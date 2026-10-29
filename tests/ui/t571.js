// Root colours on Android 16 QPR1 and QPR2 (T-049 PR 9: profiles P16Q1 and P16Q2): the Overlays tab's root-only theme controls are live
// there and write what SystemUI writes on those builds. (a) Live on P16Q1 and P16Q2 (MonetProfile.java reports them ok): every row can be
// used; the Pure black and Color spec lines are their QPR ones (light mode untouched; 2025 is the phone's own spec, 2021 recolors every
// style); with no spec stored, 2025 is the one chosen (2021 on Android 16.0, as before). Still locked: 15.0 as an APK from before PR 11
// reports it (notYet; t573 has it live), 17 as an APK from before PR 10
// reports it (notYet; t572 has it live), an Android this app does not know, QPR1 and QPR2 as an APK from before PR 9 reports them
// (notYet). A One UI phone on a QPR2 build is live, with the One UI note (PR 13). (b) Native routing: the list style at
// the phone's spec (2025, and 2026, which gives every list style 2025 there) with nothing else set is the theme setting only (no overlay,
// no hint, no preview), Rainbow included; the spec note is the QPR one, without the light / dark sentence. (c) Fidelity (More styles) at
// 2025 on P16Q2 and P16Q1: 252 entries, names and order equal to the list below made by hand from SystemUI's write order (TOC qpr2
// createOverlays / assignColorsToOverlay: accent1-3 and neutral1-2, 13 shades each, _light then _dark; the 55 roles sorted by name, _light
// then _dark; the 12 fixed colours sorted by name, unsuffixed), no unsuffixed palette alias, no error shade, nothing else (no custom
// colour); values pinned from the libmonet oracle at contrast 0 and 0.5, light and dark in the same plan; QPR1's on_surface_variant_dark
// (curve 4.5) is not QPR2's (curve 6); the light / dark mode at Apply changes nothing in the plan. Content (More styles) on both. (d) Spec
// 2021 on QPR: the list style is written (its 2021 colours over Android's 2025 ones, with its own hint sentences), 244 entries: the four
// *_dim roles, which 2021 does not define, are left out; values pinned at contrast 0 and 0.5. (e) Pure black on QPR writes only _dark
// names (system_neutral1_900_dark and system_neutral2_900_dark, the nine dark roles), alone and over a style; the _light values stay the
// light scheme's. (f) A list style at 2025 with an accent (Tonal Spot FFDE3F): the _light shades come from the light scheme and the _dark
// shades from the dark one (they differ at 2025); the preview shows the mode the phone is in; Vibrant FFDE3F, whose yellow palettes take
// tone 99 without averaging; the accent picker starts at the key colour of the phone's own spec. (g) The record (profile, build) and the note
// for colours written on another build: a QPR1 record on QPR2, a 16.0 record on QPR2, a monthly update, a mode change (no note). (h) Undo
// and Default with a QPR overlay, an Undo back to a 2021 write (no spec in its record), the resume path after a restart. (i) The new words in German.
// Every hex value pinned below comes from an oracle kept outside the repository and not from this app's code: each branch's SystemUI
// DynamicColors.java and TonalPalette.java (frameworks/libs/systemui monet, android16-qpr1-release / android16-qpr2-release) compiled on
// that branch's external/libmonet, with ThemeOverlayController's createOverlays / assignColorsToOverlay transcribed (pr9-oracle Q16Dump);
// every role value among them was also checked against a second dump of the same libmonet (LibmonetDump, every MaterialDynamicColors
// method). Spec "2025" here is the phone's own (the schemes' 3-argument constructor: SPEC_2025); FIDELITY, which SystemUI never builds, is
// libmonet's SchemeFidelity with the same write sequence.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const NOT_YET = 'Not supported on this Android version yet';
// PR 13: the note the root theme card shows on One UI only (One UI no longer locks the card)
const ONE_UI_NOTE = 'On One UI these colors reach Google apps and other apps that use Android\'s Monet colors. Samsung\'s own apps may keep Samsung\'s palette, and changing the Color palette in Samsung\'s settings can replace them; apply again if it does.';
const STALE = 'Android was updated after these colors were written. Tap Apply theme to write them for this version.';
const AAM = 'com.android.shell:aam_monet';
const RES = 'android:color/system_';
// the lines the card shows on QPR, and the ones it shows elsewhere
const BLACK_SUB_QPR = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors.';
const BLACK_SUB = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors, except that its darkest text becomes pure black.';
const SPEC_SUB_QPR = 'With root, 2021 recolors every style. CMF needs 2026.';
const SPEC_SUB = 'Recolors Tonal Spot, Vibrant, Expressive and Spritz with root. CMF needs 2026.';
const SPEC_NOTE_QPR = 'Android computes these colors itself: a style of the list above is written with root only at 2021, or with Intensity, an accent or Accurate shades off.';
const SPEC_NOTE = 'Rainbow, Fruit Salad, Fidelity, Content and Monochrome keep their 2021 colors. The base shades follow light or dark mode as the phone is when you tap Apply; after switching, tap Apply again.';
const SPEC_DESC_QPR = ['The colors earlier Android versions compute.', 'The colors Android computes itself.', 'The 2025 colors, plus the CMF style.'];
// builds (Build.FINGERPRINT) the mock reports
const B16 = 'google/mock/mock:16/BP2A.250605.031/13578712:user/release-keys';
const BQ1 = 'google/mock/mock:16/BP3A.250905.014/14053842:user/release-keys';
const BQ2 = 'google/mock/mock:16/BP4A.251205.006/14401865:user/release-keys';
const BQ2b = 'google/mock/mock:16/BP4A.260105.004/14614732:user/release-keys';
// SystemUI's write order on 16 QPR1 and QPR2, made by hand from ThemeOverlayController (qpr2 :689-725) and SystemUI's DynamicColors:
// the accent overlay (accent1, accent2, accent3), the neutral overlay (neutral1, neutral2), each palette's 13 shades in order, each shade
// _light (light scheme) then _dark (dark scheme); then the 55 roles (getAllDynamicColorsMapped: libmonet's 67 colours less the 12 fixed
// ones, sorted by name), each _light then _dark; then the 12 fixed colours (getFixedColorsMapped, sorted by name), unsuffixed
const PAL_ROWS = ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2'];
const SHADES = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];
const ROLES55 = ['background', 'control_activated', 'control_highlight', 'control_normal', 'error', 'error_container', 'error_dim', 'inverse_on_surface', 'inverse_primary', 'inverse_surface',
  'on_background', 'on_error', 'on_error_container', 'on_primary', 'on_primary_container', 'on_secondary', 'on_secondary_container', 'on_surface', 'on_surface_variant', 'on_tertiary',
  'on_tertiary_container', 'outline', 'outline_variant', 'palette_key_color_error', 'palette_key_color_neutral', 'palette_key_color_neutral_variant', 'palette_key_color_primary',
  'palette_key_color_secondary', 'palette_key_color_tertiary', 'primary', 'primary_container', 'primary_dim', 'scrim', 'secondary', 'secondary_container', 'secondary_dim', 'shadow',
  'surface', 'surface_bright', 'surface_container', 'surface_container_high', 'surface_container_highest', 'surface_container_low', 'surface_container_lowest', 'surface_dim',
  'surface_tint', 'surface_variant', 'tertiary', 'tertiary_container', 'tertiary_dim', 'text_hint_inverse', 'text_primary_inverse', 'text_primary_inverse_disable_only',
  'text_secondary_and_tertiary_inverse', 'text_secondary_and_tertiary_inverse_disabled'];
