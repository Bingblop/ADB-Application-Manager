package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-tool-corpsefinder: core/CorpseFinder.kt, Corpse.kt, filter/{StandardCorpseFilter,SdcardCorpseFilter,PublicDataCorpseFilter,PublicMediaCorpseFilter,
 * PublicObbCorpseFilter,PrivateDataCorpseFilter,FilterCandidates}.kt; app-common-data: forensics/{OwnerInfo,AreaInfo,FileForensics}.kt and
 * forensics/csi/pub/*, csi/priv/PrivateDataCSI.kt).
 * Changed for this port: plain Java on {@link Sdm.Fs} / {@link Sdm.Areas} / {@link Sdm.Packages} (no coroutines, no android.*); the data areas come from the
 * phone's own list (an area that cannot be read is skipped, which replaces upstream's API-level capability gates); the advanced root-only filters (Dalvik cache,
 * ART profiles, app libraries / sources / private sources / encrypted resources) are not ported yet (their switches exist and stay off); the uninstall watcher
 * and the multi-user check are dropped; the OBB "is mounted" check of upstream cannot be made without android.* and is left out; the PRIVATE_DATA ".overlay"
 * owner trusts the overlay APK being at its known place instead of reading its package name.
 * Quirk fixes (spec 8.8 item 8 and found while porting): the package "android" and every owner flagged "custodian" count as installed (a system folder such as
 * DCIM/Camera is never a remnant, even when the phone's package list lacks the pseudo package); an empty package list aborts the scan instead of flagging
 * everything; the first segment of a nested marker is looked up case-insensitively on public storage; the nested rule of the exclusions (a folder that holds
 * an excluded path is never a remnant) is applied to every filter, upstream only checks the remnant's own path; the markers' "regexPkgs" are resolved per
 * scan; before a delete every path is looked at again (a remnant whose app was installed again meanwhile is left alone).
 *
 * What it does: for the top-level entries of every readable public / private data area it asks "which package does this belong to" (owner attribution, spec
 * 4.2) and flags those whose package is not installed (blacklist areas: nobody installed owns it; whitelist areas, i.e. SDCARD: some marker claims it and
 * no owner is installed). Result groups are the remnants (id = path), items are the files below one.
 */
public final class SdmCorpseFinder implements Sdm.ToolImpl {

    // ---------------------------------------------------------------------------------------------------------- settings (spec 4.5)

    public static final String K_KEEPER = "risk.include.keeper", K_COMMON = "risk.include.common";
    public static final String K_SDCARD = "filter.sdcard.enabled", K_PUBLICMEDIA = "filter.publicmedia.enabled", K_PUBLICDATA = "filter.publicdata.enabled",
            K_PUBLICOBB = "filter.publicobb.enabled", K_PRIVATEDATA = "filter.privatedata.enabled";
    /** The root-only filters that are not ported yet: the keys exist (off by default), a scan ignores them. */
    public static final String K_DALVIK = "filter.dalvikcache.enabled", K_ARTPROFILES = "filter.artprofiles.enabled", K_APPLIB = "filter.applib.enabled",
            K_APPSOURCE = "filter.appsource.enabled", K_APPSOURCEPRIVATE = "filter.appsourceprivate.enabled", K_APPASEC = "filter.appasec.enabled";

    static final class FilterDef {
        final String id, label, key;
        final Sdm.Area area;
        final boolean def;
        final Set<String> skipNames;
        FilterDef(String id, String label, String key, Sdm.Area area, boolean def, String... skip) {
            this.id = id; this.label = label; this.key = key; this.area = area; this.def = def; this.skipNames = new HashSet<String>(Arrays.asList(skip));
        }
    }

    static final FilterDef F_SDCARD = new FilterDef("sdcard", "SD card", K_SDCARD, Sdm.Area.SDCARD, true);
    static final FilterDef F_PUBLICMEDIA = new FilterDef("publicmedia", "Public media", K_PUBLICMEDIA, Sdm.Area.PUBLIC_MEDIA, true, ".nomedia");
    static final FilterDef F_PUBLICDATA = new FilterDef("publicdata", "Public app data", K_PUBLICDATA, Sdm.Area.PUBLIC_DATA, true, ".nomedia", "hosts", "lost+found");
    static final FilterDef F_PUBLICOBB = new FilterDef("publicobb", "OBB resources", K_PUBLICOBB, Sdm.Area.PUBLIC_OBB, false, ".nomedia");
    static final FilterDef F_PRIVATEDATA = new FilterDef("privatedata", "Private app data", K_PRIVATEDATA, Sdm.Area.PRIVATE_DATA, true, "hosts", "lost+found");
    /** The order of the settings screen. */
    static final FilterDef[] FILTERS = { F_SDCARD, F_PUBLICMEDIA, F_PUBLICDATA, F_PUBLICOBB, F_PRIVATEDATA };

    /** Switches of filters that exist upstream but are not ported: label + key, to tell the page what a scan left out. */
    private static final String[][] DEFERRED = {
            { "Dalvik cache", K_DALVIK }, { "ART profiles", K_ARTPROFILES }, { "App libraries", K_APPLIB }, { "App sources", K_APPSOURCE },
            { "Private app sources", K_APPSOURCEPRIVATE }, { "Encrypted app resources", K_APPASEC } };

    public static final int RISK_NORMAL = 0, RISK_KEEPER = 1, RISK_COMMON = 2;

    private final SdmMarkers markers;

    /** The engine's constructor: the marker database is the app's (read through {@link SdmAppSieve#readAsset} on first use). */
    public SdmCorpseFinder() { this.markers = null; }

    /** For tests: a given marker database. */
    public SdmCorpseFinder(SdmMarkers markers) { this.markers = markers; }

