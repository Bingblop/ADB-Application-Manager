package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Client for the public Komi Store ("GitHub Store") catalog - https://github.com/komi-store/komi-store.
 * Its backend indexes GitHub repositories that publish an installable APK, so the whole Android catalog
 * can be paged with a blank query (sorted by stars). Each repo is tagged with GitHub topics; those are
 * mapped here to a small set of readable categories for the store's category filter.
 *
 * Contract (from the public server source): GET {API}search?platform=android&sort=stars&limit=50&offset=N,
 * answered with {items:[RepoResponse], totalHits, source}. Non-relevance sorts page by "offset += items
 * returned until a short page". When the backend can't be reached, the static offline mirror in
 * komi-store-backend-data (plain JSON on raw.githubusercontent.com) is used instead.
 *
 * Pure HTTP/JSON (no Android UI) so the mapping is unit-testable off-device.
 */
public final class KomiApi {
    private KomiApi() {}

    public static final String API = "https://api.github-store.org/v1/";
    public static final String MIRROR = "https://raw.githubusercontent.com/komi-store/komi-store-backend-data/main/cached-data/";
    public static final int PAGE = 50;

    // ---------------------------------------------------------------------------------------------
    // JSON helpers. Android's org.json returns the STRING "null" from optString() for an explicit JSON
    // null, and the Komi responses are full of explicit nulls - so every read goes through these.
    // ---------------------------------------------------------------------------------------------

    static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    static int num(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0;
        return o.optInt(key, 0);
    }

