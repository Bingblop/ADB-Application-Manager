package com.bloatware.bingblop;

import java.io.File;
import java.util.Collections;

public class StoragePathGuardTest {
    private static int checks = 0;
    private static int failed = 0;

    private static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.err.println("FAIL: " + msg);
        }
    }

    public static void main(String[] args) {
        testBackupRefValidation();
        testShareFileValidation();
        if (failed > 0) {
            System.err.println(failed + " of " + checks + " checks failed");
            System.exit(1);
        } else {
            System.out.println("PASS StoragePathGuardTest: " + checks + " checks");
            System.exit(0);
        }
    }

    private static void testBackupRefValidation() {
        File base = new File(System.getProperty("java.io.tmpdir"), "test_backups");
        base.mkdirs();
        File valid = new File(base, "app_1.0_20261010.adbbackup");

        check(!StoragePathGuard.isSafeBackupRef(null, base, null), "null ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("", base, null), "empty ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("   ", base, null), "whitespace ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("test\0bad", base, null), "NUL character rejected");
        check(!StoragePathGuard.isSafeBackupRef(new File(base, "test.apk").getAbsolutePath(), base, null), "non-adbbackup extension rejected");
        check(!StoragePathGuard.isSafeBackupRef(new File(base, "test.sh").getAbsolutePath(), base, null), "shell script rejected");

        File parentDir = new File(base.getParentFile(), "escaped.adbbackup");
        check(!StoragePathGuard.isSafeBackupRef(parentDir.getAbsolutePath(), base, null), "parent directory path rejected when not indexed");
        check(!StoragePathGuard.isSafeBackupRef(base.getAbsolutePath() + "/../escaped.adbbackup", base, null), "traversal dots rejected");

        check(StoragePathGuard.isSafeBackupRef(valid.getAbsolutePath(), base, null), "valid backup inside base dir accepted");

        check(!StoragePathGuard.isSafeBackupRef("content://", base, null), "empty content uri rejected");
        check(!StoragePathGuard.isSafeBackupRef("content:///local", base, null), "invalid slash content uri rejected");
        check(StoragePathGuard.isSafeBackupRef("content://media/external/downloads/123", base, null), "valid content uri accepted without index");

        String outsideIndexed = parentDir.getAbsolutePath();
        check(StoragePathGuard.isSafeBackupRef(outsideIndexed, base, Collections.singletonList(outsideIndexed)), "known indexed backup accepted even if outside base");
        check(!StoragePathGuard.isSafeBackupRef(parentDir.getAbsolutePath() + ".extra", base, Collections.singletonList(outsideIndexed)), "unknown ref rejected when index provided");
    }

    private static void testShareFileValidation() {
        File allowed = new File(System.getProperty("java.io.tmpdir"), "test_share");
        allowed.mkdirs();
        File report = new File(allowed, "report.txt");

        check(!StoragePathGuard.isSafeShareFile(null, Collections.singletonList(allowed)), "null share path rejected");
        check(!StoragePathGuard.isSafeShareFile("", Collections.singletonList(allowed)), "empty share path rejected");
        check(!StoragePathGuard.isSafeShareFile("bad\0path", Collections.singletonList(allowed)), "NUL path rejected");

        check(!StoragePathGuard.isSafeShareFile("/data/data/com.bloatware/shared_prefs/prefs.xml", Collections.singletonList(allowed)), "shared_prefs path blocked");
        check(!StoragePathGuard.isSafeShareFile("/data/user/0/com.bloatware/databases/app.db", Collections.singletonList(allowed)), "databases path blocked");
        check(!StoragePathGuard.isSafeShareFile(new File(allowed, "test.xml").getAbsolutePath(), Collections.singletonList(allowed)), "xml extension blocked");
        check(!StoragePathGuard.isSafeShareFile(new File(allowed, "test.db").getAbsolutePath(), Collections.singletonList(allowed)), "db extension blocked");

        File unauth = new File(System.getProperty("java.io.tmpdir"), "unauthorized.txt");
        check(!StoragePathGuard.isSafeShareFile(unauth.getAbsolutePath(), Collections.singletonList(allowed)), "file outside allowed directories rejected");

        check(StoragePathGuard.isSafeShareFile(report.getAbsolutePath(), Collections.singletonList(allowed)), "safe file in allowed dir accepted");
        check(StoragePathGuard.isSafeShareFile("content://downloads/my_file", Collections.singletonList(allowed)), "content uri accepted");
    }
}
