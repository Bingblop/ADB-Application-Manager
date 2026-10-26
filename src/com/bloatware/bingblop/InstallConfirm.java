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

    /** The most apps the question for several updates lists by name; the rest are counted. */
    static final int LIST_MAX = 8;

    /** The line that closes every question: what a privileged install lacks. */
    private static final String NO_SYSTEM_QUESTION = "It is installed without the system installer's own question, so only continue if you started this install.";

    /** What an install may also do, said in the question: the installed copy goes first (with its data); the data saved in a backup is put back. */
    static final String REMOVES_FIRST = "The installed app is uninstalled first, with its data.";
    static final String REINSTATES_SYSTEM = "The system copy of this app, removed for your user, is brought back; the APK in the backup is not installed.";
    static final String RESTORES_DATA = "The app's saved data from the backup is restored as well.";

    /** What is said when the install turns out to have no privileged mode at all (the app went Read-Only) before the question could be asked. */
    static final String NO_PRIVILEGED_MODE = "Installing needs ADB, Wireless Debugging, Shizuku or Root. Nothing was installed.";

    /** What is said when the working mode is not the one the question named by the time it is answered. */
    static final String MODE_CHANGED = "The working mode changed while the question was open. Nothing was installed.";

    /** How the privileged working mode is named in the question ("ADB", "Wireless Debugging (ADB)", "Shizuku", "Root"); anything else is "a privileged mode". */
    static String viaText(String mode) {
        if ("adb_tcp".equals(mode)) return "ADB";
        if ("adb_wireless".equals(mode)) return "Wireless Debugging (ADB)";
        if ("shizuku".equals(mode)) return "Shizuku";
        if ("root".equals(mode)) return "Root";
        return "a privileged mode";
    }

    /** The title of the question for several apps in one go. */
    static String titleUpdates(int count) {
        return count == 1 ? "Update this app?" : "Update " + count + " apps?";
    }

    /** The package name as shown (quoted, not checked), or a plain note when the file did not give one. */
    private static String packageText(String packageName) {
        String q = quoted(packageName, 120);
        return q.isEmpty() ? "(could not be read)" : q;
    }

    private static void viaLine(StringBuilder sb, String mode) {
        sb.append("It is installed through: ").append(viaText(mode)).append(".\n");
    }

    private static void newOrReplaces(StringBuilder sb, String installedVersion) {
        if (installedVersion == null) sb.append("This is a new app: it is not installed on this phone.\n");
        else {
            String iv = quoted(installedVersion, 40);
            sb.append("It replaces the installed app").append(iv.isEmpty() ? "" : " (now " + iv + ")").append(".\n");
        }
    }

    /**
     * The question before an app from a file on this phone (the Installer tab, or a package handed over by the file manager) is installed with a
     * privileged mode. The source is a file on this phone, not a host. Everything the app read itself comes first; the file's name, the package and
     * version inside it and the install options come after it, quoted and labelled as not checked.
     *
     * @param fileName         the name of the file that was picked (may be empty)
     * @param packageName      the package name read from the base APK
     * @param versionName      the version name read from the base APK (may be empty)
     * @param installedVersion the version installed now, or null when the app is not installed
     * @param files            how many APK files go in (more than one: a bundle with splits, installed together)
     * @param mode             the privileged mode the install runs through (adb_tcp, adb_wireless, shizuku, root)
     * @param options          the install options that will be used, as the shell would get them (may be empty)
     * @param actions          what else the action does to the phone, in the app's own words ({@link #REMOVES_FIRST}, {@link #RESTORES_DATA}); may be null
     */
    static String messageLocalFile(String fileName, String packageName, String versionName, String installedVersion, int files, String mode, String options, java.util.List<String> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: a file on this phone\n");
        viaLine(sb, mode);
        // Bringing back a removed system app installs nothing from the file, so "new app" / "replaces" would only contradict the action line
        boolean reinstates = actions != null && actions.contains(REINSTATES_SYSTEM);
        if (!reinstates) newOrReplaces(sb, installedVersion);
        if (files > 1 && !reinstates) sb.append("It is a bundle of ").append(files).append(" files, installed together as one app.\n");
        if (actions != null) for (String a : actions) if (a != null && !a.isEmpty()) sb.append(a).append("\n");
        if (!reinstates) sb.append("No checksum was compared with this file.\n");
        sb.append("\nWritten in the file, its name or by the page (not checked):\n");
        String fn = quoted(fileName, 80);
        if (!fn.isEmpty()) sb.append("File: ").append(fn).append("\n");
        sb.append("Package: ").append(packageText(packageName)).append("\n");
        String v = quoted(versionName, 40);
        if (!v.isEmpty()) sb.append("Version: ").append(v).append("\n");
        String o = quoted(options, 160);
        if (!o.isEmpty()) sb.append("Options: ").append(o).append("\n");
        sb.append("\n").append(NO_SYSTEM_QUESTION);
        return sb.toString();
    }

    /**
     * The question before a font package made in the Custom Fonts tab is installed with a privileged mode. The app made the package and signed it with
     * its own key, recorded its SHA-256 then, and has just checked the file that goes to the install against that record. The font's name is the
     * user's own text and the options are the shell's: they come after it, quoted.
     *
     * @param fontName         the name the font was given (may be empty)
     * @param packageName      the package name the app gave the font
     * @param versionName      the package's version name
     * @param installedVersion the version installed now, or null when the package is not installed
     * @param mode             the privileged mode the install runs through
     * @param options          the install options that will be used, as the shell would get them (may be empty)
     */
    static String messageFontPackage(String fontName, String packageName, String versionName, String installedVersion, String mode, String options) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: a font package this app made (Custom Fonts, My fonts)\n");
        viaLine(sb, mode);
        newOrReplaces(sb, installedVersion);
        sb.append("The file matches the SHA-256 checksum the app recorded when it made the package.\n");
        sb.append("\nIn the package:\n");
        String fn = quoted(fontName, 80);
        if (!fn.isEmpty()) sb.append("Font: ").append(fn).append("\n");
        sb.append("Package: ").append(packageText(packageName)).append("\n");
        String v = quoted(versionName, 40);
        if (!v.isEmpty()) sb.append("Version: ").append(v).append("\n");
        String o = quoted(options, 160);
        if (!o.isEmpty()) sb.append("Options: ").append(o).append("\n");
        sb.append("\n").append(NO_SYSTEM_QUESTION);
        return sb.toString();
    }

    /**
     * The question before one update downloaded in the Updater is installed with a privileged mode. The app has checked that the file is the package it
     * asked for, that its version is newer and that it is signed with the key of the installed app (it would not have got this far otherwise).
     *
     * @param label            the name of the installed app (may be empty)
     * @param packageName      the package name read from the downloaded APK
     * @param versionName      the version name read from the downloaded APK (may be empty)
     * @param host             the host the bytes finally came from, after redirects (may be empty)
     * @param requestedHost    the host of the address the update check found (may be empty); shown when it differs from {@code host}
     * @param installedVersion the version installed now
     * @param mode             the privileged mode the install runs through
     */
    static String messageUpdate(String label, String packageName, String versionName, String host, String requestedHost, String installedVersion, String mode) {
        StringBuilder sb = new StringBuilder();
        String h = hostText(host);
        sb.append("Downloaded from: ").append(h.isEmpty() ? "unknown" : h).append("\n");
        String rh = hostText(requestedHost);
        if (!rh.isEmpty() && !rh.equalsIgnoreCase(h)) sb.append("(the address given was on ").append(rh).append(" and sent the download on)\n");
        viaLine(sb, mode);
        newOrReplaces(sb, installedVersion);
        sb.append("The file is the package that was asked for, a newer version, and signed with the same key as the installed app.\n");
        sb.append("No checksum was compared with this file.\n");
        sb.append("\nWritten in the file or by the update source (not checked):\n");
        sb.append("Package: ").append(packageText(packageName)).append("\n");
        String v = quoted(versionName, 40);
        if (!v.isEmpty()) sb.append("Version: ").append(v).append("\n");
        String name = quoted(label, 40);
        if (!name.isEmpty()) sb.append("Name: ").append(name).append("\n");
        sb.append("\n").append(NO_SYSTEM_QUESTION);
        return sb.toString();
    }

    /**
     * The one question for "Update all": the apps the update check found, listed (at most {@link #LIST_MAX}, the rest counted), asked before anything is
     * downloaded. Each row is {name, package, installed version, found version}; the names and versions are labelled as not checked and quoted.
     */
    static String messageUpdateAll(java.util.List<String[]> rows, String mode) {
        StringBuilder sb = new StringBuilder();
        int n = rows == null ? 0 : rows.size();
        sb.append(n == 1 ? "One installed app will be updated.\n" : n + " installed apps will be updated, one after the other.\n");
        if (n == 1) viaLine(sb, mode);
        else sb.append("They are installed through: ").append(viaText(mode)).append(".\n");
        sb.append("Each file is downloaded and checked before it is installed: it must be the package named below, a newer version, and signed with the same key as the installed app. One that fails is skipped.\n");
        sb.append("\nFound by the update check, not downloaded yet (names and versions not checked):\n");
        for (int i = 0; i < n && i < LIST_MAX; i++) {
            String[] r = rows.get(i);
            String nm = quoted(r.length > 0 ? r[0] : "", 40);
            String pk = clean(r.length > 1 ? r[1] : "", 120);
            sb.append(i + 1).append(". ").append(nm.isEmpty() ? "" : nm + " ").append(pk.isEmpty() && nm.isEmpty() ? "(unknown app)" : pk);
            String from = quoted(r.length > 2 ? r[2] : "", 40), to = quoted(r.length > 3 ? r[3] : "", 40);
            if (!from.isEmpty() || !to.isEmpty()) sb.append(": ").append(from.isEmpty() ? "?" : from).append(" to ").append(to.isEmpty() ? "?" : to);
            sb.append("\n");
        }
        if (n > LIST_MAX) sb.append("and ").append(n - LIST_MAX).append(n - LIST_MAX == 1 ? " more app.\n" : " more apps.\n");
        sb.append("\nThey are installed without the system installer's own question, so only continue if you started this.");
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
