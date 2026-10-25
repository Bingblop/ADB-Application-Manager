package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The app's data backup and restore scripts (BackupScripts.dataBackup / dataRestore) run for real with sh and tar against a fake /data
 * tree laid out like a phone's: user/0 is a link to data, and the data folder of a 32-bit app holds installd's link "lib" to its install
 * folder (an absolute path under /data/app). A backup of such an app said OK, and the restore of that very file refused the whole archive
 * ("links that point outside the app"). Every case runs with this machine's tar and, when toybox is on PATH, again with toybox's tar (the
 * one Android ships). am, restorecon and chown are stand-ins that do nothing. Without sh or tar the test says so and passes.
 */
public class BackupScriptsTest {
    static int fails = 0, n = 0;
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

    static final String PKG = "com.example.app32";
    static Path bin;
    static String sysTar;

    static String run(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int k;
        InputStream in = p.getInputStream();
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    static String q(Object s) { return BackupScripts.quote(String.valueOf(s)); }

    static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path f) throws Exception {
        return Files.isRegularFile(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "<missing>";
    }

    static String linkOf(Path f) {
        try { return Files.readSymbolicLink(f).toString(); } catch (Exception e) { return "<not a link>"; }
    }

    static void deleteTree(File f) {
        File[] kids = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** A phone's /data for one app: user/0 -> data, the app's prefs and files, its device-protected folder, and (32-bit) lib -> /data/app/.../lib/arm. */
    static Path phone(Path root, boolean lib32) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("user"));
        Files.createSymbolicLink(root.resolve("user/0"), Paths.get("../data"));   // absolute on a phone; GNU tar refuses to extract through an absolute one
        Path app = root.resolve("data").resolve(PKG);
        write(app.resolve("shared_prefs/prefs.xml"), "<map><int name=\"level\" value=\"7\" /></map>");
        write(app.resolve("files/save.txt"), "saved game");
        Files.createSymbolicLink(app.resolve("files/latest"), Paths.get("save.txt"));   // a link that stays inside: always fine
        write(root.resolve("user_de/0").resolve(PKG).resolve("files/de.txt"), "device protected");
        if (lib32) {
            Path arm = root.resolve("app/~~AbCd==/" + PKG + "-XyZ==/lib/arm");
            Files.createDirectories(arm);
            Files.createSymbolicLink(app.resolve("lib"), arm);
        }
        return app;
    }

    /** A tar of the app's folders made the way dataBackup used to: everything as it is, installd's link included. */
    static String oldBackup(Path root, Path out) throws Exception {
        return run("cd " + q(root) + " && tar -cf " + q(out) + " user/0/" + PKG + " user_de/0/" + PKG + " && echo MADE");
    }

