package com.bloatware.bingblop;

/**
 * Which characters an emoji font draws once it is merged into a text font, from Unicode's emoji data.
 *
 * <p>When the Custom Fonts tab puts an emoji font inside a text font ({@code EmojiMerge}), a character both fonts have keeps the text font's
 * glyph unless {@link #emojiWins} says the emoji form is the one people expect: characters shown as emoji by default (Emoji_Presentation),
 * skin tone modifiers, flag letters, the joiner and the selectors emoji sequences are built from, and the emoji blocks from U+1F000.
 * {@link #textAlwaysWins} keeps space, digits, # * and every other Latin-1 character with the text font even where an emoji font has a glyph
 * for it (keycaps and the (c) (R) emoji then come from the U+FE0F sequences). Pure Java, no Android classes.
 */
public final class EmojiData {

    private EmojiData() {}

    /** The Unicode version of the Emoji_Presentation table below. */
    public static final String UNICODE_VERSION = "18.0.0";
    /** Variation selectors: U+FE0E asks for the text form of a character, U+FE0F for the emoji form. */
    public static final int TEXT_SELECTOR = 0xFE0E, EMOJI_SELECTOR = 0xFE0F;
    public static final int ZWJ = 0x200D, KEYCAP = 0x20E3;

    // Emoji_Presentation=Yes from Unicode 18.0.0 emoji-data.txt (https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-data.txt, dated
    // 2026-01-30, sha256 80d00f8e616a0ef27fd6b8de3b758c06383b5d917e2977709578e68baf733bf1): 1228 code points in 80 ranges, first and last of
    // each in order. Written by tests/java/fixtures/fonts/cmap/make_fixtures.py emojidata; the cmap suite checks it against that file's list.
    private static final int[] PRESENTATION = {
            0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
            0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
            0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
            0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
            0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
            0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
            0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F201, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F236,
            0x1F238, 0x1F23A, 0x1F250, 0x1F251, 0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393,
            0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440,
            0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
            0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D9,
            0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
            0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FAC6, 0x1FAC8, 0x1FAC8, 0x1FACC, 0x1FADD,
            0x1FADF, 0x1FAEB, 0x1FAEF, 0x1FAFA,
    };

    /** True when Unicode shows the character as an emoji by default (Emoji_Presentation=Yes), with no U+FE0F needed. */
    public static boolean isEmojiPresentation(int cp) {
        int lo = 0, hi = PRESENTATION.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < PRESENTATION[2 * mid]) hi = mid - 1;
            else if (cp > PRESENTATION[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    /** Characters the text font keeps whatever the emoji font has: space, # * 0-9, (c) (R) and the rest of U+0000..U+00FF. */
    public static boolean textAlwaysWins(int cp) { return cp >= 0 && cp <= 0xFF; }

    /**
     * True when the emoji font's glyph replaces the text font's for a character both have: Emoji_Presentation, the skin tone modifiers
     * U+1F3FB..1F3FF, the regional indicators U+1F1E6..1F1FF, ZWJ U+200D, VS16 U+FE0F, the keycap U+20E3, the tags U+E0020..E007F, and the
     * plane 1 symbol and emoji blocks U+1F000..1FFFF. Never for {@link #textAlwaysWins} characters; planes 2 and up (CJK, private use) stay
     * with the text font.
     */
    public static boolean emojiWins(int cp) {
        if (textAlwaysWins(cp)) return false;
        return isEmojiPresentation(cp)
                || cp >= 0x1F000 && cp <= 0x1FFFF                     // includes the modifiers and the regional indicators
                || cp == ZWJ || cp == EMOJI_SELECTOR || cp == KEYCAP
                || cp >= 0xE0020 && cp <= 0xE007F;
    }
}
