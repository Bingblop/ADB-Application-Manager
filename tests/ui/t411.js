// v7.14 Apps list rows: only a row the person selected has an outline (the accent, 2px: the border and a ring inside it); a running,
// frozen or uninstalled app keeps the plain hairline and gets a glow in its state's colour that fades across the row, as strong as
// Settings > Row tints says (Off, Subtle, Normal). Checked in dark and light: the borders, the tint layer's strength and colour, a
// status badge still reads at 4.5:1 on what is really drawn behind it (measured on the pixels, with the text hidden), and the rows look
// the same in a WebView without color-mix() (before Chrome 111), where the old rules drew a white outline and no background.
const fs = require('fs');
const { fileURLToPath } = require('url');
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (opts, noColorMix) => {
    const page = await b.newPage({ viewport: { width: 400, height: 1500 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(appsMock.initScript, opts);
    if (noColorMix) await page.route(u => u.href === PAGE, r => r.fulfill({ contentType: 'text/html', body: fs.readFileSync(fileURLToPath(PAGE), 'utf8').replace(/color-mix\(/g, 'no-color-mix(') }));
    await page.goto(PAGE); await page.waitForTimeout(600);
    await page.evaluate(() => switchView('apps')); await page.waitForTimeout(300);
    return page;
  };
  // every row: its border, ring, background and tint layer
  const rows = page => page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    const probe = c => { const e = document.createElement('i'); e.style.color = c; document.body.appendChild(e); const v = getComputedStyle(e).color; e.remove(); return v; };
    const accent = probe(root.getPropertyValue('--accent').trim()), glass = probe(root.getPropertyValue('--border-glass').trim()), card = probe(root.getPropertyValue('--bg-card').trim());
    const ink = { running: probe(root.getPropertyValue('--apps-running').trim()), frozen: probe(root.getPropertyValue('--apps-frozen').trim()), uninstalled: probe(root.getPropertyValue('--apps-uninstalled').trim()) };
    return { accent, glass, card, ink, rows: [...document.querySelectorAll('#view-apps .app-card')].map(c => {
      const s = getComputedStyle(c), a = getComputedStyle(c, '::before');
      const state = ['running', 'frozen', 'uninstalled'].find(k => c.classList.contains(k)) || '';
      return { pkg: c.dataset.pkg, state, selected: c.classList.contains('selected'), border: s.borderTopColor, width: s.borderTopWidth, shadow: s.boxShadow, bg: s.backgroundColor, o: a.content === 'none' ? 0 : +a.opacity, img: a.backgroundImage };
    }) };
  });
  const summary = (r) => {
    const plain = r.rows.filter(x => !x.selected), sel = r.rows.filter(x => x.selected);
    return {
      outlineFree: plain.every(x => x.border === r.glass && x.shadow === 'none' && x.width === '1px'),
      outlined: plain.filter(x => x.border !== r.glass || x.shadow !== 'none').map(x => x.pkg + ' ' + x.border + ' ' + x.shadow),
      selOk: sel.length > 0 && sel.every(x => x.border === r.accent && x.shadow.indexOf(r.accent) === 0 && /inset/.test(x.shadow)),
      bgOk: r.rows.every(x => x.bg === r.card),
      tint: [...new Set(plain.filter(x => x.state).map(x => x.o))],
      untinted: plain.filter(x => !x.state).every(x => x.o === 0 && x.img === 'none'),
      colours: plain.filter(x => x.state).every(x => x.img.indexOf(r.ink[x.state]) >= 0),
      selTint: sel.map(x => x.o),
      selGradient: sel.every(x => x.img.indexOf(r.accent) >= 0),
    };
  };
  for (const dark of [true, false]) for (const preset of ['material3', 'cyberpunk', 'amoled', 'amber']) {
    const t = (dark ? 'dark' : 'light') + ' ' + preset;
    const page = await open({ dark, preset });
    await page.evaluate(() => { toggleSelectPkg('com.android.chrome'); toggleSelectPkg('com.amazon.mShop.android.shopping'); }); await page.waitForTimeout(250);
    for (const [v, name, want] of [[0, 'Off', 0], [1, 'Faint', 0.06], [2, 'Subtle', 0.11], [3, 'Normal', 0.2], [4, 'Vivid', 0.24]]) {
      await page.evaluate(v => setRowTint(v), v); await page.waitForTimeout(120);
      const s = summary(await rows(page));
      check(`${t}, Row tints ${name}: no row that is not selected has an outline (running, frozen and uninstalled ones neither)`, s.outlineFree, s.outlined.join(' | '));
      check(`${t}, Row tints ${name}: a selected row (a running one and a plain one) has the accent outline, 2px`, s.selOk);
      check(`${t}, Row tints ${name}: every row keeps the card's own background under its tint`, s.bgOk);
      check(`${t}, Row tints ${name}: running, frozen and uninstalled rows are tinted at ${want}`, s.tint.length === 1 && Math.abs(s.tint[0] - want) < 0.001, JSON.stringify(s.tint));
      check(`${t}, Row tints ${name}: the other rows are not tinted`, s.untinted);
      check(`${t}, Row tints ${name}: each tint is drawn in its state's colour`, s.colours);
      check(`${t}, Row tints ${name}: the selected rows are drawn with the accent gradient whatever the setting`, s.selGradient && s.selTint.every(o => Math.abs(o - 0.13) < 0.001), JSON.stringify(s.selTint));
    }
    // the badges read on what is really behind them (Vivid is the strongest tint; the selected rows too): the text hidden, the pixels averaged
    await page.evaluate(() => setRowTint(4)); await page.waitForTimeout(150);
    const badges = await page.evaluate(() => [...document.querySelectorAll('#view-apps .app-card .tag-badge')].map((e, i) => { e.dataset.t411 = i; const r = e.getBoundingClientRect(); return { i, kind: e.textContent, sel: !!e.closest('.selected'), color: getComputedStyle(e).color, x: r.left + 3, y: r.top + 3, w: r.width - 6, h: r.height - 6 }; }));
    await page.addStyleTag({ content: '[data-t411] { color: transparent !important; }' });
    await page.evaluate(() => { const s = document.createElement('canvas'); s.id = 't411c'; s.style.display = 'none'; document.body.appendChild(s); });
    const low = [];
    for (const g of badges) {
      const png = (await page.screenshot({ clip: { x: g.x, y: g.y, width: g.w, height: g.h } })).toString('base64');
      const ratio = await page.evaluate(async ([png, color]) => {
        const img = new Image(); img.src = 'data:image/png;base64,' + png; await img.decode();
        const c = document.getElementById('t411c'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0);
        const d = x.getImageData(0, 0, c.width, c.height).data; let r = 0, gg = 0, bb = 0, n = 0;
        for (let i = 0; i < d.length; i += 4) { r += d[i]; gg += d[i + 1]; bb += d[i + 2]; n++; }
        const lum = ([r, g, b]) => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
        const fg = color.match(/[\d.]+/g).slice(0, 3).map(Number), bg = [r / n, gg / n, bb / n];
        const p = lum(fg), q = lum(bg); return (Math.max(p, q) + 0.05) / (Math.min(p, q) + 0.05);
      }, [png, g.color]);
      if (ratio < 4.5) low.push(g.kind + (g.sel ? ' (selected)' : '') + ' ' + ratio.toFixed(2));
    }
    check(`${t}: every badge on a tinted or selected row reads at 4.5:1 or more on the pixels behind it (${badges.length} badges)`, badges.length >= 10 && !low.length, low.join(', '));
    await page.close();
  }
  // a WebView without color-mix(): the rows look the same (no white outline, the card's background), the tint and the selection are drawn
  for (const dark of [true, false]) {
    const page = await open({ dark }, true);
    await page.evaluate(() => { toggleSelectPkg('com.android.chrome'); setRowTint(3); }); await page.waitForTimeout(250);
    const s = summary(await rows(page));
    const t = (dark ? 'dark' : 'light') + ', no color-mix()';
    check(`${t}: no row that is not selected has an outline`, s.outlineFree, s.outlined.join(' | '));
    check(`${t}: the selected row has the accent outline`, s.selOk);
    check(`${t}: every row keeps the card's background`, s.bgOk);
    check(`${t}: the state tints are drawn, in their colours`, s.tint.length === 1 && s.tint[0] === 0.2 && s.colours, JSON.stringify(s.tint));
    await page.close();
  }
  // the moment of selection: a tap starts the one-time shine on that row only, and it is gone afterwards; drawing a selected list does not start it
  {
    const page = await open({ dark: true });
    const r1 = await page.evaluate(() => { toggleSelectPkg('com.whatsapp'); const c = document.getElementById('card_com.whatsapp'); return { cls: c.classList.contains('sel-shine'), anim: getComputedStyle(c, '::after').animationName, others: document.querySelectorAll('.app-card.sel-shine').length }; });
    check('a tap that selects a row starts its shine once (the others do not shine)', r1.cls && r1.anim === 'selShine' && r1.others === 1, JSON.stringify(r1));
    await page.waitForFunction(() => !document.querySelector('.app-card.sel-shine'), null, { timeout: 8000 }).catch(() => {});          // (0.7 s; longer on a busy machine)
    const r2 = await page.evaluate(() => document.querySelectorAll('.app-card.sel-shine').length);
    check('the shine is over after it has run', r2 === 0, r2);
    const r3 = await page.evaluate(() => { toggleSelectPkg('com.whatsapp'); const c = document.getElementById('card_com.whatsapp'); return c.classList.contains('sel-shine') || getComputedStyle(c, '::after').animationName !== 'none'; });
    check('unselecting does not shine', !r3);
    const r4 = await page.evaluate(() => { selectAllVisible(true); return document.querySelectorAll('.app-card.sel-shine').length; });
    check('Select all draws the rows selected without a shine', r4 === 0, r4);
    await page.close();
    const rm = await b.newPage({ viewport: { width: 400, height: 1200 }, reducedMotion: 'reduce' });
    rm.on('pageerror', e => errors.push(e.message));
    await rm.addInitScript(appsMock.initScript, { dark: true });
    await rm.goto(PAGE); await rm.waitForTimeout(600); await rm.evaluate(() => switchView('apps')); await rm.waitForTimeout(300);
    const r5 = await rm.evaluate(() => { toggleSelectPkg('com.whatsapp'); return getComputedStyle(document.getElementById('card_com.whatsapp'), '::after').animationName; });
    check('with reduced motion the row does not shine', r5 === 'none', r5);
    await rm.close();
  }
  // the setting says what it does
  {
    const page = await open({ dark: true });
    const sub = await page.evaluate(() => document.querySelector('#rowTintCard .color-card-subtitle').textContent);
    check('Settings > Row tints says that only a selected row is outlined', /select/i.test(sub) && /outline/i.test(sub), sub);
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
