package com.bloatware.bingblop;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Where the Terminal's coding agents keep their API keys: each key sealed with AES-256-GCM under a key that lives in the Android
 * Keystore (it cannot be read out of the phone, and app backups are off), stored in this app's private settings. The page only
 * ever gets a hint like {@code sk-ant-…a1b2}. See {@link AgentRules}.
 */
final class AgentVault {

    private static final String ALIAS = "adbmgr_agent_vault_v1";
    private final SharedPreferences prefs;

    AgentVault(Context ctx) {
        prefs = ctx.getSharedPreferences("agent_vault", Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    synchronized void put(String provider, String secret) throws Exception {
        SharedPreferences.Editor e = prefs.edit();
        if (secret == null || secret.trim().isEmpty()) {
            e.remove("k_" + provider).remove("h_" + provider);
        } else {
            e.putString("k_" + provider, AgentRules.seal(key(), provider, secret.trim())).putString("h_" + provider, AgentRules.hint(secret));
        }
        e.putLong("t_" + provider, System.currentTimeMillis()).apply();
    }

    /** The key, or null when none is saved or it can no longer be opened (the Keystore key was reset). */
    synchronized String get(String provider) {
        String sealed = prefs.getString("k_" + provider, null);
        if (sealed == null) return null;
        try {
            return AgentRules.open(key(), provider, sealed);
        } catch (Throwable t) {
            return null;
        }
    }

    synchronized void setBase(String provider, String base) {
        if (base == null || base.isEmpty()) prefs.edit().remove("b_" + provider).apply();
        else prefs.edit().putString("b_" + provider, base).putLong("t_" + provider, System.currentTimeMillis()).apply();
    }

    synchronized String base(String provider) {
        return prefs.getString("b_" + provider, "");
    }

    synchronized void remove(String provider) {
        prefs.edit().remove("k_" + provider).remove("h_" + provider).remove("b_" + provider).remove("t_" + provider).apply();
    }

    /** {provider: {key: bool, hint, base, savedAt, readable}} for the page: never the keys themselves. */
    synchronized JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            for (String id : new String[]{"claude", "chatgpt", "gemini", "cursor", "copilot", "perplexity", "grok", "muse", "deepseek", "crawl4ai", "exa", "browseruse", "jan", "anythingllm", "ollama", "ownserver"}) {
                boolean hasKey = prefs.contains("k_" + id);
                String base = prefs.getString("b_" + id, "");
                if (!hasKey && base.isEmpty()) continue;
                JSONObject p = new JSONObject();
                p.put("key", hasKey);
                p.put("hint", prefs.getString("h_" + id, ""));
                p.put("base", base);
                p.put("savedAt", prefs.getLong("t_" + id, 0));
                if (hasKey) p.put("readable", get(id) != null);
                o.put(id, p);
            }
        } catch (Exception ignored) {
        }
        return o;
    }
}