    @Override public Sdm.Tool tool() { return Sdm.Tool.CORPSEFINDER; }

    // ---------------------------------------------------------------------------------------------------------- texts (spec 7.3, 9.3, 9.7)

    public static String areaLabel(Sdm.Area a) {
        switch (a) {
            case SDCARD: return "Public storage";
            case PUBLIC_MEDIA: return "Public app media";
            case PUBLIC_DATA: return "Public app data";
            case PUBLIC_OBB: return "Public app resources";
            case PRIVATE_DATA: return "Private app data";
            case PORTABLE: return "Portable storage";
            default: return a.name();
        }
    }

    public static String foundLine(int n) { return n + (n == 1 ? " app remnant discovered" : " app remnants discovered"); }
    public static String deletedLine(int n) { return n + (n == 1 ? " app remnant deleted" : " app remnants deleted"); }
    public static String canBeFreed(long bytes) { return fmtSize(bytes) + " can be freed"; }
    public static String freedLine(long bytes) { return "Freed " + fmtSize(bytes) + " space."; }

    /** Binary units, one decimal: "12.3 MB" (the engine formats the same way). */
    static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        String[] u = { "KB", "MB", "GB", "TB", "PB" };
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }

    // ---------------------------------------------------------------------------------------------------------- model

    /** A package that claims a path, with the flags of the marker that said so. */
    public static final class Owner {
        public final String pkg;
        public final Set<String> flags;
        Owner(String pkg, Set<String> flags) { this.pkg = pkg; this.flags = flags == null ? Collections.<String>emptySet() : flags; }
        boolean has(String flag) { return flags.contains(flag); }
        @Override public boolean equals(Object o) { return o instanceof Owner && ((Owner) o).pkg.equals(pkg) && ((Owner) o).flags.equals(flags); }
        @Override public int hashCode() { return pkg.hashCode() * 31 + flags.hashCode(); }
        @Override public String toString() { return pkg + flags; }
    }

    /** OwnerInfo: who owns one path (spec 4.1). */
    static final class OwnerInfo {
        final Sdm.AreaInfo area;
        final String path;
        final boolean blackList;
        final Set<Owner> owners;
        final Set<Owner> installedOwners;
        final boolean unknownOwner;
        OwnerInfo(Sdm.AreaInfo area, String path, boolean blackList, Set<Owner> owners, Set<Owner> installed, boolean unknown) {
            this.area = area; this.path = path; this.blackList = blackList; this.owners = owners; this.installedOwners = installed; this.unknownOwner = unknown;
        }
        boolean isKeeper() { for (Owner o : owners) if (o.has(SdmMarkers.KEEPER)) return true; return false; }
        boolean isCommon() { for (Owner o : owners) if (o.has(SdmMarkers.COMMON)) return true; return false; }
        boolean isOwned() { return unknownOwner || !installedOwners.isEmpty(); }
        boolean isCorpse() {
            if (blackList) return installedOwners.isEmpty() && !unknownOwner;
            if (!owners.isEmpty()) return installedOwners.isEmpty() && !unknownOwner;
            return false;
        }
    }

    /** One remnant: the top-level path and everything below it. Immutable; a partial delete makes a new one. */
    public static final class Corpse {
        public final String path, name, parent, filterId, filterLabel;
        public final Sdm.Area area;
        public final int risk;
        public final List<Owner> owners;
        public final boolean unknownOwner;
        public final int type;
        public final long ownSize, mtime;
        public final List<Sdm.Entry> content;      // sorted by path
        public final long size;                    // own + content
        Corpse(String path, FilterDef f, Sdm.Area area, int risk, List<Owner> owners, boolean unknownOwner, Sdm.Entry lookup, List<Sdm.Entry> content) {
            this.path = path;
            int i = path.lastIndexOf('/');
            this.name = i < 0 ? path : path.substring(i + 1);
            this.parent = i <= 0 ? "/" : path.substring(0, i);
            this.filterId = f.id; this.filterLabel = f.label; this.area = area; this.risk = risk; this.owners = owners; this.unknownOwner = unknownOwner;
            this.type = lookup.type; this.ownSize = lookup.size; this.mtime = lookup.mtime;
            this.content = content;
            long s = lookup.size;
            for (Sdm.Entry e : content) s += e.size;
            this.size = s;
        }
        private Corpse(Corpse c, List<Sdm.Entry> content) {
            this.path = c.path; this.name = c.name; this.parent = c.parent; this.filterId = c.filterId; this.filterLabel = c.filterLabel; this.area = c.area; this.risk = c.risk;
            this.owners = c.owners; this.unknownOwner = c.unknownOwner; this.type = c.type; this.ownSize = c.ownSize; this.mtime = c.mtime;
            this.content = content;
            long s = c.ownSize;
            for (Sdm.Entry e : content) s += e.size;
            this.size = s;
        }
        Corpse withContent(List<Sdm.Entry> content) { return new Corpse(this, content); }
        public boolean hasOwner(Collection<String> pkgs) { for (Owner o : owners) if (pkgs.contains(o.pkg)) return true; return false; }
        JSONObject row() throws JSONException {
            JSONArray own = new JSONArray();
            for (Owner o : owners) own.put(o.pkg);
            return new JSONObject().put("id", path).put("label", name).put("sub", parent).put("count", content.size()).put("bytes", size)
                    .put("path", path).put("name", name).put("parent", parent).put("size", ownSize).put("mtime", mtime).put("type", type)
                    .put("filter", filterId).put("filterLabel", filterLabel).put("area", area.name()).put("areaLabel", areaLabel(area))
                    .put("risk", risk == RISK_KEEPER ? "keeper" : risk == RISK_COMMON ? "common" : "normal")
                    .put("riskLabel", risk == RISK_KEEPER ? "Desirable" : risk == RISK_COMMON ? "Common" : "")
                    .put("owners", own).put("unknownOwner", owners.isEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the result

    /** What a scan found. A delete changes it in place (deleted remnants vanish, a partly deleted one loses those files); the engine can also drop excluded ones. */
    public static final class CorpseResult implements Sdm.Result {
        private List<Corpse> corpses;
        private final List<String> skippedFilters;

        CorpseResult(List<Corpse> corpses, List<String> skippedFilters) {
            this.corpses = new ArrayList<Corpse>(corpses);
            this.skippedFilters = skippedFilters;
        }

        @Override public Sdm.Tool tool() { return Sdm.Tool.CORPSEFINDER; }
        @Override public synchronized int groupCount() { return corpses.size(); }
        /** One item of the dashboard is one remnant (upstream: totalCount = corpses). */
        @Override public synchronized int itemCount() { return corpses.size(); }
        @Override public synchronized long bytes() { long b = 0; for (Corpse c : corpses) b += c.size; return b; }

        public synchronized List<Corpse> corpses() { return new ArrayList<Corpse>(corpses); }

        /** A copy that a later change of this result does not touch (the engine keeps one for the Undo of an exclusion). */
        public synchronized CorpseResult snapshot() { return new CorpseResult(corpses, skippedFilters); }

        /** Takes over what another result holds (the Undo of an exclusion). */
        public synchronized void restore(CorpseResult other) { this.corpses = new ArrayList<Corpse>(other.corpses()); }

        @Override public synchronized JSONArray groups(int offset, int limit) throws JSONException {
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, offset); i < corpses.size() && out.length() < limit; i++) out.put(corpses.get(i).row());
            return out;
        }

        @Override public synchronized JSONArray items(String groupId, int offset, int limit) throws JSONException {
            JSONArray out = new JSONArray();
            Corpse c = find(groupId);
            if (c == null) return out;
            for (int i = Math.max(0, offset); i < c.content.size() && out.length() < limit; i++) {
                Sdm.Entry e = c.content.get(i);
                out.put(new JSONObject().put("id", e.path).put("path", e.path).put("name", e.name).put("size", e.size).put("mtime", e.mtime).put("type", e.type));
            }
            return out;
        }

        @Override public synchronized JSONObject summary() throws JSONException {
            int n = corpses.size();
            long b = bytes();
            JSONObject o = new JSONObject().put("itemCount", n).put("bytes", b).put("groupCount", n)
                    .put("primary", foundLine(n)).put("secondary", canBeFreed(b));
            if (!skippedFilters.isEmpty()) o.put("skippedFilters", new JSONArray(skippedFilters));
            return o;
        }

        synchronized Corpse find(String id) {
            for (Corpse c : corpses) if (c.path.equals(id)) return c;
            return null;
        }

        /**
         * Exclusions created after the scan: a remnant that is, or lies below, an excluded path goes, and so does one that holds an excluded path (the nested
         * rule). Returns how many remnants went.
         */
        public synchronized int dropPaths(Collection<String> excluded) {
            List<Corpse> keep = new ArrayList<Corpse>();
            for (Corpse c : corpses) {
                boolean drop = false;
                for (String p : excluded) {
                    if (p.equals(c.path) || SdmSieve.isAncestorOf(p, c.path) || SdmSieve.isAncestorOf(c.path, p)) { drop = true; break; }
                }
                if (!drop) keep.add(c);
            }
            int gone = corpses.size() - keep.size();
            corpses = keep;
            return gone;
        }

        /** A package exclusion created after the scan: every remnant one of whose owners is that package goes. */
        public synchronized int dropPackages(Collection<String> pkgs) {
            List<Corpse> keep = new ArrayList<Corpse>();
            for (Corpse c : corpses) if (!c.hasOwner(pkgs)) keep.add(c);
            int gone = corpses.size() - keep.size();
            corpses = keep;
            return gone;
        }

        /** After a delete: remnants in {@code whole} are gone, those in {@code parts} lost the content files at or below the given paths. */
        synchronized void applyDelete(Set<String> whole, Map<String, Set<String>> parts) {
            List<Corpse> keep = new ArrayList<Corpse>();
            for (Corpse c : corpses) {
                if (whole.contains(c.path)) continue;
                boolean covered = false;
                for (String w : whole) if (SdmSieve.isAncestorOf(w, c.path)) { covered = true; break; }
                if (covered) continue;
                Set<String> roots = parts.get(c.path);
                if (roots == null || roots.isEmpty()) { keep.add(c); continue; }
                List<Sdm.Entry> rest = new ArrayList<Sdm.Entry>();
                for (Sdm.Entry e : c.content) {
                    boolean gone = false;
                    for (String r = e.path; r != null && r.length() > c.path.length(); r = parentOf(r)) if (roots.contains(r)) { gone = true; break; }
                    if (!gone) rest.add(e);
                }
                keep.add(c.withContent(rest));
            }
            corpses = keep;
        }
    }

    static String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i <= 0 ? null : p.substring(0, i);
    }

    static String join(String dir, String name) { return dir.endsWith("/") ? dir + name : dir + "/" + name; }

    // ---------------------------------------------------------------------------------------------------------- scan

    @Override
    public Sdm.Result scan(Sdm.Ctx ctx) throws Exception {
        SdmMarkers db = markers != null ? markers : SdmMarkers.shared();
        Scan s = new Scan(ctx, db);
        List<Corpse> all = new ArrayList<Corpse>();

        for (FilterDef f : FILTERS) {
            if (!ctx.bool(f.key, f.def)) continue;
            s.check();
            if (f == F_PRIVATEDATA && !(ctx.shell != null && ctx.shell.uid() == 0)) continue;     // private data needs root
            List<Corpse> found = f == F_SDCARD ? s.sdcard() : s.standard(f);
            all.addAll(found);
        }

        List<String> skipped = new ArrayList<String>();
        for (String[] d : DEFERRED) if (ctx.bool(d[1], false)) skipped.add(d[0]);

        // post filters of CorpseFinder.runScan: owner package exclusion, path exclusion, and the nested rule of the exclusions
        List<String> excludedPaths = ctx.exclusions == null ? Collections.<String>emptyList() : ctx.exclusions.paths(Sdm.Tool.CORPSEFINDER);
        List<Corpse> kept = new ArrayList<Corpse>();
        for (Corpse c : all) {
            boolean drop = false;
            if (ctx.exclusions != null) {
                for (Owner o : c.owners) if (ctx.exclusions.excludesPackage(Sdm.Tool.CORPSEFINDER, o.pkg)) { drop = true; break; }
                if (!drop && ctx.exclusions.excludesPath(Sdm.Tool.CORPSEFINDER, c.path)) drop = true;
                if (!drop) for (String p : excludedPaths) if (SdmSieve.isAncestorOf(c.path, p)) { drop = true; break; }
            }
            if (!drop) kept.add(c);
        }
        Collections.sort(kept, new Comparator<Corpse>() {
            @Override public int compare(Corpse a, Corpse b) {
                if (a.size != b.size) return a.size > b.size ? -1 : 1;
                return a.path.compareTo(b.path);
            }
        });
        return new CorpseResult(kept, skipped);
    }

    /** The state of one scan: the package list, the marker index, the settings and the progress. */
    private final class Scan {
        final Sdm.Ctx ctx;
        final SdmMarkers.Index idx;
        final Set<String> installedNames = new HashSet<String>();
        final Map<String, Boolean> installedMemo = new HashMap<String, Boolean>();
        final List<Sdm.Pkg> pkgList;
        final boolean includeKeeper, includeCommon;
        long foundBytes;
        final List<Sdm.AreaInfo> areas = new ArrayList<Sdm.AreaInfo>();

        Scan(Sdm.Ctx ctx, SdmMarkers db) {
            this.ctx = ctx;
            this.pkgList = ctx.pkgs == null ? Collections.<Sdm.Pkg>emptyList() : ctx.pkgs.installed();
            for (Sdm.Pkg p : pkgList) installedNames.add(p.pkg);
            if (installedNames.isEmpty()) throw new IllegalStateException("The list of installed apps is empty, so remnants cannot be told from live data. Scan stopped.");
            this.idx = db.index(installedNames);
            this.includeKeeper = ctx.bool(K_KEEPER, false);
            this.includeCommon = ctx.bool(K_COMMON, false);
            List<Sdm.AreaInfo> all = ctx.areas == null ? Collections.<Sdm.AreaInfo>emptyList() : ctx.areas.all();
            for (Sdm.AreaInfo a : all) if (a.available()) areas.add(a);
        }

        void check() { if (ctx.cancelled()) throw new CancellationException("cancelled"); }

        void progress(String primary, String secondary, long done, long total) { ctx.progress.update(primary, secondary, done, total, foundBytes); }

        boolean installed(String pkg) {
            if (installedNames.contains(pkg) || pkg.equals("android")) return true;
            Boolean m = installedMemo.get(pkg);
            if (m == null) {
                boolean b = false;
                try { b = ctx.pkgs != null && ctx.pkgs.get(pkg) != null; } catch (RuntimeException e) { b = false; }
                installedMemo.put(pkg, b);
                m = b;
            }
            return m;
        }

        List<Sdm.AreaInfo> areasOf(Sdm.Area type) {
            List<Sdm.AreaInfo> l = new ArrayList<Sdm.AreaInfo>();
            for (Sdm.AreaInfo a : areas) if (a.area == type) l.add(a);
            return l;
        }

        boolean isExcluded(String path) { return ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.CORPSEFINDER, path); }

        // ------------------------------------------------------------------------------------------ owner attribution (spec 4.2)

        Set<Owner> fromMarkers(Sdm.Area area, String[] pfp) {
            Set<Owner> owners = new LinkedHashSet<Owner>();
            for (SdmMarkers.Match m : idx.match(area, pfp)) for (String p : m.pkgs) owners.add(new Owner(p, m.flags));
            return owners;
        }

        /** The area a path is in among the readable ones, as FileForensics.identifyArea finds it; null when none. */
        Sdm.AreaInfo identify(String path) {
            Sdm.AreaInfo best = null;
            for (Sdm.AreaInfo a : areas) {
                if (!SdmSieve.isAncestorOf(a.root, path)) continue;
                if (a.area == Sdm.Area.SDCARD) {
                    if (SdmSieve.isAncestorOf(join(a.root, "Android/obb"), path) || SdmSieve.isAncestorOf(join(a.root, "Android/data"), path)
                            || SdmSieve.isAncestorOf(join(a.root, "Android/media"), path)) continue;
                }
                if (best == null || a.root.length() > best.root.length()) best = a;
            }
            return best;
        }

        OwnerInfo finish(Sdm.AreaInfo area, String path, Set<Owner> owners, boolean unknown) {
            Set<Owner> inst = new LinkedHashSet<Owner>();
            for (Owner o : owners) if (installed(o.pkg) || o.has(SdmMarkers.CUSTODIAN)) inst.add(o);
            boolean black = area.area == Sdm.Area.PUBLIC_DATA || area.area == Sdm.Area.PUBLIC_MEDIA || area.area == Sdm.Area.PUBLIC_OBB || area.area == Sdm.Area.PRIVATE_DATA;
            return new OwnerInfo(area, path, black, owners, inst, unknown);
        }

        OwnerInfo findOwners(Sdm.AreaInfo area, String path) throws IOException {
            String[] pfp = area.pfp(path);
            if (pfp == null || pfp.length == 0) return null;
            switch (area.area) {
                case PUBLIC_DATA: return publicNamed(area, path, pfp, true);
                case PUBLIC_MEDIA: return publicNamed(area, path, pfp, false);
                case PUBLIC_OBB: return publicObb(area, path, pfp);
                case PRIVATE_DATA: return privateData(area, path, pfp);
                case SDCARD: return sdcardOwners(area, path, pfp);
                default: return finish(area, path, new LinkedHashSet<Owner>(), false);    // PORTABLE: nobody claims anything
            }
        }

        /** PublicDataCSI / PublicMediaCSI: the folder is named like the package, possibly hidden by a prefix; unknown names are assumed to be package names. */
        OwnerInfo publicNamed(Sdm.AreaInfo area, String path, String[] pfp, boolean data) {
            Set<Owner> owners = new LinkedHashSet<Owner>();
            String name = pfp[0], hidden = null;
            if (installed(name)) {
                owners.add(new Owner(name, null));
            } else {
                hidden = cleanName(name, data);
                if (hidden != null && installed(hidden)) owners.add(new Owner(hidden, null));
            }
            if (owners.isEmpty()) owners.addAll(fromMarkers(area.area, new String[] { name }));
            if (owners.isEmpty()) owners.add(new Owner(hidden != null ? hidden : name, null));
            return finish(area, path, owners, false);
        }

        String cleanName(String name, boolean data) {
            if (name.startsWith(".external.")) return name.substring(10);
            if (name.startsWith("_") || name.startsWith(".")) return name.substring(1);
            if (data && name.endsWith(":remote")) return name.substring(0, name.length() - ":remote".length());
            return null;
        }

        /** PublicObbCSI: installed, else markers; no name fallback (an unknown OBB folder has no owner and is a remnant). */
        OwnerInfo publicObb(Sdm.AreaInfo area, String path, String[] pfp) {
            Set<Owner> owners = new LinkedHashSet<Owner>();
            String name = pfp[0];
            if (installed(name)) owners.add(new Owner(name, null));
            else owners.addAll(fromMarkers(area.area, new String[] { name }));
            return finish(area, path, owners, false);
        }

        /** SdcardCSI: chop segments off the end until a marker claims the rest. */
        OwnerInfo sdcardOwners(Sdm.AreaInfo area, String path, String[] pfp) {
            Set<Owner> owners = new LinkedHashSet<Owner>();
            String[] best = pfp;
            while (best.length > 0) {
                owners.addAll(fromMarkers(Sdm.Area.SDCARD, best));
                if (!owners.isEmpty()) break;
                best = Arrays.copyOf(best, best.length - 1);
            }
            return finish(area, path, owners, false);
        }

        static final String LGE = "^(com\\.lge\\.theme\\.[\\w_\\-]+)(\\.[\\w._\\-]+)$";

        /** PrivateDataCSI (root): name, hidden name, markers, then the POSIX owner uid of the folder; unknown or shared uid means "owned by something". */
        OwnerInfo privateData(Sdm.AreaInfo area, String path, String[] pfp) throws IOException {
            Set<Owner> owners = new LinkedHashSet<Owner>();
            boolean unknown = false;
            String dir = pfp[0];
            if (installed(dir)) owners.add(new Owner(dir, null));
            String hidden = privateHidden(dir);
            if (owners.isEmpty() && hidden != null && installed(hidden)) owners.add(new Owner(hidden, null));
            if (owners.isEmpty()) owners.addAll(fromMarkers(area.area, new String[] { dir }));

            boolean anyInstalled = false;
            for (Owner o : owners) if (installed(o.pkg) || o.has(SdmMarkers.CUSTODIAN)) { anyInstalled = true; break; }
            if (!anyInstalled) {
                int uid = -1;
                try {
                    Sdm.Entry e = ctx.fs.stat(path);
                    if (e != null) uid = e.uid;
                } catch (IOException e) {
                    uid = -1;
                }
                if (uid < 0) {
                    unknown = true;                         // could not be told: fail safe
                } else if (uid > 0) {
                    List<Sdm.Pkg> hit = new ArrayList<Sdm.Pkg>();
                    for (Sdm.Pkg p : pkgList) if (p.uid > 0 && p.uid % 100000 == uid % 100000 && p.uid / 100000 == uid / 100000) hit.add(p);
                    if (hit.size() == 1) owners.add(new Owner(hit.get(0).pkg, null));
                    else if (hit.size() > 1) unknown = true;   // a shared uid: owned, but by no single package
                }
            }
            if (owners.isEmpty() && !unknown) owners.add(new Owner(hidden != null ? hidden : dir, null));
            return finish(area, path, owners, unknown);
        }

        String privateHidden(String name) {
            if (name.startsWith(".external.")) return name.substring(10);
            if (name.startsWith("_") || name.startsWith(".")) return name.substring(1);
            if (name.startsWith("com.lge.theme.")) {
                Matcher m = Pattern.compile(LGE).matcher(name);
                if (m.matches()) return m.group(1);
            } else if (name.endsWith(".overlay")) {
                String owner = name.substring(0, name.lastIndexOf(".overlay"));
                String a = "/system/vendor/overlay/" + owner + "/" + owner + ".apk", b = "/OP/OPEN_EU/overlay/app/" + owner + ".apk";
                if (ctx.fs.exists(a) || ctx.fs.exists(b)) return owner;
            }
            return null;
        }

        // ------------------------------------------------------------------------------------------ the filters

        /** StandardCorpseFilter: the top-level entries of every readable area of the type. */
        List<Corpse> standard(FilterDef f) throws IOException {
            List<Corpse> out = new ArrayList<Corpse>();
            for (Sdm.AreaInfo ai : areasOf(f.area)) {
                check();
                String secondary = "Processing " + areaLabel(f.area);
                progress(f.label, secondary, 0, -1);
                String[] names = ctx.fs.list(ai.root);
                if (names == null) continue;
                Arrays.sort(names);
                List<String> cands = new ArrayList<String>();
                for (String n : names) {
                    String p = join(ai.root, n);
                    if (isExcluded(p) || f.skipNames.contains(n)) continue;
                    cands.add(p);
                }
                for (int i = 0; i < cands.size(); i++) {
                    check();
                    progress(f.label, secondary, i, cands.size());
                    String p = cands.get(i);
                    OwnerInfo oi = findOwners(ai, p);
                    if (oi == null || oi.area.area != f.area) continue;
                    if (!oi.isCorpse()) continue;
                    if (oi.isKeeper() && !includeKeeper) continue;
                    if (oi.isCommon() && !includeCommon) continue;
                    Corpse c = corpse(f, oi);
                    if (c != null) { out.add(c); foundBytes += c.size; }
                }
                progress(f.label, secondary, cands.size(), cands.size());
            }
            return out;
        }

        /** Looks the remnant up and walks what is below it; null when it has gone, or holds an excluded path (nested rule). */
        Corpse corpse(FilterDef f, OwnerInfo oi) throws IOException {
            Sdm.Entry lookup = ctx.fs.stat(oi.path);
            if (lookup == null) return null;
            final List<Sdm.Entry> content = new ArrayList<Sdm.Entry>();
            final boolean[] blocked = { false };
            if (lookup.type == Sdm.DIR) {
                ctx.fs.walk(oi.path, new Sdm.EntrySink() {
                    @Override public boolean accept(Sdm.Entry e) {
                        if (blocked[0]) return false;
                        if (isExcluded(e.path)) { blocked[0] = true; return false; }
                        content.add(e);
                        return true;
                    }
                }, new Sdm.Cancel() {
                    @Override public boolean cancelled() { return blocked[0] || ctx.cancelled(); }
                });
                check();
            }
            if (blocked[0]) return null;
            Collections.sort(content, new Comparator<Sdm.Entry>() {
                @Override public int compare(Sdm.Entry a, Sdm.Entry b) { return a.path.compareTo(b.path); }
            });
            int risk = oi.isKeeper() ? RISK_KEEPER : oi.isCommon() ? RISK_COMMON : RISK_NORMAL;
            return new Corpse(oi.path, f, oi.area.area, risk, new ArrayList<Owner>(oi.owners), oi.unknownOwner, lookup, Collections.unmodifiableList(content));
        }

        /**
         * SdcardCorpseFilter: SDCARD is a whitelist, so instead of asking who owns every path it looks at every path some marker knows (a reverse lookup):
         * top-level entries, plus every nested path of a marker whose first folder exists. Dead items that hold a living one, or lie below another dead one, go.
         */
        List<Corpse> sdcard() throws IOException {
            FilterDef f = F_SDCARD;
            List<Corpse> out = new ArrayList<Corpse>();
            List<Sdm.AreaInfo> sd = areasOf(Sdm.Area.SDCARD);
            if (sd.isEmpty()) return out;
            progress(f.label, "Loading", 0, -1);

            // 1. top level of every area
            List<OwnerInfo> potential = new ArrayList<OwnerInfo>();
            Map<Sdm.AreaInfo, Set<String>> topNames = new HashMap<Sdm.AreaInfo, Set<String>>();
            for (Sdm.AreaInfo a : sd) {
                check();
                String secondary = "Processing " + a.root;
                progress(f.label, secondary, 0, -1);
                String[] names = ctx.fs.list(a.root);
                Set<String> lowers = new HashSet<String>();
                topNames.put(a, lowers);
                if (names == null) continue;
                Arrays.sort(names);
                for (int i = 0; i < names.length; i++) {
                    check();
                    progress(f.label, secondary, i, names.length);
                    String p = join(a.root, names[i]);
                    lowers.add(SdmSieve.lower(names[i]));
                    OwnerInfo oi = findOwners(a, p);
                    if (oi != null) potential.add(oi);
                }
            }

            // 2. the nested paths of the markers whose top-level folder exists
            List<SdmMarkers.Marker> relevant = new ArrayList<SdmMarkers.Marker>();
            for (SdmMarkers.Marker m : idx.forLocation(Sdm.Area.SDCARD)) {
                if (!m.direct || m.segments.length <= 1) continue;
                boolean empty = false;
                for (String seg : m.segments) if (seg.isEmpty()) { empty = true; break; }
                if (!empty) relevant.add(m);
            }
            progress(f.label, "Filtering", 0, relevant.size());
            Map<String, Sdm.AreaInfo> nestedArea = new LinkedHashMap<String, Sdm.AreaInfo>();
            Map<String, Set<Owner>> nestedOwners = new LinkedHashMap<String, Set<Owner>>();
            for (int i = 0; i < relevant.size(); i++) {
                check();
                progress(f.label, "Filtering", i, relevant.size());
                SdmMarkers.Marker m = relevant.get(i);
                for (Sdm.AreaInfo a : sd) {
                    if (!topNames.get(a).contains(SdmSieve.lower(m.segments[0]))) continue;
                    String cand = join(a.root, SdmSieve.join(m.segments));
                    Sdm.Entry st;
                    try { st = ctx.fs.stat(cand); } catch (IOException e) { continue; }
                    if (st == null) continue;
                    cand = realCase(cand);
                    Sdm.AreaInfo ai = identify(cand);
                    if (ai == null || ai.area != Sdm.Area.SDCARD) continue;
                    String[] pfp = ai.pfp(cand);
                    if (pfp == null) continue;
                    SdmMarkers.Match match = m.match(Sdm.Area.SDCARD, pfp);
                    if (match == null) continue;
                    Set<Owner> owners = nestedOwners.get(cand);
                    if (owners == null) { owners = new LinkedHashSet<Owner>(); nestedOwners.put(cand, owners); nestedArea.put(cand, ai); }
                    for (String p : match.pkgs) owners.add(new Owner(p, match.flags));
                }
            }
            for (Map.Entry<String, Set<Owner>> e : nestedOwners.entrySet()) potential.add(finish(nestedArea.get(e.getKey()), e.getKey(), e.getValue(), false));

            // 3. classify
            progress(f.label, "Filtering", 0, potential.size());
            List<OwnerInfo> dead = new ArrayList<OwnerInfo>(), alive = new ArrayList<OwnerInfo>();
            for (int i = 0; i < potential.size(); i++) {
                OwnerInfo c = potential.get(i);
                if (c.isOwned()) alive.add(c);
                else if (c.isKeeper() && !includeKeeper) alive.add(c);
                else if (c.isCommon() && !includeCommon) alive.add(c);
                else if (c.isCorpse()) dead.add(c);
                progress(f.label, "Filtering", i + 1, potential.size());
            }
            // blocked: never delete a folder that holds something still owned; covered: the parent already includes it
            List<OwnerInfo> blockedFree = new ArrayList<OwnerInfo>();
            for (int i = 0; i < dead.size(); i++) {
                check();
                OwnerInfo d = dead.get(i);
                boolean blocked = false;
                for (OwnerInfo l : alive) if (SdmSieve.isAncestorOf(d.path, l.path)) { blocked = true; break; }
                if (!blocked) blockedFree.add(d);
                progress(f.label, "Filtering", i, Math.max(1, dead.size() * 2));
            }
            List<OwnerInfo> finalDead = new ArrayList<OwnerInfo>();
            for (int i = 0; i < blockedFree.size(); i++) {
                OwnerInfo d = blockedFree.get(i);
                boolean covered = false;
                for (OwnerInfo o : blockedFree) if (SdmSieve.isAncestorOf(o.path, d.path)) { covered = true; break; }
                if (!covered) finalDead.add(d);
                progress(f.label, "Filtering", dead.size() + i, Math.max(1, dead.size() * 2));
            }

            // 4. the remnants
            progress(f.label, "Filtering", 0, finalDead.size());
            for (int i = 0; i < finalDead.size(); i++) {
                check();
                Corpse c = corpse(f, finalDead.get(i));
                if (c != null) { out.add(c); foundBytes += c.size; }
                progress(f.label, "Filtering", i + 1, finalDead.size());
            }
            return out;
        }

        /** Public storage ignores case: use the name the file system has for the last segment when it differs from the marker's. */
        String realCase(String path) {
            String parent = parentOf(path);
            if (parent == null) return path;
            String leaf = path.substring(parent.length() + 1);
            String[] names;
            try { names = ctx.fs.list(parent); } catch (IOException e) { return path; }
            if (names == null) return path;
            String found = null;
            for (String n : names) {
                if (n.equals(leaf)) return path;
                if (n.equalsIgnoreCase(leaf)) { if (found != null) return path; found = n; }
            }
            return found == null ? path : join(parent, found);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- delete (spec 4.6)

    private static final class Target {
        final String path, label, corpseId;
        final long bytes;
        final boolean whole;
        final Corpse corpse;
        Target(String path, long bytes, boolean whole, Corpse corpse) {
            this.path = path; this.bytes = bytes; this.whole = whole; this.corpse = corpse; this.corpseId = corpse.path; this.label = corpse.name;
        }
        String name() { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }
    }

    private static final int BATCH = 10;

    /**
     * Deletes everything the selection resolves to: every remnant not in dropGroups, as a whole, unless some of its content paths are in dropItems; then only
     * the content that is neither dropped, nor holds a dropped path, nor lies below one (its distinct roots) goes and the remnant itself stays. Every path is
     * looked at again right before it goes: one that is already gone counts as deleted, one that is excluded now, unsafe, or whose owner app is installed
     * again is left alone and reported as failed.
     */
    @Override
    public Sdm.DeleteReport delete(Sdm.Result result, Sdm.Selection selection, Sdm.Ctx ctx) throws Exception {
        CorpseResult r = (CorpseResult) result;
        Sdm.DeleteReport report = new Sdm.DeleteReport();
        List<Corpse> corpses = r.corpses();

        // plan
        List<Target> plan = new ArrayList<Target>();
        for (Corpse c : corpses) {
            if (selection.dropGroups.contains(c.path)) continue;
            Set<String> dropped = new HashSet<String>();
            for (Sdm.Entry e : c.content) if (selection.dropItems.contains(e.path)) dropped.add(e.path);
            if (dropped.isEmpty()) { plan.add(new Target(c.path, c.size, true, c)); continue; }

            Set<String> protectedAnc = new HashSet<String>();           // the dropped paths and their ancestors below the remnant
            for (String d : dropped) for (String p = d; p != null && p.length() > c.path.length(); p = parentOf(p)) protectedAnc.add(p);
            List<String> open = new ArrayList<String>();
            for (Sdm.Entry e : c.content) {
                if (protectedAnc.contains(e.path)) continue;
                boolean inside = false;                                  // below a dropped directory
                for (String p = parentOf(e.path); p != null && p.length() > c.path.length(); p = parentOf(p)) if (dropped.contains(p)) { inside = true; break; }
                if (!inside) open.add(e.path);
            }
            List<String> roots = SdmSieve.distinctRoots(open);
            Map<String, Long> bytes = new HashMap<String, Long>();
            Set<String> rootSet = new HashSet<String>(roots);
            for (Sdm.Entry e : c.content) {
                for (String p = e.path; p != null && p.length() > c.path.length(); p = parentOf(p)) {
                    if (rootSet.contains(p)) { Long b = bytes.get(p); bytes.put(p, (b == null ? 0 : b) + e.size); break; }
                }
            }
            for (String root : roots) plan.add(new Target(root, bytes.containsKey(root) ? bytes.get(root) : 0, false, c));
        }
        // distinct roots over the whole plan (a path below another target is covered by it)
        List<String> allPaths = new ArrayList<String>();
        for (Target t : plan) allPaths.add(t.path);
        Set<String> rootsOfPlan = new HashSet<String>(SdmSieve.distinctRoots(allPaths));

        // installed packages now (a remnant whose app came back is no remnant)
        Set<String> installedNow = new HashSet<String>();
        if (ctx.pkgs != null) for (Sdm.Pkg p : ctx.pkgs.installed()) installedNow.add(p.pkg);

        Set<String> wholeGone = new HashSet<String>();
        Map<String, Set<String>> partGone = new HashMap<String, Set<String>>();

        List<Target> ready = new ArrayList<Target>();
        for (Target t : plan) {
            if (!rootsOfPlan.contains(t.path)) continue;       // lies below another target: that one takes it along
            String why = SdmSafety.check(t.path, ctx.areas);
            if (why == null && t.whole && ownerInstalled(t.corpse, installedNow, ctx)) why = "its app is installed again";
            if (why == null && ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.CORPSEFINDER, t.path)) why = "it is excluded";
            if (why != null) {
                report.failed.add(t.path);
                report.notes.add("Skipped " + t.path + ": " + why);
                continue;
            }
            ready.add(t);
        }

        int total = ready.size(), done = 0;
        List<Target> batch = new ArrayList<Target>();
        for (int i = 0; i < ready.size(); i++) {
            if (ctx.cancelled()) break;
            batch.add(ready.get(i));
            if (batch.size() == BATCH || i == ready.size() - 1) {
                runBatch(batch, ctx, report, wholeGone, partGone, done, total);
                done += batch.size();
                batch.clear();
            }
        }
        ctx.progress.update("Loading", "", total, total, report.bytes());
        r.applyDelete(wholeGone, partGone);
        return report;
    }

    private static boolean ownerInstalled(Corpse c, Set<String> installedNow, Sdm.Ctx ctx) {
        for (Owner o : c.owners) {
            if (o.has(SdmMarkers.CUSTODIAN)) continue;
            if (installedNow.contains(o.pkg) || o.pkg.equals("android")) return true;
            try { if (ctx.pkgs != null && ctx.pkgs.get(o.pkg) != null) return true; } catch (RuntimeException e) { /* treat as not installed */ }
        }
        return false;
    }

    private void runBatch(List<Target> batch, Sdm.Ctx ctx, Sdm.DeleteReport report, Set<String> wholeGone, Map<String, Set<String>> partGone, int done, int total) {
        Target first = batch.get(0);
        ctx.progress.update("Deleting " + first.name(), first.path, done, total, report.bytes());
        List<String> todo = new ArrayList<String>();
        Set<String> alreadyGone = new HashSet<String>();
        for (Target t : batch) {
            Sdm.Entry now;
            try { now = ctx.fs.stat(t.path); } catch (IOException e) { now = null; }
            if (now == null) alreadyGone.add(t.path); else todo.add(t.path);      // a path that is already gone counts as deleted
        }
        Set<String> gone = todo.isEmpty() ? new HashSet<String>() : ctx.fs.deleteAll(todo, ctx.cancel);
        for (Target t : batch) {
            boolean isGone = alreadyGone.contains(t.path) || (gone != null && gone.contains(t.path));
            if (!isGone && !ctx.cancelled()) { try { isGone = ctx.fs.stat(t.path) == null; } catch (IOException e) { isGone = false; } }
            if (isGone) {
                report.deleted.add(new Sdm.Deleted(t.path, t.bytes, t.corpseId, t.label));
                if (t.whole) wholeGone.add(t.corpseId);
                else { Set<String> s = partGone.get(t.corpseId); if (s == null) { s = new HashSet<String>(); partGone.put(t.corpseId, s); } s.add(t.path); }
            } else if (!ctx.cancelled()) {
                report.failed.add(t.path);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- helpers for the engine

    /** The scan line of a result (spec 7.3): {primary, secondary}. */
    public static JSONObject scanLines(int count, long bytes) {
        try { return new JSONObject().put("primary", foundLine(count)).put("secondary", canBeFreed(bytes)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** The receipt of a delete (spec 7.3): {primary, secondary}; count is the number of removed paths (affectedPaths.size). */
    public static JSONObject deleteLines(int count, long bytes) {
        try { return new JSONObject().put("primary", deletedLine(count)).put("secondary", freedLine(bytes)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** What the delete report says in the words of the dashboard. */
    public static JSONObject deleteLines(Sdm.DeleteReport report) { return deleteLines(report.deleted.size(), report.bytes()); }
}
