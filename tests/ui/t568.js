// Intensity on the one Theme screen (v7.22.0, ColorBlendr parity; PR 6's sliders folded in): three native sliders (Accent saturation,
// Background saturation, Background lightness), 0 to 200 in steps of 5, 100 % when never moved, each with its value label and aria-valuetext;
// moving one (input) shows the value, the preview follows and nothing is stored or written; letting go (change) stores it (kv ovl_adj_*); Reset
// puts all three back to 100 and is disabled there; the plan follows the values (MonetPlan.cb's accentSaturation, backgroundSaturation,
// backgroundLightness); the sliders are drawn once (a redraw does not take one from a finger); a locked row without uid 0; a restart keeps them.
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
  const sliders = () => ev(() => Array.from(document.querySelectorAll('#ovlAdjust input[type=range]')).map(x => ({ id: x.id, key: x.dataset.adj, min: x.min, max: x.max, step: x.step, value: x.value, text: x.getAttribute('aria-valuetext'), label: document.getElementById(x.id + 'V').innerText, name: document.querySelector('label[for="' + x.id + '"] span').innerText })));
  const resetBtn = () => ev(() => { const r = document.getElementById('ovlAdjReset'); return r ? { text: r.innerText, disabled: r.disabled } : null; });
  const move = async (id, v, change) => { await ev(([i, val, ch]) => { const el = document.getElementById(i); el.value = String(val); el.dispatchEvent(new Event('input', { bubbles: true })); if (ch) el.dispatchEvent(new Event('change', { bubbles: true })); }, [id, v, !!change]); await sleep(60); };
  const chip = () => ev(() => document.querySelector('#ovlPalette .ovl-pal:nth-child(2) .ovl-chip:nth-child(6)').dataset.hex);     // accent1 500 of the preview

  await ev(() => switchView('overlays')); await sleep(400);
  let s = await sliders();
  check('1. three sliders, 0 to 200 in steps of 5, each at 100 % with its label and aria-valuetext; the row\'s name and line', s.length === 3 && s.map(x => x.name).join('|') === 'Accent saturation|Background saturation|Background lightness' && s.every(x => x.min === '0' && x.max === '200' && x.step === '5' && x.value === '100' && x.text === '100%' && x.label === '100%') && (await ev(() => { const r = document.getElementById('ovlAdjust').querySelector('.rf-row .rf-name'); return r.childNodes[0].textContent.trim() === 'Intensity' && r.querySelector('span').innerText === '100% leaves the computed colors as they are.'; })), JSON.stringify(s));
  check('   Reset is there and disabled at 100 %; nothing stored', JSON.stringify(await resetBtn()) === '{"text":"Reset","disabled":true}' && (await kv('ovl_adj_accent_sat')) === null && (await ev(() => document.querySelector('#ovlAdjust .ovl-adj').getAttribute('aria-label') === 'Intensity')));

  // 2) moving: shown, the preview follows, nothing stored or written; letting go stores it
  const before = await chip();
  await move('ovlAdjAccentSat', 160, false);
  s = await sliders();
  check('2. Accent saturation moved to 160 (not let go): the label and valuetext say 160 %, Reset enabled, nothing stored, no bridge call', s[0].value === '160' && s[0].label === '160%' && s[0].text === '160%' && !(await resetBtn()).disabled && (await kv('ovl_adj_accent_sat')) === null && (await ev(() => window.__ovlCalls.fab.length === 0)), JSON.stringify(s[0]));
  check('   the preview follows it (the accent1 chips change)', (await chip()) !== before, (await chip()) + ' vs ' + before);
  check('   the input is the same element (drawn once, not replaced)', await ev(() => { window.__adjEl = document.getElementById('ovlAdjAccentSat'); ovlRenderRoot(); return document.getElementById('ovlAdjAccentSat') === window.__adjEl && document.getElementById('ovlAdjAccentSat').value === '160'; }));
  await move('ovlAdjAccentSat', 160, true);
  check('   let go: stored as 160', (await kv('ovl_adj_accent_sat')) === 160);
  await move('ovlAdjBgLight', 143, true);
  check('   a value off the steps is held to them (143 -> 145) and clamped', (await kv('ovl_adj_bg_light')) === 145 && (await sliders())[2].value === '145');
  await move('ovlAdjBgSat', 300, true);
  check('   300 is clamped to 200', (await kv('ovl_adj_bg_sat')) === 200);

  // 3) the plan follows
  await reset(); await apply();
  const rec = await kv('ovl_root_applied');
  check('3. Apply: the record carries the three values and the plan is what the maths give for them', rec.opts.accentSaturation === 160 && rec.opts.backgroundSaturation === 200 && rec.opts.backgroundLightness === 145 && (await ev(() => { const r = kvGet('ovl_root_applied', null), e = MonetPlan.cb.entries(r.opts, 35), f = window.__ovlCalls.fab[0]; const plain = MonetPlan.cb.entries(Object.assign({}, r.opts, { accentSaturation: 100, backgroundSaturation: 100, backgroundLightness: 100 }), 35); return e.every((x, i) => '0x' + x.argb.slice(1) === f.entries[i].argb) && e.some((x, i) => x.argb !== plain[i].argb); })), JSON.stringify(rec.opts));

  // 4) Reset
  await page.locator('#ovlAdjReset').click(); await sleep(80);
  s = await sliders();
  check('4. Reset: all three back to 100, stored so, the button disabled, the focus on the first slider', s.every(x => x.value === '100' && x.label === '100%') && (await kv('ovl_adj_accent_sat')) === 100 && (await kv('ovl_adj_bg_sat')) === 100 && (await kv('ovl_adj_bg_light')) === 100 && (await resetBtn()).disabled && (await ev(() => document.activeElement && document.activeElement.id === 'ovlAdjAccentSat')), JSON.stringify(s.map(x => x.value)));

  // 5) a restart keeps them; locked without uid 0
  await move('ovlAdjBgLight', 60, true);
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.addInitScript(() => { window.__sdk = 35; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await p2.goto(PAGE); await p2.waitForTimeout(500);
  await p2.evaluate(() => switchView('overlays')); await p2.waitForTimeout(400);
  check('5. after a restart Background lightness is still 60 %', await p2.evaluate(() => document.getElementById('ovlAdjBgLight').value === '60' && document.getElementById('ovlAdjBgLightV').innerText === '60%' && !document.getElementById('ovlAdjReset').disabled));
  await p2.evaluate(() => { window.__root = false; checkAllWorkingModes(false); }); await p2.waitForTimeout(150);
  const locked = await p2.evaluate(() => { const r = document.getElementById('ovlAdjust').querySelector('.rf-row'); return { locked: r.classList.contains('is-locked'), name: r.querySelector('.rf-name').childNodes[0].textContent.trim(), why: r.querySelector('.pm-lock span').innerText, inputs: document.querySelectorAll('#ovlAdjust input').length }; });
  check('   without uid 0: a locked row with the glyph and the reason, no slider; the stored value stays', locked.locked && locked.name === '🔒 Intensity' && locked.why === 'Needs Super User (uid 0) to write Material You colors' && locked.inputs === 0 && (await p2.evaluate(() => kvGet('ovl_adj_bg_light', null) === 60)), JSON.stringify(locked));
  await p2.evaluate(() => ovlAdjReset());
  check('   Reset refuses while locked', await p2.evaluate(() => kvGet('ovl_adj_bg_light', null) === 60));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
