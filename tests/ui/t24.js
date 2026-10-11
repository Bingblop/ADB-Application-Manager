// Profiles: deleting an unwatched profile leaves the native watch setting alone; the watched one clears it
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = []; window.__store = {};
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore(k, v) { window.__store[k] = v; return true; }, loadStore(k) { return window.__store[k] || ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: true, isFrozen: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      setWatchedProfile(n) { window.__calls.push('watch:' + JSON.stringify(n)); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => {
    profiles = [
      { id: 'watched', name: 'Watched', created: 1, watch: true, apps: [{ pkg: 'com.a', name: 'A', state: 'disabled' }] },
      { id: 'other', name: 'Other', created: 2, watch: false, apps: [{ pkg: 'com.a', name: 'A', state: 'disabled' }] },
    ];
    openProfiles();
  });
  // deleting the NON-watched profile must not touch the native preference
  await page.evaluate(() => deleteProfile('other'));
  console.log('delete non-watched -> bridge calls:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('watched profile still present:', await page.evaluate(() => profiles.some(p => p.id === 'watched')));
  // deleting the WATCHED profile must clear the native preference
  await page.evaluate(() => deleteProfile('watched'));
  console.log('delete watched -> bridge calls:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('profiles left:', await page.evaluate(() => profiles.length));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
