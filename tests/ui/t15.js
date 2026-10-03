// App menu header: version line for normal, uninstalled, N/A-version and bad-data apps, dark and light themes
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  for (const dark of [true, false]) {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(d => {
      const details = {
        'com.sec.android.app.sbrowser': { versionName: '27.1.0.103', versionCode: 2701000103, permissions: [], appopsRaw: '', activityInfo: [], services: [] },
        'com.example.noname': { versionName: 'N/A', versionCode: 7, permissions: [] },
        'com.samsung.android.bixby.agent': { versionName: '3.3.55.4', versionCode: 333554000, permissions: [] },
        'com.broken.app': 'not json',
      };
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: d ? 'dark' : 'light', overrides: { dark: {}, light: {} } }); }, savePreferences() {}, loadCustomLists() { return '[]'; },
        getSystemInfo() { return '{}'; }, isSystemDarkMode() { return d; }, setSystemBarColor() {}, saveStore() {}, loadStore() { return ''; },
        loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true }, { pkg: 'com.samsung.android.bixby.agent', name: 'Bixby', isSystem: true, isUninstalled: true, isFrozen: true }]); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getAppDetails(p) { const v = details[p]; return typeof v === 'string' ? v : JSON.stringify(v); },
      };
    }, dark);
    await page.goto(PAGE); await page.waitForTimeout(300);
    const show = async pkg => { await page.evaluate(p => openInspector(p), pkg); await page.waitForTimeout(80); return (await page.locator('#inspectorModal .sheet-title').innerText()).replace(/\n/g, ' | '); };
    console.log(dark ? 'DARK' : 'LIGHT');
    console.log('  normal:     ', await show('com.sec.android.app.sbrowser'));
    if (dark) {
      console.log('  uninstalled:', await show('com.samsung.android.bixby.agent'));
      console.log('  no name:    ', await show('com.example.noname'));
      console.log('  bad data:   ', await show('com.broken.app'));
      await show('com.sec.android.app.sbrowser');
    }
    await page.screenshot({ path: `version_${dark ? 'dark' : 'light'}.png`, clip: { x: 0, y: 380, width: 400, height: 200 } });
    console.log('  errors:', JSON.stringify(errors));
  }
  await b.close(); })();
