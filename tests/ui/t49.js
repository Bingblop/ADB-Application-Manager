// v5.8 in-app APK signing UI: Sign… in the file manager, the toolbar button + "edited" banner in the archive browser, the sign sheet.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(() => {
    window.__calls = { open: [], signInfo: [], sign: [], regen: [], install: [], intent: [], edit: [] };
    const T = Date.UTC(2024, 0, 8);
    const FILES = [{ p: 'AndroidManifest.xml', s: 300 }, { p: 'classes.dex', s: 5000 }, { p: 'res/a.xml', s: 40 }];
    window.__info = {
      '/sd/app.apk': { ok: true, op: 'signinfo', path: '/sd/app.apk', name: 'app.apk', editBlock: '', key: { ok: true, exists: false }, pkg: 'com.example.app', label: 'Example <b>App</b>', versionName: '2.1', versionCode: 21, signed: true, signer: 'aa'.repeat(32), installed: true, installedVersion: '2.0', installedSigner: 'bb'.repeat(32), keyMatchesInstalled: false },
    };
    window.__editBlock = '';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      hasAllFilesAccess() { return true; },
      fmList(path) { return JSON.stringify({ path, entries: [{ name: 'app.apk', isDir: false, size: 1000 }] }); },
      loadSetting() { return ''; }, saveSetting() {},
      archiveOpen(path) {
        window.__calls.open.push(path);
        return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: FILES.length, files: FILES.length, size: 5340, zip64: false, staged: false, apk: /\.apk$/i.test(path), editable: !window.__editBlock, whyNot: window.__editBlock });
      },
      archiveList(path, dir, q, off, lim) { return JSON.stringify({ ok: true, dir, query: q, total: FILES.length, offset: off, more: false, entries: FILES.map(f => ({ n: f.p.split('/').pop(), p: f.p, d: false, s: f.s, c: f.s, t: T, m: 8, e: false })) }); },
      archiveRead(path, entry) { return JSON.stringify({ ok: true, name: entry, size: 4, csize: 4, method: 8, crc: 1, mtime: T, kind: 'hex', hex: '' }); },
      archiveEdit(path, op) { window.__calls.edit.push(op); setTimeout(() => window.onArchiveResult({ ok: true, op: 'delete', message: 'Deleted x', apk: true }), 20); return 'started'; },
      archiveClose() {}, archiveRelease() {},
      archiveSignInfo(path) {
        window.__calls.signInfo.push(path);
        const i = window.__info[path] || { ok: false, op: 'signinfo', path, name: path.split('/').pop(), error: 'No such file' };
        const out = JSON.parse(JSON.stringify(i)); out.editBlock = window.__editBlock;
        setTimeout(() => window.onSignInfo(out), 30);
        return 'started';
      },
      archiveSign(path, mode) {
        window.__calls.sign.push([path, mode]);
        if (window.__signBusy) return 'busy';
        setTimeout(() => window.onArchiveProgress && window.onArchiveProgress('Signing…'), 15);
        setTimeout(() => {
          if (window.__signFail) { window.onArchiveSigned({ ok: false, op: 'sign', error: 'Not enough free space in the app cache to sign this (3.0 MB).' }); return; }
          const dest = mode === 'inplace' ? path : path.replace(/\.apk$/, '-signed.apk');
          window.onArchiveSigned({ ok: true, op: 'sign', inPlace: mode === 'inplace', path: dest, name: dest.split('/').pop(), sha256: 'cc'.repeat(32), hardware: true, size: 5400 });
        }, 120);
        return 'started';
      },
      signingKeyRegenerate() { window.__calls.regen.push(1); setTimeout(() => window.onSigningKey({ ok: true, exists: true, sha256: 'dd'.repeat(32), hardware: true, bits: 2048 }), 60); return 'started'; },
      fmInstall(path) { window.__calls.install.push(path); return JSON.stringify({ ok: true, ref: '/cache/stage/' + path.split('/').pop() }); },
      inspectInstallSource(ref) { window.__calls.intent.push(ref); },
      pickInstallerFile() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(350);
  const sleep = ms => page.waitForTimeout(ms);
  const toast = () => page.locator('#toastMsg').innerText();
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  const rowsText = () => page.locator('#signRows').innerText();
  const btnTexts = () => page.locator('#signBtns button').allInnerTexts();
  await page.evaluate(() => switchView('files')); await sleep(150);

  // 1) The file manager sheet for an .apk offers Sign…, other files don't.
  await page.evaluate(() => fmActions('/sd/app.apk', false, 'app.apk')); await sleep(60);
  const fmBtns = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('1. the file manager sheet of an .apk offers Sign…:', fmBtns.some(t => /Sign/.test(t)), JSON.stringify(fmBtns));
  await page.evaluate(() => fmActions('/sd/archive.zip', false, 'archive.zip')); await sleep(60);
  console.log('   a .zip does not:', !(await page.locator('#fmActionBtns button').allInnerTexts()).some(t => /Sign/.test(t)));
  await page.evaluate(() => fmActions('/sd/app.apk', false, 'app.apk')); await sleep(60);
  await page.locator('#fmActionBtns button', { hasText: 'Sign' }).click(); await sleep(250);
  console.log('   tapping it opens the sign sheet and closes the file sheet:', await vis('#signModal.show') && !(await page.evaluate(() => document.getElementById('fmActionModal').classList.contains('show'))));
  const rows1 = await rowsText();
  console.log('   the sheet shows the app, its valid signature, the installed copy with a different signer and the not-yet-made key:',
    /Example <b>App<\/b>/.test(rows1) && /com\.example\.app/.test(rows1) && /Valid/.test(rows1) && /signed by someone else/.test(rows1) && /Made the first time you sign/.test(rows1), JSON.stringify(rows1.slice(0, 300)));
  console.log('   the app label is escaped (no injected element):', await page.evaluate(() => !document.querySelector('#signRows b')));
  console.log('   the sheet asked the bridge for the info of that path:', JSON.stringify(await page.evaluate(() => window.__calls.signInfo)) === '["/sd/app.apk"]');

  // 2) Save a signed copy (no confirm: nothing is replaced).
  let btns = await btnTexts();
  console.log('2. the sheet offers Sign in place and Save a signed copy:', btns.some(t => /Sign in place/.test(t)) && btns.some(t => /signed copy/.test(t)) && !btns.some(t => /Install/.test(t)) && !btns.some(t => /New key/.test(t)), JSON.stringify(btns));
  await page.locator('#signBtns button', { hasText: 'signed copy' }).click(); await sleep(30);
  console.log('   it is busy while working, buttons are disabled and progress is shown:', (await page.locator('#signBtns button[disabled]').count()) >= 2 && /Signing/.test(await page.locator('#signStatus').innerText()));
  await sleep(250);
  const rows2 = await rowsText(); btns = await btnTexts();
  console.log('   the copy was requested: ', JSON.stringify(await page.evaluate(() => window.__calls.sign)) === '[["/sd/app.apk","copy"]]');
  console.log('   done: result row with the new path, key now shown, Install offered:', /Signed app-signed\.apk/.test(rows2) && /\/sd\/app-signed\.apk/.test(rows2) && /secure hardware/.test(rows2) && btns.some(t => /Install/.test(t)) && btns.some(t => /New key/.test(t)), JSON.stringify(rows2.slice(-260)));
  console.log('   no confirm dialog for a copy:', dialogs.length === 0);
  console.log('   the key now matches nothing installed (cc… vs bb…) so the warning stays:', /signed by someone else/.test(rows2));

  // 3) Install the signed copy: staged through fmInstall and handed to the Installer tab.
  await page.locator('#signBtns button', { hasText: 'Install' }).click(); await sleep(200);
  const inst = await page.evaluate(() => ({ install: window.__calls.install.slice(), intent: window.__calls.intent.slice(), tab: currentViewName(), open: document.getElementById('signModal').classList.contains('show') }));
  console.log('3. Install stages the signed copy and opens the Installer:', JSON.stringify(inst.install) === '["/sd/app-signed.apk"]' && inst.tab === 'installer' && !inst.open && inst.intent[0] === '/cache/stage/app-signed.apk', JSON.stringify(inst));

  // 4) Archive browser: toolbar button, edit -> banner -> sign in place.
  await page.evaluate(() => switchView('files')); await sleep(120);
  await page.evaluate(() => arcOpen('/sd/app.apk')); await sleep(300);
  console.log('4. the archive browser of an .apk shows Sign but no banner yet:', await vis('#arcSignBtn') && !(await vis('#arcSignBanner')));
  console.log('   the note on APK editing now points at Sign:', /Sign/.test(await page.locator('#arcNote').innerText()));
  await page.evaluate(() => arcRunEdit({ op: 'delete', name: 'res/a.xml' })); await sleep(200);
  console.log('   after an edit the "signature is no longer valid" banner shows:', await vis('#arcSignBanner'));
  await page.locator('#arcSignBanner button').click(); await sleep(250);
  console.log('   its button opens the sheet for that file:', await vis('#signModal.show') && (await page.locator('#signName').innerText()) === 'app.apk');
  await page.evaluate(() => { window.__calls.sign.length = 0; window.__calls.open.length = 0; });
  dialogs.length = 0;
  await page.locator('#signBtns button', { hasText: 'Sign in place' }).click(); await sleep(20);
  const dlg = dialogs.slice();
  await sleep(300);
  console.log('   signing in place of a valid-signed file asks first (it replaces that signature):', dlg.length === 1 && /already has a valid signature/.test(dlg[0]), JSON.stringify(dlg));
  console.log('   in place was sent to the bridge:', JSON.stringify(await page.evaluate(() => window.__calls.sign)) === '[["/sd/app.apk","inplace"]]');
  console.log('   afterwards the banner is gone and the archive was re-opened:', !(await vis('#arcSignBanner')) && (await page.evaluate(() => window.__calls.open)).includes('/sd/app.apk'));
  console.log('   the sheet now says the signature is valid:', /Valid/.test(await rowsText()));
  await page.evaluate(() => signClose()); await sleep(60);

  // 5) A file that can't be edited in place: Sign in place is disabled, the copy still works.
  await page.evaluate(() => { window.__editBlock = 'Installed and system packages can’t be edited in place. Copy it to storage first.'; signOpen('/sd/app.apk'); }); await sleep(250);
  const inplaceBtn = page.locator('#signBtns button', { hasText: 'Sign in place' });
  console.log('5. Sign in place is disabled when the file can not be written, and says why:', await inplaceBtn.isDisabled() && /can’t be edited in place/.test(await page.locator('#signNote').innerText()));
  console.log('   the copy button stays enabled:', await page.locator('#signBtns button', { hasText: 'signed copy' }).isEnabled());
  await page.evaluate(() => { signClose(); window.__editBlock = ''; });

  // 6) Failure and busy handling.
  await page.evaluate(() => { window.__signFail = true; signOpen('/sd/app.apk'); }); await sleep(250);
  await page.locator('#signBtns button', { hasText: 'signed copy' }).click(); await sleep(300);
  console.log('6. a failed signing is reported and the sheet is usable again:', /Not enough free space/.test(await toast()) && (await page.locator('#signBtns button[disabled]').count()) === 0);
  console.log('   the failure report is on top of the sign sheet (it used to open underneath):', await page.evaluate(() => { const e = document.elementFromPoint(innerWidth / 2, innerHeight / 2); const o = e && e.closest('.modal-overlay.show'); return !!o && o.id === 'commandResultsModal'; }));
  await page.evaluate(() => { window.__signFail = false; closeCommandResultsModal(); });
  await page.evaluate(() => { window.__signBusy = true; });
  await page.locator('#signBtns button', { hasText: 'signed copy' }).click(); await sleep(60);
  console.log('   another archive job running: told so, not stuck busy:', /still running/.test(await toast()) && (await page.locator('#signBtns button[disabled]').count()) === 0);
  await page.evaluate(() => { window.__signBusy = false; });

  // 7) New key: confirm, regenerate, the sheet refetches the info.
  await page.evaluate(() => { window.__info['/sd/app.apk'].key = { ok: true, exists: true, sha256: 'cc'.repeat(32), hardware: true, bits: 2048 }; signClose(); signOpen('/sd/app.apk'); }); await sleep(250);
  dialogs.length = 0;
  await page.evaluate(() => { window.__calls.signInfo.length = 0; });
  await page.locator('#signBtns button', { hasText: 'New key' }).click(); await sleep(300);
  console.log('7. New key asks first, regenerates and refetches the sheet info:', dialogs.length === 1 && /new signing key/i.test(dialogs[0]) && (await page.evaluate(() => window.__calls.regen.length)) === 1 && (await page.evaluate(() => window.__calls.signInfo.length)) === 1, JSON.stringify(dialogs));

  // 8) Smart back closes the sheet.
  const closed = await page.evaluate(() => { const r = handleAndroidBack(); return { r, open: document.getElementById('signModal').classList.contains('show') }; });
  console.log('8. Back closes the sign sheet:', closed.r === true && closed.open === false);

  // 9) Info failure shows the problem; sheet buttons still close cleanly.
  await page.evaluate(() => signOpen('/sd/missing.apk')); await sleep(200);
  console.log('9. an unreadable file shows the problem:', /No such file/.test(await rowsText()));
  await page.evaluate(() => signClose());

  // 10) The sheet closed while signing: late results don't throw.
  await page.evaluate(() => { signOpen('/sd/app.apk'); }); await sleep(200);
  await page.locator('#signBtns button', { hasText: 'signed copy' }).click(); await sleep(20);
  await page.evaluate(() => signClose()); await sleep(300);
  console.log('10. closing the sheet mid-signing is harmless:', errors.length === 0 && !(await page.evaluate(() => signTarget)));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
