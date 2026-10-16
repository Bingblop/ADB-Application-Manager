package com.bloatware.bingblop;

import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Whether an APK holds the classes a ReVanced or Morphe patch adds to the app it patches (its extension code, app.revanced.* or
 * app.morphe.*). Such a patch often keeps the app's own package name, so these classes are what gives it away. Only classes a dex
 * DEFINES count, read from its type table, never words in its strings: an app that says "not ReVanced" in a text, a language library's
 * Morpheme class, or this app (its own strings name both tools, and it bundles the Morphe patcher, app.morphe.patcher: the tool, not a
 * patch) is not patched. No Android classes, so it is tested on its own.
 */
final class PatchClasses {
    private PatchClasses() {}

    private static final byte[] REVANCED = ascii("Lapp/revanced/"), REVANCED_PATCHER = ascii("Lapp/revanced/patcher/");
    private static final byte[] MORPHE = ascii("Lapp/morphe/"), MORPHE_PATCHER = ascii("Lapp/morphe/patcher/");

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static boolean startsWith(byte[] d, int len, byte[] prefix) {
        if (len < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (d[i] != prefix[i]) return false;
        return true;
    }

    /** The label for a class a dex defines ("Lapp/revanced/extension/shared/Utils;" gives "ReVanced"), or null; the patcher library itself is no patch. */
    static String label(byte[] d, int len) {
        if (startsWith(d, len, REVANCED) && !startsWith(d, len, REVANCED_PATCHER)) return "ReVanced";
        if (startsWith(d, len, MORPHE) && !startsWith(d, len, MORPHE_PATCHER)) return "Morphe";
        return null;
    }

    /** Adds to {@code out} the label of every patch whose classes these dex entries define. A damaged dex is skipped. */
    static void scan(ZipFile zf, List<ZipEntry> dexEntries, final Set<String> out) {
        DexTypeScanner.DescriptorSink sink = new DexTypeScanner.DescriptorSink() {
            @Override
            public void feed(byte[] d, int len, boolean defined) {
                if (!defined) return;
                String l = label(d, len);
                if (l != null) out.add(l);
            }
        };
        for (ZipEntry e : dexEntries) {
            if (out.contains("ReVanced") && out.contains("Morphe")) break;
            try {
                DexTypeScanner.scanDex(zf, e, sink, new DexTypeScanner.Stats());
            } catch (Exception ignored) {
                // not a dex this reader understands: no answer from it
            }
        }
    }
}
