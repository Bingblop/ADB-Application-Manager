package com.bloatware.bingblop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell scripts and parsers for app backup and restore. No Android dependencies, so the scripts can be
 * run against a fake /data tree on a normal Linux machine.
 *
 * App data is only reachable as Root: the scripts run through `su -c`. A backup can come from any file
 * the user picks, so the restore script refuses archives that could write outside the app's own folders
 * (other paths, "..", hard links, or links pointing elsewhere) before it touches anything.
 */
final class BackupScripts {

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z][A-Za-z0-9_.]*");
    private static final Pattern APP_OP = Pattern.compile("^([A-Z][A-Z0-9_]+):\\s*(allow|ignore|deny|foreground)\\b", Pattern.MULTILINE);
    private static final Pattern SESSION = Pattern.compile("\\[(\\d+)\\]");

    private BackupScripts() {}

    static boolean isPackageName(String pkg) {
        return pkg != null && PACKAGE.matcher(pkg).matches();
    }

    static String checkedPackage(String pkg) {
        if (!isPackageName(pkg)) throw new IllegalArgumentException("invalid package name");
        return pkg;
    }

    static boolean isPermission(String s) {
        return s != null && PERMISSION.matcher(s).matches();
    }

    /** Single-quotes a value for sh. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Root script: archives the app's data folders into outFile and hands the file to ownerUid. Prints OK on success. */
    static String dataBackup(String dataRoot, String pkg, String outFile, int ownerUid) {
        checkedPackage(pkg);
        return ""
            + "cd " + quote(dataRoot) + " || { echo 'ERROR: cannot open the data folder'; exit 2; }\n"
            + "am force-stop " + pkg + " >/dev/null 2>&1\n"
            + "paths=''\n"
            + "[ -d user/0/" + pkg + " ] && paths=\"$paths user/0/" + pkg + "\"\n"
            + "[ -d user_de/0/" + pkg + " ] && paths=\"$paths user_de/0/" + pkg + "\"\n"
            + "[ -n \"$paths\" ] || { echo 'ERROR: " + pkg + " has no data folder'; exit 3; }\n"
            + "rm -f " + quote(outFile) + "\n"
            + "tar -cf " + quote(outFile) + " $paths >/dev/null 2>&1; rc=$?\n"
            + "[ -s " + quote(outFile) + " ] || { echo \"ERROR: tar failed ($rc)\"; exit 4; }\n"
            + "chown " + ownerUid + ":" + ownerUid + " " + quote(outFile) + " && chmod 600 " + quote(outFile) + "\n"
            + "[ $rc -eq 0 ] || echo \"WARN: tar exited with $rc\"\n"
            + "echo OK\n";
    }

    /**
     * Root script: replaces the app's data with the archive. The app must already be installed (its folder
     * gives the uid). Everything is checked before anything is deleted. Prints OK on success.
     */
    static String dataRestore(String dataRoot, String pkg, String tarFile) {
        checkedPackage(pkg);
        String re = "^(user/0|user_de/0)/" + pkg.replace(".", "\\.") + "(/|$)";
        String t = quote(tarFile);
        return ""
            + "cd " + quote(dataRoot) + " || { echo 'ERROR: cannot open the data folder'; exit 2; }\n"
            + "uid=$(stat -c %u user/0/" + pkg + " 2>/dev/null)\n"
            + "[ -n \"$uid\" ] || { echo 'ERROR: " + pkg + " is not installed for this user'; exit 2; }\n"
            + "[ -s " + t + " ] || { echo 'ERROR: the backup has no data'; exit 3; }\n"
            + "tar -tf " + t + " >/dev/null 2>&1 || { echo 'ERROR: the data archive is damaged'; exit 5; }\n"
            // Only this app's own folders, nothing with a parent-directory step, no hard links,
            // and links only when they point inside (relative, without ..)
            + "if tar -tf " + t + " | grep -v -E '" + re + "' | grep -q .; then echo 'ERROR: the archive has files outside this app'; exit 6; fi\n"
            + "if tar -tf " + t + " | grep -q -E '(^|/)\\.\\.(/|$)'; then echo 'ERROR: the archive has unsafe paths'; exit 6; fi\n"
            + "if tar -tvf " + t + " | grep -q -E '^h'; then echo 'ERROR: the archive has hard links'; exit 6; fi\n"
            + "if tar -tvf " + t + " | grep -E '^l' | grep -q -E -- ' -> (/|.*\\.\\.)'; then echo 'ERROR: the archive has links that point outside the app'; exit 6; fi\n"
            + "am force-stop " + pkg + " >/dev/null 2>&1\n"
            + "for d in user/0/" + pkg + " user_de/0/" + pkg + "; do [ -d \"$d\" ] && find \"$d\" -mindepth 1 -maxdepth 1 -exec rm -rf {} \\; ; done\n"
            + "tar -xf " + t + " -C " + quote(dataRoot) + " >/dev/null 2>&1; rc=$?\n"
            + "[ $rc -eq 0 ] || { echo \"ERROR: extracting failed ($rc)\"; exit 4; }\n"
            + "for d in user/0/" + pkg + " user_de/0/" + pkg + "; do if [ -d \"$d\" ]; then chown -R $uid:$uid \"$d\"; restorecon -RF \"$d\" >/dev/null 2>&1; fi; done\n"
            + "echo OK\n";
    }

    /** "Success: created install session [1234]" -> "1234", or null. */
    static String parseSessionId(String output) {
        if (output == null) return null;
        Matcher m = SESSION.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /** From `cmd appops get <pkg>`: the ops the user changed away from allow (deny / ignore / foreground). */
    static Map<String, String> changedAppOps(String output) {
        Map<String, String> ops = new LinkedHashMap<String, String>();
        if (output == null) return ops;
        Matcher m = APP_OP.matcher(output);
        while (m.find()) {
            if (!"allow".equals(m.group(2))) ops.put(m.group(1), m.group(2));
        }
        return ops;
    }
}
