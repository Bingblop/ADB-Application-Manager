package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Audit M-13: the Morphe signing key leaves the app through the share sheet, not through a copy in shared Downloads. This checks KeyShare with real files (what is
 * copied and where, who can read it, what is removed and when, what the share address can reach) and, with a source scan, the wiring that needs Android classes
 * (the bridge's keyExport, the activity's share and timer, the provider's key route, the page's question).
 */
public class KeyShareTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void check(String name, boolean ok) { check(name, ok, null); }

    static File repo() {
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
            if (new File(d, "src/com/bloatware/bingblop/MorpheBridge.java").isFile()) return d;
        throw new IllegalStateException("repository not found");
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** The text of the method (or block) that starts at {@code head}: from the head to its matching closing brace. */
    static String body(String src, String head) {
        int i = src.indexOf(head);
        if (i < 0) return "";
        int open = src.indexOf('{', i), depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(i, j + 1);
        }
        return "";
    }

    static Set<String> tree(File root) {
        Set<String> out = new TreeSet<String>();
        walk(root, root, out);
        return out;
    }

    static void walk(File root, File f, Set<String> out) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            out.add(root.toPath().relativize(k.toPath()).toString() + (k.isDirectory() ? "/" : ":" + k.length()));
            walk(root, k, out);
        }
    }

    static boolean has(String text, String... parts) {
        for (String p : parts) if (!text.contains(p)) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("keyshare").toFile();
        File data = new File(tmp, "data"), files = new File(data, "files"), cache = new File(data, "cache"), base = new File(files, "morphe");
        File downloads = new File(tmp, "sdcard/Download");
        base.mkdirs(); cache.mkdirs(); downloads.mkdirs();
        byte[] keyBytes = new byte[2231];
        new java.util.Random(13).nextBytes(keyBytes);
        File key = new File(base, "morphe.keystore");
        Files.write(key.toPath(), keyBytes);
        File info = new File(base, "morphe_key.json");
        Files.write(info.toPath(), "{\"alias\":\"Morphe\",\"password\":\"Morphe\"}".getBytes(StandardCharsets.UTF_8));

        // ---- staging: what is copied, where, and nothing in Downloads ----
        Set<String> baseBefore = tree(base), dlBefore = tree(downloads);
        File staged = KeyShare.stage(cache, key);
        File dir = staged.getParentFile();
        check("stage: the copy is cache/morphe_keyshare/<16 hex>/morphe.keystore", staged.getName().equals("morphe.keystore") && dir.getName().matches("[0-9a-f]{16}")
            && dir.getParentFile().equals(new File(cache, "morphe_keyshare")) && staged.isFile(), staged.getPath());
        check("stage: the copy has the same bytes as the key, and the key is untouched", Arrays.equals(Files.readAllBytes(staged.toPath()), keyBytes) && Arrays.equals(Files.readAllBytes(key.toPath()), keyBytes), null);
        check("stage: only the key file is copied (not morphe_key.json), with no partial file next to it", Arrays.equals(dir.list(), new String[] {"morphe.keystore"}), Arrays.toString(dir.list()));
        check("stage: nothing is written to Downloads, and the app's own Morphe folder is as it was", tree(downloads).equals(dlBefore) && tree(base).equals(baseBefore), tree(downloads) + " " + tree(base));
        check("stage: the only new place in the whole tree is cache/morphe_keyshare", tree(cache).equals(new TreeSet<String>(Arrays.asList(
            "morphe_keyshare/", "morphe_keyshare/" + dir.getName() + "/", "morphe_keyshare/" + dir.getName() + "/morphe.keystore:" + keyBytes.length))), tree(cache).toString());
        boolean posix = Files.getFileStore(tmp.toPath()).supportsFileAttributeView("posix");
        if (posix) {
            check("perms: the key copy is readable and writable by its owner only (rw-------)", PosixFilePermissions.toString(Files.getPosixFilePermissions(staged.toPath())).equals("rw-------"), PosixFilePermissions.toString(Files.getPosixFilePermissions(staged.toPath())));
            check("perms: its folder and the key-share folder are owner only (rwx------)", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())).equals("rwx------")
                && PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParentFile().toPath())).equals("rwx------"), null);
        } else System.out.println("note: no POSIX permissions on this file system; the permission checks are skipped");

        // ---- the address and what it can reach ----
        String[] seg = KeyShare.segments(staged);
        check("address: the path segments are key / <id> / morphe.keystore and they lead back to the copy (what the provider serves)", Arrays.equals(seg, new String[] {"key", dir.getName(), "morphe.keystore"})
            && staged.equals(KeyShare.resolve(cache, Arrays.asList(seg))), Arrays.toString(seg));
        List<String> good = Arrays.asList(seg);
        String id = dir.getName();
        List<List<String>> bad = new ArrayList<List<String>>();
        bad.add(Arrays.asList("share", id, "morphe.keystore"));                        // the ordinary share route is not the key route
        bad.add(Arrays.asList("key", id.toUpperCase(), "morphe.keystore"));
        bad.add(Arrays.asList("key", id.substring(1), "morphe.keystore"));
        bad.add(Arrays.asList("key", id + "0", "morphe.keystore"));
        bad.add(Arrays.asList("key", "..", "morphe.keystore"));
        bad.add(Arrays.asList("key", "../../files/morphe", "morphe.keystore"));
        bad.add(Arrays.asList("key", id, "morphe_key.json"));
        bad.add(Arrays.asList("key", id, "morphe.keystore.bak"));
        bad.add(Arrays.asList("key", id, ".."));
        bad.add(Arrays.asList("key", id));
        bad.add(Arrays.asList("key", id, "morphe.keystore", "x"));
        bad.add(Arrays.asList("key", "0000000000000000", "morphe.keystore"));         // no such copy
        bad.add(Collections.<String>emptyList());
        bad.add(null);
        int reached = 0;
        for (List<String> s : bad) if (KeyShare.resolve(cache, s) != null) reached++;
        check("address: another route, a malformed or traversing id, another file name, a wrong length or an unknown copy reach nothing (14 addresses)", reached == 0, reached + " reached");
        check("address: the key in the app's own Morphe folder cannot be named through it", KeyShare.resolve(cache, Arrays.asList("key", "morphe", "morphe.keystore")) == null
            && KeyShare.resolve(cache, Arrays.asList("key", "files", "morphe.keystore")) == null);
        if (posix) {
            File other = new File(cache, "morphe_keyshare/" + "aaaaaaaaaaaaaaaa");
            Files.createSymbolicLink(other.toPath(), base.toPath());               // a link to the Morphe folder in place of a copy's folder
            check("address: a link in place of the folder (to the real key's folder) reaches nothing", KeyShare.resolve(cache, Arrays.asList("key", "aaaaaaaaaaaaaaaa", "morphe.keystore")) == null);
            other.delete();
            File dir2 = new File(cache, "morphe_keyshare/bbbbbbbbbbbbbbbb"); dir2.mkdirs();
            Files.createSymbolicLink(new File(dir2, "morphe.keystore").toPath(), key.toPath());
            check("address: a link in place of the file (to the real key) reaches nothing", KeyShare.resolve(cache, Arrays.asList("key", "bbbbbbbbbbbbbbbb", "morphe.keystore")) == null);
            new File(dir2, "morphe.keystore").delete(); dir2.delete();
            File dir3 = new File(cache, "morphe_keyshare/cccccccccccccccc"); new File(dir3, "morphe.keystore").mkdirs();
            check("address: a folder named morphe.keystore is not a file", KeyShare.resolve(cache, Arrays.asList("key", "cccccccccccccccc", "morphe.keystore")) == null);
            new File(dir3, "morphe.keystore").delete(); dir3.delete();
        }

        // ---- not reachable by the other routes that hand out or read app files ----
        PrivatePaths.Links one = new PrivatePaths.Links() { @Override public long count(File f) { return 1; } };
        PrivatePaths.Root[] exportable = PrivatePaths.exportable(files, cache);
        check("exposure: the copy is not in a folder the file manager, VirusTotal, Open with or the share-a-path routes may hand out (PrivatePaths.exportable)", PrivatePaths.blocked(staged, data, one, exportable));
        check("exposure: the key in the app's Morphe folder is still protected there (so the generic share of a path cannot send it either)", PrivatePaths.blocked(key, data, one, exportable) && PrivatePaths.blocked(info, data, one, exportable));
        File ordinary = new File(cache, "share/0123456789abcdef/list.csv"); ordinary.getParentFile().mkdirs(); Files.write(ordinary.toPath(), new byte[] {1});
        check("exposure: a file the ordinary share makes (cache/share/<id>/...) is still allowed, as before", !PrivatePaths.blocked(ordinary, data, one, exportable));
        ordinary.delete(); ordinary.getParentFile().delete();

        // ---- the recipient reads, then the copy goes away ----
        check("share: until it is removed, the address gives the key's bytes", Arrays.equals(Files.readAllBytes(KeyShare.resolve(cache, good).toPath()), keyBytes));
        check("cleanup: remove() deletes the copy and its folder, and says so", KeyShare.remove(staged) && !staged.exists() && !dir.exists());
        check("cleanup: after that the address reaches nothing, and the cache holds no key copy", KeyShare.resolve(cache, good) == null && tree(new File(cache, "morphe_keyshare")).isEmpty(), tree(cache).toString());
        check("cleanup: removing it again is fine", KeyShare.remove(staged));
        check("cleanup: the real key and its info are still there", Arrays.equals(Files.readAllBytes(key.toPath()), keyBytes) && info.isFile());

        // remove() only touches a staged copy
        File stray1 = new File(base, "morphe.keystore");
        File d2 = new File(cache, "share/0123456789abcdef"); d2.mkdirs(); File stray2 = new File(d2, "morphe.keystore"); Files.write(stray2.toPath(), new byte[] {1});
        File d3 = new File(cache, "morphe_keyshare/0123456789abcdef"); d3.mkdirs(); File stray3 = new File(d3, "other.bin"); Files.write(stray3.toPath(), new byte[] {2});
        File d4 = new File(cache, "morphe_keyshare/notanid"); d4.mkdirs(); File stray4 = new File(d4, "morphe.keystore"); Files.write(stray4.toPath(), new byte[] {3});
        boolean r1 = KeyShare.remove(stray1), r2 = KeyShare.remove(stray2), r3 = KeyShare.remove(stray3), r4 = KeyShare.remove(stray4), r5 = KeyShare.remove(null);
        check("cleanup: remove() refuses anything that is not a staged key copy (the real key, a file of the ordinary share, another name, another folder name) and leaves it", !r1 && !r2 && !r3 && !r4 && r5
            && stray1.isFile() && stray2.isFile() && stray3.isFile() && stray4.isFile());
        stray2.delete(); d2.delete(); stray3.delete(); d3.delete(); stray4.delete(); d4.delete();

        // ---- one copy at a time; short-lived ----
        File first = KeyShare.stage(cache, key);
        File second = KeyShare.stage(cache, key);
        check("one at a time: a second export removes the copy of the first", !first.exists() && second.isFile() && !first.getParentFile().exists() && new File(cache, "morphe_keyshare").list().length == 1);
        long now = System.currentTimeMillis();
        File old = new File(cache, "morphe_keyshare/dddddddddddddddd"); old.mkdirs(); Files.write(new File(old, "morphe.keystore").toPath(), keyBytes);
        old.setLastModified(now - KeyShare.KEEP_MS - 60000);
        File fresh = second.getParentFile();
        fresh.setLastModified(now - 1000);
        check("short-lived: sweep(KEEP_MS) removes a copy older than the keep time, and keeps the one just made", KeyShare.sweep(cache, KeyShare.KEEP_MS, now) == 1 && !old.exists() && fresh.isDirectory());
        check("short-lived: the keep time is a few minutes, not hours (the ordinary share keeps its files an hour)", KeyShare.KEEP_MS >= 60000 && KeyShare.KEEP_MS <= 15 * 60000L, String.valueOf(KeyShare.KEEP_MS));
        check("short-lived: sweep(0) removes every copy, and sweeping with no folder at all is fine", KeyShare.sweep(cache, 0, now) == 1 && !fresh.exists() && KeyShare.sweep(new File(tmp, "nothing"), 0, now) == 0);

        // the first sweep of a process removes every copy, however young (nothing of a new process can be sharing one); a later one in the same process leaves the live copy
        File leftYoung = new File(cache, "morphe_keyshare/eeeeeeeeeeeeeeee"); leftYoung.mkdirs(); Files.write(new File(leftYoung, "morphe.keystore").toPath(), keyBytes);
        leftYoung.setLastModified(System.currentTimeMillis() - 1000);
        int atStart = KeyShare.sweepAtStart(cache);
        check("start-up: the first sweepAtStart of the process removes a copy that is only a second old (a dead process left it; no share of it can be in flight)", atStart == 1 && !leftYoung.exists(), String.valueOf(atStart));
        File live = KeyShare.stage(cache, key);
        check("start-up: a later sweepAtStart in the same process (the window opened again while a share sheet is reading the copy) leaves the live copy", KeyShare.sweepAtStart(cache) == 0 && live.isFile());
        KeyShare.remove(live);

        // ---- exports at the same time (the bridge runs calls on a cached thread pool): sweep-then-create is one step, so one copy is left ----
        final File cacheC = cache, keyC = key;
        final int threads = 16, rounds = 40;
        int roundsWithOne = 0, errors = 0, wrongBytes = 0;
        File lastErr = null; String firstErr = null;
        for (int r = 0; r < rounds; r++) {
            final java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(threads);
            final List<File> made = Collections.synchronizedList(new ArrayList<File>());
            final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
            List<Thread> ts = new ArrayList<Thread>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { gate.await(); made.add(KeyShare.stage(cacheC, keyC)); }
                        catch (Throwable e) { failures.add(e.toString()); }
                    }
                });
                ts.add(t); t.start();
            }
            for (Thread t : ts) t.join();
            File[] left = new File(cache, "morphe_keyshare").listFiles();
            int copies = 0;
            if (left != null) for (File d : left) if (new File(d, "morphe.keystore").isFile()) copies++;
            if (left != null && left.length == 1 && copies == 1) roundsWithOne++;
            errors += failures.size();
            if (!failures.isEmpty() && firstErr == null) firstErr = failures.get(0);
            if (left != null) for (File d : left) { File f = new File(d, "morphe.keystore"); if (f.isFile() && !Arrays.equals(Files.readAllBytes(f.toPath()), keyBytes)) wrongBytes++; }
            KeyShare.sweep(cache, 0, System.currentTimeMillis());
        }
        check("one at a time, at once: " + threads + " exports in the same moment, " + rounds + " times, always end with exactly one key copy (one folder, one file)", roundsWithOne == rounds, roundsWithOne + " of " + rounds + " rounds had one copy");
        check("one at a time, at once: none of those exports fails (one is never swept while it is being copied) and the copy that is left is whole", errors == 0 && wrongBytes == 0, errors + " errors " + wrongBytes + " wrong " + firstErr);
        check("one at a time, at once: the changes to the key-share folder (stage, remove, sweep) take the same lock", has(read(new File(repo(), "src/com/bloatware/bingblop/KeyShare.java")), "synchronized (LOCK) { return stageLocked(cacheDir, key); }")
            && body(read(new File(repo(), "src/com/bloatware/bingblop/KeyShare.java")), "static boolean remove(").contains("synchronized (LOCK)")
            && body(read(new File(repo(), "src/com/bloatware/bingblop/KeyShare.java")), "static int sweep(File cacheDir, long olderThanMs, long now)").contains("synchronized (LOCK)"));

        // ---- failures leave nothing ----
        String why = null;
        try { KeyShare.stage(cache, new File(base, "missing.keystore")); } catch (java.io.IOException e) { why = e.getMessage(); }
        check("failure: no key yet is an error in words and nothing is made", why != null && why.contains("no signing key yet") && tree(new File(cache, "morphe_keyshare")).isEmpty(), why);
        why = null;
        try { KeyShare.stage(cache, base); } catch (java.io.IOException e) { why = e.getMessage(); }
        check("failure: a folder in place of the key is refused too", why != null && tree(new File(cache, "morphe_keyshare")).isEmpty(), why);
        File notADir = new File(tmp, "cache-is-a-file"); Files.write(notADir.toPath(), new byte[] {0});
        why = null;
        try { KeyShare.stage(notADir, key); } catch (java.io.IOException e) { why = e.getMessage(); }
        check("failure: a cache folder that cannot be made is an error, not a copy somewhere else", why != null && tree(downloads).equals(dlBefore), why);

        // ---- the wiring (source scan: these classes need Android) ----
        File repo = repo();
        String bridge = read(new File(repo, "src/com/bloatware/bingblop/MorpheBridge.java"));
        String ke = body(bridge, "private JSONObject keyExport()");
        check("bridge: keyExport stages the key copy for the share sheet, shares it, and removes the copy when the sheet cannot be opened", has(ke, "KeyShare.stage(host.context().getCacheDir(), k)", "host.shareKey(staged)", "KeyShare.remove(staged)"), ke);
        check("bridge: keyExport has no Downloads, no copy to a shared folder, no path in the answer, and does not go through the generic share", !ke.isEmpty() && !has(ke, "downloadsDir") && !ke.contains("Download") && !ke.contains("MorpheLibrary.copy")
            && !ke.contains("getAbsolutePath") && !ke.contains("host.share(") && !ke.contains("shareFile") && !ke.contains("exportTo"), ke);
        check("bridge: the call is still 'keyExport' (the page needs no other change) and keyImport / keyReset are as they were", has(bridge, "case \"keyExport\": return keyExport();", "case \"keyImport\": return keyImport(a);",
            "case \"keyReset\": new File(base, \"morphe.keystore\").delete(); new File(base, \"morphe_key.json\").delete(); return null;"));
        check("bridge: the Host has shareKey", bridge.contains("void shareKey(File staged) throws Exception;"));
        String roots = body(bridge, "private List<File> allowedRoots()");
        check("bridge: the folders a page may name (shareFile, install, sourceAddLocal ...) do not include the key-share folder; the shareFile case is not changed", !roots.isEmpty() && !roots.contains("KeyShare") && !roots.contains("morphe_keyshare")
            && has(roots, "\"morphe_pick\"") && has(body(bridge, "case \"shareFile\": {"), "MorpheJobs.inside(f, allowedRoots())", "host.share(f, \"application/vnd.android.package-archive\")"), roots);
        String exp = body(bridge, "private JSONObject patchedExport(");
        check("bridge: patched APKs still go to Downloads/Morphe Patcher as before (this change is only the key)", has(exp, "host.downloadsDir()", "Morphe Patcher"));

        String main = read(new File(repo, "src/com/bloatware/bingblop/MainActivity.java"));
        String sk = body(main, "private void shareMorpheKey(");
        check("activity: the Host's shareKey is shareMorpheKey", has(main, "@Override public void shareKey(File staged) throws Exception { shareMorpheKey(staged); }"));
        check("activity: the share sheet gets the key route address with a read grant, not a generic share address or a path", has(sk, "ShareProvider.uriForKey(staged)", "Intent.ACTION_SEND", "FLAG_GRANT_READ_URI_PERMISSION", "Intent.createChooser")
            && !sk.contains("ShareProvider.uriFor(") && !sk.contains("newShareFile") && !sk.contains("shareStoredFile") && !sk.contains("downloadsDir"), sk);
        String expiry = body(main, "private static final class KeyShareExpiry");
        check("activity: the copy is removed and the grant revoked after KeyShare.KEEP_MS, by a timer that holds the application context and the file (not the activity)",
            has(sk, "postDelayed(new KeyShareExpiry(app, uri, staged), KeyShare.KEEP_MS)", "getApplicationContext()") && !sk.contains("MainActivity.this") && !sk.contains("new Runnable"), sk);
        check("activity: that timer is a static nested class (it cannot capture the activity), with only the application context, the address and the file, and it revokes the grant and removes the copy",
            expiry.startsWith("private static final class KeyShareExpiry implements Runnable") && has(expiry, "private final Context app;", "private final Uri uri;", "private final File staged;", "revokeUriPermission(uri", "KeyShare.remove(staged)")
            && !expiry.contains("MainActivity.this") && !expiry.contains("this$0") && !expiry.contains("runOnUiThread") && !expiry.contains("getApplicationContext") && !expiry.contains("Activity "), expiry);
        check("activity: no anonymous Runnable or Handler callback is created in shareMorpheKey (an anonymous class would hold the activity)", !sk.isEmpty() && !sk.contains("new Runnable") && !sk.contains("new android.os.Handler.Callback") && !sk.contains("new Thread"), sk);
        check("activity: a sheet that cannot be opened is an error to the page (the exception is passed on, not swallowed)", has(sk, "open.get(", "throw e.getCause()"), sk);
        int cas1 = sk.indexOf("state.compareAndSet(0, 1)"), sa = sk.indexOf("startActivity(chooser)");
        check("activity: the task checks, before it opens the sheet, that the waiting caller has not given up (compare-and-set 0 to 1 comes before startActivity, and fails with no sheet)", cas1 > 0 && sa > cas1 && has(sk, "if (!state.compareAndSet(0, 1)) return Boolean.FALSE;"), sk);
        int toCatch = sk.indexOf("catch (java.util.concurrent.TimeoutException"), inCatch = sk.indexOf("catch (InterruptedException");
        check("activity: a wait that times out or is interrupted gives the task up (compare-and-set 0 to 2 and cancel) before the failure is passed on",
            toCatch > 0 && inCatch > toCatch && has(sk.substring(toCatch, inCatch), "state.compareAndSet(0, 2)", "open.cancel(false)", "throw e;") && has(sk.substring(inCatch), "state.compareAndSet(0, 2)", "open.cancel(false)", "Thread.currentThread().interrupt()", "throw e;"), sk);
        check("activity: when the UI thread had already started the sheet as the wait ended, the sheet is treated as open (the timer is set), and an error it ended with is still passed on",
            has(sk, "awaitStarted(open)") && has(body(main, "private static void awaitStarted("), "open.get()", "ExecutionException") && sk.indexOf("awaitStarted(open)") < sk.indexOf("postDelayed"), sk);
        check("activity: a copy a dead process left behind is swept at the first start of the process, whatever its age", has(main, "KeyShare.sweepAtStart(getCacheDir())") && !main.contains("KeyShare.sweep(getCacheDir()"));

        String prov = read(new File(repo, "src/com/bloatware/bingblop/ShareProvider.java"));
        String ff = body(prov, "private File fileFor(");
        check("provider: the key route is read through KeyShare.resolve, before and apart from the ordinary route, whose address pattern is unchanged", has(ff, "KeyShare.ROUTE.equals(seg.get(0))", "KeyShare.resolve(getContext().getCacheDir(), seg)")
            && ff.indexOf("KeyShare.resolve") < ff.indexOf("ID.matcher") && prov.contains("Pattern.compile(\"[0-9a-f]{16}\")") && has(prov, "static Uri uriForKey(File staged)", "KeyShare.segments(staged)"), ff);
        check("provider: still not exported, grants only by the one-off URI grant", has(read(new File(repo, "AndroidManifest.xml")), "android:name=\".ShareProvider\"", "android:exported=\"false\"", "android:grantUriPermissions=\"true\""));
        check("PrivatePaths.exportable does not list the key-share folder", !read(new File(repo, "src/com/bloatware/bingblop/PrivatePaths.java")).contains("morphe_keyshare") && !read(new File(repo, "src/com/bloatware/bingblop/PrivatePaths.java")).contains("KeyShare"));

        String page = read(new File(repo, "assets/index.html"));
        String pg = body(page, "async function mpKeyExport()");
        check("page: Export asks (mpAsk) what the file allows before it calls keyExport, and does not report a save or read a path", !pg.isEmpty() && pg.indexOf("mpAsk(") >= 0 && pg.indexOf("mpAsk(") < pg.indexOf("mpCall('keyExport'")
            && pg.contains("sign apps as you") && !pg.contains("Saved to") && !pg.contains(".path") && !pg.contains("Download"), pg);
        check("page: both Export buttons call mpKeyExport", has(page, "id=\"usKeyExportBtn\" onclick=\"mpKeyExport()\"", "<button class=\"mp-act\" onclick=\"mpKeyExport()\">Export the key</button>"));

        // ---- the documents do not say the key is written to Downloads any more ----
        String[] docs = {"docs/guide/04-tools.html", "docs/guide/10-morphe.html", "docs/DEVICE-TEST-CHECKLIST.md", "docs/FULL-GUIDE.md", "README.md"};
        StringBuilder stale = new StringBuilder();
        for (String d : docs) if (read(new File(repo, d)).contains("Downloads/Morphe Patcher/morphe.keystore")) stale.append(d).append(' ');
        check("docs: none of the guide, the checklist, the full guide or the README says the key is written to Downloads/Morphe Patcher/morphe.keystore", stale.length() == 0, stale.toString());
        check("docs: the Help Guide script (assets/guide.js) is built from the fragments", !read(new File(repo, "assets/guide.js")).contains("Downloads/Morphe Patcher/morphe.keystore"));

        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
