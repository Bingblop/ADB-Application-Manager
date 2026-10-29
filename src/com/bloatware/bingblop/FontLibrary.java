package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Custom Fonts tab's own bookkeeping, the part of the font maker bridge that needs no Android classes (tested on the JDK:
 * tests/java/src/FontLibraryTest.java).
 *
 * <ul>
 * <li>{@link Dirs}: where the tab keeps its files. A picked file is copied to {@code cache/fontmaker/in/<window>/}, a font made here lives in
 * {@code files/fontmaker/library/<id>/} (font.apk, Regular.ttf, Bold.ttf when it has one, meta.json), a downloaded emoji font in
 * {@code files/fontmaker/emoji/<catalog id>.ttf}.</li>
 * <li>Ids, tokens and {@link Picks}: the page names a picked file by a random token and a library entry by a random id. Both are checked
 * against a strict pattern and only ever looked up, so nothing the page sends can name a path.</li>
 * <li>{@link #parseRequest}: the page's build request, checked field by field before anything is read.</li>
 * <li>{@link Entry}, {@link #toMeta} / {@link #fromMeta}, {@link #commit}, {@link #list}: an entry's meta.json, and an entry written so that
 * a crash, a power cut or a full disk never leaves half of one (a staged folder flushed to the disk and renamed into place; a rebuild swaps
 * the old folder out only after the new one is complete).</li>
 * <li>{@link Jobs}: the running builds and downloads, one per key, with their Cancel; {@link Throttle}: how often progress reaches the
 * page.</li>
 * <li>The JSON the page gets: {@link #infoJson}, {@link #entryJson}, {@link #catalogItemJson}, {@link #error} (design section 6).</li>
 * </ul>
 */
public final class FontLibrary {

    private FontLibrary() {}

    /** The files of one library entry. */
    public static final String APK = "font.apk", REGULAR = "Regular.ttf", BOLD = "Bold.ttf", META = "meta.json";
    /** The names the font changer gives the fonts in /data/app_fonts/<user>/<suffix>/ (Samsung's renderer looks for these). */
    public static final String DROID_REGULAR = "DroidSans.ttf", DROID_BOLD = "DroidSans-Bold.ttf";
    /** The largest picked file the app copies (the engine's own limit). */
    public static final long MAX_PICK_BYTES = Sfnt.MAX_FONT_BYTES;
    /** The largest font the page is handed as base64 for a preview (a bigger one previews as "too large"). */
    public static final long MAX_PREVIEW_BYTES = 24L * 1024 * 1024;
    /** The largest emoji download (the iOS emoji are about 44 MB). */
    public static final long MAX_EMOJI_BYTES = 96L * 1024 * 1024;
    /** A build request is a few hundred bytes; anything this long is refused unread. */
    static final int MAX_REQUEST_CHARS = 64 * 1024;
    static final int MAX_AXES = 64;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern CATALOG_ID = Pattern.compile("[a-z0-9]{1,32}");
    private static final Pattern AXIS_TAG = Pattern.compile("[\\x21-\\x7e][\\x20-\\x7e]{3}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public static final List<String> SLOTS = Collections.unmodifiableList(java.util.Arrays.asList("regular", "bold", "emoji"));

    // ---------------------------------------------------------------------------------------------
    // Where the files are
    // ---------------------------------------------------------------------------------------------

    /** The tab's folders, from the app's files and cache folders. */
    public static final class Dirs {
        public final File root, library, emoji, in, work;
        /** Google Fonts (v7.18.0): the catalog (gfonts/catalog.json), the downloaded families (gfonts/fam/), the previews (a cache). */
        public final File gfonts, gfFamilies, gfPreviews;
        /** The iOS emoji sample read from its font (specimen/apple.json). */
        public final File specimen;

        public Dirs(File filesDir, File cacheDir) {
            root = new File(filesDir, "fontmaker");
            library = new File(root, "library");
            emoji = new File(root, "emoji");
            in = new File(new File(cacheDir, "fontmaker"), "in");
            work = new File(new File(cacheDir, "fontmaker"), "work");
            gfonts = new File(root, "gfonts");
            gfFamilies = new File(gfonts, "fam");
            gfPreviews = new File(new File(cacheDir, "fontmaker"), "gfpv");
            specimen = new File(root, "specimen");
        }

        /** An entry's folder. A string that is not an id is a caller bug: the bridge checks {@link #isId} first. */
        public File entryDir(String id) {
            if (!isId(id)) throw new IllegalArgumentException("not a library id");
            return new File(library, id);
        }

        /** Where a catalog emoji font is kept, or null for a string that is not a catalog id. */
        public File emojiFile(String catalogId) {
            if (catalogId == null || !CATALOG_ID.matcher(catalogId).matches()) return null;
            return new File(emoji, catalogId + ".ttf");
        }

        /** A window's folder for the copies of its picked files (each window has its own, so one never removes another's). */
        public File pickFolder(String window) {
            if (!isId(window)) throw new IllegalArgumentException("not a window id");
            return new File(in, window);
        }

        /** The system emoji swap's record and the font changer's (what the app last applied). */
        File swapRecord() { return new File(root, "sysemoji.json"); }
        File changerRecord() { return new File(root, "changer.json"); }
    }

    // ---------------------------------------------------------------------------------------------
    // Ids and tokens
    // ---------------------------------------------------------------------------------------------

    public static boolean isId(String s) { return s != null && ID.matcher(s).matches(); }
    public static boolean isToken(String s) { return s != null && TOKEN.matcher(s).matches(); }
    public static boolean isCatalogId(String s) { return s != null && CATALOG_ID.matcher(s).matches(); }
    public static boolean isSlot(String s) { return s != null && SLOTS.contains(s); }

    /** A new library id (12 hex digits). The bridge passes a SecureRandom. */
    public static String newId(Random r) { return hexOf(r, 6); }

    /** A new pick token (32 hex digits). */
    public static String newToken(Random r) { return hexOf(r, 16); }

    private static String hexOf(Random r, int bytes) {
        byte[] b = new byte[bytes];
        r.nextBytes(b);
        return hex(b);
    }

    /** The Android user the app runs as (a uid is user * 100000 + app id), the folder number under /data/app_fonts. */
    public static int userIdOf(int uid) { return uid < 0 ? 0 : uid / 100000; }

    // ---------------------------------------------------------------------------------------------
    // Picked files
    // ---------------------------------------------------------------------------------------------

    /** A file the user picked and the app copied, as the page names it (by its token). */
    public static final class Pick {
        public final String slot, token, fileName;
        public final File file;
        public final long size;
        /** What FontInspect found: the colour format ("none", "COLRv0" ...), the outlines, whether it is variable. */
        public final String color, outlines;
        public final boolean variable;

        public Pick(String slot, String token, String fileName, File file, long size, String color, String outlines, boolean variable) {
            this.slot = slot; this.token = token; this.fileName = fileName; this.file = file; this.size = size;
            this.color = color == null ? "none" : color; this.outlines = outlines == null ? "" : outlines; this.variable = variable;
        }
    }

    /**
     * The picked files of this window: the current one of each slot, and older ones a running build still reads. A new pick of a slot makes
     * the one before it unknown; its copy is deleted at once, or when the last build holding it lets go.
     */
    public static final class Picks {
        private final File folder;
        private final Map<String, Pick> byToken = new HashMap<String, Pick>();
        private final Map<String, String> current = new HashMap<String, String>();   // slot -> token
        private final Map<String, Integer> held = new HashMap<String, Integer>();     // token -> holders
        private final Map<String, Integer> asked = new HashMap<String, Integer>();    // slot -> the number of the latest choice

        /** Starts empty, in the window's own folder ({@link Dirs#pickFolder}): the copies of another window are not touched. */
        public Picks(File folder) {
            this.folder = folder;
            deleteTree(folder);
        }

        public synchronized File fileFor(String slot, String token) { return new File(folder, slot + "-" + token + ".font"); }

        /**
         * Numbers a file the user chose for a slot, in the order the choices came in. The copies run side by side, and one from a slow
         * provider (a cloud file) can end after a later choice: {@link #put(Pick, int)} then keeps the later one.
         */
        public synchronized int ask(String slot) {
            int n = (asked.containsKey(slot) ? asked.get(slot) : 0) + 1;
            asked.put(slot, n);
            return n;
        }

        /** Makes p the slot's current pick, as the newest choice. */
        public synchronized void put(Pick p) { put(p, ask(p.slot)); }

        /**
         * Makes p (choice number {@code ask} of its slot) the slot's current pick; the one it replaces is forgotten (and its copy deleted
         * unless a build holds it). False, and p's copy deleted, when a later choice of the slot came in meanwhile.
         */
        public synchronized boolean put(Pick p, int ask) {
            Integer latest = asked.get(p.slot);
            if (latest != null && ask < latest) { p.file.delete(); return false; }
            String old = current.put(p.slot, p.token);
            byToken.put(p.token, p);
            if (old != null && !old.equals(p.token)) forget(old);
            return true;
        }

        /** The pick with this token, or null (unknown, replaced, or not a token at all). */
        public synchronized Pick get(String token) {
            if (!isToken(token)) return null;
            Pick p = byToken.get(token);
            return p != null && token.equals(current.get(p.slot)) ? p : null;
        }

        /** A build keeps its inputs while it reads them; {@link #release} lets go. */
        public synchronized void hold(Pick p) { if (p != null) held.put(p.token, held.containsKey(p.token) ? held.get(p.token) + 1 : 1); }

        public synchronized void release(Pick p) {
            if (p == null || !held.containsKey(p.token)) return;
            int n = held.get(p.token) - 1;
            if (n > 0) { held.put(p.token, n); return; }
            held.remove(p.token);
            if (!p.token.equals(current.get(p.slot))) { byToken.remove(p.token); p.file.delete(); }
        }

        private void forget(String token) {
            Pick p = byToken.get(token);
            if (p == null || held.containsKey(token)) return;
            byToken.remove(token);
            p.file.delete();
        }

        /** Every copy of this window is deleted (the window closes). */
        public synchronized void clear() {
            byToken.clear();
            current.clear();
            held.clear();
            deleteTree(folder);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The build request
    // ---------------------------------------------------------------------------------------------

    /** A request the bridge refuses before any work, with the page's code ("bad_request", "bad_name", "no_regular" ...) and a sentence. */
    public static final class BadRequest extends Exception {
        public final String code;
        public BadRequest(String code, String message) { super(message); this.code = code; }
    }

    /** One face of a request: the pick's token and, for a variable font, the point on its axes (null: the static font, or the guess). */
    public static final class Face {
        public final String token;
        public final LinkedHashMap<String, Double> coords;
        Face(String token, LinkedHashMap<String, Double> coords) { this.token = token; this.coords = coords; }
    }

    /** {@code {name, regular:{token, coords}, bold:{token, coords}|null, emoji:{catalogId}|{token}|null, emojiScale, rebuildId}}, checked. */
    public static final class Request {
        public String name;
        public Face regular, bold;
        public String emojiCatalogId, emojiToken;
        public double emojiScale = 1.0;
        public String rebuildId;
        public boolean hasEmoji() { return emojiCatalogId != null || emojiToken != null; }
    }

    /**
     * Reads and checks the page's build request. The name is cleaned as the package will be ({@link FontPackage#cleanName}); a token must
     * have a token's shape (whether it is still known is the caller's check); a coordinate is a 4-character axis tag and a finite number;
     * the emoji is a catalog id or a token, not both; the emoji size is within EmojiMerge's range; a rebuild names an id.
     */
    public static Request parseRequest(String json) throws BadRequest {
        if (json == null || json.length() > MAX_REQUEST_CHARS) throw new BadRequest("bad_request", "The request could not be read.");
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            throw new BadRequest("bad_request", "The request could not be read.");
        }
        Request r = new Request();
        Object name = o.opt("name");
        if (!(name instanceof String)) throw new BadRequest("bad_name", "Give the font a name.");
        try {
            r.name = FontPackage.cleanName((String) name);
        } catch (Sfnt.FontException e) {
            throw new BadRequest(e.code, e.getMessage());
        }
        r.regular = face(o.opt("regular"));
        if (r.regular == null) throw new BadRequest("no_regular", "Choose the Regular font first.");
        r.bold = face(o.opt("bold"));
        Object em = o.opt("emoji");
        if (em instanceof JSONObject) {
            JSONObject e = (JSONObject) em;
            Object cat = e.opt("catalogId"), tok = e.opt("token");
            boolean hasCat = cat != null && cat != JSONObject.NULL, hasTok = tok != null && tok != JSONObject.NULL;
            if (hasCat == hasTok) throw new BadRequest("bad_request", "Choose one emoji font.");
            if (hasCat) {
                if (!(cat instanceof String) || !isCatalogId((String) cat)) throw new BadRequest("bad_request", "That emoji font is not in the list.");
                r.emojiCatalogId = (String) cat;
            } else {
                if (!(tok instanceof String) || !isToken((String) tok)) throw new BadRequest("bad_request", "Choose the emoji font file again.");
                r.emojiToken = (String) tok;
            }
        } else if (em != null && em != JSONObject.NULL) {
            throw new BadRequest("bad_request", "The request could not be read.");
        }
        Object sc = o.opt("emojiScale");
        if (sc != null && sc != JSONObject.NULL) {
            if (!(sc instanceof Number)) throw new BadRequest("bad_request", "The emoji size must be between 80% and 125%.");
            double s = ((Number) sc).doubleValue();
            if (!(s >= EmojiMerge.MIN_SCALE && s <= EmojiMerge.MAX_SCALE)) throw new BadRequest("bad_request", "The emoji size must be between 80% and 125%.");
            r.emojiScale = s;
        }
        Object rb = o.opt("rebuildId");
        if (rb != null && rb != JSONObject.NULL) {
            if (!(rb instanceof String) || !isId((String) rb)) throw new BadRequest("bad_request", "That font is not in My fonts.");
            r.rebuildId = (String) rb;
        }
        return r;
    }

    /** {token, coords} or null for a null / missing face; a face of the wrong shape is refused. */
    private static Face face(Object v) throws BadRequest {
        if (v == null || v == JSONObject.NULL) return null;
        if (!(v instanceof JSONObject)) throw new BadRequest("bad_request", "The request could not be read.");
        JSONObject f = (JSONObject) v;
        Object tok = f.opt("token");
        if (!(tok instanceof String) || !isToken((String) tok)) throw new BadRequest("bad_request", "Choose the font file again.");
        Object c = f.opt("coords");
        LinkedHashMap<String, Double> coords = null;
        if (c instanceof JSONObject) {
            JSONObject co = (JSONObject) c;
            if (co.length() > MAX_AXES) throw new BadRequest("bad_request", "The font style could not be read.");
            coords = new LinkedHashMap<String, Double>();
            Iterator<String> keys = co.keys();
            while (keys.hasNext()) {
                String tag = keys.next();
                Object val = co.opt(tag);
                if (!AXIS_TAG.matcher(tag).matches() || !(val instanceof Number)) throw new BadRequest("bad_request", "The font style could not be read.");
                double d = ((Number) val).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 32768) throw new BadRequest("bad_request", "The font style could not be read.");
                coords.put(tag, d);
            }
        } else if (c != null && c != JSONObject.NULL) {
            throw new BadRequest("bad_request", "The font style could not be read.");
        }
        return new Face((String) tok, coords);
    }

    // ---------------------------------------------------------------------------------------------
    // Library entries (meta.json)
    // ---------------------------------------------------------------------------------------------

    /** One font made here, as its meta.json holds it. */
    public static final class Entry {
        public String id, name, pkg, suffix;
        public int versionCode = 1;
        public long created, updated;
        public boolean hasBold;
        /** The emoji inside: a catalog id (or "own" for a file of the user's) and its name; null id when there are none. */
        public String emojiId, emojiName;
        public long apkSize, regularSize, boldSize;
        public String apkSha, regularSha, boldSha;
        /** Where each face came from: {regular:{fileName, coords}, bold:{fileName, coords}|null}; for the page's "Make again". */
        public JSONObject sources = new JSONObject();
        public final List<String> warnings = new ArrayList<String>();
    }

    public static JSONObject toMeta(Entry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("id", e.id).put("name", e.name).put("package", e.pkg).put("suffix", e.suffix);
        o.put("versionCode", e.versionCode).put("created", e.created).put("updated", e.updated);
        o.put("hasBold", e.hasBold);
        o.put("emoji", e.emojiId == null ? JSONObject.NULL : new JSONObject().put("id", e.emojiId).put("name", e.emojiName == null ? e.emojiId : e.emojiName));
        o.put("sizes", new JSONObject().put("apk", e.apkSize).put("regular", e.regularSize).put("bold", e.hasBold ? e.boldSize : 0));
        JSONObject sha = new JSONObject().put("apk", e.apkSha).put("regular", e.regularSha);
        if (e.hasBold) sha.put("bold", e.boldSha);
        o.put("sha256", sha);
        o.put("sources", e.sources == null ? new JSONObject() : e.sources);
        o.put("warnings", new JSONArray(e.warnings));
        return o;
    }

    /**
     * An entry from its meta.json, or null when anything is off: an id that is not the folder's, a package that is not the one its suffix
     * gives, a missing hash. A broken entry is left out of the list rather than shown half-read.
     */
    public static Entry fromMeta(JSONObject o, String folderId) {
        if (o == null || !isId(folderId)) return null;
        try {
            Entry e = new Entry();
            e.id = o.optString("id", "");
            if (!folderId.equals(e.id)) return null;
            e.name = o.optString("name", "").trim();
            e.suffix = o.optString("suffix", "");
            e.pkg = o.optString("package", "");
            if (e.name.isEmpty() || !FontPackage.isValidSuffix(e.suffix) || !FontPackage.packageName(e.suffix).equals(e.pkg)) return null;
            e.versionCode = o.optInt("versionCode", 0);
            if (e.versionCode < 1) return null;
            e.created = o.optLong("created", 0);
            e.updated = o.optLong("updated", e.created);
            e.hasBold = o.optBoolean("hasBold", false);
            JSONObject em = o.optJSONObject("emoji");
            if (em != null) {
                String eid = em.optString("id", "");
                if (!eid.isEmpty() && (isCatalogId(eid) || "own".equals(eid))) { e.emojiId = eid; e.emojiName = em.optString("name", eid); }
            }
            JSONObject sizes = o.optJSONObject("sizes"), sha = o.optJSONObject("sha256");
            if (sizes == null || sha == null) return null;
            e.apkSize = sizes.optLong("apk", -1);
            e.regularSize = sizes.optLong("regular", -1);
            e.boldSize = e.hasBold ? sizes.optLong("bold", -1) : 0;
            e.apkSha = sha.optString("apk", "");
            e.regularSha = sha.optString("regular", "");
            e.boldSha = e.hasBold ? sha.optString("bold", "") : null;
            if (e.apkSize <= 0 || e.regularSize <= 0 || (e.hasBold && e.boldSize <= 0)) return null;
            if (!isSha(e.apkSha) || !isSha(e.regularSha) || (e.hasBold && !isSha(e.boldSha))) return null;
            JSONObject src = o.optJSONObject("sources");
            e.sources = src == null ? new JSONObject() : src;
            JSONArray w = o.optJSONArray("warnings");
            if (w != null) for (int i = 0; i < w.length() && i < 50; i++) { String s = w.optString(i, ""); if (!s.isEmpty()) e.warnings.add(s); }
            return e;
        } catch (Sfnt.FontException bad) {
            return null;
        }
    }

    public static boolean isSha(String s) { return s != null && SHA256.matcher(s).matches(); }

    /** The entry in its folder, or null when it is missing or broken (its files must be there, at the sizes meta.json records). */
    public static Entry read(Dirs d, String id) {
        if (!isId(id)) return null;
        File dir = d.entryDir(id);
        File meta = new File(dir, META);
        if (!meta.isFile() || meta.length() > 1024 * 1024) return null;
        try {
            Entry e = fromMeta(new JSONObject(new String(Files.readAllBytes(meta.toPath()), "UTF-8")), id);
            if (e == null) return null;
            if (new File(dir, APK).length() != e.apkSize || new File(dir, REGULAR).length() != e.regularSize) return null;
            if (e.hasBold && new File(dir, BOLD).length() != e.boldSize) return null;
            return e;
        } catch (Exception bad) {
            return null;
        }
    }

    /** Every readable entry, the newest first (the last made or made again). Folders that are not an id (a staged one, say) are skipped. */
    public static List<Entry> list(Dirs d) {
        List<Entry> out = new ArrayList<Entry>();
        File[] kids = d.library.listFiles();
        if (kids == null) return out;
        for (File k : kids) {
            if (!k.isDirectory() || !isId(k.getName())) continue;
            Entry e = read(d, k.getName());
            if (e != null) out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                long x = Math.max(a.created, a.updated), y = Math.max(b.created, b.updated);
                return x != y ? (y > x ? 1 : -1) : a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** The entry whose package suffix this is, or null. */
    public static Entry bySuffix(List<Entry> entries, String suffix) {
        if (suffix == null || entries == null) return null;
        for (Entry e : entries) if (suffix.equals(e.suffix)) return e;
        return null;
    }

    /**
     * Who a font being made is: a new one gets {@code freshId} and a package suffix from its name; one made again ({@code rebuild}, the
     * request's rebuildId) keeps its id, suffix, package and created time, so it replaces the same entry and installs over the same package.
     * The versionCode is {@link #versionCodeFor}'s, once the caller knows what is installed of that package.
     */
    public static Entry entryFor(String name, Entry rebuild, String freshId, long now) throws Sfnt.FontException {
        Entry e = new Entry();
        e.id = rebuild != null ? rebuild.id : freshId;
        if (!isId(e.id)) throw new IllegalArgumentException("not a library id");
        e.name = name;
        e.suffix = rebuild != null ? rebuild.suffix : FontPackage.suffixFor(name, e.id);
        e.pkg = FontPackage.packageName(e.suffix);
        e.created = rebuild != null ? rebuild.created : now;
        e.updated = now;
        return e;
    }

    /**
     * The versionCode of a font being made: one above the entry's own when it is made again, and above the package's on the phone
     * ({@code installedVersion}, 0 when it is not installed), so it installs as an update; 1 for a new font with nothing installed.
     */
    public static int versionCodeFor(Entry rebuild, long installedVersion) throws Sfnt.FontException {
        int onPhone = (int) Math.max(0, Math.min(Integer.MAX_VALUE, installedVersion));
        if (rebuild != null) return FontPackage.nextVersionCode(rebuild.versionCode, onPhone);
        return onPhone > 0 ? FontPackage.nextVersionCode(1, onPhone) : 1;
    }

    /** A fresh folder to build an entry's files in, inside the library (so the final rename stays on one file system). */
    public static File stageDir(Dirs d, String id, Random r) throws IOException {
        if (!isId(id)) throw new IllegalArgumentException("not a library id");
        File s = new File(d.library, ".new-" + id + "-" + hexOf(r, 4));
        if (!s.mkdirs() && !s.isDirectory()) throw new IOException("The font could not be saved (no room for its folder).");
        return s;
    }

    /**
     * Puts a staged entry in place. Its files are flushed to the disk first and meta.json is written last (through a temp file), then the
     * folder is renamed to the id; an entry made again first moves its old folder aside and deletes it only once the new one is in place,
     * putting it back when the rename fails. A failure leaves the old entry as it was and nothing staged behind; a power cut leaves the old
     * entry or the whole new one, never a meta.json whose files did not reach the disk.
     */
    public static void commit(Dirs d, File staged, Entry e) throws IOException {
        if (!isId(e.id)) throw new IllegalArgumentException("not a library id");
        boolean done = false;
        try {
            File[] files = staged.listFiles();
            if (files != null) for (File f : files) if (f.isFile()) syncFile(f);
            try {
                writeAtomic(new File(staged, META), toMeta(e).toString(1).getBytes("UTF-8"));
            } catch (JSONException je) {
                throw new IOException("The font's details could not be written.");
            }
            File dest = d.entryDir(e.id);
            File aside = null;
            if (dest.exists()) {
                aside = new File(d.library, ".old-" + e.id + "-" + System.nanoTime());
                if (!dest.renameTo(aside)) throw new IOException("The font could not be saved (its old files are in use).");
            }
            if (!staged.renameTo(dest)) {
                if (aside != null && !aside.renameTo(dest)) deleteTree(aside);
                throw new IOException("The font could not be saved.");
            }
            done = true;
            syncDir(d.library);                                         // the rename itself reaches the disk before the old folder goes
            if (aside != null) deleteTree(aside);
        } finally {
            if (!done) deleteTree(staged);
        }
    }

    /**
     * Tidies the library after a crash or a power cut (the app runs it once, before anything else uses the library): staged folders are
     * deleted; a folder moved aside during a rebuild is put back when its entry is missing or did not survive whole, else deleted; an entry
     * whose files do not match its meta.json is deleted, since it is never listed and the page has no id to delete it with.
     */
    public static void sweep(Dirs d) {
        File[] kids = d.library.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String n = k.getName();
            if (n.startsWith(".new-")) deleteTree(k);
            else if (n.startsWith(".old-") && n.length() >= 5 + 12) {
                String id = n.substring(5, 17);
                File dest = isId(id) ? d.entryDir(id) : null;
                if (dest != null && dest.exists() && read(d, id) == null) deleteTree(dest);
                if (dest != null && !dest.exists() && k.renameTo(dest)) continue;
                deleteTree(k);
            }
        }
        kids = d.library.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.isDirectory() && isId(k.getName()) && read(d, k.getName()) == null) deleteTree(k);
    }

    /** Removes an entry's folder. False when it is not there. */
    public static boolean delete(Dirs d, String id) {
        if (!isId(id)) return false;
        File dir = d.entryDir(id);
        if (!dir.exists()) return false;
        File gone = new File(d.library, ".new-del-" + id + "-" + System.nanoTime());   // swept as a staged folder if the delete is cut short
        if (!dir.renameTo(gone)) gone = dir;
        deleteTree(gone);
        return true;
    }

    /** The file name an exported package is offered under: the display name without characters a file name cannot hold, then ".apk". */
    public static String exportName(String displayName) {
        String n = displayName == null ? "" : displayName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (n.startsWith(".")) n = n.substring(1).trim();
        if (n.isEmpty()) n = "Font";
        if (n.codePointCount(0, n.length()) > 80) n = n.substring(0, n.offsetByCodePoints(0, 80)).trim();
        return n + ".apk";
    }

    // ---------------------------------------------------------------------------------------------
    // The records of the font changer and the system emoji swap
    // ---------------------------------------------------------------------------------------------

    /** The package suffix the app last applied with the font changer ("" for Default, null when it never did or the record is unreadable). */
    public static String readApplied(Dirs d) {
        try {
            File f = d.changerRecord();
            if (!f.isFile() || f.length() > 4096) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            String s = o.optString("suffix", "");
            return s.isEmpty() || FontPackage.isValidSuffix(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Remembers what the phone was read back to show (a suffix, or "" / null for Default). */
    public static void writeApplied(Dirs d, String suffix, long now) {
        try {
            String s = suffix != null && FontPackage.isValidSuffix(suffix) ? suffix : "";
            writeAtomic(d.changerRecord(), new JSONObject().put("suffix", s).put("at", now).toString().getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    /**
     * The system emoji in use (until the restart it was applied in): which catalog font, the boot it was applied in, the files bound over,
     * and each file's SHA-256 before the bind ({@code originals}, what Undo must bring back; empty in the record written before the bind).
     */
    public static final class Swap {
        public final String id, bootId, sha256;
        public final List<String> targets;
        public final Map<String, String> originals;
        public Swap(String id, String bootId, String sha256, List<String> targets) {
            this(id, bootId, sha256, targets, null);
        }
        public Swap(String id, String bootId, String sha256, List<String> targets, Map<String, String> originals) {
            this.id = id; this.bootId = bootId; this.sha256 = sha256;
            this.targets = Collections.unmodifiableList(new ArrayList<String>(targets));
            this.originals = Collections.unmodifiableMap(originals == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(originals));
        }
    }

    public static void writeSwap(Dirs d, Swap s) throws IOException {
        try {
            JSONObject o = new JSONObject().put("id", s.id).put("boot", s.bootId).put("sha256", s.sha256).put("targets", new JSONArray(s.targets))
                    .put("originals", new JSONObject(s.originals));
            writeAtomic(d.swapRecord(), o.toString().getBytes("UTF-8"));
        } catch (JSONException e) {
            throw new IOException("The emoji swap could not be remembered.");
        }
    }

    /**
     * The files an Undo works on: this boot's recorded targets, then every other file the mount table shows a bind of the swap's own on (a
     * swap whose record was lost), each once. The undo script itself takes off only the swap's own binds.
     */
    public static List<String> undoTargets(Swap active, List<String> ownMounts) {
        List<String> out = new ArrayList<String>();
        if (active != null) for (String t : active.targets) if (!out.contains(t)) out.add(t);
        if (ownMounts != null) for (String t : ownMounts) if (t != null && !out.contains(t)) out.add(t);
        return out;
    }

    public static void clearSwap(Dirs d) { d.swapRecord().delete(); }

    /**
     * The swap applied since the phone last started, or null: none recorded, unreadable, or recorded in another boot (a restart undid it, so
     * a record from before is stale and reads as not in use). An unknown boot id is never "the same boot".
     */
    public static Swap activeSwap(Dirs d, String bootId) {
        Swap s = readSwap(d);
        if (s == null) return null;
        String b = bootId == null ? "" : bootId.trim();
        return !b.isEmpty() && b.equals(s.bootId) ? s : null;
    }

    /**
     * The swap record whatever boot it is from, or null. Apply and Undo go by {@link #activeSwap} instead: a record from another boot names
     * files whose binds that restart took away, and whatever is mounted there now is not the swap's.
     */
    public static Swap readSwap(Dirs d) {
        try {
            File f = d.swapRecord();
            if (!f.isFile() || f.length() > 64 * 1024) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            String id = o.optString("id", ""), boot = o.optString("boot", "").trim(), sha = o.optString("sha256", "");
            JSONArray t = o.optJSONArray("targets");
            if (!isCatalogId(id) || boot.isEmpty() || !isSha(sha) || t == null) return null;
            List<String> targets = new ArrayList<String>();
            for (int i = 0; i < t.length() && i < 64; i++) {
                String p = t.optString(i, "");
                if (p.startsWith("/") && p.indexOf('\n') < 0 && !p.contains("..")) targets.add(p);
            }
            Map<String, String> originals = new LinkedHashMap<String, String>();
            JSONObject or = o.optJSONObject("originals");
            if (or != null) for (String p : targets) if (isSha(or.optString(p, ""))) originals.put(p, or.optString(p));
            return new Swap(id, boot, sha, targets, originals);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Jobs (builds, downloads) and their Cancel
    // ---------------------------------------------------------------------------------------------

    /**
     * The running long jobs: one per kind and key (one build, one download per emoji font), each with a Cancel the engine's progress reads.
     * The app keeps one for the whole process, so a second window sees what the first one runs.
     */
    public static final class Jobs {
        /**
         * One job. {@link #uses} holds what it reads ("cat:noto", "tok:<token>", "id:<entry id>"), so a delete can refuse while it runs, and
         * what it writes ("make:<entry id>", a font made again).
         */
        public static final class Job {
            public final String id, kind, key;
            final Set<String> uses = new HashSet<String>();
            final Object owner;
            private volatile boolean cancelled;
            Job(String id, String kind, String key, Object owner) { this.id = id; this.kind = kind; this.key = key; this.owner = owner; }
            public boolean cancelled() { return cancelled; }
            public void cancel() { cancelled = true; }
        }

        private final Map<String, Job> byId = new LinkedHashMap<String, Job>();
        private int seq;

        /** A new job, or null when one of this kind and key is running (the page shows "busy"). */
        public synchronized Job start(String kind, String key, String... uses) {
            return startFor(null, null, kind, key, uses);
        }

        /**
         * {@link #start} for a window ({@code owner}: {@link #cancelAll(Object)} stops the jobs it started). Also null while a running job
         * uses {@code busyIf} (null: no such check), in the same step, so a font is not made again while it is installed, applied or saved,
         * and is not installed, applied or saved while it is made again.
         */
        public synchronized Job startFor(Object owner, String busyIf, String kind, String key, String... uses) {
            if (busyIf != null && inUse(busyIf)) return null;
            for (Job j : byId.values()) if (j.kind.equals(kind) && j.key.equals(key)) return null;
            Job j = new Job(kind + (++seq), kind, key, owner);
            for (String u : uses) if (u != null) j.uses.add(u);
            byId.put(j.id, j);
            return j;
        }

        public synchronized Job get(String id) { return id == null ? null : byId.get(id); }

        public synchronized Job running(String kind, String key) {
            for (Job j : byId.values()) if (j.kind.equals(kind) && j.key.equals(key)) return j;
            return null;
        }

        /**
         * The running job of this kind and key when {@code owner} started it and it is not cancelled: a second ask from that window waits
         * for its answer. Null when none runs, another window's runs (its answer goes only to that window) or a cancelled one has not ended.
         */
        public synchronized Job joinable(Object owner, String kind, String key) {
            Job j = running(kind, key);
            return j != null && owner != null && j.owner == owner && !j.cancelled() ? j : null;
        }

        /** Cancel by job id; false when it is not running (finished already, or never was). */
        public synchronized boolean cancel(String id) {
            Job j = get(id);
            if (j == null) return false;
            j.cancel();
            return true;
        }

        /** True while a running job reads this ("cat:<id>", "tok:<token>", "id:<entry id>"). */
        public synchronized boolean inUse(String what) {
            for (Job j : byId.values()) if (j.uses.contains(what)) return true;
            return false;
        }

        public synchronized void finish(Job j) { if (j != null) byId.remove(j.id); }

        /** Every running job is cancelled (they end at their next check). */
        public synchronized void cancelAll() { for (Job j : byId.values()) j.cancel(); }

        /** A window closes: the jobs it started are cancelled (another window's go on). Returns them, so their downloads can be disconnected. */
        public synchronized List<Job> cancelAll(Object owner) {
            List<Job> out = new ArrayList<Job>();
            for (Job j : byId.values()) if (owner != null && owner == j.owner) { j.cancel(); out.add(j); }
            return out;
        }

        public synchronized int size() { return byId.size(); }
    }

    /** Progress reaches the page at most about eight times a second, but always at a new stage and at the end of one. */
    public static final class Throttle {
        private final long gapMs;
        private String stage = null;
        private long at = Long.MIN_VALUE / 2;
        public Throttle(long gapMs) { this.gapMs = gapMs; }

        public synchronized boolean due(String s, long done, long total, long nowMs) {
            boolean go = stage == null || !stage.equals(s) || (total > 0 && done >= total) || nowMs - at >= gapMs;
            if (go) { stage = s; at = nowMs; }
            return go;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The JSON the page gets (design section 6)
    // ---------------------------------------------------------------------------------------------

    public static JSONObject error(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("code", code == null || code.isEmpty() ? "bad_font" : code).put("error", message == null || message.isEmpty() ? "Something went wrong." : message);
        } catch (JSONException ignored) {}
        return o;
    }

    /** What a picked font is: {family, subfamily, fullName, version, weight, width, italic, outlines, glyphs, upem, codepoints, latin, color, variable, axes, instances, guessRegular, guessBold, warnings}. */
    public static JSONObject infoJson(FontInspect.Info i) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("family", i.family).put("subfamily", i.subfamily).put("fullName", i.fullName).put("version", i.version);
        o.put("weight", i.weight).put("width", i.width).put("italic", i.italic);
        o.put("outlines", i.outlines).put("glyphs", i.glyphs).put("upem", i.upem).put("codepoints", i.codepoints).put("latin", i.latin);
        o.put("color", i.color).put("variable", i.variable);
        JSONArray axes = new JSONArray();
        for (FontInspect.Axis a : i.axes) {
            axes.put(new JSONObject().put("tag", a.tag).put("name", a.name == null || a.name.isEmpty() ? a.tag : a.name)
                    .put("min", num(a.min)).put("def", num(a.def)).put("max", num(a.max)).put("hidden", a.hidden));
        }
        o.put("axes", axes);
        JSONArray inst = new JSONArray();
        for (FontInspect.Instance x : i.instances) inst.put(new JSONObject().put("name", x.name).put("coords", coordsJson(x.coords)));
        o.put("instances", inst);
        o.put("guessRegular", coordsJson(i.guessRegular));
        o.put("guessBold", coordsJson(i.guessBold));
        o.put("warnings", new JSONArray(i.warnings));
        return o;
    }

    private static JSONObject coordsJson(Map<String, Double> c) throws JSONException {
        JSONObject o = new JSONObject();
        if (c != null) for (Map.Entry<String, Double> e : c.entrySet()) if (e.getValue() != null) o.put(e.getKey(), num(e.getValue()));
        return o;
    }

    /** A whole number as an integer (400, not 400.0), anything else to 1/1000. */
    private static Object num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return (long) v;
        return Math.round(v * 1000) / 1000.0;
    }

    /**
     * A row of My fonts: {id, name, pkg, versionCode, created, hasBold, emoji:{id,name}|null, sizes:{apk, regular, bold}, installed,
     * installedVersion, applied}. {@code installedVersion} is the versionCode on the phone, 0 when the package is not installed.
     */
    public static JSONObject entryJson(Entry e, long installedVersion, boolean applied) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", e.id).put("name", e.name).put("pkg", e.pkg).put("versionCode", e.versionCode);
        o.put("created", e.created).put("updated", e.updated).put("hasBold", e.hasBold);
        o.put("emoji", e.emojiId == null ? JSONObject.NULL : new JSONObject().put("id", e.emojiId).put("name", e.emojiName == null ? e.emojiId : e.emojiName));
        o.put("sizes", new JSONObject().put("apk", e.apkSize).put("regular", e.regularSize).put("bold", e.hasBold ? e.boldSize : 0));
        o.put("installed", installedVersion > 0).put("installedVersion", Math.max(0, installedVersion));
        o.put("applied", applied);
        return o;
    }

    /**
     * A row of the emoji downloads for a phone at API {@code sdk}: the build this phone gets (OpenMoji's COLRv0 one below Android 13), with
     * {id, name, version, format, mergeable, minApi, usable, size, sha256, license, licenseUrl, sourceUrl, attribution, note, downloaded}.
     */
    public static JSONObject catalogItemJson(EmojiCatalog.Item item, int sdk, boolean downloaded) throws JSONException {
        EmojiCatalog.Item b = item.forSdk(sdk);
        JSONObject o = new JSONObject();
        o.put("id", item.id).put("name", item.name).put("version", b.version).put("format", b.format).put("mergeable", item.mergeable);
        o.put("minApi", item.minApiForMerge).put("usable", item.usableForMerge(sdk));
        o.put("size", b.size).put("sha256", b.sha256);
        o.put("license", b.license).put("licenseUrl", b.licenseUrl).put("sourceUrl", b.sourceUrl).put("attribution", b.attribution).put("note", item.note);
        o.put("downloaded", downloaded);
        return o;
    }

    /**
     * Which build of a catalog font this kept file is (the main one or its lower-API fallback), by its exact size: null when it is neither
     * (missing, cut short, or not one of ours). The file was checked by SHA-256 when it was downloaded; what reads it for a build or a swap
     * checks the hash again.
     */
    public static EmojiCatalog.Item keptBuild(EmojiCatalog.Item item, File f) {
        if (item == null || f == null || !f.isFile()) return null;
        long n = f.length();
        if (n == item.size) return item;
        if (item.fallback != null && n == item.fallback.size) return item.fallback;
        return null;
    }

    /**
     * The answer for a download that failed other than by its checks or a Cancel: "http" with the {@code status} when the server answered
     * with one other than 200 (404 and 410: the file moved away, which no retry mends), "no_space" when the phone's storage filled up while it
     * was written, else "network" ({@code detail}: what failed, in words).
     */
    public static JSONObject downloadError(int status, String detail) {
        String d = detail == null ? "" : detail;
        if (status > 0) {
            JSONObject o = error("http", status == 404 || status == 410
                    ? "The file is no longer at its address (HTTP " + status + "). Update the app."
                    : "The server answered HTTP " + status + ". Try again later.");
            try {
                o.put("status", status);
            } catch (JSONException ignored) {}
            return o;
        }
        if (d.contains("ENOSPC") || d.contains("No space left")) return error("no_space", "There is not enough free space on this phone.");
        return error("network", "The download failed: " + (d.isEmpty() ? "no answer" : d));
    }

    /**
     * The answer for a download that failed on the phone's own storage where no errno says why (a folder that was not made, a rename that was
     * refused): "no_space" when less than 16 MB is free ({@code usableBytes}, of a folder that exists), else "failed" with {@code detail}.
     * Never "network": nothing went wrong with the connection.
     */
    public static JSONObject localError(String detail, long usableBytes) {
        if (usableBytes < (16L << 20)) return error("no_space", "There is not enough free space on this phone.");
        return error("failed", detail);
    }

    /** The Android API level a merged emoji font of this colour format needs to be drawn: COLRv1 from Android 13 (API 33), COLRv0 from 8. */
    public static int minApiForEmoji(String color) {
        return "COLRv1".equals(color) ? 33 : 26;
    }

    /**
     * Why an emoji font cannot be deleted now, as a code, or null when it can: "in_use" while it is the phone's system emoji (this boot),
     * "building" while a build reads it.
     */
    public static String emojiDeleteRefusal(String id, Swap active, Jobs jobs) {
        if (active != null && active.id.equals(id)) return "in_use";
        if (jobs != null && jobs.inUse("cat:" + id)) return "building";
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------

    /** Writes the bytes through a temp file renamed over f, so a reader sees the old file or the new one, never part of one. */
    public static void writeAtomic(File f, byte[] data) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir.getName());
        File tmp = new File(dir, "." + f.getName() + "." + System.nanoTime() + ".tmp");
        boolean ok = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
    }

    /** Flushes a file that was written and closed to the disk (fsync), so a power cut after this cannot leave it short or empty. */
    static void syncFile(File f) throws IOException {
        FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.WRITE);
        try {
            ch.force(true);
        } finally {
            ch.close();
        }
    }

    /** Flushes a folder's list of names (a rename in it) to the disk, where the system allows it; best effort. */
    static void syncDir(File dir) {
        try {
            FileChannel ch = FileChannel.open(dir.toPath(), StandardOpenOption.READ);
            try {
                ch.force(true);
            } finally {
                ch.close();
            }
        } catch (Exception notHere) {}                                  // a system that cannot open a folder this way: the rename stands as it is
    }

    /**
     * Copies at most {@code cap} bytes of {@code in} to {@code out}; one byte more is refused with the "too_big" code (the copy is the
     * caller's to delete). Returns the bytes copied.
     */
    public static long copyCapped(InputStream in, OutputStream out, long cap) throws Sfnt.FontException, IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) throw new Sfnt.FontException("too_big", "This file is larger than " + (cap >> 20) + " MB.");
            out.write(buf, 0, n);
        }
        return total;
    }

    /** A file's whole content (a font, at most {@link #MAX_PICK_BYTES}); "too_big" over that. */
    public static byte[] readFile(File f, long cap) throws IOException {
        long len = f.length();
        if (len > cap) throw new Sfnt.FontException("too_big", "This file is larger than " + (cap >> 20) + " MB.");
        byte[] data = new byte[(int) len];
        InputStream in = new FileInputStream(f);
        try {
            int at = 0;
            while (at < data.length) {
                int n = in.read(data, at, data.length - at);
                if (n < 0) throw new IOException("The file changed while it was read.");
                at += n;
            }
            if (in.read() >= 0) throw new IOException("The file changed while it was read.");
        } finally {
            in.close();
        }
        return data;
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md = sha();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    public static String sha256(byte[] b) { return hex(sha().digest(b)); }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { c[i * 2] = HEX[(b[i] >> 4) & 0xf]; c[i * 2 + 1] = HEX[b[i] & 0xf]; }
        return new String(c);
    }

    /** Deletes a file or a folder with everything in it; symbolic links are removed, not followed. */
    public static void deleteTree(File f) {
        if (f == null) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            // the names only (list(), not listFiles()), each made a File as it is reached
            String[] kids = f.list();
            if (kids != null) for (String k : kids) deleteTree(new File(f, k));
        }
        f.delete();
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
