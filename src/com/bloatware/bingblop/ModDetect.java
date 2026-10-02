package com.bloatware.bingblop;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import org.json.JSONArray;

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
 *  - {@link #deep}: adds the signer DN (a debug/repack key) and a scan of the APK's zip entries
 *    (LSPatch / Xposed / NPatch / ReVanced markers). It opens the APK, so it runs on demand for a
 *    single app (the inspector), never across the whole list.
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
        if (i.equals("org.lsposed.lspatch") || i.contains("lspatch")) return "LSPatch";
        if (i.contains("npatch")) return "NPatch";
        return null;
    }

    /** Cheap signals from ApplicationInfo + installer; adds any detected tool labels to {@code out}. */
    static void cheap(ApplicationInfo info, String installer, Set<String> out) {
        if (info != null) {
            String pkg = info.packageName == null ? "" : info.packageName;
            if (pkg.startsWith("app.revanced.")) out.add("ReVanced");

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
                Enumeration<? extends ZipEntry> e = zf.entries();
                while (e.hasMoreElements()) {
                    String low = e.nextElement().getName().toLowerCase(Locale.US);
                    if (low.startsWith("assets/lspatch/") || low.equals("assets/lspatch.json")) s.add("LSPatch");
                    else if (low.equals("assets/xposed_init") || low.startsWith("meta-inf/xposed/")) s.add("Xposed/LSPosed module");
                    else if (low.startsWith("assets/npatch") || low.contains("/npatch/")) s.add("NPatch");
                    else if (low.contains("revanced")) s.add("ReVanced");
                }
            } catch (Exception ignored) {
            } finally {
                if (zf != null) try { zf.close(); } catch (Exception ignored) {}
            }
        }
        return new JSONArray(new ArrayList<String>(s));
    }
}
