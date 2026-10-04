// v5.8 file manager multi-select: long-press / Select, select all, delete, copy / move through a clipboard, Paste here, conflicts, failures, Back.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = [];
  let acceptNext = true;
  page.on('dialog', d => { dialogs.push(d.message()); if (acceptNext) d.accept(); else d.dismiss(); });
  await page.addInitScript(() => {
    window.__calls = { batch: [], list: [] };
    window.__fs = {
      '/storage/emulated/0': [{ name: 'Download', isDir: true }, { name: 'DCIM', isDir: true }, { name: 'a.txt', isDir: false, size: 10 }, { name: "it's \"q\".txt", isDir: false, size: 20 }, { name: 'b.apk', isDir: false, size: 3000 }],
      '/storage/emulated/0/Download': [{ name: 'a.txt', isDir: false, size: 5 }, { name: 'old.zip', isDir: false, size: 99 }],
      '/storage/emulated/0/DCIM': [],
    };
    window.__batchReply = null;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      hasAllFilesAccess() { return true; }, loadSetting() { return ''; }, saveSetting() {},
      fmList(path) {
        window.__calls.list.push(path);
        const entries = (window.__fs[path] || []).map(e => Object.assign({ isLink: false, perms: (e.isDir ? 'd' : '-') + 'rwx', size: 0 }, e));
        return JSON.stringify({ path, entries });
      },
      fmBatch2(op, pathsJson, dest, policy) { return this.fmBatch(op, pathsJson, dest, policy); },
      fmBatchCancel() { window.__calls.cancel = (window.__calls.cancel || 0) + 1; },
      fmBatch(op, pathsJson, dest, policy) {
        window.__calls.batch.push({ op, paths: JSON.parse(pathsJson), dest, policy });
        if (window.__batchBusy) return 'busy';
        setTimeout(() => window.onFmBatchProgress && window.onFmBatchProgress('Working 1 of 2…'), 15);
        const done = () => window.onFmBatchDone(window.__batchReply || { op, ok: true, total: JSON.parse(pathsJson).length, done: JSON.parse(pathsJson).length, failed: [] });
        if (window.__holdBatch) window.__releaseBatch = done; else setTimeout(done, 80);          // held: the job ends when the test says so
        return 'started';
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(350);
  const sleep = ms => page.waitForTimeout(ms);
  const toast = () => page.locator('#toastMsg').innerText();
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  const picked = () => page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-row.fm-picked')).map(r => fmRows[+r.dataset.i].name));
  await page.evaluate(() => switchView('files')); await sleep(250);
  await page.evaluate(() => fmGo('/storage/emulated/0')); await sleep(150);

  // 1) Select mode via the button.
  console.log('1. five rows listed, no checkboxes visible, no selection bar:', (await page.locator('#fmList .perm-row[data-i]').count()) === 5 && !(await page.locator('#fmList .fm-chk').first().isVisible()) && !(await vis('#fmSelBar')));
  await page.locator('#fmSelectBtn').click(); await sleep(60);
  console.log('   Select shows checkboxes and the bar, hides the ⋯ buttons, and the button reads Done:', await page.locator('#fmList .fm-chk').first().isVisible() && await vis('#fmSelBar') && !(await page.locator('#fmList .fm-more').first().isVisible()) && /Done/.test(await page.locator('#fmSelectBtn').innerText()));
  await page.locator('#fmList .perm-info', { hasText: 'a.txt' }).first().click(); await sleep(40);
  await page.locator('#fmList .perm-info', { hasText: 'b.apk' }).click(); await sleep(40);
  console.log('   tapping rows picks them (a folder is not opened):', JSON.stringify(await picked()) === '["a.txt","b.apk"]' && (await page.evaluate(() => fmPath)) === '/storage/emulated/0', JSON.stringify(await picked()));
  console.log('   the count follows:', (await page.locator('#fmSelCount').innerText()) === '2 selected');
  await page.locator('#fmList .perm-info', { hasText: 'Download' }).click(); await sleep(40);
  console.log('   a folder row can be picked too and is not entered:', (await picked()).includes('Download') && (await page.evaluate(() => fmPath)) === '/storage/emulated/0');
  await page.locator('#fmList .perm-info', { hasText: 'Download' }).click(); await sleep(40);
  console.log('   tapping again un-picks it:', !(await picked()).includes('Download'));
  await page.locator('#fmSelBar button', { hasText: 'All' }).click(); await sleep(40);
  console.log('   All picks every row, and again none:', (await picked()).length === 5);
  await page.locator('#fmSelBar button', { hasText: 'All' }).click(); await sleep(40);
  console.log('   …', (await picked()).length === 0);

  // 2) Back leaves select mode first.
  const back = await page.evaluate(() => { const r = handleAndroidBack(); return { r, mode: fmSelMode, bar: getComputedStyle(document.getElementById('fmSelBar')).display }; });
  console.log('2. Back leaves selection mode first (the folder stays):', back.r === true && back.mode === false && back.bar === 'none' && (await page.evaluate(() => currentViewName())) === 'files');

  // 3) Long press starts a selection on that row instead of opening it.
  const box = await page.locator('#fmList .perm-info', { hasText: 'DCIM' }).boundingBox();
  await page.mouse.move(box.x + 30, box.y + 10); await page.mouse.down(); await sleep(700); await page.mouse.up(); await sleep(80);
  console.log('3. press and hold a folder row: selection mode starts with that row picked, the folder is not opened:', JSON.stringify(await picked()) === '["DCIM"]' && (await page.evaluate(() => fmSelMode)) && (await page.evaluate(() => fmPath)) === '/storage/emulated/0', JSON.stringify(await picked()));
  const box2 = await page.locator('#fmList .perm-info', { hasText: 'DCIM' }).boundingBox();     // the bar pushed the list down
  await page.mouse.move(box2.x + 30, box2.y + 10); await page.mouse.down(); await sleep(150); await page.mouse.up(); await sleep(60);
  console.log('   a short tap while selecting toggles it (it is un-picked):', (await picked()).length === 0);
  await page.evaluate(() => fmSelExit());

  // 4) Delete.
  await page.locator('#fmSelectBtn').click(); await sleep(40);
  await page.locator('#fmList .perm-info', { hasText: 'a.txt' }).first().click();
  await page.locator('#fmList .perm-info', { hasText: 'DCIM' }).click(); await sleep(40);
  dialogs.length = 0;
  await page.locator('#fmSelBar button', { hasText: 'Delete' }).click(); await sleep(200);
  const call1 = await page.evaluate(() => window.__calls.batch.slice());
  console.log('4. Delete asks first (count + names), then sends one batch with every path:', dialogs.length === 1 && /Delete 2 items/.test(dialogs[0]) && /DCIM/.test(dialogs[0]) && call1.length === 1 && call1[0].op === 'rm' && JSON.stringify(call1[0].paths.sort()) === JSON.stringify(['/storage/emulated/0/DCIM', '/storage/emulated/0/a.txt']) && call1[0].dest === '', JSON.stringify(call1));
  console.log('   selection mode ends and a status line shows meanwhile:', !(await page.evaluate(() => fmSelMode)));
  await sleep(150);
  console.log('   when done: toast, list refreshed:', /Deleted 2 of 2/.test(await toast()) && !(await vis('#fmBatchStatus')) && (await page.evaluate(() => window.__calls.list.length)) >= 3);
  acceptNext = false; dialogs.length = 0;
  await page.locator('#fmSelectBtn').click(); await page.locator('#fmList .perm-info', { hasText: 'b.apk' }).click();
  await page.locator('#fmSelBar button', { hasText: 'Delete' }).click(); await sleep(120);
  console.log('   declining the confirm sends nothing:', (await page.evaluate(() => window.__calls.batch.length)) === 1 && dialogs.length === 1 && (await page.evaluate(() => fmSel.size)) === 1);
  acceptNext = true;
  await page.evaluate(() => fmSelExit());

  // 5) Copy through the clipboard and paste elsewhere, with a name conflict.
  await page.locator('#fmSelectBtn').click();
  await page.locator('#fmList .perm-info', { hasText: 'a.txt' }).first().click();
  await page.locator('#fmList .perm-info', { hasText: 'b.apk' }).click();
  await page.locator('#fmSelBar button', { hasText: 'Copy' }).click(); await sleep(60);
  console.log('5. Copy: selection mode ends, the clipboard bar names the count:', !(await page.evaluate(() => fmSelMode)) && await vis('#fmClipBar') && /2 items to copy/.test(await page.locator('#fmClipText').innerText()));
  await page.evaluate(() => fmGo('/storage/emulated/0/Download')); await sleep(120);
  console.log('   the clipboard survives opening another folder:', await vis('#fmClipBar'));
  dialogs.length = 0;
  await page.locator('#fmPasteBtn').click(); await sleep(150);
  const askShown = await vis('#fmConflictModal'), askText = (await page.locator('#fmConflictCnt').innerText()) + ' ' + (await page.locator('#fmConflictNames').innerText());
  await page.locator('#fmConflictModal .batch-grid-btn.danger').click(); await sleep(250);
  const call2 = await page.evaluate(() => window.__calls.batch.slice(-1)[0]);
  console.log('   Paste here: a.txt already exists there, so a sheet asks (Replace / Skip / Keep both), then sends cp with the folder and the choice:', askShown && /1 of 2/.test(askText) && /a\.txt/.test(askText) && call2.policy === 'replace' && call2.op === 'cp' && call2.dest === '/storage/emulated/0/Download' && call2.paths.length === 2, JSON.stringify(call2) + ' ' + JSON.stringify(dialogs));
  console.log('   a copy keeps the clipboard for another paste:', await vis('#fmClipBar'));
  // paste into the folder the items are already in: refused
  await page.evaluate(() => fmGo('/storage/emulated/0')); await sleep(100);
  const before = await page.evaluate(() => window.__calls.batch.length);
  await page.locator('#fmPasteBtn').click(); await sleep(120);
  console.log('   pasting a copy into the folder it came from asks what to do (Keep both makes a duplicate) and sends nothing until chosen:', (await page.evaluate(() => window.__calls.batch.length)) === before && await vis('#fmConflictModal'));
  await page.evaluate(() => fmConflictCancel()); await sleep(40);
  await page.evaluate(() => { fmClip.op = 'mv'; });
  await page.locator('#fmPasteBtn').click(); await sleep(120);
  console.log('   pasting a move into the folder it came from is refused:', (await page.evaluate(() => window.__calls.batch.length)) === before && /already in this folder/i.test(await toast()));
  await page.locator('#fmClipBar button', { hasText: '✕' }).click(); await sleep(40);
  console.log('   ✕ drops the clipboard:', !(await vis('#fmClipBar')) && (await page.evaluate(() => fmClip)) === null);

  // 6) Move: clipboard cleared when it worked; a failure lists what failed.
  await page.locator('#fmSelectBtn').click();
  await page.locator('#fmList .perm-info', { hasText: 'a.txt' }).first().click();
  await page.locator('#fmSelBar button', { hasText: 'Move' }).click(); await sleep(60);
  await page.evaluate(() => fmGo('/storage/emulated/0/DCIM')); await sleep(100);
  await page.locator('#fmPasteBtn').click(); await sleep(250);
  const call3 = await page.evaluate(() => window.__calls.batch.slice(-1)[0]);
  console.log('6. Move sends mv to the open folder, no conflict prompt for a new name:', call3.op === 'mv' && call3.dest === '/storage/emulated/0/DCIM' && JSON.stringify(call3.paths) === '["/storage/emulated/0/a.txt"]');
  console.log('   after a successful move the clipboard is gone:', !(await vis('#fmClipBar')) && /Moved 1 of 1/.test(await toast()));
  await page.evaluate(() => { window.__batchReply = { op: 'rm', ok: false, total: 3, done: 1, failed: [{ p: '/storage/emulated/0/x', error: 'Permission denied' }, { p: '/storage/emulated/0/y', error: 'System location: not touched' }] }; });
  await page.evaluate(() => fmGo('/storage/emulated/0')); await sleep(80);
  await page.locator('#fmSelectBtn').click(); await page.locator('#fmList .perm-info', { hasText: 'b.apk' }).click();
  await page.locator('#fmSelBar button', { hasText: 'Delete' }).click(); await sleep(300);
  const res = await page.locator('#cmdResultsModal, #commandResultsModal').first().innerText().catch(() => '');
  console.log('   failures are reported (toast count + a results sheet naming each file and reason):', /1 of 3/.test(await toast()) && /2 failed/.test(await toast()) && /Permission denied/.test(res) && /not touched/.test(res), JSON.stringify(res.slice(0, 120)));
  await page.evaluate(() => { window.__batchReply = null; closeCommandResultsModal(); }); await sleep(60);

  // 7) Names with quotes/HTML are safe.
  await page.evaluate(() => { fmGo('/storage/emulated/0'); fmSelEnter(null); }); await sleep(80);
  await page.locator('#fmList .perm-info', { hasText: 'q' }).first().click(); await sleep(40);
  console.log('7. a file name with quotes can be picked (no inline handler breaks):', JSON.stringify(await picked()) === JSON.stringify(["it's \"q\".txt"]), JSON.stringify(await picked()));
  await page.evaluate(() => fmSelExit());

  // 8) A job running: a second one is refused, the Paste button is disabled.
  await page.evaluate(() => { window.__batchBusy = false; window.__holdBatch = true; fmClip = { op: 'cp', paths: ['/storage/emulated/0/zzz'] }; fmClipRender(); fmGo('/storage/emulated/0/DCIM'); });
  await page.locator('#fmPasteBtn').click();
  await page.waitForFunction(() => typeof window.__releaseBatch === 'function');          // the job has started and runs until it is released
  console.log('8. while a job runs the Paste button is disabled and a second job is refused:', await page.locator('#fmPasteBtn').isDisabled() && (await page.evaluate(() => fmBatchStart('rm', ['/x/y'], ''))) === false);
  await page.evaluate(() => { window.__holdBatch = false; window.__releaseBatch(); });
  await page.waitForFunction(() => !document.getElementById('fmPasteBtn').disabled);
  console.log('   …and it frees up afterwards:', !(await page.locator('#fmPasteBtn').isDisabled()));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
