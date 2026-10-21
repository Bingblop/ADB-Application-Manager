// Root colours on Android 17 (T-049 PR 10: profile P17): the Overlays tab's root-only theme controls are live there and write what
// SystemUI writes on that build. (a) Live on P17 (MonetProfile.java reports it ok): every row can be used, CMF among the More styles; the
// Pure black line is the QPR one (light mode untouched), the Color spec line, its three specs and the note under them are the 17 ones
// (2026 is the phone's own spec; 2025 gives the same colours without CMF; 2021 recolors Tonal Spot, Vibrant, Expressive and Spritz);
// with no spec stored, 2026 is the one chosen. Still locked: an Android above 17 (unknown), 17 without its colour names (unknown), 17 as
// an APK from before PR 10 reports it (notYet), One UI on 17, and without Super User. (b) Native routing: the list style at 2026 and at
// 2025 (which the oracle shows give every style the same colours) with nothing else set is the theme setting only (no overlay, no hint,
// no preview); Rainbow and Fruit Salad at 2021 too (2026 gives them 2021). (c) Fidelity (More styles) at 2026 on P17: 264 entries, names
// and order equal to the list below made by hand from SystemUI's write order (TOC android17-release createOverlays /
// assignColorsToOverlay: accent1-3 and neutral1-2, 13 shades each, _light then _dark; the 55 roles sorted by name, _light then _dark; the
// 12 fixed colours sorted by name, _light then _dark, as the roles), no unsuffixed palette or fixed name, nothing else (no custom colour);
// values pinned from the libmonet android17-release oracle at contrast 0 and 0.5, light and dark in the same plan (at 0.5 the fixed
// colours' _light and _dark differ); the values are 17's, not QPR2's (Fidelity falls back to 2021 there); the lookups the Java side
// makes include system_primary_fixed_dark; the mode at Apply changes nothing. (d) Spec 2025 written as an overlay: Fidelity at 2025 (264,
// the same values, the record says 2025) and Tonal Spot FFDE3F at 2025 with an accent (its fixed colours _light from the light scheme,
// _dark from the dark one; the hint without a spec, since its colours are Android's own plus the accent); the accent 1B6EF3 there gives
// accent2 the oracle's accent1 of Tonal Spot 1B6EF3, each mode its own; CMF only at 2026, pinned.
// (e) Spec 2021: Tonal Spot is written (its 2021 colours over Android's), 256 entries: the four *_dim roles, which 2021 does not define,
// are left out; values pinned at contrast 0 and 0.5; Vibrant, Expressive and Spritz likewise (pinned at 0); Fidelity at 2021 likewise;
// Rainbow at 2021 with an Intensity slider is written with the sentence without a spec. Accent saturation at 0 turns the accent shades of
// both modes grey; Accurate shades off gives shade 100 and the tone-90 fixed colours of each mode that mode's shade 300 (oracle pins). (f) Pure black writes only _dark names, alone and over a style. (g) The preview and the accent picker's
// start at the phone's own spec. (h) The record (profile, build) and the note for colours written on another build: a QPR2 record on 17,
// a monthly update, a mode change. (i) Undo, Default, an Undo back to a 2021 write, the resume path after a restart. (j) The new words in
// German. (k) The mock's name check mirrors OverlayRules: an unsuffixed fixed colour is refused on P17 only.
// Every hex value pinned below comes from an oracle kept outside the repository and not from this app's code: SystemUI's DynamicColors.java
// and TonalPalette.java (frameworks/libs/systemui monet, android17-release) compiled on external/libmonet android17-release, with
// ThemeOverlayController's createOverlays / assignColorsToOverlay (android17-release) transcribed (pr10-oracle Q17Dump). Spec "2026" here
// is the phone's own (ColorScheme's 4-argument constructor: SPEC_2026; the oracle's rows at 2025, 2026 and the default agree for every
// style); FIDELITY, which SystemUI never builds, is libmonet's SchemeFidelity with the same write sequence; CMF is libmonet a17's SchemeCmf.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const NOT_YET = 'Not supported on this Android version yet';
const ONE_UI = 'Not supported on One UI';
const STALE = 'Android was updated after these colors were written. Tap Apply theme to write them for this version.';
const AAM = 'com.android.shell:aam_monet';
const RES = 'android:color/system_';
// the lines the card shows on 17
const BLACK_SUB_QPR = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors.';
const SPEC_SUB_A17 = 'With root, 2021 recolors Tonal Spot, Vibrant, Expressive and Spritz. CMF needs 2026.';
const SPEC_NOTE_A17 = 'Android computes these colors itself: a style of the list above is written with root only with Intensity, an accent or Accurate shades off, or at 2021 for Tonal Spot, Vibrant, Expressive and Spritz.';
const SPEC_DESC_A17 = ['The colors earlier Android versions compute.', 'The same colors as 2026, without the CMF style.', 'The colors Android computes itself, plus the CMF style.'];
const SPEC_SUB_QPR = 'With root, 2021 recolors every style. CMF needs 2026.';
const BLACK_SUB = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors, except that its darkest text becomes pure black.';
// builds (Build.FINGERPRINT) the mock reports
const BQ2 = 'google/mock/mock:16/BP4A.251205.006/14401865:user/release-keys';
const B17 = 'google/mock/mock:17/CP1A.260605.009/15230418:user/release-keys';
const B17b = 'google/mock/mock:17/CP1A.260705.004/15377102:user/release-keys';
// SystemUI's write order on 17, made by hand from ThemeOverlayController (android17-release :689-718) and SystemUI's DynamicColors: the
// accent overlay (accent1, accent2, accent3), the neutral overlay (neutral1, neutral2), each palette's 13 shades in order, each shade _light
// (light scheme) then _dark (dark scheme); then the 55 roles (getAllDynamicColorsMapped, sorted by name), each _light then _dark; then the
// 12 fixed colours (getFixedColorsMapped, sorted by name), each _light then _dark too (assignColorsToOverlay has no fixed branch on 17)
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
const LD = n => [n + '_light', n + '_dark'];
const A17_ORDER = PAL_ROWS.map(r => SHADES.map(s => LD(r + '_' + s)).flat()).flat().concat(ROLES55.map(LD).flat(), FIXED.map(LD).flat()).map(n => RES + n);
// at spec 2021: the same without the four dim roles (libmonet's ColorSpec2021 has none)
const A17_ORDER_2021 = A17_ORDER.filter(r => !DIMS.some(d => r === RES + d + '_light' || r === RES + d + '_dark'));
// Oracle pins (resource suffix: value), libmonet android17-release. FID: FIDELITY 6750A4 at 2026 (the phone's own; the oracle's 2025 rows
// are the same); TS21: TONAL_SPOT 6750A4 at 2021; CON: CONTENT B5651D at 2026; TSY: TONAL_SPOT FFDE3F at 2026 (= 2025); CMF: CMF 6750A4 at 2026
const FID_0 = { accent1_100_light: '0xFFE9DDFF', accent1_500_dark: '0xFF8069BF', accent3_500_dark: '0xFF91731D', neutral1_900_light: '0xFF1D1B20', neutral2_500_dark: '0xFF7A7582',
  primary_light: '0xFF4F378A', primary_dark: '0xFFCFBCFF', surface_light: '0xFFFDF7FF', surface_dark: '0xFF141218', on_surface_variant_dark: '0xFFCBC4D2', primary_container_dark: '0xFF6750A4',
  outline_dark: '0xFF948E9C', primary_dim_dark: '0xFFDCCDFF', control_activated_light: '0xFFE9DDFF', control_normal_dark: '0xFFCBC4D2', text_primary_inverse_light: '0xFFE6E0E9',
  primary_fixed_light: '0xFFE9DDFF', primary_fixed_dark: '0xFFE9DDFF', on_primary_fixed_light: '0xFF22005D', on_primary_fixed_dark: '0xFF22005D', secondary_fixed_dim_light: '0xFFCDC0E9',
  secondary_fixed_dim_dark: '0xFFCDC0E9', tertiary_fixed_dark: '0xFFFFDF93' };
