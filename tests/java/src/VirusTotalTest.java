package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Tests the SHA-256 file hashing utility used before sending files to VirusTotal. */
public class VirusTotalTest {
    static int fails = 0, n = 0;

    static void check(String what, boolean ok) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        } else {
            System.out.println("ok   " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("vtsha256", ".tmp");
        try {
            // empty file
            check("sha256 of empty file", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(VirusTotal.sha256(f)));

            // short file
            Files.write(f.toPath(), "abc".getBytes("UTF-8"));
            check("sha256 of 'abc'", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(VirusTotal.sha256(f)));

            // larger than buffer file (buffer is 65536)
            byte[] large = new byte[100000];
            Arrays.fill(large, (byte) 'a');
            Files.write(f.toPath(), large);
            check("sha256 of 100k 'a's (across 64K buffer boundary)", "6d1cf22d7cc09b085dfc25ee1a1f3ae0265804c607bc2074ad253bcc82fd81ee".equals(VirusTotal.sha256(f)));

        } finally {
            f.delete();
        }

        // missing file error
        boolean missingThrew = false;
        try {
            VirusTotal.sha256(new File("/does/not/exist/at/all/12345.xyz"));
        } catch (Exception e) {
            missingThrew = true;
        }
        check("missing file throws", missingThrew);

        System.out.println(fails == 0 ? "PASS VirusTotalTest: " + n + " checks" : "FAILED VirusTotalTest: " + fails + " of " + n);
        if (fails != 0) System.exit(1);
    }
}
