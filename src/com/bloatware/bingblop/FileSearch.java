package com.bloatware.bingblop;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Search for files by name, ending, size, date and kind, by what is inside a file ({@code content:}) and by the names of entries inside archives
 * ({@code archive:}). Pure Java: the query is parsed here, the walk is bounded (results, files looked at, time, depth), can be cancelled, reports
 * progress, never follows a link to a folder, and reads only text of a limited size.
 *
 * <pre>
 *   photo                      a name that contains "photo" (case ignored; * and ? are wildcards; "two words" in quotes; -word leaves a name out)
 *   ext:jpg,png   .pdf         endings
 *   size:&gt;10mb  size:1k..5m     bigger, smaller, between (b k kb m mb g gb t)
 *   date:today  date:7d  date:2025-03-01..2025-03-31  date:&lt;2024-01-01      modified today / in the last 7 days / between / before
 *   type:image|video|audio|text|doc|archive|apk|font|folder|file
 *   content:word               files whose text has the word (text files up to 4 MB; with archives on, text entries up to 1 MB too)
 *   archive:name               entries inside archives whose name has "name"
 * </pre>
 */
public final class FileSearch {
    private FileSearch() {}

    public static final class Query {
        public final List<String> names = new ArrayList<String>();        // all must match (substring or wildcard)
        public final List<String> notNames = new ArrayList<String>();
        public final Set<String> exts = new HashSet<String>();            // lower case, no dot
        public long minSize = -1, maxSize = -1;
        public long minTime = Long.MIN_VALUE, maxTime = Long.MAX_VALUE;
        public final Set<String> types = new HashSet<String>();
        public final List<String> content = new ArrayList<String>();      // all must be in the file
        public final List<String> archive = new ArrayList<String>();      // entry names
        public boolean includeHidden = true, recursive = true, inArchives = false;
        public final List<String> problems = new ArrayList<String>();     // what could not be understood (the rest still works)

        public boolean isEmpty() {
            return names.isEmpty() && notNames.isEmpty() && exts.isEmpty() && minSize < 0 && maxSize < 0 && minTime == Long.MIN_VALUE && maxTime == Long.MAX_VALUE
                    && types.isEmpty() && content.isEmpty() && archive.isEmpty();
        }
    }

    public static final class Hit {
        public String path;                 // the file or folder; for an archive entry the archive
        public String entry;                // the entry inside the archive, or null
        public boolean dir;
        public long size, mtime;
        public String line;                 // a line that matched content:, trimmed
        public int lineNo;
        public String why;                  // "name", "content", "archive"
    }

    public static final class Limits {
        public int maxResults = 1000;
        public int maxVisited = 400000;
        public int maxDepth = 40;
        public long deadlineMs = Long.MAX_VALUE;
        public long contentMaxBytes = 4L * 1024 * 1024;
        public long entryContentMaxBytes = 1024 * 1024;
        public int maxArchiveEntries = 100000;
        public volatile boolean cancelled;
        public boolean hitLimit;            // a limit (not Cancel) stopped it
        public int visited, archivesRead;
    }

    public interface Progress {
        /** Called now and then with the folder being looked at; return false to stop. */
        boolean onProgress(String folder, int visited, int found);
    }

    // ---------------------------------------------------------------------------------------------
    // the query
    // ---------------------------------------------------------------------------------------------

