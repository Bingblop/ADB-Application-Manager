// v7.4 Archive formats: the password dialog (asked when opening, browsing or extracting a locked archive) and the Compress dialog
// (zip / 7z / tar family / a single compressed file, an optional password, where to save, what to do if the name is taken).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { open: [], open2: [], setPw: [], create: [], fmList: [] };
    window.__arc = { needPwToOpen: false, realPw: 'pw123' };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadSetting() { return ''; }, saveSetting() {}, hasAllFilesAccess() { return true; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      fmList(path) {
        window.__calls.fmList.push(path);
        return JSON.stringify({ path, entries: [
          { name: 'a.txt', isDir: false, isLink: false, size: 10, perms: '-rw-' },
          { name: 'b.txt', isDir: false, isLink: false, size: 20, perms: '-rw-' },
          { name: 'locked.7z', isDir: false, isLink: false, size: 500, perms: '-rw-' },
        ] });
      },
      archiveOpen(path) {
        window.__calls.open.push(path);
        if (window.__arc.needPwToOpen && /locked\.7z$/.test(path)) return JSON.stringify({ ok: false, error: 'This archive needs a password to open', needPassword: true, wrong: false });
        return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: 1, files: 1, size: 500, zip64: false, format: '7z', solid: false, encrypted: false, staged: false, apk: false, editable: false, whyNot: 'View only: editing 7z is not available in this build.' });
      },
      archiveOpen2(path, password) {
        window.__calls.open2.push([path, password]);
        if (password !== window.__arc.realPw) return JSON.stringify({ ok: false, error: 'That password did not work', needPassword: true, wrong: true });
        return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: 1, files: 1, size: 500, zip64: false, format: '7z', solid: true, encrypted: true, staged: false, apk: false, editable: false, whyNot: 'View only: editing 7z is not available in this build.' });
      },
      archiveSetPassword(path, password) {
        window.__calls.setPw.push([path, password]);
        if (password !== window.__arc.realPw) return JSON.stringify({ ok: false, error: 'That password did not work', needPassword: true, wrong: true });
        return JSON.stringify({ ok: true });
      },
      archiveList() { return JSON.stringify({ ok: true, dir: '', query: '', total: 0, offset: 0, more: false, entries: [] }); },
      archiveClose() {},
      archiveCreate(optsJson, itemsJson) {
        window.__calls.create.push([JSON.parse(optsJson), JSON.parse(itemsJson)]);
        return window.__busy ? 'busy' : 'started';
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  // .modal-overlay always has display:flex; .show toggles opacity/pointer-events only, so open/closed is a classList check.
  const modalOpen = sel => page.evaluate(s => document.querySelector(s).classList.contains('show'), sel);
  // a plain row (czPwRow, czEncRow, czLevelRow, pwWrong) is toggled by its own inline style.display, no 'show' class involved.
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  await page.evaluate(() => switchView('files')); await sleep(200);

  // ---------------------------------------------------------------- the password dialog, opening an archive
  await page.evaluate(() => { window.__arc.needPwToOpen = true; });
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/locked.7z', false, 'locked.7z'));
  await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(80);
  console.log('1. an archive that needs a password to open shows the password dialog, not an error toast:', await modalOpen('#pwModal'));
  console.log('   it names the archive, and does not say "wrong" the first time:', /locked\.7z/.test(await page.locator('#pwName').innerText()) && !(await vis('#pwWrong')));
  await page.fill('#pwInput', 'nope'); await page.click('#pwOk'); await sleep(80);
  console.log('   the wrong password is tried through archiveOpen2 and shown as wrong, the dialog stays open:', (await page.evaluate(() => window.__calls.open2.slice(-1)[0][1])) === 'nope' && (await vis('#pwWrong')) && (await modalOpen('#pwModal')));
  await page.fill('#pwInput', 'pw123'); await page.click('#pwOk'); await sleep(120);
  const afterOpen = await page.evaluate(() => ({ pwOpen: document.getElementById('pwModal').classList.contains('show'), card: getComputedStyle(document.getElementById('arcCard')).display, enc: document.getElementById('arcMeta').innerText }));
  console.log('   the right password opens the archive (dialog closes, browser shows, the header says it is protected):', !afterOpen.pwOpen && afterOpen.card !== 'none' && /password-protected/.test(afterOpen.enc), JSON.stringify(afterOpen));

  // ---------------------------------------------------------------- the password dialog, a password asked for mid-browse (one entry)
  await page.evaluate(() => {
    arcTarget = { path: 'secret.txt', dir: false, name: 'secret.txt' };
    let unlocked = false;
    window.AndroidBridge.archiveRead = (p, e) => unlocked
      ? JSON.stringify({ ok: true, name: e, size: 2, csize: 2, method: 0, crc: 0, mtime: 0, kind: 'text', text: 'hi', truncated: false, crlf: false, editable: false })
      : JSON.stringify({ ok: false, error: 'This entry needs a password', needPassword: true, wrong: false });
    const origSetPw = window.AndroidBridge.archiveSetPassword;
    window.AndroidBridge.archiveSetPassword = (p, pw) => { const r = origSetPw(p, pw); if (JSON.parse(r).ok) unlocked = true; return r; };
    arcViewEntry();
  });
  await sleep(80);
  console.log('2. a single entry that needs a password (data-only encryption) also asks, without closing the archive:', await modalOpen('#pwModal'));
  await page.fill('#pwInput', 'pw123'); await page.click('#pwOk'); await sleep(80);
  console.log('   it is set through archiveSetPassword, with the password just given:', (await page.evaluate(() => window.__calls.setPw.slice(-1)[0][1])) === 'pw123');
  console.log('   the entry is then shown and the dialog closes:', !(await modalOpen('#pwModal')) && (await vis('#arcView')));

  await page.evaluate(() => arcClose());

  // ---------------------------------------------------------------- the Compress dialog: a single file
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/a.txt', false, 'a.txt'));
  const btns3 = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('3. a file\'s sheet offers "Compress…":', btns3.some(t => /Compress…/.test(t)));
  await page.locator('#fmActionBtns button', { hasText: 'Compress…' }).click(); await sleep(60);
  console.log('   it opens the Compress dialog, named after the file, saving into the same folder by default:', (await modalOpen('#czModal')) && (await page.locator('#czName').inputValue()) === 'a' && (await page.locator('#czHerePath').innerText()) === '/storage/emulated/0/Download');
  const singleState = await page.evaluate(() => ({ format: document.getElementById('czFormat').value, singlesEnabled: !document.getElementById('czSingles').children[0].disabled, pwShown: getComputedStyle(document.getElementById('czPwRow')).display !== 'none' }));
  console.log('   one file: Zip is the default, the one-file-only formats are selectable, and a password row is offered:', singleState.format === 'zip' && singleState.singlesEnabled && singleState.pwShown, JSON.stringify(singleState));
  await page.selectOption('#czFormat', 'xz'); await sleep(30);
  const xzState = await page.evaluate(() => ({ levelShown: getComputedStyle(document.getElementById('czLevelRow')).display !== 'none', pwHidden: getComputedStyle(document.getElementById('czPwRow')).display === 'none' }));
  console.log('   a single-file format (xz) still offers a packing level (xz has one) but hides the password (it has none):', xzState.levelShown && xzState.pwHidden, JSON.stringify(xzState));
  await page.selectOption('#czFormat', 'tar'); await sleep(30);
  console.log('   plain tar (no packing at all) hides the level row:', await page.evaluate(() => getComputedStyle(document.getElementById('czLevelRow')).display === 'none'));
  await page.selectOption('#czFormat', 'zip');
  await page.click('#czGoBtn'); await sleep(80);
  const c1 = await page.evaluate(() => window.__calls.create.slice(-1)[0]);
  console.log('   leaving the password empty compresses without one (archiveCreate gets an empty password):', c1[0].format === 'zip' && c1[0].password === '' && c1[0].name === 'a.zip' && c1[0].dir === '/storage/emulated/0/Download' && c1[1].length === 1 && c1[1][0].p === '/storage/emulated/0/Download/a.txt', JSON.stringify(c1));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'create', files: 1, bytes: 10, csize: 8, dest: '/storage/emulated/0/Download/a.zip', encrypted: false })); await sleep(60);
  console.log('   the toast says what was made and that it is not protected:', /a\.zip/.test(await toastText()) && !/password/.test(await toastText()), await toastText());

  // ---------------------------------------------------------------- the Compress dialog: several files, with a password
  await page.evaluate(() => { window.__calls.create = []; fmSelMode = true; fmSel = new Set(['/storage/emulated/0/Download/a.txt', '/storage/emulated/0/Download/b.txt']); fmRows = [{ name: 'a.txt', full: '/storage/emulated/0/Download/a.txt', isDir: false }, { name: 'b.txt', full: '/storage/emulated/0/Download/b.txt', isDir: false }]; fmSelRender(); });
  await sleep(30);
  await page.click('#fmSelBar button:has-text("Compress")'); await sleep(60);
  const multiState = await page.evaluate(() => ({ shown: document.getElementById('czModal').classList.contains('show'), singlesDisabled: document.getElementById('czSingles').children[0].disabled }));
  console.log('4. compressing a selection of several files disables the one-file-only formats:', multiState.shown && multiState.singlesDisabled);
  await page.fill('#czPw', 'sekret');
  await page.selectOption('#czEnc', 'zipcrypto');
  await page.click('#czGoBtn'); await sleep(60);
  const c2 = await page.evaluate(() => window.__calls.create.slice(-1)[0]);
  console.log('   a password is sent through with the chosen protection, for every picked file:', c2[0].password === 'sekret' && c2[0].enc === 'zipcrypto' && c2[1].length === 2, JSON.stringify(c2));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'create', files: 2, bytes: 30, csize: 25, dest: '/storage/emulated/0/Download/Download.zip', encrypted: true })); await sleep(60);
  console.log('   the toast says it is password-protected, and the selection is left (Select mode closed):', /password-protected/.test(await toastText()) && !(await page.evaluate(() => fmSelMode)));

  // ---------------------------------------------------------------- a 7z password uses AES-256 and hides names; tar has no password row at all
  await page.evaluate(() => { fmActions('/storage/emulated/0/Download/a.txt', false, 'a.txt'); });
  await page.locator('#fmActionBtns button', { hasText: 'Compress…' }).click(); await sleep(50);
  await page.selectOption('#czFormat', '7z'); await sleep(30);
  console.log('5. a 7z note explains it hides the file names too, and there is no separate protection choice (7z is always AES-256):', /hides the file names/.test(await page.locator('#czPwNote').innerText()) && !(await vis('#czEncRow')));
  await page.selectOption('#czFormat', 'tar'); await sleep(30);
  console.log('   tar has no password row (tar itself has no encryption):', !(await vis('#czPwRow')));
  await page.click('#czModal [aria-label="Close"]');

  console.log(errors.length ? 'FAIL page errors: ' + errors.join(' | ') : 'no page errors');
  await page.close(); await b.close();
})();
