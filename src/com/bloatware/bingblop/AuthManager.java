package com.bloatware.bingblop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * This app's own Authorization Manager code. Another app, a Tasker or MacroDroid task or an adb command can start {@link AuthLaunchActivity} with the code
 * in the intent's "auth" extra, and that activity then launches an app or an address for it. No Android classes here, so it is tested on its own.
 *
 * The code is 25 letters and digits (125 bits of randomness; Crockford's alphabet, so no 0/O or 1/I/L mix-ups), written in five groups. It is
 * checked without leaking where it differs, a run of wrong guesses locks the door for a minute, and it is off until the person turns it on.
 */
public final class AuthManager {
    public static final String ACTION = "com.bloatware.bingblop.action.AUTH_LAUNCH";
    public static final String EXTRA_AUTH = "auth";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_URI = "uri";

    /** Where the code, the switch and the short list of recent uses are kept (the app's private preferences on the phone). */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public enum Verdict { OK, DISABLED, LOCKED, WRONG, MISSING }

    /** One line of "Recent uses". */
    public static final class Entry {
        public final long at;
        public final String verdict;
        public final String what;
        Entry(long at, String verdict, String what) { this.at = at; this.verdict = verdict; this.what = what; }
    }

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int GROUPS = 5, GROUP_LEN = 5;
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 60000L;
    static final int MAX_LOG = 10;
    static final String K_CODE = "auth_code", K_ON = "auth_on", K_LOG = "auth_log";

    private final Store store;
    private final SecureRandom random;
    private int fails;
    private long lockedUntil;

    public AuthManager(Store store, SecureRandom random) {
        this.store = store;
        this.random = random == null ? new SecureRandom() : random;
    }

    /** A fresh code: ABCDE-FGHJK-MNPQR-STVWX-YZ012. */
    public static String generate(SecureRandom r) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < GROUP_LEN; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** What a typed or pasted code is compared as: upper case, no dashes or spaces, O as 0, I and L as 1 (Crockford). */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            if (ALPHABET.indexOf(c) >= 0) sb.append(c);
        }
        return sb.toString();
    }

    /** The code, made the first time it is asked for. */
    public synchronized String code() {
        String c = store.get(K_CODE);
        if (c == null || normalize(c).length() != GROUPS * GROUP_LEN) {
            c = generate(random);
            store.put(K_CODE, c);
        }
        return c;
    }

    /** A new code; the old one stops working at once. */
    public synchronized String refresh() {
        String c = generate(random);
        store.put(K_CODE, c);
        fails = 0;
        lockedUntil = 0;
        return c;
    }

    public synchronized boolean enabled() { return "1".equals(store.get(K_ON)); }

    public synchronized void setEnabled(boolean on) {
        store.put(K_ON, on ? "1" : "0");
        if (!on) { fails = 0; lockedUntil = 0; }
    }

    /** Whether the code in an intent opens the door. Counts wrong guesses; after {@link #MAX_FAILS} in a row nothing is accepted for {@link #LOCK_MS}. */
    public synchronized Verdict check(String supplied, long now) {
        if (!enabled()) return Verdict.DISABLED;
        if (lockedUntil > now) return Verdict.LOCKED;
        if (lockedUntil != 0) { lockedUntil = 0; fails = 0; }
        if (supplied == null || supplied.trim().isEmpty()) return Verdict.MISSING;
        byte[] a = normalize(supplied).getBytes(StandardCharsets.UTF_8);
        byte[] b = normalize(code()).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(a, b)) { fails = 0; return Verdict.OK; }
        if (++fails >= MAX_FAILS) lockedUntil = now + LOCK_MS;
        return Verdict.WRONG;
    }

    /** Seconds until a locked door opens again (0 when it is not locked). */
    public synchronized int lockedSeconds(long now) { return lockedUntil > now ? (int) ((lockedUntil - now + 999) / 1000) : 0; }

    /** Adds a line to the recent uses (newest first, the last {@value #MAX_LOG} are kept). */
    public synchronized void record(long at, String verdict, String what) {
        List<Entry> list = recent();
        list.add(0, new Entry(at, clean(verdict, 12), clean(what, 80)));
        while (list.size() > MAX_LOG) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(e.at).append('|').append(e.verdict).append('|').append(e.what).append('\n');
        store.put(K_LOG, sb.toString());
    }

    public synchronized List<Entry> recent() {
        List<Entry> out = new ArrayList<Entry>();
        String raw = store.get(K_LOG);
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            try { out.add(new Entry(Long.parseLong(p[0]), p[1], p[2])); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public synchronized void clearRecent() { store.put(K_LOG, ""); }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n|]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ---- what the intent may ask for ----

    /** An Android package name, as the package extra must be. */
    public static boolean validPackage(String p) { return p != null && p.length() <= 255 && p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"); }

    /** The schemes a plain address may have; file:, content: and javascript: never go through this door. */
    public static boolean allowedScheme(String scheme) {
        if (scheme == null) return false;
        String s = scheme.toLowerCase(java.util.Locale.ROOT);
        return s.equals("http") || s.equals("https") || s.equals("market") || s.equals("geo") || s.equals("mailto") || s.equals("tel") || s.equals("sms") || s.equals("smsto") || s.equals("package") || s.equals("android-app");
    }

    /** The flags an incoming intent may not ask for: handing out access to a file or a content address of this app. */
    public static int strippedFlags(int flags, int[] forbidden) {
        for (int f : forbidden) flags &= ~f;
        return flags;
    }
}
