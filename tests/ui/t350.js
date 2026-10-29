// Connected Devices, ADB Stub for Wear OS watches: a watch shows an ADB Stub row with Install when the stub is not on it; Install sends the APK this app
// carries and starts it; when a watch with the stub connects, the stub is started once (not again on Reload details) unless "Start ADB Stub on connect" is
// off; Disconnect stops the stub on the watch before adb lets go of it; a watch with an older stub is offered Update; a phone or tablet has no row, and
// neither does a build without the stub. v7.19.3: a watch that connects while another device is the chosen one is started too, an older stub that
// still lets go by itself (version 2) is started on connect, and the phone sends the watch's adb port with starts and renewals.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const W = '192.168.1.30:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // one device W: a watch (or a tablet), the stub installed or not, running or not, in this version or an older one. The mock keeps every call in order.
  const open = async (o) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((o) => {
      const W = '192.168.1.30:5555';
      const st = window.__st = { log: [], up: true, inst: !!o.inst, ver: o.ver || 1, run: !!o.run, watch: o.watch !== false, bundled: o.bundled !== false, other: !!o.other };
      const P = '192.168.1.40:5555';
      const reply = (r) => setTimeout(() => window.onCdResult(r), 8);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdStubInfo() { return JSON.stringify({ bundled: st.bundled, version: 3, auto: 2 }); },
        cdStub(tag, serial, action, phone) {
          st.log.push('stub ' + action + ' ' + serial);
          let ok = true, out = '';
          if (action === 'install') { st.inst = true; st.ver = 3; out = 'Performing Streamed Install\nSuccess'; }
          if (action === 'start') { st.run = true; out = 'Starting: Intent { cmp=com.bloatware.bingblop.adbstub/.StubActivity (has extras) }'; }
          if (action === 'renew') out = 'Broadcast completed: result=0';
          if (action === 'stop') { st.run = false; out = 'stopped'; }
          reply({ tag, ok, out, installed: st.inst, version: st.inst ? st.ver : 0, running: st.inst && st.run });
        },
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') return reply({ tag, out: 'List of devices attached\n' + (st.other ? P + '\tdevice product:p model:Pixel_9 device:p transport_id:1\n' : '') + (st.up ? W + '\tdevice product:w model:Galaxy_Watch6 device:w transport_id:2\n' : '') });
          if (a[0] === 'shell' && /^getprop ro\.build\.characteristics/.test(a[1])) { st.log.push('probe ' + serial); return reply({ tag, out: (serial === W && st.watch ? 'watch' : 'default') + '\n' }); }
          if (a[0] === 'disconnect') { st.log.push('adb disconnect ' + a[1]); st.up = false; return reply({ tag, out: 'disconnected ' + a[1] }); }
          if (a[0] === 'shell' && /^for p in/.test(a[1])) return reply({ tag, out: ['ro.product.manufacturer=samsung', 'ro.product.model=SM-R930', 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a',
            'ro.sf.lcd_density=320', 'ro.build.characteristics=' + (serial === W && st.watch ? 'watch' : 'tablet'), 'wmsize=Physical size: 450x450 ', 'wmdens=Physical density: 320 ', 'batt=  level: 83   status: 3 '].join('\n') });
          return reply({ tag, out: '' });
        },
      };
    }, o);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    if (o.auto === false) await page.evaluate(() => kvSet('cd_stub_auto', false));
    await page.evaluate(() => switchView('devices'));
    await page.waitForFunction(() => cd.info && cd.info.serial, null, { timeout: 5000 });
    await page.waitForTimeout(300);
    return page;
  };
  const log = page => page.evaluate(() => window.__st.log.join(' | '));
  const row = page => page.evaluate(() => { const r = document.getElementById('cdStub'), btn = document.getElementById('cdStubBtn'); return r ? { text: r.innerText.replace(/\s+/g, ' ').trim(), btn: btn ? btn.innerText.trim() : '' } : null; });
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const menu = page => page.evaluate(() => { cdDeviceMenu(); return cd.acts.map(x => x.label); });
  const act = (page, label) => page.evaluate(l => { const i = cd.acts.findIndex(x => x.label === l); cdSheetAct(i); }, label);

  // ---- 1. a watch without the stub: Install, which sends it and starts it ----
  let page = await open({ inst: false });
  let r = await row(page);
  check('1. a watch without the stub: the ADB Stub row offers Install', r && r.btn === 'Install' && /ADB Stub/.test(r.text) && /screen on/.test(r.text), JSON.stringify(r));
  check('   on connect only the status was read: nothing started', await log(page) === 'stub status ' + W, await log(page));
  await page.screenshot({ path: 'install.png' });
  await page.click('#cdStubBtn'); await page.waitForTimeout(300);
  r = await row(page);
  check('   Install sends the stub and starts it (start on connect is on by default)', await log(page) === ['stub status ' + W, 'stub install ' + W, 'stub start ' + W].join(' | '), await log(page));
  check('   then the row says it is running and offers Stop', r && r.btn === 'Stop' && /Running/i.test(r.text) && /Disconnect stops it/.test(r.text), JSON.stringify(r));
  check('   and the toast says so', /ADB Stub started/.test(await toast(page)), await toast(page));

  // ---- 2. Disconnect stops the stub before adb lets go of the watch ----
  const items = await menu(page);
  check('2. the device menu has the start-on-connect switch and Disconnect', items.includes('Start ADB Stub on connect: On') && items.includes('Disconnect'), items.join(', '));
  await page.evaluate(() => { window.__st.log = []; });
  await act(page, 'Disconnect'); await page.waitForTimeout(400);
  check('   Disconnect: the stub is stopped first, then adb disconnects', await log(page) === ['stub stop ' + W, 'adb disconnect ' + W].join(' | '), await log(page));
  await page.close();

  // ---- 3. a watch with the stub, not running: started once on connect, not again on Reload details; back after a drop: started again ----
  page = await open({ inst: true, ver: 3, run: false });
  check('3. the stub is installed and not running: it is started when the watch connects', await log(page) === ['stub status ' + W, 'stub start ' + W].join(' | '), await log(page));
  await page.screenshot({ path: 'running.png' });
  r = await row(page);
  check('   the row offers Stop', r && r.btn === 'Stop', JSON.stringify(r));
  await page.evaluate(() => { cd.lastKey = ''; return cdLoadInfo(); }); await page.waitForTimeout(200);
  check('   Reload details does not ask or start it again', (await log(page)).split(' | ').length === 2, await log(page));
  await page.evaluate(() => { window.__st.run = false; window.__st.up = false; return cdRefreshDevices(false); }); await page.waitForTimeout(200);
  await page.evaluate(() => { window.__st.up = true; return cdRefreshDevices(false); }); await page.waitForTimeout(400);
  check('   the watch dropped and came back: read and started again', (await log(page)).split(' | ').filter(x => x === 'stub start ' + W).length === 2, await log(page));
  await page.click('#cdStubBtn'); await page.waitForTimeout(300);
  r = await row(page);
  check('   Stop stops it; the row offers Start', /stub stop/.test(await log(page)) && r && r.btn === 'Start' && /Installed, not running/.test(r.text), JSON.stringify(r));
  await page.close();

  // ---- a running watch refreshes the supplied port as soon as it reconnects ----
  page = await open({ inst: true, ver: 3, run: true });
  check('   the already-running stub gets a renewal with the current endpoint on connect', await log(page) === ['stub status ' + W, 'stub renew ' + W].join(' | '), await log(page));
  await page.close();

  // ---- 4. start on connect switched off ----
  page = await open({ inst: true, ver: 3, run: false, auto: false });
  await page.evaluate(() => { cd.stub = {}; cd.lastKey = ''; return cdLoadInfo(); }); await page.waitForTimeout(300);
  check('4. start on connect off: only the status is read', !/stub start/.test(await log(page)), await log(page));
  check('   the menu shows it off', (await menu(page)).includes('Start ADB Stub on connect: Off'));
  await act(page, 'Start ADB Stub on connect: Off'); await page.waitForTimeout(100);
  check('   and turns it back on', await page.evaluate(() => cdStubAutoOn()) && /starts when the watch connects/.test(await toast(page)), await toast(page));
  await page.close();

  // ---- 5. an older stub on the watch: Update; one without the lease (version 1) is not started before that, one with it (version 2) is ----
  page = await open({ inst: true, ver: 1, run: false });
  r = await row(page);
  check('5. an older stub without the lease: Update, and it was not started', r && r.btn === 'Update' && !/stub start/.test(await log(page)), JSON.stringify(r) + ' ' + await log(page));
  await page.close();
  page = await open({ inst: true, ver: 2, run: false });
  r = await row(page);
  check('   version 2 (it lets go by itself): started when the watch connects, and still offered Update', await log(page) === ['stub status ' + W, 'stub start ' + W].join(' | ') && r && r.btn === 'Update' && /A newer version/.test(r.text) && /RUNNING/i.test(r.text), JSON.stringify(r) + ' ' + await log(page));
  await page.click('#cdStubBtn'); await page.waitForTimeout(300);
  r = await row(page);
  check('   Update installs the newer one and starts it', /stub install/.test(await log(page)) && r && r.btn === 'Stop' && !/newer version/.test(r.text), JSON.stringify(r) + ' ' + await log(page));
  await page.close();

  // ---- 5b. a watch that connects while a phone is the chosen device: asked once what it is, then started like the chosen one ----
  page = await open({ other: true, inst: true, ver: 3, run: false });
  await page.waitForTimeout(400);
  const chosen = await page.evaluate(() => cd.serial);
  check('5b. the phone is the chosen device (first in the list)', chosen === '192.168.1.40:5555', chosen);
  check('   the watch was asked what it is once, then its stub was read and started', await log(page) === ['probe ' + W, 'stub status ' + W, 'stub start ' + W].join(' | '), await log(page));
  await page.evaluate(() => cdRefreshDevices(false)); await page.waitForTimeout(300);
  await page.evaluate(() => cdRefreshDevices(false)); await page.waitForTimeout(300);
  check('   the next lists do not ask or start again', (await log(page)).split(' | ').length === 3, await log(page));
  await page.evaluate(() => { window.__st.run = false; window.__st.up = false; return cdRefreshDevices(false); }); await page.waitForTimeout(200);
  await page.evaluate(() => { window.__st.up = true; return cdRefreshDevices(false); }); await page.waitForTimeout(500);
  check('   the watch dropped and came back: asked and started again', (await log(page)).split(' | ').filter(x => x === 'stub start ' + W).length === 2, await log(page));
  await page.close();
  page = await open({ other: true, inst: true, ver: 3, run: false, auto: false });
  await page.waitForTimeout(300);
  check('   start on connect off: the other watch is not even asked', await log(page) === '', await log(page));
  await page.close();
  page = await open({ other: true, watch: false });
  await page.waitForTimeout(300);
  check('   a second device that is no watch is asked once and gets no stub call', await log(page) === 'probe ' + W, await log(page));
  await page.close();

  // ---- 6. not a watch / no stub in this build ----
  page = await open({ watch: false });
  check('6. a tablet: no ADB Stub row and no stub call', await row(page) === null && await log(page) === '', await log(page));
  await page.close();
  page = await open({ bundled: false });
  check('   a build without the stub: no row and no stub call', await row(page) === null && await log(page) === '', await log(page));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
