// Settings > My Themes: save the look you made (palette or palette style, source color, color tweaks, Pure black) under a name, switch between your themes with a
// tap, rename, replace with the look in use now, delete. At most 30, names unique without regard to case, a damaged saved list is cleaned, the choice survives a restart,
// and Appearance (light / dark / system) is not part of a theme.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__saved = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences(j) { window.__saved.push(j); }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {},
      getMaterialYouColors() { return JSON.stringify({ supported: false }); }
    };
  });
  // native prompt() and confirm(): each answer is queued, what was asked is kept
  const dq = [], asked = [];
  page.on('dialog', async d => { const n = dq.shift() || {}; asked.push({ type: d.type(), msg: d.message(), def: d.defaultValue() }); if (n.accept === false) await d.dismiss(); else await d.accept(n.value); });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const css = n => ev(x => getComputedStyle(document.documentElement).getPropertyValue(x).trim().toUpperCase(), n);
  const saved = () => ev(() => kvGet('saved_themes', []));
  const names = () => ev(() => [...document.querySelectorAll('#mtGrid .mt-card .preset-name')].map(e => e.innerText));
  const rgbHex = c => '#' + c.match(/\d+/g).map(x => Number(x).toString(16).padStart(2, '0')).join('').toUpperCase();

  await ev(() => { localStorage.clear(); switchView('prefs'); applyPalettePreset('amoled'); setAppearance('dark'); renderMyThemes('dark'); }); await wait(200);

  // ---- the card, empty ----
  check('Settings has a My Themes card right after Palette Style', await ev(() => { const a = document.getElementById('paletteStyleCard'), c = document.getElementById('myThemesCard'); return !!c && !!(a.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_FOLLOWING) && c.nextElementSibling.querySelector('.color-card-title').innerText === 'Curated Theme Palettes'; }));
  check('empty: no themes, and the note says how to make one', (await names()).length === 0 && /None yet/.test(await ev(() => document.getElementById('mtNote').innerText)));
  check('the card says what a theme holds and that Appearance is not part of it', await ev(() => { const t = document.getElementById('myThemesCard').innerText; return /palette or palette style/.test(t) && /source color/.test(t) && /color tweaks/.test(t) && /Pure black/.test(t) && /Appearance/.test(t); }));

  // ---- save a palette style with a source color ----
  await ev(() => { setPaletteStyle('VIBRANT'); setPaletteSeed('#E91E63'); });
  dq.push({ value: 'Pink pop' }); await page.click('#mtSaveBtn'); await wait(150);
  check('Save asks for a name, suggesting the style and its source color', asked.length === 1 && asked[0].type === 'prompt' && asked[0].def === 'Vibrant #E91E63' && /Name for this theme/.test(asked[0].msg), JSON.stringify(asked));
  let sv = await saved();
  check('it is saved with the style, the source color and the palette', sv.length === 1 && sv[0].name === 'Pink pop' && sv[0].preset === 'generated' && sv[0].style === 'VIBRANT' && sv[0].seed === '#E91E63' && sv[0].pureBlack === false, JSON.stringify(sv));
  check('it is listed with its name and what it is, marked as the one in use, and a toast says so', JSON.stringify(await names()) === '["Pink pop"]' && await ev(() => document.querySelector('#mtGrid .mt-card .mt-sub').innerText === 'Vibrant · #E91E63' && document.querySelector('#mtGrid .mt-card').classList.contains('active')) && /Saved the theme Pink pop/.test(await toast()));
  check('the tile shows the theme\'s colors (accent, background, card, second accent)', await ev(() => { const sw = [...document.querySelectorAll('#mtGrid .mt-card .preset-swatch')].map(e => e.style.backgroundColor); const c = psScheme('#E91E63', 'VIBRANT', 'dark'); const h = x => '#' + x.match(/\d+/g).map(v => Number(v).toString(16).padStart(2, '0')).join('').toUpperCase(); return JSON.stringify(sw.map(h)) === JSON.stringify([c.accent, c.bg, c.card, c.secondary]); }));

  // ---- a curated palette, then a tweaked one with Pure black ----
  await ev(() => applyPalettePreset('crimson')); await wait(100);
  check('another look: the saved theme is no longer marked', await ev(() => !document.querySelector('#mtGrid .mt-card.active')));
  dq.push({ value: 'Blood' }); await page.click('#mtSaveBtn'); await wait(100);
  check('the suggested name of a curated palette is its name', asked[1].def === 'Blood Moon', asked[1].def);
  await ev(() => { applyPalettePreset('slate'); updateCustomColor('accent', '#12AB34'); setPureBlack(true); }); await wait(100);
  dq.push({ value: 'Slate green' }); await page.click('#mtSaveBtn'); await wait(100);
  check('a tweaked palette is suggested as such', asked[2].def === 'Midnight Slate tweaked', asked[2].def);
  sv = await saved();
  check('newest first; the tweak and Pure black are kept, colors in lower case', JSON.stringify(await names()) === '["Slate green","Blood","Pink pop"]' && sv[0].pureBlack === true && sv[0].overrides.dark.accent === '#12ab34' && sv[0].overrides.light.accent === undefined, JSON.stringify(sv[0]));
  check('the line under the name says palette, tweaked, pure black', await ev(() => document.querySelector('#mtGrid .mt-card .mt-sub').innerText === 'Midnight Slate · tweaked · pure black'));

  // ---- switching ----
  await page.click('#mtGrid [data-mt="2"] .mt-main'); await wait(150);
  const p1 = await ev(() => ({ preset: themeState.preset, style: themeState.style, seed: themeState.seed, pb: themeState.pureBlack, ov: JSON.stringify(themeState.overrides), app: themeState.appearance, active: [...document.querySelectorAll('#mtGrid .mt-card.active .preset-name')].map(e => e.innerText), styleOn: document.querySelector('#psStyleGrid .active') && document.querySelector('#psStyleGrid .active').dataset.style, want: psScheme('#E91E63', 'VIBRANT', 'dark') }));
  check('tapping a theme applies it: palette style, source color, no tweaks, no pure black', p1.preset === 'generated' && p1.style === 'VIBRANT' && p1.seed === '#E91E63' && p1.pb === false && p1.ov === '{"dark":{},"light":{}}', JSON.stringify(p1));
  check('the page wears its colors, only that theme is marked, the Palette Style card follows, and a toast names it', (await css('--accent')) === p1.want.accent && (await css('--bg-base')) === p1.want.bg && JSON.stringify(p1.active) === '["Pink pop"]' && p1.styleOn === 'VIBRANT' && /Theme: Pink pop/.test(await toast()));
  check('Appearance is untouched', p1.app === 'dark');
  await page.click('#mtGrid [data-mt="0"] .mt-main'); await wait(150);
  const p2 = await ev(() => ({ preset: themeState.preset, pb: themeState.pureBlack, ov: themeState.overrides.dark.accent }));
  check('another theme brings back its tweak and Pure black', p2.preset === 'slate' && p2.pb === true && p2.ov === '#12ab34' && (await css('--accent')) === '#12AB34' && (await css('--bg-base')) === '#000000');
  await ev(() => { document.querySelector('#mtGrid [data-mt="1"] .mt-main').focus(); }); await page.keyboard.press('Enter'); await wait(120);
  check('Enter on a focused theme applies it too', await ev(() => themeState.preset === 'crimson' && themeState.pureBlack === false) && (await css('--accent')) === '#FF1744');
  await ev(() => setAppearance('light')); await wait(150);
  check('in light mode the tiles show the light versions', await ev(() => { const sw = document.querySelector('#mtGrid [data-mt="2"] .preset-swatch').style.backgroundColor.match(/\d+/g).map(v => Number(v).toString(16).padStart(2, '0')).join('').toUpperCase(); return '#' + sw === psScheme('#E91E63', 'VIBRANT', 'light').accent; }));
  await ev(() => setAppearance('dark'));

  // ---- the same name, rename, update, delete ----
  const asked0 = asked.length;
  dq.push({ value: 'PINK POP' }, { accept: false }); await page.click('#mtSaveBtn'); await wait(120);
  check('saving under a name that exists (any case) asks before replacing, and No leaves everything', asked[asked0 + 1].type === 'confirm' && /Pink pop/.test(asked[asked0 + 1].msg) && (await saved()).length === 3 && (await saved()).find(t => t.name === 'Pink pop').preset === 'generated');
  dq.push({ value: 'pink pop' }, { accept: true }); await page.click('#mtSaveBtn'); await wait(120);
  sv = await saved();
  check('Yes replaces it with the look in use now (crimson), keeping three themes, the replaced one first', sv.length === 3 && sv[0].name === 'pink pop' && sv[0].preset === 'crimson', JSON.stringify(sv.map(t => [t.name, t.preset])));
  dq.push({ value: 'x'.repeat(60) }); await page.click('#mtSaveBtn'); await wait(100);
  check('a name is cut at 40 characters', (await saved())[0].name.length === 40);
  dq.push({ value: '   ' }); const n0 = (await saved()).length; await page.click('#mtSaveBtn'); await wait(100);
  check('an empty name saves nothing', (await saved()).length === n0);
  dq.push({ accept: false }); await page.click('#mtSaveBtn'); await wait(100);
  check('Cancel saves nothing', (await saved()).length === n0);
  await ev(() => { const l = kvGet('saved_themes', []); l.shift(); kvSet('saved_themes', l); renderMyThemes('dark'); });   // back to Slate green, Blood, pink pop
  await ev(() => applyPalettePreset('amoled'));

  const idx = nm => ev(n => mtLoad().findIndex(t => t.name === n), nm);
  let i = await idx('Blood');
  dq.push({ value: '  Red night ' }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Rename")'); await wait(120);
  check('Rename asks with the old name, trims, and keeps everything else', asked[asked.length - 1].def === 'Blood' && (await names()).includes('Red night') && !(await names()).includes('Blood') && (await saved()).find(t => t.name === 'Red night').preset === 'crimson' && /Renamed to Red night/.test(await toast()));
  i = await idx('Red night');
  dq.push({ value: 'SLATE GREEN' }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Rename")'); await wait(120);
  check('a name another theme has (any case) is refused', /already called SLATE GREEN/.test(await toast()) && (await names()).includes('Red night'));
  dq.push({ value: 'Red night' }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Rename")'); await wait(100);
  check('the same name again changes nothing', (await names()).includes('Red night'));
  await ev(() => { applyPalettePreset('emerald'); });
  i = await idx('Red night');
  dq.push({ accept: false }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Update")'); await wait(100);
  check('Update asks first; No leaves the theme as it was', (await saved()).find(t => t.name === 'Red night').preset === 'crimson');
  dq.push({ accept: true }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Update")'); await wait(100);
  check('Yes puts the look in use now (Nordic Emerald) into it, same name and place', (await saved()).find(t => t.name === 'Red night').preset === 'emerald' && (await idx('Red night')) === i && /Updated the theme Red night/.test(await toast()));
  const count = (await saved()).length;
  dq.push({ accept: false }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Delete")'); await wait(100);
  check('Delete asks first; No keeps it', (await saved()).length === count && asked[asked.length - 1].type === 'confirm' && /Red night/.test(asked[asked.length - 1].msg));
  dq.push({ accept: true }); await page.click('#mtGrid [data-mt="' + i + '"] button:has-text("Delete")'); await wait(100);
  check('Yes removes it, and a toast says so', (await saved()).length === count - 1 && !(await names()).includes('Red night') && /Deleted the theme Red night/.test(await toast()));

  // ---- names are shown as text, never as markup ----
  dq.push({ value: '<b>bold</b> & "q"' }); await page.click('#mtSaveBtn'); await wait(100);
  check('a name with markup is shown as written, nothing is injected', await ev(() => !document.querySelector('#mtGrid b') && [...document.querySelectorAll('#mtGrid .preset-name')].some(e => e.innerText === '<b>bold</b> & "q"')));
  await ev(() => { const l = kvGet('saved_themes', []); l.shift(); kvSet('saved_themes', l); renderMyThemes('dark'); });

  // ---- at most 30 ----
  await ev(() => { const l = []; for (let k = 0; k < 30; k++) l.push({ name: 'T' + k, preset: 'amoled', style: 'TONAL_SPOT', seed: null, pureBlack: false, overrides: { dark: {}, light: {} } }); kvSet('saved_themes', l); renderMyThemes('dark'); });
  dq.push({ value: 'one too many' }); await page.click('#mtSaveBtn'); await wait(100);
  check('the 31st is refused in words', (await saved()).length === 30 && /Up to 30 themes/.test(await toast()) && !(await names()).includes('one too many'));
  dq.push({ value: 't5' }, { accept: true }); await page.click('#mtSaveBtn'); await wait(100);
  check('but a name that exists can still be replaced when 30 are saved', (await saved()).length === 30 && (await saved())[0].name === 't5');

  // ---- a damaged list ----
  await ev(() => {
    kvSet('saved_themes', [
      null, 5, 'x', { name: '', preset: 'amoled' }, { name: 'no preset' }, { name: 'bad preset', preset: 'nope' }, { name: 'custom', preset: 'custom' },
      { name: 'Good', preset: 'generated', style: 'NOPE', seed: 'javascript:1', pureBlack: 'yes', overrides: { dark: { accent: '#ABCDEF', bg: 'red', bogus: '#123456' }, light: 'x' } },
      { name: 'good', preset: 'slate' }, { name: 'Long ' + 'y'.repeat(80), preset: 'material3', overrides: null }
    ]); renderMyThemes('dark');
  });
  const dmg = await ev(() => mtLoad());
  check('a damaged list keeps only the sound entries, the first of a repeated name, cut and cleaned', dmg.length === 2 && dmg[0].name === 'Good' && dmg[0].style === 'TONAL_SPOT' && dmg[0].seed === null && dmg[0].pureBlack === true && dmg[0].overrides.dark.accent === '#abcdef' && dmg[0].overrides.dark.bg === undefined && dmg[0].overrides.dark.bogus === undefined && dmg[1].name.length === 40, JSON.stringify(dmg));
  await ev(() => { kvSet('saved_themes', 'not a list'); });
  check('something that is not a list is an empty list', (await ev(() => mtLoad())).length === 0);

  // ---- the old custom palette cannot be saved ----
  await ev(() => { kvSet('saved_themes', []); palettes.custom = { dark: { accent: '#123456', bg: '#000000', card: '#111111' } }; themeState.preset = 'custom'; applyTheme(false); });
  await page.click('#mtSaveBtn'); await wait(100);
  check('an old custom palette says to choose a palette first, and nothing is asked or saved', /old custom palette/.test(await toast()) && (await saved()).length === 0);
  await ev(() => applyPalettePreset('amoled'));

  // ---- it survives a restart ----
  dq.push({ value: 'Keep me' }); await ev(() => { setPaletteStyle('RAINBOW'); }); await page.click('#mtSaveBtn'); await wait(100);
  await page.reload(); await wait(600);
  await ev(() => switchView('prefs')); await wait(200);
  check('after the app is reopened the theme is still there', JSON.stringify(await names()) === '["Keep me"]');
  await ev(() => { kvSet('saved_themes', [{ name: 'Pink pop', preset: 'generated', style: 'VIBRANT', seed: '#E91E63', pureBlack: false, overrides: { dark: {}, light: {} } }, { name: 'Slate green', preset: 'slate', style: 'TONAL_SPOT', seed: null, pureBlack: true, overrides: { dark: { accent: '#12ab34' }, light: {} } }, { name: 'Keep me', preset: 'generated', style: 'RAINBOW', seed: null, pureBlack: false, overrides: { dark: {}, light: {} } }]); mtApply(2); document.getElementById('myThemesCard').scrollIntoView(); });
  await wait(200);
  await page.screenshot({ path: 'my_themes.png' });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
