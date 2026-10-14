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
      const st = window.__st = { calls: [], devs: ['a', 'b'], xferMs: 0, pmMs: 0, pullOut: null, pushOut: null };
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
          if (a[0] === 'shell') return reply(tag, shell(a[1], serial), /^pm path/.test(a[1]) ? st.pmMs : 0);
          if (a[0] === 'push') return reply(tag, st.pushOut || '/x: 1 file pushed, 0 skipped.', st.xferMs);
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

  // ---- 2. A multi-file push (Send a file here) and the APK pull (Apps menu) keep to the device they started on ----
  const files = [1, 2, 3].map(i => ({ name: 'f' + i + '.bin', path: '/cache/cd_pick/1/f' + i + '.bin', size: 10 }));
  const xfers = (page, verb) => page.evaluate(v => window.__st.calls.filter(c => c.cmd.split(' ')[0] === v).map(c => c.serial + ' ' + c.cmd), verb);
  const pushStart = page => page.evaluate(f => { window.__st.xferMs = 250; window.onCdPicked({ tag: 'push', files: f }); }, files);
  const pullStart = page => page.evaluate(() => { window.__st.xferMs = 250; cdPullApk('com.example.app'); });
  const inFlight = (page, verb, n) => page.waitForFunction(([v, n]) => window.__st.calls.filter(c => c.cmd.split(' ')[0] === v).length >= n, [verb, n], { timeout: 3000 });
  const settle = page => page.waitForTimeout(1400);
  page = await open();
  await pushStart(page); await settle(page);
  check('2. push, no change of device: the three files go to A and the summary counts them', (await xfers(page, 'push')).join('|') === [1, 2, 3].map(i => A + ' push /cache/cd_pick/1/f' + i + '.bin /sdcard/').join('|') && /3 files sent to \/sdcard/.test(await toast(page)), (await xfers(page, 'push')).join('|') + ' ' + await toast(page));
  await page.close();
  page = await open();
  await pushStart(page); await inFlight(page, 'push', 1);
  await drop(page); await settle(page);
  const pushed = await xfers(page, 'push');
  check('   push, A drops after the first file and B is picked: nothing is pushed to B', await page.evaluate(b => cd.serial === b, B) && pushed.every(c => c.startsWith(A + ' ')) && pushed.length === 1, pushed.join('|'));
  check('   and it does not say all three were sent', !/files sent/.test(await toast(page)) && /The push did not finish/.test(await toast(page)), await toast(page));
  await page.close();
  page = await open();
  await pullStart(page); await settle(page);
  check('3. APK pull, no change of device: the three files come from A and are saved', (await xfers(page, 'pull')).length === 3 && (await xfers(page, 'pull')).every(c => c.startsWith(A + ' pull /data/app/x/')) && /Saved to Download\/ADB App Manager\/Devices\/192\.168\.1\.20_5555/.test(await toast(page)), (await xfers(page, 'pull')).join('|') + ' ' + await toast(page));
  await page.close();
  page = await open();
  await pullStart(page); await inFlight(page, 'pull', 1);
  await drop(page); await settle(page);
  const pulled = await xfers(page, 'pull');
  check('   APK pull, A drops after the first file and B is picked: nothing is pulled from B', await page.evaluate(b => cd.serial === b, B) && pulled.every(c => c.startsWith(A + ' ')) && pulled.length === 1, pulled.join('|'));
  check('   and it does not say "Saved"', !/Saved to/.test(await toast(page)) && /The pull did not finish/.test(await toast(page)), await toast(page));
  await page.close();
  page = await open();
  await page.evaluate(() => { window.__st.pmMs = 300; cdPullApk('com.example.app'); });
  await page.evaluate(() => { window.__st.devs = ['b']; return cdRefreshDevices(false); }); await settle(page);
  check('   APK pull, A drops while the device is still asked for the APK path: no pull at all', (await xfers(page, 'pull')).length === 0 && !/Saved to/.test(await toast(page)), (await xfers(page, 'pull')).join('|') + ' ' + await toast(page));
  await page.close();

  // ---- 3. A pull or push that skipped files is not "Saved" / "sent": adb's summary line comes last, the complaints before it ----
  const SKIPPED = "pull: building file list...\nadb: error: failed to copy '/sdcard/Folder/secret.db' to '/dl/Folder/secret.db': remote open failed: Permission denied\n/sdcard/Folder/: 3 files pulled, 1 skipped. 1.2 MB/s (4096 bytes in 0.003s)";
  const SKIP_ONLY = '/sdcard/Folder/: 3 files pulled, 1 skipped. 1.2 MB/s (4096 bytes in 0.003s)';
  const CLEAN = '/sdcard/Folder/: 4 files pulled, 0 skipped. 1.2 MB/s (4096 bytes in 0.003s)';
  const pull = async (out) => { page = await open(); await page.evaluate(o => { window.__st.pullOut = o; cdFmPull('/sdcard/Folder'); }, out); await page.waitForTimeout(500); const t = await toast(page); await page.close(); return t; };
  let t = await pull(CLEAN);
  check('4. a folder pull with nothing skipped says Saved', /^Saved to Download\/ADB App Manager\/Devices\/192\.168\.1\.20_5555$/.test(t), t);
  t = await pull(SKIPPED);
  check('   a folder pull with an error line and a skipped file does not say Saved, it shows the error', !/Saved/.test(t) && /failed to copy '\/sdcard\/Folder\/secret\.db'.*Permission denied/.test(t), t);
  t = await pull(SKIP_ONLY);
  check('   a folder pull that only reports a skipped file does not say Saved either', !/Saved/.test(t) && /3 files pulled, 1 skipped/.test(t), t);
  page = await open();
  await page.evaluate(() => { window.__st.pullOut = '/data/app/x/base.apk: 1 file pulled, 0 skipped.'; cdPullApk('com.example.app'); }); await page.waitForTimeout(700);
  t = await toast(page); await page.close();
  check('   the APK pull with nothing skipped says Saved', /^Saved to /.test(t), t);
  page = await open();
  await page.evaluate(() => { window.__st.pullOut = "pull: building file list...\nadb: error: failed to copy '/data/app/x/base.apk' to '/dl/a.apk': Permission denied\n/data/app/x/base.apk: 0 files pulled, 1 skipped."; cdPullApk('com.example.app'); }); await page.waitForTimeout(700);
  t = await toast(page); await page.close();
  check('   the APK pull that skipped a file does not say Saved', !/Saved/.test(t) && /Permission denied/.test(t), t);
  page = await open();
  await page.evaluate(f => { window.__st.pushOut = '/cache/cd_pick/1/f1.bin: 0 files pushed, 1 skipped.'; window.onCdPicked({ tag: 'push', files: [f] }); }, files[0]); await page.waitForTimeout(500);
  t = await toast(page); await page.close();
  check('   a push that skipped the file does not say it was sent', !/sent to/.test(t) && /1 skipped/.test(t), t);

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
