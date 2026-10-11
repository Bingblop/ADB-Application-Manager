package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-common-io: files/SegmentsExtensions.kt, sieve/SegmentCriterium.kt, sieve/NameCriterium.kt, sieve/CriteriaOperator.kt, APathExtensions.filterDistinctRoots,
 * app-tool-systemcleaner: core/sieve/SystemCrawlerSieve.kt).
 * Changed for this port: plain Java (String[] instead of List&lt;String&gt;, no coroutines, no APath types), segment equality with String.equalsIgnoreCase (no
 * allocation per comparison), {@link #distinctRoots} with a hash lookup per ancestor instead of the quadratic fold, an empty target gives "no match" instead
 * of an exception where upstream would throw.
 *
 * What every tool shares: the segment helpers and criteria of spec 1.2, the SystemCrawlerSieve conjunction of spec 1.3 and the distinct-roots rule of 1.5.
 * A path is a segment array as {@link Sdm#segments} makes it ("/a/b" -> ["", "a", "b"]); a pfp is the part below an area root ("a/b" under "/" ->
 * ["a", "b"]). Pure functions only, no I/O, no android.*.
 */
public final class SdmSieve {
    private SdmSieve() {}

    // ---------------------------------------------------------------------------------------------------------- helpers shared by all tools (contract API)

    /** Kotlin lowercase(): Locale.ROOT. */
    public static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    private static boolean segEq(String a, String b, boolean ci) { return ci ? a.equalsIgnoreCase(b) : a.equals(b); }

    private static boolean startsWithStr(String s, String prefix, boolean ci) { return s.regionMatches(ci, 0, prefix, 0, prefix.length()); }

    private static boolean endsWithStr(String s, String suffix, boolean ci) {
        int off = s.length() - suffix.length();
        return off >= 0 && s.regionMatches(ci, off, suffix, 0, suffix.length());
    }

    /** "a/b" -> ["a", "b"]; "a/b/" -> ["a", "b", ""] (a trailing slash gives a final empty segment); "" -> [""] (Kotlin toSegs). */
    public static String[] toSegs(String raw) { return raw.split("/", -1); }

    public static String join(String[] segs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < segs.length; i++) { if (i > 0) b.append('/'); b.append(segs[i]); }
        return b.toString();
    }

    /** Segments.startsWith(prefix, ignoreCase, allowPartial = false): the first prefix.length segments are equal. */
    public static boolean startsWith(String[] segs, String[] prefix, boolean ci) {
        if (segs == null || prefix == null || segs.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (!segEq(segs[i], prefix[i], ci)) return false;
        return true;
    }

    /** Segments.startsWith(prefix, ignoreCase, allowPartial = true): the first n-1 segments equal, segment n STARTS WITH the last prefix segment. */
    public static boolean startsWithPartial(String[] segs, String[] prefix, boolean ci) {
        if (segs == null || prefix == null || segs.length < prefix.length) return false;
        int n = prefix.length;
        if (n == 0) return true;
        for (int i = 0; i < n - 1; i++) if (!segEq(segs[i], prefix[i], ci)) return false;
        return startsWithStr(segs[n - 1], prefix[n - 1], ci);
    }

    /** Segments.endsWith(suffix, ignoreCase, allowPartial = false): the last suffix.length segments are equal. */
    public static boolean endsWith(String[] segs, String[] suffix, boolean ci) {
        if (segs == null || suffix == null || segs.length < suffix.length) return false;
        int off = segs.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) if (!segEq(segs[off + i], suffix[i], ci)) return false;
        return true;
    }

    /** Segments.endsWith(suffix, ignoreCase, allowPartial = true): the last n-1 segments equal, the segment before them ENDS WITH the first suffix segment. */
    public static boolean endsWithPartial(String[] segs, String[] suffix, boolean ci) {
        if (segs == null || suffix == null || segs.length < suffix.length) return false;
        int n = suffix.length;
        if (n == 0) return true;
        int off = segs.length - n;
        for (int i = 1; i < n; i++) if (!segEq(segs[off + i], suffix[i], ci)) return false;
        return endsWithStr(segs[off], suffix[0], ci);
    }

    /** Segments.containsSegments(part, allowPartial = false): part is a contiguous sub-list (Collections.indexOfSubList), segment by segment. */
    public static boolean contains(String[] segs, String[] part, boolean ci) {
        if (segs == null || part == null || segs.length < part.length) return false;
        if (part.length == 0) return true;
        for (int start = 0; start + part.length <= segs.length; start++) {
            boolean all = true;
            for (int i = 0; i < part.length; i++) if (!segEq(segs[start + i], part[i], ci)) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    /**
     * Segments.containsSegments(other, ignoreCase, allowPartial): false when segs has fewer segments than part; allowPartial: the joined path contains the
     * joined part as a plain substring ("bc/de" is in "abc/def"); otherwise {@link #contains}. This is what a SegmentExclusion ("DCIM/Camera") uses.
     */
    public static boolean containsSegments(String[] segs, String[] part, boolean allowPartial, boolean ci) {
        if (segs == null || part == null || segs.length < part.length) return false;
        if (!allowPartial) return contains(segs, part, ci);
        String a = join(segs), b = join(part);
        return ci ? lower(a).contains(lower(b)) : a.contains(b);
    }

    /** Segments.isAncestorOf: segs is strictly shorter than other and other starts with it. */
    public static boolean isAncestorSegs(String[] ancestor, String[] other, boolean ci) {
        if (ancestor == null || other == null || ancestor.length >= other.length) return false;
        return startsWith(other, ancestor, ci);
    }

    /** Segments.matches: same length, all segments equal. */
    public static boolean sameSegs(String[] a, String[] b, boolean ci) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!segEq(a[i], b[i], ci)) return false;
        return true;
    }

    /** Segments.segmentContains: segs[index] (or segs[last - index] when backwards) equals segment, or contains it when allowPartial; false when there is none. */
    public static boolean segmentContains(String[] segs, String segment, int index, boolean backwards, boolean ci, boolean allowPartial) {
        int at = backwards ? segs.length - 1 - index : index;
        if (at < 0 || at >= segs.length) return false;
        String target = segs[at];
        if (!allowPartial) return segEq(target, segment, ci);
        if (!ci) return target.contains(segment);
        return lower(target).contains(lower(segment));
    }

    /** LocalPath.isAncestorOf: strict, by path string and separator (case sensitive), never by segments. "/" is an ancestor of every other absolute path. */
    public static boolean isAncestorOf(String ancestor, String path) {
        if (ancestor == null || path == null) return false;
        if (ancestor.length() >= path.length()) return false;
        if (!path.startsWith(ancestor)) return false;
        if (ancestor.equals("/")) return true;
        return path.charAt(ancestor.length()) == '/';
    }

    /**
     * filterDistinctRoots (spec 1.5): of the paths, those that have no ancestor in the set (a path whose parent directory is also in the set is dropped).
     * Shallowest paths first, ties in input order, duplicates once. Two paths that merely share a name prefix ("log.0" and "log.0.1") are both roots.
     */
    public static List<String> distinctRoots(Collection<String> paths) {
        List<String> sorted = new ArrayList<String>();
        final java.util.HashMap<String, Integer> depth = new java.util.HashMap<String, Integer>();
        for (String p : paths) {
            if (p == null || depth.containsKey(p)) continue;
            depth.put(p, depthOf(p));
            sorted.add(p);
        }
        Collections.sort(sorted, new Comparator<String>() {   // stable, like Kotlin sortedBy
            @Override public int compare(String a, String b) { return depth.get(a).compareTo(depth.get(b)); }
        });
        Set<String> kept = new HashSet<String>();
        List<String> out = new ArrayList<String>();
        for (String p : sorted) {
            if (!hasKeptAncestor(kept, p)) { kept.add(p); out.add(p); }
        }
        return out;
    }

    private static int depthOf(String p) {
        if (p.equals("/")) return 0;                                   // [""] is one segment, "/a" is two
        int n = 0;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) == '/') n++;
        return n;
    }

    private static boolean hasKeptAncestor(Set<String> kept, String p) {
        if (kept.isEmpty()) return false;
        if (kept.contains("/") && p.length() > 1) return true;
        for (int i = p.indexOf('/', 1); i > 0; i = p.indexOf('/', i + 1)) if (kept.contains(p.substring(0, i))) return true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------- criteria (spec 1.2)

    /** A segment criterium or a name criterium or an And / Or of them, matched against the segments of a path. */
    public interface Crit { boolean match(String[] target); }

    /** SegmentCriterium: segments + mode. Ignore case is on unless stated, as upstream. */
    public static final class Seg implements Crit {
        public static final int ANCESTOR = 0, START = 1, END = 2, CONTAIN = 3, EQUAL = 4, SPECIFIC = 5;
        public final String[] segments;
        public final int mode;
        public final boolean ignoreCase, allowPartial, backwards;
        public final int index;

        private Seg(String[] segments, int mode, boolean ignoreCase, boolean allowPartial, int index, boolean backwards) {
            this.segments = segments; this.mode = mode; this.ignoreCase = ignoreCase; this.allowPartial = allowPartial; this.index = index; this.backwards = backwards;
        }
        public static Seg anc(String raw) { return new Seg(toSegs(raw), ANCESTOR, true, false, 0, false); }
        public static Seg anc(String raw, boolean ignoreCase) { return new Seg(toSegs(raw), ANCESTOR, ignoreCase, false, 0, false); }
        public static Seg start(String raw) { return new Seg(toSegs(raw), START, true, false, 0, false); }
        public static Seg startPartial(String raw) { return new Seg(toSegs(raw), START, true, true, 0, false); }
        public static Seg end(String raw) { return new Seg(toSegs(raw), END, true, false, 0, false); }
        public static Seg endPartial(String raw) { return new Seg(toSegs(raw), END, true, true, 0, false); }
        public static Seg contain(String raw) { return new Seg(toSegs(raw), CONTAIN, true, false, 0, false); }
        public static Seg containPartial(String raw) { return new Seg(toSegs(raw), CONTAIN, true, true, 0, false); }
        public static Seg eq(String raw) { return new Seg(toSegs(raw), EQUAL, true, false, 0, false); }
        public static Seg specific(String raw, int index, boolean backwards, boolean ignoreCase, boolean allowPartial) { return new Seg(toSegs(raw), SPECIFIC, ignoreCase, allowPartial, index, backwards); }
        /** Any mode with explicit flags. */
        public static Seg of(String raw, int mode, boolean ignoreCase, boolean allowPartial) { return new Seg(toSegs(raw), mode, ignoreCase, allowPartial, 0, false); }
        public static Seg ofSegments(String[] segs, int mode, boolean ignoreCase, boolean allowPartial) { return new Seg(segs, mode, ignoreCase, allowPartial, 0, false); }

        @Override public boolean match(String[] t) {
            switch (mode) {
                case ANCESTOR: return isAncestorSegs(segments, t, ignoreCase);
                case START: return allowPartial ? startsWithPartial(t, segments, ignoreCase) : startsWith(t, segments, ignoreCase);
                case END: return allowPartial ? endsWithPartial(t, segments, ignoreCase) : endsWith(t, segments, ignoreCase);
                case CONTAIN: return containsSegments(t, segments, allowPartial, ignoreCase);
                case EQUAL: return sameSegs(t, segments, ignoreCase);
                case SPECIFIC:
                    if (segments.length != 1) throw new IllegalArgumentException("Specific needs exactly one segment: " + join(segments));   // Kotlin single()
                    return segmentContains(t, segments[0], index, backwards, ignoreCase, allowPartial);
                default: return false;
            }
        }
        /** Match the segments of a raw "a/b/c" string. */
        public boolean matchRaw(String raw) { return match(toSegs(raw)); }
        @Override public String toString() { return "Seg(" + join(segments) + ", mode=" + mode + ", ci=" + ignoreCase + ", partial=" + allowPartial + ")"; }
    }

    /** NameCriterium: matched against the last segment. */
    public static final class Name implements Crit {
        public static final int START = 1, END = 2, CONTAIN = 3, EQUAL = 4;
        public final String name;
        public final int mode;
        public final boolean ignoreCase;
        private Name(String name, int mode, boolean ignoreCase) { this.name = name; this.mode = mode; this.ignoreCase = ignoreCase; }
        public static Name start(String n) { return new Name(n, START, true); }
        public static Name end(String n) { return new Name(n, END, true); }
        public static Name contain(String n) { return new Name(n, CONTAIN, true); }
        public static Name eq(String n) { return new Name(n, EQUAL, true); }
        public static Name of(String n, int mode, boolean ignoreCase) { return new Name(n, mode, ignoreCase); }

        public boolean matchName(String target) {
            switch (mode) {
                case START: return startsWithStr(target, name, ignoreCase);
                case END: return endsWithStr(target, name, ignoreCase);
                case CONTAIN: return ignoreCase ? lower(target).contains(lower(name)) : target.contains(name);
                case EQUAL: return segEq(target, name, ignoreCase);
                default: return false;
            }
        }
        @Override public boolean match(String[] t) { return t != null && t.length > 0 && matchName(t[t.length - 1]); }
        @Override public String toString() { return "Name(" + name + ", mode=" + mode + ", ci=" + ignoreCase + ")"; }
    }

    /** CriteriaOperator.And: every criterium matches. */
    public static Crit and(final Crit... crits) {
        return new Crit() { @Override public boolean match(String[] t) { for (Crit c : crits) if (!c.match(t)) return false; return true; } };
    }

    /** CriteriaOperator.Or: any criterium matches. */
    public static Crit or(final Crit... crits) {
        return new Crit() { @Override public boolean match(String[] t) { for (Crit c : crits) if (c.match(t)) return true; return false; } };
    }

    /** Collection.match of FileSieve: any of the criteria matches (an empty list matches nothing). */
    public static boolean matchAny(Crit[] crits, String[] target) {
        for (Crit c : crits) if (c.match(target)) return true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------- SystemCrawlerSieve (spec 1.3)

    public static final int T_FILE = 1, T_DIR = 2;

    /**
     * A conjunction: an entry matches when EVERY member that is set passes, checked in this order (SystemCrawlerSieve.match): type, maximum size, minimum size,
     * maximum age, minimum age, path criteria (any), name criteria (any), path exclusions (any rejects), path regexes (one must match the WHOLE absolute path),
     * area, pfp exclusions (any rejects), pfp criteria (any). An empty / unset member is no restriction.
     */
    public static final class Config {
        public Set<Sdm.Area> areas;                 // null or empty: any area
        public int targetTypes;                     // 0: any; T_FILE | T_DIR. A link and everything that is no directory counts as a file.
        public Name[] nameCriteria;
        public Crit[] pathCriteria, pathExclusions, pfpCriteria, pfpExclusions;
        public Pattern[] pathRegexes;
        public Long maximumSize, minimumSize;       // bytes of the entry itself
        public Long maximumAgeSec, minimumAgeSec;   // now - mtime

        public Config areas(Sdm.Area... a) { this.areas = a.length == 0 ? null : EnumSet.copyOf(Arrays.asList(a)); return this; }
        public Config types(int t) { this.targetTypes = t; return this; }
        public Config names(Name... n) { this.nameCriteria = n; return this; }
        public Config path(Crit... c) { this.pathCriteria = c; return this; }
        public Config notPath(Crit... c) { this.pathExclusions = c; return this; }
        public Config pfp(Crit... c) { this.pfpCriteria = c; return this; }
        public Config notPfp(Crit... c) { this.pfpExclusions = c; return this; }
        public Config regex(String... r) { this.pathRegexes = new Pattern[r.length]; for (int i = 0; i < r.length; i++) pathRegexes[i] = Pattern.compile(r[i]); return this; }
        public Config regex(Pattern... r) { this.pathRegexes = r; return this; }
        public Config maxSize(long b) { this.maximumSize = b; return this; }
        public Config minSize(long b) { this.minimumSize = b; return this; }
        public Config maxAgeSec(long s) { this.maximumAgeSec = s; return this; }
        public Config minAgeSec(long s) { this.minimumAgeSec = s; return this; }

        /**
         * @param e       the entry (size / mtime in seconds / type)
         * @param segs    its absolute path split with {@link Sdm#segments}
         * @param area    the data area the entry was found in, or null when unknown (then an area or pfp restriction rejects)
         * @param pfp     the segments below the area root (null when area is null)
         * @param nowSec  the clock, seconds since the epoch
         */
        public boolean matches(Sdm.Entry e, String[] segs, Sdm.Area area, String[] pfp, long nowSec) {
            if (targetTypes != 0) {
                int t = e.type == Sdm.DIR ? T_DIR : T_FILE;
                if ((targetTypes & t) == 0) return false;
            }
            if (maximumSize != null && e.size > maximumSize) return false;
            if (minimumSize != null && e.size < minimumSize) return false;
            if (maximumAgeSec != null || minimumAgeSec != null) {
                long ageMs = (nowSec - e.mtime) * 1000L;
                if (maximumAgeSec != null && ageMs > maximumAgeSec * 1000L) return false;
                if (minimumAgeSec != null && ageMs < minimumAgeSec * 1000L) return false;
            }
            if (pathCriteria != null && pathCriteria.length > 0 && !matchAny(pathCriteria, segs)) return false;
            if (nameCriteria != null && nameCriteria.length > 0) {
                boolean any = false;
                for (Name n : nameCriteria) if (n.matchName(e.name)) { any = true; break; }
                if (!any) return false;
            }
            if (pathExclusions != null && pathExclusions.length > 0 && matchAny(pathExclusions, segs)) return false;
            if (pathRegexes != null && pathRegexes.length > 0) {
                boolean any = false;
                for (Pattern p : pathRegexes) if (p.matcher(e.path).matches()) { any = true; break; }
                if (!any) return false;
            }
            if (areas != null && !areas.isEmpty() && (area == null || !areas.contains(area))) return false;
            if (pfpExclusions != null && pfpExclusions.length > 0) {
                if (pfp == null) return false;
                if (matchAny(pfpExclusions, pfp)) return false;
            }
            if (pfpCriteria != null && pfpCriteria.length > 0) {
                if (pfp == null) return false;
                if (!matchAny(pfpCriteria, pfp)) return false;
            }
            return true;
        }
    }
}
