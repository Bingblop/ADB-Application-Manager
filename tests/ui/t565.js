// The Style grid of the one Theme screen (v7.22.0, ColorBlendr parity; PR 3's More styles folded in): all ten styles in one list, Android's six
// (a setting of their own) always open, Fidelity, Content, Monochrome and CMF marked Super User and greyed with the lock glyph and the reason
// without uid 0 (CMF also while the spec is not 2026); with uid 0 a root-only style is chosen like any other, the theme setting takes Tonal Spot
// for it (OverlayRules.STYLES knows no other) and the overlay carries its colours; the preview names the style; Colors in use reads the
// setting; a root-only style remembered on a phone that cannot write it counts for nothing (Apply writes Tonal Spot, the grid marks it); the
// radio semantics and the focus kept across a redraw.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => { window.__sdk = 35; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const styles = () => ev(() => Array.from(document.querySelectorAll('#ovlStyles .ovl-style')).map(s => ({ id: s.dataset.style, name: s.querySelector('b').innerText, tag: (s.querySelector('i') || {}).innerText || '', locked: s.classList.contains('is-locked'), disabled: s.disabled, checked: s.getAttribute('aria-checked'), why: (s.querySelector('.ovl-lockwhy') || {}).innerText || '' })));
  const preview = () => ev(() => (document.getElementById('ovlPreviewLine') || {}).innerText || '');

  await ev(() => switchView('overlays')); await sleep(400);
  let st = await styles();
  check('1. the shell: ten styles, the six of Android open and the four of Super User tagged so, greyed and named with the lock glyph', st.length === 10 && st.slice(0, 6).every(x => !x.locked && x.tag !== 'Super User') && st.slice(6).every(x => x.locked && x.disabled && x.tag === 'Super User' && x.name.startsWith('🔒 ')), JSON.stringify(st.map(x => x.name + '/' + x.tag)));
  check('   their names and lines', st.slice(6).map(x => x.name).join('|') === '🔒 Fidelity|🔒 Content|🔒 Monochrome|🔒 CMF' && (await ev(() => Array.from(document.querySelectorAll('#ovlStyles .ovl-style.is-locked .ovl-styletxt > span:not(.ovl-lockwhy)')).map(x => x.innerText).join('|'))) === 'Accents keep your exact color; backgrounds take a faint tint of it.|Like Fidelity, with a third accent chosen to sit beside your color.|No color at all: black, white and greys.|One color family: accents and backgrounds are your color at different strengths.', st.slice(6).map(x => x.name).join('|'));
  check('   the grid is a radio group, Tonal Spot checked', await ev(() => document.getElementById('ovlStyles').getAttribute('role') === 'radiogroup') && st.filter(x => x.checked === 'true').map(x => x.id).join() === 'TONAL_SPOT');
  check('   no preview line without uid 0 (the phone\'s own palette shows)', (await preview()) === '' && (await ev(() => document.querySelectorAll('#ovlPalette .ovl-chip').length)) === 55);

  // 2) with Root: a root-only style chosen, written as Tonal Spot in the setting and as itself in the overlay
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(150);
  st = await styles();
  check('2. with Root: Fidelity, Content and Monochrome open, CMF greyed for the spec', st.slice(6, 9).every(x => !x.locked && !x.disabled && !x.name.startsWith('🔒')) && st[9].locked && st[9].why === 'Needs Color spec 2026', JSON.stringify(st.slice(6)));
  await page.locator('#ovlStyles .ovl-style[data-style="CONTENT"]').click(); await sleep(120);
  st = await styles();
  check('   Content tapped: checked alone, the preview names it', st.filter(x => x.checked === 'true').map(x => x.id).join() === 'CONTENT' && /, Content, /.test(await preview()), await preview());
  check('   the focus stays on the row tapped across the redraw', await ev(() => document.activeElement && document.activeElement.dataset.style === 'CONTENT'));
  await reset(); await apply();
  let k = await calls();
  check('   Apply: the theme setting gets Tonal Spot (Android knows no Content), the overlay the Content colours (236 entries on Android 15), the record Content', k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && k.fab[0].entries.length === 236 && (await ev(() => { const r = kvGet('ovl_root_applied', null); return r.style === 'CONTENT' && r.opts.style === 'CONTENT'; })), JSON.stringify([k.apply[0], k.fab[0].entries.length]));
  check('   the overlay\'s shades are the Content ones, not Tonal Spot\'s', await ev(() => { const o = kvGet('ovl_root_applied', null).opts; const c = MonetPlan.cb.entries(o, 35), t = MonetPlan.cb.entries(Object.assign({}, o, { style: 'TONAL_SPOT' }), 35); return c.some((e, i) => e.argb !== t[i].argb); }));
  check('   Colors in use reads the setting: a custom colour is not set, so the wallpaper line, Tonal Spot', /Tonal Spot/.test(await ev(() => document.getElementById('ovlNow').innerText)) && !/Content/.test(await ev(() => document.getElementById('ovlNow').innerText)));
  check('   the hint stays the usual one plus the overlay sentence', /Android repaints in a moment, and apps may restart\. Also writes these colors into Android with Super User: the same palettes as ColorBlendr\./.test(await ev(() => document.getElementById('ovlApplyHint').innerText.replace(/\s+/g, ' ').trim())));
  // Android's six still go into the setting as themselves
  await page.locator('#ovlStyles .ovl-style[data-style="VIBRANT"]').click(); await sleep(100);
  await reset(); await apply(); k = await calls();
  check('   Vibrant: the setting gets Vibrant, the overlay the Vibrant colours too', k.apply[0].style === 'VIBRANT' && k.fab.length === 1 && (await ev(() => kvGet('ovl_root_applied', null).style === 'VIBRANT')), JSON.stringify(k.apply[0]));
  check('   ovlStyleName knows all ten, and a name Android adds later', await ev(() => ovlStyleName('MONOCHROMATIC') === 'Monochrome' && ovlStyleName('CMF') === 'CMF' && ovlStyleName('FRUIT_SALAD') === 'Fruit Salad' && ovlStyleName('SOME_NEW') === 'Some new'));

  // 3) a root-only style remembered on a phone that cannot write it any more
  await page.locator('#ovlStyles .ovl-style[data-style="MONOCHROMATIC"]').click(); await sleep(100);
  await ev(() => { window.__root = false; checkAllWorkingModes(false); }); await sleep(150);
  st = await styles();
  check('3. Monochrome chosen, then Root gone: the grid marks Tonal Spot, Monochrome is greyed with the uid 0 reason', st.filter(x => x.checked === 'true').map(x => x.id).join() === 'TONAL_SPOT' && st[8].locked && st[8].why === 'Needs Super User (uid 0) to write Material You colors' && (await ev(() => ovlStyle === 'MONOCHROMATIC' && ovlStyleEffective() === 'TONAL_SPOT')), JSON.stringify(st[8]));
  await reset(); await apply(); k = await calls();
  check('   Apply writes Tonal Spot and nothing else', k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0, JSON.stringify(k.apply));
  check('   the hint says this is what the phone uses now (the setting is Tonal Spot from the wallpaper)', (await ev(() => document.getElementById('ovlApplyHint').innerText.trim())) === 'This is already what the phone uses.');
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(150);
  check('   Root back: Monochrome is the one marked again', (await styles()).filter(x => x.checked === 'true').map(x => x.id).join() === 'MONOCHROMATIC');

  // 4) a restart keeps nothing of the grid (the style is a choice of the moment, as before); a backup does (t604)
  check('4. the grid\'s choice is not stored by itself', (await ev(() => kvGet('ovl_root_style', null))) === null);

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
