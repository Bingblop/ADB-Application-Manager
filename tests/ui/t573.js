// Root colours on Android 15.0 (T-049 PR 11: profile P15): the Overlays tab's root-only theme controls are live there and write what
// SystemUI writes on that build. 15.0 takes 15 QPR1's paths in the page (165 entries, SystemUI's names and order with the four lookups
// first, palettes unsuffixed from the mode the phone is in, native spec 2021), with libmonet android15-release's own 2021 maths: the
// on_primary/secondary/tertiary/error_container roles at light tone 10 with ContrastCurve(4.5, 7, 11, 21), and the key colour from a walk
// out from tone 50. (a) Live on P15 (MonetProfile.java reports it ok from this PR on): every row can be used; the lines are the ones of
// Android 14 to 16.0 (Pure black's with light mode's darkest text, Color spec's four styles, 2021 the phone's own and chosen with nothing
// stored, no note under it); a One UI phone on 15 too, with the One UI note (PR 13). Still locked: 15.0 as an APK from before this PR reports it (notYet), an SDK 35 build without its colour
// names (unknown), an older APK without a profile (Update the app); without Super User: Needs Super User. (b) Native routing
// at 2021: every list style with nothing else set is the theme setting only; at 2025 Rainbow and Fruit Salad stay native (2025 gives them
// 2021) and Tonal Spot is written. (c) Fidelity (More styles) at 2021: 165 entries, names and order equal to the list below made by hand
// from SystemUI's write order on android15-release (TOC createOverlays / assignColorsToOverlay: neutral1, neutral2, accent1-3 in its
// shade order, unsuffixed; Android 14's 44 roles _light and _dark; the 12 fixed colours unsuffixed; the four lookups moved first), values
// pinned from the a15 oracle at contrast 0 and 0.5; the four on_*_container roles are not 15 QPR1's for the same seed (P15Q1 pins from
// libmonet android15-qpr2-release); the mode at Apply changes nothing at 2021. Content; Monochrome, whose key colours are the walk's
// 0xFF070707 (the bisection gives 0xFF777777); Tonal Spot written at 2021 for a tertiary accent (its primary and secondary side pinned);
// Vibrant written at 2021 with the Intensity sliders, whose rebuilt neutral palette takes the a15 key colour (the walk) too.
// (d) 2025 written: Tonal Spot at 2025 gives what 15 QPR1 gives (no a15 reference for 2025: 15.0 never builds it), its 65 shades from
// the mode the phone is in (the note under 2025 says to tap Apply again after switching); Fidelity at 2025 keeps its 2021 a15 colours;
// CMF at 2026 is 15 QPR1's. (e) Pure black: unsuffixed, as on Android 14 to 16.0, alone and over a style. (f) The accent picker starts at
// the a15 key colour. (g) The record and the note for colours written on another build: a 15 QPR1 record on 15.0, a 15.0 record on 15
// QPR1, a monthly update (no note). (h) Undo, Default and the resume path. (i) The credit line in German.
// Every hex value pinned below comes from an oracle kept outside the repository and not from this app's code: SystemUI's ColorScheme,
// TonalPalette and DynamicColors of android15-release compiled on external/libmonet android15-release, with ThemeOverlayController's
// createOverlays / assignColorsToOverlay transcribed (pr11-oracle, PR 5's SysuiDump harness: main-a15.tsv); the P15Q1 values beside them
// from a dump of every MaterialDynamicColors method of libmonet android15-qpr2-release, the library 15 QPR1's SystemUI is built on
// (LibmonetDump: lm-android15-qpr2-release.tsv). FIDELITY, which SystemUI never builds, is libmonet's SchemeFidelity with the same write
// sequence.
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
// the lines the card shows on 15.0 (those of Android 14 to 16.0)
const BLACK_SUB = 'True black dark backgrounds, panels in a few dark greys. Light mode keeps its colors, except that its darkest text becomes pure black.';
const SPEC_SUB = 'Recolors Tonal Spot, Vibrant, Expressive and Spritz with root. CMF needs 2026.';
const SPEC_NOTE = 'Rainbow, Fruit Salad, Fidelity, Content and Monochrome keep their 2021 colors. The base shades follow light or dark mode as the phone is when you tap Apply; after switching, tap Apply again.';
const SPEC_DESC = ['The colors Android computes itself.', 'Google\'s newer tuning of the same styles.', 'The 2025 tuning, plus the CMF style.'];
const FID_HINT = 'Also writes the Fidelity colors with root, in place of the style above.';
// builds (Build.FINGERPRINT) the mock reports: 15.0, a monthly update of it, 15 QPR1
const B15 = 'google/mock/mock:15/AP3A.240905.015/12231197:user/release-keys';
const B15b = 'google/mock/mock:15/AP3A.241005.015/12366759:user/release-keys';
const BQ1 = 'google/mock/mock:15/AP4A.250105.002/12701944:user/release-keys';
// SystemUI's write order on android15-release, made by hand from ThemeOverlayController (createOverlays / assignColorsToOverlay) and
// SystemUI's DynamicColors read as text: the neutral overlay (neutral1, neutral2) and the accent overlay (accent1, accent2, accent3), each
// palette's 13 shades in the order of its HashMap, unsuffixed; Android 14's 44 roles (DynamicColors.allDynamicColorsMapped), each _light
// then _dark; the 12 fixed colours (getFixedColorsMapped), unsuffixed. The plan moves the four entries the Java side looks up first
// (OVL_ROOT_LEAD), the rest in this order (equal to the oracle's 165-name sequence with the four taken out)
const LEAD = ['accent1_600', 'neutral1_500', 'primary_light', 'surface_dark'].map(n => RES + n);
const PAL_ROWS = ['neutral1', 'neutral2', 'accent1', 'accent2', 'accent3'];
const SHADE_ORDER = [0, 800, 100, 900, 200, 1000, 10, 300, 400, 50, 500, 600, 700];
const ROLES44 = ['primary_container', 'on_primary_container', 'primary', 'on_primary', 'secondary_container', 'on_secondary_container', 'secondary', 'on_secondary', 'tertiary_container', 'on_tertiary_container', 'tertiary', 'on_tertiary', 'background', 'on_background', 'surface', 'on_surface', 'surface_container_low', 'surface_container_lowest', 'surface_container', 'surface_container_high', 'surface_container_highest', 'surface_bright', 'surface_dim', 'surface_variant', 'on_surface_variant', 'outline', 'outline_variant', 'error', 'on_error', 'error_container', 'on_error_container', 'control_activated', 'control_normal', 'control_highlight', 'text_primary_inverse', 'text_secondary_and_tertiary_inverse', 'text_primary_inverse_disable_only', 'text_secondary_and_tertiary_inverse_disabled', 'text_hint_inverse', 'palette_key_color_primary', 'palette_key_color_secondary', 'palette_key_color_tertiary', 'palette_key_color_neutral', 'palette_key_color_neutral_variant'];
const FIXED14 = ['primary_fixed', 'primary_fixed_dim', 'on_primary_fixed', 'on_primary_fixed_variant', 'secondary_fixed', 'secondary_fixed_dim', 'on_secondary_fixed', 'on_secondary_fixed_variant', 'tertiary_fixed', 'tertiary_fixed_dim', 'on_tertiary_fixed', 'on_tertiary_fixed_variant'];
const ORDER = PAL_ROWS.map(r => SHADE_ORDER.map(s => r + '_' + s)).flat().concat(ROLES44.map(r => [r + '_light', r + '_dark']).flat(), FIXED14).map(n => RES + n);
const PLAN_ORDER = LEAD.concat(ORDER.filter(r => !LEAD.includes(r)));
// Oracle pins (resource suffix: value), a15: android15-release, spec 2021. FID: FIDELITY 6750A4, dark mode
const A15_FID_0 = { accent1_100: '0xFFE9DDFF', accent1_500: '0xFF8069BF', accent2_500: '0xFF7C7296', accent3_500: '0xFF91731D', neutral1_900: '0xFF1D1B20', neutral2_500: '0xFF7A7582',
  primary_light: '0xFF4F378A', primary_dark: '0xFFCFBCFF', surface_dark: '0xFF141218', on_primary_container_light: '0xFFFFFFFF', on_primary_container_dark: '0xFFFFFFFF',
  on_secondary_container_light: '0xFF473E5F', on_secondary_container_dark: '0xFFEADFFF', on_tertiary_container_light: '0xFF2A1F00', on_tertiary_container_dark: '0xFF2A1F00',
  on_error_container_light: '0xFF410002', on_error_container_dark: '0xFFFFDAD6', palette_key_color_primary_light: '0xFF8069BF', primary_fixed: '0xFFE9DDFF', on_primary_fixed: '0xFF22005D',
  secondary_fixed_dim: '0xFFCDC0E9' };
