package com.bloatware.bingblop;

public class FileRulesTest {
    static int fails = 0, n = 0;
    static void eq(String what, String got, String want) { n++; if (want == null ? got != null : !want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static final String P = "/storage/emulated/0";
    static String c(String s) { return FileRules.canonical(s, P); }
    static boolean prot(String s) { return FileRules.isProtected(c(s)); }

    public static void main(String[] a) {
        // canonical form
        eq("empty", c(""), "/");
        eq("null", c(null), "/");
        eq("root", c("/"), "/");
        eq("dup slashes + trailing", c("//data///local//tmp/"), "/data/local/tmp");
        eq("relative becomes absolute", c("data/local/tmp"), "/data/local/tmp");
        eq("sdcard alias", c("/sdcard"), P);
        eq("sdcard alias deep", c("/sdcard/DCIM/Camera"), P + "/DCIM/Camera");
        eq("self primary alias", c("/storage/self/primary/Download"), P + "/Download");
        eq("sdcard-like name is not the alias", c("/sdcardx/foo"), "/sdcardx/foo");
        eq("dot", c("/storage/emulated/0/./Download/."), P + "/Download");
        eq("dotdot inside", c("/storage/emulated/0/Download/../DCIM"), P + "/DCIM");
        eq("the reviewer's case", c("/storage/emulated/0/../0"), P);
        eq("sdcard dotdot", c("/sdcard/.."), "/storage/emulated");
        eq("sdcard dotdot 0", c("/sdcard/../0"), P);
        eq("dotdot above root stays at root", c("/../../.."), "/");
        eq("dotdot to root", c("/data/.."), "/");
        eq("only dots", c("/./././"), "/");
        eq("spaces kept", c("/sdcard/My Files/a b.txt"), P + "/My Files/a b.txt");
        eq("triple dots is a name", c("/a/.../b"), "/a/.../b");
        eq("trailing dotdot", c("/data/local/tmp/.."), "/data/local");
        // an alias that only shows up once the dots are folded is still resolved
        eq("alias behind dotdot", c("/a/../sdcard"), P);
        eq("alias behind dotdot, deep", c("/a/../sdcard/Android"), P + "/Android");
        eq("alias reached by climbing out", c("/storage/emulated/0/../../../sdcard"), P);
        eq("alias reached by climbing out, deep", c("/storage/emulated/0/../../../sdcard/Download"), P + "/Download");
        eq("primary alias behind dotdot", c("/a/../storage/self/primary/DCIM"), P + "/DCIM");
        // canonical is a fixed point
        for (String s : new String[] {"", "/", "//a//b/", "data/local/tmp", "/sdcard", "/sdcard/../0", "/a/../sdcard", "/a/../sdcard/Android", "/storage/emulated/0/../../../sdcard",
                "/storage/self/primary/../..", "/storage/self/primary/../self/primary/Download", "/x/../storage/self/primary", "/sdcard/./../../sdcard/x", "/../sdcard", "/mnt/runtime/default/emulated/0/../0"}) {
            eq("idempotent " + s, c(c(s)), c(s));
        }

        // protected: everything the reviewer found, and what was already
        for (String s : new String[] {"/", "/data", "/system", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0", "/storage/emulated/10",
                "/storage/ABCD-1234", "/mnt/sdcard", "/data/data", "/data/app", "/data/user", "/data/user/0", "/data/local", "/data/local/tmp", "/data/media", "/data/misc", "/data/system",
                "/system/bin", "/system/app", "/vendor/lib", "/apex", "/proc/1", "/dev/block",
                // the reviewer's list
                "/data/media/0", "/data/user_de/0", "/data/user/10", "/data/system/users", "/data/adb", "/data/system_ce", "/mnt/runtime/default/emulated/0", "/storage/emulated/0/../0",
                "/sdcard/..", "/sdcard/../0", "/sdcard/DCIM/../..", "/storage/emulated/0/Download/../..", "/mnt/runtime/default", "/mnt/runtime/default/emulated", "/mnt/user/0/primary",
                "/mnt/pass_through/0/emulated/0", "/data/data/..", "/data/local/tmp/..", "/data/system_de", "/data/misc_ce", "/data/dalvik-cache",
                // second review: the installer view of storage, a volume root seen raw, and a hidden alias
                "/mnt/installer/0", "/mnt/installer/0/emulated", "/mnt/installer/0/emulated/0", "/mnt/media_rw", "/mnt/media_rw/ABCD-1234", "/a/../sdcard", "/storage/emulated/0/../../../sdcard"}) {
            is("protected " + s, prot(s), true);
        }
        // not protected: the files people really delete
        for (String s : new String[] {"/storage/emulated/0/Download/x.apk", "/sdcard/Download/x.apk", "/storage/emulated/0/Android/data/com.foo/cache", "/data/local/tmp/foo", "/data/local/tmp/fm_install.apk",
                "/data/data/com.foo/cache", "/data/data/com.foo", "/data/user/0/com.foo/files", "/data/app/~~abc==/com.foo-1", "/storage/ABCD-1234/Download/x", "/storage/ABCD-1234/DCIM",
                "/mnt/media_rw/ABCD-1234/Download", "/mnt/runtime/default/emulated/0/Download", "/mnt/runtime/default/emulated/0/Download/x", "/system/app/Foo/Foo.apk", "/data/media/0/Download/x",
                "/storage/emulated/0/Download/../DCIM/x", "/sdcard/./Download/x", "/mnt/installer/0/emulated/0/Download", "/mnt/installer/0/emulated/0/Download/x",
                "/mnt/media_rw/ABCD-1234/DCIM", "/a/../sdcard/Download/x"}) {
            is("not protected " + s, prot(s), false);
        }
        // /system/<x>/<y> (depth 3) stays deletable as before (the rule protects the trees themselves)
        is("system depth 3", prot("/system/app/Foo"), false);
        is("system depth 2", prot("/system/app"), true);

        // signed copies never land on their source
        eq("plain", FileRules.signedName("app.apk"), "app-signed.apk");
        eq("upper-case extension", FileRules.signedName("App.APK"), "App-signed.apk");
        eq("no extension", FileRules.signedName("app"), "app-signed.apk");
        eq("already signed", FileRules.signedName("app-release-signed.apk"), "app-release-signed-2.apk");
        eq("signed 2", FileRules.signedName("app-signed-2.apk"), "app-signed-3.apk");
        eq("signed 9", FileRules.signedName("x-signed-9.apk"), "x-signed-10.apk");
        eq("signed in the middle is a plain name", FileRules.signedName("my-signed-app.apk"), "my-signed-app-signed.apk");
        eq("base.apk", FileRules.signedName("base.apk"), "base-signed.apk");
        eq("number-only suffix is plain", FileRules.signedName("app-2.apk"), "app-2-signed.apk");
        for (String name : new String[] {"app.apk", "app-signed.apk", "app-signed-2.apk", "a-signed-signed.apk", ".apk", "signed.apk", "-signed.apk"}) {
            is("never the source: " + name, FileRules.signedName(name).equals(name), false);
        }

        // shell answers that mean the link is gone
        for (String s : new String[] {"error: device offline", "error: no devices/emulators found", "error: device '127.0.0.1:5555' not found", "[Process timed out after 600000ms]", "error: closed", "adb: error: connection refused", "Error: Shizuku is not authorized for this app", "error: device unauthorized."}) {
            is("lost: " + s, FileRules.transportLost(s), true);
        }
        for (String s : new String[] {"", "rm: /sdcard/x: No such file or directory", "mv: Permission denied", "cp: write error: No space left on device", "failed", "Operation not permitted",
                // second review: a file that is merely named like an adb message, or a shell error that says "unauthorized"
                "rm: unauthorized access", "mv: unauthorized.txt: Permission denied", "mv: /sdcard/device offline.txt: Permission denied", "rm: cannot remove 'error: closed': Permission denied",
                "cp: /sdcard/connection refused.log: No such file or directory", "ls: error: closed.txt: No such file", "foo error: closed"}) {
            is("not lost: " + s, FileRules.transportLost(s), false);
        }
        for (String s : new String[] {"rm: a: Permission denied\nerror: closed", "  error: device offline  ", "adb: error: device offline", "mv: x: busy\r\n[Process timed out after 600000ms]", "ERROR: DEVICE OFFLINE",
                "adb: error: device '192.168.1.5:41234' not found"}) {
            is("lost (multi-line / padded): " + s, FileRules.transportLost(s), true);
        }
        is("null", FileRules.transportLost(null), false);

        // the success marker of a shell command is a whole line, never letters inside a message
        is("okLine: marker alone", FileRules.okLine("FMOK", "FMOK"), true);
        is("okLine: after other output, with CRLF and blanks", FileRules.okLine("12345:678\r\n  OK \r\n", "OK"), true);
        is("okLine: a path with the letters inside is not a success", FileRules.okLine("cp: /sdcard/BOOKS/a.txt: No such file or directory", "OK"), false);
        is("okLine: a name that ends in OK on its own line is not the marker", FileRules.okLine("NOT OK\nBOOK", "OK"), false);
        is("okLine: FMOK is not OK", FileRules.okLine("FMOK", "OK"), false);
        is("okLine: the message of a taken name is not a success", FileRules.okLine("A file or folder with that name is already there", "FMOK"), false);
        is("okLine: null and empty", FileRules.okLine(null, "OK") || FileRules.okLine("", "OK") || FileRules.okLine("OK", ""), false);

        // MA2-2: which items of a cp / mv / rm batch are refused, whichever route the batch takes (plain, or into or out of added storage)
        final String D = "/data/user/0/com.bloatware.bingblop";
        FileRules.PrivateTest priv = new FileRules.PrivateTest() {
            public boolean isPrivate(String p) { return !p.startsWith("content://") && (p.equals(D) || p.startsWith(D + "/")) && !p.startsWith(D + "/cache/share/"); }
        };
        String WHY = "private", SYS = "System location: not touched";
        String key = D + "/files/adb_home/.android/adbkey", prefs = D + "/shared_prefs";
        String saf = "content://com.android.externalstorage.documents/tree/1234-5678%3A/document/1234-5678%3A";
        String safFile = saf + "notes.txt";
        eq("the adb key copied to added storage is refused", FileRules.batchRefusal("cp", key, saf, priv, WHY), WHY);
        eq("the adb key moved to added storage is refused", FileRules.batchRefusal("mv", key, saf, priv, WHY), WHY);
        eq("the adb key copied to a plain folder is refused", FileRules.batchRefusal("cp", key, P + "/Download", priv, WHY), WHY);
        eq("a file of added storage copied into the app's settings folder is refused", FileRules.batchRefusal("cp", safFile, prefs, priv, WHY), WHY);
        eq("a file of added storage moved into the app's settings folder is refused", FileRules.batchRefusal("mv", safFile, prefs, priv, WHY), WHY);
        eq("a plain file copied into the app's settings folder is refused", FileRules.batchRefusal("cp", P + "/a.txt", prefs, priv, WHY), WHY);
        eq("a system tree moved to added storage is refused", FileRules.batchRefusal("mv", "/system/app", saf, priv, WHY), SYS);
        eq("a whole storage root moved to added storage is refused", FileRules.batchRefusal("mv", P, saf, priv, WHY), SYS);
        eq("a shared-storage file copied to added storage is allowed", FileRules.batchRefusal("cp", P + "/Download/a.txt", saf, priv, WHY), null);
        eq("a file of added storage copied to shared storage is allowed", FileRules.batchRefusal("cp", safFile, P + "/Download", priv, WHY), null);
        eq("added storage to added storage is allowed", FileRules.batchRefusal("mv", safFile, saf, priv, WHY), null);
        eq("a file of added storage removed is allowed", FileRules.batchRefusal("rm", safFile, "", priv, WHY), null);
        eq("an allowed cache folder of the app copied to added storage is allowed", FileRules.batchRefusal("cp", D + "/cache/share/a/app.apk", saf, priv, WHY), null);
        eq("rm ignores the destination", FileRules.batchRefusal("rm", P + "/Download/a.txt", prefs, priv, WHY), null);
        eq("the adb key removed is refused", FileRules.batchRefusal("rm", key, "", priv, WHY), WHY);

        // v7.18.2 root file management: permissions and owner
        eq("refuse root", FileRules.permRefusal("/", false), "System location: not touched");
        eq("refuse top level", FileRules.permRefusal("/data", false), "System location: not touched");
        eq("refuse storage root", FileRules.permRefusal(P, true), "System location: not touched");
        eq("refuse system tree", FileRules.permRefusal("/system/bin", false), "System location: not touched");
        eq("a file under system is fine", FileRules.permRefusal("/system/bin/sh", false), null);
        eq("but not a whole system folder at once", FileRules.permRefusal("/system/etc/init", true), "A whole folder of the system partitions: not changed at once");
        eq("a data folder recursively is fine", FileRules.permRefusal("/data/local/tmp/x", true), null);
        eq("no path", FileRules.permRefusal("", false), "No path");
        eq("relative path", FileRules.permRefusal("tmp/x", false), "No path");
        eq("chmod (a link refused: chmod would follow it)", FileRules.chmodCommand("/data/local/tmp/a b", "644", false), "if [ -L '/data/local/tmp/a b' ]; then echo 'This is a link: open the file it points to and change the permissions there'; else chmod 644 -- '/data/local/tmp/a b' && echo FMOK; fi");
        eq("chmod -R 4 digits", FileRules.chmodCommand("/data/x", "0755", true), "if [ -L '/data/x' ]; then echo 'This is a link: open the file it points to and change the permissions there'; else chmod -R 0755 -- '/data/x' && echo FMOK; fi");
        eq("chmod quote", FileRules.chmodCommand("/data/it's", "600", false), "if [ -L '/data/it'\\''s' ]; then echo 'This is a link: open the file it points to and change the permissions there'; else chmod 600 -- '/data/it'\\''s' && echo FMOK; fi");
        eq("restorecon", FileRules.restoreconCommand("/data/data/com.a/files/x b", false), "restorecon -- '/data/data/com.a/files/x b' && echo FMOK");
        eq("restorecon -R quote", FileRules.restoreconCommand("/data/it's", true), "restorecon -R -- '/data/it'\\''s' && echo FMOK");
        for (String op : new String[] { "stat", "chmod", "chmod-r", "chown", "chown-r", "restorecon", "restorecon-r" }) is("perm op keeps its mode/owner argument as typed: " + op, FileRules.isPermOp(op), true);
        for (String op : new String[] { "cp", "mv", "rm", "mkdir", "touch", "chmodx", "", null }) is("a path op: " + op, FileRules.isPermOp(op), false);
        for (String bad : new String[] { "", "64", "888", "u+x", "12345", "644; rm -rf /", null }) {
            boolean threw = false;
            try { FileRules.chmodCommand("/data/x", bad, false); } catch (IllegalArgumentException e) { threw = true; }
            is("bad mode refused: " + bad, threw, true);
        }
        eq("chown both", FileRules.chownCommand("/data/x", "root", "shell", false), "chown -h root:shell -- '/data/x' && echo FMOK");
        eq("chown numbers -R", FileRules.chownCommand("/data/x", "1000", "1000", true), "chown -h -R 1000:1000 -- '/data/x' && echo FMOK");
        eq("chown owner only", FileRules.chownCommand("/data/x", "system", "", false), "chown -h system -- '/data/x' && echo FMOK");
        eq("chown group only", FileRules.chownCommand("/data/x", "", "media_rw", false), "chown -h :media_rw -- '/data/x' && echo FMOK");
        eq("chown app user", FileRules.chownCommand("/data/x", "u0_a123", "u0_a123", false), "chown -h u0_a123:u0_a123 -- '/data/x' && echo FMOK");
        for (String[] bad : new String[][] { { "", "" }, { "root;id", "" }, { "", "a b" }, { "-R", "" }, { "$(id)", "" }, { "root:root", "" } }) {
            boolean threw = false;
            try { FileRules.chownCommand("/data/x", bad[0], bad[1], false); } catch (IllegalArgumentException e) { threw = true; }
            is("bad owner refused: " + bad[0] + "/" + bad[1], threw, true);
        }
        is("stat reads the marker line", FileRules.statCommand("/data/x").startsWith("stat -c 'FMSTAT %a|%U|%G|%u|%g|%F' -- '/data/x'"), true);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
