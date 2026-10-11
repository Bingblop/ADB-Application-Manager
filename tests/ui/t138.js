// v7.10.14: the Connected Devices app list reads the device back after enable / disable / uninstall / reinstall ("Checking the device…", then what it found); Settings > Progress messages
// turns the "Working on it… / Checking the phone… / Checking the device…" toasts off (results still show); Clear data comes back with what was left in the folder (Root mode, native).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  const apps = ['alpha', 'bravo'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isFrozen: false, isSuspended: false, isUninstalled: false }));
  await page.addInitScript(a => {
    window.__sync = []; window.__checked = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      executeAppAction(action, pkg) { window.__sync.push([action, pkg]); return '\u00011Success'; },
      appActionChecked(action, pkg, token) {
        window.__checked.push([action, pkg]);
        if (action === 'clear_data' && window.__notRoot) return 'unsupported';
        setTimeout(() => window.onAppActionChecking(token), 20);
        setTimeout(() => window.onAppActionChecked(token, { pkg, action, success: true, label: 'Data cleared', verified: true, commandOk: true, output: 'Checked afterwards: 12 files (3.4 MB) before, 0 after (4 KB).\n\nSuccess' }), 60);
        return 'started';
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { window.__toasts = []; const o = showToast; window.showToast = function (m) { window.__toasts.push(m); return o(m); }; });
  const seen = () => ev(() => window.__toasts.slice());

  // ---- 1. Settings > Progress messages ----
  check('1. the Settings card exists and the switch is on by default', await ev(() => { switchView('prefs'); return !!document.getElementById('progressToastsCard') && document.getElementById('progressToastsToggle').checked === true; }));
  await ev(() => { apps = null; });
  await ev(() => { window.__toasts.length = 0; quickAction('clear_data', 'com.example.alpha'); }); await sleep(200);
  let t = await seen();
  check('   on: Working on it…, Checking the phone…, then the result "Alpha: Data cleared"', t.includes('Working on it…') && t.includes('Checking the phone…') && t[t.length - 1] === 'Alpha: Data cleared', JSON.stringify(t));
  check('   Clear data went the checked way', await ev(() => window.__checked.length === 1 && window.__sync.length === 0));
  await ev(() => { document.getElementById('progressToastsToggle').click(); });
  check('   turning it off is kept and says so', await ev(() => kvGet('progress_toasts', true) === false) && (await seen()).includes('Progress messages off'));
  await ev(() => { window.__toasts.length = 0; quickAction('clear_data', 'com.example.bravo'); }); await sleep(200);
  t = await seen();
  check('   off: no Working on it… and no Checking the phone…; the result is still shown', !t.includes('Working on it…') && !t.includes('Checking the phone…') && t[t.length - 1] === 'Bravo: Data cleared', JSON.stringify(t));
  await ev(() => { window.__notRoot = true; window.__toasts.length = 0; quickAction('clear_data', 'com.example.alpha'); }); await sleep(150);
  check('   without Root the native side says "unsupported" and the plain way is taken', await ev(() => window.__sync.length === 1 && window.__sync[0][0] === 'clear_data'));
  await ev(() => { document.getElementById('progressToastsToggle').click(); });
  check('   turned on again', await ev(() => kvGet('progress_toasts', true) === true && progressToastsEnabled()));

  // ---- 2. Connected Devices: the apps are read back ----
  await ev(() => {
    window.__dev = { inst: new Set(['com.a', 'com.b', 'com.c']), dis: new Set(), cmds: [], sticky: new Set() };
    cd.serial = 'watch'; cd.devices = [{ serial: 'watch', state: 'device' }]; cdReady = () => true;
    cd.apps = ['com.a', 'com.b', 'com.c'].map(p => ({ pkg: p, name: p, system: false, disabled: false, uninstalled: false }));
    cdSh = async (cmd) => {
      const d = window.__dev; d.cmds.push(cmd);
      if (/^echo '#ALL'/.test(cmd)) return ['#ALL'].concat(['com.a', 'com.b', 'com.c'].map(p => 'package:/data/app/' + p + '/base.apk=' + p), ['#INST'], [...d.inst].map(p => 'package:' + p), ['#DIS'], [...d.dis].map(p => 'package:' + p), ['#SYS']).join('\n');
      let m;
      if ((m = /^pm uninstall --user 0 '(.+)'$/.exec(cmd))) { if (!d.sticky.has(m[1])) d.inst.delete(m[1]); return d.sticky.has(m[1]) ? 'Failure [DELETE_FAILED_INTERNAL_ERROR]' : 'Failure [x]'; }      // gone, though the command printed a failure (the case in the report)
      if ((m = /^pm disable-user --user 0 '(.+)'$/.exec(cmd))) { if (!d.sticky.has(m[1])) d.dis.add(m[1]); return 'Package ' + m[1] + ' new state: disabled-user'; }                 // said it worked, did not
      return '';
    };
    window.__dev.sticky.add('com.c');
    window.__toasts.length = 0;
  });
  let r = await ev(async () => await cdDo('uninstall', 'com.a'));
  check('2. an uninstall whose command printed "Failure" but whose app is gone counts as done', r.ok === true && r.label === 'Uninstalled' && /The command reported a failure, but the device says it worked/.test(r.out), JSON.stringify(r));
  check('   "Checking the device…" was shown', (await seen()).includes('Checking the device…'));
  check('   the app record follows the device', await ev(() => cdApp('com.a').uninstalled === true));
  r = await ev(async () => await cdDo('disable', 'com.c'));
  check('   a disable the command called a success, though the app is not disabled, is a failure ("Not disabled")', r.ok === false && r.label === 'Not disabled' && /although the command reported success/.test(r.out), JSON.stringify(r));
  check('   the record says it is not disabled', await ev(() => cdApp('com.c').disabled === false));
  r = await ev(async () => await cdDo('uninstall', 'com.c'));
  check('   an uninstall that really failed is "Still installed"', r.ok === false && r.label === 'Still installed', JSON.stringify(r));
  // a batch: one look for all of them
  await ev(() => { window.__dev.cmds.length = 0; });
  await ev(() => { cd.sel.clear(); cd.sel.add('com.b'); cd.sel.add('com.c'); cdAsk = async () => true; window.__toasts.length = 0; });
  await ev(async () => { await cdBatch('disable'); });
  const lists = await ev(() => window.__dev.cmds.filter(c => /^echo '#ALL'/.test(c)).length);
  check('   a batch looks at the device once for all the apps (twice when something is not as wanted)', lists >= 1 && lists <= 2, lists);
  check('   the batch says "Checking the device…"', (await seen()).includes('Checking the device…'));
  const sheet = await ev(() => ({ open: document.getElementById('cdSheet').classList.contains('show'), title: document.getElementById('cdSheetTitle').innerText, body: document.getElementById('cdSheetBody').innerText }));
  check('   and names the one that is not disabled (com.c), not the one that is', sheet.open && /1 of 2 did not work/.test(sheet.title) && /com\.c: Not disabled/.test(sheet.body) && !/com\.b/.test(sheet.body), JSON.stringify(sheet));
  await ev(() => { document.getElementById('progressToastsToggle').click(); window.__toasts.length = 0; });
  await ev(async () => { await cdDo('enable', 'com.b'); });
  check('   with Progress messages off the device is checked without the message', !(await seen()).includes('Checking the device…'), JSON.stringify(await seen()));

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
