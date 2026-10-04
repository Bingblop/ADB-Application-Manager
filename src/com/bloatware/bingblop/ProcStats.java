package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the output of {@code ps -A -o PID,PPID,USER,RSS,%CPU,NAME} (Android's own toybox {@code ps}; this is the primary
 * format this class supports), plus a fallback for the plain {@code ps -A} that older, busybox-style devices print when they
 * have no {@code -o} support, whose column names and order differ from device to device. Free of Android classes so it can
 * be tested off the device.
 *
 * <p>The header line's own column labels say where PID, PPID, USER, RSS, %CPU and NAME sit (matched case-insensitively, so
 * {@code %CPU}/{@code CPU} and {@code NAME}/{@code COMMAND}/{@code CMD} are all understood, and a column this class does not
 * care about - VSZ, WCHAN, ADDR, S, TIME, ... - is simply skipped); a data row is then split into exactly that many
 * whitespace-separated fields. That shortcut is safe here only because NAME, for this command, is a bare package name or a
 * {@code package:processName} and so never itself contains a space - unlike the free-form, space-carrying CMD column of
 * {@code ps aux} / {@code ps -ef}, where splitting a line on whitespace would cut a command line into pieces. A header this
 * class cannot place PID in at all still gets a best-effort guess: of each row, the two left-most tokens that look like
 * numbers are taken as PID and PPID, and the right-most token as the name. Nothing here ever throws on malformed, truncated
 * or empty input; a row with fewer fields than the header promises is skipped, not fatal to the rest of the parse.
 */
final class ProcStats {

    private ProcStats() {}

    /** One row of a `ps` listing. */
    static final class Proc {
        public int pid, ppid;
        public String user = "";
        public long rssKb;
        public double cpuPercent;
        public String name = "";
    }

    /** The rows of a `ps` listing, and whether the header really carried USER, RSS and %CPU for them to be read from. */
    static final class Result {
        public List<Proc> procs = new ArrayList<Proc>();
        /** True when USER, RSS and %CPU columns were found in the header and parsed; false for a degraded or last-resort parse (see {@link #parse}). */
        public boolean fullFormat = false;
    }

    private static final String[] NAME_TOKENS = {"NAME", "COMMAND", "CMD"};
    private static final String[] CPU_TOKENS = {"%CPU", "CPU"};