const FID_05 = { primary_light: '0xFF3E2578', primary_dark: '0xFFE3D6FF', on_surface_variant_dark: '0xFFE1DAE8', primary_container_dark: '0xFF9A83DB', outline_dark: '0xFFB6AFBD',
  primary_dim_dark: '0xFFDCCDFF', primary_fixed_light: '0xFF765FB4', primary_fixed_dark: '0xFFE9DDFF', on_primary_fixed_light: '0xFFFFFFFF', on_primary_fixed_dark: '0xFF160042',
  secondary_fixed_dim_light: '0xFF595072', secondary_fixed_dim_dark: '0xFFCDC0E9' };
// what the same Fidelity gives on 16 QPR2 (t571's qpr2 oracle pin): 17's own rules differ there
const Q2_FID_PRIMARY_LIGHT = '0xFF6750A5';
const TS21_0 = { accent1_100_light: '0xFFE9DDFF', accent1_500_dark: '0xFF7E6EA9', accent3_500_dark: '0xFF996A79', neutral2_500_dark: '0xFF7A757F', primary_light: '0xFF65558F',
  primary_dark: '0xFFCFBDFE', surface_dark: '0xFF141218', on_surface_variant_dark: '0xFFCAC4CF', primary_container_dark: '0xFF4D3D75', outline_dark: '0xFF948F99',
  control_activated_light: '0xFFE9DDFF', text_primary_inverse_light: '0xFFE6E0E9', primary_fixed_light: '0xFFE9DDFF', primary_fixed_dark: '0xFFE9DDFF', on_primary_fixed_light: '0xFF201047',
  secondary_fixed_dim_dark: '0xFFCBC2DB', tertiary_fixed_dark: '0xFFFFD9E3' };
const TS21_05 = { primary_light: '0xFF3C2D63', primary_dark: '0xFFE3D6FF', on_surface_variant_dark: '0xFFE0DAE5', primary_container_dark: '0xFF9887C5', outline_dark: '0xFFB5B0BB',
  primary_fixed_light: '0xFF74649F', primary_fixed_dark: '0xFFE9DDFF', on_primary_fixed_light: '0xFFFFFFFF', on_primary_fixed_dark: '0xFF16033D', secondary_fixed_dim_light: '0xFF585267' };
const CON_0 = { accent1_500_dark: '0xFFB2631B', accent3_500_dark: '0xFF767D0E', primary_light: '0xFF8F4900', primary_dark: '0xFFFFB781', surface_dark: '0xFF19120D',
  on_surface_variant_dark: '0xFFDAC2B3', primary_dim_dark: '0xFFFFCAA4', primary_fixed_light: '0xFFFFDCC5', primary_fixed_dark: '0xFFFFDCC5', tertiary_fixed_dark: '0xFFE2EA75' };
const TSY_0 = { accent1_100_light: '0xFFF6E292', accent1_100_dark: '0xFFF3E2A2', accent1_500_light: '0xFF867732', accent1_500_dark: '0xFF847741', neutral1_900_light: '0xFF1E1B15',
  neutral1_10_light: '0xFFFFFBFF', primary_light: '0xFF6D5F1C', primary_dark: '0xFFD6C689', surface_light: '0xFFFFF9EE', surface_dark: '0xFF100E08', on_surface_variant_dark: '0xFFB1AB99',
  primary_fixed_light: '0xFFF6E292', primary_fixed_dark: '0xFFF3E2A2' };
const CMF_0 = { accent1_500_light: '0xFF8069BF', accent3_500_dark: '0xFF7E6EA9', neutral1_900_light: '0xFF1D1A24', primary_light: '0xFF5B4497', primary_dark: '0xFFA890E9',
  surface_dark: '0xFF0F0D16', primary_fixed_light: '0xFF6750A4', primary_fixed_dark: '0xFF6750A4', on_surface_variant_dark: '0xFFB0A7C0', primary_dim_dark: '0xFFA890E9' };
