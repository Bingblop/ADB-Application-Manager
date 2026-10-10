package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keeps new network code on the safe path. Every place that opens a raw HttpURLConnection (instead of HttpSafe.open) is listed here with
 * how many it may have; each one must turn automatic redirects off, because a redirect carries request headers (keys, cookies) wherever
 * the answer points. A new call site fails this test until it is either moved to HttpSafe.open or added below with a reason.
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

  static File srcDir() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop");
      if (f.isDirectory()) return f;
    }
    return null;
  }

  static boolean comment(String line) {
    String t = line.trim();
    return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
  }

  public static void main(String[] args) throws Exception {
    File dir = srcDir();
    if (dir == null) { System.out.println("SKIP src/com/bloatware/bingblop not found"); System.out.println("PASS HttpSitesTest: 0 checks"); return; }
    File[] files = dir.listFiles();
    Arrays.sort(files);
    Map<String, Integer> opens = new TreeMap<String, Integer>();
    for (File f : files) {
      if (!f.getName().endsWith(".java")) continue;
      int open = 0, off = 0;
      boolean on = false;
      for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        if (comment(line)) continue;
        if (line.contains("openConnection(")) open++;
        if (line.contains("setInstanceFollowRedirects(false)")) off++;
        if (line.contains("setInstanceFollowRedirects(true)")) on = true;
      }
      check(f.getName() + " must not switch automatic redirects on", !on);
      if (open > 0) opens.put(f.getName(), open);
      Integer allowed = ALLOWED.get(f.getName());
      if (open > 0) {
        check(f.getName() + " opens a raw connection (" + open + ") but is not in the allowed list: use HttpSafe.open or add it here with a reason", allowed != null);
        if (allowed != null) {
          check(f.getName() + " has " + open + " raw connections, " + allowed + " allowed", open <= allowed);
          check(f.getName() + " must turn automatic redirects off for each raw connection (" + off + " of " + open + ")", off >= open);
        }
      }
    }
    for (Map.Entry<String, Integer> e : ALLOWED.entrySet())
      check(e.getKey() + " is listed as allowed but no longer opens a raw connection (remove it from the list)", opens.containsKey(e.getKey()));
    System.out.println(fails == 0 ? "PASS HttpSitesTest: " + n + " checks" : "FAILED HttpSitesTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
