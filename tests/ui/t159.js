// Opening a compressed tar (tar.zst / tar.gz / ...) in the File Manager: listing it unpacks all of it, which can take minutes, and the bridge runs one call at a time,
// so the app answers archiveOpen with { ok: true, pending: true, token } at once and sends the real answer to window.onArchiveOpened. The page keeps working, shows the
// "Opening …" line with a Cancel button and the app's progress text, handles the final answer (success, error), drops an answer to an open that was cancelled or replaced,
// and asks the app to stop (archiveOpenCancel) on Cancel, on leaving the File Manager and on closing the archive view. A zip answers in the call, as before.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { open: [], cancel: 0, close: 0, release: [], list: [] };
    window.__tok = 0;
    const info = (path, extra) => Object.assign({ ok: true, path, name: path.split('/').pop(), count: 12, files: 10, size: 4096, zip64: false, format: /\.zip$/.test(path) ? 'zip' : 'tar.zst', solid: !/\.zip$/.test(path), encrypted: false, staged: false, apk: false, editable: false, whyNot: 'x' }, extra || {});
    window.__info = info;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadSetting() { return ''; }, saveSetting() {}, hasAllFilesAccess() { return true; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      fmList(path) {
        return JSON.stringify({ path, entries: [
          { name: 'big.tar.zst', isDir: false, isLink: false, size: 290000, perms: '-rw-' },
          { name: 'small.zip', isDir: false, isLink: false, size: 900, perms: '-rw-' },
        ] });
      },
      archiveOpen(path) {
        window.__calls.open.push(path);
        if (/\.zip$/.test(path)) return JSON.stringify(window.__info(path));                       // a directory is read: answered in the call
        return JSON.stringify({ ok: true, pending: true, token: 'open' + (++window.__tok) });      // a compressed tar: unpacked in the background
      },
      archiveOpenCancel() { window.__calls.cancel++; },
      archiveList(path, dir, q, off, lim) { window.__calls.list.push(path); return JSON.stringify({ ok: true, dir: '', query: '', total: 2, offset: 0, more: false, entries: [{ n: 'a.txt', p: 'a.txt', d: false, s: 5, c: 5, t: 0, m: 8, e: false }, { n: 'b.txt', p: 'b.txt', d: false, s: 7, c: 7, t: 0, m: 8, e: false }] }); },
      archiveClose() { window.__calls.close++; },
      archiveRelease(p) { window.__calls.release.push(p); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  const statusLine = () => page.evaluate(() => { const e = document.getElementById('fmBatchStatus'); return e && getComputedStyle(e).display !== 'none' ? e.innerText.replace(/\s+/g, ' ').trim() : null; });
  const arcStatusLine = () => page.evaluate(() => { const e = document.getElementById('arcStatus'); return e && getComputedStyle(e).display !== 'none' ? e.innerText.replace(/\s+/g, ' ').trim() : null; });
  const calls = () => page.evaluate(() => JSON.stringify({ open: window.__calls.open.length, cancel: window.__calls.cancel, close: window.__calls.close, release: window.__calls.release.length }));
  const view = async name => { await page.evaluate(n => fmActions('/storage/emulated/0/Download/' + n, false, n), name); await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(120); };
  const arcShown = () => vis('#arcCard');
  const token = () => page.evaluate(() => 'open' + window.__tok);
  const answer = (extra, tok) => page.evaluate(([x, t]) => onArchiveOpened(Object.assign(window.__info('/storage/emulated/0/Download/big.tar.zst'), { token: t }, x)), [extra || {}, tok]);
  await page.evaluate(() => switchView('files')); await sleep(200);

  // ---------------------------------------------------------------- 1. a zip answers in the call: nothing changes
  await view('small.zip');
  console.log('1. a zip is answered by archiveOpen itself: the archive view shows at once, no "Opening" line, no cancel asked:', await arcShown(), await statusLine(), await calls());
  await page.evaluate(() => arcClose()); await sleep(60);

  // ---------------------------------------------------------------- 2. a tar.zst: pending
  await view('big.tar.zst');
  console.log('2. a tar.zst is answered "pending": the archive view is not shown yet, the File Manager shows the Opening line with a Cancel button:', await arcShown(), JSON.stringify(await statusLine()), await page.locator('#fmBatchStatus button', { hasText: 'Cancel' }).count());
  console.log('   the page is not frozen: a second open is refused in words, not queued behind it:');
  await page.evaluate(() => { fmActions('/storage/emulated/0/Download/small.zip', false, 'small.zip'); });
  await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(80);
  console.log('   ', JSON.stringify(await toastText()), await calls());

  // ---------------------------------------------------------------- 3. progress
  await page.evaluate(() => onArchiveProgress('Opening big.tar.zst… 1.2 GB read')); await sleep(30);
  console.log('3. the app\'s progress text replaces the line and the Cancel button stays:', JSON.stringify(await statusLine()), await page.locator('#fmBatchStatus button', { hasText: 'Cancel' }).count());

  // ---------------------------------------------------------------- 4. the final answer, success
  await answer({}, await token()); await sleep(100);
  const meta = await page.locator('#arcMeta').innerText();
  console.log('4. the final answer opens the archive: the view shows, the line is gone, the header says it is solid:', await arcShown(), await statusLine(), /TAR\.ZST \(solid/.test(meta), JSON.stringify(meta.split('\n')[0]));
  console.log('   it lists its entries:', (await page.locator('#arcList .perm-row').count()), 'rows, and the list was asked of the app:', await page.evaluate(() => window.__calls.list.length > 0));
  await page.evaluate(() => arcClose()); await sleep(60);

  // ---------------------------------------------------------------- 5. the final answer, an error
  await view('big.tar.zst');
  await answer({ ok: false, error: 'damaged or hostile archive: header of 1610612736 bytes' }, await token()); await sleep(100);
  console.log('5. an error answer shows the app\'s words in a toast, no archive view, no line left:', JSON.stringify(await toastText()), await arcShown(), await statusLine());
  await page.evaluate(() => { if (document.getElementById('fmViewer')) document.getElementById('fmViewer').style.display = 'none'; });

  // ---------------------------------------------------------------- 6. an answer for another open is ignored
  await view('big.tar.zst');
  const t6 = await token();
  await answer({}, 'open999'); await sleep(80);
  console.log('6. an answer carrying another token is ignored (still waiting, still the line):', await arcShown(), JSON.stringify(await statusLine()));
  await answer({}, t6); await sleep(100);
  console.log('   the right token opens it:', await arcShown());
  await page.evaluate(() => arcClose()); await sleep(60);

  // ---------------------------------------------------------------- 7. Cancel
  await view('big.tar.zst');
  const t7 = await token();
  const c0 = JSON.parse(await calls()).cancel;
  await page.locator('#fmBatchStatus button', { hasText: 'Cancel' }).click(); await sleep(60);
  console.log('7. Cancel asks the app to stop (archiveOpenCancel once) and frees the page at once:', JSON.parse(await calls()).cancel - c0, await statusLine(), await page.evaluate(() => arcBusy));
  const toast7 = await toastText();
  await answer({ ok: false, error: 'Cancelled', cancelled: true }, t7); await sleep(60);
  console.log('   the app\'s "cancelled" answer shows nothing (no error toast, no archive, no fallback viewer):', await toastText() === toast7, await arcShown(), await vis('#fmViewer'));
  await answer({}, t7); await sleep(60);
  console.log('   even a success that was already on its way for the cancelled open is dropped:', await arcShown());
  // a new open after Cancel works, and is its own
  await view('big.tar.zst');
  console.log('   opening again right after a Cancel starts a new open with a new token:', await page.evaluate(() => window.__tok), JSON.stringify(await statusLine()));
  await answer({}, await token()); await sleep(100);
  console.log('   ... and it opens:', await arcShown());
  await page.evaluate(() => arcClose()); await sleep(60);

  // ---------------------------------------------------------------- 8. leaving the File Manager
  await view('big.tar.zst');
  const c1 = JSON.parse(await calls()).cancel;
  await page.evaluate(() => switchView('apps')); await sleep(80);
  console.log('8. leaving the File Manager asks the app to stop and clears the line:', JSON.parse(await calls()).cancel - c1, await statusLine(), await page.evaluate(() => arcBusy));
  await page.evaluate(() => switchView('files')); await sleep(80);
  console.log('   back in the File Manager nothing is waiting any more:', await statusLine());

  // ---------------------------------------------------------------- 9. re-opening after an edit (inside the open archive)
  await view('small.zip');
  await page.evaluate(() => { window.__info = (path, extra) => Object.assign({ ok: true, path, name: path.split('/').pop(), count: 13, files: 11, size: 4100, zip64: false, format: 'tar.zst', solid: true, encrypted: false, staged: false, apk: false, editable: true, whyNot: '' }, extra || {}); });
  await page.evaluate(() => { arc.path = '/storage/emulated/0/Download/big.tar.zst'; arc.name = 'big.tar.zst'; arcAfterEdit(); }); await sleep(80);
  console.log('9. after an edit the archive is opened again; if that is pending, the line (with Cancel) shows inside the archive view and the old listing stays:', JSON.stringify(await arcStatusLine()), await arcShown(), await page.locator('#arcStatus button', { hasText: 'Cancel' }).count());
  await answer({}, await token()); await sleep(100);
  console.log('   the answer refreshes the header (11 files) and the line goes:', await arcStatusLine(), /11 files/.test(await page.locator('#arcMeta').innerText()));

  // ---------------------------------------------------------------- 10. closing the archive view while it is waiting
  await page.evaluate(() => { arcAfterEdit(); }); await sleep(60);
  const c2 = JSON.parse(await calls()).cancel;
  await page.evaluate(() => arcClose()); await sleep(60);
  console.log('10. closing the archive view while a re-open waits asks the app to stop:', JSON.parse(await calls()).cancel - c2, await arcShown(), await arcStatusLine());

  console.log(errors.length ? 'FAIL page errors: ' + errors.join(' | ') : 'no page errors');
  await page.close(); await b.close();
})();
