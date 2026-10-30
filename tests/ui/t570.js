// The three switches of the one Theme screen (v7.22.0, ColorBlendr parity; PR 2's Pure black and PR 8's Accurate shades folded in, Tint text
// color new): Accurate shades (on by default), Pure black (off), Tint text color (on), each a switch row with its name and line, remembered
// in the store (ovl_accurate_shades as '0' / '1', ovl_black and ovl_tint_text as booleans), toggling stores and redraws and writes nothing;
// the plan follows each (Accurate shades off makes the accent shades more vivid, Pure black puts the dark surfaces at black, Tint text color
// off puts plain white and black text on the surfaces); the records from before (ovl_accurate_shades '0', ovl_black true) are read as they
// are; the hint names nothing per switch; locked rows without uid 0; a restart keeps them.
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
  await page.addInitScript(() => { window.__sdk = 35; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const kv = key => ev(k => kvGet(k, null), key);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const rows = () => ev(() => Array.from(document.querySelectorAll('#ovlSwitches .rf-row')).map(r => { const sw = r.querySelector('.sdb-sw'); return { name: r.querySelector('.rf-name').childNodes[0].textContent.trim(), sub: r.querySelector('.rf-name span').innerText, locked: r.classList.contains('is-locked'), sw: sw ? { id: sw.id, on: sw.classList.contains('on'), checked: sw.getAttribute('aria-checked'), role: sw.getAttribute('role'), label: sw.getAttribute('aria-label') } : null }; }));
  const at = (p, n) => (p.entries.find(e => e.resource === 'android:color/system_' + n && !e.config) || {}).argb;
  const plan = () => ev(() => window.__ovlCalls.fab[0]);

  await ev(() => switchView('overlays')); await sleep(400);
  let r = await rows();
  check('1. three switch rows: Accurate shades on, Pure black off, Tint text color on; each a switch with its name', r.length === 3 && r.map(x => x.name).join('|') === 'Accurate shades|Pure black|Tint text color' && r.every(x => !x.locked && x.sw && x.sw.role === 'switch' && x.sw.label === x.name) && r.map(x => x.sw.checked).join() === 'true,false,true' && r.map(x => x.sw.on).join() === 'true,false,true', JSON.stringify(r));
  check('   their lines', r.map(x => x.sub).join('|') === 'On: every shade keeps its computed tone. Off: the accent colors come out more vivid, with their pale tones pulled down a little.|True black dark backgrounds, with the surfaces in a few dark greys. Light mode keeps its colors.|On: text on surfaces takes a tint of your color. Off: plain white text in dark mode and black in light mode.', r.map(x => x.sub).join('|'));
  check('   the card is Shades and text; nothing stored yet', (await ev(() => document.getElementById('ovlSwitchCard').querySelector('.color-card-title').innerText)) === 'Shades and text' && (await kv('ovl_accurate_shades')) === null && (await kv('ovl_black')) === null && (await kv('ovl_tint_text')) === null);
  await reset(); await apply();
  const base = await plan();

  // 2) each switch, stored and in the plan
  await page.locator('#ovlAccurateSw').click(); await sleep(80);
  check('2. Accurate shades off: stored as \'0\', the switch off, nothing written', (await kv('ovl_accurate_shades')) === '0' && (await rows())[0].sw.checked === 'false' && (await ev(() => window.__ovlCalls.fab.length === 1)));
  await reset(); await apply();
  let p = await plan();
  check('   the plan: the accent shades differ from the accurate ones (more vivid), the record says accurate false', at(p, 'accent1_200') !== at(base, 'accent1_200') && (await ev(() => kvGet('ovl_root_applied', null).opts.accurate === false)), at(p, 'accent1_200') + ' vs ' + at(base, 'accent1_200'));
  await page.locator('#ovlAccurateSw').click(); await sleep(80);
  check('   on again: stored as \'1\'', (await kv('ovl_accurate_shades')) === '1');
  await page.locator('#ovlBlackSw').click(); await sleep(80);
  check('   Pure black on: stored true', (await kv('ovl_black')) === true && (await rows())[1].sw.checked === 'true');
  await reset(); await apply(); p = await plan();
  check('   the plan: system_surface_dark black, system_background_dark black; light mode kept', at(p, 'surface_dark') === '0xFF000000' && at(p, 'background_dark') === '0xFF000000' && at(p, 'surface_light') === at(base, 'surface_light') && (await ev(() => kvGet('ovl_root_applied', null).black === true)), JSON.stringify([at(p, 'surface_dark'), at(p, 'background_dark'), at(p, 'surface_light')]));
  await page.locator('#ovlBlackSw').click(); await sleep(80);
  await page.locator('#ovlTintSw').click(); await sleep(80);
  check('   Tint text color off: stored false', (await kv('ovl_tint_text')) === false && (await rows())[2].sw.checked === 'false');
  await reset(); await apply(); p = await plan();
  check('   the plan: plain white text on the dark surfaces, black on the light ones; with it on they were tinted', at(p, 'on_surface_dark') === '0xFFFFFFFF' && at(p, 'on_background_dark') === '0xFFFFFFFF' && at(p, 'on_surface_light') === '0xFF000000' && at(base, 'on_surface_dark') !== '0xFFFFFFFF', JSON.stringify([at(p, 'on_surface_dark'), at(p, 'on_surface_light'), at(base, 'on_surface_dark')]));
  check('   the hint names no switch (one sentence for the overlay)', (await ev(() => document.getElementById('ovlApplyHint').innerText.replace(/\s+/g, ' ').trim())) === 'Android repaints in a moment, and apps may restart. Also writes these colors into Android with Super User: the same palettes as ColorBlendr. Reset also removes the theme overlay.');

  // 3) the focus survives the redraw of a toggle
  await ev(() => document.getElementById('ovlTintSw').focus());
  await page.keyboard.press('Enter'); await sleep(80);
  check('3. Enter on a focused switch toggles it and the focus stays on it', (await kv('ovl_tint_text')) === true && (await ev(() => document.activeElement && document.activeElement.id === 'ovlTintSw')));

  // 4) a restart keeps them; locked without uid 0
  await page.locator('#ovlBlackSw').click(); await sleep(60);
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.addInitScript(() => { window.__sdk = 35; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await p2.goto(PAGE); await p2.waitForTimeout(500);
  await p2.evaluate(() => switchView('overlays')); await p2.waitForTimeout(400);
  check('4. after a restart Pure black is still on and the other two as left', await p2.evaluate(() => ['ovlAccurateSw', 'ovlBlackSw', 'ovlTintSw'].map(id => document.getElementById(id).getAttribute('aria-checked')).join() === 'true,true,true'));
  await p2.evaluate(() => { window.__root = false; checkAllWorkingModes(false); }); await p2.waitForTimeout(150);
  const locked = await p2.evaluate(() => Array.from(document.querySelectorAll('#ovlSwitches .rf-row')).map(r => r.classList.contains('is-locked') + ':' + r.querySelector('.rf-name').childNodes[0].textContent.trim() + ':' + r.querySelector('.pm-lock span').innerText + ':' + !!r.querySelector('.sdb-sw')));
  check('   without uid 0: three locked rows with the glyph and the reason, no switch', locked.join('|') === 'true:🔒 Accurate shades:Needs Super User (uid 0) to write Material You colors:false|true:🔒 Pure black:Needs Super User (uid 0) to write Material You colors:false|true:🔒 Tint text color:Needs Super User (uid 0) to write Material You colors:false', locked.join('|'));
  await p2.evaluate(() => { ovlToggleBlack(); ovlToggleTint(); ovlToggleAccurate(); });
  check('   the toggles refuse while locked', await p2.evaluate(() => kvGet('ovl_black', null) === true && kvGet('ovl_tint_text', null) === true && kvGet('ovl_accurate_shades', null) === '1'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
