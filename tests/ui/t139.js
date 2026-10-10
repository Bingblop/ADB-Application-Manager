// v7.10.16: SD Maid > Clear Data from Uninstalled Apps; the batch menu (handle that pulls up and down, outlined Select All / Clear All, Keep selecting on every time, Show Applications / Share List swapped);
// package names in their own colour; leaving the Apps tab clears the selection at once; Running turns Uninstalled off; one VirusTotal key in Settings with links from the places that use it.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  const apps = [
    { pkg: 'com.example.alpha', name: 'Alpha', isFrozen: false, isSuspended: false, isUninstalled: false, isRunning: true },
    { pkg: 'com.example.bravo', name: 'Bravo', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.gone1', name: 'Gone One', isFrozen: false, isSuspended: false, isUninstalled: true },
    { pkg: 'com.example.gone2', name: 'Gone Two', isFrozen: false, isSuspended: false, isUninstalled: true },
  ];
  await page.addInitScript(a => {
    window.__batch = []; window.__root = true; window.__vtOk = true; window.__vtCalls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      leftoverData(json) { return window.__root ? JSON.stringify({ root: true, found: { 'com.example.gone1': 5120 } }) : JSON.stringify({ root: false }); },
      appActionBatch(action, pkgsJson) { window.__batch.push([action, JSON.parse(pkgsJson)]); return 'started'; },
      morphe(tag, op, args) { window.__vtCalls.push([op, JSON.parse(args)]); setTimeout(() => window.onMorphe(op === 'vtValidate' ? (window.__vtOk ? { tag, ok: true } : { tag, ok: false, error: 'Wrong API key' }) : { tag, ok: true, data: {} }), 20); },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { isPrivilegedActive = true; });

  // ---- 1. SD Maid: Clear Data from Uninstalled Apps ----
  const card = await ev(() => { const u = document.getElementById('uninstDataCard'), t = document.getElementById('trimCachesCard'); return { before: !!u && !!t && (u.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0, parent: u && t && u.parentElement === t.parentElement, btn: document.getElementById('uninstDataBtn').innerText.trim(), text: u.innerText, code: !!u.querySelector('code') }; });
  check('1. the button is in the SD Maid tab, in a card above the Trim Caches one', card.before && card.parent && /Clear Data from Uninstalled Apps/.test(card.btn), JSON.stringify([card.before, card.parent, card.btn]));
  check('   the description under it is the corrected sentence with markup (flag in code, 3rd party and fix in bold)', /Clears the data of apps that were uninstalled from this device while the DONT_DELETE_FLAG flag was active\. This usually applies to third-party apps, and it can fix common problems when you install them again\./.test(card.text) && card.code, card.text.slice(0, 260));
  await ev(() => switchView('sdm')); await sleep(200);
  await ev(() => uninstDataStart()); await sleep(100);
  const m1 = await ev(() => ({ open: document.getElementById('uninstDataModal').classList.contains('show'), rows: document.querySelectorAll('#uninstDataList .confirm-list-row').length, sub: document.getElementById('uninstDataSub').innerText, size: document.getElementById('uninstDataList').innerText, go: document.getElementById('uninstDataGo').innerText }));
  check('   Root: only the uninstalled app that still has data is listed, with its size', m1.open && m1.rows === 1 && /Gone One/.test(m1.size) && /5(\.0)? ?(KB|MB)/.test(m1.size) && /with data left/.test(m1.sub), JSON.stringify(m1));
  await ev(() => uninstDataClose());
  await ev(() => { window.__root = false; uninstDataStart(); }); await sleep(100);
  const m2 = await ev(() => ({ rows: document.querySelectorAll('#uninstDataList .confirm-list-row').length, note: document.getElementById('uninstDataNote').innerText, go: document.getElementById('uninstDataGo').innerText }));
  check('   without Root every uninstalled app is listed, ticked, and the note says Root is needed to see the data', m2.rows === 2 && /Without Root/.test(m2.note) && /Clear data of 2 apps/.test(m2.go), JSON.stringify(m2));
  await ev(() => uninstDataAll(false));
  check('   Select None leaves nothing to clear (the button is off)', await ev(() => document.getElementById('uninstDataGo').disabled === true));
  await ev(() => { uninstDataAll(true); }); await page.click('#uninstDataGo'); await sleep(200);
  check('   confirming runs the batch action clear_removed_data on the ticked apps, after asking', await ev(() => window.__batch.length === 1 && window.__batch[0][0] === 'clear_removed_data' && window.__batch[0][1].length === 2) && dialogs.some(d => /cannot be undone/.test(d)), JSON.stringify([await ev(() => window.__batch), dialogs]));
  await ev(() => { document.getElementById('batchConfirmModal').classList.remove('show'); batchRunActive = false; });

  // ---- 2-5. the batch menu ----
  await ev(() => { switchView('apps'); }); await sleep(300);
  await ev(() => { ['com.example.alpha', 'com.example.bravo'].forEach(p => toggleSelectPkg(p)); expandBatchPanel(); }); await sleep(400);
  const bm = await ev(() => {
    const bar = document.getElementById('floatingBatchBar'), cs = getComputedStyle(bar);
    const tools = Array.from(bar.querySelectorAll('.batch-sheet-tools .batch-tool-link')).map(e => ({ t: e.innerText.trim(), bw: getComputedStyle(e).borderTopWidth, bs: getComputedStyle(e).borderTopStyle }));
    return { show: bar.classList.contains('show'), handle: /linear-gradient/.test(cs.backgroundImage), pos: cs.backgroundPosition, tools,
      copy: Array.from(bar.querySelectorAll('.copy-row button')).map(e => e.innerText.trim()), grid: Array.from(bar.querySelectorAll('.batch-actions-grid button')).map(e => e.innerText.trim()),
      keep: document.getElementById('batchKeepSelectionToggle').checked };
  });
  check('2. the batch menu has the grab handle in the middle at the top, like the other sheets', bm.show && bm.handle && /50%|center/.test(bm.pos), bm.pos);
  check('3. Select All and Clear All are outlined buttons ("Clear" is now "Clear All")', bm.tools.length >= 2 && bm.tools[0].t === 'Select All' && bm.tools[1].t === 'Clear All' && bm.tools.slice(0, 2).every(x => x.bw === '1px' && x.bs === 'solid'), JSON.stringify(bm.tools));
  check('4. Keep selecting after running is on', bm.keep === true);
  check('5. Share List and Show Apps changed places; it is Show Applications now', bm.copy.join('|') === 'Copy Packages|Show Applications' && bm.grid.includes('Share List') && !bm.grid.includes('Show Apps') && bm.grid.indexOf('Share List') === bm.grid.length - 2, JSON.stringify([bm.copy, bm.grid]));
  // the gestures: drag the handle up, then down, then down again (mouse events do the same as touch)
  const box = await ev(() => { const r = document.getElementById('floatingBatchBar').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + 6 }; });
  const drag = async (dy) => { await page.mouse.move(box.x, box.y); await page.mouse.down(); await page.mouse.move(box.x, box.y + dy / 2, { steps: 4 }); await page.mouse.move(box.x, box.y + dy, { steps: 4 }); await page.mouse.up(); await sleep(350); };
  await drag(-140);
  check('   pulled up, the menu gets taller (like the other sheets)', await ev(() => document.getElementById('floatingBatchBar').classList.contains('sheet-expanded')));
  const box2 = await ev(() => { const r = document.getElementById('floatingBatchBar').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + 6 }; }); box.y = box2.y;
  await drag(140);
  check('   pulled down once, it goes back to its size', await ev(() => !document.getElementById('floatingBatchBar').classList.contains('sheet-expanded') && document.getElementById('floatingBatchBar').classList.contains('show')));
  const box3 = await ev(() => { const r = document.getElementById('floatingBatchBar').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + 6 }; }); box.y = box3.y;
  await drag(140);
  check('   pulled down again, the menu closes to the checkmark button and the apps stay selected', await ev(() => !document.getElementById('floatingBatchBar').classList.contains('show') && document.getElementById('batchFab').classList.contains('show') && selectedPkgs.size === 2));
  // keep selecting: turn it off, drop the selection, select again: on again
  await ev(() => { expandBatchPanel(); });
  await page.click('#batchKeepSelectionToggle + .switch-track'); await sleep(50);
  check('   turned off for this selection', await ev(() => batchKeepSelection === false && document.getElementById('batchKeepSelectionToggle').checked === false));
  await ev(() => { clearBatchSelection(); toggleSelectPkg('com.example.alpha'); expandBatchPanel(); }); await sleep(100);
  check('4. a new selection starts with Keep selecting after running on again', await ev(() => batchKeepSelection === true && document.getElementById('batchKeepSelectionToggle').checked === true));
  check('   it is not remembered in the saved state', await ev(() => !JSON.stringify(loadStoreJson('ui_state', {}) || {}).includes('batchKeepSelection')));

  // ---- 6. package names ----
  const col = await ev(() => { const n = document.querySelector('.app-card .app-name'), p = document.querySelector('.app-card .app-pkg'); return { name: getComputedStyle(n).color, pkg: getComputedStyle(p).color, muted: getComputedStyle(document.documentElement).getPropertyValue('--text-muted').trim() }; });
  check('6. the package name has its own colour, not the name\'s white and not the plain grey', col.pkg !== col.name && col.pkg !== 'rgb(142, 155, 174)', JSON.stringify(col));
  await page.screenshot({ path: 'apps_pkg_colour.png' });

  // ---- 7. leaving the Apps tab drops the selection at once ----
  await ev(() => { collapseBatchPanel(); });
  const left = await ev(() => { const n0 = selectedPkgs.size; switchView('settings'); return { n0, n: selectedPkgs.size, fab: document.getElementById('batchFab').classList.contains('show'), bar: document.getElementById('floatingBatchBar').classList.contains('show'), tr: getComputedStyle(document.getElementById('batchFab')).transitionDuration }; });
  check('7. switching to another tab with apps selected clears them and removes the checkmark button in the same moment', left.n0 > 0 && left.n === 0 && !left.fab && !left.bar && /^0s$/.test(left.tr), JSON.stringify(left));
  await ev(() => { switchView('apps'); }); await sleep(200);
  check('   back in the Apps tab nothing is selected', await ev(() => selectedPkgs.size === 0 && !document.querySelector('.app-card.selected')));

  // ---- 8. Running turns Uninstalled off ----
  const fl = await ev(() => { activeFilters.clear(); setFilter('uninstalled'); const a = Array.from(activeFilters); setFilter('running'); return { a, b: Array.from(activeFilters) }; });
  check('8. with Uninstalled on, tapping Running turns Uninstalled off', fl.a.join() === 'uninstalled' && fl.b.join() === 'running', JSON.stringify(fl));

  // ---- 9. one VirusTotal key in Settings ----
  await ev(() => { vtKeyCache = null; kvSet('vt_key', ''); kvSet('vt_key_ok', ''); switchView('prefs'); }); await sleep(200);
  check('9. Settings has the VirusTotal API key card', await ev(() => !!document.getElementById('vtCard') && document.getElementById('vtKeyInput').type === 'password'));
  await ev(() => { vtRefreshUi(); });
  const inst0 = await ev(() => ({ note: document.getElementById('vtKeyNote').innerText, link: !!document.querySelector('#vtKeyNote button'), noBox: !document.getElementById('vtApiKey'), scanOff: document.getElementById('vtScanBtn').disabled }));
  check('   the Installer no longer has its own key box; it says the key is missing and links to Settings', inst0.noBox && inst0.link && /No VirusTotal API key yet/.test(inst0.note) && inst0.scanOff, JSON.stringify(inst0));
  await ev(() => { mp.cfg.hVt = true; });
  await ev(() => { document.getElementById('vtKeyInput').value = 'KEY123'; document.getElementById('vtKeyInput').dispatchEvent(new Event('input', { bubbles: true })); }); await sleep(50);
  check('   typing the key keeps it, not approved yet', await ev(() => vtKey() === 'KEY123' && !vtKeyApproved() && /not been approved/.test(document.getElementById('vtKeyStatus').innerText)));
  const inst1 = await ev(() => ({ note: document.getElementById('vtKeyNote').innerText, btn: (document.querySelector('#vtKeyNote button') || {}).innerText }));
  check('   the Installer then says it is not approved and links to Settings to test it', /not been approved/.test(inst1.note) && /Test it in Settings/.test(inst1.btn), JSON.stringify(inst1));
  await ev(() => { window.__vtOk = false; vtTestKey(); }); await sleep(150);
  check('   a refused key stays unapproved', await ev(() => !vtKeyApproved() && /Wrong API key/.test(document.getElementById('toastMsg').innerText)));
  await ev(() => { window.__vtOk = true; vtTestKey(); }); await sleep(150);
  check('   Test key approves it (one validation call with the key)', await ev(() => vtKeyApproved() && /approved/.test(document.getElementById('vtKeyStatus').innerText) && window.__vtCalls.filter(c => c[0] === 'vtValidate').length === 2 && window.__vtCalls[0][1].key === 'KEY123'));
  check('   the Installer now says it uses the key from Settings', await ev(() => /Using the VirusTotal API key from Settings/.test(document.getElementById('vtKeyNote').innerText) && !document.querySelector('#vtKeyNote button') && !document.getElementById('vtScanBtn').disabled));
  check('   Morphe Helper uses the same key (no box of its own)', await ev(() => vtKey() === 'KEY123' && typeof mpHKeyTest === 'undefined'));
  await ev(() => { vtKeyTyped('OTHER'); });
  check('   changing the key takes the approval away', await ev(() => !vtKeyApproved() && vtKey() === 'OTHER'));
  await ev(() => { vtOpenSettings(); }); await sleep(200);
  check('   the links go to Settings', await ev(() => currentViewName() === 'prefs'));
  // an older saved key (the Installer\'s own) is taken over
  await ev(() => { vtKeyCache = null; kvSet('vt_key', ''); window.AndroidBridge.loadSetting = k => k === 'vt_api_key' ? 'OLDKEY' : ''; });
  check('   a key saved by the older Installer box is taken over', await ev(() => vtKey() === 'OLDKEY'));
  // ... and the older copy is cleared once it is the one key; a Helper config that still carries a key is emptied
  await ev(() => {
    window.__st = { vt_api_key: 'OLDKEY', morphe_cfg: JSON.stringify({ hVtKey: 'HKEY', hDefault: 'apkmirror' }) };
    window.AndroidBridge.loadSetting = k => window.__st[k] || '';
    window.AndroidBridge.saveSetting = (k, v) => { window.__st[k] = v; return true; };
    vtKeyCache = null; kvSet('vt_key', '');
  });
  check('   the older key is cleared after it is taken over', await ev(() => vtKey() === 'OLDKEY' && window.__st.vt_api_key === ''));
  await ev(() => { vtKeyCache = null; kvSet('vt_key', ''); mpCfgLoad(); });
  check('   Helper\'s own key is moved to the one key and the plain config is emptied', await ev(() => vtKey() === 'HKEY' && mp.cfg.hVtKey === '' && JSON.parse(window.__st.morphe_cfg).hVtKey === '' && JSON.parse(window.__st.morphe_cfg).hDefault === 'apkmirror'));
  await ev(() => { window.__st.morphe_cfg = JSON.stringify({ hVtKey: 'OTHERKEY' }); window.AndroidBridge.saveSetting = (k, v) => { if (k === 'vt_key' && v !== '""') return false; window.__st[k] = v; return true; }; vtKeyCache = null; kvSet('vt_key', ''); mpCfgLoad(); });
  check('   if the key cannot be stored securely the Helper copy is kept (nothing is lost) and nothing is stored under vt_key', await ev(() => mp.cfg.hVtKey === 'OTHERKEY' && (window.__st.vt_key || '""') === '""'));

  // the Keystore refuses to seal the approval: the page says so instead of "The key works", and the key stays unapproved
  await ev(() => { window.__vtOk = true; window.AndroidBridge.saveSetting = (k, v) => { if (k === 'vt_key_ok' && v !== '""') return false; window.__st[k] = v; return true; }; vtKeyCache = 'KEYZ'; vtTestKey(); }); await sleep(200);
  check('   if the approval cannot be stored securely the toast says so and the key stays unapproved', await ev(() => /approval could not be stored securely/.test(document.getElementById('toastMsg').innerText) && !vtKeyApproved()));

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
