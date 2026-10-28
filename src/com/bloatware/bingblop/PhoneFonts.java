package com.bloatware.bingblop;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Custom Fonts tab's Phone font card (v7.19.1): the font packages installed on a Samsung phone, read off the phone itself.
 *
 * <p>Free of Android classes, so it is tested on the JDK (PhoneFontsTest). Three parts:
 * <ul>
 * <li>{@link #displayName} and {@link #sansFiles} read a package's font XML ({@code assets/xml/*.xml}, the shape FontPackage writes and
 * Samsung's own font packages use): the name Samsung's Font style shows, and which file of {@code assets/fonts/} is drawn as the Regular
 * ({@code DroidSans.ttf}) and which as the Bold ({@code DroidSans-Bold.ttf}). Tolerant of damaged XML: it never throws, and a name or a
 * file it does not trust is left out.</li>
 * <li>{@link #recognized}: whether Samsung's Font style takes the package by itself. One UI 8.5 (and 8.0 from the March 2026 patch) takes
 * only packages signed by Samsung or Monotype (SamsungFonts.appliesSelfSigned), by the SHA-256 of the signing certificate. The app does
 * not carry those hashes, so it takes the phone's own as the reference: the platform's signer and the signers of the font packages that
 * came with the phone. On an older One UI every package is taken.</li>
 * <li>{@link #isFont}: whether a file's first bytes are one TrueType or OpenType font (never a collection), the only thing the font
 * changer writes.</li>
 * </ul>
 */
public final class PhoneFonts {

    private PhoneFonts() {}

    /** The longest font XML read; a real one is a few hundred bytes. */
    public static final int MAX_XML = 64 * 1024;
    /** The longest asset name of a font file trusted. */
    public static final int MAX_FILE_NAME = 128;
    /** What the font changer names the two files it writes, and what a font XML's droidname says for each. */
    public static final String REGULAR = "DroidSans.ttf", BOLD = "DroidSans-Bold.ttf";

    private static final int CI = Pattern.CASE_INSENSITIVE, CID = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern COMMENT = Pattern.compile("<!--.*?(-->|$)", Pattern.DOTALL);
    private static final Pattern FONT_TAG = Pattern.compile("<font(\\s[^>]*)?>", CI);
    private static final Pattern DISPLAY = Pattern.compile("(?:^|\\s)displayname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", CI);
    private static final Pattern SANS = Pattern.compile("<sans(?:\\s[^>]*)?>(.*?)</sans\\s*>", CID);
    private static final Pattern FILE = Pattern.compile("<file(?:\\s[^>]*)?>(.*?)</file\\s*>", CID);
    private static final Pattern FILENAME = Pattern.compile("<filename(?:\\s[^>]*)?>(.*?)</filename\\s*>", CID);
    private static final Pattern DROIDNAME = Pattern.compile("<droidname(?:\\s[^>]*)?>(.*?)</droidname\\s*>", CID);
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);");

    /**
     * The name a font XML gives its font ({@code <font displayname="...">}), unescaped and cleaned as {@link #cleanName} does; null when
     * there is none, or it is empty or holds a control character.
     */
    public static String displayName(String xml) {
        if (xml == null || xml.length() > MAX_XML) return null;
        String x = COMMENT.matcher(xml).replaceAll("");
        Matcher f = FONT_TAG.matcher(x);
        if (!f.find() || f.group(1) == null) return null;
        Matcher d = DISPLAY.matcher(f.group(1));
        if (!d.find()) return null;
        return cleanName(unescape(d.group(1) != null ? d.group(1) : d.group(2)));
    }

    /**
     * A font's name as the card shows it (from the XML, or a package's label): runs of white space one space, trimmed, cut to
     * FontPackage.MAX_NAME characters; null when it is null, empty or holds a control character.
     */
    public static String cleanName(String name) {
        if (name == null) return null;
        name = name.replaceAll("[\\s\\u00A0]+", " ").trim();
        if (name.isEmpty()) return null;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.getType(cp) == Character.CONTROL) return null;
            i += Character.charCount(cp);
        }
        if (name.codePointCount(0, name.length()) > FontPackage.MAX_NAME) name = name.substring(0, name.offsetByCodePoints(0, FontPackage.MAX_NAME)).trim();
        return name;
    }

    /**
     * The files of {@code assets/fonts/} a font XML's {@code <sans>} part draws: {@code {regular, bold}}, each the asset name the XML
     * gives for the droidname {@code DroidSans.ttf} or {@code DroidSans-Bold.ttf} (the first of each), or null when it names none. A file
     * name with a path in it, a control character, or more than MAX_FILE_NAME characters is not trusted and left out.
     */
    public static String[] sansFiles(String xml) {
        String[] out = new String[2];
        if (xml == null || xml.length() > MAX_XML) return out;
        String x = COMMENT.matcher(xml).replaceAll("");
        Matcher s = SANS.matcher(x);
        if (!s.find()) return out;
        Matcher f = FILE.matcher(s.group(1));
        while (f.find()) {
            Matcher fn = FILENAME.matcher(f.group(1)), dn = DROIDNAME.matcher(f.group(1));
            if (!fn.find() || !dn.find()) continue;
            String file = unescape(fn.group(1)), droid = unescape(dn.group(1));
            if (file == null || droid == null) continue;
            file = file.trim();
            droid = droid.trim();
            if (!isAssetName(file)) continue;
            if (droid.equalsIgnoreCase(REGULAR)) { if (out[0] == null) out[0] = file; }
            else if (droid.equalsIgnoreCase(BOLD)) { if (out[1] == null) out[1] = file; }
        }
        return out;
    }

    /** A plain file name inside assets/fonts/: no folder, no "..", no control character, at most MAX_FILE_NAME characters. */
    static boolean isAssetName(String n) {
        if (n == null || n.isEmpty() || n.length() > MAX_FILE_NAME) return false;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.equals(".") || n.contains("..")) return false;
        for (int i = 0; i < n.length(); i++) if (n.charAt(i) < 0x20 || n.charAt(i) == 0x7F) return false;
        return true;
    }

    /**
     * Whether Samsung's Font style applies the package without help: always on a One UI that takes self-signed packages
     * ({@code selfSignedOk}), always for a package that came with the phone, otherwise only when one of its signers ({@code signers}, the
     * SHA-256 of its certificates) is in {@code reference} (the platform's signers and those of the phone's own font packages). A package
     * whose signers could not be read is not recognized (fail closed: it then needs the font changer).
     */
    public static boolean recognized(boolean selfSignedOk, boolean preinstalled, Collection<String> signers, Collection<String> reference) {
        if (selfSignedOk || preinstalled) return true;
        if (signers == null || signers.isEmpty() || reference == null || reference.isEmpty()) return false;
        for (String s : signers) if (s != null && !s.isEmpty() && reference.contains(s)) return true;
        return false;
    }

    /** Whether these are the first bytes of one TrueType (0x00010000, 'true') or OpenType ('OTTO') font: never a collection ('ttcf') or a web font. */
    public static boolean isFont(byte[] head) {
        if (head == null || head.length < 4) return false;
        int t = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return t == 0x00010000 || t == 0x74727565 || t == 0x4F54544F;
    }

    /** XML text with its five named entities and numeric character references resolved; null when a reference names no character. */
    static String unescape(String s) {
        if (s == null) return null;
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(s, at, m.start());
            String e = m.group(1);
            if (e.equals("amp")) out.append('&');
            else if (e.equals("lt")) out.append('<');
            else if (e.equals("gt")) out.append('>');
            else if (e.equals("quot")) out.append('"');
            else if (e.equals("apos")) out.append('\'');
            else {
                int cp;
                try {
                    cp = e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                } catch (NumberFormatException bad) {
                    return null;
                }
                if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return null;
                out.appendCodePoint(cp);
            }
            at = m.end();
        }
        out.append(s, at, s.length());
        return out.toString();
    }
}
