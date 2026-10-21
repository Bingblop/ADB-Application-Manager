// Root colours on Android 14 QPR1 to QPR3 (T-050, T-049 PR 12: profile P14Q): the Overlays tab's root-only theme controls are live there
// and write what SystemUI writes on those builds. P14Q takes Android 14's paths in the page (165 entries, SystemUI's names and order with the
// four lookups first, palettes unsuffixed from the mode the phone is in, native spec 2021) and Android 14's palettes and shades, with the
// roles of libmonet android14-qpr*-release (the library ThemeOverlayController imports from QPR1 on): fixed surface tones, ContrastCurve
// (1, 1, 3, 7) and (3, 4.5, 7, 11), ToneDeltaPair 15, and Albers for Fidelity and Content. (a) Live on P14Q (MonetProfile.java reports it
// ok on SDK 34 with config_repairModeSupported): every row can be used; the lines are those of Android 14 (Pure black's with light mode's
// darkest text, Color spec's four styles, 2021 the phone's own and chosen with nothing stored, no note under it). Live as P14 is: an APK
// from before PR 12 on a QPR build (it reports P14 and writes 14.0's colours, as before), an older APK without a profile on 14, and P14Q
// without the colour maths (Pure black only, the style rows say Update the app). A One UI phone on 14 QPR is live, with the One UI note (PR 13). Locked: P14Q reported notYet, P14Q with a
// monet.js that does not name it (it would refuse the profile); without Super User: Needs Super User. (b) Native routing at 2021: every
// list style with nothing else set is the theme setting only; at 2025 Rainbow and Fruit Salad stay native and Tonal Spot is written.
// (c) Fidelity (More styles) at 2021: 165 entries, names and order equal to the list below made by hand (Android 14's plan, as on
// 14.0: the roles _dark then _light as createDynamicOverlay writes them, the four lookups moved first), the shades Android 14's (t565's P14 pins),
// roles and fixed colours pinned from the a14q oracle at contrast 0 and 0.5, where 10 and 42 of the 100 differ from 14.0's (P14's); the
// mode at Apply changes nothing at 2021. Content and Monochrome at contrast 0 (outline_light differs from 14.0's); Tonal Spot written at
// 2021 for a tertiary accent at contrast 0 and 1 (its primary, secondary, neutral and error side pinned; all 165 values, the accent's
// included, equal to monet.js with P14Q and the accent, this app's rule with no Android reference); Vibrant with the Intensity
// sliders. (d) 2025 and 2026 give what P14 gives (no Android reference: 14 never builds them); Fidelity at 2025 keeps its 2021 a14q colours.
// (e) Pure black: unsuffixed, alone and over a style. (f) The accent picker starts at the oracle's key colour (Android 14's palettes, as on
// 14.0). (g) The record and the note for colours written on another build: a 14.0 record (no profile) on QPR2, a QPR2 record after a
// monthly update to QPR3 (no note), a QPR record on 15.0, a record without a profile written on QPR2 by an APK from before PR 12 (no
// note on QPR2, the note after the update to QPR3). (h) Undo, Default and the resume path. (i) The credit line in German.
// Every role and fixed-colour value pinned below comes from an oracle kept outside the repository and not from this app's code: AOSP
// external/libmonet android14-qpr*-release (tree 9e15d1a7, the same on qpr1, qpr2, qpr3 and d2) compiled as it is, driven as
// ThemeOverlayController android14-qpr3-release drives it (dynamicSchemeFromStyle; createDynamicOverlay: the 44 roles of DynamicColors.kt
// _dark then _light with MaterialDynamicColors(isExtendedFidelity = false), the 12 fixed colours from the light scheme), transcribed
// (pr12-oracle, A14qDump: a14q.tsv). FIDELITY and CONTENT, which SystemUI never builds, are libmonet's SchemeFidelity and SchemeContent
// with the same write sequence. The shade pins and the 14.0 values beside them are t565's pins of monet.js's P14 (Android 14's own
// SystemUI monet, which QPR1 to QPR3 keep for the palettes and shades: ColorScheme.kt differs only by an annotation, Shades.java not at all).
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
// the lines the card shows on Android 14 (those of Android 14 to 16.0)
const BLACK_SUB = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors, except that its darkest text becomes pure black.';
const SPEC_SUB = 'Recolors Tonal Spot, Vibrant, Expressive and Spritz with root. CMF needs 2026.';
const SPEC_NOTE = 'Rainbow, Fruit Salad, Fidelity, Content and Monochrome keep their 2021 colors. The base shades follow light or dark mode as the phone is when you tap Apply; after switching, tap Apply again.';
const SPEC_DESC = ['The colors Android computes itself.', 'Google\'s newer tuning of the same styles.', 'The 2025 tuning, plus the CMF style.'];
const FID_HINT = 'Also writes the Fidelity colors with root, in place of the style above.';
// builds (Build.FINGERPRINT) the mock reports: 14.0, 14 QPR2, 14 QPR3 (a later update of the same profile), 15.0
const B14 = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
const BQ2 = 'google/mock/mock:14/AP1A.240305.019.A1/11445699:user/release-keys';
const BQ3 = 'google/mock/mock:14/AP2A.240605.024/11860263:user/release-keys';
const B15 = 'google/mock/mock:15/AP3A.240905.015/12231197:user/release-keys';
// The plan's order on Android 14 (P14's, PR 3's, which P14Q keeps): the five palettes accent1, accent2, accent3, neutral1, neutral2, each
// with its 13 shades in order (0, 10, 50, 100 ... 1000), unsuffixed; then the 44 roles of DynamicColors.kt (the same list on 14.0 and
// QPR1 to QPR3), each _dark, then each _light (createDynamicOverlay assigns the dark scheme first: the oracle's sequence); then the 12 fixed
// colours, unsuffixed. The plan moves the four entries the Java side looks up first (OVL_ROOT_LEAD), the rest in this order
const LEAD = ['accent1_600', 'neutral1_500', 'primary_light', 'surface_dark'].map(n => RES + n);
const PAL_ROWS = ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2'];
const SHADES = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];
const ROLES44 = ['primary_container', 'on_primary_container', 'primary', 'on_primary', 'secondary_container', 'on_secondary_container', 'secondary', 'on_secondary', 'tertiary_container', 'on_tertiary_container', 'tertiary', 'on_tertiary', 'background', 'on_background', 'surface', 'on_surface', 'surface_container_low', 'surface_container_lowest', 'surface_container', 'surface_container_high', 'surface_container_highest', 'surface_bright', 'surface_dim', 'surface_variant', 'on_surface_variant', 'outline', 'outline_variant', 'error', 'on_error', 'error_container', 'on_error_container', 'control_activated', 'control_normal', 'control_highlight', 'text_primary_inverse', 'text_secondary_and_tertiary_inverse', 'text_primary_inverse_disable_only', 'text_secondary_and_tertiary_inverse_disabled', 'text_hint_inverse', 'palette_key_color_primary', 'palette_key_color_secondary', 'palette_key_color_tertiary', 'palette_key_color_neutral', 'palette_key_color_neutral_variant'];
const FIXED14 = ['primary_fixed', 'primary_fixed_dim', 'on_primary_fixed', 'on_primary_fixed_variant', 'secondary_fixed', 'secondary_fixed_dim', 'on_secondary_fixed', 'on_secondary_fixed_variant', 'tertiary_fixed', 'tertiary_fixed_dim', 'on_tertiary_fixed', 'on_tertiary_fixed_variant'];
const ORDER = PAL_ROWS.map(r => SHADES.map(s => r + '_' + s)).flat().concat(ROLES44.map(r => r + '_dark'), ROLES44.map(r => r + '_light'), FIXED14).map(n => RES + n);
const PLAN_ORDER = LEAD.concat(ORDER.filter(r => !LEAD.includes(r)));
// Android 14's shades for Fidelity 6750A4 and Content B5651D (t565's P14 pins: the shade rule of 14.0, which QPR1 to QPR3 keep)
const SHADES_FID = { accent1_500: '0xFF7F68BE', accent1_900: '0xFF22005D', accent2_500: '0xFF7B7195', accent3_500: '0xFF90721C', neutral1_500: '0xFF78757C', neutral2_500: '0xFF797481' };
const SHADES_CON = { accent1_500: '0xFFB1621A', accent3_500: '0xFF757C0C', neutral1_500: '0xFF80746C' };
// Oracle pins (resource suffix: value), a14q: libmonet android14-qpr*-release, spec 2021. FID: FIDELITY 6750A4, contrast 0. The first ten
// differ from 14.0's (the on_* roles: ContrastCurve(3, 4.5, 7, 11) and Albers' containers; outline_light: the tone the curve gives), the
// rest are the same on both
const Q_FID_0 = { on_primary_container_light: '0xFFFFFFFF', on_primary_container_dark: '0xFFFEF8FF', on_primary_light: '0xFFFFFFFF', on_secondary_container_light: '0xFF4A4162',
  on_secondary_container_dark: '0xFFDCCFF8', on_tertiary_container_light: '0xFFFFFFFF', on_tertiary_container_dark: '0xFF2A1F00', on_tertiary_light: '0xFFFFFFFF', on_tertiary_dark: '0xFF3E2E00',
  outline_light: '0xFF7A7582',
  primary_light: '0xFF513A8D', primary_dark: '0xFFCFBCFF', surface_light: '0xFFFDF7FF', surface_dark: '0xFF141218', outline_dark: '0xFF948E9C', palette_key_color_primary_light: '0xFF8069BF',
  primary_fixed: '0xFFE9DDFF', on_primary_fixed: '0xFF22005D', secondary_fixed_dim: '0xFFCDC0E9' };