    static long lng(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0L;
        return o.optLong(key, 0L);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** "2026-08-15 22:05:38+00" / "2026-08-15T22:05:38Z" / "2026-08-15T22:05:38.123456Z" -> epoch millis (UTC), 0 if unparseable. */
    public static long parseDate(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            String head = s.substring(0, 19).replace('T', ' ');
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(head).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Categories: topicCodes (the backend's own 15 canonical codes) plus keyword rules over raw topics
    // ---------------------------------------------------------------------------------------------

    private static final String[][] CODE_LABELS = {
        {"ai", "AI"}, {"privacy", "Privacy & Security"}, {"security", "Privacy & Security"},
        {"networking", "Networking"}, {"messaging", "Messaging"}, {"browser", "Browsers"},
        {"social", "Social"}, {"launcher", "Launchers & Customization"}, {"notes", "Productivity"},
        {"reader", "Reading"}, {"audio", "Music & Audio"}, {"video", "Video"}, {"photo", "Photos & Camera"},
        {"backup", "Backup & Sync"}, {"self-hosted", "Self-hosted"},
    };

    // {label, keyword...}; a repo topic matches a keyword when it equals it or contains it as a hyphen-separated word
    private static final String[][] TOPIC_RULES = {
        {"Games", "game", "games", "gaming", "emulator", "emulation", "retro", "arcade", "puzzle", "chess", "roguelike"},
        {"Music & Audio", "music", "audio", "podcast", "radio", "equalizer", "spotify"},
        {"Video", "video", "youtube", "streaming", "anime", "movies", "tv"},
        {"Photos & Camera", "camera", "photo", "photos", "gallery", "image", "images", "photography"},
        {"Messaging", "chat", "messenger", "messaging", "matrix", "xmpp", "signal", "telegram", "sms", "email", "mail"},
        {"Social", "mastodon", "fediverse", "lemmy", "reddit", "twitter", "social", "social-network", "bluesky"},
        {"Privacy & Security", "privacy", "security", "vpn", "password", "password-manager", "encryption", "2fa", "totp", "tor", "firewall", "adblock", "tracker"},
        {"Networking", "network", "networking", "wifi", "dns", "proxy", "ssh", "torrent", "bittorrent", "ftp", "wireguard"},
        {"Browsers", "browser", "web-browser", "webview", "firefox", "chromium"},
        {"Productivity", "productivity", "notes", "note-taking", "todo", "calendar", "office", "task", "tasks", "organizer"},
        {"Reading", "ebook", "ebooks", "epub", "reader", "manga", "comic", "comics", "rss", "news", "pdf"},
        {"Maps & Navigation", "maps", "map", "navigation", "gps", "openstreetmap", "osm", "geocaching"},
        {"File management", "file-manager", "filemanager", "files", "explorer", "file-explorer", "storage"},
        {"Launchers & Customization", "launcher", "theme", "themes", "customization", "icon-pack", "wallpaper", "widget", "keyboard", "ime"},
        {"System tools", "root", "magisk", "xposed", "lsposed", "shizuku", "adb", "system", "utility", "utilities", "tools", "tool", "debloat", "package-manager"},
        {"Development", "developer-tools", "developer", "debugging", "programming", "terminal", "ide", "git", "devtools", "code-editor"},
        {"Education", "education", "learning", "flashcards", "dictionary", "language-learning", "quiz"},
        {"Health & Fitness", "health", "fitness", "workout", "sleep", "meditation", "wellness"},
        {"Finance", "finance", "bitcoin", "crypto", "cryptocurrency", "wallet", "budget", "expense", "money"},
        {"Weather", "weather", "forecast"},
        {"Backup & Sync", "backup", "sync", "syncthing", "nextcloud", "cloud", "webdav"},
        {"AI", "ai", "llm", "chatgpt", "machine-learning", "openai", "gpt", "stable-diffusion"},
    };

    // {label, regex-of-whole-words}: a fallback for repos with no usable topics, matched against the repo's
    // name + description. Deliberately conservative - generic words ("tool", "system", "app") are left out.
    private static final String[][] TEXT_RULES = {
        {"Messaging", "messenger|messaging|chat|sms|instant messag\\w*"},
        {"Social", "social|mastodon|fediverse|lemmy|reddit|twitter|bluesky"},
        {"Privacy & Security", "privacy|private|password manager|encrypt\\w*|authenticator|2fa|totp|security|vpn"},
        {"Networking", "proxy|tunnel|dns|wi-?fi|network\\w*|ssh|torrent\\w*|clash|sing-box"},
        {"Games", "games?|gaming|emulator|emulation|minecraft|retro|arcade"},
        {"Music & Audio", "music|audio|podcasts?|radio|songs?"},
        {"Video", "video|videos|media player|youtube|streaming|movies?|anime"},
        {"Photos & Camera", "photos?|camera|gallery|images?"},
        {"Weather", "weather|forecast"},
        {"Reading", "e-?books?|readers?|manga|comics?|novels?|rss"},
        {"Productivity", "reminders?|notes|to-?do|calendar|tasks|office|translat\\w+"},
        {"Maps & Navigation", "maps?|navigation|gps|openstreetmap"},
        {"File management", "file manager|file sharing|file explorer"},
        {"Browsers", "browsers?"},
        {"System tools", "root|magisk|kernel|adb|accessibility|debloat\\w*"},
        {"Development", "terminal|developers?|debugger|programming"},
        {"AI", "ai|llm|machine learning|genai|gpt"},
        {"Backup & Sync", "backups?|sync"},
        {"Education", "learning|education\\w*|dictionary|flashcards"},
        {"Finance", "wallet|bitcoin|crypto\\w*|finance|budget"},
        {"Health & Fitness", "health|fitness|workout"},
        {"Launchers & Customization", "launcher|themes?|wallpapers?|keyboard|icon pack"},
    };
    private static final java.util.regex.Pattern[] TEXT_PATTERNS = compileTextRules();

    private static java.util.regex.Pattern[] compileTextRules() {
        java.util.regex.Pattern[] p = new java.util.regex.Pattern[TEXT_RULES.length];
        for (int i = 0; i < TEXT_RULES.length; i++) {
            p[i] = java.util.regex.Pattern.compile("\\b(?:" + TEXT_RULES[i][1] + ")\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        }
        return p;
    }

    /** Categories guessed from free text (name + description); empty when nothing matches. */
    public static List<String> categoriesFromText(String text, int max) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) return out;
        for (int i = 0; i < TEXT_RULES.length && out.size() < max; i++) {
            if (TEXT_PATTERNS[i].matcher(text).find()) out.add(TEXT_RULES[i][0]);
        }
        return out;
    }

    private static boolean topicHas(String topic, String kw) {
        if (topic.equals(kw)) return true;
        if (kw.indexOf('-') >= 0) return topic.contains(kw);
        for (String tok : topic.split("-")) if (tok.equals(kw)) return true;
        return false;
    }

    /** At most {@code max} readable categories for a repo, or ["Other"] when nothing matches. */
    public static List<String> categories(JSONArray topicCodes, JSONArray topics, int max) {
        Set<String> out = new LinkedHashSet<String>();
        if (topicCodes != null) {
            for (int i = 0; i < topicCodes.length() && out.size() < max; i++) {
                String code = topicCodes.optString(i, "");
                for (String[] cl : CODE_LABELS) if (cl[0].equals(code)) { out.add(cl[1]); break; }
            }
        }
        if (topics != null && out.size() < max) {
            List<String> ts = new ArrayList<String>();
            for (int i = 0; i < topics.length(); i++) {
                String t = topics.optString(i, "").toLowerCase(Locale.US);
                if (!t.isEmpty()) ts.add(t);
            }
            for (String[] rule : TOPIC_RULES) {
                if (out.size() >= max) break;
                boolean hit = false;
                for (int k = 1; k < rule.length && !hit; k++) for (String t : ts) if (topicHas(t, rule[k])) { hit = true; break; }
                if (hit) out.add(rule[0]);
            }
        }
        if (out.isEmpty()) out.add("Other");
        return new ArrayList<String>(out);
    }

    // ---------------------------------------------------------------------------------------------
    // RepoResponse -> catalog item
    // ---------------------------------------------------------------------------------------------

    /** "libre-tube" -> "Libre Tube"; names that already carry capitals ("NewPipe") are left alone. */
    static String prettyName(String repo) {
        if (repo == null || repo.isEmpty()) return "";
        if ((repo.indexOf('-') < 0 && repo.indexOf('_') < 0) || !repo.equals(repo.toLowerCase(Locale.US))) return repo;
        StringBuilder sb = new StringBuilder();
        for (String w : repo.split("[-_]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Maps one backend RepoResponse (or a mirror entry) to the item shape the Store tab renders. Null if unusable. */
    public static JSONObject item(JSONObject r) {
        if (r == null) return null;
        try {
            String full = str(r, "fullName");
            JSONObject ownerObj = r.optJSONObject("owner");
            String owner = str(ownerObj, "login");
            String repo = str(r, "name");
            if (full.isEmpty() && !owner.isEmpty() && !repo.isEmpty()) full = owner + "/" + repo;
            int slash = full.indexOf('/');
            if (slash > 0) {
                if (owner.isEmpty()) owner = full.substring(0, slash);
                if (repo.isEmpty()) repo = full.substring(slash + 1);
            }
            if (owner.isEmpty() || repo.isEmpty()) return null;

            String avatar = str(ownerObj, "avatarUrl");
            if (avatar.isEmpty()) avatar = "https://github.com/" + owner + ".png";
            if (avatar.startsWith("https://avatars.githubusercontent.com/")) avatar += (avatar.contains("?") ? "&" : "?") + "s=96";

            JSONObject o = new JSONObject();
            o.put("id", full);
            o.put("key", full);
            o.put("name", prettyName(repo));
            o.put("desc", trim(str(r, "description"), 200));
            o.put("icon", avatar);
            o.put("owner", owner);
            o.put("repo", repo);
            o.put("pkg", "");                       // not exposed by the catalog; learned from the APK at install time
            o.put("stars", num(r, "stargazersCount"));
            o.put("downloads", lng(r, "downloadCount"));
            long upd = parseDate(str(r, "latestReleaseDate"));
            if (upd == 0) upd = parseDate(str(r, "updatedAt"));
            if (upd == 0) upd = parseDate(str(r, "pushedAt"));
            o.put("updated", upd);
            o.put("ver", str(r, "latestReleaseTag"));
            o.put("lang", str(r, "language"));
            String page = str(r, "htmlUrl");
            o.put("page", page.isEmpty() ? "https://github.com/" + full : page);
            List<String> cats = categories(r.optJSONArray("topicCodes"), r.optJSONArray("topics"), 3);
            if (cats.size() == 1 && cats.get(0).equals("Other")) {
                // No usable topics: fall back to what the name and description say
                List<String> guess = categoriesFromText(repo + " " + str(r, "description"), 2);
                if (!guess.isEmpty()) cats = guess;
            }
            o.put("cats", new JSONArray(cats));
            o.put("source", "github");
            o.put("resolveKind", "github");
            o.put("assetFilter", "");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Maps a {items:[...]} / {repositories:[...]} / bare-array body to catalog items, skipping duplicates already in {@code seen}. */
    public static JSONArray mapItems(Object body, Set<String> seen) {
        JSONArray src = null;
        if (body instanceof JSONArray) src = (JSONArray) body;
        else if (body instanceof JSONObject) {
            JSONObject b = (JSONObject) body;
            src = b.optJSONArray("items");
            if (src == null) src = b.optJSONArray("repositories");
        }
        JSONArray out = new JSONArray();
        if (src == null) return out;
        for (int i = 0; i < src.length(); i++) {
            JSONObject it = item(src.optJSONObject(i));
            if (it == null) continue;
            if (seen != null && !seen.add(it.optString("key"))) continue;
            out.put(it);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------------

    /**
     * One page of the Android catalog. A blank {@code q} browses by {@code sort} (stars | updated | recent);
     * a non-blank {@code q} runs a relevance search. Returns the raw backend page: {items:[RepoResponse...], ...}.
     */
    public static JSONObject searchRaw(String q, String sort, int offset, int limit) throws Exception {
        boolean query = q != null && !q.trim().isEmpty();
        String s = query ? "relevance" : (sort == null || sort.isEmpty() ? "stars" : sort);
        StringBuilder u = new StringBuilder(API).append("search?platform=android&limit=").append(limit)
                .append("&offset=").append(offset).append("&sort=").append(s);
        if (query) u.append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"));
        byte[] raw = UpdateManager.httpGet(u.toString(), "application/json", 6 * 1024 * 1024);
        return new JSONObject(new String(raw, "UTF-8"));
    }

    // Static mirror files (plain JSON, no API limits): the daily feed, the three charts and the topic buckets.
    private static final String[] MIRROR_FILES = {
        "feed/android.json", "trending/android.json", "most-popular/android.json", "new-releases/android.json",
        "topics/privacy/android.json", "topics/media/android.json", "topics/productivity/android.json",
        "topics/networking/android.json", "topics/dev-tools/android.json",
    };

    /** The offline mirror's Android lists, merged and de-duplicated. Throws only when none could be read. */
    public static JSONArray mirrorItems() throws Exception {
        JSONArray all = new JSONArray();
        Set<String> seen = new HashSet<String>();
        Exception last = null;
        int ok = 0;
        for (String f : MIRROR_FILES) {
            try {
                byte[] raw = UpdateManager.httpGet(MIRROR + f, "application/json", 4 * 1024 * 1024);
                JSONArray part = mapItems(new JSONObject(new String(raw, "UTF-8")), seen);
                for (int i = 0; i < part.length(); i++) all.put(part.get(i));
                ok++;
            } catch (Exception e) {
                last = e;
            }
        }
        if (ok == 0) throw last != null ? last : new IllegalStateException("mirror unavailable");
        return all;
    }
}
