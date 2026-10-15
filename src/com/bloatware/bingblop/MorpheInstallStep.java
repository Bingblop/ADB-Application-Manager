package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.IOException;

/**
 * What the app does with the answer of an install of a patched APK: record that it was installed and, when the person chose that, delete the
 * patched APK afterwards. Pure java + org.json, so it is unit-testable off-device.
 */
final class MorpheInstallStep {
    private MorpheInstallStep() {}

    /**
     * True when the answer says the install worked. With no working mode a single APK is only handed to Android's installer ({@code handedOff}):
     * the person may still cancel there or the install may fail, so that answer is "opened", not "installed". (A split bundle without a working mode
     * waits for the system's verdict and answers for real.)
     */
    static boolean installed(JSONObject r) {
        return r != null && r.optBoolean("ok") && !r.optBoolean("handedOff");
    }

    /**
     * The state of the run's "Installing" step for an install answer: OK only when it was installed ({@link #installed}), FAIL when it was not, and
     * OPENED when the APK was only handed to Android's installer (the person may still cancel there). The page draws a step it does not know as a
     * plain chip, neither done nor failed, and logs it as "Installing opened".
     */
    static String stepState(JSONObject r) {
        if (installed(r)) return "OK";
        return r != null && r.optBoolean("ok") && r.optBoolean("handedOff") ? "OPENED" : "FAIL";
    }

    /**
     * Applies an install answer to the library entry {@code id}: marks it installed when it was, and deletes it as well when {@code deleteAfter}.
     * Returns true when the entry was deleted.
     */
    static boolean settle(MorpheLibrary library, String id, JSONObject r, boolean deleteAfter) throws IOException {
        if (!installed(r)) return false;
        library.markInstalled(id);
        if (!deleteAfter) return false;
        library.delete(id);
        return true;
    }
}
