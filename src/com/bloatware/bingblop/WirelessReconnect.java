package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * Wireless Debugging picks a new random port every time it is switched off and on, so the port this app saved goes stale. adb's own mDNS
 * browser lists the phone's current {@code _adb-tls-connect} endpoint; this class turns that listing into the endpoints worth trying in
 * place of the saved one. Pure text rules, no Android classes, so they run in tests/java.
 */
final class WirelessReconnect {
    private WirelessReconnect() {}

    /** Every {@code ip:port} that {@code adb mdns services} lists for a {@code _adb-tls-connect} service, in listing order, without repeats. */
    static List<String> connectEndpoints(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            if (!line.contains("_adb-tls-connect")) continue;
            String[] parts = line.trim().split("\\s+");
            String ep = parts[parts.length - 1];
            if (splitPort(ep) <= 0 || hostOf(ep).isEmpty() || out.contains(ep)) continue;
            out.add(ep);
        }
        return out;
    }

    /**
     * The endpoints to try after {@code savedHost:stalePort} would not connect: those on the saved host (or, when the saved host is this
     * phone's loopback address, on {@code deviceIp}) with a port other than the stale one. Another phone's service is never offered, so a
     * stale port can not turn into a connection to a different device.
     */
    static List<String> candidates(List<String> endpoints, String savedHost, int stalePort, String deviceIp) {
        List<String> out = new ArrayList<String>();
        if (endpoints == null) return out;
        String want = savedHost == null ? "" : savedHost.trim();
        boolean loopback = want.isEmpty() || want.equals("localhost") || want.startsWith("127.") || want.equals("::1");
        for (String ep : endpoints) {
            int port = splitPort(ep);
            String host = hostOf(ep);
            if (port <= 0 || port == stalePort || host.isEmpty()) continue;
            boolean mine = host.equals(want)
                    || (loopback && deviceIp != null && !deviceIp.isEmpty() && host.equals(deviceIp));
            if (mine && !out.contains(ep)) out.add(ep);
        }
        return out;
    }

    /** {@code host} of {@code host:port}; an IPv6 address may be in brackets. */
    static String hostOf(String endpoint) {
        if (endpoint == null) return "";
        int i = endpoint.lastIndexOf(':');
        if (i <= 0) return "";
        String h = endpoint.substring(0, i);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h;
    }

    /** Port of {@code host:port}, or -1 when there is none or it is out of range. */
    static int splitPort(String endpoint) {
        if (endpoint == null) return -1;
        int i = endpoint.lastIndexOf(':');
        if (i < 0 || i == endpoint.length() - 1) return -1;
        String p = endpoint.substring(i + 1);
        for (int k = 0; k < p.length(); k++) if (p.charAt(k) < '0' || p.charAt(k) > '9') return -1;
        if (p.length() > 5) return -1;
        int v = Integer.parseInt(p);
        return v >= 1 && v <= 65535 ? v : -1;
    }
}
