// Root colours per Android build family (T-049): the Overlays tab's root-only theme controls compute the colours for the family the phone
// is (getSystemPalette's profile, MonetProfile.java) at its contrast setting, and lock on every family this app cannot compute exactly.
// (a) Locks: Android 15.0 (P15), 16 QPR1 and QPR2 (P16Q1, P16Q2), 17 (P17), an Android this app does not know (unknown), a profile it is
// told is fine but cannot compute, a phone with a One UI version, and an older APK that reports no profile on Android 15 and 16: the three
// rows (Pure black, Color spec, More styles) are locked with their reason, with Super User and without; nothing in the card can be used,
// the stored choices count for nothing (no hint, no preview, the handlers change nothing) and Apply writes the theme setting only, without
// asking the bridge for the palette (where the contrast comes from) when tapped. An older
// APK on Android 14 keeps working as before (PR 3's plan, a record without profile and build). (b) A locked phone with an overlay written
// before (as after an update to a build not supported yet): the note says it is on, and Apply, Default and Undo switch it off; nothing is
// written. (c) Android 16.0 (P16): the plan is 177 entries, the four lookups first and then SystemUI's order (the palettes neutral1,
// neutral2, accent1, accent2, accent3 in its shade write order; the 50 roles sorted by name, each _light then _dark; the 12 fixed colours
// sorted by name), shade 500 is tone 50, the fixed colours come from the light scheme; the profile and the phone's contrast reach the plan
// (contrast 0.5 changes the roles and the fixed colours, not the shades), the contrast is read from the phone when Apply is tapped; Pure
// black with it and alone; the preview; a list style at 2025; Content (its 2021 tertiary palette from TemperatureCache.analogous) and
// Monochrome in More styles. (d) Android 15 QPR1 (P15Q1): 165 entries with Android 14's 44 names in its
// order; Android 15 QPR2 (P15Q2): 177; Android 14 (P14): PR 3's plan. (e) The record: profile (when it is not P14) and build; old records
// read as P14 without a build. (f) The note for colours written on another build ("Android was updated..."): when it shows and when not;
// nothing runs by itself; Apply writes them for the build the phone runs and the note goes. (g) The new words in German.
// Every value pinned below comes from an oracle kept outside the repository (SysuiDump.java): ThemeOverlayController's write sequence and
// resource names (android16-release; android15-qpr1-release for P15Q1), transcribed, run with SystemUI's monet classes of that branch
// compiled on its libmonet (a16; a15-qpr2 for P15Q1). Content and Monochrome come from SystemUI's own ColorScheme (its Content and
// Monochrome styles; ThemeOverlayController itself never asks for Content). Fidelity, which SystemUI never builds, is libmonet's
// SchemeFidelity on the raw seed with SystemUI's TonalPalette wrappers and the same write sequence: the reference for it (Google's library
// at spec 2021), not a value Android writes. The cases: FIDELITY 6750A4 at contrast 0 and 0.5, CONTENT B5651D and MONOCHROMATIC 6750A4 at
// 0, all spec 2021, dark mode; monet.js gives the same 177 (165) values in each case, and so does a second dump of the same kind made apart.
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
const LEAD = ['accent1_600', 'neutral1_500', 'primary_light', 'surface_dark'].map(n => RES + n);
// builds (Build.FINGERPRINT) the mock reports: one per Android, and a second Android 16 one (a system update)
const B14 = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
const B15 = 'google/mock/mock:15/AP4A.250105.002/12701944:user/release-keys';
const B16 = 'google/mock/mock:16/BP2A.250605.031/13578712:user/release-keys';
const B16b = 'google/mock/mock:16/BP2A.250805.005/13860026:user/release-keys';
// SystemUI's write order, from the oracle's dumps: the palettes neutral1, neutral2, accent1, accent2, accent3, each in this shade order
// (a HashMap's), then each role _light and _dark, then the fixed colours
const PAL_ROWS = ['neutral1', 'neutral2', 'accent1', 'accent2', 'accent3'];
const SHADE_ORDER = [0, 800, 100, 900, 200, 1000, 10, 300, 400, 50, 500, 600, 700];
// Android 15 QPR2 and 16.0: the 50 roles and the 12 fixed colours, as SystemUI names and orders them (sorted by name)
const ROLES50 = ['background', 'control_activated', 'control_highlight', 'control_normal', 'error', 'error_container', 'inverse_on_surface', 'inverse_primary', 'inverse_surface', 'on_background', 'on_error', 'on_error_container', 'on_primary', 'on_primary_container', 'on_secondary', 'on_secondary_container', 'on_surface', 'on_surface_variant', 'on_tertiary', 'on_tertiary_container', 'outline', 'outline_variant', 'palette_key_color_neutral', 'palette_key_color_neutral_variant', 'palette_key_color_primary', 'palette_key_color_secondary', 'palette_key_color_tertiary', 'primary', 'primary_container', 'scrim', 'secondary', 'secondary_container', 'shadow', 'surface', 'surface_bright', 'surface_container', 'surface_container_high', 'surface_container_highest', 'surface_container_low', 'surface_container_lowest', 'surface_dim', 'surface_tint', 'surface_variant', 'tertiary', 'tertiary_container', 'text_hint_inverse', 'text_primary_inverse', 'text_primary_inverse_disable_only', 'text_secondary_and_tertiary_inverse', 'text_secondary_and_tertiary_inverse_disabled'];
const FIXED16 = ['on_primary_fixed', 'on_primary_fixed_variant', 'on_secondary_fixed', 'on_secondary_fixed_variant', 'on_tertiary_fixed', 'on_tertiary_fixed_variant', 'primary_fixed', 'primary_fixed_dim', 'secondary_fixed', 'secondary_fixed_dim', 'tertiary_fixed', 'tertiary_fixed_dim'];
// Android 15 QPR1: Android 14's 44 roles and 12 fixed colours, in Android 14's order
const ROLES44 = ['primary_container', 'on_primary_container', 'primary', 'on_primary', 'secondary_container', 'on_secondary_container', 'secondary', 'on_secondary', 'tertiary_container', 'on_tertiary_container', 'tertiary', 'on_tertiary', 'background', 'on_background', 'surface', 'on_surface', 'surface_container_low', 'surface_container_lowest', 'surface_container', 'surface_container_high', 'surface_container_highest', 'surface_bright', 'surface_dim', 'surface_variant', 'on_surface_variant', 'outline', 'outline_variant', 'error', 'on_error', 'error_container', 'on_error_container', 'control_activated', 'control_normal', 'control_highlight', 'text_primary_inverse', 'text_secondary_and_tertiary_inverse', 'text_primary_inverse_disable_only', 'text_secondary_and_tertiary_inverse_disabled', 'text_hint_inverse', 'palette_key_color_primary', 'palette_key_color_secondary', 'palette_key_color_tertiary', 'palette_key_color_neutral', 'palette_key_color_neutral_variant'];
const FIXED14 = ['primary_fixed', 'primary_fixed_dim', 'on_primary_fixed', 'on_primary_fixed_variant', 'secondary_fixed', 'secondary_fixed_dim', 'on_secondary_fixed', 'on_secondary_fixed_variant', 'tertiary_fixed', 'tertiary_fixed_dim', 'on_tertiary_fixed', 'on_tertiary_fixed_variant'];
const NEW6 = ['inverse_surface', 'inverse_on_surface', 'inverse_primary', 'shadow', 'scrim', 'surface_tint'];
const order = (roles, fixed) => PAL_ROWS.map(r => SHADE_ORDER.map(s => r + '_' + s)).flat().concat(roles.map(r => [r + '_light', r + '_dark']).flat(), fixed).map(n => RES + n);
// the plan's order: the four lookups first, the rest as SystemUI writes them
const planOrder = (roles, fixed) => LEAD.concat(order(roles, fixed).filter(r => !LEAD.includes(r)));
// Oracle pins, P16, FIDELITY 6750A4, spec 2021, dark mode (resource suffix: value)
const SHADE500 = { accent1_500: '0xFF8069BF', accent2_500: '0xFF7C7296', accent3_500: '0xFF91731D', neutral1_500: '0xFF79767D', neutral2_500: '0xFF7A7582' };
// the same five on Android 14 (Android 14's shade rule, tone 49.6): monet.js's P14 output for Fidelity 6750A4 (t565 pins accent1_500)
const SHADE500_P14 = { accent1_500: '0xFF7F68BE', accent2_500: '0xFF7B7195', accent3_500: '0xFF90721C', neutral1_500: '0xFF78757C', neutral2_500: '0xFF797481' };
const P16_C0 = { primary_light: '0xFF4F378A', primary_dark: '0xFFCFBCFF', surface_light: '0xFFFDF7FF', surface_dark: '0xFF141218', on_primary_container_dark: '0xFFE0D2FF', outline_dark: '0xFF948E9C', accent1_100: '0xFFE9DDFF',
  surface_tint_light: '0xFF6750A4', inverse_surface_dark: '0xFFE6E0E9', inverse_primary_light: '0xFFCFBCFF', inverse_on_surface_dark: '0xFF322F35', scrim_light: '0xFF000000', shadow_dark: '0xFF000000',
  primary_fixed: '0xFFE9DDFF', on_primary_fixed: '0xFF22005D', secondary_fixed_dim: '0xFFCDC0E9' };
