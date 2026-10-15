package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source scans of MainActivity for mistakes that end the app or leak past its window and that no on-device test catches:
 * <ul>
 * <li>every {@code startActivityForResult(} call sits inside a {@code try} block of its own method (with no picker app, for example with the
 * Files app frozen, the call throws ActivityNotFoundException, and uncaught on the UI thread that ends the app: review MA2-3).</li>
 * <li>every executor that MainActivity (or its bridge) keeps in a field is shut down in onDestroy: each recreate() makes a new activity and
 * bridge, and a pool nobody stops keeps its idle thread for the life of the process (AGENTS.md; review MA2-4).</li>
 * </ul>
 * The scan works on the source with comments, string and character literals blanked out.
 */
public class ActivityGuardsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  /** The source with comments, strings and characters replaced by spaces (same length, line breaks kept). */
  static String blank(String s) {
    StringBuilder b = new StringBuilder(s);
    int i = 0, len = s.length();
    while (i < len) {
      char c = s.charAt(i);
      if (c == '/' && i + 1 < len && s.charAt(i + 1) == '/') {
        while (i < len && s.charAt(i) != '\n') b.setCharAt(i++, ' ');
      } else if (c == '/' && i + 1 < len && s.charAt(i + 1) == '*') {
        int e = s.indexOf("*/", i + 2);
        e = e < 0 ? len : e + 2;
        for (; i < e; i++) if (s.charAt(i) != '\n') b.setCharAt(i, ' ');
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < len && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
        for (int k = i + 1; k < j && k < len; k++) b.setCharAt(k, ' ');
        i = j + 1;
      } else i++;
    }
    return b.toString();
  }

  static final Pattern METHOD_HEAD = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\([^(){};]*\\)\\s*(throws\\s+[\\w.,\\s]+)?$");
  static final Pattern TRY_HEAD = Pattern.compile("\\btry\\s*(\\([^{}]*\\))?\\s*$");
  static final List<String> NOT_METHODS = java.util.Arrays.asList("if", "for", "while", "switch", "catch", "synchronized");

  /** Whether position {@code at} of the blanked source {@code b} lies inside a try block of the method (or lambda-free block chain) that holds it. */
  static boolean insideTry(String b, int at) {
    int depth = 0;
    for (int i = at - 1; i >= 0; i--) {
      char c = b.charAt(i);
      if (c == '}') depth++;
      else if (c == '{') {
        if (depth > 0) { depth--; continue; }
        String head = b.substring(Math.max(0, i - 300), i);
        if (TRY_HEAD.matcher(head).find()) return true;
        Matcher m = METHOD_HEAD.matcher(head);
        if (m.find() && !NOT_METHODS.contains(m.group(1))) return false;     // the method that holds the call: a try further out runs at another time
      }
    }
    return false;
  }

  /** Line numbers of the startActivityForResult calls that are not inside a try of their own method. */
  static List<Integer> unguardedStarts(String src) {
    String b = blank(src);
    List<Integer> out = new ArrayList<Integer>();
    Matcher m = Pattern.compile("\\bstartActivityForResult\\s*\\(").matcher(b);
    while (m.find()) {
      String before = b.substring(Math.max(0, m.start() - 80), m.start());
      if (Pattern.compile("\\bvoid\\s*$").matcher(before).find()) continue;      // a declaration (an override), not a call
      if (!insideTry(b, m.start())) out.add(lineOf(src, m.start()));
    }
    return out;
  }

  /** The body of the method whose header is {@code sig} (blanked source), up to its matching brace; null when not found. */
  static String bodyOf(String b, String sig) {
    int i = b.indexOf(sig);
    if (i < 0) return null;
    int open = b.indexOf('{', i);
    if (open < 0) return null;
    int depth = 0;
    for (int j = open; j < b.length(); j++) {
      char c = b.charAt(j);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return b.substring(open, j + 1);
    }
    return null;
  }

  /** The names of fields given an executor from Executors.new... whose shutdown (or shutdownNow) is not called in onDestroy. */
  static List<String> executorsNotStopped(String src, List<String> seen) {
    String b = blank(src);
    String destroy = bodyOf(b, "void onDestroy()");
    List<String> out = new ArrayList<String>();
    Matcher m = Pattern.compile("\\b(\\w+)\\s*=\\s*(?:java\\.util\\.concurrent\\.)?Executors\\s*\\.\\s*new\\w+").matcher(b);
    while (m.find()) {
      String name = m.group(1);
      if (seen != null) seen.add(name);
      if (destroy == null || !Pattern.compile("\\b" + name + "\\s*\\.\\s*shutdown(Now)?\\s*\\(").matcher(destroy).find()) out.add(name);
    }
    return out;
  }

  static int lineOf(String s, int at) {
    int line = 1;
    for (int i = 0; i < at; i++) if (s.charAt(i) == '\n') line++;
    return line;
  }

  static File srcDir() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop");
      if (f.isDirectory()) return f;
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    // the scanner itself
    check("a call inside a try is guarded", unguardedStarts("class A { void f() { try { startActivityForResult(i, 1); } catch (Exception e) {} } }").isEmpty());
    check("a call inside an if inside a try is guarded", unguardedStarts("class A { void f() { try { if (x) { startActivityForResult(i, 1); } } catch (Exception e) {} } }").isEmpty());
    check("a bare call is not guarded", unguardedStarts("class A { void f() {\n startActivityForResult(i, 1); } }").equals(java.util.Arrays.asList(2)));
    check("a call after a closed try is not guarded", unguardedStarts("class A { void f() { try { g(); } catch (Exception e) {} startActivityForResult(i, 1); } }").size() == 1);
    check("a try around the code that queues a runnable does not guard the call inside run()",
        unguardedStarts("class A { void f() { try { runOnUiThread(new Runnable() { public void run() { startActivityForResult(i, 1); } }); } catch (Exception e) {} } }").size() == 1);
    check("a try inside run() guards it", unguardedStarts("class A { void f() { runOnUiThread(new Runnable() { public void run() { try { startActivityForResult(i, 1); } catch (Exception e) {} } }); } }").isEmpty());
    check("comments and strings do not count", unguardedStarts("class A { String s = \"startActivityForResult(i, 1)\"; // startActivityForResult(i, 1)\n }").isEmpty()
        && unguardedStarts("class A { void f() { /* try { */ startActivityForResult(i, 1); } }").size() == 1);
    check("the declaration of an override is not a call", unguardedStarts("class A { @Override public void startActivityForResult(Intent i, int r) { super.x(); } }").isEmpty());

    check("an executor shut down in onDestroy is fine", executorsNotStopped("class A { final ExecutorService a = Executors.newCachedThreadPool(); protected void onDestroy() { a.shutdown(); } }", null).isEmpty()
        && executorsNotStopped("class A { ExecutorService t; void f() { t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(); } protected void onDestroy() { if (t != null) { t.shutdownNow(); } } }", null).isEmpty());
    check("an executor of an inner class that onDestroy does not stop is named", executorsNotStopped("class A { protected void onDestroy() { a.shutdown(); } class B { final ExecutorService thumbs = Executors.newSingleThreadExecutor(); } }", null).equals(java.util.Arrays.asList("thumbs")));
    check("a shutdown elsewhere, or only in a comment, does not count", executorsNotStopped("class A { final ExecutorService a = Executors.newCachedThreadPool(); void stop() { a.shutdown(); } protected void onDestroy() { // a.shutdown();\n } }", null).equals(java.util.Arrays.asList("a")));

    // the source tree
    File dir = srcDir();
    if (dir == null) { System.out.println("SKIP src/com/bloatware/bingblop not found"); System.out.println("PASS ActivityGuardsTest: " + n + " checks"); return; }
    String main = new String(Files.readAllBytes(new File(dir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
    String b = blank(main);
    int calls = 0;
    Matcher m = Pattern.compile("\\bstartActivityForResult\\s*\\(").matcher(b);
    while (m.find()) calls++;
    List<Integer> bad = unguardedStarts(main);
    check("MainActivity: every startActivityForResult is inside a try of its own method (a phone without a picker must not end the app); unguarded at lines " + bad,
        calls > 0 && bad.isEmpty());

    List<String> pools = new ArrayList<String>();
    List<String> loose = executorsNotStopped(main, pools);
    check("MainActivity: every executor field is shut down in onDestroy (found " + pools + "); not stopped: " + loose, pools.size() >= 8 && loose.isEmpty());

    // MA1-2: the activity's own monitor is taken on the UI thread (onDestroy, the Morphe picker) and by the lazy getters, so no method that
    // holds it may run a shell command (which can wait minutes on a backend that does not answer)
    List<String> slow = new ArrayList<String>();
    int syncs = 0;
    Matcher sm = Pattern.compile("\\n    (?:private|public|protected)? ?synchronized [^\\n(]*\\(").matcher(main);
    while (sm.find()) {
      syncs++;
      String sig = sm.group().substring(1);
      String body = bodyOf(main, sig);
      if (body != null && (body.contains("executeShell(") || body.contains("runAdbShell(") || body.contains("restoreSecureSetting("))) slow.add(sig.trim());
    }
    check("MainActivity: no synchronized method of the activity runs a shell command while it holds the activity's lock (checked " + syncs + "); found: " + slow, syncs >= 8 && slow.isEmpty());

    System.out.println(fails == 0 ? "PASS ActivityGuardsTest: " + n + " checks" : "FAILED ActivityGuardsTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
