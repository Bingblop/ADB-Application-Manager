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
 * script against a fake root (the absolute roots - {@code /data/app_fonts}, {@code /data/adb}, {@code /system/fonts},
 * {@code /data/fonts/files} and init's mount table {@code /proc/1/mountinfo} - are held in a {@link Roots} the test points at a temp
 * folder) with {@code chown}, {@code chcon}, {@code restorecon}, {@code nsenter}, {@code mount} and the rest stubbed on {@code PATH}. Every
 * path and name the page hands in is single-quoted with {@link BackupScripts#quote}, so a hostile suffix or file name reaches the shell as
 * text, never as shell code; a script's success is read back from the font files and {@code sans.loc} it printed, never from an exit text.
 *
 * <p>Three parts:
 * <ul>
 * <li>{@link #appliesSelfSigned} and {@link #styleIndex} - what One UI does with a self-signed font package, and which row Settings &gt; Font
 * style highlights.</li>
 * <li>The font changer - files go to {@code /data/app_fonts/<userId>/<suffix>/} and {@code sans.loc} points at them. Shizuku as the system
 * user (uid 1000) cannot read the app's private files, so each font arrives on stdin in its own call ({@link #writeFontScript}) and waits
 * beside the font in use as a {@code .new} file; a final call ({@link #installScript}) verifies the staged files, moves them into place
 * together, writes {@code sans.loc} atomically, sets ownership and the SELinux label, and prints a read-back {@link #parseApply} checks.</li>
 * <li>The system-emoji swap (root only, until the next restart) - the emoji font is staged under {@code /data/adb/adbmgr_fonts/} and
 * bind-mounted over every {@code und-Zsye} family's file in {@code /system/fonts} or {@code /product/fonts} ({@link #emojiFiles},
 * {@link #emojiSwapScript}), never over an updated font under {@code /data/fonts/files} ({@link #updatedEmojiFiles}). Init's mount table
 * tells the swap's own binds from another tool's: Undo takes off only its own ({@link #emojiUndoScript}), and the staged copy is deleted
 * once nothing is bound from it ({@link #removeStagedScript}). Nothing is written anywhere else, and a normal restart undoes it.</li>
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
        public final String dataFonts;    // /data/fonts/files - updatable (downloaded) system fonts, never bound over
        public final String mountinfo;    // /proc/1/mountinfo - init's mount table, which says which binds are the swap's own

        public Roots(String appFonts, String adb, String systemFonts, String productFonts, String dataFonts) {
            this(appFonts, adb, systemFonts, productFonts, dataFonts, "/proc/1/mountinfo");
        }

        public Roots(String appFonts, String adb, String systemFonts, String productFonts, String dataFonts, String mountinfo) {
            this.appFonts = stripSlash(appFonts);
            this.adb = stripSlash(adb);
            this.systemFonts = stripSlash(systemFonts);
            this.productFonts = stripSlash(productFonts);
            this.dataFonts = stripSlash(dataFonts);
            this.mountinfo = stripSlash(mountinfo);
        }

        /** The phone's real roots. */
        public static Roots system() {
            return new Roots("/data/app_fonts", "/data/adb", "/system/fonts", "/product/fonts", "/data/fonts/files");
        }

        /** The folder the emoji swap stages its font in; the only place the swap scripts create or change a file. */
        public String adbmgrFonts() { return adb + "/" + STAGING_DIR; }

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

    /** Whether a font package suffix is one the scripts take (a letter, then letters, digits or _; at most 64). */
    public static boolean isSuffix(String s) {
        return s != null && SUFFIX.matcher(s).matches();
    }

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
            M_END = "@@SF-END@@", M_AVAIL = "@@SF-AVAIL@@", M_BIND = "@@SF-BIND@@", M_UNDO = "@@SF-UNDO@@",
            M_ORIG = "@@SF-ORIG@@", M_OWN = "@@SF-OWN@@", M_MNT = "@@SF-MNT@@", M_MOUNT = "@@SF-MOUNT@@", M_STAGED = "@@SF-STAGED@@";
    static final String LABEL = "u:object_r:app_fonts_data_file:s0";
    static final String EMOJI_LABEL = "u:object_r:system_file:s0";
    /** The folder under /data/adb the swap stages its font in. A bind whose source is in it is the swap's own: no other tool uses this name. */
    static final String STAGING_DIR = "adbmgr_fonts";
    /** A staged font waits under this ending beside the font in use until {@link #installScript} moves it into place. */
    static final String NEW = ".new";

    // ---------------------------------------------------------------------------------------------
    // Font changer scripts
    // ---------------------------------------------------------------------------------------------

    /**
     * One font's bytes from stdin into {@code <appFonts>/<userId>/<suffix>/<fileName>.new}, through a {@code .part} that is checked against
     * the exact size and SHA-256 before it is renamed; a mismatch deletes the {@code .part} and prints an {@code ERROR} line (fail closed). The
     * {@code .new} waits beside the font in use: only {@link #installScript} moves the files into place, once every file of the font has
     * arrived, so a transfer cut short half way through a font never leaves a new Regular next to an old Bold in the folder {@code sans.loc}
     * may point at ({@link #discardScript} clears what such a transfer left). Shizuku as the system user runs one of these per file, feeding
     * the font on stdin, because it cannot read the app's private files.
     */
    public static String writeFontScript(Roots r, int userId, String suffix, String fileName, long size, String sha256) {
        requireUser(userId); requireSuffix(suffix); requireFontFile(fileName); requireSize(size); sha256 = requireSha(sha256);
        String dir = r.appFonts + "/" + userId + "/" + suffix;
        String part = dir + "/" + fileName + ".part";
        String dest = dir + "/" + fileName + NEW;
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

    /** {@link #installScript} for a font that leaves no file of an earlier build behind. */
    public static String installScript(Roots r, int userId, String suffix, String[] fileNames, String[] expectedShas) {
        return installScript(r, userId, suffix, fileNames, expectedShas, new String[0]);
    }

    /**
     * The final font-changer call (no stdin): checks every staged {@code .new} file is there and still matches its SHA-256, and only then
     * removes {@code removeNames} (a Bold an earlier build of the font left, when this one has none) and moves the staged files into place one
     * after the other in this one call, so the folder {@code sans.loc} may point at holds the old font or the new one, never part of each for
     * longer than two renames. It then writes {@code sans.loc} ({@code <suffix dir>#<suffix>}) through a temp file renamed into place, sets
     * {@code system:system} ownership, the file / directory modes (0711 on the user directory, 0755 on the suffix directory, 0644 on the files)
     * and the {@code app_fonts_data_file} SELinux label ({@code restorecon}, else {@code chcon}), then prints the read-back {@link #parseApply}
     * reads: {@code sans.loc}, {@code ls -lZ} and the files' hashes. {@code fileNames} and {@code expectedShas} line up one to one (Regular,
     * then Bold when present).
     */
    public static String installScript(Roots r, int userId, String suffix, String[] fileNames, String[] expectedShas, String[] removeNames) {
        requireUser(userId); requireSuffix(suffix);
        if (fileNames == null || expectedShas == null || fileNames.length != expectedShas.length || fileNames.length == 0)
            throw new IllegalArgumentException("a file name and a hash for each font");
        String[] shas = new String[expectedShas.length];   // normalized copy, so the caller's array is not changed
        for (int i = 0; i < fileNames.length; i++) { requireFontFile(fileNames[i]); shas[i] = requireSha(expectedShas[i]); }
        expectedShas = shas;
        List<String> written = Arrays.asList(fileNames);
        if (removeNames == null) removeNames = new String[0];
        for (String n : removeNames) if (written.contains(requireFontFile(n))) throw new IllegalArgumentException("a file is both written and removed");

        String userDir = r.appFonts + "/" + userId;
        String dir = userDir + "/" + suffix;
        String loc = userDir + "/sans.loc";
        String locTmp = loc + ".tmp";
        StringBuilder s = new StringBuilder();
        s.append("if [ ! -d ").append(q(r.appFonts)).append(" ]; then echo 'ERROR: this is not a Samsung phone (no /data/app_fonts).'; exit 1; fi\n");
        for (int i = 0; i < fileNames.length; i++) {
            String f = dir + "/" + fileNames[i] + NEW;
            s.append("if [ ! -f ").append(q(f)).append(" ]; then echo 'ERROR: ").append(fileNames[i]).append(" was not staged.'; exit 1; fi\n");
            s.append("s=$(sha256sum ").append(q(f)).append(" 2>/dev/null | cut -d' ' -f1)\n");
            s.append("if [ \"$s\" != '").append(expectedShas[i]).append("' ]; then echo 'ERROR: ").append(fileNames[i]).append(" changed after it was written.'; exit 1; fi\n");
        }
        // every file of the font is there and whole: only now does the font in use change
        for (String n : removeNames) {
            String f = dir + "/" + n;
            s.append("rm -f ").append(q(f)).append(" 2>/dev/null\n");
            s.append("if [ -e ").append(q(f)).append(" ]; then echo 'ERROR: the ").append(n).append(" of an earlier version could not be removed.'; exit 1; fi\n");
        }
        for (String n : fileNames) {
            String f = dir + "/" + n;
            s.append("mv -f ").append(q(f + NEW)).append(" ").append(q(f)).append(" || { echo 'ERROR: cannot put ").append(n).append(" in place.'; exit 1; }\n");
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

    /**
     * Clears what an Apply that stopped part way left in the app's own suffix directory: the {@code .new} and {@code .part} files of
     * {@code fileNames}, then the directory itself when that leaves it empty (a font that was never applied). The fonts in use are not
     * touched. Prints {@code present} / {@code gone} for each name, like {@link #removeSuffixScript}.
     */
    public static String discardScript(Roots r, int userId, String suffix, String[] fileNames) {
        requireUser(userId); requireSuffix(suffix);
        String dir = r.appFonts + "/" + userId + "/" + suffix;
        StringBuilder s = new StringBuilder();
        for (String n : fileNames) {
            String f = dir + "/" + requireFontFile(n);
            s.append("rm -f ").append(q(f + NEW)).append(" ").append(q(f + ".part")).append(" 2>/dev/null\n");
            s.append("if [ -e ").append(q(f + NEW)).append(" ] || [ -e ").append(q(f + ".part")).append(" ]; then echo '").append(M_BIND).append(" present' ")
             .append(q(n)).append("; else echo '").append(M_BIND).append(" gone' ").append(q(n)).append("; fi\n");
        }
        s.append("rmdir ").append(q(dir)).append(" 2>/dev/null\n");
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
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
     * exists). Never an updated font under {@code /data/fonts/files}: see {@link #updatedEmojiFiles}. Tolerant of damaged XML: it never
     * throws or hangs, and anything it does not recognise is left out.
     */
    public static List<String> emojiFiles(Roots r, String fontsXml, String fallbackXml) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String name : emojiFamilyFiles(fontsXml)) { out.add(r.systemFonts + "/" + name); out.add(r.productFonts + "/" + name); }
        for (String name : emojiFamilyFiles(fallbackXml)) { out.add(r.systemFonts + "/" + name); out.add(r.productFonts + "/" + name); }
        return new ArrayList<String>(out);
    }

    /**
     * The updated (downloaded) fonts under {@code /data/fonts/files} the phone draws its emoji from instead of an {@code und-Zsye} family's
     * own file: Android keeps an update as {@code <PostScript name>.ttf} and uses it in place of the fonts.xml entry with that PostScript name
     * (the entry's {@code postScriptName}, else its file name without the extension). The swap never binds over one: Android checks these
     * files with fs-verity, so an app that loads a bound one aborts, and a soft restart that finds one deletes every updated font. A bind over
     * the original in /system/fonts would not show either, so while this list is not empty the app does not swap. Empty when no emoji
     * family is updated.
     */
    public static List<String> updatedEmojiFiles(Roots r, String fontsXml, String fallbackXml, List<String> updatableFontFiles) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        List<String[]> fonts = new ArrayList<String[]>(emojiFamilyFonts(fontsXml));
        fonts.addAll(emojiFamilyFonts(fallbackXml));
        for (String[] f : fonts) {
            names.add(f[0].substring(0, f[0].length() - 4));                // NotoColorEmoji.ttf -> NotoColorEmoji
            if (f[1] != null && !f[1].isEmpty()) names.add(f[1]);
        }
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (updatableFontFiles != null) {
            for (String p : updatableFontFiles) {
                if (p == null) continue;
                String path = p.trim();
                if (path.isEmpty() || path.indexOf('\n') >= 0 || path.contains("..")) continue;
                if (!path.startsWith(r.dataFonts + "/")) continue;          // only the updatable-font tree
                String base = path.substring(path.lastIndexOf('/') + 1);
                int dot = base.lastIndexOf('.');
                if (dot > 0 && names.contains(base.substring(0, dot))) out.add(path);
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
     * init mount namespace ({@code nsenter -t 1 -m}), and reads back each target's hash before the bind ({@code @@SF-ORIG@@}, what Undo must
     * bring back) and after it. Every target must be a file in {@code /system/fonts} or {@code /product/fonts}, never an updated font under
     * {@code /data/fonts/files} ({@link #updatedEmojiFiles} says why). A target that is not there is skipped and said so, and one that already
     * carries a bind of the swap's own is left alone ({@code stacked}): a swap laid over another would leave the first one in place after
     * Undo. Nothing is bound when init's mount table cannot be read, since Undo could then not tell the swap's binds from another tool's.
     * The script writes nowhere but the bind targets, so a normal restart undoes the whole swap.
     */
    public static String emojiSwapScript(Roots r, String staged, List<String> targets) {
        String m = r.adbmgrFonts();
        if (staged == null || !staged.startsWith(m + "/") || staged.contains("..")) throw new IllegalArgumentException("the emoji font must be staged under adbmgr_fonts");
        List<String> t = checkedTargets(targets);
        for (String target : t) if (!isSwapTarget(r, target)) throw new IllegalArgumentException("the swap binds only over a font in /system/fonts or /product/fonts");
        StringBuilder s = new StringBuilder();
        appendMountFns(s, r);
        s.append("if ! mi_ok; then echo 'ERROR: the list of mounts could not be read.'; exit 1; fi\n");
        for (String target : t) {
            String e = q(mountinfoEscape(target));
            s.append("if [ ! -e ").append(q(target)).append(" ]; then echo '").append(M_BIND).append(" missing' ").append(q(target)).append("\n")
             .append("elif [ \"$(mi_own ").append(e).append(")\" != 0 ]; then echo '").append(M_BIND).append(" stacked' ").append(q(target)).append("\n")
             .append("else _o=$(nsenter -t 1 -m -- sha256sum ").append(q(target)).append(" 2>/dev/null | cut -d' ' -f1)\n")
             .append("  nsenter -t 1 -m -- mount -o bind ").append(q(staged)).append(" ").append(q(target))
             .append(" && echo '").append(M_BIND).append(" ok' ").append(q(target)).append(" || echo '").append(M_BIND).append(" fail' ").append(q(target)).append("\n")
             .append("  echo \"").append(M_ORIG).append(" $_o\"; echo '").append(M_SHA).append("'; nsenter -t 1 -m -- sha256sum ").append(q(target)).append(" 2>/dev/null\n")
             .append("fi\n");
        }
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /** A file directly in /system/fonts or /product/fonts: the only places the swap binds over (never under /data/fonts/files). */
    static boolean isSwapTarget(Roots r, String p) {
        if (p == null || p.contains("..") || p.startsWith(r.dataFonts + "/")) return false;
        for (String dir : new String[] { r.systemFonts, r.productFonts }) {
            if (p.length() > dir.length() + 1 && p.startsWith(dir + "/") && p.indexOf('/', dir.length() + 1) < 0) return true;
        }
        return false;
    }

    /**
     * Undoes the swap: at each target it takes off the top mount through the init mount namespace while that is a bind of the swap's own
     * (its source a staged font, read from init's mount table before each one), and never another tool's, such as a root module's own emoji
     * laid over the same file; when the table cannot be read nothing is taken off. A plain umount fails as busy while a process still has the
     * swapped file open or mapped (any app that loaded it may), so a lazy one follows: the path shows the file below again at once and the
     * apps that hold the swapped one keep it until they start again, as after the swap itself. It reads back how many binds of the swap's own
     * are left at the target ({@code @@SF-OWN@@}, {@code unknown} without the table) and the target's hash.
     */
    public static String emojiUndoScript(Roots r, List<String> targets) {
        List<String> t = checkedTargets(targets);
        StringBuilder s = new StringBuilder();
        appendMountFns(s, r);
        for (String target : t) {
            String e = q(mountinfoEscape(target));
            // a few rounds: two swaps stacked on one file (the first one's record lost) take two
            s.append("_i=0; while [ $_i -lt 4 ]; do case \"$(mi_top ").append(e).append(")\" in ").append(OWN).append(") ;; *) break;; esac; ")
             .append("nsenter -t 1 -m -- umount ").append(q(target)).append(" 2>/dev/null || nsenter -t 1 -m -- umount -l ").append(q(target)).append(" 2>/dev/null || break; ")
             .append("_i=$((_i+1)); done\n");
            s.append("echo '").append(M_UNDO).append("' ").append(q(target)).append("\n");
            s.append("if mi_ok; then echo \"").append(M_OWN).append(" $(mi_own ").append(e).append(")\"; else echo '").append(M_OWN).append(" unknown'; fi\n");
            s.append("echo '").append(M_SHA).append("'; nsenter -t 1 -m -- sha256sum ").append(q(target)).append(" 2>/dev/null\n");
        }
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /**
     * Lists the swap's own binds from init's mount table: {@code @@SF-MNT@@ yes} (or {@code no} when the table cannot be read, and then
     * nothing more), then one {@code @@SF-MOUNT@@ <mount point> <source>} line for each mount whose source is a staged font, both written
     * as the table writes them. It finds a swap whose record was lost (the app killed right after the bind, or its data cleared).
     * {@link #parseMounts} reads it.
     */
    public static String emojiMountsScript(Roots r) {
        StringBuilder s = new StringBuilder();
        appendMountFns(s, r);
        s.append("if mi_ok; then echo '").append(M_MNT).append(" yes'; ")
         .append("while read -r _a _b _c _r _m _x; do case \"$_r\" in ").append(OWN).append(") printf '%s %s %s\\n' '").append(M_MOUNT).append("' \"$_m\" \"$_r\";; esac; done < \"$MI\"; ")
         .append("else echo '").append(M_MNT).append(" no'; fi\n");
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /**
     * Deletes the staged copies {@code fileNames} in {@code /data/adb/adbmgr_fonts/} that nothing is bound from any more (init's mount table
     * shows no mount whose source is that file, as it is or deleted), each with its {@code .part}, then the folder when that leaves it empty,
     * and reads each copy back: {@code @@SF-STAGED@@ gone <name>} or {@code kept <name>}. A copy still bound stays, and so does every copy
     * when the table cannot be read. {@link #parseStaged} reads it.
     */
    public static String removeStagedScript(Roots r, List<String> fileNames) {
        String m = r.adbmgrFonts();
        List<String> names = new ArrayList<String>();
        if (fileNames != null) for (String n : fileNames) if (!names.contains(requireFontFile(n))) names.add(n);
        String in = "*/" + STAGING_DIR + "/";
        StringBuilder s = new StringBuilder();
        appendMountFns(s, r);
        s.append("_ok=0; mi_ok && _ok=1\n");
        for (String n : names) {
            String f = m + "/" + n;
            s.append("_u=1; if [ $_ok = 1 ]; then _u=0; while read -r _a _b _c _r _m _x; do case \"$_r\" in ").append(in).append(q(n));
            for (String d : DELETED) s.append("|").append(in).append(q(mountinfoEscape(n + d)));
            s.append(") _u=1;; esac; done < \"$MI\"; fi\n");
            s.append("[ $_u = 0 ] && rm -f ").append(q(f)).append(" ").append(q(f + ".part")).append(" 2>/dev/null\n");
            s.append("if [ -e ").append(q(f)).append(" ]; then echo '").append(M_STAGED).append(" kept' ").append(q(n))
             .append("; else echo '").append(M_STAGED).append(" gone' ").append(q(n)).append("; fi\n");
        }
        s.append("rmdir ").append(q(m)).append(" 2>/dev/null\n");
        s.append("echo '").append(M_END).append("'\n");
        return s.toString();
    }

    /** A soft restart: a separate, explicit action that closes every app and reloads the fonts. */
    public static String softRestartScript() {
        return "setprop ctl.restart zygote\n";
    }

    /** The pattern a bind's source matches when it is a staged font of the swap (the table writes it from its own file system's root). */
    private static final String OWN = "*/" + STAGING_DIR + "/*";

    /**
     * What the table writes after the source of a bind whose file was deleted while bound: "//deleted" (the kernel's dentry_path, which
     * prints this field). " (deleted)", the form of {@code /proc/<pid>/maps} and fd links, is accepted too, should a kernel ever write it here.
     */
    private static final String[] DELETED = { "//deleted", " (deleted)" };

    /**
     * Shell functions over init's mount table, whose fourth field is the path a mount shows from its own file system and fifth the mount
     * point, both with space, tab, newline and backslash as octal escapes ({@link #mountinfoEscape}). {@code mi_ok} answers whether the table
     * can be read; {@code mi_top E} prints the source of the top mount at the escaped mount point E ("" when there is none: the table lists
     * mounts in the order they were made, so the last one there is on top); {@code mi_own E} counts the binds of the swap's own there.
     */
    private static void appendMountFns(StringBuilder s, Roots r) {
        s.append("MI=").append(q(r.mountinfo)).append("\n");
        s.append("mi_ok() { read -r _l 2>/dev/null < \"$MI\"; }\n");
        s.append("mi_top() { _t=''; while read -r _a _b _c _r _m _x; do [ \"$_m\" = \"$1\" ] && _t=\"$_r\"; done < \"$MI\"; printf '%s' \"$_t\"; }\n");
        s.append("mi_own() { _n=0; while read -r _a _b _c _r _m _x; do if [ \"$_m\" = \"$1\" ]; then case \"$_r\" in ").append(OWN)
         .append(") _n=$((_n+1));; esac; fi; done < \"$MI\"; echo \"$_n\"; }\n");
    }

    /** A path as init's mount table writes it: space, tab, newline and backslash as a backslash and three octal digits. */
    static String mountinfoEscape(String p) {
        StringBuilder b = new StringBuilder(p.length() + 8);
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\\') b.append('\\').append((char) ('0' + (c >> 6))).append((char) ('0' + ((c >> 3) & 7))).append((char) ('0' + (c & 7)));
            else b.append(c);
        }
        return b.toString();
    }

    /** {@link #mountinfoEscape} undone: each backslash and three octal digits back to its character. */
    static String mountinfoUnescape(String f) {
        StringBuilder b = new StringBuilder(f.length());
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '\\' && i + 3 < f.length() && isOctal(f.charAt(i + 1)) && isOctal(f.charAt(i + 2)) && isOctal(f.charAt(i + 3))) {
                b.append((char) (((f.charAt(i + 1) - '0') * 64 + (f.charAt(i + 2) - '0') * 8 + (f.charAt(i + 3) - '0')) & 0xff));
                i += 3;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static boolean isOctal(char c) { return c >= '0' && c <= '7'; }

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

    /**
     * One target's result from an emoji swap or undo: the target, the state (ok / fail / missing / stacked / undone), the hash read back,
     * the hash it had before a swap's bind (null otherwise), and after an undo how many binds of the swap's own are left there (-1 when not
     * known).
     */
    public static final class BindResult {
        public final String target, state, sha256, original;
        public final int own;
        BindResult(String target, String state, String sha256, String original, int own) {
            this.target = target; this.state = state; this.sha256 = sha256; this.original = original; this.own = own;
        }

        /**
         * Whether an undo brought this target back: no bind of the swap's own is left there, its hash was read, and it is the hash the file had
         * before the swap when that is known ({@code originalSha}, from the swap's record).
         */
        public boolean back(String originalSha) {
            return "undone".equals(state) && own == 0 && sha256 != null && (originalSha == null || originalSha.equals(sha256));
        }
    }

    /** Parses the read-back of {@link #emojiSwapScript} or {@link #emojiUndoScript}. */
    public static List<BindResult> parseSwap(String out) {
        List<String[]> marks = new ArrayList<String[]>();   // {state, target}
        List<String> shas = new ArrayList<String>(), origs = new ArrayList<String>();
        List<Integer> owns = new ArrayList<Integer>();
        String[] lines = splitLines(out);
        boolean expectSha = false;
        for (String line : lines) {
            String tr = line.trim();
            if (tr.startsWith(M_BIND + " ")) {
                String rest = tr.substring(M_BIND.length() + 1);
                int sp = rest.indexOf(' ');
                if (sp > 0) marks.add(new String[] { rest.substring(0, sp), rest.substring(sp + 1) });
                else marks.add(new String[] { rest, "" });
                shas.add(null); origs.add(null); owns.add(-1); expectSha = true;
            } else if (tr.startsWith(M_UNDO + " ")) {
                marks.add(new String[] { "undone", tr.substring(M_UNDO.length() + 1) });
                shas.add(null); origs.add(null); owns.add(-1); expectSha = true;
            } else if (tr.startsWith(M_ORIG) && !marks.isEmpty()) {
                String h = tr.substring(M_ORIG.length()).trim().toLowerCase(Locale.ROOT);
                if (SHA256.matcher(h).matches()) origs.set(origs.size() - 1, h);
            } else if (tr.startsWith(M_OWN + " ") && !marks.isEmpty()) {
                String v = tr.substring(M_OWN.length() + 1).trim();
                owns.set(owns.size() - 1, v.matches("[0-9]{1,4}") ? Integer.parseInt(v) : -1);
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
        for (int i = 0; i < marks.size(); i++) out2.add(new BindResult(marks.get(i)[1], marks.get(i)[0], shas.get(i), origs.get(i), owns.get(i)));
        return out2;
    }

    /** The swap's own binds in init's mount table, read back from {@link #emojiMountsScript}. */
    public static final class Mounts {
        /** The table was read. When it was not, nothing may be taken off: the swap's binds cannot be told from another tool's. */
        public final boolean readable;
        /** Every mount point that carries a bind of the swap's own, in the table's order, each once. */
        public final List<String> targets;
        /** The staged file each of those binds shows ({@code <catalog id>.ttf}, also when it was deleted while bound), in the table's order: the last is the newest. */
        public final List<String> staged;

        Mounts(boolean readable, List<String> targets, List<String> staged) {
            this.readable = readable;
            this.targets = java.util.Collections.unmodifiableList(targets);
            this.staged = java.util.Collections.unmodifiableList(staged);
        }

        /** The name without {@code .ttf} of the staged font the newest bind shows (a catalog id), or null when there is no bind. */
        public String showing() {
            if (staged.isEmpty()) return null;
            String n = staged.get(staged.size() - 1);
            return n.endsWith(".ttf") ? n.substring(0, n.length() - 4) : n;
        }
    }

    /** Parses the read-back of {@link #emojiMountsScript}. Tolerant: a line it does not recognise is left out. */
    public static Mounts parseMounts(String out) {
        boolean readable = false;
        List<String> targets = new ArrayList<String>(), staged = new ArrayList<String>();
        for (String line : splitLines(out)) {
            String tr = line.trim();
            if (tr.startsWith(M_MNT + " ")) { readable = tr.equals(M_MNT + " yes"); continue; }
            if (!tr.startsWith(M_MOUNT + " ")) continue;
            String[] f = tr.substring(M_MOUNT.length() + 1).split(" ");
            if (f.length != 2 || !f[1].contains("/" + STAGING_DIR + "/")) continue;
            String mp = mountinfoUnescape(f[0]);
            if (mp.isEmpty() || mp.charAt(0) != '/' || mp.indexOf('\n') >= 0 || mp.contains("..")) continue;
            String src = mountinfoUnescape(f[1]);
            for (String d : DELETED) if (src.endsWith(d)) { src = src.substring(0, src.length() - d.length()); break; }
            if (!targets.contains(mp)) targets.add(mp);
            staged.add(src.substring(src.lastIndexOf('/') + 1));
        }
        if (!readable) { targets.clear(); staged.clear(); }
        return new Mounts(readable, targets, staged);
    }

    /** Each staged copy {@link #removeStagedScript} read back: true when it is gone, false when it is still there. */
    public static java.util.Map<String, Boolean> parseStaged(String out) {
        java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<String, Boolean>();
        for (String line : splitLines(out)) {
            String tr = line.trim();
            if (!tr.startsWith(M_STAGED + " ")) continue;
            String rest = tr.substring(M_STAGED.length() + 1);
            int sp = rest.indexOf(' ');
            if (sp > 0) m.put(rest.substring(sp + 1).trim(), "gone".equals(rest.substring(0, sp)));
        }
        return m;
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
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String[] f : emojiFamilyFonts(xml)) out.add(f[0]);
        return new ArrayList<String>(out);
    }

    /** The fonts of every {@code und-Zsye} family as {file name, {@code postScriptName} attribute or null}, each pair once; see {@link #emojiFamilyFiles}. */
    static List<String[]> emojiFamilyFonts(String xml) {
        List<String[]> out = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
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
                    if (XML_FONT_FILE.matcher(name).matches()) {
                        String ps = attr(xml.substring(fo, fte), "postScriptName");
                        if (seen.add(name + "\u0000" + ps)) out.add(new String[] { name, ps });
                    }
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
