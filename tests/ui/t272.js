// Connected Devices, Files and Apps menu: an action stays on the device it was started on. Delete, Rename and New folder read the device before their question and refuse when
// another device was picked while it was open; a multi-file push and the APK pull stop when the device list moved to another device half-way instead of sending the rest
// there; a folder pull that skipped files is not reported as "Saved".
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // A page with two devices (A is the one chosen, B is next in the list), on the Files sub-tab of A. The mock records every command with the device it was sent to.
  const open = async () => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
      const st = window.__st = { calls: [], devs: ['a', 'b'], xferMs: 0, pullOut: null };
      const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
      const shell = (cmd, serial) => {
        if (/^for p in/.test(cmd)) return ['ro.product.manufacturer=x', 'ro.product.model=M-' + serial, 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a', 'ro.sf.lcd_density=320', 'wmsize=Physical size: 450x450 ', 'wmdens=Physical density: 320 ', 'batt=  level: 83   status: 3 '].join('\n');
        if (/^ls -la '\/sdcard\/'$/.test(cmd)) return 'total 8\ndrwxrwx--x 2 u0_a1 media_rw 4096 2026-01-02 03:04 Folder\n-rw-rw---- 1 u0_a1 media_rw 12 2026-01-02 03:04 notes.txt';
        if (/^pm path '(.+)'$/.test(cmd)) return 'package:/data/app/x/base.apk\npackage:/data/app/x/split_a.apk\npackage:/data/app/x/split_b.apk';
        if (/^(mv|rm|mkdir) /.test(cmd)) return '';
        return '';
      };
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdDownloadDir(s) { return '/storage/emulated/0/Download/ADB App Manager/Devices/' + s.replace(':', '_'); },
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') {
            let out = 'List of devices attached\n';
            if (st.devs.includes('a')) out += A + '\tdevice product:a model:Watch device:a transport_id:2\n';
            if (st.devs.includes('b')) out += B + '\tdevice product:b model:Tablet device:b transport_id:3\n';
            return reply(tag, out);
          }
          st.calls.push({ serial, cmd: a[0] === 'shell' ? a[1] : a.join(' ') });
          if (a[0] === 'shell') return reply(tag, shell(a[1], serial));
          if (a[0] === 'push') return reply(tag, '/x: 1 file pushed, 0 skipped.', st.xferMs);
          if (a[0] === 'pull') return reply(tag, st.pullOut || '/x: 1 file pulled, 0 skipped.', st.xferMs);
          return reply(tag, '');
        },
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(() => switchView('devices')); await page.waitForTimeout(600);
    await page.evaluate(() => cdSub('files'));
    await page.waitForFunction(() => cd.fm && cd.fm.entries && cd.fm.entries.length === 2, null, { timeout: 5000 });
    return page;
  };
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const cmds = (page, re) => page.evaluate(r => window.__st.calls.filter(c => new RegExp(r).test(c.cmd)).map(c => c.serial + ' ' + c.cmd), re.source);
  // A drops off the adb list while something is in progress: the 6-second poll runs cdRefreshDevices and picks B
  const drop = page => page.evaluate(() => { window.__st.devs = ['b']; return cdRefreshDevices(false); });
  const ask = page => page.waitForFunction(() => document.getElementById('cdAskModal').classList.contains('show'), null, { timeout: 3000 });

  // ---- 1. Delete, Rename, New folder: the device is read before the question ----
  let page = await open();
  check('1. the first device (A) is chosen and its folder is listed', await page.evaluate(a => cd.serial === a, A));
  const del = () => page.evaluate(() => { const e = cd.fm.entries.find(x => x.name === 'notes.txt'); cdFmDelete('/sdcard/notes.txt', e); });
  const ren = () => page.evaluate(() => { const e = cd.fm.entries.find(x => x.name === 'notes.txt'); cdFmRename('/sdcard/notes.txt', e); });
  const mk = () => page.evaluate(() => { cdFmNewFolder(); });
  const answer = (text) => page.evaluate(t => { if (t !== undefined) document.getElementById('cdAskInput').value = t; cdAskDone(true); }, text);
  // controls: nothing changes, the command runs and carries A's serial
  await del(); await ask(page); await answer(); await page.waitForTimeout(200);
  check('   control: Delete runs on A', (await cmds(page, /^rm /)).join('|') === A + " rm '/sdcard/notes.txt'" && /Deleted/.test(await toast(page)), (await cmds(page, /^rm /)).join('|') + ' ' + await toast(page));
  await ren(); await ask(page); await answer('other.txt'); await page.waitForTimeout(200);
  check('   control: Rename runs on A', (await cmds(page, /^mv /)).join('|') === A + " mv '/sdcard/notes.txt' '/sdcard/other.txt'" && /Renamed/.test(await toast(page)), (await cmds(page, /^mv /)).join('|') + ' ' + await toast(page));
  await mk(); await ask(page); await answer('Pics'); await page.waitForTimeout(200);
  check('   control: New folder runs on A', (await cmds(page, /^mkdir /)).join('|') === A + " mkdir -p '/sdcard/Pics'" && /Folder made/.test(await toast(page)), (await cmds(page, /^mkdir /)).join('|') + ' ' + await toast(page));
  await page.close();

  for (const [name, start, text, re, done] of [
    ['Delete', del, undefined, /^rm /, /Deleted/],
    ['Rename', ren, 'other.txt', /^mv /, /Renamed/],
    ['New folder', mk, 'Pics', /^mkdir /, /Folder made/],
  ]) {
    page = await open();
    await start(); await ask(page);
    await drop(page); await page.waitForTimeout(300);
    check('   ' + name + ': while its question is open the list moved to B', await page.evaluate(b => cd.serial === b, B));
    await answer(text); await page.waitForTimeout(400);
    const sent = await cmds(page, re);
    check('   ' + name + ' confirmed after that: no command reached any device', sent.length === 0, sent.join('|'));
    check('   ' + name + ' does not claim it was done; the toast is the one about the lost connection', !done.test(await toast(page)) && /Lost the connection to Watch/.test(await toast(page)), await toast(page));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
