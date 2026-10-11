package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The font setting's files (preview.bin / preview.json for the font being looked at, current.bin / current.json for the one in use) and the
 * lock around them. The slow part, reading the chosen font, runs OUTSIDE the lock into a private staging file; the lock is held only to swap
 * files, so apply and clear never wait for a source that has stalled. Plain Java, no Android.
 */
public final class FontStore {
    /** Opens the chosen font. {@link #name()} is asked after {@link #open()}. */
    public interface Opener {
        InputStream open() throws Exception;
        String name();
    }

    private final File dir;
    private final Object lock = new Object();             // held only to move / delete the files, never while a source is read
    private final AtomicInteger seq = new AtomicInteger();
    private int newest = 0;                               // (lock) the newest stage that has finished or been cancelled; an older one finishing later is dropped

    public FontStore(File dir) { this.dir = dir; }

    public File dir() { if (!dir.isDirectory()) dir.mkdirs(); return dir; }

    /** Copies the font from {@code src} to the preview slot after checking it. Answer: {ok, family, style, name, kind, variable, size} or {ok:false, error}. */
    public JSONObject stage(Opener src, long timeoutMs) {
        final int my = seq.incrementAndGet();
        File staged = new File(dir(), "preview." + my + ".stage");
        JSONObject res = new JSONObject();
        InputStream in = null;
        try {
            synchronized (lock) {                          // a failed pick must not leave the earlier preview to be applied
                new File(dir(), "preview.bin").delete();
                new File(dir(), "preview.json").delete();
            }
            in = src.open();
            if (in == null) throw new IllegalStateException("The file could not be opened.");
            String name = src.name();
            FontScan.Names n = FontScan.copyChecked(in, staged, timeoutMs);     // slow, not under the lock
            res.put("ok", true);
            res.put("family", n.family.isEmpty() ? (name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name) : n.family);
            res.put("style", n.style);
            res.put("name", name);
            res.put("kind", n.kind);
            res.put("variable", n.variable);
            res.put("size", staged.length());
            commit(my, staged, res);
        } catch (Exception e) {
            staged.delete();
            new File(staged.getPath() + ".part").delete();
            try { res = new JSONObject(); res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "The font could not be read."); } catch (Exception ignored) {}
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        return res;
    }

    private void commit(int my, File staged, JSONObject meta) throws IOException {
        synchronized (lock) {
            if (my < newest) throw new IllegalStateException("A newer font was chosen.");
            File pb = new File(dir(), "preview.bin"), pj = new File(dir(), "preview.json"), pjTmp = new File(dir(), "preview.json.part");
            pb.delete();
            pj.delete();
            if (!staged.renameTo(pb)) throw new IllegalStateException("The font could not be stored.");
            java.io.FileOutputStream o = new java.io.FileOutputStream(pjTmp);
            try { o.write(meta.toString().getBytes("UTF-8")); } finally { o.close(); }
            if (!pjTmp.renameTo(pj)) { pb.delete(); throw new IllegalStateException("The font could not be stored."); }
            newest = my;
        }
    }

    /** Makes the font that was looked at the one in use. Answer: the font's details {ok, ...}, or {ok:false, error}. */
    public String apply() {
        JSONObject res = new JSONObject();
        try {
            synchronized (lock) {
                File d = dir();
                File p = new File(d, "preview.bin"), pm = new File(d, "preview.json"), c = new File(d, "current.bin"), cm = new File(d, "current.json");
                if (!p.isFile() || !pm.isFile()) throw new IllegalStateException("There is no font to use.");
                if (!p.renameTo(c) || !pm.renameTo(cm)) throw new IllegalStateException("The font could not be stored.");
                res = new JSONObject(readText(cm));
            }
        } catch (Exception e) {
            try { res = new JSONObject(); res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "failed"); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    /** Back to the system font: removes the stored font and the one that was being looked at; a pick still being read is dropped when it finishes. */
    public String clear() {
        JSONObject res = new JSONObject();
        try {
            synchronized (lock) {
                File d = dir();
                for (String n : new String[]{"preview.bin", "preview.json", "preview.bin.part", "preview.json.part", "current.bin", "current.json"}) new File(d, n).delete();
                newest = seq.incrementAndGet();
            }
            res.put("ok", true);
        } catch (Exception e) {
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    private static String readText(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { in.close(); }
    }
}
