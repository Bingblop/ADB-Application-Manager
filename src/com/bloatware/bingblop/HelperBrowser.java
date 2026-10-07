package com.bloatware.bingblop;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/**
 * The browser inside the app for Morphe Helper. Sites such as APKMirror put a browser check in front of their downloads that a plain HTTP client cannot pass, and the
 * download then happens in some other app. This is a real web view (a full-screen dialog with Back, Forward, Reload and Close) that presents itself as Chrome, so the
 * check passes the way it does in a browser; whatever it is asked to download is saved by {@link BrowserDownload} with the web view's own cookies, straight into the Helper's
 * folder. The web page it shows has no access to this app (no JavaScript bridge is added, and only web addresses open).
 */
final class HelperBrowser {

    private final Activity activity;
    private final File dir;
    private final BrowserDownload.Events events;
    private Dialog dialog;
    private WebView web;
    private TextView title, status;
    private ProgressBar bar;
    private Button back, forward;
    private volatile boolean cancel;
    private String ua = "";

    private HelperBrowser(Activity a, File dir, BrowserDownload.Events e) {
        this.activity = a;
        this.dir = dir;
        this.events = e;
    }

    /** Opens the browser at {@code url} (an http or https address). Must be called on the UI thread. */
    static HelperBrowser show(Activity a, String url, File dir, BrowserDownload.Events events) {
        HelperBrowser b = new HelperBrowser(a, dir, events);
        b.build(url);
        return b;
    }

    void close() {
        cancel = true;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#1f1f1f"));
        b.setMinWidth(0);
        b.setMinimumWidth(dp(44));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setTextSize(15);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void build(final String startUrl) {
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.parseColor("#101010"));
        top.setPadding(dp(4), dp(24), dp(4), dp(2));
        back = barButton("‹", new View.OnClickListener() { @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); } });
        forward = barButton("›", new View.OnClickListener() { @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); } });
        Button reload = barButton("Reload", new View.OnClickListener() { @Override public void onClick(View v) { web.reload(); } });
        title = new TextView(activity);
        title.setTextColor(Color.parseColor("#cfcfcf"));
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = barButton("Close", new View.OnClickListener() { @Override public void onClick(View v) { dialog.dismiss(); } });
        close.setTextColor(Color.parseColor("#ff8a80"));
        top.addView(back);
        top.addView(forward);
        top.addView(reload);
        top.addView(title);
        top.addView(close);
        root.addView(top);

        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(bar);

        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setBackgroundColor(Color.parseColor("#1b5e20"));
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        status.setVisibility(View.GONE);
        root.addView(status);

        web = new WebView(activity);
        web.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);
        dialog.setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // a web view says "wv" and "Version/4.0" in its user agent, which is what browser checks look for: say what Chrome says
        ua = s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", "");
        s.setUserAgentString(ua);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch));            // intent:, market:, file: and the like never open
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                title.setText(host(url));
                refreshNav();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                refreshNav();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                title.setText(host(v.getUrl()) + "  ·  " + t);
            }
        });
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                startDownload(url, userAgent);
            }
        });
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                    if (web.canGoBack()) web.goBack(); else dialog.dismiss();
                    return true;
                }
                return code == KeyEvent.KEYCODE_BACK;
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                cancel = true;
                try {
                    web.stopLoading();
                    web.loadUrl("about:blank");
                    web.destroy();
                } catch (Throwable ignored) {
                }
                events.onClosed();
            }
        });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        web.loadUrl(startUrl);
    }

    private static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private void refreshNav() {
        if (back != null) back.setAlpha(web.canGoBack() ? 1f : 0.35f);
        if (forward != null) forward.setAlpha(web.canGoForward() ? 1f : 0.35f);
    }

    private void say(final String text, final boolean error) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (status == null) return;
                status.setText(text);
                status.setBackgroundColor(Color.parseColor(error ? "#7f1d1d" : "#1b5e20"));
                status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void startDownload(final String url, final String agent) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            say("This download cannot be saved here (" + (url == null ? "" : url.split(":")[0]) + ")", true);
            return;
        }
        final String referer = web.getUrl();
        cancel = false;
        say("Downloading...", false);
        events.onStatus("Downloading...", 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BrowserDownload.Result r = BrowserDownload.download(url, ua.isEmpty() ? agent : ua, new BrowserDownload.Cookies() {
                        @Override
                        public String forUrl(String u) {
                            return CookieManager.getInstance().getCookie(u);
                        }
                    }, referer, dir, new BrowserDownload.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            int pct = total > 0 ? (int) (100 * done / total) : 0;
                            String t = "Downloading " + (done / 1048576) + (total > 0 ? " of " + (total / 1048576) + " MB (" + pct + "%)" : " MB");
                            say(t, false);
                            events.onStatus(t, pct);
                        }

                        @Override
                        public boolean cancelled() {
                            return cancel;
                        }
                    });
                    say("Saved " + r.file.getName(), false);
                    if (events.onDownloaded(r.file)) activity.runOnUiThread(new Runnable() { @Override public void run() { close(); } });
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    if (!"cancelled".equals(m)) {
                        say("Download failed: " + m, true);
                        events.onFailed(m);
                    }
                }
            }
        }, "helper-browser-download").start();
    }
}
