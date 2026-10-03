package com.bloatware.bingblop;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Settings tab's rules, free of Android classes so they can be tested off the device: which tables and names may be
 * touched, the exact shell commands (every name and value single-quoted for sh, so nothing typed can end up as shell code),
 * how the output of {@code settings list} is split into entries, and how the answer to a change is read.
 *
 * <p>A change is one shell line: the {@code settings put} / {@code delete}, then a read of the same key between two markers.
 * Android answers a refused change with an exception text (or nothing at all when a service quietly puts the old value back),
 * so the read-back is what says whether the setting really holds the new value.
 */
final class SettingsDb {

    private SettingsDb() {}

    static final String[] NAMESPACES = {"global", "secure", "system"};
    static final int MAX_KEY = 256;
    static final int MAX_VALUE = 20000;
    /** adb frames a request with four hex digits, so a command of 64 KiB or more is corrupted on the way; this leaves room for the rest of the line. */
    static final int MAX_QUOTED_VALUE_BYTES = 62000;
    static final String GET_MARK = "@@SDB-GET@@";
    static final String LIST_MARK = "@@SDB-LIST@@";
    static final String END_MARK = "@@SDB-END@@";

    private static final Pattern DELETED = Pattern.compile("Deleted\\s+(\\d+)\\s+rows?", Pattern.CASE_INSENSITIVE);

    static boolean isNamespace(String ns) {
        if (ns == null) return false;
        for (String n : NAMESPACES) if (n.equals(ns)) return true;
        return false;
    }

    /** Null when the table name is one of the three, else why not. */
    static String namespaceProblem(String ns) {
        return isNamespace(ns) ? null : "Unknown settings table (use global, secure or system)";
    }

