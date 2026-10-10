package com.bloatware.bingblop;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Coding agents' key rules: which address a provider's key may go to (and every way a URL could pretend to be that address),
 * the user's own server addresses, key hints and redaction, the headers the page may not set, and the sealing of keys
 * (AES-256-GCM with the provider bound in), here with a software key in place of the Android Keystore one.
 */
public class AgentRulesTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static boolean throwsIAE(String base) {
    try { AgentRules.normalizeBase(base); return false; } catch (IllegalArgumentException e) { return e.getMessage() != null && !e.getMessage().isEmpty(); }
  }

  public static void main(String[] args) throws Exception {
    AgentRules.Provider claude = AgentRules.find("claude");
    AgentRules.Provider openai = AgentRules.find("chatgpt");
    AgentRules.Provider gemini = AgentRules.find("gemini");
    AgentRules.Provider cursor = AgentRules.find("cursor");
    AgentRules.Provider copilot = AgentRules.find("copilot");
    AgentRules.Provider jan = AgentRules.find("jan");
    AgentRules.Provider perplexity = AgentRules.find("perplexity");
    AgentRules.Provider grok = AgentRules.find("grok");
    AgentRules.Provider muse = AgentRules.find("muse");
    AgentRules.Provider deepseek = AgentRules.find("deepseek");

    // ---------------------------------------------------------------- the provider table
    check("every key provider is known, with its host", "api.anthropic.com".equals(claude.host) && "api.openai.com".equals(openai.host)
        && "generativelanguage.googleapis.com".equals(gemini.host) && "api.cursor.com".equals(cursor.host) && "api.github.com".equals(copilot.host)
        && "api.perplexity.ai".equals(perplexity.host) && "api.x.ai".equals(grok.host) && "api.llama.com".equals(muse.host) && "api.deepseek.com".equals(deepseek.host));
    check("Perplexity: Bearer token, its own models endpoint as the test, and its key never leaves that host",
        "Authorization".equals(perplexity.header) && "Bearer ".equals(perplexity.prefix) && "PERPLEXITY_API_KEY".equals(perplexity.env)
        && perplexity.test.equals("https://api.perplexity.ai/v1/models")
        && AgentRules.allowed(perplexity, null, "https://api.perplexity.ai/v1/chat/completions")
        && !AgentRules.allowed(perplexity, null, "https://evil.example/v1/chat/completions"));
    check("a pplx- key's hint shows only the prefix and the last four", AgentRules.hint("pplx-0123456789abcdef0123456789abcdef").equals("pplx-…cdef"));
    check("redact masks a pplx- key in output", AgentRules.redact("Authorization: Bearer pplx-0123456789abcdef0123456789abcdef").equals("Authorization: Bearer pplx-0123…"));
    check("Grok (xAI): Bearer token to api.x.ai only, env var and hint/redact of an xai- key",
        "Authorization".equals(grok.header) && "Bearer ".equals(grok.prefix) && "XAI_API_KEY".equals(grok.env)
        && grok.test.equals("https://api.x.ai/v1/models")
        && AgentRules.allowed(grok, null, "https://api.x.ai/v1/chat/completions") && !AgentRules.allowed(grok, null, "https://evil.example/v1/chat/completions")
        && AgentRules.hint("xai-0123456789abcdef0123456789abcdef").equals("xai-…cdef")
        && AgentRules.redact("Authorization: Bearer xai-0123456789abcdef0123456789abcdef").equals("Authorization: Bearer xai-0123…"));
    check("Muse (Meta Llama API): Bearer token to api.llama.com only, env var and hint/redact of an LLM| key",
        "Authorization".equals(muse.header) && "Bearer ".equals(muse.prefix) && "LLAMA_API_KEY".equals(muse.env)
        && muse.test.equals("https://api.llama.com/v1/models")
        && AgentRules.allowed(muse, null, "https://api.llama.com/v1/chat/completions") && !AgentRules.allowed(muse, null, "https://evil.example/v1/chat/completions")
        && AgentRules.hint("LLM|0123456789|abcdefghijklmnopqrstuvwxyz").equals("LLM|…wxyz")
        && AgentRules.redact("Authorization: Bearer LLM|0123456789|abcdefghijklmnopqrstuvwxyz").equals("Authorization: Bearer LLM|0123…"));
    check("Deepseek: Bearer token to api.deepseek.com only, env var, and its sk- key already masked by the generic sk- pattern",
        "Authorization".equals(deepseek.header) && "Bearer ".equals(deepseek.prefix) && "DEEPSEEK_API_KEY".equals(deepseek.env)
        && deepseek.test.equals("https://api.deepseek.com/v1/models")
        && AgentRules.allowed(deepseek, null, "https://api.deepseek.com/v1/chat/completions") && !AgentRules.allowed(deepseek, null, "https://evil.example/v1/chat/completions")
        && AgentRules.hint("sk-0123456789abcdef0123456789abcdef").equals("sk-…cdef"));
    AgentRules.Provider exa = AgentRules.find("exa");
    check("Exa: x-api-key to api.exa.ai only, a key test that is one small POST search, no other provider has a test body",
        exa != null && "api.exa.ai".equals(exa.host) && "x-api-key".equals(exa.header) && "".equals(exa.prefix) && "EXA_API_KEY".equals(exa.env)
        && AgentRules.allowed(exa, null, "https://api.exa.ai/answer") && !AgentRules.allowed(exa, null, "https://evil.example/answer") && !AgentRules.allowed(exa, null, "http://api.exa.ai/answer")
        && AgentRules.testBody(exa) != null && AgentRules.testBody(exa).contains("\"query\"") && AgentRules.testBody(AgentRules.find("perplexity")) == null && AgentRules.testBody(AgentRules.find("crawl4ai")) == null);
    AgentRules.Provider crawl4ai = AgentRules.find("crawl4ai"), browseruse = AgentRules.find("browseruse");
    check("Crawl4AI: Bearer key to api.crawl4ai.com only, its sk_live_ key shown short and masked",
        crawl4ai != null && "api.crawl4ai.com".equals(crawl4ai.host) && "Authorization".equals(crawl4ai.header) && "Bearer ".equals(crawl4ai.prefix)
        && AgentRules.allowed(crawl4ai, null, "https://api.crawl4ai.com/answer?q=x") && !AgentRules.allowed(crawl4ai, null, "https://evil.example/answer")
        && !AgentRules.allowed(crawl4ai, null, "http://api.crawl4ai.com/answer")
        && AgentRules.hint("sk_" + "live_0123456789abcdef0123456789").equals("sk_live_…6789")
        && AgentRules.redact("Bearer sk_" + "live_0123456789abcdef0123456789").equals("Bearer sk_live_0123…"));
    check("Browser Use: X-Browser-Use-API-Key (no prefix) to api.browser-use.com only, its bu_ key masked",
        browseruse != null && "api.browser-use.com".equals(browseruse.host) && "X-Browser-Use-API-Key".equals(browseruse.header) && "".equals(browseruse.prefix)
        && browseruse.test.equals("https://api.browser-use.com/api/v2/billing/account")
        && AgentRules.allowed(browseruse, null, "https://api.browser-use.com/api/v2/tasks") && !AgentRules.allowed(browseruse, null, "https://evil.example/api/v2/tasks")
        && AgentRules.hint("bu_0123456789abcdef0123456789").equals("bu_…6789")
        && AgentRules.redact("key bu_0123456789abcdef0123456789").equals("key bu_0123…"));
    check("unknown providers are not", AgentRules.find("evil") == null && AgentRules.find(null) == null);
    check("Claude: x-api-key plus the API version header", "x-api-key".equals(claude.header) && "".equals(claude.prefix)
        && claude.extra.length == 1 && "anthropic-version".equals(claude.extra[0][0]) && "2023-06-01".equals(claude.extra[0][1]));
    check("OpenAI, Cursor, GitHub: Bearer tokens", "Authorization".equals(openai.header) && "Bearer ".equals(openai.prefix)
        && "Bearer ".equals(cursor.prefix) && "Bearer ".equals(copilot.prefix));
    check("Gemini: x-goog-api-key (not a URL parameter, so it never lands in logs)", "x-goog-api-key".equals(gemini.header));
    check("tests are the cheap read calls", claude.test.equals("https://api.anthropic.com/v1/models?limit=100") && openai.test.equals("https://api.openai.com/v1/models")
        && gemini.test.startsWith("https://generativelanguage.googleapis.com/v1beta/models") && cursor.test.equals("https://api.cursor.com/v1/me")
        && copilot.test.equals("https://api.github.com/user"));
    check("each command-line tool's own variable", "ANTHROPIC_API_KEY".equals(claude.env) && "OPENAI_API_KEY".equals(openai.env) && "GEMINI_API_KEY".equals(gemini.env)
        && "CURSOR_API_KEY".equals(cursor.env) && "COPILOT_GITHUB_TOKEN".equals(copilot.env) && jan.env == null);
    check("own-server providers have no fixed host", jan.ownServer() && AgentRules.find("anythingllm").ownServer() && AgentRules.find("ollama").ownServer() && AgentRules.find("ownserver").ownServer() && !claude.ownServer());
    check("Own server: its key goes only to the saved address (a LAN address over http is fine, another one is not)",
        AgentRules.allowed(AgentRules.find("ownserver"), "http://192.168.1.20:1234/v1", "http://192.168.1.20:1234/v1/chat/completions")
        && !AgentRules.allowed(AgentRules.find("ownserver"), "http://192.168.1.20:1234/v1", "http://192.168.1.21:1234/v1/chat/completions")
        && !AgentRules.allowed(AgentRules.find("ownserver"), null, "http://192.168.1.20:1234/v1/models")
        && AgentRules.testUrl(AgentRules.find("ownserver"), "192.168.1.20:1234/v1/").equals("http://192.168.1.20:1234/v1/models"));

    // ---------------------------------------------------------------- where a key may go
    check("Claude key: to api.anthropic.com over https", AgentRules.allowed(claude, "", "https://api.anthropic.com/v1/messages"));
    check("... with the port spelled out too", AgentRules.allowed(claude, "", "https://api.anthropic.com:443/v1/messages"));
    check("... host case does not matter", AgentRules.allowed(claude, "", "https://API.Anthropic.com/v1/models"));
    check("not over plain http", !AgentRules.allowed(claude, "", "http://api.anthropic.com/v1/messages"));
    check("not to a look-alike host", !AgentRules.allowed(claude, "", "https://api.anthropic.com.evil.example/v1/messages"));
    check("not to a sub-domain", !AgentRules.allowed(claude, "", "https://x.api.anthropic.com/v1/messages"));
    check("not when the host only appears in the query", !AgentRules.allowed(claude, "", "https://evil.example/?u=https://api.anthropic.com"));
    check("not with user info pointing elsewhere", !AgentRules.allowed(claude, "", "https://api.anthropic.com@evil.example/v1"));
    check("not on another port", !AgentRules.allowed(claude, "", "https://api.anthropic.com:8443/v1/messages"));
    check("not another provider's host", !AgentRules.allowed(claude, "", "https://api.openai.com/v1/chat/completions"));
    check("not a non-web scheme", !AgentRules.allowed(claude, "", "file:///etc/passwd") && !AgentRules.allowed(claude, "", "javascript:alert(1)"));
    check("not garbage", !AgentRules.allowed(claude, "", "::::") && !AgentRules.allowed(claude, "", null) && !AgentRules.allowed(null, "", "https://api.anthropic.com/"));
    check("Gemini key: only Google's API host", AgentRules.allowed(gemini, "", "https://generativelanguage.googleapis.com/v1beta/models/x:generateContent")
        && !AgentRules.allowed(gemini, "", "https://googleapis.com/"));

    String base = "http://192.168.1.20:1337/v1";
    check("own server: the saved address", AgentRules.allowed(jan, base, "http://192.168.1.20:1337/v1/chat/completions"));
    check("own server: the same origin, any path", AgentRules.allowed(jan, base, "http://192.168.1.20:1337/api/other"));
    check("own server: not another port", !AgentRules.allowed(jan, base, "http://192.168.1.20:1338/v1/chat/completions"));
    check("own server: not another scheme", !AgentRules.allowed(jan, base, "https://192.168.1.20:1337/v1/chat/completions"));
    check("own server: not another host", !AgentRules.allowed(jan, base, "http://192.168.1.21:1337/v1/chat/completions"));
    check("own server: nothing before an address was saved", !AgentRules.allowed(jan, "", "http://192.168.1.20:1337/v1/models") && !AgentRules.allowed(jan, null, "http://127.0.0.1:1337/v1/models"));
    check("origin fills default ports", AgentRules.origin("https://a.example/x").equals("https://a.example:443") && AgentRules.origin("http://a.example").equals("http://a.example:80"));

    // ---------------------------------------------------------------- the user's server address
    check("a bare host:port gets http:// and loses the trailing slash", "http://192.168.1.20:1337/v1".equals(AgentRules.normalizeBase(" 192.168.1.20:1337/v1/ ")));
    check("scheme and host are lower-cased, the path kept", "http://example.com:3001/api/v1/openai".equals(AgentRules.normalizeBase("HTTP://Example.COM:3001/api/v1/openai")));
    check("https stays https", "https://jan.example".equals(AgentRules.normalizeBase("https://jan.example/")));
    check("empty is refused with a hint", throwsIAE("") && throwsIAE("   ") && throwsIAE(null));
    check("ftp is refused", throwsIAE("ftp://example.com"));
    check("a user name / password is refused", throwsIAE("http://user:pw@example.com:1337"));
    check("query and fragment are refused", throwsIAE("http://example.com/v1?key=1") && throwsIAE("http://example.com/v1#x"));
    check("no host is refused", throwsIAE("http:///v1"));
    check("the test URL of an own server is its models list", "http://h:1337/v1/models".equals(AgentRules.testUrl(jan, "h:1337/v1/")));
    check("the test URL of a fixed provider ignores any base", claude.test.equals(AgentRules.testUrl(claude, "http://evil.example")));

    // ---------------------------------------------------------------- hints and redaction
    check("hint keeps the kind and the last four", "sk-ant-…WXYZ".equals(AgentRules.hint("sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWXYZ")));
    check("hint for an OpenAI project key", "sk-proj-…7890".equals(AgentRules.hint("sk-proj-abcdefghijklmnopqrstuv1234567890")));
    check("hint for a Google key", "AIza…XYZ9".equals(AgentRules.hint("AIzaSyD1234567890abcdefghXYZ9")));
    check("hint for a GitHub token", "github_pat_…abcd".equals(AgentRules.hint("github_pat_11AAAAAAA0123456789_zzzzzzzzzzzzabcd")));
    check("hint for an unknown kind shows only the end", "…6789".equals(AgentRules.hint("abcdefghijklmnop0123456789")));
    check("short or empty: nothing useful leaks", "••••".equals(AgentRules.hint("abc123")) && "".equals(AgentRules.hint("")) && "".equals(AgentRules.hint(null)));
    String leak = "ANTHROPIC_API_KEY=sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345 and AIzaSyD1234567890abcdefghijklmnopq and ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 and github_pat_11AAAAAAA0123456789_zzzzzzzzzzzzzzzzzzz and sk-proj-abcdefghijklmnopqrstuvwxyz123456 crsr_abcdefghijklmnopqrstuvwxyz";
    String red = AgentRules.redact(leak);
    check("redact masks every kind of key", !red.contains("UVWXYZ012345") && !red.contains("ijklmnopq ") && !red.contains("6789 ") && !red.contains("zzzzzzzzzzzzzzzzzzz")
        && !red.contains("qrstuvwxyz123456") && !red.contains("opqrstuvwxyz") && red.contains("sk-ant-api03-…") && red.contains("AIzaSyD1…"));
    check("redact leaves ordinary text alone", "ls -la /sdcard && echo sk- done".equals(AgentRules.redact("ls -la /sdcard && echo sk- done")) && AgentRules.redact(null) == null);
    // Cursor keys that start with key_ (as hint() knows them) are masked too, but not a word like api_key_name or a short key_id
    String ck = "key_" + "0123456789abcdef0123456789abcdef0123456789abcdef";
    check("redact masks a key_ Cursor key", AgentRules.redact("export CURSOR_API_KEY=" + ck).equals("export CURSOR_API_KEY=key_0123…"));
    check("redact leaves key_ words alone", "api_key_name_is_long_enough_to_look_like_one key_id".equals(AgentRules.redact("api_key_name_is_long_enough_to_look_like_one key_id")));

    // keyLooksValid: visible ASCII only (what an HTTP header carries); a control character would come back quoted in the HTTP library's error
    check("keyLooksValid: a normal key", AgentRules.keyLooksValid("sk-ant-api03-abc_DEF-123"));
    check("keyLooksValid: empty is allowed (a server without a key)", AgentRules.keyLooksValid("") && AgentRules.keyLooksValid(null));
    check("keyLooksValid: no space, tab, line break", !AgentRules.keyLooksValid("sk-a b") && !AgentRules.keyLooksValid("sk-a\tb") && !AgentRules.keyLooksValid("sk-a\nb") && !AgentRules.keyLooksValid("sk-a\rb"));
    check("keyLooksValid: no control character (BEL, DEL)", !AgentRules.keyLooksValid("sk-a\u0007b") && !AgentRules.keyLooksValid("sk-a\u007fb"));
    check("keyLooksValid: no character outside ASCII", !AgentRules.keyLooksValid("sk-caf\u00e9") && !AgentRules.keyLooksValid("sk-\u200bzero-width"));
    StringBuilder longKey = new StringBuilder(); for (int i = 0; i < 4097; i++) longKey.append('a');
    check("keyLooksValid: at most 4096 characters", !AgentRules.keyLooksValid(longKey.toString()) && AgentRules.keyLooksValid(longKey.substring(1)));

    // scrub: the key itself and anything key-like are masked in an error before it goes back to the page
    String tok = "my-own-server-token-1234567890";
    String err = "Unexpected char 0x07 at 12 in Authorization value: Bearer " + tok + " (and sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV)";
    String sc = AgentRules.scrub(err, tok);
    check("scrub masks the secret itself (" + sc + ")", sc.indexOf(tok) < 0 && sc.contains("Bearer …"));
    check("scrub masks other key-like text", sc.indexOf("ABCDEFGHIJKLMNOPQRSTUV") < 0);
    check("scrub of nothing is empty", "".equals(AgentRules.scrub(null, tok)) && "".equals(AgentRules.scrub("", tok)));
    check("scrub ignores a too-short secret", "abc abc".equals(AgentRules.scrub("abc abc", "abc")));

    // ---------------------------------------------------------------- headers the page may not set
    check("credential headers are the app's alone (any case)", AgentRules.reservedHeader("Authorization") && AgentRules.reservedHeader("x-api-key")
        && AgentRules.reservedHeader("X-Goog-Api-Key") && AgentRules.reservedHeader("COOKIE") && AgentRules.reservedHeader("Host") && AgentRules.reservedHeader("Proxy-Authorization"));
    check("header injection and empty names are refused", AgentRules.reservedHeader("x\nevil") && AgentRules.reservedHeader("") && AgentRules.reservedHeader(null) && AgentRules.reservedHeader("sec-fetch-mode"));
    check("ordinary headers are fine", !AgentRules.reservedHeader("Content-Type") && !AgentRules.reservedHeader("anthropic-version") && !AgentRules.reservedHeader("Accept"));

    // ---------------------------------------------------------------- sealing
    KeyGenerator g = KeyGenerator.getInstance("AES");
    g.init(256);
    SecretKey k = g.generateKey();
    String secret = "sk-ant-api03-secret-ünïcode-✓";
    String sealed = AgentRules.seal(k, "claude", secret);
    check("sealed form is v1:iv:ciphertext and does not contain the key", sealed.startsWith("v1:") && sealed.split(":").length == 3 && !sealed.contains("secret"));
    check("opens again with the same provider", secret.equals(AgentRules.open(k, "claude", sealed)));
    check("the IV is 12 bytes, fresh each time", Base64.getDecoder().decode(sealed.split(":")[1]).length == 12 && !sealed.equals(AgentRules.seal(k, "claude", secret)));
    boolean wrongSlot = false;
    try { AgentRules.open(k, "chatgpt", sealed); } catch (java.security.GeneralSecurityException e) { wrongSlot = true; }
    check("a Claude key copied into the OpenAI slot does not open", wrongSlot);
    String[] f = sealed.split(":");
    byte[] ct = Base64.getDecoder().decode(f[2]);
    ct[0] ^= 1;
    boolean tampered = false;
    try { AgentRules.open(k, "claude", f[0] + ":" + f[1] + ":" + Base64.getEncoder().encodeToString(ct)); } catch (java.security.GeneralSecurityException e) { tampered = true; }
    check("a changed byte is detected", tampered);
    SecretKey other = g.generateKey();
    boolean otherKey = false;
    try { AgentRules.open(other, "claude", sealed); } catch (java.security.GeneralSecurityException e) { otherKey = true; }
    check("another key does not open it (a reset Keystore)", otherKey);
    boolean junk = true;
    for (String bad : new String[]{null, "", "plain", "v2:a:b", "v1:only", "v1:!!!:###"}) {
      try { AgentRules.open(k, "claude", bad); junk = false; } catch (java.security.GeneralSecurityException e) { /* expected */ }
    }
    check("junk is refused, never decoded as a key", junk);

    System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }
}
