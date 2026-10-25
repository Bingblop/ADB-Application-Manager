// "View contents" of an archive is refused while a File Manager extraction, compression or file job runs (PG4-2), the way a second extraction
// or compression is: opening a compressed tar waits in the same status line with its own Cancel button, so it used to take over the running
// job's line (the job's progress then went to the open's line and its Cancel cancelled the open), and the job's end hid the line of the open.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { open: [], cancel: 0, extract: 0, create: 0 };
    window.__tok = 0;
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
        return JSON.stringify({ ok: true, pending: true, token: 'open' + (++window.__tok) });      // a compressed tar: unpacked in the background
      },
      archiveOpenCancel() { window.__calls.cancel++; },
      archiveExtract2() { window.__calls.extract++; return 'started'; },
      archiveCreate() { window.__calls.create++; return 'started'; },
      archiveCancel() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  const statusLine = () => page.evaluate(() => { const e = document.getElementById('fmBatchStatus'); return e && getComputedStyle(e).display !== 'none' ? e.innerText.replace(/\s+/g, ' ').trim() : null; });
  const cancelOf = () => page.evaluate(() => { const b = document.querySelector('#fmBatchStatus button'); return b ? b.getAttribute('onclick') : null; });
  const view = async name => { await page.evaluate(n => fmActions('/storage/emulated/0/Download/' + n, false, n), name); await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(120); };
  await page.evaluate(() => switchView('files')); await sleep(200);

  // ---------------------------------------------------------------- an extraction is running
  await page.evaluate(() => exStart({ src: '/storage/emulated/0/Download/small.zip', entry: '', name: 'small.zip', fromArc: false }, '/storage/emulated/0/Download/small', 'keep', false));
  await sleep(50);
  check('1. an extraction runs and shows its line', (await statusLine()) === 'Extracting… Cancel' && await page.evaluate(() => exRunning));
  await view('big.tar.zst');
  check('   View contents of a tar.zst now is refused in words', (await toastText()) === 'Another archive job is still running', JSON.stringify(await toastText()));
  check('   ... nothing is asked of the app', await page.evaluate(() => window.__calls.open.length === 0));
  check('   the line and its Cancel still belong to the extraction', (await statusLine()) === 'Extracting… Cancel' && (await cancelOf()) === 'fmArchiveCancelTap()', JSON.stringify([await statusLine(), await cancelOf()]));
  await page.evaluate(() => onArchiveProgress('Extracting 3 of 10 files…')); await sleep(30);
  check('   the extraction\'s progress goes to its own line', (await statusLine()) === 'Extracting 3 of 10 files… Cancel' && (await cancelOf()) === 'fmArchiveCancelTap()');
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', count: 10 })); await sleep(60);
  check('   when it ends the line goes', (await statusLine()) === null && !(await page.evaluate(() => exRunning)));
  await page.evaluate(() => { const v = document.getElementById('fmViewer'); if (v) v.style.display = 'none'; });

  // ---------------------------------------------------------------- after it: the open works
  await view('big.tar.zst');
  check('2. with nothing running the open goes ahead and waits in the line', (await page.evaluate(() => window.__calls.open.length)) === 1 && /^Opening big\.tar\.zst… Cancel$/.test(await statusLine() || '') && (await cancelOf()) === 'arcOpenCancelTap()', JSON.stringify(await statusLine()));
  await page.evaluate(() => arcOpenAbort()); await sleep(30);

  // ---------------------------------------------------------------- a compression, and a file job
  await page.evaluate(() => { czRunning = true; });
  await view('big.tar.zst');
  check('3. while a compression runs it is refused the same way', (await toastText()) === 'Another archive job is still running' && (await page.evaluate(() => window.__calls.open.length)) === 1);
  await page.evaluate(() => { czRunning = false; fmBatchRunning = true; });
  await view('big.tar.zst');
  check('   while a file job runs it says so', (await toastText()) === 'A file job is still running' && (await page.evaluate(() => window.__calls.open.length)) === 1, JSON.stringify(await toastText()));
  await page.evaluate(() => { fmBatchRunning = false; });

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