const Q_FID_0_DIFF = Object.keys(Q_FID_0).slice(0, 10);
const P14_FID_OUTLINE_LIGHT = '0xFF77727F';   // t565: 14.0's outline_light for Fidelity 6750A4
// FID at contrast 0.5: 42 of the 100 differ from 14.0's; these among them
const Q_FID_05 = { primary_light: '0xFF4B3386', primary_dark: '0xFFD3C1FF', primary_container_dark: '0xFF9A83DB', on_primary_container_dark: '0xFF000000', on_primary_container_light: '0xFFFFFFFF',
  on_surface_dark: '0xFFFFF9FF', outline_variant_dark: '0xFF86818E', outline_variant_light: '0xFF7E7885', error_container_dark: '0xFFFF5449', on_error_container_dark: '0xFF000000',
  tertiary_container_light: '0xFF876A13', primary_fixed_dim: '0xFF644DA1', tertiary_fixed_dim: '0xFF735800', surface_dark: '0xFF141218' };
// CON: CONTENT B5651D, contrast 0 (nine differ from 14.0's: the first seven here; the rest the same)
const Q_CON_0 = { on_primary_container_light: '0xFFFFFFFF', on_primary_container_dark: '0xFFFFFFFF', on_secondary_container_light: '0xFF5E3B1F', on_secondary_container_dark: '0xFFFCC6A0',
  on_tertiary_container_light: '0xFFFFFFFF', on_tertiary_light: '0xFFFFFFFF', outline_light: '0xFF877366',
  primary_light: '0xFF7E3F00', primary_dark: '0xFFFFB781', surface_dark: '0xFF19120D', tertiary_light: '0xFF4F5400', palette_key_color_tertiary_dark: '0xFF787F11', primary_fixed: '0xFFFFDCC5' };
