package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyStore;

/**
 * Importing a signing keystore for the Morphe Patcher. The imported key replaces the one that signed the apps patched so far, and Android refuses an update
 * signed with another key, so a file that is not a keystore, a wrong password or a store without the expected key must not replace anything: the new file is
 * copied beside the old one, opened with the password, checked for the key, and only then moved into place, with the old key and its info kept as .bak.
 * Pure Java (no android.*): the keystore types come from the running Java (Android has BKS, a computer has PKCS12 and JKS).
 */
final class KeyImport {
    private KeyImport() {}

    static final String ALIAS = "Morphe";                      // the alias the app tells the engine to sign with
    static final String DEFAULT_PASSWORD = "Morphe";           // the key password of a keystore made by Morphe itself
    private static final String[] TYPES = {"BKS", "PKCS12", "JKS", "JCEKS", "BCFKS"};

    /** Null when {@code store} opens with {@code password} and holds a private key under {@link #ALIAS}; otherwise a sentence for the person. */
    static String check(File store, String password) {
        String pw = password == null ? "" : password;
        char[] storePass = pw.isEmpty() ? null : pw.toCharArray();
        char[] keyPass = (pw.isEmpty() ? DEFAULT_PASSWORD : pw).toCharArray();
        boolean opened = false;
        for (String type : TYPES) {
            KeyStore ks;
            try { ks = KeyStore.getInstance(type); } catch (Exception notThere) { continue; }
            InputStream in = null;
            try {
                in = new FileInputStream(store);
                ks.load(in, storePass);
            } catch (Exception wrongTypeOrPassword) {
                continue;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
            }
            opened = true;
            try {
                if (!ks.containsAlias(ALIAS)) continue;
                Key k = ks.getKey(ALIAS, keyPass);
                if (k instanceof java.security.PrivateKey) return null;
            } catch (Exception noKey) {
                // a key with another password: the same sentence as a missing key below
            }
        }
        return opened
            ? "The keystore opened, but it has no signing key named \"" + ALIAS + "\" that opens with this password. Nothing was changed."
            : "That file is not a keystore this app can open, or the password is wrong. Nothing was changed.";
    }

    /**
     * Checks {@code picked} and, only when it is good, makes it the key in {@code base} ({@code morphe.keystore} and {@code morphe_key.json}). Returns null on
     * success or a sentence on failure; on failure the files in {@code base} are as they were. The previous pair stays as {@code .bak}.
     */
    static String install(File base, File picked, String password) {
        File keystore = new File(base, "morphe.keystore"), info = new File(base, "morphe_key.json");
        File fresh = new File(base, "morphe.keystore.new"), oldKs = new File(base, "morphe.keystore.bak"), oldInfo = new File(base, "morphe_key.json.bak");
        try {
            base.mkdirs();
            Files.copy(picked.toPath(), fresh.toPath(), StandardCopyOption.REPLACE_EXISTING);        // judge the copy, not a file that may change under us
            String why = check(fresh, password);
            if (why != null) return why;
            String pw = password == null ? "" : password;
            org.json.JSONObject ki = new org.json.JSONObject();
            ki.put("alias", ALIAS);
            ki.put("password", pw.isEmpty() ? DEFAULT_PASSWORD : pw);
            if (!pw.isEmpty()) ki.put("storePassword", pw);
            File fresh2 = new File(base, "morphe_key.json.new");
            Files.write(fresh2.toPath(), ki.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean hadKs = keystore.isFile(), hadInfo = info.isFile();
            try {
                if (hadKs) Files.copy(keystore.toPath(), oldKs.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (hadInfo) Files.copy(info.toPath(), oldInfo.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(fresh.toPath(), keystore.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(fresh2.toPath(), info.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // put the old pair back
                try { if (hadKs) Files.copy(oldKs.toPath(), keystore.toPath(), StandardCopyOption.REPLACE_EXISTING); else keystore.delete(); } catch (IOException ignored) {}
                try { if (hadInfo) Files.copy(oldInfo.toPath(), info.toPath(), StandardCopyOption.REPLACE_EXISTING); else info.delete(); } catch (IOException ignored) {}
                fresh2.delete();
                return "The key could not be saved (" + (e.getMessage() == null ? "write failed" : e.getMessage()) + "). The old key was kept.";
            }
            return null;
        } catch (Exception e) {
            return "The key could not be imported (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "). Nothing was changed.";
        } finally {
            fresh.delete();
            new File(base, "morphe_key.json.new").delete();
        }
    }
}
