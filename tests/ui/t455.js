// Connected Devices, ADB Stub for Wear OS watches (review fixes of #233): while the stub runs on a connected watch the phone renews its lease once a
// minute, whichever view is open (without renewals the stub lets go of the watch by itself after 5 minutes); a renewal whose answer says the stub
// is no longer running shows Start again and the renewals stop; nothing is renewed while the stub is not running. A failed install says why (the
// Failure [...] line adb prints after "Performing Streamed Install"), and what to do when the watch has a stub signed with another key.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const W = '192.168.1.30:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // one watch W with the stub (version 2); the mock keeps every stub call in order. lease: false makes a renewal answer "not running" (the lease ran out).
  const open = async (o) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((o) => {
      const W = '192.168.1.30:5555';
      const st = window.__st = { log: [], up: true, inst: o.inst !== false, ver: o.ver || 2, run: !!o.run, lease: true, timers: [], installOut: o.installOut || '' };
      const si = window.setInterval.bind(window), ci = window.clearInterval.bind(window);
      window.setInterval = (fn, ms, ...a) => { const id = si(fn, ms, ...a); st.timers.push({ id, ms, name: fn && fn.name || '' }); return id; };
      window.clearInterval = (id) => { st.timers = st.timers.filter(t => t.id !== id); ci(id); };
      const reply = (r) => setTimeout(() => window.onCdResult(r), 8);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdStubInfo() { return JSON.stringify({ bundled: true, version: 2 }); },
        cdStub(tag, serial, action, arg) {
          st.log.push('stub ' + action + ' ' + serial);
          let ok = true, out = '';
          if (action === 'install') { if (st.installOut) { ok = false; out = st.installOut; } else { st.inst = true; st.ver = 2; out = 'Performing Streamed Install\nSuccess'; } }
          if (action === 'start') { st.run = true; out = 'Starting: Intent { cmp=com.bloatware.bingblop.adbstub/.StubActivity (has extras) }'; }
          if (action === 'stop') { st.run = false; out = 'stopped'; }
          if (action === 'renew') { if (!st.lease) st.run = false; out = 'Broadcasting: Intent { act=com.bloatware.bingblop.adbstub.RENEW }\nBroadcast completed: result=0'; }
          reply({ tag, ok, out, installed: st.inst, version: st.inst ? st.ver : 0, running: st.inst && st.run });
        },
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') return reply({ tag, out: 'List of devices attached\n' + (st.up ? W + '\tdevice product:w model:Galaxy_Watch6 device:w transport_id:2\n' : '') });
          if (a[0] === 'shell' && /^for p in/.test(a[1])) return reply({ tag, out: ['ro.product.manufacturer=samsung', 'ro.product.model=SM-R930', 'ro.build.version.release=14', 'ro.build.version.sdk=34',
            'ro.build.characteristics=watch', 'batt=  level: 83   status: 3 '].join('\n') });
          return reply({ tag, out: '' });
        },
      };
    }, o);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(() => switchView('devices'));
    await page.waitForFunction(() => cd.info && cd.info.serial, null, { timeout: 5000 });
    await page.waitForTimeout(300);
    return page;
  };
  const log = page => page.evaluate(() => window.__st.log.join(' | '));
  const renewTimer = page => page.evaluate(() => window.__st.timers.filter(t => t.name === 'cdStubRenew').map(t => t.ms).join(','));
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const row = page => page.evaluate(() => { const r = document.getElementById('cdStub'), btn = document.getElementById('cdStubBtn'); return r ? { text: r.innerText.replace(/\s+/g, ' ').trim(), btn: btn ? btn.innerText.trim() : '' } : null; });

  // ---- 1. the stub runs: renewed once a minute, also from another view ----
  let page = await open({ run: false });
  check('1. the stub was started on connect', await log(page) === ['stub status ' + W, 'stub start ' + W].join(' | '), await log(page));
  check('   a renewal runs once a minute while it runs', await renewTimer(page) === '60000', await renewTimer(page));
  await page.evaluate(() => { window.__st.log = []; return cdStubRenew(); }); await page.waitForTimeout(100);
  check('   a renewal is sent to the watch', await log(page) === 'stub renew ' + W, await log(page));
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(100);
  check('   another view is opened (the device list is not polled there): the renewal keeps running', await page.evaluate(() => cd.poll === null) && await renewTimer(page) === '60000', await renewTimer(page));
  await page.evaluate(() => { window.__st.log = []; return cdStubRenew(); }); await page.waitForTimeout(100);
  check('   ... and still renews', await log(page) === 'stub renew ' + W, await log(page));
  await page.evaluate(() => switchView('devices')); await page.waitForTimeout(300);

  // ---- 2. the answer says the stub is not running any more (its lease ran out, or it was stopped on the watch) ----
  await page.evaluate(() => { window.__st.lease = false; window.__st.log = []; return cdStubRenew(); }); await page.waitForTimeout(150);
  let r = await row(page);
  check('2. a renewal finds the stub stopped: the row offers Start', r && r.btn === 'Start' && /Installed, not running/.test(r.text), JSON.stringify(r));
  check('   and the renewals stop', await renewTimer(page) === '', await renewTimer(page));
  await page.evaluate(() => { window.__st.log = []; return cdStubRenew(); }); await page.waitForTimeout(100);
  check('   nothing is renewed while it is not running', await log(page) === '', await log(page));
  await page.click('#cdStubBtn'); await page.waitForTimeout(300);
  check('   Start: the renewals run again', await renewTimer(page) === '60000', await renewTimer(page));
  await page.close();

  // ---- 3. no stub on the watch: no renewal ----
  page = await open({ inst: false });
  check('3. a watch without the stub: no renewal', await renewTimer(page) === '' && !/renew/.test(await log(page)), await log(page));
  await page.close();

  // ---- 4. a failed install says why ----
  const INCOMPATIBLE = 'Performing Streamed Install\nadb: failed to install /data/user/0/com.bloatware.bingblop/cache/adb_stub/adb_stub_80_2.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.bloatware.bingblop.adbstub signatures do not match newer version; ignoring!]';
  page = await open({ ver: 1, installOut: INCOMPATIBLE });
  r = await row(page);
  check('4. an older stub on the watch: Update', r && r.btn === 'Update', JSON.stringify(r));
  await page.click('#cdStubBtn'); await page.waitForTimeout(300);
  check('   Update fails, signed with another key: the toast says to uninstall it first', await toast(page) === 'The ADB Stub on the watch was signed with another key. Uninstall it from the watch, then tap Install.', await toast(page));
  const fails = await page.evaluate(() => [
    cdStubFail('Performing Streamed Install\nadb: failed to install /x/adb_stub.apk: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]'),
    cdStubFail('Performing Streamed Install\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE]'),
    cdStubFail('error: device offline'),
    cdStubFail('Performing Streamed Install\nadb: connect error for write: closed'),
    cdStubFail('Performing Streamed Install\nsomething else'),
    cdStubFail('')
  ]);
  check('   the Failure [...] line, not "Performing Streamed Install"', fails[0] === 'Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]' && fails[1] === 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]', JSON.stringify(fails.slice(0, 2)));
  check('   adb\'s own error; a line that says error; else the last line; nothing: no answer', fails[2] === 'device offline' && fails[3] === 'adb: connect error for write: closed' && fails[4] === 'something else' && fails[5] === 'No answer from the watch', JSON.stringify(fails.slice(2)));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
