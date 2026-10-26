package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Samsung's font rules and the privileged shell scripts the Custom Fonts tab's font changer and system-emoji swap run.
 *
 * <p>This is a pure string builder and parser, free of Android classes, so it is tested off the device: a real {@code sh} runs every
 * script against a fake root (the four absolute roots - {@code /data/app_fonts}, {@code /data/adb}, {@code /system/fonts},
 * {@code /data/fonts/files} - are held in a {@link Roots} the test points at a temp folder) with {@code chown}, {@code chcon},
 * {@code restorecon}, {@code nsenter}, {@code mount} and the rest stubbed on {@code PATH}. Every path and name the page hands in is
 * single-quoted with {@link BackupScripts#quote}, so a hostile suffix or file name reaches the shell as text, never as shell code; a
 * script's success is read back from the font files and {@code sans.loc} it printed, never from an exit text.
 *
 * <p>Three parts:
 * <ul>
 * <li>{@link #appliesSelfSigned} and {@link #styleIndex} - what One UI does with a self-signed font package, and which row Settings &gt; Font
 * style highlights.</li>
 * <li>The font changer - files go to {@code /data/app_fonts/<userId>/<suffix>/} and {@code sans.loc} points at them. Shizuku as the system
 * user (uid 1000) cannot read the app's private files, so each font arrives on stdin in its own call ({@link #writeFontScript}); a final
 * call ({@link #installScript}) verifies the staged files, writes {@code sans.loc} atomically, sets ownership and the SELinux label, and
 * prints a read-back {@link #parseApply} checks.</li>
 * <li>The system-emoji swap (root only, until the next restart) - the emoji font is staged under {@code /data/adb/adbmgr_fonts/} and
 * bind-mounted over every {@code und-Zsye} family's file ({@link #emojiFiles}, {@link #emojiSwapScript}); nothing is written anywhere
 * else, and a normal restart undoes it.</li>
 * </ul>
 */
public final class SamsungFonts {

    private SamsungFonts() {}

    /** One UI 8.5 and newer (value {@code major*100 + minor*10}, so 8.5 is 850) refuse a self-signed font package in Settings. */
    public static final int ONE_UI_8_5 = 850;
    /** One UI 8.0, from which the self-signed restriction reaches 8.0..8.4 with a March 2026 or newer security patch. */
    public static final int ONE_UI_8_0 = 800;

    /** Where the font changer and the emoji swap write. The test builds one pointing into a temp folder; {@link #system} is the phone's. */
    public static final class Roots {
        public final String appFonts;     // /data/app_fonts  - the FlipFont cache the renderer reads
        public final String adb;          // /data/adb        - the emoji font is staged under <adb>/adbmgr_fonts
        public final String systemFonts;  // /system/fonts    - the und-Zsye emoji files to bind over
        public final String productFonts; // /product/fonts   - the same on phones whose emoji font ships in the product partition
        public final String dataFonts;    // /data/fonts/files - updatable (downloaded) system fonts

        public Roots(String appFonts, String adb, String systemFonts, String productFonts, String dataFonts) {
            this.appFonts = stripSlash(appFonts);
            this.adb = stripSlash(adb);
            this.systemFonts = stripSlash(systemFonts);
            this.productFonts = stripSlash(productFonts);
            this.dataFonts = stripSlash(dataFonts);
        }

        /** The phone's real roots. */
        public static Roots system() {
            return new Roots("/data/app_fonts", "/data/adb", "/system/fonts", "/product/fonts", "/data/fonts/files");
        }

        /** The folder the emoji swap stages its font in; the only place the swap scripts create or change a file. */
        public String adbmgrFonts() { return adb + "/adbmgr_fonts"; }

        private static String stripSlash(String p) {
            if (p == null || p.isEmpty()) return "";
            int e = p.length();
            while (e > 1 && p.charAt(e - 1) == '/') e--;
            return p.substring(0, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // One UI rules
    // ---------------------------------------------------------------------------------------------

    /** The One UI version as {@code major*100 + minor*10} (6.1 -&gt; 610, 8.5 -&gt; 850, 9.0 -&gt; 900) from {@code ro.build.version.oneui} (80500 -&gt; 850); 0 when it could not be read. */
    public static int oneUiTimes100(int rawOneUi) {
        if (rawOneUi < 10000 || rawOneUi > 999999) return 0;
        int major = rawOneUi / 10000, minor = (rawOneUi / 100) % 100;
        return major * 100 + minor * 10;
    }

    /**
     * Whether Settings &gt; Font style on this One UI applies a self-signed font package (one signed by the app, not by Samsung DMC or
     * Monotype): true on older One UI, false on 8.5 and newer, and false on 8.0..8.4 once the security patch is March 2026 or newer. An
     * unknown One UI ({@code oneUiTimes100 <= 0}), or an 8.0..8.4 phone whose patch cannot be read, answers false: the page then says the
     * package may install but not appear in Settings, rather than promising it will.
     */
    public static boolean appliesSelfSigned(int oneUiTimes100, String securityPatch) {
        if (oneUiTimes100 <= 0) return false;
        if (oneUiTimes100 >= ONE_UI_8_5) return false;
        if (oneUiTimes100 >= ONE_UI_8_0) return knownBeforeMarch2026(securityPatch);
        return true;
    }

    /** True only when the patch string positively reads as a date before 2026-03-01; an unreadable or missing patch is not "before". */
    static boolean knownBeforeMarch2026(String patch) {
        if (patch == null) return false;
        String p = patch.trim();
        if (p.length() < 7 || p.charAt(4) != '-') return false;
        int year = digits(p, 0, 4), month = digits(p, 5, 7);
        if (year < 0 || month < 1 || month > 12) return false;
        return year < 2026 || (year == 2026 && month < 3);
    }

    private static int digits(String s, int from, int to) {
        if (to > s.length()) return -1;
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /** The preloaded FlipFont suffixes Samsung lists first, in this order, when they are installed. */
    static final String[] PRELOADED = {"foundation", "roboto", "samsungone"};

    /**
     * The row Settings &gt; Font style highlights for {@code target}: 0 for Default (also when {@code target} is null, {@code "default"} or
     * not installed), otherwise {@code 1 +} the package's place in Samsung's list - the preloaded foundation, roboto and samsungone first
     * (those that are installed), then the rest of the installed {@code com.monotype.android.font.*} packages in alphabetical order, their
     * true order being unknown. The index is cosmetic (which row looks selected); the font itself is applied by {@code sans.loc}.
     */
    public static int styleIndex(List<String> installedFontPackages, String target) {
        if (target == null) return 0;
        String t = target.trim();
        if (t.isEmpty() || t.equals("default") || t.equals("default#default")) return 0;

        LinkedHashSet<String> installed = new LinkedHashSet<String>();
        if (installedFontPackages != null) for (String p : installedFontPackages) if (p != null && !p.trim().isEmpty()) installed.add(p.trim());

        List<String> ordered = new ArrayList<String>();
        for (String pre : PRELOADED) {
            for (String p : installed) if (suffixOf(p).equals(pre) && !ordered.contains(p)) ordered.add(p);
        }
        List<String> rest = new ArrayList<String>();
        for (String p : installed) if (!ordered.contains(p)) rest.add(p);
        java.util.Collections.sort(rest);
        ordered.addAll(rest);

        int i = ordered.indexOf(t);
        return i < 0 ? 0 : i + 1;
    }

    /** The part of a FlipFont package after {@code com.monotype.android.font.}, or the whole string when it has no such prefix. */
    static String suffixOf(String pkg) {
        return pkg.startsWith(FontPackage.PACKAGE_PREFIX) ? pkg.substring(FontPackage.PACKAGE_PREFIX.length()) : pkg;
    }

    // ---------------------------------------------------------------------------------------------
    // Validation of the names and values that go into a script
    // ---------------------------------------------------------------------------------------------

    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern FONT_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.ttf");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static String requireSuffix(String s) {
        if (s == null || !SUFFIX.matcher(s).matches()) throw new IllegalArgumentException("invalid font package suffix");
        return s;
    }

    private static String requireFontFile(String s) {
        if (s == null || !FONT_FILE.matcher(s).matches()) throw new IllegalArgumentException("invalid font file name");
        return s;
    }

    private static String requireSha(String s) {
        if (s == null || !SHA256.matcher(s.toLowerCase(Locale.ROOT)).matches()) throw new IllegalArgumentException("invalid sha-256");
        return s.toLowerCase(Locale.ROOT);
    }

    private static long requireSize(long size) {
        if (size < 0 || size > Sfnt.MAX_FONT_BYTES) throw new IllegalArgumentException("invalid font size");
        return size;
    }

    private static int requireUser(int userId) {
        if (userId < 0 || userId > 100000) throw new IllegalArgumentException("invalid user id");
        return userId;
    }

    private static String q(String s) { return BackupScripts.quote(s); }

    // Marker lines the scripts print and the parsers read. Unlikely in a path, a context or a hash.
    static final String M_WROTE = "@@SF-WROTE@@", M_LOC = "@@SF-LOC@@", M_LS = "@@SF-LS@@", M_SHA = "@@SF-SHA@@",
            M_END = "@@SF-END@@", M_AVAIL = "@@SF-AVAIL@@", M_BIND = "@@SF-BIND@@", M_UNDO = "@@SF-UNDO@@";
    static final String LABEL = "u:object_r:app_fonts_data_file:s0";
    static final String EMOJI_LABEL = "u:object_r:system_file:s0";

    // ---------------------------------------------------------------------------------------------
    // Font changer scripts
    // ---------------------------------------------------------------------------------------------

    /**
     * One font's bytes from stdin into {@code <appFonts>/<userId>/<suffix>/<fileName>}, through a {@code .part} that is checked against the
     * exact size and SHA-256 before it is moved into place; a mismatch deletes the {@code .part} and prints an {@code ERROR} line (fail
     * closed), so a short or corrupt transfer never replaces the font. Shizuku as the system user runs one of these per file, feeding the
     * font on stdin, because it cannot read the app's private files.
     */
    public static String writeFontScript(Roots r, int userId, String suffix, String fileName, long size, String sha256) {
        requireUser(userId); requireSuffix(suffix); requireFontFile(fileName); requireSize(size); sha256 = requireSha(sha256);
        String dir = r.appFonts + "/" + userId + "/" + suffix;
        String part = dir + "/" + fileName + ".part";
        String dest = dir + "/" + fileName;
        StringBuilder s = new StringBuilder();
        s.append("mkdir -p ").append(q(dir)).append(" || { echo 'ERROR: cannot create the font folder'; exit 1; }\n");
        s.append("cat > ").append(q(part)).append(" || { rm -f ").append(q(part)).append("; echo 'ERROR: cannot write the font'; exit 1; }\n");
        s.append("got=$(wc -c < ").append(q(part)).append(" 2>/dev/null | tr -d ' ')\n");
        s.append("if [ \"$got\" != '").append(size).append("' ]; then rm -f ").append(q(part))
         .append("; echo \"ERROR: the font arrived the wrong size ($got bytes, expected ").append(size).append(")\"; exit 1; fi\n");
        s.append("sum=$(sha256sum ").append(q(part)).append(" 2>/dev/null | cut -d' ' -f1)\n");
        s.append("if [ \"$sum\" != '").append(sha256).append("' ]; then rm -f ").append(q(part))
         .append("; echo 'ERROR: the font did not match its checksum'; exit 1; fi\n");
        s.append("mv -f ").append(q(part)).append(" ").append(q(dest)).append(" || { rm -f ").append(q(part)).append("; echo 'ERROR: cannot store the font'; exit 1; }\n");
        s.append("chmod 0644 ").append(q(dest)).append(" 2>/dev/null\n");
        s.append("echo \"").append(M_WROTE).append(" ").append(fileName).append(" $got $sum\"\n");
        return s.toString();
    }

    /**
     * The final font-changer call (no stdin): checks every staged file is there and still matches its SHA-256, writes {@code sans.loc}
     * ({@code <suffix dir>#<suffix>}) through a temp file renamed into place, sets {@code system:system} ownership, the file / directory
     * modes (0711 on the user directory, 0755 on the suffix directory, 0644 on the files) and the {@code app_fonts_data_file} SELinux label
     * ({@code restorecon}, else {@code chcon}), then prints the read-back {@link #parseApply} reads: {@code sans.loc}, {@code ls -lZ} and the
     * files' hashes. {@code fileNames} and {@code expectedShas} line up one to one (Regular, then Bold when present).
     */
    public static String installScript(Roots r, int userId, String suffix, String[] fileNames, String[] expectedShas) {
        requireUser(userId); requireSuffix(suffix);
        if (fileNames == null || expectedShas == null || fileNames.length != expectedShas.length || fileNames.length == 0)
            throw new IllegalArgumentException("a file name and a hash for each font");
        String[] shas = new String[expectedShas.length];   // normalized copy, so the caller's array is not changed
        for (int i = 0; i < fileNames.length; i++) { requireFontFile(fileNames[i]); shas[i] = requireSha(expectedShas[i]); }
        expectedShas = shas;

        String userDir = r.appFonts + "/" + userId;
        String dir = userDir + "/" + suffix;
        String loc = userDir + "/sans.loc";
        String locTmp = loc + ".tmp";
        StringBuilder s = new StringBuilder();
        s.append("if [ ! -d ").append(q(r.appFonts)).append(" ]; then echo 'ERROR: this is not a Samsung phone (no /data/app_fonts).'; exit 1; fi\n");
        for (int i = 0; i < fileNames.length; i++) {
            String f = dir + "/" + fileNames[i];
            s.append("if [ ! -f ").append(q(f)).append(" ]; then echo 'ERROR: ").append(fileNames[i]).append(" was not staged.'; exit 1; fi\n");
            s.append("s=$(sha256sum ").append(q(f)).append(" 2>/dev/null | cut -d' ' -f1)\n");
            s.append("if [ \"$s\" != '").append(expectedShas[i]).append("' ]; then echo 'ERROR: ").append(fileNames[i]).append(" changed after it was written.'; exit 1; fi\n");
        }
        // sans.loc: the suffix directory, then '#', then the suffix. Reset writes default#default.
        s.append("printf '%s' ").append(q(dir + "#" + suffix)).append(" > ").append(q(locTmp))
         .append(" && mv -f ").append(q(locTmp)).append(" ").append(q(loc))
         .append(" || { rm -f ").append(q(locTmp)).append("; echo 'ERROR: cannot write sans.loc.'; exit 1; }\n");
        s.append("chown -R system:system ").append(q(userDir)).append(" 2>/dev/null\n");
        s.append("chmod 0711 ").append(q(userDir)).append(" 2>/dev/null\n");
        s.append("chmod 0755 ").append(q(dir)).append(" 2>/dev/null\n");
        for (String f : fileNames) s.append("chmod 0644 ").append(q(dir + "/" + f)).append(" 2>/dev/null\n");
        s.append("chmod 0644 ").append(q(loc)).append(" 2>/dev/null\n");
        s.append("restorecon -RF ").append(q(userDir)).append(" 2>/dev/null || chcon -R ").append(LABEL).append(" ").append(q(userDir)).append(" 2>/dev/null\n");
        appendReadBack(s, loc, dir, fileNames);
        return s.toString();
    }

    /** Resets the font changer: {@code sans.loc} back to {@code default#default} (atomic temp + move), ownership and label restored, read back. */
    public static String resetScript(Roots r, int userId) {
        requireUser(userId);
        String userDir = r.appFonts + "/" + userId;
        String loc = userDir + "/sans.loc";
        String locTmp = loc + ".tmp";
        StringBuilder s = new StringBuilder();
        s.append("if [ ! -d ").append(q(r.appFonts)).append(" ]; then echo 'ERROR: this is not a Samsung phone (no /data/app_fonts).'; exit 1; fi\n");
        s.append("mkdir -p ").append(q(userDir)).append(" 2>/dev/null\n");
        s.append("printf '%s' 'default#default' > ").append(q(locTmp))
         .append(" && mv -f ").append(q(locTmp)).append(" ").append(q(loc))
         .append(" || { rm -f ").append(q(locTmp)).append("; echo 'ERROR: cannot reset sans.loc.'; exit 1; }\n");
        s.append("chown system:system ").append(q(loc)).append(" 2>/dev/null\n");
        s.append("chmod 0644 ").append(q(loc)).append(" 2>/dev/null\n");
        s.append("restorecon -F ").append(q(loc)).append(" 2>/dev/null || chcon ").append(LABEL).append(" ").append(q(loc)).append(" 2>/dev/null\n");
        s.append("echo '").append(M_LOC).append("'; cat ").append(q(loc)).append(" 2>/dev/null; echo\n");
        s.append("echo \"").append(M_END).append("$?\"\n");
        return s.toString();
    }

    /** Removes only the app's own suffix directory (never another app's), used when a library entry is deleted. The directory is quoted and the suffix validated. */
    public static String removeSuffixScript(Roots r, int userId, String suffix) {
        requireUser(userId); requireSuffix(suffix);
        String dir = r.appFonts + "/" + userId + "/" + suffix;
        return "rm -rf " + q(dir) + " 2>/dev/null\n"
                + "if [ -d " + q(dir) + " ]; then echo '" + M_BIND + " present'; else echo '" + M_BIND + " gone'; fi\n"
                + "echo '" + M_END + "'\n";
    }

    /** Reads the current state: whether {@code /data/app_fonts} exists, {@code sans.loc} and the suffix directories present. */
    public static String statusScript(Roots r, int userId) {
        requireUser(userId);
        String userDir = r.appFonts + "/" + userId;
        String loc = userDir + "/sans.loc";
        StringBuilder s = new StringBuilder();
        s.append("if [ -d ").append(q(r.appFonts)).append(" ]; then echo '").append(M_AVAIL).append(" yes'; else echo '").append(M_AVAIL).append(" no'; fi\n");
        s.append("echo '").append(M_LOC).append("'; cat ").append(q(loc)).append(" 2>/dev/null; echo\n");
        s.append("echo '").append(M_LS).append("'; ls ").append(q(userDir)).append(" 2>/dev/null\n");
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    private static void appendReadBack(StringBuilder s, String loc, String dir, String[] fileNames) {
        s.append("echo '").append(M_LOC).append("'; cat ").append(q(loc)).append(" 2>/dev/null; echo\n");
        s.append("echo '").append(M_LS).append("'; ls -lZ ").append(q(dir)).append(" 2>/dev/null\n");
        s.append("echo '").append(M_SHA).append("'\n");
        for (String f : fileNames) s.append("sha256sum ").append(q(dir + "/" + f)).append(" 2>/dev/null\n");
        s.append("echo \"").append(M_END).append("$?\"\n");
    }

    // ---------------------------------------------------------------------------------------------
    // System emoji swap (root, until restart)
    // ---------------------------------------------------------------------------------------------

    /**
     * The emoji files the swap replaces: every {@code und-Zsye} family's font in {@code fonts.xml} and {@code font_fallback.xml} (Android
     * 15+ keeps the fallback in its own file), resolved to {@code /system/fonts} and {@code /product/fonts} (the swap binds over whichever
     * exists), plus the updatable (downloaded) font files whose name contains "Emoji". Tolerant of damaged XML: it never throws or hangs,
     * and anything it does not recognise is left out.
     */
    public static List<String> emojiFiles(Roots r, String fontsXml, String fallbackXml, List<String> updatableFontFiles) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String name : emojiFamilyFiles(fontsXml)) { out.add(r.systemFonts + "/" + name); out.add(r.productFonts + "/" + name); }
        for (String name : emojiFamilyFiles(fallbackXml)) { out.add(r.systemFonts + "/" + name); out.add(r.productFonts + "/" + name); }
        if (updatableFontFiles != null) {
            for (String p : updatableFontFiles) {
                if (p == null) continue;
                String path = p.trim();
                if (path.isEmpty() || path.indexOf('\n') >= 0 || path.contains("..")) continue;
                if (!path.startsWith(r.dataFonts + "/")) continue;          // only the updatable-font tree
                String base = path.substring(path.lastIndexOf('/') + 1);
                if (base.contains("Emoji") || base.contains("emoji")) out.add(path);
            }
        }
        return new ArrayList<String>(out);
    }

    /** Stages the emoji font from stdin under {@code /data/adb/adbmgr_fonts/<fileName>} (size + SHA-256 checked, {@code system_file} label) - the only place the swap writes a file. */
    public static String stageEmojiScript(Roots r, String fileName, long size, String sha256) {
        requireFontFile(fileName); requireSize(size); sha256 = requireSha(sha256);
        String m = r.adbmgrFonts();
        String part = m + "/" + fileName + ".part";
        String dest = m + "/" + fileName;
        StringBuilder s = new StringBuilder();
        s.append("mkdir -p ").append(q(m)).append(" || { echo 'ERROR: cannot create the staging folder'; exit 1; }\n");
        s.append("chmod 0755 ").append(q(m)).append(" 2>/dev/null\n");
        s.append("cat > ").append(q(part)).append(" || { rm -f ").append(q(part)).append("; echo 'ERROR: cannot write the emoji font'; exit 1; }\n");
        s.append("got=$(wc -c < ").append(q(part)).append(" 2>/dev/null | tr -d ' ')\n");
        s.append("if [ \"$got\" != '").append(size).append("' ]; then rm -f ").append(q(part))
         .append("; echo \"ERROR: the emoji font arrived the wrong size ($got bytes, expected ").append(size).append(")\"; exit 1; fi\n");
        s.append("sum=$(sha256sum ").append(q(part)).append(" 2>/dev/null | cut -d' ' -f1)\n");
        s.append("if [ \"$sum\" != '").append(sha256).append("' ]; then rm -f ").append(q(part))
         .append("; echo 'ERROR: the emoji font did not match its checksum'; exit 1; fi\n");
        s.append("mv -f ").append(q(part)).append(" ").append(q(dest)).append(" || { rm -f ").append(q(part)).append("; echo 'ERROR: cannot stage the emoji font'; exit 1; }\n");
        s.append("chmod 0644 ").append(q(dest)).append(" 2>/dev/null\n");
        s.append("chcon ").append(EMOJI_LABEL).append(" ").append(q(dest)).append(" 2>/dev/null\n");
        s.append("echo \"").append(M_WROTE).append(" ").append(fileName).append(" $got $sum\"\n");
        return s.toString();
    }

    /**
     * Bind-mounts the staged emoji font ({@code staged}, which must be under {@code /data/adb/adbmgr_fonts}) over each target through the
     * init mount namespace ({@code nsenter -t 1 -m}), and reads back each target's hash. A target that is not there is skipped and said so;
     * the script writes nowhere but the bind targets, so a normal restart undoes the whole swap.
     */
    public static String emojiSwapScript(Roots r, String staged, List<String> targets) {
        String m = r.adbmgrFonts();
        if (staged == null || !staged.startsWith(m + "/") || staged.contains("..")) throw new IllegalArgumentException("the emoji font must be staged under adbmgr_fonts");
        List<String> t = checkedTargets(targets);
        StringBuilder s = new StringBuilder();
        for (String target : t) {
            s.append("if [ -e ").append(q(target)).append(" ]; then ")
             .append("nsenter -t 1 -m -- mount -o bind ").append(q(staged)).append(" ").append(q(target))
             .append(" && echo '").append(M_BIND).append(" ok' ").append(q(target)).append(" || echo '").append(M_BIND).append(" fail' ").append(q(target)).append("; ")
             .append("echo '").append(M_SHA).append("'; nsenter -t 1 -m -- sha256sum ").append(q(target)).append(" 2>/dev/null; ")
             .append("else echo '").append(M_BIND).append(" missing' ").append(q(target)).append("; fi\n");
        }
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /** Undoes the swap: unmounts each target through the init mount namespace and reads its hash back. */
    public static String emojiUndoScript(List<String> targets) {
        List<String> t = checkedTargets(targets);
        StringBuilder s = new StringBuilder();
        for (String target : t) {
            s.append("nsenter -t 1 -m -- umount ").append(q(target)).append(" 2>/dev/null; ")
             .append("echo '").append(M_UNDO).append("' ").append(q(target)).append("; ")
             .append("echo '").append(M_SHA).append("'; nsenter -t 1 -m -- sha256sum ").append(q(target)).append(" 2>/dev/null\n");
        }
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /** A soft restart: a separate, explicit action that closes every app and reloads the fonts. */
    public static String softRestartScript() {
        return "setprop ctl.restart zygote\n";
    }

    /** The targets of a swap, checked: each an absolute path, no newline, no {@code ..}, no embedded single quote worry (it is quoted anyway). */
    private static List<String> checkedTargets(List<String> targets) {
        List<String> out = new ArrayList<String>();
        if (targets == null) return out;
        for (String t : targets) {
            if (t == null) continue;
            String p = t.trim();
            if (p.isEmpty() || p.charAt(0) != '/' || p.indexOf('\n') >= 0 || p.contains("..")) continue;
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsers for the scripts' read-back
    // ---------------------------------------------------------------------------------------------

    /** One file's read-back from a font the renderer caches: name, size, SHA-256 and SELinux label as the install read-back printed them. */
    public static final class FileInfo {
        public final String name;
        public final long size;     // -1 when ls did not print it
        public final String sha256; // null when not read back
        public final String label;  // the SELinux context, or null

        FileInfo(String name, long size, String sha256, String label) {
            this.name = name; this.size = size; this.sha256 = sha256; this.label = label;
        }
    }

    /** What {@link #installScript} / {@link #resetScript} did, read back from what the script printed, not its exit text. */
    public static final class ApplyResult {
        /** The whole read-back arrived (the end marker) and no ERROR line was printed. */
        public final boolean ok;
        /** The first {@code ERROR:} sentence, or "". */
        public final String error;
        /** The contents of {@code sans.loc}, or "" when it could not be read. */
        public final String sansLoc;
        public final List<FileInfo> files;

        ApplyResult(boolean ok, String error, String sansLoc, List<FileInfo> files) {
            this.ok = ok; this.error = error; this.sansLoc = sansLoc; this.files = files;
        }
    }

    /** Parses the read-back of {@link #installScript} or {@link #resetScript}. Tolerant: unexpected or missing lines never throw. */
    public static ApplyResult parseApply(String out) {
        String error = firstError(out);
        boolean end = false;
        String sansLoc = "";
        List<String[]> ls = new ArrayList<String[]>();   // {name,size,label}
        java.util.LinkedHashMap<String, String> shas = new java.util.LinkedHashMap<String, String>();
        String[] lines = splitLines(out);
        int section = 0;                                  // 0 none, 1 loc, 2 ls, 3 sha
        for (String line : lines) {
            String tr = line.trim();
            if (tr.startsWith(M_END)) { end = true; section = 0; continue; }
            if (tr.equals(M_LOC)) { section = 1; sansLoc = ""; continue; }
            if (tr.equals(M_LS)) { section = 2; continue; }
            if (tr.equals(M_SHA)) { section = 3; continue; }
            if (tr.startsWith("@@SF-")) { section = 0; continue; }
            if (section == 1) { sansLoc = sansLoc.isEmpty() ? line : sansLoc + "\n" + line; }
            else if (section == 2) { String[] f = parseLsLine(line); if (f != null) ls.add(f); }
            else if (section == 3) { String[] f = parseShaLine(line); if (f != null) shas.put(f[0], f[1]); }
        }
        sansLoc = sansLoc.trim();

        java.util.LinkedHashMap<String, FileInfo> byName = new java.util.LinkedHashMap<String, FileInfo>();
        for (String[] f : ls) {
            long size = -1;
            try { size = Long.parseLong(f[1]); } catch (NumberFormatException ignored) {}
            byName.put(f[0], new FileInfo(f[0], size, shas.get(f[0]), f[2]));
        }
        for (java.util.Map.Entry<String, String> e : shas.entrySet())
            if (!byName.containsKey(e.getKey())) byName.put(e.getKey(), new FileInfo(e.getKey(), -1, e.getValue(), null));

        boolean ok = end && error.isEmpty();
        return new ApplyResult(ok, error, sansLoc, new ArrayList<FileInfo>(byName.values()));
    }

    /** The current state, read back from {@link #statusScript}. */
    public static final class Status {
        /** {@code /data/app_fonts} is there: this is a Samsung phone with the FlipFont cache. */
        public final boolean available;
        /** {@code sans.loc}'s contents, or "". */
        public final String sansLoc;
        /** The suffix now applied (the part after {@code #}), or null for Default / unreadable. */
        public final String currentSuffix;
        /** The names in the user's app-fonts directory. */
        public final List<String> entries;

        Status(boolean available, String sansLoc, String currentSuffix, List<String> entries) {
            this.available = available; this.sansLoc = sansLoc; this.currentSuffix = currentSuffix; this.entries = entries;
        }
    }

    /** Parses the read-back of {@link #statusScript}. Tolerant of noise and missing sections. */
    public static Status parseStatus(String out) {
        boolean available = false;
        String sansLoc = "";
        List<String> entries = new ArrayList<String>();
        String[] lines = splitLines(out);
        int section = 0;                                  // 1 loc, 2 ls
        for (String line : lines) {
            String tr = line.trim();
            if (tr.startsWith(M_AVAIL)) { available = tr.endsWith("yes"); section = 0; continue; }
            if (tr.equals(M_LOC)) { section = 1; sansLoc = ""; continue; }
            if (tr.equals(M_LS)) { section = 2; continue; }
            if (tr.startsWith("@@SF-")) { section = 0; continue; }
            if (section == 1) { sansLoc = sansLoc.isEmpty() ? line : sansLoc + "\n" + line; }
            else if (section == 2 && !tr.isEmpty()) entries.add(tr);
        }
        sansLoc = sansLoc.trim();
        String suffix = currentSuffixOf(sansLoc);
        return new Status(available, sansLoc, suffix, entries);
    }

    /** The suffix a {@code sans.loc} content applies ({@code <dir>#<suffix>}), or null for {@code default#default}, empty or unreadable. */
    public static String currentSuffixOf(String sansLoc) {
        if (sansLoc == null) return null;
        String s = sansLoc.trim();
        int h = s.lastIndexOf('#');
        if (h < 0) return null;
        String suffix = s.substring(h + 1).trim();
        if (suffix.isEmpty() || suffix.equals("default")) return null;
        return suffix;
    }

    /** One target's result from an emoji swap or undo: the target, the state (ok / fail / missing / undone) and the hash read back. */
    public static final class BindResult {
        public final String target, state, sha256;
        BindResult(String target, String state, String sha256) { this.target = target; this.state = state; this.sha256 = sha256; }
    }

    /** Parses the read-back of {@link #emojiSwapScript} or {@link #emojiUndoScript}. */
    public static List<BindResult> parseSwap(String out) {
        List<String[]> marks = new ArrayList<String[]>();   // {state, target}
        List<String> shas = new ArrayList<String>();
        String[] lines = splitLines(out);
        boolean expectSha = false;
        for (String line : lines) {
            String tr = line.trim();
            if (tr.startsWith(M_BIND + " ")) {
                String rest = tr.substring(M_BIND.length() + 1);
                int sp = rest.indexOf(' ');
                if (sp > 0) marks.add(new String[] { rest.substring(0, sp), rest.substring(sp + 1) });
                else marks.add(new String[] { rest, "" });
                shas.add(null); expectSha = true;
            } else if (tr.startsWith(M_UNDO + " ")) {
                marks.add(new String[] { "undone", tr.substring(M_UNDO.length() + 1) });
                shas.add(null); expectSha = true;
            } else if (tr.equals(M_SHA)) {
                expectSha = true;
            } else if (tr.startsWith("@@SF-")) {
                expectSha = false;
            } else if (expectSha && !tr.isEmpty() && !marks.isEmpty()) {
                String[] sp = parseShaLine(line);
                if (sp != null) shas.set(shas.size() - 1, sp[1]);
                expectSha = false;
            }
        }
        List<BindResult> out2 = new ArrayList<BindResult>();
        for (int i = 0; i < marks.size(); i++) out2.add(new BindResult(marks.get(i)[1], marks.get(i)[0], shas.get(i)));
        return out2;
    }

    /** The WROTE line of {@link #writeFontScript} / {@link #stageEmojiScript}: ok and the hash it read back, or the ERROR sentence. */
    public static final class WriteResult {
        public final boolean ok;
        public final String error, sha256;
        public final long size;
        WriteResult(boolean ok, String error, String sha256, long size) { this.ok = ok; this.error = error; this.sha256 = sha256; this.size = size; }
    }

    /** Parses {@link #writeFontScript} / {@link #stageEmojiScript} output for the given file name. */
    public static WriteResult parseWrite(String out, String fileName) {
        String error = firstError(out);
        if (!error.isEmpty()) return new WriteResult(false, error, null, -1);
        for (String line : splitLines(out)) {
            String tr = line.trim();
            if (tr.startsWith(M_WROTE + " ")) {
                String[] p = tr.substring(M_WROTE.length() + 1).split("\\s+");
                if (p.length >= 3 && p[0].equals(fileName)) {
                    long size = -1;
                    try { size = Long.parseLong(p[1]); } catch (NumberFormatException ignored) {}
                    return new WriteResult(true, "", p[2], size);
                }
            }
        }
        return new WriteResult(false, "The device gave no answer.", null, -1);
    }

    // ---------------------------------------------------------------------------------------------
    // Small parsing helpers (tolerant, bounded)
    // ---------------------------------------------------------------------------------------------

    private static String firstError(String out) {
        if (out == null) return "";
        for (String line : splitLines(out)) {
            String tr = line.trim();
            if (tr.startsWith("ERROR:")) return tr.substring("ERROR:".length()).trim();
            if (tr.equals("ERROR")) return "The command failed.";
        }
        return "";
    }

    private static String[] splitLines(String out) {
        if (out == null || out.isEmpty()) return new String[0];
        return out.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /** A line of {@code ls -lZ}: {name, size, label}. The label is the token with an SELinux context, the size the integer after it, the name the last token. */
    private static String[] parseLsLine(String line) {
        String tr = line.trim();
        if (tr.isEmpty()) return null;
        String[] tok = tr.split("\\s+");
        if (tok.length < 2) return null;
        int labelIdx = -1;
        for (int i = 0; i < tok.length; i++) if (tok[i].contains(":object_r:") || tok[i].contains(":r:")) { labelIdx = i; break; }
        String label = labelIdx >= 0 ? tok[labelIdx] : null;
        String size = "-1";
        if (labelIdx >= 0 && labelIdx + 1 < tok.length && tok[labelIdx + 1].matches("[0-9]+")) size = tok[labelIdx + 1];
        String name = tok[tok.length - 1];
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
        return new String[] { name, size, label };
    }

    /** A line of {@code sha256sum}: {basename, hash}. */
    private static String[] parseShaLine(String line) {
        String tr = line.trim();
        if (tr.isEmpty()) return null;
        int sp = tr.indexOf(' ');
        if (sp != 64) {                                   // "<64 hex>  <path>"; be lenient if the hash is first otherwise
            String[] tk = tr.split("\\s+");
            if (tk.length < 2 || !SHA256.matcher(tk[0].toLowerCase(Locale.ROOT)).matches()) return null;
            String n = tk[tk.length - 1];
            int s = n.lastIndexOf('/');
            return new String[] { s >= 0 ? n.substring(s + 1) : n, tk[0].toLowerCase(Locale.ROOT) };
        }
        String hash = tr.substring(0, 64).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(hash).matches()) return null;
        String path = tr.substring(64).trim();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return new String[] { name, hash };
    }

    // ---------------------------------------------------------------------------------------------
    // The und-Zsye emoji families of a fonts.xml / font_fallback.xml (a small, tolerant parser)
    // ---------------------------------------------------------------------------------------------

    private static final int MAX_XML = 8 * 1024 * 1024;   // a real fonts.xml is ~70 KB; stop well before a hostile one can cost memory
    private static final int MAX_FAMILIES = 20000, MAX_FONTS = 100000;
    private static final Pattern XML_FONT_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(ttf|otf|ttc)");

    /**
     * The font file names of every {@code <family lang="...und-Zsye...">} in the XML (skipping {@code ignore="true"} legacy families). A
     * hand-written scanner, not a DOM parser: it never resolves entities or a DTD, so XML billion-laughs / external-entity tricks cannot
     * reach it, and it is bounded in input length, families and fonts so a malformed document cannot hang it.
     */
    static List<String> emojiFamilyFiles(String xml) {
        List<String> out = new ArrayList<String>();
        if (xml == null || xml.isEmpty()) return out;
        int len = Math.min(xml.length(), MAX_XML);
        int pos = 0, families = 0, fonts = 0;
        while (pos < len && families < MAX_FAMILIES && fonts < MAX_FONTS) {
            int open = xml.indexOf("<family", pos);
            if (open < 0 || open >= len) break;
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0 || tagEnd >= len) break;
            String tag = xml.substring(open, tagEnd);
            boolean selfClose = tag.endsWith("/");
            families++;
            String lang = attr(tag, "lang");
            String ignore = attr(tag, "ignore");
            boolean isZsye = lang != null && hasLangToken(lang, "und-Zsye");
            boolean ignored = ignore != null && ignore.equalsIgnoreCase("true");
            if (selfClose) { pos = tagEnd + 1; continue; }
            int close = xml.indexOf("</family>", tagEnd);
            int bodyEnd = close < 0 ? len : Math.min(close, len);
            if (isZsye && !ignored) {
                int fp = tagEnd + 1;
                while (fp < bodyEnd && fonts < MAX_FONTS) {
                    int fo = xml.indexOf("<font", fp);
                    if (fo < 0 || fo >= bodyEnd) break;
                    int fte = xml.indexOf('>', fo);
                    if (fte < 0 || fte >= bodyEnd) break;
                    if (xml.charAt(fte - 1) == '/') { fp = fte + 1; continue; }   // <font .../> has no file text
                    int fc = xml.indexOf("</font>", fte);
                    int textEnd = fc < 0 ? bodyEnd : Math.min(fc, bodyEnd);
                    String name = xml.substring(fte + 1, textEnd).trim();
                    fonts++;
                    if (XML_FONT_FILE.matcher(name).matches() && !out.contains(name)) out.add(name);
                    fp = fc < 0 ? bodyEnd : fc + "</font>".length();
                }
            }
            pos = close < 0 ? tagEnd + 1 : close + "</family>".length();
        }
        return out;
    }

    /** The value of {@code name="..."} (or {@code name='...'}) in an opening-tag substring, or null. */
    private static String attr(String tag, String name) {
        int i = 0;
        while (true) {
            int at = tag.indexOf(name, i);
            if (at < 0) return null;
            // the attribute name must be at a word boundary and followed (after spaces) by '='
            boolean leftOk = at == 0 || isSpace(tag.charAt(at - 1));
            int j = at + name.length();
            while (j < tag.length() && isSpace(tag.charAt(j))) j++;
            if (leftOk && j < tag.length() && tag.charAt(j) == '=') {
                j++;
                while (j < tag.length() && isSpace(tag.charAt(j))) j++;
                if (j >= tag.length()) return null;
                char quote = tag.charAt(j);
                if (quote != '"' && quote != '\'') { i = at + name.length(); continue; }
                int end = tag.indexOf(quote, j + 1);
                if (end < 0) return null;
                return tag.substring(j + 1, end);
            }
            i = at + name.length();
        }
    }

    /** {@code lang} is a space- or comma-separated list of BCP-47 tags; true when {@code token} is one of them. */
    private static boolean hasLangToken(String lang, String token) {
        for (String part : lang.split("[\\s,]+")) if (part.equals(token)) return true;
        return false;
    }

    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
}
