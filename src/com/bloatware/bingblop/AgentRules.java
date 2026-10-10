package com.bloatware.bingblop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The Terminal's coding agents: which address each provider's key may be sent to, how it is sent, how a key is tested, and how a
 * key is sealed for storage. Pure Java (no Android classes), unit-tested off-device.
 *
 * <p>API keys never travel back to the page: the page names a provider ("claude") and the app adds that provider's key on its way
 * out, and only to that provider's own host (or, for a server the user runs, such as Jan or Ollama, only to the address saved
 * together with the key). So even a page script that misbehaved could not send a key anywhere else.
 */
public final class AgentRules {

    private AgentRules() {
    }

    public static final class Provider {
        public final String id;
        /** The only host its key goes to; null = the user's own server (the base URL saved with the key). */
        public final String host;
        public final String header;
        public final String prefix;
        /** A cheap call that needs a valid key: a full https URL, or a path after the user's base URL. */
        public final String test;
        /** Headers the provider requires on every call. */
        public final String[][] extra;
        /** The environment variable its official command-line tool reads the key from, or null. */
        public final String env;

        Provider(String id, String host, String header, String prefix, String test, String[][] extra, String env) {
            this.id = id;
            this.host = host;
            this.header = header;
            this.prefix = prefix;
            this.test = test;
            this.extra = extra == null ? new String[0][] : extra;
            this.env = env;
        }

        public boolean ownServer() {
            return host == null;
        }
    }