// a secondary accent 1B6EF3 over TSY at 2025: accent2 is the primary palette of the same style's scheme built from 1B6EF3, each mode its
// own (monet.js's accent rule), so its pins are the oracle's accent1 of TONAL_SPOT 1B6EF3 at 2025 (light and dark differ from shade 200 on)
const ACC_1B = { accent2_100_light: '0xFFD9E2FF', accent2_100_dark: '0xFFD9E2FF', accent2_200_light: '0xFFB3C6F9', accent2_200_dark: '0xFFB8C6EE', accent2_500_light: '0xFF6476A5',
  accent2_500_dark: '0xFF69769B', accent2_700_light: '0xFF334671', accent2_700_dark: '0xFF394668', accent2_900_light: '0xFF021943', accent2_900_dark: '0xFF0B1A3A' };
// TSY at 2025 without the accent (the oracle): accent2 and the secondary roles the accent must change in both modes
const TSY_NOACC = { accent2_200_light: '0xFFD1C6A1', accent2_200_dark: '0xFFD1C6A1', secondary_light: '0xFF675F41', secondary_dark: '0xFFD1C6A1' };
// TSY at 2026 (the oracle): shade 300 of the three accent rows, light and dark, the colour Accurate shades off gives shade 100 and the
// fixed colours taken at tone 90 (primary_fixed, secondary_fixed); tertiary_fixed (0xFFFDC891) is not taken at tone 90 there
const TSY26_300 = { accent1_300_light: '0xFFBDAB61', accent1_300_dark: '0xFFBAAB70', accent2_300_light: '0xFFB5AB88', accent2_300_dark: '0xFFB5AB88', accent3_300_light: '0xFFD3A26E', accent3_300_dark: '0xFFD3A26E' };
// the 2021 colours of Vibrant, Expressive and Spritz 6750A4 (the oracle, contrast 0); each value differs from the oracle's 2026 one
const V21_0 = {
  VIBRANT: { primary_light: '0xFF6F19FF', primary_dark: '0xFFCFBCFF', accent3_500_dark: '0xFF936994', primary_fixed_dark: '0xFFE9DDFF', on_surface_variant_dark: '0xFFCBC3D5' },
  EXPRESSIVE: { primary_light: '0xFF006B5A', primary_dark: '0xFF79D7C0', accent3_500_dark: '0xFF8D6B9A', primary_fixed_dark: '0xFF95F4DC', on_surface_variant_dark: '0xFFCFC2D3' },
  SPRITZ: { primary_light: '0xFF615C6B', primary_dark: '0xFFCBC3D5', accent3_500_dark: '0xFF7B748A', primary_fixed_dark: '0xFFE7DFF2', on_surface_variant_dark: '0xFFC9C5C7' } };
