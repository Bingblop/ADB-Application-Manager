package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which data areas this phone has and how each one is read (spec sections 1.4 and 8.3): by this app itself ("java"), through the working
 * mode's shell ("shell") or not at all (via "" and a reason in words). Decided by probes at run time, not by API level guesses, because
 * phones differ:
 * <ul>
 * <li>Public storage (SDCARD) and Public app media: Java when All-files access is granted and a test file can be written and removed
 *     (upstream's "eu.darken.sdmse-test-sd-area-access-NNN" check), else the shell when there is one, else unavailable.</li>
 * <li>Public app data / Public app resources (Android/data, Android/obb): Java only when listing shows something besides this app's own folder
 *     (older Android), else the shell, else unavailable ("This feature needs root or ADB access, which isn't available on this device.").</li>
 * <li>Private app data and every /data area: shell, only when the shell user is root (uid 0), and only for folders that exist.</li>
 * </ul>
 * The roots are read from a {@link Config} so a test can run all of it on a temp tree with a fake shell.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; the roots follow upstream's
 * {@code DataArea.Type} table (spec 1.4), the availability rules are the port plan of spec 8.3. Pure Java, no android.* classes.
 */
public final class SdmAreas implements Sdm.Areas {

    /** Whether the app was granted All-files access (MANAGE_EXTERNAL_STORAGE, or the storage permission below Android 11). */
    public interface Access { boolean storage(); }

    public static final class Config {
        /** Public volumes, the primary one first: {@code /storage/emulated/0}, then {@code /storage/XXXX-XXXX}. */
        public List<String> publicRoots = new ArrayList<String>(Collections.singletonList("/storage/emulated/0"));
        /** Removable USB volumes (nothing in plain Java can tell them from SD cards: the host fills this in when it can). */
        public List<String> portableRoots = new ArrayList<String>();
        public String dataRoot = "/data";
        public String cacheRoot = "/cache";
        public String dataMirror = "/data_mirror";
        public int userId = 0;
        public int sdkInt = 34;
        public String ownPackage = "";
        /** Create and delete a test file in a public root before reading it with Java (upstream does). */
        public boolean writeTest = true;
    }

    public static final String CANT_ACCESS = "Can't access %s.";
    public static final String NEEDS_ROOT = "This feature needs root access, but SD Maid couldn't get it on this device.";
    public static final String NEEDS_ROOT_OR_ADB = "This feature needs root or ADB access, which isn't available on this device.";
    private static final long TTL_MS = 30000;
    private static final Pattern VOLUME = Pattern.compile("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}");

    private final Sdm.Shell shell;
    private final Sdm.Fs javaFs;
    private final Config cfg;
    private final Access access;
    private final Object lock = new Object();
    private volatile List<Sdm.AreaInfo> cache;          // as computed, in display order
    private volatile List<Sdm.AreaInfo> byDepth;        // the same, deepest root first (for areaOf)
    private volatile String cacheKey = "";
    private volatile long cacheAt = 0;

    public SdmAreas(Sdm.Shell shell, Sdm.Fs javaProbe, Config cfg, Access access) {
        this.shell = shell;
        this.javaFs = javaProbe;
        this.cfg = cfg == null ? new Config() : cfg;
        this.access = access;
    }

    /** The mounted public volumes of this phone: the primary one, then every {@code /storage/XXXX-XXXX}. */
    public static List<String> discoverPublicRoots(Sdm.Fs fs, String primary) {
        List<String> out = new ArrayList<String>();
        out.add(primary);
        try {
            String[] l = fs.list("/storage");
            if (l != null) {
                java.util.Arrays.sort(l);
                for (String n : l) if (VOLUME.matcher(n).matches()) out.add("/storage/" + n);
            }
        } catch (IOException ignored) {
            // the primary volume alone
        }
        return out;
    }

    public static String label(Sdm.Area a) {
        switch (a) {
            case SDCARD: return "Public storage";
            case PUBLIC_MEDIA: return "Public app media";
            case PUBLIC_DATA: return "Public app data";
            case PUBLIC_OBB: return "Public app resources";
            case PRIVATE_DATA: return "Private app data";
            case PORTABLE: return "Portable storage";
            case DATA: return "Data";
            case DATA_SYSTEM: return "System data";
            case DATA_SYSTEM_CE: return "System data (CE)";
            case DATA_SYSTEM_DE: return "System data (DE)";
            case DATA_MISC: return "Misc data";
            case DATA_VENDOR: return "Vendor data";
            case DOWNLOAD_CACHE: return "Download cache";
            case APP_APP: return "App sources";
            case APP_APP_PRIVATE: return "Private app sources";
            case APP_LIB: return "App libraries";
            case APP_ASEC: return "Encrypted app resources";
            case DALVIK_DEX: return "Dalvik cache";
            case DALVIK_PROFILE: return "Dalvik profiles";
            case ART_PROFILE: return "ART profiles";
            default: return a.name();
        }
    }

    /** The six areas of the availability line of the tab header (spec 8.3 and 9.7). */
    public static boolean isMainArea(Sdm.Area a) {
        return a == Sdm.Area.SDCARD || a == Sdm.Area.PUBLIC_MEDIA || a == Sdm.Area.PUBLIC_DATA || a == Sdm.Area.PUBLIC_OBB || a == Sdm.Area.PRIVATE_DATA || a == Sdm.Area.PORTABLE;
    }

    // ---------------------------------------------------------------------------------------------------------- Sdm.Areas

    @Override
    public List<Sdm.AreaInfo> all() {
        String key = key();
        List<Sdm.AreaInfo> c = cache;
        if (c != null && key.equals(cacheKey) && System.currentTimeMillis() - cacheAt < TTL_MS) return c;
        return refresh();
    }

    /** Probes again (the mode changed, the user granted a permission, a volume was mounted). */
    public List<Sdm.AreaInfo> refresh() {
        synchronized (lock) {
            List<Sdm.AreaInfo> list = Collections.unmodifiableList(compute());
            List<Sdm.AreaInfo> deep = new ArrayList<Sdm.AreaInfo>();
            for (Sdm.AreaInfo a : list) if (!a.root.isEmpty()) deep.add(a);
            Collections.sort(deep, new Comparator<Sdm.AreaInfo>() {
                @Override public int compare(Sdm.AreaInfo x, Sdm.AreaInfo y) { return y.root.length() - x.root.length(); }
            });
            byDepth = deep;
            cacheKey = key();
            cacheAt = System.currentTimeMillis();
            cache = list;
            return list;
        }
    }

    private String key() {
        int uid;
        try { uid = shell == null ? -1 : shell.uid(); } catch (RuntimeException e) { uid = -1; }
        return uid + "/" + (access == null || access.storage());
    }

    @Override
    public Sdm.AreaInfo get(Sdm.Area a) {
        Sdm.AreaInfo first = null;
        for (Sdm.AreaInfo i : all()) {
            if (i.area != a) continue;
            if (i.available()) return i;
            if (first == null) first = i;
        }
        return first != null ? first : new Sdm.AreaInfo(a, "", a == Sdm.Area.SDCARD || a == Sdm.Area.DATA, "", "Not present on this device");
    }

    @Override
    public Sdm.AreaInfo areaOf(String path) {
        if (path == null) return null;
        List<Sdm.AreaInfo> d = byDepth;
        if (d == null || System.currentTimeMillis() - cacheAt >= TTL_MS) { all(); d = byDepth; }
        if (d == null) return null;
        for (Sdm.AreaInfo a : d) {
            if (path.equals(a.root) || Sdm.isInside(path, a.root)) return a;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------- the probes

    private boolean writeTest(String root) {
        if (!cfg.writeTest) return true;
        File f = new File(root, "eu.darken.sdmse-test-sd-area-access-" + Math.abs(new Random().nextInt()));
        try {
            if (!f.createNewFile()) return false;
            return f.delete() || !f.exists();
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String[] listOrNull(String dir) {
        try { return javaFs.list(dir); } catch (IOException e) { return null; }
    }

    /** Which of these folders exist, asked of the shell in one process; also every folder directly below {@code globDir} (when given). */
    private Set<String> probeDirs(List<String> dirs, String globDir) {
        final Set<String> found = new HashSet<String>();
        if (dirs.isEmpty() && globDir == null) return found;
        StringBuilder sb = new StringBuilder();
        if (!dirs.isEmpty()) {
            sb.append("for d in");
            for (String d : dirs) sb.append(' ').append(BackupScripts.quote(d));
            sb.append("; do if [ -d \"$d\" ]; then echo \"Y:$d\"; fi; done; ");
        }
        if (globDir != null) sb.append("for d in ").append(BackupScripts.quote(globDir)).append("/*; do if [ -d \"$d\" ]; then echo \"Y:$d\"; fi; done; ");
        sb.append("echo ").append(SdmFsShell.END);
        try {
            shell.stream(sb.toString(), 60000, new Sdm.LineSink() {
                @Override public void line(String line) { if (line.startsWith("Y:")) found.add(line.substring(2)); }
            }, null);
        } catch (IOException e) {
            // nothing is known to exist
        }
        return found;
    }

    private List<Sdm.AreaInfo> compute() {
        int uid;
        try { uid = shell == null ? -1 : shell.uid(); } catch (RuntimeException e) { uid = -1; }
        final boolean privileged = uid >= 0, root = uid == 0;
        final boolean storage = access == null || access.storage();

        // 1. what Java can read
        final List<String> pub = cfg.publicRoots;
        final String[] sdJava = new String[pub.size()];                 // "java" when the volume is usable with Java
        for (int i = 0; i < pub.size(); i++) {
            String r = pub.get(i);
            sdJava[i] = storage && listOrNull(r) != null && writeTest(r) ? "java" : "";
        }
        final boolean[] dataJava = new boolean[pub.size()], obbJava = new boolean[pub.size()], mediaJava = new boolean[pub.size()];
        for (int i = 0; i < pub.size(); i++) {
            String r = pub.get(i);
            mediaJava[i] = !sdJava[i].isEmpty() && listOrNull(r + "/Android/media") != null;
            dataJava[i] = storage && javaListsOthers(r + "/Android/data");
            obbJava[i] = storage && javaListsOthers(r + "/Android/obb");
        }
        List<String> portable = cfg.portableRoots;
        final String[] portJava = new String[portable.size()];
        for (int i = 0; i < portable.size(); i++) portJava[i] = storage && listOrNull(portable.get(i)) != null && writeTest(portable.get(i)) ? "java" : "";

        // 2. what the shell has to say about the folders that need it
        String dr = cfg.dataRoot, mirror = cfg.dataMirror, u = String.valueOf(cfg.userId);
        List<String> ask = new ArrayList<String>();
        if (privileged) {
            for (int i = 0; i < pub.size(); i++) {
                String r = pub.get(i);
                if (sdJava[i].isEmpty()) ask.add(r);
                if (!mediaJava[i]) ask.add(r + "/Android/media");
                if (!dataJava[i]) ask.add(r + "/Android/data");
                if (!obbJava[i]) ask.add(r + "/Android/obb");
            }
            for (int i = 0; i < portable.size(); i++) if (portJava[i].isEmpty()) ask.add(portable.get(i));
        }
        if (root) {
            ask.add(dr); ask.add(dr + "/data"); ask.add(mirror); ask.add(mirror + "/data_ce/null/" + u); ask.add(mirror + "/data_de/null/" + u);
            ask.add(dr + "/system"); ask.add(dr + "/system_ce/" + u); ask.add(dr + "/system_de/" + u); ask.add(dr + "/misc"); ask.add(dr + "/vendor");
            ask.add(dr + "/cache"); ask.add(cfg.cacheRoot);
            ask.add(dr + "/app"); ask.add(dr + "/app-private"); ask.add(dr + "/app-lib"); ask.add(dr + "/app-asec");
            ask.add(dr + "/dalvik-cache/profiles"); ask.add(cfg.cacheRoot + "/dalvik-cache/profiles");
            ask.add(mirror + "/ref_profiles"); ask.add(mirror + "/cur_profiles/" + u); ask.add(dr + "/misc/profiles/ref"); ask.add(dr + "/misc/profiles/cur/" + u);
        }
        Set<String> there = new HashSet<String>();
        Set<String> dalvik = new HashSet<String>();
        if (privileged) {
            there = probeDirs(ask, null);
            if (root) {
                Set<String> g = probeDirs(new ArrayList<String>(), dr + "/dalvik-cache");
                g.addAll(probeDirs(new ArrayList<String>(), cfg.cacheRoot + "/dalvik-cache"));
                dalvik = g;
                there.addAll(g);
            }
        }

        // 3. the areas
        List<Sdm.AreaInfo> out = new ArrayList<Sdm.AreaInfo>();
        for (int i = 0; i < pub.size(); i++) {
            String r = pub.get(i);
            boolean primary = i == 0;
            if (!sdJava[i].isEmpty()) out.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, r, primary, "java", ""));
            else if (privileged && there.contains(r)) out.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, r, primary, "shell", ""));
            else out.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, r, primary, "", String.format(CANT_ACCESS, r)));

            String media = r + "/Android/media";
            if (mediaJava[i]) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, media, primary, "java", ""));
            else if (privileged && there.contains(media)) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, media, primary, "shell", ""));
            else out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, media, primary, "", String.format(CANT_ACCESS, media)));

            String data = r + "/Android/data";
            if (dataJava[i]) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, data, primary, "java", ""));
            else if (privileged && there.contains(data)) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, data, primary, "shell", ""));
            else out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, data, primary, "", privileged ? String.format(CANT_ACCESS, data) : NEEDS_ROOT_OR_ADB));

            String obb = r + "/Android/obb";
            if (obbJava[i]) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, obb, primary, "java", ""));
            else if (privileged && there.contains(obb)) out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, obb, primary, "shell", ""));
            else out.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, obb, primary, "", privileged ? String.format(CANT_ACCESS, obb) : NEEDS_ROOT_OR_ADB));
        }
        if (portable.isEmpty()) out.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, "", false, "", "No portable storage found"));
        for (int i = 0; i < portable.size(); i++) {
            String r = portable.get(i);
            if (!portJava[i].isEmpty()) out.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, r, i == 0, "java", ""));
            else if (privileged && there.contains(r)) out.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, r, i == 0, "shell", ""));
            else out.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, r, i == 0, "", String.format(CANT_ACCESS, r)));
        }

        // root only: everything below /data
        String noRoot = NEEDS_ROOT;
        boolean mirrored = root && cfg.sdkInt >= 30 && there.contains(mirror);
        if (mirrored) {
            rootArea(out, root, there, Sdm.Area.PRIVATE_DATA, mirror + "/data_ce/null/" + u, true, noRoot);
            rootArea(out, root, there, Sdm.Area.PRIVATE_DATA, mirror + "/data_de/null/" + u, false, noRoot);
        } else {
            rootArea(out, root, there, Sdm.Area.PRIVATE_DATA, dr + "/data", true, noRoot);
        }
        rootArea(out, root, there, Sdm.Area.DATA, dr, true, noRoot);
        rootArea(out, root, there, Sdm.Area.DATA_SYSTEM, dr + "/system", true, noRoot);
        rootArea(out, root, there, Sdm.Area.DATA_SYSTEM_CE, dr + "/system_ce/" + u, true, noRoot);
        rootArea(out, root, there, Sdm.Area.DATA_SYSTEM_DE, dr + "/system_de/" + u, true, noRoot);
        rootArea(out, root, there, Sdm.Area.DATA_MISC, dr + "/misc", true, noRoot);
        rootArea(out, root, there, Sdm.Area.DATA_VENDOR, dr + "/vendor", true, noRoot);
        rootArea(out, root, there, Sdm.Area.DOWNLOAD_CACHE, dr + "/cache", true, noRoot);
        if (!(dr + "/cache").equals(cfg.cacheRoot)) rootArea(out, root, there, Sdm.Area.DOWNLOAD_CACHE, cfg.cacheRoot, false, noRoot);
        rootArea(out, root, there, Sdm.Area.APP_APP, dr + "/app", true, noRoot);
        rootArea(out, root, there, Sdm.Area.APP_APP_PRIVATE, dr + "/app-private", true, noRoot);
        rootArea(out, root, there, Sdm.Area.APP_LIB, dr + "/app-lib", true, noRoot);
        rootArea(out, root, there, Sdm.Area.APP_ASEC, dr + "/app-asec", true, noRoot);
        List<String> dex = new ArrayList<String>(dalvik);
        Collections.sort(dex);
        boolean firstDex = true;
        for (String d : dex) {
            if (d.endsWith("/profiles")) continue;
            rootArea(out, root, there, Sdm.Area.DALVIK_DEX, d, firstDex, noRoot);
            firstDex = false;
        }
        if (firstDex) rootArea(out, root, there, Sdm.Area.DALVIK_DEX, dr + "/dalvik-cache", true, noRoot);
        rootArea(out, root, there, Sdm.Area.DALVIK_PROFILE, dr + "/dalvik-cache/profiles", true, noRoot);
        if (cfg.sdkInt >= 30) {
            rootArea(out, root, there, Sdm.Area.ART_PROFILE, mirror + "/ref_profiles", true, noRoot);
            rootArea(out, root, there, Sdm.Area.ART_PROFILE, mirror + "/cur_profiles/" + u, false, noRoot);
        } else {
            rootArea(out, root, there, Sdm.Area.ART_PROFILE, dr + "/misc/profiles/ref", true, noRoot);
            rootArea(out, root, there, Sdm.Area.ART_PROFILE, dr + "/misc/profiles/cur/" + u, false, noRoot);
        }
        return out;
    }

    private static void rootArea(List<Sdm.AreaInfo> out, boolean root, Set<String> there, Sdm.Area area, String path, boolean primary, String noRoot) {
        if (!root) out.add(new Sdm.AreaInfo(area, path, primary, "", noRoot));
        else if (there.contains(path)) out.add(new Sdm.AreaInfo(area, path, primary, "shell", ""));
        else out.add(new Sdm.AreaInfo(area, path, primary, "", "Not present on this device"));
    }

    /** Java sees Android/data (or Android/obb) when it can list it and it holds more than this app's own folder (or on Android 10 and below, when it can list it at all). */
    private boolean javaListsOthers(String dir) {
        String[] l = listOrNull(dir);
        if (l == null) return false;
        if (cfg.sdkInt <= 29) return true;
        for (String n : l) if (!n.equals(cfg.ownPackage)) return true;
        return false;
    }
}
