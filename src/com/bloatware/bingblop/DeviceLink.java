package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sending packages to another device through adb (the Connected Devices tab): one APK, the splits of an app that is installed on this phone, or a
 * package file (APKS, APKM, XAPK, or a zip of APKs) with the splits the device needs and its OBB / data files. The adb itself is a callback, so the
 * rules are tested against a fake. No Android classes.
 */
public final class DeviceLink {
    private DeviceLink() {}

    /** Runs adb for the chosen device with these arguments (after "-s serial") and gives what it printed. */
    public interface Adb {
        String run(List<String> args, int timeoutMs);
    }

    /** index of total, the item's name, and a line about what is happening. */
    public interface Progress {
        void step(int index, int total, String name, String line);
    }

    private static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]{1,120}");

    /** An adb serial as adb lists it: letters, digits and . _ : - [ ] only (an IP:port, a USB serial, a "[fe80::1]:5555"). */
    public static boolean validSerial(String s) {
        return s != null && SERIAL.matcher(s).matches() && !s.startsWith("-");
    }

    /** What adb answered to an install: it is done when it says Success and nothing about a failure. */
    public static boolean installed(String out) {
        if (out == null) return false;
        String o = out.toLowerCase(Locale.US);
        return o.contains("success") && !o.contains("failure") && !o.contains("exception") && !o.contains("error:") && !o.contains("[process timed out");
    }

    static String baseName(String path) {
        String n = path == null ? "" : path.replace('\\', '/');
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static List<String> listOf(JSONArray a) {
        List<String> l = new ArrayList<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i, ""));
        return l;
    }

    /** The `adb install` arguments for these files: install for one file, install-multiple for the splits of one app. */
    static List<String> installArgs(List<String> files, boolean reinstall, boolean downgrade, boolean grantAll, boolean testOk) {
        List<String> a = new ArrayList<String>();
        a.add(files.size() > 1 ? "install-multiple" : "install");
        if (reinstall) a.add("-r");
        if (downgrade) a.add("-d");
        if (grantAll) a.add("-g");
        if (testOk) a.add("-t");
        a.addAll(files);
        return a;
    }

    /** The extra files of a package file that go to the device's storage (Android/obb/..., Android/data/...), as entry names. */
    static boolean isExtraEntry(String name) {
        String n = name.replace('\\', '/');
        return !n.endsWith("/") && (n.startsWith("Android/obb/") || n.startsWith("Android/data/")) && !n.contains("..");
    }

    private static void copy(InputStream in, File dest) throws Exception {
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            out.close();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /**
     * items: [{name, paths:[apk files]} | {name, archive:file}]. opts: {reinstall (default true), downgrade, grantAll, testOk}.
     * profile: {abis:["arm64-v8a",...], dpi, lang} of the device. Answer: {ok, results:[{name, ok, out, files}]}.
     */
    public static JSONObject install(File stageRoot, JSONArray items, JSONObject opts, JSONObject profile, Adb adb, Progress progress) {
        JSONObject res = new JSONObject();
        JSONArray results = new JSONArray();
        boolean all = true;
        try {
            boolean reinstall = opts == null || opts.optBoolean("reinstall", true);
            boolean downgrade = opts != null && opts.optBoolean("downgrade", false);
            boolean grantAll = opts != null && opts.optBoolean("grantAll", false);
            boolean testOk = opts != null && opts.optBoolean("testOk", false);
            List<String> abis = profile == null ? new ArrayList<String>() : listOf(profile.optJSONArray("abis"));
            int dpi = profile == null ? 0 : profile.optInt("dpi", 0);
            String lang = profile == null ? "" : profile.optString("lang", "");
            int total = items.length();
            for (int i = 0; i < total; i++) {
                JSONObject it = items.getJSONObject(i);
                String name = it.optString("name", "package");
                JSONObject r = new JSONObject();
                r.put("name", name);
                File stage = null;
                try {
                    List<String> files = new ArrayList<String>();
                    List<String[]> extras = new ArrayList<String[]>();          // {entry name, extracted file}
                    if (it.has("archive")) {
                        if (progress != null) progress.step(i, total, name, "Reading the package file");
                        stage = new File(stageRoot, "cd" + System.nanoTime());
                        stage.mkdirs();
                        ZipFile z = new ZipFile(it.getString("archive"));
                        try {
                            List<String> apkNames = new ArrayList<String>();
                            Enumeration<? extends ZipEntry> en = z.entries();
                            while (en.hasMoreElements()) {
                                ZipEntry e = en.nextElement();
                                if (e.isDirectory()) continue;
                                if (e.getName().toLowerCase(Locale.US).endsWith(".apk") && !e.getName().contains("..")) apkNames.add(baseName(e.getName()));
                            }
                            List<String> keep = SplitPick.pick(apkNames, abis, dpi, lang);
                            if (keep.isEmpty()) throw new Exception("There is no APK in this package file.");
                            en = z.entries();
                            int k = 0;
                            while (en.hasMoreElements()) {
                                ZipEntry e = en.nextElement();
                                if (e.isDirectory()) continue;
                                String bn = baseName(e.getName());
                                if (e.getName().toLowerCase(Locale.US).endsWith(".apk") && !e.getName().contains("..") && keep.contains(bn)) {
                                    File f = new File(stage, (k++) + "_" + bn);
                                    copy(z.getInputStream(e), f);
                                    files.add(f.getAbsolutePath());
                                    keep.remove(bn);                                        // two entries of one name: the first only
                                } else if (isExtraEntry(e.getName())) {
                                    File f = new File(stage, "x" + (k++));
                                    copy(z.getInputStream(e), f);
                                    extras.add(new String[]{e.getName().replace('\\', '/'), f.getAbsolutePath()});
                                }
                            }
                        } finally {
                            z.close();
                        }
                    } else {
                        files.addAll(listOf(it.optJSONArray("paths")));
                    }
                    if (files.isEmpty()) throw new Exception("Nothing to install.");
                    if (progress != null) progress.step(i, total, name, "Installing " + files.size() + (files.size() == 1 ? " file" : " files"));
                    String out = adb.run(installArgs(files, reinstall, downgrade, grantAll, testOk), 600000);
                    boolean ok = installed(out);
                    r.put("files", files.size());
                    StringBuilder log = new StringBuilder(out == null ? "" : out.trim());
                    if (ok) {
                        for (String[] x : extras) {
                            if (progress != null) progress.step(i, total, name, "Copying " + baseName(x[0]));
                            String dir = x[0].substring(0, x[0].lastIndexOf('/'));
                            adb.run(java.util.Arrays.asList("shell", "mkdir", "-p", "'/sdcard/" + dir.replace("'", "") + "'"), 30000);
                            String pushed = adb.run(java.util.Arrays.asList("push", x[1], "/sdcard/" + x[0]), 3600000);
                            log.append("\n").append(baseName(x[0])).append(": ").append(pushed == null ? "" : pushed.trim());
                            if (pushed == null || pushed.toLowerCase(Locale.US).contains("error") || pushed.toLowerCase(Locale.US).contains("failed")) ok = false;
                        }
                    }
                    r.put("ok", ok);
                    r.put("out", log.toString());
                } catch (Exception e) {
                    r.put("ok", false);
                    r.put("out", e.getMessage() == null ? "Failed" : e.getMessage());
                } finally {
                    if (stage != null) deleteTree(stage);
                }
                if (!r.optBoolean("ok")) all = false;
                results.put(r);
            }
            res.put("ok", all);
            res.put("results", results);
        } catch (Exception e) {
            try { res.put("ok", false); res.put("error", e.getMessage()); res.put("results", results); } catch (Exception ignored) {}
        }
        return res;
    }
}
