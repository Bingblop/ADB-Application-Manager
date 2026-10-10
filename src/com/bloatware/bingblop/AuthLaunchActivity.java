package com.bloatware.bingblop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.security.SecureRandom;

/**
 * The door of the Authorization Manager: an app, a Tasker or MacroDroid task or an adb command starts this activity with the code in the "auth" extra, and
 * with a "package" (an app to open) or a "uri" (an address, or an intent: URI) to launch. It has no screen. Off until the person turns it on in About;
 * answers with RESULT_OK or RESULT_CANCELED and a "message" extra.
 *
 *   adb shell am start -n com.bloatware.bingblop/.AuthLaunchActivity -a com.bloatware.bingblop.action.AUTH_LAUNCH --es auth CODE --es package com.android.settings
 */
public class AuthLaunchActivity extends Activity {
    private static AuthManager shared;

    /** The one manager the app uses (the switch, the code and the lock after wrong guesses are the same everywhere). */
    public static synchronized AuthManager manager(Context c) {
        if (shared == null) {
            final SharedPreferences sp = c.getApplicationContext().getSharedPreferences("auth_manager", Context.MODE_PRIVATE);
            shared = new AuthManager(new AuthManager.Store() {
                @Override public String get(String k) { return sp.getString(k, null); }
                @Override public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
            }, new SecureRandom());
        }
        return shared;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String message;
        boolean ok = false;
        Intent in = getIntent();
        AuthManager m = manager(this);
        long now = System.currentTimeMillis();
        String pkg = in == null ? null : in.getStringExtra(AuthManager.EXTRA_PACKAGE);
        String uri = in == null ? null : in.getStringExtra(AuthManager.EXTRA_URI);
        String what = pkg != null ? "package " + pkg : uri != null ? "address " + uri : "nothing asked";
        AuthManager.Verdict v = m.check(in == null ? null : in.getStringExtra(AuthManager.EXTRA_AUTH), now);
        switch (v) {
            case DISABLED: message = "The Authorization Manager is off. Turn it on in About."; break;
            case LOCKED: message = "Too many wrong codes. Try again in " + m.lockedSeconds(now) + " seconds."; break;
            case MISSING: message = "The auth extra is missing."; break;
            case WRONG: message = "That code is not right."; break;
            default: {
                try {
                    message = launch(pkg, uri);
                    ok = message.isEmpty();
                    if (ok) message = "Launched";
                } catch (Exception e) {
                    message = e.getMessage() != null ? e.getMessage() : "That could not be launched.";
                }
            }
        }
        if (v != AuthManager.Verdict.DISABLED) m.record(now, ok ? "ok" : v == AuthManager.Verdict.OK ? "failed" : v.name().toLowerCase(java.util.Locale.ROOT), what);
        setResult(ok ? RESULT_OK : RESULT_CANCELED, new Intent().putExtra("message", message));
        finish();
    }

    /** Opens what was asked for; returns "" when it went, else the reason it did not. */
    private String launch(String pkg, String uri) throws Exception {
        Intent go;
        if (pkg != null && !pkg.isEmpty()) {
            if (!AuthManager.validPackage(pkg)) return "That is not a package name.";
            if (pkg.equals(getPackageName())) return "This app does not open itself through its own door.";
            go = getPackageManager().getLaunchIntentForPackage(pkg);
            if (go == null) return pkg + " is not installed, or has nothing to open.";
        } else if (uri != null && !uri.isEmpty()) {
            if (uri.startsWith("intent:")) {
                go = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                go.setSelector(null);
                if (go.getComponent() != null && getPackageName().equals(go.getComponent().getPackageName())) return "This app does not open itself through its own door.";
                if (getPackageName().equals(go.getPackage())) return "This app does not open itself through its own door.";
                Uri d = go.getData();
                if (d != null && d.getScheme() != null && !AuthManager.allowedScheme(d.getScheme())) return "An address of that kind is not opened through this door.";
            } else {
                Uri u = Uri.parse(uri);
                if (!AuthManager.allowedScheme(u.getScheme())) return "An address of that kind is not opened through this door.";
                go = new Intent(Intent.ACTION_VIEW, u);
            }
        } else {
            return "Say what to open: a package extra or a uri extra.";
        }
        go.setFlags(AuthManager.strippedFlags(go.getFlags(), new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION}));
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (go.resolveActivity(getPackageManager()) == null) return "No app on this phone opens that.";
        startActivity(go);
        return "";
    }
}
