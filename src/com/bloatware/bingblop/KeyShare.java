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
 * <li>It is short-lived: removed {@link #KEEP_MS} after it was made, before the next export, and at the next start of the app.</li>
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

    static File root(File cacheDir) { return new File(cacheDir, DIR); }

    /**
     * Copies {@code key} into a new private folder and returns the copy. Copies left by earlier exports are removed first (one key copy at a time). The
     * copy is atomic (no half file under the final name) and readable by this app only. Throws when the key is missing or cannot be copied; nothing is
     * left behind then.
     */
    static File stage(File cacheDir, File key) throws IOException {
        if (key == null || !key.isFile()) throw new IOException("There is no signing key yet: it is made the first time you patch.");
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
        deleteTree(dir);
        return !dir.exists();
    }

    /** Removes the staged copies made before {@code now - olderThanMs} (0: all of them). Returns how many folders were removed. */
    static int sweep(File cacheDir, long olderThanMs, long now) {
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

    /**
     * The staged key a share address names, or null. {@code segments} are the path segments of the address: exactly {@link #ROUTE}, a 16 hex digit id and
     * {@link #NAME}. A link in place of the folder or the file, or a file that is not a regular file, is refused.
     */
    static File resolve(File cacheDir, List<String> segments) {
        if (segments == null || segments.size() != 3 || !ROUTE.equals(segments.get(0)) || !ID.matcher(segments.get(1)).matches() || !NAME.equals(segments.get(2))) return null;
        File root = root(cacheDir);
        File dir = new File(root, segments.get(1));
        File f = new File(dir, NAME);
        try {
            if (java.nio.file.Files.isSymbolicLink(root.toPath()) || java.nio.file.Files.isSymbolicLink(dir.toPath()) || java.nio.file.Files.isSymbolicLink(f.toPath())) return null;
        } catch (Exception e) { return null; }
        return f.isFile() ? f : null;
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
