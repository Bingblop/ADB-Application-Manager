package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The built-in "Morphe Helper" of the Morphe Patcher tab: finds the original APK a Morphe patch set wants, in one specific version, on the
 * APK sites that can be read without a browser, downloads it and checks what really came down.
 *
 * The logic is ported from the open-source Android app "Helper for Morphe" (https://github.com/rushiranpise/helper-for-morphe, by rushiranpise,
 * GPL-3.0): its ten sources and their order, the version matching (a "-SECONDARY" build never satisfies a plain request, "1.2.3 (456)" carries
 * a build number), Fast Mode (walk the enabled sources, first one that works), the SHA-256 published by a source, and "verify the real manifest
 * before handoff" (package, version name and build of the file that arrived, not of the page that promised it).
 *
 * What differs from Helper: there is no in-app captcha browser and no Play / Aurora token flow here, so a source whose file sits behind a
 * Cloudflare challenge or a Turnstile captcha (Uptodown, Evozi / APKCube, Mi9, APK Downloader, and APKMirror from some networks) can still be
 * read for its version list, but a download from it is refused with a {@link NeedsBrowser} that carries the page to open in the real browser.
 * HTML is read with a small tolerant scanner (no jsoup); every parse failure ends as "&lt;Source&gt; changed its page format, open it in the
 * browser" instead of an exception trace.
 *
 * All network access goes through {@link MorpheNet} (https only, except loopback for the tests). No Android classes except through
 * {@link ManifestDecoder}, which reads the compiled manifest of a downloaded file; unit-tested off-device against a local server.
 */
public final class MorpheHelper {
    private MorpheHelper() {}

    /** Tests only: sourceId -> base URL (scheme and host, no trailing slash) that replaces that source's real https origin. */
    public static Map<String, String> baseOverride = new ConcurrentHashMap<String, String>();

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36";
    private static final String APKPURE_UA = "APKPure/3.19.39 (Aegon)";
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final String[] FORMATS = {"apk", "apkm", "apks", "xapk"};
    private static final int MAX_UPTODOWN_PAGES = 6;
    private static final int MAX_UPTODOWN_PAGES_WHEN_LOOKING = 20;
    private static final int MAX_CANDIDATES = 6;
    private static final long MAX_BUNDLE_MANIFEST = 8L * 1024 * 1024;
    /** No page is trusted to list more than this many files or versions: a damaged or hostile page cannot make the work grow without bound. */
    private static final int MAX_ITEMS = 1000;

    // ------------------------------------------------------------------------------------------------------------------------------
    // The ten sources
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final class Src {
        final String id, name, home, note;
        final boolean recommended, latest, history, direct;

        Src(String id, String name, String home, boolean recommended, boolean latest, boolean history, boolean direct, String note) {
            this.id = id;
            this.name = name;
            this.home = home;
            this.recommended = recommended;
            this.latest = latest;
            this.history = history;
            this.direct = direct;
            this.note = note;
        }

        /** Whether this port can read a version list from the source. */
        boolean lists() {
            return recommended || latest || history;
        }
    }

    /** Helper for Morphe's order. */
    private static final Src[] SOURCES = {
        new Src("apkmirror", "APKMirror", "https://www.apkmirror.com", true, true, true, true,
                "Cloudflare may ask for a browser check from some networks: then the release page has to be opened in the browser"),
        new Src("uptodown", "Uptodown", "https://en.uptodown.com/android", true, true, true, false,
                "Turnstile captcha before the file: the version list is read here, the file is downloaded in the browser"),
        new Src("apkpure", "APKPure", "https://apkpure.com", true, true, false, true,
                "Only the newest build comes without a browser (its web pages answer 403 to apps): older versions have to be opened in the browser"),
        new Src("apkcombo", "APKCombo", "https://apkcombo.com", true, true, true, true, ""),
        new Src("aptoide", "Aptoide", "https://en.aptoide.com", true, true, true, true, ""),
        new Src("evozi", "Evozi", "https://apps.evozi.com/apk-downloader/", true, true, true, false,
                "Cloudflare Turnstile captcha before the file (via APKCube): the version list is read here, the file is downloaded in the browser"),
        new Src("mi9", "Mi9", "https://mi9.com", false, false, false, false,
                "Cloudflare challenge: open it in the browser"),
        new Src("apkdownloader", "APK Downloader", "https://apkdownloader.pages.dev/", false, false, false, false,
                "Cloudflare challenge on the Mi9 API it uses: open it in the browser"),
        new Src("aurora", "Aurora", "https://auroraoss.com", false, false, false, false,
                "Needs an anonymous Google Play token with a device profile and Play's protobuf calls (Aurora Store's library): use Aurora Store, or open the Play page"),
        new Src("play", "Play", "https://play.google.com/store", false, false, false, false,
                "The Play Store gives no download to a web page: open the listing in the Play Store"),
    };

    private static Src src(String id) {
        if (id != null) for (Src s : SOURCES) if (s.id.equals(id)) return s;
        return null;
    }

    private static Src needSrc(String id) throws IOException {
        Src s = src(id);
        if (s == null) throw new IOException("unknown source: " + (id == null ? "" : id));
        return s;
    }

    private static String nameOf(String id) {
        Src s = src(id);
        return s == null ? String.valueOf(id) : s.name;
    }

    /**
     * The ten sources of Helper for Morphe, in its order. Each is {id, name, home, manual:true, recommended, latest, history, direct, note}:
     * recommended = the exact requested version can be found, latest = the newest can, history = the version list can be read, direct = a
     * download can be resolved here without a browser, note = what stands in the way when it cannot (empty when nothing does).
     */
    public static JSONArray sources() {
        JSONArray a = new JSONArray();
        for (Src s : SOURCES) {
            JSONObject o = new JSONObject();
            put(o, "id", s.id);
            put(o, "name", s.name);
            put(o, "home", s.home);
            put(o, "manual", Boolean.TRUE);
            put(o, "recommended", s.recommended);
            put(o, "latest", s.latest);
            put(o, "history", s.history);
            put(o, "direct", s.direct);
            put(o, "note", s.note);
            a.put(o);
        }
        return a;
    }

    /** The page to open in a browser for the package (and the version where the site has such a page without a lookup). */
    public static String manualUrl(String sourceId, String pkg, String version) {
        String p = enc(pkg == null ? "" : pkg);
        String v = version == null ? "" : withoutTrailingVersionCode(version).trim();
        String id = sourceId == null ? "" : sourceId;
        switch (id) {
            case "apkmirror": return "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=" + p;
            case "uptodown": return "https://en.uptodown.com/android/search?query=" + p;
            case "apkpure": return "https://apkpure.com/apk-info/" + p;
            case "apkcombo": return v.isEmpty() ? "https://apkcombo.com/search/" + p
                    : "https://apkcombo.com/search/" + p + "/download/phone-" + enc(v) + "-apk";
            case "aptoide": return "https://en.aptoide.com/search?query=" + p;
            case "evozi": return "https://apkcube.com/apk-downloader?url=" + p;
            case "mi9": return "https://mi9.com/package/" + p + "/versions/";
            case "apkdownloader": return "https://apkdownloader.pages.dev/?package=" + p;
            case "aurora":
            case "play": return "https://play.google.com/store/apps/details?id=" + p;
            default: return "";
        }
    }

