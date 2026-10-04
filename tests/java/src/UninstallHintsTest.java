package com.bloatware.bingblop;

public class UninstallHintsTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        // what `pm uninstall [-k] --user 0` really prints, on a real phone (the root-required line is a real device's,
        // the policy/restriction ones are PackageManager's own DELETE_FAILED_* constants in the same "Failure [...]" shape)
        String rootRequired = "Failure [only root can delete system app for a particular user]";
        String policy = "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]";
        String restricted = "Failure [DELETE_FAILED_USER_RESTRICTED]";
        String success = "Success";

        check("root required recognized", UninstallHints.rootRequired(rootRequired) && !UninstallHints.success(rootRequired));
        check("root required advice mentions Root", UninstallHints.advice(rootRequired).contains("Root"));
        check("root required advice mentions Freeze", UninstallHints.advice(rootRequired).contains("Freeze"));
        check("root required is case-insensitive", UninstallHints.rootRequired("failure [ONLY ROOT CAN DELETE SYSTEM APP FOR A PARTICULAR USER]"));

        check("policy blocked recognized", UninstallHints.policyBlocked(policy) && !UninstallHints.success(policy));
        check("policy advice says device policy", UninstallHints.advice(policy).toLowerCase().contains("device policy"));
        check("policy is not root-required", !UninstallHints.rootRequired(policy));

        check("user restricted recognized", UninstallHints.userRestricted(restricted) && !UninstallHints.success(restricted));
        check("restricted advice says restriction", UninstallHints.advice(restricted).toLowerCase().contains("restriction"));
        check("restricted is not policy", !UninstallHints.policyBlocked(restricted));

        check("success", UninstallHints.success(success) && UninstallHints.success("Performing Streamed Uninstall\nSuccess"));
        check("success has no advice", UninstallHints.advice(success).isEmpty());

        check("an unrecognized failure has no advice", UninstallHints.advice("Failure [DELETE_FAILED_INTERNAL_ERROR]").isEmpty());
        check("null safe", !UninstallHints.success(null) && !UninstallHints.rootRequired(null) && !UninstallHints.policyBlocked(null)
                && !UninstallHints.userRestricted(null) && UninstallHints.advice(null).isEmpty());
        check("empty", UninstallHints.advice("").isEmpty() && !UninstallHints.success(""));

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
