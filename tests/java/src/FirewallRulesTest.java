package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android Firewall (v7.21.0), the engine: the page's rule set becomes one iptables script, and what the phone prints becomes the status the page
 * believes. Checked here: the parse (what is refused), the shape of the apply script in both modes (an allow list ends every network chain in the
 * reject chain and ticked uids RETURN; a block list rejects the ticked ones and ends open), the kernel's own packets (uid -11), the optional
 * columns and chains (LAN, roaming while roaming, VPN, Tor's filter and nat lines, inbound, tethering), LOG and DROP, IPv6 built or torn down,
 * the user's own lines before and after, the disable script (both families, Android's chains untouched), every script's syntax under a real sh
 * when one is on PATH, the status and log read-back, the boot script's delay, and a scan of the activity and the boot receiver: the bridge
 * exists with the four methods the page calls, an apply is reported as ok only after the read-back shows the jump and rules in place, the
 * executor is shut down, and the receiver runs only the app's own boot file through su when Apply on boot is on.
 */
public class FirewallRulesTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, Object extra) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + extra); } }

  static int count(String hay, String needle) { int c = 0, i = 0; while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); } return c; }
  static boolean hasLine(String script, String line) { return ("\n" + script).contains("\n" + line + "\n"); }

  static FirewallRules.Cfg cfg(String json) throws Exception { return FirewallRules.parse(json); }

  /** sh -n of a script, when sh exists; null when it cannot be run (the check is then skipped, not failed). */
  static Boolean shOk(String script) {
    try {
      File f = File.createTempFile("atbx", ".sh");
      Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
      Process p = new ProcessBuilder("sh", "-n", f.getAbsolutePath()).redirectErrorStream(true).start();
      p.getOutputStream().close();
      byte[] buf = new byte[4096]; StringBuilder out = new StringBuilder();
      java.io.InputStream in = p.getInputStream(); int r; while ((r = in.read(buf)) > 0) out.append(new String(buf, 0, r, StandardCharsets.UTF_8));
      int code = p.waitFor();
      f.delete();
      if (code != 0) System.out.println("  sh -n: " + out);
      return code == 0;
    } catch (Throwable t) { return null; }
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
    // ---- parse ----
    FirewallRules.Cfg c = cfg("{\"mode\":\"allow\",\"uids\":{\"10123\":[\"wifi\",\"mobile\"],\"1051\":[\"wifi\",\"mobile\",\"lan\"],\"-11\":[\"wifi\"]},\"lan\":true,\"log\":true,\"selfUid\":10200,\"pre\":\"# a\\r\\n\\n  \\necho hi\\n\",\"post\":\"\"}");
    check("parse: mode, the three uids, the options", c.mode.equals("allow") && c.uids.size() == 3 && c.uids.get(10123).contains("mobile") && c.lan && c.log && !c.drop && c.selfUid == 10200);
    check("parse: the kernel pseudo-uid -11 is accepted", c.uids.containsKey(-11));
    check("parse: the user's lines lose CRs and blank lines", c.pre.equals("# a\necho hi\n") && c.post.isEmpty(), c.pre);
    check("parse: no uids at all is a valid (empty) set", cfg("{}").uids.isEmpty() && cfg("{}").mode.equals("allow"));
    String[] bad = { "{\"mode\":\"deny\"}", "{\"uids\":{\"abc\":[\"wifi\"]}}", "{\"uids\":{\"-5\":[\"wifi\"]}}", "{\"uids\":{\"10\":[\"bluetooth\"]}}", "{\"uids\":{\"10; rm -rf /\":[\"wifi\"]}}", "{\"uids\":{\"12345678\":[\"wifi\"]}}", "not json" };
    for (String b : bad) {
      boolean threw = false;
      try { cfg(b); } catch (Exception e) { threw = true; }
      check("parse refuses " + b, threw);
    }

    // ---- the apply script, allow list ----
    String s = FirewallRules.applyScript(c);
    check("apply: starts as a shell script and gives up in words when iptables is missing", s.startsWith("#!/system/bin/sh\n") && s.contains("command -v iptables >/dev/null 2>&1 || { echo '__ATBX_NOBIN__'; exit 0; }"));
    check("apply: every chain is made (missing allowed) and emptied", hasLine(s, "iptables -w 5 -N atbx 2>/dev/null") && hasLine(s, "iptables -w 5 -F atbx") && hasLine(s, "iptables -w 5 -N atbx-reject 2>/dev/null") && hasLine(s, "iptables -w 5 -F atbx-wifi"));
    check("apply: the old jump is removed in a loop and one new jump put first in OUTPUT", hasLine(s, "while iptables -w 5 -D OUTPUT -j atbx 2>/dev/null; do :; done") && count(s, "iptables -w 5 -I OUTPUT 1 -j atbx\n") == 1);
    check("apply: loopback returns first in the main chain", hasLine(s, "iptables -w 5 -A atbx -o lo -j RETURN") && s.indexOf("-A atbx -o lo -j RETURN") < s.indexOf("-A atbx -o wlan+"));
    check("apply: Wi-Fi interfaces go to the Wi-Fi chain, LAN ranges to the LAN chain first when LAN is on", hasLine(s, "iptables -w 5 -A atbx -o wlan+ -j atbx-wifi") && hasLine(s, "iptables -w 5 -A atbx -o wlan+ -d 192.168.0.0/16 -j atbx-lan") && s.indexOf("-o wlan+ -d 10.0.0.0/8 -j atbx-lan") < s.indexOf("-o wlan+ -j atbx-wifi"));
    check("apply: mobile interfaces go to the mobile chain (not roaming)", hasLine(s, "iptables -w 5 -A atbx -o rmnet_data+ -j atbx-mobile") && !s.contains("-j atbx-roam"));
    check("apply: VPN interfaces are not filtered while VPN is not a column", !s.contains("-o tun+"));
    check("allow list: a ticked uid RETURNs in its chains and nowhere else", hasLine(s, "iptables -w 5 -A atbx-wifi -m owner --uid-owner 10123 -j RETURN") && hasLine(s, "iptables -w 5 -A atbx-mobile -m owner --uid-owner 10123 -j RETURN") && !s.contains("-A atbx-lan -m owner --uid-owner 10123"));
    check("allow list: the resolver's LAN tick lands in the LAN chain", hasLine(s, "iptables -w 5 -A atbx-lan -m owner --uid-owner 1051 -j RETURN"));
    check("allow list: the kernel's packets are matched as 'no owner' and RETURN", hasLine(s, "iptables -w 5 -A atbx-wifi -m owner ! --uid-owner 0-999999999 -j RETURN"));
    check("allow list: every network chain ends in the reject chain", hasLine(s, "iptables -w 5 -A atbx-wifi -j atbx-reject") && hasLine(s, "iptables -w 5 -A atbx-mobile -j atbx-reject") && hasLine(s, "iptables -w 5 -A atbx-lan -j atbx-reject"));
    check("allow list: the ticked uid's RETURN comes before the chain's reject", s.indexOf("-A atbx-wifi -m owner --uid-owner 10123 -j RETURN") < s.indexOf("-A atbx-wifi -j atbx-reject"));
    check("log on: one rate-limited LOG line with the app's prefix and the uid, before REJECT", hasLine(s, "iptables -w 5 -A atbx-reject -m limit --limit 1000/min --limit-burst 200 -j LOG --log-prefix \"{ATBX} \" --log-uid --log-level 4") && s.indexOf("-j LOG --log-prefix") < s.indexOf("-A atbx-reject -j REJECT"));
    check("reject, not drop, by default (an app sees 'unreachable' at once instead of waiting)", hasLine(s, "iptables -w 5 -A atbx-reject -j REJECT --reject-with icmp-port-unreachable") && !s.contains("-j DROP"));
    check("IPv6 off: no ip6tables rule is built, the family's chains are torn down (guarded by command -v)", !s.contains("ip6tables -w 5 -I OUTPUT") && s.contains("if command -v ip6tables >/dev/null 2>&1; then\nwhile ip6tables -w 5 -D OUTPUT -j atbx 2>/dev/null; do :; done") && s.contains("ip6tables -w 5 -X atbx 2>/dev/null\nfi"));
    check("inbound and tethering off: their jumps are removed, none added", hasLine(s, "while iptables -w 5 -D INPUT -j atbx-in 2>/dev/null; do :; done") && !s.contains("-I INPUT 1") && hasLine(s, "while iptables -w 5 -D FORWARD -j atbx-fwd 2>/dev/null; do :; done") && !s.contains("-I FORWARD 1"));
    check("Tor off: the nat jump is removed and the nat chain deleted", hasLine(s, "while iptables -w 5 -t nat -D OUTPUT -j atbx-tor 2>/dev/null; do :; done") && hasLine(s, "iptables -w 5 -t nat -X atbx-tor 2>/dev/null") && !s.contains("REDIRECT"));
    check("the user's lines come before the rules, marked, and none after when post is empty", s.contains("# --- your lines, before ---\n# a\necho hi\n# --- end ---\n") && s.indexOf("echo hi") < s.indexOf("-N atbx ") && !s.contains("your lines, after"));
    check("apply ends with the applied marker and the status lines", s.contains("echo '__ATBX_APPLIED__'\n") && s.endsWith(FirewallRules.statusScript()) && s.indexOf("__ATBX_APPLIED__") < s.indexOf("__ATBX_STATUS__"));
    check("no rule of the script touches Android's own chains (only -I/-D of the jumps in OUTPUT/INPUT/FORWARD)", !s.matches("(?s).*-A (OUTPUT|INPUT|FORWARD)\\b.*") && !s.contains("-F OUTPUT") && !s.contains("-F INPUT") && !s.contains("-F FORWARD") && !s.contains("-P "));

    // ---- block list with every option ----
    FirewallRules.Cfg b = cfg("{\"mode\":\"block\",\"uids\":{\"10050\":[\"wifi\",\"mobile\",\"roam\",\"vpn\",\"lan\"],\"10060\":[\"tor\"],\"-11\":[\"mobile\"]},\"lan\":true,\"vpn\":true,\"roam\":true,\"tor\":true,\"ipv6\":true,\"log\":false,\"drop\":true,\"inbound\":true,\"tether\":true,\"roamingNow\":true,\"post\":\"iptables -A atbx -j RETURN\\n\"}");
    String t = FirewallRules.applyScript(b);
    check("block list: the ticked uid is rejected in every column's chain", hasLine(t, "iptables -w 5 -A atbx-wifi -m owner --uid-owner 10050 -j atbx-reject") && hasLine(t, "iptables -w 5 -A atbx-lan -m owner --uid-owner 10050 -j atbx-reject") && hasLine(t, "iptables -w 5 -A atbx-roam -m owner --uid-owner 10050 -j atbx-reject") && hasLine(t, "iptables -w 5 -A atbx-vpn -m owner --uid-owner 10050 -j atbx-reject"));
    check("block list: no chain ends in the reject chain (everyone else gets out)", !t.contains("-A atbx-wifi -j atbx-reject") && !t.contains("-A atbx-mobile -j atbx-reject") && !t.contains("-A atbx-vpn -j atbx-reject"));
    check("block list: the kernel's packets on mobile are rejected with the no-owner match", hasLine(t, "iptables -w 5 -A atbx-mobile -m owner ! --uid-owner 0-999999999 -j atbx-reject"));
    check("roaming now, Roaming a column: the mobile interfaces go to the roaming chain", hasLine(t, "iptables -w 5 -A atbx -o rmnet_data+ -j atbx-roam") && !t.contains("-o rmnet_data+ -j atbx-mobile"));
    check("VPN a column: the tunnel interfaces go to the VPN chain", hasLine(t, "iptables -w 5 -A atbx -o tun+ -j atbx-vpn") && hasLine(t, "iptables -w 5 -A atbx -o wg+ -j atbx-vpn"));
    check("drop: DROP instead of REJECT, and no LOG line when log is off", hasLine(t, "iptables -w 5 -A atbx-reject -j DROP") && !t.contains("-j LOG") && hasLine(t, "ip6tables -w 5 -A atbx-reject -j DROP"));
    check("Tor: the app's traffic may only reach Orbot's local ports in the filter table, the rest of it is rejected", hasLine(t, "iptables -w 5 -A atbx -m owner --uid-owner 10060 -d 127.0.0.1 -p tcp -m multiport --dports 9040,9050,8118 -j RETURN") && hasLine(t, "iptables -w 5 -A atbx -m owner --uid-owner 10060 -d 127.0.0.1 -p udp --dport 5400 -j RETURN") && hasLine(t, "iptables -w 5 -A atbx -m owner --uid-owner 10060 -j atbx-reject"));
    check("Tor: the nat chain redirects its DNS to 5400 and its TCP to 9040, jumped first from nat OUTPUT", hasLine(t, "iptables -w 5 -t nat -I OUTPUT 1 -j atbx-tor") && hasLine(t, "iptables -w 5 -t nat -A atbx-tor -m owner --uid-owner 10060 -p udp --dport 53 -j REDIRECT --to-ports 5400") && hasLine(t, "iptables -w 5 -t nat -A atbx-tor -m owner --uid-owner 10060 -p tcp --syn -j REDIRECT --to-ports 9040"));
    check("Tor: the Tor lines come before the interface lines (a Tor app is decided before its network is)", t.indexOf("--uid-owner 10060 -j atbx-reject") < t.indexOf("-o wlan+ -j atbx-wifi"));
    check("Tor: nothing of it in ip6tables (Orbot's transparent proxy is IPv4)", !t.contains("ip6tables -w 5 -t nat") && !t.contains("ip6tables -w 5 -A atbx -m owner --uid-owner 10060 -d"));
    check("inbound: lo and answers pass, new inbound connections are rejected, jumped first from INPUT", hasLine(t, "iptables -w 5 -I INPUT 1 -j atbx-in") && hasLine(t, "iptables -w 5 -A atbx-in -i lo -j RETURN") && hasLine(t, "iptables -w 5 -A atbx-in -m state --state ESTABLISHED,RELATED -j RETURN") && hasLine(t, "iptables -w 5 -A atbx-in -j atbx-reject"));
    check("tethering: what comes in from a hotspot interface and is forwarded is rejected", hasLine(t, "iptables -w 5 -I FORWARD 1 -j atbx-fwd") && hasLine(t, "iptables -w 5 -A atbx-fwd -i rndis+ -j atbx-reject") && hasLine(t, "iptables -w 5 -A atbx-fwd -i swlan+ -j atbx-reject"));
    check("IPv6 on: the same chains with ip6tables, the IPv6 LAN ranges, its own reject, the jump first in OUTPUT", hasLine(t, "ip6tables -w 5 -I OUTPUT 1 -j atbx") && hasLine(t, "ip6tables -w 5 -A atbx -o wlan+ -d fe80::/10 -j atbx-lan") && hasLine(t, "ip6tables -w 5 -A atbx-wifi -m owner --uid-owner 10050 -j atbx-reject") && !t.contains("ip6tables -w 5 -A atbx -o wlan+ -d 192.168"));
    check("the user's lines after come after the rules, marked", t.contains("# --- your lines, after ---\niptables -A atbx -j RETURN\n# --- end ---\n") && t.indexOf("your lines, after") > t.lastIndexOf("-A atbx-fwd") && t.indexOf("your lines, after") < t.indexOf("__ATBX_APPLIED__"));

    // ---- disable ----
    String d = FirewallRules.disableScript();
    check("disable: both families' jumps are removed in loops, the chains emptied then deleted (reverse order: the referenced ones last)", d.contains("while iptables -w 5 -D OUTPUT -j atbx 2>/dev/null; do :; done") && d.contains("while ip6tables -w 5 -D OUTPUT -j atbx 2>/dev/null; do :; done") && d.indexOf("iptables -w 5 -F atbx-fwd 2>/dev/null") < d.indexOf("iptables -w 5 -X atbx-fwd 2>/dev/null") && d.indexOf("iptables -w 5 -X atbx-fwd 2>/dev/null") < d.indexOf("iptables -w 5 -X atbx 2>/dev/null"));
    check("disable: the nat chain goes too, and the cleared marker then the status follow", d.contains("iptables -w 5 -t nat -X atbx-tor 2>/dev/null") && d.contains("echo '__ATBX_CLEARED__'\n") && d.endsWith(FirewallRules.statusScript()));
    check("disable: Android's chains are left alone", !d.contains("-F OUTPUT") && !d.contains("-F INPUT") && !d.contains("-F FORWARD") && !d.contains("-X OUTPUT") && !d.contains("-P "));
    check("no script carries a CR or a NUL", !(s + t + d).contains("\r") && !(s + t + d).contains("\0"));

    // ---- syntax under a real sh ----
    Boolean sh = shOk(s);
    if (sh == null) System.out.println("  (no sh on PATH: the syntax checks are skipped)");
    else {
      check("the allow script is valid sh", sh);
      check("the block script with every option is valid sh", Boolean.TRUE.equals(shOk(t)));
      check("the disable script is valid sh", Boolean.TRUE.equals(shOk(d)));
      check("the status, list and log scripts are valid sh", Boolean.TRUE.equals(shOk(FirewallRules.statusScript())) && Boolean.TRUE.equals(shOk(FirewallRules.listScript())) && Boolean.TRUE.equals(shOk(FirewallRules.logScript(true))));
      check("the boot script is valid sh", Boolean.TRUE.equals(shOk(FirewallRules.bootScript(s, 20))));
    }

    // ---- status read-back ----
    String out = "iptables: Bad rule (does a matching rule exist in that chain?).\n__ATBX_APPLIED__\n__ATBX_STATUS__\nbin4=1\nbin6=1\njump4=1\njump6=0\nin4=0\nfwd4=1\ntor4=0\nn4_atbx=14\nn4_atbx-wifi=3\nn4_atbx-lan=0\nn4_atbx-mobile=3\nn4_atbx-roam=0\nn4_atbx-vpn=0\nn4_atbx-reject=1\nn6_atbx=0\nuid=0\n__ATBX_END__\n";
    FirewallRules.Status st = FirewallRules.parseStatus(out);
    check("status: applied, the jump in place, the rules counted over the chains, tethering on, uid 0", st.applied && !st.cleared && !st.noBin && st.jump4 && !st.jump6 && st.bin4 && st.bin6 && !st.inbound && st.tether && !st.tor && st.rules4 == 21 && st.rules6 == 0 && st.uid == 0 && st.chains.get("atbx-wifi") == 3);
    JSONObject sj = st.toJson();
    check("status json: on = the jump is there and rules exist", sj.getBoolean("on") && sj.getInt("rules4") == 21 && sj.getJSONObject("chains").getInt("atbx") == 14 && sj.getBoolean("applied"));
    FirewallRules.Status off = FirewallRules.parseStatus("__ATBX_CLEARED__\n__ATBX_STATUS__\nbin4=1\nbin6=0\njump4=0\njump6=0\nin4=0\nfwd4=0\ntor4=0\nn4_atbx=0\nn6_atbx=0\nuid=1000\n__ATBX_END__");
    check("status after disable: cleared, no jump, off, uid 1000", off.cleared && !off.jump4 && !off.toJson().getBoolean("on") && off.uid == 1000 && off.bin4 && !off.bin6);
    FirewallRules.Status nob = FirewallRules.parseStatus("__ATBX_NOBIN__\n");
    check("no iptables: noBin, nothing else believed, not on", nob.noBin && !nob.applied && !nob.jump4 && !nob.toJson().getBoolean("on"));
    check("a jump without rules is not 'on' (a half-built table)", !FirewallRules.parseStatus("__ATBX_STATUS__\njump4=1\nn4_atbx=0\n__ATBX_END__").toJson().getBoolean("on"));
    check("garbage and null do not crash the status parse", !FirewallRules.parseStatus(null).jump4 && !FirewallRules.parseStatus("jump4=yes\n=3\nn4_atbx=x").jump4);
    check("lines before the status block (iptables' own words) are not read as counts", !FirewallRules.parseStatus("jump4=1\nn4_atbx=5\n").jump4);

    // ---- the log ----
    String dm = "<4>[ 1234.567890] {ATBX} IN= OUT=wlan0 SRC=192.168.1.5 DST=142.250.74.78 LEN=60 TOS=0x00 PREC=0x00 TTL=64 ID=1 DF PROTO=TCP SPT=43210 DPT=443 WINDOW=65535 RES=0x00 SYN URGP=0 UID=10123 GID=10123\n"
        + "[ 1234.900000] {ATBX} IN= OUT=rmnet_data0 SRC=10.1.2.3 DST=8.8.8.8 LEN=72 PROTO=UDP SPT=5353 DPT=53 LEN=52 UID=10123 GID=10123\n"
        + "[ 1235.000000] {ATBX} IN= OUT=wlan0 SRC=192.168.1.5 DST=224.0.0.251 LEN=32 PROTO=ICMP TYPE=8 CODE=0\n"
        + "not a firewall line UID=1\n"
        + "[ 1236.000000] {ATBX} IN= OUT=wlan0 DST=1.1.1.1 PROTO=TCP DPT=80 UID=10050\n"
        + "__ATBX_LOGEND__\n";
    List<FirewallRules.LogEntry> log = FirewallRules.parseLog(dm);
    check("log: four lines, the fields read, a line without UID is the kernel's", log.size() == 4 && log.get(0).uid == 10123 && log.get(0).dst.equals("142.250.74.78") && log.get(0).dport == 443 && log.get(0).proto.equals("TCP") && log.get(0).iface.equals("wlan0") && log.get(0).when.equals("1234.567890") && log.get(2).uid == FirewallRules.UID_KERNEL && log.get(2).dport == -1);
    JSONArray g = FirewallRules.groupLog(log);
    check("log grouped by uid, newest first, with the count and the last destination", g.length() == 3 && g.getJSONObject(0).getInt("uid") == 10050 && g.getJSONObject(1).getInt("uid") == -11 && g.getJSONObject(2).getInt("uid") == 10123 && g.getJSONObject(2).getInt("count") == 2 && g.getJSONObject(2).getString("dst").equals("8.8.8.8") && g.getJSONObject(2).getInt("dport") == 53, g.toString());
    check("log: null and empty give an empty list", FirewallRules.parseLog(null).isEmpty() && FirewallRules.parseLog("").isEmpty() && FirewallRules.groupLog(FirewallRules.parseLog("")).length() == 0);
    check("the log script greps for the prefix and clears the ring only when asked", FirewallRules.logScript(false).contains("grep -F '{ATBX}'") && !FirewallRules.logScript(false).contains("dmesg -c") && FirewallRules.logScript(true).contains("dmesg -c") && FirewallRules.logScript(true).endsWith("echo '__ATBX_LOGEND__'\n"));

    // ---- boot ----
    check("boot script: the delay in front, clamped to 0..300, then the apply script itself", FirewallRules.bootScript(s, 20).startsWith("#!/system/bin/sh\n# ADB/Root Toolbox - Android Firewall, run at boot\nsleep 20\n#!/system/bin/sh") && FirewallRules.bootScript(s, 20).endsWith(s) && !FirewallRules.bootScript(s, 0).contains("sleep") && FirewallRules.bootScript(s, 9999).contains("sleep 300\n") && !FirewallRules.bootScript(s, -4).contains("sleep"));

    // ---- specials ----
    check("the specials: kernel -11, root 0, system 1000, the resolver 1051, each with a reason", FirewallRules.special("kernel").uid == -11 && FirewallRules.special("root").uid == 0 && FirewallRules.special("system").uid == 1000 && FirewallRules.special("dns").uid == 1051 && !FirewallRules.special("dns").why.isEmpty() && FirewallRules.special("nope") == null);
    check("the network kinds the page may send", String.join(",", FirewallRules.NETS).equals("wifi,mobile,roam,vpn,lan,tor"));

    // ---- a scan of the activity and the receiver ----
    File dir = srcDir();
    if (dir == null) System.out.println("  (src/com/bloatware/bingblop not found: the source scans are skipped)");
    else {
      String main = new String(Files.readAllBytes(new File(dir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
      for (String m : new String[] { "fwApps", "fwRun", "fwNetState", "fwBoot" })
        check("the bridge offers " + m + " to the page, @JavascriptInterface", Pattern.compile("@JavascriptInterface\\s+public\\s+String\\s+" + m + "\\s*\\(").matcher(main).find());
      check("fwRun answers 'started' and reports through window.onFwDone", main.contains("return \"started\";") && main.contains("window.onFwDone("));
      check("an apply is ok only when the read-back shows the jump and rules in place", Pattern.compile("st\\.applied\\s*&&\\s*st\\.jump4\\s*&&\\s*st\\.rules4\\s*>\\s*0").matcher(main).find());
      check("the firewall gate is the Root Features gate (su, or Shizuku as the system user / root), never the shell user", main.contains("fwGate()") && Pattern.compile("String fwGate\\(\\)\\s*\\{\\s*return fmkGate\\(false\\);").matcher(main).find());
      check("the firewall executor is shut down in onDestroy", Pattern.compile("fwExec\\s*=\\s*Executors\\.newSingleThreadExecutor\\(\\)").matcher(main).find() && Pattern.compile("onDestroy\\(\\)[\\s\\S]*fwExec\\.shutdownNow\\(\\)").matcher(main).find());
      check("the network callback is unregistered in onDestroy", Pattern.compile("onDestroy\\(\\)[\\s\\S]*fwNetCb").matcher(main).find());
      check("the page's uids never reach the shell unparsed: fwRun parses the JSON through FirewallRules.parse before any script", main.contains("FirewallRules.parse(") && main.contains("FirewallRules.applyScript("));
      check("the boot file is the app's own files/firewall/boot.sh, written with the boot script", main.contains("FirewallRules.BOOT_SCRIPT") && main.contains("FirewallRules.bootScript("));
      String boot = new String(Files.readAllBytes(new File(dir, "BootReceiver.java").toPath()), StandardCharsets.UTF_8);
      check("the boot receiver runs the firewall only when Apply on boot is on and the file exists", boot.contains("p.getBoolean(\"fw_boot\", false)") && boot.contains("FirewallRules.BOOT_SCRIPT") && boot.contains("isFile()"));
      check("the boot receiver runs that file through su and nothing else", Pattern.compile("new ProcessBuilder\\(\"su\", \"-c\", \"sh \" \\+ BackupScripts\\.quote\\(f\\.getAbsolutePath\\(\\)\\)\\)").matcher(boot).find() && count(boot, "ProcessBuilder(") == 1);
      check("the boot receiver finishes its async result on every path", count(boot, "pending.finish()") >= 2);
      File page = new File(dir.getParentFile().getParentFile().getParentFile().getParentFile(), "assets/index.html");
      if (page.isFile()) {
        String html = new String(Files.readAllBytes(page.toPath()), StandardCharsets.UTF_8);
        check("the page calls the four bridge methods and listens for both callbacks", html.contains("AndroidBridge.fwApps()") && html.contains("AndroidBridge.fwRun(") && html.contains("AndroidBridge.fwNetState()") && html.contains("AndroidBridge.fwBoot(") && html.contains("window.onFwDone = function") && html.contains("window.onFwNet = function"));
        check("the page's Remove all firewall rules is a hold-to-confirm danger sheet", Pattern.compile("function fwFlush\\(\\)[\\s\\S]{0,600}hold: true, tone: 'danger'").matcher(html).find());
        check("the page's network kinds are the engine's", html.contains("{ key: 'wifi'") && html.contains("{ key: 'tor'") && html.contains("[\"wifi\", \"mobile\", \"roam\", \"vpn\", \"lan\", \"tor\"]".replace("\"", "'").replace("[", "").replace("]", "")) || html.contains("key: 'lan'"));
      }
    }

    System.out.println(fails == 0 ? "PASS FirewallRulesTest: " + n + " checks" : "FAILED FirewallRulesTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