const DIMS = ['primary_dim', 'secondary_dim', 'tertiary_dim', 'error_dim'];
const FIXED = ['on_primary_fixed', 'on_primary_fixed_variant', 'on_secondary_fixed', 'on_secondary_fixed_variant', 'on_tertiary_fixed', 'on_tertiary_fixed_variant', 'primary_fixed', 'primary_fixed_dim', 'secondary_fixed', 'secondary_fixed_dim', 'tertiary_fixed', 'tertiary_fixed_dim'];
const QPR_ORDER = PAL_ROWS.map(r => SHADES.map(s => [r + '_' + s + '_light', r + '_' + s + '_dark']).flat()).flat()
  .concat(ROLES55.map(r => [r + '_light', r + '_dark']).flat(), FIXED).map(n => RES + n);
// at spec 2021: the same without the four dim roles (libmonet's ColorSpec2021 has none)
const QPR_ORDER_2021 = QPR_ORDER.filter(r => !DIMS.some(d => r === RES + d + '_light' || r === RES + d + '_dark'));
// Oracle pins (resource suffix: value). Q2: android16-qpr2-release; Q1: android16-qpr1-release. FID: FIDELITY 6750A4 at the phone's spec
// (2025); TS21: TONAL_SPOT 6750A4 at spec 2021; CON: CONTENT B5651D at 2025; TSY: TONAL_SPOT FFDE3F at 2025
const Q2_FID_0 = { accent1_100_light: '0xFFE9DDFF', accent1_500_dark: '0xFF8069BF', accent2_500_light: '0xFF7C7296', accent3_500_dark: '0xFF91731D', neutral1_900_light: '0xFF1D1B20', neutral2_500_dark: '0xFF7A7582',
  primary_light: '0xFF6750A5', primary_dark: '0xFFBBA2FD', surface_light: '0xFFFDF7FF', surface_dark: '0xFF0F0D13', on_surface_variant_light: '0xFF615E64', on_surface_variant_dark: '0xFFAEA9B1',
  primary_container_dark: '0xFFAB92EC', outline_dark: '0xFF78747B', control_activated_light: '0xFFBBA2FD', control_normal_dark: '0xFFAEA9B1', text_primary_inverse_light: '0xFFA09BA3',
  on_background_dark: '0xFFE9E3EB', primary_dim_dark: '0xFFAD95EF', primary_fixed: '0xFFBBA2FD', on_primary_fixed: '0xFF1E0053', secondary_fixed_dim: '0xFFDBCEF7' };
const Q2_FID_05 = { primary_light: '0xFF4B3386', primary_dark: '0xFFC3ACFF', on_surface_variant_light: '0xFF444248', on_surface_variant_dark: '0xFFBCB7BF', outline_dark: '0xFF969299',
  control_activated_light: '0xFF7E66BC', text_primary_inverse_light: '0xFFC8C3CB', on_background_dark: '0xFFFFFFFF', primary_dim_dark: '0xFFB59DF7', primary_fixed: '0xFF7E66BC',
  on_primary_fixed: '0xFFFFFFFF', secondary_fixed_dim: '0xFF6D6386', accent1_500_dark: '0xFF8069BF' };
const Q1_FID_0 = { accent1_500_dark: '0xFF8069BF', primary_light: '0xFF6750A5', primary_dark: '0xFFBBA2FD', surface_dark: '0xFF0F0D13', on_surface_variant_light: '0xFF615E64',
  on_surface_variant_dark: '0xFF969299', control_normal_dark: '0xFF969299', control_activated_light: '0xFFBBA2FD', primary_dim_dark: '0xFFAD95EF', primary_fixed: '0xFFBBA2FD', on_primary_fixed: '0xFF1E0053' };
const Q1_FID_05 = { primary_light: '0xFF4B3386', primary_dark: '0xFFC3ACFF', on_surface_variant_dark: '0xFFBCB7BF', outline_dark: '0xFF969299', primary_fixed: '0xFF7E66BC', on_primary_fixed: '0xFFFFFFFF' };
const Q2_TS21_0 = { accent1_100_light: '0xFFE9DDFF', accent1_500_light: '0xFF7E6EA9', accent1_500_dark: '0xFF7E6EA9', accent3_500_dark: '0xFF996A79', neutral2_500_dark: '0xFF7A757F',
  primary_light: '0xFF65558F', primary_dark: '0xFFCFBDFE', surface_dark: '0xFF141218', on_surface_variant_dark: '0xFFCAC4CF', primary_container_dark: '0xFF4D3D75', outline_dark: '0xFF948F99',
  control_activated_light: '0xFFE9DDFF', text_primary_inverse_light: '0xFFE6E0E9', primary_fixed: '0xFFE9DDFF', on_primary_fixed: '0xFF201047', secondary_fixed_dim: '0xFFCBC2DB',
  surface_container_dark: '0xFF211F24' };
