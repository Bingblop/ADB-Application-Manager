package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A Morphe patch bundle (.mpp) is program code: the engine runs it inside this app's user id as soon as it reads the file. This class decides whether a
 * custom bundle may be handed to the engine. A bundle is approved when the SHA-256 of the file now on disk is the one recorded for its source in the
 * approvals file (a JSON file in the Morphe folder, source id -> sha256), so a source that changes under the person (an update, a swapped file) is a
 * new question. The pre-installed source is exempt, judged from the store's own record and never from what the page says. Bundles that were already on
 * disk when this code first ran are approved once ("grandfathered", guarded by a marker in the same file) so an update does not start with a wall of
 * questions. Anything damaged means "nothing approved", never a crash and never a fresh grandfathering.
 *
 * The question itself is asked through {@link Asker} (a native dialog of the app that the page cannot press, see MainActivity), one at a time, and
 * only after the app has worked out what to say from facts it found out itself; the text is built here, from untrusted values only inside quotation
 * marks (the same rules as {@link InstallConfirm}). Pure Java (org.json only), no android.* classes.
 */
final class BundleApprovals {
    /** How long a question waits for an answer; no answer in this time is a no. MainActivity uses the same number. */
    static final int ANSWER_WAIT_SECONDS = 120;
    static final String NOT_ALLOWED = "Not allowed: you did not approve this source";

