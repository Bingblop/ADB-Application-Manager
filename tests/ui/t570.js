// Accurate shades (ColorBlendr item 6, PR 8): the switch under Secondary and tertiary accents in the Overlays tab's Root-only theme
// controls card (on by default; kv ovl_accurate_shades, '0' off, absent or anything else on) and what Apply writes with it off (monet.js's
// opts.accurate false: tone 90 of the primary, secondary and tertiary palettes is read as tone 70, after the accents and the sliders, so in
// accent1, accent2 and accent3 the shade-100 entry takes the row's shade-300 colour and every role and fixed colour taken from those palettes
// at tone 90 (its tone as the spec and the contrast compute it) takes the tone-70 colour; the neutral rows and every other entry are
// unchanged). (a) Locks, as Intensity and the accents have them: Update the app (no
// overlayFabricate, or a monet.js without the option), the build family's lock (T-049), Needs Super User; Needs Android 14 on Android 12
// and 13; a stored off counts for nothing there and the handler changes nothing and calls no AndroidBridge method. (b) Live on Android 14
// (P14) and 16.0 (P16), on by default; what the store reads as off. (c) Flipping it (a tap, the keyboard): stored, the preview and the hint
// follow, no bridge method but the page's reads, no theme or overlay call. (d) Apply: a list style at 2021 with the sliders at 100, no
// accent and the switch off is written by the app (MonetPlan.entries with accurate false) on P16 and P14 and the record keeps accurate
// false; the value of the tap; off with a More style, with the sliders, with accents, at 2025, under Monochrome; Pure black wins; with the
// switch on everything is what the base writes. (e) Undo (T-069) rebuilds a record's own choices, accurate false included. (f) The records;
// the note on colours written on another version of Android (T-077). (g) A restart before the theme's answer (T-068): the resume record
// keeps the tap's value. (h) The maths through the page's MonetPlan: identity when on; off changes the three _100 accent entries (each
// its row's _300) and exactly the roles and fixed colours the rule names (written here on the page's scheme with the switch on), nothing
// else; after the accents and the sliders; what throws; and values pinned from an oracle that does not use the new code (kept outside the
// repository: the monet.js from before the option, 5b44669b, gives the entries and the schemes of the same call; the rule is applied there
// by hand; every other pinned value is that file's own). (j) German. (k) The row at 320 px in English and German.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const TITLE = 'Accurate shades';
const SUB = 'On: every accent shade keeps its own tone. Off: the pale accent colors (shade 100 and, mostly with Color spec 2021, the light container and fixed colors) take a darker tone.';
const OFF = 'Accurate shades: off.';
const NOT_YET = 'Not supported on this Android version yet';
const ONE_UI = 'Not supported on One UI';
const AAM = 'com.android.shell:aam_monet';
const RES = 'android:color/system_';
const KV = 'ovl_accurate_shades';
const B14 = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
const B16 = 'google/mock/mock:16/BP2A.250605.031/13578712:user/release-keys';
const SAID = { sec: 'Secondary accent: your color.', both: 'Secondary and tertiary accents: your colors.' };
const HINT = {
  list: 'Also writes this style\'s colors with root.',
  listBlack: 'Also writes this style\'s colors and pure black with root.',
  list25: 'Also writes this style\'s 2025 colors with root, in place of Android\'s.',
  adj: 'Also writes this style\'s colors with root, adjusted by the Intensity sliders.',
  more: n => 'Also writes the ' + n + ' colors with root, in place of the style above.',
  moreAdj: n => 'Also writes the ' + n + ' colors with root, in place of the style above, adjusted by the Intensity sliders.'
};
const BOTH = { secondary: 'E91E63', tertiary: '00897B' }, A150 = { accentSat: 150, bgSat: 100, bgLight: 100 };
// Oracle pins (pr8-oracle/roles/t570-pins-roles.cjs on the monet.js from before the option): [profile, style, seed, spec, accents, adjust,
// the entries with the switch off (the three _100 entries are the base's _300 of the same call; a role or fixed colour whose palette is an
// accent palette and whose tone, computed by the base, is exactly 90 is Hct(that palette's hue and chroma, tone 70); the others the base's
// own), the base's values of the pinned entries the switch changes (the switch on)]; contrast 0, dark mode
const PINS = [
  ['P16', 'TONAL_SPOT', '6750A4', '2021', null, null, { accent1_100: '0xFFB3A2E1', accent2_100: '0xFFB0A7C0', accent3_100: '0xFFD29DAD', accent1_300: '0xFFB3A2E1', accent2_200: '0xFFCBC2DB', accent3_300: '0xFFD29DAD', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EB', primary_light: '0xFF65558F', primary_container_light: '0xFFB3A2E1', on_primary_container_dark: '0xFFB3A2E1', secondary_container_light: '0xFFB0A7C0', tertiary_container_light: '0xFFD29DAD', on_tertiary_container_dark: '0xFFD29DAD', primary_fixed: '0xFFB3A2E1', secondary_fixed: '0xFFB0A7C0', tertiary_fixed: '0xFFD29DAD', control_activated_light: '0xFFB3A2E1', palette_key_color_primary_light: '0xFF7E6EA9' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFE8DEF8', accent3_100: '0xFFFFD9E3', primary_container_light: '0xFFE9DDFF', on_primary_container_dark: '0xFFE9DDFF', secondary_container_light: '0xFFE8DEF8', tertiary_container_light: '0xFFFFD9E3', on_tertiary_container_dark: '0xFFFFD9E3', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFE8DEF8', tertiary_fixed: '0xFFFFD9E3', control_activated_light: '0xFFE9DDFF' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', null, null, { accent1_100: '0xFFB3A2E1', accent2_100: '0xFFB0A7C0', accent3_100: '0xFFD29DAD', accent1_300: '0xFFB3A2E1', accent2_200: '0xFFCBC2DB', accent3_300: '0xFFD29DAD', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EB', primary_light: '0xFF65558F', primary_container_light: '0xFFB3A2E1', on_primary_container_dark: '0xFFB3A2E1', secondary_container_light: '0xFFB0A7C0', tertiary_container_light: '0xFFD29DAD', on_tertiary_container_dark: '0xFFD29DAD', primary_fixed: '0xFFB3A2E1', secondary_fixed: '0xFFB0A7C0', tertiary_fixed: '0xFFD29DAD', control_activated_light: '0xFFB3A2E1', palette_key_color_primary_light: '0xFF7E6EA9' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFE8DEF8', accent3_100: '0xFFFFD9E3', primary_container_light: '0xFFE9DDFF', on_primary_container_dark: '0xFFE9DDFF', secondary_container_light: '0xFFE8DEF8', tertiary_container_light: '0xFFFFD9E3', on_tertiary_container_dark: '0xFFFFD9E3', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFE8DEF8', tertiary_fixed: '0xFFFFD9E3', control_activated_light: '0xFFE9DDFF' }],
  ['P16', 'FIDELITY', '6750A4', '2021', null, null, { accent1_100: '0xFFB69DF7', accent2_100: '0xFFB1A5CC', accent3_100: '0xFFC9A74D', accent1_300: '0xFFB69DF7', accent2_200: '0xFFCDC0E9', accent3_300: '0xFFC9A74D', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EE', primary_light: '0xFF4F378A', primary_container_light: '0xFF6750A4', on_primary_container_dark: '0xFFE0D2FF', secondary_container_light: '0xFFE1D4FD', tertiary_container_light: '0xFFC9A74D', on_tertiary_container_dark: '0xFF503D00', primary_fixed: '0xFFB69DF7', secondary_fixed: '0xFFB1A5CC', tertiary_fixed: '0xFFC9A74D', control_activated_light: '0xFFB69DF7', palette_key_color_primary_light: '0xFF8069BF' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFE9DDFF', accent3_100: '0xFFFFDF93', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFE9DDFF', tertiary_fixed: '0xFFFFDF93', control_activated_light: '0xFFE9DDFF' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2021', null, A150, { accent1_100: '0xFFB69BFF', accent2_100: '0xFFB1A5CC', accent3_100: '0xFFE394AE', accent1_300: '0xFFB69BFF', accent2_200: '0xFFCDC0E9', accent3_300: '0xFFE394AE', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EB', primary_light: '0xFF684CB0', primary_container_light: '0xFFB69BFF', on_primary_container_dark: '0xFFB69BFF', secondary_container_light: '0xFFB1A5CC', tertiary_container_light: '0xFFE394AE', on_tertiary_container_dark: '0xFFE394AE', primary_fixed: '0xFFB69BFF', secondary_fixed: '0xFFB1A5CC', tertiary_fixed: '0xFFE394AE', control_activated_light: '0xFFB69BFF', palette_key_color_primary_light: '0xFF8166CB' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFE9DDFF', accent3_100: '0xFFFFD9E3', primary_container_light: '0xFFE9DDFF', on_primary_container_dark: '0xFFE9DDFF', secondary_container_light: '0xFFE9DDFF', tertiary_container_light: '0xFFFFD9E3', on_tertiary_container_dark: '0xFFFFD9E3', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFE9DDFF', tertiary_fixed: '0xFFFFD9E3', control_activated_light: '0xFFE9DDFF' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2021', {secondary: 'E91E63'}, null, { accent1_100: '0xFFB3A2E1', accent2_100: '0xFFE794A2', accent3_100: '0xFFD29DAD', accent1_300: '0xFFB3A2E1', accent2_200: '0xFFFFB2BE', accent3_300: '0xFFD29DAD', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EB', primary_light: '0xFF65558F', primary_container_light: '0xFFB3A2E1', on_primary_container_dark: '0xFFB3A2E1', secondary_container_light: '0xFFE794A2', tertiary_container_light: '0xFFD29DAD', on_tertiary_container_dark: '0xFFD29DAD', primary_fixed: '0xFFB3A2E1', secondary_fixed: '0xFFE794A2', tertiary_fixed: '0xFFD29DAD', control_activated_light: '0xFFB3A2E1', palette_key_color_primary_light: '0xFF7E6EA9' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFFFD9DE', accent3_100: '0xFFFFD9E3', primary_container_light: '0xFFE9DDFF', on_primary_container_dark: '0xFFE9DDFF', secondary_container_light: '0xFFFFD9DE', tertiary_container_light: '0xFFFFD9E3', on_tertiary_container_dark: '0xFFFFD9E3', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFFFD9DE', tertiary_fixed: '0xFFFFD9E3', control_activated_light: '0xFFE9DDFF' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', {secondary: 'E91E63', tertiary: '00897B'}, A150, { accent1_100: '0xFFB69BFF', accent2_100: '0xFFFF869D', accent3_100: '0xFF00C0AD', accent1_300: '0xFFB69BFF', accent2_200: '0xFFFFB2BE', accent3_300: '0xFF00C0AD', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E0E9', neutral2_100: '0xFFE7E0EB', primary_light: '0xFF684CB0', primary_container_light: '0xFFB69BFF', on_primary_container_dark: '0xFFB69BFF', secondary_container_light: '0xFFFF869D', tertiary_container_light: '0xFF00C0AD', on_tertiary_container_dark: '0xFF00C0AD', primary_fixed: '0xFFB69BFF', secondary_fixed: '0xFFFF869D', tertiary_fixed: '0xFF00C0AD', control_activated_light: '0xFFB69BFF', palette_key_color_primary_light: '0xFF8166CB' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFFFD9DE', accent3_100: '0xFF55FBE4', primary_container_light: '0xFFE9DDFF', on_primary_container_dark: '0xFFE9DDFF', secondary_container_light: '0xFFFFD9DE', tertiary_container_light: '0xFF55FBE4', on_tertiary_container_dark: '0xFF55FBE4', primary_fixed: '0xFFE9DDFF', secondary_fixed: '0xFFFFD9DE', tertiary_fixed: '0xFF55FBE4', control_activated_light: '0xFFE9DDFF' }],
  ['P16', 'CMF', '6750A4', '2026', {secondary: 'E91E63', tertiary: 'FFD600'}, A150, { accent1_100: '0xFFB69BFF', accent2_100: '0xFFFF869D', accent3_100: '0xFFC9A900', accent1_300: '0xFFB69BFF', accent2_200: '0xFFFFB2BE', accent3_300: '0xFFC9A900', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE7E0EE', neutral2_100: '0xFFE7E0EE', primary_light: '0xFF5F2CCA', primary_container_light: '0xFF6C3CD7', on_primary_container_dark: '0xFFFCF6FF', secondary_container_light: '0xFFFF5580', tertiary_container_light: '0xFF705D00', on_tertiary_container_dark: '0xFFFFF7E8', primary_fixed: '0xFF6C3CD7', secondary_fixed: '0xFFFF5580', tertiary_fixed: '0xFF705D00', control_activated_light: '0xFFB69BFF', palette_key_color_primary_light: '0xFF8559F1' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFFFD9DE', accent3_100: '0xFFFFE170', control_activated_light: '0xFFE9DDFF' }],
  ['P16', 'MONOCHROMATIC', '6750A4', '2021', null, null, { accent1_100: '0xFFABABAB', accent2_100: '0xFFABABAB', accent3_100: '0xFFABABAB', accent1_300: '0xFFABABAB', accent2_200: '0xFFC6C6C6', accent3_300: '0xFFABABAB', accent1_50: '0xFFF1F1F1', neutral1_100: '0xFFE2E2E2', neutral2_100: '0xFFE2E2E2', primary_light: '0xFF000000', primary_container_light: '0xFF3B3B3B', on_primary_container_dark: '0xFF000000', secondary_container_light: '0xFFD4D4D4', tertiary_container_light: '0xFF747474', on_tertiary_container_dark: '0xFF000000', primary_fixed: '0xFF5E5E5E', secondary_fixed: '0xFFC6C6C6', tertiary_fixed: '0xFF5E5E5E', control_activated_light: '0xFFABABAB', palette_key_color_primary_light: '0xFF777777' }, { accent1_100: '0xFFE2E2E2', accent2_100: '0xFFE2E2E2', accent3_100: '0xFFE2E2E2', control_activated_light: '0xFFE2E2E2' }],
  ['P15Q2', 'TONAL_SPOT', '6750A4', '2025', null, null, { accent1_100: '0xFFB1A5D0', accent2_100: '0xFFB0A7C0', accent3_100: '0xFFCE9DBF', accent1_300: '0xFFB1A5D0', accent2_200: '0xFFCBC2DB', accent3_300: '0xFFCE9DBF', accent1_50: '0xFFF6EEFF', neutral1_100: '0xFFE6E1E7', neutral2_100: '0xFFE7E0EC', primary_light: '0xFF655789', primary_container_light: '0xFFD4C3FD', on_primary_container_dark: '0xFFE9DEFF', secondary_container_light: '0xFFB0A7C0', tertiary_container_light: '0xFFF4BFE3', on_tertiary_container_dark: '0xFF5F3956', primary_fixed: '0xFFD4C3FD', secondary_fixed: '0xFFB0A7C0', tertiary_fixed: '0xFFF4BFE3', control_activated_light: '0xFFB3A3DA', palette_key_color_primary_light: '0xFF7D6FA2' }, { accent1_100: '0xFFE9DDFF', accent2_100: '0xFFE8DEF8', accent3_100: '0xFFFFD7F1', secondary_container_light: '0xFFE8DEF8', secondary_fixed: '0xFFE8DEF8', control_activated_light: '0xFFE9DDFF' }],
  ['P14', 'VIBRANT', '1B6EF3', '2025', null, null, { accent1_100: '0xFF86A9FF', accent2_100: '0xFF97A5FF', accent3_100: '0xFFE58BE5', accent1_300: '0xFF86A9FF', accent2_200: '0xFFBBC3FF', accent3_300: '0xFFE58BE5', accent1_50: '0xFFEEF0FF', neutral1_100: '0xFFDEE0FF', neutral2_100: '0xFFDEE0FF', primary_light: '0xFF0055C6', primary_container_light: '0xFF749EFF', on_primary_container_dark: '0xFF002054', secondary_container_light: '0xFFC9CFFF', tertiary_container_light: '0xFFFDA0FC', on_tertiary_container_dark: '0xFF67176C', primary_fixed: '0xFF749EFF', secondary_fixed: '0xFFC9CFFF', tertiary_fixed: '0xFFFDA0FC', control_activated_light: '0xFF86A9FF', palette_key_color_primary_light: '0xFF006EFD' }, { accent1_100: '0xFFD9E2FF', accent2_100: '0xFFDEE0FF', accent3_100: '0xFFFFD6F9', control_activated_light: '0xFFD9E2FF' }],
  ['P14', 'FIDELITY', '00897B', '2021', null, null, { accent1_100: '0xFF52BCAC', accent2_100: '0xFF8DB3AB', accent3_100: '0xFFF19372', accent1_300: '0xFF52BCAC', accent2_200: '0xFFA8CEC6', accent3_300: '0xFFF19372', accent1_50: '0xFFB4FFF1', neutral1_100: '0xFFDFE4E1', neutral2_100: '0xFFD8E5E1', primary_light: '0xFF005B51', primary_container_light: '0xFF008376', on_primary_container_dark: '0xFFCDFFF5', secondary_container_light: '0xFFC7EFE6', tertiary_container_light: '0xFFB05E41', on_tertiary_container_dark: '0xFFFFF3F0', primary_fixed: '0xFF52BCAC', secondary_fixed: '0xFF8DB3AB', tertiary_fixed: '0xFFF19372', control_activated_light: '0xFF52BCAC', palette_key_color_primary_light: '0xFF00897B' }, { accent1_100: '0xFF8DF5E4', accent2_100: '0xFFC3EBE2', accent3_100: '0xFFFFDBCF', primary_fixed: '0xFF8DF5E4', secondary_fixed: '0xFFC3EBE2', tertiary_fixed: '0xFFFFDBCF', control_activated_light: '0xFF8DF5E4' }]
];
const pinOf = (profile, style, spec, accents, adjust) => PINS.find(p => p[0] === profile && p[1] === style && p[3] === spec && JSON.stringify(p[4]) === JSON.stringify(accents) && JSON.stringify(p[5]) === JSON.stringify(adjust));
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  // the same for the maths: when MonetPlan throws (a broken monet.js) the line says so as a FAIL and the check gets the fallback
  const evm = (fn, arg, fallback) => ev(fn, arg).catch(e => { console.log('FAIL the maths threw: ' + String(e.message).split('\n')[0]); return fallback; });
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  const phone = async o => {
    await ev(o => { window.__sdk = o.sdk; window.__profile = o.profile; window.__profileReason = o.reason; window.__build = o.build === undefined ? null : o.build; window.__contrast = 0; ovlReadPalette(); ovlRender(); }, o);
    await sleep(150);
  };
  // the stored switch (null: never set), drawn again
  const setAccu = async v => { await ev(([v, k]) => { if (v === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v); ovlRender(); }, [v, KV]); await sleep(50); };
  const setAcc = async (s, t) => { await ev(v => { ['ovl_accent_secondary', 'ovl_accent_tertiary'].forEach((k, i) => { if (v[i] === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v[i]); }); ovlRender(); }, [s, t]); await sleep(50); };
  const setAdj = async (a, b, c) => { await ev(v => { ['ovl_adj_accent_sat', 'ovl_adj_bg_sat', 'ovl_adj_bg_light'].forEach((k, i) => { if (v[i] === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v[i]); }); ovlRender(); }, [a, b, c]); await sleep(50); };
  const setCard = async (more, spec, black) => { await ev(([more, spec, black]) => { kvSet('ovl_root_style', more); kvSet('ovl_spec', spec); kvSet('ovl_black', black); ovlRender(); }, [more, spec, black]); await sleep(50); };
  // the row's container: the locked row, or the row with its switch; the hint under Apply (its three sentences) and the preview
  const row = () => ev(() => {
    const box = document.getElementById('ovlRootAccurate') || document.createElement('div');      // none: a page without the row
    const r = box.querySelector('.rf-row'), lock = box.querySelector('.pm-lock'), lockBtn = box.querySelector('.op-mode-btn.locked'), sw = box.querySelector('#ovlAccurateSw'), pv = document.getElementById('ovlRootPreview');
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(r && r.classList.contains('is-locked')), name: r ? r.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', lock: lock ? lock.innerText : '', lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null,
      sw: sw ? { on: sw.classList.contains('on'), checked: sw.getAttribute('aria-checked'), role: sw.getAttribute('role'), label: sw.getAttribute('aria-label'), tag: sw.tagName, type: sw.getAttribute('type') } : null,
      hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, said: (document.getElementById('ovlApplyHintAccents') || { innerText: '' }).innerText, off: (document.getElementById('ovlApplyHintAccurate') || { innerText: '' }).innerText,
      preview: !!pv && pv.innerHTML !== '', acc: (document.querySelector('#ovlRootAccents .pm-lock') || { innerText: '' }).innerText, adj: (document.querySelector('#ovlRootAdjust .pm-lock') || { innerText: '' }).innerText };
  });
  const lockedAs = (a, reason) => a && a.rows === 1 && a.locked && a.lock === reason && a.lockBtn && a.lockBtn.disabled && a.sw === null;
  const liveOn = (a, on) => a && a.rows === 1 && !a.locked && a.name === TITLE + ' ' + SUB && a.sw && a.sw.on === on && a.sw.checked === String(on) && a.sw.role === 'switch' && a.sw.label === TITLE && a.sw.tag === 'BUTTON' && a.sw.type === 'button';
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  // a tap that cannot stop the script: when something covers the control (a failure sheet, in a run against a broken page) the line says so
  // as a FAIL, the open sheets are closed, and the run goes on to the checks after it
  const tap = sel => page.locator(sel).click({ timeout: 5000 }).then(() => true, async e => { console.log('FAIL could not tap ' + sel + ': ' + String(e.message).split('\n')[0]); await ev(() => document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show'))); return false; });
  const apply = async () => { await tap('#ovlApplyBtn'); await settle(); await sleep(100); };
  const undo = async () => { await tap('#sdbSnackUndo'); await settle(); await sleep(150); };
  const waitFab = () => page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => { let want; try { want = MonetPlan.entries(style, seed, opts || undefined); } catch (e) { return 'throws: ' + e.message; } if (!entries || want.length !== entries.length) return 'count ' + (entries || []).length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan && plan.entries, style, seed, opts || null]);
  // T-069: a record with a style also keeps the source colour, the mode and the contrast (t564 checks them); the record strings compared here leave them out
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  const o16 = (spec, more) => Object.assign({ spec: spec || '2021', dark: true, profile: 'P16', contrast: 0 }, more || {});
  const o14 = (spec, more) => Object.assign({ spec: spec || '2021', dark: true, profile: 'P14', contrast: 0 }, more || {});
  const OFFO = { accurate: false };
  // every AndroidBridge method, counted (name and arguments)
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });
  await ev(() => { window.__bc = []; const b = window.AndroidBridge; for (const k of Object.keys(b)) if (typeof b[k] === 'function') { const f = b[k]; b[k] = function () { window.__bc.push([k].concat(Array.from(arguments, a => String(a)))); return f.apply(this, arguments); }; } });
  // the page's reads (the settings, the dark mode, the phone's palette and contrast) and its tap vibration: none of them writes anything
  const READS = ['loadSetting', 'isSystemDarkMode', 'getSystemPalette', 'vibrate'];
  // the handler called directly: [stored value, the bridge methods it called (none while locked)]
  const handler = () => ev(k => {
    if (typeof ovlToggleAccurate !== 'function') return 'no handler';
    window.__bc.length = 0; ovlToggleAccurate();
    const w = window.__bc.map(c => c[0]);
    return [kvGet(k, null), w.length].concat(w.length ? [w] : []);
  }, KV);

  // ---- (a) locks ----
  await ev(() => switchView('overlays')); await sleep(300);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_root_applied', null); });
  await phone({ sdk: 34, build: B14 });
  let a = await row();
  check('A1. without Super User (Android 14): Accurate shades is a locked row of the Root Features kind (Locked, Needs Super User) with its line, no switch', lockedAs(a, 'Needs Super User') && a.name === TITLE + ' ' + SUB && a.lockBtn.text === 'Locked', JSON.stringify(a));
  check('   its container sits under Secondary and tertiary accents and above the notes, inside the Root-only theme controls card', await ev(() => { const box = document.getElementById('ovlRootAccurate'); return !!box && !!box.closest('#ovlRootCard') && box.previousElementSibling.id === 'ovlRootAccents' && box.nextElementSibling.id === 'ovlRootNote'; }));
  await setAccu('0'); a = await row();
  check('   a stored off (\'0\', from a rooted session) counts for nothing: still locked, no hint, no plan style, ovlRootAccurateOn() true', lockedAs(a, 'Needs Super User') && a.hint === '' && a.off === '' && (await ev(() => typeof ovlRootAccurateOn === 'function' && ovlRootAccurateOn() === true && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify(a));
  let h = JSON.stringify(await handler());
  check('   the handler called directly changes nothing (still \'0\') and calls no AndroidBridge method at all', h === '["0",0]', h);
  await reset(); await apply(); let k = await calls();
  check('   Apply writes the theme setting only: no overlay call', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); await setRoot(true); a = await row();
  check('A2. Super User on Android 13 (P12): a locked row saying Needs Android 14, as the accents and Intensity', lockedAs(a, 'Needs Android 14') && a.acc === 'Needs Android 14' && a.adj === 'Needs Android 14', JSON.stringify([a.lock, a.acc, a.adj]));
  h = JSON.stringify(await handler());
  check('   the stored off counts for nothing there (no hint); the handler changes nothing and calls no AndroidBridge method', a.hint === '' && a.off === '' && h === '["0",0]', h);
  const LOCKS = [
    ['Android 16 QPR1 (P16Q1)', { sdk: 36, profile: 'P16Q1', build: B16 }, NOT_YET],
    ['a One UI phone on Android 14', { sdk: 34, profile: 'unknown', reason: 'oneUi', build: B14 }, ONE_UI],
    ['an older APK on Android 16 (no profile)', { sdk: 36, profile: null, build: null }, 'Update the app']
  ];
  for (const [label, o, reason] of LOCKS) {
    await phone(o); a = await row();
    check('A3. Super User, ' + label + ': locked with "' + reason + '", as the accents and Intensity', lockedAs(a, reason) && a.acc === reason && a.adj === reason && a.hint === '' && a.off === '', JSON.stringify([a.lock, a.acc, a.adj, a.hint]));
    await reset(); await apply(); k = await calls();
    check('   the handler changes nothing and calls no AndroidBridge method; Apply writes the theme setting only', JSON.stringify(await handler()) === '["0",0]' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  }
  await ev(() => { window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; });
  await phone({ sdk: 34, build: B14 }); a = await row();
  check('A4. an APK without overlayFabricate, Super User on Android 14: Update the app, as the other rows', lockedAs(a, 'Update the app') && a.acc === 'Update the app' && a.adj === 'Update the app', JSON.stringify([a.lock, a.acc, a.adj]));
  await setRoot(false); a = await row();
  check('   and without Super User: Update the app', lockedAs(a, 'Update the app'), a.lock);
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; }); await setRoot(true);
  // a colour maths that does not know the switch (the monet.js from before it: MonetPlan.lib without accurateOf)
  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = Object.assign({}, window.__mp, { lib: Object.assign({}, window.__mp.lib, { accurateOf: undefined }) }); });
  await phone({ sdk: 34, build: B14 }); a = await row();
  check('A5. Android 14 with Super User and a monet.js without the option: Update the app; the accents and Intensity stay live', lockedAs(a, 'Update the app') && a.acc === '' && a.adj === '' && (await ev(() => !!document.getElementById('ovlAccSecondary') && !!document.getElementById('ovlAdjAccentSat'))), JSON.stringify([a.lock, a.acc, a.adj]));
  h = JSON.stringify(await handler());
  check('   the stored off counts for nothing (no hint, Tonal Spot at 2021 gives no plan style); the handler changes nothing and calls no AndroidBridge method', a.hint === '' && a.off === '' && (await ev(() => typeof ovlRootAccurateOn === 'function' && ovlRootAccurateOn() === true && ovlRootPlanOn('TONAL_SPOT').style === '')) && h === '["0",0]', h);
  await reset(); await apply(); k = await calls();
  check('   Apply writes the theme setting only', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); a = await row();
  check('   and on Android 13: Update the app comes before Needs Android 14', lockedAs(a, 'Update the app') && a.acc === 'Needs Android 14', JSON.stringify([a.lock, a.acc]));
  await ev(() => { window.MonetPlan = window.__mp; });

  // ---- (b) live on Android 14 and 16, on by default ----
  await setAccu(null); await phone({ sdk: 34, build: B14 }); a = await row();
  check('B1. Super User on Android 14 (P14): the row with its line and a switch (a button, role switch, named Accurate shades), on by default with nothing stored', liveOn(a, true) && (await kv(KV)) === null, JSON.stringify(a));
  check('   on: Tonal Spot in the list at 2021 gives no hint, no preview, no plan style (Android writes its own colours); ovlRootAccurateOn() true', a.hint === '' && a.off === '' && !a.preview && (await ev(() => typeof ovlRootAccurateOn === 'function' && ovlRootAccurateOn() === true && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify([a.hint, a.preview]));
  const reads = await ev(k => ['0', '1', 0, false, 'off', '', 'false', true, ['0'], { v: '0' }].map(v => { kvSet(k, v); return typeof ovlAccurateStored === 'function' ? ovlAccurateStored() : 'missing'; }), KV);
  check('   what the store reads: \'0\' is off; \'1\', 0, false, \'off\', \'\', \'false\', true, an array and an object are on', JSON.stringify(reads) === JSON.stringify([false, true, true, true, true, true, true, true, true, true]), JSON.stringify(reads));
  await setAccu(null); await phone({ sdk: 36, profile: 'P16', build: B16 }); a = await row();
  check('B2. Super User on Android 16.0 (P16): the same live row, on', liveOn(a, true) && a.hint === '' && !a.preview, JSON.stringify(a));

  // ---- (c) flipping it (P16, Tonal Spot at 2021, sliders at 100, no accent) ----
  const palOf = (style, opts) => ev(([style, opts]) => { const o = Object.assign({ spec: '2021', dark: systemPrefersDark(), profile: 'P16', contrast: 0 }, opts || {}); const p = MonetPlan.palettes(style, '6750A4', o); const r = {}; for (const k of ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2']) r[k] = p[k].shades.map(h => '#' + h); return r; }, [style, opts]);
  const pvRows = () => ev(() => { const rows = Array.from(document.querySelectorAll('#ovlRootPreview .ovl-pal')); const hx = r => r ? Array.from(r.querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : []; return { rows: rows.length, accent1: hx(rows[0]), accent2: hx(rows[1]), accent3: hx(rows[2]), neutral1: hx(rows[3]), neutral2: hx(rows[4]) }; });
  const plain = await evm(() => { const p = MonetPlan.palettes('TONAL_SPOT', '6750A4', { spec: '2021', dark: systemPrefersDark(), profile: 'P16', contrast: 0 }); const r = {}; for (const k of ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2']) r[k] = p[k].shades.map(h => '#' + h); return r; }, undefined, {});
  await ev(() => { window.__bc.length = 0; window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  if (await page.$('#ovlAccurateSw')) await tap('#ovlAccurateSw'); await sleep(150);
  let bc = await ev(() => window.__bc.map(c => c.slice())); a = await row(); let pv = await pvRows(); let want = await palOf('TONAL_SPOT', OFFO);
  const other = c => !READS.includes(c[0]);
  check('C1. a tap on the switch: stored as \'0\' (one saveSetting) and no bridge method but that, the page\'s reads and its tap vibration: no theme and no overlay call', JSON.stringify(bc.filter(other)) === '[["saveSetting","ovl_accurate_shades","\\"0\\""]]' && (await kv(KV)) === '0' && (await calls()).fab.length === 0 && (await calls()).apply.length === 0, JSON.stringify([bc.filter(other), await kv(KV)]));
  check('   the switch is off (aria-checked false) and keeps the focus', liveOn(a, false) && (await ev(() => document.activeElement && document.activeElement.id)) === 'ovlAccurateSw', JSON.stringify(a.sw));
  check('   the hint: the app writes this style\'s colors, then "' + OFF + '" (no accent sentence)', a.hint === HINT.list && a.said === '' && a.off === OFF, JSON.stringify([a.hint, a.said, a.off]));
  const swapped = (rows, base) => ['accent1', 'accent2', 'accent3'].every(r => rows[r][3] === rows[r][5] && rows[r][3] !== base[r][3] && rows[r].every((h, i) => i === 3 || h === base[r][i])) && ['neutral1', 'neutral2'].every(r => JSON.stringify(rows[r]) === JSON.stringify(base[r]));
  check('   the preview appears: in each accent row shade 100 shows shade 300\'s colour (MonetPlan.palettes with accurate false), every other swatch and the neutral rows are the style\'s', pv.rows === 5 && ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2'].every(r => JSON.stringify(pv[r]) === JSON.stringify(want[r])) && swapped(pv, plain), JSON.stringify([pv.accent1.slice(2, 6), plain.accent1 && plain.accent1.slice(2, 6)]));
  await ev(() => { window.__bc.length = 0; });
  await page.focus('#ovlAccurateSw').catch(() => {}); await page.keyboard.press('Space'); await sleep(150);
  bc = await ev(() => window.__bc.map(c => c.slice())); a = await row();
  check('C2. Space on the focused switch: back on, stored as \'1\' (one saveSetting, nothing else written), the focus kept; no hint, no preview', JSON.stringify(bc.filter(other)) === '[["saveSetting","ovl_accurate_shades","\\"1\\""]]' && liveOn(a, true) && (await ev(() => document.activeElement && document.activeElement.id)) === 'ovlAccurateSw' && a.hint === '' && a.off === '' && !a.preview, JSON.stringify([bc.filter(other), a.sw, a.hint]));
  await ev(() => { window.__bc.length = 0; });
  await page.keyboard.press('Enter'); await sleep(150); a = await row();
  check('   Enter: off again (\'0\'), the hint and the preview are back', (await kv(KV)) === '0' && liveOn(a, false) && a.off === OFF && a.preview, JSON.stringify([await kv(KV), a.sw, a.off]));

  // ---- (d) Apply ----
  await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  const pTS16 = pinOf('P16', 'TONAL_SPOT', '2021', null, null);
  check('D1. P16, Tonal Spot in the list at 2021, the sliders at 100, no accent, the switch off: the setting gets TONAL_SPOT, then overlayFabricate with 177 entries, no resource twice', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && plan.action === 'fabricate' && plan.entries && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177, JSON.stringify([k.apply, (plan.entries || []).length]));
  let s1 = await sameAs(plan, 'TONAL_SPOT', '6750A4', o16('2021', OFFO));
  check('   it is MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P16, contrast 0, accurate false }), not the style\'s own; ' + Object.keys(pTS16[6]).length + ' values pinned from the oracle (accentN_100 the base\'s accentN_300; the light containers, the dark on-containers, the fixed colours and control_activated at tone 70; primary, palette_key_color and the neutral rows the base\'s)', s1 === 'same' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', o16())) !== 'same' && pinsOff(plan, pTS16[6]).length === 0, JSON.stringify([s1, pinsOff(plan, pTS16[6])]));
  check('   the record keeps accurate false, after the style and before the profile and the build; the toast and the note name the theme overlay', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accurate":false,"profile":"P16","build":"' + B16 + '"}' && (await toasts()).includes('Theme overlay written') && (await ev(() => document.getElementById('ovlRootNote').innerText)) === 'The theme overlay is written on this phone.', JSON.stringify([await rec(), await toasts()]));
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  const pTS14 = pinOf('P14', 'TONAL_SPOT', '2021', null, null);
  s1 = await sameAs(plan, 'TONAL_SPOT', '6750A4', o14('2021', OFFO));
  check('D2. P14, the same: 165 entries, MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P14, contrast 0, accurate false }); ' + Object.keys(pTS14[6]).length + ' values pinned (Android 14\'s shade rule)', k.fab.length === 1 && plan.entries && plan.entries.length === 165 && s1 === 'same' && pinsOff(plan, pTS14[6]).length === 0, JSON.stringify([s1, pinsOff(plan, pTS14[6])]));
  check('   the record: Tonal Spot, accurate false, the build, no profile (P14)', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accurate":false,"build":"' + B14 + '"}', await rec());
  // the value of the tap: flipped during Applying… (between the tap and the theme answer), both ways
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_root_applied', null); window.__delay = 400; }); await reset();
  await tap('#ovlApplyBtn'); const res1 = await kv('ovl_resume');
  const busy = await ev(() => document.getElementById('ovlApplyBtn').innerText);
  if (await page.$('#ovlAccurateSw')) await tap('#ovlAccurateSw');
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('D3. off when Apply is tapped (the resume record\'s choices say accurate false), flipped on during "' + busy + '": the plan is the one with the switch off (the pins of D1), and so is the record; the switch is on for the next Apply', busy === 'Applying…' && !!res1 && !!res1.at && res1.at.accurate === false && pinsOff(plan, pTS16[6]).length === 0 && (await rec()).includes('"accurate":false') && (await kv(KV)) === '1', JSON.stringify([busy, res1 && res1.at, pinsOff(plan, pTS16[6]), await rec(), await kv(KV)]));
  await ev(() => { window.__delay = 20; }); await reset(); await apply(); k = await calls();
  check('   the next Apply takes the switch as it is now (on): Tonal Spot at 2021 is Android\'s own again, so D3\'s overlay is switched off (a disable, no fabricate) and the record goes', k.apply.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && (await rec()) === 'null' && (await kv(KV)) === '1', JSON.stringify([k.fab.map(f => f.action), await rec()]));
  await ev(() => { kvSet('ovl_root_applied', null); window.__delay = 400; }); await reset();
  await tap('#ovlApplyBtn'); const res2 = await kv('ovl_resume');
  if (await page.$('#ovlAccurateSw')) await tap('#ovlAccurateSw');
  await settle(); await sleep(500); k = await calls();
  check('   and the other way round: on when tapped (the resume record plans no overlay: Tonal Spot at 2021 is Android\'s own), flipped off before the answer: no overlay call, no record', !!res2 && res2.root === null && res2.at === null && k.apply.length === 1 && k.fab.length === 0 && (await rec()) === 'null' && (await kv(KV)) === '0', JSON.stringify([res2 && res2.at, k.fab, await rec()]));
  await ev(() => { window.__delay = 20; });
  // off with the sliders, with accents, with a More style, at 2025, under Monochrome
  const OFFS = [
    ['D4. off with Accent saturation 150 (P16)', { sdk: 36, profile: 'P16', build: B16 }, ['', '2021', false], null, A150, 'TONAL_SPOT', '2021', HINT.adj, '', '"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"accurate":false,"profile":"P16"'],
    ['D5. off with Secondary E91E63 (P16)', { sdk: 36, profile: 'P16', build: B16 }, ['', '2021', false], { secondary: 'E91E63' }, null, 'TONAL_SPOT', '2021', HINT.list, SAID.sec, '"style":"TONAL_SPOT","accents":{"secondary":"E91E63"},"accurate":false,"profile":"P16"'],
    ['    off with both accents and Accent saturation 150 (P14)', { sdk: 34, build: B14 }, ['', '2021', false], BOTH, A150, 'TONAL_SPOT', '2021', HINT.adj, SAID.both, '"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"accents":{"secondary":"E91E63","tertiary":"00897B"},"accurate":false,"build"'],
    ['D6. off with Fidelity in More styles (P16)', { sdk: 36, profile: 'P16', build: B16 }, ['FIDELITY', '2021', false], null, null, 'FIDELITY', '2021', HINT.more('Fidelity'), '', '"style":"FIDELITY","accurate":false,"profile":"P16"'],
    ['    off with CMF at 2026, both accents (E91E63, FFD600) and Accent saturation 150 (P16)', { sdk: 36, profile: 'P16', build: B16 }, ['CMF', '2026', false], { secondary: 'E91E63', tertiary: 'FFD600' }, A150, 'CMF', '2026', HINT.moreAdj('CMF'), SAID.both, '"style":"CMF","spec":"2026","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"accents":{"secondary":"E91E63","tertiary":"FFD600"},"accurate":false,"profile":"P16"'],
    ['    off with Monochrome in More styles (P16): the swap applies to its grey accent rows too', { sdk: 36, profile: 'P16', build: B16 }, ['MONOCHROMATIC', '2021', false], null, null, 'MONOCHROMATIC', '2021', HINT.more('Monochrome'), '', '"style":"MONOCHROMATIC","accurate":false,"profile":"P16"'],
    ['D7. off with Tonal Spot at 2025 (P15Q2)', { sdk: 36, profile: 'P15Q2', build: B16 }, ['', '2025', false], null, null, 'TONAL_SPOT', '2025', HINT.list25, '', '"style":"TONAL_SPOT","spec":"2025","accurate":false,"profile":"P15Q2"']
  ];
  for (const [label, ph, [more, spec, black], accents, adjust, style, at, hint, said, recPart] of OFFS) {
    await phone(ph); await setCard(more, spec, black); await setAcc(accents && accents.secondary || null, accents && accents.tertiary || null);
    await setAdj(adjust ? adjust.accentSat : null, null, null); await setAccu('0');
    a = await row(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
    const profile = ph.profile || 'P14', pin = pinOf(profile, style, at, accents, adjust);
    const opts = Object.assign({ spec: at, dark: true, profile, contrast: 0 }, adjust ? { adjust } : {}, accents ? { accents } : {}, OFFO);
    const same = await sameAs(plan, style, '6750A4', opts), r = await rec();
    check(label + ': the hint "' + hint + '"' + (said ? ', then the accents\' sentence' : '') + ', then "' + OFF + '"; the plan is MonetPlan.entries with accurate false (' + Object.keys(pin[6]).length + ' values pinned); the record: ' + recPart.replace(/"/g, ''), a.hint === hint && a.said === said && a.off === OFF && same === 'same' && pinsOff(plan, pin[6]).length === 0 && r.includes(recPart), JSON.stringify([a.hint, a.said, a.off, same, pinsOff(plan, pin[6]), r]));
  }
  await setAdj(null, null, null); await setAcc(null, null);
  // Pure black wins where both name a resource
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await setCard('', '2021', true); await setAccu('0'); a = await row(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  check('D8. Pure black on with the switch off (Tonal Spot at 2021, P16): the hint names this style\'s colors and pure black, then "' + OFF + '"; 177 entries, no resource twice; neutral1_900, neutral2_900, background_dark and surface_dark black, the dark containers grey', a.hint === HINT.listBlack && a.off === OFF && plan.entries && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177 && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818', JSON.stringify([a.hint, a.off, r.surface_dark, r.surface_container_dark]));
  check('   the rest is Tonal Spot with the switch off (the pins of D1); the record: black, Tonal Spot, accurate false', pinsOff(plan, pTS16[6]).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"TONAL_SPOT","accurate":false,"profile":"P16","build":"' + B16 + '"}', JSON.stringify([pinsOff(plan, pTS16[6]), await rec()]));
  // with the switch on: what the base writes
  await setCard('FIDELITY', '2021', false); await setAccu('1'); a = await row(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; r = byRes(plan);
  const pFid = pinOf('P16', 'FIDELITY', '2021', null, null);
  check('D9. the switch on, Fidelity in More styles (P16): Fidelity\'s hint alone; MonetPlan.entries without accurate (the base\'s accentN_100: ' + ['accent1_100', 'accent2_100', 'accent3_100'].map(n => pFid[7][n]).join(' ') + '); the record has no accurate (as before the switch)', a.hint === HINT.more('Fidelity') && a.off === '' && (await sameAs(plan, 'FIDELITY', '6750A4', o16())) === 'same' && Object.keys(pFid[7]).every(n => r[n] === pFid[7][n]) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', JSON.stringify([a.hint, a.off, await rec()]));
  await setCard('', '2021', false); a = await row(); await reset(); await apply(); k = await calls();
  check('   on with Tonal Spot at 2021 and nothing else: no hint sentence of its own, the overlay from before is switched off (a disable), no record', a.off === '' && k.fab.length === 1 && k.fab[0].action === 'disable' && (await rec()) === 'null', JSON.stringify([a.hint, k.fab.map(f => f.action)]));
  await ev(() => kvSet('ovl_root_applied', null));

  // ---- (e) Undo (T-069): the record's own choices, accurate included, never the switch as it is now ----
  await setCard('', '2021', false); await setAccu('0'); await ev(() => { ovlPickColor('6750A4'); ovlRender(); }); await reset(); await apply();
  const offPlan = ((await calls()).fab[0] || {}).entries || [];
  check('E1. Tonal Spot at 2021 written with the switch off: its record keeps accurate false and what a rebuild needs', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accurate":false,"profile":"P16","build":"' + B16 + '"}' && ((await kv('ovl_root_applied')) || {}).seed === '6750A4', await rec());
  await setAccu('1'); await setCard('FIDELITY', '2021', false); await reset(); await apply();
  check('   then the switch on, Fidelity: Apply writes Fidelity; the record has no accurate', ((await calls()).fab[0] || {}).action === 'fabricate' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', await rec());
  await reset(); await undo(); k = await calls();
  check('   its Undo puts the theme back, then writes the Tonal Spot overlay from before again with the switch off, entry for entry (the switch, on now, does not count); the record has accurate false again', k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'fabricate' && offPlan.length === 177 && JSON.stringify(k.fab[0].entries) === JSON.stringify(offPlan) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accurate":false,"profile":"P16","build":"' + B16 + '"}' && (await kv(KV)) === '1', JSON.stringify([k.fab.map(f => f.action), await rec()]));
  await reset(); await apply();
  const onPlan = ((await calls()).fab[0] || {}).entries || [];
  await setAccu('0'); await setCard('', '2021', false); await ev(() => { ovlPickColor('E91E63'); ovlRender(); }); await reset(); await apply();
  check('E2. the other way round: Fidelity written with the switch on, then the switch off and Tonal Spot of another colour (written with the switch off)', ((await calls()).fab[0] || {}).action === 'fabricate' && (await rec()).includes('"accurate":false'), await rec());
  await reset(); await undo(); k = await calls();
  check('   its Undo writes the Fidelity overlay from before as it was (the switch, off now, does not count); its record has no accurate', k.fab.length === 1 && k.fab[0].action === 'fabricate' && onPlan.length === 177 && JSON.stringify(k.fab[0].entries) === JSON.stringify(onPlan) && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', JSON.stringify([k.fab.map(f => f.action), await rec()]));
  await setAccu(null); await setCard('', '2021', false); await ev(() => { kvSet('ovl_root_applied', null); ovlPickColor('6750A4'); ovlRender(); });

  // ---- (f) the record ----
  const readsRec = await ev(id => {
    const out = [];
    for (const x of [{ style: 'FIDELITY' }, { style: 'TONAL_SPOT', accurate: false }, { style: 'TONAL_SPOT', accurate: true }, { style: 'TONAL_SPOT', accurate: 'false' }, { style: 'TONAL_SPOT', accurate: 0 }, { style: 'TONAL_SPOT', accurate: null }, { black: true, style: '', accurate: false }]) {
      kvSet('ovl_root_applied', Object.assign({ ids: [id], t: 1, black: false }, x)); out.push(String(ovlRootApplied().accurate));
    }
    kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: [id], t: 1 }); out.push(String(ovlRootApplied().accurate)); kvSet('ovl_black_applied', null);
    return out;
  }, AAM);
  check('F1. records: one from before the switch reads as on (and so do PR 2\'s pure black one and one without a style); accurate false is read back as off; true, \'false\', 0 and null read as on', JSON.stringify(readsRec) === JSON.stringify(['true', 'false', 'true', 'true', 'true', 'true', 'true', 'true']), JSON.stringify(readsRec));
  const reb = await ev(id => {
    const one = x => { const a = ovlRootRebuildAt(Object.assign({ ids: [id], t: 1, black: false, seed: '6750A4', dark: true, contrast: 0 }, x)); return a ? ('accurate' in a ? String(a.accurate) : 'absent') : 'null'; };
    return [one({ style: 'TONAL_SPOT', accurate: false }), one({ style: 'TONAL_SPOT' }), one({ style: 'FIDELITY', accurate: false }), one({ style: '', black: true })];
  }, AAM);
  check('   what an Undo rebuilds from a record (ovlRootRebuildAt): accurate false where the record says off, nothing (on) where it does not, and Pure black alone carries none', JSON.stringify(reb) === JSON.stringify(['false', 'absent', 'false', 'absent']), JSON.stringify(reb));

  // ---- the note on colours written on another version of Android (T-077), when the switch off is the only reason Apply writes a style ----
  {
    const STALE = 'Android was updated after these colors were written. Tap Apply theme to write them for this version.';
    const B15 = 'google/mock/mock:15/AP4A.250205.002/12821496:user/release-keys';
    const stale = () => ev(() => { const el = document.getElementById('ovlRootStale'); return el ? (el.style.display === 'none' ? '' : el.innerText) : 'missing'; });
    await phone({ sdk: 36, profile: 'P16', build: B16 }); await setCard('', '2021', false); await setAcc(null, null); await setAdj(null, null, null);
    await ev(([id, b15]) => { kvSet('ovl_root_applied', { ids: [id], t: 1, black: false, style: 'TONAL_SPOT', accurate: false, seed: '6750A4', dark: true, contrast: 0, profile: 'P15Q2', build: b15 }); }, [AAM, B15]);
    await setAccu('0'); await reset(); const n0 = await stale(); a = await row();
    check('F3. Tonal Spot written with the switch off on 15 QPR2 (its record), the phone now on 16.0 (P16), Tonal Spot at 2021, sliders at 100, no accent, the switch off: the note says Android was updated; nothing is written by itself', n0 === STALE && a.hint === HINT.list && a.off === OFF && (await calls()).fab.length === 0, JSON.stringify([n0, a.hint, a.off]));
    await setAccu('1'); const n1 = await stale(); a = await row();
    check('   the switch on (Apply would leave Tonal Spot to Android and switch the overlay off): no such note; the hint says Apply removes it', n1 === '' && a.hint === 'Also removes the theme overlay.' && a.off === '' && (await calls()).fab.length === 0, JSON.stringify([n1, a.hint]));
    await ev(() => { kvSet('ovl_root_applied', null); ovlRender(); }); await setAccu(null);
  }

  // ---- (g) a restart before the theme's answer (T-068): the resume record keeps the tap's value ----
  {
    const KEY = 'theme_customization_overlay_packages';
    await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_resume', null); }); await setAccu('0'); await setCard('', '2021', false);
    await ev(() => { window.__ovlNoAnswer = true; }); await reset();
    await tap('#ovlApplyBtn'); await sleep(150);
    const lost = await ev(() => {
      const a = window.__ovlCalls.apply[0] || {}, st = a.style || 'TONAL_SPOT';
      const value = JSON.stringify({ 'android.theme.customization.system_palette': a.hex, 'android.theme.customization.color_source': 'preset', 'android.theme.customization.theme_style': st, _applied_timestamp: Date.now() });
      return { kv: Object.assign({}, window.AndroidBridge.__kv), value, resume: kvGet('ovl_resume', null), fab: window.__ovlCalls.fab.length, source: a.source };
    });
    check('G1. Apply with the switch off (P16, Tonal Spot of 6750A4), the theme\'s answer lost: the resume record keeps what the tap planned (fabricate) and its choices with accurate false; nothing was written yet', lost.fab === 0 && lost.source === 'preset' && !!lost.resume && lost.resume.root === 'fabricate' && !!lost.resume.at && lost.resume.at.accurate === false, JSON.stringify(lost.resume && lost.resume.at));
    // the restarted page: the switch stored on now (it was flipped after the tap), the phone's setting as written
    const kvs = Object.assign({}, lost.kv, { [KV]: JSON.stringify('1') });
    const p = await b.newPage({ viewport: { width: 360, height: 800 } });
    p.on('pageerror', e => errors.push(e.message));
    await p.addInitScript(k => { window.__kvInit = k; }, kvs);
    await p.addInitScript(sdb.initScript); await p.addInitScript(ovl.initScript);
    await p.addInitScript(([key, v, b16]) => { window.__root = true; window.__sdk = 36; window.__profile = 'P16'; window.__build = b16; window.__db.secure[key] = v; }, [KEY, lost.value, B16]);
    await p.goto(URL); await p.waitForTimeout(1900);
    await p.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).catch(() => {}); await p.waitForTimeout(100);
    const rs = await p.evaluate(() => ({ fab: window.__ovlCalls.fab.map(f => ({ action: f.action, entries: f.entries })), applied: kvGet('ovl_root_applied', null), sw: kvGet('ovl_accurate_shades', null) }));
    const rplan = rs.fab[0] || {};
    const same = await p.evaluate(([entries, opts]) => { const want = MonetPlan.entries('TONAL_SPOT', '6750A4', opts); const have = Object.fromEntries((entries || []).map(e => [e.resource, e.argb])); return want.length === (entries || []).length && want.every(e => have[e.resource] === e.argb); }, [rplan.entries, o16('2021', OFFO)]).catch(() => false);
    check('   after the restart, the theme read back as written: that Apply\'s overlay is written with the switch off as tapped (the pins of D1), although the switch is stored on now; the record keeps accurate false', rs.fab.length === 1 && rplan.action === 'fabricate' && same && pinsOff(rplan, pTS16[6]).length === 0 && !!rs.applied && rs.applied.accurate === false && rs.sw === '1', JSON.stringify([rs.fab.map(f => f.action), pinsOff(rplan, pTS16[6]), rs.applied, rs.sw]));
    await p.close();
    await ev(() => { window.__ovlNoAnswer = false; kvSet('ovl_resume', null); kvSet('ovl_root_applied', null); }); await setAccu(null);
  }

  // ---- (h) the maths through the page's MonetPlan ----
  const STY = [['TONAL_SPOT', ['2021', '2025', '2026']], ['VIBRANT', ['2021', '2025']], ['EXPRESSIVE', ['2021', '2026']], ['RAINBOW', ['2021']], ['FRUIT_SALAD', ['2021', '2025']], ['SPRITZ', ['2021', '2025']], ['FIDELITY', ['2021']], ['CONTENT', ['2021']], ['MONOCHROMATIC', ['2021']], ['CMF', ['2026']]];
  const id0 = await evm(STY => {
    let n = 0; const off = [];
    for (const profile of ['P14', 'P16']) for (const [style, specs] of STY) for (const spec of specs) for (const dark of [false, true]) {
      const o = { spec, dark, profile, contrast: 0 }, e = JSON.stringify(MonetPlan.entries(style, '6750A4', o)), p = JSON.stringify(MonetPlan.palettes(style, '6750A4', o)), rl = JSON.stringify(MonetPlan.roles(style, '6750A4', dark, o));
      for (const accurate of [undefined, null, true]) {
        n++;
        const oa = Object.assign({ accurate }, o);
        if (JSON.stringify(MonetPlan.entries(style, '6750A4', oa)) !== e || JSON.stringify(MonetPlan.palettes(style, '6750A4', oa)) !== p || JSON.stringify(MonetPlan.roles(style, '6750A4', dark, oa)) !== rl) off.push([profile, style, spec, dark, String(accurate)].join(' '));
      }
    }
    return { n, off };
  }, STY, { n: 0, off: ['threw'] });
  check('I1. identity: with accurate absent, null or true, entries, palettes and roles are byte for byte the ones without, for every style and spec on P14 and P16, light and dark (' + id0.n + ' cases)', id0.n === 192 && id0.off.length === 0, JSON.stringify(id0.off.slice(0, 5)));
  // the rule, written here on the page's own scheme with the switch on (I1: the base's): a role or fixed colour whose palette is the
  // scheme's primary, secondary or tertiary palette and whose computed tone is exactly 90 takes Hct(that palette's hue and chroma, 70)
  // (part 1's roles on a part-1 scheme; on a part-2 scheme upstream's roles, and part 1's for the eight Android-only roles, as monet.js
  // takes them); system_accentN_100 takes system_accentN_300; every other entry and role stays
  const iso = await evm(STY => {
    const L = MonetPlan.lib, X = L.mcu, out = [], names = {};
    const hex8 = a => '0x' + (a >>> 0).toString(16).toUpperCase().padStart(8, '0'), cut = r => r.replace('android:color/system_', '');
    const LISTS = { P14: [L.ALL_DYNAMIC_COLORS_MAPPED, L.FIXED_COLORS_MAPPED], P16: [L.ROLES_A15Q2, L.FIXED_A15Q2] };
    let n = 0, moved = 0;
    const expect = (s, role, argb) => {
      const part2 = s instanceof X.DynamicScheme, androidOnly = part2 && L.ANDROID_ONLY_ROLES.includes(role);
      const view = androidOnly ? { isDark: s.isDark, contrastLevel: s.contrastLevel, variant: s.variant, sourceColorHct: s.sourceColorHct, primaryPalette: s.primaryPalette, secondaryPalette: s.secondaryPalette, tertiaryPalette: s.tertiaryPalette, neutralPalette: s.neutralPalette, neutralVariantPalette: s.neutralVariantPalette, errorPalette: s.errorPalette } : s;
      const dc = part2 && !androidOnly ? X.colorSpec[role]() : L.MDC[role]();
      const pal = typeof dc.palette === 'function' ? dc.palette(view) : null;
      if (!pal || ![s.primaryPalette, s.secondaryPalette, s.tertiaryPalette].includes(pal) || dc.getTone(view) !== 90) return argb;
      return hex8((part2 && !androidOnly ? X.Hct : L.Hct).from(pal.hue, pal.chroma, 70).toInt());
    };
    // every style and spec of 6750A4, and Fidelity and Content at 2021 of 00897B too (a seed where Fidelity's and Content's secondary
    // container tone on P14 is computed from its palette at tone 90: the rule reads the palette as it is with the switch on)
    const CASES = [];
    for (const profile of ['P14', 'P16']) { for (const [style, specs] of STY) for (const spec of specs) CASES.push([profile, style, spec, '6750A4']); for (const style of ['FIDELITY', 'CONTENT']) CASES.push([profile, style, '2021', '00897B']); }
    for (const [profile, style, spec, seed] of CASES) for (const dark of [false, true]) {
      const o = { spec, dark, profile, contrast: 0 }, oa = Object.assign({ accurate: false }, o);
      const light = MonetPlan.scheme(style, seed, false, o), darkS = MonetPlan.scheme(style, seed, true, o);
      const [roleList, fixedList] = LISTS[profile], role = {};
      for (const [nm, r] of roleList) { role[nm + '_light'] = [light, r]; role[nm + '_dark'] = [darkS, r]; }
      for (const [nm, r] of fixedList) role[nm] = [light, r];
      const base = MonetPlan.entries(style, seed, o), got = MonetPlan.entries(style, seed, oa), bv = Object.fromEntries(base.map(e => [cut(e.resource), e.argb]));
      const want = base.map(e => { const k = cut(e.resource), m = /^accent([123])_100$/.exec(k); return m ? bv['accent' + m[1] + '_300'] : role[k] ? expect(role[k][0], role[k][1], e.argb) : e.argb; });
      const bad = got.length !== base.length ? ['count ' + got.length] : got.filter((e, i) => e.resource !== base[i].resource || e.argb !== want[i]).map(e => cut(e.resource));
      base.forEach((e, i) => { const k = cut(e.resource); if (want[i] !== e.argb && !/^accent[123]_100$/.test(k)) { moved++; names[k.replace(/_(light|dark)$/, ' ($1)')] = 1; } });
      const okE = bad.length === 0 && [1, 2, 3].every(n => want[base.findIndex(e => cut(e.resource) === 'accent' + n + '_100')] !== bv['accent' + n + '_100']);
      const bp = MonetPlan.palettes(style, seed, o), gp = MonetPlan.palettes(style, seed, oa);
      const okP = ['accent1', 'accent2', 'accent3'].every(r => gp[r].hue === bp[r].hue && gp[r].chroma === bp[r].chroma && gp[r].key === bp[r].key && gp[r].shades.every((h, i) => i === 3 ? h === bp[r].shades[5] : h === bp[r].shades[i])) && ['neutral1', 'neutral2'].every(r => JSON.stringify(gp[r]) === JSON.stringify(bp[r])) && gp.seed === bp.seed;
      const s = MonetPlan.scheme(style, seed, dark, o), rb = MonetPlan.roles(style, seed, dark, o), rg = MonetPlan.roles(style, seed, dark, oa);
      const okR = rg.length === rb.length && rg.every((e, i) => e.name === rb[i].name && e.argb === expect(s, roleList[i][1], rb[i].argb));
      n++;
      if (!okE || !okP || !okR) out.push([profile, style, seed, spec, dark, 'entries', okE, bad.slice(0, 5).join(','), 'palettes', okP, 'roles', okR].join(' '));
    }
    return { n, out, moved, names: Object.keys(names).sort() };
  }, STY, { n: 0, out: ['threw'], moved: 0, names: [] });
  // the role and fixed entries the rule moves on this grid, at 2021 at least (light containers, dark on-containers, the three fixed colours,
  // control_activated in light mode), and never one from the neutral, neutral variant or error palettes
  const MUST = ['primary_container (light)', 'secondary_container (light)', 'tertiary_container (light)', 'on_primary_container (dark)', 'on_secondary_container (dark)', 'on_tertiary_container (dark)', 'primary_fixed', 'secondary_fixed', 'tertiary_fixed', 'control_activated (light)'];
  check('I2. off: entries are exactly the rule\'s (system_accent1_100, accent2_100 and accent3_100 each the row\'s _300; each role and fixed colour from an accent palette at tone 90 that palette\'s tone 70; every other entry, the neutral rows included, unchanged); palettes() swaps shade 100 (index 3) for shade 300 (index 5) in the three accent rows only, hue, chroma and key kept; roles() follow the same rule; every style and spec of 6750A4 and Fidelity and Content of 00897B on P14 and P16, light and dark (' + iso.n + ' cases, ' + iso.moved + ' role and fixed entries moved: ' + iso.names.join(', ') + ')', iso.n === 72 && iso.out.length === 0 && MUST.every(x => iso.names.includes(x)) && !iso.names.some(x => /neutral|surface|background|outline|error|shadow|scrim/.test(x)), JSON.stringify(iso.out.slice(0, 5)));
  const order = await evm(() => {
    const out = [];
    for (const profile of ['P14', 'P16']) for (const [accents, adjust] of [[{ secondary: 'E91E63', tertiary: '00C853' }, null], [null, { accentSat: 150, bgSat: 50, bgLight: 130 }], [{ tertiary: 'B5651D' }, { accentSat: 0, bgSat: 100, bgLight: 100 }]]) {
      const o = Object.assign({ spec: '2021', dark: true, profile, contrast: 0 }, accents ? { accents } : {}, adjust ? { adjust } : {});
      const on = Object.fromEntries(MonetPlan.entries('TONAL_SPOT', '6750A4', o).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
      const off = Object.fromEntries(MonetPlan.entries('TONAL_SPOT', '6750A4', Object.assign({ accurate: false }, o)).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
      const plain = Object.fromEntries(MonetPlan.entries('TONAL_SPOT', '6750A4', { spec: '2021', dark: true, profile, contrast: 0, accurate: false }).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
      const grey = h => h.slice(4, 6) === h.slice(6, 8) && h.slice(6, 8) === h.slice(8, 10);
      out.push([1, 2, 3].every(n => off['accent' + n + '_100'] === on['accent' + n + '_300']) && [1, 2, 3].some(n => off['accent' + n + '_100'] !== plain['accent' + n + '_100']) && (!adjust || adjust.accentSat !== 0 || [1, 2, 3].every(n => grey(off['accent' + n + '_100']))));
    }
    return out;
  }, undefined, [false]);
  check('I3. the swap comes after the accents and the sliders: with accents, with the sliders (150 / 50 / 130) and with both (Accent saturation 0), each accentN_100 is the accentN_300 of the same options (not of the plain style; greys at saturation 0), on P14 and P16', order.length === 6 && order.every(Boolean), JSON.stringify(order));
  const thr = await evm(() => { const t = (fn, v) => { try { const o = { profile: 'P16', accurate: v }; if (fn === 'roles' || fn === 'scheme') MonetPlan[fn]('TONAL_SPOT', '6750A4', false, o); else MonetPlan[fn]('TONAL_SPOT', '6750A4', o); return 'ok'; } catch (e) { return 'throws'; } }; const out = []; for (const fn of ['entries', 'palettes', 'roles', 'scheme']) for (const v of [0, 1, 'false', 'true', '', {}]) out.push(t(fn, v)); return out; }, undefined, ['threw']);
  check('I4. accurate as 0, 1, \'false\', \'true\', \'\' or an object throws in entries, palettes, roles and scheme (24 calls)', thr.length === 24 && thr.every(x => x === 'throws'), JSON.stringify(thr));
  for (const [profile, style, seed, spec, accents, adjust, pins, base] of PINS) {
    const off = await evm(([profile, style, seed, spec, accents, adjust, pins, base]) => {
      const o = Object.assign({ spec, dark: true, profile, contrast: 0 }, accents ? { accents } : {}, adjust ? { adjust } : {}), out = [];
      const v = Object.fromEntries(MonetPlan.entries(style, seed, Object.assign({ accurate: false }, o)).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
      const w = Object.fromEntries(MonetPlan.entries(style, seed, o).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
      Object.keys(pins).forEach(k => { if (v[k] !== pins[k]) out.push('off ' + k + ' ' + v[k] + ' != ' + pins[k]); });
      Object.keys(base).forEach(k => { if (w[k] !== base[k]) out.push('on ' + k + ' ' + w[k] + ' != ' + base[k]); });
      return out;
    }, [profile, style, seed, spec, accents, adjust, pins, base], ['threw']);
    check('I5. ' + profile + ' ' + style + ' ' + seed + ' at ' + spec + (accents ? ' ' + JSON.stringify(accents) : '') + (adjust ? ' ' + JSON.stringify(adjust) : '') + ': ' + Object.keys(pins).length + ' values pinned with the switch off, ' + Object.keys(base).length + ' with it on (the base\'s, where the switch changes them)', off.length === 0, off.join('; '));
  }

  // ---- (j) the new words in German (assets/lang/de.js) ----
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await setCard('', '2021', false); await setAccu('0');
  await ev(() => I18N.set('de')); await sleep(600); await ev(() => ovlRender()); await sleep(200); a = await row();
  const de = await ev(words => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return words.map(w => x[w] || ''); }, [TITLE, SUB, OFF, HINT.list]);
  check('J1. in German: the row, its line, the switch\'s name and both hint sentences read as assets/lang/de.js has them', de.every(t => t !== '') && de[0] !== TITLE && a.name === de[0] + ' ' + de[1] && a.sw && a.sw.label === de[0] && a.hint === de[3] && a.off === de[2], JSON.stringify([de, a.name, a.sw, a.hint, a.off]));
  check('   the German words address no one with du (no du, dich, dir, dein): the app speaks in the Sie form', de.slice(0, 3).every(t => t !== '' && !/\b(du|dich|dir|dein\w*)\b/i.test(t)), JSON.stringify(de.slice(0, 3)));
  await ev(() => I18N.set('en')); await sleep(200); await ev(() => ovlRender()); await sleep(100); a = await row();
  check('   back in English with a render: the row\'s words follow', a.name === TITLE + ' ' + SUB && a.sw && a.sw.label === TITLE && a.off === OFF, JSON.stringify([a.name, a.off]));

  // ---- (k) at 320 px, in English and German: the row fits (sizes printed only on a failure) ----
  await page.setViewportSize({ width: 320, height: 800 });
  for (const lang of ['en', 'de']) {
    await ev(l => I18N.set(l), lang); await sleep(400); await ev(() => ovlRender()); await sleep(150);
    const g = await ev(() => {
      const box = document.getElementById('ovlRootAccurate') || document.createElement('div'), sw = box.querySelector('#ovlAccurateSw');
      return { sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, live: !!sw,
        out: Array.from(box.querySelectorAll('*')).filter(e => { const q = e.getBoundingClientRect(); return q.width > 0 && (q.right > window.innerWidth + 1 || q.left < -1); }).map(e => e.id || e.className),
        overlap: (() => { if (!sw) return true; const n = box.querySelector('.rf-name').getBoundingClientRect(), s = sw.getBoundingClientRect(); return n.right > s.left + 1 && n.bottom > s.top && n.top < s.bottom; })() };
    });
    const fits = g.live && g.sw <= g.cw && g.out.length === 0 && !g.overlap;
    check('K1. ' + (lang === 'en' ? 'English' : 'German') + ' at 320 px: the row fits, nothing sticks out sideways, the name and line do not run under the switch, no page scroll sideways', fits, fits ? '' : JSON.stringify(g));
  }
  await ev(() => I18N.set('en')); await sleep(200); await page.setViewportSize({ width: 360, height: 800 }); await setAccu(null);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
