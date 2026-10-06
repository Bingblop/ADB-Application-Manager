package com.bloatware.bingblop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link Sdm.Fs} the tools get: one file system over two, picked per path by the data area the path is in. Where the area is read "java"
 * (public storage with All-files access, the app's own folders) {@link SdmFsJava} answers; where it is read "shell" (Android/data on newer
 * phones, private data as root) {@link SdmFsShell} does; a path that is in no known area goes to Java. Every delete first passes the safety
 * rails of {@link SdmSafety} (spec 8.6), whatever the caller is: a path that is not strictly inside an available data area, an area root,
 * "/", "/data", "&lt;sdcard&gt;/Android" and so on is refused (reported as not deleted).
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; the choice per area is the
 * port plan of spec 8.1 / 8.3 (upstream picks a gateway per path the same way: normal, ADB, root). Pure Java, no android.* classes.
 */
public final class SdmFs implements Sdm.Fs, SdmEngine.PruningFs {
    private final Sdm.Areas areas;
    private final Sdm.Fs javaFs;
    private final Sdm.Fs shell;

    /** @param shell the file system over the working mode's shell; null when there is none (every path is then read with Java) */
    public SdmFs(Sdm.Areas areas, Sdm.Fs java, Sdm.Fs shell) {
        this.areas = areas;
        this.javaFs = java;
        this.shell = shell;
    }

    /** True when this path is read through the shell. */
    public boolean viaShell(String path) {
        if (shell == null) return false;
        Sdm.AreaInfo a = areas.areaOf(path);
        return a != null && "shell".equals(a.via);
    }

    private Sdm.Fs pick(String path) {
        return viaShell(path) ? shell : javaFs;
    }

    @Override public String[] list(String dir) throws IOException { return pick(dir).list(dir); }

    @Override public Sdm.Entry stat(String path) throws IOException { return pick(path).stat(path); }

    @Override public boolean exists(String path) { return pick(path).exists(path); }

    @Override public String sha256(String path, Sdm.Cancel cancel) throws IOException { return pick(path).sha256(path, cancel); }

    @Override public byte[] head(String path, int max) throws IOException { return pick(path).head(path, max); }

    @Override public void walk(String root, Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException {
        pick(root).walk(root, sink, cancel);
    }

    @Override
    public void walk(String root, Collection<String> prune, final Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException {
        Sdm.Fs fs = pick(root);
        if (fs instanceof SdmEngine.PruningFs) {
            ((SdmEngine.PruningFs) fs).walk(root, prune, sink, cancel);
            return;
        }
        final Set<String> pruned = new HashSet<String>(prune == null ? new ArrayList<String>() : prune);
        fs.walk(root, new Sdm.EntrySink() {
            @Override public boolean accept(Sdm.Entry e) {
                if (!pruned.isEmpty() && pruned.contains(e.path)) return false;
                return sink.accept(e);
            }
        }, cancel);
    }

    @Override
    public boolean delete(String path) {
        if (SdmSafety.check(path, areas) != null) return false;
        return pick(path).delete(path);
    }

    @Override
    public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) {
        Set<String> gone = new HashSet<String>();
        List<String> viaJava = new ArrayList<String>(), viaShell = new ArrayList<String>();
        for (String p : new LinkedHashSet<String>(paths)) {
            if (SdmSafety.check(p, areas) != null) continue;
            if (viaShell(p)) viaShell.add(p); else viaJava.add(p);
        }
        if (!viaJava.isEmpty()) gone.addAll(javaFs.deleteAll(viaJava, cancel));
        if (!viaShell.isEmpty() && !(cancel != null && cancel.cancelled())) gone.addAll(shell.deleteAll(viaShell, cancel));
        return gone;
    }
}
