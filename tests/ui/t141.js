// v7.10.18: Select All / Clear All outlined on the Selected apps list and the Clear Data from Uninstalled Apps sheet (New List in Saved Applications too);
// a row in the Uninstalled filter offers to clear what that app left behind; the VirusTotal card says how many lookups are left today.
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
    { pkg: 'com.example.alpha', name: 'Alpha', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.bravo', name: 'Bravo', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.gone1', name: 'Gone One', isFrozen: false, isSuspended: false, isUninstalled: true },
    { pkg: 'com.example.gone2', name: 'Gone Two', isFrozen: false, isSuspended: false, isUninstalled: true },
  ];
  await page.addInitScript(a => {
    window.__batch = []; window.__root = true; window.__vt = []; window.__quota = { source: 'virustotal', dayAllowed: 500, dayUsed: 123, dayLeft: 377, resetDayInMs: 5 * 3600000 + 12 * 60000 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      leftoverData(json) { return window.__root ? JSON.stringify({ root: true, found: { 'com.example.gone1': 5120 } }) : JSON.stringify({ root: false }); },
      appActionBatch(action, pkgsJson) { window.__batch.push([action, JSON.parse(pkgsJson)]); return 'started'; },
      morphe(tag, op, args) {
        window.__vt.push([op, JSON.parse(args)]);
        const r = op === 'vtValidate' ? { tag, ok: true } : op === 'vtQuotaLive' ? (window.__quotaErr ? { tag, ok: false, error: window.__quotaErr } : { tag, ok: true, data: window.__quota }) : { tag, ok: true, data: {} };
        setTimeout(() => window.onMorphe(r), 20);
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { isPrivilegedActive = true; });
  const outlined = sel => ev(s => { const e = document.querySelector(s); if (!e) return null; const c = getComputedStyle(e); return { t: e.innerText.trim(), bw: c.borderTopWidth, bs: c.borderTopStyle }; }, sel);

  // ---- 1. outlines on the other list headers ----
  await ev(() => { ['com.example.alpha', 'com.example.bravo'].forEach(p => toggleSelectPkg(p)); openShowApps(); }); await sleep(200);
  const sa = await ev(() => Array.from(document.querySelectorAll('#showAppsModal .batch-sheet-tools .batch-tool-link')).map(e => ({ t: e.innerText.trim(), bw: getComputedStyle(e).borderTopWidth, bs: getComputedStyle(e).borderTopStyle })));
  check('1. the Selected apps list has outlined Select All and Clear All buttons', sa.length === 2 && sa[0].t === 'Select All' && sa[1].t === 'Clear All' && sa.every(x => x.bw === '1px' && x.bs === 'solid'), JSON.stringify(sa));
  await ev(() => saRemove('com.example.bravo'));
  await ev(() => saSelectAll()); await sleep(100);
  const afterAll = await ev(() => [selectedPkgs.size, document.querySelectorAll('#saList .sa-row').length, filteredApps.length]);
  check('   Select All adds every app the list shows now (the uninstalled ones are in the list too)', afterAll[0] === afterAll[2] && afterAll[0] >= 2 && afterAll[1] === afterAll[0], JSON.stringify(afterAll));
  await ev(() => saClearAll()); await sleep(100);
  check('   Clear All empties the selection and closes the list', await ev(() => selectedPkgs.size === 0 && !document.getElementById('showAppsModal').classList.contains('show')));
  const nl = await outlined('#view-saved-lists .saved-lists-toolbar .batch-tool-link, .saved-lists-toolbar .batch-tool-link');
  check('   New List in Saved Applications is outlined the same way', nl && nl.t === 'New List' && nl.bw === '1px' && nl.bs === 'solid', JSON.stringify(nl));
  await ev(() => { uninstDataStart(); }); await sleep(100);
  const ud = await ev(() => Array.from(document.querySelectorAll('#uninstDataModal .batch-sheet-tools .batch-tool-link')).map(e => ({ t: e.innerText.trim(), bw: getComputedStyle(e).borderTopWidth })));
  check('   the Clear Data from Uninstalled Apps sheet has them too (Clear All instead of Select None)', ud.length === 2 && ud[0].t === 'Select All' && ud[1].t === 'Clear All' && ud.every(x => x.bw === '1px'), JSON.stringify(ud));
  await ev(() => uninstDataClose());

  // ---- 2. one app's leftover data from its row in the Uninstalled filter ----
  await ev(() => switchView('apps')); await sleep(200);
  const none = await ev(() => document.querySelectorAll('.row-leftover').length);
  check('2. without the Uninstalled filter no row offers it', none === 0, none);
  await ev(() => setFilter('uninstalled', null)); await sleep(200);
  const rows = await ev(() => Array.from(document.querySelectorAll('#view-apps .app-card, .app-card')).filter(c => c.querySelector('.row-leftover')).map(c => c.dataset.pkg));
  check('   in it, every uninstalled row has the button (and no installed app is in the list)', rows.join() === 'com.example.gone1,com.example.gone2', rows.join());
  await ev(() => document.querySelector('#card_com\\.example\\.gone2 .row-leftover').click()); await sleep(150);
  const t2 = await ev(() => document.getElementById('toastMsg').innerText);
  check('   Root: an app with no data left says so and starts nothing', /Gone Two has no data left/.test(t2) && (await ev(() => window.__batch.length)) === 0, t2);
  await ev(() => document.querySelector('#card_com\\.example\\.gone1 .row-leftover').click()); await sleep(250);
  const b1 = await ev(() => window.__batch);
  check('   Root: an app with data asks first (with the size), then clears just that app with the read-back batch', dialogs.some(d => /Gone One left behind \(5(\.0)? ?(KB|MB)\)/.test(d) && /cannot be undone/.test(d)) && b1.length === 1 && b1[0][0] === 'clear_removed_data' && b1[0][1].join() === 'com.example.gone1', JSON.stringify([dialogs, b1]));
  await ev(() => { document.getElementById('batchConfirmModal').classList.remove('show'); batchRunActive = false; window.__batch.length = 0; window.__root = false; });
  await ev(() => document.querySelector('#card_com\\.example\\.gone2 .row-leftover').click()); await sleep(250);
  const b2 = await ev(() => window.__batch);
  check('   without Root the size is unknown: it asks and clears', b2.length === 1 && b2[0][1].join() === 'com.example.gone2' && dialogs.some(d => /Gone Two left behind\?/.test(d)), JSON.stringify([dialogs, b2]));
  await ev(() => { document.getElementById('batchConfirmModal').classList.remove('show'); batchRunActive = false; setFilter('uninstalled', null); }); await sleep(100);
  check('   turning the filter off takes the buttons away', (await ev(() => document.querySelectorAll('.row-leftover').length)) === 0);

  // ---- 3. the VirusTotal card: what is left today ----
  await ev(() => switchView('prefs')); await sleep(200);
  check('3. no key, no quota line', await ev(() => getComputedStyle(document.getElementById('vtQuotaLine')).display === 'none'));
  const KEY = 'a'.repeat(64);
  await ev(k => { vtKeyTyped(k); }, KEY); await sleep(50);
  check('   a key that is not approved yet shows none', await ev(() => getComputedStyle(document.getElementById('vtQuotaLine')).display === 'none') && (await ev(() => window.__vt.filter(c => c[0] === 'vtQuotaLive').length)) === 0);
  await page.click('#vtTestBtn'); await sleep(500);
  const q1 = await ev(() => ({ shown: getComputedStyle(document.getElementById('vtQuotaLine')).display !== 'none', text: document.getElementById('vtQuotaLine').innerText, calls: window.__vt.filter(c => c[0] === 'vtQuotaLive').length }));
  check('   once the key is approved the card says 377 of 500 lookups are left today, 123 used, and when the day ends', q1.shown && /377 of 500 lookups left today \(123 used\)/.test(q1.text) && /00:00 UTC, in 5 h 12 min/.test(q1.text) && q1.calls === 1, JSON.stringify(q1));
  await ev(() => { window.__quota = { source: 'virustotal', dayAllowed: 500, dayUsed: 130, dayLeft: 370, resetDayInMs: 3600000 }; });
  await ev(() => document.querySelector('#vtQuotaLine a').click()); await sleep(300);
  check('   Refresh asks again and shows the new number', /370 of 500/.test(await ev(() => document.getElementById('vtQuotaLine').innerText)));
  await ev(() => { vtRefreshUi(); vtRefreshUi(); }); await sleep(100);
  check('   showing the card again within a minute does not ask VirusTotal again', (await ev(() => window.__vt.filter(c => c[0] === 'vtQuotaLive').length)) === 2);
  check('   the Installer\'s VirusTotal card carries the same line', /370 of 500/.test(await ev(() => document.getElementById('vtQuotaLineInst').textContent)));
  await ev(() => { window.__quota = { source: 'local', dayAllowed: 500, dayUsed: 7, dayLeft: 493, resetDayInMs: 120000 }; vtLoadQuota(true); }); await sleep(300);
  check('   when only this app\'s own count exists the line says so', /493 of 500 lookups left today \(7 used\), counted by this app only/.test(await ev(() => document.getElementById('vtQuotaLine').innerText)));
  await ev(() => { window.__quotaErr = 'VirusTotal answered HTTP 500'; vtLoadQuota(true); }); await sleep(300);
  check('   if it cannot be read the line says why and offers Try again', /could not be read: VirusTotal answered HTTP 500/.test(await ev(() => document.getElementById('vtQuotaLine').innerText)) && (await ev(() => document.querySelector('#vtQuotaLine a').innerText)) === 'Try again');
  await ev(() => vtClearKey()); await sleep(100);
  check('   removing the key hides the line', await ev(() => getComputedStyle(document.getElementById('vtQuotaLine')).display === 'none'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
