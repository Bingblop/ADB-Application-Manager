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
    private boolean pageReady = false;
    // An APK opened from outside (default-installer intent) waiting to be handed to the Installer tab.
    private String pendingInstallRef = null;
    private Vibrator vibrator;
    private SharedPreferences prefs;
    private boolean buildChangedThisLaunch = false;

    /** True for QuickActionActivity: run a tile/widget action without building the UI. */
    protected boolean isHeadless() {
        return false;
    }

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

        // A changed build fingerprint means the system was updated since the app last ran
        // (a tile or widget tap runs headless and must not use up the flag before the user opens the app)
        if (!isHeadless()) {
            String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
            String seenFingerprint = prefs.getString("app_fp", null);
            buildChangedThisLaunch = seenFingerprint != null && !seenFingerprint.equals(fingerprint);
            prefs.edit().putString("app_fp", fingerprint).apply();
        }

        // Extract binaries and ADB keys in background
        setupBinariesAndKeys();

        if (isHeadless()) {
            runQuickAction(getIntent());
            return;
        }

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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverPendingInstall();
            }
        });
        webView.setBackgroundColor(0xFF080A0F);

        WebView.setWebContentsDebuggingEnabled(true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
        registerWallpaperListener();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    /** Picks up an APK opened from outside (the default-installer intent-filter) and remembers it. */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !"android.intent.action.INSTALL_PACKAGE".equals(action)) return;
        Uri data = intent.getData();
        if (data == null) return;
        pendingInstallRef = "file".equals(data.getScheme()) ? data.getPath() : data.toString();
        deliverPendingInstall();
    }

    /** Hands a pending opened-APK to the Installer tab once the WebView is ready. */
    private void deliverPendingInstall() {
        if (!pageReady || pendingInstallRef == null) return;
        final String ref = pendingInstallRef;
        pendingInstallRef = null;
        notifyJs("window.onInstallIntent && window.onInstallIntent(" + JSONObject.quote(ref) + ")");
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
    // Quick actions (Quick Settings tiles and the home-screen widget run these through QuickActionActivity)
    // ---------------------------------------------------------------------------------------------

    private void runQuickAction(Intent intent) {
        final String action = intent == null ? null : intent.getStringExtra(QuickActions.EXTRA_ACTION);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    if (QuickActions.ACTION_CYCLE_MODE.equals(action)) message = quickCycleMode();
                    else if (QuickActions.ACTION_STOP_LIST.equals(action)) message = quickStopList();
                    else message = "Unknown quick action";
                } catch (Exception e) {
                    message = "Failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
                finishQuickAction(message);
            }
        });
    }

    private void finishQuickAction(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                QuickWidgetProvider.refreshAll(MainActivity.this);
                try {
                    android.service.quicksettings.TileService.requestListeningState(MainActivity.this, new android.content.ComponentName(MainActivity.this, ModeTileService.class));
                    android.service.quicksettings.TileService.requestListeningState(MainActivity.this, new android.content.ComponentName(MainActivity.this, StopListTileService.class));
                } catch (Throwable ignored) {}
                finish();
            }
        });
    }

    /** Is this backend usable right now, without showing any prompt? */
    private boolean modeReadyQuiet(String mode) {
        if ("adb_tcp".equals(mode)) return isAdbTargetConnected(tcpTarget());
        if ("adb_wireless".equals(mode)) return adbWirelessPort > 0 && isAdbTargetConnected(wirelessTarget());
        if ("shizuku".equals(mode)) return isShizukuAuthorized();
        if ("root".equals(mode)) return isRootAvailable();
        return false;
    }

    /** Switches to the next working mode that is ready (ADB TCP, Wireless, Shizuku, Root), else back to Automatic. */
    private String quickCycleMode() {
        String[] order = {"adb_tcp", "adb_wireless", "shizuku", "root"};
        int start = -1;
        for (int i = 0; i < order.length; i++) if (order[i].equals(activeWorkingMode)) start = i;
        // Starting from an explicit mode, only look at the OTHER backends (exclude step == order.length,
        // which would wrap back to the current one and "switch" to the mode already selected).
        int steps = start == -1 ? order.length : order.length - 1;
        for (int step = 1; step <= steps; step++) {
            String candidate = order[(start + step) % order.length];
            if (modeReadyQuiet(candidate)) {
                setConfiguredMode(candidate);
                return "Working mode: " + QuickActions.modeLabel(candidate);
            }
        }
        setConfiguredMode("auto");
        return "No other mode is ready. Using Automatic.";
    }

    /** Force-stops every app in the quick list. */
    private String quickStopList() throws Exception {
        JSONObject list = QuickActions.quickList(this);
        if (list == null) return "No quick list set. Open Saved Lists in the app and tap \u26a1 Quick list.";
        if ("standard".equals(resolveExecMode())) return "Needs ADB, Shizuku or Root. Set up a working mode first.";
        org.json.JSONArray pkgs = list.optJSONArray("packages");
        AndroidBridge bridge = new AndroidBridge();
        int ok = 0, failed = 0;
        for (int i = 0; pkgs != null && i < pkgs.length(); i++) {
            String out = bridge.executeAppAction("force_stop", pkgs.optString(i)).toLowerCase();
            if (out.contains("error") || out.contains("exception") || out.contains("denied")) failed++;
            else ok++;
        }
        return "Force-stopped " + ok + " app" + (ok == 1 ? "" : "s") + (failed > 0 ? ", " + failed + " failed" : "");
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
            // The shell user can't read app-private files, so stream the APK into `pm install -S`
            return shizukuStream("pm install -r -S " + apk.length(), apk);
        }
        if ("root".equals(mode)) {
            apk.setReadable(true, false);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "pm install -r '" + apk.getAbsolutePath() + "'");
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 300000);
        }
        return "Error: installing updates needs ADB, Shizuku or Root";
    }

    /** Runs a command through the Shizuku API, optionally streaming a file into its stdin. */
    private String shizukuStream(String cmd, File stdinFile) throws Exception {
        if (shizukuNewProcessMethod == null) {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            shizukuNewProcessMethod = m;
        }
        Process p = (Process) shizukuNewProcessMethod.invoke(null,
                new String[]{"sh", "-c", "exec 2>&1; " + cmd}, null, null);
        OutputStream os = p.getOutputStream();
        if (stdinFile != null) {
            java.io.FileInputStream in = new java.io.FileInputStream(stdinFile);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                in.close();
            }
        }
        os.flush();
        os.close();
        return readProcessWithTimeout(p, 300000);
    }

    /** Installs one APK, or a base APK with its splits, through the active mode. */
    private String installApks(List<File> apks) throws Exception {
        String mode = resolveExecMode();
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            boolean tcp = "adb_tcp".equals(mode);
            String target = tcp ? tcpTarget() : wirelessTarget();
            if (!isAdbTargetConnected(target)) performConnect(tcp ? adbTcpHost : adbWirelessHost, tcp ? adbTcpPort : adbWirelessPort, null);
            List<String> args = new ArrayList<String>();
            args.add("-s");
            args.add(target);
            args.add(apks.size() == 1 ? "install" : "install-multiple");
            args.add("-r");
            for (File f : apks) args.add(f.getAbsolutePath());
            return runProcessWithTimeout(buildAdbProcess(args.toArray(new String[0])), 600000);
        }
        if ("shizuku".equals(mode)) {
            String created = shizukuStream("pm install-create -r", null);
            String id = BackupScripts.parseSessionId(created);
            if (id == null) return created;
            // A thrown exception mid-write (not just a "Success"-less return) must still abandon the
            // session - otherwise a staged PackageInstaller session and its backing files are left behind
            // with no owner. Only a successful commit excuses the cleanup below.
            boolean committed = false;
            try {
                int index = 0;
                for (File f : apks) {
                    String name = (index++) + "_" + f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                    String written = shizukuStream("pm install-write -S " + f.length() + " " + id + " " + name + " -", f);
                    if (!written.contains("Success")) return written;
                }
                String result = shizukuStream("pm install-commit " + id, null);
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        shizukuStream("pm install-abandon " + id, null);
                    } catch (Exception ignored) {}
                }
            }
        }
        if ("root".equals(mode)) {
            StringBuilder cmd = new StringBuilder(apks.size() == 1 ? "pm install -r" : "pm install-multiple -r");
            for (File f : apks) {
                f.setReadable(true, false);
                cmd.append(' ').append(BackupScripts.quote(f.getAbsolutePath()));
            }
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd.toString());
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 600000);
        }
        return "Error: restoring needs ADB, Shizuku or Root";
    }

    // ---------------------------------------------------------------------------------------------
    // All-in-one installer (APK / APKS / APKM) - the "Installer" tab.
    // Reuses the same per-backend install shapes as installApks() above, but with a caller-chosen set
    // of pm flags and a caller-chosen authorizer, plus a no-privilege PackageInstaller fallback. Picked
    // packages are staged into getCacheDir()/installer; nothing outside that directory is ever installed.
    // ---------------------------------------------------------------------------------------------

    /** The real current enabled state of a component: the pm override if one is set, else the manifest default. */
    private boolean componentEnabled(String pkg, String name, boolean manifestEnabled) {
        try {
            int st = getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(pkg, name));
            if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false;
        } catch (Exception ignored) {}
        return manifestEnabled;
    }

    private JSONObject componentEntry(String pkg, String name, boolean exported, boolean manifestEnabled, String permission) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("exported", exported);
        o.put("enabled", componentEnabled(pkg, name, manifestEnabled));
        o.put("permission", permission != null ? permission : "");
        return o;
    }

    private File installerWorkDir() {
        return new File(getCacheDir(), "installer");
    }

    private void clearInstallerWorkDir() {
        File[] fs = installerWorkDir().listFiles();
        if (fs != null) for (File f : fs) { try { f.delete(); } catch (Exception ignored) {} }
    }

    /** The split="..." value from an APK's binary manifest, or null for a base APK. Best-effort. */
    private String manifestSplitName(File apk) {
        try {
            String xml = ManifestDecoder.decodeApk(apk.getAbsolutePath(), null);
            if (xml == null) return null;
            // A config/feature split declares <manifest ... split="config.xxxhdpi">; a base APK has no such
            // attribute. \bsplit= deliberately won't match splitName=/splitTypes= on other elements.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bsplit\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Streams a picked .apk/.apks/.apkm into the installer cache and returns its package info + split list. */
    private JSONObject inspectInstallSourceImpl(String ref) throws Exception {
        clearInstallerWorkDir();
        File work = installerWorkDir();
        work.mkdirs();
        // A content:// stream is one-shot, so copy it to the cache once (capped like restore, so a crafted
        // archive can't fill storage) and work from concrete files afterwards.
        File raw = new File(work, "source.bin");
        long copied = 0;
        InputStream in0 = openRef(ref);
        try {
            FileOutputStream out = new FileOutputStream(raw);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in0.read(buf)) > 0) {
                    copied += r;
                    if (copied > MAX_RESTORE_EXTRACT_BYTES) throw new IllegalStateException("file is far too large to be an app package");
                    out.write(buf, 0, r);
                }
            } finally { out.close(); }
        } finally { in0.close(); }

        PackageManager pm = getPackageManager();
        int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;

        List<File> splits = new ArrayList<File>();
        File base = null;
        String type;

        // A lone APK is itself a ZIP, so tell them apart by whether the file parses as an installable APK.
        PackageInfo direct = pm.getPackageArchiveInfo(raw.getAbsolutePath(), 0);
        if (direct != null && direct.packageName != null) {
            type = "apk";
            File apk = new File(work, "base.apk");
            if (!raw.renameTo(apk)) apk = raw;
            base = apk;
            splits.add(apk);
        } else {
            // An .apks (bundletool) or .apkm (APKMirror) archive: a ZIP of split APKs. Extract every *.apk
            // entry through the same byte-capped loop restore uses (an unwanted entry is still drained,
            // never skipped, so the cap can't be bypassed by a huge entry under an unrecognized name).
            type = "apks";
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(raw)));
            long extracted = 0;
            int idx = 0;
            boolean sawApkm = false;
            try {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    String low = n.toLowerCase(java.util.Locale.US);
                    if (low.endsWith("info.json") || low.endsWith(".sai_v2.json") || low.endsWith("icon.png")) sawApkm = true;
                    File dest = null;
                    if (low.endsWith(".apk")) {
                        String flat = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
                        dest = new File(work, (idx++) + "__" + flat);
                    }
                    OutputStream out = dest != null ? new FileOutputStream(dest) : new OutputStream() {
                        public void write(int b) {}
                        public void write(byte[] b, int off, int len) {}
                    };
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = zin.read(buf)) > 0) {
                            extracted += r;
                            if (extracted > MAX_RESTORE_EXTRACT_BYTES) throw new IllegalStateException("archive is far too large to be an app package");
                            out.write(buf, 0, r);
                        }
                    } finally { out.close(); }
                    if (dest != null) splits.add(dest);
                }
            } finally { zin.close(); }
            raw.delete();
            if (sawApkm) type = "apkm";
            if (splits.isEmpty()) throw new IllegalStateException("no APKs found inside this archive");
            // Base = the split whose manifest has no split="..." attribute.
            for (File f : splits) {
                if (manifestSplitName(f) == null) { base = f; break; }
            }
            if (base == null) base = splits.get(0);
        }

        PackageInfo archive = pm.getPackageArchiveInfo(base.getAbsolutePath(), sigFlags);
        if (archive == null || archive.packageName == null) throw new IllegalStateException("could not read the base APK of this package");
        final String pkg = archive.packageName;

        JSONObject res = new JSONObject();
        res.put("ref", ref);
        res.put("type", type);
        res.put("pkg", pkg);
        res.put("versionName", archive.versionName != null ? archive.versionName : "");
        res.put("versionCode", versionCodeOf(archive));
        if (archive.applicationInfo != null) {
            try {
                CharSequence lbl = pm.getApplicationLabel(archive.applicationInfo);
                if (lbl != null) res.put("label", lbl.toString());
            } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= 24) res.put("minSdk", archive.applicationInfo.minSdkVersion);
            res.put("targetSdk", archive.applicationInfo.targetSdkVersion);
        }

        List<File> ordered = new ArrayList<File>();
        ordered.add(base);
        for (File f : splits) if (f != base) ordered.add(f);
        long total = 0;
        JSONArray arr = new JSONArray();
        for (File f : ordered) {
            total += f.length();
            String split = (f == base) ? null : manifestSplitName(f);
            JSONObject s = new JSONObject();
            s.put("path", f.getAbsolutePath());
            s.put("name", f.getName());
            s.put("size", f.length());
            s.put("isBase", f == base);
            s.put("split", split != null ? split : "");
            arr.put(s);
        }
        res.put("splits", arr);
        res.put("totalSize", total);

        // Signer of the archive + comparison to an already-installed copy, for the two signature gates.
        java.util.Set<String> archiveSigners = signerDigests(archive, false);
        res.put("signed", !archiveSigners.isEmpty());
        try {
            PackageInfo cur = pm.getPackageInfo(pkg, sigFlags);
            res.put("installed", true);
            res.put("installedVersionName", cur.versionName != null ? cur.versionName : "");
            res.put("installedVersionCode", versionCodeOf(cur));
            java.util.Set<String> curSigners = signerDigests(cur, true);
            java.util.Set<String> common = new HashSet<String>(curSigners);
            common.retainAll(archiveSigners);
            res.put("signerMatchesInstalled", !curSigners.isEmpty() && !archiveSigners.isEmpty() && !common.isEmpty());
        } catch (PackageManager.NameNotFoundException nf) {
            res.put("installed", false);
        }
        return res;
    }

    /** Maps the installer's authorizer choice to a concrete backend mode. */
    private String resolveAuthorizerMode(String authorizer) {
        if ("shizuku".equals(authorizer)) return "shizuku";
        if ("root".equals(authorizer)) return "root";
        if ("none".equals(authorizer)) return "none";
        if ("adb".equals(authorizer)) {
            String m = resolveExecMode();
            if ("adb_tcp".equals(m) || "adb_wireless".equals(m)) return m;
            if (isAdbTargetConnected(tcpTarget())) return "adb_tcp";
            if (isAdbTargetConnected(wirelessTarget())) return "adb_wireless";
            return "adb_tcp";
        }
        return resolveExecMode();
    }

    /** Installs the given files (base first) with a caller-built flag set, via a specific backend. */
    private String installApksOptions(List<File> apks, String createFlags, String mode) throws Exception {
        if (createFlags == null || createFlags.trim().isEmpty()) createFlags = "-r";
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            boolean tcp = "adb_tcp".equals(mode);
            String target = tcp ? tcpTarget() : wirelessTarget();
            if (!isAdbTargetConnected(target)) performConnect(tcp ? adbTcpHost : adbWirelessHost, tcp ? adbTcpPort : adbWirelessPort, null);
            List<String> args = new ArrayList<String>();
            args.add("-s");
            args.add(target);
            args.add(apks.size() == 1 ? "install" : "install-multiple");
            // adb forwards -r/-g/-d/-t natively and passes the pm-only flags (--user, --install-reason,
            // --package-source, --update-ownership, --bypass-low-target-sdk-block) through to install-create.
            for (String fl : createFlags.trim().split("\\s+")) if (!fl.isEmpty()) args.add(fl);
            for (File f : apks) args.add(f.getAbsolutePath());
            return runProcessWithTimeout(buildAdbProcess(args.toArray(new String[0])), 600000);
        }
        if ("shizuku".equals(mode)) {
            String created = shizukuStream("pm install-create " + createFlags, null);
            String id = BackupScripts.parseSessionId(created);
            if (id == null) return created;
            boolean committed = false;
            try {
                int index = 0;
                for (File f : apks) {
                    String name = (index++) + "_" + f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                    String written = shizukuStream("pm install-write -S " + f.length() + " " + id + " " + name + " -", f);
                    if (!written.contains("Success")) return written;
                }
                String result = shizukuStream("pm install-commit " + id, null);
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try { shizukuStream("pm install-abandon " + id, null); } catch (Exception ignored) {}
                }
            }
        }
        if ("root".equals(mode)) {
            StringBuilder cmd = new StringBuilder(apks.size() == 1 ? "pm install" : "pm install-multiple");
            cmd.append(' ').append(createFlags.trim());
            for (File f : apks) {
                f.setReadable(true, false);
                cmd.append(' ').append(BackupScripts.quote(f.getAbsolutePath()));
            }
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd.toString());
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 600000);
        }
        return "Error: this authorizer needs ADB, Shizuku or Root";
    }

    /** Runs a short pm command through a specific backend (for post-install dexopt). */
    private String shellVia(String mode, String cmd) throws Exception {
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            boolean tcp = "adb_tcp".equals(mode);
            String target = tcp ? tcpTarget() : wirelessTarget();
            if (!isAdbTargetConnected(target)) performConnect(tcp ? adbTcpHost : adbWirelessHost, tcp ? adbTcpPort : adbWirelessPort, null);
            return runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), 600000);
        }
        if ("shizuku".equals(mode)) return shizukuStream(cmd, null);
        if ("root".equals(mode)) {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            return runProcessWithTimeout(pb, 600000);
        }
        return "Error: needs ADB, Shizuku or Root";
    }

    /** Post-install dexopt + auto-delete of the source, then reports the outcome to the WebView. */
    private void finishInstall(JSONObject res, JSONObject opts, String mode) {
        try {
            boolean success = res.optBoolean("ok", false);
            if (success && opts.optBoolean("dexopt", false) && !"none".equals(mode)) {
                String pkg = opts.optString("pkg");
                String cm = opts.optString("dexoptMode", "speed").replaceAll("[^a-z-]", "");
                if (!pkg.isEmpty() && !cm.isEmpty() && pkg.matches("[A-Za-z0-9._]+")) {
                    try {
                        String comp = "pm compile -m " + cm + (opts.optBoolean("dexForce", false) ? " -f " : " ") + pkg;
                        res.put("dexopt", shellVia(mode, comp).trim());
                    } catch (Exception e) {
                        try { res.put("dexopt", "Error: " + e.getMessage()); } catch (Exception ignored) {}
                    }
                }
            }
            if (success && opts.optBoolean("autoDelete", false)) {
                String ref = opts.optString("sourceRef");
                boolean deleted = false;
                try {
                    if (ref.startsWith("content://")) {
                        deleted = android.provider.DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(ref));
                    } else if (!ref.isEmpty()) {
                        deleted = new File(ref).delete();
                    }
                } catch (Exception ignored) {}
                try { res.put("sourceDeleted", deleted); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        clearInstallerWorkDir();
        notifyJs("window.onInstallResult && window.onInstallResult(" + JSONObject.quote(res.toString()) + ")");
    }

    /** Installs via the platform PackageInstaller with user confirmation - no ADB/Shizuku/Root. The
     *  privileged pm flags (grant-all, downgrade, all-users, bypass, update-ownership) are not available
     *  to an ordinary app here, so only install reason / package source are passed through. */
    private void installNoPrivilege(final List<File> apks, final JSONObject opts) {
        try {
            android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            try { if (opts.optInt("installReason", -1) >= 0) params.setInstallReason(opts.optInt("installReason")); } catch (Exception ignored) {}
            try { if (Build.VERSION.SDK_INT >= 33 && opts.optInt("packageSource", -1) >= 0) params.setPackageSource(opts.optInt("packageSource")); } catch (Exception ignored) {}
            final int sessionId = pi.createSession(params);
            android.content.pm.PackageInstaller.Session session = pi.openSession(sessionId);
            boolean staged = false;
            try {
                for (File f : apks) {
                    java.io.FileInputStream fin = new java.io.FileInputStream(f);
                    OutputStream sout = session.openWrite(f.getName().replaceAll("[^A-Za-z0-9._-]", "_"), 0, f.length());
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = fin.read(buf)) > 0) sout.write(buf, 0, r);
                        session.fsync(sout);
                    } finally { fin.close(); sout.close(); }
                }
                staged = true;
            } finally {
                if (!staged) { try { session.abandon(); } catch (Exception ignored) {} }
            }

            final String action = getPackageName() + ".INSTALL_RESULT." + (installReceiverSeq++);
            android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int status = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
                    if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { startActivity(confirm); } catch (Exception ignored) {}
                        }
                        return; // not terminal - wait for the user to confirm or cancel
                    }
                    try { c.unregisterReceiver(this); } catch (Exception ignored) {}
                    JSONObject r = new JSONObject();
                    try {
                        boolean success = status == android.content.pm.PackageInstaller.STATUS_SUCCESS;
                        String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                        r.put("ok", success);
                        r.put("method", "No privilege");
                        r.put("output", success ? "Success"
                                : ("Install " + (status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "failed")
                                   + (msg != null && !msg.isEmpty() ? ": " + msg : "")));
                    } catch (Exception ignored) {}
                    finishInstall(r, opts, "none");
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(rcv, filter);
            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
            android.app.PendingIntent sender = android.app.PendingIntent.getBroadcast(this, sessionId, new Intent(action).setPackage(getPackageName()), piFlags);
            session.commit(sender.getIntentSender());
            session.close();
        } catch (Exception e) {
            JSONObject r = new JSONObject();
            try {
                r.put("ok", false);
                r.put("method", "No privilege");
                r.put("output", "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            } catch (Exception ignored) {}
            finishInstall(r, opts, "none");
        }
    }

    /** The Installer tab's install action. Validates the selection, runs the signature gates, installs
     *  through the chosen authorizer, then dexopts/auto-deletes. Result: window.onInstallResult(json). */
    private void runInstallSelected(final String optsJson) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                try {
                    JSONObject opts = new JSONObject(optsJson);
                    JSONArray sel = opts.optJSONArray("splits");
                    if (sel == null || sel.length() == 0) throw new IllegalStateException("no APK selected to install");
                    // Only ever install files we extracted into our own installer cache - never an arbitrary
                    // path handed in from JS.
                    String workPath = installerWorkDir().getCanonicalPath();
                    List<File> files = new ArrayList<File>();
                    File base = null;
                    for (int i = 0; i < sel.length(); i++) {
                        JSONObject s = sel.optJSONObject(i);
                        if (s == null) continue;
                        File f = new File(s.optString("path"));
                        if (!f.getCanonicalPath().startsWith(workPath + File.separator) || !f.exists()) {
                            throw new IllegalStateException("install files are no longer available - pick the package again");
                        }
                        if (s.optBoolean("isBase")) base = f;
                        files.add(f);
                    }
                    if (files.isEmpty()) throw new IllegalStateException("no APK selected to install");
                    if (base != null) { files.remove(base); files.add(0, base); }

                    // Signature gates, read from the base APK, before anything is installed.
                    PackageManager pm = getPackageManager();
                    int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                    File gateApk = base != null ? base : files.get(0);
                    PackageInfo archive = pm.getPackageArchiveInfo(gateApk.getAbsolutePath(), sigFlags);
                    java.util.Set<String> archiveSigners = archive != null ? signerDigests(archive, false) : new HashSet<String>();
                    if (opts.optBoolean("blockUnknown", true) && archiveSigners.isEmpty()) {
                        throw new IllegalStateException("this package is unsigned or its signature can't be read (blocked by \"block unknown signature\")");
                    }
                    if (opts.optBoolean("blockMismatch", true) && archive != null && archive.packageName != null) {
                        try {
                            PackageInfo cur = pm.getPackageInfo(archive.packageName, sigFlags);
                            java.util.Set<String> curSigners = signerDigests(cur, true);
                            if (!curSigners.isEmpty() && !archiveSigners.isEmpty()) {
                                java.util.Set<String> common = new HashSet<String>(curSigners);
                                common.retainAll(archiveSigners);
                                if (common.isEmpty()) {
                                    throw new IllegalStateException(archive.packageName + " is already installed with a different signing key (blocked by \"block signature mismatch\")");
                                }
                            }
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }

                    String mode = resolveAuthorizerMode(opts.optString("authorizer", ""));
                    if ("none".equals(mode)) {
                        installNoPrivilege(files, opts); // result arrives via the PackageInstaller receiver
                        return;
                    }
                    String out = installApksOptions(files, opts.optString("createFlags", "-r"), mode);
                    res.put("ok", out != null && out.contains("Success"));
                    res.put("method", mode);
                    res.put("output", out != null ? out.trim() : "");
                    finishInstall(res, opts, mode);
                } catch (Exception e) {
                    try {
                        res.put("ok", false);
                        res.put("method", "none");
                        res.put("output", "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    } catch (Exception ignored) {}
                    notifyJs("window.onInstallResult && window.onInstallResult(" + JSONObject.quote(res.toString()) + ")");
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Backup and restore
    //
    // A backup is one .adbbackup file (a zip): backup.json, apk/<each APK>, and data.tar when the data
    // was included. App data is private to the app, so it needs Root; the APK, permissions and app ops
    // work in every privileged mode.
    // ---------------------------------------------------------------------------------------------

    private static final int REQ_PICK_BACKUP = 4202;
    private static final int REQ_PICK_INSTALL = 4203;
    // Distinguishes each no-privilege install's result broadcast so two in-flight installs can't cross wires.
    private int installReceiverSeq = 0;
    private final java.util.concurrent.atomic.AtomicBoolean backupBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    // A real app + its data is rarely anywhere near this; bounds how much a crafted or damaged backup can
    // make the restore step write before it gives up, instead of grinding on a zip bomb until storage fills.
    private static final long MAX_RESTORE_EXTRACT_BYTES = 8L * 1024 * 1024 * 1024;

    private void backupEvent(String op, String stage, int pct, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("op", op);
            o.put("stage", stage);
            o.put("pct", pct);
            o.put("msg", message);
            notifyJs("window.onBackupProgress && window.onBackupProgress(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }

    private void backupDone(JSONObject res) {
        notifyJs("window.onBackupDone && window.onBackupDone(" + JSONObject.quote(res.toString()) + ")");
    }

    /** Runs a script as root through su, whatever working mode is selected. */
    private String runRootScript(String script, int timeoutMs) throws Exception {
        File f = new File(getCacheDir(), "root_" + System.nanoTime() + ".sh");
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(script.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            f.setReadable(true, false);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "sh " + BackupScripts.quote(f.getAbsolutePath()));
            pb.redirectErrorStream(true);
            String out2 = runProcessWithTimeout(pb, timeoutMs);
            return out2 == null ? "" : out2;
        } finally {
            f.delete();
        }
    }

    private static String rootError(String out) {
        for (String line : out.split("\n")) {
            if (line.startsWith("ERROR")) return line.substring(line.indexOf(':') + 1).trim();
        }
        return out.trim().isEmpty() ? "Root access was denied or is not available" : out.trim();
    }

    private static String installHint(String out) {
        String o = out == null ? "" : out;
        if (o.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) return "A newer version is installed than the one in the backup. Uninstall the app first (keep its data if you want it restored), then restore.";
        if (o.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || o.contains("signatures do not match")) return "The installed app is signed with a different key than the backup. Uninstall it first, then restore.";
        if (o.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "Not enough storage to install the app.";
        if (o.contains("INSTALL_FAILED_MISSING_SPLIT")) return "A split APK is missing from the backup.";
        return o.trim().isEmpty() ? "install failed" : o.trim();
    }

    private InputStream openRef(String ref) throws Exception {
        if (ref != null && ref.startsWith("content://")) {
            InputStream in = getContentResolver().openInputStream(Uri.parse(ref));
            if (in == null) throw new java.io.FileNotFoundException("cannot open the backup file");
            return in;
        }
        return new java.io.FileInputStream(ref);
    }

    private boolean backupExists(String ref) {
        try {
            if (ref != null && ref.startsWith("content://")) {
                android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(Uri.parse(ref), "r");
                if (pfd == null) return false;
                pfd.close();
                return true;
            }
            return ref != null && new File(ref).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private JSONArray backupIndex() {
        try {
            return new JSONArray(prefs.getString("backups_index", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** backup.json must be the first entry, so this reads a few KB even from a multi-GB backup. */
    private JSONObject readBackupMeta(String ref) throws Exception {
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(openRef(ref)));
        try {
            // A picked backup file is untrusted, so scanning forward for backup.json by name cannot be
            // allowed: skipping a non-matching entry via another getNextEntry() call first fully inflates
            // it to find its end, with no byte cap - a tiny zip bomb placed before backup.json would be
            // decompressed in full before this method ever gets to check anything. Require it to be the
            // very first entry instead and refuse immediately otherwise.
            java.util.zip.ZipEntry e = zin.getNextEntry();
            if (e == null || !"backup.json".equals(e.getName())) {
                throw new IllegalStateException("not a valid backup file (backup.json must be the first entry)");
            }
            {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = zin.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > 1024 * 1024) throw new IllegalStateException("backup.json is too large");
                }
                JSONObject meta = new JSONObject(buf.toString("UTF-8"));
                if (!"adb-app-manager-backup".equals(meta.optString("format")) || meta.optInt("v") != 1) {
                    throw new IllegalStateException("this backup was made by a different version of the app");
                }
                BackupScripts.checkedPackage(meta.optString("pkg"));
                return meta;
            }
        } finally {
            zin.close();
        }
    }

    private static boolean isDangerousPermission(PackageManager pm, String name) {
        try {
            android.content.pm.PermissionInfo info = pm.getPermissionInfo(name, 0);
            return (info.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Exception e) {
            return false;
        }
    }

    private void runBackup(final String pkg, final boolean includeData) {
        if (!backupBusy.compareAndSet(false, true)) {
            JSONObject busy = new JSONObject();
            try { busy.put("op", "backup"); busy.put("ok", false); busy.put("error", "Another backup or restore is still running"); } catch (Exception ignored) {}
            backupDone(busy);
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                File tmpTar = null;
                Object[] target = null;
                try {
                    res.put("op", "backup");
                    res.put("pkg", pkg);
                    BackupScripts.checkedPackage(pkg);
                    if ("standard".equals(resolveExecMode())) {
                        throw new IllegalStateException("backing up needs ADB, Shizuku or Root (app ops can't be read otherwise). Set up a working mode first.");
                    }
                    PackageManager pm = getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                    PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.GET_PERMISSIONS | sigFlags);
                    File[] files = apkFiles(ai);
                    if (files.length == 0 || !files[0].canRead()) throw new IllegalStateException("the APK is not readable");
                    String label = pm.getApplicationLabel(ai).toString();
                    String version = pi.versionName != null ? pi.versionName : String.valueOf(versionCodeOf(pi));
                    backupEvent("backup", "info", 5, "Reading " + label + "...");

                    JSONObject meta = new JSONObject();
                    meta.put("format", "adb-app-manager-backup");
                    meta.put("v", 1);
                    meta.put("pkg", pkg);
                    meta.put("label", label);
                    meta.put("versionName", version);
                    meta.put("versionCode", versionCodeOf(pi));
                    meta.put("createdAt", System.currentTimeMillis());
                    meta.put("device", Build.MODEL);
                    meta.put("sdk", Build.VERSION.SDK_INT);
                    meta.put("installer", installerOf(pkg));
                    meta.put("suspended", (ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
                    JSONArray signers = new JSONArray();
                    for (String d : signerDigests(pi, false)) signers.put(d);
                    meta.put("signers", signers);
                    JSONArray perms = new JSONArray();
                    if (pi.requestedPermissions != null && pi.requestedPermissionsFlags != null) {
                        for (int i = 0; i < pi.requestedPermissions.length; i++) {
                            String name = pi.requestedPermissions[i];
                            if ((pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                                    && BackupScripts.isPermission(name) && isDangerousPermission(pm, name)) perms.put(name);
                        }
                    }
                    meta.put("permissions", perms);
                    JSONArray warnings = new JSONArray();
                    JSONObject ops = new JSONObject();
                    try {
                        String opsRaw = new AndroidBridge().executeShell("cmd appops get " + pkg);
                        for (Map.Entry<String, String> e : BackupScripts.changedAppOps(opsRaw).entrySet()) {
                            ops.put(e.getKey(), e.getValue());
                        }
                        // An empty result is the common, legitimate case (no overrides) - but if the backend
                        // reported a failure instead of actual appops output, that looks identical unless
                        // checked for, and the backup would otherwise silently claim a complete settings
                        // snapshot it doesn't have.
                        String lowered = opsRaw == null ? "" : opsRaw.toLowerCase();
                        if (ops.length() == 0 && (opsRaw == null || lowered.contains("error") || lowered.contains("exception") || lowered.contains("denied") || lowered.contains("unknown command"))) {
                            warnings.put("Could not read this app's app ops" + (opsRaw == null || opsRaw.trim().isEmpty() ? "" : " (" + opsRaw.trim() + ")") + "; any app op overrides will be missing from this backup");
                        }
                    } catch (Exception e) {
                        warnings.put("Could not read this app's app ops (" + e.getMessage() + "); any app op overrides will be missing from this backup");
                    }
                    meta.put("appops", ops);
                    boolean withData = false;
                    if (includeData) {
                        backupEvent("backup", "data", 20, "Backing up data (Root)...");
                        tmpTar = new File(getCacheDir(), "backup_data_" + System.nanoTime() + ".tar");
                        String out = runRootScript(BackupScripts.dataBackup("/data", pkg, tmpTar.getAbsolutePath(), android.os.Process.myUid()), 900000);
                        if (!out.contains("OK")) throw new IllegalStateException("data: " + rootError(out));
                        if (out.contains("WARN")) warnings.put("Some data changed while it was being read; the backup may be incomplete");
                        withData = true;
                    }
                    meta.put("hasData", withData);
                    meta.put("dataBytes", withData ? tmpTar.length() : 0);

                    backupEvent("backup", "write", 55, "Saving the backup...");
                    // Millisecond precision: on Android 8-9 (API 26-28, see openDownloadOutput) this name is
                    // opened directly with FileOutputStream, so two backups of the same app/version within
                    // the same minute would otherwise overwrite each other while both index records still
                    // point at one file - and a failed second write could delete an earlier, valid backup.
                    String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(new java.util.Date());
                    String fileName = (label.isEmpty() ? pkg : label) + "_" + version + "_" + stamp + ".adbbackup";
                    target = openDownloadOutput(fileName, "application/octet-stream", "Backups");
                    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream((OutputStream) target[0]));
                    long total = 0;
                    try {
                        zip.setLevel(1); // APKs are already compressed; keep it fast
                        zip.putNextEntry(new java.util.zip.ZipEntry("backup.json"));
                        zip.write(meta.toString(2).getBytes("UTF-8"));
                        zip.closeEntry();
                        for (int i = 0; i < files.length; i++) {
                            // apkFiles() always returns the base APK (ai.sourceDir) first; name it "base.apk"
                            // regardless of its real on-disk name, since restore looks for that name and a
                            // system app's source file is rarely actually called that (e.g. .../Settings.apk)
                            String entryName = i == 0 ? "base.apk" : files[i].getName();
                            zip.putNextEntry(new java.util.zip.ZipEntry("apk/" + entryName));
                            total += copyFile(files[i], zip);
                            zip.closeEntry();
                        }
                        if (withData) {
                            zip.putNextEntry(new java.util.zip.ZipEntry("data.tar"));
                            total += copyFile(tmpTar, zip);
                            zip.closeEntry();
                        }
                    } finally {
                        zip.close();
                    }

                    JSONObject rec = new JSONObject();
                    rec.put("ref", target[2]);
                    rec.put("path", target[1]);
                    rec.put("name", fileName);
                    rec.put("pkg", pkg);
                    rec.put("label", label);
                    rec.put("versionName", version);
                    rec.put("createdAt", meta.getLong("createdAt"));
                    rec.put("bytes", total);
                    rec.put("hasData", withData);
                    JSONArray index = backupIndex();
                    JSONArray next = new JSONArray();
                    next.put(rec);
                    for (int i = 0; i < index.length() && next.length() < 200; i++) next.put(index.get(i));
                    prefs.edit().putString("backups_index", next.toString()).apply();

                    res.put("ok", true);
                    res.put("label", label);
                    res.put("path", target[1]);
                    res.put("ref", target[2]);
                    res.put("bytes", total);
                    res.put("hasData", withData);
                    res.put("warnings", warnings);
                } catch (Exception e) {
                    // A target already created (a MediaStore row on Android 10+, indexed the moment it's
                    // inserted, before any bytes are written) must not survive a failure partway through
                    // writing it - otherwise a failed backup still shows up in Downloads looking like a
                    // complete, restorable .adbbackup file.
                    deleteDownloadTarget(target);
                    try {
                        res.put("ok", false);
                        res.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    } catch (Exception ignored) {}
                } finally {
                    if (tmpTar != null) tmpTar.delete();
                    backupBusy.set(false);
                }
                backupDone(res);
            }
        });
    }

    private void runRestore(final String ref, final boolean restoreData) {
        if (!backupBusy.compareAndSet(false, true)) {
            JSONObject busy = new JSONObject();
            try { busy.put("op", "restore"); busy.put("ok", false); busy.put("error", "Another backup or restore is still running"); } catch (Exception ignored) {}
            backupDone(busy);
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                File work = new File(getCacheDir(), "restore_" + System.nanoTime());
                try {
                    res.put("op", "restore");
                    JSONObject meta = readBackupMeta(ref);
                    // backup.json can come from "Choose backup file..." - an arbitrary file the user picked -
                    // so nothing in it is trusted yet. It only supplies a label for the UI until the APK inside
                    // proves what it actually is, below.
                    String claimedPkg = meta.optString("pkg", "");
                    res.put("pkg", claimedPkg);
                    res.put("label", meta.optString("label", claimedPkg));
                    if ("standard".equals(resolveExecMode())) {
                        throw new IllegalStateException("restoring needs ADB, Shizuku or Root. Set up a working mode first.");
                    }
                    boolean wantData = restoreData && meta.optBoolean("hasData");
                    if (restoreData && !meta.optBoolean("hasData")) {
                        res.put("data", "this backup has no data");
                    }

                    backupEvent("restore", "read", 10, "Reading the backup...");
                    File apkDir = new File(work, "apk");
                    apkDir.mkdirs();
                    File dataTar = wantData ? new File(work, "data.tar") : null;
                    long extracted = 0;
                    java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(openRef(ref)));
                    try {
                        java.util.zip.ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            String n = e.getName();
                            // runBackup always writes data.tar last. Skipping an unwanted entry by calling
                            // getNextEntry() again would still make ZipInputStream fully inflate it first just to
                            // find the end - for a multi-gigabyte data.tar that defeats the byte cap below before
                            // it ever runs. Since nothing of ours follows data.tar, stop scanning instead.
                            if ("data.tar".equals(n) && dataTar == null) break;
                            File dest = null;
                            if (n.matches("apk/[A-Za-z0-9_.+-]+\\.apk")) dest = new File(apkDir, n.substring(4));
                            else if ("data.tar".equals(n)) dest = dataTar;
                            // Anything else in the file is ignored, but still has to be read through the same
                            // counted loop below: a bare "continue" here would make the next getNextEntry()
                            // call silently inflate the whole entry first to find its end, bypassing the byte
                            // cap entirely for a huge entry under a name this code doesn't recognize.
                            OutputStream out = dest != null ? new FileOutputStream(dest) : new OutputStream() {
                                public void write(int b) {}
                                public void write(byte[] b, int off, int len) {}
                            };
                            try {
                                byte[] buf = new byte[65536];
                                int r;
                                while ((r = zin.read(buf)) > 0) {
                                    extracted += r;
                                    // A real app plus its data is rarely anywhere near this; past it, this is either
                                    // a damaged file or one crafted to fill the phone's storage, so stop reading it.
                                    if (extracted > MAX_RESTORE_EXTRACT_BYTES) {
                                        throw new IllegalStateException("this backup is far larger than any real app (stopped past "
                                                + (MAX_RESTORE_EXTRACT_BYTES / (1024 * 1024)) + " MB) - it looks damaged or unsafe to extract");
                                    }
                                    out.write(buf, 0, r);
                                }
                            } finally {
                                out.close();
                            }
                        }
                    } finally {
                        zin.close();
                    }
                    File[] apks = apkDir.listFiles();
                    if (apks == null || apks.length == 0) throw new IllegalStateException("the backup has no APK");
                    java.util.Arrays.sort(apks);
                    // The base APK must come first
                    List<File> ordered = new ArrayList<File>();
                    File baseApk = null;
                    for (File f : apks) if (f.getName().equals("base.apk")) { ordered.add(f); baseApk = f; }
                    for (File f : apks) if (!f.getName().equals("base.apk")) ordered.add(f);
                    if (baseApk == null) throw new IllegalStateException("the backup has no base.apk");

                    // The truth from here on is the APK itself, never backup.json: a picked file can claim to be
                    // anything, but its APK cannot lie about the package name it installs as or who signed it.
                    PackageManager pm = getPackageManager();
                    int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                    PackageInfo archiveInfo = pm.getPackageArchiveInfo(baseApk.getAbsolutePath(), sigFlags);
                    if (archiveInfo == null || archiveInfo.packageName == null) {
                        throw new IllegalStateException("base.apk in this backup could not be read as an APK");
                    }
                    final String pkg = archiveInfo.packageName;
                    res.put("pkg", pkg);
                    if (!claimedPkg.isEmpty() && !claimedPkg.equals(pkg)) {
                        throw new IllegalStateException("backup.json says " + claimedPkg + " but base.apk installs as " + pkg + " - this backup looks damaged or tampered with");
                    }
                    long archiveVersionCode = versionCodeOf(archiveInfo);
                    java.util.Set<String> archiveSigners = signerDigests(archiveInfo, false);

                    // Same package already installed? Compare keys and versions - both read from the APK above,
                    // not from backup.json - before deciding whether install can be skipped.
                    boolean installed = false;
                    boolean sameVersion = false;
                    try {
                        PackageInfo cur = pm.getPackageInfo(pkg, sigFlags);
                        installed = (cur.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
                        if (installed) {
                            requireCompatibleSigner(signerDigests(cur, true), archiveSigners,
                                    "the installed app is signed with a different key than this backup's APK. Uninstall it first, then restore.");
                        }
                        sameVersion = installed && versionCodeOf(cur) == archiveVersionCode;
                    } catch (PackageManager.NameNotFoundException ignored) {}

                    // A system app removed for this user is still on the system image: bring it back instead of
                    // installing. getPackageInfo(pkg, sigFlags) above throws for an app uninstalled-for-user, so
                    // this is the only identity check this path gets - it must verify the signer itself, the
                    // same as the installed-app branch above, rather than trusting the archive on its say-so.
                    String installNote = null;
                    if (!installed) {
                        PackageInfo sysInfo = null;
                        try {
                            sysInfo = pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES | sigFlags);
                        } catch (PackageManager.NameNotFoundException ignored) {}
                        if (sysInfo != null) {
                            requireCompatibleSigner(signerDigests(sysInfo, true), archiveSigners,
                                    "the system app on this phone is signed with a different key than this backup's APK - it does not match this device");
                            String o = new AndroidBridge().executeShell("pm install-existing " + pkg);
                            if (o.contains("installed for user")) {
                                installed = true;
                                sameVersion = true;
                                installNote = "restored for your user (it is part of the system)";
                            }
                        }
                    }

                    if (installed && sameVersion) {
                        res.put("install", installNote != null ? installNote : "already installed (same version)");
                    } else {
                        backupEvent("restore", "install", 40, "Installing " + meta.optString("label", pkg) + "...");
                        String out = installApks(ordered);
                        if (!out.contains("Success")) throw new IllegalStateException(installHint(out));
                        res.put("install", "installed " + meta.optString("versionName"));
                    }

                    // Permissions and app ops
                    backupEvent("restore", "settings", 65, "Restoring permissions...");
                    AndroidBridge bridge = new AndroidBridge();
                    JSONArray perms = meta.optJSONArray("permissions");
                    int granted = 0, total = perms == null ? 0 : perms.length();
                    for (int i = 0; i < total; i++) {
                        String perm = perms.optString(i);
                        // backup.json is untrusted, so restore must accept nothing wider than what backup
                        // creation itself ever writes: a syntactically valid, dangerous runtime permission.
                        // Without the same isDangerousPermission check used there, a crafted backup wrapped
                        // around an otherwise-legitimate APK could list any real, syntactically valid
                        // permission - including signature/development-level ones this format never
                        // produces - and have the privileged backend asked to grant it.
                        if (!BackupScripts.isPermission(perm) || !isDangerousPermission(pm, perm)) continue;
                        String o = bridge.setPermission(pkg, perm, true).toLowerCase();
                        if (!o.contains("exception") && !o.contains("error") && !o.contains("not a changeable")) granted++;
                    }
                    res.put("permissionsGranted", granted);
                    res.put("permissionsTotal", total);
                    JSONObject ops = meta.optJSONObject("appops");
                    int opsSet = 0;
                    java.util.Iterator<String> it = ops == null ? null : ops.keys();
                    while (it != null && it.hasNext()) {
                        String op = it.next();
                        String mode = ops.optString(op);
                        if (!op.matches("[A-Z][A-Z0-9_]+") || !mode.matches("allow|ignore|deny|foreground")) continue;
                        String o = bridge.setAppOp(pkg, op, mode).toLowerCase();
                        if (!o.contains("exception") && !o.contains("error")) opsSet++;
                    }
                    res.put("appopsSet", opsSet);

                    // Data (Root only)
                    JSONArray warnings = new JSONArray();
                    if (wantData) {
                        backupEvent("restore", "data", 80, "Restoring data (Root)...");
                        String out = runRootScript(BackupScripts.dataRestore("/data", pkg, dataTar.getAbsolutePath()), 900000);
                        if (out.contains("OK")) {
                            res.put("data", "restored");
                        } else {
                            res.put("data", "not restored");
                            warnings.put("Data was not restored: " + rootError(out));
                        }
                    }
                    res.put("warnings", warnings);
                    res.put("ok", true);
                } catch (Exception e) {
                    try {
                        res.put("ok", false);
                        res.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    } catch (Exception ignored) {}
                } finally {
                    deleteRecursively(work);
                    backupBusy.set(false);
                }
                backupDone(res);
            }
        });
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
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

    /**
     * Throws when two non-empty signer sets share no certificate. Used by both restore paths (an app
     * currently installed for the user, and a system app only present on the system image) so a future
     * change to this check can't land in one copy and leave the other unprotected.
     */
    private static void requireCompatibleSigner(java.util.Set<String> installedSigners, java.util.Set<String> archiveSigners, String message) {
        // Fail closed: every real, installable APK has at least one signer, so an empty set here means the
        // archive is unsigned/unreadable, not "nothing to compare". Treating that as a pass would let a
        // backup whose signature can't be read skip verification entirely.
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) throw new IllegalStateException(message);
        java.util.Set<String> common = new HashSet<String>(installedSigners);
        common.retainAll(archiveSigners);
        if (common.isEmpty()) throw new IllegalStateException(message);
    }

    private static final int REQ_IMPORT_OBTAINIUM = 4201;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BACKUP) {
            final Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (picked == null) return;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        JSONObject meta = readBackupMeta(picked.toString());
                        res.put("ref", picked.toString());
                        res.put("pkg", meta.optString("pkg"));
                        res.put("label", meta.optString("label"));
                        res.put("versionName", meta.optString("versionName"));
                        res.put("createdAt", meta.optLong("createdAt"));
                        res.put("hasData", meta.optBoolean("hasData"));
                        res.put("bytes", meta.optLong("dataBytes"));
                    } catch (Exception e) {
                        try { res.put("error", e.getMessage() != null ? e.getMessage() : "not a backup file"); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onBackupPicked && window.onBackupPicked(" + JSONObject.quote(res.toString()) + ")");
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_INSTALL) {
            final Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (picked == null) return;
            notifyJs("window.onInstallFilePicked && window.onInstallFilePicked(" + JSONObject.quote(picked.toString()) + ")");
            return;
        }
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

    // ---------------------------------------------------------------------------------------------
    // App sizes, storage stats and APK extraction
    // ---------------------------------------------------------------------------------------------

    private static File[] apkFiles(ApplicationInfo ai) {
        List<File> files = new ArrayList<File>();
        if (ai.sourceDir != null) files.add(new File(ai.sourceDir));
        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) files.add(new File(split));
        }
        return files.toArray(new File[0]);
    }

    private static long apkBytes(ApplicationInfo ai) {
        long total = 0;
        for (File f : apkFiles(ai)) total += f.length();
        return total;
    }

    private boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** {"app":..,"data":..,"cache":..} bytes from StorageStatsManager (needs usage access), or null */
    private JSONObject storageStats(String pkg) {
        if (!hasUsageAccess()) return null;
        try {
            android.app.usage.StorageStatsManager ssm = (android.app.usage.StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
            android.app.usage.StorageStats st = ssm.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle());
            JSONObject o = new JSONObject();
            o.put("app", st.getAppBytes());
            o.put("data", Math.max(0, st.getDataBytes() - st.getCacheBytes()));
            o.put("cache", st.getCacheBytes());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens a file in Download/ADB App Manager/<sub> via MediaStore (Android 10+). Before MediaStore.Downloads
     *  existed (Android 8.0-9, API 26-28) this instead uses this app's own external-files storage, which is
     *  removed if the app is uninstalled - there is no public-Downloads write path on those versions without
     *  a runtime WRITE_EXTERNAL_STORAGE grant this app doesn't request. target[1], returned to the caller,
     *  always reflects the real path used, so the UI and the backup index never claim the wrong one. */
    private Object[] openDownloadOutput(String fileName, String mime, String sub) throws Exception {
        String safeName = fileName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        String folder = "ADB App Manager" + (sub == null || sub.isEmpty() ? "" : "/" + sub);
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + folder);
            Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("could not create " + safeName);
            return new Object[]{getContentResolver().openOutputStream(uri), "Download/" + folder + "/" + safeName, uri.toString()};
        }
        File dir = new File(getExternalFilesDir(null), folder);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, safeName);
        return new Object[]{new FileOutputStream(file), file.getAbsolutePath(), file.getAbsolutePath()};
    }

    /** Deletes a target from openDownloadOutput after a failure, so a file that never finished writing
     *  doesn't stay indexed (or on disk) looking like a complete, usable one. */
    private void deleteDownloadTarget(Object[] target) {
        if (target == null) return;
        try {
            String ref = (String) target[2];
            if (ref.startsWith("content://")) {
                getContentResolver().delete(Uri.parse(ref), null, null);
            } else {
                new File(ref).delete();
            }
        } catch (Exception ignored) {}
    }

    /** Copies an app's APK (or base + splits as a .apks bundle) to Downloads. Result via window.onApkExtracted(json). */
    private void runExtractApk(final String pkg) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                Object[] target = null;
                try {
                    res.put("pkg", pkg);
                    PackageManager pm = getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    File[] files = apkFiles(ai);
                    if (files.length == 0 || !files[0].canRead()) throw new IllegalStateException("APK not readable");
                    String label = pm.getApplicationLabel(ai).toString();
                    String base = (label.isEmpty() ? pkg : label) + "_" + (pi.versionName != null ? pi.versionName : String.valueOf(versionCodeOf(pi)));
                    long total = 0;
                    if (files.length == 1) {
                        target = openDownloadOutput(base + ".apk", "application/vnd.android.package-archive", "APKs");
                        OutputStream out = (OutputStream) target[0];
                        try {
                            total = copyFile(files[0], out);
                        } finally {
                            out.close();
                        }
                    } else {
                        // Split app: one .apks bundle (a zip of base.apk + splits) that split-APK installers accept
                        // octet-stream so MediaStore keeps the .apks name instead of appending .zip
                        target = openDownloadOutput(base + ".apks", "application/octet-stream", "APKs");
                        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream((OutputStream) target[0]);
                        try {
                            for (File f : files) {
                                zip.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                                total += copyFile(f, zip);
                                zip.closeEntry();
                            }
                        } finally {
                            zip.close();
                        }
                    }
                    res.put("ok", true);
                    res.put("path", target[1]);
                    res.put("ref", target[2]);
                    res.put("mime", files.length == 1 ? "application/vnd.android.package-archive" : "application/octet-stream");
                    res.put("bytes", total);
                    res.put("splits", files.length);
                } catch (Exception e) {
                    deleteDownloadTarget(target);
                    try {
                        res.put("ok", false);
                        res.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    } catch (Exception ignored) {}
                }
                notifyJs("window.onApkExtracted && window.onApkExtracted(" + JSONObject.quote(res.toString()) + ")");
            }
        });
    }

    private static long copyFile(File f, OutputStream out) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        long total = 0;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            in.close();
        }
        return total;
    }

    // Small JSON documents the UI keeps between launches (remembered filters, debloat history)
    private static final java.util.Set<String> UI_STORE_KEYS = new HashSet<String>(java.util.Arrays.asList("ui_state", "debloat_history", "profiles", "seen_version"));

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

        /** The profile whose apps the user wants to be told about when they come back ("" = none). */
        @JavascriptInterface
        public void setWatchedProfile(final String name) {
            // No separate baseline write needed here: this bridge method only exists once the WebView is up,
            // which means onCreate's non-headless branch has already run this launch and recorded "app_fp" -
            // the exact value BootReceiver falls back to when it has no baseline of its own yet.
            prefs.edit().putString("watched_profile", name == null ? "" : name).apply();
            if (name != null && !name.isEmpty() && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5043);
                    }
                });
            }
        }

        /** Saved list used by the Quick Settings tile and the widget ("" = none). */
        @JavascriptInterface
        public void setQuickList(String id) {
            prefs.edit().putString("quick_list_id", id == null ? "" : id).apply();
            QuickWidgetProvider.refreshAll(MainActivity.this);
        }

        @JavascriptInterface
        public String getQuickList() {
            return prefs.getString("quick_list_id", "");
        }

        /** The changelog bundled into the APK by build.sh (empty if the build did not include it). */
        @JavascriptInterface
        public String getChangelog() {
            try {
                InputStream in = getAssets().open("changelog.md");
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                in.close();
                return buf.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String getAppVersion() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                JSONObject o = new JSONObject();
                o.put("versionName", pi.versionName);
                o.put("versionCode", versionCodeOf(pi));
                o.put("firstInstallTime", pi.firstInstallTime);
                o.put("lastUpdateTime", pi.lastUpdateTime);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
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
                obj.put("buildChanged", buildChangedThisLaunch);
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
                // Version names for the optional label in the app list (one call for all packages)
                java.util.Map<String, String> versions = new java.util.HashMap<String, String>();
                java.util.Map<String, PackageInfo> infos = new java.util.HashMap<String, PackageInfo>();
                try {
                    for (PackageInfo pi : pm.getInstalledPackages(0)) {
                        versions.put(pi.packageName, pi.versionName != null ? pi.versionName : String.valueOf(versionCodeOf(pi)));
                        infos.put(pi.packageName, pi);
                    }
                } catch (Exception ignored) {}

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
                    o.put("version", versions.containsKey(info.packageName) ? versions.get(info.packageName) : "");
                    PackageInfo pi = infos.get(info.packageName);
                    if (pi != null) {
                        o.put("installedAt", pi.firstInstallTime);
                        o.put("updatedAt", pi.lastUpdateTime);
                    }
                    o.put("apkSize", apkBytes(info));
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
                // Every legitimate caller gets pkg from PackageManager, but a saved/quick list is free-form
                // text the user typed; everything below concatenates pkg into a shell command (or a monkey
                // fallback), so refuse anything that isn't a well-formed package name before it gets there.
                if (!BackupScripts.isPackageName(pkg)) return "Error: \"" + pkg + "\" is not a valid package name";
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
                            | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES;
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

                    // Four component kinds, each with per-component exported/enabled/permission so the UI
                    // can show and toggle them. The plain name arrays stay for older callers.
                    JSONArray activities = new JSONArray();
                    JSONArray activityInfo = new JSONArray();
                    if (info.activities != null) {
                        for (ActivityInfo a : info.activities) {
                            if (a.name == null) continue;
                            activities.put(a.name);
                            activityInfo.put(componentEntry(pkg, a.name, a.exported, a.enabled, a.permission));
                        }
                    }
                    obj.put("activities", activities);
                    obj.put("activityInfo", activityInfo);

                    JSONArray services = new JSONArray();
                    JSONArray serviceInfo = new JSONArray();
                    if (info.services != null) {
                        for (ServiceInfo s : info.services) {
                            if (s.name == null) continue;
                            services.put(s.name);
                            serviceInfo.put(componentEntry(pkg, s.name, s.exported, s.enabled, s.permission));
                        }
                    }
                    obj.put("services", services);
                    obj.put("serviceInfo", serviceInfo);

                    JSONArray receivers = new JSONArray();
                    JSONArray receiverInfo = new JSONArray();
                    if (info.receivers != null) {
                        for (ActivityInfo r : info.receivers) {
                            if (r.name == null) continue;
                            receivers.put(r.name);
                            receiverInfo.put(componentEntry(pkg, r.name, r.exported, r.enabled, r.permission));
                        }
                    }
                    obj.put("receivers", receivers);
                    obj.put("receiverInfo", receiverInfo);

                    JSONArray providers = new JSONArray();
                    JSONArray providerInfo = new JSONArray();
                    if (info.providers != null) {
                        for (android.content.pm.ProviderInfo p : info.providers) {
                            if (p.name == null) continue;
                            providers.put(p.name);
                            String perm = p.readPermission != null ? p.readPermission : p.writePermission;
                            JSONObject po = componentEntry(pkg, p.name, p.exported, p.enabled, perm);
                            if (p.authority != null) po.put("authority", p.authority);
                            providerInfo.put(po);
                        }
                    }
                    obj.put("providers", providers);
                    obj.put("providerInfo", providerInfo);
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
         * Launches an activity. Whenever a privileged mode (ADB / Shizuku / Root) is active, this always
         * goes through `am start -W` via shell - regardless of the exported flag - because that's the only
         * path whose result can actually be trusted: Android's exported=false denial frequently does not
         * throw an exception back to a plain startActivity() caller, it just silently does nothing, so a
         * caller that only tries the Intent path for anything it *believes* is exported can end up reporting
         * a launch as successful when nothing actually opened (including whenever that belief is wrong, e.g.
         * a caller that doesn't have per-activity detail and defaults everything to exported). `am start -W`
         * always prints a real "Status: ok" or a specific denial, so its result is trustworthy either way.
         * The plain Intent is only a fallback for when no privileged mode is set up at all, and only for an
         * activity actually believed exported; even then its "ok" can't be verified the same way.
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

                if (!"standard".equals(resolveExecMode())) {
                    String output = executeShell("am start -W -n '" + pkg + "/" + fullCls + "'");
                    String lower = output.toLowerCase();
                    boolean ok = lower.contains("status: ok") || (lower.contains("starting: intent")
                            && !lower.contains("error") && !lower.contains("exception") && !lower.contains("permission denial"));
                    res.put("ok", ok);
                    res.put("method", "shell");
                    res.put("output", output);
                    return res.toString();
                }

                if (exported) {
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(pkg, fullCls);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        res.put("ok", true);
                        res.put("method", "intent");
                        res.put("output", "Requested " + pkg + "/" + fullCls + " (no working mode set up, so this can't be verified)");
                        return res.toString();
                    } catch (Exception e) {
                        res.put("ok", false);
                        res.put("method", "intent");
                        res.put("output", "Error: " + e.getMessage());
                        return res.toString();
                    }
                }

                res.put("ok", false);
                res.put("method", "none");
                res.put("output", "Error: launching this activity needs ADB, Shizuku or Root. Set up a working mode first.");
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
        public boolean saveStore(String key, String json) {
            if (UI_STORE_KEYS.contains(key) && json != null && json.length() < 512 * 1024) {
                prefs.edit().putString("store_" + key, json).apply();
                return true;
            }
            return false; // too large or not an allowed key: the page shows an error instead of losing the data silently
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

        /** Sizes for the app menu: APK files always, app/data/cache when usage access is granted. */
        @JavascriptInterface
        public String getAppSizes(String pkg) {
            JSONObject o = new JSONObject();
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                o.put("apk", apkBytes(ai));
                o.put("splits", apkFiles(ai).length);
                o.put("usageAccess", hasUsageAccess());
                JSONObject st = storageStats(pkg);
                if (st != null) o.put("stats", st);
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        /** Total storage per package ({"pkg": bytes}) for sorting by size; APK size when usage access is off. */
        @JavascriptInterface
        public String getAllAppSizes() {
            JSONObject o = new JSONObject();
            boolean access = hasUsageAccess();
            try {
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    long total = apkBytes(ai);
                    if (access) {
                        JSONObject st = storageStats(ai.packageName);
                        if (st != null) total = st.optLong("app") + st.optLong("data") + st.optLong("cache");
                    }
                    o.put(ai.packageName, total);
                }
                o.put("__usageAccess", access);
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** Grants this app usage access via the privileged shell, or opens the settings page. */
        @JavascriptInterface
        public String requestUsageAccess() {
            if (hasUsageAccess()) return "granted";
            if (!"standard".equals(resolveExecMode())) {
                executeShell("appops set " + getPackageName() + " GET_USAGE_STATS allow");
                if (hasUsageAccess()) return "granted";
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
            return "settings";
        }

        @JavascriptInterface
        public boolean hasRoot() {
            return isRootAvailable();
        }

        /** Backs up an app to Download/ADB App Manager/Backups. Progress and result arrive as JS callbacks. */
        @JavascriptInterface
        public void backupApp(String pkg, boolean includeData) {
            runBackup(pkg, includeData);
        }

        @JavascriptInterface
        public void restoreBackup(String ref, boolean restoreData) {
            runRestore(ref, restoreData);
        }

        /** Backups made by this app that still exist, newest first. */
        @JavascriptInterface
        public String getBackups() {
            JSONArray index = backupIndex();
            JSONArray keep = new JSONArray();
            try {
                for (int i = 0; i < index.length(); i++) {
                    JSONObject rec = index.getJSONObject(i);
                    if (backupExists(rec.optString("ref"))) keep.put(rec);
                }
                if (keep.length() != index.length()) prefs.edit().putString("backups_index", keep.toString()).apply();
            } catch (Exception e) {
                return index.toString();
            }
            return keep.toString();
        }

        @JavascriptInterface
        public String deleteBackup(String ref) {
            try {
                boolean ok;
                if (ref != null && ref.startsWith("content://")) ok = getContentResolver().delete(Uri.parse(ref), null, null) > 0;
                else ok = ref != null && new File(ref).delete();
                JSONArray index = backupIndex();
                JSONArray keep = new JSONArray();
                for (int i = 0; i < index.length(); i++) {
                    if (!index.getJSONObject(i).optString("ref").equals(ref)) keep.put(index.get(i));
                }
                prefs.edit().putString("backups_index", keep.toString()).apply();
                return ok ? "" : "Error: could not delete the file (it may already be gone)";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        /** Lets the user choose a .adbbackup file (for example from another phone). Answer: window.onBackupPicked(json). */
        @JavascriptInterface
        public void pickBackupFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_PICK_BACKUP);
                }
            });
        }

        /** Lets the user choose an .apk/.apks/.apkm to install. Answer: window.onInstallFilePicked(ref). */
        @JavascriptInterface
        public void pickInstallerFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_PICK_INSTALL);
                }
            });
        }

        private boolean isInstalled(String pkg) {
            try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
        }

        /** Whether Aurora Store / Play Store are installed, for routing Play-sourced updates. */
        @JavascriptInterface
        public String storeStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("aurora", isInstalled("com.aurora.store"));
                o.put("play", isInstalled("com.android.vending"));
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** Opens a package's store page (market:// resolves to Aurora Store or Play, whichever handles it). */
        @JavascriptInterface
        public void openInStore(final String pkg) {
            if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }

        /** Opens Aurora Store (its Updates screen lives in-app), falling back to its store listing. */
        @JavascriptInterface
        public void openAuroraStore() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage("com.aurora.store");
                        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.aurora.store"));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }

        /** Opens the system screen where the user can make this app the default for opening APK files. */
        @JavascriptInterface
        public void openDefaultAppsSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = Build.VERSION.SDK_INT >= 24
                                ? new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }

        /** Reads a picked package and returns its info + splits. Answer: window.onInstallInspected(json). */
        @JavascriptInterface
        public void inspectInstallSource(final String ref) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res;
                    try {
                        res = inspectInstallSourceImpl(ref);
                    } catch (Exception e) {
                        res = new JSONObject();
                        try { res.put("error", e.getMessage() != null ? e.getMessage() : "could not read this package"); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onInstallInspected && window.onInstallInspected(" + JSONObject.quote(res.toString()) + ")");
                }
            });
        }

        /** Installs the selected splits with the chosen options. Answer: window.onInstallResult(json). */
        @JavascriptInterface
        public void installSelected(String optsJson) {
            runInstallSelected(optsJson);
        }

        /** Enables or disables one component (pm enable/disable pkg/component) via the active backend. */
        @JavascriptInterface
        public String setComponentEnabled(String pkg, String component, boolean enable) {
            JSONObject r = new JSONObject();
            try {
                if (pkg == null || component == null || !pkg.matches("[A-Za-z0-9._]+") || !component.matches("[A-Za-z0-9._$]+")) {
                    r.put("ok", false); r.put("output", "Error: invalid component name"); return r.toString();
                }
                if ("standard".equals(resolveExecMode())) {
                    r.put("ok", false); r.put("output", "Error: enabling or disabling a component needs ADB, Shizuku or Root."); return r.toString();
                }
                String full = component.startsWith(".") ? pkg + component : component;
                String out = executeShell("pm " + (enable ? "enable" : "disable") + " " + pkg + "/" + full);
                String low = out == null ? "" : out.toLowerCase();
                r.put("ok", low.contains("new state") || low.contains(enable ? "enabled" : "disabled"));
                r.put("output", out != null ? out.trim() : "");
            } catch (Exception e) {
                try { r.put("ok", false); r.put("output", "Error: " + e.getMessage()); } catch (Exception ignored) {}
            }
            return r.toString();
        }

        // ---- Logcat reader ----
        /** Recent logcat lines. level=V/D/I/W/E/F, filter=optional text grepped in-process, lines=tail count. */
        @JavascriptInterface
        public String getLogcat(String level, String filter, int lines) {
            try {
                if ("standard".equals(resolveExecMode())) return "Error: reading logcat needs ADB, Shizuku or Root.";
                String lv = (level == null || !level.matches("[VDIWEF]")) ? "V" : level;
                int n = (lines <= 0 || lines > 5000) ? 500 : lines;
                String out = executeShell("logcat -d -v threadtime -t " + n + " *:" + lv);
                if (out == null) out = "";
                // The filter is applied here, never in the shell, so it can't inject anything.
                if (filter != null && !filter.trim().isEmpty()) {
                    String f = filter.toLowerCase();
                    StringBuilder sb = new StringBuilder();
                    for (String line : out.split("\n")) if (line.toLowerCase().contains(f)) sb.append(line).append('\n');
                    out = sb.toString();
                }
                return out.trim().isEmpty() ? "(no matching log lines)" : out;
            } catch (Exception e) {
                return "Error: " + (e.getMessage() != null ? e.getMessage() : "logcat failed");
            }
        }

        @JavascriptInterface
        public String clearLogcat() {
            try {
                if ("standard".equals(resolveExecMode())) return "Error: needs ADB, Shizuku or Root.";
                executeShell("logcat -c");
                return "cleared";
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        // ---- Privileged file manager ----
        /** Lists a directory via the active backend. Returns the raw `ls -la` output for the JS side to
         *  parse ({path,parent,raw}), which keeps the parser testable and robust to toybox vs busybox. */
        @JavascriptInterface
        public String fmList(String path) {
            JSONObject res = new JSONObject();
            try {
                if ("standard".equals(resolveExecMode())) { res.put("error", "File manager needs ADB, Shizuku or Root."); return res.toString(); }
                String p = (path == null || path.isEmpty()) ? "/" : path;
                if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
                String out = executeShell("ls -la " + BackupScripts.quote(p));
                res.put("path", p);
                int slash = p.lastIndexOf('/');
                res.put("parent", p.equals("/") ? "/" : (slash <= 0 ? "/" : p.substring(0, slash)));
                res.put("raw", out != null ? out : "");
            } catch (Exception e) {
                try { res.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** First 128 KB of a file as text (for viewing). */
        @JavascriptInterface
        public String fmRead(String path) {
            try {
                if ("standard".equals(resolveExecMode())) return "Error: needs ADB, Shizuku or Root.";
                return executeShell("toybox head -c 131072 " + BackupScripts.quote(path) + " 2>&1 || head -c 131072 " + BackupScripts.quote(path));
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        /** File op: mkdir|touch|rm|cp|mv. For rm, dirs are removed recursively. */
        @JavascriptInterface
        public String fmOp(String op, String a, String b) {
            JSONObject res = new JSONObject();
            try {
                if ("standard".equals(resolveExecMode())) { res.put("ok", false); res.put("output", "needs ADB, Shizuku or Root"); return res.toString(); }
                if (a == null || a.isEmpty()) { res.put("ok", false); res.put("output", "no path"); return res.toString(); }
                String qa = BackupScripts.quote(a);
                String cmd;
                if ("mkdir".equals(op)) cmd = "mkdir -p " + qa + " && echo OK";
                else if ("touch".equals(op)) cmd = "touch " + qa + " && echo OK";
                else if ("rm".equals(op)) cmd = "rm -rf " + qa + " && echo OK";
                else if ("cp".equals(op)) cmd = "cp -r " + qa + " " + BackupScripts.quote(b) + " && echo OK";
                else if ("mv".equals(op)) cmd = "mv " + qa + " " + BackupScripts.quote(b) + " && echo OK";
                else { res.put("ok", false); res.put("output", "unknown op"); return res.toString(); }
                String out = executeShell(cmd);
                res.put("ok", out != null && out.contains("OK"));
                res.put("output", out != null ? out.trim() : "");
            } catch (Exception e) {
                try { res.put("ok", false); res.put("output", "Error: " + e.getMessage()); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Stages an APK from an arbitrary (privileged) path to a readable temp, for the Installer. {ok,ref}|{error}. */
        @JavascriptInterface
        public String fmInstall(String path) {
            JSONObject res = new JSONObject();
            try {
                if ("standard".equals(resolveExecMode())) { res.put("ok", false); res.put("error", "needs ADB, Shizuku or Root"); return res.toString(); }
                String staged = "/data/local/tmp/fm_install.apk";
                String out = executeShell("cp " + BackupScripts.quote(path) + " " + staged + " && chmod 644 " + staged + " && echo OK");
                if (out == null || !out.contains("OK")) { res.put("ok", false); res.put("error", out != null ? out.trim() : "copy failed"); return res.toString(); }
                res.put("ok", true);
                res.put("ref", staged);
            } catch (Exception e) {
                try { res.put("ok", false); res.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Runs ART dex optimization for a package (pm compile -m <mode> [-f]) via the active backend. */
        @JavascriptInterface
        public String optimizeApp(String pkg, String mode, boolean force) {
            JSONObject r = new JSONObject();
            try {
                if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) { r.put("ok", false); r.put("output", "Error: invalid package"); return r.toString(); }
                if ("standard".equals(resolveExecMode())) { r.put("ok", false); r.put("output", "Error: dex optimization needs ADB, Shizuku or Root."); return r.toString(); }
                String m = mode == null ? "speed" : mode.replaceAll("[^a-z-]", "");
                if (m.isEmpty()) m = "speed";
                String out = executeShell("pm compile -m " + m + (force ? " -f " : " ") + pkg);
                String low = out == null ? "" : out.toLowerCase();
                r.put("ok", low.contains("success") || low.contains("performed") || (!low.contains("error") && !low.contains("failure") && !low.contains("unknown") && !low.contains("usage")));
                r.put("output", out != null && !out.trim().isEmpty() ? out.trim() : "Done");
            } catch (Exception e) {
                try { r.put("ok", false); r.put("output", "Error: " + e.getMessage()); } catch (Exception ignored) {}
            }
            return r.toString();
        }

        @JavascriptInterface
        public void extractApk(String pkg) {
            runExtractApk(pkg);
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
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, safeName.endsWith(".xml") ? "text/xml" : safeName.endsWith(".csv") ? "text/csv" : "text/plain");
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

        /** Opens the Android share sheet with plain text (package lists, versions, manifests). */
        @JavascriptInterface
        public void shareText(final String subject, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_SUBJECT, subject == null ? "" : subject);
                        send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                        startActivity(Intent.createChooser(send, "Share"));
                    } catch (Exception e) {
                        Log.e(TAG, "shareText failed", e);
                    }
                }
            });
        }

        /** Writes text to a private cache file and shares it as an attachment (CSV, XML, JSON). Returns "" or "Error: ...". */
        @JavascriptInterface
        public String shareTextFile(String fileName, String text, String mime) {
            try {
                File f = ShareProvider.newShareFile(MainActivity.this, fileName);
                OutputStream out = new FileOutputStream(f);
                try {
                    out.write((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                shareUri(ShareProvider.uriFor(f), mime == null || mime.isEmpty() ? "text/plain" : mime, f.getName());
                return "";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        /** Shares a file this app saved to Downloads (a content:// reference, or a file path on Android 9 and older). */
        @JavascriptInterface
        public String shareStoredFile(String ref, String mime, String name) {
            try {
                Uri uri;
                if (ref != null && ref.startsWith("content://")) {
                    uri = Uri.parse(ref);
                } else {
                    File src = new File(ref);
                    File copy = ShareProvider.newShareFile(MainActivity.this, name != null ? name : src.getName());
                    java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
                    try {
                        copyFile(src, out);
                    } finally {
                        out.close();
                    }
                    uri = ShareProvider.uriFor(copy);
                }
                shareUri(uri, mime == null || mime.isEmpty() ? "application/octet-stream" : mime, name);
                return "";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        private void shareUri(final Uri uri, final String mime, final String name) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mime);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        if (name != null) send.putExtra(Intent.EXTRA_SUBJECT, name);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(send, "Share");
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(chooser);
                    } catch (Exception e) {
                        Log.e(TAG, "share failed", e);
                    }
                }
            });
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
        if (isHeadless()) return;
        String saved = prefs.getString("working_mode", activeWorkingMode);
        if (!saved.equals(activeWorkingMode)) {
            // A tile or the widget switched the mode while the app was in the background
            activeWorkingMode = saved;
            cachedAutoMode = null;
            notifyJs("window.checkAllWorkingModes && window.checkAllWorkingModes(false)");
        }
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
