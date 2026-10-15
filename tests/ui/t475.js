// v7.14.0: Live Logcat colours (the owner's choice). The dot of the Verbose chip is a dark green (Info keeps its bright green; the Verbose rows keep their grey),
// and Record to file has a red dot before its label, which is in the text colour; while recording the dot is a square and the button turns orange.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1' }, log: '10-12 10:00:01.123  1  1 V Tag: hello\n' });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);
  const rgb = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 3).map(Number); return /^color\(srgb/.test(s) ? n.map(x => x * 255) : n; };
  for (const theme of ['dark', 'light']) {
    await ev(t => { themeState.appearance = t; applyTheme(false); switchView('logcat'); logcatStop(); }, theme); await page.waitForTimeout(300);
    const r = await ev(() => {
      const dot = l => getComputedStyle(document.querySelector('#logcatKey .lvl-' + l + ' .lc-dot')).backgroundColor;
      const rec = document.getElementById('logcatRecBtn'), before = getComputedStyle(rec, '::before');
      const badge = document.querySelector('#logcatOutput .lc-row.lvl-V .lc-badge');
      return { v: dot('V'), i: dot('I'), recText: rec.innerText.trim(), recColor: getComputedStyle(rec).color, main: getComputedStyle(document.body).color, mark: before.content, markColor: before.color, vBadge: badge ? getComputedStyle(badge).color : '' };
    });
    const v = rgb(r.v), i = rgb(r.i), m = rgb(r.markColor);
    check(theme + ': the Verbose dot is a dark green, darker than Info\'s green', v[1] > v[0] + 50 && v[1] > v[2] + 40 && v[1] < 150 && v[1] < i[1] - 40, JSON.stringify([r.v, r.i]));
    check(theme + ': Record to file reads "Record to file" in the text colour, with a red dot before it', r.recText === 'Record to file' && r.recColor === r.main && r.mark === '"●"' && m[0] > 200 && m[1] < 90 && m[2] < 90, JSON.stringify(r));
    check(theme + ': the Verbose rows keep their grey badge', !r.vBadge || (() => { const c = rgb(r.vBadge); return Math.max(...c) - Math.min(...c) < 30; })(), r.vBadge);
  }
  await ev(() => lcRecButton({ running: true, lines: 5 }));
  const on = await ev(() => { const rec = document.getElementById('logcatRecBtn'), be = getComputedStyle(rec, '::before'); return { text: rec.innerText.trim(), mark: be.content, color: getComputedStyle(rec).color, markColor: be.color }; });
  check('while recording: "Stop recording · 5 lines" after an orange square', on.text === 'Stop recording · 5 lines' && on.mark === '"■"' && on.markColor === on.color && (() => { const c = rgb(on.color); return c[0] > c[2] + 60 && c[1] > c[2]; })(), JSON.stringify(on));
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
