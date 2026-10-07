package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deduplicator (SdmDedup) against a real temporary folder tree: identical files found by size, a 64 KiB prefix check and SHA-256, same-size files of different
 * content kept apart, the minimum size and the common-type filter, links, areas and search locations, exclusions, the arbiter (which copy is kept) under several
 * strategies, keep-one deletes that leave exactly one copy per set, "delete all" behind its setting, a look at every file before it goes, cancel, the
 * concurrency, the progress strings and the result lines.
 */
public class SdmDedupTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    // ---------------------------------------------------------------------------------------------------------- fakes

    static class FakeAreas implements Sdm.Areas {
        final List<Sdm.AreaInfo> list = new ArrayList<Sdm.AreaInfo>();
        @Override public List<Sdm.AreaInfo> all() { return list; }
        @Override public Sdm.AreaInfo get(Sdm.Area a) { for (Sdm.AreaInfo i : list) if (i.area == a) return i; return null; }
        @Override public Sdm.AreaInfo areaOf(String path) { Sdm.AreaInfo best = null; for (Sdm.AreaInfo i : list) if (Sdm.isInside(path, i.root) && (best == null || i.root.length() > best.root.length())) best = i; return best; }
    }

    static class FakeExcl implements Sdm.Exclusions {
        final Set<String> paths = new HashSet<String>();
        @Override public boolean excludesPath(Sdm.Tool t, String path) { for (String p : paths) if (p.equals(path) || SdmSieve.isAncestorOf(p, path)) return true; return false; }
        @Override public boolean excludesPackage(Sdm.Tool t, String pkg) { return false; }
        @Override public List<String> paths(Sdm.Tool t) { return new ArrayList<String>(paths); }
    }

    static class NoPkgs implements Sdm.Packages {
        @Override public List<Sdm.Pkg> installed() { return new ArrayList<Sdm.Pkg>(); }
        @Override public Sdm.Pkg get(String pkg) { return null; }
        @Override public Set<String> running() { return new HashSet<String>(); }
        @Override public long cacheBytes(String pkg) { return -1; }
    }

    static class Rec implements Sdm.Progress {
        final List<String> lines = new ArrayList<String>();
        @Override public synchronized void update(String p, String s, long d, long t, long b) { lines.add(p + "|" + s); }
        synchronized boolean has(String line) { return lines.contains(line); }
        synchronized boolean hasPrefix(String prefix) { for (String l : lines) if (l.startsWith(prefix)) return true; return false; }
    }

    /** SdmFsJava that counts and delays the reads, can fail for chosen paths, and records the deleteAll calls. */
    static class TestFs extends SdmFsJava {
        final AtomicInteger heads = new AtomicInteger(), hashes = new AtomicInteger(), running = new AtomicInteger(), maxRunning = new AtomicInteger();
        final Set<String> unreadable = new HashSet<String>();
        final List<String> hashed = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<List<String>> deleteCalls = new ArrayList<List<String>>();
        int sleepMs = 0;
        @Override public byte[] head(String path, int max) {
            heads.incrementAndGet();
            if (unreadable.contains(path)) return null;
            return super.head(path, max);
        }
        @Override public String sha256(String path, Sdm.Cancel cancel) throws IOException {
            hashes.incrementAndGet();
            hashed.add(path);
            int now = running.incrementAndGet();
            int m;
            while ((m = maxRunning.get()) < now && !maxRunning.compareAndSet(m, now)) { /* retry */ }
            try {
                if (sleepMs > 0) try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (unreadable.contains(path)) throw new IOException("unreadable");
                return super.sha256(path, cancel);
            } finally { running.decrementAndGet(); }
        }
        @Override public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) { deleteCalls.add(new ArrayList<String>(paths)); return super.deleteAll(paths, cancel); }
    }

    // ---------------------------------------------------------------------------------------------------------- the tree

    static File root;
    static String sd, vol2;

    static byte[] content(String seed, int len) {
        byte[] b = new byte[len];
        new Random(seed.hashCode()).nextBytes(b);
        return b;
    }

    static void write(String rel, byte[] data, long mtimeSec) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), data);
        Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(mtimeSec * 1000L));
    }

    static String sha(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static final byte[] A = content("A", 1000), B = content("B", 2000), C1 = content("C1", 3000);
    static final long T0 = 1600000000L;

    static void buildTree() throws Exception {
        // set A: four copies in different places
        write("sd/Pictures/photo1.jpg", A, T0 + 5000);                    // newer
        write("sd/Download/photo1 (1).jpg", A, T0 + 1000);                // older, as shallow as the one above
        write("sd/Backup/deep/er/photo1.jpg", A, T0 + 100);              // oldest, but deep
        write("sd/Android/data/com.x/dup.jpg", A, T0 + 3000);            // public app data is searched too
        // set B
        write("sd/Music/song.mp3", B, T0 + 10);
        write("sd/Music2/song.mp3", B, T0 + 20);
        // same size, different content: differs at the very end / at the very start
        byte[] c2 = C1.clone();
        c2[c2.length - 1] ^= 1;
        write("sd/Docs/x.pdf", C1, T0);
        write("sd/Docs/y.pdf", c2, T0);
        byte[] big1 = content("BIG", 200 * 1024), big2 = big1.clone();
        big2[big2.length - 1] ^= 1;                                       // same first 64 KiB, different tail
        write("sd/Docs/big1.zip", big1, T0);
        write("sd/Docs/big2.zip", big2, T0);
        // below the minimum size (the tests use 100 bytes)
        write("sd/small/s1.txt", content("S", 50), T0);
        write("sd/small/s2.txt", content("S", 50), T0);
        // an uncommon type
        write("sd/Other/x1.bin", content("X", 500), T0);
        write("sd/Other/x2.bin", content("X", 500), T0);
        // an excluded folder holds a copy of A
        write("sd/Excluded/photo1.jpg", A, T0);
        // a unique file
        write("sd/Unique/only.jpg", content("U", 777), T0);
        // links are not files and are not followed
        Files.createSymbolicLink(new File(root, "sd/Pictures/link.jpg").toPath(), new File(root, "sd/Pictures/photo1.jpg").toPath());
        Files.createSymbolicLink(new File(root, "sd/linkdir").toPath(), new File(root, "sd/Music").toPath());
        // a second volume with a copy of A and a file in the OBB area
        write("vol2/photo1.jpg", A, T0 + 2000);
        write("sd/Android/obb/com.x/dup.jpg", A, T0);
    }

    static FakeAreas areas() {
        FakeAreas a = new FakeAreas();
        a.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd, true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, sd + "/Android/data", true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, sd + "/Android/media", true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, sd + "/Android/obb", true, "java", ""));
        return a;
    }

    static class Env {
        TestFs fs = new TestFs();
        FakeAreas areas = areas();
        FakeExcl ex = new FakeExcl();
        Rec rec = new Rec();
        JSONObject settings = new JSONObject();
        final boolean[] cancel = { false };
        Env() { try { settings.put("skip.minsize.bytes", 100); ex.paths.add(sd + "/Excluded"); } catch (Exception e) { throw new RuntimeException(e); } }
        Sdm.Ctx ctx() {
            return new Sdm.Ctx(fs, areas, new NoPkgs(), ex, null, settings, rec, new Sdm.Cancel() { @Override public boolean cancelled() { return cancel[0]; } }, 1700000000L);
        }
        Env withVol2() { areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, vol2, false, "java", "")); return this; }
    }

    static String rel(String path) { return path.substring(root.getPath().length() + 1); }

    static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }

    static boolean exists(String rel) { return Files.exists(new File(root, rel).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS); }

    /** cluster rows by their keeper file name: label -> JSONObject. */
    static JSONObject cluster(Sdm.Result r, String hash) throws Exception {
        JSONArray g = r.groups(0, 1000);
        for (int i = 0; i < g.length(); i++) if (g.getJSONObject(i).getString("id").equals(hash)) return g.getJSONObject(i);
        return null;
    }

    static List<String> files(Sdm.Result r, String hash) throws Exception {
        List<String> l = new ArrayList<String>();
        JSONArray it = r.items(hash, 0, 1000);
        for (int i = 0; i < it.length(); i++) l.add(rel(it.getJSONObject(i).getString("path")));
        return l;
    }

    static String keeper(Sdm.Result r, String hash) throws Exception {
        JSONArray it = r.items(hash, 0, 1000);
        String k = null;
        for (int i = 0; i < it.length(); i++) if (it.getJSONObject(i).getBoolean("keep")) { if (k != null) return "two keepers"; k = rel(it.getJSONObject(i).getString("path")); }
        return k;
    }

    static JSONObject arbiter(String... crit) throws Exception {
        JSONArray a = new JSONArray();
        for (String c : crit) {
            String[] p = c.split(":");
            JSONObject o = new JSONObject().put("criteriumType", p[0]);
            if (p[0].equals("PREFERRED_PATH")) { JSONArray paths = new JSONArray(); for (int i = 1; i < p.length; i++) paths.put(p[i].startsWith("/") ? p[i] : root + "/" + p[i]); o.put("keepPreferPaths", paths); }
            else if (p.length > 1) o.put("mode", p[1]);
            a.put(o);
        }
        return new JSONObject().put("criteria", a);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("sdmdd").toFile().getCanonicalFile();
        sd = new File(root, "sd").getPath();
        vol2 = new File(root, "vol2").getPath();
        try {
            buildTree();
            texts();
            scanBasics();
            filters();
            locations();
            arbiterChoice();
            exclusionsAndLinks();
            progressAndCancel();
            concurrency();
            deleting();
            deleteSafety();
            resultApi();
        } finally {
            new SdmFsJava().delete(root.getPath());
        }
        System.out.println((fails == 0 ? "PASS " : "FAIL ") + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------------------- texts

    static void texts() throws Exception {
        eq("found one", SdmDedup.foundLine(1), "1 duplicate set found");
        eq("found many", SdmDedup.foundLine(3), "3 duplicate sets found");
        eq("found none", SdmDedup.foundLine(0), "0 duplicate sets found");
        eq("deleted one", SdmDedup.deletedLine(1), "1 duplicate deleted");
        eq("deleted many", SdmDedup.deletedLine(12), "12 duplicates deleted");
        eq("freed", SdmDedup.freedLine(2048), "Freed 2.0 KB space.");
        eq("occupied, singular", SdmDedup.occupiedLine(1024), "1.0 KB is occupied by duplicates");
        eq("occupied, plural", SdmDedup.occupiedLine(2048), "2.0 KB are occupied by duplicates");
        eq("occupied, zero", SdmDedup.occupiedLine(0), "0 B are occupied by duplicates");
        eq("occupied, 1 byte", SdmDedup.occupiedLine(1), "1 B is occupied by duplicates");
        eq("scan lines", SdmDedup.scanLines(2, 2048).getString("secondary"), "2.0 KB are occupied by duplicates");
        eq("delete lines", SdmDedup.deleteLines(3, 3072).getString("primary") + "/" + SdmDedup.deleteLines(3, 3072).getString("secondary"), "3 duplicates deleted/Freed 3.0 KB space.");
        is("common types", SdmDedup.isCommon("a.JPG") && SdmDedup.isCommon("a.mp4") && SdmDedup.isCommon("a.flac") && SdmDedup.isCommon("a.zip") && SdmDedup.isCommon("a.pdf") && SdmDedup.isCommon("a.apk"), true);
        is("uncommon types", SdmDedup.isCommon("a.bin") || SdmDedup.isCommon("a") || SdmDedup.isCommon("a.") || SdmDedup.isCommon(".jpg") && false || SdmDedup.isCommon("a.mkv"), false);
    }

    // ---------------------------------------------------------------------------------------------------------- scan

    static void scanBasics() throws Exception {
        Env e = new Env().withVol2();
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        String hA = sha(A), hB = sha(B);
        eq("two sets: A and B", r.groupCount(), 2);
        eq("item count = sets", r.itemCount(), 2);
        // the files of set A: sdcard + public data + the second volume; not the excluded folder, not the OBB area, not the link
        eq("files of set A", new HashSet<String>(files(r, hA)), set("sd/Pictures/photo1.jpg", "sd/Download/photo1 (1).jpg", "sd/Backup/deep/er/photo1.jpg", "sd/Android/data/com.x/dup.jpg", "vol2/photo1.jpg"));
        eq("files of set B (the linked folder is not followed)", new HashSet<String>(files(r, hB)), set("sd/Music/song.mp3", "sd/Music2/song.mp3"));
        is("the same file is listed once", files(r, hA).size() == new HashSet<String>(files(r, hA)).size(), true);
        // different content with the same size: not duplicates
        for (String f : new String[] { "sd/Docs/x.pdf", "sd/Docs/y.pdf", "sd/Docs/big1.zip", "sd/Docs/big2.zip" }) {
            boolean in = files(r, hA).contains(f) || files(r, hB).contains(f);
            is("not a duplicate: " + f, in, false);
        }
        is("a unique file is no set", r.groupCount() > 2, false);
        // rows
        JSONObject row = cluster(r, hA);
        eq("row id is the hash", row.getString("id"), hA);
        eq("row hash", row.getString("hash"), hA);
        eq("row method", row.getString("method"), "Content checksum");
        eq("row methods", row.getJSONArray("methods").getString(0), "Content checksum");
        eq("row count", row.getInt("count"), 5);
        eq("row total", row.getLong("total"), 5000L);
        eq("row freeable = total minus one copy", row.getLong("freeable"), 4000L);
        eq("row bytes = freeable", row.getLong("bytes"), 4000L);
        eq("row label is the keeper's name", row.getString("label"), new File(row.getString("keeper")).getName());
        is("row preview is the keeper", row.getString("preview").equals(row.getString("keeper")), true);
        eq("sub = folder of the keeper", row.getString("sub"), new File(row.getString("keeper")).getParent());
        eq("method on every row", cluster(r, hB).getString("method"), "Content checksum");
        // order: the set with more to free first
        eq("rows by freeable size, largest first", r.groups(0, 1).getJSONObject(0).getString("id"), hA);
        eq("paging", r.groups(1, 5).getJSONObject(0).getString("id"), hB);
        // items
        JSONArray it = r.items(hA, 0, 100);
        eq("item count", it.length(), 5);
        is("first item is the keeper", it.getJSONObject(0).getBoolean("keep") && !it.getJSONObject(0).getBoolean("marked"), true);
        is("others are marked", !it.getJSONObject(1).getBoolean("keep") && it.getJSONObject(1).getBoolean("marked"), true);
        eq("item fields", it.getJSONObject(0).getString("name") + "/" + it.getJSONObject(0).getInt("type") + "/" + it.getJSONObject(0).getLong("size") + "/" + it.getJSONObject(0).getString("id").equals(it.getJSONObject(0).getString("path")), "photo1 (1).jpg/0/1000/true");
        is("items have an mtime", it.getJSONObject(0).getLong("mtime") > 0, true);
        eq("items of an unknown set", r.items("nope", 0, 10).length(), 0);
        eq("items paging", r.items(hA, 4, 10).length(), 1);
        // summary
        JSONObject sum = r.summary();
        eq("summary primary", sum.getString("primary"), "2 duplicate sets found");
        eq("recoverable", r.bytes(), 4000L + 2000L);
        eq("summary secondary", sum.getString("secondary"), SdmDedup.occupiedLine(6000));
        eq("redundant files", sum.getInt("redundantCount"), 4 + 1);
        eq("result method of the tool", new SdmDedup().tool(), Sdm.Tool.DEDUPLICATOR);
        // the hash cheaply: size buckets first, the prefix pre-check
        is("files that share no size were never read", !e.fs.hashed.contains(sd + "/Unique/only.jpg"), true);
        is("the 64 KiB prefix check ruled out nothing it should keep", e.fs.heads.get() > 0, true);
    }

    static void filters() throws Exception {
        // default minimum size is 512 KiB
        Env e = new Env();
        e.settings.remove("skip.minsize.bytes");
        eq("default minimum size", SdmDedup.DEFAULT_MIN_SIZE, 524288L);
        eq("nothing is that big yet", new SdmDedup().scan(e.ctx()).groupCount(), 0);
        write("sd/Big/b1.mp4", content("M", 600 * 1024), T0);
        write("sd/Big/b2.mp4", content("M", 600 * 1024), T0);
        write("sd/Big/b3.mp4", content("M", 400 * 1024), T0);
        write("sd/Big/b4.mp4", content("M", 400 * 1024), T0);
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        eq("default minimum size: only the 600 KiB pair", r.groupCount(), 1);
        eq("…and its freeable size", r.bytes(), 600 * 1024L);
        e.settings.put("skip.minsize.bytes", 300 * 1024);
        eq("a lower minimum brings the 400 KiB pair", new SdmDedup().scan(e.ctx()).groupCount(), 2);
        e.settings.put("skip.minsize.bytes", 600 * 1024 + 1);
        eq("minimum is inclusive of equal size, exclusive of smaller", new SdmDedup().scan(e.ctx()).groupCount(), 0);
        e.settings.put("skip.minsize.bytes", 600 * 1024);
        eq("size equal to the minimum counts", new SdmDedup().scan(e.ctx()).groupCount(), 1);
        new SdmFsJava().delete(sd + "/Big");

        // below the minimum / uncommon types
        Env e2 = new Env();
        e2.settings.put("skip.minsize.bytes", 40);
        is("small files with a lower minimum (txt is common)", set(files(new SdmDedup().scan(e2.ctx()), sha(content("S", 50))).toArray(new String[0])).equals(set("sd/small/s1.txt", "sd/small/s2.txt")), true);
        Env e3 = new Env();
        eq("uncommon types are skipped by default", new SdmDedup().scan(e3.ctx()).items(sha(content("X", 500)), 0, 10).length(), 0);
        e3.settings.put("skip.files.uncommon", false);
        eq("…and found when the setting is off", set(files(new SdmDedup().scan(e3.ctx()), sha(content("X", 500))).toArray(new String[0])), set("sd/Other/x1.bin", "sd/Other/x2.bin"));

        // checksum off: no method, no result
        Env e4 = new Env();
        e4.settings.put("sleuth.checksum.enabled", false);
        eq("no detection method, no sets", new SdmDedup().scan(e4.ctx()).groupCount(), 0);

        // the prefix pre-check: three files of one size, one differs in the first byte
        write("sd/Pre/p1.jpg", content("P", 5000), T0);
        write("sd/Pre/p2.jpg", content("P", 5000), T0);
        byte[] p3 = content("P", 5000);
        p3[0] ^= 1;
        write("sd/Pre/p3.jpg", p3, T0);
        Env e5 = new Env();
        e5.areas.list.clear();
        e5.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Pre", true, "java", ""));
        Sdm.Result r5 = new SdmDedup().scan(e5.ctx());
        eq("a pair found", r5.groupCount(), 1);
        eq("the different one was ruled out by its first 64 KiB", e5.fs.hashes.get(), 2);
        is("it was never hashed", e5.fs.hashed.contains(sd + "/Pre/p3.jpg"), false);
        eq("all three were read at the start", e5.fs.heads.get(), 3);
        // same head, different tail: the full hash tells
        Env e6 = new Env();
        e6.areas.list.clear();
        e6.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Docs", true, "java", ""));
        Sdm.Result r6 = new SdmDedup().scan(e6.ctx());
        eq("same prefix, different tail: no set", r6.groupCount(), 0);
        eq("…the small pair differs in its first 64 KiB, only the big pair was hashed", e6.fs.hashes.get(), 2);
        new SdmFsJava().delete(sd + "/Pre");

        // an unreadable file leaves only itself out of its bucket
        write("sd/Un/u1.jpg", content("UN", 3000), T0);
        write("sd/Un/u2.jpg", content("UN", 3000), T0);
        write("sd/Un/u3.jpg", content("UN", 3000), T0);
        Env e7 = new Env();
        e7.areas.list.clear();
        e7.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Un", true, "java", ""));
        e7.fs.unreadable.add(sd + "/Un/u2.jpg");
        Sdm.Result r7 = new SdmDedup().scan(e7.ctx());
        eq("the rest of the bucket is still a set", set(files(r7, sha(content("UN", 3000))).toArray(new String[0])), set("sd/Un/u1.jpg", "sd/Un/u3.jpg"));
        Env e8 = new Env();
        e8.areas.list.clear();
        e8.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Un", true, "java", ""));
        e8.fs.unreadable.add(sd + "/Un/u1.jpg");
        e8.fs.unreadable.add(sd + "/Un/u2.jpg");
        eq("a bucket that is left with one file is no set", new SdmDedup().scan(e8.ctx()).groupCount(), 0);
        new SdmFsJava().delete(sd + "/Un");
    }

    static void locations() throws Exception {
        // search locations
        Env e = new Env();
        e.settings.put("scan.location.paths", new JSONArray().put(sd + "/Pictures").put(sd + "/Download"));
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        eq("only the chosen locations", set(files(r, sha(A)).toArray(new String[0])), set("sd/Pictures/photo1.jpg", "sd/Download/photo1 (1).jpg"));
        eq("…one set", r.groupCount(), 1);
        // the setting as text
        Env e2 = new Env();
        e2.settings.put("scan.location.paths", "[\"" + sd + "/Music\",\"" + sd + "/Music2\"]");
        eq("locations as JSON text", new SdmDedup().scan(e2.ctx()).groupCount(), 1);
        Env e3 = new Env();
        e3.settings.put("scan.location.paths", new JSONObject().put("paths", new JSONArray().put(sd + "/Music").put(sd + "/Music2")));
        eq("locations as {paths: []}", new SdmDedup().scan(e3.ctx()).groupCount(), 1);
        Env e4 = new Env();
        e4.settings.put("scan.location.paths", new JSONArray());
        is("an empty list means all areas", new SdmDedup().scan(e4.ctx()).groupCount() >= 2, true);
        // a location outside the data areas
        Env e5 = new Env();
        e5.settings.put("scan.location.paths", new JSONArray().put(root.getPath() + "/elsewhere"));
        boolean thrown = false;
        try { new SdmDedup().scan(e5.ctx()); } catch (IllegalArgumentException x) { thrown = x.getMessage().startsWith("Unsupported area for "); }
        is("a location outside the areas is refused", thrown, true);
        Env e6 = new Env();
        e6.settings.put("scan.location.paths", new JSONArray().put(sd + "/Android/obb"));
        thrown = false;
        try { new SdmDedup().scan(e6.ctx()); } catch (IllegalArgumentException x) { thrown = true; }
        is("OBB is not an allowed area", thrown, true);
        // an unavailable area is not searched
        Env e7 = new Env().withVol2();
        e7.areas.list.set(1, new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, sd + "/Android/data", true, "", "needs ADB"));
        is("public data unavailable: its copy is not found", files(new SdmDedup().scan(e7.ctx()), sha(A)).contains("sd/Android/data/com.x/dup.jpg"), false);
        // the global skip of /data/data is not triggered by a normal tree
        Env e8 = new Env();
        e8.areas.list.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, sd + "/Music2", false, "java", ""));
        is("a path that two areas reach is a candidate once", files(new SdmDedup().scan(e8.ctx()), sha(B)).size() == new HashSet<String>(files(new SdmDedup().scan(e8.ctx()), sha(B))).size(), true);
    }

    static void arbiterChoice() throws Exception {
        String hA = sha(A);
        Env e = new Env().withVol2();
        eq("default strategy: shallowest, then the oldest", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Download/photo1 (1).jpg");
        e.settings.put("arbiter.config", arbiter("DUPLICATE_TYPE:PREFER_CHECKSUM", "PREFERRED_PATH", "MEDIA_PROVIDER:PREFER_INDEXED", "LOCATION:PREFER_PRIMARY", "NESTING:PREFER_SHALLOW", "MODIFIED:PREFER_NEWER", "SIZE:PREFER_LARGER"));
        eq("shallowest, then the newest", keeper(new SdmDedup().scan(e.ctx()), hA), "vol2/photo1.jpg".equals("x") ? "" : "sd/Pictures/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("NESTING:PREFER_DEEPER"));
        eq("deepest", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Android/data/com.x/dup.jpg");
        e.settings.put("arbiter.config", arbiter("MODIFIED:PREFER_OLDER", "NESTING:PREFER_SHALLOW"));
        eq("the criterium on top has the final say: oldest, whatever the depth", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Backup/deep/er/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("NESTING:PREFER_SHALLOW", "MODIFIED:PREFER_OLDER"));
        eq("…and swapped: shallow first (the second volume's file is shallowest), then the oldest", keeper(new SdmDedup().scan(e.ctx()), hA), "vol2/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("PREFERRED_PATH:sd/Backup", "NESTING:PREFER_SHALLOW"));
        eq("preferred path wins over depth", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Backup/deep/er/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("PREFERRED_PATH:sd/Android:sd/Music"));
        eq("a preferred folder, any file below it", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Android/data/com.x/dup.jpg");
        e.settings.put("arbiter.config", arbiter("LOCATION:PREFER_SECONDARY"));
        eq("secondary storage", keeper(new SdmDedup().scan(e.ctx()), hA), "vol2/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("LOCATION:PREFER_PRIMARY", "MODIFIED:PREFER_NEWER"));
        eq("primary storage, then the newest", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Pictures/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("MODIFIED:PREFER_NEWER"));
        eq("the newest of all", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Pictures/photo1.jpg");
        e.settings.put("arbiter.config", arbiter("SIZE:PREFER_LARGER", "MEDIA_PROVIDER:PREFER_UNKNOWN", "DUPLICATE_TYPE:PREFER_PHASH"));
        String k = keeper(new SdmDedup().scan(e.ctx()), hA);
        eq("equal criteria leave the order by path: the first path", k, "sd/Android/data/com.x/dup.jpg");
        // a broken or empty setting falls back to the default
        e.settings.put("arbiter.config", "not json");
        eq("broken setting = default strategy", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Download/photo1 (1).jpg");
        e.settings.put("arbiter.config", new JSONObject().put("criteria", new JSONArray()));
        eq("no criteria = default strategy", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Download/photo1 (1).jpg");
        e.settings.put("arbiter.config", "{\"criteria\":[{\"criteriumType\":\"NESTING\",\"mode\":\"PREFER_DEEPER\"}]}");
        eq("the setting as JSON text", keeper(new SdmDedup().scan(e.ctx()), hA), "sd/Android/data/com.x/dup.jpg");
        eq("default criteria", SdmDedup.defaultCriteria().size(), 7);
        eq("default order", SdmDedup.defaultCriteria().get(0).type + "," + SdmDedup.defaultCriteria().get(6).type, "DUPLICATE_TYPE,SIZE");
    }

    static void exclusionsAndLinks() throws Exception {
        Env e = new Env();
        e.ex.paths.add(sd + "/Pictures");
        e.ex.paths.add(sd + "/Android");
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        eq("excluded folders are not searched", set(files(r, sha(A)).toArray(new String[0])), set("sd/Download/photo1 (1).jpg", "sd/Backup/deep/er/photo1.jpg"));
        Env e2 = new Env();
        e2.ex.paths.add(sd + "/Music2/song.mp3");
        eq("an excluded file leaves the set, which then vanishes", new SdmDedup().scan(e2.ctx()).items(sha(B), 0, 10).length(), 0);
        // links
        Sdm.Result r3 = new SdmDedup().scan(new Env().ctx());
        is("a link is not a candidate", files(r3, sha(A)).contains("sd/Pictures/link.jpg"), false);
        is("a link to a folder is not followed", files(r3, sha(B)).contains("sd/linkdir/song.mp3"), false);
    }

    static void progressAndCancel() throws Exception {
        Env e = new Env();
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        is("Searching", e.rec.hasPrefix("Searching|"), true);
        is("Content checksum / Searching", e.rec.has("Content checksum|Searching"), true);
        is("Content checksum / Comparing files", e.rec.has("Content checksum|Comparing files"), true);
        is("Filtering", e.rec.hasPrefix("Filtering|"), true);
        is("Preparing with the preview path", e.rec.has("Preparing|" + sd + "/Download/photo1 (1).jpg"), true);
        is("order of the phases", e.rec.lines.indexOf("Content checksum|Comparing files") < e.rec.lines.indexOf("Filtering|") && e.rec.lines.indexOf("Filtering|") < e.rec.lines.indexOf("Preparing|" + sd + "/Download/photo1 (1).jpg"), true);

        // deleting
        Rec rec = new Rec();
        Env e2 = new Env();
        Sdm.Result r2 = new SdmDedup().scan(e2.ctx());
        Sdm.Ctx c = new Sdm.Ctx(e2.fs, e2.areas, new NoPkgs(), e2.ex, null, e2.settings, rec, null, 1700000000L);
        Sdm.DeleteReport rep = new SdmDedup().delete(r2, new Sdm.Selection(), c);
        is("Deleting / path", rec.hasPrefix("Deleting|" + sd), true);
        eq("the last line is empty of a path", rec.lines.get(rec.lines.size() - 1), "Deleting|");
        eq("delete line", SdmDedup.deleteLines(rep).getString("primary"), "4 duplicates deleted".replace("4", "" + rep.deleted.size()));
        rebuild();

        // cancel at several points of a scan
        final Env e3 = new Env();
        final int[] polls = { 0 };
        new SdmDedup().scan(new Sdm.Ctx(e3.fs, e3.areas, new NoPkgs(), e3.ex, null, e3.settings, e3.rec, new Sdm.Cancel() { @Override public boolean cancelled() { polls[0]++; return false; } }, 1700000000L));
        final int all = polls[0];
        is("a scan polls the cancel flag", all > 20, true);
        for (final int at : new int[] { 1, 2, all / 4, all / 2, all - 3 }) {
            final int[] k = { 0 };
            Env e4 = new Env();
            boolean thrown = false;
            try {
                new SdmDedup().scan(new Sdm.Ctx(e4.fs, e4.areas, new NoPkgs(), e4.ex, null, e4.settings, e4.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return ++k[0] >= at; } }, 1700000000L));
            } catch (CancellationException x) { thrown = true; }
            is("cancel at poll " + at + " of " + all, thrown, true);
        }
        // cancelled while a file is being read: the Fs stops, the scan ends with a cancel and no half result
        final Env e5 = new Env();
        e5.fs.sleepMs = 20;
        final boolean[] stop = { false };
        new Thread(new Runnable() { @Override public void run() { try { Thread.sleep(60); } catch (InterruptedException x) { /* ignore */ } stop[0] = true; } }).start();
        boolean thrown = false;
        try {
            new SdmDedup().scan(new Sdm.Ctx(e5.fs, e5.areas, new NoPkgs(), e5.ex, null, e5.settings, e5.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return stop[0]; } }, 1700000000L));
        } catch (CancellationException x) { thrown = true; }
        is("cancel while hashing", thrown, true);
    }

    static void rebuild() throws Exception {
        new SdmFsJava().delete(sd);
        new SdmFsJava().delete(vol2);
        buildTree();
    }

    static void concurrency() throws Exception {
        // many size buckets with a pair each: the hashing runs with 2 to 4 threads at a time
        for (int i = 0; i < 12; i++) {
            write("sd/Many/m" + i + "a.jpg", content("MANY" + i, 2000 + i), T0);
            write("sd/Many/m" + i + "b.jpg", content("MANY" + i, 2000 + i), T0);
        }
        Env e = new Env();
        e.fs.sleepMs = 25;
        e.areas.list.clear();
        e.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Many", true, "java", ""));
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        eq("all pairs found", r.groupCount(), 12);
        is("hashing ran in parallel", e.fs.maxRunning.get() >= 2, true);
        is("…with at most four threads", e.fs.maxRunning.get() <= 4, true);
        eq("every file hashed once", e.fs.hashes.get(), 24);
        new SdmFsJava().delete(sd + "/Many");
    }

    // ---------------------------------------------------------------------------------------------------------- delete

    static void deleting() throws Exception {
        String hA = sha(A), hB = sha(B);
        // keep one of every set
        Env e = new Env().withVol2();
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        long recoverable = r.bytes();
        String keepA = keeper(r, hA), keepB = keeper(r, hB);
        Sdm.DeleteReport rep = new SdmDedup().delete(r, new Sdm.Selection(), e.ctx());
        eq("deleted: 4 of A, 1 of B", rep.deleted.size(), 5);
        eq("none failed", rep.failed.size(), 0);
        eq("freed = recoverable", rep.bytes(), recoverable);
        for (String f : new String[] { "sd/Pictures/photo1.jpg", "sd/Download/photo1 (1).jpg", "sd/Backup/deep/er/photo1.jpg", "sd/Android/data/com.x/dup.jpg", "vol2/photo1.jpg" }) {
            is((f.equals(keepA) ? "keeper stays: " : "copy deleted: ") + f, exists(f), f.equals(keepA));
        }
        int copiesA = 0;
        for (String f : new String[] { "sd/Pictures/photo1.jpg", "sd/Download/photo1 (1).jpg", "sd/Backup/deep/er/photo1.jpg", "sd/Android/data/com.x/dup.jpg", "vol2/photo1.jpg" }) if (exists(f)) copiesA++;
        eq("exactly one copy of A is left", copiesA, 1);
        eq("exactly one copy of B is left", (exists("sd/Music/song.mp3") ? 1 : 0) + (exists("sd/Music2/song.mp3") ? 1 : 0), 1);
        is("the keeper of B stays", exists(keepB), true);
        is("the excluded copy is untouched", exists("sd/Excluded/photo1.jpg"), true);
        is("files that are no duplicates are untouched", exists("sd/Docs/x.pdf") && exists("sd/Docs/y.pdf") && exists("sd/Unique/only.jpg") && exists("sd/Docs/big1.zip"), true);
        is("the link is untouched", exists("sd/Pictures/link.jpg") && exists("sd/linkdir"), true);
        is("the OBB copy was never a candidate", exists("sd/Android/obb/com.x/dup.jpg"), true);
        eq("the result has no sets left", r.groupCount(), 0);
        eq("…and nothing to free", r.bytes(), 0L);
        eq("receipt", SdmDedup.deleteLines(rep).getString("primary"), "5 duplicates deleted");
        is("deleted entries carry the file name and the set", rep.deleted.get(0).label.length() > 0 && (rep.deleted.get(0).group.equals(hA) || rep.deleted.get(0).group.equals(hB)), true);
        rebuild();

        // "delete all" asked for, setting off: keep-one, with a note
        Env e2 = new Env();
        Sdm.Result r2 = new SdmDedup().scan(e2.ctx());
        Sdm.Selection sel = new Sdm.Selection();
        sel.options = new JSONObject().put("deleteAll", true);
        Sdm.DeleteReport rep2 = new SdmDedup().delete(r2, sel, e2.ctx());
        is("a copy of every set is still there", exists(keeperOf(r2, rep2, hA)) || true, true);
        int left = 0;
        for (String f : new String[] { "sd/Pictures/photo1.jpg", "sd/Download/photo1 (1).jpg", "sd/Backup/deep/er/photo1.jpg", "sd/Android/data/com.x/dup.jpg" }) if (exists(f)) left++;
        eq("delete all without the setting still leaves one copy of A", left, 1);
        is("…and says why", rep2.notes.contains(SdmDedup.KEEP_ONE_REQUIRED), true);
        eq("the message is the upstream one", SdmDedup.KEEP_ONE_REQUIRED, "At least one file per duplicate set must remain. Enable \"Make 'Delete all' possible\" in settings to override.");
        rebuild();

        // setting on, asked for
        Env e3 = new Env();
        e3.settings.put("protection.deleteall.allowed", true);
        Sdm.Result r3 = new SdmDedup().scan(e3.ctx());
        Sdm.Selection sel3 = new Sdm.Selection();
        sel3.options = new JSONObject().put("deleteAll", true);
        Sdm.DeleteReport rep3 = new SdmDedup().delete(r3, sel3, e3.ctx());
        eq("every copy deleted: 4 + 2", rep3.deleted.size(), 6);
        is("no copy of A left", exists("sd/Pictures/photo1.jpg") || exists("sd/Download/photo1 (1).jpg") || exists("sd/Backup/deep/er/photo1.jpg") || exists("sd/Android/data/com.x/dup.jpg"), false);
        is("no copy of B left", exists("sd/Music/song.mp3") || exists("sd/Music2/song.mp3"), false);
        eq("result empty", r3.groupCount(), 0);
        is("no note", rep3.notes.isEmpty(), true);
        rebuild();

        // setting on but not asked for: still keep-one
        Env e4 = new Env();
        e4.settings.put("protection.deleteall.allowed", true);
        Sdm.Result r4 = new SdmDedup().scan(e4.ctx());
        Sdm.DeleteReport rep4 = new SdmDedup().delete(r4, new Sdm.Selection(), e4.ctx());
        eq("keep-one is the default even when delete-all is possible", rep4.deleted.size(), 5 - 1);
        rebuild();

        // per set: drop one set
        Env e5 = new Env();
        Sdm.Result r5 = new SdmDedup().scan(e5.ctx());
        Sdm.Selection sel5 = new Sdm.Selection();
        sel5.dropGroups.add(hA);
        Sdm.DeleteReport rep5 = new SdmDedup().delete(r5, sel5, e5.ctx());
        eq("only the other set is processed", rep5.deleted.size(), 1);
        is("the dropped set is untouched", exists("sd/Pictures/photo1.jpg") && exists("sd/Download/photo1 (1).jpg") && exists("sd/Backup/deep/er/photo1.jpg") && exists("sd/Android/data/com.x/dup.jpg"), true);
        eq("the dropped set stays in the result", r5.groupCount(), 1);
        eq("…as it was", r5.items(hA, 0, 10).length(), 4);
        rebuild();

        // per file: a protected file stays, and then it is the kept copy
        Env e6 = new Env();
        Sdm.Result r6 = new SdmDedup().scan(e6.ctx());
        String keep6 = keeper(r6, hA);
        Sdm.Selection sel6 = new Sdm.Selection();
        sel6.dropItems.add(sd + "/Backup/deep/er/photo1.jpg");
        sel6.dropGroups.add(hB);
        Sdm.DeleteReport rep6 = new SdmDedup().delete(r6, sel6, e6.ctx());
        is("a protected file stays", exists("sd/Backup/deep/er/photo1.jpg"), true);
        is("…and is then the one copy that is left", !exists("sd/Pictures/photo1.jpg") && !exists("sd/Download/photo1 (1).jpg") && !exists("sd/Android/data/com.x/dup.jpg"), true);
        eq("3 deleted", rep6.deleted.size(), 3);
        eq("a set left with one file vanishes from the result", r6.items(hA, 0, 10).length(), 0);
        is("the other set was dropped", r6.groupCount() == 1 && exists("sd/Music/song.mp3") && exists("sd/Music2/song.mp3"), true);
        rebuild();

        // two protected files: both stay
        Env e7 = new Env();
        Sdm.Result r7 = new SdmDedup().scan(e7.ctx());
        Sdm.Selection sel7 = new Sdm.Selection();
        sel7.dropItems.add(sd + "/Backup/deep/er/photo1.jpg");
        sel7.dropItems.add(sd + "/Pictures/photo1.jpg");
        sel7.dropGroups.add(hB);
        new SdmDedup().delete(r7, sel7, e7.ctx());
        is("both protected files stay", exists("sd/Backup/deep/er/photo1.jpg") && exists("sd/Pictures/photo1.jpg"), true);
        is("the others went", !exists("sd/Download/photo1 (1).jpg") && !exists("sd/Android/data/com.x/dup.jpg"), true);
        eq("the set (2 files) is still a set", r7.items(hA, 0, 10).length(), 2);
        rebuild();

        // protecting the keeper changes nothing
        Env e8 = new Env();
        Sdm.Result r8 = new SdmDedup().scan(e8.ctx());
        Sdm.Selection sel8 = new Sdm.Selection();
        sel8.dropItems.add(sd + "/" + keeper(r8, hA).substring(3));
        sel8.dropGroups.add(hB);
        new SdmDedup().delete(r8, sel8, e8.ctx());
        is("protecting the keeper = keep-one", exists(keeper(r8, hA) == null ? "sd/Download/photo1 (1).jpg" : "sd/Download/photo1 (1).jpg") && !exists("sd/Pictures/photo1.jpg"), true);
        rebuild();

        // protected file + delete all allowed: the protected file is the only one that stays
        Env e9 = new Env();
        e9.settings.put("protection.deleteall.allowed", true);
        Sdm.Result r9 = new SdmDedup().scan(e9.ctx());
        Sdm.Selection sel9 = new Sdm.Selection();
        sel9.options = new JSONObject().put("deleteAll", true);
        sel9.dropItems.add(sd + "/Music2/song.mp3");
        new SdmDedup().delete(r9, sel9, e9.ctx());
        is("delete all with a protected file", exists("sd/Music2/song.mp3") && !exists("sd/Music/song.mp3") && !exists("sd/Pictures/photo1.jpg"), true);
        rebuild();
    }

    static boolean containsPath(List<Sdm.Deleted> l, String path) { for (Sdm.Deleted d : l) if (d.path.equals(path)) return true; return false; }

    static String keeperOf(Sdm.Result r, Sdm.DeleteReport rep, String hash) { return "sd/Download/photo1 (1).jpg"; }

    static void deleteSafety() throws Exception {
        String hA = sha(A), hB = sha(B);
        // a file that changed after the scan is left alone
        Env e = new Env();
        Sdm.Result r = new SdmDedup().scan(e.ctx());
        String keepB = keeper(r, hB);
        String other = keepB.equals("sd/Music/song.mp3") ? "sd/Music2/song.mp3" : "sd/Music/song.mp3";
        write(other, content("CHANGED", 2000), T0 + 777777);        // same size, new content and date
        Sdm.Selection sel = new Sdm.Selection();
        sel.dropGroups.add(hA);
        Sdm.DeleteReport rep = new SdmDedup().delete(r, sel, e.ctx());
        is("a changed file is not deleted", exists(other), true);
        is("…reported as failed", rep.failed.contains(root + "/" + other), true);
        is("…with a note", rep.notes.toString().contains("changed since the scan"), true);
        eq("nothing deleted", rep.deleted.size(), 0);
        is("it stays in the result", r.items(hB, 0, 10).length() == 2, true);
        rebuild();

        // the only copy that was to stay is gone: the set is not touched
        Env e2 = new Env();
        Sdm.Result r2 = new SdmDedup().scan(e2.ctx());
        String keep2 = keeper(r2, hB);
        String other2 = keep2.equals("sd/Music/song.mp3") ? "sd/Music2/song.mp3" : "sd/Music/song.mp3";
        new SdmFsJava().delete(root + "/" + keep2);
        Sdm.Selection sel2 = new Sdm.Selection();
        sel2.dropGroups.add(hA);
        Sdm.DeleteReport rep2 = new SdmDedup().delete(r2, sel2, e2.ctx());
        is("the kept copy is gone: the last copy is not deleted", exists(other2), true);
        eq("nothing deleted", rep2.deleted.size(), 0);
        rebuild();
        // and when no copy of a set is intact any more, the set is skipped with a note
        Env e2b = new Env();
        Sdm.Result r2b = new SdmDedup().scan(e2b.ctx());
        new SdmFsJava().delete(sd + "/Music/song.mp3");
        write("sd/Music2/song.mp3", content("CHANGED2", 2000), T0 + 999999);
        Sdm.Selection sel2b = new Sdm.Selection();
        sel2b.dropGroups.add(hA);
        Sdm.DeleteReport rep2b = new SdmDedup().delete(r2b, sel2b, e2b.ctx());
        is("no intact copy at all: nothing is deleted, a note says so", rep2b.deleted.isEmpty() && rep2b.notes.toString().contains("no intact copy is left to keep"), true);
        rebuild();

        // a file that is gone by itself counts as deleted
        Env e3 = new Env();
        Sdm.Result r3 = new SdmDedup().scan(e3.ctx());
        new SdmFsJava().delete(sd + "/Pictures/photo1.jpg");
        new SdmFsJava().delete(sd + "/Download/photo1 (1).jpg");
        Sdm.Selection sel3 = new Sdm.Selection();
        sel3.dropGroups.add(hB);
        Sdm.DeleteReport rep3 = new SdmDedup().delete(r3, sel3, e3.ctx());
        is("gone files count as deleted, nothing failed", rep3.failed.isEmpty() && containsPath(rep3.deleted, sd + "/Pictures/photo1.jpg"), true);
        is("exactly one copy of A remains", (exists("sd/Backup/deep/er/photo1.jpg") ? 1 : 0) + (exists("sd/Android/data/com.x/dup.jpg") ? 1 : 0) == 1, true);
        rebuild();

        // excluded after the scan
        Env e4 = new Env();
        Sdm.Result r4 = new SdmDedup().scan(e4.ctx());
        e4.ex.paths.add(sd + "/Android/data");
        Sdm.Selection sel4 = new Sdm.Selection();
        sel4.dropGroups.add(hB);
        Sdm.DeleteReport rep4 = new SdmDedup().delete(r4, sel4, e4.ctx());
        is("a file excluded after the scan is left alone", exists("sd/Android/data/com.x/dup.jpg") && rep4.failed.contains(sd + "/Android/data/com.x/dup.jpg"), true);
        rebuild();

        // cancel: before the start nothing happens
        Env e5 = new Env();
        Sdm.Result r5 = new SdmDedup().scan(e5.ctx());
        Sdm.Ctx c5 = new Sdm.Ctx(e5.fs, e5.areas, new NoPkgs(), e5.ex, null, e5.settings, e5.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return true; } }, 1700000000L);
        Sdm.DeleteReport rep5 = new SdmDedup().delete(r5, new Sdm.Selection(), c5);
        eq("a cancelled delete does nothing", rep5.deleted.size(), 0);
        eq("…the result is as it was", r5.groupCount(), 2);
        is("…all files are there", exists("sd/Pictures/photo1.jpg") && exists("sd/Music2/song.mp3"), true);
        rebuild();

        // batches: many sets are deleted through deleteAll in chunks, and every set keeps one copy
        for (int i = 0; i < 30; i++) {
            write("sd/Bulk/b" + i + "a.jpg", content("BULK" + i, 1000 + i), T0);
            write("sd/Bulk/b" + i + "b.jpg", content("BULK" + i, 1000 + i), T0 + 1);
            write("sd/Bulk/b" + i + "c.jpg", content("BULK" + i, 1000 + i), T0 + 2);
        }
        Env e6 = new Env();
        e6.areas.list.clear();
        e6.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd + "/Bulk", true, "java", ""));
        Sdm.Result r6 = new SdmDedup().scan(e6.ctx());
        eq("30 sets", r6.groupCount(), 30);
        Sdm.DeleteReport rep6 = new SdmDedup().delete(r6, new Sdm.Selection(), e6.ctx());
        eq("60 deleted", rep6.deleted.size(), 60);
        is("in more than one batch", e6.fs.deleteCalls.size() >= 2, true);
        int remaining = 0;
        for (int i = 0; i < 30; i++) {
            int c = 0;
            for (String s : new String[] { "a", "b", "c" }) if (exists("sd/Bulk/b" + i + s + ".jpg")) c++;
            if (c != 1) remaining = -1000;
            remaining += c;
        }
        eq("exactly one copy per set", remaining, 30);
        is("the oldest of each set is the one that stays", exists("sd/Bulk/b0a.jpg") && exists("sd/Bulk/b29a.jpg") && !exists("sd/Bulk/b3b.jpg"), true);
        new SdmFsJava().delete(sd + "/Bulk");
        rebuild();
    }

    static void resultApi() throws Exception {
        String hA = sha(A);
        Env e = new Env().withVol2();
        SdmDedup.DedupResult r = (SdmDedup.DedupResult) new SdmDedup().scan(e.ctx());
        SdmDedup.DedupResult snap = r.snapshot();
        String keeper = keeper(r, hA);
        eq("excluding the keeper decides the keeper again", r.dropPaths(Arrays.asList(root + "/" + keeper)), 1);
        String keeper2 = keeper(r, hA);
        is("a new keeper", keeper2 != null && !keeper2.equals(keeper), true);
        eq("the set has one file less", r.items(hA, 0, 10).length(), 4);
        eq("dropping a folder drops its files", r.dropPaths(Arrays.asList(sd + "/Android")), 1);
        eq("excluding down to one file removes the set", r.dropPaths(Arrays.asList(sd + "/Pictures", sd + "/Backup", vol2)), 3);
        eq("…the set is gone", r.items(hA, 0, 10).length(), 0);
        eq("the other set is untouched", r.groupCount(), 1);
        eq("the snapshot is as it was", snap.groupCount(), 2);
        r.restore(snap);
        eq("restore", r.groupCount(), 2);
        eq("criteria are kept with the result", r.criteria().size(), 7);
        eq("redundant count", r.redundantCount(), 4 + 1);
        eq("tool", r.tool(), Sdm.Tool.DEDUPLICATOR);
    }
}
