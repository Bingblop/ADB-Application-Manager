package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The System UI Tuner tab's rules, free of Android classes so they can be tested off the device: the shell commands the page runs
 * through the working mode (Demo Mode, shade and status-bar actions, test notifications, system actions, battery simulator,
 * navigation mode, display and window settings, info dumps) and the readers for what those commands print.
 *
 * <p>Like {@link SettingsDb} every command is built here and nowhere else: each enum is a whitelist, each number is range-checked,
 * each free text (notification title / text / tag, Wi-Fi name, carrier) is length-capped, refused when it holds a control
 * character and single-quoted with {@link BackupScripts#quote}, so nothing typed can end up as shell code. A bad value throws
 * IllegalArgumentException with a plain sentence. Nothing here writes a Global / Secure / System setting, except
 * {@link #demoAllowCommands} (Demo Mode's own switch, which System UI insists on).
 */
public final class SysUiRules {

    private SysUiRules() {}

    public static final int MAX_TEXT = 500;          // safeForShell and notification text
    public static final int MAX_TITLE = 100;
    public static final int MAX_TAG = 64;
    public static final int MAX_LINE = 200;          // one inbox line
    public static final int MAX_LINES = 8;
    public static final int MAX_MESSAGE = 300;       // one chat message ("Name:text")
    public static final int MAX_MESSAGES = 10;
    public static final int MAX_WHO = 40;
    public static final int MAX_SSID = 32;
    public static final int MAX_CARRIER = 40;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern DIGITS = Pattern.compile("-?\\d{1,9}");

    // ---- shared helpers ----

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(msg);
    }

    /** A value as it may appear in an error sentence: control characters gone, cut short. */
    private static String shown(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length() && i < 40; i++) {
            char c = v.charAt(i);
            sb.append(Character.isISOControl(c) ? '?' : c);
        }
        return v.length() > 40 ? sb + "..." : sb.toString();
    }

    private static boolean has(String[] set, String v) {
        if (v == null) return false;
        for (String s : set) if (s.equals(v)) return true;
        return false;
    }

    private static String join(String[] set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String oneOf(String what, String v, String... allowed) {
        if (v == null) throw bad("No " + what + " given");
        if (!has(allowed, v)) throw bad("Unknown " + what + " \"" + shown(v) + "\" (use " + join(allowed) + ")");
        return v;
    }

    private static int inRange(String what, int v, int lo, int hi) {
        if (v < lo || v > hi) throw bad(what + " must be from " + lo + " to " + hi);
        return v;
    }

    /** A whole number given as text, in range. */
    private static int number(String what, String v, int lo, int hi) {
        if (v == null) throw bad("No " + what + " given");
        if (!DIGITS.matcher(v).matches()) throw bad(what + " must be a whole number from " + lo + " to " + hi);
        return inRange(what, Integer.parseInt(v), lo, hi);
    }

    /** One free text, checked and single-quoted: refused when null, empty (if required), too long, or holding a control character or line break. */
    private static String text(String what, String s, int max, boolean required) {
        if (s == null) throw bad(what + " is missing");
        if (required && s.isEmpty()) throw bad(what + " can't be empty");
        if (s.length() > max) throw bad(what + " is too long (" + max + " characters at most)");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == ' ' || c == ' ') throw bad(what + " can't contain control characters or line breaks");
        }
        return BackupScripts.quote(s);
    }

    /** The text, validated and single-quoted for sh (null, control characters and over MAX_TEXT characters are refused). Every free text of every command goes through this or {@link #text}. */
    public static String safeForShell(String s) {
        return text("The text", s, MAX_TEXT, false);
    }

    /** " -e key token": the token is whitelisted by the caller and is checked once more so a bad entry in a list can never reach the shell bare. */
    private static String ex(String key, String token) {
        if (!TOKEN.matcher(token).matches()) throw bad("Unsafe value");
        return " -e " + key + " " + token;
    }

    // ---- Demo Mode ----

    public static final String SETTING_DEMO_ALLOWED = "sysui_demo_allowed";
    private static final String AM_DEMO = "am broadcast -a com.android.systemui.demo -e command ";

    private static final String[] SHOW_HIDE = {"show", "hide"};
    private static final String[] TRUE_FALSE = {"true", "false"};
    private static final String[] ACTIVITY = {"none", "in", "out", "inout"};
    public static final String[] BAR_MODES = {"opaque", "semi-transparent", "translucent", "transparent", "warning"};
    public static final String[] DATA_TYPES = {"none", "1x", "3g", "4g", "4g+", "5g", "5ge", "5g+", "e", "g", "h", "h+", "lte", "lte+", "dis", "not", "roam"};
    private static final String[] VOLUME = {"hide", "vibrate"};
    private static final String[] BLUETOOTH = {"hide", "connected"};
    private static final String[] ZEN = {"hide", "dnd", "important", "none"};

    /** The state keys demoApply understands: "&lt;command&gt;.&lt;extra&gt;", as in the protocol. */
    public static final String[] DEMO_KEYS = {
        "clock.hhmm", "battery.level", "battery.plugged", "battery.powersave", "bars.mode", "notifications.visible",
        "status.volume", "status.bluetooth", "status.location", "status.alarm", "status.tty", "status.mute", "status.speakerphone",
        "status.zen", "status.cast", "status.hotspot", "status.airplane", "network.sims", "network.nosim",
        "wifi.show", "wifi.level", "wifi.fully", "wifi.activity", "wifi.ssid",
        "mobile.show", "mobile.level", "mobile.fully", "mobile.datatype", "mobile.roam", "mobile.carriernetworkchange", "mobile.inflate",
        "mobile.activity", "mobile.networkname"};
    /** Rows Tweaker offers that no System UI listens to (AOSP DemoStatusIcons has no handler for them): accepted and left out. */
    private static final String[] DEMO_NO_HANDLER = {"status.sync", "status.eri", "status.secure"};
    private static final String[] STATUS_ICONS = {"location", "alarm", "tty", "mute", "speakerphone", "cast", "hotspot"};

    /**
     * The command that lets System UI obey demo commands. Allowing is the one settings write of this tab ({@code settings put global
     * sysui_demo_allowed 1}); disallowing deletes the key again, which puts the phone back to its default.
     */
    public static String demoAllowCommands(boolean allow) {
        return allow ? "settings put global " + SETTING_DEMO_ALLOWED + " 1" : "settings delete global " + SETTING_DEMO_ALLOWED;
    }

    public static String demoAllowedRead() {
        return "settings get global " + SETTING_DEMO_ALLOWED;
    }

    /** TRUE when the answer to {@link #demoAllowedRead} is a number other than 0, FALSE for 0 or null (not set), null when it is not an answer (an error, nothing). */
    public static Boolean parseDemoAllowed(String out) {
        if (out == null || out.trim().isEmpty()) return null;
        String[] lines = out.trim().split("\r?\n");
        String last = lines[lines.length - 1].trim();       // a warning from adb may come first; the answer is the last line
        if (last.equals("null")) return Boolean.FALSE;
        if (!DIGITS.matcher(last).matches()) return null;
        return Boolean.valueOf(Integer.parseInt(last) != 0);
    }

    public static String demoEnter() {
        return AM_DEMO + "enter";
    }

    public static String demoExit() {
        return AM_DEMO + "exit";
    }

    private static String get(Map<String, String> m, String k) {
        return m.get(k);
    }

    private static void addLine(List<String> out, String command, StringBuilder extras) {
        if (extras.length() > 0) out.add(AM_DEMO + command + extras);
    }

    /** 1200, 12:00, 930 and 9:30 as the four digits HHMM the protocol wants. */
    private static String clockValue(String v) {
        Matcher m = Pattern.compile("(\\d{1,2}):?(\\d{2})").matcher(v);
        if (!m.matches()) throw bad("The clock must be a time like 12:30 or 1230");
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) throw bad("The clock must be a time from 00:00 to 23:59");
        return String.format(Locale.US, "%02d%02d", h, min);
    }

    /**
     * The page's demo state as shell lines, one {@code am broadcast} per line (strings only: {@code -e}, never {@code --ei}/{@code --ez}).
     * A key that is missing (or null) sends nothing. Keys are {@link #DEMO_KEYS}; any other key is refused, bar the three that no System UI
     * handles (status.sync, status.eri, status.secure), which are skipped. Wi-Fi and mobile each get a line of their own because both read
     * the same "level" extra. Not part of the lines: enter (any command enters demo mode) and the allow switch.
     */
    public static String demoApply(Map<String, String> state) {
        if (state == null) throw bad("No demo state given");
        for (String k : state.keySet()) {
            if (!has(DEMO_KEYS, k) && !has(DEMO_NO_HANDLER, k)) throw bad("Unknown demo setting \"" + shown(k) + "\"");
        }
        List<String> out = new ArrayList<String>();
        String v;
        StringBuilder x = new StringBuilder();
        if ((v = get(state, "clock.hhmm")) != null) x.append(ex("hhmm", clockValue(v)));
        addLine(out, "clock", x);
        x.setLength(0);
        if ((v = get(state, "battery.level")) != null) x.append(ex("level", String.valueOf(number("The battery level", v, 0, 100))));
        if ((v = get(state, "battery.plugged")) != null) x.append(ex("plugged", oneOf("charging value", v, TRUE_FALSE)));
        if ((v = get(state, "battery.powersave")) != null) x.append(ex("powersave", oneOf("battery saver value", v, TRUE_FALSE)));
        addLine(out, "battery", x);
        x.setLength(0);
        if ((v = get(state, "bars.mode")) != null) x.append(ex("mode", oneOf("bar style", v, BAR_MODES)));
        addLine(out, "bars", x);
        x.setLength(0);
        if ((v = get(state, "notifications.visible")) != null) x.append(ex("visible", oneOf("notification icons value", v, TRUE_FALSE)));
        addLine(out, "notifications", x);
        x.setLength(0);
        if ((v = get(state, "status.volume")) != null) x.append(ex("volume", oneOf("volume icon", v, VOLUME)));
        if ((v = get(state, "status.bluetooth")) != null) x.append(ex("bluetooth", oneOf("Bluetooth icon", v, BLUETOOTH)));
        if ((v = get(state, "status.zen")) != null) x.append(ex("zen", oneOf("Do Not Disturb icon", v, ZEN)));
        for (String icon : STATUS_ICONS) {
            if ((v = get(state, "status." + icon)) != null) x.append(ex(icon, oneOf(icon + " icon", v, SHOW_HIDE)));
        }
        addLine(out, "status", x);
        x.setLength(0);
        if ((v = get(state, "status.airplane")) != null) x.append(ex("airplane", oneOf("airplane icon", v, SHOW_HIDE)));
        if ((v = get(state, "network.sims")) != null) x.append(ex("sims", String.valueOf(number("The number of SIMs", v, 1, 8))));
        if ((v = get(state, "network.nosim")) != null) x.append(ex("nosim", oneOf("no-SIM icon", v, SHOW_HIDE)));
        addLine(out, "network", x);
        x.setLength(0);
        wifiExtras(state, x);
        addLine(out, "network", x);
        x.setLength(0);
        mobileExtras(state, x);
        addLine(out, "network", x);
        StringBuilder sb = new StringBuilder();
        for (String l : out) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    private static void wifiExtras(Map<String, String> s, StringBuilder x) {
        String show = get(s, "wifi.show");
        String lvl = get(s, "wifi.level"), fully = get(s, "wifi.fully"), act = get(s, "wifi.activity"), ssid = get(s, "wifi.ssid");
        // everything is checked, but a hidden Wi-Fi sends only "hide": the details mean nothing then
        String qLvl = lvl == null ? null : String.valueOf(number("The Wi-Fi level", lvl, 0, 4));
        String qFully = fully == null ? null : oneOf("Wi-Fi fully-connected value", fully, TRUE_FALSE);
        String qAct = act == null ? null : oneOf("Wi-Fi activity", act, ACTIVITY);
        String qSsid = ssid == null ? null : text("The Wi-Fi name", ssid, MAX_SSID, true);
        if (show == null) {
            if (lvl != null || fully != null || act != null || ssid != null) throw bad("The Wi-Fi details need the Wi-Fi switch (wifi.show) as well");
            return;
        }
        x.append(ex("wifi", oneOf("Wi-Fi value", show, SHOW_HIDE)));
        if (!"show".equals(show)) return;
        if (qLvl != null) x.append(ex("level", qLvl));
        if (qFully != null) x.append(ex("fully", qFully));
        if (qAct != null) x.append(ex("activity", qAct));
        if (qSsid != null) x.append(" -e ssid ").append(qSsid);
    }

    private static void mobileExtras(Map<String, String> s, StringBuilder x) {
        String show = get(s, "mobile.show");
        String lvl = get(s, "mobile.level"), fully = get(s, "mobile.fully"), type = get(s, "mobile.datatype"), roam = get(s, "mobile.roam");
        String cnc = get(s, "mobile.carriernetworkchange"), infl = get(s, "mobile.inflate"), act = get(s, "mobile.activity"), name = get(s, "mobile.networkname");
        String qLvl = lvl == null ? null : String.valueOf(number("The mobile level", lvl, 0, 4));
        String qFully = fully == null ? null : oneOf("mobile fully-connected value", fully, TRUE_FALSE);
        String qType = type == null ? null : oneOf("mobile data type", type.isEmpty() ? "none" : type, DATA_TYPES);
        String qRoam = roam == null ? null : oneOf("roaming value", roam, SHOW_HIDE);
        String qCnc = cnc == null ? null : oneOf("carrier-network-change value", cnc, SHOW_HIDE);
        String qInfl = infl == null ? null : oneOf("inflate-signal value", infl, TRUE_FALSE);
        String qAct = act == null ? null : oneOf("mobile activity", act, ACTIVITY);
        String qName = name == null ? null : text("The carrier name", name, MAX_CARRIER, true);
        if (show == null) {
            if (lvl != null || fully != null || type != null || roam != null || cnc != null || infl != null || act != null || name != null) {
                throw bad("The mobile details need the mobile switch (mobile.show) as well");
            }
            return;
        }
        x.append(ex("mobile", oneOf("mobile value", show, SHOW_HIDE)));
        if (!"show".equals(show)) return;
        if (qLvl != null) x.append(ex("level", qLvl));
        if (qFully != null) x.append(ex("fully", qFully));
        if (qType != null) x.append(ex("datatype", qType));
        if (qRoam != null) x.append(ex("roam", qRoam));
        if (qCnc != null) x.append(ex("carriernetworkchange", qCnc));
        if (qInfl != null) x.append(ex("inflate", qInfl));
        if (qAct != null) x.append(ex("activity", qAct));
        if (qName != null) x.append(" -e networkname ").append(qName);
    }

    // ---- shade and status-bar flags ----

    public static final String[] SHADE_ACTIONS = {"expand-notifications", "expand-settings", "collapse", "check-support"};

    public static String shadeCommand(String which) {
        return "cmd statusbar " + oneOf("shade action", which, SHADE_ACTIONS);
    }

    /** The flag names of {@code cmd statusbar send-disable-flag}, in the order everything here lists them. */
    public static final String[] DISABLE_FLAGS = {"search", "home", "recents", "notification-alerts", "statusbar-expansion", "system-icons",
        "clock", "notification-icons", "quick-settings"};
    /** What each flag sets in {@code mDisabled1} / {@code mDisabled2} of {@code dumpsys statusbar} (StatusBarManager.DISABLE_* / DISABLE2_*). */
    private static final int[] FLAG_WORD1 = {0x02000000, 0x00200000, 0x01000000, 0x00040000, 0x00010000, 0x00100000, 0x00800000, 0x00020000, 0};
    private static final int[] FLAG_WORD2 = {0, 0, 0, 0, 0, 0, 0, 0, 0x1};
    private static final String[] RISKY_FLAGS = {"home", "recents", "statusbar-expansion", "quick-settings"};

    /** The flags in listing order, without repeats; every name is checked. "none" on its own, or nothing, means no flags. */
    private static List<String> canonicalFlags(List<String> flags) {
        if (flags == null) throw bad("No flags given");
        boolean none = false;
        List<String> seen = new ArrayList<String>();
        for (String f : flags) {
            if ("none".equals(f)) none = true;
            else seen.add(oneOf("status bar flag", f, DISABLE_FLAGS));
        }
        if (none && !seen.isEmpty()) throw bad("\"none\" can't be combined with other flags");
        List<String> res = new ArrayList<String>();
        for (String f : DISABLE_FLAGS) if (seen.contains(f)) res.add(f);
        return res;
    }

    /** Each call replaces the whole set that an earlier call switched off; an empty list (or "none") restores everything. It lives in system_server until a reboot or the next call. */
    public static String disableFlagsCommand(List<String> flags) {
        List<String> c = canonicalFlags(flags);
        StringBuilder sb = new StringBuilder("cmd statusbar send-disable-flag");
        if (c.isEmpty()) return sb.append(" none").toString();
        for (String f : c) sb.append(' ').append(f);
        return sb.toString();
    }

    /** The flags that can leave the navigation or the shade out of reach, for the confirm dialog. */
    public static List<String> disableFlagsRisky(List<String> flags) {
        List<String> res = new ArrayList<String>();
        for (String f : canonicalFlags(flags)) if (has(RISKY_FLAGS, f)) res.add(f);
        return res;
    }

    /** The Android version a flag first exists in: 29 for the first five, 30 for the next three, 36 for quick-settings (that one is unverified on a device). */
    public static int disableFlagMinSdk(String flag) {
        int i = indexOf(DISABLE_FLAGS, oneOf("status bar flag", flag, DISABLE_FLAGS));
        return i < 5 ? 29 : i < 8 ? 30 : 36;
    }

    private static int indexOf(String[] set, String v) {
        for (int i = 0; i < set.length; i++) if (set[i].equals(v)) return i;
        return -1;
    }

    private static final Pattern DISABLED1 = Pattern.compile("mDisabled1?=0x([0-9a-fA-F]{1,8})");
    private static final Pattern DISABLED2 = Pattern.compile("mDisabled2=0x([0-9a-fA-F]{1,8})");
    private static final Pattern WHAT1 = Pattern.compile("\\bwhat1?=0x([0-9a-fA-F]{1,8})");
    private static final Pattern WHAT2 = Pattern.compile("\\bwhat2=0x([0-9a-fA-F]{1,8})");

    private static long hexOf(Pattern p, String s, int from, boolean all) {
        Matcher m = p.matcher(s);
        long v = 0;
        int at = from;
        while (m.find(at)) {
            v |= Long.parseLong(m.group(1), 16);
            at = m.end();
            if (!all) break;
        }
        return v;
    }

    /**
     * The flags that are switched off in the answer of {@code dumpsys statusbar}, in listing order. It reads {@code mDisabled1} /
     * {@code mDisabled2} of the first display (the older single {@code mDisabled} too) and, when the dump has none, the words of the
     * disable records. These words hold everything System UI and the system have switched off, not only this tab's flags. Anything it
     * cannot read gives an empty list.
     */
    public static List<String> parseDisableState(String dump) {
        List<String> res = new ArrayList<String>();
        if (dump == null) return res;
        long w1, w2;
        Matcher m = DISABLED1.matcher(dump);
        if (m.find()) {
            w1 = Long.parseLong(m.group(1), 16);
            w2 = hexOf(DISABLED2, dump, m.end(), false);
        } else {
            w1 = hexOf(WHAT1, dump, 0, true);
            w2 = hexOf(WHAT2, dump, 0, true);
        }
        for (int i = 0; i < DISABLE_FLAGS.length; i++) {
            if ((FLAG_WORD1[i] != 0 && (w1 & FLAG_WORD1[i]) != 0) || (FLAG_WORD2[i] != 0 && (w2 & FLAG_WORD2[i]) != 0)) res.add(DISABLE_FLAGS[i]);
        }
        return res;
    }

    // ---- status-bar icon slots ----

    private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    private static final Pattern ICON_LINE = Pattern.compile("^\\s*([A-Za-z0-9_][A-Za-z0-9_.-]{0,63})\\s+->\\s+StatusBarIcon\\b.*$");

    public static String statusIconSlotsCommand() {
        return "cmd statusbar get-status-icons";
    }

    /** What to run when {@code cmd statusbar get-status-icons} printed nothing usable. */
    public static String statusIconSlotsFallbackCommand() {
        return infoCommand("statusbar");
    }

    /**
     * The slot names: one per line from {@code cmd statusbar get-status-icons} (blank lines, errors and anything with a space dropped), or,
     * when the text is the dump of {@code dumpsys statusbar}, the {@code slot -> StatusBarIcon(...)} lines of its mIcons. No repeats.
     */
    public static List<String> parseStatusIconSlots(String out) {
        List<String> res = new ArrayList<String>();
        if (out == null) return res;
        String[] lines = out.split("\r?\n");
        for (String l : lines) {
            Matcher m = ICON_LINE.matcher(l);
            if (m.matches() && !res.contains(m.group(1))) res.add(m.group(1));
        }
        if (!res.isEmpty()) return res;
        for (String l : lines) {
            String t = l.trim();
            if (SLOT.matcher(t).matches() && !res.contains(t)) res.add(t);
        }
        return res;
    }

    // ---- test notifications ----

    public static final String[] NOTIFY_STYLES = {"basic", "bigtext", "inbox", "messaging"};
    private static final Pattern NOTE_KEY = Pattern.compile("\\d{1,10}\\|[A-Za-z0-9_.]{1,255}\\|-?\\d{1,10}\\|[^\\p{Cntrl}\\u0080-\\u009f\\u2028\\u2029]{0,200}\\|-?\\d{1,10}");
    public static final long SNOOZE_MIN_MS = 1000L;
    public static final long SNOOZE_MAX_MS = 86400000L;

    private static String chatMessage(String m) {
        if (m == null) throw bad("A message is missing");
        int c = m.indexOf(':');
        if (c == 0) throw bad("A message can't start with a colon (write Name:text, or just the text)");
        if (c > MAX_WHO) throw bad("The name in front of a message is too long (" + MAX_WHO + " characters at most)");
        if (c > 0 && c == m.length() - 1) throw bad("A message needs some text after the name");
        return text("A message", m, MAX_MESSAGE, true);
    }

    /**
     * {@code cmd notification post} (Android 10 and newer, as shell or root). {@code style}: basic, bigtext, inbox (needs {@code lines}) or
     * messaging (needs {@code messages} as "Name:text", or just the text; {@code conversation} is optional). Title and conversation may be
     * null or empty; lines, messages and conversation are ignored by the styles they do not belong to.
     */
    public static String notifyPostCommand(String style, String title, String text, String tag, List<String> lines, List<String> messages, String conversation) {
        String st = oneOf("notification style", style, NOTIFY_STYLES);
        StringBuilder sb = new StringBuilder("cmd notification post");
        if (title != null && !title.isEmpty()) sb.append(" -t ").append(text("The title", title, MAX_TITLE, false));
        String qText = text("The text", text, MAX_TEXT, true);
        String qTag = text("The tag", tag, MAX_TAG, true);
        if ("bigtext".equals(st)) {
            sb.append(" -S bigtext");
        } else if ("inbox".equals(st)) {
            if (lines == null || lines.isEmpty()) throw bad("The inbox style needs at least one line");
            if (lines.size() > MAX_LINES) throw bad("The inbox style takes " + MAX_LINES + " lines at most");
            sb.append(" -S inbox");
            for (String l : lines) sb.append(" --line ").append(text("A line", l, MAX_LINE, true));
        } else if ("messaging".equals(st)) {
            if (messages == null || messages.isEmpty()) throw bad("The messaging style needs at least one message");
            if (messages.size() > MAX_MESSAGES) throw bad("The messaging style takes " + MAX_MESSAGES + " messages at most");
            sb.append(" -S messaging");
            if (conversation != null && !conversation.isEmpty()) sb.append(" --conversation ").append(text("The conversation title", conversation, MAX_TITLE, false));
            for (String m : messages) sb.append(" --message ").append(chatMessage(m));
        }
        if (tag.startsWith("-") || text.startsWith("-")) sb.append(" --");       // so a tag or text that starts with a dash is not read as an option
        return sb.append(' ').append(qTag).append(' ').append(qText).toString();
    }

    public static String notifyListCommand() {
        return "cmd notification list";
    }

    /** A key as {@code cmd notification list} prints it: user|package|id|tag|uid. */
    public static boolean notifyKeyValid(String key) {
        return key != null && key.length() <= 400 && NOTE_KEY.matcher(key).matches();
    }

    /** The keys in the answer of {@link #notifyListCommand} (anything that is not a key, like an error text, is dropped), no repeats. */
    public static List<String> parseNotificationKeys(String out) {
        List<String> res = new ArrayList<String>();
        if (out == null) return res;
        for (String l : out.split("\r?\n")) {
            String t = l.trim();
            if (notifyKeyValid(t) && !res.contains(t)) res.add(t);
        }
        return res;
    }

    private static String requireKey(String key) {
        if (!notifyKeyValid(key)) throw bad("That is not a notification key (it looks like 0|com.example.app|1|tag|10123)");
        return BackupScripts.quote(key);
    }

    public static String notifySnoozeCommand(String key, long ms) {
        String q = requireKey(key);
        if (ms < SNOOZE_MIN_MS || ms > SNOOZE_MAX_MS) throw bad("Snooze time must be from 1 second to 24 hours");
        return "cmd notification snooze --for " + ms + " " + q;
    }

    public static String notifyUnsnoozeCommand(String key) {
        return "cmd notification unsnooze " + requireKey(key);
    }

    // ---- system actions ----

    public static final int ACTION_MIN = 1;
    public static final int ACTION_MAX = 22;
    private static final String[] ACTION_LABELS = {null, "Back", "Home", "Recents", "Open notification shade", "Open quick settings", "Power menu",
        "Toggle split screen", "Lock screen", "Take screenshot", "Headset hook", "Accessibility button", "Accessibility button chooser",
        "Accessibility shortcut", "Accessibility all apps", "Dismiss notification shade", "D-pad up", "D-pad down", "D-pad left", "D-pad right",
        "D-pad center", "Menu", "Media play / pause"};
    /** What stands in for each id below Android 11 ({@code cmd accessibility call-system-action} came with 11); null where nothing does. */
    private static final String[] ACTION_FALLBACKS = {null, "input keyevent 4", "input keyevent 3", "input keyevent 187", "cmd statusbar expand-notifications",
        "cmd statusbar expand-settings", "input keyevent --longpress 26", null, "input keyevent 223", "input keyevent 120", "input keyevent 79", null,
        null, null, null, "cmd statusbar collapse", "input keyevent 19", "input keyevent 20", "input keyevent 21", "input keyevent 22",
        "input keyevent 23", "input keyevent 82", "input keyevent 85"};

    private static int actionId(int id) {
        return inRange("The system action", id, ACTION_MIN, ACTION_MAX);
    }

    /** The ids the page can offer, 1 to 22 (the public GLOBAL_ACTION_* numbers). Which of them a phone implements is up to the phone. */
    public static int[] systemActionIds() {
        int[] ids = new int[ACTION_MAX];
        for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        return ids;
    }

    public static String actionLabel(int id) {
        return ACTION_LABELS[actionId(id)];
    }

    /** Runs silently: the command prints nothing, and does nothing for an id the phone does not implement. */
    public static String systemActionCommand(int id) {
        return "cmd accessibility call-system-action " + actionId(id);
    }

    /** The command for an Android older than 11, or null when there is none for that action. */
    public static String systemActionFallback(int id) {
        return ACTION_FALLBACKS[actionId(id)];
    }

    /** Android starts System UI again at once; the bars and shade flicker for a second or two and demo mode ends. */
    public static String restartSystemUiCommand() {
        return "am crash com.android.systemui";
    }

    // ---- battery simulator ----

    public static final String[] BATTERY_KEYS = {"level", "status", "ac", "usb", "wireless", "dock", "temp", "present"};

    /** level 0-100, status 1-5 (1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full), ac / usb / wireless / dock / present 0 or 1, temp in tenths of a degree (-200 to 1000). Freezes the real battery state until reset. */
    public static String batterySetCommand(String key, int value) {
        String k = oneOf("battery value", key, BATTERY_KEYS);
        if (k.equals("level")) inRange("The battery level", value, 0, 100);
        else if (k.equals("status")) inRange("The battery status", value, 1, 5);
        else if (k.equals("temp")) inRange("The battery temperature (tenths of a degree)", value, -200, 1000);
        else inRange("The " + k + " value", value, 0, 1);
        return "dumpsys battery set " + k + " " + value;
    }

    public static String batteryUnplugCommand() {
        return "dumpsys battery unplug";
    }

    public static String batteryResetCommand() {
        return "dumpsys battery reset";
    }

    public static String batteryReadCommand() {
        return "dumpsys battery";
    }

    private static final Pattern BATTERY_LINE = Pattern.compile("^\\s*(AC powered|USB powered|Wireless powered|Dock powered|status|present|level|temperature):\\s*(\\S+)\\s*$");

    /** The numbers in the answer of {@code dumpsys battery}: level, status, ac, usb, wireless, dock (0/1), temp (tenths), present (0/1); what it does not print is missing from the map. */
    public static Map<String, Integer> parseBatteryState(String dump) {
        Map<String, Integer> res = new LinkedHashMap<String, Integer>();
        if (dump == null) return res;
        for (String l : dump.split("\r?\n")) {
            Matcher m = BATTERY_LINE.matcher(l);
            if (!m.matches()) continue;
            String name = m.group(1), v = m.group(2);
            String key = name.equals("AC powered") ? "ac" : name.equals("USB powered") ? "usb" : name.equals("Wireless powered") ? "wireless"
                    : name.equals("Dock powered") ? "dock" : name.equals("temperature") ? "temp" : name;
            Integer val;
            if (v.equals("true")) val = 1;
            else if (v.equals("false")) val = 0;
            else if (DIGITS.matcher(v).matches()) val = Integer.valueOf(v);
            else continue;
            if (!res.containsKey(key)) res.put(key, val);
        }
        return res;
    }

    /** Whether the dump says the battery state is frozen ("(UPDATES STOPPED -- use 'reset' to restart)"). */
    public static boolean isFrozen(String dump) {
        return dump != null && dump.toUpperCase(Locale.US).contains("UPDATES STOPPED");
    }

    // ---- navigation mode ----

    public static final String[] NAV_MODES = {"gestural", "threebutton", "twobutton"};
    private static final String NAV_PREFIX = "com.android.internal.systemui.navbar.";

    public static String navListCommand() {
        return "cmd overlay list --user 0 android";
    }

    /**
     * Which of the three navigation-bar overlays the phone has and their state ("on", "off" or "unavailable"), in the order of
     * {@link #NAV_MODES}, from the answer of {@link #navListCommand}. A phone with none (some makers use their own way) gives an empty map.
     */
    public static Map<String, String> parseNavModes(String out) {
        Map<String, String> found = new LinkedHashMap<String, String>();
        if (out != null) {
            for (String line : out.split("\r?\n")) {
                String t = line.trim();
                String state, rest;
                if (t.startsWith("[x]") || t.startsWith("[X]")) { state = "on"; rest = t.substring(3); }
                else if (t.startsWith("[ ]")) { state = "off"; rest = t.substring(3); }
                else if (t.startsWith("---")) { state = "unavailable"; rest = t.substring(3); }
                else continue;
                rest = rest.trim();
                if (rest.startsWith(NAV_PREFIX)) {
                    String mode = rest.substring(NAV_PREFIX.length()).split("\\s+")[0];
                    if (has(NAV_MODES, mode) && !found.containsKey(mode)) found.put(mode, state);
                }
            }
        }
        Map<String, String> res = new LinkedHashMap<String, String>();
        for (String m : NAV_MODES) if (found.containsKey(m)) res.put(m, found.get(m));
        return res;
    }

    /** The mode that is on in a {@link #parseNavModes} result, or "" when none is. */
    public static String navEnabled(Map<String, String> modes) {
        if (modes != null) for (Map.Entry<String, String> e : modes.entrySet()) if ("on".equals(e.getValue())) return e.getKey();
        return "";
    }

    /** Turns one mode on and the other two off (enable-exclusive, within the navigation-bar category). */
    public static String navEnableCommand(String mode) {
        return "cmd overlay enable-exclusive --user 0 --category " + NAV_PREFIX + oneOf("navigation mode", mode, NAV_MODES);
    }

    // ---- display mode and window behaviour ----

    public static final int MODE_SIZE_MIN = 200, MODE_SIZE_MAX = 20000;
    public static final int WM_SIZE_MIN = 100, WM_SIZE_MAX = 99999;      // the Connected Devices tab takes 3 to 5 digits
    public static final int WM_DENSITY_MIN = 72, WM_DENSITY_MAX = 1000;
    private static final Pattern DISPLAY_MODE = Pattern.compile("width=(\\d+),\\s*height=(\\d+),\\s*fps=(\\d+(?:\\.\\d+)?)");

    public static String displayModeGetCommand() {
        return "cmd display get-user-preferred-display-mode";
    }

    /** Android 13 and newer; many makers ignore the preference. The refresh rate is written with up to two decimals (60.0, 59.94). */
    public static String displayModeSetCommand(int width, int height, double hz) {
        inRange("The width", width, MODE_SIZE_MIN, MODE_SIZE_MAX);
        inRange("The height", height, MODE_SIZE_MIN, MODE_SIZE_MAX);
        if (Double.isNaN(hz) || hz < 1.0 || hz > 480.0) throw bad("The refresh rate must be from 1 to 480 Hz");
        return "cmd display set-user-preferred-display-mode " + width + " " + height + " " + hzText(hz);
    }

    public static String displayModeClearCommand() {
        return "cmd display clear-user-preferred-display-mode";
    }

    /** Where the supported modes are listed. */
    public static String displayModesReadCommand() {
        return infoCommand("display");
    }

    private static String hzText(double hz) {
        String s = String.format(Locale.US, "%.2f", hz);
        while (s.endsWith("0") && !s.endsWith(".0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The modes in {@code dumpsys display} as "1080x2400@60.0" (the line format is unverified on a device, so this reads loosely: width, height and fps in a row), no repeats. */
    public static List<String> parseDisplayModes(String dump) {
        List<String> res = new ArrayList<String>();
        if (dump == null) return res;
        Matcher m = DISPLAY_MODE.matcher(dump);
        while (m.find()) {
            String s = m.group(1) + "x" + m.group(2) + "@" + hzText(Double.parseDouble(m.group(3)));
            if (!res.contains(s)) res.add(s);
        }
        return res;
    }

    /** Android 12 and newer. */
    public static String wmIgnoreOrientationCommand(boolean ignore) {
        return "wm set-ignore-orientation-request " + (ignore ? "true" : "false");
    }

    public static String wmIgnoreOrientationReadCommand() {
        return "wm get-ignore-orientation-request";
    }

    public static String wmScalingCommand(String mode) {
        return "wm scaling " + oneOf("scaling mode", mode, "off", "auto");
    }

    public static String wmSizeCommand(int w, int h) {
        inRange("The width", w, WM_SIZE_MIN, WM_SIZE_MAX);
        inRange("The height", h, WM_SIZE_MIN, WM_SIZE_MAX);
        return "wm size " + w + "x" + h;
    }

    public static String wmDensityCommand(int dpi) {
        return "wm density " + inRange("The density", dpi, WM_DENSITY_MIN, WM_DENSITY_MAX);
    }

    public static String wmSizeResetCommand() {
        return "wm size reset";
    }

    public static String wmDensityResetCommand() {
        return "wm density reset";
    }

    /** Size and density back to the screen's own (works on every Android version, unlike {@code wm reset}). */
    public static String wmResetCommand() {
        return "wm size reset; wm density reset";
    }

    public static String wmReadCommand() {
        return "wm size; wm density";
    }

    private static final Pattern WM_LINE = Pattern.compile("^\\s*(Physical|Override) (size|density):\\s*(\\d+(?:x\\d+)?)\\s*$");

    /** The answer of {@link #wmReadCommand}: size, overrideSize ("1080x2400"), density, overrideDensity; an override is missing when there is none. */
    public static Map<String, String> parseWmState(String out) {
        Map<String, String> res = new LinkedHashMap<String, String>();
        if (out == null) return res;
        for (String l : out.split("\r?\n")) {
            Matcher m = WM_LINE.matcher(l);
            if (!m.matches()) continue;
            boolean size = m.group(2).equals("size");
            if (size != m.group(3).contains("x")) continue;
            String key = (m.group(1).equals("Override") ? "override" + (size ? "Size" : "Density") : (size ? "size" : "density"));
            if (!res.containsKey(key)) res.put(key, m.group(3));
        }
        return res;
    }

    // ---- info dumps ----

    /** The read-only dumps the page may show: the name and what runs. */
    public static final String[] INFO_KEYS = {"statusbar", "status-icons", "window-displays", "display", "uimode"};
    private static final String[] INFO_COMMANDS = {"dumpsys statusbar", "cmd statusbar get-status-icons", "dumpsys window displays", "dumpsys display", "dumpsys uimode"};

    /** Whitelist only: the notification dump is not on it (see {@link #notificationDumpWarning}). */
    public static String infoCommand(String which) {
        return INFO_COMMANDS[indexOf(INFO_KEYS, oneOf("info sheet", which, INFO_KEYS))];
    }

    /** Why the dump of the notification service is not offered. */
    public static String notificationDumpWarning() {
        return "The notification dump (dumpsys notification) prints the text of every notification on the phone, private messages included, so it is not offered here. "
                + "Run it yourself in the Terminal only when you are sure nobody else can see the screen or the copied text.";
    }
}