const P14_CON_OUTLINE_LIGHT = '0xFF847164';   // t565: 14.0's outline_light for Content B5651D
// MONO: MONOCHROMATIC 6750A4, contrast 0 (only outline_light differs from 14.0's)
const Q_MONO_0 = { outline_light: '0xFF777777', primary_light: '0xFF000000', primary_dark: '0xFFFFFFFF', on_primary_dark: '0xFF1B1B1B', surface_dark: '0xFF131313', outline_dark: '0xFF919191',
  palette_key_color_primary_light: '0xFF070707', palette_key_color_neutral_dark: '0xFF070707', primary_fixed: '0xFF5E5E5E' };
const P14_MONO_OUTLINE_LIGHT = '0xFF747474';  // t565: 14.0's outline_light for Monochrome 6750A4
// TS: TONAL_SPOT 6750A4: the entries a tertiary accent leaves alone (it touches accent3 and the tertiary roles only). Contrast 0: only
// outline_light differs from 14.0's (0xFF77737D there); contrast 1: 35 of the 100 differ, these among them
const Q_TS_0 = { outline_light: '0xFF7A757F', primary_light: '0xFF65558F', primary_dark: '0xFFCFBDFE', on_primary_container_light: '0xFF201047', on_primary_container_dark: '0xFFE9DDFF',
  on_secondary_container_light: '0xFF1E192B', palette_key_color_primary_light: '0xFF7E6EA9', primary_fixed: '0xFFE9DDFF', surface_dark: '0xFF141218' };
const Q_TS_1 = { on_primary_container_dark: '0xFF000000', primary_dark: '0xFFFFF9FF', on_primary_light: '0xFFFFFFFF', on_secondary_dark: '0xFF000000', on_surface_light: '0xFF000000',
  on_surface_dark: '0xFFFFFFFF', on_surface_variant_light: '0xFF25232B', on_error_dark: '0xFF000000', on_error_container_light: '0xFFFFFFFF', primary_fixed_dim: '0xFF322359',
  on_primary_fixed: '0xFFFFFFFF', secondary_fixed_dim: '0xFF2F2A3D', surface_dark: '0xFF141218', surface_container_highest_dark: '0xFF36343A', outline_variant_dark: '0xFFCEC8D4' };
