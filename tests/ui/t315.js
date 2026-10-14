// A batch action, a profile and a Dex optimization batch report what ran. MainActivity ends appActionBatch and optimizeAppBatch with
// notifyJs("window.onAppBatchDone && window.onAppBatchDone(" + res.toString() + ")"): the result reaches the page as an object, not a quoted JSON
// text. The page used to JSON.parse it, so every batch said "Batch action failed to run" and a profile stopped after its first group.
// The mock here evaluates the very same script text that Java builds; a JSON text (the old mocks' form) is still taken too.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  const apps = [
    { pkg: 'com.example.alpha', name: 'Alpha', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.bravo', name: 'Bravo', isFrozen: true, isSuspended: false, isUninstalled: false },
  ];
  await page.addInitScript(a => {
    window.__store = {}; window.__batches = []; window.__optimized = [];
    // what WebView.evaluateJavascript does with the text notifyJs is given
    const javaNotify = script => setTimeout(() => (0, eval)(script), 20);
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      loadStore(k) { return window.__store[k] || null; }, saveStore(k, v) { window.__store[k] = v; return true; },
      appActionBatch(action, pkgsJson) {
        const pk = JSON.parse(pkgsJson);
        window.__batches.push(action + ':' + pk.join(','));
        const res = { action, total: pk.length, done: pk.length, cancelled: false, rows: pk.map(p => ({ pkg: p, output: 'ok', success: true })), ok: true };
        javaNotify('window.onAppBatchDone && window.onAppBatchDone(' + JSON.stringify(res) + ')');          // MainActivity.appActionBatch: res.toString(), unquoted
        return 'started';
      },
      optimizeAppBatch(pkgsJson, mode, force) {
        const pk = JSON.parse(pkgsJson);
        window.__optimized.push(pk.join(','));
        const res = { total: pk.length, done: pk.length, cancelled: false, rows: pk.map(p => ({ pkg: p, output: 'Success', success: true })), ok: true };
        javaNotify('window.onOptimizeBatchDone && window.onOptimizeBatchDone(' + JSON.stringify(res) + ')'); // MainActivity.optimizeAppBatch: the same form
        return 'started';
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const results = () => ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), sub: (document.getElementById('commandResultsSubtitle') || {}).innerText }));
  await ev(() => { apps = null; });

  // ---- 1. a plain batch: Freeze two apps ----
  await ev(() => { ['com.example.alpha', 'com.example.bravo'].forEach(p => toggleSelectPkg(p)); runBatchAction('freeze', Array.from(selectedPkgs)); });
  await sleep(300);
  check('1. a batch Freeze reports what ran', /Batch Done: 2 succeeded, 0 failed/.test(await toast()), await toast());
  let r = await results();
  check('   and shows its results', r.shown && /2 of 2 apps/.test(r.sub), JSON.stringify(r));
  check('   the progress sheet is closed', await ev(() => !document.getElementById('batchConfirmModal').classList.contains('show')));
  await ev(() => closeCommandResultsModal());

  // ---- 2. a profile with two groups: re-enable Bravo, then disable Alpha ----
  await ev(() => {
    profiles = [{ id: 'p1', name: 'Work', created: Date.now(), apps: [
      { pkg: 'com.example.alpha', name: 'Alpha', state: 'disabled', frozen: true, suspended: false },
      { pkg: 'com.example.bravo', name: 'Bravo', state: 'enabled', frozen: false, suspended: false }] }];
    window.__batches.length = 0; openProfiles(); previewProfile('p1'); applyProfile();
  });
  await sleep(400);
  check('2. both groups of the profile are sent, in order', (await ev(() => window.__batches.join(' | '))) === 'unfreeze:com.example.bravo | freeze:com.example.alpha', await ev(() => window.__batches.join(' | ')));
  check('   the toast counts every app', /Profile applied: 2 succeeded, 0 failed/.test(await toast()), await toast());
  r = await results();
  check('   the results list both apps', r.shown && /Work • 2 apps/.test(r.sub), JSON.stringify(r));
  check('   the run is over', await ev(() => !profileRunActive && profileRunState === null));
  await ev(() => closeCommandResultsModal());

  // ---- 3. a Dex optimization batch ----
  await ev(() => { clearBatchSelection(); toggleSelectPkg('com.example.alpha'); toggleSelectPkg('com.example.bravo'); openOptimizeModal('batch'); confirmOptimize(); });
  await sleep(300);
  check('3. a Dex optimization batch reports what ran', /Optimized 2\/2/.test(await toast()), await toast());
  r = await results();
  check('   and shows its results', r.shown && /2 apps/.test(r.sub), JSON.stringify(r));
  await ev(() => closeCommandResultsModal());

  // ---- 4. a JSON text is still taken, and a failed run still says so ----
  await ev(() => { batchRunActive = true; window.onAppBatchDone(JSON.stringify({ ok: true, action: 'freeze', total: 1, done: 1, cancelled: false, rows: [{ pkg: 'com.example.alpha', output: 'ok', success: true }] })); });
  await sleep(100);
  check('4. a JSON text result is read the same way', /Batch Done: 1 succeeded, 0 failed/.test(await toast()), await toast());
  await ev(() => closeCommandResultsModal());
  await ev(() => { batchRunActive = true; window.onAppBatchDone({ ok: false, error: 'boom' }); });
  await sleep(100);
  check('   an object with ok:false still says the batch failed to run', /Batch action failed to run/.test(await toast()), await toast());
  await ev(() => { optimizeRunActive = true; window.onOptimizeBatchDone({ ok: false }); });
  await sleep(100);
  check('   and so does a failed Dex optimization', /Dex optimization failed to run/.test(await toast()), await toast());

  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
