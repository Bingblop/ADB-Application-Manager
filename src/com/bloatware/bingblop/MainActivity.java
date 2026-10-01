package com.bloatware.bingblop;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final String TAG = "ADBAppManager";
    private static final int SHIZUKU_REQUEST_CODE = 5042;
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_PLUS_PKG = "af.shizuku.plus.api";

    private WebView webView;
    private Vibrator vibrator;
    private SharedPreferences prefs;

    private File rishFile;
    private File rishDexFile;
    private File adbBinFile;
    private File adbHomeDir;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Working modes: "auto", "adb_tcp", "adb_wireless", "shizuku", "root", "unprivileged".
    // The configured mode is only ever changed by an explicit user action (selecting a mode,
    // connecting, authorizing Shizuku). Status checks never change it.
    private volatile String activeWorkingMode = "auto";
    private volatile String adbTcpHost = "127.0.0.1";
    private volatile int adbTcpPort = 5555;
    private volatile String adbWirelessHost = "127.0.0.1";
    private volatile int adbWirelessPort = 0;

    // Short-lived cache of the backend used by "auto" mode so batch actions don't re-probe every command
    private volatile String cachedAutoMode = null;
    private volatile long cachedAutoModeAt = 0;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != SHIZUKU_REQUEST_CODE) return;
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                setConfiguredMode("shizuku");
            }
            notifyJs("window.onShizukuPermissionResult && window.onShizukuPermissionResult(" + granted + ")");
        }
    };

    private final Shizuku.OnBinderReceivedListener shizukuBinderListener = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            notifyJs("window.checkAllWorkingModes && window.checkAllWorkingModes(false)");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // One UI dark system bar integration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(0xFF080A0F);
            getWindow().setStatusBarColor(0xFF080A0F);
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        prefs = getSharedPreferences("adb_app_manager_prefs", Context.MODE_PRIVATE);

        // Load saved working mode settings
        activeWorkingMode = prefs.getString("working_mode", "auto");
        adbTcpHost = prefs.getString("adb_tcp_host", "127.0.0.1");
        adbTcpPort = prefs.getInt("adb_tcp_port", 5555);
        adbWirelessHost = prefs.getString("adb_wireless_host", "127.0.0.1");
        adbWirelessPort = prefs.getInt("adb_wireless_port", 0);

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listener registration failed: " + t.getMessage());
        }

        // Extract binaries and ADB keys in background
        setupBinariesAndKeys();

        webView = new WebView(this);
        setContentView(webView);

        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(0xFF080A0F);

        WebView.setWebContentsDebuggingEnabled(true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
        registerWallpaperListener();
    }

    private void setupBinariesAndKeys() {
        try {
            File filesDir = getFilesDir();

            // 1. Shizuku rish setup (fallback executor when the Shizuku API binder is unavailable)
            rishFile = new File(filesDir, "rish");
            rishDexFile = new File(filesDir, "rish_shizuku.dex");
            extractAsset("rish", rishFile);
            rishFile.setExecutable(true, false);
            rishFile.setReadable(true, false);
            extractAsset("rish_shizuku.dex", rishDexFile);
            rishDexFile.setReadable(true, false);
            if (Build.VERSION.SDK_INT >= 34) {
                rishDexFile.setWritable(false, false);
            }

            // 2. ADB Native Library setup
            // Prefer extracted native library in nativeLibraryDir (standard for Android APKs)
            File nativeAdb = new File(getApplicationInfo().nativeLibraryDir, "libadb.so");
            if (nativeAdb.exists() && nativeAdb.canExecute()) {
                adbBinFile = nativeAdb;
            } else {
                adbBinFile = new File(filesDir, "libadb.so");
                extractAsset("libadb.so", adbBinFile);
                adbBinFile.setExecutable(true, false);
                adbBinFile.setReadable(true, false);
            }

            // 3. ADB Keys setup (.android/adbkey & .android/adbkey.pub)
            adbHomeDir = new File(filesDir, "adb_home");
            File dotAndroid = new File(adbHomeDir, ".android");
            if (!dotAndroid.exists()) dotAndroid.mkdirs();

            ensurePrivateAdbKey();

            // Reconnect the saved ADB TCP target on launch, but only when ADB TCP is (or may be) the
            // chosen mode. This never changes the configured mode, so other modes stay selectable.
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        String mode = activeWorkingMode;
                        if (("auto".equals(mode) || "adb_tcp".equals(mode)) && isPortOpen(adbTcpHost, adbTcpPort, 400)) {
                            performConnect(adbTcpHost, adbTcpPort, null);
                        } else if ("adb_wireless".equals(mode) && adbWirelessPort > 0) {
                            performConnect(adbWirelessHost, adbWirelessPort, null);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Auto-connect background failed: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "setupBinariesAndKeys error", e);
        }
    }

    // Fingerprint of the key pair that versions up to 3.5 shipped inside every APK (public in the repo history)
    private static final String LEGACY_SHARED_KEY_FINGERPRINT = "36:62:EE:8B:CA:29:5D:B6:B1:5C:DA:4E:ED:59:1D:01";

    private File adbKeyFile() {
        return new File(new File(adbHomeDir, ".android"), "adbkey");
    }

    private File adbPubKeyFile() {
        return new File(new File(adbHomeDir, ".android"), "adbkey.pub");
    }

    private String adbKeyName() {
        String model = Build.MODEL == null ? "android" : Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
        return "adbmanager@" + model;
    }

    /**
     * Every install gets its own ADB key. Missing keys are generated; the old shared key from v3.0–v3.5
     * is replaced (the phone will ask "Allow debugging?" once for the new key).
     */
    private void ensurePrivateAdbKey() {
        File key = adbKeyFile();
        File pub = adbPubKeyFile();
        try {
            if (key.exists() && key.length() > 0) {
                if (!pub.exists() || pub.length() == 0) {
                    AdbKeyManager.generate(key, pub, adbKeyName()); // unreadable pub: start fresh
                    onAdbKeyReplaced(false);
                    return;
                }
                if (!LEGACY_SHARED_KEY_FINGERPRINT.equals(AdbKeyManager.fingerprint(pub))) {
                    key.setReadable(false, false);
                    key.setReadable(true, true);
                    return; // already a private per-install key
                }
                AdbKeyManager.generate(key, pub, adbKeyName());
                onAdbKeyReplaced(true);
            } else {
                AdbKeyManager.generate(key, pub, adbKeyName());
                prefs.edit().putLong("adb_key_created_at", System.currentTimeMillis()).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "ADB key generation failed", e);
        }
    }

    private void onAdbKeyReplaced(boolean fromSharedKey) {
        prefs.edit()
                .putLong("adb_key_created_at", System.currentTimeMillis())
                .putBoolean("adb_key_notice_pending", fromSharedKey)
                .apply();
        // A running adb server still holds the old key in memory
        runProcessWithTimeout(buildAdbProcess("kill-server"), 3000);
        cachedAutoMode = null;
    }

    private JSONObject adbKeyInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("fingerprint", AdbKeyManager.fingerprint(adbPubKeyFile()));
            o.put("name", adbKeyName());
            o.put("createdAt", prefs.getLong("adb_key_created_at", 0));
            o.put("noticePending", prefs.getBoolean("adb_key_notice_pending", false));
        } catch (Exception ignored) {}
        return o;
    }

    private void extractAsset(String assetName, File destFile) {
        try {
            if (destFile.exists() && destFile.length() > 0) return;
            InputStream in = getAssets().open(assetName);
            OutputStream out = new FileOutputStream(destFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception ignored) {}
    }

    private void notifyJs(final String script) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(script, null);
                }
            }
        });
    }

    private void setConfiguredMode(String mode) {
        activeWorkingMode = (mode == null || mode.trim().isEmpty()) ? "auto" : mode.trim();
        cachedAutoMode = null;
        prefs.edit().putString("working_mode", activeWorkingMode).apply();
    }

    // ---------------------------------------------------------------------------------------------
    // Process helpers
    // ---------------------------------------------------------------------------------------------

    private ProcessBuilder buildAdbProcess(String... args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(adbBinFile != null && adbBinFile.exists() ? adbBinFile.getAbsolutePath() : "adb");
        cmd.add("-P");
        cmd.add("5042");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        if (adbHomeDir != null) {
            env.put("HOME", adbHomeDir.getAbsolutePath());
            env.put("ANDROID_USER_HOME", adbHomeDir.getAbsolutePath());
            File dotKey = new File(new File(adbHomeDir, ".android"), "adbkey");
            if (dotKey.exists()) {
                env.put("ADB_VENDOR_KEYS", dotKey.getAbsolutePath());
            }
        }
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);
        return pb;
    }

    private String runProcessWithTimeout(ProcessBuilder pb, int timeoutMs) {
        try {
            return readProcessWithTimeout(pb.start(), timeoutMs);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String readProcessWithTimeout(final Process p, int timeoutMs) {
        final StringBuilder sb = new StringBuilder();
        Future<String> future = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sb) {
                        sb.append(line).append("\n");
                    }
                }
                reader.close();
                p.waitFor();
                return "";
            }
        });

        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception timeoutEx) {
            future.cancel(true);
            try { p.destroy(); } catch (Exception ignored) {}
            synchronized (sb) {
                sb.append("\n[Process timed out after ").append(timeoutMs).append("ms]");
            }
        }
        synchronized (sb) {
            return sb.toString().trim();
        }
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ADB backends
    // ---------------------------------------------------------------------------------------------

    private String adbDevicesOutput() {
        return runProcessWithTimeout(buildAdbProcess("devices"), 2500);
    }

    /** True when `adb devices` lists the target in the ready "device" state (not offline/unauthorized). */
    private static boolean isTargetReady(String devicesOutput, String target) {
        if (devicesOutput == null) return false;
        for (String line : devicesOutput.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].equals(target) && "device".equals(parts[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdbTargetConnected(String target) {
        return isTargetReady(adbDevicesOutput(), target);
    }

    private String tcpTarget() {
        return adbTcpHost + ":" + adbTcpPort;
    }

    private String wirelessTarget() {
        return adbWirelessHost + ":" + adbWirelessPort;
    }

    /**
     * Runs `adb connect host:port`. When selectMode is non-null and the target ends up ready,
     * that mode becomes the configured mode (explicit user action). Otherwise the mode is untouched.
     */
    private String performConnect(String host, int port, String selectMode) {
        if (host == null || host.trim().isEmpty()) host = "127.0.0.1";
        host = host.trim();
        String target = host + ":" + port;

        String output = runProcessWithTimeout(buildAdbProcess("connect", target), 6000);
        Log.d(TAG, "adb connect " + target + " -> " + output);

        boolean ready = isAdbTargetConnected(target);
        if (ready && selectMode != null) {
            setConfiguredMode(selectMode);
        }
        cachedAutoMode = null;
        if (output == null) output = "";
        if (!ready && !output.toLowerCase().contains("fail") && !output.toLowerCase().contains("error")) {
            output = output + (output.isEmpty() ? "" : "\n") + "Target " + target + " is not ready (check the authorization prompt on the device).";
        }
        return output;
    }

    private String connectTcp(String host, int port, boolean select) {
        if (host == null || host.trim().isEmpty()) host = "127.0.0.1";
        if (port <= 0) port = 5555;
        adbTcpHost = host.trim();
        adbTcpPort = port;
        prefs.edit().putString("adb_tcp_host", adbTcpHost).putInt("adb_tcp_port", adbTcpPort).apply();
        return performConnect(adbTcpHost, adbTcpPort, select ? "adb_tcp" : null);
    }

    private String connectWireless(String host, int port, boolean select) {
        if (port <= 0) return "Error: Invalid wireless debugging port";
        if (host == null || host.trim().isEmpty()) host = "127.0.0.1";
        adbWirelessHost = host.trim();
        adbWirelessPort = port;
        prefs.edit().putString("adb_wireless_host", adbWirelessHost).putInt("adb_wireless_port", adbWirelessPort).apply();
        return performConnect(adbWirelessHost, adbWirelessPort, select ? "adb_wireless" : null);
    }

    private String runAdbShell(String target, String cmd) {
        String output = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), 8000);
        if (output != null && output.contains("device '") && output.contains("' not found")) {
            // The connection dropped (e.g. adbd restarted). Reconnect once without changing the mode.
            runProcessWithTimeout(buildAdbProcess("connect", target), 5000);
            output = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), 8000);
        }
        return output != null ? output : "";
    }

    // ---------------------------------------------------------------------------------------------
    // Shizuku backend
    // ---------------------------------------------------------------------------------------------

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String installedShizukuPackage() {
        if (isPackageInstalled(SHIZUKU_PKG)) return SHIZUKU_PKG;
        if (isPackageInstalled(SHIZUKU_PLUS_PKG)) return SHIZUKU_PLUS_PKG;
        return null;
    }

    private static boolean isShizukuBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShizukuAuthorized() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONObject shizukuStatus() {
        JSONObject obj = new JSONObject();
        try {
            String pkg = installedShizukuPackage();
            boolean running = isShizukuBinderAlive();
            boolean authorized = isShizukuAuthorized();
            boolean preV11 = false;
            int version = 0;
            if (running) {
                try {
                    preV11 = Shizuku.isPreV11();
                    version = Shizuku.getVersion();
                } catch (Throwable ignored) {}
            }
            obj.put("installed", pkg != null || running);
            obj.put("manager", pkg != null ? pkg : "");
            obj.put("running", running);
            obj.put("authorized", authorized);
            obj.put("preV11", preV11);
            obj.put("version", version);
        } catch (Exception ignored) {}
        return obj;
    }

    private static Method shizukuNewProcessMethod;

    private String runShizukuShell(String cmd) {
        // Preferred path: Shizuku API remote process (fast, no app_process start-up cost)
        if (isShizukuAuthorized()) {
            try {
                if (shizukuNewProcessMethod == null) {
                    Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    m.setAccessible(true);
                    shizukuNewProcessMethod = m;
                }
                Process p = (Process) shizukuNewProcessMethod.invoke(null,
                        new String[]{"sh", "-c", "exec 2>&1; " + cmd}, null, null);
                return readProcessWithTimeout(p, 10000);
            } catch (Throwable t) {
                Log.w(TAG, "Shizuku newProcess failed, falling back to rish: " + t.getMessage());
            }
        }

        // Fallback: rish shell bundled in assets
        if (rishFile != null && rishFile.exists()) {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", rishFile.getAbsolutePath(), "-c", cmd);
            pb.environment().put("RISH_APPLICATION_ID", getPackageName());
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 10000);
        }
        return "Error: Shizuku is not authorized for this app";
    }

    // ---------------------------------------------------------------------------------------------
    // Root backend
    // ---------------------------------------------------------------------------------------------

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/vendor/bin/su"
    };

    private boolean isRootAvailable() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return true;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "command -v su"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mode resolution
    // ---------------------------------------------------------------------------------------------

    /** Backend used to execute commands right now: adb_tcp, adb_wireless, shizuku, root or standard. */
    private String resolveExecMode() {
        String mode = activeWorkingMode;
        if (!"auto".equals(mode)) {
            return "unprivileged".equals(mode) ? "standard" : mode;
        }
        long now = System.currentTimeMillis();
        String cached = cachedAutoMode;
        if (cached != null && now - cachedAutoModeAt < 4000) {
            return cached;
        }
        String devices = adbDevicesOutput();
        String resolved;
        if (isTargetReady(devices, tcpTarget())) {
            resolved = "adb_tcp";
        } else if (adbWirelessPort > 0 && isTargetReady(devices, wirelessTarget())) {
            resolved = "adb_wireless";
        } else if (isShizukuAuthorized()) {
            resolved = "shizuku";
        } else if (isPortOpen(adbTcpHost, adbTcpPort, 200)) {
            // Port is open but not connected yet: connect quietly without changing the configured mode
            performConnect(adbTcpHost, adbTcpPort, null);
            resolved = isAdbTargetConnected(tcpTarget()) ? "adb_tcp" : "standard";
        } else {
            resolved = "standard";
        }
        cachedAutoMode = resolved;
        cachedAutoModeAt = now;
        return resolved;
    }

    private static int parsePort(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String deviceWifiIp() {
        String fallback = "";
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    String name = nif.getName() != null ? nif.getName() : "";
                    if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")) {
                        return ip;
                    }
                    if (fallback.isEmpty() && addr.isSiteLocalAddress()) {
                        fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String colorHex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static int mixColor(int a, int b, float ratio) {
        float inv = 1f - ratio;
        int r = (int) (Color.red(a) * inv + Color.red(b) * ratio);
        int g = (int) (Color.green(a) * inv + Color.green(b) * ratio);
        int bl = (int) (Color.blue(a) * inv + Color.blue(b) * ratio);
        return Color.rgb(r, g, bl);
    }

    /**
     * Adds protection details so the UI knows which permissions `pm grant/revoke` can change:
     * runtime (dangerous) and development permissions are changeable, the rest are install-time.
     */
    private static void describePermission(PackageManager pm, String permName, JSONObject out) throws Exception {
        String protection = "unknown";
        boolean changeable = false;
        boolean appOp = false;
        String label = "";
        try {
            android.content.pm.PermissionInfo pi = pm.getPermissionInfo(permName, 0);
            int level = pi.protectionLevel;
            int base = level & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE;
            boolean development = (level & android.content.pm.PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
            appOp = (level & android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
            switch (base) {
                case android.content.pm.PermissionInfo.PROTECTION_NORMAL: protection = "normal"; break;
                case android.content.pm.PermissionInfo.PROTECTION_DANGEROUS: protection = "runtime"; break;
                case android.content.pm.PermissionInfo.PROTECTION_SIGNATURE: protection = "signature"; break;
                default: protection = "privileged"; break;
            }
            changeable = base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS || development;
            if (development && !"runtime".equals(protection)) protection = "development";
            CharSequence l = pi.loadLabel(pm);
            if (l != null && !permName.equals(l.toString())) label = l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            protection = "undefined";
        }
        out.put("protection", protection);
        out.put("changeable", changeable);
        out.put("appOp", appOp);
        out.put("label", label);
    }

    /** Maps Android 12+ system tonal palettes to the app's color roles (Material 3 dark / light). */
    private JSONObject materialYouScheme(boolean dark) throws Exception {
        JSONObject o = new JSONObject();
        if (dark) {
            int n900 = systemColor("system_neutral1_900");
            int n800 = systemColor("system_neutral1_800");
            o.put("accent", colorHex(systemColor("system_accent1_200")));
            o.put("bg", colorHex(mixColor(n900, 0xFF000000, 0.35f)));
            o.put("surface", colorHex(n800));
            o.put("card", colorHex(mixColor(n900, n800, 0.45f)));
            o.put("sheet", colorHex(n900));
            o.put("running", colorHex(systemColor("system_accent3_200")));
            o.put("frozen", colorHex(systemColor("system_accent1_200")));
            o.put("system", colorHex(systemColor("system_accent2_200")));
            o.put("secondary", colorHex(systemColor("system_accent2_200")));
            o.put("bloat", "#F2B8B5");
            o.put("text", colorHex(systemColor("system_neutral1_100")));
            o.put("muted", colorHex(systemColor("system_neutral2_200")));
        } else {
            int n10 = systemColor("system_neutral1_10");
            int n50 = systemColor("system_neutral1_50");
            int n100 = systemColor("system_neutral1_100");
            o.put("accent", colorHex(systemColor("system_accent1_600")));
            o.put("bg", colorHex(n10));
            o.put("surface", colorHex(n100));
            o.put("card", colorHex(n50));
            o.put("sheet", colorHex(mixColor(n10, n50, 0.5f)));
            o.put("running", colorHex(systemColor("system_accent3_600")));
            o.put("frozen", colorHex(systemColor("system_accent1_600")));
            o.put("system", colorHex(systemColor("system_accent2_600")));
            o.put("secondary", colorHex(systemColor("system_accent2_600")));
            o.put("bloat", "#B3261E");
            o.put("text", colorHex(systemColor("system_neutral1_900")));
            o.put("muted", colorHex(systemColor("system_neutral2_700")));
        }
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // Debloater: Universal Android Debloater Next Generation (UAD-NG) community package list.
    // Downloaded at runtime (GPL-3.0 data, not bundled) and cached in app storage.
    // ---------------------------------------------------------------------------------------------

    private static final String UAD_LIST_URL =
            "https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json";
    private static final long UAD_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private volatile boolean uadDownloading = false;

    private File uadCacheFile() {
        return new File(getFilesDir(), "uad_lists.json");
    }

    private JSONObject uadStatus() {
        JSONObject o = new JSONObject();
        try {
            File f = uadCacheFile();
            long updatedAt = prefs.getLong("uad_updated_at", 0);
            o.put("cached", f.exists() && f.length() > 0);
            o.put("updatedAt", updatedAt);
            o.put("count", prefs.getInt("uad_count", 0));
            o.put("stale", updatedAt == 0 || System.currentTimeMillis() - updatedAt > UAD_MAX_AGE_MS);
            o.put("downloading", uadDownloading);
            o.put("source", UAD_LIST_URL);
        } catch (Exception ignored) {}
        return o;
    }

    private void downloadUadList() {
        if (uadDownloading) return;
        uadDownloading = true;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                String error = null;
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(UAD_LIST_URL).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "ADB-Application-Manager");
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                    InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > 32 * 1024 * 1024) throw new IllegalStateException("List too large");
                    }
                    in.close();
                    String text = buf.toString("UTF-8");
                    JSONObject parsed = new JSONObject(text); // validate before replacing the cache
                    if (parsed.length() < 100) throw new IllegalStateException("List looks incomplete");

                    File tmp = new File(getFilesDir(), "uad_lists.json.tmp");
                    OutputStream out = new FileOutputStream(tmp);
                    out.write(buf.toByteArray());
                    out.close();
                    if (!tmp.renameTo(uadCacheFile())) {
                        uadCacheFile().delete();
                        if (!tmp.renameTo(uadCacheFile())) throw new IllegalStateException("Could not save list");
                    }
                    prefs.edit().putLong("uad_updated_at", System.currentTimeMillis()).putInt("uad_count", parsed.length()).apply();
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    Log.w(TAG, "UAD list download failed: " + error);
                } finally {
                    if (conn != null) conn.disconnect();
                    uadDownloading = false;
                }
                try {
                    JSONObject status = uadStatus();
                    status.put("error", error == null ? "" : error);
                    notifyJs("window.onUadListUpdated && window.onUadListUpdated(" + JSONObject.quote(status.toString()) + ")");
                } catch (Exception ignored) {}
            }
        });
    }

    /** UAD-NG entries for packages on this device, including ones uninstalled for the current user. */
    private String uadMatches() {
        JSONObject res = new JSONObject();
        try {
            File f = uadCacheFile();
            if (!f.exists()) {
                res.put("packages", new JSONArray());
                return res.toString();
            }
            JSONObject list = new JSONObject(new String(AdbKeyManager.readFile(f), "UTF-8"));
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
            JSONArray out = new JSONArray();
            for (ApplicationInfo ai : apps) {
                JSONObject e = list.optJSONObject(ai.packageName);
                if (e == null) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", ai.packageName);
                String label = ai.packageName;
                try {
                    CharSequence l = pm.getApplicationLabel(ai);
                    if (l != null && l.length() > 0) label = l.toString();
                } catch (Exception ignored) {}
                o.put("name", label);
                boolean installed = (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                String state = !installed ? "uninstalled" : (!ai.enabled ? "disabled" : "enabled");
                o.put("state", state);
                o.put("isSystem", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                o.put("isSuspended", installed && (ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
                o.put("list", e.optString("list", "Misc"));
                o.put("removal", e.optString("removal", "Expert"));
                o.put("description", e.optString("description", ""));
                o.put("dependencies", e.optJSONArray("dependencies") != null ? e.optJSONArray("dependencies") : new JSONArray());
                o.put("neededBy", e.optJSONArray("neededBy") != null ? e.optJSONArray("neededBy") : new JSONArray());
                out.put(o);
            }
            res.put("packages", out);
        } catch (Exception e) {
            try {
                res.put("packages", new JSONArray());
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Updates tab: Galaxy Store (Samsung system / store apps) and this app's GitHub releases
    // ---------------------------------------------------------------------------------------------

    private final java.util.concurrent.ConcurrentHashMap<String, JSONObject> updateResults = new java.util.concurrent.ConcurrentHashMap<String, JSONObject>();
    private volatile boolean updateCheckRunning = false;
    private volatile long updateCheckedAt = 0;
    private final java.util.Set<String> installingUpdates = java.util.Collections.synchronizedSet(new HashSet<String>());

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static long versionCodeOf(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private UpdateManager.Device updateDevice() {
        String mcc = "", mnc = "";
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            String op = tm != null ? tm.getSimOperator() : "";
            if (op != null && op.length() >= 5) {
                mcc = op.substring(0, 3);
                mnc = op.substring(3);
            }
        } catch (Exception ignored) {}
        String csc = runProcessWithTimeout(new ProcessBuilder("getprop", "ro.csc.sales_code"), 2000).trim();
        if (csc.isEmpty() || csc.contains(" ")) csc = runProcessWithTimeout(new ProcessBuilder("getprop", "persist.omc.sales_code"), 2000).trim();
        if (csc.contains(" ")) csc = "";
        return new UpdateManager.Device(Build.MODEL, mcc, mnc, csc, Build.VERSION.SDK_INT);
    }

    /** Samsung system apps and apps installed from the Galaxy Store are checked against the Galaxy Store. */
    private boolean isGalaxyStoreCandidate(ApplicationInfo ai, String installer) {
        if (UpdateManager.GALAXY_STORE_PKG.equals(installer)) return true;
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        String p = ai.packageName;
        return system && (p.startsWith("com.samsung.") || p.startsWith("com.sec.") || p.startsWith("com.osp."));
    }

    private String installerOf(String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.pm.InstallSourceInfo src = getPackageManager().getInstallSourceInfo(pkg);
                return src.getInstallingPackageName();
            }
            return getPackageManager().getInstallerPackageName(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyUpdates(String fn, JSONObject payload) {
        notifyJs("window." + fn + " && window." + fn + "(" + JSONObject.quote(payload.toString()) + ")");
    }

    private void runUpdateCheck() {
        if (updateCheckRunning) return;
        updateCheckRunning = true;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject summary = new JSONObject();
                try {
                    updateResults.clear();
                    PackageManager pm = getPackageManager();

                    // 1. This app, from GitHub Releases
                    try {
                        JSONObject rel = UpdateManager.githubLatest(UpdateManager.SELF_REPO);
                        String installed = currentVersionName();
                        if (UpdateManager.compareVersions(rel.optString("version"), installed) > 0) {
                            JSONObject o = new JSONObject();
                            o.put("pkg", getPackageName());
                            o.put("name", "ADB Application Manager Pro");
                            o.put("installedVersion", installed);
                            o.put("availableVersion", rel.optString("version"));
                            o.put("source", "self");
                            o.put("downloadUrl", rel.optString("url"));
                            o.put("page", rel.optString("page"));
                            o.put("notes", rel.optString("notes"));
                            updateResults.put(getPackageName(), o);
                        }
                        summary.put("selfStatus", "ok");
                    } catch (Exception e) {
                        summary.put("selfStatus", e.getMessage() != null && e.getMessage().contains("404") ? "no_releases" : "error: " + e.getMessage());
                    }

                    // 2. Sideloaded / open-source apps: your sources, Obtainium catalog, IzzyOnDroid, F-Droid
                    checkOpenSourceApps(summary);

                    // 3. Galaxy Store candidates
                    final UpdateManager.Device device = updateDevice();
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    final List<ApplicationInfo> candidates = new ArrayList<ApplicationInfo>();
                    for (ApplicationInfo ai : apps) {
                        if (isGalaxyStoreCandidate(ai, installerOf(ai.packageName))) candidates.add(ai);
                    }
                    final int total = candidates.size();
                    final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                    final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
                    final java.util.concurrent.atomic.AtomicReference<String> lastError = new java.util.concurrent.atomic.AtomicReference<String>("");
                    ExecutorService pool = Executors.newFixedThreadPool(6);
                    for (final ApplicationInfo ai : candidates) {
                        pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    PackageInfo info = getPackageManager().getPackageInfo(ai.packageName, 0);
                                    long vc = versionCodeOf(info);
                                    JSONObject r = UpdateManager.galaxyCheck(ai.packageName, vc, device);
                                    if (r.optBoolean("available")) {
                                        JSONObject o = new JSONObject();
                                        o.put("pkg", ai.packageName);
                                        o.put("name", getPackageManager().getApplicationLabel(ai).toString());
                                        o.put("installedVersion", info.versionName != null ? info.versionName : String.valueOf(vc));
                                        o.put("installedCode", vc);
                                        o.put("availableVersion", r.optString("versionName"));
                                        o.put("availableCode", r.optLong("versionCode"));
                                        o.put("isSystem", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                                        o.put("source", "galaxy");
                                        updateResults.put(ai.packageName, o);
                                    }
                                } catch (Exception e) {
                                    errors.incrementAndGet();
                                    lastError.set(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                                }
                                int d = done.incrementAndGet();
                                if (d % 8 == 0 || d == total) {
                                    try {
                                        JSONObject prog = new JSONObject();
                                        prog.put("done", d);
                                        prog.put("total", total);
                                        prog.put("phase", "galaxy");
                                        notifyUpdates("onUpdateCheckProgress", prog);
                                    } catch (Exception ignored) {}
                                }
                            }
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(5, TimeUnit.MINUTES);
                    summary.put("checked", total);
                    summary.put("errors", errors.get());
                    summary.put("lastError", lastError.get());
                    summary.put("device", device.model + " / " + (device.csc.isEmpty() ? "?" : device.csc));
                } catch (Exception e) {
                    try { summary.put("error", e.getMessage()); } catch (Exception ignored) {}
                } finally {
                    updateCheckRunning = false;
                    updateCheckedAt = System.currentTimeMillis();
                }
                try {
                    summary.put("updates", updatesArray());
                    summary.put("checkedAt", updateCheckedAt);
                    notifyUpdates("onUpdatesChecked", summary);
                } catch (Exception ignored) {}
            }
        });
    }

    private JSONArray updatesArray() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : updateResults.values()) arr.put(o);
        return arr;
    }

    private void progress(String pkg, String stage, int percent, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("stage", stage);
            o.put("percent", percent);
            o.put("message", message == null ? "" : message);
            notifyUpdates("onUpdateProgress", o);
        } catch (Exception ignored) {}
    }

    /** Downloads the official APK, verifies it, and installs it through the active privileged mode. */
    private void runInstallUpdate(final String pkg) {
        final JSONObject u = updateResults.get(pkg);
        if (u == null || !installingUpdates.add(pkg)) return;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                File apk = new File(new File(getCacheDir(), "updates"), pkg + ".apk");
                try {
                    String source = u.optString("source");
                    if ("self".equals(source) || "external".equals(source)) throw new IllegalStateException("this update opens in the browser or Obtainium");
                    if ("standard".equals(resolveExecMode())) {
                        throw new IllegalStateException("installing updates needs ADB, Shizuku or Root. Set up a working mode first.");
                    }
                    apk.getParentFile().mkdirs();
                    progress(pkg, "downloading", 0, "Getting download link...");
                    String url = "galaxy".equals(source) ? UpdateManager.galaxyDownloadUrl(pkg, updateDevice()) : u.optString("downloadUrl");
                    if (url.isEmpty()) throw new IllegalStateException("no APK for this phone in the latest release");
                    UpdateManager.download(url, apk, new UpdateManager.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            progress(pkg, "downloading", total > 0 ? (int) (done * 100 / total) : -1, (done / (1024 * 1024)) + " MB");
                        }
                    });

                    // Only install what we asked for: same package, newer version
                    PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (archive == null || !pkg.equals(archive.packageName)) {
                        throw new IllegalStateException("downloaded file is not " + pkg);
                    }
                    long installedVc = versionCodeOf(getPackageManager().getPackageInfo(pkg, 0));
                    if (versionCodeOf(archive) <= installedVc) {
                        throw new IllegalStateException("downloaded version (" + archive.versionName + ") is not newer than the installed one");
                    }
                    // Android only accepts an update signed by the same key. Catch a mismatch (e.g. an F-Droid
                    // build over a developer build) before trying, and say why.
                    int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                    java.util.Set<String> installedSigners = signerDigests(getPackageManager().getPackageInfo(pkg, sigFlags), true);
                    PackageInfo archiveSigned = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), sigFlags);
                    java.util.Set<String> newSigners = archiveSigned != null ? signerDigests(archiveSigned, false) : new HashSet<String>();
                    if (!installedSigners.isEmpty() && !newSigners.isEmpty()) {
                        java.util.Set<String> common = new HashSet<String>(installedSigners);
                        common.retainAll(newSigners);
                        if (common.isEmpty()) {
                            throw new IllegalStateException("signed with a different key than the installed app, so Android won't accept it as an update. "
                                    + "Update from the source you originally installed from" + ("fdroid".equals(source) ? " (F-Droid signs its own builds)." : "."));
                        }
                    }

                    progress(pkg, "installing", 100, "Installing " + archive.versionName + "...");
                    String out = installApk(apk);
                    if (out.contains("Success")) {
                        updateResults.remove(pkg);
                        progress(pkg, "done", 100, "Updated to " + archive.versionName);
                    } else {
                        throw new IllegalStateException(out.trim().isEmpty() ? "install failed" : out.trim());
                    }
                } catch (Exception e) {
                    progress(pkg, "error", 0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                } finally {
                    apk.delete();
                    installingUpdates.remove(pkg);
                }
            }
        });
    }

    private String installApk(File apk) throws Exception {
        String mode = resolveExecMode();
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            String target = "adb_tcp".equals(mode) ? tcpTarget() : wirelessTarget();
            if (!isAdbTargetConnected(target)) performConnect("adb_tcp".equals(mode) ? adbTcpHost : adbWirelessHost,
                    "adb_tcp".equals(mode) ? adbTcpPort : adbWirelessPort, null);
            // The adb client runs as this app, so it can read the file and stream it to the device
            return runProcessWithTimeout(buildAdbProcess("-s", target, "install", "-r", apk.getAbsolutePath()), 300000);
        }
        if ("shizuku".equals(mode)) {
            if (shizukuNewProcessMethod == null) {
                Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                shizukuNewProcessMethod = m;
            }
            // The shell user can't read app-private files, so stream the APK into `pm install -S`
            Process p = (Process) shizukuNewProcessMethod.invoke(null,
                    new String[]{"sh", "-c", "exec 2>&1; pm install -r -S " + apk.length()}, null, null);
            OutputStream os = p.getOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.flush();
            os.close();
            return readProcessWithTimeout(p, 300000);
        }
        if ("root".equals(mode)) {
            apk.setReadable(true, false);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "pm install -r '" + apk.getAbsolutePath() + "'");
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 300000);
        }
        return "Error: installing updates needs ADB, Shizuku or Root";
    }

    // ---------------------------------------------------------------------------------------------
    // Open-source / sideloaded app updates
    // ---------------------------------------------------------------------------------------------

    private static final java.util.Set<String> STORE_INSTALLERS = new HashSet<String>(java.util.Arrays.asList(
            "com.android.vending", UpdateManager.GALAXY_STORE_PKG, "com.aurora.store", "com.amazon.venezia",
            "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market",
            "com.bbk.appstore", "com.google.android.feedback"));
    private static final java.util.Set<String> FDROID_CLIENTS = new HashSet<String>(java.util.Arrays.asList(
            "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
            "eu.bubu1.fdroidclassic", "in.sunilpaulmathew.izzyondroid"));

    private JSONObject updateSources() {
        try {
            return new JSONObject(prefs.getString("update_sources", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveUpdateSources(JSONObject o) {
        prefs.edit().putString("update_sources", o.toString()).apply();
    }

    /** Apps that may have an open-source update: user-mapped apps, or user apps not from an app store. */
    private boolean isOpenSourceCandidate(ApplicationInfo ai, String installer, JSONObject sources) {
        if (sources.has(ai.packageName)) return true;
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
        if (ai.packageName.equals(getPackageName())) return false;
        return installer == null || !STORE_INSTALLERS.contains(installer);
    }

    private JSONObject obtainiumLinkConfig(String pkg, String name, String url, String author) {
        JSONObject c = new JSONObject();
        try {
            c.put("id", pkg);
            c.put("url", url);
            c.put("author", author == null ? "" : author);
            c.put("name", name == null ? pkg : name);
        } catch (Exception ignored) {}
        return c;
    }

    /**
     * Resolves one app against its sources in order. The first source that knows the app decides
     * (so an up-to-date answer from the developer's repo isn't overridden by another repo's build).
     * Returns an update JSON, or {"none":true,...} when up to date, or null when no source knows it.
     */
    private JSONObject checkOpenSourceApp(ApplicationInfo ai, PackageInfo info, String installer, JSONObject sources, String token) throws Exception {
        String pkg = ai.packageName;
        String name = getPackageManager().getApplicationLabel(ai).toString();
        long vc = versionCodeOf(info);
        String vn = info.versionName != null ? info.versionName : String.valueOf(vc);

        java.util.List<JSONObject> candidates = new ArrayList<JSONObject>();
        JSONObject mine = sources.optJSONObject(pkg);
        if (mine != null && mine.optString("url").length() > 0) candidates.add(new JSONObject().put("url", mine.optString("url"))
                .put("origin", mine.optString("origin", "yours")).put("settings", mine.optJSONObject("settings") != null ? mine.optJSONObject("settings") : new JSONObject()));
        boolean fromFdroidClient = installer != null && FDROID_CLIENTS.contains(installer);
        if (!fromFdroidClient) {
            JSONObject cfg = UpdateManager.obtainiumCatalog(pkg);
            if (cfg != null && cfg.optString("url").length() > 0) candidates.add(new JSONObject().put("url", cfg.optString("url"))
                    .put("origin", "obtainium-catalog").put("settings", UpdateManager.obtainiumSettings(cfg)).put("author", cfg.optString("author")));
        }
        // F-Droid builds are signed by F-Droid, IzzyOnDroid mostly ships the developer's APKs
        if (fromFdroidClient) {
            candidates.add(new JSONObject().put("fdroid", "fdroid"));
            candidates.add(new JSONObject().put("fdroid", "izzy"));
        } else {
            candidates.add(new JSONObject().put("fdroid", "izzy"));
            candidates.add(new JSONObject().put("fdroid", "fdroid"));
        }

        for (JSONObject c : candidates) {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("name", name);
            o.put("installedVersion", vn);
            o.put("installedCode", vc);
            o.put("installer", installer == null ? "" : installer);

            String fd = c.optString("fdroid");
            if (!fd.isEmpty()) {
                boolean izzy = "izzy".equals(fd);
                JSONObject latest = UpdateManager.fdroidLatest(izzy ? UpdateManager.IZZY_API : UpdateManager.FDROID_API, pkg);
                if (latest == null) continue;
                long remote = latest.optLong("versionCode");
                o.put("source", izzy ? "izzy" : "fdroid");
                o.put("origin", izzy ? "izzy" : "fdroid");
                o.put("availableVersion", latest.optString("versionName"));
                o.put("availableCode", remote);
                o.put("downloadUrl", (izzy ? UpdateManager.IZZY_REPO : UpdateManager.FDROID_REPO) + pkg + "_" + remote + ".apk");
                o.put("page", izzy ? "https://apt.izzysoft.de/fdroid/index/apk/" + pkg : "https://f-droid.org/packages/" + pkg + "/");
                if (remote <= vc) o.put("none", true);
                return o;
            }

            String url = c.optString("url");
            JSONObject settings = c.optJSONObject("settings") != null ? c.optJSONObject("settings") : new JSONObject();
            o.put("origin", c.optString("origin"));
            o.put("sourceUrl", url);
            o.put("obtainium", "obtainium://app/" + java.net.URLEncoder.encode(obtainiumLinkConfig(pkg, name, url, c.optString("author")).toString(), "UTF-8").replace("+", "%20"));

            String fdroidPkg = UpdateManager.parseFdroidUrl(url);
            if (fdroidPkg != null) {
                JSONObject latest = UpdateManager.fdroidLatest(url.contains("izzysoft") ? UpdateManager.IZZY_API : UpdateManager.FDROID_API, pkg);
                if (latest == null) continue;
                long remote = latest.optLong("versionCode");
                boolean izzy = url.contains("izzysoft");
                o.put("source", izzy ? "izzy" : "fdroid");
                o.put("availableVersion", latest.optString("versionName"));
                o.put("availableCode", remote);
                o.put("downloadUrl", (izzy ? UpdateManager.IZZY_REPO : UpdateManager.FDROID_REPO) + pkg + "_" + remote + ".apk");
                o.put("page", url);
                if (remote <= vc) o.put("none", true);
                return o;
            }

            JSONObject repo = UpdateManager.parseRepoUrl(url);
            if (repo == null) {
                // e.g. a vendor website: Obtainium can track it, this app can't check it directly
                o.put("source", "external");
                o.put("page", url);
                o.put("unsupported", true);
                return o;
            }
            UpdateManager.Release rel = "codeberg.org".equals(repo.optString("host"))
                    ? UpdateManager.codebergRelease(repo.optString("owner"), repo.optString("repo"))
                    : UpdateManager.githubRelease(repo.optString("owner"), repo.optString("repo"), token, settings.optBoolean("includePrereleases", false));
            String version = UpdateManager.extractVersion(rel.tag, settings.optString("versionExtractionRegEx", ""), settings.optString("matchGroupToUse", ""));
            String[] apk = UpdateManager.pickApk(rel.assets, settings.optString("apkFilterRegEx", ""), settings.optBoolean("invertAPKFilter", false), Build.SUPPORTED_ABIS);
            o.put("source", "codeberg.org".equals(repo.optString("host")) ? "codeberg" : "github");
            o.put("availableVersion", version.replaceFirst("^[vV](?=[0-9])", ""));
            o.put("page", rel.page);
            o.put("notes", rel.notes);
            if (apk != null) {
                o.put("downloadUrl", apk[1]);
                o.put("assetName", apk[0]);
            }
            if (UpdateManager.compareVersions(version, vn) <= 0) o.put("none", true);
            else if (apk == null) o.put("noApk", true);
            return o;
        }
        return null;
    }

    private void checkOpenSourceApps(JSONObject summary) throws Exception {
        final JSONObject sources = updateSources();
        final String token = prefs.getString("github_token", "");
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> candidates = new ArrayList<ApplicationInfo>();
        final java.util.Map<String, String> installers = new java.util.HashMap<String, String>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String inst = installerOf(ai.packageName);
            if (isOpenSourceCandidate(ai, inst, sources)) {
                candidates.add(ai);
                installers.put(ai.packageName, inst);
            }
        }
        final JSONArray untracked = new JSONArray();
        final JSONArray upToDate = new JSONArray();
        final JSONArray external = new JSONArray();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        final int total = candidates.size();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (final ApplicationInfo ai : candidates) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        PackageInfo info = pm.getPackageInfo(ai.packageName, 0);
                        JSONObject r = checkOpenSourceApp(ai, info, installers.get(ai.packageName), sources, token);
                        if (r == null) {
                            JSONObject u = new JSONObject();
                            u.put("pkg", ai.packageName);
                            u.put("name", pm.getApplicationLabel(ai).toString());
                            u.put("installedVersion", info.versionName);
                            u.put("installer", installers.get(ai.packageName) == null ? "" : installers.get(ai.packageName));
                            synchronized (untracked) { untracked.put(u); }
                        } else if (r.optBoolean("none")) {
                            synchronized (upToDate) { upToDate.put(r); }
                        } else if (r.optBoolean("unsupported")) {
                            synchronized (external) { external.put(r); }
                        } else {
                            updateResults.put(ai.packageName, r);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        Log.w(TAG, "Open-source update check failed for " + ai.packageName + ": " + e.getMessage());
                    }
                    int d = done.incrementAndGet();
                    try {
                        JSONObject prog = new JSONObject();
                        prog.put("done", d);
                        prog.put("total", total);
                        prog.put("phase", "open-source");
                        notifyUpdates("onUpdateCheckProgress", prog);
                    } catch (Exception ignored) {}
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
        summary.put("openSourceChecked", total);
        summary.put("openSourceErrors", errors.get());
        summary.put("untracked", untracked);
        summary.put("external", external);
        summary.put("upToDateOpenSource", upToDate.length());
    }

    /** SHA-256 digests of the signing certificates (including past keys for installed apps). */
    private static java.util.Set<String> signerDigests(PackageInfo pi, boolean includeHistory) {
        java.util.Set<String> out = new HashSet<String>();
        try {
            android.content.pm.Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = includeHistory && !pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getSigningCertificateHistory()
                        : pi.signingInfo.getApkContentsSigners();
            } else {
                sigs = pi.signatures;
            }
            if (sigs == null) return out;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
                out.add(sb.toString());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static final int REQ_IMPORT_OBTAINIUM = 4201;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_OBTAINIUM) return;
        final Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                try {
                    if (uri == null) throw new IllegalStateException("cancelled");
                    InputStream in = getContentResolver().openInputStream(uri);
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        buf.write(b, 0, n);
                        if (buf.size() > 20 * 1024 * 1024) throw new IllegalStateException("file too large");
                    }
                    in.close();
                    res.put("imported", importObtainiumJson(buf.toString("UTF-8")));
                } catch (Exception e) {
                    try { res.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                notifyUpdates("onObtainiumImported", res);
            }
        });
    }

    /** Merges an Obtainium export ({"apps":[{"id","url","additionalSettings",...}]}) into the update sources. */
    private int importObtainiumJson(String text) throws Exception {
        text = text.trim();
        JSONArray apps = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text).optJSONArray("apps");
        if (apps == null) throw new IllegalStateException("not an Obtainium export (no \"apps\" list)");
        JSONObject sources = updateSources();
        int count = 0;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("id"), url = a.optString("url");
            if (id.isEmpty() || url.isEmpty()) continue;
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("origin", "obtainium-import");
            entry.put("settings", UpdateManager.obtainiumSettings(a));
            sources.put(id, entry);
            count++;
        }
        saveUpdateSources(sources);
        return count;
    }

    // Small JSON documents the UI keeps between launches (remembered filters, debloat history)
    private static final java.util.Set<String> UI_STORE_KEYS = new HashSet<String>(java.util.Arrays.asList("ui_state", "debloat_history"));

    private int systemColor(String name) {
        int id = getResources().getIdentifier(name, "color", "android");
        if (id == 0) throw new IllegalStateException("Missing system color " + name);
        return getColor(id);
    }

    private class AndroidBridge {

        @JavascriptInterface
        public void vibrate() {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(20);
                }
            }
        }

        @JavascriptInterface
        public void showToast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openDeveloperOptions() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                }
            });
        }

        @JavascriptInterface
        public void openShizukuApp() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String pkg = installedShizukuPackage();
                    Intent launch = pkg != null ? getPackageManager().getLaunchIntentForPackage(pkg) : null;
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                    } else {
                        Toast.makeText(MainActivity.this, "Shizuku app not found on device!", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        /**
         * Requests Shizuku authorization through the official Shizuku API. The result is delivered
         * asynchronously to window.onShizukuPermissionResult(granted). Returns a status string:
         * granted, requested, not_installed, not_running, pre_v11 or error:...
         */
        @JavascriptInterface
        public String requestShizukuPermission() {
            try {
                if (!isShizukuBinderAlive()) {
                    if (installedShizukuPackage() == null) return "not_installed";
                    return "not_running";
                }
                if (Shizuku.isPreV11()) return "pre_v11";
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    setConfiguredMode("shizuku");
                    return "granted";
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "Shizuku request failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
                return "requested";
            } catch (Throwable t) {
                return "error: " + t.getMessage();
            }
        }

        @JavascriptInterface
        public String checkShizukuStatus() {
            return shizukuStatus().toString();
        }

        @JavascriptInterface
        public String connectAdbTcp(String host, int port) {
            return connectTcp(host, port, true);
        }

        @JavascriptInterface
        public String pairAdbWireless(String host, int port, String code) {
            if (host == null || host.trim().isEmpty()) host = "127.0.0.1";
            if (port <= 0 || code == null || code.trim().isEmpty()) {
                return "Error: Invalid port or pairing code";
            }
            return runProcessWithTimeout(buildAdbProcess("pair", host.trim() + ":" + port, code.trim()), 10000);
        }

        @JavascriptInterface
        public String connectAdbWireless(String host, int port) {
            return connectWireless(host, port, true);
        }

        @JavascriptInterface
        public String setAdbTcpip(int port) {
            if (port <= 0) port = 5555;
            // `adb tcpip` needs an existing transport. Use the wireless debugging connection when present
            // so the command does not fail with "more than one device/emulator".
            String devices = adbDevicesOutput();
            if (adbWirelessPort > 0 && isTargetReady(devices, wirelessTarget())) {
                return runProcessWithTimeout(buildAdbProcess("-s", wirelessTarget(), "tcpip", String.valueOf(port)), 6000);
            }
            return runProcessWithTimeout(buildAdbProcess("tcpip", String.valueOf(port)), 6000);
        }

        @JavascriptInterface
        public String disconnectAdb(String target) {
            String output = (target == null || target.trim().isEmpty())
                    ? runProcessWithTimeout(buildAdbProcess("disconnect"), 3000)
                    : runProcessWithTimeout(buildAdbProcess("disconnect", target.trim()), 3000);
            // After disconnecting, fall back to automatic selection so other modes can take over
            String mode = activeWorkingMode;
            if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
                setConfiguredMode("auto");
            }
            cachedAutoMode = null;
            return output;
        }

        @JavascriptInterface
        public String getDeviceIp() {
            return deviceWifiIp();
        }

        /** Finds wireless debugging endpoints via adb mDNS. Returns {"connect":[...],"pairing":[...],"raw":"..."} */
        @JavascriptInterface
        public String discoverWirelessDebugging() {
            JSONObject obj = new JSONObject();
            JSONArray connect = new JSONArray();
            JSONArray pairing = new JSONArray();
            String raw = runProcessWithTimeout(buildAdbProcess("mdns", "services"), 4000);
            try {
                for (String line : raw.split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 3) continue;
                    String endpoint = parts[parts.length - 1];
                    if (!endpoint.contains(":")) continue;
                    if (line.contains("_adb-tls-connect")) connect.put(endpoint);
                    else if (line.contains("_adb-tls-pairing")) pairing.put(endpoint);
                }
                obj.put("connect", connect);
                obj.put("pairing", pairing);
                obj.put("raw", raw);
            } catch (Exception ignored) {}
            return obj.toString();
        }

        /** Read-only status report. Never connects, reconnects or changes the configured mode. */
        @JavascriptInterface
        public String getWorkingMode() {
            try {
                JSONObject obj = new JSONObject();
                String devices = adbDevicesOutput();

                boolean tcpPortOpen = isPortOpen(adbTcpHost, adbTcpPort, 300);
                boolean tcpConnected = isTargetReady(devices, tcpTarget());
                JSONObject tcpObj = new JSONObject();
                tcpObj.put("host", adbTcpHost);
                tcpObj.put("port", adbTcpPort);
                tcpObj.put("portOpen", tcpPortOpen);
                tcpObj.put("connected", tcpConnected);
                obj.put("adbTcp", tcpObj);

                boolean wirelessConnected = adbWirelessPort > 0 && isTargetReady(devices, wirelessTarget());
                JSONObject wirelessObj = new JSONObject();
                wirelessObj.put("host", adbWirelessHost);
                wirelessObj.put("port", adbWirelessPort);
                wirelessObj.put("connected", wirelessConnected);
                obj.put("adbWireless", wirelessObj);

                JSONObject shizukuObj = shizukuStatus();
                obj.put("shizuku", shizukuObj);
                boolean shizukuAuthorized = shizukuObj.optBoolean("authorized", false);

                boolean rootAvailable = isRootAvailable();
                obj.put("rootAvailable", rootAvailable);
                obj.put("deviceIp", deviceWifiIp());

                String configured = activeWorkingMode;
                String resolved;
                boolean available;
                if ("auto".equals(configured)) {
                    if (tcpConnected) resolved = "adb_tcp";
                    else if (wirelessConnected) resolved = "adb_wireless";
                    else if (shizukuAuthorized) resolved = "shizuku";
                    else if (tcpPortOpen) resolved = "adb_tcp"; // connected lazily on the first command
                    else resolved = "unprivileged";
                    available = !"unprivileged".equals(resolved);
                } else {
                    resolved = configured;
                    if ("adb_tcp".equals(configured)) available = tcpConnected || tcpPortOpen;
                    else if ("adb_wireless".equals(configured)) available = wirelessConnected;
                    else if ("shizuku".equals(configured)) available = shizukuAuthorized;
                    else if ("root".equals(configured)) available = rootAvailable;
                    else available = false;
                }

                obj.put("configuredMode", configured);
                obj.put("activeMode", resolved);
                obj.put("modeAvailable", available);
                obj.put("isPrivileged", available && !"unprivileged".equals(resolved));
                return obj.toString();
            } catch (Exception e) {
                return "{\"activeMode\":\"unprivileged\",\"configuredMode\":\"auto\",\"isPrivileged\":false}";
            }
        }

        @JavascriptInterface
        public void setWorkingMode(String mode) {
            setConfiguredMode(mode);
        }

        /**
         * Explicitly switches the working mode. Always allowed, whatever is currently connected.
         * Returns {"ok":bool,"mode":"...","message":"..."}; ok=false means the mode was selected
         * but its backend is not ready yet (the message says what to do).
         */
        @JavascriptInterface
        public String selectWorkingMode(String mode) {
            JSONObject res = new JSONObject();
            try {
                if (mode == null || mode.trim().isEmpty()) mode = "auto";
                mode = mode.trim();
                boolean ok = true;
                String message;

                if ("adb_tcp".equals(mode)) {
                    if (!isAdbTargetConnected(tcpTarget())) {
                        connectTcp(adbTcpHost, adbTcpPort, false);
                    }
                    ok = isAdbTargetConnected(tcpTarget());
                    message = ok ? "Using ADB TCP " + tcpTarget() : "Selected ADB TCP, but " + tcpTarget() + " is not connected yet";
                } else if ("adb_wireless".equals(mode)) {
                    if (adbWirelessPort <= 0) {
                        ok = false;
                        message = "Selected Wireless Debugging. Enter the connect address and tap Connect.";
                    } else {
                        if (!isAdbTargetConnected(wirelessTarget())) {
                            connectWireless(adbWirelessHost, adbWirelessPort, false);
                        }
                        ok = isAdbTargetConnected(wirelessTarget());
                        message = ok ? "Using Wireless Debugging " + wirelessTarget() : "Selected Wireless Debugging, but " + wirelessTarget() + " is not connected";
                    }
                } else if ("shizuku".equals(mode)) {
                    ok = isShizukuAuthorized();
                    if (!ok) {
                        String req = requestShizukuPermission();
                        if ("not_installed".equals(req)) message = "Shizuku is not installed";
                        else if ("not_running".equals(req)) message = "Shizuku is not running. Start it in the Shizuku app first.";
                        else if ("pre_v11".equals(req)) message = "This Shizuku version is too old. Update Shizuku.";
                        else message = "Approve the Shizuku prompt to finish switching";
                    } else {
                        message = "Using Shizuku (UID " + Shizuku.getUid() + ")";
                    }
                } else if ("root".equals(mode)) {
                    ProcessBuilder pb = new ProcessBuilder("su", "-c", "id");
                    pb.redirectErrorStream(true);
                    String out = runProcessWithTimeout(pb, 15000);
                    ok = out != null && out.contains("uid=0");
                    message = ok ? "Using Root (Superuser)" : "Root was denied or is not available";
                } else if ("unprivileged".equals(mode)) {
                    message = "Switched to Read-Only Mode";
                } else {
                    mode = "auto";
                    message = "Automatic mode: best available backend is used";
                }

                setConfiguredMode(mode);
                res.put("ok", ok);
                res.put("mode", mode);
                res.put("message", message);
            } catch (Exception e) {
                try {
                    res.put("ok", false);
                    res.put("mode", activeWorkingMode);
                    res.put("message", "Error: " + e.getMessage());
                } catch (Exception ignored) {}
            }
            return res.toString();
        }

        @JavascriptInterface
        public String executeShell(String cmd) {
            if (cmd == null || cmd.trim().isEmpty()) return "";
            String mode = resolveExecMode();
            String output;

            if ("adb_tcp".equals(mode)) {
                if (!isAdbTargetConnected(tcpTarget())) {
                    performConnect(adbTcpHost, adbTcpPort, null);
                }
                output = runAdbShell(tcpTarget(), cmd);
            } else if ("adb_wireless".equals(mode)) {
                if (adbWirelessPort <= 0) {
                    output = "Error: Wireless Debugging is not configured. Connect it in Working Modes.";
                } else {
                    if (!isAdbTargetConnected(wirelessTarget())) {
                        performConnect(adbWirelessHost, adbWirelessPort, null);
                    }
                    output = runAdbShell(wirelessTarget(), cmd);
                }
            } else if ("shizuku".equals(mode)) {
                output = runShizukuShell(cmd);
            } else if ("root".equals(mode)) {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                pb.redirectErrorStream(true);
                output = runProcessWithTimeout(pb, 10000);
            } else {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                output = runProcessWithTimeout(pb, 6000);
            }

            Log.d(TAG, "executeShell [" + mode + "]: " + cmd + " -> " + (output != null ? output.trim() : ""));
            return output != null ? output : "";
        }

        @JavascriptInterface
        public String getSystemInfo() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("device", Build.MODEL);
                obj.put("manufacturer", Build.MANUFACTURER);
                obj.put("brand", Build.BRAND);
                obj.put("sdk", Build.VERSION.SDK_INT);
                obj.put("release", Build.VERSION.RELEASE);
                obj.put("materialYou", Build.VERSION.SDK_INT >= 31);
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String loadPackages() {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

                Set<String> runningPkgs = new HashSet<String>();
                Set<String> disabledPkgs = new HashSet<String>();
                Set<String> uninstalledPkgs = new HashSet<String>();

                if (!"standard".equals(resolveExecMode())) {
                    try {
                        String out = executeShell("dumpsys activity processes | grep -E 'APP.*ProcessRecord\\{' | sed -E 's/^.*\\{[^:]+:([^:/ ]+).*$/\\1/g' | sort | uniq");
                        for (String line : out.split("\n")) {
                            String p = line.trim();
                            if (!p.isEmpty()) runningPkgs.add(p);
                        }
                    } catch (Exception ignored) {}

                    try {
                        String out = executeShell("pm list packages -d | cut -f 2 -d ':'");
                        for (String line : out.split("\n")) {
                            String p = line.trim();
                            if (!p.isEmpty()) disabledPkgs.add(p);
                        }
                    } catch (Exception ignored) {}

                    try {
                        String allWithUninstalled = executeShell("pm list packages -u | cut -f 2 -d ':'");
                        String installed = executeShell("pm list packages | cut -f 2 -d ':'");
                        Set<String> installedSet = new HashSet<String>();
                        for (String line : installed.split("\n")) {
                            String p = line.trim();
                            if (!p.isEmpty()) installedSet.add(p);
                        }
                        for (String line : allWithUninstalled.split("\n")) {
                            String p = line.trim();
                            if (!p.isEmpty() && !installedSet.contains(p)) uninstalledPkgs.add(p);
                        }
                    } catch (Exception ignored) {}
                }

                JSONArray arr = new JSONArray();
                for (ApplicationInfo info : apps) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", info.packageName);
                    String label = pm.getApplicationLabel(info).toString();
                    if (label == null || label.isEmpty()) label = info.packageName;
                    o.put("name", label);
                    o.put("isSystem", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    o.put("isRunning", runningPkgs.contains(info.packageName));
                    o.put("isFrozen", disabledPkgs.contains(info.packageName) || !info.enabled);
                    // Readable in every mode, including Read-Only
                    o.put("isSuspended", (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
                    o.put("isUninstalled", false);
                    o.put("targetSdk", info.targetSdkVersion);
                    arr.put(o);
                }
                for (String pkg : uninstalledPkgs) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("name", pkg);
                    o.put("isSystem", true);
                    o.put("isRunning", false);
                    o.put("isFrozen", true);
                    o.put("isSuspended", false);
                    o.put("isUninstalled", true);
                    o.put("targetSdk", 0);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String executeAppAction(String action, String pkg) {
            try {
                if ("freeze".equals(action)) return executeShell("pm disable-user " + pkg);
                if ("unfreeze".equals(action)) {
                    executeShell("pm enable " + pkg);
                    return executeShell("pm unsuspend " + pkg);
                }
                if ("suspend".equals(action)) return executeShell("pm suspend " + pkg);
                if ("unsuspend".equals(action)) return executeShell("pm unsuspend " + pkg);
                if ("force_stop".equals(action)) return executeShell("am force-stop " + pkg);
                if ("clear_data".equals(action)) return executeShell("pm clear " + pkg);
                if ("uninstall".equals(action)) return executeShell("pm uninstall --user 0 " + pkg);
                if ("uninstall_keep_data".equals(action)) return executeShell("pm uninstall -k --user 0 " + pkg);
                if ("uninstall_updates".equals(action)) return executeShell("pm uninstall-system-updates " + pkg);
                if ("reinstall".equals(action)) return executeShell("pm install-existing " + pkg);
                if ("launch".equals(action)) {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        return "Launched";
                    }
                    return executeShell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
                }
                if ("app_settings".equals(action)) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + pkg));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return "Settings opened";
                }
                return "";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String executeBatchAction(String action, String pkgsJson) {
            try {
                JSONObject detailed = new JSONObject(executeBatchActionDetailed(action, pkgsJson));
                return "{\"success\":" + detailed.optInt("success", 0) + ",\"failed\":" + detailed.optInt("failed", 0) + "}";
            } catch (Exception e) {
                return "{\"success\":0,\"failed\":1}";
            }
        }

        @JavascriptInterface
        public String executeBatchActionDetailed(String action, String pkgsJson) {
            JSONArray results = new JSONArray();
            int success = 0;
            int failed = 0;
            try {
                JSONArray pkgs = new JSONArray(pkgsJson);
                for (int i = 0; i < pkgs.length(); i++) {
                    String pkg = pkgs.getString(i);
                    String output = executeAppAction(action, pkg);
                    if (output == null) output = "";
                    String lower = output.toLowerCase();
                    boolean ok = !(lower.contains("error") || lower.contains("failed") || lower.contains("exception"));
                    if (ok) success++; else failed++;
                    JSONObject row = new JSONObject();
                    row.put("pkg", pkg);
                    row.put("name", pkg);
                    row.put("output", output);
                    row.put("success", ok);
                    results.put(row);
                }
            } catch (Exception e) {
                failed++;
                try {
                    JSONObject row = new JSONObject();
                    row.put("pkg", "batch");
                    row.put("name", "batch");
                    row.put("output", "Error: " + e.getMessage());
                    row.put("success", false);
                    results.put(row);
                } catch (Exception ignored) {}
            }
            try {
                JSONObject res = new JSONObject();
                res.put("success", success);
                res.put("failed", failed);
                res.put("results", results);
                return res.toString();
            } catch (Exception e) {
                return "{\"success\":0,\"failed\":1,\"results\":[]}";
            }
        }

        @JavascriptInterface
        public String getAppDetails(String pkg) {
            try {
                JSONObject obj = new JSONObject();
                try {
                    int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES
                            | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS
                            | PackageManager.MATCH_DISABLED_COMPONENTS;
                    PackageInfo info = getPackageManager().getPackageInfo(pkg, flags);
                    obj.put("versionName", info.versionName != null ? info.versionName : "N/A");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        obj.put("versionCode", info.getLongVersionCode());
                    } else {
                        obj.put("versionCode", info.versionCode);
                    }
                    obj.put("firstInstallTime", info.firstInstallTime);
                    obj.put("lastUpdateTime", info.lastUpdateTime);
                    if (info.applicationInfo != null) {
                        obj.put("sourceDir", info.applicationInfo.sourceDir);
                        obj.put("dataDir", info.applicationInfo.dataDir);
                        obj.put("targetSdk", info.applicationInfo.targetSdkVersion);
                        obj.put("minSdk", info.applicationInfo.minSdkVersion);
                    }

                    JSONArray perms = new JSONArray();
                    if (info.requestedPermissions != null) {
                        PackageManager pm = getPackageManager();
                        for (int i = 0; i < info.requestedPermissions.length; i++) {
                            String permName = info.requestedPermissions[i];
                            JSONObject p = new JSONObject();
                            p.put("name", permName);
                            boolean granted = info.requestedPermissionsFlags != null
                                    && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                            p.put("granted", granted);
                            describePermission(pm, permName, p);
                            perms.put(p);
                        }
                    }
                    obj.put("permissions", perms);

                    JSONArray activities = new JSONArray();
                    JSONArray activityInfo = new JSONArray();
                    if (info.activities != null) {
                        for (ActivityInfo a : info.activities) {
                            activities.put(a.name);
                            JSONObject ao = new JSONObject();
                            ao.put("name", a.name);
                            ao.put("exported", a.exported);
                            ao.put("enabled", a.enabled);
                            ao.put("permission", a.permission != null ? a.permission : "");
                            activityInfo.put(ao);
                        }
                    }
                    obj.put("activities", activities);
                    obj.put("activityInfo", activityInfo);

                    JSONArray services = new JSONArray();
                    if (info.services != null) {
                        for (ServiceInfo s : info.services) services.put(s.name);
                    }
                    obj.put("services", services);
                } catch (Exception e) {
                    obj.put("error", e.getMessage());
                }

                try {
                    String appops = executeShell("cmd appops get " + pkg);
                    obj.put("appopsRaw", appops != null ? appops : "");
                } catch (Exception ignored) {}

                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /**
         * Launches an activity. Exported activities start with a normal intent; unexported ones are
         * started with `am start` through the active privileged mode (ADB / Shizuku / Root). Whatever
         * Android answers is returned as-is, so a refused launch reports the system's error.
         * Returns {"ok":bool,"method":"intent|shell","output":"..."}
         */
        @JavascriptInterface
        public String launchActivity(String pkg, String cls, boolean exported) {
            JSONObject res = new JSONObject();
            try {
                if (pkg == null || cls == null || !pkg.matches("[A-Za-z0-9._]+") || !cls.matches("[A-Za-z0-9._$]+")) {
                    res.put("ok", false);
                    res.put("method", "none");
                    res.put("output", "Error: invalid component name");
                    return res.toString();
                }
                String fullCls = cls.startsWith(".") ? pkg + cls : cls;

                if (exported) {
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(pkg, fullCls);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        res.put("ok", true);
                        res.put("method", "intent");
                        res.put("output", "Started " + pkg + "/" + fullCls);
                        return res.toString();
                    } catch (Exception e) {
                        // Fall through to the privileged shell (e.g. permission-protected activity)
                        Log.w(TAG, "Intent launch failed, trying shell: " + e.getMessage());
                    }
                }

                if ("standard".equals(resolveExecMode())) {
                    res.put("ok", false);
                    res.put("method", "shell");
                    res.put("output", "Error: launching this activity needs ADB, Shizuku or Root. Set up a working mode first.");
                    return res.toString();
                }

                String output = executeShell("am start -W -n '" + pkg + "/" + fullCls + "'");
                String lower = output.toLowerCase();
                boolean ok = lower.contains("status: ok") || (lower.contains("starting: intent")
                        && !lower.contains("error") && !lower.contains("exception") && !lower.contains("permission denial"));
                res.put("ok", ok);
                res.put("method", "shell");
                res.put("output", output);
            } catch (Exception e) {
                try {
                    res.put("ok", false);
                    res.put("method", "none");
                    res.put("output", "Error: " + e.getMessage());
                } catch (Exception ignored) {}
            }
            return res.toString();
        }

        @JavascriptInterface
        public void checkForUpdates() {
            runUpdateCheck();
        }

        @JavascriptInterface
        public String getUpdateState() {
            try {
                JSONObject o = new JSONObject();
                o.put("running", updateCheckRunning);
                o.put("checkedAt", updateCheckedAt);
                o.put("updates", updatesArray());
                o.put("installing", new JSONArray(new ArrayList<String>(installingUpdates)));
                o.put("currentVersion", currentVersionName());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void installUpdate(String pkg) {
            runInstallUpdate(pkg);
        }

        /** Tracks an app's releases at a GitHub / Codeberg / F-Droid URL. Empty url removes it. */
        @JavascriptInterface
        public String setUpdateSource(String pkg, String url) {
            try {
                JSONObject sources = updateSources();
                if (url == null || url.trim().isEmpty()) {
                    sources.remove(pkg);
                } else {
                    String u = url.trim();
                    if (!u.startsWith("https://")) throw new IllegalStateException("use an https:// link");
                    if (UpdateManager.parseRepoUrl(u) == null && UpdateManager.parseFdroidUrl(u) == null) {
                        throw new IllegalStateException("supported: github.com/owner/repo, codeberg.org/owner/repo or an F-Droid package link");
                    }
                    JSONObject entry = new JSONObject();
                    entry.put("url", u);
                    entry.put("origin", "yours");
                    sources.put(pkg, entry);
                }
                saveUpdateSources(sources);
                return "ok";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String getUpdateSources() {
            return updateSources().toString();
        }

        /** Opens the system file picker for an Obtainium export; result via window.onObtainiumImported(json) */
        @JavascriptInterface
        public void importObtainiumExport() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                    try {
                        startActivityForResult(i, REQ_IMPORT_OBTAINIUM);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void setGithubToken(String token) {
            prefs.edit().putString("github_token", token == null ? "" : token.trim()).apply();
        }

        @JavascriptInterface
        public boolean hasGithubToken() {
            return prefs.getString("github_token", "").length() > 0;
        }

        /** Opens obtainium:// links (falls back to the Obtainium web catalog when Obtainium isn't installed) */
        @JavascriptInterface
        public void openObtainiumLink(final String link) {
            if (link == null || !link.startsWith("obtainium://")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://apps.obtainium.imranr.dev/redirect?r=" + Uri.encode(link)));
                            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(web);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveStore(String key, String json) {
            if (UI_STORE_KEYS.contains(key) && json != null && json.length() < 512 * 1024) {
                prefs.edit().putString("store_" + key, json).apply();
            }
        }

        @JavascriptInterface
        public String loadStore(String key) {
            return UI_STORE_KEYS.contains(key) ? prefs.getString("store_" + key, "") : "";
        }

        @JavascriptInterface
        public String getUadStatus() {
            return uadStatus().toString();
        }

        /** Downloads the latest UAD-NG list in the background; result arrives via window.onUadListUpdated(json). */
        @JavascriptInterface
        public void updateUadList() {
            downloadUadList();
        }

        @JavascriptInterface
        public String getUadMatches() {
            return uadMatches();
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null || !url.startsWith("https://")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getAdbKeyInfo() {
            return adbKeyInfo().toString();
        }

        @JavascriptInterface
        public void dismissAdbKeyNotice() {
            prefs.edit().putBoolean("adb_key_notice_pending", false).apply();
        }

        /** Creates a brand-new ADB key. Existing ADB connections must be re-approved on the phone. */
        @JavascriptInterface
        public String regenerateAdbKey() {
            try {
                runProcessWithTimeout(buildAdbProcess("disconnect"), 3000);
                AdbKeyManager.generate(adbKeyFile(), adbPubKeyFile(), adbKeyName());
                onAdbKeyReplaced(false);
                return adbKeyInfo().toString();
            } catch (Exception e) {
                return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            }
        }

        /** Decoded AndroidManifest.xml of an installed package, or "Error: ..." */
        @JavascriptInterface
        public String getAppManifest(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                Resources res = null;
                try {
                    res = pm.getResourcesForApplication(ai);
                } catch (Exception ignored) {}
                return ManifestDecoder.decodeApk(ai.sourceDir, res);
            } catch (Throwable t) {
                return "Error: " + t.getMessage();
            }
        }

        /**
         * Saves text to Downloads/ADB App Manager/ (MediaStore on Android 10+, app storage before that).
         * Returns the saved location or "Error: ...".
         */
        @JavascriptInterface
        public String saveTextToDownloads(String fileName, String text) {
            String safeName = (fileName == null ? "export.txt" : fileName).replaceAll("[^A-Za-z0-9._-]", "_");
            byte[] bytes = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, safeName.endsWith(".xml") ? "text/xml" : "text/plain");
                    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/ADB App Manager");
                    Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "Error: could not create file";
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(bytes);
                    out.close();
                    return "Download/ADB App Manager/" + safeName;
                }
                File dir = new File(getExternalFilesDir(null), "exports");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, safeName);
                OutputStream out = new FileOutputStream(file);
                out.write(bytes);
                out.close();
                return file.getAbsolutePath();
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String getAppOpsRaw(String pkg) {
            return executeShell("cmd appops get " + pkg);
        }

        @JavascriptInterface
        public String setAppOp(String pkg, String op, String mode) {
            return executeShell("appops set " + pkg + " " + op + " " + mode);
        }

        @JavascriptInterface
        public String setPermission(String pkg, String perm, boolean grant) {
            return grant ? executeShell("pm grant " + pkg + " " + perm) : executeShell("pm revoke " + pkg + " " + perm);
        }

        @JavascriptInterface
        public void savePreferences(String jsonStr) {
            prefs.edit().putString("custom_settings", jsonStr).apply();
        }

        @JavascriptInterface
        public String loadPreferences() {
            return prefs.getString("custom_settings", "{}");
        }

        @JavascriptInterface
        public void saveCustomLists(String jsonStr) {
            prefs.edit().putString("custom_app_lists", jsonStr).apply();
        }

        @JavascriptInterface
        public String loadCustomLists() {
            return prefs.getString("custom_app_lists", "[]");
        }

        @JavascriptInterface
        public void copyToClipboard(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("ADB App Manager", text));
                            Toast.makeText(MainActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "copyToClipboard failed", e);
                    }
                }
            });
        }

        /** Tints the status and navigation bars to match the active theme background. */
        @JavascriptInterface
        public void setSystemBarColor(final String hex) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int color = Color.parseColor(hex);
                        getWindow().setStatusBarColor(color);
                        getWindow().setNavigationBarColor(color);
                        if (webView != null) webView.setBackgroundColor(color);
                        // Dark icons on light backgrounds, light icons on dark ones
                        double lum = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
                        boolean lightBars = lum > 0.6;
                        android.view.View decor = getWindow().getDecorView();
                        int flags = decor.getSystemUiVisibility();
                        int lightFlags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        decor.setSystemUiVisibility(lightBars ? (flags | lightFlags) : (flags & ~lightFlags));
                    } catch (Exception ignored) {}
                }
            });
        }

        /**
         * Material You (Material Design 3 dynamic color) palette derived from the wallpaper on Android 12+.
         * Returns {"supported":false} on older versions.
         */
        @JavascriptInterface
        public String getMaterialYouColors() {
            JSONObject obj = new JSONObject();
            try {
                if (Build.VERSION.SDK_INT < 31) {
                    obj.put("supported", false);
                    return obj.toString();
                }
                obj.put("supported", true);
                obj.put("dark", materialYouScheme(true));
                obj.put("light", materialYouScheme(false));
            } catch (Exception e) {
                try {
                    obj.put("supported", false);
                    obj.put("error", e.getMessage());
                } catch (Exception ignored) {}
            }
            return obj.toString();
        }

        /** True when the phone is in dark mode (Settings > Display > Dark mode). */
        @JavascriptInterface
        public boolean isSystemDarkMode() {
            int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is handled here (see configChanges) so a dark mode switch re-themes without restarting
        notifyJs("window.onSystemAppearanceChanged && window.onSystemAppearanceChanged()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyJs("window.onAppResume && window.onAppResume()");
    }

    private Object wallpaperColorsListener;

    private void registerWallpaperListener() {
        if (Build.VERSION.SDK_INT < 27) return;
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
            android.app.WallpaperManager.OnColorsChangedListener l = new android.app.WallpaperManager.OnColorsChangedListener() {
                @Override
                public void onColorsChanged(android.app.WallpaperColors colors, int which) {
                    // The system palette updates shortly after the wallpaper; give it a moment
                    if (webView != null) {
                        webView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                notifyJs("window.onAppResume && window.onAppResume()");
                            }
                        }, 2000);
                    }
                }
            };
            wm.addOnColorsChangedListener(l, new android.os.Handler(android.os.Looper.getMainLooper()));
            wallpaperColorsListener = l;
        } catch (Throwable t) {
            Log.w(TAG, "Wallpaper listener unavailable: " + t.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= 27 && wallpaperColorsListener != null) {
            try {
                android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(
                        (android.app.WallpaperManager.OnColorsChangedListener) wallpaperColorsListener);
            } catch (Throwable ignored) {}
        }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        } catch (Throwable ignored) {}
        try {
            executor.shutdown();
        } catch (Exception ignored) {}
    }
}
