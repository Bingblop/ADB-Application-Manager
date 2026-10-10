package com.bloatware.bingblop;

import java.util.Objects;

/** /proc/net/dev parsing: the two header lines skipped by shape (not by assuming a fixed header line count), long interface
 *  names with no leading space, lines with the wrong field count treated as junk, only lo present, find()/totals with and
 *  without loopback, and rate() including a counter reset and a non-positive elapsed time. */
public class NetStatsTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    static NetStats.Iface iface(String name, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        NetStats.Iface i = new NetStats.Iface();
        i.name = name; i.rxBytes = rxBytes; i.rxPackets = rxPackets; i.txBytes = txBytes; i.txPackets = txPackets;
        return i;
    }

    public static void main(String[] a) {
        // ---- a normal full sample: lo, wifi, mobile data and a dummy interface, after the two header lines ----
        String normal = "Inter-|   Receive                                                |  Transmit\n"
            + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
            + "    lo:  733384    4537    0    0    0     0          0         0   733384    4537    0    0    0     0       0          0\n"
            + "  wlan0: 48291823   34521    2    0    0     0          0        15 2918233   21044    0    0    0     0       0          0\n"
            + "rmnet_data0: 1234567    2345    0    0    0     0          0         0  234567     987    0    0    0     0       0          0\n"
            + " dummy0:       0       0    0    0    0     0          0         0       0       0    0    0    0     0       0          0\n";
        NetStats.Reading r = NetStats.parse(normal);
        eq("4 interfaces parsed (2 header lines skipped)", r.ifaces.size(), 4);
        eq("file order preserved: 1st", r.ifaces.get(0).name, "lo");
        eq("file order preserved: 2nd", r.ifaces.get(1).name, "wlan0");
        eq("file order preserved: 3rd", r.ifaces.get(2).name, "rmnet_data0");
        eq("file order preserved: 4th", r.ifaces.get(3).name, "dummy0");
        NetStats.Iface wlan0 = NetStats.find(r, "wlan0");
        is("find(wlan0) found it", wlan0 != null);
        eq("wlan0 rxBytes (1st of 16 fields)", wlan0.rxBytes, 48291823L);
        eq("wlan0 rxPackets (2nd field)", wlan0.rxPackets, 34521L);
        eq("wlan0 txBytes (9th field)", wlan0.txBytes, 2918233L);
        eq("wlan0 txPackets (10th field)", wlan0.txPackets, 21044L);
        eq("find() of an interface that isn't there", NetStats.find(r, "eth0"), null);
        eq("totalRxBytes excluding loopback", NetStats.totalRxBytes(r, false), 49526390L);
        eq("totalRxBytes including loopback", NetStats.totalRxBytes(r, true), 50259774L);
        eq("totalTxBytes excluding loopback", NetStats.totalTxBytes(r, false), 3152800L);
        eq("totalTxBytes including loopback", NetStats.totalTxBytes(r, true), 3886184L);

        // ---- missing optional: only lo is up ----
        String onlyLo = "Inter-|   Receive                                                |  Transmit\n"
            + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
            + "    lo:  1000     10    0    0    0     0          0         0    1000     10    0    0    0     0       0          0\n";
        r = NetStats.parse(onlyLo);
        eq("only lo: exactly one interface", r.ifaces.size(), 1);
        eq("only lo: name", r.ifaces.get(0).name, "lo");
        eq("only lo: totalRxBytes excluding loopback is 0 (lo is the only interface)", NetStats.totalRxBytes(r, false), 0L);
        eq("only lo: totalRxBytes including loopback", NetStats.totalRxBytes(r, true), 1000L);
        eq("only lo: totalTxBytes excluding loopback is 0", NetStats.totalTxBytes(r, false), 0L);
        eq("only lo: totalTxBytes including loopback", NetStats.totalTxBytes(r, true), 1000L);

        // ---- a long interface name with no leading space, a truncated line, a line with too many fields, and non-numeric
        //      tokens on an otherwise well-formed line: wrong field count is junk, but non-numeric fields alone are not ----
        String extra = "Inter-|   Receive                                                |  Transmit\n"
            + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
            + "ccmni0: 9988776   5544    0    0    0     0          0         3  1122334     998    0    0    0     0       0          0\n"
            + "truncated_iface: 123 456 789\n"
            + "toomany_iface: 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17\n"
            + "    lo:     50      1    0    0    0     0          0         0      50       1    0    0    0     0       0          0\n";
        r = NetStats.parse(extra);
        eq("long name with no leading space + truncated/too-many-fields lines skipped as junk: 2 interfaces", r.ifaces.size(), 2);
        eq("1st kept interface", r.ifaces.get(0).name, "ccmni0");
        eq("ccmni0 rxBytes", r.ifaces.get(0).rxBytes, 9988776L);
        eq("ccmni0 txBytes", r.ifaces.get(0).txBytes, 1122334L);
        eq("2nd kept interface", r.ifaces.get(1).name, "lo");
        is("the truncated line never became an interface", NetStats.find(r, "truncated_iface") == null);
        is("the too-many-fields line never became an interface", NetStats.find(r, "toomany_iface") == null);

        String nonNumeric = "weird0: a b c d e f g h i j k l m n o p\n";    // exactly 16 tokens, none of them numbers
        r = NetStats.parse(nonNumeric);
        eq("16 non-numeric tokens: the line is still kept (wrong field count is junk, non-numeric fields are not)", r.ifaces.size(), 1);
        NetStats.Iface weird0 = NetStats.find(r, "weird0");
        is("non-numeric tokens read as 0 rather than throwing", weird0 != null && weird0.rxBytes == 0 && weird0.rxPackets == 0 && weird0.txBytes == 0 && weird0.txPackets == 0);

        // ---- blank / garbage input ----
        for (String bad : new String[] {"", "   \n\n  ", "no colons or numbers here\njust text", "random: not numbers at all"}) {
            NetStats.Reading br = NetStats.parse(bad);
            is("blank/garbage [" + bad.replace("\n", "\\n") + "]: empty interface list, not null, not a crash", br.ifaces != null && br.ifaces.isEmpty());
        }
        NetStats.Reading nr = NetStats.parse(null);
        is("parse(null): empty list, not a crash", nr.ifaces != null && nr.ifaces.isEmpty());

        // ---- rate(): the normal forward case ----
        NetStats.Iface before = iface("wlan0", 1000, 5, 200, 2);
        NetStats.Iface after = iface("wlan0", 5000, 9, 1200, 6);
        NetStats.Rates rates = NetStats.rate(before, after, 2000);
        eq("rate: rx bytes/sec over 2s", rates.rxBytesPerSec, 2000.0);
        eq("rate: tx bytes/sec over 2s", rates.txBytesPerSec, 500.0);

        // ---- rate(): a counter reset (the interface went down and came back with a lower count) must read as 0, not negative ----
        NetStats.Iface highBefore = iface("wlan0", 1000000, 900, 500000, 400);
        NetStats.Iface resetAfter = iface("wlan0", 200, 1, 100, 1);
        rates = NetStats.rate(highBefore, resetAfter, 1000);
        eq("rate: a reset rx counter is 0.0, not a huge negative number", rates.rxBytesPerSec, 0.0);
        eq("rate: a reset tx counter is 0.0, not a huge negative number", rates.txBytesPerSec, 0.0);

        // ---- rate(): no change at all ----
        NetStats.Iface same = iface("wlan0", 5000, 9, 1200, 6);
        rates = NetStats.rate(same, same, 1000);
        eq("rate: identical readings give 0 rx", rates.rxBytesPerSec, 0.0);
        eq("rate: identical readings give 0 tx", rates.txBytesPerSec, 0.0);

        // ---- rate(): elapsedMs <= 0 must not divide by zero or go negative ----
        rates = NetStats.rate(before, after, 0);
        eq("rate: elapsedMs == 0 gives rx 0", rates.rxBytesPerSec, 0.0); eq("rate: elapsedMs == 0 gives tx 0", rates.txBytesPerSec, 0.0);
        rates = NetStats.rate(before, after, -500);
        eq("rate: negative elapsedMs gives rx 0", rates.rxBytesPerSec, 0.0); eq("rate: negative elapsedMs gives tx 0", rates.txBytesPerSec, 0.0);

        // ---- rate(): defensive against null interfaces ----
        rates = NetStats.rate(null, after, 1000);
        eq("rate(null, x, _): rx 0", rates.rxBytesPerSec, 0.0); eq("rate(null, x, _): tx 0", rates.txBytesPerSec, 0.0);
        rates = NetStats.rate(before, null, 1000);
        eq("rate(x, null, _): rx 0", rates.rxBytesPerSec, 0.0); eq("rate(x, null, _): tx 0", rates.txBytesPerSec, 0.0);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
