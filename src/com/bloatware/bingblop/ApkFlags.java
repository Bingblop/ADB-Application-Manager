package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For the list of package files the Installer found: which are the same file twice (identical content), and which are an older version of a package that
 * is there in a newer one. Pure Java. A group of identical files keeps one (the newest, then the one with the shorter path) and marks the others; for each
 * package the highest version code is the newest and the lower ones are marked older. Nothing is deleted here: the page only offers to select them.
 */
public final class ApkFlags {
    private ApkFlags() {}

    public static final class Item {
        public String path;
        public long size, mtime;
        public String pkg = "";          // package name, "" when it could not be read (a bundle, a damaged file)
        public String versionName = "";
        public long versionCode = -1;    // -1 when unknown
        public String hash = "";         // content hash, "" when not computed
        // the result:
        public String duplicateOf;       // path of the copy that is kept, or null
        public boolean older;            // a newer version of the same package is in the list
        public long newestVersion = -1;  // that newer version code
        public String newestPath;        // the file that has it
    }

    /** Fills duplicateOf / older / newestVersion on the items. Items with the same content count as one copy for the version check. */
    public static void compute(List<Item> items) {
        for (Item it : items) { it.duplicateOf = null; it.older = false; it.newestVersion = -1; it.newestPath = null; }
        // identical content: same size and the same hash
        Map<String, List<Item>> byHash = new HashMap<String, List<Item>>();
        for (Item it : items) if (!it.hash.isEmpty()) {
            String k = it.size + ":" + it.hash;
            List<Item> l = byHash.get(k);
            if (l == null) { l = new ArrayList<Item>(); byHash.put(k, l); }
            l.add(it);
        }
        for (List<Item> g : byHash.values()) {
            if (g.size() < 2) continue;
            Collections.sort(g, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    if (a.mtime != b.mtime) return a.mtime > b.mtime ? -1 : 1;                   // the newest first
                    if (a.path.length() != b.path.length()) return a.path.length() - b.path.length();
                    return a.path.compareTo(b.path);
                }
            });
            Item keep = g.get(0);
            for (int i = 1; i < g.size(); i++) g.get(i).duplicateOf = keep.path;
        }
        // versions of one package
        Map<String, Item> newest = new HashMap<String, Item>();
        for (Item it : items) {
            if (it.pkg.isEmpty() || it.versionCode < 0 || it.duplicateOf != null) continue;
            Item cur = newest.get(it.pkg);
            if (cur == null || it.versionCode > cur.versionCode || (it.versionCode == cur.versionCode && it.mtime > cur.mtime)) newest.put(it.pkg, it);
        }
        for (Item it : items) {
            if (it.pkg.isEmpty() || it.versionCode < 0) continue;
            Item n = newest.get(it.pkg);
            if (n != null && n != it && n.versionCode > it.versionCode) { it.older = true; it.newestVersion = n.versionCode; it.newestPath = n.path; }
        }
    }

    /** SHA-256 of a file, hex. Reads at most {@code maxBytes}; returns "" when the file is bigger (not worth the time) or cannot be read. */
    public static String sha256(File f, long maxBytes) {
        if (f.length() > maxBytes) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally { in.close(); }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Hashes only the files whose size is shared with another file (a file with a size of its own has no twin). */
    public static void hashSameSizes(List<Item> items, long maxBytesEach, long deadlineMs) {
        Map<Long, List<Item>> bySize = new HashMap<Long, List<Item>>();
        for (Item it : items) if (it.size > 0) {
            List<Item> l = bySize.get(it.size);
            if (l == null) { l = new ArrayList<Item>(); bySize.put(it.size, l); }
            l.add(it);
        }
        for (List<Item> g : bySize.values()) {
            if (g.size() < 2) continue;
            for (Item it : g) {
                if (System.currentTimeMillis() > deadlineMs) return;
                it.hash = sha256(new File(it.path), maxBytesEach);
            }
        }
    }
}
