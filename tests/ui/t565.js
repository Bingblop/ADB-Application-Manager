// More styles on the Overlays tab's Theme panel (PR 3 of the ColorBlendr series): the styles Android has no setting for (Fidelity, Content,
// Monochrome), computed by assets/monet.js and written as root over Android's own colours. (a) the maths in the page context: for two seeds
// and the three styles the plan has 165 entries, no resource twice, android:color/system_* and 0xAARRGGBB, a selection of values pinned
// (each palette's 500 and 900, primary/surface/outline light and dark, control_highlight with its alpha, the primary key colour; the pins
// are monet.js's values, 94 of them computed bit for bit by @material/material-color-utilities 0.4.0 too, the 14 that differ follow AOSP
// 14 as the monet.js header says: Fidelity/Content primary_light, outline tone 49 in light, the key colour of a chroma-0 palette), and
// Monochrome's rows are greys; (b) the card: a locked row without Super User (no bridge call), "Needs Android 14" with Super User on
// Android 13, "Update the app" without the maths or without overlayFabricate (an older APK), the radio group on Android 14 (kv
// ovl_root_style, persists, survives a redraw), the preview (five rows of 13 swatches, a tap copies) and its source line for each source
// (the colour above, the wallpaper colour the setting carries, the wallpaper colours the bridge reports with the chroma rule, nothing
// readable: Google blue; a custom colour the setting carries is not the wallpaper's); the hint sentence for each combination; (c) Apply:
// with a root style the bridge gets the 165-entry plan after the theme setting, the four lookups first; with Pure black on as well still
// 165 with surface_dark and neutral1_900 black; Pure black alone keeps PR 2's plan (11 on Android 14, 2 on 13, a remembered style counting
// for nothing there); from the source Wallpaper with a colour in the setting, the plan the preview showed although the theme write drops
// that colour (the seed is taken when Apply is tapped); Off with Pure black off after a write sends a disable; Undo and Revert disable; the
// record (kv ovl_root_applied with black and style, written before the write) and PR 2's key read as a fallback; the toasts, the note and
// the failure sheets name what the overlay carries.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (label === 'no page errors') { extra = undefined; ok = true; } if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
// [style, seed, { resource suffix: value }]: six hex digits for a palette shade ('0xFF' in front of it), 0xAARRGGBB for a role
const PINS = [
  ['FIDELITY', '6750A4', { accent1_500: '7F68BE', accent1_900: '22005D', accent2_500: '7B7195', accent2_900: '1F1635', accent3_500: '90721C', accent3_900: '241A00', neutral1_500: '78757C', neutral1_900: '1D1B20', neutral2_500: '797481', neutral2_900: '1D1A24', primary_light: '0xFF513A8D', surface_light: '0xFFFDF7FF', outline_light: '0xFF77727F', palette_key_color_primary_light: '0xFF8069BF', control_highlight_light: '0x1F000000', primary_dark: '0xFFCFBCFF', surface_dark: '0xFF141218', outline_dark: '0xFF948E9C', palette_key_color_primary_dark: '0xFF8069BF', control_highlight_dark: '0x33FFFFFF' }],
  ['CONTENT', '6750A4', { accent1_500: '7F68BE', accent1_900: '22005D', accent2_500: '7B7195', accent2_900: '1F1635', accent3_500: 'AE598D', accent3_900: '3C002B', neutral1_500: '78757C', neutral1_900: '1D1B20', neutral2_500: '797481', neutral2_900: '1D1A24', primary_light: '0xFF513A8D', surface_light: '0xFFFDF7FF', outline_light: '0xFF77727F', palette_key_color_primary_light: '0xFF8069BF', control_highlight_light: '0x1F000000', primary_dark: '0xFFCFBCFF', surface_dark: '0xFF141218', outline_dark: '0xFF948E9C', palette_key_color_primary_dark: '0xFF8069BF', control_highlight_dark: '0x33FFFFFF' }],
  ['MONOCHROMATIC', '6750A4', { accent1_500: '767676', accent1_900: '1B1B1B', accent2_500: '767676', accent2_900: '1B1B1B', accent3_500: '767676', accent3_900: '1B1B1B', neutral1_500: '767676', neutral1_900: '1B1B1B', neutral2_500: '767676', neutral2_900: '1B1B1B', primary_light: '0xFF000000', surface_light: '0xFFF9F9F9', outline_light: '0xFF747474', palette_key_color_primary_light: '0xFF070707', control_highlight_light: '0x1F000000', primary_dark: '0xFFFFFFFF', surface_dark: '0xFF131313', outline_dark: '0xFF919191', palette_key_color_primary_dark: '0xFF070707', control_highlight_dark: '0x33FFFFFF' }],
  ['FIDELITY', 'B5651D', { accent1_500: 'B1621A', accent1_900: '301400', accent2_500: '986D4D', accent2_900: '2F1400', accent3_500: '007FAC', accent3_900: '001E2C', neutral1_500: '80746C', neutral1_900: '221A14', neutral2_500: '867265', neutral2_900: '26190F', primary_light: '0xFF7E3F00', surface_light: '0xFFFFF8F5', outline_light: '0xFF847164', palette_key_color_primary_light: '0xFFB2631B', control_highlight_light: '0x1F000000', primary_dark: '0xFFFFB781', surface_dark: '0xFF19120D', outline_dark: '0xFFA28D7F', palette_key_color_primary_dark: '0xFFB2631B', control_highlight_dark: '0x33FFFFFF' }],
  ['CONTENT', 'B5651D', { accent1_500: 'B1621A', accent1_900: '301400', accent2_500: '986D4D', accent2_900: '2F1400', accent3_500: '757C0C', accent3_900: '1B1D00', neutral1_500: '80746C', neutral1_900: '221A14', neutral2_500: '867265', neutral2_900: '26190F', primary_light: '0xFF7E3F00', surface_light: '0xFFFFF8F5', outline_light: '0xFF847164', palette_key_color_primary_light: '0xFFB2631B', control_highlight_light: '0x1F000000', primary_dark: '0xFFFFB781', surface_dark: '0xFF19120D', outline_dark: '0xFFA28D7F', palette_key_color_primary_dark: '0xFFB2631B', control_highlight_dark: '0x33FFFFFF' }],
  ['MONOCHROMATIC', 'B5651D', { accent1_500: '767676', accent1_900: '1B1B1B', accent2_500: '767676', accent2_900: '1B1B1B', accent3_500: '767676', accent3_900: '1B1B1B', neutral1_500: '767676', neutral1_900: '1B1B1B', neutral2_500: '767676', neutral2_900: '1B1B1B', primary_light: '0xFF000000', surface_light: '0xFFF9F9F9', outline_light: '0xFF747474', palette_key_color_primary_light: '0xFF070707', control_highlight_light: '0x1F000000', primary_dark: '0xFFFFFFFF', surface_dark: '0xFF131313', outline_dark: '0xFF919191', palette_key_color_primary_dark: '0xFF070707', control_highlight_dark: '0x33FFFFFF' }]
];
const LEAD = ['android:color/system_accent1_600', 'android:color/system_neutral1_500', 'android:color/system_primary_light', 'android:color/system_surface_dark'];
const MORE_SUB = 'Fidelity, Content and Monochrome: colors Android has no setting for, computed by this app.';
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !m.text().includes('Failed to load resource: net::ERR_FILE_NOT_FOUND')) errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const BUILD = await ev(() => window.__build);          // T-049: the build the mock reports (Build.FINGERPRINT), which every new record keeps
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const undoShown = () => ev(() => document.getElementById('sdbSnackUndo').style.display !== 'none');
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; window.__toasts.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  // T-069: a record with a style also keeps the source colour, the mode and the contrast (seed, dark, contrast) for the Undo; t564 checks them,
  // and the record strings compared here leave them out
  const recJ = async () => JSON.stringify(await kv('ovl_root_applied')).replace(/"t":\d+/, '"t":T').replace(/,"seed":"[0-9A-F]{6}","dark":(?:true|false),"contrast":-?[0-9.e+-]+/, '');
  const toasts = () => ev(() => window.__toasts.slice());
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  const setSdk = async n => { await ev(v => { window.__sdk = v; ovlReadPalette(); }, n); await sleep(150); await ev(() => ovlRender()); };
  const card = () => ev(() => {
    const box = document.getElementById('ovlRootCards'), row = box.querySelector('.rf-row'), sw = document.getElementById('ovlBlackSw');
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(row && row.classList.contains('is-locked')), sw: sw ? { on: sw.classList.contains('on') } : null, note: document.getElementById('ovlRootNote').innerText };
  });
  // the More styles container: the locked row or the radio group, the preview under it, the note under the card and the hint under Apply
  const more = () => ev(() => {
    const box = document.getElementById('ovlRootMore'); if (!box) return null;
    const row = box.querySelector('.rf-row'), lockBtn = box.querySelector('.op-mode-btn.locked'), lock = box.querySelector('.pm-lock'), group = document.getElementById('ovlRootStyles');
    const radios = group ? Array.from(group.querySelectorAll('.ovl-style')).map(r => ({ style: r.dataset.style, role: r.getAttribute('role'), checked: r.getAttribute('aria-checked'), active: r.classList.contains('active'), name: r.querySelector('b').innerText, desc: r.querySelector('.ovl-styletxt > span').innerText })) : null;
    const pv = document.getElementById('ovlRootPreview'), rows = pv ? Array.from(pv.querySelectorAll('.ovl-pal')) : [];
    const preview = pv ? { empty: pv.innerHTML === '', rows: rows.length, names: rows.map(r => r.querySelector('.ovl-pal-name').innerText), chips: rows.map(r => r.querySelectorAll('.ovl-chip').length), title: (pv.querySelector('.ovl-preview-title') || { innerText: '' }).innerText, source: (document.getElementById('ovlRootSource') || { innerText: '' }).innerText, accent1: rows[0] ? Array.from(rows[0].querySelectorAll('.ovl-chip')).map(c => c.dataset.hex) : [] } : null;
    return { rows: box.querySelectorAll('.rf-row').length, locked: !!(row && row.classList.contains('is-locked')), name: row ? row.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null, lock: lock ? lock.innerText : '',
      group: group ? { role: group.getAttribute('role'), label: group.getAttribute('aria-label') } : null, radios, preview, note: document.getElementById('ovlRootNote').innerText, rootHint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText };
  });
  const sheet = () => ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async ms => { await page.locator('#ovlApplyBtn').click(); if (ms) await sleep(ms); else { await settle(); await sleep(100); } };
  const undo = async () => { await page.locator('#sdbSnackUndo').click(); await settle(); await sleep(100); };
  const AAM = 'com.android.shell:aam_monet';
  const listed = id => ev(i => { const o = window.__ovl.find(x => x.id === i); return o ? o.state : null; }, id);
  const byRes = plan => Object.fromEntries((plan.entries || []).map(e => [e.resource, e.argb]));
  // the plan's entries against MonetPlan.entries(style, seed) in the page: the same resources, each with the same value (the order is the plan's own)
  const sameAs = (plan, style, seed) => ev(([entries, style, seed]) => { const want = MonetPlan.entries(style, seed); if (want.length !== entries.length) return 'count ' + entries.length + ' vs ' + want.length; const have = Object.fromEntries(entries.map(e => [e.resource, e.argb])); const off = want.filter(e => have[e.resource] !== e.argb).map(e => e.resource + ' ' + have[e.resource] + ' != ' + e.argb); return off.length ? off.slice(0, 5).join('; ') : 'same'; }, [plan.entries, style, seed]);
  await ev(() => { window.__toasts = []; const real = showToast; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; });

  // ---- (a) the maths, in the page context ----
  for (const [style, seed, pins] of PINS) {
    const r = await ev(([style, seed, pins]) => {
      const es = MonetPlan.entries(style, seed), names = es.map(e => e.resource), have = {};
      es.forEach(e => { have[e.resource.replace('android:color/system_', '')] = e.argb; });
      const bad = Object.keys(pins).filter(k => have[k] !== (pins[k].length === 6 ? '0xFF' + pins[k] : pins[k])).map(k => k + ' ' + have[k] + ' != ' + pins[k]);
      return { n: es.length, uniq: new Set(names).size, res: names.every(n => /^android:color\/system_[a-z0-9_]+$/.test(n)), val: es.every(e => /^0x[0-9A-F]{8}$/.test(e.argb)), bad,
        pal: names.slice(0, 65).every(n => /^android:color\/system_(accent[123]|neutral[12])_(0|10|50|100|200|300|400|500|600|700|800|900|1000)$/.test(n)), dark: names.slice(65, 109).every(n => n.endsWith('_dark')), light: names.slice(109, 153).every(n => n.endsWith('_light')), fixed: names.slice(153).length === 12 && names.slice(153).every(n => /_fixed/.test(n)) };
    }, [style, seed, pins]);
    check('A. ' + style + ' ' + seed + ': 165 entries, no resource twice, every one android:color/system_* with a 0xAARRGGBB value', r.n === 165 && r.uniq === 165 && r.res && r.val, JSON.stringify([r.n, r.uniq, r.res, r.val]));
    check('   the 65 palette entries first, then the 44 dark roles, the 44 light roles and the 12 fixed colours', r.pal && r.dark && r.light && r.fixed, JSON.stringify([r.pal, r.dark, r.light, r.fixed]));
    check('   ' + Object.keys(pins).length + ' pinned values: each palette\'s 500 and 900, primary, surface and outline light and dark, control_highlight with its alpha, the primary key colour', r.bad.length === 0, r.bad.join('; '));
  }
  const mono = await ev(seeds => seeds.map(s => { const p = MonetPlan.palettes('MONOCHROMATIC', s), rows = ['accent1', 'accent2', 'accent3', 'neutral1', 'neutral2']; return { chroma: rows.map(r => p[r].chroma), grey: rows.every(r => p[r].shades.length === 13 && p[r].shades.every(h => h.slice(0, 2) === h.slice(2, 4) && h.slice(2, 4) === h.slice(4, 6))), ends: [p.accent1.shades[0], p.accent1.shades[12]] }; }), ['6750A4', 'B5651D']);
  check('   Monochrome: the five rows have chroma 0 and all 65 shades are greys (R = G = B), white at 0 and black at 1000, for both seeds', mono.every(m => m.chroma.every(c => c === 0) && m.grey && m.ends[0] === 'FFFFFF' && m.ends[1] === '000000'), JSON.stringify(mono));
  check('   the seed rule: a grey seed (808080) stays the seed for Fidelity and Content, becomes Google blue for a stock style; Fidelity keeps 6750A4', await ev(() => MonetPlan.seed('FIDELITY', '808080') === '808080' && MonetPlan.seed('CONTENT', '808080') === '808080' && MonetPlan.seed('TONAL_SPOT', '808080') === '1B6EF3' && MonetPlan.seed('FIDELITY', '6750A4') === '6750A4' && MonetPlan.GOOGLE_BLUE === '1B6EF3' && MonetPlan.MIN_CHROMA === 5));

  // ---- (b) the card ----
  await ev(() => switchView('overlays')); await sleep(300);
  let m = await more(), c = await card();
  check('B1. without Super User: More styles is a locked row of the Root Features kind under the Pure black row (Locked, Needs Super User), no radios, no preview', m && m.locked && m.rows === 1 && m.name === 'More styles ' + MORE_SUB && m.lockBtn && m.lockBtn.text === 'Locked' && m.lockBtn.disabled && m.lock === 'Needs Super User' && !m.radios && !m.preview && m.group === null, JSON.stringify(m));
  check('   it has its own container after the Pure black row and PR 4\'s Color spec row, inside the Root-only theme controls card; PR 2\'s row is still the only one in its box', await ev(() => { const box = document.getElementById('ovlRootMore'), prev = box.previousElementSibling; return !!box.closest('#ovlRootCard') && !!prev && prev.id === 'ovlRootSpec' && !!prev.previousElementSibling && prev.previousElementSibling.id === 'ovlRootCards' && box.nextElementSibling.id === 'ovlRootAdjust' && box.nextElementSibling.nextElementSibling.id === 'ovlRootAccents' && box.nextElementSibling.nextElementSibling.nextElementSibling.id === 'ovlRootAccurate' && box.nextElementSibling.nextElementSibling.nextElementSibling.nextElementSibling.id === 'ovlRootNote'; }) && c.rows === 1 && c.locked);
  check('   nothing went to the bridge for it; the hint under Apply says nothing', (await calls()).fab.length === 0 && (await calls()).op.length === 0 && m.rootHint === '');
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); m = await more();
  check('   a style remembered from a rooted session: still locked, counts for nothing (no hint, ovlRootStyleOn() empty)', m.locked && m.rootHint === '' && (await ev(() => ovlRootStyleOn())) === '' && (await kv('ovl_root_style')) === 'FIDELITY', JSON.stringify([m.lock, m.rootHint]));
  await ev(() => { delete window.AndroidBridge.__kv.ovl_root_style; });

  // B2: Super User on Android 13: Pure black is live, More styles is not
  await ev(() => { window.__sdk = 33; ovlReadPalette(); }); await sleep(150);
  await setRoot(true); m = await more(); c = await card();
  check('B2. with Super User on Android 13: the Pure black switch is live, More styles stays a locked row whose caption says Needs Android 14', c.sw && !c.locked && m.locked && m.lock === 'Needs Android 14' && m.lockBtn && m.lockBtn.disabled && !m.radios && !m.preview, JSON.stringify([c, m.lock, m.lockBtn]));
  check('   the gate: the root controls are live (ovlRootLive), the styles are not (ovlRootStyleLive), the Android level is 33', await ev(() => ovlRootLive() && !ovlRootStyleLive() && ovlSdk() === 33));
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_black', true); ovlRender(); }); m = await more();
  check('   a remembered style counts for nothing there: the hint names pure black only, the row stays locked', m.rootHint === 'Also writes the pure black overlay with root.' && m.locked && m.lock === 'Needs Android 14', JSON.stringify([m.rootHint, m.lock]));
  await ev(() => { kvSet('ovl_black', false); delete window.AndroidBridge.__kv.ovl_root_style; });

  // B3: Android 14 without the colour maths
  await ev(() => { window.__mp = window.MonetPlan; window.MonetPlan = null; });
  await setSdk(34); m = await more();
  check('B3. Android 14 with Super User but without the colour maths (monet.js not loaded): a locked row saying Update the app', m.locked && m.lock === 'Update the app' && !m.radios && (await ev(() => ovlRootLive() && !ovlRootStyleLive() && ovlSdk() === 34)), JSON.stringify([m.lock, m.radios]));
  await ev(() => { window.MonetPlan = window.__mp; ovlRender(); });
  // an APK too old to write overlays (no overlayFabricate), with the maths there and Super User on Android 14: both rows locked, Update the app
  await ev(() => { window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; ovlRender(); }); m = await more(); c = await card();
  check('   without overlayFabricate (an older APK), Super User on Android 14: More styles is a locked row saying Update the app, as the Pure black row is, and the line under the card says why', m.locked && m.lock === 'Update the app' && m.lockBtn && m.lockBtn.disabled && !m.radios && !m.preview && c.locked && !c.sw && m.note === 'This version of the app cannot write color overlays. Update it, then come back.' && m.rootHint === '', JSON.stringify([m.lock, m.note, m.rootHint, c]));
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; ovlRender(); });

  // B4: live on Android 14: the radio group
  m = await more();
  check('B4. Android 14 with Super User: a radio group (role radiogroup, labelled More styles) of four radios in order, Off checked, nothing stored, no preview', !m.locked && m.rows === 1 && m.name === 'More styles ' + MORE_SUB && !m.lockBtn && m.lock === '' && m.group && m.group.role === 'radiogroup' && m.group.label === 'More styles' && m.radios && m.radios.map(r => r.style).join(',') === 'OFF,FIDELITY,CONTENT,MONOCHROMATIC' && m.radios.every(r => r.role === 'radio') && m.radios.map(r => r.checked).join(',') === 'true,false,false,false' && m.radios[0].active && (await kv('ovl_root_style')) === null && m.preview && m.preview.empty, JSON.stringify(m));
  check('   the names and the one-line descriptions', m.radios.map(r => r.name).join('|') === 'Off|Fidelity|Content|Monochrome' && m.radios.map(r => r.desc).join('|') === 'Apply writes only what the style list above says.|Accents keep your exact color; backgrounds take a faint tint of it.|Like Fidelity, with a third accent chosen to sit beside your color.|No color at all: black, white and greys.', JSON.stringify(m.radios));
  check('   the look of the style list above Apply: the same .ovl-style buttons with a radio dot', await ev(() => { const a = document.querySelector('#ovlRootStyles .ovl-style'), b = document.querySelector('#ovlStyles .ovl-style') || document.querySelector('.ovl-styles:not(#ovlRootStyles) .ovl-style'); return !!a && !!b && !!a.querySelector('.ovl-radio') && !!b.querySelector('.ovl-radio') && getComputedStyle(a).borderRadius === getComputedStyle(b).borderRadius; }));
  await page.locator('#ovlRootStyles .ovl-style[data-style="FIDELITY"]').click(); await sleep(80); m = await more();
  check('   tapping Fidelity stores it (kv ovl_root_style) and checks it; no bridge call; the preview appears', (await kv('ovl_root_style')) === 'FIDELITY' && m.radios.map(r => r.checked).join(',') === 'false,true,false,false' && m.radios[1].active && !m.radios[0].active && (await calls()).fab.length === 0 && m.preview.rows === 5, JSON.stringify([await kv('ovl_root_style'), m.radios.map(r => r.checked)]));
  await ev(() => ovlRender()); m = await more();
  check('   a redraw reads the choice from the store', m.radios[1].checked === 'true' && m.radios[1].active && m.preview.rows === 5);
  await ev(() => switchView('home')); await sleep(150); await ev(() => switchView('overlays')); await sleep(300); m = await more();
  check('   so does coming back to the tab', m.radios[1].checked === 'true' && m.preview.rows === 5 && (await kv('ovl_root_style')) === 'FIDELITY');
  await page.locator('#ovlRootStyles .ovl-style[data-style="CONTENT"]').click(); await sleep(80);
  check('   Content: stored and checked', (await kv('ovl_root_style')) === 'CONTENT' && (await more()).radios.map(r => r.checked).join(',') === 'false,false,true,false');
  await page.locator('#ovlRootStyles .ovl-style[data-style="OFF"]').click(); await sleep(80); m = await more();
  check('   Off: stored as the empty string, checked, the preview goes away', (await kv('ovl_root_style')) === '' && m.radios.map(r => r.checked).join(',') === 'true,false,false,false' && m.preview.empty, JSON.stringify([await kv('ovl_root_style'), m.preview]));
  await ev(() => ovlPickRootStyle('BOGUS')); await sleep(50);
  check('   a name that is not a style is ignored', (await kv('ovl_root_style')) === '' && (await more()).radios[0].checked === 'true');

  // B5: the preview and its source line, with Fidelity chosen
  await ev(() => ovlPickRootStyle('FIDELITY')); await ev(() => ovlSetSource('home_wallpaper')); await sleep(80); m = await more();
  const blue500 = await ev(() => '#' + MonetPlan.palettes('FIDELITY', '1B6EF3').accent1.shades[7]);
  check('B5. source Wallpaper, the setting carries no colour and the phone reports no wallpaper colours: five rows of 13 swatches from Google blue, the line says so', m.preview.rows === 5 && m.preview.chips.join(',') === '13,13,13,13,13' && m.preview.names.join('|') === 'Accent 1|Accent 2|Accent 3|Neutral 1|Neutral 2' && m.preview.title === 'Preview' && m.preview.source === 'From Google blue: no wallpaper color could be read' && m.preview.accent1[7] === blue500 && (await ev(() => ovlTheme && ovlTheme.color === '' && ovlSource === 'home_wallpaper')), JSON.stringify([m.preview, blue500]));
  check('   the swatches are the "Colors in use" card\'s own markup (.ovl-pal, .ovl-pal-name, .ovl-pal-row, .ovl-chip with data-hex and data-name)', await ev(() => { const pv = document.getElementById('ovlRootPreview'), chip = pv.querySelector('.ovl-pal .ovl-pal-row .ovl-chip'); return !!chip && /^#[0-9A-F]{6}$/.test(chip.dataset.hex) && chip.dataset.name === 'Accent 1 0' && chip.style.background !== ''; }));
  await page.locator('#ovlRootPreview .ovl-chip').first().click(); await sleep(80);
  check('   a tap on a swatch copies it, as on the card above', /^Copied #[0-9A-F]{6} \(Accent 1 0\)$/.test(await toast()), await toast());
  await ev(() => { window.__wallpaper = ['808080', 'B5651D', '6750A4']; ovlReadPalette(); }); await sleep(150); m = await more();
  check('   the phone reports wallpaper colours: the first with a chroma of at least 5 is the seed (the grey 808080 is passed over), the line names it', m.preview.source === 'From your wallpaper (#B5651D)' && m.preview.accent1[7] === '#B1621A' && m.preview.rows === 5, JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__db.secure.theme_customization_overlay_packages = '{"android.theme.customization.system_palette":"6750a4","android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT","_applied_timestamp":1700000000000}'; ovlReadTheme(); }); await sleep(150); m = await more();
  check('   the setting carries the wallpaper\'s colour: that is the seed, before the bridge\'s list', m.preview.source === 'From your wallpaper (#6750A4)' && m.preview.accent1[7] === '#7F68BE' && (await ev(() => ovlSource === 'home_wallpaper')), JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__db.secure.theme_customization_overlay_packages = '{"android.theme.customization.system_palette":"6750a4","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"TONAL_SPOT","_applied_timestamp":1700000000000}'; ovlReadTheme(); }); await sleep(150); m = await more();
  check('   the setting carries a custom colour (source preset, 6750A4) while the page is on Wallpaper: not the wallpaper\'s, so the bridge\'s list decides (B5651D)', m.preview.source === 'From your wallpaper (#B5651D)' && m.preview.accent1[7] === '#B1621A' && (await ev(() => ovlSource === 'home_wallpaper' && ovlTheme.source === 'preset' && ovlTheme.color === '6750A4')), JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__wallpaper = ['808080']; ovlReadPalette(); }); await sleep(150); m = await more();
  check('   the same with only a grey wallpaper colour: Google blue, not the custom colour', m.preview.source === 'From Google blue: no wallpaper color could be read' && m.preview.accent1[7] === blue500, JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__wallpaper = ['808080', 'B5651D', '6750A4']; ovlReadPalette(); }); await sleep(150);
  await ev(() => ovlPickColor('B5651D')); await sleep(80); m = await more();
  check('   source A color: the colour above is the seed, the line says so', m.preview.source === 'From the color above (#B5651D)' && m.preview.accent1[7] === '#B1621A', JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => ovlPickColor('#6750a4')); await sleep(80); m = await more();
  check('   the preview follows the colour as it is changed', m.preview.source === 'From the color above (#6750A4)' && m.preview.accent1[7] === '#7F68BE' && m.preview.accent1.length === 13, JSON.stringify([m.preview.source, m.preview.accent1]));
  await ev(() => { window.__wallpaper = ['808080']; window.__db.secure.theme_customization_overlay_packages = '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT"}'; ovlReadTheme(); ovlReadPalette(); ovlSetSource('home_wallpaper'); }); await sleep(200); m = await more();
  check('   only a grey wallpaper colour and no colour in the setting: nothing readable, Google blue again', m.preview.source === 'From Google blue: no wallpaper color could be read' && m.preview.accent1[7] === blue500, JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__wallpaper = null; ovlReadPalette(); }); await sleep(150);
  await page.locator('#ovlRootStyles .ovl-style[data-style="MONOCHROMATIC"]').click(); await sleep(80); m = await more();
  check('   Monochrome: five rows of greys', m.preview.rows === 5 && m.preview.accent1[7] === '#767676' && m.preview.accent1.every(h => h.slice(1, 3) === h.slice(3, 5) && h.slice(3, 5) === h.slice(5, 7)), JSON.stringify(m.preview.accent1));

  // B6: the hint under Apply for each combination (nothing written yet, so Off with Pure black off says nothing)
  const HINTS = {
    'OFF,false': '', 'OFF,true': 'Also writes the pure black overlay with root.',
    'FIDELITY,false': 'Also writes the Fidelity colors with root, in place of the style above.', 'FIDELITY,true': 'Also writes the Fidelity colors and pure black with root, in place of the style above.',
    'CONTENT,false': 'Also writes the Content colors with root, in place of the style above.', 'CONTENT,true': 'Also writes the Content colors and pure black with root, in place of the style above.',
    'MONOCHROMATIC,false': 'Also writes the Monochrome colors with root, in place of the style above.', 'MONOCHROMATIC,true': 'Also writes the Monochrome colors and pure black with root, in place of the style above.'
  };
  const hints = {};
  for (const key of Object.keys(HINTS)) {
    const [style, black] = key.split(',');
    await ev(([s, b]) => { kvSet('ovl_root_style', s === 'OFF' ? '' : s); kvSet('ovl_black', b === 'true'); ovlRender(); }, [style, black]);
    hints[key] = (await more()).rootHint;
  }
  check('B6. the hint under Apply, one whole sentence per combination of style and Pure black', JSON.stringify(hints) === JSON.stringify(HINTS), JSON.stringify(hints));
  check('   the hint is its own element after the usual sentence', await ev(() => { const h = document.getElementById('ovlApplyHint').innerText.replace(/\s+/g, ' ').trim(), r = document.getElementById('ovlApplyHintRoot').innerText; return r !== '' && h.endsWith(' ' + r); }));

  // ---- (c) Apply ----
  // C1: Fidelity, the colour above 6750A4, Pure black off
  await ev(() => { ovlPickColor('6750A4'); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_black', false); ovlRender(); ovlList = null; window.__delay = 400; }); await reset();
  await apply(100);
  let k = await calls();
  check('C1. Apply with Fidelity: the theme setting goes first (the style list\'s own style and the colour above), the overlay waits, the button is busy', k.apply.length === 1 && k.fab.length === 0 && JSON.stringify(k.apply[0]) === '{"source":"preset","hex":"6750A4","style":"TONAL_SPOT"}' && (await ev(() => document.getElementById('ovlApplyBtn').disabled)), JSON.stringify([k.apply, k.fab.length]));
  check('   the record is written before the overlay is, as pending, with what the write carries (style FIDELITY, no pure black)', await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).then(() => true, () => false) && (await ev(() => { const a = kvGet('ovl_root_applied', null); return !!a && a.pending === true && a.style === 'FIDELITY' && a.black === false && JSON.stringify(a.ids) === JSON.stringify(['com.android.shell:aam_monet']); })), JSON.stringify(await kv('ovl_root_applied')));
  await settle(); await sleep(100); await ev(() => { window.__delay = 20; });
  k = await calls(); let plan = k.fab[0] || {};
  check('   then overlayFabricate gets the plan: action fabricate, 165 entries, no resource twice', k.fab.length === 1 && plan.action === 'fabricate' && Array.isArray(plan.entries) && plan.entries.length === 165 && new Set(plan.entries.map(e => e.resource)).size === 165, JSON.stringify([plan.action, (plan.entries || []).length]));
  check('   the first four are the ones the phone is asked to look up: accent1_600, neutral1_500, primary_light, surface_dark', JSON.stringify(plan.entries.slice(0, 4).map(e => e.resource)) === JSON.stringify(LEAD), JSON.stringify(plan.entries.slice(0, 4)));
  check('   the entries are MonetPlan.entries(FIDELITY, 6750A4), resource for resource (the pinned primary_light among them)', (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan)['android:color/system_primary_light'] === '0xFF513A8D', await sameAs(plan, 'FIDELITY', '6750A4'));
  check('   the answer: toast Theme overlay written, the record (id, black false, style FIDELITY, not pending), the note under the card, the Undo bar', (await toasts()).includes('Theme overlay written') && (await recJ()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","build":"' + BUILD + '"}' && (await more()).note === 'The theme overlay is written on this phone.' && (await snack()) === 'Theme applied' && (await undoShown()) && (await listed(AAM)) === 1, JSON.stringify([await toasts(), await kv('ovl_root_applied'), (await more()).note]));
  check('   PR 2\'s key is not written', (await kv('ovl_black_applied')) === null);
  // C2: Undo
  await reset(); await undo(); k = await calls();
  check('C2. Undo switches the overlay off first (a disable with the remembered id), then puts the theme back; the toast named the theme overlay', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && k.restore.length === 1 && k.restoreUndo[0] === true && (await toasts()).includes('Theme overlay removed'), JSON.stringify([k.fab, await toasts()]));
  check('   the record is cleared, the overlay listed off, the note gone', (await kv('ovl_root_applied')) === null && (await listed(AAM)) === 0 && (await more()).note === '' && (await snack()) === '');

  // C3: Fidelity with Pure black on
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); m = await more();
  check('C3. Pure black on as well: the hint names both', m.rootHint === 'Also writes the Fidelity colors and pure black with root, in place of the style above.', m.rootHint);
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {}; let r = byRes(plan);
  const greys = await ev(() => [4, 6, 8, 11, 14, 18].map(t => '0xFF' + psHex(0, 0, t).slice(1)));
  check('   still 165 entries, no resource twice; surface_dark and neutral1_900 are black (so are neutral2_900, background_dark and surface_dim_dark)', plan.action === 'fabricate' && plan.entries.length === 165 && new Set(plan.entries.map(e => e.resource)).size === 165 && ['system_surface_dark', 'system_neutral1_900', 'system_neutral2_900', 'system_background_dark', 'system_surface_dim_dark'].every(n => r['android:color/' + n] === '0xFF000000'), JSON.stringify([plan.entries.length, ['system_surface_dark', 'system_neutral1_900'].map(n => r['android:color/' + n])]));
  check('   the five containers and surface_bright take PR 2\'s greys; the rest of the plan is Fidelity\'s (primary_light pinned)', ['system_surface_container_lowest_dark', 'system_surface_container_low_dark', 'system_surface_container_dark', 'system_surface_container_high_dark', 'system_surface_container_highest_dark', 'system_surface_bright_dark'].every((n, i) => r['android:color/' + n] === greys[i]) && r['android:color/system_primary_light'] === '0xFF513A8D' && r['android:color/system_accent1_500'] === '0xFF7F68BE', JSON.stringify(greys));
  check('   surface_dark is the fourth entry, black: that is what the lookup must see', plan.entries[3].resource === LEAD[3] && plan.entries[3].argb === '0xFF000000' && JSON.stringify(plan.entries.slice(0, 4).map(e => e.resource)) === JSON.stringify(LEAD));
  check('   the record says what the overlay carries: black true, style FIDELITY', (await recJ()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"FIDELITY","build":"' + BUILD + '"}' && (await toasts()).includes('Theme overlay written'), JSON.stringify(await kv('ovl_root_applied')));
  // C4: Revert
  await reset(); dialogs.length = 0;
  await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100); k = await calls();
  check('C4. Revert asks first and names the theme overlay; confirmed, it resets the theme and switches the overlay off', dialogs.length === 1 && /\n\nThe theme overlay is switched off too\.$/.test(dialogs[0]) && k.restore.length === 1 && k.restore[0] === '' && k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && (await kv('ovl_root_applied')) === null && (await listed(AAM)) === 0 && (await toasts()).includes('Theme overlay removed'), JSON.stringify([dialogs[0], k.fab, await toasts()]));

  // C5: Pure black alone keeps PR 2's plan
  await ev(() => { ovlPickRootStyle('OFF'); kvSet('ovl_black', true); ovlRender(); }); m = await more();
  check('C5. Off with Pure black on: PR 2\'s hint', m.rootHint === 'Also writes the pure black overlay with root.', m.rootHint);
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('   Apply sends PR 2\'s plan: 11 entries on Android 14, the two _900 first; the record and the toast say pure black', plan.action === 'fabricate' && plan.entries.length === 11 && plan.entries[0].resource === 'android:color/system_neutral1_900' && plan.entries[1].resource === 'android:color/system_neutral2_900' && (await recJ()) === '{"ids":["' + AAM + '"],"t":T,"black":true,"style":"","build":"' + BUILD + '"}' && (await toasts()).includes('Pure black overlay written') && (await more()).note === 'The pure black overlay is written on this phone.', JSON.stringify([plan.entries.length, await kv('ovl_root_applied'), await toasts()]));
  await reset(); await apply(); k = await calls();
  check('   Apply again with nothing changed rewrites it the same (one overlay, every Apply rewrites all of it)', k.fab.length === 1 && k.fab[0].action === 'fabricate' && k.fab[0].entries.length === 11 && JSON.stringify((await kv('ovl_root_applied')).ids) === '["' + AAM + '"]');
  await ev(() => { kvSet('ovl_root_applied', null); window.__ovl.filter(o => o.id.startsWith('com.android.shell:aam_monet')).forEach(o => { o.state = 0; }); });
  // Android 13 with a style remembered: two entries, two overlays
  await setSdk(33); await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); }); m = await more();
  check('   Android 13 with Fidelity remembered: the row is locked (Needs Android 14) and the hint names pure black only', m.locked && m.lock === 'Needs Android 14' && m.rootHint === 'Also writes the pure black overlay with root.', JSON.stringify([m.lock, m.rootHint]));
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('   Apply sends PR 2\'s two entries; the phone answers with one overlay per colour; the record says pure black, no style', plan.action === 'fabricate' && plan.entries.length === 2 && plan.entries.every(e => e.argb === '0xFF000000') && (await recJ()) === '{"ids":["' + AAM + '_neutral1_900","' + AAM + '_neutral2_900"],"t":T,"black":true,"style":"","profile":"P12","build":"' + BUILD + '"}', JSON.stringify([plan.entries, await kv('ovl_root_applied')]));
  await ev(() => { kvSet('ovl_black', false); ovlRender(); }); m = await more();
  check('   Pure black off again: the hint says Apply removes the pure black overlay', m.rootHint === 'Also removes the pure black overlay.', m.rootHint);
  await reset(); await apply(); k = await calls();
  check('   Apply sends the disable with both ids; the record is cleared, the toast says pure black', k.fab.length === 1 && k.fab[0].action === 'disable' && k.fab[0].ids.length === 2 && (await kv('ovl_root_applied')) === null && (await toasts()).includes('Pure black overlay removed') && (await more()).note === '', JSON.stringify([k.fab, await toasts()]));
  await setSdk(34);

  // C6: a style written, then Off: Apply removes the theme overlay
  await ev(() => { kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_black', false); ovlRender(); }); await reset(); await apply();
  check('C6. Fidelity written again on Android 14', (await calls()).fab[0].entries.length === 165 && (await kv('ovl_root_applied')).style === 'FIDELITY');
  await page.locator('#ovlRootStyles .ovl-style[data-style="OFF"]').click(); await sleep(80); m = await more();
  check('   Off with Pure black off while the overlay is on the phone: the hint says Apply removes the theme overlay; the note still says it is written', m.rootHint === 'Also removes the theme overlay.' && m.note === 'The theme overlay is written on this phone.' && m.preview.empty, JSON.stringify([m.rootHint, m.note]));
  await reset(); await apply(); k = await calls();
  check('   Apply sends the disable; the record is cleared, the toast names the theme overlay, the note is gone', k.apply.length === 1 && k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && (await kv('ovl_root_applied')) === null && (await toasts()).includes('Theme overlay removed') && (await more()).note === '' && (await more()).rootHint === '', JSON.stringify([k.fab, await toasts()]));
  await reset(); await apply(); k = await calls();
  check('   and nothing root is sent after that (nothing on, nothing on the phone)', k.apply.length === 1 && k.fab.length === 0, JSON.stringify(k.fab));

  // C7: Monochrome and Content through Apply
  await ev(() => { ovlPickColor('B5651D'); ovlPickRootStyle('MONOCHROMATIC'); }); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('C7. Monochrome with B5651D: the plan is MonetPlan.entries(MONOCHROMATIC, B5651D), every palette entry a grey, the record says MONOCHROMATIC', plan.entries && plan.entries.length === 165 && (await sameAs(plan, 'MONOCHROMATIC', 'B5651D')) === 'same' && plan.entries.slice(0, 65).every(e => e.argb.slice(4, 6) === e.argb.slice(6, 8) && e.argb.slice(6, 8) === e.argb.slice(8, 10)) && (await kv('ovl_root_applied')).style === 'MONOCHROMATIC' && (await more()).note === 'The theme overlay is written on this phone.', JSON.stringify([await sameAs(plan, 'MONOCHROMATIC', 'B5651D'), await kv('ovl_root_applied')]));
  await ev(() => ovlPickRootStyle('CONTENT')); await reset(); await apply(); plan = (await calls()).fab[0] || {};
  check('   Content with B5651D: the plan is MonetPlan.entries(CONTENT, B5651D) (accent3_500 pinned), the first four as always', plan.entries && (await sameAs(plan, 'CONTENT', 'B5651D')) === 'same' && byRes(plan)['android:color/system_accent3_500'] === '0xFF757C0C' && JSON.stringify(plan.entries.slice(0, 4).map(e => e.resource)) === JSON.stringify(LEAD) && (await kv('ovl_root_applied')).style === 'CONTENT', await sameAs(plan, 'CONTENT', 'B5651D'));
  await ev(() => ovlPickRootStyle('OFF')); await reset(); await apply();
  check('   Off: removed again', (await calls()).fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null);
  // Apply from the source Wallpaper while the setting carries the wallpaper's colour: the write drops that colour, the overlay keeps the preview's
  await ev(() => { window.__wallpaper = ['B5651D']; window.__db.secure.theme_customization_overlay_packages = '{"android.theme.customization.system_palette":"6750a4","android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT","_applied_timestamp":1700000000000}'; ovlReadTheme(); ovlReadPalette(); ovlSetSource('home_wallpaper'); ovlPickRootStyle('FIDELITY'); }); await sleep(200); m = await more();
  const shown = m.preview.accent1[7];
  check('   source Wallpaper, the setting carrying 6750A4 for the wallpaper, the phone reporting B5651D: the preview is 6750A4\'s', m.preview.source === 'From your wallpaper (#6750A4)' && shown === '#7F68BE', JSON.stringify([m.preview.source, shown]));
  await reset(); await apply(); k = await calls(); plan = k.fab[0] || {};
  check('   Apply writes the setting with the wallpaper as its source and no colour, then the plan the preview showed: MonetPlan.entries(FIDELITY, 6750A4), its accent1_500 the eighth Accent 1 swatch', k.apply.length === 1 && k.apply[0].source === 'home_wallpaper' && k.apply[0].hex === '' && plan.action === 'fabricate' && (await sameAs(plan, 'FIDELITY', '6750A4')) === 'same' && byRes(plan)['android:color/system_accent1_500'] === '0xFF' + shown.slice(1), JSON.stringify([k.apply, await sameAs(plan, 'FIDELITY', '6750A4'), byRes(plan)['android:color/system_accent1_500']]));
  m = await more();
  check('   afterwards the setting carries no colour: the preview shows what the next Apply would write, from the wallpaper colour the phone reports', m.preview.source === 'From your wallpaper (#B5651D)' && m.preview.accent1[7] === '#B1621A' && (await ev(() => ovlTheme.color === '' && ovlTheme.source === 'home_wallpaper')), JSON.stringify([m.preview.source, m.preview.accent1[7]]));
  await ev(() => { window.__wallpaper = null; ovlReadPalette(); ovlPickColor('B5651D'); ovlPickRootStyle('OFF'); }); await sleep(150); await reset(); await apply();
  check('   Off with the colour above again: removed', (await calls()).fab[0].action === 'disable' && (await kv('ovl_root_applied')) === null);

  // C8: PR 2's record (kv ovl_black_applied) is read as a fallback
  await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t: Date.now() - 5000 }); window.__ovl.find(o => o.id === 'com.android.shell:aam_monet').state = 1; ovlRender(); }); m = await more();
  const fb = await ev(() => { const a = ovlRootApplied(); return a && { ids: a.ids, pending: a.pending, black: a.black, style: a.style }; });
  check('C8. a phone that ran PR 2\'s build: its record is read (pure black, no style), the note and the hint say pure black', JSON.stringify(fb) === '{"ids":["' + AAM + '"],"pending":false,"black":true,"style":""}' && m.note === 'The pure black overlay is written on this phone.' && m.rootHint === 'Also removes the pure black overlay.', JSON.stringify([fb, m.note, m.rootHint]));
  await reset(); await apply(); k = await calls();
  check('   Apply with nothing on sends the disable with its id; both keys are cleared afterwards', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && (await kv('ovl_root_applied')) === null && (await kv('ovl_black_applied')) === null && (await toasts()).includes('Pure black overlay removed'), JSON.stringify([k.fab, await kv('ovl_black_applied')]));
  await ev(() => { kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t: Date.now() - 5000 }); window.__ovl.find(o => o.id === 'com.android.shell:aam_monet').state = 1; kvSet('ovl_root_style', 'FIDELITY'); ovlRender(); window.__delay = 400; }); await reset();
  await apply(100);
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  const mid = await ev(() => ({ applied: kvGet('ovl_root_applied', null), old: kvGet('ovl_black_applied', null) }));
  await settle(); await sleep(100); await ev(() => { window.__delay = 20; });
  check('   a new write with the old record there: the new key takes the pending record before the write goes out and the old key is cleared with it; settled by the answer', !!mid.applied && mid.applied.pending === true && mid.applied.style === 'FIDELITY' && mid.applied.black === false && mid.old === null && (await kv('ovl_root_applied')).style === 'FIDELITY' && (await kv('ovl_root_applied')).pending === undefined && (await kv('ovl_black_applied')) === null, JSON.stringify([mid, await kv('ovl_root_applied')]));
  const stale = await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t: Date.now(), style: 'FIDELITY' }); const a = ovlRootApplied(); kvSet('ovl_black_applied', null); return a && a.style; });
  check('   a style field in the old key counts for nothing (that build wrote pure black only)', stale === '', JSON.stringify(stale));

  // C9: failures name what the overlay carries
  await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_root_style', 'FIDELITY'); kvSet('ovl_black', false); window.__fabDeny = 'Android did not take the colours'; ovlRender(); }); await reset(); await apply();
  let s = await sheet();
  check('C9. a refused write with Fidelity: listed on, so the sheet and the toast say the theme overlay is on but Android shows its own colors, with the lookups; it stays remembered (as PR 2) with what it carries', s.shown && s.title === 'The theme overlay is on, but Android shows its own colors' && /did not take the colours/.test(s.body) && /system_accent1_600 → #ff1b1b1b/.test(s.body) && (await toasts()).includes('The theme overlay is on, but Android shows its own colors') && !(await toasts()).includes('The theme overlay was not written') && (await recJ()) === '{"ids":["' + AAM + '"],"t":T,"black":false,"style":"FIDELITY","build":"' + BUILD + '"}' && (await more()).note === 'The theme overlay is written on this phone.', JSON.stringify([s.title, await toasts(), await kv('ovl_root_applied')]));
  await closeAll(); await ev(() => { window.__fabDeny = null; });
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: true, style: 'CONTENT' }); window.__toasts.length = 0; ovlRootDone({ req: 1, op: 'disable', ok: false, state: 1, ids: [id], error: 'Android did not switch it off (' + id + ')', list: [['android.theme.customization.accent_color', 'android', 1], [id, 'android', 1]] }, 'disable', null, null); }, AAM);
  s = await sheet();
  check('   a disable that fails with Content and pure black on the phone (still listed on): the sheet and the toast name the theme overlay; the record is kept', s.shown && s.title === 'Could not remove the theme overlay' && (await toasts()).includes('The theme overlay was not removed') && JSON.stringify(((await kv('ovl_root_applied')) || {}).ids) === '["' + AAM + '"]', JSON.stringify([s.title, await toasts()]));
  await closeAll();
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: true, style: 'CONTENT' }); window.__toasts.length = 0; ovlRootDone({ req: 1, op: 'disable', ok: false, state: -2, ids: [id], error: 'That overlay is not listed any more (' + id + ')', list: [['android.theme.customization.accent_color', 'android', 1]] }, 'disable', null, null); }, AAM);
  s = await sheet();
  check('   the same disable when Android does not list the overlay: nothing of it is on, so the theme overlay counts as removed (toast, no sheet) and the record is cleared', !s.shown && (await toasts()).includes('Theme overlay removed') && !(await toasts()).includes('The theme overlay was not removed') && (await kv('ovl_root_applied')) === null, JSON.stringify([s.shown, await toasts()]));
  await closeAll();
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), pending: true, black: false, style: 'FIDELITY' }); ovlRender(); }, AAM); m = await more();
  check('   a pending record with a style: the note says the theme overlay may be written', m.note === 'The theme overlay may be written on this phone.' && m.rootHint === 'Also writes the Fidelity colors with root, in place of the style above.', JSON.stringify([m.note, m.rootHint]));
  await ev(() => { kvSet('ovl_root_applied', null); kvSet('ovl_root_style', ''); ovlRender(); });

  // C10: losing root locks the row again and clears the hint; the choice is kept in the store
  await ev(() => { kvSet('ovl_root_style', 'CONTENT'); ovlRender(); }); await setRoot(false); m = await more();
  check('C10. the working mode drops root: More styles is a locked row again, the choice stays stored but writes nothing (no hint)', m.locked && m.lock === 'Needs Super User' && !m.radios && m.rootHint === '' && (await kv('ovl_root_style')) === 'CONTENT' && (await ev(() => ovlRootStyleOn())) === '', JSON.stringify([m.lock, m.rootHint]));
  await setRoot(true); m = await more();
  check('   root back: the radio group with Content checked', !m.locked && m.radios && m.radios[2].checked === 'true' && m.rootHint === 'Also writes the Content colors with root, in place of the style above.');

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
