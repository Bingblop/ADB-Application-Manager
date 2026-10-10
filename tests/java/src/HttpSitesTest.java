package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps new network code on the safe path. Every place that opens a raw HttpURLConnection (instead of HttpSafe.open) is listed here with
 * how many it may have; each connection must have automatic redirects switched off on its own variable, because a redirect carries request
 * headers (keys, cookies) wherever the answer points. A new call site fails this test until it is either moved to HttpSafe.open or added
 * below with a reason.
 *
 * The scan works on the source with comments, string and character literals blanked out, and matches across any whitespace, so
 * {@code c.openConnection ()} or a call split over two lines is seen like the plain spelling.
 */
public class HttpSitesTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  /** file -> openConnection() calls allowed, and why the file is not using HttpSafe.open. */
  static final Map<String, Integer> ALLOWED = new HashMap<String, Integer>();
  static {
    ALLOWED.put("HttpSafe.java", 1);            // the safe opener itself
    ALLOWED.put("AiHttp.java", 1);              // streaming requests to the user's chosen AI endpoint: own host check for the key, no redirects
    ALLOWED.put("BrowserDownload.java", 1);     // resumable downloads with a cookie jar and Range, own hop loop
    ALLOWED.put("MorpheNet.java", 1);           // Morphe sources and bundles: own hop loop (HttpSafe.redirectAllowed), size cap
    ALLOWED.put("MorpheVirusTotal.java", 1);    // multipart upload to the address VirusTotal returns (host checked first)
    ALLOWED.put("UpdateManager.java", 1);       // reads the redirect target of /releases/latest without following it
    ALLOWED.put("VirusTotal.java", 2);          // GET and multipart POST to www.virustotal.com, redirects off
  }

  /** How far after an opening its own variable has to switch redirects off. */
  static final int WINDOW = 2500;

  /** The source with comments, string literals and char literals replaced by spaces (newlines kept). */
  static String blank(String s) {
    StringBuilder o = new StringBuilder(s.length());
    int i = 0, len = s.length();
    while (i < len) {
      char c = s.charAt(i);
      if (c == '/' && i + 1 < len && s.charAt(i + 1) == '/') {
        while (i < len && s.charAt(i) != '\n') { o.append(' '); i++; }
      } else if (c == '/' && i + 1 < len && s.charAt(i + 1) == '*') {
        o.append("  "); i += 2;
        while (i < len && !(s.charAt(i) == '*' && i + 1 < len && s.charAt(i + 1) == '/')) { o.append(s.charAt(i) == '\n' ? '\n' : ' '); i++; }
        if (i < len) { o.append("  "); i += 2; }
      } else if (c == '"' || c == '\'') {
        char q = c;
        o.append(' '); i++;
        while (i < len && s.charAt(i) != q) {
          if (s.charAt(i) == '\\' && i + 1 < len) { o.append("  "); i += 2; continue; }
          o.append(s.charAt(i) == '\n' ? '\n' : ' '); i++;
        }
        if (i < len) { o.append(' '); i++; }
      } else { o.append(c); i++; }
    }
    return o.toString();
  }

  static final Pattern OPEN = Pattern.compile("\\bopenConnection\\s*\\(");
  static final Pattern SETTER = Pattern.compile("\\bsetInstanceFollowRedirects\\s*\\(");
  static final Pattern LITERAL_FALSE = Pattern.compile("\\G\\s*false\\s*\\)");
  static final Pattern ASSIGN = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=\\s*[^=;{}]*$");

  /** Problems in one source file: the number of raw openings goes to {@code count[0]}. */
  static List<String> problems(String src, int[] count) {
    List<String> out = new ArrayList<String>();
    String t = blank(src);
    Matcher sm = SETTER.matcher(t);
    while (sm.find()) {
      if (!LITERAL_FALSE.matcher(t).region(sm.end(), t.length()).lookingAt()) { out.add("setInstanceFollowRedirects with an argument that is not the literal false (switches automatic redirects on, or cannot be checked)"); break; }
    }
    Matcher m = OPEN.matcher(t);
    int opens = 0;
    while (m.find()) {
      opens++;
      int stmtStart = Math.max(Math.max(t.lastIndexOf(';', m.start()), t.lastIndexOf('{', m.start())), t.lastIndexOf('}', m.start())) + 1;
      Matcher a = ASSIGN.matcher(t.substring(stmtStart, m.start()));
      if (!a.find()) { out.add("a raw connection that is not assigned to a variable, so its redirects cannot be checked"); continue; }
      String var = a.group(1);
      int stmtEnd = t.indexOf(';', m.end());
      if (stmtEnd < 0) stmtEnd = m.end();
      String after = t.substring(stmtEnd, Math.min(t.length(), stmtEnd + WINDOW));
      // this connection lives until the variable is given another one
      Matcher again = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*=[^=]").matcher(after);
      if (again.find()) after = after.substring(0, again.start());
      Matcher off = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.\\s*setInstanceFollowRedirects\\s*\\(\\s*false\\s*\\)").matcher(after);
      if (!off.find()) { out.add("the connection '" + var + "' does not turn automatic redirects off"); continue; }
      // ... and it has to happen before anything that connects (a redirect is followed while the answer is read)
      String before = after.substring(0, off.start());
      // the variable must not be handed on (as an argument) before the switch; a plain copy into another variable is followed
      if (Pattern.compile("[(,]\\s*" + Pattern.quote(var) + "\\s*[,)]").matcher(before).find()) { out.add("the connection '" + var + "' is passed on before it turns automatic redirects off"); continue; }
      StringBuilder names = new StringBuilder(Pattern.quote(var));
      Matcher al = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*=\\s*" + Pattern.quote(var) + "\\s*;").matcher(before);
      while (al.find()) names.append('|').append(Pattern.quote(al.group(1)));
      Matcher use = Pattern.compile("\\b(?:" + names + ")\\s*\\.\\s*(connect|getResponseCode|getResponseMessage|getInputStream|getOutputStream|getErrorStream|getHeaderField\\w*|getContent\\w*|getLastModified|getDate|getExpiration|getHeaderFields)\\s*\\(").matcher(after);
      if (use.find() && use.start() < off.start()) out.add("the connection '" + var + "' turns automatic redirects off only after it has connected");
    }
    count[0] = opens;
    return out;
  }

  static File srcDir() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop");
      if (f.isDirectory()) return f;
    }
    return null;
  }

  static boolean has(List<String> p, String part) {
    for (String s : p) if (s.contains(part)) return true;
    return false;
  }

  public static void main(String[] args) throws Exception {
    // the scanner itself
    int[] cnt = new int[1];
    String ok = "class A { void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); c.setInstanceFollowRedirects(false); } }";
    check("a connection with redirects off is fine", problems(ok, cnt).isEmpty() && cnt[0] == 1);
    check("odd spacing and a call split over lines are still seen as an opening", problems(ok.replace("openConnection()", "openConnection\n   ()"), cnt).isEmpty() && cnt[0] == 1
        && problems(ok.replace("openConnection();", "openConnection ();").replace("c.setInstanceFollowRedirects(false)", "c . setInstanceFollowRedirects ( false )"), cnt).isEmpty() && cnt[0] == 1);
    String noOff = ok.replace("c.setInstanceFollowRedirects(false);", "");
    check("an opening without redirects off is refused (also with spaces before the bracket)", has(problems(noOff, cnt), "does not turn") && has(problems(noOff.replace("openConnection()", "openConnection ()"), cnt), "does not turn") && cnt[0] == 1);
    check("redirects switched on are refused, however it is spelled", has(problems(ok + " class B { void g(java.net.HttpURLConnection c) { c.setInstanceFollowRedirects (true); } }", cnt), "switches automatic redirects on")
        && has(problems("class B { void g(java.net.HttpURLConnection c) { c.setInstanceFollowRedirects\n(\ntrue\n); } }", cnt), "switches automatic redirects on"));
    String two = "class A { void f(String u) throws Exception { java.net.HttpURLConnection a = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); java.net.HttpURLConnection b = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); a.setInstanceFollowRedirects(false); a.setInstanceFollowRedirects(false); } }";
    check("two connections: switching one off twice does not cover the other", has(problems(two, cnt), "'b' does not turn") && !has(problems(two, cnt), "'a' does not turn") && cnt[0] == 2);
    check("a connection that is not assigned to a variable is refused", has(problems("class A { Object f(String u) throws Exception { return new java.net.URL(u).openConnection(); } }", cnt), "not assigned"));
    check("text in comments and strings does not count", problems("class A { String s = \"x.openConnection()\"; /* y.openConnection(); */ // z.openConnection();\n void f() {} }", cnt).isEmpty() && cnt[0] == 0
        && problems("class A { String s = \"setInstanceFollowRedirects(true)\"; // c.setInstanceFollowRedirects(true)\n }", cnt).isEmpty());
    String late = "class A { void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); int code = c.getResponseCode(); c.setInstanceFollowRedirects(false); } }";
    check("switching redirects off after the connection was used is too late", has(problems(late, cnt), "only after it has connected"));
    String early = "class A { void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); c.setRequestMethod(\"GET\"); c.setConnectTimeout(5); c.setInstanceFollowRedirects(false); int code = c.getResponseCode(); } }";
    check("other setup calls before the switch are fine", problems(early, cnt).isEmpty());
    String reuse = "class A { void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); c.getResponseCode(); c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); c.setInstanceFollowRedirects(false); } }";
    check("a variable reused for a second connection does not cover the first", has(problems(reuse, cnt), "'c' does not turn") && cnt[0] == 2);
    check("the redirect switch with a non-literal argument is refused", has(problems(ok.replace("setInstanceFollowRedirects(false)", "setInstanceFollowRedirects(Boolean.TRUE)"), cnt), "not the literal false")
        && has(problems(ok.replace("setInstanceFollowRedirects(false)", "setInstanceFollowRedirects(flag)"), cnt), "not the literal false")
        && has(problems(ok + " class B { void g(java.net.HttpURLConnection c, boolean b) { c.setInstanceFollowRedirects ( !b ); } }", cnt), "not the literal false"));
    check("a literal false re-enabled by a later call is refused", has(problems(ok.replace("c.setInstanceFollowRedirects(false);", "c.setInstanceFollowRedirects(false); c.setInstanceFollowRedirects(Boolean.TRUE);"), cnt), "not the literal false"));
    String alias = "class A { Object o; void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); java.net.HttpURLConnection d = c; int code = d.getResponseCode(); c.setInstanceFollowRedirects(false); } }";
    check("connecting through a copy of the variable before the switch is refused", has(problems(alias, cnt), "only after it has connected"));
    String aliasOk = "class A { Object o; void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); o = c; c.setInstanceFollowRedirects(false); int code = c.getResponseCode(); } }";
    check("keeping a copy for a later disconnect is fine", problems(aliasOk, cnt).isEmpty());
    String passed = "class A { void f(String u) throws Exception { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection(); use(c); c.setInstanceFollowRedirects(false); } }";
    check("handing the connection to another method before the switch is refused", has(problems(passed, cnt), "is passed on before"));
    check("a call to another variable's redirects does not cover this one", has(problems(ok.replace("c.setInstanceFollowRedirects(false)", "d.setInstanceFollowRedirects(false)"), cnt), "'c' does not turn"));

    // the source tree
    File dir = srcDir();
    if (dir == null) { System.out.println("SKIP src/com/bloatware/bingblop not found"); System.out.println("PASS HttpSitesTest: " + n + " checks"); return; }
    File[] files = dir.listFiles();
    Arrays.sort(files);
    Map<String, Integer> opens = new HashMap<String, Integer>();
    for (File f : files) {
      if (!f.getName().endsWith(".java")) continue;
      String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      List<String> p = problems(src, cnt);
      int open = cnt[0];
      for (String s : p) check(f.getName() + ": " + s, false);
      if (p.isEmpty()) check(f.getName() + " is clean", true);
      if (open > 0) opens.put(f.getName(), open);
      Integer allowed = ALLOWED.get(f.getName());
      if (open > 0) {
        check(f.getName() + " opens a raw connection (" + open + ") but is not in the allowed list: use HttpSafe.open or add it here with a reason", allowed != null);
        if (allowed != null) check(f.getName() + " has " + open + " raw connections, " + allowed + " allowed", open <= allowed);
      }
    }
    for (Map.Entry<String, Integer> e : ALLOWED.entrySet())
      check(e.getKey() + " is listed as allowed but no longer opens a raw connection (remove it from the list)", opens.containsKey(e.getKey()));
    System.out.println(fails == 0 ? "PASS HttpSitesTest: " + n + " checks" : "FAILED HttpSitesTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
