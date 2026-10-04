package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The folder of small pictures the file manager keeps so a list of photos does not have to decode each one again. A picture is found by
 * a key made of the file's path, its size, its modified time and the size asked for, so an edited or replaced file gets a new one and
 * the old one simply ages out. The folder is kept under a size limit by removing the least recently used files.
 */
public final class ThumbCache {
    private ThumbCache() {}

    public static final long LIMIT_BYTES = 48L * 1024 * 1024;

    public static String key(String path, long mtime, long length, int px) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((path + "\u0000" + mtime + "\u0000" + length + "\u0000" + px).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((path + mtime + length + px).hashCode());
        }
    }

    public static long size(File dir) {
        long n = 0;
        File[] f = dir.listFiles();
        if (f != null) for (File x : f) if (x.isFile()) n += x.length();
        return n;
    }

    /** Removes the least recently used files until the folder holds at most {@code limit} bytes. Returns the bytes freed. */
    public static long purge(File dir, long limit) {
        File[] f = dir.listFiles();
        if (f == null) return 0;
        long total = 0;
        for (File x : f) if (x.isFile()) total += x.length();
        if (total <= limit) return 0;
        Arrays.sort(f, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        long freed = 0;
        for (File x : f) {
            if (total - freed <= limit) break;
            if (!x.isFile()) continue;
            long len = x.length();
            if (x.delete()) freed += len;
        }
        return freed;
    }

    /** Removes everything. Returns the bytes freed. */
    public static long clear(File dir) {
        long freed = 0;
        File[] f = dir.listFiles();
        if (f != null) for (File x : f) { long len = x.length(); if (x.isFile() && x.delete()) freed += len; }
        return freed;
    }

    /** Marks a file as just used, so purge keeps it longer. */
    public static void touch(File f) { f.setLastModified(System.currentTimeMillis()); }

    /** The kinds of file that get a small picture: images and videos, by extension. */
    public static String kindOf(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        int i = n.lastIndexOf('.');
        String e = i < 0 ? "" : n.substring(i + 1);
        switch (e) {
            case "jpg": case "jpeg": case "png": case "webp": case "gif": case "bmp": case "heic": case "heif": case "avif": return "image";
            case "mp4": case "m4v": case "3gp": case "mkv": case "webm": case "mov": case "avi": return "video";
            default: return "";
        }
    }
}
