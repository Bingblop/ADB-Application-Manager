package com.bloatware.bingblop;

/**
 * The text one Task Manager poll parses: CPU ({@code /proc/stat}), RAM ({@code /proc/meminfo}), network ({@code /proc/net/dev}),
 * the process list, the GPU nodes and the thread counts, each section ended by its marker, so a slow or truncated read of one
 * piece never corrupts another. Free of Android classes so it can be tested off the device.
 */
final class TaskMgrBlob {

    private TaskMgrBlob() {}

    static final String M1 = "@@TM1-CPU@@", M2 = "@@TM2-MEM@@", M3 = "@@TM3-NET@@", M4 = "@@TM4-PS@@",
            M5 = "@@TM5-GPUBUSY@@", M6 = "@@TM6-GPUPCT@@", M7 = "@@TM7-MALI@@", M8 = "@@TM8-FREQCUR@@", M9 = "@@TM9-FREQMAX@@",
            M10 = "@@TM10-THREADS@@";
    private static final String[] MARKERS = {M1, M2, M3, M4, M5, M6, M7, M8, M9, M10};

    /** The ten sections, in marker order. A section whose marker is missing (the output was cut short) is "". */
    static String[] split(String blob) {
        String[] out = new String[MARKERS.length];
        int from = 0;
        for (int i = 0; i < MARKERS.length; i++) {
            int at = blob == null ? -1 : blob.indexOf(MARKERS[i], from);
            if (at < 0) { out[i] = ""; continue; }
            out[i] = blob.substring(from, at);
            from = at + MARKERS[i].length();
        }
        return out;
    }

    /**
     * Whether the privileged shell ran the script at all. executeShell answers a backend that is down (adb lost the device,
     * Shizuku stopped, su refused) with its error text instead of throwing; that text has no marker, and parsed as it is it reads
     * as 0 % CPU, no RAM, no network and no processes. The first marker is enough: a slow process list or thread count cut off by
     * the timeout still leaves CPU, RAM and network.
     */
    static boolean scriptRan(String blob) {
        return blob != null && blob.indexOf(M1) >= 0;
    }

    /** The same sections from the world-readable files the app reads itself, with no shell: CPU, RAM and network; the process
     *  list, GPU and thread count stay empty. */
    static String direct(String procStat, String meminfo, String netDev) {
        return procStat + M1 + meminfo + M2 + netDev + M3 + M4 + M5 + M6 + M7 + M8 + M9 + M10;
    }
}
