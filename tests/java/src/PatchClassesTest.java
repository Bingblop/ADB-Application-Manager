package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The inspector's "Patched / modified: ReVanced / Morphe" from the dex code (PatchClasses, used by ModDetect.deep). It searched every dex
 * byte for the words "revanced" and "morphe", so an app that only names them (a Morpheme class, a "not ReVanced" notice) and this app
 * itself (its own strings, and the Morphe patcher it bundles) were shown as patched. Now only a class the dex defines under app.revanced.*
 * or app.morphe.* counts, the patcher library left out. Hand-built dex files (TrackersTest.dex), and this app's own APK when there is one.
 */
public class PatchClassesTest {
    static int n = 0, fails = 0;
    static void check(String what, boolean ok, String extra) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (extra == null ? "" : " :: " + extra)); } }

    static File apk(File dir, String name, byte[]... dexes) throws Exception {
        File f = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("AndroidManifest.xml")); z.write(new byte[] {3, 0, 8, 0}); z.closeEntry();
            for (int i = 0; i < dexes.length; i++) {
                z.putNextEntry(new ZipEntry(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex"));
                z.write(dexes[i]);
                z.closeEntry();
            }
        }
        return f;
    }

    /** What ModDetect.deep hands over: every classes*.dex entry of the APK. */
    static Set<String> labels(File f) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        try (ZipFile zf = new ZipFile(f)) {
            List<ZipEntry> dex = new ArrayList<ZipEntry>();
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String low = ze.getName().toLowerCase(java.util.Locale.US);
                if (low.startsWith("classes") && low.endsWith(".dex")) dex.add(ze);
            }
            PatchClasses.scan(zf, dex, out);
        }
        return out;
    }

    static String blank(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("patchclasses").toFile();
        try {
            String[] none = {};
            Set<String> got = labels(apk(dir, "words.apk", TrackersTest.dex(
                new String[] {"com.worksap.nlp.sudachi.Morpheme", "com.example.shop.NotRevancedNotice", "com.example.MorpheusBrushes"},
                new String[] {"com.example.RevancedChecker"})));
            check("an app whose class names merely hold the words is not patched", got.isEmpty(), got.toString());

            got = labels(apk(dir, "revanced.apk", TrackersTest.dex(new String[] {"com.google.android.youtube.Main", "app.revanced.extension.shared.Utils"}, none)));
            check("a dex that defines ReVanced's extension classes: ReVanced", got.equals(new LinkedHashSet<String>(Arrays.asList("ReVanced"))), got.toString());

            got = labels(apk(dir, "integrations.apk", TrackersTest.dex(new String[] {"app.revanced.integrations.patches.VideoAdsPatch"}, none)));
            check("the older integrations package: ReVanced", got.equals(new LinkedHashSet<String>(Arrays.asList("ReVanced"))), got.toString());

            got = labels(apk(dir, "morphe.apk", TrackersTest.dex(new String[] {"com.reddit.frontpage.Main"}, none), TrackersTest.dex(new String[] {"app.morphe.extension.reddit.Utils"}, none)));
            check("a second dex that defines Morphe's extension classes: Morphe", got.equals(new LinkedHashSet<String>(Arrays.asList("Morphe"))), got.toString());

            got = labels(apk(dir, "both.apk", TrackersTest.dex(new String[] {"app.revanced.extension.shared.Utils", "app.morphe.extension.shared.Utils"}, none)));
            check("both", got.containsAll(Arrays.asList("ReVanced", "Morphe")) && got.size() == 2, got.toString());

            got = labels(apk(dir, "referenced.apk", TrackersTest.dex(new String[] {"com.example.Main"}, new String[] {"app.revanced.extension.shared.Utils", "app.morphe.extension.shared.Utils"})));
            check("classes only referenced, not defined, are no patch", got.isEmpty(), got.toString());

            got = labels(apk(dir, "patcher.apk", TrackersTest.dex(new String[] {"app.morphe.patcher.Patcher", "app.morphe.patcher.patch.BytecodePatch", "app.revanced.patcher.Patcher", "com.bloatware.bingblop.MorpheEngine"}, none)));
            check("an app that bundles the patcher library (a patcher tool, like this one) is not patched", got.isEmpty(), got.toString());

            byte[] broken = Arrays.copyOf(TrackersTest.dex(new String[] {"app.revanced.extension.shared.Utils"}, none), 0x80);
            got = labels(apk(dir, "damaged.apk", broken, TrackersTest.dex(new String[] {"app.morphe.extension.shared.Utils"}, none)));
            check("a damaged dex is skipped, the next one is still read", got.equals(new LinkedHashSet<String>(Arrays.asList("Morphe"))), got.toString());

            String testApk = System.getProperty("testapk");
            if (testApk != null && new File(testApk).isFile()) {
                got = labels(new File(testApk));
                check("this app's own APK (its strings name both tools, it bundles the Morphe patcher) is not patched: " + testApk, got.isEmpty(), got.toString());
            } else {
                System.out.println("no APK of this app (run ./build.sh): its own-APK check was skipped");
            }

            // ModDetect needs Android classes: a scan that deep() asks PatchClasses and no longer searches the dex bytes for the words
            File d = new File(System.getProperty("user.dir")).getAbsoluteFile(), src = null;
            for (int i = 0; d != null && i < 8 && src == null; i++, d = d.getParentFile()) {
                File c = new File(d, "src/com/bloatware/bingblop/ModDetect.java");
                if (c.isFile()) src = c;
            }
            check("ModDetect.java found", src != null, null);
            if (src != null) {
                String code = blank(new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8));
                check("ModDetect.deep hands the dex entries to PatchClasses.scan", code.contains("PatchClasses.scan(zf, dexEntries, s)"), null);
                check("ModDetect no longer searches dex bytes for the words", !code.contains("DEX_NEEDLES") && !code.contains("scanDex("), null);
            }
        } finally {
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) k.delete();
            dir.delete();
        }
        System.out.println((fails == 0 ? "OK " : "FAILED ") + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
