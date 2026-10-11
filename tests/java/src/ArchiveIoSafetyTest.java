package com.bloatware.bingblop;

public class ArchiveIoSafetyTest {

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
        testSafeEntryNameDetection();
        testEntryNameSanitization();

        if (failed > 0) {
            System.err.println("ArchiveIoSafetyTest: " + failed + " of " + checks + " checks failed");
            System.exit(1);
        } else {
            System.out.println("PASS ArchiveIoSafetyTest: " + checks + " checks");
            System.exit(0);
        }
    }

    private static void testSafeEntryNameDetection() {
        // Valid paths
        check(ArchiveIo.isSafeEntryName("foo.txt"), "plain filename is safe");
        check(ArchiveIo.isSafeEntryName("assets/images/logo.png"), "subfolder relative path is safe");
        check(ArchiveIo.isSafeEntryName("data/app/test/"), "directory path is safe");

        // Traversal attempts
        check(!ArchiveIo.isSafeEntryName("../secret.txt"), "leading dot-dot rejected");
        check(!ArchiveIo.isSafeEntryName("dir/../../secret.txt"), "embedded dot-dot rejected");
        check(!ArchiveIo.isSafeEntryName("foo/bar/.."), "trailing dot-dot rejected");
        check(!ArchiveIo.isSafeEntryName(".."), "lone dot-dot rejected");
        check(!ArchiveIo.isSafeEntryName("/etc/passwd"), "absolute unix path rejected");
        check(!ArchiveIo.isSafeEntryName("\\Windows\\System32"), "absolute windows backslash path rejected");
        check(!ArchiveIo.isSafeEntryName("C:\\boot.ini"), "windows drive letter rejected");
        check(!ArchiveIo.isSafeEntryName("D:/files"), "windows drive forward-slash rejected");

        // Edge cases
        check(!ArchiveIo.isSafeEntryName(null), "null rejected");
        check(!ArchiveIo.isSafeEntryName(""), "empty string rejected");
        check(!ArchiveIo.isSafeEntryName("foo\0bar.txt"), "NUL byte rejected");
    }

    private static void testEntryNameSanitization() {
        // Valid sanitization
        check("foo.txt".equals(ArchiveIo.safeEntryName("foo.txt")), "clean file unchanged");
        check("assets/logo.png".equals(ArchiveIo.safeEntryName("assets/logo.png")), "clean subfolder unchanged");
        check("dir/".equals(ArchiveIo.safeEntryName("dir/")), "folder trailing slash preserved");

        // Stripping leading slashes
        check("etc/passwd".equals(ArchiveIo.safeEntryName("/etc/passwd")), "leading slash stripped");
        check("etc/passwd".equals(ArchiveIo.safeEntryName("///etc/passwd")), "multiple leading slashes stripped");

        // Normalizing backslashes
        check("dir/file.txt".equals(ArchiveIo.safeEntryName("dir\\file.txt")), "backslashes normalized");

        // Escaping root traversal
        check(ArchiveIo.safeEntryName("../evil.txt") == null, "escaping root returns null");
        check(ArchiveIo.safeEntryName("a/../../evil.txt") == null, "escaping root from subfolder returns null");
        check("file.txt".equals(ArchiveIo.safeEntryName("dir/../file.txt")), "safe internal dot-dot resolved");

        // NUL byte rejection
        check(ArchiveIo.safeEntryName("foo\0bar") == null, "NUL byte returns null");
        check(ArchiveIo.safeEntryName(null) == null, "null returns null");
    }
}
