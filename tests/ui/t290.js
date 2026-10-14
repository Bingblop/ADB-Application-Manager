// Connected Devices, Logcat: the live log stops when the device list moves to another device by itself. The 2.5-second timer used to keep running after the device that was
// picked dropped off the list and the next one was picked, so it went on drawing that device's log under a Pause button nobody had pressed for it. (Opening the Logcat sub-tab for the new device loads its log once, with the button on Play; that is not the timer.) A device change by hand stopped it already.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + extra)); }
const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async () => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
      const st = window.__st = { calls: [], devs: ['a', 'b'] };
      const reply = (tag, out) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), 8);
      const shell = (cmd, serial) => {
        if (/^for p in/.test(cmd)) return ['ro.product.manufacturer=x', 'ro.product.model=M-' + serial, 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a', 'ro.sf.lcd_density=320', 'wmsize=Physical size: 450x450 ', 'wmdens=Physical density: 320 ', 'batt=  level: 83   status: 3 '].join('\n');
        if (/^logcat -d/.test(cmd)) return '10-03 04:00:01.000  100  100 I Tag' + (serial === A ? 'A' : 'B') + ': hello from ' + (serial === A ? 'A' : 'B');
        return '';
      };
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdDownloadDir(s) { return '/storage/emulated/0/Download/ADB App Manager/Devices/' + s.replace(':', '_'); },
        cdCancel() {},
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') {
            let out = 'List of devices attached\n';
            if (st.devs.includes('a')) out += A + '\tdevice product:a model:Watch device:a transport_id:2\n';
            if (st.devs.includes('b')) out += B + '\tdevice product:b model:Tablet device:b transport_id:3\n';
            return reply(tag, out);
          }
          st.calls.push({ serial, cmd: a[0] === 'shell' ? a[1] : a.join(' ') });
          return reply(tag, a[0] === 'shell' ? shell(a[1], serial) : '');
        },
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(() => switchView('devices')); await page.waitForTimeout(600);
    await page.evaluate(() => cdSub('logcat'));
    await page.waitForFunction(() => cd.serial && cd.info, null, { timeout: 5000 });
    return page;
  };
  const lc = (page) => page.evaluate(() => window.__st.calls.filter(c => /^logcat/.test(c.cmd)).map(c => c.serial));
  const btn = (page) => page.evaluate(() => document.getElementById('cdLcPlay').innerText);
  const pane = (page) => page.evaluate(() => document.getElementById('cdLcOut').innerText);

  // control: nothing changes, the timer goes on asking device A
  let page = await open();
  check('1. the first device (A) is chosen', await page.evaluate(a => cd.serial === a, A));
  await page.evaluate(() => cdLcTogglePlay());
  await page.waitForFunction(() => document.getElementById('cdLcOut').innerText.includes('hello from A'), null, { timeout: 3000 });
  check('   Play: the button says Pause and A\'s log is drawn', (await btn(page)) === 'Pause' && /hello from A/.test(await pane(page)), await btn(page));
  await page.waitForTimeout(2900);
  let asked = await lc(page);
  check('   control: with the same device the timer keeps asking A', asked.length >= 2 && asked.every(s => s === A), asked.join('|'));
  await page.close();

  // A drops off the adb list while the live log runs: the 6-second poll runs cdRefreshDevices and picks B
  page = await open();
  await page.evaluate(() => cdLcTogglePlay());
  await page.waitForFunction(() => document.getElementById('cdLcOut').innerText.includes('hello from A'), null, { timeout: 3000 });
  await page.evaluate(() => { window.__st.devs = ['b']; return cdRefreshDevices(false); });
  await page.waitForTimeout(300);
  check('2. A dropped and B was picked by itself', await page.evaluate(b => cd.serial === b, B));
  check('   the live log is stopped: the button says Play again', (await btn(page)) === 'Play', await btn(page));
  const before = (await lc(page)).length;
  await page.waitForTimeout(3200);
  asked = (await lc(page)).slice(before);
  check('   no logcat request goes to B after the change', asked.length === 0, asked.join('|'));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
