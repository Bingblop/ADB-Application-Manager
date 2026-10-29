// v7.14.0: Connected Devices' Logcat in Live Logcat's colours (the owner's wish). Play / Stop is the same filled button (green Play, red Stop, black text and
// outline); each row's badge and bar are in its level's dot colour (Verbose green, Debug yellow, Info the accent, Warn orange, Error red, Fatal purple) and the row
// is tinted in it as strongly as Row tints says, in place of the fixed washes; the text stays readable at every step.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const LOG = ['V', 'D', 'I', 'W', 'E', 'F'].map((l, i) => '10-12 10:00:0' + i + '.123  1  1 ' + l + ' Tag' + l + ': message ' + l).join('\n') + '\n';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(400);
  // Row tints > Gradient is off by default since v7.18.1; this script measures the gradient tints, so it turns the switch on (t549 covers the default)
  await page.evaluate(() => setRowTintGrad(true));
  const ev = (fn, a) => page.evaluate(fn, a);
  // a device that answers: the log comes from the test, not adb
  await ev(log => { window.cdReady = () => true; window.cdSh = async () => log; switchView('devices'); cd.sub = 'logcat'; document.querySelectorAll('.cd-pane').forEach(p => p.classList.toggle('active', p.id === 'cdPane-logcat')); }, LOG);
  await ev(() => cdLcRefresh()); await page.waitForTimeout(300);
  const rgb = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 3).map(Number); return /^color\(srgb/.test(s) ? n.map(x => x * 255) : n; };
  const look = () => ev(() => {
    const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
    const ratio = (a, c) => { const x = lum(a), y = lum(c); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const rgb = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 3).map(Number); return /^color\(srgb/.test(s) ? n.map(x => x * 255) : n; };
    const probe = v => { const e = document.createElement('i'); e.style.color = v; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return rgb(c).join(); };
    const want = { V: probe('#2EBD6B'), D: probe('#FFD600'), I: probe('var(--accent)'), W: probe('#FF6D00'), E: probe('var(--lc-e)'), F: probe('var(--lc-f)') };
    const box = document.getElementById('cdLcOut');
    const out = {};
    for (const l of Object.keys(want)) {
      const row = box.querySelector('.lc-row.lvl-' + l); if (!row) { out[l] = null; continue; }
      const be = getComputedStyle(row, '::before'), first = (be.backgroundImage.match(/(rgb|color)\([^)]*\)/) || [''])[0], op = parseFloat(be.opacity);
      const rowBg = getComputedStyle(row).backgroundColor; let up = row; while (up && /rgba\(0, 0, 0, 0\)|transparent/.test(getComputedStyle(up).backgroundColor)) up = up.parentElement;          // the first painted background behind the row
      const base = rgb(up ? getComputedStyle(up).backgroundColor : 'rgb(0, 0, 0)');
      const tint = rgb(first), mixed = base.map((x, i) => x * (1 - op) + tint[i] * op);
      const badge = row.querySelector('.lc-badge'), bs = getComputedStyle(badge);
      out[l] = { tint: tint.join() === want[l], bar: rgb(getComputedStyle(row).borderLeftColor).join() === want[l], badge: rgb(bs.backgroundColor).join() === want[l],
        badgeText: +ratio(rgb(bs.color), rgb(bs.backgroundColor)).toFixed(2), op: +op.toFixed(3), fixedBg: rowBg,
        text: +Math.min.apply(null, [...row.querySelectorAll('.lc-msg, .lc-tag, .lc-time')].map(e => ratio(rgb(getComputedStyle(e).color), mixed))).toFixed(2) };
    }
    return out;
  });
  for (const theme of ['dark', 'light']) {
    await ev(t => { themeState.appearance = t; applyTheme(false); }, theme); await page.waitForTimeout(200);
    const ops = [];
    for (let v = 0; v <= 4; v++) {
      await ev(x => setRowTint(x), v); await page.waitForTimeout(80);
      const r = await look();
      ops.push(r.I && r.I.op);
      const rows = Object.values(r);
      if (v === 2) check(theme + ': every row\'s badge and bar are in its level\'s dot colour, the badge text at 4.5:1 or more', rows.every(x => x && x.badge && x.bar && x.badgeText >= 4.5), JSON.stringify(r));
      if (v > 0) check(theme + ', Row tints step ' + v + ': each row tinted in its dot colour (no fixed wash) and its text at 4.5:1 or more where the tint is strongest',
        rows.every(x => x && x.tint && x.op > 0 && !/rgba\(\d+, \d+, \d+, 0\.\d/.test(x.fixedBg) && x.text >= 4.5), JSON.stringify(Object.fromEntries(Object.entries(r).map(([k, x]) => [k, x && [x.tint, x.op, x.text, x.fixedBg]]))));
    }
    check(theme + ': Off draws no tint and each step more than the one before', ops[0] === 0 && ops.every((o, i) => !i || o > ops[i - 1]), JSON.stringify(ops));
  }
  // Play / Stop
  const btn = () => ev(() => { const e = document.getElementById('cdLcPlay'), s = getComputedStyle(e); return { text: e.innerText, pressed: e.getAttribute('aria-pressed'), color: s.color, border: s.borderTopColor, bg: s.backgroundImage, inline: e.getAttribute('style') || '' }; });
  let p = await btn();
  const green = x => { const st = (x.bg.match(/rgb\(\d+, \d+, \d+\)/g) || []).map(rgb); return st.length >= 2 && st.every(f => f[1] > f[0] + 60 && f[1] > f[2]); };
  const red = x => { const st = (x.bg.match(/rgb\(\d+, \d+, \d+\)/g) || []).map(rgb); return st.length >= 2 && st.every(f => f[0] > f[1] + 60 && f[0] > f[2] + 60); };
  check('Play is filled with the green gradient, black text and outline', p.text === 'Play' && p.pressed === 'false' && green(p) && p.color === 'rgb(0, 0, 0)' && p.border === 'rgb(0, 0, 0)' && !/color/.test(p.inline), JSON.stringify(p));
  await ev(() => cdLcTogglePlay()); await page.waitForTimeout(150);
  p = await btn();
  check('while following it says Stop, filled with the red gradient, black text and outline', p.text === 'Stop' && p.pressed === 'true' && red(p) && p.color === 'rgb(0, 0, 0)' && p.border === 'rgb(0, 0, 0)', JSON.stringify(p));
  await ev(() => cdLcStop());
  check('   and back to Play when stopped', (await btn()).text === 'Play');
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
