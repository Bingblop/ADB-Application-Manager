// Intensity (ColorBlendr PR 6): the three sliders in the Overlays tab's Root-only theme controls card (Accent saturation, Background
// saturation, Background lightness: 0 to 200 % in steps of 5, 100 % when never moved; kv ovl_adj_accent_sat, ovl_adj_bg_sat,
// ovl_adj_bg_light) and what Apply writes with them (monet.js's opts.adjust). (a) Locks, as Color spec and More styles have them: Update the
// app (no overlayFabricate, or a monet.js without the sliders), the build family's lock (T-049), Needs Super User; Needs Android 14 on
// Android 12 and 13; stored values count for nothing there and the handlers change nothing. (b) Live on Android 14 (P14) and 16.0 (P16).
// (c) Moving a slider changes the value shown, the hint and the preview and calls no bridge method but the reads of the page's settings and
// of the dark mode; letting go stores that one value; the slider stays under the finger while the preview comes and goes. (d) Apply: a list
// style at 2021 with a slider off 100 is written by the app (MonetPlan.entries with the same adjust), on P16 and P14; the values are the ones
// of the tap; all at 100 writes what the base writes; Pure black wins. (e) The hint in every variant. (f) The record's adjust (old records
// read as 100 / 100 / 100) and the note for colours written on another build. (g) Reset. (h) Keyboard and aria-valuetext. (i) The maths
// through the page's MonetPlan: identity at 100, the saturation sliders at 0, Background lightness at 0 and 200, Monochrome, and values
// pinned from an oracle kept outside the repository: Google's Material Color Utilities (TypeScript, main) with the scheme's palettes
// adjusted by the definition (accent palettes TonalPalette.fromHueAndChroma(hue, chroma * accentSat / 100), neutral palettes the same with
// bgSat and, asked for tone t, the colour at f(t) = clamp(t + 0.1 * (bgLight - 100) * min(1, t / 10, (100 - t) / 10), 0, 100)) for P16, at
// 2021, 2025 and CMF's 2026 (the 2025 roles with a chroma multiplier at Hct.from(hue, chroma x m, f(tone)), cross-checked where only bgLight
// moves by Hct.from on the unadjusted scheme's role tones), and the monet.js from before the sliders run by hand the same way for P14
// (Android 14's shade rule with its cap of chroma 40 on shades 10 and 50 before the factor, its engine's role tones). (j) German, and the
// row's words following a language switch.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const SUB = '100% leaves Android\'s colors as they are.';
const NOT_YET = 'Not supported on this Android version yet';
const STALE = 'Android was updated after these colors were written. Tap Apply theme to write them for this version.';
const AAM = 'com.android.shell:aam_monet';
const RES = 'android:color/system_';
const IDS = ['ovlAdjAccentSat', 'ovlAdjBgSat', 'ovlAdjBgLight'];
const KV = ['ovl_adj_accent_sat', 'ovl_adj_bg_sat', 'ovl_adj_bg_light'];
const B14 = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
const B16 = 'google/mock/mock:16/BP2A.250605.031/13578712:user/release-keys';
const B16b = 'google/mock/mock:16/BP2A.250805.005/13860026:user/release-keys';
const HINT = {
  list: 'Also writes this style\'s colors with root, adjusted by the Intensity sliders.',
  listBlack: 'Also writes this style\'s colors, adjusted by the Intensity sliders, and pure black with root.',
  list25: 'Also writes this style\'s 2025 colors with root, adjusted by the Intensity sliders.',
  list25Black: 'Also writes this style\'s 2025 colors, adjusted by the Intensity sliders, and pure black with root.',
  more: n => 'Also writes the ' + n + ' colors with root, in place of the style above, adjusted by the Intensity sliders.',
  moreBlack: n => 'Also writes the ' + n + ' colors, adjusted by the Intensity sliders, and pure black with root, in place of the style above.'
};
const A150 = { accentSat: 150, bgSat: 100, bgLight: 100 };
// Oracle pins (resource suffix: value), contrast 0; the same in light and dark mode. D1 and D2: seed 6750A4, spec 2021
const P16_TS150 = { accent1_500: '0xFF8166CB', accent2_500: '0xFF7C7296', accent3_500: '0xFFA8627A', primary_light: '0xFF684CB0', primary_dark: '0xFFCFBCFF', on_primary_container_dark: '0xFFE9DDFF', neutral1_500: '0xFF79767D', surface_dark: '0xFF141218' };
const P14_TS150 = { accent1_10: '0xFFFFFBFF', accent1_500: '0xFF8065CA', accent2_500: '0xFF7B7195', accent3_500: '0xFFA76179', primary_light: '0xFF684CB0', primary_dark: '0xFFCFBCFF', neutral1_500: '0xFF78757C', surface_dark: '0xFF141218' };
// I6: [profile, style, seed, spec, adjust, pins]. At 2025 and 2026 only role values are pinned (their _light and _dark entries are the same
// in either mode; the palette shades there come from the scheme of the mode). The 2025 and CMF rows go through the 2025 chroma-multiplier
// path (a neutral role asked for tone t gets Hct.from(hue, chroma x m, f(t))); the P14 accent1_50 rows through Android 14's cap on shades
// 10 and 50 (chroma min(40, c) x the factor: Content 00FF00 has c above 40; Vibrant FFFF00's accent1_50 is 0xFFF7F692 at 100)
const PINS = [
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { bgLight: 200 }, { neutral1_10: '0xFFFFFFFF', neutral1_100: '0xFFFFFFFF', neutral1_500: '0xFF938F96', neutral1_900: '0xFF322F35', neutral2_500: '0xFF948F99', surface_light: '0xFFFFFFFF', surface_dark: '0xFF211F24', on_surface_light: '0xFF322F35', surface_container_high_dark: '0xFF413F45', accent1_500: '0xFF7E6EA9', primary_dark: '0xFFCFBDFE' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { bgLight: 0 }, { neutral1_10: '0xFFFDF7FF', neutral1_100: '0xFFCAC5CC', neutral1_500: '0xFF605D64', neutral1_900: '0xFF000000', surface_light: '0xFFF8F2FA', surface_dark: '0xFF000000', on_surface_light: '0xFF000000', surface_container_high_dark: '0xFF16151A' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { accentSat: 50, bgSat: 150, bgLight: 130 }, { accent1_100: '0xFFE8DEFB', accent1_500: '0xFF7B738D', neutral1_500: '0xFF817D88', neutral1_900: '0xFF232029', primary_light: '0xFF625B73', primary_dark: '0xFFCCC2DF', surface_dark: '0xFF18161F', surface_container_high_dark: '0xFF322F39' }],
  ['P16', 'FIDELITY', '6750A4', '2021', { accentSat: 50 }, { accent1_500: '0xFF7C7296', accent3_500: '0xFF86754F', primary_light: '0xFF4B4263', primary_dark: '0xFFCDC0E9', on_primary_container_dark: '0xFFE0D3FC', neutral1_500: '0xFF79767D' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', { bgLight: 200 }, { neutral1_100: '0xFFFFFFFF', neutral1_500: '0xFF928E95', neutral1_900: '0xFF322F35', neutral2_500: '0xFF938E98', surface_dark: '0xFF211F24', surface_container_high_dark: '0xFF413F45' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', { bgLight: 0 }, { neutral1_10: '0xFFFDF7FF', neutral1_100: '0xFFCAC5CC', neutral1_500: '0xFF5F5C63', neutral1_900: '0xFF000000', surface_light: '0xFFF8F2FA', surface_dark: '0xFF000000', on_surface_light: '0xFF000000' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2025', { bgLight: 200 }, { on_surface_light: '0xFF4B4751', on_surface_dark: '0xFFFFFFFF', on_surface_variant_light: '0xFF7A7681', outline_light: '0xFF98929E', surface_container_highest_light: '0xFFFFFFFF', surface_container_dark: '0xFF2D2B32', surface_light: '0xFFFFFFFF' }],
  ['P16', 'CMF', '6750A4', '2026', { accentSat: 150, bgSat: 50, bgLight: 150 }, { primary_light: '0xFF5F2CCA', primary_dark: '0xFFAA8AFF', on_surface_light: '0xFF3F3C45', on_surface_dark: '0xFFF7F0FC', on_surface_variant_dark: '0xFFBCB6C2', surface_container_highest_light: '0xFFF5EEFA', surface_container_dark: '0xFF242228' }],
  ['P14', 'CONTENT', '00FF00', '2021', { accentSat: 50 }, { accent1_10: '0xFFF7FFEE', accent1_50: '0xFFE1F8D5', accent1_100: '0xFFA8F693', accent1_500: '0xFF3E8532' }],
  ['P14', 'VIBRANT', 'FFFF00', '2021', { accentSat: 150 }, { accent1_50: '0xFFF8F858' }],
  ['P14', 'VIBRANT', 'FFFF00', '2021', { accentSat: 200 }, { accent1_50: '0xFFF9F900' }]
];
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const kvs = () => ev(keys => keys.map(k => kvGet(k, null)), KV);
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  const phone = async o => {
    await ev(o => { window.__sdk = o.sdk; window.__profile = o.profile; window.__profileReason = o.reason; window.__build = o.build === undefined ? null : o.build; window.__contrast = 0; ovlReadPalette(); ovlRender(); }, o);
    await sleep(150);
  };
  // the sliders' stored values (null: never set), drawn again
  const setAdj = async (a, b, c) => { await ev(([v, keys]) => { keys.forEach((k, i) => { if (v[i] === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v[i]); }); ovlRender(); }, [[a, b, c], KV]); await sleep(50); };
  // the Intensity container: the locked row, or the row with Reset and the three sliders; the hint under Apply and the preview above
  const adj = () => ev(() => {
    const box = document.getElementById('ovlRootAdjust') || document.createElement('div');      // none: a page without the sliders (every row reads as missing)
    const row = box.querySelector('.rf-row'), lock = box.querySelector('.pm-lock'), lockBtn = box.querySelector('.op-mode-btn.locked'), reset = document.getElementById('ovlAdjReset'), group = box.querySelector('.ovl-adj'), pv = document.getElementById('ovlRootPreview');
    const sliders = Array.from(box.querySelectorAll('input[type=range]')).map(el => ({ id: el.id, key: el.dataset.adj, min: el.min, max: el.max, step: el.step, value: el.value, text: el.getAttribute('aria-valuetext'), label: el.labels && el.labels.length === 1 ? el.labels[0].querySelector('span').innerText : '', shown: (document.getElementById(el.id + 'V') || { innerText: '' }).innerText }));
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(row && row.classList.contains('is-locked')), name: row ? row.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', lock: lock ? lock.innerText : '', lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null,
      group: group ? { role: group.getAttribute('role'), label: group.getAttribute('aria-label') } : null, sliders, reset: reset ? { text: reset.innerText, disabled: reset.disabled, title: reset.title } : null,
      hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '', more: (document.querySelector('#ovlRootMore .pm-lock') || { innerText: '' }).innerText };
  });
  const sl = (a, i) => (a && a.sliders && a.sliders[i]) || {};
  const lockedAs = (a, reason) => a && a.rows === 1 && a.locked && a.lock === reason && a.lockBtn && a.lockBtn.disabled && a.sliders.length === 0 && a.reset === null && a.group === null;
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  const waitFab = () => page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => { const want = MonetPlan.entries(style, seed, opts || undefined); if (!entries || want.length !== entries.length) return 'count ' + (entries || []).length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan && plan.entries, style, seed, opts || null]);
  // T-069: a record with a style also keeps the source colour, the mode and the contrast (seed, dark, contrast) for the Undo; t564 checks them,
  // and the record strings compared here leave them out
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  // every AndroidBridge method, counted from here on (name and arguments) while window.__spy is on
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });
  await ev(() => { window.__bc = []; const b = window.AndroidBridge; for (const k of Object.keys(b)) if (typeof b[k] === 'function') { const f = b[k]; b[k] = function () { window.__bc.push([k].concat(Array.from(arguments, a => String(a)))); return f.apply(this, arguments); }; } });
  const READS = ['loadSetting', 'isSystemDarkMode'];

  // ---- (a) locks ----
  await ev(() => switchView('overlays')); await sleep(300);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_root_applied', null); });
  await phone({ sdk: 34, build: B14 });
  let a = await adj();
  check('A1. without Super User (Android 14): Intensity is a locked row of the Root Features kind (Locked, Needs Super User) with its line, no sliders, no Reset', lockedAs(a, 'Needs Super User') && a.name === 'Intensity ' + SUB && a.lockBtn.text === 'Locked', JSON.stringify(a));
  check('   its container sits under More styles and above the notes, inside the Root-only theme controls card', await ev(() => { const box = document.getElementById('ovlRootAdjust'); return !!box && !!box.closest('#ovlRootCard') && box.previousElementSibling.id === 'ovlRootMore' && box.nextElementSibling.id === 'ovlRootAccents' && box.nextElementSibling.nextElementSibling.id === 'ovlRootAccurate' && box.nextElementSibling.nextElementSibling.nextElementSibling.id === 'ovlRootNote'; }));
  await setAdj(150, 50, 130); a = await adj();
  check('   values stored from a rooted session (150, 50, 130) count for nothing: still locked, no hint, no plan style, ovlRootAdjOn() null', lockedAs(a, 'Needs Super User') && a.hint === '' && (await ev(() => typeof ovlRootAdjOn === 'function' && ovlRootAdjOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify(a));
  const handlers = () => ev(() => { if (typeof ovlAdjInput !== 'function') return 'no handlers'; const fake = { dataset: { adj: 'accentSat' }, value: '10', getBoundingClientRect: () => ({ top: 0 }) }; window.__bc.length = 0; ovlAdjInput(fake); ovlAdjChange(fake); ovlAdjReset(); const w = window.__bc.filter(c => c[0] === 'saveSetting'); return [kvGet('ovl_adj_accent_sat', null), kvGet('ovl_adj_bg_sat', null), kvGet('ovl_adj_bg_light', null), w.length]; });
  check('   the handlers called directly change nothing (no value stored, no saveSetting)', JSON.stringify(await handlers()) === '[150,50,130,0]', JSON.stringify(await handlers()));
  await reset(); await apply(); let k = await calls();
  check('   Apply writes the theme setting only: no overlay call', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); a = await adj();
  check('   Android 13 without Super User: Needs Super User (the root lock comes first)', lockedAs(a, 'Needs Super User'), a.lock);
  await setRoot(true); a = await adj();
  check('A2. Super User on Android 13 (P12): Intensity is a locked row saying Needs Android 14, as More styles', lockedAs(a, 'Needs Android 14') && a.more === 'Needs Android 14', JSON.stringify([a.lock, a.more]));
  check('   the stored values count for nothing there (no hint, no plan style); the handlers change nothing', a.hint === '' && (await ev(() => typeof ovlRootAdjOn === 'function' && ovlRootAdjOn() === null)) && JSON.stringify(await handlers()) === '[150,50,130,0]', a.hint);
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply(); k = await calls();
  const r33 = await kv('ovl_root_applied');
  check('   with Pure black on there: its own plan only (ovlBuildBlackPlan(33)), and the record carries no adjust', k.fab.length === 1 && JSON.stringify(k.fab[0].entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(33))) && r33 && r33.style === '' && !('adjust' in r33), JSON.stringify(Object.assign({}, r33, { t: 'T' })));
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_applied', null); ovlRender(); });
  const LOCKS = [
    ['Android 16 QPR1 (P16Q1)', { sdk: 36, profile: 'P16Q1', reason: 'notYet', build: B16 }, NOT_YET],
    ['an older APK on Android 16 (no profile)', { sdk: 36, profile: null, build: null }, 'Update the app']
  ];
  for (const [label, o, reason] of LOCKS) {
    await phone(o); a = await adj();
    check('A3. Super User, ' + label + ': Intensity is locked with "' + reason + '", as More styles', lockedAs(a, reason) && a.more === reason && a.hint === '', JSON.stringify([a.lock, a.more, a.hint]));
    await reset(); await apply(); k = await calls();
    check('   the handlers change nothing; Apply writes the theme setting only', JSON.stringify(await handlers()) === '[150,50,130,0]' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  }
  await ev(() => { window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; });
  await phone({ sdk: 34, build: B14 }); a = await adj();
  check('A4. an APK without overlayFabricate, Super User on Android 14: Update the app, as the other rows', lockedAs(a, 'Update the app') && a.more === 'Update the app', JSON.stringify([a.lock, a.more]));
  await phone({ sdk: 36, profile: 'P16Q1', reason: 'notYet', build: B16 }); a = await adj();
  check('   and on Android 16 QPR1 (a locked build family): Update the app comes first', lockedAs(a, 'Update the app'), a.lock);
  await setRoot(false); a = await adj();
  check('   and without Super User: Update the app', lockedAs(a, 'Update the app'), a.lock);
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; }); await setRoot(true);
  // a colour maths that does not know the sliders (the monet.js from before them: MonetPlan.lib without adjustOf)
  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = Object.assign({}, window.__mp, { lib: Object.assign({}, window.__mp.lib, { adjustOf: undefined }) }); });
  await phone({ sdk: 34, build: B14 }); a = await adj();
  check('A5. Android 14 with Super User and a monet.js without the sliders: Intensity says Update the app; More styles stays live', lockedAs(a, 'Update the app') && a.more === '' && (await ev(() => !!document.getElementById('ovlRootStyles'))), JSON.stringify([a.lock, a.more]));
  check('   the stored values count for nothing (no hint, Tonal Spot at 2021 gives no plan style)', a.hint === '' && (await ev(() => typeof ovlRootAdjOn === 'function' && ovlRootAdjOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')), a.hint);
  await reset(); await apply(); k = await calls();
  check('   Apply writes the theme setting only', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); a = await adj();
  check('   and on Android 13: Update the app comes before Needs Android 14', lockedAs(a, 'Update the app') && a.more === 'Needs Android 14', JSON.stringify([a.lock, a.more]));
  await ev(() => { window.MonetPlan = window.__mp; });

  // ---- (b) live on Android 14 and 16 ----
  await setAdj(null, null, null); await phone({ sdk: 34, build: B14 }); a = await adj();
  const fresh = s => s.length === 3 && s.every((x, i) => x.id === IDS[i] && x.min === '0' && x.max === '200' && x.step === '5' && x.value === '100' && x.text === '100%' && x.shown === '100%') && s.map(x => x.key).join() === 'accentSat,bgSat,bgLight' && s.map(x => x.label).join('|') === 'Accent saturation|Background saturation|Background lightness';
  check('B1. Super User on Android 14 (P14): the Intensity row with its line, a group "Intensity" of three labelled sliders (0 to 200, step 5) at 100 % (aria-valuetext and the value shown), Reset disabled', !a.locked && a.rows === 1 && a.name === 'Intensity ' + SUB && a.group && a.group.role === 'group' && a.group.label === 'Intensity' && fresh(a.sliders) && a.reset && (a.reset || {}).text === 'Reset' && (a.reset || {}).disabled && (a.reset || {}).title === 'Set all three to 100%', JSON.stringify(a));
  check('   Tonal Spot in the list at 2021, all at 100: no hint, no preview, no plan style (Android writes its own colours)', a.hint === '' && !a.preview && (await ev(() => typeof ovlRootAdjOn === 'function' && ovlRootAdjOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify([a.hint, a.preview]));
  await setAdj(150, 50, 130); a = await adj();
  check('   stored values 150, 50 and 130: the sliders show them, Reset is enabled, ovlRootAdjOn() gives them', a.sliders.map(x => x.value + '/' + x.text + '/' + x.shown).join(' ') === '150/150%/150% 50/50%/50% 130/130%/130%' && !(a.reset || {}).disabled && JSON.stringify(await ev(() => typeof ovlRootAdjOn === 'function' ? ovlRootAdjOn() : 'missing')) === '{"accentSat":150,"bgSat":50,"bgLight":130}', JSON.stringify(a.sliders));
  const held = await ev(() => [[203, 'x', -7], [12, 199.9, null]].map(v => { kvSet('ovl_adj_accent_sat', v[0]); kvSet('ovl_adj_bg_sat', v[1]); kvSet('ovl_adj_bg_light', v[2]); return typeof ovlAdjValues === 'function' ? JSON.stringify(ovlAdjValues()) : 'missing'; }));
  check('   a stored value is held to 0..200 and to the steps of 5; anything but a number is 100', JSON.stringify(held) === JSON.stringify(['{"accentSat":200,"bgSat":100,"bgLight":0}', '{"accentSat":10,"bgSat":200,"bgLight":100}']), JSON.stringify(held));
  await setAdj(null, null, null); await phone({ sdk: 36, profile: 'P16', build: B16 }); a = await adj();
  check('B2. Super User on Android 16.0 (P16): the same live row, the three sliders at 100 %, Reset disabled', !a.locked && fresh(a.sliders) && a.reset && (a.reset || {}).disabled && a.hint === '' && !a.preview, JSON.stringify(a));

  // ---- (c) moving a slider ----
  const palOf = (style, adjust) => ev(([style, adjust]) => { const p = MonetPlan.palettes(style, '6750A4', Object.assign({ spec: '2021', dark: true, profile: 'P16', contrast: 0 }, adjust ? { adjust } : {})); return { accent1: p.accent1.shades.map(h => '#' + h), neutral1: p.neutral1.shades.map(h => '#' + h) }; }, [style, adjust]);
  const pvRows = () => ev(() => { const rows = Array.from(document.querySelectorAll('#ovlRootPreview .ovl-pal')); const hx = r => r ? Array.from(r.querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : []; return { rows: rows.length, accent1: hx(rows[0]), neutral1: hx(rows[3]) }; });
  // moves one slider as a finger does (input events; change when let go), in one go with the count of bridge calls and where the slider is on screen
  const move = (id, v, letGo) => ev(([id, v, letGo]) => {
    const el = document.getElementById(id);
    if (!el) return { onInput: [], onInputOther: [['no slider']], onChange: [['no slider']], moved: 99 };
    const top = el.getBoundingClientRect().top;
    window.__bc.length = 0;
    el.value = String(v); el.dispatchEvent(new Event('input', { bubbles: true }));
    const onInput = window.__bc.map(c => c.slice());
    window.__bc.length = 0;
    if (letGo) el.dispatchEvent(new Event('change', { bubbles: true }));
    const onChange = window.__bc.map(c => c.slice());
    return { onInput: Array.from(new Set(onInput.map(c => c[0]))), onInputOther: onInput.filter(c => !['loadSetting', 'isSystemDarkMode'].includes(c[0])), onChange: onChange.filter(c => !['loadSetting', 'isSystemDarkMode'].includes(c[0])), moved: Math.round(Math.abs(el.getBoundingClientRect().top - top) * 10) / 10 };
  }, [id, v, letGo]);
  await ev(id => { const el = document.getElementById(id); if (el) el.scrollIntoView({ block: 'center' }); }, IDS[0]);
  let mv = await move(IDS[0], 150, false); a = await adj();
  let pv = await pvRows(), want = await palOf('TONAL_SPOT', A150), plain = await palOf('TONAL_SPOT', null);
  check('C1. Accent saturation moved to 150 (input, not let go yet): the value shown and aria-valuetext say 150%; the hint says the app writes this style\'s colors, adjusted', sl(a, 0).value === '150' && sl(a, 0).text === '150%' && sl(a, 0).shown === '150%' && a.hint === HINT.list, JSON.stringify([sl(a, 0), a.hint]));
  check('   the preview appears with the adjusted shades (accent1 = MonetPlan.palettes with adjust 150, not the unadjusted ones)', pv.rows === 5 && JSON.stringify(pv.accent1) === JSON.stringify(want.accent1) && JSON.stringify(pv.accent1) !== JSON.stringify(plain.accent1) && JSON.stringify(pv.neutral1) === JSON.stringify(plain.neutral1), JSON.stringify(pv.accent1.slice(6, 8)));
  check('   no bridge method was called but the reads of the page\'s settings and of the dark mode (' + mv.onInput.join(', ') + '); nothing stored (kv still unset)', mv.onInputOther.length === 0 && mv.onInput.every(n => READS.includes(n)) && JSON.stringify(await kvs()) === '[null,null,null]', JSON.stringify([mv.onInputOther, await kvs()]));
  check('   the slider stayed where it was on screen (within 1.5 px) while the preview came in above it: the page scrolled by as much', mv.moved <= 1.5, mv.moved <= 1.5 ? '' : mv.moved);
  mv = await move(IDS[0], 150, true);
  check('C2. let go (change): one write, saveSetting(ovl_adj_accent_sat, 150), and nothing else; Reset enabled', JSON.stringify(mv.onChange) === '[["saveSetting","ovl_adj_accent_sat","150"]]' && JSON.stringify(await kvs()) === '[150,null,null]' && !((await adj()).reset || {}).disabled, JSON.stringify([mv.onChange, await kvs()]));
  mv = await move(IDS[2], 200, false); pv = await pvRows(); want = await palOf('TONAL_SPOT', { accentSat: 150, bgSat: 100, bgLight: 200 });
  check('   Background lightness moved to 200 too: the preview\'s neutral1 row follows (palettes with 150 / 100 / 200), still no write', JSON.stringify(pv.neutral1) === JSON.stringify(want.neutral1) && JSON.stringify(pv.accent1) === JSON.stringify(want.accent1) && mv.onInputOther.length === 0, JSON.stringify(pv.neutral1.slice(1, 4)));
  mv = await move(IDS[2], 100, true); mv = await move(IDS[0], 100, false); a = await adj();
  check('C3. both back to 100: the preview goes, the hint goes, the slider stays put (within 1.5 px)', !a.preview && a.hint === '' && mv.moved <= 1.5, JSON.stringify([a.preview, a.hint, mv.moved <= 1.5 ? 'put' : mv.moved]));
  mv = await move(IDS[0], 100, true);
  check('   let go: saveSetting(ovl_adj_accent_sat, 100); Reset disabled again', JSON.stringify(mv.onChange) === '[["saveSetting","ovl_adj_accent_sat","100"]]' && JSON.stringify(await kvs()) === '[100,null,100]' && ((await adj()).reset || {}).disabled, JSON.stringify([mv.onChange, await kvs()]));

  // ---- (d) Apply ----
  await setAdj(150, 100, 100); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('D1. P16, Tonal Spot in the list at 2021, Accent saturation 150: the setting gets TONAL_SPOT, then overlayFabricate with 177 entries, no resource twice', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && plan.action === 'fabricate' && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177, JSON.stringify([k.apply, (plan.entries || []).length]));
  const ts16 = await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0, adjust: A150 });
  check('   it is MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P16, contrast 0, adjust 150 / 100 / 100 }), not the unadjusted one; ' + Object.keys(P16_TS150).length + ' values pinned from the oracle', ts16 === 'same' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 })) !== 'same' && pinsOff(plan, P16_TS150).length === 0, JSON.stringify([ts16, pinsOff(plan, P16_TS150)]));
  check('   the record keeps the sliders: {"accentSat":150,"bgSat":100,"bgLight":100}, before the profile and the build; the toast and the note name the theme overlay', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"profile":"P16","build":"' + B16 + '"}' && (await toasts()).includes('Theme overlay written') && (await ev(() => document.getElementById('ovlRootNote').innerText)) === 'The theme overlay is written on this phone.', JSON.stringify([await rec(), await toasts()]));
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  const ts14 = await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0, adjust: A150 });
  check('D2. P14, the same: 165 entries, MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P14, contrast 0, adjust 150 }); ' + Object.keys(P14_TS150).length + ' values pinned from the oracle (Android 14\'s shade rule; Tonal Spot\'s accent1 chroma, 36, is under the cap of 40, which I6 pins)', k.fab.length === 1 && plan.entries && plan.entries.length === 165 && ts14 === 'same' && pinsOff(plan, P14_TS150).length === 0, JSON.stringify([ts14, pinsOff(plan, P14_TS150)]));
  check('   the record: Tonal Spot, the sliders, the build, no profile (P14)', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"build":"' + B14 + '"}', await rec());
  // the values of the tap: a slider moved between the tap and the theme answer, both ways
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_root_applied', null); window.__delay = 400; }); await reset();
  await page.locator('#ovlApplyBtn').click(); await move(IDS[0], 50, true);
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('D3. 150 when Apply is tapped, 50 before the theme answer: the plan is at 150 (the pins of D1), and so is the record', pinsOff(plan, P16_TS150).length === 0 && (await rec()).includes('"adjust":{"accentSat":150,"bgSat":100,"bgLight":100}') && JSON.stringify(await kvs()) === '[50,100,100]', JSON.stringify([pinsOff(plan, P16_TS150), await rec()]));
  await setAdj(100, 100, 100); await ev(() => kvSet('ovl_root_applied', null)); await reset();
  await page.locator('#ovlApplyBtn').click(); await move(IDS[0], 150, true);
  await settle(); await sleep(500); k = await calls();
  check('   and the other way round: all at 100 when tapped (Tonal Spot at 2021: Android\'s own colours), 150 before the answer: no overlay call, no record', k.apply.length === 1 && k.fab.length === 0 && (await rec()) === 'null', JSON.stringify(k.fab));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { if (typeof ovlAdjReset === 'function') ovlAdjReset(); });
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('   Fidelity with 150 when tapped, Reset before the answer: Fidelity\'s plan with adjust 150', (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0, adjust: A150 })) === 'same' && JSON.stringify(await kvs()) === '[100,100,100]', await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0, adjust: A150 }));
  await ev(() => { window.__delay = 20; });
  // all at 100: what the base writes
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D4. all at 100 (after Reset), Fidelity on P16: MonetPlan.entries without adjust, and the record has no adjust (as before the sliders)', (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', await rec());
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   on P14: PR 3\'s plan (entries(FIDELITY, 6750A4) without options, accent1_500 0xFF7F68BE); the record as before', plan.entries && plan.entries.length === 165 && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan).accent1_500 === '0xFF7F68BE' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","build":"' + B14 + '"}', await rec());
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); ovlRender(); }); await phone({ sdk: 36, profile: 'P16', build: B16 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   Tonal Spot at 2025 on P16, all at 100: entries(..., { spec 2025, dark, profile P16, contrast 0 }) without adjust; the record says 2025, no adjust', (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P16', contrast: 0 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","profile":"P16","build":"' + B16 + '"}', await rec());
  await setAdj(100, 100, 150); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   at 2025 with Background lightness 150: entries with spec 2025 and the adjust; the record has the spec, then the adjust', (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P16', contrast: 0, adjust: { accentSat: 100, bgSat: 100, bgLight: 150 } })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","adjust":{"accentSat":100,"bgSat":100,"bgLight":150},"profile":"P16","build":"' + B16 + '"}', await rec());
  // Pure black wins where both name a resource
  await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_black', true); ovlRender(); }); await setAdj(100, 100, 200); a = await adj(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('D5. Pure black on with Background lightness 200 (Tonal Spot at 2021, P16): the hint names both; 177 entries, no resource twice; neutral1_900, neutral2_900, background_dark and surface_dark black (0xFF211F24 without it), the dark containers grey', a.hint === HINT.listBlack && plan.entries && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177 && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818', JSON.stringify([a.hint, r.surface_dark, r.surface_container_dark]));
  check('   the rest is the adjusted Tonal Spot (oracle: neutral1_500 0xFF938F96, surface_light 0xFFFFFFFF, on_surface_light 0xFF322F35); the record: black, Tonal Spot, the sliders', pinsOff(plan, { neutral1_500: '0xFF938F96', surface_light: '0xFFFFFFFF', on_surface_light: '0xFF322F35' }).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"TONAL_SPOT","adjust":{"accentSat":100,"bgSat":100,"bgLight":200},"profile":"P16","build":"' + B16 + '"}', JSON.stringify([pinsOff(plan, { neutral1_500: '0xFF938F96', surface_light: '0xFFFFFFFF', on_surface_light: '0xFF322F35' }), await rec()]));
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_applied', null); ovlRender(); });

  // ---- (e) the hint in every variant (P16; no overlay on the phone, so all at 100 with nothing on gives no hint) ----
  const hintFor = (list, spec, more, black) => ev(([list, spec, more, black]) => { ovlPickStyle(list); kvSet('ovl_spec', spec); kvSet('ovl_root_style', more); kvSet('ovl_black', black); ovlRender(); return (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText; }, [list, spec, more, black]);
  const VARIANTS = [
    ['TONAL_SPOT', '2021', '', HINT.list, HINT.listBlack, '', 'Also writes the pure black overlay with root.'],
    ['RAINBOW', '2025', '', HINT.list, HINT.listBlack, '', 'Also writes the pure black overlay with root.'],
    ['TONAL_SPOT', '2025', '', HINT.list25, HINT.list25Black, 'Also writes this style\'s 2025 colors with root, in place of Android\'s.', 'Also writes this style\'s 2025 colors and pure black with root, in place of Android\'s.'],
    ['VIBRANT', '2026', '', HINT.list25, HINT.list25Black, 'Also writes this style\'s 2025 colors with root, in place of Android\'s.', 'Also writes this style\'s 2025 colors and pure black with root, in place of Android\'s.'],
    ['TONAL_SPOT', '2021', 'FIDELITY', HINT.more('Fidelity'), HINT.moreBlack('Fidelity'), 'Also writes the Fidelity colors with root, in place of the style above.', 'Also writes the Fidelity colors and pure black with root, in place of the style above.'],
    ['TONAL_SPOT', '2025', 'CONTENT', HINT.more('Content'), HINT.moreBlack('Content'), 'Also writes the Content colors with root, in place of the style above.', 'Also writes the Content colors and pure black with root, in place of the style above.'],
    ['VIBRANT', '2021', 'MONOCHROMATIC', HINT.more('Monochrome'), HINT.moreBlack('Monochrome'), 'Also writes the Monochrome colors with root, in place of the style above.', 'Also writes the Monochrome colors and pure black with root, in place of the style above.'],
    ['TONAL_SPOT', '2026', 'CMF', HINT.more('CMF'), HINT.moreBlack('CMF'), 'Also writes the CMF colors with root, in place of the style above.', 'Also writes the CMF colors and pure black with root, in place of the style above.']
  ];
  for (const [list, spec, more, adjusted, adjustedBlack, plainHint, plainBlack] of VARIANTS) {
    await setAdj(100, 70, 100);
    const got = [await hintFor(list, spec, more, false), await hintFor(list, spec, more, true)];
    await setAdj(100, 100, 100);
    const was = [await hintFor(list, spec, more, false), await hintFor(list, spec, more, true)];
    check('E. ' + list + ' at ' + spec + (more ? ', ' + more + ' in More styles' : '') + ': with a slider off 100 the hint says the colors are adjusted (with and without Pure black); at 100 it is the one from before the sliders', got[0] === adjusted && got[1] === adjustedBlack && was[0] === plainHint && was[1] === plainBlack, JSON.stringify([got, was]));
  }
  await ev(() => { ovlPickStyle('TONAL_SPOT'); kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', ''); kvSet('ovl_black', false); ovlRender(); });

  // ---- (f) the record ----
  const reads = await ev(id => {
    const out = [];
    for (const x of [{ style: 'FIDELITY' }, { style: 'TONAL_SPOT', adjust: { accentSat: 150, bgSat: 50, bgLight: 130 } }, { style: 'TONAL_SPOT', adjust: { accentSat: -5, bgSat: 250, bgLight: 'x' } }, { style: 'TONAL_SPOT', adjust: 'loud' }, { black: true, style: '', adjust: { accentSat: 150, bgSat: 150, bgLight: 150 } }]) {
      kvSet('ovl_root_applied', Object.assign({ ids: [id], t: 1, black: false }, x)); const r = ovlRootApplied(); out.push([(r.adjust || {}).accentSat, (r.adjust || {}).bgSat, (r.adjust || {}).bgLight].join('/'));
    }
    kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: [id], t: 1 }); const r = ovlRootApplied(); out.push([(r.adjust || {}).accentSat, (r.adjust || {}).bgSat, (r.adjust || {}).bgLight].join('/')); kvSet('ovl_black_applied', null);
    return out;
  }, AAM);
  check('F1. records: one from before the sliders reads as 100 / 100 / 100 (and so do PR 2\'s pure black one and one without a style); the sliders are read back; a value outside 0..200 or not a number counts as 100', JSON.stringify(reads) === JSON.stringify(['100/100/100', '150/50/130', '100/100/100', '100/100/100', '100/100/100', '100/100/100']), JSON.stringify(reads));
  // the note for colours written on another build, with an adjusted record
  await setAdj(150, 100, 100);
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'TONAL_SPOT', adjust: { accentSat: 150, bgSat: 100, bgLight: 100 }, profile: 'P15Q2', build: b }); }, [AAM, B16]);
  await reset(); await phone({ sdk: 36, profile: 'P16', build: B16b }); a = await adj(); let stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('F2. Tonal Spot at 2021 written adjusted on the old build (15 QPR2), the sliders still off 100, the phone on another build and profile (16.0): the note says Android was updated; nothing is written by itself', stale === STALE && a.hint === HINT.list && (await calls()).fab.length === 0, JSON.stringify([stale, a.hint]));
  await setAdj(100, 100, 100); a = await adj(); stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('   the sliders back at 100 (Apply would leave Tonal Spot to Android and switch the overlay off): no such note; the hint says Apply removes it', stale === '' && a.hint === 'Also removes the theme overlay.', JSON.stringify([stale, a.hint]));
  await setAdj(150, 100, 100); await reset(); await apply(); stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('   off 100 again and Apply: the colours are written for this build, the record keeps the sliders and the new build, the note goes', (await calls()).fab.length === 1 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"profile":"P16","build":"' + B16b + '"}' && stale === '', JSON.stringify([await rec(), stale]));

  // ---- (g) Reset ----
  await setAdj(150, 50, 130); await ev(() => { window.__bc.length = 0; });
  if (await page.$('#ovlAdjReset')) { await page.focus('#ovlAdjReset'); await page.keyboard.press('Enter'); } await sleep(100); a = await adj();
  const saves = await ev(() => window.__bc.filter(c => c[0] === 'saveSetting').map(c => c.slice(1).join('=')));
  check('G1. Reset (Enter on it): the three back to 100 % (three saveSetting calls, nothing else written), Reset disabled, the preview gone; the hint now says Apply removes the overlay written in F2', JSON.stringify(saves) === JSON.stringify(['ovl_adj_accent_sat=100', 'ovl_adj_bg_sat=100', 'ovl_adj_bg_light=100']) && fresh(a.sliders) && (a.reset || {}).disabled && a.hint === 'Also removes the theme overlay.' && !a.preview, JSON.stringify([saves, a.sliders.map(x => x.value)]));
  check('   the focus, on Reset (now disabled), went to the first slider', (await ev(() => document.activeElement && document.activeElement.id)) === IDS[0], await ev(() => document.activeElement && document.activeElement.id));
  check('   Apply with nothing else on switches the overlay written in F2 off (a disable, no fabricate)', await (async () => { await reset(); await apply(); const k = await calls(); return k.fab.length === 1 && k.fab[0].action === 'disable' && (await rec()) === 'null'; })());

  // ---- (h) keyboard and aria-valuetext ----
  if (await page.$('#' + IDS[2])) await page.focus('#' + IDS[2]);
  const key = async kname => { await page.keyboard.press(kname); await sleep(40); const s = sl(await adj(), 2); return s.value + '/' + s.text + '/' + s.shown + '/' + (await kv(KV[2])); };
  const keys = [await key('ArrowRight'), await key('End'), await key('ArrowUp'), await key('Home'), await key('ArrowLeft'), await key('ArrowRight')];
  check('H1. Background lightness by keyboard: ArrowRight 105, End 200, ArrowUp stays 200, Home 0, ArrowLeft stays 0, ArrowRight 5; aria-valuetext and the value shown follow, and each key stores the value', JSON.stringify(keys) === JSON.stringify(['105/105%/105%/105', '200/200%/200%/200', '200/200%/200%/200', '0/0%/0%/0', '0/0%/0%/0', '5/5%/5%/5']), JSON.stringify(keys));
  check('   each slider has one label (its name) and the group is labelled Intensity', (await ev(ids => ids.every(id => { const el = document.getElementById(id); return !!el && el.labels.length === 1 && el.labels[0].htmlFor === id; }), IDS)) && ((await adj()).group || {}).label === 'Intensity');
  await setAdj(null, null, null);

  // ---- (i) the maths through the page's MonetPlan ----
  const id100 = await ev(() => {
    const STY = [['TONAL_SPOT', ['2021', '2025', '2026']], ['VIBRANT', ['2021', '2025']], ['EXPRESSIVE', ['2021', '2026']], ['RAINBOW', ['2021']], ['FRUIT_SALAD', ['2021']], ['SPRITZ', ['2021', '2025']], ['FIDELITY', ['2021']], ['CONTENT', ['2021']], ['MONOCHROMATIC', ['2021']], ['CMF', ['2026']]];
    let n = 0; const off = [];
    for (const profile of ['P14', 'P16']) for (const [style, specs] of STY) for (const spec of specs) for (const dark of [false, true]) {
      const o = { spec, dark, profile, contrast: 0 }, e = JSON.stringify(MonetPlan.entries(style, '6750A4', o)), p = JSON.stringify(MonetPlan.palettes(style, '6750A4', o));
      for (const adjust of [{ accentSat: 100, bgSat: 100, bgLight: 100 }, {}, null]) {
        n++;
        const oa = Object.assign({ adjust }, o);
        if (JSON.stringify(MonetPlan.entries(style, '6750A4', oa)) !== e || JSON.stringify(MonetPlan.palettes(style, '6750A4', oa)) !== p) off.push([profile, style, spec, dark, JSON.stringify(adjust)].join(' '));
      }
    }
    return { n, off };
  });
  check('I1. identity: with adjust 100 / 100 / 100, {} or null, entries and palettes are byte for byte the ones without, for every style and spec on P14 and P16, light and dark (' + id100.n + ' cases)', id100.n === 180 && id100.off.length === 0, JSON.stringify(id100.off.slice(0, 5)));
  const zero = await ev(() => {
    const grey = h => h.slice(0, 2) === h.slice(2, 4) && h.slice(2, 4) === h.slice(4, 6), out = [];
    for (const profile of ['P14', 'P16']) for (const style of ['TONAL_SPOT', 'VIBRANT', 'FIDELITY', 'CONTENT']) {
      const b = MonetPlan.palettes(style, '6750A4', { profile }), a0 = MonetPlan.palettes(style, '6750A4', { profile, adjust: { accentSat: 0 } }), b0 = MonetPlan.palettes(style, '6750A4', { profile, adjust: { bgSat: 0 } });
      const acc = ['accent1', 'accent2', 'accent3'], neu = ['neutral1', 'neutral2'], same = (x, y, rows) => rows.every(r => JSON.stringify(x[r]) === JSON.stringify(y[r]));
      out.push({ k: profile + ' ' + style, accGrey: acc.every(r => a0[r].chroma < 1 && a0[r].shades.every(grey)), accColour: acc.some(r => !b[r].shades.slice(1, 12).every(grey)), neuKept: same(a0, b, neu), neuGrey: neu.every(r => b0[r].chroma < 1 && b0[r].shades.every(grey)), neuColour: neu.some(r => !b[r].shades.slice(1, 12).every(grey)), accKept: same(b0, b, acc) });
    }
    return out;
  });
  check('I2. Accent saturation 0: the three accent palettes have chroma 0 and all 39 accent shades are greys (R = G = B), the neutral rows unchanged; Background saturation 0: the same for the neutral rows, the accents unchanged (Tonal Spot, Vibrant, Fidelity and Content on P14 and P16; each with colour at 100)', zero.every(z => z.accGrey && z.neuKept && z.neuGrey && z.accKept && z.accColour && z.neuColour), JSON.stringify(zero.filter(z => !(z.accGrey && z.neuKept && z.neuGrey && z.accKept && z.accColour && z.neuColour))));
  const light = await ev(() => {
    const SH = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000], out = {};
    const d = t => Math.min(1, t / 10, (100 - t) / 10), f = (t, v) => Math.min(100, Math.max(0, t + 0.1 * (v - 100) * d(t)));
    for (const profile of ['P14', 'P16']) {
      const toneOf = s => profile === 'P14' ? (s === 500 ? 49.6 : 100 - s / 10) : Math.trunc((1000 - s) / 10);
      const b = MonetPlan.palettes('TONAL_SPOT', '6750A4', { profile });
      for (const v of [0, 200]) {
        const a = MonetPlan.palettes('TONAL_SPOT', '6750A4', { profile, adjust: { bgLight: v } });
        let worst = 0; const moved = {};
        for (const row of ['neutral1', 'neutral2']) SH.forEach((s, i) => {
          const got = MonetPlan.hct(a[row].shades[i]).tone - MonetPlan.hct(b[row].shades[i]).tone, want = f(toneOf(s), v) - toneOf(s);
          worst = Math.max(worst, Math.abs(got - want));
          if (row === 'neutral1') moved[s] = Math.round(got * 100) / 100;
        });
        out[profile + ' ' + v] = { worst: Math.round(worst * 100) / 100, moved, ends: [a.neutral1.shades[0], a.neutral1.shades[12], a.neutral2.shades[0], a.neutral2.shades[12]].join(' '), accentsKept: ['accent1', 'accent2', 'accent3'].every(r => JSON.stringify(a[r]) === JSON.stringify(b[r])) };
      }
    }
    return out;
  });
  const lightOk = Object.values(light).every(x => x.worst <= 0.35 && x.ends === 'FFFFFF 000000 FFFFFF 000000' && x.accentsKept);
  check('I3. Background lightness 0 and 200 (Tonal Spot 6750A4, P14 and P16): each neutral shade\'s measured tone moves by f(t) - t = ±10·d(t) of its requested tone t (within 0.35), shade 0 stays #FFFFFF and 1000 #000000, the accents are unchanged', lightOk, JSON.stringify(Object.entries(light).map(([k, x]) => k + ' worst ' + x.worst + ' ends ' + x.ends)));
  const p16l0 = light['P16 0'].moved, p14l0 = light['P14 0'].moved;
  check('   at 0, shade 10 (tone 99) moves a tenth as far as the middle shades: P16 ' + p16l0[10] + ' against ' + p16l0[500] + ' at 500 and ' + p16l0[300] + ' at 300; P14 ' + p14l0[10] + ' against ' + p14l0[500], [p16l0, p14l0].every(m => Math.abs(m[10] + 1) <= 0.35 && Math.abs(m[500] + 10) <= 0.35 && Math.abs(m[300] + 10) <= 0.35 && Math.abs(m[50] + 5) <= 0.35), JSON.stringify([p16l0, p14l0]));
  const mono = await ev(() => {
    const out = [];
    for (const profile of ['P14', 'P16']) for (const dark of [false, true]) {
      const o = { spec: '2021', dark, profile, contrast: 0 }, base = JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', o));
      const sat = [{ accentSat: 0, bgSat: 200 }, { accentSat: 200, bgSat: 0 }].every(adjust => JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', Object.assign({ adjust }, o))) === base);
      out.push(sat && JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', Object.assign({ adjust: { bgLight: 150 } }, o))) !== base);
    }
    return out;
  });
  check('I4. Monochrome: both saturation sliders (0 and 200) leave all its entries as they are, on P14 and P16, light and dark (Background lightness does move them)', mono.every(Boolean), JSON.stringify(mono));
  const edge = await ev(() => { const j = o => JSON.stringify(MonetPlan.entries('TONAL_SPOT', '6750A4', { profile: 'P16', adjust: o })); let thrown = ''; try { MonetPlan.entries('TONAL_SPOT', '6750A4', { adjust: { accentSat: 'x' } }); } catch (e) { thrown = e.message; } return { thrown, high: j({ accentSat: 250 }) === j({ accentSat: 200 }), low: j({ bgLight: -20 }) === j({ bgLight: 0 }) }; });
  check('I5. a value that is not a number throws; one outside 0..200 is held to it (250 is 200, -20 is 0)', edge.thrown !== '' && edge.high && edge.low, JSON.stringify(edge));
  for (const [profile, style, seed, spec, adjust, pins] of PINS) {
    const off = await ev(([profile, style, seed, spec, adjust, pins]) => {
      const out = [];
      for (const dark of [false, true]) {
        const have = Object.fromEntries(MonetPlan.entries(style, seed, { spec, dark, profile, contrast: 0, adjust }).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
        Object.keys(pins).forEach(k => { if (have[k] !== pins[k]) out.push((dark ? 'dark ' : 'light ') + k + ' ' + have[k] + ' != ' + pins[k]); });
      }
      return out;
    }, [profile, style, seed, spec, adjust, pins]);
    check('I6. ' + profile + ' ' + style + ' ' + seed + (spec === '2021' ? '' : ' at ' + spec) + ' ' + JSON.stringify(adjust) + ': ' + Object.keys(pins).length + (Object.keys(pins).length === 1 ? ' value' : ' values') + ' pinned from the oracle, light and dark mode', off.length === 0, off.join('; '));
  }

  // ---- (j) the new words in German (assets/lang/de.js) ----
  await setAdj(150, 100, 100); await ev(() => { ovlPickStyle('TONAL_SPOT'); kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_black', false); });
  await ev(() => I18N.set('de')); await sleep(600); await phone({ sdk: 36, profile: 'P16', build: B16b }); await sleep(200); a = await adj();
  const de = await ev(words => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return words.map(w => x[w] || ''); }, ['Intensity', SUB, 'Accent saturation', 'Background saturation', 'Background lightness', 'Reset', 'Set all three to 100%', HINT.list]);
  const pct = await ev(() => new Intl.NumberFormat('de', { style: 'percent', maximumFractionDigits: 0 }).format(1.5));
  check('J1. in German: the row, its line, the three labels, Reset and its title, and the hint read as assets/lang/de.js has them; the values are written the German way (' + pct + ')', de.every(t => t !== '') && de[0] !== 'Intensity' && a.name === de[0] + ' ' + de[1] && a.sliders.map(s => s.label).join('|') === de.slice(2, 5).join('|') && (a.reset || {}).text === de[5] && (a.reset || {}).title === de[6] && a.hint === de[7] && sl(a, 0).shown === pct && sl(a, 0).text === pct, JSON.stringify([de, a.name, a.sliders.map(s => s.label + ' ' + s.shown), a.reset, a.hint]));
  await phone({ sdk: 36, profile: 'P16Q1', reason: 'notYet', build: B16b }); a = await adj();
  check('   locked there: the row keeps its German name and line', a.locked && a.name === de[0] + ' ' + de[1], a.name);
  // drawn while German is on (live again: the sliders are drawn anew), then the language switched: the next render puts the row's name, its
  // line and the group's label in the new language (the sliders stay drawn; their labels, Reset and the hint follow as before)
  await phone({ sdk: 36, profile: 'P16', build: B16b }); a = await adj();
  const drawnDe = a.name === de[0] + ' ' + de[1] && (a.group || {}).label === de[0] && a.sliders.length === 3;
  await ev(() => I18N.set('en')); await sleep(200); await ev(() => ovlRender()); await sleep(100);
  const en = await adj();
  await ev(() => I18N.set('fr')); await sleep(400); await ev(() => ovlRender()); await sleep(100);
  const fra = await adj();
  const fr = await ev(words => { const x = (window.__LANGS.fr && window.__LANGS.fr.x) || {}; return words.map(w => x[w] || ''); }, ['Intensity', SUB, 'Accent saturation', 'Background saturation', 'Background lightness', 'Reset', HINT.list]);
  check('J2. drawn in German, then English and French with a render after each: the row\'s name and line and the group\'s label follow (as do the labels, Reset and the hint)', drawnDe && en.name === 'Intensity ' + SUB && (en.group || {}).label === 'Intensity' && en.sliders.map(s => s.label).join('|') === 'Accent saturation|Background saturation|Background lightness' && (en.reset || {}).text === 'Reset' && en.hint === HINT.list
    && fr.every(t => t !== '') && fra.name === fr[0] + ' ' + fr[1] && (fra.group || {}).label === fr[0] && fra.sliders.map(s => s.label).join('|') === fr.slice(2, 5).join('|') && (fra.reset || {}).text === fr[5] && fra.hint === fr[6], JSON.stringify([a.name, (a.group || {}).label, en.name, (en.group || {}).label, fra.name, (fra.group || {}).label, fr[0]]));
  await ev(() => I18N.set('en')); await sleep(100);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
