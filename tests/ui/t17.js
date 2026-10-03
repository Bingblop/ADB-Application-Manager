// Apps list: sort by name/updated/installed/size/updates, Recent filter, CSV export, menu sizes, saved sort
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const mk = async (store, access) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.__errors = []; page.on('pageerror', e => page.__errors.push(e.message));
    await page.addInitScript(([store, access]) => {
      const day = 86400000, now = Date.now();
      const st = { calls: [], store: store || {}, access: access, saved: null }; window.__st = st;
      const apps = [
        { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0.3.1', installedAt: now - 900 * day, updatedAt: now - 2 * day, apkSize: 180e6 },
        { pkg: 'com.duckduckgo.mobile.android', name: 'DuckDuckGo', version: '5.210.0', installedAt: now - 30 * day, updatedAt: now - 20 * day, apkSize: 40e6 },
        { pkg: 'org.fdroid.fdroid', name: 'F-Droid', version: '1.21.0', installedAt: now - 3 * day, updatedAt: now - 3 * day, apkSize: 12e6 },
        { pkg: 'com.whatsapp', name: 'WhatsApp, "Messenger"', version: '2.24.1', installedAt: now - 400 * day, updatedAt: now - 1 * day, apkSize: 90e6, isSuspended: true },
        { pkg: 'com.samsung.android.bixby.agent', name: 'Bixby', isSystem: true, isUninstalled: true, version: '' }];
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
        getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore(k, v) { st.store[k] = v; }, loadStore(k) { return st.store[k] || ''; },
        loadPackages() { return JSON.stringify(apps); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getUpdateState() { return JSON.stringify({ updates: [{ pkg: 'com.duckduckgo.mobile.android', availableVersion: '5.212.1', source: 'github' }] }); },
        getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 1, permissions: [] }); },
        getAppSizes(p) { return JSON.stringify(st.access ? { apk: 180e6, splits: 3, usageAccess: true, stats: { app: 200e6, data: 512e6, cache: 34e6 } } : { apk: 180e6, splits: 3, usageAccess: false }); },
        getAllAppSizes() { st.calls.push('allSizes'); return JSON.stringify(st.access ? { 'com.sec.android.app.sbrowser': 700e6, 'com.duckduckgo.mobile.android': 150e6, 'org.fdroid.fdroid': 20e6, 'com.whatsapp': 2.5e9, __usageAccess: true } : { __usageAccess: false }); },
        requestUsageAccess() { st.calls.push('usageAccess'); st.access = true; return 'granted'; },
        extractApk(p) { st.calls.push('extract:' + p); setTimeout(() => window.onApkExtracted(JSON.stringify({ ok: true, pkg: p, path: 'Download/ADB App Manager/APKs/Samsung Internet_26.0.3.1.apks', bytes: 180e6, splits: 3 })), 50); },
        saveTextToDownloads(n, t) { st.saved = { name: n, text: t }; return 'Download/ADB App Manager/' + n; },
      };
    }, [store, access]);
    await page.goto(PAGE); await page.waitForTimeout(600);
    return page;
  };
  let page = await mk(null, false);
  const names = async () => (await page.locator('#appsListContainer .app-name').allInnerTexts()).join(', ');
  const metas = async () => (await page.locator('#appsListContainer .app-meta-line').allInnerTexts()).join(' ');
  console.log('name:     ', await names());
  for (const s of ['updated', 'installed', 'size', 'updates']) {
    await page.selectOption('#appSort', s); await page.waitForTimeout(150);
    console.log((s + ':').padEnd(10), await names(), '|', await metas());
  }
  await page.selectOption('#appSort', 'name');
  console.log('recent pill:', await page.locator('.filter-pill[data-filter="recent"]').innerText());
  await page.click('.filter-pill[data-filter="recent"]');
  console.log('recent apps:', await names());
  await page.click('.filter-pill[data-filter="all"]');
  // Export
  await page.click('#view-apps >> text=Export');
  const saved = await page.evaluate(() => window.__st.saved);
  console.log('export file:', saved.name, '| toast:', await page.locator('#toastMsg').innerText());
  console.log(saved.text.split('\n').filter(l => l.startsWith('"Whats')).join(''));
  // App menu sizes + usage access + extract
  await page.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await page.waitForTimeout(100);
  console.log('sizes (no access):', await page.locator('#sheetSizes').innerText());
  await page.click('#sheetSizes .link'); await page.waitForTimeout(100);
  console.log('sizes (granted):  ', await page.locator('#sheetSizes').innerText());
  await page.click('#sheetBtnExtract'); await page.waitForTimeout(200);
  console.log('extract toast:', await page.locator('#toastMsg').innerText());
  await page.screenshot({ path: 'menu_sizes.png', clip: { x: 0, y: 380, width: 400, height: 330 } });
  await page.evaluate(() => closeInspector());
  await page.selectOption('#appSort', 'size'); await page.waitForTimeout(150);
  console.log('size (with data):', await names(), '|', await metas());
  await page.screenshot({ path: 'sort_size.png', clip: { x: 0, y: 260, width: 400, height: 420 } });
  const store = await page.evaluate(() => window.__st.store);
  const calls = await page.evaluate(() => window.__st.calls);
  const errs = page.__errors;
  page = await mk(store, true);
  console.log('RELAUNCH sort:', await page.locator('#appSort').inputValue(), '|', await names());
  console.log('calls:', JSON.stringify(calls), '| errors:', JSON.stringify(errs.concat(page.__errors)));
  await b.close(); })();
