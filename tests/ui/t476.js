// v7.14.0: each row of Live Logcat is tinted in the colour of its level's dot (Verbose green, Debug yellow, Info the accent, Warn orange, Error red, Fatal purple),
// a wash from the leading edge as strong as "Row tints" in Settings says: none with Off, more with Normal than with Subtle. The text stays readable on it.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const LOG = ['V', 'D', 'I', 'W', 'E', 'F'].map((l, i) => '10-12 10:00:0' + i + '.123  1  1 ' + l + ' Tag' + l + ': message ' + l).join('\n') + '\n';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1' }, log: LOG });
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => setRowTintGrad(true));   // (Row tints > Gradient on: the glow measured here, the default until v7.18.1; the even colour, the default since, is t549's)
  const ev = (fn, a) => page.evaluate(fn, a);
  const look = () => ev(() => {
    const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
    const ratio = (a, c) => { const x = lum(a), y = lum(c); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const rgb = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 4).map(Number); return /^color\(srgb/.test(s) ? n.slice(0, 3).map(x => x * 255) : n.slice(0, 3); };
    const out = {};
    for (const l of ['V', 'D', 'I', 'W', 'E', 'F']) {
      const row = document.querySelector('#logcatOutput .lc-row.lvl-' + l); if (!row) { out[l] = null; continue; }
      const be = getComputedStyle(row, '::before'), first = (be.backgroundImage.match(/(rgb|color)\([^)]*\)/) || [''])[0];
      const dot = getComputedStyle(document.querySelector('#logcatKey .lvl-' + l + ' .lc-dot')).backgroundColor;
      const op = parseFloat(be.opacity), base = rgb(getComputedStyle(row).backgroundColor), tint = rgb(first);
      const mixed = base.map((x, i) => x * (1 - op) + tint[i] * op);          // the strongest point of the wash, at the leading edge
      const texts = [...row.querySelectorAll('.lc-msg, .lc-tag, .lc-badge')].map(e => ratio(rgb(getComputedStyle(e).color), mixed));
      out[l] = { first, dot, op: +op.toFixed(3), same: rgb(first).join() === rgb(dot).join(), minText: +Math.min.apply(null, texts).toFixed(2) };
    }
    return out;
  });
  for (const theme of ['dark', 'light']) {
    await ev(t => { themeState.appearance = t; applyTheme(false); switchView('logcat'); }, theme); await page.waitForTimeout(600);
    const ops = {};
    for (const [v, name] of [[0, 'off'], [1, 'faint'], [2, 'subtle'], [3, 'normal'], [4, 'vivid']]) {
      await ev(x => setRowTint(x), v); await page.waitForTimeout(100);
      const r = await look();
      ops[name] = r.I && r.I.op;
      if (name === 'off') check(theme + ', off: the level-coloured badge and tag and the text read at 4.5:1 or more on the untinted row', Object.values(r).every(x => x && x.minText >= 4.5), JSON.stringify(Object.fromEntries(Object.entries(r).map(([k, x]) => [k, x && x.minText]))));
      if (name !== 'off') {
        check(theme + ', ' + name + ': every row is tinted in its level dot\'s colour', Object.values(r).every(x => x && x.same && x.op > 0), JSON.stringify(r));
        check(theme + ', ' + name + ': its text keeps at least 4.5:1 where the tint is strongest', Object.values(r).every(x => x.minText >= 4.5), JSON.stringify(Object.fromEntries(Object.entries(r).map(([k, x]) => [k, x.minText]))));
      }
    }
    check(theme + ': Off draws no tint, and each step more than the one before (Faint, Subtle, Normal, Vivid)', ops.off === 0 && ops.faint > 0 && ops.subtle > ops.faint && ops.normal > ops.subtle && ops.vivid > ops.normal, JSON.stringify(ops));
  }
  check('Settings > Row tints says it tints Live Logcat too', await ev(() => /Live Logcat/.test(document.querySelector('#rowTintCard .color-card-subtitle').textContent)));
  await ev(() => logcatStop());
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
