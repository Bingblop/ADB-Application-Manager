package com.bloatware.bingblop;

import java.security.SecureRandom;

/**
 * Reads the output of {@code stat -c '<tag> %f %s %Y %u %n'} so that a file name cannot forge a record. A name may hold a newline, and
 * the name {@code x\n81a4 5 1700000000 10000 /sdcard/DCIM/IMG_0001.jpg} would print a second line that looks exactly like the stat line of
 * another file. Every real record therefore starts with a tag the scan makes up for itself (random, unknown to whoever named the file):
 * a line without the tag is the rest of a name, and a record that goes on over another line is dropped instead of being believed.
 */
final class SdmStatFrame {
    private final String tag;
    private final String end;
    private Sdm.Entry pending;
    private boolean continued;

    SdmStatFrame() { this(randomToken("__SDM_"), randomToken("__SDM_END_")); }

    SdmStatFrame(String tag) { this(tag, randomToken("__SDM_END_")); }

    SdmStatFrame(String tag, String end) { this.tag = tag; this.end = end; }

    private static String randomToken(String prefix) {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.append("__").toString();
    }

    /**
     * The line that ends this scan's output, made up for the scan like the tag. The shared {@code __SDM_END__} would let a name
     * such as {@code victim\n__SDM_END__} end the output early and leave the record of {@code victim} (cut off at the newline) believed.
     */
    String endMarker() { return end; }

    /** The {@code -c} argument, single-quoted for the shell. */
    String format() { return "'" + tag + " %f %s %Y %u %n'"; }

    /** One line of output; returns the entry that this line completed (the previous record), or null. */
    Sdm.Entry feed(String line) {
        if (line != null && line.startsWith(tag + " ")) {
            Sdm.Entry done = take();
            pending = SdmFsShell.parseStat(line.substring(tag.length() + 1));
            continued = false;
            return done;
        }
        if (pending != null) continued = true;      // not a record of ours: the name of the one before runs on over this line
        return null;
    }

    /** The end of the output: the last record, unless its name ran on. */
    Sdm.Entry finish() { return take(); }

    private Sdm.Entry take() {
        Sdm.Entry e = continued ? null : pending;
        pending = null;
        continued = false;
        return e;
    }
}
