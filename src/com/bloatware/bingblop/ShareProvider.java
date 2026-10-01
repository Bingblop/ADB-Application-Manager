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

/**
 * Serves files from this app's cache/share folder to the Android share sheet.
 * Not exported: other apps can only read a file through the one-off URI grant on the share intent.
 * (A hand-rolled provider because androidx FileProvider is not available in the Gradle-free build.)
 */
public class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "com.bloatware.bingblop.share";

    /** A fresh, empty file in cache/share. Old shares are cleared first so the folder cannot grow. */
    static File newShareFile(Context ctx, String name) {
        File dir = new File(ctx.getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        return new File(dir, safeName(name));
    }

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    private static String safeName(String name) {
        String n = (name == null || name.trim().isEmpty() ? "share.txt" : name).replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.startsWith(".") ? "_" + n : n;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.equals(safeName(name))) throw new FileNotFoundException("bad name");
        File f = new File(new File(getContext().getCacheDir(), "share"), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
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
