// Backups in Read-Only mode: Create backup is blocked with the privilege modal and never reaches the bridge
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, saveStore() {}, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0' }]); },
      // Read-Only: modeAvailable true (apps still listed) but isPrivileged false
      getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'unprivileged', activeMode: 'unprivileged', modeAvailable: true, isPrivileged: false }); },
      getAppDetails() { return '{}'; }, hasRoot() { return false; },
      backupApp(p, d) { window.__calls.push('backup:' + p); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => openBackups('com.sec.android.app.sbrowser')); await page.waitForTimeout(200);
  await page.click('#backupTarget button:has-text("Create backup")'); await page.waitForTimeout(200);
  console.log('Read-Only: backupApp not called:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('privilege-required modal shown:', await page.locator('.modal-overlay.show').count() > 0);
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
