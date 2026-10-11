// Suspended apps: badge, filter and count, menu Suspend/Unsuspend, batch suspend (confirm) and batch unsuspend
const { chromium, PAGE } = require('./lib/pw');
const appBatchMock = require('./lib/appbatch_mock');
(async () => {
  const b = await chromium.launch();
  for (const dark of [true, false]) {
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(d => {
    const apps = { 'com.facebook.katana': { name: 'Facebook', isSuspended: true }, 'com.netflix.mediaclient': { name: 'Netflix' }, 'com.spotify.music': { name: 'Spotify' }, 'com.samsung.android.bixby.agent': { name: 'Bixby', isSystem: true } };
    const st = { calls: [], prefs: JSON.stringify({ version: 2, preset: 'material3', appearance: d ? 'dark' : 'light', overrides: { dark: {}, light: {} } }) }; window.__st = st;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return st.prefs; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return d; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify(Object.entries(apps).map(([pkg, a]) => Object.assign({ pkg, isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false }, a))); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      executeAppAction(action, pkg) { st.calls.push(action + ':' + pkg);
        if (action === 'suspend') apps[pkg].isSuspended = true;
        if (action === 'unsuspend' || action === 'unfreeze') apps[pkg].isSuspended = false;
        return `Package ${pkg} new suspended state: ${action === 'suspend'}`; },
    };
  }, dark);
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(300);
  const badges = async () => (await page.locator('.app-card').evaluateAll(cs => cs.map(c => c.querySelector('.app-name').innerText + (c.classList.contains('suspended') ? '[⏸]' : '') + ' ' + [...c.querySelectorAll('.tag-badge')].map(t => t.innerText).join('/')))).join(' | ');
  console.log(`--- ${dark ? 'DARK' : 'LIGHT'} ---`);
  console.log('list:', await badges());
  console.log('suspended pill:', await page.locator('#countSuspended').innerText());
  if (!dark) { await page.screenshot({ path: 'suspended_light.png' }); continue; }
  // Single app menu
  await page.evaluate(() => openInspector('com.facebook.katana')); await page.waitForTimeout(150);
  console.log('Facebook menu → Suspend visible:', await page.isVisible('#sheetBtnSuspend'), 'Unsuspend visible:', await page.isVisible('#sheetBtnUnsuspend'));
  await page.click('#sheetBtnUnsuspend'); await page.waitForTimeout(600); await page.evaluate(() => closeCommandResultsModal());
  // The single-app sheet is left open behind the result dialog now (not auto-closed) - close it by hand before
  // reopening it for Netflix, same as the person tapping its own close button once they're done looking.
  await page.evaluate(() => closeInspector());
  await page.evaluate(() => openInspector('com.netflix.mediaclient')); await page.waitForTimeout(150);
  console.log('Netflix menu → Suspend visible:', await page.isVisible('#sheetBtnSuspend'), 'Unsuspend visible:', await page.isVisible('#sheetBtnUnsuspend'));
  await page.click('#sheetBtnSuspend'); await page.waitForTimeout(600); await page.evaluate(() => closeCommandResultsModal());
  await page.evaluate(() => closeInspector());
  console.log('after single actions:', await badges());
  // Batch: suspend Spotify + Bixby (needs confirmation), then batch unsuspend
  await page.evaluate(() => { toggleSelectPkg('com.spotify.music'); toggleSelectPkg('com.samsung.android.bixby.agent'); batchKeepSelection = false; });
  await page.waitForTimeout(350);
  console.log('FAB shown, sheet collapsed after select:', await page.isVisible('#batchFab.show'), await page.isVisible('#floatingBatchBar.show'));
  await page.evaluate(() => expandBatchPanel());
  await page.click('.batch-grid-btn:has-text("Suspend")'); await page.waitForTimeout(150);
  console.log('confirm shown:', await page.locator('#batchConfirmTitle').innerText(), '|', await page.locator('#batchConfirmSubtitle').innerText());
  await page.click('#batchConfirmExecuteBtn'); await page.waitForTimeout(600); await page.evaluate(() => closeCommandResultsModal());
  console.log('after batch suspend:', await badges());
  await page.click('.filter-pill:has-text("Suspended")'); await page.waitForTimeout(200);
  console.log('filter Suspended shows:', await page.locator('.app-name').allInnerTexts());
  await page.screenshot({ path: 'suspended_dark.png' });
  await page.evaluate(() => { setFilter('all'); toggleSelectPkg('com.spotify.music'); toggleSelectPkg('com.netflix.mediaclient'); batchKeepSelection = false; expandBatchPanel(); });
  await page.click('.batch-grid-btn:has-text("Unsuspend")'); await page.waitForTimeout(600); await page.evaluate(() => closeCommandResultsModal());
  console.log('after batch unsuspend (no confirm):', await badges());
  await page.evaluate(() => { toggleSelectPkg('com.spotify.music'); batchKeepSelection = false; expandBatchPanel(); });
  await page.screenshot({ path: 'batch_sheet.png' });
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  console.log('errors:', JSON.stringify(errors));
  }
  await b.close(); })();
