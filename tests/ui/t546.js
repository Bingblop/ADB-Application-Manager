// Extra Settings > Speed test (the owner's request: the network test of Task Manager > Network also in Extra Settings, right after Private DNS):
// a section of its own (<section id="extraSpeedSection" class="xs-sec">) between Private DNS and Animation speed, in the tab's look (icon tile, title, ONE
// pill, a sentence, a button). The pill and a header chip show the download speed of the newest saved result. The button opens the same full screen as
// the Task Manager's Speed test button; closing it returns to Extra Settings without starting the Task Manager poll. Back, leaving the tab (to any other
// tab, the Task Manager too) and the app going to the background stop a running test, as they do from the Task Manager. The Task Manager entry still
// works as before (its poll pauses and resumes). The bottom-bar shortcuts of the tab list the section in page order. The mock bridge stands in for
// SpeedTest.java as in t432. Under the screen the tab's own work waits, as the Task Manager poll does: a Private DNS speed test still running is
// stopped, the telemetry poll and the zram timer wait, and they run again on closing (the shared mocks of t217 / t250 / t251 stand in for the app there).
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, TESTS } = require('./lib/pw');
const sdbMock = require('./lib/sdb_mock.js');
const xqMock = require('./lib/xq_mock.js');
const xsMock = require('./lib/xs_mock.js');
const dnsMock = require('./lib/dns_mock.js');
const FX = path.join(TESTS, 'java', 'fixtures', 'powertweaks');
const rd = f => fs.readFileSync(path.join(FX, f), 'utf8');
const fixtures = { mm_stat: rd('mm_stat.txt'), disksize: rd('disksize.txt'), meminfo: rd('meminfo.txt'), help11: rd('cmd-power-help-11.txt'), dump_active: rd('deviceidle-active.txt') };
(async () => {
  const b = await chromium.launch();
  let bad = 0;
  const check = (name, ok, extra) => { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || extra === undefined ? '' : ' ' + extra)); };
  const errors = [];

  async function open(opts) {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([kv, noSpeed]) => {
      const s = window.__s = { starts: [], stops: [], tmStart: 0, tmStop: 0, net: { type: 'wifi', metered: false, maxBytes: 350000000 } };
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
      };
      if (!noSpeed) Object.assign(window.AndroidBridge, {
        speedTestStart(req) { const r = JSON.parse(req); s.starts.push(r.id); return JSON.stringify({ ok: true, id: r.id }); },
        speedTestStop(id) { s.stops.push(id); },
        speedTestNetInfo() { return JSON.stringify(s.net); },
      });
    }, [opts.kv, !!opts.noSpeed]);
    await page.goto(PAGE); await page.waitForTimeout(300);
    await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(80);
    return page;
  }
  const sec = page => page.evaluate(() => {
    const g = id => document.getElementById(id), s = g('extraSpeedSection');
    const vis = el => !!el && getComputedStyle(el).display !== 'none' && el.offsetParent !== null;
    const chip = g('xsChipSpeed');
    return { cur: currentViewName(), open: g('stModal').classList.contains('show'), pill: s ? s.querySelector('.xs-pill').innerText.replace(/\s+/g, ' ').trim() : null,
      pillCls: s ? s.querySelector('.xs-pill').className : null, chip: vis(chip) ? chip.innerText.replace(/\s+/g, ' ').trim() + ' -> ' + chip.dataset.target : 'hidden' };
  });
  const s = page => page.evaluate(() => ({ starts: __s.starts.slice(), stops: __s.stops.slice(), tmStart: __s.tmStart, tmStop: __s.tmStop }));
  const ev = (page, o) => page.evaluate(o => window.onSpeedTest(JSON.stringify(o)), o);
  const lastId = page => page.evaluate(() => __s.starts[__s.starts.length - 1]);
  const startTest = async page => { await page.click('#stGo'); await page.waitForTimeout(30); return lastId(page); };

  // ---------------------------------------------------------------- 1. where the section is, and what it holds
  let page = await open();
  const shell = await page.evaluate(() => {
    const root = document.getElementById('extraSettingsRoot'), s = document.getElementById('extraSpeedSection');
    if (!s) return { missing: true, kids: [...root.children].map(e => e.id).join() };
    const card = s.querySelector('.color-card.xs-card'), head = s.querySelector('.xs-sec-head'), btn = s.querySelector('#xspOpen');
    return { kids: [...root.children].map(e => e.id).join(), prev: s.previousElementSibling && s.previousElementSibling.id, next: s.nextElementSibling && s.nextElementSibling.id,
      tag: s.tagName, cls: s.className, label: s.getAttribute('aria-label'), card: !!card, ico: head ? head.querySelectorAll('.xs-ico svg').length : 0, pills: head ? head.querySelectorAll('.xs-pill').length : 0,
      title: head && head.querySelector('.xs-sec-line .color-card-title').innerText, sub: card && card.querySelector(':scope > .color-card-subtitle').innerText,
      btn: btn && btn.innerText.trim(), btnPrimary: !!btn && btn.classList.contains('xs-btn') && btn.classList.contains('is-primary'), btnH: btn ? Math.round(btn.getBoundingClientRect().height) : 0, more: !!s.querySelector('details.xs-more summary') && /Cloudflare sees this phone's IP address/.test(s.querySelector('details.xs-more').textContent) };
  });
  check('1. the tab has a section #extraSpeedSection right after Private DNS (#extraDnsSection) and before Animation speed', !shell.missing && shell.prev === 'extraDnsSection' && shell.next === 'extraAnimSection' && shell.kids.startsWith('extraSettingsHeader,extraDnsSection,extraSpeedSection,extraAnimSection'), JSON.stringify(shell));
  check('   in the tab\'s look: <section class="xs-sec"> with a .color-card.xs-card, the head with the icon tile, the title "Speed test" and ONE pill', shell.tag === 'SECTION' && shell.cls === 'xs-sec' && shell.label === 'Speed test' && shell.card && shell.ico === 1 && shell.pills === 1 && shell.title === 'Speed test', JSON.stringify(shell));
  check('   a sentence of what it measures, the button "Open speed test" (primary, at least 44 px tall) and a "What does this do?" note (the screen\'s own note: Cloudflare sees the IP address)', /ping, download and upload/.test(shell.sub || '') && /Cloudflare/.test(shell.sub || '') && shell.btn === 'Open speed test' && shell.btnPrimary && shell.btnH >= 44 && shell.more, JSON.stringify(shell));
  let u = await sec(page);
  check('2. no saved result yet: the pill says "Not tested", the header has no speed chip', u.pill === '● Not tested' && u.pillCls === 'xs-pill' && u.chip === 'hidden', JSON.stringify(u));
  if (shell.missing) { console.log(bad + ' FAILED (no section: the rest cannot run)'); await b.close(); process.exit(1); }

  // ---------------------------------------------------------------- 3. the button opens the speed test screen over Extra Settings
  let before = await s(page);
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  const scr = await page.evaluate(() => { const r = document.getElementById('stScreen').getBoundingClientRect(); const top = document.elementFromPoint(200, 845);
    return { open: document.getElementById('stModal').classList.contains('show'), h: Math.round(r.height), w: Math.round(r.width), covers: !!(top && top.closest('#stModal')), go: document.getElementById('stGo').textContent,
      phase: document.getElementById('stPhase').textContent, cur: currentViewName(), from: spd.from, title: document.getElementById('stTitle').textContent }; });
  let after = await s(page);
  check('3. "Open speed test" opens the same full screen as the Task Manager button (#stModal: Speed test, Ready, Start), over the bottom bar', scr.open && scr.h === 860 && scr.w === 400 && scr.covers && scr.title === 'Speed test' && scr.go === 'Start' && scr.phase === 'Ready', JSON.stringify(scr));
  check('   the tab under it stays Extra Settings; the Task Manager poll is not started', scr.cur === 'extrasettings' && scr.from === 'extrasettings' && after.tmStart === before.tmStart, JSON.stringify([scr, before, after]));
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(60);
  u = await sec(page); after = await s(page);
  check('4. X closes it: back on Extra Settings, the Task Manager poll still not started', !u.open && u.cur === 'extrasettings' && after.tmStart === before.tmStart, JSON.stringify([u, after]));

  // ---------------------------------------------------------------- 5. a whole run from Extra Settings: the pill and the chip follow the history
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  let id = await startTest(page);
  await ev(page, { id, type: 'phase', phase: 'latency' });
  await ev(page, { id, type: 'latency', pingMs: 12.5, jitterMs: 1.43, samples: 15 });
  await ev(page, { id, type: 'phase', phase: 'download', phaseMs: 10000, rampMs: 2000 });
  await ev(page, { id, type: 'result', phase: 'download', mbps: 93.8, bytes: 94000000, ms: 10000 });
  await ev(page, { id, type: 'phase', phase: 'upload', phaseMs: 10000, rampMs: 2000 });
  await ev(page, { id, type: 'result', phase: 'upload', mbps: 21.3, bytes: 20000000, ms: 10000 });
  await ev(page, { id, type: 'done', ok: true, pingMs: 12.5, jitterMs: 1.43, downMbps: 93.8, upMbps: 21.3, downBytes: 94000000, upBytes: 20000000, colo: 'IAD' });
  const done = await page.evaluate(() => ({ phase: document.getElementById('stPhase').textContent, go: document.getElementById('stGo').textContent, hist: JSON.parse(window.__kv.speedtest_history || '[]').map(r => r.down) }));
  check('5. a test started from Extra Settings runs and is saved in the same history', done.phase === 'Done' && done.go === 'Run again' && done.hist.join() === '93.8', JSON.stringify(done));
  await page.click('.st-back'); await page.waitForTimeout(60);
  u = await sec(page);
  check('   closed: the pill shows the new download speed, and a header chip "Speed test ↓ 93.8 Mbps" points at the section', !u.open && u.cur === 'extrasettings' && u.pill === '↓ 93.8 Mbps' && u.chip === 'Speed test ↓ 93.8 Mbps -> extraSpeedSection', JSON.stringify(u));
  const chips = await page.evaluate(() => [...document.querySelectorAll('#extraSettingsChips .xs-chip')].filter(c => getComputedStyle(c).display !== 'none').map(c => c.id).join());
  check('   the chips keep the order of the sections (Private DNS, Speed test, Animations, ...)', chips.startsWith('xsChipDns,xsChipSpeed,xsChipAnim'), chips);
  await page.click('#xsChipSpeed'); await page.waitForTimeout(700);
  const went = await page.evaluate(() => { const r = document.getElementById('extraSpeedSection').getBoundingClientRect(); return { top: Math.round(r.top), vh: innerHeight }; });
  check('   the chip scrolls to the section', went.top >= 0 && went.top < went.vh / 2, JSON.stringify(went));

  // ---------------------------------------------------------------- 6. what stops a test opened from Extra Settings
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  id = await startTest(page);
  let backed = await page.evaluate(() => handleAndroidBack());
  u = await sec(page);
  check('6. Back closes the screen and stops the running test; Extra Settings stays', backed === true && !u.open && u.cur === 'extrasettings' && (await s(page)).stops.slice(-1)[0] === id, JSON.stringify(u));

  await page.click('#xspOpen'); await page.waitForTimeout(350);
  id = await startTest(page);
  before = await s(page);
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  const bg = await page.evaluate(() => ({ phase: document.getElementById('stPhase').textContent, running: spd.running }));
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
  after = await s(page);
  check('7. the app going to the background stops the test and says why; coming back starts no Task Manager poll', !bg.running && bg.phase === 'Stopped: the app went to the background' && after.stops.slice(-1)[0] === id && after.tmStart === before.tmStart, JSON.stringify([bg, before, after]));
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(60);

  await page.click('#xspOpen'); await page.waitForTimeout(350);
  id = await startTest(page);
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(60);
  u = await sec(page);
  check('8. leaving the tab closes the screen and stops the test', !u.open && u.cur === 'apps' && (await s(page)).stops.slice(-1)[0] === id, JSON.stringify(u));

  await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(80);
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  id = await startTest(page);
  before = await s(page);
  await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(80);
  u = await sec(page); after = await s(page);
  check('   leaving it for the Task Manager closes it too (the screen belongs to Extra Settings), and the Task Manager poll starts once', !u.open && u.cur === 'taskmgr' && after.stops.slice(-1)[0] === id && after.tmStart === before.tmStart + 1, JSON.stringify([u, before, after]));

  // ---------------------------------------------------------------- 9. the Task Manager entry works as before
  await page.evaluate(() => onTaskMgrData({ ts: 0, ok: true, privileged: true, net: { totalRxBytesPerSec: 1000, totalTxBytesPerSec: 200, ifaces: [] } }));
  before = await s(page);
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  u = await sec(page); after = await s(page);
  check('9. Task Manager > Network > Speed test still opens the screen and pauses the Task Manager poll', u.open && u.cur === 'taskmgr' && after.tmStop > before.tmStop, JSON.stringify([u, before, after]));
  before = await s(page);
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(60);
  u = await sec(page); after = await s(page);
  check('   closing it there resumes the poll (once)', !u.open && u.cur === 'taskmgr' && after.tmStart === before.tmStart + 1, JSON.stringify([before, after]));
  await page.click('#tmSpeedBtn'); await page.waitForTimeout(350);
  id = await startTest(page);
  await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(80);
  u = await sec(page);
  check('   and leaving the Task Manager (for Extra Settings) closes it and stops the test', !u.open && u.cur === 'extrasettings' && (await s(page)).stops.slice(-1)[0] === id, JSON.stringify(u));

  // ---------------------------------------------------------------- 10. the bottom-bar shortcuts of the tab
  const sc = await page.evaluate(() => ({ ids: TAB_SHORTCUTS.extrasettings.map(x => x.id).join(), labels: TAB_SHORTCUTS.extrasettings.map(x => bnavScLabel(x)).join('|'),
    page: [...document.querySelectorAll('#extraSettingsRoot > section.xs-sec')].map(x => x.id).join(), on: TAB_SHORTCUTS.extrasettings.filter(x => x.when()).length }));
  check('10. the tab\'s shortcut list names the section in page order ("Speed test" after "Private DNS"), one entry per section', sc.ids === 'dns,speed,animations,brightness,telemetry,power,doze,zram' && /^Private DNS\|Speed test\|Animations/.test(sc.labels) && sc.page === 'extraDnsSection,extraSpeedSection,extraAnimSection,extraBrightnessSection,extraTelemetrySection,extraPowerSection,extraDozeSection,extraZramSection' && sc.on === 8, JSON.stringify(sc));
  await page.evaluate(() => { switchView('apps'); TAB_SHORTCUTS.extrasettings.find(x => x.id === 'speed').run(); }); await page.waitForTimeout(900);
  const lit = await page.evaluate(() => ({ cur: currentViewName(), flash: document.getElementById('extraSpeedSection').classList.contains('bn-flash') }));
  check('    "Speed test" opens the tab and lights the section up', lit.cur === 'extrasettings' && lit.flash, JSON.stringify(lit));
  // The default menu of the slot (no choice saved) stays the four it was before the section came: Speed test is in the list, off, for whoever wants it in the menu
  const dm = await page.evaluate(() => { navScRender(); const g = document.querySelector('#navScList .nav-sc-group[data-tab="extrasettings"]');
    return { saved: !!tabConfig.shortcuts.extrasettings, menu: bnavScList('extrasettings').map(x => bnavScLabel(x)).join(), rows: g ? [...g.querySelectorAll('.fl-row')].map(r => r.querySelector('.fl-name').textContent + (r.classList.contains('off') ? ' (off)' : '')).join() : null }; });
  check('    the slot\'s default menu stays Private DNS, Animations, Brightness, Telemetry; Settings > Navigation lists Speed test (off) to switch on', !dm.saved && dm.menu === 'Private DNS,Animations,Brightness,Telemetry' && dm.rows === 'Private DNS,Animations,Brightness,Telemetry,Speed test (off),Power (off),Doze (off),Zram (off)', JSON.stringify(dm));
  await page.close();

  // ---------------------------------------------------------------- 11. a saved history; outside the app
  page = await open({ kv: { speedtest_history: JSON.stringify([{ at: Date.parse('2026-10-02T18:30:00Z'), down: 250, up: 40, ping: 9.5, jitter: 1, colo: 'LHR', net: 'wifi' }, { at: Date.parse('2026-10-01T18:30:00Z'), down: 12.34, up: 3, ping: 20, jitter: 2, colo: 'LHR', net: 'wifi' }]) } });
  u = await sec(page);
  check('11. a saved history: the pill and the chip show the newest download speed', u.pill === '↓ 250 Mbps' && u.chip === 'Speed test ↓ 250 Mbps -> extraSpeedSection', JSON.stringify(u));
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  await page.click('#stHistClear'); await page.waitForTimeout(60);
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(60);
  u = await sec(page);
  check('    Clear in the screen\'s history: the pill says "Not tested" again and the chip goes', u.pill === '● Not tested' && u.chip === 'hidden', JSON.stringify(u));
  await page.close();

  page = await open({ noSpeed: true });
  u = await sec(page);
  check('    without the app\'s speed test: the pill says "Needs the app" (a warning), no chip', u.pill === '● Needs the app' && /is-warn/.test(u.pillCls) && u.chip === 'hidden', JSON.stringify(u));
  await page.close();

  // ---------------------------------------------------------------- 12. it fits, at phone widths, and in the languages with the longest words (German, Spanish, Portuguese)
  // The head is the one of Power, Doze and Zram: the pill keeps its own width (the measured speed is never cut), the title gives way and may wrap to two lines.
  for (const [w, lang] of [[320, 'en'], [360, 'en'], [412, 'en'], [320, 'de'], [360, 'de'], [320, 'es'], [360, 'es'], [360, 'pt-BR']]) {
    page = await open({ w, kv: { speedtest_history: JSON.stringify([{ at: Date.parse('2026-10-02T18:30:00Z'), down: 941.2, up: 40, ping: 9.5, jitter: 1, colo: 'LHR', net: 'wifi' }]) } });
    if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(700); }
    const r = await page.evaluate(() => {
      const s = document.getElementById('extraSpeedSection'), card = s.querySelector('.xs-card'), cr = card.getBoundingClientRect();
      const lines = el => { const lh = parseFloat(getComputedStyle(el).lineHeight) || el.getBoundingClientRect().height; return Math.round(el.getBoundingClientRect().height / lh); };
      const pill = s.querySelector('.xs-pill'), pt = document.getElementById('xspPillT'), title = s.querySelector('.xs-sec-line .color-card-title'), btn = document.getElementById('xspOpen'), chip = document.getElementById('xsChipSpeed'), cv = document.getElementById('xsChipSpeedV');
      return { page: document.documentElement.scrollWidth <= innerWidth, out: [...s.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.width && (r.left < cr.left - 0.5 || r.right > cr.right + 0.5); }).map(e => e.id || e.className),
        pillOne: pill.scrollWidth <= pill.clientWidth + 1 && pt.scrollWidth <= pt.clientWidth + 1 && pill.getBoundingClientRect().height <= 28, pill: pill.innerText, pillW: pt.clientWidth + '/' + pt.scrollWidth, title: title.innerText, titleCut: title.scrollWidth > title.clientWidth + 1, titleLines: lines(title), btnH: Math.round(btn.getBoundingClientRect().height),
        chipOne: chip.scrollWidth <= chip.clientWidth + 1 && cv.scrollWidth <= cv.clientWidth + 1 && chip.getBoundingClientRect().height >= 43.5 && chip.getBoundingClientRect().height < 60 };
    });
    const tag = lang === 'en' ? '' : '-' + lang;
    await page.locator('#extraSpeedSection').screenshot({ path: 'section-' + w + tag + '.png' });
    await page.locator('#extraSettingsHeader').screenshot({ path: 'header-' + w + tag + '.png' });
    check('12. [' + w + ' px' + (lang === 'en' ? '' : ', ' + lang) + '] no sideways scroll, nothing outside the card, the pill ("↓ 941 Mbps") and the chip on one line and not cut, the title not cut (two lines at most), the button 44 px or taller',
      r.page && r.out.length === 0 && r.pillOne && r.chipOne && r.btnH >= 44 && !r.titleCut && r.titleLines <= 2, JSON.stringify(r));
    await page.close();
  }
  page = await open();
  await page.evaluate(() => I18N.set('de')); await page.waitForTimeout(700);
  const de = await page.evaluate(() => ({ title: document.querySelector('#extraSpeedSection .xs-sec-line .color-card-title').innerText, btn: document.getElementById('xspOpen').innerText.trim(), pill: document.getElementById('xspPillT').innerText,
    sub: document.querySelector('#extraSpeedCard > .color-card-subtitle').innerText }));
  check('13. in German the section speaks German (title, sentence, button, pill)', de.title === 'Geschwindigkeitstest' && de.btn === 'Geschwindigkeitstest öffnen' && de.pill === 'Nicht getestet' && /^Misst die Internetverbindung/.test(de.sub), JSON.stringify(de));
  await page.close();

  // ---------------------------------------------------------------- 14. Extra Settings' own work waits under the screen, as the Task Manager poll does
  page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(sdbMock.initScript); await page.addInitScript(xqMock.initScript, fixtures); await page.addInitScript(xsMock.initScript); await page.addInitScript(dnsMock.initScript);
  await page.addInitScript(() => {
    const B = window.AndroidBridge, s = window.__s = { starts: [], stops: [] };
    B.speedTestStart = req => { const r = JSON.parse(req); s.starts.push(r.id); return JSON.stringify({ ok: true, id: r.id }); };
    B.speedTestStop = id => { s.stops.push(id); };
    B.speedTestNetInfo = () => JSON.stringify({ type: 'wifi', metered: false, maxBytes: 350000000 });
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => { XZR_REFRESH_MS = 150; switchView('extrasettings'); }); await page.waitForTimeout(400);           // the zram timer every 150 ms instead of 5 s
  await page.click('#pdnsTest'); await page.waitForTimeout(60);
  const work = () => page.evaluate(() => ({ dns: !!(pdnsScan && pdnsScan.running), cancels: __dns.cancels.slice(), tel: !!telTimer, zram: !!xzrTimer,
    zramReads: __xq.calls.filter(c => /zram0/.test(c.bare)).length, telReads: __tel.calls, open: document.getElementById('stModal').classList.contains('show'), cur: currentViewName() }));
  const w0 = await work();
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  const w1 = await work();
  const scanId = await page.evaluate(() => pdnsScan && pdnsScan.id);
  check('14. a Private DNS speed test still running when "Open speed test" is tapped is stopped (dnsProbeCancel), the telemetry poll and the zram timer stop', w0.dns && w0.tel && w0.zram && w1.open && !w1.dns && w1.cancels.includes(scanId) && !w1.tel && !w1.zram, JSON.stringify([w0, w1]));
  await page.click('#stGo'); await page.waitForTimeout(1000);
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
  await page.waitForTimeout(400);
  const w2 = await work();
  check('    while the screen is open (a test running, the app going to the background and back) no zram read and no telemetry read is sent, and neither timer starts again', w2.open && w2.zramReads === w1.zramReads && w2.telReads === w1.telReads && !w2.tel && !w2.zram, JSON.stringify([w1, w2]));
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(500);
  const w3 = await work();
  check('    closing it, back on Extra Settings: the telemetry poll and the zram timer run again (the zram numbers are read again)', !w3.open && w3.cur === 'extrasettings' && w3.tel && w3.zram && w3.zramReads > w2.zramReads, JSON.stringify([w2, w3]));
  await page.click('#xspOpen'); await page.waitForTimeout(350);
  const w3b = await work();
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(400);                // leaving the tab the screen was opened from, while it is open
  const w3c = await work();
  check('    leaving Extra Settings while the screen is open closes it, and closing it on the way out does not start the telemetry poll or the zram timer (no read follows)', w3b.open && !w3b.tel && !w3b.zram && !w3c.open && w3c.cur === 'apps' && !w3c.tel && !w3c.zram && w3c.zramReads === w3b.zramReads && w3c.telReads === w3b.telReads, JSON.stringify([w3b, w3c]));
  await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(500);
  const w3d = await work();
  check('    back on Extra Settings they run once more', w3d.cur === 'extrasettings' && w3d.tel && w3d.zram && w3d.zramReads > w3c.zramReads, JSON.stringify([w3c, w3d]));
  await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(80);
  await page.evaluate(() => stOpen()); await page.waitForTimeout(350);                // what #tmSpeedBtn calls (its Network page is not the one shown here)
  await page.click('#stModal .sheet-close'); await page.waitForTimeout(80);
  const w4 = await work();
  check('    opened and closed from the Task Manager, it starts neither of them', w4.cur === 'taskmgr' && !w4.tel && !w4.zram, JSON.stringify(w4));
  await page.close();

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
