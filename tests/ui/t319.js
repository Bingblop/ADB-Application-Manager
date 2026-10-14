// SD Maid > History: a deletion that ends while "Affected paths" is open no longer replaces that list with the History list. The engine
// emits {ev:"history"}; sdHistoryLoad wrote the reloaded History into whatever sheet was on top (the paths sheet, title unchanged), and the
// History sheet under it kept its old list. Now the History sheet (shown, or under the paths) gets it, and Back shows the new report.
// Also: paths that arrive after Back was pressed stay out of the History list. The mock answers as MainActivity.sdm does.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__store = {}; window.__holdPaths = false; window.__held = null;
    let reports = [{ id: 'r1', tool: 'systemcleaner', status: 'success', primary: 'Old run', bytes: 10, startAt: 1, hasPaths: true }];
    window.__addReport = () => { reports = [{ id: 'r2', tool: 'appcleaner', status: 'success', primary: 'New run', bytes: 20, startAt: 2, hasPaths: true }].concat(reports); };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      loadStore(k) { return window.__store[k] || null; }, saveStore(k, v) { window.__store[k] = v; return true; },
      sdm(tag, op, args) {
        let r = { ok: true };
        if (op === 'historyList') r = { ok: true, reports, total: reports.length, stats: { freedBytes: reports.reduce((n, x) => n + x.bytes, 0), items: reports.length } };
        if (op === 'historyPaths') r = { ok: true, paths: [{ path: '/storage/emulated/0/a.log', action: 'deleted' }], total: 1, bytes: 10 };
        // the same text MainActivity.sdm evaluates: window.onSdmReply({tag:"..",r:<json>})
        const answer = () => (0, eval)('window.onSdmReply && window.onSdmReply({tag:' + JSON.stringify(tag) + ',r:' + JSON.stringify(r) + '})');
        if (op === 'historyPaths' && window.__holdPaths) { window.__held = answer; return; }
        setTimeout(answer, 10);
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(800);
  const sleep = ms => page.waitForTimeout(ms);
  const snap = () => page.evaluate(() => ({ title: document.getElementById('sdSheetTitle').textContent, body: document.getElementById('sdSheetBody').innerText.replace(/\s+/g, ' ').trim().replace(/[A-Z][a-z]{2} \d{1,2}, \d{4}, \d{1,2}:\d{2}\s?[AP]M/g, '<date>') }));
  const finished = () => page.evaluate(() => { window.__addReport(); (0, eval)('window.onSdm && window.onSdm({"ev":"history"})'); });

  // 1. History open on its own: a finished deletion still refreshes it
  await page.evaluate(() => sdHistoryOpen()); await sleep(100);
  let s = await snap();
  check('1. History lists the old run', s.title === 'History' && /Old run/.test(s.body) && !/New run/.test(s.body), JSON.stringify(s));

  // 2. Affected paths open over it, then a deletion ends
  await page.evaluate(() => sdHistoryPaths(0)); await sleep(100);
  s = await snap();
  check('2. Affected paths lists the path', s.title === 'Affected paths' && /a\.log/.test(s.body), JSON.stringify(s));
  await finished(); await sleep(100);
  s = await snap();
  check('   a deletion that ends leaves the paths list as it is', s.title === 'Affected paths' && /a\.log/.test(s.body) && !/New run/.test(s.body), JSON.stringify(s));
  await page.evaluate(() => sdSheetBack()); await sleep(50);
  s = await snap();
  check('   Back shows History with the new report', s.title === 'History' && /New run/.test(s.body) && /Old run/.test(s.body), JSON.stringify(s));

  // 3. History shown: the next report goes straight into it
  await finished(); await sleep(100);
  s = await snap();
  check('3. with History shown, a finished deletion refreshes it', (s.body.match(/New run/g) || []).length === 2, JSON.stringify(s));

  // 4. paths that arrive after Back stay out of the History list
  await page.evaluate(() => { window.__holdPaths = true; sdHistoryPaths(0); }); await sleep(50);
  await page.evaluate(() => sdSheetBack()); await sleep(30);
  await page.evaluate(() => { window.__holdPaths = false; window.__held && window.__held(); }); await sleep(50);
  s = await snap();
  check('4. paths answered after Back do not replace the History list', s.title === 'History' && /New run/.test(s.body) && !/a\.log/.test(s.body), JSON.stringify(s));

  await page.evaluate(() => sdSheetClose());
  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