// the oracle's key colours of Fidelity 00FF00's secondary palette and Tonal Spot 6750A4's tertiary one (palette_key_color_*)
const Q_FID_GREEN_KEY2 = '038E00', Q_TS_KEY3 = '996A79';
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
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  // the phone: { sdk, profile, reason, build, contrast } (reason left out follows the profile as ovl_mock says: ok for P14 and P14Q)
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
      said: (document.getElementById('ovlApplyHintAccents') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '' };
  });
  const lockedAll = (c, reason) => c.shown && ['black', 'spec', 'more'].every(k => c[k].locked && c[k].lock === reason) && c.live.length === 0 && c.chips === 0;
  const LIVE = '["ovlBlackSw","2021","2025","2026","OFF","FIDELITY","CONTENT","MONOCHROMATIC","ovlAdjAccentSat","ovlAdjBgSat","ovlAdjBgLight","ovlAccSecondary","ovlAccTertiary","ovlAccurateSw"]';
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const names = plan => ((plan && plan.entries) || []).map(e => e.resource);
  const sameOrder = (plan, order) => JSON.stringify(names(plan)) === JSON.stringify(order);
  // the plan against MonetPlan.entries with the given opts, the four lookups moved first as the page does: 'same', or what differs
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => {
    const want = new Map(MonetPlan.entries(style, seed, opts).map(e => [e.resource, e.argb]));
    if (!entries || entries.length !== want.size) return 'length ' + (entries || []).length + ' != ' + want.size;
    const off = entries.filter(e => want.get(e.resource) !== e.argb).map(e => e.resource);
    return off.length ? off.slice(0, 5).join(' ') : 'same';
  }, [plan && plan.entries, style, seed, opts]);
  // the plan's names in the order 14.0's plan has them (MonetPlan.entries with P14, the four lookups moved first)
  const sameOrderP14 = (plan, style, seed) => ev(([n, style, seed, lead]) => { const all = MonetPlan.entries(style, seed, { spec: '2021', dark: true, profile: 'P14', contrast: 0 }).map(e => e.resource); return JSON.stringify(n) === JSON.stringify(lead.concat(all.filter(r => !lead.includes(r)))); }, [names(plan), style, seed, LEAD]);
  // MonetPlan.entries for P14 (14.0) as { suffix: value }
  const p14 = (style, seed, contrast, extra) => ev(([style, seed, o]) => Object.fromEntries(MonetPlan.entries(style, seed, o).map(e => [e.resource.replace('android:color/system_', ''), e.argb])), [style, seed, Object.assign({ spec: '2021', dark: true, profile: 'P14', contrast }, extra || {})]);
  // the forms Android 14 never takes: a palette shade with _light or _dark, an error palette shade, a fixed colour with _light or _dark
  const foreign = plan => names(plan).filter(r => /^android:color\/system_(accent[123]|neutral[12])_\d+_(light|dark)$/.test(r) || /^android:color\/system_error_\d+/.test(r) || /_fixed(_dim|_variant)?_(light|dark)$/.test(r));
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });

  // ---- (a) live on P14Q; what stays live as P14; the locks ----
  await ev(() => switchView('overlays')); await sleep(300);
  await setRoot(true);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); kvSet('ovl_root_applied', null); window.__dark = true; });
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); let c = await card(); const reason = await ev(() => ovlPalette.profileReason);
  check('A1. Android 14 QPR2 (P14Q) with Super User (reported ok): every row is live (the two switches, the two radio groups, the three Intensity sliders, the two accent entries, and the preview\'s 65 swatches)', reason === 'ok' && c.shown && !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.preview, JSON.stringify([reason, c.live, c.chips]));
  check('   its lines are Android 14\'s: Pure black with light mode\'s darkest text, Color spec\'s four styles, 2021 the colors Android computes itself and chosen with no spec stored, no note under it; the hint names Fidelity; the page computes for P14Q, not on the 16 QPR paths', c.black.sub === BLACK_SUB && c.spec.sub === SPEC_SUB && JSON.stringify(c.specs.map(s => s.desc)) === JSON.stringify(SPEC_DESC) && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2021"]' && c.specNote === '' && c.hint === FID_HINT && JSON.stringify(await ev(() => [ovlMonetProfile(), ovlQpr(), ovlA17(), ovlNativeSpec()])) === '["P14Q",false,false,"2021"]', JSON.stringify([c.black.sub, c.spec.sub, c.specs, c.specNote, c.hint]));
  const AS_P14 = [
    ['an APK from before PR 12 on a QPR build (it reports P14)', { sdk: 34, profile: 'P14', build: BQ2 }],
    ['an older APK on Android 14 (no profile, build or contrast)', { sdk: 34, profile: null, build: null, contrast: null }]
  ];
  for (const [label, o] of AS_P14) {
    await phone(o); c = await card();
    check('A2. ' + label + ': live, computed as 14.0 (P14), the rows and lines of A1', !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.hint === FID_HINT && c.black.sub === BLACK_SUB && (await ev(() => ovlMonetProfile())) === 'P14', JSON.stringify([c.live, await ev(() => ovlMonetProfile())]));
  }
  // without the colour maths (monet.js not loaded): P14Q as P14, Pure black alone
  const noMaths = async prof => {
    await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = null; });
    await phone({ sdk: 34, profile: prof, build: BQ2 }); const r = await card();
    await ev(() => { window.MonetPlan = window.__mp; ovlRender(); });
    return r;
  };
  const nmQ = await noMaths('P14Q'), nm14 = await noMaths('P14');
  const strip = x => JSON.stringify([x.black, x.spec, x.more, x.live, x.chips, x.hint, x.preview]);
  check('A3. P14Q without the colour maths (monet.js not loaded): as on P14, Pure black is live and More styles and Color spec say Update the app; the card equals P14\'s', !nmQ.black.locked && nmQ.more.locked && nmQ.more.lock === 'Update the app' && nmQ.spec.locked && nmQ.spec.lock === 'Update the app' && JSON.stringify(nmQ.live) === '["ovlBlackSw"]' && strip(nmQ) === strip(nm14), JSON.stringify([strip(nmQ), strip(nm14)]));
  const LOCKS = [
    ['P14Q reported notYet (a family the APK says it cannot compute)', { sdk: 34, profile: 'P14Q', reason: 'notYet', build: BQ2 }, NOT_YET]
  ];
  for (const [label, o, why] of LOCKS) {
    await phone(o); c = await card(); await reset(); await apply(); const k = await calls();
    check('A4. ' + label + ': locked with "' + why + '", nothing in the card can be used, no hint, no preview; Apply writes the theme setting only', lockedAll(c, why) && !c.preview && c.hint === '' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([c.black, c.live, k.fab.length]));
  }
  // PR 13: a One UI phone on this build is served by its Android version (the One UI lock is gone): live as any phone of the build, with the
  // One UI note; the same build without One UI has no note
  {
    const oneUiNote = () => ev(() => { const e = document.getElementById('ovlRootOneUi'); return e && e.style.display !== 'none' ? e.innerText : ''; });
    await ev(() => { window.__oneUi = '8.5'; kvSet('ovl_root_applied', null); }); await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); const o = await card(); const on = await oneUiNote(); await reset(); await apply(); const k = await calls();
    check('A4b. a One UI phone on Android 14 QPR (P14Q) with Super User: every row is live, as on any phone of that build; the One UI note shows; Apply writes the overlay', !o.black.locked && !o.spec.locked && !o.more.locked && JSON.stringify(o.live) === LIVE && o.chips === 65 && on === ONE_UI_NOTE && k.fab.length === 1 && k.fab[0].action === 'fabricate', JSON.stringify([o.live, o.chips, on, k.fab.length]));
    await ev(() => { window.__oneUi = ''; kvSet('ovl_root_applied', null); }); await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); const off = await oneUiNote();
    check('   the same build without One UI: no One UI note', off === '', off);
  }
  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = Object.assign({}, window.__mp, { PROFILES: window.__mp.PROFILES.filter(p => p !== 'P14Q') }); });
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); c = await card(); await reset(); await apply(); let k = await calls();
  const p14Still = await ev(() => { window.__profile = 'P14'; ovlReadPalette(); return ovlRootLock(); });
  await ev(() => { window.MonetPlan = window.__mp; });
  check('A5. P14Q with a monet.js whose PROFILES does not name it (that file would refuse the profile): locked with "' + NOT_YET + '", Apply writes the theme setting only; P14 stays live with it', lockedAll(c, NOT_YET) && k.apply.length === 1 && k.fab.length === 0 && p14Still === '', JSON.stringify([c.black, k.fab.length, p14Still]));
  await setRoot(false); await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); c = await card();
  check('A6. P14Q without Super User: Needs Super User (as on every profile this app computes), its lines, the note about Root', lockedAll(c, 'Needs Super User') && c.black.sub === BLACK_SUB && c.spec.sub === SPEC_SUB && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await setRoot(true);

  // ---- (b) native routing ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', null); ovlRender(); });
  for (const style of ['TONAL_SPOT', 'VIBRANT', 'EXPRESSIVE', 'SPRITZ', 'RAINBOW', 'FRUIT_SALAD']) {
    await ev(st => { ovlPickStyle(st); ovlRender(); }, style); c = await card(); await reset(); await apply(); k = await calls();
    check('B1. P14Q, ' + style + ' in the list, no spec stored (2021, the phone\'s own), nothing else on: native, no hint, no preview, the theme setting only, no overlay call', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === style && k.fab.length === 0 && (await kv('ovl_root_applied')) === null, JSON.stringify([c.hint, c.preview, k.fab.length]));
  }
  for (const style of ['RAINBOW', 'FRUIT_SALAD']) {
    await ev(st => { kvSet('ovl_spec', '2025'); ovlPickStyle(st); ovlRender(); }, style); c = await card(); await reset(); await apply(); k = await calls();
    check('   ' + style + ' at 2025 (MonetPlan.specFor with P14Q gives ' + (await ev(st => MonetPlan.specFor(st, '2025', 'P14Q'), style)) + '): still native; the note under 2025 is the one with the light / dark sentence', c.hint === '' && k.apply.length === 1 && k.fab.length === 0 && c.specNote === SPEC_NOTE, JSON.stringify([c.hint, k.fab.length, c.specNote]));
  }
  await ev(() => { kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card();
  check('   Tonal Spot at 2025: written (2021 is the phone\'s own): the hint says its 2025 colors, the preview shows', c.hint === 'Also writes this style\'s 2025 colors with root, in place of Android\'s.' && c.preview, JSON.stringify([c.hint, c.preview]));

  // ---- (c) Fidelity, Content, Monochrome, Tonal Spot and Vibrant at 2021 ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); });
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2, contrast: 0 }); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('C1. P14Q, Fidelity 6750A4 at 2021, dark mode, contrast 0: overlayFabricate with 165 entries, the four lookups first and then Android 14\'s order (the list made by hand: accent1-3, neutral1-2 with their shades in order, unsuffixed; the 44 roles _dark, then _light; the 12 fixed colours unsuffixed), equal to 14.0\'s plan; no resource twice', k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 165 && new Set(names(plan)).size === 165 && sameOrder(plan, PLAN_ORDER) && (await sameOrderP14(plan, 'FIDELITY', '6750A4')), JSON.stringify([plan.action, names(plan).length, names(plan).slice(0, 6)]));
  check('   no palette shade or fixed colour with _light or _dark, no error palette shade, none of the six roles 15 QPR2 added; the Java side\'s lookups are the first four', foreign(plan).length === 0 && !names(plan).some(r => /_(inverse_surface|inverse_on_surface|inverse_primary|shadow|scrim|surface_tint)_(light|dark)$/.test(r)) && JSON.stringify(names(plan).slice(0, 4)) === JSON.stringify(LEAD), JSON.stringify(foreign(plan)));
  check('   the shades are Android 14\'s (t565\'s P14 pins: accent1_500 0xFF7F68BE, tone 49.6), the 65 the same as 14.0\'s plan', pinsOff(plan, SHADES_FID).length === 0 && (await ev(([e]) => { const r = Object.fromEntries(MonetPlan.entries('FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0 }).map(x => [x.resource, x.argb])); return e.filter(x => /_(accent[123]|neutral[12])_\d+$/.test(x.resource)).every(x => r[x.resource] === x.argb); }, [plan.entries || []])), pinsOff(plan, SHADES_FID).join('; '));
  let r14 = await p14('FIDELITY', '6750A4', 0);
  check('   ' + Object.keys(Q_FID_0).length + ' values pinned from the a14q oracle: on_primary_container_light 0xFFFFFFFF, on_secondary_container_light 0xFF4A4162, on_tertiary_container_dark 0xFF2A1F00, outline_light 0xFF7A7582, ...; primary_light 0xFF513A8D, surface_dark, the key colour, the fixed colours', pinsOff(plan, Q_FID_0).length === 0, pinsOff(plan, Q_FID_0).join('; '));
  check('   the first ' + Q_FID_0_DIFF.length + ' of them are not 14.0\'s (MonetPlan.entries with P14: on_primary_container_light ' + r14.on_primary_container_light + ', outline_light ' + r14.outline_light + ', t565\'s 14.0 pin ' + P14_FID_OUTLINE_LIGHT + '); the record says Fidelity, no spec, P14Q, the build', Q_FID_0_DIFF.every(n => r14[n] !== Q_FID_0[n]) && r14.outline_light === P14_FID_OUTLINE_LIGHT && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P14Q","build":"' + BQ2 + '"}', await rec());
  const toastsC1 = await ev(() => window.__toasts.slice());
  check('   the mock judged the four lookups (the plan\'s first four) and the write: "Theme overlay written"', toastsC1.includes('Theme overlay written'), JSON.stringify(toastsC1));
  const darkPlan = plan;
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C2. the same in light mode: the same 165 entries and values (at 2021 the shades are the same in both modes)', JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries), names(plan).length);
  await ev(() => { window.__dark = true; window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  r14 = await p14('FIDELITY', '6750A4', 0.5);
  const diff05 = names(plan).filter(n => !/_(accent[123]|neutral[12])_\d+$/.test(n) && byRes(plan)[n.replace(RES, '')] !== r14[n.replace(RES, '')]).length;
  check('C3. contrast 0.5 (read when Apply is tapped): ' + Object.keys(Q_FID_05).length + ' values pinned from the a14q oracle at 0.5 (primary_light 0xFF4B3386, the third lookup; outline_variant_dark 0xFF86818E: ContrastCurve(1, 1, 3, 7); error_container_dark 0xFFFF5449); ' + diff05 + ' of the 100 roles and fixed colours differ from 14.0\'s (42 in the oracle); same order', pinsOff(plan, Q_FID_05).length === 0 && diff05 === 42 && r14.primary_light !== Q_FID_05.primary_light && sameOrder(plan, PLAN_ORDER) && plan.entries[2].argb === Q_FID_05.primary_light, pinsOff(plan, Q_FID_05).join('; '));
  await ev(() => { window.__contrast = 0; kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  r14 = await p14('CONTENT', 'B5651D', 0);
  check('C4. Content (More styles) B5651D at 2021, contrast 0: 165 in order; Android 14\'s shades (t565); ' + Object.keys(Q_CON_0).length + ' values pinned from the a14q oracle (on_secondary_container_light 0xFF5E3B1F, 14.0\'s ' + r14.on_secondary_container_light + '; outline_light 0xFF877366, t565\'s 14.0 pin ' + P14_CON_OUTLINE_LIGHT + ')', sameOrder(plan, PLAN_ORDER) && pinsOff(plan, SHADES_CON).length === 0 && pinsOff(plan, Q_CON_0).length === 0 && r14.outline_light === P14_CON_OUTLINE_LIGHT && Object.keys(Q_CON_0).slice(0, 7).every(n => r14[n] !== Q_CON_0[n]), pinsOff(plan, Q_CON_0).concat(pinsOff(plan, SHADES_CON)).join('; '));
  await ev(() => { kvSet('ovl_root_style', 'MONOCHROMATIC'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C5. Monochrome (More styles) 6750A4 at 2021: 165 in order; ' + Object.keys(Q_MONO_0).length + ' values pinned from the a14q oracle: outline_light 0xFF777777 (t565\'s 14.0 pin ' + P14_MONO_OUTLINE_LIGHT + '), the rest as on 14.0 (the key colours 0xFF070707)', sameOrder(plan, PLAN_ORDER) && pinsOff(plan, Q_MONO_0).length === 0 && (await p14('MONOCHROMATIC', '6750A4', 0)).outline_light === P14_MONO_OUTLINE_LIGHT, pinsOff(plan, Q_MONO_0).join('; '));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_accent_tertiary', 'FFD600'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card(); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  const tsAcc = c => ({ spec: '2021', dark: true, profile: 'P14Q', contrast: c, accents: { tertiary: 'FFD600' } });
  const ts0 = await sameAs(plan, 'TONAL_SPOT', '6750A4', tsAcc(0));
  check('C6. Tonal Spot 6750A4 in the list at 2021 with a tertiary accent, contrast 0: written (the hint says its colors, the accents line names the tertiary one); the setting (TONAL_SPOT), then 165 in order; ' + Object.keys(Q_TS_0).length + ' values the accent leaves alone pinned from the a14q oracle (outline_light 0xFF7A757F, 14.0\'s 0xFF77737D); all 165, those the accent changes included, equal to MonetPlan.entries with P14Q and the accent (this app\'s rule, no Android reference: ' + ts0 + '); the record keeps the accent', c.hint === 'Also writes this style\'s colors with root.' && c.said === 'Tertiary accent: your color.' && k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && sameOrder(plan, PLAN_ORDER) && pinsOff(plan, Q_TS_0).length === 0 && ts0 === 'same' && (await p14('TONAL_SPOT', '6750A4', 0, { accents: { tertiary: 'FFD600' } })).outline_light === '0xFF77737D' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accents":{"tertiary":"FFD600"},"profile":"P14Q","build":"' + BQ2 + '"}', JSON.stringify([c.hint, c.said, pinsOff(plan, Q_TS_0), await rec()]));
  await ev(() => { window.__contrast = 1; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  r14 = await p14('TONAL_SPOT', '6750A4', 1, { accents: { tertiary: 'FFD600' } });
  const ts1 = await sameAs(plan, 'TONAL_SPOT', '6750A4', tsAcc(1));
  check('C7. the same at contrast 1: ' + Object.keys(Q_TS_1).length + ' values pinned from the a14q oracle (on_primary_container_dark 0xFF000000, 14.0\'s ' + r14.on_primary_container_dark + '; on_surface_variant_light 0xFF25232B; primary_fixed_dim 0xFF322359; the surfaces at their fixed tones); all 165 equal to MonetPlan.entries with P14Q, contrast 1 and the accent (' + ts1 + ')', sameOrder(plan, PLAN_ORDER) && pinsOff(plan, Q_TS_1).length === 0 && ts1 === 'same' && ['on_primary_container_dark', 'on_surface_variant_light', 'primary_fixed_dim', 'on_error_dark'].every(n => r14[n] !== Q_TS_1[n]), pinsOff(plan, Q_TS_1).join('; '));
  await ev(() => { window.__contrast = 0; kvSet('ovl_accent_tertiary', ''); ovlRender(); });
  const ADJ = { accentSat: 150, bgSat: 50, bgLight: 120 }, adjOpts = p => ({ spec: '2021', dark: true, profile: p, contrast: 0, adjust: ADJ });
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_adj_accent_sat', 150); kvSet('ovl_adj_bg_sat', 50); kvSet('ovl_adj_bg_light', 120); ovlPickStyle('VIBRANT'); ovlPickColor('6750A4'); ovlRender(); });
  c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const vib14 = await sameAs(plan, 'VIBRANT', '6750A4', adjOpts('P14'));
  check('C8. Vibrant 6750A4 in the list at 2021 with Intensity 150 / 50 / 120: written, 165 in order, MonetPlan.entries with P14Q and the sliders (this app\'s rule, no Android reference); not 14.0\'s (' + vib14 + ')', sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'VIBRANT', '6750A4', adjOpts('P14Q'))) === 'same' && vib14 !== 'same', JSON.stringify([names(plan).length, vib14]));
  await ev(() => { ['ovl_adj_accent_sat', 'ovl_adj_bg_sat', 'ovl_adj_bg_light'].forEach(k => { delete window.AndroidBridge.__kv[k]; }); ovlRender(); });

  // ---- (d) 2025 and 2026 on 14 QPR1 to QPR3 ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); }); c = await card();
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const ts25d = plan;
  check('D1. Tonal Spot 6750A4 at 2025, dark mode: the note under 2025 is the one with the light / dark sentence; 165 in order; exactly what 14.0 gets (MonetPlan.entries with P14: no Android reference exists for 2025); the record says spec 2025, P14Q', c.specNote === SPEC_NOTE && sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P14', contrast: 0 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","profile":"P14Q","build":"' + BQ2 + '"}', JSON.stringify([c.specNote, await rec()]));
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const shadeDiff = names(plan).filter(r => /_(accent[123]|neutral[12])_\d+$/.test(r) && byRes(plan)[r.replace(RES, '')] !== byRes(ts25d)[r.replace(RES, '')]);
  check('D2. the same in light mode: the 65 shades come from the light scheme (' + shadeDiff.length + ' of them differ from the dark write); the 100 roles and fixed colours are the same; 14.0\'s light plan', shadeDiff.length > 0 && names(plan).filter(r => !/_(accent[123]|neutral[12])_\d+$/.test(r)).every(r => byRes(plan)[r.replace(RES, '')] === byRes(ts25d)[r.replace(RES, '')]) && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: false, profile: 'P14', contrast: 0 })) === 'same', JSON.stringify(shadeDiff));
  await ev(() => { window.__dark = true; kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D3. Fidelity at 2025 keeps its 2021 colours (Google\'s rule), with the a14q rules: the values of C1 (on_primary_container_light 0xFFFFFFFF), the record without a spec', pinsOff(plan, Q_FID_0).length === 0 && JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries) && c.hint === FID_HINT && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P14Q","build":"' + BQ2 + '"}', JSON.stringify([pinsOff(plan, Q_FID_0), await rec()]));
  await ev(() => { kvSet('ovl_spec', '2026'); kvSet('ovl_root_style', 'CMF'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D4. CMF (More styles) at 2026: 165 in order, exactly 14.0\'s (MonetPlan.entries with P14); the hint names CMF; the record says CMF, 2026, P14Q', sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'CMF', '6750A4', { spec: '2026', dark: true, profile: 'P14', contrast: 0 })) === 'same' && c.hint === 'Also writes the CMF colors with root, in place of the style above.' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"CMF","spec":"2026","profile":"P14Q","build":"' + BQ2 + '"}', JSON.stringify([c.hint, await rec()]));

  // ---- (e) Pure black ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_black', true); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('E1. P14Q, Pure black alone (Tonal Spot at 2021 is native): the hint names pure black; the 11 entries of Android 14 (system_neutral1_900 and system_neutral2_900 unsuffixed, the nine dark roles); the record: black, no style, P14Q', c.hint === 'Also writes the pure black overlay with root.' && JSON.stringify(plan.entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(34))) && names(plan)[0] === RES + 'neutral1_900' && names(plan)[1] === RES + 'neutral2_900' && names(plan).length === 11 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","profile":"P14Q","build":"' + BQ2 + '"}', JSON.stringify([c.hint, names(plan).slice(0, 3), await rec()]));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('E2. Pure black over Fidelity: still 165 in order, surface_dark (fourth) black, neutral1_900, neutral2_900 and background_dark black, surface_container_dark grey; the rest Fidelity\'s a14q values (on_primary_container_light 0xFFFFFFFF, on_tertiary_container_dark 0xFF2A1F00)', sameOrder(plan, PLAN_ORDER) && plan.entries[3].argb === '0xFF000000' && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818' && r.on_primary_container_light === Q_FID_0.on_primary_container_light && r.on_tertiary_container_dark === Q_FID_0.on_tertiary_container_dark, JSON.stringify([r.surface_dark, r.on_primary_container_light]));
  await ev(() => { kvSet('ovl_black', false); });

  // ---- (f) the accent picker starts at the oracle's key colour ----
  const sheet = () => ev(() => ({ open: document.getElementById('colorPickModal').classList.contains('show'), sub: document.getElementById('cpSub').innerText, hex: document.getElementById('cpHex1').value }));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); ovlPickColor('00FF00'); ovlRender(); ovlAccOpen('secondary'); }); await sleep(150); const sh = await sheet(); await ev(() => closeColorPicker());
  check('F1. P14Q, Fidelity 00FF00 at 2021: the Secondary accent\'s picker starts at #' + Q_FID_GREEN_KEY2 + ', the a14q oracle\'s key colour of that palette (Android 14\'s palettes, as on 14.0)', sh.open && new RegExp('#' + Q_FID_GREEN_KEY2 + ' ').test(sh.sub) && sh.hex.toUpperCase().includes(Q_FID_GREEN_KEY2), JSON.stringify(sh));
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); const shTs = await sheet(); await ev(() => closeColorPicker());
  check('   Tonal Spot 6750A4 in the list (native at 2021): the Tertiary accent\'s picker starts at #' + Q_TS_KEY3 + ' (the a14q oracle\'s palette_key_color_tertiary)', shTs.open && new RegExp('#' + Q_TS_KEY3 + ' ').test(shTs.sub), JSON.stringify(shTs));

  // ---- (g) the record and the note for colours written on another build ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); kvSet('ovl_root_applied', null); ovlPickColor('6750A4'); });
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); await reset(); await apply(); c = await card();
  check('G1. written on 14 QPR2 (P14Q): no note about another build; the record says P14Q and its build', c.stale === '' && (await rec()).includes('"profile":"P14Q","build":"' + BQ2 + '"'), JSON.stringify([c.stale, await rec()]));
  await reset(); await phone({ sdk: 34, profile: 'P14Q', build: BQ3 }); c = await card(); k = await calls();
  check('   an update to 14 QPR3 (another build, P14Q on both sides): no note, nothing written by itself', c.stale === '' && k.fab.length === 0 && k.apply.length === 0, JSON.stringify([c.stale, k.fab.length]));
  await phone({ sdk: 35, profile: 'P15', build: B15 }); c = await card();
  check('   the phone now runs 15.0 (P15): the note says Android was updated', c.stale === STALE && c.note === 'The theme overlay is written on this phone.', JSON.stringify([c.stale, c.note]));
  // a record written on 14.0 (P14: the record has no profile, as every P14 record) and the phone updated to QPR2
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', build: b }); }, [AAM, B14]);
  await phone({ sdk: 34, profile: 'P14', build: B14 }); c = await card();
  check('G2. a record written on 14.0 (P14, no profile in it), the phone still on 14.0: no note', c.stale === '', c.stale);
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); c = await card();
  check('   the phone updated to 14 QPR2 (P14Q): the note shows (10 of Fidelity\'s roles differ at contrast 0)', c.stale === STALE, c.stale);
  await reset(); await apply(); c = await card();
  check('   Apply writes them for QPR2: the record says P14Q and its build, and the note goes', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P14Q","build":"' + BQ2 + '"}' && c.stale === '', JSON.stringify([await rec(), c.stale]));
  // a record with no profile written on a QPR build by an APK from before PR 12 (which reported P14 there), read by this one on QPR2
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', build: b }); }, [AAM, BQ2]);
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2 }); c = await card();
  check('G3. a record without a profile written on 14 QPR2 (an APK from before PR 12), read on the same build as P14Q: no note (same build)', c.stale === '', c.stale);
  await phone({ sdk: 34, profile: 'P14Q', build: BQ3 }); c = await card();
  check('   the phone then updated to 14 QPR3 (P14Q): the note shows (the record says P14, the phone P14Q)', c.stale === STALE, c.stale);

  // ---- (h) Undo, Default and the resume path ----
  await phone({ sdk: 34, profile: 'P14Q', build: BQ2, contrast: 0 }); await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_root_style', 'FIDELITY'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply();
  const first = ((await calls()).fab[0] || {}).entries;
  await ev(() => { kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply();
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('H1. Undo of a Content write after a Fidelity one on P14Q: the theme is put back and Fidelity\'s 165 entries are written again, as they were (the a14q values of C1)', k.restore.length === 1 && k.fab.length === 1 && plan.action === 'fabricate' && JSON.stringify(plan.entries) === JSON.stringify(first) && pinsOff(plan, Q_FID_0).length === 0 && (await rec()).includes('"style":"FIDELITY"'), JSON.stringify([k.restore.length, k.fab.length, names(plan).length]));
  await reset(); dialogs.length = 0; await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('H2. Default with the overlay on: the question says it is switched off too; the setting is reset and the overlay disabled; the record is cleared', dialogs.length === 1 && dialogs[0].includes('The theme overlay is switched off too.') && k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([dialogs, k.fab]));

  // the resume path (T-068): an Apply of Fidelity at 2021 on P14Q whose theme answer is lost to a restart; the restarted page, the theme read
  // back as written, completes that Apply's overlay half with the 165-entry plan
  const KEY = 'theme_customization_overlay_packages';
  const a14q = () => { window.__root = true; window.__sdk = 34; window.__profile = 'P14Q'; window.__build = 'google/mock/mock:14/AP1A.240305.019.A1/11445699:user/release-keys'; window.__dark = true; window.__contrast = 0; };
  const openWith = async (kvs, fn, arg) => {
    const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
    p2.on('pageerror', e => errors.push(e.message));
    await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, kvs);
    await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript); await p2.addInitScript(a14q); await p2.addInitScript(fn, arg);
    return p2;
  };
  const p1 = await openWith(JSON.stringify({ ovl_root_style: '"FIDELITY"', ovl_spec: '"2021"' }), () => {}, null);
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
  check('H3. the resume path on P14Q: Fidelity at 2021 tapped, the theme answer lost to a restart (nothing written yet, the resume record plans the overlay); the restarted page, the theme read back as written, writes that overlay: 165 entries, the lookups first, then Android 14\'s order; the record says Fidelity, P14Q; the resume record is done',
    lost.fab === 0 && !!lost.resume && lost.resume.root === 'fabricate' && rs.fab.length === 1 && rf.action === 'fabricate' && JSON.stringify(rf.names) === JSON.stringify(PLAN_ORDER) && !!rs.applied && rs.applied.style === 'FIDELITY' && !rs.applied.spec && rs.applied.profile === 'P14Q' && rs.done,
    JSON.stringify([lost.fab, lost.resume && lost.resume.root, rs.fab.map(f => f.action + ':' + f.names.length), rs.applied, rs.done]));

  // ---- (i) the credit line in German ----
  const CREDIT = 'More styles and Color spec: on Android 14 the Fidelity, Content and Monochrome colors are computed with a port of Android 14\'s own color engine, from the <1>Android Open Source Project</1> and Google\'s <2>Material Color Utilities</2> 0.4.0; on Android 15 and 16 those colors, and the 2025 and 2026 specs and CMF everywhere, with a port of Material Color Utilities on GitHub (main, commit bd811e4, 2026). On Android 14 QPR1 to QPR3, 15.0, 16 QPR1, 16 QPR2 and 17 they also follow the changes in Android\'s own copy of that library (libmonet, Android Open Source Project). All Apache-2.0; the file monet.js names what was taken from where.';
  const tip = () => ev(() => { const t = Array.from(document.querySelectorAll('.about-tip')).find(e => /libmonet/.test(e.textContent)); return t ? t.textContent : ''; });
  const en = await tip();
  await ev(() => I18N.set('de')); await sleep(600);
  const de = await tip();
  const deWant = await ev(k => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return (x[k] || '').replace(/<\/?\d>/g, ''); }, CREDIT);
  check('I1. the More styles credit names the builds that follow libmonet (14 QPR1 to QPR3, 15.0, 16 QPR1, 16 QPR2, 17), and reads in German as assets/lang/de.js has it', en === CREDIT.replace(/<\/?\d>/g, '') && deWant !== '' && /Android 14 QPR1 bis QPR3, 15\.0, 16 QPR1, 16 QPR2 und 17/.test(deWant) && de === deWant, JSON.stringify([en, de, deWant]));
  await ev(() => I18N.set('en')); await sleep(300);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
