package com.bloatware.bingblop;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * The accessibility service of the SD Maid SE tab: AppCleaner uses it to open the App info screen of an app, find Storage and tap Clear cache
 * ({@link SdmAutomation}). It does nothing by itself: it holds the connection and gives {@link SdmAutomation} the window to read. It refuses to run
 * without the consent given in the app (it turns itself off at once), and the cover with the Cancel button is {@link SdmAutomation}'s.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se (app-common-automation:
 * AutomationService.kt). Changed for this port: no event flow, the window is read on demand; the consent lives in {@link SdmAutomation}.
 */
public class SdmAccessService extends AccessibilityService {
    static volatile SdmAccessService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!SdmAutomation.hasConsent(this)) {
            // no consent in the app: this service must not act
            try { disableSelf(); } catch (RuntimeException ignored) {}
            return;
        }
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* the window is read when AppCleaner needs it */ }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