// the same four roles on 15 QPR1 (libmonet android15-qpr2-release: light tone 30, ContrastCurve(3, 4.5, 7, 11)), same seed, contrast 0
const Q1_FID_0_ON = { on_primary_container_light: '0xFFE0D2FF', on_primary_container_dark: '0xFFE0D2FF', on_secondary_container_light: '0xFF645A7D', on_secondary_container_dark: '0xFFBFB2DA',
  on_tertiary_container_light: '0xFF503D00', on_tertiary_container_dark: '0xFF503D00', on_error_container_light: '0xFF93000A' };
const A15_FID_05 = { primary_light: '0xFF3E2578', primary_dark: '0xFFE3D6FF', outline_dark: '0xFFB6AFBD', on_primary_container_light: '0xFFFFFFFF', on_primary_container_dark: '0xFF000000',
  on_secondary_container_dark: '0xFF000000', on_tertiary_container_light: '0xFFFFFFFF', on_tertiary_container_dark: '0xFF000000', on_error_container_dark: '0xFF000000',
  primary_fixed: '0xFF765FB4', on_primary_fixed: '0xFFFFFFFF' };
const Q1_FID_05_ON = { on_tertiary_container_dark: '0xFF2A1F00' };
// CON: CONTENT B5651D, dark, contrast 0
const A15_CON_0 = { accent1_500: '0xFFB2631B', accent3_500: '0xFF767D0E', primary_light: '0xFF8F4900', primary_dark: '0xFFFFB781', surface_dark: '0xFF19120D',
  on_primary_container_light: '0xFFFFFFFF', on_tertiary_container_light: '0xFFFFFFFF', on_tertiary_container_dark: '0xFF000000', primary_fixed: '0xFFFFDCC5' };
