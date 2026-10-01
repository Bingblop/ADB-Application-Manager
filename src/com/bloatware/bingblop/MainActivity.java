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

            File keyFile = new File(dotAndroid, "adbkey");
            File pubFile = new File(dotAndroid, "adbkey.pub");

            extractAsset("adbkey", keyFile);
            keyFile.setReadable(true, false);
            keyFile.setWritable(true, false);

            extractAsset("adbkey.pub", pubFile);
            pubFile.setReadable(true, false);
            pubFile.setWritable(true, false);

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
                            | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS;
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
                    if (info.activities != null) {
                        for (ActivityInfo a : info.activities) activities.put(a.name);
                    }
                    obj.put("activities", activities);

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
                int neutral900 = systemColor("system_neutral1_900");
                int neutral800 = systemColor("system_neutral1_800");
                obj.put("supported", true);
                obj.put("accent", colorHex(systemColor("system_accent1_200")));
                obj.put("bg", colorHex(mixColor(neutral900, 0xFF000000, 0.35f)));
                obj.put("surface", colorHex(neutral900));
                obj.put("card", colorHex(mixColor(neutral900, neutral800, 0.45f)));
                obj.put("running", colorHex(systemColor("system_accent3_200")));
                obj.put("frozen", colorHex(systemColor("system_accent1_200")));
                obj.put("system", colorHex(systemColor("system_accent2_200")));
                obj.put("bloat", "#F2B8B5");
                obj.put("text", colorHex(systemColor("system_neutral1_100")));
                obj.put("muted", colorHex(systemColor("system_neutral2_200")));
            } catch (Exception e) {
                try {
                    obj.put("supported", false);
                    obj.put("error", e.getMessage());
                } catch (Exception ignored) {}
            }
            return obj.toString();
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
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        } catch (Throwable ignored) {}
        try {
            executor.shutdown();
        } catch (Exception ignored) {}
    }
}
