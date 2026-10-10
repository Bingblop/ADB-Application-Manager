package com.bloatware.bingblop;

import java.security.SecureRandom;

/**
 * This app's own authorization code (About tab): 25 letters and digits in five groups of five, made on the phone with a secure random source
 * (125 bits; Crockford's alphabet, so no 0/O or 1/I/L mix-ups) and kept in the app's private preferences. Nothing accepts it yet: no intent is
 * wired to it, so it is only shown, copied and replaced. No Android classes here, so it is tested on its own.
 */
public final class AuthManager {
    /** Where the code is kept (the app's private preferences on the phone). */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int GROUPS = 5, GROUP_LEN = 5;
    static final String K_CODE = "auth_code";

    private final Store store;
    private final SecureRandom random;

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

    /** A typed or pasted code as it is compared: upper case, no dashes or spaces, O as 0, I and L as 1 (Crockford). */
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

    /** The code, made the first time it is asked for (and again if what was kept is damaged). */
    public synchronized String code() {
        String c = store.get(K_CODE);
        if (c == null || normalize(c).length() != GROUPS * GROUP_LEN) {
            c = generate(random);
            store.put(K_CODE, c);
        }
        return c;
    }

    /** A new code; the old one is gone. */
    public synchronized String refresh() {
        String c = generate(random);
        store.put(K_CODE, c);
        return c;
    }
}
