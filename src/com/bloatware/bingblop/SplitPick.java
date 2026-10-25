package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which files of a split package (APKS, APKM, XAPK: a base and "config" splits for the CPU, the screen density and the languages) a device needs.
 * The base and the feature splits always go; of the config splits only the CPU the device runs best, the nearest screen density (both for
 * the base and for each feature module), and the device's language (and English) - so a watch gets a few megabytes, not every language of
 * the app. No Android classes: tested on its own.
 */
public final class SplitPick {
    private SplitPick() {}

    private static final Pattern CONFIG = Pattern.compile("(?i)(?:^|[._-])config[._-]([a-z0-9_+-]+)\\.apk$");
    private static final Set<String> ABIS = new HashSet<String>(Arrays.asList("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips", "riscv64"));
    private static final String[] DPI_NAMES = {"ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] DPI_VALUES = {120, 160, 213, 240, 320, 480, 640};

    /** The part of a split's file name after "config." in lower case ("arm64_v8a", "xxhdpi", "en"), or null for a base or feature split. */
    static String configToken(String name) {
        Matcher m = CONFIG.matcher(name == null ? "" : name);
        return m.find() ? m.group(1).toLowerCase(Locale.US) : null;
    }

    /**
     * The module a config split configures: "" for the base (split_config.x.apk, config.x.apk, base.config.x.apk), else the feature module
     * ("feature_camera" for split_feature_camera.config.x.apk and feature_camera.config.x.apk). Null for a base or feature split.
     */
    static String configModule(String name) {
        Matcher m = CONFIG.matcher(name == null ? "" : name);
        if (!m.find()) return null;
        String mod = name.substring(0, m.start()).toLowerCase(Locale.US);
        if (mod.startsWith("split_")) mod = mod.substring(6);
        return mod.equals("split") || mod.equals("base") ? "" : mod;
    }

    private static List<String> group(Map<String, List<String>> groups, String name) {
        String mod = configModule(name);
        List<String> g = groups.get(mod);
        if (g == null) groups.put(mod, g = new ArrayList<String>());
        return g;
    }

    private static int dpiIndex(String token) {
        for (int i = 0; i < DPI_NAMES.length; i++) if (DPI_NAMES[i].equals(token)) return i;
        return -1;
    }

    private static String normAbi(String abi) {
        return abi == null ? "" : abi.toLowerCase(Locale.US).replace('-', '_');
    }

    /**
     * @param names the file names of the .apk files in the package
     * @param abis  the device's ABIs, best first (ro.product.cpu.abilist: "arm64-v8a,armeabi-v7a,armeabi")
     * @param dpi   the device's screen density in dpi, or 0 when unknown (every density split stays)
     * @param lang  the device's language ("en", "de"), or empty
     * @return the names to install, the base first
     */
    public static List<String> pick(List<String> names, List<String> abis, int dpi, String lang) {
        List<String> keep = new ArrayList<String>();
        List<String> langNames = new ArrayList<String>();
        // CPU and density splits are chosen per module: a feature module with native code or its own drawables has config splits of its
        // own (split_feature_camera.config.arm64_v8a.apk next to the base's split_config.arm64_v8a.apk), and it needs one of each too
        Map<String, List<String>> abiGroups = new LinkedHashMap<String, List<String>>(), dpiGroups = new LinkedHashMap<String, List<String>>();
        for (String n : names) {
            String t = configToken(n);
            if (t == null || t.equals("master")) { keep.add(n); continue; }
            if (ABIS.contains(t)) group(abiGroups, n).add(n);
            else if (dpiIndex(t) >= 0) group(dpiGroups, n).add(n);
            else if (t.equals("anydpi") || t.equals("nodpi")) keep.add(n);
            else langNames.add(n);
        }
        // the CPU: the first of the device's ABIs that has a split; when none matches, every CPU split stays (better big than broken)
        for (List<String> abiNames : abiGroups.values()) {
            String chosen = null;
            if (abis != null) {
                outer:
                for (String abi : abis) {
                    for (String n : abiNames) if (configToken(n).equals(normAbi(abi))) { chosen = n; break outer; }
                }
            }
            if (chosen != null) keep.add(chosen); else keep.addAll(abiNames);
        }
        // the density: the nearest bucket (the larger one on a tie)
        for (List<String> dpiNames : dpiGroups.values()) {
            if (dpi <= 0) keep.addAll(dpiNames);
            else {
                String best = null;
                int bestDist = Integer.MAX_VALUE, bestVal = -1;
                for (String n : dpiNames) {
                    int v = DPI_VALUES[dpiIndex(configToken(n))];
                    int d = Math.abs(v - dpi);
                    if (d < bestDist || (d == bestDist && v > bestVal)) { best = n; bestDist = d; bestVal = v; }
                }
                keep.add(best);
            }
        }
        // the languages: the device's own and English, when the package has them
        String l = lang == null ? "" : lang.toLowerCase(Locale.US);
        for (String n : langNames) {
            String t = configToken(n);
            String base = t.split("[_+-]")[0];
            if (base.equals("en") || (!l.isEmpty() && base.equals(l))) keep.add(n);
        }
        // the base first (a name without a config part, "base" before the rest)
        List<String> out = new ArrayList<String>();
        for (String n : keep) if (n.toLowerCase(Locale.US).startsWith("base") && configToken(n) == null) out.add(n);
        for (String n : keep) if (!out.contains(n)) out.add(n);
        return out;
    }
}
