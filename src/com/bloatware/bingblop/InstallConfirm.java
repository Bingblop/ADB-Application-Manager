package com.bloatware.bingblop;

/**
 * The text of the question asked before an app that was downloaded from a web address is installed with a privileged mode (adb, Shizuku, root), which
 * installs without the system installer's own confirmation. It names only what the app itself has checked: the package and version inside the downloaded
 * file, where it came from, and whether it is new or replaces an installed app. Nothing in it comes from the page except the label, which is shown as
 * text and marked as the page's name for it. Pure Java, no android.* classes.
 */
final class InstallConfirm {
    private InstallConfirm() {}

    static String title() {
        return "Install this app?";
    }

    /**
     * @param label            the name the page gave the app (may be empty)
     * @param packageName      the package name read from the downloaded APK
     * @param versionName      the version name read from the downloaded APK (may be empty)
     * @param host             the host the bytes finally came from, after redirects (may be empty)
     * @param requestedHost    the host of the address the page gave (may be empty); shown when it differs from {@code host}
     * @param installedVersion the version installed now, or null when the app is not installed
     * @param hashChecked      whether the file matched a checksum published by the source
     */
    static String message(String label, String packageName, String versionName, String host, String requestedHost, String installedVersion, boolean hashChecked) {
        StringBuilder sb = new StringBuilder();
        String name = clean(label, 80);
        if (!name.isEmpty()) sb.append(name).append("\n");
        sb.append(clean(packageName, 200));
        String v = clean(versionName, 60);
        if (!v.isEmpty()) sb.append("  ").append(v);
        sb.append("\n\n");
        String h = clean(host, 100);
        sb.append("Downloaded from: ").append(h.isEmpty() ? "unknown" : h).append("\n");
        String rh = clean(requestedHost, 100);
        if (!rh.isEmpty() && !rh.equalsIgnoreCase(h)) sb.append("(the address given was on ").append(rh).append(" and sent the download on)\n");
        if (installedVersion == null) sb.append("This is a new app: it is not installed on this phone.\n");
        else sb.append("It replaces the installed app").append(clean(installedVersion, 60).isEmpty() ? "" : " (now " + clean(installedVersion, 60) + ")").append(".\n");
        sb.append(hashChecked ? "The file matches the checksum its source published.\n" : "Its source published no checksum, so the file could not be checked against one.\n");
        sb.append("\nIt is installed without the system installer's own question, so only continue if you started this install.");
        return sb.toString();
    }

    /**
     * A value shown in the question: control, format (bidi marks and overrides, zero-width), line/paragraph separator, unassigned and surrogate-less
     * private-use characters become spaces, so the value stays on one line in the direction it was written; it is cut at {@code max} characters.
     */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            sb.appendCodePoint(hidden(cp) ? ' ' : cp);
        }
        return sb.toString().trim();
    }

    private static boolean hidden(int cp) {
        if (cp == 0x20 || cp == 0xA0) return true;
        switch (Character.getType(cp)) {
            case Character.CONTROL:            // C0, DEL and C1 (including U+0085)
            case Character.FORMAT:             // bidi marks and overrides, zero-width characters, joiners
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SPACE_SEPARATOR:
            case Character.PRIVATE_USE:
            case Character.UNASSIGNED:
            case Character.SURROGATE:
                return true;
            default:
                return false;
        }
    }

    /** The host of an https address, lower case, or "" when there is none. */
    static String hostOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int s = u.indexOf("://");
        if (s < 0) return "";
        int start = s + 3, end = start;
        while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) end++;
        String auth = u.substring(start, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) { int b = auth.indexOf(']'); return b > 0 ? auth.substring(0, b + 1).toLowerCase(java.util.Locale.ROOT) : ""; }
        int colon = auth.indexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        return auth.toLowerCase(java.util.Locale.ROOT);
    }
}
