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

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