const Q2_TS21_05 = { primary_light: '0xFF3C2D63', primary_dark: '0xFFE3D6FF', on_surface_variant_light: '0xFF38353D', on_surface_variant_dark: '0xFFE0DAE5', primary_container_dark: '0xFF9887C5',
  outline_dark: '0xFFB5B0BB', primary_fixed: '0xFF74649F', on_primary_fixed: '0xFFFFFFFF', secondary_fixed_dim: '0xFF585267', surface_container_dark: '0xFF29272D' };
const Q1_TS21_0 = { primary_light: '0xFF65558F', primary_dark: '0xFFCFBDFE', on_surface_variant_dark: '0xFFCAC4CF', control_normal_dark: '0xFFCAC4CF', primary_fixed: '0xFFE9DDFF' };
const Q1_TS21_05 = { primary_light: '0xFF3C2D63', primary_dark: '0xFFE3D6FF', on_surface_variant_light: '0xFF38353D', on_surface_variant_dark: '0xFFE0DAE5', primary_container_dark: '0xFF9887C5',
  outline_dark: '0xFFB5B0BB', control_normal_dark: '0xFFCAC4CF', primary_fixed: '0xFF74649F', on_primary_fixed: '0xFFFFFFFF', secondary_fixed_dim: '0xFF585267', surface_container_dark: '0xFF29272D' };
const Q2_CON_0 = { accent1_500_light: '0xFFB2631B', accent3_500_dark: '0xFF767D0E', primary_light: '0xFF944C00', primary_dark: '0xFFFB9D51', surface_dark: '0xFF140D08',
  on_surface_variant_dark: '0xFFB7A8A0', primary_dim_dark: '0xFFEB9046', primary_fixed: '0xFFFB9D51', on_primary_fixed: '0xFF2F1400' };
const Q1_CON_0 = { on_surface_variant_dark: '0xFF9E9189', control_normal_dark: '0xFF9E9189', primary_light: '0xFF944C00' };
const Q2_TSY_0 = { accent1_100_light: '0xFFF6E292', accent1_100_dark: '0xFFF3E2A2', accent1_500_light: '0xFF867732', accent1_500_dark: '0xFF847741', neutral1_900_light: '0xFF1E1B15',
  primary_light: '0xFF6D5F1C', primary_dark: '0xFFD6C689', surface_light: '0xFFFFF9EE', surface_dark: '0xFF100E08', on_surface_variant_dark: '0xFFB1AB99' };
// TVY: VIBRANT FFDE3F at 2025, contrast 0 (qpr2): its tertiary (hue 122.2) and both neutral palettes (hue 108.2) are yellow, and libmonet's
// getHct gives their tone 99 (shade 10, and the roles at tone 99) without upstream's averaging of tones 98 and 100 (that would give
// 0xFFFFFDED for the neutrals and 0xFFFAFFE3 for the tertiary); the roles from the golden's R rows, the shades Hct.from(hue, chroma, 99) on
// its P rows (the secondary accent of (f) touches accent2 and the secondary roles only, so none of these)
const Q2_TVY_0 = { background_light: '0xFFFFFBFF', surface_light: '0xFFFFFBFF', surface_bright_light: '0xFFFFFBFF', tertiary_container_light: '0xFFD8F56F', on_surface_dark: '0xFFFCF6B1',
  neutral1_10_light: '0xFFFFFBFF', neutral1_10_dark: '0xFFFFFBFF', neutral2_10_light: '0xFFFFFBFF', accent3_10_light: '0xFFFBFFE0', accent3_10_dark: '0xFFFBFFE0', accent3_500_light: '0xFF698000' };
