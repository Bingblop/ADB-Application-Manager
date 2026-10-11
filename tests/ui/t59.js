// v5.8 review round 2 (page side): a second tap while an install runs is refused, and the "uninstall first?" question names the package.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(() => {
    window.__calls = { install: [] };
    window.__pkg = { signed: true, sigUnreadable: false, installed: true, signerMatchesInstalled: false };
    window.__answerInstall = true;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() {
        return JSON.stringify({
          activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, configuredMode: 'auto',
          adbTcp: { connected: true, port: 5555 }, adbWireless: { connected: false, port: 0 },
          shizuku: { installed: false, running: false, authorized: false }, rootAvailable: false,
        });
      },
      hasAllFilesAccess() { return true; }, loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {},
      fmList(path) { return JSON.stringify({ path, entries: [{ name: 'app.apk', isDir: false, size: 1000 }] }); },
      fmInstall(path) { return JSON.stringify({ ok: true, ref: '/cache/stage/' + path.split('/').pop() }); },
      inspectInstallSource(ref) {
        const p = window.__pkg;
        setTimeout(() => window.onInstallInspected(JSON.stringify({
          ref, type: 'apk', pkg: window.__pkgName || 'com.example.app', label: window.__label || 'Example App', versionName: '2.0', versionCode: 20, minSdk: 26, targetSdk: 34,
          splits: [{ path: '/cache/installer/base.apk', name: 'base.apk', size: 1000, isBase: true, split: '' }], totalSize: 1000, extras: [], extrasTotal: 0,
          signed: p.signed, sigUnreadable: p.sigUnreadable, installed: p.installed, installedVersionName: '1.0', installedVersionCode: 10, signerMatchesInstalled: p.signerMatchesInstalled,
        })), 25);
      },
      pickInstallerFile() {},
      installSelected(json) {
        window.__calls.install.push(JSON.parse(json));
        if (window.__answerInstall) setTimeout(() => window.onInstallResult(JSON.stringify({ ok: true, method: 'adb_tcp', output: 'Success' })), 30);
      },
      openUrl() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(450);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const closeResults = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const fromFm = async (path = '/sd/app.apk') => {
    await ev(p => { switchView('files'); fmActions(p, false, p.split('/').pop()); }, path); await sleep(80);
    await page.locator('#fmActionBtns button', { hasText: 'Install' }).click(); await sleep(250);
  };

  // 1) The question names the app and its package, and still says the data goes
  await fromFm();
  answer = false; dialogs.length = 0;
  await ev(() => runInstall()); await sleep(100);
  console.log('1. the "uninstall first?" question names the app and its package:', dialogs.length === 1 && /Example App \(com\.example\.app\)/.test(dialogs[0]) && /data are deleted/.test(dialogs[0]), JSON.stringify(dialogs[0] && dialogs[0].slice(0, 90)));
  console.log('   declined: nothing was sent and the page is not left "running":', (await ev(() => window.__calls.install.length)) === 0 && (await ev(() => installRunning)) === false);

  // a label that is the package id itself is not repeated
  await ev(() => { window.__label = 'com.example.app'; });
  await closeResults(); await fromFm();
  dialogs.length = 0;
  await ev(() => runInstall()); await sleep(100);
  console.log('   a label equal to the package id is not written twice:', dialogs.length === 1 && !/com\.example\.app \(com\.example\.app\)/.test(dialogs[0]) && /^com\.example\.app is installed/.test(dialogs[0]), JSON.stringify(dialogs[0] && dialogs[0].slice(0, 60)));
  await ev(() => { window.__label = ''; });
  await closeResults();

  // 2) A second tap while the first install is still running is ignored
  await ev(() => { window.__pkg = { signed: true, sigUnreadable: false, installed: false, signerMatchesInstalled: false }; window.__calls.install.length = 0; window.__answerInstall = false; });
  await fromFm();
  answer = true; dialogs.length = 0;
  await ev(() => { runInstall(); runInstall(); runInstall(); }); await sleep(100);
  console.log('2. three quick taps send the install once:', (await ev(() => window.__calls.install.length)) === 1, String(await ev(() => window.__calls.install.length)));
  const toast = await page.locator('#toastMsg').innerText().catch(() => '');
  console.log('   and the second tap says one is already running:', /already running/.test(toast), JSON.stringify(toast));
  console.log('   Back is told an install is running:', (await ev(() => backBusyReason())) === 'an install is running');
  // it ends: a new install can start again
  await ev(() => onInstallResult(JSON.stringify({ ok: true, output: 'Success' }))); await sleep(50);
  await closeResults();
  await fromFm();
  await ev(() => runInstall()); await sleep(80);
  console.log('3. once the first one is over the next install is sent:', (await ev(() => window.__calls.install.length)) === 2, String(await ev(() => window.__calls.install.length)));

  // 4) The retry question (found out only after trying) names the package too
  await ev(() => onInstallResult(JSON.stringify({ ok: true, output: 'Success' }))); await sleep(50);
  await closeResults();
  await ev(() => { window.__pkg = { signed: false, sigUnreadable: true, installed: true, signerMatchesInstalled: false }; window.__calls.install.length = 0; window.__answerInstall = false; });
  await fromFm();
  dialogs.length = 0; answer = false;
  await ev(() => runInstall()); await sleep(60);
  await ev(() => onInstallResult(JSON.stringify({ ok: false, retry: 'uninstall', method: 'adb_tcp', pkg: 'com.example.app', output: 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' }))); await sleep(120);
  console.log('4. the retry question names the app and its package:', dialogs.length === 1 && /Example App \(com\.example\.app\)/.test(dialogs[0]), JSON.stringify(dialogs[0] && dialogs[0].slice(0, 70)));
  console.log('   declined: only the first attempt was sent and a new install may start:', (await ev(() => window.__calls.install.length)) === 1 && (await ev(() => installRunning)) === false);

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
