package com.bloatware.bingblop;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*;
public class ParityDump2 {
    static String unhex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return new String(b, StandardCharsets.UTF_8); }
    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        for (String line : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) {
            String[] p = line.split(" ", -1);
            String v = p.length > 1 ? unhex(p[1]) : "";
            if (p[0].equals("K")) out.println(SettingsDb.keyProblem(v) == null ? "0" : "1");
            else if (p[0].equals("V")) out.println(SettingsDb.valueProblem(v) == null ? "0" : "1");
            else if (p[0].equals("B")) out.println(SettingsDb.quotedBytes(v));
            else if (p[0].equals("H")) { String h = OverlayRules.normalizeHex(v); out.println(h == null ? "null" : h); }
            else if (p[0].equals("I")) out.println(OverlayRules.idProblem(v) == null ? "0" : "1");
        }
    }
}
