package com.bloatware.bingblop;

/**
 * Progress and Cancel for the long steps of the Custom Fonts engine (instancing a variable font, converting PostScript outlines, merging
 * emoji). The engine calls {@link #step} every few hundred glyphs and stops with a "cancelled" {@link Sfnt.FontException} once
 * {@link #cancelled} answers true.
 */
public interface FontProgress {

    /** {@code done} of {@code total} units of the step named {@code stage} ("instance", "convert", "emoji", "package" ...). */
    void step(String stage, int done, int total);

    boolean cancelled();

    /** Reports nothing and never cancels. */
    FontProgress NONE = new FontProgress() {
        @Override public void step(String stage, int done, int total) {}
        @Override public boolean cancelled() { return false; }
    };

    /** Throws the "cancelled" FontException when the job was cancelled; a null progress never is. */
    final class Check {
        private Check() {}

        public static void cancel(FontProgress p) throws Sfnt.FontException {
            if (p != null && p.cancelled()) throw new Sfnt.FontException("cancelled", "Cancelled.");
        }
    }
}
