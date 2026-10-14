// MA1-1: while a package installs, the Installer does not read another one. Reading it would take the installer's work folder, and the
// installed game's OBB data, from under the running install; the page says "An install is already running" instead and keeps the package
// being installed loaded. Covers the file picker, a found file, a shell-only found file (not staged), an "Open with" hand-over and the file
// manager's Install; once the result is in, another package opens as before.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const D = '/storage/emulated/0/Download/';
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  page.on('dialog', d => d.accept().catch(() => {}));
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, files: [D + 'A.xapk', D + 'B.apk', D + 'C.apk'], pickRef: D + 'B.apk' });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.onApkScanProgress === 'function');
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const mk = (pkg, label, extras) => ({ type: extras ? 'xapk' : 'apk', pkg, label, versionName: '1', versionCode: 1, signed: true, installed: false,
    splits: [{ path: '/data/user/0/x/cache/installer/p1/base.apk', name: 'base.apk', size: 10, isBase: true, split: '', configFor: '', feature: false }],
    extras: extras ? [{ name: 'main.obb', dest: 'Android/obb/' + pkg + '/main.obb', size: 100 }] : [], extrasTotal: extras ? 100 : 0, totalSize: 10 });
  await ev(([a, b2, c]) => { window.__pkgs[a.ref] = a.d; window.__pkgs[b2.ref] = b2.d; window.__pkgs[c.ref] = c.d; window.__pkgs['/data/local/tmp/fm_install.apk'] = c.d; },
    [{ ref: D + 'A.xapk', d: mk('com.game.a', 'Game A', true) }, { ref: D + 'B.apk', d: mk('com.app.b', 'App B', false) }, { ref: D + 'C.apk', d: mk('com.app.c', 'App C', false) }]);
  await ev(() => switchView('installer'));
  await page.waitForFunction(() => window.__scanPending === true);
  await ev(() => window.__scanFinish());
  await page.waitForFunction(() => document.querySelectorAll('#apkScanList .apk-scan-row').length === 3);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const state = () => ev(() => ({ running: installRunning, inspected: window.__calls.inspect.length, shown: installData && installData.pkg, opened: window.__calls.opened.filter(o => o.startsWith('fmInstall')).length, fromFiles: installFromFiles }));

  await ev(r => onInstallFilePicked(r), D + 'A.xapk');
  await page.waitForFunction(() => installData && installData.pkg === 'com.game.a');
  await ev(() => runInstall());
  await sleep(50);
  const s0 = await state();
  check('1. Install on game A starts (with its OBB data)', s0.running && (await ev(() => window.__calls.install.length)) === 1 && (await ev(() => window.__calls.install[0].copyExtras)) === true, JSON.stringify(s0));

  await ev(() => onInstallFilePicked('/storage/emulated/0/Download/B.apk'));
  await sleep(50);
  let s = await state();
  check('2. a package handed in while A installs is not read: A stays loaded and the page says why', s.inspected === 1 && s.shown === 'com.game.a' && (await toast()) === 'An install is already running', JSON.stringify(s));

  await ev(() => pickInstallFile('apk'));
  await sleep(50);
  s = await state();
  check('3. the file picker does not open while A installs', s.inspected === 1 && s.shown === 'com.game.a' && (await toast()) === 'An install is already running', JSON.stringify(s));

  const idxB = await ev(() => apkScanFiles.findIndex(f => f.name === 'B.apk'));
  await ev(i => installFromScan(i), idxB);
  await sleep(50);
  s = await state();
  check('4. tapping a found package does not read it while A installs', s.inspected === 1 && s.shown === 'com.game.a', JSON.stringify(s));

  await ev(() => { const f = apkScanFiles.find(x => x.name === 'C.apk'); f.shell = true; });
  const idxC = await ev(() => apkScanFiles.findIndex(f => f.name === 'C.apk'));
  await ev(i => installFromScan(i), idxC);
  await sleep(50);
  s = await state();
  check('5. a found package only the shell can read is not even staged while A installs', s.opened === 0 && s.inspected === 1 && s.shown === 'com.game.a', JSON.stringify(s));

  await ev(() => onInstallIntent('content://com.android.externalstorage/B.apk'));
  await sleep(50);
  s = await state();
  check('6. an "Open with" package does not replace A while it installs', s.inspected === 1 && s.shown === 'com.game.a' && (await toast()) === 'An install is already running', JSON.stringify(s));

  check('7. the file manager\'s Install is refused before it stages anything', (await ev(() => installFilesReady())) === false && (await state()).opened === 0);
  check('   and nothing is marked as coming from the file manager', (await state()).fromFiles === false && (await ev(() => installFilesPending)) === false);

  await ev(() => onInstallResult(JSON.stringify({ ok: true, method: 'shizuku', output: 'Success', extras: '1 of 1 data file copied (100 B) to Android/obb/com.game.a/' })));
  await sleep(50);
  await ev(() => { if (typeof closeCommandResultsModal === 'function') closeCommandResultsModal(); });
  s = await state();
  check('8. A\'s result ends the install', !s.running, JSON.stringify(s));

  await ev(() => onInstallFilePicked('/storage/emulated/0/Download/B.apk'));
  await page.waitForFunction(() => installData && installData.pkg === 'com.app.b');
  s = await state();
  check('9. once the result is in, another package opens as before', s.inspected === 2 && s.shown === 'com.app.b', JSON.stringify(s));

  console.log(bad ? 'FAILED: ' + bad : 'ALL PASS');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
