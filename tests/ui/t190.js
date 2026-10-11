// App Stores: the text on the Install button, on the letter tile of an app without an icon and on the selected store tab is black or white, whichever reads better over the
// WHOLE gradient behind it (accent to secondary), in every palette and in light and dark. It used to be white whatever the colors (the button of a row), or chosen from the accent alone.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      getMaterialYouColors() { return JSON.stringify({ supported: false }); }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);

  // the three surfaces, as the store tab draws them, in the page itself
  await ev(() => {
    const h = document.createElement('div'); h.id = 't190fx';
    h.innerHTML = '<div class="store-row"><div class="sr-fallback">A</div><button class="store-inst-btn">Install</button></div>'
      + '<button class="store-subtab active">GitHub</button><button class="mode-action-btn primary" id="t190prim">Install</button>';
    document.body.appendChild(h);
  });

  // the rule itself, against hand-made pairs
  const pairs = await ev(() => [['#FFFF00', '#FFEB3B'], ['#00008B', '#4B0082'], ['#00E5FF', '#7C4DFF'], ['#FFD600', '#0000AA'], ['#FFFFFF', '#000000'], ['#FF5722', '#FFC107'], ['#A0A0A0', '#000040']].map(([a, c]) => {
    const worst = x => Math.min(contrastRatio(a, x), contrastRatio(c, x));
    return { a, c, got: onGradientColor(a, c), black: +worst('#000000').toFixed(2), white: +worst('#FFFFFF').toFixed(2) };
  }));
  for (const p of pairs) check('onGradientColor(' + p.a + ', ' + p.c + ') = ' + p.got + ' (worst contrast black ' + p.black + ', white ' + p.white + ')', p.got === (p.black >= p.white ? '#000000' : '#FFFFFF'));
  check('two light colors get black, two dark colors get white', pairs[0].got === '#000000' && pairs[1].got === '#FFFFFF');
  check('a mid-light accent over a very dark secondary gets WHITE (black reads on the accent alone, but not over the whole gradient)', pairs[6].got === '#FFFFFF', JSON.stringify(pairs[6]));

  // every palette, in dark and light: the page variable and the three surfaces agree with the rule
  const presets = await ev(() => [...document.querySelectorAll('.palette-preset-card[data-preset]')].map(e => e.dataset.preset));
  check('the Theme menu lists palettes to try (' + presets.length + ')', presets.length >= 5);
  let bad = [], seen = new Set();
  for (const mode of ['dark', 'light']) {
    for (const pr of presets) {
      await ev(([m, name]) => { setAppearance(m); applyPalettePreset(name); }, [mode, pr]); await wait(350);      // buttons fade their colors over 0.15 s: read the settled color
      const r = await ev(([m, name]) => {
        const cs = getComputedStyle(document.documentElement);
        const a = cs.getPropertyValue('--accent').trim(), s = cs.getPropertyValue('--secondary').trim(), og = cs.getPropertyValue('--on-gradient').trim().toUpperCase();
        const col = sel => { const c = getComputedStyle(document.querySelector(sel)).color.match(/\d+/g).map(Number); return '#' + c.slice(0, 3).map(n => n.toString(16).padStart(2, '0')).join('').toUpperCase(); };
        const want = onGradientColor(a, s).toUpperCase();
        return { m, name, a, s, og, want, btn: col('#t190fx .store-inst-btn'), tile: col('#t190fx .sr-fallback'), tab: col('#t190fx .store-subtab.active'), prim: col('#t190prim') };
      }, [mode, pr]);
      seen.add(r.og);
      if (!(r.og === r.want && r.btn === r.want && r.tile === r.want && r.tab === r.want && r.prim === r.want)) bad.push(JSON.stringify(r));
    }
  }
  check('in ' + presets.length * 2 + ' palette and appearance combinations the Install button, the letter tile, the selected store tab and the primary button all use the rule', bad.length === 0, bad.slice(0, 3).join(' | '));
  check('both black and white are used somewhere (the rule is not a constant)', seen.size === 2, [...seen].join());
  // a light accent over a dark secondary: whatever the rule picks must beat the other color at the worse end
  const mixed = await ev(() => { document.documentElement.style.setProperty('--accent', '#FFD600'); document.documentElement.style.setProperty('--secondary', '#0000AA'); const og = onGradientColor('#FFD600', '#0000AA'); const other = og === '#000000' ? '#FFFFFF' : '#000000'; const w = x => Math.min(contrastRatio('#FFD600', x), contrastRatio('#0000AA', x)); return { og, better: w(og) >= w(other) }; });
  check('a bright accent over a dark secondary gets the color that is better at the worse end', mixed.better, JSON.stringify(mixed));
  check('no script errors', errors.length === 0, errors.join('|'));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  console.log('all passed');
})().catch(e => { console.error(e); process.exit(1); });
