package com.bloatware.bingblop;

/** The VirusTotal API key goes only to https addresses on the API's own host (the upload address of a big file comes from the server's answer). */
public class VirusTotalHostTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  public static void main(String[] args) {
    check("the API itself", VirusTotal.isApiHost("https://www.virustotal.com/api/v3/files"));
    check("a real-looking upload address", VirusTotal.isApiHost("https://www.virustotal.com/_ah/upload/AMmfu6b/abc="));
    check("upper-case host", VirusTotal.isApiHost("https://WWW.VirusTotal.com/x"));
    check("explicit default port", VirusTotal.isApiHost("https://www.virustotal.com:443/x"));
    check("another host is refused", !VirusTotal.isApiHost("https://evil.example/upload"));
    check("a look-alike suffix is refused", !VirusTotal.isApiHost("https://www.virustotal.com.evil.example/x"));
    check("a look-alike prefix is refused", !VirusTotal.isApiHost("https://evilwww.virustotal.com/x"));
    check("another sub-domain is refused", !VirusTotal.isApiHost("https://upload.virustotal.com/x"));
    check("user info trick is refused", !VirusTotal.isApiHost("https://www.virustotal.com@evil.example/x"));
    check("user info on the real host is refused", !VirusTotal.isApiHost("https://user:pw@www.virustotal.com/x"));
    check("plain http is refused", !VirusTotal.isApiHost("http://www.virustotal.com/x"));
    check("another port is refused", !VirusTotal.isApiHost("https://www.virustotal.com:8443/x"));
    check("a loopback address is refused", !VirusTotal.isApiHost("https://127.0.0.1/x") && !VirusTotal.isApiHost("http://localhost:8080/x"));
    check("other schemes are refused", !VirusTotal.isApiHost("ftp://www.virustotal.com/x") && !VirusTotal.isApiHost("file:///etc/passwd"));
    check("garbage and empty are refused", !VirusTotal.isApiHost("") && !VirusTotal.isApiHost("not a url") && !VirusTotal.isApiHost(null));
    System.out.println(fails == 0 ? "PASS VirusTotalHostTest: " + n + " checks" : "FAILED VirusTotalHostTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
