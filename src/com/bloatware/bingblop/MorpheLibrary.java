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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The patched APKs of the Morphe Patcher tab ("Patched APKs"): one folder per patched app and run under the app's own storage, with the APK, its
 * meta.json (what was patched, with which patches and which bundle versions, when) and the log of the run. Also copies a patched APK to a
 * folder the person can reach (Downloads/Morphe Patcher) and bundles the split APKs of an installed app into one .apks file for the engine.
 * Pure java + org.json, so it is unit-testable off-device.
 */
public final class MorpheLibrary {
    private final File dir;

    public MorpheLibrary(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private static String slug(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 60; i++) {
            char c = s.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return b.length() == 0 ? "x" : b.toString();
    }

    private File folder(String id) throws IOException {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) throw new IOException("bad id");
        File f = new File(dir, id);
        if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new IOException("bad id");
        return f;
    }

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally { in.close(); }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /**
     * Files a freshly patched APK. The APK is moved (not copied) into the library; meta is what the page knows about the run
     * (app, bundles, patches, mode ...) and is completed here with id, file, size, sha256 and patchedAt. The log may be null.
     */
    public synchronized JSONObject add(File apk, JSONObject meta, File log) throws IOException {
        return add(apk, meta, log, null);
    }

    /** As {@link #add(File, JSONObject, File)}, with the APK itself put in apkDir (Downloads/Morphe Patcher) when that is not null; its meta and log stay here. */
    public synchronized JSONObject add(File apk, JSONObject meta, File log, File apkDir) throws IOException {
        String pkg = meta.optString("pkg", "app");
        String ver = meta.optString("versionName", "");
        long stamp = System.currentTimeMillis();
        String id = stamp + "-" + slug(pkg);
        while (folder(id).exists()) id = (++stamp) + "-" + slug(pkg);                      // a second filing in the same millisecond (or a retry after a failed one) must not land in the same folder
        File f = folder(id);
        if (!f.mkdirs()) throw new IOException("cannot create " + f);
        String name = slug(pkg) + (ver.isEmpty() ? "" : "_" + slug(ver)) + "-patched.apk";
        File dest = new File(f, name);
        try {
            if (apkDir != null) {
                if (!apkDir.isDirectory() && !apkDir.mkdirs()) throw new IOException("cannot create " + apkDir);
                dest = new File(apkDir, name);
                String base = name.substring(0, name.length() - 4);
                for (int n = 1; dest.exists(); n++) dest = new File(apkDir, base + " (" + n + ").apk");
            }
            if (!apk.renameTo(dest)) { copy(apk, dest); apk.delete(); }
            File logCopy = null;
            if (log != null && log.exists()) { logCopy = new File(f, "log.txt"); copy(log, logCopy); }
            try {
                meta.put("id", id);
                meta.put("file", dest.getAbsolutePath());
                meta.put("fileName", dest.getName());
                meta.put("size", dest.length());
                meta.put("sha256", sha256(dest));
                meta.put("patchedAt", System.currentTimeMillis());
                meta.put("log", logCopy == null ? "" : logCopy.getAbsolutePath());
                write(new File(f, "meta.json"), meta);
            } catch (JSONException e) {
                throw new IOException(e);
            }
        } catch (IOException e) {
            undo(f, dest, apk);
            throw e;
        }
        return meta;
    }

    /**
     * A filing that failed leaves nothing behind: its folder goes, and so does the APK it wrote, but only once the patched APK is safe again at
     * its old place (it was moved, and the next attempt - for example into the app folder when Downloads is not writable - needs it there).
     */
    private static void undo(File folder, File dest, File apk) {
        if (dest != null && dest.isFile()) {
            if (!apk.exists()) {
                long size = dest.length();
                if (!dest.renameTo(apk)) { try { copy(dest, apk); } catch (IOException ignored) {} }
                if (apk.length() != size) return;                                       // could not put it back whole: keep what we have, lose nothing
            }
            dest.delete();                                                               // (already gone when it was moved back; a partial copy otherwise)
        }
        deleteTree(folder);
    }

