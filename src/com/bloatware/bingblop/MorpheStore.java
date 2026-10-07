package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.SSLException;

/**
 * The patch sources of the Morphe Patcher tab. A source is a patch bundle (a .mpp file: a jar with classes.dex and a manifest that names
 * and versions it) that comes from a GitHub or GitLab repository, from a direct metadata (.json) address or from a file on the phone.
 * This class keeps the list of them, resolves a remote one to its newest bundle the way the Morphe Manager does (patches-bundle.json on
 * the main / dev branch, else the newest release that carries a .mpp), downloads and checks the bundle, and fetches the community patch
 * finder's data. Everything lives under one folder: sources.json, bundles/&lt;id&gt;/bundle.mpp, community.json.
 *
 * Pure Java + org.json (no Android classes), all network access through {@link MorpheNet}, so it is unit-testable off-device against a local
 * server. The registry calls are safe from any thread; a network call never holds the registry's lock, so list() answers while a bundle
 * downloads, and changes to the sources (add, update) wait for each other.
 */
public final class MorpheStore {
    public static final String COMMUNITY_URL = "https://morphe-patches.software/data/bundles.json";
    /** The version of the patch engine in this app; a bundle that asks for a newer one is flagged (needsNewerPatcher). */
    public static final String ENGINE_PATCHER_VERSION = "1.15.1";
    public static final String BUILTIN_ID = "morphe-official";

    /** Tests only: when set, these replace the community address, https://raw.githubusercontent.com, https://gitlab.com and https://api.github.com. */
    public static volatile String communityUrlOverride;
    public static volatile String rawBaseOverride;
    public static volatile String gitlabBaseOverride;
    public static volatile String githubApiOverride;

    private static final long COMMUNITY_TTL_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_NAME = 80;
    private static final int MAX_TEXT = 100000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern ID_OK = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA_OK = Pattern.compile("[0-9a-f]{64}");

    private static final String BAD_URL = "Invalid source URL: the address must point to a GitHub or GitLab repository, or to a .json metadata file";
    private static final String ONLY_HTTPS = "Invalid source URL: only https:// addresses are allowed";

    private static final Map<String, String> JSON_HEADERS = new HashMap<String, String>();
    private static final Map<String, String> GITHUB_HEADERS = new HashMap<String, String>();
    static {
        JSON_HEADERS.put("Accept", "application/json");
        JSON_HEADERS.put("Cache-Control", "no-cache");
        GITHUB_HEADERS.put("Accept", "application/vnd.github+json");
        GITHUB_HEADERS.put("X-GitHub-Api-Version", "2022-11-28");
    }

    // ---------------------------------------------------------------------------------------------
    // JSON helpers. org.json differs between Android (checked JSONException, "null" from optString of a
    // JSON null) and the plain library the tests may use, so every read and write goes through these.
    // ---------------------------------------------------------------------------------------------

