package com.bloatware.bingblop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link Sdm.Fs} over java.nio: what this app can read itself (public storage with All files access, its own folders, a temp folder in the tests).
 * Links are never followed. The shell side (Android/data, private data) is SdmFsShell; SdmFs picks one per path.
 */
public class SdmFsJava implements Sdm.Fs {
    @Override
    public String[] list(String dir) {
        String[] l = new File(dir).list();
        return l;
    }

    @Override
    public Sdm.Entry stat(String path) {
        try {
            BasicFileAttributes a = Files.readAttributes(Paths.get(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return entry(path, a);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Sdm.Entry entry(String path, BasicFileAttributes a) {
        int type = a.isSymbolicLink() ? Sdm.LINK : a.isDirectory() ? Sdm.DIR : a.isRegularFile() ? Sdm.FILE : Sdm.OTHER;
        long m = a.lastModifiedTime() == null ? 0 : a.lastModifiedTime().toMillis() / 1000;
        return new Sdm.Entry(path, a.size(), m, type, -1);
    }

    @Override
    public void walk(String root, Sdm.EntrySink sink, Sdm.Cancel cancel) {
        walkDir(Paths.get(root), sink, cancel == null ? Sdm.NEVER : cancel);
    }

    private boolean walkDir(Path dir, Sdm.EntrySink sink, Sdm.Cancel cancel) {
        DirectoryStream<Path> ds = null;
        try {
            ds = Files.newDirectoryStream(dir);
            for (Path p : ds) {
                if (cancel.cancelled()) return false;
                BasicFileAttributes a;
                try { a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (IOException e) { continue; }
                Sdm.Entry e = entry(p.toString(), a);
                boolean into = sink.accept(e);
                if (e.type == Sdm.DIR && into) { if (!walkDir(p, sink, cancel)) return false; }
            }
        } catch (IOException e) {
            // an unreadable directory is skipped, as SD Maid does
        } catch (RuntimeException e) {
            // so is a name the platform cannot decode
        } finally {
            if (ds != null) try { ds.close(); } catch (IOException ignored) {}
        }
        return true;
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean delete(String path) {
        try {
            deleteTree(Paths.get(path));
        } catch (IOException ignored) {}
        return !exists(path);
    }

    private static void deleteTree(Path p) throws IOException {
        BasicFileAttributes a;
        try { a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (IOException e) { return; }
        if (a.isDirectory()) {
            DirectoryStream<Path> ds = Files.newDirectoryStream(p);
            try { for (Path c : ds) deleteTree(c); } finally { ds.close(); }
        }
        Files.deleteIfExists(p);
    }

    @Override
    public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) {
        Set<String> gone = new HashSet<String>();
        for (String p : paths) {
            if (cancel != null && cancel.cancelled()) break;
            if (delete(p)) gone.add(p);
        }
        return gone;
    }

    @Override
    public String sha256(String path, Sdm.Cancel cancel) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(path);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.cancelled()) throw new IOException("cancelled");
                    md.update(buf, 0, n);
                }
            } finally { in.close(); }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public byte[] head(String path, int max) {
        try {
            InputStream in = new FileInputStream(path);
            try {
                byte[] buf = new byte[max];
                int n = 0, k;
                while (n < max && (k = in.read(buf, n, max - n)) > 0) n += k;
                return n == max ? buf : java.util.Arrays.copyOf(buf, n);
            } finally { in.close(); }
        } catch (IOException e) {
            return null;
        }
    }

    /** A test helper: every file and directory under root, sorted, as paths. */
    public static List<String> listAll(String root) {
        final List<String> out = new ArrayList<String>();
        new SdmFsJava().walk(root, new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { out.add(e.path); return true; } }, null);
        java.util.Collections.sort(out);
        return out;
    }
}
