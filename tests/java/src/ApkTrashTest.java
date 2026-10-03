package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deleting a found package file with an Undo (ApkTrash: which paths may be handled, where the trashed copy waits) and the progress
 * the storage search reports (ApkScan.walkRoot / countTopFolders), against a real folder tree.
 */
public class ApkTrashTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final String P = "/storage/emulated/0";
    static final String SD = "/storage/ABCD-1234";
    static final String T = P + "/" + ApkTrash.DIR;

    static void write(File f, int len) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[len]);
    }

    public static void main(String[] a) throws Exception {
        // ---------- which storage volume a path is on ----------
        eq("primary", ApkTrash.volumeRoot(P + "/Download/a.apk"), P);
        eq("the volume itself", ApkTrash.volumeRoot(P), P);
        eq("another user's storage", ApkTrash.volumeRoot("/storage/emulated/10/x.apk"), "/storage/emulated/10");
        eq("SD card", ApkTrash.volumeRoot(SD + "/Download/a.apk"), SD);
        eq("SD card, lower case id", ApkTrash.volumeRoot("/storage/abcd-12ef/a.apk"), "/storage/abcd-12ef");
        eq("the alias is not canonical", ApkTrash.volumeRoot("/sdcard/a.apk"), null);
        eq("self primary alias", ApkTrash.volumeRoot("/storage/self/primary/a.apk"), null);
        eq("app tmp", ApkTrash.volumeRoot("/data/local/tmp/a.apk"), null);
        eq("not a volume id", ApkTrash.volumeRoot("/storage/ABCD/a.apk"), null);
        eq("emulated folder alone", ApkTrash.volumeRoot("/storage/emulated"), null);
        eq("a prefix is not the volume", ApkTrash.volumeRoot("/storage/emulated/0x/a.apk"), null);
        eq("null", ApkTrash.volumeRoot(null), null);
        eq("empty", ApkTrash.volumeRoot(""), null);

        // ---------- what may be deleted ----------
        for (String ok : new String[] { P + "/Download/a.apk", P + "/a.apk", P + "/Download/A.APKS", SD + "/Games/b.xapk", P + "/Android/data/com.x/files/c.apkm",
                P + "/My Files/it's a (1).apk", P + "/a/b/c/d/e/f/g.apk", "/storage/emulated/10/x.apk" }) {
            is("deletable " + ok, ApkTrash.deletable(ok), true);
        }
        for (String no : new String[] { P, P + "/", P + "/Download", P + "/Download/a.zip", P + "/Download/.apk", P + "/Download/apk", P + "/Download/a.apk/",
                "/data/app/x/base.apk", "/system/app/x/x.apk", "/data/local/tmp/fm_install.apk", "/sdcard/a.apk", "",
                T, T + "/1_a.apk", SD + "/" + ApkTrash.DIR + "/1_a.apk",
                P + "/../0/a.apk", P + "/Download/../x.apk", P + "/Download/..", P + "//a.apk" }) {
            is("not deletable " + no, ApkTrash.deletable(no), false);
        }
        is("null", ApkTrash.deletable(null), false);

        // ---------- where it waits ----------
        eq("trash path", ApkTrash.trashPath(P + "/Download/a.apk", 123L), T + "/123_a.apk");
        eq("trash path on the SD card", ApkTrash.trashPath(SD + "/x/y.xapk", 9L), SD + "/" + ApkTrash.DIR + "/9_y.xapk");
        eq("trash path of a name with spaces", ApkTrash.trashPath(P + "/Download/My App (1).apk", 5L), T + "/5_My App (1).apk");
        eq("not on a volume", ApkTrash.trashPath("/data/local/tmp/a.apk", 1L), null);
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append((char) ('a' + i % 26));
        String lp = P + "/Download/" + longName + ".apk";
        String ltp = ApkTrash.trashPath(lp, 1700000000000L);
        is("a long name is cut to fit a file system", ltp.substring(ltp.lastIndexOf('/') + 1).length() <= ApkTrash.MAX_NAME_BYTES + 14, true);
        is("…and keeps its extension", ltp.endsWith(".apk"), true);
        is("…and is still a trash path", ApkTrash.isTrashPath(ltp), true);

        // ---------- what is a trashed file ----------
        is("trash file", ApkTrash.isTrashPath(T + "/123_a.apk"), true);
        is("trash file on SD", ApkTrash.isTrashPath(SD + "/" + ApkTrash.DIR + "/1_b.apk"), true);
        is("the folder itself is not", ApkTrash.isTrashPath(T), false);
        is("the folder with a slash is not", ApkTrash.isTrashPath(T + "/"), false);
        is("deeper is not", ApkTrash.isTrashPath(T + "/x/1_a.apk"), false);
        is("a folder of that name further down is not", ApkTrash.isTrashPath(P + "/Download/" + ApkTrash.DIR + "/1_a.apk"), false);
        is("other folder", ApkTrash.isTrashPath(P + "/Download/a.apk"), false);
        is("not a volume", ApkTrash.isTrashPath("/data/local/tmp/" + ApkTrash.DIR + "/a.apk"), false);
        is("null", ApkTrash.isTrashPath(null), false);

        // ---------- putting it back ----------
        String orig = P + "/Download/a.apk";
        is("restore", ApkTrash.restorable(ApkTrash.trashPath(orig, 7L), orig), true);
        is("restore on SD", ApkTrash.restorable(ApkTrash.trashPath(SD + "/x/b.apk", 7L), SD + "/x/b.apk"), true);
        is("another volume is refused", ApkTrash.restorable(ApkTrash.trashPath(orig, 7L), SD + "/Download/a.apk"), false);
        is("a file that was never trashed is refused", ApkTrash.restorable(P + "/Download/a.apk", P + "/Download/b.apk"), false);
        is("a target that could not have been deleted is refused", ApkTrash.restorable(ApkTrash.trashPath(orig, 7L), P + "/Download/a.txt"), false);
        is("a target in the trash is refused", ApkTrash.restorable(T + "/1_a.apk", T + "/2_a.apk"), false);
        is("a target outside storage is refused", ApkTrash.restorable(T + "/1_a.apk", "/data/app/base.apk"), false);
        for (String p : new String[] { P + "/a.apk", P + "/Download/x y.apks", SD + "/Games/Big Game (2).xapk", P + "/d/e.APKM" }) {
            String t = ApkTrash.trashPath(p, 42L);
            is("round trip " + p, ApkTrash.deletable(p) && ApkTrash.isTrashPath(t) && ApkTrash.restorable(t, p) && ApkTrash.volumeRoot(t).equals(ApkTrash.volumeRoot(p)), true);
        }

        // ---------- odd names ----------
        String nl = P + "/Download/line\nbreak.apk";
        is("a name with a line break is a path like any other", ApkTrash.volumeRoot(nl) != null && ApkTrash.deletable(nl) && ApkTrash.isTrashPath(ApkTrash.trashPath(nl, 3L)) && ApkTrash.restorable(ApkTrash.trashPath(nl, 3L), nl), true);
        is("…so does a carriage return or U+2028", ApkTrash.deletable(P + "/a\rb.apk") && ApkTrash.deletable(P + "/a\u2028b.apk") && ApkTrash.deletable(P + "/a\u0085b.apk"), true);
        is("a line break in the folder name too", ApkTrash.deletable(P + "/two\nlines/a.apk") && ApkTrash.volumeRoot(SD + "/x\ny/a.apk") != null, true);
        // a name is cut by bytes, not by characters: a file name holds at most 255 bytes, and the stamp and its "_" take 14 of them
        eq("tail: short names stay", ApkTrash.tail("abc", 10), "abc");
        eq("tail: the end is kept", ApkTrash.tail("abcdef", 3), "def");
        eq("tail: empty", ApkTrash.tail("", 5), "");
        eq("tail: two-byte letters", ApkTrash.tail("\u00e9\u00e9\u00e9\u00e9\u00e9", 4), "\u00e9\u00e9");
        eq("tail: an odd limit never cuts a letter", ApkTrash.tail("\u00e9\u00e9\u00e9", 5), "\u00e9\u00e9");
        eq("tail: a four-byte character (a surrogate pair) is whole or gone", ApkTrash.tail("a\uD83D\uDE00b", 5), "\uD83D\uDE00b");
        eq("tail: …gone when it does not fit", ApkTrash.tail("a\uD83D\uDE00b", 4), "b");
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < 80; i++) cjk.append('\u6587');                    // 240 bytes: a legal name with ".apk" (244 bytes)
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 70; i++) emoji.appendCodePoint(0x1F600);           // 280 bytes
        for (String nm : new String[] { cjk + ".apk", emoji + ".apk", longName + ".apk" }) {
            String tp = ApkTrash.trashPath(P + "/Download/" + nm, 1700000000000L);
            String leaf = tp.substring(tp.lastIndexOf('/') + 1);
            is("a long name fits a file system (" + leaf.getBytes("UTF-8").length + " bytes)", leaf.getBytes("UTF-8").length <= 255 && leaf.endsWith(".apk") && ApkTrash.isTrashPath(tp), true);
            boolean whole = true;
            for (int i = 0; i < leaf.length(); i++) if (Character.isHighSurrogate(leaf.charAt(i)) != (i + 1 < leaf.length() && Character.isLowSurrogate(leaf.charAt(i + 1))) && Character.isHighSurrogate(leaf.charAt(i))) whole = false;
            is("…with no character cut in two", whole && !Character.isLowSurrogate(leaf.charAt("1700000000000_".length())), true);
        }
        // two files deleted in the same millisecond must not share a trash name (a rename would replace the first)
        long s1 = ApkTrash.nextStamp(1000L), s2 = ApkTrash.nextStamp(1000L), s3 = ApkTrash.nextStamp(500L), s4 = ApkTrash.nextStamp(5000L), s5 = ApkTrash.nextStamp(5000L);
        is("stamps never repeat", s1 == 1000L && s2 == 1001L && s3 == 1002L && s4 == 5000L && s5 == 5001L, true);
        is("…so the same name from two folders waits under two names", !ApkTrash.trashPath(P + "/A/base.apk", s1).equals(ApkTrash.trashPath(P + "/B/base.apk", s2)), true);

        // ---------- the name a restored file gets ----------
        final Set<String> taken = new HashSet<String>();
        ApkTrash.Exists ex = new ApkTrash.Exists() { public boolean at(String path) { return taken.contains(path); } };
        eq("a free name is kept", ApkTrash.uniqueTarget(P + "/Download/a.apk", ex), P + "/Download/a.apk");
        taken.add(P + "/Download/a.apk");
        eq("a taken name gets (2)", ApkTrash.uniqueTarget(P + "/Download/a.apk", ex), P + "/Download/a (2).apk");
        taken.add(P + "/Download/a (2).apk");
        taken.add(P + "/Download/a (3).apk");
        eq("…the next free one", ApkTrash.uniqueTarget(P + "/Download/a.apk", ex), P + "/Download/a (4).apk");
        taken.add(P + "/x/.hidden.apks");
        eq("a leading dot is part of the name, not an extension", ApkTrash.uniqueTarget(P + "/x/.hidden.apks", ex), P + "/x/.hidden (2).apks");
        taken.add(P + "/x/noext");
        eq("no extension", ApkTrash.uniqueTarget(P + "/x/noext", ex), P + "/x/noext (2)");
        taken.add(P + "/x/two.dots.xapk");
        eq("the last dot starts the extension", ApkTrash.uniqueTarget(P + "/x/two.dots.xapk", ex), P + "/x/two.dots (2).xapk");
        for (int i = 2; i < 50; i++) taken.add(P + "/y/b (" + i + ").apk");
        taken.add(P + "/y/b.apk");
        eq("when 48 are taken there is no name", ApkTrash.uniqueTarget(P + "/y/b.apk", ex), null);
        boolean threw = false;
        try { ApkTrash.uniqueTarget(P + "/z.apk", new ApkTrash.Exists() { public boolean at(String path) throws Exception { throw new java.io.IOException("shell gone"); } }); }
        catch (java.io.IOException e) { threw = true; }
        is("a shell that does not answer is an error, not a free name", threw, true);

        // ---------- a shell that does not answer is not "no" ----------
        is("YES", ApkTrash.yesNo("YES\n"), true);
        is("NO", ApkTrash.yesNo("NO\n"), false);
        is("noise before the answer", ApkTrash.yesNo("warning: something\nYES\n"), true);
        is("windows line ends and blank lines", ApkTrash.yesNo("\r\nNO\r\n\r\n"), false);
        is("no line end", ApkTrash.yesNo("YES"), true);
        for (String dead : new String[] { "", "\n", null, "error: device offline\n", "Error: Cannot run program \"su\"", "adb: no devices/emulators found", "YES maybe", "yes", "NO\nerror: closed" }) {
            boolean threw2 = false; String why = null;
            try { ApkTrash.yesNo(dead); } catch (java.io.IOException e) { threw2 = true; why = e.getMessage(); }
            is("not a yes or no, an error: " + dead, threw2, true);
            if (dead != null && !dead.trim().isEmpty()) eq("…that says what the shell said: " + dead, why, dead.trim().substring(dead.trim().lastIndexOf('\n') + 1).trim());
            else eq("…or that it did not answer: " + dead, why, "The working mode did not answer.");
        }

        // ---------- the shell's list never shows what is in the trash ----------
        List<String> found = ApkScan.parseFindOutput(P + "/Download/a.apk\n" + T + "/1_a.apk\n" + SD + "/" + ApkTrash.DIR + "/2_b.xapk\n/x/y.apk\n" + P + "/z.txt\n", 100);
        eq("find output without the trash", found.toString(), "[" + P + "/Download/a.apk, /x/y.apk]");

        // ---------- progress of the search, against a real folder tree ----------
        File base = Files.createTempDirectory("apktrash").toFile();
        File r1 = new File(base, "internal"), r2 = new File(base, "card");
        write(new File(r1, "a.apk"), 10);
        write(new File(r1, "empty.apk"), 0);
        write(new File(r1, "Download/x.apk"), 10);
        write(new File(r1, "Download/sub/y.apks"), 10);
        write(new File(r1, "Download/notes.txt"), 10);
        new File(r1, "Music").mkdirs();
        write(new File(r1, "Games/g.xapk"), 10);
        write(new File(r1, ApkTrash.DIR + "/1_z.apk"), 10);
        write(new File(r1, ".thumbnails/w.apk"), 10);
        write(new File(r2, "Backup/b.apkm"), 10);
        write(new File(r2, "Backup/deep/er/c.apk"), 10);
        new File(r2, "Empty").mkdirs();

        List<File> roots = new ArrayList<File>();
        roots.add(r1); roots.add(r2);
        int total = ApkScan.countTopFolders(roots);
        eq("top-level folders (the trash and thumbnails are not counted)", total, 5);       // Download, Music, Games, Backup, Empty
        eq("none for a missing folder", ApkScan.countTopFolders(new ArrayList<File>(java.util.Arrays.asList(new File(base, "nope")))), 0);

        final List<String> events = new ArrayList<String>();
        final int[] last = { 0, -1 };
        final boolean[] ordered = { true };
        ApkScan.Progress progress = new ApkScan.Progress() {
            @Override
            public void onProgress(int done, int tot, String folder, int foundNow) {
                events.add(done + "/" + tot + ":" + folder);
                if (done != last[0] + 1 || foundNow < last[1]) ordered[0] = false;
                last[0] = done; last[1] = foundNow;
            }
        };
        ApkScan.Limits lim = new ApkScan.Limits();
        lim.deadlineMs = System.currentTimeMillis() + 20000;
        List<ApkScan.Entry> out = new ArrayList<ApkScan.Entry>();
        Set<String> seen = new HashSet<String>();
        int done = 0;
        for (File r : roots) done += ApkScan.walkRoot(r, out, seen, lim, done, total, progress);
        eq("every top-level folder is gone through", done, 5);
        eq("one report per folder", events.size(), 5);
        is("the reports count up one by one, of the same total, and the finds never go down", ordered[0], true);
        is("the last report is the total", events.get(events.size() - 1).startsWith("5/5:"), true);
        is("every report names its total", events.get(0).contains("/5:"), true);
        Set<String> names = new java.util.TreeSet<String>();
        for (ApkScan.Entry e : out) names.add(e.name);
        eq("what was found (root files too; no empty file, nothing from the trash or thumbnails)", names.toString(), "[a.apk, b.apkm, c.apk, g.xapk, x.apk, y.apks]");
        is("the search was not cut short", lim.hitLimit, false);
        eq("the last report counts every file found", last[1], 6);

        // a limit stops it and says so
        ApkScan.Limits tight = new ApkScan.Limits();
        tight.deadlineMs = System.currentTimeMillis() + 20000;
        tight.maxResults = 2;
        List<ApkScan.Entry> few = new ArrayList<ApkScan.Entry>();
        ApkScan.walkRoot(r1, few, new HashSet<String>(), tight, 0, 3, null);
        is("a result limit is reported", tight.hitLimit, true);
        is("…and keeps what was found", few.size() >= 1 && few.size() <= 3, true);

        // an unreadable root does nothing, a null listener is fine
        eq("missing root", ApkScan.walkRoot(new File(base, "nope"), new ArrayList<ApkScan.Entry>(), new HashSet<String>(), new ApkScan.Limits(), 0, 0, null), 0);
        ApkScan.Limits lim2 = new ApkScan.Limits();
        lim2.deadlineMs = System.currentTimeMillis() + 20000;
        eq("no listener", ApkScan.walkRoot(r2, new ArrayList<ApkScan.Entry>(), new HashSet<String>(), lim2, 0, 2, null), 2);

        // the old walk still works the same
        ApkScan.Limits lim3 = new ApkScan.Limits();
        lim3.deadlineMs = System.currentTimeMillis() + 20000;
        List<ApkScan.Entry> old = new ArrayList<ApkScan.Entry>();
        ApkScan.walk(r1, 0, old, new HashSet<String>(), lim3);
        Set<String> oldNames = new java.util.TreeSet<String>();
        for (ApkScan.Entry e : old) oldNames.add(e.name);
        eq("walk() skips the trash folder as well", oldNames.toString(), "[a.apk, g.xapk, x.apk, y.apks]");

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
