package com.bloatware.bingblop;

/**
 * Where small secrets that are not coding-agent keys live: the GitHub token and the VirusTotal key (and the copy of it the page keeps to
 * remember "this key was tested"). They used to sit in plain SharedPreferences; now they are sealed in {@link AgentVault} (Android Keystore).
 * No Android classes here, so the rules can be tested: the backing store is an interface.
 *
 * <ul>
 * <li>{@link #get}: the vault first; if there is only an old plain value, move it into the vault and delete the plain copy (a value that
 * cannot be moved is still returned, and stays where it was, so nothing is lost).</li>
 * <li>{@link #put}: seal the value and delete any plain copy. If the vault refuses, the plain copy and any older sealed value are deleted and
 * {@code false} is returned (fail closed: the user has to enter the secret again, it is never written in the clear).</li>
 * <li>{@link #get}, {@link #put} and {@link #has} share one lock: the bridge thread and the background executors both call them, and a reader
 * that is moving an old plain value must not overwrite a value entered meanwhile.</li>
 * </ul>
 */
final class SecretSettings {

    /** A place for secrets that does not keep them in the clear (the Keystore vault). */
    interface Vault {
        String get(String id);
        void put(String id, String secret) throws Exception;
        void remove(String id);
    }

    /** The old plain place (SharedPreferences). */
    interface Plain {
        String get(String name);
        void remove(String name);
    }

    static final String GITHUB_TOKEN = "github_token";
    private static final String KV_PREFIX = "kv_";

    private final Vault vault;
    private final Plain plain;

    SecretSettings(Vault vault, Plain plain) {
        this.vault = vault;
        this.plain = plain;
    }

    /** True for the small key/value settings of the page that hold a secret and so go to the vault. */
    static boolean isSecretKv(String key) {
        return "vt_key".equals(key) || "vt_key_ok".equals(key) || "vt_api_key".equals(key);
    }

    /** The name a page setting has in the vault and in the old plain store. */
    static String kvName(String key) {
        return KV_PREFIX + key;
    }

    synchronized String get(String name) {
        String v = vault.get(name);
        if (v != null && !v.isEmpty()) {
            plain.remove(name);
            return v;
        }
        String old = plain.get(name);
        if (old == null || old.isEmpty()) return "";
        try {
            vault.put(name, old);
            plain.remove(name);
        } catch (Throwable t) {
            // the vault will not take it: keep the old value usable rather than lose the user's secret
        }
        return old;
    }

    /** @return false when the vault would not take a non-empty value (the secret was not stored anywhere). */
    synchronized boolean put(String name, String value) {
        String v = value == null ? "" : value.trim();
        plain.remove(name);
        if (v.isEmpty()) {
            vault.remove(name);
            return true;
        }
        try {
            vault.put(name, v);
            return true;
        } catch (Throwable t) {
            vault.remove(name);      // fail closed: an older sealed value must not keep answering for the one that was just entered
            return false;
        }
    }

    synchronized boolean has(String name) {
        return !get(name).isEmpty();
    }
}
