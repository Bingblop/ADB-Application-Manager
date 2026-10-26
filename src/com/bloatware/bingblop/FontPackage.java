package com.bloatware.bingblop;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A Samsung FlipFont font package: the APK that carries a font to Settings > Font style and to the font changer.
 *
 * <p>The Custom Fonts tab turns the Regular (and optional Bold) font that {@link FontBuilder} made into a package named
 * {@code com.monotype.android.font.<suffix>} with no code and no resources: a binary AndroidManifest.xml ({@link #manifest}: label as a
 * literal string, hasCode false, minSdk 26, targetSdk 34), the FlipFont description {@code assets/xml/<suffix>.xml} ({@link #fontXml})
 * and the fonts under {@code assets/fonts/}, stored uncompressed and 4-byte aligned. {@link #writeUnsigned} writes that zip with fixed
 * timestamps (the same input gives the same file); the caller then signs it with {@link ApkSigner#prepare} and {@link ApkSigner#signV2}
 * with the app's {@link SigningKey}. The display name is the user's text: it is cleaned ({@link #cleanName}) and escaped here.
 * Every failure is a {@link Sfnt.FontException} whose message can be shown. Pure Java, no Android classes; the manifest is byte for byte
 * what aapt2 compiles from the same text, and the packages were checked with aapt2 and apksigner off-device.
 */
public final class FontPackage {

    private FontPackage() {}

    public static final String PACKAGE_PREFIX = "com.monotype.android.font.";
    public static final int MIN_SDK = 26, TARGET_SDK = 34;
    /** The longest display name kept, in characters (code points); the rest is cut. */
    public static final int MAX_NAME = 64;
    /** The longest name part of a suffix, before the "_" and the 6 hex digits. */
    public static final int MAX_SUFFIX_NAME = 41;
    public static final String MANIFEST_ENTRY = "AndroidManifest.xml", FONTS_DIR = "assets/fonts/", XML_DIR = "assets/xml/";

    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z][A-Za-z0-9]{0," + (MAX_SUFFIX_NAME - 1) + "}_[0-9a-f]{6}");
    private static final Pattern PACKAGE_SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern FONT_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.ttf");

    // ---------------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------------

    /**
     * The display name as it goes into the package: control characters, unpaired surrogates, noncharacters and bidi embedding / override /
     * isolate controls removed, every run of white space one space, trimmed, at most {@link #MAX_NAME} characters. Refuses a name that is
     * empty after that, or that shows nothing ({@link #visible}).
     */
    public static String cleanName(String name) throws Sfnt.FontException {
        String s = clean(name, MAX_NAME);
        if (s.isEmpty() || !visible(s)) throw new Sfnt.FontException("bad_name", "Give the font a name.");
        return s;
    }

    /**
     * Whether a name shows something: at least one letter, number, punctuation mark or symbol that is not drawn blank (the Hangul fillers,
     * the blank Braille pattern). Format characters (zero-width spaces and joiners, the word joiner, tag characters, the soft hyphen) and
     * combining marks draw nothing on their own, so a name made only of them would be an empty label in Settings and in My fonts.
     * Everything in the emoji and symbol blocks U+1F000-U+1FBFF counts: they hold only symbols and digits, and an emoji newer than the
     * phone's Unicode (UNASSIGNED here, while the keyboard offers it and the page's WebView knows it) is one of them. The page's
     * fntNameShows is the same rule.
     */
    static boolean visible(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800) continue;
            if (cp >= 0x1F000 && cp <= 0x1FBFF) return true;
            switch (Character.getType(cp)) {
                case Character.UPPERCASE_LETTER: case Character.LOWERCASE_LETTER: case Character.TITLECASE_LETTER:
                case Character.MODIFIER_LETTER: case Character.OTHER_LETTER:
                case Character.DECIMAL_DIGIT_NUMBER: case Character.LETTER_NUMBER: case Character.OTHER_NUMBER:
                case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION: case Character.START_PUNCTUATION:
                case Character.END_PUNCTUATION: case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
                case Character.OTHER_PUNCTUATION:
                case Character.MATH_SYMBOL: case Character.CURRENCY_SYMBOL: case Character.MODIFIER_SYMBOL: case Character.OTHER_SYMBOL:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** {@link #cleanName} without the refusal: "" for nothing usable. */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max * 2) + 2);
        int kept = 0;
        boolean space = false;
        for (int i = 0; i < s.length() && kept < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = b.length() > 0; continue; }
            if (dropped(cp)) continue;
            if (space) { b.append(' '); kept++; space = false; if (kept >= max) break; }
            b.appendCodePoint(cp);
            kept++;
        }
        return b.toString().trim();
    }

    private static boolean dropped(int cp) {
        int t = Character.getType(cp);
        if (t == Character.CONTROL || t == Character.SURROGATE) return true;          // a lone surrogate comes through codePointAt as itself
        if ((cp & 0xFFFE) == 0xFFFE || (cp >= 0xFDD0 && cp <= 0xFDEF)) return true;     // noncharacters (not allowed in XML either)
        return cp == 0x061C || cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0xFEFF;
    }

    /**
     * The package suffix for a display name: its letters and digits folded to Latin ("Straße Ünï" -> "StrasseUni"; "Font" when none are
     * left, "Font" in front of a leading digit), at most {@link #MAX_SUFFIX_NAME} of them, then "_" and the first 6 hex digits of
     * SHA-256(cleaned name + salt). The salt (the library entry's id) keeps two fonts of the same name apart.
     */
    public static String suffixFor(String displayName, String salt) {
        String name = clean(displayName, MAX_NAME);
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKD);       // NFKD: accents split off, ligatures and full-width forms opened
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < folded.length() && b.length() < MAX_SUFFIX_NAME + 8; i++) {
            char c = folded.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            else {
                String f = latinFold(c);
                if (f != null) b.append(f);
            }
        }
        String base = b.length() == 0 ? "Font" : (b.charAt(0) <= '9' ? "Font" + b : b.toString());
        if (base.length() > MAX_SUFFIX_NAME) base = base.substring(0, MAX_SUFFIX_NAME);
        byte[] h = sha256((name + (salt == null ? "" : salt)).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(base).append('_');
        for (int i = 0; i < 3; i++) hex.append(Character.forDigit((h[i] >> 4) & 15, 16)).append(Character.forDigit(h[i] & 15, 16));
        return hex.toString();
    }

    /** Latin letters that NFKD does not split into an ASCII letter and a mark. */
    private static String latinFold(char c) {
        switch (c) {
            case '\u00df': return "ss";
            case '\u1e9e': return "SS";
            case '\u00e6': return "ae";
            case '\u00c6': return "AE";
            case '\u0153': return "oe";
            case '\u0152': return "OE";
            case '\u00f8': return "o";
            case '\u00d8': return "O";
            case '\u0111': case '\u00f0': return "d";
            case '\u0110': case '\u00d0': return "D";
            case '\u0142': return "l";
            case '\u0141': return "L";
            case '\u00fe': return "th";
            case '\u00de': return "Th";
            case '\u0127': return "h";
            case '\u0126': return "H";
            case '\u0131': return "i";
            case '\u0138': return "q";
            case '\u014b': return "ng";
            case '\u014a': return "NG";
            default: return null;
        }
    }

    /** Whether {@code s} has the shape {@link #suffixFor} gives. */
    public static boolean isValidSuffix(String s) { return s != null && SUFFIX.matcher(s).matches(); }

    public static String packageName(String suffix) throws Sfnt.FontException {
        if (!isValidSuffix(suffix)) throw new Sfnt.FontException("bad_package", "This font package has a damaged name.");
        return PACKAGE_PREFIX + suffix;
    }

    /** The suffix of a package this tab made, or null for any other package (Samsung's own fonts included). */
    public static String suffixOf(String packageName) {
        if (packageName == null || !packageName.startsWith(PACKAGE_PREFIX)) return null;
        String s = packageName.substring(PACKAGE_PREFIX.length());
        return isValidSuffix(s) ? s : null;
    }

    /**
     * Whether an installed package is a font package this app made: named the way {@link #suffixFor} names one, not part of the system, and
     * either still in My fonts ({@code librarySuffixes}) or signed with this app's own key ({@code signers}, the SHA-256 digests of the
     * package's certificates, holds {@code appKeySha256}: one deleted from My fonts but still installed). The update check leaves these out:
     * no store has them, and asking one would send the font's name along with the phone's model and region.
     */
    public static boolean madeHere(String packageName, boolean system, java.util.Collection<String> librarySuffixes, java.util.Collection<String> signers, String appKeySha256) {
        String s = suffixOf(packageName);
        if (s == null || system) return false;
        if (librarySuffixes != null && librarySuffixes.contains(s)) return true;
        return appKeySha256 != null && !appKeySha256.isEmpty() && signers != null && signers.contains(appKeySha256);
    }

    /** The font file name in the package, without ".ttf": the name part of the suffix ("MyFont" for "MyFont_3fa9c1"). */
    public static String fontFileBase(String suffix) throws Sfnt.FontException {
        if (!isValidSuffix(suffix)) throw new Sfnt.FontException("bad_package", "This font package has a damaged name.");
        return suffix.substring(0, suffix.lastIndexOf('_'));
    }

    /** An Android package name: two or more dot-separated parts, each a letter followed by letters, digits or "_". */
    public static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255) return false;
        String[] parts = pkg.split("\\.", -1);
        if (parts.length < 2) return false;
        for (String p : parts) if (!PACKAGE_SEGMENT.matcher(p).matches()) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Versions
    // ---------------------------------------------------------------------------------------------

    /**
     * The versionCode of a rebuilt package: one above both the library entry's and the installed package's ({@code installed} 0 or less
     * when it is not installed), so the rebuild installs as an update. 1 for a new entry ({@code current} 0).
     */
    public static int nextVersionCode(int current, int installed) throws Sfnt.FontException {
        int top = Math.max(Math.max(current, installed), 0);
        if (top == Integer.MAX_VALUE) throw new Sfnt.FontException("bad_version", "This font package cannot get a newer version number. Make it again as a new font.");
        return top + 1;
    }

    /** The versionName shown for a versionCode: "3.0" for 3. */
    public static String versionName(int versionCode) { return versionCode + ".0"; }

    // ---------------------------------------------------------------------------------------------
    // The FlipFont description
    // ---------------------------------------------------------------------------------------------

    /** XML text for an attribute or element: &amp; &lt; &gt; &quot; &apos; escaped (the caller has cleaned out what XML cannot hold). */
    public static String escapeXml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * assets/xml/&lt;suffix&gt;.xml: the font's display name and its files, the Regular one standing in for DroidSans.ttf and the Bold one
     * (null when there is none: Android then emboldens the Regular) for DroidSans-Bold.ttf.
     */
    public static String fontXml(String displayName, String regularFile, String boldFile) throws Sfnt.FontException {
        String name = cleanName(displayName);
        checkFontFile(regularFile);
        if (boldFile != null) checkFontFile(boldFile);
        StringBuilder b = new StringBuilder(400);
        b.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        b.append("<font displayname=\"").append(escapeXml(name)).append("\">\n");
        b.append("    <sans>\n");
        fileXml(b, regularFile, "DroidSans.ttf");
        if (boldFile != null) fileXml(b, boldFile, "DroidSans-Bold.ttf");
        b.append("    </sans>\n");
        b.append("</font>\n");
        return b.toString();
    }

    private static void fileXml(StringBuilder b, String file, String droid) {
        b.append("        <file>\n");
        b.append("            <filename>").append(file).append("</filename>\n");
        b.append("            <droidname>").append(droid).append("</droidname>\n");
        b.append("        </file>\n");
    }

    private static void checkFontFile(String f) throws Sfnt.FontException {
        if (f == null || !FONT_FILE.matcher(f).matches()) throw new Sfnt.FontException("bad_name", "A font file in the package has a name that cannot be used.");
    }

    // ---------------------------------------------------------------------------------------------
    // The binary manifest
    // ---------------------------------------------------------------------------------------------

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    // The manifest attributes with a framework resource id, in id order: the first strings of the pool, matched by the resource map
    private static final String[] ATTR = { "label", "hasCode", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion", "allowBackup" };
    private static final int[] ATTR_ID = { 0x01010001, 0x0101000c, 0x0101020c, 0x0101021b, 0x0101021c, 0x01010270, 0x01010280 };
    private static final int A_LABEL = 0, A_HAS_CODE = 1, A_MIN_SDK = 2, A_VERSION_CODE = 3, A_VERSION_NAME = 4, A_TARGET_SDK = 5, A_ALLOW_BACKUP = 6;
    private static final int TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10, TYPE_INT_BOOLEAN = 0x12;

    /**
     * The compiled (binary XML) AndroidManifest.xml of a font package:
     * {@code <manifest package versionCode versionName><uses-sdk minSdkVersion=26 targetSdkVersion=34/><application label hasCode=false
     * allowBackup=false/></manifest>}. UTF-16 string pool, attributes sorted by resource id, laid out as aapt2 lays out the same text.
     */
    public static byte[] manifest(String pkg, int versionCode, String versionName, String label) throws Sfnt.FontException {
        if (!isValidPackageName(pkg)) throw new Sfnt.FontException("bad_package", "This font package has a damaged name.");
        if (versionCode < 1) throw new Sfnt.FontException("bad_version", "The font package's version number must be 1 or more.");
        String vName = clean(versionName, MAX_NAME);
        if (vName.isEmpty()) throw new Sfnt.FontException("bad_version", "The font package needs a version name.");
        String lbl = cleanName(label);

        // The pool: the attribute names first (the resource map covers them), then every other string once, in code point order (aapt2's)
        List<String> others = new ArrayList<String>();
        for (String s : new String[] { "android", ANDROID_NS, "manifest", "package", pkg, vName, "uses-sdk", "application", lbl })
            if (!others.contains(s)) others.add(s);
        Collections.sort(others, CODE_POINT_ORDER);
        List<String> pool = new ArrayList<String>();
        Collections.addAll(pool, ATTR);
        pool.addAll(others);
        Map<String, Integer> at = new HashMap<String, Integer>();
        for (int i = ATTR.length; i < pool.size(); i++) at.put(pool.get(i), i);
        int ns = at.get(ANDROID_NS);

        Le x = new Le();
        x.u16(0x0003); x.u16(8); x.u32(0);                                   // RES_XML_TYPE, size filled in at the end
        stringPool(x, pool);
        x.u16(0x0180); x.u16(8); x.u32(8 + 4 * ATTR_ID.length);              // RES_XML_RESOURCE_MAP_TYPE
        for (int id : ATTR_ID) x.u32(id);
        namespace(x, 0x0100, 2, at.get("android"), ns);
        startElement(x, 2, at.get("manifest"), new int[][] {
                { ns, A_VERSION_CODE, -1, TYPE_INT_DEC, versionCode },
                { ns, A_VERSION_NAME, at.get(vName), TYPE_STRING, at.get(vName) },
                { -1, at.get("package"), at.get(pkg), TYPE_STRING, at.get(pkg) } });
        startElement(x, 3, at.get("uses-sdk"), new int[][] {
                { ns, A_MIN_SDK, -1, TYPE_INT_DEC, MIN_SDK },
                { ns, A_TARGET_SDK, -1, TYPE_INT_DEC, TARGET_SDK } });
        endElement(x, 3, at.get("uses-sdk"));
        startElement(x, 4, at.get("application"), new int[][] {
                { ns, A_LABEL, at.get(lbl), TYPE_STRING, at.get(lbl) },
                { ns, A_HAS_CODE, -1, TYPE_INT_BOOLEAN, 0 },
                { ns, A_ALLOW_BACKUP, -1, TYPE_INT_BOOLEAN, 0 } });
        endElement(x, 4, at.get("application"));
        endElement(x, 2, at.get("manifest"));
        namespace(x, 0x0101, 2, at.get("android"), ns);
        x.set32(4, x.size());
        return x.bytes();
    }

    /** Code point order (UTF-8 byte order), which is how aapt2 sorts a pool; String.compareTo would put U+E000-FFFF after supplementary ones. */
    private static final Comparator<String> CODE_POINT_ORDER = new Comparator<String>() {
        @Override public int compare(String a, String b) {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                int ca = a.codePointAt(i), cb = b.codePointAt(j);
                if (ca != cb) return ca < cb ? -1 : 1;
                i += Character.charCount(ca);
                j += Character.charCount(cb);
            }
            return (a.length() - i) - (b.length() - j);
        }
    };

    /** ResStringPool, UTF-16: header (28 bytes), one offset per string, then each string as its length, its UTF-16LE units and a 0. */
    private static void stringPool(Le x, List<String> pool) {
        int start = x.size();
        x.u16(0x0001); x.u16(28); x.u32(0);
        x.u32(pool.size()); x.u32(0); x.u32(0);                              // strings, styles, flags (0: UTF-16, not sorted)
        x.u32(28 + 4 * pool.size()); x.u32(0);                               // stringsStart, stylesStart
        int offsets = x.size();
        for (int i = 0; i < pool.size(); i++) x.u32(0);
        int data = x.size();
        for (int i = 0; i < pool.size(); i++) {
            x.set32(offsets + 4 * i, x.size() - data);
            String s = pool.get(i);
            x.u16(s.length());                                               // every string here is far below 0x8000 units
            for (int k = 0; k < s.length(); k++) x.u16(s.charAt(k));
            x.u16(0);
        }
        x.align4();
        x.set32(start + 4, x.size() - start);
    }

    private static void namespace(Le x, int type, int line, int prefix, int uri) {
        x.u16(type); x.u16(16); x.u32(24);
        x.u32(line); x.u32(-1);
        x.u32(prefix); x.u32(uri);
    }

    /** attrs: {namespace, name, raw value (-1 none), value type, value data}, already in resource id order. */
    private static void startElement(Le x, int line, int name, int[][] attrs) {
        x.u16(0x0102); x.u16(16); x.u32(36 + 20 * attrs.length);
        x.u32(line); x.u32(-1);
        x.u32(-1); x.u32(name);
        x.u16(20); x.u16(20); x.u16(attrs.length);                          // attributeStart, attributeSize, attributeCount
        x.u16(0); x.u16(0); x.u16(0);                                        // no id, class or style attribute
        for (int[] a : attrs) {
            x.u32(a[0]); x.u32(a[1]); x.u32(a[2]);
            x.u16(8); x.u8(0); x.u8(a[3]); x.u32(a[4]);                      // Res_value
        }
    }

    private static void endElement(Le x, int line, int name) {
        x.u16(0x0103); x.u16(16); x.u32(24);
        x.u32(line); x.u32(-1);
        x.u32(-1); x.u32(name);
    }

    /** A growing little-endian buffer (binary XML is little-endian). */
    private static final class Le {
        private byte[] b = new byte[1024];
        private int n;

        private void room(int k) { if (n + k > b.length) b = java.util.Arrays.copyOf(b, Math.max(b.length * 2, n + k)); }

        void u8(int v) { room(1); b[n++] = (byte) v; }

        void u16(int v) { room(2); b[n++] = (byte) v; b[n++] = (byte) (v >> 8); }

        void u32(int v) { room(4); set32At(n, v); n += 4; }

        void set32(int off, int v) { set32At(off, v); }

        private void set32At(int off, int v) { b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24); }

        void align4() { while ((n & 3) != 0) u8(0); }

        int size() { return n; }

        byte[] bytes() { return java.util.Arrays.copyOf(b, n); }
    }

    // ---------------------------------------------------------------------------------------------
    // The package
    // ---------------------------------------------------------------------------------------------

    /** What goes into one font package. Give each font as bytes or as a file (not both); bold may be left out. */
    public static final class Spec {
        public String displayName;
        public String suffix;
        public int versionCode = 1;
        /** null: {@link #versionName(int)} of the versionCode. */
        public String versionName;
        public byte[] regular, bold;
        public File regularFile, boldFile;

        public Spec() {}

        public Spec(String displayName, String suffix, int versionCode, byte[] regular, byte[] bold) {
            this.displayName = displayName;
            this.suffix = suffix;
            this.versionCode = versionCode;
            this.regular = regular;
            this.bold = bold;
        }

        boolean hasBold() { return bold != null || boldFile != null; }
    }

    /** The entry names of a package, in file order: manifest, font description, Regular font, Bold font (when there is one). */
    public static List<String> entryNames(Spec spec) throws Sfnt.FontException {
        String base = fontFileBase(spec.suffix);
        List<String> names = new ArrayList<String>();
        names.add(MANIFEST_ENTRY);
        names.add(XML_DIR + spec.suffix + ".xml");
        names.add(FONTS_DIR + base + ".ttf");
        if (spec.hasBold()) names.add(FONTS_DIR + base + "-Bold.ttf");
        return names;
    }

    public static void writeUnsigned(File out, Spec spec) throws Sfnt.FontException { writeUnsigned(out, spec, FontProgress.NONE); }

    /**
     * Writes the unsigned package to {@code out} (through a ".part" file, so a failure or a Cancel leaves nothing behind): the manifest and
     * the font description deflated, the fonts stored and 4-byte aligned (AssetManager can map them), every entry dated 2008-01-01. The
     * fonts must be TrueType / OpenType files of at most {@link Sfnt#MAX_FONT_BYTES}. Reports stage "package" in bytes.
     */
    public static void writeUnsigned(File out, Spec spec, FontProgress p) throws Sfnt.FontException {
        if (p == null) p = FontProgress.NONE;
        if (out == null || spec == null) throw new Sfnt.FontException("Nothing to package.");
        if (out.isDirectory()) throw new Sfnt.FontException("write_failed", "The font package could not be saved (a folder has its name).");
        String pkg = packageName(spec.suffix);
        if ((spec.regular == null) == (spec.regularFile == null)) throw new Sfnt.FontException("The font package needs exactly one Regular font.");
        if (spec.bold != null && spec.boldFile != null) throw new Sfnt.FontException("The font package can hold only one Bold font.");
        String vName = spec.versionName != null ? spec.versionName : versionName(spec.versionCode);
        byte[] manifest = manifest(pkg, spec.versionCode, vName, spec.displayName);
        String base = fontFileBase(spec.suffix);
        String regularName = base + ".ttf", boldName = base + "-Bold.ttf";
        byte[] xml = fontXml(spec.displayName, regularName, spec.hasBold() ? boldName : null).getBytes(StandardCharsets.UTF_8);
        long regularLen = checkFont(spec.regular, spec.regularFile, "Regular");
        long boldLen = spec.hasBold() ? checkFont(spec.bold, spec.boldFile, "Bold") : 0;
        long work = regularLen + boldLen + (spec.regularFile != null ? regularLen : 0) + (spec.boldFile != null ? boldLen : 0);

        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Sfnt.FontException("write_failed", "The font package could not be saved (no folder for it).");
        File part = new File(dir, out.getName() + ".part");
        boolean ok = false;
        ZipOut z = null;
        try {
            z = new ZipOut(new BufferedOutputStream(new FileOutputStream(part), 1 << 17), p, (int) work);
            p.step("package", 0, (int) work);
            z.deflated(MANIFEST_ENTRY, manifest);
            z.deflated(XML_DIR + spec.suffix + ".xml", xml);
            z.stored(FONTS_DIR + regularName, spec.regular, spec.regularFile, regularLen);
            if (spec.hasBold()) z.stored(FONTS_DIR + boldName, spec.bold, spec.boldFile, boldLen);
            z.finish();
            z.close();
            z = null;
            try {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            p.step("package", (int) work, (int) work);
            ok = true;
        } catch (Sfnt.FontException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw saveError(e);
        } finally {
            if (z != null) try { z.close(); } catch (IOException ignored) {}
            if (!ok) part.delete();
        }
    }

    /** A failed write as a sentence: out of space has its own code; a reason that names a path (Android's usually do) is left out. */
    static Sfnt.FontException saveError(Exception e) {
        String why = e.getMessage() == null ? "" : e.getMessage().trim();
        if (why.contains("ENOSPC") || why.toLowerCase(java.util.Locale.ROOT).contains("no space left"))
            return new Sfnt.FontException("no_space", "There is not enough free space to save the font package.");
        return new Sfnt.FontException("write_failed", "The font package could not be saved" + (why.isEmpty() || why.contains("/") ? "." : " (" + why + ")."));
    }

    /** The font's size, after a look at its first bytes: an sfnt (TrueType or OpenType), not a collection or a web font, not empty or too big. */
    private static long checkFont(byte[] data, File file, String which) throws Sfnt.FontException {
        byte[] head = new byte[4];
        long len;
        if (data != null) {
            len = data.length;
            System.arraycopy(data, 0, head, 0, (int) Math.min(4, len));
        } else {
            if (!file.isFile()) throw new Sfnt.FontException("not_found", "The " + which + " font file is missing.");
            len = file.length();
            if (len >= 4) {
                try (InputStream in = new FileInputStream(file)) {
                    int got = 0, n;
                    while (got < 4 && (n = in.read(head, got, 4 - got)) > 0) got += n;
                } catch (IOException e) {
                    throw new Sfnt.FontException("read_failed", "The " + which + " font file could not be read.");
                }
            }
        }
        if (len > Sfnt.MAX_FONT_BYTES) throw new Sfnt.FontException("too_big", "The " + which + " font is too big for a font package (over " + (Sfnt.MAX_FONT_BYTES >> 20) + " MB).");
        int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
        if (len < 12 || (magic != Sfnt.TRUETYPE && magic != Sfnt.OTTO && magic != Sfnt.TRUE_MAC))
            throw new Sfnt.FontException("not_a_font", "The " + which + " font is not a TrueType or OpenType font file.");
        return len;
    }

    /** A zip written front to back: local headers with known sizes (no data descriptors), then the central directory and the end record. */
    private static final class ZipOut {
        private static final int DOS_TIME = 0, DOS_DATE = (28 << 9) | (1 << 5) | 1;   // 2008-01-01 00:00:00
        private static final int STEP = 256 * 1024;
        private final OutputStream os;
        private final FontProgress p;
        private final int work;
        private final Le cd = new Le();
        private long pos;
        private int count, done, reported;

        ZipOut(OutputStream os, FontProgress p, int work) { this.os = os; this.p = p; this.work = work; }

        void deflated(String name, byte[] data) throws IOException {
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
            byte[] packed;
            try {
                d.setInput(data);
                d.finish();
                byte[] buf = new byte[data.length + 64];
                int n = 0;
                while (!d.finished()) {
                    if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    n += d.deflate(buf, n, buf.length - n);
                }
                packed = java.util.Arrays.copyOf(buf, n);
            } finally {
                d.end();
            }
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            entry(name, 8, crc.getValue(), packed.length, data.length, 0);
            write(packed, 0, packed.length);
        }

        /** A stored entry from bytes or from a file; a file is read twice (its CRC goes in front of the data) and must not change between. */
        void stored(String name, byte[] data, File file, long len) throws IOException {
            long crc;
            if (data != null) {
                CRC32 c = new CRC32();
                c.update(data, 0, data.length);
                crc = c.getValue();
            } else {
                crc = copy(file, len, null);
            }
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int pad = (int) ((4 - (pos + 30 + nameBytes.length) % 4) % 4);
            if (pad > 0) pad += 4;                     // room for a zipalign padding record (id 0xD935) rather than 1-3 stray bytes
            entry(name, 0, crc, len, len, pad);
            if (data != null) {
                for (int off = 0; off < data.length; off += STEP) {
                    int n = Math.min(STEP, data.length - off);
                    write(data, off, n);
                    progress(n);
                }
            } else if (copy(file, len, os) != crc) {
                throw new Sfnt.FontException("read_failed", "The font file changed while it was being packaged. Try again.");
            }
        }

        /** Reads {@code len} bytes of {@code file} (to {@code to} when not null) and returns their CRC-32. */
        private long copy(File file, long len, OutputStream to) throws IOException {
            CRC32 c = new CRC32();
            byte[] buf = new byte[STEP];
            long left = len;
            try (InputStream in = new FileInputStream(file)) {
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new Sfnt.FontException("read_failed", "The font file changed while it was being packaged. Try again.");
                    c.update(buf, 0, n);
                    if (to != null) write(buf, 0, n);
                    left -= n;
                    progress(n);
                }
                if (in.read() >= 0) throw new Sfnt.FontException("read_failed", "The font file changed while it was being packaged. Try again.");
            }
            return c.getValue();
        }

        private void progress(int n) throws Sfnt.FontException {
            done += n;
            if (done - reported >= STEP || done >= work) {
                reported = done;
                FontProgress.Check.cancel(p);
                p.step("package", Math.min(done, work), work);
            }
        }

        private void entry(String name, int method, long crc, long csize, long size, int pad) throws IOException {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int needed = method == 8 ? 20 : 10;
            Le h = new Le();
            h.u32(0x04034b50);
            h.u16(needed); h.u16(0); h.u16(method); h.u16(DOS_TIME); h.u16(DOS_DATE);
            h.u32((int) crc); h.u32((int) csize); h.u32((int) size);
            h.u16(nameBytes.length); h.u16(pad);
            byte[] hb = h.bytes();
            long lho = pos;
            write(hb, 0, hb.length);
            write(nameBytes, 0, nameBytes.length);
            if (pad > 0) {
                byte[] x = new byte[pad];
                x[0] = (byte) 0x35; x[1] = (byte) 0xD9; x[2] = (byte) (pad - 4);
                write(x, 0, pad);
            }
            cd.u32(0x02014b50);
            cd.u16(20); cd.u16(needed); cd.u16(0); cd.u16(method); cd.u16(DOS_TIME); cd.u16(DOS_DATE);
            cd.u32((int) crc); cd.u32((int) csize); cd.u32((int) size);
            cd.u16(nameBytes.length); cd.u16(0); cd.u16(0);                  // name, extra, comment lengths
            cd.u16(0); cd.u16(0); cd.u32(0);                                 // disk, internal and external attributes
            cd.u32((int) lho);
            for (byte b : nameBytes) cd.u8(b);
            count++;
        }

        void finish() throws IOException {
            long cdStart = pos;
            byte[] c = cd.bytes();
            write(c, 0, c.length);
            Le e = new Le();
            e.u32(0x06054b50);
            e.u16(0); e.u16(0); e.u16(count); e.u16(count);
            e.u32(c.length); e.u32((int) cdStart); e.u16(0);
            byte[] eb = e.bytes();
            write(eb, 0, eb.length);
            os.flush();
        }

        private void write(byte[] b, int off, int n) throws IOException {
            os.write(b, off, n);
            pos += n;
        }

        void close() throws IOException { os.close(); }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);            // every Java runtime has SHA-256
        }
    }
}
