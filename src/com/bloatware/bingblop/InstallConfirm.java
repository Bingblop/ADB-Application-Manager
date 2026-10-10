package com.bloatware.bingblop;

/**
 * The text of the question asked before an app that was downloaded from a web address is installed with a privileged mode (adb, Shizuku, root), which
 * installs without the system installer's own confirmation. It names only what the app itself has checked: the package and version inside the downloaded
 * file, where it came from, and whether it is new or replaces an installed app. Nothing in it comes from the page except the label, which is shown as
 * text and marked as the page's name for it, and the checksum, which is said to come with the request. Pure Java, no android.* classes.
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
     * @param hashChecked      whether the file matched a checksum that came with the install request
     */
    static String message(String label, String packageName, String versionName, String host, String requestedHost, String installedVersion, boolean hashChecked) {
        StringBuilder sb = new StringBuilder();
        // What the app itself found out comes first, one fact to a line. Everything an attacker can write (the package name and version inside the file,
        // the page's name for the app) comes after it, labelled as not checked and always inside quotation marks, so that a long value that wraps onto
        // the next screen line cannot pass for one of the facts above.
        String h = clean(host, 100);
        sb.append("Downloaded from: ").append(h.isEmpty() ? "unknown" : h).append("\n");
        String rh = clean(requestedHost, 100);
        if (!rh.isEmpty() && !rh.equalsIgnoreCase(h)) sb.append("(the address given was on ").append(rh).append(" and sent the download on)\n");
        if (installedVersion == null) sb.append("This is a new app: it is not installed on this phone.\n");
        else {
            String iv = quoted(installedVersion, 40);
            sb.append("It replaces the installed app").append(iv.isEmpty() ? "" : " (now " + iv + ")").append(".\n");
        }
        // The checksum comes with the install request, from the same place as the address, so a match proves the download is the file that was asked for,
        // not that the source published it.
        sb.append(hashChecked ? "The file matches the checksum that came with the install request (it does not show who published it).\n"
                              : "No checksum came with the install request, so the file could not be compared with one.\n");
        sb.append("\nWritten in the file or by the page (not checked):\n");
        sb.append("Package: ").append(quoted(packageName, 120)).append("\n");
        String v = quoted(versionName, 40);
        if (!v.isEmpty()) sb.append("Version: ").append(v).append("\n");
        String name = quoted(label, 40);
        if (!name.isEmpty()) sb.append("Name given by the page: ").append(name).append("\n");
        sb.append("\nIt is installed without the system installer's own question, so only continue if you started this install.");
        return sb.toString();
    }

    /**
     * An untrusted value in quotation marks: cleaned and cut like {@link #clean}, and any quotation mark inside it becomes an apostrophe, so the
     * value cannot close its own quotes and go on as if it were text of the dialog. Empty when there is nothing to show.
     */
    static String quoted(String s, int max) {
        String c = clean(s, max);
        if (c.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\u201c");
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            sb.append(ch == '"' || ch == '\u201c' || ch == '\u201d' || ch == '\u201e' || ch == '\u201f' || ch == '\u00ab' || ch == '\u00bb' ? '\'' : ch);
        }
        return sb.append('\u201d').toString();
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
        // A name with non-ASCII letters is shown in its ASCII (xn--) form, so a look-alike of a trusted name cannot pass for it; a name that is not a
        // valid host name is not shown at all.
        try {
            return java.net.IDN.toASCII(auth, java.net.IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
