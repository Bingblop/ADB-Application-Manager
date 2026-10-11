package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Settings, "Try the Kotlin build (beta)": finds the test build of the separate Kotlin app in a GitHub release of this repository and
 * says whether it is installed. Pure logic with no Android classes: the web request and the package query are passed in ({@link Http},
 * {@link Packages}), so the choice of release and file can be tested with a fake source. The download and the install themselves are
 * not here: MainActivity hands the chosen file to its one existing download-and-install path (hardened download client, published
 * SHA-256, native install confirmation), with this class's package id as the package the download must turn out to be.
 */
final class KotlinBuild {
    private KotlinBuild() {}

    // ---- The configuration: everything that names the Kotlin build or its release layout is in this block ------------------------------

    /**
     * applicationId of the Kotlin app, as the owner decided it: the Kotlin app's applicationId (app/build.gradle.kts) must be changed to this
     * before its first release. It is a different id from this app's, so the two install side by side. See docs/KOTLIN-SWAP-CONTRACT.md.
     */
    static final String KOTLIN_PACKAGE_ID = "com.bloatware.bingblop.kotlin";
    /** The repository whose releases hold the Kotlin test builds. */
    static final String REPO = "Bingblop/ADB-Application-Manager";
    /** The releases list, newest first, 100 to a page; page N is this address plus "&page=N" (see {@link #pageUrl}). */
    static final String RELEASES_API_URL = "https://api.github.com/repos/" + REPO + "/releases?per_page=100";
    /** What "Open the releases page" opens. */
    static final String RELEASES_PAGE_URL = "https://github.com/" + REPO + "/releases";
    /** Only a Kotlin release is taken: its tag starts with this. */
    static final String TAG_PREFIX = "kotlin-";
    /** The APK of the release: only {@code *} is a wildcard, the rest is literal. */
    static final String ASSET_PATTERN = "ADB-Manager-Kotlin*.apk";
    /** Whether a release marked "pre-release" counts (a draft never does). */
    static final boolean ALLOW_PRERELEASE = false;

    // ---- Limits ------------------------------------------------------------------------------------------------------------------------

    /** Every download address must start with this (https, this repository's release downloads). */
    static final String DOWNLOAD_URL_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    /** One page of 100 releases with their notes and files. */
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    /** Pages of releases read at most (this repository has more than 30 releases, and a Kotlin one may be on an older page). */
    static final int MAX_PAGES = 5;
    static final int PAGE_SIZE = 100;
    static final int MAX_SUMS_BYTES = 64 * 1024;
    static final int MAX_RELEASES = PAGE_SIZE;
    static final int MAX_ASSETS = 100;
    static final long MAX_APK_BYTES = 600L * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NAME = 120;
    private static final int MAX_TAG = 64;

    /** The web request: the body of {@code url}, at most {@code maxBytes} (more is an error). Throws on any failure. */
    interface Http {
        byte[] get(String url, int maxBytes) throws Exception;
    }

    /** The package query: the versionName of an installed package ("" when it has none), or null when it is not installed. */
    interface Packages {
        String installedVersion(String pkg) throws Exception;
    }

    /** The release and file chosen. */
    static final class Choice {
        String tag = "", version = "", apkName = "", apkUrl = "", page = "";
        /** The SHA-256 the release publishes for the file (as published, so a malformed one stays visible and stops the install), or "". */
        String sha256 = "";
        /** Address of a checksum file of the release that may hold the file's hash, or "". */
        String sumsUrl = "", sumsName = "";
        long size = 0;
        long publishedAt = 0;
    }

    // ---- The choice --------------------------------------------------------------------------------------------------------------------

    /** Only letters, digits and . _ - (so a name or tag cannot carry a path, a space, a query, a control or direction character). */
    static boolean safeToken(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return !s.startsWith(".") && !s.contains("..");
    }