// Pure black's 11 entries on QPR: _dark names only
const BLACK_QPR = ['neutral1_900_dark', 'neutral2_900_dark', 'background_dark', 'surface_dark', 'surface_dim_dark', 'surface_container_lowest_dark', 'surface_container_low_dark',
  'surface_container_dark', 'surface_container_high_dark', 'surface_container_highest_dark', 'surface_bright_dark'];
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !m.text().includes('Failed to load resource: net::ERR_FILE_NOT_FOUND')) errors.push('console: ' + m.text()); });
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  // the phone: { sdk, profile, reason, build, contrast } (as t567: reason left out follows the profile as ovl_mock says, ok for P16Q1/P16Q2)
  const phone = async o => {
    await ev(o => { window.__sdk = o.sdk; window.__profile = o.profile; window.__profileReason = o.reason; window.__build = o.build === undefined ? null : o.build; window.__contrast = o.contrast === undefined ? 0 : o.contrast; ovlReadPalette(); ovlRender(); }, o);
    await sleep(150);
  };
  const card = () => ev(() => {
    const c = document.getElementById('ovlRootCard');
    const row = id => { const box = document.getElementById(id), r = box && box.querySelector('.rf-row'), lock = box && box.querySelector('.pm-lock'), sub = r && r.querySelector('.rf-name > span'); return { locked: !!(r && r.classList.contains('is-locked')), lock: lock ? lock.innerText : '', sub: sub ? sub.innerText : '' }; };
    const live = Array.from(c.querySelectorAll('button, input, select, textarea, a[href], [tabindex], [role="radio"], [role="switch"]')).filter(e => !e.disabled && e.getAttribute('tabindex') !== '-1');
    const stale = document.getElementById('ovlRootStale'), pv = document.getElementById('ovlRootPreview'), sn = document.getElementById('ovlRootSpecNote');
    const specs = Array.from(document.querySelectorAll('#ovlRootSpecs [role="radio"]')).map(e => ({ id: e.dataset.spec, on: e.getAttribute('aria-checked') === 'true', desc: (e.querySelector('.ovl-styletxt > span') || { innerText: '' }).innerText }));
    return { shown: c.style.display !== 'none', black: row('ovlRootCards'), spec: row('ovlRootSpec'), more: row('ovlRootMore'), live: live.filter(e => !e.classList.contains('ovl-chip')).map(e => e.id || (e.dataset && (e.dataset.style || e.dataset.spec)) || e.className),
      chips: live.filter(e => e.classList.contains('ovl-chip')).length, specs, specNote: sn ? sn.innerText : '',
      note: document.getElementById('ovlRootNote').innerText, stale: stale && stale.style.display !== 'none' ? stale.innerText : '', hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '' };
  });
  const lockedAll = (c, reason) => c.shown && ['black', 'spec', 'more'].every(k => c[k].locked && c[k].lock === reason) && c.live.length === 0 && c.chips === 0;
  const LIVE = '["ovlBlackSw","2021","2025","2026","OFF","FIDELITY","CONTENT","MONOCHROMATIC","ovlAdjAccentSat","ovlAdjBgSat","ovlAdjBgLight","ovlAccSecondary","ovlAccTertiary","ovlAccurateSw"]';
  const preview = () => ev(() => { const pv = document.getElementById('ovlRootPreview'); const rows = pv ? Array.from(pv.querySelectorAll('.ovl-pal')) : []; return { rows: rows.length, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] }; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const names = plan => ((plan && plan.entries) || []).map(e => e.resource);
  const sameOrder = (plan, order) => JSON.stringify(names(plan)) === JSON.stringify(order);
  // the forms the brief rules out on QPR: an unsuffixed palette alias (system_accent1_500), an error palette shade (system_error_500)
  const aliases = plan => names(plan).filter(r => /^android:color\/system_(accent[123]|neutral[12]|error)_\d+$/.test(r) || /^android:color\/system_error_\d+(_light|_dark)?$/.test(r));
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });

  // ---- (a) live on P16Q1 and P16Q2; the locks that stay ----
  await ev(() => switchView('overlays')); await sleep(300);
  await setRoot(true);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); kvSet('ovl_root_applied', null); window.__dark = true; });
  for (const [label, prof, build] of [['Android 16 QPR1 (P16Q1)', 'P16Q1', BQ1], ['Android 16 QPR2 (P16Q2)', 'P16Q2', BQ2]]) {
    await phone({ sdk: 36, profile: prof, build }); const c = await card(); const reason = await ev(() => ovlPalette.profileReason);
    check('A1. ' + label + ' with Super User (reported ok): every row is live (the two switches, the two radio groups, the three Intensity sliders, the two accent entries, and the preview\'s 65 swatches)', reason === 'ok' && c.shown && !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.preview, JSON.stringify([reason, c.live, c.chips]));
    check('   its lines: Pure black leaves light mode alone; Color spec says 2021 recolors every style; the three specs say 2021 is earlier Android\'s colors, 2025 the phone\'s own, 2026 the 2025 colors plus CMF; with no spec stored 2025 is chosen, and the note under it is the QPR one (no light / dark sentence); the hint names Fidelity', c.black.sub === BLACK_SUB_QPR && c.spec.sub === SPEC_SUB_QPR && JSON.stringify(c.specs.map(s => s.desc)) === JSON.stringify(SPEC_DESC_QPR) && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2025"]' && c.specNote === SPEC_NOTE_QPR && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify([c.black.sub, c.spec.sub, c.specs, c.specNote, c.hint]));
  }
  await phone({ sdk: 36, profile: 'P16', build: B16 }); let c = await card();
  check('   on Android 16.0 (P16) the lines are as before: Pure black\'s with light mode\'s darkest text, Color spec\'s four styles, 2021 chosen with no spec stored, no note under 2021', c.black.sub === BLACK_SUB && c.spec.sub === SPEC_SUB && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2021"]' && c.specNote === '' && c.specs[0].desc === 'The colors Android computes itself.', JSON.stringify([c.black.sub, c.spec.sub, c.specs, c.specNote]));
  await ev(() => { kvSet('ovl_spec', '2025'); ovlRender(); }); c = await card();
  check('   P16 at 2025: the note is the one from before (with the light / dark sentence)', c.specNote === SPEC_NOTE, c.specNote);
  await ev(() => { kvSet('ovl_spec', null); });
  const LOCKS = [
    ['15.0 as an APK from before PR 11 reports it (P15, notYet)', { sdk: 35, profile: 'P15', reason: 'notYet', build: B16 }, NOT_YET],
    ['Android 17 (P17)', { sdk: 37, profile: 'P17', reason: 'notYet', build: B16 }, NOT_YET],
    ['an Android this app does not know (SDK 38: unknown)', { sdk: 38, build: B16 }, NOT_YET],
    ['16 QPR1 as an APK from before PR 9 reports it (P16Q1, notYet)', { sdk: 36, profile: 'P16Q1', reason: 'notYet', build: BQ1 }, NOT_YET],
    ['16 QPR2 as an APK from before PR 9 reports it (P16Q2, notYet)', { sdk: 36, profile: 'P16Q2', reason: 'notYet', build: BQ2 }, NOT_YET]
  ];
  for (const [label, o, reason] of LOCKS) {
    await phone(o); c = await card(); await reset(); await apply(); const k = await calls();
    check('A2. ' + label + ': still locked with "' + reason + '", nothing in the card can be used, no hint, no preview; Apply writes the theme setting only', lockedAll(c, reason) && !c.preview && c.hint === '' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([c.black, c.live, k.fab.length]));
  }
  // PR 13: a One UI phone on this build is served by its Android version (the One UI lock is gone): live as any phone of the build, with the
  // One UI note; the same build without One UI has no note
  {
    const oneUiNote = () => ev(() => { const e = document.getElementById('ovlRootOneUi'); return e && e.style.display !== 'none' ? e.innerText : ''; });
    await ev(() => { window.__oneUi = '8.5'; kvSet('ovl_root_applied', null); }); await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); const o = await card(); const on = await oneUiNote(); await reset(); await apply(); const k = await calls();
    check('A2b. a One UI phone on Android 16 QPR2 (P16Q2) with Super User: every row is live, as on any phone of that build; the One UI note shows; Apply writes the overlay', !o.black.locked && !o.spec.locked && !o.more.locked && JSON.stringify(o.live) === LIVE && o.chips === 65 && on === ONE_UI_NOTE && k.fab.length === 1 && k.fab[0].action === 'fabricate', JSON.stringify([o.live, o.chips, on, k.fab.length]));
    await ev(() => { window.__oneUi = ''; kvSet('ovl_root_applied', null); }); await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); const off = await oneUiNote();
    check('   the same build without One UI: no One UI note', off === '', off);
  }
  await setRoot(false); await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); c = await card();
  check('A3. P16Q2 without Super User: Needs Super User (as on every profile this app computes), its QPR lines, the note about Root', lockedAll(c, 'Needs Super User') && c.black.sub === BLACK_SUB_QPR && c.spec.sub === SPEC_SUB_QPR && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await setRoot(true);

  // ---- (b) native routing: the list style at the phone's spec ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', null); ovlPickStyle('TONAL_SPOT'); ovlRender(); });
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); c = await card(); await reset(); await apply(); let k = await calls();
  check('B1. P16Q2, Tonal Spot in the list, no spec stored (2025, the phone\'s own), nothing else on: no hint, no preview; Apply writes the theme setting only (TONAL_SPOT), no overlay call', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0 && (await kv('ovl_root_applied')) === null, JSON.stringify([c.hint, c.preview, k.fab.length]));
  for (const [spec, style] of [['2025', 'TONAL_SPOT'], ['2026', 'VIBRANT'], ['2025', 'RAINBOW'], ['2026', 'FRUIT_SALAD'], ['2025', 'SPRITZ']]) {
    await ev(([s, st]) => { kvSet('ovl_spec', s); ovlPickStyle(st); ovlRender(); }, [spec, style]); c = await card(); await reset(); await apply(); k = await calls();
    check('   ' + style + ' at ' + spec + ' (MonetPlan.specFor with the profile gives ' + (await ev(st => MonetPlan.specFor(st, '2026', 'P16Q2'), style)) + '): native, the setting only, no overlay', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === style && k.fab.length === 0, JSON.stringify([c.hint, k.fab.length]));
  }
  await phone({ sdk: 36, profile: 'P16Q1', build: BQ1 }); await ev(() => { kvSet('ovl_spec', '2025'); ovlPickStyle('EXPRESSIVE'); ovlRender(); }); c = await card(); await reset(); await apply(); k = await calls();
  check('   P16Q1, Expressive at 2025: native as well', c.hint === '' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([c.hint, k.fab.length]));
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card();
  check('   on P16 (16.0) Tonal Spot at 2025 is still written (2021 is that phone\'s own): the hint says so', c.hint === 'Also writes this style\'s 2025 colors with root, in place of Android\'s.', c.hint);

  // ---- (c) Fidelity (More styles) at 2025 on P16Q2 and P16Q1 ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; });
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2, contrast: 0 }); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('C1. P16Q2, Fidelity 6750A4, dark mode, contrast 0: overlayFabricate with 252 entries in SystemUI\'s order (the list made by hand: accent1-3 and neutral1-2, each shade _light then _dark; the 55 roles sorted by name, _light then _dark; the 12 fixed colours unsuffixed); no resource twice', k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 252 && QPR_ORDER.length === 252 && new Set(names(plan)).size === 252 && sameOrder(plan, QPR_ORDER), JSON.stringify([plan.action, names(plan).length, names(plan).slice(0, 4)]));
  check('   no unsuffixed palette alias (system_accent1_500 and the like), no error palette shade, no name outside the list (no custom colour); 130 palette entries, 110 role entries, 12 fixed', aliases(plan).length === 0 && names(plan).every(r => QPR_ORDER.includes(r)) && names(plan).filter(r => /_(accent[123]|neutral[12])_\d+_(light|dark)$/.test(r)).length === 130 && names(plan).filter(r => ROLES55.some(x => r === RES + x + '_light' || r === RES + x + '_dark')).length === 110 && names(plan).filter(r => FIXED.includes(r.replace(RES, ''))).length === 12, JSON.stringify(aliases(plan)));
  check('   ' + Object.keys(Q2_FID_0).length + ' values pinned from the libmonet oracle (qpr2, spec 2025, contrast 0), light and dark: the shades, primary_light 0xFF6750A5, on_surface_variant_dark 0xFFAEA9B1 (curve 6), primary_dim_dark, control_activated_light (libmonet\'s primary_container), the fixed colours from the light scheme', pinsOff(plan, Q2_FID_0).length === 0, pinsOff(plan, Q2_FID_0).join('; '));
  check('   the record: Fidelity, spec 2025, P16Q2, the build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2025","profile":"P16Q2","build":"' + BQ2 + '"}', await rec());
  const darkPlan = plan;
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C2. the same in light mode: the same 252 entries and values (both modes are in the plan; the mode at Apply changes nothing)', JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries), names(plan).length);
  await ev(() => { window.__dark = true; window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C3. contrast 0.5 (read when Apply is tapped): ' + Object.keys(Q2_FID_05).length + ' values pinned from the oracle at 0.5 (primary_light 0xFF4B3386, on_surface_variant both modes, the fixed colours); same order', pinsOff(plan, Q2_FID_05).length === 0 && sameOrder(plan, QPR_ORDER), pinsOff(plan, Q2_FID_05).join('; '));
  await phone({ sdk: 36, profile: 'P16Q1', build: BQ1, contrast: 0 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C4. P16Q1, the same Fidelity at contrast 0: 252 entries in the same order, ' + Object.keys(Q1_FID_0).length + ' values pinned from the qpr1 oracle: on_surface_variant_dark and control_normal_dark 0xFF969299 (QPR1\'s curve 4.5), not QPR2\'s 0xFFAEA9B1; the record says P16Q1', sameOrder(plan, QPR_ORDER) && pinsOff(plan, Q1_FID_0).length === 0 && Q1_FID_0.on_surface_variant_dark !== Q2_FID_0.on_surface_variant_dark && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2025","profile":"P16Q1","build":"' + BQ1 + '"}', JSON.stringify([pinsOff(plan, Q1_FID_0), await rec()]));
  await ev(() => { window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   P16Q1 at contrast 0.5: ' + Object.keys(Q1_FID_05).length + ' values pinned from the qpr1 oracle', pinsOff(plan, Q1_FID_05).length === 0, pinsOff(plan, Q1_FID_05).join('; '));
  await ev(() => { window.__contrast = 0; kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); });
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C5. Content (More styles) B5651D at 2025, contrast 0, P16Q1: 252 in order; on_surface_variant_dark 0xFF9E9189 (qpr1 oracle)', sameOrder(plan, QPR_ORDER) && pinsOff(plan, Q1_CON_0).length === 0, pinsOff(plan, Q1_CON_0).join('; '));
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2, contrast: 0 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   the same on P16Q2: ' + Object.keys(Q2_CON_0).length + ' values pinned from the qpr2 oracle (its 2021 tertiary palette, accent3_500_dark 0xFF767D0E, with 2025 roles)', sameOrder(plan, QPR_ORDER) && pinsOff(plan, Q2_CON_0).length === 0, pinsOff(plan, Q2_CON_0).join('; '));

  // ---- (d) spec 2021 on QPR ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); }); c = await card();
  check('D1. P16Q2, Tonal Spot in the list at 2021: not native any more: the hint names its 2021 colors in place of Android\'s; the preview shows; no spec note under 2021', c.hint === 'Also writes this style\'s 2021 colors with root, in place of Android\'s.' && c.preview && c.specNote === '' && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2021"]', JSON.stringify([c.hint, c.preview, c.specNote]));
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('   Apply: the setting (TONAL_SPOT), then 244 entries: the list without the four dim roles (2021 does not define them), in order; no alias, no error shade', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && names(plan).length === 244 && sameOrder(plan, QPR_ORDER_2021) && !names(plan).some(r => DIMS.some(d => r === RES + d + '_light' || r === RES + d + '_dark')) && aliases(plan).length === 0, JSON.stringify([names(plan).length, names(plan).filter(r => DIMS.some(d => r.includes(d)))]));
  check('   ' + Object.keys(Q2_TS21_0).length + ' values pinned from the qpr2 oracle at SPEC_2021, contrast 0; the record says Tonal Spot (2021 is not written into a record), P16Q2', pinsOff(plan, Q2_TS21_0).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","profile":"P16Q2","build":"' + BQ2 + '"}', JSON.stringify([pinsOff(plan, Q2_TS21_0), await rec()]));
  await ev(() => { window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D2. at contrast 0.5: ' + Object.keys(Q2_TS21_05).length + ' values pinned from the oracle at 0.5', pinsOff(plan, Q2_TS21_05).length === 0 && names(plan).length === 244, pinsOff(plan, Q2_TS21_05).join('; '));
  await phone({ sdk: 36, profile: 'P16Q1', build: BQ1, contrast: 0 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   P16Q1 at 2021, contrast 0: 244 in order; ' + Object.keys(Q1_TS21_0).length + ' values pinned from the qpr1 oracle (at 2021 QPR1 and QPR2 agree: on_surface_variant_dark 0xFFCAC4CF)', sameOrder(plan, QPR_ORDER_2021) && pinsOff(plan, Q1_TS21_0).length === 0, pinsOff(plan, Q1_TS21_0).join('; '));
  await ev(() => { window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   P16Q1 at 2021, contrast 0.5: 244 in order; ' + Object.keys(Q1_TS21_05).length + ' values pinned from the qpr1 oracle at 0.5', sameOrder(plan, QPR_ORDER_2021) && pinsOff(plan, Q1_TS21_05).length === 0, pinsOff(plan, Q1_TS21_05).join('; '));
  await ev(() => { window.__contrast = 0; });
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   Fidelity (More styles) at 2021 follows the spec: 244 entries, the hint names Fidelity, the record has no spec', names(plan).length === 244 && sameOrder(plan, QPR_ORDER_2021) && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16Q1","build":"' + BQ1 + '"}', JSON.stringify([names(plan).length, c.hint, await rec()]));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_black', true); ovlRender(); }); c = await card();
  const h1 = c.hint;
  await ev(() => { kvSet('ovl_adj_accent_sat', 150); ovlRender(); }); c = await card(); const h2 = c.hint;
  await ev(() => { kvSet('ovl_black', false); ovlRender(); }); c = await card(); const h3 = c.hint;
  await ev(() => { kvSet('ovl_spec', '2025'); ovlRender(); }); c = await card(); const h4 = c.hint;
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  await ev(() => { kvSet('ovl_adj_accent_sat', 100); kvSet('ovl_spec', '2021'); ovlRender(); });
  check('D3. the hint sentences on QPR: at 2021 with Pure black "' + h1 + '"; with an Intensity slider "' + h2 + '", without Pure black "' + h3 + '"; at 2025 with the slider, the 2025 sentence, and the plan is the 2025 one (252)',
    h1 === 'Also writes this style\'s 2021 colors and pure black with root, in place of Android\'s.' && h2 === 'Also writes this style\'s 2021 colors, adjusted by the Intensity sliders, and pure black with root.' && h3 === 'Also writes this style\'s 2021 colors with root, adjusted by the Intensity sliders.' && h4 === 'Also writes this style\'s 2025 colors with root, adjusted by the Intensity sliders.' && names(plan).length === 252, JSON.stringify([h1, h2, h3, h4, names(plan).length]));

  // ---- (e) Pure black on QPR ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); kvSet('ovl_black', true); ovlPickStyle('TONAL_SPOT'); ovlRender(); });
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2, contrast: 0 }); c = await card(); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('E1. P16Q2, Pure black alone (Tonal Spot at 2025 is native): the hint names pure black; 11 entries, all _dark names (system_neutral1_900_dark, system_neutral2_900_dark, the nine dark roles), black and the greys; no _light or unsuffixed name', c.hint === 'Also writes the pure black overlay with root.' && JSON.stringify(names(plan)) === JSON.stringify(BLACK_QPR.map(n => RES + n)) && names(plan).every(r => /_dark$/.test(r)) && byRes(plan).neutral1_900_dark === '0xFF000000' && byRes(plan).surface_container_dark === '0xFF181818' && byRes(plan).surface_bright_dark === '0xFF2C2C2C', JSON.stringify(names(plan)));
  check('   the record: black, no style, P16Q2, the build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","profile":"P16Q2","build":"' + BQ2 + '"}', await rec());
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('E2. Pure black over Fidelity on P16Q2: still 252 in SystemUI\'s order; neutral1_900_dark, neutral2_900_dark, background_dark, surface_dark black, surface_container_dark grey; the _light values stay the oracle\'s (neutral1_900_light 0xFF1D1B20, surface_light 0xFFFDF7FF), the rest Fidelity\'s (primary_dark 0xFFBBA2FD)', sameOrder(plan, QPR_ORDER) && ['neutral1_900_dark', 'neutral2_900_dark', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818' && r.neutral1_900_light === Q2_FID_0.neutral1_900_light && r.surface_light === Q2_FID_0.surface_light && r.primary_dark === Q2_FID_0.primary_dark && r.neutral2_900_light !== '0xFF000000', JSON.stringify([r.neutral1_900_light, r.neutral2_900_light, r.surface_light]));
  await ev(() => { kvSet('ovl_black', false); }); await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   on P16 (16.0, spec 2021) Pure black alone is the plan from before (system_neutral1_900 and system_neutral2_900 unsuffixed, 11 entries)', names(plan).length === 11 && names(plan)[0] === RES + 'neutral1_900' && names(plan)[1] === RES + 'neutral2_900' && JSON.stringify(plan.entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(34))), JSON.stringify(names(plan).slice(0, 3)));
  await ev(() => { kvSet('ovl_black', false); });

  // ---- (f) a list style at 2025 with an accent: each mode's own shades; the preview ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); kvSet('ovl_accent_secondary', '00A0FF'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('FFDE3F'); window.__dark = true; ovlRender(); });
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2, contrast: 0 }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('F1. P16Q2, Tonal Spot FFDE3F at 2025 with a secondary accent (so it is written): the hint says its 2025 colors; 252 in order; accent1 and the neutral / primary values pinned from the oracle (the secondary accent touches accent2 and the secondary roles only): accent1_100_light 0xFFF6E292 from the light scheme, accent1_100_dark 0xFFF3E2A2 from the dark one', c.hint === 'Also writes this style\'s 2025 colors with root, in place of Android\'s.' && sameOrder(plan, QPR_ORDER) && pinsOff(plan, Q2_TSY_0).length === 0 && Q2_TSY_0.accent1_100_light !== Q2_TSY_0.accent1_100_dark, JSON.stringify([c.hint, pinsOff(plan, Q2_TSY_0)]));
  let p = await preview();
  check('   the preview in dark mode shows the dark scheme\'s accent1 (shade 100 #F3E2A2, 500 #847741)', p.rows === 5 && p.accent1[3] === '#F3E2A2' && p.accent1[7] === '#847741', JSON.stringify(p.accent1));
  await ev(() => { window.__dark = false; ovlRender(); }); p = await preview();
  check('   in light mode the light scheme\'s (#F6E292, #867732)', p.rows === 5 && p.accent1[3] === '#F6E292' && p.accent1[7] === '#867732', JSON.stringify(p.accent1));
  await ev(() => { window.__dark = true; ovlPickStyle('VIBRANT'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('F2. Vibrant FFDE3F at 2025 with the same accent: ' + Object.keys(Q2_TVY_0).length + ' values pinned from the qpr2 oracle on its yellow palettes at tone 99 (shade 10 and background / surface: 0xFFFFFBFF, no averaging of tones 98 and 100)', sameOrder(plan, QPR_ORDER) && pinsOff(plan, Q2_TVY_0).length === 0, pinsOff(plan, Q2_TVY_0).join('; '));
  await ev(() => { window.__dark = true; kvSet('ovl_accent_secondary', ''); ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); ovlRender(); });
  const sheet = () => ev(() => ({ open: document.getElementById('colorPickModal').classList.contains('show'), sub: document.getElementById('cpSub').innerText, hex: document.getElementById('cpHex1').value }));
  await ev(() => { kvSet('ovl_spec', null); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); let sh = await sheet(); await ev(() => closeColorPicker());
  await ev(() => { kvSet('ovl_spec', '2021'); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); const sh21 = await sheet(); await ev(() => { closeColorPicker(); kvSet('ovl_spec', '2025'); ovlRender(); });
  check('F3. P16Q2, Tonal Spot 6750A4 in the list, dark, no spec stored (2025, native): the Tertiary accent\'s picker starts at the key colour Android\'s own 2025 scheme gives (#956989, qpr2 oracle), not the 2021 one; at 2021 (written with root) at #996A79 (qpr2 oracle at SPEC_2021)', sh.open && /#956989 /.test(sh.sub) && sh.hex.toUpperCase().includes('956989') && sh21.open && /#996A79 /.test(sh21.sub), JSON.stringify([sh, sh21]));

  // ---- (g) the record and the note for colours written on another build ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2025'); kvSet('ovl_root_applied', null); });
  await phone({ sdk: 36, profile: 'P16Q1', build: BQ1 }); await reset(); await apply(); c = await card();
  check('G1. written on P16Q1: no note about another build; the record says P16Q1 and its build', c.stale === '' && (await rec()).includes('"profile":"P16Q1","build":"' + BQ1 + '"'), JSON.stringify([c.stale, await rec()]));
  await reset(); await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); c = await card(); k = await calls();
  check('   the phone now runs QPR2 (P16Q2, another build): the note says Android was updated; nothing is written by itself', c.stale === STALE && c.note === 'The theme overlay is written on this phone.' && k.fab.length === 0 && k.apply.length === 0, JSON.stringify([c.stale, k.fab.length]));
  await reset(); await apply(); c = await card();
  check('   Apply writes them for QPR2: the record says P16Q2 and the new build, and the note goes', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2025","profile":"P16Q2","build":"' + BQ2 + '"}' && c.stale === '', JSON.stringify([await rec(), c.stale]));
  await ev(() => { window.__dark = false; ovlRender(); }); c = await card(); await ev(() => { window.__dark = true; ovlRender(); });
  check('G2. a switch to light mode after the write: no note (both modes are in the overlay); the hint is the same', c.stale === '' && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify([c.stale, c.hint]));
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2b }); c = await card();
  check('   a monthly update that stays QPR2 (another build, P16Q2): no note, Apply would write the same colours', c.stale === '', c.stale);
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P16', build: b }); ovlRender(); }, [AAM, B16]); c = await card();
  check('G3. a record written on Android 16.0 (P16), the phone now on QPR2: the note shows', c.stale === STALE, c.stale);
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card();
  check('   with only the list style at the phone\'s spec (native): no such note, and the hint says Apply removes the overlay', c.stale === '' && c.hint === 'Also removes the theme overlay.', JSON.stringify([c.stale, c.hint]));
  await reset(); await apply(); k = await calls();
  check('   Apply there: the setting, then a disable of the overlay; the record is cleared', k.apply.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify(k.fab));

  // ---- (h) Undo and Default with a QPR overlay ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_root_applied', null); ovlRender(); });
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2, contrast: 0 }); await reset(); await apply();
  const first = ((await calls()).fab[0] || {}).entries;
  await ev(() => { kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply();
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('H1. Undo of a Content write after a Fidelity one on P16Q2: the theme is put back and Fidelity\'s 252 entries are written again, as they were', k.restore.length === 1 && k.fab.length === 1 && plan.action === 'fabricate' && JSON.stringify(plan.entries) === JSON.stringify(first) && (await rec()).includes('"style":"FIDELITY"'), JSON.stringify([k.restore.length, k.fab.length, names(plan).length]));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply();
  const first21 = ((await calls()).fab[0] || {}).entries || [];
  await ev(() => { kvSet('ovl_spec', null); kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); const fid = ((await calls()).fab[0] || {}).entries || [];
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('H1b. Undo of a Fidelity write (2025, 252) made after a Tonal Spot one at 2021 (244, its record has no spec), with no spec stored now (the page\'s default is 2025 on QPR): the 244 entries of the 2021 write are written again, as they were', first21.length === 244 && fid.length === 252 && k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 244 && JSON.stringify(plan.entries) === JSON.stringify(first21) && (await rec()).includes('"style":"TONAL_SPOT"') && !(await rec()).includes('"spec"'), JSON.stringify([first21.length, fid.length, k.fab.length, names(plan).length, await rec()]));
  await reset(); dialogs.length = 0; await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('H2. Default with the overlay on: the question says it is switched off too; the setting is reset and the overlay disabled; the record is cleared', dialogs.length === 1 && dialogs[0].includes('The theme overlay is switched off too.') && k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([dialogs, k.fab]));

  // the resume path (T-068): an Apply of Fidelity at 2025 on P16Q2 whose theme answer is lost to a restart; the restarted page, the theme read
  // back as written, completes that Apply's overlay half with the 252-entry plan in SystemUI's order (as t564 13b does with Pure black)
  const KEY = 'theme_customization_overlay_packages';
  const qpr = () => { window.__root = true; window.__sdk = 36; window.__profile = 'P16Q2'; window.__build = 'google/mock/mock:16/BP4A.251205.006/14401865:user/release-keys'; window.__dark = true; window.__contrast = 0; };
  const openWith = async (kvs, fn, arg) => {
    const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
    p2.on('pageerror', e => errors.push(e.message));
    await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, kvs);
    await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript); await p2.addInitScript(qpr); await p2.addInitScript(fn, arg);
    return p2;
  };
  const p1 = await openWith(JSON.stringify({ ovl_root_style: '"FIDELITY"', ovl_spec: '"2025"' }), () => {}, null);
  await p1.goto(URL); await p1.waitForTimeout(500);
  await p1.evaluate(() => switchView('overlays')); await p1.waitForTimeout(400);
  await p1.evaluate(() => { window.__ovlNoAnswer = true; });
  await p1.locator('#ovlApplyBtn').click(); await p1.waitForTimeout(150);
  const lost = await p1.evaluate(() => {
    const a = window.__ovlCalls.apply[0] || {}, st = a.style || 'TONAL_SPOT';
    const value = a.source === 'preset' ? JSON.stringify({ 'android.theme.customization.system_palette': a.hex, 'android.theme.customization.color_source': 'preset', 'android.theme.customization.theme_style': st, _applied_timestamp: Date.now() })
      : JSON.stringify({ 'android.theme.customization.color_source': 'home_wallpaper', 'android.theme.customization.theme_style': st, _applied_timestamp: Date.now() });
    return { kv: JSON.stringify(window.AndroidBridge.__kv), value, resume: kvGet('ovl_resume', null), fab: window.__ovlCalls.fab.length };
  });
  await p1.close();
  const p2 = await openWith(lost.kv, ([key, v]) => { window.__db.secure[key] = v; }, [KEY, lost.value]);
  await p2.goto(URL); await p2.waitForTimeout(1900);
  await p2.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).catch(() => {}); await p2.waitForTimeout(100);
  const rs = await p2.evaluate(() => ({ fab: window.__ovlCalls.fab.map(f => ({ action: f.action, names: (f.entries || []).map(e => e.resource) })), applied: kvGet('ovl_root_applied', null), done: (kvGet('ovl_resume', null) || {}).done === true }));
  await p2.close();
  const rf = rs.fab[0] || { names: [] };
  check('H3. the resume path on P16Q2: Fidelity at 2025 tapped, the theme answer lost to a restart (nothing written yet, the resume record plans the overlay); the restarted page, the theme read back as written, writes that overlay: 252 entries in SystemUI\'s order; the record says Fidelity, 2025, P16Q2; the resume record is done',
    lost.fab === 0 && !!lost.resume && lost.resume.root === 'fabricate' && rs.fab.length === 1 && rf.action === 'fabricate' && JSON.stringify(rf.names) === JSON.stringify(QPR_ORDER) && !!rs.applied && rs.applied.style === 'FIDELITY' && rs.applied.spec === '2025' && rs.applied.profile === 'P16Q2' && rs.done,
    JSON.stringify([lost.fab, lost.resume && lost.resume.root, rs.fab.map(f => f.action + ':' + f.names.length), rs.applied, rs.done]));

  // ---- (i) the new words in German ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); });
  await ev(() => I18N.set('de')); await sleep(600); await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); await sleep(200); c = await card();
  const de = await ev(keys => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return keys.map(k => x[k] || ''); }, [BLACK_SUB_QPR, SPEC_SUB_QPR, SPEC_DESC_QPR[0], 'Also writes this style\'s 2021 colors with root, in place of Android\'s.']);
  check('I1. in German: the QPR Pure black and Color spec lines, the 2021 description and the 2021 hint read as assets/lang/de.js has them', de.every(t => t !== '') && c.black.sub === de[0] && c.spec.sub === de[1] && c.specs[0].desc === de[2] && c.hint === de[3], JSON.stringify([de, c.black.sub, c.spec.sub, c.specs[0] && c.specs[0].desc, c.hint]));

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