    /** Null when the name can be used, else why not. Names are quoted for the shell, so only what would confuse {@code settings} itself is refused. */
    static String keyProblem(String key) {
        if (key == null || key.isEmpty()) return "Enter a name";
        if (key.length() > MAX_KEY) return "The name is too long (" + MAX_KEY + " characters at most)";
        if (key.charAt(0) == '-') return "A name can't start with a dash";
        for (int i = 0; i < key.length(); ) {
            int cp = key.codePointAt(i);
            if (cp == '=') return "A name can't contain =";
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) || cp == 0xFEFF) {
                return "A name can't contain spaces or control characters";
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    /** Null when the value can be stored, else why not. An empty value is fine. */
    static String valueProblem(String value) {
        if (value == null) return "No value";
        if (value.length() > MAX_VALUE) return "The value is too long (" + MAX_VALUE + " characters at most)";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) return "A value can't contain a NUL character";
            if (c == '\r') return "A value can't contain a carriage return (use plain line breaks)";     // the answer's lines are read back with \r\n folded to \n
            if (c < 0x20 && c != '\t' && c != '\n') return "A value can't contain control characters";
        }
        int bytes = quotedBytes(value);
        if (bytes > MAX_QUOTED_VALUE_BYTES) return "The value is too long for adb to carry (" + bytes + " bytes once quoted, about " + MAX_QUOTED_VALUE_BYTES + " at most)";
        return null;
    }

    /** How many bytes the value takes on the command line: quoted, in UTF-8 (every apostrophe becomes four characters). */
    static int quotedBytes(String value) {
        return quote(value).getBytes(StandardCharsets.UTF_8).length;
    }

    static String quote(String s) {
        return BackupScripts.quote(s);
    }

    /** The listing, then a marker line: a table is only complete when the marker is the last line (see {@link #readList}). */
    static String listCommand(String ns) {
        requireNamespace(ns);
        return "settings list " + ns + "; echo " + END_MARK;
    }

    static String getCommand(String ns, String key) {
        requireNamespace(ns);
        requireKey(key);
        return "settings get " + ns + " " + quote(key);
    }

    static String putCommand(String ns, String key, String value) {
        requireNamespace(ns);
        requireKey(key);
        requireValue(value);
        return "settings put " + ns + " " + quote(key) + " " + quote(value);
    }

    static String deleteCommand(String ns, String key) {
        requireNamespace(ns);
        requireKey(key);
        return "settings delete " + ns + " " + quote(key);
    }

    /**
     * One shell line for a "put" or "delete" (op), then a read-back of the key; or just the read-back for "get". The parts are
     * told apart by marker lines that also carry the exit status of the command before them (see {@link #parseWrite}):
     * <pre>change; echo @@SDB-GET@@$?; settings get ..; echo @@SDB-END@@$?</pre>
     * {@code settings get} prints the word null both for a missing key and for the text "null", so where that cannot tell
     * (putting "null", deleting) the line goes on to list the table: <pre>..; echo @@SDB-LIST@@$?; settings list ..; echo @@SDB-END@@$?</pre>
     */
    static String writeScript(String op, String ns, String key, String value) {
        String read = getCommand(ns, key);
        if ("get".equals(op)) return "echo " + GET_MARK + "; " + read + "; echo " + END_MARK + "$?";
        String change;
        boolean probe;
        if ("put".equals(op)) {
            change = putCommand(ns, key, value);
            probe = "null".equals(value);
        } else if ("delete".equals(op)) {
            change = deleteCommand(ns, key);
            probe = true;
        } else {
            throw new IllegalArgumentException("unknown operation");
        }
        StringBuilder sb = new StringBuilder(change);
        sb.append("; echo ").append(GET_MARK).append("$?; ").append(read);
        if (probe) sb.append("; echo ").append(LIST_MARK).append("$?; settings list ").append(ns);
        sb.append("; echo ").append(END_MARK).append("$?");
        return sb.toString();
    }

    private static void requireNamespace(String ns) {
        String p = namespaceProblem(ns);
        if (p != null) throw new IllegalArgumentException(p);
    }

    private static void requireKey(String key) {
        String p = keyProblem(key);
        if (p != null) throw new IllegalArgumentException(p);
    }

    private static void requireValue(String value) {
        String p = valueProblem(value);
        if (p != null) throw new IllegalArgumentException(p);
    }

    // ---- reading answers ----

    /** A name starts an entry of {@code settings list}: non-empty, no spaces (a continuation line of a multi-line value rarely looks like that). */
    private static boolean isEntryName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= ' ' || c == 0x7f || Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    /**
     * Splits the lines of a listing into {name, value} entries, in the order given. A line without a "name=" start belongs to the
     * value before it (values can hold line breaks); lines before the first entry (a warning from adb, say) are dropped.
     */
    private static List<String[]> splitEntries(String body) {
        List<String[]> res = new ArrayList<String[]>();
        if (body == null || body.isEmpty()) return res;
        String curKey = null;
        StringBuilder cur = null;
        for (String line : body.split("\r?\n", -1)) {
            int eq = line.indexOf('=');
            if (eq > 0 && isEntryName(line.substring(0, eq))) {
                if (curKey != null) res.add(new String[] {curKey, cur.toString()});
                curKey = line.substring(0, eq);
                cur = new StringBuilder(line.substring(eq + 1));
            } else if (curKey != null) {
                cur.append('\n').append(line);
            }
        }
        if (curKey != null) res.add(new String[] {curKey, cur.toString()});
        return res;
    }

    /** The text without its closing line breaks (a loop, not a regex: a long run of them must not overflow the stack). */
    private static String stripLineBreaks(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** The text without its last line break (one only: it belongs to the line before, not to a value that ends with a blank line). */
    private static String stripOneLineBreak(String s) {
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
        return s;
    }

    /** Entries of a listing that is taken as it comes (no marker): every closing line break is dropped. */
    static List<String[]> parseList(String out) {
        if (out == null || out.isEmpty()) return new ArrayList<String[]>();
        return splitEntries(stripLineBreaks(out));
    }

    /** The answer to {@link #listCommand}: the entries, and whether the closing marker came (so nothing was cut off on the way). */
    static final class ListResult {
        List<String[]> entries = new ArrayList<String[]>();
        boolean complete = false;
        String text = "";                    // what came, for an error message
    }

    static ListResult readList(String out) {
        ListResult r = new ListResult();
        if (out == null) return r;
        r.text = out;
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == '\n' || out.charAt(end - 1) == '\r' || out.charAt(end - 1) == ' ' || out.charAt(end - 1) == '\t')) end--;
        int mark = end - END_MARK.length();
        if (mark < 0 || !out.startsWith(END_MARK, mark)) return r;
        if (mark > 0 && out.charAt(mark - 1) != '\n') return r;                  // the marker is a line of its own
        r.complete = true;
        r.entries = splitEntries(stripOneLineBreak(out.substring(0, mark)));    // the last entry's own line break comes before the marker
        return r;
    }

    /** What a change printed, what the key holds now, and (where it was asked for) whether a listing shows the key. */
    static final class WriteResult {
        String answer = "";        // what the put / delete itself printed (an error text, "Deleted 1 rows", or nothing)
        int rc = -1;               // that command's exit status, -1 when it did not say
        String value = null;       // what the key holds now ("null" when it is not there); null when the read-back never came or failed
        String readError = "";     // what the read-back printed when `settings get` itself failed
        Boolean present = null;    // whether the listing shows the key; null when it was not asked for or did not finish
        int deleted = -1;          // the row count of a "Deleted N rows" answer, -1 when it did not say
    }

    private static int deletedCount(String answer) {
        Matcher m = DELETED.matcher(answer);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Reads the digits right after a marker (the exit status echoed with it) into rc[0], -1 when there are none; returns the position after them. */
    private static int readStatus(String out, int pos, int[] rc) {
        int p = pos;
        long v = 0;
        while (p < out.length() && p - pos < 6 && out.charAt(p) >= '0' && out.charAt(p) <= '9') {
            v = v * 10 + (out.charAt(p) - '0');
            p++;
        }
        rc[0] = p > pos ? (int) v : -1;
        return p;
    }

    private static int skipLineBreak(String out, int pos) {
        int p = pos;
        if (p < out.length() && out.charAt(p) == '\r') p++;
        if (p < out.length() && out.charAt(p) == '\n') p++;
        return p;
    }

    static WriteResult parseWrite(String out) {
        return parseWrite(out, null);
    }

    /** key: the name the listing (if there is one) is searched for. */
    static WriteResult parseWrite(String out, String key) {
        WriteResult r = new WriteResult();
        if (out == null) return r;
        int g = out.indexOf(GET_MARK);
        if (g < 0) {
            r.answer = out.trim();
            r.deleted = deletedCount(r.answer);
            return r;
        }
        r.answer = out.substring(0, g).trim();
        r.deleted = deletedCount(r.answer);
        int[] st = new int[1];
        int p = readStatus(out, g + GET_MARK.length(), st);
        r.rc = st[0];
        p = skipLineBreak(out, p);
        int end = out.lastIndexOf(END_MARK);
        boolean finished = end >= p;
        int list = out.indexOf(LIST_MARK, p);
        if (!finished && list < 0) return r;                                // the read-back never finished
        String body;
        int readRc;
        int listRc = -1;
        String listBody = null;
        if (list >= 0 && (!finished || list < end)) {
            body = out.substring(p, list);                                  // the read-back is complete: the listing's marker follows it
            int q = readStatus(out, list + LIST_MARK.length(), st);
            readRc = st[0];
            q = skipLineBreak(out, q);
            if (finished) {
                listBody = out.substring(Math.min(q, end), end);            // a listing that never finished is left out
                readStatus(out, end + END_MARK.length(), st);
                listRc = st[0];
            }
        } else {
            body = out.substring(p, end);
            readStatus(out, end + END_MARK.length(), st);
            readRc = st[0];
        }
        // `settings get` ends its answer with one line break; that one is not part of the value
        body = stripOneLineBreak(body);
        if (readRc > 0 && !"null".equals(body)) r.readError = body;         // it failed: that text is an error, not the value
        else r.value = body;
        if (listBody != null && key != null && listRc <= 0) {
            r.present = Boolean.FALSE;
            for (String[] e : splitEntries(stripOneLineBreak(listBody))) {
                if (e[0].equals(key)) {
                    r.present = Boolean.TRUE;
                    break;
                }
            }
        }
        return r;
    }

    /** The verdict on a change: whether the setting now holds what was asked for, and if not, one line saying what happened. */
    static final class Verdict {
        boolean ok;
        boolean unknown;           // the read-back never came (the link dropped, say), so what became of the change is not known
        String error = "";
    }

    /**
     * Whether a change worked, decided by what the key holds afterwards rather than by Android's silence: a put worked when the
     * read-back is the value asked for, a delete when the key is gone, a get when it answered. The word null is the one value
     * the read-back cannot vouch for (a missing key prints it too), so there Android's own error text and exit status, and the
     * listing when it was asked for, have a say.
     */
    static Verdict judge(String op, String requested, WriteResult w) {
        Verdict v = new Verdict();
        if (w.value == null) {
            if (!w.readError.isEmpty()) {
                v.error = summary(w.readError);
                return v;
            }
            v.unknown = true;
            v.error = w.answer.isEmpty() ? "No answer from the device" : summary(w.answer);
            return v;
        }
        boolean refused = w.rc > 0 || looksLikeFailure(w.answer);
        if ("get".equals(op)) {
            v.ok = true;
        } else if ("put".equals(op)) {
            if (!w.value.equals(requested)) {
                v.error = refused ? summary(w.answer) : "Android did not keep the new value";
            } else if ("null".equals(requested)) {
                v.ok = !refused && (w.present == null || w.present);
                if (!v.ok) v.error = refused ? summary(w.answer) : "Android did not create it";
            } else {
                v.ok = true;
            }
        } else {
            v.ok = "null".equals(w.value) && !refused && (w.present == null || !w.present);
            if (!v.ok) v.error = refused ? summary(w.answer) : "null".equals(w.value) ? "Android did not delete it" : "Android still has a value for it";
        }
        if (!v.ok && v.error.isEmpty()) v.error = "It did not work";
        return v;
    }

    /** True when the text of a refused change (or a failed listing) looks like an error rather than normal output. */
    static boolean looksLikeFailure(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String a = text.toLowerCase(Locale.US);
        return a.contains("exception") || a.contains("permission denial") || a.contains("permission denied") || a.contains("not allowed")
                || a.contains("unknown command") || a.contains("usage: settings") || a.contains("can't find service")
                || a.contains("cmd: failure") || a.contains("error:") || a.contains("failed") || FileRules.transportLost(text);
    }

    /** One short line out of an error text: the line that names the exception or the error, else the first line. */
    static String summary(String text) {
        if (text == null) return "";
        String first = "";
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (first.isEmpty()) first = t;
            String low = t.toLowerCase(Locale.US);
            if (low.contains("exception:") || low.contains("permission denial") || low.startsWith("error")) {
                first = t;
                break;
            }
        }
        return first.length() > 260 ? first.substring(0, 260) + "…" : first;
    }

    /** A plain-words hint for the usual reasons a change is refused; empty when there is nothing useful to add. */
    static String advice(String text) {
        if (text == null) return "";
        String a = text.toLowerCase(Locale.US);
        if (FileRules.transportLost(text)) {
            return "The connection to the device was lost. Reconnect in Working Modes, then try again.";
        }
        if (a.contains("write_secure_settings") || a.contains("permission denial") || a.contains("securityexception")) {
            return "Android refused the request. Some phones (Xiaomi, Redmi, POCO) only allow it once \"USB debugging (Security settings)\" is on in Developer options, and some keys are locked by the system.";
        }
        if (a.contains("invalid value") || a.contains("illegalargumentexception")) {
            return "Android rejected that value for this setting. Try a value of the same kind as the current one.";
        }
        if (a.contains("can't find service") || a.contains("cmd: failure")) {
            return "The settings service did not answer. Try again in a moment, or restart the phone.";
        }
        if (a.contains("unknown command") || a.contains("usage: settings")) {
            return "This Android version did not understand the command.";
        }
        return "";
    }
}
