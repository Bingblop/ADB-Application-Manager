// v7.11.0: Trackers. A "Trackers" filter pill in the Apps tab (the first tap starts a scan of the apps without a result, with its count growing as results arrive), and a
// TRACKERS chip in the app menu that opens the list of the tracker libraries found (with the Exodus credit and the limits).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [
    { pkg: 'com.example.alpha', name: 'Alpha', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.bravo', name: 'Bravo', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.charlie', name: 'Charlie', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.gone', name: 'Gone', isFrozen: false, isSuspended: false, isUninstalled: true },
  ];
  await page.addInitScript(a => {
    window.__scans = []; window.__cache = {};
    const results = { 'com.example.alpha': { ok: true, ids: [49, 312] }, 'com.example.bravo': { ok: true, ids: [] }, 'com.example.charlie': { ok: false, ids: [] } };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      getUadInfo() { return '{}'; },
      trackerCached(json) { const o = {}; JSON.parse(json).forEach(p => { if (window.__cache[p]) o[p] = window.__cache[p]; }); return JSON.stringify(o); },
      trackerScan(json) {
        const pkgs = JSON.parse(json); window.__scans.push(pkgs);
        pkgs.forEach((p, i) => setTimeout(() => {
          window.__cache[p] = results[p] || { ok: true, ids: [] };
          const m = {}; m[p] = results[p] || { ok: true, ids: [] };
          window.onTrackers({ done: i + 1, total: pkgs.length, apps: m, finished: false });
          if (i === pkgs.length - 1) setTimeout(() => window.onTrackers({ done: pkgs.length, total: pkgs.length, apps: {}, finished: true }), 30);
        }, 60 * (i + 1)));
        return 'started';
      },
      trackerUpdate() { window.__upd = (window.__upd || 0) + 1; setTimeout(() => { window.__cache = {}; window.onTrackerUpdate(window.__updFail ? { ok: false, error: 'Exodus Privacy answered HTTP 500' } : { ok: true, count: 431, retrieved: '2026-11-01' }); }, 120); return 'started'; },
      trackerInfo(json) { return JSON.stringify({ count: 428, retrieved: '2026-10-03', trackers: { 49: { id: 49, name: 'Google Firebase Analytics', categories: 'Analytics', website: 'https://firebase.google.com/' }, 312: { id: 312, name: 'Google AdMob', categories: 'Advertisement', website: 'https://admob.google.com' } } }); },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { isPrivilegedActive = true; });

  const pill = await ev(() => { const e = document.querySelector('.filter-pill[data-filter="trackers"]'); return e ? e.innerText.trim() : null; });
  check('1. a Trackers pill is after Patched, with a question mark before the first scan', pill === 'Trackers (?)' && await ev(() => { const p = Array.from(document.querySelectorAll('#filterScroll .filter-pill')).map(e => e.dataset.filter); return p.indexOf('trackers') === p.indexOf('patched') + 1; }), pill);
  check('   nothing is scanned before it is needed', (await ev(() => window.__scans.length)) === 0);
  await ev(() => document.querySelector('.filter-pill[data-filter="trackers"]').click()); await sleep(80);
  check('2. the first tap starts one scan of the installed apps only (the uninstalled one is left out) and says so', (await ev(() => JSON.stringify(window.__scans))) === '[["com.example.alpha","com.example.bravo","com.example.charlie"]]' && /Looking for trackers in 3 apps/.test(await ev(() => document.getElementById('toastMsg').innerText)));
  const mid = await ev(() => document.getElementById('countTrackers').innerText);
  check('   while it runs the pill counts the apps done', /^\d\/3$/.test(mid), mid);
  await sleep(500);
  const end = await ev(() => ({ n: document.getElementById('countTrackers').innerText, rows: Array.from(document.querySelectorAll('.app-card')).map(c => c.dataset.pkg), active: document.querySelector('.filter-pill[data-filter="trackers"]').classList.contains('active') }));
  check('3. after it the count is the apps with at least one tracker, and the list shows just those', end.n === '1' && end.rows.join() === 'com.example.alpha' && end.active, JSON.stringify(end));
  await ev(() => document.querySelector('.filter-pill[data-filter="trackers"]').click()); await sleep(100);
  await ev(() => document.querySelector('.filter-pill[data-filter="trackers"]').click()); await sleep(100);
  check('   turning it off and on again does not scan again', (await ev(() => window.__scans.length)) === 1);
  await ev(() => setFilter('all')); await sleep(100);

  // the app menu
  await ev(() => openInspector('com.example.alpha')); await sleep(250);
  const chip = await ev(() => { const c = document.getElementById('sheetTrk'); return { shown: getComputedStyle(c).display !== 'none', text: c.innerText.replace(/\s+/g, ' ').trim(), cls: c.className }; });
  check('4. the app menu shows TRACKERS 2 for the app, in the warning colour', chip.shown && chip.text === 'TRACKERS 2' && /trk-n/.test(chip.cls), JSON.stringify(chip));
  await ev(() => document.getElementById('sheetTrk').click()); await sleep(150);
  const m = await ev(() => ({ open: document.getElementById('trackersModal').classList.contains('show'), app: document.getElementById('trackersApp').innerText, body: document.getElementById('trackersBody').innerText, credit: document.getElementById('trackersCredit').innerText }));
  check('   tapping it lists the trackers with their kinds', m.open && /Alpha · com\.example\.alpha/.test(m.app) && /Google Firebase Analytics/.test(m.body) && /Analytics/.test(m.body) && /Google AdMob/.test(m.body) && /Advertisement/.test(m.body), JSON.stringify(m));
  check('   with the Exodus credit, the licence, the date of the list and the limit of the method', /Exodus Privacy \(428 trackers, copied 2026-10-03\)/.test(m.credit) && /ODbL 1\.0/.test(m.credit) && /not proof/.test(m.credit) && /nothing is sent anywhere/.test(m.credit), m.credit);
  await ev(() => closeTrackersInfo()); await ev(() => closeInspector()); await sleep(150);
  await ev(() => openInspector('com.example.bravo')); await sleep(200);
  check('5. an app with none says TRACKERS 0 (green); the list says none was found', await ev(() => { const c = document.getElementById('sheetTrk'); return c.innerText.replace(/\s+/g, ' ').trim() === 'TRACKERS 0' && /trk-0/.test(c.className); }));
  await ev(() => { document.getElementById('sheetTrk').click(); }); await sleep(100);
  check('   the list says that no known tracker was found', /No known tracker library was found/.test(await ev(() => document.getElementById('trackersBody').innerText)));
  await ev(() => { closeTrackersInfo(); closeInspector(); }); await sleep(150);
  await ev(() => openInspector('com.example.charlie')); await sleep(200);
  check('6. an app whose code cannot be read says TRACKERS ? and does not claim zero', await ev(() => { const c = document.getElementById('sheetTrk'); return c.innerText.replace(/\s+/g, ' ').trim() === 'TRACKERS ?' && /trk-na/.test(c.className); }));
  await ev(() => { closeInspector(); }); await sleep(150);
  await ev(() => openInspector('com.example.gone')); await sleep(200);
  check('7. an uninstalled app has no chip', await ev(() => getComputedStyle(document.getElementById('sheetTrk')).display === 'none'));
  await ev(() => closeInspector());

  // update the list
  await ev(() => openInspector('com.example.alpha')); await sleep(200);
  await ev(() => document.getElementById('sheetTrk').click()); await sleep(100);
  await ev(() => document.getElementById('trackersUpdateBtn').click()); await sleep(40);
  const up1 = await ev(() => ({ t: document.getElementById('trackersUpdateBtn').innerText, d: document.getElementById('trackersUpdateBtn').disabled }));
  check('9. Update the list asks the app and shows that it is working', up1.t === 'Updating…' && up1.d === true && (await ev(() => window.__upd)) === 1, JSON.stringify(up1));
  await sleep(300);
  const up2 = await ev(() => ({ toast: document.getElementById('toastMsg').innerText, open: document.getElementById('trackersModal').classList.contains('show'), btn: document.getElementById('trackersUpdateBtn').innerText, scans: window.__scans.length }));
  check('   when it is done the toast says how many trackers the new list has, the old results are dropped and the open app is read again', /Tracker list updated: 431 trackers/.test(up2.toast) && !up2.open && up2.btn === 'Update the list' && up2.scans === 2, JSON.stringify(up2));
  await ev(() => { window.__updFail = true; openTrackersInfo(); document.getElementById('trackersUpdateBtn').click(); }); await sleep(300);
  check('10. a failed update says why and keeps the button usable', /Tracker list not updated: Exodus Privacy answered HTTP 500/.test(await ev(() => document.getElementById('toastMsg').innerText)) && (await ev(() => document.getElementById('trackersUpdateBtn').disabled)) === false);
  await ev(() => { closeTrackersInfo(); closeInspector(); });

  // a second start: results are kept by the native side, so nothing is left to scan
  await ev(() => { window.__cache = { 'com.example.alpha': { ok: true, ids: [49] }, 'com.example.bravo': { ok: true, ids: [] }, 'com.example.charlie': { ok: false, ids: [] } }; trackerMap = {}; trackersLoadCached(); });
  check('8. the results of an earlier run come back from the cache at the next start, so nothing is left to scan', await ev(() => Object.keys(trackerMap).length) === 3 && (await ev(() => trackersMissing().length)) === 0);

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