    private static JSONObject put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (Exception e) { throw new IllegalStateException(e); }
        return o;
    }

    static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return s == null ? "" : s;
    }

    private static JSONObject parseObject(String text) {
        try { return new JSONObject(text); }
        catch (Exception e) { throw new IllegalArgumentException("The text is not valid JSON (" + brief(e.getMessage()) + ")", e); }
    }

    private static String pretty(JSONObject o) {
        try { return o.toString(2); } catch (Exception e) { return o.toString(); }
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** A display name: control characters and runs of spaces folded, trimmed, at most 80 characters. */
    private static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.getType(c) == Character.FORMAT) continue;      // zero-width and right-to-left override characters
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        String t = b.toString().replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_NAME) t = t.substring(0, MAX_NAME).trim();
        return t;
    }

    // ---------------------------------------------------------------------------------------------
    // Pure helpers: the address a person types, versions, the manifest, the metadata file
    // ---------------------------------------------------------------------------------------------

    private static JSONObject fail(String message) {
        return put(put(new JSONObject(), "ok", false), "error", message);
    }

    private static boolean goodName(String s) {
        return s != null && NAME_OK.matcher(s).matches() && !s.replace(".", "").isEmpty();
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    /** https for any host, http only for the loopback address (what MorpheNet allows). */
    static boolean isAllowedUrl(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return false;
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("https") || (scheme.equals("http") && isLoopback(host.toLowerCase(Locale.ROOT)));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean portFollows(String s, int colon) {
        int end = colon + 1;
        while (end < s.length() && "/?#".indexOf(s.charAt(end)) < 0) end++;
        if (end == colon + 1) return false;
        for (int i = colon + 1; i < end; i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<String>();
        if (path == null) return out;
        for (String p : path.split("/")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (rawQuery == null) return m;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                throw new IllegalArgumentException("bad query");
            }
        }
        return m;
    }

    private static String stripGit(String repo) {
        return repo.toLowerCase(Locale.ROOT).endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }

    private static JSONObject repoResult(String host, String owner, String repo, String branch, String name) {
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "host", host);
        put(o, "owner", owner);
        put(o, "repo", repo);
        put(o, "slug", owner + "/" + repo);
        put(o, "url", ("github".equals(host) ? "https://github.com/" : "https://gitlab.com/") + owner + "/" + repo);
        if (branch != null && !branch.isEmpty()) put(o, "branch", branch);
        if (name != null && !name.isEmpty()) put(o, "name", name);
        return o;
    }

    private static JSONObject githubResult(List<String> parts, String branch, String name) {
        if (parts.size() < 2) return fail("Invalid source URL: a GitHub address needs an owner and a repository (github.com/owner/repo)");
        String owner = parts.get(0), repo = stripGit(parts.get(1));
        if (!goodName(owner) || !goodName(repo)) return fail("Invalid source URL: the owner and the repository may only use letters, digits, '.', '-' and '_'");
        if (branch != null && !goodName(branch)) return fail("Invalid source URL: the branch name has characters that are not allowed");
        return repoResult("github", owner, repo, branch, name);
    }

    /** GitLab keeps a project in a group, possibly in subgroups: owner is the whole group path, repo the project. */
    private static JSONObject gitlabResult(List<String> path, String branch, String name) {
        if (path.size() < 2 || path.size() > 8) return fail("Invalid source URL: a GitLab address needs a group and a project (gitlab.com/owner/repo)");
        StringBuilder owner = new StringBuilder();
        for (int i = 0; i < path.size() - 1; i++) {
            if (!goodName(path.get(i))) return fail("Invalid source URL: the group and the project may only use letters, digits, '.', '-' and '_'");
            if (owner.length() > 0) owner.append('/');
            owner.append(path.get(i));
        }
        String repo = stripGit(path.get(path.size() - 1));
        if (!goodName(repo)) return fail("Invalid source URL: the group and the project may only use letters, digits, '.', '-' and '_'");
        if (branch != null && !goodName(branch)) return fail("Invalid source URL: the branch name has characters that are not allowed");
        return repoResult("gitlab", owner.toString(), repo, branch, name);
    }

    /**
     * What a typed or pasted address means: {ok:true, host:"github|gitlab|url", owner, repo, slug, url, branch?, metadataUrl (host url),
     * name? (the &name= of a deep link)} or {ok:false, error}. Accepts github.com/owner/repo and gitlab.com/owner/repo (with or without
     * https://, .git, a trailing slash, /tree/branch, /releases/...), a patches-bundle.json address, any https address that ends in .json,
     * and the https://morphe.software/add-source?github=owner/repo[&name=X] deep links (also ?gitlab=). Anything else, and everything
     * that is not https (the loopback address aside, for local servers), is refused.
     */
    public static JSONObject parseInput(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return fail("Invalid source URL: the address is empty");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f) return fail("Invalid source URL: the address must not contain spaces or control characters");
        }
        String url;
        int sep = s.indexOf("://");
        if (sep >= 0 && s.substring(0, sep).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            String scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return fail(ONLY_HTTPS);
            url = s;
        } else {
            int colon = s.indexOf(':'), slash = s.indexOf('/');
            // "javascript:...", "data:...", "mailto:..." name a scheme; "example.com:8443/x.json" names a port
            if (colon >= 0 && (slash < 0 || colon < slash) && !portFollows(s, colon)) return fail(ONLY_HTTPS);
            url = "https://" + s;
        }
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { return fail(BAD_URL); }
        String host = u.getHost();
        if (host == null || u.getUserInfo() != null) return fail(BAD_URL);
        host = host.toLowerCase(Locale.ROOT);
        boolean http = "http".equalsIgnoreCase(u.getScheme());
        if (http && !isLoopback(host)) return fail(ONLY_HTTPS);
        String bare = host.startsWith("www.") ? host.substring(4) : host;

        List<String> segs = segments(u.getRawPath());
        for (String seg : segs) if (seg.equals("..") || seg.equals(".")) return fail(BAD_URL);
        boolean knownHost = bare.equals("github.com") || bare.equals("gitlab.com") || bare.equals("morphe.software");
        if (knownHost && u.getPort() != -1 && u.getPort() != 443) return fail(BAD_URL);

        try {
            if (bare.equals("github.com")) {
                String branch = null;
                if (segs.size() >= 4 && (segs.get(2).equals("tree") || segs.get(2).equals("blob") || segs.get(2).equals("raw"))) branch = segs.get(3);
                return githubResult(segs, branch, null);
            }
            if (bare.equals("gitlab.com")) {
                int dash = segs.indexOf("-");
                List<String> path = dash >= 0 ? segs.subList(0, dash) : segs;
                String branch = null;
                if (dash >= 0) {
                    List<String> after = segs.subList(dash + 1, segs.size());
                    if (after.size() >= 2 && (after.get(0).equals("tree") || after.get(0).equals("blob") || after.get(0).equals("raw"))) branch = after.get(1);
                    // a raw link to a metadata file with another name is a metadata address of its own
                    if (after.size() >= 3 && after.get(0).equals("raw") && after.get(after.size() - 1).toLowerCase(Locale.ROOT).endsWith(".json")
                            && !(after.size() == 3 && after.get(2).equals("patches-bundle.json"))) {
                        return urlResult(u, host);
                    }
                }
                return gitlabResult(path, branch, null);
            }
            if (bare.equals("raw.githubusercontent.com") && segs.size() == 4 && segs.get(3).equals("patches-bundle.json")) {
                return githubResult(segs.subList(0, 2), segs.get(2), null);
            }
            if (bare.equals("morphe.software") && !segs.isEmpty() && segs.get(0).equals("add-source")) {
                Map<String, String> q = queryParams(u.getRawQuery());
                String gh = q.containsKey("github") ? q.get("github").trim() : "";
                String gl = q.containsKey("gitlab") ? q.get("gitlab").trim() : "";
                if (gh.isEmpty() == gl.isEmpty()) {
                    return fail("Invalid source link: it must name one GitHub or GitLab repository (?github=owner/repo or ?gitlab=owner/repo)");
                }
                String name = cleanName(q.get("name"));
                List<String> parts = segments(gh.isEmpty() ? gl : gh);
                if (!gh.isEmpty() && parts.size() != 2) return fail("Invalid source link: a GitHub repository is written owner/repo");
                return gh.isEmpty() ? gitlabResult(parts, null, name) : githubResult(parts, null, name);
            }
        } catch (IllegalArgumentException e) {
            return fail(BAD_URL);
        }
        // everything else must be a .json metadata file
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".json")) return fail(BAD_URL);
        return urlResult(u, host);
    }

    private static JSONObject urlResult(URI u, String host) {
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String meta = (http(u) ? "http" : "https") + "://" + host + (u.getPort() != -1 ? ":" + u.getPort() : "") + path
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "host", "url");
        put(o, "metadataUrl", meta);
        put(o, "url", meta);
        return o;
    }

    private static boolean http(URI u) { return "http".equalsIgnoreCase(u.getScheme()); }

    /**
     * Compares two versions like semver: an optional leading v, numeric parts compared as numbers (1.46.0 &gt; 1.9.0), a pre-release
     * suffix (-dev.3, -rc1, -beta) sorts before the same release, build metadata (+x) is ignored. Text that is not a version counts as
     * zero parts; never throws. Returns a negative number, 0 or a positive number.
     */
    public static int compareVersions(String a, String b) {
        try {
            return cmpVer(a == null ? "" : a, b == null ? "" : b);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String[] splitVersion(String v) {
        v = v.trim();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) v = v.substring(1);
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        return new String[] { dash < 0 ? v : v.substring(0, dash), dash < 0 ? "" : v.substring(dash + 1) };
    }

    private static int cmpVer(String a, String b) {
        String[] pa = splitVersion(a), pb = splitVersion(b);
        String[] ca = pa[0].split("\\."), cb = pb[0].split("\\.");
        int n = Math.max(ca.length, cb.length);
        for (int i = 0; i < n; i++) {
            int c = cmpDigits(leadingDigits(i < ca.length ? ca[i] : ""), leadingDigits(i < cb.length ? cb[i] : ""));
            if (c != 0) return c;
        }
        if (pa[1].isEmpty() && pb[1].isEmpty()) return 0;
        if (pa[1].isEmpty()) return 1;      // a release is newer than its own pre-release
        if (pb[1].isEmpty()) return -1;
        return cmpPre(pa[1], pb[1]);
    }

    private static String leadingDigits(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return s.substring(0, i);
    }

    private static int cmpDigits(String x, String y) {
        x = x.replaceFirst("^0+", "");
        y = y.replaceFirst("^0+", "");
        if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1;
        int c = x.compareTo(y);
        return c < 0 ? -1 : (c > 0 ? 1 : 0);
    }

    /** Pre-release identifiers (dev.3, rc1, beta) compared piece by piece, digits as numbers, so rc10 follows rc9 and dev.10 follows dev.3. */
    private static int cmpPre(String a, String b) {
        String[] xa = a.split("\\."), xb = b.split("\\.");
        for (int i = 0; i < Math.max(xa.length, xb.length); i++) {
            if (i >= xa.length) return -1;
            if (i >= xb.length) return 1;
            int c = cmpIdent(xa[i], xb[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static List<String> chunks(String s) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < s.length()) {
            boolean d = Character.isDigit(s.charAt(i));
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j)) == d) j++;
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    private static int cmpIdent(String a, String b) {
        List<String> ca = chunks(a), cb = chunks(b);
        for (int i = 0; i < Math.max(ca.size(), cb.size()); i++) {
            if (i >= ca.size()) return -1;
            if (i >= cb.size()) return 1;
            String x = ca.get(i), y = cb.get(i);
            boolean dx = Character.isDigit(x.charAt(0)), dy = Character.isDigit(y.charAt(0));
            int c;
            if (dx && dy) c = cmpDigits(x, y);
            else if (dx != dy) c = dx ? -1 : 1;            // numbers sort before words
            else c = Integer.signum(x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT)));
            if (c != 0) return c;
        }
        return 0;
    }

    private static boolean isPre(String version) {
        return splitVersion(version == null ? "" : version)[1].length() > 0;
    }

    /** "v1.2.3" and "1.2.3" are the same version; shown without the v. */
    private static String cleanVersion(String v) {
        v = v == null ? "" : v.trim();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) v = v.substring(1);
        return cap(v, 100);
    }

    /**
     * The attributes of a bundle's META-INF/MANIFEST.MF as {name, version, description, source, author, contact, website, license,
     * patcherVersion} (missing or "na" = ""), plus {hasDex, hasClasses, entries}. Null when the file is not a zip / jar.
     */
    public static JSONObject readManifest(File mpp) {
        if (mpp == null || !mpp.isFile()) return null;
        ZipFile zip = null;
        try {
            zip = new ZipFile(mpp);
            boolean dex = false, classes = false;
            ZipEntry manifest = null;
            int count = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                count++;
                if (e.isDirectory()) continue;
                String low = e.getName().toLowerCase(Locale.ROOT);
                if (low.endsWith(".dex")) dex = true;
                else if (low.endsWith(".class")) classes = true;
                else if (manifest == null && low.equals("meta-inf/manifest.mf")) manifest = e;
            }
            Map<String, String> attrs = new HashMap<String, String>();
            if (manifest != null) {
                InputStream in = zip.getInputStream(manifest);
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && bos.size() < 256 * 1024) bos.write(buf, 0, n);
                    attrs = parseManifestMain(new String(bos.toByteArray(), UTF8));
                } finally { try { in.close(); } catch (IOException ignored) {} }
            }
            JSONObject o = new JSONObject();
            put(o, "name", attr(attrs, "name"));
            put(o, "version", attr(attrs, "version"));
            put(o, "description", attr(attrs, "description"));
            put(o, "source", attr(attrs, "source"));
            put(o, "author", attr(attrs, "author"));
            put(o, "contact", attr(attrs, "contact"));
            put(o, "website", attr(attrs, "website"));
            put(o, "license", attr(attrs, "license"));
            put(o, "patcherVersion", attr(attrs, "patcher-version"));
            put(o, "hasDex", dex);
            put(o, "hasClasses", classes);
            put(o, "entries", count);
            return o;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) {}
        }
    }

    private static String attr(Map<String, String> m, String key) {
        String v = m.get(key);
        return v == null ? "" : v;
    }

    /** The main section of a manifest: "Key: value" lines, a line that starts with a space continues the one before, a blank line ends it. */
    static Map<String, String> parseManifestMain(String text) {
        Map<String, String> m = new HashMap<String, String>();
        String key = null;
        StringBuilder val = null;
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) break;
            if (line.charAt(0) == ' ') {
                if (key != null) val.append(line, 1, line.length());
                continue;
            }
            storeAttr(m, key, val);
            int c = line.indexOf(':');
            if (c <= 0) { key = null; val = null; continue; }
            key = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            val = new StringBuilder(line.substring(c + 1));
        }
        storeAttr(m, key, val);
        return m;
    }

    private static void storeAttr(Map<String, String> m, String key, StringBuilder val) {
        if (key == null || val == null || m.containsKey(key)) return;
        String v = val.toString().trim();
        if (v.equalsIgnoreCase("na")) v = "";
        m.put(key, cap(v, MAX_TEXT));
    }

    /**
     * Reads a source's patches-bundle.json: {version, downloadUrl, createdAt, description, signatureUrl, sha256}. Throws
     * IllegalArgumentException with a plain message when it is not JSON, has no version or no download_url, or when the download_url is
     * not an https address (or when a sha256 is given but is not a 64-digit hex value).
     */
    public static JSONObject parseBundleJson(String text) {
        JSONObject j = parseObject(text);
        String version = str(j, "version").trim();
        if (version.isEmpty()) throw new IllegalArgumentException("The metadata has no version");
        String dl = str(j, "download_url").trim();
        if (dl.isEmpty()) throw new IllegalArgumentException("The metadata has no download_url");
        if (!isAllowedUrl(dl)) throw new IllegalArgumentException("The download_url must be an https address");
        String sig = str(j, "signature_download_url").trim();
        if (!sig.isEmpty() && !isAllowedUrl(sig)) sig = "";
        String sha = str(j, "sha256").trim().toLowerCase(Locale.ROOT);
        if (sha.startsWith("sha256:")) sha = sha.substring(7);
        if (!sha.isEmpty() && !SHA_OK.matcher(sha).matches()) throw new IllegalArgumentException("The sha256 in the metadata is not a 64-digit hex value");
        JSONObject o = new JSONObject();
        put(o, "version", version);
        put(o, "downloadUrl", dl);
        put(o, "createdAt", cap(str(j, "created_at").trim(), 64));
        put(o, "description", cap(str(j, "description"), MAX_TEXT));
        put(o, "signatureUrl", sig);
        put(o, "sha256", sha);
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // The registry
    // ---------------------------------------------------------------------------------------------

    /** One source as it is kept in sources.json. What depends on the file (file, size, meta, needsNewerPatcher) is worked out when it is read. */
    private static final class Rec {
        String id = "", name = "", kind = "remote", host = "", repo = "", url = "", branch = "", version = "", description = "", createdAt = "", error = "";
        long updatedAt;
        int patchCount = -1;
        boolean enabled = true, builtIn, prerelease;
        JSONObject meta;

        Rec copy() {
            Rec c = new Rec();
            c.id = id; c.name = name; c.kind = kind; c.host = host; c.repo = repo; c.url = url; c.branch = branch; c.version = version;
            c.description = description; c.createdAt = createdAt; c.error = error; c.updatedAt = updatedAt; c.patchCount = patchCount;
            c.enabled = enabled; c.builtIn = builtIn; c.prerelease = prerelease; c.meta = meta;
            return c;
        }

        boolean remote() { return "remote".equals(kind); }

        /** What makes two sources the same one: the repository (host + owner + repo, lowercased) or the metadata address. Local files have none. */
        String key() {
            if (!remote()) return null;
            return "url".equals(host) ? "url:" + normUrl(url) : host + ":" + repo.toLowerCase(Locale.ROOT);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            put(o, "id", id);
            put(o, "name", name);
            put(o, "kind", kind);
            put(o, "host", host);
            put(o, "repo", repo);
            put(o, "url", url);
            put(o, "branch", branch);
            put(o, "version", version);
            put(o, "description", description);
            put(o, "createdAt", createdAt);
            put(o, "updatedAt", updatedAt);
            put(o, "patchCount", patchCount);
            put(o, "enabled", enabled);
            put(o, "builtIn", builtIn);
            put(o, "prerelease", prerelease);
            put(o, "error", error);
            if (meta != null) put(o, "meta", meta);
            return o;
        }

        /** A record from sources.json; anything that does not look like one the app wrote is refused (IllegalArgumentException). */
        static Rec fromJson(JSONObject o) {
            if (o == null) throw new IllegalArgumentException("not an object");
            Rec r = new Rec();
            r.id = str(o, "id");
            if (!ID_OK.matcher(r.id).matches()) throw new IllegalArgumentException("bad id");
            r.kind = str(o, "kind");
            if (!r.kind.equals("remote") && !r.kind.equals("local")) throw new IllegalArgumentException("bad kind");
            if (r.remote()) {
                r.host = str(o, "host");
                r.repo = str(o, "repo");
                r.url = str(o, "url");
                if (r.host.equals("github") || r.host.equals("gitlab")) {
                    if (!validRepo(r.host, r.repo)) throw new IllegalArgumentException("bad repo");
                    r.url = (r.host.equals("github") ? "https://github.com/" : "https://gitlab.com/") + r.repo;
                } else if (r.host.equals("url")) {
                    r.repo = "";
                    if (!isAllowedUrl(r.url)) throw new IllegalArgumentException("bad url");
                } else {
                    throw new IllegalArgumentException("bad host");
                }
                r.branch = str(o, "branch");
                if (!r.branch.isEmpty() && !goodName(r.branch)) r.branch = "";
            }
            r.name = cleanName(str(o, "name"));
            if (r.name.isEmpty()) r.name = r.id;
            r.version = cap(str(o, "version"), 100);
            r.description = cap(str(o, "description"), MAX_TEXT);
            r.createdAt = cap(str(o, "createdAt"), 64);
            r.error = cap(str(o, "error"), 500);
            r.updatedAt = Math.max(0, o.optLong("updatedAt", 0));
            r.patchCount = Math.max(-1, o.optInt("patchCount", -1));
            r.enabled = o.optBoolean("enabled", true);
            r.prerelease = o.optBoolean("prerelease", false);
            r.builtIn = BUILTIN_ID.equals(r.id);
            r.meta = o.optJSONObject("meta");
            return r;
        }
    }

    private static boolean validRepo(String host, String repo) {
        String[] parts = repo.split("/", -1);
        if (host.equals("github") ? parts.length != 2 : (parts.length < 2 || parts.length > 8)) return false;
        for (String p : parts) if (!goodName(p)) return false;
        return true;
    }

    private static String normUrl(String url) {
        try {
            URI u = new URI(url);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() != -1 ? ":" + u.getPort() : "")
                    + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        } catch (Exception e) {
            return url.toLowerCase(Locale.ROOT);
        }
    }

    private static Rec builtin() {
        Rec r = new Rec();
        r.id = BUILTIN_ID;
        r.name = "Morphe Patches";
        r.kind = "remote";
        r.host = "github";
        r.repo = "MorpheApp/morphe-patches";
        r.url = "https://github.com/MorpheApp/morphe-patches";
        r.builtIn = true;
        return r;
    }

    private final File baseDir, bundlesDir, sourcesFile, communityFile;
    private final List<Rec> recs = new ArrayList<Rec>();
    /** Taken for the whole of an add or an update (so two of them never write the same files); the registry's own monitor is never held across the network. */
    private final Object opLock = new Object();
    private final Object communityLock = new Object();     // the cache file
    private final Object communityFetch = new Object();    // one download of the list at a time
    private final SecureRandom random = new SecureRandom();

    public MorpheStore(File baseDir) {
        this.baseDir = baseDir.getAbsoluteFile();
        this.bundlesDir = new File(this.baseDir, "bundles");
        this.sourcesFile = new File(this.baseDir, "sources.json");
        this.communityFile = new File(this.baseDir, "community.json");
        this.baseDir.mkdirs();
        synchronized (this) { load(); }
        tidyBundleFolders();
    }

    /** Reads sources.json. A damaged file is kept as sources.json.bak and the list starts again from the built-in source. */
    private void load() {
        List<Rec> loaded = new ArrayList<Rec>();
        boolean existed = sourcesFile.isFile(), damaged = false;
        if (existed) {
            try {
                JSONObject root = parseObject(new String(Files.readAllBytes(sourcesFile.toPath()), UTF8));
                JSONArray arr = root.optJSONArray("sources");
                if (arr == null) throw new IllegalArgumentException("no list of sources");
                Set<String> seen = new HashSet<String>();
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        Rec r = Rec.fromJson(arr.optJSONObject(i));
                        if (seen.add(r.id)) loaded.add(r); else damaged = true;
                    } catch (RuntimeException e) {
                        damaged = true;
                    }
                }
            } catch (IOException e) {
                damaged = true;
                loaded.clear();
            } catch (RuntimeException e) {
                damaged = true;
                loaded.clear();
            }
            if (damaged) {
                try { Files.copy(sourcesFile.toPath(), new File(baseDir, "sources.json.bak").toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) { /* the list is rebuilt anyway */ }
            }
        }
        Rec first = null;
        for (Rec r : loaded) if (BUILTIN_ID.equals(r.id)) first = r;
        if (first == null) {
            first = builtin();
        } else {
            loaded.remove(first);
            Rec fixed = builtin();     // what the built-in source is cannot be changed from the file
            fixed.enabled = first.enabled; fixed.prerelease = first.prerelease; fixed.version = first.version; fixed.description = first.description;
            fixed.createdAt = first.createdAt; fixed.updatedAt = first.updatedAt; fixed.patchCount = first.patchCount; fixed.error = first.error;
            fixed.meta = first.meta; fixed.branch = first.branch;
            first = fixed;
        }
        loaded.add(0, first);
        recs.addAll(loaded);
        if (!existed || damaged) {
            try { save(); } catch (IOException ignored) { /* tried again at the next change */ }
        }
    }

    /** Leftovers of a download that was cut off by a crash: the staging files, and folders that hold nothing. */
    private void tidyBundleFolders() {
        File[] dirs = bundlesDir.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            deleteQuiet(new File(d, "bundle.mpp.new"));
            deleteQuiet(new File(d, "bundle.mpp.new.part"));
            deleteQuiet(new File(d, "bundle.mpp.part"));
            String[] left = d.list();
            if (left != null && left.length == 0) deleteQuiet(d);
        }
    }

    private void save() throws IOException {
        JSONArray arr = new JSONArray();
        for (Rec r : recs) arr.put(r.toJson());
        JSONObject root = new JSONObject();
        put(root, "version", 1);
        put(root, "sources", arr);
        writeAtomic(sourcesFile, pretty(root).getBytes(UTF8));
    }

    private Rec find(String id) {
        if (id == null) return null;
        for (Rec r : recs) if (r.id.equals(id)) return r;
        return null;
    }

    private File dirOf(String id) throws IOException {
        if (id == null || !ID_OK.matcher(id).matches()) throw new IOException("Invalid source id");
        return new File(bundlesDir, id);
    }

    private File bundlePath(String id) throws IOException {
        return new File(dirOf(id), "bundle.mpp");
    }

    private String isoNow() {
        return Instant.ofEpochSecond(System.currentTimeMillis() / 1000).toString();
    }

    /** One source as the page reads it. */
    private JSONObject view(Rec r) {
        File f = null;
        try {
            File p = bundlePath(r.id);
            if (p.isFile()) f = p;
        } catch (IOException ignored) { /* an id that is not one: no file */ }
        if (f != null && r.meta == null) r.meta = readManifest(f);
        JSONObject meta = f != null ? r.meta : null;
        String installed = "";
        if (f != null) {
            installed = meta != null ? str(meta, "version") : "";
            if (installed.isEmpty()) installed = r.version;
        }
        String patcher = meta != null ? str(meta, "patcherVersion") : "";
        JSONObject o = new JSONObject();
        put(o, "id", r.id);
        put(o, "name", r.name);
        put(o, "kind", r.kind);
        put(o, "host", r.host);
        put(o, "repo", r.repo);
        put(o, "url", r.url);
        put(o, "branch", r.branch);
        put(o, "version", installed);
        put(o, "description", r.description);
        put(o, "createdAt", r.createdAt);
        put(o, "updatedAt", r.updatedAt);
        put(o, "patchCount", r.patchCount);
        put(o, "file", f != null ? f.getAbsolutePath() : "");
        put(o, "size", f != null ? f.length() : 0L);
        put(o, "enabled", r.enabled);
        put(o, "builtIn", r.builtIn);
        put(o, "prerelease", r.prerelease);
        put(o, "meta", meta != null ? copyOf(meta) : JSONObject.NULL);
        put(o, "error", r.error);
        put(o, "needsNewerPatcher", !patcher.isEmpty() && compareVersions(patcher, ENGINE_PATCHER_VERSION) > 0);
        return o;
    }

    private static JSONObject copyOf(JSONObject o) {
        JSONObject c = new JSONObject();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            put(c, k, o.opt(k));
        }
        return c;
    }

    /** The sources in order, the built-in one first. */
    public synchronized JSONArray list() {
        JSONArray a = new JSONArray();
        for (Rec r : recs) a.put(view(r));
        return a;
    }

    /** One source (see list()), or null when there is none with this id. */
    public synchronized JSONObject get(String id) {
        Rec r = find(id);
        return r == null ? null : view(r);
    }

    /** The bundle.mpp of a source, or null when it has not been downloaded. */
    public synchronized File bundleFile(String id) {
        if (find(id) == null) return null;
        try {
            File f = bundlePath(id);
            return f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Changes that need no network
    // ---------------------------------------------------------------------------------------------

    /** Removes a source and its downloaded bundle. The pre-installed source stays. */
    public synchronized void remove(String id) throws IOException {
        Rec r = find(id);
        if (r == null) throw new IOException("Unknown source");
        if (r.builtIn) throw new IOException("The pre-installed source cannot be removed");
        deleteTree(dirOf(r.id));
        recs.remove(r);
        save();
    }

    public synchronized void rename(String id, String name) throws IOException {
        Rec r = find(id);
        if (r == null) throw new IOException("Unknown source");
        if (r.builtIn) throw new IOException("The pre-installed source cannot be renamed");
        String n = cleanName(name);
        if (n.isEmpty()) throw new IOException("The name cannot be empty");
        r.name = n;
        save();
    }

    public synchronized void setEnabled(String id, boolean on) {
        Rec r = find(id);
        if (r == null || r.enabled == on) return;
        r.enabled = on;
        saveOrThrow();
    }

    public synchronized void setPrerelease(String id, boolean on) {
        Rec r = find(id);
        if (r == null || r.prerelease == on) return;
        r.prerelease = on;
        saveOrThrow();
    }

    /** The page stores the number of patches it got from the patch engine (-1 = not known). */
    public synchronized void setPatchCount(String id, int n) {
        Rec r = find(id);
        if (r == null) return;
        r.patchCount = Math.max(-1, n);
        saveOrThrow();
    }

    private void saveOrThrow() {
        try { save(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private synchronized void noteError(String id, String message) {
        Rec r = find(id);
        if (r == null) return;
        String m = cap(message == null ? "" : message.replaceAll("\\s+", " ").trim(), 500);
        if (m.equals(r.error)) return;
        r.error = m;
        try { save(); } catch (IOException ignored) { /* only a note */ }
    }

    // ---------------------------------------------------------------------------------------------
    // Adding, checking and updating
    // ---------------------------------------------------------------------------------------------

    /** What a remote source offers right now: the newest bundle and where to get it. */
    private static final class Resolved {
        String version = "", downloadUrl = "", createdAt = "", description = "", signatureUrl = "", sha256 = "";
    }

    /** A downloaded bundle that has passed the checks and waits to be moved into place. */
    private static final class Staged {
        File file;
        JSONObject meta;
    }

    private static Resolved fromMetadata(String text) {
        JSONObject j = parseBundleJson(text);
        Resolved r = new Resolved();
        r.version = cleanVersion(str(j, "version"));
        r.downloadUrl = str(j, "downloadUrl");
        r.createdAt = str(j, "createdAt");
        r.description = str(j, "description");
        r.signatureUrl = str(j, "signatureUrl");
        r.sha256 = str(j, "sha256");
        return r;
    }

    private static String base(String override, String standard) {
        String b = override != null ? override : standard;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String hostOf(String url) {
        try { return new URI(url).getHost(); } catch (Exception e) { return "?"; }
    }

    /** A network failure in words a person can act on. */
    private static String why(Exception e, String url) {
        if (e instanceof MorpheNet.HttpError) {
            int s = ((MorpheNet.HttpError) e).status;
            return e.getMessage() + (s == 403 || s == 429 ? " (the server is limiting requests, try again later)" : "");
        }
        if (e instanceof UnknownHostException) return "no internet connection (cannot find " + hostOf(url) + ")";
        if (e instanceof SocketTimeoutException) return "the connection to " + hostOf(url) + " timed out";
        if (e instanceof ConnectException) return "cannot connect to " + hostOf(url);
        if (e instanceof SSLException) return "the secure connection to " + hostOf(url) + " failed";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private Resolved resolve(Rec r) throws IOException {
        if ("url".equals(r.host)) return resolveUrl(r);
        if ("gitlab".equals(r.host)) return resolveGitlab(r);
        return resolveGithub(r);
    }

    private Resolved resolveUrl(Rec r) throws IOException {
        try {
            return fromMetadata(MorpheNet.getString(r.url, JSON_HEADERS));
        } catch (IllegalArgumentException e) {
            throw new IOException("The metadata file is not usable: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IOException("The metadata file could not be read: " + why(e, r.url), e);
        }
    }

    /** The branches to look at for patches-bundle.json: the one asked for, else dev (pre-release) or main, then master. */
    private static List<String> branchesFor(Rec r) {
        List<String> l = new ArrayList<String>();
        String first = !r.branch.isEmpty() ? r.branch : (r.prerelease ? "dev" : "main");
        l.add(first);
        if (!first.equals("master")) l.add("master");
        return l;
    }

    private static IOException notFound(Rec r, List<String> branches, String problem) {
        StringBuilder b = new StringBuilder("No patch bundle was found in ").append(r.repo).append(": no patches-bundle.json on ");
        for (int i = 0; i < branches.size(); i++) b.append(i > 0 ? " or " : "").append(branches.get(i));
        b.append(" and no release with a .mpp file. Check that the repository exists and is public.");
        if (problem != null) b.append(" (").append(problem).append(")");
        return new IOException(b.toString());
    }

    private Resolved resolveGithub(Rec r) throws IOException {
        String raw = base(rawBaseOverride, "https://raw.githubusercontent.com");
        List<String> branches = branchesFor(r);
        String problem = null;
        long minute = System.currentTimeMillis() / 60000L;     // the raw host caches for minutes; a new key every minute gets past it
        for (String b : branches) {
            String url = raw + "/" + r.repo + "/" + b + "/patches-bundle.json?t=" + minute;
            try {
                return fromMetadata(MorpheNet.getString(url, JSON_HEADERS));
            } catch (MorpheNet.HttpError e) {
                if (e.status != 404) problem = why(e, url);
            } catch (IllegalArgumentException e) {
                problem = "the patches-bundle.json on " + b + " is not usable: " + e.getMessage();
            } catch (IOException e) {
                throw new IOException("Cannot read " + r.repo + ": " + why(e, url), e);
            }
        }
        String api = base(githubApiOverride, "https://api.github.com") + "/repos/" + r.repo + "/releases?per_page=30";
        try {
            Resolved x = pickGithubRelease(MorpheNet.getString(api, GITHUB_HEADERS), r.prerelease);
            if (x != null) return x;
        } catch (MorpheNet.HttpError e) {
            if (e.status != 404 && problem == null) problem = why(e, api);
        } catch (IllegalArgumentException e) {
            if (problem == null) problem = e.getMessage();
        } catch (IOException e) {
            if (problem == null) problem = "the release list could not be read: " + why(e, api);
        }
        throw notFound(r, branches, problem);
    }

    private Resolved resolveGitlab(Rec r) throws IOException {
        String gl = base(gitlabBaseOverride, "https://gitlab.com");
        List<String> branches = branchesFor(r);
        String problem = null;
        for (String b : branches) {
            String url = gl + "/" + r.repo + "/-/raw/" + b + "/patches-bundle.json";
            try {
                return fromMetadata(MorpheNet.getString(url, JSON_HEADERS));
            } catch (MorpheNet.HttpError e) {
                if (e.status != 404) problem = why(e, url);
            } catch (IllegalArgumentException e) {
                problem = "the patches-bundle.json on " + b + " is not usable: " + e.getMessage();
            } catch (IOException e) {
                throw new IOException("Cannot read " + r.repo + ": " + why(e, url), e);
            }
        }
        String api;
        try {
            api = gl + "/api/v4/projects/" + URLEncoder.encode(r.repo, "UTF-8") + "/releases";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IOException(e);
        }
        try {
            Resolved x = pickGitlabRelease(MorpheNet.getString(api, JSON_HEADERS), r.prerelease);
            if (x != null) return x;
        } catch (MorpheNet.HttpError e) {
            if (e.status != 404 && problem == null) problem = why(e, api);
        } catch (IllegalArgumentException e) {
            if (problem == null) problem = e.getMessage();
        } catch (IOException e) {
            if (problem == null) problem = "the release list could not be read: " + why(e, api);
        }
        throw notFound(r, branches, problem);
    }

    private static JSONArray parseArray(String text) {
        try { return new JSONArray(text); }
        catch (Exception e) { throw new IllegalArgumentException("the release list is not valid JSON"); }
    }

    /** The newest release (the list is newest first) that is not a draft, is not a pre-release unless asked, and carries a .mpp. */
    private static Resolved pickGithubRelease(String text, boolean prerelease) {
        JSONArray arr = parseArray(text);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rel = arr.optJSONObject(i);
            if (rel == null || rel.optBoolean("draft", false)) continue;
            if (!prerelease && rel.optBoolean("prerelease", false)) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                String name = str(a, "name");
                String url = str(a, "browser_download_url");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".mpp") || !isAllowedUrl(url)) continue;
                Resolved r = new Resolved();
                r.downloadUrl = url;
                r.version = cleanVersion(!str(rel, "tag_name").isEmpty() ? str(rel, "tag_name") : str(rel, "name"));
                String body = str(rel, "body");
                r.description = cap(!body.trim().isEmpty() ? body : str(rel, "name"), MAX_TEXT);
                r.createdAt = cap(!str(rel, "published_at").isEmpty() ? str(rel, "published_at") : str(rel, "created_at"), 64);
                String digest = str(a, "digest").toLowerCase(Locale.ROOT);
                if (digest.startsWith("sha256:") && SHA_OK.matcher(digest.substring(7)).matches()) r.sha256 = digest.substring(7);
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject s = assets.optJSONObject(k);
                    String sn = str(s, "name"), su = str(s, "browser_download_url");
                    if ((sn.equals(name + ".sig") || sn.equals(name + ".asc")) && isAllowedUrl(su)) { r.signatureUrl = su; break; }
                }
                return r;
            }
        }
        return null;
    }

    /** GitLab has no pre-release flag: a tag with a suffix (-dev.3, -rc1) counts as one, an upcoming release is not out yet. */
    private static Resolved pickGitlabRelease(String text, boolean prerelease) {
        JSONArray arr = parseArray(text);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rel = arr.optJSONObject(i);
            if (rel == null || rel.optBoolean("upcoming_release", false)) continue;
            String tag = str(rel, "tag_name");
            if (!prerelease && isPre(tag)) continue;
            JSONObject assets = rel.optJSONObject("assets");
            JSONArray links = assets == null ? null : assets.optJSONArray("links");
            if (links == null) continue;
            for (int j = 0; j < links.length(); j++) {
                JSONObject l = links.optJSONObject(j);
                String name = str(l, "name"), url = str(l, "url"), direct = str(l, "direct_asset_url");
                String path = url.toLowerCase(Locale.ROOT);
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".mpp") && !path.endsWith(".mpp")) continue;
                String dl = isAllowedUrl(direct) ? direct : url;
                if (!isAllowedUrl(dl)) continue;
                Resolved r = new Resolved();
                r.downloadUrl = dl;
                r.version = cleanVersion(!tag.isEmpty() ? tag : str(rel, "name"));
                String desc = str(rel, "description");
                r.description = cap(!desc.trim().isEmpty() ? desc : str(rel, "name"), MAX_TEXT);
                r.createdAt = cap(!str(rel, "released_at").isEmpty() ? str(rel, "released_at") : str(rel, "created_at"), 64);
                return r;
            }
        }
        return null;
    }

    /** Whether latest is worth installing over the installed version. Moving a source off pre-releases takes the newest stable one even if its number is lower. */
    private static boolean isNewer(String installed, String latest, boolean prerelease) {
        if (installed.isEmpty()) return true;
        if (compareVersions(latest, installed) > 0) return true;
        return !prerelease && isPre(installed) && !isPre(latest) && !cleanVersion(installed).equals(cleanVersion(latest));
    }

    /** The version of a source's bundle on disk (manifest, else what the metadata said when it was downloaded), "" when there is none. */
    private synchronized String installedVersion(Rec r) {
        return str(view(r), "version");
    }

    /**
     * Adds a source from an address (see parseInput): finds its newest bundle, downloads and checks it, and registers it. Nothing is
     * left behind when any step fails. name may be empty (the deep link's name, the bundle's own name or the repository's name is used).
     */
    public JSONObject addRemote(String input, String name, boolean prerelease, MorpheNet.Progress p) throws IOException {
        JSONObject in = parseInput(input);
        if (!in.optBoolean("ok", false)) throw new IOException(str(in, "error"));
        Rec rec = new Rec();
        rec.kind = "remote";
        rec.host = str(in, "host");
        rec.prerelease = prerelease;
        if ("url".equals(rec.host)) {
            rec.url = str(in, "metadataUrl");
        } else {
            rec.repo = str(in, "slug");
            rec.url = str(in, "url");
            rec.branch = str(in, "branch");
        }
        String wanted = cleanName(name);
        if (wanted.isEmpty()) wanted = str(in, "name");
        synchronized (opLock) {
            synchronized (this) {
                if (hasKey(rec.key())) throw new IOException("This source has already been added");
            }
            Resolved res = resolve(rec);
            synchronized (this) {
                rec.id = uniqueId(baseId(rec));
            }
            Staged st = fetchBundle(rec.id, res, p);
            boolean done = false;
            try {
                String nm = wanted;
                if (nm.isEmpty()) nm = cleanName(str(st.meta, "name"));
                if (nm.isEmpty()) nm = "url".equals(rec.host) ? cleanName(hostOf(rec.url)) : cleanName(rec.repo.substring(rec.repo.lastIndexOf('/') + 1));
                rec.name = nm.isEmpty() ? rec.id : nm;
                synchronized (this) {
                    place(st.file, bundlePath(rec.id));
                    applyDownload(rec, res, st);
                    recs.add(rec);
                    try { save(); } catch (IOException e) { recs.remove(rec); throw e; }
                }
                done = true;
                return get(rec.id);
            } finally {
                if (!done) { deleteQuiet(st.file); deleteTreeQuiet(quiet(rec.id)); }
            }
        }
    }

    private File quiet(String id) {
        try { return dirOf(id); } catch (IOException e) { return null; }
    }

    private boolean hasKey(String key) {
        if (key == null) return false;
        for (Rec r : recs) if (key.equals(r.key())) return true;
        return false;
    }

    private static String baseId(Rec r) {
        if ("url".equals(r.host)) return "url-" + slug(hostOf(r.url)) + "-" + cap(sha256Hex(normUrl(r.url)), 8);
        return ("gitlab".equals(r.host) ? "gl-" : "gh-") + slug(r.repo.replace('/', '-'));
    }

    private static String slug(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != '-') b.append('-');
        }
        while (b.length() > 0 && b.charAt(b.length() - 1) == '-') b.setLength(b.length() - 1);
        return b.length() == 0 ? "x" : cap(b.toString(), 48);
    }

    private String uniqueId(String base) {
        String id = base;
        for (int n = 2; find(id) != null; n++) id = base + "-" + n;
        return id;
    }

    /** What changes in a record when a bundle is installed: the metadata's words, the time, a patch count that has to be read again. */
    private void applyDownload(Rec rec, Resolved res, Staged st) {
        rec.version = res.version;
        rec.description = res.description;
        rec.createdAt = res.createdAt.isEmpty() ? isoNow() : res.createdAt;
        rec.updatedAt = System.currentTimeMillis();
        rec.patchCount = -1;
        rec.meta = st.meta;
        rec.error = "";
    }

    /** Adds a bundle file from the phone. The file name must end in .mpp. */
    public JSONObject addLocal(File mpp, String name) throws IOException {
        if (mpp == null || !mpp.isFile()) throw new IOException("The file does not exist");
        if (!mpp.getName().toLowerCase(Locale.ROOT).endsWith(".mpp")) throw new IOException("Local bundles must have the .mpp extension");
        checkBundle(readManifest(mpp));
        synchronized (opLock) {
            String id;
            synchronized (this) {
                String hex;
                do { hex = randomHex(4); } while (find("local-" + hex) != null);
                id = "local-" + hex;
            }
            File dir = dirOf(id);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File staging = new File(dir, "bundle.mpp.new");
            boolean done = false;
            try {
                Files.copy(mpp.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Staged st = new Staged();
                st.file = staging;
                st.meta = readManifest(staging);
                checkBundle(st.meta);
                Rec rec = new Rec();
                rec.id = id;
                rec.kind = "local";
                String nm = cleanName(name);
                if (nm.isEmpty()) nm = cleanName(str(st.meta, "name"));
                if (nm.isEmpty()) nm = cleanName(mpp.getName().substring(0, mpp.getName().length() - 4));
                rec.name = nm.isEmpty() ? id : nm;
                Resolved res = new Resolved();
                res.version = str(st.meta, "version");
                res.description = str(st.meta, "description");
                res.createdAt = Instant.ofEpochSecond(mpp.lastModified() / 1000).toString();
                synchronized (this) {
                    place(st.file, bundlePath(id));
                    applyDownload(rec, res, st);
                    recs.add(rec);
                    try { save(); } catch (IOException e) { recs.remove(rec); throw e; }
                }
                done = true;
                return get(id);
            } finally {
                if (!done) { deleteQuiet(staging); deleteTreeQuiet(dir); }
            }
        }
    }

    private static void checkBundle(JSONObject meta) throws IOException {
        if (meta == null || !(meta.optBoolean("hasDex", false) || meta.optBoolean("hasClasses", false))) {
            throw new IOException("That file is not a Morphe patch bundle");
        }
    }

    /** Downloads a bundle next to its final place (bundle.mpp.new), checks it, and leaves it there for place() to move in. */
    private Staged fetchBundle(String id, Resolved res, MorpheNet.Progress p) throws IOException {
        if (p != null && p.cancelled()) throw new IOException("cancelled");
        File dir = dirOf(id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File staging = new File(dir, "bundle.mpp.new");
        File part = new File(dir, "bundle.mpp.new.part");
        deleteQuiet(staging);
        deleteQuiet(part);
        boolean ok = false;
        try {
            try {
                MorpheNet.download(res.downloadUrl, staging, null, p);
            } catch (IOException e) {
                if ("cancelled".equals(e.getMessage())) throw e;
                throw new IOException("Download failed: " + why(e, res.downloadUrl), e);
            }
            if (!res.sha256.isEmpty() && !res.sha256.equals(sha256Hex(staging))) {
                throw new IOException("The downloaded file does not match the checksum (sha256) the source publishes");
            }
            Staged st = new Staged();
            st.file = staging;
            st.meta = readManifest(staging);
            checkBundle(st.meta);
            ok = true;
            return st;
        } finally {
            if (!ok) {
                deleteQuiet(staging);
                deleteQuiet(part);
                boolean known;
                synchronized (this) { known = find(id) != null; }
                if (!known) deleteTreeQuiet(dir);
            }
        }
    }

    /** Makes a staged file the bundle: read-only (Android 14 refuses to load a writable dex file) and moved over the old one in one step. */
    private static void place(File staged, File target) throws IOException {
        staged.setReadOnly();
        moveReplace(staged, target);
    }

    /** What a remote source offers against what is installed: {id, current, latest, newer, downloadUrl, description, createdAt}. Downloads nothing. */
    public JSONObject checkUpdate(String id) throws IOException {
        Rec snap;
        String current;
        synchronized (this) {
            Rec r = find(id);
            if (r == null) throw new IOException("Unknown source");
            if (!r.remote()) throw new IOException("A local source never updates on its own, add the new file instead");
            snap = r.copy();
            current = installedVersion(r);
        }
        Resolved res;
        try {
            res = resolve(snap);
        } catch (IOException e) {
            noteError(id, e.getMessage());
            throw e;
        }
        noteError(id, "");
        JSONObject o = new JSONObject();
        put(o, "id", id);
        put(o, "current", current);
        put(o, "latest", res.version);
        put(o, "newer", isNewer(current, res.version, snap.prerelease));
        put(o, "downloadUrl", res.downloadUrl);
        put(o, "description", res.description);
        put(o, "createdAt", res.createdAt);
        return o;
    }

    /**
     * Downloads the newest bundle of a remote source when it is newer than the installed one or when there is no file yet. The old bundle
     * stays in place until the new one has been downloaded and checked. Returns the source as get() does.
     */
    public JSONObject update(String id, MorpheNet.Progress p) throws IOException {
        synchronized (opLock) {
            Rec snap;
            String current;
            boolean hasFile;
            synchronized (this) {
                Rec r = find(id);
                if (r == null) throw new IOException("Unknown source");
                if (!r.remote()) throw new IOException("A local source never updates on its own, add the new file instead");
                snap = r.copy();
                current = installedVersion(r);
                hasFile = bundleFile(id) != null;
            }
            try {
                Resolved res = resolve(snap);
                if (hasFile && !isNewer(current, res.version, snap.prerelease)) {
                    noteError(id, "");
                    return get(id);
                }
                Staged st = fetchBundle(id, res, p);
                synchronized (this) {
                    Rec rec = find(id);
                    if (rec == null) {
                        deleteQuiet(st.file);
                        throw new IOException("The source was removed while it was updating");
                    }
                    try { place(st.file, bundlePath(id)); } catch (IOException e) { deleteQuiet(st.file); throw e; }
                    applyDownload(rec, res, st);
                    save();
                    return view(rec);
                }
            } catch (IOException e) {
                if (!"cancelled".equals(e.getMessage())) noteError(id, e.getMessage());
                throw e;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The community patch finder
    // ---------------------------------------------------------------------------------------------

    /**
     * The community list (the parsed JSON of COMMUNITY_URL: bundles, store, compatibilities, featured) plus fetchedAt (ms) and stale.
     * Kept in community.json and reused while younger than 6 hours unless refresh is set; when the network fails the cached copy is
     * returned with stale=true, and with no cache the IOException is thrown.
     */
    public JSONObject community(boolean refresh, MorpheNet.Progress p) throws IOException {
        JSONObject cached = readCommunityCache();
        if (!refresh && isFresh(cached)) return put(cached, "stale", false);
        String url = communityUrlOverride != null ? communityUrlOverride : COMMUNITY_URL;
        synchronized (communityFetch) {
            if (!refresh) {                                   // another call may have loaded it while this one waited
                JSONObject again = readCommunityCache();
                if (isFresh(again)) return put(again, "stale", false);
                cached = again;
            }
            try {
                return fetchCommunity(url, p);
            } catch (IOException e) {
                if (cached != null && !"cancelled".equals(e.getMessage())) return put(cached, "stale", true);
                throw e;
            }
        }
    }

    private static boolean isFresh(JSONObject cache) {
        if (cache == null) return false;
        long age = System.currentTimeMillis() - cache.optLong("fetchedAt", 0);
        return age >= 0 && age < COMMUNITY_TTL_MS;
    }

    private JSONObject readCommunityCache() {
        synchronized (communityLock) {
            try {
                if (!communityFile.isFile()) return null;
                JSONObject o = parseObject(new String(Files.readAllBytes(communityFile.toPath()), UTF8));
                if (o.optJSONArray("bundles") == null || o.optLong("fetchedAt", 0) <= 0) return null;
                return o;
            } catch (IOException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private JSONObject fetchCommunity(String url, MorpheNet.Progress p) throws IOException {
        File tmp = new File(baseDir, "community.json.new");
        try {
            try {
                MorpheNet.download(url, tmp, JSON_HEADERS, p);
            } catch (IOException e) {
                if ("cancelled".equals(e.getMessage())) throw e;
                throw new IOException("The community list could not be loaded: " + why(e, url), e);
            }
            JSONObject o;
            try {
                o = parseObject(new String(Files.readAllBytes(tmp.toPath()), UTF8));
            } catch (IllegalArgumentException e) {
                throw new IOException("The community list is not valid: " + e.getMessage(), e);
            }
            if (o.optJSONArray("bundles") == null) throw new IOException("The community list has an unexpected format");
            put(o, "fetchedAt", System.currentTimeMillis());
            synchronized (communityLock) { writeAtomic(communityFile, o.toString().getBytes(UTF8)); }
            return put(o, "stale", false);
        } finally {
            deleteQuiet(tmp);
            deleteQuiet(new File(baseDir, "community.json.new.part"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------

    private static void writeAtomic(File dest, byte[] data) throws IOException {
        File dir = dest.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, dest.getName() + ".tmp");
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            moveReplace(tmp, dest);
        } catch (IOException e) {
            deleteQuiet(tmp);
            throw e;
        }
    }

    /** Renames over an existing file in one step when the file system can, else replaces it. */
    private static void moveReplace(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuiet(File f) {
        if (f != null) try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) { /* best effort */ }
    }

    /** Deletes a folder and what is in it; a link is removed, never followed. */
    private static void deleteTree(File f) throws IOException {
        if (f == null || !(f.exists() || Files.isSymbolicLink(f.toPath()))) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        Files.delete(f.toPath());
    }

    private static void deleteTreeQuiet(File f) {
        try { deleteTree(f); } catch (IOException ignored) { /* best effort */ }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = digits[(b[i] >> 4) & 15];
            out[i * 2 + 1] = digits[b[i] & 15];
        }
        return new String(out);
    }

    private static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
