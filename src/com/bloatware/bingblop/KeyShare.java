package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Handing the Morphe signing key to the Android share sheet. Whoever holds the key file can sign an APK that Android installs as an update of every app
 * patched here, so it is never written to a shared folder (not Downloads, not the shared cache of ordinary shares). The one copy that leaves the app's
 * private data is made here, only when the person taps Export, and only for the share sheet:
 * <ul>
 * <li>It lives in its own folder, {@code cache/morphe_keyshare/<random id>/morphe.keystore}. That folder is not one of the places the page may name
 * (the bridge's allowed folders) and not one of the places the file manager or VirusTotal may read from (PrivatePaths.exportable): only the share
 * provider's key route reads it, for exactly this name, and only through the one-off grant on the share intent.</li>
 * <li>The folder and the file are readable by this app only. The recipient reads through the provider.</li>
 * <li>It is short-lived: removed {@link #KEEP_MS} after it was made, before the next export, and at the next start of the app (the first thing a new process
 * does is {@link #sweepAtStart}: a process that is new has no share in flight, so whatever is left is removed, however young). That first call is made
 * by the share provider's onCreate, which Android runs at the start of every process of the app, also one started only to serve a share; and
 * {@link #resolve} never serves a copy older than the keep time, so an address stops working at the keep time whatever process state there is.</li>
 * <li>One copy at a time, also when exports overlap: staging, the removal of the copy before it and the removal of a copy are serialized by one lock, so two
 * calls from the bridge's thread pool cannot both sweep before either creates its folder.</li>
 * </ul>
 * Pure Java (no android.*), so the rules can be tested with real files.
 */
final class KeyShare {
    private KeyShare() {}

    /** The name of the key file, here and in the share (the recipient sees this name). */
    static final String NAME = "morphe.keystore";
    /** The folder inside the app's cache; the share provider's "key" route reads from here and nowhere else. */
    static final String DIR = "morphe_keyshare";
    /** The first path segment of the share URI that means "the key": content://.../key/<id>/morphe.keystore. */
    static final String ROUTE = "key";
    /** How long the staged copy waits for the receiving app to read it. */
    static final long KEEP_MS = 10 * 60 * 1000L;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** One lock for every change to the key-share folder (stage, remove, sweep): sweep-then-create is one step, so two exports end with one copy. */
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean SWEPT_AT_START = new java.util.concurrent.atomic.AtomicBoolean();

    static File root(File cacheDir) { return new File(cacheDir, DIR); }

    /**
     * Copies {@code key} into a new private folder and returns the copy. Copies left by earlier exports are removed first (one key copy at a time). The
     * copy is atomic (no half file under the final name) and readable by this app only. Throws when the key is missing or cannot be copied; nothing is
     * left behind then.
     */
    static File stage(File cacheDir, File key) throws IOException {
        if (key == null || !key.isFile()) throw new IOException("There is no signing key yet: it is made the first time you patch.");
        synchronized (LOCK) { return stageLocked(cacheDir, key); }
    }

    private static File stageLocked(File cacheDir, File key) throws IOException {
        File root = root(cacheDir);
        sweep(cacheDir, 0, System.currentTimeMillis());
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException("cannot make " + root);
        ownerOnly(root, true);
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder id = new StringBuilder();
        for (byte x : b) id.append(String.format("%02x", x & 0xFF));
        File dir = new File(root, id.toString());
        File copy = new File(dir, NAME);
        boolean done = false;
        try {
            if (!dir.mkdirs()) throw new IOException("cannot make " + dir);
            ownerOnly(dir, true);
            // into a hidden .part file first, so no half file ever has the final name (the folder is closed to other apps from the first moment)
            File part = new File(dir, "." + NAME + ".part");
            java.nio.file.Files.copy(key.toPath(), part.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ownerOnly(part, false);
            java.nio.file.Files.move(part.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            done = copy.isFile() && copy.length() == key.length();
            if (!done) throw new IOException("the copy of the key is not whole");
        } finally {
            if (!done) remove(copy);
        }
        return copy;
    }

    /** Removes a staged copy and its folder (and a leftover partial file); a path that is not a staged copy is left alone. Returns whether it is gone. */
    static boolean remove(File staged) {
        if (staged == null) return true;
        File dir = staged.getParentFile();
        if (dir == null || !ID.matcher(dir.getName()).matches() || !NAME.equals(staged.getName()) || dir.getParentFile() == null || !DIR.equals(dir.getParentFile().getName())) return false;
        synchronized (LOCK) {
            deleteTree(dir);
            return !dir.exists();
        }
    }

    /** Removes the staged copies made before {@code now - olderThanMs} (0: all of them). Returns how many folders were removed. */
    static int sweep(File cacheDir, long olderThanMs, long now) {
        synchronized (LOCK) {
            File[] dirs = root(cacheDir).listFiles();
            int n = 0;
            if (dirs == null) return 0;
            for (File d : dirs) {
                if (olderThanMs > 0 && d.lastModified() > now - olderThanMs) continue;
                deleteTree(d);
                if (!d.exists()) n++;
            }
            return n;
        }
    }

    /**
     * The start-up sweep: removes every staged copy, whatever its age, the first time it is called in this process, and does nothing after that. A copy that
     * is there when the process starts was left by a process that died, and no share of it can be in flight (the timer that would remove it died with that
     * process), so it is not kept for the keep time. The share provider calls this from onCreate (the first thing of every process of the app, before any
     * address is served) and the activity from onCreate, which also runs again in a live process (the window is recreated or opened again); then a share
     * sheet may still be reading the copy, so only the first call of the process sweeps.
     * Returns how many folders were removed.
     */
    static int sweepAtStart(File cacheDir) {
        if (!SWEPT_AT_START.compareAndSet(false, true)) return 0;
        return sweep(cacheDir, 0, System.currentTimeMillis());
    }

    /**
     * The staged key a share address names, or null. {@code segments} are the path segments of the address: exactly {@link #ROUTE}, a 16 hex digit id and
     * {@link #NAME}. A link in place of the folder or the file, or a file that is not a regular file, is refused.
     * <p>A copy older than {@link #KEEP_MS} is never served, whatever state the process is in: the timer that removes it lives in the process that made
     * it, and a receiving app that still holds the grant can open the address after that process died (Android then starts the app only for the
     * provider). Such a copy is removed here, and null is returned.
     */
    static File resolve(File cacheDir, List<String> segments) { return resolve(cacheDir, segments, System.currentTimeMillis()); }

    static File resolve(File cacheDir, List<String> segments, long now) {
        if (segments == null || segments.size() != 3 || !ROUTE.equals(segments.get(0)) || !ID.matcher(segments.get(1)).matches() || !NAME.equals(segments.get(2))) return null;
        File root = root(cacheDir);
        File dir = new File(root, segments.get(1));
        File f = new File(dir, NAME);
        try {
            if (java.nio.file.Files.isSymbolicLink(root.toPath()) || java.nio.file.Files.isSymbolicLink(dir.toPath()) || java.nio.file.Files.isSymbolicLink(f.toPath())) return null;
        } catch (Exception e) { return null; }
        if (!f.isFile()) return null;
        if (now - dir.lastModified() > KEEP_MS) {                       // the copy's age is the age of its folder (made when the copy was)
            synchronized (LOCK) { deleteTree(dir); }
            return null;
        }
        return f;
    }

    /** The path segments a share address for {@code staged} has (the inverse of {@link #resolve}). */
    static String[] segments(File staged) {
        return new String[] {ROUTE, staged.getParentFile().getName(), staged.getName()};
    }

    private static void ownerOnly(File f, boolean dir) {
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false);
        f.setReadable(true, true); f.setWritable(true, true);
        if (dir) f.setExecutable(true, true);
    }

    private static void deleteTree(File f) {
        File[] kids = java.nio.file.Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
