package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0: the criteria of spec 1.2 (SegmentCriteriumTest, NameCriteriumTest, SegmentsExtensions,
 * filterDistinctRoots tests of upstream), the SystemCrawlerSieve conjunction of 1.3 and the distinct-roots rule of 1.5, against SdmSieve.
 */
public class SdmSieveTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static String[] s(String raw) { return SdmSieve.toSegs(raw); }
    static boolean seg(SdmSieve.Seg c, String raw) { return c.matchRaw(raw); }

    static final long NOW = 1_700_000_000L;
    static Sdm.Entry file(String path, long size, long mtime) { return new Sdm.Entry(path, size, mtime, Sdm.FILE, -1); }
    static Sdm.Entry dir(String path) { return new Sdm.Entry(path, 4096, NOW, Sdm.DIR, -1); }
    static Sdm.Entry link(String path) { return new Sdm.Entry(path, 10, NOW, Sdm.LINK, -1); }
    static boolean m(SdmSieve.Config c, Sdm.Entry e, Sdm.Area area, String pfp) {
        return c.matches(e, Sdm.segments(e.path), area, pfp == null ? null : (pfp.isEmpty() ? new String[0] : s(pfp)), NOW);
    }
    static boolean m(SdmSieve.Config c, Sdm.Entry e) { return m(c, e, null, null); }

    public static void main(String[] args) throws Exception {
        helpers();
        startsEndsContains();
        ancestors();
        distinctRoots();
        segmentCriteria();
        nameCriteria();
        operators();
        config();
        edgeCases();
        if (fails == 0) System.out.println("PASS SdmSieveTest: " + n + " checks");
        else { System.out.println("FAIL SdmSieveTest: " + n + " checks, " + fails + " failed"); System.exit(1); }
    }

    // ---------------------------------------------------------------------------------------------------------- small helpers

    static void helpers() {
        eq("segments of an absolute path", Arrays.asList(Sdm.segments("/storage/emulated/0/DCIM/a.jpg")), Arrays.asList("", "storage", "emulated", "0", "DCIM", "a.jpg"));
        eq("segments of the root", Arrays.asList(Sdm.segments("/")), Arrays.asList(""));
        eq("toSegs", Arrays.asList(s("a/b")), Arrays.asList("a", "b"));
        eq("toSegs of one name", Arrays.asList(s("abc")), Arrays.asList("abc"));
        eq("toSegs keeps a trailing empty segment", Arrays.asList(s("a/b/")), Arrays.asList("a", "b", ""));
        eq("toSegs of the empty string is one empty segment", Arrays.asList(s("")), Arrays.asList(""));
        eq("join", SdmSieve.join(s("a/b/c")), "a/b/c");
        eq("join of nothing", SdmSieve.join(new String[0]), "");
        eq("lower is Locale.ROOT", SdmSieve.lower("ABC.Log-Ä"), "abc.log-ä");
        // a Turkish default locale must not turn "I" into a dotless i
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            eq("lower ignores the default locale", SdmSieve.lower("TITLE"), "title");
        } finally { java.util.Locale.setDefault(old); }
    }

    // ---------------------------------------------------------------------------------------------------------- startsWith / endsWith / contains

    static void startsEndsContains() {
        // startsWith
        is("startsWith equal", SdmSieve.startsWith(s("a/b"), s("a/b"), false), true);
        is("startsWith longer", SdmSieve.startsWith(s("a/b/c"), s("a/b"), false), true);
        is("startsWith shorter target", SdmSieve.startsWith(s("a"), s("a/b"), false), false);
        is("startsWith different", SdmSieve.startsWith(s("a/c"), s("a/b"), false), false);
        is("startsWith case sensitive", SdmSieve.startsWith(s("A/b"), s("a/b"), false), false);
        is("startsWith ignore case", SdmSieve.startsWith(s("A/B/c"), s("a/b"), true), true);
        is("startsWith the empty prefix", SdmSieve.startsWith(s("a"), new String[0], false), true);
        is("startsWith both empty", SdmSieve.startsWith(new String[0], new String[0], false), true);
        is("startsWith null target", SdmSieve.startsWith(null, s("a"), false), false);
        is("startsWith is by whole segments", SdmSieve.startsWith(s("abc/d"), s("ab"), false), false);
        is("startsWith the absolute prefix", SdmSieve.startsWith(Sdm.segments("/data/data/x"), Sdm.segments("/data/data"), false), true);
        is("startsWith /data/datax is not /data/data", SdmSieve.startsWith(Sdm.segments("/data/datax"), Sdm.segments("/data/data"), false), false);
        // startsWith partial (Start~)
        is("Start~ one segment", SdmSieve.startsWithPartial(s("abc/def"), s("ab"), false), true);
        is("Start~ one segment, no", SdmSieve.startsWithPartial(s("abc/def"), s("bc"), false), false);
        is("Start~ last segment of two", SdmSieve.startsWithPartial(s("abc/def"), s("abc/d"), false), true);
        is("Start~ first segments must be whole", SdmSieve.startsWithPartial(s("abc/def"), s("ab/d"), false), false);
        is("Start~ three segments", SdmSieve.startsWithPartial(s("a/b/cde/f"), s("a/b/c"), false), true);
        is("Start~ ignore case", SdmSieve.startsWithPartial(s("ABC/DEF"), s("abc/d"), true), true);
        is("Start~ case sensitive", SdmSieve.startsWithPartial(s("ABC/DEF"), s("abc/d"), false), false);
        is("Start~ shorter target", SdmSieve.startsWithPartial(s("abc"), s("abc/d"), false), false);
        is("Start~ trailing empty segment", SdmSieve.startsWithPartial(s("abc/"), new String[] { "abc", "" }, false), true);

        // endsWith
        is("endsWith equal", SdmSieve.endsWith(s("a/b"), s("a/b"), false), true);
        is("endsWith longer", SdmSieve.endsWith(s("x/a/b"), s("a/b"), false), true);
        is("endsWith not the end", SdmSieve.endsWith(s("a/b/x"), s("a/b"), false), false);
        is("endsWith shorter target", SdmSieve.endsWith(s("b"), s("a/b"), false), false);
        is("endsWith ignore case", SdmSieve.endsWith(s("abc/DEF/ghi"), s("def/ghi"), true), true);
        is("endsWith case sensitive", SdmSieve.endsWith(s("abc/DEF/ghi"), s("def/ghi"), false), false);
        is("endsWith the empty suffix", SdmSieve.endsWith(s("a/b"), new String[0], false), true);
        is("endsWith End~ is not segment equality", SdmSieve.endsWith(s("abc/def/ghi"), s("ef/ghi"), false), false);
        // endsWith partial (End~)
        is("End~ two segments", SdmSieve.endsWithPartial(s("abc/def/ghi"), s("ef/ghi"), false), true);
        is("End~ one segment", SdmSieve.endsWithPartial(s("abc/def"), s("ef"), false), true);
        is("End~ the later segments must be whole", SdmSieve.endsWithPartial(s("abc/def/ghi"), s("ef/hi"), false), false);
        is("End~ equal size", SdmSieve.endsWithPartial(s("abc/def/ghi"), s("c/def/ghi"), false), true);
        is("End~ ignore case", SdmSieve.endsWithPartial(s("abc/DEF/ghi"), s("ef/ghi"), true), true);
        is("End~ case sensitive", SdmSieve.endsWithPartial(s("abc/DEF/ghi"), s("ef/ghi"), false), false);

        // contains (contiguous sub list)
        is("contains one", SdmSieve.contains(s("abc/def"), s("abc"), false), true);
        is("contains in the middle", SdmSieve.contains(s("x/abc/def/y"), s("abc/def"), false), true);
        is("contains not contiguous", SdmSieve.contains(s("abc/x/def"), s("abc/def"), false), false);
        is("contains is whole segments", SdmSieve.contains(s("abcd/def"), s("abc"), false), false);
        is("contains ignore case", SdmSieve.contains(s("abc/DEF/ghi"), s("def"), true), true);
        is("contains case sensitive", SdmSieve.contains(s("abc/DEF/ghi"), s("def"), false), false);
        is("contains the empty part", SdmSieve.contains(s("a"), new String[0], false), true);
        is("contains longer than target", SdmSieve.contains(s("a"), s("a/b"), false), false);
        // containsSegments (the SegmentExclusion)
        is("containsSegments plain", SdmSieve.containsSegments(Sdm.segments("/sdcard/DCIM/Camera/photo.png"), s("DCIM/Camera"), false, true), true);
        is("containsSegments plain, no", SdmSieve.containsSegments(Sdm.segments("/sdcard/DCIM/Cameras/photo.png"), s("DCIM/Camera"), false, true), false);
        is("containsSegments partial is a substring of the joined path", SdmSieve.containsSegments(s("abc/def"), s("bc/de"), true, false), true);
        is("containsSegments not partial", SdmSieve.containsSegments(s("abc/def"), s("bc/de"), false, false), false);
        is("containsSegments partial ignore case", SdmSieve.containsSegments(s("ABC/def"), s("bc/de"), true, true), true);
        is("containsSegments partial case sensitive", SdmSieve.containsSegments(s("ABC/def"), s("bc/de"), true, false), false);
        is("containsSegments: fewer segments than the pattern is false even when partial", SdmSieve.containsSegments(s("abcdef"), s("abc/def"), true, true), false);
        is("containsSegments partial across a boundary", SdmSieve.containsSegments(s("x/.indexeddb.leveldb/y"), s(".indexeddb.leveldb"), true, true), true);
        is("containsSegments partial inside a name", SdmSieve.containsSegments(s("x/app.indexeddb.leveldb/y"), s(".indexeddb.leveldb"), true, true), true);
        is("containsSegments partial, no match inside a name when not partial", SdmSieve.containsSegments(s("x/app.indexeddb.leveldb/y"), s(".indexeddb.leveldb"), false, true), false);

        // sameSegs / isAncestorSegs / segmentContains
        is("sameSegs", SdmSieve.sameSegs(s("a/b"), s("a/b"), false), true);
        is("sameSegs length", SdmSieve.sameSegs(s("a/b"), s("a/b/"), false), false);
        is("sameSegs ci", SdmSieve.sameSegs(s("a/B"), s("A/b"), true), true);
        is("sameSegs null", SdmSieve.sameSegs(null, null, false), true);
        is("sameSegs null vs value", SdmSieve.sameSegs(null, s("a"), false), false);
        is("isAncestorSegs strictly deeper", SdmSieve.isAncestorSegs(s("a"), s("a/b"), false), true);
        is("isAncestorSegs not the same", SdmSieve.isAncestorSegs(s("a/b"), s("a/b"), false), false);
        is("isAncestorSegs not shorter", SdmSieve.isAncestorSegs(s("a/b"), s("a"), false), false);
        is("segmentContains forwards", SdmSieve.segmentContains(s("abc/DEF"), "abc", 0, false, false, false), true);
        is("segmentContains backwards", SdmSieve.segmentContains(s("abc/DEF"), "DEF", 0, true, false, false), true);
        is("segmentContains out of range", SdmSieve.segmentContains(s("abc/DEF"), "abc", 2, false, false, false), false);
        is("segmentContains negative index", SdmSieve.segmentContains(s("abc/DEF"), "abc", 5, true, false, false), false);
        is("segmentContains partial", SdmSieve.segmentContains(s("abc/DEF"), "DE", 1, false, false, true), true);
    }

    // ---------------------------------------------------------------------------------------------------------- isAncestorOf (path based)

    static void ancestors() {
        is("ancestor of a child", SdmSieve.isAncestorOf("/a/b", "/a/b/c"), true);
        is("ancestor of a grandchild", SdmSieve.isAncestorOf("/a", "/a/b/c"), true);
        is("not of itself", SdmSieve.isAncestorOf("/a/b", "/a/b"), false);
        is("not of a longer name", SdmSieve.isAncestorOf("/a/b", "/a/bc"), false);
        is("not of the parent", SdmSieve.isAncestorOf("/a/b/c", "/a/b"), false);
        is("the root is an ancestor of everything else", SdmSieve.isAncestorOf("/", "/a"), true);
        is("the root is not its own ancestor", SdmSieve.isAncestorOf("/", "/"), false);
        is("case sensitive", SdmSieve.isAncestorOf("/a/B", "/a/b/c"), false);
        is("null", SdmSieve.isAncestorOf(null, "/a"), false);
        is("sibling with a shared prefix", SdmSieve.isAncestorOf("/data/log/knoxsdk.log.0", "/data/log/knoxsdk.log.0.1"), false);
    }

    // ---------------------------------------------------------------------------------------------------------- distinctRoots

    static void distinctRoots() {
        eq("roots of two trees", SdmSieve.distinctRoots(Arrays.asList("/test/file1", "/test/file1/sub", "/test/file2", "/test/file2/sub")), Arrays.asList("/test/file1", "/test/file2"));
        eq("the knoxsdk edge case: names that only share a prefix are all roots",
            SdmSieve.distinctRoots(Arrays.asList("/data/log/knoxsdk.log.0.lck", "/data/log/knoxsdk.log.0", "/data/log/knoxsdk.log.0.1.lck", "/data/log/knoxsdk.log.0.1")),
            Arrays.asList("/data/log/knoxsdk.log.0.lck", "/data/log/knoxsdk.log.0", "/data/log/knoxsdk.log.0.1.lck", "/data/log/knoxsdk.log.0.1"));
        eq("a deeper path first in the input", SdmSieve.distinctRoots(Arrays.asList("/a/b/c/d", "/a/b")), Arrays.asList("/a/b"));
        eq("only the shallowest of a chain survives", SdmSieve.distinctRoots(Arrays.asList("/a/b/c", "/a/b", "/a/b/c/d", "/a")), Arrays.asList("/a"));
        eq("duplicates once", SdmSieve.distinctRoots(Arrays.asList("/a/b", "/a/b", "/a/b/c")), Arrays.asList("/a/b"));
        eq("empty", SdmSieve.distinctRoots(new ArrayList<String>()), new ArrayList<String>());
        eq("the root swallows everything", SdmSieve.distinctRoots(Arrays.asList("/a", "/", "/b/c")), Arrays.asList("/"));
        eq("a grandchild of a kept root is dropped, a sibling stays", SdmSieve.distinctRoots(Arrays.asList("/a/x/y", "/a/x", "/a/z")), Arrays.asList("/a/x", "/a/z"));
        eq("ties keep the input order", SdmSieve.distinctRoots(Arrays.asList("/b/1", "/a/1", "/c/1")), Arrays.asList("/b/1", "/a/1", "/c/1"));
        eq("case sensitive", SdmSieve.distinctRoots(Arrays.asList("/A/b", "/a/b/c")), Arrays.asList("/A/b", "/a/b/c"));
        eq("null entries are ignored", SdmSieve.distinctRoots(Arrays.asList("/a", null, "/a/b")), Arrays.asList("/a"));
        // performance: the upstream fold is quadratic, this one must not be
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 200000; i++) many.add("/parentA/parentB/file" + i);
        long t = System.nanoTime();
        eq("200k files are all roots", SdmSieve.distinctRoots(many).size(), 200000);
        is("... in a blink", (System.nanoTime() - t) / 1_000_000 < 5000, true);
        List<String> nested = new ArrayList<String>();
        for (int i = 0; i < 20000; i++) { nested.add("/r/d" + i); nested.add("/r/d" + i + "/f"); nested.add("/r/d" + i + "/f/g"); }
        eq("20k folders with 2 levels below: only the folders", SdmSieve.distinctRoots(nested).size(), 20000);
    }

    // ---------------------------------------------------------------------------------------------------------- SegmentCriterium (SegmentCriteriumTest of upstream)

    static void segmentCriteria() {
        // ANCESTOR
        is("anc: a child is not the ancestor", seg(SdmSieve.Seg.anc("def"), "abc/def"), false);
        is("anc: abc is the ancestor of abc/def", seg(SdmSieve.Seg.anc("abc"), "abc/def"), true);
        is("anc: not the item itself", seg(SdmSieve.Seg.anc("abc/def"), "abc/def"), false);
        is("anc: casing off", seg(SdmSieve.Seg.anc("abc", false), "ABC/def"), false);
        is("anc: casing on", seg(SdmSieve.Seg.anc("abc", true), "ABC/def"), true);
        is("anc: default is ignore case", seg(SdmSieve.Seg.anc("abc"), "ABC/def"), true);
        // START
        is("start: no", seg(SdmSieve.Seg.start("def"), "abc/def"), false);
        is("start: yes", seg(SdmSieve.Seg.start("abc"), "abc/def"), true);
        is("start: the item itself matches", seg(SdmSieve.Seg.start("abc/def"), "abc/def"), true);
        is("start: partial off", seg(SdmSieve.Seg.of("ab", SdmSieve.Seg.START, true, false), "abc/def"), false);
        is("start: partial on", seg(SdmSieve.Seg.of("ab", SdmSieve.Seg.START, true, true), "abc/def"), true);
        is("start: two segments, partial off", seg(SdmSieve.Seg.of("abc/d", SdmSieve.Seg.START, true, false), "abc/def"), false);
        is("start: two segments, partial on", seg(SdmSieve.Seg.of("abc/d", SdmSieve.Seg.START, true, true), "abc/def"), true);
        is("start: partial, trailing empty segment (raw)", seg(SdmSieve.Seg.startPartial("abc/"), "abc/"), true);
        is("start: partial, trailing empty segment (segments)", SdmSieve.Seg.ofSegments(new String[] { "abc", "" }, SdmSieve.Seg.START, true, true).match(new String[] { "abc", "" }), true);
        is("start: casing off", seg(SdmSieve.Seg.of("abc", SdmSieve.Seg.START, false, false), "ABC/def"), false);
        is("start: casing on", seg(SdmSieve.Seg.of("abc", SdmSieve.Seg.START, true, false), "ABC/def"), true);
        // CONTAIN
        is("contain: no", seg(SdmSieve.Seg.contain("123"), "abc/def"), false);
        is("contain: yes", seg(SdmSieve.Seg.contain("abc"), "abc/def"), true);
        is("contain: partial off", seg(SdmSieve.Seg.of("bc/de", SdmSieve.Seg.CONTAIN, true, false), "abc/def"), false);
        is("contain: partial on", seg(SdmSieve.Seg.of("bc/de", SdmSieve.Seg.CONTAIN, true, true), "abc/def"), true);
        is("contain: casing off", seg(SdmSieve.Seg.of("def", SdmSieve.Seg.CONTAIN, false, false), "abc/DEF/ghi"), false);
        is("contain: casing on", seg(SdmSieve.Seg.of("def", SdmSieve.Seg.CONTAIN, true, false), "abc/DEF/ghi"), true);
        // END
        is("end: no", seg(SdmSieve.Seg.end("abc/def"), "abc/def/ghi"), false);
        is("end: yes", seg(SdmSieve.Seg.end("def/ghi"), "abc/def/ghi"), true);
        is("end: partial off", seg(SdmSieve.Seg.of("ef/ghi", SdmSieve.Seg.END, true, false), "abc/def/ghi"), false);
        is("end: partial on", seg(SdmSieve.Seg.of("ef/ghi", SdmSieve.Seg.END, true, true), "abc/def/ghi"), true);
        is("end: casing off", seg(SdmSieve.Seg.of("def/ghi", SdmSieve.Seg.END, false, false), "abc/DEF/ghi"), false);
        is("end: casing on", seg(SdmSieve.Seg.of("def/ghi", SdmSieve.Seg.END, true, false), "abc/DEF/ghi"), true);
        // MATCH (equal)
        is("equal: trailing slash is another segment", seg(SdmSieve.Seg.eq("abc/def/"), "abc/def"), false);
        is("equal: yes", seg(SdmSieve.Seg.eq("abc/def"), "abc/def"), true);
        is("equal: casing off", seg(SdmSieve.Seg.of("abc/def", SdmSieve.Seg.EQUAL, false, false), "abc/DEF"), false);
        is("equal: casing on", seg(SdmSieve.Seg.of("abc/def", SdmSieve.Seg.EQUAL, true, false), "abc/DEF"), true);
        is("equal: not a descendant", seg(SdmSieve.Seg.eq("abc"), "abc/def"), false);
        // SPECIFIC
        boolean threw = false;
        try { SdmSieve.Seg.specific("ABC/DEF", 0, false, true, false).matchRaw("abc/DEF"); } catch (IllegalArgumentException e) { threw = true; }
        is("specific: more than one pattern segment is an error", threw, true);
        is("specific: case differs", SdmSieve.Seg.specific("DEF", 0, false, false, false).matchRaw("abc/DEF"), false);
        is("specific: first", SdmSieve.Seg.specific("abc", 0, false, false, false).matchRaw("abc/DEF"), true);
        is("specific: backwards", SdmSieve.Seg.specific("DEF", 0, true, false, false).matchRaw("abc/DEF"), true);
        is("specific: ignore case", SdmSieve.Seg.specific("ABC", 0, false, true, false).matchRaw("abc/DEF"), true);
        is("specific: partial", SdmSieve.Seg.specific("DE", 1, false, false, true).matchRaw("abc/DEF"), true);
        is("specific: backwards, ignore case, partial", SdmSieve.Seg.specific("BC", 1, true, true, true).matchRaw("abc/DEF"), true);
        is("specific: out of range", SdmSieve.Seg.specific("abc", 3, false, true, false).matchRaw("abc/DEF"), false);
    }

    // ---------------------------------------------------------------------------------------------------------- NameCriterium (NameCriteriumTest of upstream)

    static void nameCriteria() {
        is("name start: no", SdmSieve.Name.start("abc").matchName("ghi"), false);
        is("name start: yes", SdmSieve.Name.start("ghi").matchName("ghi"), true);
        is("name start: casing off", SdmSieve.Name.of("ghi", SdmSieve.Name.START, false).matchName("GHI"), false);
        is("name start: casing on", SdmSieve.Name.of("ghi", SdmSieve.Name.START, true).matchName("GHI"), true);
        is("name start: a prefix", SdmSieve.Name.start(".trashed-").matchName(".trashed-123-a.jpg"), true);
        is("name start: longer than the name", SdmSieve.Name.start("abcd").matchName("abc"), false);
        is("name contain: no", SdmSieve.Name.contain("e").matchName("ghi"), false);
        is("name contain: yes", SdmSieve.Name.contain("h").matchName("ghi"), true);
        is("name contain: casing off", SdmSieve.Name.of("h", SdmSieve.Name.CONTAIN, false).matchName("GHI"), false);
        is("name contain: casing on", SdmSieve.Name.of("h", SdmSieve.Name.CONTAIN, true).matchName("GHI"), true);
        is("name end: no", SdmSieve.Name.end("h").matchName("ghi"), false);
        is("name end: yes", SdmSieve.Name.end("hi").matchName("ghi"), true);
        is("name end: casing off", SdmSieve.Name.of("h", SdmSieve.Name.END, false).matchName("gHI"), false);
        is("name end: casing on", SdmSieve.Name.of("hi", SdmSieve.Name.END, true).matchName("gHI"), true);
        is("name end: an extension", SdmSieve.Name.end(".log").matchName("A.LOG"), true);
        is("name end: longer than the name", SdmSieve.Name.end("abcd").matchName("bcd"), false);
        is("name equal: no", SdmSieve.Name.eq("def").matchName("ghi"), false);
        is("name equal: yes", SdmSieve.Name.eq("ghi").matchName("ghi"), true);
        is("name equal: casing off", SdmSieve.Name.of("ghi", SdmSieve.Name.EQUAL, false).matchName("GHI"), false);
        is("name equal: casing on", SdmSieve.Name.of("ghi", SdmSieve.Name.EQUAL, true).matchName("GHI"), true);
        is("name equal: not a part", SdmSieve.Name.eq("gh").matchName("ghi"), false);
        is("name as a Crit matches the last segment", SdmSieve.Name.end(".log").match(s("a/b/c.log")), true);
        is("name as a Crit on nothing", SdmSieve.Name.end(".log").match(new String[0]), false);
    }

    // ---------------------------------------------------------------------------------------------------------- And / Or (CriteriaOperator)

    static void operators() {
        SdmSieve.Crit a = SdmSieve.Seg.start("a");
        SdmSieve.Crit end = SdmSieve.Name.end(".jpg");
        is("and: both", SdmSieve.and(a, end).match(s("a/x/p.jpg")), true);
        is("and: one fails", SdmSieve.and(a, end).match(s("a/x/p.png")), false);
        is("or: one", SdmSieve.or(a, end).match(s("b/p.jpg")), true);
        is("or: none", SdmSieve.or(a, end).match(s("b/p.png")), false);
        is("nested", SdmSieve.or(SdmSieve.and(a, end), SdmSieve.Seg.start("z")).match(s("z/q")), true);
        is("empty and matches", SdmSieve.and().match(s("a")), true);
        is("empty or matches nothing", SdmSieve.or().match(s("a")), false);
        is("matchAny", SdmSieve.matchAny(new SdmSieve.Crit[] { SdmSieve.Seg.start("q"), SdmSieve.Seg.start("a") }, s("a/b")), true);
        is("matchAny of nothing", SdmSieve.matchAny(new SdmSieve.Crit[0], s("a/b")), false);
    }

    // ---------------------------------------------------------------------------------------------------------- SystemCrawlerSieve conjunction (1.3)

    static void config() {
        // an empty config matches everything
        is("empty config", m(new SdmSieve.Config(), file("/a/b", 1, NOW)), true);
        // target types
        SdmSieve.Config files = new SdmSieve.Config().types(SdmSieve.T_FILE), dirs = new SdmSieve.Config().types(SdmSieve.T_DIR), both = new SdmSieve.Config().types(SdmSieve.T_FILE | SdmSieve.T_DIR);
        is("file config: a file", m(files, file("/a/b", 1, NOW)), true);
        is("file config: a directory", m(files, dir("/a/b")), false);
        is("file config: a link counts as a file", m(files, link("/a/b")), true);
        is("file config: an unknown type counts as a file", m(files, new Sdm.Entry("/a/b", 0, NOW, Sdm.OTHER, -1)), true);
        is("dir config: a directory", m(dirs, dir("/a/b")), true);
        is("dir config: a file", m(dirs, file("/a/b", 1, NOW)), false);
        is("dir config: a link is never a directory", m(dirs, link("/a/b")), false);
        is("both types", m(both, dir("/a")) && m(both, file("/a", 1, NOW)), true);
        // sizes: a bigger one than the maximum and a smaller one than the minimum are out, the limit itself is in
        SdmSieve.Config sz = new SdmSieve.Config().minSize(10).maxSize(20);
        is("size below the minimum", m(sz, file("/a", 9, NOW)), false);
        is("size at the minimum", m(sz, file("/a", 10, NOW)), true);
        is("size at the maximum", m(sz, file("/a", 20, NOW)), true);
        is("size above the maximum", m(sz, file("/a", 21, NOW)), false);
        is("maximum size 0 allows empty files only", m(new SdmSieve.Config().maxSize(0), file("/a", 0, NOW)) && !m(new SdmSieve.Config().maxSize(0), file("/a", 1, NOW)), true);
        // ages: older than the maximum is out, younger than the minimum is out
        SdmSieve.Config age = new SdmSieve.Config().minAgeSec(100).maxAgeSec(1000);
        is("age younger than the minimum", m(age, file("/a", 1, NOW - 99)), false);
        is("age at the minimum", m(age, file("/a", 1, NOW - 100)), true);
        is("age at the maximum", m(age, file("/a", 1, NOW - 1000)), true);
        is("age older than the maximum", m(age, file("/a", 1, NOW - 1001)), false);
        is("a minimum age of 14 days", m(new SdmSieve.Config().minAgeSec(14 * 86400L), file("/a", 1, NOW - 15 * 86400L)) && !m(new SdmSieve.Config().minAgeSec(14 * 86400L), file("/a", 1, NOW - 13 * 86400L)), true);
        is("an mtime in the future is younger than any minimum", m(new SdmSieve.Config().minAgeSec(1), file("/a", 1, NOW + 500)), false);
        // path criteria (any), name criteria (any), exclusions
        SdmSieve.Config pc = new SdmSieve.Config().path(SdmSieve.Seg.contain("x"), SdmSieve.Seg.contain("y"));
        is("path criteria: any one", m(pc, file("/a/y/b", 1, NOW)), true);
        is("path criteria: none", m(pc, file("/a/z/b", 1, NOW)), false);
        is("path criteria see the absolute segments", m(new SdmSieve.Config().path(SdmSieve.Seg.start("a")), file("/a/b", 1, NOW)), false);
        is("path criteria with the leading empty segment", m(new SdmSieve.Config().path(SdmSieve.Seg.of("/a", SdmSieve.Seg.START, true, false)), file("/a/b", 1, NOW)), true);
        SdmSieve.Config nc = new SdmSieve.Config().names(SdmSieve.Name.end(".log"), SdmSieve.Name.eq("x"));
        is("name criteria: ends with", m(nc, file("/a/b.LOG", 1, NOW)), true);
        is("name criteria: equals", m(nc, file("/a/x", 1, NOW)), true);
        is("name criteria: none", m(nc, file("/a/b.txt", 1, NOW)), false);
        is("name criteria look at the name only", m(nc, file("/a.log/b", 1, NOW)), false);
        SdmSieve.Config ex = new SdmSieve.Config().notPath(SdmSieve.Seg.contain("leveldb"));
        is("path exclusion rejects", m(ex, file("/a/leveldb/b", 1, NOW)), false);
        is("path exclusion lets others pass", m(ex, file("/a/b", 1, NOW)), true);
        is("exclusion beats a criterion", m(new SdmSieve.Config().names(SdmSieve.Name.end(".log")).notPath(SdmSieve.Seg.contain("t/Paths")), file("/a/t/Paths/x.log", 1, NOW)), false);
        // regexes: the WHOLE path must match, one of them is enough
        SdmSieve.Config rx = new SdmSieve.Config().regex("^(?:[\\W\\w]+/LOST\\.DIR/[\\W\\w]+)$");
        is("regex full match", m(rx, file("/storage/emulated/0/LOST.DIR/a", 1, NOW)), true);
        is("regex nested", m(rx, file("/storage/x/LOST.DIR/b/c", 1, NOW)), true);
        is("regex needs something after the folder", m(rx, file("/storage/x/LOST.DIR", 1, NOW)), false);
        is("regex is case sensitive", m(rx, file("/storage/x/lost.dir/a", 1, NOW)), false);
        is("regex without anchors still needs a full match", m(new SdmSieve.Config().regex("/abc"), file("/abc/def", 1, NOW)), false);
        is("regex one of two", m(new SdmSieve.Config().regex("/nope", "/abc/.+"), file("/abc/def", 1, NOW)), true);
        is("dot does not match a line break", m(new SdmSieve.Config().regex(".+/usagestats/[0-9]+/.+"), file("/x/usagestats/0/a\nb", 1, NOW)), false);
        is("[\\W\\w] does", m(new SdmSieve.Config().regex("^(?:[\\W\\w]+/LOST\\.DIR/[\\W\\w]+)$"), file("/x/LOST.DIR/a\nb", 1, NOW)), true);
        // areas and pfp
        SdmSieve.Config ar = new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE);
        is("area in the set", m(ar, file("/a", 1, NOW), Sdm.Area.PORTABLE, "a"), true);
        is("area not in the set", m(ar, file("/a", 1, NOW), Sdm.Area.DATA, "a"), false);
        is("an unknown area is rejected when areas are set", m(ar, file("/a", 1, NOW), null, null), false);
        is("an unknown area passes when none are set", m(new SdmSieve.Config(), file("/a", 1, NOW), null, null), true);
        SdmSieve.Config pf = new SdmSieve.Config().pfp(SdmSieve.Seg.anc("Pictures/Screenshots"));
        is("pfp ancestor: below", m(pf, file("/r/Pictures/Screenshots/a.png", 1, NOW), Sdm.Area.SDCARD, "Pictures/Screenshots/a.png"), true);
        is("pfp ancestor: the folder itself", m(pf, dir("/r/Pictures/Screenshots"), Sdm.Area.SDCARD, "Pictures/Screenshots"), false);
        is("pfp criteria without a pfp are rejected", m(pf, file("/r/Pictures/Screenshots/a.png", 1, NOW), Sdm.Area.SDCARD, null), false);
        SdmSieve.Config pe = new SdmSieve.Config().notPfp(SdmSieve.Seg.anc("Android"));
        is("pfp exclusion rejects", m(pe, dir("/r/Android/data"), Sdm.Area.SDCARD, "Android/data"), false);
        is("pfp exclusion lets others pass", m(pe, dir("/r/DCIM/x"), Sdm.Area.SDCARD, "DCIM/x"), true);
        is("pfp is relative to the area root: the root part of the path is not seen", m(new SdmSieve.Config().pfp(SdmSieve.Seg.start("storage")), file("/storage/a", 1, NOW), Sdm.Area.SDCARD, "a"), false);
        is("an empty pfp (the root itself) never starts with anything", m(new SdmSieve.Config().pfp(SdmSieve.Seg.start("a")), dir("/r"), Sdm.Area.SDCARD, ""), false);
        // a conjunction: every member must pass
        SdmSieve.Config all = new SdmSieve.Config().areas(Sdm.Area.SDCARD).types(SdmSieve.T_FILE).names(SdmSieve.Name.end(".tmp")).minSize(1).notPfp(SdmSieve.Seg.contain("keep"));
        is("conjunction passes", m(all, file("/r/a.tmp", 5, NOW), Sdm.Area.SDCARD, "a.tmp"), true);
        is("conjunction: the type fails", m(all, dir("/r/a.tmp"), Sdm.Area.SDCARD, "a.tmp"), false);
        is("conjunction: the name fails", m(all, file("/r/a.txt", 5, NOW), Sdm.Area.SDCARD, "a.txt"), false);
        is("conjunction: the size fails", m(all, file("/r/a.tmp", 0, NOW), Sdm.Area.SDCARD, "a.tmp"), false);
        is("conjunction: the area fails", m(all, file("/r/a.tmp", 5, NOW), Sdm.Area.PORTABLE, "a.tmp"), false);
        is("conjunction: the exclusion fails", m(all, file("/r/keep/a.tmp", 5, NOW), Sdm.Area.SDCARD, "keep/a.tmp"), false);
        // the TempFilesFilter quirk of upstream: relative Anc exclusions on the ABSOLUTE segments can never match
        SdmSieve.Config quirk = new SdmSieve.Config().notPath(SdmSieve.Seg.anc("backup/pending"));
        is("upstream quirk: Anc(backup/pending) on absolute segments never excludes", m(quirk, file("/data/backup/pending/x.tmp", 1, NOW)), true);
        SdmSieve.Config fixed = new SdmSieve.Config().notPfp(SdmSieve.Seg.contain("backup/pending"));
        is("the port's fix: Contain on the pfp excludes", m(fixed, file("/data/backup/pending/x.tmp", 1, NOW), Sdm.Area.DATA, "backup/pending/x.tmp"), false);
        is("... and lets other files pass", m(fixed, file("/data/other/x.tmp", 1, NOW), Sdm.Area.DATA, "other/x.tmp"), true);
        // the configs are not tied to a clock: now is a parameter
        is("now is a parameter", new SdmSieve.Config().minAgeSec(10).matches(file("/a", 1, 100), Sdm.segments("/a"), null, null, 105), false);
        is("... later it is old enough", new SdmSieve.Config().minAgeSec(10).matches(file("/a", 1, 100), Sdm.segments("/a"), null, null, 110), true);
        is("EnumSet areas", new SdmSieve.Config().areas(Sdm.Area.DATA).areas.equals(EnumSet.of(Sdm.Area.DATA)), true);
    }

    // ---------------------------------------------------------------------------------------------------------- spec 1.2 / 8.8 item 7

    static void edgeCases() {
        // a JSON "startsWith" rule that ends in "/" has a final empty segment
        SdmSieve.Seg trailing = SdmSieve.Seg.of("a/b/", SdmSieve.Seg.START, true, false);
        is("start 'a/b/' does not match the folder itself", trailing.matchRaw("a/b"), false);
        is("start 'a/b/' (not partial) does not match a child either: the empty segment must match", trailing.matchRaw("a/b/c"), false);
        is("start 'a/b/' matches the same path with a trailing slash", trailing.matchRaw("a/b/"), true);
        SdmSieve.Seg trailingP = SdmSieve.Seg.startPartial("a/b/");
        is("start~ 'a/b/' does not match the folder itself", trailingP.matchRaw("a/b"), false);
        is("start~ 'a/b/' matches strictly below the folder", trailingP.matchRaw("a/b/c"), true);
        is("start~ 'a/b/' matches deep below", trailingP.matchRaw("a/b/c/d"), true);
        is("start~ 'a/b/' does not match a sibling", trailingP.matchRaw("a/bc/d"), false);
        // Anc is strictly deeper than the pattern
        is("anc: the folder itself is not matched", SdmSieve.Seg.anc("a/b").matchRaw("a/b"), false);
        is("anc: its children are", SdmSieve.Seg.anc("a/b").matchRaw("a/b/c"), true);
        is("anc: a trailing slash pattern needs the empty segment below", SdmSieve.Seg.anc("a/b/").matchRaw("a/b/c"), false);
        // Contain~ is a substring test on the joined path, not on the segments
        is("contain~ finds a substring across a name", SdmSieve.Seg.containPartial(".indexeddb.leveldb").matchRaw("x/app.indexeddb.leveldb/y"), true);
        is("contain~ finds a substring across a boundary", SdmSieve.Seg.containPartial("x/ap").matchRaw("x/app/y"), true);
        is("contain~ matches 'p/y' although no segment is 'p'", SdmSieve.Seg.containPartial("p/y").matchRaw("x/app/y"), true);
        is("contain (whole segments) does not", SdmSieve.Seg.contain("p/y").matchRaw("x/app/y"), false);
        is("contain~ with more pattern segments than the target is false", SdmSieve.Seg.containPartial("a/b/c").matchRaw("a/b"), false);
        is("contain~ joins with '/' only, a name with a dot is no separator", SdmSieve.Seg.containPartial("a/b").matchRaw("a.b/c"), false);
        // Specific: out of range and negative index
        is("specific: index beyond the end", SdmSieve.Seg.specific("a", 5, false, true, false).matchRaw("a"), false);
        is("specific: backwards beyond the start", SdmSieve.Seg.specific("a", 5, true, true, false).matchRaw("a"), false);
        // case: the area rule decides, the criteria default to ignore case
        is("default criteria ignore case", SdmSieve.Seg.start("dcim").matchRaw("DCIM/Camera"), true);
        is("explicit case sensitive", SdmSieve.Seg.of("dcim", SdmSieve.Seg.START, false, false).matchRaw("DCIM/Camera"), false);
        // an empty pattern segment list
        is("start of nothing", SdmSieve.startsWith(s("a"), new String[0], true), true);
        is("equal of two empties", SdmSieve.sameSegs(new String[0], new String[0], false), true);
        // lowercase of non ASCII
        is("ignore case with umlauts", SdmSieve.Seg.eq("Ärger").matchRaw("ärger"), true);
        // sorting helper sanity: distinctRoots output is usable as a list
        List<String> r = SdmSieve.distinctRoots(Arrays.asList("/b", "/a"));
        Collections.sort(r);
        eq("sorted roots", r, Arrays.asList("/a", "/b"));
    }
}
