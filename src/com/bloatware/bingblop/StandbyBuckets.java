package com.bloatware.bingblop;

/**
 * App standby buckets ({@code am get-standby-bucket} / {@code am set-standby-bucket}): which ones the page may ask for, the command, and
 * how to read what the phone answers. No Android classes, so it can be tested.
 *
 * Android reports a bucket as a number (5 exempted, 10 active, 20 working_set, 30 frequent, 40 rare, 45 restricted, 50 never), and some
 * builds print the name; both are understood. Only the five that a person can set are offered.
 */
final class StandbyBuckets {
    private StandbyBuckets() {}

    /** The buckets {@code am set-standby-bucket} takes, in the order from least to most limited. */
    static final String[] SETTABLE = {"active", "working_set", "frequent", "rare", "restricted"};

    static boolean isSettable(String name) {
        if (name == null) return false;
        for (String s : SETTABLE) if (s.equals(name)) return true;
        return false;
    }

    /** {@code am get-standby-bucket <pkg>}, or null when the package name is not valid. */
    static String getCommand(String pkg) {
        return BackupScripts.isPackageName(pkg) ? "am get-standby-bucket " + pkg : null;
    }

    /** {@code am set-standby-bucket <pkg> <bucket>}, or null when the package or the bucket is not valid. */
    static String setCommand(String pkg, String bucket) {
        return BackupScripts.isPackageName(pkg) && isSettable(bucket) ? "am set-standby-bucket " + pkg + " " + bucket : null;
    }

    /** The bucket name in what the phone printed, or "" when it printed something else (an error, nothing). */
    static String parse(String out) {
        if (out == null) return "";
        String t = out.trim();
        if (t.isEmpty() || t.length() > 40) return "";
        String low = t.toLowerCase(java.util.Locale.ROOT);
        for (String s : SETTABLE) if (low.equals(s)) return s;
        if (low.equals("exempted") || low.equals("never")) return low;
        if (!t.matches("[0-9]{1,3}")) return "";
        switch (Integer.parseInt(t)) {
            case 5: return "exempted";
            case 10: return "active";
            case 20: return "working_set";
            case 30: return "frequent";
            case 40: return "rare";
            case 45: return "restricted";
            case 50: return "never";
            default: return "";
        }
    }
}
