package com.bloatware.bingblop;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import org.json.JSONArray;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects whether an installed app looks patched / repackaged by a third-party tool - ReVanced,
 * an Xposed/LSPosed module, LSPatch, NPatch, and the like. Two tiers so the app list stays fast:
 *
 *  - {@link #cheap}: signals already loaded for the list (package name, the manifest's
 *    appComponentFactory, its meta-data, and the installer package). No extra I/O, so it runs for
 *    every app on every list load.
 *  - {@link #deep}: adds the signer DN (a debug/repack key), a scan of the APK's zip entries
 *    (LSPatch / Xposed / NPatch / ReVanced markers), and a streamed scan of the dex bytes for the
 *    ReVanced / Morphe fingerprints that survive when a patch keeps the app's original package name.
 *    It opens the APK, so it runs on demand for a single app (the inspector), never across the list.
 *
 * Labels are stable, human-readable strings shown as badges, so the UI never has to map codes.
 */
public final class ModDetect {
    private ModDetect() {}

    /** An installer package that is an app patcher/injector (not an ordinary store or sideload tool). */
    static String installerTool(String installer) {
        if (installer == null || installer.isEmpty()) return null;
        String i = installer.toLowerCase(Locale.US);
        if (i.startsWith("app.revanced.manager")) return "ReVanced";
        if (i.contains("morphe")) return "Morphe";
        if (i.equals("org.lsposed.lspatch") || i.contains("lspatch")) return "LSPatch";
        if (i.contains("npatch")) return "NPatch";
        return null;
    }

    /** Cheap signals from ApplicationInfo + installer; adds any detected tool labels to {@code out}. */
    static void cheap(ApplicationInfo info, String installer, Set<String> out) {
        if (info != null) {
            String pkg = info.packageName == null ? "" : info.packageName;
            if (pkg.startsWith("app.revanced.")) out.add("ReVanced");
            if (pkg.startsWith("app.morphe.")) out.add("Morphe");

            // Xposed/LSPosed/NPatch recognize modules by these manifest meta-data keys.
            if (info.metaData != null && (info.metaData.containsKey("xposedmodule")
                    || info.metaData.containsKey("xposedminversion")
                    || info.metaData.containsKey("xposeddescription"))) {
                out.add("Xposed/LSPosed module");
            }

            // A non-root injector rewrites the app's appComponentFactory to its own loader.
            if (Build.VERSION.SDK_INT >= 28 && info.appComponentFactory != null) {
                String f = info.appComponentFactory.toLowerCase(Locale.US);
                if (f.contains("lspatch")) out.add("LSPatch");
                else if (f.contains("npatch")) out.add("NPatch");
                else if (f.contains("xposed")) out.add("Xposed");
            }
        }
        String tool = installerTool(installer);
        if (tool != null) out.add(tool);
    }

    /** Cheap labels as a JSON array (for the app list). */
    static JSONArray cheapArray(ApplicationInfo info, String installer) {
        Set<String> s = new LinkedHashSet<String>();
        cheap(info, installer, s);
        return new JSONArray(new ArrayList<String>(s));
    }

    /** Deep labels: cheap signals plus the signer DN and APK zip markers. */
    static JSONArray deep(ApplicationInfo info, String installer, String sourceDir, List<String> signerDns) {
        Set<String> s = new LinkedHashSet<String>();
        cheap(info, installer, s);

        if (signerDns != null) {
            for (String dn : signerDns) {
                if (dn == null) continue;
                String d = dn.toLowerCase(Locale.US);
                // The Android debug key (CN=Android Debug, O=Android, C=US) - the default signer for
                // repackaged / casually re-signed APKs. Matched narrowly so platform-signed system apps
                // (CN=Android) are never flagged.
                if (d.contains("android debug")) {
                    s.add("Debug-signed (repackaged)");
                    break;
                }
            }
        }

        if (sourceDir != null && !sourceDir.isEmpty()) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(sourceDir);
                List<ZipEntry> dexEntries = new ArrayList<ZipEntry>();
                Enumeration<? extends ZipEntry> e = zf.entries();
                while (e.hasMoreElements()) {
                    ZipEntry ze = e.nextElement();
                    String low = ze.getName().toLowerCase(Locale.US);
                    if (low.startsWith("assets/lspatch/") || low.equals("assets/lspatch.json")) s.add("LSPatch");
                    else if (low.equals("assets/xposed_init") || low.startsWith("meta-inf/xposed/")) s.add("Xposed/LSPosed module");
                    else if (low.startsWith("assets/npatch") || low.contains("/npatch/")) s.add("NPatch");
                    else if (low.contains("revanced")) s.add("ReVanced");
                    else if (low.startsWith("classes") && low.endsWith(".dex")) dexEntries.add(ze);
                }

                // Dex-content scan: ReVanced and Morphe patches often keep the app's original package
                // name, so nothing in the manifest, installer, or file paths gives them away - only
                // their fingerprints compiled into the dex code do. Stops once both are found.
                if (!dexEntries.isEmpty() && !(s.contains("ReVanced") && s.contains("Morphe"))) {
                    for (ZipEntry ze : dexEntries) {
                        scanDex(zf, ze, s);
                        if (s.contains("ReVanced") && s.contains("Morphe")) break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (zf != null) try { zf.close(); } catch (Exception ignored) {}
            }
        }
        return new JSONArray(new ArrayList<String>(s));
    }

    // Lowercase ASCII fingerprints searched for in dex bytes, paired with the label each one adds.
    private static final String[] DEX_NEEDLES = { "revanced", "morphe" };
    private static final String[] DEX_LABELS  = { "ReVanced", "Morphe" };

    /** Cap on bytes read from a single dex entry, so a huge app can't stall the inspector. */
    private static final int DEX_SCAN_CAP = 24 * 1024 * 1024;

    /**
     * Streams one dex entry and looks for the ASCII fingerprints (case-insensitively), adding each
     * match's label to {@code out}. Reads in chunks with a carry-over window so a fingerprint split
     * across a chunk boundary is still found, caps total bytes, and stops as soon as every still-wanted
     * fingerprint is matched. Never loads the whole dex into memory.
     */
    private static void scanDex(ZipFile zf, ZipEntry entry, Set<String> out) {
        boolean[] pending = new boolean[DEX_NEEDLES.length];
        int remaining = 0;
        int maxNeedle = 0;
        for (int i = 0; i < DEX_NEEDLES.length; i++) {
            if (out.contains(DEX_LABELS[i])) {
                pending[i] = false;
            } else {
                pending[i] = true;
                remaining++;
            }
            if (DEX_NEEDLES[i].length() > maxNeedle) maxNeedle = DEX_NEEDLES[i].length();
        }
        if (remaining == 0) return;

        InputStream in = null;
        try {
            in = zf.getInputStream(entry);
            byte[] buf = new byte[1 << 16];                 // 64 KiB read chunk
            byte[] window = new byte[buf.length + maxNeedle]; // carried tail + fresh chunk, lowercased
            int carry = 0;
            long total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                for (int i = 0; i < read; i++) {
                    int c = buf[i] & 0xFF;
                    if (c >= 'A' && c <= 'Z') c += 32;
                    window[carry + i] = (byte) c;
                }
                int windowLen = carry + read;
                for (int i = 0; i < DEX_NEEDLES.length; i++) {
                    if (pending[i] && indexOf(window, windowLen, DEX_NEEDLES[i]) >= 0) {
                        out.add(DEX_LABELS[i]);
                        pending[i] = false;
                        remaining--;
                    }
                }
                if (remaining == 0 || total >= DEX_SCAN_CAP) break;
                // Keep the last (maxNeedle - 1) bytes so a fingerprint spanning the boundary is caught.
                carry = Math.min(maxNeedle - 1, windowLen);
                System.arraycopy(window, windowLen - carry, window, 0, carry);
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** ASCII substring search over the first {@code len} bytes of {@code hay}; {@code needle} is lowercase. */
    private static int indexOf(byte[] hay, int len, String needle) {
        int nl = needle.length();
        if (nl == 0 || nl > len) return -1;
        byte first = (byte) needle.charAt(0);
        int last = len - nl;
        for (int i = 0; i <= last; i++) {
            if (hay[i] != first) continue;
            int j = 1;
            while (j < nl && hay[i + j] == (byte) needle.charAt(j)) j++;
            if (j == nl) return i;
        }
        return -1;
    }
}