    private static final Pattern ID_OK = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern SHA_OK = Pattern.compile("[0-9a-f]{64}");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Asks the person; true only for an explicit yes. May block (up to {@link #ANSWER_WAIT_SECONDS}); no answer is false. */
    interface Asker {
        boolean ask(String title, String text);
    }

    interface Clock {
        long nowMs();
    }

    /** What is known about one bundle on disk. The fields the source wrote itself (name, version, repo) are shown as "not checked". */
    static final class Source {
        String id = "", name = "", kind = "remote", url = "", repo = "", version = "", fileHost = "";
        boolean builtIn, sumMatched;
        File file;

        /** From MorpheStore.bundleFacts(id) and the bundle file. */
        static Source of(JSONObject facts, File file) {
            Source s = new Source();
            s.file = file;
            if (facts == null) return s;
            s.id = facts.optString("id");
            s.name = facts.optString("name");
            s.kind = facts.optString("kind", "remote");
            s.url = facts.optString("url");
            s.repo = facts.optString("repo");
            s.version = facts.optString("version");
            s.fileHost = facts.optString("fileHost");
            s.builtIn = facts.optBoolean("builtIn");
            s.sumMatched = facts.optBoolean("sumMatched");
            return s;
        }

        boolean local() { return "local".equals(kind); }
    }

    private final File store;
    private final Clock clock;
    private final Map<String, String> approved = new HashMap<String, String>();
    private boolean grandfathered;
    private boolean damaged;
    private final Object askLock = new Object();                // one question on screen at a time, across catalog and patch

    BundleApprovals(File store) {
        this(store, new Clock() { @Override public long nowMs() { return System.currentTimeMillis(); } });
    }

    BundleApprovals(File store, Clock clock) {
        this.store = store;
        this.clock = clock;
        load();
    }

    private void load() {
        if (!store.exists()) return;                             // a first start: grandfatherOnce() may fill it in
        grandfathered = true;                                    // a file that is there, readable or not, is never a first start again
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(store.toPath()), UTF8));
            JSONObject map = root.getJSONObject("approved");
            grandfathered = root.optBoolean("grandfathered", false);
            for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                String id = it.next();
                String sha = map.optString(id, "");
                if (ID_OK.matcher(id).matches() && SHA_OK.matcher(sha).matches()) approved.put(id, sha);
            }
            if (!grandfathered) { grandfathered = true; damaged = true; approved.clear(); }   // a file without its marker is not one this code wrote: do not approve what is on disk
        } catch (Exception e) {
            approved.clear();
            damaged = true;
            grandfathered = true;
        }
    }

    /** Whether the approvals file was unreadable (nothing is approved until the person approves again). */
    synchronized boolean wasDamaged() { return damaged; }

    synchronized boolean isApproved(String id, String sha256) {
        return id != null && sha256 != null && sha256.equals(approved.get(id));
    }

    /** The hash approved for a source, or null. */
    synchronized String approvedHash(String id) { return approved.get(id); }

    /** Records the approval of this exact file for this source (and keeps nothing of an older one). */
    synchronized void approve(String id, String sha256) {
        if (id == null || !ID_OK.matcher(id).matches() || sha256 == null || !SHA_OK.matcher(sha256).matches()) return;
        approved.put(id, sha256);
        grandfathered = true;
        save();
    }

    /** A source was removed: its approval goes with it. */
    synchronized void forget(String id) {
        if (approved.remove(id) != null) save();
    }

    /**
     * The first time this code runs (no approvals file yet): every bundle already on disk is approved as it is, and the marker is written, so this
     * happens once. Not when the file exists, damaged or not. {@code present}: source id -> its bundle file (the pre-installed source needs no entry).
     * @return whether anything was done
     */
    synchronized boolean grandfatherOnce(Map<String, File> present) {
        if (grandfathered) return false;
        for (Map.Entry<String, File> e : present.entrySet()) {
            File f = e.getValue();
            if (f == null || !f.isFile() || !ID_OK.matcher(e.getKey()).matches()) continue;
            try { approved.put(e.getKey(), sha256(f)); } catch (IOException ignored) { /* this one will be asked about */ }
        }
        grandfathered = true;
        save();
        return true;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("grandfathered", grandfathered);
            JSONObject map = new JSONObject();
            for (Map.Entry<String, String> e : approved.entrySet()) map.put(e.getKey(), e.getValue());
            root.put("approved", map);
            File dir = store.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(store.getPath() + ".new");
            Files.write(tmp.toPath(), root.toString(2).getBytes(UTF8));
            try {
                Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            /* the approval holds for this run; it is asked again after a restart */
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- the decision

    /** Whether this bundle would be handed to the engine now without a question (the pre-installed source, or this exact file approved before). */
    boolean allowedNow(Source s) {
        if (s == null || s.file == null || !s.file.isFile()) return false;
        if (s.builtIn) return true;
        try { return isApproved(s.id, sha256(s.file)); } catch (IOException e) { return false; }
    }

    /** The sources of this list that a question would be asked about (not exempt, not approved as they are). */
    java.util.ArrayList<Source> unapproved(List<Source> list) {
        java.util.ArrayList<Source> out = new java.util.ArrayList<Source>();
        for (Source s : list) if (s != null && s.file != null && s.file.isFile() && !allowedNow(s)) out.add(s);
        return out;
    }

    /**
     * Makes sure every bundle of the list is approved, asking about each one that is not, one question at a time and in order. A yes is remembered
     * (for this exact file). If a question gets no answer in the whole waiting time, the sources after it in this call are not asked: they are refused
     * the same way, so a phone that is lying on a table is not asked for minutes more.
     * @return source id -> the error to show, for every source that is NOT allowed (an empty map: all allowed)
     */
    Map<String, String> requireAll(List<Source> list, Asker asker) {
        Map<String, String> refused = new LinkedHashMap<String, String>();
        boolean[] unanswered = { false };
        for (Source s : list) {
            if (s == null) continue;
            String why = require(s, asker, unanswered);
            if (why != null) refused.put(s.id, why);
        }
        return refused;
    }

    /** One bundle: null when it is allowed, else the error to show. */
    String require(Source s, Asker asker) {
        return require(s, asker, new boolean[] { false });
    }

    private String require(Source s, Asker asker, boolean[] unanswered) {
        if (s.file == null || !s.file.isFile()) return "Not allowed: the bundle file is not there";
        if (s.builtIn) return null;
        String sha;
        try { sha = sha256(s.file); } catch (IOException e) { return "Not allowed: the bundle file could not be read"; }
        synchronized (askLock) {
            String before = approvedHash(s.id);
            if (sha.equals(before)) return null;                  // answered by a question that was on screen before this one
            if (unanswered[0]) return NOT_ALLOWED;
            long t0 = clock.nowMs();
            boolean yes;
            try {
                yes = asker.ask(title(), message(s, sha, s.file.length(), before));
            } catch (RuntimeException e) {
                yes = false;
            }
            if (yes) { approve(s.id, sha); return null; }
            if (clock.nowMs() - t0 >= ANSWER_WAIT_SECONDS * 1000L - 3000L) unanswered[0] = true;
            return NOT_ALLOWED;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- the text

    static String title() {
        return "Allow this patch source to run?";
    }

    /**
     * The text of the question. What the app found out itself comes first, one fact to a line: which source, its address and the host the file really
     * came from, the size, the start of the SHA-256 of the file on disk, whether a published checksum matched, and whether this source was approved
     * before. What the source wrote (its name, version, repository) comes after, labelled as not checked and always inside quotation marks.
     * @param previous the hash approved before for this source, or null
     */
    static String message(Source s, String sha256, long size, String previous) {
        StringBuilder sb = new StringBuilder();
        String id = InstallConfirm.clean(s.id, 80);
        sb.append("Source: ").append(id.isEmpty() ? "unknown" : id).append(s.local() ? " (a file from this phone)" : "").append("\n");
        String addr = s.local() ? "" : InstallConfirm.hostText(InstallConfirm.hostOf(s.url));
        sb.append("Source address: ").append(s.local() ? "none (a file from this phone)" : addr.isEmpty() ? "unknown" : addr).append("\n");
        if (!s.local()) {
            String from = InstallConfirm.hostText(s.fileHost);
            sb.append("File downloaded from: ").append(from.isEmpty() ? "unknown" : from).append("\n");
        }
        sb.append("File size: ").append(sizeText(size)).append("\n");
        String fp = sha256 == null || sha256.length() < 12 ? "" : sha256.substring(0, 12).toLowerCase(Locale.ROOT);
        sb.append("File fingerprint (SHA-256, first 12 characters): ").append(fp.isEmpty() ? "unknown" : fp).append("\n");
        if (s.local()) sb.append("A file from this phone has no published checksum, so it could not be compared with one.\n");
        else if (s.sumMatched) sb.append("The file matches the checksum the source published (it does not show who made the file).\n");
        else sb.append("The source published no checksum, so the file could not be compared with one.\n");
        if (previous == null) sb.append("You have not approved this source before.\n");
        else if (!previous.equals(sha256)) sb.append("The file is different from the one you approved before.\n");
        sb.append("\nWritten by the source (not checked):\n");
        String name = InstallConfirm.quoted(s.name, 60);
        if (!name.isEmpty()) sb.append("Name: ").append(name).append("\n");
        String v = InstallConfirm.quoted(s.version, 40);
        if (!v.isEmpty()) sb.append("Version: ").append(v).append("\n");
        String repo = s.local() ? "" : InstallConfirm.quoted(s.repo, 120);
        if (!repo.isEmpty()) sb.append("Repository: ").append(repo).append("\n");
        sb.append("\nA patch source is program code. It runs inside this app, with the app's permissions, as soon as it is read. Only allow it if you trust who made it.");
        return sb.toString();
    }

    /** "512 bytes", "3.4 KB (3482 bytes)", "12.0 MB (12582912 bytes)". */
    static String sizeText(long b) {
        if (b < 0) return "unknown";
        if (b < 1024) return b + " bytes";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB (%d bytes)", b / 1024.0, b);
        return String.format(Locale.ROOT, "%.1f MB (%d bytes)", b / (1024.0 * 1024.0), b);
    }

    static String sha256(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
