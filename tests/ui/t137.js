// v7.10.13: the app menu's Uninstall / Freeze / Suspend of one app are read back from the phone (a "Checking the phone…" step, then what was found), a batch says "Checking the phone…"
// on its progress sheet before it reports, and the console of Connected Devices suggests commands as you type (Right arrow or a tap accepts).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  const apps = ['alpha', 'bravo'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isFrozen: false, isSuspended: false, isUninstalled: false }));
  await page.addInitScript(a => {
    window.__checked = []; window.__sync = []; window.__loads = 0;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { window.__loads++; return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      executeAppAction(action, pkg) { window.__sync.push([action, pkg]); return '\u00011Success'; },
      appActionChecked(action, pkg, token) {
        window.__checked.push([action, pkg]);
        setTimeout(() => window.onAppActionChecking(token), 20);
        // the command said "failed", the phone says the app is gone
        const gone = !(action === 'uninstall' && pkg === 'com.example.bravo');
        setTimeout(() => window.onAppActionChecked(token, { pkg, action, success: gone, label: gone ? (action === 'suspend' ? 'Suspended' : 'Uninstalled') : 'Still installed', verified: true, commandOk: false, output: 'Checked afterwards.\n\nFailure [x]' }), 60);
        return 'started';
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);

  // ---- 1. one app: Uninstall from the app menu is read back ----
  await ev(() => { apps = null; });
  await ev(() => quickAction('uninstall', 'com.example.alpha')); await sleep(30);
  check('1. the plain call is not used for Uninstall: the checked one is', await ev(() => window.__checked.length === 1 && window.__sync.length === 0));
  check('   the toast first says the phone is being asked', /Checking the phone/.test(await toast()), await toast());
  await sleep(120);
  check('   then it says what was found: Alpha: Uninstalled', /Alpha: Uninstalled/.test(await toast()), await toast());
  check('   a success shows no result window', await ev(() => !document.getElementById('commandResultsModal').classList.contains('show')));
  check('   the history has it', await ev(() => JSON.stringify(getHistory()).indexOf('com.example.alpha') > 0));
  await ev(() => quickAction('uninstall', 'com.example.bravo')); await sleep(200);
  check('   an app that is still installed says so, and the result window opens', /Bravo: Still installed/.test(await toast()) && await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /still installed/i.test(document.getElementById('commandResultsList').innerText)), await toast());
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); });
  await ev(() => quickAction('suspend', 'com.example.alpha')); await sleep(200);
  check('   Suspend is read back too', /Alpha: Suspended/.test(await toast()) && await ev(() => window.__checked.length === 3), await toast());
  await ev(() => quickAction('force_stop', 'com.example.alpha')); await sleep(60);
  check('   Force stop is read back too since v7.10.17 (the process should be gone)', await ev(() => window.__sync.length === 0 && window.__checked.length === 4 && window.__checked[3][0] === 'force_stop'));
  await ev(() => quickAction('launch', 'com.example.alpha')); await sleep(60);
  check('   an action with nothing to read back (Open) takes the plain way', await ev(() => window.__sync.length === 1 && window.__sync[0][0] === 'launch'));

  // ---- 2. a batch says it is checking the phone ----
  await ev(() => { window.AndroidBridge.appActionBatch = () => 'started'; });
  await ev(() => { runBatchAction('uninstall', ['com.example.alpha', 'com.example.bravo']); });
  await ev(() => window.onAppBatchProgress(1, 2, 'com.example.bravo')); await sleep(30);
  await ev(() => window.onAppBatchChecking(2));
  const prog = await ev(() => ({ text: document.getElementById('batchProgressText').innerText, bar: document.getElementById('batchProgressBar').style.width, stop: document.getElementById('batchProgressStopBtn').disabled }));
  check('2. the progress sheet says "Checking the phone…" with the number of apps and a full bar', /Checking the phone… \(2 apps\)/.test(prog.text) && prog.bar === '100%', JSON.stringify(prog));
  check('   Stop is off while it checks', prog.stop === true);
  await ev(() => window.onAppBatchDone(JSON.stringify({ ok: true, action: 'uninstall', total: 2, done: 2, cancelled: false, rows: [{ pkg: 'com.example.alpha', success: true, label: 'Uninstalled', output: 'ok' }, { pkg: 'com.example.bravo', success: true, label: 'Uninstalled', output: 'ok' }] }))); await sleep(100);
  check('   then the results open', await ev(() => document.getElementById('commandResultsModal').classList.contains('show')));
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); });

  // ---- 3. the console of Connected Devices suggests commands ----
  await ev(() => { cd.hist = ['pm list packages -3', 'getprop ro.product.model']; cd.apps = [{ pkg: 'com.wear.weather' }, { pkg: 'com.google.android.deskclock' }]; cd.mode = 'shell'; });
  await ev(() => { switchView('devices'); document.getElementById('cdWork').style.display = ''; document.getElementById('cdEmpty').style.display = 'none'; cdSub('console'); }); await sleep(300);      // the panel that a connected device shows
  const type = async t => { await ev(t => { const i = document.getElementById('cdInput'); i.focus(); i.value = t; i.setSelectionRange(t.length, t.length); i.dispatchEvent(new Event('input', { bubbles: true })); }, t); await sleep(40); };
  const hint = () => ev(() => { const a = document.getElementById('cdAc'); return a.hidden ? '' : a.innerText.replace(/\s*→\s*$/, '').replace(/\s+/g, ' ').trim(); });
  await type('getprop ro.product.m');
  check('3. a line from the history is offered: getprop ro.product.model', (await hint()).startsWith('getprop ro.product.model'), await hint());
  await page.keyboard.press('ArrowRight'); await sleep(40);
  check('   Right arrow at the end accepts it', await ev(() => document.getElementById('cdInput').value) === 'getprop ro.product.model');
  await type('pm path com.wear.we');
  check('   after pm path, the device\'s own package names (from its Apps list) are offered', (await hint()).startsWith('pm path com.wear.weather'), await hint());
  await page.click('#cdAc'); await sleep(40);
  check('   a tap on the line accepts too', await ev(() => document.getElementById('cdInput').value) === 'pm path com.wear.weather');
  await type('dumpsys batt');
  check('   common commands are offered', (await hint()).startsWith('dumpsys battery'), await hint());
  await type('xyzzy');
  check('   nothing matches: no suggestion', (await hint()) === '');
  await type('adb pu');
  check('   after "adb " the lines for adb itself are offered', (await hint()).startsWith('adb pull /sdcard/') || (await hint()).startsWith('adb push '), await hint());
  await type('adb shell pm path com.google.android.desk');
  check('   after "adb shell" the device\'s shell is completed (a package from its list)', (await hint()).startsWith('adb shell pm path com.google.android.deskclock'), await hint());
  await ev(() => cdModeToggle());
  await type('pul');
  check('   in adb mode the typed line is for adb: pull…', (await hint()).startsWith('pull /sdcard/'), await hint());
  await type('uninstall com.wear.we');
  check('   adb uninstall takes a package of the device', (await hint()).startsWith('uninstall com.wear.weather'), await hint());
  await ev(() => cdModeToggle());
  await type('getprop ro.product.model');
  check('   a finished line has no suggestion', (await hint()) === '');
  await type('');
  check('   an empty box has none', (await hint()) === '');
  await type('pm list packages -3');
  await ev(() => { document.getElementById('cdInput').setSelectionRange(2, 2); document.getElementById('cdInput').dispatchEvent(new Event('click', { bubbles: true })); }); await sleep(30);
  check('   with the cursor in the middle there is no suggestion', (await hint()) === '');

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
