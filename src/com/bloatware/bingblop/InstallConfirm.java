package com.bloatware.bingblop;

/**
 * The text of the question asked before an app that was downloaded from a web address is installed with a privileged mode (adb, Shizuku, root), which
 * installs without the system installer's own confirmation. It names only what the app itself has checked: the package and version inside the downloaded
 * file, where it came from, and whether it is new or replaces an installed app. Three things in it come from the page and are labelled so: the name it gave
 * the app (the label), the checksum (said to come with the request), and the host of the address it gave, which is shown only when the bytes finally came
 * from another host (requestedHost). Pure Java, no android.* classes.
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
        String h = hostText(host);
        sb.append("Downloaded from: ").append(h.isEmpty() ? "unknown" : h).append("\n");
        String rh = hostText(requestedHost);
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
     * A host as shown. A host name is read from its END (the registered domain is the last labels), so it is never cut from the right: up to the longest
     * valid DNS name (253 characters) it is shown whole; a longer one is not a valid name and is shown by its last 253 characters, marked with a leading
     * ellipsis. Control, format and separator characters become spaces, as everywhere in the question.
     */
    static String hostText(String host) {
        String c = clean(host, Integer.MAX_VALUE);
        if (c.length() <= 253) return c;
        return "\u2026" + c.substring(c.length() - 253);
    }

    /**
     * An untrusted value in quotation marks: cleaned and cut like {@link #clean}, and any quotation mark inside it becomes an apostrophe, so the
     * value cannot close its own quotes and go on as if it were text of the dialog. Empty when there is nothing to show.
     */
    static String quoted(String s, int max) {
        String c = clean(s, max);
        if (c.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\u201c");
        for (int i = 0; i < c.length(); ) {
            int cp = c.codePointAt(i);
            i += Character.charCount(cp);
            sb.appendCodePoint(isQuoteMark(cp) ? '\'' : cp);
        }
        return sb.append('\u201d').toString();
    }

    /**
     * Whether a character is, or can pass for, a quotation mark: every one with the Unicode Quotation_Mark property (ASCII and typographic quotes in all
     * languages, the corner brackets of CJK text, the fullwidth forms), the grave accent and acute used as quotes, the primes, and the quote ornaments.
     * Any of them inside an untrusted value could look like the end of the quotation that holds it.
     */
    static boolean isQuoteMark(int cp) {
        if (cp == 0x22 || cp == 0x27 || cp == 0x60 || cp == 0xB4 || cp == 0xAB || cp == 0xBB) return true;
        if (cp >= 0x2018 && cp <= 0x201F) return true;                // single and double, low and reversed
        if (cp == 0x2039 || cp == 0x203A || cp == 0x2E42) return true;
        if (cp >= 0x2032 && cp <= 0x2037) return true;                // primes
        if (cp >= 0x275B && cp <= 0x275E) return true;                // heavy quote ornaments
        if (cp == 0x276E || cp == 0x276F) return true;                // heavy angle quotation ornaments
        if (cp >= 0x300C && cp <= 0x300F) return true;                // corner brackets
        if (cp >= 0x301D && cp <= 0x301F) return true;                // double prime and low double prime quotes
        if (cp >= 0xFE41 && cp <= 0xFE44) return true;                // vertical corner brackets
        return cp == 0xFF02 || cp == 0xFF07 || cp == 0xFF62 || cp == 0xFF63 || cp == 0xFF40;   // fullwidth quote, apostrophe, halfwidth corners, grave
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
