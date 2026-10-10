package com.bloatware.bingblop;

/**
 * The record kept while the device assistant is changed on purpose (see MainActivity.launchViaAssistant): the user's own
 * {@code assistant} and {@code voice_interaction_service} values, written down before they are touched, so a process that is
 * killed half way can put them back at the next start. Values are checked before they are written back to a shell command.
 */
final class AssistantRestore {
    private AssistantRestore() {}

    /**
     * True for an empty value (the setting is unset) or a plain component name such as {@code com.x.y/com.x.y.Z$Inner}.
     * Anything else (error text, spaces, quotes, shell characters) is not a value we read from the setting and is never written back.
     */
    static boolean isSafeValue(String v) {
        if (v == null) return false;
        if (v.isEmpty()) return true;
        if (v.length() > 400) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '$' || c == '/' || c == '-' || c == ':' || c == '@' || c == ',';
            if (!ok) return false;
        }
        return true;
    }

    /** "v1\n&lt;assistant&gt;\n&lt;voice_interaction_service&gt;", or null when either value is unsafe. */
    static String encode(String assistant, String vis) {
        if (!isSafeValue(assistant) || !isSafeValue(vis)) return null;
        return "v1\n" + assistant + "\n" + vis;
    }

    /** {assistant, vis} from {@link #encode}, or null for anything else. */
    static String[] decode(String s) {
        if (s == null) return null;
        String[] p = s.split("\n", -1);
        if (p.length != 3 || !"v1".equals(p[0])) return null;
        if (!isSafeValue(p[1]) || !isSafeValue(p[2])) return null;
        return new String[] { p[1], p[2] };
    }
}
