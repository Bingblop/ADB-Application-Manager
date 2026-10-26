package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The OpenType layout tables GSUB and GPOS (and the parts of GDEF that go with them): read into a small model, written back, and patched in
 * place.
 *
 * <p>The Custom Fonts tab changes layout tables in two places. A variable font's static instance ({@code FontInstancer}) adds the variation
 * deltas into GPOS values and anchors ({@link #applyGposDeltas}), picks the FeatureVariations record that matches the location
 * ({@link #applyFeatureVariations}) and drops GDEF's variation store ({@link #instanceGdef}). Merging a colour emoji font ({@code EmojiMerge})
 * moves the emoji glyph ids past the text font's ({@link #shiftGlyphs}) and appends the emoji lookups to the text font's ({@link #merge}).
 *
 * <p>The model ({@link Table}) holds the scripts, features and lookups; lookup subtables are not decoded but kept as offsets into the source
 * table they came from. {@link #write} embeds the source tables byte for byte at the end of the new table and reaches every subtable through an
 * Extension lookup (32-bit offsets), so the original subtables and their internal 16-bit offsets stay valid however big the table gets. The
 * patchers change the table they are given in place (pass a copy to keep the original) and keep a record of what they visited, so a coverage,
 * class definition or subtable that several lookups share is changed once. The input is untrusted: every read is bounds-checked and a bad table ends in a {@link Sfnt.FontException}.
 */
public final class OtLayout {

    private OtLayout() {}

    /** Lookup types that wrap another lookup's subtable (GSUB 7, GPOS 9) and the contextual types that call other lookups. */
    static final int GSUB_EXTENSION = 7, GPOS_EXTENSION = 9;
    static final int GSUB_CONTEXT = 5, GSUB_CHAIN = 6, GPOS_CONTEXT = 7, GPOS_CHAIN = 8;
    /** Lookup flag bit: the lookup names a mark glyph set, stored after its subtable offsets. */
    public static final int USE_MARK_FILTERING_SET = 0x0010;
    /** Progress stage name used by the long steps here. */
    public static final String STAGE = "layout";

    // ---------------------------------------------------------------------------------------------
    // The model
    // ---------------------------------------------------------------------------------------------

    /** A language system: the features that apply for one script and language. */
    public static final class LangSys {
        /** The required feature's index, or 0xFFFF for none. */
        public int required = 0xFFFF;
        /** Indexes into {@link Table#features}. */
        public final List<Integer> features = new ArrayList<Integer>();

        LangSys copy() {
            LangSys l = new LangSys();
            l.required = required;
            l.features.addAll(features);
            return l;
        }
    }

    /** A script: its default language system (null when it has none) and the language systems by tag. */
    public static final class Script {
        public final String tag;
        public LangSys dflt;
        public final TreeMap<String, LangSys> langs = new TreeMap<String, LangSys>();

        public Script(String tag) { this.tag = tag; }

        Script copy(String newTag) {
            Script s = new Script(newTag);
            s.dflt = dflt == null ? null : dflt.copy();
            for (Map.Entry<String, LangSys> e : langs.entrySet()) s.langs.put(e.getKey(), e.getValue().copy());
            return s;
        }
    }

    /** A feature: its tag, the lookups it turns on (indexes into {@link Table#lookups}) and its FeatureParams bytes (null for none). */
    public static final class Feature {
        public final String tag;
        public final List<Integer> lookups = new ArrayList<Integer>();
        /** A copy of the FeatureParams table ('size', 'ssNN', 'cvNN'); the writer puts it next to the feature. */
        public byte[] params;

        public Feature(String tag) { this.tag = tag; }
    }

    /** A lookup subtable: where it is (source table index and absolute offset in it). Extension subtables are already unwrapped. */
    public static final class Subtable {
        public final int source, offset;

        public Subtable(int source, int offset) { this.source = source; this.offset = offset; }
    }

    /** A lookup: its real type (an Extension lookup has the type it wraps), flag, mark filtering set and subtables. */
    public static final class Lookup {
        public int type, flag, markFilteringSet;
        public final List<Subtable> subtables = new ArrayList<Subtable>();
    }

    /** A GSUB or GPOS table: scripts, features and lookups, and the source tables the subtables live in. */
    public static final class Table {
        public final boolean gsub;
        /** The tables the subtables point into, unchanged; {@link #write} embeds the ones that are used. */
        public final List<byte[]> sources = new ArrayList<byte[]>();
        public final List<Script> scripts = new ArrayList<Script>();
        public final List<Feature> features = new ArrayList<Feature>();
        public final List<Lookup> lookups = new ArrayList<Lookup>();
        /** Absolute offset of the FeatureVariations table in sources[0], or -1. */
        int featureVariations = -1;

        public Table(boolean gsub) { this.gsub = gsub; }

        public boolean hasFeatureVariations() { return featureVariations >= 0; }

        public Script script(String tag) {
            for (Script s : scripts) if (s.tag.equals(tag)) return s;
            return null;
        }

        String name() { return gsub ? "GSUB" : "GPOS"; }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a GSUB ({@code gsub} true) or GPOS table into the model. The model keeps {@code table} itself as its first source (not a copy):
     * patch a copy before parsing when the source must stay as it was. Feature and lookup indexes that point past their lists are dropped
     * (HarfBuzz ignores them too).
     */
    public static Table parse(byte[] table, boolean gsub) throws Sfnt.FontException {
        String name = gsub ? "GSUB" : "GPOS";
        if (table == null || table.length < 10) throw new Sfnt.FontException("This font's " + name + " table is too short.");
        int major = Sfnt.u16(table, 0), minor = Sfnt.u16(table, 2);
        if (major != 1) throw new Sfnt.FontException("This font's " + name + " table has a version this app cannot read (" + major + "." + minor + ").");
        Table m = new Table(gsub);
        m.sources.add(table);
        int[] budget = { table.length / 2 + 200000 };       // model entries; shared tables cannot multiply the model past this
        int lookupList = Sfnt.u16(table, 8);
        if (lookupList != 0) readLookups(m, table, lookupList, budget);
        int featureList = Sfnt.u16(table, 6);
        if (featureList != 0) readFeatures(m, table, featureList, budget);
        int scriptList = Sfnt.u16(table, 4);
        if (scriptList != 0) readScripts(m, table, scriptList, budget);
        if (minor >= 1 && table.length >= 14) {
            long fv = Sfnt.u32(table, 10);
            if (fv != 0) {
                if (fv + 8 > table.length) throw damaged(name);
                m.featureVariations = (int) fv;
            }
        }
        return m;
    }

    private static Sfnt.FontException damaged(String name) {
        return new Sfnt.FontException("This font's " + name + " table is damaged.");
    }

    private static void spend(int[] budget, int n, String name) throws Sfnt.FontException {
        budget[0] -= n;
        if (budget[0] < 0) throw new Sfnt.FontException("This font's " + name + " table refers to the same data too many times.");
    }

    private static void readLookups(Table m, byte[] t, int base, int[] budget) throws Sfnt.FontException {
        String name = m.name();
        int count = Sfnt.u16(t, base);
        Sfnt.need(t, base + 2, 2L * count);
        int ext = m.gsub ? GSUB_EXTENSION : GPOS_EXTENSION;
        for (int i = 0; i < count; i++) {
            int lo = base + Sfnt.u16(t, base + 2 + 2 * i);
            Lookup l = new Lookup();
            l.type = Sfnt.u16(t, lo);
            l.flag = Sfnt.u16(t, lo + 2);
            int n = Sfnt.u16(t, lo + 4);
            Sfnt.need(t, lo + 6, 2L * n);
            spend(budget, n + 1, name);
            if ((l.flag & USE_MARK_FILTERING_SET) != 0) l.markFilteringSet = Sfnt.u16(t, lo + 6 + 2 * n);
            int real = -1;
            for (int k = 0; k < n; k++) {
                int rel = Sfnt.u16(t, lo + 6 + 2 * k);
                if (rel == 0) continue;                                     // a null subtable does nothing
                long so = (long) lo + rel;
                if (l.type == ext) {
                    Sfnt.need(t, so, 8);
                    if (Sfnt.u16(t, (int) so) != 1) continue;               // unknown Extension format: HarfBuzz skips it
                    int wrapped = Sfnt.u16(t, (int) so + 2);
                    if (wrapped == ext) throw damaged(name);
                    if (real >= 0 && wrapped != real) throw new Sfnt.FontException("This font's " + name + " table is damaged (a lookup mixes subtable types).");
                    real = wrapped;
                    so += Sfnt.u32(t, (int) so + 4);
                }
                if (so + 2 > t.length) throw damaged(name);
                l.subtables.add(new Subtable(0, (int) so));
            }
            if (l.type == ext && real >= 0) l.type = real;
            m.lookups.add(l);
        }
    }

    private static void readFeatures(Table m, byte[] t, int base, int[] budget) throws Sfnt.FontException {
        String name = m.name();
        int count = Sfnt.u16(t, base);
        Sfnt.need(t, base + 2, 6L * count);
        for (int i = 0; i < count; i++) {
            int rec = base + 2 + 6 * i;
            Feature f = new Feature(Sfnt.tagString(t, rec));
            int fo = base + Sfnt.u16(t, rec + 4);
            int params = Sfnt.u16(t, fo);
            int n = Sfnt.u16(t, fo + 2);
            Sfnt.need(t, fo + 4, 2L * n);
            spend(budget, n + 1, name);
            for (int k = 0; k < n; k++) {
                int li = Sfnt.u16(t, fo + 4 + 2 * k);
                if (li < m.lookups.size()) f.lookups.add(li);
            }
            if (params != 0) {
                f.params = readParams(t, f.tag, fo + params);
                // Old Adobe tools counted the 'size' params from the FeatureList: try there when the right place holds nothing valid (as HarfBuzz does)
                if (f.params == null && f.tag.equals("size")) f.params = readParams(t, f.tag, base + params);
            }
            m.features.add(f);
        }
    }

    /** The FeatureParams bytes of a feature at {@code off}, or null when the tag has none defined or they are not valid. */
    private static byte[] readParams(byte[] t, String tag, int off) throws Sfnt.FontException {
        int len;
        if (tag.equals("size")) {
            if (off + 10 > t.length) return null;
            int design = Sfnt.u16(t, off), sub = Sfnt.u16(t, off + 2), subName = Sfnt.u16(t, off + 4), start = Sfnt.u16(t, off + 6), end = Sfnt.u16(t, off + 8);
            if (design == 0) return null;
            if (!(sub == 0 && subName == 0 && start == 0 && end == 0) && (design < start || design > end || subName < 256 || subName > 32767)) return null;
            len = 10;
        } else if (tag.matches("ss(0[1-9]|1[0-9]|20)")) {
            len = 4;
        } else if (tag.matches("cv(0[1-9]|[1-9][0-9])")) {
            if (off + 14 > t.length) return null;
            len = 14 + 3 * Sfnt.u16(t, off + 12);
        } else {
            return null;
        }
        if (off + (long) len > t.length) return null;
        return Arrays.copyOfRange(t, off, off + len);
    }

    private static void readScripts(Table m, byte[] t, int base, int[] budget) throws Sfnt.FontException {
        int count = Sfnt.u16(t, base);
        Sfnt.need(t, base + 2, 6L * count);
        for (int i = 0; i < count; i++) {
            int rec = base + 2 + 6 * i;
            Script s = new Script(Sfnt.tagString(t, rec));
            int so = base + Sfnt.u16(t, rec + 4);
            int dflt = Sfnt.u16(t, so);
            if (dflt != 0) s.dflt = readLangSys(m, t, so + dflt, budget);
            int n = Sfnt.u16(t, so + 2);
            Sfnt.need(t, so + 4, 6L * n);
            for (int k = 0; k < n; k++) {
                int lr = so + 4 + 6 * k;
                String tag = Sfnt.tagString(t, lr);
                LangSys l = readLangSys(m, t, so + Sfnt.u16(t, lr + 4), budget);
                if (!s.langs.containsKey(tag)) s.langs.put(tag, l);
            }
            m.scripts.add(s);
        }
    }

    private static LangSys readLangSys(Table m, byte[] t, int off, int[] budget) throws Sfnt.FontException {
        LangSys l = new LangSys();
        int req = Sfnt.u16(t, off + 2), n = Sfnt.u16(t, off + 4);
        Sfnt.need(t, off + 6, 2L * n);
        spend(budget, n + 1, m.name());
        l.required = req < m.features.size() ? req : 0xFFFF;
        for (int k = 0; k < n; k++) {
            int fi = Sfnt.u16(t, off + 6 + 2 * k);
            if (fi < m.features.size()) l.features.add(fi);
        }
        return l;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /**
     * The table as bytes, version 1.0: header, ScriptList, FeatureList, LookupList, then each source table that a subtable lives in, byte for
     * byte. Every lookup with subtables is written as an Extension lookup whose 32-bit offsets point into those copies. Scripts, language
     * systems and features are written sorted by tag (feature indexes renumbered to match). FeatureVariations are not written: resolve them
     * first with {@link #applyFeatureVariations}.
     */
    public static byte[] write(Table m, FontProgress p) throws Sfnt.FontException {
        String name = m.name();
        int nf = m.features.size(), nl = m.lookups.size();
        if (nl > 0xFFFF || nf > 0xFFFF || m.scripts.size() > 0xFFFF) throw tooBig(name);
        // Features sorted by tag (stable), and where each one went
        Integer[] order = new Integer[nf];
        for (int i = 0; i < nf; i++) order[i] = i;
        final List<Feature> fs = m.features;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return fs.get(a).tag.compareTo(fs.get(b).tag); }
        });
        int[] newIndex = new int[nf];
        for (int i = 0; i < nf; i++) newIndex[order[i]] = i;

        byte[] scriptList = writeScripts(m, newIndex, name);
        byte[] featureList = writeFeatures(m, order, name);
        int lookupListPos = 10 + scriptList.length + featureList.length;
        if (10 + scriptList.length > 0xFFFF || lookupListPos > 0xFFFF) throw tooBig(name);

        // LookupList size, so the embedded sources' positions are known before writing the 32-bit offsets
        boolean[] used = new boolean[m.sources.size()];
        long llSize = 2 + 2L * nl;
        for (Lookup l : m.lookups) {
            int n = l.subtables.size();
            if (n > 0xFFFF || 8L + 10L * n > 0xFFFF) throw tooBig(name);       // the last Extension subtable is 16 bits from its lookup
            llSize += 6 + 2L * n + ((l.flag & USE_MARK_FILTERING_SET) != 0 ? 2 : 0) + 8L * n;
            for (Subtable s : l.subtables) {
                if (s.source < 0 || s.source >= used.length || s.offset <= 0 || s.offset >= m.sources.get(s.source).length)
                    throw new Sfnt.FontException("The " + name + " table could not be written (a subtable points outside its source).");
                used[s.source] = true;
            }
        }
        long pos = (lookupListPos + llSize + 3) & ~3L;
        long[] srcPos = new long[used.length];
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) continue;
            srcPos[i] = pos;
            pos = (pos + m.sources.get(i).length + 3) & ~3L;
        }
        if (pos > Integer.MAX_VALUE - 8) throw tooBig(name);

        Sfnt.Writer w = new Sfnt.Writer((int) pos);
        w.u16(1).u16(0).u16(10).u16(10 + scriptList.length).u16(lookupListPos);
        w.bytes(scriptList).bytes(featureList);
        w.u16(nl);
        int at = 2 + 2 * nl;                                                // next lookup, from the LookupList start
        for (Lookup l : m.lookups) {
            if (at > 0xFFFF) throw tooBig(name);
            w.u16(at);
            at += 6 + 2 * l.subtables.size() + ((l.flag & USE_MARK_FILTERING_SET) != 0 ? 2 : 0) + 8 * l.subtables.size();
        }
        int ext = m.gsub ? GSUB_EXTENSION : GPOS_EXTENSION;
        int done = 0;
        for (Lookup l : m.lookups) {
            int n = l.subtables.size();
            int lookupPos = w.size();
            int head = 6 + 2 * n + ((l.flag & USE_MARK_FILTERING_SET) != 0 ? 2 : 0);
            w.u16(n == 0 ? l.type : ext).u16(l.flag).u16(n);
            for (int k = 0; k < n; k++) w.u16(head + 8 * k);
            if ((l.flag & USE_MARK_FILTERING_SET) != 0) w.u16(l.markFilteringSet);
            for (int k = 0; k < n; k++) {
                Subtable s = l.subtables.get(k);
                long target = srcPos[s.source] + s.offset - (lookupPos + head + 8L * k);
                w.u16(1).u16(l.type).u32(target);
            }
            if ((++done & 255) == 0) { FontProgress.Check.cancel(p); if (p != null) p.step(STAGE, done, nl); }
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) continue;
            w.zeros((int) (srcPos[i] - w.size()));
            w.bytes(m.sources.get(i));
        }
        w.align(4);
        return w.toByteArray();
    }

    private static Sfnt.FontException tooBig(String name) {
        return new Sfnt.FontException("too_big", "The " + name + " table would be too big to write.");
    }

    private static byte[] writeScripts(Table m, int[] newIndex, String name) throws Sfnt.FontException {
        List<Script> scripts = new ArrayList<Script>(m.scripts);
        Collections.sort(scripts, new Comparator<Script>() {
            @Override public int compare(Script a, Script b) { return a.tag.compareTo(b.tag); }
        });
        List<byte[]> bodies = new ArrayList<byte[]>();
        for (Script s : scripts) bodies.add(writeScript(s, newIndex, name));
        Sfnt.Writer w = new Sfnt.Writer();
        w.u16(scripts.size());
        int at = 2 + 6 * scripts.size();
        for (int i = 0; i < scripts.size(); i++) {
            if (at > 0xFFFF) throw tooBig(name);
            tag(w, scripts.get(i).tag);
            w.u16(at);
            at += bodies.get(i).length;
        }
        for (byte[] b : bodies) w.bytes(b);
        return w.toByteArray();
    }

    private static byte[] writeScript(Script s, int[] newIndex, String name) throws Sfnt.FontException {
        int n = s.langs.size();
        Sfnt.Writer body = new Sfnt.Writer();
        HashMap<String, Integer> shared = new HashMap<String, Integer>();     // identical language systems are written once
        int head = 4 + 6 * n;
        int dfltOff = s.dflt == null ? 0 : head + langSys(body, s.dflt, newIndex, shared, name);
        int[] offs = new int[n];
        int i = 0;
        for (LangSys l : s.langs.values()) offs[i++] = head + langSys(body, l, newIndex, shared, name);
        if (head + body.size() > 0xFFFF) throw tooBig(name);
        Sfnt.Writer w = new Sfnt.Writer(head + body.size());
        w.u16(dfltOff).u16(n);
        i = 0;
        for (String tag : s.langs.keySet()) { tag(w, tag); w.u16(offs[i++]); }
        w.bytes(body.toByteArray());
        return w.toByteArray();
    }

    /** Appends a LangSys to {@code body} (or finds the same one written before) and returns its position in body. */
    private static int langSys(Sfnt.Writer body, LangSys l, int[] newIndex, HashMap<String, Integer> shared, String name) throws Sfnt.FontException {
        int[] v = new int[l.features.size() + 1];
        v[0] = l.required == 0xFFFF ? 0xFFFF : remap(newIndex, l.required, name);
        for (int k = 0; k < l.features.size(); k++) v[k + 1] = remap(newIndex, l.features.get(k), name);
        if (v.length > 0x10000) throw tooBig(name);
        String key = Arrays.toString(v);
        Integer seen = shared.get(key);
        if (seen != null) return seen;
        int at = body.size();
        body.u16(0).u16(v[0]).u16(v.length - 1);
        for (int k = 1; k < v.length; k++) body.u16(v[k]);
        shared.put(key, at);
        return at;
    }

    private static int remap(int[] newIndex, int fi, String name) throws Sfnt.FontException {
        if (fi < 0 || fi >= newIndex.length) throw new Sfnt.FontException("The " + name + " table could not be written (a language system names a feature that does not exist).");
        return newIndex[fi];
    }

    private static byte[] writeFeatures(Table m, Integer[] order, String name) throws Sfnt.FontException {
        int nf = order.length;
        Sfnt.Writer w = new Sfnt.Writer();
        w.u16(nf);
        int at = 2 + 6 * nf;
        Sfnt.Writer body = new Sfnt.Writer();
        for (int i = 0; i < nf; i++) {
            Feature f = m.features.get(order[i]);
            if (at + body.size() > 0xFFFF) throw tooBig(name);
            tag(w, f.tag);
            w.u16(at + body.size());
            int n = f.lookups.size();
            if (n > 0xFFFF) throw tooBig(name);
            body.u16(f.params == null ? 0 : 4 + 2 * n).u16(n);
            for (int li : f.lookups) {
                if (li < 0 || li >= m.lookups.size()) throw new Sfnt.FontException("The " + name + " table could not be written (a feature names a lookup that does not exist).");
                body.u16(li);
            }
            if (f.params != null) body.bytes(f.params);
        }
        w.bytes(body.toByteArray());
        return w.toByteArray();
    }

    private static void tag(Sfnt.Writer w, String tag) {
        for (int i = 0; i < 4; i++) w.u8(i < tag.length() ? tag.charAt(i) : ' ');
    }

    // ---------------------------------------------------------------------------------------------
    // Merging (EmojiMerge)
    // ---------------------------------------------------------------------------------------------

    /**
     * A new model: {@code text}'s scripts, features and lookups, then {@code added}'s lookups appended after them. Every lookup that one of
     * {@code added}'s features turns on is added to the {@code featureTag} feature (normally "ccmp") of every language system of every script of
     * the result: an existing feature of that tag gets them after its own lookups, a language system without one gets a new shared feature.
     * A missing DFLT script is created (a copy of 'dflt' or 'latn' when the text table has one, so text that HarfBuzz used to shape with those
     * still gets the same features), and so is a missing default language system. {@code text} may be null (the text font has no table): the
     * result then has DFLT and latn. {@code added} is parsed from a copy of the emoji table that already has its glyph ids moved past the text
     * font's ({@link #shiftGlyphs} by the text glyph count) and its contextual lookup indexes moved past the text lookups
     * ({@link #shiftLookupIndexes} by {@code text.lookups.size()}, 0 without a text table); this moves the indexes in the model's feature lists.
     * Neither input is changed; the result shares their source arrays. FeatureVariations of either table are dropped.
     */
    public static Table merge(Table text, Table added, String featureTag, FontProgress p) throws Sfnt.FontException {
        boolean gsub = added != null ? added.gsub : text != null && text.gsub;
        if (text != null && added != null && text.gsub != added.gsub) throw new IllegalArgumentException("merge: a GSUB and a GPOS table");
        if (featureTag == null || featureTag.length() != 4) throw new IllegalArgumentException("merge: a feature tag has four characters");
        Table out = new Table(gsub);
        if (text != null) {
            out.sources.addAll(text.sources);
            for (Script s : text.scripts) out.scripts.add(s.copy(s.tag));
            for (Feature f : text.features) {
                Feature c = new Feature(f.tag);
                c.lookups.addAll(f.lookups);
                c.params = f.params;
                out.features.add(c);
            }
            for (Lookup l : text.lookups) out.lookups.add(copy(l, 0));
        }
        if (text == null || text.scripts.isEmpty()) {
            out.scripts.add(new Script("DFLT"));
            out.scripts.add(new Script("latn"));
        }
        if (added == null) return out;
        int base = out.lookups.size(), srcBase = out.sources.size();
        if (base + added.lookups.size() > 0xFFFF) throw new Sfnt.FontException("The merged font would have too many " + out.name() + " lookups.");
        FontProgress.Check.cancel(p);
        out.sources.addAll(added.sources);
        for (Lookup l : added.lookups) out.lookups.add(copy(l, srcBase));
        // The lookups the added features turn on, in lookup order
        TreeSet<Integer> on = new TreeSet<Integer>();
        for (Feature f : added.features) for (int li : f.lookups) on.add(base + li);
        if (on.isEmpty()) return out;
        if (out.script("DFLT") == null) {
            Script from = out.script("dflt") != null ? out.script("dflt") : out.script("latn");
            out.scripts.add(from != null ? from.copy("DFLT") : new Script("DFLT"));
        }
        LinkedHashSet<Integer> extended = new LinkedHashSet<Integer>();
        int created = -1;
        for (Script s : out.scripts) {
            if (s.dflt == null) s.dflt = new LangSys();
            List<LangSys> all = new ArrayList<LangSys>();
            all.add(s.dflt);
            all.addAll(s.langs.values());
            for (LangSys l : all) {
                boolean has = false;
                for (int fi : l.features) {
                    if (!out.features.get(fi).tag.equals(featureTag)) continue;
                    has = true;
                    if (extended.add(fi)) {
                        List<Integer> ls = out.features.get(fi).lookups;
                        HashSet<Integer> had = new HashSet<Integer>(ls);
                        for (int li : on) if (had.add(li)) ls.add(li);
                    }
                }
                if (!has) {
                    if (created < 0) {
                        Feature f = new Feature(featureTag);
                        f.lookups.addAll(on);
                        out.features.add(f);
                        created = out.features.size() - 1;
                        extended.add(created);
                    }
                    l.features.add(created);
                }
            }
        }
        return out;
    }

    private static Lookup copy(Lookup l, int srcBase) {
        Lookup c = new Lookup();
        c.type = l.type; c.flag = l.flag; c.markFilteringSet = l.markFilteringSet;
        for (Subtable s : l.subtables) c.subtables.add(new Subtable(s.source + srcBase, s.offset));
        return c;
    }

    // ---------------------------------------------------------------------------------------------
    // Patchers (in place)
    // ---------------------------------------------------------------------------------------------

    /**
     * Adds {@code delta} to every glyph id in every lookup subtable of the GSUB / GPOS table {@code t}, in place: coverages, class
     * definitions, substitutes, sequences, alternates, ligatures and their components, pair second glyphs, the glyphs of contextual and
     * chained rules (single substitution format 1 keeps its delta, which is relative). Fails when a glyph id would pass 65535.
     */
    public static void shiftGlyphs(byte[] t, boolean gsub, int delta, FontProgress p) throws Sfnt.FontException {
        new Patch(t, gsub, Patch.GLYPHS, delta, null, p).lookups();
    }

    /**
     * {@link #shiftGlyphs(byte[], boolean, int, FontProgress)}, except that glyph {@code from} becomes glyph {@code to} (as it is, not shifted)
     * where it is part of a sequence: a ligature component past the first, a glyph of a contextual or chained rule's sequences (format 1)
     * past the first input glyph. Ask {@link #onlyInSequences} first: anywhere else, {@code from} is shifted like the other glyphs.
     */
    public static void shiftGlyphs(byte[] t, boolean gsub, int delta, int from, int to, FontProgress p) throws Sfnt.FontException {
        if (to < 0 || to > 0xFFFF) throw new IllegalArgumentException("shiftGlyphs: glyph " + to);
        Patch q = new Patch(t, gsub, Patch.GLYPHS, delta, null, p);
        q.from = from;
        q.to = to;
        q.lookups();
    }

    /**
     * True when glyph {@code g} appears in the lookup subtables of {@code t} only as part of sequences (see
     * {@link #shiftGlyphs(byte[], boolean, int, int, int, FontProgress)}): never in a coverage or a class definition, never as a substitute
     * (single, multiple, alternate, reverse chaining) or a ligature's glyph, so no lookup applies at it or puts it in the buffer. Then the
     * sequences can name another glyph instead and every lookup still applies where it did. Reads the table only.
     */
    public static boolean onlyInSequences(byte[] t, boolean gsub, int g, FontProgress p) throws Sfnt.FontException {
        Patch q = new Patch(t, gsub, Patch.GLYPHS, 0, null, p);
        q.find = true;
        q.from = g;
        q.lookups();
        return !q.found;
    }

    /** Adds {@code delta} to every lookup index that a contextual or chained contextual subtable of {@code t} calls (every format), in place. */
    public static void shiftLookupIndexes(byte[] t, boolean gsub, int delta, FontProgress p) throws Sfnt.FontException {
        new Patch(t, gsub, Patch.LOOKUPS, delta, null, p).lookups();
    }

    /**
     * Bakes the variation deltas of the GPOS table {@code gpos} into its values, in place, at the location {@code store} was set to with
     * {@link VarStore#at} (GDEF's ItemVariationStore, see {@link #gdefVarStore}): every ValueRecord (single and pair adjustment) and every
     * Anchor format 3 whose Device offset points to a VariationIndex table gets round(delta) added (clamped to 16 bits) and that Device offset
     * cleared. Hinting Device tables are left. Returns how many non-zero deltas could not be applied because the ValueRecord has a Device
     * but no value field to add them to (fontTools would add the field; a fixed-size patch cannot), normally 0. Drop GDEF's store afterwards
     * with {@link #instanceGdef}.
     */
    public static int applyGposDeltas(byte[] gpos, VarStore store, FontProgress p) throws Sfnt.FontException {
        if (store == null) throw new IllegalArgumentException("applyGposDeltas: no variation store");
        Patch d = new Patch(gpos, false, Patch.DELTAS, 0, store, p);
        d.lookups();
        return d.skipped;
    }

    /**
     * Applies the FeatureVariations of the model's source at normalized coordinates {@code coords} (F2Dot14, fvar axis order, missing axes are
     * 0): the first record whose conditions all hold (format 1: filterRangeMin &lt;= coord &lt;= filterRangeMax; a record with no conditions
     * always holds; any other condition format never does) swaps its alternate lookup lists into the features it names, as fontTools'
     * instancer does for a static instance. Call it on a freshly parsed model (feature indexes are the source's). The FeatureVariations are
     * then gone from the model either way. Returns true when a record matched.
     */
    public static boolean applyFeatureVariations(Table m, int[] coords) throws Sfnt.FontException {
        if (m.featureVariations < 0) return false;
        byte[] t = m.sources.get(0);
        int fv = m.featureVariations;
        m.featureVariations = -1;
        String name = m.name();
        if (Sfnt.u16(t, fv) != 1) return false;                         // a version HarfBuzz would not read either
        long count = Sfnt.u32(t, fv + 4);
        Sfnt.need(t, fv + 8, 8 * count);
        long[] budget = { t.length + 65536L };                         // conditions and lookups read; records sharing data cannot make this quadratic
        for (int r = 0; r < count; r++) {
            int rec = fv + 8 + 8 * r;
            long cs = Sfnt.u32(t, rec), fts = Sfnt.u32(t, rec + 4);
            if (!conditionsHold(t, cs == 0 ? -1 : fv + cs, coords, name, budget)) continue;
            if (fts == 0) return true;                                   // a null substitution still matches and blocks later records
            long so = fv + fts;
            if (so + 6 > t.length) throw damaged(name);
            int s = (int) so;
            if (Sfnt.u16(t, s) != 1) return true;
            int n = Sfnt.u16(t, s + 4);
            Sfnt.need(t, s + 6, 6L * n);
            for (int k = 0; k < n; k++) {
                int fi = Sfnt.u16(t, s + 6 + 6 * k);
                long alt = so + Sfnt.u32(t, s + 6 + 6 * k + 2);
                if (fi >= m.features.size()) continue;
                if (alt + 4 > t.length) throw damaged(name);
                int a = (int) alt;
                int lc = Sfnt.u16(t, a + 2);
                Sfnt.need(t, a + 4, 2L * lc);
                fvSpend(budget, lc + 1, name);
                List<Integer> ls = m.features.get(fi).lookups;
                ls.clear();
                for (int i = 0; i < lc; i++) {
                    int li = Sfnt.u16(t, a + 4 + 2 * i);
                    if (li < m.lookups.size()) ls.add(li);
                }
            }
            return true;
        }
        return false;
    }

    private static void fvSpend(long[] budget, long n, String name) throws Sfnt.FontException {
        budget[0] -= n;
        if (budget[0] < 0) throw new Sfnt.FontException("This font's " + name + " table refers to the same data too many times.");
    }

    private static boolean conditionsHold(byte[] t, long cs, int[] coords, String name, long[] budget) throws Sfnt.FontException {
        if (cs < 0) return true;
        if (cs + 2 > t.length) throw damaged(name);
        int c = (int) cs;
        int n = Sfnt.u16(t, c);
        Sfnt.need(t, c + 2, 4L * n);
        fvSpend(budget, 1, name);
        for (int i = 0; i < n; i++) {
            fvSpend(budget, 1, name);
            long co = cs + Sfnt.u32(t, c + 2 + 4 * i);
            if (co + 2 > t.length) throw damaged(name);
            int o = (int) co;
            if (Sfnt.u16(t, o) != 1) return false;
            int axis = Sfnt.u16(t, o + 2), min = Sfnt.s16(t, o + 4), max = Sfnt.s16(t, o + 6);
            int v = coords != null && axis < coords.length ? coords[axis] : 0;
            if (v < min || v > max) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // GDEF
    // ---------------------------------------------------------------------------------------------

    /** GDEF's ItemVariationStore (GDEF 1.3), or null when it has none. Set the location with {@link VarStore#at} before using it. */
    public static VarStore gdefVarStore(byte[] gdef) throws Sfnt.FontException {
        if (gdef == null || gdef.length < 18 || Sfnt.u16(gdef, 0) != 1 || Sfnt.u16(gdef, 2) < 3) return null;
        long off = Sfnt.u32(gdef, 14);
        if (off == 0) return null;
        if (off + 8 > gdef.length) throw new Sfnt.FontException("This font's GDEF table is damaged.");
        return new VarStore(gdef, (int) off);
    }

    /**
     * A copy of GDEF for a static instance: when {@code store} is given (at the instance's location), the ligature caret positions of
     * CaretValue format 3 with a VariationIndex get their deltas added and become format 1, as fontTools writes them (a format 3 with a
     * hinting Device stays); then the ItemVariationStore offset is cleared and the version set to 1.2, so no variation store is left. The
     * store's bytes stay in the table, unreferenced.
     */
    public static byte[] instanceGdef(byte[] gdef, VarStore store) throws Sfnt.FontException {
        byte[] g = gdef.clone();
        if (g.length < 18 || Sfnt.u16(g, 0) != 1 || Sfnt.u16(g, 2) < 3) return g;
        if (store != null) {
            int lcl = Sfnt.u16(g, 8);
            if (lcl != 0) {
                HashMap<Integer, Boolean> seen = new HashMap<Integer, Boolean>();
                long budget = 8L * g.length + 65536;
                int n = Sfnt.u16(g, lcl + 2);
                Sfnt.need(g, lcl + 4, 2L * n);
                for (int i = 0; i < n; i++) {
                    int lg = lcl + Sfnt.u16(g, lcl + 4 + 2 * i);
                    if (seen.put(-1 - lg, Boolean.TRUE) != null) continue;           // a LigGlyph shared by several ligatures
                    int cc = Sfnt.u16(g, lg);
                    Sfnt.need(g, lg + 2, 2L * cc);
                    budget -= cc + 1;
                    if (budget < 0) throw new Sfnt.FontException("This font's GDEF table refers to the same data too many times.");
                    for (int k = 0; k < cc; k++) {
                        int rel = Sfnt.u16(g, lg + 2 + 2 * k);
                        if (rel == 0) continue;
                        int cv = lg + rel;
                        if (seen.put(cv, Boolean.TRUE) != null || Sfnt.u16(g, cv) != 3) continue;
                        int dev = Sfnt.u16(g, cv + 4);
                        if (dev == 0) continue;
                        Sfnt.need(g, cv + dev, 6);
                        if (Sfnt.u16(g, cv + dev + 4) != 0x8000) continue;
                        double d = store.delta(Sfnt.u16(g, cv + dev), Sfnt.u16(g, cv + dev + 2));
                        Sfnt.w16(g, cv + 2, clamp16(Sfnt.s16(g, cv + 2) + Glyf.round(d)));
                        // Format 1 (the coordinate alone): format 3 must have a Device, OTS refuses one without. The 2 bytes left over
                        // are not read, so no offset moves.
                        Sfnt.w16(g, cv, 1);
                        Sfnt.w16(g, cv + 4, 0);
                    }
                }
            }
        }
        Sfnt.w32(g, 14, 0);
        Sfnt.w16(g, 2, 2);
        return g;
    }

    static int clamp16(long v) { return (int) Math.max(-32768, Math.min(32767, v)); }

    // ---------------------------------------------------------------------------------------------
    // The subtable walker behind the patchers
    // ---------------------------------------------------------------------------------------------

    /**
     * Walks every lookup subtable of one table and changes glyph ids, lookup indexes or GPOS values in place. Each structure it changes is
     * recorded by offset with the kind it was read as: a second visit of the same kind is skipped (shared data is changed once), a second
     * visit as another kind would change different fields of the same bytes, so the table is refused instead.
     */
    private static final class Patch {
        static final int GLYPHS = 0, LOOKUPS = 1, DELTAS = 2;
        // Kinds of shared structures. Coverage format 2 and ClassDef format 2 have the same fields, so do Sequence and AlternateSet.
        static final long K_COV1 = 1, K_CLS1 = 2, K_RANGES = 3, K_GLYPHS = 4, K_LIGSET = 5, K_LIG = 6, K_RULESET_G = 7, K_RULESET_C = 8,
                K_RULE_G = 9, K_RULE_C = 10, K_CRULESET_G = 11, K_CRULESET_C = 12, K_CRULE_G = 13, K_CRULE_C = 14, K_ANCHOR = 15,
                K_PAIRSET = 1L << 40, K_SUBTABLE = 1L << 50;

        final byte[] t;
        final boolean gsub;
        final int mode, delta;
        final VarStore store;
        final FontProgress p;
        final String name;
        final HashMap<Integer, Long> seen = new HashMap<Integer, Long>();
        final HashMap<Integer, Double> deltas = new HashMap<Integer, Double>();
        final long budget;
        long ops, nextCancelCheck;
        int skipped;
        // onlyInSequences and the renaming shiftGlyphs: the glyph asked about, its new id, whether the glyphs read now are a sequence's
        int from = -1, to;
        boolean find, found, sequence;

        Patch(byte[] t, boolean gsub, int mode, int delta, VarStore store, FontProgress p) throws Sfnt.FontException {
            this.t = t; this.gsub = gsub; this.mode = mode; this.delta = delta; this.store = store; this.p = p;
            this.name = gsub ? "GSUB" : "GPOS";
            if (t == null || t.length < 10) throw new Sfnt.FontException("This font's " + name + " table is too short.");
            budget = 8L * t.length + 65536;                     // fields touched; overlapping hostile data cannot make this quadratic
        }

        /** Every subtable of every lookup of the table. */
        void lookups() throws Sfnt.FontException {
            if (Sfnt.u16(t, 0) != 1) throw new Sfnt.FontException("This font's " + name + " table has a version this app cannot read.");
            int ll = Sfnt.u16(t, 8);
            if (ll == 0) return;
            int count = Sfnt.u16(t, ll);
            Sfnt.need(t, ll + 2, 2L * count);
            int ext = gsub ? GSUB_EXTENSION : GPOS_EXTENSION;
            for (int i = 0; i < count; i++) {
                int lo = ll + Sfnt.u16(t, ll + 2 + 2 * i);
                int type = Sfnt.u16(t, lo), n = Sfnt.u16(t, lo + 4);
                Sfnt.need(t, lo + 6, 2L * n);
                spend(n);                                        // lookups that all name one big lookup are counted too
                for (int k = 0; k < n; k++) {
                    int rel = Sfnt.u16(t, lo + 6 + 2 * k);
                    if (rel == 0) continue;
                    int so = lo + rel, real = type;
                    if (type == ext) {
                        Sfnt.need(t, so, 8);
                        if (Sfnt.u16(t, so) != 1) continue;
                        real = Sfnt.u16(t, so + 2);
                        long abs = so + Sfnt.u32(t, so + 4);
                        if (real == ext || abs + 2 > t.length) throw damaged(name);
                        so = (int) abs;
                    }
                    subtable(so, real);
                }
                if ((i & 63) == 63) { FontProgress.Check.cancel(p); if (p != null) p.step(STAGE, i + 1, count); }
            }
        }

        // ---- bookkeeping ----

        boolean first(int off, long kind) throws Sfnt.FontException {
            Long k = seen.get(off);
            if (k == null) { seen.put(off, kind); return true; }
            if (k != kind) throw new Sfnt.FontException("This font's " + name + " table shares data between lookups in a way this app cannot change.");
            return false;
        }

        void spend(long n) throws Sfnt.FontException {
            ops += n + 1;
            if (ops > budget) throw new Sfnt.FontException("This font's " + name + " table refers to the same data too many times.");
            if (ops >= nextCancelCheck) {
                FontProgress.Check.cancel(p);
                nextCancelCheck = ops + 1024;
            }
        }

        /** The absolute offset {@code base} + the 16-bit offset at {@code at}, or -1 for a null offset. */
        int off16(int base, int at) throws Sfnt.FontException {
            int rel = Sfnt.u16(t, at);
            if (rel == 0) return -1;
            if (base + rel + 2 > t.length) throw damaged(name);
            return base + rel;
        }

        void glyph(int at) throws Sfnt.FontException {
            int old = Sfnt.u16(t, at);
            if (find) { found |= old == from && !sequence; return; }
            if (old == from && sequence) { Sfnt.w16(t, at, to); return; }
            int v = old + delta;
            if (v > 0xFFFF || v < 0) throw new Sfnt.FontException("too_many_glyphs", "The merged font would have glyph numbers past 65535.");
            Sfnt.w16(t, at, v);
        }

        /** The glyphs of a sequence (see {@link #onlyInSequences}). */
        void sequence(int at, int n) throws Sfnt.FontException {
            sequence = true;
            try {
                glyphs(at, n);
            } finally {
                sequence = false;
            }
        }

        void glyphs(int at, int n) throws Sfnt.FontException {
            Sfnt.need(t, at, 2L * n);
            spend(n);
            for (int i = 0; i < n; i++) glyph(at + 2 * i);
        }

        /** Lookup records (sequenceIndex, lookupListIndex) at {@code at}. */
        void records(int at, int n) throws Sfnt.FontException {
            Sfnt.need(t, at, 4L * n);
            spend(n);
            if (mode != LOOKUPS) return;
            for (int i = 0; i < n; i++) {
                int v = Sfnt.u16(t, at + 4 * i + 2) + delta;
                if (v > 0xFFFF || v < 0) throw new Sfnt.FontException("The merged font would have too many " + name + " lookups.");
                Sfnt.w16(t, at + 4 * i + 2, v);
            }
        }

        void coverage(int off) throws Sfnt.FontException {
            if (off < 0 || mode != GLYPHS) return;
            int fmt = Sfnt.u16(t, off), n = Sfnt.u16(t, off + 2);
            if (fmt == 1) {
                if (first(off, K_COV1)) glyphs(off + 4, n);
            } else if (fmt == 2) {
                if (first(off, K_RANGES)) ranges(off + 4, n);
            }                                                            // another format covers nothing (HarfBuzz)
        }

        void classDef(int off) throws Sfnt.FontException {
            if (off < 0 || mode != GLYPHS) return;
            int fmt = Sfnt.u16(t, off);
            if (fmt == 1) {
                if (!first(off, K_CLS1)) return;
                int start = Sfnt.u16(t, off + 2), n = Sfnt.u16(t, off + 4);
                if (n > 0 && start + n - 1 + delta > 0xFFFF) throw new Sfnt.FontException("too_many_glyphs", "The merged font would have glyph numbers past 65535.");
                found |= find && from >= start && from < start + n;
                glyph(off + 2);
            } else if (fmt == 2) {
                if (first(off, K_RANGES)) ranges(off + 4, Sfnt.u16(t, off + 2));
            }
        }

        void ranges(int at, int n) throws Sfnt.FontException {
            Sfnt.need(t, at, 6L * n);
            spend(2L * n);
            for (int i = 0; i < n; i++) {
                found |= find && from >= Sfnt.u16(t, at + 6 * i) && from <= Sfnt.u16(t, at + 6 * i + 2);
                glyph(at + 6 * i);
                glyph(at + 6 * i + 2);
            }
        }

        /** True when the coverage at {@code off} lists glyph {@code g} (formats 1 and 2). */
        boolean covers(int off, int g) throws Sfnt.FontException {
            if (off < 0) return false;
            int fmt = Sfnt.u16(t, off), n = Sfnt.u16(t, off + 2);
            Sfnt.need(t, off + 4, (fmt == 1 ? 2L : fmt == 2 ? 6L : 0) * n);
            spend(n);
            for (int i = 0; i < n; i++) {
                if (fmt == 1 && Sfnt.u16(t, off + 4 + 2 * i) == g) return true;
                if (fmt == 2 && g >= Sfnt.u16(t, off + 4 + 6 * i) && g <= Sfnt.u16(t, off + 6 + 6 * i)) return true;
            }
            return false;
        }

        /** Offsets (16-bit, from {@code base}) to coverages at {@code at}. */
        void coverages(int base, int at, int n) throws Sfnt.FontException {
            Sfnt.need(t, at, 2L * n);
            spend(n);
            for (int i = 0; i < n; i++) coverage(off16(base, at + 2 * i));
        }

        // ---- subtables ----

        void subtable(int so, int type) throws Sfnt.FontException {
            int fmt = Sfnt.u16(t, so);
            if (!first(so, K_SUBTABLE + ((long) type << 16 | fmt))) return;
            spend(1);
            if (gsub) gsubSubtable(so, type, fmt);
            else gposSubtable(so, type, fmt);
        }

        void gsubSubtable(int so, int type, int fmt) throws Sfnt.FontException {
            if (mode != GLYPHS) {
                if (type == GSUB_CONTEXT) context(so, fmt);
                else if (type == GSUB_CHAIN) chain(so, fmt);
                return;
            }
            switch (type) {
                case 1:                                                  // single: format 1 keeps its (relative) delta
                    coverage(off16(so, so + 2));
                    if (fmt == 2) glyphs(so + 6, Sfnt.u16(t, so + 4));
                    if (fmt == 1 && find) found |= covers(off16(so, so + 2), (from - Sfnt.s16(t, so + 4)) & 0xFFFF);
                    break;
                case 2: case 3: {                                        // multiple / alternate: [count, glyphs] per covered glyph
                    if (fmt != 1) break;
                    coverage(off16(so, so + 2));
                    int n = Sfnt.u16(t, so + 4);
                    Sfnt.need(t, so + 6, 2L * n);
                    spend(n);
                    for (int i = 0; i < n; i++) {
                        int s = off16(so, so + 6 + 2 * i);
                        if (s >= 0 && first(s, K_GLYPHS)) glyphs(s + 2, Sfnt.u16(t, s));
                    }
                    break;
                }
                case 4: {                                                // ligature
                    if (fmt != 1) break;
                    coverage(off16(so, so + 2));
                    int n = Sfnt.u16(t, so + 4);
                    Sfnt.need(t, so + 6, 2L * n);
                    spend(n);
                    for (int i = 0; i < n; i++) {
                        int ls = off16(so, so + 6 + 2 * i);
                        if (ls < 0 || !first(ls, K_LIGSET)) continue;
                        int lc = Sfnt.u16(t, ls);
                        Sfnt.need(t, ls + 2, 2L * lc);
                        spend(lc);
                        for (int k = 0; k < lc; k++) {
                            int lig = off16(ls, ls + 2 + 2 * k);
                            if (lig < 0 || !first(lig, K_LIG)) continue;
                            glyph(lig);
                            sequence(lig + 4, Math.max(0, Sfnt.u16(t, lig + 2) - 1));
                        }
                    }
                    break;
                }
                case GSUB_CONTEXT: context(so, fmt); break;
                case GSUB_CHAIN: chain(so, fmt); break;
                case 8: {                                                // reverse chaining single
                    if (fmt != 1) break;
                    coverage(off16(so, so + 2));
                    int p = so + 4;
                    int nb = Sfnt.u16(t, p);
                    coverages(so, p + 2, nb);
                    p += 2 + 2 * nb;
                    int na = Sfnt.u16(t, p);
                    coverages(so, p + 2, na);
                    p += 2 + 2 * na;
                    glyphs(p + 2, Sfnt.u16(t, p));
                    break;
                }
                default: break;                                          // unknown types do nothing (HarfBuzz)
            }
        }

        void gposSubtable(int so, int type, int fmt) throws Sfnt.FontException {
            if (mode == DELTAS && (type == GPOS_CONTEXT || type == GPOS_CHAIN)) return;
            if (mode == LOOKUPS) {
                if (type == GPOS_CONTEXT) context(so, fmt);
                else if (type == GPOS_CHAIN) chain(so, fmt);
                return;
            }
            switch (type) {
                case 1: {
                    coverage(off16(so, so + 2));
                    if (mode != DELTAS) break;
                    int vf = Sfnt.u16(t, so + 4);
                    if (fmt == 1) value(so + 6, vf, so);
                    else if (fmt == 2) {
                        int n = Sfnt.u16(t, so + 6), size = valueSize(vf);
                        Sfnt.need(t, so + 8, (long) n * size);
                        spend(n);
                        for (int i = 0; i < n; i++) value(so + 8 + i * size, vf, so);
                    }
                    break;
                }
                case 2: {
                    coverage(off16(so, so + 2));
                    int vf1 = Sfnt.u16(t, so + 4), vf2 = Sfnt.u16(t, so + 6);
                    int s1 = valueSize(vf1), s2 = valueSize(vf2);
                    if (fmt == 1) {
                        int n = Sfnt.u16(t, so + 8);
                        Sfnt.need(t, so + 10, 2L * n);
                        spend(n);
                        int rec = 2 + s1 + s2;
                        for (int i = 0; i < n; i++) {
                            int ps = off16(so, so + 10 + 2 * i);
                            if (ps < 0 || !first(ps, K_PAIRSET + (mode == GLYPHS ? rec : ((long) vf1 << 16 | vf2)))) continue;
                            int pc = Sfnt.u16(t, ps);
                            Sfnt.need(t, ps + 2, (long) pc * rec);
                            spend(pc);
                            for (int k = 0; k < pc; k++) {
                                int r = ps + 2 + k * rec;
                                if (mode == GLYPHS) glyph(r);
                                else { value(r + 2, vf1, ps); value(r + 2 + s1, vf2, ps); }
                            }
                        }
                    } else if (fmt == 2) {
                        classDef(off16(so, so + 8));
                        classDef(off16(so, so + 10));
                        if (mode != DELTAS) break;
                        int c1 = Sfnt.u16(t, so + 12), c2 = Sfnt.u16(t, so + 14);
                        long cells = (long) c1 * c2;
                        Sfnt.need(t, so + 16, cells * (s1 + s2));
                        spend(cells);
                        for (int i = 0; i < cells; i++) {
                            int r = so + 16 + i * (s1 + s2);
                            value(r, vf1, so);
                            value(r + s1, vf2, so);
                        }
                    }
                    break;
                }
                case 3: {                                                // cursive: entry and exit anchors
                    if (fmt != 1) break;
                    coverage(off16(so, so + 2));
                    if (mode != DELTAS) break;
                    int n = Sfnt.u16(t, so + 4);
                    Sfnt.need(t, so + 6, 4L * n);
                    spend(n);
                    for (int i = 0; i < 2 * n; i++) anchor(off16(so, so + 6 + 2 * i));
                    break;
                }
                case 4: case 5: case 6: {                                // mark to base / ligature / mark
                    if (fmt != 1) break;
                    coverage(off16(so, so + 2));
                    coverage(off16(so, so + 4));
                    if (mode != DELTAS) break;
                    int classes = Sfnt.u16(t, so + 6);
                    markArray(off16(so, so + 8));
                    int arr = off16(so, so + 10);
                    if (arr < 0) break;
                    if (type == 5) {
                        int n = Sfnt.u16(t, arr);
                        Sfnt.need(t, arr + 2, 2L * n);
                        spend(n);
                        for (int i = 0; i < n; i++) {
                            int att = off16(arr, arr + 2 + 2 * i);
                            if (att >= 0) anchorRows(att, classes);
                        }
                    } else {
                        anchorRows(arr, classes);
                    }
                    break;
                }
                case GPOS_CONTEXT: context(so, fmt); break;
                case GPOS_CHAIN: chain(so, fmt); break;
                default: break;
            }
        }

        // ---- contextual (GSUB 5 / GPOS 7) and chained (GSUB 6 / GPOS 8) ----

        void context(int so, int fmt) throws Sfnt.FontException {
            if (fmt == 1 || fmt == 2) {
                coverage(off16(so, so + 2));
                int sets;
                if (fmt == 2) { classDef(off16(so, so + 4)); sets = so + 6; } else sets = so + 4;
                int n = Sfnt.u16(t, sets);
                Sfnt.need(t, sets + 2, 2L * n);
                spend(n);
                for (int i = 0; i < n; i++) {
                    int rs = off16(so, sets + 2 + 2 * i);
                    if (rs < 0 || !first(rs, mode == GLYPHS && fmt == 2 ? K_RULESET_C : K_RULESET_G)) continue;
                    int rc = Sfnt.u16(t, rs);
                    Sfnt.need(t, rs + 2, 2L * rc);
                    spend(rc);
                    for (int k = 0; k < rc; k++) {
                        int r = off16(rs, rs + 2 + 2 * k);
                        if (r < 0 || !first(r, mode == GLYPHS && fmt == 2 ? K_RULE_C : K_RULE_G)) continue;
                        int gc = Sfnt.u16(t, r), sc = Sfnt.u16(t, r + 2);
                        int in = Math.max(0, gc - 1);
                        Sfnt.need(t, r + 4, 2L * in);
                        if (mode == GLYPHS && fmt == 1) sequence(r + 4, in);
                        records(r + 4 + 2 * in, sc);
                    }
                }
            } else if (fmt == 3) {
                int gc = Sfnt.u16(t, so + 2), sc = Sfnt.u16(t, so + 4);
                coverages(so, so + 6, gc);
                records(so + 6 + 2 * gc, sc);
            }
        }

        void chain(int so, int fmt) throws Sfnt.FontException {
            if (fmt == 1 || fmt == 2) {
                coverage(off16(so, so + 2));
                int sets;
                if (fmt == 2) {
                    classDef(off16(so, so + 4));
                    classDef(off16(so, so + 6));
                    classDef(off16(so, so + 8));
                    sets = so + 10;
                } else sets = so + 4;
                int n = Sfnt.u16(t, sets);
                Sfnt.need(t, sets + 2, 2L * n);
                spend(n);
                boolean glyphRules = mode == GLYPHS && fmt == 1;
                for (int i = 0; i < n; i++) {
                    int rs = off16(so, sets + 2 + 2 * i);
                    if (rs < 0 || !first(rs, mode == GLYPHS && fmt == 2 ? K_CRULESET_C : K_CRULESET_G)) continue;
                    int rc = Sfnt.u16(t, rs);
                    Sfnt.need(t, rs + 2, 2L * rc);
                    spend(rc);
                    for (int k = 0; k < rc; k++) {
                        int r = off16(rs, rs + 2 + 2 * k);
                        if (r < 0 || !first(r, mode == GLYPHS && fmt == 2 ? K_CRULE_C : K_CRULE_G)) continue;
                        int q = r;
                        int nb = Sfnt.u16(t, q);
                        Sfnt.need(t, q + 2, 2L * nb);
                        if (glyphRules) sequence(q + 2, nb);
                        q += 2 + 2 * nb;
                        int ni = Math.max(0, Sfnt.u16(t, q) - 1);
                        Sfnt.need(t, q + 2, 2L * ni);
                        if (glyphRules) sequence(q + 2, ni);
                        q += 2 + 2 * ni;
                        int na = Sfnt.u16(t, q);
                        Sfnt.need(t, q + 2, 2L * na);
                        if (glyphRules) sequence(q + 2, na);
                        q += 2 + 2 * na;
                        records(q + 2, Sfnt.u16(t, q));
                    }
                }
            } else if (fmt == 3) {
                int q = so + 2;
                int nb = Sfnt.u16(t, q);
                coverages(so, q + 2, nb);
                q += 2 + 2 * nb;
                int ni = Sfnt.u16(t, q);
                coverages(so, q + 2, ni);
                q += 2 + 2 * ni;
                int na = Sfnt.u16(t, q);
                coverages(so, q + 2, na);
                q += 2 + 2 * na;
                records(q + 2, Sfnt.u16(t, q));
            }
        }

        // ---- GPOS values and anchors (DELTAS) ----

        /** Bytes of a ValueRecord: two per bit set (HarfBuzz counts the reserved bits too). */
        static int valueSize(int vf) { return 2 * Integer.bitCount(vf & 0xFFFF); }

        /** Adds the VariationIndex deltas of the ValueRecord at {@code rec} (format {@code vf}, Device offsets from {@code base}). */
        void value(int rec, int vf, int base) throws Sfnt.FontException {
            if ((vf & 0xF0) == 0) return;
            Sfnt.need(t, rec, valueSize(vf));
            for (int b = 0; b < 4; b++) {
                int devBit = 1 << (b + 4);
                if ((vf & devBit) == 0) continue;
                int devAt = rec + 2 * Integer.bitCount(vf & (devBit - 1));
                double d = variation(base, devAt);
                if (Double.isNaN(d)) continue;
                if ((vf & (1 << b)) != 0) {
                    int valAt = rec + 2 * Integer.bitCount(vf & ((1 << b) - 1));
                    Sfnt.w16(t, valAt, clamp16(Sfnt.s16(t, valAt) + Glyf.round(d)));
                } else if (Glyf.round(d) != 0) {
                    skipped++;
                }
                Sfnt.w16(t, devAt, 0);
            }
        }

        /** The delta of the VariationIndex table that the offset at {@code at} (from {@code base}) points to, or NaN for none / a hinting Device. */
        double variation(int base, int at) throws Sfnt.FontException {
            int rel = Sfnt.u16(t, at);
            if (rel == 0) return Double.NaN;
            int dev = base + rel;
            Sfnt.need(t, dev, 6);
            if (Sfnt.u16(t, dev + 4) != 0x8000) return Double.NaN;
            spend(1);
            int key = Sfnt.u16(t, dev) << 16 | Sfnt.u16(t, dev + 2);
            Double d = deltas.get(key);                          // many values share a variation index
            if (d == null) deltas.put(key, d = store.delta(key >>> 16, key & 0xFFFF));
            return d;
        }

        void anchor(int off) throws Sfnt.FontException {
            if (off < 0 || Sfnt.u16(t, off) != 3 || !first(off, K_ANCHOR)) return;
            Sfnt.need(t, off, 10);
            spend(2);
            for (int a = 0; a < 2; a++) {
                double d = variation(off, off + 6 + 2 * a);
                if (Double.isNaN(d)) continue;
                Sfnt.w16(t, off + 2 + 2 * a, clamp16(Sfnt.s16(t, off + 2 + 2 * a) + Glyf.round(d)));
                Sfnt.w16(t, off + 6 + 2 * a, 0);
            }
        }

        /** A MarkArray: (class, anchor offset from the array) per mark. */
        void markArray(int off) throws Sfnt.FontException {
            if (off < 0) return;
            int n = Sfnt.u16(t, off);
            Sfnt.need(t, off + 2, 4L * n);
            spend(n);
            for (int i = 0; i < n; i++) anchor(off16(off, off + 2 + 4 * i + 2));
        }

        /** BaseArray, Mark2Array or LigatureAttach: a count of rows of {@code classes} anchor offsets from the table. */
        void anchorRows(int off, int classes) throws Sfnt.FontException {
            int n = Sfnt.u16(t, off);
            long cells = (long) n * classes;
            Sfnt.need(t, off + 2, 2 * cells);
            spend(cells);
            for (int i = 0; i < cells; i++) anchor(off16(off, off + 2 + 2 * i));
        }
    }
}
