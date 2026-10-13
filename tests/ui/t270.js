// Bug-hunt fixes: a Connected Devices batch stays on the device it started on (the device select is locked meanwhile, the device list does not re-pick); a rename
// or a typed copy / move onto a name already used in the folder shown is refused (a change of case only is not); a Hidden Settings restore reads a table it has
// not seen before it puts values, so Revert never deletes a value that existed; the Debloater review sheet shows its list after a batch left the modal in progress
// mode; a move paste with skipped items keeps only the skipped paths in the clipboard; the Task Manager poll stops when the app goes to the background and
// starts again when it comes back.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
const sdb = require('./lib/sdb_mock.js');
const appBatchMock = require('./lib/appbatch_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const ROOT = '/storage/emulated/0';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const newPage = async () => { const page = await b.newPage({ viewport: { width: 400, height: 860 } }); page.on('pageerror', e => errors.push(e.message)); return page; };
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);

  // ---- 1. Connected Devices: the batch keeps its device ----
  let page = await newPage();
  await page.addInitScript(() => {
    const WATCH = '192.168.1.20:5555', TAB = '10.0.0.9:5555';
    const st = window.__st = { calls: [], devs: ['self', 'watch', 'tab'], uninstalled: new Set() };
    const apps = ['com.one', 'com.two', 'com.three', 'com.keep'];          // com.keep stays: an empty installed list reads as "no answer"
    const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
    const shell = (cmd, serial) => {
      let m;
      if (/^for p in/.test(cmd)) return ['ro.product.manufacturer=x', 'ro.product.model=M-' + serial, 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a', 'ro.sf.lcd_density=320', 'wmsize=Physical size: 450x450 ', 'wmdens=Physical density: 320 ', 'batt=  level: 83   status: 3 '].join('\n');
      if (/^echo '#ALL'/.test(cmd)) {
        const all = apps.map(a => 'package:/system/app/' + a + '/x.apk=' + a);
        const inst = apps.filter(a => !st.uninstalled.has(serial + ':' + a)).map(a => 'package:' + a);
        return ['#ALL'].concat(all, ['#INST'], inst, ['#DIS'], ['#SYS'], apps.map(a => 'package:' + a)).join('\n');
      }
      if ((m = /^pm uninstall --user 0 '(.+)'$/.exec(cmd))) { st.uninstalled.add(serial + ':' + m[1]); return 'Success'; }
      if ((m = /^cmd package install-existing '(.+)'$/.exec(cmd))) { st.uninstalled.delete(serial + ':' + m[1]); return 'Package ' + m[1] + ' installed for user: 0'; }
      return '';
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
      cdAdb(tag, serial, argsJson) {
        const a = JSON.parse(argsJson);
        if (a[0] === 'devices') {
          let out = 'List of devices attached\n';
          if (st.devs.includes('self')) out += '127.0.0.1:5555\tdevice product:phone model:Pixel_8 device:husky transport_id:1\n';
          if (st.devs.includes('watch')) out += WATCH + '\tdevice product:e1sxxx model:Watch device:e1s transport_id:2\n';
          if (st.devs.includes('tab')) out += TAB + '\tdevice product:tab model:Tablet device:tab transport_id:3\n';
          return reply(tag, out);
        }
        if (a[0] === 'shell') { st.calls.push({ serial, cmd: a[1].slice(0, 30) }); return reply(tag, shell(a[1], serial), /^pm uninstall/.test(a[1]) ? 300 : 8); }
        return reply(tag, '');
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => switchView('devices')); await page.waitForTimeout(600);
  await page.evaluate(() => cdSub('apps')); await page.waitForTimeout(400);
  const WATCH = '192.168.1.20:5555', TAB = '10.0.0.9:5555';
  check('1. the watch is the device chosen, its apps are listed', await page.evaluate(() => cd.serial) === WATCH && await page.evaluate(() => (cd.apps || []).length) === 4);
  await page.evaluate(() => { cd.sel.add('com.one'); cd.sel.add('com.two'); cd.sel.add('com.three'); cdBatch('uninstall'); });
  await page.waitForTimeout(100);
  await page.evaluate(() => cdAskDone(true));
  await page.waitForTimeout(150);                       // the first pm uninstall is in flight
  check('   while the batch runs the device select is disabled', await page.evaluate(() => document.getElementById('cdDeviceSel').disabled && cd.batchBusy));
  await page.evaluate(() => cdSelect('10.0.0.9:5555'));
  check('   and choosing another device is refused', await page.evaluate(() => cd.serial) === WATCH);
  // the watch drops off the adb list: the 6-second poll runs cdRefreshDevices
  await page.evaluate(() => { window.__st.devs = ['self', 'tab']; return cdRefreshDevices(false); });
  await page.waitForTimeout(100);
  check('   the device list does not re-pick another device meanwhile', await page.evaluate(() => cd.serial) === WATCH, await page.evaluate(() => cd.serial));
  await page.waitForFunction(() => !cd.batchBusy, null, { timeout: 15000 }); await page.waitForTimeout(100);
  const calls = await page.evaluate(() => window.__st.calls.filter(c => /^pm uninstall/.test(c.cmd)));
  check('   every uninstall went to the watch', calls.length === 3 && calls.every(c => c.serial === WATCH), JSON.stringify(calls));
  const reads = await page.evaluate(() => window.__st.calls.filter(c => /^echo '#ALL'/.test(c.cmd)).slice(-1));
  check('   and the read-back afterwards too', reads.length === 1 && reads[0].serial === WATCH, JSON.stringify(reads));
  check('   the batch ends well: "Uninstalled 3 apps", the select is free again', /Uninstalled 3 apps/.test(await toast(page)) && await page.evaluate(() => !cd.batchBusy && !document.getElementById('cdDeviceSel').disabled), await toast(page));
  await page.evaluate(() => cdRefreshDevices(false)); await page.waitForTimeout(200);
  check('   after it the list may move to the tablet', await page.evaluate(() => cd.serial) === TAB);
  // a single action: the device chosen changes while its question is open
  await page.evaluate(() => { window.__st.devs = ['self', 'watch', 'tab']; return cdRefreshDevices(false); }); await page.waitForTimeout(600);
  await page.evaluate(() => { cdSelect('192.168.1.20:5555'); }); await page.waitForTimeout(600);
  await page.evaluate(() => { window.__st.calls.length = 0; cdAppAct('com.one', 'reinstall'); });
  await page.evaluate(() => cdSelect('10.0.0.9:5555')); await page.waitForTimeout(500);
  const re = await page.evaluate(() => window.__st.calls.filter(c => /install-existing/.test(c.cmd)));
  check('2. a single action keeps the device it was started on', re.length === 1 && re[0].serial === WATCH, JSON.stringify(re));
  await page.close();

  // ---- 2. File manager: a rename, copy or move onto a taken name ----
  page = await newPage();
  await page.addInitScript(fm.initScript, { files: { [ROOT + '/notes.txt']: 'n', [ROOT + '/other.txt']: 'o', [ROOT + '/Backup']: null, [ROOT + '/Backup/notes.txt']: 'old', [ROOT + '/Backup/keep.txt']: 'k' } });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('files'));
  await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
  const ops = () => page.evaluate(() => window.__fm.calls.filter(c => /^fmOp:/.test(c)));
  const tryOp = async (op, dest) => { await page.evaluate(([op, dest]) => { window.__fm.calls.length = 0; fmActions('/storage/emulated/0/notes.txt', false, 'notes.txt'); fmStartDest(op); document.getElementById('fmDestInput').value = dest; fmDestConfirm(); }, [op, dest]); await page.waitForTimeout(120); };
  await tryOp('rename', 'Backup');
  check('3. a rename onto an existing folder is refused (it would move the file inside and replace what is there)', /already used here/.test(await toast(page)) && (await ops()).length === 0 && await page.evaluate(() => window.__fm.fs['/storage/emulated/0/Backup/notes.txt'].text === 'old'), await toast(page));
  await tryOp('rename', 'OTHER.txt');
  check('   so is one onto an existing file, whatever the case', /already used here/.test(await toast(page)) && (await ops()).length === 0, await toast(page));
  await tryOp('cp', ROOT + '/other.txt');
  check('   a copy typed onto an existing file too', /already used here/.test(await toast(page)) && (await ops()).length === 0, await toast(page));
  await tryOp('cp', ROOT + '/copy.txt');
  check('   a copy to a new name runs', (await ops()).join() === 'fmOp:cp:' + ROOT + '/notes.txt->' + ROOT + '/copy.txt', (await ops()).join());
  await tryOp('rename', 'Notes.txt');
  check('   a change of case only is a rename', (await ops()).join() === 'fmOp:mv:' + ROOT + '/notes.txt->' + ROOT + '/Notes.txt', (await ops()).join());
  await page.close();

  // ---- 3. Hidden Settings: a restore into a table not read yet ----
  page = await newPage();
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(() => {                     // the first read of the Secure table fails (a phone that refuses it once): the tab opens with that table unread
    const iv = setInterval(() => {
      if (!window.AndroidBridge) return;
      clearInterval(iv);
      const real = window.AndroidBridge.settingsList;
      let failed = false;
      window.AndroidBridge.settingsList = function (req, ns) {
        if (ns === 'secure' && !failed) { failed = true; window.__calls.list.push(ns); setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: false, error: 'Permission denied' })), 10); return 'started'; }
        return real.call(this, req, ns);
      };
    }, 1);
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(500);
  check('4. the Settings tab opened with the Secure table unread', await page.evaluate(() => !sdbData.secure && !!sdbError.secure && !!sdbData.global), await page.evaluate(() => window.__calls.list.join()));
  await page.evaluate(() => { window.__calls.op.length = 0; window.__calls.list.length = 0; });
  const backup = JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'hidden-settings', settings: [{ ns: 'secure', key: 'ui_night_mode', value: '1', original: '2' }, { ns: 'global', key: 'zen_mode', value: '1', original: '0' }] });
  await page.evaluate(text => window.onTextFilePicked({ tag: 'hs-restore', text }), backup); await page.waitForTimeout(900);
  check('   the restore reads the Secure table first', await page.evaluate(() => window.__calls.list.join()) === 'secure' && await page.evaluate(() => !!sdbData.secure), await page.evaluate(() => window.__calls.list.join()));
  check('   then asks and puts both values', dialogs.length === 1 && /Put 2 hidden settings back/.test(dialogs[0]) && (await page.evaluate(() => window.__calls.op.map(o => o.op + ' ' + o.ns + '/' + o.key + '=' + o.value).sort().join('|'))) === 'put global/zen_mode=1|put secure/ui_night_mode=1', JSON.stringify(dialogs));
  const j = await page.evaluate(() => sdbJournal.filter(e => e.k === 'ui_night_mode').map(e => [e.ns, e.f, e.v]));
  check('   the history holds the value the setting had before (Revert puts it back, it does not delete)', JSON.stringify(j) === '[["secure","2","1"]]', JSON.stringify(j));
  check('   and the toast counts both', /Restored 2 of 2/.test(await toast(page)), await toast(page));
  await page.close();

  // ---- 4. Debloater review after a batch left the modal in progress mode (A2); a profile over a running batch (A3); a batch that did not run (A4) ----
  page = await newPage();
  await page.addInitScript(() => {
    const apps = [
      { pkg: 'com.a', name: 'App A', isSystem: true, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
      { pkg: 'com.b', name: 'App B', isSystem: true, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify(apps); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      executeAppAction(action, pkg) { return '\u00011ok ' + action + ' ' + pkg; },
      getUadMatches() { return JSON.stringify({ packages: [
        { pkg: 'com.a', name: 'App A', list: 'Google', removal: 'Recommended', state: 'enabled', description: 'x' },
        { pkg: 'com.b', name: 'App B', list: 'Google', removal: 'Recommended', state: 'enabled', description: 'y' } ] }); },
      getUadStatus() { return JSON.stringify({ cached: true, count: 2, updatedAt: Date.now(), stale: false }); },
    };
  });
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(500);
  const modal = () => page.evaluate(() => ({
    shown: document.getElementById('batchConfirmModal').classList.contains('show'),
    confirmBody: getComputedStyle(document.getElementById('batchConfirmBody')).display !== 'none',
    progressBody: getComputedStyle(document.getElementById('batchProgressBody')).display !== 'none',
    title: document.getElementById('batchConfirmTitle').innerText,
    rows: document.querySelectorAll('#batchConfirmList .confirm-list-row').length,
  }));
  await page.evaluate(() => { selectedPkgs.add('com.a'); selectedPkgs.add('com.b'); updateBatchBar(); batchAction('force_stop'); });
  await page.waitForTimeout(800);
  let m = await modal();
  check('5. a plain batch ran and its modal closed on the progress body', !m.shown && m.progressBody && !m.confirmBody, JSON.stringify(m));
  await page.evaluate(() => { document.getElementById('commandResultsModal').classList.remove('show'); switchView('debloater'); }); await page.waitForTimeout(300);
  await page.evaluate(() => { loadUadMatches(); uadSelected.add('com.a'); uadSelected.add('com.b'); uadAction('uninstall'); }); await page.waitForTimeout(200);
  m = await modal();
  check('   the Debloater review sheet shows its list, not the stale progress', m.shown && m.confirmBody && !m.progressBody && m.title === 'Review: Uninstall Packages' && m.rows === 2, JSON.stringify(m));
  await page.evaluate(() => closeBatchConfirmModal());
  // A3: a profile asked to run while a batch is in flight leaves that batch's modal alone
  await page.evaluate(() => { switchView('apps'); batchRunActive = true; profilePending = { id: 'x', plan: { steps: {} } }; applyProfile(); });
  check('6. a profile over a running batch is refused without touching the modal', /Another batch action is already running/.test(await toast(page)) && await page.evaluate(() => !!profilePending && !profileRunActive), await toast(page));
  await page.evaluate(() => { batchRunActive = false; profilePending = null; });
  // A4: a batch whose native run reports a failure leaves no label for the next one
  await page.evaluate(() => { batchRunLabel = 'Old label'; batchRunHist = 'custom'; window.onAppBatchDone(JSON.stringify({ ok: false })); });
  check('7. a batch that failed to run clears its label and history kind', await page.evaluate(() => batchRunLabel === '' && batchRunHist === '') && /failed to run/.test(await toast(page)), await toast(page));
  await page.close();

  // ---- 5. File manager: a move paste with Skip keeps only the skipped paths in the clipboard (D7); search hits in added storage (D2) ----
  page = await newPage();
  await page.addInitScript(fm.initScript, { files: { [ROOT + '/a.txt']: 'A', [ROOT + '/b.txt']: 'B', [ROOT + '/Dest']: null, [ROOT + '/Dest/a.txt']: 'old A', [ROOT + '/Other']: null } });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('files'));
  await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
  await page.evaluate(() => { fmSel.add('/storage/emulated/0/a.txt'); fmSel.add('/storage/emulated/0/b.txt'); fmClipSet('mv'); fmGo('/storage/emulated/0/Dest'); });
  await page.waitForFunction(() => fmPath === '/storage/emulated/0/Dest' && fmLast.entries.length === 1);
  await page.evaluate(() => fmPaste()); await page.waitForTimeout(100);
  check('8. pasting the move asks about the taken name', await page.evaluate(() => document.getElementById('fmConflictModal').classList.contains('show')));
  await page.evaluate(() => fmConflictChoose('skip')); await page.waitForTimeout(300);
  check('   Skip: one moved, one skipped', /Moved 1 of 2 • 1 skipped/.test(await toast(page)) && await page.evaluate(() => !!window.__fm.fs['/storage/emulated/0/Dest/b.txt'] && window.__fm.fs['/storage/emulated/0/Dest/a.txt'].text === 'old A'), await toast(page));
  check('   the clipboard keeps only the skipped path', JSON.stringify(await page.evaluate(() => fmClip && fmClip.paths)) === '["/storage/emulated/0/a.txt"]', JSON.stringify(await page.evaluate(() => fmClip)));
  await page.evaluate(() => fmGo('/storage/emulated/0/Other')); await page.waitForTimeout(100);
  await page.evaluate(() => fmPaste()); await page.waitForTimeout(300);
  check('   a second paste elsewhere moves only that one and empties the clipboard', /Moved 1 of 1/.test(await toast(page)) && await page.evaluate(() => fmClip === null), await toast(page));
  // D2: hits from added storage carry name and parent
  const TREE = 'content://com.android.externalstorage.documents/tree/1234-5678%3A';
  const hits = [
    { path: TREE + '/document/1234-5678%3ADocs%2Fnotes.txt', name: 'notes.txt', parent: TREE + '/document/1234-5678%3ADocs', dir: false, size: 12, mtime: 1700000000000, why: 'name' },
    { path: TREE + '/document/1234-5678%3ADocs', name: 'Docs', parent: TREE, dir: true, size: 0, mtime: 1700000000000, why: 'name' },
  ];
  await page.evaluate(([h, tree]) => { fmSafRootsLoad(); fmSafRoots = [{ uri: tree, label: 'SD card' }]; window.AndroidBridge.fmList = p => JSON.stringify({ path: p, entries: [], source: 'saf' }); fmSearchShow(true); window.onFmSearchDone({ ok: true, hits: h, ms: 5 }); }, [hits, TREE]);
  const shown = await page.evaluate(() => Array.from(document.querySelectorAll('#fmSearchList .perm-row')).map(r => [r.querySelector('.perm-name').innerText, r.querySelectorAll('.perm-sub')[0].innerText]));
  check('9. a hit in added storage is listed by its name, under its parent', JSON.stringify(shown) === JSON.stringify([['notes.txt', hits[0].parent], ['Docs/', TREE]]), JSON.stringify(shown));
  await page.evaluate(() => fmSearchOpen(0)); await page.waitForTimeout(100);
  check('   opening the file hit names it by its display name', await page.evaluate(() => document.getElementById('fmActionName').innerText === 'notes.txt' && fmActionTarget.full === fmSearchHits[0].path));
  await page.evaluate(() => { closeFmAction(); fmSearchReveal(0); }); await page.waitForTimeout(100);
  check('   "In folder" goes to the parent document with a way back to the storage', await page.evaluate(h => fmPath === h.parent && JSON.stringify(fmSafStack) === JSON.stringify([h.tree]), { parent: hits[0].parent, tree: TREE }), await page.evaluate(() => fmPath + ' ' + JSON.stringify(fmSafStack)));
  await page.evaluate(([h]) => { fmSearchShow(true); window.onFmSearchDone({ ok: true, hits: h, ms: 5 }); fmSearchOpen(1); }, [hits]); await page.waitForTimeout(100);
  check('   opening the folder hit goes into it with ".." back to the storage', await page.evaluate(h => fmPath === h.path && JSON.stringify(fmSafStack) === JSON.stringify([h.parent]), hits[1]), await page.evaluate(() => fmPath + ' ' + JSON.stringify(fmSafStack)));
  await page.close();

  // ---- 6. Task Manager: the poll stops while the app is in the background (D3) ----
  page = await newPage();
  await page.addInitScript(() => {
    window.__tm = { start: [], stop: 0 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, configuredMode: 'auto', adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {} }); },
      tmStart(ms) { window.__tm.start.push(ms); }, tmStop() { window.__tm.stop++; }, tmRefreshNow() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(300);
  const hide = h => page.evaluate(h => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => h }); Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => h ? 'hidden' : 'visible' }); document.dispatchEvent(new Event('visibilitychange')); }, h);
  check('10. opening the tab starts the poll', await page.evaluate(() => window.__tm.start.length === 1 && window.__tm.stop === 0), JSON.stringify(await page.evaluate(() => window.__tm)));
  await hide(true); await page.waitForTimeout(50);
  check('   the app going to the background stops it', await page.evaluate(() => window.__tm.stop === 1 && tmRedrawTimer === null), JSON.stringify(await page.evaluate(() => window.__tm)));
  await hide(false); await page.waitForTimeout(50);
  check('   coming back starts it again', await page.evaluate(() => window.__tm.start.length === 2 && tmRedrawTimer !== null), JSON.stringify(await page.evaluate(() => window.__tm)));
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(100);
  const stops = await page.evaluate(() => window.__tm.stop);
  await hide(true); await hide(false); await page.waitForTimeout(50);
  check('   on another tab the background does nothing to it', await page.evaluate(s => window.__tm.stop === s && window.__tm.start.length === 2, stops), JSON.stringify(await page.evaluate(() => window.__tm)));
  await page.close();

  // ---- 7. Hidden Settings: a backup key with / + , $ restores (D5) ----
  page = await newPage();
  const dlg2 = []; page.on('dialog', d => { dlg2.push(d.message()); d.accept(); });
  await page.addInitScript(sdb.initScript);
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(500);
  await page.evaluate(() => { window.__calls.op.length = 0; });
  const bk2 = JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'hidden-settings', settings: [{ ns: 'global', key: 'weird.key:with-chars/and+more', value: 'w2' }, { ns: 'global', key: 'a,b$c', value: '1' }, { ns: 'global', key: 'bad key', value: '1' }, { ns: 'global', key: '-dash', value: '1' }] });
  await page.evaluate(text => window.onTextFilePicked({ tag: 'hs-restore', text }), bk2); await page.waitForTimeout(600);
  const ops2 = await page.evaluate(() => window.__calls.op.map(o => o.key + '=' + o.value).sort().join('|'));
  check('11. keys with / + , $ are restored; a space or a leading dash is still refused', ops2 === 'a,b$c=1|weird.key:with-chars/and+more=w2' && /Put 2 hidden settings back/.test(dlg2[0] || '') && /Restored 2 of 2/.test(await toast(page)), ops2 + ' | ' + await toast(page));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
