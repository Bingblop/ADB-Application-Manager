package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Google Fonts browser of the Custom Fonts tab (v7.18.0), the part that needs no Android classes (tested on the JDK:
 * tests/java/src/GoogleFontsTest.java). The network is MainActivity's ({@code HttpSafe.open}); this class reads what Google Fonts answered
 * and decides what the app asks for and keeps.
 *
 * <ul>
 * <li>{@link #compact}: the live catalog (fonts.google.com/metadata/fonts, 2.7 MB) cut down to what the page shows and filters by
 * (design section 2.5); a family whose name is not a plain Google Fonts name is left out, so a name can never be a path or markup.</li>
 * <li>{@link #css2Url} and {@link #parseCss}: a preview is the css2 API's answer for a few characters ({@code text=}), whose small woff2
 * faces the page loads with FontFace.</li>
 * <li>{@link #plan}: which files of a family's download list the app downloads (the variable fonts when there are any, never a name
 * that is not a plain .ttf / .otf name, never an address outside fonts.gstatic.com), and its licence with its Reserved Font Names
 * ({@link #reservedNames}).</li>
 * <li>{@link #info}: a family's description (as plain text; its "article" when "description" is empty), licence and designers.</li>
 * <li>{@link #metaJson} / {@link #readMeta} / {@link #sweep}: a downloaded family's folder files/fontmaker/gfonts/fam/&lt;slug&gt;/ and
 * its meta.json; {@link #downloadsRow} is the page's row for it.</li>
 * <li>The preview cache in cache/fontmaker/gfpv/ ({@link #previewFile}, {@link #readCached}, {@link #trimCache}), each answer named by the css2
 * request it stands for.</li>
 * </ul>
 * Every refusal is a {@link GfException} whose code and sentence are the page's (design section 5.1).
 */
public final class GoogleFonts {

    private GoogleFonts() {}

    // ---------------------------------------------------------------------------------------------
    // Addresses, caps, sentences
    // ---------------------------------------------------------------------------------------------

    static final String CATALOG_URL = "https://fonts.google.com/metadata/fonts";
    static final String LIST_URL = "https://fonts.google.com/download/list?family=";
    static final String DETAIL_URL = "https://fonts.google.com/metadata/fonts/";
    static final String CSS2_URL = "https://fonts.googleapis.com/css2?family=";
    static final String GSTATIC = "https://fonts.gstatic.com/";
    /** css2 serves woff2 faces (one per style for a text= request) only to a browser it knows; anything else gets TrueType. */
    static final String CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    static final String APP_UA = "ADB-Application-Manager";
    private static final Set<String> HOSTS = new HashSet<String>(Arrays.asList("fonts.google.com", "fonts.googleapis.com", "fonts.gstatic.com"));

    static final long MAX_CATALOG = 16L << 20, MAX_LIST = 1L << 20, MAX_DETAIL = 2L << 20, MAX_CSS = 256L << 10;
    static final long MAX_FACE = 2L << 20, MAX_PREVIEW = 8L << 20, MAX_FILE = 64L << 20, MAX_FAMILY = 160L << 20;
    static final int MAX_FILES = 64, MAX_TEXT = 300, MAX_LICENSE = 64 * 1024, MAX_DESCRIPTION = 4000;
    static final int MAX_DESIGNERS = 8, MAX_DESIGNER = 80, MAX_STYLES = 18, MAX_FACES = 64, MAX_AXES = 32;
    /** A list older than this is refreshed in the background. */
    static final long STALE_MS = 7L * 24 * 3600 * 1000;
    /** The preview cache: at most this much, oldest first; an answer older than this is not used. */
    static final long PREVIEW_CACHE_BYTES = 24L << 20, PREVIEW_CACHE_MS = 30L * 24 * 3600 * 1000;
    /** Family details (gfontsInfo) kept in memory. */
    static final int INFO_CACHE = 24;

    public static final String E_UNKNOWN = "This family is not in the Google Fonts list. Update the list and try again.";
    public static final String E_BAD_NAME = "That is not a Google Fonts family name.";
    public static final String E_BAD_ANSWER = "Google Fonts sent an answer the app cannot read. Try again later.";
    public static final String E_NO_FILES = "Google Fonts lists no font files for this family.";
    public static final String E_TOO_BIG = "This family is larger than the app downloads (160 MB or 64 files).";
    public static final String E_NOT_A_FONT = "Google Fonts sent a file that is not a font, so the family was not kept.";
    public static final String E_NO_PREVIEW = "Google Fonts sent no preview for this family.";
    public static final String E_NOT_DOWNLOADED = "Download this family first.";
    public static final String E_BAD_FILE = "That file is not part of this family's download.";
    public static final String E_CHANGED = "This family's files changed on the phone. Delete it and download it again.";

    /** The page's sentence for one of this class's codes (design section 5.1); the code itself for any other. */
    public static String sentence(String code) {
        if ("gf_unknown".equals(code)) return E_UNKNOWN;
        if ("gf_bad_name".equals(code)) return E_BAD_NAME;
        if ("gf_bad_answer".equals(code)) return E_BAD_ANSWER;
        if ("gf_no_files".equals(code)) return E_NO_FILES;
        if ("gf_too_big".equals(code)) return E_TOO_BIG;
        if ("gf_not_a_font".equals(code)) return E_NOT_A_FONT;
        if ("gf_no_preview".equals(code)) return E_NO_PREVIEW;
        if ("gf_not_downloaded".equals(code)) return E_NOT_DOWNLOADED;
        if ("gf_bad_file".equals(code)) return E_BAD_FILE;
        if ("gf_changed".equals(code)) return E_CHANGED;
        return code;
    }

    /** A refusal with the page's code; the message is the page's sentence for it. */
    public static final class GfException extends Exception {
        public final String code;
        public GfException(String code) { super(sentence(code)); this.code = code; }
    }

    // ---------------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------------

    private static final Pattern FAMILY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ]{0,63}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._,+-]{0,120}\\.(ttf|otf)");
    private static final Pattern AXIS_TAG = Pattern.compile("[A-Za-z0-9]{4}");
    private static final Pattern STYLE = Pattern.compile("(\\d{1,4})(i?)");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SUBSET = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern SCRIPT = Pattern.compile("[A-Z][a-z]{3}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** The licence files a download list carries inline, with the licence each one is. */
    static final List<String> LICENSE_FILES = Collections.unmodifiableList(Arrays.asList("OFL.txt", "LICENSE.txt", "UFL.txt"));

    /** Every Google Fonts family name is plain ASCII letters, digits and spaces (checked against all 1,950): nothing else is taken. */
    public static boolean isFamily(String s) { return s != null && FAMILY.matcher(s).matches(); }

    /** A font file name the app keeps (the last part of a download list's file name). */
    public static boolean isFileName(String s) { return s != null && FILE_NAME.matcher(s).matches(); }

    /** The family's folder name: lowercased, spaces to "-" ("Open Sans" is open-sans). */
    public static String slug(String family) { return family.toLowerCase(Locale.ROOT).replace(' ', '-'); }

    /** Drops the anti-XSSI line ")]}'" Google puts in front of a JSON answer. */
    public static String stripXssi(String s) {
        if (s == null) return "";
        String t = s.startsWith("\ufeff") ? s.substring(1) : s;
        if (!t.startsWith(")]}'")) return t;
        int nl = t.indexOf('\n');
        return nl < 0 ? t.substring(4) : t.substring(nl + 1);
    }

    /** An address the app may open for Google Fonts: https on fonts.google.com, fonts.googleapis.com or fonts.gstatic.com, nothing else in it. */
    public static boolean allowed(String url) {
        if (url == null || url.length() > 8192) return false;             // the longest css2 address (32 axes, 300 4-byte characters) is under 5,000
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '"' || c == '\'' || c == '<' || c == '>') return false;
        }
        try {
            URI u = new URI(url);
            return "https".equals(u.getScheme()) && u.getRawUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getHost() != null && HOSTS.contains(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The catalog (design section 2.5)
    // ---------------------------------------------------------------------------------------------

    /**
     * The catalog the page gets: {v:1, at, axes:{tag: displayName}, families:[{f, c, d, p, t, a, m, s, w, x, k, col?, sym?, noto?}]}, the
     * families in the order of the source list. A family whose name fails {@link #isFamily} is left out, and so is a second one of the same
     * name. Throws gf_bad_answer when the answer is not JSON or has no familyMetadataList.
     */
    public static JSONObject compact(String metadataJson, long at) throws GfException {
        JSONObject root;
        try {
            root = new JSONObject(stripXssi(metadataJson));
        } catch (Exception e) {
            throw new GfException("gf_bad_answer");
        }
        JSONArray list = root.optJSONArray("familyMetadataList");
        if (list == null) throw new GfException("gf_bad_answer");
        try {
            JSONObject axes = new JSONObject();
            JSONArray reg = root.optJSONArray("axisRegistry");
            if (reg != null) {
                for (int i = 0; i < reg.length(); i++) {
                    JSONObject a = reg.optJSONObject(i);
                    if (a == null) continue;
                    String tag = a.optString("tag", ""), name = cut(a.optString("displayName", "").trim(), 64);
                    if (AXIS_TAG.matcher(tag).matches() && !name.isEmpty() && !axes.has(tag)) axes.put(tag, name);
                }
            }
            JSONArray fams = new JSONArray();
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null) continue;
                Object name = f.opt("family");
                if (!(name instanceof String) || !isFamily((String) name) || !seen.add((String) name)) continue;
                fams.put(entry((String) name, f));
            }
            return new JSONObject().put("v", 1).put("at", at).put("axes", axes).put("families", fams);
        } catch (JSONException e) {
            throw new GfException("gf_bad_answer");
        }
    }

    /**
     * The catalog kept in files/fontmaker/gfonts/catalog.json ({@link #compact}'s answer, written whole), or null when it is missing, larger
     * than a catalog answer can be, or not one: another version, no time, no axes or families, a family whose name is not a plain Google
     * Fonts name (the bridge looks families up by it).
     */
    public static JSONObject readCatalog(File f) {
        try {
            if (f == null || !f.isFile() || f.length() > MAX_CATALOG) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("v", 0) != 1 || o.optLong("at", 0) <= 0 || o.optJSONObject("axes") == null) return null;
            JSONArray fams = o.optJSONArray("families");
            if (fams == null) return null;
            for (int i = 0; i < fams.length(); i++) {
                JSONObject e = fams.optJSONObject(i);
                if (e == null || !(e.opt("f") instanceof String) || !isFamily(e.optString("f"))) return null;
            }
            return o;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** A list got at {@code at} is refreshed: none (0), older than 7 days, or from a time ahead of the phone's clock (the clock was set back). */
    public static boolean isStale(long at, long nowMs) {
        return at <= 0 || nowMs < at || nowMs - at > STALE_MS;
    }

    /** The catalog's families by name (the first of a name, as {@link #compact} keeps them). */
    public static Map<String, JSONObject> index(JSONObject catalog) {
        Map<String, JSONObject> by = new HashMap<String, JSONObject>();
        JSONArray fams = catalog == null ? null : catalog.optJSONArray("families");
        if (fams == null) return by;
        for (int i = 0; i < fams.length(); i++) {
            JSONObject e = fams.optJSONObject(i);
            if (e != null && isFamily(e.optString("f", null)) && !by.containsKey(e.optString("f"))) by.put(e.optString("f"), e);
        }
        return by;
    }

    /** A family's download list and its details (spaces as "+" in the query, as "%20" in the path; a name has nothing else to encode). */
    public static String listUrl(String family) {
        if (!isFamily(family)) throw new IllegalArgumentException("not a family");
        return LIST_URL + family.replace(' ', '+');
    }

    public static String detailUrl(String family) {
        if (!isFamily(family)) throw new IllegalArgumentException("not a family");
        return DETAIL_URL + family.replace(" ", "%20");
    }

    private static JSONObject entry(String name, JSONObject f) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("f", name);
        o.put("c", category(f.optString("category", "")));
        JSONArray d = new JSONArray();
        JSONArray ds = f.optJSONArray("designers");
        if (ds != null) {
            for (int i = 0; i < ds.length() && d.length() < MAX_DESIGNERS; i++) {
                Object v = ds.opt(i);
                String s = v instanceof String ? (String) v : v instanceof JSONObject ? ((JSONObject) v).optString("name", "") : "";
                s = cut(oneLine(s), MAX_DESIGNER);
                if (!s.isEmpty()) d.put(s);
            }
        }
        o.put("d", d);
        o.put("p", rank(f.opt("popularity")));
        o.put("t", rank(f.opt("trending")));
        o.put("a", date(f.opt("dateAdded")));
        o.put("m", date(f.opt("lastModified")));
        JSONArray s = new JSONArray();
        JSONArray ss = f.optJSONArray("subsets");
        if (ss != null) {
            Set<String> got = new HashSet<String>();
            for (int i = 0; i < ss.length() && s.length() < 64; i++) {
                Object v = ss.opt(i);
                if (v instanceof String && !"menu".equals(v) && SUBSET.matcher((String) v).matches() && got.add((String) v)) s.put(v);
            }
        }
        o.put("s", s);
        o.put("w", new JSONArray(styles(f.optJSONObject("fonts"))));
        JSONArray x = new JSONArray();
        JSONArray xs = f.optJSONArray("axes");
        if (xs != null) {
            Set<String> got = new HashSet<String>();
            for (int i = 0; i < xs.length() && x.length() < MAX_AXES; i++) {
                JSONObject a = xs.optJSONObject(i);
                if (a == null) continue;
                String tag = a.optString("tag", "");
                Object min = a.opt("min"), max = a.opt("max"), def = a.opt("defaultValue");
                if (!AXIS_TAG.matcher(tag).matches() || !finite(min) || !finite(max) || !finite(def) || !got.add(tag)) continue;
                x.put(new JSONArray().put(tag).put(num(((Number) min).doubleValue())).put(num(((Number) max).doubleValue())).put(num(((Number) def).doubleValue())));
            }
        }
        o.put("x", x);
        String k = f.optString("primaryScript", "");
        o.put("k", SCRIPT.matcher(k).matches() ? k : "");
        JSONArray col = f.optJSONArray("colorCapabilities");
        if (col != null && col.length() > 0) o.put("col", 1);
        JSONArray cls = f.optJSONArray("classifications");
        if (cls != null) for (int i = 0; i < cls.length(); i++) if ("Symbols".equals(cls.opt(i))) { o.put("sym", 1); break; }
        if (f.optBoolean("isNoto", false)) o.put("noto", 1);
        return o;
    }

    /** The page's category: sans | serif | display | hand | mono, "" when unknown. */
    static String category(String c) {
        if ("Sans Serif".equals(c)) return "sans";
        if ("Serif".equals(c)) return "serif";
        if ("Display".equals(c)) return "display";
        if ("Handwriting".equals(c)) return "hand";
        if ("Monospace".equals(c)) return "mono";
        return "";
    }

    /** The styles of a family's fonts{} ("400", "400i" ...; a weight 1..1000 and "i" for italic): weight ascending, upright before italic. */
    static List<String> styles(JSONObject fonts) {
        List<int[]> got = new ArrayList<int[]>();
        Set<String> seen = new HashSet<String>();
        if (fonts != null) {
            Iterator<String> it = fonts.keys();
            while (it.hasNext()) {
                String key = it.next();
                Matcher m = STYLE.matcher(key);
                if (!m.matches()) continue;
                int w = Integer.parseInt(m.group(1));
                if (w < 1 || w > 1000 || !seen.add(w + m.group(2))) continue;
                got.add(new int[] { w, m.group(2).isEmpty() ? 0 : 1 });
            }
        }
        Collections.sort(got, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]; }
        });
        List<String> out = new ArrayList<String>();
        for (int[] s : got) out.add(s[0] + (s[1] == 1 ? "i" : ""));
        return out;
    }

    private static int rank(Object v) {
        if (!(v instanceof Number)) return 0;
        long n = ((Number) v).longValue();
        return n < 0 || n > Integer.MAX_VALUE ? 0 : (int) n;
    }

    private static String date(Object v) {
        return v instanceof String && DATE.matcher((String) v).matches() ? (String) v : "";
    }

    private static boolean finite(Object v) {
        return v instanceof Number && !Double.isNaN(((Number) v).doubleValue()) && !Double.isInfinite(((Number) v).doubleValue())
                && Math.abs(((Number) v).doubleValue()) < 1e7;
    }

    /** A whole number as an integer (100, not 100.0), else the double. */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return (long) v;
        return v;
    }

    /** A number as css2 writes it: 100, -200, 0.5 (no exponent, no trailing zeros). */
    static String plain(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------------------------------------
    // Previews: the css2 request and its answer
    // ---------------------------------------------------------------------------------------------

    /**
     * The css2 address of a preview of {@code entry} (a catalog family): mode "card" (one face: 400, else the family's first style),
     * "styles" (every style, at most 18) or "axes" (the variable font's whole ranges; "card" for a family without axes), for the characters
     * of {@code text} ({@link #uniqueText}, the family name when there are none). Axis tags in css2's order: ital first, then the lowercase
     * tags, then the uppercase ones, each alphabetically. A mode or a name the bridge did not check is a caller bug.
     */
    public static String css2Url(JSONObject entry, String mode, String text) {
        String f = entry == null ? null : entry.optString("f", null);
        if (!isFamily(f)) throw new IllegalArgumentException("not a family");
        if (!"card".equals(mode) && !"styles".equals(mode) && !"axes".equals(mode)) throw new IllegalArgumentException("not a preview mode");
        List<String> w = new ArrayList<String>();
        JSONArray ws = entry.optJSONArray("w");
        if (ws != null) for (int i = 0; i < ws.length(); i++) { String s = ws.optString(i, ""); if (STYLE.matcher(s).matches()) w.add(s); }
        JSONArray x = entry.optJSONArray("x");
        String spec;
        if ("axes".equals(mode) && x != null && x.length() > 0) {
            spec = axesSpec(x);
        } else if ("styles".equals(mode) && !w.isEmpty()) {
            spec = stylesSpec(w.size() > MAX_STYLES ? w.subList(0, MAX_STYLES) : w);
        } else if (w.isEmpty() || w.contains("400")) {
            spec = "";
        } else {
            spec = stylesSpec(w.subList(0, 1));
        }
        return CSS2_URL + f.replace(' ', '+') + spec + "&text=" + pct(uniqueText(text, f)) + "&display=block";
    }

    private static String stylesSpec(List<String> styles) {
        List<int[]> t = new ArrayList<int[]>();
        boolean italic = false;
        for (String s : styles) {
            boolean i = s.endsWith("i");
            italic |= i;
            t.add(new int[] { i ? 1 : 0, Integer.parseInt(i ? s.substring(0, s.length() - 1) : s) });
        }
        Collections.sort(t, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]; }
        });
        StringBuilder b = new StringBuilder(italic ? ":ital,wght@" : ":wght@");
        for (int k = 0; k < t.size(); k++) {
            if (k > 0) b.append(';');
            if (italic) b.append(t.get(k)[0]).append(',');
            b.append(t.get(k)[1]);
        }
        return b.toString();
    }

    private static String axesSpec(JSONArray x) {
        List<JSONArray> axes = new ArrayList<JSONArray>();
        for (int i = 0; i < x.length(); i++) {
            JSONArray a = x.optJSONArray(i);
            if (a != null && a.length() >= 3 && AXIS_TAG.matcher(a.optString(0, "")).matches() && a.opt(1) instanceof Number && a.opt(2) instanceof Number) axes.add(a);
        }
        Collections.sort(axes, new Comparator<JSONArray>() {
            @Override public int compare(JSONArray a, JSONArray b) { return axisOrder(a.optString(0)).compareTo(axisOrder(b.optString(0))); }
        });
        StringBuilder tags = new StringBuilder(), values = new StringBuilder();
        for (JSONArray a : axes) {
            if (tags.length() > 0) { tags.append(','); values.append(','); }
            tags.append(a.optString(0));
            double min = a.optDouble(1), max = a.optDouble(2);
            values.append(min == max ? plain(min) : plain(Math.min(min, max)) + ".." + plain(Math.max(min, max)));
        }
        return ":" + tags + "@" + values;
    }

    /** css2's axis order as a sort key: ital, then lowercase tags, then uppercase ones. */
    private static String axisOrder(String tag) {
        if ("ital".equals(tag)) return "0";
        return (Character.isLowerCase(tag.charAt(0)) ? "1" : "2") + tag;
    }

    /**
     * The characters a preview asks for: each code point once, in order, without control characters (C0, DEL, C1) or a lone surrogate, at
     * most 300; none at all is {@code fallback} + " Aa" (the family name), treated the same way.
     */
    public static String uniqueText(String text, String fallback) {
        String u = distinct(text);
        return u.isEmpty() ? distinct((fallback == null ? "" : fallback) + " Aa") : u;
    }

    private static String distinct(String s) {
        if (s == null) return "";
        Set<Integer> seen = new LinkedHashSet<Integer>();
        for (int i = 0; i < s.length() && seen.size() < MAX_TEXT; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) || (cp >= 0xd800 && cp <= 0xdfff)) continue;
            seen.add(cp);
        }
        StringBuilder b = new StringBuilder();
        for (int cp : seen) b.appendCodePoint(cp);
        return b.toString();
    }

    /** Percent-encodes UTF-8 (everything but A-Z a-z 0-9 - _ . ~). */
    static String pct(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') b.append((char) c);
            else b.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return b.toString();
    }

    private static final Pattern ANGLE = Pattern.compile("(-?\\d{1,3}(?:\\.\\d{1,4})?)deg");

    /**
     * A face's font-style as the page registers it: "italic" for italic and for a slanted oblique, "normal" for anything else, including an
     * oblique range that takes in 0deg (a variable font with a slnt axis, Roboto Flex's "oblique 0deg 10deg", is also the upright face).
     */
    static String faceStyle(String v) {
        String l = v.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("italic")) return "italic";
        if (!l.startsWith("oblique")) return "normal";
        Matcher a = ANGLE.matcher(l);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        while (a.find()) { double d = Double.parseDouble(a.group(1)); lo = Math.min(lo, d); hi = Math.max(hi, d); }
        return lo <= 0 && hi >= 0 ? "normal" : "italic";
    }

    /** One face of a preview: font-style ("normal" | "italic"), font-weight ("400" | "100 900"), font-stretch ("" | "75% 100%") and its woff2. */
    public static final class Face {
        public final String style, weight, stretch, url;
        Face(String style, String weight, String stretch, String url) { this.style = style; this.weight = weight; this.stretch = stretch; this.url = url; }
    }

    private static final Pattern FONT_FACE = Pattern.compile("@font-face\\s*\\{([^{}]*)\\}");
    private static final Pattern PROP = Pattern.compile("(?:^|;)\\s*(font-style|font-weight|font-stretch|src)\\s*:\\s*([^;]*)");
    private static final Pattern SRC_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\"()\\s]+)\\1\\s*\\)");
    private static final Pattern WEIGHT = Pattern.compile("\\d{1,4}( \\d{1,4})?");
    private static final Pattern STRETCH = Pattern.compile("\\d{1,3}(\\.\\d{1,4})?%( \\d{1,3}(\\.\\d{1,4})?%)?");

    /**
     * The faces of a css2 answer: one per {@code @font-face} whose source is a font on fonts.gstatic.com (a source anywhere else is left
     * out), at most 64. The style is {@link #faceStyle}'s; a weight or stretch of another shape is "400" / "". Throws
     * gf_no_preview when there is none.
     */
    public static List<Face> parseCss(String css) throws GfException {
        List<Face> out = new ArrayList<Face>();
        Matcher m = FONT_FACE.matcher(css == null ? "" : css);
        while (m.find() && out.size() < MAX_FACES) {
            String style = "normal", weight = "400", stretch = "", url = null;
            Matcher p = PROP.matcher(m.group(1));
            while (p.find()) {
                String k = p.group(1), v = p.group(2).trim();
                if ("font-style".equals(k)) {
                    style = faceStyle(v);
                } else if ("font-weight".equals(k)) {
                    weight = WEIGHT.matcher(v).matches() ? v : "400";
                } else if ("font-stretch".equals(k)) {
                    stretch = STRETCH.matcher(v).matches() ? v : "";
                } else if (url == null) {
                    Matcher u = SRC_URL.matcher(v);
                    while (u.find()) {
                        if (u.group(2).startsWith(GSTATIC) && allowed(u.group(2))) { url = u.group(2); break; }
                    }
                }
            }
            if (url != null) out.add(new Face(style, weight, stretch, url));
        }
        if (out.isEmpty()) throw new GfException("gf_no_preview");
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // A family's download list
    // ---------------------------------------------------------------------------------------------

    /** One font file of a family: its name in the family's folder, where it comes from, and what its name says. */
    public static final class FileRef {
        public final String name, url;
        public final boolean variable, italic;
        FileRef(String name, String url) {
            this.name = name; this.url = url;
            variable = name.contains("VariableFont");
            italic = name.contains("Italic");
        }
    }

    /** What the app downloads of a family: the font files, and the licence (its file name, kind, text and Reserved Font Names). */
    public static final class Plan {
        public final List<FileRef> files;
        /** OFL.txt, LICENSE.txt or UFL.txt; "" when the list has none. */
        public final String licenseFile, licenseText;
        /** ofl, apache2 or ufl (from the licence's file); "" when there is none. */
        public final String licenseKind;
        public final List<String> reserved;
        Plan(List<FileRef> files, String licenseFile, String licenseText, List<String> reserved) {
            this.files = Collections.unmodifiableList(files);
            this.licenseFile = licenseFile; this.licenseText = licenseText;
            this.licenseKind = kindOfFile(licenseFile);
            this.reserved = Collections.unmodifiableList(reserved);
        }
    }

    static String kindOfFile(String licenseFile) {
        if ("OFL.txt".equals(licenseFile)) return "ofl";
        if ("LICENSE.txt".equals(licenseFile)) return "apache2";
        if ("UFL.txt".equals(licenseFile)) return "ufl";
        return "";
    }

    /**
     * The files to download from a family's download list (fonts.google.com/download/list, with or without its ")]}'" line). A file is
     * kept under the last part of its name, only when that is a plain .ttf / .otf name, its address is on fonts.gstatic.com and no file
     * before it has the name. The fonts at the top level of the list are taken (a variable family lists its variable fonts there and its
     * static instances under static/), all of them when there are none at the top level. gf_unknown for Google's "Unable to find family",
     * gf_no_files when nothing is left, gf_too_big for more than 64 files, gf_bad_answer for anything else it cannot read.
     */
    public static Plan plan(String downloadListJson) throws GfException {
        JSONObject root;
        try {
            root = new JSONObject(stripXssi(downloadListJson));
        } catch (Exception e) {
            throw new GfException("gf_bad_answer");
        }
        if (root.has("error")) {
            if (root.optString("error", "").contains("Unable to find family")) throw new GfException("gf_unknown");
            throw new GfException("gf_bad_answer");
        }
        JSONObject man = root.optJSONObject("manifest");
        JSONArray refs = man == null ? null : man.optJSONArray("fileRefs");
        if (refs == null) throw new GfException("gf_bad_answer");
        List<FileRef> top = new ArrayList<FileRef>(), all = new ArrayList<FileRef>();
        for (int i = 0; i < refs.length(); i++) {
            JSONObject r = refs.optJSONObject(i);
            if (r == null) continue;
            Object fn = r.opt("filename"), url = r.opt("url");
            if (!(fn instanceof String) || !(url instanceof String)) continue;
            String full = (String) fn, name = full.substring(full.lastIndexOf('/') + 1);
            if (!isFileName(name) || !((String) url).startsWith(GSTATIC) || !allowed((String) url)) continue;
            FileRef f = new FileRef(name, (String) url);
            all.add(f);
            if (full.indexOf('/') < 0) top.add(f);
        }
        List<FileRef> files = new ArrayList<FileRef>();
        Set<String> names = new HashSet<String>();
        for (FileRef f : top.isEmpty() ? all : top) if (names.add(f.name)) files.add(f);
        if (files.isEmpty()) throw new GfException("gf_no_files");
        if (files.size() > MAX_FILES) throw new GfException("gf_too_big");
        String licFile = "", licText = "";
        JSONArray inline = man.optJSONArray("files");
        if (inline != null) {
            for (int i = 0; i < inline.length(); i++) {
                JSONObject f = inline.optJSONObject(i);
                if (f == null || !LICENSE_FILES.contains(f.optString("filename", "")) || !(f.opt("contents") instanceof String)) continue;
                licFile = f.optString("filename");
                licText = (String) f.opt("contents");
                if (licText.length() > MAX_LICENSE) licText = cut(licText, MAX_LICENSE);
                break;
            }
        }
        return new Plan(files, licFile, licText, reservedNames(licText));
    }

    // the phrase, then a space, a comma or a colon ("Reserved Font Names, "Chango"", "Reserved Font Name: "Orbitron""); the OFL's own
    // '"Reserved Font Name" refers to' and 'Reserved Font Name(s)' are not followed by one
    private static final Pattern RESERVED = Pattern.compile("Reserved\\s+Font\\s+Names?(?=[\\s,:])", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NAME = Pattern.compile("([A-Za-z0-9][A-Za-z0-9 -]{0,62}[A-Za-z0-9]|[A-Za-z0-9])\\s*(?:[.,;]|\\r?\\n|$)");
    // the quoted name just before the phrase: '"Jomolhari" is a Reserved Font Name for this Font Software.' (Jomolhari, Uchen)
    private static final Pattern NAME_BEFORE = Pattern.compile("[\"'\u201c\u2018]([^\"'\u201c\u201d\u2018\u2019\\r\\n]{1,80})[\"'\u201d\u2019]\\s+(?:is|are)\\s+(?:an?\\s+|the\\s+)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The Reserved Font Names of an OFL licence, each once, in order. Every quoted name after "Reserved Font Name" or "Reserved Font Names"
     * ("A" and "B", "A", "B" and "C", "A" &amp; "B"), the phrase also followed by a comma, a colon or "is" ('Reserved Font Names, "Chango"',
     * 'Reserved Font Name: "Orbitron"', 'Reserved Font Name is "Julee"'). Quotes: double, single, curly, a straight one closed by a curly one
     * (Encode Sans, Homenaje) and &lt;GFS Didot&gt;. A name may run over one line break ('"Stint Ultra\r\nExpanded"'), which becomes a space;
     * a blank line ends it. Each name is trimmed and loses a trailing period ('"Numans."'). The quoted name of '"Jomolhari" is a Reserved
     * Font Name' is taken too. A licence that writes one name without quotes ("with Reserved Font Name UnifrakturCook.") gives that name,
     * but not the words that go on with the sentence ("... Reserved Font Name for this Font Software."). The licence's own text ("Reserved
     * Font Name" refers to ..., the Reserved Font Name(s)) names none.
     */
    public static List<String> reservedNames(String licenseText) {
        List<String> out = new ArrayList<String>();
        if (licenseText == null) return out;
        String t = licenseText.length() > MAX_LICENSE ? licenseText.substring(0, MAX_LICENSE) : licenseText;
        Matcher m = RESERVED.matcher(t);
        while (m.find() && out.size() < 16) {
            Matcher before = NAME_BEFORE.matcher(t);
            before.region(Math.max(0, m.start() - 100), m.start());
            if (before.find()) addName(out, before.group(1));
            int i = skipSpace(t, m.end());
            if (i < t.length() && (t.charAt(i) == ',' || t.charAt(i) == ':')) i = skipSpace(t, i + 1);
            else if (t.regionMatches(true, i, "is", 0, 2) && i + 2 < t.length() && Character.isWhitespace(t.charAt(i + 2))) i = skipSpace(t, i + 2);
            boolean any = false;
            while (i < t.length() && out.size() < 16) {
                char open = t.charAt(i);
                String close = open == '"' ? "\"\u201d" : open == '\'' ? "'\u2019" : open == '\u201c' ? "\u201d\"" : open == '\u2018' ? "\u2019'"
                        : open == '<' ? ">" : null;
                if (close == null) break;
                int end = i + 1, breaks = 0;
                while (end < t.length() && close.indexOf(t.charAt(end)) < 0 && end - i <= 80) {
                    if (t.charAt(end) == '\n' && ++breaks > 1) break;
                    end++;
                }
                if (end >= t.length() || close.indexOf(t.charAt(end)) < 0) break;
                addName(out, t.substring(i + 1, end));
                any = true;
                i = skipSpace(t, end + 1);
                if (i < t.length() && t.charAt(i) == ',') i = skipSpace(t, i + 1);
                if (t.startsWith("and", i) && i + 3 < t.length() && Character.isWhitespace(t.charAt(i + 3))) i = skipSpace(t, i + 3);
                else if (i < t.length() && t.charAt(i) == '&') i = skipSpace(t, i + 1);
            }
            if (!any && i < t.length() && Character.isLetterOrDigit(t.charAt(i)) && !Character.isLowerCase(t.charAt(i))) {
                Matcher b = BARE_NAME.matcher(t);
                b.region(i, Math.min(t.length(), i + 80));
                if (b.lookingAt()) addName(out, b.group(1));
            }
        }
        return out;
    }

    /** One name: white space (a line break inside the quotes) as one space, trimmed, without a trailing period; at most 16 names. */
    private static void addName(List<String> out, String raw) {
        String name = raw.replaceAll("\\s+", " ").trim();
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).trim();
        if (!name.isEmpty() && !out.contains(name) && out.size() < 16) out.add(name);
    }

    private static int skipSpace(String t, int i) {
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        return i;
    }

    // ---------------------------------------------------------------------------------------------
    // A family's details
    // ---------------------------------------------------------------------------------------------

    /** A family's description (plain text), licence (ofl | apache2 | ufl | "") and its name, and the designers' names. */
    public static final class Info {
        public final String description, license, licenseName;
        public final List<String> designers;
        Info(String description, String license, List<String> designers) {
            this.description = description; this.license = license; this.licenseName = licenseName(license);
            this.designers = Collections.unmodifiableList(designers);
        }
    }

    /** The licence's name as the page shows it. */
    public static String licenseName(String kind) {
        if ("ofl".equals(kind)) return "SIL Open Font License 1.1";
        if ("apache2".equals(kind)) return "Apache License 2.0";
        if ("ufl".equals(kind)) return "Ubuntu Font Licence 1.0";
        return "";
    }

    /**
     * A family's details (fonts.google.com/metadata/fonts/&lt;family&gt;, with or without its ")]}'" line). The description is "description"
     * as plain text, else (it is "" for the newer families) "article", its HTML paragraphs as plain text. gf_unknown for Google's
     * not-found answer (an "er" array with status 404: [["er",null,null,null,null,404,...],...]), gf_bad_answer for anything else that is
     * not a family's details, an "er" array with another status (a 503, a 429) among them: that is no answer about the family.
     */
    public static Info info(String familyDetailJson) throws GfException {
        String s = stripXssi(familyDetailJson).trim();
        JSONObject root;
        try {
            root = new JSONObject(s);
        } catch (Exception e) {
            if (s.startsWith("[") && s.contains("\"er\"")) {
                boolean notFound = false;
                try {
                    JSONArray env = new JSONArray(s);
                    for (int i = 0; i < env.length() && !notFound; i++) {
                        JSONArray row = env.optJSONArray(i);
                        notFound = row != null && "er".equals(row.opt(0)) && row.optInt(5, 0) == 404;
                    }
                } catch (Exception notAnArray) {}
                if (notFound) throw new GfException("gf_unknown");
            }
            throw new GfException("gf_bad_answer");
        }
        if (!(root.opt("family") instanceof String)) throw new GfException("gf_bad_answer");
        String lic = root.optString("license", "").toLowerCase(Locale.ROOT);
        if (licenseName(lic).isEmpty()) lic = "";
        List<String> designers = new ArrayList<String>();
        JSONArray ds = root.optJSONArray("designers");
        if (ds != null) {
            for (int i = 0; i < ds.length() && designers.size() < 16; i++) {
                Object v = ds.opt(i);
                String n = v instanceof JSONObject ? ((JSONObject) v).optString("name", "") : v instanceof String ? (String) v : "";
                n = cut(oneLine(n), MAX_DESIGNER);
                if (!n.isEmpty() && !designers.contains(n)) designers.add(n);
            }
        }
        Object desc = root.opt("description");
        String text = plainText(desc instanceof String ? (String) desc : "", MAX_DESCRIPTION);
        if (text.isEmpty()) {
            // the families added since 2023 (Google Sans, Geist...) send "description": "" and their About text as "article", an array of
            // HTML strings (R3-01); an item that is not text is skipped, and the input stops where plainText stops reading anyway
            JSONArray art = root.optJSONArray("article");
            StringBuilder b = new StringBuilder();
            for (int i = 0; art != null && i < art.length() && b.length() <= 4 * MAX_DESCRIPTION + 65536; i++) {
                Object v = art.opt(i);
                if (v instanceof String) b.append(b.length() > 0 ? "<p>" : "").append((String) v);
            }
            text = plainText(b.toString(), MAX_DESCRIPTION);
        }
        return new Info(text, lic, designers);
    }

    /**
     * The page's details of a family (onGfInfo): {ok, family, description, license, licenseName, licenseText, reserved, designers,
     * files:[{name, variable, italic}]}. The licence is the download list's (its text is the one shown, and the one kept with a download),
     * else the details'. {@code info} null (Google has no details for the family, as for the Material Symbols ones) leaves the description
     * empty and takes the designers from the catalog entry ({@code entry}, may be null).
     */
    public static JSONObject infoJson(String family, Plan plan, Info info, JSONObject entry) throws JSONException {
        String kind = !plan.licenseKind.isEmpty() ? plan.licenseKind : info == null ? "" : info.license;
        JSONArray designers = new JSONArray();
        if (info != null) {
            for (String d : info.designers) designers.put(d);
        } else if (entry != null && entry.optJSONArray("d") != null) {
            JSONArray d = entry.optJSONArray("d");
            for (int i = 0; i < d.length(); i++) if (d.opt(i) instanceof String) designers.put(d.opt(i));
        }
        JSONArray files = new JSONArray();
        for (FileRef f : plan.files) files.put(new JSONObject().put("name", f.name).put("variable", f.variable).put("italic", f.italic));
        return new JSONObject().put("ok", true).put("family", family).put("description", info == null ? "" : info.description)
                .put("license", kind).put("licenseName", licenseName(kind)).put("licenseText", plan.licenseText)
                .put("reserved", new JSONArray(plan.reserved)).put("designers", designers).put("files", files);
    }

    // a line break, and the start and end of a block (a paragraph, a list and its items, a heading, a figure and its caption as in Google
    // Sans' article...): the \b keeps <pre>, <link>, <track> out
    private static final Pattern BREAK = Pattern.compile("</?(?:br|p|div|ul|ol|li|dt|dd|h[1-6]|blockquote|tr|hr|figure|figcaption)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM = Pattern.compile("<li\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>?");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,7});");
    /**
     * The named entities a description is written with, as text: HTML 4's Latin-1 ones (iexcl to yuml) and its typographic ones (dashes,
     * quotes, the ellipsis...). copy, reg and trade are written "(c)", "(R)" and "(tm)", as MorpheHelper writes them (their symbols can show as
     * emoji).
     */
    private static final Map<String, String> NAMED = new HashMap<String, String>();
    static {
        String[] latin1 = ("iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para middot"
                + " cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave"
                + " Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute"
                + " acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml"
                + " divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(" ");
        for (int i = 0; i < latin1.length; i++) NAMED.put(latin1[i], String.valueOf((char) (0xa1 + i)));            // U+00A1 to U+00FF, in order
        String[] more = { "OElig", "\u0152", "oelig", "\u0153", "Scaron", "\u0160", "scaron", "\u0161", "Yuml", "\u0178", "fnof", "\u0192",
                "circ", "\u02c6", "tilde", "\u02dc", "ensp", "\u2002", "emsp", "\u2003", "thinsp", "\u2009", "ndash", "\u2013", "mdash", "\u2014",
                "lsquo", "\u2018", "rsquo", "\u2019", "sbquo", "\u201a", "ldquo", "\u201c", "rdquo", "\u201d", "bdquo", "\u201e", "dagger", "\u2020",
                "Dagger", "\u2021", "bull", "\u2022", "hellip", "\u2026", "permil", "\u2030", "prime", "\u2032", "Prime", "\u2033", "lsaquo", "\u2039",
                "rsaquo", "\u203a", "euro", "\u20ac", "copy", "(c)", "reg", "(R)", "trade", "(tm)" };
        for (int i = 0; i < more.length; i += 2) NAMED.put(more[i], more[i + 1]);
    }

    /**
     * HTML as plain text: a line break, and the start and end of a block (a paragraph, a list, a heading, a quote) are a new line, a list item
     * starts with a bullet, every other tag goes, the entities amp lt gt quot apos nbsp, HTML 4's Latin-1 and typographic ones and numeric
     * ones are decoded (an unknown name is left as it is), runs of spaces are one, each line is trimmed and empty lines dropped; at most {@code max}
     * characters (never half of a pair of surrogates).
     */
    public static String plainText(String html, int max) {
        if (html == null || html.isEmpty()) return "";
        String s = html.length() > 4 * MAX_DESCRIPTION + 65536 ? html.substring(0, 4 * MAX_DESCRIPTION + 65536) : html;
        s = s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        s = ITEM.matcher(s).replaceAll("\n\u2022 ");
        s = BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        Matcher m = ENTITY.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(entity(m.group(1), m.group())));
        m.appendTail(b);
        StringBuilder out = new StringBuilder();
        for (String line : b.toString().split("\n")) {
            String l = line.replaceAll("[\\s\\u00a0]+", " ").trim();
            if (l.isEmpty() || l.equals("\u2022")) continue;                        // an empty line, an empty list item
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return cut(out.toString(), max);
    }

    private static String entity(String name, String whole) {
        if ("amp".equals(name)) return "&";
        if ("lt".equals(name)) return "<";
        if ("gt".equals(name)) return ">";
        if ("quot".equals(name)) return "\"";
        if ("apos".equals(name)) return "'";
        if ("nbsp".equals(name)) return " ";
        if (name.charAt(0) != '#') {
            String v = NAMED.get(name);
            return v == null ? whole : v;
        }
        int cp;
        try {
            cp = name.startsWith("#x") || name.startsWith("#X") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
        } catch (NumberFormatException e) {
            return whole;
        }
        if (cp <= 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || cp < 0x20 && cp != '\t' && cp != '\n') return whole;
        return new String(Character.toChars(cp));
    }

    /** At most {@code max} characters, never ending on the first half of a surrogate pair. */
    static String cut(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    // ---------------------------------------------------------------------------------------------
    // A downloaded family: meta.json, the page's row, the sweep
    // ---------------------------------------------------------------------------------------------

    public static final String META = "meta.json";

    /** One downloaded font file as meta.json records it (weight, variable and italic as FontInspect read them). */
    public static final class MetaFile {
        public final String name, sha256;
        public final long size;
        public final boolean variable, italic;
        public final int weight;
        public final List<String> axes;
        public MetaFile(String name, long size, String sha256, boolean variable, boolean italic, int weight, List<String> axes) {
            this.name = name; this.size = size; this.sha256 = sha256; this.variable = variable; this.italic = italic; this.weight = weight;
            this.axes = axes == null ? new ArrayList<String>() : axes;
        }
    }

    /** The family's folder in fam/, and the folder a download is written in before it is renamed to that. */
    public static File familyDir(File famDir, String family) { return new File(famDir, slug(family)); }
    public static File partDir(File famDir, String family) { return new File(famDir, "." + slug(family) + ".part"); }

    /** {v:1, family, at, license:{kind, file, reserved:[...]}, files:[{name, size, sha256, variable, italic, weight, axes:[tag]}]}. */
    public static JSONObject metaJson(String family, long at, String licenseKind, String licenseFile, List<String> reserved, List<MetaFile> files) throws JSONException {
        JSONArray fs = new JSONArray();
        for (MetaFile f : files) {
            fs.put(new JSONObject().put("name", f.name).put("size", f.size).put("sha256", f.sha256).put("variable", f.variable)
                    .put("italic", f.italic).put("weight", f.weight).put("axes", new JSONArray(f.axes)));
        }
        JSONObject lic = new JSONObject().put("kind", licenseKind == null ? "" : licenseKind).put("file", licenseFile == null ? "" : licenseFile)
                .put("reserved", new JSONArray(reserved == null ? new ArrayList<String>() : reserved));
        return new JSONObject().put("v", 1).put("family", family).put("at", at).put("license", lic).put("files", fs);
    }

    /**
     * A family's meta.json in its folder, or null when anything is off: a family that is not the folder's (by its slug), a licence or a
     * file entry of the wrong shape, a name twice, a font file that is not in the folder at the size recorded. What the bridge sends the page
     * and what Use and Save read come only from a meta.json that passed this.
     */
    public static JSONObject readMeta(File dir) {
        try {
            File f = new File(dir, META);
            if (!dir.isDirectory() || !f.isFile() || f.length() > 256 * 1024) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            String family = o.optString("family", "");
            if (o.optInt("v", 0) != 1 || !isFamily(family) || !slug(family).equals(dir.getName()) || o.optLong("at", -1) < 0) return null;
            JSONObject lic = o.optJSONObject("license");
            if (lic == null) return null;
            String kind = lic.optString("kind", null), lf = lic.optString("file", null);
            if (kind == null || (!kind.isEmpty() && licenseName(kind).isEmpty()) || lf == null || (!lf.isEmpty() && !LICENSE_FILES.contains(lf))) return null;
            if (!lf.isEmpty() && !new File(dir, lf).isFile()) return null;
            JSONArray rs = lic.optJSONArray("reserved");
            if (rs == null || rs.length() > 16) return null;
            for (int i = 0; i < rs.length(); i++) if (!(rs.opt(i) instanceof String) || rs.optString(i).length() > 80) return null;
            JSONArray files = o.optJSONArray("files");
            if (files == null || files.length() < 1 || files.length() > MAX_FILES) return null;
            Set<String> names = new HashSet<String>();
            long total = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject e = files.optJSONObject(i);
                if (e == null) return null;
                String name = e.optString("name", "");
                long size = e.optLong("size", -1);
                int weight = e.optInt("weight", -1);
                if (!isFileName(name) || !names.add(name) || size <= 0 || size > MAX_FILE || !SHA256.matcher(e.optString("sha256", "")).matches()) return null;
                if (!(e.opt("variable") instanceof Boolean) || !(e.opt("italic") instanceof Boolean) || weight < 1 || weight > 1000) return null;
                JSONArray ax = e.optJSONArray("axes");
                if (ax == null || ax.length() > 64) return null;
                for (int k = 0; k < ax.length(); k++) if (!(ax.opt(k) instanceof String)) return null;
                if (new File(dir, name).length() != size) return null;
                total += size;
            }
            return total <= MAX_FAMILY ? o : null;
        } catch (Exception bad) {
            return null;
        }
    }

    /** The meta.json entry of one of the family's files, or null when it is not one of them. */
    public static JSONObject metaFile(JSONObject meta, String fileName) {
        JSONArray files = meta == null ? null : meta.optJSONArray("files");
        if (files == null || fileName == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject e = files.optJSONObject(i);
            if (e != null && fileName.equals(e.optString("name"))) return e;
        }
        return null;
    }

    /** The page's row of a downloaded family: {family, at, size, license, licenseName, reserved, files:[{name, size, variable, italic, weight}]}. */
    public static JSONObject downloadsRow(JSONObject meta, long size) throws JSONException {
        JSONObject lic = meta.optJSONObject("license");
        String kind = lic == null ? "" : lic.optString("kind", "");
        JSONArray files = new JSONArray(), src = meta.optJSONArray("files");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject e = src.optJSONObject(i);
                if (e == null) continue;
                files.put(new JSONObject().put("name", e.optString("name")).put("size", e.optLong("size")).put("variable", e.optBoolean("variable"))
                        .put("italic", e.optBoolean("italic")).put("weight", e.optInt("weight")));
            }
        }
        JSONArray reserved = lic == null ? null : lic.optJSONArray("reserved");
        return new JSONObject().put("family", meta.optString("family")).put("at", meta.optLong("at")).put("size", size).put("license", kind)
                .put("licenseName", licenseName(kind)).put("reserved", reserved == null ? new JSONArray() : reserved).put("files", files);
    }

    /** gfontsDownloads' rows: every family in fam/ whose meta.json passes {@link #readMeta}, newest first (the same time: by name). */
    public static JSONArray downloads(File famDir) throws JSONException {
        File[] kids = famDir == null ? null : famDir.listFiles();
        List<JSONObject> rows = new ArrayList<JSONObject>();
        if (kids != null) {
            for (File k : kids) {
                if (k.getName().startsWith(".")) continue;                  // a download or a delete under way
                JSONObject meta = readMeta(k);
                if (meta != null) rows.add(downloadsRow(meta, folderSize(k)));
            }
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long x = a.optLong("at"), y = b.optLong("at");
                return x != y ? (x > y ? -1 : 1) : a.optString("family").compareTo(b.optString("family"));
            }
        });
        return new JSONArray(rows);
    }

    /**
     * A downloaded family's meta.json, only when it is {@code family}'s: the folder name is the family's slug, which "open sans" and "Open
     * Sans" share, so the name itself must match too. Null when it is not downloaded (or not whole).
     */
    public static JSONObject downloaded(File famDir, String family) {
        if (!isFamily(family)) return null;
        JSONObject meta = readMeta(familyDir(famDir, family));
        return meta != null && family.equals(meta.optString("family")) ? meta : null;
    }

    /**
     * Writes a downloaded family as a zip into {@code out} and closes it: each font file of its meta.json, then the licence file, flat (by
     * their names in the folder). Each font is checked while it is written against the size and SHA-256 meta.json recorded: gf_changed when
     * one is not the file that was downloaded. The caller deletes what was written when this throws.
     */
    public static void writeZip(File dir, JSONObject meta, OutputStream out) throws IOException, GfException {
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(out, 65536));
        boolean done = false;
        try {
            JSONArray files = meta.getJSONArray("files");
            byte[] buf = new byte[65536];
            for (int i = 0; i < files.length(); i++) {
                JSONObject e = files.getJSONObject(i);
                String name = e.getString("name");
                if (!isFileName(name)) throw new GfException("gf_changed");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long n = 0;
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                java.io.InputStream in = new java.io.FileInputStream(new File(dir, name));
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        n += r;
                        if (n > e.getLong("size")) throw new GfException("gf_changed");
                        md.update(buf, 0, r);
                        zip.write(buf, 0, r);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
                if (n != e.getLong("size") || !FontLibrary.hex(md.digest()).equals(e.getString("sha256"))) throw new GfException("gf_changed");
            }
            JSONObject lic = meta.optJSONObject("license");
            String lf = lic == null ? "" : lic.optString("file", "");
            if (LICENSE_FILES.contains(lf)) {
                byte[] text = FontLibrary.readFile(new File(dir, lf), MAX_LICENSE * 4L);
                zip.putNextEntry(new java.util.zip.ZipEntry(lf));
                zip.write(text);
                zip.closeEntry();
            }
            zip.finish();
            done = true;
        } catch (JSONException | java.security.NoSuchAlgorithmException e) {
            throw new GfException("gf_changed");
        } finally {
            if (done) zip.close();                                      // the last bytes reach the document here: a failure is a failure
            else try { zip.close(); } catch (IOException ignored) {}    // what stopped the copy is the answer, not the close after it
        }
    }

    /**
     * Puts a whole download in place: the family's .part folder ({@link #partDir}) is renamed to its folder. A folder already there (a family
     * downloaded again) is moved aside under a dot-name first and deleted after (the sweep deletes it when the process ends in between). On
     * an IOException the family's old folder is back in place. Returns the family's folder.
     */
    public static File commitDownload(File famDir, String family) throws IOException {
        File part = partDir(famDir, family), dest = familyDir(famDir, family);
        File old = new File(famDir, "." + slug(family) + ".old-" + System.nanoTime());
        boolean moved = false;
        if (dest.exists()) {
            if (!dest.renameTo(old)) throw new IOException("The family's old folder could not be moved aside.");
            moved = true;
        }
        if (!part.renameTo(dest)) {
            if (moved) old.renameTo(dest);
            throw new IOException("The download could not be put in place.");
        }
        FontLibrary.syncDir(famDir);
        if (moved) FontLibrary.deleteTree(old);
        return dest;
    }

    /** The bytes of a family's folder (its fonts, its licence and meta.json): what Delete frees. */
    public static long folderSize(File dir) {
        long n = 0;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) if (k.isFile()) n += k.length();
        return n;
    }

    /**
     * Tidies fam/ once per process, before any download: a download's .part folder and a family moved aside while it was downloaded again
     * (every folder whose name starts with a dot), a stray file, and a family folder whose meta.json does not pass {@link #readMeta} (meta.json
     * is the last file written before the rename, so such a folder was never a whole download).
     */
    public static void sweep(File famDir) {
        File[] kids = famDir == null ? null : famDir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".") || !k.isDirectory() || readMeta(k) == null) FontLibrary.deleteTree(k);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The preview cache
    // ---------------------------------------------------------------------------------------------

    /**
     * A preview's cache file in {@code dir}: named by {@link #previewKey} of the family, the mode and the css2 address the preview is fetched
     * from ({@link #css2Url}). The address carries the styles or axes asked for (from the entry's w and x) and the characters, so a family whose
     * styles or axes changed in a newer list is asked for again instead of being drawn with the faces it had.
     */
    public static File previewFile(File dir, JSONObject entry, String mode, String text) {
        return new File(dir, previewKey(entry.optString("f"), mode, css2Url(entry, mode, text)) + ".json");
    }

    /** A preview's cache file name: the SHA-256 of family, mode and the css2 address, one per line. */
    public static String previewKey(String family, String mode, String css2Url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return FontLibrary.hex(md.digest((family + "\n" + mode + "\n" + css2Url).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    /** A cached answer, or null when it is missing, older than {@code maxAgeMs}, larger than a preview can be or not an answer. */
    public static String readCached(File f, long nowMs, long maxAgeMs) {
        try {
            if (!f.isFile() || nowMs - f.lastModified() > maxAgeMs || f.length() > MAX_PREVIEW * 4 / 3 + 65536) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(s);
            return o.optBoolean("ok") && o.optJSONArray("faces") != null ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes the oldest files of the cache folder until it holds at most {@code maxBytes}. Returns how many went. */
    public static int trimCache(File dir, long maxBytes) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return 0;
        long total = 0;
        final Map<File, Long> at = new LinkedHashMap<File, Long>();
        List<File> files = new ArrayList<File>();
        for (File k : kids) {
            if (!k.isFile()) continue;
            files.add(k);
            at.put(k, k.lastModified());
            total += k.length();
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = at.get(a), y = at.get(b);
                return x != y ? (x < y ? -1 : 1) : a.getName().compareTo(b.getName());
            }
        });
        int n = 0;
        for (File f : files) {
            if (total <= maxBytes) break;
            long len = f.length();
            if (f.delete()) { total -= len; n++; }
        }
        return n;
    }

    /** Writes a small file whole through {@code <name>.part}, renamed over it (the catalog, a cached preview). */
    public static void writeWhole(File f, byte[] data) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir.getName());
        File part = new File(dir, f.getName() + ".part");
        boolean ok = false;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(part);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            try {
                Files.move(part.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(part.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ok = true;
        } finally {
            if (!ok) part.delete();
        }
    }
}
