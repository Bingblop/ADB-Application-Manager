package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the extra payload of an XAPK. An XAPK is a ZIP holding one or more APKs (a base plus optional
 * splits), a manifest.json, and - for games and big apps - data files that Android expects outside the
 * APK: OBB expansion files under Android/obb/&lt;package&gt;/ (and sometimes Android/data/&lt;package&gt;/).
 *
 * The installer extracts the APKs itself; this class only finds the data files and decides where each
 * one may go. Pure java.io / java.util.zip / org.json so it can be unit-tested off-device.
 */
public final class XapkInfo {
    private XapkInfo() {}

    /** A data file inside the archive, with the folder it belongs in relative to the storage root. */
    public static final class Extra {
        public final String entry;   // entry name inside the ZIP
        public final String dest;    // normalized, e.g. Android/obb/com.foo/main.12.com.foo.obb
        public final long size;      // uncompressed size, or -1 when unknown

        Extra(String entry, String dest, long size) {
            this.entry = entry;
            this.dest = dest;
            this.size = size;
        }
    }

    /** Everything found in one archive. */
    public static final class Info {
        public JSONObject manifest;                        // manifest.json, or null
        public final List<Extra> candidates = new ArrayList<Extra>();

        /** True when this looks like an XAPK (its manifest says so, or it carries OBB/data payload). */
        public boolean isXapk() {
            if (manifest != null && (manifest.has("xapk_version") || manifest.has("split_apks") || manifest.has("expansions"))) return true;
            return !candidates.isEmpty();
        }

        /** The data files that really belong to {@code pkg} (a wrong package folder is dropped). */
        public List<Extra> forPackage(String pkg) {
            List<Extra> out = new ArrayList<Extra>();
            if (pkg == null || pkg.isEmpty()) return out;
            for (Extra x : candidates) if (belongsTo(x.dest, pkg)) out.add(x);
            return out;
        }
    }

    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /**
     * Normalizes an install path from an archive to a path relative to the storage root, or null when it
     * is not a safe Android/obb or Android/data location. Accepts a leading slash and the common storage
     * prefixes (sdcard/, storage/emulated/0/, storage/self/primary/); rejects empty, "." and ".." segments.
     */
    public static String normalizeDest(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/').trim().replaceAll("^/+", "");
        String low = p.toLowerCase(Locale.US);
        String[] prefixes = { "sdcard/", "storage/emulated/0/", "storage/self/primary/" };
        for (String pre : prefixes) {
            if (low.startsWith(pre)) { p = p.substring(pre.length()); break; }
        }
        String[] segs = p.split("/", -1);
        // Android / obb|data / <package> / <file...>: a destination is a file inside the package's folder
        if (segs.length < 4) return null;
        if (!segs[0].equalsIgnoreCase("Android")) return null;
        if (!segs[1].equalsIgnoreCase("obb") && !segs[1].equalsIgnoreCase("data")) return null;
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
        }
        segs[0] = "Android";
        segs[1] = segs[1].toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(segs[i]);
        }
        return sb.toString();
    }

    /** True when the normalized dest sits in Android/obb/&lt;pkg&gt;/... or Android/data/&lt;pkg&gt;/... */
    public static boolean belongsTo(String dest, String pkg) {
        if (dest == null || pkg == null) return false;
        String[] segs = dest.split("/");
        return segs.length >= 4 && segs[2].equals(pkg);
    }

    /** Scans the archive's central directory (no extraction) for data files and its manifest.json. */
    public static Info inspect(File zip) {
        Info info = new Info();
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry me = zf.getEntry("manifest.json");
            if (me != null && me.getSize() >= 0 && me.getSize() <= MAX_MANIFEST_BYTES) {
                InputStream in = zf.getInputStream(me);
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    info.manifest = new JSONObject(bo.toString("UTF-8"));
                } catch (Exception ignored) {
                    info.manifest = null;
                } finally {
                    in.close();
                }
            }

            // manifest "expansions": [{file, install_location, install_path}] maps an entry to its destination
            Map<String, String> declared = new HashMap<String, String>();
            if (info.manifest != null) {
                JSONArray ex = info.manifest.optJSONArray("expansions");
                if (ex != null) {
                    for (int i = 0; i < ex.length(); i++) {
                        JSONObject o = ex.optJSONObject(i);
                        if (o == null) continue;
                        String file = o.optString("file", "");
                        if (file.isEmpty()) continue;
                        String path = o.optString("install_path", "");
                        declared.put(file, path.isEmpty() ? file : path);
                    }
                }
            }

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String raw = declared.get(name);
                if (raw == null) {
                    String low = name.toLowerCase(Locale.US);
                    if (low.startsWith("android/obb/") || low.startsWith("android/data/")) raw = name;
                }
                if (raw == null) continue;
                String dest = normalizeDest(raw);
                if (dest != null) info.candidates.add(new Extra(name, dest, e.getSize()));
            }
        } catch (Exception ignored) {
            // not a readable ZIP central directory: no data files to offer
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return info;
    }

    /** Human size, e.g. "1.2 GB". */
    public static String humanBytes(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }
}
