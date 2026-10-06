// Batch menu: a taller sheet with a fourth row (Batch Ops, Show Apps, Command), a big outlined arrow in the middle of the top of the batch and app menus.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  const apps = ['alpha', 'bravo', 'charlie'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isFrozen: false, isSuspended: false, isUninstalled: false }));
  await page.addInitScript(a => {
    window.__batch = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      appActionBatch(action, pkgsJson) {
        const pk = JSON.parse(pkgsJson); window.__batch.push([action, pk]);
        setTimeout(() => window.onAppBatchDone(JSON.stringify({ ok: true, action, total: pk.length, done: pk.length, cancelled: false, rows: pk.map(p => ({ pkg: p, output: 'ran for ' + p, success: true })) })), 30);
        return 'started';
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  await page.evaluate(() => { apps = null; });
  await page.evaluate(() => { ['com.example.alpha', 'com.example.bravo', 'com.example.charlie'].forEach(p => toggleSelectPkg(p)); expandBatchPanel(); }); await sleep(400);
  const grid = await page.evaluate(() => { const g = document.querySelectorAll('#floatingBatchBar .batch-actions-grid .batch-grid-btn'); const tops = new Set(Array.from(g).map(x => Math.round(x.getBoundingClientRect().top))); return { buttons: g.length, rows: tops.size, labels: Array.from(g).slice(9).map(x => x.innerText.trim()) }; });
  console.log('batch grid: buttons / rows / the new row:', JSON.stringify(grid));
  const arrow = async sel => page.evaluate(sel => { const e = document.querySelector(sel); const r = e.getBoundingClientRect(); const sheet = e.closest('.batch-bottom-sheet, .modal-sheet').getBoundingClientRect(); const cs = getComputedStyle(e); return { w: Math.round(r.width), h: Math.round(r.height), centered: Math.abs((r.left + r.width / 2) - (sheet.left + sheet.width / 2)) < 2, border: cs.borderTopWidth + ' ' + cs.borderTopStyle, svg: !!e.querySelector('svg') }; }, sel);
  console.log('batch arrow:', JSON.stringify(await arrow('#floatingBatchBar .sheet-arrow-btn')));
  console.log('sheet fits the screen:', await page.evaluate(() => { const r = document.getElementById('floatingBatchBar').getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight + 1; }));

  // Show Apps
  await page.locator('#floatingBatchBar button', { hasText: 'Show Apps' }).click(); await sleep(250);
  console.log('Show Apps lists the selection:', await page.evaluate(() => [document.getElementById('saSub').innerText, Array.from(document.querySelectorAll('#saList .sa-name')).map(e => e.innerText)]));
  await page.locator('#saList .sa-row', { hasText: 'Bravo' }).locator('button').click(); await sleep(150);
  console.log('Remove takes one out:', await page.evaluate(() => [Array.from(selectedPkgs), document.getElementById('batchCountText').innerText, document.getElementById('saList').querySelectorAll('.sa-row').length, document.getElementById('card_com.example.bravo').classList.contains('selected')]));
  await page.evaluate(() => closeShowApps()); await sleep(150);

  // Batch Ops
  await page.locator('#floatingBatchBar button', { hasText: 'Batch Ops' }).click(); await sleep(250);
  console.log('Batch Ops opens with the list:', await page.evaluate(() => [document.getElementById('bopsSub').innerText, document.querySelectorAll('#bopsList .bops-row').length > 80, document.getElementById('bopsApply').disabled]));
  await page.fill('#bopsSearch', 'camera'); await sleep(100);
  await page.locator('#bopsList .bops-row[data-op="CAMERA"] .app-checkbox').click(); await sleep(80);
  await page.selectOption('#bopsList .bops-row[data-op="CAMERA"] select', 'deny'); await sleep(80);
  await page.fill('#bopsSearch', 'RUN_ANY'); await sleep(100);
  await page.selectOption('#bopsList .bops-row[data-op="RUN_ANY_IN_BACKGROUND"] select', 'ignore'); await sleep(80);
  console.log('two ticked (choosing a value ticks it):', await page.evaluate(() => [document.getElementById('bopsSummary').innerText, document.getElementById('bopsApply').innerText]));
  await page.click('#bopsApply'); await sleep(400);
  console.log('Apply sends one batch with every op and value:', JSON.stringify(await page.evaluate(() => window.__batch)), '| asked:', JSON.stringify(dialogs.slice(-1)).slice(0, 120));
  await page.evaluate(() => { window.__batch.length = 0; document.getElementById('commandResultsModal') && 0; });
  console.log('the result dialog is titled for app ops:', await page.evaluate(() => /App Ops/.test(document.body.innerText)));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); ['com.example.alpha', 'com.example.charlie'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); }); await sleep(400);

  // Command
  await page.locator('#floatingBatchBar button', { hasText: 'Command' }).click(); await sleep(250);
  console.log('Command instructions:', await page.evaluate(() => { const t = document.querySelector('#batchCmdModal .batch-help').innerText; return [/\$package/.test(t), /once for every selected app/.test(t), /no undo/.test(t)]; }));
  await page.fill('#bcInput', 'pm clear $package'); await sleep(100);
  console.log('preview for the first selected app:', await page.evaluate(() => document.getElementById('bcPreview').innerText.replace(/\s+/g, ' ')), '|', await page.evaluate(() => document.getElementById('bcRun').innerText));
  await page.click('#bcRun'); await sleep(400);
  console.log('Run sends custom:<command> to the batch runner:', JSON.stringify(await page.evaluate(() => window.__batch)));
  console.log('the recent command is kept:', await page.evaluate(() => JSON.stringify(kvGet('batch_cmd_recent', null))));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // The app menu's arrow
  await page.evaluate(() => { collapseBatchPanel(); openInspector('com.example.alpha'); }); await sleep(300);
  console.log('app menu arrow:', JSON.stringify(await arrow('#inspectorModal .sheet-arrow-btn')));
  await page.click('#inspectorModal .sheet-arrow-btn'); await sleep(200);
  console.log('it closes the menu:', await page.evaluate(() => !document.getElementById('inspectorModal').classList.contains('show')));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
