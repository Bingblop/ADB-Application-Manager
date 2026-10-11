package com.bloatware.bingblop;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

public class StoragePathGuardTest {

    private static int checks = 0;
    private static int failed = 0;

    private static void check(boolean condition, String msg) {
        checks++;
        if (!condition) {
            failed++;
            System.err.println("FAIL: " + msg);
        }
    }

    public static void main(String[] args) {
        testBackupRefValidation();
        testShareFileValidation();

        if (failed > 0) {
            System.err.println("StoragePathGuardTest: " + failed + " of " + checks + " checks failed");
            System.exit(1);
        } else {
            System.out.println("PASS StoragePathGuardTest: " + checks + " checks");
            System.exit(0);
        }
    }

    private static void testBackupRefValidation() {
        File baseDir = new File(System.getProperty("java.io.tmpdir"), "test_backups");
        baseDir.mkdirs();
        File validBackup = new File(baseDir, "app_1.0_20261010.adbbackup");

        // Null and empty checks
        check(!StoragePathGuard.isSafeBackupRef(null, baseDir, null), "null ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("", baseDir, null), "empty ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("   ", baseDir, null), "whitespace ref rejected");
        check(!StoragePathGuard.isSafeBackupRef("test\0bad", baseDir, null), "NUL character rejected");

        // Non-backup extension rejected
        check(!StoragePathGuard.isSafeBackupRef(new File(baseDir, "test.apk").getAbsolutePath(), baseDir, null), "non-adbbackup extension rejected");
        check(!StoragePathGuard.isSafeBackupRef(new File(baseDir, "test.sh").getAbsolutePath(), baseDir, null), "shell script rejected");

        // Directory traversal attempts rejected
        File outsideFile = new File(baseDir.getParentFile(), "escaped.adbbackup");
        check(!StoragePathGuard.isSafeBackupRef(outsideFile.getAbsolutePath(), baseDir, null), "parent directory path rejected when not indexed");
        check(!StoragePathGuard.isSafeBackupRef(baseDir.getAbsolutePath() + "/../escaped.adbbackup", baseDir, null), "traversal dots rejected");

        // Legitimate backup inside base dir accepted
        check(StoragePathGuard.isSafeBackupRef(validBackup.getAbsolutePath(), baseDir, null), "valid backup inside base dir accepted");

        // Content URIs
        check(!StoragePathGuard.isSafeBackupRef("content://", baseDir, null), "empty content uri rejected");
        check(!StoragePathGuard.isSafeBackupRef("content:///local", baseDir, null), "invalid slash content uri rejected");
        check(StoragePathGuard.isSafeBackupRef("content://media/external/downloads/123", baseDir, null), "valid content uri accepted without index");

        // Indexed backup matching
        String externalBackup = outsideFile.getAbsolutePath();
        check(StoragePathGuard.isSafeBackupRef(externalBackup, baseDir, Collections.singletonList(externalBackup)), "known indexed backup accepted even if outside base");
        check(!StoragePathGuard.isSafeBackupRef(outsideFile.getAbsolutePath() + ".fake", baseDir, Collections.singletonList(externalBackup)), "unknown ref rejected when index provided");
    }

    private static void testShareFileValidation() {
        File allowedBase = new File(System.getProperty("java.io.tmpdir"), "test_share");
        allowedBase.mkdirs();
        File safeFile = new File(allowedBase, "report.txt");

        // Null and empty checks
        check(!StoragePathGuard.isSafeShareFile(null, Collections.singletonList(allowedBase)), "null share path rejected");
        check(!StoragePathGuard.isSafeShareFile("", Collections.singletonList(allowedBase)), "empty share path rejected");
        check(!StoragePathGuard.isSafeShareFile("bad\0path", Collections.singletonList(allowedBase)), "NUL path rejected");

        // Private directory paths blocked
        check(!StoragePathGuard.isSafeShareFile("/data/data/com.bloatware/shared_prefs/prefs.xml", Collections.singletonList(allowedBase)), "shared_prefs path blocked");
        check(!StoragePathGuard.isSafeShareFile("/data/user/0/com.bloatware/databases/app.db", Collections.singletonList(allowedBase)), "databases path blocked");
        check(!StoragePathGuard.isSafeShareFile(new File(allowedBase, "test.xml").getAbsolutePath(), Collections.singletonList(allowedBase)), "xml extension blocked");
        check(!StoragePathGuard.isSafeShareFile(new File(allowedBase, "test.db").getAbsolutePath(), Collections.singletonList(allowedBase)), "db extension blocked");

        // Outside directory rejected
        File outside = new File(System.getProperty("java.io.tmpdir"), "unauthorized.txt");
        check(!StoragePathGuard.isSafeShareFile(outside.getAbsolutePath(), Collections.singletonList(allowedBase)), "file outside allowed directories rejected");

        // Safe file accepted
        check(StoragePathGuard.isSafeShareFile(safeFile.getAbsolutePath(), Collections.singletonList(allowedBase)), "safe file in allowed dir accepted");

        // Content URI accepted
        check(StoragePathGuard.isSafeShareFile("content://downloads/my_file", Collections.singletonList(allowedBase)), "content uri accepted");
    }
}
