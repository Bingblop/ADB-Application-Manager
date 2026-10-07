package com.bloatware.bingblop;

import java.util.List;
import java.util.Locale;

/** Which build of the app (and of adb) suits a phone, by the ABIs it reports (Build.SUPPORTED_ABIS, best first). No Android classes: tested on its own. */
public final class AbiPick {
    private AbiPick() {}

    /** Whether the phone's first choice is a 64-bit ARM ABI. */
    public static boolean is64(String[] abis) {
        return abis != null && abis.length > 0 && abis[0] != null && abis[0].toLowerCase(Locale.US).startsWith("arm64");
    }

    /**
     * Which of a release's .apk files suits a phone with these ABIs: the one named for its own ABI (the first of its ABIs that has one), else the
     * universal one, else the first. A release with a single untagged .apk (the older releases) gives that one. Returns the index into names, or -1
     * when there is no .apk.
     */
    public static int pickApk(List<String> names, String[] abis) {
        int first = -1, universal = -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null || !n.toLowerCase(Locale.US).endsWith(".apk")) continue;
            if (first < 0) first = i;
            if (universal < 0 && n.toLowerCase(Locale.US).contains("universal")) universal = i;
        }
        if (first < 0) return -1;
        if (abis != null) {
            for (String abi : abis) {
                if (abi == null || abi.isEmpty()) continue;
                String a = abi.toLowerCase(Locale.US);
                for (int i = 0; i < names.size(); i++) {
                    String n = names.get(i);
                    if (n != null && n.toLowerCase(Locale.US).endsWith(".apk") && n.toLowerCase(Locale.US).contains(a)) return i;
                }
            }
        }
        return universal >= 0 ? universal : first;
    }
}
