// Tab bar order, header Colors icon, tab highlighting, and main update check also running self-update check
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  // ---- tab order ----
  const tabs = (await page.locator('.tab-btn').allInnerTexts()).map(t => t.trim());
  console.log('1. tab order:', JSON.stringify(tabs));
  console.log('   Saved App Lists right after Application Manager:', tabs[0].includes('Application') && tabs[1].includes('Saved App'));
  console.log('   About is the last tab, Logcat Viewer right before it:', tabs[tabs.length - 1].includes('About') && tabs[tabs.length - 2].includes('Logcat'));
  console.log('   no "Color" tab button:', !tabs.some(t => t.includes('Color')));

  // ---- the header gear opens Settings ----
  console.log('2. header settings icon present:', await page.isVisible('#prefsHeaderBtn'));
  await page.click('#prefsHeaderBtn'); await page.waitForTimeout(150);
  console.log('   opens the Settings view:', await page.evaluate(() => document.getElementById('view-prefs').classList.contains('active')));
  console.log('   no tab-btn marked active on Settings:', await page.evaluate(() => !document.querySelector('.tab-btn.active')));

  // ---- navigating back to a real tab still works (index mapping intact) ----
  await page.click('.tab-btn[data-tab="apps"]'); await page.waitForTimeout(100);
  console.log('3. back to Applications active:', await page.evaluate(() => { const a = document.querySelector('.tab-btn.active'); return !!a && a.innerText.includes('Application'); }));
  await page.evaluate(() => switchView('logcat')); await page.waitForTimeout(100);
  console.log('   switchView(logcat) activates the Logcat Viewer tab:', await page.evaluate(() => { const a = document.querySelector('.tab-btn.active'); return !!a && a.innerText.includes('Logcat'); }));

  // ---- main "Check for Updates" also triggers the self-update check ----
  await page.evaluate(() => {
    window.__selfCalled = false;
    window.AndroidBridge.checkSelfUpdate = () => { window.__selfCalled = true; };
    window.AndroidBridge.getUpdateState = () => '{}';
    window.AndroidBridge.checkForUpdates = () => {};
    checkUpdatesUI(true);
  });
  console.log('4. main Check also triggers self-update check:', await page.evaluate(() => window.__selfCalled));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
