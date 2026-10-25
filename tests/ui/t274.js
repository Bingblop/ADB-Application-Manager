// Connected Devices, Console and Logcat: when the device list moves to another device on its own (the one in use dropped off), a console command that is still
// running is cancelled on the old device and the Run / Stop button still stops it (#187), and a live log that was playing stops instead of showing the new
// device's log under a Pause button (#188); the sub-tab still reads the new device's log once, as it does for a device picked by hand.
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
      // every command with the device it went to; a console command ("sleep") never answers until it is cancelled
      const st = window.__st = { calls: [], cancels: [], devs: ['a', 'b'], hung: {} };
      const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdCancel(tag) { st.cancels.push(tag); if (st.hung[tag]) { delete st.hung[tag]; reply(tag, 'Cancelled'); } },
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') {
            let out = 'List of devices attached\n';
            if (st.devs.includes('a')) out += A + '\tdevice product:a model:Watch device:a transport_id:2\n';
            if (st.devs.includes('b')) out += B + '\tdevice product:b model:Tablet device:b transport_id:3\n';
            return reply(tag, out);
          }
          const cmd = a[0] === 'shell' ? a[1] : a.join(' ');
          st.calls.push({ serial, cmd, tag });
          if (a[0] === 'shell' && /^for p in/.test(a[1])) return reply(tag, ['ro.product.model=M-' + serial, 'ro.build.version.release=14', 'ro.build.version.sdk=34'].join('\n'));
          if (a[0] === 'shell' && /^sleep/.test(a[1])) { st.hung[tag] = true; return; }
          if (a[0] === 'shell' && /^logcat -d/.test(a[1])) return reply(tag, '10-14 11:00:00.000  100  100 I Tag' + serial.slice(0, 2) + ': line from ' + serial);
          return reply(tag, '');
        },
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(() => switchView('devices')); await page.waitForTimeout(600);
    return page;
  };
  const drop = page => page.evaluate(() => { window.__st.devs = ['b']; return cdRefreshDevices(false); });
  const sent = (page, re) => page.evaluate(r => window.__st.calls.filter(c => new RegExp(r).test(c.cmd)).map(c => c.serial + ' ' + c.cmd), re.source);
  const btn = page => page.evaluate(() => document.getElementById('cdRunBtn').innerText);

  // ---- 1. Console: a running command and an automatic change of device ----
  let page = await open();
  await page.evaluate(() => cdSub('console'));
  await page.evaluate(() => { document.getElementById('cdInput').value = 'sleep 600'; cdRunOrStop(); });
  await page.waitForTimeout(200);
  check('1. the command runs on A and the button says Stop', (await sent(page, /^sleep/)).join('|') === A + ' sleep 600' && await btn(page) === 'Stop', (await sent(page, /^sleep/)).join('|') + ' ' + await btn(page));
  await drop(page); await page.waitForTimeout(300);
  check('   A drops off and B is picked', await page.evaluate(b => cd.serial === b, B));
  check('   the command on A is cancelled', await page.evaluate(() => window.__st.cancels.length === 1 && /^cdterm/.test(window.__st.cancels[0])), await page.evaluate(() => JSON.stringify(window.__st.cancels)));
  check('   once it has ended the button says Run', await btn(page) === 'Run', await btn(page));
  await page.evaluate(() => { document.getElementById('cdInput').value = 'sleep 700'; cdRunOrStop(); });
  await page.waitForTimeout(200);
  check('   a new command goes to B and Stop stops that one', (await sent(page, /^sleep 700/)).join('|') === B + ' sleep 700' && await btn(page) === 'Stop', (await sent(page, /^sleep 700/)).join('|'));
  await page.evaluate(() => cdRunOrStop()); await page.waitForTimeout(200);
  check('   ... pressing it cancels the command on B and starts nothing', await page.evaluate(() => window.__st.cancels.length === 2) && (await sent(page, /^sleep/)).length === 2 && await btn(page) === 'Run', await page.evaluate(() => JSON.stringify(window.__st.cancels)));
  await page.close();

  // a slow cancel: the old command has not answered yet when the device changes, so the button keeps saying Stop and pressing it does not start the typed line
  page = await open();
  await page.evaluate(() => { cdSub('console'); window.AndroidBridge.cdCancel = tag => { window.__st.cancels.push(tag); }; });
  await page.evaluate(() => { document.getElementById('cdInput').value = 'sleep 600'; cdRunOrStop(); });
  await page.waitForTimeout(200);
  await drop(page); await page.waitForTimeout(300);
  await page.evaluate(() => { document.getElementById('cdInput').value = 'rm -rf /sdcard/x'; cdRunOrStop(); });
  await page.waitForTimeout(200);
  check('2. while the old command is still ending, Run/Stop does not start the typed line on B', (await sent(page, /^rm /)).length === 0 && await btn(page) === 'Stop', (await sent(page, /^rm /)).join('|') + ' ' + await btn(page));
  await page.close();

  // ---- 3. Logcat: the live log stops when the device changes on its own, and when one is picked by hand ----
  for (const how of ['automatic', 'by hand']) {
    page = await open();
    await page.evaluate(() => cdSub('logcat'));
    await page.evaluate(() => cdLcTogglePlay()); await page.waitForTimeout(300);
    check('3. ' + how + ': the live log plays on A', (await sent(page, /^logcat -d/)).every(c => c.startsWith(A + ' ')) && (await sent(page, /^logcat -d/)).length >= 1 && await page.evaluate(() => !!cd.lcTimer));
    if (how === 'automatic') await drop(page); else await page.evaluate(b => cdSelect(b), B);
    await page.waitForTimeout(3200);
    // the sub-tab reads the new device's log once on its own (-t 1000, the default since v7.14); the live poll reads -t 300
    const liveB = (await sent(page, /^logcat -d .* -t 300 /)).filter(c => c.startsWith(B + ' '));
    check('   after the change to B the live poll does not read B and the button says Play', liveB.length === 0 && await page.evaluate(() => !cd.lcTimer && document.getElementById('cdLcPlay').innerText === 'Play'), liveB.join('|'));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
