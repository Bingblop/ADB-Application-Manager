package com.bloatware.bingblop;

import java.util.HashMap;
import java.util.Map;

/** The GitHub token and the VirusTotal key: sealed in the vault, old plain copies moved or deleted, never written in the clear. */
public class SecretSettingsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static class FakeVault implements SecretSettings.Vault {
    final Map<String, String> m = new HashMap<>();
    boolean refuse;
    public String get(String id) { return m.get(id); }
    public void put(String id, String s) throws Exception { if (refuse) throw new Exception("keystore unavailable"); m.put(id, s); }
    public void remove(String id) { m.remove(id); }
  }
  static class FakePlain implements SecretSettings.Plain {
    final Map<String, String> m = new HashMap<>();
    public String get(String name) { return m.get(name); }
    public void remove(String name) { m.remove(name); }
  }

  public static void main(String[] args) {
    check("vt_key, its tested copy and the older vt_api_key are secret", SecretSettings.isSecretKv("vt_key") && SecretSettings.isSecretKv("vt_key_ok") && SecretSettings.isSecretKv("vt_api_key"));
    check("other page settings are not", !SecretSettings.isSecretKv("theme") && !SecretSettings.isSecretKv("vt_keyx") && !SecretSettings.isSecretKv("") && !SecretSettings.isSecretKv(null));
    check("the old plain name keeps its prefix", "kv_vt_key".equals(SecretSettings.kvName("vt_key")));

    FakeVault v = new FakeVault(); FakePlain p = new FakePlain();
    SecretSettings s = new SecretSettings(v, p);
    check("nothing saved reads as empty", s.get("github_token").isEmpty() && !s.has("github_token"));

    check("put stores in the vault", s.put("github_token", "  ghp_abc  ") && "ghp_abc".equals(v.m.get("github_token")));
    check("put leaves nothing in the plain store", p.m.isEmpty());
    check("get returns it", "ghp_abc".equals(s.get("github_token")) && s.has("github_token"));

    p.m.put("github_token", "ghp_old");
    check("a plain copy next to a vault value is deleted, the vault value wins", "ghp_abc".equals(s.get("github_token")) && !p.m.containsKey("github_token"));

    v = new FakeVault(); p = new FakePlain(); s = new SecretSettings(v, p);
    p.m.put("kv_vt_key", "vtsecret");
    check("an old plain value is returned", "vtsecret".equals(s.get("kv_vt_key")));
    check("... moved into the vault", "vtsecret".equals(v.m.get("kv_vt_key")));
    check("... and removed from the plain store", !p.m.containsKey("kv_vt_key"));
    check("the second read comes from the vault", "vtsecret".equals(s.get("kv_vt_key")));

    v = new FakeVault(); p = new FakePlain(); s = new SecretSettings(v, p); v.refuse = true;
    p.m.put("github_token", "ghp_keep");
    check("if the vault refuses the move the old value is still returned", "ghp_keep".equals(s.get("github_token")));
    check("... and stays where it was (nothing lost)", "ghp_keep".equals(p.m.get("github_token")) && v.m.isEmpty());

    check("if the vault refuses a new value put says so", !s.put("github_token", "ghp_new"));
    check("... the new value is not written in the clear", !"ghp_new".equals(p.m.get("github_token")) && v.m.isEmpty());
    check("... and the stale plain copy is gone, so the old secret is not silently kept", !p.m.containsKey("github_token"));

    v = new FakeVault(); p = new FakePlain(); s = new SecretSettings(v, p);
    s.put("github_token", "ghp_sealed");
    v.refuse = true;
    check("an older sealed value is dropped when the vault refuses the new one", !s.put("github_token", "ghp_new") && s.get("github_token").isEmpty() && !s.has("github_token") && v.m.isEmpty());

    // one reader moving an old plain value while another thread enters a new one: the new one has to win
    final FakeVault cv = new FakeVault(); final FakePlain cp = new FakePlain(); final SecretSettings cs = new SecretSettings(cv, cp);
    try {
      for (int round = 0; round < 200; round++) {
        cv.m.clear(); cp.m.clear(); cp.m.put("github_token", "old");
        Thread w = new Thread(new Runnable() { public void run() { cs.put("github_token", "new"); } });
        Thread r = new Thread(new Runnable() { public void run() { cs.get("github_token"); } });
        r.start(); w.start(); r.join(); w.join();
        if (!"new".equals(cs.get("github_token"))) { check("a value entered while an old one is being moved wins (round " + round + ")", false); break; }
      }
      check("a value entered while an old one is being moved wins (200 rounds)", "new".equals(cs.get("github_token")));
    } catch (InterruptedException e) { check("interrupted", false); }

    v = new FakeVault(); p = new FakePlain(); s = new SecretSettings(v, p);
    s.put("kv_vt_key", "k1");
    check("an empty value clears the secret", s.put("kv_vt_key", "") && !s.has("kv_vt_key") && v.m.isEmpty());
    check("null and blanks clear it too", s.put("kv_vt_key", "k2") && s.put("kv_vt_key", null) && !s.has("kv_vt_key"));
    check("blanks clear it", s.put("kv_vt_key", "k3") && s.put("kv_vt_key", "   ") && !s.has("kv_vt_key"));
    v.refuse = true;
    check("clearing works even when the vault refuses new values", s.put("kv_vt_key", ""));

    System.out.println(fails == 0 ? "PASS SecretSettingsTest: " + n + " checks" : "FAILED SecretSettingsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
