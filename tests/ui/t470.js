// v7.14.0: the coloured action buttons are filled, not outlined (the owner's choice). Uninstall is a red gradient, Freeze an ice-blue one,
// Reinstall the green, each with black text; Enable is the accent with --on-accent, which is black wherever black reads (4.5:1) and white
// only where it would not. The same in the app menu, the batch bar, Batch Ops-style grids (.batch-grid-btn) and Connected Devices' rows.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);

  // the classes of the real buttons
  const cls = await ev(() => {
    const c = id => document.getElementById(id).className;
    const grid = t => { const e = [...document.querySelectorAll('.batch-grid-btn')].find(x => (x.getAttribute('onclick') || '') === "batchAction('" + t + "')"); return e && e.className; };
    const cd = t => { const e = [...document.querySelectorAll('.cd-row-act')].find(x => (x.getAttribute('onclick') || '') === "cdBatch('" + t + "')"); return e && e.className; };
    return { sheet: [c('sheetBtnUninstall'), c('sheetBtnFreeze'), c('sheetBtnReinstall'), c('sheetBtnUnfreeze')], grid: ['uninstall', 'freeze', 'reinstall', 'unfreeze'].map(grid), cd: ['uninstall', 'disable', 'reinstall', 'enable'].map(cd) };
  });
  check('1. Uninstall, Freeze, Reinstall and Enable carry the red, blue, green and accent classes in the app menu, the batch bar and Connected Devices',
    JSON.stringify(cls.sheet) === JSON.stringify(['sheet-btn sheet-btn-red', 'sheet-btn sheet-btn-blue', 'sheet-btn sheet-btn-green', 'sheet-btn sheet-btn-accent'])
    && JSON.stringify(cls.grid) === JSON.stringify(['batch-grid-btn danger', 'batch-grid-btn blue', 'batch-grid-btn green', 'batch-grid-btn accent'])
    && JSON.stringify(cls.cd) === JSON.stringify(['cd-row-act danger', 'cd-row-act blue', 'cd-row-act green', 'cd-row-act accent']), JSON.stringify(cls));

  const sample = () => ev(() => {
    const host = document.createElement('div'); document.body.appendChild(host);
    const kinds = { red: ['sheet-btn sheet-btn-red', 'batch-grid-btn danger', 'cd-row-act danger'], blue: ['sheet-btn sheet-btn-blue', 'batch-grid-btn blue', 'cd-row-act blue'],
      green: ['sheet-btn sheet-btn-green', 'batch-grid-btn green', 'cd-row-act green'], accent: ['sheet-btn sheet-btn-accent', 'batch-grid-btn accent', 'cd-row-act accent', 'cd-row-act'] };
    const lum = rgb => { const v = rgb.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
    const ratio = (a, c) => { const x = lum(a), y = lum(c); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const nums = s => (s.match(/\d+(\.\d+)?/g) || []).map(Number);
    const out = {};
    for (const [k, list] of Object.entries(kinds)) out[k] = list.map(c => {
      const e = document.createElement('button'); e.className = c; e.textContent = 'x'; host.appendChild(e);
      const s = getComputedStyle(e), text = nums(s.color).slice(0, 3);
      const stops = (s.backgroundImage.match(/rgb\(\d+, \d+, \d+\)/g) || []).map(nums);
      const fills = stops.length ? stops : [nums(s.backgroundColor).slice(0, 3)];
      return { c, text: s.color, fills, alpha: /rgba/.test(s.backgroundColor) && !stops.length ? nums(s.backgroundColor)[3] : 1, minRatio: Math.min.apply(null, fills.map(f => ratio(f, text))) };
    });
    host.remove();
    return out;
  });
  const look = s => {
    const bad = [];
    const all = Object.values(s).flat();
    for (const x of all) { if (x.alpha < 1) bad.push(x.c + ' not filled'); if (x.minRatio < 4.5) bad.push(x.c + ' contrast ' + x.minRatio.toFixed(2)); }
    for (const k of ['red', 'blue', 'green']) for (const x of s[k]) if (x.text !== 'rgb(0, 0, 0)') bad.push(x.c + ' text ' + x.text);
    for (const x of s.red) { const f = x.fills[x.fills.length - 1]; if (x.fills.length < 2 || !(f[0] > f[1] + 100 && f[0] > f[2] + 100)) bad.push(x.c + ' not a red gradient ' + JSON.stringify(x.fills)); }
    for (const x of s.blue) { const f = x.fills[x.fills.length - 1]; if (x.fills.length < 2 || !(f[2] > f[0] + 60 && f[2] >= f[1])) bad.push(x.c + ' not ice blue ' + JSON.stringify(x.fills)); }
    for (const x of s.green) { const f = x.fills[x.fills.length - 1]; if (!(f[1] > f[0] + 100 && f[1] > f[2] + 60)) bad.push(x.c + ' not green ' + JSON.stringify(x.fills)); }
    return bad;
  };
  let s = await sample();
  check('2. dark theme: every one is filled (no outline-only button), Uninstall a red gradient, Freeze ice blue, Reinstall green, black text on each, contrast at least 4.5:1 on every colour stop', look(s).length === 0, look(s).join(' | '));
  const acc = await ev(() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim());
  check('   Enable is the accent itself', s.accent.every(x => x.fills.length === 1), JSON.stringify(s.accent.map(x => x.fills)) + ' accent ' + acc);

  await ev(() => { themeState.appearance = 'light'; applyTheme(false); }); await page.waitForTimeout(150);
  const isLight = await ev(() => document.documentElement.getAttribute('data-appearance'));
  s = await sample();
  check('3. light theme: the same fills and black text (fixed colours, not the darker light-theme status colours)', isLight === 'light' && look(s).length === 0, look(s).join(' | '));

  // the accent's text: black wherever it reads, white only where it would not
  const onAcc = await ev(() => {
    const orig = window.currentScheme, res = {};
    for (const a of ['#00E5FF', '#FFC400', '#7C4DFF', '#1E88E5', '#D81B60', '#3D5AFE', '#43A047', '#8E8E8E']) {
      window.currentScheme = () => Object.assign({}, orig(), { accent: a });
      applyTheme(false);
      res[a] = [getComputedStyle(document.documentElement).getPropertyValue('--on-accent').trim(), +contrastRatio(a, '#000000').toFixed(2), +contrastRatio(a, '#FFFFFF').toFixed(2)];
    }
    window.currentScheme = orig; applyTheme(false);
    return res;
  });
  const wrong = Object.entries(onAcc).filter(([a, [on, blk, wht]]) => on !== (blk >= 4.5 || blk >= wht ? '#000000' : '#FFFFFF'));
  check('4. the accent buttons\' text is black where black has 4.5:1 or more (or beats white), white otherwise', wrong.length === 0 && onAcc['#00E5FF'][0] === '#000000' && onAcc['#3D5AFE'][0] === '#FFFFFF', JSON.stringify(onAcc));

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
