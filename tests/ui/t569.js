// Secondary and tertiary accents (ColorBlendr PR 7): the row under Intensity in the Overlays tab's Root-only theme controls card (two entries,
// Secondary accent and Tertiary accent, each a colour from the page's colour picker or "From the style"; kv ovl_accent_secondary and
// ovl_accent_tertiary, 'RRGGBB' in capitals) and what Apply writes with them (monet.js's opts.accents). (a) Locks, as Intensity and More
// styles have them: Update the app (no overlayFabricate, or a monet.js without the accents), the build family's lock (T-049), Needs Super
// User; Needs Android 14 on Android 12 and 13; stored colours count for nothing there and the handlers change nothing and call no
// AndroidBridge method. (b) Live on Android 14 (P14) and 16.0 (P16); what the store reads as set. (c) Picking a colour through the colour
// picker sheet: it starts at the colour the style gives the row (the style Apply would write without the accents and the sliders: a More style
// and the spec count, the sliders do not) or at the one set; its Apply stores the colour (one saveSetting) and changes the preview and the
// hint, and no other bridge method is called than the page's reads (no theme or overlay call); Cancel stores nothing. (d) Clear. (e) Apply: a
// list style at 2021 with the sliders at 100 and an accent set is written by the app (MonetPlan.entries with the same accents) on P16 and P14,
// the record keeps the accents; the values are the ones of the tap; both accents with the sliders and with a More style; Monochrome takes
// none; none set writes what PR 6 writes; Pure black wins. (f) The hint in every variant. (g) The record (old records read as none) and the
// note for colours written on another build. (h) The maths through the page's MonetPlan: identity with none set, the rows a colour changes and
// the ones it leaves alone, the hue kept in Expressive and Fruit Salad, Fidelity's key colour, Monochrome, accents before the sliders, what
// throws, and values pinned (a grey accent among them, under Fidelity, Tonal Spot and CMF) from oracles that do not use the new monet.js (kept
// outside the repository): for P16 and P15Q2 at 2021 and 2025 and CMF at 2026, Google's Material Color Utilities (TypeScript, main bd811e4)
// with the scheme built from X as the seed by its own Scheme class, its primary palette (or, where its hue is not X's, the palette at X's hue
// with that chroma) put in as the secondary or tertiary palette, then the sliders, a new DynamicScheme and its roles; for P14 the monet.js
// from before the accents (a231777c) run by hand the same way with Android 14's shade rule. (j) German. (k) The row at 320 px in English and
// German.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const TITLE = 'Secondary and tertiary accents';
const SUB = 'Your own colors for the second and third accent. The main color stays as it is.';
const NONE = 'From the style';
const NOT_YET = 'Not supported on this Android version yet';
const ONE_UI = 'Not supported on One UI';
const STALE = 'Android was updated after these colors were written. Tap Apply theme to write them for this version.';
const AAM = 'com.android.shell:aam_monet';
const RES = 'android:color/system_';
const IDS = ['ovlAccSecondary', 'ovlAccTertiary'];
const KV = ['ovl_accent_secondary', 'ovl_accent_tertiary'];
const B14 = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
const B16 = 'google/mock/mock:16/BP2A.250605.031/13578712:user/release-keys';
const B16b = 'google/mock/mock:16/BP2A.250805.005/13860026:user/release-keys';
const SAID = { sec: 'Secondary accent: your color.', ter: 'Tertiary accent: your color.', both: 'Secondary and tertiary accents: your colors.' };
const HINT = {
  list: 'Also writes this style\'s colors with root.',
  listBlack: 'Also writes this style\'s colors and pure black with root.',
  list25: 'Also writes this style\'s 2025 colors with root, in place of Android\'s.',
  list25Black: 'Also writes this style\'s 2025 colors and pure black with root, in place of Android\'s.',
  adj: 'Also writes this style\'s colors with root, adjusted by the Intensity sliders.',
  adjBlack: 'Also writes this style\'s colors, adjusted by the Intensity sliders, and pure black with root.',
  adj25: 'Also writes this style\'s 2025 colors with root, adjusted by the Intensity sliders.',
  adj25Black: 'Also writes this style\'s 2025 colors, adjusted by the Intensity sliders, and pure black with root.',
  more: n => 'Also writes the ' + n + ' colors with root, in place of the style above.',
  moreBlack: n => 'Also writes the ' + n + ' colors and pure black with root, in place of the style above.',
  moreAdj: n => 'Also writes the ' + n + ' colors with root, in place of the style above, adjusted by the Intensity sliders.',
  moreAdjBlack: n => 'Also writes the ' + n + ' colors, adjusted by the Intensity sliders, and pure black with root, in place of the style above.'
};
const SEC = { secondary: 'E91E63' }, BOTH = { secondary: 'E91E63', tertiary: '00897B' };
const A150 = { accentSat: 150, bgSat: 100, bgLight: 100 };
// Oracle pins: [profile, style, seed, spec, accents, adjust, pins (resource suffix: value)], contrast 0; each the same in light and dark mode
const PINS = [
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { secondary: 'E91E63' }, null, { accent2_10: '0xFFFFFBFF', accent2_50: '0xFFFFECEE', accent2_100: '0xFFFFD9DE', accent2_500: '0xFFAB616F', accent2_800: '0xFF561D2A', secondary_light: '0xFF8E4957', secondary_dark: '0xFFFFB2BE', on_secondary_container_dark: '0xFFFFD9DE', secondary_container_light: '0xFFFFD9DE', secondary_fixed: '0xFFFFD9DE', on_secondary_fixed_variant: '0xFF71333F', palette_key_color_secondary_light: '0xFFAB616E', accent1_500: '0xFF7E6EA9', primary_light: '0xFF65558F', neutral1_500: '0xFF79767D', surface_dark: '0xFF141218', accent3_500: '0xFF996A79', tertiary_light: '0xFF7E5260' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', { secondary: 'E91E63' }, null, { accent2_10: '0xFFFFFBFF', accent2_50: '0xFFFFECEE', accent2_100: '0xFFFFD9DE', accent2_500: '0xFFAA606E', accent2_800: '0xFF561D2A', secondary_light: '0xFF8E4957', secondary_dark: '0xFFFFB2BE', on_secondary_container_dark: '0xFFFFD9DE', secondary_container_light: '0xFFFFD9DE', secondary_fixed: '0xFFFFD9DE', on_secondary_fixed_variant: '0xFF71333F', palette_key_color_secondary_light: '0xFFAB616E', accent1_500: '0xFF7D6DA8', primary_light: '0xFF65558F', neutral1_500: '0xFF78757C', surface_dark: '0xFF141218', accent3_500: '0xFF986978', tertiary_light: '0xFF7E5260' }],
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { secondary: 'E91E63', tertiary: '00897B' }, { accentSat: 150, bgSat: 100, bgLight: 100 }, { accent2_500: '0xFFC0536A', secondary_light: '0xFFA13B53', secondary_dark: '0xFFFFB2BE', accent3_500: '0xFF008678', tertiary_light: '0xFF006B5F', tertiary_dark: '0xFF26DEC8', accent1_500: '0xFF8166CB', neutral1_500: '0xFF79767D' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', { secondary: 'E91E63', tertiary: '00897B' }, { accentSat: 150, bgSat: 100, bgLight: 100 }, { accent2_50: '0xFFFFECEE', accent2_500: '0xFFBF5269', secondary_light: '0xFFA13B53', secondary_dark: '0xFFFFB2BE', accent3_50: '0xFFB4FFF1', accent3_500: '0xFF008577', tertiary_light: '0xFF006B5F', accent1_500: '0xFF8065CA', neutral1_500: '0xFF78757C' }],
  ['P16', 'EXPRESSIVE', '6750A4', '2021', { tertiary: '00C853' }, null, { accent3_10: '0xFFF6FFF1', accent3_50: '0xFFC6FFC6', accent3_100: '0xFFB2F2B4', accent3_500: '0xFF498450', accent3_800: '0xFF003912', tertiary_light: '0xFF306A39', tertiary_dark: '0xFF97D599', on_tertiary_container_dark: '0xFFB2F2B4', tertiary_container_light: '0xFFB2F2B4', tertiary_fixed: '0xFFB2F2B4', on_tertiary_fixed_variant: '0xFF155123', palette_key_color_tertiary_light: '0xFF498450', accent2_500: '0xFF936B83', secondary_light: '0xFF79536A', accent1_500: '0xFF1A8672', primary_light: '0xFF006B5A', neutral1_500: '0xFF7C757E', surface_dark: '0xFF161219' }],
  ['P14', 'EXPRESSIVE', '6750A4', '2021', { tertiary: '00C853' }, null, { accent3_10: '0xFFF6FFF1', accent3_50: '0xFFC6FFC6', accent3_100: '0xFFB2F2B4', accent3_500: '0xFF48834F', accent3_800: '0xFF003912', tertiary_light: '0xFF306A39', tertiary_dark: '0xFF97D599', on_tertiary_container_dark: '0xFFB2F2B4', tertiary_container_light: '0xFFB2F2B4', tertiary_fixed: '0xFFB2F2B4', on_tertiary_fixed_variant: '0xFF155123', palette_key_color_tertiary_light: '0xFF498450', accent2_500: '0xFF926A82', secondary_light: '0xFF79536A', accent1_500: '0xFF188571', primary_light: '0xFF006B5A', neutral1_500: '0xFF7B747D', surface_dark: '0xFF161219' }],
  ['P16', 'FRUIT_SALAD', '6750A4', '2025', { secondary: '2962FF' }, null, { secondary_light: '0xFF4359A9', secondary_dark: '0xFFB6C4FF', on_secondary_container_dark: '0xFFDCE1FF', secondary_fixed: '0xFFDCE1FF', palette_key_color_secondary_light: '0xFF5C72C4', primary_light: '0xFF00639C' }],
  ['P15Q2', 'TONAL_SPOT', '6750A4', '2025', { secondary: 'E91E63', tertiary: 'FFD600' }, null, { secondary_light: '0xFF8A4D58', secondary_dark: '0xFFF6B6BF', secondary_fixed: '0xFFFFD9DE', tertiary_light: '0xFF6E5E1C', tertiary_dark: '0xFFFFF8EF', tertiary_fixed: '0xFFF6DF8F', palette_key_color_tertiary_dark: '0xFF857640', primary_dark: '0xFFCDC0EC' }],
  ['P16', 'FIDELITY', '6750A4', '2021', { secondary: 'E91E63', tertiary: '00897B' }, null, { accent2_500: '0xFFE61A61', secondary_light: '0xFFB80049', secondary_container_light: '0xFFE2165F', palette_key_color_secondary_light: '0xFFE61A61', accent3_500: '0xFF008678', tertiary_light: '0xFF005148', palette_key_color_tertiary_light: '0xFF00897B', accent1_500: '0xFF8069BF', primary_light: '0xFF4F378A' }],
  ['P16', 'CMF', '6750A4', '2026', { secondary: 'E91E63', tertiary: 'FFD600' }, { accentSat: 150, bgSat: 100, bgLight: 100 }, { secondary_light: '0xFFBE004C', secondary_dark: '0xFFFF6D8D', on_secondary_container_light: '0xFF30000D', tertiary_light: '0xFF625100', tertiary_dark: '0xFFBA9C00', tertiary_fixed_dim: '0xFF625100', primary_light: '0xFF5F2CCA' }],
  // a grey accent is taken as it is (seed()'s grey rule, Google blue, is for a wallpaper colour): under Fidelity it keeps its own low chroma
  ['P16', 'FIDELITY', '6750A4', '2021', { secondary: '808080' }, null, { accent2_500: '0xFF777777', accent2_100: '0xFFE3E2E2', secondary_light: '0xFF5E5E5E', secondary_dark: '0xFFC7C6C6', palette_key_color_secondary_light: '0xFF777777', accent1_500: '0xFF8069BF', primary_light: '0xFF4F378A' }],
  ['P14', 'FIDELITY', '6750A4', '2021', { secondary: '808080' }, null, { accent2_500: '0xFF767676', accent2_100: '0xFFE3E2E2', secondary_light: '0xFF5E5E5E', secondary_dark: '0xFFC7C6C6', palette_key_color_secondary_light: '0xFF777777', accent1_500: '0xFF7F68BE', primary_light: '0xFF513A8D' }],
  // and where seed() would turn it into Google blue (it exempts only Fidelity, Content and Monochrome): Tonal Spot gives the grey's own hue
  // (teal for 808080) at the style's chroma, CMF the grey's own chroma
  ['P16', 'TONAL_SPOT', '6750A4', '2021', { secondary: '808080' }, null, { accent2_10: '0xFFF6FEFF', accent2_100: '0xFF9EEFFD', accent2_500: '0xFF29828E', accent2_800: '0xFF00363D', secondary_light: '0xFF006874', secondary_dark: '0xFF82D3E0', secondary_container_light: '0xFF9EEFFD', palette_key_color_secondary_light: '0xFF28828E', accent1_500: '0xFF7E6EA9', primary_light: '0xFF65558F' }],
  ['P14', 'TONAL_SPOT', '6750A4', '2021', { secondary: '808080' }, null, { accent2_10: '0xFFF6FEFF', accent2_100: '0xFF9EEFFD', accent2_500: '0xFF27818D', accent2_800: '0xFF00363D', secondary_light: '0xFF006874', secondary_dark: '0xFF82D3E0', secondary_container_light: '0xFF9EEFFD', palette_key_color_secondary_light: '0xFF28828E', accent1_500: '0xFF7D6DA8', primary_light: '0xFF65558F' }],
  ['P16', 'CMF', '6750A4', '2026', { secondary: '808080' }, null, { accent2_10: '0xFFFDFCFB', accent2_100: '0xFFE3E2E2', accent2_500: '0xFF777777', accent2_800: '0xFF303031', secondary_light: '0xFF5F5F5F', secondary_dark: '0xFF9E9E9E', secondary_container_light: '0xFFE3E2E2', palette_key_color_secondary_light: '0xFF777777', accent1_500: '0xFF8069BF', primary_light: '0xFF5B4497' }]
];
const pinOf = (profile, style, spec, accents, adjust) => PINS.find(p => p[0] === profile && p[1] === style && p[3] === spec && JSON.stringify(p[4]) === JSON.stringify(accents) && JSON.stringify(p[5]) === JSON.stringify(adjust))[6];
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
  const kvs = () => ev(keys => keys.map(k => kvGet(k, null)), KV);
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  const phone = async o => {
    await ev(o => { window.__sdk = o.sdk; window.__profile = o.profile; window.__profileReason = o.reason; window.__build = o.build === undefined ? null : o.build; window.__contrast = 0; ovlReadPalette(); ovlRender(); }, o);
    await sleep(150);
  };
  // the two stored colours (null: never set), drawn again
  const setAcc = async (s, t) => { await ev(([v, keys]) => { keys.forEach((k, i) => { if (v[i] === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v[i]); }); ovlRender(); }, [[s, t], KV]); await sleep(50); };
  const setAdj = async (a, b, c) => { await ev(v => { ['ovl_adj_accent_sat', 'ovl_adj_bg_sat', 'ovl_adj_bg_light'].forEach((k, i) => { if (v[i] === null) delete window.AndroidBridge.__kv[k]; else kvSet(k, v[i]); }); ovlRender(); }, [a, b, c]); await sleep(50); };
  // the accents container: the locked row, or the row with its two entries; the hint under Apply (both sentences) and the preview
  const acc = () => ev(() => {
    const box = document.getElementById('ovlRootAccents') || document.createElement('div');      // none: a page without the row
    const row = box.querySelector('.rf-row'), lock = box.querySelector('.pm-lock'), lockBtn = box.querySelector('.op-mode-btn.locked'), group = box.querySelector('.ovl-acc'), pv = document.getElementById('ovlRootPreview');
    const named = el => (el.getAttribute('aria-labelledby') || '').split(' ').filter(Boolean).map(id => { const x = document.getElementById(id); return x ? x.innerText.trim() : '?'; }).join(' ');
    const entries = Array.from(box.querySelectorAll('.ovl-acc-row')).map(r => {
      const btn = r.querySelector('button.picker-bubble-btn'), circle = r.querySelector('.bubble-circle'), clear = r.querySelector('button.batch-tool-link');
      return { name: (r.querySelector('.ovl-acc-name') || { innerText: '' }).innerText, id: btn ? btn.id : '', key: btn ? btn.dataset.acc : '', value: btn ? btn.innerText.trim() : '', popup: btn ? btn.getAttribute('aria-haspopup') : '', named: btn ? named(btn) : '',
        swatch: circle ? (circle.classList.contains('ovl-acc-unset') ? 'unset' : getComputedStyle(circle).backgroundColor) : '', clear: clear ? { id: clear.id, text: clear.innerText, named: named(clear) } : null };
    });
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(row && row.classList.contains('is-locked')), name: row ? row.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', lock: lock ? lock.innerText : '', lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null,
      group: group ? { role: group.getAttribute('role'), label: group.getAttribute('aria-label') } : null, entries,
      hint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, said: (document.getElementById('ovlApplyHintAccents') || { innerText: '' }).innerText, preview: !!pv && pv.innerHTML !== '',
      adj: (document.querySelector('#ovlRootAdjust .pm-lock') || { innerText: '' }).innerText, more: (document.querySelector('#ovlRootMore .pm-lock') || { innerText: '' }).innerText };
  });
  const lockedAs = (a, reason) => a && a.rows === 1 && a.locked && a.lock === reason && a.lockBtn && a.lockBtn.disabled && a.entries.length === 0 && a.group === null;
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  // a tap that cannot stop the script: when something covers the control (a failure sheet, in a run against a broken page) the line says so
  // as a FAIL, the open sheets are closed, and the run goes on to the checks after it
  const tap = sel => page.locator(sel).click({ timeout: 5000 }).then(() => true, async e => { console.log('FAIL could not tap ' + sel + ': ' + String(e.message).split('\n')[0]); await ev(() => document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show'))); return false; });
  const apply = async () => { await tap('#ovlApplyBtn'); await settle(); await sleep(100); };
  const waitFab = () => page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource.replace(RES, ''), e.argb]));
  const pinsOff = (plan, pins) => { const r = byRes(plan); return Object.keys(pins).filter(k => r[k] !== pins[k]).map(k => k + ' ' + r[k] + ' != ' + pins[k]); };
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => { let want; try { want = MonetPlan.entries(style, seed, opts || undefined); } catch (e) { return 'throws: ' + e.message; } if (!entries || want.length !== entries.length) return 'count ' + (entries || []).length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan && plan.entries, style, seed, opts || null]);
  // T-069: a record with a style also keeps the source colour, the mode and the contrast (seed, dark, contrast) for the Undo; t564 checks them,
  // and the record strings compared here leave them out
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '') : 'null'; };
  const o16 = (spec, more) => Object.assign({ spec: spec || '2021', dark: true, profile: 'P16', contrast: 0 }, more || {});
  const o14 = (spec, more) => Object.assign({ spec: spec || '2021', dark: true, profile: 'P14', contrast: 0 }, more || {});
  // every AndroidBridge method, counted (name and arguments)
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });
  await ev(() => { window.__bc = []; const b = window.AndroidBridge; for (const k of Object.keys(b)) if (typeof b[k] === 'function') { const f = b[k]; b[k] = function () { window.__bc.push([k].concat(Array.from(arguments, a => String(a)))); return f.apply(this, arguments); }; } });
  // the page's reads (the settings, the dark mode, the phone's palette and contrast) and its tap vibration: none of them writes anything
  const READS = ['loadSetting', 'isSystemDarkMode', 'getSystemPalette', 'vibrate'];
  const sheet = () => ev(() => ({ open: document.getElementById('colorPickModal').classList.contains('show'), title: document.getElementById('cpTitle').innerText, sub: document.getElementById('cpSub').innerText, hex: document.getElementById('cpHex1').value }));
  const closeSheet = () => ev(() => { if (document.getElementById('colorPickModal').classList.contains('show')) closeColorPicker(); });

  // ---- (a) locks ----
  await ev(() => switchView('overlays')); await sleep(300);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_black', false); kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_root_applied', null); });
  await phone({ sdk: 34, build: B14 });
  let a = await acc();
  check('A1. without Super User (Android 14): Secondary and tertiary accents is a locked row of the Root Features kind (Locked, Needs Super User) with its line, no entries', lockedAs(a, 'Needs Super User') && a.name === TITLE + ' ' + SUB && a.lockBtn.text === 'Locked', JSON.stringify(a));
  check('   its container sits under Intensity and above the notes, inside the Root-only theme controls card', await ev(() => { const box = document.getElementById('ovlRootAccents'); return !!box && !!box.closest('#ovlRootCard') && box.previousElementSibling.id === 'ovlRootAdjust' && box.nextElementSibling.id === 'ovlRootAccurate' && box.nextElementSibling.nextElementSibling.id === 'ovlRootNote'; }));
  await setAcc('E91E63', '00897B'); a = await acc();
  check('   colours stored from a rooted session (E91E63, 00897B) count for nothing: still locked, no hint, no plan style, ovlRootAccOn() null', lockedAs(a, 'Needs Super User') && a.hint === '' && a.said === '' && (await ev(() => typeof ovlRootAccOn === 'function' && ovlRootAccOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify(a));
  const handlers = () => ev(() => {
    if (typeof ovlAccSet !== 'function') return 'no handlers';
    window.__bc.length = 0; ovlAccOpen('secondary'); const opened = document.getElementById('colorPickModal').classList.contains('show'); if (opened) closeColorPicker();
    ovlAccSet('secondary', '2962FF'); ovlAccSet('tertiary', 'FFD600'); ovlAccClear('secondary');
    // every AndroidBridge method the three handlers called (none while locked: not a write, a read, a vibration or a palette read), named
    // after the count when there is one
    const w = window.__bc.map(c => c[0]);
    return [kvGet('ovl_accent_secondary', null), kvGet('ovl_accent_tertiary', null), w.length, opened].concat(w.length ? [w] : []);
  });
  const h1 = JSON.stringify(await handlers());
  check('   the handlers called directly change nothing (the picker does not open, nothing stored, no AndroidBridge method called at all)', h1 === '["E91E63","00897B",0,false]', h1);
  await reset(); await apply(); let k = await calls();
  check('   Apply writes the theme setting only: no overlay call', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); a = await acc();
  check('   Android 13 without Super User: Needs Super User (the root lock comes first)', lockedAs(a, 'Needs Super User'), a.lock);
  await setRoot(true); a = await acc();
  check('A2. Super User on Android 13 (P12): a locked row saying Needs Android 14, as Intensity and More styles', lockedAs(a, 'Needs Android 14') && a.adj === 'Needs Android 14' && a.more === 'Needs Android 14', JSON.stringify([a.lock, a.adj, a.more]));
  check('   the stored colours count for nothing there (no hint, no plan style); the handlers change nothing and call no AndroidBridge method', a.hint === '' && a.said === '' && (await ev(() => typeof ovlRootAccOn === 'function' && ovlRootAccOn() === null)) && JSON.stringify(await handlers()) === '["E91E63","00897B",0,false]', a.hint);
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply(); k = await calls();
  const r33 = await kv('ovl_root_applied');
  check('   with Pure black on there: its own plan only (ovlBuildBlackPlan(33)), and the record carries no accents', k.fab.length === 1 && JSON.stringify(k.fab[0].entries) === JSON.stringify(await ev(() => ovlBuildBlackPlan(33))) && r33 && r33.style === '' && !('accents' in r33), JSON.stringify(Object.assign({}, r33, { t: 'T' })));
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_applied', null); ovlRender(); });
  const LOCKS = [
    ['Android 16 QPR1 (P16Q1)', { sdk: 36, profile: 'P16Q1', build: B16 }, NOT_YET],
    ['a One UI phone on Android 14', { sdk: 34, profile: 'unknown', reason: 'oneUi', build: B14 }, ONE_UI],
    ['an older APK on Android 16 (no profile)', { sdk: 36, profile: null, build: null }, 'Update the app']
  ];
  for (const [label, o, reason] of LOCKS) {
    await phone(o); a = await acc();
    check('A3. Super User, ' + label + ': locked with "' + reason + '", as Intensity and More styles', lockedAs(a, reason) && a.adj === reason && a.more === reason && a.hint === '' && a.said === '', JSON.stringify([a.lock, a.adj, a.more, a.hint]));
    await reset(); await apply(); k = await calls();
    check('   the handlers change nothing and call no AndroidBridge method; Apply writes the theme setting only', JSON.stringify(await handlers()) === '["E91E63","00897B",0,false]' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  }
  await ev(() => { window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; });
  await phone({ sdk: 34, build: B14 }); a = await acc();
  check('A4. an APK without overlayFabricate, Super User on Android 14: Update the app, as the other rows', lockedAs(a, 'Update the app') && a.adj === 'Update the app' && a.more === 'Update the app', JSON.stringify([a.lock, a.adj, a.more]));
  await phone({ sdk: 36, profile: 'P16Q1', build: B16 }); a = await acc();
  check('   and on Android 16 QPR1 (a locked build family): Update the app comes first', lockedAs(a, 'Update the app'), a.lock);
  await setRoot(false); a = await acc();
  check('   and without Super User: Update the app', lockedAs(a, 'Update the app'), a.lock);
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; }); await setRoot(true);
  // a colour maths that does not know the accents (the monet.js from before them: MonetPlan.lib without accentsOf)
  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = Object.assign({}, window.__mp, { lib: Object.assign({}, window.__mp.lib, { accentsOf: undefined }) }); });
  await phone({ sdk: 34, build: B14 }); a = await acc();
  check('A5. Android 14 with Super User and a monet.js without the accents: Update the app; Intensity and More styles stay live', lockedAs(a, 'Update the app') && a.adj === '' && a.more === '' && (await ev(() => !!document.getElementById('ovlRootStyles') && !!document.getElementById('ovlAdjAccentSat'))), JSON.stringify([a.lock, a.adj, a.more]));
  check('   the stored colours count for nothing (no hint, Tonal Spot at 2021 gives no plan style); the handlers change nothing and call no AndroidBridge method', a.hint === '' && a.said === '' && (await ev(() => typeof ovlRootAccOn === 'function' && ovlRootAccOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')) && JSON.stringify(await handlers()) === '["E91E63","00897B",0,false]', a.hint);
  await reset(); await apply(); k = await calls();
  check('   Apply writes the theme setting only', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  await phone({ sdk: 33, build: B14 }); a = await acc();
  check('   and on Android 13: Update the app comes before Needs Android 14', lockedAs(a, 'Update the app') && a.more === 'Needs Android 14', JSON.stringify([a.lock, a.more]));
  await ev(() => { window.MonetPlan = window.__mp; });

  // ---- (b) live on Android 14 and 16 ----
  await setAcc(null, null); await phone({ sdk: 34, build: B14 }); a = await acc();
  const fresh = e => e.length === 2 && e.map(x => [x.name, x.id, x.key, x.value, x.popup, x.named, x.swatch, x.clear === null].join('|')).join(' / ') === 'Secondary accent|ovlAccSecondary|secondary|' + NONE + '|dialog|Secondary accent ' + NONE + '|unset|true / Tertiary accent|ovlAccTertiary|tertiary|' + NONE + '|dialog|Tertiary accent ' + NONE + '|unset|true';
  check('B1. Super User on Android 14 (P14): the row with its line, a group "' + TITLE + '" of two entries, Secondary accent and Tertiary accent, each "' + NONE + '" with an empty dashed circle and no Clear, opening a dialog, named by the entry and its value', !a.locked && a.rows === 1 && a.name === TITLE + ' ' + SUB && a.group && a.group.role === 'group' && a.group.label === TITLE && fresh(a.entries), JSON.stringify(a));
  check('   Tonal Spot in the list at 2021, none set: no hint, no preview, no plan style (Android writes its own colours)', a.hint === '' && a.said === '' && !a.preview && (await ev(() => typeof ovlRootAccOn === 'function' && ovlRootAccOn() === null && ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify([a.hint, a.preview]));
  await setAcc('E91E63', '00897B'); a = await acc();
  check('   stored E91E63 and 00897B: each entry shows its swatch and hex and has a Clear named by the entry; ovlRootAccOn() gives them', a.entries.map(x => [x.value, x.swatch, x.named, x.clear && x.clear.text, x.clear && x.clear.named].join('|')).join(' / ') === '#E91E63|rgb(233, 30, 99)|Secondary accent #E91E63|Clear|Clear Secondary accent / #00897B|rgb(0, 137, 123)|Tertiary accent #00897B|Clear|Clear Tertiary accent' && JSON.stringify(await ev(() => typeof ovlRootAccOn === 'function' ? ovlRootAccOn() : 'missing')) === '{"secondary":"E91E63","tertiary":"00897B"}', JSON.stringify(a.entries));
  const reads = await ev(() => ['e91e63', '#E91E63', 'E91E6', 'E91E63 ', 123, true, ['E91E63'], { v: 'E91E63' }, '', 'GGGGGG'].map(v => { kvSet('ovl_accent_secondary', v); return typeof ovlAccStored === 'function' ? ovlAccStored(OVL_ACC[0]) : 'missing'; }));
  check('   what the store reads as not set: lower case, a #, five digits, a space, a number, true, an array, an object, \'\' and not hex', reads.every(x => x === ''), JSON.stringify(reads));
  await setAcc(null, null); await phone({ sdk: 36, profile: 'P16', build: B16 }); a = await acc();
  check('B2. Super User on Android 16.0 (P16): the same live row, both entries "' + NONE + '"', !a.locked && a.name === TITLE + ' ' + SUB && fresh(a.entries) && a.hint === '' && !a.preview, JSON.stringify(a));

  // ---- (c) picking a colour through the colour picker (P16, Tonal Spot at 2021, sliders at 100) ----
  const palOf = (style, accents, adjust, profile) => ev(([style, accents, adjust, profile]) => { const o = Object.assign({ spec: '2021', dark: systemPrefersDark(), profile: profile || 'P16', contrast: 0 }, accents ? { accents } : {}, adjust ? { adjust } : {}); const p = MonetPlan.palettes(style, '6750A4', o); const r = {}; for (const k of ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2']) r[k] = p[k].shades.map(h => '#' + h); r.key2 = p.accent2.key; r.key3 = p.accent3.key; return r; }, [style, accents, adjust, profile]);
  const pvRows = () => ev(() => { const rows = Array.from(document.querySelectorAll('#ovlRootPreview .ovl-pal')); const hx = r => r ? Array.from(r.querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : []; return { rows: rows.length, accent1: hx(rows[0]), accent2: hx(rows[1]), accent3: hx(rows[2]), neutral1: hx(rows[3]), neutral2: hx(rows[4]) }; });
  const plain = await palOf('TONAL_SPOT', null, null);
  await ev(() => { window.__bc.length = 0; });
  if (await page.$('#' + IDS[0])) await tap('#' + IDS[0]); await sleep(150);
  let sh = await sheet(), bc = await ev(() => window.__bc.map(c => c.slice()));
  check('C1. tapping Secondary accent opens the colour picker sheet titled "Secondary accent", at the colour Tonal Spot gives the row now (accent2\'s key colour #' + plain.key2 + ') and saying so', sh.open && sh.title === 'Secondary accent' && sh.hex === plain.key2 && sh.sub === 'The style gives #' + plain.key2 + ' now • Apply theme writes your color to the phone', JSON.stringify(sh));
  check('   nothing stored, no bridge method called but the page\'s reads and its tap vibration (' + READS.join(', ') + ')', JSON.stringify(await kvs()) === '[null,null]' && bc.every(c => READS.includes(c[0])), JSON.stringify(bc.filter(c => !READS.includes(c[0]))));
  if ((await sheet()).open) await tap('#colorPickModal .mode-action-btn:not(.primary)'); await sleep(100); a = await acc();
  check('   Cancel: the sheet closes, nothing stored, the entry still "' + NONE + '", no hint', !(await sheet()).open && JSON.stringify(await kvs()) === '[null,null]' && (a.entries[0] || {}).value === NONE && a.hint === '' && !a.preview, JSON.stringify([await kvs(), (a.entries[0] || {}).value]));
  // the start colour is the one of the style Apply would write without the accents and the sliders: a More style or the spec changes it, the
  // sliders do not. [what is chosen, entry, More style, spec, Accent saturation, the style whose palettes() give it]
  const STARTS = [
    ['Fidelity in More styles (2021)', 0, 'FIDELITY', '2021', 100, 'FIDELITY'],
    ['CMF in More styles at 2026', 1, 'CMF', '2026', 100, 'CMF'],
    ['Tonal Spot in the list at 2025 with Accent saturation 150', 1, '', '2025', 150, 'TONAL_SPOT']
  ];
  for (const [what, i, more, spec, sat, style] of STARTS) {
    await ev(([more, spec]) => { kvSet('ovl_spec', spec); kvSet('ovl_root_style', more); ovlRender(); }, [more, spec]); await setAdj(sat, 100, 100);
    const want = await evm(([style, spec, row, sat]) => { const o = { spec, dark: systemPrefersDark(), profile: 'P16', contrast: 0 }; return [MonetPlan.palettes(style, '6750A4', o)[row].key, MonetPlan.palettes(style, '6750A4', Object.assign({ adjust: { accentSat: sat } }, o))[row].key]; }, [style, spec, i ? 'accent3' : 'accent2', sat], ['?', '?']);
    const was = i ? plain.key3 : plain.key2;
    if (await page.$('#' + IDS[i])) await tap('#' + IDS[i]); await sleep(100); sh = await sheet(); await closeSheet(); await sleep(50);
    check('   ' + what + ': ' + (i ? 'Tertiary' : 'Secondary') + ' accent starts at the colour that style gives its row now (#' + want[0] + ', not Tonal Spot\'s 2021 #' + was + (sat !== 100 ? ' nor #' + want[1] + ' with the slider' : '') + ') and says so', sh.open && sh.hex === want[0] && want[0] !== was && (sat === 100 || want[1] !== want[0]) && sh.sub === 'The style gives #' + want[0] + ' now • Apply theme writes your color to the phone' && JSON.stringify(await kvs()) === '[null,null]', JSON.stringify(sh));
  }
  await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', ''); ovlRender(); }); await setAdj(null, null, null);
  // Secondary: the grid's #E91E63 swatch, then Apply
  const pick = async (id, how, hex) => {
    await ev(() => { window.__bc.length = 0; window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
    if (await page.$('#' + id)) await tap('#' + id); await sleep(100);
    if (!(await sheet()).open) return { bc: [], fab: -1, apply: -1 };
    if (how === 'grid') await tap('#cpGrid .cp-sw[aria-label="#' + hex + '"]'); else await page.fill('#cpHex1', hex);
    await sleep(50); await tap('#cpApply'); await sleep(150);
    return ev(() => ({ bc: window.__bc.map(c => c.slice()), fab: window.__ovlCalls.fab.length, apply: window.__ovlCalls.apply.length }));
  };
  let p = await pick(IDS[0], 'grid', 'E91E63'); a = await acc(); let pv = await pvRows(), want = await palOf('TONAL_SPOT', SEC, null);
  const other = c => !READS.includes(c[0]);
  check('C2. the #E91E63 swatch and Apply: stored as E91E63 (one saveSetting), and no bridge method but that, the page\'s reads and its tap vibration: no theme and no overlay call', JSON.stringify(p.bc.filter(other)) === '[["saveSetting","ovl_accent_secondary","\\"E91E63\\""]]' && p.fab === 0 && p.apply === 0 && JSON.stringify(await kvs()) === '["E91E63",null]' && !(await sheet()).open, JSON.stringify([p.bc.filter(other), p.fab, p.apply, await kvs()]));
  check('   the entry shows the swatch and #E91E63, and Clear', (a.entries[0] || {}).value === '#E91E63' && (a.entries[0] || {}).swatch === 'rgb(233, 30, 99)' && (a.entries[0] || {}).clear && (a.entries[0] || {}).clear.id === 'ovlAccSecondaryClear' && (a.entries[1] || {}).value === NONE && (a.entries[1] || {}).clear === null, JSON.stringify(a.entries));
  check('   the hint: the app writes this style\'s colors, then "' + SAID.sec + '"', a.hint === HINT.list && a.said === SAID.sec, JSON.stringify([a.hint, a.said]));
  check('   the preview appears; its accent2 row is MonetPlan.palettes with the secondary E91E63 (not the style\'s), the other four rows the style\'s', pv.rows === 5 && JSON.stringify(pv.accent2) === JSON.stringify(want.accent2) && JSON.stringify(pv.accent2) !== JSON.stringify(plain.accent2) && ['accent1', 'accent3', 'neutral1', 'neutral2'].every(r => JSON.stringify(pv[r]) === JSON.stringify(plain[r])), JSON.stringify(pv.accent2.slice(6, 8)));
  // Tertiary: typed in lower case
  p = await pick(IDS[1], 'type', '00897b'); a = await acc(); pv = await pvRows(); want = await palOf('TONAL_SPOT', BOTH, null);
  check('C3. Tertiary accent, 00897b typed and Apply: stored in capitals (00897B, one saveSetting), no theme and no overlay call; the hint now says both', JSON.stringify(p.bc.filter(other)) === '[["saveSetting","ovl_accent_tertiary","\\"00897B\\""]]' && p.fab === 0 && p.apply === 0 && JSON.stringify(await kvs()) === '["E91E63","00897B"]' && (a.entries[1] || {}).value === '#00897B' && a.hint === HINT.list && a.said === SAID.both, JSON.stringify([p.bc.filter(other), await kvs(), a.hint, a.said]));
  check('   the preview\'s accent3 row follows (palettes with both), accent2 as in C2, accent1 and the neutrals the style\'s', JSON.stringify(pv.accent3) === JSON.stringify(want.accent3) && JSON.stringify(pv.accent3) !== JSON.stringify(plain.accent3) && JSON.stringify(pv.accent2) === JSON.stringify(want.accent2) && ['accent1', 'neutral1', 'neutral2'].every(r => JSON.stringify(pv[r]) === JSON.stringify(plain[r])), JSON.stringify(pv.accent3.slice(6, 8)));
  if (await page.$('#' + IDS[0])) await tap('#' + IDS[0]); await sleep(100); sh = await sheet();
  check('C4. a set entry opens the picker at its colour: "Current color #E91E63 • Apply theme writes it to the phone"', sh.open && sh.title === 'Secondary accent' && sh.hex === 'E91E63' && sh.sub === 'Current color #E91E63 • Apply theme writes it to the phone', JSON.stringify(sh));
  await closeSheet(); await sleep(50);

  // ---- (d) Clear ----
  await ev(() => { window.__bc.length = 0; });
  if (await page.$('#ovlAccSecondaryClear')) { await page.focus('#ovlAccSecondaryClear'); await page.keyboard.press('Enter'); } await sleep(100); a = await acc(); pv = await pvRows(); want = await palOf('TONAL_SPOT', { tertiary: '00897B' }, null);
  const saves = await ev(reads => window.__bc.filter(c => !reads.includes(c[0])).map(c => c.join('=')), READS);
  check('G1. Clear on Secondary accent (Enter on it): stored as \'\' (one saveSetting, nothing else), the entry "' + NONE + '" without Clear, the focus on the entry; the hint says the tertiary only; the preview\'s accent2 back to the style\'s', JSON.stringify(saves) === JSON.stringify(['saveSetting=ovl_accent_secondary=""']) && JSON.stringify(await kvs()) === '["","00897B"]' && (a.entries[0] || {}).value === NONE && (a.entries[0] || {}).swatch === 'unset' && (a.entries[0] || {}).clear === null && (await ev(() => document.activeElement && document.activeElement.id)) === IDS[0] && a.said === SAID.ter && JSON.stringify(pv.accent2) === JSON.stringify(plain.accent2) && JSON.stringify(pv.accent3) === JSON.stringify(want.accent3), JSON.stringify([saves, await kvs(), a.said]));
  if (await page.$('#ovlAccTertiaryClear')) await tap('#ovlAccTertiaryClear'); await sleep(100); a = await acc();
  check('G2. Clear on Tertiary accent: both "' + NONE + '"; Tonal Spot at 2021 with nothing else on: no hint, no preview, no plan style', fresh(a.entries) && a.hint === '' && a.said === '' && !a.preview && JSON.stringify(await kvs()) === '["",""]' && (await ev(() => ovlRootPlanOn('TONAL_SPOT').style === '')), JSON.stringify([a.hint, a.said, await kvs()]));

  // ---- (e) Apply ----
  await setAcc('E91E63', null); await reset(); await apply(); k = await calls(); let plan = k.fab[0] || {};
  check('D1. P16, Tonal Spot in the list at 2021, the sliders at 100, Secondary E91E63: the setting gets TONAL_SPOT, then overlayFabricate with 177 entries, no resource twice', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && plan.action === 'fabricate' && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177, JSON.stringify([k.apply, (plan.entries || []).length]));
  const pin16 = pinOf('P16', 'TONAL_SPOT', '2021', SEC, null), ts16 = await sameAs(plan, 'TONAL_SPOT', '6750A4', o16('2021', { accents: SEC }));
  check('   it is MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P16, contrast 0, accents { secondary E91E63 } }), not the style\'s own; ' + Object.keys(pin16).length + ' values pinned from the oracle', ts16 === 'same' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', o16())) !== 'same' && pinsOff(plan, pin16).length === 0, JSON.stringify([ts16, pinsOff(plan, pin16)]));
  check('   the record keeps the accents: {"secondary":"E91E63"}, before the profile and the build; the toast and the note name the theme overlay', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accents":{"secondary":"E91E63"},"profile":"P16","build":"' + B16 + '"}' && (await toasts()).includes('Theme overlay written') && (await ev(() => document.getElementById('ovlRootNote').innerText)) === 'The theme overlay is written on this phone.', JSON.stringify([await rec(), await toasts()]));
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  const pin14 = pinOf('P14', 'TONAL_SPOT', '2021', SEC, null), ts14 = await sameAs(plan, 'TONAL_SPOT', '6750A4', o14('2021', { accents: SEC }));
  check('D2. P14, the same: 165 entries, MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2021, dark, profile P14, contrast 0, accents { secondary E91E63 } }); ' + Object.keys(pin14).length + ' values pinned from the oracle (Android 14\'s shade rule)', k.fab.length === 1 && plan.entries && plan.entries.length === 165 && ts14 === 'same' && pinsOff(plan, pin14).length === 0, JSON.stringify([ts14, pinsOff(plan, pin14)]));
  check('   the record: Tonal Spot, the accents, the build, no profile (P14)', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accents":{"secondary":"E91E63"},"build":"' + B14 + '"}', await rec());
  // the values of the tap: the colour changed or cleared between the tap and the theme answer, both ways
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_root_applied', null); window.__delay = 400; }); await reset();
  await tap('#ovlApplyBtn'); await ev(() => typeof ovlAccSet === 'function' && ovlAccSet('secondary', '2962FF'));
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('D3. E91E63 when Apply is tapped, 2962FF picked before the theme answer: the plan has E91E63 (the pins of D1), and so has the record', pinsOff(plan, pin16).length === 0 && (await rec()).includes('"accents":{"secondary":"E91E63"}') && JSON.stringify(await kvs()) === '["2962FF",null]', JSON.stringify([pinsOff(plan, pin16), await rec()]));
  await setAcc('E91E63', null); await ev(() => kvSet('ovl_root_applied', null)); await reset();
  await tap('#ovlApplyBtn'); await ev(() => typeof ovlAccClear === 'function' && ovlAccClear('secondary'));
  await waitFab(); await settle(); await sleep(100); plan = (await calls()).fab[0] || {};
  check('   E91E63 when tapped, Clear before the answer: the plan and the record still have E91E63', pinsOff(plan, pin16).length === 0 && (await rec()).includes('"accents":{"secondary":"E91E63"}') && JSON.stringify(await kvs()) === '["",null]', JSON.stringify([pinsOff(plan, pin16), await rec()]));
  await ev(() => kvSet('ovl_root_applied', null)); await reset();
  await tap('#ovlApplyBtn'); await ev(() => typeof ovlAccSet === 'function' && ovlAccSet('secondary', 'E91E63'));
  await settle(); await sleep(500); k = await calls();
  check('   and the other way round: none set when tapped (Tonal Spot at 2021: Android\'s own colours), E91E63 picked before the answer: no overlay call, no record', k.apply.length === 1 && k.fab.length === 0 && (await rec()) === 'null', JSON.stringify(k.fab));
  await ev(() => { window.__delay = 20; });
  // both accents with the sliders
  await setAcc('E91E63', '00897B'); await setAdj(150, 100, 100); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const pinAdj16 = pinOf('P16', 'TONAL_SPOT', '2021', BOTH, A150);
  check('D4. both accents and Accent saturation 150, P16: entries with { accents both, adjust 150 / 100 / 100 } (accents first, then the sliders: ' + Object.keys(pinAdj16).length + ' values pinned from the oracle); the record has the adjust, then the accents', (await sameAs(plan, 'TONAL_SPOT', '6750A4', o16('2021', { adjust: A150, accents: BOTH }))) === 'same' && pinsOff(plan, pinAdj16).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"accents":{"secondary":"E91E63","tertiary":"00897B"},"profile":"P16","build":"' + B16 + '"}', JSON.stringify([pinsOff(plan, pinAdj16), await rec()]));
  await phone({ sdk: 34, build: B14 }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const pinAdj14 = pinOf('P14', 'TONAL_SPOT', '2021', BOTH, A150);
  check('   the same on P14: ' + Object.keys(pinAdj14).length + ' values pinned (Android 14\'s cap of 40 on shades 10 and 50 before the factor)', (await sameAs(plan, 'TONAL_SPOT', '6750A4', o14('2021', { adjust: A150, accents: BOTH }))) === 'same' && pinsOff(plan, pinAdj14).length === 0, JSON.stringify(pinsOff(plan, pinAdj14)));
  await setAdj(100, 100, 100);
  // with a More style
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); a = await acc(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const pinFid = pinOf('P16', 'FIDELITY', '2021', BOTH, null);
  check('D5. Fidelity in More styles with both accents (P16): the hint names Fidelity, then both accents; Fidelity\'s plan with the accents (' + Object.keys(pinFid).length + ' values pinned); the record: Fidelity and the accents', a.hint === HINT.more('Fidelity') && a.said === SAID.both && (await sameAs(plan, 'FIDELITY', '6750A4', o16('2021', { accents: BOTH }))) === 'same' && pinsOff(plan, pinFid).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","accents":{"secondary":"E91E63","tertiary":"00897B"},"profile":"P16","build":"' + B16 + '"}', JSON.stringify([a.hint, a.said, pinsOff(plan, pinFid), await rec()]));
  await setAcc('E91E63', 'FFD600'); await setAdj(150, 100, 100); await ev(() => { kvSet('ovl_spec', '2026'); kvSet('ovl_root_style', 'CMF'); ovlRender(); }); a = await acc(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const pinCmf = pinOf('P16', 'CMF', '2026', { secondary: 'E91E63', tertiary: 'FFD600' }, A150);
  check('   CMF at 2026 with both accents and Accent saturation 150: the adjusted CMF hint, then both accents; ' + Object.keys(pinCmf).length + ' values pinned; the record: spec 2026, the adjust, the accents', a.hint === HINT.moreAdj('CMF') && a.said === SAID.both && (await sameAs(plan, 'CMF', '6750A4', o16('2026', { adjust: A150, accents: { secondary: 'E91E63', tertiary: 'FFD600' } }))) === 'same' && pinsOff(plan, pinCmf).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"CMF","spec":"2026","adjust":{"accentSat":150,"bgSat":100,"bgLight":100},"accents":{"secondary":"E91E63","tertiary":"FFD600"},"profile":"P16","build":"' + B16 + '"}', JSON.stringify([a.hint, a.said, pinsOff(plan, pinCmf), await rec()]));
  await setAdj(100, 100, 100); await setAcc('E91E63', '00897B'); await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', 'MONOCHROMATIC'); ovlRender(); }); a = await acc(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const monoSame = await evm(() => JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0, accents: { secondary: 'E91E63', tertiary: '00897B' } })) === JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', { spec: '2021', dark: true, profile: 'P16', contrast: 0 })), undefined, false);
  check('   Monochrome with both accents set: they count for nothing (its rows are greys; MonetPlan gives the same entries with them): Monochrome\'s hint alone, its plan, a record without accents', monoSame && a.hint === HINT.more('Monochrome') && a.said === '' && (await sameAs(plan, 'MONOCHROMATIC', '6750A4', o16())) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"MONOCHROMATIC","profile":"P16","build":"' + B16 + '"}', JSON.stringify([a.hint, a.said, await rec()]));
  // none set: what PR 6 writes
  await setAcc(null, null); await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('D6. none set, Fidelity on P16: MonetPlan.entries without accents, and the record has none (as before the accents)', (await sameAs(plan, 'FIDELITY', '6750A4', o16())) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","profile":"P16","build":"' + B16 + '"}', await rec());
  await phone({ sdk: 34, build: B14 }); await setAcc('', ''); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   stored \'\' on P14: PR 3\'s plan (entries(FIDELITY, 6750A4) without options, accent1_500 0xFF7F68BE); the record as before', plan.entries && plan.entries.length === 165 && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan).accent1_500 === '0xFF7F68BE' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","build":"' + B14 + '"}', await rec());
  await ev(() => { kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2025'); ovlRender(); }); await phone({ sdk: 36, profile: 'P15Q2', build: B16 }); await setAcc('E91E63', 'FFD600'); a = await acc(); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  const pin25 = pinOf('P15Q2', 'TONAL_SPOT', '2025', { secondary: 'E91E63', tertiary: 'FFD600' }, null);
  check('D7. Tonal Spot at 2025 on P15Q2 with both accents: the 2025 hint, then both accents; entries with spec 2025 and the accents (' + Object.keys(pin25).length + ' values pinned); the record: spec 2025, the accents', a.hint === HINT.list25 && a.said === SAID.both && plan.entries && plan.entries.length === 177 && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true, profile: 'P15Q2', contrast: 0, accents: { secondary: 'E91E63', tertiary: 'FFD600' } })) === 'same' && pinsOff(plan, pin25).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025","accents":{"secondary":"E91E63","tertiary":"FFD600"},"profile":"P15Q2","build":"' + B16 + '"}', JSON.stringify([a.hint, a.said, pinsOff(plan, pin25), await rec()]));
  // Pure black wins where both name a resource
  await phone({ sdk: 36, profile: 'P16', build: B16 }); await setAcc('E91E63', null); await ev(() => { kvSet('ovl_spec', '2021'); kvSet('ovl_black', true); ovlRender(); }); a = await acc(); await reset(); await apply(); plan = (await calls()).fab[0] || {}; let r = byRes(plan);
  const keep16 = Object.fromEntries(Object.entries(pin16).filter(([n]) => n !== 'surface_dark'));
  check('D8. Pure black on with Secondary E91E63 (Tonal Spot at 2021, P16): the hint names this style\'s colors and pure black, then the secondary; 177 entries, no resource twice; neutral1_900, neutral2_900, background_dark and surface_dark black, the dark containers grey', a.hint === HINT.listBlack && a.said === SAID.sec && plan.entries && plan.entries.length === 177 && new Set(plan.entries.map(e => e.resource)).size === 177 && ['neutral1_900', 'neutral2_900', 'background_dark', 'surface_dark'].every(n => r[n] === '0xFF000000') && r.surface_container_dark === '0xFF181818', JSON.stringify([a.hint, a.said, r.surface_dark, r.surface_container_dark]));
  check('   the rest is the Tonal Spot with the secondary (the pins of D1 but surface_dark); the record: black, Tonal Spot, the accents', pinsOff(plan, keep16).length === 0 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"TONAL_SPOT","accents":{"secondary":"E91E63"},"profile":"P16","build":"' + B16 + '"}', JSON.stringify([pinsOff(plan, keep16), await rec()]));
  await ev(() => { kvSet('ovl_black', false); kvSet('ovl_root_applied', null); ovlRender(); });

  // ---- (f) the hint in every variant (P16; no overlay on the phone) ----
  const hintFor = (list, spec, more, black) => ev(([list, spec, more, black]) => { ovlPickStyle(list); kvSet('ovl_spec', spec); kvSet('ovl_root_style', more); kvSet('ovl_black', black); ovlRender(); return [(document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, (document.getElementById('ovlApplyHintAccents') || { innerText: '' }).innerText]; }, [list, spec, more, black]);
  // [list, spec, more, first sentence with an accent (sliders at 100) without / with Pure black, the same with a slider off 100, the sentence with none set (PR 6's) without / with Pure black]
  const VARIANTS = [
    ['TONAL_SPOT', '2021', '', [HINT.list, HINT.listBlack], [HINT.adj, HINT.adjBlack], ['', 'Also writes the pure black overlay with root.']],
    ['RAINBOW', '2025', '', [HINT.list, HINT.listBlack], [HINT.adj, HINT.adjBlack], ['', 'Also writes the pure black overlay with root.']],
    ['TONAL_SPOT', '2025', '', [HINT.list25, HINT.list25Black], [HINT.adj25, HINT.adj25Black], [HINT.list25, HINT.list25Black]],
    ['VIBRANT', '2026', '', [HINT.list25, HINT.list25Black], [HINT.adj25, HINT.adj25Black], [HINT.list25, HINT.list25Black]],
    ['TONAL_SPOT', '2021', 'FIDELITY', [HINT.more('Fidelity'), HINT.moreBlack('Fidelity')], [HINT.moreAdj('Fidelity'), HINT.moreAdjBlack('Fidelity')], [HINT.more('Fidelity'), HINT.moreBlack('Fidelity')]],
    ['TONAL_SPOT', '2025', 'CONTENT', [HINT.more('Content'), HINT.moreBlack('Content')], [HINT.moreAdj('Content'), HINT.moreAdjBlack('Content')], [HINT.more('Content'), HINT.moreBlack('Content')]],
    ['TONAL_SPOT', '2026', 'CMF', [HINT.more('CMF'), HINT.moreBlack('CMF')], [HINT.moreAdj('CMF'), HINT.moreAdjBlack('CMF')], [HINT.more('CMF'), HINT.moreBlack('CMF')]]
  ];
  for (const [list, spec, more, withAcc, withAdj, none] of VARIANTS) {
    const got = [];
    for (const [s, t, said] of [['E91E63', null, SAID.sec], [null, '00897B', SAID.ter], ['E91E63', '00897B', SAID.both]]) {
      await setAcc(s, t);
      const h = [await hintFor(list, spec, more, false), await hintFor(list, spec, more, true)];
      await setAdj(100, 70, 100);
      const j = [await hintFor(list, spec, more, false), await hintFor(list, spec, more, true)];
      await setAdj(100, 100, 100);
      got.push(h[0][0] === withAcc[0] && h[1][0] === withAcc[1] && j[0][0] === withAdj[0] && j[1][0] === withAdj[1] && [h[0][1], h[1][1], j[0][1], j[1][1]].every(x => x === said) ? 'ok' : JSON.stringify([h, j]));
    }
    await setAcc(null, null);
    const n = [await hintFor(list, spec, more, false), await hintFor(list, spec, more, true)];
    check('E. ' + list + ' at ' + spec + (more ? ', ' + more + ' in More styles' : '') + ': with an accent set the first sentence is ' + (withAcc[0] === none[0] ? 'the one from before' : 'this style\'s colors') + ' (the adjusted one with a slider off 100), then the secondary, tertiary or both sentence; with none set the hint is PR 6\'s and there is no second sentence', got.every(x => x === 'ok') && n[0][0] === none[0] && n[1][0] === none[1] && n[0][1] === '' && n[1][1] === '', JSON.stringify([got.filter(x => x !== 'ok'), n]));
  }
  await setAcc('E91E63', '00897B'); const mono = [await hintFor('TONAL_SPOT', '2021', 'MONOCHROMATIC', false), await hintFor('TONAL_SPOT', '2021', 'MONOCHROMATIC', true)];
  check('   Monochrome in More styles with both accents: its own sentence (with and without Pure black), no accent sentence', mono[0][0] === HINT.more('Monochrome') && mono[1][0] === HINT.moreBlack('Monochrome') && mono[0][1] === '' && mono[1][1] === '', JSON.stringify(mono));
  await setAcc(null, null); await ev(() => { ovlPickStyle('TONAL_SPOT'); kvSet('ovl_spec', '2021'); kvSet('ovl_root_style', ''); kvSet('ovl_black', false); ovlRender(); });

  // ---- (g) the record ----
  const readsRec = await ev(id => {
    const out = [], show = r => [(r.accents || {}).secondary, (r.accents || {}).tertiary].map(v => v === null ? 'null' : String(v)).join('/');
    for (const x of [{ style: 'FIDELITY' }, { style: 'TONAL_SPOT', accents: { secondary: 'E91E63', tertiary: '00897B' } }, { style: 'TONAL_SPOT', accents: { tertiary: 'FFD600' } }, { style: 'TONAL_SPOT', accents: { secondary: 'e91e63', tertiary: 123 } }, { style: 'TONAL_SPOT', accents: { secondary: '#E91E63' } }, { style: 'TONAL_SPOT', accents: 'loud' }, { black: true, style: '', accents: { secondary: 'E91E63' } }]) {
      kvSet('ovl_root_applied', Object.assign({ ids: [id], t: 1, black: false }, x)); out.push(show(ovlRootApplied()));
    }
    kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: [id], t: 1 }); out.push(show(ovlRootApplied())); kvSet('ovl_black_applied', null);
    return out;
  }, AAM);
  check('F1. records: one from before the accents reads as none set (and so do PR 2\'s pure black one and one without a style); the accents are read back; a value that is not RRGGBB in capitals counts as not set', JSON.stringify(readsRec) === JSON.stringify(['null/null', 'E91E63/00897B', 'null/FFD600', 'null/null', 'null/null', 'null/null', 'null/null', 'null/null']), JSON.stringify(readsRec));
  await setAcc('E91E63', null);
  await ev(([id, b]) => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: false, style: 'TONAL_SPOT', accents: { secondary: 'E91E63' }, profile: 'P15Q2', build: b }); }, [AAM, B16]);
  await reset(); await phone({ sdk: 36, profile: 'P16', build: B16b }); a = await acc(); let stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('F2. Tonal Spot at 2021 written with Secondary E91E63 on the old build (15 QPR2), still set, the phone on another build and profile (16.0): the note says Android was updated; nothing is written by itself', stale === STALE && a.hint === HINT.list && a.said === SAID.sec && (await calls()).fab.length === 0, JSON.stringify([stale, a.hint]));
  await setAcc('', null); a = await acc(); stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('   the secondary cleared (Apply would leave Tonal Spot to Android and switch the overlay off): no such note; the hint says Apply removes it', stale === '' && a.hint === 'Also removes the theme overlay.' && a.said === '', JSON.stringify([stale, a.hint]));
  await setAcc('E91E63', null); await reset(); await apply(); stale = await ev(() => document.getElementById('ovlRootStale').innerText);
  check('   set again and Apply: the colours are written for this build, the record keeps the accent and the new build, the note goes', (await calls()).fab.length === 1 && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","accents":{"secondary":"E91E63"},"profile":"P16","build":"' + B16b + '"}' && stale === '', JSON.stringify([await rec(), stale]));
  await setAcc('', null); await reset(); await apply();
  check('   cleared and Apply with nothing else on: the overlay is switched off (a disable, no fabricate), no record', await (async () => { const k = await calls(); return k.fab.length === 1 && k.fab[0].action === 'disable' && (await rec()) === 'null'; })());
  await setAcc(null, null);

  // ---- (h) the maths through the page's MonetPlan ----
  const STY = [['TONAL_SPOT', ['2021', '2025', '2026']], ['VIBRANT', ['2021', '2025']], ['EXPRESSIVE', ['2021', '2026']], ['RAINBOW', ['2021']], ['FRUIT_SALAD', ['2021', '2025']], ['SPRITZ', ['2021', '2025']], ['FIDELITY', ['2021']], ['CONTENT', ['2021']], ['MONOCHROMATIC', ['2021']], ['CMF', ['2026']]];
  const id0 = await evm(STY => {
    let n = 0; const off = [];
    for (const profile of ['P14', 'P16']) for (const [style, specs] of STY) for (const spec of specs) for (const dark of [false, true]) {
      const o = { spec, dark, profile, contrast: 0 }, e = JSON.stringify(MonetPlan.entries(style, '6750A4', o)), p = JSON.stringify(MonetPlan.palettes(style, '6750A4', o));
      for (const accents of [undefined, null, {}, { secondary: null, tertiary: null }]) {
        n++;
        const oa = Object.assign({ accents }, o);
        if (JSON.stringify(MonetPlan.entries(style, '6750A4', oa)) !== e || JSON.stringify(MonetPlan.palettes(style, '6750A4', oa)) !== p) off.push([profile, style, spec, dark, JSON.stringify(accents)].join(' '));
      }
    }
    return { n, off };
  }, STY, { n: 0, off: ['threw'] });
  check('I1. identity: with accents absent, null, {} or both null, entries and palettes are byte for byte the ones without, for every style and spec on P14 and P16, light and dark (' + id0.n + ' cases)', id0.n === 256 && id0.off.length === 0, JSON.stringify(id0.off.slice(0, 5)));
  const iso = await evm(STY => {
    const SECR = /^system_(accent2_|secondary|on_secondary|palette_key_color_secondary)/, TERR = /^system_(accent3_|tertiary|on_tertiary|palette_key_color_tertiary)/, out = [];
    let n = 0;
    for (const profile of ['P14', 'P16']) for (const [style, specs] of STY) for (const spec of specs) for (const [which, accents] of [['secondary', { secondary: '2962FF' }], ['tertiary', { tertiary: 'FF6D00' }]]) {
      const o = { spec, dark: true, profile, contrast: 0 }, base = MonetPlan.entries(style, '6750A4', o), got = MonetPlan.entries(style, '6750A4', Object.assign({ accents }, o));
      const bp = MonetPlan.palettes(style, '6750A4', o), gp = MonetPlan.palettes(style, '6750A4', Object.assign({ accents }, o));
      const re = which === 'secondary' ? SECR : TERR, row = which === 'secondary' ? 'accent2' : 'accent3';
      const changed = got.filter((e, i) => e.argb !== base[i].argb).map(e => e.resource.replace('android:color/', ''));
      const outside = changed.filter(r => !re.test(r)), rowsKept = ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2'].filter(r => r !== row).every(r => JSON.stringify(gp[r]) === JSON.stringify(bp[r]));
      const shadesMoved = gp[row].shades.slice(1, 12).some((h, i) => h !== bp[row].shades[i + 1]);
      n++;
      if (style === 'MONOCHROMATIC') { if (changed.length) out.push(profile + ' ' + style + ' ' + which + ' changed ' + changed.length); continue; }
      if (outside.length || !rowsKept || !shadesMoved || !changed.some(r => /_(light|dark)$/.test(r) && !/^system_(accent|neutral)/.test(r))) out.push([profile, style, spec, which, 'outside', outside.slice(0, 3).join(','), 'rowsKept', rowsKept, 'shadesMoved', shadesMoved].join(' '));
    }
    return { n, out };
  }, STY, { n: 0, out: ['threw'] });
  check('I2. a secondary (2962FF) changes only the accent2 shades and the secondary roles (secondary*, on_secondary*, the secondary fixed colours, palette_key_color_secondary), with the shades and some roles moving; a tertiary (FF6D00) only the accent3 ones; the other four rows of palettes() unchanged; every style and spec on P14 and P16 (' + iso.n + ' cases); under Monochrome nothing changes', iso.n === 64 && iso.out.length === 0, JSON.stringify(iso.out.slice(0, 5)));
  const hue = await evm(() => {
    const d = (a, b) => { const x = Math.abs(a - b) % 360; return Math.min(x, 360 - x); }, out = [];
    for (const [style, spec, profiles] of [['EXPRESSIVE', '2021', ['P14', 'P16']], ['FRUIT_SALAD', '2021', ['P14', 'P16']], ['FRUIT_SALAD', '2025', ['P16']]]) for (const profile of profiles) for (const x of ['E91E63', '2962FF', '00C853']) {
      const o = { spec, profile }, xh = MonetPlan.hct(x).hue, own = MonetPlan.palettes(style, x, o).accent1, got = MonetPlan.palettes(style, '6750A4', Object.assign({ accents: { secondary: x, tertiary: x } }, o));
      out.push({ k: [style, spec, profile, x].join(' '), turned: d(own.hue, xh) > 0.5, sec: d(got.accent2.hue, xh) <= 0.5 && Math.abs(got.accent2.chroma - own.chroma) < 1e-9, ter: d(got.accent3.hue, xh) <= 0.5 && Math.abs(got.accent3.chroma - own.chroma) < 1e-9 });
    }
    for (const profile of ['P14', 'P16']) for (const x of ['E91E63', '2962FF']) {
      const own = MonetPlan.palettes('TONAL_SPOT', x, { profile }).accent1, got = MonetPlan.palettes('TONAL_SPOT', '6750A4', { profile, accents: { secondary: x } }).accent2;
      out.push({ k: 'TONAL_SPOT 2021 ' + profile + ' ' + x, turned: false, sec: JSON.stringify(got) === JSON.stringify(own), ter: true });
    }
    return out;
  }, undefined, [{ k: 'threw' }]);
  check('I3. Expressive (2021) and Fruit Salad (2021, 2025) turn the hue of a primary away from its seed, yet a colour set as an accent keeps its hue (within 0.5 degrees) with the chroma the style gives it; Tonal Spot keeps the hue itself, so its accent row is the primary row of the colour\'s own Tonal Spot (P14 and P16; E91E63, 2962FF, 00C853)', hue.every(h => h.sec && h.ter && (h.k.startsWith('TONAL_SPOT') || h.turned)), JSON.stringify(hue.filter(h => !(h.sec && h.ter && (h.k.startsWith('TONAL_SPOT') || h.turned)))));
  const fid = await evm(() => ['P14', 'P16'].map(profile => {
    const o = { spec: '2021', dark: true, profile, contrast: 0, accents: { secondary: '00897B', tertiary: '00897B' } }, e = Object.fromEntries(MonetPlan.entries('FIDELITY', '6750A4', o).map(x => [x.resource.replace('android:color/system_', ''), x.argb])), p = MonetPlan.palettes('FIDELITY', '6750A4', o), h = MonetPlan.hct('00897B');
    return [e.palette_key_color_secondary_light, e.palette_key_color_secondary_dark, e.palette_key_color_tertiary_light, p.accent2.key, Math.abs(p.accent2.hue - h.hue) < 1e-9 && Math.abs(p.accent2.chroma - h.chroma) < 1e-9].join(' ');
  }), undefined, ['threw']);
  check('I4. Fidelity with 00897B as both accents keeps its hue and chroma, and palette_key_color_secondary (light and dark) and _tertiary are 0xFF00897B, on P14 and P16 (the key colour is the palette\'s own key colour search: X itself where X sits at the tone it finds, as 00897B does)', fid.every(x => x === '0xFF00897B 0xFF00897B 0xFF00897B 00897B true'), JSON.stringify(fid));
  const monoM = await evm(() => { const out = []; for (const profile of ['P14', 'P16']) for (const dark of [false, true]) for (const accents of [{ secondary: 'E91E63' }, { secondary: 'FF0000', tertiary: '00C853' }]) { const o = { spec: '2021', dark, profile, contrast: 0 }; out.push(JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', Object.assign({ accents }, o))) === JSON.stringify(MonetPlan.entries('MONOCHROMATIC', '6750A4', o))); } return out; }, undefined, ['threw']);
  check('I5. Monochrome: the accents leave all its entries as they are, on P14 and P16, light and dark', monoM.length === 8 && monoM.every(Boolean), JSON.stringify(monoM));
  const order = await evm(() => ['P14', 'P16'].map(profile => { const grey = h => h.slice(0, 2) === h.slice(2, 4) && h.slice(2, 4) === h.slice(4, 6); const p = MonetPlan.palettes('TONAL_SPOT', '6750A4', { profile, accents: { secondary: 'E91E63', tertiary: '00C853' }, adjust: { accentSat: 0 } }); return ['accent2', 'accent3'].every(r => p[r].chroma === 0 && p[r].shades.every(grey)); }), undefined, [false]);
  check('I6. the accents come before the sliders: with Accent saturation 0 the accent2 and accent3 rows set to E91E63 and 00C853 are greys (chroma 0, R = G = B), on P14 and P16 (the pins with 150 below as well)', order.every(Boolean), JSON.stringify(order));
  const thr = await evm(() => { const t = acc => { try { MonetPlan.entries('TONAL_SPOT', '6750A4', { profile: 'P16', accents: acc }); return 'ok'; } catch (e) { return 'throws'; } }; return [t(5), t('E91E63'), t(['E91E63']), t({ secondary: 123 }), t({ secondary: 'FFF' }), t({ secondary: '' }), t({ secondary: ' E91E63' }), t({ tertiary: 'GGGGGG' }), t({ secondary: '#e91e63' }), t({ secondary: 'e91e63', tertiary: '#00897B' }), JSON.stringify(MonetPlan.entries('TONAL_SPOT', '6750A4', { profile: 'P16', accents: { primary: 'FF0000', foo: 1 } })) === JSON.stringify(MonetPlan.entries('TONAL_SPOT', '6750A4', { profile: 'P16' })) ? 'ignored' : 'used']; }, undefined, ['threw']);
  check('I7. accents as a number, a string or an array throw, and so do a number, three digits, \'\', a space and non-hex as a colour; #e91e63 and e91e63 are taken (any case, with or without #); other keys are ignored', JSON.stringify(thr) === JSON.stringify(['throws', 'throws', 'throws', 'throws', 'throws', 'throws', 'throws', 'throws', 'ok', 'ok', 'ignored']), JSON.stringify(thr));
  for (const [profile, style, seed, spec, accents, adjust, pins] of PINS) {
    const off = await evm(([profile, style, seed, spec, accents, adjust, pins]) => {
      const out = [];
      for (const dark of [false, true]) {
        const have = Object.fromEntries(MonetPlan.entries(style, seed, Object.assign({ spec, dark, profile, contrast: 0, accents }, adjust ? { adjust } : {})).map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
        Object.keys(pins).forEach(k => { if (have[k] !== pins[k]) out.push((dark ? 'dark ' : 'light ') + k + ' ' + have[k] + ' != ' + pins[k]); });
      }
      return out;
    }, [profile, style, seed, spec, accents, adjust, pins], ['threw']);
    check('I8. ' + profile + ' ' + style + ' ' + seed + ' at ' + spec + ' ' + JSON.stringify(accents) + (adjust ? ' ' + JSON.stringify(adjust) : '') + ': ' + Object.keys(pins).length + ' values pinned from the oracle, light and dark mode', off.length === 0, off.join('; '));
  }

  // ---- (j) the new words in German (assets/lang/de.js) ----
  await phone({ sdk: 36, profile: 'P16', build: B16b }); await setAcc('E91E63', null); await ev(() => { ovlPickStyle('TONAL_SPOT'); kvSet('ovl_root_style', ''); kvSet('ovl_spec', '2021'); kvSet('ovl_black', false); });
  await ev(() => I18N.set('de')); await sleep(600); await ev(() => ovlRender()); await sleep(200); a = await acc();
  const de = await ev(words => { const x = (window.__LANGS.de && window.__LANGS.de.x) || {}; return words.map(w => x[w] || ''); }, [TITLE, SUB, 'Secondary accent', 'Tertiary accent', NONE, 'Clear', HINT.list, SAID.sec]);
  if (await page.$('#' + IDS[1])) await tap('#' + IDS[1]); await sleep(200); sh = await sheet(); await closeSheet();
  const dePat = await ev(() => { const p = (window.__LANGS.de && window.__LANGS.de.p) || []; const f = k => (p.find(x => x[0] === k) || [])[1] || ''; return f('The style gives #{0} now • Apply theme writes your color to the phone'); });
  check('J1. in German: the row, its line, the entry names, "' + NONE + '", Clear, both hint sentences and the picker\'s title and line read as assets/lang/de.js has them', de.every(t => t !== '') && de[0] !== TITLE && a.name === de[0] + ' ' + de[1] && (a.group || {}).label === de[0] && a.entries.map(e => e.name).join('|') === de[2] + '|' + de[3] && (a.entries[1] || {}).value === de[4] && ((a.entries[0] || {}).clear || {}).text === de[5] && a.hint === de[6] && a.said === de[7] && sh.title === de[3] && dePat !== '' && sh.sub === dePat.replace('{0}', sh.hex), JSON.stringify([de, a.name, a.entries, a.hint, a.said, sh]));
  await ev(() => I18N.set('en')); await sleep(200); await ev(() => ovlRender()); await sleep(100); a = await acc();
  check('   back in English with a render: the row\'s words follow', a.name === TITLE + ' ' + SUB && (a.group || {}).label === TITLE && (a.entries[1] || {}).value === NONE && a.said === SAID.sec, JSON.stringify([a.name, a.said]));

  // ---- (k) at 320 px, in English and German, one colour set (its hex and Clear) and one not: the row fits (sizes printed only on a failure) ----
  await setAcc('E91E63', null); await page.setViewportSize({ width: 320, height: 800 });
  for (const lang of ['en', 'de']) {
    await ev(l => I18N.set(l), lang); await sleep(400); await ev(() => ovlRender()); await sleep(150);
    const g = await ev(() => {
      const box = document.getElementById('ovlRootAccents') || document.createElement('div');
      return { sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, live: !!box.querySelector('.ovl-acc'),
        out: Array.from(box.querySelectorAll('*')).filter(e => { const q = e.getBoundingClientRect(); return q.width > 0 && (q.right > window.innerWidth + 1 || q.left < -1); }).map(e => e.id || e.className),
        small: Array.from(box.querySelectorAll('button')).filter(x => x.getBoundingClientRect().height < 32).map(x => x.id) };
    });
    const fits = g.live && g.sw <= g.cw && g.out.length === 0 && g.small.length === 0;
    check('K1. ' + (lang === 'en' ? 'English' : 'German') + ' at 320 px, Secondary E91E63 set (its hex and Clear), Tertiary from the style: the row fits, nothing sticks out sideways, no page scroll sideways, no button under 32 px tall', fits, fits ? '' : JSON.stringify(g));
  }
  await ev(() => I18N.set('en')); await sleep(200); await page.setViewportSize({ width: 360, height: 800 }); await ev(() => ovlRender());
  await setAcc(null, null);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
