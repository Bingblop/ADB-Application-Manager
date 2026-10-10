// v7.10.12: the results of a batch come from what the phone says afterwards (Uninstalled / Still installed...), not from what the commands printed:
// the result list shows that word on each row, counts by it, and "Run again on the ones that failed" offers only the apps still installed.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
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
        // what the native side sends after it asked the package manager: every command said "failed", the phone says two are gone and charlie is still there
        const rows = pk.map(p => {
          const gone = p !== 'com.example.charlie';
          return { pkg: p, success: gone, label: gone ? 'Uninstalled' : 'Still installed', verified: true, commandOk: false,
            output: gone ? 'Checked afterwards: uninstalled. The command reported a failure, but the phone says it worked.\n\nFailure [x]' : 'Checked afterwards: still installed.\n\nFailure [x]' };
        });
        setTimeout(() => window.onAppBatchDone(JSON.stringify({ ok: true, action, total: pk.length, done: rows.filter(r => r.success).length, cancelled: false, rows })), 30);
        return 'started';
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { apps = null; });
  await ev(() => { ['com.example.alpha', 'com.example.bravo', 'com.example.charlie'].forEach(p => toggleSelectPkg(p)); expandBatchPanel(); }); await sleep(300);
  await ev(() => runBatchAction('uninstall', Array.from(selectedPkgs))); await sleep(400);
  const res = await ev(() => ({
    shown: document.getElementById('commandResultsModal').classList.contains('show'),
    tags: Array.from(document.querySelectorAll('#commandResultsList .tag-badge')).map(e => e.innerText),
    classes: Array.from(document.querySelectorAll('#commandResultsList .result-item')).map(e => e.classList.contains('success') ? 'success' : 'failed'),
    actions: Array.from(document.querySelectorAll('#commandResultsActions button')).map(e => e.innerText),
    toast: (document.querySelector('.toast, #toast') || {}).innerText,
  }));
  check('1. the results list opens', res.shown);
  check('   each row says what the phone found: Uninstalled, Uninstalled, Still installed', res.tags.join().toLowerCase() === 'uninstalled,uninstalled,still installed', res.tags.join());
  check('   rows are coloured by that (two succeeded, one failed), not by the commands', res.classes.join() === 'success,success,failed', res.classes.join());
  check('   "Run again on the 1 that failed" is offered for the app that is still installed', res.actions.some(a => /Run again on the 1 that failed/.test(a)), res.actions.join(' | '));
  check('   the toast counts from the phone\'s answer: 2 succeeded, 1 failed', /2 succeeded, 1 failed/.test(res.toast || ''), res.toast);
  const note = await ev(() => document.querySelector('#commandResultsList .result-item .result-item-output').innerText);
  check('   the first row says the command reported a failure but the phone says it worked', /command reported a failure, but the phone says it worked/.test(note), note);
  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
