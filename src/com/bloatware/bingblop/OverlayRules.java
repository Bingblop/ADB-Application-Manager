package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Overlays tab's rules, free of Android classes so they can be tested off the device: how the output of
 * {@code cmd overlay list} is read, which overlay names may be touched, the shell commands that enable and disable one, and
 * the value Android's Material You theme setting ({@code theme_customization_overlay_packages}) is given.
 *
 * <p>Like {@link SettingsDb}, a change is one shell line that ends with a read-back between two markers, so whether it worked
 * is decided by what Android reports afterwards, not by what the command printed. Every name and value that reaches the
 * shell is single-quoted, so nothing typed can end up as shell code.
 */
final class OverlayRules {

    private OverlayRules() {}

    static final int MAX_ID = 300;
    static final int MAX_THEME_VALUE = 4000;
    static final String GET_MARK = "@@OVL-GET@@";
    static final String END_MARK = "@@OVL-END@@";
    static final String FLAG_MARK = "@@OVL-FLAG@@";

    /** The secure setting the system's Monet engine reads its source colour and style from. */
    static final String THEME_KEY = "theme_customization_overlay_packages";
    /** Samsung's "wallpaper colours" switch; which settings table holds it differs, so it is looked for in all three. */
    static final String FLAG_KEY = "wallpapertheme_state";