    private static final Provider[] PROVIDERS = {
            new Provider("claude", "api.anthropic.com", "x-api-key", "", "https://api.anthropic.com/v1/models?limit=100",
                    new String[][]{{"anthropic-version", "2023-06-01"}}, "ANTHROPIC_API_KEY"),
            new Provider("chatgpt", "api.openai.com", "Authorization", "Bearer ", "https://api.openai.com/v1/models", null, "OPENAI_API_KEY"),
            new Provider("gemini", "generativelanguage.googleapis.com", "x-goog-api-key", "",
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", null, "GEMINI_API_KEY"),
            new Provider("cursor", "api.cursor.com", "Authorization", "Bearer ", "https://api.cursor.com/v1/me", null, "CURSOR_API_KEY"),
            new Provider("copilot", "api.github.com", "Authorization", "Bearer ", "https://api.github.com/user",
                    new String[][]{{"Accept", "application/vnd.github+json"}, {"X-GitHub-Api-Version", "2022-11-28"}}, "COPILOT_GITHUB_TOKEN"),
            new Provider("perplexity", "api.perplexity.ai", "Authorization", "Bearer ", "https://api.perplexity.ai/v1/models", null, "PERPLEXITY_API_KEY"),
            new Provider("grok", "api.x.ai", "Authorization", "Bearer ", "https://api.x.ai/v1/models", null, "XAI_API_KEY"),
            new Provider("muse", "api.llama.com", "Authorization", "Bearer ", "https://api.llama.com/v1/models", null, "LLAMA_API_KEY"),
            new Provider("deepseek", "api.deepseek.com", "Authorization", "Bearer ", "https://api.deepseek.com/v1/models", null, "DEEPSEEK_API_KEY"),
            new Provider("crawl4ai", "api.crawl4ai.com", "Authorization", "Bearer ", "https://api.crawl4ai.com/search?q=android", null, "CRAWL4AI_KEY"),
            new Provider("browseruse", "api.browser-use.com", "X-Browser-Use-API-Key", "", "https://api.browser-use.com/api/v2/billing/account", null, "BROWSER_USE_API_KEY"),
            new Provider("jan", null, "Authorization", "Bearer ", "/models", null, null),
            new Provider("anythingllm", null, "Authorization", "Bearer ", "/models", null, null),
            new Provider("ollama", null, "Authorization", "Bearer ", "/models", null, null),
            new Provider("ownserver", null, "Authorization", "Bearer ", "/models", null, null),
    };

    public static Provider find(String id) {
        if (id == null) return null;
        for (Provider p : PROVIDERS) if (p.id.equals(id)) return p;
        return null;
    }

    /** Headers the page may not set itself: the app adds credentials, nothing else does. */
    public static boolean reservedHeader(String name) {
        if (name == null) return true;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() || n.equals("authorization") || n.equals("x-api-key") || n.equals("x-goog-api-key") || n.equals("cookie")
                || n.equals("host") || n.equals("proxy-authorization") || n.startsWith("sec-") || n.contains("\n") || n.contains("\r");
    }

    /**
     * A base URL for the user's own server, cleaned up: http or https, a host, no user name or password, no query, no trailing
     * slash. Throws with a readable message when it is not usable.
     */
    public static String normalizeBase(String base) {
        if (base == null || base.trim().isEmpty()) throw new IllegalArgumentException("Enter the server address, like http://192.168.1.20:1337/v1");
        String b = base.trim();
        if (!b.contains("://")) b = "http://" + b;          // "192.168.1.20:1337/v1" -> http://; any other scheme is refused below
        URI u;
        try {
            u = new URI(b);
        } catch (Exception e) {
            throw new IllegalArgumentException("That is not a web address");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("The address must start with http:// or https://");
        if (u.getHost() == null || u.getHost().isEmpty()) throw new IllegalArgumentException("The address has no host name");
        if (u.getRawUserInfo() != null) throw new IllegalArgumentException("Leave the user name and password out of the address");
        if (u.getRawQuery() != null || u.getRawFragment() != null) throw new IllegalArgumentException("Leave ? and # parts out of the address");
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
    }

    /** scheme://host:port with the default port filled in, or "" for something that is not an http(s) URL. */
    public static String origin(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
            if (u.getHost() == null || u.getRawUserInfo() != null) return "";
            int port = u.getPort() > 0 ? u.getPort() : (scheme.equals("https") ? 443 : 80);
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    /** May {@code url} carry provider {@code p}'s key? Its fixed host over https, or the saved server's own origin. */
    public static boolean allowed(Provider p, String savedBase, String url) {
        if (p == null || url == null) return false;
        String o = origin(url);
        if (o.isEmpty()) return false;
        if (p.ownServer()) return savedBase != null && !savedBase.isEmpty() && o.equals(origin(savedBase));
        return o.equals("https://" + p.host + ":443");
    }

    /** The URL that tests a key: the provider's own, or the path after the user's server address. */
    public static String testUrl(Provider p, String base) {
        if (!p.ownServer()) return p.test;
        return normalizeBase(base) + p.test;
    }

    /** How a saved key is shown: the start that names its kind and the last four, never the rest. */
    public static String hint(String secret) {
        if (secret == null) return "";
        String s = secret.trim();
        if (s.length() <= 10) return s.isEmpty() ? "" : "••••";
        int keep = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(sk-ant-|sk-proj-|sk_live_|sk-|bu_|pplx-|xai-|LLM\\||AIza|github_pat_|gh[pousr]_|crsr_|key_)").matcher(s);
        if (m.find()) keep = m.end();
        return s.substring(0, keep) + "…" + s.substring(s.length() - 4);
    }

    /** Masks anything in {@code text} that looks like an API key or token (before it is logged or sent to a model). */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(sk-ant-[A-Za-z0-9_\\-]{6})[A-Za-z0-9_\\-]{10,}", "$1…")
                .replaceAll("(sk-(?:proj-)?[A-Za-z0-9]{4})[A-Za-z0-9_\\-]{16,}", "$1…")
                .replaceAll("(AIza[0-9A-Za-z_\\-]{4})[0-9A-Za-z_\\-]{20,}", "$1…")
                .replaceAll("(github_pat_[A-Za-z0-9]{4})[A-Za-z0-9_]{20,}", "$1…")
                .replaceAll("(gh[pousr]_[A-Za-z0-9]{4})[A-Za-z0-9]{20,}", "$1…")
                .replaceAll("(crsr_[A-Za-z0-9]{4})[A-Za-z0-9_\\-]{16,}", "$1…")
                .replaceAll("\\b(key_[A-Za-z0-9]{4})[A-Za-z0-9_\\-]{24,}", "$1…")
                .replaceAll("(sk_live_[A-Za-z0-9]{4})[A-Za-z0-9_\\-]{12,}", "$1…")
                .replaceAll("\\b(bu_[A-Za-z0-9]{4})[A-Za-z0-9_\\-]{16,}", "$1…")
                .replaceAll("(pplx-[A-Za-z0-9]{4})[A-Za-z0-9]{16,}", "$1…")
                .replaceAll("(xai-[A-Za-z0-9]{4})[A-Za-z0-9]{16,}", "$1…")
                .replaceAll("(LLM\\|[A-Za-z0-9]{4})[A-Za-z0-9|_\\-]{10,}", "$1…");
    }

    /** A key is visible ASCII only, as an HTTP header must be: no spaces, no control characters, at most 4096 characters. Empty is allowed
     *  (a server that needs no key). A key with a control character would come back in the error of the HTTP library, which quotes the header. */
    public static boolean keyLooksValid(String k) {
        if (k == null) return true;
        if (k.length() > 4096) return false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }

    /** What an error may carry back to the page: {@code secret} itself and anything else that looks like a key are masked. */
    public static String scrub(String text, String secret) {
        if (text == null || text.isEmpty()) return "";
        String t = text;
        if (secret != null && secret.length() >= 4) t = t.replace(secret, "…");
        return redact(t);
    }

    // ---------------------------------------------------------------------------------------------
    // Sealing: AES-256-GCM with a key that never leaves the Android Keystore; the provider id is bound in as
    // associated data, so a sealed Claude key copied into the OpenAI slot does not open.
    // ---------------------------------------------------------------------------------------------

    public static String seal(SecretKey key, String provider, String secret) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);           // the cipher picks a fresh random IV (the Keystore insists on that)
        c.updateAAD(provider.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder e = Base64.getEncoder();
        return "v1:" + e.encodeToString(c.getIV()) + ":" + e.encodeToString(ct);
    }

    public static String open(SecretKey key, String provider, String sealed) throws GeneralSecurityException {
        if (sealed == null || !sealed.startsWith("v1:")) throw new GeneralSecurityException("not a sealed value");
        String[] f = sealed.split(":", 3);
        if (f.length != 3) throw new GeneralSecurityException("damaged sealed value");
        Base64.Decoder d = Base64.getDecoder();
        byte[] iv;
        byte[] ct;
        try {
            iv = d.decode(f[1]);
            ct = d.decode(f[2]);
        } catch (IllegalArgumentException bad) {
            throw new GeneralSecurityException("damaged sealed value");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(provider.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }
}
