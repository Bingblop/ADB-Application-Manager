// v7.14: cards that fold (Settings and Connected Devices). Curated Theme Palettes and the Press and hold guide start folded; Action Button, Navigation, Feature List
// and Reconnecting devices start open. Each has a round chevron in its top-right corner (a 44 px target, named by the card's title, aria-expanded) and a tap on
// the title row folds or unfolds it too; a folded body is hidden (not only clipped), the height moves smoothly unless reduced motion is asked for, the state of
// each card is kept across a reload (kv 'cards_folded'), and the shortcuts that jump into a card (More > Customize, the VirusTotal key link) unfold it first.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
// the details (sizes, positions: they differ a little from one machine's fonts to another's) are printed only when a check fails
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const CARDS = [['curatedCard', 'Curated Theme Palettes', false], ['actionBtnCard', 'Action Button', true], ['navCard', 'Navigation', true],
  ['featureCard', 'Feature List', true], ['holdGuideCard', 'Press and hold guide', false], ['cdReconnectCard', 'Reconnecting devices', false]];
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const ctx = await b.newContext({ viewport: { width: 390, height: 860 }, hasTouch: true, colorScheme: 'dark' });
  const page = await ctx.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);
  const wait = ms => page.waitForTimeout(ms);
  const state = () => ev(ids => ids.map(id => {
    const c = document.getElementById(id), btn = c.querySelector(':scope > .cc-btn'), body = c.querySelector(':scope > .cc-body'), title = c.querySelector(':scope > .color-card-title');
    const r = btn.getBoundingClientRect();
    return { id, open: !c.classList.contains('cc-closed'), expanded: btn.getAttribute('aria-expanded'), name: document.getElementById(btn.getAttribute('aria-labelledby')).textContent.trim(),
      title: title.textContent.trim(), controls: btn.getAttribute('aria-controls') === body.id, vis: getComputedStyle(body).visibility, h: Math.round(body.getBoundingClientRect().height),
      w: Math.round(r.width), hh: Math.round(r.height), type: btn.type };
  }), CARDS.map(c => c[0]));

  await ev(() => { localStorage.clear(); switchView('prefs'); }); await wait(500);
  let s = await state();
  // (the cards of Settings are measured here; Reconnecting devices, in another tab, in 8)
  check('1. every folding card has its title row, a chevron button named by the title that controls the body, and a 44 px target',
    s.slice(0, 5).every((x, i) => x.title === CARDS[i][1] && x.name === CARDS[i][1] && x.controls && x.type === 'button' && x.w >= 44 && x.hh >= 44), JSON.stringify(s.map(x => [x.id, x.name, x.w, x.hh])));
  check('2. defaults: Curated Theme Palettes and Press and hold guide folded, Action Button, Navigation, Feature List and Reconnecting devices open',
    s.every((x, i) => x.open === CARDS[i][2] && x.expanded === String(CARDS[i][2])), JSON.stringify(s.map(x => x.id + '=' + x.open)));
  check('   a folded body is hidden (visibility) and takes no height; an open one is visible', s.slice(0, 5).every(x => x.open ? x.vis === 'visible' && x.h > 0 : x.vis === 'hidden' && x.h === 0), JSON.stringify(s.map(x => [x.id, x.vis, x.h])));
  check('   the button sits in the top-right corner of the card', await ev(() => { const c = document.getElementById('curatedCard').getBoundingClientRect(), r = document.querySelector('#curatedCard > .cc-btn').getBoundingClientRect(); return c.right - r.right < 16 && r.top - c.top < 16; }));
  check('   nothing in a folded card can be reached by a tap', await ev(() => { const p = document.querySelector('#curatedCard .palette-preset-card'); const r = p.getBoundingClientRect(); return r.height === 0 || document.elementFromPoint(r.left + 5, r.top + 5) !== p; }));

  // ---- the chevron and the title ----
  await page.click('#curatedCard > .cc-btn'); await wait(450);
  s = await state();
  check('3. the chevron unfolds Curated Theme Palettes (aria-expanded true, the presets show)', s[0].open && s[0].expanded === 'true' && s[0].vis === 'visible' && s[0].h > 300 && await ev(() => document.querySelector('#curatedCard .palette-preset-card').getBoundingClientRect().height > 0), JSON.stringify(s[0]));
  await page.click('#featureCard > .color-card-title'); await wait(450);
  s = await state();
  check('4. a tap on the title folds Feature List', !s[3].open && s[3].expanded === 'false' && s[3].h === 0, JSON.stringify(s[3]));
  await page.click('#holdGuideCard > .color-card-title'); await wait(450);
  check('   and one on the Press and hold guide title unfolds it', (await state())[4].open);
  check('   the states are kept in kv cards_folded', await ev(() => { const o = kvGet('cards_folded', null); return o && o.curated === false && o.features === true && o.holdguide === false; }), await ev(() => JSON.stringify(kvGet('cards_folded', null))));
  await page.focus('#navCard > .cc-btn'); await page.keyboard.press('Enter'); await wait(450);
  check('   the chevron works from the keyboard too (Navigation folds)', !(await state())[2].open);

  // ---- after a reload ----
  await page.reload(); await wait(500);
  await ev(() => switchView('prefs')); await wait(300);
  s = await state();
  check('5. after a reload every card is as it was left (Curated open, Feature List folded, Press and hold open, Navigation folded)',
    s[0].open && !s[3].open && s[4].open && !s[2].open && s[1].open && !s[5].open && s.every(x => x.expanded === String(x.open)), JSON.stringify(s.map(x => x.id + '=' + x.open)));

  // ---- jumps into a folded card unfold it ----
  await ev(() => bnavCustomize()); await wait(500);
  check('6. More > Customize (the Feature List) unfolds the Feature List first', (await state())[3].open);
  await ev(() => { ccApply(document.getElementById('curatedCard'), false, true); ccReveal(document.querySelector('#curatedCard .palette-preset-card')); }); await wait(400);
  check('   ccReveal(element) unfolds the card around an element', (await state())[0].open);
  await ev(() => { switchView('apps'); vtOpenSettings(); }); await wait(500);
  check('   the VirusTotal key link opens Settings with the key field in view', await ev(() => { const r = document.getElementById('vtKeyInput').getBoundingClientRect(); return currentViewName() === 'prefs' && r.height > 0 && r.top >= 0 && r.bottom <= innerHeight; }));

  // ---- motion ----
  const tr = await ev(() => getComputedStyle(document.querySelector('#curatedCard > .cc-body')).transitionProperty + ' / ' + getComputedStyle(document.querySelector('#curatedCard > .cc-body')).transitionDuration);
  check('7. the height moves (grid rows, about a quarter of a second)', /grid-template-rows/.test(tr) && /0\.26s/.test(tr), tr);
  await page.emulateMedia({ reducedMotion: 'reduce' }); await wait(100);
  const tr2 = await ev(() => getComputedStyle(document.querySelector('#curatedCard > .cc-body')).transitionDuration);
  check('   with reduced motion it does not move', tr2.split(',').every(d => parseFloat(d) === 0), tr2);
  await page.emulateMedia({ reducedMotion: 'no-preference' });

  // ---- the Reconnecting devices card in Connected Devices ----
  await ev(() => switchView('devices')); await wait(400);
  s = await state();
  const firstFolded = !s[5].open;
  await page.click('#cdReconnectCard > .cc-btn'); await wait(450);
  s = await state();
  check('8. Reconnecting devices folds in Connected Devices too: folded at first (it sits above the device area), unfolds, its chevron named by its title with a 44 px target', await ev(() => { const c = document.getElementById('cdReconnectCard'); return document.getElementById('view-devices').contains(c) && c.getBoundingClientRect().height > 0; }) && firstFolded &&
    s[5].open && s[5].vis === 'visible' && s[5].h > 0 && s[5].name === 'Reconnecting devices' && s[5].controls && s[5].w >= 44 && s[5].hh >= 44, JSON.stringify(s[5]));
  await page.click('#cdReconnectCard > .cc-btn'); await wait(450);
  check('   folded, only its title row is left', await ev(() => { const c = document.getElementById('cdReconnectCard'); return c.classList.contains('cc-closed') && c.getBoundingClientRect().height < 80; }));
  await page.screenshot({ path: 'cc.png' });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
