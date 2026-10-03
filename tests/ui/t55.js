// v5.8 installs from the file manager: always privileged (ADB / Wireless Debugging / Shizuku / Root), the app's own signature gates off,
// no silent fallback to the system installer, a signed-by-someone-else app is replaced only after asking.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(() => {
    window.__calls = { stage: [], inspect: [], install: [], archiveStage: [] };
    window.__mode = 'shizuku';
    window.__pkg = { signed: true, sigUnreadable: false, installed: false, signerMatchesInstalled: false };
    window.__result = { ok: true, method: 'shizuku', output: 'Success' };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() {
        const m = window.__mode;
        return JSON.stringify({
          activeMode: m === 'none' ? 'unprivileged' : m, modeAvailable: m !== 'none', isPrivileged: m !== 'none', configuredMode: 'auto',
          adbTcp: { connected: m === 'adb_tcp', port: 5555 }, adbWireless: { connected: m === 'adb_wireless', port: 37099 },
          shizuku: { installed: true, running: m === 'shizuku' || window.__shizukuReady === true, authorized: m === 'shizuku' || window.__shizukuReady === true },
          rootAvailable: m === 'root' || window.__rootReady === true,
        });
      },
      hasAllFilesAccess() { return true; }, loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {},
      fmList(path) { return JSON.stringify({ path, entries: [{ name: 'app.apk', isDir: false, size: 1000 }] }); },
      fmInstall(path) { window.__calls.stage.push(path); return JSON.stringify({ ok: true, ref: '/cache/stage/' + path.split('/').pop() }); },
      inspectInstallSource(ref) {
        window.__calls.inspect.push(ref);
        const p = window.__pkg;
        setTimeout(() => window.onInstallInspected(JSON.stringify({
          ref, type: 'apk', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 20, minSdk: 26, targetSdk: 34,
          splits: [{ path: '/cache/installer/base.apk', name: 'base.apk', size: 1000, isBase: true, split: '' }], totalSize: 1000, extras: [], extrasTotal: 0,
          signed: p.signed, sigUnreadable: p.sigUnreadable, installed: p.installed, installedVersionName: '1.0', installedVersionCode: 10, signerMatchesInstalled: p.signerMatchesInstalled,
        })), 25);
      },
      pickInstallerFile() {},
      installSelected(json) {
        window.__calls.install.push(JSON.parse(json));
        const r = window.__results && window.__results.length ? window.__results.shift() : window.__result;
        setTimeout(() => window.onInstallResult(JSON.stringify(r)), 30);
      },
      archiveStage(src, entry, kind) { window.__calls.archiveStage.push([src, entry, kind]); return 'started'; },
      openUrl() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(450);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  const closeResults = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const fromFm = async (path = '/sd/app.apk') => {
    await ev(p => { switchView('files'); fmActions(p, false, p.split('/').pop()); }, path); await sleep(80);
    await page.locator('#fmActionBtns button', { hasText: 'Install' }).click(); await sleep(250);
  };
  const lastOpts = () => ev(() => window.__calls.install[window.__calls.install.length - 1]);

  // 1) Shizuku active: the Installer opens locked to Shizuku, signature gates off, a note says so
  await fromFm();
  const s1 = await ev(() => ({
    tab: currentViewName(), auth: document.getElementById('optAuthorizer').value,
    noneDisabled: document.querySelector('#optAuthorizer option[value="none"]').disabled,
    mismatch: document.getElementById('optBlockMismatch').checked, unknown: document.getElementById('optBlockUnknown').checked,
    note: document.getElementById('installFilesNote').innerText, noteShown: getComputedStyle(document.getElementById('installFilesNote')).display !== 'none',
    flag: installFromFiles, staged: window.__calls.stage.slice(), inspected: window.__calls.inspect.slice(),
  }));
  console.log('1. a file-manager install opens the Installer on that file:', s1.tab === 'installer' && JSON.stringify(s1.staged) === '["/sd/app.apk"]' && s1.inspected[0] === '/cache/stage/app.apk', JSON.stringify(s1.inspected));
  console.log('   the authorizer is Shizuku and "No privilege" can not be chosen:', s1.auth === 'shizuku' && s1.noneDisabled === true);
  console.log('   both of this app\'s signature gates are off:', s1.mismatch === false && s1.unknown === false);
  console.log('   a note names the backend and that Android still verifies:', s1.noteShown && /Shizuku/.test(s1.note) && /Android still verifies/.test(s1.note), JSON.stringify(s1.note.slice(0, 120)));
  await page.locator('#installOptionsCard button, #installOptionsCard .mode-action-btn', { hasText: /Install$/ }).first().isVisible().catch(() => false);
  await ev(() => runInstall()); await sleep(200);
  const o1 = await lastOpts();
  console.log('   the install is sent as from Files with the privileged authorizer and the gates off:', o1.fromFiles === true && o1.authorizer === 'shizuku' && o1.blockMismatch === false && o1.blockUnknown === false && o1.uninstallFirst === false, JSON.stringify({ f: o1.fromFiles, a: o1.authorizer, bm: o1.blockMismatch, bu: o1.blockUnknown, u: o1.uninstallFirst }));
  await closeResults();

  // 2) ADB (TCP or Wireless Debugging) maps to the adb authorizer; root to root
  for (const [mode, auth, label] of [['adb_tcp', 'adb', /ADB/], ['adb_wireless', 'adb', /Wireless Debugging/], ['root', 'root', /Root/]]) {
    await ev(m => { window.__mode = m; checkAllWorkingModes(false); }, mode);
    await fromFm();
    const r = await ev(() => ({ auth: document.getElementById('optAuthorizer').value, note: document.getElementById('installFilesNote').innerText }));
    console.log('2. ' + mode + ' -> authorizer ' + auth + ', named in the note:', r.auth === auth && label.test(r.note), JSON.stringify(r));
    await closeResults();
  }

  // 3) A privileged backend that is ready but not the active mode is still used (Shizuku running while the mode is read-only)
  await ev(() => { window.__mode = 'none'; window.__shizukuReady = true; checkAllWorkingModes(false); });
  await fromFm();
  console.log('3. no active mode, but Shizuku is running: it is used:', (await ev(() => document.getElementById('optAuthorizer').value)) === 'shizuku');
  await closeResults();

  // 4) Nothing privileged at all: nothing is copied, the user is told and offered Working Modes; no fallback to the system installer
  await ev(() => { window.__mode = 'none'; window.__shizukuReady = false; window.__rootReady = false; checkAllWorkingModes(false); window.__calls.stage.length = 0; window.__calls.inspect.length = 0; });
  await closeResults();
  dialogs.length = 0; answer = false;
  await ev(() => { switchView('files'); fmActions('/sd/app.apk', false, 'app.apk'); }); await sleep(80);
  await page.locator('#fmActionBtns button', { hasText: 'Install' }).click(); await sleep(200);
  const s4 = await ev(() => ({ staged: window.__calls.stage.length, inspected: window.__calls.inspect.length, tab: currentViewName(), modes: document.getElementById('modesModal').classList.contains('show') }));
  console.log('4. with no ADB / Wireless Debugging / Shizuku nothing is staged and no installer opens:', s4.staged === 0 && s4.inspected === 0 && s4.tab === 'files', JSON.stringify(s4));
  console.log('   the user is asked whether to open Working Modes (declined: it stays closed):', dialogs.length === 1 && /ADB, Wireless Debugging, Shizuku or Root/.test(dialogs[0]) && s4.modes === false, JSON.stringify(dialogs));
  await closeResults(); dialogs.length = 0; answer = true;
  await ev(() => { switchView('files'); fmActions('/sd/app.apk', false, 'app.apk'); }); await sleep(80);
  await page.locator('#fmActionBtns button', { hasText: 'Install' }).click(); await sleep(200);
  console.log('   accepted: Working Modes opens:', await ev(() => document.getElementById('modesModal').classList.contains('show')));
  await closeResults();

  // 5) The same for an APK inside an archive and for a freshly signed copy
  await ev(() => { window.__calls.archiveStage.length = 0; arc = { path: '/sd/x.zip', name: 'x.zip', editable: false }; arcTarget = { name: 'inner.apk', path: 'inner.apk', dir: false }; arcBusy = false; arcSrcPath = () => '/sd/x.zip'; });
  dialogs.length = 0; answer = false;
  await ev(() => arcStageEntry('install')); await sleep(100);
  console.log('5. "Install" on an APK inside an archive copies nothing out when there is no privileged backend:', (await ev(() => window.__calls.archiveStage.length)) === 0 && dialogs.length === 1);
  await ev(() => { window.__mode = 'shizuku'; checkAllWorkingModes(false); }); answer = true; dialogs.length = 0;
  await ev(() => arcStageEntry('install')); await sleep(100);
  console.log('   with Shizuku it is copied out (kind install):', JSON.stringify(await ev(() => window.__calls.archiveStage)) === '[["/sd/x.zip","inner.apk","install"]]');
  await ev(() => { arcSetBusy(false); window.__calls.inspect.length = 0; onArchiveStaged({ ok: true, kind: 'install', ref: '/cache/stage/inner.apk' }); }); await sleep(150);
  const s5 = await ev(() => ({ tab: currentViewName(), flag: installFromFiles, auth: document.getElementById('optAuthorizer').value, inspected: window.__calls.inspect.slice() }));
  console.log('   the staged APK opens the Installer in Files mode:', s5.tab === 'installer' && s5.flag === true && s5.auth === 'shizuku' && s5.inspected[0] === '/cache/stage/inner.apk', JSON.stringify(s5));
  await closeResults();

  // 6) Any other way of loading a package leaves Files mode: all authorizers back, the gates back on, no note
  await ev(() => { window.onInstallFilePicked('/sd/other.apk'); }); await sleep(120);
  const s6 = await ev(() => ({
    flag: installFromFiles, noneDisabled: document.querySelector('#optAuthorizer option[value="none"]').disabled,
    mismatch: document.getElementById('optBlockMismatch').checked, unknown: document.getElementById('optBlockUnknown').checked,
    noteShown: getComputedStyle(document.getElementById('installFilesNote')).display !== 'none',
  }));
  console.log('6. a package from the normal picker is a normal install again:', s6.flag === false && s6.noneDisabled === false && s6.mismatch === true && s6.unknown === true && s6.noteShown === false, JSON.stringify(s6));
  await ev(() => runInstall()); await sleep(150);
  const o6 = await lastOpts();
  console.log('   and it is sent without the Files flag:', o6.fromFiles === false);
  await closeResults();

  // 7) An edited APK (its signature can't be verified) is shown as such, and can still be installed from Files
  await ev(() => { window.__pkg = { signed: false, sigUnreadable: true, installed: false, signerMatchesInstalled: false }; });
  await fromFm();
  const sign7 = await ev(() => document.getElementById('installSignRow').innerText);
  console.log('7. the signature row explains it and what happens from Files:', /can't verify this APK's signature/.test(sign7) && /signed with this app's key/.test(sign7), JSON.stringify(sign7));
  await ev(() => runInstall()); await sleep(150);
  console.log('   Install goes ahead (no gate in the way):', (await ev(() => window.__calls.install.length)) >= 3);
  await closeResults();
  await ev(() => { window.onInstallFilePicked('/sd/edited.apk'); }); await sleep(120);
  const sign7b = await ev(() => document.getElementById('installSignRow').innerText);
  console.log('   in a normal install the same row points at the gate instead:', /can't verify this APK's signature/.test(sign7b) && !/signed with this app's key/.test(sign7b), JSON.stringify(sign7b));
  await closeResults();

  // 8) Installed copy signed by someone else: asked before anything, decline stops, accept sends uninstallFirst
  await ev(() => { window.__pkg = { signed: true, sigUnreadable: false, installed: true, signerMatchesInstalled: false }; window.__calls.install.length = 0; });
  await fromFm();
  dialogs.length = 0; answer = false;
  await ev(() => runInstall()); await sleep(120);
  console.log('8. a differently signed installed copy: asks before trying, says the data is deleted (for every user):', dialogs.length === 1 && /different signing key/.test(dialogs[0]) && /data are deleted/.test(dialogs[0]), JSON.stringify(dialogs));
  console.log('   declined: nothing is sent:', (await ev(() => window.__calls.install.length)) === 0);
  answer = true; dialogs.length = 0;
  await ev(() => runInstall()); await sleep(200);
  const o8 = await lastOpts();
  console.log('   accepted: sent with uninstallFirst:', o8 && o8.uninstallFirst === true && o8.fromFiles === true, JSON.stringify(o8 && o8.uninstallFirst));
  await closeResults();

  // 9) Found out only after trying (the signature was unreadable beforehand): the result asks, then runs again with uninstallFirst
  await ev(() => { window.__pkg = { signed: false, sigUnreadable: true, installed: true, signerMatchesInstalled: false }; window.__calls.install.length = 0;
    window.__results = [{ ok: false, retry: 'uninstall', method: 'shizuku', pkg: 'com.example.app', output: 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]' }, { ok: true, method: 'shizuku', output: 'The installed copy was uninstalled first.\nSuccess' }]; });
  await fromFm();
  dialogs.length = 0; answer = true;
  await ev(() => runInstall()); await sleep(400);
  const calls9 = await ev(() => window.__calls.install.map(o => o.uninstallFirst));
  console.log('9. a "different key" answer from Android asks and then installs again after uninstalling:', JSON.stringify(calls9) === '[false,true]' && dialogs.length === 1 && /different signing key/.test(dialogs[0]), JSON.stringify({ calls9, dialogs }));
  console.log('   the final result sheet is the success one:', /Installed/.test(await page.locator('#toastMsg').innerText()) || (await vis('#commandResultsModal.show')));
  await closeResults();
  // declined
  await ev(() => { window.__calls.install.length = 0; window.__results = [{ ok: false, retry: 'uninstall', method: 'shizuku', pkg: 'com.example.app', output: 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' }]; });
  await fromFm();
  dialogs.length = 0; answer = false;
  await ev(() => runInstall()); await sleep(400);
  console.log('   declined: no second attempt, and the failure is shown:', (await ev(() => window.__calls.install.length)) === 1 && /INSTALL_FAILED_UPDATE_INCOMPATIBLE/.test(await page.locator('#commandResultsModal').innerText().catch(() => '')) );
  await closeResults(); answer = true;

  // 10) Back asks before leaving while an install runs
  await ev(() => { window.__pkg = { signed: true, sigUnreadable: false, installed: false, signerMatchesInstalled: false }; window.__results = []; window.__result = { ok: true, method: 'shizuku', output: 'Success' }; });
  await fromFm();
  await ev(() => { window.AndroidBridge.installSelected = function (j) { window.__calls.install.push(JSON.parse(j)); }; });          // never answers
  await ev(() => runInstall()); await sleep(50);
  console.log('10. while an install is running Back says so:', (await ev(() => backBusyReason())) === 'an install is running');
  await ev(() => onInstallResult(JSON.stringify({ ok: true, output: 'Success' }))); await sleep(50);
  console.log('    when it ends Back is free again:', (await ev(() => backBusyReason())) === '');
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