const P16_C05 = { primary_light: '0xFF3E2578', primary_dark: '0xFFE3D6FF', surface_dark: '0xFF141218', on_primary_container_dark: '0xFF000000', outline_dark: '0xFFB6AFBD', accent1_100: '0xFFE9DDFF',
  primary_fixed: '0xFF765FB4', on_primary_fixed: '0xFFFFFFFF', secondary_fixed_dim: '0xFF595072' };
// Oracle pins, P16, CONTENT B5651D, spec 2021, dark mode, contrast 0: the first eight come from the tertiary palette (TemperatureCache's
// analogous colours), the rest from the primary, secondary and neutral ones
const CONTENT_P16 = { accent3_500: '0xFF767D0E', accent3_100: '0xFFE2EA75', tertiary_light: '0xFF5B6000', tertiary_dark: '0xFFC6CE5C', tertiary_container_dark: '0xFF8F972B',
  on_tertiary_container_light: '0xFFFFFFD8', palette_key_color_tertiary_dark: '0xFF787F11', tertiary_fixed: '0xFFE2EA75',
  accent1_500: '0xFFB2631B', primary_light: '0xFF8F4900', primary_dark: '0xFFFFB781', secondary_dark: '0xFFF0BC97', surface_dark: '0xFF19120D' };