const Q1_CON_0_ON = { on_primary_container_light: '0xFFFFFBFF', on_tertiary_container_light: '0xFFFFFFD8', on_tertiary_container_dark: '0xFF0B0C00' };
// MONO: MONOCHROMATIC 6750A4, dark, contrast 0: the key colours of the walk (libmonet a15 TonalPalette :71-100) are tone 2's grey
const A15_MONO_0 = { accent1_500: '0xFF777777', neutral1_500: '0xFF777777', primary_light: '0xFF000000', primary_dark: '0xFFFFFFFF', palette_key_color_primary_light: '0xFF070707',
  palette_key_color_primary_dark: '0xFF070707', palette_key_color_secondary_light: '0xFF070707', palette_key_color_tertiary_dark: '0xFF070707', palette_key_color_neutral_light: '0xFF070707',
  palette_key_color_neutral_variant_dark: '0xFF070707', on_primary_container_light: '0xFFFFFFFF', on_primary_container_dark: '0xFF000000' };
const Q1_MONO_KEY = '0xFF777777';   // libmonet android15-qpr2-release, MONOCHROME 6750A4: the primary and neutral palettes' key colour
// TS: TONAL_SPOT 6750A4, dark, contrast 0: the entries a tertiary accent leaves alone (it touches accent3 and the tertiary roles only)
const A15_TS_0 = { accent1_100: '0xFFE9DDFF', accent1_500: '0xFF7E6EA9', neutral1_500: '0xFF79767D', primary_light: '0xFF65558F', primary_dark: '0xFFCFBDFE',
  on_primary_container_light: '0xFF201047', on_primary_container_dark: '0xFFE9DDFF', on_secondary_container_light: '0xFF1E192B', on_secondary_container_dark: '0xFFE8DEF8',
  palette_key_color_primary_light: '0xFF7E6EA9', palette_key_color_secondary_dark: '0xFF7B748A', primary_fixed: '0xFFE9DDFF' };
