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
 * What the engine reads is a private copy made and hashed in one pass ({@link #approvedCopy}), never the live file, so a source that is updated after the check
 * cannot change what runs.
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

    /** What the person said. TIMEOUT is only "nobody answered in the whole waiting time"; an explicit "Don't allow" (however late) is DENY. */
    enum Answer { ALLOW, DENY, TIMEOUT }

    /** Asks the person. May block (up to {@link #ANSWER_WAIT_SECONDS}). Only ALLOW lets a bundle through; anything else, an exception or null included, is a no. */
    interface Asker {
        Answer ask(String title, String text);
    }

    /** What is known about one bundle on disk. The fields the source wrote itself (name, version, repo) are shown as "not checked". */
    static final class Source {
        String id = "", name = "", kind = "remote", url = "", repo = "", version = "", fileHost = "";
        boolean builtIn;
        /** "matched", "none" (the app saw that no checksum was published) or anything else: not known (a source recorded before the app kept this). */
        String sum = "";
        /** The SHA-256 of the file that fileHost and sum describe (the file as the app downloaded it); "" for a record that did not keep it. They are shown only when it is the file being approved. */
        String factSha = "";
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
            s.sum = facts.optString("sum");
            s.factSha = facts.optString("factSha");
            return s;
        }

        boolean local() { return "local".equals(kind); }
    }

    private final File store;
    private final Map<String, String> approved = new HashMap<String, String>();
    private boolean grandfathered;
    private boolean damaged;
    private boolean persisted;                                   // the approvals file (with its marker) is known to be on disk
    private final Object askLock = new Object();                // one question on screen at a time, across catalog and patch

    BundleApprovals(File store) {
        this.store = store;
        load();
    }

    private void load() {
        if (!store.exists()) return;                             // a first start: grandfatherOnce() may fill it in
        persisted = true;
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
     * The marker and the entries are committed in one atomic write BEFORE anything is granted: if that write fails nothing is approved (fail closed,
     * the person is asked about everything), because a "first start" that could not be recorded would otherwise repeat at the next start and approve
     * whatever was added and refused in between. The write is tried again at every later question until it succeeds.
     * @return whether anything was done
     */
    synchronized boolean grandfatherOnce(Map<String, File> present) {
        if (grandfathered) return false;
        Map<String, String> next = new HashMap<String, String>(approved);
        for (Map.Entry<String, File> e : present.entrySet()) {
            File f = e.getValue();
            if (f == null || !f.isFile() || !ID_OK.matcher(e.getKey()).matches()) continue;
            try { next.put(e.getKey(), sha256(f)); } catch (IOException ignored) { /* this one will be asked about */ }
        }
        grandfathered = true;                                    // in memory: this run never grandfathers again
        if (write(next, true)) approved.putAll(next);            // granted only once the marker is on disk
        return true;
    }

    private void save() {
        write(approved, grandfathered);
    }

    /** Writes the file (temp file, then a move over the old one). @return whether it is on disk now */
    private boolean write(Map<String, String> entries, boolean marker) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("grandfathered", marker);
            JSONObject map = new JSONObject();
            for (Map.Entry<String, String> e : entries.entrySet()) map.put(e.getKey(), e.getValue());
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
            persisted = true;
            return true;
        } catch (Exception e) {
            return false;                                        // an approval then holds for this run only; it is asked again after a restart
        }
    }

    /** The marker could not be written at first: try again now (a refused question is the usual moment, nothing else would write the file). */
    private synchronized void persistMarker() {
        if (grandfathered && !persisted) save();
    }

    // ---------------------------------------------------------------------------------------------------------------- the decision

    /** Whether this bundle would be handed to the engine now without a question (the pre-installed source, or this exact file approved before). */
    boolean allowedNow(Source s) {
        if (s == null || s.file == null || !s.file.isFile()) return false;
        if (s.builtIn) return true;
        try { return isApproved(s.id, sha256(s.file)); } catch (IOException e) { return false; }
    }

    /**
     * Makes sure every bundle of the list is approved, asking about each one that is not, one question at a time and in order. A yes is remembered
     * (for this exact file). If a question gets no answer in the whole waiting time (the asker says TIMEOUT), the sources after it in this call are
     * not asked: they are refused the same way, so a phone that is lying on a table is not asked for minutes more. An explicit "Don't allow" never
     * does that, however late it comes.
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
        if (unanswered[0] && approvedHash(s.id) == null) return NOT_ALLOWED;   // nobody answered in this call: a file that cannot match any approval is not even read (it may be GBs)
        String sha;
        try { sha = sha256(s.file); } catch (IOException e) { return "Not allowed: the bundle file could not be read"; }
        synchronized (askLock) {
            String before = approvedHash(s.id);
            if (sha.equals(before)) return null;                  // answered by a question that was on screen before this one
            persistMarker();
            if (unanswered[0]) return NOT_ALLOWED;
            Answer answer;
            try {
                answer = asker.ask(title(), message(s, sha, s.file.length(), before));
            } catch (RuntimeException e) {
                answer = Answer.DENY;
            }
            if (answer == Answer.ALLOW) { approve(s.id, sha); return null; }
            if (answer == Answer.TIMEOUT) unanswered[0] = true;
            return NOT_ALLOWED;
        }
    }

    /**
     * The bundle to give the engine: a private copy of the file, made in {@code destDir} and hashed in the same pass, and the copy only if that hash is
     * the one approved for this source (the pre-installed source is handed out as it is). The engine is given the copy, never the live path, so a
     * source that is updated or swapped after the check cannot change what runs. Null (and no copy left) when it is not approved, the file is gone or
     * cannot be read.
     */
    File approvedCopy(Source s, File destDir) {
        return approvedCopy(s, destDir, null);
    }

    /** As {@link #approvedCopy(Source, File)}; {@code shaOut[0]} gets the SHA-256 of the copy that was handed out (null for the pre-installed source, which is not copied). */
    File approvedCopy(Source s, File destDir, String[] shaOut) {
        if (shaOut != null) shaOut[0] = null;
        if (s == null || s.file == null || !s.file.isFile()) return null;
        if (s.builtIn) return s.file;
        if (s.id == null || !ID_OK.matcher(s.id).matches()) return null;
        File copy = new File(destDir, s.id + ".mpp");
        try {
            String got = copyAndHash(s.file, copy);
            if (isApproved(s.id, got)) { if (shaOut != null) shaOut[0] = got; return copy; }
        } catch (IOException ignored) { /* not readable: not handed out */ }
        copy.delete();
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------- the catalog cache

    /**
     * The name of the file a bundle's catalog (its list of patches) is cached in. A custom bundle is keyed by the SHA-256 of the approved file, so an
     * updated or swapped bundle with the same version and the same size never gets the old bundle's list; the pre-installed source keeps its
     * version-and-size name. {@code safeId} and {@code safeVersion} are already file-name safe.
     */
    static String catalogCacheName(String safeId, boolean builtIn, String safeVersion, long length, String sha256) {
        return builtIn ? safeId + "-" + safeVersion + "-" + length + ".json" : safeId + "-sha-" + sha256 + ".json";
    }

    /** Deletes the cached catalogs of this custom source that belong to any other file than {@code keepName} (they describe bundles that are gone). */
    static void dropOtherCatalogs(File cacheDir, String safeId, String keepName) {
        File[] all = cacheDir.listFiles();
        if (all == null) return;
        for (File f : all) {
            java.util.regex.Matcher m = CACHE_SHA.matcher(f.getName());
            if (m.matches() && m.group(1).equals(safeId) && !f.getName().equals(keepName)) f.delete();
        }
    }

    private static final Pattern CACHE_SHA = Pattern.compile("(.*)-sha-[0-9a-f]{64}\\.json");

    /** Copies {@code from} to {@code to} (a new file in a folder of this app) and returns the SHA-256 of the bytes copied, in one pass. */
    static String copyAndHash(File from, File to) throws IOException {
        File dir = to.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        Files.deleteIfExists(to.toPath());
        try (java.nio.channels.FileChannel inChannel = java.nio.channels.FileChannel.open(from.toPath(), java.nio.file.StandardOpenOption.READ);
             java.nio.channels.FileChannel outChannel = java.nio.channels.FileChannel.open(to.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(524288);
            while (inChannel.read(buf) > 0) {
                buf.flip();
                md.update(buf.duplicate());
                while (buf.hasRemaining()) {
                    outChannel.write(buf);
                }
                buf.clear();
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
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
        boolean bound = !s.factSha.isEmpty() && s.factSha.equals(sha256);        // the host and the checksum status were recorded for these very bytes, not for an earlier file of the source
        String id = InstallConfirm.clean(s.id, 80);
        sb.append("Source: ").append(id.isEmpty() ? "unknown" : id).append(s.local() ? " (a file from this phone)" : "").append("\n");
        String addr = s.local() ? "" : InstallConfirm.hostText(InstallConfirm.hostOf(s.url));
        sb.append("Source address: ").append(s.local() ? "none (a file from this phone)" : addr.isEmpty() ? "unknown" : addr).append("\n");
        if (!s.local()) {
            String from = bound ? InstallConfirm.hostText(s.fileHost) : "";
            sb.append("File downloaded from: ").append(from.isEmpty() ? "unknown" : from).append("\n");
        }
        sb.append("File size: ").append(sizeText(size)).append("\n");
        String fp = sha256 == null || sha256.length() < 12 ? "" : sha256.substring(0, 12).toLowerCase(Locale.ROOT);
        sb.append("File fingerprint (SHA-256, first 12 characters): ").append(fp.isEmpty() ? "unknown" : fp).append("\n");
        if (s.local()) sb.append("A file from this phone has no published checksum, so it could not be compared with one.\n");
        else if (bound && "matched".equals(s.sum)) sb.append("The file matches the checksum the source published (it does not show who made the file).\n");
        else if (bound && "none".equals(s.sum)) sb.append("The source published no checksum, so the file could not be compared with one.\n");
        else sb.append("The app has no record of whether the source published a checksum for this exact file, so nothing is known about one.\n");
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
        if (b < 1024) return b == 1 ? "1 byte" : b + " bytes";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB (%d bytes)", b / 1024.0, b);
        return String.format(Locale.ROOT, "%.1f MB (%d bytes)", b / (1024.0 * 1024.0), b);
    }

    static String sha256(File f) throws IOException {
        try (java.nio.channels.FileChannel inChannel = java.nio.channels.FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(524288);
            while (inChannel.read(buf) > 0) {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