    /** Splits on spaces, keeping "quoted text" (also after key:) together. */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"') { quoted = !quoted; continue; }
            if (Character.isWhitespace(c) && !quoted) { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } continue; }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public static Query parse(String text, long now, TimeZone tz) {
        Query q = new Query();
        if (text == null) return q;
        for (String t : tokens(text)) {
            int colon = t.indexOf(':');
            String key = colon > 0 ? t.substring(0, colon).toLowerCase(Locale.US) : "";
            String val = colon > 0 ? t.substring(colon + 1) : t;
            switch (key) {
                case "ext": case "type": case "size": case "date": case "content": case "archive":
                    if (val.isEmpty()) { q.problems.add(key + ": needs a value"); continue; }
                    break;
                default:
                    key = ""; val = t;
            }
            if (key.isEmpty()) {
                if (val.startsWith(".") && val.length() > 1 && val.indexOf('*') < 0 && val.indexOf('?') < 0 && val.lastIndexOf('.') == 0) q.exts.add(val.substring(1).toLowerCase(Locale.US));
                else if (val.startsWith("-") && val.length() > 1) q.notNames.add(val.substring(1).toLowerCase(Locale.US));
                else q.names.add(val.toLowerCase(Locale.US));
            } else if (key.equals("ext")) {
                for (String e : val.split(",")) { e = e.trim().toLowerCase(Locale.US); if (e.startsWith(".")) e = e.substring(1); if (!e.isEmpty()) q.exts.add(e); }
            } else if (key.equals("type")) {
                for (String e : val.split("[,|]")) {
                    e = e.trim().toLowerCase(Locale.US);
                    if (KNOWN_TYPES.contains(e)) q.types.add(e); else if (!e.isEmpty()) q.problems.add("type: " + e + " is not a kind I know");
                }
            } else if (key.equals("size")) {
                if (!parseSize(val, q)) q.problems.add("size: could not read " + val);
            } else if (key.equals("date")) {
                if (!parseDate(val, q, now, tz)) q.problems.add("date: could not read " + val);
            } else if (key.equals("content")) {
                q.content.add(val.toLowerCase(Locale.US));
            } else if (key.equals("archive")) {
                q.archive.add(val.toLowerCase(Locale.US));
            }
        }
        return q;
    }

    private static final Set<String> KNOWN_TYPES = new HashSet<String>(java.util.Arrays.asList("image", "video", "audio", "text", "doc", "archive", "apk", "font", "folder", "file"));

    static long parseBytes(String s) {
        java.util.regex.Matcher m = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)$").matcher(s.trim());
        if (!m.matches()) return -1;
        double n = Double.parseDouble(m.group(1));
        String u = m.group(2).toLowerCase(Locale.US);
        double mul;
        switch (u) {
            case "": case "b": mul = 1; break;
            case "k": case "kb": case "kib": mul = 1024; break;
            case "m": case "mb": case "mib": mul = 1024.0 * 1024; break;
            case "g": case "gb": case "gib": mul = 1024.0 * 1024 * 1024; break;
            case "t": case "tb": case "tib": mul = 1024.0 * 1024 * 1024 * 1024; break;
            default: return -1;
        }
        return (long) (n * mul);
    }

    static boolean parseSize(String v, Query q) {
        int dots = v.indexOf("..");
        if (dots > 0) {
            long a = parseBytes(v.substring(0, dots)), b = parseBytes(v.substring(dots + 2));
            if (a < 0 || b < 0) return false;
            q.minSize = Math.min(a, b); q.maxSize = Math.max(a, b);
            return true;
        }
        String op = "=";
        if (v.startsWith(">=") || v.startsWith("<=")) { op = v.substring(0, 2); v = v.substring(2); }
        else if (v.startsWith(">") || v.startsWith("<") || v.startsWith("=")) { op = v.substring(0, 1); v = v.substring(1); }
        long n = parseBytes(v);
        if (n < 0) return false;
        switch (op) {
            case ">": q.minSize = n + 1; break;
            case ">=": q.minSize = n; break;
            case "<": q.maxSize = n - 1; break;
            case "<=": q.maxSize = n; break;
            default: q.minSize = n; q.maxSize = n;
        }
        return true;
    }

    private static long startOfDay(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long addDays(long t, int d, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_YEAR, d);
        return c.getTimeInMillis();
    }

    /** A day "2025-03-01" as its start, or -1. */
    private static long day(String s, TimeZone tz) {
        java.util.regex.Matcher m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(s.trim());
        if (!m.matches()) return -1;
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.setLenient(false);
        try { c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)), 0, 0, 0); return c.getTimeInMillis(); }
        catch (IllegalArgumentException e) { return -1; }
    }

    static boolean parseDate(String v, Query q, long now, TimeZone tz) {
        String low = v.toLowerCase(Locale.US);
        long today = startOfDay(now, tz);
        switch (low) {
            case "today": q.minTime = today; return true;
            case "yesterday": q.minTime = addDays(today, -1, tz); q.maxTime = today - 1; return true;
            case "week": q.minTime = addDays(today, -6, tz); return true;
            case "month": q.minTime = addDays(today, -29, tz); return true;
            case "year": q.minTime = addDays(today, -364, tz); return true;
            default: break;
        }
        java.util.regex.Matcher rel = Pattern.compile("^(\\d{1,4})([dwmy])$").matcher(low);       // in the last N days / weeks / months / years
        if (rel.matches()) {
            int n = Integer.parseInt(rel.group(1));
            int days = n * (rel.group(2).equals("d") ? 1 : rel.group(2).equals("w") ? 7 : rel.group(2).equals("m") ? 30 : 365);
            q.minTime = addDays(today, -(days - 1), tz);
            return true;
        }
        int dots = v.indexOf("..");
        if (dots > 0) {
            long a = day(v.substring(0, dots), tz), b = day(v.substring(dots + 2), tz);
            if (a < 0 || b < 0) return false;
            q.minTime = Math.min(a, b); q.maxTime = addDays(Math.max(a, b), 1, tz) - 1;
            return true;
        }
        String op = "=";
        String d = v;
        if (v.startsWith(">=") || v.startsWith("<=")) { op = v.substring(0, 2); d = v.substring(2); }
        else if (v.startsWith(">") || v.startsWith("<") || v.startsWith("=")) { op = v.substring(0, 1); d = v.substring(1); }
        long s = day(d, tz);
        if (s < 0) return false;
        long end = addDays(s, 1, tz) - 1;
        switch (op) {
            case ">": q.minTime = end + 1; break;                  // after that day
            case ">=": q.minTime = s; break;
            case "<": q.maxTime = s - 1; break;                    // before that day
            case "<=": q.maxTime = end; break;
            default: q.minTime = s; q.maxTime = end;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // kinds of file
    // ---------------------------------------------------------------------------------------------

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i <= 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.US);
    }

    private static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "ico", "raw", "dng");
    private static final Set<String> VIDEO = set("mp4", "m4v", "3gp", "mkv", "webm", "mov", "avi", "wmv", "flv", "mpg", "mpeg");
    private static final Set<String> AUDIO = set("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "wma");
    private static final Set<String> TEXT = set("txt", "md", "markdown", "log", "json", "xml", "html", "htm", "css", "js", "mjs", "ts", "java", "kt", "kts", "py", "sh", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "rb", "php",
            "sql", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "prop", "gradle", "csv", "tsv", "srt", "vtt", "rc", "env", "bat", "ps1", "tex", "smali", "list", "lst");
    private static final Set<String> DOC = set("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "mobi", "djvu", "pages", "numbers", "key");
    private static final Set<String> ARCHIVE = set("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "lz4", "tgz", "tbz2", "txz", "jar", "aar", "war", "cab", "iso", "lzma");
    private static final Set<String> APK = set("apk", "apks", "apkm", "xapk");
    private static final Set<String> FONT = set("ttf", "otf", "ttc", "woff", "woff2");
    /** Archives whose entry names can be read here (zip format). */
    static final Set<String> ZIPS = set("zip", "apk", "apks", "apkm", "xapk", "jar", "aar", "war", "epub", "docx", "xlsx", "pptx", "odt", "ods", "odp", "cbz", "xpi", "crx", "kmz", "whl", "nupkg", "vsix", "mrpack", "3mf");

    private static Set<String> set(String... a) { return new HashSet<String>(java.util.Arrays.asList(a)); }

    public static String kindOf(String name, boolean dir) {
        if (dir) return "folder";
        String e = ext(name);
        if (APK.contains(e)) return "apk";
        if (IMAGE.contains(e)) return "image";
        if (VIDEO.contains(e)) return "video";
        if (AUDIO.contains(e)) return "audio";
        if (DOC.contains(e)) return "doc";
        if (ARCHIVE.contains(e)) return "archive";
        if (FONT.contains(e)) return "font";
        if (TEXT.contains(e)) return "text";
        return "file";
    }

    private static boolean typeOk(Query q, String name, boolean dir) {
        if (q.types.isEmpty()) return true;
        String k = kindOf(name, dir);
        if (q.types.contains(k)) return true;
        if (q.types.contains("file") && !dir) return true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // matching
    // ---------------------------------------------------------------------------------------------

    private static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') sb.append(".*"); else if (c == '?') sb.append('.'); else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    static boolean nameHas(String lowerName, String term) {
        if (term.indexOf('*') >= 0 || term.indexOf('?') >= 0) return glob(term).matcher(lowerName).matches() || glob("*" + term + "*").matcher(lowerName).matches();
        return lowerName.contains(term);
    }

    private static boolean nameOk(Query q, String name) {
        String low = name.toLowerCase(Locale.US);
        for (String t : q.names) if (!nameHas(low, t)) return false;
        for (String t : q.notNames) if (nameHas(low, t)) return false;
        if (!q.exts.isEmpty() && !q.exts.contains(ext(name))) return false;
        return true;
    }

    private static boolean metaOk(Query q, long size, long mtime, boolean dir) {
        if (!dir && q.minSize >= 0 && size < q.minSize) return false;
        if (!dir && q.maxSize >= 0 && size > q.maxSize) return false;
        if (dir && (q.minSize >= 0 || q.maxSize >= 0)) return false;               // a size says nothing about a folder
        return mtime >= q.minTime && mtime <= q.maxTime;
    }

    /** First line of the stream that holds every term (case ignored), as {lineNo, line}; null when none or when it is not text. Reads at most maxBytes. */
    static Object[] findInText(InputStream in, List<String> terms, long maxBytes) throws IOException {
        byte[] head = new byte[8192];
        java.io.PushbackInputStream pin = new java.io.PushbackInputStream(in, head.length);
        int n = pin.read(head);
        if (n <= 0) return null;
        if (!FileOps.looksLikeText(head, n, n == head.length)) return null;
        pin.unread(head, 0, n);
        BufferedReader r = new BufferedReader(new InputStreamReader(pin, StandardCharsets.UTF_8), 32768);
        // every term must be in the file, the line shown is the first one that has the first term
        boolean[] seen = new boolean[terms.size()];
        int left = terms.size(), lineNo = 0, firstLine = 0;
        String firstText = null;
        long read = 0;
        String ln;
        while ((ln = r.readLine()) != null) {
            lineNo++;
            read += ln.length() + 1;
            if (read > maxBytes) break;
            String low = ln.toLowerCase(Locale.US);
            for (int i = 0; i < seen.length; i++) {
                if (!seen[i] && low.contains(terms.get(i))) {
                    seen[i] = true; left--;
                    if (firstText == null) { firstText = ln; firstLine = lineNo; }
                }
            }
            if (left == 0) break;
        }
        if (left > 0) return null;
        String t = firstText.trim();
        return new Object[]{firstLine, t.length() > 160 ? t.substring(0, 160) : t};
    }

    // ---------------------------------------------------------------------------------------------
    // the walk
    // ---------------------------------------------------------------------------------------------

    private static final class Run {
        final Query q; final Limits lim; final Progress progress; final List<Hit> hits = new ArrayList<Hit>();
        long lastTick;
        Run(Query q, Limits lim, Progress p) { this.q = q; this.lim = lim; this.progress = p; }
        boolean stop() {
            if (lim.cancelled) return true;
            if (hits.size() >= lim.maxResults || lim.visited >= lim.maxVisited || System.currentTimeMillis() > lim.deadlineMs) { lim.hitLimit = true; return true; }
            return false;
        }
        void tick(String folder) {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            if (now - lastTick < 120) return;
            lastTick = now;
            if (!progress.onProgress(folder, lim.visited, hits.size())) lim.cancelled = true;
        }
    }

    /** Searches under each root (a root that is a file is looked at by itself). Hits come back in the order found. */
    public static List<Hit> run(List<File> roots, Query q, Limits lim, Progress progress) {
        Run r = new Run(q, lim, progress);
        for (File root : roots) {
            if (r.stop()) break;
            if (!root.exists()) continue;
            if (root.isDirectory()) walk(root, 0, r, true); else look(root, r);
        }
        if (progress != null) progress.onProgress("", lim.visited, r.hits.size());
        return r.hits;
    }

    private static boolean skipDir(File d) {
        String p = d.getPath();
        return p.equals("/proc") || p.equals("/sys") || p.equals("/dev") || p.startsWith("/proc/") || p.startsWith("/sys/") || p.startsWith("/dev/");
    }

    private static void walk(File dir, int depth, Run r, boolean top) {
        if (r.stop()) return;
        if (skipDir(dir)) return;
        r.tick(dir.getPath());
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays_sort(kids);
        List<File> subs = new ArrayList<File>();
        for (File k : kids) {
            if (r.stop()) return;
            if (!r.q.includeHidden && k.getName().startsWith(".")) continue;
            r.lim.visited++;
            boolean link = Files.isSymbolicLink(k.toPath());
            boolean d = !link && k.isDirectory();
            if (d) {
                if (r.q.recursive && depth < r.lim.maxDepth) subs.add(k);
                lookDir(k, r);
            } else {
                look(k, r);
            }
        }
        for (File s : subs) { if (r.stop()) return; walk(s, depth + 1, r, false); }
    }

    private static void Arrays_sort(File[] a) {
        java.util.Arrays.sort(a, new java.util.Comparator<File>() {
            @Override public int compare(File x, File y) { return x.getName().compareToIgnoreCase(y.getName()); }
        });
    }

    private static void lookDir(File d, Run r) {
        Query q = r.q;
        if (!q.content.isEmpty() || !q.archive.isEmpty()) return;            // these are about what is inside a file
        if (!typeOk(q, d.getName(), true) || !nameOk(q, d.getName())) return;
        if (q.isEmpty()) return;                                              // an empty query lists nothing (not every file of the phone)
        if (!metaOk(q, 0, d.lastModified(), true)) return;
        Hit h = new Hit();
        h.path = d.getPath(); h.dir = true; h.mtime = d.lastModified(); h.why = "name";
        r.hits.add(h);
    }

    private static void look(File f, Run r) {
        Query q = r.q;
        if (r.stop()) return;
        String name = f.getName();
        if (!Files.isRegularFile(f.toPath()) && !Files.isSymbolicLink(f.toPath())) return;      // pipes, sockets, devices
        boolean zip = ZIPS.contains(ext(name));
        long size = f.length(), mtime = f.lastModified();

        // archive entries: by archive: terms, and (with archives on) by the name terms and the text of entries
        if (zip && (!q.archive.isEmpty() || q.inArchives) && size > 0 && size < 4L * 1024 * 1024 * 1024) {
            searchZip(f, size, mtime, r);
        }
        if (!q.archive.isEmpty()) return;                                      // archive: asks about entries only

        if (q.isEmpty()) return;
        if (!typeOk(q, name, false) || !nameOk(q, name) || !metaOk(q, size, mtime, false)) return;
        Hit h = new Hit();
        h.path = f.getPath(); h.size = size; h.mtime = mtime; h.why = "name";
        if (!q.content.isEmpty()) {
            if (size == 0 || size > r.lim.contentMaxBytes || ARCHIVE.contains(ext(name)) || IMAGE.contains(ext(name)) || VIDEO.contains(ext(name)) || AUDIO.contains(ext(name))) return;
            try {
                InputStream in = new java.io.FileInputStream(f);
                try {
                    Object[] m = findInText(in, q.content, r.lim.contentMaxBytes);
                    if (m == null) return;
                    h.lineNo = (Integer) m[0]; h.line = (String) m[1]; h.why = "content";
                } finally { in.close(); }
            } catch (IOException e) { return; }
        }
        r.hits.add(h);
    }

    private static void searchZip(File f, long size, long mtime, Run r) {
        Query q = r.q;
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            r.lim.archivesRead++;
            Enumeration<? extends ZipEntry> en = z.entries();
            int n = 0;
            while (en.hasMoreElements()) {
                if (r.stop()) return;
                ZipEntry e = en.nextElement();
                if (++n > r.lim.maxArchiveEntries) { r.lim.hitLimit = true; return; }
                String full = e.getName();
                String base = full.endsWith("/") ? full.substring(0, full.length() - 1) : full;
                base = base.substring(base.lastIndexOf('/') + 1);
                boolean dir = e.isDirectory();
                String low = full.toLowerCase(Locale.US);
                boolean ok = true;
                // the terms of archive: are looked for in the whole entry path
                for (String t : q.archive) if (!nameHas(low, t)) { ok = false; break; }
                if (ok && q.archive.isEmpty()) {                               // archives on, plain query: the usual name rules on the entry
                    if (!typeOk(q, base, dir) || !nameOk(q, base)) ok = false;
                    else if (q.isEmpty()) ok = false;
                }
                if (ok) {
                    long es = e.getSize(), et = e.getTime();
                    if (q.archive.isEmpty() && !metaOk(q, es, et < 0 ? mtime : et, dir)) ok = false;
                    if (ok && !q.archive.isEmpty()) { if (!typeOk(q, base, dir) || !nameOk(q, base) && (!q.names.isEmpty() || !q.exts.isEmpty())) ok = false; }
                    if (ok && !q.archive.isEmpty() && !metaOk(q, es, et < 0 ? mtime : et, dir)) ok = false;
                    if (ok && !q.content.isEmpty()) {
                        if (dir || es < 0 || es > r.lim.entryContentMaxBytes || es == 0) ok = false;
                        else {
                            InputStream in = z.getInputStream(e);
                            try {
                                Object[] m = findInText(in, q.content, r.lim.entryContentMaxBytes);
                                if (m == null) ok = false;
                                else {
                                    Hit h = new Hit();
                                    h.path = f.getPath(); h.entry = full; h.dir = dir; h.size = es; h.mtime = et < 0 ? mtime : et; h.lineNo = (Integer) m[0]; h.line = (String) m[1]; h.why = "content";
                                    r.hits.add(h);
                                    ok = false;
                                }
                            } finally { in.close(); }
                        }
                    }
                }
                if (ok) {
                    Hit h = new Hit();
                    h.path = f.getPath(); h.entry = full; h.dir = dir; h.size = Math.max(0, e.getSize()); h.mtime = e.getTime() < 0 ? mtime : e.getTime(); h.why = "archive";
                    r.hits.add(h);
                }
            }
        } catch (IOException | RuntimeException e) {
            // a damaged or unreadable archive is simply skipped
        } finally {
            if (z != null) try { z.close(); } catch (IOException ignored) {}
        }
    }

    /** A short sentence for the problems of a query (empty when it was understood). */
    public static String problemText(Query q) {
        return q.problems.isEmpty() ? "" : String.join("; ", q.problems);
    }

    public static List<Hit> sortedByPath(List<Hit> hits) {
        List<Hit> c = new ArrayList<Hit>(hits);
        Collections.sort(c, new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { int x = a.path.compareToIgnoreCase(b.path); return x != 0 ? x : (a.entry == null ? "" : a.entry).compareTo(b.entry == null ? "" : b.entry); }
        });
        return c;
    }
}
