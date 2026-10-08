package com.bloatware.bingblop;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Multi-pattern matcher: Aho-Corasick completed into a DFA, built from every '|'-separated alternative
 * of every tracker's code_signature. One table lookup per input byte, no allocation while scanning.
 *
 * Input is a Dalvik type descriptor body ("com/flurry/android/FlurryAgent$1"); '/' is treated as '.', so the
 * text matched is the dotted class name. Match semantics = unanchored substring, case sensitive.
 */
final class TrackerMatcher {
    final TrackerDb db;
    private final int alphabet;          // distinct pattern chars + 1 ("other", index 0)
    private final byte[] alpha = new byte[128];
    private final char[] next;           // DFA: next[state * alphabet + symbol]
    private final int[][] out;           // out[state] = tracker indexes whose pattern ends here (incl. suffix links)
    final int states;

    /** Per-app (or per-dex) detection result. */
    static final class Result {
        final boolean[] any;             // tracker class descriptor present anywhere (defined OR merely referenced)
        final boolean[] defined;         // tracker class is DEFINED by a class_def in the app's dex files
        final String[] example;          // first class name that matched (for the UI "why")
        Result(int n) { any = new boolean[n]; defined = new boolean[n]; example = new String[n]; }
        int countAny() { int c = 0; for (boolean b : any) if (b) c++; return c; }
        int countDefined() { int c = 0; for (boolean b : defined) if (b) c++; return c; }
    }

    TrackerMatcher(TrackerDb db) {
        this.db = db;
        int a = 1, total = 0;
        for (String[] alts : db.alts) {
            for (String alt : alts) {
                for (int i = 0; i < alt.length(); i++) {
                    char ch = alt.charAt(i);
                    if (ch >= 128) throw new IllegalArgumentException("non-ASCII signature: " + alt);
                    if (alpha[ch] == 0) alpha[ch] = (byte) (a++);
                }
                total += alt.length();
            }
        }
        alphabet = a;
        int max = total + 1;
        if (max > 65535) throw new IllegalStateException("too many states for char[] table");
        char[] nx = new char[max * alphabet];
        int[][] o = new int[max][];
        int st = 1;
        for (int t = 0; t < db.n; t++) {
            for (String alt : db.alts[t]) {
                int s = 0;
                for (int i = 0; i < alt.length(); i++) {
                    int c = alpha[alt.charAt(i)];
                    int ns = nx[s * alphabet + c];
                    if (ns == 0) { ns = st++; nx[s * alphabet + c] = (char) ns; }
                    s = ns;
                }
                o[s] = addUnique(o[s], t);
            }
        }
        // BFS: failure links + complete the automaton (0 = "no edge", root is never an edge target)
        int[] fail = new int[st];
        int[] q = new int[st];
        int head = 0, tail = 0;
        for (int c = 0; c < alphabet; c++) {
            int s = nx[c];
            if (s != 0) { fail[s] = 0; q[tail++] = s; }
        }
        while (head < tail) {
            int r = q[head++];
            for (int c = 0; c < alphabet; c++) {
                int s = nx[r * alphabet + c];
                int f = nx[fail[r] * alphabet + c];
                if (s != 0) {
                    fail[s] = f;
                    if (o[f] != null) for (int t : o[f]) o[s] = addUnique(o[s], t);
                    q[tail++] = s;
                } else {
                    nx[r * alphabet + c] = (char) f;
                }
            }
        }
        this.states = st;
        this.next = Arrays.copyOf(nx, st * alphabet);
        this.out = Arrays.copyOf(o, st);
    }

    private static int[] addUnique(int[] arr, int v) {
        if (arr == null) return new int[] { v };
        for (int x : arr) if (x == v) return arr;
        int[] n = Arrays.copyOf(arr, arr.length + 1);
        n[arr.length] = v;
        return n;
    }

    /**
     * Feeds one Dalvik type descriptor ("Lcom/foo/Bar;", "[Lcom/foo/Bar;", "I", ...). Non-class types are ignored.
     * @param d   buffer holding the descriptor
     * @param len number of valid bytes in d
     */
    void feedDescriptor(byte[] d, int len, boolean isDefined, Result r) {
        int i = 0;
        while (i < len && d[i] == '[') i++;              // array of X is a reference to X
        if (i >= len || d[i] != 'L' || d[len - 1] != ';') return;
        int s = 0;
        final int end = len - 1;
        for (int k = i + 1; k < end; k++) {
            int ch = d[k];
            int c = ch < 0 ? 0 : alpha[ch == '/' ? '.' : ch];
            s = next[s * alphabet + c];
            int[] o = out[s];
            if (o != null) {
                for (int t : o) {
                    r.any[t] = true;
                    if (isDefined) r.defined[t] = true;
                    if (r.example[t] == null) r.example[t] = dotted(d, i + 1, end);
                }
            }
        }
    }

    private static final Charset ISO = Charset.forName("ISO-8859-1");

    static String dotted(byte[] d, int from, int to) {
        return new String(d, from, to - from, ISO).replace('/', '.');
    }

    /** Convenience for tests: feed a dotted/slash class name given as a String. */
    void feedName(String className, boolean isDefined, Result r) {
        byte[] b = ("L" + className.replace('.', '/') + ";").getBytes(ISO);
        feedDescriptor(b, b.length, isDefined, r);
    }
}