    /** Whether {@code name} matches a pattern in which only {@code *} is special (case-sensitive; the stars can be empty). */
    static boolean globMatch(String pattern, String name) {
        if (pattern == null || name == null) return false;
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) return pattern.equals(name);
        int at = 0;
        if (!name.startsWith(parts[0])) return false;
        at = parts[0].length();
        for (int i = 1; i < parts.length - 1; i++) {
            int f = name.indexOf(parts[i], at);
            if (f < 0) return false;
            at = f + parts[i].length();
        }
        String last = parts[parts.length - 1];
        return name.length() - at >= last.length() && name.endsWith(last);
    }

    /**
     * The numbers of a version ("kotlin-8.0.10-beta" gives "8", "0", "10"): the first dotted number in it, each as digits without leading
     * zeros and without any limit on their size (so no number is cut or overflows); empty when there is none.
     */
    static String[] versionParts(String v) {
        if (v == null) return new String[0];
        int i = 0, n = v.length();
        while (i < n && !(v.charAt(i) >= '0' && v.charAt(i) <= '9')) i++;
        String[] out = new String[8];
        int count = 0;
        while (i < n && count < out.length) {
            int j = i;
            while (j < n && v.charAt(j) >= '0' && v.charAt(j) <= '9') j++;
            if (j == i) break;
            int k = i;
            while (k < j - 1 && v.charAt(k) == '0') k++;                    // leading zeros out, a lone 0 stays
            out[count++] = v.substring(k, j);
            if (j < n && v.charAt(j) == '.' && j + 1 < n && v.charAt(j + 1) >= '0' && v.charAt(j + 1) <= '9') i = j + 1; else break;
        }
        return java.util.Arrays.copyOf(out, count);
    }

    static boolean hasNumber(String v) {
        return versionParts(v).length > 0;
    }

    /** -1, 0 or 1 comparing the numbers of two versions ("8.0.10" is above "8.0.9"; a missing part is 0; text around the numbers is ignored). */
    static int compareVersions(String a, String b) {
        String[] x = versionParts(a), y = versionParts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            if (p.length() != q.length()) return p.length() < q.length() ? -1 : 1;          // no leading zeros: more digits is a bigger number
            int c = p.compareTo(q);
            if (c != 0) return c < 0 ? -1 : 1;
        }
        return 0;
    }

    /** "2026-10-11T13:52:00Z" as a number that sorts the same way (0 when it is not that shape). */
    private static long stamp(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 19; i++) {
            char c = iso.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
        }
        if (d.length() != 14) return 0;
        try { return Long.parseLong(d.toString()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * The newest Kotlin test build in a GitHub releases list ({@code json}, the answer of {@link #RELEASES_API_URL}): a release whose tag starts
     * with {@link #TAG_PREFIX}, that is not a draft (nor a pre-release, unless {@code allowPrerelease}), and has an APK whose name matches
     * {@link #ASSET_PATTERN} at this repository's download address. "Newest" is the highest number in the tag, then the later publishing date,
     * then the one listed first. Returns null when there is none; throws {@link IllegalArgumentException} when the text is not a releases list.
     */
    static Choice choose(String json, boolean allowPrerelease) {
        return chooseFrom(parseList(json), allowPrerelease);
    }

    /** The text of one page as a list: refused (IllegalArgumentException) when it is too large, nested too deep or not a list. */
    static JSONArray parseList(String json) {
        if (json == null) throw new IllegalArgumentException("no answer");
        if (json.length() > MAX_JSON_BYTES) throw new IllegalArgumentException("the answer is too large");
        if (nestingTooDeep(json)) throw new IllegalArgumentException("the answer is not a list of releases");
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("the answer is not a list of releases");
        } catch (StackOverflowError e) {
            throw new IllegalArgumentException("the answer is not a list of releases");
        }
    }

    /** The address of page {@code page} (1 is the first) of the releases list. */
    static String pageUrl(int page) {
        return RELEASES_API_URL + "&page=" + page;
    }

    static Choice chooseFrom(JSONArray list, boolean allowPrerelease) {
        Choice best = null;
        int n = Math.min(list.length(), MAX_RELEASES);
        for (int i = 0; i < n; i++) {
            JSONObject rel = list.optJSONObject(i);
            if (rel == null) continue;
            if (rel.optBoolean("draft", false)) continue;
            if (rel.optBoolean("prerelease", false) && !allowPrerelease) continue;
            Object tagObj = rel.opt("tag_name");
            if (!(tagObj instanceof String)) continue;
            String tag = (String) tagObj;
            if (!tag.startsWith(TAG_PREFIX) || tag.length() == TAG_PREFIX.length() || !safeToken(tag, MAX_TAG)) continue;
            Choice c = pickAsset(rel, tag);
            if (c == null) continue;
            c.publishedAt = stamp(rel.optString("published_at", ""));
            if (best == null) { best = c; continue; }
            if (newer(c, best)) best = c;
        }
        return best;
    }

    /** Whether brackets outside strings nest deeper than a releases list ever does (the parser recurses on them). */
    static boolean nestingTooDeep(String json) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '[' || c == '{') {
                if (++depth > MAX_DEPTH) return true;
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
        return false;
    }

    /** Whether {@code c} is a newer build than {@code best}: the higher number in the tag, then the later publishing date (equal: the one found first stays). */
    static boolean newer(Choice c, Choice best) {
        if (best == null) return true;
        int cmp = compareVersions(c.version, best.version);
        return cmp > 0 || (cmp == 0 && c.publishedAt > best.publishedAt);
    }

    private static Choice pickAsset(JSONObject rel, String tag) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return null;
        Choice c = null;
        String sumsList = null;
        int n = Math.min(assets.length(), MAX_ASSETS);
        for (int i = 0; i < n; i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            Object nameObj = a.opt("name"), urlObj = a.opt("browser_download_url");
            if (!(nameObj instanceof String) || !(urlObj instanceof String)) continue;
            String name = (String) nameObj, url = (String) urlObj;
            if (!safeToken(name, MAX_NAME)) continue;
            if (!url.equals(DOWNLOAD_URL_PREFIX + tag + "/" + name)) continue;               // https, this repository, this release, this file: nothing else
            if (a.has("state") && !"uploaded".equals(a.optString("state", ""))) continue;     // still uploading
            if (c == null && globMatch(ASSET_PATTERN, name)) {
                long size = a.optLong("size", 0);
                if (a.has("size") && (size <= 0 || size > MAX_APK_BYTES)) continue;
                c = new Choice();
                c.tag = tag;
                c.version = tag.substring(TAG_PREFIX.length());
                c.apkName = name;
                c.apkUrl = url;
                c.size = size;
                c.page = RELEASES_PAGE_URL + "/tag/" + tag;
                Object digest = a.opt("digest");
                if (digest instanceof String) c.sha256 = ((String) digest).trim();
            } else if (name.equalsIgnoreCase("SHA256SUMS.txt") || name.equalsIgnoreCase("SHA256SUMS")) {
                sumsList = name;
            }
        }
        if (c == null) return null;
        if (c.sha256.isEmpty()) {
            // A checksum file of the release: "<apk>.sha256" first, else SHA256SUMS(.txt)
            String want = c.apkName + ".sha256";
            String useName = null;
            for (int i = 0; i < n && useName == null; i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && want.equals(a.optString("name", ""))) {
                    String url = a.optString("browser_download_url", "");
                    if (url.equals(DOWNLOAD_URL_PREFIX + tag + "/" + want)) useName = want;
                }
            }
            if (useName == null && sumsList != null) useName = sumsList;
            if (useName != null) { c.sumsName = useName; c.sumsUrl = DOWNLOAD_URL_PREFIX + tag + "/" + useName; }
        }
        return c;
    }

    // ---- The checksum file --------------------------------------------------------------------------------------------------------------

    /**
     * The SHA-256 of {@code apkName} in a checksum file: "hex  name" / "hex *name" lines (sha256sum), or a lone hex line in a file that
     * belongs to this one APK ({@code single}). Returns "" when the file does not list it.
     */
    static String hashFromSums(String text, String apkName, boolean single) {
        if (text == null) return "";
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length && i < 2000; i++) {
            String l = lines[i].trim();
            if (l.length() < 64) continue;
            String h = l.substring(0, 64);
            if (!InstallGuards.isSha256(h)) continue;
            String rest = l.substring(64).trim();
            if (rest.isEmpty()) { if (single) return h.toLowerCase(Locale.ROOT); continue; }
            if (rest.startsWith("*")) rest = rest.substring(1);
            if (rest.equals(apkName)) return h.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // ---- The lookups the page and the install use ----------------------------------------------------------------------------------------

    /**
     * The newest Kotlin test build with its checksum, or null when no release has one. The releases are read a page at a time (newest
     * created first, {@link #PAGE_SIZE} to a page, at most {@link #MAX_PAGES} pages) until a page is short or empty (the last one). The
     * best build across all the pages read wins, by the number in its tag: a higher number can sit on an older page. A page that cannot
     * be read stops the lookup.
     *
     * A release that carries a checksum asset (its own .sha256 or SHA256SUMS.txt) must give a valid SHA-256 for the file: an unreadable,
     * empty, malformed or unrelated checksum file stops the lookup (an exception) instead of leaving the file unchecked. With no checksum
     * asset and no digest at all, nothing is published and the install goes on as other Store installs do.
     */
    static Choice find(Http http, boolean allowPrerelease) throws Exception {
        Choice c = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");       // the screen is gone: no more pages
            byte[] body = http.get(pageUrl(page), MAX_JSON_BYTES);
            if (body == null) throw new IllegalStateException("no answer from GitHub");
            if (body.length > MAX_JSON_BYTES) throw new IllegalArgumentException("the answer is too large");
            JSONArray list = parseList(new String(body, "UTF-8"));
            Choice here = chooseFrom(list, allowPrerelease);
            if (here != null && newer(here, c)) c = here;
            if (list.length() < PAGE_SIZE) break;
        }
        if (c != null && c.sha256.isEmpty() && !c.sumsUrl.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
            byte[] sums;
            try {
                sums = http.get(c.sumsUrl, MAX_SUMS_BYTES);
            } catch (Exception e) {
                throw new IllegalStateException("the release's checksum file (" + c.sumsName + ") could not be read, so the download cannot be verified");
            }
            c.sha256 = sums == null ? "" : hashFromSums(new String(sums, "UTF-8"), c.apkName, !c.sumsName.equalsIgnoreCase("SHA256SUMS.txt") && !c.sumsName.equalsIgnoreCase("SHA256SUMS"));
            if (!InstallGuards.isSha256(c.sha256)) {
                throw new IllegalStateException("the release's checksum file (" + c.sumsName + ") has no valid SHA-256 for " + c.apkName + ", so the download cannot be verified");
            }
        }
        return c;
    }

    /**
     * What the Settings card shows: whether the Kotlin app is installed (and its version), and the newest test build in the releases.
     * {@code release} is "ok" (version, tag, size, update), "none" (nothing published) or "error" (error). {@code update} is true only when
     * the app is installed and the release is newer than it by number. Never throws.
     */
    static JSONObject check(Http http, Packages pk, boolean allowPrerelease) {
        JSONObject o = new JSONObject();
        try {
            String inst = null;
            try { inst = pk.installedVersion(KOTLIN_PACKAGE_ID); } catch (Exception ignored) { /* a package query that fails reads as not installed */ }
            o.put("package", KOTLIN_PACKAGE_ID);
            o.put("page", RELEASES_PAGE_URL);
            o.put("installed", inst != null);
            o.put("installedVersion", inst == null ? "" : inst);
            o.put("update", false);
            try {
                Choice c = find(http, allowPrerelease);
                if (c == null) {
                    o.put("release", "none");
                } else {
                    o.put("release", "ok");
                    o.put("tag", c.tag);
                    o.put("version", c.version);
                    o.put("size", c.size);
                    o.put("update", inst != null && hasNumber(inst) && compareVersions(c.version, inst) > 0);
                }
            } catch (Exception e) {
                o.put("release", "error");
                o.put("error", cleanMessage(e));
            }
        } catch (Exception e) {
            // JSONObject.put only fails on a non-finite number; nothing here is one
        }
        return o;
    }

    /** A short single line of an exception's message (no new lines or control characters, at most 160 characters). */
    static String cleanMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < m.length() && b.length() < 160; i++) {
            char c = m.charAt(i);
            b.append(c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) ? ' ' : c);
        }
        return b.toString().trim();
    }
}
