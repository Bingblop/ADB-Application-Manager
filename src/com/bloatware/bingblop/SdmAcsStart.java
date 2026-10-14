package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure part of starting the accessibility service from the app: what to do for each state of the service, and the edits of Android's list of enabled
 * accessibility services (the secure setting {@code enabled_accessibility_services}, entries joined by ':'). No android.* classes, so it is tested on its own
 * ({@link SdmAutomation} and {@link SdmHost} do the shell calls with it).
 */
final class SdmAcsStart {
    private SdmAcsStart() {}

    /** What to do to bring the service up. */
    enum Step {
        /** Nothing: not allowed, not needed or not possible. */
        NONE,
        /** The service is switched off: add it to the list. */
        ENABLE,
        /** It is in the list but Android does not run it: take it out and put it back so that Android binds it again. */
        REBIND
    }

    /**
     * The decision table. {@code reason} is {@link Sdm.Automation#notReadyReason}: NO_CONSENT, NOT_ENABLED, NOT_CONNECTED, or null (ready or not known).
     * Without the consent, without a working mode shell, or when the reason is not one of the two states that starting can fix, nothing is done:
     * consent is never given by the app on its own, and the secure setting can be written only through a privileged shell.
     */
    static Step decide(String reason, boolean hasShell) {
        if (!hasShell || reason == null) return Step.NONE;
        if ("NOT_ENABLED".equals(reason)) return Step.ENABLE;
        if ("NOT_CONNECTED".equals(reason)) return Step.REBIND;
        return Step.NONE;                                              // NO_CONSENT and anything unknown
    }

    static final String READ = "settings get secure enabled_accessibility_services";

    /** The command that writes the list; adding also sets the master switch ({@code accessibility_enabled}), as the SD Maid tab's button always did. */
    static String writeCommand(String list, boolean masterSwitch) {
        return "settings put secure enabled_accessibility_services " + BackupScripts.quote(list) + (masterSwitch ? "; settings put secure accessibility_enabled 1" : "");
    }

    /** What the read printed as a list: "" for an empty or unset setting ("null"), null when the phone refused (an error text instead of a list). */
    static String parseList(String output) {
        String cur = output == null ? "" : output.trim();
        if (cur.contains("Error") || cur.contains("Exception")) return null;
        return cur.equals("null") ? "" : cur;
    }

    static boolean listed(String list, String full, String shortName) {
        if (list == null) return false;
        for (String s : list.split(":")) if (s.equalsIgnoreCase(full) || s.equalsIgnoreCase(shortName)) return true;
        return false;
    }

    /** The list with the component added after the others; null when it is already in it (nothing to write). */
    static String add(String list, String full, String shortName) {
        String cur = list == null ? "" : list;
        if (listed(cur, full, shortName)) return null;
        return cur.isEmpty() ? full : cur + ":" + full;
    }

    /** The list without the component (the other services stay, in order); null when it is not in it (nothing to write). */
    static String remove(String list, String full, String shortName) {
        if (!listed(list, full, shortName)) return null;
        List<String> keep = new ArrayList<String>();
        for (String s : list.split(":")) if (!s.isEmpty() && !s.equalsIgnoreCase(full) && !s.equalsIgnoreCase(shortName)) keep.add(s);
        StringBuilder sb = new StringBuilder();
        for (String s : keep) { if (sb.length() > 0) sb.append(':'); sb.append(s); }
        return sb.toString();
    }
}
