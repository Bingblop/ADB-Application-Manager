package com.bloatware.bingblop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link Sdm.Fs} over a {@link Sdm.Shell} (spec section 8.2): what the app cannot read itself (Android/data and Android/obb on newer phones,
 * private app data as root) is read and removed through the working mode. Commands are plain toybox/mksh ones, no busybox and no
 * {@code find -printf}: a tree is {@code find ... -exec stat -c '%f %s %Y %u %n' {} +} streamed line by line while it runs, a listing is
 * {@code ls -1A}, a hash {@code sha256sum --}, a head {@code head -c | od}, a delete {@code rm -rf --} followed by an existence check.
 * Every path is quoted with {@link BackupScripts#quote} and never goes through {@code eval}; every script ends with the line
 * {@code __SDM_END__}, because the old adb protocol loses the exit code and a missing sentinel therefore means truncated output.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; upstream reads and deletes through
 * its own root/ADB gateways, this class is the port's replacement. Pure Java, no android.* classes.
 */
public final class SdmFsShell implements Sdm.Fs, SdmEngine.PruningFs {

    public static final String END = "__SDM_END__";
    private static final String OK = "__SDM_OK__";
    private static final String NO = "__SDM_NO__";
    private static final String STAT_FORMAT = "'%f %s %Y %u %n'";
    private static final int BATCH_PATHS = 150;
    private static final int BATCH_BYTES = 60000;
    private static final int T_SHORT = 60000;
    private static final int T_BATCH = 15 * 60000;
    private static final int T_HASH = 60 * 60000;
    private static final int T_WALK = 4 * 60 * 60000;
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{1,8}");
    private static final Pattern NUM = Pattern.compile("-?\\d{1,19}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{64}");

    private final Sdm.Shell shell;

    public SdmFsShell(Sdm.Shell shell) {
        this.shell = shell;
    }

    private static String q(String s) {
        return BackupScripts.quote(s);
    }

    /** Runs a script and returns its lines up to the sentinel; throws when the sentinel never came (the output was cut). */
    private List<String> lines(String script, int timeoutMs, Sdm.Cancel cancel, String what) throws IOException {
        final List<String> out = new ArrayList<String>();
        final boolean[] end = { false };
        shell.stream(script, timeoutMs, new Sdm.LineSink() {
            @Override public void line(String line) {
                if (line.equals(END)) end[0] = true;
                else if (!end[0]) out.add(line);
            }
        }, cancel);
        if (!end[0]) {
            if (cancel != null && cancel.cancelled()) return out;
            throw new IOException(what + ": the output was cut short");
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- read

    @Override
    public String[] list(String dir) throws IOException {
        String d = q(dir);
        List<String> l = lines("if [ -d " + d + " ]; then ls -1A " + d + " 2>/dev/null && echo " + OK + " || echo " + NO + "; else echo " + NO + "; fi; echo " + END,
                T_SHORT, null, "list " + dir);
        if (l.isEmpty() || !l.get(l.size() - 1).equals(OK)) return null;
        l.remove(l.size() - 1);
        return l.toArray(new String[0]);
    }

    /** One line of {@code stat -c '%f %s %Y %u %n'}: raw mode in hex, size, mtime, uid, then the name (which may hold spaces). Null when the line is not one. */
    public static Sdm.Entry parseStat(String line) {
        if (line == null) return null;
        int a = line.indexOf(' ');
        if (a <= 0) return null;
        int b = line.indexOf(' ', a + 1);
        if (b < 0) return null;
        int c = line.indexOf(' ', b + 1);
        if (c < 0) return null;
        int d = line.indexOf(' ', c + 1);
        if (d < 0 || d + 1 >= line.length()) return null;
        String mode = line.substring(0, a), size = line.substring(a + 1, b), mtime = line.substring(b + 1, c), uid = line.substring(c + 1, d);
        if (!HEX.matcher(mode).matches() || !NUM.matcher(size).matches() || !NUM.matcher(mtime).matches() || !NUM.matcher(uid).matches()) return null;
        String name = line.substring(d + 1);
        if (name.isEmpty() || name.charAt(0) != '/') return null;
        int fmt = (int) (Long.parseLong(mode, 16) & 0170000);
        int type = fmt == 0040000 ? Sdm.DIR : fmt == 0100000 ? Sdm.FILE : fmt == 0120000 ? Sdm.LINK : Sdm.OTHER;
        long u = Long.parseLong(uid);
        return new Sdm.Entry(name, Long.parseLong(size), Long.parseLong(mtime), type, u > Integer.MAX_VALUE ? -1 : (int) u);
    }

    @Override
    public Sdm.Entry stat(String path) throws IOException {
        List<String> l = lines("stat -c " + STAT_FORMAT + " -- " + q(path) + " 2>/dev/null; echo " + END, T_SHORT, null, "stat " + path);
        for (String s : l) {
            Sdm.Entry e = parseStat(s);
            if (e != null) return e;
        }
        return null;
    }

    /** Several paths in as few processes as possible (what a tool should use instead of a loop of {@link #stat}); a path with nothing there is missing from the map. */
    public Map<String, Sdm.Entry> statAll(Collection<String> paths, Sdm.Cancel cancel) throws IOException {
        Map<String, Sdm.Entry> out = new LinkedHashMap<String, Sdm.Entry>();
        for (List<String> batch : batches(paths)) {
            if (cancel != null && cancel.cancelled()) break;
            StringBuilder sb = new StringBuilder("stat -c " + STAT_FORMAT + " --");
            for (String p : batch) sb.append(' ').append(q(p));
            sb.append(" 2>/dev/null; echo ").append(END);
            for (String s : lines(sb.toString(), T_BATCH, cancel, "stat")) {
                Sdm.Entry e = parseStat(s);
                if (e != null) out.put(e.path, e);
            }
        }
        return out;
    }

    @Override
    public boolean exists(String path) {
        try {
            String p = q(path);
            List<String> l = lines("if [ -e " + p + " ] || [ -L " + p + " ]; then echo Y; else echo N; fi; echo " + END, T_SHORT, null, "exists");
            return !l.isEmpty() && l.get(0).equals("Y");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void walk(String root, Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException {
        walk(root, new ArrayList<String>(), sink, cancel);
    }

    @Override
    public void walk(String root, Collection<String> prune, final Sdm.EntrySink sink, final Sdm.Cancel cancel) throws IOException {
        final String r = root.length() > 1 && root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        StringBuilder sb = new StringBuilder("find ").append(q(r)).append(" -mindepth 1 ");
        List<String> pr = new ArrayList<String>();
        if (prune != null) for (String p : prune) if (p != null && p.startsWith("/") && !p.equals(r) && (r.equals("/") || p.startsWith(r + "/"))) pr.add(p);
        if (!pr.isEmpty()) {
            sb.append("\\(");
            boolean first = true;
            for (String p : pr) {
                sb.append(first ? " " : " -o ").append("-path ").append(q(globEscape(p)));
                first = false;
            }
            sb.append(" \\) -prune -o ");
        }
        sb.append("-exec stat -c ").append(STAT_FORMAT).append(" {} + 2>/dev/null; echo ").append(END);
        final String[] skip = { null };
        final boolean[] end = { false };
        shell.stream(sb.toString(), T_WALK, new Sdm.LineSink() {
            @Override public void line(String line) {
                if (end[0]) return;
                if (line.equals(END)) { end[0] = true; return; }
                Sdm.Entry e = parseStat(line);
                if (e == null) return;
                if (skip[0] != null) {
                    if (e.path.startsWith(skip[0])) return;       // below a directory the sink left out: find is depth first, so these come right after it
                    skip[0] = null;
                }
                boolean into = sink.accept(e);
                if (e.type == Sdm.DIR && !into) skip[0] = e.path + "/";
            }
        }, cancel);
        if (!end[0] && !(cancel != null && cancel.cancelled())) throw new IOException("walk " + root + ": the listing was cut short");
    }

    /** -path patterns are globs: a name that holds * ? [ or a backslash has to be escaped to mean itself. */
    static String globEscape(String p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' || c == '*' || c == '?' || c == '[') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String sha256(String path, Sdm.Cancel cancel) throws IOException {
        List<String> l = lines("sha256sum -- " + q(path) + " 2>/dev/null; echo " + END, T_HASH, cancel, "sha256 " + path);
        if (cancel != null && cancel.cancelled()) throw new IOException("cancelled");
        for (String s : l) {
            int sp = s.indexOf(' ');
            if (sp == 64 && SHA.matcher(s.substring(0, 64)).matches()) return s.substring(0, 64).toLowerCase(java.util.Locale.ROOT);
        }
        throw new IOException("cannot read " + path);
    }

    @Override
    public byte[] head(String path, int max) throws IOException {
        if (max <= 0) return new byte[0];
        String p = q(path);
        List<String> l = lines("if [ -f " + p + " ] && [ -r " + p + " ]; then head -c " + max + " " + p + " 2>/dev/null | od -An -v -tx1 2>/dev/null; echo " + OK + "; else echo " + NO + "; fi; echo " + END,
                T_SHORT, null, "head " + path);
        if (l.isEmpty() || !l.get(l.size() - 1).equals(OK)) return null;
        l.remove(l.size() - 1);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        for (String s : l) {
            for (String t : s.trim().split("\\s+")) {
                if (t.length() == 2) {
                    try { bo.write(Integer.parseInt(t, 16)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return bo.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------------- delete

    @Override
    public boolean delete(String path) {
        if (SdmSafety.lexical(path) != null) return false;
        try {
            String p = q(path);
            List<String> l = lines("rm -rf -- " + p + " 2>/dev/null; if [ -e " + p + " ] || [ -L " + p + " ]; then echo F; else echo D; fi; echo " + END, T_BATCH, null, "delete");
            return !l.isEmpty() && l.get(0).equals("D");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<List<String>> batches(Collection<String> paths) {
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> cur = new ArrayList<String>();
        int bytes = 0;
        for (String p : paths) {
            int n = p.length() * 2 + 8;      // quoted, in the worst case every character is a multi-byte one
            if (!cur.isEmpty() && (cur.size() >= BATCH_PATHS || bytes + n > BATCH_BYTES)) { out.add(cur); cur = new ArrayList<String>(); bytes = 0; }
            cur.add(p);
            bytes += n;
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    @Override
    public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) {
        Set<String> gone = new HashSet<String>();
        LinkedHashSet<String> todo = new LinkedHashSet<String>();
        for (String p : paths) if (SdmSafety.lexical(p) == null) todo.add(p);
        for (List<String> batch : batches(todo)) {
            if (cancel != null && cancel.cancelled()) break;
            StringBuilder sb = new StringBuilder("for p in");
            for (String p : batch) sb.append(' ').append(q(p));
            sb.append("; do rm -rf -- \"$p\" 2>/dev/null; if [ -e \"$p\" ] || [ -L \"$p\" ]; then echo \"F:$p\"; else echo \"D:$p\"; fi; done; echo ").append(END);
            Set<String> asked = new HashSet<String>(batch);
            Set<String> answered = new HashSet<String>();
            try {
                final Set<String> doneNow = new HashSet<String>(), failedNow = new HashSet<String>();
                lines(sb.toString(), T_BATCH, cancel, "delete", doneNow, failedNow);
                for (String p : doneNow) if (asked.contains(p)) { gone.add(p); answered.add(p); }
                for (String p : failedNow) answered.add(p);
            } catch (IOException e) {
                // output cut short: whatever was answered is below, the rest is looked at again
            }
            List<String> unknown = new ArrayList<String>();
            for (String p : batch) if (!answered.contains(p) && !gone.contains(p)) unknown.add(p);
            if (!unknown.isEmpty() && !(cancel != null && cancel.cancelled())) gone.addAll(goneAmong(unknown));
        }
        return gone;
    }

    /** A variant of lines() for the delete batches: collects the D: and F: answers even when the sentinel does not come. */
    private void lines(String script, int timeoutMs, Sdm.Cancel cancel, String what, final Set<String> done, final Set<String> failed) throws IOException {
        final boolean[] end = { false };
        shell.stream(script, timeoutMs, new Sdm.LineSink() {
            @Override public void line(String line) {
                if (line.equals(END)) end[0] = true;
                else if (line.startsWith("D:")) done.add(line.substring(2));
                else if (line.startsWith("F:")) failed.add(line.substring(2));
            }
        }, cancel);
        if (!end[0] && !(cancel != null && cancel.cancelled())) throw new IOException(what + ": the output was cut short");
    }

    /** Which of these paths no longer exist. */
    private Set<String> goneAmong(List<String> paths) {
        Set<String> gone = new HashSet<String>();
        for (List<String> batch : batches(paths)) {
            StringBuilder sb = new StringBuilder("for p in");
            for (String p : batch) sb.append(' ').append(q(p));
            sb.append("; do if [ -e \"$p\" ] || [ -L \"$p\" ]; then :; else echo \"G:$p\"; fi; done; echo ").append(END);
            try {
                for (String s : lines(sb.toString(), T_BATCH, null, "verify")) if (s.startsWith("G:")) gone.add(s.substring(2));
            } catch (IOException ignored) {
                // cannot tell: they count as not deleted
            }
        }
        return gone;
    }
}
