// Secondary and tertiary accents on the one Theme screen (v7.22.0, ColorBlendr parity; PR 7's row folded into the Color card): two entries,
// each its name and the Settings pickers' colour bubble (a dashed empty circle and "From the style" while not set; the swatch, the hex and Clear
// once set); a tap opens the page's colour picker, titled Secondary accent / Tertiary accent, on the colour the style gives that row now (its
// palette's key colour) or on the one set; the sheet's Apply stores it (kv ovl_accent_*), nothing is written until Apply theme; the plan moves
// that palette's shades (and the matching roles) and leaves the main colour alone; Clear puts the entry back; a locked row without uid 0; a
// restart keeps them.
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
  const entries = () => ev(() => Array.from(document.querySelectorAll('#ovlAccents .ovl-acc-row')).map(r => ({ name: r.querySelector('.ovl-acc-name').innerText, value: r.querySelector('.bubble-hex, .ovl-acc-from').innerText, unset: !!r.querySelector('.ovl-acc-unset'), swatch: r.querySelector('.bubble-circle').style.background, clear: !!r.querySelector('[id$="Clear"]'), labelled: r.querySelector('.picker-bubble-btn').getAttribute('aria-labelledby'), popup: r.querySelector('.picker-bubble-btn').getAttribute('aria-haspopup') })));
  const picker = () => ev(() => ({ shown: document.getElementById('colorPickModal').classList.contains('show'), title: document.getElementById('cpTitle').innerText, sub: document.getElementById('cpSub').innerText, hex: cp.hex }));

  await ev(() => switchView('overlays')); await sleep(400);
  let e = await entries();
  check('1. two entries in the Color card: Secondary accent and Tertiary accent, each From the style with a dashed empty circle, no Clear; each opens a dialog', e.length === 2 && e.map(x => x.name).join('|') === 'Secondary accent|Tertiary accent' && e.every(x => x.value === 'From the style' && x.unset && !x.clear && x.popup === 'dialog' && /Name .*Val$/.test(x.labelled)) && (await ev(() => document.getElementById('ovlAccents').closest('.color-card').querySelector('.color-card-title').innerText === 'Color')), JSON.stringify(e));
  check('   the row\'s name and line', await ev(() => { const r = document.getElementById('ovlAccents').querySelector('.rf-row .rf-name'); return r.childNodes[0].textContent.trim() === 'Secondary and tertiary accents' && r.querySelector('span').innerText === 'Your own colors for the second and third accent. The main color stays as it is.'; }));

  // 2) the picker opens on the colour the style gives the row now
  await page.locator('#ovlAccSecondary').click(); await sleep(150);
  let p = await picker();
  const key2 = await ev(() => MonetPlan.cb.palettes(ovlRootOpts()).accent2.key.toUpperCase());
  check('2. the picker opens as Secondary accent on the colour the style gives accent 2 now, and says Apply theme writes the colour', p.shown && p.title === 'Secondary accent' && p.hex === key2 && p.sub === 'The style gives #' + key2 + ' now • Apply theme writes your color to the phone', JSON.stringify([p, key2]));
  await ev(() => cpPick('F44336')); await page.locator('#cpApply').click(); await sleep(150);
  e = await entries();
  check('   its Apply stores #F44336: the swatch, the hex and Clear; the sheet closes; nothing written', e[0].value === '#F44336' && !e[0].unset && e[0].clear && /rgb\(244, 67, 54\)/.test(e[0].swatch) && (await kv('ovl_accent_secondary')) === 'F44336' && !(await picker()).shown && (await ev(() => window.__ovlCalls.fab.length === 0)), JSON.stringify(e[0]));
  await page.locator('#ovlAccSecondary').click(); await sleep(150);
  p = await picker();
  check('   opened again it starts on the colour set and says so', p.hex === 'F44336' && p.sub === 'Current color #F44336 • Apply theme writes it to the phone', JSON.stringify(p));
  await ev(() => closeColorPicker());
  check('   the preview\'s accent 2 row follows the colour, accent 1 does not', await ev(() => { const pal = MonetPlan.cb.palettes(ovlRootOpts()); const chips = Array.from(document.querySelectorAll('#ovlPalette .ovl-pal')); const row = i => Array.from(chips[i].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex); return row(1)[5] === '#' + pal.accent2.shades[6].toUpperCase() && pal.accent2.key.toUpperCase() !== (MonetPlan.cb.palettes(Object.assign(ovlRootOpts(), { secondary: null })).accent2.key.toUpperCase()); }));

  // 3) the plan moves that palette and its roles, the main colour stays
  await reset(); await apply();
  check('3. Apply: the record carries the accent; accent 2 shades and the secondary roles differ from the plan without it, accent 1 and the primary roles are the same', await ev(() => { const r = kvGet('ovl_root_applied', null); if (r.opts.secondary !== 'F44336') return false; const w = MonetPlan.cb.entries(r.opts, 35), o = MonetPlan.cb.entries(Object.assign({}, r.opts, { secondary: null }), 35); const at = n => i => i.find(x => x.resource === 'android:color/system_' + n && !x.config).argb; return at('accent2_500')(w) !== at('accent2_500')(o) && at('secondary_light')(w) !== at('secondary_light')(o) && at('accent1_500')(w) === at('accent1_500')(o) && at('primary_light')(w) === at('primary_light')(o); }));
  // the tertiary one too, set through the same hook the sheet uses
  await ev(() => { openColorPicker('', 'accentTertiary'); }); await sleep(100);
  check('   the tertiary picker is titled Tertiary accent', (await picker()).title === 'Tertiary accent');
  await ev(() => cpPick('009688')); await page.locator('#cpApply').click(); await sleep(150);
  check('   stored as 009688', (await kv('ovl_accent_tertiary')) === '009688' && (await entries())[1].value === '#009688');

  // 4) Clear
  await page.locator('#ovlAccSecondaryClear').click(); await sleep(100);
  e = await entries();
  check('4. Clear: the entry is From the style again, cleared in the store, the focus moves to the entry', e[0].value === 'From the style' && e[0].unset && !e[0].clear && (await kv('ovl_accent_secondary')) === '' && (await ev(() => document.activeElement && document.activeElement.id === 'ovlAccSecondary')), JSON.stringify(e[0]));
  check('   odd stored values count as not set', await ev(() => { kvSet('ovl_accent_secondary', 'zz'); const v = ovlAccStored(OVL_ACC[0]); kvSet('ovl_accent_secondary', ''); return v === ''; }));

  // 5) a restart keeps the tertiary one; locked without uid 0
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.addInitScript(() => { window.__sdk = 35; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await p2.goto(PAGE); await p2.waitForTimeout(500);
  await p2.evaluate(() => switchView('overlays')); await p2.waitForTimeout(400);
  check('5. after a restart the Tertiary accent is still #009688, the Secondary one from the style', await p2.evaluate(() => document.querySelector('#ovlAccTertiary .bubble-hex').innerText === '#009688' && !!document.querySelector('#ovlAccSecondary .ovl-acc-from')));
  await p2.evaluate(() => { window.__root = false; checkAllWorkingModes(false); }); await p2.waitForTimeout(150);
  const locked = await p2.evaluate(() => { const r = document.getElementById('ovlAccents').querySelector('.rf-row'); return { locked: r.classList.contains('is-locked'), name: r.querySelector('.rf-name').childNodes[0].textContent.trim(), why: r.querySelector('.pm-lock span').innerText, bubbles: document.querySelectorAll('#ovlAccents .picker-bubble-btn').length }; });
  check('   without uid 0: a locked row with the glyph and the reason, no entries; the stored colour stays', locked.locked && locked.name === '🔒 Secondary and tertiary accents' && locked.why === 'Needs Super User (uid 0) to write Material You colors' && locked.bubbles === 0 && (await p2.evaluate(() => kvGet('ovl_accent_tertiary', null) === '009688')), JSON.stringify(locked));
  await p2.evaluate(() => { ovlAccSet('secondary', '123456'); ovlAccClear('tertiary'); });
  check('   set and Clear refuse while locked', await p2.evaluate(() => kvGet('ovl_accent_secondary', null) === '' && kvGet('ovl_accent_tertiary', null) === '009688'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
