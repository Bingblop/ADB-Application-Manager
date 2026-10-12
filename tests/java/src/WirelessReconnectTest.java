package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.List;

/** Finding the current Wireless Debugging port in adb's mDNS listing after the saved one went stale. */
public class WirelessReconnectTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static List<String> l(String... a) { return Arrays.asList(a); }

  public static void main(String[] x) {
    String raw = "List of discovered mdns services\n"
        + "adb-R5CT1234ABC-xYz1Qa\t_adb-tls-connect._tcp.\t192.168.1.20:41233\n"
        + "adb-R5CT1234ABC\t_adb-tls-pairing._tcp.\t192.168.1.20:37771\n"
        + "adb-OTHERPHONE-q9w8e7\t_adb-tls-connect._tcp.\t192.168.1.31:38000\n";

    List<String> eps = WirelessReconnect.connectEndpoints(raw);
    is("both connect services are listed, the pairing one is not", eps.equals(l("192.168.1.20:41233", "192.168.1.31:38000")));
    is("empty and null listings give nothing", WirelessReconnect.connectEndpoints("").isEmpty() && WirelessReconnect.connectEndpoints(null).isEmpty());
    is("a line without a port is skipped", WirelessReconnect.connectEndpoints("a\t_adb-tls-connect._tcp.\tnoport\n").isEmpty());
    is("a port out of range is skipped", WirelessReconnect.connectEndpoints("a\t_adb-tls-connect._tcp.\t10.0.0.1:70000\n").isEmpty());
    is("a repeated service is listed once", WirelessReconnect.connectEndpoints(raw + raw).size() == 2);
    is("CRLF line ends do not end up in the port", WirelessReconnect.connectEndpoints("a\t_adb-tls-connect._tcp.\t10.0.0.5:4000\r\n").equals(l("10.0.0.5:4000")));

    // the phone this app controls over its Wi-Fi address
    is("same host, new port", WirelessReconnect.candidates(eps, "192.168.1.20", 40001, "").equals(l("192.168.1.20:41233")));
    is("another phone's service is never offered", !WirelessReconnect.candidates(eps, "192.168.1.20", 40001, "").contains("192.168.1.31:38000"));
    is("the stale port itself is not offered again", WirelessReconnect.candidates(eps, "192.168.1.20", 41233, "").isEmpty());
    is("no listing entry for the host gives nothing", WirelessReconnect.candidates(eps, "192.168.1.99", 40001, "").isEmpty());

    // this phone, saved as loopback: its own Wi-Fi address stands for it
    is("loopback 127.0.0.1 matches this phone's Wi-Fi address", WirelessReconnect.candidates(eps, "127.0.0.1", 40001, "192.168.1.20").equals(l("192.168.1.20:41233")));
    is("localhost matches this phone's Wi-Fi address", WirelessReconnect.candidates(eps, "localhost", 40001, "192.168.1.20").equals(l("192.168.1.20:41233")));
    is("an empty saved host counts as this phone", WirelessReconnect.candidates(eps, "", 40001, "192.168.1.20").equals(l("192.168.1.20:41233")));
    is("loopback without a known Wi-Fi address matches nothing", WirelessReconnect.candidates(eps, "127.0.0.1", 40001, "").isEmpty());
    is("a remote saved host is not widened to this phone's address", WirelessReconnect.candidates(eps, "192.168.1.31", 1, "192.168.1.20").equals(l("192.168.1.31:38000")));

    // bad input
    is("null endpoints give nothing", WirelessReconnect.candidates(null, "10.0.0.1", 1, "").isEmpty());
    is("null saved host is treated as this phone", WirelessReconnect.candidates(eps, null, 1, "192.168.1.20").equals(l("192.168.1.20:41233")));
    is("several ports on the host are all kept in order", WirelessReconnect.candidates(l("10.0.0.2:5001", "10.0.0.2:5002"), "10.0.0.2", 5000, "").equals(l("10.0.0.2:5001", "10.0.0.2:5002")));

    // endpoint pieces
    is("hostOf strips IPv6 brackets", WirelessReconnect.hostOf("[fe80::1]:5555").equals("fe80::1"));
    is("hostOf and splitPort of an IPv4 endpoint", WirelessReconnect.hostOf("1.2.3.4:5555").equals("1.2.3.4") && WirelessReconnect.splitPort("1.2.3.4:5555") == 5555);
    is("splitPort refuses 0, text, a trailing colon and null", WirelessReconnect.splitPort("a:0") < 0 && WirelessReconnect.splitPort("a:x1") < 0 && WirelessReconnect.splitPort("a:") < 0 && WirelessReconnect.splitPort(null) < 0);
    is("hostOf of nothing is empty", WirelessReconnect.hostOf(null).isEmpty() && WirelessReconnect.hostOf(":5555").isEmpty() && WirelessReconnect.hostOf("nocolon").isEmpty());

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
