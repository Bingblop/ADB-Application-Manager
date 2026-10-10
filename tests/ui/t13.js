// Saved filters, history (undo, copy log) and Updates tab (check, update one/all, error), kept on relaunch
const { chromium, PAGE, fixture } = require('./lib/pw');
const fs = require('fs');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
function bridgeInit([mock, store]) {
  const data = JSON.parse(mock);
  const st = { calls: [], store: store || {}, installs: [] }; window.__st = st;
  window.confirm = () => true;
  const updates = [
    { pkg: 'com.bloatware.bingblop', name: 'ADB Application Manager Pro', installedVersion: '4.0-Pro', availableVersion: '4.1', source: 'self', downloadUrl: 'https://github.com/x.apk', page: 'https://github.com/r', notes: 'Bug fixes' },
    { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', installedVersion: '26.0.3.1', availableVersion: '27.1.0.103', source: 'galaxy', isSystem: true },
    { pkg: 'com.samsung.android.calendar', name: 'Calendar', installedVersion: '12.5.01.4', availableVersion: '12.6.00.11', source: 'galaxy', isSystem: true },
    { pkg: 'com.samsung.android.app.notes', name: 'Samsung Notes', installedVersion: '4.4.20', availableVersion: '4.4.26', source: 'galaxy', isSystem: true },
  ];
  window.AndroidBridge = {
    vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung' }); }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    loadPackages() { return JSON.stringify([{ pkg: 'com.facebook.katana', name: 'Facebook' }, { pkg: 'com.netflix.mediaclient', name: 'Netflix' }]); },
    getAppDetails() { return '{}'; },
    getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
    getUadStatus() { return JSON.stringify({ cached: true, count: 5381, updatedAt: Date.now(), stale: false }); }, updateUadList() {},
    getUadMatches() { return JSON.stringify(data); },
    executeAppAction(action, pkg) { st.calls.push(action + ':' + pkg);
      const p = data.packages.find(x => x.pkg === pkg);
      if (p) { if (action === 'uninstall') p.state = 'uninstalled'; if (action === 'reinstall') p.state = 'enabled'; }
      return 'Success'; },
    saveStore(k, v) { st.store[k] = v; }, loadStore(k) { return st.store[k] || ''; },
    openUrl(u) { st.calls.push('url:' + u); }, copyToClipboard(t) { st.copied = t; },
    getUpdateState() { return JSON.stringify({ running: false, checkedAt: 0, updates: [] }); },
    checkForUpdates() { st.calls.push('check');
      let n = 0; const t = setInterval(() => { n += 40; window.onUpdateCheckProgress(JSON.stringify({ done: Math.min(n, 120), total: 120 }));
        if (n >= 120) { clearInterval(t); window.onUpdatesChecked(JSON.stringify({ updates, checked: 120, errors: 1, lastError: 'timeout', selfStatus: 'ok', checkedAt: Date.now() })); } }, 300); },
    installUpdate(pkg) { st.installs.push(pkg);
      const steps = [['downloading', 30, '12 MB'], ['downloading', 100, '40 MB'], ['installing', 100, 'Installing...'],
        pkg.includes('notes') ? ['error', 0, 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'] : ['done', 100, 'Updated']];
      steps.forEach((s, i) => setTimeout(() => window.onUpdateProgress(JSON.stringify({ pkg, stage: s[0], percent: s[1], message: s[2] })), 300 * (i + 1))); },
  };
}
(async () => {
  const b = await chromium.launch();
  let page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(bridgeInit, [MOCK, null]);
  await page.goto(PAGE); await page.waitForTimeout(300);
  console.log('tabs:', (await page.locator('.tab-btn').allInnerTexts()).map(t => t.replace(/\s+/g, ' ')).join(' | '));

  // ---- 1. Remembered filters ----
  await page.click('#filterScroll .filter-pill[data-filter="system"]');
  await page.click('.tab-btn:has-text("Debloater")'); await page.waitForTimeout(200);
  await page.click('#uadRemovalRow [data-removal="Advanced"]'); await page.click('#uadListRow [data-list="Oem"]');
  await page.click('#uadStateRow [data-state="enabled"]'); await page.click('#uadBrandRow .filter-chip.device-brand');
  const store = await page.evaluate(() => window.__st.store);
  console.log('saved ui_state:', store.ui_state);

  // ---- 2. History: debloater uninstall, undo; app batch ----
  await page.evaluate(() => { const rows = uadVisiblePackages().slice(0, 2); rows.forEach(r => toggleUadSelect(r.pkg)); });
  await page.click('.uad-action-grid >> text=Uninstall'); await page.click('#batchConfirmExecuteBtn'); await page.waitForTimeout(300);
  await page.evaluate(() => closeCommandResultsModal());
  await page.click('text=/^History$/'); await page.waitForTimeout(100);
  console.log('history entries:', await page.locator('.hist-entry').count(), '|', await page.locator('.hist-title').first().innerText());
  await page.screenshot({ path: 'history.png' });
  await page.click('.hist-entry >> text=Undo'); await page.waitForTimeout(300);
  await page.evaluate(() => closeCommandResultsModal());
  console.log('after undo:', (await page.locator('.hist-title').allInnerTexts()).join(' / '));
  await page.click('text=Copy Log');
  console.log('copied log first lines:', (await page.evaluate(() => window.__st.copied)).split('\n').slice(0, 3).join(' | '));
  await page.evaluate(() => closeHistoryModal());
  const calls1 = await page.evaluate(() => window.__st.calls.slice());
  console.log('debloat calls:', calls1.join(', '));

  // ---- 3. Updates ----
  await page.click('.tab-btn[data-tab="installer"]'); await page.click('#instSwitchUpdater'); await page.waitForTimeout(100);
  await page.waitForFunction(() => /40\/120/.test(document.getElementById('updStatus').innerText), null, { timeout: 5000 });
  console.log('auto-check status:', await page.locator('#updStatus').innerText());
  await page.waitForFunction(() => /updates? available/.test(document.getElementById('updStatus').innerText), null, { timeout: 5000 });
  console.log('after check:', await page.locator('#updStatus').innerText());
  console.log('tab label:', (await page.locator('#updatesTabBtn').innerText()).replace(/\s+/g, ' '), '| update-all:', await page.locator('#updAllBtn').innerText());
  await page.screenshot({ path: 'updates.png' });
  await page.click('.upd-row:has-text("Calendar") >> text=Update');
  const rowHas = (name, re) => page.waitForFunction(([n, src]) => [...document.querySelectorAll('.upd-row')].some(r => r.innerText.includes(n) && new RegExp(src).test(r.innerText)), [name, re], { timeout: 5000 });
  await rowHas('Calendar', '30%');
  console.log('calendar mid-progress:', (await page.locator('.upd-row:has-text("Calendar")').innerText()).replace(/\s+/g, ' ').slice(0, 120));
  await rowHas('Calendar', 'Updated');
  console.log('calendar done:', (await page.locator('.upd-row:has-text("Calendar") button').innerText()), '| tab:', (await page.locator('#updatesTabBtn').innerText()).replace(/\s+/g, ' '));
  await page.click('#updAllBtn');
  await page.waitForFunction(() => /30%/.test(document.getElementById('updContainer').innerText), null, { timeout: 5000 });
  console.log('queue started:', (await page.locator('.upd-row button').allInnerTexts()).join(' | '));
  await page.waitForFunction(() => /Retry/.test(document.getElementById('updContainer').innerText), null, { timeout: 15000 });   // the last one (Notes) fails
  console.log('after update all:', (await page.locator('.upd-row button').allInnerTexts()).join(' | '));
  console.log('notes error msg:', await page.locator('.upd-row:has-text("Samsung Notes") .upd-msg').innerText());
  console.log('install order:', JSON.stringify(await page.evaluate(() => window.__st.installs)));
  // self-update is no longer in the general list (it has its own card); verify it's filtered out
  console.log('self filtered from general list:', !(await page.locator('#updContainer').innerText()).includes('ADB Application Manager Pro'));
  await page.screenshot({ path: 'updates_after.png' });

  // ---- relaunch: filters restored, history persisted ----
  const persisted = await page.evaluate(() => window.__st.store);
  const p2 = await b.newPage({ viewport: { width: 400, height: 860 } });
  p2.on('pageerror', e => errors.push('p2: ' + e.message));
  await p2.addInitScript(bridgeInit, [MOCK, persisted]);
  await p2.goto(PAGE); await p2.waitForTimeout(300);
  console.log('RELAUNCH apps filter:', await p2.locator('#filterScroll .filter-pill.active').innerText());
  await p2.click('.tab-btn:has-text("Debloater")'); await p2.waitForTimeout(200);
  console.log('RELAUNCH uad chips:', (await p2.locator('#uadRemovalRow .active, #uadListRow .active, #uadStateRow .active, #uadBrandRow .active').allInnerTexts()).join(', '));
  await p2.click('text=/^History$/'); await p2.waitForTimeout(100);
  console.log('RELAUNCH history entries:', await p2.locator('.hist-entry').count());
  console.log('calls (other):', (await page.evaluate(() => window.__st.calls)).filter(c => c.startsWith('url') || c === 'check').join(', '));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