    static final String SOURCE_WALLPAPER = "home_wallpaper";
    static final String SOURCE_PRESET = "preset";
    static final String DEFAULT_STYLE = "TONAL_SPOT";
    static final String[] STYLES = {"TONAL_SPOT", "VIBRANT", "EXPRESSIVE", "FRUIT_SALAD", "RAINBOW", "SPRITZ"};

    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");
    private static final Pattern COLOR_SOURCE = Pattern.compile("\"android\\.theme\\.customization\\.color_source\"\\s*:\\s*\"([a-z_]+)\"");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9_.:$@+/-]+");

    // ---- overlay names ----

    /** Null when the overlay name can be used, else why not. Names are quoted for the shell, so only what would confuse {@code cmd overlay} itself is refused. */
    static String idProblem(String id) {
        if (id == null || id.isEmpty()) return "No overlay named";
        if (id.length() > MAX_ID) return "The overlay name is too long (" + MAX_ID + " characters at most)";
        if (id.charAt(0) == '-') return "An overlay name can't start with a dash";
        for (int i = 0; i < id.length(); ) {
            int cp = id.codePointAt(i);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) || cp == 0xFEFF) {
                return "An overlay name can't contain spaces or control characters";
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    static boolean isOp(String op) {
        return "enable".equals(op) || "disable".equals(op);
    }

    // ---- commands ----

    static String listCommand() {
        return "cmd overlay list";
    }

    /** The listing, then a marker line: a list is only complete when the marker is its last line (see {@link #readList}). */
    static String listScript() {
        return listCommand() + "; echo " + END_MARK;
    }

    static String changeCommand(String op, String id) {
        if (!isOp(op)) throw new IllegalArgumentException("unknown operation");
        String p = idProblem(id);
        if (p != null) throw new IllegalArgumentException(p);
        return "cmd overlay " + op + " " + BackupScripts.quote(id);
    }

    /** The change, then the whole list again between the markers: the list says what state the overlay is really in. */
    static String changeScript(String op, String id) {
        return changeCommand(op, id) + "; echo " + GET_MARK + "; " + listCommand() + "; echo " + END_MARK;
    }

    // ---- reading the list ----

    /** One overlay: state is 1 when it is on, 0 when it is off, -1 when Android lists it but it cannot be switched (its target app is missing, say). */
    static final class Overlay {
        final String id;
        final String target;
        final int state;

        Overlay(String id, String target, int state) {
            this.id = id;
            this.target = target;
            this.state = state;
        }
    }

    /**
     * Reads the output of {@code cmd overlay list}: a line naming the target package, then one line per overlay that
     * targets it, each starting with {@code [x]} (on), {@code [ ]} (off) or {@code ---} (cannot be switched). Anything else
     * (a warning from adb, a blank line) is skipped.
     */
    static List<Overlay> parseList(String out) {
        List<Overlay> res = new ArrayList<Overlay>();
        if (out == null || out.isEmpty()) return res;
        String target = "";
        for (String line : out.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int state;
            String rest;
            if (t.startsWith("[x]") || t.startsWith("[X]")) {
                state = 1;
                rest = t.substring(3);
            } else if (t.startsWith("[ ]")) {
                state = 0;
                rest = t.substring(3);
            } else if (t.startsWith("---")) {
                state = -1;
                rest = t.substring(3);
            } else {
                // a target package has no status and no spaces in it; a sentence of noise has both
                if (HEADER.matcher(t).matches() && !t.startsWith("-")) target = t;
                continue;
            }
            rest = rest.trim();
            int sp = firstSpace(rest);
            if (sp > 0) rest = rest.substring(0, sp);               // an id never holds a space; anything after it is a remark
            if (rest.isEmpty()) continue;
            res.add(new Overlay(rest, target, state));
        }
        return res;
    }

    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || Character.isSpaceChar(c)) return i;
        }
        return -1;
    }

    /** The answer to {@link #listScript}: the overlays, and whether the closing marker came (so nothing was cut off on the way). */
    static final class ListResult {
        List<Overlay> overlays = new ArrayList<Overlay>();
        boolean complete = false;
        String text = "";
    }

    static ListResult readList(String out) {
        ListResult r = new ListResult();
        if (out == null) return r;
        r.text = out;
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == '\n' || out.charAt(end - 1) == '\r' || out.charAt(end - 1) == ' ' || out.charAt(end - 1) == '\t')) end--;
        int mark = end - END_MARK.length();
        if (mark < 0 || !out.startsWith(END_MARK, mark)) return r;
        if (mark > 0 && out.charAt(mark - 1) != '\n') return r;
        r.complete = true;
        r.overlays = parseList(out.substring(0, mark));
        return r;
    }

    /** What a change printed, and the list it was followed by (null when that never came, or was an error instead of a list). */
    static final class ChangeResult {
        String answer = "";
        List<Overlay> all = null;
        String listError = "";        // what the listing printed in place of a list (the overlay service going away, say)
    }

    static ChangeResult parseChange(String out) {
        ChangeResult r = new ChangeResult();
        if (out == null) return r;
        int g = out.indexOf(GET_MARK);
        if (g < 0) {
            r.answer = out.trim();
            return r;
        }
        r.answer = out.substring(0, g).trim();
        int start = g + GET_MARK.length();
        int end = out.lastIndexOf(END_MARK);
        if (end >= start) {
            String body = out.substring(start, end);
            List<Overlay> all = parseList(body);
            // a listing that printed an error, or nothing, is not a list of no overlays: where the overlay stands is not known
            if (all.isEmpty() && (body.trim().isEmpty() || looksLikeFailure(body))) r.listError = body.trim();
            else r.all = all;
        }
        return r;
    }

    static final class Verdict {
        boolean ok;
        boolean unknown;          // the list never came (the link dropped, say), so what became of the change is not known
        String error = "";
        int state = -2;           // what the list says the overlay is now: 1, 0, -1, or -2 when it is not listed / the list never came
    }

    /** Android 12 and newer report a refused change as a SecurityException that says only "commit failed" (and exit with 0). */
    static boolean commitFailed(String text) {
        return text != null && text.toLowerCase(Locale.US).contains("commit failed");
    }

    static final String NO_REASON = "Android refused the change and gave no reason";

    /** Whether an enable or disable worked: the overlay is listed, and in the state that was asked for. */
    static Verdict judge(String op, String id, ChangeResult r) {
        Verdict v = new Verdict();
        if (r.all == null) {
            v.unknown = true;
            String why = r.listError.isEmpty() ? r.answer : r.listError;
            v.error = why.isEmpty() ? "No answer from the device" : summary(why);
            return v;
        }
        Overlay found = null;
        for (Overlay o : r.all) {
            if (o.id.equals(id)) {
                found = o;
                break;
            }
        }
        if (found == null) {
            v.error = commitFailed(r.answer) ? NO_REASON : looksLikeFailure(r.answer) ? summary(r.answer) : "That overlay is not listed any more";
            return v;
        }
        v.state = found.state;
        int want = "enable".equals(op) ? 1 : 0;
        v.ok = found.state == want;
        if (!v.ok) {
            if (commitFailed(r.answer)) v.error = NO_REASON;
            else if (looksLikeFailure(r.answer)) v.error = summary(r.answer);
            else if (found.state < 0) v.error = "Android lists this overlay as unavailable, so it cannot be switched";
            else v.error = "enable".equals(op) ? "Android did not switch it on" : "Android did not switch it off (some overlays are fixed on)";
        }
        return v;
    }

    // ---- Material You theme ----

    static final String K_PALETTE = "android.theme.customization.system_palette";
    static final String K_SOURCE = "android.theme.customization.color_source";
    static final String K_STYLE = "android.theme.customization.theme_style";
    static final String K_STAMP = "_applied_timestamp";
    /** Never written, and cleared by a colour pick: an accent or colour index left over from an earlier pick would go on winning over the new palette. */
    static final String K_ACCENT = "android.theme.customization.accent_color";
    static final String K_INDEX = "android.theme.customization.color_index";

    /** The settings tables Samsung's wallpaper-colours switch is looked for in. */
    static final String[] FLAG_TABLES = {"system", "secure", "global"};

    static boolean isStyle(String style) {
        if (style == null) return false;
        for (String s : STYLES) if (s.equals(style)) return true;
        return false;
    }

    /**
     * A colour as the six upper-case hex digits the theme setting wants, or null when it is not one. Accepts RRGGBB, AARRGGBB
     * (the transparency is ignored) and RGB, with or without a leading #.
     */
    static String normalizeHex(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.isEmpty() || !HEX.matcher(t).matches()) return null;
        if (t.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) sb.append(t.charAt(i)).append(t.charAt(i));
            t = sb.toString();
        } else if (t.length() == 8) {
            t = t.substring(2);
        } else if (t.length() != 6) {
            return null;
        }
        return t.toUpperCase(Locale.US);
    }

    /**
     * The setting's value for a colour source and style, in the form Android's own theme picker writes. {@code source} is
     * {@link #SOURCE_PRESET} (with a colour) or {@link #SOURCE_WALLPAPER}. Throws IllegalArgumentException for anything else,
     * so nothing but these few fixed shapes is ever written.
     */
    static String themeValue(String source, String hex, String style, long nowMs) {
        String st = style == null || style.isEmpty() ? DEFAULT_STYLE : style;
        if (!isStyle(st)) throw new IllegalArgumentException("Unknown theme style");
        StringBuilder sb = new StringBuilder("{");
        if (SOURCE_PRESET.equals(source)) {
            String c = normalizeHex(hex);
            if (c == null) throw new IllegalArgumentException("That is not a colour (use six hex digits, like 6750A4)");
            sb.append("\"android.theme.customization.system_palette\":\"").append(c).append("\",");
            sb.append("\"android.theme.customization.color_source\":\"preset\",");
        } else if (SOURCE_WALLPAPER.equals(source)) {
            sb.append("\"android.theme.customization.color_source\":\"home_wallpaper\",");
        } else {
            throw new IllegalArgumentException("Unknown colour source");
        }
        sb.append("\"android.theme.customization.theme_style\":\"").append(st).append("\",");
        sb.append("\"_applied_timestamp\":").append(nowMs).append("}");
        return sb.toString();
    }

    private static boolean ownsKey(String k) {
        return K_PALETTE.equals(k) || K_SOURCE.equals(k) || K_STYLE.equals(k) || K_STAMP.equals(k) || K_ACCENT.equals(k) || K_INDEX.equals(k);
    }

    /**
     * The value to write for a colour source and style when the setting already holds {@code current}: the same members as
     * {@link #themeValue(String, String, String, long)}, with every other member (fonts, icon shapes, icon packs ...) kept as it
     * was, because Android switches off whatever a theme setting does not mention. When {@code current} is not a flat JSON object,
     * or the result would be too long, it is the plain value.
     */
    static String themeValue(String source, String hex, String style, long nowMs, String current) {
        String fresh = themeValue(source, hex, style, nowMs);
        Map<String, String> old = parseFlatObject(current);
        if (old == null) return fresh;
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : old.entrySet()) {
            if (ownsKey(e.getKey())) continue;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue()).append(',');
        }
        if (sb.length() == 1) return fresh;
        sb.append(fresh, 1, fresh.length());
        return sb.length() > MAX_THEME_VALUE ? fresh : sb.toString();
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            i++;
        }
        return i;
    }

    private static boolean isDigit(String s, int i) {
        return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** The index just past the JSON string, number, true, false or null that starts at i, or -1 (an object, an array or anything malformed). */
    private static int valueEnd(String s, int i) {
        int n = s.length();
        if (i >= n) return -1;
        char c = s.charAt(i);
        if (c == '"') {
            for (int j = i + 1; j < n; j++) {
                char d = s.charAt(j);
                if (d == '"') return j + 1;
                if (d < 0x20) return -1;
                if (d != '\\') continue;
                if (++j >= n) return -1;
                char e = s.charAt(j);
                if (e == 'u') {
                    if (j + 4 >= n) return -1;
                    for (int k = 1; k <= 4; k++) if (!isHexDigit(s.charAt(j + k))) return -1;
                    j += 4;
                } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                    return -1;
                }
            }
            return -1;
        }
        if (c == '-' || isDigit(s, i)) {
            int j = c == '-' ? i + 1 : i;
            if (!isDigit(s, j)) return -1;
            if (s.charAt(j) == '0') j++;
            else while (isDigit(s, j)) j++;
            if (j < n && s.charAt(j) == '.') {
                int f = ++j;
                while (isDigit(s, j)) j++;
                if (j == f) return -1;
            }
            if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                j++;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                int f = j;
                while (isDigit(s, j)) j++;
                if (j == f) return -1;
            }
            // Android's org.json refuses a number that does not fit a double (1E400 ...), and then ignores the whole setting
            try {
                if (Double.isInfinite(Double.parseDouble(s.substring(i, j)))) return -1;
            } catch (NumberFormatException e) {
                return -1;
            }
            return j;
        }
        if (s.startsWith("true", i)) return i + 4;
        if (s.startsWith("false", i)) return i + 5;
        if (s.startsWith("null", i)) return i + 4;
        return -1;
    }

    /**
     * The members of a flat JSON object as name -> value text exactly as it was written (so nothing has to be escaped again), in
     * their order. Null when the text is anything else: nested objects or arrays, malformed JSON, a name with an escape in it, a
     * name used twice, more than 64 members, or over {@link #MAX_THEME_VALUE} characters.
     */
    static Map<String, String> parseFlatObject(String s) {
        if (s == null || s.length() > MAX_THEME_VALUE) return null;
        int n = s.length();
        int i = skipWs(s, 0);
        if (i >= n || s.charAt(i) != '{') return null;
        Map<String, String> m = new LinkedHashMap<String, String>();
        i = skipWs(s, i + 1);
        if (i < n && s.charAt(i) == '}') return skipWs(s, i + 1) == n ? m : null;
        while (true) {
            if (i >= n || s.charAt(i) != '"') return null;
            int ke = i + 1;
            while (ke < n && s.charAt(ke) != '"') {
                char c = s.charAt(ke);
                if (c == '\\' || c < 0x20) return null;
                ke++;
            }
            if (ke >= n) return null;
            String key = s.substring(i + 1, ke);
            i = skipWs(s, ke + 1);
            if (i >= n || s.charAt(i) != ':') return null;
            i = skipWs(s, i + 1);
            int ve = valueEnd(s, i);
            if (ve < 0 || m.containsKey(key) || m.size() >= 64) return null;
            m.put(key, s.substring(i, ve));
            i = skipWs(s, ve);
            if (i >= n) return null;
            char c = s.charAt(i);
            if (c == '}') return skipWs(s, i + 1) == n ? m : null;
            if (c != ',') return null;
            i = skipWs(s, i + 1);
        }
    }

    /** Reads the theme setting on its own (for the merge): a marker, what it holds, the closing marker. Anything printed before the first marker is not the value. */
    static String themeReadScript() {
        return "echo " + GET_MARK + "; settings get secure " + THEME_KEY + "; echo " + END_MARK;
    }

    /** What the setting holds ("null" when it is not there), or null when the answer was cut short. */
    static String parseThemeRead(String out) {
        if (out == null) return null;
        int g = out.indexOf(GET_MARK);
        if (g < 0) return null;
        int start = g + GET_MARK.length();
        if (start < out.length() && out.charAt(start) == '\r') start++;
        if (start < out.length() && out.charAt(start) == '\n') start++;
        int end = out.lastIndexOf(END_MARK);
        if (end < start) return null;
        String body = out.substring(start, end);
        if (body.endsWith("\r\n")) body = body.substring(0, body.length() - 2);
        else if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        return body;
    }

    /** Null when what the read printed can be taken for the setting's value (a JSON object, "null" or nothing), else the error it printed. */
    static String themeReadProblem(String read) {
        if (read == null) return "No answer from the device";
        String t = read.trim();
        if (t.isEmpty() || t.equals("null") || t.startsWith("{")) return null;
        return looksLikeFailure(t) ? summary(t) : null;
    }

    /** Null when a stored theme value can be written back (an earlier value being restored, or empty for the default), else why not. */
    static String themeValueProblem(String value) {
        if (value == null) return "No value";
        if (value.length() > MAX_THEME_VALUE) return "The theme value is too long";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) return "A value can't contain a NUL character";
            if (c < 0x20 && c != '\t' && c != '\n') return "A value can't contain control characters";
        }
        return null;
    }

    // ---- Samsung's wallpaper-colours switch ----

    /**
     * What the Samsung "wallpaper colours" switch should be set to for a theme value: 0 for a chosen colour, 1 for the
     * wallpaper, and -1 (leave it alone) for anything else, the default included (the Tasker project this tab grew from does the same).
     */
    static int flagFor(String value) {
        if (value == null) return -1;
        Matcher m = COLOR_SOURCE.matcher(value);
        if (!m.find()) return -1;
        return SOURCE_PRESET.equals(m.group(1)) ? 0 : SOURCE_WALLPAPER.equals(m.group(1)) ? 1 : -1;
    }

    private static int tableIndex(String t) {
        for (int i = 0; i < FLAG_TABLES.length; i++) if (FLAG_TABLES[i].equals(t)) return i;
        return -1;
    }

    static boolean flagValueOk(String v) {
        if (v == null || v.isEmpty() || v.length() > 3) return false;
        for (int i = 0; i < v.length(); i++) if (v.charAt(i) < '0' || v.charAt(i) > '9') return false;
        return true;
    }

    /** The values wanted for the switch in the three tables (indexes as FLAG_TABLES); -1 leaves a table alone. */
    static int[] flagPlanFor(String themeValue) {
        int f = flagFor(themeValue);
        return f < 0 ? new int[] {-1, -1, -1} : new int[] {f, f, f};
    }

    /** The same for a colour source that was chosen (not read out of a value): 0 for a colour, 1 for the wallpaper. */
    static int[] flagPlanForSource(String source) {
        int f = SOURCE_PRESET.equals(source) ? 0 : SOURCE_WALLPAPER.equals(source) ? 1 : -1;
        return f < 0 ? new int[] {-1, -1, -1} : new int[] {f, f, f};
    }

    /** The plan that puts back what an earlier change moved: {table, value} pairs as {@link #flagSnapshotRead} gives them. */
    static int[] flagPlanFromWas(List<String[]> was) {
        int[] plan = {-1, -1, -1};
        if (was != null) {
            for (String[] w : was) {
                int i = w.length == 2 ? tableIndex(w[0]) : -1;
                if (i >= 0 && flagValueOk(w[1])) plan[i] = Integer.parseInt(w[1]);
            }
        }
        return plan;
    }

    /** What the script found in one table's switch, and what it held after the script had set it. */
    static final class FlagChange {
        final String table;
        final String was;
        final String now;

        FlagChange(String table, String was, String now) {
            this.table = table;
            this.was = was;
            this.now = now;
        }
    }

    static String themeWriteScript(String value) {
        return themeWriteScript(value, flagPlanFor(value));
    }

    /**
     * Writes the theme value and reads it back. Where the switch for wallpaper colours exists (a table in which it already holds a
     * number) it is set first, the way the Tasker project did, and read back too; the line it prints says what the switch held
     * before and after. Everything the value is made of is single-quoted.
     */
    static String themeWriteScript(String value, int[] plan) {
        String p = themeValueProblem(value);
        if (p != null) throw new IllegalArgumentException(p);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; plan != null && i < FLAG_TABLES.length && i < plan.length; i++) {
            if (plan[i] < 0 || plan[i] > 999) continue;
            String t = FLAG_TABLES[i];
            String get = "settings get " + t + " " + FLAG_KEY + " 2>/dev/null";
            sb.append("v=$(").append(get).append("); case \"$v\" in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) settings put ")
                    .append(t).append(' ').append(FLAG_KEY).append(' ').append(plan[i]).append("; n=$(").append(get).append("); echo \"")
                    .append(FLAG_MARK).append(' ').append(t).append(" $v $n\";; esac; ");
        }
        sb.append("settings put secure ").append(THEME_KEY).append(' ').append(BackupScripts.quote(value));
        sb.append("; echo ").append(GET_MARK).append("; settings get secure ").append(THEME_KEY).append("; echo ").append(END_MARK);
        return sb.toString();
    }

    /** Puts switches back to what they held (after a change that was refused); each prints what it holds afterwards, like the write does. */
    static String flagRestoreScript(List<FlagChange> moved) {
        StringBuilder sb = new StringBuilder();
        for (FlagChange c : moved) {
            if (tableIndex(c.table) < 0 || !flagValueOk(c.was)) continue;
            sb.append("settings put ").append(c.table).append(' ').append(FLAG_KEY).append(' ').append(c.was).append("; n=$(settings get ")
                    .append(c.table).append(' ').append(FLAG_KEY).append(" 2>/dev/null); echo \"").append(FLAG_MARK).append(' ').append(c.table)
                    .append(' ').append(c.was).append(" $n\"; ");
        }
        return sb.append("echo ").append(END_MARK).toString();
    }

    /** The tables whose switch is still not what it was after {@link #flagRestoreScript} ran (all of them when it printed nothing). */
    static List<String> flagRestoreFailed(List<FlagChange> moved, String out) {
        List<String> failed = new ArrayList<String>();
        ThemeResult r = parseTheme(out);
        for (FlagChange c : moved) {
            boolean ok = false;
            for (FlagChange f : r.flags) if (f.table.equals(c.table) && f.now.equals(c.was)) ok = true;
            if (!ok) failed.add(c.table);
        }
        return failed;
    }

    static final class FlagReport {
        final List<String> set = new ArrayList<String>();               // the tables whose switch now holds what was wanted
        final List<FlagChange> moved = new ArrayList<FlagChange>();     // ... of which the ones that held something else before
        final List<String> failed = new ArrayList<String>();            // the switch would not take the value
    }

    /** What became of the switches the plan asked for. */
    static FlagReport flagReport(int[] plan, ThemeResult r) {
        FlagReport fr = new FlagReport();
        for (FlagChange c : r.flags) {
            int i = tableIndex(c.table);
            if (i < 0 || plan == null || i >= plan.length || plan[i] < 0) continue;
            String want = String.valueOf(plan[i]);
            if (!want.equals(c.now)) {
                fr.failed.add(c.table);
            } else {
                fr.set.add(c.table);
                if (!want.equals(c.was)) fr.moved.add(c);
            }
        }
        return fr;
    }

    /** Empty when every switch took its value, else a sentence for the page. */
    static String flagWarning(FlagReport fr) {
        if (fr.failed.isEmpty()) return "";
        return "Applied, but the wallpaper-colors switch (" + joinTables(fr.failed) + ") would not change, so the phone's own palette may still win.";
    }

    /**
     * What an Undo needs to put the switches back: "<time>|table=value,table=value" for the ones a change moved, and "<time>|-"
     * when it moved none (so its Undo moves none either, instead of guessing from the value it restores).
     */
    static String flagSnapshot(long nowMs, List<FlagChange> moved) {
        StringBuilder sb = new StringBuilder();
        for (FlagChange c : moved) {
            if (tableIndex(c.table) < 0 || !flagValueOk(c.was)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(c.table).append('=').append(c.was);
        }
        return nowMs + "|" + (sb.length() == 0 ? "-" : sb.toString());
    }

    /** The {table, value} pairs of a snapshot that is well formed and not older than {@code maxAgeMs} (none at all for "-"); null otherwise. */
    static List<String[]> flagSnapshotRead(String s, long nowMs, long maxAgeMs) {
        if (s == null) return null;
        int bar = s.indexOf('|');
        if (bar <= 0) return null;
        long t;
        try {
            t = Long.parseLong(s.substring(0, bar));
        } catch (NumberFormatException e) {
            return null;
        }
        if (t > nowMs + 60000L || nowMs - t > maxAgeMs) return null;
        List<String[]> out = new ArrayList<String[]>();
        if ("-".equals(s.substring(bar + 1))) return out;
        for (String part : s.substring(bar + 1).split(",", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) return null;
            String table = part.substring(0, eq);
            String value = part.substring(eq + 1);
            if (tableIndex(table) < 0 || !flagValueOk(value)) return null;
            out.add(new String[] {table, value});
        }
        return out.isEmpty() ? null : out;
    }

    // ---- reading what the theme write printed ----

    static final class ThemeResult {
        String answer = "";                                   // what the writes printed (an error text, or nothing)
        String value = null;                                  // what the setting holds now ("null" when it is not there); null when the read-back never came
        List<FlagChange> flags = new ArrayList<FlagChange>(); // the wallpaper-colour switches the script found, as it left them
    }

    static ThemeResult parseTheme(String out) {
        ThemeResult r = new ThemeResult();
        if (out == null) return r;
        int g = out.indexOf(GET_MARK);
        String head = g < 0 ? out : out.substring(0, g);
        StringBuilder answer = new StringBuilder();
        for (String line : head.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith(FLAG_MARK)) {
                String[] tok = t.substring(FLAG_MARK.length()).trim().split("\\s+");
                if (tok.length >= 2 && tableIndex(tok[0]) >= 0) r.flags.add(new FlagChange(tok[0], tok[1], tok.length > 2 ? tok[2] : ""));
            } else if (!t.isEmpty()) {
                if (answer.length() > 0) answer.append('\n');
                answer.append(t);
            }
        }
        r.answer = answer.toString();
        if (g < 0) return r;
        int start = g + GET_MARK.length();
        if (start < out.length() && out.charAt(start) == '\r') start++;
        if (start < out.length() && out.charAt(start) == '\n') start++;
        int end = out.lastIndexOf(END_MARK);
        if (end >= start) {
            String body = out.substring(start, end);
            if (body.endsWith("\r\n")) body = body.substring(0, body.length() - 2);
            else if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            r.value = body;
        }
        return r;
    }

    /** Whether the setting holds what was asked for. Clearing the theme counts as done whether Android now says nothing or "null". */
    static Verdict judgeTheme(String requested, ThemeResult r) {
        Verdict v = new Verdict();
        if (r.value == null) {
            v.unknown = true;
            v.error = r.answer.isEmpty() ? "No answer from the device" : summary(r.answer);
            return v;
        }
        if (requested.isEmpty()) v.ok = r.value.isEmpty() || "null".equals(r.value);
        else v.ok = themeMatches(requested, r.value);
        if (!v.ok) v.error = looksLikeFailure(r.answer) ? summary(r.answer) : "Android did not keep the new theme";
        return v;
    }

    /**
     * Whether the setting now holds what was asked for: the very same text, or, for a flat JSON object, every member that was asked
     * for with the same value, whatever the order, the spacing, the time stamp, the members added since, or the transparency digits
     * of the palette colour (the system may rewrite the value as soon as it has read it).
     */
    static boolean themeMatches(String requested, String actual) {
        if (requested == null || actual == null) return false;
        if (requested.equals(actual)) return true;
        Map<String, String> want = parseFlatObject(requested);
        Map<String, String> got = parseFlatObject(actual);
        if (want == null || got == null || want.isEmpty()) return false;
        for (Map.Entry<String, String> e : want.entrySet()) {
            if (K_STAMP.equals(e.getKey())) continue;
            String g = got.get(e.getKey());
            if (g == null) return false;
            if (K_PALETTE.equals(e.getKey()) ? !samePalette(e.getValue(), g) : !e.getValue().equals(g)) return false;
        }
        return true;
    }

    /** Two palette members as JSON string text ("7E57C2", "FF7E57C2"): the same colour, with or without the transparency digits. */
    private static boolean samePalette(String a, String b) {
        String x = paletteDigits(a);
        String y = paletteDigits(b);
        return x != null && x.equalsIgnoreCase(y);
    }

    private static String paletteDigits(String jsonString) {
        if (jsonString == null || jsonString.length() < 2 || jsonString.charAt(0) != '"' || jsonString.charAt(jsonString.length() - 1) != '"') return null;
        String t = jsonString.substring(1, jsonString.length() - 1);
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6 && t.length() != 8) return null;
        for (int i = 0; i < t.length(); i++) if (!isHexDigit(t.charAt(i))) return null;
        return t.substring(t.length() - 6);
    }

    // ---- one theme change, start to finish ----

    /** How long after a change its Undo may still put Samsung's wallpaper-colours switch back. */
    static final long FLAG_SNAPSHOT_MAX_AGE_MS = 30L * 60L * 1000L;

    /** What a theme change needs from the app: a shell that runs a script, somewhere to keep what an Undo needs, and the clock. */
    interface ThemeEnv {
        String run(String script, int timeoutMs);

        String snapshot();

        void saveSnapshot(String snapshot);

        long now();
    }

    /** What became of a theme change. */
    static final class ThemeOutcome {
        boolean ok;
        boolean unknown;                 // no read-back came, so what became of the change is not known
        boolean flagsRestored;           // the change was refused after a switch was moved, and the switch was put back
        String error = "";
        String advice = "";
        String answer = "";
        String warning = "";
        String value = null;             // what the setting holds now ("null" when it is not there)
        String before = null;            // what it held when the change began (apply only)
        List<String> flags = new ArrayList<String>();   // the tables whose switch now holds what was wanted
    }

    /**
     * Runs one theme change. {@code kind}: "apply" (the source, colour and style are merged into what the setting holds now),
     * "reset" (the empty default), "restore" (an earlier value written back) or "undo" (a restore that also puts the switches back
     * to what the change being undone found). A change that is refused after it moved a switch puts the switch back.
     */
    static ThemeOutcome runTheme(ThemeEnv env, String kind, String source, String hex, String style, String raw) {
        ThemeOutcome o = new ThemeOutcome();
        String value = raw;
        String cur = null;                       // what the setting held before (apply reads it first)
        if ("apply".equals(kind)) {
            String readOut = env.run(themeReadScript(), 20000);
            cur = parseThemeRead(readOut);
            String bad;
            if (cur != null) bad = themeReadProblem(cur);
            else bad = readOut == null || readOut.trim().isEmpty() ? "No answer from the device" : summary(readOut);
            if (bad != null) {
                o.error = "Could not read the current theme first: " + bad;      // nothing was written
                o.advice = advice(readOut);
                return o;
            }
            o.before = cur;
            value = themeValue(source, hex, style, env.now(), cur);
        }
        int[] plan = "apply".equals(kind) ? flagPlanForSource(source) : flagPlanFor(value);
        if ("undo".equals(kind)) {
            List<String[]> was = flagSnapshotRead(env.snapshot(), env.now(), FLAG_SNAPSHOT_MAX_AGE_MS);
            if (was != null) plan = flagPlanFromWas(was);
        }
        ThemeResult tr = parseTheme(env.run(themeWriteScript(value, plan), 30000));
        Verdict v = judgeTheme(value, tr);
        FlagReport fr = flagReport(plan, tr);
        o.ok = v.ok;
        o.unknown = v.unknown;
        o.value = tr.value;
        o.answer = tr.answer;
        o.flags = fr.set;
        if (v.ok) {
            // what this change moved (or that it moved nothing) is what its Undo puts back; an Undo uses its snapshot up
            env.saveSnapshot("undo".equals(kind) ? "" : flagSnapshot(env.now(), fr.moved));
            o.warning = flagWarning(fr);
        } else {
            o.error = v.error;
            o.advice = advice(tr.answer);
            if (v.unknown) {
                // nobody knows whether the theme went in; an Undo that follows must not use the snapshot of an earlier change
                if (!"undo".equals(kind)) env.saveSnapshot(flagSnapshot(env.now(), fr.moved));
            } else if (!fr.moved.isEmpty() && (cur == null || themeMatches(cur, tr.value))) {
                // refused (the setting still holds what it held) after a switch was moved: put the switch back, and check it went
                String out = "";
                try {
                    out = env.run(flagRestoreScript(fr.moved), 15000);
                } catch (RuntimeException ignored) {
                    // the switch stays where it is; the refusal is still what the page is told
                }
                List<String> stuck = flagRestoreFailed(fr.moved, out);
                o.flagsRestored = stuck.isEmpty();
                if (!stuck.isEmpty()) o.warning = "The wallpaper-colors switch (" + joinTables(stuck) + ") could not be put back to what it was.";
            }
        }
        return o;
    }

    private static String joinTables(List<String> tables) {
        StringBuilder t = new StringBuilder();
        for (String f : tables) {
            if (t.length() > 0) t.append(", ");
            t.append(f);
        }
        return t.toString();
    }

    // ---- words for failures ----

    static boolean looksLikeFailure(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String a = text.toLowerCase(Locale.US);
        return SettingsDb.looksLikeFailure(text) || a.contains("overlay manager") || a.contains("not found") || a.contains("no such")
                || a.contains("unknown option");
    }

    static String summary(String text) {
        return SettingsDb.summary(text);
    }

    /** A plain-words hint for the usual reasons a change is refused; empty when there is nothing useful to add. */
    static String advice(String text) {
        if (text == null) return "";
        String a = text.toLowerCase(Locale.US);
        if (FileRules.transportLost(text)) return "The connection to the device was lost. Reconnect in Working Modes, then try again.";
        if (a.contains("can't find service") || a.contains("cmd: failure")) {
            return "This phone has no overlay manager to talk to (or it is not answering). Try again in a moment, or restart the phone.";
        }
        if (a.contains("commit failed")) {
            return "Android gives no reason for this. Overlays the system keeps fixed in place, or that belong to another app, cannot be switched from here.";
        }
        if (a.contains("securityexception") || a.contains("permission denial") || a.contains("permission denied") || a.contains("write_secure_settings")) {
            return "Android refused the request. Some phones (Xiaomi, Redmi, POCO) only allow it once \"USB debugging (Security settings)\" is on in Developer options.";
        }
        if (a.contains("not found") || a.contains("no such")) return "Android does not know that overlay any more. Reload the list.";
        if (a.contains("unknown command") || a.contains("overlay manager")) return "This Android version did not understand the command.";
        return "";
    }
}
