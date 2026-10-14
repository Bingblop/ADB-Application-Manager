package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The request codes of MainActivity (REQ_...). Two screens that use one code cannot both get their answer: onActivityResult takes the first
 * branch that matches, so the other branch is dead (this happened with the saved-lists backup folder and "Choose package files" on the
 * Connected Devices tab, both 4291). MainActivity needs Android classes, so this is a source scan: every private static final int REQ_...
 * constant has a value no other REQ_ constant has, and every constant a screen starts with startActivityForResult is tested in a
 * requestCode == REQ_X branch of onActivityResult (every one asked with requestPermissions in onRequestPermissionsResult, except the listed
 * ones that the closing "permissions changed" line answers). The failure names the offending pair or constant.
 *
 * The scan works on the source with comments and string literals blanked out. REQCODES_SOURCE=/path/MainActivity.java scans another copy.
 */
public class RequestCodesTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  /** Permission requests that have no branch of their own: the generic "permissions changed" line at the end of onRequestPermissionsResult answers them. */
  static final Set<String> GENERIC_PERMISSION_ANSWER = new HashSet<String>(Arrays.asList("REQ_BT_CONNECT"));

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

  /** The text of the braces that follow the first "name(" declaration, or null. */
  static String body(String t, String name) {
    Matcher m = Pattern.compile("\\bvoid\\s+" + name + "\\s*\\(").matcher(t);
    if (!m.find()) return null;
    int open = t.indexOf('{', m.end());
    if (open < 0) return null;
    int depth = 0;
    for (int i = open; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return t.substring(open, i + 1);
    }
    return null;
  }

  static final Pattern CONST = Pattern.compile("\\bstatic\\s+final\\s+int\\s+(REQ_[A-Z0-9_]+)\\s*=\\s*(-?\\d+)\\s*;");

  /** What is wrong with the request codes of one MainActivity source; empty when nothing. */
  static List<String> problems(String src) {
    List<String> out = new ArrayList<String>();
    String t = blank(src);
    Map<String, Integer> vals = new LinkedHashMap<String, Integer>();
    Matcher cm = CONST.matcher(t);
    while (cm.find()) vals.put(cm.group(1), Integer.parseInt(cm.group(2)));
    if (vals.isEmpty()) { out.add("no REQ_ constants found (the scan is out of date)"); return out; }
    List<String> names = new ArrayList<String>(vals.keySet());
    for (int i = 0; i < names.size(); i++)
      for (int j = i + 1; j < names.size(); j++)
        if (vals.get(names.get(i)).equals(vals.get(names.get(j))))
          out.add("two request codes share the value " + vals.get(names.get(i)) + ": " + names.get(i) + " and " + names.get(j) + " (the later branch of onActivityResult never runs)");
    String act = body(t, "onActivityResult"), perm = body(t, "onRequestPermissionsResult");
    if (act == null) out.add("onActivityResult not found (the scan is out of date)");
    if (perm == null) out.add("onRequestPermissionsResult not found (the scan is out of date)");
    if (act == null || perm == null) return out;
    for (String name : names) {
      boolean forResult = Pattern.compile("\\bstartActivityForResult\\s*\\([^;]*\\b" + name + "\\b").matcher(t).find();
      boolean forPerm = Pattern.compile("\\brequestPermissions\\s*\\([^;]*\\b" + name + "\\b").matcher(t).find();
      Pattern tested = Pattern.compile("\\brequestCode\\s*[=!]=\\s*" + name + "\\b");
      if (!forResult && !forPerm) out.add(name + " is declared but no screen asks with it");
      if (forResult && !tested.matcher(act).find()) out.add(name + " starts an activity for a result but onActivityResult has no requestCode == " + name + " branch (a dead constant: the answer is never delivered)");
      if (forPerm && !GENERIC_PERMISSION_ANSWER.contains(name) && !tested.matcher(perm).find()) out.add(name + " asks for a permission but onRequestPermissionsResult has no requestCode == " + name + " branch");
    }
    return out;
  }

  static boolean has(List<String> p, String part) {
    for (String s : p) if (s.contains(part)) return true;
    return false;
  }

  static File mainActivity() {
    String over = System.getenv("REQCODES_SOURCE");
    if (over != null && !over.isEmpty()) return new File(over);
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (f.isFile()) return f;
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    // the scanner itself, on small sources
    String ok = "class A { private static final int REQ_ONE = 1; private static final int REQ_TWO = 2; private static final int REQ_P = 3;\n"
        + " void go() { startActivityForResult(i, REQ_ONE); startActivityForResult(i, REQ_TWO); requestPermissions(x, REQ_P); }\n"
        + " protected void onActivityResult(int requestCode, int resultCode, Intent data) { if (requestCode == REQ_ONE) { return; } if (requestCode != REQ_TWO) return; }\n"
        + " public void onRequestPermissionsResult(int requestCode, String[] p, int[] g) { if (requestCode == REQ_P) { } } }";
    check("a clean source has no problem", problems(ok).isEmpty());
    List<String> dup = problems(ok.replace("REQ_TWO = 2", "REQ_TWO = 1"));
    check("two constants with one value are named as a pair", has(dup, "REQ_ONE and REQ_TWO") && has(dup, "value 1"));
    check("a constant with no branch is a dead constant, by name", has(problems(ok.replace("if (requestCode == REQ_ONE) { return; }", "")), "REQ_ONE starts an activity for a result but onActivityResult has no")
        && !has(problems(ok.replace("if (requestCode == REQ_ONE) { return; }", "")), "REQ_TWO"));
    check("a permission constant with no branch is refused unless it is listed", has(problems(ok.replace("if (requestCode == REQ_P) { }", "")), "REQ_P asks for a permission")
        && problems(ok.replace("REQ_P", "REQ_BT_CONNECT").replace("if (requestCode == REQ_BT_CONNECT) { }", "")).isEmpty());
    check("a constant nobody asks with is named", has(problems(ok.replace("startActivityForResult(i, REQ_TWO);", "")), "REQ_TWO is declared but no screen asks"));
    check("a branch that is only in a comment or a string does not count",
        has(problems(ok.replace("if (requestCode == REQ_ONE) { return; }", "// if (requestCode == REQ_ONE) { return; }\n String s = \"requestCode == REQ_ONE\";")), "REQ_ONE starts an activity"));
    check("a branch in the wrong method does not count", has(problems(ok.replace("if (requestCode == REQ_ONE) { return; }", "").replace("if (requestCode == REQ_P) { }", "if (requestCode == REQ_P || requestCode == REQ_ONE) { }")), "REQ_ONE starts an activity"));
    check("a source without constants is reported, not passed", has(problems("class A { }"), "no REQ_ constants"));

    // the real source
    File f = mainActivity();
    check("MainActivity.java found", f != null && f.isFile());
    if (f != null && f.isFile()) {
      String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      List<String> p = problems(src);
      for (String s : p) check("MainActivity: " + s, false);
      Matcher cm = CONST.matcher(blank(src));
      int count = 0;
      while (cm.find()) count++;
      check("the scan sees the request codes (found " + count + ")", count >= 10);
      check("MainActivity request codes are distinct and every one is answered", p.isEmpty());
    }
    System.out.println((fails == 0 ? "OK " : "FAILED ") + n + " checks, " + fails + " failed");
    System.exit(fails == 0 ? 0 : 1);
  }
}