// Oracle pins, P16, MONOCHROMATIC 6750A4, spec 2021, dark mode, contrast 0
const MONO_P16 = { accent1_500: '0xFF777777', accent3_500: '0xFF777777', neutral1_500: '0xFF777777', primary_light: '0xFF000000', primary_dark: '0xFFFFFFFF', on_primary_dark: '0xFF1B1B1B',
  primary_container_dark: '0xFFD4D4D4', tertiary_dark: '0xFFE2E2E2', surface_dark: '0xFF131313', primary_fixed: '0xFF5E5E5E' };
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
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  // the phone: { sdk, profile, reason, build, contrast }; profile and reason left out follow the SDK as ovl_mock says, null leaves the field
  // out of the answer (an older APK); the page reads the answer again and redraws, as when the tab is opened
  const phone = async o => {
    await ev(o => { window.__sdk = o.sdk; window.__profile = o.profile; window.__profileReason = o.reason; window.__build = o.build === undefined ? null : o.build; window.__contrast = o.contrast === undefined ? 0 : o.contrast; ovlReadPalette(); ovlRender(); }, o);
    await sleep(150);
  };
  // the card: each row locked or not and its reason, what in it can be used (enabled controls), the notes, the hint under Apply, the preview
  const card = () => ev(() => {
    const c = document.getElementById('ovlRootCard');
    const row = id => { const box = document.getElementById(id), r = box && box.querySelector('.rf-row'), lock = box && box.querySelector('.pm-lock'); return { locked: !!(r && r.classList.contains('is-locked')), lock: lock ? lock.innerText : '' }; };
    const live = Array.from(c.querySelectorAll('button, input, select, textarea, a[href], [tabindex], [role="radio"], [role="switch"]')).filter(e => !e.disabled && e.getAttribute('tabindex') !== '-1');
    const stale = document.getElementById('ovlRootStale'), pv = document.getElementById('ovlRootPreview');
    const chips = live.filter(e => e.classList.contains('ovl-chip')).length;                           // the preview's swatches (a tap copies one)
    return { shown: c.style.display !== 'none', black: row('ovlRootCards'), spec: row('ovlRootSpec'), more: row('ovlRootMore'), live: live.filter(e => !e.classList.contains('ovl-chip')).map(e => e.id || (e.dataset && (e.dataset.style || e.dataset.spec)) || e.className), chips,
      note: document.getElementById('ovlRootNote').innerText, stale: stale && stale.style.display !== 'none' ? stale.innerText : '', hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '' };
  });
  const lockedAll = (c, reason) => c.shown && ['black', 'spec', 'more'].every(k => c[k].locked && c[k].lock === reason) && c.live.length === 0 && c.chips === 0;
  const LIVE = '["ovlBlackSw","2021","2025","2026","OFF","FIDELITY","CONTENT","MONOCHROMATIC","ovlAdjAccentSat","ovlAdjBgSat","ovlAdjBgLight","ovlAccSecondary","ovlAccTertiary"]';
  const preview = () => ev(() => { const pv = document.getElementById('ovlRootPreview'); const rows = pv ? Array.from(pv.querySelectorAll('.ovl-pal')) : []; return { rows: rows.length, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] }; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await ev(() => { window.__palBefore = window.__ovlCalls.palette; window.__palAtTap = -1; }); await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  // the getSystemPalette reads Apply made when tapped: those before its theme write (themeApply), the last time apply() ran
  const tapReads = () => ev(() => window.__palAtTap);
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => { const want = MonetPlan.entries(style, seed, opts || undefined); if (!entries || want.length !== entries.length) return 'count ' + (entries || []).length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan && plan.entries, style, seed, opts || null]);
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T') : 'null'; };
  const waitFab = () => page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });
  await ev(() => { const b = window.AndroidBridge, real = b.themeApply; b.themeApply = function () { window.__palAtTap = window.__ovlCalls.palette - window.__palBefore; return real.apply(this, arguments); }; });

  // ---- (a) locks ----
  await ev(() => switchView('overlays')); await sleep(300);
  await setRoot(true);
  // stored choices that write colours wherever the controls are live: Pure black on, Fidelity in More styles, spec 2025, Tonal Spot in the list
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', true); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_spec', '2025'); kvSet('ovl_root_applied', null); });
  await phone({ sdk: 36, profile: 'P16', build: B16 });
  let c = await card();
  check('A0. Android 16.0 (P16) with Super User: the five rows are live (the switch, the two radio groups, the three Intensity sliders and the two accent entries can be used, and the preview\'s 65 swatches; Intensity\'s Reset is disabled at 100 %), the hint names Fidelity and pure black', c.shown && !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.hint === 'Also writes the Fidelity colors and pure black with root, in place of the style above.' && c.preview, JSON.stringify(c));
  const LOCKS = [
    ['Android 15.0 (P15, notYet)', { sdk: 35, profile: 'P15', build: B15 }, NOT_YET],
    ['Android 16 QPR1 (P16Q1, notYet)', { sdk: 36, profile: 'P16Q1', build: B16 }, NOT_YET],
    ['Android 16 QPR2 (P16Q2, notYet)', { sdk: 36, profile: 'P16Q2', build: B16 }, NOT_YET],
    ['Android 17 (P17, notYet)', { sdk: 37, profile: 'P17', build: B16 }, NOT_YET],
    ['an Android this app does not know (SDK 38: unknown)', { sdk: 38, build: B16 }, NOT_YET],
    ['a profile reported ok that this app cannot compute (P16Q3, ok)', { sdk: 36, profile: 'P16Q3', reason: 'ok', build: B16 }, NOT_YET],
    ['P16 with a reason other than ok (notYet)', { sdk: 36, profile: 'P16', reason: 'notYet', build: B16 }, NOT_YET],
    ['a One UI phone on Android 14 (unknown, oneUi)', { sdk: 34, profile: 'unknown', reason: 'oneUi', build: B14 }, ONE_UI],
    ['a One UI phone on Android 16 (unknown, oneUi)', { sdk: 36, profile: 'unknown', reason: 'oneUi', build: B16 }, ONE_UI],
    ['an older APK on Android 15 (no profile, build or contrast)', { sdk: 35, profile: null, build: null, contrast: null }, 'Update the app'],
    ['an older APK on Android 16 (no profile, build or contrast)', { sdk: 36, profile: null, build: null, contrast: null }, 'Update the app']
  ];
  for (const [label, o, reason] of LOCKS) {
    await phone(o); c = await card();
    check('A1. ' + label + ': Pure black, Color spec and More styles are locked with "' + reason + '"; nothing in the card can be used; no preview, no hint, no note', lockedAll(c, reason) && !c.preview && c.hint === '' && c.note === '' && c.stale === '', JSON.stringify(c));
    await reset();
    const h = await ev(() => { ovlToggleBlack(); ovlPickRootStyle('MONOCHROMATIC'); ovlPickSpec('2026'); return [kvGet('ovl_black', null), kvGet('ovl_root_style', null), kvGet('ovl_spec', null)]; });
    await apply(); const k = await calls(), reads = await tapReads();
    check('   the switch, More styles and Color spec change nothing (handlers called directly); Apply writes the theme setting only, no overlay call, and asks the bridge for no palette (the contrast) when tapped', JSON.stringify(h) === '[true,"FIDELITY","2025"]' && k.apply.length === 1 && k.fab.length === 0 && (await kv('ovl_root_applied')) === null && reads === 0, JSON.stringify([h, k.apply.length, k.fab, reads]));
  }
  await setRoot(false); await phone({ sdk: 36, profile: 'P16Q1', build: B16 }); c = await card();
  check('A2. without Super User the profile\'s reason shows, not Needs Super User (root would not unlock it), and no note about Root', lockedAll(c, NOT_YET) && c.note === '', JSON.stringify(c));
  await phone({ sdk: 36, profile: 'P16', build: B16 }); c = await card();
  check('   P16 without Super User: Needs Super User, as before, with its note', lockedAll(c, 'Needs Super User') && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await phone({ sdk: 36, profile: 'unknown', reason: 'oneUi', build: B16 }); c = await card();
  check('   a One UI phone without Super User: Not supported on One UI', lockedAll(c, ONE_UI), JSON.stringify(c));
  await setRoot(true);
  // an older APK on Android 14 reports no profile: Android 14's colours, as before
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_spec', '2021'); }); await phone({ sdk: 34, profile: null, build: null, contrast: null }); c = await card();
  check('A3. an older APK on Android 14 (no profile, build or contrast): the rows are live (P14, as that APK was made for); the hint names Fidelity', !c.black.locked && !c.spec.locked && !c.more.locked && JSON.stringify(c.live) === LIVE && c.chips === 65 && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify(c));
  await reset(); await apply(); let k = await calls(), plan = k.fab[0] || {};
  check('   Apply writes PR 3\'s plan (MonetPlan.entries(FIDELITY, 6750A4) without options, 165 entries, accent1_500 0xFF7F68BE); the record has no profile and no build', k.fab.length === 1 && plan.action === 'fabricate' && plan.entries.length === 165 && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan).accent1_500 === '0xFF7F68BE' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY"}', JSON.stringify([await sameAs(plan, 'FIDELITY', '6750A4'), await rec()]));
  await ev(() => kvSet('ovl_root_applied', null));

  // ---- (b) a locked phone with an overlay written before ----
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await reset(); await apply(); k = await calls();
  check('B1. written on Android 16.0 (P16): Fidelity\'s plan goes out and the record is kept', k.fab.length === 1 && k.fab[0].action === 'fabricate' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', await rec());
  await phone({ sdk: 36, profile: 'P16Q1', build: B16b }); c = await card();
  check('   after an update to Android 16 QPR1 (P16Q1, another build): the rows are locked, the note says the theme overlay is on, the hint says Apply removes it; no note about another build (Apply would not write them)', lockedAll(c, NOT_YET) && c.note === 'The theme overlay is written on this phone.' && c.hint === 'Also removes the theme overlay.' && c.stale === '', JSON.stringify(c));
  await reset(); await apply(); k = await calls();
  check('B2. Apply there: the theme setting, then a disable of the overlay (no fabricate); the record is cleared and the toast says it is removed', k.apply.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && (await kv('ovl_root_applied')) === null && (await toasts()).includes('Theme overlay removed'), JSON.stringify([k.fab, await toasts()]));
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(100); k = await calls();
  check('   its Undo puts the theme back and writes nothing (no overlay call)', k.restore.length === 1 && k.restoreUndo[0] === true && k.fab.length === 0 && (await kv('ovl_root_applied')) === null, JSON.stringify(k.fab));
  await reset(); await apply(); k = await calls();
  check('   Apply again: the setting only', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P16', build: b }); ovlRender(); }, [AAM, B16]);
  await reset(); dialogs.length = 0; await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('B3. Default with the overlay on, on the locked phone: the question says it is switched off too, then the setting is reset and the overlay disabled; the record is cleared', dialogs.length === 1 && dialogs[0].includes('The theme overlay is switched off too.') && k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([dialogs, k.fab]));
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await reset(); await apply();
  await phone({ sdk: 36, profile: 'P16Q1', build: B16b }); await reset();
  await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(100); k = await calls();
  check('B4. Undo of a write, the phone reading as P16Q1 by then: the overlay is switched off first, then the theme put back; the record is cleared', k.fab.length === 1 && k.fab[0].action === 'disable' && k.restore.length === 1 && k.restoreUndo[0] === true && (await kv('ovl_root_applied')) === null, JSON.stringify([k.fab, k.restore]));

  // ---- (c) Android 16.0 (P16) ----
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_root_applied', null); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; });
  await phone({ sdk: 36, profile: 'P16', build: B16, contrast: 0 }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  const names = (plan.entries || []).map(e => e.resource);
  check('C1. P16, Fidelity 6750A4, dark mode, contrast 0: overlayFabricate with 177 entries, no resource twice, the four lookups first and then SystemUI\'s order (palettes neutral1, neutral2, accent1-3 in its shade order; 50 roles each _light then _dark; 12 fixed colours)', k.fab.length === 1 && plan.action === 'fabricate' && names.length === 177 && new Set(names).size === 177 && JSON.stringify(names) === JSON.stringify(planOrder(ROLES50, FIXED16)), JSON.stringify([plan.action, names.length, names.slice(0, 6)]));
  check('   Apply read the phone\'s contrast when tapped: one getSystemPalette call before the theme write (none on a locked profile, A1)', (await tapReads()) === 1, await tapReads());
  const roleSet = names.filter(n => /_light$/.test(n)).map(n => n.replace(RES, '').replace(/_light$/, '')), fixedSet = names.map(n => n.replace(RES, '')).filter(n => FIXED16.includes(n));
  check('   the roles are SystemUI\'s 50 (Android 14\'s 44 and inverse_surface, inverse_on_surface, inverse_primary, shadow, scrim, surface_tint), each light and dark; the 12 fixed colours unsuffixed', roleSet.length === 50 && ROLES50.every(r => names.includes(RES + r + '_light') && names.includes(RES + r + '_dark')) && ROLES44.every(r => ROLES50.includes(r)) && NEW6.every(r => ROLES50.includes(r)) && fixedSet.length === 12, JSON.stringify([roleSet.length, fixedSet.length]));
  const tones = await ev(([a, b]) => [MonetPlan.hct(a).tone, MonetPlan.hct(b).tone].map(t => Math.round(t * 10) / 10), [byRes(plan).accent1_500.slice(4), SHADE500_P14.accent1_500.slice(4)]);
  check('C2. shade 500 is tone 50 (Android 15 and 16\'s rule): accent1-3 and neutral1-2 500 as the oracle has them, not Android 14\'s (its rule asks for tone 49.6: monet.js\'s P14 output, t565 pins accent1_500); measured, accent1_500\'s tone is 50 here and 49.7 on Android 14', pinsOff(plan, SHADE500).length === 0 && Object.keys(SHADE500).every(n => SHADE500[n] !== SHADE500_P14[n]) && tones[0] === 50 && tones[1] === 49.7, JSON.stringify([pinsOff(plan, SHADE500), tones]));
  check('   the roles, the fixed colours and accent1_100: ' + Object.keys(P16_C0).length + ' values pinned from the oracle (one value of each of the six roles new on 15 QPR2 among them); primary_light is not Android 14\'s Fidelity one (0xFF513A8D)', pinsOff(plan, P16_C0).length === 0 && P16_C0.primary_light !== '0xFF513A8D', pinsOff(plan, P16_C0).join('; '));
  check('C3. the profile reaches the plan: it is MonetPlan.entries(FIDELITY, 6750A4, { spec 2021, dark, profile P16, contrast 0 }), and not the Android 14 one', (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 })) === 'same' && (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0 })) !== 'same', await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 }));
  check('   the record keeps the profile and the build: P16 and the phone\'s build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}' && (await ev(() => { const a = ovlRootApplied(); return a.profile + ' ' + a.build; })) === 'P16 ' + B16, await rec());
  // the contrast: set on the phone after the tab read the palette (the page's copy still says 0); Apply reads it when it is tapped
  await ev(() => { window.__contrast = 0.5; }); const cached = await ev(() => ovlPalette.contrast);
  await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const darkFixed = await ev(() => { const s = MonetPlan.scheme('FIDELITY', '6750A4', true, { spec: '2021', profile: 'P16', contrast: 0.5 }), cs = MonetPlan.lib.mcu.colorSpec; return [MonetPlan.lib.hex8(cs.primaryFixed().getArgb(s)), MonetPlan.lib.hex8(cs.onPrimaryFixed().getArgb(s))]; });
  check('C4. contrast 0.5 set in Settings after the tab was read (the page\'s copy says ' + cached + '): Apply reads it when tapped; the roles change (' + Object.keys(P16_C05).length + ' values pinned from the oracle at 0.5: primary_light 0xFF3E2578 for 0xFF4F378A, outline_dark, on_primary_container_dark), the shades do not (shade 500 as at 0)', cached === 0 && pinsOff(plan, P16_C05).length === 0 && pinsOff(plan, SHADE500).length === 0 && (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0.5 })) === 'same', JSON.stringify([cached, pinsOff(plan, P16_C05), pinsOff(plan, SHADE500)]));
  check('   the fixed colours come from the light scheme: in dark mode primary_fixed is 0xFF765FB4 and on_primary_fixed 0xFFFFFFFF (SystemUI\'s write sequence takes them from the light scheme in both modes), not the dark scheme\'s ' + darkFixed.join(' and '), byRes(plan).primary_fixed === '0xFF765FB4' && byRes(plan).on_primary_fixed === '0xFFFFFFFF' && darkFixed[0] !== '0xFF765FB4' && darkFixed[1] !== '0xFFFFFFFF', JSON.stringify(darkFixed));
  await ev(() => { window.__dark = false; }); await reset(); await apply(); const lightPlan = (await calls()).fab[0] || {}; await ev(() => { window.__dark = true; });
  check('   the same fixed colours in light mode (the plan is entries(..., dark false), its 12 fixed colours equal the dark-mode plan\'s)', (await sameAs(lightPlan, 'FIDELITY', '6750A4', { spec: '2021', dark: false, profile: 'P16', contrast: 0.5 })) === 'same' && FIXED16.every(n => byRes(lightPlan)[n] === byRes(plan)[n]), await sameAs(lightPlan, 'FIDELITY', '6750A4', { spec: '2021', dark: false, profile: 'P16', contrast: 0.5 }));
  // taken at the tap: changed between the tap and the answer, both ways
  await ev(() => { window.__contrast = 0.5; window.__delay = 400; }); await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { window.__contrast = 0; ovlReadPalette(); });
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('C5. contrast 0.5 when Apply is tapped, 0 by the time the theme answer comes (and read by the page): the plan is at 0.5', pinsOff(plan, P16_C05).length === 0, pinsOff(plan, P16_C05).join('; '));
  await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { window.__contrast = 0.5; ovlReadPalette(); });
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('   and the other way round: 0 at the tap, 0.5 before the answer: the plan is at 0', pinsOff(plan, P16_C0).length === 0, pinsOff(plan, P16_C0).join('; '));
  await ev(() => { window.__delay = 20; window.__contrast = null; ovlReadPalette(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const clamp = []; for (const v of [3, -7]) { await ev(v => { window.__contrast = v; }, v); await reset(); await apply(); clamp.push(await sameAs((await calls()).fab[0], 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: v > 0 ? 1 : -1 })); }
  check('   an answer without a contrast is 0; one outside -1..1 is held to it (3 is 1, -7 is -1)', pinsOff(plan, P16_C0).length === 0 && clamp.join(',') === 'same,same', JSON.stringify([pinsOff(plan, P16_C0), clamp]));
  await ev(() => { window.__contrast = 0; ovlReadPalette(); ovlRender(); });
  // Pure black with it, and alone
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('C6. Pure black on as well: still 177 entries, no resource twice; neutral1_900, neutral2_900, background_dark and surface_dark (fourth) black, the dark containers grey; the rest P16 Fidelity (primary_light 0xFF4F378A)', plan.entries && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177 && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && plan.entries[3].resource === LEAD[3] && plan.entries[3].argb === '0xFF000000' && r.surface_container_dark === '0xFF181818' && r.primary_light === '0xFF4F378A' && r.accent1_500 === '0xFF8069BF', JSON.stringify([(plan.entries || []).length, r.surface_dark, r.primary_light]));
  check('   the record: black true, Fidelity, P16, the build', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', await rec());
  await ev(() => { kvSet('ovl_root_style', ''); ovlRender(); }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   Pure black alone on P16: the hint names it; its 11 entries, as on Android 14 (ovlBuildBlackPlan); the record has no style, P16 and the build', c.hint === 'Also writes the pure black overlay with root.' && plan.entries && plan.entries.length === 11 && JSON.stringify(plan.entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(34))) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","profile":"P16","build":"' + B16 + '"}', JSON.stringify([c.hint, (plan.entries || []).length, await rec()]));
  // the preview: the same options as Apply
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); let p = await preview();
  check('C7. the preview on P16 shows the shades Apply writes: accent1 500 is #8069BF (tone 50; Android 14\'s #7F68BE)', p.rows === 5 && p.accent1[7] === '#8069BF', JSON.stringify(p));
  await phone({ sdk: 34, build: B14 }); p = await preview();
  check('   on Android 14 (P14): #7F68BE, as before', p.rows === 5 && p.accent1[7] === '#7F68BE', JSON.stringify(p.accent1[7]));
  await phone({ sdk: 36, profile: 'P16', build: B16, contrast: 0.5 }); p = await preview();
  check('   P16 at contrast 0.5: the same shades (the palettes do not depend on the contrast)', p.rows === 5 && p.accent1[7] === '#8069BF', JSON.stringify(p.accent1[7]));
  // a list style at 2025 on P16
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); ovlPickStyle('TONAL_SPOT'); ovlRender(); }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('C8. Tonal Spot in the list at 2025 on P16 (contrast 0.5): the setting gets TONAL_SPOT, the overlay entries(TONAL_SPOT, 6750A4, { spec 2025, dark, profile P16, contrast 0.5 }), 177; the record says 2025 and P16', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && plan.entries && plan.entries.length === 177 && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P16', contrast: 0.5 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","profile":"P16","build":"' + B16 + '"}', JSON.stringify([await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P16', contrast: 0.5 }), await rec()]));
  // Content and Monochrome in More styles on P16: part 2's 2021 Content (its tertiary palette from TemperatureCache.analogous) and Monochrome
  await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', 'CONTENT'); ovlPickColor('B5651D'); });
  await phone({ sdk: 36, profile: 'P16', build: B16, contrast: 0 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const contentSame = await sameAs(plan, 'CONTENT', 'B5651D', { spec: '2021', dark: true, profile: 'P16', contrast: 0 });
  check('C9. Content (More styles) on P16, B5651D, dark mode, contrast 0: 177 entries in the order of C1, MonetPlan.entries(CONTENT, B5651D, { spec 2021, dark, profile P16, contrast 0 }), not the Android 14 plan; ' + Object.keys(CONTENT_P16).length + ' values pinned from the oracle (accent3_500, the tertiary roles and tertiary_fixed among them); the record says Content and P16', plan.entries && JSON.stringify(plan.entries.map(e => e.resource)) === JSON.stringify(planOrder(ROLES50, FIXED16)) && contentSame === 'same' && pinsOff(plan, CONTENT_P16).length === 0 && (await sameAs(plan, 'CONTENT', 'B5651D', { spec: '2021', dark: true, profile: 'P14', contrast: 0 })) !== 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"CONTENT","profile":"P16","build":"' + B16 + '"}', JSON.stringify([contentSame, pinsOff(plan, CONTENT_P16), await rec()]));
  await ev(() => { kvSet('ovl_root_style', 'MONOCHROMATIC'); ovlPickColor('6750A4'); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const monoSame = await sameAs(plan, 'MONOCHROMATIC', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 });
  check('C10. Monochrome (More styles) on P16, 6750A4, dark mode, contrast 0: MonetPlan.entries(MONOCHROMATIC, 6750A4, { spec 2021, dark, profile P16, contrast 0 }); ' + Object.keys(MONO_P16).length + ' values pinned from the oracle; shade 500 is 0xFF777777 (tone 50), not Android 14\'s 0xFF767676', plan.entries && plan.entries.length === 177 && monoSame === 'same' && pinsOff(plan, MONO_P16).length === 0 && (await ev(() => MonetPlan.entries('MONOCHROMATIC', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0 }).find(e => /_accent1_500$/.test(e.resource)).argb)) === '0xFF767676', JSON.stringify([monoSame, pinsOff(plan, MONO_P16)]));
  await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', 'FIDELITY'); ovlPickColor('6750A4'); ovlRender(); });

  // ---- (d) Android 15 QPR1, 15 QPR2 and 14 ----
  await phone({ sdk: 35, profile: 'P15Q1', build: B15 }); c = await card(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const n1 = (plan.entries || []).map(e => e.resource);
  check('D1. Android 15 QPR1 (P15Q1): live; Fidelity\'s plan is 165 entries, no resource twice, the lookups first and then SystemUI\'s order with Android 14\'s 44 roles and 12 fixed colours in Android 14\'s order', !c.black.locked && !c.more.locked && n1.length === 165 && new Set(n1).size === 165 && JSON.stringify(n1) === JSON.stringify(planOrder(ROLES44, FIXED14)), JSON.stringify([n1.length, n1.slice(0, 6)]));
  check('   none of the six roles new on 15 QPR2; shade 500 tone 50 and the roles as the oracle has them (accent1_500 0xFF8069BF, primary_light 0xFF4F378A, surface_dark 0xFF141218, primary_fixed 0xFFE9DDFF); MonetPlan.entries with profile P15Q1; the record says P15Q1', NEW6.every(n => !n1.includes(RES + n + '_light')) && pinsOff(plan, { accent1_500: '0xFF8069BF', primary_light: '0xFF4F378A', surface_dark: '0xFF141218', primary_fixed: '0xFFE9DDFF' }).length === 0 && (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P15Q1', contrast: 0 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P15Q1","build":"' + B15 + '"}', JSON.stringify([await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P15Q1', contrast: 0 }), await rec()]));
  await phone({ sdk: 35, profile: 'P15Q2', build: B15 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D2. Android 15 QPR2 (P15Q2): 177 entries in Android 16.0\'s order (the same names, as SystemUI has them), MonetPlan.entries with profile P15Q2; the record says P15Q2', plan.entries && JSON.stringify(plan.entries.map(e => e.resource)) === JSON.stringify(planOrder(ROLES50, FIXED16)) && (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P15Q2', contrast: 0 })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P15Q2","build":"' + B15 + '"}', await rec());
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D3. Android 14 (P14): PR 3\'s plan (the 165 entries and values of entries without options, accent1_500 0xFF7F68BE); the record has the build and no profile', plan.entries && plan.entries.length === 165 && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan).accent1_500 === '0xFF7F68BE' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","build":"' + B14 + '"}', await rec());
  await phone({ sdk: 34, build: B14, contrast: 0.5 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   Android 14 at contrast 0.5: entries(..., { profile P14, contrast 0.5 }), and its roles are not the ones at 0', (await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0.5 })) === 'same' && (await sameAs(plan, 'FIDELITY', '6750A4')) !== 'same', await sameAs(plan, 'FIDELITY', '6750A4', { spec: '2021', dark: true, profile: 'P14', contrast: 0.5 }));

  // ---- (e) records ----
  const reads = await ev(id => {
    const out = [];
    for (const a of [{ ids: [id], t: 1, style: 'FIDELITY' }, { ids: [id], t: 1, style: 'FIDELITY', profile: 'P16', build: 'b/1' }, { ids: [id], t: 1, black: true, profile: 'P15Q1' }, { ids: [id], t: 1, style: 'FIDELITY', profile: '../x' }, { ids: [id], t: 1, style: 'FIDELITY', profile: 7, build: 'x'.repeat(1025) }]) { kvSet('ovl_root_applied', a); const r = ovlRootApplied(); out.push(r.profile + '/' + r.build); }
    kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: [id], t: 1 }); const r = ovlRootApplied(); out.push(r.profile + '/' + r.build); kvSet('ovl_black_applied', null);
    return out;
  }, AAM);
  check('E1. records: one from before the profiles reads as P14 without a build (and so does PR 2\'s pure black one); a profile and a build are read back; a profile that is not a name, or a build too long, counts for nothing', JSON.stringify(reads) === JSON.stringify(['P14/', 'P16/b/1', 'P15Q1/', 'P14/', 'P14/', 'P14/']), JSON.stringify(reads));

  // ---- (f) colours written on another build ----
  await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); });
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await reset(); await apply(); c = await card();
  check('F1. written on this build: no note about another build', c.stale === '' && (await rec()).includes('"build":"' + B16 + '"'), JSON.stringify([c.stale, await rec()]));
  await reset(); await phone({ sdk: 36, profile: 'P16', build: B16b }); c = await card(); k = await calls();
  check('F2. the phone now runs another build (a system update, still P16): the note says Android was updated and to tap Apply theme; nothing is written by itself (no overlay call)', c.stale === STALE && c.note === 'The theme overlay is written on this phone.' && c.hint === 'Also writes the Fidelity colors with root, in place of the style above.' && k.fab.length === 0 && k.apply.length === 0, JSON.stringify([c.stale, k.fab.length]));
  await ev(() => { switchView('home'); }); await sleep(150); await ev(() => { switchView('overlays'); }); await sleep(300); c = await card();
  check('   it is there when the tab is opened again', c.stale === STALE, c.stale);
  await ev(() => { kvSet('ovl_root_style', ''); ovlRender(); }); c = await card();
  check('   with nothing on (Apply would only switch the overlay off): no such note; the hint says Apply removes it', c.stale === '' && c.hint === 'Also removes the theme overlay.', JSON.stringify([c.stale, c.hint]));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await setRoot(false); c = await card();
  check('   without Super User: no such note', c.stale === '' && lockedAll(c, 'Needs Super User'), c.stale);
  await setRoot(true); await phone({ sdk: 36, profile: 'P16', build: null }); c = await card();
  check('   an answer without a build (an older APK): no such note', c.stale === '', c.stale);
  await phone({ sdk: 36, profile: 'P16Q1', build: B16b }); c = await card();
  check('   on a locked profile (P16Q1): no such note (Apply would not write them), the note says the overlay is on', c.stale === '' && c.note === 'The theme overlay is written on this phone.', JSON.stringify([c.stale, c.note]));
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY' }); }, AAM); await phone({ sdk: 36, profile: 'P16', build: B16b }); c = await card();
  check('   a record from before the profiles (no build): no such note', c.stale === '', c.stale);
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: true, style: '', profile: 'P16', build: b }); kvSet('ovl_black', true); kvSet('ovl_root_style', ''); ovlRender(); }, [AAM, B16]); c = await card();
  check('   Pure black alone, written on the old build and on again (its entries are the same on every build): no such note; the hint says Apply writes pure black', c.stale === '' && c.hint === 'Also writes the pure black overlay with root.', JSON.stringify([c.stale, c.hint]));
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P16', build: b }); ovlRender(); }, [AAM, B16]); c = await card();
  check('   a style written on the old build, Pure black alone on now (Apply would write no style): no such note', c.stale === '' && c.hint === 'Also writes the pure black overlay with root.', JSON.stringify([c.stale, c.hint]));
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); });
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P16', build: b }); ovlRender(); }, [AAM, B16]); c = await card();
  check('F3. back to the record of the old build on the new one: the note shows', c.stale === STALE, c.stale);
  await reset(); await apply(); c = await card(); k = await calls();
  check('   Apply writes the colours for this build: the record keeps the new build and the note goes', k.fab.length === 1 && k.fab[0].action === 'fabricate' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16b + '"}' && c.stale === '', JSON.stringify([await rec(), c.stale]));

  // ---- the new words in another language (assets/lang/de.js) ----
  await ev(() => I18N.set('de')); await sleep(600);
  const de = await ev(([a, b, c]) => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return [x[a] || '', x[b] || '', x[c] || '']; }, [NOT_YET, ONE_UI, STALE]);
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'FIDELITY', profile: 'P16', build: b }); }, [AAM, B16]);
  await phone({ sdk: 36, profile: 'P16', build: B16b }); await sleep(200); const deStale = (await card()).stale;
  await phone({ sdk: 36, profile: 'P16Q1', build: B16b }); await sleep(200); const deNotYet = await card();
  await phone({ sdk: 36, profile: 'unknown', reason: 'oneUi', build: B16b }); await sleep(200); const deOneUi = await card();
  check('G1. in German: the note on another build and both lock reasons read as assets/lang/de.js has them', de.every(t => t !== '') && de[0] !== NOT_YET && deStale === de[2] && lockedAll(deNotYet, de[0]) && lockedAll(deOneUi, de[1]), JSON.stringify([de, deStale, deNotYet.black.lock, deOneUi.black.lock]));

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
