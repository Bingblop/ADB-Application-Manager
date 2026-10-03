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
import java.io.IOException;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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

    private static int renderGoneCount;               // WebView renderer deaths within the last minute (process-wide: a re-created Activity must not forget them)
    private static long renderGoneAt;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Hands a job to the executor. False when it refuses (shut down with the Activity): the caller then clears the
     * process-wide "busy" mark it set before submitting, so a re-created page isn't told "busy" for the rest of the process.
     */
    private boolean submitJob(Runnable job) {
        try {
            executor.submit(job);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException refused) {
            return false;
        }
    }

    /** One thread for the Settings tab: a toggle tapped twice quickly must reach the phone in that order, and reads must not overtake writes. */
    private final ExecutorService settingsExecutor = Executors.newSingleThreadExecutor();

    private boolean submitSettingsJob(Runnable job) {
        try {
            settingsExecutor.submit(job);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException refused) {
            return false;
        }
    }

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
            invalidateModeCache();
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
            invalidateModeCache();                  // Shizuku just came up: the page's next look must not read a probe from before it did
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

            // The page holds a powerful bridge (shell, files, installs), so it never navigates away from the
            // bundled UI: a web link opens in the browser instead of loading inside this WebView.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u.toString().startsWith("file:///android_asset/")) return false;
                String scheme = u.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {}
                }
                return true;
            }

            // If the WebView's renderer is killed (low memory), start over instead of crashing the app: the dead WebView
            // is destroyed and the Activity re-created. A renderer that dies again and again (three times within a minute)
            // is not retried for ever.
            @Override
            public boolean onRenderProcessGone(final WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "WebView renderer gone (crashed=" + detail.didCrash() + ")");
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - renderGoneAt > 60000) renderGoneCount = 0;
                renderGoneAt = now;
                final boolean giveUp = ++renderGoneCount >= 3;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.view.ViewParent parent = view.getParent();
                            if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(view);
                            view.destroy();
                        } catch (Throwable ignored) {}
                        if (giveUp) {
                            Toast.makeText(MainActivity.this, "The web view keeps crashing, so the app is closing. Restart the phone or update Android System WebView, then open it again.", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            recreate();
                        }
                    }
                });
                return true;
            }
        });
        webView.setBackgroundColor(0xFF080A0F);

        // Remote WebView debugging only for a debuggable build (the release build is not).
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
        // Leftovers of archive browsing (nested archives, files staged for install) from a previous run
        executor.submit(new Runnable() {
            @Override
            public void run() {
                synchronized (archiveLock) {
                    if (archiveBusy) return;          // this process (re-created Activity) still has a job using them
                    deleteContents(archiveNestedDir());
                    deleteContents(archiveStageDirLocal());
                    // working files of a signing / edit job that a killed process never got to remove
                    new File(getCacheDir(), "archive_sign_prep.apk").delete();
                    new File(getCacheDir(), "archive_sign_out.apk").delete();
                    new File(getCacheDir(), "archive_edit.tmp").delete();
                }
            }
        });
        registerWallpaperListener();
        handleIncomingIntent(getIntent());
        maybeRequestFirstLaunchPermissions();
    }

    /**
     * On the very first launch, check and request the standard runtime permissions the app uses
     * (notifications, and legacy storage on pre-Android 11). Special-access permissions - All-files
     * access, usage access and overlay - are still requested in context from their own screens.
     */
    private void maybeRequestFirstLaunchPermissions() {
        try {
            if (prefs == null || prefs.getBoolean("first_launch_perms_done", false)) return;
            prefs.edit().putBoolean("first_launch_perms_done", true).apply();
            java.util.List<String> req = new java.util.ArrayList<String>();
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                req.add("android.permission.POST_NOTIFICATIONS");
            }
            // Android 11+ browses storage through All-files access (a special-access grant), not these.
            if (Build.VERSION.SDK_INT < 30) {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    req.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    req.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), 9100);
        } catch (Exception ignored) {}
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

    // The mode probe is a few process spawns and a socket check, and the page asks for it on most view changes.
    private static final Object modeCacheLock = new Object();
    private static String modeCacheJson;
    private static long modeCacheAt;
    private static long modeCacheGen;          // bumped by every invalidation: a probe that was running meanwhile must not store its (older) answer
    private static final long MODE_CACHE_MS = 2500;
    private static boolean rootChecked;
    private static boolean rootCached;
    private static long rootCheckedAt;

    private static void invalidateModeCache() {
        synchronized (modeCacheLock) {
            modeCacheJson = null;
            modeCacheGen++;
        }
    }

    private void setConfiguredMode(String mode) {
        invalidateModeCache();
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

    // ---------------------------------------------------------------------------------------------
    // Wi-Fi pairing notification (inline reply runs `adb pair` straight from the shade)
    // ---------------------------------------------------------------------------------------------

    private void toastUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Posts the inline-reply pairing notification. Ensures notification permission, discovers the
     *  current `_adb-tls-pairing` endpoint via mDNS off the UI thread, then builds the notification. */
    private void postPairingNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9201);
                } catch (Exception ignored) {}
                toastUi("Allow notifications, then tap “Pair via notification” again.");
                return;
            }
        }
        toastUi("Preparing pairing notification…");
        new Thread(new Runnable() {
            public void run() {
                String endpoint = "";
                try {
                    String raw = runProcessWithTimeout(buildAdbProcess("mdns", "services"), 4000);
                    if (raw != null) {
                        for (String line : raw.split("\n")) {
                            if (line.contains("_adb-tls-pairing")) {
                                String[] parts = line.trim().split("\\s+");
                                String ep = parts.length > 0 ? parts[parts.length - 1] : "";
                                if (ep.contains(":")) { endpoint = ep; break; }
                            }
                        }
                    }
                } catch (Exception ignored) {}
                final String ep = endpoint;
                runOnUiThread(new Runnable() {
                    public void run() { buildAndPostPairingNotification(ep); }
                });
            }
        }).start();
    }

    /** Builds and shows the pairing notification with a RemoteInput "Pair" action. Replying runs
     *  `adb pair` in {@link PairReceiver} without the app needing to be open. */
    private void buildAndPostPairingNotification(String endpoint) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    PairReceiver.CHANNEL, "Wi-Fi pairing", android.app.NotificationManager.IMPORTANCE_HIGH));

            boolean haveEndpoint = endpoint != null && !endpoint.isEmpty();
            String hint = haveEndpoint
                    ? "6-digit pairing code"
                    : "port code  (e.g. 37123 123456)";

            android.app.RemoteInput remoteInput = new android.app.RemoteInput.Builder(PairReceiver.KEY_CODE)
                    .setLabel(hint)
                    .build();

            Intent replyIntent = new Intent(this, PairReceiver.class).setAction(PairReceiver.ACTION);
            if (haveEndpoint) replyIntent.putExtra(PairReceiver.EXTRA_ENDPOINT, endpoint);
            int replyFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) replyFlags |= android.app.PendingIntent.FLAG_MUTABLE;
            android.app.PendingIntent replyPending = android.app.PendingIntent.getBroadcast(
                    this, 71, replyIntent, replyFlags);

            android.app.Notification.Action action = new android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                    "Pair", replyPending)
                    .addRemoteInput(remoteInput)
                    .build();

            String body = haveEndpoint
                    ? ("Found pairing service at " + endpoint + ".\nTap Pair and type the 6-digit code shown on your device.")
                    : "On your device: Wireless debugging ▸ Pair device with pairing code.\nTap Pair and reply with: port code  (e.g. 37123 123456).";

            Intent openIntent = new Intent(this, MainActivity.class);
            int openFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) openFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent openPending =
                    android.app.PendingIntent.getActivity(this, 72, openIntent, openFlags);

            android.app.Notification n = new android.app.Notification.Builder(this, PairReceiver.CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Pair over Wi-Fi")
                    .setContentText(haveEndpoint
                            ? ("Tap Pair, then enter the code for " + endpoint)
                            : "Tap Pair, then enter: port code")
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                    .addAction(action)
                    .setContentIntent(openPending)
                    .setAutoCancel(false)
                    .build();
            nm.notify(PairReceiver.NOTIF_ID, n);
            Toast.makeText(this, "Pairing notification posted — open your shade to enter the code.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't post pairing notification: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
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
        FutureTask<String> future = new FutureTask<String>(new Callable<String>() {
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
            executor.execute(future);
        } catch (RejectedExecutionException closing) {
            // the activity is being destroyed (a theme change restarts it): the command is already running, so read it out on a thread of its own
            Thread reader = new Thread(future, "proc-read");
            reader.setDaemon(true);
            reader.start();
        }

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
        invalidateModeCache();
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
        invalidateModeCache();                      // connected (or not) now: a probe that overlapped the connect must not keep the old picture
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
    // Rish shell: the Terminal tab's persistent shell (see RishShell). It runs as the Shizuku shell
    // user, so cd / export / variables persist between commands. Output is streamed to the page.
    // ---------------------------------------------------------------------------------------------

    private static final long RISH_COMMAND_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int RISH_OUTPUT_CAP = 1500000;    // characters shown per command
    private static final int RISH_FLUSH_CHARS = 24000;
    private static final long RISH_FLUSH_MS = 40;

    private static final int RISH_IDLE_CHARS_PER_SEC = 200000;     // background-job output the page is sent per second at most

    private final Object rishGate = new Object();
    private static volatile String rishLastCwd = "";       // where the shell was last, so the next one opens there (read and written from different threads)
    private long rishIdleWindowAt;
    private int rishIdleChars;
    private boolean rishIdleDropped;
    private RishShell rishShell;           // guarded by rishGate
    private boolean rishStarting;          // guarded by rishGate
    private boolean rishRunning;           // guarded by rishGate
    private final StringBuilder rishOut = new StringBuilder();   // guarded by itself
    private String rishOutRunId = "";
    private boolean rishFlushScheduled;
    private final android.os.Handler rishHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable rishFlusher = new Runnable() {
        @Override
        public void run() {
            rishFlush();
        }
    };

    /** Starts a process through the Shizuku API, or through the bundled rish when that call fails. */
    private Process rishSpawn(String[] argv) throws Exception {
        if (isShizukuAuthorized()) {
            try {
                if (shizukuNewProcessMethod == null) {
                    Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    m.setAccessible(true);
                    shizukuNewProcessMethod = m;
                }
                return (Process) shizukuNewProcessMethod.invoke(null, argv, null, null);
            } catch (Throwable t) {
                Log.w(TAG, "Shizuku newProcess failed for the Rish shell, trying the bundled rish: " + t.getMessage());
            }
        }
        if (rishFile != null && rishFile.exists()) {
            List<String> cmd = new ArrayList<String>();
            cmd.add("/system/bin/sh");
            cmd.add(rishFile.getAbsolutePath());
            if (argv.length > 1) {
                cmd.add("-c");
                StringBuilder script = new StringBuilder();
                for (int i = 2; i < argv.length; i++) script.append(i > 2 ? " " : "").append(argv[i]);
                cmd.add(script.toString());
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("RISH_APPLICATION_ID", getPackageName());
            return pb.start();
        }
        throw new IOException("Shizuku is not authorized for this app");
    }

    /**
     * Output that arrives while no command is running (a background job). A runaway one (`yes &`) can produce tens of
     * megabytes a second, so the page gets a bounded share and one note that the rest was dropped.
     */
    private void rishEmitIdle(String text) {
        boolean notice = false;
        synchronized (rishOut) {
            long now = System.nanoTime() / 1000000L;
            if (now - rishIdleWindowAt >= 1000) {
                rishIdleWindowAt = now;
                rishIdleChars = 0;
                rishIdleDropped = false;
            }
            if (rishIdleChars >= RISH_IDLE_CHARS_PER_SEC) {
                if (!rishIdleDropped) {
                    rishIdleDropped = true;
                    notice = true;
                } else {
                    return;
                }
            } else {
                rishIdleChars += text.length();
            }
        }
        rishEmit("", notice ? "\n[a background job is printing faster than this screen can show: output is being dropped]\n" : text);
    }

    /** Buffers shell output and sends it to the page a few times a second instead of once per read. */
    private void rishEmit(String runId, String text) {
        synchronized (rishOut) {
            if (rishOut.length() > 0 && !runId.equals(rishOutRunId)) rishFlushLocked();
            rishOutRunId = runId;
            rishOut.append(text);
            if (rishOut.length() >= RISH_FLUSH_CHARS) {
                rishFlushLocked();
            } else if (!rishFlushScheduled) {
                rishFlushScheduled = true;
                rishHandler.postDelayed(rishFlusher, RISH_FLUSH_MS);
            }
        }
    }

    private void rishFlush() {
        synchronized (rishOut) {
            rishFlushLocked();
        }
    }

    /** Posts the buffered output to the UI thread in order (post order == buffer order). */
    private void rishFlushLocked() {
        rishFlushScheduled = false;
        rishHandler.removeCallbacks(rishFlusher);
        if (rishOut.length() == 0) return;
        final String runId = rishOutRunId;
        final String text = rishOut.toString();
        rishOut.setLength(0);
        rishHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                int i = 0;
                while (i < text.length()) {
                    int end = Math.min(text.length(), i + 32000);
                    if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
                    webView.evaluateJavascript("window.onRishOutput && window.onRishOutput(" + JSONObject.quote(runId)
                            + "," + JSONObject.quote(text.substring(i, end)) + ")", null);
                    i = end;
                }
            }
        });
    }

    private void closeRishShell() {
        final RishShell sh;
        synchronized (rishGate) {
            sh = rishShell;
            rishShell = null;
        }
        if (sh != null) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    sh.close();
                }
            });
        }
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
        // "no su" rarely changes (Magisk can be installed while the app runs): ask the shell once a minute, not on every probe
        synchronized (modeCacheLock) {
            if (rootChecked && System.nanoTime() / 1000000L - rootCheckedAt < 60000) return rootCached;
        }
        boolean found;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "command -v su"});
            found = p.waitFor() == 0;
        } catch (Exception e) {
            found = false;
        }
        synchronized (modeCacheLock) {
            rootChecked = true;
            rootCached = found;
            rootCheckedAt = System.nanoTime() / 1000000L;
        }
        return found;
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
                    java.util.Set<String> newSigners = archiveSigned != null ? signerDigests(archiveSigned, true) : new HashSet<String>();
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

    private void selfUpdateProgress(String stage, int percent, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("stage", stage);
            o.put("percent", percent);
            o.put("message", message == null ? "" : message);
            notifyUpdates("onSelfUpdateProgress", o);
        } catch (Exception ignored) {}
    }

    /** Checks this app's own latest GitHub release and reports it to window.onSelfUpdate(json). */
    private void runCheckSelfUpdate() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                String installed = currentVersionName();
                try {
                    o.put("installedVersion", installed);
                    JSONObject rel = UpdateManager.githubLatest(UpdateManager.SELF_REPO);
                    String latest = rel.optString("version");
                    o.put("latestVersion", latest);
                    o.put("page", rel.optString("page"));
                    o.put("downloadUrl", rel.optString("url"));
                    o.put("notes", rel.optString("notes"));
                    o.put("hasApk", !rel.optString("url").isEmpty());
                    o.put("newer", UpdateManager.compareVersions(latest, installed) > 0);
                    o.put("status", "ok");
                } catch (Exception e) {
                    try {
                        o.put("installedVersion", installed);
                        String m = e.getMessage();
                        o.put("status", m != null && m.contains("404") ? "no_releases" : "error");
                        o.put("error", m);
                    } catch (Exception ignored) {}
                }
                notifyUpdates("onSelfUpdate", o);
            }
        });
    }

    /**
     * Downloads the latest release APK of this app, verifies it is this package, newer and signed with the
     * same key, then installs it: through the active privileged mode (seamless, the app restarts), or - with
     * no privileged mode - by handing it to the system package installer for a normal install confirmation.
     */
    private void runInstallSelfUpdate() {
        final String pkg = getPackageName();
        if (!installingUpdates.add("self")) return;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                File apk = new File(new File(getCacheDir(), "updates"), "self.apk");
                boolean standard = "standard".equals(resolveExecMode());
                try {
                    apk.getParentFile().mkdirs();
                    selfUpdateProgress("downloading", 0, "Getting the latest release...");
                    JSONObject rel = UpdateManager.githubLatest(UpdateManager.SELF_REPO);
                    String url = rel.optString("url");
                    if (url == null || url.isEmpty()) throw new IllegalStateException("the latest release has no APK to download");
                    UpdateManager.download(url, apk, new UpdateManager.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            selfUpdateProgress("downloading", total > 0 ? (int) (done * 100 / total) : -1, (done / (1024 * 1024)) + " MB");
                        }
                    });

                    PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (archive == null || !pkg.equals(archive.packageName)) throw new IllegalStateException("the downloaded file is not this app");
                    long installedVc = versionCodeOf(getPackageManager().getPackageInfo(pkg, 0));
                    if (versionCodeOf(archive) <= installedVc) throw new IllegalStateException("already up to date (" + archive.versionName + ")");
                    // Android only accepts an update signed with the same key - fail clearly if it isn't.
                    int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                    java.util.Set<String> installedSigners = signerDigests(getPackageManager().getPackageInfo(pkg, sigFlags), true);
                    PackageInfo archiveSigned = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), sigFlags);
                    java.util.Set<String> newSigners = archiveSigned != null ? signerDigests(archiveSigned, true) : new HashSet<String>();
                    if (!installedSigners.isEmpty() && !newSigners.isEmpty()) {
                        java.util.Set<String> common = new HashSet<String>(installedSigners);
                        common.retainAll(newSigners);
                        if (common.isEmpty()) throw new IllegalStateException("the release APK is signed with a different key than this install, so Android won't accept it as an update");
                    }

                    if (standard) {
                        selfUpdateProgress("installing", 100, "Opening the installer...");
                        launchSystemInstaller(apk);
                        selfUpdateProgress("opened", 100, "Confirm the install to update to " + archive.versionName + ".");
                    } else {
                        selfUpdateProgress("installing", 100, "Installing " + archive.versionName + "...");
                        String out = installApk(apk);
                        if (out != null && out.contains("Success")) {
                            selfUpdateProgress("done", 100, "Updated to " + archive.versionName + ". The app will restart.");
                        } else {
                            throw new IllegalStateException(out == null || out.trim().isEmpty() ? "install failed" : out.trim());
                        }
                    }
                } catch (Exception e) {
                    selfUpdateProgress("error", 0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                } finally {
                    // The system installer reads the file asynchronously, so keep it in that path; the
                    // privileged path has already consumed it and can clean up.
                    if (!standard) apk.delete();
                    installingUpdates.remove("self");
                }
            }
        });
    }

    /** Hands an APK to the system package installer (normal install confirmation, no privileged mode needed). */
    private void launchSystemInstaller(File apk) throws Exception {
        File shareApk = ShareProvider.newShareFile(this, "app-update.apk");
        java.io.FileInputStream in = new java.io.FileInputStream(apk);
        try {
            FileOutputStream out = new FileOutputStream(shareApk);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally { out.close(); }
        } finally { in.close(); }
        final Uri uri = ShareProvider.uriFor(shareApk);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception ignored) {}
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

    private static String errMsg(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------------------------------------
    // Activity launch helpers
    // ---------------------------------------------------------------------------------------------

    /** True when an `am start` output indicates the activity actually started. */
    private static boolean isLaunchOk(String out) {
        String low = out == null ? "" : out.toLowerCase();
        boolean denied = low.contains("permission denial") || low.contains("securityexception")
                || low.contains("does not exist") || low.contains("unable to resolve")
                || low.contains("not found") || low.contains("exception");
        return low.contains("status: ok")
                || (low.contains("starting: intent") && !denied);
    }

    private String trimSetting(String s) {
        if (s == null) return "";
        s = s.trim();
        return "null".equals(s) ? "" : s;
    }

    private void restoreSecureSetting(AndroidBridge sh, String key, String val) {
        if (val == null || val.isEmpty()) sh.executeShell("settings delete secure " + key);
        else sh.executeShell("settings put secure " + key + " '" + val + "'");
    }

    /**
     * Launches an activity the system's way, so the exported / START_ANY_ACTIVITY check is bypassed:
     * temporarily make the target the device "assistant", press KEYCODE_ASSIST so the SYSTEM (not the
     * shell) starts it, then restore the user's assistant. This is how "Activity Manager" launches
     * unexported activities over Shizuku - a shell (uid 2000) can't `am start` an unexported activity of
     * another uid, but it can set the secure settings and inject the assist key. Needs WRITE_SECURE_SETTINGS,
     * which the shell (ADB / Shizuku) and root both already hold. Returns the `input keyevent` output.
     */
    private String launchViaAssistant(String comp, StringBuilder tried) {
        AndroidBridge sh = new AndroidBridge();
        String oldAssist = trimSetting(sh.executeShell("settings get secure assistant"));
        String oldVis = trimSetting(sh.executeShell("settings get secure voice_interaction_service"));
        String keyOut = "";
        try {
            sh.executeShell("settings put secure assistant '" + comp + "'");
            // Clear the voice-interaction service so the assist key resolves to the 'assistant' component
            // (a set VIS takes precedence). It is restored in finally.
            sh.executeShell("settings delete secure voice_interaction_service");
            keyOut = sh.executeShell("input keyevent 219"); // KEYCODE_ASSIST
            if (tried != null) {
                tried.append("$ settings put secure assistant '").append(comp).append("'\n");
                tried.append("$ input keyevent 219 (KEYCODE_ASSIST)\n").append(keyOut == null ? "" : keyOut.trim()).append("\n\n");
            }
            // Give the system a moment to start the activity before the settings are restored.
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        } finally {
            restoreSecureSetting(sh, "assistant", oldAssist);
            restoreSecureSetting(sh, "voice_interaction_service", oldVis);
        }
        return keyOut == null ? "" : keyOut;
    }

    /** X.500 subject DNs of a package's signing certificates (for repackage / debug-key detection). */
    private List<String> signerDnList(String pkg) {
        List<String> out = new ArrayList<String>();
        try {
            int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, sigFlags);
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                sigs = pi.signatures;
            }
            if (sigs != null) {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                for (android.content.pm.Signature s : sigs) {
                    try {
                        java.security.cert.X509Certificate c = (java.security.cert.X509Certificate)
                                cf.generateCertificate(new java.io.ByteArrayInputStream(s.toByteArray()));
                        out.add(c.getSubjectDN().getName());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // ShizuStore catalog (browse + install Shizuku apps straight from their upstream sources)
    // ---------------------------------------------------------------------------------------------

    /** Loads the ShizuStore catalog off-thread and reports it to window.onStoreCatalog(json). */
    private void runStoreCatalog() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    JSONObject cat = ShizuStore.catalog(200, 2000);
                    o.put("status", "ok");
                    o.put("base", ShizuStore.BASE);
                    o.put("items", cat.optJSONArray("items"));
                    o.put("total", cat.optInt("total"));
                } catch (Exception e) {
                    try { o.put("status", "error"); o.put("error", errMsg(e)); } catch (Exception ignored) {}
                }
                notifyUpdates("onStoreCatalog", o);
            }
        });
    }

    /** Loads one ShizuStore app detail off-thread and reports it to window.onStoreApp(json). */
    private void runStoreApp(final String slug) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    JSONObject detail = ShizuStore.app(slug);
                    o.put("status", "ok");
                    o.put("base", ShizuStore.BASE);
                    o.put("app", detail);
                    JSONObject dl = ShizuStore.primaryDownload(detail);
                    if (dl != null) o.put("download", dl);
                } catch (Exception e) {
                    try { o.put("status", "error"); o.put("error", errMsg(e)); } catch (Exception ignored) {}
                }
                try { o.put("slug", slug); } catch (Exception ignored) {}
                notifyUpdates("onStoreApp", o);
            }
        });
    }

    private final java.util.Set<String> storeInstalling =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * Downloads a ShizuStore APK from its upstream URL and installs it through the active mode (or hands
     * it to the system installer with no privileged mode). Progress -> window.onStoreInstallProgress(json).
     */
    private void runStoreInstall(final String apkUrl, final String pkg, final String label) {
        if (apkUrl == null || !apkUrl.startsWith("https://")) {
            storeInstallProgress(pkg, "error", 0, "This app has no direct APK to install.");
            return;
        }
        downloadAndInstall(apkUrl, pkg, label, pkg, "");
    }

    /**
     * Downloads an https APK and installs it through the active mode (shared by every Store source).
     * {@code pkg}: the package the download must turn out to be ("" when the catalog doesn't know it).
     * {@code progressKey}: identity used for progress events and the one-at-a-time guard (a GitHub repo has no
     * package name until the APK is read). {@code sha256}: a checksum published by the repository, verified
     * before anything is installed ("" = none available).
     */
    private void downloadAndInstall(final String apkUrl, final String pkg, final String label,
                                    final String progressKey, final String sha256) {
        final String key = progressKey != null && !progressKey.isEmpty() ? progressKey
                : (pkg == null || pkg.isEmpty() ? apkUrl : pkg);
        if (!storeInstalling.add(key)) return;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                // One file per item, so two installs started back to back can't overwrite each other's download
                File apk = new File(new File(getCacheDir(), "updates"), "store-" + Integer.toHexString(key.hashCode()) + ".apk");
                boolean standard = "standard".equals(resolveExecMode());
                try {
                    apk.getParentFile().mkdirs();
                    final String name = label == null || label.isEmpty() ? (pkg == null || pkg.isEmpty() ? "app" : pkg) : label;
                    storeInstallProgress(key, "downloading", 0, "Downloading " + name + "…");
                    UpdateManager.download(apkUrl, apk, new UpdateManager.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            storeInstallProgress(key, "downloading", total > 0 ? (int) (done * 100 / total) : -1,
                                    (done / (1024 * 1024)) + " MB");
                        }
                    });
                    // The repository's own checksum, when it publishes one (F-Droid index, ShizuStore)
                    if (sha256 != null && sha256.matches("(?i)[0-9a-f]{64}")) {
                        storeInstallProgress(key, "verifying", 100, "Checking the download's SHA-256…");
                        String got = VirusTotal.sha256(apk);
                        if (!got.equalsIgnoreCase(sha256)) {
                            throw new IllegalStateException("the download doesn't match the checksum published by the repository "
                                    + "(expected " + sha256.substring(0, 12) + "…, got " + got.substring(0, 12) + "…) - not installed");
                        }
                    }
                    PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (archive == null || archive.packageName == null) throw new IllegalStateException("the download is not a valid APK");
                    // Guard against a redirected/wrong download installing an unexpected package.
                    if (pkg != null && !pkg.isEmpty() && !pkg.equals(archive.packageName))
                        throw new IllegalStateException("the download is " + archive.packageName + ", not " + pkg);
                    if (standard) {
                        storeInstallProgress(key, "installing", 100, "Opening the installer…");
                        launchSystemInstaller(apk);
                        storeInstallProgress(key, "opened", 100, "Confirm the install of " + name + ".");
                    } else {
                        storeInstallProgress(key, "installing", 100, "Installing " + name + "…");
                        String out = installApk(apk);
                        if (out != null && out.contains("Success")) {
                            storeInstallProgress(key, "done", 100, "Installed " + name
                                    + (archive.versionName != null ? " " + archive.versionName : "") + ".");
                        } else {
                            throw new IllegalStateException(out == null || out.trim().isEmpty() ? "install failed" : out.trim());
                        }
                    }
                } catch (Exception e) {
                    storeInstallProgress(key, "error", 0, errMsg(e));
                } finally {
                    if (!standard) apk.delete();
                    storeInstalling.remove(key);
                }
            }
        });
    }

    private void storeInstallProgress(String pkg, String stage, int percent, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("stage", stage);
            o.put("percent", percent);
            o.put("message", message == null ? "" : message);
            notifyUpdates("onStoreInstallProgress", o);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------------------------------
    // Store sub-tabs: GitHub (Komi catalog), F-Droid repositories, Orion
    //
    // Catalogs reach the WebView as window.onStoreSource(json) events:
    //   {source, arg?, status:"ok", items:[...], append, done, total, note?, fallback?}   (chunks of <=400 items)
    //   {source, arg?, status:"progress", message}
    //   {source, arg?, status:"error", error}
    // The first chunk has append=false (replace the list); the last has done=true.
    // ---------------------------------------------------------------------------------------------

    private static final int STORE_CHUNK = 400;
    private static final long STORE_CACHE_TTL_MS = 12L * 3600 * 1000;
    private static final int GITHUB_MAX_APPS = 5000;

    private File storeCacheFile(String key) {
        File d = new File(getCacheDir(), "store");
        d.mkdirs();
        return new File(d, key.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    /** A cached catalog (JSON array of items) younger than {@code ttlMs}, or null. */
    private JSONArray storeCacheRead(String key, long ttlMs) {
        try {
            File f = storeCacheFile(key);
            if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > ttlMs || f.length() > 40L * 1024 * 1024) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) f.length());
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally { in.close(); }
            return new JSONArray(bo.toString("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    private void storeCacheWrite(String key, JSONArray items) {
        try {
            FileOutputStream out = new FileOutputStream(storeCacheFile(key));
            try { out.write(items.toString().getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) {}
    }

    private void storeCacheClear(String key) {
        try { storeCacheFile(key).delete(); } catch (Throwable ignored) {}
    }

    private JSONObject storeEvent(String source, String arg, String status) throws Exception {
        JSONObject o = new JSONObject();
        o.put("source", source);
        if (arg != null) o.put("arg", arg);
        o.put("status", status);
        return o;
    }

    private void storeProgress(String source, String arg, String message) {
        try {
            JSONObject o = storeEvent(source, arg, "progress");
            o.put("message", message);
            notifyUpdates("onStoreSource", o);
        } catch (Exception ignored) {}
    }

    private void storeError(String source, String arg, String message) {
        try {
            JSONObject o = storeEvent(source, arg, "error");
            o.put("error", message);
            notifyUpdates("onStoreSource", o);
        } catch (Exception ignored) {}
    }

    /** Sends one chunk of items. {@code extra} (may be null) is merged into the event. */
    private void storeChunk(String source, String arg, JSONArray items, boolean append, boolean done, int total, JSONObject extra) {
        try {
            JSONObject o = storeEvent(source, arg, "ok");
            o.put("items", items);
            o.put("append", append);
            o.put("done", done);
            o.put("total", total);
            if (extra != null) {
                java.util.Iterator<String> it = extra.keys();
                while (it.hasNext()) { String k = it.next(); o.put(k, extra.get(k)); }
            }
            notifyUpdates("onStoreSource", o);
        } catch (Exception ignored) {}
    }

    /** Delivers a whole catalog in chunks so no single message to the WebView is huge. */
    private void storeDeliver(String source, String arg, JSONArray items, JSONObject extra) {
        int total = items.length();
        if (total == 0) { storeChunk(source, arg, new JSONArray(), false, true, 0, extra); return; }
        for (int i = 0; i < total; i += STORE_CHUNK) {
            JSONArray part = new JSONArray();
            for (int j = i; j < Math.min(i + STORE_CHUNK, total); j++) part.put(items.opt(j));
            boolean last = i + STORE_CHUNK >= total;
            storeChunk(source, arg, part, i > 0, last, total, last ? extra : null);
        }
    }

    /**
     * The GitHub tab: pages the Komi catalog's whole Android list (50 per request, shown as it arrives), or
     * - for arg "q:<text>" - runs a live relevance search. When Komi's server can't be reached it falls back
     * to its offline mirror, then to a small built-in list, and says so.
     */
    private void loadGithubCatalog(String arg, boolean force) throws Exception {
        final String source = "github";
        if (arg != null && arg.startsWith("q:")) {
            String q = arg.substring(2).trim();
            if (q.isEmpty()) { storeDeliver(source, arg, new JSONArray(), null); return; }
            JSONArray found = new JSONArray();
            Set<String> seen = new HashSet<String>();
            for (int page = 0; page < 2; page++) {
                JSONObject body = KomiApi.searchRaw(q, "", page * KomiApi.PAGE, KomiApi.PAGE);
                JSONArray rawItems = body.optJSONArray("items");
                JSONArray part = KomiApi.mapItems(body, seen);
                for (int i = 0; i < part.length(); i++) found.put(part.get(i));
                if (rawItems == null || rawItems.length() < KomiApi.PAGE) break;
            }
            JSONObject extra = new JSONObject();
            extra.put("search", true);
            extra.put("query", q);
            storeDeliver(source, arg, found, extra);
            return;
        }

        if (force) storeCacheClear("github-browse");
        JSONArray cached = force ? null : storeCacheRead("github-browse", 6L * 3600 * 1000);
        if (cached != null && cached.length() > 0) {
            JSONObject extra = new JSONObject();
            extra.put("cached", true);
            storeDeliver(source, arg, cached, extra);
            return;
        }

        JSONArray all = new JSONArray();
        Set<String> seen = new HashSet<String>();
        boolean first = true;
        int offset = 0;
        String stopNote = "";
        try {
            while (all.length() < GITHUB_MAX_APPS) {
                JSONObject body = KomiApi.searchRaw("", "stars", offset, KomiApi.PAGE);
                JSONArray rawItems = body.optJSONArray("items");
                int got = rawItems == null ? 0 : rawItems.length();
                JSONArray part = KomiApi.mapItems(body, seen);
                for (int i = 0; i < part.length(); i++) all.put(part.get(i));
                if (part.length() > 0 || first) storeChunk(source, arg, part, !first, false, all.length(), null);
                first = false;
                if (got < KomiApi.PAGE) break;
                offset += got;
                Thread.sleep(100); // be a polite client: ~10 requests a second at most
            }
        } catch (Exception e) {
            if (all.length() == 0) {
                githubFallback(arg);
                return;
            }
            stopNote = "Stopped early after " + all.length() + " apps (" + errMsg(e) + "). Tap Refresh to retry.";
        }
        JSONObject extra = new JSONObject();
        if (!stopNote.isEmpty()) extra.put("note", stopNote);
        storeChunk(source, arg, new JSONArray(), true, true, all.length(), extra);
        if (stopNote.isEmpty() && all.length() > 0) storeCacheWrite("github-browse", all);
    }

    /** Komi's server is unreachable: its static mirror, else a small built-in list. */
    private void githubFallback(String arg) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("fallback", true);
        try {
            JSONArray mirror = KomiApi.mirrorItems();
            extra.put("note", "Couldn't reach the Komi catalog server, so this is its offline mirror (" + mirror.length()
                    + " popular apps). Tap Refresh to try the full catalog again.");
            storeDeliver("github", arg, mirror, extra);
        } catch (Exception e) {
            JSONArray builtin = Stores.githubBuiltin();
            extra.put("note", "Couldn't reach the Komi catalog. Showing " + builtin.length() + " built-in apps; search still works once you're online.");
            storeDeliver("github", arg, builtin, extra);
        }
    }

    private void loadOrionCatalog(boolean force) throws Exception {
        if (force) storeCacheClear("orion");
        JSONArray cached = force ? null : storeCacheRead("orion", STORE_CACHE_TTL_MS);
        if (cached != null && cached.length() > 0) { storeDeliver("orion", null, cached, null); return; }
        storeProgress("orion", null, "Downloading the Orion catalog…");
        JSONArray items = Stores.orionCatalog(4000).optJSONArray("items");
        if (items == null) items = new JSONArray();
        storeCacheWrite("orion", items);
        storeDeliver("orion", null, items, null);
    }

    private void loadFdroidRepo(final String address, boolean force) throws Exception {
        final String source = "fdroid-repo";
        if (address == null || !address.startsWith("https://")) {
            storeError(source, address, "Invalid repository address.");
            return;
        }
        final String ckey = "fdroid-" + Integer.toHexString(address.hashCode());
        if (force) storeCacheClear(ckey);
        JSONArray cached = force ? null : storeCacheRead(ckey, STORE_CACHE_TTL_MS);
        if (cached != null && cached.length() > 0) {
            JSONObject extra = new JSONObject();
            extra.put("cached", true);
            storeDeliver(source, address, cached, extra);
            return;
        }
        storeProgress(source, address, "Contacting the repository…");
        FdroidIndex.Result res = FdroidIndex.load(address, Build.SUPPORTED_ABIS, Build.VERSION.SDK_INT, new FdroidIndex.Sink() {
            @Override
            public void onProgress(long bytes, int apps) {
                storeProgress(source, address, "Reading the catalog… " + XapkInfo.humanBytes(bytes) + " · " + apps + " apps");
            }
        });
        storeCacheWrite(ckey, res.items);
        JSONObject extra = new JSONObject();
        extra.put("repoName", res.repoName);
        extra.put("format", res.format);
        extra.put("skipped", res.skipped);
        storeDeliver(source, address, res.items, extra);
    }

    /**
     * Loads a sub-tab's catalog off-thread (see the event protocol above). source: "github" (arg "" = browse,
     * "q:<text>" = search), "orion", "fdroid-repos" (the known-repo directory) or "fdroid-repo" (arg = address).
     * {@code force} bypasses the on-disk cache.
     */
    private void runStoreSourceCatalog(final String source, final String arg, final boolean force) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if ("github".equals(source)) loadGithubCatalog(arg, force);
                    else if ("orion".equals(source)) loadOrionCatalog(force);
                    else if ("fdroid-repos".equals(source)) {
                        JSONObject res = Stores.fdroidRepos();
                        storeDeliver(source, arg, res.optJSONArray("items"), null);
                    } else if ("fdroid-repo".equals(source)) loadFdroidRepo(arg, force);
                    else throw new IllegalStateException("unknown store source");
                } catch (Throwable e) {
                    storeError(source, arg, e instanceof Exception ? errMsg((Exception) e) : String.valueOf(e.getMessage()));
                }
            }
        });
    }

    /**
     * Resolves a GitHub/Orion/F-Droid catalog item (a direct APK, or a GitHub/Codeberg release picked for
     * this device's ABI) and installs it. Progress -> window.onStoreInstallProgress(json), keyed by the item's key.
     */
    private void runStoreSourceInstall(final String itemJson) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                String key = "";
                try {
                    JSONObject item = new JSONObject(itemJson);
                    String pkg = item.optString("pkg", "");
                    key = item.optString("key", "");
                    if (key.isEmpty()) key = pkg.isEmpty() ? item.optString("id", "") : pkg;
                    String name = item.optString("name", pkg.isEmpty() ? "app" : pkg);
                    boolean direct = "direct".equals(item.optString("resolveKind", ""))
                            && !item.optString("apkUrl", "").isEmpty();
                    if (!direct) storeInstallProgress(key, "resolving", -1, "Finding the latest release of " + name + "…");
                    // A saved GitHub token (Updates tab) lifts the API's anonymous rate limit
                    JSONObject r = Stores.resolve(item, Build.SUPPORTED_ABIS, prefs.getString("github_token", ""));
                    String apkUrl = r.optString("apkUrl", "");
                    String rpkg = r.optString("pkg", pkg);
                    downloadAndInstall(apkUrl, rpkg == null || rpkg.isEmpty() ? pkg : rpkg, name, key, item.optString("sha256", ""));
                } catch (Exception e) {
                    storeInstallProgress(key, "error", 0, errMsg(e));
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // VirusTotal (optional, user-supplied API key) pre-install scan
    // ---------------------------------------------------------------------------------------------

    /**
     * Scans a local APK with VirusTotal. A SHA-256 lookup (private, no upload) when upload=false; a full
     * upload-and-wait when upload=true. Progress and the final result -> window.onVtResult(json).
     */
    private void runVirusTotalScan(final String apiKey, final String path, final boolean upload) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalStateException("Enter your VirusTotal API key first.");
                    if (path == null || path.isEmpty()) throw new IllegalStateException("Load a package first.");
                    File f = new File(path);
                    if (!f.exists()) throw new IllegalStateException("The selected package is no longer available. Pick it again.");
                    notifyUpdates("onVtResult", new JSONObject()
                            .put("stage", upload ? "uploading" : "scanning")
                            .put("message", upload ? "Uploading to VirusTotal… this can take a minute." : "Checking VirusTotal…"));
                    String sha = VirusTotal.sha256(f);
                    JSONObject res = upload
                            ? VirusTotal.uploadAndWait(apiKey.trim(), f, sha, 180000)
                            : VirusTotal.lookup(apiKey.trim(), sha);
                    res.put("stage", "done");
                    res.put("size", f.length());
                    notifyUpdates("onVtResult", res);
                } catch (Exception e) {
                    try {
                        notifyUpdates("onVtResult", new JSONObject().put("stage", "error").put("error", errMsg(e)));
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    /** Runs a command through the Shizuku API, optionally streaming a file into its stdin. */
    private String shizukuStream(String cmd, File stdinFile) throws Exception {
        if (stdinFile == null) return shizukuPipe(cmd, null);
        java.io.FileInputStream in = new java.io.FileInputStream(stdinFile);
        try {
            return shizukuPipe(cmd, in);
        } finally {
            in.close();
        }
    }

    /** Runs a command through the Shizuku API, optionally streaming any InputStream into its stdin. */
    private String shizukuPipe(String cmd, InputStream stdin) throws Exception {
        return shizukuPipe(cmd, stdin, 300000);
    }

    private String shizukuPipe(String cmd, InputStream stdin, int timeoutMs) throws Exception {
        if (shizukuNewProcessMethod == null) {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            shizukuNewProcessMethod = m;
        }
        Process p = (Process) shizukuNewProcessMethod.invoke(null,
                new String[]{"sh", "-c", "exec 2>&1; " + cmd}, null, null);
        OutputStream os = p.getOutputStream();
        if (stdin != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = stdin.read(buf)) > 0) os.write(buf, 0, n);
        }
        os.flush();
        os.close();
        return readProcessWithTimeout(p, timeoutMs);
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

    /** OBB / Android-data files of the XAPK currently loaded in the Installer, and the archive that holds them. */
    private volatile List<XapkInfo.Extra> installExtras = new ArrayList<XapkInfo.Extra>();
    private volatile File installSourceZip = null;

    private static final Object installInspectLock = new Object();

    private static final Object fmBatchLock = new Object();
    private static boolean fmBatchBusy;

    private static String fmBatchLabel(String op) {
        return "rm".equals(op) ? "Deleting" : "cp".equals(op) ? "Copying" : "Moving";
    }

    /** True when a shell answer says the link to the device is gone, so trying item after item is pointless (see FileRules). */
    private static boolean fmTransportLost(String msg) {
        return FileRules.transportLost(msg);
    }

    /** Places a batch delete / move must not touch: the root, any top-level folder, the user's whole storage, the system trees (see FileRules). */
    private static boolean fmProtectedPath(String p) {
        return FileRules.isProtected(p);
    }

    private void clearInstallerWorkDir() {
        installExtras = new ArrayList<XapkInfo.Extra>();
        installSourceZip = null;
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
        XapkInfo.Info xinfo = null;   // set for archives (.apks / .apkm / .xapk)

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
            // XAPK: read the central directory (no extraction) for OBB / Android-data files and manifest.json.
            xinfo = XapkInfo.inspect(raw);
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
            // The archive itself is kept (below) when it carries OBB / data files, so they can be copied
            // after the install; otherwise it is deleted once the package name is known.
            if (sawApkm) type = "apkm";
            if (xinfo.isXapk()) type = "xapk";
            if (splits.isEmpty()) throw new IllegalStateException("no APKs found inside this archive");
            // Base = the split whose manifest has no split="..." attribute.
            for (File f : splits) {
                if (manifestSplitName(f) == null) { base = f; break; }
            }
            if (base == null) base = splits.get(0);
        }

        PackageInfo archive = pm.getPackageArchiveInfo(base.getAbsolutePath(), sigFlags);
        boolean sigUnreadable = false;
        if (archive == null) {
            // Asking for the signature made Android refuse the file (edited after signing, or unsigned). Read it
            // without, so it can still be shown, and installed from Files, where Android gets the last word.
            archive = pm.getPackageArchiveInfo(base.getAbsolutePath(), 0);
            sigUnreadable = archive != null;
        }
        if (archive == null || archive.packageName == null) throw new IllegalStateException("could not read the base APK of this package");
        final String pkg = archive.packageName;

        // Data files that belong to THIS package (a wrong package folder in the archive is dropped). Keep the
        // archive only when there is something to copy from it later.
        List<XapkInfo.Extra> extras = xinfo != null ? xinfo.forPackage(pkg) : new ArrayList<XapkInfo.Extra>();
        if (xinfo != null) {
            if (extras.isEmpty()) {
                raw.delete();
            } else {
                File keep = new File(work, "source.zip");
                installSourceZip = raw.renameTo(keep) ? keep : raw;
            }
        }
        installExtras = extras;

        JSONObject res = new JSONObject();
        res.put("ref", ref);
        res.put("type", type);
        res.put("pkg", pkg);
        JSONArray extraArr = new JSONArray();
        long extrasTotal = 0;
        for (XapkInfo.Extra x : extras) {
            JSONObject o = new JSONObject();
            o.put("name", x.dest.substring(x.dest.lastIndexOf('/') + 1));
            o.put("dest", x.dest);
            o.put("size", x.size);
            extraArr.put(o);
            if (x.size > 0) extrasTotal += x.size;
        }
        res.put("extras", extraArr);
        res.put("extrasTotal", extrasTotal);
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

        // Signer of the archive + comparison to an already-installed copy, for the two signature gates. Both sides
        // are compared with their rotation history, so a rotated key still matches the key it replaced.
        java.util.Set<String> archiveSigners = signerDigests(archive, false);
        java.util.Set<String> archiveLineage = signerDigests(archive, true);
        res.put("signed", !archiveSigners.isEmpty());
        res.put("sigUnreadable", sigUnreadable);
        try {
            PackageInfo cur = pm.getPackageInfo(pkg, sigFlags);
            res.put("installed", true);
            res.put("installedVersionName", cur.versionName != null ? cur.versionName : "");
            res.put("installedVersionCode", versionCodeOf(cur));
            java.util.Set<String> curSigners = signerDigests(cur, true);
            java.util.Set<String> common = new HashSet<String>(curSigners);
            common.retainAll(archiveLineage);
            res.put("signerMatchesInstalled", !curSigners.isEmpty() && !archiveLineage.isEmpty() && !common.isEmpty());
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

    /** ADB (TCP or Wireless Debugging), Shizuku or Root: a backend that runs `pm` with shell or root rights. */
    private static boolean isPrivilegedMode(String mode) {
        return "adb_tcp".equals(mode) || "adb_wireless".equals(mode) || "shizuku".equals(mode) || "root".equals(mode);
    }

    /** The app that provides Shizuku. With Shizuku as the backend, removing it would take the install's own rights away. */
    private static boolean isShizukuApp(String pkg) {
        return SHIZUKU_PKG.equals(pkg) || SHIZUKU_PLUS_PKG.equals(pkg);
    }

    /**
     * True when {@code pkg} is an installed, non-system app other than this one: the kind that can be removed to make room
     * for a differently signed copy. With Shizuku as the backend that excludes Shizuku's own app.
     */
    private boolean canUninstallFirst(String pkg, String mode) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+") || pkg.equals(getPackageName())) return false;
        if ("shizuku".equals(mode) && isShizukuApp(pkg)) return false;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException nf) {
            return false;
        }
    }

    /**
     * Removes the installed copy of {@code pkg}, with all its data, so an APK signed with another key can take its place.
     * Throws with the reason when that can't be done. The caller asked for this explicitly.
     */
    private void uninstallInstalledCopy(String mode, String pkg) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) throw new IllegalStateException("can't tell which app to uninstall first");
        if (pkg.equals(getPackageName())) throw new IllegalStateException("this app can't uninstall itself first");
        if ("shizuku".equals(mode) && isShizukuApp(pkg)) {
            throw new IllegalStateException("Shizuku is doing this install and can't remove its own app. Use ADB or Root for this one.");
        }
        if (!canUninstallFirst(pkg, mode)) {
            try {
                getPackageManager().getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException nf) {
                return;                       // not installed (any more): nothing to remove
            }
            throw new IllegalStateException(pkg + " is a system app, so its signature can't be replaced this way");
        }
        String out = shellVia(mode, "pm uninstall " + pkg);
        if (out == null || !out.contains("Success")) {
            throw new IllegalStateException("could not uninstall the installed copy first: " + (out == null || out.trim().isEmpty() ? "no answer" : out.trim()));
        }
    }

    /**
     * Copies of the files, signed with this app's own key (APK Signature Scheme v2), written next to them in the installer
     * cache. Every split gets the same key, which is what Android needs. The originals are left as they were.
     */
    private List<File> signedCopies(List<File> files) throws Exception {
        long total = 0;
        for (File f : files) total += f.length();
        File dir = files.get(0).getParentFile();
        if (dir.getUsableSpace() < 2 * total + (16L << 20)) throw new IOException("not enough free space in the app cache to sign a copy");
        SigningKey k = SigningKey.getOrCreate();
        List<File> out = new ArrayList<File>();
        for (File f : files) {
            File prepared = new File(f.getParentFile(), "prep_" + f.getName());
            File signed = new File(f.getParentFile(), "signed_" + f.getName());
            try {
                ApkSigner.prepare(f, prepared);
                ApkSigner.signV2(prepared, signed, k.key, k.cert);
            } finally {
                prepared.delete();
            }
            out.add(signed);
        }
        return out;
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

    // ---------------------------------------------------------------------------------------------
    // Android settings (the Settings tab): `settings list|get|put|delete` on the global, secure and system tables, through the
    // active privileged backend. What may be touched and how a command is built is SettingsDb's job (it is tested off the device).
    // ---------------------------------------------------------------------------------------------

    /** One command through a privileged backend with a timeout of its own; the text is the (merged) output, or an "Error: ..." line. */
    private String runPrivilegedShell(String mode, String cmd, int timeoutMs) {
        try {
            if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
                boolean tcp = "adb_tcp".equals(mode);
                if (!tcp && adbWirelessPort <= 0) return "Error: Wireless Debugging is not configured. Connect it in Working Modes.";
                String target = tcp ? tcpTarget() : wirelessTarget();
                String out = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), timeoutMs);
                if (out != null && out.contains("device '") && out.contains("' not found")) {
                    // the link dropped (adbd restarted, say): reconnect once and ask again
                    runProcessWithTimeout(buildAdbProcess("connect", target), 5000);
                    out = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), timeoutMs);
                }
                return out != null ? out : "";
            }
            if ("shizuku".equals(mode)) {
                try {
                    return shizukuPipe(cmd, null, timeoutMs);
                } catch (Exception e) {
                    return runShizukuShell(cmd);                 // the usual path, with its rish fallback
                }
            }
            if ("root".equals(mode)) {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                pb.redirectErrorStream(true);
                return runProcessWithTimeout(pb, timeoutMs);
            }
            return "Error: this needs ADB, Wireless Debugging, Shizuku or Root";
        } catch (Throwable t) {
            return "Error: " + errMsg(t);
        }
    }

    private void settingsReply(String callback, JSONObject res) {
        notifyJs("window." + callback + " && window." + callback + "(" + JSONObject.quote(res.toString()) + ")");
    }

    /** The page gives up on a request after 60 s; one that waited this long behind slower ones is dropped instead of surprising the user later. */
    private static final long SETTINGS_JOB_MAX_WAIT_MS = 55000;

    private static boolean settingsJobStale(long queuedAt) {
        return android.os.SystemClock.elapsedRealtime() - queuedAt > SETTINGS_JOB_MAX_WAIT_MS;
    }

    /** Reads one table and reports it as window.onSettingsList(json): req, ns, ok, entries [[name, value], ...], error, advice, needsPrivilege, mode, ms. */
    private void settingsListJob(int req, String ns, long queuedAt) {
        JSONObject res = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            res.put("req", req);
            res.put("ns", ns);
            if (settingsJobStale(queuedAt)) {
                res.put("ok", false);
                res.put("stale", true);
                res.put("error", "Skipped: it waited too long behind other requests");
                settingsReply("onSettingsList", res);
                return;
            }
            String mode = resolveExecMode();
            res.put("mode", mode);
            if ("standard".equals(mode)) {
                res.put("ok", false);
                res.put("needsPrivilege", true);
                res.put("error", "Needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                String out = runPrivilegedShell(mode, SettingsDb.listCommand(ns), 25000);
                SettingsDb.ListResult lr = SettingsDb.readList(out);
                String text = lr.text == null ? "" : lr.text.trim();
                if (!lr.complete) {
                    // the closing marker never came: the connection dropped or the command timed out, and what did arrive is only a part of the table
                    res.put("ok", false);
                    res.put("error", text.isEmpty() ? "Android returned no settings"
                            : text.contains("[Process timed out") ? "Android took too long to answer, so the list is incomplete"
                            : SettingsDb.looksLikeFailure(text) ? SettingsDb.summary(text)
                            : "The list was cut short on the way (the connection may have dropped)");
                    res.put("advice", SettingsDb.advice(text));
                } else if (lr.entries.isEmpty() && SettingsDb.looksLikeFailure(text)) {
                    res.put("ok", false);
                    res.put("error", SettingsDb.summary(text));
                    res.put("advice", SettingsDb.advice(text));
                } else {
                    JSONArray arr = new JSONArray();
                    for (String[] e : lr.entries) arr.put(new JSONArray().put(e[0]).put(e[1]));
                    res.put("ok", true);
                    res.put("entries", arr);
                }
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("error", errMsg(t));
            } catch (Exception ignored) {}
        }
        try { res.put("ms", System.currentTimeMillis() - t0); } catch (Exception ignored) {}
        settingsReply("onSettingsList", res);
    }

    /**
     * Reads, sets or deletes one setting and reports it as window.onSettingsOp(json): req, op, ns, key, ok, value (what the key
     * holds now, "null" when it is not there), requested, deleted, answer, error, advice, needsPrivilege, unknown (the phone
     * stopped answering, so what became of the change is not known), mode. Whether it worked is decided by the read-back (and,
     * where the word null cannot vouch for itself, by Android's own error text, exit status and a listing), not by silence.
     */
    private void settingsOpJob(int req, String op, String ns, String key, String value, long queuedAt) {
        JSONObject res = new JSONObject();
        try {
            res.put("req", req);
            res.put("op", op);
            res.put("ns", ns);
            res.put("key", key);
            if (value != null) res.put("requested", value);
            if (settingsJobStale(queuedAt)) {
                res.put("ok", false);
                res.put("stale", true);
                res.put("error", "Skipped: it waited too long behind other requests");
                settingsReply("onSettingsOp", res);
                return;
            }
            String mode = resolveExecMode();
            res.put("mode", mode);
            if ("standard".equals(mode)) {
                res.put("ok", false);
                res.put("needsPrivilege", true);
                res.put("error", "Needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                String out = runPrivilegedShell(mode, SettingsDb.writeScript(op, ns, key, value), 20000);
                SettingsDb.WriteResult w = SettingsDb.parseWrite(out, key);
                SettingsDb.Verdict verdict = SettingsDb.judge(op, value, w);
                res.put("ok", verdict.ok);
                if (verdict.unknown) res.put("unknown", true);
                if (w.value != null) res.put("value", w.value);
                if (w.deleted >= 0) res.put("deleted", w.deleted);
                String said = !w.answer.isEmpty() ? w.answer : w.readError;
                if (!said.isEmpty()) res.put("answer", said.length() > 600 ? said.substring(0, 600) + "…" : said);
                if (!verdict.ok) {
                    res.put("error", verdict.error);
                    res.put("advice", SettingsDb.advice(said));
                }
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("error", errMsg(t));
            } catch (Exception ignored) {}
        }
        settingsReply("onSettingsOp", res);
    }

    // ---------------------------------------------------------------------------------------------
    // Overlays tab: `cmd overlay list|enable|disable`, the Material You theme setting, and the colours the system uses now. What may
    // be touched and how a command is built is OverlayRules' job (tested off the device). They share the Settings tab's single
    // thread, so a theme change and a settings change reach the phone in the order they were made.
    // ---------------------------------------------------------------------------------------------

    private static JSONArray overlaysToJson(List<OverlayRules.Overlay> all) {
        JSONArray arr = new JSONArray();
        for (OverlayRules.Overlay o : all) arr.put(new JSONArray().put(o.id).put(o.target).put(o.state));
        return arr;
    }

    private static String clipAnswer(String text) {
        return text.length() > 600 ? text.substring(0, 600) + "…" : text;
    }

    /** Reads the overlays and reports them as window.onOverlayList(json): req, ok, list [[id, target, state], ...], error, advice, needsPrivilege, mode, ms. */
    private void overlayListJob(int req, long queuedAt) {
        JSONObject res = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            res.put("req", req);
            if (settingsJobStale(queuedAt)) {
                res.put("ok", false);
                res.put("stale", true);
                res.put("error", "Skipped: it waited too long behind other requests");
                settingsReply("onOverlayList", res);
                return;
            }
            String mode = resolveExecMode();
            res.put("mode", mode);
            if ("standard".equals(mode)) {
                res.put("ok", false);
                res.put("needsPrivilege", true);
                res.put("error", "Needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                String out = runPrivilegedShell(mode, OverlayRules.listScript(), 25000);
                OverlayRules.ListResult lr = OverlayRules.readList(out);
                String text = lr.text == null ? "" : lr.text.trim();
                if (!lr.complete) {
                    res.put("ok", false);
                    res.put("error", text.isEmpty() ? "Android returned no overlays"
                            : text.contains("[Process timed out") ? "Android took too long to answer, so the list is incomplete"
                            : OverlayRules.looksLikeFailure(text) ? OverlayRules.summary(text)
                            : "The list was cut short on the way (the connection may have dropped)");
                    res.put("advice", OverlayRules.advice(text));
                } else if (lr.overlays.isEmpty() && OverlayRules.looksLikeFailure(text)) {
                    res.put("ok", false);
                    res.put("error", OverlayRules.summary(text));
                    res.put("advice", OverlayRules.advice(text));
                } else {
                    res.put("ok", true);
                    res.put("list", overlaysToJson(lr.overlays));
                }
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("error", errMsg(t));
            } catch (Exception ignored) {}
        }
        try { res.put("ms", System.currentTimeMillis() - t0); } catch (Exception ignored) {}
        settingsReply("onOverlayList", res);
    }

    /**
     * Switches one overlay on or off and reports it as window.onOverlayOp(json): req, op, id, ok, state (1 on, 0 off, -1 cannot be
     * switched, -2 not listed), list (every overlay as it is now), answer, error, advice, needsPrivilege, unknown, mode.
     * Whether it worked is decided by the list that follows the change, not by the command's silence.
     */
    private void overlayOpJob(int req, String op, String id, long queuedAt) {
        JSONObject res = new JSONObject();
        try {
            res.put("req", req);
            res.put("op", op);
            res.put("id", id);
            if (settingsJobStale(queuedAt)) {
                res.put("ok", false);
                res.put("stale", true);
                res.put("error", "Skipped: it waited too long behind other requests");
                settingsReply("onOverlayOp", res);
                return;
            }
            String mode = resolveExecMode();
            res.put("mode", mode);
            if ("standard".equals(mode)) {
                res.put("ok", false);
                res.put("needsPrivilege", true);
                res.put("error", "Needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                String out = runPrivilegedShell(mode, OverlayRules.changeScript(op, id), 30000);
                OverlayRules.ChangeResult cr = OverlayRules.parseChange(out);
                OverlayRules.Verdict v = OverlayRules.judge(op, id, cr);
                res.put("ok", v.ok);
                res.put("state", v.state);
                if (v.unknown) res.put("unknown", true);
                // an empty list after a change is not "this phone has no overlays": the page keeps the list it has and looks again
                if (cr.all != null && !cr.all.isEmpty()) res.put("list", overlaysToJson(cr.all));
                if (!cr.answer.isEmpty()) res.put("answer", clipAnswer(cr.answer));
                if (!v.ok) {
                    res.put("error", v.error);
                    res.put("advice", OverlayRules.advice(cr.answer + "\n" + cr.listError));
                }
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("error", errMsg(t));
            } catch (Exception ignored) {}
        }
        settingsReply("onOverlayOp", res);
    }

    private void saveFlagSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) prefs.edit().remove("ovl_flag_snap").apply();
        else prefs.edit().putString("ovl_flag_snap", snapshot).apply();
    }

    /**
     * Writes the Material You theme setting (and Samsung's wallpaper-colours switch where the phone has one) and reports it as
     * window.onThemeOp(json): req, kind, ok, value (what the setting holds now, "null" when it is not there), before (what it held
     * when this change began; apply only), flags (the settings tables whose switch now holds what was wanted), warning (a switch
     * that would not change), flagsRestored, answer, error, advice, needsPrivilege, unknown, mode.
     * What a change does, step by step, is OverlayRules.runTheme's job (tested off the device).
     */
    private void themeJob(int req, String kind, String source, String hex, String style, String raw, long queuedAt) {
        JSONObject res = new JSONObject();
        try {
            res.put("req", req);
            res.put("kind", kind);
            if (settingsJobStale(queuedAt)) {
                res.put("ok", false);
                res.put("stale", true);
                res.put("error", "Skipped: it waited too long behind other requests");
                settingsReply("onThemeOp", res);
                return;
            }
            final String mode = resolveExecMode();
            res.put("mode", mode);
            if ("standard".equals(mode)) {
                res.put("ok", false);
                res.put("needsPrivilege", true);
                res.put("error", "Needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                OverlayRules.ThemeOutcome oc = OverlayRules.runTheme(new OverlayRules.ThemeEnv() {
                    @Override
                    public String run(String script, int timeoutMs) {
                        return runPrivilegedShell(mode, script, timeoutMs);
                    }

                    @Override
                    public String snapshot() {
                        return prefs.getString("ovl_flag_snap", "");
                    }

                    @Override
                    public void saveSnapshot(String snapshot) {
                        saveFlagSnapshot(snapshot);
                    }

                    @Override
                    public long now() {
                        return System.currentTimeMillis();
                    }
                }, kind, source, hex, style, raw);
                res.put("ok", oc.ok);
                if (oc.unknown) res.put("unknown", true);
                if (oc.value != null) res.put("value", oc.value);
                if (oc.before != null) res.put("before", oc.before);
                JSONArray flags = new JSONArray();
                for (String f : oc.flags) flags.put(f);
                res.put("flags", flags);
                if (oc.flagsRestored) res.put("flagsRestored", true);
                if (!oc.answer.isEmpty()) res.put("answer", clipAnswer(oc.answer));
                if (!oc.warning.isEmpty()) res.put("warning", oc.warning);
                if (!oc.ok) {
                    res.put("error", oc.error);
                    res.put("advice", oc.advice);
                }
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("error", errMsg(t));
            } catch (Exception ignored) {}
        }
        settingsReply("onThemeOp", res);
    }

    /** The five tonal palettes the system builds from its theme colour (Android 12+): 13 steps each, light to dark. */
    private String systemPaletteJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("sdk", Build.VERSION.SDK_INT);
            if (Build.VERSION.SDK_INT < 31) {
                o.put("ok", false);
                o.put("error", "Material You colors need Android 12 or newer (this phone runs Android " + Build.VERSION.RELEASE + ").");
                return o.toString();
            }
            int[] tones = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
            String[] names = {"accent1", "accent2", "accent3", "neutral1", "neutral2"};
            JSONArray t = new JSONArray();
            for (int tone : tones) t.put(tone);
            o.put("tones", t);
            for (String n : names) {
                JSONArray a = new JSONArray();
                for (int tone : tones) a.put(colorHex(systemColor("system_" + n + "_" + tone)));
                o.put(n, a);
            }
            o.put("ok", true);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("error", "The system colors could not be read: " + errMsg(t));
            } catch (Exception ignored) {}
        }
        return o.toString();
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

    /** Post-install OBB/data copy, dexopt and auto-delete of the source, then reports the outcome to the WebView. */
    private void finishInstall(final JSONObject res, final JSONObject opts, final String mode) {
        // The no-privilege install result arrives on the main thread; copying GB-sized OBB files there would ANR.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executor.submit(new Runnable() {
                @Override
                public void run() { finishInstallImpl(res, opts, mode); }
            });
            return;
        }
        finishInstallImpl(res, opts, mode);
    }

    private void notifyInstallProgress(String msg) {
        notifyJs("window.onInstallProgress && window.onInstallProgress(" + JSONObject.quote(msg) + ")");
    }

    /** Extracts one archive entry to a temp file in the installer cache. */
    private File extractExtraToTemp(java.util.zip.ZipFile zf, java.util.zip.ZipEntry e) throws Exception {
        File tmp = new File(installerWorkDir(), "extra.tmp");
        InputStream in = zf.getInputStream(e);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
        return tmp;
    }

    /** Writes one data file to {@code dest} (an absolute path on shared storage). Returns null on success, else why not. */
    private String writeInstallExtra(String mode, java.util.zip.ZipFile zf, java.util.zip.ZipEntry e, String dir, String dest) throws Exception {
        if ("shizuku".equals(mode)) {
            // Streams straight from the archive through Shizuku's shell: no temp copy of a multi-GB file.
            InputStream in = zf.getInputStream(e);
            try {
                String out = shizukuPipe("mkdir -p " + BackupScripts.quote(dir) + " && cat > " + BackupScripts.quote(dest) + " && echo COPY_OK", in);
                return out != null && out.contains("COPY_OK") ? null : (out == null || out.trim().isEmpty() ? "copy failed" : out.trim());
            } finally { in.close(); }
        }
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            File tmp = extractExtraToTemp(zf, e);
            try {
                boolean tcp = "adb_tcp".equals(mode);
                String target = tcp ? tcpTarget() : wirelessTarget();
                if (!isAdbTargetConnected(target)) performConnect(tcp ? adbTcpHost : adbWirelessHost, tcp ? adbTcpPort : adbWirelessPort, null);
                shellVia(mode, "mkdir -p " + BackupScripts.quote(dir));
                String out = runProcessWithTimeout(buildAdbProcess("-s", target, "push", tmp.getAbsolutePath(), dest), 3600000);
                return out != null && out.toLowerCase(java.util.Locale.US).contains("pushed") ? null
                        : (out == null || out.trim().isEmpty() ? "push failed" : out.trim());
            } finally { tmp.delete(); }
        }
        if ("root".equals(mode)) {
            File tmp = extractExtraToTemp(zf, e);
            try {
                tmp.setReadable(true, false);
                ProcessBuilder pb = new ProcessBuilder("su", "-c", "mkdir -p " + BackupScripts.quote(dir)
                        + " && cp " + BackupScripts.quote(tmp.getAbsolutePath()) + " " + BackupScripts.quote(dest) + " && echo COPY_OK");
                pb.redirectErrorStream(true);
                String out = runProcessWithTimeout(pb, 3600000);
                return out != null && out.contains("COPY_OK") ? null : (out == null || out.trim().isEmpty() ? "copy failed" : out.trim());
            } finally { tmp.delete(); }
        }
        // No privilege: only works where this app may write there itself (older Android, or open storage).
        File d = new File(dir);
        if (!d.isDirectory() && !d.mkdirs()) {
            return "Android doesn't let this app create that folder without ADB, Shizuku or Root. Copy it by hand to " + dest;
        }
        InputStream in = zf.getInputStream(e);
        try {
            FileOutputStream out = new FileOutputStream(new File(dest));
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } catch (java.io.IOException io) {
            return "can't write there without ADB, Shizuku or Root (" + io.getMessage() + ")";
        } finally { in.close(); }
        return null;
    }

    /** Copies the XAPK's OBB / Android-data files into place after a successful install. Returns a summary. */
    private String copyInstallExtras(String mode) {
        final List<XapkInfo.Extra> extras = installExtras;
        final File zip = installSourceZip;
        if (extras == null || extras.isEmpty() || zip == null || !zip.exists()) return "";
        String root = fmCanonicalPath("/sdcard");
        StringBuilder problems = new StringBuilder();
        int done = 0;
        long bytes = 0;
        String lastDir = "";
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(zip);
            for (int i = 0; i < extras.size(); i++) {
                XapkInfo.Extra x = extras.get(i);
                String shortName = x.dest.substring(x.dest.lastIndexOf('/') + 1);
                java.util.zip.ZipEntry e = zf.getEntry(x.entry);
                if (e == null) { problems.append(shortName).append(": missing from the archive\n"); continue; }
                notifyInstallProgress("Copying data file " + (i + 1) + " of " + extras.size() + ": " + shortName + " (" + XapkInfo.humanBytes(x.size) + ")…");
                String dest = root + "/" + x.dest;
                String dir = dest.substring(0, dest.lastIndexOf('/'));
                String err;
                try {
                    err = writeInstallExtra(mode, zf, e, dir, dest);
                } catch (Exception ex) {
                    err = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                }
                if (err == null) { done++; if (x.size > 0) bytes += x.size; lastDir = x.dest.substring(0, x.dest.lastIndexOf('/')); }
                else problems.append(shortName).append(": ").append(err).append('\n');
            }
        } catch (Exception ex) {
            problems.append("Error: ").append(ex.getMessage()).append('\n');
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        String head = done + " of " + extras.size() + " data file" + (extras.size() == 1 ? "" : "s") + " copied"
                + (done > 0 ? " (" + XapkInfo.humanBytes(bytes) + ") to " + lastDir + "/" : "");
        return problems.length() == 0 ? head : head + "\n" + problems.toString().trim();
    }

    private void finishInstallImpl(JSONObject res, JSONObject opts, String mode) {
        try {
            boolean success = res.optBoolean("ok", false);
            if (success && opts.optBoolean("copyExtras", false) && installExtras != null && !installExtras.isEmpty()) {
                try {
                    res.put("extras", copyInstallExtras(mode));
                } catch (Exception e) {
                    try { res.put("extras", "Error: " + e.getMessage()); } catch (Exception ignored) {}
                }
            }
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
                // What was done to the phone along the way; it leads the report, also when a later step fails.
                StringBuilder notes = new StringBuilder();
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
                            java.util.Set<String> archiveLineage = archive != null ? signerDigests(archive, true) : new HashSet<String>();
                            if (!curSigners.isEmpty() && !archiveLineage.isEmpty()) {
                                java.util.Set<String> common = new HashSet<String>(curSigners);
                                common.retainAll(archiveLineage);
                                if (common.isEmpty()) {
                                    throw new IllegalStateException(archive.packageName + " is already installed with a different signing key (blocked by \"block signature mismatch\")");
                                }
                            }
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }

                    // From the file manager an install always runs through a privileged backend (ADB, Wireless
                    // Debugging, Shizuku or Root), never the system installer.
                    boolean viaFiles = opts.optBoolean("fromFiles", false);
                    String mode = resolveAuthorizerMode(opts.optString("authorizer", ""));
                    if (viaFiles && !isPrivilegedMode(mode)) {
                        throw new IllegalStateException("installing from Files needs ADB, Wireless Debugging, Shizuku or Root - set one up in Working Modes and try again");
                    }
                    if ("none".equals(mode)) {
                        installNoPrivilege(files, opts); // result arrives via the PackageInstaller receiver
                        return;
                    }
                    String flags = opts.optString("createFlags", "-r");
                    // The app's name, read without its signature so it is known even when that is broken.
                    PackageInfo named = pm.getPackageArchiveInfo(gateApk.getAbsolutePath(), 0);
                    String pkgName = named != null ? named.packageName : null;
                    res.put("pkg", pkgName == null ? "" : pkgName);
                    List<File> preSigned = null;
                    if (viaFiles && opts.optBoolean("uninstallFirst", false)) {
                        // Whatever can fail without touching the phone is done before the installed copy (and its data) goes:
                        // an APK Android can't read a signature from will need the signed copy, so make it now.
                        if (archiveSigners.isEmpty()) {
                            notifyInstallProgress("Signing a copy with this app's key...");
                            preSigned = signedCopies(files);
                        }
                        notifyInstallProgress("Uninstalling the installed copy first...");
                        uninstallInstalledCopy(mode, pkgName);
                        notes.append("The installed copy was uninstalled first.\n");
                    }
                    String out = installApksOptions(files, flags, mode);
                    if (viaFiles && !InstallHints.success(out) && InstallHints.brokenSignature(out)) {
                        // Android won't take the signature as it is (edited after signing, or unsigned). Sign a copy
                        // with this app's own key and try once more; the original file is not touched.
                        notifyInstallProgress("Android couldn't verify the signature - signing a copy with this app's key...");
                        String again = installApksOptions(preSigned != null ? preSigned : signedCopies(files), flags, mode);
                        if (InstallHints.success(again)) {
                            notes.append("Android could not verify the APK's signature, so a copy was signed with this app's key and installed.\n");
                            out = again;
                        } else {
                            out = out.trim() + "\n\nAfter signing a copy with this app's key:\n" + (again == null ? "" : again.trim());
                        }
                    }
                    boolean ok = InstallHints.success(out);
                    String advice = ok ? "" : InstallHints.advice(out);
                    String text = notes + (out != null ? out.trim() : "") + (advice.isEmpty() ? "" : "\n\nNote: " + advice);
                    res.put("method", mode);
                    res.put("output", text);
                    if (!ok && viaFiles && !opts.optBoolean("uninstallFirst", false)
                            && InstallHints.updateIncompatible(out) && canUninstallFirst(pkgName, mode)) {
                        // The page may ask for the installed copy to be removed and this to run again, so the
                        // staged files stay until it decides.
                        res.put("ok", false);
                        res.put("retry", "uninstall");
                        notifyJs("window.onInstallResult && window.onInstallResult(" + JSONObject.quote(res.toString()) + ")");
                        return;
                    }
                    res.put("ok", ok);
                    finishInstall(res, opts, mode);
                } catch (Exception e) {
                    try {
                        res.put("ok", false);
                        res.put("method", "none");
                        res.put("output", notes + "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    } catch (Exception ignored) {}
                    notifyJs("window.onInstallResult && window.onInstallResult(" + JSONObject.quote(res.toString()) + ")");
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Archive browser: File manager -> View on an .apk / .zip / package file opens its contents as a folder
    // tree without extracting. ZipTool does the zip work; this part resolves the path, picks the route for
    // files only a privileged shell can reach, and reports long jobs to the page.
    // ---------------------------------------------------------------------------------------------

    private static final int ARCHIVE_PAGE = 400;
    private static final int ARCHIVE_TEXT_LIMIT = 262144;            // preview / edit limit for text
    private static final int ARCHIVE_IMAGE_LIMIT = 4 * 1024 * 1024;
    private static final int ARCHIVE_AXML_LIMIT = 8 * 1024 * 1024;
    private static final String ARCHIVE_STAGE_DIR = "/data/local/tmp";
    private static final String ARCHIVE_STAGE_PREFIX = "fm_archive_";
    private static final long ARCHIVE_STAGE_MAX = 2L << 30;          // largest entry copied out to install / open
    private static final String[] ARCHIVE_NO_EDIT = {"/system/", "/product/", "/vendor/", "/system_ext/", "/apex/", "/odm/", "/data/app/"};

    private static final Object archiveLock = new Object();
    private static boolean archiveBusy;        // an extract / edit / stage / compare / sign job is running (process-wide, so a re-created Activity can't start a second one)
    private static boolean stageSwept;         // leftover shell-staged copies from an earlier run were removed

    private static final class ArcSlot {
        final ZipTool.Archive archive;
        final String stagedPath;     // the shell-staged copy the archive was read from, or null when the real file is read directly
        final String stamp;          // size:mtime of the real file when it was staged (to notice it changing underneath an edit)

        ArcSlot(ZipTool.Archive archive, String stagedPath, String stamp) {
            this.archive = archive;
            this.stagedPath = stagedPath;
            this.stamp = stamp;
        }
    }

    private static final java.util.ArrayList<String> archiveEvictedStages = new java.util.ArrayList<String>();

    /** The last two archives used (the open one and the one it is compared with / nested inside), by canonical path. */
    private static final java.util.LinkedHashMap<String, ArcSlot> archiveSlots = new java.util.LinkedHashMap<String, ArcSlot>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ArcSlot> eldest) {
            if (size() <= 2) return false;
            if (eldest.getValue().stagedPath != null) archiveEvictedStages.add(eldest.getValue().stagedPath);
            return true;
        }
    };

    private File archiveNestedDir() {
        return new File(getCacheDir(), "archive_nested");
    }

    private File archiveStageDirLocal() {
        return new File(getCacheDir(), "archive_stage");
    }

    private static void deleteContents(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) deleteContents(k);
            k.delete();
        }
    }

    private boolean archiveStaged(String canonicalPath) {
        synchronized (archiveLock) {
            ArcSlot s = archiveSlots.get(canonicalPath);
            return s != null && s.stagedPath != null;
        }
    }

    /** The archive the page named: the file itself when this app can read it, else a copy staged through the shell. */
    private ZipTool.Archive archiveFor(String path, boolean fresh) throws Exception {
        String p = fmCanonicalPath(path);
        synchronized (archiveLock) {
            ArcSlot hit = fresh ? null : archiveSlots.get(p);
            if (hit != null && !hit.archive.isStale()) return hit.archive;
            File f = new File(p);
            File use = f;
            String stagedPath = null, stamp = null;
            ArcSlot old = archiveSlots.get(p);
            if (!(f.isFile() && f.canRead())) {
                String[] st = stageArchive(p);
                stagedPath = st[0];
                stamp = st[1];
                use = new File(stagedPath);
            }
            ZipTool.Archive a;
            try {
                a = ZipTool.open(use);
            } catch (IOException | RuntimeException bad) {
                if (stagedPath != null) deleteStagedFiles(java.util.Collections.singletonList(stagedPath));
                throw bad;
            }
            archiveSlots.put(p, new ArcSlot(a, stagedPath, stamp));
            java.util.ArrayList<String> gone = new java.util.ArrayList<String>(archiveEvictedStages);
            archiveEvictedStages.clear();
            if (old != null && old.stagedPath != null && !old.stagedPath.equals(stagedPath)) gone.add(old.stagedPath);
            if (!gone.isEmpty()) deleteStagedFiles(gone);
            return a;
        }
    }

    /** Forgets an archive that changed on disk, and removes the shell-staged copy it was read from. */
    private void dropSlot(String canonicalPath) {
        String staged = null;
        synchronized (archiveLock) {
            ArcSlot slot = archiveSlots.remove(canonicalPath);
            if (slot != null) staged = slot.stagedPath;
        }
        if (staged != null) deleteStagedFiles(java.util.Collections.singletonList(staged));
    }

    /** A new name for each staging, so removing an old copy can never take a newer one with it. */
    private static String newStagePath(String p) {
        return ARCHIVE_STAGE_DIR + "/" + ARCHIVE_STAGE_PREFIX + Long.toHexString(System.nanoTime()) + "_" + Integer.toHexString(p.hashCode()) + ".zip";
    }

    /** "size:mtime" of a file as the shell sees it, or null when it can't be read. */
    private String statStamp(String mode, String p) {
        try {
            String out = shellVia(mode, "stat -c %s:%Y " + BackupScripts.quote(p) + " 2>&1");
            for (String line : out.split("\n")) if (line.trim().matches("[0-9]+:[0-9]+")) return line.trim();
        } catch (Exception ignored) {}
        return null;
    }

    /** Throws when a staged archive's real file changed after it was staged, so an edit can't overwrite those changes. */
    private void requireUnchanged(String p) throws Exception {
        ArcSlot slot;
        synchronized (archiveLock) {
            slot = archiveSlots.get(p);
        }
        if (slot == null || slot.stagedPath == null || slot.stamp == null) return;
        String now = statStamp(resolveExecMode(), p);
        if (now != null && !now.equals(slot.stamp)) {
            throw new IOException("This file changed after it was opened. Close it and open it again, then redo the edit.");
        }
    }

    private String[] stageArchive(String p) throws Exception {
        String mode = resolveExecMode();
        if ("standard".equals(mode)) {
            throw new IOException("Can't read this file. For storage, grant All-files access; for system folders, set up ADB, Shizuku or Root.");
        }
        String sz = shellVia(mode, "stat -c %s " + BackupScripts.quote(p) + " 2>&1");
        long size = -1;
        try { size = Long.parseLong(sz.trim()); } catch (Exception ignored) {}
        if (size > (3L << 30)) {
            throw new IOException("This file is " + XapkInfo.humanBytes(size) + ", too large to open through the shell. Copy it to storage first.");
        }
        String stage = newStagePath(p);
        String sweep = "";
        synchronized (archiveLock) {
            if (!stageSwept) {          // the first staging of this run: copies a killed earlier run left behind go first
                stageSwept = true;
                sweep = "rm -f " + ARCHIVE_STAGE_DIR + "/" + ARCHIVE_STAGE_PREFIX + "*.zip " + ARCHIVE_STAGE_DIR + "/fm_archive.zip; ";     // the second is v5.7's single fixed name
            }
        }
        String out = shellVia(mode, sweep + "cp " + BackupScripts.quote(p) + " " + stage + " && chmod 644 " + stage + " && stat -c %s:%Y " + BackupScripts.quote(p) + " && echo OK");
        if (out == null || !out.contains("OK")) {
            deleteStagedFiles(java.util.Collections.singletonList(stage));
            throw new IOException(out == null || out.trim().isEmpty() ? "copy failed" : out.trim());
        }
        String stamp = null;
        for (String line : out.split("\n")) if (line.trim().matches("[0-9]+:[0-9]+")) stamp = line.trim();
        File f = new File(stage);
        if (!f.canRead()) {
            deleteStagedFiles(java.util.Collections.singletonList(stage));
            throw new IOException("The shell copied the file, but this app can't read " + stage);
        }
        return new String[]{stage, stamp};
    }

    /** Null when the archive can be edited and written back, else why not. */
    private String archiveEditBlock(String p) {
        String canon = p;
        try {
            canon = new File(p).getCanonicalPath();           // resolves ".." and symlinks, so a path can't sidestep the list below
        } catch (IOException ignored) {}
        for (String s : ARCHIVE_NO_EDIT) {
            if (p.startsWith(s) || canon.startsWith(s)) return "Installed and system packages can't be edited in place. Copy it to storage first.";
        }
        File f = new File(p);
        File dir = f.getParentFile();
        if (f.canWrite() && dir != null && dir.canWrite()) return null;
        if ("standard".equals(resolveExecMode())) return "Editing needs All-files access for storage, or ADB / Shizuku / Root for other folders.";
        return null;
    }

    private static String archiveFail(Throwable t) {
        return archiveFail(errMsg(t));
    }

    private static String archiveFail(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** Copies a file this app can read to dest through the best route: its own access, else the active privileged mode. */
    private String writeFileTo(File src, String dest) {
        try {
            File d = new File(dest);
            File dir = d.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs()) && dir.canWrite()) {
                File tmp = new File(dir, "." + d.getName() + ".copy-tmp");
                try {
                    copyFile(src, tmp);
                } catch (IOException ioe) {
                    tmp.delete();                       // no half-written hidden file left behind
                    throw ioe;
                }
                if (!tmp.renameTo(d)) {
                    tmp.delete();
                    return "Couldn't replace " + dest;
                }
                return null;
            }
        } catch (IOException ioe) {
            String why = ioe.getMessage() == null ? "" : ioe.getMessage();
            if (why.contains("ENOSPC") || why.toLowerCase(java.util.Locale.US).contains("no space left")) {
                return "There is not enough free space to write " + dest;       // not a permission problem: say so
            }
            // otherwise fall through to the privileged route
        }
        String mode = resolveExecMode();
        if ("standard".equals(mode)) {
            return "This app can't write there. Grant All-files access, or set up ADB, Shizuku or Root.";
        }
        try {
            return writeFileViaMode(mode, src, dest);
        } catch (Exception e) {
            return errMsg(e);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /** Writes src to dest through ADB / Shizuku / Root, via a temp name so an interrupted copy never corrupts dest. */
    private String writeFileViaMode(String mode, File src, String dest) throws Exception {
        String dir = dest.substring(0, dest.lastIndexOf('/'));
        String tmpDest = dest + ".copy-tmp";
        String qd = BackupScripts.quote(dest), qt = BackupScripts.quote(tmpDest);
        if ("shizuku".equals(mode)) {
            InputStream in = new java.io.FileInputStream(src);
            try {
                String out = shizukuPipe("{ mkdir -p " + BackupScripts.quote(dir) + " && cat > " + qt + " && mv -f " + qt + " " + qd + " && echo COPY_OK; } || rm -f " + qt, in);
                return out != null && out.contains("COPY_OK") ? null : (out == null || out.trim().isEmpty() ? "copy failed" : out.trim());
            } finally { in.close(); }
        }
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) {
            boolean tcp = "adb_tcp".equals(mode);
            String target = tcp ? tcpTarget() : wirelessTarget();
            if (!isAdbTargetConnected(target)) performConnect(tcp ? adbTcpHost : adbWirelessHost, tcp ? adbTcpPort : adbWirelessPort, null);
            shellVia(mode, "mkdir -p " + BackupScripts.quote(dir));
            String out = runProcessWithTimeout(buildAdbProcess("-s", target, "push", src.getAbsolutePath(), tmpDest), 3600000);
            if (out == null || !out.toLowerCase(java.util.Locale.US).contains("pushed")) {
                try { shellVia(mode, "rm -f " + qt); } catch (Exception ignored) {}
                return out == null || out.trim().isEmpty() ? "push failed" : out.trim();
            }
            String mv = shellVia(mode, "mv -f " + qt + " " + qd + " && echo COPY_OK || rm -f " + qt);
            return mv != null && mv.contains("COPY_OK") ? null : (mv == null || mv.trim().isEmpty() ? "move failed" : mv.trim());
        }
        if ("root".equals(mode)) {
            src.setReadable(true, false);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "{ mkdir -p " + BackupScripts.quote(dir) + " && cp " + BackupScripts.quote(src.getAbsolutePath())
                    + " " + qt + " && mv -f " + qt + " " + qd + " && echo COPY_OK; } || rm -f " + qt);
            pb.redirectErrorStream(true);
            String out = runProcessWithTimeout(pb, 3600000);
            return out != null && out.contains("COPY_OK") ? null : (out == null || out.trim().isEmpty() ? "copy failed" : out.trim());
        }
        return "needs ADB, Shizuku or Root";
    }

    private static JSONObject signingKeyJson(SigningKey k) throws Exception {
        JSONObject o = new JSONObject().put("ok", true).put("exists", k != null);
        if (k != null) {
            o.put("sha256", k.sha256()).put("subject", k.subject()).put("created", k.created).put("hardware", k.hardware).put("bits", k.bits);
        }
        return o;
    }

    /** Where "save a signed copy" goes: next to the file, or Downloads when that folder is not one we may write to. */
    private static String signedCopyPath(String p) {
        File f = new File(p);
        String name = FileRules.signedName(f.getName());       // app.apk -> app-signed.apk; never the file it was made from
        String dir = f.getParent();
        boolean blocked = dir == null;
        for (String s : ARCHIVE_NO_EDIT) if (p.startsWith(s)) blocked = true;
        if (blocked) dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        return dir + "/" + name;
    }

    private static final long SIGN_CHECK_MAX = 1536L << 20;       // larger APKs skip the (slow) signature read-back

    /** Refuses to extract a file onto the archive it is being read from (a staged archive's real path included). */
    private void requireNotArchive(String archivePath, File target) throws IOException {
        if (target.getCanonicalPath().equals(new File(fmCanonicalPath(archivePath)).getCanonicalPath())) {
            throw new IOException("That would overwrite the archive itself. Pick another folder.");
        }
    }

    private void notifyArchiveProgress(String msg) {
        notifyJs("window.onArchiveProgress && window.onArchiveProgress(" + JSONObject.quote(msg) + ")");
    }

    private static JSONArray diffJson(List<ZipTool.DiffItem> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (ZipTool.DiffItem i : items) arr.put(new JSONObject().put("p", i.name).put("a", i.sizeA).put("b", i.sizeB));
        return arr;
    }

    /** Removes specific shell-staged archive copies (they live in /data/local/tmp, which this app can't delete from). */
    private void deleteStagedFiles(final java.util.List<String> paths) {
        final StringBuilder cmd = new StringBuilder();
        for (String sp : paths) {
            if (sp != null && sp.startsWith(ARCHIVE_STAGE_DIR + "/" + ARCHIVE_STAGE_PREFIX) && sp.matches("[A-Za-z0-9_./]+")) {
                cmd.append(cmd.length() == 0 ? "rm -f " : " ").append(sp);
            }
        }
        if (cmd.length() == 0) return;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    shellVia(resolveExecMode(), cmd.toString());
                } catch (Throwable ignored) {}
            }
        });
    }

    private static JSONObject archiveEntryJson(ZipTool.Entry e) throws Exception {
        return new JSONObject().put("n", e.baseName()).put("p", e.name).put("d", e.dir).put("s", e.size).put("c", e.csize)
                .put("t", e.mtime()).put("m", e.method).put("e", e.encrypted());
    }

    private static JSONObject archiveChildJson(ZipTool.Child c) throws Exception {
        JSONObject o = new JSONObject().put("n", c.name).put("p", c.path).put("d", c.dir).put("s", c.size).put("c", c.csize)
                .put("t", c.mtime).put("f", c.count);
        if (c.entry != null) o.put("m", c.entry.method).put("e", c.entry.encrypted());
        else o.put("m", -1).put("e", false);
        return o;
    }

    // ---------------------------------------------------------------------------------------------
    // Installer: find package files (.apk / .apks / .apkm / .xapk) on storage
    // ---------------------------------------------------------------------------------------------

    /** Whether this app can read shared storage itself (All-files access on Android 11+, else the storage permission). */
    private boolean hasStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) { return false; }
    }

    private static final String SCAN_FIND_PREDICATE =
            "-type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.apkm' -o -iname '*.xapk' \\)";

    /**
     * Scans storage off-thread and reports to window.onApkScan(json): {status, files[], truncated, ms, fs, shell}.
     * Folders this app can read are walked directly; with a privileged mode the shell also covers the ones it
     * can't (Android/data, Android/obb - or everything when All-files access isn't granted).
     */
    private void runApkScan() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                long t0 = System.currentTimeMillis();
                try {
                    boolean fsAccess = hasStorageAccess();
                    boolean privileged = !"standard".equals(resolveExecMode());
                    res.put("fs", fsAccess);
                    res.put("shell", privileged);
                    if (!fsAccess && !privileged) {
                        res.put("status", "noaccess");
                        notifyJs("window.onApkScan && window.onApkScan(" + JSONObject.quote(res.toString()) + ")");
                        return;
                    }

                    List<ApkScan.Entry> found = new ArrayList<ApkScan.Entry>();
                    Set<String> seen = new HashSet<String>();
                    ApkScan.Limits lim = new ApkScan.Limits();
                    lim.deadlineMs = t0 + 25000;

                    String primary = fmCanonicalPath("/sdcard");
                    List<File> roots = new ArrayList<File>();
                    roots.add(new File(primary));
                    File[] vols = new File("/storage").listFiles();   // SD cards / USB drives
                    if (vols != null) {
                        for (File v : vols) if (v.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) roots.add(v);
                    }

                    if (fsAccess) {
                        for (File r : roots) ApkScan.walk(r, 0, found, seen, lim);
                    }

                    if (privileged) {
                        StringBuilder where = new StringBuilder();
                        if (fsAccess) {
                            where.append(BackupScripts.quote(primary + "/Android/data")).append(' ')
                                 .append(BackupScripts.quote(primary + "/Android/obb"));
                        } else {
                            for (File r : roots) where.append(BackupScripts.quote(r.getAbsolutePath())).append(' ');
                        }
                        AndroidBridge sh = new AndroidBridge();
                        String out = sh.executeShell("find " + where.toString().trim() + " -maxdepth 12 " + SCAN_FIND_PREDICATE
                                + " 2>/dev/null | head -n 2500");
                        List<String> unreadable = new ArrayList<String>();
                        for (String path : ApkScan.parseFindOutput(out, 2500)) {
                            if (found.size() >= lim.maxResults) { lim.hitLimit = true; break; }
                            if (!seen.add(path)) continue;
                            ApkScan.Entry e = new ApkScan.Entry();
                            e.path = path;
                            e.name = path.substring(path.lastIndexOf('/') + 1);
                            e.kind = ApkScan.kindOf(e.name);
                            File f = new File(path);
                            if (f.isFile() && f.canRead()) {
                                e.size = f.length();
                                e.mtime = f.lastModified();
                                e.shell = false;
                            } else {
                                e.shell = true;
                                unreadable.add(path);
                            }
                            found.add(e);
                        }
                        // Size + date of the files only the shell can see, 40 per call
                        Map<String, long[]> stats = new java.util.HashMap<String, long[]>();
                        for (int i = 0; i < unreadable.size(); i += 40) {
                            StringBuilder args = new StringBuilder();
                            for (int j = i; j < Math.min(i + 40, unreadable.size()); j++) {
                                args.append(' ').append(BackupScripts.quote(unreadable.get(j)));
                            }
                            stats.putAll(ApkScan.parseStatOutput(sh.executeShell("stat -c '%s|%Y|%n'" + args + " 2>/dev/null")));
                        }
                        List<ApkScan.Entry> keep = new ArrayList<ApkScan.Entry>();
                        for (ApkScan.Entry e : found) {
                            if (e.shell) {
                                long[] st = stats.get(e.path);
                                if (st != null) { e.size = st[0]; e.mtime = st[1] * 1000L; }
                                if (stats.size() > 0 && st != null && st[0] <= 0) continue; // empty placeholder
                            }
                            keep.add(e);
                        }
                        found = keep;
                    }

                    ApkScan.sort(found);
                    JSONArray files = new JSONArray();
                    for (ApkScan.Entry e : found) files.put(ApkScan.toJson(e));
                    res.put("status", "ok");
                    res.put("files", files);
                    res.put("truncated", lim.hitLimit);
                    res.put("ms", System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    try { res.put("status", "error"); res.put("error", e.getMessage() != null ? e.getMessage() : "scan failed"); } catch (Exception ignored) {}
                }
                notifyJs("window.onApkScan && window.onApkScan(" + JSONObject.quote(res.toString()) + ")");
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

    /**
     * Resolves the primary-storage aliases to the concrete /storage/emulated/0 path. /sdcard and
     * /storage/self/primary are symlinks, and the "self" view resolves differently for an ADB/Shizuku
     * shell (uid 2000) than for the app, so a shell often can't read through them. The concrete
     * /storage/emulated/0 path is readable the same way by both, so browsing storage works regardless
     * of mode. Also collapses duplicate slashes, strips a trailing slash (except root), makes the path absolute and
     * folds "." and ".." (after the aliases, as the shell would follow them), so a typed or composed path such as
     * /storage/emulated/0/../0 can neither reach nor hide a place the protection list names.
     */
    private String fmCanonicalPath(String path) {
        String primary = "/storage/emulated/0";
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && ext.getAbsolutePath() != null && !ext.getAbsolutePath().isEmpty())
                primary = ext.getAbsolutePath();
        } catch (Exception ignored) {}
        return FileRules.canonical(path, primary);
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
                    java.util.Set<String> archiveSigners = signerDigests(archiveInfo, true);

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

    /**
     * SHA-256 digests of the signing certificates. With {@code includeHistory}, a key that was rotated also lists the
     * keys before it, which is what two packages are compared by: an APK signed with the newer key of a rotation may
     * update an install signed with the older one, and the other way round.
     */
    private static java.util.Set<String> signerDigests(PackageInfo pi, boolean includeHistory) {
        java.util.Set<String> out = new HashSet<String>();
        try {
            android.content.pm.Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                if (includeHistory && !pi.signingInfo.hasMultipleSigners()) sigs = pi.signingInfo.getSigningCertificateHistory();
                if (sigs == null || sigs.length == 0) sigs = pi.signingInfo.getApkContentsSigners();
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
            String out = runProcessWithTimeout(buildAdbProcess("pair", host.trim() + ":" + port, code.trim()), 10000);
            invalidateModeCache();
            return out;
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
            invalidateModeCache();                  // whatever the mode was: the connection list changed
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

        /** Posts a notification with an inline reply so the user can type the Wi-Fi pairing code from the
         *  shade (handy while the Wireless debugging "Pair with code" screen is open). */
        @JavascriptInterface
        public void showWirelessPairingNotification() {
            postPairingNotification();
        }

        /** Read-only status report. Never connects, reconnects or changes the configured mode. */
        @JavascriptInterface
        public String getWorkingMode() {
            synchronized (modeCacheLock) {
                if (modeCacheJson != null && System.nanoTime() / 1000000L - modeCacheAt < MODE_CACHE_MS) return modeCacheJson;
            }
            return probeAndCacheMode(false);
        }

        /** Like getWorkingMode, but always probes, root included (an explicit "check now" or right after something changed). */
        @JavascriptInterface
        public String getWorkingModeFresh() {
            return probeAndCacheMode(true);
        }

        /** Probes, and keeps the answer for a moment unless something invalidated the cache while the probe was running. */
        private String probeAndCacheMode(boolean recheckRoot) {
            long gen;
            synchronized (modeCacheLock) {
                gen = modeCacheGen;
                if (recheckRoot) rootChecked = false;
            }
            String json = probeWorkingMode();
            synchronized (modeCacheLock) {
                if (modeCacheGen == gen) {
                    modeCacheJson = json;
                    modeCacheAt = System.nanoTime() / 1000000L;
                }
            }
            return json;
        }

        private String probeWorkingMode() {
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

        /**
         * Runs a command without blocking the page (a hung or slow one used to freeze every tap until it ended).
         * Returns "started"; the output arrives as window.onShellDone(runId, text).
         */
        @JavascriptInterface
        public String executeShellAsync(final String runId, final String cmd) {
            final String id = runId == null ? "" : runId;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    String out;
                    try {
                        out = executeShell(cmd);
                    } catch (Throwable t) {
                        out = "Error: " + errMsg(t);
                    }
                    notifyJs("window.onShellDone && window.onShellDone(" + JSONObject.quote(id) + "," + JSONObject.quote(out == null ? "" : out) + ")");
                }
            });
            return "started";
        }

        /**
         * Reads one settings table (global, secure or system) off the page's thread. Returns "started", or "error: ..." when the
         * request itself is not acceptable. The answer arrives as window.onSettingsList(json).
         */
        @JavascriptInterface
        public String settingsList(final int req, final String ns) {
            String bad = SettingsDb.namespaceProblem(ns);
            if (bad != null) return "error: " + bad;
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!submitSettingsJob(new Runnable() {
                @Override
                public void run() {
                    settingsListJob(req, ns, queuedAt);
                }
            })) return "error: the app is closing";
            return "started";
        }

        /**
         * Reads ("get"), sets ("put") or removes ("delete") one setting, in the order the page asked. Returns "started", or
         * "error: ..." when the table, the name or the value is not acceptable. The answer arrives as window.onSettingsOp(json).
         */
        @JavascriptInterface
        public String settingsOp(final int req, final String op, final String ns, final String key, final String value) {
            if (!"get".equals(op) && !"put".equals(op) && !"delete".equals(op)) return "error: unknown operation";
            String bad = SettingsDb.namespaceProblem(ns);
            if (bad == null) bad = SettingsDb.keyProblem(key);
            if (bad == null && "put".equals(op)) bad = SettingsDb.valueProblem(value);
            if (bad != null) return "error: " + bad;
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!submitSettingsJob(new Runnable() {
                @Override
                public void run() {
                    settingsOpJob(req, op, ns, key, "put".equals(op) ? value : null, queuedAt);
                }
            })) return "error: the app is closing";
            return "started";
        }

        /**
         * Reads the overlays (`cmd overlay list`) off the page's thread. Returns "started". The answer arrives as window.onOverlayList(json).
         */
        @JavascriptInterface
        public String overlayList(final int req) {
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!submitSettingsJob(new Runnable() {
                @Override
                public void run() {
                    overlayListJob(req, queuedAt);
                }
            })) return "error: the app is closing";
            return "started";
        }

        /**
         * Switches one overlay "enable" or "disable". Returns "started", or "error: ..." when the request itself is not acceptable.
         * The answer arrives as window.onOverlayOp(json).
         */
        @JavascriptInterface
        public String overlayOp(final int req, final String op, final String id) {
            if (!OverlayRules.isOp(op)) return "error: unknown operation";
            String bad = OverlayRules.idProblem(id);
            if (bad != null) return "error: " + bad;
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!submitSettingsJob(new Runnable() {
                @Override
                public void run() {
                    overlayOpJob(req, op, id, queuedAt);
                }
            })) return "error: the app is closing";
            return "started";
        }

        /**
         * Sets the Material You theme: source "preset" (with a colour, six hex digits) or "home_wallpaper", and a style (TONAL_SPOT,
         * VIBRANT, EXPRESSIVE, FRUIT_SALAD, RAINBOW or SPRITZ). Returns "started", or "error: ...". The answer arrives as window.onThemeOp(json).
         */
        @JavascriptInterface
        public String themeApply(final int req, final String source, final String hex, final String style) {
            try {
                OverlayRules.themeValue(source, hex, style, System.currentTimeMillis());       // the checks, so a bad request is refused here
            } catch (IllegalArgumentException bad) {
                return "error: " + bad.getMessage();
            }
            return submitTheme(req, "apply", source, hex, style, "");
        }

        /**
         * Writes an earlier theme value back, or an empty one to go back to the system default. {@code undo} says this takes back the
         * last change, so Samsung's wallpaper-colours switch goes back to what that change found too. Returns "started", or
         * "error: ...". The answer arrives as window.onThemeOp(json).
         */
        @JavascriptInterface
        public String themeRestore(final int req, final String raw, final boolean undo) {
            String bad = OverlayRules.themeValueProblem(raw);
            if (bad != null) return "error: " + bad;
            return submitTheme(req, undo ? "undo" : raw.isEmpty() ? "reset" : "restore", "", "", "", raw);
        }

        private String submitTheme(final int req, final String kind, final String source, final String hex, final String style, final String raw) {
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!submitSettingsJob(new Runnable() {
                @Override
                public void run() {
                    themeJob(req, kind, source, hex, style, raw, queuedAt);
                }
            })) return "error: the app is closing";
            return "started";
        }

        /** The colours the system uses now (JSON: ok, sdk, tones, accent1..3, neutral1..2), read without any privileged mode. */
        @JavascriptInterface
        public String getSystemPalette() {
            return systemPaletteJson();
        }

        /** The log, fetched off the page's thread. The answer arrives as window.onLogcatData(id, text). */
        @JavascriptInterface
        public String getLogcatAsync(final int id, final String level, final String filter, final int lines, final String pkg) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    String out = logcatImpl(level, filter, lines, pkg == null || pkg.isEmpty() ? null : pkg);
                    notifyJs("window.onLogcatData && window.onLogcatData(" + id + "," + JSONObject.quote(out == null ? "" : out) + ")");
                }
            });
            return "started";
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

        /**
         * Starts the Rish shell: a persistent shell running as the Shizuku shell user. Returns "not_authorized"
         * when Shizuku is not ready, else "starting"; the outcome arrives as window.onRishStarted(json) with
         * ok, uid, host, cwd, prompt or message.
         */
        @JavascriptInterface
        public String rishStart() {
            if (!isShizukuAuthorized()) return "not_authorized";
            synchronized (rishGate) {
                if (rishStarting) return "starting";
                rishStarting = true;
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        RishShell sh;
                        synchronized (rishGate) {
                            sh = rishShell;
                        }
                        boolean reused = sh != null && sh.isAlive();
                        if (!reused) {
                            sh = new RishShell(new RishShell.Spawner() {
                                @Override
                                public Process spawn(String[] argv) throws Exception {
                                    return rishSpawn(argv);
                                }
                            });
                            sh.setIdleSink(new RishShell.Sink() {
                                @Override
                                public void onOutput(String text) {
                                    rishEmitIdle(text);
                                }
                            });
                            synchronized (rishGate) {
                                rishShell = sh;            // reachable by close() (and onDestroy) while it is still starting
                            }
                            try {
                                sh.start(rishLastCwd.isEmpty() ? null : rishLastCwd);
                            } catch (Throwable failed) {
                                synchronized (rishGate) {
                                    if (rishShell == sh) rishShell = null;
                                }
                                sh.close();
                                throw failed;
                            }
                        }
                        res.put("ok", true);
                        res.put("reused", reused);
                        res.put("uid", sh.uid());
                        res.put("host", sh.host());
                        res.put("cwd", sh.cwd());
                        res.put("prompt", sh.prompt());
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("message", "Could not start the Rish shell: " + errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (rishGate) {
                            rishStarting = false;
                        }
                    }
                    notifyJs("window.onRishStarted && window.onRishStarted(" + res.toString() + ")");
                }
            });
            return "starting";
        }

        /**
         * Runs one command in the Rish shell. Returns "ok", "busy" (one is already running) or "no_shell".
         * Output streams to window.onRishOutput(runId, text); the end arrives as window.onRishDone(runId, json)
         * with exit, cwd, prompt, exited, stopped, timedOut, restarted, revived (or error).
         */
        @JavascriptInterface
        public String rishRun(final String cmd, final String runId) {
            final RishShell sh;
            synchronized (rishGate) {
                sh = rishShell;
                if (sh == null || !sh.isAlive()) return "no_shell";
                if (rishRunning) return "busy";
                rishRunning = true;
            }
            final String rid = runId == null ? "" : runId;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject done = new JSONObject();
                    final int[] shown = {0};
                    try {
                        RishShell.Result r = sh.run(cmd == null ? "" : cmd, RISH_COMMAND_TIMEOUT_MS, new RishShell.Sink() {
                            @Override
                            public void onOutput(String text) {
                                if (shown[0] > RISH_OUTPUT_CAP) return;
                                int room = RISH_OUTPUT_CAP - shown[0];
                                if (text.length() > room) {
                                    text = text.substring(0, room)
                                            + "\n... output cut off here (the command keeps running; tap STOP to end it)\n";
                                    shown[0] = RISH_OUTPUT_CAP + 1;
                                } else {
                                    shown[0] += text.length();
                                }
                                rishEmit(rid, text);
                            }
                        });
                        done.put("exit", r.exit);
                        done.put("cwd", r.cwd);
                        done.put("prompt", sh.prompt());
                        done.put("exited", r.exited);
                        done.put("stopped", r.stopped);
                        done.put("timedOut", r.timedOut);
                        done.put("restarted", r.restarted);
                        done.put("revived", r.revived);
                        if (r.cwd != null && !r.exited) rishLastCwd = r.cwd;
                    } catch (Throwable t) {
                        try {
                            done.put("error", errMsg(t));
                            done.put("exited", !sh.isAlive());
                            done.put("exit", -1);
                        } catch (Exception ignored) {}
                    }
                    rishFlush();
                    synchronized (rishGate) {
                        rishRunning = false;
                    }
                    notifyJs("window.onRishDone && window.onRishDone(" + JSONObject.quote(rid) + "," + done.toString() + ")");
                }
            });
            return "ok";
        }

        /** Ends the command running in the Rish shell (SIGTERM, then SIGKILL if it ignores that). */
        @JavascriptInterface
        public void rishStop() {
            RishShell sh;
            synchronized (rishGate) {
                sh = rishShell;
            }
            if (sh != null) sh.stop();
        }

        /** Closes the Rish shell. */
        @JavascriptInterface
        public void rishClose() {
            closeRishShell();
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

        /** What the About tab shows: this build, the certificate it is signed with, the phone and its WebView. */
        @JavascriptInterface
        public String getAboutInfo() {
            JSONObject o = new JSONObject();
            try {
                PackageManager pm = getPackageManager();
                int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                PackageInfo pi = pm.getPackageInfo(getPackageName(), sigFlags);
                ApplicationInfo ai = getApplicationInfo();
                o.put("versionName", pi.versionName == null ? "" : pi.versionName);
                o.put("versionCode", versionCodeOf(pi));
                o.put("pkg", getPackageName());
                o.put("minSdk", ai.minSdkVersion);
                o.put("targetSdk", ai.targetSdkVersion);
                o.put("debuggable", (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
                o.put("firstInstall", pi.firstInstallTime);
                o.put("lastUpdate", pi.lastUpdateTime);
                java.util.Set<String> signers = signerDigests(pi, false);
                o.put("signerCount", signers.size());
                o.put("signerSha256", signers.isEmpty() ? "" : signers.iterator().next());
                String installer = null;
                try {
                    installer = Build.VERSION.SDK_INT >= 30 ? pm.getInstallSourceInfo(getPackageName()).getInstallingPackageName()
                            : pm.getInstallerPackageName(getPackageName());
                } catch (Throwable ignored) {}
                o.put("installer", installer == null ? "" : installer);
            } catch (Throwable ignored) {
                // whatever could not be read is simply missing
            }
            try {
                o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
                PackageInfo wv = WebView.getCurrentWebViewPackage();
                o.put("webview", wv == null ? "" : (wv.packageName + " " + (wv.versionName == null ? "" : wv.versionName)).trim());
            } catch (Throwable ignored) {
                // idem
            }
            return o.toString();
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
                    String installer = null;
                    try { installer = pm.getInstallerPackageName(info.packageName); } catch (Exception ignored) {}
                    o.put("mods", ModDetect.cheapArray(info, installer));
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

                    // Third-party patch / repackage detection (ReVanced, Xposed/LSPosed module,
                    // LSPatch, NPatch, debug-signed). Deep scan: opens the APK + reads the signer,
                    // which is fine for one app on demand.
                    try {
                        ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                        String installer = null;
                        try { installer = getPackageManager().getInstallerPackageName(pkg); } catch (Exception ignored) {}
                        obj.put("installer", installer == null ? "" : installer);
                        obj.put("mods", ModDetect.deep(ai, installer, ai.sourceDir, signerDnList(pkg)));
                    } catch (Exception ignored) {}

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
         * Launches an activity using a privileged mode (ADB / Shizuku / Root). An EXPORTED activity is
         * started with `am start -W` (trustworthy "Status: ok"/denial, launched in a new task so it
         * surfaces). An UNEXPORTED activity can't be started that way from the shell - uid 2000 isn't its
         * owner and lacks START_ANY_ACTIVITY (the "not exported from uid …" denial) - so it is launched the
         * system's way: the target is set as the device assistant and KEYCODE_ASSIST is injected so the
         * SYSTEM starts it (bypassing the exported check), after which the user's assistant is restored.
         * With no privileged mode, a plain Intent is the only fallback and only for an activity believed
         * exported. Returns {"ok":bool,"method":"intent|shell|assistant|none","output":"..."}
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
                    String comp = pkg + "/" + fullCls;
                    StringBuilder tried = new StringBuilder();

                    // An exported activity starts directly with `am start` (fast, and FLAG_ACTIVITY_NEW_TASK
                    // makes it surface reliably). An unexported activity of another app can't be started this
                    // way from the shell - uid 2000 isn't its owner and lacks START_ANY_ACTIVITY - so skip
                    // straight to the assistant method, which is the only thing that works there.
                    if (exported) {
                        String[] attempts = {
                            "am start -W -f 0x10000000 -n '" + comp + "'",
                            "am start -W -n '" + comp + "'",
                        };
                        for (String cmd : attempts) {
                            String out = executeShell(cmd);
                            tried.append("$ ").append(cmd).append('\n').append(out == null ? "" : out.trim()).append("\n\n");
                            if (isLaunchOk(out)) {
                                res.put("ok", true);
                                res.put("method", "shell");
                                res.put("output", out == null ? "" : out.trim());
                                return res.toString();
                            }
                        }
                    }

                    // The system's way: temporarily make the target the device assistant and press
                    // KEYCODE_ASSIST, so the SYSTEM launches it (bypassing the exported / START_ANY_ACTIVITY
                    // check), then restore the user's assistant. This is how dedicated activity launchers
                    // open unexported activities over Shizuku.
                    String keyOut = launchViaAssistant(comp, tried);
                    String kl = keyOut == null ? "" : keyOut.toLowerCase();
                    // `input keyevent` prints nothing on success; a clean run means the assist key was dispatched.
                    boolean dispatched = !(kl.contains("error") || kl.contains("exception")
                            || kl.contains("not found") || kl.contains("permission denial"));
                    res.put("ok", dispatched);
                    res.put("method", "assistant");
                    res.put("output", dispatched
                        ? ("Launched “" + comp + "” via the assistant method: set it as the device "
                           + "assistant, pressed the ASSIST key so the system started it (this bypasses the "
                           + "exported check), then restored your assistant. If it did not open, the activity "
                           + "may need extra arguments, or another app is holding the assist gesture.\n\n"
                           + tried.toString().trim())
                        : tried.toString().trim());
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

        /** Checks THIS app's own latest GitHub release. Result -> window.onSelfUpdate(json). */
        @JavascriptInterface
        public void checkSelfUpdate() {
            runCheckSelfUpdate();
        }

        /** Downloads and installs the latest release of THIS app (privileged, else the system installer). */
        @JavascriptInterface
        public void installSelfUpdate() {
            runInstallSelfUpdate();
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

        /** Scans storage for .apk/.apks/.apkm/.xapk files. Answer: window.onApkScan(json). */
        @JavascriptInterface
        public void scanApkFiles() {
            runApkScan();
        }

        /** Lets the user choose an .apk/.apks/.apkm/.xapk to install. Answer: window.onInstallFilePicked(ref). */
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
                        synchronized (installInspectLock) {       // two overlapping runs would share (and clear) the same work folder
                            res = inspectInstallSourceImpl(ref);
                        }
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

        // ---- ShizuStore ----------------------------------------------------------------------

        /** Loads the ShizuStore catalog. Answer: window.onStoreCatalog(json). */
        @JavascriptInterface
        public void storeLoadCatalog() {
            runStoreCatalog();
        }

        /** Loads one ShizuStore app's detail. Answer: window.onStoreApp(json). */
        @JavascriptInterface
        public void storeLoadApp(String slug) {
            if (slug != null && !slug.isEmpty()) runStoreApp(slug);
        }

        /** Downloads and installs a ShizuStore app. Progress: window.onStoreInstallProgress(json). */
        @JavascriptInterface
        public void storeInstall(String apkUrl, String pkg, String label) {
            runStoreInstall(apkUrl, pkg, label);
        }

        /**
         * Loads a Store sub-tab catalog: source = "komi" | "orion" | "fdroid-repos" | "fdroid-repo"
         * (arg = repo address for "fdroid-repo"). Answer: window.onStoreSource(json).
         */
        @JavascriptInterface
        public void storeSourceCatalog(String source, String arg) {
            if (source != null && !source.isEmpty()) runStoreSourceCatalog(source, arg, false);
        }

        /** Same as storeSourceCatalog but ignores the on-disk cache (the Refresh buttons). */
        @JavascriptInterface
        public void storeSourceRefresh(String source, String arg) {
            if (source != null && !source.isEmpty()) runStoreSourceCatalog(source, arg, true);
        }

        /** True on a metered connection (mobile data / hotspot) - the UI asks before a big catalog download. */
        @JavascriptInterface
        public boolean isNetworkMetered() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                return cm != null && cm.isActiveNetworkMetered();
            } catch (Throwable t) {
                return false;
            }
        }

        /** Resolves and installs a Komi/Orion/F-Droid catalog item. Progress: window.onStoreInstallProgress(json). */
        @JavascriptInterface
        public void storeSourceInstall(String itemJson) {
            if (itemJson != null && !itemJson.isEmpty()) runStoreSourceInstall(itemJson);
        }

        // ---- VirusTotal (optional, user key) -------------------------------------------------

        /** SHA-256 lookup of the given APK on VirusTotal (no upload). Answer: window.onVtResult(json). */
        @JavascriptInterface
        public void virusTotalScan(String apiKey, String path) {
            runVirusTotalScan(apiKey, path, false);
        }

        /** Uploads the given APK to VirusTotal and waits for analysis. Answer: window.onVtResult(json). */
        @JavascriptInterface
        public void virusTotalUpload(String apiKey, String path) {
            runVirusTotalScan(apiKey, path, true);
        }

        // ---- Small key/value settings (e.g. the VirusTotal API key) --------------------------

        @JavascriptInterface
        public void saveSetting(String key, String value) {
            if (key == null || key.isEmpty() || prefs == null) return;
            prefs.edit().putString("kv_" + key, value == null ? "" : value).apply();
        }

        @JavascriptInterface
        public String loadSetting(String key) {
            if (key == null || key.isEmpty() || prefs == null) return "";
            return prefs.getString("kv_" + key, "");
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
            return logcatImpl(level, filter, lines, null);
        }

        /** Like getLogcat, but only the lines written by one app (every process it has run as, matched by its user id). */
        @JavascriptInterface
        public String getLogcatFor(String level, String filter, int lines, String pkg) {
            return logcatImpl(level, filter, lines, pkg);
        }

        private String logcatImpl(String level, String filter, int lines, String pkg) {
            try {
                if ("standard".equals(resolveExecMode())) return "Error: reading logcat needs ADB, Shizuku or Root.";
                String lv = (level == null || !level.matches("[VDIWEF]")) ? "V" : level;
                int n = (lines <= 0 || lines > 5000) ? 500 : lines;
                String scope = "";
                String note = "";
                int fetch = n;
                int uid = -1;
                if (pkg != null && !pkg.trim().isEmpty()) {
                    pkg = pkg.trim();
                    if (!pkg.matches("[A-Za-z0-9._]+")) return "Error: invalid package name";
                    try {
                        uid = getPackageManager().getApplicationInfo(pkg, 0).uid;
                    } catch (PackageManager.NameNotFoundException nf) {
                        return "Error: " + pkg + " is not installed.";
                    }
                    scope = " --uid=" + uid;
                    fetch = Math.min(5000, n * 5);            // the tail is cut before the app filter on some versions, so read more
                    String[] sharing = getPackageManager().getPackagesForUid(uid);
                    if (sharing != null && sharing.length > 1) note = "note: " + pkg + " shares its user ID with " + (sharing.length - 1) + " other package(s), so their lines appear too\n";
                }
                String out = executeShell("logcat -d -v threadtime -t " + fetch + scope + " *:" + lv);
                if (out == null) out = "";
                if (!scope.isEmpty() && isLogcatUsageError(out)) {
                    // an older logcat without --uid: follow the app's current process instead
                    String pidOut = executeShell("pidof -s " + pkg);
                    String pid = pidOut == null ? "" : pidOut.trim().split("\\s+")[0];
                    if (!pid.matches("[0-9]+")) return "(" + pkg + " is not running, so there are no log lines to show)";
                    out = executeShell("logcat -d -v threadtime -t " + fetch + " --pid=" + pid + " *:" + lv);
                    if (out == null) out = "";
                    note = "note: this Android version can't filter by app, so only the running process (pid " + pid + ") is shown\n";
                }
                // The filter is applied here, never in the shell, so it can't inject anything.
                String[] all = out.split("\n");
                java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
                String f = filter == null ? "" : filter.trim().toLowerCase();
                for (String line : all) {
                    if (line.isEmpty()) continue;
                    if (f.isEmpty() || line.toLowerCase().contains(f)) keep.add(line);
                }
                int from = Math.max(0, keep.size() - n);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < keep.size(); i++) sb.append(keep.get(i)).append('\n');
                String res = sb.toString();
                if (res.trim().isEmpty()) return pkg != null && !pkg.isEmpty() ? "(no matching log lines from " + pkg + ")" : "(no matching log lines)";
                return note + res;
            } catch (Exception e) {
                return "Error: " + (e.getMessage() != null ? e.getMessage() : "logcat failed");
            }
        }

        /** logcat refusing an option answers with an error or its usage text FIRST; an app's own log lines (which may say "invalid option") start with a timestamp. */
        private boolean isLogcatUsageError(String out) {
            if (out == null) return false;
            String first = "";
            for (String line : out.split("\n", 8)) {
                if (!line.trim().isEmpty()) { first = line.trim().toLowerCase(java.util.Locale.US); break; }
            }
            if (first.isEmpty()) return false;
            if (first.matches("^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d.*") || first.startsWith("---------")) return false;     // a real log line / buffer header
            if (first.startsWith("usage:")) return true;
            // "logcat: ..." is also how it reports a failed read ("logcat: Unexpected EOF!"), so it only counts when it is about the options
            if (first.startsWith("logcat:")) {
                return first.contains("unrecognized") || first.contains("unknown") || first.contains("invalid") || first.contains("unexpected argument")
                        || first.contains("usage");
            }
            return first.contains("unrecognized option") || first.contains("unknown option") || first.contains("invalid option") || first.contains("unknown argument");
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
        /** Lists a directory. Tries the app's own filesystem first (works for /sdcard and other storage when
         *  All-files access is granted - fast and reliable, no shell), and falls back to the shell for
         *  privileged-only paths (/data, /system, ...). Returns structured {entries} from the File API, or
         *  {raw,names} for the JS side to parse from the shell. */
        @JavascriptInterface
        public String fmList(String path) {
            JSONObject res = new JSONObject();
            try {
                // Resolve /sdcard and /storage/self/primary to the concrete /storage/emulated/0 (readable
                // by both the app and a shell), and normalize slashes so paths self-heal.
                String p = fmCanonicalPath(path);
                res.put("path", p);
                int slash = p.lastIndexOf('/');
                res.put("parent", p.equals("/") ? "/" : (slash <= 0 ? "/" : p.substring(0, slash)));

                // 1) Direct filesystem access (storage the app can read itself - no shell, no ADB latency).
                try {
                    File dir = new File(p);
                    if (dir.isDirectory()) {
                        File[] kids = dir.listFiles();
                        if (kids != null) {
                            JSONArray entries = new JSONArray();
                            for (File k : kids) {
                                JSONObject e = new JSONObject();
                                boolean d = k.isDirectory();
                                boolean link = false;
                                try { link = !k.getAbsolutePath().equals(k.getCanonicalPath()); } catch (Exception ignored) {}
                                e.put("name", k.getName());
                                e.put("isDir", d);
                                e.put("isLink", link);
                                e.put("size", k.isFile() ? k.length() : 0);
                                e.put("perms", (d ? "d" : "-") + (k.canRead() ? "r" : "-") + (k.canWrite() ? "w" : "-") + (k.canExecute() ? "x" : "-"));
                                entries.put(e);
                            }
                            res.put("entries", entries);
                            res.put("source", "file");
                            return res.toString();
                        }
                    }
                } catch (Exception ignored) {}

                // 2) Shell fallback for privileged paths. Also a simple name list so the JS parser is robust.
                if ("standard".equals(resolveExecMode())) {
                    res.put("error", "Can't read this folder. For storage, grant All-files access; for system folders, set up ADB, Shizuku or Root.");
                    return res.toString();
                }
                // Append a trailing "/" so a symlinked directory (e.g. /sdcard -> /storage/self/primary)
                // is listed by its CONTENTS rather than printing the link itself as a lone entry.
                String listTarget = p.equals("/") ? "/" : p + "/";
                String out = executeShell("ls -la " + BackupScripts.quote(listTarget));
                String names = executeShell("ls -1p " + BackupScripts.quote(listTarget));
                res.put("raw", out != null ? out : "");
                res.put("names", names != null ? names : "");
                res.put("source", "shell");
            } catch (Exception e) {
                try { res.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Whether the app has broad storage access (All-files access on Android 11+). */
        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            try {
                if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
                return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) { return false; }
        }

        /** Opens the system screen to grant this app All-files access (for browsing /sdcard without a shell). */
        @JavascriptInterface
        public void requestAllFilesAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i;
                        if (Build.VERSION.SDK_INT >= 30) {
                            i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                        } else {
                            i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        }
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

        /** First 128 KB of a file as text (for viewing). Reads directly when the app can (e.g. /sdcard),
         *  else through the shell for privileged paths. */
        @JavascriptInterface
        public String fmRead(String path) {
            try {
                path = fmCanonicalPath(path);
                File f = new File(path);
                if (f.isFile() && f.canRead()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int n, total = 0;
                        while ((n = in.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                            total += n;
                            if (total >= 131072) break;
                        }
                        return new String(bo.toByteArray(), "UTF-8");
                    } finally { in.close(); }
                }
                if ("standard".equals(resolveExecMode())) return "Error: needs ADB, Shizuku or Root (or grant All-files access for storage).";
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
                a = fmCanonicalPath(a);
                if (b != null && !b.isEmpty()) b = fmCanonicalPath(b);
                // the same protection the batch operations have: removing or moving away a whole storage root, a top-level folder or a system tree
                if (("rm".equals(op) || "mv".equals(op)) && fmProtectedPath(a)) { res.put("ok", false); res.put("output", "System location: not touched"); return res.toString(); }
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

        /**
         * One command of a batch, through the active privileged backend with a long timeout (a big copy or move outlives
         * executeShell's 8-10 seconds). No connection check per call: a dropped link is reconnected once, like runAdbShell.
         */
        private String batchShell(String cmd) {
            String m = resolveExecMode();
            if ("adb_tcp".equals(m) || "adb_wireless".equals(m)) {
                boolean tcp = "adb_tcp".equals(m);
                if (!tcp && adbWirelessPort <= 0) return "Error: Wireless Debugging is not configured. Connect it in Working Modes.";
                String target = tcp ? tcpTarget() : wirelessTarget();
                String out = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), 600000);
                if (out != null && out.contains("device '") && out.contains("' not found")) {
                    runProcessWithTimeout(buildAdbProcess("connect", target), 5000);
                    out = runProcessWithTimeout(buildAdbProcess("-s", target, "shell", cmd), 600000);
                }
                return out != null ? out : "";
            }
            if ("shizuku".equals(m)) {
                try {
                    return shizukuStream(cmd, null);
                } catch (Exception e) {
                    return executeShell(cmd);                  // the usual path, with its rish fallback
                }
            }
            if ("root".equals(m)) {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                pb.redirectErrorStream(true);
                return runProcessWithTimeout(pb, 600000);
            }
            return executeShell(cmd);
        }

        /**
         * Deletes ("rm"), copies ("cp") or moves ("mv") many files and folders in a few commands instead of one per item
         * (destDir is where cp / mv put them). Returns "started" or "busy"; progress arrives as window.onFmBatchProgress(text)
         * and the end as window.onFmBatchDone(json) with op, ok, total, done and failed: [{p, error}].
         */
        @JavascriptInterface
        public String fmBatch(final String op, final String pathsJson, final String destDir) {
            synchronized (fmBatchLock) {
                if (fmBatchBusy) return "busy";
                fmBatchBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        res.put("op", op);
                        if ("standard".equals(resolveExecMode())) throw new IOException("This needs ADB, Shizuku or Root.");
                        if (!"rm".equals(op) && !"cp".equals(op) && !"mv".equals(op)) throw new IOException("Unknown operation: " + op);
                        JSONArray in = new JSONArray(pathsJson);
                        List<String> paths = new ArrayList<String>();
                        for (int i = 0; i < in.length() && paths.size() < 5000; i++) {
                            String q = fmCanonicalPath(in.optString(i, ""));
                            if (!q.isEmpty() && !paths.contains(q)) paths.add(q);
                        }
                        if (paths.isEmpty()) throw new IOException("Nothing selected");
                        String dest = "";
                        if (!"rm".equals(op)) {
                            dest = fmCanonicalPath(destDir == null ? "" : destDir.trim());
                            if (dest.isEmpty() || dest.equals("/")) throw new IOException("Choose a destination folder");
                        }
                        JSONArray failed = new JSONArray();
                        List<String> todo = new ArrayList<String>();
                        for (String q : paths) {
                            if (fmProtectedPath(q)) failed.put(new JSONObject().put("p", q).put("error", "System location: not touched"));
                            else if (!"rm".equals(op) && (dest.equals(q) || dest.startsWith(q + "/"))) failed.put(new JSONObject().put("p", q).put("error", "Can't put a folder inside itself"));
                            else todo.add(q);
                        }
                        int done = 0;
                        String qd = BackupScripts.quote(dest);
                        final int chunk = 40;
                        boolean linkLost = false;                         // the connection to the device is gone: nothing after that point is tried
                        for (int from = 0; from < todo.size(); from += chunk) {
                            List<String> part = todo.subList(from, Math.min(todo.size(), from + chunk));
                            if (linkLost) {
                                for (String q : part) failed.put(new JSONObject().put("p", q).put("error", "The connection to the device was lost"));
                                continue;
                            }
                            notifyJs("window.onFmBatchProgress && window.onFmBatchProgress(" + JSONObject.quote(fmBatchLabel(op) + " " + Math.min(todo.size(), from + chunk) + " of " + todo.size() + "…") + ")");
                            StringBuilder args = new StringBuilder();
                            for (String q : part) args.append(' ').append(BackupScripts.quote(q));
                            String cmd = "rm".equals(op) ? "rm -rf" + args
                                    : "cp".equals(op) ? "mkdir -p " + qd + " && cp -r" + args + " " + qd + "/"
                                    : "mkdir -p " + qd + " && mv" + args + " " + qd;
                            String out = batchShell(cmd + " && echo FMOK");
                            if (out != null && out.contains("FMOK")) {
                                done += part.size();
                                continue;
                            }
                            if (out != null && fmTransportLost(out.trim())) {
                                // The whole group's answer says the link is gone: asking about each item would only wait on a dead connection.
                                linkLost = true;
                                for (String q : part) failed.put(new JSONObject().put("p", q).put("error", "The connection to the device was lost"));
                                continue;
                            }
                            // something in the group failed: do them one at a time to say which
                            int lost = 0;                                 // answers in a row that say the connection is gone
                            for (String q : part) {
                                if (lost >= 3) {
                                    linkLost = true;
                                    failed.put(new JSONObject().put("p", q).put("error", "The connection to the device was lost"));
                                    continue;
                                }
                                String qq = BackupScripts.quote(q);
                                String one;
                                if ("rm".equals(op)) {
                                    one = "rm -rf " + qq;
                                } else if ("cp".equals(op)) {
                                    one = "mkdir -p " + qd + " && cp -r " + qq + " " + qd + "/";
                                } else {
                                    // The group's mv may already have moved this one before another item stopped it: that counts as done.
                                    String there = BackupScripts.quote(dest + "/" + q.substring(q.lastIndexOf('/') + 1));
                                    one = "if [ -e " + qq + " ] || [ -L " + qq + " ]; then mkdir -p " + qd + " && mv " + qq + " " + qd
                                            + "; elif [ -e " + there + " ] || [ -L " + there + " ]; then true; else echo 'No such file or directory'; false; fi";
                                }
                                String o1 = batchShell(one + " && echo FMOK");
                                if (o1 != null && o1.contains("FMOK")) {
                                    done++;
                                    lost = 0;
                                } else {
                                    String msg = o1 == null ? "failed" : o1.replace("FMOK", "").trim();
                                    lost = fmTransportLost(msg) ? lost + 1 : 0;
                                    failed.put(new JSONObject().put("p", q).put("error", msg.isEmpty() ? "failed" : (msg.length() > 200 ? msg.substring(0, 200) : msg)));
                                }
                            }
                        }
                        res.put("total", paths.size());
                        res.put("done", done);
                        res.put("failed", failed);
                        res.put("ok", failed.length() == 0);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (fmBatchLock) {
                            fmBatchBusy = false;
                        }
                    }
                    notifyJs("window.onFmBatchDone && window.onFmBatchDone(" + res.toString() + ")");
                }
            })) {
                synchronized (fmBatchLock) {
                    fmBatchBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /** Stages an APK from an arbitrary (privileged) path to a readable temp, for the Installer. {ok,ref}|{error}.
         *  When the app itself can read the file (storage with All-files access), the Installer reads it in
         *  place with no shell at all; only truly privileged paths fall back to staging in /data/local/tmp. */
        @JavascriptInterface
        public String fmInstall(String path) {
            JSONObject res = new JSONObject();
            try {
                path = fmCanonicalPath(path);
                try {
                    File f = new File(path);
                    if (f.isFile() && f.canRead()) { res.put("ok", true); res.put("ref", path); return res.toString(); }
                } catch (Exception ignored) {}
                if ("standard".equals(resolveExecMode())) { res.put("ok", false); res.put("error", "Can't read this APK. For storage, grant All-files access; for system paths, set up ADB, Shizuku or Root."); return res.toString(); }
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

        // ---- Archive browser (see ZipTool). The page names the archive by path on every call. ----

        /** Opens an archive for browsing: {ok, path, name, count, files, size, zip64, staged, apk, editable, whyNot}. */
        @JavascriptInterface
        public String archiveOpen(String path) {
            try {
                ZipTool.Archive a = archiveFor(path, true);
                String p = fmCanonicalPath(path);
                int files = 0;
                for (ZipTool.Entry e : a.entries) if (!e.dir) files++;
                String block = archiveEditBlock(p);
                if (block == null && a.prefix > 0) block = "This file has data in front of the archive (a self-extracting or signed package), so it can be viewed but not edited.";
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("path", p);
                r.put("name", new File(p).getName());
                r.put("count", a.entries.size());
                r.put("files", files);
                r.put("size", a.length);
                r.put("zip64", a.zip64);
                r.put("staged", archiveStaged(p));
                r.put("apk", p.toLowerCase(java.util.Locale.US).endsWith(".apk"));
                r.put("editable", block == null);
                r.put("whyNot", block == null ? "" : block);
                return r.toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /**
         * One page of a folder (dir = "" or "a/b/"), or of a search across the whole archive (query != ""):
         * {ok, total, offset, more, entries:[{n,p,d,s,c,t,m,e,f}]}.
         */
        @JavascriptInterface
        public String archiveList(String path, String dir, String query, int offset, int limit) {
            try {
                ZipTool.Archive a = archiveFor(path, false);
                if (limit <= 0 || limit > 2000) limit = ARCHIVE_PAGE;
                if (offset < 0) offset = 0;
                String q = query == null ? "" : query.trim();
                JSONArray arr = new JSONArray();
                int total;
                boolean capped = false;
                if (!q.isEmpty()) {
                    List<ZipTool.Entry> hits = ZipTool.search(a, q, 5000);
                    total = hits.size();
                    capped = total >= 5000;
                    for (int i = offset; i < Math.min(total, offset + limit); i++) arr.put(archiveEntryJson(hits.get(i)));
                } else {
                    List<ZipTool.Child> kids = ZipTool.children(a, dir == null ? "" : dir);
                    total = kids.size();
                    for (int i = offset; i < Math.min(total, offset + limit); i++) arr.put(archiveChildJson(kids.get(i)));
                }
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("dir", dir == null ? "" : dir);
                r.put("query", q);
                r.put("total", total);
                r.put("offset", offset);
                r.put("more", offset + limit < total);
                r.put("capped", capped);
                r.put("entries", arr);
                return r.toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /**
         * Previews one entry. kind is "text" (text, truncated, editable, crlf), "image" (mime, b64), "axml" (text:
         * compiled XML decoded back to XML) or "hex" (hex, note). Every kind also carries size, csize, method, crc, mtime.
         */
        @JavascriptInterface
        public String archiveRead(String path, String entry) {
            try {
                ZipTool.Archive a = archiveFor(path, false);
                ZipTool.Entry e = a.find(entry);
                if (e == null || e.dir) return archiveFail("Not found in the archive: " + entry);
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("name", e.name);
                r.put("size", e.size);
                r.put("csize", e.csize);
                r.put("method", e.method);
                r.put("crc", e.crc);
                r.put("mtime", e.mtime());
                if (e.encrypted()) {
                    r.put("kind", "hex");
                    r.put("hex", "");
                    r.put("note", "This entry is password-protected, so it can't be previewed.");
                    return r.toString();
                }
                ZipTool.Head head = ZipTool.readHead(a, e, 4096);
                String kind = ZipTool.classify(e.name, head.data, head.data.length);
                String note = "";
                if ("image".equals(kind)) {
                    if (e.size > ARCHIVE_IMAGE_LIMIT) {
                        kind = "hex";
                        note = "This image is " + XapkInfo.humanBytes(e.size) + ", too large to preview here. Extract it to open it.";
                    } else {
                        ZipTool.Head full = ZipTool.readHead(a, e, ARCHIVE_IMAGE_LIMIT);
                        r.put("mime", ZipTool.imageMime(e.name));
                        r.put("b64", android.util.Base64.encodeToString(full.data, android.util.Base64.NO_WRAP));
                    }
                }
                if ("axml".equals(kind)) {
                    if (e.size > ARCHIVE_AXML_LIMIT) {
                        kind = "hex";
                        note = "This compiled XML is too large to decode here.";
                    } else {
                        try {
                            ZipTool.Head full = ZipTool.readHead(a, e, ARCHIVE_AXML_LIMIT);
                            r.put("text", ManifestDecoder.decodeBytes(full.data, null));
                        } catch (Throwable t) {
                            kind = "hex";
                            note = "Couldn't decode this compiled XML (" + errMsg(t) + ").";
                        }
                    }
                }
                if ("text".equals(kind)) {
                    ZipTool.Head full = ZipTool.readHead(a, e, ARCHIVE_TEXT_LIMIT);
                    String text = new String(full.data, "UTF-8");
                    boolean valid = !full.truncated && ZipTool.isValidUtf8(full.data, full.data.length, false);
                    String block = archiveEditBlock(fmCanonicalPath(path));
                    boolean hasCrlf = text.contains("\r\n");
                    String withoutCrlf = text.replace("\r\n", "");
                    boolean bareLf = withoutCrlf.indexOf('\n') >= 0;
                    boolean loneCr = withoutCrlf.indexOf('\r') >= 0;
                    boolean mixed = loneCr || (hasCrlf && bareLf);        // the edit box would turn every line end into one kind
                    r.put("text", text);
                    r.put("truncated", full.truncated);
                    r.put("crlf", hasCrlf && !bareLf && !loneCr);
                    r.put("editable", valid && block == null && !mixed);
                    if (full.truncated) r.put("editNote", "Too large to edit here (over " + XapkInfo.humanBytes(ARCHIVE_TEXT_LIMIT) + ").");
                    else if (!valid) r.put("editNote", "Not plain UTF-8 text, so it can't be edited safely.");
                    else if (block != null) r.put("editNote", block);
                    else if (mixed) r.put("editNote", "Mixed line endings, so saving here would change them. Extract it to edit it elsewhere.");
                }
                if ("hex".equals(kind)) {
                    r.put("hex", ZipTool.hexDump(head.data, head.data.length, 512));
                    if (!note.isEmpty()) r.put("note", note);
                }
                r.put("kind", kind);
                return r.toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /**
         * Extracts a file, or a folder (entry ends with '/'; "" is everything), into destDir. Returns "started" or
         * "busy"; progress arrives as window.onArchiveProgress(text) and the end as window.onArchiveResult(json).
         */
        @JavascriptInterface
        public String archiveExtract(final String path, final String entry, final String destDir) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    File tmp = null;
                    try {
                        res.put("op", "extract");
                        ZipTool.Archive a = archiveFor(path, false);
                        String dest = fmCanonicalPath(destDir == null || destDir.trim().isEmpty() ? "/storage/emulated/0/Download" : destDir.trim());
                        File dir = new File(dest);
                        String name = entry == null ? "" : entry;
                        boolean tree = name.isEmpty() || name.endsWith("/");
                        boolean writable = (dir.isDirectory() || dir.mkdirs()) && dir.canWrite();
                        if (!tree) {
                            ZipTool.Entry e = a.find(name);
                            if (e == null || e.dir) throw new IOException("Not found in the archive: " + name);
                            String base = ZipTool.safeName(e.baseName());
                            if (base == null) throw new IOException("Unsafe file name: " + name);
                            notifyArchiveProgress("Extracting " + base + "…");
                            requireNotArchive(path, new File(dest, base));
                            long bytes;
                            if (writable) {
                                bytes = ZipTool.extractTo(a, e, new File(dir, base), new File(fmCanonicalPath(path)));
                            } else {
                                tmp = new File(getCacheDir(), "archive_extract.tmp");
                                bytes = ZipTool.extractTo(a, e, tmp);
                                String err = writeFileTo(tmp, dest + "/" + base);
                                if (err != null) throw new IOException(err);
                            }
                            res.put("files", 1);
                            res.put("bytes", bytes);
                            res.put("skipped", 0);
                            res.put("dest", dest + "/" + base);
                        } else {
                            if (!writable) {
                                throw new IOException("This app can't write to " + dest + ". Choose a folder on shared storage, like /storage/emulated/0/Download/…");
                            }
                            final long[] last = {0};
                            java.util.ArrayList<String> problems = new java.util.ArrayList<String>();
                            long[] r = ZipTool.extractTree(a, name, dir, new ZipTool.Progress() {
                                @Override
                                public boolean onProgress(long doneBytes, int doneFiles, String current) {
                                    long now = System.currentTimeMillis();
                                    if (now - last[0] > 200 && !current.isEmpty()) {
                                        last[0] = now;
                                        notifyArchiveProgress("Extracting " + (doneFiles + 1) + ": " + current);
                                    }
                                    return true;
                                }
                            }, problems, new File(fmCanonicalPath(path)));
                            res.put("files", r[0]);
                            res.put("bytes", r[1]);
                            res.put("skipped", r[2]);
                            res.put("problems", new JSONArray(problems));
                            res.put("dest", dest);
                        }
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        if (tmp != null) tmp.delete();
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onArchiveResult && window.onArchiveResult(" + res.toString() + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /**
         * Edits the archive in place. opJson is one of {op:"delete",name} (a trailing '/' deletes the folder),
         * {op:"rename",name,to}, {op:"replaceText",name,text,crlf}, {op:"add",from,to,overwrite} or {op:"mkdir",to}.
         * The archive is rewritten next to the original and swapped in only when that worked. Returns "started" or
         * "busy"; the end arrives as window.onArchiveResult(json). An edited APK's signature no longer matches.
         */
        @JavascriptInterface
        public String archiveEdit(final String path, final String opJson) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    File tmp = null;
                    try {
                        JSONObject op = new JSONObject(opJson);
                        String kind = op.optString("op");
                        res.put("op", kind);
                        String p = fmCanonicalPath(path);
                        String block = archiveEditBlock(p);
                        if (block != null) throw new IOException(block);
                        ZipTool.Archive a = archiveFor(p, false);
                        requireUnchanged(p);
                        List<ZipTool.Edit> edits = new ArrayList<ZipTool.Edit>();
                        String msg;
                        String name = op.optString("name");
                        if ("delete".equals(kind)) {
                            edits.add(name.endsWith("/") ? ZipTool.Edit.deleteTree(name) : ZipTool.Edit.delete(name));
                            msg = "Deleted " + name;
                        } else if ("rename".equals(kind)) {
                            String to = op.optString("to");
                            if (name.endsWith("/")) edits.add(ZipTool.Edit.renameTree(name, to.endsWith("/") ? to : to + "/"));
                            else edits.add(ZipTool.Edit.rename(name, to));
                            msg = "Renamed " + name + " to " + to;
                        } else if ("replaceText".equals(kind)) {
                            String text = op.optString("text");
                            if (op.optBoolean("crlf")) text = text.replace("\r\n", "\n").replace("\n", "\r\n");
                            if (a.find(name) == null) throw new IOException("Not found in the archive: " + name);
                            edits.add(ZipTool.Edit.replace(name, text.getBytes("UTF-8")));
                            msg = "Saved " + name;
                        } else if ("add".equals(kind)) {
                            String from = fmCanonicalPath(op.optString("from"));
                            File src = new File(from);
                            if (!src.isFile() || !src.canRead()) {
                                throw new IOException("Can't read " + from + ". Pick a file on shared storage (All-files access is needed).");
                            }
                            String to = op.optString("to");
                            if (a.find(to) != null) {
                                if (!op.optBoolean("overwrite")) throw new IOException("\"" + to + "\" is already in the archive");
                                edits.add(ZipTool.Edit.replace(to, src));
                            } else {
                                edits.add(ZipTool.Edit.add(to, src));
                            }
                            msg = "Added " + to;
                        } else if ("mkdir".equals(kind)) {
                            String to = op.optString("to");
                            edits.add(ZipTool.Edit.add(to.endsWith("/") ? to : to + "/", new byte[0]));
                            msg = "Created folder " + to;
                        } else {
                            throw new IOException("Unknown edit: " + kind);
                        }

                        File orig = new File(p);
                        File dir = orig.getParentFile();
                        boolean direct = orig.canWrite() && dir != null && dir.canWrite();
                        tmp = direct ? new File(dir, "." + orig.getName() + ".edit-tmp") : new File(getCacheDir(), "archive_edit.tmp");
                        notifyArchiveProgress("Rewriting " + orig.getName() + "…");
                        final long[] last = {0};
                        ZipTool.rewrite(a, tmp, edits, p.toLowerCase(java.util.Locale.US).endsWith(".apk"), new ZipTool.Progress() {
                            @Override
                            public boolean onProgress(long doneBytes, int doneFiles, String current) {
                                long now = System.currentTimeMillis();
                                if (now - last[0] > 250) {
                                    last[0] = now;
                                    notifyArchiveProgress("Rewriting… " + doneFiles + " entries, " + XapkInfo.humanBytes(doneBytes));
                                }
                                return true;
                            }
                        });
                        ZipTool.open(tmp);      // a result that can't be read back never replaces the original
                        if (direct) {
                            if (!tmp.renameTo(orig)) throw new IOException("Couldn't replace " + orig.getName());
                        } else {
                            String err = writeFileTo(tmp, p);
                            if (err != null) throw new IOException(err);
                        }
                        dropSlot(p);
                        res.put("ok", true);
                        res.put("message", msg);
                        res.put("apk", p.toLowerCase(java.util.Locale.US).endsWith(".apk"));
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        if (tmp != null) tmp.delete();
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onArchiveResult && window.onArchiveResult(" + res.toString() + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /** Forgets every open archive: shell-staged copies and the nested ones extracted to the cache are deleted. */
        @JavascriptInterface
        public void archiveClose() {
            java.util.ArrayList<String> staged = new java.util.ArrayList<String>();
            synchronized (archiveLock) {
                for (ArcSlot slot : archiveSlots.values()) if (slot.stagedPath != null) staged.add(slot.stagedPath);
                archiveSlots.clear();
                staged.addAll(archiveEvictedStages);
                archiveEvictedStages.clear();
            }
            deleteContents(archiveNestedDir());
            if (!staged.isEmpty()) deleteStagedFiles(staged);
        }

        /** Forgets one archive (a nested one is also deleted from the cache). */
        @JavascriptInterface
        public void archiveRelease(String path) {
            String p = fmCanonicalPath(path);
            String staged = null;
            synchronized (archiveLock) {
                ArcSlot slot = archiveSlots.remove(p);
                if (slot != null) staged = slot.stagedPath;
            }
            if (p.startsWith(archiveNestedDir().getAbsolutePath() + "/")) new File(p).delete();
            if (staged != null) deleteStagedFiles(java.util.Collections.singletonList(staged));
        }

        /**
         * Copies one entry out to the cache so it can be installed (kind "install") or opened as an archive of its
         * own (kind "nested"). Returns "started" or "busy"; the end arrives as window.onArchiveStaged(json) with
         * ok, kind, ref (the staged file), name, size.
         */
        @JavascriptInterface
        public String archiveStage(final String path, final String entry, final String kind) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        boolean nested = "nested".equals(kind);
                        res.put("op", "stage");
                        res.put("kind", nested ? "nested" : "install");
                        ZipTool.Archive a = archiveFor(path, false);
                        ZipTool.Entry e = a.find(entry == null ? "" : entry);
                        if (e == null || e.dir) throw new IOException("Not found in the archive: " + entry);
                        if (e.size > ARCHIVE_STAGE_MAX) throw new IOException("This file is " + XapkInfo.humanBytes(e.size) + ", too large to open here. Extract it instead.");
                        File dir = nested ? archiveNestedDir() : archiveStageDirLocal();
                        if (!nested) deleteContents(dir);
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create the cache folder");
                        if (dir.getUsableSpace() < e.size + (16L << 20)) throw new IOException("Not enough free space in the app cache for " + XapkInfo.humanBytes(e.size));
                        String base = ZipTool.safeName(e.baseName());
                        if (base == null) base = "entry";
                        File out = new File(dir, System.currentTimeMillis() + "_" + base.replaceAll("[^A-Za-z0-9._-]", "_"));
                        notifyArchiveProgress("Preparing " + e.baseName() + " (" + XapkInfo.humanBytes(e.size) + ")…");
                        ZipTool.extractTo(a, e, out);
                        res.put("ok", true);
                        res.put("ref", out.getAbsolutePath());
                        res.put("name", e.baseName());
                        res.put("size", e.size);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onArchiveStaged && window.onArchiveStaged(" + res.toString() + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /**
         * Compares two archives by size and CRC (nothing is extracted). The second can also be an installed app's
         * package name. Returns "started" or "busy"; the end arrives as window.onArchiveDiff(json) with ok, a, b,
         * same, truncated and the added / removed / changed lists of {p, a, b}.
         */
        @JavascriptInterface
        public String archiveDiff(final String pathA, final String pathB) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        res.put("op", "diff");
                        String pa = fmCanonicalPath(pathA);
                        String other = pathB == null ? "" : pathB.trim();
                        String pb;
                        if (other.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                            try {
                                pb = getPackageManager().getApplicationInfo(other, 0).sourceDir;
                            } catch (Exception nf) {
                                throw new IOException("No installed app named " + other);
                            }
                        } else {
                            pb = fmCanonicalPath(other);
                        }
                        notifyArchiveProgress("Comparing…");
                        ZipTool.Archive a = archiveFor(pa, false);
                        ZipTool.Archive b = archiveFor(pb, false);
                        ZipTool.Diff d = ZipTool.diff(a, b, 3000);
                        res.put("a", new JSONObject().put("path", pa).put("name", new File(pa).getName()).put("count", a.entries.size()));
                        res.put("b", new JSONObject().put("path", pb).put("name", new File(pb).getName()).put("count", b.entries.size()));
                        res.put("same", d.same);
                        res.put("truncated", d.truncated);
                        res.put("added", diffJson(d.added));
                        res.put("removed", diffJson(d.removed));
                        res.put("changed", diffJson(d.changed));
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onArchiveDiff && window.onArchiveDiff(" + res.toString() + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /** The signing key's fingerprint, without making one: {ok, exists, sha256, subject, created, hardware, bits}. */
        @JavascriptInterface
        public String signingKeyInfo() {
            try {
                return signingKeyJson(SigningKey.existing()).toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /** Throws the signing key away and makes a new one. The end arrives as window.onSigningKey(json). */
        @JavascriptInterface
        public String signingKeyRegenerate() {
            // Not while a signing job runs: it holds the old key, and a new one under the same name must not reach it half way.
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = signingKeyJson(SigningKey.regenerate()).toString();
                    } catch (Throwable t) {
                        json = archiveFail(t);
                    } finally {
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onSigningKey && window.onSigningKey(" + json + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /**
         * What the sign sheet shows for an .apk: its package, whether it carries a valid signature now, whether an
         * installed copy has the same signer as the app's key, and the key itself. The end arrives as
         * window.onSignInfo(json) with path, name, editBlock (why signing in place isn't possible, or ""), key,
         * pkg, label, versionName, versionCode, signed, signer, installed, installedSigner, installedVersion.
         */
        @JavascriptInterface
        public String archiveSignInfo(final String path) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        String p = fmCanonicalPath(path);
                        res.put("op", "signinfo");
                        res.put("path", p);
                        res.put("name", new File(p).getName());
                        String block = archiveEditBlock(p);
                        res.put("editBlock", block == null ? "" : block);
                        SigningKey k = SigningKey.existing();
                        res.put("key", signingKeyJson(k));
                        File f = archiveFor(p, false).file;
                        PackageManager pm = getPackageManager();
                        PackageInfo plain = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                        if (plain != null && plain.packageName != null) {
                            res.put("pkg", plain.packageName);
                            res.put("versionName", plain.versionName != null ? plain.versionName : "");
                            res.put("versionCode", versionCodeOf(plain));
                            if (plain.applicationInfo != null) {
                                plain.applicationInfo.sourceDir = f.getAbsolutePath();
                                plain.applicationInfo.publicSourceDir = f.getAbsolutePath();
                                try {
                                    CharSequence lbl = pm.getApplicationLabel(plain.applicationInfo);
                                    if (lbl != null) res.put("label", lbl.toString());
                                } catch (Throwable ignored) {}
                            }
                            int sigFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                            if (f.length() <= SIGN_CHECK_MAX) {
                                PackageInfo withSig = pm.getPackageArchiveInfo(f.getAbsolutePath(), sigFlags);
                                java.util.Set<String> now = withSig != null ? signerDigests(withSig, false) : new HashSet<String>();
                                res.put("signed", !now.isEmpty());
                                res.put("signer", now.isEmpty() ? "" : now.iterator().next());
                            }
                            try {
                                PackageInfo cur = pm.getPackageInfo(plain.packageName, sigFlags);
                                java.util.Set<String> curSigners = signerDigests(cur, true);
                                res.put("installed", true);
                                res.put("installedVersion", cur.versionName != null ? cur.versionName : "");
                                res.put("installedSigner", curSigners.isEmpty() ? "" : curSigners.iterator().next());
                                res.put("keyMatchesInstalled", k != null && curSigners.contains(k.sha256()));
                            } catch (PackageManager.NameNotFoundException nf) {
                                res.put("installed", false);
                            }
                        }
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    }
                    notifyJs("window.onSignInfo && window.onSignInfo(" + res.toString() + ")");
                }
            });
            return "started";
        }

        /**
         * Signs an .apk with the app's own key (APK Signature Scheme v2): mode "inplace" replaces the file, "copy"
         * writes NAME-signed.apk beside it. Old signature files are dropped and entries re-aligned first. Returns
         * "started" or "busy"; the end arrives as window.onArchiveSigned(json) with ok, inPlace, path, name,
         * sha256, size, hardware.
         */
        @JavascriptInterface
        public String archiveSign(final String path, final String mode) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    File prepared = null, signed = null;
                    try {
                        res.put("op", "sign");
                        boolean inPlace = "inplace".equals(mode);
                        String p = fmCanonicalPath(path);
                        res.put("inPlace", inPlace);
                        if (!p.toLowerCase(java.util.Locale.US).endsWith(".apk")) throw new IOException("Only .apk files can be signed.");
                        if (inPlace) {
                            String block = archiveEditBlock(p);
                            if (block != null) throw new IOException(block);
                        }
                        ZipTool.Archive a = archiveFor(p, true);        // sign what is on disk right now
                        File cache = getCacheDir();
                        if (cache.getUsableSpace() < 2 * a.length + (32L << 20)) {
                            throw new IOException("Not enough free space in the app cache to sign this (" + XapkInfo.humanBytes(a.length) + ").");
                        }
                        notifyArchiveProgress("Preparing " + new File(p).getName() + "…");
                        prepared = new File(cache, "archive_sign_prep.apk");
                        ApkSigner.prepare(a.file, prepared);
                        notifyArchiveProgress("Signing…");
                        SigningKey k = SigningKey.getOrCreate();
                        signed = new File(cache, "archive_sign_out.apk");
                        ApkSigner.signV2(prepared, signed, k.key, k.cert);
                        prepared.delete();
                        ZipTool.open(signed);                           // a result that can't be read back is never written out
                        String dest = inPlace ? p : signedCopyPath(p);
                        notifyArchiveProgress("Saving " + new File(dest).getName() + "…");
                        String err = writeFileTo(signed, dest);
                        if (err != null) throw new IOException(err);
                        dropSlot(p);
                        dropSlot(fmCanonicalPath(dest));
                        res.put("ok", true);
                        res.put("path", dest);
                        res.put("name", new File(dest).getName());
                        res.put("sha256", k.sha256());
                        res.put("hardware", k.hardware);
                        res.put("size", signed.length());
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        if (prepared != null) prepared.delete();
                        if (signed != null) signed.delete();
                        synchronized (archiveLock) {
                            archiveBusy = false;
                        }
                    }
                    notifyJs("window.onArchiveSigned && window.onArchiveSigned(" + res.toString() + ")");
                }
            })) {
                synchronized (archiveLock) {
                    archiveBusy = false;
                }
                return "error";
            }
            return "started";
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

    private static final long BACK_MIN_GAP_MS = 700;     // a press closer than this to the one before is a double tap, not a decision
    private static final long BACK_WINDOW_MS = 3500;     // how long after the warning a deliberate second press still leaves
    private long backArmedAt;                            // when "press back again" was shown (0 = not showing)
    private long backLastAt;                             // the previous Back press

    /**
     * Back asks the page first (window.handleAndroidBack): it closes the top sheet, drops a selection, steps out
     * of the archive browser or back to the previous tab, and only when it has nothing left to do does it warn;
     * a deliberate second press (not a double tap, and never while something is still running) then leaves.
     * The page answers 1 (handled), 0 (leave) or, if it can't answer, this method applies the same two-press rule itself.
     */
    @Override
    public void onBackPressed() {
        if (isHeadless()) {                                // a tile / widget action has no page: Back just closes it
            super.onBackPressed();
            return;
        }
        final WebView wv = webView;
        if (wv == null || !pageReady) {
            backWithoutPage();
            return;
        }
        wv.evaluateJavascript("(function(){try{return window.handleAndroidBack?(window.handleAndroidBack()?1:0):-1;}catch(e){return -1;}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String handled) {
                        if ("1".equals(handled)) return;
                        if ("0".equals(handled)) {
                            backArmedAt = 0;
                            if (wv.canGoBack()) wv.goBack();
                            else MainActivity.super.onBackPressed();
                            return;
                        }
                        backWithoutPage();
                    }
                });
    }

    /** Leaving when the page can't help (it isn't ready yet, or its handler failed): the same deliberate two-press rule. */
    private void backWithoutPage() {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean deliberate = now - backLastAt >= BACK_MIN_GAP_MS;
        backLastAt = now;
        if (backArmedAt != 0 && deliberate && now - backArmedAt <= BACK_WINDOW_MS) {
            backArmedAt = 0;
            super.onBackPressed();
            return;
        }
        backArmedAt = now;
        android.widget.Toast.makeText(this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show();
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
            final RishShell sh;
            synchronized (rishGate) {
                sh = rishShell;
                rishShell = null;
            }
            if (sh != null) {
                // closing ends the shell's whole process tree through helper processes: not on the UI thread
                Thread closer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sh.close();
                        } catch (Throwable ignored) {}
                    }
                }, "rish-close");
                closer.setDaemon(true);
                closer.start();
            }
        } catch (Throwable ignored) {}
        try {
            executor.shutdown();
        } catch (Exception ignored) {}
        try {
            settingsExecutor.shutdown();
        } catch (Exception ignored) {}
    }
}
