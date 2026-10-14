// Every status badge reads at 4.5:1 or better against what it really sits on. applyTheme() makes each status colour (RUNNING, FROZEN,
// SYSTEM, USER, SUSPENDED, UNINSTALLED, the MOD tag of a patched app, and the green of the mode badge in the header) dark enough on the
// light theme and light enough on the dark one for the page, the cards, the sheets, a row tinted for its state or for the selection,
// with the badge's own tint on top. Measured here on the page: from each badge up to the first solid background, every veil in between
// laid over it, in light and dark, for Material 3 (the default) and the Cyberpunk, AMOLED and Sunset presets, on a plain row, a selected
// row and with the Normal row tints. The colours are made where the theme is made: no rule has a light-mode twin.
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }
const PRESETS = ['material3', 'cyberpunk', 'amoled', 'amber'];

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const measure = page => page.evaluate(() => {
    const rgba = s => { const k = /^color\(srgb/.test(s) ? 255 : 1, m = s.replace(/^color\(srgb/, '').match(/[\d.]+/g).map(Number); return { r: m[0] * k, g: m[1] * k, b: m[2] * k, a: m.length > 3 ? m[3] : 1 }; };
    const over = (t, u) => ({ r: t.r * t.a + u.r * (1 - t.a), g: t.g * t.a + u.g * (1 - t.a), b: t.b * t.a + u.b * (1 - t.a), a: 1 });
    const lum = c => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
    const cr = (x, y) => { const p = lum(x), q = lum(y); return (Math.max(p, q) + 0.05) / (Math.min(p, q) + 0.05); };
    // what is behind an element: the first solid background up the tree, with every see-through one between laid on top of it
    const behind = el => {
      const veils = []; let base = null;
      for (let p = el; p; p = p.parentElement) { const c = rgba(getComputedStyle(p).backgroundColor); if (c.a >= 1) { base = c; break; } if (c.a > 0) veils.unshift(c); }
      if (!base) base = rgba(getComputedStyle(document.documentElement).backgroundColor);
      let u = base; veils.forEach(v => { u = over(v, u); }); return u;
    };
    const out = {};
    const add = (k, el) => { if (!el || !el.getClientRects().length) return; const c = cr(rgba(getComputedStyle(el).color), behind(el)); if (!(k in out) || c < out[k]) out[k] = Math.round(c * 100) / 100; };
    document.querySelectorAll('#view-apps .tag-badge').forEach(el => add(el.className.replace('tag-badge', '').trim().replace('tag-', '') + (el.closest('.app-card.selected') ? ' (selected row)' : ''), el));
    add('mode badge', document.getElementById('execModeText'));
    return out;
  });
  for (const preset of PRESETS) for (const dark of [false, true]) {
    const t = preset + ' ' + (dark ? 'dark' : 'light');
    const page = await b.newPage({ viewport: { width: 400, height: 1400 } });
    page.on('pageerror', e => errors.push(t + ' ' + e.message));
    await page.addInitScript(appsMock.initScript, { dark, preset });
    await page.goto(PAGE); await page.waitForTimeout(700);
    await page.evaluate(() => switchView('apps')); await page.waitForTimeout(300);
    const plain = await measure(page);
    const kinds = Object.keys(plain).sort();
    check(t + ': badges on the page: ' + kinds.join(', '), kinds.length === 8, kinds.join(', '));
    const low = Object.entries(plain).filter(([, v]) => v < 4.5);
    check(t + ': every badge at 4.5:1 or more on its row', !low.length, JSON.stringify(low));
    // a selected row (the accent's tint) and the Normal row tints (green, blue, red rows)
    await page.evaluate(() => { toggleSelectPkg('org.mozilla.firefox'); toggleSelectPkg('com.android.chrome'); document.documentElement.setAttribute('data-row-tint', 'normal'); }); await page.waitForTimeout(300);
    const more = await measure(page);
    const sel = Object.keys(more).filter(k => / \(selected row\)$/.test(k));
    const low2 = Object.entries(more).filter(([, v]) => v < 4.5);
    check(t + ': on selected rows and with the Normal row tints too', sel.length >= 3 && !low2.length, JSON.stringify(low2) + ' selected: ' + sel.length);
    await page.close();
  }
  // no light-mode twin: no rule of the page names a status colour under the light appearance
  const twins = await (async () => {
    const page = await b.newPage();
    await page.addInitScript(appsMock.initScript, {});
    await page.goto(PAGE); await page.waitForTimeout(500);
    const r = await page.evaluate(() => [...document.styleSheets].flatMap(s => { try { return [...s.cssRules]; } catch (e) { return []; } })
      .filter(r => r.selectorText && /data-appearance="?light/.test(r.selectorText) && /(tag-|status-|apps-)/.test(r.cssText)).map(r => r.selectorText));
    await page.close(); return r;
  })();
  check('no light-mode twin rule for a badge or a status colour', twins.length === 0, twins.join(', '));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
