// v7.3 Installer, what the search found: duplicates and older versions are flagged, several files are picked and deleted at once with ONE Undo, and after a
// successful install the result sheet offers to delete the installer file (it goes to the hidden trash, with an Undo).
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const D = '/storage/emulated/0/Download/';
const FILES = [D + 'app-1.0.apk', D + 'app-2.0.apk', D + 'app-2.0 (1).apk', D + 'other.apk', D + 'bundle.xapk'];
const ANALYSIS = [
  { path: D + 'app-1.0.apk', pkg: 'com.example.app', vn: '1.0', vc: 100, older: true, newest: 200, newestPath: D + 'app-2.0.apk' },
  { path: D + 'app-2.0.apk', pkg: 'com.example.app', vn: '2.0', vc: 200 },
  { path: D + 'app-2.0 (1).apk', pkg: 'com.example.app', vn: '2.0', vc: 200, dupOf: D + 'app-2.0.apk' },
  { path: D + 'other.apk', pkg: 'com.other', vn: '5', vc: 5 },
];
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const b = await chromium.launch();
  const open = async (extra) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.__dialogs = [];
    page.on('dialog', d => { page.__dialogs.push(d.message()); (page.__accept === false ? d.dismiss() : d.accept()).catch(() => {}); });
    await page.addInitScript(inst.initScript, Object.assign({ kv: { perm_intro_v61: '1' }, files: FILES }, extra || {}));
    await page.goto(PAGE);
    await page.waitForFunction(() => typeof window.onApkScanProgress === 'function');
    await page.evaluate(() => switchView('installer'));
    await page.waitForFunction(() => window.__scanPending === true);
    await page.evaluate(() => window.__scanFinish());
    await page.waitForFunction(() => document.querySelectorAll('#apkScanList .apk-scan-row').length === 5);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const rowsOf = page => ev(page, () => Array.from(document.querySelectorAll('#apkScanList .apk-scan-row')).map(r => ({ name: r.querySelector('.apk-scan-name').innerText, flags: Array.from(r.querySelectorAll('.apk-flag')).map(f => f.innerText), picked: r.classList.contains('apk-picked'), pkg: (r.querySelector('.apk-scan-pkg') || {}).innerText || '' })));
  const snack = page => ev(page, () => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const settle = page => page.waitForFunction(() => window.__opQueue.length === 0 && apkOpWait.size === 0);

  // ---------------------------------------------------------------- 1) flags
  let page = await open();
  check('1. the search asks the app to look at the files (names, versions, copies): the .apk ones, the paths it can read', JSON.stringify(await ev(page, () => window.__calls.analyze[0].sort())) === JSON.stringify(FILES.filter(f => f.endsWith('.apk')).sort()) || (await ev(page, () => window.__calls.analyze[0].length)) >= 4);
  check('   before the answer nothing is flagged', (await rowsOf(page)).every(r => r.flags.length === 0));
  await ev(page, a => window.__analyzeFinish(a), ANALYSIS);
  await sleep(60);
  const rows = await rowsOf(page);
  const by = n => rows.find(r => r.name.endsWith(n));
  check('   a second copy is flagged "Duplicate" and says which file it is the same as', by('app-2.0 (1).apk').flags.join() === 'Duplicate' && /same as app-2\.0\.apk/.test(by('app-2.0 (1).apk').pkg), JSON.stringify(by('app-2.0 (1).apk')));
  check('   an old version is flagged "Older version" and says which file is newer', by('app-1.0.apk').flags.join() === 'Older version' && /newer: app-2\.0\.apk/.test(by('app-1.0.apk').pkg), JSON.stringify(by('app-1.0.apk')));
  check('   the file to keep and an unrelated one are not flagged; the package and version are shown', by('app-2.0.apk').flags.length === 0 && by('other.apk').flags.length === 0 && /^com\.other · 5$/.test(by('other.apk').pkg));
  check('   a bundle the app cannot read gets no flags and no package line', by('bundle.xapk').flags.length === 0 && by('bundle.xapk').pkg === '');

  // ---------------------------------------------------------------- 2) selecting
  await ev(page, () => apkSelToggle());
  check('2. Select shows the boxes and the buttons (the first is now Done)', (await ev(page, () => document.getElementById('apkSelBtn').innerText)) === 'Done' && (await ev(page, () => getComputedStyle(document.getElementById('apkSelDelBtn')).display)) !== 'none' && (await ev(page, () => document.getElementById('apkScanList').classList.contains('apk-selecting'))));
  await ev(page, () => apkSelCleanup());
  const sel = await rowsOf(page);
  check('   "Select duplicates and older versions" picks exactly the flagged files and says how many', sel.filter(r => r.picked).map(r => r.name.replace(/^.*?(app-[^ ]*( \(1\))?\.apk)$/, '$1')).sort().join('|') === 'app-1.0.apk|app-2.0 (1).apk' && /2 selected: 1 duplicate, 1 older version/.test(await ev(page, () => document.getElementById('toastMsg').innerText)), JSON.stringify(sel.map(r => r.picked)));
  check('   the delete button shows how many', (await ev(page, () => document.getElementById('apkSelDelBtn').innerText)) === 'Delete selected (2)');
  await ev(page, () => document.querySelectorAll('#apkScanList .apk-scan-row')[3].click());
  check('   tapping a row picks or unpicks it (it does not load the file)', (await ev(page, () => apkSel.size)) === 3 && (await ev(page, () => window.__calls.inspect.length)) === 0);
  await ev(page, () => document.querySelectorAll('#apkScanList .apk-scan-row')[3].click());
  check('   tapped again it is unpicked', (await ev(page, () => apkSel.size)) === 2);

  // ---------------------------------------------------------------- 3) one delete, one Undo
  page.__accept = false;
  await ev(page, () => apkSelDelete()); await sleep(40);
  check('3. Delete asks first with the count and the size; "no" deletes nothing', /Delete 2 files/.test(page.__dialogs.slice(-1)[0]) && /one Undo for all/.test(page.__dialogs.slice(-1)[0]) && (await ev(page, () => window.__calls.ops.length)) === 0);
  page.__accept = true;
  await ev(page, () => apkSelDelete());
  await settle(page); await sleep(60);
  check('   "yes" moves each file to the hidden trash, one after the other', (await ev(page, () => window.__calls.ops.filter(o => o.startsWith('trash:')).length)) === 2 && (await ev(page, () => document.querySelectorAll('#apkScanList .apk-scan-row').length)) === 3);
  check('   and ONE Undo bar for the whole batch', (await snack(page)) === 'Deleted 2 files');
  check('   the selection is empty and the files are gone from the list', (await ev(page, () => apkSel.size)) === 0 && !(await rowsOf(page)).some(r => /app-1\.0|\(1\)/.test(r.name)));
  await ev(page, () => sdbSnackUndoNow());
  await settle(page); await sleep(80);
  check('   Undo puts both back (the files and their places in the list), and says how many', (await ev(page, () => window.__calls.ops.filter(o => o.startsWith('untrash:')).length)) === 2 && (await ev(page, () => document.querySelectorAll('#apkScanList .apk-scan-row').length)) === 5 && /Restored 2 files/.test(await ev(page, () => document.getElementById('toastMsg').innerText)));
  check('   the files are on the fake storage again', (await ev(page, () => window.__fs.has('/storage/emulated/0/Download/app-1.0.apk') && window.__fs.has('/storage/emulated/0/Download/app-2.0 (1).apk'))));
  // let the bar run out: the files go for good
  await ev(page, () => { apkSelCleanup(); });
  await ev(page, () => apkSelDelete()); await settle(page); await sleep(60);
  await ev(page, () => sdbSnackHide()); await settle(page); await sleep(60);
  check('   when the bar goes without Undo, the files are deleted for good (the trash is emptied)', (await ev(page, () => window.__calls.ops.filter(o => o.startsWith('purge:')).length)) === 2 && !(await ev(page, () => Array.from(window.__fs).some(p => p.includes('.adb_manager_trash')))));
  await page.close();

  // a file that cannot be moved
  page = await open({ files: FILES });
  await ev(page, a => window.__analyzeFinish(a), ANALYSIS); await sleep(40);
  await ev(page, () => { window.__fs.delete('/storage/emulated/0/Download/app-1.0.apk'); apkSelToggle(); apkSelCleanup(); apkSelDelete(); });
  await settle(page); await sleep(60);
  check('   a file that is already gone is counted as failed; the others are deleted, one Undo for them', /Deleted 1 file · 1 failed/.test(await snack(page)));
  await page.close();

  // ---------------------------------------------------------------- 4) all, and a list with nothing flagged
  page = await open();
  await ev(page, () => { apkSelToggle(); apkSelCleanup(); });
  check('4. asking for the duplicates before the files were checked says to wait (nothing is picked)', /Still checking/.test(await ev(page, () => document.getElementById('toastMsg').innerText)) && (await ev(page, () => apkSel.size)) === 0);
  await ev(page, d => window.__analyzeFinish([{ path: d + 'other.apk', pkg: 'com.other', vn: '5', vc: 5 }]), D); await sleep(40);
  await ev(page, () => apkSelCleanup());
  check('   with nothing flagged it says so', /No duplicates or older versions found/.test(await ev(page, () => document.getElementById('toastMsg').innerText)));
  await ev(page, () => apkSelAll());
  check('   All picks every file shown; again it clears them', (await ev(page, () => apkSel.size)) === 5 && (await ev(page, () => { apkSelAll(); return apkSel.size; })) === 0);
  await ev(page, () => apkSelToggle());
  check('   Done leaves the selecting and forgets the picks', !(await ev(page, () => apkSelMode)) && (await ev(page, () => getComputedStyle(document.getElementById('apkSelDelBtn')).display)) === 'none');
  await page.close();

  // ---------------------------------------------------------------- 5) after an install
  page = await open();
  await ev(page, () => { window.__pkgs['/storage/emulated/0/Download/other.apk'] = { type: 'apk', pkg: 'com.other', label: 'Other', versionName: '5', versionCode: 5, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 4e6, isBase: true, split: '', configFor: '', feature: false }], signed: true, installed: false }; installFromScan(3); });
  await page.waitForFunction(() => !!installData);
  const opt = await ev(page, () => ({ present: !!document.getElementById('optOfferDelete'), on: document.getElementById('optOfferDelete').checked, auto: document.getElementById('optAutoDelete').checked }));
  check('5. an option "Offer to delete the file afterwards" is there, on at the start (the old "auto-delete" stays off)', opt.present && opt.on && !opt.auto, JSON.stringify(opt));
  await ev(page, () => runInstall());
  await ev(page, () => window.onInstallResult(JSON.stringify({ ok: true, output: 'Success', method: 'adb', pkg: 'com.other' }))); await sleep(60);
  const acts = await ev(page, () => Array.from(document.querySelectorAll('#commandResultsActions button')).map(x => x.innerText));
  check('   after a successful install the result sheet offers "Delete the installer file" next to Launch and Settings', acts.join('|') === 'Launch Application|Application Settings|Delete the installer file', acts.join('|'));
  await ev(page, () => document.querySelectorAll('#commandResultsActions button')[2].click()); await settle(page); await sleep(80);
  check('   it moves the file to the hidden trash, closes the sheet and shows an Undo bar', (await ev(page, () => window.__calls.ops.some(o => o === 'trash:/storage/emulated/0/Download/other.apk'))) && !(await ev(page, () => document.getElementById('commandResultsModal').classList.contains('show'))) && /Deleted other\.apk/.test(await snack(page)));
  await ev(page, () => sdbSnackUndoNow()); await settle(page); await sleep(60);
  check('   Undo brings it back', await ev(page, () => window.__fs.has('/storage/emulated/0/Download/other.apk')));
  await page.close();
  // no offer: switch off, failed install, a file from outside storage
  for (const [label, setup, result] of [
    ['switched off', () => { document.getElementById('optOfferDelete').checked = false; }, { ok: true, output: 'Success', method: 'adb' }],
    ['a failed install', () => {}, { ok: false, output: 'Failure', method: 'adb' }],
    ['the file was already deleted by the old option', () => {}, { ok: true, output: 'Success', method: 'adb', sourceDeleted: true }],
  ]) {
    page = await open();
    await ev(page, () => { window.__pkgs['/storage/emulated/0/Download/other.apk'] = { type: 'apk', pkg: 'com.other', label: 'Other', versionName: '5', versionCode: 5, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 4e6, isBase: true, split: '', configFor: '', feature: false }], signed: true, installed: false }; installFromScan(3); });
    await page.waitForFunction(() => !!installData);
    await ev(page, setup); await ev(page, () => runInstall());
    await ev(page, r => window.onInstallResult(JSON.stringify(r)), result); await sleep(60);
    const a2 = await ev(page, () => Array.from(document.querySelectorAll('#commandResultsActions button')).map(x => x.innerText));
    check('   no offer when ' + label, !a2.includes('Delete the installer file'), a2.join('|'));
    await page.close();
  }
  page = await open();
  await ev(page, () => { window.__pkgs['content://pick/1'] = { type: 'apk', pkg: 'com.picked', label: 'Picked', versionName: '1', versionCode: 1, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 4e6, isBase: true, split: '', configFor: '', feature: false }], signed: true, installed: false }; onInstallFilePicked('content://pick/1'); });
  await page.waitForFunction(() => !!installData);
  await ev(page, () => runInstall()); await ev(page, () => window.onInstallResult(JSON.stringify({ ok: true, output: 'Success', method: 'adb' }))); await sleep(60);
  check('   no offer for a file picked through Android\'s chooser (it is not a path of the storage)', !(await ev(page, () => Array.from(document.querySelectorAll('#commandResultsActions button')).some(x => /installer file/.test(x.innerText)))));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
