package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code /proc/net/dev}: two header lines (column titles split across two rows, with no fixed wording this relies on),
 * then one line per interface — a name, a colon, and 16 whitespace-separated counters in the fixed order {@code rx_bytes
 * rx_packets rx_errs rx_drop rx_fifo rx_frame rx_compressed rx_multicast tx_bytes tx_packets tx_errs tx_drop tx_fifo tx_colls
 * tx_carrier tx_compressed}. Pure Java, free of Android imports, so it can be tested off the device.
 *
 * <p>Rather than assume the header is always exactly two lines (or look for particular header wording, which is not part of
 * any stable contract), a line is only read as interface data when it has exactly one {@code ':'} with a non-empty name
 * before it and exactly 16 numbers after it; anything else — either header line, a blank line, a line some odd kernel build
 * truncated or padded — is treated as junk and skipped. The interface name is trimmed, so a short name with its usual leading
 * padding ({@code "    lo:"}) and a long name with none ({@code "rmnet_data0:"}) read the same way.
 *
 * <p>Defensive throughout: garbled, truncated or entirely blank text never throws and never comes back null — the worst it
 * does is an empty interface list.
 */
final class NetStats {

    private NetStats() {}

    /** One interface's line. Only the four counters the app shows are kept; the other 12 of the 16 fields are read (so the
     *  16-field check still applies to them) and then dropped. */
    static final class Iface {
        public String name;
        public long rxBytes, rxPackets, txBytes, txPackets;
    }

    /** Every interface line that parsed, in the order {@code /proc/net/dev} printed them. */
    static final class Reading {
        public List<Iface> ifaces = new ArrayList<Iface>();
    }

    /** One interface's throughput between two readings of it, in bytes per second. */
    static final class Rates {
        public double rxBytesPerSec, txBytesPerSec;
    }

    /**
     * Reads every interface line in {@code /proc/net/dev}: a line is interface data only when it has exactly one {@code ':'}
     * with a non-empty name before it and exactly 16 whitespace-separated numbers after it (a non-numeric token among them
     * reads as 0 rather than disqualifying the line). Both header lines fail that test on their own (no ':' at all) and are
     * skipped without any special-casing of header line count or wording. Blank or garbage text — or text with no interface
     * lines in it at all — comes back as an empty list, never null, never a thrown exception.
     */
    static Reading parse(String procNetDevText) {
        Reading r = new Reading();
        if (procNetDevText == null) return r;
        for (String raw : procNetDevText.split("\r?\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.indexOf(':', colon + 1) >= 0) continue;        // more than one ':': a header line, not interface data
            String name = line.substring(0, colon).trim();
            if (name.isEmpty()) continue;
            String rest = line.substring(colon + 1).trim();
            String[] f = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            if (f.length != 16) continue;                           // not the fixed rx+tx field count: junk or a header row
            Iface iface = new Iface();
            iface.name = name;
            iface.rxBytes = field(f, 0);
            iface.rxPackets = field(f, 1);
            iface.txBytes = field(f, 8);
            iface.txPackets = field(f, 9);
            r.ifaces.add(iface);
        }
        return r;
    }

    private static long field(String[] f, int i) {
        try {
            return Long.parseLong(f[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The interface named {@code name}, or null when the reading has none by that name. */
    static Iface find(Reading r, String name) {
        if (r == null || r.ifaces == null || name == null) return null;
        for (Iface i : r.ifaces) if (name.equals(i.name)) return i;
        return null;
    }

    /** The sum of every interface's {@code rxBytes}; an interface literally named {@code lo} is left out unless
     *  {@code includeLoopback} is true. */
    static long totalRxBytes(Reading r, boolean includeLoopback) {
        if (r == null || r.ifaces == null) return 0;
        long sum = 0;
        for (Iface i : r.ifaces) if (includeLoopback || !"lo".equals(i.name)) sum += i.rxBytes;
        return sum;
    }

    /** The sum of every interface's {@code txBytes}; an interface literally named {@code lo} is left out unless
     *  {@code includeLoopback} is true. */
    static long totalTxBytes(Reading r, boolean includeLoopback) {
        if (r == null || r.ifaces == null) return 0;
        long sum = 0;
        for (Iface i : r.ifaces) if (includeLoopback || !"lo".equals(i.name)) sum += i.txBytes;
        return sum;
    }

    /**
     * Bytes per second in each direction between two readings of the *same* interface: {@code (after - before) * 1000 /
     * elapsedMs}. Each direction is clamped to >= 0 on its own, so a counter that went backwards — the interface went down
     * and came back up with a lower count, rather than a real wraparound of a 64-bit kernel counter — reads as 0.0 instead of
     * a huge or negative number. {@code elapsedMs <= 0} answers {@code {0, 0}} outright rather than dividing by zero or a
     * negative span; {@code before}/{@code after} being null does the same.
     */
    static Rates rate(Iface before, Iface after, long elapsedMs) {
        Rates out = new Rates();
        if (before == null || after == null || elapsedMs <= 0) return out;
        long rxDelta = after.rxBytes - before.rxBytes;
        long txDelta = after.txBytes - before.txBytes;
        if (rxDelta > 0) out.rxBytesPerSec = rxDelta * 1000.0 / elapsedMs;
        if (txDelta > 0) out.txBytesPerSec = txDelta * 1000.0 / elapsedMs;
        return out;
    }
}
