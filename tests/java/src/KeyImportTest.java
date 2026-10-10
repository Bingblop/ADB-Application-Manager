package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Importing a signing keystore: a bad file, a wrong password or a store without the key replaces nothing; a good one replaces the key and keeps the old as .bak. */
public class KeyImportTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static File keystore(File dir, String name, String type, String alias, String storePass, String keyPass) throws Exception {
        File f = new File(dir, name);
        String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=test",
            "-keystore", f.getPath(), "-storetype", type, "-storepass", storePass, "-keypass", keyPass).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0) { }
        if (p.waitFor() != 0 || !f.isFile()) throw new IllegalStateException("keytool failed for " + name);
        return f;
    }

    static byte[] bytes(File f) throws Exception { return Files.readAllBytes(f.toPath()); }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("keyimport").toFile();
        File picked = new File(root, "picked"); picked.mkdirs();
        File old = keystore(picked, "old.p12", "PKCS12", "Morphe", "oldpass1", "oldpass1");
        File good = keystore(picked, "good.p12", "PKCS12", "Morphe", "newpass1", "newpass1");
        File otherAlias = keystore(picked, "other.p12", "PKCS12", "mykey", "newpass1", "newpass1");
        File jks = keystore(picked, "made-by-morphe.jks", "JKS", "Morphe", "changeit", "Morphe");
        File text = new File(picked, "notes.txt"); Files.write(text.toPath(), "this is not a keystore".getBytes(StandardCharsets.UTF_8));

        check("check: a good store and password pass", KeyImport.check(good, "newpass1") == null, KeyImport.check(good, "newpass1"));
        check("check: a wrong password is refused", KeyImport.check(good, "nope") != null, null);
        check("check: a text file is refused", KeyImport.check(text, "") != null && KeyImport.check(text, "x") != null, null);
        check("check: a missing file is refused", KeyImport.check(new File(picked, "nothing"), "x") != null, null);
        check("check: a store without the key named Morphe is refused, in words", String.valueOf(KeyImport.check(otherAlias, "newpass1")).contains("no signing key"), KeyImport.check(otherAlias, "newpass1"));
        check("check: an empty password opens a Morphe-made store (key password Morphe)", KeyImport.check(jks, "") == null, KeyImport.check(jks, ""));
        check("check: an empty file is refused", KeyImport.check(Files.createFile(new File(picked, "empty").toPath()).toFile(), "") != null, null);

        // a first import into an empty folder
        File base = new File(root, "morphe");
        check("a good key installs", KeyImport.install(base, old, "oldpass1") == null, KeyImport.install(base, old, "oldpass1"));
        byte[] oldBytes = bytes(old);
        check("the store is in place and its info says alias, key and store password", Arrays.equals(bytes(new File(base, "morphe.keystore")), oldBytes)
            && new org.json.JSONObject(new String(bytes(new File(base, "morphe_key.json")), StandardCharsets.UTF_8)).getString("storePassword").equals("oldpass1"), null);

        // a text file, a wrong password and a store without the key all leave the key as it was
        String[] why = {KeyImport.install(base, text, ""), KeyImport.install(base, good, "wrong"), KeyImport.install(base, otherAlias, "newpass1")};
        check("a text file, a wrong password and a wrong alias are refused with a sentence", why[0] != null && why[1] != null && why[2] != null && why[0].contains("Nothing was changed"), Arrays.toString(why));
        check("... and the old key and its info are untouched", Arrays.equals(bytes(new File(base, "morphe.keystore")), oldBytes)
            && new org.json.JSONObject(new String(bytes(new File(base, "morphe_key.json")), StandardCharsets.UTF_8)).getString("password").equals("oldpass1"), null);
        check("... and no temporary file is left behind", !new File(base, "morphe.keystore.new").exists() && !new File(base, "morphe_key.json.new").exists(), null);

        // a good replacement keeps the old pair as .bak
        check("a second good key replaces the first", KeyImport.install(base, good, "newpass1") == null, null);
        check("the new key is in place", Arrays.equals(bytes(new File(base, "morphe.keystore")), bytes(good)), null);
        check("the previous key and info are kept as .bak", Arrays.equals(bytes(new File(base, "morphe.keystore.bak")), oldBytes)
            && new org.json.JSONObject(new String(bytes(new File(base, "morphe_key.json.bak")), StandardCharsets.UTF_8)).getString("storePassword").equals("oldpass1"), null);

        // a Morphe-made store with an empty password: no store password in the info, the key password is the default
        check("an empty password installs a Morphe-made store", KeyImport.install(base, jks, "") == null, null);
        org.json.JSONObject ki = new org.json.JSONObject(new String(bytes(new File(base, "morphe_key.json")), StandardCharsets.UTF_8));
        check("... with the default key password and no store password", ki.getString("password").equals("Morphe") && !ki.has("storePassword") && ki.getString("alias").equals("Morphe"), ki.toString());

        // a failure while putting the files in place restores the old pair
        File base2 = new File(root, "morphe2");
        check("setup: an old key", KeyImport.install(base2, old, "oldpass1") == null, null);
        byte[] before = bytes(new File(base2, "morphe.keystore"));
        File info = new File(base2, "morphe_key.json");
        byte[] infoBefore = bytes(info);
        info.delete(); info.mkdirs(); Files.write(new File(info, "x").toPath(), new byte[] {1});     // the info path is now a folder that cannot be replaced
        String failed2 = KeyImport.install(base2, good, "newpass1");
        check("a failure while saving is reported and the old store is back", failed2 != null && failed2.contains("old key was kept") && Arrays.equals(bytes(new File(base2, "morphe.keystore")), before), failed2);
        new File(info, "x").delete(); info.delete();
        check("the failed attempt left no .new files", !new File(base2, "morphe.keystore.new").exists() && !new File(base2, "morphe_key.json.new").exists(), null);

        // the bridge uses it
        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        if (src != null) {
            String m = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
            int i = m.indexOf("private JSONObject keyImport(");
            String body = i < 0 ? "" : m.substring(i, Math.min(m.length(), i + 900));
            check("the bridge's keyImport goes through KeyImport.install and does not copy over the key itself", body.contains("KeyImport.install(") && !body.contains("MorpheLibrary.copy"), null);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }
}
