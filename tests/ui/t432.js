// v7.14 Task Manager > Network > Speed test: a button at the top of the Network section opens a full screen of its own (not a tab) with a
// gauge, live graphs and cards for ping, jitter, download, upload, the server and the connection. The mock bridge stands in for SpeedTest.java:
// the script streams its events (phase, ping, latency, server, progress, result, done) into window.onSpeedTest the way the app does. Covered:
// the opening (the Task Manager poll pauses, and resumes on closing), a whole run (live needle, arc, numbers, graphs, the result saved in the
// history), events of another test ignored, copy and share, the mobile-data warning with the data use, Stop, closing with X / Back / leaving the
// tab / the app going to the background (each stops the test), errors, no app, reduced motion, the light theme, and the history's limit.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let bad = 0;
  const check = (name, ok, extra) => { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || extra === undefined ? '' : ' ' + extra)); };
  const errors = [];

  async function open(opts) {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    if (opts.reduce) await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.addInitScript(([kv, noSpeed]) => {
      const s = window.__s = { starts: [], stops: [], tmStart: 0, tmStop: 0, copied: [], shared: [], startAnswer: null, net: { type: 'wifi', metered: false, maxBytes: 350000000 } };
      window.__kv = Object.assign({ taskmgr_ui: JSON.stringify({ sub: 'network' }) }, kv || {});
      window.AndroidBridge = {
        executeShell() { return ''; },
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
        loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
        saveSetting(k, v) { window.__kv[k] = v; },
        loadPackages() { return '[]'; },
        getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true } }); },
        tmStart() { s.tmStart++; }, tmStop() { s.tmStop++; }, tmRefreshNow() {}, tmKill() {},
        copyToClipboard(t) { s.copied.push(t); },
        shareText(subject, t) { s.shared.push([subject, t]); },
      };
      if (!noSpeed) Object.assign(window.AndroidBridge, {
        speedTestStart(req) { const r = JSON.parse(req); s.starts.push(r.id); return s.startAnswer || JSON.stringify({ ok: true, id: r.id }); },
        speedTestStop(id) { s.stops.push(id); },
        speedTestNetInfo() { return JSON.stringify(s.net); },
      });
    }, [opts.kv, !!opts.noSpeed]);
    await page.goto(PAGE); await page.waitForTimeout(300);
    await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(60);
    await page.evaluate(() => onTaskMgrData({ ts: 0, ok: true, privileged: true, net: { totalRxBytesPerSec: 1000, totalTxBytesPerSec: 200, ifaces: [] } }));
    return page;
  }
  const ui = page => page.evaluate(() => {
    const g = id => document.getElementById(id);
    const vis = id => { const el = g(id); return !!el && getComputedStyle(el).display !== 'none' && el.offsetParent !== null; };
    const nd = g('stNeedle').getAttribute('transform');
    return { open: g('stModal').classList.contains('show'), num: g('stNum').textContent, unit: g('stUnit').textContent, phase: g('stPhase').textContent,
      go: g('stGo').textContent, stopCls: g('stGo').classList.contains('is-stop'), angle: +(/rotate\(([\d.]+)/.exec(nd) || [0, 0])[1], dash: +g('stArc').getAttribute('stroke-dashoffset'),
      ping: g('stPing').textContent, pingSub: g('stPingSub').textContent, jitter: g('stJitter').textContent, down: g('stDown').textContent, up: g('stUp').textContent,
      server: g('stServer').textContent, serverSub: g('stServerSub').textContent, net: g('stNet').textContent, netSub: g('stNetSub').textContent, chip: g('stNetText').textContent,
      live: [...document.querySelectorAll('.st-card.is-live')].map(e => e.id), share: vis('stShare'), warn: vis('stWarn'), warnMb: g('stWarnMb').textContent, warnTitle: g('stWarnTitle').textContent,
      err: vis('stErr') ? g('stErrText').textContent : '', hist: document.querySelectorAll('#stHist .st-hrow').length, histText: g('stHist').innerText.replace(/\s+/g, ' ').trim(),
      clear: vis('stHistClear') };
  });
  const ev = (page, o) => page.evaluate(o => window.onSpeedTest(JSON.stringify(o)), o);
  const lastId = page => page.evaluate(() => __s.starts[__s.starts.length - 1]);
  const s = page => page.evaluate(() => ({ starts: __s.starts.length, stops: __s.stops.slice(), tmStart: __s.tmStart, tmStop: __s.tmStop }));
  const ink = (page, id) => page.evaluate(id => {                     // how many pixels of the canvas are painted
    const c = document.getElementById(id); if (!c.width || !c.height) return 0;
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0;
    for (let i = 3; i < d.length; i += 4) if (d[i] > 0) n++;
    return n;
  }, id);
  const frames = page => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
  // the needle has arrived where it was going (it eases over animation frames, which a busy machine slows down)
  const settle = page => page.waitForFunction(() => spd.shown === spd.target && !spd.frame, null, { timeout: 15000 });
  const toast = async page => (await page.locator('#toastMsg').innerText()).trim();

  // ---------------------------------------------------------------- the button and the screen
  let page = await open();
  let btn = await page.evaluate(() => { const p = document.getElementById('tmNetworkPanel'); const b = document.getElementById('tmSpeedBtn'); return { first: p.firstElementChild === b, text: b.innerText.replace(/\s+/g, ' ').trim(), shown: b.offsetParent !== null }; });
  check('1. the Network section starts with a Speed test button', btn.first && btn.shown && /^Speed test Ping, download and upload$/.test(btn.text), JSON.stringify(btn));
  let before = await s(page);
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  let u = await ui(page);
  let geo = await page.evaluate(() => { const r = document.getElementById('stScreen').getBoundingClientRect(); const bn = document.querySelector('.bottom-nav, #bottomNav'); const top = document.elementFromPoint(200, 845);
    return { h: Math.round(r.height), w: Math.round(r.width), coversNav: !!(top && top.closest('#stModal')), ticks: document.querySelectorAll('#stTicks text').length, labels: [...document.querySelectorAll('#stTicks text')].map(t => t.textContent).join(','),
      overflow: document.querySelector('#stModal .st-body').scrollWidth > document.querySelector('#stModal .st-body').clientWidth }; });
  check('2. it opens a full screen of its own, over the bottom bar', u.open && geo.h === 860 && geo.w === 400 && geo.coversNav, JSON.stringify(geo));
  check('   the gauge has its scale 0 to 1000 Mbps, the needle at 0, the arc empty', geo.labels === '0,1,5,10,20,50,100,250,500,1000' && u.angle === 135 && u.dash === 1000, JSON.stringify([geo.labels, u.angle, u.dash]));
  check('   nothing is wider than the screen', !geo.overflow);
  check('   ready: Start, the connection (Wi-Fi, not metered), no history yet', u.go === 'Start' && u.phase === 'Ready' && u.chip === 'Wi-Fi' && u.net === 'Wi-Fi' && u.netSub === 'Not metered' && u.hist === 0 && /No results yet/.test(u.histText) && !u.clear, JSON.stringify(u));
  let after = await s(page);
  check('   the Task Manager poll is paused while it is open', after.tmStop > before.tmStop, JSON.stringify([before, after]));

  // ---------------------------------------------------------------- a whole run
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  let id = await lastId(page);
  check('3. Start asks the app for a test with an id, and becomes Stop', (await s(page)).starts === 1 && /^st[a-z0-9]+$/.test(id) && u.go === 'Stop' && u.stopCls && u.phase === 'Connecting…', JSON.stringify(u));
  await ev(page, { id, type: 'phase', phase: 'latency' });
  const pings = [14, 12, 13, 15, 12, 11, 13, 12, 14, 13, 12, 13, 12, 16, 12];
  for (let i = 0; i < pings.length; i++) await ev(page, { id, type: 'ping', ms: pings[i], n: i + 1, of: 15, medianMs: [...pings.slice(0, i + 1)].sort((a, b) => a - b)[Math.floor(i / 2)] });
  u = await ui(page);
  check('4. latency: the phase says Ping, the gauge shows the last sample in ms, the Ping card the running median', u.phase === 'Ping' && u.unit === 'ms' && u.num === '12' && u.ping === '13' && u.pingSub === 'Sample 15 / 15' && u.live.join() === 'stCardPing', JSON.stringify(u));
  await ev(page, { id, type: 'latency', pingMs: 12.5, jitterMs: 1.43, samples: 15 });
  await ev(page, { id, type: 'server', colo: 'IAD' });
  u = await ui(page);
  check('   then ping and jitter (one decimal under 10 ms), and the data centre by its city', u.ping === '13' && u.jitter === '1.4' && u.pingSub === 'Median of 15 requests' && u.server === 'Ashburn' && u.serverSub === 'IAD · Cloudflare' && u.chip === 'Wi-Fi', JSON.stringify(u));
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  for (let t = 200; t <= 4000; t += 200) await ev(page, { id, type: 'progress', phase: 'download', t, mbps: Math.min(94.2, t / 30), bytes: t * 1000 });
  u = await ui(page);
  check('5. download: the phase, the live number on the Download card, the card marked live', u.phase === 'Download' && u.unit === 'Mbps' && u.down === '94.2' && u.live.join() === 'stCardDown', JSON.stringify(u));
  await settle(page);
  u = await ui(page);
  check('   the needle and the arc have reached 94.2 Mbps (between 50 and 100 on the scale), the big number too', Math.abs(u.angle - (135 + 270 * (5 + 44.2 / 50) / 9)) < 0.5 && u.dash < 360 && u.dash > 330 && u.num === '94.2', JSON.stringify([u.angle, u.dash, u.num]));
  await frames(page);
  check('   the download graph is drawn, the upload graph is still empty', (await ink(page, 'stDownGraph')) > 200 && (await ink(page, 'stUpGraph')) === 0);
  await ev(page, { id, type: 'result', phase: 'download', mbps: 93.8, bytes: 94000000, ms: 10000 });
  await ev(page, { id, type: 'phase', phase: 'upload', phaseMs: 10000, rampMs: 2000 });
  for (let t = 200; t <= 3000; t += 200) await ev(page, { id, type: 'progress', phase: 'upload', t, mbps: 21.37, bytes: t * 300 });
  await frames(page);
  u = await ui(page);
  check('6. upload: its phase, its number (three figures), its card live, the needle starting again from 0', u.phase === 'Upload' && u.up === '21.4' && u.down === '93.8' && u.live.join() === 'stCardUp', JSON.stringify(u));
  check('   and its graph drawn', (await ink(page, 'stUpGraph')) > 200);
  await ev(page, { id, type: 'result', phase: 'upload', mbps: 21.3, bytes: 20000000, ms: 10000 });
  await ev(page, { id, type: 'done', ok: true, pingMs: 12.5, jitterMs: 1.43, downMbps: 93.8, upMbps: 21.3, downBytes: 94000000, upBytes: 20000000, colo: 'IAD' });
  await settle(page);
  u = await ui(page);
  check('7. done: Run again, the final numbers, copy and share offered, nothing live', u.go === 'Run again' && !u.stopCls && u.phase === 'Done' && u.num === '93.8' && u.down === '93.8' && u.up === '21.3' && u.share && !u.live.length, JSON.stringify(u));
  let kv = await page.evaluate(() => JSON.parse(window.__kv.speedtest_history || '[]'));
  check('   the result is kept in the history (the app\'s settings store) and listed', kv.length === 1 && kv[0].down === 93.8 && kv[0].up === 21.3 && kv[0].colo === 'IAD' && kv[0].net === 'wifi' && u.hist === 1 && /↓ 93\.8 ↑ 21\.3 13 ms/.test(u.histText) && /Ashburn/.test(u.histText) && u.clear, JSON.stringify([kv, u.histText]));

  await ev(page, { id, type: 'progress', phase: 'upload', t: 5000, mbps: 999, bytes: 1 });
  await ev(page, { id: 'other', type: 'done', ok: false, error: 'boom' });
  u = await ui(page);
  check('8. events after done, or of another test, change nothing', u.up === '21.3' && u.err === '' && u.phase === 'Done', JSON.stringify(u));

  await page.click('text=Copy result'); await page.waitForTimeout(30);
  await page.click('text=Share result'); await page.waitForTimeout(30);
  let cs = await page.evaluate(() => ({ copied: __s.copied, shared: __s.shared }));
  const want = 'Speed test (Cloudflare, Ashburn IAD)\nDownload: 93.8 Mbps\nUpload: 21.3 Mbps\nPing: 13 ms, jitter 1.4 ms\nConnection: Wi-Fi\n';
  check('9. Copy puts the result on the clipboard as text', cs.copied.length === 1 && cs.copied[0].startsWith(want), JSON.stringify(cs.copied));
  check('   Share sends the same text with the subject Speed test', cs.shared.length === 1 && cs.shared[0][0] === 'Speed test' && cs.shared[0][1] === cs.copied[0], JSON.stringify(cs.shared));

  // ---------------------------------------------------------------- mobile data: ask first
  await page.evaluate(() => { __s.net = { type: 'cellular', metered: true, maxBytes: 350000000 }; });
  let n0 = (await s(page)).starts;
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  check('10. on mobile data Run again asks first, naming the data a test can use (350 MB), and starts nothing', u.warn && u.warnTitle === 'You are on mobile data' && u.warnMb === '350 MB' && (await s(page)).starts === n0 && u.chip === 'Mobile data · metered' && u.netSub === 'Metered: data may cost money', JSON.stringify(u));
  await page.click('#stWarn >> text=Cancel'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    Cancel: the warning goes, still nothing started', !u.warn && (await s(page)).starts === n0, JSON.stringify(u));
  await page.evaluate(() => { __s.net = { type: 'wifi', metered: true, maxBytes: 120000000 }; });
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    a metered Wi-Fi is asked about too, with the amount the app names', u.warn && u.warnTitle === 'This network is metered' && u.warnMb === '120 MB', JSON.stringify(u));
  await page.click('text=Start anyway'); await page.waitForTimeout(30);
  u = await ui(page);
  id = await lastId(page);
  check('    Start anyway starts it and clears the last result', !u.warn && (await s(page)).starts === n0 + 1 && u.go === 'Stop' && u.down === '—' && u.ping === '—' && !u.share, JSON.stringify(u));

  // ---------------------------------------------------------------- Stop
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  await ev(page, { id, type: 'progress', phase: 'download', t: 200, mbps: 40, bytes: 100 });
  await page.click('#stGo'); await settle(page);
  u = await ui(page);
  let st1 = await s(page);
  check('11. Stop tells the app to stop that test, says Stopped, offers Run again, the needle back at 0', st1.stops[st1.stops.length - 1] === id && u.phase === 'Stopped' && u.go === 'Run again' && !u.stopCls && u.angle === 135 && u.num === '0' && !u.live.length, JSON.stringify([st1.stops, u]));
  await ev(page, { id, type: 'progress', phase: 'download', t: 400, mbps: 80, bytes: 200 });
  await ev(page, { id, type: 'done', ok: false, stopped: true });
  u = await ui(page);
  check('    what the stopped test still sends is ignored; nothing is added to the history', u.down === '40.0' && u.hist === 1 && u.phase === 'Stopped', JSON.stringify(u));

  // ---------------------------------------------------------------- closing stops the test
  await page.evaluate(() => { __s.net = { type: 'wifi', metered: false, maxBytes: 350000000 }; });
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  before = await s(page);
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(30);
  after = await s(page);
  u = await ui(page);
  check('12. X closes the screen and stops the running test; the Task Manager poll resumes', !u.open && after.stops[after.stops.length - 1] === id && after.tmStart > before.tmStart, JSON.stringify([before, after]));
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  u = await ui(page);
  check('    opened again: the last stopped state, Run again', u.open && u.go === 'Run again' && u.phase === 'Stopped', JSON.stringify(u));
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  let backed = await page.evaluate(() => handleAndroidBack());
  u = await ui(page);
  check('13. Back closes it and stops the test', backed === true && !u.open && (await s(page)).stops.slice(-1)[0] === id, JSON.stringify(u));
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await page.click('.st-back'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    the back arrow in its header too', !u.open && (await s(page)).stops.slice(-1)[0] === id);

  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  before = await s(page);
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  u = await ui(page);
  check('14. the app going to the background stops the test and says why', u.open && (await s(page)).stops.slice(-1)[0] === id && u.phase === 'Stopped: the app went to the background' && u.go === 'Run again', JSON.stringify(u));
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
  after = await s(page);
  check('    coming back does not restart the Task Manager poll under the open screen', after.tmStart === before.tmStart, JSON.stringify([before, after]));

  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(30);
  u = await ui(page);
  check('15. leaving the Task Manager tab closes the screen and stops the test', !u.open && (await s(page)).stops.slice(-1)[0] === id, JSON.stringify(u));

  // ---------------------------------------------------------------- errors
  await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(60);
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  await ev(page, { id, type: 'done', ok: false, error: 'download failed: connection lost' });
  u = await ui(page);
  check('16. a failed test says so in words and offers Run again', u.err === 'Download failed: connection lost.' && u.phase === 'Failed' && u.go === 'Run again', JSON.stringify(u));
  await page.evaluate(() => { __s.startAnswer = JSON.stringify({ ok: false, error: 'busy' }); });
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    a start the app refuses: its reason, and nothing running', u.err === 'Could not start: busy' && u.go === 'Start' && !u.stopCls, JSON.stringify(u));
  await page.evaluate(() => { __s.startAnswer = null; __s.net = { type: 'none', metered: false }; });
  n0 = (await s(page)).starts;
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    no connection: says so and starts nothing', u.err === 'No internet connection.' && (await s(page)).starts === n0 && u.net === 'No connection', JSON.stringify(u));

  // ---------------------------------------------------------------- the light theme
  await page.evaluate(() => { __s.net = { type: 'wifi', metered: false, maxBytes: 350000000 }; });
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  for (let t = 200; t <= 2000; t += 200) await ev(page, { id, type: 'progress', phase: 'download', t, mbps: 50, bytes: t });
  await frames(page);
  const darkPix = await page.evaluate(() => { const c = document.getElementById('stDownGraph'); const d = c.getContext('2d').getImageData(Math.floor(c.width * 0.15), Math.floor(c.height * 0.6), 1, 1).data; return [d[0], d[1], d[2]].join(','); });
  await page.evaluate(() => { themeState.appearance = 'light'; applyTheme(false); });
  await ev(page, { id, type: 'progress', phase: 'download', t: 2200, mbps: 50, bytes: 2200 });
  await frames(page);
  const th = await page.evaluate(() => {
    const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
    const c = document.getElementById('stDownGraph'); const d = c.getContext('2d').getImageData(Math.floor(c.width * 0.15), Math.floor(c.height * 0.6), 1, 1).data;
    return { light: document.documentElement.getAttribute('data-appearance'), bg: getComputedStyle(document.getElementById('stScreen')).backgroundColor, base: css('--bg-base'),
      stop0: getComputedStyle(document.querySelector('#stGrad .st-g0')).stopColor, accent: css('--accent'), pix: [d[0], d[1], d[2]].join(',') };
  });
  const hexRgb = h => { const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(h); return m ? 'rgb(' + [1, 2, 3].map(i => parseInt(m[i], 16)).join(', ') + ')' : h; };
  check('17. light theme: the screen takes the theme\'s background, the gauge its accent', th.light === 'light' && th.bg === hexRgb(th.base) && th.stop0 === hexRgb(th.accent), JSON.stringify(th));
  check('    and the graphs are painted again in the new accent', th.pix !== darkPix, darkPix + ' -> ' + th.pix);
  await page.evaluate(() => { themeState.appearance = 'dark'; applyTheme(false); });
  await page.click('#stGo'); await page.waitForTimeout(30);

  // ---------------------------------------------------------------- history: a limit, Clear
  await page.evaluate(() => {
    const h = []; for (let i = 0; i < 12; i++) h.push({ at: Date.now() - i * 3600e3, down: 10 + i, up: 1 + i, ping: 20, jitter: 2, colo: 'FRA', net: 'wifi' });
    window.__kv.speedtest_history = JSON.stringify(h);
  });
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await ev(page, { id, type: 'done', ok: true, pingMs: 8.04, jitterMs: 0.5, downMbps: 512, upMbps: 48.6, downBytes: 1, upBytes: 1, colo: 'XQZ' });
  u = await ui(page);
  kv = await page.evaluate(() => JSON.parse(window.__kv.speedtest_history));
  check('18. the history keeps the last 10 results, newest first', kv.length === 10 && kv[0].down === 512 && kv[9].down === 18 && u.hist === 10, JSON.stringify(kv.map(r => r.down)));
  check('    a data centre the page has no city for shows its code', u.server === 'XQZ' && u.serverSub === 'XQZ · Cloudflare' && u.ping === '8.0' && u.jitter === '0.5', JSON.stringify(u));
  await page.click('#stHistClear'); await page.waitForTimeout(30);
  u = await ui(page);
  check('    Clear empties it, in the store too', u.hist === 0 && !u.clear && (await page.evaluate(() => window.__kv.speedtest_history)) === '[]' && /History cleared/.test(await toast(page)), JSON.stringify(u));
  await page.close();

  // ---------------------------------------------------------------- the history is read back; reduced motion
  const saved = JSON.stringify([{ at: Date.parse('2026-10-02T18:30:00Z'), down: 250, up: 40, ping: 9.5, jitter: 1, colo: 'LHR', net: 'wifi' }, { bogus: true }]);
  page = await open({ kv: { speedtest_history: saved }, reduce: true });
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  u = await ui(page);
  check('19. a saved history is shown when the screen opens (a damaged entry is left out)', u.hist === 1 && /Oct 2 06:30 PM · London ↓ 250 ↑ 40\.0 9\.5 ms/.test(u.histText), u.histText);
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  const jump = await page.evaluate(id => { window.onSpeedTest(JSON.stringify({ id, type: 'progress', phase: 'download', t: 200, mbps: 250, bytes: 1 })); const nd = document.getElementById('stNeedle').getAttribute('transform'); return +(/rotate\(([\d.]+)/.exec(nd))[1]; }, id);
  check('20. reduced motion: the needle jumps to the reading at once (no easing)', Math.abs(jump - (135 + 270 * 7 / 9)) < 0.01, String(jump));
  const anim = await page.evaluate(() => getComputedStyle(document.getElementById('stArc')).animationName);
  check('    and the arc does not pulse', anim === 'none', anim);
  await page.close();

  page = await open();
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  await page.click('#stGo'); await page.waitForTimeout(30);
  id = await lastId(page);
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  const eased = await page.evaluate(id => { window.onSpeedTest(JSON.stringify({ id, type: 'progress', phase: 'download', t: 200, mbps: 250, bytes: 1 })); const nd = document.getElementById('stNeedle').getAttribute('transform'); return +(/rotate\(([\d.]+)/.exec(nd))[1]; }, id);
  check('    with motion the needle moves there over frames instead', eased < 136, String(eased));
  // many readings in one go are painted once: one frame for the graphs, not one per event
  const draws = await page.evaluate(id => new Promise(res => {
    let n = 0; const orig = window.stDrawGraph; window.stDrawGraph = function (p) { n++; return orig.apply(this, arguments); };
    for (let t = 400; t <= 4000; t += 100) window.onSpeedTest(JSON.stringify({ id, type: 'progress', phase: 'download', t, mbps: 100, bytes: t }));
    requestAnimationFrame(() => requestAnimationFrame(() => { window.stDrawGraph = orig; res(n); }));
  }), id);
  check('21. 37 readings arriving together redraw the graph once', draws === 1, String(draws));
  await page.close();

  // ---------------------------------------------------------------- outside the app
  page = await open({ noSpeed: true });
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  await page.click('#stGo'); await page.waitForTimeout(30);
  u = await ui(page);
  check('22. without the app\'s speed test: a toast says it needs the app, nothing runs', /The speed test needs the app/.test(await toast(page)) && u.go === 'Start' && u.chip === 'Other network', JSON.stringify(u));
  await page.close();

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
