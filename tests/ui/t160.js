// Logcat parses only the new poll batch, keeps multiline entries together, resets on filter changes, and reparses when its bounded store trims.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const web = require('./lib/web_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 860 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(web.install);
  await page.addInitScript(() => {
    const bridge = window.AndroidBridge;
    bridge.getLogcatAsync = () => 'started';
    bridge.clearLogcat = () => 'cleared';
  });
  await page.goto(PAGE);
  await page.waitForTimeout(450);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => {
    window.__fullParses = 0;
    const parse = window.logcatParse;
    window.logcatParse = function (text) { window.__fullParses++; return parse(text); };
    isPrivilegedActive = true;
    logcatStop();
    lcStoreReset();
    lcSig = lcSigNow();
    logcatNotice('(cleared)');
    window.__fullParses = 0;
  });
  const line = (sec, level, tag, message) => `10-03 12:00:${sec}.000  100  200 ${level} ${tag}: ${message}`;
  const initial = [line('01', 'I', 'Trace', 'start'), '    at com.example.First.run(First.java:1)'].join('\n');
  await ev(([text, sig]) => logcatRender(text, false, true, sig), [initial, await ev(() => lcSigNow())]);
  let state = await ev(() => ({
    fullParses: window.__fullParses,
    entries: logcatEntries.filter(entry => entry.kind === 'entry').map(entry => ({ messages: entry.lines.length, raw: entry.raws.length })),
    info: logcatCounts.I
  }));
  check('first poll uses the incremental parser and groups a continuation line', state.fullParses === 0 && state.entries.length === 1 && state.entries[0].messages === 2 && state.info === 2, JSON.stringify(state));

  const next = [
    line('01', 'I', 'Trace', 'start'),
    '    at com.example.First.run(First.java:1)',
    line('02', 'I', 'Trace', 'finish'),
    '    at com.example.First.done(First.java:2)'
  ].join('\n');
  await ev(([text, sig]) => logcatRender(text, false, true, sig), [next, await ev(() => lcSigNow())]);
  state = await ev(() => ({
    fullParses: window.__fullParses,
    entries: logcatEntries.filter(entry => entry.kind === 'entry').map(entry => ({ messages: entry.lines.length, raw: entry.raws.length })),
    info: logcatCounts.I,
    rows: document.querySelectorAll('#logcatOutput .lc-row').length
  }));
  check('next poll appends just new lines, retaining multiline groups and counts', state.fullParses === 0 && JSON.stringify(state.entries) === JSON.stringify([{ messages: 2, raw: 2 }, { messages: 2, raw: 2 }]) && state.info === 4 && state.rows === 2, JSON.stringify(state));

  await ev(() => { document.getElementById('logcatLevel').value = 'E'; });
  await ev(([text, sig]) => logcatRender(text, false, true, sig), [line('03', 'E', 'Crash', 'fresh filter'), await ev(() => lcSigNow())]);
  state = await ev(() => ({
    entries: logcatEntries.filter(entry => entry.kind === 'entry').map(entry => entry.tag),
    info: logcatCounts.I,
    error: logcatCounts.E
  }));
  check('changing the filter starts a fresh store and fresh counters', state.entries.join() === 'Crash' && state.info === 0 && state.error === 1, JSON.stringify(state));

  await ev(() => {
    lcStore = Array.from({ length: 8000 }, (_, i) => `10-03 12:01:${String(i % 60).padStart(2, '0')}.000  300  301 I Bulk${i}: entry ${i}`);
    lcSeen = new Set(lcStore.map(raw => raw + '\u0001' + 0));
    lcNote = ''; lcRenderedNote = '';
    logcatEntries = logcatParse(lcStore.join('\n'));
    logcatCounts = { V: 0, D: 0, I: 0, W: 0, E: 0, F: 0 };
    logcatEntries.forEach(entry => { if (entry.kind === 'entry') logcatCounts[entry.lvl] += entry.lines.length; });
    window.__fullParses = 0;
  });
  await ev(([text, sig]) => logcatRender(text, false, true, sig), [line('04', 'W', 'AfterTrim', 'retained'), await ev(() => lcSigNow())]);
  state = await ev(() => ({
    fullParses: window.__fullParses,
    stored: lcStore.length,
    containsNewest: logcatEntries.some(entry => entry.kind === 'entry' && entry.tag === 'AfterTrim'),
    warning: logcatCounts.W
  }));
  check('when the bounded store trims, a full reparse keeps the newest log and correct counts', state.fullParses === 1 && state.stored === 8000 && state.containsNewest && state.warning === 1, JSON.stringify(state));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await browser.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(error => { console.error(error); process.exit(1); });
