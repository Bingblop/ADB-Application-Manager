// The notes of the one Theme screen (v7.22.0; PR 13's One UI note and the Android 11 case carried over): on One UI (getSystemPalette reports
// oneUi) the note under the switches on which apps follow these colours shows, locked or not, and not on any other phone; below Android 12 the
// root cards (Color spec, Contrast, Intensity, Shades and text) and the accents are hidden with the rest of Material You, the hint says
// Android 12 is needed, and they come back on Android 14; the note on what is written names PR 2's pure black overlay for a record without a
// style and the theme overlay otherwise, "may be" while pending; the How this works card is collapsed and opens with its text and buttons.
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
  await page.addInitScript(() => { window.__sdk = 34; window.__oneUi = '6.1'; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const noteOf = id => ev(i => { const e = document.getElementById(i); return e.style.display === 'none' ? '' : e.innerText; }, id);
  const ONE_UI = 'On One UI these colors reach Google apps and other apps that use Android\'s Monet colors. Samsung\'s own apps may keep Samsung\'s palette, and changing the Color palette in Samsung\'s settings can replace them; apply again if it does.';
  const AAM = 'com.android.shell:aam_monet';

  await ev(() => switchView('overlays')); await sleep(400);
  check('1. One UI, the shell: the locked rows, and the One UI note under them', (await noteOf('ovlRootOneUi')) === ONE_UI && (await ev(() => document.querySelectorAll('#ovlSwitches .rf-row.is-locked').length === 3)), await noteOf('ovlRootOneUi'));
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(150);
  check('   with Root too', (await noteOf('ovlRootOneUi')) === ONE_UI && (await ev(() => ovlRootLive())));
  await ev(() => { window.__oneUi = '?'; ovlReadPalette(); ovlRender(); }); await sleep(60);
  check('   a Samsung phone whose One UI version could not be read (\'?\') gets it too', (await noteOf('ovlRootOneUi')) === ONE_UI);
  await ev(() => { window.__oneUi = ''; ovlReadPalette(); ovlRender(); }); await sleep(60);
  check('   any other phone: no note', (await noteOf('ovlRootOneUi')) === '');

  // 2) the note on what is written
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), style: 'VIBRANT', pending: true }); ovlRender(); }, AAM);
  check('2. a pending record: the theme overlay may be written', (await noteOf('ovlRootNote')) === 'The theme overlay may be written on this phone.');
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), style: 'VIBRANT' }); ovlRender(); }, AAM);
  check('   a settled one: is written', (await noteOf('ovlRootNote')) === 'The theme overlay is written on this phone.');
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), black: true }); ovlRender(); }, AAM);
  check('   a record without a style (PR 2\'s pure black alone): the pure black overlay', (await noteOf('ovlRootNote')) === 'The pure black overlay is written on this phone.');
  await ev(() => { window.__root = false; checkAllWorkingModes(false); }); await sleep(150);
  check('   locked with it on the phone: the reason and the line', (await noteOf('ovlRootNote')) === 'Shizuku and ADB cannot write these colors; Super User (uid 0) can.\nThe pure black overlay is written on this phone.');
  await ev(id => { kvSet('ovl_root_applied', { ids: ['com.other:thing'], t: Date.now() }); ovlRender(); }, AAM);
  check('   a record naming an overlay that is not this app\'s counts for nothing', (await noteOf('ovlRootNote')) === 'Shizuku and ADB cannot write these colors; Super User (uid 0) can.' && (await ev(() => ovlRootApplied() === null)));
  await ev(() => { kvSet('ovl_root_applied', null); window.__root = true; checkAllWorkingModes(false); }); await sleep(150);

  // 3) Android 11
  await ev(() => { window.__sdk = 28; ovlReadPalette(); ovlRender(); }); await sleep(60);
  const hidden = await ev(() => ({ cards: ['ovlSpecCard', 'ovlContrastCard', 'ovlIntensityCard', 'ovlSwitchCard'].map(id => getComputedStyle(document.getElementById(id)).display), acc: document.getElementById('ovlAccents').innerHTML, note: document.getElementById('ovlRootNote').innerText, hint: document.getElementById('ovlApplyHint').innerText, styles: document.querySelectorAll('#ovlStyles .ovl-style').length, apply: document.getElementById('ovlApplyBtn').disabled }));
  check('3. below Android 12 the four root cards are hidden and the accents empty; the hint says Android 12 is needed and Apply is disabled', hidden.cards.every(d => d === 'none') && hidden.acc === '' && hidden.note === '' && /needs Android 12/.test(hidden.hint) && !/ColorBlendr/.test(hidden.hint) && hidden.apply, JSON.stringify(hidden));
  await ev(() => { window.__sdk = 34; ovlReadPalette(); ovlRender(); }); await sleep(60);
  check('   back on Android 14 they are there again, live', await ev(() => ['ovlSpecCard', 'ovlContrastCard', 'ovlIntensityCard', 'ovlSwitchCard'].every(id => getComputedStyle(document.getElementById(id)).display !== 'none') && document.querySelectorAll('#ovlSwitches .sdb-sw').length === 3 && !document.getElementById('ovlApplyBtn').disabled));

  // 4) How this works
  const how = await ev(() => { const d = document.getElementById('ovlHowCard'); return { open: d.open, summary: d.querySelector('summary').innerText, text: d.innerText.replace(/\s+/g, ' ') }; });
  check('4. How this works is collapsed, with its summary', !how.open && how.summary === 'How this works', JSON.stringify(how).slice(0, 200));
  await page.locator('#ovlHowCard summary').click(); await sleep(100);
  const opened = await ev(() => { const d = document.getElementById('ovlHowCard'); return { open: d.open, text: d.innerText.replace(/\s+/g, ' '), buttons: Array.from(d.querySelectorAll('button')).map(x => x.innerText) }; });
  check('   opened: the text on the setting and on the overlay, and the Copy the setting and Reload buttons', opened.open && /theme_customization_overlay_packages/.test(opened.text) && /cmd overlay fabricate/.test(opened.text) && /Shizuku as the system user \(uid 1000\) and the ADB shell cannot write it/.test(opened.text) && opened.buttons.join('|') === 'Copy the setting|Reload', JSON.stringify(opened).slice(0, 300));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
