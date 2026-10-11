package com.bloatware.bingblop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Serves files from this app's cache/share folder to the Android share sheet.
 * Not exported: other apps can only read a file through the one-off URI grant on the share intent.
 * (A hand-rolled provider because androidx FileProvider is not available in the Gradle-free build.)
 *
 * Every share gets its own random folder (cache/share/<id>/<name>), so a later share can never replace
 * a file an earlier recipient still has access to, and old shares are only removed once they are an
 * hour old, so a receiving app that is slow to open its file never loses it.
 */
public class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "com.bloatware.bingblop.share";
    private static final long KEEP_MS = 60 * 60 * 1000L;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh path cache/share/<random id>/<name>; the folder exists, the file does not yet. */
    static File newShareFile(Context ctx, String name) {
        File root = new File(ctx.getCacheDir(), "share");
        root.mkdirs();
        deleteOlderThan(root, System.currentTimeMillis() - KEEP_MS);
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder id = new StringBuilder();
        for (byte x : b) id.append(String.format("%02x", x & 0xFF));
        File dir = new File(root, id.toString());
        dir.mkdirs();
        return new File(dir, safeName(name));
    }

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(f.getParentFile().getName()).appendPath(f.getName()).build();
    }

    private static void deleteOlderThan(File root, long cutoff) {
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (d.lastModified() >= cutoff) continue;
            File[] files = d.listFiles();
            if (files != null) for (File f : files) f.delete();
            d.delete();
        }
    }

    private static String safeName(String name) {
        String n = (name == null || name.trim().isEmpty() ? "share.txt" : name).replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.startsWith(".") ? "_" + n : n;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !ID.matcher(seg.get(0)).matches() || !seg.get(1).equals(safeName(seg.get(1)))) {
            throw new FileNotFoundException("bad path");
        }
        File f = new File(new File(new File(getContext().getCacheDir(), "share"), seg.get(0)), seg.get(1));
        if (!f.isFile()) throw new FileNotFoundException(seg.get(1));
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = fileFor(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                row[i] = OpenableColumns.SIZE.equals(cols[i]) ? (Object) Long.valueOf(f.length()) : f.getName();
            }
            c.addRow(row);
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (ext.equals("apks")) return "application/octet-stream";
            String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (t != null) return t;
        }
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
