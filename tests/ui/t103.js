// Batch menu: a taller sheet with a fourth row (Batch Ops, Show Apps, Command), a big outlined arrow in the middle of the top of the batch and app menus.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  const apps = ['alpha', 'bravo', 'charlie'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isFrozen: false, isSuspended: false, isUninstalled: false }));
  await page.addInitScript(a => {
    window.__batch = []; window.__share = []; window.__prompt = 'My preset';
    window.prompt = () => window.__prompt;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      shareTextFile(name, text, mime) { window.__share.push([name, text, mime]); return ''; },
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
  console.log('presets offered:', await page.evaluate(() => Array.from(document.querySelectorAll('#bopsPresets .filter-chip')).map(c => c.innerText.trim())));
  await page.evaluate(() => { document.getElementById('bopsSearch').value = ''; });
  await page.locator('#bopsPresets .filter-chip', { hasText: /^Privacy lockdown$/ }).click(); await sleep(150);
  console.log('Privacy lockdown ticks its ops (replacing the earlier ticks):', await page.evaluate(() => [document.getElementById('bopsSummary').innerText, bopsPick.get('CAMERA'), bopsPick.has('RUN_ANY_IN_BACKGROUND')]));
  await page.evaluate(() => { bopsPick.set('RUN_ANY_IN_BACKGROUND', 'deny'); bopsRender(); });
  await page.locator('#bopsPresets .filter-chip', { hasText: 'Save ticked' }).click(); await sleep(150);
  console.log('a saved preset appears and is kept:', await page.evaluate(() => [Array.from(document.querySelectorAll('#bopsPresets .preset-chip')).map(c => c.innerText.replace(/\s*✕/, '').trim()), JSON.stringify(kvGet('bops_presets', null)).slice(0, 90)]));
  await page.evaluate(() => { bopsPick.clear(); bopsRender(); });
  await page.locator('#bopsPresets .preset-chip span').first().click(); await sleep(120);
  console.log('loading it brings the ops back (with RUN_ANY_IN_BACKGROUND deny):', await page.evaluate(() => [bopsPick.size, bopsPick.get('RUN_ANY_IN_BACKGROUND')]));
  await page.evaluate(() => { bopsPick.clear(); bopsPick.set('CAMERA', 'deny'); bopsPick.set('RUN_ANY_IN_BACKGROUND', 'ignore'); bopsRender(); });
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
  console.log('the results dialog offers Share CSV:', await page.evaluate(() => Array.from(document.querySelectorAll('#commandResultsActions button')).map(b => b.innerText.trim())));
  await page.locator('#commandResultsActions button', { hasText: 'Share CSV' }).click(); await sleep(150);
  const csv = await page.evaluate(() => window.__share.slice(-1)[0]);
  console.log('CSV shared:', JSON.stringify([csv[0].replace(/\d{4}-\d\d-\d\d/, 'DATE'), csv[2], csv[1].split('\n')[0], csv[1].split('\n').length - 1, /Alpha,com.example.alpha,OK,ran for com.example.alpha,.*,pm clear \$package/.test(csv[1])]));
  console.log('the recent command is kept:', await page.evaluate(() => JSON.stringify(kvGet('batch_cmd_recent', null))));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // Named saved commands and a saved list as the target
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); ['com.example.alpha', 'com.example.charlie'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); customLists = [{ id: 'l1', name: 'My list', packages: ['com.example.bravo', 'com.example.gone.app'] }]; window.__prompt = 'Wipe cache'; }); await sleep(300);
  await page.locator('#floatingBatchBar button', { hasText: 'Command' }).click(); await sleep(250);
  await page.fill('#bcInput', 'pm trim-caches 1G; echo $package'); await page.click('button[onclick="bcSave()"]'); await sleep(150);
  console.log('a saved command is listed by name:', await page.evaluate(() => [Array.from(document.querySelectorAll('#bcSaved .preset-chip')).map(c => c.innerText.replace(/\s*✕/, '').trim()), JSON.stringify(kvGet('batch_cmds', null))]));
  await page.fill('#bcInput', ''); await page.locator('#bcSaved .preset-chip span').first().click(); await sleep(100);
  console.log('tapping it loads the command:', await page.inputValue('#bcInput'));
  console.log('Run on offers the selection and the saved list:', await page.evaluate(() => Array.from(document.querySelectorAll('#bcTarget option')).map(o => o.innerText)));
  await page.selectOption('#bcTarget', 'l1'); await sleep(100);
  console.log('with the list: only its apps that are on the phone, and the others noted:', await page.evaluate(() => [document.getElementById('bcSub').innerText, document.getElementById('bcRun').innerText, document.getElementById('bcPreview').innerText.replace(/\s+/g, ' ')]));
  await page.evaluate(() => { window.__batch.length = 0; });
  await page.click('#bcRun'); await sleep(400);
  console.log('Run sends the list apps:', JSON.stringify(await page.evaluate(() => window.__batch)));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); ['com.example.alpha'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); }); await sleep(300);
  await page.locator('#floatingBatchBar button', { hasText: 'Command' }).click(); await sleep(250);
  await page.locator('#bcSaved .preset-x').first().click(); await sleep(120);
  console.log('a saved command can be deleted:', await page.evaluate(() => [document.querySelectorAll('#bcSaved .preset-chip').length, JSON.stringify(kvGet('batch_cmds', null))]));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // The app menu's arrow
  await page.evaluate(() => { collapseBatchPanel(); openInspector('com.example.alpha'); }); await sleep(300);
  console.log('app menu arrow:', JSON.stringify(await arrow('#inspectorModal .sheet-arrow-btn')));
  await page.click('#inspectorModal .sheet-arrow-btn'); await sleep(200);
  console.log('it closes the menu:', await page.evaluate(() => !document.getElementById('inspectorModal').classList.contains('show')));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
