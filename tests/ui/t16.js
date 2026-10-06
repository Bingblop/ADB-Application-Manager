// Versions in app list and menu: update arrows, one bridge read, menu dates and Update hint, remembered toggle
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const mk = async (store) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.__errors = []; page.on('pageerror', e => page.__errors.push(e.message));
    await page.addInitScript(store => {
      const st = { calls: [], stateCalls: 0, store: store || {} }; window.__st = st;
      const updates = [{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', installedVersion: '26.0.3.1', availableVersion: '27.1.0.103', source: 'galaxy', isSystem: true },
                       { pkg: 'com.duckduckgo.mobile.android', name: 'DuckDuckGo', installedVersion: '5.210.0', availableVersion: '5.212.1', source: 'github', noApk: true, page: 'https://github.com/duckduckgo/Android/releases' }];
      const apps = [{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0.3.1' },
                    { pkg: 'com.duckduckgo.mobile.android', name: 'DuckDuckGo', version: '5.210.0' },
                    { pkg: 'org.fdroid.fdroid', name: 'F-Droid', version: '1.21.0' },
                    { pkg: 'com.samsung.android.bixby.agent', name: 'Bixby', isSystem: true, isUninstalled: true, version: '' }];
      for (let i = 0; i < 120; i++) apps.push({ pkg: 'com.filler.app' + i, name: 'Filler ' + i, version: '1.' + i });
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
        getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore(k, v) { st.store[k] = v; }, loadStore(k) { return st.store[k] || ''; },
        loadPackages() { return JSON.stringify(apps); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getUpdateState() { st.stateCalls++; return JSON.stringify({ running: false, checkedAt: Date.now(), updates }); },
        getAppDetails(p) { return JSON.stringify({ versionName: p.includes('sbrowser') ? '26.0.3.1' : '5.210.0', versionCode: 260003100, firstInstallTime: Date.UTC(2024, 0, 15), lastUpdateTime: p.includes('sbrowser') ? Date.UTC(2026, 8, 20) : Date.UTC(2024, 0, 15), permissions: [] }); },
        checkForUpdates() { st.calls.push('check'); }, installUpdate(pkg) { st.calls.push('install:' + pkg); }, openUrl(u) { st.calls.push('url:' + u); },
      };
    }, store);
    await page.goto(PAGE); await page.waitForTimeout(400);
    return page;
  };
  let page = await mk(null);
  const label = async pkg => page.locator(`[id="card_${pkg}"] .badge-row`).innerText();
  console.log('row sbrowser:', await label('com.sec.android.app.sbrowser'));
  console.log('row fdroid:  ', await label('org.fdroid.fdroid'));
  console.log('row uninstalled (no version):', await label('com.samsung.android.bixby.agent'));
  console.log('bridge getUpdateState calls for 124 rows:', await page.evaluate(() => window.__st.stateCalls));
  await page.screenshot({ path: 'list_versions.png', clip: { x: 0, y: 340, width: 400, height: 260 } });
  // App menu: dates + update hint
  await page.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await page.waitForTimeout(100);
  console.log('menu sbrowser:', (await page.locator('#inspectorModal .sheet-title').innerText()).replace(/\n+/g, ' | '));
  await page.screenshot({ path: 'menu_hint.png', clip: { x: 0, y: 380, width: 400, height: 230 } });
  await page.click('#sheetUpdateHint >> button:has-text("Update")'); await page.waitForTimeout(200);
  console.log('after Update tap → active tab:', await page.locator('.tab-btn.active').innerText(), '| calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  await page.evaluate(() => { switchView('apps'); openInspector('com.duckduckgo.mobile.android'); }); await page.waitForTimeout(100);
  console.log('menu ddg (no apk, same-day install):', (await page.locator('#inspectorModal .sheet-title').innerText()).replace(/\n+/g, ' | '));
  await page.click('#sheetUpdateHint >> button:has-text("Release")');
  await page.evaluate(() => { closeInspector(); openInspector('org.fdroid.fdroid'); }); await page.waitForTimeout(100);
  console.log('hint shown for fdroid (no update):', await page.isVisible('#sheetUpdateHint'));
  await page.evaluate(() => closeInspector());
  // Off by default (a less cluttered list row); toggle on + remember
  console.log('pill starts off, no version in the row by default:', !(await page.locator('#versionTogglePill').evaluate(e => e.classList.contains('active'))));
  await page.click('#versionTogglePill');
  console.log('toggle on → row:', await label('org.fdroid.fdroid'));
  await page.click('#filterScroll .filter-pill[data-filter="system"]');
  console.log('toggle still on after filter change:', await page.locator('#versionTogglePill').evaluate(e => e.classList.contains('active')));
  const store = await page.evaluate(() => window.__st.store);
  const errs1 = page.__errors;
  page = await mk(store);
  console.log('RELAUNCH toggle:', await page.locator('#versionTogglePill').evaluate(e => e.classList.contains('active')) ? 'on' : 'off', '| row:', await page.locator('[id="card_com.sec.android.app.sbrowser"] .badge-row').innerText());
  console.log('errors:', JSON.stringify(errs1.concat(page.__errors)));
  await b.close(); })();
