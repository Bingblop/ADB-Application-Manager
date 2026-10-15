// v7.14 Task Manager speeds: the graph redraws every 150 ms by default and as fast as 50 ms; auto-refresh is 1 s by default and as fast as
// 0.5 s. A value saved by an older version keeps working, one that is now out of range is brought to the nearest allowed. The graph is painted
// in an animation frame (never more than one waiting), a redraw that would paint the same picture is skipped, the line scrolls between two
// samples (not with reduced motion), both timers stop when the tab is left or the app is hidden, and a poll's page work stays small: a sample
// with 200 processes is rendered in a few milliseconds and the same sample again does not rewrite the list.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let bad = 0;
  const check = (name, ok, extra) => { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || extra === undefined ? '' : ' ' + extra)); };
  const errors = [];

  async function open(saved, opts) {
    const page = await b.newPage(Object.assign({ viewport: { width: 400, height: 860 } }, opts || {}));
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((saved) => {
      window.__kv = saved ? { taskmgr_ui: JSON.stringify(saved) } : {};
      window.__calls = { tmStart: [], tmStop: 0, tmRefreshNow: 0 };
      window.AndroidBridge = {
        executeShell() { return ''; },
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
        loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
        saveSetting(k, v) { window.__kv[k] = v; },
        loadPackages() { return '[]'; },
        getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
        tmStart(ms) { window.__calls.tmStart.push(ms); }, tmStop() { window.__calls.tmStop++; }, tmRefreshNow() { window.__calls.tmRefreshNow++; }, tmKill() {},
      };
    }, saved || null);
    await page.goto(PAGE); await page.waitForTimeout(300);
    await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(80);
    return page;
  }
  const saved = page => page.evaluate(() => JSON.parse(window.__kv.taskmgr_ui || '{}'));
  const ui = page => page.evaluate(() => { const s = document.getElementById('tmGraphDelay'); return { auto: document.getElementById('tmAutoRefresh').value, graph: s.value, min: s.min, max: s.max, label: document.getElementById('tmGraphDelayVal').innerText, autoMs: tmAutoMs, graphMs: tmGraphMs, started: window.__calls.tmStart.slice(-1)[0] }; });

  // ---------------------------------------------------------------- defaults on a fresh install
  let page = await open(null);
  let u = await ui(page);
  check('1. fresh install: auto-refresh 1 s, graph speed 150 ms, the slider goes from 50 to 1000 ms', u.auto === '1' && u.graph === '150' && u.min === '50' && u.max === '1000' && u.label === '150 ms' && u.autoMs === 1000 && u.graphMs === 150, JSON.stringify(u));
  check('   opening the tab starts the native poll every 1000 ms', u.started === 1000, JSON.stringify(u));
  const choices = await page.evaluate(() => [...document.querySelectorAll('#tmAutoRefresh option')].map(o => o.value + '=' + o.innerText));
  check('2. the auto-refresh choices are Off, 0.5, 1, 2, 3, 5 and 10 s', JSON.stringify(choices) === JSON.stringify(['0=Off', '0.5=0.5 s', '1=1 s', '2=2 s', '3=3 s', '5=5 s', '10=10 s']), JSON.stringify(choices));
  await page.selectOption('#tmAutoRefresh', '0.5'); await page.waitForTimeout(30);
  check('   0.5 s asks the native side for a 500 ms poll and is saved as 500', (await page.evaluate(() => window.__calls.tmStart.slice(-1)[0])) === 500 && (await saved(page)).autoMs === 500);
  await page.fill('#tmGraphDelay', '50'); await page.evaluate(() => document.getElementById('tmGraphDelay').dispatchEvent(new Event('input'))); await page.waitForTimeout(30);
  u = await ui(page);
  check('3. the graph slider reaches 50 ms, shows it and saves it', u.graphMs === 50 && u.label === '50 ms' && (await saved(page)).graphMs === 50, JSON.stringify(u));
  await page.evaluate(() => { const s = document.getElementById('tmGraphDelay'); s.value = '10'; s.dispatchEvent(new Event('input')); }); await page.waitForTimeout(30);
  check('   it cannot go below 50 ms', (await page.evaluate(() => tmGraphMs)) === 50);

  // ---------------------------------------------------------------- the redraw: one animation frame at a time, nothing painted twice
  const counts = await page.evaluate(() => {
    let rafs = 0; const real = window.requestAnimationFrame;
    window.requestAnimationFrame = function (f) { rafs++; return real.call(window, f); };
    tmRedrawFrame && cancelAnimationFrame(tmRedrawFrame); tmRedrawFrame = 0;
    for (let i = 0; i < 10; i++) tmRequestRedraw();
    window.requestAnimationFrame = real;
    return rafs;
  });
  check('4. ten redraw requests before the next frame ask for ONE animation frame (no pile-up)', counts === 1, counts);
  // paints counted at the canvas: CPU tab, one sample, auto-refresh Off (nothing scrolls): the timer at 50 ms paints once, then skips
  await page.evaluate(() => {
    window.__strokes = 0; const real = CanvasRenderingContext2D.prototype.stroke;
    CanvasRenderingContext2D.prototype.stroke = function () { window.__strokes++; return real.apply(this, arguments); };
  });
  await page.selectOption('#tmAutoRefresh', '0'); await page.evaluate(() => tmSetSub('cpu'));
  const sample = (v) => ({ ts: 0, ok: true, privileged: true, cpu: { overall: v, perCore: [v], cores: 1 }, mem: { totalKb: 100, usedKb: 50 }, net: { totalRxBytesPerSec: 0, totalTxBytesPerSec: 0, ifaces: [] }, procs: [], gpu: { available: false }, battery: { percent: 50 } });
  await page.evaluate(s => { onTaskMgrData(s); onTaskMgrData(Object.assign({}, s, { cpu: { overall: 60, perCore: [60], cores: 1 } })); }, sample(20));
  await page.waitForTimeout(150);
  const s0 = await page.evaluate(() => window.__strokes);
  await page.waitForTimeout(600);
  const s1 = await page.evaluate(() => window.__strokes);
  check('5. with nothing new to show, the 50 ms timer paints the graph once and then skips (no repaint in 600 ms)', s0 >= 1 && s1 === s0, s0 + ' then ' + s1);
  await page.evaluate(s => onTaskMgrData(s), sample(30)); await page.waitForTimeout(150);
  check('   a new sample is painted at the next tick', (await page.evaluate(() => window.__strokes)) > s1);

  // with auto-refresh on, the line scrolls between samples: several paints within one period, each a little further left
  await page.selectOption('#tmAutoRefresh', '1'); await page.waitForTimeout(20);
  await page.evaluate(s => { onTaskMgrData(s); onTaskMgrData(s); }, sample(40));       // two at once: the pace is the chosen 1 s, not a slow gap before
  const a0 = await page.evaluate(() => window.__strokes);
  await page.waitForTimeout(600);
  const a1 = await page.evaluate(() => window.__strokes);
  check('6. auto-refresh 1 s, graph 50 ms: the graph scrolls on between samples (many paints in 0.6 s, not one)', a1 - a0 >= 5, (a1 - a0) + ' paints');
  const frac = await page.evaluate(() => tmScrollFraction());
  check('   half a period after a sample it has scrolled about half a step', frac > 0.35 && frac < 0.95, frac);
  await page.waitForTimeout(700);
  const f1 = await page.evaluate(() => tmScrollFraction());
  const b0 = await page.evaluate(() => window.__strokes); await page.waitForTimeout(300); const b1 = await page.evaluate(() => window.__strokes);
  check('   once a full step has passed with no new sample it holds still (no more paints) instead of running ahead', f1 === 1 && b1 === b0, f1 + ' / ' + (b1 - b0));

  // ---------------------------------------------------------------- a poll's page work: 200 processes
  await page.evaluate(() => tmSetSub('processes'));
  const cost = await page.evaluate(() => {
    const procs = []; for (let i = 0; i < 200; i++) procs.push({ pid: 1000 + i, rssKb: 1000 * (i + 1), cpuPercent: (i % 17) / 3, name: 'com.example.app' + i, pkg: i % 3 ? 'com.example.app' + i : '' });
    const s = { ts: 0, ok: true, privileged: true, procs, procsFullFormat: true, cpu: { overall: 10, perCore: [10], cores: 1 }, mem: { totalKb: 100, usedKb: 50 }, net: { ifaces: [] }, gpu: { available: false }, battery: { percent: 50 } };
    let muts = 0; const mo = new MutationObserver(l => { muts += l.length; }); mo.observe(document.getElementById('tmProcList'), { childList: true, subtree: true });
    const t0 = performance.now(); for (let i = 0; i < 10; i++) onTaskMgrData(Object.assign({}, s, { procs: procs.map(p => Object.assign({}, p, { cpuPercent: p.cpuPercent + i })) })); const t1 = performance.now();
    onTaskMgrData(s);                                       // this one differs from the last loop's sample: it rewrites the list once
    return new Promise(r => setTimeout(() => {
      const first = muts;
      const t2 = performance.now(); for (let i = 0; i < 10; i++) onTaskMgrData(s); const t3 = performance.now();
      setTimeout(() => { mo.disconnect(); r({ changedMs: (t1 - t0) / 10, sameMs: (t3 - t2) / 10, rows: document.querySelectorAll('#tmProcList .tm-row').length, firstMuts: first, sameMuts: muts - first }); }, 50);
    }, 50));
  });
  require('fs').writeFileSync('poll-cost.txt', 'page cost of one poll with 200 processes: ' + cost.changedMs.toFixed(2) + ' ms (changed), ' + cost.sameMs.toFixed(2) + ' ms (same)\n');   // a timing: kept beside the output, not in it
  check('7. a poll with 200 changed processes renders the 200 rows well inside one 500 ms period (under 100 ms here)', cost.rows === 200 && cost.changedMs < 100, JSON.stringify(cost));
  check('   the same sample again does not rewrite the list (no DOM change)', cost.firstMuts > 0 && cost.sameMuts === 0, JSON.stringify(cost));

  // ---------------------------------------------------------------- stopping
  await page.evaluate(() => tmSetSub('cpu'));
  const stops = await page.evaluate(() => window.__calls.tmStop);
  await page.evaluate(() => switchView('about')); await page.waitForTimeout(30);
  check('8. leaving the tab stops the native poll and both page timers (interval and waiting frame)', (await page.evaluate(() => window.__calls.tmStop)) > stops && (await page.evaluate(() => tmRedrawTimer === null && tmRedrawFrame === 0)));
  const p0 = await page.evaluate(() => window.__strokes); await page.waitForTimeout(300);
  check('   and nothing is painted after that', (await page.evaluate(() => window.__strokes)) === p0);
  await page.close();

  // ---------------------------------------------------------------- saved values from an older version
  page = await open({ sub: 'ram', autoMs: 3000, graphMs: 300 });
  u = await ui(page);
  check('9. an older saved 3 s and 300 ms keep working as they were', u.auto === '3' && u.graph === '300' && u.label === '300 ms' && u.started === 3000, JSON.stringify(u));
  await page.close();
  page = await open({ autoMs: 10000, graphMs: 1000 });
  u = await ui(page);
  check('   so do 10 s and 1000 ms', u.auto === '10' && u.graph === '1000' && u.started === 10000, JSON.stringify(u));
  await page.close();
  page = await open({ autoMs: 0, graphMs: 150 });
  u = await ui(page);
  check('   and Off (one sample on opening, no poll)', u.auto === '0' && u.started === undefined && (await page.evaluate(() => window.__calls.tmRefreshNow)) === 1, JSON.stringify(u));
  await page.close();
  page = await open({ autoMs: 250, graphMs: 20 });
  u = await ui(page);
  check('10. out of range: 250 ms becomes 0.5 s and a 20 ms graph becomes 50 ms', u.auto === '0.5' && u.graph === '50' && u.started === 500, JSON.stringify(u));
  await page.close();
  page = await open({ autoMs: 7000, graphMs: 5000 });
  u = await ui(page);
  check('    7 s becomes the nearest choice (5 s) and 5000 ms becomes 1000 ms', u.auto === '5' && u.graph === '1000' && u.started === 5000, JSON.stringify(u));
  await page.close();

  // ---------------------------------------------------------------- reduced motion: no scroll, the graph moves in steps as before
  page = await open(null, { reducedMotion: 'reduce' });
  await page.evaluate(() => tmSetSub('cpu'));
  await page.evaluate(s => { onTaskMgrData(s); onTaskMgrData(s); }, sample(50)); await page.waitForTimeout(400);
  check('11. with reduced motion the graph does not scroll between samples', (await page.evaluate(() => tmScrollFraction())) === 0);
  await page.evaluate(() => { window.__h = document.hidden; });
  const st0 = await page.evaluate(() => window.__calls.tmStop);
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  check('12. the app going to the background (screen off) stops the poll and the graph timer', (await page.evaluate(() => window.__calls.tmStop)) > st0 && (await page.evaluate(() => tmRedrawTimer === null)));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
