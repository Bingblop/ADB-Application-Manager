package com.bloatware.bingblop;

/**
 * The words and colours of the page's own dialogs (alert(), confirm() and prompt() of assets/index.html), worked out without Android
 * classes so they can be tested on a plain JVM (tests/java/src/PageDialogTextTest.java). {@link PageDialogs} draws the dialog.
 *
 * A dialog gets a real title instead of the WebView's 'The page at "file://" says:'. The page can name it (nextDialogTitle(title) in
 * index.html, which reaches {@link PageDialogs#setNextTitle}); without one the title is taken from the message: a short first line followed
 * by a blank line ("Delete the preset X?\n\nThis cannot be undone.") is the title and the rest the body; otherwise a plain word for the kind
 * of dialog ("Confirm", "ADB Full System Manager", "Enter a value", or the page's translation of them).
 */
final class PageDialogText {
    private PageDialogText() {}

    static final int ALERT = 0, CONFIRM = 1, PROMPT = 2;

    /** A first line longer than this is a sentence of the body, not a title. */
    static final int TITLE_MAX = 80;

    /** The title and the body a dialog shows. */
    static final class Parts {
        final String title, body;
        Parts(String title, String body) { this.title = title; this.body = body; }
    }

    /**
     * The title and body for a dialog of this kind. explicit: the title the page named for it (null or blank: none); message: what the page
     * passed to alert / confirm / prompt; fallback: the title used when neither gives one (null: the English word for the kind).
     */
    static Parts split(int kind, String explicit, String message, String fallback) {
        String msg = message == null ? "" : message.replace("\r\n", "\n");
        String t = clean(explicit);
        if (!t.isEmpty()) return new Parts(t, msg);
        int gap = msg.indexOf("\n\n");
        if (gap > 0) {
            String first = msg.substring(0, gap).trim();
            String rest = msg.substring(gap + 2);
            while (rest.startsWith("\n")) rest = rest.substring(1);
            if (!first.isEmpty() && first.indexOf('\n') < 0 && first.length() <= TITLE_MAX && !rest.trim().isEmpty()) return new Parts(first, rest);
        }
        String fb = clean(fallback);
        return new Parts(fb.isEmpty() ? defaultTitle(kind) : fb, msg);
    }

    static String defaultTitle(int kind) {
        if (kind == CONFIRM) return "Confirm";
        if (kind == PROMPT) return "Enter a value";
        return "ADB Full System Manager";
    }

    /** One line of plain text: control characters (line breaks included) become spaces, at most 120 characters. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        String out = b.toString().trim();
        return out.length() > 120 ? out.substring(0, 120).trim() : out;
    }

    /**
     * A CSS colour as the page writes it, as ARGB: #RGB, #RRGGBB, #RRGGBBAA (CSS order: alpha last), rgb(r, g, b) and rgba(r, g, b, a)
     * (a from 0 to 1). Anything else gives dflt.
     */
    static int parseColor(String s, int dflt) {
        if (s == null) return dflt;
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (v.startsWith("#")) {
                String h = v.substring(1);
                if (!h.matches("[0-9a-f]+")) return dflt;
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
                if (h.length() == 8) {
                    int rgb = Integer.parseInt(h.substring(0, 6), 16), a = Integer.parseInt(h.substring(6), 16);
                    return (a << 24) | rgb;
                }
                return dflt;
            }
            boolean rgba = v.startsWith("rgba(");
            if ((rgba || v.startsWith("rgb(")) && v.endsWith(")")) {
                String[] p = v.substring(v.indexOf('(') + 1, v.length() - 1).split(",");
                if (p.length != (rgba ? 4 : 3)) return dflt;
                int r = channel(p[0]), g = channel(p[1]), bl = channel(p[2]);
                if (r < 0 || g < 0 || bl < 0) return dflt;
                int a = 255;
                if (rgba) {
                    double d = Double.parseDouble(p[3].trim());
                    if (!(d >= 0 && d <= 1)) return dflt;
                    a = (int) Math.round(d * 255);
                }
                return (a << 24) | (r << 16) | (g << 8) | bl;
            }
        } catch (RuntimeException e) {
            return dflt;
        }
        return dflt;
    }

    private static int channel(String s) {
        int n = Integer.parseInt(s.trim());
        return n >= 0 && n <= 255 ? n : -1;
    }

    /** The colour a over b at weight t of a (0..1), opaque. */
    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
        int g = (int) Math.round(((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
        int bl = (int) Math.round((a & 255) * t + (b & 255) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** The same colour at this alpha (0..255). */
    static int withAlpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }
}
