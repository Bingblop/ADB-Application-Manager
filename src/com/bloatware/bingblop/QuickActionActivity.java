package com.bloatware.bingblop;

/**
 * Runs a quick action (switch mode, force-stop the quick list) for a tile or widget tap without
 * showing the app. It is MainActivity minus the WebView: the backends (ADB, Shizuku, Root) live in
 * MainActivity, so this reuses them instead of duplicating that code.
 */
public class QuickActionActivity extends MainActivity {

    @Override
    protected boolean isHeadless() {
        return true;
    }
}
