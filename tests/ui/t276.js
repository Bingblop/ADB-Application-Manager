// Connected Devices, Apps and Logcat: when another device is chosen while the list of apps (or the log) of the old one is still being read, the new
// device's list (or log) is read once the old read returns, instead of the tab staying on "Reading the apps of the device..." until Reload.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async () => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
      // the first read of the apps and of the log is held until the script lets it go
      const st = window.__st = { calls: [], held: [], hold: { apps: true, log: true } };
      const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdCancel() {},
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') return reply(tag, 'List of devices attached\n' + A + '\tdevice product:a model:Watch device:a transport_id:2\n' + B + '\tdevice product:b model:Tablet device:b transport_id:3\n');
          const cmd = a[0] === 'shell' ? a[1] : a.join(' ');
          st.calls.push(serial + ' ' + cmd.split(';')[0].slice(0, 16));
          if (/^for p in/.test(cmd)) return reply(tag, 'ro.product.model=M-' + serial + '\nro.build.version.release=14\nro.build.version.sdk=34');
          if (/^echo '#ALL'/.test(cmd)) {
            const out = "#ALL\npackage:/data/app/x/base.apk=com.from." + serial.slice(0, 2) + "\n#INST\npackage:com.from." + serial.slice(0, 2) + "\n#DIS\n#SYS\n";
            if (st.hold.apps) { st.hold.apps = false; st.held.push(() => reply(tag, out)); return; }
            return reply(tag, out);
          }
          if (/^logcat -d/.test(cmd)) {
            const out = '10-14 11:00:00.000  100  100 I Tag: line from ' + serial;
            if (st.hold.log) { st.hold.log = false; st.held.push(() => reply(tag, out)); return; }
            return reply(tag, out);
          }
          return reply(tag, '');
        },
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(a => kvSet('cd_ui', { serial: a, sub: 'apps' }), A);
    await page.evaluate(() => switchView('devices')); await page.waitForTimeout(700);
    return page;
  };
  const sent = (page, re) => page.evaluate(r => window.__st.calls.filter(c => new RegExp(r).test(c)), re.source);
  const release = page => page.evaluate(() => { window.__st.held.splice(0).forEach(f => f()); });

  // ---- Apps ----
  let page = await open();
  await page.evaluate(() => cdSub('apps')); await page.waitForTimeout(200);
  check('1. the apps of A are being read', (await sent(page, /echo '#ALL'/)).join('|') === A + " echo '#ALL'" && await page.evaluate(() => cd.appsBusy === true), (await sent(page, /echo '#ALL'/)).join('|'));
  await page.evaluate(b => cdSelect(b), B); await page.waitForTimeout(400);
  await release(page); await page.waitForTimeout(400);
  check('   after B is chosen and the read of A returns, the apps of B are read', (await sent(page, /echo '#ALL'/)).filter(c => c.startsWith(B + ' ')).length === 1, (await sent(page, /echo '#ALL'/)).join('|'));
  check('   the list shows the app of B, not a "Reading" line', await page.evaluate(() => Array.isArray(cd.apps) && cd.apps.length === 1 && cd.apps[0].pkg === 'com.from.10' && !/Reading/.test(document.getElementById('cdAppCount').innerText)),
    await page.evaluate(() => JSON.stringify(cd.apps) + ' ' + document.getElementById('cdAppCount').innerText));
  await page.close();

  // ---- Logcat ----
  page = await open();
  await page.evaluate(() => cdSub('logcat')); await page.waitForTimeout(200);
  check('2. the log of A is being read', (await sent(page, /^\S+ logcat -d/)).join('|') === A + ' logcat -d -v thr' && await page.evaluate(() => cd.lcBusy === true), (await sent(page, /logcat/)).join('|'));
  await page.evaluate(b => cdSelect(b), B); await page.waitForTimeout(400);
  await release(page); await page.waitForTimeout(400);
  check('   after B is chosen and the read of A returns, the log of B is read and shown', (await sent(page, /logcat/)).filter(c => c.startsWith(B + ' ')).length === 1 && await page.evaluate(b => cd.lcText.includes('line from ' + b), B),
    (await sent(page, /logcat/)).join('|') + ' ' + await page.evaluate(() => cd.lcText));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