    /** The Helper's settings with their defaults (SettingsRepository.kt), to be kept by the page. */
    public static JSONObject settingsDefaults() {
        JSONObject o = new JSONObject();
        JSONArray all = new JSONArray();
        for (Src s : SOURCES) all.put(s.id);
        put(o, "enabledSources", all);
        put(o, "defaultSource", "apkmirror");
        put(o, "connection", "both");
        put(o, "saveTo", "cache");
        put(o, "autoClear", Boolean.TRUE);
        JSONObject fast = new JSONObject();
        put(fast, "enabled", Boolean.FALSE);
        put(fast, "policy", "requested");
        put(o, "fast", fast);
        JSONObject vt = new JSONObject();
        put(vt, "enabled", Boolean.FALSE);
        put(vt, "apiKey", "");
        put(vt, "mode", "ask");
        put(o, "vt", vt);
        put(o, "logHttp", Boolean.TRUE);
        return o;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------------------------------------------------------------------

    /** The source has the file, but only behind a browser check or captcha: open {@link #page} in the real browser. */
    public static final class NeedsBrowser extends IOException {
        public final String source, page;

        NeedsBrowser(String source, String page, String message) {
            super(message);
            this.source = source;
            this.page = page == null ? "" : page;
        }
    }

    /** Fast Mode found nothing: {@link #tried} says why, source by source. */
    public static final class FastFailed extends IOException {
        public final JSONArray tried;

        FastFailed(JSONArray tried, String message) {
            super(message);
            this.tried = tried;
        }
    }

    /** A page answered 404 / 410. */
    private static final class Missing extends IOException {
        Missing(String m) {
            super(m);
        }
    }

    /** A bot wall (403, Cloudflare challenge): the public methods turn it into a {@link NeedsBrowser}. */
    private static final class Blocked extends IOException {
        Blocked(String m) {
            super(m);
        }
    }

    private static IOException changed(String id) {
        return new IOException(nameOf(id) + " changed its page format, open it in the browser");
    }

    /** The package is not on the source (as opposed to the source being unreadable). */
    private static final class NotListed extends IOException {
        NotListed(String m) {
            super(m);
        }
    }

    private static IOException notListed(String id, String pkg) {
        return new NotListed(nameOf(id) + " does not list " + pkg);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------------------------------------------

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    private static JSONObject put(JSONObject o, String k, long v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    private static JSONObject put(JSONObject o, String k, boolean v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    /** A string field; JSON null and a missing field are "" (Android's optString would give the text "null"). */
    private static String str(JSONObject o, String k) {
        if (o == null) return "";
        Object v = o.opt(k);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        if (o == null) return 0;
        Object v = o.opt(k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static JSONObject obj(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    private static JSONArray arr(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkPkg(String pkg) throws IOException {
        if (pkg == null || pkg.length() > 200 || !PKG.matcher(pkg).matches()) throw new IOException("not a package name: " + (pkg == null ? "" : pkg.length() > 60 ? pkg.substring(0, 60) : pkg));
    }

    private static String base(String id, String real) {
        String o = baseOverride == null ? null : baseOverride.get(id);
        if (o == null || o.isEmpty()) return real;
        return o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
    }

    /** Resolves href against base; the fragment is dropped; null when it is no address. */
    private static String abs(String baseUrl, String href) {
        if (href == null) return null;
        String h = href.trim();
        if (h.isEmpty() || h.startsWith("#") || h.regionMatches(true, 0, "javascript:", 0, 11) || h.regionMatches(true, 0, "mailto:", 0, 7)) return null;
        try {
            String u = new URL(new URL(baseUrl), h).toString();
            int i = u.indexOf('#');
            return i < 0 ? u : u.substring(0, i);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean loopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Text: entities, whitespace
    // ------------------------------------------------------------------------------------------------------------------------------

    /** Decodes the HTML entities of a text: the named ones that pages use, and numeric ones (&#39; &#x27;). Unknown ones stay as they are. */
    static String unescape(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 10) {
                    String rep = entity(s.substring(i + 1, semi));
                    if (rep != null) {
                        b.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    private static String entity(String e) {
        if (e.isEmpty()) return null;
        if (e.charAt(0) == '#') {
            try {
                int cp = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X') ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                if (cp > 0 && cp <= 0x10FFFF) return new String(Character.toChars(cp));
            } catch (NumberFormatException ignored) {
            }
            return null;
        }
        switch (e) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "ndash": return "\u2013";
            case "mdash": return "\u2014";
            case "hellip": return "\u2026";
            case "middot": return "\u00b7";
            case "bull": return "\u2022";
            case "rsquo": return "\u2019";
            case "lsquo": return "\u2018";
            case "rdquo": return "\u201d";
            case "ldquo": return "\u201c";
            case "copy": return "(c)";
            case "reg": return "(R)";
            case "trade": return "(tm)";
            default: return null;
        }
    }

    private static String collapse(String s) {
        return s.replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // A tiny tolerant HTML tree: tags, attributes, text. Enough for the selectors the sources need (id, class, tag, attribute).
    // ------------------------------------------------------------------------------------------------------------------------------

    static final class El {
        final String tag;
        final Map<String, String> attrs;
        final int openStart, openEnd;
        int closeStart = -1, closeEnd = -1, index;
        El parent;
        final List<El> kids = new ArrayList<El>();

        El(String tag, Map<String, String> attrs, int openStart, int openEnd) {
            this.tag = tag;
            this.attrs = attrs;
            this.openStart = openStart;
            this.openEnd = openEnd;
        }

        String attr(String k) {
            String v = attrs.get(k);
            return v == null ? "" : v;
        }

        boolean has(String k) {
            return attrs.containsKey(k);
        }

        boolean hasClass(String c) {
            String cl = attr("class");
            if (cl.isEmpty()) return false;
            for (String p : cl.split("\\s+")) if (p.equals(c)) return true;
            return false;
        }
    }

    static final class Doc {
        private static final Set<String> VOID = new HashSet<String>(java.util.Arrays.asList("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));
        private static final Set<String> BLOCK = new HashSet<String>(java.util.Arrays.asList("div", "p", "br", "li", "ul", "ol", "tr", "td", "th", "table", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "header", "footer", "dt", "dd", "button"));
        private static final int MAX_ELEMENTS = 400000;

        final String src;
        final List<El> all = new ArrayList<El>();
        final El root = new El("#root", new HashMap<String, String>(), 0, 0);

        Doc(String html) {
            src = html == null ? "" : html;
            parse();
        }

        private static boolean nameChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == ':' || c == '_';
        }

        private int tagEnd(int from) {
            int n = src.length();
            char quote = 0;
            char prev = 0;
            for (int j = from; j < n; j++) {
                char c = src.charAt(j);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '>') {
                    return j;
                } else if ((c == '"' || c == '\'') && prev == '=') {
                    quote = c;
                }
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') prev = c;
            }
            // an unbalanced quote: fall back to the first '>'
            int g = src.indexOf('>', from);
            return g < 0 ? n : g;
        }

        private Map<String, String> attrs(int from, int to) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            int i = from;
            while (i < to && m.size() < 80) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') {
                    i++;
                    continue;
                }
                int s = i;
                while (i < to) {
                    char d = src.charAt(i);
                    if (d == ' ' || d == '\t' || d == '\n' || d == '\r' || d == '=' || d == '/' || d == '>') break;
                    i++;
                }
                if (i == s) {
                    i++;
                    continue;
                }
                String name = src.substring(s, i).toLowerCase(Locale.ROOT);
                int j = i;
                while (j < to && Character.isWhitespace(src.charAt(j))) j++;
                String val = "";
                if (j < to && src.charAt(j) == '=') {
                    j++;
                    while (j < to && Character.isWhitespace(src.charAt(j))) j++;
                    if (j < to && (src.charAt(j) == '"' || src.charAt(j) == '\'')) {
                        char q = src.charAt(j);
                        int e = src.indexOf(q, j + 1);
                        if (e < 0 || e > to) e = to;
                        val = src.substring(j + 1, e);
                        j = Math.min(e + 1, to);
                    } else {
                        int e = j;
                        while (e < to && !Character.isWhitespace(src.charAt(e)) && src.charAt(e) != '>') e++;
                        val = src.substring(j, e);
                        j = e;
                    }
                    i = j;
                }
                if (!m.containsKey(name)) m.put(name, unescape(val));
            }
            return m;
        }

        private void parse() {
            int n = src.length();
            List<El> stack = new ArrayList<El>();
            stack.add(root);
            root.closeStart = n;
            root.closeEnd = n;
            int i = 0;
            while (i < n && all.size() < MAX_ELEMENTS) {
                int lt = src.indexOf('<', i);
                if (lt < 0 || lt + 1 >= n) break;
                if (src.startsWith("<!--", lt)) {
                    int e = src.indexOf("-->", lt + 4);
                    i = e < 0 ? n : e + 3;
                    continue;
                }
                char c = src.charAt(lt + 1);
                if (c == '!' || c == '?') {
                    int e = src.indexOf('>', lt + 2);
                    i = e < 0 ? n : e + 1;
                    continue;
                }
                boolean closing = c == '/';
                int ns = lt + (closing ? 2 : 1);
                int ne = ns;
                while (ne < n && nameChar(src.charAt(ne))) ne++;
                if (ne == ns) {
                    i = lt + 1;
                    continue;
                }
                String name = src.substring(ns, ne).toLowerCase(Locale.ROOT);
                int te = tagEnd(ne);
                int after = te < n ? te + 1 : n;
                if (closing) {
                    int k = stack.size() - 1;
                    while (k > 0 && !stack.get(k).tag.equals(name)) k--;
                    if (k > 0) {
                        for (int j = stack.size() - 1; j >= k; j--) {
                            El x = stack.get(j);
                            x.closeStart = lt;
                            x.closeEnd = j == k ? after : lt;
                        }
                        stack.subList(k, stack.size()).clear();
                    }
                    i = after;
                    continue;
                }
                // optional end tags: a new cell, row or item ends the open one
                if (name.equals("td") || name.equals("th")) {
                    while (stack.size() > 1 && (top(stack).tag.equals("td") || top(stack).tag.equals("th"))) pop(stack, lt);
                } else if (name.equals("tr")) {
                    while (stack.size() > 1 && (top(stack).tag.equals("td") || top(stack).tag.equals("th"))) pop(stack, lt);
                    if (stack.size() > 1 && top(stack).tag.equals("tr")) pop(stack, lt);
                } else if (name.equals("li") || name.equals("option") || name.equals("p")) {
                    if (stack.size() > 1 && top(stack).tag.equals(name)) pop(stack, lt);
                }
                El parent = top(stack);
                El el = new El(name, attrs(ne, te), lt, after);
                el.parent = parent;
                el.index = all.size();
                parent.kids.add(el);
                all.add(el);
                i = after;
                boolean selfClose = te > ne && te < n && src.charAt(te - 1) == '/';
                if (selfClose || VOID.contains(name)) {
                    el.closeStart = after;
                    el.closeEnd = after;
                    continue;
                }
                if (name.equals("script") || name.equals("style")) {
                    int e = indexOfIgnoreCase("</" + name, i);
                    if (e < 0) e = n;
                    el.closeStart = e;
                    int g = src.indexOf('>', e);
                    el.closeEnd = g < 0 ? n : g + 1;
                    i = el.closeEnd;
                    continue;
                }
                stack.add(el);
            }
            for (int j = stack.size() - 1; j > 0; j--) {
                El x = stack.get(j);
                x.closeStart = n;
                x.closeEnd = n;
            }
        }

        private int indexOfIgnoreCase(String needle, int from) {
            int n = src.length(), m = needle.length();
            for (int i = from; i + m <= n; i++) if (src.regionMatches(true, i, needle, 0, m)) return i;
            return -1;
        }

        private static El top(List<El> stack) {
            return stack.get(stack.size() - 1);
        }

        private static void pop(List<El> stack, int at) {
            El x = stack.remove(stack.size() - 1);
            x.closeStart = at;
            x.closeEnd = at;
        }

        /** The visible text of an element, entities decoded, whitespace collapsed. */
        String text(El e) {
            if (e == null) return "";
            StringBuilder sb = new StringBuilder();
            int i = e.openEnd;
            int end = Math.min(e.closeStart < 0 ? src.length() : e.closeStart, src.length());
            while (i < end) {
                int lt = src.indexOf('<', i);
                if (lt < 0 || lt >= end) {
                    sb.append(src, i, end);
                    break;
                }
                sb.append(src, i, lt);
                if (src.startsWith("<!--", lt)) {
                    int c = src.indexOf("-->", lt + 4);
                    i = c < 0 ? end : c + 3;
                    continue;
                }
                int ns = lt + 1;
                boolean closing = ns < end && src.charAt(ns) == '/';
                if (closing) ns++;
                int ne = ns;
                while (ne < end && nameChar(src.charAt(ne))) ne++;
                String name = src.substring(ns, ne).toLowerCase(Locale.ROOT);
                if (ne == ns) {
                    sb.append('<');
                    i = lt + 1;
                    continue;
                }
                int te = tagEnd(ne);
                if (!closing && (name.equals("script") || name.equals("style"))) {
                    int ce = indexOfIgnoreCase("</" + name, te);
                    int g = ce < 0 ? -1 : src.indexOf('>', ce);
                    i = g < 0 ? end : g + 1;
                    continue;
                }
                if (BLOCK.contains(name)) sb.append(' ');
                i = te + 1;
            }
            return collapse(unescape(sb.toString()));
        }

        String body(El script) {
            if (script == null || script.closeStart < script.openEnd) return "";
            return src.substring(script.openEnd, Math.min(script.closeStart, src.length()));
        }

        El byId(String id) {
            for (El e : all) if (id.equals(e.attrs.get("id"))) return e;
            return null;
        }

        /** Elements with this tag ("" = any) and class ("" = any) anywhere below {@code within} (null = the whole document). */
        List<El> select(El within, String tag, String cls) {
            List<El> out = new ArrayList<El>();
            int from = within == null ? 0 : within.index + 1;
            int to = within == null ? Integer.MAX_VALUE : within.closeStart;
            for (int i = from; i < all.size(); i++) {
                El e = all.get(i);
                if (e.openStart >= to) break;
                if (!tag.isEmpty() && !e.tag.equals(tag)) continue;
                if (!cls.isEmpty() && !e.hasClass(cls)) continue;
                out.add(e);
            }
            return out;
        }

        El first(El within, String tag, String cls) {
            List<El> l = select(within, tag, cls);
            return l.isEmpty() ? null : l.get(0);
        }

        /** The first element above {@code e} (or e itself) with this tag and class. */
        El up(El e, String tag, String cls) {
            for (El p = e; p != null && p != root; p = p.parent) {
                if ((tag.isEmpty() || p.tag.equals(tag)) && (cls.isEmpty() || p.hasClass(cls))) return p;
            }
            return null;
        }

        /** The value cell (last td) of the row whose th says {@code label}. */
        String infoValue(String label) {
            for (El tr : select(null, "tr", "")) {
                List<El> ths = select(tr, "th", "");
                if (ths.isEmpty() || !text(ths.get(0)).equalsIgnoreCase(label)) continue;
                List<El> tds = select(tr, "td", "");
                for (int i = tds.size() - 1; i >= 0; i--) {
                    String t = text(tds.get(i));
                    if (!t.isEmpty()) return t;
                }
            }
            return "";
        }

        String title() {
            El t = first(null, "title", "");
            return t == null ? "" : text(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Versions: the matching rules of Helper for Morphe
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Pattern TRAILING_CODE = Pattern.compile("\\s*\\(\\s*(\\d+)\\s*\\)\\s*$");
    private static final Pattern VARIANT_SUFFIX = Pattern.compile("(?i)(?<=\\d)[\\s._-]*(?:secondary)\\b");
    private static final Pattern NOISE_WORDS = Pattern.compile("\\b(version|ver|v|release|stable|apk|xapk|apkm|apks|bundle)\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    static String withoutTrailingVersionCode(String v) {
        return TRAILING_CODE.matcher(v).replaceFirst("").trim();
    }

    static long trailingVersionCode(String v) {
        Matcher m = TRAILING_CODE.matcher(v);
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String normalizedVersionName(String v) {
        String s = withoutTrailingVersionCode(v).toLowerCase(Locale.ROOT);
        s = NOISE_WORDS.matcher(s).replaceAll(" ");
        s = s.replaceAll("[^A-Za-z0-9]+", ".");
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '.') a++;
        while (b > a && s.charAt(b - 1) == '.') b--;
        return s.substring(a, b);
    }

    private static List<Long> numberParts(String v) {
        List<Long> out = new ArrayList<Long>();
        Matcher m = DIGITS.matcher(v);
        while (m.find()) {
            if (m.group().length() > 18) continue;
            out.add(Long.parseLong(m.group()));
        }
        return out;
    }

    /** "21.36.45-SECONDARY": an alternate package of the same release, which Morphe cannot patch. */
    static boolean hasVariantBuildMarker(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] tokens = s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].equals("secondary")) {
                String prev = tokens[i - 1];
                for (int k = 0; k < prev.length(); k++) if (Character.isDigit(prev.charAt(k))) return true;
            }
        }
        return false;
    }

    static boolean versionNameEquals(String a, String b, boolean ignoreVariantMarker) {
        if (a == null || b == null) return false;
        String l = ignoreVariantMarker ? VARIANT_SUFFIX.matcher(a).replaceAll("") : a;
        String r = ignoreVariantMarker ? VARIANT_SUFFIX.matcher(b).replaceAll("") : b;
        if (hasVariantBuildMarker(l) != hasVariantBuildMarker(r)) return false;
        String nl = normalizedVersionName(l), nr = normalizedVersionName(r);
        if (nl.isEmpty() || nr.isEmpty()) return false;
        if (nl.equals(nr)) return true;
        List<Long> pl = numberParts(nl), pr = numberParts(nr);
        return !pl.isEmpty() && pl.equals(pr);
    }

    static int compareVersionNames(String l, String r) {
        if (l == null ? r == null : l.equals(r)) return 0;
        if (l == null) return -1;
        if (r == null) return 1;
        List<Long> pl = numberParts(l), pr = numberParts(r);
        int n = Math.max(pl.size(), pr.size());
        for (int i = 0; i < n; i++) {
            long a = i < pl.size() ? pl.get(i) : 0, b = i < pr.size() ? pr.get(i) : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        return l.compareToIgnoreCase(r);
    }

    private static final Pattern VERSION_IN_TEXT = Pattern.compile("\\b(v?\\d+(?:[._-]\\d+)+(?:[-.][A-Za-z0-9]+)?)\\b", Pattern.CASE_INSENSITIVE);

    private static String versionFromText(String text) {
        Matcher m = VERSION_IN_TEXT.matcher(text == null ? "" : text);
        if (!m.find()) return "";
        String v = m.group(1);
        return v.replace('_', '.');
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Formats and ABIs
    // ------------------------------------------------------------------------------------------------------------------------------

    private static int formatRank(String f) {
        for (int i = 0; i < FORMATS.length; i++) if (FORMATS[i].equals(f)) return i;
        return FORMATS.length;
    }

    private static String normFormat(String f) {
        String s = f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
        if (s.contains("xapk")) return "xapk";
        if (s.contains("apks")) return "apks";
        if (s.contains("apkm") || s.contains("bundle")) return "apkm";
        if (s.contains("apk")) return "apk";
        return "";
    }

    private static String normAbi(String a) {
        String s = a == null ? "" : a.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (s.isEmpty()) return "";
        if (s.equals("x86-64")) return "x86_64";
        if (s.equals("arm64") || s.equals("arm64v8a")) return "arm64-v8a";
        if (s.equals("armeabi-v7a") || s.equals("armv7") || s.equals("armv7a")) return "armeabi-v7a";
        if (s.contains("universal") || s.equals("all") || s.equals("noarch") || s.contains("all architectures")) return "universal";
        return s;
    }

    private static List<String> parseAbis(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String p : text.split("[,;/+&]|\\s+and\\s+")) {
            String n = normAbi(p);
            if (n.isEmpty() || out.contains(n)) continue;
            if (n.startsWith("arm") || n.startsWith("x86") || n.startsWith("mips") || n.equals("universal") || n.startsWith("riscv")) out.add(n);
        }
        return out;
    }

    private static String abiLabel(List<String> abis) {
        if (abis.isEmpty()) return "";
        if (abis.contains("universal")) return "universal";
        StringBuilder b = new StringBuilder();
        for (String a : abis) {
            if (b.length() > 0) b.append(',');
            b.append(a);
        }
        return b.toString();
    }

    /** 0 = cannot run on that ABI, 1 = no information or an older ABI that still runs, 2 = universal, 3 = a fat build that has it, 4 = built for it. */
    private static int abiScore(List<String> abis, String wanted) {
        String w = normAbi(wanted);
        if (abis.isEmpty()) return 1;
        if (w.isEmpty()) return abis.contains("universal") ? 3 : abis.size() == 1 ? 2 : 1;
        if (abis.contains(w)) return abis.size() == 1 ? 4 : 3;
        if (abis.contains("universal")) return 2;
        if (w.equals("arm64-v8a") && abis.contains("armeabi-v7a")) return 1;
        if (w.equals("x86_64") && abis.contains("x86")) return 1;
        return 0;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // The model every source reads into
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final class Item {
        String version = "", format = "", page = "", url = "", sha256 = "", md5 = "", ref = "", referer = "", store = "";
        long code, size;
        List<String> abis = new ArrayList<String>();

        Item copy() {
            Item c = new Item();
            c.version = version;
            c.format = format;
            c.page = page;
            c.url = url;
            c.sha256 = sha256;
            c.md5 = md5;
            c.ref = ref;
            c.referer = referer;
            c.store = store;
            c.code = code;
            c.size = size;
            c.abis = new ArrayList<String>(abis);
            return c;
        }
    }

    private static final class Listing {
        String name = "";
        String appPage = "";
        final List<Item> items = new ArrayList<Item>();
    }

    private static void sortNewestFirst(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int c = compareVersionNames(b.version, a.version);
                if (c != 0) return c;
                if (a.code != b.code) return a.code < b.code ? 1 : -1;
                return formatRank(a.format) - formatRank(b.format);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Map<String, Long> NEXT_AT = new HashMap<String, Long>();

    /** Pages are asked for no faster than a site tolerates (APKMirror declares Crawl-delay 3); APIs and loopback are not slowed. */
    private static void pace(String url) throws IOException {
        String host = hostOf(url);
        if (host.isEmpty() || loopback(host)) return;
        long gap;
        if (host.equals("www.apkmirror.com")) gap = 2500;
        else if (host.endsWith("uptodown.com") || host.equals("apkcombo.com") || host.equals("apkcube.com")) gap = 800;
        else return;
        long wait;
        synchronized (NEXT_AT) {
            long now = System.currentTimeMillis();
            Long next = NEXT_AT.get(host);
            long start = next == null || next < now ? now : next;
            wait = start - now;
            NEXT_AT.put(host, start + gap);
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    private static Map<String, String> webHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", BROWSER_UA);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "en-US,en;q=0.9");
        if (referer != null && !referer.isEmpty()) h.put("Referer", referer);
        return h;
    }

    private static Map<String, String> jsonHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", BROWSER_UA);
        h.put("Accept", "application/json, text/plain, */*");
        if (referer != null && !referer.isEmpty()) h.put("Referer", referer);
        return h;
    }

    private static boolean challengePage(String text) {
        if (text == null) return false;
        int n = Math.min(text.length(), 30000);
        String head = text.substring(0, n);
        return head.contains("<title>Just a moment") || head.contains("Enable JavaScript and cookies to continue");
    }

    private static IOException netError(String id, IOException e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException) return new IOException("Could not connect to " + nameOf(id) + ". Check your connection.");
        if (e instanceof SocketTimeoutException) return new IOException(nameOf(id) + " took too long to respond. Try again.");
        String m = e.getMessage();
        return new IOException(nameOf(id) + ": " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m));
    }

    /** One request to a source. 404 / 410 are {@link Missing}, a bot wall is {@link Blocked}, other failures say what happened. */
    private static MorpheNet.Response fetch(String id, String method, String url, Map<String, String> headers, byte[] body, boolean html) throws IOException {
        pace(url);
        MorpheNet.Response r;
        try {
            r = MorpheNet.request(method, url, headers, body);
        } catch (IOException e) {
            throw netError(id, e);
        }
        int s = r.status;
        if (s >= 200 && s < 300) {
            if (html && challengePage(r.text)) throw new Blocked(nameOf(id) + " showed a browser verification page, so direct access is blocked.");
            return r;
        }
        if (s == 404 || s == 410) throw new Missing(nameOf(id) + " has no such page (HTTP " + s + ")");
        if (s == 403 || (s == 503 && challengePage(r.text))) {
            throw new Blocked(nameOf(id) + " blocked automated access (HTTP " + s + "), likely bot protection.");
        }
        if (s == 429) throw new IOException(nameOf(id) + " rate-limited the helper (HTTP 429). Try again later.");
        throw new IOException(nameOf(id) + " answered HTTP " + s + ".");
    }

    private static MorpheNet.Response getPage(String id, String url, String referer) throws IOException {
        return fetch(id, "GET", url, webHeaders(referer), null, true);
    }

    private static JSONObject getJson(String id, String url, String referer) throws IOException {
        MorpheNet.Response r = fetch(id, "GET", url, jsonHeaders(referer), null, false);
        try {
            return new JSONObject(r.text == null ? "" : r.text.trim());
        } catch (JSONException e) {
            throw changed(id);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Aptoide: public JSON API
    // ------------------------------------------------------------------------------------------------------------------------------

    private static String aptoideApi() {
        return base("aptoide", "https://ws75.aptoide.com") + "/api/7/";
    }

    /** The app of a getApp answer, or null when Aptoide has none (404, FAIL status, another package). */
    private static JSONObject aptoideApp(String url, String pkg) throws IOException {
        JSONObject root;
        try {
            root = getJson("aptoide", url, null);
        } catch (Missing e) {
            return null;
        }
        JSONObject info = obj(root, "info");
        if (info != null && "FAIL".equalsIgnoreCase(str(info, "status"))) return null;
        JSONObject nodes = obj(root, "nodes");
        JSONObject meta = nodes == null ? null : obj(nodes, "meta");
        JSONObject data = meta == null ? null : obj(meta, "data");
        if (data == null) throw changed("aptoide");
        return pkg.equals(str(data, "package")) ? data : null;
    }

    private static Item aptoideItem(JSONObject a, String pkg) {
        JSONObject f = obj(a, "file");
        if (f == null) return null;
        String vername = str(f, "vername").trim();
        if (vername.isEmpty()) return null;
        Item it = new Item();
        it.version = vername;
        it.code = lng(f, "vercode");
        // Aptoide's API clamps a build number above 2^31 - 1 to exactly that: it says nothing then
        if (it.code == Integer.MAX_VALUE) it.code = 0;
        it.size = lng(f, "filesize");
        it.md5 = str(f, "md5sum");
        it.format = "apk";
        it.ref = String.valueOf(lng(a, "id"));
        String path = str(f, "path");
        if (path.isEmpty()) path = str(f, "path_alt");
        it.url = path.startsWith("http") ? path : "";
        JSONObject hw = obj(f, "hardware");
        JSONArray cpus = hw == null ? null : arr(hw, "cpus");
        if (cpus != null) for (int i = 0; i < cpus.length(); i++) {
            String n = normAbi(cpus.optString(i, ""));
            if (!n.isEmpty() && !it.abis.contains(n)) it.abis.add(n);
        }
        JSONObject store = obj(a, "store");
        it.store = store == null ? "" : str(store, "name");
        String uname = str(a, "uname");
        it.page = uname.matches("[a-z0-9-]{1,80}") ? "https://" + uname + ".en.aptoide.com/versions" : manualUrl("aptoide", pkg, null);
        return it;
    }

    private static Listing listAptoide(String pkg) throws IOException {
        String api = aptoideApi();
        JSONObject app = aptoideApp(api + "getApp?package_name=" + enc(pkg), pkg);
        if (app == null) {
            // Not every app is reachable by package; the search finds some of the others (Helper does the same)
            JSONObject body = new JSONObject();
            put(body, "query", pkg);
            put(body, "limit", "25");
            put(body, "not_apk_tags", "alpha,beta");
            put(body, "store_ids", new JSONArray().put(15L).put(711454L));
            Map<String, String> h = jsonHeaders(null);
            h.put("Content-Type", "application/json");
            JSONObject res;
            try {
                MorpheNet.Response r = fetch("aptoide", "POST", api + "listSearchApps", h, body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
                res = new JSONObject(r.text == null ? "" : r.text.trim());
            } catch (Missing e) {
                throw notListed("aptoide", pkg);
            } catch (JSONException e) {
                throw changed("aptoide");
            }
            JSONObject dl = obj(res, "datalist");
            JSONArray list = dl == null ? null : arr(dl, "list");
            if (list != null) for (int i = 0; i < list.length() && app == null; i++) {
                JSONObject c = list.optJSONObject(i);
                if (c != null && pkg.equals(str(c, "package"))) app = c;
            }
            if (app == null) throw notListed("aptoide", pkg);
        }
        Listing l = new Listing();
        l.name = str(app, "name");
        Set<String> seen = new HashSet<String>();
        Item latest = aptoideItem(app, pkg);
        if (latest != null) {
            l.items.add(latest);
            seen.add(latest.ref);
        }
        List<JSONObject> more = new ArrayList<JSONObject>();
        try {
            JSONObject res = getJson("aptoide", api + "listAppVersions?package_name=" + enc(pkg) + "&limit=100", null);
            JSONArray list = arr(res, "list");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c != null) more.add(c);
            }
        } catch (Missing ignored) {
        }
        if (more.isEmpty() && lng(app, "id") > 0) {
            try {
                JSONObject res = getJson("aptoide", api + "listAppVersions?app_id=" + lng(app, "id") + "&limit=100", null);
                JSONArray list = arr(res, "list");
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject c = list.optJSONObject(i);
                    if (c != null) more.add(c);
                }
            } catch (Missing ignored) {
            }
        }
        for (JSONObject c : more) {
            if (l.items.size() >= MAX_ITEMS) break;
            if (!pkg.equals(str(c, "package"))) continue;
            Item it = aptoideItem(c, pkg);
            if (it == null || !seen.add(it.ref)) continue;
            l.items.add(it);
        }
        if (l.items.isEmpty()) throw changed("aptoide");
        return l;
    }

    private static Item finishAptoide(String pkg, Item it) throws IOException {
        if (!it.url.isEmpty()) return it;
        long id;
        try {
            id = Long.parseLong(it.ref);
        } catch (NumberFormatException e) {
            throw changed("aptoide");
        }
        JSONObject app = aptoideApp(aptoideApi() + "getApp?app_id=" + id, pkg);
        if (app == null) throw notListed("aptoide", pkg);
        Item full = aptoideItem(app, pkg);
        if (full == null || full.url.isEmpty()) throw new IOException("Aptoide has no download link for " + pkg + " " + it.version + ", open it in the browser");
        Item out = it.copy();
        out.url = full.url;
        if (out.md5.isEmpty()) out.md5 = full.md5;
        if (out.size <= 0) out.size = full.size;
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKPure: the update API of its own app (the web pages answer 403 to anything but a browser, and not even to that from a datacenter)
    // ------------------------------------------------------------------------------------------------------------------------------

    private static String randomHex16() {
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(Character.forDigit(r.nextInt(16), 16));
        return b.toString();
    }

    /**
     * The API answers with the newest build that suits the ABIs of the device header, so the ABI decides which build (and version) comes back:
     * the phone's own ABI first, then (for a 64-bit ARM phone) with the 32-bit one allowed, which may offer a newer build. Each answer is one
     * entry; versions() takes both, resolve() the second only when the first has no match.
     */
    private static Listing listApkPure(String pkg, String abi, boolean exhaustive, String wantedName) throws IOException {
        String w = normAbi(abi);
        List<List<String>> sets = new ArrayList<List<String>>();
        if (w.isEmpty() || w.equals("universal")) {
            sets.add(java.util.Collections.singletonList("arm64-v8a"));
            sets.add(java.util.Arrays.asList("arm64-v8a", "armeabi-v7a"));
        } else {
            sets.add(java.util.Collections.singletonList(w));
            if (w.equals("arm64-v8a")) sets.add(java.util.Arrays.asList("arm64-v8a", "armeabi-v7a"));
        }
        Listing out = new Listing();
        IOException firstMissing = null;
        for (List<String> set : sets) {
            Listing part = null;
            try {
                part = queryApkPure(pkg, set);
            } catch (NotListed e) {
                if (firstMissing == null) firstMissing = e;
            }
            if (part != null) {
                if (out.name.isEmpty()) out.name = part.name;
                for (Item it : part.items) {
                    boolean dup = false;
                    for (Item o : out.items) if (o.version.equals(it.version) && o.code == it.code && o.abis.equals(it.abis) && o.format.equals(it.format)) dup = true;
                    if (!dup) out.items.add(it);
                }
            }
            if (!exhaustive && !out.items.isEmpty()) {
                if (wantedName == null || wantedName.isEmpty()) break;
                boolean have = false;
                for (Item o : out.items) if (versionNameEquals(o.version, wantedName, requestsVariant(wantedName))) have = true;
                if (have) break;
            }
        }
        if (out.items.isEmpty()) throw firstMissing != null ? firstMissing : notListed("apkpure", pkg);
        return out;
    }

    private static Listing queryApkPure(String pkg, List<String> deviceAbis) throws IOException {
        String api = base("apkpure", "https://tapi.pureapk.com") + "/v3/get_app_update";
        String androidId = randomHex16();
        JSONObject dev = new JSONObject();
        JSONArray abis = new JSONArray();
        for (String a : deviceAbis) abis.put(a);
        JSONObject di = new JSONObject();
        put(di, "abis", abis);
        put(di, "android_id", androidId);
        put(di, "os_ver", "34");
        put(di, "os_ver_name", "14");
        put(di, "platform", 1L);
        put(dev, "device_info", di);
        JSONObject req = new JSONObject();
        JSONArray infos = new JSONArray();
        JSONObject one = new JSONObject();
        put(one, "package_name", pkg);
        put(one, "version_code", 0L);
        put(one, "is_system", Boolean.FALSE);
        put(one, "version_id", "");
        put(one, "cached_size", -1L);
        infos.put(one);
        put(req, "app_info_for_update", infos);
        put(req, "android_id", androidId);
        put(req, "application_id", "com.apkpure.aegon");
        put(req, "cached_size", -1L);
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", APKPURE_UA);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("ual-access-businessid", "projecta");
        h.put("ual-access-projecta", dev.toString());
        JSONObject res;
        try {
            MorpheNet.Response r = fetch("apkpure", "POST", api, h, req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            res = new JSONObject(r.text == null ? "" : r.text.trim());
        } catch (Missing e) {
            throw notListed("apkpure", pkg);
        } catch (JSONException e) {
            throw changed("apkpure");
        }
        JSONArray list = arr(res, "app_update_response");
        if (list == null) {
            if (res.has("retcode") && lng(res, "retcode") != 0) throw notListed("apkpure", pkg);
            throw changed("apkpure");
        }
        Listing l = new Listing();
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null || !pkg.equals(str(a, "package_name"))) continue;
            JSONObject asset = obj(a, "asset");
            String version = str(a, "version_name").trim();
            if (asset == null || version.isEmpty()) continue;
            Item it = new Item();
            it.version = version;
            it.code = lng(a, "version_code");
            it.size = lng(asset, "size");
            String raw = str(asset, "url");
            // the API still hands out http:// addresses; the CDN answers on https (Helper upgrades them the same way)
            String url = raw.startsWith("http://") && !loopback(hostOf(raw)) ? "https://" + raw.substring(7) : raw;
            it.url = url.startsWith("http") ? url : "";
            String type = normFormat(str(asset, "type"));
            it.format = type.isEmpty() ? (url.toLowerCase(Locale.ROOT).contains("/xapk") ? "xapk" : "apk") : type;
            String sha = str(asset, "file_sha256").trim().toLowerCase(Locale.ROOT);
            it.sha256 = sha.matches("[0-9a-f]{64}") ? sha : "";
            JSONArray nc = arr(a, "native_code");
            if (nc != null) for (int k = 0; k < nc.length(); k++) {
                String n = normAbi(nc.optString(k, ""));
                if (!n.isEmpty() && !it.abis.contains(n)) it.abis.add(n);
            }
            it.page = manualUrl("apkpure", pkg, null);
            if (l.name.isEmpty()) l.name = str(a, "label").isEmpty() ? str(a, "title") : str(a, "label");
            l.items.add(it);
        }
        if (l.items.isEmpty()) throw notListed("apkpure", pkg);
        return l;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKCombo: the download page (variants), the old-versions page and the versioned pages
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Pattern COMBO_PHONE_URL = Pattern.compile("phone-(.+?)-(?:apk|xapk|apks|apkm)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LD_VERSION = Pattern.compile("\"softwareVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DESC_VERSION = Pattern.compile("Version:\\s*([^-]+?)\\s*-");

    private static long parseSize(String text) {
        Matcher m = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(KB|MB|GB)", Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        if (!m.find()) return 0;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
        String u = m.group(2).toUpperCase(Locale.ROOT);
        double mul = u.equals("KB") ? 1024.0 : u.equals("MB") ? 1024.0 * 1024 : 1024.0 * 1024 * 1024;
        return (long) (v * mul);
    }

    private static long parseCode(String text) {
        Matcher m = Pattern.compile("\\(\\s*(\\d{1,12})\\s*\\)").matcher(text == null ? "" : text);
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The download variants of an APKCombo page: file link, version name and build, type, size, and the ABIs of the group they sit in. */
    private static List<Item> comboVariants(Doc d, String pageUrl, String versionHint) {
        List<Item> out = new ArrayList<Item>();
        for (El a : d.select(null, "a", "variant")) {
            if (out.size() >= MAX_ITEMS) break;
            String href = abs(pageUrl, a.attr("href"));
            if (href == null) continue;
            Item it = new Item();
            El vername = d.first(a, "span", "vername");
            String vtext = vername == null ? "" : d.text(vername);
            it.version = versionFromText(vtext);
            if (it.version.isEmpty()) it.version = versionHint == null ? "" : versionHint;
            El vercode = d.first(a, "span", "vercode");
            it.code = parseCode(vercode == null ? "" : d.text(vercode));
            El vtype = d.first(a, "span", "vtype");
            it.format = normFormat(vtype == null ? "" : d.text(vtype));
            if (it.format.isEmpty()) it.format = fileKindFromUrl(href);
            List<El> specs = d.select(a, "span", "spec");
            for (El sp : specs) {
                long sz = parseSize(d.text(sp));
                if (sz > 0) {
                    it.size = sz;
                    break;
                }
            }
            String label = a.attr("data-arch");
            if (label.isEmpty()) label = a.attr("data-abi");
            if (label.isEmpty()) label = a.attr("data-cpu");
            if (label.isEmpty()) label = comboGroupAbis(d, a);
            it.abis = parseAbis(label);
            it.ref = href;
            it.page = pageUrl;
            out.add(it);
        }
        return out;
    }

    /** The ABI line of the group a variant sits in: "&lt;span class=blur&gt;&lt;code&gt;arm64-v8a, ...&lt;/code&gt;&lt;/span&gt;&lt;ul class=file-list&gt;". */
    private static String comboGroupAbis(Doc d, El variant) {
        El ul = d.up(variant, "ul", "file-list");
        if (ul == null || ul.parent == null) return "";
        El prev = null;
        for (El k : ul.parent.kids) {
            if (k == ul) break;
            prev = k;
        }
        if (prev == null) return "";
        El code = prev.tag.equals("code") ? prev : d.first(prev, "code", "");
        return code == null ? "" : d.text(code);
    }

    private static String fileKindFromUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?:filename[^.&]*|\\.)(xapk|apks|apkm|apk)(?:[\"'&%?/]|$)").matcher(u);
        String last = "";
        while (m.find()) last = m.group(1);
        return last.isEmpty() ? "apk" : last;
    }

    private static String comboVersionOfPage(Doc d, String pageUrl) {
        Matcher m = COMBO_PHONE_URL.matcher(pageUrl);
        if (m.find() && !m.group(1).trim().isEmpty()) return m.group(1).trim();
        m = LD_VERSION.matcher(d.src);
        if (m.find() && !m.group(1).trim().isEmpty()) return m.group(1).trim();
        for (El a : d.select(null, "a", "variant")) {
            El vn = d.first(a, "span", "vername");
            String v = versionFromText(vn == null ? "" : d.text(vn));
            if (!v.isEmpty()) return v;
        }
        for (El meta : d.select(null, "meta", "")) {
            if (!"description".equalsIgnoreCase(meta.attr("name"))) continue;
            Matcher dm = DESC_VERSION.matcher(meta.attr("content"));
            if (dm.find() && !dm.group(1).trim().isEmpty()) return dm.group(1).trim();
        }
        El h1 = d.first(null, "h1", "");
        return versionFromText(h1 == null ? "" : d.text(h1));
    }

    private static boolean comboPageIsForPackage(Doc d, String pageUrl, String pkg) {
        if (pageUrl.contains("/" + pkg + "/")) return true;
        for (El l : d.select(null, "link", "")) {
            if (!"canonical".equalsIgnoreCase(l.attr("rel"))) continue;
            String h = l.attr("href");
            if (h.endsWith("/" + pkg) || h.contains("/" + pkg + "/")) return true;
        }
        return d.src.contains(pkg);
    }

    private static final class ComboPage {
        String url;
        Doc doc;
    }

    private static ComboPage comboFetch(String url, String pkg, String referer) throws IOException {
        MorpheNet.Response r = getPage("apkcombo", url, referer);
        ComboPage p = new ComboPage();
        p.url = r.url == null ? url : r.url;
        p.doc = new Doc(r.text);
        if (!comboPageIsForPackage(p.doc, p.url, pkg)) throw notListed("apkcombo", pkg);
        return p;
    }

    private static boolean comboCaptchaGated(Doc d) {
        return d.src.toLowerCase(Locale.ROOT).contains("aptcha.execute");
    }

    private static Listing listApkCombo(String pkg, String stopAt) throws IOException {
        String b = base("apkcombo", "https://apkcombo.com");
        ComboPage latestPage;
        try {
            latestPage = comboFetch(b + "/search/" + enc(pkg) + "/download/apk", pkg, null);
        } catch (Missing e) {
            throw notListed("apkcombo", pkg);
        }
        Listing l = new Listing();
        l.appPage = latestPage.url;
        String latestVersion = comboVersionOfPage(latestPage.doc, latestPage.url);
        List<Item> vars = comboVariants(latestPage.doc, latestPage.url, latestVersion);
        Set<String> seenRef = new HashSet<String>();
        for (Item v : vars) {
            // the live page repeats its file list in a second tab
            if (!seenRef.add(v.ref)) continue;
            v.page = latestPage.url;
            l.items.add(v);
        }
        El nm = latestPage.doc.first(null, "span", "vername");
        l.name = nm == null ? "" : latestPage.doc.text(nm).replaceAll("\\s+v?\\d[\\d._-]*.*$", "").trim();
        if (l.name.isEmpty()) {
            El h1 = latestPage.doc.first(null, "h1", "");
            l.name = h1 == null ? "" : latestPage.doc.text(h1).replaceAll("(?i)\\s+APK\\b.*$", "").trim();
        }
        Set<String> haveVersion = new HashSet<String>();
        for (Item v : l.items) haveVersion.add(normalizedVersionName(v.version));
        // the older versions the pages list (the latest page shows a few, the old-versions page the rest)
        List<Doc> owners = new ArrayList<Doc>();
        List<El> verItems = new ArrayList<El>();
        for (El a : latestPage.doc.select(null, "a", "ver-item")) {
            owners.add(latestPage.doc);
            verItems.add(a);
        }
        String oldUrl = comboOldVersionsUrl(latestPage.url, pkg, b);
        try {
            MorpheNet.Response r = getPage("apkcombo", oldUrl, latestPage.url);
            Doc oldDoc = new Doc(r.text);
            for (El a : oldDoc.select(null, "a", "ver-item")) {
                owners.add(oldDoc);
                verItems.add(a);
            }
        } catch (Missing ignored) {
        }
        for (int vi = 0; vi < verItems.size() && l.items.size() < MAX_ITEMS; vi++) {
            El a = verItems.get(vi);
            Doc owner = owners.get(vi);
            String href = abs(latestPage.url, a.attr("href"));
            if (href == null) continue;
            El vn = owner.first(a, "span", "vername");
            String text = vn == null ? owner.text(a) : owner.text(vn);
            String version = versionFromText(text);
            if (version.isEmpty()) version = versionFromText(href);
            if (version.isEmpty() || !haveVersion.add(normalizedVersionName(version))) continue;
            Item it = new Item();
            it.version = version;
            El vt = owner.first(a, "span", "vtype");
            it.format = normFormat(vt == null ? "" : owner.text(vt));
            it.page = href;
            l.items.add(it);
        }
        if (stopAt != null && !stopAt.isEmpty()) {
            boolean have = false;
            for (Item it : l.items) if (versionNameEquals(it.version, stopAt, false)) have = true;
            if (!have) {
                // an old version that no list shows may still have its own page (Helper tries the same address shapes)
                String[] suffixes = {"apk", "xapk", "apks"};
                for (String suf : suffixes) {
                    String guess = b + "/search/" + enc(pkg) + "/download/phone-" + enc(stopAt) + "-" + suf;
                    try {
                        MorpheNet.Response r = getPage("apkcombo", guess, latestPage.url);
                        String fin = r.url == null ? guess : r.url;
                        if (!fin.contains("phone-")) continue;
                        Doc gd = new Doc(r.text);
                        List<Item> gv = comboVariants(gd, fin, stopAt);
                        for (Item v : gv) {
                            v.page = fin;
                            l.items.add(v);
                        }
                        if (!gv.isEmpty()) break;
                    } catch (Missing ignored) {
                    }
                }
            }
        }
        if (l.items.isEmpty()) {
            if (comboCaptchaGated(latestPage.doc)) {
                Item it = new Item();
                it.version = latestVersion;
                it.page = latestPage.url;
                l.items.add(it);
            } else {
                throw changed("apkcombo");
            }
        }
        return l;
    }

    private static String comboOldVersionsUrl(String pageUrl, String pkg, String b) {
        try {
            URL u = new URL(pageUrl);
            String path = u.getPath();
            int i = path.indexOf("/" + pkg);
            if (i >= 0) return u.getProtocol() + "://" + u.getAuthority() + path.substring(0, i + 1 + pkg.length()) + "/old-versions/";
        } catch (Exception ignored) {
        }
        return b + "/search/" + enc(pkg) + "/old-versions/";
    }

    private static Item finishApkCombo(String pkg, Item it, String abi) throws IOException {
        Item chosen = it;
        if (it.ref.isEmpty()) {
            ComboPage p;
            try {
                p = comboFetch(it.page, pkg, null);
            } catch (Missing e) {
                throw new IOException("APKCombo has no page for version " + it.version + " any more");
            }
            if (!p.url.contains("phone-")) throw new IOException("APKCombo has no page for version " + it.version + " any more");
            List<Item> vars = comboVariants(p.doc, p.url, it.version);
            if (vars.isEmpty()) {
                if (comboCaptchaGated(p.doc)) throw new NeedsBrowser("apkcombo", it.page, "APKCombo is showing a captcha for this download. Open the page and download it there.");
                throw changed("apkcombo");
            }
            for (Item v : vars) v.page = p.url;
            chosen = choose(vars, abi, "apkcombo");
        }
        String checkIn = "";
        try {
            MorpheNet.Response r = getPage("apkcombo", base("apkcombo", "https://apkcombo.com") + "/checkin", chosen.page);
            String t = r.text == null ? "" : r.text.trim();
            if (t.length() <= 300 && t.indexOf('<') < 0 && t.indexOf('=') > 0) checkIn = t;
        } catch (Missing ignored) {
        } catch (Blocked ignored) {
        }
        Item out = chosen.copy();
        out.url = checkIn.isEmpty() ? chosen.ref : chosen.ref + (chosen.ref.contains("?") ? "&" : "?") + checkIn;
        // No Referer: APKCombo's file link redirects to a CDN that bounces requests carrying an apkcombo.com Referer to an HTML page (Helper)
        out.referer = "";
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Uptodown: search page, versions JSON, version page, variants (the file itself sits behind a Turnstile captcha on the live site)
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Pattern UPTODOWN_DETAIL = Pattern.compile("https://[a-z0-9-]+\\.en\\.uptodown\\.com/android/?");

    private static boolean uptodownDetail(String url) {
        String ov = baseOverride == null ? null : baseOverride.get("uptodown");
        if (ov != null && !ov.isEmpty()) return url.startsWith(base("uptodown", "") + "/") && url.replaceAll("/+$", "").endsWith("/android");
        return UPTODOWN_DETAIL.matcher(url).matches();
    }

    private static String uptodownPackage(Doc d) {
        El g = d.byId("gplay-url");
        String u = g == null ? "" : g.attr("data-url");
        if (!u.isEmpty()) {
            Matcher m = Pattern.compile("[?&]id=([^&#]+)").matcher(u);
            if (m.find()) return m.group(1);
        }
        return d.infoValue("Package Name");
    }

    private static String uptodownDataCode(Doc d) {
        El n = d.byId("detail-app-name");
        return n == null ? "" : n.attr("data-code");
    }

    private static final class UptodownApp {
        String detail, code, name;
        Doc download;
    }

    private static UptodownApp uptodownFind(String pkg) throws IOException {
        String b = base("uptodown", "https://en.uptodown.com");
        Doc search;
        String searchUrl = b + "/android/search?query=" + enc(pkg);
        try {
            search = new Doc(getPage("uptodown", searchUrl, null).text);
        } catch (Missing e) {
            throw notListed("uptodown", pkg);
        }
        List<String> cands = new ArrayList<String>();
        for (El a : search.select(null, "a", "")) {
            String h = abs(searchUrl, a.attr("href"));
            if (h != null && uptodownDetail(h)) {
                String norm = h.replaceAll("/+$", "");
                if (!cands.contains(norm)) cands.add(norm);
            }
            if (cands.size() >= 20) break;
        }
        int tried = 0;
        for (String c : cands) {
            if (tried++ >= MAX_CANDIDATES) break;
            Doc d;
            try {
                d = new Doc(getPage("uptodown", c + "/download", searchUrl).text);
            } catch (Missing e) {
                continue;
            }
            if (!pkg.equals(uptodownPackage(d))) continue;
            UptodownApp app = new UptodownApp();
            app.detail = c;
            app.download = d;
            app.code = uptodownDataCode(d);
            El n = d.byId("detail-app-name");
            app.name = n == null ? "" : d.text(n);
            return app;
        }
        throw notListed("uptodown", pkg);
    }

    private static String uptodownVersionPage(JSONObject e) {
        JSONObject vu = obj(e, "versionURL");
        if (vu == null) return "";
        String u = str(vu, "url").trim().replaceAll("/+$", "");
        if (u.isEmpty()) return "";
        String extra = str(vu, "extraURL").trim().replaceAll("^/+|/+$", "");
        if (extra.isEmpty()) extra = "download";
        String id = str(vu, "versionID");
        if (id.isEmpty()) id = str(e, "fileID");
        if (id.isEmpty()) return "";
        return u + "/" + extra + "/" + id;
    }

    private static Listing listUptodown(String pkg, String stopAt) throws IOException {
        UptodownApp app = uptodownFind(pkg);
        Listing l = new Listing();
        l.name = app.name;
        l.appPage = app.detail;
        if (app.code.isEmpty()) {
            Doc v;
            try {
                v = new Doc(getPage("uptodown", app.detail + "/versions", app.detail + "/download").text);
            } catch (Missing e) {
                throw changed("uptodown");
            }
            app.code = uptodownDataCode(v);
            if (app.code.isEmpty()) throw changed("uptodown");
        }
        int maxPages = stopAt != null && !stopAt.isEmpty() ? MAX_UPTODOWN_PAGES_WHEN_LOOKING : MAX_UPTODOWN_PAGES;
        outer:
        for (int page = 1; page <= maxPages; page++) {
            JSONObject res;
            try {
                res = getJson("uptodown", app.detail + "/apps/" + app.code + "/versions/" + page, app.detail + "/versions");
            } catch (Missing e) {
                break;
            }
            JSONArray data = arr(res, "data");
            if (data == null) {
                if (page == 1) throw changed("uptodown");
                break;
            }
            if (data.length() == 0) break;
            for (int i = 0; i < data.length() && l.items.size() < MAX_ITEMS; i++) {
                JSONObject e = data.optJSONObject(i);
                if (e == null) continue;
                String version = str(e, "version").trim();
                String vp = uptodownVersionPage(e);
                if (version.isEmpty() || vp.isEmpty()) continue;
                Item it = new Item();
                it.version = version;
                String kind = str(e, "kindFile");
                if (kind.isEmpty()) kind = str(e, "titleKindFile");
                it.format = normFormat(kind.isEmpty() ? "apk" : kind);
                if (it.format.isEmpty()) it.format = "apk";
                it.page = vp;
                it.ref = str(e, "fileID");
                l.items.add(it);
                if (stopAt != null && !stopAt.isEmpty() && versionNameEquals(version, stopAt, false)) break outer;
            }
        }
        if (l.items.isEmpty()) throw changed("uptodown");
        return l;
    }

    private static final class UptodownVariant {
        String fileId, kind, arch;
    }

    private static List<UptodownVariant> uptodownVariants(String contentHtml, String baseUrl) {
        Doc d = new Doc(contentHtml);
        List<UptodownVariant> out = new ArrayList<UptodownVariant>();
        El content = d.first(null, "div", "content");
        if (content == null) return out;
        String arch = null;
        for (El k : content.kids) {
            if (!k.hasClass("variant")) {
                String t = d.text(k);
                if (!t.isEmpty()) arch = t;
                continue;
            }
            String fileId = "";
            for (El r : d.select(k, "", "v-report")) {
                if (!r.attr("data-file-id").isEmpty()) {
                    fileId = r.attr("data-file-id");
                    break;
                }
            }
            if (fileId.isEmpty()) continue;
            UptodownVariant v = new UptodownVariant();
            v.fileId = fileId;
            El vf = d.first(k, "", "v-file");
            El sp = vf == null ? null : d.first(vf, "span", "");
            String kind = normFormat(sp == null ? "" : d.text(sp));
            v.kind = kind.isEmpty() ? "apk" : kind;
            v.arch = arch == null ? "" : arch;
            out.add(v);
        }
        return out;
    }

    private static String uptodownDownloadUrl(Doc d) {
        El b = d.byId("detail-download-button");
        String u = b == null ? "" : b.attr("data-url").trim();
        if (u.isEmpty()) return "";
        if (u.regionMatches(true, 0, "http", 0, 4)) return u;
        return "https://dw.uptodown.com/dwn/" + u.replaceAll("^/+", "");
    }

    private static Item finishUptodown(String pkg, Item it, String abi) throws IOException {
        String detail = it.page.contains("/download/") ? it.page.substring(0, it.page.indexOf("/download/")) : it.page;
        Doc page;
        try {
            page = new Doc(getPage("uptodown", it.page, detail + "/versions").text);
        } catch (Missing e) {
            throw notListed("uptodown", pkg);
        }
        String code = uptodownDataCode(page);
        // variants (one file per CPU), as Helper does; each has its own token page
        El vb = null;
        for (El e : page.select(null, "", "variants")) {
            if (!e.attr("data-version").isEmpty()) {
                vb = e;
                break;
            }
        }
        if (vb != null && !code.isEmpty()) {
            String host = detail.contains("/") ? detail.substring(0, detail.lastIndexOf('/')) : detail;
            try {
                JSONObject files = getJson("uptodown", host + "/app/" + code + "/version/" + vb.attr("data-version") + "/files", it.page);
                String content = str(files, "content");
                List<UptodownVariant> vars = content.isEmpty() ? new ArrayList<UptodownVariant>() : uptodownVariants(content, detail);
                List<Item> cands = new ArrayList<Item>();
                for (UptodownVariant v : vars) {
                    Item c = it.copy();
                    c.format = v.kind;
                    c.abis = parseAbis(v.arch);
                    c.ref = v.fileId;
                    cands.add(c);
                }
                if (!cands.isEmpty()) {
                    Item chosen = choose(cands, abi, "uptodown");
                    Doc tok;
                    try {
                        tok = new Doc(getPage("uptodown", detail + "/download/" + chosen.ref + "-x", it.page).text);
                    } catch (Missing e) {
                        throw changed("uptodown");
                    }
                    String url = uptodownDownloadUrl(tok);
                    if (!url.isEmpty()) {
                        Item out = chosen.copy();
                        out.url = url;
                        out.referer = it.page;
                        String sha = tok.infoValue("SHA256").trim().toLowerCase(Locale.ROOT);
                        if (sha.matches("[0-9a-f]{64}")) out.sha256 = sha;
                        return out;
                    }
                    throw uptodownGated(it, tok);
                }
            } catch (Missing ignored) {
            }
        }
        String url = uptodownDownloadUrl(page);
        if (url.isEmpty()) {
            El b = page.byId("detail-download-button");
            String ext = b == null ? "" : b.attr("data-url-ext").trim();
            if (ext.startsWith("https://") && !ext.contains("play.google.com") && !ext.contains("market.android.com")) url = ext;
        }
        if (url.isEmpty()) throw uptodownGated(it, page);
        Item out = it.copy();
        out.url = url;
        out.referer = it.page;
        String sha = page.infoValue("SHA256").trim().toLowerCase(Locale.ROOT);
        if (sha.matches("[0-9a-f]{64}")) out.sha256 = sha;
        return out;
    }

    private static NeedsBrowser uptodownGated(Item it, Doc d) {
        El t = d.byId("download-turnstile-widget");
        String why = t != null && !t.attr("data-sitekey").isEmpty()
                ? "Uptodown asks for a captcha (Cloudflare Turnstile) before it shows the download link. Open the version page and download it there."
                : "Uptodown shows no download link for this version. Open the version page and download it there.";
        return new NeedsBrowser("uptodown", it.page, why);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // APKMirror: the app page lists the releases; a release page has the variants (a Cloudflare challenge may stand in front of it)
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Pattern MIRROR_APP_PATH = Pattern.compile("/apk/[^/]+/[^/]+/?");
    private static final Pattern MIRROR_EDITION = Pattern.compile("(?i)(amazon|fire-tablet|fire-tv|androidtv|wear|go-edition|lite|beta|alpha|enterprise|kids|headunit|auto)");
    private static final Pattern MIRROR_VERSION = Pattern.compile("(?i)\\d+(?:[-.]\\d+)+(?:[-.](?:alpha|beta|rc)\\d*)?");

    private static String mirrorBase() {
        return base("apkmirror", "https://www.apkmirror.com");
    }

    private static String mirrorAbs(String href) {
        if (href == null) return null;
        String h = href.trim();
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        if (h.isEmpty()) return null;
        return abs(mirrorBase() + "/", h);
    }

    private static String pathOf(String url) {
        try {
            return new URL(url).getPath();
        } catch (Exception e) {
            return "";
        }
    }

    /** The version of a release address: the numbers at the end of the slug, after the app's own slug when that is known. */
    static String mirrorVersionFromReleaseUrl(String url, String appPageUrl) {
        String path = pathOf(url).replaceAll("/+$", "");
        String slug = path.substring(path.lastIndexOf('/') + 1);
        if (slug.endsWith("-release")) slug = slug.substring(0, slug.length() - "-release".length());
        if (appPageUrl != null) {
            String ap = pathOf(appPageUrl).replaceAll("/+$", "");
            String appSlug = ap.substring(ap.lastIndexOf('/') + 1);
            if (!appSlug.isEmpty() && slug.startsWith(appSlug + "-")) slug = slug.substring(appSlug.length() + 1);
        }
        Matcher m = MIRROR_VERSION.matcher(slug);
        String last = "";
        while (m.find()) last = m.group();
        if (last.isEmpty()) return "";
        String v = last.replace('-', '.');
        if (hasVariantBuildMarker(slug)) v += "-SECONDARY";
        return v;
    }

    private static int mirrorSlugScore(String url, Set<String> expected) {
        String[] seg = pathOf(url).replaceAll("^/+|/+$", "").split("/");
        String dev = seg.length > 1 ? seg[1] : "";
        String app = seg.length > 2 ? seg[2] : "";
        int score = Math.min(slugScore(dev, expected), slugScore(app, expected));
        if (MIRROR_EDITION.matcher(app).find()) score += 4;
        return score;
    }

    private static int slugScore(String slug, Set<String> expected) {
        if (expected.contains(slug)) return 0;
        for (String e : expected) if (slug.endsWith(e)) return 1;
        for (String e : expected) if (slug.contains(e)) return 2;
        return 3;
    }

    private static String slugOf(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("&", " and ").replace("'", "").replaceAll("[^a-z0-9]+", "-");
        return t.replaceAll("^-+|-+$", "");
    }

    private static boolean mirrorMatchesPackage(Doc d, String pkg) {
        if (d.byId(pkg) != null) return true;
        for (El a : d.select(null, "a", "")) {
            String h = a.attr("href");
            if (h.contains("play.google.com") && Pattern.compile("[?&]id=" + Pattern.quote(pkg) + "(?:[&#]|$)").matcher(h).find()) return true;
        }
        return d.infoValue("Package Name").equals(pkg);
    }

    private static List<String> mirrorReleaseLinks(Doc d, String appPageUrl) {
        String appPath = appPageUrl == null ? null : pathOf(appPageUrl).replaceAll("/+$", "");
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (El a : d.select(null, "a", "")) {
            String u = mirrorAbs(a.attr("href"));
            if (u == null) continue;
            String path = pathOf(u);
            if (!path.startsWith("/apk/") || !path.replaceAll("/+$", "").endsWith("-release")) continue;
            if (appPath != null && !path.startsWith(appPath + "/")) continue;
            out.add(path.endsWith("/") ? u : u + "/");
            if (out.size() >= MAX_ITEMS) break;
        }
        return new ArrayList<String>(out);
    }

    private static Listing listApkMirror(String pkg) throws IOException {
        String searchUrl = mirrorBase() + "/?post_type=app_release&searchtype=app&s=" + enc(pkg);
        Doc search;
        try {
            search = new Doc(getPage("apkmirror", searchUrl, null).text);
        } catch (Missing e) {
            throw notListed("apkmirror", pkg);
        }
        for (El p : search.select(null, "p", "")) if (search.text(p).toLowerCase(Locale.ROOT).contains("no results found matching your query")) throw notListed("apkmirror", pkg);
        Set<String> expected = new LinkedHashSet<String>();
        String[] parts = pkg.split("\\.");
        if (parts.length >= 2) expected.add(slugOf(parts[parts.length - 2] + "-" + parts[parts.length - 1]));
        if (parts.length >= 3) expected.add(slugOf(parts[parts.length - 3] + "-" + parts[parts.length - 2] + "-" + parts[parts.length - 1]));
        expected.add(slugOf(parts[parts.length - 1]));
        final Set<String> exp = expected;
        List<String> cands = new ArrayList<String>();
        for (El a : search.select(null, "a", "")) {
            String u = mirrorAbs(a.attr("href"));
            if (u != null && MIRROR_APP_PATH.matcher(pathOf(u)).matches()) {
                String n = u.endsWith("/") ? u : u + "/";
                if (!cands.contains(n)) cands.add(n);
            }
        }
        final Map<String, Integer> scores = new HashMap<String, Integer>();
        for (String c : cands) scores.put(c, mirrorSlugScore(c, exp));
        Collections.sort(cands, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return scores.get(a) - scores.get(b);
            }
        });
        Listing l = new Listing();
        int tried = 0;
        for (String c : cands) {
            if (tried++ >= MAX_CANDIDATES) break;
            Doc d;
            try {
                d = new Doc(getPage("apkmirror", c, searchUrl).text);
            } catch (Missing e) {
                continue;
            }
            if (!mirrorMatchesPackage(d, pkg)) continue;
            l.appPage = c;
            El h1 = d.first(null, "h1", "");
            l.name = h1 == null ? "" : d.text(h1);
            for (String rel : mirrorReleaseLinks(d, c)) {
                String v = mirrorVersionFromReleaseUrl(rel, c);
                if (v.isEmpty()) continue;
                Item it = new Item();
                it.version = v;
                it.page = rel;
                l.items.add(it);
            }
            if (l.items.isEmpty()) throw changed("apkmirror");
            return l;
        }
        throw notListed("apkmirror", pkg);
    }

    private static final class MirrorVariant {
        String url, kind = "apk", arch = "", dpi = "";
        long code;
        boolean bundle;
    }

    private static List<MirrorVariant> mirrorVariants(Doc d) {
        List<MirrorVariant> out = new ArrayList<MirrorVariant>();
        for (El row : d.select(null, "div", "table-row")) {
            if (!row.hasClass("headerFont")) continue;
            List<El> cells = new ArrayList<El>();
            for (El k : row.kids) if (k.tag.equals("div") && k.hasClass("table-cell")) cells.add(k);
            if (cells.isEmpty()) continue;
            El a = d.first(cells.get(0), "a", "");
            String url = a == null ? null : mirrorAbs(a.attr("href"));
            if (url == null) continue;
            MirrorVariant v = new MirrorVariant();
            v.url = url;
            El badge = d.first(cells.get(0), "span", "apkm-badge");
            String type = badge == null ? "" : d.text(badge).toLowerCase(Locale.ROOT);
            String kind = normFormat(type);
            v.kind = kind.isEmpty() ? "apk" : kind;
            v.bundle = !v.kind.equals("apk");
            v.arch = cells.size() > 1 ? d.text(cells.get(1)) : "";
            v.dpi = cells.size() > 3 ? d.text(cells.get(3)) : "";
            // the build number is the digits-only grey line under the version
            for (El sp : d.select(cells.get(0), "span", "colorLightBlack")) {
                String t = d.text(sp);
                if (t.matches("\\d{1,12}")) {
                    v.code = Long.parseLong(t);
                    break;
                }
            }
            out.add(v);
        }
        return out;
    }

    private static String mirrorDownloadButton(Doc d, boolean bundle) {
        LinkedHashSet<String> urls = new LinkedHashSet<String>();
        for (El a : d.select(null, "a", "")) {
            String h = a.attr("href");
            if (a.hasClass("downloadButton") || h.contains("/download/?key")) {
                String u = mirrorAbs(h);
                if (u != null) urls.add(u);
            }
        }
        String any = null;
        for (String u : urls) {
            if (any == null) any = u;
            boolean force = u.toLowerCase(Locale.ROOT).contains("forcebaseapk");
            if (bundle ? !force : force) return u;
        }
        return any;
    }

    private static String mirrorFinalLink(Doc d) {
        El a = d.byId("download-link");
        if (a != null && a.tag.equals("a") && !a.attr("href").isEmpty()) return mirrorAbs(a.attr("href"));
        for (El x : d.select(null, "a", "")) {
            String h = x.attr("href");
            String lh = h.toLowerCase(Locale.ROOT);
            if (lh.contains("download.php") || lh.contains("/download/?key=")) return mirrorAbs(h);
        }
        return null;
    }

    private static String mirrorFileSha256(Doc d) {
        El modal = null;
        El safe = d.byId("safeDownload");
        if (safe != null) modal = d.first(safe, "", "modal-body");
        if (modal == null) {
            for (El e : d.select(null, "", "safeDownload")) {
                modal = d.first(e, "", "modal-body");
                if (modal != null) break;
            }
        }
        if (modal == null) return "";
        String text = d.text(modal);
        int a = text.toLowerCase(Locale.ROOT).indexOf("apk file hashes");
        String section = a >= 0 ? text.substring(a) : text;
        int b = section.toLowerCase(Locale.ROOT).indexOf("apk certificate fingerprints");
        if (b >= 0) section = section.substring(0, b);
        Matcher m = Pattern.compile("[0-9a-fA-F]{64}").matcher(section);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : "";
    }

    private static Item finishApkMirror(String pkg, Item it, String abi) throws IOException {
        Doc rel;
        try {
            rel = new Doc(getPage("apkmirror", it.page, null).text);
        } catch (Missing e) {
            throw notListed("apkmirror", pkg);
        }
        List<MirrorVariant> vars = mirrorVariants(rel);
        MirrorVariant pick = null;
        if (!vars.isEmpty()) {
            List<Item> cands = new ArrayList<Item>();
            List<Item> screenless = new ArrayList<Item>();
            for (int i = 0; i < vars.size(); i++) {
                MirrorVariant v = vars.get(i);
                Item c = it.copy();
                c.format = v.kind;
                c.abis = parseAbis(v.arch);
                c.ref = String.valueOf(i);
                cands.add(c);
                // "nodpi" and "anydpi" fit every screen; a bundle for one DPI range is the second choice (Helper)
                String dpi = v.dpi.toLowerCase(Locale.ROOT);
                if (dpi.isEmpty() || dpi.equals("nodpi") || dpi.equals("anydpi")) screenless.add(c);
            }
            Item chosen = choose(screenless.isEmpty() ? cands : screenless, abi, "apkmirror");
            pick = vars.get(Integer.parseInt(chosen.ref));
        }
        String variantUrl = pick == null ? it.page : pick.url;
        Doc variantDoc = pick == null ? rel : null;
        if (variantDoc == null) {
            try {
                variantDoc = new Doc(getPage("apkmirror", variantUrl, it.page).text);
            } catch (Missing e) {
                throw notListed("apkmirror", pkg);
            }
        }
        boolean bundle = pick != null ? pick.bundle : mirrorLooksBundle(variantDoc);
        String button = mirrorDownloadButton(variantDoc, bundle);
        if (button == null) throw new NeedsBrowser("apkmirror", it.page, "APKMirror shows no direct download for this release. Open the release page and download it there.");
        Doc dl;
        try {
            dl = new Doc(getPage("apkmirror", button, variantUrl).text);
        } catch (Missing e) {
            throw notListed("apkmirror", pkg);
        }
        String fin = mirrorFinalLink(dl);
        if (fin == null) throw new NeedsBrowser("apkmirror", it.page, "APKMirror shows no direct download for this release. Open the release page and download it there.");
        Item out = it.copy();
        out.url = fin;
        out.referer = button;
        out.format = pick != null ? pick.kind : bundle ? "apkm" : fileKindFromUrl(fin);
        out.abis = pick != null ? parseAbis(pick.arch) : new ArrayList<String>();
        if (pick != null && pick.code > 0) out.code = pick.code;
        out.sha256 = bundle ? "" : mirrorFileSha256(variantDoc);
        return out;
    }

    private static boolean mirrorLooksBundle(Doc d) {
        for (El e : d.select(null, "span", "apkm-badge")) if (d.text(e).toLowerCase(Locale.ROOT).contains("bundle")) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Evozi / APKCube: the Next.js payload of its pages (version list with build, ABI, size and SHA-256; the file is behind Turnstile)
    // ------------------------------------------------------------------------------------------------------------------------------

    /** The payload of a Next.js page holds JSON strings inside a script, so its quotes arrive escaped as \" ; undo that one escape. */
    private static String unescapeFlight(String s) {
        return s.indexOf('\\') < 0 ? s : s.replace("\\\"", "\"");
    }

    /** The JSON array that follows {@code "key":} in the text (balanced, string-aware), or null. */
    private static JSONArray flightArray(String s, String key) {
        int at = s.indexOf("\"" + key + "\":[");
        if (at < 0) return null;
        int start = s.indexOf('[', at);
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length() && i < start + 4 * 1024 * 1024; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    try {
                        return new JSONArray(s.substring(start, i + 1));
                    } catch (JSONException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Listing listEvozi(String pkg) throws IOException {
        String b = base("evozi", "https://apkcube.com");
        String html;
        try {
            html = unescapeFlight(getPage("evozi", b + "/apk-downloader?url=" + enc(pkg), null).text);
        } catch (Missing e) {
            throw notListed("evozi", pkg);
        }
        Matcher m = Pattern.compile("href\":\"(/([a-z0-9-]+)/" + Pattern.quote(pkg) + "/download[^\"]*)\"").matcher(html);
        if (!m.find()) throw notListed("evozi", pkg);
        String slug = m.group(2);
        String appUrl = b + "/" + slug + "/" + pkg;
        String vp;
        try {
            vp = unescapeFlight(getPage("evozi", appUrl + "/old-versions", b + "/apk-downloader?url=" + enc(pkg)).text);
        } catch (Missing e) {
            throw changed("evozi");
        }
        Listing l = new Listing();
        l.appPage = appUrl;
        l.name = pkg;
        MorpheHelper.Doc vdoc = new MorpheHelper.Doc(vp);
        El heading = vdoc.first(null, "h1", "");
        if (heading != null) {
            String hn = vdoc.text(heading).replaceAll("(?i)\\s*[\\u2014\\u2013-]\\s*old versions.*$", "").trim();
            if (!hn.isEmpty()) l.name = hn;
        }
        JSONArray apks = flightArray(vp, "apks");
        if (apks != null) {
            for (int i = 0; i < apks.length() && l.items.size() < MAX_ITEMS; i++) {
                JSONObject a = apks.optJSONObject(i);
                if (a == null) continue;
                String version = str(a, "versionName").trim();
                if (version.isEmpty()) continue;
                Item it = new Item();
                it.version = version;
                it.code = lng(a, "versionCode");
                it.size = lng(a, "fileSize");
                String sha = str(a, "sha256").trim().toLowerCase(Locale.ROOT);
                it.sha256 = sha.matches("[0-9a-f]{64}") ? sha : "";
                it.format = normFormat(str(a, "format"));
                if (it.format.isEmpty()) it.format = "apk";
                it.abis = parseAbis(str(a, "arch"));
                it.ref = str(a, "apkId");
                it.page = appUrl + "/download?version=" + enc(version);
                l.items.add(it);
            }
        } else {
            Matcher vm = Pattern.compile("versionName\":\"([^\"]+)\"").matcher(vp);
            Set<String> seen = new LinkedHashSet<String>();
            while (vm.find() && seen.size() < MAX_ITEMS) seen.add(vm.group(1).trim());
            for (String v : seen) {
                if (v.isEmpty()) continue;
                Item it = new Item();
                it.version = v;
                it.format = "apk";
                it.page = appUrl + "/download?version=" + enc(v);
                l.items.add(it);
            }
        }
        if (l.items.isEmpty()) throw changed("evozi");
        return l;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Choosing: the version, then the file of that version
    // ------------------------------------------------------------------------------------------------------------------------------

    private static List<Item> sameVersion(List<Item> items, Item any) {
        List<Item> out = new ArrayList<Item>();
        for (Item i : items) if (i.version.equals(any.version) || (versionNameEquals(i.version, any.version, false))) out.add(i);
        return out;
    }

    /** The best file among the files of one version: a plain APK first, then the bundles; the one built for the phone's ABI first. */
    private static Item choose(List<Item> files, String abi, String sourceId) throws IOException {
        if (files.isEmpty()) throw changed(sourceId);
        boolean wantAbi = !normAbi(abi).isEmpty();
        boolean anyFits = false;
        for (Item i : files) if (abiScore(i.abis, abi) > 0) anyFits = true;
        if (wantAbi && !anyFits) {
            StringBuilder have = new StringBuilder();
            for (Item i : files) {
                String l = abiLabel(i.abis);
                if (!l.isEmpty() && have.indexOf(l) < 0) have.append(have.length() > 0 ? "; " : "").append(l);
            }
            throw new IOException(nameOf(sourceId) + " has this version only for " + (have.length() == 0 ? "other CPUs" : have.toString()) + ", not for " + normAbi(abi));
        }
        Item best = null;
        int bestFormat = 0, bestAbi = 0;
        for (Item i : files) {
            int sc = abiScore(i.abis, abi);
            if (anyFits && sc == 0) continue;
            int fr = formatRank(i.format);
            if (best == null || fr < bestFormat || (fr == bestFormat && sc > bestAbi)) {
                best = i;
                bestFormat = fr;
                bestAbi = sc;
            }
        }
        return best == null ? files.get(0) : best;
    }

    private static boolean requestsVariant(String wanted) {
        return hasVariantBuildMarker(wanted);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Public: version lists and resolving
    // ------------------------------------------------------------------------------------------------------------------------------

    private static Listing list(String id, String pkg, String stopAt, String abi, boolean exhaustive) throws IOException {
        switch (id) {
            case "apkmirror": return listApkMirror(pkg);
            case "uptodown": return listUptodown(pkg, stopAt);
            case "apkpure": return listApkPure(pkg, abi, exhaustive, stopAt);
            case "apkcombo": return listApkCombo(pkg, stopAt);
            case "aptoide": return listAptoide(pkg);
            case "evozi": return listEvozi(pkg);
            default: throw new IOException(nameOf(id) + " cannot be read without a browser");
        }
    }

    private static Item finish(String id, String pkg, Item it, String abi) throws IOException {
        switch (id) {
            case "apkmirror": return finishApkMirror(pkg, it, abi);
            case "uptodown": return finishUptodown(pkg, it, abi);
            case "apkpure":
                if (it.url.isEmpty()) throw new NeedsBrowser(id, manualUrl(id, pkg, it.version), "APKPure gave no download link for " + it.version + ". Open it in the browser.");
                return it;
            case "apkcombo": return finishApkCombo(pkg, it, abi);
            case "aptoide": return finishAptoide(pkg, it);
            case "evozi":
                throw new NeedsBrowser(id, it.page, "APKCube (Evozi) gates the file behind a Cloudflare captcha. Open the page in the browser and download it there.");
            default: throw new IOException(nameOf(id) + " cannot be downloaded without a browser");
        }
    }

    private static NeedsBrowser manualOnly(Src s, String pkg, String version) {
        return new NeedsBrowser(s.id, manualUrl(s.id, pkg, version), s.name + " can only be used in the browser: " + s.note);
    }

    private static JSONObject itemJson(Item it) {
        JSONObject o = new JSONObject();
        put(o, "version", it.version);
        put(o, "versionCode", it.code);
        put(o, "format", it.format);
        put(o, "abi", abiLabel(it.abis));
        put(o, "size", it.size);
        put(o, "page", it.page);
        put(o, "url", it.url);
        put(o, "sha256", it.sha256);
        if (!it.md5.isEmpty()) put(o, "md5", it.md5);
        if (!it.store.isEmpty()) put(o, "store", it.store);
        return o;
    }

    /** The versions a source lists for a package, newest first. */
    public static JSONObject versions(String sourceId, String pkg) throws IOException {
        Src s = needSrc(sourceId);
        checkPkg(pkg);
        if (!s.lists()) throw manualOnly(s, pkg, null);
        Listing l;
        try {
            l = list(s.id, pkg, null, "", true);
        } catch (Blocked b) {
            throw new NeedsBrowser(s.id, manualUrl(s.id, pkg, null), b.getMessage() + " Open it in the browser.");
        } catch (RuntimeException e) {
            throw changed(s.id);
        }
        sortNewestFirst(l.items);
        JSONObject o = new JSONObject();
        put(o, "ok", Boolean.TRUE);
        put(o, "source", s.id);
        put(o, "pkg", pkg);
        put(o, "name", l.name.isEmpty() ? pkg : l.name);
        JSONArray a = new JSONArray();
        for (Item it : l.items) a.put(itemJson(it));
        put(o, "versions", a);
        return o;
    }

    private static String newestOf(List<Item> items) {
        String best = null;
        for (Item i : items) if (!hasVariantBuildMarker(i.version) && (best == null || compareVersionNames(i.version, best) > 0)) best = i.version;
        if (best == null) for (Item i : items) if (best == null || compareVersionNames(i.version, best) > 0) best = i.version;
        return best == null ? "" : best;
    }

    private static boolean prerelease(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        return s.contains("alpha") || s.contains("beta");
    }

    /**
     * What to download for a package from one source. policy "requested" is the exact version (the name, and the build in a "1.2.3 (456)" form
     * when given; the same name with another build is a "build mismatch", a "-SECONDARY" build never stands in for a plain version),
     * "latest" is the newest the source lists. abi is the phone's ABI ("" = any; a list such as "arm64-v8a,armeabi-v7a" counts by its first). Returns the file to fetch (url, referer, size, sha256 or md5
     * when the source publishes one) and whether it is the exact version asked for. A source that hides the file behind a browser check
     * throws {@link NeedsBrowser}.
     */
    public static JSONObject resolve(String sourceId, String pkg, String wantedVersion, String abi, String policy) throws IOException {
        Src s = needSrc(sourceId);
        checkPkg(pkg);
        boolean requested = "requested".equals(policy);
        if (!requested && !"latest".equals(policy)) throw new IOException("unknown version policy: " + policy + " (requested or latest)");
        String wanted = wantedVersion == null ? "" : wantedVersion.trim();
        String name = withoutTrailingVersionCode(wanted);
        long wantedCode = trailingVersionCode(wanted);
        if (requested && name.isEmpty()) throw new IOException("the requested version is needed for the \"requested\" policy");
        if (!s.lists()) throw manualOnly(s, pkg, name);
        // a list of ABIs (Build.SUPPORTED_ABIS) is taken by its first, best one
        String wantAbi = abi == null ? "" : abi.trim().split("[,;\\s]+")[0];
        Listing l;
        try {
            l = list(s.id, pkg, requested ? name : null, wantAbi, false);
        } catch (Blocked b) {
            throw new NeedsBrowser(s.id, manualUrl(s.id, pkg, name), b.getMessage() + " Open it in the browser.");
        } catch (RuntimeException e) {
            throw changed(s.id);
        }
        sortNewestFirst(l.items);
        List<Item> pool;
        if (requested) {
            boolean variant = requestsVariant(name);
            pool = new ArrayList<Item>();
            for (Item i : l.items) {
                if (hasVariantBuildMarker(i.version) && !variant) continue;
                if (versionNameEquals(i.version, name, variant)) pool.add(i);
            }
            if (variant) {
                // a request for the variant build gets the variant, not the plain build that matches the number too
                List<Item> marked = new ArrayList<Item>();
                for (Item i : pool) if (hasVariantBuildMarker(i.version)) marked.add(i);
                if (!marked.isEmpty()) pool = marked;
            }
            if (pool.isEmpty()) {
                String newest = newestOf(l.items);
                if (s.id.equals("apkpure")) {
                    throw new NeedsBrowser(s.id, manualUrl(s.id, pkg, name), "APKPure only hands out its newest build (" + newest + ") without a browser, not " + name + ". Open it in the browser for older versions.");
                }
                throw new IOException(s.name + " does not list version " + name + " of " + pkg + (newest.isEmpty() ? "" : " (the newest it lists is " + newest + ")"));
            }
            if (wantedCode > 0) {
                List<Item> fit = new ArrayList<Item>();
                for (Item i : pool) if (i.code <= 0 || i.code == wantedCode) fit.add(i);
                if (fit.isEmpty()) {
                    throw new IOException("Build mismatch: " + s.name + " has " + pkg + " " + name + " as build " + pool.get(0).code + ", the request is for build " + wantedCode + ". Pick it yourself in the browser.");
                }
                pool = fit;
            }
        } else {
            List<Item> plain = new ArrayList<Item>();
            for (Item i : l.items) if (!hasVariantBuildMarker(i.version)) plain.add(i);
            if (plain.isEmpty()) throw new IOException(s.name + " lists only alternate (-SECONDARY) builds of " + pkg + ", which cannot be patched");
            List<Item> stable = new ArrayList<Item>();
            for (Item i : plain) if (!prerelease(i.version)) stable.add(i);
            List<Item> from = stable.isEmpty() ? plain : stable;
            Item newest = from.get(0);
            for (Item i : from) if (compareVersionNames(i.version, newest.version) > 0) newest = i;
            pool = sameVersion(from, newest);
        }
        Item picked = choose(pool, wantAbi, s.id);
        Item done;
        try {
            done = finish(s.id, pkg, picked, wantAbi);
        } catch (Blocked b) {
            throw new NeedsBrowser(s.id, picked.page.isEmpty() ? manualUrl(s.id, pkg, name) : picked.page, b.getMessage() + " Open the page in the browser.");
        } catch (RuntimeException e) {
            throw changed(s.id);
        }
        if (done.url.isEmpty()) throw new NeedsBrowser(s.id, done.page, s.name + " gave no download link. Open the page in the browser.");
        // a source that only shows the build number on the file's own page (APKMirror) is checked here
        if (requested && wantedCode > 0 && done.code > 0 && done.code != wantedCode) {
            throw new IOException("Build mismatch: " + s.name + " has " + pkg + " " + name + " as build " + done.code + ", the request is for build " + wantedCode + ". Pick it yourself in the browser.");
        }
        JSONObject o = new JSONObject();
        put(o, "ok", Boolean.TRUE);
        put(o, "source", s.id);
        put(o, "pkg", pkg);
        put(o, "name", l.name.isEmpty() ? pkg : l.name);
        put(o, "version", done.version);
        put(o, "versionCode", done.code);
        put(o, "format", done.format.isEmpty() ? fileKindFromUrl(done.url) : done.format);
        put(o, "abi", abiLabel(done.abis));
        put(o, "url", done.url);
        put(o, "size", done.size);
        put(o, "sha256", done.sha256);
        put(o, "md5", done.md5);
        put(o, "page", done.page.isEmpty() ? manualUrl(s.id, pkg, name) : done.page);
        put(o, "referer", done.referer);
        boolean exact = !name.isEmpty() && versionNameEquals(done.version, name, requestsVariant(name)) && (wantedCode <= 0 || done.code <= 0 || done.code == wantedCode);
        put(o, "exact", exact);
        put(o, "newer", !name.isEmpty() && compareVersionNames(done.version, name) > 0);
        put(o, "wanted", name);
        put(o, "wantedCode", wantedCode);
        put(o, "policy", requested ? "requested" : "latest");
        return o;
    }

    /**
     * Fast Mode: walks the enabled sources that can download without a browser, in the order given, and returns the first resolve() that
     * works plus "tried" (what each source answered). With the policy "latest" and a wanted version, a source whose newest build is older
     * than the wanted one does not count as a "latest" (as in Helper). Nothing found is a {@link FastFailed} with the same list.
     */
    public static JSONObject fast(JSONArray enabledSourceIds, String pkg, String wantedVersion, String abi, String policy) throws IOException {
        checkPkg(pkg);
        boolean requested = "requested".equals(policy);
        if (!requested && !"latest".equals(policy)) throw new IOException("unknown version policy: " + policy + " (requested or latest)");
        JSONArray tried = new JSONArray();
        StringBuilder why = new StringBuilder();
        String wanted = wantedVersion == null ? "" : withoutTrailingVersionCode(wantedVersion.trim());
        int walked = 0;
        for (int i = 0; enabledSourceIds != null && i < enabledSourceIds.length(); i++) {
            String id = enabledSourceIds.optString(i, "");
            Src s = src(id);
            if (s == null || !s.direct) continue;
            walked++;
            String error = null;
            try {
                JSONObject r = resolve(id, pkg, wantedVersion, abi, policy);
                if (!requested && !wanted.isEmpty() && compareVersionNames(str(r, "version"), wanted) < 0) {
                    error = "its newest build (" + str(r, "version") + ") is older than the requested " + wanted;
                } else {
                    JSONObject t = new JSONObject();
                    put(t, "source", id);
                    put(t, "ok", Boolean.TRUE);
                    put(t, "error", "");
                    tried.put(t);
                    put(r, "tried", tried);
                    return r;
                }
            } catch (IOException e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (RuntimeException e) {
                error = nameOf(id) + " changed its page format, open it in the browser";
            }
            JSONObject t = new JSONObject();
            put(t, "source", id);
            put(t, "ok", Boolean.FALSE);
            put(t, "error", error);
            tried.put(t);
            if (why.length() > 0) why.append(" | ");
            why.append(s.name).append(": ").append(error);
        }
        throw new FastFailed(tried, walked == 0 ? "No enabled source can download without a browser." : "No source could provide " + pkg + (wanted.isEmpty() ? "" : " " + wanted) + ": " + why);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Download and the check of what came down
    // ------------------------------------------------------------------------------------------------------------------------------

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) {
            b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return b.toString();
    }

    private static String safeName(String s) {
        String t = s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (t.length() > 80) t = t.substring(0, 80);
        return t.isEmpty() ? "x" : t;
    }

    /** SHA-256 and MD5 of a file in one pass. */
    private static String[] digests(File f, MorpheNet.Progress p) throws IOException {
        MessageDigest sha, md5;
        try {
            sha = MessageDigest.getInstance("SHA-256");
            md5 = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256 on this device");
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (p != null && p.cancelled()) throw new IOException("cancelled");
                sha.update(buf, 0, n);
                md5.update(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return new String[] {hex(sha.digest()), hex(md5.digest())};
    }

    /**
     * Downloads what resolve() found into destDir as "pkg_version.ext", checks the SHA-256 (or MD5) the source published, reads the real
     * manifest and refuses a file whose package, version name or build differs from the request. The file appears under its name only
     * when it is whole and checked: it is written to name.dl.part, then name.dl, then renamed; a cancel or a refusal leaves nothing behind.
     */
    public static JSONObject download(JSONObject resolved, File destDir, MorpheNet.Progress p) throws IOException {
        if (resolved == null) throw new IOException("nothing to download");
        String pkg = str(resolved, "pkg");
        checkPkg(pkg);
        String url = str(resolved, "url");
        if (url.isEmpty()) throw new IOException("no download address: open the page in the browser instead");
        String version = str(resolved, "version");
        String source = str(resolved, "source");
        String format = normFormat(str(resolved, "format"));
        String wantedSha = str(resolved, "sha256").trim().toLowerCase(Locale.ROOT);
        String wantedMd5 = str(resolved, "md5").trim().toLowerCase(Locale.ROOT);
        String sourceName = src(source) == null ? source : nameOf(source);
        String baseName = safeName(pkg) + "_" + safeName(version.isEmpty() ? "x" : version);
        File tmp = new File(destDir, baseName + ".dl");
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", "apkpure".equals(source) ? APKPURE_UA : BROWSER_UA);
        String ref = str(resolved, "referer");
        if (!ref.isEmpty()) h.put("Referer", ref);
        boolean keep = false;
        try {
            MorpheNet.download(url, tmp, h, p);
            if (p != null && p.cancelled()) throw new IOException("cancelled");
            String[] dg = digests(tmp, p);
            boolean verified = false;
            if (wantedSha.matches("[0-9a-f]{64}")) {
                if (!wantedSha.equals(dg[0])) throw new IOException("The file does not match the SHA-256 that " + sourceName + " published for it (got " + dg[0].substring(0, 12) + "..., expected " + wantedSha.substring(0, 12) + "...). Refused: open the source in the browser instead.");
                verified = true;
            }
            if (wantedMd5.matches("[0-9a-f]{32}")) {
                if (!wantedMd5.equals(dg[1])) throw new IOException("The file does not match the MD5 that " + sourceName + " published for it. Refused: open the source in the browser instead.");
                verified = true;
            }
            JSONObject info = inspectImpl(tmp, format, dg[0]);
            if (!info.optBoolean("ok", false)) throw new IOException("The downloaded file is not a usable Android package: " + str(info, "error") + " Refused.");
            List<String> problems = new ArrayList<String>();
            String realPkg = str(info, "pkg");
            if (!realPkg.equals(pkg)) problems.add("Package: requested " + pkg + ", found " + (realPkg.isEmpty() ? "unknown" : realPkg));
            String realName = str(info, "versionName");
            long realCode = lng(info, "versionCode");
            String wanted = str(resolved, "wanted");
            long wantedCode = lng(resolved, "wantedCode");
            boolean requested = "requested".equals(str(resolved, "policy"));
            if (requested && !wanted.isEmpty() && !realName.isEmpty() && !versionNameEquals(realName, wanted, requestsVariant(wanted))) {
                problems.add("Version: requested " + wanted + ", found " + realName);
            }
            if (!requested && !wanted.isEmpty() && !realName.isEmpty() && compareVersionNames(realName, wanted) < 0) {
                problems.add("Version: " + realName + " is older than the requested " + wanted);
            }
            long claimed = lng(resolved, "versionCode");
            if (wantedCode > 0 && requested && realCode > 0 && realCode != wantedCode) problems.add("Build mismatch: requested build " + wantedCode + ", found " + realCode);
            else if (claimed > 0 && realCode > 0 && realCode != claimed) problems.add("Build mismatch: " + sourceName + " said build " + claimed + ", the file is build " + realCode);
            if (!problems.isEmpty()) {
                StringBuilder m = new StringBuilder("Downloaded file does not match the request.");
                for (String x : problems) m.append('\n').append(x);
                m.append("\nOpen the source in the browser instead.");
                throw new IOException(m.toString());
            }
            String realFormat = str(info, "format");
            String ext = normFormat(realFormat).isEmpty() ? (format.isEmpty() ? "apk" : format) : realFormat;
            File dest = new File(destDir, baseName + "." + ext);
            if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest.getName());
            if (!tmp.renameTo(dest)) throw new IOException("cannot move the download to " + dest.getName());
            keep = true;
            JSONObject out = new JSONObject();
            put(out, "ok", Boolean.TRUE);
            put(out, "path", dest.getAbsolutePath());
            put(out, "size", dest.length());
            put(out, "sha256", dg[0]);
            put(out, "pkg", realPkg);
            put(out, "versionName", realName);
            put(out, "versionCode", realCode);
            put(out, "format", ext);
            put(out, "splits", lng(info, "splits"));
            put(out, "abis", arr(info, "abis") == null ? new JSONArray() : arr(info, "abis"));
            put(out, "verified", verified);
            put(out, "source", source);
            return out;
        } finally {
            if (!keep) {
                tmp.delete();
                new File(tmp.getPath() + ".part").delete();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Inspecting a file: the manifest of an APK, or of the base APK in a bundle
    // ------------------------------------------------------------------------------------------------------------------------------

    private static final Pattern ABI_IN_NAME = Pattern.compile("(?<![a-z0-9])(arm64[_-]v8a|armeabi[_-]v7a|x86[_-]64|armeabi|x86|mips64|mips)(?![a-z0-9])");

    /** What a file is: {ok, path, size, sha256, format, pkg, versionName, versionCode, splits, abis, error}. A bad file is ok:false with the reason. */
    public static JSONObject inspect(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no such file: " + (f == null ? "" : f.getName()));
        String n = f.getName().toLowerCase(Locale.ROOT);
        String hint = n.endsWith(".xapk") ? "xapk" : n.endsWith(".apks") ? "apks" : n.endsWith(".apkm") ? "apkm" : n.endsWith(".apk") ? "apk" : "";
        return inspectImpl(f, hint, null);
    }

    private static JSONObject inspectImpl(File f, String hint, String sha) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "ok", Boolean.FALSE);
        put(o, "path", f.getAbsolutePath());
        put(o, "size", f.length());
        put(o, "sha256", sha != null ? sha : digests(f, null)[0]);
        put(o, "format", "");
        put(o, "pkg", "");
        put(o, "versionName", "");
        put(o, "versionCode", 0L);
        put(o, "splits", 0L);
        put(o, "abis", new JSONArray());
        put(o, "error", "");
        ZipTool.Archive a;
        try {
            a = ZipTool.open(f);
        } catch (IOException e) {
            return fail(o, "this is not a zip file, so it is neither an APK nor a bundle of APKs");
        } catch (RuntimeException e) {
            return fail(o, "this is not a readable zip file");
        }
        if (!a.isZip()) return fail(o, "this is a " + a.format + " archive, not an APK or a bundle of APKs");
        ZipTool.Entry root = a.find("AndroidManifest.xml");
        List<ZipTool.Entry> apks = new ArrayList<ZipTool.Entry>();
        for (ZipTool.Entry e : a.entries) if (!e.dir && e.name.toLowerCase(Locale.ROOT).endsWith(".apk")) apks.add(e);
        String xml = null;
        LinkedHashSet<String> abis = new LinkedHashSet<String>();
        if (root != null) {
            put(o, "format", "apk");
            xml = readManifest(a, root);
            for (ZipTool.Entry e : a.entries) {
                if (!e.name.startsWith("lib/")) continue;
                String[] seg = e.name.split("/");
                if (seg.length >= 3) {
                    String ab = normAbi(seg[1]);
                    if (!ab.isEmpty()) abis.add(ab);
                }
            }
        } else if (!apks.isEmpty()) {
            String fmt = bundleFormat(a, hint);
            put(o, "format", fmt);
            put(o, "splits", (long) apks.size());
            for (ZipTool.Entry e : apks) {
                Matcher m = ABI_IN_NAME.matcher(e.baseName().toLowerCase(Locale.ROOT).replaceAll("\\.apk$", ""));
                while (m.find()) abis.add(normAbi(m.group(1)));
            }
            ZipTool.Entry base = pickBase(a, apks);
            xml = base == null ? null : readManifest(a, base);
            if (xml == null) {
                // the manifest of the base could not be read: the bundle's own description says the same things
                JSONObject meta = bundleMeta(a);
                if (meta != null) {
                    put(o, "pkg", firstNonEmpty(str(meta, "package_name"), str(meta, "pname")));
                    put(o, "versionName", firstNonEmpty(str(meta, "version_name"), str(meta, "release_version")));
                    put(o, "versionCode", firstLong(lng(meta, "version_code"), lng(meta, "versioncode")));
                }
            }
        } else {
            return fail(o, "there is no AndroidManifest.xml and no APK inside: this is not an Android package");
        }
        JSONArray ja = new JSONArray();
        for (String ab : abis) ja.put(ab);
        put(o, "abis", ja);
        if (xml != null) {
            String tag = manifestTag(xml);
            put(o, "pkg", manifestAttr(tag, "package"));
            String vn = manifestAttr(tag, "android:versionName");
            // a version name that is a reference into the resources cannot be read without them
            put(o, "versionName", vn.startsWith("@") ? "" : vn);
            long code = parseNumber(manifestAttr(tag, "android:versionCode"));
            long major = parseNumber(manifestAttr(tag, "android:versionCodeMajor"));
            put(o, "versionCode", major > 0 ? (major << 32) | (code & 0xFFFFFFFFL) : code);
        }
        if (str(o, "pkg").isEmpty()) return fail(o, "the manifest does not say which package this is");
        put(o, "ok", Boolean.TRUE);
        return o;
    }

    private static JSONObject fail(JSONObject o, String why) {
        put(o, "ok", Boolean.FALSE);
        put(o, "error", why);
        return o;
    }

    private static String firstNonEmpty(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    private static long firstLong(long a, long b) {
        return a != 0 ? a : b;
    }

    private static long parseNumber(String s) {
        String t = s == null ? "" : s.trim();
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) return Long.parseLong(t.substring(2), 16);
            return t.isEmpty() ? 0 : Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String manifestTag(String xml) {
        int a = xml.indexOf("<manifest");
        if (a < 0) return "";
        int b = xml.indexOf('>', a);
        return b < 0 ? xml.substring(a) : xml.substring(a, b);
    }

    private static String manifestAttr(String tag, String name) {
        Matcher m = Pattern.compile("(?<![\\w:.-])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? unescape(m.group(1)) : "";
    }

    /** The decoded text of the manifest in an APK entry of the archive, or null when it cannot be read. */
    private static String readManifest(ZipTool.Archive a, ZipTool.Entry e) {
        try {
            if (e.name.equals("AndroidManifest.xml")) {
                ZipTool.Head h = ZipTool.readHead(a, e, (int) MAX_BUNDLE_MANIFEST);
                if (h.truncated) return null;
                return ManifestDecoder.decodeBytes(h.data, null);
            }
            byte[] data = innerManifest(a, e);
            return data == null ? null : ManifestDecoder.decodeBytes(data, null);
        } catch (IOException x) {
            return null;
        } catch (RuntimeException x) {
            return null;
        }
    }

    /** The AndroidManifest.xml bytes of an APK that sits inside the archive: streamed when it can be, else the APK is unpacked to a temp file. */
    private static byte[] innerManifest(ZipTool.Archive a, ZipTool.Entry e) throws IOException {
        InputStream in = a.open(e);
        try {
            ZipInputStream zin = new ZipInputStream(in);
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.getName().equals("AndroidManifest.xml")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > MAX_BUNDLE_MANIFEST) return null;
                    }
                    return bo.toByteArray();
                }
            }
            return null;
        } catch (java.util.zip.ZipException x) {
            return innerManifestViaFile(a, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] innerManifestViaFile(ZipTool.Archive a, ZipTool.Entry e) throws IOException {
        File tmp = File.createTempFile("morphe-inner", ".apk");
        try {
            ZipTool.extractTo(a, e, tmp);
            ZipTool.Archive inner = ZipTool.open(tmp);
            ZipTool.Entry m = inner.find("AndroidManifest.xml");
            if (m == null) return null;
            ZipTool.Head h = ZipTool.readHead(inner, m, (int) MAX_BUNDLE_MANIFEST);
            return h.truncated ? null : h.data;
        } finally {
            tmp.delete();
        }
    }

    private static JSONObject bundleMeta(ZipTool.Archive a) {
        for (String name : new String[] {"manifest.json", "info.json"}) {
            ZipTool.Entry e = a.find(name);
            if (e == null || e.size > 1024 * 1024) continue;
            try {
                ZipTool.Head h = ZipTool.readHead(a, e, 1024 * 1024);
                return new JSONObject(new String(h.data, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException x) {
                continue;
            } catch (JSONException x) {
                continue;
            }
        }
        return null;
    }

    private static String bundleFormat(ZipTool.Archive a, String hint) {
        if (a.find("manifest.json") != null) {
            JSONObject m = bundleMeta(a);
            if (m != null && (m.has("xapk_version") || m.has("split_apks") || m.has("package_name"))) return "xapk";
        }
        if (a.find("info.json") != null) return "apkm";
        if (a.find("toc.pb") != null) return "apks";
        if (hint.equals("xapk") || hint.equals("apkm") || hint.equals("apks")) return hint;
        return "apks";
    }

    /** The base APK of a bundle: named base, or the one the bundle's manifest.json calls base, or the one whose manifest has no "split" attribute. */
    private static ZipTool.Entry pickBase(ZipTool.Archive a, List<ZipTool.Entry> apks) {
        ZipTool.Entry best = null;
        for (ZipTool.Entry e : apks) {
            String b = e.baseName().toLowerCase(Locale.ROOT);
            if (b.equals("base.apk") || b.equals("base-master.apk")) {
                if (best == null || e.name.length() < best.name.length()) best = e;
            }
        }
        if (best != null) return best;
        JSONObject meta = bundleMeta(a);
        JSONArray sa = meta == null ? null : arr(meta, "split_apks");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject s = sa.optJSONObject(i);
            if (s != null && "base".equals(str(s, "id"))) {
                ZipTool.Entry e = a.find(str(s, "file"));
                if (e != null && !e.dir) return e;
            }
        }
        List<ZipTool.Entry> bySize = new ArrayList<ZipTool.Entry>(apks);
        Collections.sort(bySize, new Comparator<ZipTool.Entry>() {
            @Override
            public int compare(ZipTool.Entry x, ZipTool.Entry y) {
                return x.size == y.size ? 0 : x.size < y.size ? 1 : -1;
            }
        });
        int look = 0;
        for (ZipTool.Entry e : bySize) {
            if (look++ >= 8) break;
            String xml = readManifest(a, e);
            if (xml == null) continue;
            String[] sp = SplitInfo.parse(xml);
            if (sp != null && sp[0] == null) return e;
        }
        return bySize.isEmpty() ? null : bySize.get(0);
    }
}
