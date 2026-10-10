package com.bloatware.bingblop;

public class InstallHintsTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        // what `adb install` / `pm install-commit` really print
        String[] broken = {
            "Performing Streamed Install\nadb: failed to install /data/user/0/x/cache/installer/base.apk: Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from /data/app/vmdl1.tmp/base.apk: Attempt to get length of null array]",
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from /data/app/vmdl2.tmp/base.apk: META-INF/MANIFEST.MF has invalid digest for AndroidManifest.xml in AndroidManifest.xml]",
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Scanning Failed.: No signature found in package of version 2 or newer for package com.example]",
            "Failure [INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: Failed to collect certificates from /data/app/vmdl3.tmp/base.apk: APK Signature Scheme v2 signer #1 ... SHA-256 digest of contents did not verify]",
            "Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: Package com.example has no certificates at entry res/layout/a.xml; ignoring!]",
            "Error: java.lang.SecurityException: Package has no certificates at entry AndroidManifest.xml",
        };
        for (String s : broken) check("broken: " + s, InstallHints.brokenSignature(s) && !InstallHints.updateIncompatible(s) && !InstallHints.success(s));

        String[] mismatch = {
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.example signatures do not match previously installed version; ignoring!]",
            "adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.example signatures do not match newer version; ignoring!]",
        };
        for (String s : mismatch) check("mismatch: " + s, InstallHints.updateIncompatible(s) && !InstallHints.success(s));

        // not these two
        check("shared user is not a plain mismatch", !InstallHints.updateIncompatible("Failure [INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: Package com.x has no signatures that match those in shared user android.uid.system; ignoring!]"));
        check("downgrade is not a signature problem", !InstallHints.brokenSignature("Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2]"));
        check("null safe", !InstallHints.success(null) && !InstallHints.brokenSignature(null) && !InstallHints.updateIncompatible(null) && InstallHints.advice(null).isEmpty());
        check("empty", InstallHints.advice("").isEmpty());

        check("success", InstallHints.success("Performing Streamed Install\nSuccess") && InstallHints.success("Success"));
        check("success has no advice", InstallHints.advice("Success").isEmpty());

        check("advice mismatch", InstallHints.advice(mismatch[0]).contains("Uninstalling it first"));
        check("advice broken", InstallHints.advice(broken[0]).contains("signing") || InstallHints.advice(broken[0]).contains("Signing"));
        check("advice downgrade", InstallHints.advice("Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2]").contains("Allow downgrade"));
        check("advice test only", InstallHints.advice("Failure [INSTALL_FAILED_TEST_ONLY]").contains("test packages"));
        check("advice low sdk", InstallHints.advice("Failure [INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 23, but found 22]").contains("low target SDK"));
        check("advice older sdk", InstallHints.advice("Failure [INSTALL_FAILED_OLDER_SDK: Dependency: requires newer sdk]").contains("newer Android"));
        check("advice abi", InstallHints.advice("Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]").contains("processor"));
        check("advice storage", InstallHints.advice("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]").contains("storage"));
        check("advice verifier", InstallHints.advice("Failure [INSTALL_FAILED_VERIFICATION_FAILURE: Install verification failed]").contains("Play Protect"));
        check("advice unknown is empty", InstallHints.advice("Failure [INSTALL_FAILED_WEIRD]").isEmpty());
        // mismatch wins over a downgrade line in the same output
        check("mismatch first", InstallHints.advice("INSTALL_FAILED_UPDATE_INCOMPATIBLE ... INSTALL_FAILED_VERSION_DOWNGRADE").contains("Uninstalling"));

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