    /**
     * Auto-detects the column layout from the first non-blank line (the header) and parses every line after it into a
     * {@link Proc}. Never throws and never returns null; blank or unrecognizable input gives back an empty, non-full-format
     * result.
     */
    static Result parse(String psOutput) {
        Result r = new Result();
        if (psOutput == null) return r;
        String[] lines = psOutput.split("\r?\n", -1);
        int headerAt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) { headerAt = i; break; }
        }
        if (headerAt < 0) return r;                                        // nothing but blank lines

        String[] cols = lines[headerAt].trim().split("\\s+");
        int pidIdx = indexOfHeader(cols, "PID");
        int ppidIdx = indexOfHeader(cols, "PPID");
        int userIdx = indexOfHeader(cols, "USER");
        int rssIdx = indexOfHeader(cols, "RSS");
        int cpuIdx = indexOfAnyHeader(cols, CPU_TOKENS);
        int nameIdx = indexOfAnyHeader(cols, NAME_TOKENS);
        boolean recognized = pidIdx >= 0;
        if (recognized && nameIdx < 0) nameIdx = cols.length - 1;           // NAME is always the last column for this command; trust its position over a label we don't know
        r.fullFormat = recognized && userIdx >= 0 && rssIdx >= 0 && cpuIdx >= 0;

        int numCols = cols.length;
        for (int i = headerAt + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            Proc p = recognized ? parseFullRow(line, numCols, pidIdx, ppidIdx, userIdx, rssIdx, cpuIdx, nameIdx) : parseGuessedRow(line);
            if (p != null) r.procs.add(p);
        }
        return r;
    }

    /**
     * One data row under a recognized header: split into exactly {@code numCols} whitespace-separated fields. Splitting a
     * fixed command's output into a fixed number of tokens like this would be wrong for a free-form CMD column (it could
     * swallow a quoted argument that itself contains spaces), but it is safe for this command's NAME column specifically,
     * which is only ever a bare package name or {@code package:processName} - see the class javadoc. Fewer fields than
     * {@code numCols} means the line was cut short on its way here (a truncated transfer, a corrupted buffer); that row is
     * skipped, not the whole parse.
     */
    private static Proc parseFullRow(String line, int numCols, int pidIdx, int ppidIdx, int userIdx, int rssIdx, int cpuIdx, int nameIdx) {
        String[] t = line.trim().split("\\s+", numCols);
        if (t.length < numCols) return null;
        Integer pid = tryParseInt(t[pidIdx]);
        if (pid == null) return null;                                      // no usable PID (e.g. a stray "?" under SELinux) - nothing to identify this row by
        Proc p = new Proc();
        p.pid = pid;
        if (ppidIdx >= 0) { Integer v = tryParseInt(t[ppidIdx]); if (v != null) p.ppid = v; }
        if (userIdx >= 0) p.user = t[userIdx];
        if (rssIdx >= 0) { Long v = tryParseLong(t[rssIdx]); if (v != null) p.rssKb = v; }
        if (cpuIdx >= 0) { Double v = tryParseDouble(t[cpuIdx]); if (v != null) p.cpuPercent = v; }
        p.name = t[nameIdx];
        return p;
    }

    /** A header this class could not place PID in at all: the first two numeric tokens (left to right) become PID and PPID, and the last token becomes the name. */
    private static Proc parseGuessedRow(String line) {
        String[] t = line.trim().split("\\s+");
        Integer pid = null, ppid = null;
        for (String tok : t) {
            Integer v = tryParseInt(tok);
            if (v == null) continue;
            if (pid == null) pid = v; else { ppid = v; break; }
        }
        if (pid == null) return null;                                      // not even one numeric token: nothing usable in this line
        Proc p = new Proc();
        p.pid = pid;
        if (ppid != null) p.ppid = ppid;
        p.name = t[t.length - 1];
        return p;
    }

    private static int indexOfHeader(String[] cols, String want) {
        for (int i = 0; i < cols.length; i++) if (cols[i].equalsIgnoreCase(want)) return i;
        return -1;
    }

    private static int indexOfAnyHeader(String[] cols, String[] wants) {
        for (String w : wants) {
            int i = indexOfHeader(cols, w);
            if (i >= 0) return i;
        }
        return -1;
    }

    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static Long tryParseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double tryParseDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    // ---- process name -> package ----

    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", Pattern.CASE_INSENSITIVE);

    /**
     * The Android package a `ps` NAME column names, or null when it is a kernel/native process ({@code kworker/0:1},
     * {@code [kthreadd]}, {@code /system/bin/...}, {@code logd}, an empty name, ...) rather than an app. A secondary process
     * is printed as {@code package:processName}; only the part before the first colon is tested against the package-name
     * shape, and that part (not the whole string) is what is returned.
     */
    static String packageOf(String psName) {
        if (psName == null || psName.isEmpty()) return null;
        int colon = psName.indexOf(':');
        String base = colon >= 0 ? psName.substring(0, colon) : psName;
        return PACKAGE_NAME.matcher(base).matches() ? base : null;
    }

    // ---- sorting / totals ----

    private static final Comparator<Proc> BY_CPU_DESC = new Comparator<Proc>() {
        @Override public int compare(Proc a, Proc b) { return Double.compare(b.cpuPercent, a.cpuPercent); }
    };
    private static final Comparator<Proc> BY_RSS_DESC = new Comparator<Proc>() {
        @Override public int compare(Proc a, Proc b) { return Long.compare(b.rssKb, a.rssKb); }
    };

    /** The {@code limit} busiest processes by %CPU, highest first; ties keep their original relative order. {@code limit <= 0} or {@code >= size} returns everyone, sorted. Null or empty input returns an empty list. */
    static List<Proc> topByCpu(List<Proc> procs, int limit) { return top(procs, limit, BY_CPU_DESC); }

    /** The {@code limit} processes holding the most RSS, highest first; ties keep their original relative order. {@code limit <= 0} or {@code >= size} returns everyone, sorted. Null or empty input returns an empty list. */
    static List<Proc> topByMem(List<Proc> procs, int limit) { return top(procs, limit, BY_RSS_DESC); }

    private static List<Proc> top(List<Proc> procs, int limit, Comparator<Proc> cmp) {
        List<Proc> out = new ArrayList<Proc>();
        if (procs == null || procs.isEmpty()) return out;
        out.addAll(procs);
        Collections.sort(out, cmp);                                        // a stable sort: equal values keep their original relative order
        if (limit > 0 && limit < out.size()) return new ArrayList<Proc>(out.subList(0, limit));
        return out;
    }

    /** The RSS of every process added up, in KiB; 0 for null or empty. */
    static long totalRssKb(List<Proc> procs) {
        long sum = 0;
        if (procs == null) return sum;
        for (Proc p : procs) sum += p.rssKb;
        return sum;
    }
}
