package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Android Firewall tab's rule engine (v7.21.0): turns the page's configuration into one iptables / ip6tables script, and reads the
 * phone's state back. No Android classes, so the Java suite (FirewallRulesTest) checks every script it can write.
 *
 * How the rules are laid out (the netfilter layout a per-app firewall needs, written for this app; the chain names are this app's own):
 * <pre>
 *   filter OUTPUT  -j atbx                       (inserted first, so Android's own chains see only what the firewall lets through)
 *   atbx:          lo -> RETURN; [Tor apps]; Wi-Fi interfaces -> atbx-lan (LAN ranges) / atbx-wifi; mobile interfaces -> atbx-roam or atbx-mobile;
 *                  VPN interfaces -> atbx-vpn; anything else RETURN
 *   atbx-wifi ...: allow list: each allowed uid -> RETURN, then -> atbx-reject   /   block list: each blocked uid -> atbx-reject
 *   atbx-reject:   [LOG "{ATBX}"], then REJECT (or DROP)
 *   filter INPUT   -j atbx-in       (only with "Block inbound": lo and established connections pass, new inbound connections are rejected)
 *   filter FORWARD -j atbx-fwd      (only with "Block tethered devices": traffic from the hotspot interfaces is rejected)
 *   nat OUTPUT     -j atbx-tor      (only with Tor apps: their DNS to port 5400 and TCP to port 9040 of Orbot's transparent proxy)
 * </pre>
 * Every chain of this app starts with "atbx" and nothing else is touched, so "Flush" deletes this app's chains and leaves Android's
 * (bw_*, fw_*, oem_*, st_*) exactly as they were. Success is read back ({@link #statusScript}, {@link #parseStatus}), never taken from a command's text.
 */
public final class FirewallRules {

    /** Prefix of every chain this app owns. */
    public static final String CHAIN = "atbx";
    /** The prefix the LOG target writes before a rejected packet's line (dmesg). */
    public static final String LOG_PREFIX = "{ATBX}";
    /** Orbot's transparent proxy ports (the Tor project's documented defaults). */
    public static final int TOR_TCP_PORT = 9040, TOR_DNS_PORT = 5400, TOR_SOCKS_PORT = 9050, TOR_HTTP_PORT = 8118;
    /** Where the boot script is kept inside the app's files directory. */
    public static final String BOOT_SCRIPT = "firewall/boot.sh";

    /** The network kinds a rule can name, in the order the page shows them. */
    public static final String[] NETS = {"wifi", "mobile", "roam", "vpn", "lan", "tor"};

    // Interface names by network kind, as the kernel names them on the phones this app knows (the "+" is iptables' wildcard).
    static final String[] IF_WIFI = {"tiwlan+", "wlan+", "eth+", "ra+", "bnep+", "wifi+"};
    static final String[] IF_MOBILE = {"rmnet+", "rmnet_data+", "rmnet_ipa+", "r_rmnet_data+", "v4-rmnet+", "v4-rmnet_data+", "pdp+", "ppp0", "uwbr+",
            "wimax+", "vsnet+", "ccmni+", "ccemni+", "cc2mni+", "usb+", "wwan+", "cdma_rmnet+", "clat4+", "bond1+", "rmnet_smux+", "ccinet+", "seth_w+"};
    static final String[] IF_VPN = {"tun+", "tap+", "ppp+", "wg+"};
    static final String[] IF_TETHER = {"rndis+", "bt-pan", "ap+", "swlan+", "wlan1", "softap+"};
    static final String[] LAN4 = {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32"};
    static final String[] LAN6 = {"fc00::/7", "fe80::/10", "ff00::/8"};

    /** A pseudo app of the list: traffic that belongs to no installed package. */
    public static final class Special {
        public final String key, name, why;
        public final int uid;
        Special(String key, int uid, String name, String why) { this.key = key; this.uid = uid; this.name = name; this.why = why; }
    }

    /** uid -11 is this engine's own mark for packets without an owner (the kernel's: ICMP, some tethering, parts of IPv6). */
    public static final int UID_KERNEL = -11;

    public static final Special[] SPECIALS = {
            new Special("kernel", UID_KERNEL, "Linux kernel", "Packets no app owns: ping, parts of IPv6, tethering. Allow it when LAN discovery or IPv6 breaks."),
            new Special("root", 0, "Root (UID 0)", "Processes running as root: su shells, some vendor daemons."),
            new Special("system", 1000, "Android system (UID 1000)", "The system server: time sync (NTP), captive portal checks, carrier setup, Find My Device."),
            new Special("dns", 1051, "DNS resolver (UID 1051)", "Android's own resolver answers every app's name lookups. Blocked, no app resolves a name, so an allow list usually keeps it."),
            new Special("media", 1013, "Media server (UID 1013)", "Streams for older players and online radio. Allow it when a stream stops."),
            new Special("vpn", 1016, "VPN networking (UID 1016)", "The system's VPN plumbing (not the VPN app itself)."),
            new Special("drm", 1019, "DRM (UID 1019)", "Licence checks for protected video and music."),
            new Special("mdns", 1020, "mDNS (UID 1020)", "Local device discovery: casting, printers, AirPlay-style finders."),
            new Special("gps", 1021, "GPS (UID 1021)", "Assisted GPS downloads (faster first fix)."),
            new Special("clat", 1029, "464XLAT (UID 1029)", "IPv4 over an IPv6-only mobile network. Block it and IPv4 sites fail on such networks."),
            new Special("shell", 2000, "ADB shell (UID 2000)", "Commands run over adb or Shizuku as the shell user."),
            new Special("nobody", 9999, "Nobody (UID 9999)", "Unprivileged helpers (some tethering and test daemons)."),
    };

    public static Special special(String key) {
        for (Special s : SPECIALS) if (s.key.equals(key)) return s;
        return null;
    }

    /** What the page sends: the whole rule set for one apply, already resolved to uids. */
    public static final class Cfg {
        /** "allow": only ticked apps get out (AFWall's whitelist); "block": ticked apps are stopped (blacklist). */
        public String mode = "allow";
        public boolean lan, vpn, roam, tor, ipv6, log, drop, inbound, tether;
        /** Whether the phone is roaming right now (the page asks Java; decides which mobile chain the mobile interfaces go to). */
        public boolean roamingNow;
        /** The lines run before this app's rules are built and after (the user's own iptables lines). */
        public String pre = "", post = "";
        /** uid -> the network kinds ticked for it (allow list: allowed; block list: blocked). */
        public final TreeMap<Integer, Set<String>> uids = new TreeMap<Integer, Set<String>>();
        /** The app's own uid, kept reachable in an allow list by default so updates and stores keep working (the page decides, this only records it). */
        public int selfUid = -1;

        Set<String> ticked(String net) {
            Set<String> out = new LinkedHashSet<String>();
            for (Map.Entry<Integer, Set<String>> e : uids.entrySet()) if (e.getValue().contains(net)) out.add(String.valueOf(e.getKey()));
            return out;
        }
    }

    private static final Pattern UID_KEY = Pattern.compile("^-?\\d{1,7}$");

    /** Parses and validates the page's JSON; a bad uid or an unknown network kind is an error (nothing half-applied). */
    public static Cfg parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Cfg c = new Cfg();
        String mode = o.optString("mode", "allow");
        if (!"allow".equals(mode) && !"block".equals(mode)) throw new JSONException("mode must be allow or block");
        c.mode = mode;
        c.lan = o.optBoolean("lan"); c.vpn = o.optBoolean("vpn"); c.roam = o.optBoolean("roam"); c.tor = o.optBoolean("tor");
        c.ipv6 = o.optBoolean("ipv6"); c.log = o.optBoolean("log"); c.drop = o.optBoolean("drop"); c.inbound = o.optBoolean("inbound");
        c.tether = o.optBoolean("tether"); c.roamingNow = o.optBoolean("roamingNow");
        c.pre = cleanScript(o.optString("pre", "")); c.post = cleanScript(o.optString("post", ""));
        c.selfUid = o.optInt("selfUid", -1);
        JSONObject u = o.optJSONObject("uids");
        if (u != null) {
            java.util.Iterator<String> it = u.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!UID_KEY.matcher(k).matches()) throw new JSONException("not a uid: " + k);
                int uid = Integer.parseInt(k);
                if (uid < 0 && uid != UID_KERNEL) throw new JSONException("not a uid: " + k);
                JSONArray nets = u.optJSONArray(k);
                Set<String> set = new LinkedHashSet<String>();
                if (nets != null) for (int i = 0; i < nets.length(); i++) {
                    String n = nets.getString(i);
                    if (Arrays.asList(NETS).indexOf(n) < 0) throw new JSONException("unknown network: " + n);
                    set.add(n);
                }
                c.uids.put(uid, set);
            }
        }
        return c;
    }

    /** The user's own lines: CR removed, a line that is only white space dropped; the shell runs them as they are (they are root's own commands). */
    static String cleanScript(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (String line : s.replace("\r", "").split("\n")) if (!line.trim().isEmpty()) b.append(line).append('\n');
        return b.toString();
    }

    // ---- the scripts ----

    private static final String[] CHAINS = {"atbx", "atbx-wifi", "atbx-lan", "atbx-mobile", "atbx-roam", "atbx-vpn", "atbx-reject", "atbx-in", "atbx-fwd"};

    /** One iptables binary with the wait flag (a second iptables holding the xtables lock makes the first one wait rather than fail). */
    static String bin(boolean v6) { return (v6 ? "ip6tables" : "iptables") + " -w 5"; }

    /**
     * The script that applies the configuration: this app's chains are (re)built from nothing, the jumps put first in OUTPUT (and INPUT, FORWARD,
     * nat OUTPUT when asked), while Android's own chains stay. It ends with the status lines of {@link #statusScript}, so one run both applies
     * and reads back.
     */
    public static String applyScript(Cfg c) {
        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n# ADB/Root Toolbox - Android Firewall\nexport PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH\n");
        s.append("command -v iptables >/dev/null 2>&1 || { echo '__ATBX_NOBIN__'; exit 0; }\n");
        if (!c.pre.isEmpty()) s.append("# --- your lines, before ---\n").append(c.pre).append("# --- end ---\n");
        build(s, c, false);
        if (c.ipv6) build(s, c, true); else teardown(s, true);
        if (!c.post.isEmpty()) s.append("# --- your lines, after ---\n").append(c.post).append("# --- end ---\n");
        s.append("echo '__ATBX_APPLIED__'\n");
        s.append(statusScript());
        return s.toString();
    }

    private static void build(StringBuilder s, Cfg c, boolean v6) {
        String I = bin(v6);
        String[] lan = v6 ? LAN6 : LAN4;
        // chains: made when missing, then emptied (an earlier apply's rules go, the jump from OUTPUT is renewed below)
        for (String ch : CHAINS) s.append(I).append(" -N ").append(ch).append(" 2>/dev/null\n").append(I).append(" -F ").append(ch).append('\n');
        s.append("while ").append(I).append(" -D OUTPUT -j atbx 2>/dev/null; do :; done\n");
        s.append(I).append(" -I OUTPUT 1 -j atbx\n");
        // the reject chain: an optional log line (rate limited: a chatty app must not flood the kernel log), then REJECT or DROP
        if (c.log) s.append(I).append(" -A atbx-reject -m limit --limit 1000/min --limit-burst 200 -j LOG --log-prefix \"").append(LOG_PREFIX).append(" \" --log-uid --log-level 4\n");
        s.append(I).append(" -A atbx-reject -j ").append(c.drop ? "DROP" : v6 ? "REJECT --reject-with icmp6-port-unreachable" : "REJECT --reject-with icmp-port-unreachable").append('\n');
        // the main chain: loopback first
        s.append(I).append(" -A atbx -o lo -j RETURN\n");
        // Tor apps: only Orbot's local ports and DNS, in the filter table; the nat table redirects below (IPv4 only: Orbot's transparent proxy is IPv4)
        if (c.tor && !v6) {
            for (String uid : c.ticked("tor")) {
                if (uid.equals(String.valueOf(UID_KERNEL))) continue; // packets no app owns cannot go through Orbot, and iptables refuses the pseudo-uid
                s.append(I).append(" -A atbx -m owner --uid-owner ").append(uid).append(" -d 127.0.0.1 -p tcp -m multiport --dports ")
                        .append(TOR_TCP_PORT).append(',').append(TOR_SOCKS_PORT).append(',').append(TOR_HTTP_PORT).append(" -j RETURN\n");
                s.append(I).append(" -A atbx -m owner --uid-owner ").append(uid).append(" -d 127.0.0.1 -p udp --dport ").append(TOR_DNS_PORT).append(" -j RETURN\n");
                s.append(I).append(" -A atbx -m owner --uid-owner ").append(uid).append(" -j atbx-reject\n");
            }
        }
        // Wi-Fi interfaces: the LAN ranges to the LAN chain when LAN is a column of its own, the rest to the Wi-Fi chain
        for (String itf : IF_WIFI) {
            // a LAN packet is decided by atbx-lan alone: what that chain lets through RETURNs here and leaves atbx on the next line, so the
            // Wi-Fi chain never sees it (RETURN cannot leave two chains at once; a LAN tick without a Wi-Fi tick must still work)
            if (c.lan) for (String range : lan) {
                s.append(I).append(" -A atbx -o ").append(itf).append(" -d ").append(range).append(" -j atbx-lan\n");
                s.append(I).append(" -A atbx -o ").append(itf).append(" -d ").append(range).append(" -j RETURN\n");
            }
            s.append(I).append(" -A atbx -o ").append(itf).append(" -j atbx-wifi\n");
        }
        // mobile interfaces: the roaming chain while the phone roams (and Roaming is a column), the mobile chain otherwise
        String mobileChain = (c.roam && c.roamingNow) ? "atbx-roam" : "atbx-mobile";
        for (String itf : IF_MOBILE) s.append(I).append(" -A atbx -o ").append(itf).append(" -j ").append(mobileChain).append('\n');
        // VPN interfaces: filtered only when VPN is a column; otherwise the VPN app's tunnel decides
        if (c.vpn) for (String itf : IF_VPN) s.append(I).append(" -A atbx -o ").append(itf).append(" -j atbx-vpn\n");
        // the per-network chains
        chain(s, I, c, "atbx-wifi", "wifi");
        if (c.lan) chain(s, I, c, "atbx-lan", "lan");
        chain(s, I, c, "atbx-mobile", "mobile");
        if (c.roam) chain(s, I, c, "atbx-roam", "roam");
        if (c.vpn) chain(s, I, c, "atbx-vpn", "vpn");
        // inbound: lo and answers to the phone's own connections pass, new inbound connections do not
        s.append("while ").append(I).append(" -D INPUT -j atbx-in 2>/dev/null; do :; done\n");
        if (c.inbound) {
            s.append(I).append(" -I INPUT 1 -j atbx-in\n");
            s.append(I).append(" -A atbx-in -i lo -j RETURN\n");
            s.append(I).append(" -A atbx-in -m state --state ESTABLISHED,RELATED -j RETURN\n");
            s.append(I).append(" -A atbx-in -j atbx-reject\n");
        }
        // tethered devices: what comes in from a hotspot interface and is forwarded out is rejected
        s.append("while ").append(I).append(" -D FORWARD -j atbx-fwd 2>/dev/null; do :; done\n");
        if (c.tether) {
            s.append(I).append(" -I FORWARD 1 -j atbx-fwd\n");
            for (String itf : IF_TETHER) s.append(I).append(" -A atbx-fwd -i ").append(itf).append(" -j atbx-reject\n");
        }
        // Tor: the nat redirects (IPv4)
        if (!v6) {
            s.append("while ").append(I).append(" -t nat -D OUTPUT -j atbx-tor 2>/dev/null; do :; done\n");
            s.append(I).append(" -t nat -N atbx-tor 2>/dev/null\n").append(I).append(" -t nat -F atbx-tor\n");
            if (c.tor) {
                Set<String> torUids = new java.util.TreeSet<String>(c.ticked("tor"));
                torUids.remove(String.valueOf(UID_KERNEL));
                if (!torUids.isEmpty()) {
                    s.append(I).append(" -t nat -I OUTPUT 1 -j atbx-tor\n");
                    for (String uid : torUids) {
                        s.append(I).append(" -t nat -A atbx-tor -m owner --uid-owner ").append(uid).append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(TOR_DNS_PORT).append('\n');
                        s.append(I).append(" -t nat -A atbx-tor -m owner --uid-owner ").append(uid).append(" -p tcp --syn -j REDIRECT --to-ports ").append(TOR_TCP_PORT).append('\n');
                    }
                }
            } else {
                s.append(I).append(" -t nat -X atbx-tor 2>/dev/null\n");
            }
        }
    }

    /** One network chain. Allow list: ticked uids RETURN, everyone else is rejected. Block list: ticked uids are rejected, everyone else returns. */
    private static void chain(StringBuilder s, String I, Cfg c, String chain, String net) {
        boolean allow = "allow".equals(c.mode);
        String target = allow ? "RETURN" : "atbx-reject";
        for (String uid : c.ticked(net)) {
            if (uid.equals(String.valueOf(UID_KERNEL))) s.append(I).append(" -A ").append(chain).append(" -m owner ! --uid-owner 0-999999999 -j ").append(target).append('\n');
            else s.append(I).append(" -A ").append(chain).append(" -m owner --uid-owner ").append(uid).append(" -j ").append(target).append('\n');
        }
        if (allow) s.append(I).append(" -A ").append(chain).append(" -j atbx-reject\n");
    }

    /** Removes this app's jumps and chains of one family; Android's chains are not touched. */
    private static void teardown(StringBuilder s, boolean v6) {
        String I = bin(v6);
        s.append("if command -v ").append(v6 ? "ip6tables" : "iptables").append(" >/dev/null 2>&1; then\n");
        s.append("while ").append(I).append(" -D OUTPUT -j atbx 2>/dev/null; do :; done\n");
        s.append("while ").append(I).append(" -D INPUT -j atbx-in 2>/dev/null; do :; done\n");
        s.append("while ").append(I).append(" -D FORWARD -j atbx-fwd 2>/dev/null; do :; done\n");
        for (String ch : CHAINS) s.append(I).append(" -F ").append(ch).append(" 2>/dev/null\n");
        for (int i = CHAINS.length - 1; i >= 0; i--) s.append(I).append(" -X ").append(CHAINS[i]).append(" 2>/dev/null\n");
        if (!v6) {
            s.append("while ").append(I).append(" -t nat -D OUTPUT -j atbx-tor 2>/dev/null; do :; done\n");
            s.append(I).append(" -t nat -F atbx-tor 2>/dev/null\n").append(I).append(" -t nat -X atbx-tor 2>/dev/null\n");
        }
        s.append("fi\n");
    }

    /** The script that turns the firewall off: every chain of this app goes, for both families. Ends with the status lines. */
    public static String disableScript() {
        StringBuilder s = new StringBuilder("#!/system/bin/sh\nexport PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH\n");
        teardown(s, false);
        teardown(s, true);
        s.append("echo '__ATBX_CLEARED__'\n").append(statusScript());
        return s.toString();
    }

    /** Prints the phone's state: whether the jump is in place, how many rules each of this app's chains holds, and whether the binaries exist. */
    public static String statusScript() {
        return "export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH\n"
                + "echo '__ATBX_STATUS__'\n"
                + "echo \"bin4=$(command -v iptables >/dev/null 2>&1 && echo 1 || echo 0)\"\n"
                + "echo \"bin6=$(command -v ip6tables >/dev/null 2>&1 && echo 1 || echo 0)\"\n"
                + "echo \"jump4=$(iptables -w 5 -S OUTPUT 2>/dev/null | grep -c -- '-j atbx$')\"\n"
                + "echo \"jump6=$(ip6tables -w 5 -S OUTPUT 2>/dev/null | grep -c -- '-j atbx$')\"\n"
                + "echo \"in4=$(iptables -w 5 -S INPUT 2>/dev/null | grep -c -- '-j atbx-in$')\"\n"
                + "echo \"fwd4=$(iptables -w 5 -S FORWARD 2>/dev/null | grep -c -- '-j atbx-fwd$')\"\n"
                + "echo \"tor4=$(iptables -w 5 -t nat -S OUTPUT 2>/dev/null | grep -c -- '-j atbx-tor$')\"\n"
                + "for c in atbx atbx-wifi atbx-lan atbx-mobile atbx-roam atbx-vpn atbx-reject; do echo \"n4_$c=$(iptables -w 5 -S $c 2>/dev/null | grep -c -- '^-A ')\"; done\n"
                + "echo \"n6_atbx=$(ip6tables -w 5 -S atbx 2>/dev/null | grep -c -- '^-A ')\"\n"
                + "echo \"uid=$(id -u 2>/dev/null)\"\n"
                + "echo '__ATBX_END__'\n";
    }

    /** The script that lists this app's active rules as iptables prints them (the Active rules card). */
    public static String listScript() {
        return "export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH\n"
                + "for c in atbx atbx-wifi atbx-lan atbx-mobile atbx-roam atbx-vpn atbx-reject atbx-in atbx-fwd; do iptables -w 5 -S $c 2>/dev/null; done\n"
                + "iptables -w 5 -t nat -S atbx-tor 2>/dev/null\n"
                + "echo '# IPv6'\n"
                + "for c in atbx atbx-wifi atbx-lan atbx-mobile atbx-roam atbx-vpn atbx-reject atbx-in atbx-fwd; do ip6tables -w 5 -S $c 2>/dev/null; done\n";
    }

    /** The script that reads the kernel log for this app's LOG lines (newest last); with clear, the ring buffer is emptied afterwards (root only). */
    public static String logScript(boolean clear) {
        return "export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH\n"
                + "dmesg 2>/dev/null | grep -F '" + LOG_PREFIX + "' | tail -n 400\n"
                + (clear ? "dmesg -c >/dev/null 2>&1\n" : "")
                + "echo '__ATBX_LOGEND__'\n";
    }

    /** What the status lines said. */
    public static final class Status {
        public boolean bin4, bin6, jump4, jump6, inbound, tether, tor, applied, cleared, noBin;
        public int rules4, rules6, uid = -1;
        public final Map<String, Integer> chains = new LinkedHashMap<String, Integer>();

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("bin4", bin4); o.put("bin6", bin6); o.put("jump4", jump4); o.put("jump6", jump6); o.put("inbound", inbound);
            o.put("tether", tether); o.put("tor", tor); o.put("applied", applied); o.put("cleared", cleared); o.put("noBin", noBin);
            o.put("rules4", rules4); o.put("rules6", rules6); o.put("uid", uid);
            o.put("on", jump4 && rules4 > 0);
            JSONObject ch = new JSONObject();
            for (Map.Entry<String, Integer> e : chains.entrySet()) ch.put(e.getKey(), e.getValue());
            o.put("chains", ch);
            return o;
        }
    }

    public static Status parseStatus(String out) {
        Status st = new Status();
        if (out == null) return st;
        st.applied = out.contains("__ATBX_APPLIED__");
        st.cleared = out.contains("__ATBX_CLEARED__");
        st.noBin = out.contains("__ATBX_NOBIN__");
        int at = out.indexOf("__ATBX_STATUS__");
        if (at < 0) return st;
        for (String line : out.substring(at).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            int n;
            try { n = Integer.parseInt(v); } catch (NumberFormatException e) { continue; }
            if (k.equals("bin4")) st.bin4 = n > 0;
            else if (k.equals("bin6")) st.bin6 = n > 0;
            else if (k.equals("jump4")) st.jump4 = n > 0;
            else if (k.equals("jump6")) st.jump6 = n > 0;
            else if (k.equals("in4")) st.inbound = n > 0;
            else if (k.equals("fwd4")) st.tether = n > 0;
            else if (k.equals("tor4")) st.tor = n > 0;
            else if (k.equals("uid")) st.uid = n;
            else if (k.startsWith("n4_")) { st.chains.put(k.substring(3), n); st.rules4 += n; }
            else if (k.equals("n6_atbx")) st.rules6 = n;
        }
        return st;
    }

    // ---- the log ----

    /** One rejected packet, as the kernel logged it. */
    public static final class LogEntry {
        public int uid = UID_KERNEL;
        public String iface = "", dst = "", proto = "", when = "";
        public int dport = -1;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("uid", uid); o.put("iface", iface); o.put("dst", dst); o.put("proto", proto); o.put("dport", dport); o.put("when", when);
            return o;
        }
    }

    private static final Pattern LOG_TIME = Pattern.compile("^(?:<\\d+>)?\\[\\s*([\\d.]+)\\]");

    /** Parses the dmesg lines the LOG target wrote. A line without a UID= field is the kernel's own packet. */
    public static List<LogEntry> parseLog(String out) {
        List<LogEntry> list = new ArrayList<LogEntry>();
        if (out == null) return list;
        for (String line : out.split("\n")) {
            if (!line.contains(LOG_PREFIX)) continue;
            LogEntry e = new LogEntry();
            Matcher t = LOG_TIME.matcher(line.trim());
            if (t.find()) e.when = t.group(1);
            for (String tok : line.substring(line.indexOf(LOG_PREFIX) + LOG_PREFIX.length()).trim().split("\\s+")) {
                int eq = tok.indexOf('=');
                if (eq <= 0) continue;
                String k = tok.substring(0, eq), v = tok.substring(eq + 1);
                if (k.equals("OUT")) e.iface = v;
                else if (k.equals("DST")) e.dst = v;
                else if (k.equals("PROTO")) e.proto = v;
                else if (k.equals("DPT")) { try { e.dport = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                else if (k.equals("UID")) { try { e.uid = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
            }
            list.add(e);
        }
        return list;
    }

    /** The log grouped by uid: how many packets, the last destination and port, newest first. */
    public static JSONArray groupLog(List<LogEntry> entries) throws JSONException {
        Map<Integer, JSONObject> by = new LinkedHashMap<Integer, JSONObject>();
        for (LogEntry e : entries) {
            JSONObject g = by.remove(e.uid);       // taken out and put back: the map's order becomes "last seen", so the reverse below is newest first
            if (g == null) {
                g = new JSONObject();
                g.put("uid", e.uid); g.put("count", 0);
            }
            by.put(e.uid, g);
            g.put("count", g.getInt("count") + 1);
            g.put("dst", e.dst); g.put("dport", e.dport); g.put("proto", e.proto); g.put("iface", e.iface); g.put("when", e.when);
        }
        List<JSONObject> list = new ArrayList<JSONObject>(by.values());
        Collections.reverse(list);
        JSONArray a = new JSONArray();
        for (JSONObject g : list) a.put(g);
        return a;
    }

    /** The boot script: the last applied script, with a delay in front, so the rules come back before most apps start talking. */
    public static String bootScript(String applyScript, int delaySec) {
        int d = Math.max(0, Math.min(300, delaySec));
        return "#!/system/bin/sh\n# ADB/Root Toolbox - Android Firewall, run at boot\n" + (d > 0 ? "sleep " + d + "\n" : "") + applyScript;
    }
}
