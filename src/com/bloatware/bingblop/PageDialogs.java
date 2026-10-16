package com.bloatware.bingblop;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The page's alert(), confirm() and prompt() as dialogs in the app's own look, instead of the WebView's grey system dialog titled
 * 'The page at "file://" says:'. Every synchronous call in assets/index.html keeps working unchanged: the WebView waits for the
 * JsResult, and this class completes it exactly once, whatever happens to the dialog (OK, Cancel, Back, a tap outside, the window
 * closing). A JsResult left pending would freeze the page.
 *
 * The colours come from the page (setDialogTheme, sent by applyTheme()); until it has sent them the defaults are those of the default
 * dark theme. The title is the one the page named for the next dialog (nextDialogTitle in index.html), else one taken from the message
 * (see {@link PageDialogText#split}).
 */
final class PageDialogs {
    private final Activity activity;

    // The theme, written by the bridge thread and read on the UI thread
    private volatile int cCard = 0xFF141A28, cSurface = 0xFF10141E, cText = 0xFFFFFFFF, cMuted = 0xFF8E9BAE;
    private volatile int cAccent = 0xFF00E5FF, cOnAccent = 0xFF000000, cBorder = 0x17FFFFFF;
    private volatile float radiusDp = 24f;
    private volatile boolean light;
    private volatile String tAlert, tConfirm, tPrompt, lOk = "OK", lCancel = "Cancel";

    /** The title the page named for the next dialog, and when (a title not used within a few seconds is not used at all). */
    private final AtomicReference<Object[]> nextTitle = new AtomicReference<Object[]>();
    private static final long TITLE_TTL_MS = 5000;

    /** The dialogs on screen now; touched on the UI thread. */
    private final Set<Dialog> open = new HashSet<Dialog>();

    PageDialogs(Activity activity) { this.activity = activity; }

    // ---------------------------------------------------------------- from the page (bridge thread)

    /** The page's colours as JSON: card, surface, text, muted, accent, onAccent, border, radius (px), light, and the words of the dialog. */
    void setTheme(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json == null ? "{}" : json);
            cCard = PageDialogText.parseColor(o.optString("card", null), cCard);
            cSurface = PageDialogText.parseColor(o.optString("surface", null), cSurface);
            cText = PageDialogText.parseColor(o.optString("text", null), cText);
            cMuted = PageDialogText.parseColor(o.optString("muted", null), cMuted);
            cAccent = PageDialogText.parseColor(o.optString("accent", null), cAccent);
            cOnAccent = PageDialogText.parseColor(o.optString("onAccent", null), cOnAccent);
            cBorder = PageDialogText.parseColor(o.optString("border", null), cBorder);
            double r = o.optDouble("radius", radiusDp);
            if (r >= 0 && r <= 40) radiusDp = (float) r;
            light = o.optBoolean("light", light);
            tAlert = word(o, "alertTitle", tAlert);
            tConfirm = word(o, "confirmTitle", tConfirm);
            tPrompt = word(o, "promptTitle", tPrompt);
            lOk = word(o, "ok", lOk);
            lCancel = word(o, "cancel", lCancel);
        } catch (Throwable ignored) { /* a damaged message keeps the colours there were */ }
    }

    private static String word(org.json.JSONObject o, String key, String dflt) {
        String s = PageDialogText.clean(o.optString(key, ""));
        return s.isEmpty() ? dflt : s;
    }

    /** The title of the next dialog (used once). */
    void setNextTitle(String title) {
        String t = PageDialogText.clean(title);
        nextTitle.set(t.isEmpty() ? null : new Object[] { t, android.os.SystemClock.elapsedRealtime() });
    }

    private String takeTitle() {
        Object[] t = nextTitle.getAndSet(null);
        if (t == null) return null;
        return android.os.SystemClock.elapsedRealtime() - (Long) t[1] <= TITLE_TTL_MS ? (String) t[0] : null;
    }

    // ---------------------------------------------------------------- the WebView's side

    WebChromeClient client() {
        return new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return show(PageDialogText.ALERT, message, null, result);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return show(PageDialogText.CONFIRM, message, null, result);
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return show(PageDialogText.PROMPT, message, defaultValue == null ? "" : defaultValue, result);
            }
        };
    }

    /** The window is closing: every dialog still open is closed, and its JsResult answered with Cancel. */
    void dismissAll() {
        for (Dialog d : new ArrayList<Dialog>(open)) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
        open.clear();
    }

    // ---------------------------------------------------------------- the dialog (UI thread)

    private boolean show(final int kind, String message, final String value, final JsResult result) {
        final String explicit = takeTitle();
        final AtomicBoolean done = new AtomicBoolean(false);
        if (activity.isFinishing() || activity.isDestroyed()) { result.cancel(); return true; }
        final Dialog dlg = new Dialog(activity);
        final EditText[] field = { null };
        // every way out ends here; only the first one answers the page
        final Runnable cancel = new Runnable() {
            @Override
            public void run() { if (done.compareAndSet(false, true)) result.cancel(); }
        };
        final Runnable ok = new Runnable() {
            @Override
            public void run() {
                if (!done.compareAndSet(false, true)) return;
                if (kind == PageDialogText.PROMPT && result instanceof JsPromptResult) {
                    ((JsPromptResult) result).confirm(field[0] == null ? "" : field[0].getText().toString());
                } else {
                    result.confirm();
                }
            }
        };
        try {
            String fallback = kind == PageDialogText.CONFIRM ? tConfirm : kind == PageDialogText.PROMPT ? tPrompt : tAlert;
            PageDialogText.Parts parts = PageDialogText.split(kind, explicit, message, fallback);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setContentView(build(dlg, kind, parts, value, field, ok, cancel));
            dlg.setCancelable(true);
            dlg.setCanceledOnTouchOutside(true);
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    open.remove(dlg);
                    cancel.run();                     // a dialog gone without OK is a Cancel (Back, a tap outside, the window closing)
                }
            });
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(light ? 0.32f : 0.6f);
                w.setLayout(Math.min(dp(400), activity.getResources().getDisplayMetrics().widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT);
                if (kind == PageDialogText.PROMPT) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            open.add(dlg);
            dlg.show();
            if (w != null) w.setLayout(Math.min(dp(400), activity.getResources().getDisplayMetrics().widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (field[0] != null) { field[0].requestFocus(); field[0].selectAll(); }
        } catch (Throwable t) {
            open.remove(dlg);
            cancel.run();                             // no window to show it on: the page must not wait for an answer that never comes
        }
        return true;
    }

    private View build(final Dialog dlg, int kind, PageDialogText.Parts parts, String value, final EditText[] field, final Runnable ok, final Runnable cancel) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(Math.max(1, dp(1)), cBorder);
        card.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(parts.title);
        title.setTextColor(cText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (parts.body != null && !parts.body.trim().isEmpty()) {
            // a long message scrolls, so the buttons are always on screen
            final int cap = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
            ScrollView sv = new ScrollView(activity) {
                @Override
                protected void onMeasure(int wSpec, int hSpec) {
                    super.onMeasure(wSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                }
            };
            TextView body = new TextView(activity);
            body.setText(parts.body);
            body.setTextColor(PageDialogText.mix(cText, cCard, 0.82));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            body.setLineSpacing(0, 1.15f);
            body.setTextIsSelectable(false);
            sv.addView(body);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            card.addView(sv, lp);
        }

        if (kind == PageDialogText.PROMPT) {
            final EditText et = new EditText(activity);
            et.setText(value == null ? "" : value);
            et.setTextColor(cText);
            et.setHintTextColor(cMuted);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            et.setPadding(dp(14), dp(12), dp(14), dp(12));
            et.setHighlightColor(PageDialogText.withAlpha(cAccent, 0x66));
            StateListDrawable st = new StateListDrawable();
            st.addState(new int[] { android.R.attr.state_focused }, field(cAccent, 2));
            st.addState(new int[0], field(cBorder, 1));
            et.setBackground(st);
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    GradientDrawable cur = new GradientDrawable();
                    cur.setColor(cAccent);
                    cur.setSize(dp(2), dp(20));
                    et.setTextCursorDrawable(cur);
                } catch (Throwable ignored) {}
            }
            et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
                    if (actionId == EditorInfo.IME_ACTION_DONE || enter) { ok.run(); dlg.dismiss(); return true; }
                    return false;
                }
            });
            field[0] = et;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            card.addView(et, lp);
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (kind != PageDialogText.ALERT) {
            TextView no = button(lCancel, false);
            no.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { cancel.run(); dlg.dismiss(); }
            });
            row.addView(no);
        }
        TextView yes = button(lOk, true);
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { ok.run(); dlg.dismiss(); }
        });
        LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yl.setMarginStart(dp(8));
        row.addView(yes, yl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(20);
        card.addView(row, rl);
        return card;
    }

    private GradientDrawable field(int stroke, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(cSurface);
        d.setCornerRadius(dp(8));
        d.setStroke(Math.max(1, dp(width)), stroke);
        return d;
    }

    /** The accent button (filled, OK) or the text button (Cancel), each with a ripple. */
    private TextView button(String label, boolean primary) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setMinWidth(dp(72));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        shape.setColor(primary ? cAccent : Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(22));
        mask.setColor(Color.WHITE);
        int ripple = primary ? PageDialogText.withAlpha(cOnAccent, 0x33) : PageDialogText.withAlpha(cAccent, 0x33);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask));
        b.setTextColor(primary ? cOnAccent : cAccent);
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics()));
    }
}
