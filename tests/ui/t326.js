// "Ask agent" is a quiet ghost button: on every Hidden Settings row (and on the overlays and debloater rows that carry it) it used to be an accent
// outlined pill, the loudest thing in the list. Now it is muted text on a faint fill (--fill-2) with a clear border, still a pill with a pointer
// that reads as a button, and the accent comes only while it is pressed or has keyboard focus. Its text keeps at least 4.5:1 against what is
// under it in the dark and the light theme, it stays 36px tall with its 44px hit area, and a tap still opens the Ask Agent window.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  for (const dark of [true, false]) {
    const t = dark ? 'dark' : 'light';
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(sdb.initScript);
    await page.addInitScript(dark => {
      Object.assign(window.AndroidBridge, {
        vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: dark ? 'dark' : 'light', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
        loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return dark; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
        loadSetting() { return ''; }, saveSetting() { return true; }, saveStore() {}, loadStore() { return ''; }, executeShell() { return ''; },
      });
    }, dark);
    await page.goto(PAGE); await page.waitForTimeout(600);
    await page.evaluate(() => switchView('settings')); await page.waitForTimeout(700);
    const sel = '#sdbList .sdb-row .agent-btn';
    await page.evaluate(sel => document.querySelector(sel).scrollIntoView({ block: 'center' }), sel);
    // the colours of the button and of what is under it, and the theme's own accent, muted text and --fill-2
    const look = () => page.evaluate(sel => {
      const btn = document.querySelector(sel), cs = getComputedStyle(btn), root = getComputedStyle(document.documentElement);
      const probe = c => { const d = document.createElement('div'); d.style.color = c; document.body.appendChild(d); const v = getComputedStyle(d).color; d.remove(); return v; };
      const probeBg = c => { const d = document.createElement('div'); d.style.background = c; document.body.appendChild(d); const v = getComputedStyle(d).backgroundColor; d.remove(); return v; };
      const rgba = s => { const k = /^color\(srgb/.test(s) ? 255 : 1, m = s.replace(/^color\(srgb/, '').match(/[\d.]+/g).map(Number); return { r: m[0] * k, g: m[1] * k, b: m[2] * k, a: m.length > 3 ? m[3] : 1 }; };          // rgb(), rgba() or color(srgb r g b / a)
      const over = (top, under) => ({ r: top.r * top.a + under.r * (1 - top.a), g: top.g * top.a + under.g * (1 - top.a), b: top.b * top.a + under.b * (1 - top.a), a: 1 });
      // what is under the button: the first ancestor with a solid background, with every veil in between laid on top of it
      const veils = []; let base = null;
      for (let p = btn.parentElement; p; p = p.parentElement) {
        const c = rgba(getComputedStyle(p).backgroundColor);
        if (c.a >= 1) { base = c; break; }
        if (c.a > 0) veils.unshift(c);
      }
      if (!base) base = rgba(getComputedStyle(document.body).backgroundColor);
      let under = base; veils.forEach(v => { under = over(v, under); });
      const fill = rgba(cs.backgroundColor), behind = over(fill, under), text = rgba(cs.color);
      const lum = c => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
      const cr = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
      const r = btn.getBoundingClientRect();
      return {
        color: cs.color, bg: cs.backgroundColor, border: cs.borderTopColor, radius: cs.borderTopLeftRadius, cursor: cs.cursor, outline: cs.outlineStyle,
        accent: probe(root.getPropertyValue('--accent')), muted: probe(root.getPropertyValue('--text-muted')), fill2: probeBg('var(--fill-2)'),
        contrast: Math.round(cr(text, behind) * 100) / 100, h: Math.round(r.height),
      };
    }, sel);
    const rest = await look();
    check(t + ': at rest the text is the muted grey, not the accent', rest.color === rest.muted && rest.color !== rest.accent, rest.color + ' muted ' + rest.muted + ' accent ' + rest.accent);
    check(t + ': on the faint --fill-2 fill with a clear border', rest.bg === rest.fill2 && /rgba\(\d+, \d+, \d+, 0\)|transparent/.test(rest.border), rest.bg + ' / ' + rest.border);
    check(t + ': still a pill with a pointer, 36px tall', parseFloat(rest.radius) > 100 && rest.cursor === 'pointer' && rest.h === 36, rest.radius + ' ' + rest.cursor + ' ' + rest.h);
    check(t + ': its text has at least 4.5:1 against what is under it', rest.contrast >= 4.5, String(rest.contrast));
    // pressed and keyboard focus: forced through DevTools, so nothing is tapped
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('DOM.enable'); await cdp.send('CSS.enable');
    const { root } = await cdp.send('DOM.getDocument', { depth: 0 });
    const { nodeId } = await cdp.send('DOM.querySelector', { nodeId: root.nodeId, selector: sel });
    for (const st of ['active', 'focus-visible']) {
      await cdp.send('CSS.forcePseudoState', { nodeId, forcedPseudoClasses: [st] }); await page.waitForTimeout(250);
      const s = await look();
      check(t + ': :' + st + ' turns the text and the border to the accent', s.color === s.accent && s.border !== rest.border && s.bg !== rest.bg, s.color + ' / ' + s.border + ' / ' + s.bg);
      if (st === 'focus-visible') check(t + ': and keyboard focus draws a ring', s.outline === 'solid', s.outline);
      await cdp.send('CSS.forcePseudoState', { nodeId, forcedPseudoClasses: [] }); await page.waitForTimeout(250);
    }
    const back = await look();
    check(t + ': and back to muted after', back.color === rest.color && back.bg === rest.bg);
    await page.locator(sel).first().click(); await page.waitForTimeout(500);
    const open = await page.evaluate(() => document.getElementById('agentAskModal').classList.contains('show'));
    check(t + ': a tap still opens the Ask Agent window', open);
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
})();
