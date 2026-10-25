package com.bloatware.bingblop;

/**
 * Whether a runtime permission the permissions sheet asked for (Bluetooth Connect, Notifications) is refused for good, so the app opens its
 * App info page, where the switch is. Android tells only through shouldShowRequestPermissionRationale, and that is false in two states: before
 * the first real answer, and after a refusal for good. A dialog dismissed with Back or a tap outside (Android 11+) changes nothing, so on a first
 * ask it looks like a refusal for good. It counts as one only when there was an answer before: the rationale was there before this request
 * (refused once, now refused again), or the sheet has asked before (refused for good earlier, so Android showed no dialog at all; a second
 * dismissal also lands here). No Android classes.
 */
final class PermRefusal {
    private PermRefusal() {}

    static boolean forGood(boolean granted, boolean rationaleBefore, boolean rationaleAfter, boolean askedBefore) {
        return !granted && !rationaleAfter && (rationaleBefore || askedBefore);
    }
}
