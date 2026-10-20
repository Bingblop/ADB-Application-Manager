// Color spec (2021 / 2025 / 2026) and the CMF style on the Overlays tab's Theme panel (PR 4 of the ColorBlendr series): Google's newer colour
// specs, computed by assets/monet.js part 2 and written as root over Android's own colours. (a) the maths in the page context: MonetPlan.specFor
// for every style and spec (Google's rule: Tonal Spot, Vibrant, Expressive and Spritz get 2025 at 2025 and 2026, CMF keeps its spec, the rest
// 2021); at 2025 Tonal Spot's primary chroma is 32 in light and 26 in dark and the 65 shades follow opts.dark while the 100 role and fixed
// entries do not; 165 entries, no resource twice, well formed, in PR 3's order; values pinned from the oracle (Google's Material Color
// Utilities at main bd811e4, bundled and run in node; each pin was computed by it and by monet.js, equal): primary, surface,
// on_primary_container and outline light and dark, accent1 and neutral1 500 and 900 for each mode, CMF's primary, surface and error; CMF at
// any spec but 2026 throws; with no opts, with spec 2021 and for a style that falls back, PR 3's values (t565's Fidelity pins), byte for
// byte. (b) the card: a locked row without Super User (no bridge call, a stored 2025 counts for nothing), "Needs Android 14" with Super User
// on Android 13, "Update the app" without the spec maths or without overlayFabricate, the radio group on Android 14 (kv ovl_spec, persists,
// a redraw and a tab change; a spec picked from the keyboard keeps the focus), the note under it only at 2025 and 2026, CMF in More styles
// only at 2026 (and the 2026 line of More styles), leaving 2026 with CMF chosen sets More styles to Off, the preview for a list style at 2025
// (and its light or dark shades, drawn again when the phone switches mode with the tab open: onSystemAppearanceChanged), the source line
// for a grey seed; each new hint sentence. (c) Apply: Tonal Spot at 2025 sends themeApply (TONAL_SPOT) and then the 165-entry 2025 plan (its
// system_primary_light the 2025 value), with Pure black still 165 with the black entries; the record carries spec; Rainbow at 2025 with
// nothing else on sends a disable after a write and then nothing; Fidelity at 2026 sends the 2021 plan; CMF at 2026 the CMF plan; the 65
// shades follow the dark mode of the moment Apply is tapped (switched between the tap and the answer, both ways), and so does the list style
// (another one picked before the answer changes nothing, both ways); a grey seed gives Google blue's plan; Undo; no overlay without Super
// User or below Android 14; old records without spec read as 2021.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
// [style, seed, spec, dark, { resource suffix: value }]: six hex digits for a palette shade ('0xFF' in front of it), 0xAARRGGBB for a role.
// Every value: the oracle (Material Color Utilities main bd811e4: MaterialDynamicColors roles on new Scheme*(Hct.fromInt(seed), dark, 0, spec);
// the shades as Hct.from(palette hue, palette chroma, tone 49.6 or 10) of that scheme's palettes, Android 14's Shades.of rule) and monet.js agree.
const PINS = [
  ['TONAL_SPOT', '6750A4', '2025', false, { primary_light: '0xFF655789', primary_dark: '0xFFCDC0EC', surface_light: '0xFFFDF7FE', surface_dark: '0xFF0F0D12', on_primary_container_light: '0xFF493C6C', on_primary_container_dark: '0xFFE9DEFF', outline_light: '0xFF7D7983', outline_dark: '0xFF78737E', accent1_500: '7C6EA2', accent1_900: '201241', neutral1_500: '78757A', neutral1_900: '1C1B1F' }],
  ['TONAL_SPOT', '6750A4', '2025', true, { primary_light: '0xFF655789', primary_dark: '0xFFCDC0EC', surface_light: '0xFFFDF7FE', surface_dark: '0xFF0F0D12', on_primary_container_light: '0xFF493C6C', on_primary_container_dark: '0xFFE9DEFF', outline_light: '0xFF7D7983', outline_dark: '0xFF78737E', accent1_500: '7B7098', accent1_900: '1F1538', neutral1_500: '78757A', neutral1_900: '1C1B1F' }],
  ['TONAL_SPOT', 'B5651D', '2025', true, { primary_light: '0xFF875329', primary_dark: '0xFFF4BB92', surface_light: '0xFFFFF8F5', surface_dark: '0xFF130D09', on_primary_container_light: '0xFF5D3109', on_primary_container_dark: '0xFFFFDDC6', outline_light: '0xFF89776C', outline_dark: '0xFF837267', accent1_500: '9B6C49', accent1_900: '301400', neutral1_500: '7F746E', neutral1_900: '211A16' }],
  ['CMF', '6750A4', '2026', true, { primary_light: '0xFF5B4497', primary_dark: '0xFFA890E9', surface_light: '0xFFFDF7FF', surface_dark: '0xFF0F0D16', error_light: '0xFF9F3F47', error_dark: '0xFFFA868D', on_primary_container_light: '0xFFFCF6FF', outline_light: '0xFF7E778E', outline_dark: '0xFF797289', accent1_500: '7F68BE', accent1_900: '22005D', neutral1_500: '797480', neutral1_900: '1D1A24' }],
  ['CMF', 'B5651D', '2026', false, { primary_light: '0xFF944C00', primary_dark: '0xFFE1883F', surface_light: '0xFFFFF8F5', surface_dark: '0xFF160C05', error_light: '0xFF9E422C', error_dark: '0xFFFE8B70', accent1_500: 'B1621A', accent1_900: '301400', neutral1_500: '857266', neutral1_900: '251910' }]
];
// t565's pins for Fidelity and 6750A4 (PR 3's values, Android 14's engine)
const PR3_FIDELITY = { accent1_500: '7F68BE', accent1_900: '22005D', accent2_500: '7B7195', accent2_900: '1F1635', accent3_500: '90721C', accent3_900: '241A00', neutral1_500: '78757C', neutral1_900: '1D1B20', neutral2_500: '797481', neutral2_900: '1D1A24', primary_light: '0xFF513A8D', surface_light: '0xFFFDF7FF', outline_light: '0xFF77727F', palette_key_color_primary_light: '0xFF8069BF', control_highlight_light: '0x1F000000', primary_dark: '0xFFCFBCFF', surface_dark: '0xFF141218', outline_dark: '0xFF948E9C', palette_key_color_primary_dark: '0xFF8069BF', control_highlight_dark: '0x33FFFFFF' };
const LEAD = ['android:color/system_accent1_600', 'android:color/system_neutral1_500', 'android:color/system_primary_light', 'android:color/system_surface_dark'];
const SPEC_SUB = 'Recolors Tonal Spot, Vibrant, Expressive and Spritz with root. CMF needs 2026.';
const SPEC_NOTE = 'Rainbow, Fruit Salad, Fidelity, Content and Monochrome keep their 2021 colors. The base shades follow light or dark mode as the phone is when you tap Apply; after switching, tap Apply again.';
const MORE_SUB = 'Fidelity, Content and Monochrome: colors Android has no setting for, computed by this app.';
const MORE_SUB_2026 = 'Fidelity, Content, Monochrome and CMF: colors Android has no setting for, computed by this app.';
const AAM = 'com.android.shell:aam_monet';
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
  const setSdk = async n => { await ev(v => { window.__sdk = v; ovlReadPalette(); }, n); await sleep(150); await ev(() => ovlRender()); };
  // a locked row or the live radio group of one of the root cards (#ovlRootSpec or #ovlRootMore)
  const rowOf = (boxId, groupId, dataKey) => ev(([boxId, groupId, dataKey]) => {
    const box = document.getElementById(boxId); if (!box) return null;
    const row = box.querySelector('.rf-row'), lockBtn = box.querySelector('.op-mode-btn.locked'), lock = box.querySelector('.pm-lock'), group = document.getElementById(groupId);
    const radios = group ? Array.from(group.querySelectorAll('.ovl-style')).map(r => ({ id: r.dataset[dataKey], role: r.getAttribute('role'), checked: r.getAttribute('aria-checked'), active: r.classList.contains('active'), name: r.querySelector('b').innerText, desc: r.querySelector('.ovl-styletxt > span').innerText })) : null;
    const note = document.getElementById('ovlRootSpecNote');
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(row && row.classList.contains('is-locked')), name: row ? row.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null, lock: lock ? lock.innerText : '',
      group: group ? { role: group.getAttribute('role'), label: group.getAttribute('aria-label') } : null, radios, specNote: note ? note.innerText : null, rootHint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText, cardNote: document.getElementById('ovlRootNote').innerText };
  }, [boxId, groupId, dataKey]);
  const spec = () => rowOf('ovlRootSpec', 'ovlRootSpecs', 'spec');
  const more = () => rowOf('ovlRootMore', 'ovlRootStyles', 'style');
  const preview = () => ev(() => {
    const pv = document.getElementById('ovlRootPreview'); if (!pv) return null;
    const rows = Array.from(pv.querySelectorAll('.ovl-pal'));
    return { empty: pv.innerHTML === '', rows: rows.length, chips: rows.map(r => r.querySelectorAll('.ovl-chip').length), source: (document.getElementById('ovlRootSource') || { innerText: '' }).innerText, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] };
  });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(100); };
  const byRes = plan => Object.fromEntries(((plan && plan.entries) || []).map(e => [e.resource, e.argb]));
  // the plan's entries against MonetPlan.entries(style, seed, opts) in the page: the same resources, each with the same value
  const sameAs = (plan, style, seed, opts) => ev(([entries, style, seed, opts]) => { const want = MonetPlan.entries(style, seed, opts || undefined); if (!entries || want.length !== entries.length) return 'count ' + (entries || []).length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan && plan.entries, style, seed, opts || null]);
  const rec = async () => { const a = await kv('ovl_root_applied'); return a ? JSON.stringify(a).replace(/"t":\d+/, '"t":T') : 'null'; };
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });

  // ---- (a) the maths, in the page context ----
  const table = await ev(() => { const out = {}; for (const s of MonetPlan.STYLES) out[s] = MonetPlan.SPECS.map(x => MonetPlan.specFor(s, x)).join(' '); return { specs: MonetPlan.SPECS.join(' '), out }; });
  const WANT = { TONAL_SPOT: '2021 2025 2025', VIBRANT: '2021 2025 2025', EXPRESSIVE: '2021 2025 2025', SPRITZ: '2021 2025 2025', RAINBOW: '2021 2021 2021', FRUIT_SALAD: '2021 2021 2021', FIDELITY: '2021 2021 2021', CONTENT: '2021 2021 2021', MONOCHROMATIC: '2021 2021 2021', CMF: '2021 2025 2026' };
  check('A1. MonetPlan.SPECS is 2021 2025 2026; specFor for the ten styles and the three specs is Google\'s rule (2026 turns into 2025 for Tonal Spot, Vibrant, Expressive and Spritz, CMF keeps its spec, the other five are always 2021)', table.specs === '2021 2025 2026' && Object.keys(table.out).length === 10 && Object.keys(WANT).every(k => table.out[k] === WANT[k]), JSON.stringify(table));
  check('   specFor without a spec is 2021; an unknown spec or style throws', await ev(() => { let a = false, b = false; try { MonetPlan.specFor('TONAL_SPOT', '2024'); } catch (e) { a = true; } try { MonetPlan.specFor('BOGUS', '2025'); } catch (e) { b = true; } return a && b && MonetPlan.specFor('VIBRANT') === '2021'; }));
  const td = await ev(() => {
    const l = MonetPlan.palettes('TONAL_SPOT', '6750A4', { spec: '2025', dark: false }), d = MonetPlan.palettes('TONAL_SPOT', '6750A4', { spec: '2025', dark: true });
    const el = MonetPlan.entries('TONAL_SPOT', '6750A4', { spec: '2025', dark: false }), ed = MonetPlan.entries('TONAL_SPOT', '6750A4', { spec: '2025', dark: true });
    const rows = ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2'], flat = p => rows.map(r => p[r].shades.map(h => '0xFF' + h)).flat();
    return { cl: l.accent1.chroma, cd: d.accent1.chroma, shadesL: JSON.stringify(el.slice(0, 65).map(e => e.argb)) === JSON.stringify(flat(l)), shadesD: JSON.stringify(ed.slice(0, 65).map(e => e.argb)) === JSON.stringify(flat(d)),
      differ: JSON.stringify(el.slice(0, 65).reduce((o, e, i) => { if (e.argb !== ed[i].argb) { const r = e.resource.replace('android:color/system_', '').split('_')[0]; o[r] = (o[r] || 0) + 1; } return o; }, {})), rolesSame: JSON.stringify(el.slice(65)) === JSON.stringify(ed.slice(65)), res: JSON.stringify(el.map(e => e.resource)) === JSON.stringify(ed.map(e => e.resource)) };
  });
  check('A2. Tonal Spot 6750A4 at 2025: the primary palette\'s chroma is 32 for light and 26 for dark', td.cl === 32 && td.cd === 26, JSON.stringify([td.cl, td.cd]));
  check('   the 65 palette entries are the shades of the palettes of the mode asked for (opts.dark): only the primary row differs, in 8 of its 13 shades (the oracle\'s palettes: primary chroma 32 against 26, the other four the same); the 88 roles and the 12 fixed colours are the same either way', td.shadesL && td.shadesD && td.differ === '{"accent1":8}' && td.rolesSame && td.res, JSON.stringify(td));
  for (const [style, seed, sp, dark, pins] of PINS) {
    const r = await ev(([style, seed, sp, dark, pins]) => {
      const es = MonetPlan.entries(style, seed, { spec: sp, dark }), names = es.map(e => e.resource), have = {};
      es.forEach(e => { have[e.resource.replace('android:color/system_', '')] = e.argb; });
      const bad = Object.keys(pins).filter(k => have[k] !== (pins[k].length === 6 ? '0xFF' + pins[k] : pins[k])).map(k => k + ' ' + have[k] + ' != ' + pins[k]);
      const pr3 = MonetPlan.entries(style === 'CMF' ? 'TONAL_SPOT' : style, seed).map(e => e.resource);
      return { n: es.length, uniq: new Set(names).size, res: names.every(n => /^android:color\/system_[a-z0-9_]+$/.test(n)), val: es.every(e => /^0x[0-9A-F]{8}$/.test(e.argb)), order: JSON.stringify(names) === JSON.stringify(pr3), bad };
    }, [style, seed, sp, dark, pins]);
    check('A3. ' + style + ' ' + seed + ' at ' + sp + (dark ? ' (dark)' : ' (light)') + ': 165 entries, no resource twice, android:color/system_* and 0xAARRGGBB, the resources in PR 3\'s order', r.n === 165 && r.uniq === 165 && r.res && r.val && r.order, JSON.stringify([r.n, r.uniq, r.res, r.val, r.order]));
    check('   ' + Object.keys(pins).length + ' values pinned from the oracle', r.bad.length === 0, r.bad.join('; '));
  }
  check('A4. Tonal Spot\'s 2025 primary_light (655789) is not Android\'s own 2021 one (65558F)', await ev(() => { const h = es => Object.fromEntries(es.map(e => [e.resource, e.argb])); return h(MonetPlan.entries('TONAL_SPOT', '6750A4'))['android:color/system_primary_light'] === '0xFF65558F' && h(MonetPlan.entries('TONAL_SPOT', '6750A4', { spec: '2025' }))['android:color/system_primary_light'] === '0xFF655789'; }));
  const cmfThrows = await ev(() => ['2025', '2021', undefined].map(s => { try { MonetPlan.entries('CMF', '6750A4', s === undefined ? undefined : { spec: s }); return 'no'; } catch (e) { return 'throws'; } }).concat([(() => { try { MonetPlan.palettes('CMF', '6750A4', { spec: '2025' }); return 'no'; } catch (e) { return 'throws'; } })()]));
  check('A5. CMF at 2025, at 2021 and without a spec throws (entries and palettes), as Google\'s SchemeCmf does', cmfThrows.every(x => x === 'throws'), JSON.stringify(cmfThrows));
  const pr3 = await ev(pins => {
    const h = es => Object.fromEntries(es.map(e => [e.resource.replace('android:color/system_', ''), e.argb]));
    const none = MonetPlan.entries('FIDELITY', '6750A4'), j = JSON.stringify(none), have = h(none);
    const bad = Object.keys(pins).filter(k => have[k] !== (pins[k].length === 6 ? '0xFF' + pins[k] : pins[k]));
    const same = [{ spec: '2021' }, { spec: '2021', dark: true }, { spec: '2025' }, { spec: '2026', dark: true }].every(o => JSON.stringify(MonetPlan.entries('FIDELITY', '6750A4', o)) === j);
    const ts = JSON.stringify(MonetPlan.entries('TONAL_SPOT', '6750A4')), tsSame = JSON.stringify(MonetPlan.entries('TONAL_SPOT', '6750A4', { spec: '2021', dark: true })) === ts && JSON.stringify(MonetPlan.palettes('TONAL_SPOT', '6750A4')) === JSON.stringify(MonetPlan.palettes('TONAL_SPOT', '6750A4', { spec: '2021', dark: true }));
    return { bad, same, tsSame };
  }, PR3_FIDELITY);
  check('A6. without opts the results are PR 3\'s: Fidelity 6750A4 has t565\'s 20 pinned values, and spec 2021 (light or dark), 2025 and 2026 give it the same 165 entries byte for byte (it falls back to 2021); Tonal Spot at 2021 is the same with or without dark', pr3.bad.length === 0 && pr3.same && pr3.tsSame, JSON.stringify(pr3));
  check('   the seed rule takes CMF like a stock style: a grey (808080) becomes Google blue, 6750A4 stays', await ev(() => MonetPlan.seed('CMF', '808080') === '1B6EF3' && MonetPlan.seed('CMF', '6750A4') === '6750A4' && MonetPlan.STYLES.includes('CMF')));

  // ---- (b) the card ----
  await ev(() => switchView('overlays')); await sleep(300);
  await ev(() => { ovlPickColor('6750A4'); ovlPickStyle('TONAL_SPOT'); kvSet('ovl_spec', '2025'); ovlRender(); });
  let s = await spec(), m = await more();
  check('B1. without Super User: Color spec is a locked row of the Root Features kind (Locked, Needs Super User) with its line, no radios, no note', s && s.locked && s.rows === 1 && s.name === 'Color spec ' + SPEC_SUB && s.lockBtn && s.lockBtn.text === 'Locked' && s.lockBtn.disabled && s.lock === 'Needs Super User' && !s.radios && s.group === null && s.specNote === null, JSON.stringify(s));
  check('   its container sits between the Pure black row and More styles, inside the Root-only theme controls card', await ev(() => { const box = document.getElementById('ovlRootSpec'); return !!box.closest('#ovlRootCard') && box.previousElementSibling.id === 'ovlRootCards' && box.nextElementSibling.id === 'ovlRootMore'; }) && m.locked);
  check('   a stored 2025 with Tonal Spot in the list counts for nothing: no hint, no plan style', s.rootHint === '' && (await ev(() => ovlRootPlanOn('TONAL_SPOT').style)) === '' && (await kv('ovl_spec')) === '2025', JSON.stringify([s.rootHint, await kv('ovl_spec')]));
  await reset(); await apply(); let k = await calls();
  check('   Apply writes the theme setting only: no bridge call for the overlay', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0, JSON.stringify([k.apply, k.fab.length]));

  await ev(() => { window.__sdk = 33; ovlReadPalette(); }); await sleep(150);
  await setRoot(true); s = await spec(); m = await more();
  check('B2. Super User on Android 13: Color spec is a locked row saying Needs Android 14 (as More styles is)', s.locked && s.lock === 'Needs Android 14' && s.lockBtn && s.lockBtn.disabled && !s.radios && m.locked && m.lock === 'Needs Android 14', JSON.stringify([s.lock, m.lock]));
  await reset(); await apply(); k = await calls();
  check('   the stored 2025 and Tonal Spot write nothing there: no hint, Apply sends no overlay', s.rootHint === '' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([s.rootHint, k.fab]));

  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = Object.assign({}, window.__mp, { SPECS: undefined, specFor: undefined }); kvSet('ovl_spec', '2026'); kvSet('ovl_root_style', 'CMF'); });
  await setSdk(34); s = await spec(); m = await more();
  check('B3. Android 14 with Super User and a colour maths without the specs (PR 3\'s monet.js): Color spec is locked with Update the app; More styles works as in PR 3 (four radios, no CMF though 2026 and CMF are stored, Off checked)', s.locked && s.lock === 'Update the app' && !s.radios && !m.locked && m.radios && m.radios.map(r => r.id).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC' && m.radios[0].checked === 'true' && m.name === 'More styles ' + MORE_SUB, JSON.stringify([s.lock, m.radios && m.radios.map(r => r.id + ':' + r.checked)]));
  check('   the stored CMF and 2026 count for nothing there: no root style, no hint, no plan style', (await ev(() => ovlRootStyle() === '' && ovlSpecNow() === '2021' && ovlRootPlanOn('TONAL_SPOT').style === '')) && m.rootHint === '', m.rootHint);
  await ev(() => { window.MonetPlan = window.__mp; kvSet('ovl_root_style', ''); delete window.AndroidBridge.__kv.ovl_spec; window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; ovlRender(); });
  s = await spec();
  check('   an APK without overlayFabricate: Color spec is locked with Update the app, as the other two rows are', s.locked && s.lock === 'Update the app' && !s.radios && (await more()).lock === 'Update the app', s.lock);
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; ovlRender(); });

  // B4: live on Android 14
  s = await spec(); m = await more();
  check('B4. Android 14 with Super User: a radio group (role radiogroup, labelled Color spec) of 2021, 2025 and 2026 with their lines; nothing stored, so 2021 is checked; no note', !s.locked && s.rows === 1 && s.name === 'Color spec ' + SPEC_SUB && !s.lockBtn && s.group && s.group.role === 'radiogroup' && s.group.label === 'Color spec' && s.radios.map(r => r.id).join(',') === '2021,2025,2026' && s.radios.every(r => r.role === 'radio') && s.radios.map(r => r.name).join('|') === '2021|2025|2026' && s.radios.map(r => r.desc).join('|') === 'The colors Android computes itself.|Google\'s newer tuning of the same styles.|The 2025 tuning, plus the CMF style.' && s.radios.map(r => r.checked).join(',') === 'true,false,false' && s.radios[0].active && s.specNote === null && (await kv('ovl_spec')) === null, JSON.stringify(s));
  check('   the look of the style lists: .ovl-style buttons with a radio dot in an .ovl-styles box', await ev(() => { const a = document.querySelector('#ovlRootSpecs .ovl-style'), b = document.querySelector('#ovlRootStyles .ovl-style'); return !!a && !!b && !!a.querySelector('.ovl-radio') && a.parentElement.classList.contains('ovl-styles') && getComputedStyle(a).borderRadius === getComputedStyle(b).borderRadius; }));
  check('   More styles at 2021: four radios, no CMF, its PR 3 line; Tonal Spot in the list writes nothing (no hint, no preview)', m.radios.map(r => r.id).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC' && m.name === 'More styles ' + MORE_SUB && m.rootHint === '' && (await preview()).empty, JSON.stringify([m.radios.map(r => r.id), m.rootHint]));
  await page.locator('#ovlRootSpecs .ovl-style[data-spec="2025"]').click(); await sleep(80); s = await spec(); m = await more();
  check('B5. tapping 2025 stores it (kv ovl_spec) and checks it; no bridge call; the note appears under the radios', (await kv('ovl_spec')) === '2025' && s.radios.map(r => r.checked).join(',') === 'false,true,false' && s.radios[1].active && !s.radios[0].active && s.specNote === SPEC_NOTE && (await calls()).fab.length === 0, JSON.stringify([await kv('ovl_spec'), s.specNote]));
  check('   More styles at 2025: still four radios and the PR 3 line (CMF needs 2026)', m.radios.map(r => r.id).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC' && m.name === 'More styles ' + MORE_SUB, JSON.stringify(m.radios.map(r => r.id)));
  await ev(() => ovlRender()); s = await spec();
  check('   a redraw reads the choice from the store', s.radios[1].checked === 'true' && s.specNote === SPEC_NOTE);
  await ev(() => switchView('home')); await sleep(150); await ev(() => switchView('overlays')); await sleep(300); s = await spec();
  check('   so does coming back to the tab', s.radios[1].checked === 'true' && s.specNote === SPEC_NOTE && (await kv('ovl_spec')) === '2025');
  await page.locator('#ovlRootSpecs .ovl-style[data-spec="2026"]').click(); await sleep(80); s = await spec(); m = await more();
  check('B6. 2026: stored and checked, the note stays; More styles lists CMF fifth with its line, and its own line names CMF', (await kv('ovl_spec')) === '2026' && s.radios.map(r => r.checked).join(',') === 'false,false,true' && s.specNote === SPEC_NOTE && m.radios.map(r => r.id).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC,CMF' && m.radios[4].name === 'CMF' && m.radios[4].desc === 'One color family: accents and backgrounds are your color at different strengths.' && m.name === 'More styles ' + MORE_SUB_2026, JSON.stringify([m.radios.map(r => r.id), m.name]));
  await page.locator('#ovlRootStyles .ovl-style[data-style="CMF"]').click(); await sleep(80); m = await more();
  check('   tapping CMF stores it and checks it; the preview appears', (await kv('ovl_root_style')) === 'CMF' && m.radios[4].checked === 'true' && m.radios[4].active && (await preview()).rows === 5, JSON.stringify([await kv('ovl_root_style'), m.radios.map(r => r.checked)]));
  await page.locator('#ovlRootSpecs .ovl-style[data-spec="2025"]').click(); await sleep(80); s = await spec(); m = await more();
  check('B7. leaving 2026 with CMF chosen: the same tap stores 2025 and sets More styles to Off (kv ovl_root_style empty), Off is checked and CMF is gone', (await kv('ovl_spec')) === '2025' && (await kv('ovl_root_style')) === '' && m.radios.map(r => r.id).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC' && m.radios[0].checked === 'true' && m.name === 'More styles ' + MORE_SUB, JSON.stringify([await kv('ovl_spec'), await kv('ovl_root_style'), m.radios.map(r => r.id + ':' + r.checked)]));
  await ev(() => { ovlPickSpec('2026'); ovlPickRootStyle('FIDELITY'); ovlPickSpec('2025'); }); await sleep(50);
  check('   leaving 2026 with another More style chosen keeps it', (await kv('ovl_root_style')) === 'FIDELITY' && (await kv('ovl_spec')) === '2025');
  await ev(() => ovlPickRootStyle('OFF'));
  await page.locator('#ovlRootSpecs .ovl-style[data-spec="2021"]').click(); await sleep(80); s = await spec();
  check('B8. back to 2021: stored, checked, the note goes away', (await kv('ovl_spec')) === '2021' && s.radios[0].checked === 'true' && s.specNote === null, JSON.stringify([await kv('ovl_spec'), s.specNote]));
  // keyboard focus must survive the redraw a pick makes (t66 checks the same for the style list, the switches and the preset families)
  await page.evaluate(() => document.querySelector('#ovlRootSpecs .ovl-style[data-spec="2025"]').focus());
  await page.keyboard.press('Enter'); await sleep(100);
  const foc = await ev(() => { const a = document.activeElement; return { spec: a && a.dataset ? a.dataset.spec || null : null, tag: a ? a.tagName : null, inGroup: !!(a && a.closest && a.closest('#ovlRootSpecs')), checked: a && a.getAttribute ? a.getAttribute('aria-checked') : null }; });
  check('   a spec chosen from the keyboard (2025, Enter) is stored and keeps the focus on its radio, drawn anew and checked', (await kv('ovl_spec')) === '2025' && foc.spec === '2025' && foc.inGroup && foc.checked === 'true', JSON.stringify(foc));
  await ev(() => ovlPickSpec('2021'));
  await ev(() => { kvSet('ovl_spec', '2024'); ovlRender(); }); s = await spec();
  const odd = await ev(() => { ovlPickSpec('1999'); const a = kvGet('ovl_spec', null); kvSet('ovl_spec', '2025'); kvSet('ovl_root_style', 'CMF'); ovlPickRootStyle('CMF'); return { a, style: ovlRootStyle(), stored: kvGet('ovl_root_style', null) }; });
  check('B9. a stored spec that is not one reads as 2021; a spec that is not one is ignored; at 2025 a stored CMF counts for nothing and CMF cannot be picked', s.radios[0].checked === 'true' && s.specNote === null && odd.a === '2024' && odd.style === '' && odd.stored === 'CMF', JSON.stringify([s.radios.map(r => r.checked), odd]));
  await ev(() => { kvSet('ovl_root_style', ''); ovlRender(); });

  // B10: the preview for a list style at 2025 (More styles Off), drawn from the palettes Apply uses, for the mode the phone is in
  await ev(() => { ovlPickSpec('2025'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); }); await sleep(80);
  let p = await preview();
  check('B10. at 2025 with Tonal Spot in the list and More styles Off: the preview shows five rows of 13, the 2025 dark shades (accent1 500 is 7B7098), from the colour above', p.rows === 5 && p.chips.join(',') === '13,13,13,13,13' && p.accent1[7] === '#7B7098' && p.source === 'From the color above (#6750A4)', JSON.stringify(p));
  await ev(() => { window.__dark = false; ovlRender(); }); p = await preview();
  check('   the phone in light mode: the 2025 light shades (7C6EA2)', p.accent1[7] === '#7C6EA2', p.accent1[7]);
  // a light or dark switch with the app open reaches the page only as onSystemAppearanceChanged (uiMode is in the activity's configChanges)
  await ev(() => { window.__dark = true; onSystemAppearanceChanged(); }); await sleep(50); p = await preview();
  check('   the phone switched to dark mode while the tab is open (MainActivity calls onSystemAppearanceChanged and nothing else): the preview is drawn again with the dark shades (7B7098)', p.rows === 5 && p.accent1[7] === '#7B7098', p.accent1[7]);
  await ev(() => { window.__dark = false; onSystemAppearanceChanged(); }); await sleep(50); p = await preview();
  check('   and back to light mode: the light shades again (7C6EA2)', p.rows === 5 && p.accent1[7] === '#7C6EA2', p.accent1[7]);
  await ev(() => { window.__dark = true; ovlPickStyle('RAINBOW'); }); await sleep(50); p = await preview(); m = await more();
  check('   Rainbow in the list (Google\'s rule keeps it at 2021): no preview, no hint', p.empty && m.rootHint === '', JSON.stringify([p.empty, m.rootHint]));
  await ev(() => { ovlPickStyle('SPRITZ'); }); await sleep(50); p = await preview();
  check('   Spritz in the list: its 2025 preview (accent1 500 is 79747E, chroma 8)', p.rows === 5 && p.accent1[7] === '#79747E', p.accent1[7]);
  await ev(() => { ovlPickSpec('2021'); }); await sleep(50); p = await preview();
  check('   at 2021 a list style writes nothing: no preview', p.empty);
  // B11: the source line for a grey seed
  await ev(() => { ovlPickSpec('2025'); ovlPickStyle('TONAL_SPOT'); ovlPickColor('808080'); }); await sleep(80); p = await preview();
  const blue25 = await ev(() => '#' + MonetPlan.palettes('TONAL_SPOT', '1B6EF3', { spec: '2025', dark: true }).accent1.shades[7]);
  check('B11. a grey colour above (808080) with Tonal Spot at 2025: the line says Google blue and why, and the rows are Google blue\'s 2025 ones', p.source === 'From Google blue: #808080 is too grey for this style' && p.accent1[7] === blue25 && blue25 === '#68759A', JSON.stringify([p.source, p.accent1[7], blue25]));
  await ev(() => { ovlPickSpec('2026'); ovlPickRootStyle('CMF'); }); await sleep(50); p = await preview();
  check('   the same for CMF at 2026', p.source === 'From Google blue: #808080 is too grey for this style' && p.accent1[7] === '#1D6FF4', JSON.stringify([p.source, p.accent1[7]]));
  await ev(() => { ovlPickRootStyle('FIDELITY'); }); await sleep(50); p = await preview();
  check('   Fidelity keeps a grey seed, so its line names the colour as before', p.source === 'From the color above (#808080)', p.source);
  await ev(() => { ovlPickRootStyle('OFF'); ovlSetSource('home_wallpaper'); }); await sleep(80); p = await preview();
  check('   no colour readable at all: PR 3\'s Google blue line, not the grey one; the rows are still Google blue\'s 2025 Tonal Spot ones', p.source === 'From Google blue: no wallpaper color could be read' && p.rows === 5 && p.accent1[7] === blue25, JSON.stringify([p.source, p.accent1[7]]));
  await ev(() => { ovlPickColor('6750A4'); }); await sleep(50);

  // B12: the hint under Apply for each new combination (nothing written yet)
  const HINTS = {
    '2026,CMF,TONAL_SPOT,false': 'Also writes the CMF colors with root, in place of the style above.',
    '2026,CMF,TONAL_SPOT,true': 'Also writes the CMF colors and pure black with root, in place of the style above.',
    '2025,,TONAL_SPOT,false': 'Also writes this style\'s 2025 colors with root, in place of Android\'s.',
    '2025,,TONAL_SPOT,true': 'Also writes this style\'s 2025 colors and pure black with root, in place of Android\'s.',
    '2026,,VIBRANT,false': 'Also writes this style\'s 2025 colors with root, in place of Android\'s.',
    '2025,,EXPRESSIVE,false': 'Also writes this style\'s 2025 colors with root, in place of Android\'s.',
    '2025,,RAINBOW,false': '', '2025,,FRUIT_SALAD,true': 'Also writes the pure black overlay with root.',
    '2026,FIDELITY,TONAL_SPOT,false': 'Also writes the Fidelity colors with root, in place of the style above.',
    '2025,MONOCHROMATIC,VIBRANT,true': 'Also writes the Monochrome colors and pure black with root, in place of the style above.',
    '2021,,TONAL_SPOT,false': ''
  };
  const hints = {};
  for (const key of Object.keys(HINTS)) {
    const [sp, st, list, black] = key.split(',');
    await ev(([sp, st, list, black]) => { kvSet('ovl_spec', sp); kvSet('ovl_root_style', st); kvSet('ovl_black', black === 'true'); ovlStyle = list; ovlRender(); }, [sp, st, list, black]);
    hints[key] = (await more()).rootHint;
  }
  check('B12. the hint under Apply: one whole sentence per combination (CMF; a list style at 2025, also from 2026; Rainbow and Fruit Salad kept at 2021; a More style wins over the list)', JSON.stringify(hints) === JSON.stringify(HINTS), JSON.stringify(hints));

  // ---- (c) Apply ----
  // C1: Tonal Spot at 2025, More styles Off, Pure black off, the phone in dark mode
  await ev(() => { kvSet('ovl_spec', '2025'); kvSet('ovl_root_style', ''); kvSet('ovl_black', false); kvSet('ovl_root_applied', null); ovlPickStyle('TONAL_SPOT'); ovlPickColor('6750A4'); window.__dark = true; ovlRender(); }); await reset();
  await apply(); k = await calls(); let plan = k.fab[0] || {}, r = byRes(plan);
  check('C1. Apply with Tonal Spot at 2025: themeApply first with the list style (TONAL_SPOT), then overlayFabricate with 165 entries, no resource twice, the four lookups first', k.apply.length === 1 && JSON.stringify(k.apply[0]) === '{"source":"preset","hex":"6750A4","style":"TONAL_SPOT"}' && k.fab.length === 1 && plan.action === 'fabricate' && plan.entries.length === 165 && new Set(plan.entries.map(e => e.resource)).size === 165 && JSON.stringify(plan.entries.slice(0, 4).map(e => e.resource)) === JSON.stringify(LEAD), JSON.stringify([k.apply, plan.action, (plan.entries || []).length]));
  check('   the entries are MonetPlan.entries(TONAL_SPOT, 6750A4, { spec 2025, dark }): system_primary_light is the 2025 value 0xFF655789 (Android\'s own is 0xFF65558F), accent1_500 the dark shade 7B7098', (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true })) === 'same' && r['android:color/system_primary_light'] === '0xFF655789' && r['android:color/system_accent1_500'] === '0xFF7B7098', await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true }));
  check('   the record carries the list style and the spec (TONAL_SPOT, 2025); the toast and the note name the theme overlay', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025"}' && (await ev(() => { const a = ovlRootApplied(); return a.style === 'TONAL_SPOT' && a.spec === '2025'; })) && (await toasts()).includes('Theme overlay written') && (await more()).cardNote === 'The theme overlay is written on this phone.', JSON.stringify([await rec(), await toasts()]));
  // C2: Undo
  await reset(); await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(100); k = await calls();
  check('C2. Undo switches the overlay off first, then puts the theme back; the record is cleared', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && k.restore.length === 1 && k.restoreUndo[0] === true && (await kv('ovl_root_applied')) === null && (await toasts()).includes('Theme overlay removed'), JSON.stringify([k.fab, await toasts()]));
  // C3: with Pure black on
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {}; r = byRes(plan);
  check('C3. Pure black on as well: still 165 entries, no resource twice; surface_dark, neutral1_900, neutral2_900 and background_dark black; surface_dark the fourth, black; the rest 2025 Tonal Spot (primary_light 0xFF655789)', plan.entries && plan.entries.length === 165 && new Set(plan.entries.map(e => e.resource)).size === 165 && ['surface_dark', 'neutral1_900', 'neutral2_900', 'background_dark'].every(n => r['android:color/system_' + n] === '0xFF000000') && plan.entries[3].resource === LEAD[3] && plan.entries[3].argb === '0xFF000000' && r['android:color/system_primary_light'] === '0xFF655789' && r['android:color/system_surface_container_dark'] === '0xFF181818', JSON.stringify([(plan.entries || []).length, r['android:color/system_surface_dark'], r['android:color/system_primary_light']]));
  check('   the record: black true, TONAL_SPOT, 2025', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"TONAL_SPOT","spec":"2025"}', await rec());
  // C4: Rainbow at 2025 with nothing else on, after that write
  await ev(() => { kvSet('ovl_black', false); ovlPickStyle('RAINBOW'); }); await sleep(50); m = await more();
  check('C4. Rainbow in the list at 2025, Pure black off, the overlay on the phone: the hint says Apply removes the theme overlay', m.rootHint === 'Also removes the theme overlay.', m.rootHint);
  await reset(); await apply(); k = await calls();
  check('   Apply writes RAINBOW into the setting and sends the disable (no fabricate); the record is cleared', k.apply.length === 1 && k.apply[0].style === 'RAINBOW' && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null && (await toasts()).includes('Theme overlay removed'), JSON.stringify([k.apply, k.fab]));
  await reset(); await apply(); k = await calls();
  check('   Apply again: the setting only, no overlay call at all', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));
  // C5: Fidelity at 2026 sends the same plan as at 2021
  await ev(() => { kvSet('ovl_spec', '2021'); ovlPickRootStyle('FIDELITY'); ovlPickStyle('TONAL_SPOT'); }); await reset(); await apply();
  const plan21 = (await calls()).fab[0] || {}; const rec21 = await rec();
  await ev(() => { ovlPickSpec('2026'); }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('C5. Fidelity at 2026 sends the plan it sends at 2021, entry for entry (MonetPlan.entries(FIDELITY, 6750A4), primary_light 0xFF513A8D)', plan.entries && plan.entries.length === 165 && JSON.stringify(plan.entries) === JSON.stringify(plan21.entries) && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan)['android:color/system_primary_light'] === '0xFF513A8D', await sameAs(plan, 'FIDELITY', '6750A4'));
  check('   both records are PR 3\'s (no spec field: computed at 2021), and read back as spec 2021', rec21 === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY"}' && (await rec()) === rec21 && (await ev(() => ovlRootApplied().spec)) === '2021', JSON.stringify([rec21, await rec()]));
  // C6: CMF at 2026
  await ev(() => { ovlPickRootStyle('CMF'); }); await sleep(50); m = await more();
  check('C6. CMF at 2026: the hint names CMF', m.rootHint === 'Also writes the CMF colors with root, in place of the style above.', m.rootHint);
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {}; r = byRes(plan);
  check('   Apply writes the list style (TONAL_SPOT, not CMF) into the setting, then the CMF plan: MonetPlan.entries(CMF, 6750A4, { spec 2026, dark }), primary_light 0xFF5B4497, error_light 0xFF9F3F47, surface_dark 0xFF0F0D16', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && plan.entries && plan.entries.length === 165 && (await sameAs(plan, 'CMF', '6750A4', { spec: '2026', dark: true })) === 'same' && r['android:color/system_primary_light'] === '0xFF5B4497' && r['android:color/system_error_light'] === '0xFF9F3F47' && r['android:color/system_surface_dark'] === '0xFF0F0D16' && JSON.stringify(plan.entries.slice(0, 4).map(e => e.resource)) === JSON.stringify(LEAD), JSON.stringify([k.apply, await sameAs(plan, 'CMF', '6750A4', { spec: '2026', dark: true })]));
  check('   the record: style CMF, spec 2026; the note says the theme overlay is written', (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"CMF","spec":"2026"}' && (await more()).cardNote === 'The theme overlay is written on this phone.', await rec());
  // C7: the 65 shades follow the dark mode of the moment Apply is tapped, not of the answer
  await ev(() => { ovlPickRootStyle('OFF'); ovlPickSpec('2025'); ovlPickStyle('TONAL_SPOT'); window.__dark = false; window.__delay = 400; }); await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { window.__dark = true; });
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {}); await settle(); await sleep(100);
  plan = (await calls()).fab[0] || {}; r = byRes(plan);
  check('C7. light mode when Apply is tapped, dark when the answer comes: the 65 shades are the light ones (accent1_500 7C6EA2), the plan is entries(TONAL_SPOT, 6750A4, { spec 2025, dark false })', r['android:color/system_accent1_500'] === '0xFF7C6EA2' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: false })) === 'same', JSON.stringify([r['android:color/system_accent1_500'], await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: false })]));
  await ev(() => { window.__dark = true; }); await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { window.__dark = false; });
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {}); await settle(); await sleep(100);
  plan = (await calls()).fab[0] || {}; r = byRes(plan);
  check('   and the other way round: dark at the tap gives the dark shades (7B7098); the roles are the same in both plans', r['android:color/system_accent1_500'] === '0xFF7B7098' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true })) === 'same', r['android:color/system_accent1_500']);
  await ev(() => { window.__dark = true; window.__delay = 20; });
  // C7b: the list style counted is the one Apply wrote, taken at the tap: another style picked in the list before the answer changes nothing
  await ev(() => { ovlPickStyle('TONAL_SPOT'); window.__delay = 400; }); await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { ovlPickStyle('RAINBOW'); });
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {}); await settle(); await sleep(100);
  k = await calls(); plan = k.fab[0] || {};
  check('C7b. Tonal Spot in the list when Apply is tapped, Rainbow picked before the answer: the setting gets TONAL_SPOT and the overlay Tonal Spot\'s 2025 plan (entries(TONAL_SPOT, 6750A4, { spec 2025, dark true })); the record says TONAL_SPOT and 2025', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && plan.action === 'fabricate' && (await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"TONAL_SPOT","spec":"2025"}', JSON.stringify([k.apply, k.fab.map(f => f.action), await sameAs(plan, 'TONAL_SPOT', '6750A4', { spec: '2025', dark: true }), await rec()]));
  await reset();
  await page.locator('#ovlApplyBtn').click(); await ev(() => { ovlPickStyle('TONAL_SPOT'); });
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {}); await settle(); await sleep(100);
  k = await calls();
  check('   the other way round: Rainbow at the tap, Tonal Spot picked before the answer: the setting gets RAINBOW and the overlay written before is switched off (a disable, no fabricate); the record is cleared', k.apply.length === 1 && k.apply[0].style === 'RAINBOW' && k.fab.length === 1 && k.fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null, JSON.stringify([k.apply, k.fab.map(f => f.action), await rec()]));
  await ev(() => { window.__delay = 20; });
  // C8: a grey seed at 2025 is Google blue's plan
  await ev(() => { ovlPickColor('808080'); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C8. the colour above 808080 with Tonal Spot at 2025: the setting gets 808080, the overlay Google blue\'s 2025 colours (entries of 1B6EF3)', (await calls()).apply[0].hex === '808080' && (await sameAs(plan, 'TONAL_SPOT', '1B6EF3', { spec: '2025', dark: true })) === 'same', await sameAs(plan, 'TONAL_SPOT', '1B6EF3', { spec: '2025', dark: true }));
  // C9: Vibrant and Spritz at 2026 are written at 2025
  await ev(() => { ovlPickColor('6750A4'); ovlPickSpec('2026'); ovlPickStyle('VIBRANT'); }); await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('C9. Vibrant in the list at 2026: written at 2025 (Google\'s rule), the record says VIBRANT and 2025', k.apply[0].style === 'VIBRANT' && (await sameAs(plan, 'VIBRANT', '6750A4', { spec: '2025', dark: true })) === 'same' && (await rec()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"VIBRANT","spec":"2025"}', JSON.stringify([await sameAs(plan, 'VIBRANT', '6750A4', { spec: '2025', dark: true }), await rec()]));
  // C10: records
  const reads = await ev(id => {
    const out = [];
    for (const a of [{ ids: [id], t: 1, style: 'FIDELITY' }, { ids: [id], t: 1, style: 'SPRITZ', spec: '2025' }, { ids: [id], t: 1, style: 'CMF', spec: '2026' }, { ids: [id], t: 1, style: 'BOGUS', spec: '2025' }, { ids: [id], t: 1, style: 'TONAL_SPOT', spec: '1999' }, { ids: [id], t: 1, black: true }]) { kvSet('ovl_root_applied', a); const r = ovlRootApplied(); out.push(r.style + '/' + r.spec + '/' + ovlRootWords(r.style).written); }
    kvSet('ovl_root_applied', null); return out;
  }, AAM);
  check('C10. records: one without spec reads as 2021; a list style or CMF is a style the overlay carries (the theme overlay words); a style that is not one, or a spec that is not one, counts for nothing', JSON.stringify(reads) === JSON.stringify(['FIDELITY/2021/Theme overlay written', 'SPRITZ/2025/Theme overlay written', 'CMF/2026/Theme overlay written', '/2021/Pure black overlay written', 'TONAL_SPOT/2021/Theme overlay written', '/2021/Pure black overlay written']), JSON.stringify(reads));
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now() }); }, AAM);
  // C11: losing root: nothing but the setting
  await ev(() => { ovlPickSpec('2025'); ovlPickStyle('TONAL_SPOT'); }); await setRoot(false); await reset(); await apply(); k = await calls(); s = await spec();
  check('C11. Super User gone: Color spec locked again (the choice stays stored), Apply writes the setting only', s.locked && s.lock === 'Needs Super User' && (await kv('ovl_spec')) === '2025' && k.apply.length === 1 && k.fab.length === 0, JSON.stringify([s.lock, k.fab]));
  await setRoot(true); s = await spec();
  check('   root back: the radio group with 2025 checked and its note', !s.locked && s.radios[1].checked === 'true' && s.specNote === SPEC_NOTE);

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