    private static void write(File f, JSONObject o) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try { out.write(o.toString().getBytes("UTF-8")); } finally { out.close(); }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("cannot write " + f); }
    }

    private static JSONObject read(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            } finally { in.close(); }
        } catch (Exception e) {
            return null;
        }
    }

    /** Every patched APK, newest first. A folder whose APK is gone is dropped from the list (and cleaned). */
    public synchronized JSONArray list() {
        List<JSONObject> all = new ArrayList<JSONObject>();
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (!k.isDirectory()) continue;
            JSONObject m = read(new File(k, "meta.json"));
            if (m == null) continue;
            String file = m.optString("file");
            if (file.isEmpty() || !new File(file).exists()) { deleteTree(k); continue; }
            all.add(m);
        }
        Collections.sort(all, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("patchedAt"), a.optLong("patchedAt")); }
        });
        return new JSONArray(all);
    }

    public synchronized JSONObject get(String id) throws IOException {
        return read(new File(folder(id), "meta.json"));
    }

    /** Deletes the patched APK (and its log and meta) of one run, also when the APK is kept outside, in Downloads. */
    public synchronized boolean delete(String id) throws IOException {
        File f = folder(id);
        if (!f.exists()) return false;
        JSONObject m = read(new File(f, "meta.json"));
        if (m != null) {
            File apk = new File(m.optString("file"));
            if (apk.exists() && !apk.getAbsolutePath().startsWith(f.getAbsolutePath() + File.separator)) apk.delete();
        }
        deleteTree(f);
        return true;
    }

    /** Deletes only the APK file of a run (the "delete the APK after installing" choice); the entry goes with it. */
    public synchronized void deleteApk(String id) throws IOException {
        delete(id);
    }

    public synchronized String readLog(String id, int maxBytes) throws IOException {
        JSONObject m = get(id);
        if (m == null) return "";
        File l = new File(m.optString("log"));
        if (!l.exists()) return "";
        long len = l.length();
        InputStream in = new FileInputStream(l);
        try {
            if (len > maxBytes) in.skip(len - maxBytes);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    /** Copies the patched APK to destDir under its own name (a free name when one is taken); returns the copy. */
    public synchronized File exportTo(String id, File destDir) throws IOException {
        JSONObject m = get(id);
        if (m == null) throw new IOException("no such patched APK");
        File src = new File(m.optString("file"));
        if (!src.exists()) throw new IOException("the patched APK is gone");
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("cannot create " + destDir);
        String name = src.getName();
        File dest = new File(destDir, name);
        int n = 1;
        String base = name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
        while (dest.exists()) dest = new File(destDir, base + " (" + (n++) + ").apk");
        copy(src, dest);
        return dest;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /**
     * The APK files inside an .apks / .apkm / .xapk bundle, written into dir (the base APK first: base.apk, else the one that is not a split_ or config
     * file). Entry names are flattened, so nothing can land outside dir.
     */
    public static List<File> unzipApks(File bundle, File dir) throws IOException {
        dir.mkdirs();
        List<File> out = new ArrayList<File>();
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(bundle);
        try {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                String n = e.getName();
                n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
                if (n.isEmpty() || n.startsWith(".")) continue;
                File f = new File(dir, n);
                InputStream in = z.getInputStream(e);
                try {
                    OutputStream o = new FileOutputStream(f);
                    try {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                    } finally { o.close(); }
                } finally { in.close(); }
                out.add(f);
            }
        } finally { z.close(); }
        if (out.isEmpty()) throw new IOException("there is no APK inside " + bundle.getName());
        Collections.sort(out, new Comparator<File>() {
            private int rank(File f) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.equals("base.apk")) return 0;
                return n.startsWith("split_") || n.startsWith("config.") ? 2 : 1;
            }
            @Override public int compare(File a, File b) { int r = rank(a) - rank(b); return r != 0 ? r : a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** Bundles the APK files of one app (base first, then its splits) into one .apks file, which is what the engine takes for a split app. */
    public static void zipApks(List<File> apks, File out) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        try {
            z.setLevel(0);
            for (File f : apks) {
                ZipEntry e = new ZipEntry(f.getName());
                z.putNextEntry(e);
                InputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                } finally { in.close(); }
                z.closeEntry();
            }
        } finally { z.close(); }
    }
}
