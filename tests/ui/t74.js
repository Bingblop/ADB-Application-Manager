// v6.1 Installer, finding and picking packages: a progress bar while "Find APKs on this device" runs; press and hold a found file to delete it from the
// device (with a tip that says so, and an Undo bar); the page swipes down to the next box once a package is picked.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const FILES = ['/storage/emulated/0/Download/Example-App-2.0.apks', '/storage/emulated/0/Download/Another.apk', '/storage/emulated/0/Telegram/Telegram Documents/game_data.xapk', '/storage/ABCD-1234/Backup/old-version.apkm'];

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 }, hasTouch: true });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, files: FILES });
  await page.addInitScript(() => {
    const mk = (n, split, size, extra) => Object.assign({ path: '/cache/' + n, name: n, size, isBase: false, split: split || '', configFor: '', feature: false }, extra || {});
    window.__pkgs['/storage/emulated/0/Download/Example-App-2.0.apks'] = { type: 'apks', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 70e6,
      splits: [mk('base.apk', '', 60e6, { isBase: true }), mk('0__split_config.arm64_v8a.apk', 'config.arm64_v8a', 12e6)], signed: true, installed: false };
    window.__pkgs['content://pick/1'] = { type: 'apk', pkg: 'com.picked', label: 'Picked', versionName: '1', versionCode: 1, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [mk('base.apk', '', 4e6, { isBase: true })], signed: true, installed: false };
    window.__pkgs['/storage/emulated/0/Download/Another.apk'] = { type: 'apk', pkg: 'com.another', label: 'Another', versionName: '1', versionCode: 1, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [mk('base.apk', '', 4e6, { isBase: true })], signed: true, installed: false };
  });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.onApkScanProgress === 'function');
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const calls = () => ev(() => window.__calls);
  const bar = () => ev(() => { const b = document.getElementById('apkScanBar'), f = document.getElementById('apkScanBarFill'); return { shown: getComputedStyle(b).display !== 'none', indeterminate: b.classList.contains('indeterminate'), width: f.style.width, now: b.getAttribute('aria-valuenow'), status: document.getElementById('apkScanStatus').innerText }; });
  const rows = () => ev(() => Array.from(document.querySelectorAll('#apkScanList .apk-scan-row')).map(r => r.querySelector('.apk-scan-name').innerText));
  const waitRows = n => page.waitForFunction(k => document.querySelectorAll('#apkScanList .apk-scan-row').length === k, n);
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  // hold until the page says the press has been taken for a long one (not for a fixed time: a slow machine would let go too early)
  const longPress = async (selector) => { const bx = await page.locator(selector).first().boundingBox(); await page.mouse.move(bx.x + 120, bx.y + bx.height / 2); await page.mouse.down(); await page.waitForFunction(() => apkPress.fired === true, null, { timeout: 15000 }); await page.mouse.up(); };
  const settleOps = async () => { await page.waitForFunction(() => window.__opQueue.length === 0 && apkOpWait.size === 0); };

  // 1) the search starts by itself when the tab opens, with a bar
  await ev(() => switchView('installer'));
  await page.waitForFunction(() => window.__scanPending === true);
  let s = await bar();
  check('1. the search starts with the tab and shows a bar at its start', (await calls()).scan === 1 && s.shown && !s.indeterminate && s.width === '2%', JSON.stringify(s));
  check('   with the find button off while it runs', await ev(() => document.getElementById('apkScanBtn').disabled));
  await ev(() => window.__scanStep(37, 'Searched Download (6 of 16 folders)', 2));
  s = await bar();
  check('2. the bar follows what the phone reports (37%), with the folder it is at and how many files were found', s.width === '37%' && s.now === '37' && /Searched Download \(6 of 16 folders\)/.test(s.status) && /2 found/.test(s.status), JSON.stringify(s));
  await ev(() => window.__scanStep(85, 'Searched Music (14 of 16 folders)', 3));
  s = await bar();
  check('   it moves on', s.width === '85%' && /3 found/.test(s.status), s.width);
  await ev(() => window.__scanStep(-1, 'Searching Android/data and Android/obb with the privileged mode…', 3));
  s = await bar();
  check('3. a step the phone cannot measure (one shell command) shows a bar that sweeps, not a number', s.indeterminate && s.width === '' && s.now === null && /privileged mode/.test(s.status), JSON.stringify(s));
  await ev(() => window.__scanStep(95, 'Reading sizes and dates (40 of 80 files)', 4));
  s = await bar();
  check('   and it goes back to numbers after it', !s.indeterminate && s.width === '95%', JSON.stringify(s));
  await ev(() => window.__scanStep(-5, '', 0));
  check('   a made-up step never gives a negative or huge width', (await bar()).indeterminate === true);
  await ev(() => window.__scanStep(900, 'x', 1));
  check('   or one past 100%', (await bar()).width === '100%');
  await ev(() => window.__scanFinish());
  await waitRows(4);
  s = await bar();
  check('4. when the answer comes the bar goes and the list appears', !s.shown && (await rows()).length === 4 && !(await ev(() => document.getElementById('apkScanBtn').disabled)), JSON.stringify(s));
  await ev(() => window.onApkScanProgress(JSON.stringify({ pct: 10, msg: 'late', found: 0 })));
  check('   a late progress report after the end does not bring the bar back', !(await bar()).shown);
  check('   the status line counts the files', /4 of 4 files found/.test(await page.locator('#apkScanStatus').innerText()));

  // 2) the tip
  const tip = await ev(() => { const t = document.getElementById('apkScanTip'); return { shown: getComputedStyle(t).display !== 'none', text: t.innerText, title: document.querySelector('.apk-scan-row').title }; });
  check('5. a tip tells to press and hold a file to delete it, with an Undo after', tip.shown && /press and hold/i.test(tip.text) && /delete/i.test(tip.text) && /undo/i.test(tip.text), tip.text);
  check('   every row carries the same words as a hover title (tap to load, press and hold to delete)', /Tap to load/.test(tip.title) && /press and hold \(or press Delete\) to delete/.test(tip.title), tip.title);
  await ev(() => { document.getElementById('apkScanSearch').value = 'zzzz'; renderApkScan(); });
  check('   the tip stays while the filter shows nothing (the rows are still there to press)', true);
  await ev(() => { document.getElementById('apkScanSearch').value = ''; renderApkScan(); });

  // 3) press and hold -> sheet -> delete -> undo
  const before = await calls();
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show');
  check('6. press and hold on a file opens a sheet for it (not the file)', (await ev(() => document.getElementById('apkDeleteTitle').innerText)) === 'Delete Example-App-2.0.apks?' && (await calls()).inspect.length === before.inspect.length);
  const sh = await ev(() => ({ sub: document.getElementById('apkDeleteSub').innerText, path: document.getElementById('apkDeletePath').innerText, helper: document.querySelector('#apkDeleteModal .modal-helper').innerText }));
  check('   the sheet shows the size, how old it is, the full path, and that an Undo follows', /11\.8 MB/.test(sh.sub) && /days ago/.test(sh.sub) && sh.path === FILES[0] && /Undo/.test(sh.helper), JSON.stringify(sh));
  check('   nothing has been deleted yet', (await calls()).ops.length === 0 && (await rows()).length === 4);
  await page.click('#apkDeleteModal >> text=Cancel');
  await page.waitForFunction(() => !document.getElementById('apkDeleteModal').classList.contains('show'));
  check('7. Cancel closes it and nothing happens', (await calls()).ops.length === 0 && (await rows()).length === 4 && (await snack()) === '');
  // the backdrop and Back close it too
  await longPress('.apk-scan-row >> nth=1');
  await page.waitForSelector('#apkDeleteModal.show');
  check('   Back closes it too', (await ev(() => handleAndroidBack())) === true && !(await ev(() => document.getElementById('apkDeleteModal').classList.contains('show'))));
  check('   and a press that opened the sheet did not also load the file', (await calls()).inspect.length === 0);

  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show');
  await page.click('#apkDeleteBtn');
  await page.waitForFunction(() => document.querySelectorAll('#apkScanList .apk-scan-row').length === 3);
  await settleOps();
  const after = await calls();
  check('8. Delete asks the phone to move that one file to its trash', after.ops.length === 1 && after.ops[0] === 'trash:' + FILES[0], JSON.stringify(after.ops));
  check('   the file is gone from the list at once, and from the (fake) storage', (await rows()).join('|') === 'Another.apk|game_data.xapk|old-version.apkm' && !(await ev(p => window.__fs.has(p), FILES[0])), (await rows()).join('|'));
  check('   an Undo bar says what was deleted', (await snack()) === '🗑️ Deleted Example-App-2.0.apks', await snack());
  check('   the file is only in the trash so far (nothing is deleted for good)', (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash')))).length === 1 && !after.ops.some(o => o.startsWith('purge')));
  check('   the status line counts what is left', /3 of 3 files found/.test(await page.locator('#apkScanStatus').innerText()));
  await page.click('#sdbSnackUndo');
  await waitRows(4);
  await settleOps();
  const undone = await calls();
  check('9. Undo puts it back where it was: the same place in the list, the same path on the phone', (await rows())[0] === 'Example-App-2.0.apks' && await ev(p => window.__fs.has(p), FILES[0]) && undone.ops.length === 2 && undone.ops[1].startsWith('untrash:/storage/emulated/0/.adb_manager_trash/') && undone.ops[1].endsWith('>' + FILES[0]), JSON.stringify(undone.ops));
  check('   the Undo bar is gone and nothing was purged', (await snack()) === '' && !undone.ops.some(o => o.startsWith('purge')) && (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash')))).length === 0);
  await page.waitForTimeout(150);
  check('   the toast says it was restored', /Restored Example-App-2.0.apks/.test(await page.locator('#toastMsg').innerText()));

  // 4) the Undo runs out: the file goes for good
  await longPress('.apk-scan-row >> nth=1');
  await page.waitForSelector('#apkDeleteModal.show');
  await page.click('#apkDeleteBtn');
  await waitRows(3); await settleOps();
  const trashPath = (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash'))))[0];
  check('10. a second file goes to the trash the same way (volume\'s own trash folder)', /^\/storage\/emulated\/0\/\.adb_manager_trash\/\d+_Another\.apk$/.test(trashPath), trashPath);
  await ev(() => sdbSnackHide());                    // the bar's few seconds are over
  await settleOps();
  check('    when the bar goes without Undo the file is deleted for good (a purge of exactly that file)', (await calls()).ops.slice(-1)[0] === 'purge:' + trashPath && !(await ev(p => window.__fs.has(p), trashPath)), (await calls()).ops.slice(-1)[0]);
  check('    and the list does not list it again', (await rows()).join('|') === 'Example-App-2.0.apks|game_data.xapk|old-version.apkm');

  // 5) deleting a second file while the bar of the first is still up makes the first final
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await waitRows(2); await settleOps();
  const t1 = (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash'))))[0];
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await waitRows(1); await settleOps();
  check('11. a new delete while the first one\'s bar is up makes the first one final', (await calls()).ops.filter(o => o === 'purge:' + t1).length === 1 && !(await ev(p => window.__fs.has(p), t1)) && /Deleted old-version.apkm|Deleted game_data.xapk/.test(await snack()), await snack());
  // leaving the tab makes the last one final
  const t2 = (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash'))))[0];
  await ev(() => switchView('apps'));
  await settleOps();
  check('12. leaving the Installer tab makes it final too (an Undo only makes sense next to what it undoes)', (await calls()).ops.slice(-1)[0] === 'purge:' + t2 && (await snack()) === '', (await calls()).ops.slice(-1)[0]);

  // 6) things that go wrong
  await ev(fs => { window.__fs.clear(); fs.forEach(f => window.__fs.add(f)); }, FILES);
  await ev(() => switchView('installer'));
  await ev(() => scanApkFiles());                    // (the tab only searches by itself the first time)
  await page.waitForFunction(() => window.__scanPending === true);
  await ev(() => window.__scanFinish());
  await waitRows(4);
  await ev(() => { window.__opFail = 'trash'; });
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await page.waitForFunction(() => /Couldn't delete/.test(document.getElementById('toastMsg').innerText));
  check('13. when the phone refuses, the file stays in the list, a toast says why, and there is no Undo bar', (await rows()).length === 4 && /Android refused \(trash\)/.test(await page.locator('#toastMsg').innerText()) && (await snack()) === '', await page.locator('#toastMsg').innerText());
  await ev(() => { window.__opFail = ''; });
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await waitRows(3); await settleOps();
  await ev(() => { window.__opFail = 'untrash'; });
  await page.click('#sdbSnackUndo');
  await page.waitForFunction(() => /Couldn't restore/.test(document.getElementById('toastMsg').innerText));
  check('14. when the phone cannot put it back, a toast says so and the list does not pretend', (await rows()).length === 3 && /Couldn't restore Example-App-2.0.apks/.test(await page.locator('#toastMsg').innerText()));
  check('    …and an Undo bar comes back, so it can be tried again (the file is still in its hidden folder)', /Not restored: Example-App-2.0.apks/.test(await snack()) && (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash')))).length === 1, await snack());
  await ev(() => { window.__opFail = ''; });
  await page.click('#sdbSnackUndo');
  await waitRows(4); await settleOps();
  check('    and the second try puts it back', (await rows())[0] === 'Example-App-2.0.apks' && await ev(p => window.__fs.has(p), FILES[0]) && (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash')))).length === 0, (await rows()).join('|'));
  await ev(() => { window.__opFail = ''; });
  // a file of that name is there again when Undo is pressed: both are kept
  await ev(fs => { window.__fs.clear(); fs.forEach(f => window.__fs.add(f)); window.__scanFiles = null; }, FILES);
  await ev(() => scanApkFiles()); await page.waitForFunction(() => window.__scanPending === true); await ev(() => window.__scanFinish()); await waitRows(4);
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await waitRows(3); await settleOps();
  await ev(p => window.__fs.add(p), FILES[0]);        // another copy has been downloaded meanwhile
  await page.click('#sdbSnackUndo');
  await waitRows(4); await settleOps();
  check('15. a file of the same name is there again when Undo is pressed: the restored one is kept next to it, under another name, and shown with it', (await rows()).includes('Example-App-2.0 (2).apks') && await ev(() => window.__fs.has('/storage/emulated/0/Download/Example-App-2.0 (2).apks')) && await ev(p => window.__fs.has(p), FILES[0]), (await rows()).join('|'));

  // 6b) more things that go wrong
  const resetList = async () => {
    await ev(fs => { window.__fs.clear(); fs.forEach(f => window.__fs.add(f)); window.__scanFiles = null; window.__opFail = ''; window.__opsHold = false; window.__opQueue.length = 0; }, FILES);
    await ev(() => { sdbSnackHide(); switchView('installer'); });
    await ev(() => scanApkFiles()); await page.waitForFunction(() => window.__scanPending === true); await ev(() => window.__scanFinish()); await waitRows(4);
  };
  await resetList();
  // the answer to a delete comes after the person went to another tab: there is no bar to put an Undo on there, so the file is final
  await ev(() => { window.__opsHold = true; });
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show'); await page.click('#apkDeleteBtn');
  await page.waitForFunction(() => window.__opQueue.length === 1);
  await ev(() => switchView('apps'));
  await ev(() => { window.__opsHold = false; window.__opsFlush(); });
  await page.waitForFunction(() => window.__calls.ops.some(o => o.startsWith('purge:') && o.includes('Example-App-2.0')));
  await settleOps();
  check('23. an answer that comes after the person went to another tab puts no Undo bar there: the file is final at once', (await snack()) === '' && (await ev(() => Array.from(window.__fs).filter(p => p.includes('.adb_manager_trash')))).length === 0);
  // a delete answered while a new search is running does not redraw the list under it
  await resetList();
  await longPress('.apk-scan-row >> nth=0');
  await page.waitForSelector('#apkDeleteModal.show');
  await ev(() => { window.__opsHold = true; });
  await page.click('#apkDeleteBtn');
  await page.waitForFunction(() => window.__opQueue.length === 1);
  await ev(() => scanApkFiles());                         // Find APKs again before the answer comes
  await page.waitForFunction(() => window.__scanPending === true);
  await ev(() => { window.__opsHold = false; window.__opsFlush(); });
  await settleOps();
  s = await bar();
  check('24. a delete answered while a new search is running leaves that search\'s bar and status alone', s.shown && /Searching storage/.test(s.status), JSON.stringify(s));
  await ev(() => window.__scanFinish());
  await waitRows(3);
  check('    and when the search ends its list is the three that are left', (await rows()).join('|') === 'Another.apk|game_data.xapk|old-version.apkm', (await rows()).join('|'));
  // the press is on the file, not on its place in the list
  await resetList();
  const pressed = await ev(() => { const row = document.querySelector('.apk-scan-row[data-i="0"]'); apkPressStart(row, 0, 0); const n = apkScanFiles[0].name; apkScanFiles.reverse(); renderApkScan(); return n; });
  await page.waitForSelector('#apkDeleteModal.show');
  check('25. a list that is redrawn (here: turned round) while the finger is down still opens the sheet for the file that was pressed', (await ev(() => document.getElementById('apkDeleteTitle').innerText)) === 'Delete ' + pressed + '?', pressed);
  await page.click('#apkDeleteModal >> text=Cancel');
  await ev(() => { apkPress.fired = false; });
  // keyboard and screen readers
  await resetList();
  await ev(() => document.querySelector('.apk-scan-row[data-i="1"]').focus());
  check('26. a found file can be reached with the keyboard and is a button for a screen reader', await ev(() => document.activeElement.classList.contains('apk-scan-row') && document.activeElement.getAttribute('role') === 'button' && document.activeElement.tabIndex === 0));
  await page.keyboard.press('Delete');
  await page.waitForSelector('#apkDeleteModal.show');
  check('    Delete opens the delete sheet for it', (await ev(() => document.getElementById('apkDeleteTitle').innerText)) === 'Delete Another.apk?');
  await page.click('#apkDeleteModal >> text=Cancel');
  const nIns = (await calls()).inspect.length;
  await ev(() => document.querySelector('.apk-scan-row[data-i="1"]').focus());
  await page.keyboard.press('Enter');
  await page.waitForFunction(n => window.__calls.inspect.length > n, nIns);
  check('    Enter loads it', (await calls()).inspect.slice(-1)[0] === FILES[1]);
  check('27. the text of a found file is not selectable (the long press must not start Android\'s own text selection)', await ev(() => { const st = getComputedStyle(document.querySelector('.apk-scan-row')); return st.userSelect === 'none' || st.webkitUserSelect === 'none'; }));

  // 7) a short tap still loads the file, and the page swipes down to the next box
  await ev(() => { window.scrollTo(0, 0); });
  await ev(() => document.getElementById('apkScanBox').scrollIntoView({ block: 'start' }));
  const y0 = await ev(() => Math.round(window.scrollY));
  await page.locator('.apk-scan-row', { hasText: 'Another.apk' }).click();
  await page.waitForFunction(() => document.getElementById('installInfoCard').style.display !== 'none');
  await page.waitForFunction(y => window.scrollY > y + 100, y0);
  await page.waitForTimeout(700);                     // the smooth scroll runs to its end
  const top = await ev(() => Math.round(document.getElementById('installInfoCard').getBoundingClientRect().top));
  const hdr = await ev(() => Math.round(document.querySelector('.tab-bar').getBoundingClientRect().bottom));
  check('16. a tap loads the file and the page swipes down to the next box (the package info card), just below the header and tabs', (await calls()).inspect.slice(-1)[0] === FILES[1] && top >= hdr - 5 && top <= hdr + 80, 'card top ' + top + ', tabs end ' + hdr + ', scrollY ' + (await ev(() => Math.round(window.scrollY))) + ' from ' + y0);
  check('    the card it landed on says what was picked', (await page.locator('#installPkgLabel').innerText()) === 'Another');
  // from the system picker too
  await ev(() => { window.scrollTo(0, 0); });
  await ev(() => pickInstallFile());
  await page.waitForFunction(() => window.scrollY > 100);
  await page.waitForTimeout(600);
  check('17. choosing with the file picker swipes down the same way', (await ev(() => Math.round(document.getElementById('installInfoCard').getBoundingClientRect().top))) <= hdr + 80);
  // no swipe when the user is not on the Installer any more
  await ev(() => { window.scrollTo(0, 0); onInstallFilePicked('/storage/emulated/0/Download/Another.apk'); switchView('apps'); });
  await page.waitForTimeout(600);
  check('18. no swipe when the answer comes after the user went to another tab', (await ev(() => Math.round(window.scrollY))) < 50);
  // reduced motion: it jumps instead
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ev(() => { switchView('installer'); window.scrollTo(0, 0); });
  await ev(() => onInstallFilePicked('/storage/emulated/0/Download/Another.apk'));
  await page.waitForFunction(() => window.scrollY > 100, null, { timeout: 3000 });
  check('19. with reduced motion switched on it jumps there instead of gliding (it still arrives)', true);
  await page.emulateMedia({ reducedMotion: 'no-preference' });

  // 8) press and hold with a finger
  await ev(() => switchView('installer'));
  await ev(() => document.getElementById('apkScanBox').scrollIntoView({ block: 'start' }));
  const cdp = await page.context().newCDPSession(page);
  const row = await page.locator('.apk-scan-row').first().boundingBox();
  const pt = [{ x: row.x + 100, y: row.y + row.height / 2 }];
  const inspectsBefore = (await calls()).inspect.length;
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: pt });
  await page.waitForFunction(() => apkPress.fired === true, null, { timeout: 15000 });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForSelector('#apkDeleteModal.show');
  check('20. a finger held on a file opens the sheet too, and lifting it does not also load the file', (await calls()).inspect.length === inspectsBefore);
  await page.click('#apkDeleteModal >> text=Cancel');
  // a finger that moves (a scroll) is not a press
  const row2 = await page.locator('.apk-scan-row').first().boundingBox();
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: row2.x + 100, y: row2.y + 30 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: row2.x + 100, y: row2.y + 80 }] });
  await page.waitForTimeout(750);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  check('21. a finger that moves (scrolling the list) does not open it', !(await ev(() => document.getElementById('apkDeleteModal').classList.contains('show'))));

  // 9) no access: the search says so, and asks (not when it started by itself)
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => { bad++; console.log('FAIL page error (access):', e.message); });
  await p2.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, perm: { files: false }, mode: { priv: false } });
  await p2.goto(PAGE);
  await p2.waitForFunction(() => typeof window.onApkScanProgress === 'function');
  await p2.evaluate(() => switchView('installer'));
  await p2.waitForFunction(() => window.__scanPending === true);
  await p2.evaluate(() => window.__scanFinish({ status: 'noaccess', files: [] }));
  await p2.waitForFunction(() => /All-files access/.test(document.getElementById('apkScanStatus').innerHTML));
  check('22. with no access the automatic search only says what is needed (no sheet in the way)', !(await p2.isVisible('#permModal.show')) && await p2.isVisible('#apkScanStatus >> text=Grant access'));
  await p2.evaluate(() => scanApkFiles());
  await p2.waitForFunction(() => window.__scanPending === true);
  await p2.evaluate(() => window.__scanFinish({ status: 'noaccess', files: [] }));
  await p2.waitForSelector('#permModal.show');
  check('    a search the user started asks for the access', (await p2.locator('#permTitle').innerText()) === 'File access needed' && /search storage/.test(await p2.locator('#permReason').innerText()));
  await p2.click('#permCloseBtn');
  await p2.evaluate(() => scanApkFiles());
  await p2.waitForFunction(() => window.__calls.scan === 3 && window.__scanPending === true);
  await p2.evaluate(() => window.__scanFinish({ status: 'noaccess', files: [] }));
  await p2.waitForSelector('#permModal.show');
  check('    and again straight after "Not now", because the person asked again', (await p2.locator('#permTitle').innerText()) === 'File access needed');
  await p2.close();

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
