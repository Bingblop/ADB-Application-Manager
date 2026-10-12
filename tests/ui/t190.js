// App Stores: the text on the Install button, on the letter tile of an app without an icon and on the selected store tab is black or white, whichever reads better over the
// WHOLE gradient behind it (accent to secondary), in every palette and in light and dark: including the real Install buttons on the rows of the ShizuStore tab. It used to be white whatever the colors (the button of a row), or chosen from the accent alone.
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
      getMaterialYouColors() { return JSON.stringify({ supported: false }); }, storeLoadCatalog() {}, storeLoadApp() {}, storeInstall() {}
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);

  // the ShizuStore tab's own rows: drawn by the page from a catalog (an app with an icon hash and one without), each with its Install button
  await ev(() => {
    onStoreCatalog(JSON.stringify({ status: 'ok', base: 'https://store.example', total: 2, items: [
      { slug: 'alpha', name: 'Alpha Tool', description: 'First test app', packageName: 'com.alpha', stars: 5, categorySlug: 'tools', iconHash: '' },
      { slug: 'bravo', name: 'Bravo Utility', description: 'Second test app', packageName: 'com.bravo', stars: 9, categorySlug: 'tools', iconHash: '', requiresRoot: true }] }));
  });
  const shizuBtns = await ev(() => document.querySelectorAll('#storeList > div .store-inst-btn').length);
  check('the ShizuStore tab draws a real Install button on each of its 2 rows (class store-inst-btn, as the other stores)', shizuBtns === 2, String(shizuBtns));

  // the three surfaces, as the store tab draws them, in the page itself
  await ev(() => {
    const h = document.createElement('div'); h.id = 't190fx';
    h.innerHTML = '<div class="store-row"><div class="sr-fallback">A</div><button class="store-inst-btn">Install</button></div>'
      + '<div class="sheet-avatar" id="t190av">A</div><button class="store-subtab active">GitHub</button><button class="mode-action-btn primary" id="t190prim">Install</button>';
    document.body.appendChild(h);
  });

  // the rule itself, against hand-made pairs
  const pairs = await ev(() => [['#FFFF00', '#FFEB3B'], ['#00008B', '#4B0082'], ['#00E5FF', '#7C4DFF'], ['#FFD600', '#0000AA'], ['#FFFFFF', '#000000'], ['#FF5722', '#FFC107'], ['#A0A0A0', '#000040'], ['#FF0000', '#008080'], ['#FF0000', '#00A80A']].map(([a, c]) => {
    // the oracle does not use the page's rule: 4001 unrounded points along the gradient, its darkest and its brightest
    const lin = u => u <= 0.03928 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4);
    const x = [1, 3, 5].map(i => parseInt(a.substr(i, 2), 16)), y = [1, 3, 5].map(i => parseInt(c.substr(i, 2), 16));
    let lo = 9, hi = -9;
    for (let i = 0; i <= 4000; i++) { const t = i / 4000, q = x.map((v, k) => lin((v + (y[k] - v) * t) / 255)), l = 0.2126 * q[0] + 0.7152 * q[1] + 0.0722 * q[2]; lo = Math.min(lo, l); hi = Math.max(hi, l); }
    return { a, c, got: onGradientColor(a, c), black: +((lo + 0.05) / 0.05).toFixed(2), white: +(1.05 / (hi + 0.05)).toFixed(2) };
  }));
  for (const p of pairs) check('onGradientColor(' + p.a + ', ' + p.c + ') = ' + p.got + ' (worst contrast black ' + p.black + ', white ' + p.white + ')', p.got === (p.black >= p.white ? '#000000' : '#FFFFFF'));
  check('two light colors get black, two dark colors get white', pairs[0].got === '#000000' && pairs[1].got === '#FFFFFF');
  check('complementary ends (red to teal): the middle of the gradient is darker than both ends, and the rule reads it: white, not the black the two ends alone would pick', pairs[7].got === '#FFFFFF' && pairs[7].white > pairs[7].black, JSON.stringify(pairs[7]));
  check('red to green (#00A80A): the darkest point is between nine evenly spaced samples (t about 0.44), where black falls just below white: white', pairs[8].got === '#FFFFFF' && pairs[8].white > pairs[8].black, JSON.stringify(pairs[8]));
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
        return { m, name, a, s, og, want, btn: col('#t190fx .store-inst-btn'), shizu: [...document.querySelectorAll('#storeList .store-inst-btn')].map(b => { const c = getComputedStyle(b).color.match(/\d+/g).map(Number); return '#' + c.slice(0, 3).map(n => n.toString(16).padStart(2, '0')).join('').toUpperCase(); }), tile: col('#t190fx .sr-fallback'), tab: col('#t190fx .store-subtab.active'), prim: col('#t190prim'), av: col('#t190av'), detail: col('#storeDetailAvatar') };
      }, [mode, pr]);
      seen.add(r.og);
      if (!(r.og === r.want && r.shizu.length === 2 && r.shizu.every(c => c === r.want) && r.btn === r.want && r.tile === r.want && r.tab === r.want && r.prim === r.want && r.av === r.want && r.detail === r.want)) bad.push(JSON.stringify(r));
    }
  }
  check('in ' + presets.length * 2 + ' palette and appearance combinations the Install button (the fixture one and the two real buttons on the ShizuStore rows), the letter tile, the selected store tab, the primary button and the avatar tiles of the sheets (including the one in the details window of the store) all use the rule', bad.length === 0, bad.slice(0, 3).join(' | '));
  check('both black and white are used somewhere (the rule is not a constant)', seen.size === 2, [...seen].join());
  // a light accent over a dark secondary: whatever the rule picks must beat the other color at the worse end
  const mixed = await ev(() => { document.documentElement.style.setProperty('--accent', '#FFD600'); document.documentElement.style.setProperty('--secondary', '#0000AA'); const og = onGradientColor('#FFD600', '#0000AA'); const other = og === '#000000' ? '#FFFFFF' : '#000000'; const w = x => Math.min(contrastRatio('#FFD600', x), contrastRatio('#0000AA', x)); return { og, better: w(og) >= w(other) }; });
  check('a bright accent over a dark secondary gets the color that is better at the worse end', mixed.better, JSON.stringify(mixed));
  check('no script errors', errors.length === 0, errors.join('|'));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  console.log('all passed');
})().catch(e => { console.error(e); process.exit(1); });
