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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
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

    /** One thread for the Hidden Settings tab (and the Overlays tab): a toggle tapped twice quickly must reach the phone in that order, and reads must not overtake writes. */
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
        // A package file deleted with an Undo whose few seconds ran out while the app was closed: it is deleted for good now
        if (prefs != null && prefs.getBoolean("apk_trash_pending", false)) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try { purgeApkTrash(); } catch (Exception ignored) {}
                }
            });
        }
    }

    private static final int REQ_STORAGE_PERM = 9301;

    /** The system's permission dialog was answered: tell the page, and after a refusal for good send the user to the app's settings page, where the switch is. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        try {
            if (requestCode == REQ_STORAGE_PERM) {
                boolean never = false;
                for (String p : permissions) {
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p)) never = true;
                }
                if (never) {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            }
        } catch (Exception ignored) {}
        if (requestCode == REQ_TERMUX_PERM) {
            boolean granted = checkSelfPermission(TermuxBridge.PERMISSION) == PackageManager.PERMISSION_GRANTED;
            boolean canAsk = !granted && shouldShowRequestPermissionRationale(TermuxBridge.PERMISSION);
            notifyJs("window.onTermuxPermission && window.onTermuxPermission(" + granted + "," + canAsk + ")");
            return;
        }
        notifyJs("window.onPermissionsChanged && window.onPermissionsChanged()");
    }

    /**
     * On the very first launch, check and request the standard runtime permissions the app uses
     * (notifications, and legacy storage on pre-Android 11). The special accesses - All-files access,
     * usage access and display over other apps - have no dialog of their own: the page offers them in its
     * first-launch sheet (and asks for All-files access again whenever an action needs it).
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
        if (list == null) return "No quick list set. Open Saved Applications in the app and tap Quick list.";
        if ("standard".equals(resolveExecMode())) return "Needs ADB, Shizuku or Root. Set up a working mode first.";
        org.json.JSONArray pkgs = list.optJSONArray("packages");
        AndroidBridge bridge = new AndroidBridge();
        int ok = 0, failed = 0;
        for (int i = 0; pkgs != null && i < pkgs.length(); i++) {
            String out = bridge.executeAppAction("force_stop", pkgs.optString(i));
            // force_stop is flagged by its own real exit status (see AndroidBridge.runShellAction) - read that
            // rather than guessing from the text, same reasoning as the rest of executeAppAction's callers.
            if (out != null && out.length() >= 2 && out.charAt(0) == '\u0001') { if (out.charAt(1) == '1') ok++; else failed++; }
            else if (out != null && (out.toLowerCase().contains("error") || out.toLowerCase().contains("exception") || out.toLowerCase().contains("denied"))) failed++;
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
    // Terminal tab (v7.8): the Terminal sub-tab's persistent shells, one per backend, and the coding agents'
    // HTTPS calls. Backends: "app" (this app's own sandbox, always there), "priv" (the working mode: Shizuku,
    // Root or ADB) and "termux" (the user's own Termux: bash and everything installed with pkg, through
    // TermuxBridge + TermuxLink). Each one is a RishShell, so cd and exports persist between commands.
    // ---------------------------------------------------------------------------------------------

    private static final long TERM_COMMAND_TIMEOUT_MS = 30 * 60 * 1000;      // a package install or a build can take a while
    private static final int TERM_OUTPUT_CAP = 2000000;                        // characters shown per command
    private static final int TERM_QUIET_CAP = 4 * 1024 * 1024;                // what a quiet command (the agents' file reads) may return
    private static final int TERM_IDLE_CHARS_PER_SEC = 200000;
    private static final int REQ_TERMUX_PERM = 9302;

    /** Batches text for one page callback, window.fn(args..., text): a few calls a second instead of one per read, in order. */
    private final class JsStream {
        private final String fn;
        private final StringBuilder buf = new StringBuilder();
        private String args = "";
        private boolean scheduled;
        private final Runnable flusher = new Runnable() {
            @Override
            public void run() {
                flush();
            }
        };

        JsStream(String fn) {
            this.fn = fn;
        }

        /** {@code argsJs}: the callback's leading arguments as JavaScript (already quoted). Text for different arguments never mixes. */
        void add(String argsJs, String text) {
            if (text == null || text.isEmpty()) return;
            synchronized (buf) {
                if (buf.length() > 0 && !argsJs.equals(args)) flushLocked();
                args = argsJs;
                buf.append(text);
                if (buf.length() >= 24000) {
                    flushLocked();
                } else if (!scheduled) {
                    scheduled = true;
                    rishHandler.postDelayed(flusher, 40);
                }
            }
        }

        void flush() {
            synchronized (buf) {
                flushLocked();
            }
        }

        private void flushLocked() {
            scheduled = false;
            rishHandler.removeCallbacks(flusher);
            if (buf.length() == 0) return;
            final String a = args;
            final String text = buf.toString();
            buf.setLength(0);
            rishHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (webView == null) return;
                    int i = 0;
                    while (i < text.length()) {
                        int end = Math.min(text.length(), i + 32000);
                        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
                        webView.evaluateJavascript("window." + fn + " && window." + fn + "(" + a + "," + JSONObject.quote(text.substring(i, end)) + ")", null);
                        i = end;
                    }
                }
            });
        }
    }

    private final class TermSlot {
        final String backend;
        RishShell shell;
        String kind = "";            // what it runs on: app, shizuku, root, adb, termux
        boolean starting;
        boolean running;
        long idleWindowAt;
        int idleChars;
        boolean idleDropped;
        final JsStream out = new JsStream("onTermOutput");

        TermSlot(String backend) {
            this.backend = backend;
        }

        /** Output while no command runs (a background job). A runaway one gets a bounded share and one note. */
        void idle(String text) {
            boolean notice = false;
            synchronized (this) {
                long now = System.nanoTime() / 1000000L;
                if (now - idleWindowAt >= 1000) {
                    idleWindowAt = now;
                    idleChars = 0;
                    idleDropped = false;
                }
                if (idleChars >= TERM_IDLE_CHARS_PER_SEC) {
                    if (idleDropped) return;
                    idleDropped = true;
                    notice = true;
                } else {
                    idleChars += text.length();
                }
            }
            out.add(JSONObject.quote(backend) + ",\"\"", notice ? "\n[a background job is printing faster than this screen can show: output is being dropped]\n" : text);
        }
    }

    private final Object termGate = new Object();
    private final Map<String, TermSlot> termSlots = new java.util.HashMap<String, TermSlot>();     // guarded by termGate

    /** What the "priv" backend runs on right now: shizuku, root, adb, or null with no privileged working mode. */
    private String termPrivKind() {
        String mode = resolveExecMode();
        if ("shizuku".equals(mode)) return isShizukuAuthorized() ? "shizuku" : null;
        if ("root".equals(mode)) return "root";
        if ("adb_tcp".equals(mode) || "adb_wireless".equals(mode)) return "adb";
        return null;
    }

    private RishShell.Spawner termSpawner(String kind, JSONObject opts) {
        if ("shizuku".equals(kind)) {
            return new RishShell.Spawner() {
                @Override
                public Process spawn(String[] argv) throws Exception {
                    return rishSpawn(argv);
                }
            };
        }
        if ("root".equals(kind)) {
            return new RishShell.Spawner() {
                @Override
                public Process spawn(String[] argv) throws Exception {
                    return (argv.length >= 3 && "-c".equals(argv[1]) ? new ProcessBuilder("su", "-c", argv[2]) : new ProcessBuilder("su")).start();
                }
            };
        }
        if ("adb".equals(kind)) {
            final String target = "adb_wireless".equals(resolveExecMode()) ? wirelessTarget() : tcpTarget();
            return new RishShell.Spawner() {
                @Override
                public Process spawn(String[] argv) throws Exception {
                    // -T: no terminal on the phone's side; the shell reads the commands this app writes
                    return (argv.length >= 3 && "-c".equals(argv[1]) ? buildAdbProcess("-s", target, "shell", argv[2])
                            : buildAdbProcess("-s", target, "shell", "-T")).start();
                }
            };
        }
        if ("termux".equals(kind)) {
            boolean login = opts == null || opts.optBoolean("profile", true);
            String cwd = opts == null ? "" : opts.optString("cwd", "");
            return TermuxLink.spawner(TermuxBridge.launcher(this), TermuxBridge.BASH, cwd, login, 25000);
        }
        // this app's own sandbox: always there, no privileges (toybox, and files under the app's home folder)
        final File home = new File(getFilesDir(), "home");
        if (!home.isDirectory()) home.mkdirs();
        return new RishShell.Spawner() {
            @Override
            public Process spawn(String[] argv) throws Exception {
                ProcessBuilder pb = argv.length >= 3 && "-c".equals(argv[1]) ? new ProcessBuilder("/system/bin/sh", "-c", argv[2])
                        : new ProcessBuilder("/system/bin/sh");
                pb.directory(home);
                Map<String, String> env = pb.environment();
                env.put("HOME", home.getAbsolutePath());
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                return pb.start();
            }
        };
    }

    /** Set up once per new shell: no pagers or terminal tricks (there is no keyboard input to running programs). */
    private static String termInitScript(String kind, boolean matchEnv) {
        StringBuilder s = new StringBuilder("export PAGER=cat GIT_PAGER=cat MANPAGER=cat TERM=dumb GIT_TERMINAL_PROMPT=0 2>/dev/null; ");
        if ("termux".equals(kind)) {
            s.append("export DEBIAN_FRONTEND=noninteractive PATH=\"$HOME/.local/bin:$HOME/bin:$PATH\"; ")
                    // pkg / apt would stop at "Do you want to continue? [Y/n]" with no way to answer: they answer yes here
                    .append("pkg() { case \"$1\" in install|reinstall|upgrade|update|uninstall|remove|autoremove|full-upgrade) ")
                    .append("local c=\"$1\"; shift; command pkg \"$c\" -y \"$@\";; *) command pkg \"$@\";; esac; }; ")
                    .append("apt() { case \"$1\" in install|reinstall|upgrade|remove|purge|autoremove|full-upgrade|dist-upgrade) ")
                    .append("local c=\"$1\"; shift; command apt \"$c\" -y \"$@\";; *) command apt \"$@\";; esac; }; ");
            // "profile" (login shell) already reads ~/.bash_profile or ~/.profile; neither of those pulls in ~/.bashrc
            // unless the user's own profile chains to it, so without this, aliases/functions/exports a Termux user
            // keeps in .bashrc would silently be missing here even with the login profile on.
            if (matchEnv) s.append("[ -f \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\" >/dev/null 2>&1; ");
        }
        s.append("true");
        return s.toString();
    }

    private void closeTermSessions() {
        final List<RishShell> shells = new ArrayList<RishShell>();
        synchronized (termGate) {
            for (TermSlot s : termSlots.values()) {
                if (s.shell != null) shells.add(s.shell);
                s.shell = null;
            }
            termSlots.clear();
        }
        if (shells.isEmpty()) return;
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (RishShell sh : shells) {
                    try {
                        sh.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "term-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Termux is there and this app may run commands in it; otherwise an error whose prefix tells the page what to show. */
    private void checkTermuxReady() throws IOException {
        JSONObject st = TermuxBridge.status(this);
        if (!st.optBoolean("installed", false)) throw new IOException("termux_missing: Termux is not installed");
        if (!st.optBoolean("permission", false)) throw new IOException("termux_permission: this app may not run commands in Termux yet");
    }

    /** export lines for an official command-line tool: each provider's own variable only, filled from the vault. */
    private String termSecretEnv(String envJson) {
        if (envJson == null || envJson.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject o = new JSONObject(envJson);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String name = it.next();
                AgentRules.Provider p = AgentRules.find(o.optString(name, ""));
                if (p == null || p.env == null || !p.env.equals(name)) continue;
                String key = vault().get(p.id);
                if (key == null || key.isEmpty()) continue;
                sb.append("export ").append(name).append('=').append(RishShell.quote(key)).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    // ---- coding agents: keys and HTTPS ----

    private AgentVault agentVault;
    private final ExecutorService aiExecutor = Executors.newCachedThreadPool();
    private final Map<String, AiHttp> aiCalls = new java.util.concurrent.ConcurrentHashMap<String, AiHttp>();

    private synchronized AgentVault vault() {
        if (agentVault == null) agentVault = new AgentVault(this);
        return agentVault;
    }

    /** Adds provider {@code auth}'s key to a request, but only if the request goes to that provider's own address. */
    private String aiAuthorize(AiHttp.Request req, String auth) {
        if (auth == null || auth.isEmpty()) return null;
        AgentRules.Provider p = AgentRules.find(auth);
        if (p == null) return "unknown provider " + auth;
        String base = vault().base(auth);
        if (!AgentRules.allowed(p, base, req.url)) {
            return p.ownServer() ? "blocked: that address is not the " + auth + " server saved in Terminal settings"
                    : "blocked: " + auth + " keys are only sent to " + p.host;
        }
        for (String[] h : p.extra) if (!req.headers.containsKey(h[0])) req.headers.put(h[0], h[1]);
        String key = vault().get(auth);
        if (key != null && !key.isEmpty()) { req.headers.put(p.header, p.prefix + key); req.secret = key; }
        else if (!p.ownServer()) return "no " + auth + " key is saved: add it in Terminal settings";
        return null;
    }

    private void aiFinish(String reqId, AiHttp.Response r, String secret) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", r.status);
            o.put("body", r.body == null ? "" : r.body);
            o.put("error", AgentRules.scrub(r.error, secret));          // an exception may quote a header: the key never goes back to the page
            o.put("cancelled", r.cancelled);
            JSONObject h = new JSONObject();
            for (Map.Entry<String, String> e : r.headers.entrySet()) h.put(e.getKey(), e.getValue());
            o.put("headers", h);
        } catch (Exception ignored) {
        }
        notifyJs("window.onAiDone && window.onAiDone(" + JSONObject.quote(reqId) + "," + o.toString() + ")");
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
        String description = "";
        String group = "";
        try {
            android.content.pm.PermissionInfo pi = pm.getPermissionInfo(permName, 0);
            try {
                CharSequence ds = pi.loadDescription(pm);
                if (ds != null) description = ds.toString();
            } catch (Throwable ignored) {}
            if (pi.group != null) group = pi.group;
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
        out.put("description", description);
        out.put("group", group);
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
                                    + (archive.versionName != null ? " " + archive.versionName : "") + ".",
                                    archive.packageName, name);
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
        storeInstallProgress(pkg, stage, percent, message, "", "");
    }

    /**
     * {@code pkg} here is the progress key (a GitHub repo has no package name until the APK is read), so a
     * successful install also reports the real, installed package name and app label separately: the page
     * needs the true package name to offer Launch / Application Settings, which the key is not guaranteed to be.
     */
    private void storeInstallProgress(String pkg, String stage, int percent, String message, String installedPkg, String label) {
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("stage", stage);
            o.put("percent", percent);
            o.put("message", message == null ? "" : message);
            o.put("installedPkg", installedPkg == null ? "" : installedPkg);
            o.put("label", label == null ? "" : label);
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
    private static volatile boolean archiveCancel;      // the page's Cancel for an extraction
    private static volatile boolean fmBatchCancel;       // the page's Cancel: the running batch stops at the next step

    private static final Object appBatchLock = new Object();
    private static boolean appBatchBusy;
    private static volatile boolean appBatchCancel;      // the page's Stop: the running batch stops after the app it is on

    private static final Object optimizeBatchLock = new Object();
    private static boolean optimizeBatchBusy;
    private static volatile boolean optimizeBatchCancel;  // same Stop convention, for Dex optimization (single app or several)

    /** The marker line fmOp's shell commands print when the command itself succeeded: a whole line, so a name or message that merely contains "OK" never counts. */
    private static final java.util.regex.Pattern FM_OK_LINE = java.util.regex.Pattern.compile("(?m)^FMOK\\s*$");
    /** What a failed app launch or app action prints, matched at the start of a line: "Error: ...", "Error type 3", an exception, "Warning: Activity not started", "No activities found", a permission denial. A package or app name that happens to contain "error" or "failed" is not a failure. */
    private static final java.util.regex.Pattern LAUNCH_FAILED = java.util.regex.Pattern.compile(
            "(?mi)^\\s*(error\\b|error type\\b|exception\\b|(java|android)\\.[a-z.]*(exception|error)\\b|security\\s*exception|failure\\b|failed\\b|aborted\\b|status:\\s*(failed|error)|warning: activity not started|no activities found|permission denial)");
    private static boolean launchOutputFailed(String out) {
        return out != null && LAUNCH_FAILED.matcher(out).find();
    }

    private static String fmBatchLabel(String op) {
        return "rm".equals(op) ? "Deleting" : "cp".equals(op) ? "Copying" : "Moving";
    }

    /** True when a shell answer says the link to the device is gone, so trying item after item is pointless (see FileRules). */
    private static boolean fmTransportLost(String msg) {
        return FileRules.transportLost(msg);
    }

    /** Places a batch delete / move must not touch: the root, any top-level folder, the user's whole storage, the system trees (see FileRules). */
    /** Whether the app can do this one with its own file access: it can read the source and write where the result goes. */
    private static boolean fmDirectOk(String op, String path, String destDir) {
        try {
            File f = new File(path);
            if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return false;
            File parent = f.getParentFile();
            if ("rm".equals(op)) return parent != null && parent.canWrite();
            if (!f.canRead()) return false;
            if ("mv".equals(op) && (parent == null || !parent.canWrite())) return false;
            File d = new File(destDir);
            while (d != null && !d.exists()) d = d.getParentFile();           // the folder may still have to be made: its nearest parent decides
            return d != null && d.isDirectory() && d.canWrite();
        } catch (Exception e) { return false; }
    }

    /**
     * One file operation with the app's own access: "" when it worked, a message when it failed, null when this one is not for Java
     * (the path is out of reach, or the shape of the operation needs the shell). A message starting with "?" means: try the shell.
     */
    private static String fmOpDirect(String op, String a, String b) {
        try {
            File fa = new File(a);
            if ("mkdir".equals(op)) {
                File d = fa;
                while (d != null && !d.exists()) d = d.getParentFile();
                if (d == null || !d.isDirectory() || !d.canWrite()) return null;
                if (fa.isDirectory()) return "";
                return fa.mkdirs() ? "" : "The folder could not be created";
            }
            if ("touch".equals(op)) {
                File p = fa.getParentFile();
                if (p == null || !p.isDirectory() || !p.canWrite()) return null;
                if (fa.exists()) return fa.setLastModified(System.currentTimeMillis()) ? "" : "?";
                return fa.createNewFile() ? "" : "The file could not be created";
            }
            if ("rm".equals(op)) {
                if (!fmDirectOk("rm", a, null)) return null;
                FileOps.Result r = FileOps.delete(java.util.Collections.singletonList(fa), null);
                return r.failed.isEmpty() ? "" : r.failed.get(0)[1];
            }
            if (("mv".equals(op) || "cp".equals(op)) && b != null && !b.isEmpty()) {
                File fb = new File(b);
                if ("mv".equals(op)) {                                          // a rename, or a move into a folder
                    if (fb.isDirectory()) { if (!fmDirectOk("mv", a, b)) return null; FileOps.Result r = FileOps.move(java.util.Collections.singletonList(fa), fb, FileOps.REPLACE, null); return r.failed.isEmpty() ? "" : r.failed.get(0)[1]; }
                    File pa = fa.getParentFile(), pb = fb.getParentFile();
                    if (pa == null || pb == null || !pa.canWrite() || !pb.isDirectory() || !pb.canWrite() || !fa.exists()) return null;
                    boolean same = false;
                    try { same = fb.exists() && java.nio.file.Files.isSameFile(fa.toPath(), fb.toPath()); } catch (Exception ignored) {}      // only the case of the name changes
                    if (fb.exists() && !same) return "A file or folder with that name is already there";
                    return fa.renameTo(fb) ? "" : "?";
                }
                if (fb.isDirectory()) { if (!fmDirectOk("cp", a, b)) return null; FileOps.Result r = FileOps.copy(java.util.Collections.singletonList(fa), fb, FileOps.REPLACE, null); return r.failed.isEmpty() ? "" : r.failed.get(0)[1]; }
            }
        } catch (Exception e) {
            return e.getMessage() == null ? "failed" : e.getMessage();
        }
        return null;
    }

    private static String fmBatchDoneText(String op, JSONObject res) {
        try {
            if (res.has("error")) return String.valueOf(res.optString("error"));
            String verb = "rm".equals(op) ? "Deleted" : "cp".equals(op) ? "Copied" : "Moved";
            int bad = res.optJSONArray("failed") == null ? 0 : res.optJSONArray("failed").length();
            return verb + " " + res.optInt("done") + " of " + res.optInt("total") + (bad > 0 ? " · " + bad + " failed" : "") + (res.optBoolean("cancelled") ? " · stopped" : "");
        } catch (Exception e) { return ""; }
    }

    private static String fmSizeText(long b) {
        if (b >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return Math.max(1, b / 1024) + " KB";
    }

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
        String[] info = manifestSplitInfo(apk);
        return info == null ? null : info[0];
    }

    /**
     * What a split's manifest says about it: {split, configForSplit, isFeatureSplit}, or null when it can't be read. split is null for a
     * base APK; configForSplit is "" for the base's own config splits and names the feature module otherwise.
     */
    private String[] manifestSplitInfo(File apk) {
        try {
            // A config/feature split declares <manifest ... split="config.xxxhdpi">; a base APK has no such attribute (see SplitInfo).
            return SplitInfo.parse(ManifestDecoder.decodeApk(apk.getAbsolutePath(), null));
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
            String[] info = (f == base) ? null : manifestSplitInfo(f);
            String split = info == null ? null : info[0];
            JSONObject s = new JSONObject();
            s.put("path", f.getAbsolutePath());
            s.put("name", f.getName());
            s.put("size", f.length());
            s.put("isBase", f == base);
            s.put("split", split != null ? split : "");
            s.put("configFor", info == null ? "" : info[1]);          // the feature module a config split belongs to ("" = the base)
            s.put("feature", info != null && "true".equals(info[2])); // a feature (dynamic) module of its own
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
    // Android settings (the Hidden Settings tab): `settings list|get|put|delete` on the global, secure and system tables, through the
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
    // be touched and how a command is built is OverlayRules' job (tested off the device). They share the Hidden Settings tab's single
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

    // ---- Task Manager: a lightweight native poll, separate from JobService (which is for long file jobs with their own notification).
    // Runs only while the tab is open (started/stopped by the page); each tick is one shell round trip for CPU/RAM/network/processes/GPU,
    // marker-delimited so a slow or truncated read of one piece never corrupts another, plus a synchronous (no shell) battery read.
    private static final Object tmLock = new Object();
    private ScheduledExecutorService tmExec;
    private ScheduledFuture<?> tmTask;
    private CpuStats.Reading tmLastCpu;
    private NetStats.Reading tmLastNet;
    private long tmLastSampleAt;
    private static final String TM_M1 = "@@TM1-CPU@@", TM_M2 = "@@TM2-MEM@@", TM_M3 = "@@TM3-NET@@", TM_M4 = "@@TM4-PS@@",
            TM_M5 = "@@TM5-GPUBUSY@@", TM_M6 = "@@TM6-GPUPCT@@", TM_M7 = "@@TM7-MALI@@", TM_M8 = "@@TM8-FREQCUR@@", TM_M9 = "@@TM9-FREQMAX@@",
            TM_M10 = "@@TM10-THREADS@@";

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
        return archiveFor(path, fresh, null);
    }

    /** As {@link #archiveFor(String, boolean)}; {@code password} is tried only for a fresh open of a format whose listing itself needs one
     *  (7z with encrypted names, rar -hp) — a zip's directory is never encrypted, so a zip never needs it here. */
    private ZipTool.Archive archiveFor(String path, boolean fresh, char[] password) throws Exception {
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
                a = openAnyArchive(use, password);
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

    /** Opens a zip first (a bounded scan for its end record, cheap even to rule a huge non-zip file out); a file that isn't one is tried
     *  as a RAR, then as whatever {@link ArchiveIo} recognises (7z, the tar family, or a single compressed file). */
    private ZipTool.Archive openAnyArchive(File use, char[] password) throws IOException {
        IOException zipErr;
        try {
            return ZipTool.open(use);
        } catch (IOException e) {
            zipErr = e;
        }
        if (RarReader.isRar(use)) return RarSource.open(use, password);
        String fmt = ArchiveIo.detect(use);
        if (fmt != null && ArchiveIo.supportsRead(fmt)) return ArchiveIoSource.open(use, fmt, password);
        throw zipErr;
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
            needFileAccess("To open this file", p);
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
        if ("standard".equals(resolveExecMode())) { needFileAccess("To change this file", p); return "Editing needs All-files access for storage, or ADB / Shizuku / Root for other folders."; }
        return null;
    }

    private static String archiveFail(Throwable t) {
        if (t instanceof ZipTool.NeedPassword) {
            ZipTool.NeedPassword np = (ZipTool.NeedPassword) t;
            try {
                return new JSONObject().put("ok", false).put("error", np.getMessage()).put("needPassword", true).put("wrong", np.wrong).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"needPassword\":true}";
            }
        }
        return archiveFail(errMsg(t));
    }

    /** Puts ok:false, error and (for a NeedPassword) needPassword/wrong into a result JSON built by an async job. */
    private static void putArchiveError(JSONObject res, Throwable t) {
        try {
            res.put("ok", false);
            if (t instanceof ZipTool.NeedPassword) {
                ZipTool.NeedPassword np = (ZipTool.NeedPassword) t;
                res.put("error", np.getMessage());
                res.put("needPassword", true);
                res.put("wrong", np.wrong);
            } else {
                res.put("error", errMsg(t));
            }
        } catch (Exception ignored) {}
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
            needFileAccess("To save a file there", dest);
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

    /**
     * Tells the page an action on {@code path} has just failed for want of All-files access, so that it asks for it. Nothing happens
     * when the app has the access (the failure was something else), nor when the path is not on shared storage (system folders, other
     * apps' data: the access would not help there), and the page shows its prompt once, not once per failure.
     */
    private void needFileAccess(String reason, String path) {
        try {
            if (hasStorageAccess()) return;
            if (path == null || ApkTrash.volumeRoot(fmCanonicalPath(path)) == null) return;
            notifyJs("window.onFileAccessNeeded && window.onFileAccessNeeded(" + JSONObject.quote(reason == null ? "" : reason) + ")");
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------------------------------
    // Deleting a package file the search found, with an Undo (the rules are in ApkTrash)
    // ---------------------------------------------------------------------------------------------

    /**
     * Asks the working mode whether {@code test} (-e, -f) holds for a path: it answers YES or NO. Any other reply (the link is down, su is
     * missing: the shell helpers report those as text, they do not throw) is an error and not a "no", so a dead shell is never read as
     * "that file is not there".
     */
    private boolean shellTest(String mode, String test, String path) throws Exception {
        return ApkTrash.yesNo(shellVia(mode, "[ " + test + " " + BackupScripts.quote(path) + " ] && echo YES || echo NO"));
    }

    // A purge at start-up can take seconds (the working mode may still be connecting): it must not touch what the person deletes meanwhile
    private final Object apkTrashLock = new Object();
    private volatile boolean apkTrashedThisRun = false;

    private boolean pathExists(String mode, String path) throws Exception {
        if (new File(path).exists()) return true;
        if ("standard".equals(mode)) return false;
        return shellTest(mode, "-e", path);
    }

    /** True for a regular file (not a folder that happens to be called "x.apk"). */
    private boolean isRegularFile(String mode, String path) throws Exception {
        File f = new File(path);
        if (f.isFile()) return true;
        if (f.exists() || "standard".equals(mode)) return false;
        return shellTest(mode, "-f", path);
    }

    /**
     * Moves one file: with the app's own access when it has it, else through the privileged shell. Never replaces a file that is already
     * at the target (a rename would). null when it worked, otherwise why not.
     */
    private String moveFile(String mode, String from, String to) {
        try {
            File src = new File(from), dst = new File(to);
            boolean own = hasStorageAccess();
            if (own && src.isFile() && !dst.exists()) {
                File dir = dst.getParentFile();
                if (dir != null && (dir.mkdirs() || dir.isDirectory()) && src.renameTo(dst)) return null;          // (mkdirs is false when another call made it a moment ago)
            }
            if ("standard".equals(mode)) return own ? "Android wouldn't let this app move that file." : "This needs All-files access, or ADB, Shizuku or Root.";
            String dir = to.substring(0, to.lastIndexOf('/'));
            String out = shellVia(mode, "mkdir -p " + BackupScripts.quote(dir) + " && [ ! -e " + BackupScripts.quote(to) + " ] && mv " + BackupScripts.quote(from) + " " + BackupScripts.quote(to) + " && echo FMOK");
            if (out != null && out.contains("FMOK")) return null;
            String why = out == null ? "" : out.trim();
            return why.isEmpty() ? "The file could not be moved." : why;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "The file could not be moved.";
        }
    }

    /** Deletes one trashed file or a whole trash folder, with the app's own access when it can and else through the shell. */
    private void deleteQuietly(String mode, String path, boolean folder) {
        try {
            File f = new File(path);
            if (hasStorageAccess()) {
                if (folder && f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) k.delete(); }
                f.delete();
                if (!f.exists()) return;
            }
            if (!"standard".equals(mode)) shellVia(mode, (folder ? "rm -rf " : "rm -f ") + BackupScripts.quote(path));
        } catch (Exception ignored) {}
    }

    /** The storage volumes a package file can be on: the primary storage and any SD card or USB drive. */
    private List<String> storageVolumes() {
        List<String> roots = new ArrayList<String>();
        roots.add(fmCanonicalPath("/sdcard"));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) if (v.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) roots.add(v.getAbsolutePath());
        }
        return roots;
    }

    /**
     * Deletes what every trash folder holds (what an app that was closed during an Undo's few seconds left behind). The "something is
     * waiting" mark is cleared only when every trash folder is seen to be gone; when the app can neither read storage nor use a working
     * mode (the connection is not up yet, say) it can't see them, so the mark stays and the next start tries again. true when all is gone.
     */
    private boolean purgeApkTrash() {
        if (apkTrashedThisRun) return false;                  // a file deleted in this run waits for its Undo: leave it all to the next start
        String mode = resolveExecMode();
        boolean gone = hasStorageAccess() || !"standard".equals(mode);
        for (String root : storageVolumes()) {
            if (apkTrashedThisRun) return false;
            String dir = ApkTrash.trashDir(root);
            deleteQuietly(mode, dir, true);
            try { if (hasStorageAccess() ? new File(dir).exists() : pathExists(mode, dir)) gone = false; } catch (Exception e) { gone = false; }
        }
        synchronized (apkTrashLock) {
            if (gone && !apkTrashedThisRun) prefs.edit().putBoolean("apk_trash_pending", false).apply();
        }
        return gone && !apkTrashedThisRun;
    }

    /** op "trash" (a = path): move the file into its volume's trash; "untrash" (a = trash path, b = original path): put it back; "purge" (a = trash path, or empty for all): delete for good. */
    private JSONObject apkFileOpImpl(String op, String a, String b) throws Exception {
        JSONObject res = new JSONObject();
        String mode = resolveExecMode();
        if ("trash".equals(op)) {
            String src = fmCanonicalPath(a);
            if (!ApkTrash.deletable(src)) throw new IOException("Only app package files on storage can be deleted here.");
            if ("standard".equals(mode) && !hasStorageAccess()) {          // the list came from a working mode that has gone since
                needFileAccess("To delete files from storage", src);
                throw new IOException("This needs All-files access, or ADB, Shizuku or Root.");
            }
            if (!isRegularFile(mode, src)) throw new IOException(pathExists(mode, src) ? "That is not a package file." : "That file is already gone.");
            String dest = ApkTrash.trashPath(src, ApkTrash.nextStamp(System.currentTimeMillis()));
            // marked before the move: if the app is killed in between, the next start still knows to look in the trash folders
            synchronized (apkTrashLock) {
                apkTrashedThisRun = true;
                prefs.edit().putBoolean("apk_trash_pending", true).commit();
            }
            String why = moveFile(mode, src, dest);
            if (why != null) {
                if (why.contains("All-files access")) needFileAccess("To delete files from storage", src);
                throw new IOException(why);
            }
            res.put("ok", true);
            res.put("trash", dest);
        } else if ("untrash".equals(op)) {
            String trash = fmCanonicalPath(a), orig = fmCanonicalPath(b);
            if (!ApkTrash.restorable(trash, orig)) throw new IOException("That file can't be put back.");
            // a file of the same name may be there again: both are kept
            final String m = mode;
            String target = ApkTrash.uniqueTarget(orig, new ApkTrash.Exists() {
                @Override
                public boolean at(String path) throws Exception { return pathExists(m, path); }
            });
            if (target == null) throw new IOException("A file with that name is in the way.");
            String why = moveFile(mode, trash, target);
            if (why != null) throw new IOException(why);
            res.put("ok", true);
            res.put("path", target);
        } else if ("purge".equals(op)) {
            if (a == null || a.trim().isEmpty()) {
                if (prefs.getBoolean("apk_trash_pending", false)) purgeApkTrash();          // nothing waiting: nothing to look for
            } else {
                String trash = fmCanonicalPath(a);
                if (!ApkTrash.isTrashPath(trash)) throw new IOException("That is not a deleted file.");
                deleteQuietly(mode, trash, false);
                if (pathExists(mode, trash)) throw new IOException("The file could not be removed now. It is removed the next time the app starts.");
            }
            res.put("ok", true);
        } else {
            throw new IOException("Unknown operation: " + op);
        }
        return res;
    }

    private static final String SCAN_FIND_PREDICATE =
            "-type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.apkm' -o -iname '*.xapk' \\)";

    private volatile long apkScanProgressAt = 0;
    // numbers the searches: when a newer one starts (the page gave up on the first, or was reloaded), the older one's progress and answer are dropped
    private final java.util.concurrent.atomic.AtomicInteger apkScanSeq = new java.util.concurrent.atomic.AtomicInteger();

    /** Tells the page how far the search has got: window.onApkScanProgress({pct (-1 = no way to tell), msg, found}). At most a few times a second unless forced. */
    private void sendApkScanProgress(int scanId, int pct, String msg, int found, boolean force) {
        if (scanId != apkScanSeq.get()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - apkScanProgressAt < 120) return;
        apkScanProgressAt = now;
        try {
            JSONObject o = new JSONObject();
            o.put("pct", pct);
            o.put("msg", msg);
            o.put("found", found);
            notifyJs("window.onApkScanProgress && window.onApkScanProgress(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }

    /**
     * Scans storage off-thread and reports to window.onApkScan(json): {status, files[], truncated, ms, fs, shell}.
     * Folders this app can read are walked directly; with a privileged mode the shell also covers the ones it
     * can't (Android/data, Android/obb - or everything when All-files access isn't granted).
     */
    private void runApkScan() {
        final int scanId = apkScanSeq.incrementAndGet();
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
                        if (scanId == apkScanSeq.get()) notifyJs("window.onApkScan && window.onApkScan(" + JSONObject.quote(res.toString()) + ")");
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

                    // Progress: the folders this app can read fill the first part of the bar (all of it without a privileged mode),
                    // the shell's search and its size look-ups the rest. The unit is a top-level folder of each volume.
                    final int walkShare = privileged ? 85 : 98;
                    apkScanProgressAt = 0;
                    sendApkScanProgress(scanId, 0, "Looking through storage…", 0, true);
                    if (fsAccess) {
                        final int totalTop = ApkScan.countTopFolders(roots);
                        int doneBefore = 0;
                        for (File r : roots) {
                            doneBefore += ApkScan.walkRoot(r, found, seen, lim, doneBefore, totalTop, new ApkScan.Progress() {
                                @Override
                                public void onProgress(int done, int total, String folder, int foundNow) {
                                    int pct = total > 0 ? Math.min(walkShare, done * walkShare / total) : walkShare;
                                    sendApkScanProgress(scanId, pct, "Searched " + folder + " (" + done + " of " + total + " folders)", foundNow, done >= total);
                                }
                            });
                        }
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
                        sendApkScanProgress(scanId, -1, fsAccess ? "Searching Android/data and Android/obb with the privileged mode…" : "Searching storage with the privileged mode…", found.size(), true);
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
                            sendApkScanProgress(scanId, 90 + Math.min(9, i * 9 / Math.max(1, unreadable.size())), "Reading sizes and dates (" + Math.min(i + 40, unreadable.size()) + " of " + unreadable.size() + " files)", found.size(), false);
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
                if (scanId == apkScanSeq.get()) notifyJs("window.onApkScan && window.onApkScan(" + JSONObject.quote(res.toString()) + ")");
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The app's own font: found on storage or chosen with the file chooser, kept in the app's files, handed to the page as base64
    // ---------------------------------------------------------------------------------------------

    private static final int REQ_PICK_FONT = 4204;
    private static final int REQ_PICK_TEXT = 4290;       // a small text file for the page (presets and saved commands)
    private volatile String pickTextTag = "";
    private volatile long fontScanProgressAt = 0;
    // numbers the searches, like the one for packages: an older search's progress and answer are dropped
    private final java.util.concurrent.atomic.AtomicInteger fontScanSeq = new java.util.concurrent.atomic.AtomicInteger();

    private final Object fontLock = new Object();       // one preview / apply / clear at a time: they share preview.bin, preview.json and current.*

    private File fontDir() {
        File d = new File(getFilesDir(), "ui_font");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Tells the page how far the font search has got: window.onFontScanProgress({pct, msg, found}). At most a few times a second unless forced. */
    private void sendFontScanProgress(int scanId, int pct, String msg, int found, boolean force) {
        if (scanId != fontScanSeq.get()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - fontScanProgressAt < 120) return;
        fontScanProgressAt = now;
        try {
            JSONObject o = new JSONObject();
            o.put("pct", pct);
            o.put("msg", msg);
            o.put("found", found);
            notifyJs("window.onFontScanProgress && window.onFontScanProgress(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) {}
    }

    /** Searches the folders this app can read for .ttf / .otf files and reports to window.onFontScan({status, fonts[], truncated, ms}). */
    private void runFontScan() {
        final int scanId = fontScanSeq.incrementAndGet();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                JSONObject res = new JSONObject();
                long t0 = System.currentTimeMillis();
                try {
                    if (!hasStorageAccess()) {
                        res.put("status", "noaccess");
                    } else {
                        final FontScan.Limits lim = new FontScan.Limits();
                        lim.deadlineMs = t0 + 25000;
                        List<File> roots = new ArrayList<File>();
                        roots.add(new File(fmCanonicalPath("/sdcard")));
                        File[] vols = new File("/storage").listFiles();   // SD cards / USB drives
                        if (vols != null) {
                            for (File v : vols) if (v.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) roots.add(v);
                        }
                        fontScanProgressAt = 0;
                        sendFontScanProgress(scanId, 0, "Looking through storage…", 0, true);
                        List<FontScan.Entry> found = new ArrayList<FontScan.Entry>();
                        Set<String> seen = new HashSet<String>();
                        final int totalTop = FontScan.countTopFolders(roots);
                        int doneBefore = 0;
                        for (File r : roots) {
                            doneBefore += FontScan.walkRoot(r, found, seen, lim, doneBefore, totalTop, new FontScan.Progress() {
                                @Override
                                public void onProgress(int done, int total, String folder, int foundNow) {
                                    if (scanId != fontScanSeq.get()) { lim.hitLimit = true; return; }     // a newer search started: stop this walk
                                    int pct = total > 0 ? Math.min(99, done * 99 / total) : 99;
                                    sendFontScanProgress(scanId, pct, "Searched " + folder + " (" + done + " of " + total + " folders)", foundNow, done >= total);
                                }
                            });
                        }
                        FontScan.sort(found);
                        JSONArray fonts = new JSONArray();
                        for (FontScan.Entry e : found) fonts.put(FontScan.toJson(e));
                        res.put("status", "ok");
                        res.put("fonts", fonts);
                        res.put("truncated", lim.hitLimit);
                        res.put("ms", System.currentTimeMillis() - t0);
                    }
                } catch (Exception e) {
                    try { res.put("status", "error"); res.put("error", e.getMessage() != null ? e.getMessage() : "search failed"); } catch (Exception ignored) {}
                }
                if (scanId == fontScanSeq.get()) notifyJs("window.onFontScan && window.onFontScan(" + JSONObject.quote(res.toString()) + ")");
            }
        });
    }

    /** The name a content:// address is shown with (its last part when the provider does not say). */
    private String contentName(Uri u) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null && !n.isEmpty()) return n; }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        String last = u.getLastPathSegment();
        return last == null ? "font" : last.substring(last.lastIndexOf('/') + 1);
    }

    /** Copies the font in ref (a path, or the content:// address the file chooser gave) to the preview slot after checking it. Answer: {ok, family, style, name, kind, variable, size} or {ok:false, error}. */
    private JSONObject stageFont(String ref) {
        synchronized (fontLock) { return stageFontLocked(ref); }
    }

    private JSONObject stageFontLocked(String ref) {
        JSONObject res = new JSONObject();
        try {
            File dir = fontDir();
            new File(dir, "preview.bin").delete();     // a failed pick must not leave the earlier preview to be applied
            new File(dir, "preview.json").delete();
            if (ref == null || ref.isEmpty()) throw new IllegalStateException("No file was chosen.");
            InputStream in;
            String name;
            if (ref.startsWith("content:")) {
                Uri u = Uri.parse(ref);
                name = contentName(u);
                in = getContentResolver().openInputStream(u);
                if (in == null) throw new IllegalStateException("The file could not be opened.");
            } else {
                File f = new File(fmCanonicalPath(ref));
                if (!f.isFile() || !f.canRead()) throw new IllegalStateException("The app cannot read that file. Grant All-files access, or choose the file with the file chooser.");
                name = f.getName();
                in = new java.io.FileInputStream(f);
            }
            File dest = new File(fontDir(), "preview.bin");
            FontScan.Names n;
            try { n = FontScan.copyChecked(in, dest); } finally { try { in.close(); } catch (Exception ignored) {} }
            String kind = n.kind;
            res.put("ok", true);
            res.put("family", n.family.isEmpty() ? (name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name) : n.family);
            res.put("style", n.style);
            res.put("name", name);
            res.put("kind", kind);
            res.put("variable", n.variable);
            res.put("size", dest.length());
            File pj = new File(dir, "preview.json"), pjTmp = new File(dir, "preview.json.part");
            writeTextFile(pjTmp, res.toString());
            if (!pjTmp.renameTo(pj)) throw new IllegalStateException("The font could not be stored.");
        } catch (Exception e) {
            new File(fontDir(), "preview.bin").delete();
            try { res = new JSONObject(); res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "The font could not be read."); } catch (Exception ignored) {}
        }
        return res;
    }

    private String readTextFile(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { in.close(); }
    }

    private void writeTextFile(File f, String text) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
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
        // A storage root added through pickAddStorage() is an opaque content:// tree/document URI, not a POSIX
        // path - running it through alias-swapping and "." / ".." folding would corrupt it (SAF URIs can
        // legitimately contain their own "/" and ":" in the document id). Pass it through untouched.
        if (path != null && path.startsWith("content://")) return path;
        String primary = "/storage/emulated/0";
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && ext.getAbsolutePath() != null && !ext.getAbsolutePath().isEmpty())
                primary = ext.getAbsolutePath();
        } catch (Exception ignored) {}
        return FileRules.canonical(path, primary);
    }

    /** A human-readable name for a picked storage tree: the root document's own display name, falling back to
     *  the volume-ish part of its tree id (most DocumentsProviders encode it as "authority:label", e.g.
     *  "primary:Download" or an SD card's "1234-5678:"), and the raw URI as a last resort. */
    private String storageRootLabel(Uri treeUri) {
        String docId = null;
        try {
            docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            android.database.Cursor c = getContentResolver().query(docUri,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String name = c.getString(0);
                        if (name != null && !name.isEmpty()) return name;
                    }
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        if (docId != null) {
            int colon = docId.indexOf(':');
            if (colon >= 0 && colon + 1 < docId.length()) return docId.substring(colon + 1);
            if (colon < 0 && !docId.isEmpty()) return docId;
        }
        return treeUri.toString();
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
    private static final int REQ_PICK_STORAGE_TREE = 4205;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_STORAGE_TREE) {
            final Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (picked == null) { notifyJs("window.onStorageRootPicked && window.onStorageRootPicked(null)"); return; }
            try {
                getContentResolver().takePersistableUriPermission(picked,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
                // some providers (notably a few cloud ones) don't support a persistable grant; the tree URI
                // still works for the rest of this session, it just won't survive a process restart.
            }
            String label = storageRootLabel(picked);
            notifyJs("window.onStorageRootPicked && window.onStorageRootPicked(" + JSONObject.quote(picked.toString()) + "," + JSONObject.quote(label) + ")");
            return;
        }
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
        if (requestCode == REQ_PICK_TEXT) {
            final String tag = pickTextTag;
            final Uri textUri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (textUri == null) return;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        res.put("tag", tag);
                        InputStream in = getContentResolver().openInputStream(textUri);
                        if (in == null) throw new IllegalStateException("could not open the file");
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        try {
                            byte[] b = new byte[16384];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                buf.write(b, 0, n);
                                if (buf.size() > 1024 * 1024) throw new IllegalStateException("the file is over 1 MB, too big for this");
                            }
                        } finally {
                            in.close();
                        }
                        String name = "";
                        try {
                            android.database.Cursor c = getContentResolver().query(textUri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
                            if (c != null) {
                                try { if (c.moveToFirst()) name = c.getString(0); } finally { c.close(); }
                            }
                        } catch (Exception ignored) {}
                        res.put("name", name);
                        res.put("text", buf.toString("UTF-8"));
                    } catch (Exception e) {
                        try { res.put("error", e.getMessage() == null ? "could not read the file" : e.getMessage()); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onTextFilePicked && window.onTextFilePicked(" + res.toString() + ")");
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_FONT) {
            final Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (picked == null) return;
            notifyJs("window.onFontPicked && window.onFontPicked(" + JSONObject.quote(picked.toString()) + ")");
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

    /** "Display over other apps" (a special access with its own settings screen from Android 6 on). */
    private boolean hasOverlayAccess() {
        try {
            return Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
        } catch (Exception e) {
            return false;
        }
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

    private boolean hasLegacyStorageAccess() {
        try {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasWriteSecureSettingsAccess() {
        try {
            return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** "Allow restricted settings" - an app-op (android:read_write_restricted_settings), not a manifest
     *  permission, so there is nothing to declare in AndroidManifest.xml for this one. */
    private boolean hasRestrictedSettingsAccess() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow("android:read_write_restricted_settings", android.os.Process.myUid(), getPackageName());
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

    /** App icons for the list, kept on disk (files/icon_cache/<pkg>_<lastUpdateTime>.png, 96 px) so later launches skip the drawing. */
    private File iconCacheDir(String pack) {
        File d = new File(getFilesDir(), pack == null || pack.isEmpty() ? "icon_cache" : "icon_cache_" + pack.replaceAll("[^A-Za-z0-9._]", "_"));
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static final String[] ICON_PACK_ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.gau.go.launcherex.theme"};
    private final Map<String, Map<String, String>> iconPackFilters = new java.util.HashMap<String, Map<String, String>>();

    /** Installed icon packs (the ADW / Nova / Apex / Go theme convention): [{pkg, label}]. */
    private String listIconPacks() {
        JSONArray out = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (String action : ICON_PACK_ACTIONS) {
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!seen.add(pkg)) continue;
                    out.put(new JSONObject().put("pkg", pkg).put("label", String.valueOf(ri.loadLabel(pm))));
                }
            }
        } catch (Throwable ignored) {}
        return out.toString();
    }

    /** What an icon pack's appfilter.xml maps: "ComponentInfo{pkg/cls}" and bare "pkg" to a drawable name. */
    private synchronized Map<String, String> iconPackFilter(String pack) {
        Map<String, String> m = iconPackFilters.get(pack);
        if (m != null) return m;
        m = new java.util.HashMap<String, String>();
        try {
            Resources res = getPackageManager().getResourcesForApplication(pack);
            org.xmlpull.v1.XmlPullParser xp = null;
            int id = res.getIdentifier("appfilter", "xml", pack);
            if (id != 0) {
                xp = res.getXml(id);
            } else {
                try {
                    xp = android.util.Xml.newPullParser();
                    xp.setInput(res.getAssets().open("appfilter.xml"), "UTF-8");
                } catch (Exception noAsset) {
                    xp = null;
                }
            }
            if (xp != null) {
                int ev;
                StringBuilder backs = new StringBuilder();
                while ((ev = xp.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (ev != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                    String tag = xp.getName();
                    if ("iconback".equals(tag)) {
                        for (int i = 0; i < xp.getAttributeCount(); i++) {
                            if (xp.getAttributeName(i).startsWith("img")) backs.append(backs.length() > 0 ? "," : "").append(xp.getAttributeValue(i));
                        }
                        continue;
                    }
                    if ("iconmask".equals(tag) || "iconupon".equals(tag)) {
                        if (xp.getAttributeCount() > 0 && !m.containsKey("#" + tag)) m.put("#" + tag, xp.getAttributeValue(0));
                        continue;
                    }
                    if ("scale".equals(tag)) {
                        String fct = xp.getAttributeValue(null, "factor");
                        if (fct != null) m.put("#scale", fct);
                        continue;
                    }
                    if (!"item".equals(tag)) continue;
                    String comp = xp.getAttributeValue(null, "component");
                    String dr = xp.getAttributeValue(null, "drawable");
                    if (comp == null || dr == null || dr.isEmpty()) continue;
                    if (!m.containsKey(comp)) m.put(comp, dr);
                    int slash = comp.indexOf('/');
                    if (comp.startsWith("ComponentInfo{") && slash > 14) {
                        String pkg = comp.substring(14, slash);
                        if (!m.containsKey(pkg)) m.put(pkg, dr);
                    }
                }
                if (backs.length() > 0) m.put("#iconback", backs.toString());
            }
        } catch (Throwable ignored) {}
        iconPackFilters.put(pack, m);
        return m;
    }

    private android.graphics.drawable.Drawable iconPackDrawable(String pack, String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Map<String, String> f = iconPackFilter(pack);
            String name = null;
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li != null && li.getComponent() != null) name = f.get("ComponentInfo{" + li.getComponent().getPackageName() + "/" + li.getComponent().getClassName() + "}");
            if (name == null) name = f.get(pkg);
            if (name == null) return null;
            Resources res = pm.getResourcesForApplication(pack);
            int id = res.getIdentifier(name, "drawable", pack);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pack);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The icon of {@code pkg} at px x px: from the icon pack {@code pack} when it has one for the app, else the app's own. */
    private android.graphics.Bitmap renderAppIcon(String pkg, int px, String pack) throws Exception {
        PackageManager pm = getPackageManager();
        android.graphics.drawable.Drawable d = pack == null || pack.isEmpty() ? null : iconPackDrawable(pack, pkg);
        boolean themed = d != null;
        if (d == null) d = pm.getApplicationIcon(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES));
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        if (!themed && pack != null && !pack.isEmpty()) {
            Map<String, String> f = iconPackFilter(pack);
            if (f.containsKey("#iconback") || f.containsKey("#iconmask") || f.containsKey("#iconupon")) {
                return themedFallbackIcon(pack, pkg, d, px, f);
            }
        }
        d.setBounds(0, 0, px, px);
        d.draw(c);
        return b;
    }

    private android.graphics.drawable.Drawable packDrawableByName(String pack, String name) {
        try {
            if (name == null || name.isEmpty()) return null;
            Resources res = getPackageManager().getResourcesForApplication(pack);
            int id = res.getIdentifier(name, "drawable", pack);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pack);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** An app the pack has no icon for, dressed the way the pack dresses such apps: its iconback, the app icon scaled by the pack's factor and cut by its iconmask, then its iconupon. */
    private android.graphics.Bitmap themedFallbackIcon(String pack, String pkg, android.graphics.drawable.Drawable app, int px, Map<String, String> f) {
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        String backs = f.get("#iconback");
        if (backs != null) {
            String[] names = backs.split(",");
            android.graphics.drawable.Drawable back = packDrawableByName(pack, names[Math.abs(pkg.hashCode()) % names.length]);
            if (back != null) {
                back.setBounds(0, 0, px, px);
                back.draw(c);
            }
        }
        float factor = 1f;
        try {
            if (f.containsKey("#scale")) factor = Float.parseFloat(f.get("#scale"));
        } catch (Exception ignored) {}
        if (factor <= 0f || factor > 1.5f) factor = 1f;
        android.graphics.Bitmap fg = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas fc = new android.graphics.Canvas(fg);
        int inset = Math.round(px * (1f - factor) / 2f);
        app.setBounds(inset, inset, px - inset, px - inset);
        app.draw(fc);
        android.graphics.drawable.Drawable mask = packDrawableByName(pack, f.get("#iconmask"));
        if (mask != null) {
            android.graphics.Paint mp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            mp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            android.graphics.Bitmap mb = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            mask.setBounds(0, 0, px, px);
            mask.draw(new android.graphics.Canvas(mb));
            fc.drawBitmap(mb, 0, 0, mp);
        }
        c.drawBitmap(fg, 0, 0, null);
        android.graphics.drawable.Drawable upon = packDrawableByName(pack, f.get("#iconupon"));
        if (upon != null) {
            upon.setBounds(0, 0, px, px);
            upon.draw(c);
        }
        return out;
    }

    /** Clears the cached list icons (every pack); returns how many files went. */
    private int clearIconCache() {
        int n = 0;
        File[] dirs = getFilesDir().listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().startsWith("icon_cache")) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File x : fs) if (x.delete()) n++;
            d.delete();
        }
        return n;
    }

    /** The cached PNG of {@code pkg}'s icon (in {@code pack} when given) as a data: URI, drawn and cached on first use; null when it has none. */
    private String iconDataUri(String pkg, String pack) {
        try {
            long stamp = 0;
            PackageManager pm = getPackageManager();
            try {
                stamp = pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).lastUpdateTime;
            } catch (Exception ignored) {}
            if (pack != null && !pack.isEmpty()) {
                try {
                    stamp += pm.getPackageInfo(pack, 0).lastUpdateTime;
                } catch (Exception ignored) {}
            }
            File dir = iconCacheDir(pack);
            File f = new File(dir, pkg + "_" + stamp + ".png");
            if (!f.isFile()) {
                final String prefix = pkg + "_";
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) {
                    String n = o.getName();
                    if (n.startsWith(prefix) && n.substring(prefix.length()).matches("\\d+\\.png")) o.delete();
                }
                android.graphics.Bitmap b = renderAppIcon(pkg, 96, pack);
                FileOutputStream out = new FileOutputStream(f);
                try {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally {
                    out.close();
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                in.close();
            }
            return "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
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

        // ---------------- Terminal tab (v7.8): shells per backend, Termux, coding agents ----------------

        /**
         * Starts (or reuses) the Terminal's shell for {@code backend}: "app", "priv" or "termux". Returns "starting" (or "error: ...");
         * the outcome arrives as window.onTermStarted(backend, json) with ok, kind, uid, host, cwd, prompt, reused, or message.
         * {@code optsJson}: {cwd, profile, matchEnv} (profile: Termux reads the user's login profile; matchEnv: also
         * sources ~/.bashrc, so aliases and functions from the user's own Termux shell work here too).
         */
        @JavascriptInterface
        public String termStart(final String backend, final String optsJson) {
            final String b = backend == null ? "" : backend;
            if (!b.equals("app") && !b.equals("priv") && !b.equals("termux")) return "error: unknown shell " + b;
            JSONObject o;
            try {
                o = new JSONObject(optsJson == null || optsJson.trim().isEmpty() ? "{}" : optsJson);
            } catch (Exception e) {
                o = new JSONObject();
            }
            final JSONObject opts = o;
            final TermSlot slot;
            synchronized (termGate) {
                TermSlot s = termSlots.get(b);
                if (s == null) {
                    s = new TermSlot(b);
                    termSlots.put(b, s);
                }
                if (s.starting) return "starting";
                s.starting = true;
                slot = s;
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        RishShell sh;
                        String had;
                        synchronized (termGate) {
                            sh = slot.shell;
                            had = slot.kind;
                        }
                        String kind = "app".equals(b) ? "app" : "termux".equals(b) ? "termux" : termPrivKind();
                        if (kind == null) throw new IOException("no privileged working mode is active: connect ADB, Shizuku or Root in Working Modes first");
                        boolean reused = sh != null && sh.isAlive() && kind.equals(had);
                        if (!reused) {
                            if (sh != null) sh.close();          // the working mode changed (Shizuku -> Root, say): a new shell on the new one
                            if ("termux".equals(kind)) checkTermuxReady();
                            final boolean bash = "termux".equals(kind);
                            sh = new RishShell(termSpawner(kind, opts), bash ? 30000 : 15000, bash ? "bash" : "sh");
                            sh.setIdleSink(new RishShell.Sink() {
                                @Override
                                public void onOutput(String text) {
                                    slot.idle(text);
                                }
                            });
                            synchronized (termGate) {
                                slot.shell = sh;
                                slot.kind = kind;
                            }
                            try {
                                String cwd = opts.optString("cwd", "");
                                sh.start(cwd.isEmpty() ? null : cwd);
                                boolean matchEnv = "termux".equals(kind) && opts.optBoolean("matchEnv", true);
                                sh.run(termInitScript(kind, matchEnv), 15000, null);
                            } catch (Throwable failed) {
                                synchronized (termGate) {
                                    if (slot.shell == sh) slot.shell = null;
                                }
                                sh.close();
                                throw failed;
                            }
                        }
                        res.put("ok", true);
                        res.put("reused", reused);
                        res.put("kind", kind);
                        res.put("uid", sh.uid());
                        res.put("host", sh.host());
                        res.put("cwd", sh.cwd());
                        res.put("prompt", sh.prompt());
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("message", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (termGate) {
                            slot.starting = false;
                        }
                    }
                    notifyJs("window.onTermStarted && window.onTermStarted(" + JSONObject.quote(b) + "," + res.toString() + ")");
                }
            });
            return "starting";
        }

        /**
         * Runs one command in the Terminal's {@code backend} shell. Returns "ok", "busy" or "no_shell". Output streams to
         * window.onTermOutput(backend, runId, text) - or, with {@code quiet}, comes back whole in the end report instead -, and the
         * end arrives as window.onTermDone(backend, runId, json) with exit, cwd, prompt, exited, stopped, timedOut, restarted,
         * revived, output (quiet only) or error. {@code envJson} {"ANTHROPIC_API_KEY":"claude"}: a saved key handed to an official
         * command-line tool for this one command only (each provider's own variable, nothing else).
         */
        @JavascriptInterface
        public String termRun(final String backend, final String runId, final String cmd, final boolean quiet, final String envJson) {
            final TermSlot slot;
            final RishShell sh;
            synchronized (termGate) {
                slot = termSlots.get(backend == null ? "" : backend);
                sh = slot == null ? null : slot.shell;
                if (sh == null || !sh.isAlive()) return "no_shell";
                if (slot.running) return "busy";
                slot.running = true;
            }
            final String rid = runId == null ? "" : runId;
            final String argsJs = JSONObject.quote(slot.backend) + "," + JSONObject.quote(rid);
            String command = cmd == null ? "" : cmd;
            String env = termSecretEnv(envJson);
            if (!env.isEmpty()) command = "(\n" + env + command + "\n)";       // a subshell: the keys don't stay in the session
            final String full = command;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject done = new JSONObject();
                    final StringBuilder captured = quiet ? new StringBuilder() : null;
                    final int[] shown = {0};
                    try {
                        RishShell.Result r = sh.run(full, TERM_COMMAND_TIMEOUT_MS, new RishShell.Sink() {
                            @Override
                            public void onOutput(String text) {
                                if (captured != null) {
                                    int room = TERM_QUIET_CAP - captured.length();
                                    if (room > 0) captured.append(text.length() > room ? text.substring(0, room) : text);
                                    return;
                                }
                                if (shown[0] > TERM_OUTPUT_CAP) return;
                                int room = TERM_OUTPUT_CAP - shown[0];
                                if (text.length() > room) {
                                    text = text.substring(0, room) + "\n... output cut off here (the command keeps running; tap STOP to end it)\n";
                                    shown[0] = TERM_OUTPUT_CAP + 1;
                                } else {
                                    shown[0] += text.length();
                                }
                                slot.out.add(argsJs, text);
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
                        if (captured != null) done.put("output", captured.toString());
                    } catch (Throwable t) {
                        try {
                            done.put("error", errMsg(t));
                            done.put("exited", !sh.isAlive());
                            done.put("exit", -1);
                        } catch (Exception ignored) {}
                    }
                    slot.out.flush();
                    synchronized (termGate) {
                        slot.running = false;
                    }
                    notifyJs("window.onTermDone && window.onTermDone(" + argsJs + "," + done.toString() + ")");
                }
            });
            return "ok";
        }

        /** Ends the command running in {@code backend}'s shell (SIGINT, then TERM, then KILL if it ignores that). */
        @JavascriptInterface
        public void termStop(String backend) {
            RishShell sh;
            synchronized (termGate) {
                TermSlot s = termSlots.get(backend == null ? "" : backend);
                sh = s == null ? null : s.shell;
            }
            if (sh != null) sh.stop();
        }

        /** Closes {@code backend}'s shell (the next termStart opens a new one). */
        @JavascriptInterface
        public void termClose(String backend) {
            final RishShell sh;
            synchronized (termGate) {
                TermSlot s = termSlots.get(backend == null ? "" : backend);
                sh = s == null ? null : s.shell;
                if (s != null) s.shell = null;
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

        /** Termux's state, the open shells and the phone, for the Terminal (quick: no process is started). */
        @JavascriptInterface
        public String termInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("termux", TermuxBridge.status(MainActivity.this));
                JSONObject sessions = new JSONObject();
                synchronized (termGate) {
                    for (TermSlot s : termSlots.values()) {
                        JSONObject j = new JSONObject();
                        boolean alive = s.shell != null && s.shell.isAlive();
                        j.put("alive", alive);
                        j.put("kind", s.kind);
                        j.put("running", s.running);
                        if (alive) {
                            j.put("cwd", s.shell.cwd());
                            j.put("prompt", s.shell.prompt());
                            j.put("uid", s.shell.uid());
                        }
                        sessions.put(s.backend, j);
                    }
                }
                o.put("sessions", sessions);
                o.put("home", new File(getFilesDir(), "home").getAbsolutePath());
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", Build.VERSION.RELEASE);
                o.put("model", Build.MODEL);
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** Asks for Termux's "Run commands in Termux environment" permission. "granted", "not_installed" or "asked" (answer: window.onTermuxPermission). */
        @JavascriptInterface
        public String termuxRequestPermission() {
            JSONObject st = TermuxBridge.status(MainActivity.this);
            if (!st.optBoolean("installed", false)) return "not_installed";
            if (st.optBoolean("permission", false)) return "granted";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        requestPermissions(new String[]{TermuxBridge.PERMISSION}, REQ_TERMUX_PERM);
                    } catch (Exception e) {
                        notifyJs("window.onTermuxPermission && window.onTermuxPermission(false,false)");
                    }
                }
            });
            return "asked";
        }

        /**
         * Checks that Termux accepts commands from this app (permission granted AND allow-external-apps=true in Termux). Returns
         * "started" or "error: ..."; the answer is window.onTermuxProbe(reqId, json{ok, message, errmsg}).
         */
        @JavascriptInterface
        public String termuxProbe(final String reqId) {
            final String id = reqId == null ? "" : reqId;
            final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean(false);
            final Runnable timeout = new Runnable() {
                @Override
                public void run() {
                    if (!answered.compareAndSet(false, true)) return;
                    JSONObject o = new JSONObject();
                    try {
                        o.put("ok", false);
                        o.put("timedOut", true);
                        o.put("message", "Termux did not answer. Open Termux once (it finishes installing on first start), then try again.");
                    } catch (Exception ignored) {}
                    notifyJs("window.onTermuxProbe && window.onTermuxProbe(" + JSONObject.quote(id) + "," + o.toString() + ")");
                }
            };
            try {
                checkTermuxReady();
                TermuxBridge.run(MainActivity.this, "echo adbmgr-ok", true, "ADB App Manager check", new TermuxLink.Callback() {
                    @Override
                    public void onResult(TermuxLink.Result r) {
                        if (!answered.compareAndSet(false, true)) return;
                        rishHandler.removeCallbacks(timeout);
                        JSONObject o = new JSONObject();
                        try {
                            boolean ok = r.ran() && r.stdout.contains("adbmgr-ok");
                            o.put("ok", ok);
                            o.put("message", ok ? "" : TermuxLink.failureText(r));
                            o.put("errmsg", r.errmsg);
                            o.put("needsExternalApps", r.errmsg.contains("allow-external-apps"));
                        } catch (Exception ignored) {}
                        notifyJs("window.onTermuxProbe && window.onTermuxProbe(" + JSONObject.quote(id) + "," + o.toString() + ")");
                    }
                });
                rishHandler.postDelayed(timeout, 12000);
            } catch (IOException e) {
                return "error: " + e.getMessage();
            }
            return "started";
        }

        /** Opens a command (or, when empty, a plain shell) in a real Termux window: for full-screen programs and sign-in flows. */
        @JavascriptInterface
        public String termuxOpen(final String cmd) {
            try {
                checkTermuxReady();
            } catch (IOException e) {
                return "error: " + e.getMessage();
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        TermuxBridge.openInTermux(MainActivity.this, cmd);
                    } catch (Exception e) {
                        notifyJs("window.onTermuxOpenFailed && window.onTermuxOpenFailed(" + JSONObject.quote(errMsg(e)) + ")");
                    }
                }
            });
            return "ok";
        }

        /**
         * One HTTPS call for a coding agent. {@code specJson}: {url, method, headers, body, stream, auth}; with {@code auth} (a
         * provider id) the saved key is added, and only if the URL is that provider's own. Returns "started" or "error: ...".
         * Streamed text arrives as window.onAiChunk(reqId, text); the end as window.onAiDone(reqId, json{status, body, error,
         * cancelled, headers}).
         */
        @JavascriptInterface
        public String aiRequest(final String reqId, final String specJson) {
            final String id = reqId == null ? "" : reqId;
            final AiHttp.Request req = new AiHttp.Request();
            try {
                JSONObject spec = new JSONObject(specJson == null ? "{}" : specJson);
                req.url = spec.getString("url");
                if (!req.url.startsWith("https://") && !req.url.startsWith("http://")) return "error: not a web address";
                req.method = spec.optString("method", "POST");
                req.stream = spec.optBoolean("stream", false);
                if (spec.has("body") && !spec.isNull("body")) {
                    Object body = spec.get("body");
                    req.body = body instanceof String ? (String) body : body.toString();
                }
                if (spec.has("readTimeoutMs")) req.readTimeoutMs = Math.max(5000, Math.min(900000, spec.optInt("readTimeoutMs", 300000)));
                JSONObject h = spec.optJSONObject("headers");
                if (h != null) {
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (!AgentRules.reservedHeader(k)) req.headers.put(k, h.optString(k));
                    }
                }
                String refused = aiAuthorize(req, spec.optString("auth", ""));
                if (refused != null) return "error: " + refused;
            } catch (Exception e) {
                return "error: " + errMsg(e);
            }
            final AiHttp call = new AiHttp();
            aiCalls.put(id, call);
            final JsStream stream = new JsStream("onAiChunk");
            final String argsJs = JSONObject.quote(id);
            aiExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    AiHttp.Response r = call.execute(req, new AiHttp.Sink() {
                        @Override
                        public void onChunk(String text) {
                            stream.add(argsJs, text);
                        }
                    });
                    stream.flush();
                    aiCalls.remove(id);
                    aiFinish(id, r, req.secret);
                }
            });
            return "started";
        }

        /** Ends an agent's HTTPS call (window.onAiDone then reports it cancelled). */
        @JavascriptInterface
        public void aiCancel(String reqId) {
            AiHttp c = aiCalls.get(reqId == null ? "" : reqId);
            if (c != null) c.cancel();
        }

        /**
         * Tests a key with the provider's own cheap call (the model list, or who-am-i) and saves it when it works. {@code base}: the
         * address of the user's own server (Jan, AnythingLLM, Ollama). Returns "started" or "error: ..."; the answer is
         * window.onAiKeyTest(reqId, json{ok, status, body, error, saved, hint, base}).
         */
        @JavascriptInterface
        public String aiTestKey(final String reqId, final String provider, final String key, final String base) {
            final AgentRules.Provider p = AgentRules.find(provider);
            if (p == null) return "error: unknown provider";
            final String k = key == null ? "" : key.trim();
            if (!AgentRules.keyLooksValid(k)) return "error: that does not look like a key";
            String b = "";
            if (p.ownServer()) {
                try {
                    b = AgentRules.normalizeBase(base);
                } catch (IllegalArgumentException e) {
                    return "error: " + e.getMessage();
                }
            } else if (k.isEmpty()) {
                return "error: enter the key first";
            }
            final String fb = b;
            final String id = reqId == null ? "" : reqId;
            final AiHttp call = new AiHttp();
            aiCalls.put(id, call);
            aiExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    JSONObject o = new JSONObject();
                    try {
                        AiHttp.Request req = new AiHttp.Request();
                        req.method = "GET";
                        req.url = AgentRules.testUrl(p, fb);
                        req.readTimeoutMs = 30000;
                        for (String[] h : p.extra) req.headers.put(h[0], h[1]);
                        if (!k.isEmpty()) req.headers.put(p.header, p.prefix + k);
                        AiHttp.Response r = call.execute(req, null);
                        boolean ok = r.status >= 200 && r.status < 300;
                        o.put("ok", ok);
                        o.put("status", r.status);
                        o.put("body", r.body.length() > 1000000 ? r.body.substring(0, 1000000) : r.body);
                        o.put("error", AgentRules.scrub(r.error, k));
                        o.put("cancelled", r.cancelled);
                        o.put("base", fb);
                        o.put("hint", AgentRules.hint(k));
                        JSONObject hh = new JSONObject();
                        for (Map.Entry<String, String> e : r.headers.entrySet()) hh.put(e.getKey(), e.getValue());
                        o.put("headers", hh);
                        if (ok) {
                            try {
                                vault().put(p.id, k);
                                if (p.ownServer()) vault().setBase(p.id, fb);
                                o.put("saved", true);
                            } catch (Throwable t) {
                                o.put("saved", false);
                                o.put("saveError", errMsg(t));
                            }
                        }
                    } catch (Throwable t) {
                        try {
                            o.put("ok", false);
                            o.put("error", AgentRules.scrub(errMsg(t), k));
                        } catch (Exception ignored) {}
                    }
                    aiCalls.remove(id);
                    notifyJs("window.onAiKeyTest && window.onAiKeyTest(" + JSONObject.quote(id) + "," + o.toString() + ")");
                }
            });
            return "started";
        }

        /** Saves a key (and server address) without testing it ("Save anyway"). Returns "ok" or "error: ...". */
        @JavascriptInterface
        public String aiSaveKey(String provider, String key, String base) {
            AgentRules.Provider p = AgentRules.find(provider);
            if (p == null) return "error: unknown provider";
            String k = key == null ? "" : key.trim();
            if (!AgentRules.keyLooksValid(k)) return "error: that does not look like a key";
            try {
                if (p.ownServer()) vault().setBase(p.id, AgentRules.normalizeBase(base));
                else if (k.isEmpty()) return "error: enter the key first";
                vault().put(p.id, k);
                return "ok";
            } catch (Throwable t) {
                return "error: " + errMsg(t);
            }
        }

        /** Forgets a provider's key and server address. */
        @JavascriptInterface
        public void aiForgetKey(String provider) {
            if (AgentRules.find(provider) != null) vault().remove(provider);
        }

        /** Which providers have a key / server saved: hints only, never the keys. */
        @JavascriptInterface
        public String aiVaultStatus() {
            return vault().status().toString();
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

        /**
         * A broad, one-shot snapshot for the About tab's Device Specs card: hardware, software, system, battery,
         * network, camera and sensors. Every read here is public and context-free - BatteryManager, world-readable
         * /proc and /sys nodes (via {@link #readWorldReadable}, {@link #cpuInfoJson()}), PackageManager/
         * ActivityManager feature queries ({@link #gpuInfoJson()}), and CameraManager/SensorManager static
         * characteristics, none of which need the camera permission (only opening one for capture does) - so this
         * works the same with or without a working mode connected, unlike everything shell-based in this file.
         */
        @JavascriptInterface
        public String getDeviceSpecs() {
            JSONObject res = new JSONObject();
            try {
                JSONObject hw = new JSONObject();
                hw.put("model", Build.MODEL);
                hw.put("manufacturer", Build.MANUFACTURER);
                hw.put("brand", Build.BRAND);
                mergeInto(hw, cpuInfoJson());
                mergeInto(hw, gpuInfoJson());
                try {
                    String memRaw = readWorldReadable("/proc/meminfo");
                    if (!memRaw.isEmpty()) hw.put("ramTotalKb", MemStats.parse(memRaw).totalKb);
                } catch (Throwable ignored) {}
                try {
                    android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm != null && wm.getDefaultDisplay() != null) {
                        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(dm);
                        hw.put("screenWidthPx", dm.widthPixels);
                        hw.put("screenHeightPx", dm.heightPixels);
                        hw.put("densityDpi", dm.densityDpi);
                        float refresh = wm.getDefaultDisplay().getRefreshRate();
                        if (refresh > 0) hw.put("refreshRateHz", refresh);
                    }
                } catch (Throwable ignored) {}
                try {
                    android.os.StatFs sfs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                    hw.put("storageTotalBytes", sfs.getTotalBytes());
                    hw.put("storageFreeBytes", sfs.getAvailableBytes());
                } catch (Throwable ignored) {}
                res.put("hardware", hw);

                JSONObject sw = new JSONObject();
                sw.put("androidRelease", Build.VERSION.RELEASE);
                sw.put("sdk", Build.VERSION.SDK_INT);
                if (Build.VERSION.SECURITY_PATCH != null) sw.put("securityPatch", Build.VERSION.SECURITY_PATCH);
                sw.put("buildId", Build.ID);
                sw.put("bootloader", Build.BOOTLOADER);
                try {
                    String kernel = readWorldReadable("/proc/version").trim();
                    if (!kernel.isEmpty()) sw.put("kernel", kernel);
                } catch (Throwable ignored) {}
                res.put("software", sw);

                JSONObject sys = new JSONObject();
                sys.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
                sys.put("locale", java.util.Locale.getDefault().toLanguageTag());
                sys.put("timezone", java.util.TimeZone.getDefault().getID());
                res.put("system", sys);

                res.put("battery", tmBatteryJson());

                JSONObject net = new JSONObject();
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                    if (ni != null) {
                        net.put("type", ni.getTypeName());
                        net.put("connected", ni.isConnected());
                    }
                } catch (Throwable ignored) {}
                try {
                    String netRaw = readWorldReadable("/proc/net/dev");
                    if (!netRaw.isEmpty()) {
                        JSONArray ifaceArr = new JSONArray();
                        for (NetStats.Iface i : NetStats.parse(netRaw).ifaces) {
                            if (!"lo".equals(i.name)) ifaceArr.put(i.name);
                        }
                        net.put("interfaces", ifaceArr);
                    }
                } catch (Throwable ignored) {}
                res.put("network", net);

                JSONArray cams = new JSONArray();
                try {
                    android.hardware.camera2.CameraManager camMgr = (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
                    if (camMgr != null) {
                        for (String id : camMgr.getCameraIdList()) {
                            android.hardware.camera2.CameraCharacteristics c = camMgr.getCameraCharacteristics(id);
                            JSONObject cj = new JSONObject();
                            Integer facing = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                            cj.put("facing", facing == null ? "unknown"
                                    : facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT ? "front"
                                    : facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK ? "back" : "external");
                            android.util.Size size = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                            if (size != null) cj.put("megapixels", Math.round(size.getWidth() * size.getHeight() / 100000.0) / 10.0);
                            Boolean flash = c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                            cj.put("flash", flash != null && flash);
                            cams.put(cj);
                        }
                    }
                } catch (Throwable ignored) {}
                res.put("cameras", cams);

                JSONArray sensors = new JSONArray();
                try {
                    android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
                    if (sm != null) {
                        for (android.hardware.Sensor s : sm.getSensorList(android.hardware.Sensor.TYPE_ALL)) {
                            JSONObject sj = new JSONObject();
                            sj.put("name", s.getName());
                            sj.put("vendor", s.getVendor());
                            sensors.put(sj);
                        }
                    }
                } catch (Throwable ignored) {}
                res.put("sensors", sensors);

                res.put("ok", true);
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** What a split APK has to match to suit this phone: its CPU architectures (best first), screen density and languages (best first). */
        @JavascriptInterface
        public String getDeviceProfile() {
            JSONObject o = new JSONObject();
            try {
                JSONArray abis = new JSONArray();
                for (String a : Build.SUPPORTED_ABIS) abis.put(a);
                o.put("abis", abis);
                o.put("dpi", getResources().getDisplayMetrics().densityDpi);
                JSONArray loc = new JSONArray();
                android.os.LocaleList ll = getResources().getConfiguration().getLocales();
                for (int i = 0; i < ll.size(); i++) loc.put(ll.get(i).toLanguageTag());
                o.put("locales", loc);
                o.put("sdk", Build.VERSION.SDK_INT);
            } catch (Exception ignored) {}
            return o.toString();
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
                    o.put("name", uninstalledLabel(pm, pkg));
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

        /** The real app name for a package that's uninstalled-for-this-user but still has a stub on this system
         *  partition - the common case for a system app removed for one user, not all of them. MATCH_UNINSTALLED_PACKAGES
         *  can still resolve a label from that stub; falls back to the bare package name when there is nothing left
         *  to read a label from (a non-system package, or one genuinely gone with no stub at all). */
        private String uninstalledLabel(PackageManager pm, String pkg) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null && label.length() > 0) return label.toString();
            } catch (Exception ignored) {}
            return pkg;
        }

        @JavascriptInterface
        public String executeAppAction(String action, String pkg) {
            try {
                // Every legitimate caller gets pkg from PackageManager, but a saved/quick list is free-form
                // text the user typed; everything below concatenates pkg into a shell command (or a monkey
                // fallback), so refuse anything that isn't a well-formed package name before it gets there.
                if (!BackupScripts.isPackageName(pkg)) return "Error: \"" + pkg + "\" is not a valid package name";
                // Batch App Ops: "appops:OP=mode,OP=mode" sets each op on this app
                if (action != null && action.startsWith("appops:")) return batchAppOps(action.substring(7), pkg);
                // Batch Command: "custom:<command>" runs the user's command with $package replaced by this app's package name
                if (action != null && action.startsWith("custom:")) {
                    String tpl = action.substring(7).trim();
                    if (tpl.isEmpty()) return "Error: the command is empty";
                    return runShellAction(tpl.replace("$package", pkg));
                }
                if ("freeze".equals(action)) return runShellAction("pm disable-user " + pkg);
                if ("unfreeze".equals(action)) {
                    String r1 = runShellAction("pm enable " + pkg);
                    String r2 = runShellAction("pm unsuspend " + pkg);
                    String t1 = flagText(r1), t2 = flagText(r2);
                    String combined = t1 + (t1.isEmpty() || t2.isEmpty() ? "" : "\n") + t2;
                    return flagged(flagOk(r1) && flagOk(r2), combined);
                }
                if ("suspend".equals(action)) return runShellAction("pm suspend " + pkg);
                if ("unsuspend".equals(action)) return runShellAction("pm unsuspend " + pkg);
                if ("force_stop".equals(action)) return runShellAction("am force-stop " + pkg);
                if ("clear_data".equals(action)) return runShellAction("pm clear " + pkg);
                if ("uninstall".equals(action)) return uninstallForUser(pkg, false);
                if ("uninstall_keep_data".equals(action)) return uninstallForUser(pkg, true);
                if ("uninstall_updates".equals(action)) return runShellAction("pm uninstall-system-updates " + pkg);
                if ("reinstall".equals(action)) return runShellAction("pm install-existing " + pkg);
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

        /** Stops the running batch after whichever app it is already on; that one call can't be interrupted
         *  once started, so every app not reached yet is left exactly as it was. */
        @JavascriptInterface
        public void appBatchCancel() { appBatchCancel = true; }

        /**
         * Runs {@link #executeAppAction} across every package in pkgsJson (a JSON array of package name
         * strings) one at a time, off the page's thread - the point of this method existing at all, since
         * executeAppAction's own shell round-trip blocks whichever thread calls it, and running the whole loop
         * on the page's thread (as the JS side used to, one bridge call per app with only a setTimeout(0)
         * between them) still froze the page for the entire loop: the page's thread is also the WebView's own,
         * so nothing on it - including a repaint - runs while a call made from it is in flight.
         * Returns "started" (or "busy" while a previous call is still running); progress arrives as
         * window.onAppBatchProgress(i, total, pkg) before each app's own action runs, and the end as
         * window.onAppBatchDone({action, total, done, cancelled, rows: [{pkg, output, success}]}).
         */
        @JavascriptInterface
        public String appActionBatch(final String action, final String pkgsJson) {
            synchronized (appBatchLock) {
                if (appBatchBusy) return "busy";
                appBatchBusy = true;
                appBatchCancel = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        JSONArray pkgs = new JSONArray(pkgsJson);
                        int total = pkgs.length();
                        JSONArray rows = new JSONArray();
                        int done = 0;
                        boolean cancelled = false;
                        int i = 0;
                        for (; i < total; i++) {
                            if (appBatchCancel) { cancelled = true; break; }
                            String pkg = pkgs.optString(i, "");
                            notifyJs("window.onAppBatchProgress && window.onAppBatchProgress(" + i + "," + total + "," + JSONObject.quote(pkg) + ")");
                            String flagged = executeAppAction(action, pkg);
                            boolean ok = flagOk(flagged);
                            String out = flagText(flagged);
                            if (ok) done++;
                            rows.put(new JSONObject().put("pkg", pkg).put("output", out).put("success", ok));
                        }
                        res.put("action", action);
                        res.put("total", total);
                        res.put("done", done);
                        res.put("cancelled", cancelled);
                        res.put("rows", rows);
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        synchronized (appBatchLock) {
                            appBatchBusy = false;
                        }
                    }
                    notifyJs("window.onAppBatchDone && window.onAppBatchDone(" + res.toString() + ")");
                }
            })) {
                synchronized (appBatchLock) {
                    appBatchBusy = false;
                }
                return "error";
            }
            return "started";
        }

        // A single '\u0001' + ('1'|'0') flag glued onto the front of a pm/am result, read from the command's
        // own exit status rather than guessed from its wording - an OEM shell (Samsung among them) can reword
        // or silence what these commands print in ways no fixed keyword list keeps up with, but it can't touch
        // the exit code the command itself sets. Every caller that only ever displays this text (the Terminal,
        // UninstallHints' string matching, etc.) must read it from flagText(...), never the raw return value.
        private static final char RC_FLAG = '\u0001';

        private String flagged(boolean ok, String text) {
            return RC_FLAG + (ok ? "1" : "0") + (text == null ? "" : text);
        }

        private boolean flagOk(String s) {
            return s != null && s.length() >= 2 && s.charAt(0) == RC_FLAG && s.charAt(1) == '1';
        }

        private String flagText(String s) {
            return (s != null && s.length() >= 2 && s.charAt(0) == RC_FLAG) ? s.substring(2) : (s == null ? "" : s);
        }

        /** Runs one pm/am command through the active privileged shell and reports success from its own exit
         *  status (see {@link #RC_FLAG}), not from sniffing what it printed. */
        /** One app's share of a Batch App Ops run: spec is "OP=mode,OP=mode" (names and modes are checked before they reach the shell). */
        private String batchAppOps(String spec, String pkg) {
            StringBuilder text = new StringBuilder();
            boolean all = true;
            int n = 0;
            for (String pair : spec.split(",")) {
                pair = pair.trim();
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String op = eq > 0 ? pair.substring(0, eq).trim() : "";
                String mode = eq > 0 ? pair.substring(eq + 1).trim() : "";
                if (!op.matches("[A-Z][A-Z0-9_]+") || !mode.matches("allow|ignore|deny|foreground|default")) {
                    all = false;
                    text.append(pair).append(": not a valid app op and value\n");
                    continue;
                }
                String r = runShellAction("appops set " + pkg + " " + op + " " + mode);
                boolean ok = flagOk(r);
                if (!ok) all = false;
                text.append(op).append(" -> ").append(mode).append(": ").append(ok ? "ok" : flagText(r).trim()).append("\n");
                n++;
            }
            if (n == 0 && all) return flagged(false, "No app ops to set");
            return flagged(all, text.toString().trim());
        }

        private String runShellAction(String cmd) {
            String marker = "__RC" + System.nanoTime() + "__";
            String raw = executeShell(cmd + "; echo \"" + marker + ":$?\"");
            if (raw == null) raw = "";
            int idx = raw.lastIndexOf(marker + ":");
            if (idx < 0) return flagged(true, raw); // the backend didn't run a real shell - nothing to parse, assume ok
            String text = raw.substring(0, idx);
            while (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            int rc;
            try { rc = Integer.parseInt(raw.substring(idx + marker.length() + 1).trim()); }
            catch (NumberFormatException e) { rc = 0; }
            return flagged(rc == 0, text);
        }

        /** A failed {@code pm uninstall} line, with a plain-language note appended when it is one of the common,
         *  cryptic refusals {@link UninstallHints} recognizes (most often: this needs actual root). */
        private String withUninstallHint(String flaggedOut) {
            String out = flagText(flaggedOut);
            String advice = UninstallHints.advice(out);
            String text = advice.isEmpty() ? out : out.trim() + "\n\nNote: " + advice;
            return flagged(flagOk(flaggedOut), text);
        }

        /** {@code pm uninstall [-k] --user 0 <pkg>}, with one automatic fallback: when that is refused with
         *  Android's own "only root can delete system app for a particular user" line and this isn't already
         *  root mode, it retries through {@link #attemptSystemlessUninstall} - the direct Binder call
         *  (IPackageManager.deletePackageAsUser) App Manager and Canta use for the same refusal, run as a
         *  standalone app_process under whatever privileged shell (ADB or Shizuku) is already active. That
         *  fallback only applies to a full removal, not the keep-data variant. */
        private String uninstallForUser(String pkg, boolean keepData) {
            String cmd = keepData ? ("pm uninstall -k --user 0 " + pkg) : ("pm uninstall --user 0 " + pkg);
            String result = runShellAction(cmd);
            if (flagOk(result)) return result;
            if (!keepData && UninstallHints.rootRequired(flagText(result)) && !"root".equals(resolveExecMode())) {
                String binder = attemptSystemlessUninstall(pkg);
                if (binder != null) return binder;
            }
            return withUninstallHint(result);
        }

        /** The systemless-uninstall workaround: spawns {@code app_process} on this app's own already-installed
         *  APK (so the call lands on {@link SystemlessUninstallRunner} with zero extra build/dex steps) under
         *  whichever privileged shell {@link #executeShell} is already using, and has it call
         *  IPackageManager.deletePackageAsUser directly over Binder with the DELETE_SYSTEM_APP flag - the one
         *  bit the `pm uninstall` command line never exposes. Removes the app for this user only; it stays in
         *  the system partition, same as a normal `pm uninstall --user 0` does for a non-system app. Returns
         *  null (never a failure string) when this device/mode can't even attempt it, so the caller falls back
         *  to the plain pm-uninstall refusal it already has. */
        private String attemptSystemlessUninstall(String pkg) {
            String apkPath;
            try {
                apkPath = getPackageManager().getApplicationInfo(getPackageName(), 0).sourceDir;
            } catch (Exception e) {
                return null;
            }
            if (apkPath == null || apkPath.isEmpty()) return null;
            String out = flagText(runShellAction("CLASSPATH=\"" + apkPath + "\" app_process /system/bin "
                    + SystemlessUninstallRunner.class.getName() + " " + pkg + " 0"));
            if (out.contains("RESULT:OK")) {
                return flagged(true, "Success\n\nRemoved via a direct Binder call (IPackageManager.deletePackageAsUser) "
                        + "since the shell-level `pm uninstall` needs actual root for a system app - it stays in the "
                        + "system partition but is gone for this user, the same result a normal uninstall gives for "
                        + "any other app.");
            }
            if (out.contains("RESULT:FAIL:") || out.contains("RESULT:ERROR:")) {
                return flagged(false, out.trim());
            }
            return null;
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
                    boolean ok = !launchOutputFailed(output) && !output.toLowerCase().contains("exception occurred");
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
                    boolean dispatched = !launchOutputFailed(keyOut) && !kl.contains("not found");
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
                        // straight to this app's own switch where the phone supports it, else the list of apps
                        Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) {}
                    }
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

        /** Scans storage for .apk/.apks/.apkm/.xapk files. Answer: window.onApkScan(json); progress: window.onApkScanProgress(json). */
        @JavascriptInterface
        public void scanApkFiles() {
            runApkScan();
        }

        /** Searches storage for .ttf / .otf files for the font setting. Answer: window.onFontScan(json); progress: window.onFontScanProgress(json). */
        @JavascriptInterface
        public void scanFonts() {
            runFontScan();
        }

        /** Lets the user choose a small text file (up to 1 MB). Answer: window.onTextFilePicked({tag, name, text} or {tag, error}). */
        @JavascriptInterface
        public void pickTextFile(final String tag) {
            pickTextTag = tag == null ? "" : tag;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_PICK_TEXT);
                }
            });
        }

        /** Lets the user choose a font file with Android's file chooser. Answer: window.onFontPicked(ref). */
        @JavascriptInterface
        public void pickFontFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_PICK_FONT);
                }
            });
        }

        /** Checks the font in ref (a path, or what the file chooser gave) and keeps a copy to look at. Answer: window.onFontPreview(json). */
        @JavascriptInterface
        public void fontPreview(final String ref) {
            submitJob(new Runnable() {
                @Override
                public void run() {
                    notifyJs("window.onFontPreview && window.onFontPreview(" + JSONObject.quote(stageFont(ref).toString()) + ")");
                }
            });
        }

        /** The font as base64: which is "preview" (the one being looked at) or "current" (the one in use). Empty when there is none. */
        @JavascriptInterface
        public String fontData(String which) {
            try {
                File f = new File(fontDir(), "current".equals(which) ? "current.bin" : "preview.bin");
                long len = f.length();
                if (!f.isFile() || len <= 0 || len > FontScan.MAX_FONT_BYTES) return "";
                byte[] data = new byte[(int) len];
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
                try { in.readFully(data); } finally { in.close(); }
                return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            } catch (Throwable e) {                                         // includes OutOfMemoryError on a small phone: the page then keeps the system font
                return "";
            }
        }

        /** Makes the font that was looked at the one in use. Answer: the font's details {ok, family, style, name, kind, variable, size}, or {ok:false, error}. */
        @JavascriptInterface
        public String fontApply() {
            JSONObject res = new JSONObject();
            try {
                synchronized (fontLock) {
                    File dir = fontDir();
                    File p = new File(dir, "preview.bin"), pm = new File(dir, "preview.json"), c = new File(dir, "current.bin"), cm = new File(dir, "current.json");
                    if (!p.isFile() || !pm.isFile()) throw new IllegalStateException("There is no font to use.");
                    if (!p.renameTo(c) || !pm.renameTo(cm)) throw new IllegalStateException("The font could not be stored.");
                    res = new JSONObject(readTextFile(cm));
                }
            } catch (Exception e) {
                try { res = new JSONObject(); res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "failed"); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Back to the system font: removes the stored font (and the one that was being looked at). */
        @JavascriptInterface
        public String fontClear() {
            JSONObject res = new JSONObject();
            try {
                synchronized (fontLock) {
                    File dir = fontDir();
                    for (String n : new String[]{"preview.bin", "preview.json", "preview.bin.part", "preview.json.part", "current.bin", "current.json"}) new File(dir, n).delete();
                }
                res.put("ok", true);
            } catch (Exception e) {
                try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /**
         * Reads the package name and version of the found .apk files and finds identical copies (same size and SHA-256) and older versions of a package that is there
         * in a newer one (see ApkFlags). Nothing is changed. Answer: window.onApkAnalyze({files: [{path, pkg, vn, vc, dupOf, older, newest, newestPath}], ms}).
         */
        @JavascriptInterface
        public void apkAnalyze(final String pathsJson) {
            synchronized (analyzeLock) {
                if (analyzeBusy) return;                                   // one analysis at a time: the answer of the running one comes
                analyzeBusy = true;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    long t0 = System.currentTimeMillis();
                    try {
                        JSONArray in = new JSONArray(pathsJson);
                        List<ApkFlags.Item> items = new ArrayList<ApkFlags.Item>();
                        PackageManager pm = getPackageManager();
                        java.util.Set<String> seenFiles = new java.util.HashSet<String>();
                        for (int i = 0; i < in.length() && i < 2000; i++) {
                            File f = new File(in.optString(i, ""));
                            if (!f.isFile() || !f.canRead()) continue;
                            String canon;
                            try { canon = f.getCanonicalPath(); } catch (IOException e) { canon = f.getPath(); }
                            if (!seenFiles.add(canon)) continue;           // the same file under two names (a link, a second mount point) is one file, never its own duplicate
                            ApkFlags.Item it = new ApkFlags.Item();
                            it.path = f.getPath(); it.size = f.length(); it.mtime = f.lastModified();
                            if (f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                                try {
                                    int fl = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
                                    android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), fl);
                                    if (pi != null) {
                                        it.pkg = pi.packageName == null ? "" : pi.packageName;
                                        it.versionName = pi.versionName == null ? "" : pi.versionName;
                                        it.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                                        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                                            android.content.pm.Signature[] sg = pi.signingInfo.getApkContentsSigners();
                                            if (sg != null && sg.length > 0) {
                                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                                StringBuilder sb = new StringBuilder();
                                                for (byte b : md.digest(sg[0].toByteArray())) sb.append(String.format("%02x", b & 0xFF));
                                                it.signer = sb.toString();
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                try {                                      // which kinds of phone: the lib/<abi>/ folders inside
                                    java.util.TreeSet<String> abis = new java.util.TreeSet<String>();
                                    java.util.zip.ZipFile z = new java.util.zip.ZipFile(f);
                                    try {
                                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                                        int guard = 0;
                                        while (en.hasMoreElements() && ++guard < 20000) {
                                            String nm = en.nextElement().getName();
                                            if (nm.startsWith("lib/")) { int sl = nm.indexOf('/', 4); if (sl > 4) abis.add(nm.substring(4, sl)); }
                                        }
                                    } finally { z.close(); }
                                    it.abis = String.join(",", abis);
                                } catch (Throwable ignored) {}
                            }
                            items.add(it);
                        }
                        boolean complete = ApkFlags.hashSameSizes(items, 400L * 1024 * 1024, t0 + 45000);
                        res.put("partial", !complete);
                        ApkFlags.compute(items);
                        JSONArray out = new JSONArray();
                        for (ApkFlags.Item it : items) {
                            if (it.pkg.isEmpty() && it.duplicateOf == null) continue;
                            JSONObject o = new JSONObject().put("path", it.path).put("pkg", it.pkg).put("vn", it.versionName).put("vc", it.versionCode);
                            if (it.duplicateOf != null) o.put("dupOf", it.duplicateOf);
                            if (it.older) { o.put("older", true); o.put("newest", it.newestVersion); o.put("newestPath", it.newestPath); }
                            out.put(o);
                        }
                        res.put("files", out);
                    } catch (Throwable t) {
                        try { res.put("files", new JSONArray()); res.put("error", String.valueOf(t.getMessage())); } catch (Exception ignored) {}
                    }
                    try { res.put("ms", System.currentTimeMillis() - t0); } catch (Exception ignored) {}
                    synchronized (analyzeLock) { analyzeBusy = false; }
                    notifyJs("window.onApkAnalyze && window.onApkAnalyze(" + res.toString() + ")");
                }
            })) {
                synchronized (analyzeLock) { analyzeBusy = false; }
            }
        }

        /**
         * Deletes a found package file with an Undo. op "trash" (a = path) moves it into its storage volume's trash folder,
         * "untrash" (a = trash path, b = the original path) puts it back, "purge" (a = a trash path, or empty for every trash folder)
         * deletes for good. Answer: window.onApkFileOp(id, json) with ok and trash / path, or error.
         */
        @JavascriptInterface
        public void apkFileOp(final String id, final String op, final String a, final String b) {
            submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res;
                    try {
                        res = apkFileOpImpl(op, a, b);
                    } catch (Exception e) {
                        res = new JSONObject();
                        try { res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "failed"); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onApkFileOp && window.onApkFileOp(" + JSONObject.quote(id == null ? "" : id) + ", " + JSONObject.quote(res.toString()) + ")");
                }
            });
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

        /** Lets the user add a storage location through Android's own document-tree picker (an SD card, USB
         *  drive, another app's exposed storage such as Termux, a cloud provider - whatever the device offers),
         *  for the File Manager to browse alongside the device's own storage. The chosen tree gets a persistable
         *  read/write grant, so it still works after this app restarts, without asking again. Answer:
         *  window.onStorageRootPicked(uri, label), or (null) if the user backed out. */
        @JavascriptInterface
        public void pickAddStorage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_PICK_STORAGE_TREE);
                    } catch (Exception e) {
                        notifyJs("window.onStorageRootPicked && window.onStorageRootPicked(null)");
                    }
                }
            });
        }

        /** Gives up this app's access to a storage tree added through pickAddStorage(). The page drops it from
         *  its own list of roots; this just releases the matching OS-level grant so it doesn't linger. */
        @JavascriptInterface
        public void removeStorageRoot(String uriString) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(uriString),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
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
                        String why = e.getMessage() != null ? e.getMessage() : "could not read this package";
                        try { res.put("error", why); } catch (Exception ignored) {}
                        if (ref != null && !ref.startsWith("content://") && (why.contains("EACCES") || why.toLowerCase(java.util.Locale.US).contains("permission denied"))) {
                            needFileAccess("To read this package from storage", ref);
                        }
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

        // ---- Task Manager ----

        /** Starts (or restarts, at a new period) the native poll. Each tick runs {@link #tmTick()} on the shared executor. */
        @JavascriptInterface
        public String tmStart(int intervalMs) {
            if (intervalMs < 1000) intervalMs = 1000;
            if (intervalMs > 10000) intervalMs = 10000;
            synchronized (tmLock) {
                if (tmTask != null) tmTask.cancel(false);
                if (tmExec == null) {
                    tmExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "taskmgr-poll");
                            t.setDaemon(true);
                            return t;
                        }
                    });
                }
                final int period = intervalMs;
                tmTask = tmExec.scheduleWithFixedDelay(new Runnable() { @Override public void run() { tmTick(); } }, 0, period, TimeUnit.MILLISECONDS);
            }
            return "started";
        }

        /** Stops the native poll (called when the tab is left, or auto-refresh is turned off). */
        @JavascriptInterface
        public void tmStop() {
            synchronized (tmLock) {
                if (tmTask != null) { tmTask.cancel(false); tmTask = null; }
                if (tmExec != null) { tmExec.shutdownNow(); tmExec = null; }
                tmLastCpu = null;
                tmLastNet = null;
                tmLastSampleAt = 0;
            }
        }

        /** One sample right away, outside the schedule (the "Refresh now" button, and used when auto-refresh is off). */
        @JavascriptInterface
        public String tmRefreshNow() {
            if (!submitJob(new Runnable() { @Override public void run() { tmTick(); } })) return "error";
            return "started";
        }

        /** One poll: a single shell round trip for CPU / RAM / network / processes / GPU (marker-delimited, so a slow or
         *  missing piece never corrupts another), plus a synchronous battery read (no shell, no permission needed).
         *  Pushes window.onTaskMgrData(json); never throws (a failure is reported in the JSON, not as a crash). */
        private void tmTick() {
            JSONObject res = new JSONObject();
            try {
                res.put("ts", System.currentTimeMillis());
                boolean havePriv = !"standard".equals(resolveExecMode());
                res.put("privileged", havePriv);
                String blob = null;
                if (havePriv) {
                    try {
                        blob = executeShell(
                                "cat /proc/stat 2>/dev/null; echo " + TM_M1 + "; "
                                        + "cat /proc/meminfo 2>/dev/null; echo " + TM_M2 + "; "
                                        + "cat /proc/net/dev 2>/dev/null; echo " + TM_M3 + "; "
                                        + "ps -A -o PID,PPID,USER,RSS,%CPU,NAME 2>/dev/null; echo " + TM_M4 + "; "
                                        + "cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null; echo " + TM_M5 + "; "
                                        + "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; echo " + TM_M6 + "; "
                                        + "cat /sys/class/misc/mali0/device/utilization 2>/dev/null; echo " + TM_M7 + "; "
                                        + "cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null; echo " + TM_M8 + "; "
                                        + "cat /sys/class/devfreq/*/max_freq 2>/dev/null | head -1; echo " + TM_M9 + "; "
                                        + "grep -H '^Threads:' /proc/[0-9]*/status 2>/dev/null; echo " + TM_M10);
                    } catch (Throwable t) {
                        blob = null;
                    }
                } else {
                    // no privileged backend: /proc/stat, /proc/meminfo and /proc/net/dev are world-readable, so this app can read
                    // them itself with no shell at all; only the process list and GPU need a privileged shell and stay empty.
                    try {
                        blob = readWorldReadable("/proc/stat") + TM_M1 + readWorldReadable("/proc/meminfo") + TM_M2
                                + readWorldReadable("/proc/net/dev") + TM_M3 + TM_M4 + TM_M5 + TM_M6 + TM_M7 + TM_M8 + TM_M9 + TM_M10;
                    } catch (Throwable t) {
                        blob = null;
                    }
                }
                String[] parts = blob == null ? new String[0] : splitTm(blob);
                long now = System.currentTimeMillis();

                // CPU
                CpuStats.Reading cpu = CpuStats.parse(parts.length > 0 ? parts[0] : "");
                JSONObject cpuJson = new JSONObject();
                synchronized (tmLock) {
                    if (tmLastCpu != null) {
                        cpuJson.put("overall", CpuStats.percentBusy(tmLastCpu.aggregate, cpu.aggregate));
                        double[] perCore = CpuStats.percentBusyPerCore(tmLastCpu, cpu);
                        JSONArray coreArr = new JSONArray();
                        for (double v : perCore) coreArr.put(v);
                        cpuJson.put("perCore", coreArr);
                    } else {
                        cpuJson.put("overall", 0.0);
                        cpuJson.put("perCore", new JSONArray());
                    }
                    tmLastCpu = cpu;
                }
                cpuJson.put("cores", CpuStats.coreCount(cpu));
                mergeInto(cpuJson, cpuInfoJson());
                res.put("cpu", cpuJson);

                // RAM
                MemStats.Reading mem = MemStats.parse(parts.length > 1 ? parts[1] : "");
                JSONObject memJson = new JSONObject();
                memJson.put("totalKb", mem.totalKb);
                memJson.put("usedKb", MemStats.usedKb(mem));
                memJson.put("availableKb", mem.availableKb);
                memJson.put("swapTotalKb", mem.swapTotalKb);
                memJson.put("swapUsedKb", MemStats.swapUsedKb(mem));
                memJson.put("freeKb", mem.freeKb);
                memJson.put("buffersKb", mem.buffersKb);
                memJson.put("cachedKb", mem.cachedKb);
                memJson.put("swapFreeKb", mem.swapFreeKb);
                memJson.put("swapCachedKb", mem.swapCachedKb);
                long pageSize = pageSizeBytes();
                if (pageSize > 0) memJson.put("pageSizeBytes", pageSize);
                res.put("mem", memJson);

                // Network
                NetStats.Reading net = NetStats.parse(parts.length > 2 ? parts[2] : "");
                JSONObject netJson = new JSONObject();
                JSONArray ifaceArr = new JSONArray();
                synchronized (tmLock) {
                    long elapsed = tmLastSampleAt > 0 ? now - tmLastSampleAt : 0;
                    double totalRx = 0, totalTx = 0;
                    if (tmLastNet != null && elapsed > 0) {
                        for (NetStats.Iface cur : net.ifaces) {
                            NetStats.Iface prev = NetStats.find(tmLastNet, cur.name);
                            if (prev == null) continue;
                            NetStats.Rates r = NetStats.rate(prev, cur, elapsed);
                            if ("lo".equals(cur.name)) continue;
                            JSONObject ij = new JSONObject();
                            ij.put("name", cur.name);
                            ij.put("rxBytesPerSec", r.rxBytesPerSec);
                            ij.put("txBytesPerSec", r.txBytesPerSec);
                            ifaceArr.put(ij);
                            totalRx += r.rxBytesPerSec;
                            totalTx += r.txBytesPerSec;
                        }
                    }
                    netJson.put("totalRxBytesPerSec", totalRx);
                    netJson.put("totalTxBytesPerSec", totalTx);
                    tmLastNet = net;
                    tmLastSampleAt = now;
                }
                netJson.put("ifaces", ifaceArr);
                netJson.put("totalRxBytes", NetStats.totalRxBytes(net, false));
                netJson.put("totalTxBytes", NetStats.totalTxBytes(net, false));
                res.put("net", netJson);

                // Processes
                ProcStats.Result ps = ProcStats.parse(parts.length > 3 ? parts[3] : "");
                JSONArray procArr = new JSONArray();
                for (ProcStats.Proc p : ps.procs) {
                    if (p.pid <= 0) continue;
                    JSONObject pj = new JSONObject();
                    pj.put("pid", p.pid);
                    pj.put("rssKb", p.rssKb);
                    pj.put("cpuPercent", p.cpuPercent);
                    pj.put("name", p.name);
                    String pkg = ProcStats.packageOf(p.name);
                    pj.put("pkg", pkg == null ? "" : pkg);
                    procArr.put(pj);
                }
                res.put("procs", procArr);
                res.put("procsFullFormat", ps.fullFormat);
                cpuJson.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
                if (havePriv) {
                    cpuJson.put("processes", ps.procs.size());
                    cpuJson.put("threads", ProcStats.sumThreads(parts.length > 9 ? parts[9] : ""));
                }

                // GPU: the first known-good reading wins (real busy-time readings before the clock-speed proxy).
                java.util.List<GpuStats.Reading> attempts = new ArrayList<GpuStats.Reading>();
                attempts.add(GpuStats.parseBusyRatio(parts.length > 4 ? parts[4] : ""));
                attempts.add(GpuStats.parseUtilizationPercent(parts.length > 6 ? parts[6] : ""));
                attempts.add(GpuStats.parseBusyRatio(parts.length > 5 ? parts[5] : ""));
                Long curFreq = GpuStats.parseFreqHz(parts.length > 7 ? parts[7] : "");
                Long maxFreq = GpuStats.parseFreqHz(parts.length > 8 ? parts[8] : "");
                attempts.add(GpuStats.freqRatio(curFreq, maxFreq));
                GpuStats.Reading gpu = GpuStats.combine(attempts);
                JSONObject gpuJson = new JSONObject();
                gpuJson.put("available", gpu.available);
                if (gpu.percent != null) gpuJson.put("percent", gpu.percent.doubleValue());
                gpuJson.put("approx", gpu.approx != null && gpu.approx);
                gpuJson.put("label", GpuStats.label(gpu));
                mergeInto(gpuJson, gpuInfoJson());
                res.put("gpu", gpuJson);

                // Battery: a plain sticky-broadcast + BatteryManager read, no shell, no permission.
                res.put("battery", tmBatteryJson());

                res.put("ok", true);
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            notifyJs("window.onTaskMgrData && window.onTaskMgrData(" + res.toString() + ")");
        }

        /** The content of a world-readable /proc file, without any shell (works even with no working mode connected). "" if it can't be read. */
        private String readWorldReadable(String path) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(new File(path).toPath());
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        private String[] splitTm(String blob) {
            String[] markers = {TM_M1, TM_M2, TM_M3, TM_M4, TM_M5, TM_M6, TM_M7, TM_M8, TM_M9, TM_M10};
            String[] out = new String[markers.length];
            int from = 0;
            for (int i = 0; i < markers.length; i++) {
                int at = blob.indexOf(markers[i], from);
                if (at < 0) { out[i] = ""; continue; }
                out[i] = blob.substring(from, at);
                from = at + markers[i].length();
            }
            return out;
        }

        /** Battery level, temperature, voltage, current, charge state and health: the public BatteryManager API and the
         *  sticky ACTION_BATTERY_CHANGED broadcast, no shell and no special permission. Units stay raw (tenths of a degree
         *  C, millivolts, microamps); the page converts to the units the person chose. */
        private JSONObject tmBatteryJson() {
            JSONObject b = new JSONObject();
            try {
                Intent batt = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (batt != null) {
                    int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    b.put("percent", level >= 0 && scale > 0 ? (100.0 * level / scale) : -1);
                    b.put("tempTenthsC", batt.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
                    b.put("voltageMv", batt.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE));
                    b.put("plugged", batt.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1));
                    b.put("health", batt.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1));
                    b.put("status", batt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1));
                    b.put("technology", batt.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY));
                    b.put("present", batt.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true));
                } else {
                    b.put("percent", -1);
                }
                try {
                    android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                    if (bm != null) {
                        int cur = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                        b.put("currentMicroA", cur);     // Integer.MIN_VALUE on a phone that doesn't expose this
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                try { b.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                if (bm != null) {
                    int cc = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    b.put("chargeCounterMicroAh", cc);   // Integer.MIN_VALUE when not exposed
                }
            } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    Intent batt = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (batt != null) {
                        int cyc = batt.getIntExtra(android.os.BatteryManager.EXTRA_CYCLE_COUNT, -1);
                        if (cyc >= 0) b.put("cycleCount", cyc);
                    }
                } catch (Throwable ignored) {}
            }
            return b;
        }

        /** Processor identity and clock info that doesn't change between polls: architecture, ABI, SoC, scaling
         *  governor, per-cluster frequency range and a best-effort thermal-zone temperature. All direct Java/sysfs
         *  reads, no shell and no special permission - these /sys nodes are world-readable on stock Android. Any
         *  piece that can't be read on this phone is simply left out of the result rather than failing the whole poll. */
        private JSONObject cpuInfoJson() {
            JSONObject o = new JSONObject();
            try {
                String arch = System.getProperty("os.arch");
                if (arch != null) o.put("arch", arch);
                if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) o.put("abi", Build.SUPPORTED_ABIS[0]);

                String soc = "";
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        String man = Build.SOC_MANUFACTURER, mod = Build.SOC_MODEL;
                        boolean haveMan = man != null && !"unknown".equalsIgnoreCase(man);
                        boolean haveMod = mod != null && !"unknown".equalsIgnoreCase(mod);
                        if (haveMan) soc = man + (haveMod ? " " + mod : "");
                    } catch (Throwable ignored) {}
                }
                if (soc.isEmpty() && Build.HARDWARE != null) soc = Build.HARDWARE;
                if (!soc.isEmpty()) o.put("soc", soc);

                String governor = readWorldReadable("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").trim();
                if (!governor.isEmpty()) o.put("governor", governor);

                Double tempC = cpuThermalC();
                if (tempC != null) o.put("tempC", tempC.doubleValue());

                int cores = Runtime.getRuntime().availableProcessors();
                long[] minHz = new long[cores], curHz = new long[cores], maxHz = new long[cores];
                boolean anyFreq = false;
                for (int i = 0; i < cores; i++) {
                    String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
                    // cpufreq reports in kHz (a long-standing Linux kernel convention), not Hz - scale up so these
                    // fields are genuinely in Hz, matching their name, for a plain JS-side Hz formatter.
                    minHz[i] = 1000L * parseLongOr(readWorldReadable(base + "cpuinfo_min_freq"), 0);
                    curHz[i] = 1000L * parseLongOr(readWorldReadable(base + "scaling_cur_freq"), 0);
                    maxHz[i] = 1000L * parseLongOr(readWorldReadable(base + "cpuinfo_max_freq"), 0);
                    if (maxHz[i] > 0) anyFreq = true;
                }
                if (anyFreq) {
                    JSONArray clusters = new JSONArray();
                    for (CpuStats.Cluster c : CpuStats.groupClusters(minHz, curHz, maxHz)) {
                        JSONObject cj = new JSONObject();
                        cj.put("cores", c.cores);
                        cj.put("minHz", c.minHz);
                        cj.put("curHz", c.curHz);
                        cj.put("maxHz", c.maxHz);
                        clusters.put(cj);
                    }
                    o.put("clusters", clusters);
                }
            } catch (Throwable ignored) {}
            return o;
        }

        /** A best-effort CPU temperature in Celsius from whichever /sys/class/thermal/thermal_zoneN looks like the
         *  CPU (its "type" file mentions "cpu"), falling back to zone 0; null when no thermal zone is readable at
         *  all. Shown as "estimated" in the UI: zone numbering and the raw unit (millidegrees on most kernels, tenths
         *  of a degree on some) are not standardized across devices. */
        private Double cpuThermalC() {
            try {
                File[] zones = new File("/sys/class/thermal").listFiles();
                if (zones == null) return null;
                String fallback = null;
                for (File z : zones) {
                    if (!z.getName().startsWith("thermal_zone")) continue;
                    String raw = readWorldReadable(z.getPath() + "/temp").trim();
                    if (raw.isEmpty()) continue;
                    if (fallback == null) fallback = raw;
                    String type = readWorldReadable(z.getPath() + "/type").trim();
                    if (type.toLowerCase(java.util.Locale.US).contains("cpu")) return millidegreesToC(raw);
                }
                return fallback == null ? null : millidegreesToC(fallback);
            } catch (Throwable t) {
                return null;
            }
        }

        private Double millidegreesToC(String raw) {
            try {
                long v = Long.parseLong(raw.trim());
                double c = v / 1000.0;
                // A handful of devices report tenths of a degree instead of millidegrees; a "temperature" past
                // 200C is never real on a phone, so treat that reading as the tenths convention instead.
                if (c > 200.0) c = v / 10.0;
                return c;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private long parseLongOr(String s, long fallback) {
            if (s == null) return fallback;
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
        }

        /** Vulkan support/API level and the OpenGL ES version this phone advertises, from public, context-free
         *  PackageManager/ActivityManager queries. No GL/EGL context is created here - safe to call from a background
         *  poll, with no risk of GPU driver instability. Android has no public API for a GPU vendor/model string at
         *  all (see GpuStats's own class doc), so this does not attempt to guess one. */
        private JSONObject gpuInfoJson() {
            JSONObject o = new JSONObject();
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                boolean vulkan = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
                o.put("vulkanSupported", vulkan);
                if (vulkan) {
                    android.content.pm.FeatureInfo[] feats = pm.getSystemAvailableFeatures();
                    if (feats != null) {
                        for (android.content.pm.FeatureInfo f : feats) {
                            if (f != null && android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(f.name)) {
                                int v = f.version;
                                o.put("vulkanApi", (v >>> 22) + "." + ((v >>> 12) & 0x3ff) + "." + (v & 0xfff));
                                break;
                            }
                        }
                    }
                }
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    android.content.pm.ConfigurationInfo ci = am.getDeviceConfigurationInfo();
                    if (ci != null && ci.reqGlEsVersion != 0) {
                        int v = ci.reqGlEsVersion;
                        o.put("glesVersion", ((v & 0xffff0000) >> 16) + "." + (v & 0x0000ffff));
                    }
                }
            } catch (Throwable ignored) {}
            return o;
        }

        /** Copies every key from {@code src} into {@code dst} (a shallow merge). Callers only ever merge key sets
         *  that don't overlap, so which side would win a collision is not a concern in practice. */
        private void mergeInto(JSONObject dst, JSONObject src) {
            if (dst == null || src == null) return;
            java.util.Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                try { dst.put(k, src.get(k)); } catch (Exception ignored) {}
            }
        }

        /** The kernel's memory page size in bytes (4096 on almost every phone shipped so far; 16384 on a 16KB-page
         *  device) via the public POSIX sysconf wrapper - no shell, no file to read, no permission. 0 if it can't
         *  be read, which is left out of the result rather than shown as 0 bytes. */
        private long pageSizeBytes() {
            try {
                return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            } catch (Throwable t) {
                return 0;
            }
        }

        /** Kills a running process (force-stop; a reinstall-free app only ever exposes this per package, not per PID,
         *  so the Processes list's Kill button resolves PID → package through {@link ProcStats#packageOf}). No confirmation:
         *  this matches force_stop everywhere else in the app (see executeAppAction). */
        @JavascriptInterface
        public String tmKill(String pkg) {
            return executeAppAction("force_stop", pkg);
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
                if (p.startsWith("content://")) return fmListSaf(p);
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

        /** Recovers the tree URI an SAF document URI was built from (see {@link #fmListSaf}) - every document URI
         *  this app hands back to the page is built with {@code buildDocumentUriUsingTree}, which keeps the
         *  original /tree/&lt;id&gt; segment alongside the appended /document/&lt;id&gt; one, so the tree id (and
         *  from it, the tree URI) can always be read back out of it, not just out of the bare root URI the picker
         *  first returned. */
        private Uri safTreeUri(Uri uri) {
            return android.provider.DocumentsContract.buildTreeDocumentUri(uri.getAuthority(), android.provider.DocumentsContract.getTreeDocumentId(uri));
        }

        /** The document id this URI points AT: the current folder/file when it is a document URI (has its own
         *  /document/&lt;id&gt; segment - true for anything previously returned by fmListSaf), or the tree's own
         *  root id for the bare tree URI pickAddStorage() first hands back, before anything has been listed yet. */
        private String safCurrentDocId(Uri uri) {
            try {
                return android.provider.DocumentsContract.getDocumentId(uri);
            } catch (Exception e) {
                return android.provider.DocumentsContract.getTreeDocumentId(uri);
            }
        }

        /** Lists a storage root added through pickAddStorage(), or a folder inside one, via the platform
         *  DocumentsContract - no java.io.File, no shell, works the same for any provider the device offers (an
         *  SD card, USB OTG, another app's exposed storage, a cloud provider...). Each entry carries its own full
         *  document URI (see {@link #safTreeUri}) since SAF has no path-join the way POSIX folders do - the page
         *  navigates into a child by using that URI directly, not by composing one out of a name. */
        private String fmListSaf(String uriString) {
            JSONObject res = new JSONObject();
            try {
                Uri incoming = Uri.parse(uriString);
                Uri treeUri = safTreeUri(incoming);
                String curDocId = safCurrentDocId(incoming);
                res.put("path", uriString);
                res.put("isSaf", true);
                Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, curDocId);
                JSONArray entries = new JSONArray();
                android.database.Cursor c = getContentResolver().query(childrenUri, new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                }, null, null, null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String childId = c.getString(0);
                            String name = c.getString(1);
                            String mime = c.getString(2);
                            boolean isDir = android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                            JSONObject e = new JSONObject();
                            e.put("name", name != null ? name : childId);
                            e.put("isDir", isDir);
                            e.put("isLink", false);
                            e.put("size", c.isNull(3) ? 0 : c.getLong(3));
                            e.put("lastModified", c.isNull(4) ? 0 : c.getLong(4));
                            e.put("uri", android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString());
                            entries.put(e);
                        }
                    } finally { c.close(); }
                }
                res.put("entries", entries);
                res.put("source", "saf");
            } catch (Throwable t) {
                try { res.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** fmReadText for a SAF document URI - same {ok,text,size,mtime,writable} contract as fmReadText, via
         *  ContentResolver streaming instead of java.io.File. */
        private String fmReadTextSaf(String uriString) {
            JSONObject res = new JSONObject();
            try {
                Uri uri = Uri.parse(uriString);
                long size = -1, mtime = 0; boolean writable = true;
                android.database.Cursor c = getContentResolver().query(uri, new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        android.provider.DocumentsContract.Document.COLUMN_FLAGS,
                }, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            if (!c.isNull(0)) size = c.getLong(0);
                            if (!c.isNull(1)) mtime = c.getLong(1);
                            writable = !c.isNull(2) && (c.getLong(2) & android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
                        }
                    } finally { c.close(); }
                }
                if (size > FM_EDIT_MAX) { res.put("ok", false); res.put("tooBig", true); res.put("size", size); res.put("error", "This file is over 2 MB: too big to edit here."); return res.toString(); }
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) { res.put("ok", false); res.put("error", "The app cannot read this file."); return res.toString(); }
                byte[] data;
                try {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192]; int n;
                    while ((n = in.read(chunk)) >= 0) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > FM_EDIT_MAX) { res.put("ok", false); res.put("tooBig", true); res.put("size", buf.size()); res.put("error", "This file is over 2 MB: too big to edit here."); return res.toString(); }
                    }
                    data = buf.toByteArray();
                } finally { in.close(); }
                res.put("mtime", mtime);
                res.put("writable", writable);
                if (!FileOps.looksLikeText(data, data.length, false)) { res.put("ok", true); res.put("binary", true); res.put("size", data.length); return res.toString(); }
                res.put("ok", true);
                res.put("size", data.length);
                res.put("text", new String(data, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** fmWriteText for a SAF document URI - same {ok,size,mtime}/{ok:false,changed:true} contract, via
         *  ContentResolver.openOutputStream(uri,"wt") (truncating write) instead of an atomic File rename. */
        private String fmWriteTextSaf(String uriString, String text, double expectMtime) {
            JSONObject res = new JSONObject();
            try {
                Uri uri = Uri.parse(uriString);
                byte[] data = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (data.length > FM_EDIT_MAX * 2) { res.put("ok", false); res.put("error", "Too much text to save here."); return res.toString(); }
                if (expectMtime > 0) {
                    long mtime = 0;
                    android.database.Cursor c = getContentResolver().query(uri, new String[]{ android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED }, null, null, null);
                    if (c != null) { try { if (c.moveToFirst() && !c.isNull(0)) mtime = c.getLong(0); } finally { c.close(); } }
                    if (mtime > 0 && Math.abs(mtime - (long) expectMtime) > 1) { res.put("ok", false); res.put("changed", true); res.put("error", "The file changed since it was opened."); return res.toString(); }
                }
                java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                if (out == null) { res.put("ok", false); res.put("error", "The app cannot write here."); return res.toString(); }
                try { out.write(data); out.flush(); } finally { out.close(); }
                long newMtime = 0;
                android.database.Cursor c2 = getContentResolver().query(uri, new String[]{ android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED }, null, null, null);
                if (c2 != null) { try { if (c2.moveToFirst() && !c2.isNull(0)) newMtime = c2.getLong(0); } finally { c2.close(); } }
                res.put("ok", true); res.put("size", data.length); res.put("mtime", newMtime);
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** fmReadB64 for a SAF document URI - same base64-up-to-maxKb contract, via ContentResolver. */
        private String fmReadB64Saf(String uriString, int maxKb) {
            try {
                Uri uri = Uri.parse(uriString);
                long size = -1;
                android.database.Cursor c = getContentResolver().query(uri, new String[]{ android.provider.DocumentsContract.Document.COLUMN_SIZE }, null, null, null);
                if (c != null) { try { if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0); } finally { c.close(); } }
                long cap = Math.min(12288, Math.max(1, maxKb)) * 1024L;
                if (size > cap) return "";
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return "";
                byte[] data;
                try {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192]; int n;
                    while ((n = in.read(chunk)) >= 0) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > cap) return "";
                    }
                    data = buf.toByteArray();
                } finally { in.close(); }
                if (data.length == 0) return "";
                return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return ""; }
        }

        /** Whether the app has broad storage access (All-files access on Android 11+). */
        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            try {
                if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
                return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) { return false; }
        }

        /** Which of the three special accesses this app has right now: {files, usage, overlay, sdk}. */
        @JavascriptInterface
        public String getPermissionStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("files", hasStorageAccess());
                o.put("usage", hasUsageAccess());
                o.put("overlay", hasOverlayAccess());
                o.put("storage_legacy", hasLegacyStorageAccess());
                o.put("secure_settings", hasWriteSecureSettingsAccess());
                o.put("restricted_settings", hasRestrictedSettingsAccess());
                o.put("sdk", Build.VERSION.SDK_INT);
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** "Display over other apps": grants it through the privileged shell when there is one, else opens the system screen. Answer: "granted" or "settings". */
        @JavascriptInterface
        public String requestOverlayAccess() {
            if (hasOverlayAccess()) return "granted";
            if (!"standard".equals(resolveExecMode())) {
                executeShell("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                if (hasOverlayAccess()) return "granted";
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // straight to this app's own switch where the phone supports it, else the list of apps
                        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) {}
                    }
                }
            });
            return "settings";
        }

        /** The older Read/Write External Storage permissions some apps still check for, granted straight
         *  through the privileged shell - only ever offered once a working mode is active, so there is no
         *  meaningful unprivileged fallback beyond this app's own permission screen. */
        @JavascriptInterface
        public String requestLegacyStorageAccess() {
            if (hasLegacyStorageAccess()) return "granted";
            if (!"standard".equals(resolveExecMode())) {
                executeShell("pm grant " + getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                executeShell("pm grant " + getPackageName() + " android.permission.WRITE_EXTERNAL_STORAGE");
                if (hasLegacyStorageAccess()) return "granted";
            }
            openOwnAppInfo();
            return "settings";
        }

        /** WRITE_SECURE_SETTINGS - a signature/privileged permission with no Settings UI toggle of its own,
         *  grantable only through a privileged shell (ADB, Shizuku, or root). */
        @JavascriptInterface
        public String requestWriteSecureSettings() {
            if (hasWriteSecureSettingsAccess()) return "granted";
            if (!"standard".equals(resolveExecMode())) {
                executeShell("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                if (hasWriteSecureSettingsAccess()) return "granted";
            }
            return "settings"; // nothing to open - this one has no Settings screen at all without a shell
        }

        /** The "Allow restricted settings" app-op Android 13+ blocks for a sideloaded app by default,
         *  covering a handful of other sensitive toggles (accessibility, notification access, usage access
         *  on some OEM builds) - granted straight through the privileged shell. */
        @JavascriptInterface
        public String requestRestrictedSettingsAccess() {
            if (hasRestrictedSettingsAccess()) return "granted";
            if (!"standard".equals(resolveExecMode())) {
                executeShell("appops set " + getPackageName() + " android:read_write_restricted_settings allow");
                if (hasRestrictedSettingsAccess()) return "granted";
            }
            openOwnAppInfo();
            return "settings";
        }

        private void openOwnAppInfo() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }

        /** Opens the system screen to grant this app All-files access (for browsing /sdcard without a shell). */
        @JavascriptInterface
        public void requestAllFilesAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT < 30) {
                            // Android 10 and older have no All-files switch: storage is a runtime permission with its own dialog
                            // (onRequestPermissionsResult sends the app's settings page after a refusal for good)
                            java.util.List<String> need = new java.util.ArrayList<String>();
                            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                            if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), REQ_STORAGE_PERM); return; }
                        }
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
                if (path.startsWith("content://")) {
                    java.io.InputStream in = getContentResolver().openInputStream(Uri.parse(path));
                    if (in == null) return "Error: could not open this file.";
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
                if ("standard".equals(resolveExecMode())) { needFileAccess("To read this file", path); return "Error: needs ADB, Shizuku or Root (or grant All-files access for storage)."; }
                return executeShell("toybox head -c 131072 " + BackupScripts.quote(path) + " 2>&1 || head -c 131072 " + BackupScripts.quote(path));
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        // ---- File manager: search ----
        private final Object analyzeLock = new Object();
        private boolean analyzeBusy;
        private volatile FileSearch.Limits searchLimits;
        private volatile boolean searchCancelEarly;
        private final Object searchLock = new Object();
        private boolean searchBusy;

        /** Places to search besides the folder on screen: [{id, label, path}]: storage, the usual folders, SD cards and USB drives. */
        @JavascriptInterface
        public String fmSearchPlaces() {
            JSONArray out = new JSONArray();
            try {
                String base = fmCanonicalPath("/sdcard");
                String[][] fixed = {{"storage", "Internal storage", base}, {"download", "Downloads", base + "/Download"}, {"dcim", "Camera and photos", base + "/DCIM"},
                        {"pictures", "Pictures", base + "/Pictures"}, {"documents", "Documents", base + "/Documents"}, {"music", "Music", base + "/Music"}, {"movies", "Movies", base + "/Movies"}};
                for (String[] f : fixed) if (new File(f[2]).isDirectory()) out.put(new JSONObject().put("id", f[0]).put("label", f[1]).put("path", f[2]));
                File[] vols = new File("/storage").listFiles();
                if (vols != null) for (File v : vols) if (v.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}") && v.isDirectory()) out.put(new JSONObject().put("id", "vol-" + v.getName()).put("label", "SD card or drive " + v.getName()).put("path", v.getPath()));
                out.put(new JSONObject().put("id", "phone").put("label", "Whole phone").put("path", "/"));
            } catch (Exception ignored) {}
            return out.toString();
        }

        /**
         * Searches (see FileSearch for the query: name, ext:, size:, date:, type:, content:, archive:). rootsJson: the folders to look in. nested: go into subfolders
         * (off: only the files and folders directly in each root). archives: also look at the entries of zip-format archives. hidden: include names that begin
         * with a dot. Answer "started" or "busy"; progress arrives as window.onFmSearchProgress({folder, visited, found}) and the end as
         * window.onFmSearchDone({ok, hits, truncated, cancelled, problems, ms, visited, error}).
         */
        @JavascriptInterface
        public String fmSearch(final String queryText, final String rootsJson, final boolean nested, final boolean archives, final boolean hidden) {
            synchronized (searchLock) {
                if (searchBusy) return "busy";
                searchBusy = true;
                searchCancelEarly = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    final long t0 = System.currentTimeMillis();
                    try {
                        FileSearch.Query q = FileSearch.parse(queryText, t0, java.util.TimeZone.getDefault());
                        q.recursive = nested; q.inArchives = archives; q.includeHidden = hidden;
                        List<File> roots = new ArrayList<File>();
                        List<String> safRoots = new ArrayList<String>();
                        JSONArray ra = new JSONArray(rootsJson);
                        for (int i = 0; i < ra.length() && i < 20; i++) {
                            String r = fmCanonicalPath(ra.optString(i, ""));
                            if (r.startsWith("content://")) safRoots.add(r);
                            else if (!r.isEmpty()) roots.add(new File(r));
                        }
                        if (roots.isEmpty() && safRoots.isEmpty()) throw new IOException("Choose where to search");
                        final FileSearch.Limits lim = new FileSearch.Limits();
                        lim.deadlineMs = t0 + 90000;
                        lim.cancelled = searchCancelEarly;                  // a Cancel that came before the walk was set up still counts
                        searchLimits = lim;
                        res.put("problems", new JSONArray(q.problems));
                        if (q.isEmpty()) { res.put("ok", true); res.put("hits", new JSONArray()); res.put("empty", true); }
                        else {
                            FileSearch.Progress sprog = new FileSearch.Progress() {
                                @Override
                                public boolean onProgress(String folder, int visited, int found) {
                                    try { notifyJs("window.onFmSearchProgress && window.onFmSearchProgress(" + new JSONObject().put("folder", folder).put("visited", visited).put("found", found).toString() + ")"); } catch (Exception ignored) {}
                                    return !lim.cancelled;
                                }
                            };
                            List<FileSearch.Hit> hits = roots.isEmpty() ? new ArrayList<FileSearch.Hit>() : FileSearch.run(roots, q, lim, sprog);
                            if (!safRoots.isEmpty()) {
                                if (!q.content.isEmpty() || !q.archive.isEmpty()) res.getJSONArray("problems").put("content: and archive: look at file bytes, so they are skipped in added storage (names, sizes, dates and types are searched)");
                                for (String sr : safRoots) {
                                    if (lim.cancelled || lim.hitLimit) break;
                                    searchSaf(Uri.parse(sr), q, lim, hits, sprog);
                                }
                            }
                            JSONArray arr = new JSONArray();
                            for (FileSearch.Hit h : hits) {
                                JSONObject o = new JSONObject().put("path", h.path).put("dir", h.dir).put("size", h.size).put("mtime", h.mtime).put("why", h.why);
                                if (h.entry != null) o.put("entry", h.entry);
                                if (h.line != null) { o.put("line", h.line); o.put("lineNo", h.lineNo); }
                                arr.put(o);
                            }
                            res.put("ok", true); res.put("hits", arr); res.put("truncated", lim.hitLimit); res.put("cancelled", lim.cancelled); res.put("visited", lim.visited);
                        }
                    } catch (Throwable t) {
                        try { res.put("ok", false); res.put("error", t.getMessage() != null ? t.getMessage() : "The search failed"); } catch (Exception ignored) {}
                    } finally {
                        searchLimits = null;
                        synchronized (searchLock) { searchBusy = false; }
                    }
                    try { res.put("ms", System.currentTimeMillis() - t0); } catch (Exception ignored) {}
                    notifyJs("window.onFmSearchDone && window.onFmSearchDone(" + res.toString() + ")");
                }
            })) {
                synchronized (searchLock) { searchBusy = false; }
                return "error";
            }
            return "started";
        }

        private static final long SAF_STAGE_MAX = 300L * 1024 * 1024;

        /**
         * Copies a file in added storage (a SAF document URI) to this app's cache so the viewers, Open with, Share and the archive tools,
         * which all read through java.io.File, can work on it. Reused while the source's size and time are unchanged. Answer "started" or
         * "busy"; the end arrives as window.onFmSafStaged({ok, uri, path, name} | {ok:false, uri, error}).
         */
        @JavascriptInterface
        public String fmStageSaf(final String uriString) {
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        res.put("uri", uriString);
                        Uri uri = Uri.parse(uriString);
                        String name = safDisplayName(uriString);
                        if (name.isEmpty()) name = "file";
                        long size = -1, mtime = 0;
                        android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.DocumentsContract.Document.COLUMN_SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) { size = c.isNull(0) ? -1 : c.getLong(0); mtime = c.isNull(1) ? 0 : c.getLong(1); }
                            } finally {
                                c.close();
                            }
                        }
                        if (size > SAF_STAGE_MAX) throw new IOException("This file is over 300 MB, too big to open from added storage here");
                        File dir = new File(getCacheDir(), "saf_stage");
                        if (!dir.isDirectory()) dir.mkdirs();
                        File f = new File(dir, Integer.toHexString(uriString.hashCode()) + "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                        if (!(f.isFile() && mtime > 0 && f.lastModified() == mtime && (size < 0 || f.length() == size))) {
                            java.io.InputStream in = getContentResolver().openInputStream(uri);
                            if (in == null) throw new IOException("Could not read this file");
                            FileOutputStream out = new FileOutputStream(f);
                            try {
                                byte[] buf = new byte[65536];
                                long total = 0;
                                int n;
                                while ((n = in.read(buf)) >= 0) {
                                    out.write(buf, 0, n);
                                    total += n;
                                    if (total > SAF_STAGE_MAX) throw new IOException("This file is over 300 MB, too big to open from added storage here");
                                }
                            } finally {
                                try { in.close(); } catch (Exception ignored) {}
                                out.close();
                            }
                            if (mtime > 0) f.setLastModified(mtime);
                        }
                        res.put("ok", true);
                        res.put("path", f.getAbsolutePath());
                        res.put("name", name);
                    } catch (Throwable t) {
                        try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onFmSafStaged && window.onFmSafStaged(" + res.toString() + ")");
                }
            })) return "error";
            return "started";
        }

        /** Walks an added-storage (SAF) folder through DocumentsContract and adds what matches the listing parts of the query. */
        private void searchSaf(Uri root, FileSearch.Query q, FileSearch.Limits lim, List<FileSearch.Hit> hits, FileSearch.Progress progress) {
            java.util.ArrayDeque<Object[]> todo = new java.util.ArrayDeque<Object[]>();
            todo.add(new Object[]{root, 0});
            Uri tree = safTreeUri(root);
            while (!todo.isEmpty() && !lim.cancelled && !lim.hitLimit) {
                Object[] cur = todo.poll();
                Uri dir = (Uri) cur[0];
                int depth = (Integer) cur[1];
                if (System.currentTimeMillis() > lim.deadlineMs) { lim.hitLimit = true; break; }
                android.database.Cursor c = null;
                try {
                    Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, safCurrentDocId(dir));
                    c = getContentResolver().query(kids, new String[]{
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                            android.provider.DocumentsContract.Document.COLUMN_SIZE,
                            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
                    if (c == null) continue;
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name == null) continue;
                        lim.visited++;
                        boolean isDir = android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                        Uri child = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                        if (!q.includeHidden && name.startsWith(".")) continue;
                        if (FileSearch.matchesListing(q, name, c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4), isDir)) {
                            FileSearch.Hit h = new FileSearch.Hit();
                            h.path = child.toString(); h.dir = isDir; h.size = c.isNull(3) ? 0 : c.getLong(3); h.mtime = c.isNull(4) ? 0 : c.getLong(4); h.why = "name";
                            hits.add(h);
                            if (hits.size() >= lim.maxResults) { lim.hitLimit = true; break; }
                        }
                        if (isDir && q.recursive && depth < lim.maxDepth && lim.visited < lim.maxVisited) todo.add(new Object[]{child, depth + 1});
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
                if (progress != null && !progress.onProgress(safDisplayName(dir.toString()), lim.visited, hits.size())) lim.cancelled = true;
            }
        }

        /** Stops the running search. */
        @JavascriptInterface
        public void fmSearchCancel() {
            searchCancelEarly = true;
            FileSearch.Limits l = searchLimits;
            if (l != null) l.cancelled = true;
        }

        // ---- File manager: edit, pictures, PDF pages, Open With ----
        private static final long FM_EDIT_MAX = 2L * 1024 * 1024;

        /**
         * A text file for the editor: {ok, text, size, mtime, writable} up to 2 MB, or {ok, binary:true} for something that is not text,
         * or {ok:false, error}. mtime is what fmWriteText wants back, to notice that the file changed in between.
         */
        @JavascriptInterface
        public String fmReadText(String path) {
            JSONObject res = new JSONObject();
            try {
                path = fmCanonicalPath(path);
                if (path.startsWith("content://")) return fmReadTextSaf(path);
                File f = new File(path);
                byte[] data;
                if (f.isFile() && f.canRead()) {
                    long len = f.length();
                    if (len > FM_EDIT_MAX) { res.put("ok", false); res.put("tooBig", true); res.put("size", len); res.put("error", "This file is over 2 MB: too big to edit here."); return res.toString(); }
                    data = new byte[(int) len];
                    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
                    try { in.readFully(data); } finally { in.close(); }
                    res.put("mtime", f.lastModified());
                    res.put("writable", f.canWrite() || (f.getParentFile() != null && f.getParentFile().canWrite()));
                } else {
                    if ("standard".equals(resolveExecMode())) { needFileAccess("To read this file", path); res.put("ok", false); res.put("error", "The app cannot read this file. Grant All-files access for storage, or set up ADB, Shizuku or Root."); return res.toString(); }
                    // the exact bytes (base64 keeps line ends, a missing last newline and odd bytes), and an error is never mistaken for the file
                    String qp = BackupScripts.quote(path);
                    String out = executeShell("p=" + qp + "; if [ ! -f \"$p\" ] || [ ! -r \"$p\" ]; then echo FMERR_UNREADABLE; "
                            + "elif [ \"$(wc -c < \"$p\")\" -gt " + FM_EDIT_MAX + " ]; then echo FMERR_BIG; "
                            + "else (toybox base64 -w0 \"$p\" || base64 -w0 \"$p\") 2>/dev/null; fi");
                    String t = out == null ? "" : out.trim();
                    if (t.startsWith("FMERR_BIG")) { res.put("ok", false); res.put("tooBig", true); res.put("error", "This file is over 2 MB: too big to edit here."); return res.toString(); }
                    if (t.startsWith("FMERR")) { res.put("ok", false); res.put("error", "The file could not be read, even with the working mode."); return res.toString(); }
                    try { data = android.util.Base64.decode(t, android.util.Base64.DEFAULT); }
                    catch (IllegalArgumentException e) { res.put("ok", false); res.put("error", "The file could not be read, even with the working mode."); return res.toString(); }
                    res.put("mtime", 0);
                    res.put("writable", true);
                }
                if (!FileOps.looksLikeText(data, data.length, false)) { res.put("ok", true); res.put("binary", true); res.put("size", data.length); return res.toString(); }
                res.put("ok", true);
                res.put("size", data.length);
                res.put("text", new String(data, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                try { res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "failed"); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /**
         * Saves text to a file. expectMtime is what fmReadText gave: when the file was changed since, nothing is written and the answer is
         * {ok:false, changed:true}. Written to a temporary file and renamed, so a failure keeps the old content. Answer {ok, size, mtime}.
         */
        @JavascriptInterface
        public String fmWriteText(String path, String text, double expectMtime) {
            JSONObject res = new JSONObject();
            try {
                path = fmCanonicalPath(path);
                if (path.startsWith("content://")) return fmWriteTextSaf(path, text, expectMtime);
                if (fmProtectedPath(path)) { res.put("ok", false); res.put("error", "System location: not touched"); return res.toString(); }
                byte[] data = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (data.length > FM_EDIT_MAX * 2) { res.put("ok", false); res.put("error", "Too much text to save here."); return res.toString(); }
                File f = new File(path);
                if (f.isDirectory()) { res.put("ok", false); res.put("error", "That is a folder, not a file."); return res.toString(); }
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) { try { f = f.getCanonicalFile(); } catch (IOException ignored) {} }
                File dir = f.getParentFile();
                if (f.exists() && expectMtime > 0 && Math.abs(f.lastModified() - (long) expectMtime) > 1) { res.put("ok", false); res.put("changed", true); res.put("error", "The file changed since it was opened."); return res.toString(); }
                if (dir != null && dir.isDirectory() && dir.canWrite() && (!f.exists() || f.canWrite())) {
                    FileOps.writeAtomic(f, data);
                    res.put("ok", true); res.put("size", data.length); res.put("mtime", f.lastModified());
                    return res.toString();
                }
                if ("standard".equals(resolveExecMode())) { needFileAccess("To save this file", path); res.put("ok", false); res.put("error", "The app cannot write here. Grant All-files access for storage, or set up ADB, Shizuku or Root."); return res.toString(); }
                // through the same helper the other privileged writes use: a pipe, a push or a copy to a temporary name and a rename, never a
                // redirection that empties the file before it is known that the new text can be read
                File stage = new File(getCacheDir(), "fm_edit.tmp");
                FileOps.writeAtomic(stage, data);
                String err;
                try { err = writeFileViaMode(resolveExecMode(), stage, f.getPath()); } finally { stage.delete(); }
                if (err != null) { res.put("ok", false); res.put("error", err.length() > 200 ? err.substring(0, 200) : err); return res.toString(); }
                res.put("ok", true); res.put("size", data.length); res.put("mtime", 0);
            } catch (Exception e) {
                try { res.put("ok", false); res.put("error", e.getMessage() != null ? e.getMessage() : "failed"); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** A file as base64 (up to maxKb), for a font sample. Empty when the app cannot read it or it is bigger. */
        @JavascriptInterface
        public String fmReadB64(String path, int maxKb) {
            try {
                String p = fmCanonicalPath(path);
                if (p.startsWith("content://")) return fmReadB64Saf(p, maxKb);
                File f = new File(p);
                long len = f.length();
                if (!f.isFile() || !f.canRead() || len <= 0 || len > Math.min(12288, Math.max(1, maxKb)) * 1024L) return "";
                byte[] data = new byte[(int) len];
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
                try { in.readFully(data); } finally { in.close(); }
                return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return ""; }
        }

        private final java.util.concurrent.ExecutorService thumbExec = java.util.concurrent.Executors.newSingleThreadExecutor();      // one picture at a time: a long list must not decode ten at once

        private File thumbDir() { File d = new File(getCacheDir(), "fm_thumbs"); if (!d.isDirectory()) d.mkdirs(); return d; }

        /** Small pictures of images and videos (px wide at most), from the cache when there. Each one arrives as window.onFmThumb(path, dataUrl). */
        @JavascriptInterface
        public void fmThumbs(final String pathsJson, final int px) {
            thumbExec.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONArray in = new JSONArray(pathsJson);
                        int size = Math.max(32, Math.min(256, px));
                        for (int i = 0; i < in.length() && i < 200; i++) {
                            String p = fmCanonicalPath(in.optString(i, ""));
                            String url = fmThumbFor(p, size);
                            if (url != null) notifyJs("window.onFmThumb && window.onFmThumb(" + JSONObject.quote(p) + "," + JSONObject.quote(url) + ")");
                        }
                        ThumbCache.purge(thumbDir(), ThumbCache.LIMIT_BYTES);
                    } catch (Throwable ignored) {}
                }
            });
        }

        /** An image decoded no larger than lim on its long side, turned upright by its EXIF orientation, on a white ground (JPEG has no transparency). */
        private android.graphics.Bitmap fmDecodeImage(String path, int lim) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int ss = 1;
            while (Math.max(o.outWidth, o.outHeight) / (ss * 2) >= lim) ss *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = ss;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(path, o);
            if (bm == null) return null;
            int rot = 0; boolean flip = false;
            try {
                int ori = new android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                switch (ori) {
                    case 3: rot = 180; break; case 6: rot = 90; break; case 8: rot = 270; break;
                    case 2: flip = true; break; case 4: rot = 180; flip = true; break; case 5: rot = 90; flip = true; break; case 7: rot = 270; flip = true; break;
                    default: break;
                }
            } catch (Throwable ignored) {}
            float sc = Math.min(1f, (float) lim / Math.max(bm.getWidth(), bm.getHeight()));
            android.graphics.Matrix m = new android.graphics.Matrix();
            if (flip) m.postScale(-1f, 1f);
            if (rot != 0) m.postRotate(rot);
            if (sc < 1f) m.postScale(sc, sc);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (out != bm) bm.recycle();
            if (out.hasAlpha()) {
                android.graphics.Bitmap flat = android.graphics.Bitmap.createBitmap(out.getWidth(), out.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(flat);
                cv.drawColor(android.graphics.Color.WHITE);
                cv.drawBitmap(out, 0, 0, null);
                out.recycle();
                out = flat;
            }
            return out;
        }

        private String fmThumbFor(String path, int px) {
            try {
                File f = new File(path);
                String kind = ThumbCache.kindOf(f.getName());
                if (kind.isEmpty() || !f.isFile() || !f.canRead() || f.length() <= 0 || (kind.equals("image") && f.length() > 80L * 1024 * 1024)) return null;
                File cached = new File(thumbDir(), ThumbCache.key(path, f.lastModified(), f.length(), px) + ".jpg");
                if (!cached.isFile()) {
                    android.graphics.Bitmap bm = null;
                    if (kind.equals("image")) {
                        bm = fmDecodeImage(path, px);
                    } else {
                        android.media.MediaMetadataRetriever mr = new android.media.MediaMetadataRetriever();
                        try { mr.setDataSource(path); bm = mr.getFrameAtTime(-1); } finally { try { mr.release(); } catch (Exception ignored) {} }
                    }
                    if (bm == null) return null;
                    float sc = Math.min(1f, (float) px / Math.max(bm.getWidth(), bm.getHeight()));
                    if (sc < 1f && !kind.equals("image")) { android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * sc)), Math.max(1, Math.round(bm.getHeight() * sc)), true); if (sm != bm) { bm.recycle(); bm = sm; } }
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
                    bm.recycle();
                    FileOps.writeAtomic(cached, bo.toByteArray());
                } else {
                    ThumbCache.touch(cached);
                }
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(cached.toPath()), android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return null; }
        }

        /** How much the picture cache holds, and removing it. */
        @JavascriptInterface
        public String fmThumbCache(String action) {
            JSONObject res = new JSONObject();
            try {
                File d = thumbDir();
                if ("clear".equals(action)) res.put("freed", ThumbCache.clear(d));
                res.put("bytes", ThumbCache.size(d));
            } catch (Exception ignored) {}
            return res.toString();
        }

        /** An image at up to maxPx for the viewer. Answer: window.onFmImage(path, dataUrl or "", width, height). */
        @JavascriptInterface
        public void fmImage(final String path, final int maxPx) {
            submitJob(new Runnable() {
                @Override
                public void run() {
                    String url = ""; int w = 0, h = 0;
                    try {
                        String p = fmCanonicalPath(path);
                        File f = new File(p);
                        if (f.isFile() && f.canRead() && f.length() < 120L * 1024 * 1024) {
                            int lim = Math.max(256, Math.min(2048, maxPx));
                            android.graphics.Bitmap bm = fmDecodeImage(p, lim);
                            if (bm != null) {
                                w = bm.getWidth(); h = bm.getHeight();
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bo);
                                bm.recycle();
                                url = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                            }
                        }
                                    } catch (Throwable ignored) {}
                    notifyJs("window.onFmImage && window.onFmImage(" + JSONObject.quote(path) + "," + JSONObject.quote(url) + "," + w + "," + h + ")");
                }
            });
        }

        /** One page of a PDF as a picture. Answer: window.onFmPdf({ok, pages, page, data} or {ok:false, error}). */
        @JavascriptInterface
        public void fmPdf(final String path, final int page, final int widthPx) {
            submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    android.os.ParcelFileDescriptor pfd = null;
                    android.graphics.pdf.PdfRenderer pr = null;
                    try {
                        File f = new File(fmCanonicalPath(path));
                        if (!f.isFile() || !f.canRead()) throw new IOException("The app cannot read this file.");
                        pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                        pr = new android.graphics.pdf.PdfRenderer(pfd);
                        int n = pr.getPageCount();
                        int pg = Math.max(0, Math.min(n - 1, page));
                        android.graphics.pdf.PdfRenderer.Page pp = pr.openPage(pg);
                        try {
                            int w = Math.max(200, Math.min(1400, widthPx));
                            int h = Math.max(1, Math.round(w * (float) pp.getHeight() / Math.max(1, pp.getWidth())));
                            if (h > 4000) { w = Math.max(100, Math.round(w * 4000f / h)); h = 4000; }            // a very tall page is drawn smaller, not at 70000 pixels
                            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                            bm.eraseColor(android.graphics.Color.WHITE);
                            pp.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bo);
                            bm.recycle();
                            res.put("ok", true); res.put("pages", n); res.put("page", pg); res.put("path", path);
                            res.put("data", "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP));
                        } finally { pp.close(); }
                    } catch (Throwable t) {
                        try { res = new JSONObject(); res.put("ok", false); res.put("path", path); res.put("error", t instanceof SecurityException ? "This PDF is protected or could not be opened." : (t.getMessage() != null ? t.getMessage() : "The PDF could not be shown.")); } catch (Exception ignored) {}
                    } finally {
                        try { if (pr != null) pr.close(); } catch (Exception ignored) {}
                        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
                    }
                    notifyJs("window.onFmPdf && window.onFmPdf(" + JSONObject.quote(res.toString()) + ")");
                }
            });
        }

        private String fmMime(String name) {
            String n = name.toLowerCase(java.util.Locale.US);
            int i = n.lastIndexOf('.');
            String e = i < 0 ? "" : n.substring(i + 1);
            String m = e.isEmpty() ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
            if (m != null) return m;
            switch (e) {
                case "md": case "log": case "ini": case "conf": case "cfg": case "prop": case "properties": case "sh": case "yml": case "yaml": case "toml": case "csv": case "srt": case "gradle": case "java": case "kt": case "py": case "js": case "ts": case "c": case "h": case "cpp": return "text/plain";
                case "apk": return "application/vnd.android.package-archive";
                case "mkv": return "video/x-matroska";
                default: return "*/*";
            }
        }

        /**
         * Hands a file to another app: a copy (up to 400 MB) goes to this app's share folder and the system's "Open with" list is shown.
         * Answer: window.onFmOpenWith({ok} or {ok:false, error}).
         */
        @JavascriptInterface
        public void fmOpenWith(final String path, final String mimeHint, final boolean chooser) {
            submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        File src = new File(fmCanonicalPath(path));
                        if (!src.isFile() || !src.canRead()) throw new IOException("The app cannot read this file. Grant All-files access for storage.");
                        if (src.length() > 400L * 1024 * 1024) throw new IOException("This file is too big to hand over from here (over 400 MB).");
                        File copy = ShareProvider.newShareFile(MainActivity.this, src.getName());
                        java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
                        try { copyFile(src, out); } finally { out.close(); }
                        final Uri uri = ShareProvider.uriFor(copy);
                        final String mime = mimeHint != null && !mimeHint.isEmpty() ? mimeHint : fmMime(src.getName());
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Intent v = new Intent(Intent.ACTION_VIEW);
                                    v.setDataAndType(uri, mime);
                                    v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    Intent go = chooser ? Intent.createChooser(v, "Open with") : v;
                                    go.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivity(go);
                                } catch (Exception e) {
                                    notifyJs("window.onFmOpenWith && window.onFmOpenWith(" + JSONObject.quote("{\"ok\":false,\"error\":\"No app on this phone can open this kind of file.\"}") + ")");
                                }
                            }
                        });
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try { res.put("ok", false); res.put("error", t.getMessage() != null ? t.getMessage() : "failed"); } catch (Exception ignored) {}
                    }
                    if (!res.optBoolean("ok", false)) notifyJs("window.onFmOpenWith && window.onFmOpenWith(" + JSONObject.quote(res.toString()) + ")");
                }
            });
        }

        /** Creates a new file or folder inside a SAF parent (mkdir/touch have no "target path" to give fmOp for a
         *  SAF location - there is no join-by-string here, so the parent and the new name travel separately). */
        @JavascriptInterface
        public String fmNewSaf(String parentUri, String name, String type) {
            JSONObject res = new JSONObject();
            try {
                Uri parent = Uri.parse(parentUri);
                boolean isDir = "dir".equals(type);
                String mime = isDir ? android.provider.DocumentsContract.Document.MIME_TYPE_DIR : fmMime(name);
                if (!isDir && "*/*".equals(mime)) mime = "application/octet-stream";
                Uri created = android.provider.DocumentsContract.createDocument(getContentResolver(), parent, mime, name);
                res.put("ok", created != null);
                res.put("output", created != null ? "OK" : "Could not create " + name);
                if (created != null) res.put("uri", created.toString());
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("output", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Renames a SAF document in place. There is no "same folder, new name" path string to hand fmOp the way
         *  a POSIX rename gets one (fmJoin(newName)), so this takes the plain new display name directly. */
        @JavascriptInterface
        public String fmRenameSaf(String uri, String newName) {
            JSONObject res = new JSONObject();
            try {
                Uri renamed = android.provider.DocumentsContract.renameDocument(getContentResolver(), Uri.parse(uri), newName);
                res.put("ok", renamed != null);
                res.put("output", renamed != null ? "OK" : "A file or folder with that name is already there");
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("output", errMsg(t)); } catch (Exception ignored) {}
            }
            return res.toString();
        }

        /** Copies (recursively, for a folder) a SAF document as a new child of destParentUri, keeping its own name. */
        private String safCopyInto(Uri srcUri, Uri destParentUri) {
            try {
                android.content.ContentResolver cr = getContentResolver();
                String name = null, mime = null;
                android.database.Cursor c = cr.query(srcUri, new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                }, null, null, null);
                if (c != null) { try { if (c.moveToFirst()) { name = c.getString(0); mime = c.getString(1); } } finally { c.close(); } }
                if (name == null) return "Could not read the source";
                boolean isDir = android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (isDir) {
                    Uri newDir = android.provider.DocumentsContract.createDocument(cr, destParentUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name);
                    if (newDir == null) return "Could not create " + name;
                    Uri srcTree = safTreeUri(srcUri);
                    String srcDocId = safCurrentDocId(srcUri);
                    Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(srcTree, srcDocId);
                    android.database.Cursor kids = cr.query(childrenUri, new String[]{ android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null);
                    if (kids != null) {
                        try {
                            while (kids.moveToNext()) {
                                Uri kidUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(srcTree, kids.getString(0));
                                String err = safCopyInto(kidUri, newDir);
                                if (err != null) return err;
                            }
                        } finally { kids.close(); }
                    }
                    return null;
                }
                String m = mime != null ? mime : "application/octet-stream";
                Uri newFile = android.provider.DocumentsContract.createDocument(cr, destParentUri, m, name);
                if (newFile == null) return "Could not create " + name;
                return safStreamCopy(cr.openInputStream(srcUri), cr.openOutputStream(newFile), name);
            } catch (Throwable t) { return errMsg(t); }
        }

        /** Copies a plain filesystem file or folder as a new child of a SAF folder, keeping its own name (recursive for a folder). */
        private String safCopyPosixIntoSaf(File srcFile, Uri destParentUri) {
            try {
                android.content.ContentResolver cr = getContentResolver();
                String name = srcFile.getName();
                if (srcFile.isDirectory()) {
                    Uri newDir = android.provider.DocumentsContract.createDocument(cr, destParentUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name);
                    if (newDir == null) return "Could not create " + name;
                    File[] kids = srcFile.listFiles();
                    if (kids != null) for (File k : kids) { String err = safCopyPosixIntoSaf(k, newDir); if (err != null) return err; }
                    return null;
                }
                String mime = fmMime(name);
                if ("*/*".equals(mime)) mime = "application/octet-stream";
                Uri newFile = android.provider.DocumentsContract.createDocument(cr, destParentUri, mime, name);
                if (newFile == null) return "Could not create " + name;
                return safStreamCopy(new java.io.FileInputStream(srcFile), cr.openOutputStream(newFile), name);
            } catch (Throwable t) { return errMsg(t); }
        }

        /** Copies a SAF document as a new child of a plain filesystem folder, keeping its own name (recursive for a folder). */
        private String safCopyIntoPosix(Uri srcUri, File destDir) {
            try {
                android.content.ContentResolver cr = getContentResolver();
                String name = null, mime = null;
                android.database.Cursor c = cr.query(srcUri, new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                }, null, null, null);
                if (c != null) { try { if (c.moveToFirst()) { name = c.getString(0); mime = c.getString(1); } } finally { c.close(); } }
                if (name == null) return "Could not read the source";
                boolean isDir = android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                File dest = new File(destDir, name);
                if (isDir) {
                    if (!dest.isDirectory() && !dest.mkdirs()) return "Could not create " + name;
                    Uri srcTree = safTreeUri(srcUri);
                    String srcDocId = safCurrentDocId(srcUri);
                    Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(srcTree, srcDocId);
                    android.database.Cursor kids = cr.query(childrenUri, new String[]{ android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null);
                    if (kids != null) {
                        try {
                            while (kids.moveToNext()) {
                                Uri kidUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(srcTree, kids.getString(0));
                                String err = safCopyIntoPosix(kidUri, dest);
                                if (err != null) return err;
                            }
                        } finally { kids.close(); }
                    }
                    return null;
                }
                return safStreamCopy(cr.openInputStream(srcUri), new java.io.FileOutputStream(dest), name);
            } catch (Throwable t) { return errMsg(t); }
        }

        /** Streams in to out (closing both either way) - the shared tail of every single-file SAF/POSIX copy above. */
        private String safStreamCopy(java.io.InputStream in, java.io.OutputStream out, String name) {
            try {
                if (in == null || out == null) return "Could not copy " + name;
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                    try { out.close(); } catch (Exception ignored) {}
                }
                return null;
            } catch (Throwable t) { return errMsg(t); }
        }

        /** Deletes, copies or moves where at least one side is a SAF document URI - the other side, when a cp/mv crosses
         *  into or out of a SAF root, may be a plain filesystem path. Unlike fmOp's POSIX convention, "b" here is always
         *  a destination FOLDER's own identity (a SAF document URI, or a plain directory path), never a bare new name -
         *  a same-folder rename has no such path string to build and uses fmRenameSaf instead. Returns "" on success,
         *  else an error message (never null). */
        private String fmOpSaf(String op, String a, String b) {
            try {
                if ("rm".equals(op)) {
                    boolean ok = android.provider.DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(a));
                    return ok ? "" : "Could not delete";
                }
                if ("cp".equals(op) || "mv".equals(op)) {
                    if (b == null || b.isEmpty()) return "no destination";
                    boolean srcSaf = a.startsWith("content://");
                    boolean dstSaf = b.startsWith("content://");
                    String err = dstSaf
                            ? (srcSaf ? safCopyInto(Uri.parse(a), Uri.parse(b)) : safCopyPosixIntoSaf(new File(a), Uri.parse(b)))
                            : safCopyIntoPosix(Uri.parse(a), new File(b));
                    if (err != null) return err;
                    if ("mv".equals(op)) {
                        if (srcSaf) android.provider.DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(a));
                        else FileOps.delete(java.util.Collections.singletonList(new File(a)), null);
                    }
                    return "";
                }
                return "unknown op";
            } catch (Throwable t) { return errMsg(t); }
        }

        /** The child of the SAF folder {@code parent} called {@code name}, or null. */
        private Uri safChildNamed(Uri parent, String name) {
            try {
                Uri tree = safTreeUri(parent);
                Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, safCurrentDocId(parent));
                android.database.Cursor c = getContentResolver().query(kids, new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (c == null) return null;
                try {
                    while (c.moveToNext()) {
                        if (name.equals(c.getString(1))) return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    }
                } finally {
                    c.close();
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private String safDisplayName(String ref) {
            if (!ref.startsWith("content://")) return new File(ref).getName();
            try {
                android.database.Cursor c = getContentResolver().query(Uri.parse(ref), new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) return c.getString(0);
                    } finally {
                        c.close();
                    }
                }
            } catch (Throwable ignored) {}
            return "";
        }

        /** A cp / mv / rm batch where some path, or the destination, is added storage (a SAF document URI): one item at a time through fmOpSaf, with the name rule applied here (replace deletes the clash first, skip leaves it, keep lets the provider number the copy). Fills {@code res} like the shell batch. */
        private void runSafBatch(String op, List<String> paths, String dest, String policyName, JSONObject res) {
            JSONArray failed = new JSONArray();
            int done = 0, skipped = 0;
            boolean cancelled = false;
            Runnable hook = new Runnable() { @Override public void run() { fmBatchCancel = true; } };
            String title = fmBatchLabel(op) + " " + paths.size() + (paths.size() == 1 ? " item" : " items");
            JobService.begin(MainActivity.this, title, hook);
            try {
                for (int i = 0; i < paths.size(); i++) {
                    if (fmBatchCancel) { cancelled = true; break; }
                    String q = paths.get(i);
                    String text = fmBatchLabel(op) + " " + (i + 1) + " of " + paths.size() + "…";
                    notifyJs("window.onFmBatchProgress && window.onFmBatchProgress(" + JSONObject.quote(text) + ")");
                    JobService.progress(MainActivity.this, text, paths.size() > 0 ? (i * 100) / paths.size() : 0);
                    try {
                        if (!"rm".equals(op)) {
                            if (q.equals(dest)) throw new IOException("Can't put a folder inside itself");
                            if (q.startsWith("content://") && dest.startsWith("content://")) {
                                try {
                                    String sd = android.provider.DocumentsContract.getDocumentId(Uri.parse(q));
                                    String dd = android.provider.DocumentsContract.getDocumentId(Uri.parse(dest));
                                    if (safTreeUri(Uri.parse(q)).equals(safTreeUri(Uri.parse(dest))) && (dd.equals(sd) || dd.startsWith(sd + "/"))) throw new IOException("Can't put a folder inside itself");
                                } catch (IllegalArgumentException ignored) {}
                            }
                            if (!dest.startsWith("content://") && (dest.equals(q) || dest.startsWith(q + "/"))) throw new IOException("Can't put a folder inside itself");
                            String name = safDisplayName(q);
                            if (dest.startsWith("content://") && !name.isEmpty()) {
                                Uri clash = safChildNamed(Uri.parse(dest), name);
                                if (clash != null) {
                                    if (clash.equals(Uri.parse(q))) { skipped++; continue; }
                                    if ("skip".equals(policyName)) { skipped++; continue; }
                                    if (!"keep".equals(policyName)) android.provider.DocumentsContract.deleteDocument(getContentResolver(), clash);
                                }
                            }
                        }
                        String err = fmOpSaf(op, q, dest);
                        if (err.isEmpty()) done++;
                        else failed.put(new JSONObject().put("p", q).put("error", err.length() > 200 ? err.substring(0, 200) : err));
                    } catch (Throwable t) {
                        try { failed.put(new JSONObject().put("p", q).put("error", errMsg(t))); } catch (Exception ignored) {}
                    }
                }
                res.put("total", paths.size());
                res.put("done", done);
                res.put("skipped", skipped);
                res.put("cancelled", cancelled);
                res.put("failed", failed);
                res.put("ok", failed.length() == 0 && !cancelled);
            } catch (Throwable t) {
                try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
            } finally {
                JobService.end(MainActivity.this, hook, fmBatchDoneText(op, res));
            }
        }

        /** File op: mkdir|touch|rm|cp|mv. For rm, dirs are removed recursively. */
        @JavascriptInterface
        public String fmOp(String op, String a, String b) {
            JSONObject res = new JSONObject();
            try {
                if (a == null || a.isEmpty()) { res.put("ok", false); res.put("output", "no path"); return res.toString(); }
                a = fmCanonicalPath(a);
                if (b != null && !b.isEmpty()) b = fmCanonicalPath(b);
                if (a.startsWith("content://") || (b != null && b.startsWith("content://"))) {
                    String out = fmOpSaf(op, a, b);
                    res.put("ok", out.isEmpty());
                    res.put("output", out.isEmpty() ? "OK" : out);
                    return res.toString();
                }
                // the same protection the batch operations have: removing or moving away a whole storage root, a top-level folder or a system tree
                if (("rm".equals(op) || "mv".equals(op)) && fmProtectedPath(a)) { res.put("ok", false); res.put("output", "System location: not touched"); return res.toString(); }
                // what the app can do with its own file access needs no working mode
                String direct = fmOpDirect(op, a, b);
                if (direct != null) { res.put("ok", direct.isEmpty()); res.put("output", direct.isEmpty() ? "OK" : direct); if (direct.isEmpty() || !direct.startsWith("?")) return res.toString(); }
                if ("standard".equals(resolveExecMode())) { res.put("ok", false); res.put("output", "needs ADB, Shizuku or Root (or All-files access for storage)"); return res.toString(); }
                String qa = BackupScripts.quote(a);
                String cmd;
                if ("mkdir".equals(op)) cmd = "mkdir -p " + qa + " && echo FMOK";
                else if ("touch".equals(op)) cmd = "touch " + qa + " && echo FMOK";
                else if ("rm".equals(op)) cmd = "rm -rf " + qa + " && echo FMOK";
                else if ("cp".equals(op)) cmd = "cp -r " + qa + " " + BackupScripts.quote(b) + " && echo FMOK";
                else if ("mv".equals(op)) cmd = "if [ -e " + BackupScripts.quote(b) + " ] && [ ! -d " + BackupScripts.quote(b) + " ]; then echo 'A file or folder with that name is already there'; else mv " + qa + " " + BackupScripts.quote(b) + " && echo OK; fi";
                else { res.put("ok", false); res.put("output", "unknown op"); return res.toString(); }
                String out = executeShell(cmd);
                res.put("ok", out != null && FM_OK_LINE.matcher(out).find());
                res.put("output", out != null ? out.replaceAll("(?m)^FMOK\\s*$", "OK").trim() : "");
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
            return fmBatch2(op, pathsJson, destDir, "replace");
        }

        /** Stops the running batch after the item it is on. */
        @JavascriptInterface
        public void fmBatchCancel() { fmBatchCancel = true; }

        /**
         * Like fmBatch, with the rule for a name that is already in destDir: "replace" (folders merge), "skip" or "keep" (both: name (1).ext).
         * Whatever the app can read and write itself is done in Java with no shell (so it works without a working mode); the rest goes
         * through the privileged shell as before. Ends with window.onFmBatchDone({op, ok, total, done, skipped, cancelled, failed: [{p, error}]}).
         */
        @JavascriptInterface
        public String fmBatch2(final String op, final String pathsJson, final String destDir, final String policyName) {
            final int policy = FileOps.policyOf(policyName);
            synchronized (fmBatchLock) {
                if (fmBatchBusy) return "busy";
                fmBatchBusy = true;
                fmBatchCancel = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    JobTicker ticker = null;
                    Runnable cancelHook = null;
                    try {
                        res.put("op", op);
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
                        boolean anySaf = dest.startsWith("content://");
                        for (String q : paths) if (q.startsWith("content://")) anySaf = true;
                        if (anySaf) {
                            runSafBatch(op, paths, dest, policyName, res);
                            synchronized (fmBatchLock) { fmBatchBusy = false; }
                            notifyJs("window.onFmBatchDone && window.onFmBatchDone(" + res.toString() + ")");
                            return;
                        }
                        JSONArray failed = new JSONArray();
                        List<String> todo = new ArrayList<String>();
                        for (String q : paths) {
                            if (fmProtectedPath(q)) failed.put(new JSONObject().put("p", q).put("error", "System location: not touched"));
                            else if (!"rm".equals(op) && (dest.equals(q) || dest.startsWith(q + "/"))) failed.put(new JSONObject().put("p", q).put("error", "Can't put a folder inside itself"));
                            else todo.add(q);
                        }
                        int done = 0, skipped = 0;
                        boolean cancelled = false;
                        // what the app can do itself, in Java; the rest goes to the shell below
                        List<File> direct = new ArrayList<File>();
                        List<String> viaShell = new ArrayList<String>();
                        for (String q : todo) { if (fmDirectOk(op, q, dest)) direct.add(new File(q)); else viaShell.add(q); }
                        // the numbers of the job: percent, speed, time left, a stall; shown on the page and in a notification that keeps the job alive
                        long totalBytes = 0;
                        if (!"rm".equals(op) && !direct.isEmpty()) totalBytes = FileOps.sizeOf(direct, 50000);
                        final ProgressMeter meter = new ProgressMeter(totalBytes, todo.size(), System.currentTimeMillis());
                        final String title = fmBatchLabel(op) + " " + todo.size() + (todo.size() == 1 ? " item" : " items");
                        cancelHook = new Runnable() { @Override public void run() { fmBatchCancel = true; } };
                        JobService.begin(MainActivity.this, title, cancelHook);
                        ticker = new JobTicker(meter, fmBatchLabel(op), 1000, 20000, new JobTicker.Listener() {
                            @Override
                            public void onTick(String text, int pct, long stalledMs) {
                                notifyJs("window.onFmBatchProgress && window.onFmBatchProgress(" + JSONObject.quote(text) + ")");
                                JobService.progress(MainActivity.this, text, pct);
                            }
                        });
                        ticker.start();
                        if (!direct.isEmpty()) {
                            FileOps.Progress prog = new FileOps.Progress() {
                                @Override
                                public boolean onProgress(String name, long bytes, int items) {
                                    synchronized (meter) { meter.update(bytes, items, name, System.currentTimeMillis()); }
                                    return !fmBatchCancel;
                                }
                            };
                            FileOps.Result fr = "rm".equals(op) ? FileOps.delete(direct, prog) : "cp".equals(op) ? FileOps.copy(direct, new File(dest), policy, prog) : FileOps.move(direct, new File(dest), policy, prog);
                            done += fr.done;
                            skipped += fr.skipped;
                            cancelled = fr.cancelled;
                            for (String[] f : fr.failed) failed.put(new JSONObject().put("p", f[0]).put("error", f[1]));
                        }
                        if (!viaShell.isEmpty() && "standard".equals(resolveExecMode())) {
                            for (String q : viaShell) failed.put(new JSONObject().put("p", q).put("error", "The app cannot reach this. Grant All-files access for storage, or set up ADB, Shizuku or Root."));
                            viaShell.clear();
                        }
                        todo = viaShell;
                        String qd = BackupScripts.quote(dest);
                        final int chunk = 40;
                        boolean linkLost = false;                         // the connection to the device is gone: nothing after that point is tried
                        for (int from = 0; from < todo.size() && !cancelled; from += chunk) {
                            if (fmBatchCancel) { cancelled = true; break; }
                            List<String> part = todo.subList(from, Math.min(todo.size(), from + chunk));
                            synchronized (meter) { meter.update(0, done + skipped + failed.length(), part.isEmpty() ? "" : part.get(0).substring(part.get(0).lastIndexOf('/') + 1), System.currentTimeMillis()); }
                            if (policy != FileOps.REPLACE && !"rm".equals(op)) {
                                // a rule for taken names needs a look at each name: one command per item
                                for (String q : part) {
                                    if (fmBatchCancel) { cancelled = true; break; }
                                    if (linkLost) { failed.put(new JSONObject().put("p", q).put("error", "The connection to the device was lost")); continue; }
                                    String o2 = batchShell(FileOps.shellScript("cp".equals(op) ? "cp -r" : "mv", q, dest, policy));
                                    if (o2 != null && o2.contains("FMOK")) done++;
                                    else if (o2 != null && o2.contains("FMSKIP")) skipped++;
                                    else {
                                        String m2 = o2 == null ? "failed" : o2.trim();
                                        if (fmTransportLost(m2)) linkLost = true;
                                        failed.put(new JSONObject().put("p", q).put("error", m2.isEmpty() ? "failed" : (m2.length() > 200 ? m2.substring(0, 200) : m2)));
                                    }
                                }
                                continue;
                            }
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
                        res.put("skipped", skipped);
                        res.put("cancelled", cancelled);
                        res.put("failed", failed);
                        res.put("ok", failed.length() == 0 && !cancelled);
                    } catch (Throwable t) {
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    } finally {
                        if (ticker != null) ticker.stop();
                        if (cancelHook != null) JobService.end(MainActivity.this, cancelHook, fmBatchDoneText(op, res));
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
                if ("standard".equals(resolveExecMode())) { needFileAccess("To read this package from storage", path); res.put("ok", false); res.put("error", "Can't read this APK. For storage, grant All-files access; for system paths, set up ADB, Shizuku or Root."); return res.toString(); }
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
                return archiveOpenJson(a, fmCanonicalPath(path)).toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /** As {@link #archiveOpen(String)}, with a password to try for a format whose listing itself needs one (not zip: a zip's directory is
         *  never encrypted, so this is the same as archiveOpen for one). {ok,...}|{ok:false,error,needPassword,wrong}. */
        @JavascriptInterface
        public String archiveOpen2(String path, String password) {
            try {
                String p = fmCanonicalPath(path);
                char[] pw = password == null || password.isEmpty() ? null : password.toCharArray();
                ZipTool.Archive a = archiveFor(path, true, pw);
                return archiveOpenJson(a, p).toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /** Sets (or, with an empty string, clears) the password used to read this archive's entries, and checks it against one encrypted
         *  entry so a wrong password is reported right away rather than on the first read that happens to need it. {ok:true}|{ok:false,error,needPassword,wrong}. */
        @JavascriptInterface
        public String archiveSetPassword(String path, String password) {
            try {
                ZipTool.Archive a = archiveFor(path, false);
                char[] pw = password == null || password.isEmpty() ? null : password.toCharArray();
                a.setPassword(pw);
                if (pw != null) {
                    ZipTool.Entry probe = null;
                    for (ZipTool.Entry e : a.entries) if (!e.dir && e.encrypted() && e.size > 0) { probe = e; break; }
                    if (probe != null) {
                        InputStream in = a.open(probe);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        try {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                        } finally {
                            in.close();
                        }
                        // a traditional entry's 1-byte check can falsely accept a wrong password (1 in 256): the CRC of the decompressed
                        // data is the real check. AES's own authentication code already failed the read above if it mismatched.
                        if (probe.crc != 0 && crc.getValue() != probe.crc) throw new ZipTool.NeedPassword(true, "That password did not work");
                    }
                }
                return new JSONObject().put("ok", true).toString();
            } catch (Throwable t) {
                return archiveFail(t);
            }
        }

        /** The JSON archiveOpen/archiveOpen2 return once an archive is open. */
        private JSONObject archiveOpenJson(ZipTool.Archive a, String p) throws Exception {
            int files = 0;
            boolean encrypted = false;
            for (ZipTool.Entry e : a.entries) {
                if (e.dir) continue;
                files++;
                if (e.encrypted()) encrypted = true;
            }
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
            r.put("format", a.format);
            r.put("solid", a.solid);
            r.put("encrypted", encrypted || a.headerEncrypted);
            r.put("staged", archiveStaged(p));
            r.put("apk", p.toLowerCase(java.util.Locale.US).endsWith(".apk"));
            r.put("editable", block == null);
            r.put("whyNot", block == null ? "" : block);
            return r;
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
            return archiveExtract2(path, entry, destDir, "replace", false);
        }

        /** Stops the extraction that is running, after the file it is on. */
        @JavascriptInterface
        public void archiveCancel() { archiveCancel = true; }

        /**
         * Extracts a file, a folder or the whole archive (entry "" or ending in "/") into destDir. policy is the rule for a file that is already there:
         * "replace", "skip" or "keep" (both: name (1).ext). deleteAfter removes the archive when the whole of it was extracted without a skipped or
         * failed file. Shows progress (percent, speed, time left, a stall) on the page and in a notification with Cancel.
         * The end arrives as window.onArchiveResult({op, ok, files, bytes, skipped, kept, deletedArchive, dest, problems}).
         */
        @JavascriptInterface
        public String archiveExtract2(final String path, final String entry, final String destDir, final String policyName, final boolean deleteAfter) {
            final int policy = FileOps.policyOf(policyName);
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
                archiveCancel = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    File tmp = null;
                    JobTicker ticker = null;
                    Runnable cancelHook = null;
                    try {
                        res.put("op", "extract");
                        ZipTool.Archive a = archiveFor(path, false);
                        String dest = fmCanonicalPath(destDir == null || destDir.trim().isEmpty() ? "/storage/emulated/0/Download" : destDir.trim());
                        File dir = new File(dest);
                        String name = entry == null ? "" : entry;
                        boolean tree = name.isEmpty() || name.endsWith("/");
                        boolean writable = (dir.isDirectory() || dir.mkdirs()) && dir.canWrite();
                        final ProgressMeter meter = new ProgressMeter(tree ? ZipTool.sizeUnder(a, name) : 0, 0, System.currentTimeMillis());
                        cancelHook = new Runnable() { @Override public void run() { archiveCancel = true; } };
                        JobService.begin(MainActivity.this, "Extracting " + new File(fmCanonicalPath(path)).getName(), cancelHook);
                        ticker = new JobTicker(meter, "Extracting", 1000, tree ? 20000 : Long.MAX_VALUE / 4, new JobTicker.Listener() {
                            @Override
                            public void onTick(String text, int pct, long stalledMs) {
                                notifyArchiveProgress(text);
                                JobService.progress(MainActivity.this, text, pct);
                            }
                        });
                        ticker.start();
                        if (!tree) {
                            ZipTool.Entry e = a.find(name);
                            if (e == null || e.dir) throw new IOException("Not found in the archive: " + name);
                            String base = ZipTool.safeName(e.baseName());
                            if (base == null) throw new IOException("Unsafe file name: " + name);
                            notifyArchiveProgress("Extracting " + base + "…");
                            requireNotArchive(path, new File(dest, base));
                            long bytes = 0;
                            File target = new File(dir, base);
                            boolean leave = false;
                            if (!writable && policy != FileOps.REPLACE) throw new IOException("Skip and Keep both need a folder this app can write to itself. Choose one on shared storage, or use Replace.");
                            if (writable && FileOps.exists(target)) {
                                if (policy == FileOps.SKIP) leave = true;
                                else if (policy == FileOps.KEEP_BOTH) target = new File(dir, FileOps.uniqueName(dir, base));
                            }
                            if (leave) {
                                res.put("kept", 1);
                            } else if (writable) {
                                bytes = ZipTool.extractTo(a, e, target, new File(fmCanonicalPath(path)));
                                dest = target.getParent();
                                base = target.getName();
                            } else {
                                tmp = new File(getCacheDir(), "archive_extract.tmp");
                                bytes = ZipTool.extractTo(a, e, tmp);
                                String err = writeFileTo(tmp, dest + "/" + base);
                                if (err != null) throw new IOException(err);
                            }
                            res.put("files", leave ? 0 : 1);
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
                                    synchronized (meter) { meter.update(doneBytes, doneFiles, current, System.currentTimeMillis()); }
                                    return !archiveCancel;
                                }
                            }, problems, new File(fmCanonicalPath(path)), policy);
                            res.put("files", r[0]);
                            res.put("bytes", r[1]);
                            res.put("skipped", r[2]);
                            res.put("kept", r[3]);
                            res.put("problems", new JSONArray(problems));
                            res.put("dest", dest);
                            // the whole archive, everything written, nothing skipped or failed: the archive may go (only when asked)
                            if (deleteAfter && name.isEmpty() && r[2] == 0 && r[3] == 0 && problems.isEmpty() && r[0] > 0 && r[0] == ZipTool.countFiles(a, "")) {
                                File af = new File(fmCanonicalPath(path));
                                boolean gone = false;
                                try { archiveRelease(path); } catch (Throwable ignored) {}
                                if (af.isFile() && af.getParentFile() != null && af.getParentFile().canWrite()) gone = af.delete();
                                res.put("deletedArchive", gone);
                            }
                        }
                        res.put("ok", true);
                    } catch (Throwable t) {
                        putArchiveError(res, t);
                    } finally {
                        if (tmp != null) tmp.delete();
                        if (ticker != null) ticker.stop();
                        String doneText = res.optBoolean("ok") ? "Extracted " + res.optInt("files") + (res.optInt("files") == 1 ? " file" : " files") + (res.optInt("kept") > 0 ? " · " + res.optInt("kept") + " left as they were" : "") : res.optString("error");
                        if (cancelHook != null) JobService.end(MainActivity.this, cancelHook, doneText);
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
                    File textTmp = null;
                    try {
                        JSONObject op = new JSONObject(opJson);
                        String kind = op.optString("op");
                        res.put("op", kind);
                        String p = fmCanonicalPath(path);
                        String block = archiveEditBlock(p);
                        if (block != null) throw new IOException(block);
                        ZipTool.Archive a = archiveFor(p, false);
                        if (!a.isZip() && !ArchiveIo.supportsEdit(a.format)) throw new IOException("This archive can't be edited here.");
                        requireUnchanged(p);
                        String name = op.optString("name");
                        String msg;
                        File orig = new File(p);
                        File dir = orig.getParentFile();
                        boolean direct = orig.canWrite() && dir != null && dir.canWrite();
                        tmp = direct ? new File(dir, "." + orig.getName() + ".edit-tmp") : new File(getCacheDir(), "archive_edit.tmp");
                        notifyArchiveProgress("Rewriting " + orig.getName() + "…");
                        final long[] last = {0};
                        if (a.isZip()) {
                            List<ZipTool.Edit> edits = new ArrayList<ZipTool.Edit>();
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
                                File src = addSource(op);
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
                        } else {
                            List<ArchiveIo.Edit> edits = new ArrayList<ArchiveIo.Edit>();
                            if ("delete".equals(kind)) {
                                edits.add(name.endsWith("/") ? ArchiveIo.Edit.deleteTree(name) : ArchiveIo.Edit.delete(name));
                                msg = "Deleted " + name;
                            } else if ("rename".equals(kind)) {
                                String to = op.optString("to");
                                if (name.endsWith("/")) edits.add(ArchiveIo.Edit.renameTree(name, to.endsWith("/") ? to : to + "/"));
                                else edits.add(ArchiveIo.Edit.rename(name, to));
                                msg = "Renamed " + name + " to " + to;
                            } else if ("replaceText".equals(kind)) {
                                String text = op.optString("text");
                                if (op.optBoolean("crlf")) text = text.replace("\r\n", "\n").replace("\n", "\r\n");
                                if (a.find(name) == null) throw new IOException("Not found in the archive: " + name);
                                textTmp = new File(getCacheDir(), "archive_edit_text.tmp");
                                java.nio.file.Files.write(textTmp.toPath(), text.getBytes("UTF-8"));
                                edits.add(ArchiveIo.Edit.replace(name, textTmp));
                                msg = "Saved " + name;
                            } else if ("add".equals(kind)) {
                                File src = addSource(op);
                                String to = op.optString("to");
                                edits.add(a.find(to) != null && op.optBoolean("overwrite") ? ArchiveIo.Edit.replace(to, src) : ArchiveIo.Edit.add(to, src));
                                if (a.find(to) != null && !op.optBoolean("overwrite")) throw new IOException("\"" + to + "\" is already in the archive");
                                msg = "Added " + to;
                            } else if ("mkdir".equals(kind)) {
                                String to = op.optString("to");
                                edits.add(ArchiveIo.Edit.add(to.endsWith("/") ? to : to + "/", null));
                                msg = "Created folder " + to;
                            } else {
                                throw new IOException("Unknown edit: " + kind);
                            }
                            ArchiveIo.rewrite(a.file, tmp, a.password(), edits, new ArchiveIo.Progress() {
                                @Override
                                public boolean tick(long bytesDone) {
                                    long now = System.currentTimeMillis();
                                    if (now - last[0] > 250) { last[0] = now; notifyArchiveProgress("Rewriting… " + XapkInfo.humanBytes(bytesDone)); }
                                    return true;
                                }
                            });
                            ArchiveIo.list(tmp, a.format, a.password());      // a result that can't be read back never replaces the original
                        }
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
                        putArchiveError(res, t);
                    } finally {
                        if (tmp != null) tmp.delete();
                        if (textTmp != null) textTmp.delete();
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

        /** The file an "add" edit reads from (must be readable by this app). */
        private File addSource(JSONObject op) throws IOException {
            String from = fmCanonicalPath(op.optString("from"));
            File src = new File(from);
            if (!src.isFile() || !src.canRead()) {
                needFileAccess("To add that file", from);
                throw new IOException("Can't read " + from + ". Pick a file on shared storage (All-files access is needed).");
            }
            return src;
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
         * Makes a new archive from files and folders on storage. optsJson is {format,name,dir,level,policy,password,enc}; itemsJson is
         * [{p,d}, ...] (the full path of each top-level file or folder picked). Returns "started" or "busy"; the end arrives as
         * window.onArchiveResult({op:"create", ok, files, bytes, csize, dest, encrypted, problems}).
         */
        @JavascriptInterface
        public String archiveCreate(final String optsJson, final String itemsJson) {
            synchronized (archiveLock) {
                if (archiveBusy) return "busy";
                archiveBusy = true;
                archiveCancel = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    JobTicker ticker = null;
                    Runnable cancelHook = null;
                    char[] pw = null;
                    try {
                        res.put("op", "create");
                        JSONObject opts = new JSONObject(optsJson);
                        JSONArray itemsArr = new JSONArray(itemsJson);
                        String format = opts.optString("format", "zip");
                        boolean singleOnly = "gz".equals(format) || "bz2".equals(format) || "xz".equals(format) || "zst".equals(format) || "lz4".equals(format);
                        if (!"zip".equals(format) && !ArchiveIo.supportsCreate(format)) throw new IOException("This format isn't available in this build yet. Use Zip for now.");
                        String name = opts.optString("name");
                        File dir = new File(fmCanonicalPath(opts.optString("dir")));
                        int level = opts.optInt("level", 6);
                        boolean replace = "replace".equals(opts.optString("policy"));
                        String password = opts.optString("password", "");
                        String enc = opts.optString("enc", "aes256");
                        if (!password.isEmpty() && !"zip".equals(format) && !ArchiveIo.supportsPassword(format)) throw new IOException("A password can be set for Zip and 7z only.");
                        if (!(dir.isDirectory() || dir.mkdirs()) || !dir.canWrite()) throw new IOException("Can't write to " + dir);
                        File dest = new File(dir, name);
                        if (!replace && FileOps.exists(dest)) dest = new File(dir, FileOps.uniqueName(dir, name));
                        List<ArchiveIo.Source> srcs = new ArrayList<ArchiveIo.Source>();
                        java.util.ArrayList<String> problems = new java.util.ArrayList<String>();
                        java.util.Set<String> seen = new java.util.HashSet<String>();
                        int[] fileCount = {0};
                        long totalBytes = 0;
                        for (int i = 0; i < itemsArr.length(); i++) {
                            File f = new File(fmCanonicalPath(itemsArr.getJSONObject(i).optString("p")));
                            totalBytes += czCollect(srcs, f, f.getName(), seen, problems, fileCount);
                        }
                        if (fileCount[0] == 0) throw new IOException("Nothing to compress");
                        if (singleOnly && (srcs.size() != 1 || srcs.get(0).file == null || !srcs.get(0).file.isFile())) {
                            throw new IOException("This format packs one file only. Pick a single file, or use Zip, 7z or a tar format for several.");
                        }
                        pw = password.isEmpty() ? null : password.toCharArray();
                        final ProgressMeter meter = new ProgressMeter(totalBytes, 0, System.currentTimeMillis());
                        cancelHook = new Runnable() { @Override public void run() { archiveCancel = true; } };
                        JobService.begin(MainActivity.this, "Compressing " + dest.getName(), cancelHook);
                        ticker = new JobTicker(meter, "Compressing", 1000, 20000, new JobTicker.Listener() {
                            @Override
                            public void onTick(String text, int pct, long stalledMs) {
                                notifyArchiveProgress(text);
                                JobService.progress(MainActivity.this, text, pct);
                            }
                        });
                        ticker.start();
                        boolean encrypted;
                        if ("zip".equals(format)) {
                            List<ZipWriter.Item> zitems = new ArrayList<ZipWriter.Item>(srcs.size());
                            for (ArchiveIo.Source s : srcs) zitems.add(new ZipWriter.Item(s.name, s.file));
                            int scheme = pw == null ? ZipWriter.NONE : "aes128".equals(enc) ? ZipWriter.AES128 : "zipcrypto".equals(enc) ? ZipWriter.ZIPCRYPTO : ZipWriter.AES256;
                            encrypted = scheme != ZipWriter.NONE;
                            ZipWriter.create(dest, zitems, level, pw, scheme, new ZipWriter.Progress() {
                                @Override
                                public boolean onProgress(long doneBytes, int doneFiles, String current) {
                                    synchronized (meter) { meter.update(doneBytes, doneFiles, current, System.currentTimeMillis()); }
                                    return !archiveCancel;
                                }
                            });
                        } else {
                            encrypted = pw != null;
                            ArchiveIo.create(dest, format, srcs, pw, level, new ArchiveIo.Progress() {
                                @Override
                                public boolean tick(long bytesDone) {
                                    synchronized (meter) { meter.update(bytesDone, 0, "", System.currentTimeMillis()); }
                                    return !archiveCancel;
                                }
                            });
                        }
                        res.put("files", fileCount[0]);
                        res.put("bytes", totalBytes);
                        res.put("csize", dest.length());
                        res.put("dest", dest.getPath());
                        res.put("encrypted", encrypted);
                        if (!problems.isEmpty()) res.put("problems", new JSONArray(problems));
                        res.put("ok", true);
                    } catch (Throwable t) {
                        putArchiveError(res, t);
                    } finally {
                        if (pw != null) java.util.Arrays.fill(pw, '\0');
                        if (ticker != null) ticker.stop();
                        if (cancelHook != null) {
                            String doneText = res.optBoolean("ok") ? "Compressed " + res.optInt("files") + (res.optInt("files") == 1 ? " file" : " files") : res.optString("error");
                            JobService.end(MainActivity.this, cancelHook, doneText);
                        }
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

        /** Walks one picked file or folder, adding it (and, for a folder, everything under it) to items to compress. Returns the bytes added.
         *  A symbolic link to a folder is left out (noted in problems) rather than followed, so a loop can't compress forever; a symbolic
         *  link to a file is included as the file it points to. {@code seen} drops an exact duplicate path reached twice. */
        private long czCollect(List<ArchiveIo.Source> out, File f, String name, java.util.Set<String> seen, List<String> problems, int[] fileCount) {
            if (!seen.add(name)) return 0;
            try {
                boolean link = java.nio.file.Files.isSymbolicLink(f.toPath());
                if (link && f.isDirectory()) { noteArc(problems, name + ": a symbolic link to a folder (left out)"); return 0; }
                if (!f.exists()) { noteArc(problems, name + ": no longer there"); return 0; }
                if (link || f.isFile()) {
                    out.add(new ArchiveIo.Source(name, f));
                    fileCount[0]++;
                    return Math.max(0, f.length());
                }
                if (f.isDirectory()) {
                    out.add(new ArchiveIo.Source(name + "/", f));
                    File[] kids = f.listFiles();
                    long total = 0;
                    if (kids != null) {
                        java.util.Arrays.sort(kids, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); } });
                        for (File k : kids) total += czCollect(out, k, name + "/" + k.getName(), seen, problems, fileCount);
                    }
                    return total;
                }
                noteArc(problems, name + ": not a file or a folder");
                return 0;
            } catch (Exception e) {
                noteArc(problems, name + ": " + (e.getMessage() == null ? "failed" : e.getMessage()));
                return 0;
            }
        }

        private void noteArc(List<String> problems, String line) {
            if (problems != null && problems.size() < 20) problems.add(line);
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
                String flagged = runShellAction("pm compile -m " + m + (force ? " -f " : " ") + pkg);
                String out = flagText(flagged);
                r.put("ok", flagOk(flagged));
                r.put("output", !out.trim().isEmpty() ? out.trim() : "Done");
            } catch (Exception e) {
                try { r.put("ok", false); r.put("output", "Error: " + e.getMessage()); } catch (Exception ignored) {}
            }
            return r.toString();
        }

        /** Stops the running Dex-optimization batch after whichever app it is already on (that one `pm compile`
         *  call can't be interrupted once started), the same Stop convention as {@link #appBatchCancel()}. */
        @JavascriptInterface
        public void optimizeBatchCancel() { optimizeBatchCancel = true; }

        /**
         * Runs {@link #optimizeApp} across every package in pkgsJson, off the page's thread - a single `pm compile`
         * call can take real time (full recompilation of a big app), and calling it straight from the page's own
         * thread (as the old single-app and batch Dex-optimize paths both used to) blocks the WebView itself for
         * that whole time, exactly the freeze {@link #appActionBatch} was already built to avoid for ordinary app
         * actions. Used for a single app too (a one-item array), so both paths get the same async treatment and
         * live progress. Returns "started" (or "busy"); progress arrives as window.onOptimizeBatchProgress(i,
         * total, pkg) before each app's own compile runs, and the end as window.onOptimizeBatchDone({total, done,
         * cancelled, rows: [{pkg, output, success}], ok}).
         */
        @JavascriptInterface
        public String optimizeAppBatch(final String pkgsJson, final String mode, final boolean force) {
            synchronized (optimizeBatchLock) {
                if (optimizeBatchBusy) return "busy";
                optimizeBatchBusy = true;
                optimizeBatchCancel = false;
            }
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    try {
                        JSONArray pkgs = new JSONArray(pkgsJson);
                        int total = pkgs.length();
                        JSONArray rows = new JSONArray();
                        int done = 0;
                        boolean cancelled = false;
                        for (int i = 0; i < total; i++) {
                            if (optimizeBatchCancel) { cancelled = true; break; }
                            String pkg = pkgs.optString(i, "");
                            notifyJs("window.onOptimizeBatchProgress && window.onOptimizeBatchProgress(" + i + "," + total + "," + JSONObject.quote(pkg) + ")");
                            JSONObject one;
                            try { one = new JSONObject(optimizeApp(pkg, mode, force)); } catch (Exception e) { one = new JSONObject(); one.put("ok", false); one.put("output", errMsg(e)); }
                            boolean ok = one.optBoolean("ok", false);
                            if (ok) done++;
                            rows.put(new JSONObject().put("pkg", pkg).put("output", one.optString("output", "")).put("success", ok));
                        }
                        res.put("total", total);
                        res.put("done", done);
                        res.put("cancelled", cancelled);
                        res.put("rows", rows);
                        res.put("ok", true);
                    } catch (Throwable t) {
                        try { res.put("ok", false); res.put("error", errMsg(t)); } catch (Exception ignored) {}
                    } finally {
                        synchronized (optimizeBatchLock) {
                            optimizeBatchBusy = false;
                        }
                    }
                    notifyJs("window.onOptimizeBatchDone && window.onOptimizeBatchDone(" + res.toString() + ")");
                }
            })) {
                synchronized (optimizeBatchLock) {
                    optimizeBatchBusy = false;
                }
                return "error";
            }
            return "started";
        }

        /** Deletes the cached list icons; returns how many files were removed. */
        @JavascriptInterface
        public int clearAppIconCache() {
            return clearIconCache();
        }

        /** Installed icon packs as JSON [{pkg, label}]. */
        @JavascriptInterface
        public String getIconPacks() {
            return listIconPacks();
        }

        /** Icons for the packages in {@code pkgsJson}, drawn off the page's thread and cached on disk. Answers arrive in chunks as window.onAppIcons({pkg: dataUri}). */
        @JavascriptInterface
        public String loadAppIcons(final String pkgsJson, final String pack) {
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONArray pkgs = new JSONArray(pkgsJson);
                        JSONObject chunk = new JSONObject();
                        for (int i = 0; i < pkgs.length(); i++) {
                            String pkg = pkgs.optString(i, "");
                            if (pkg.isEmpty()) continue;
                            String uri = iconDataUri(pkg, pack);
                            if (uri != null) chunk.put(pkg, uri);
                            if (chunk.length() >= 12 || i == pkgs.length() - 1) {
                                if (chunk.length() > 0) notifyJs("window.onAppIcons && window.onAppIcons(" + chunk.toString() + ")");
                                chunk = new JSONObject();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            })) return "error";
            return "started";
        }

        /** Saves an app's icon (256 px PNG) to Download/ADB App Manager/Icons. Result via window.onAppIconSaved(json{ok, pkg, path, error}). */
        @JavascriptInterface
        public String saveAppIcon(final String pkg, final String label, final String pack) {
            if (!submitJob(new Runnable() {
                @Override
                public void run() {
                    JSONObject res = new JSONObject();
                    Object[] target = null;
                    try {
                        res.put("pkg", pkg);
                        android.graphics.Bitmap b = renderAppIcon(pkg, 256, pack);
                        String base = (label == null || label.trim().isEmpty() ? pkg : label.trim() + " (" + pkg + ")");
                        target = openDownloadOutput(base + ".png", "image/png", "Icons");
                        java.io.OutputStream out = (java.io.OutputStream) target[0];
                        try {
                            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                        } finally {
                            out.close();
                        }
                        res.put("ok", true);
                        res.put("path", (String) target[1]);
                    } catch (Throwable t) {
                        deleteDownloadTarget(target);
                        try {
                            res.put("ok", false);
                            res.put("error", errMsg(t));
                        } catch (Exception ignored) {}
                    }
                    notifyJs("window.onAppIconSaved && window.onAppIconSaved(" + res.toString() + ")");
                }
            })) return "error";
            return "started";
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
            return runShellAction("appops set " + pkg + " " + op + " " + mode);
        }

        @JavascriptInterface
        public String setPermission(String pkg, String perm, boolean grant) {
            return runShellAction(grant ? ("pm grant " + pkg + " " + perm) : ("pm revoke " + pkg + " " + perm));
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
            closeTermSessions();
            for (AiHttp c : aiCalls.values()) c.cancel();
            aiExecutor.shutdown();
        } catch (Throwable ignored) {}
        try {
            executor.shutdown();
        } catch (Exception ignored) {}
        try {
            settingsExecutor.shutdown();
        } catch (Exception ignored) {}
    }
}
