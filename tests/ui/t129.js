// Settings > Theme: Palette Style. Nine styles (Tonal Spot, Vibrant, Fidelity, Content, Neutral, Expressive, Fruit Salad, Rainbow, Monotone) turn one source color
// into a whole theme, in light and dark. The color engine is checked against known values (the HCT of pure red, green and blue; Tonal Spot of Material purple against
// the Material 3 baseline), the styles against what they promise (grey stays grey, Monotone has no color), and every style stays readable for a spread of source colors.
// The source color: automatic (wallpaper / Material purple) or picked; choosing a style or a source turns the generated palette on; the choice is saved and restored.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__saved = [];
    window.__dyn = null;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return window.__load || '{}'; }, savePreferences(j) { window.__saved.push(j); }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {},
      getMaterialYouColors() { return window.__dyn ? JSON.stringify(window.__dyn) : JSON.stringify({ supported: false }); }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const css = name => ev(n => getComputedStyle(document.documentElement).getPropertyValue(n).trim().toUpperCase(), name);
  const near = (a, b2, tol) => { const x = a.replace('#', ''), y = b2.replace('#', ''); return [0, 2, 4].every(i => Math.abs(parseInt(x.substr(i, 2), 16) - parseInt(y.substr(i, 2), 16)) <= tol); };

  await ev(() => { switchView('prefs'); applyPalettePreset('amoled'); setAppearance('dark'); }); await wait(200);

  // ---- the card ----
  const names = await ev(() => [...document.querySelectorAll('#psStyleGrid .preset-name')].map(e => e.innerText.trim()));
  check('the Theme menu has a Palette Style card with the nine styles in order', JSON.stringify(names) === JSON.stringify(['Tonal Spot', 'Vibrant', 'Fidelity', 'Content', 'Neutral', 'Expressive', 'Fruit Salad', 'Rainbow', 'Monotone']), JSON.stringify(names));
  check('each style says in a line what it does', await ev(() => [...document.querySelectorAll('#psStyleGrid .ps-desc')].every(e => e.innerText.trim().length > 20)));
  check('each tile shows four swatches', await ev(() => [...document.querySelectorAll('#psStyleGrid .preset-strip')].every(e => e.children.length === 4)));
  check('the card sits above the curated palettes', await ev(() => { const a = document.getElementById('paletteStyleCard'), c = document.querySelector('.palette-presets-grid:not(#psStyleGrid)'); return !!a && !!c && !!(a.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_FOLLOWING); }));
  check('with a curated palette in use no style is marked, and the note says so', await ev(() => !document.querySelector('#psStyleGrid .active') && /Not in use/.test(document.getElementById('psNote').innerText)));
  check('the source color is automatic: Material purple here (no wallpaper colors), no Automatic button', await ev(() => document.getElementById('hexSeed').innerText === '#6750A4' && /Automatic: Material purple/.test(document.getElementById('psSeedNote').innerText) && getComputedStyle(document.getElementById('psAutoBtn')).display === 'none'));

  // ---- the engine against known values ----
  const hct = await ev(() => ({ red: psHctFromHex('#FF0000'), green: psHctFromHex('#00FF00'), blue: psHctFromHex('#0000FF'), grey: psHctFromHex('#777777'), white: psHctFromHex('#FFFFFF'), black: psHctFromHex('#000000') }));
  const close = (v, e, tol) => Math.abs(v - e) <= tol;
  check('HCT of pure red is hue 27.4, chroma 113.4, tone 53.2', close(hct.red.h, 27.4, 0.3) && close(hct.red.c, 113.4, 0.6) && close(hct.red.t, 53.2, 0.2), JSON.stringify(hct.red));
  check('HCT of pure green is 142.1, 108.4, 87.7', close(hct.green.h, 142.1, 0.3) && close(hct.green.c, 108.4, 0.6) && close(hct.green.t, 87.7, 0.2), JSON.stringify(hct.green));
  check('HCT of pure blue is 282.8, 87.2, 32.3', close(hct.blue.h, 282.8, 0.3) && close(hct.blue.c, 87.2, 0.6) && close(hct.blue.t, 32.3, 0.2), JSON.stringify(hct.blue));
  check('white is tone 100 and black tone 0, and a grey has almost no chroma', close(hct.white.t, 100, 0.1) && close(hct.black.t, 0, 0.1) && hct.grey.c < 3, JSON.stringify([hct.white, hct.black, hct.grey]));
  const rt = await ev(() => { let worst = 0, n = 0, bad = 0; for (let h = 0; h < 360; h += 20) for (const c of [4, 16, 36, 48, 84, 120]) for (const t of [6, 10, 20, 30, 40, 50, 60, 70, 80, 90, 94, 98]) { const x = psHctFromHex(psHex(h, c, t)); n++; worst = Math.max(worst, Math.abs(x.t - t)); if (!/^#[0-9A-F]{6}$/.test(psHex(h, c, t))) bad++; } return { n, worst, bad }; });
  check('asking for a hue, chroma and tone gives a color of that tone, for ' + rt.n + ' combinations (too much chroma is reduced, never a wrong tone)', rt.worst < 0.5 && rt.bad === 0, JSON.stringify(rt));
  check('a pure red asked for as HCT comes back as pure red', near(await ev(() => psHex(27.4, 113.4, 53.2)), '#FF0000', 3));
  const base = await ev(() => ({ d: psScheme('#6750A4', 'TONAL_SPOT', 'dark'), l: psScheme('#6750A4', 'TONAL_SPOT', 'light') }));
  check('Tonal Spot of Material purple is the Material 3 baseline: dark background #141218, accent ~#D0BCFF, card ~#211F26, sheet #1D1B20', base.d.bg === '#141218' && near(base.d.accent, '#D0BCFF', 6) && near(base.d.card, '#211F26', 4) && near(base.d.sheet, '#1D1B20', 3), JSON.stringify(base.d));
  const lh = await ev(h => { const x = psHctFromHex(h); return x; }, base.l.accent);
  check('... and light: background ~#FEF7FF, text #1D1B20, errors ~#B3261E, the accent a tone-40 purple of the same hue', near(base.l.bg, '#FEF7FF', 4) && base.l.text === '#1D1B20' && near(base.l.bloat, '#B3261E', 12) && Math.abs(lh.t - 40) < 1 && Math.abs(lh.h - 299) < 8, JSON.stringify(base.l) + JSON.stringify(lh));

  // ---- choosing a style ----
  await page.click('#psStyleGrid [data-style="VIBRANT"]'); await wait(150);
  const v = await ev(() => ({ preset: themeState.preset, style: themeState.style, active: [...document.querySelectorAll('#psStyleGrid .active')].map(e => e.dataset.style), pressed: document.querySelector('#psStyleGrid [data-style="VIBRANT"]').getAttribute('aria-pressed'), cur: psScheme('#6750A4', 'VIBRANT', 'dark'), note: document.getElementById('psNote').innerText }));
  check('a style turns the generated palette on, marks only itself, and says so', v.preset === 'generated' && v.style === 'VIBRANT' && JSON.stringify(v.active) === '["VIBRANT"]' && v.pressed === 'true' && /Vibrant from #6750A4/.test(v.note) && /Palette style: Vibrant/.test(await toast()), JSON.stringify(v));
  check('and the page uses its colors (accent, background, card, text)', (await css('--accent')) === v.cur.accent && (await css('--bg-base')) === v.cur.bg && (await css('--bg-card')) === v.cur.card && (await css('--text-main')) === v.cur.text);
  check('no curated palette stays marked', await ev(() => !document.querySelector('.palette-presets-grid:not(#psStyleGrid) .active')));

  // ---- the nine differ, and keep their promise ----
  const all = await ev(() => { const o = {}; PS_IDS.forEach(id => { o[id] = { dark: psScheme('#4285F4', id, 'dark'), light: psScheme('#4285F4', id, 'light') }; }); return o; });
  const keyOf = s => [s.accent, s.bg, s.card, s.secondary, s.frozen].join();
  check('the nine styles give nine different themes (dark)', new Set(Object.values(all).map(x => keyOf(x.dark))).size === 9);
  check('... and nine different themes (light)', new Set(Object.values(all).map(x => keyOf(x.light))).size === 9);
  const grey = h => { const x = h.replace('#', ''); return x.substr(0, 2) === x.substr(2, 2) && x.substr(2, 2) === x.substr(4, 2); };
  check('Monotone has no color: accent, backgrounds, text and accents are all grey (the green and red status colors stay)', ['accent', 'bg', 'surface', 'card', 'sheet', 'text', 'muted', 'secondary', 'frozen'].every(k => grey(all.MONOTONE.dark[k]) && grey(all.MONOTONE.light[k])), JSON.stringify(all.MONOTONE.dark));
  check('Rainbow has a plain grey background but a colored accent', grey(all.RAINBOW.dark.bg) && grey(all.RAINBOW.dark.card) && !grey(all.RAINBOW.dark.accent));
  check('Tonal Spot, Vibrant and Fidelity tint the background; Vibrant more than Tonal Spot', !grey(all.TONAL_SPOT.dark.bg) && !grey(all.VIBRANT.dark.bg) && !grey(all.FIDELITY.dark.bg) && (await ev(() => psHctFromHex(psScheme('#4285F4', 'VIBRANT', 'dark').surface).c > psHctFromHex(psScheme('#4285F4', 'TONAL_SPOT', 'dark').surface).c)));
  check('Neutral is the quietest colored style: its accent has less chroma than Tonal Spot\'s', await ev(() => psHctFromHex(psScheme('#4285F4', 'NEUTRAL', 'dark').accent).c < psHctFromHex(psScheme('#4285F4', 'TONAL_SPOT', 'dark').accent).c));
  check('Fruit Salad turns the main color back 50 degrees; Expressive sends it far away', await ev(() => { const s = psHctFromHex('#4285F4').h, f = psHctFromHex(psScheme('#4285F4', 'FRUIT_SALAD', 'dark').accent).h, x = psHctFromHex(psScheme('#4285F4', 'EXPRESSIVE', 'dark').accent).h, d = (a, b2) => { const z = Math.abs(a - b2) % 360; return z > 180 ? 360 - z : z; }; return d(f, s - 50) < 8 && d(x, s + 240) < 8; }));
  check('Fidelity and Content keep the source color\'s chroma; Tonal Spot does not', await ev(() => { const c = psHctFromHex('#4285F4').c; const f = psHctFromHex(psScheme('#4285F4', 'FIDELITY', 'light').accent).c; const t = psHctFromHex(psScheme('#4285F4', 'TONAL_SPOT', 'light').accent).c; return f > t + 3 && c > 50; }));
  check('Running stays green and Bloat stays red in every style', await ev(() => PS_IDS.every(id => ['dark', 'light'].every(m => { const s = psScheme('#E91E63', id, m), g = psHctFromHex(s.running).h, r = psHctFromHex(s.bloat).h; return g > 120 && g < 160 && (r < 40 || r > 340); }))));

  // ---- readable for any source color, in light and dark ----
  const seeds = ['#6750A4', '#FF0000', '#00FF00', '#0000FF', '#FFFF00', '#00FFFF', '#FF00FF', '#FFFFFF', '#000000', '#808080', '#4285F4', '#E91E63', '#FF9800', '#795548', '#009688', '#CDDC39'];
  const read = await ev(seedList => {
    const bad = []; let n = 0;
    for (const sd of seedList) for (const id of PS_IDS) for (const m of ['dark', 'light']) {
      const s = psScheme(sd, id, m); n++;
      const ok = /^#[0-9A-F]{6}$/.test(s.accent + '') && Object.values(s).every(x => /^#[0-9A-F]{6}$/.test(x));
      const cr = (a, b2) => contrastRatio(a, b2);
      const fails = [];
      if (!ok) fails.push('not hex');
      if (cr(s.text, s.bg) < 7) fails.push('text/bg ' + cr(s.text, s.bg).toFixed(1));
      if (cr(s.text, s.card) < 7) fails.push('text/card ' + cr(s.text, s.card).toFixed(1));
      if (cr(s.muted, s.bg) < 4.5) fails.push('muted/bg ' + cr(s.muted, s.bg).toFixed(1));
      if (cr(s.muted, s.card) < 4.5) fails.push('muted/card ' + cr(s.muted, s.card).toFixed(1));
      if (cr(s.accent, s.bg) < 4.5) fails.push('accent/bg ' + cr(s.accent, s.bg).toFixed(1));
      if (cr(s.accent, s.card) < 3.5) fails.push('accent/card ' + cr(s.accent, s.card).toFixed(1));
      if (Math.max(cr(s.accent, '#000000'), cr(s.accent, '#FFFFFF')) < 4.5) fails.push('on-accent');
      if (m === 'dark' && luminance(s.bg) > 0.03) fails.push('dark bg too light');
      if (m === 'light' && luminance(s.bg) < 0.8) fails.push('light bg too dark');
      if (fails.length) bad.push(sd + ' ' + id + ' ' + m + ': ' + fails.join(', '));
    }
    return { n, bad };
  }, seeds);
  check('every style stays readable (text 7:1, muted and accent 4.5:1, card accents 3.5:1) for ' + read.n + ' combinations of source color, style and mode', read.bad.length === 0, read.bad.slice(0, 6).join(' | '));

  // ---- light and dark follow Appearance ----
  await ev(() => setAppearance('light')); await wait(150);
  const lt = await ev(() => ({ bg: getComputedStyle(document.documentElement).getPropertyValue('--bg-base').trim().toUpperCase(), want: psScheme('#6750A4', 'VIBRANT', 'light').bg, mode: document.documentElement.getAttribute('data-appearance') }));
  check('in light mode the same style is its light version', lt.mode === 'light' && lt.bg === lt.want, JSON.stringify(lt));
  check('the tiles show the light version too', await ev(() => { const sw = document.querySelector('#psStyleGrid [data-style="TONAL_SPOT"] .preset-swatch').style.backgroundColor; const w = psScheme('#6750A4', 'TONAL_SPOT', 'light').accent.replace('#', ''); const m2 = sw.match(/\d+/g).map(Number); return m2.map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase() === w; }));
  await ev(() => setAppearance('dark')); await wait(100);

  // ---- the source color ----
  await page.click('#paletteStyleCard .picker-bubble-btn'); await wait(150);
  check('tapping the source color opens the color picker, titled Source Color', await ev(() => document.getElementById('colorPickModal').classList.contains('show') && document.getElementById('cpTitle').innerText === 'Source Color' && /builds light and dark/.test(document.getElementById('cpSub').innerText)));
  await page.fill('#cpHex1', 'E91E63'); await page.dispatchEvent('#cpHex1', 'input'); await wait(80);
  await page.click('#cpApply'); await wait(200);
  const sd = await ev(() => ({ seed: themeState.seed, preset: themeState.preset, style: themeState.style, hex: document.getElementById('hexSeed').innerText, auto: getComputedStyle(document.getElementById('psAutoBtn')).display !== 'none', note: document.getElementById('psSeedNote').innerText, accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim().toUpperCase(), want: psScheme('#E91E63', 'VIBRANT', 'dark').accent }));
  check('a picked source color is used by the style in use (and shown, with an Automatic button)', sd.seed === '#E91E63' && sd.preset === 'generated' && sd.style === 'VIBRANT' && sd.hex === '#E91E63' && sd.auto && /Your color/.test(sd.note) && sd.accent === sd.want, JSON.stringify(sd));
  check('the tiles are drawn from it', await ev(() => { const sw = document.querySelector('#psStyleGrid [data-style="RAINBOW"] .preset-swatch').style.backgroundColor.match(/\d+/g).map(Number); return sw.map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase() === psScheme('#E91E63', 'RAINBOW', 'dark').accent.replace('#', ''); }));
  await page.click('#psAutoBtn'); await wait(150);
  check('Automatic goes back to the wallpaper / Material purple', await ev(() => themeState.seed === null && document.getElementById('hexSeed').innerText === '#6750A4' && getComputedStyle(document.getElementById('psAutoBtn')).display === 'none'));
  await ev(() => applyPalettePreset('slate')); await wait(100);
  await ev(() => setPaletteSeed('#009688')); await wait(100);
  check('picking a source color while a curated palette is in use switches to the generated one (Tonal Spot, the style last used here)', await ev(() => themeState.preset === 'generated' && themeState.seed === '#009688' && !document.querySelector('.palette-presets-grid:not(#psStyleGrid) .active')));
  await ev(() => setPaletteSeed(null));

  // ---- the wallpaper's color (Android 12+) ----
  await ev(() => { window.__dyn = { supported: true, dark: Object.assign(resolvePresetScheme('material3', 'dark'), { accent: '#A8C7FA' }), light: resolvePresetScheme('material3', 'light') }; getMaterialYouSchemes(true); applyTheme(false); });
  const wp = await ev(() => ({ seed: psSeed(), h: psHctFromHex(psSeed()).h, want: psHctFromHex('#A8C7FA').h, note: document.getElementById('psSeedNote').innerText }));
  check('with wallpaper colors the automatic source color has the wallpaper accent\'s hue', Math.abs(wp.h - wp.want) < 4 && /wallpaper/.test(wp.note), JSON.stringify(wp));
  await ev(() => { window.__dyn = null; getMaterialYouSchemes(true); });

  // ---- saved, restored ----
  await ev(() => { window.__saved.length = 0; setPaletteSeed('#009688'); setPaletteStyle('FRUIT_SALAD'); });
  const sv = await ev(() => JSON.parse(window.__saved[window.__saved.length - 1]));
  check('the style and the source color are part of the saved theme', sv.preset === 'generated' && sv.style === 'FRUIT_SALAD' && sv.seed === '#009688' && sv.version === 2, JSON.stringify(sv));
  const rs = await ev(() => { window.__load = JSON.stringify({ version: 2, preset: 'generated', style: 'CONTENT', seed: '#ff5722', appearance: 'dark', overrides: {} }); themeState.preset = 'amoled'; themeState.style = 'TONAL_SPOT'; themeState.seed = null; restoreSavedTheme(); return { p: themeState.preset, s: themeState.style, d: themeState.seed }; });
  check('restoring brings back the generated palette, its style and source color (capitals)', rs.p === 'generated' && rs.s === 'CONTENT' && rs.d === '#FF5722', JSON.stringify(rs));
  const bad = await ev(() => { window.__load = JSON.stringify({ version: 2, preset: 'generated', style: 'NOPE', seed: 'javascript:1', appearance: 'dark', overrides: {} }); themeState.style = 'TONAL_SPOT'; themeState.seed = null; restoreSavedTheme(); return { p: themeState.preset, s: themeState.style, d: themeState.seed }; });
  check('a damaged saved style or source color falls back (Tonal Spot, automatic)', bad.p === 'generated' && bad.s === 'TONAL_SPOT' && bad.d === null, JSON.stringify(bad));
  const old = await ev(() => { window.__load = JSON.stringify({ version: 2, preset: 'emerald', appearance: 'dark', overrides: {} }); themeState.style = 'VIBRANT'; themeState.seed = '#123456'; restoreSavedTheme(); return { p: themeState.preset, s: themeState.style, d: themeState.seed }; });
  check('a theme saved before this existed loads as it was (no style, automatic source)', old.p === 'emerald' && old.s === 'VIBRANT' && old.d === null, JSON.stringify(old));
  await ev(() => { window.__load = ''; themeState.style = 'TONAL_SPOT'; applyPalettePreset('amoled'); });

  // ---- tweaks and the curated palettes still work ----
  await ev(() => { setPaletteStyle('NEUTRAL'); updateCustomColor('accent', '#ff0000'); }); await wait(100);
  check('a color tweak still applies on top of a style', (await css('--accent')) === '#FF0000');
  await ev(() => setPaletteStyle('MONOTONE')); await wait(100);
  check('choosing another style clears the tweaks (like choosing another palette)', (await css('--accent')) === (await ev(() => psScheme(psSeed(), 'MONOTONE', 'dark').accent)));
  await ev(() => applyPalettePreset('crimson')); await wait(100);
  check('a curated palette takes over again, and no style is marked', (await css('--accent')) === '#FF1744' && await ev(() => !document.querySelector('#psStyleGrid .active')));
  await ev(() => { applyPalettePreset('amoled'); window.scrollTo(0, 0); document.getElementById('paletteStyleCard').scrollIntoView(); });
  await page.screenshot({ path: 'palette_style.png' });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