const Q1_TS_0_ON = { on_primary_container_light: '0xFF4D3D75', on_secondary_container_light: '0xFF4A4458' };
// VIB: VIBRANT 6750A4 at 2021, dark, contrast 0, Intensity 150 / 50 / 120. The sliders rebuild the neutral palette at hue 298.98, chroma 5
// (10 x 50 %); libmonet android15-release's TonalPalette.fromHueAndChroma(298.98, 5).getKeyColor() is 0xFF7C787E (pr11-oracle/acc: KeyA15
// run on the compiled a15 library; the bisection gives 0xFF79767B). palette_key_color_neutral is that key colour's tone read through
// Background lightness 120 (this app's rule, no Android reference): 0xFF817D83, 0xFF7E7B81 from the bisection's key (15 QPR1's)
const A15_VIB_ADJ_KEYN = 'FF7C787E', VIB_ADJ = { palette_key_color_neutral_light: '0xFF817D83', palette_key_color_neutral_dark: '0xFF817D83' }, Q1_VIB_ADJ_KEYN = '0xFF7E7B81';
// the a15 key colour of Fidelity 00FF00's secondary palette (the oracle's palette_key_color_secondary: 0xFF038E00)
const A15_FID_GREEN_KEY2 = '038E00';
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
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  // the phone: { sdk, profile, reason, build, contrast } (reason left out follows the profile as ovl_mock says: ok for P15 and P15Q1)
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
  const preview = () => ev(() => { const pv = document.getElementById('ovlRootPreview'); const rows = pv ? Array.from(pv.querySelectorAll('.ovl-pal')) : []; return { rows: rows.length, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] }; });
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
  // the forms 15.0 never takes: a palette shade with _light or _dark, an error palette shade, a fixed colour with _light or _dark
  const foreign = plan => names(plan).filter(r => /^android:color\/system_(accent[123]|neutral[12])_\d+_(light|dark)$/.test(r) || /^android:color\/system_error_\d+/.test(r) || /_fixed(_dim|_variant)?_(light|dark)$/.test(r));
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });

  // ---- (a) live on P15; the locks that stay ----
  await ev(() => switchView('overlays')); await sleep(300);
  await setRoot(true);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', null); kvSet('ovl_root_applied', null); window.__dark = true; });
  await phone({ sdk: 35, profile: 'P15', build: B15 }); let c = await card(); const reason = await ev(() => ovlPalette.profileReason);
  check('A1. Android 15.0 (P15) with Super User (reported ok): every row is live (the two switches, the two radio groups, the three Intensity sliders, the two accent entries, and the preview\'s 65 swatches)', reason === 'ok' && c.shown && !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.preview, JSON.stringify([reason, c.live, c.chips]));
  check('   its lines are those of Android 14 to 16.0: Pure black with light mode\'s darkest text, Color spec\'s four styles, 2021 the colors Android computes itself and chosen with no spec stored, no note under it; the hint names Fidelity; the page computes for P15, not on the 16 QPR paths', c.black.sub === BLACK_SUB && c.spec.sub === SPEC_SUB && JSON.stringify(c.specs.map(s => s.desc)) === JSON.stringify(SPEC_DESC) && JSON.stringify(c.specs.filter(s => s.on).map(s => s.id)) === '["2021"]' && c.specNote === '' && c.hint === FID_HINT && JSON.stringify(await ev(() => [ovlMonetProfile(), ovlQpr(), ovlA17(), ovlNativeSpec()])) === '["P15",false,false,"2021"]', JSON.stringify([c.black.sub, c.spec.sub, c.specs, c.specNote, c.hint]));
  const LOCKS = [
    ['15.0 as an APK from before PR 11 reports it (P15, notYet)', { sdk: 35, profile: 'P15', reason: 'notYet', build: B15 }, NOT_YET],
    ['an SDK 35 build without its colour names (unknown)', { sdk: 35, profile: 'unknown', build: B15 }, NOT_YET],
    ['an older APK that reports no profile on Android 15', { sdk: 35, profile: null, build: null }, 'Update the app']
  ];
  for (const [label, o, why] of LOCKS) {
    await phone(o); c = await card(); await reset(); await apply(); const k = await calls();
    check('A2. ' + label + ': locked with "' + why + '", nothing in the card can be used, no hint, no preview; Apply writes the theme setting only', lockedAll(c, why) && !c.preview && c.hint === '' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([c.black, c.live, k.fab.length]));
  }
  // PR 13: a One UI phone on this build is served by its Android version (the One UI lock is gone): live as any phone of the build, with the
  // One UI note; the same build without One UI has no note
  {
    const oneUiNote = () => ev(() => { const e = document.getElementById('ovlRootOneUi'); return e && e.style.display !== 'none' ? e.innerText : ''; });
    await ev(() => { window.__oneUi = '8.5'; kvSet('ovl_root_applied', null); }); await phone({ sdk: 35, profile: 'P15', build: B15 }); const o = await card(); const on = await oneUiNote(); await reset(); await apply(); const k = await calls();
    check('A2b. a One UI phone on Android 15.0 (P15) with Super User: every row is live, as on any phone of that build; the One UI note shows; Apply writes the overlay', !o.black.locked && !o.spec.locked && !o.more.locked && JSON.stringify(o.live) === LIVE && o.chips === 65 && on === ONE_UI_NOTE && k.fab.length === 1 && k.fab[0].action === 'fabricate', JSON.stringify([o.live, o.chips, on, k.fab.length]));
    await ev(() => { window.__oneUi = ''; kvSet('ovl_root_applied', null); }); await phone({ sdk: 35, profile: 'P15', build: B15 }); const off = await oneUiNote();
    check('   the same build without One UI: no One UI note', off === '', off);
  }
  await setRoot(false); await phone({ sdk: 35, profile: 'P15', build: B15 }); c = await card();
  check('A3. P15 without Super User: Needs Super User (as on every profile this app computes), its lines, the note about Root', lockedAll(c, 'Needs Super User') && c.black.sub === BLACK_SUB && c.spec.sub === SPEC_SUB && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await setRoot(true);

  // ---- (b) native routing ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', null); ovlRender(); });
  let k;
  for (const style of ['TONAL_SPOT', 'VIBRANT', 'EXPRESSIVE', 'SPRITZ', 'RAINBOW', 'FRUIT_SALAD']) {
    await ev(st => { ovlPickStyle(st); ovlRender(); }, style); c = await card(); await reset(); await apply(); k = await calls();
    check('B1. P15, ' + style + ' in the list, no spec stored (2021, the phone\'s own), nothing else on: native, no hint, no preview, the theme setting only, no overlay call', c.hint === '' && !c.preview && k.apply.length === 1 && k.apply[0].style === style && k.fab.length === 0 && (await kv('ovl_root_applied')) === null, JSON.stringify([c.hint, c.preview, k.fab.length]));
  }
  for (const style of ['RAINBOW', 'FRUIT_SALAD']) {
    await ev(st => { kvSet('ovl_spec', '2025'); ovlPickStyle(st); ovlRender(); }, style); c = await card(); await reset(); await apply(); k = await calls();
    check('   ' + style + ' at 2025 (MonetPlan.specFor with P15 gives ' + (await ev(st => MonetPlan.specFor(st, '2025', 'P15'), style)) + '): still native; the note under 2025 is the one with the light / dark sentence', c.hint === '' && k.apply.length === 1 && k.fab.length === 0 && c.specNote === SPEC_NOTE, JSON.stringify([c.hint, k.fab.length, c.specNote]));
  }
  await ev(() => { kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card();
  check('   Tonal Spot at 2025: written (2021 is the phone\'s own): the hint says its 2025 colors, the preview shows', c.hint === 'Also writes this style\'s 2025 colors with root, in place of Android\'s.' && c.preview, JSON.stringify([c.hint, c.preview]));

  // ---- (c) Fidelity, Content, Monochrome and Tonal Spot at 2021 ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); });
  await phone({ sdk: 35, profile: 'P15', build: B15, contrast: 0 }); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('C1. P15, Fidelity 6750A4 at 2021, dark mode, contrast 0: overlayFabricate with 165 entries, the four lookups first and then SystemUI\'s order (the list made by hand: neutral1, neutral2, accent1-3 in its shade order, unsuffixed; Android 14\'s 44 roles _light then _dark; the 12 fixed colours unsuffixed); no resource twice', k.fab.length === 1 && plan.action === 'fabricate' && names(plan).length === 165 && new Set(names(plan)).size === 165 && sameOrder(plan, PLAN_ORDER), JSON.stringify([plan.action, names(plan).length, names(plan).slice(0, 6)]));
  check('   no palette shade or fixed colour with _light or _dark, no error palette shade, none of the six roles 15 QPR2 added; the Java side\'s lookups are the first four', foreign(plan).length === 0 && !names(plan).some(r => /_(inverse_surface|inverse_on_surface|inverse_primary|shadow|scrim|surface_tint)_(light|dark)$/.test(r)) && JSON.stringify(names(plan).slice(0, 4)) === JSON.stringify(LEAD), JSON.stringify(foreign(plan)));
  check('   ' + Object.keys(A15_FID_0).length + ' values pinned from the a15 oracle: the shades, primary_light 0xFF4F378A, the four on_*_container roles with a15\'s ContrastCurve(4.5, 7, 11, 21) (Fidelity takes foregroundTone for on_primary and on_secondary; light tone 10 applies to the list styles, see C6) (on_primary_container_light 0xFFFFFFFF, on_secondary_container_light 0xFF473E5F), the key colour, the fixed colours', pinsOff(plan, A15_FID_0).length === 0, pinsOff(plan, A15_FID_0).join('; '));
  check('   those on_*_container values are not 15 QPR1\'s for the same seed (libmonet android15-qpr2-release: on_primary_container_light 0xFFE0D2FF, on_secondary_container_light 0xFF645A7D, ...), and MonetPlan.entries with P15Q1 gives 15 QPR1\'s; the record says Fidelity, no spec, P15, the build', Object.keys(Q1_FID_0_ON).every(n => Q1_FID_0_ON[n] !== A15_FID_0[n] && byRes(plan)[n] === A15_FID_0[n]) && (await ev(([q]) => { const r = Object.fromEntries(MonetPlan.entries('FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P15Q1', contrast: 0 }).map(e => [e.resource.replace('android:color/system_', ''), e.argb])); return Object.keys(q).every(n => r[n] === q[n]); }, [Q1_FID_0_ON])) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P15","build":"' + B15 + '"}', await rec());
  const toastsC1 = await ev(() => window.__toasts.slice());
  check('   the mock judged the four lookups (the plan\'s first four) and the write: "Theme overlay written"', toastsC1.includes('Theme overlay written'), JSON.stringify(toastsC1));
  const darkPlan = plan;
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C2. the same in light mode: the same 165 entries and values (at 2021 the shades are the same in both modes)', JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries), names(plan).length);
  await ev(() => { window.__dark = true; window.__contrast = 0.5; }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C3. contrast 0.5 (read when Apply is tapped): ' + Object.keys(A15_FID_05).length + ' values pinned from the a15 oracle at 0.5 (ContrastCurve(4.5, 7, 11, 21): on_tertiary_container_dark 0xFF000000, 15 QPR1\'s 0xFF2A1F00); same order', pinsOff(plan, A15_FID_05).length === 0 && Q1_FID_05_ON.on_tertiary_container_dark !== A15_FID_05.on_tertiary_container_dark && sameOrder(plan, PLAN_ORDER), pinsOff(plan, A15_FID_05).join('; '));
  await ev(() => { window.__contrast = 0; kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C4. Content (More styles) B5651D at 2021, contrast 0: 165 in order; ' + Object.keys(A15_CON_0).length + ' values pinned from the a15 oracle (on_tertiary_container_light 0xFFFFFFFF, 15 QPR1\'s 0xFFFFFFD8)', sameOrder(plan, PLAN_ORDER) && pinsOff(plan, A15_CON_0).length === 0 && Object.keys(Q1_CON_0_ON).every(n => Q1_CON_0_ON[n] !== A15_CON_0[n]), pinsOff(plan, A15_CON_0).join('; '));
  await ev(() => { kvSet('ovl_root_style', 'MONOCHROMATIC'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C5. Monochrome (More styles) 6750A4 at 2021: 165 in order; ' + Object.keys(A15_MONO_0).length + ' values pinned from the a15 oracle: every palette_key_color_* is 0xFF070707 (the walk out from tone 50), not 15 QPR1\'s 0xFF777777 (the bisection); the shades as before (shade 500 0xFF777777)', sameOrder(plan, PLAN_ORDER) && pinsOff(plan, A15_MONO_0).length === 0 && A15_MONO_0.palette_key_color_primary_light !== Q1_MONO_KEY && (await sameAs(plan, 'MONOCHROMATIC', '6750A4', { spec: '2021', dark: true, profile: 'P15Q1', contrast: 0 })) !== 'same', pinsOff(plan, A15_MONO_0).join('; '));
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_accent_tertiary', 'FFD600'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card(); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('C6. Tonal Spot 6750A4 in the list at 2021 with a tertiary accent: written (the hint says its colors, the accents line names the tertiary one); the setting (TONAL_SPOT), then 165 in order; ' + Object.keys(A15_TS_0).length + ' values the accent leaves alone pinned from the a15 oracle (on_primary_container_light 0xFF201047, 15 QPR1\'s 0xFF4D3D75; on_secondary_container_light 0xFF1E192B); the record keeps the accent', c.hint === 'Also writes this style\'s colors with root.' && c.said === 'Tertiary accent: your color.' && k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && sameOrder(plan, PLAN_ORDER) && pinsOff(plan, A15_TS_0).length === 0 && Object.keys(Q1_TS_0_ON).every(n => Q1_TS_0_ON[n] !== A15_TS_0[n]) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accents":{"tertiary":"FFD600"},"profile":"P15","build":"' + B15 + '"}', JSON.stringify([c.hint, c.said, pinsOff(plan, A15_TS_0), await rec()]));
  await ev(() => { kvSet('ovl_accent_tertiary', ''); ovlRender(); });
  const ADJ = { accentSat: 150, bgSat: 50, bgLight: 120 }, adjOpts = p => ({ spec: '2021', dark: true, profile: p, contrast: 0, adjust: ADJ });
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_adj_accent_sat', 150); kvSet('ovl_adj_bg_sat', 50); kvSet('ovl_adj_bg_light', 120); ovlPickStyle('VIBRANT'); ovlPickColor('6750A4'); ovlRender(); });
  c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; const rV = byRes(plan);
  const keyN = await ev(o => (MonetPlan.scheme('VIBRANT', '6750A4', true, o).neutralPalette.keyColor.toInt() >>> 0).toString(16).toUpperCase(), adjOpts('P15'));
  const q1N = await ev(o => (MonetPlan.entries('VIBRANT', '6750A4', o).find(e => e.resource === 'android:color/system_palette_key_color_neutral_light') || {}).argb, adjOpts('P15Q1'));
  check('C7. Vibrant 6750A4 in the list at 2021 with Intensity 150 / 50 / 120: written, 165 in order, MonetPlan.entries with P15 and the sliders; the neutral palette the sliders rebuild has the a15 key colour 0x' + A15_VIB_ADJ_KEYN + ' (libmonet android15-release\'s walk for its hue and chroma, not the bisection\'s 0xFF79767B), so palette_key_color_neutral is 0xFF817D83 in both modes, not 15 QPR1\'s ' + Q1_VIB_ADJ_KEYN,
    sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'VIBRANT', '6750A4', adjOpts('P15'))) === 'same' && keyN === A15_VIB_ADJ_KEYN && pinsOff(plan, VIB_ADJ).length === 0 && q1N === Q1_VIB_ADJ_KEYN && rV.palette_key_color_neutral_light !== q1N,
    JSON.stringify([names(plan).length, keyN, pinsOff(plan, VIB_ADJ), q1N]));
  await ev(() => { ['ovl_adj_accent_sat', 'ovl_adj_bg_sat', 'ovl_adj_bg_light'].forEach(k => { delete window.AndroidBridge.__kv[k]; }); ovlRender(); });

  // ---- (d) 2025 and 2026 on 15.0 ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); }); c = await card();
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const ts25d = plan;
  check('D1. Tonal Spot 6750A4 at 2025, dark mode: the note under 2025 is the one with the light / dark sentence; 165 in order; exactly what 15 QPR1 gets (MonetPlan.entries with P15Q1: no a15 reference exists for 2025), with the 2025 roles (on_primary_container_light ' + byRes(plan).on_primary_container_light + ', not the 2021 a15 0xFF201047); the record says spec 2025, P15', c.specNote === SPEC_NOTE && sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P15Q1', contrast: 0 })) === 'same' && byRes(plan).on_primary_container_light !== A15_TS_0.on_primary_container_light && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","profile":"P15","build":"' + B15 + '"}', JSON.stringify([c.specNote, await rec()]));
  await ev(() => { window.__dark = false; ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const shadeDiff = names(plan).filter(r => /_(accent[123]|neutral[12])_\d+$/.test(r) && byRes(plan)[r.replace(RES, '')] !== byRes(ts25d)[r.replace(RES, '')]);
  check('D2. the same in light mode: the 65 shades come from the light scheme (' + shadeDiff.length + ' of them differ from the dark write, accent1_500 ' + byRes(plan).accent1_500 + ' / ' + byRes(ts25d).accent1_500 + '); the 100 roles and fixed colours are the same; 15 QPR1\'s light plan', shadeDiff.length > 0 && names(plan).filter(r => !/_(accent[123]|neutral[12])_\d+$/.test(r)).every(r => byRes(plan)[r.replace(RES, '')] === byRes(ts25d)[r.replace(RES, '')]) && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: false, profile: 'P15Q1', contrast: 0 })) === 'same', JSON.stringify(shadeDiff));
  await ev(() => { window.__dark = true; kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D3. Fidelity at 2025 keeps its 2021 colours (Google\'s rule), with the a15 rules: the values of C1 (on_primary_container_light 0xFFFFFFFF), the record without a spec', pinsOff(plan, A15_FID_0).length === 0 && JSON.stringify(plan.entries) === JSON.stringify(darkPlan.entries) && c.hint === FID_HINT && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P15","build":"' + B15 + '"}', JSON.stringify([pinsOff(plan, A15_FID_0), await rec()]));
  await ev(() => { kvSet('ovl_spec', '2026'); kvSet('ovl_root_style', 'CMF'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D4. CMF (More styles) at 2026: 165 in order, exactly 15 QPR1\'s (MonetPlan.entries with P15Q1); the hint names CMF; the record says CMF, 2026, P15', sameOrder(plan, PLAN_ORDER) && (await sameAs(plan, 'CMF', '6750A4', { spec: '2026', dark: true, profile: 'P15Q1', contrast: 0 })) === 'same' && c.hint === 'Also writes the CMF colors with root, in place of the style above.' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"CMF","spec":"2026","profile":"P15","build":"' + B15 + '"}', JSON.stringify([c.hint, await rec()]));

  // ---- (e) Pure black ----
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_black', true); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('E1. P15, Pure black alone (Tonal Spot at 2021 is native): the hint names pure black; the 11 entries of Android 14 to 16.0 (system_neutral1_900 and system_neutral2_900 unsuffixed, the nine dark roles); the record: black, no style, P15', c.hint === 'Also writes the pure black overlay with root.' && JSON.stringify(plan.entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(35))) && names(plan)[0] === RES + 'neutral1_900' && names(plan)[1] === RES + 'neutral2_900' && names(plan).length === 11 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","profile":"P15","build":"' + B15 + '"}', JSON.stringify([c.hint, names(plan).slice(0, 3), await rec()]));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('E2. Pure black over Fidelity: still 165 in order, surface_dark (fourth) black, neutral1_900, neutral2_900 and background_dark black, surface_container_dark grey; the rest Fidelity\'s a15 values (primary_light 0xFF4F378A, on_primary_container_light 0xFFFFFFFF)', sameOrder(plan, PLAN_ORDER) && plan.entries[3].argb === '0xFF000000' && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818' && r.primary_light === A15_FID_0.primary_light && r.on_primary_container_light === A15_FID_0.on_primary_container_light, JSON.stringify([r.surface_dark, r.primary_light]));
  await ev(() => { kvSet('ovl_black', false); });

  // ---- (f) the accent picker starts at the a15 key colour ----
  const sheet = () => ev(() => ({ open: document.getElementById('colorPickModal').classList.contains('show'), sub: document.getElementById('cpSub').innerText, hex: document.getElementById('cpHex1').value }));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); ovlPickColor('00FF00'); ovlRender(); ovlAccOpen('secondary'); }); await sleep(150); const sh = await sheet(); await ev(() => closeColorPicker());
  await phone({ sdk: 35, profile: 'P15Q1', build: BQ1 }); await ev(() => { ovlAccOpen('secondary'); }); await sleep(150); const shQ1 = await sheet(); await ev(() => closeColorPicker());
  check('F1. P15, Fidelity 00FF00 at 2021: the Secondary accent\'s picker starts at #' + A15_FID_GREEN_KEY2 + ', the a15 oracle\'s key colour of that palette (the walk); on 15 QPR1 (P15Q1, the bisection) at another one', sh.open && new RegExp('#' + A15_FID_GREEN_KEY2 + ' ').test(sh.sub) && sh.hex.toUpperCase().includes(A15_FID_GREEN_KEY2) && shQ1.open && !shQ1.sub.includes('#' + A15_FID_GREEN_KEY2), JSON.stringify([sh, shQ1]));
  await phone({ sdk: 35, profile: 'P15', build: B15 });
  await ev(() => { kvSet('ovl_root_style', ''); ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); ovlRender(); ovlAccOpen('tertiary'); }); await sleep(150); const shTs = await sheet(); await ev(() => closeColorPicker());
  check('   Tonal Spot 6750A4 in the list (native at 2021): the Tertiary accent\'s picker starts at #996A79 (the a15 oracle\'s palette_key_color_tertiary)', shTs.open && /#996A79 /.test(shTs.sub), JSON.stringify(shTs));

  // ---- (g) the record and the note for colours written on another build ----
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2021'); kvSet('ovl_root_applied', null); ovlPickColor('6750A4'); });
  await phone({ sdk: 35, profile: 'P15', build: B15 }); await reset(); await apply(); c = await card();
  check('G1. written on P15: no note about another build; the record says P15 and its build', c.stale === '' && (await rec()).includes('"profile":"P15","build":"' + B15 + '"'), JSON.stringify([c.stale, await rec()]));
  await reset(); await phone({ sdk: 35, profile: 'P15', build: B15b }); c = await card(); k = await calls();
  check('   a monthly update that stays 15.0 (another build, P15): no note, nothing written by itself', c.stale === '' && k.fab.length === 0 && k.apply.length === 0, JSON.stringify([c.stale, k.fab.length]));
  await phone({ sdk: 35, profile: 'P15Q1', build: BQ1 }); c = await card();
  check('   the phone now runs 15 QPR1 (P15Q1): the note says Android was updated', c.stale === STALE && c.note === 'The theme overlay is written on this phone.', JSON.stringify([c.stale, c.note]));
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P15Q1', build: b }); }, [AAM, BQ1]);
  await phone({ sdk: 35, profile: 'P15', build: B15 }); c = await card();
  check('G2. a record written on 15 QPR1 (P15Q1), the phone on 15.0: the note shows (the on_*_container roles and key colours differ)', c.stale === STALE, c.stale);
  await reset(); await apply(); c = await card();
  check('   Apply writes them for 15.0: the record says P15 and its build, and the note goes', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P15","build":"' + B15 + '"}' && c.stale === '', JSON.stringify([await rec(), c.stale]));

  // ---- (h) Undo, Default and the resume path ----
  await phone({ sdk: 35, profile: 'P15', build: B15, contrast: 0 }); await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_root_style', 'FIDELITY'); ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply();
  const first = ((await calls()).fab[0] || {}).entries;
  await ev(() => { kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); ovlRender(); }); await reset(); await apply();
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(150); k = await calls(); plan = k.fab[0] || {};
  check('H1. Undo of a Content write after a Fidelity one on P15: the theme is put back and Fidelity\'s 165 entries are written again, as they were (the a15 values of C1)', k.restore.length === 1 && k.fab.length === 1 && plan.action === 'fabricate' && JSON.stringify(plan.entries) === JSON.stringify(first) && pinsOff(plan, A15_FID_0).length === 0 && (await rec()).includes('"style":"FIDELITY"'), JSON.stringify([k.restore.length, k.fab.length, names(plan).length]));
  await reset(); dialogs.length = 0; await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('H2. Default with the overlay on: the question says it is switched off too; the setting is reset and the overlay disabled; the record is cleared', dialogs.length === 1 && dialogs[0].includes('The theme overlay is switched off too.') && k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([dialogs, k.fab]));

  // the resume path (T-068): an Apply of Fidelity at 2021 on P15 whose theme answer is lost to a restart; the restarted page, the theme read
  // back as written, completes that Apply's overlay half with the 165-entry plan
  const KEY = 'theme_customization_overlay_packages';
  const a15 = () => { window.__root = true; window.__sdk = 35; window.__profile = 'P15'; window.__build = 'google/mock/mock:15/AP3A.240905.015/12231197:user/release-keys'; window.__dark = true; window.__contrast = 0; };
  const openWith = async (kvs, fn, arg) => {
    const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
    p2.on('pageerror', e => errors.push(e.message));
    await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, kvs);
    await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript); await p2.addInitScript(a15); await p2.addInitScript(fn, arg);
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
  check('H3. the resume path on P15: Fidelity at 2021 tapped, the theme answer lost to a restart (nothing written yet, the resume record plans the overlay); the restarted page, the theme read back as written, writes that overlay: 165 entries, the lookups first, then SystemUI\'s order; the record says Fidelity, P15; the resume record is done',
    lost.fab === 0 && !!lost.resume && lost.resume.root === 'fabricate' && rs.fab.length === 1 && rf.action === 'fabricate' && JSON.stringify(rf.names) === JSON.stringify(PLAN_ORDER) && !!rs.applied && rs.applied.style === 'FIDELITY' && !rs.applied.spec && rs.applied.profile === 'P15' && rs.done,
    JSON.stringify([lost.fab, lost.resume && lost.resume.root, rs.fab.map(f => f.action + ':' + f.names.length), rs.applied, rs.done]));

  // ---- (i) the credit line in German ----
  const CREDIT = 'More styles and Color spec: on Android 14 the Fidelity, Content and Monochrome colors are computed with a port of Android 14\'s own color engine, from the <1>Android Open Source Project</1> and Google\'s <2>Material Color Utilities</2> 0.4.0; on Android 15 and 16 those colors, and the 2025 and 2026 specs and CMF everywhere, with a port of Material Color Utilities on GitHub (main, commit bd811e4, 2026). On Android 14 QPR1 to QPR3, 15.0, 16 QPR1, 16 QPR2 and 17 they also follow the changes in Android\'s own copy of that library (libmonet, Android Open Source Project). All Apache-2.0; the file monet.js names what was taken from where.';
  const tip = () => ev(() => { const t = Array.from(document.querySelectorAll('.about-tip')).find(e => /libmonet/.test(e.textContent)); return t ? t.textContent : ''; });
  const en = await tip();
  await ev(() => I18N.set('de')); await sleep(600);
  const de = await tip();
  const deWant = await ev(k => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return (x[k] || '').replace(/<\/?\d>/g, ''); }, CREDIT);
  check('I1. the More styles credit names the builds that follow libmonet (14 QPR1 to QPR3 from PR 12, 15.0, 16 QPR1, 16 QPR2, 17), and reads in German as assets/lang/de.js has it', en === CREDIT.replace(/<\/?\d>/g, '') && deWant !== '' && /Android 14 QPR1 bis QPR3, 15\.0, 16 QPR1, 16 QPR2 und 17/.test(deWant) && de === deWant, JSON.stringify([en, de, deWant]));
  await ev(() => I18N.set('en')); await sleep(300);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
