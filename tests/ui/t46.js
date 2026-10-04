// v5.8: Android Back handling (sheets, selection, archive browser, tab history, press-twice-to-exit) + HTML-injection hardening of lists.
const { chromium, PAGE, fixture } = require('./lib/pw');
const fs = require('fs');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
  await page.addInitScript(mock => {
    const evil = '<img src=x onerror="window.__pwned=(window.__pwned||0)+1">Evil <b>bold</b>';
    const apps = { 'com.facebook.katana': { name: 'Facebook' }, 'com.netflix.mediaclient': { name: 'Netflix' }, 'com.evil.label': { name: evil }, 'com.quote.app': { name: 'Quote \' " <script>window.__pwned=9</script>' } };
    window.__calls = { fmList: [], open: [], list: [] };
    window.__fmEntries = [{ name: "it's \"quoted\".txt", isDir: false, isLink: false, size: 5, perms: '-rw-' }, { name: "x');window.__pwned=7;x", isDir: true, isLink: false, size: 0, perms: 'drwx' }, { name: '<b>html</b>.zip', isDir: false, isLink: false, size: 9, perms: '-rw-' }];
    const tree = { '': [{ n: 'res', p: 'res/', d: true, s: 10, c: 5, t: 0, m: -1, e: false, f: 1 }, { n: 'a.txt', p: 'a.txt', d: false, s: 3, c: 3, t: 0, m: 0, e: false }], 'res/': [{ n: 'layout', p: 'res/layout/', d: true, s: 4, c: 2, t: 0, m: -1, e: false, f: 1 }], 'res/layout/': [{ n: 'main.xml', p: 'res/layout/main.xml', d: false, s: 4, c: 2, t: 0, m: 8, e: false }] };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {}, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify(Object.entries(apps).map(([pkg, a]) => Object.assign({ pkg, isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false }, a))); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getUadStatus() { return JSON.stringify({ cached: true, count: 5381, updatedAt: Date.now(), stale: false, downloading: false }); },
      getUadMatches() { return mock; }, hasAllFilesAccess() { return true; }, loadSetting() { return ''; }, saveSetting() {},
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      fmList(path) { window.__calls.fmList.push(path); return JSON.stringify({ path, entries: window.__fmEntries }); },
      archiveOpen(path) { window.__calls.open.push(path); return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: 3, files: 2, size: 100, zip64: false, staged: false, apk: false, editable: true, whyNot: '' }); },
      archiveList(path, dir, q, off, lim) { const all = tree[dir] || []; return JSON.stringify({ ok: true, dir, query: q, total: all.length, offset: off, more: false, entries: all }); },
      archiveClose() {},
    };
  }, MOCK);
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const back = () => page.evaluate(() => handleAndroidBack());
  const view = () => page.evaluate(() => currentViewName());
  const toast = () => page.locator('#toastMsg').innerText();

  // 1) Nothing open at the first tab: first Back asks; a deliberate second press (not a double tap) leaves; late ones ask again.
  console.log('1. first Back at the home tab asks to press again:', (await back()) === true && /Press back again/.test(await toast()), JSON.stringify(await toast()));
  console.log('   a second Back right after (a double tap) does not leave:', (await back()) === true);
  await sleep(800);
  console.log('   a deliberate second Back lets the app exit:', (await back()) === false);
  await sleep(3700);
  console.log('   after the window passes it asks again:', (await back()) === true);
  await page.evaluate(() => { backExitArmedAt = 0; });

  // 2) Sheets close first, top-most first.
  await page.evaluate(() => openWorkingModesModal()); await sleep(150);
  const open1 = await page.evaluate(() => document.getElementById('modesModal').classList.contains('show'));
  const r2 = await back(); await sleep(150);
  console.log('2. Back closes an open sheet:', open1 && r2 === true && !(await page.evaluate(() => document.getElementById('modesModal').classList.contains('show'))));
  await page.evaluate(() => { openWorkingModesModal(); showCommandResults('T', 's', [{ name: 'x', pkg: 'y', output: 'z', success: true }]); }); await sleep(150);
  await back(); await sleep(150);
  const after2 = await page.evaluate(() => ({ results: document.getElementById('commandResultsModal').classList.contains('show'), modes: document.getElementById('modesModal').classList.contains('show') }));
  console.log('   with two sheets open only one (the top) closes:', after2.results !== after2.modes, JSON.stringify(after2));
  await back(); await sleep(150);
  console.log('   next Back closes the one below:', !(await page.evaluate(() => document.getElementById('modesModal').classList.contains('show') || document.getElementById('commandResultsModal').classList.contains('show'))));
  await page.evaluate(() => { backExitArmedAt = 0; });

  // 3) Selection: an open batch panel collapses, then the selection clears; neither leaves the app.
  await page.evaluate(() => { toggleSelectPkg('com.facebook.katana'); toggleSelectPkg('com.netflix.mediaclient'); expandBatchPanel(); }); await sleep(250);
  await back(); await sleep(250);
  const s3a = await page.evaluate(() => ({ panel: document.getElementById('floatingBatchBar').classList.contains('show'), sel: selectedPkgs.size }));
  await back(); await sleep(250);
  const s3b = await page.evaluate(() => selectedPkgs.size);
  console.log('3. Back collapses the panel first, then clears the selection:', !s3a.panel && s3a.sel === 2 && s3b === 0, JSON.stringify([s3a, s3b]));
  await page.evaluate(() => { backExitArmedAt = 0; });

  // 4) Tab history is retraced.
  await page.evaluate(() => { switchView('files'); switchView('logcat'); switchView('terminal'); }); await sleep(150);
  const trail = [];
  for (let i = 0; i < 3; i++) { const r = await back(); await sleep(60); trail.push(r + ':' + await view()); }
  console.log('4. Back retraces terminal -> logcat -> files -> apps:', JSON.stringify(trail) === JSON.stringify(['true:logcat', 'true:files', 'true:apps']), JSON.stringify(trail));
  const atHome = await back();
  console.log('   at the first tab it asks before exiting:', atHome === true && (await view()) === 'apps');
  await page.evaluate(() => { backExitArmedAt = 0; viewStack = []; });
  await page.evaluate(() => { switchView('files'); switchView('files'); switchView('apps'); switchView('files'); });
  console.log('   revisiting a tab does not stack duplicates:', await page.evaluate(() => JSON.stringify(viewStack)) === '["apps"]', await page.evaluate(() => JSON.stringify(viewStack)));
  await page.evaluate(() => { switchView('apps'); backExitArmedAt = 0; viewStack = []; });

  // 5) Other tab, empty history: Back goes to the Applications tab.
  await page.evaluate(() => { viewStack = []; switchView('store'); viewStack = []; });
  console.log('5. from a tab with no history Back returns to Applications:', (await back()) === true && (await view()) === 'apps');

  // 6) Debloater selection clears before leaving the tab.
  await page.evaluate(() => switchView('debloater')); await sleep(300);
  const pkg = await page.locator('.uad-row').first().getAttribute('data-pkg');
  await page.evaluate(p => toggleUadSelect(p), pkg); await sleep(60);
  await back(); await sleep(60);
  const d6 = await page.evaluate(() => ({ sel: uadSelected.size, view: currentViewName() }));
  console.log('6. debloater: Back clears the selection first (still on the tab):', d6.sel === 0 && d6.view === 'debloater', JSON.stringify(d6));
  await back(); await sleep(60);
  console.log('   then leaves the tab:', (await view()) === 'apps');
  await page.evaluate(() => { backExitArmedAt = 0; viewStack = []; });

  // 7) Archive browser: up folder by folder, then closes, then leaves the Files tab.
  await page.evaluate(() => switchView('files')); await sleep(200);
  await page.evaluate(() => arcOpen('/storage/emulated/0/Download/app.zip')); await sleep(250);
  await page.evaluate(() => arcGo('res/layout/')); await sleep(60);
  const t7 = [];
  for (let i = 0; i < 3; i++) { await back(); await sleep(60); t7.push(await page.evaluate(() => (arc ? arcDir : 'CLOSED'))); }
  console.log('7. archive: Back goes up two folders then closes the archive:', JSON.stringify(t7) === JSON.stringify(['res/', '', 'CLOSED']), JSON.stringify(t7));
  console.log('   file list is shown again; one more Back leaves Files:', await page.locator('#fmListCard').evaluate(e => getComputedStyle(e).display !== 'none'), (await back()) === true && (await view()) === 'apps');
  await page.evaluate(() => { backExitArmedAt = 0; viewStack = []; switchView('files'); }); await sleep(100);
  await page.evaluate(() => { arcOpen('/x/a.zip'); }); await sleep(250);
  await page.evaluate(() => { document.getElementById('arcSearch').value = 'main'; arcQuery = 'main'; });
  await back(); await sleep(60);
  console.log('   a search in the archive is cleared by Back (stays in the archive):', await page.evaluate(() => !!arc && arcQuery === '' && document.getElementById('arcSearch').value === ''));
  await page.evaluate(() => { arcClose(); switchView('apps'); backExitArmedAt = 0; viewStack = []; });

  // 8) HTML injection: labels / file names are text, never markup or script.
  await page.evaluate(() => switchView('apps')); await sleep(200);
  const names = await page.locator('.app-card .app-name').allInnerTexts();
  const imgs = await page.locator('.app-card img').count();
  const pwned1 = await page.evaluate(() => window.__pwned || 0);
  console.log('8. app label with HTML shows as plain text, no element injected:', imgs === 0 && names.some(n => n.includes('<img src=x onerror=') && n.includes('<b>bold</b>')) && pwned1 === 0, JSON.stringify(names.filter(n => /Evil|Quote/.test(n))));
  const tapOk = await page.evaluate(() => { toggleSelectPkg(document.querySelector('.app-card[data-pkg="com.evil.label"] .app-left').parentElement.dataset.pkg); return selectedPkgs.has('com.evil.label'); });
  await page.locator('.app-card[data-pkg="com.netflix.mediaclient"] .app-left').click(); await sleep(60);
  console.log('   rows still select by tap through data-pkg:', tapOk && (await page.evaluate(() => selectedPkgs.has('com.netflix.mediaclient'))));
  await page.evaluate(() => clearBatchSelection());
  await page.locator('.app-card[data-pkg="com.netflix.mediaclient"] button[title="Menu"]').click(); await sleep(200);
  console.log('   ⋯ opens that app\'s menu:', await page.evaluate(() => document.getElementById('inspectorModal').classList.contains('show')));
  await page.evaluate(() => closeInspector()); await sleep(100);

  await page.evaluate(() => switchView('files')); await sleep(250);
  const fm = await page.evaluate(() => ({ rows: [...document.querySelectorAll('#fmList .perm-row[data-i]')].map(r => r.querySelector('.perm-name').innerText), html: document.getElementById('fmList').innerHTML, pwned: window.__pwned || 0 }));
  console.log('   file names with quotes / markup render as text:', fm.rows.length === 3 && fm.rows.some(r => r.includes('<b>html</b>.zip')) && !/<b>html/.test(fm.html.replace(/&lt;b&gt;/g, '')) && fm.pwned === 0, JSON.stringify(fm.rows));
  // tapping the tricky folder navigates with the exact name, and the action sheet opens for the quoted file
  await page.locator('#fmList .perm-row', { hasText: "x');window" }).locator('.perm-info').click(); await sleep(200);
  const go = await page.evaluate(() => window.__calls.fmList.slice(-1)[0]);
  console.log('   tapping the oddly named folder opens exactly that path:', go === "/storage/emulated/0/x');window.__pwned=7;x", JSON.stringify(go));
  await page.evaluate(() => { fmGo('/storage/emulated/0'); }); await sleep(100);
  await page.locator('#fmList .perm-row', { hasText: "quoted" }).locator('.perm-toggle-btn').click(); await sleep(150);
  const sheet = await page.evaluate(() => ({ name: document.getElementById('fmActionName').innerText, path: document.getElementById('fmActionPath').innerText, open: document.getElementById('fmActionModal').classList.contains('show'), pwned: window.__pwned || 0 }));
  console.log('   the ⋯ sheet shows the exact file name, nothing executed:', sheet.open && sheet.name === 'it\'s "quoted".txt' && sheet.pwned === 0, JSON.stringify(sheet.name));
  await page.evaluate(() => closeFmAction());
  console.log('errors:', JSON.stringify(errors), '| dialogs:', dialogs.length);
  await b.close();
})();
