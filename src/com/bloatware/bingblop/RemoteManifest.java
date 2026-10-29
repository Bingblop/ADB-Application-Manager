package com.bloatware.bingblop;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The AndroidManifest.xml of an app on a connected device (the Connected Devices tab's app details, v7.19.3): first only the manifest, straight out of
 * the APK on the device (adb exec-out unzip -p, read as bytes: Android 9 and later carry unzip), else the whole APK pulled into this app's cache and
 * read here. The adb and the decoder are callbacks, so the order is tested against fakes. No Android classes.
 */
public final class RemoteManifest {
    private RemoteManifest() {}

    /** adb for one device ("-s serial" is among the arguments): its output as bytes (null when it failed or went past max), or as text. */
    public interface Adb {
        byte[] bytes(List<String> args, int timeoutMs, int max);
        String run(List<String> args, int timeoutMs);
    }

    /** Binary XML to text (ManifestDecoder), from bytes or from an APK file. */
    public interface Decoder {
        String fromBytes(byte[] data) throws Exception;
        String fromApk(String path) throws Exception;
    }

    /** As large as ManifestDecoder reads. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int READ_MS = 60000;
    static final int PULL_MS = 30 * 60000;

    // what pm path prints for an APK: /data/app/~~a1B2==/com.example-c3D4==/base.apk, /system/priv-app/Name/Name.apk ... (no quote, space or shell sign)
    private static final Pattern APK_PATH = Pattern.compile("/[A-Za-z0-9._~=+@,/-]{1,500}\\.apk");

    /** A path pm path can give for an APK, safe inside single quotes on the device's shell and as one argument of adb pull. */
    public static boolean validApkPath(String p) {
        return p != null && APK_PATH.matcher(p).matches() && !p.contains("..") && !p.contains("//");
    }

    /** The start of compiled XML: chunk type RES_XML_TYPE (0x0003) with an 8-byte header. */
    public static boolean isBinaryXml(byte[] b) {
        return b != null && b.length >= 8 && b[0] == 0x03 && b[1] == 0x00 && b[2] == 0x08 && b[3] == 0x00
                && java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4) == b.length;
    }

    /** The decoded manifest, or "Error: ..." in words. The pulled copy is deleted again whatever happens. */
    public static String read(Adb adb, String serial, String apkPath, File cacheDir, Decoder dec) {
        if (!DeviceLink.validSerial(serial)) return "Error: That device name is not valid.";
        if (!validApkPath(apkPath)) return "Error: The device gave an APK path this app does not read: " + apkPath;
        byte[] b = adb.bytes(Arrays.asList("-s", serial, "exec-out", "unzip -p '" + apkPath + "' AndroidManifest.xml"), READ_MS, MAX_BYTES);
        if (isBinaryXml(b)) {
            try {
                return dec.fromBytes(b);
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
        // no unzip on the device (before Android 9), or it could not read the APK: the APK comes here
        File f = new File(cacheDir, "cd-manifest.apk");
        f.delete();
        try {
            String out = adb.run(new ArrayList<String>(Arrays.asList("-s", serial, "pull", apkPath, f.getPath())), PULL_MS);
            if (!f.isFile() || f.length() == 0) {
                String first = out == null ? "" : out.trim().split("\n")[0].replaceFirst("^(adb(\\.exe)?: )?(error: )?", "");
                return "Error: " + (first.isEmpty() ? "The APK could not be copied from the device." : first);
            }
            try {
                return dec.fromApk(f.getPath());
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        } finally {
            f.delete();
        }
    }
}
