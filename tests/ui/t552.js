// Apps tab: the three-dot menu of the search bar ends with Row tints, the very slider of Settings > Row tints (Off, Faint, Subtle, Normal, Vivid), and its
// Gradient switch (the owner's wish, v7.18.1; before, the last row only opened Settings). Both change the setting right there, the menu stays open and the
// rows behind it change as you go; they and Settings' controls are one setting (a change in one place shows in the other). Gradient is off by default. The
// slider row is built like the switch rows (name, one grey line, a hairline above) with the slider and its five step names under its line; the Gradient row is
// a switch row like the three above it. Keyboard: Tab goes switch, slider, switch; the arrow keys move the slider; Escape and Back close the menu. On a short
// screen the menu scrolls inside itself, so its last row can be reached above the bottom bar, and it fits itself again when the phone is turned. Translated in every language (the step names fit at 320 px), and in
// Arabic the slider and its step names both run from the right.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }          // (the numbers behind a passing check stay out of the output: they depend on the fonts)
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APPS = [
  { pkg: 'com.example.calendar', name: 'Calendar', isRunning: true },
  { pkg: 'com.example.camera', name: 'Camera', isFrozen: true },
  { pkg: 'com.example.clock', name: 'Clock' },
  { pkg: 'org.zz.notes', name: 'Calculator', isUninstalled: true }
];
const TINT = '#searchMenuRowTints', GRAD = '#searchMenuRowGrad';
const STEPS = ['Off', 'Faint', 'Subtle', 'Normal', 'Vivid'], RT = ['off', 'faint', 'subtle', 'normal', 'vivid'];
const LANGS = ['ar', 'de', 'es', 'fr', 'hi', 'id', 'it', 'ja', 'ko', 'pt-BR', 'ru', 'tr', 'zh-CN'];

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (vp, opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: vp || { width: 360, height: 640 }, hasTouch: true });
    page.bottomNav = !!opts.bottomNav;
    page.on('pageerror', e => { errors.push(e.message); });
    const apps = opts.apps || APPS;
    await page.addInitScript(inst.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, opts.kv || {}), apps, store: opts.store || {} });
    await page.goto(PAGE);
    await page.waitForFunction(n => document.getElementById('countAll').innerText === String(n), apps.length);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const menuOpen = page => ev(page, () => document.getElementById('searchMenu').style.display !== 'none');
  const opts = page => ev(page, () => ({ names: document.getElementById('searchOptNames').checked, packages: document.getElementById('searchOptPackages').checked, regex: document.getElementById('searchOptRegex').checked }));
  // the page has stopped scrolling (a smooth scroll takes a moment)
  const still = async page => { let n = 0, last = -1; for (let i = 0; i < 60 && n < 4; i++) { await sleep(60); const y = await ev(page, () => Math.round(scrollY)); n = y === last ? n + 1 : 0; last = y; } };
  // the setting as it is: what is stored, what the page draws, and what both places show
  const state = page => ev(page, () => {
    const run = document.querySelector('#appsListContainer .app-card.running'), a = run ? getComputedStyle(run, '::before') : null;
    return {
      lv: rowTintLevel(), tint: document.documentElement.getAttribute('data-row-tint'), grad: document.documentElement.getAttribute('data-row-grad'),
      kvLv: window.__kv.row_tint_lv, kvGrad: window.__kv.row_tint_grad,
      menuRange: document.getElementById('smRowTintRange').value, setRange: document.getElementById('rowTintRange').value,
      menuGrad: document.getElementById('smRowTintGrad').checked, setGrad: document.getElementById('rowTintGradToggle').checked,
      rowO: a ? Math.round(+a.opacity * 1000) / 1000 : null, rowImg: a ? (a.backgroundImage === 'none' ? 'none' : /gradient\(/.test(a.backgroundImage) ? 'gradient' : a.backgroundImage) : null,
      view: currentViewName()
    };
  });
  const rows = page => ev(page, () => {
    const all = Array.from(document.querySelectorAll('#searchMenu .sm-row')), menu = document.getElementById('searchMenu').getBoundingClientRect();
    const tint = document.getElementById('searchMenuRowTints'), grad = document.getElementById('searchMenuRowGrad'), regex = all[2];
    if (!tint || !grad) return null;
    const rng = tint.querySelector('input[type="range"]'), ticks = tint.querySelector('.row-tint-ticks'), set = document.querySelector('#rowTintCard .row-tint-slider');
    const name = tint.querySelector('.sm-name'), sub = tint.querySelector('.sm-sub'), r = tint.getBoundingClientRect(), nr = name.getBoundingClientRect(), sr = sub.getBoundingClientRect(), rr = rng.getBoundingClientRect();
    const look = el => ['display', 'alignItems', 'paddingTop', 'paddingBottom', 'columnGap', 'borderTopStyle', 'borderTopWidth'].map(k => getComputedStyle(el)[k]).join(' ');
    const gi = grad.querySelector('input');
    return {
      names: all.map(x => x.querySelector('.sm-name').innerText).join('|'), count: all.length, order: all.indexOf(tint) === 3 && all.indexOf(grad) === 4,
      name: name.innerText, sub: sub.innerText, subs: tint.querySelectorAll('.sm-sub').length, fontLook: getComputedStyle(name).fontSize === getComputedStyle(regex.querySelector('.sm-name')).fontSize && getComputedStyle(sub).fontSize === getComputedStyle(regex.querySelector('.sm-sub')).fontSize && getComputedStyle(sub).color === getComputedStyle(regex.querySelector('.sm-sub')).color,
      tintLook: look(tint) === look(regex), gradLook: look(grad) === look(regex), tag: tint.tagName, role: tint.getAttribute('role'), labelledBy: tint.getAttribute('aria-labelledby') === name.id && !!name.id,
      notLabel: !tint.closest('label') && tint.tagName !== 'LABEL', cursor: getComputedStyle(tint).cursor, noSwitch: !tint.querySelector('input[type="checkbox"]'), oldGone: typeof window.searchMenuRowTints !== 'function' && !document.querySelector('#searchMenu .sm-chev, #searchMenu .sm-go, #searchMenu [role="button"]'),          // (window.searchMenuRowTints is now the row itself: an element's id)
      range: [rng.id, rng.min, rng.max, rng.step, rng.getAttribute('aria-label')].join(' '), setRange: (() => { const s = document.getElementById('rowTintRange'); return [s.min, s.max, s.step, s.getAttribute('aria-label')].join(' '); })(),
      ticks: Array.from(ticks.children).map(t => t.innerText).join('|'), setTicks: Array.from(set.querySelector('.row-tint-ticks').children).map(t => t.innerText).join('|'),
      sameSlider: rng.closest('.row-tint-slider') && getComputedStyle(rng).accentColor === getComputedStyle(document.getElementById('rowTintRange')).accentColor,
      below: nr.bottom <= sr.top + 1 && sr.bottom <= rr.top + 1, fullWidth: Math.abs(rr.width - sr.width) <= 4 && rr.left >= r.left - 1 && rr.right <= r.right + 1,
      ticksFit: ticks.scrollWidth <= ticks.clientWidth + 1 && Array.from(ticks.children).every(t => t.getBoundingClientRect().right <= ticks.getBoundingClientRect().right + 1 && t.getBoundingClientRect().left >= ticks.getBoundingClientRect().left - 1),
      gradName: grad.querySelector('.sm-name').innerText, gradSub: grad.querySelector('.sm-sub').innerText, gradLabel: grad.tagName === 'LABEL', gradDiv: !!grad.querySelector('.sm-div'), gradTrack: !!grad.querySelector('.switch-input + .switch-track'),
      gradInput: gi.id + ' ' + gi.type, gradOn: gi.checked, dividers: document.querySelectorAll('#searchMenu .sm-div').length,
      insideMenu: [tint, grad].every(x => { const q = x.getBoundingClientRect(); return q.left >= menu.left - 1 && q.right <= menu.right + 1; }), inScreen: [tint, grad].every(x => { const q = x.getBoundingClientRect(); return q.left >= 0 && q.right <= innerWidth; }),
      tall: r.height >= 44 && grad.getBoundingClientRect().height >= 44, rangeTall: rr.height >= 16, textFits: [name, sub, grad.querySelector('.sm-name')].every(e => e.scrollWidth <= e.clientWidth + 1)
    };
  });

  // ---- 1. the rows: Row tints with its slider, then Gradient, after the three search switches ----
  let page = await open();
  await page.tap('#searchMenuBtn');
  check('1. the menu opens', await menuOpen(page));
  let r = await rows(page);
  check('   its rows are Include application names, Include package names, Use regex matching, Row tints, Gradient', !!r && r.count === 5 && r.order && r.names === 'Include application names|Include package names|Use regex matching|Row tints|Gradient', r && r.names);
  check('   the old shortcut is gone: no chevron row, nothing that opens Settings', !!r && r.oldGone, JSON.stringify(r));
  check('   Row tints: its name, one grey line "How strongly rows are tinted." as in the regex row, a hairline above, the row box of the others', !!r && r.name === 'Row tints' && r.sub === 'How strongly rows are tinted.' && r.subs === 1 && r.fontLook && r.tintLook, JSON.stringify(r));
  check('   under its line the slider of Settings: 0 to 4 in steps of 1, named Row tints, the same five step names (Off, Faint, Subtle, Normal, Vivid), the full width of the row', !!r && r.range === 'smRowTintRange 0 4 1 Row tints' && r.setRange === '0 4 1 Row tints' && r.ticks === STEPS.join('|') && r.ticks === r.setTicks && r.sameSlider && r.below && r.fullWidth && r.rangeTall, JSON.stringify(r));
  check('   the slider row is a group named by its name, not a label and not a switch (a tap beside the slider does nothing), with the plain arrow', !!r && r.role === 'group' && r.labelledBy && r.notLabel && r.noSwitch && r.cursor === 'default', JSON.stringify(r));
  check('   Gradient: a switch row like the three above (name, line, divider, switch), off by default', !!r && r.gradName === 'Gradient' && r.gradSub === 'Each tint fades across the row. Off: one even colour.' && r.gradLabel && r.gradDiv && r.gradTrack && r.gradInput === 'smRowTintGrad checkbox' && r.gradOn === false && r.gradLook && r.dividers === 4, JSON.stringify(r));
  check('   both are inside the menu and the screen, at least 44 px tall, and their texts and step names fit', !!r && r.insideMenu && r.inScreen && r.tall && r.textFits && r.ticksFit, JSON.stringify(r));
  let s = await state(page);
  check('   they show the setting as it is: Subtle, Gradient off (the defaults), like Settings', s.menuRange === '2' && s.setRange === '2' && s.lv === 2 && s.tint === 'subtle' && s.menuGrad === false && s.setGrad === false && s.grad === 'off' && s.rowImg === 'none', JSON.stringify(s));
  const three = await ev(page, () => Array.from(document.querySelectorAll('#searchMenu label.sm-row')).slice(0, 3).map(l => ({ input: l.querySelector('input').id, on: l.querySelector('input').checked, sub: l.querySelector('.sm-sub') ? l.querySelector('.sm-sub').innerText : '' })));
  check('   the three search switches are as before: all on, the regex line unchanged', three.map(t => t.input).join() === 'searchOptNames,searchOptPackages,searchOptRegex' && three.every(t => t.on) && three[2].sub === 'If turned off, applications and packages are matched exactly: the whole name has to be typed.', JSON.stringify(three));
  await page.screenshot({ path: 'rowtints_menu.png' });
  if (!r) { console.log('the rows are not there: the rest of the script needs them'); console.log(bad + ' FAILED'); await b.close(); process.exit(1); }

  // ---- 2. the search switches still work, and the menu stays open while they do ----
  const flip = async id => { await page.locator('.sm-row:has(#' + id + ') .sm-name').click(); };
  await flip('searchOptRegex');
  check('2. the regex switch turns off and the menu stays open', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: false }) && await menuOpen(page));
  await flip('searchOptRegex');
  await flip('searchOptNames');
  await flip('searchOptPackages');
  check('   turning off names, then packages, turns names on instead, as before', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: false, regex: true }));
  await flip('searchOptPackages');
  check('   all three are on again, and the Row tints setting did not move', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: true }) && (await state(page)).lv === 2);

  // ---- 3. the slider, by touch: right there, the menu stays open, the rows behind it change ----
  const tapAt = async frac => { const bx = await page.locator('#smRowTintRange').boundingBox(); await page.touchscreen.tap(bx.x + 4 + (bx.width - 8) * frac, bx.y + bx.height / 2); await sleep(120); };
  const o0 = (await state(page)).rowO;
  await tapAt(1);
  s = await state(page);
  check('3. a tap at the slider\'s end sets Vivid: stored, drawn (html data-row-tint="vivid") and shown in Settings too', s.lv === 4 && s.tint === 'vivid' && s.kvLv === '4' && s.menuRange === '4' && s.setRange === '4', JSON.stringify(s));
  check('   the menu stays open and the tab stays the Apps tab', await menuOpen(page) && s.view === 'apps', JSON.stringify(s));
  check('   the running app\'s row behind the menu is tinted more strongly at once', s.rowO > o0, o0 + ' -> ' + s.rowO);
  await tapAt(0);
  s = await state(page);
  check('   a tap at its start sets Off: no tint on the row', s.lv === 0 && s.tint === 'off' && s.kvLv === '0' && s.setRange === '0' && s.rowO === 0 && await menuOpen(page), JSON.stringify(s));
  const steps = [];
  for (let i = 0; i <= 4; i++) { await tapAt(i / 4); steps.push((await state(page)).tint); }
  check('   each of the five steps can be tapped: ' + steps.join(', '), steps.join() === RT.join(), steps.join());
  await tapAt(0.5);
  check('   back to Subtle (the middle)', (await state(page)).lv === 2);
  // a drag along it, as a finger does: the page's own input events
  const bx = await page.locator('#smRowTintRange').boundingBox();
  await page.mouse.move(bx.x + bx.width / 2, bx.y + bx.height / 2); await page.mouse.down(); await page.mouse.move(bx.x + bx.width * 0.8, bx.y + bx.height / 2, { steps: 6 }); await page.mouse.up(); await sleep(120);
  s = await state(page);
  check('   a drag to the right moves it on (Normal), and the menu is still open', s.lv === 3 && s.setRange === '3' && await menuOpen(page), JSON.stringify(s));
  await page.tap(TINT + ' .sm-name'); await sleep(100);
  s = await state(page);
  check('   a tap on the row\'s name does nothing: the menu stays open, Settings does not open, the level stays', await menuOpen(page) && s.view === 'apps' && s.lv === 3, JSON.stringify(s));

  // ---- 4. Gradient, by touch ----
  await page.tap(GRAD + ' .sm-name'); await sleep(120);
  s = await state(page);
  check('4. a tap on Gradient turns it on: stored (row_tint_grad true), drawn (html data-row-grad="on", the running row is a gradient), on in Settings too', s.menuGrad && s.setGrad && s.grad === 'on' && s.kvGrad === 'true' && s.rowImg === 'gradient', JSON.stringify(s));
  check('   and the menu stays open', await menuOpen(page) && s.view === 'apps');
  await page.tap(GRAD + ' .switch-track'); await sleep(120);
  s = await state(page);
  check('   a tap on the switch itself turns it off again: one even colour', !s.menuGrad && !s.setGrad && s.grad === 'off' && s.kvGrad === 'false' && s.rowImg === 'none' && await menuOpen(page), JSON.stringify(s));
  await page.screenshot({ path: 'rowtints_menu_used.png' });

  // ---- 5. one setting: a change in Settings shows in the menu ----
  await page.keyboard.press('Escape');
  await ev(page, () => switchView('prefs')); await sleep(150);
  await page.locator('#rowTintRange').fill('1');
  await page.click('#rowTintGradRow .switch-track'); await sleep(120);
  await ev(page, () => handleAndroidBack()); await sleep(150);
  check('5. back in the Apps tab after Faint and Gradient on in Settings', (await ev(page, () => currentViewName())) === 'apps');
  await page.tap('#searchMenuBtn'); await sleep(150);
  s = await state(page);
  check('   the menu shows Faint and Gradient on', s.menuRange === '1' && s.menuGrad === true && s.lv === 1 && s.grad === 'on', JSON.stringify(s));
  // a setting changed behind the page's back (a restored backup): the menu reads it again each time it opens
  await page.keyboard.press('Escape');
  await ev(page, () => { window.__kv.row_tint_lv = '4'; window.__kv.row_tint_grad = 'false'; });
  await page.tap('#searchMenuBtn'); await sleep(150);
  s = await state(page);
  check('   a value stored meanwhile is what the menu shows when it opens again (Vivid, Gradient off)', s.menuRange === '4' && s.menuGrad === false && s.tint === 'vivid' && s.grad === 'off', JSON.stringify(s));
  await ev(page, () => { setRowTint(2); });
  await page.keyboard.press('Escape');

  // ---- 6. the keyboard ----
  await page.focus('#searchMenuBtn'); await page.keyboard.press('Enter');
  check('6. Enter on the menu button opens the menu', await menuOpen(page));
  await page.focus('#searchOptRegex'); await page.keyboard.press('Tab');
  let f = await ev(page, () => { const a = document.activeElement, cs = getComputedStyle(a); return { id: a.id, ring: cs.outlineStyle === 'solid' && parseFloat(cs.outlineWidth) >= 2 }; });
  check('   Tab from the regex switch reaches the slider, and it shows a focus ring', f.id === 'smRowTintRange' && f.ring, JSON.stringify(f));
  await page.screenshot({ path: 'rowtints_menu_focus.png' });
  await page.keyboard.press('ArrowRight');
  s = await state(page);
  check('   the right arrow moves it one step (Normal), in Settings too', s.lv === 3 && s.setRange === '3' && await menuOpen(page), JSON.stringify(s));
  await page.keyboard.press('ArrowLeft'); await page.keyboard.press('ArrowLeft');
  check('   the left arrow moves it back (Faint)', (await state(page)).lv === 1);
  await page.keyboard.press('End');
  check('   End goes to Vivid, Home to Off', (await state(page)).lv === 4 && (await page.keyboard.press('Home'), (await state(page)).lv === 0));
  await ev(page, () => { setRowTint(2); });
  await page.keyboard.press('Tab');
  f = await ev(page, () => document.activeElement.id);
  check('   Tab goes on to the Gradient switch', f === 'smRowTintGrad', f);
  await page.keyboard.press('Space');
  s = await state(page);
  check('   Space turns it on, the menu stays open', s.menuGrad && s.grad === 'on' && await menuOpen(page), JSON.stringify(s));
  await page.keyboard.press('Space');
  check('   Space again turns it off', (await state(page)).grad === 'off');
  await page.keyboard.press('Escape');
  check('   Escape still closes the menu and the focus goes back to its button', !(await menuOpen(page)) && (await ev(page, () => document.activeElement.id)) === 'searchMenuBtn');
  await page.tap('#searchMenuBtn');
  const back = await ev(page, () => { const r = handleAndroidBack(); return { r, view: currentViewName(), menu: searchMenuOpen(), cls: document.body.classList.contains('search-menu-open') }; });
  check('   Back closes the menu (and stays on the Apps tab)', back.r === true && back.view === 'apps' && !back.menu && !back.cls, JSON.stringify(back));
  await page.tap('#searchMenuBtn');
  await page.touchscreen.tap(4, 320); await sleep(100);                     // the page's own margin, left of the menu
  check('   a tap outside it closes it too', !(await menuOpen(page)));
  await page.close();

  // ---- 7. a choice made before this version: kept (Gradient on stays on, Vivid stays Vivid) ----
  page = await open(null, { kv: { row_tint_grad: 'true', row_tint_lv: '4' } });
  await page.tap('#searchMenuBtn'); await sleep(100);
  s = await state(page);
  check('7. with Gradient on and Vivid stored from before, both stay: the menu and Settings show them and the row is a gradient', s.menuGrad && s.setGrad && s.grad === 'on' && s.rowImg === 'gradient' && s.menuRange === '4' && s.tint === 'vivid', JSON.stringify(s));
  await page.close();
  page = await open(null, { kv: { row_tint_grad: 'false' } });
  s = await state(page);
  check('   and with Gradient off stored from before, it stays off', !s.menuGrad && !s.setGrad && s.grad === 'off', JSON.stringify(s));
  await page.close();

  // ---- 8. screen sizes: both rows on screen and usable at 320, 360 and 412 px, tall and short, with the top tab bar alone (the harness default) and with the
  // bottom bar (the app's default); where the menu is taller than the room it has, it scrolls inside itself and its last row comes above the bar ----
  const MANY = Array.from({ length: 60 }, (_, i) => ({ pkg: 'com.vendor.app' + i, name: 'Application ' + i, isRunning: i % 3 === 0 }));          // a long list: the page can scroll, which is what opens the menu in view
  for (const [wd, ht, bottomNav, few] of [[320, 568], [360, 640], [360, 520], [412, 915], [320, 568, true], [360, 520, true], [360, 640, true], [360, 720, true], [412, 915, true], [360, 480, true], [320, 568, true, true], [360, 480, true, true]]) {
    const p = await open({ width: wd, height: ht }, { apps: few ? APPS : MANY, bottomNav: !!bottomNav });
    await p.tap('#searchMenuBtn'); await still(p);
    const m = await ev(p, () => {
      const vis = el => !!el && el.getBoundingClientRect().height > 0 && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
      const menu = document.getElementById('searchMenu'), tabs = document.getElementById('tabBar'), bar = document.getElementById('bnav');
      const top = Math.max(document.querySelector('.app-header').getBoundingClientRect().bottom, vis(tabs) ? tabs.getBoundingClientRect().bottom : 0), bottom = vis(bar) ? bar.getBoundingClientRect().top : innerHeight;
      const scrolls = menu.scrollHeight > menu.clientHeight + 1;
      menu.scrollTop = menu.scrollHeight;                                          // a finger scrolls the menu to its end
      const at = id => {
        const row = document.getElementById(id), q = row.getBoundingClientRect(), m = menu.getBoundingClientRect();
        const hit = document.elementFromPoint(q.left + q.width / 2, q.top + q.height / 2), low = document.elementFromPoint(q.left + q.width / 2, q.bottom - 2);
        return q.top >= top - 1 && q.bottom <= bottom + 1 && q.top >= m.top - 1 && q.bottom <= m.bottom + 1 && !!hit && hit.closest('.sm-row') === row && !!low && low.closest('.sm-row') === row;
      };
      const rng = document.getElementById('smRowTintRange').getBoundingClientRect(), rh = document.elementFromPoint(rng.left + rng.width / 2, rng.top + rng.height / 2);
      const ticks = document.querySelector('#searchMenu .row-tint-ticks'), mr = menu.getBoundingClientRect();
      return { barShown: vis(bar), scrolls, grad: at('searchMenuRowGrad'), tint: at('searchMenuRowTints'), slider: rh && rh.id === 'smRowTintRange', inside: mr.left >= 0 && mr.right <= innerWidth && mr.bottom <= bottom + 1, noSideScroll: document.documentElement.scrollWidth <= innerWidth,
        ticksFit: ticks.scrollWidth <= ticks.clientWidth + 1, boxBelowHeader: document.getElementById('searchBox').getBoundingClientRect().top >= document.querySelector('.app-header').getBoundingClientRect().bottom - 1 };
    });
    const tag = '[' + wd + 'x' + ht + (bottomNav ? ', bottom bar' : '') + (few ? ', 4 apps' : '') + ']';
    check('8. ' + tag + ' Row tints and Gradient are on screen' + (bottomNav ? ' above the bottom bar (the bar is really shown)' : ' under the tab bar') + ', inside the menu, nothing covers them (their low edge neither), the step names fit, nothing scrolls sideways',
      (!bottomNav || m.barShown) && m.grad && m.tint && m.slider && m.inside && m.noSideScroll && m.ticksFit && m.boxBelowHeader, JSON.stringify(m));
    // the slider and the switch work there too
    await p.locator('#smRowTintRange').evaluate(e => e.scrollIntoView({ block: 'nearest' }));
    const bb = await p.locator('#smRowTintRange').boundingBox();
    await p.touchscreen.tap(bb.x + bb.width - 4, bb.y + bb.height / 2); await sleep(100);
    await p.tap(GRAD + ' .switch-track'); await sleep(100);
    const u = await state(p);
    check('   and both work there: Vivid and Gradient on, with the menu still open', u.lv === 4 && u.grad === 'on' && await menuOpen(p), JSON.stringify(u));
    if (wd === 360 && ht === 480 && bottomNav && !few) await p.screenshot({ path: 'rowtints_short_screen.png' });
    await p.close();
  }

  // ---- 9. the languages: every one translates the rows and the step names fit at 320 px; Arabic runs from the right ----
  page = await open({ width: 320, height: 640 }, { apps: MANY });
  const lines = [];
  for (const lang of LANGS) {
    await ev(page, l => I18N.set(l), lang);
    await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent !== 'Row tints' && document.querySelector('#searchMenuRowGrad .sm-sub').textContent !== 'Each tint fades across the row. Off: one even colour.', null, { timeout: 5000 }).catch(() => {});
    await page.tap('#searchMenuBtn'); await still(page);
    const l = await rows(page);
    const t = await ev(page, () => ({ sub: document.querySelector('#searchMenuRowTints .sm-sub').textContent, grad: document.querySelector('#searchMenuRowGrad .sm-name').textContent, set: document.querySelector('#rowTintCard .row-tint-ticks').textContent }));
    const ok = !!l && l.name !== 'Row tints' && t.sub !== 'How strongly rows are tinted.' && t.grad !== 'Gradient' && l.gradSub !== 'Each tint fades across the row. Off: one even colour.' && l.ticks !== STEPS.join('|') && l.ticks.replace(/\|/g, '') === t.set && l.ticksFit && l.textFits && l.insideMenu && l.inScreen;
    if (!ok) lines.push(lang + ' ' + JSON.stringify({ l, t }));
    await page.keyboard.press('Escape');
  }
  check('9. in each of the 13 languages the two rows, their lines and the step names are translated (the same step names as Settings), and at 320 px they fit', lines.length === 0, lines.join(' || '));
  await ev(page, () => I18N.set('de'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'Zeilenfärbung', null, { timeout: 5000 }).catch(() => {});
  await page.tap('#searchMenuBtn');
  const de = await rows(page);
  check('    in German: Zeilenfärbung, its line, the step names Aus to Kräftig, and Verlauf', !!de && de.name === 'Zeilenfärbung' && de.sub === 'Wie stark Zeilen gefärbt werden.' && de.ticks === 'Aus|Schwach|Dezent|Normal|Kräftig' && de.gradName === 'Verlauf' && de.gradSub === 'Jede Färbung läuft über die Zeile hinweg aus. Aus: eine gleichmäßige Farbe.', JSON.stringify(de));
  await page.keyboard.press('Escape');
  await ev(page, () => I18N.set('ar'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'تلوين الصفوف' && document.documentElement.dir === 'rtl', null, { timeout: 5000 }).catch(() => {});
  await page.tap('#searchMenuBtn'); await still(page);
  const ar = await ev(page, () => {
    const t = Array.from(document.querySelectorAll('#searchMenu .row-tint-ticks span')).map(s => s.getBoundingClientRect()), rng = document.getElementById('smRowTintRange');
    return { dir: document.documentElement.dir, rangeDir: getComputedStyle(rng).direction, offRight: t[0].left > t[4].left, name: document.querySelector('#searchMenuRowTints .sm-name').textContent };
  });
  check('    in Arabic (right to left) the step names start at the right (Off) and so does the slider', ar.dir === 'rtl' && ar.rangeDir === 'rtl' && ar.offRight && ar.name === 'تلوين الصفوف', JSON.stringify(ar));
  // in Arabic a tap at the slider's right end is Off, as its step name says
  const ab = await page.locator('#smRowTintRange').boundingBox();
  await page.touchscreen.tap(ab.x + ab.width - 4, ab.y + ab.height / 2); await sleep(100);
  const arLv = (await state(page)).lv;
  await page.touchscreen.tap(ab.x + 4, ab.y + ab.height / 2); await sleep(100);
  check('    and a tap at its right end is Off, at its left end Vivid', arLv === 0 && (await state(page)).lv === 4, arLv + ' / ' + (await state(page)).lv);
  await page.close();

  // ---- 10. the phone turned with the menu open: it fits itself again (whole where there is room, scrolling above the bar again where there is not) ----
  page = await open({ width: 360, height: 480 }, { apps: MANY, bottomNav: true });
  await page.tap('#searchMenuBtn'); await still(page);
  const fitNow = p => ev(p, () => {
    const m = document.getElementById('searchMenu'), r = m.getBoundingClientRect(), bar = document.getElementById('bnav').getBoundingClientRect();
    return { open: m.style.display !== 'none', limited: m.style.maxHeight !== '', scrolls: m.scrollHeight > m.clientHeight + 1, aboveBar: r.bottom <= bar.top + 1 };
  });
  const short1 = await fitNow(page);
  await page.setViewportSize({ width: 360, height: 900 }); await sleep(300);
  const tall = await fitNow(page);
  await page.setViewportSize({ width: 360, height: 480 }); await sleep(300);
  const short2 = await fitNow(page);
  check('10. turned with the menu open: short, it scrolls inside itself above the bar; tall, it is whole again; short again, it scrolls above the bar again',
    short1.open && short1.limited && short1.scrolls && short1.aboveBar && tall.open && !tall.limited && !tall.scrolls && tall.aboveBar && short2.open && short2.limited && short2.scrolls && short2.aboveBar, JSON.stringify({ short1, tall, short2 }));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  process.exit(bad ? 1 : 0);
})();