    static void cases(String tar, Path work) throws Exception {
        int uid = Integer.parseInt(run("id -u"));

        // 1. A 32-bit app: back up, change the data, restore the backup the app just made
        Path r1 = Files.createDirectories(work.resolve("r1"));
        Path app = phone(r1, true);
        Path lib = app.resolve("lib");
        String target = linkOf(lib);
        Path out = work.resolve("r1.tar");
        String b = run(BackupScripts.dataBackup(r1.toString(), PKG, out.toString(), uid));
        is(tar + ": the backup of a 32-bit app says OK", b.endsWith("OK"), b);
        String list = run("tar -tf " + q(out));
        // toybox's tar -tf prints "name -> target" for a link
        is(tar + ": installd's lib link is not in a new backup", !java.util.regex.Pattern.compile("(?m)^user/0/" + PKG.replace(".", "\\.") + "/lib( -> .*)?$").matcher(list).find(), list);
        is(tar + ": the app's own files are", list.contains("shared_prefs/prefs.xml") && list.contains("files/save.txt") && list.contains("user_de/0/" + PKG), list);
        write(app.resolve("shared_prefs/prefs.xml"), "changed after the backup");
        String r = run(BackupScripts.dataRestore(r1.toString(), PKG, out.toString()));
        is(tar + ": that backup restores (it said: links that point outside the app)", r.endsWith("OK"), r);
        is(tar + ": the prefs are back", read(app.resolve("shared_prefs/prefs.xml")).contains("value=\"7\""), read(app.resolve("shared_prefs/prefs.xml")));
        is(tar + ": the device-protected files are back", "device protected".equals(read(r1.resolve("user_de/0").resolve(PKG).resolve("files/de.txt"))), "");
        is(tar + ": the app's own relative link is back", "save.txt".equals(linkOf(app.resolve("files/latest"))), linkOf(app.resolve("files/latest")));
        is(tar + ": the phone's lib link is kept", Files.isSymbolicLink(lib) && target.equals(linkOf(lib)), linkOf(lib));
        is(tar + ": no .restorebak folder is left", !Files.exists(r1.resolve("data").resolve(PKG + ".restorebak"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "");

        // 2. A backup made before this fix holds the link (to the old install folder); the app was reinstalled since, so its link is new
        Path r2 = Files.createDirectories(work.resolve("r2"));
        app = phone(r2, true);
        lib = app.resolve("lib");
        Path old = work.resolve("r2-old.tar");
        String made = oldBackup(r2, old);
        is(tar + ": an old-style backup holds the absolute lib link", made.endsWith("MADE") && run("tar -tvf " + q(old)).contains(PKG + "/lib -> /"), made);
        Files.delete(lib);
        Path newArm = Files.createDirectories(r2.resolve("app/~~New==/" + PKG + "-New==/lib/arm"));
        Files.createSymbolicLink(lib, newArm);
        write(app.resolve("files/save.txt"), "changed");
        r = run(BackupScripts.dataRestore(r2.toString(), PKG, old.toString()));
        is(tar + ": an old backup of a 32-bit app restores", r.endsWith("OK"), r);
        is(tar + ": its files are back", "saved game".equals(read(app.resolve("files/save.txt"))), read(app.resolve("files/save.txt")));
        is(tar + ": the lib link is the one of the app as installed now, not the archived one", newArm.toString().equals(linkOf(lib)), linkOf(lib));

        // 3. A 64-bit app with a real folder called lib keeps it: only installd's link is left out
        Path r3 = Files.createDirectories(work.resolve("r3"));
        app = phone(r3, false);
        write(app.resolve("lib/own.bin"), "the app's own file");
        out = work.resolve("r3.tar");
        b = run(BackupScripts.dataBackup(r3.toString(), PKG, out.toString(), uid));
        is(tar + ": a real lib folder is backed up", b.endsWith("OK") && run("tar -tf " + q(out)).contains(PKG + "/lib/own.bin"), b);
        deleteTree(app.resolve("lib").toFile());
        r = run(BackupScripts.dataRestore(r3.toString(), PKG, out.toString()));
        is(tar + ": and restored", r.endsWith("OK") && "the app's own file".equals(read(app.resolve("lib/own.bin"))), r);

        // 4. Hostile archives are still refused, with installd's link in them or not
        String[][] bad = {
            {"files/evil", "/etc"},
            {"files/up", "../../../outside"},
            {"files/a user/0/" + PKG + "/lib -> b", "/etc"},   // a link whose name only looks like the lib line
        };
        int i = 0;
        for (String[] e : bad) {
            for (boolean lib32 : new boolean[] {true, false}) {
                Path r4 = Files.createDirectories(work.resolve("r4-" + i++));
                app = phone(r4, lib32);
                Path l = app.resolve(e[0]);
                Files.createDirectories(l.getParent());
                Files.createSymbolicLink(l, Paths.get(e[1]));
                Path t = work.resolve("bad.tar");
                Files.deleteIfExists(t);
                made = oldBackup(r4, t);
                Files.delete(l);
                write(app.resolve("files/save.txt"), "current");
                r = run(BackupScripts.dataRestore(r4.toString(), PKG, t.toString()));
                String what = tar + (lib32 ? ", with installd's link" : "") + ": a link " + e[0] + " -> " + e[1];
                // toybox's tar -tf prints a link's target too, so its ".." is already caught as an unsafe path
                is(what + " is still refused", made.endsWith("MADE") && (r.equals("ERROR: the archive has links that point outside the app") || r.equals("ERROR: the archive has unsafe paths")), made + " / " + r);
                is(what + ": nothing was changed", "current".equals(read(app.resolve("files/save.txt"))), read(app.resolve("files/save.txt")));
            }
        }

        // 5. lib -> a folder outside, then a file lib/x: what lies under the left-out link is not written either
        Path r5 = Files.createDirectories(work.resolve("r5"));
        app = phone(r5, false);
        Path out5 = Files.createDirectories(r5.resolve("outside"));
        Files.createSymbolicLink(app.resolve("lib"), out5);
        Path t5 = work.resolve("r5.tar");
        made = oldBackup(r5, t5);
        Path r5b = Files.createDirectories(work.resolve("r5b"));
        write(r5b.resolve("user/0").resolve(PKG).resolve("lib/x"), "written through the link");
        made += run("cd " + q(r5b) + " && " + q(sysTar) + " -rf " + q(t5) + " user/0/" + PKG + "/lib/x && echo APPENDED");   // toybox tar cannot append
        is(tar + ": the archive has the link and a file under it", made.contains("MADE") && made.contains("APPENDED") && run("tar -tf " + q(t5)).contains(PKG + "/lib/x"), made);
        r = run(BackupScripts.dataRestore(r5.toString(), PKG, t5.toString()));
        is(tar + ": the rest of that archive restores", r.endsWith("OK"), r);
        is(tar + ": nothing is written through the lib link", !Files.exists(out5.resolve("x")), r);
    }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("backupscripts");
        try {
            bin = Files.createDirectories(tmp.resolve("bin"));
            for (String stub : new String[] {"am", "restorecon", "chown"}) {
                Path s = bin.resolve(stub);
                Files.write(s, "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
                s.toFile().setExecutable(true);
            }
            sysTar = new File("/bin/sh").exists() ? run("command -v tar") : "";
            if (sysTar.isEmpty()) {
                System.out.println("skipped: sh or tar is not available");
                System.out.println("0 checks, 0 failed");
                return;
            }
            cases("tar", Files.createDirectories(tmp.resolve("tar")));
            String toybox = run("command -v toybox");
            if (!toybox.isEmpty() && run("toybox tar --help >/dev/null 2>&1 && echo YES").equals("YES")) {
                Files.createSymbolicLink(bin.resolve("tar"), Paths.get(toybox));
                cases("toybox tar", Files.createDirectories(tmp.resolve("toybox")));
            } else {
                System.out.println("toybox tar is not on PATH: only this machine's tar was used");
            }
        } finally {
            deleteTree(tmp.toFile());
        }
        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
