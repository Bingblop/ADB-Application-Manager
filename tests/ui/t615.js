// The one Theme screen of the RRO/Monet tab (v7.22.0, ColorBlendr parity): every control in one flow (Colors in use, Color with the Secondary and
// tertiary accents, the Style grid with all ten styles, Color spec, Contrast, Intensity, the three switches, How this works collapsed, Back up and
// restore) and ONE sticky action bar at the bottom with Apply theme and Reset (no line of text under them since v7.22.1). Without uid 0 every root-only control is a locked row
// (rootLockedRow: greyed, the Locked button, the lock glyph in the title, the reason): at the shell (ADB TCP, uid 2000) "Needs Super User (uid 0) to
// write Material You colors", with Shizuku as the system user (uid 1000) "Shizuku runs as the system user (uid 1000); writing these colors needs
// uid 0"; the four root-only styles are greyed with the same reason and take no tap; with Root (uid 0) every row is live and the Root Features
// row "Monet theme — ColorBlendr palettes" is unlocked. A mode change relocks. A build without MonetPlan.cb says "Not available in this build".
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => { window.__sdk = 37; window.__wallpaper = ['4F8A5B', '888888']; window.__build = 'google/mock/mock:17/ZP1A.250905.001/1234567:user/release-keys'; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const modes = async () => { await ev(() => checkAllWorkingModes(false)); await sleep(150); };
  const UID0 = 'Needs Super User (uid 0) to write Material You colors', UID1000 = 'Shizuku runs as the system user (uid 1000); writing these colors needs uid 0';
  // every root-only row of the screen: where it sits, whether it is a locked row, its title (with the lock glyph) and its reason
  const rows = () => ev(() => ['ovlAccents', 'ovlSpecRow', 'ovlContrastRow', 'ovlAdjust', 'ovlSwitches'].flatMap(id => Array.from(document.getElementById(id).querySelectorAll('.rf-row')).map(r => ({
    box: id, locked: r.classList.contains('is-locked'), name: r.querySelector('.rf-name').childNodes[0].textContent.trim(),
    btn: (r.querySelector('.op-mode-btn') || {}).innerText || '', disabled: !!(r.querySelector('.op-mode-btn') || {}).disabled, why: (r.querySelector('.pm-lock span') || {}).innerText || '' }))));
  const styles = () => ev(() => Array.from(document.querySelectorAll('#ovlStyles .ovl-style')).map(s => ({ id: s.dataset.style, locked: s.classList.contains('is-locked'), disabled: s.disabled, name: s.querySelector('b').innerText, why: (s.querySelector('.ovl-lockwhy') || {}).innerText || '', active: s.classList.contains('active') })));
  const screen = () => ev(() => {
    const panel = document.getElementById('ovlThemePanel');
    const cards = Array.from(panel.querySelectorAll(':scope > .color-card')).map(c => { const h = c.querySelector('.color-card-title, summary'); return h ? h.innerText : c.querySelector('.rf-name').childNodes[0].textContent.trim(); });
    const bar = document.getElementById('ovlBar'), cs = getComputedStyle(bar);
    return { cards, applies: panel.querySelectorAll('#ovlApplyBtn, button[onclick="ovlApply()"]').length, resets: panel.querySelectorAll('button[onclick="ovlReset()"]').length,
      barLast: panel.lastElementChild === bar, sticky: cs.position === 'sticky', barHolds: ['ovlApplyBtn', 'ovlResetBtn'].every(id => bar.contains(document.getElementById(id))), barText: bar.innerText.replace(/\s+/g, ' ').trim(), noHint: !document.getElementById('ovlApplyHint'),
      apply: document.getElementById('ovlApplyBtn').innerText, reset: document.getElementById('ovlResetBtn').innerText, how: document.getElementById('ovlHowCard').open,
      note: document.getElementById('ovlRootNote').innerText };
  });

  // 1) the shell (ADB TCP, uid 2000): one screen, locked rows with the uid 0 reason
  await ev(() => switchView('overlays')); await sleep(400);
  let s = await screen();
  check('1. one flow: Colors in use, Color, Style, Color spec, Contrast, Intensity, Shades and text, How this works, Back up and restore', s.cards.join('|') === 'Colors in use\nRefresh|Color|Style|🔒 Color spec|🔒 Contrast|🔒 Intensity|Shades and text|How this works|Back up and restore', JSON.stringify(s.cards));
  check('   one sticky bar at the very end holds the only Apply theme and Reset, with no line under them; How this works is collapsed', s.applies === 1 && s.resets === 1 && s.barLast && s.sticky && s.barHolds && s.noHint && s.barText === 'Apply theme Reset' && s.apply === 'Apply theme' && s.reset === 'Reset' && s.how === false, JSON.stringify(s));
  let r = await rows();
  check('   the seven root-only rows are locked rows: accents, spec, contrast, intensity, Accurate shades, Pure black, Tint text color', r.length === 7 && r.every(x => x.locked && x.btn === 'Locked' && x.disabled) && r.map(x => x.name).join('|') === '🔒 Secondary and tertiary accents|🔒 Color spec|🔒 Contrast|🔒 Intensity|🔒 Accurate shades|🔒 Pure black|🔒 Tint text color', JSON.stringify(r.map(x => x.name)));
  check('   each with the reason for uid 2000', r.every(x => x.why === UID0), JSON.stringify(r.map(x => x.why)));
  check('   the rows sit in their cards: the accents in Color, then one card each', r.map(x => x.box).join() === 'ovlAccents,ovlSpecRow,ovlContrastRow,ovlAdjust,ovlSwitches,ovlSwitches,ovlSwitches', JSON.stringify(r.map(x => x.box)));
  check('   the note under the switches says who can write them', s.note === 'Shizuku and ADB cannot write these colors; Super User (uid 0) can.', s.note);
  let st = await styles();
  check('   the Style grid holds all ten styles, Android\'s six open', st.length === 10 && st.slice(0, 6).every(x => !x.locked && !x.disabled) && st.map(x => x.id).join() === 'TONAL_SPOT,VIBRANT,EXPRESSIVE,FRUIT_SALAD,RAINBOW,SPRITZ,FIDELITY,CONTENT,MONOCHROMATIC,CMF', JSON.stringify(st.map(x => x.id)));
  check('   Fidelity, Content, Monochrome and CMF greyed, with the lock glyph and the uid 0 reason', st.slice(6).every(x => x.locked && x.disabled && x.name.startsWith('🔒 ') && x.why === UID0), JSON.stringify(st.slice(6)));
  await page.locator('#ovlStyles .ovl-style[data-style="FIDELITY"]').click({ force: true }); await sleep(100);
  check('   a tap on a greyed style changes nothing', await ev(() => ovlStyle) === 'TONAL_SPOT' && (await styles()).find(x => x.id === 'TONAL_SPOT').active);
  check('   nothing of the locked rows is interactive', await ev(() => ['ovlAccents', 'ovlSpecRow', 'ovlContrastRow', 'ovlAdjust', 'ovlSwitches'].every(id => Array.from(document.getElementById(id).querySelectorAll('button, input')).every(x => x.disabled))));
  check('   no root call went out', (await ev(() => window.__ovlCalls.fab.length)) === 0);
  await page.screenshot({ path: 'ovl_one_screen_shell.png', fullPage: true });

  // 2) Shizuku as the system user (uid 1000): privileged for the rest of the tab, but these rows name the uid
  await ev(() => { window.__shizuku1000 = true; }); await modes();
  r = await rows(); st = await styles();
  check('2. Shizuku at uid 1000: the rows stay locked, the reason names the system user', r.length === 7 && r.every(x => x.locked && x.why === UID1000) && st.slice(6).every(x => x.locked && x.why === UID1000), JSON.stringify([r[0], st[6]]));
  check('   the theme half itself is usable there (Apply is enabled)', await ev(() => !document.getElementById('ovlApplyBtn').disabled && ovlUsable()));
  await page.screenshot({ path: 'ovl_one_screen_shizuku.png', fullPage: true });

  // 3) Root (uid 0): everything live
  await ev(() => { window.__shizuku1000 = false; window.__root = true; }); await modes();
  r = await rows(); st = await styles(); s = await screen();
  check('3. with Root: no locked row left; the accents, spec, contrast and intensity rows and the three switches are live', r.length === 7 && r.every(x => !x.locked && x.btn !== 'Locked') && r.map(x => x.name).join('|') === 'Secondary and tertiary accents|Color spec|Contrast|Intensity|Accurate shades|Pure black|Tint text color', JSON.stringify(r.map(x => x.name)));
  check('   the ten styles are open, except CMF, which needs spec 2026', st.slice(0, 9).every(x => !x.locked) && st[9].locked && st[9].why === 'Needs Color spec 2026', JSON.stringify(st[9]));
  const live = await ev(() => ({
    spec: Array.from(document.querySelectorAll('#ovlSpecSeg button')).map(x => x.innerText + (x.classList.contains('active') ? '*' : '')).join(),
    con: Array.from(document.querySelectorAll('#ovlContrastSeg button')).map(x => x.innerText + (x.classList.contains('active') ? '*' : '')).join(),
    sliders: Array.from(document.querySelectorAll('#ovlAdjust input[type=range]')).map(x => x.dataset.adj + '=' + x.value).join(),
    sw: ['ovlAccurateSw', 'ovlBlackSw', 'ovlTintSw'].map(id => id + ':' + document.getElementById(id).getAttribute('aria-checked')).join(),
    acc: Array.from(document.querySelectorAll('#ovlAccents .picker-bubble-btn')).map(x => x.innerText.trim()).join('|'),
    preview: (document.getElementById('ovlPreviewLine') || {}).innerText || '', chips: document.querySelectorAll('#ovlPalette .ovl-chip').length }));
  check('   defaults as ColorBlendr: 2021, Standard, 100% x3, Accurate shades on, Pure black off, Tint text color on, accents from the style', live.spec === '2021*,2025,2026' && live.con === 'Standard*,Medium,High' && live.sliders === 'accentSaturation=100,backgroundSaturation=100,backgroundLightness=100' && live.sw === 'ovlAccurateSw:true,ovlBlackSw:false,ovlTintSw:true' && live.acc === 'From the style|From the style', JSON.stringify(live));
  check('   the preview shows what Apply writes: the wallpaper colour, Tonal Spot, 55 chips', /^Preview of what Apply theme writes: #[0-9A-F]{6} from your wallpaper, Tonal Spot, (dark|light)$/.test(live.preview) && live.chips === 55, JSON.stringify([live.preview, live.chips]));
  check('   the note under the switches is empty while nothing is written', s.note === '', s.note);
  // the Root Features row
  await ev(() => switchView('rootfeatures')); await sleep(300);
  const rf = await ev(() => { const btn = document.getElementById('rfMonetBtn'); const row = btn && btn.closest('.rf-row'); return row ? { name: row.querySelector('.rf-name').childNodes[0].textContent.trim(), sub: row.querySelector('.rf-name span').innerText, btn: btn.innerText, locked: row.classList.contains('is-locked') } : null; });
  check('   the Root Features row is "Monet theme — ColorBlendr palettes", unlocked, Open tab', rf && rf.name === 'Monet theme — ColorBlendr palettes' && rf.sub === 'Color spec, contrast, intensity, accents, Pure black, Tint text color. Written with Super User.' && rf.btn === 'Open tab' && !rf.locked, JSON.stringify(rf));
  await page.locator('#rfMonetBtn').click(); await sleep(400);
  check('   Open tab opens the Theme half of the RRO tab', await ev(() => currentViewName() === 'overlays' && ovlSub === 'theme'));
  await page.screenshot({ path: 'ovl_one_screen_root.png', fullPage: true });

  // 4) back to the shell: relocked, and the same row is a locked row on the Root Features tab
  await ev(() => { window.__root = false; }); await modes();
  r = await rows();
  check('4. the mode changes back: every row locked again', r.length === 7 && r.every(x => x.locked && x.why === UID0), JSON.stringify(r.map(x => x.locked)));
  await ev(() => switchView('rootfeatures')); await sleep(300);
  check('   the Root Features row is locked there', await ev(() => { const rows = Array.from(document.querySelectorAll('#rootFeatures .rf-row, .rf-row')); const row = rows.find(x => /Monet theme — ColorBlendr palettes/.test(x.innerText)); return !!row && row.classList.contains('is-locked'); }));

  // 5) a build whose page has no colour maths (MonetPlan.cb missing) with Root: locked, "Not available in this build"
  await ev(() => { window.__root = true; window.__cbKeep = MonetPlan.cb; MonetPlan.cb = undefined; switchView('overlays'); }); await modes();
  r = await rows(); st = await styles();
  check('5. without MonetPlan.cb the rows are locked with "Not available in this build", and so are the four styles', r.length === 7 && r.every(x => x.locked && x.why === 'Not available in this build') && st.slice(6).every(x => x.locked && x.why === 'Not available in this build'), JSON.stringify([r[0].why, st[6].why]));
  check('   the note says so too', (await screen()).note.startsWith('Not available in this build'), (await screen()).note);
  await ev(() => { MonetPlan.cb = window.__cbKeep; ovlRender(); }); await sleep(100);
  check('   with the maths back the rows are live again', (await rows()).every(x => !x.locked));

  // 6) an older app without overlayFabricate: "Update the app"
  await ev(() => { window.__fabKeep = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; ovlRender(); }); await sleep(100);
  r = await rows();
  check('6. an app without the bridge: locked with "Update the app", and the note says to update', r.every(x => x.locked && x.why === 'Update the app') && (await screen()).note === 'This version of the app cannot write color overlays. Update it, then come back.', JSON.stringify([r[0].why, (await screen()).note]));
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabKeep; ovlRender(); });

  // 7) Android 11: the root cards are hidden with the rest of Material You
  await ev(() => { window.__sdk = 30; ovlReadPalette(); ovlRender(); }); await sleep(100);
  check('7. on Android 11 the Color spec, Contrast, Intensity and Shades and text cards are hidden', await ev(() => ['ovlSpecCard', 'ovlContrastCard', 'ovlIntensityCard', 'ovlSwitchCard'].every(id => document.getElementById(id).style.display === 'none') && document.getElementById('ovlAccents').innerHTML === ''));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
