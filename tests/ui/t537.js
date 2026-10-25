// v7.14 Speed test, the network under a running test (V1-2 of the #234 review): the app opens each connection on whatever network Android uses at that
// moment, so a test begun on Wi-Fi went on over mobile data when the Wi-Fi dropped, without the mobile-data warning, and saved the result under Wi-Fi.
// While a test runs the page reads the network again (at each phase, at the end, about once a second in between): another type of network, or one that
// became metered, stops the test with "The network changed during the test." and nothing is saved. A network that stays the same (or stops being
// metered) changes nothing. The mock bridge stands in for SpeedTest.java as in t432.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let bad = 0;
  const check = (name, ok, extra) => { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || extra === undefined ? '' : ' ' + extra)); };
  const errors = [];
  const WIFI = { type: 'wifi', metered: false, maxBytes: 350000000 }, CELL = { type: 'cellular', metered: true, maxBytes: 350000000 };

  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const s = window.__s = { starts: [], stops: [], netReads: 0, net: { type: 'wifi', metered: false, maxBytes: 350000000 } };
    window.__kv = { taskmgr_ui: JSON.stringify({ sub: 'network' }) };
    window.AndroidBridge = {
      executeShell() { return ''; },
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true } }); },
      tmStart() {}, tmStop() {}, tmRefreshNow() {}, tmKill() {},
      speedTestStart(req) { const r = JSON.parse(req); s.starts.push(r.id); return JSON.stringify({ ok: true, id: r.id }); },
      speedTestStop(id) { s.stops.push(id); },
      speedTestNetInfo() { s.netReads++; return JSON.stringify(s.net); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(60);
  await page.evaluate(() => onTaskMgrData({ ts: 0, ok: true, privileged: true, net: { totalRxBytesPerSec: 1000, totalTxBytesPerSec: 200, ifaces: [] } }));
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);

  const ev = o => page.evaluate(o => window.onSpeedTest(JSON.stringify(o)), o);
  const setNet = n => page.evaluate(n => { __s.net = n; }, n);
  const ui = () => page.evaluate(() => {
    const g = id => document.getElementById(id), vis = id => { const el = g(id); return !!el && getComputedStyle(el).display !== 'none' && el.offsetParent !== null; };
    return { phase: g('stPhase').textContent, go: g('stGo').textContent, err: vis('stErr') ? g('stErrText').textContent : '', chip: g('stNetText').textContent, down: g('stDown').textContent,
      warn: vis('stWarn'), hist: JSON.parse(window.__kv.speedtest_history || '[]').length, running: spd.running, stops: __s.stops.slice(), starts: __s.starts.slice(), reads: __s.netReads };
  });
  const start = async () => { await page.click('#stGo'); await page.waitForTimeout(30); const u = await ui(); return u.starts[u.starts.length - 1]; };
  const runTo = async (id, phase) => {
    await ev({ id, type: 'phase', phase: 'latency' });
    await ev({ id, type: 'latency', pingMs: 12, jitterMs: 1, samples: 15 });
    await ev({ id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
    if (phase === 'upload') { await ev({ id, type: 'result', phase: 'download', mbps: 90, bytes: 1, ms: 10000 }); await ev({ id, type: 'phase', phase: 'upload', phaseMs: 10000, rampMs: 2000 }); }
  };
  const done = id => ev({ id, type: 'done', ok: true, pingMs: 12, jitterMs: 1, downMbps: 90, upMbps: 20, downBytes: 1, upBytes: 1, colo: 'IAD' });

  // ---------------------------------------------------------------- a run on one network finishes as before
  let id = await start();
  await runTo(id, 'download');
  for (let t = 200; t <= 1000; t += 200) await ev({ id, type: 'progress', phase: 'download', t, mbps: 50, bytes: t });
  await page.waitForTimeout(1100);
  await ev({ id, type: 'progress', phase: 'download', t: 1200, mbps: 60, bytes: 1200 });
  await done(id);
  let u = await ui();
  check('1. a run whose network stays Wi-Fi is not stopped: it finishes and its result is saved', u.phase === 'Done' && u.err === '' && u.hist === 1 && !u.stops.includes(id), JSON.stringify(u));

  // ---------------------------------------------------------------- Wi-Fi lost, mobile data takes over during the download
  id = await start();
  await runTo(id, 'download');
  const r0 = (await ui()).reads;
  await page.evaluate(id => { for (let t = 200; t <= 1000; t += 200) window.onSpeedTest(JSON.stringify({ id, type: 'progress', phase: 'download', t, mbps: 40, bytes: t })); }, id);
  u = await ui();
  check('2. the network is not asked at every progress event (five in a row: one read at most)', u.reads - r0 <= 1 && u.running, JSON.stringify(u));
  await setNet(CELL);
  await page.waitForTimeout(1100);
  await ev({ id, type: 'progress', phase: 'download', t: 1200, mbps: 70, bytes: 1200 });
  u = await ui();
  check('   a second later the move to mobile data stops the test (the app is told to stop it)', !u.running && u.stops[u.stops.length - 1] === id, JSON.stringify(u));
  check('   it says Failed and why, offers Run again, and the chip shows the new network', u.phase === 'Failed' && u.err === 'The network changed during the test.' && u.go === 'Run again' && u.chip === 'Mobile data · metered', JSON.stringify(u));
  await ev({ id, type: 'progress', phase: 'download', t: 1400, mbps: 999, bytes: 1 });
  await done(id);
  u = await ui();
  check('   what the stopped test still sends is ignored: nothing is saved under Wi-Fi', u.hist === 1 && u.down === '40.0' && u.phase === 'Failed', JSON.stringify(u));
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui();
  check('   Run again now asks first, as on mobile data', u.warn && u.starts.length === 2, JSON.stringify(u));
  await page.click('#stWarn >> text=Cancel'); await page.waitForTimeout(30);

  // ---------------------------------------------------------------- the change is caught at once at a phase and at the end
  await setNet(WIFI);
  id = await start();
  await runTo(id, 'download');
  await setNet(CELL);
  await ev({ id, type: 'result', phase: 'download', mbps: 90, bytes: 1, ms: 10000 });
  await ev({ id, type: 'phase', phase: 'upload', phaseMs: 10000, rampMs: 2000 });
  u = await ui();
  check('3. a new phase reads the network at once: the upload does not start on mobile data', !u.running && u.stops[u.stops.length - 1] === id && u.err === 'The network changed during the test.', JSON.stringify(u));
  await setNet(WIFI);
  id = await start();
  await runTo(id, 'upload');
  await setNet(CELL);
  await done(id);
  u = await ui();
  check('   so does the end: a result measured after a move is not saved', !u.running && u.hist === 1 && u.phase === 'Failed' && u.err === 'The network changed during the test.', JSON.stringify(u));

  // ---------------------------------------------------------------- metered
  await setNet(WIFI);
  id = await start();
  await runTo(id, 'download');
  await setNet({ type: 'wifi', metered: true, maxBytes: 350000000 });
  await page.waitForTimeout(1100);
  await ev({ id, type: 'progress', phase: 'download', t: 1200, mbps: 60, bytes: 1200 });
  u = await ui();
  check('4. the same Wi-Fi turning metered stops it too', !u.running && u.err === 'The network changed during the test.' && u.chip === 'Wi-Fi · metered', JSON.stringify(u));
  // started on a metered Wi-Fi (asked first); it stops being metered: nothing to warn about, the test goes on
  await page.click('#stGo'); await page.waitForTimeout(30);
  await page.click('text=Start anyway'); await page.waitForTimeout(30);
  id = (await ui()).starts.slice(-1)[0];
  await runTo(id, 'download');
  await setNet(WIFI);
  await page.waitForTimeout(1100);
  await ev({ id, type: 'progress', phase: 'download', t: 1200, mbps: 60, bytes: 1200 });
  await done(id);
  u = await ui();
  check('   a network that stops being metered does not stop it', u.phase === 'Done' && u.err === '' && u.hist === 2, JSON.stringify(u));

  // ---------------------------------------------------------------- translated
  await page.evaluate(() => I18N.set('de')); await page.waitForTimeout(800);
  id = await start();
  await runTo(id, 'download');
  await setNet(CELL);
  await ev({ id, type: 'phase', phase: 'upload', phaseMs: 10000, rampMs: 2000 });
  u = await ui();
  check('5. German: the reason is translated', !u.running && u.err === 'Das Netzwerk hat sich während des Tests geändert.', JSON.stringify(u));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