// a grey's colour at each shade's tone (shade s: tone (1000 - s) / 10), from CIE L* (L* = tone) to sRGB, worked out apart from this app:
// Accent saturation at 0 makes the three accent palettes chroma 0
const GREY = { 100: '0xFFE2E2E2', 200: '0xFFC6C6C6', 300: '0xFFABABAB', 400: '0xFF919191', 500: '0xFF777777', 600: '0xFF5E5E5E', 700: '0xFF474747', 800: '0xFF303030', 900: '0xFF1B1B1B' };
// Pure black's 11 entries on 17: _dark names only
const BLACK_DARK = ['neutral1_900_dark', 'neutral2_900_dark', 'background_dark', 'surface_dark', 'surface_dim_dark', 'surface_container_lowest_dark', 'surface_container_low_dark',
  'surface_container_dark', 'surface_container_high_dark', 'surface_container_highest_dark', 'surface_bright_dark'];
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; window.__fabAns.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  // the phone: { sdk, profile, reason, build, contrast } (reason left out follows the profile as ovl_mock says: ok for P17 from PR 10 on)
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
      note: document.getElementById('ovlRootNote').innerText, stale: stale && stale.style.display !== 'none' ? stale.innerText : '', hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText,
      accHint: (document.getElementById('ovlApplyHintAccents') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '' };
  });
  const lockedAll = (c, reason) => c.shown && ['black', 'spec', 'more'].every(k => c[k].locked && c[k].lock === reason) && c.live.length === 0 && c.chips === 0;
  const LIVE = '["ovlBlackSw","2021","2025","2026","OFF","FIDELITY","CONTENT","MONOCHROMATIC","CMF","ovlAdjAccentSat","ovlAdjBgSat","ovlAdjBgLight","ovlAccSecondary","ovlAccTertiary","ovlAccurateSw"]';
  const preview = () => ev(() => { const pv = document.getElementById('ovlRootPreview'); const rows = pv ? Array.from(pv.querySelectorAll('.ovl-pal')) : []; return { rows: rows.length, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] }; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const names = plan => ((plan && plan.entries) || []).map(e => e.resource);
  const sameOrder = (plan, order) => JSON.stringify(names(plan)) === JSON.stringify(order);
  // the forms ruled out on 17: an unsuffixed palette shade (system_accent1_500), an error palette shade, an unsuffixed fixed colour
  const aliases = plan => names(plan).filter(r => /^android:color\/system_(accent[123]|neutral[12]|error)_\d+$/.test(r) || /^android:color\/system_error_\d+(_light|_dark)?$/.test(r)
    || FIXED.some(f => r === RES + f));
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  await ev(() => {
    window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); };
    // the bridge's answers to overlayFabricate (the mock's lookups among them), as the page receives them
    window.__fabAns = []; const realFab = window.onOverlayFab; window.onOverlayFab = function (j) { try { window.__fabAns.push(JSON.parse(j)); } catch (e) {} return realFab.apply(this, arguments); };
  });
  const lookups = () => ev(() => { const a = window.__fabAns[window.__fabAns.length - 1]; return a && a.lookups ? Object.keys(a.lookups) : []; });

  // ---- (a) live on P17; the locks that stay ----
  await ev(() => switchView('overlays')); await sleep(300);
  await setRoot(true);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); kvSet('ovl_root_applied', null); window.__dark = true; });
  await phone({ sdk: 37, profile: 'P17', build: B17 }); let c = await card(); const reason = await ev(() => ovlPalette.profileReason);
  check('A1. Android 17 (P17) with Super User (reported ok): every row is live (the two switches, the two radio groups with CMF among the More styles, the three Intensity sliders, the two accent entries, and the preview\'s 65 swatches)', reason === 'ok' && c.shown && !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.preview, JSON.stringify([reason, c.live, c.chips]));
  check('   its lines: Pure black leaves light mode alone; Color spec says 2021 recolors Tonal Spot, Vibrant, Expressive and Spritz; the three specs say 2021 is earlier Android\'s colors, 2025 the same as 2026 without CMF, 2026 the phone\'s own plus CMF; with no spec stored 2026 is chosen, and the note under it is the 17 one (no light / dark sentence); the hint names Fidelity', c.black.sub === BLACK_SUB_QPR && c.spec.sub === SPEC_SUB_A17 && JSON.stringify(c.specs.map(s => s.desc)) === JSON.stringify(SPEC_DESC_A17) && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2026"]' && c.specNote === SPEC_NOTE_A17 && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify([c.black.sub, c.spec.sub, c.specs, c.specNote, c.hint]));
  await phone({ sdk: 36, profile: 'P16Q2', build: BQ2 }); c = await card();
  check('   on 16 QPR2 (P16Q2) the lines stay the QPR ones and 2025 is chosen with no spec stored', c.spec.sub === SPEC_SUB_QPR && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2025"]' && c.specs[2].desc === 'The 2025 colors, plus the CMF style.', JSON.stringify([c.spec.sub, c.specs]));
  const LOCKS = [
    ['an Android above 17 (SDK 38: unknown)', { sdk: 38, build: B17 }, NOT_YET],
    ['SDK 40 (unknown)', { sdk: 40, build: B17 }, NOT_YET],
    ['17 without its colour names (SDK 37, MonetProfile\'s probe not found: unknown)', { sdk: 37, profile: 'unknown', reason: 'unknown', build: B17 }, NOT_YET],
    ['17 as an APK from before PR 10 reports it (P17, notYet)', { sdk: 37, profile: 'P17', reason: 'notYet', build: B17 }, NOT_YET],
    ['a One UI phone on 17 (unknown, oneUi)', { sdk: 37, profile: 'unknown', reason: 'oneUi', build: B17 }, ONE_UI]
  ];
  for (const [label, o, why] of LOCKS) {
    await phone(o); c = await card(); await reset(); await apply(); const k = await calls();
    check('A2. ' + label + ': still locked with "' + why + '", nothing in the card can be used, no hint, no preview; Apply writes the theme setting only', lockedAll(c, why) && !c.preview && c.hint === '' && k.apply.length === 1 && k.fab.length === 0 && c.black.sub === BLACK_SUB, JSON.stringify([c.black, c.live, k.fab.length]));
  }
  await setRoot(false); await phone({ sdk: 37, profile: 'P17', build: B17 }); c = await card();
  check('A3. P17 without Super User: Needs Super User (as on every profile this app computes), its 17 lines, the note about Root', lockedAll(c, 'Needs Super User') && c.black.sub === BLACK_SUB_QPR && c.spec.sub === SPEC_SUB_A17 && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await setRoot(true);

  // ---- (b) native routing ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', null); ovlPickStyle('TONAL_SPOT'); ovlRender(); });
  await phone({ sdk: 37, profile: 'P17', build: B17 }); c = await card(); await reset(); await apply(); let k = await calls();
  check('B1. P17, Tonal Spot in the list, no spec stored (2026, the phone\'s own), nothing else on: no hint, no preview; Apply writes the theme setting only (TONAL_SPOT), no overlay call', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0 && (await kv('ovl_root_applied')) === null, JSON.stringify([c.hint, c.preview, k.fab.length]));
  for (const [spec, style] of [['2026', 'VIBRANT'], ['2025', 'TONAL_SPOT'], ['2025', 'EXPRESSIVE'], ['2026', 'SPRITZ'], ['2026', 'RAINBOW'], ['2025', 'FRUIT_SALAD'], ['2021', 'RAINBOW'], ['2021', 'FRUIT_SALAD']]) {
    await ev(([s, st]) => { kvSet('ovl_spec', s); ovlPickStyle(st); ovlRender(); }, [spec, style]); c = await card(); await reset(); await apply(); k = await calls();
    const at = await ev(([s, st]) => [MonetPlan.specFor(st, s, 'P17'), MonetPlan.specFor(st, '2026', 'P17')], [spec, style]);
    check('   ' + style + ' at ' + spec + ' (MonetPlan.specFor with the profile gives ' + at[0] + ', and ' + at[1] + ' at 2026): native, the setting only, no overlay', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === style && k.fab.length === 0, JSON.stringify([c.hint, k.fab.length]));
  }

  // ---- (c) Fidelity (More styles) at 2026 on P17 ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; });
  await phone({ sdk: 37, profile: 'P17', build: B17, contrast: 0 }); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('C1. P17, Fidelity 6750A4, no spec stored (2026), dark mode, contrast 0: overlayFabricate with 264 entries in SystemUI\'s order (the list made by hand: accent1-3 and neutral1-2, each shade _light then _dark; the 55 roles sorted by name, _light then _dark; the 12 fixed colours sorted by name, _light then _dark); no resource twice', k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 264 && A17_ORDER.length === 264 && new Set(names(plan)).size === 264 && sameOrder(plan, A17_ORDER), JSON.stringify([plan.action, names(plan).length, names(plan).slice(-4)]));
  check('   no unsuffixed palette or fixed name, no error palette shade, no name outside the list (no custom colour); 130 palette entries, 110 role entries, 24 fixed', aliases(plan).length === 0 && names(plan).every(r => A17_ORDER.includes(r)) && names(plan).filter(r => /_(accent[123]|neutral[12])_\d+_(light|dark)$/.test(r)).length === 130 && names(plan).filter(r => ROLES55.some(x => r === RES + x + '_light' || r === RES + x + '_dark')).length === 110 && names(plan).filter(r => FIXED.some(f => r === RES + f + '_light' || r === RES + f + '_dark')).length === 24, JSON.stringify(aliases(plan)));
  check('   ' + Object.keys(FID_0).length + ' values pinned from the libmonet android17-release oracle (spec 2026, contrast 0), light and dark: the shades, primary_light 0xFF4F378A (Fidelity falls back to 2021 there; 16 QPR2 gives ' + Q2_FID_PRIMARY_LIGHT + '), primary_dim_dark (written: 2026 was asked), control_activated_light, the fixed colours _light and _dark', pinsOff(plan, FID_0).length === 0 && byRes(plan).primary_light !== Q2_FID_PRIMARY_LIGHT, pinsOff(plan, FID_0).join('; '));
  check('   the lookups the Java side makes on 17 (OverlayRules.P17_LOOKUPS, as the mock mirrors them): the QPR five, then system_primary_fixed_dark', JSON.stringify(await lookups()) === JSON.stringify(['accent1_600_light', 'accent1_600_dark', 'primary_light', 'surface_dark', 'primary_dim_dark', 'primary_fixed_dark'].map(n => RES + n)), JSON.stringify(await lookups()));
  check('   the record: Fidelity, spec 2026, P17, the build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2026","profile":"P17","build":"' + B17 + '"}', await rec());
  const darkPlan = plan;
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C2. the same in light mode: the same 264 entries and values (both modes are in the plan; the mode at Apply changes nothing)', JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries), names(plan).length);
  await ev(() => { window.__dark = true; window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C3. contrast 0.5 (read when Apply is tapped): ' + Object.keys(FID_05).length + ' values pinned from the oracle at 0.5; the fixed colours\' _light and _dark differ there (primary_fixed_light 0xFF765FB4 from the light scheme, primary_fixed_dark 0xFFE9DDFF from the dark one); same order', pinsOff(plan, FID_05).length === 0 && FID_05.primary_fixed_light !== FID_05.primary_fixed_dark && sameOrder(plan, A17_ORDER), pinsOff(plan, FID_05).join('; '));
  await ev(() => { window.__contrast = 0; kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); });
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C4. Content (More styles) B5651D at 2026, contrast 0: 264 in order; ' + Object.keys(CON_0).length + ' values pinned from the oracle', sameOrder(plan, A17_ORDER) && pinsOff(plan, CON_0).length === 0, pinsOff(plan, CON_0).join('; '));

  // ---- (d) 2025 written as an overlay; CMF ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2025'); ovlPickColor('6750A4'); ovlRender(); }); c = await card();
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D1. Fidelity at 2025 on P17: written, 264 in order (the dims too), the same ' + Object.keys(FID_0).length + ' values (the oracle\'s 2025 rows equal its 2026 ones); CMF is not offered at 2025; the record says 2025', sameOrder(plan, A17_ORDER) && pinsOff(plan, FID_0).length === 0 && !c.live.includes('CMF') && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2025","profile":"P17","build":"' + B17 + '"}', JSON.stringify([pinsOff(plan, FID_0), c.live, await rec()]));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_accent_secondary', '00A0FF'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('FFDE3F'); window.__dark = true; ovlRender(); }); c = await card();
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D2. Tonal Spot FFDE3F at 2025 with a secondary accent (so it is written): the hint says this style\'s colors (they are Android\'s own, plus the accent) and the accent\'s sentence follows; 264 in order; ' + Object.keys(TSY_0).length + ' accent1 / neutral / primary / fixed values pinned from the oracle (the accent touches accent2 and the secondary roles only): accent1_100 and primary_fixed _light 0xFFF6E292 from the light scheme, _dark 0xFFF3E2A2 from the dark one',
    c.hint === 'Also writes this style\'s colors with root.' && c.accHint !== '' && sameOrder(plan, A17_ORDER) && pinsOff(plan, TSY_0).length === 0 && TSY_0.primary_fixed_light !== TSY_0.primary_fixed_dark, JSON.stringify([c.hint, c.accHint, pinsOff(plan, TSY_0)]));
  check('   the record keeps the spec chosen (2025) and the accent', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","accents":{"secondary":"00A0FF"},"profile":"P17","build":"' + B17 + '"}', await rec());
  let p = await preview(), r;
  check('   the preview in dark mode shows the dark scheme\'s accent1 (shade 100 #F3E2A2, 500 #847741)', p.rows === 5 && p.accent1[3] === '#F3E2A2' && p.accent1[7] === '#847741', JSON.stringify(p.accent1));
  await ev(() => { window.__dark = false; ovlRender(); }); p = await preview();
  check('   in light mode the light scheme\'s (#F6E292, #867732)', p.rows === 5 && p.accent1[3] === '#F6E292' && p.accent1[7] === '#867732', JSON.stringify(p.accent1));
  await ev(() => { window.__dark = true; kvSet('ovl_accent_secondary', '1B6EF3'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; r = byRes(plan);
  check('D2b. the same with the secondary accent 1B6EF3 (each mode\'s scheme takes the accent from its own mode): 264 in order; ' + Object.keys(ACC_1B).length + ' accent2 values, _light and _dark, are the oracle\'s accent1 of Tonal Spot 1B6EF3 at 2025 (they differ between the modes from shade 200 on); accent1 / neutral / primary / fixed still the oracle\'s without the accent; accent2_200 and secondary _light and _dark all moved from the plan without it',
    sameOrder(plan, A17_ORDER) && pinsOff(plan, ACC_1B).length === 0 && ACC_1B.accent2_200_light !== ACC_1B.accent2_200_dark && pinsOff(plan, TSY_0).length === 0 && Object.keys(TSY_NOACC).every(n => r[n] && r[n] !== TSY_NOACC[n]), JSON.stringify([pinsOff(plan, ACC_1B), pinsOff(plan, TSY_0), Object.keys(TSY_NOACC).map(n => n + ' ' + r[n])]));
  await ev(() => { window.__dark = true; kvSet('ovl_accent_secondary', ''); kvSet('ovl_spec', '2026'); kvSet('ovl_root_style', 'CMF'); ovlPickColor('6750A4'); ovlRender(); }); c = await card();
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D3. CMF (More styles) 6750A4 at 2026: the hint names CMF; 264 in order; ' + Object.keys(CMF_0).length + ' values pinned from the oracle (libmonet a17\'s SchemeCmf)', c.hint === 'Also writes the CMF colors with root, in place of the style above.' && sameOrder(plan, A17_ORDER) && pinsOff(plan, CMF_0).length === 0, JSON.stringify([c.hint, pinsOff(plan, CMF_0)]));

  // ---- (e) spec 2021 on 17 ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); }); c = await card();
  check('E1. P17, Tonal Spot in the list at 2021: not native: the hint names its 2021 colors in place of Android\'s; the preview shows; no spec note under 2021', c.hint === 'Also writes this style\'s 2021 colors with root, in place of Android\'s.' && c.preview && c.specNote === '' && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2021"]', JSON.stringify([c.hint, c.preview, c.specNote]));
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('   Apply: the setting (TONAL_SPOT), then 256 entries: the list without the four dim roles (2021 does not define them), in order; no unsuffixed name', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && names(plan).length === 256 && sameOrder(plan, A17_ORDER_2021) && aliases(plan).length === 0, JSON.stringify([names(plan).length, names(plan).filter(r => DIMS.some(d => r.includes(d)))]));
  check('   ' + Object.keys(TS21_0).length + ' values pinned from the oracle at 2021, contrast 0; the record says Tonal Spot (2021 is not written into a record), P17', pinsOff(plan, TS21_0).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","profile":"P17","build":"' + B17 + '"}', JSON.stringify([pinsOff(plan, TS21_0), await rec()]));
  await ev(() => { window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('E2. at contrast 0.5: 256 in order; ' + Object.keys(TS21_05).length + ' values pinned from the oracle at 0.5 (fixed _light and _dark differ)', sameOrder(plan, A17_ORDER_2021) && pinsOff(plan, TS21_05).length === 0, pinsOff(plan, TS21_05).join('; '));
  for (const st of ['VIBRANT', 'EXPRESSIVE', 'SPRITZ']) {
    await ev(s => { window.__contrast = 0; ovlPickStyle(s); ovlRender(); }, st); c = await card(); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
    check('   ' + st + ' in the list at 2021: not native either: the same hint; Apply writes the setting (' + st + '), then 256 entries in order; ' + Object.keys(V21_0[st]).length + ' values pinned from the oracle at 2021 (each differs from its 2026 one); the record says ' + st,
      c.hint === 'Also writes this style\'s 2021 colors with root, in place of Android\'s.' && k.apply.length === 1 && k.apply[0].style === st && sameOrder(plan, A17_ORDER_2021) && pinsOff(plan, V21_0[st]).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"' + st + '","profile":"P17","build":"' + B17 + '"}', JSON.stringify([c.hint, names(plan).length, pinsOff(plan, V21_0[st]), await rec()]));
  }
  await ev(() => { window.__contrast = 0; ovlPickStyle('TONAL_SPOT'); kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const fid21Pins = Object.fromEntries(Object.entries(FID_0).filter(([n]) => !DIMS.some(d => n.startsWith(d))));
  check('E3. Fidelity (More styles) at 2021: 256 entries in order, the hint names Fidelity, the record has no spec; its other values are those of 2026 (' + Object.keys(fid21Pins).length + ' pins: Fidelity falls back to 2021 at 2026 too)', sameOrder(plan, A17_ORDER_2021) && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.' && pinsOff(plan, fid21Pins).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P17","build":"' + B17 + '"}', JSON.stringify([names(plan).length, c.hint, pinsOff(plan, fid21Pins), await rec()]));
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickStyle('RAINBOW'); kvSet('ovl_adj_accent_sat', 150); ovlRender(); }); c = await card(); const hR = c.hint;
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); c = await card(); const hRb = c.hint;
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  await ev(() => { kvSet('ovl_black', false); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card(); const hT = c.hint;
  await ev(() => { kvSet('ovl_adj_accent_sat', 100); ovlRender(); });
  check('E4. Rainbow at 2021 with an Intensity slider (written for the slider; its 2021 colours are Android\'s own): "' + hR + '", with Pure black "' + hRb + '", 256 entries; Tonal Spot at 2021 with the slider: "' + hT + '"',
    hR === 'Also writes this style\'s colors with root, adjusted by the Intensity sliders.' && hRb === 'Also writes this style\'s colors, adjusted by the Intensity sliders, and pure black with root.' && names(plan).length === 256 && hT === 'Also writes this style\'s 2021 colors with root, adjusted by the Intensity sliders.', JSON.stringify([hR, hRb, names(plan).length, hT]));
  await ev(() => { kvSet('ovl_spec', '2026'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('FFDE3F'); kvSet('ovl_adj_accent_sat', 0); window.__dark = true; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; r = byRes(plan);
  const notGrey = ['accent1', 'accent2', 'accent3'].map(row => Object.keys(GREY).map(sh => [row + '_' + sh + '_light', row + '_' + sh + '_dark'].filter(n => r[n] !== GREY[sh])).flat()).flat();
  check('E5. Tonal Spot FFDE3F at 2026 with Accent saturation at 0 (written for the slider; at 100 its colours are Android\'s own and differ between the modes): 264 in order; shades 100 to 900 of the three accent rows, _light and _dark, are the greys of their tones (worked out from L*, 0xFFE2E2E2 at shade 100 to 0xFF1B1B1B at 900); the neutral rows stay the oracle\'s (neutral1_900_light, neutral1_10_light)',
    sameOrder(plan, A17_ORDER) && notGrey.length === 0 && r.neutral1_900_light === TSY_0.neutral1_900_light && r.neutral1_10_light === TSY_0.neutral1_10_light, JSON.stringify([notGrey, r.neutral1_900_light]));
  await ev(() => { kvSet('ovl_adj_accent_sat', 100); kvSet('ovl_accurate_shades', '0'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; r = byRes(plan);
  const acc0 = ['1', '2', '3'].map(i => ['light', 'dark'].filter(m => r['accent' + i + '_100_' + m] !== TSY26_300['accent' + i + '_300_' + m]).map(m => i + m)).flat();
  check('E6. the same with Accurate shades off (and the slider back at 100): written (the hint: this style\'s colors); 264 in order; shade 300 of each accent row the oracle\'s, _light and _dark (accent1_300 0xFFBDAB61 light, 0xFFBAAB70 dark); shade 100 of each row takes its own mode\'s shade 300; primary_fixed_light and _dark and secondary_fixed _light and _dark (tone 90 there) the tone-70 colour of their mode; tertiary_fixed (not tone 90) and the neutral rows unchanged',
    c.hint === 'Also writes this style\'s colors with root.' && sameOrder(plan, A17_ORDER) && pinsOff(plan, TSY26_300).length === 0 && acc0.length === 0 && r.primary_fixed_light === TSY26_300.accent1_300_light && r.primary_fixed_dark === TSY26_300.accent1_300_dark && r.secondary_fixed_light === TSY26_300.accent2_300_light && r.secondary_fixed_dark === TSY26_300.accent2_300_dark && r.tertiary_fixed_light === '0xFFFDC891' && r.tertiary_fixed_dark === '0xFFFDC891' && r.neutral1_900_light === TSY_0.neutral1_900_light,
    JSON.stringify([c.hint, pinsOff(plan, TSY26_300), acc0, r.primary_fixed_light, r.primary_fixed_dark, r.secondary_fixed_light, r.tertiary_fixed_light]));
  await ev(() => { kvSet('ovl_accurate_shades', '1'); ovlPickColor('6750A4'); ovlRender(); });

  // ---- (f) Pure black on 17 ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2026'); kvSet('ovl_black', true); ovlPickStyle('TONAL_SPOT'); ovlRender(); });
  c = await card(); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('F1. P17, Pure black alone (Tonal Spot at 2026 is native): the hint names pure black; 11 entries, all _dark names (system_neutral1_900_dark, system_neutral2_900_dark, the nine dark roles), black and the greys', c.hint === 'Also writes the pure black overlay with root.' && JSON.stringify(names(plan)) === JSON.stringify(BLACK_DARK.map(n => RES + n)) && byRes(plan).neutral1_900_dark === '0xFF000000' && byRes(plan).surface_container_dark === '0xFF181818' && byRes(plan).surface_bright_dark === '0xFF2C2C2C', JSON.stringify(names(plan)));
  check('   the record: black, no style, P17, the build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","profile":"P17","build":"' + B17 + '"}', await rec());
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; r = byRes(plan);
  check('F2. Pure black over Fidelity on P17: still 264 in SystemUI\'s order; the dark shades and backgrounds black, surface_container_dark grey; the _light values stay the oracle\'s (neutral1_900_light 0xFF1D1B20, surface_light 0xFFFDF7FF), the rest Fidelity\'s (primary_dark, primary_fixed_dark)', sameOrder(plan, A17_ORDER) && ['neutral1_900_dark', 'neutral2_900_dark', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818' && r.neutral1_900_light === FID_0.neutral1_900_light && r.surface_light === FID_0.surface_light && r.primary_dark === FID_0.primary_dark && r.primary_fixed_dark === FID_0.primary_fixed_dark && r.neutral2_900_light !== '0xFF000000', JSON.stringify([r.neutral1_900_light, r.neutral2_900_light, r.surface_light]));
  await ev(() => { kvSet('ovl_black', false); });

  // ---- (g) the accent picker's start at the phone's own spec ----
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); });
  const sheet = () => ev(() => ({ open: document.getElementById('colorPickModal').classList.contains('show'), sub: document.getElementById('cpSub').innerText, hex: document.getElementById('cpHex1').value }));
  await ev(() => { kvSet('ovl_spec', null); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); const sh = await sheet(); await ev(() => closeColorPicker());
  await ev(() => { kvSet('ovl_spec', '2021'); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); const sh21 = await sheet(); await ev(() => { closeColorPicker(); kvSet('ovl_spec', null); ovlRender(); });
  check('G1. P17, Tonal Spot 6750A4 in the list, dark, no spec stored (2026, native): the Tertiary accent\'s picker starts at the key colour Android\'s own scheme gives (#956989, the oracle\'s palette row at 2026); at 2021 (written with root) at #996A79 (the oracle at 2021)', sh.open && /#956989 /.test(sh.sub) && sh.hex.toUpperCase().includes('956989') && sh21.open && /#996A79 /.test(sh21.sub), JSON.stringify([sh, sh21]));

  // ---- (h) the record and the note for colours written on another build ----
  await ev(([id, bq]) => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', spec: '2025', profile: 'P16Q2', build: bq }); ovlRender(); }, [AAM, BQ2]);
  await reset(); await phone({ sdk: 37, profile: 'P17', build: B17 }); c = await card(); k = await calls();
  check('H1. a record written on 16 QPR2 (P16Q2), the phone now on 17: the note says Android was updated; nothing is written by itself', c.stale === STALE && c.note === 'The theme overlay is written on this phone.' && k.fab.length === 0 && k.apply.length === 0, JSON.stringify([c.stale, k.fab.length]));
  await reset(); await apply(); c = await card();
  check('   Apply writes them for 17: the record says P17 and its build, and the note goes', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","spec":"2026","profile":"P17","build":"' + B17 + '"}' && c.stale === '', JSON.stringify([await rec(), c.stale]));
  await ev(() => { window.__dark = false; ovlRender(); }); c = await card(); await ev(() => { window.__dark = true; ovlRender(); });
  check('H2. a switch to light mode after the write: no note (both modes are in the overlay); the hint is the same', c.stale === '' && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify([c.stale, c.hint]));
  await phone({ sdk: 37, profile: 'P17', build: B17b }); c = await card();
  check('   a monthly update that stays 17 (another build, P17): no note, Apply would write the same colours', c.stale === '', c.stale);
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card();
  check('   with only the list style at the phone\'s spec (native): the hint says Apply removes the overlay', c.hint === 'Also removes the theme overlay.', c.hint);
  await reset(); await apply(); k = await calls();
  check('   Apply there: the setting, then a disable of the overlay; the record is cleared', k.apply.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify(k.fab));

  // ---- (i) Undo and Default with a 17 overlay; the resume path ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_root_applied', null); ovlRender(); });
  await phone({ sdk: 37, profile: 'P17', build: B17, contrast: 0 }); await reset(); await apply();
  const first = ((await calls()).fab[0] || {}).entries;
  await ev(() => { kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply();
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('I1. Undo of a Content write after a Fidelity one on P17: the theme is put back and Fidelity\'s 264 entries are written again, as they were', k.restore.length === 1 && k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 264 && JSON.stringify(plan.entries) === JSON.stringify(first) && (await rec()).includes('"style":"FIDELITY"'), JSON.stringify([k.restore.length, k.fab.length, names(plan).length]));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply();
  const first21 = ((await calls()).fab[0] || {}).entries || [];
  await ev(() => { kvSet('ovl_spec', null); kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); const fid = ((await calls()).fab[0] || {}).entries || [];
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('I2. Undo of a Fidelity write (2026, 264) made after a Tonal Spot one at 2021 (256, its record has no spec), with no spec stored now (the page\'s default is 2026 on 17): the 256 entries of the 2021 write are written again, as they were', first21.length === 256 && fid.length === 264 && k.fab.length === 1 && plan.action === 'fabricate' && JSON.stringify(plan.entries) === JSON.stringify(first21) && (await rec()).includes('"style":"TONAL_SPOT"') && !(await rec()).includes('"spec"'), JSON.stringify([first21.length, fid.length, k.fab.length, names(plan).length, await rec()]));
  await reset(); dialogs.length = 0; await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('I3. Default with the overlay on: the question says it is switched off too; the setting is reset and the overlay disabled; the record is cleared', dialogs.length === 1 && dialogs[0].includes('The theme overlay is switched off too.') && k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([dialogs, k.fab]));

  // the resume path (T-068): an Apply of Fidelity at 2026 on P17 whose theme answer is lost to a restart; the restarted page, the theme read
  // back as written, completes that Apply's overlay half with the 264-entry plan in SystemUI's order
  const KEY = 'theme_customization_overlay_packages';
  const a17 = () => { window.__root = true; window.__sdk = 37; window.__profile = 'P17'; window.__build = 'google/mock/mock:17/CP1A.260605.009/15230418:user/release-keys'; window.__dark = true; window.__contrast = 0; };
  const openWith = async (kvs, fn, arg) => {
    const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
    p2.on('pageerror', e => errors.push(e.message));
    await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, kvs);
    await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript); await p2.addInitScript(a17); await p2.addInitScript(fn, arg);
    return p2;
  };
  const p1 = await openWith(JSON.stringify({ ovl_root_style: '"FIDELITY"' }), () => {}, null);
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
  check('I4. the resume path on P17: Fidelity tapped with no spec stored (2026), the theme answer lost to a restart (nothing written yet, the resume record plans the overlay); the restarted page, the theme read back as written, writes that overlay: 264 entries in SystemUI\'s order; the record says Fidelity, 2026, P17; the resume record is done',
    lost.fab === 0 && !!lost.resume && lost.resume.root === 'fabricate' && rs.fab.length === 1 && rf.action === 'fabricate' && JSON.stringify(rf.names) === JSON.stringify(A17_ORDER) && !!rs.applied && rs.applied.style === 'FIDELITY' && rs.applied.spec === '2026' && rs.applied.profile === 'P17' && rs.done,
    JSON.stringify([lost.fab, lost.resume && lost.resume.root, rs.fab.map(f => f.action + ':' + f.names.length), rs.applied && rs.applied.spec, rs.applied && rs.applied.profile, rs.done]));

  // ---- (j) the new words in German ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', null); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); ovlRender(); });
  await ev(() => I18N.set('de')); await sleep(600); await phone({ sdk: 37, profile: 'P17', build: B17 }); await sleep(200); c = await card();
  const de = await ev(keys => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return keys.map(k => x[k] || ''); }, [SPEC_SUB_A17, SPEC_DESC_A17[1], SPEC_DESC_A17[2], SPEC_NOTE_A17]);
  check('J1. in German: the 17 Color spec line, the 2025 and 2026 descriptions and the note under 2026 read as assets/lang/de.js has them', de.every(t => t !== '') && c.spec.sub === de[0] && c.specs[1].desc === de[1] && c.specs[2].desc === de[2] && c.specNote === de[3], JSON.stringify([de, c.spec.sub, c.specs.map(s => s.desc), c.specNote]));
  await ev(() => I18N.set('en')); await sleep(400);

  // ---- (k) the mock's name check (OverlayRules.fabricatePlanProblem on 17) ----
  const fabTry = (prof, res) => ev(([prof, res]) => { window.__profile = prof; return window.AndroidBridge.overlayFabricate('t572k', JSON.stringify({ action: 'fabricate', entries: [{ resource: res, argb: '0xFF000000' }] })); }, [prof, res]);
  const kq = await fabTry('P17', RES + 'on_tertiary_fixed_variant'), kq2 = await fabTry('P16Q2', RES + 'on_tertiary_fixed_variant'), kq3 = await fabTry('P17', RES + 'primary_fixed_variant'), kq4 = await fabTry('P17', RES + 'accent1_500');
  await settle(); await sleep(100); await ev(() => { window.__profile = 'P17'; });
  check('K1. the bridge (mock of OverlayRules) on P17 refuses an unsuffixed fixed colour ("' + kq + '"), takes it on P16Q2, takes a look-alike (system_primary_fixed_variant), refuses an unsuffixed palette shade',
    kq === 'error: Entry 1 names a fixed color without _light or _dark, which this Android version does not take' && kq2 === 'started' && kq3 === 'started' && /^error: Entry 1 names a palette color without _light or _dark/.test(kq4), JSON.stringify([kq, kq2, kq3, kq4]));

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
