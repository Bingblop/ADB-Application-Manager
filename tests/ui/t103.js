// Batch menu: a taller sheet with a fourth row (Batch Ops, Share List, Command), and a close X at the top right like the app menu's.
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
      pickTextFile(tag) { setTimeout(() => window.onTextFilePicked(Object.assign({ tag }, window.__file)), 20); },
      shareTextFile(name, text, mime) { window.__share.push([name, text, mime]); return ''; },
      appActionBatch(action, pkgsJson) {
        const pk = JSON.parse(pkgsJson); window.__batch.push([action, pk]);
        setTimeout(() => window.onAppBatchDone(JSON.stringify({ ok: true, action, total: pk.length, done: pk.length, cancelled: false, rows: pk.map(p => ({ pkg: p, output: 'ran for ' + p, success: !(action.includes('failing') && p === 'com.example.charlie') })) })), 30);
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
  console.log('batch menu X (top right, no arrow):', await page.evaluate(() => { const x = document.querySelector('#floatingBatchBar [aria-label="Close the batch menu"]'); const sh = document.getElementById('floatingBatchBar').getBoundingClientRect(); const r = x.getBoundingClientRect(); return [x.innerText.trim(), r.right > sh.right - 40, r.top - sh.top < 50, document.querySelectorAll('.sheet-arrow-btn').length, getComputedStyle(x).fontSize]; }));
  console.log('sheet fits the screen:', await page.evaluate(() => { const r = document.getElementById('floatingBatchBar').getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight + 1; }));

  // Show Apps
  await page.locator('#floatingBatchBar button', { hasText: 'Show Applications' }).click(); await sleep(250);
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
  await page.locator('#bcSaved .preset-x[aria-label^="Delete"]').first().click(); await sleep(120);
  console.log('a saved command can be deleted:', await page.evaluate(() => [document.querySelectorAll('#bcSaved .preset-chip').length, JSON.stringify(kvGet('batch_cmds', null))]));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // Duplicate, dry run, Run again
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); ['com.example.alpha', 'com.example.charlie'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); window.__prompt = 'Wipe cache'; }); await sleep(300);
  await page.locator('#floatingBatchBar button', { hasText: 'Batch Ops' }).click(); await sleep(250);
  await page.locator('#bopsPresets .preset-chip', { hasText: /^Silence notifications/ }).locator('[title="Duplicate"]').click(); await sleep(100);
  await page.locator('#bopsPresets .preset-chip', { hasText: /^Silence notifications/ }).locator('[title="Duplicate"]').first().click(); await sleep(100);
  console.log('duplicating a built-in preset saves your own copy (and the next copy gets 2):', await page.evaluate(() => bopsSavedLoad().map(p => p.name)));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); expandBatchPanel(); }); await sleep(250);
  await page.locator('#floatingBatchBar button', { hasText: 'Command' }).click(); await sleep(250);
  await page.fill('#bcInput', 'echo $package'); await page.click('button[onclick="bcSave()"]'); await sleep(100);
    await page.locator('#bcSaved .preset-chip', { hasText: 'Wipe cache' }).locator('[title="Duplicate"]').click(); await sleep(100);
  console.log('duplicating a saved command:', await page.evaluate(() => JSON.stringify(bcSavedLoad())));
  await page.evaluate(() => { window.__batch.length = 0; });
  await page.fill('#bcInput', 'echo $package'); await page.click('#batchCmdModal .switch-title'); await page.evaluate(() => { if (!document.getElementById('bcDry').checked) { document.getElementById('bcDry').checked = true; bcPreview(); } }); await sleep(100);
  console.log('dry run changes the button:', await page.innerText('#bcRun'));
  await page.click('#bcRun'); await sleep(300);
  console.log('dry run lists what would run and runs nothing:', JSON.stringify(await page.evaluate(() => [document.getElementById('commandResultsTitle').innerText, Array.from(document.querySelectorAll('#commandResultsList .result-item')).map(r => r.innerText.replace(/\s+/g, ' ').trim()), window.__batch.length, Array.from(document.querySelectorAll('#commandResultsActions button')).map(b => b.innerText.trim())])));
  await page.locator('#commandResultsActions button', { hasText: 'Run it for real' }).click(); await sleep(300);
  console.log('Run it for real reopens the sheet with the command, dry run off:', await page.evaluate(() => [document.getElementById('batchCmdModal').classList.contains('show'), document.getElementById('bcInput').value, document.getElementById('bcDry').checked]));
  await page.fill('#bcInput', 'failing $package'); await page.click('#bcRun'); await sleep(500);
  console.log('results offer Run again and Run again on the failed:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#commandResultsActions button')).map(b => b.innerText.trim()))), '| ran:', JSON.stringify(await page.evaluate(() => window.__batch)));
  await page.evaluate(() => { window.__batch.length = 0; });
  await page.locator('#commandResultsActions button', { hasText: /that failed/ }).click(); await sleep(500);
  console.log('Run again on the failed sends only those:', JSON.stringify(await page.evaluate(() => window.__batch)));
  await page.evaluate(() => { window.__batch.length = 0; });
  await page.locator('#commandResultsActions button', { hasText: /^Run again$/ }).click(); await sleep(500);
  console.log('Run again repeats the run the dialog shows (here the retried one):', JSON.stringify(await page.evaluate(() => window.__batch)));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // Rename, export and import of presets and saved commands
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); ['com.example.alpha'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); }); await sleep(300);
  await page.locator('#floatingBatchBar button', { hasText: 'Batch Ops' }).click(); await sleep(250);
  console.log('saved presets before:', await page.evaluate(() => bopsSavedLoad().map(p => p.name)));
  window_prompt = async v => page.evaluate(v => { window.__prompt = v; }, v);
  await window_prompt('Quiet notifications');
  await page.locator('#bopsPresets .preset-chip', { hasText: 'Silence notifications (copy 2)' }).locator('[title="Rename"]').click(); await sleep(120);
  console.log('Rename a saved preset:', await page.evaluate(() => bopsSavedLoad().map(p => p.name)));
  await window_prompt('My preset');
  await page.locator('#bopsPresets .preset-chip', { hasText: 'Quiet notifications' }).locator('[title="Rename"]').click(); await sleep(120);
  console.log('a taken name is refused:', await page.evaluate(() => [bopsSavedLoad().map(p => p.name), document.getElementById('toastMsg').innerText]));
  console.log('built-in presets have no Rename:', await page.evaluate(() => Array.from(document.querySelectorAll('#bopsPresets .preset-chip')).filter(c => /^(Privacy lockdown|No location)/.test(c.innerText.trim())).map(c => c.querySelectorAll('[title="Rename"]').length)));
  await page.evaluate(() => { window.__share.length = 0; });
  await page.click('button[onclick="presetsExport(\'bops\')"]'); await sleep(150);
  const ex = await page.evaluate(() => window.__share.slice(-1)[0]);
  const exj = JSON.parse(ex[1]);
  console.log('Export presets shares a JSON file:', JSON.stringify([ex[0].replace(/\d{4}-\d\d-\d\d/, 'DATE'), ex[2], exj.app, exj.format, exj.bopsPresets.map(p => p.name), 'batchCommands' in exj]));
  // import: the same file again (nothing new), then a file with a clash, a new one, a command and junk
  await page.evaluate(t => { window.__file = { name: 'p.json', text: t }; }, ex[1]);
  await page.click('button[onclick="presetsImport(\'bops\')"]'); await sleep(200);
  console.log('importing the same file again adds nothing:', await page.evaluate(() => [document.getElementById('toastMsg').innerText, bopsSavedLoad().length]));
  const file = JSON.stringify({ app: 'adb-app-manager', format: 1,
    bopsPresets: [{ name: 'My preset', ops: { CAMERA: 'deny' } }, { name: 'Fresh', ops: { RECORD_AUDIO: 'ignore', bad_name: 'deny', CAMERA: 'bogus' } }, { name: 'Empty', ops: { x: 'allow' } }, 5],
    batchCommands: [{ name: 'Imported cmd', cmd: 'am force-stop $package' }, { name: '', cmd: 'x' }] });
  await page.evaluate(t => { window.__file = { name: 'p.json', text: t }; }, file);
  await page.click('button[onclick="presetsImport(\'bops\')"]'); await sleep(200);
  console.log('Import brings in the good ones (a clash is kept as "(imported)", junk skipped):', JSON.stringify(await page.evaluate(() => [document.getElementById('toastMsg').innerText, bopsSavedLoad().map(p => p.name), bopsSavedLoad().find(p => p.name === 'Fresh').ops, bcSavedLoad().map(c => c.name)])));
  await page.evaluate(() => { window.__file = { name: 'x.json', text: '{"hello":1}' }; });
  await page.click('button[onclick="presetsImport(\'bops\')"]'); await sleep(200);
  console.log('a file that is not ours is refused:', await page.evaluate(() => document.getElementById('toastMsg').innerText));
  await page.evaluate(() => { window.__file = { error: 'the file is over 1 MB' }; });
  await page.click('button[onclick="presetsImport(\'bops\')"]'); await sleep(200);
  console.log('a read error is shown:', await page.evaluate(() => document.getElementById('toastMsg').innerText));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); expandBatchPanel(); }); await sleep(250);
  await page.locator('#floatingBatchBar button', { hasText: 'Command' }).click(); await sleep(250);
  await window_prompt('Stop it');
  await page.locator('#bcSaved .preset-chip', { hasText: 'Imported cmd' }).locator('[title="Rename"]').click(); await sleep(120);
  console.log('Rename a saved command:', await page.evaluate(() => bcSavedLoad().map(c => c.name)));
  await page.evaluate(() => { window.__share.length = 0; });
  await page.click('button[onclick="presetsExport(\'cmds\')"]'); await sleep(150);
  const ec = await page.evaluate(() => window.__share.slice(-1)[0]);
  console.log('Export saved commands:', JSON.stringify([ec[0].replace(/\d{4}-\d\d-\d\d/, 'DATE'), JSON.parse(ec[1]).batchCommands.map(c => c.name + '=' + c.cmd)]));
  await page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // The app menu's arrow
  await page.evaluate(() => { collapseBatchPanel(); openInspector('com.example.alpha'); }); await sleep(300);
  console.log('app menu has no arrow, its X closes it:', await page.evaluate(() => document.querySelectorAll('#inspectorModal .sheet-arrow-btn').length));
  await page.locator('#inspectorModal .sheet-header-top > div', { hasText: '✕' }).click(); await sleep(200);
  console.log('closed:', await page.evaluate(() => !document.getElementById('inspectorModal').classList.contains('show')));
  await page.evaluate(() => { ['com.example.alpha'].forEach(p => { if (!selectedPkgs.has(p)) toggleSelectPkg(p); }); expandBatchPanel(); }); await sleep(400);
  await page.click('#floatingBatchBar [aria-label="Close the batch menu"]'); await sleep(300);
  console.log('the batch X minimizes the menu (selection kept):', await page.evaluate(() => [!document.getElementById('floatingBatchBar').classList.contains('show'), selectedPkgs.size]));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
