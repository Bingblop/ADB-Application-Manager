// v7.0 Application Manager search: the search bar sits under all the filters (directly above the list) and has a menu at its right end:
// 1) include application names, 2) include package names, 3) use regex matching (with a line saying that off means exact matches only).
// All on at the start; kept between searches and launches; at least one of names and packages stays on (turning off the last one turns the other on).
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APPS = [
  { pkg: 'com.example.calendar', name: 'Calendar' },
  { pkg: 'com.example.camera', name: 'Camera' },
  { pkg: 'com.example.clock', name: 'Clock' },
  { pkg: 'org.zz.notes', name: 'Calculator' },
  { pkg: 'com.cal.tools', name: 'Toolbox' },
  { pkg: 'com.example.beta', name: 'Foo (beta)' },
  { pkg: 'com.example.cpp', name: 'C++ Notes' }
];

(async () => {
  const b = await chromium.launch();
  const open = async (store, size) => {
    const page = await b.newPage({ viewport: size || { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps: APPS, store: store || {} });
    await page.goto(PAGE);
    await page.waitForFunction(() => document.getElementById('statTotal').innerText === '7');
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const names = page => ev(page, () => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(n => n.innerText));
  const search = async (page, q) => { await page.fill('#searchInput', q); await sleep(60); return (await names(page)).sort(); };
  const opts = page => ev(page, () => ({ names: document.getElementById('searchOptNames').checked, packages: document.getElementById('searchOptPackages').checked, regex: document.getElementById('searchOptRegex').checked }));
  const menuOpen = page => ev(page, () => document.getElementById('searchMenu').style.display !== 'none');
  const row = async (page, id) => { if (!(await menuOpen(page))) await page.click('#searchMenuBtn'); await page.locator('.sm-row:has(#' + id + ') .sm-name').click(); };       // typing closes the menu: open it again when it is not open
  const saved = page => ev(page, () => { const v = window.__store.ui_state; return v ? JSON.parse(v).search : null; });

  let page = await open();

  // 1) placement: under the sort row, the tip and every filter, right above the list
  const g = await ev(page, () => {
    const top = id => Math.round(document.getElementById(id).getBoundingClientRect().top + scrollY), bottom = id => Math.round(document.getElementById(id).getBoundingClientRect().bottom + scrollY);
    const sort = document.querySelector('.apps-sort-row').getBoundingClientRect();
    return { sortBottom: Math.round(sort.bottom + scrollY), hintTop: top('filtersHint'), filterTop: top('filterScroll'), filterBottom: bottom('filterScroll'), searchTop: top('searchBox'), searchBottom: bottom('searchBox'), listTop: top('appsListContainer'), statsBottom: Math.round(document.querySelector('.stats-grid').getBoundingClientRect().bottom + scrollY) };
  });
  check('1. the search bar is under all the filters (stat buttons, sort and export, the tip, the filter pills) and above the list', g.statsBottom <= g.sortBottom && g.sortBottom <= g.hintTop && g.filterBottom <= g.searchTop && g.searchBottom <= g.listTop, JSON.stringify(g));
  check('   nothing else sits between the filters and the search bar', await ev(page, () => document.getElementById('filterScroll').nextElementSibling === document.getElementById('searchBox')));
  const ph = await ev(page, () => document.getElementById('searchInput').placeholder);
  check('   the box says what it searches', ph === 'Search by name or package', ph);

  // 2) the menu button at the right end of the bar
  const bx = await ev(page, () => { const r = e => { const b = document.getElementById(e).getBoundingClientRect(); return { l: Math.round(b.left), r: Math.round(b.right), t: Math.round(b.top), b: Math.round(b.bottom), w: Math.round(b.width), h: Math.round(b.height) }; }; return { box: r('searchBox'), input: r('searchInput'), btn: r('searchMenuBtn'), label: document.getElementById('searchMenuBtn').getAttribute('aria-label'), text: document.getElementById('searchMenuBtn').innerText, expanded: document.getElementById('searchMenuBtn').getAttribute('aria-expanded') }; });
  check('2. a menu button (three dots) sits all the way to the right inside the search bar, at least 40 x 40', bx.btn.r <= bx.box.r && bx.box.r - bx.btn.r <= 8 && bx.btn.w >= 40 && bx.btn.h >= 40 && bx.btn.t >= bx.box.t && bx.btn.b <= bx.box.b && bx.text === '⋮', JSON.stringify(bx));
  check('   it has a name for a screen reader and says it is closed', bx.label === 'Search options' && bx.expanded === 'false');
  check('   the menu is closed at the start', !(await menuOpen(page)));
  await page.fill('#searchInput', 'cal');
  const cl = await ev(page, () => { const c = document.getElementById('searchClear').getBoundingClientRect(), m = document.getElementById('searchMenuBtn').getBoundingClientRect(); return { clearShown: getComputedStyle(document.getElementById('searchClear')).display !== 'none', apart: c.right <= m.left + 1 }; });
  check('   the clear button shows with text in the box and does not sit on the menu button', cl.clearShown && cl.apart, JSON.stringify(cl));
  await page.click('#searchClear');
  check('   and clears the box and the filter', (await ev(page, () => document.getElementById('searchInput').value)) === '' && (await names(page)).length === 7);

  // 3) the menu: three rows, all on, the regex line
  await page.click('#searchMenuBtn');
  check('3. tapping the button opens the menu', await menuOpen(page) && (await ev(page, () => document.getElementById('searchMenuBtn').getAttribute('aria-expanded'))) === 'true');
  const m3 = await ev(page, () => ({ rows: Array.from(document.querySelectorAll('#searchMenu .sm-row')).map(r => r.querySelector('.sm-name').innerText), sub: document.querySelector('#searchMenu .sm-sub').innerText, subIn: document.querySelector('#searchMenu .sm-sub').closest('.sm-row').querySelector('.sm-name').innerText, dividers: document.querySelectorAll('#searchMenu .sm-div').length }));
  check('   1) application names 2) package names 3) regex matching, in that order', m3.rows.join('|') === 'Include application names|Include package names|Use regex matching', JSON.stringify(m3.rows));
  check('   the regex row explains that off means exact matches only', m3.subIn === 'Use regex matching' && /exact/i.test(m3.sub) && /off/i.test(m3.sub), m3.sub);
  check('   every row has a divider before its switch (like the reference menu)', m3.dividers === 3);
  check('   all three are on at the start', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: true }));
  const geo = await ev(page, () => { const m = document.getElementById('searchMenu').getBoundingClientRect(), box = document.getElementById('searchBox').getBoundingClientRect(); return { inside: m.left >= 0 && m.right <= window.innerWidth, belowBar: m.top >= box.bottom, right: Math.abs(m.right - box.right) <= 1 }; });
  check('   it opens under the bar, lined up with its right end, inside the screen', geo.inside && geo.belowBar && geo.right, JSON.stringify(geo));
  check('   nothing is saved until an option changes', (await saved(page)) === null);

  // 4) it closes: a tap elsewhere, Escape, Back, the button again
  await page.click('.stats-grid .stat-card >> nth=0');
  check('4. a tap elsewhere closes it', !(await menuOpen(page)));
  await page.click('#searchMenuBtn'); await page.keyboard.press('Escape');
  check('   Escape closes it and the focus goes back to the button', !(await menuOpen(page)) && (await ev(page, () => document.activeElement.id)) === 'searchMenuBtn');
  await page.click('#searchMenuBtn');
  const backed = await ev(page, () => handleAndroidBack());
  check('   Back closes it first (and stays on the tab)', backed === true && !(await menuOpen(page)) && (await ev(page, () => currentViewName())) === 'apps');
  await page.click('#searchMenuBtn'); await page.click('#searchMenuBtn');
  check('   the button again closes it', !(await menuOpen(page)));
  await page.click('#searchMenuBtn');
  await page.click('.sm-row:has(#searchOptRegex) .sm-name');
  check('   tapping a row inside the menu does not close it', await menuOpen(page));
  await page.click('.sm-row:has(#searchOptRegex) .sm-name');          // regex back on
  await page.keyboard.press('Escape');

  // 5) the matching with the three on: names or packages, as a pattern, any case
  let r = await search(page, 'cal');
  check('5. default: "cal" finds names and package names ("Calendar", "Calculator", and "Toolbox" by its package com.cal.tools)', r.join() === 'Calculator,Calendar,Toolbox', r.join());
  r = await search(page, 'CALEN');
  check('   case does not matter', r.join() === 'Calendar');
  r = await search(page, '^c(a|l)');
  check('   a pattern works: "^c(a|l)" finds the names starting with ca or cl', r.join() === 'Calculator,Calendar,Camera,Clock', r.join());
  r = await search(page, 'calendar$');
  check('   and one that ends the package name: "calendar$"', r.join() === 'Calendar', r.join());
  r = await search(page, '');
  check('   an empty box shows everything', r.length === 7);
  r = await search(page, 'zzzz');
  check('   nothing found shows the empty message', r.length === 0 && /No Applications Found/.test(await ev(page, () => document.getElementById('appsListContainer').innerText)));

  // 6) a pattern that does not compile is searched as plain text, and the note says so
  r = await search(page, '(');
  check('6. "(" is not a pattern: it is searched as text, so "Foo (beta)" is found', r.join() === 'Foo (beta)', r.join());
  const note = await ev(page, () => ({ shown: getComputedStyle(document.getElementById('searchNote')).display !== 'none', text: document.getElementById('searchNote').innerText }));
  check('   the note under the bar says that', note.shown && /not a valid pattern/.test(note.text) && /plain text/.test(note.text), JSON.stringify(note));
  r = await search(page, 'c++');
  check('   "c++" too ("Nothing to repeat" as a pattern): "C++ Notes" is found', r.join() === 'C++ Notes', r.join());
  await search(page, 'cal');
  check('   a good pattern takes the note away', (await ev(page, () => getComputedStyle(document.getElementById('searchNote')).display)) === 'none');
  await search(page, '(');
  await page.click('#searchClear');
  check('   and so does clearing the box', (await ev(page, () => getComputedStyle(document.getElementById('searchNote')).display)) === 'none');

  // 7) names / packages, and the rule that one of them always stays on
  await page.click('#searchMenuBtn');
  await row(page, 'searchOptNames');
  check('7. turning names off (packages are on): packages only', JSON.stringify(await opts(page)) === JSON.stringify({ names: false, packages: true, regex: true }));
  r = await search(page, 'cal');
  check('   "cal" now finds Calendar and Toolbox (by package) but not Calculator (name only)', r.join() === 'Calendar,Toolbox', r.join());
  check('   the box says it searches package names', (await ev(page, () => document.getElementById('searchInput').placeholder)) === 'Search by package name');
  await row(page, 'searchOptPackages');
  check('   turning the last one (packages) off turns names on instead', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: false, regex: true }));
  r = await search(page, 'cal');
  check('   names only: "cal" finds Calendar and Calculator but not Toolbox', r.join() === 'Calculator,Calendar', r.join());
  check('   the box says it searches application names', (await ev(page, () => document.getElementById('searchInput').placeholder)) === 'Search by application name');
  await row(page, 'searchOptNames');
  check('   and the other way round: turning names off turns packages on', JSON.stringify(await opts(page)) === JSON.stringify({ names: false, packages: true, regex: true }));
  await row(page, 'searchOptNames');
  check('   both on again', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: true }));
  check('   the list redrew at once with every change (no need to type again)', (await names(page)).sort().join() === 'Calculator,Calendar,Toolbox');

  // 8) regex off: the whole name (or package name) has to be typed
  await row(page, 'searchOptRegex');
  check('8. regex matching off', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: false }));
  r = await search(page, 'cal');
  check('   "cal" finds nothing now', r.length === 0, r.join());
  r = await search(page, 'calendar');
  check('   "calendar" finds Calendar (case does not matter)', r.join() === 'Calendar', r.join());
  r = await search(page, 'CLOCK');
  check('   "CLOCK" finds Clock', r.join() === 'Clock');
  r = await search(page, 'com.example.camera');
  check('   a whole package name finds its app', r.join() === 'Camera', r.join());
  r = await search(page, 'com.example');
  check('   part of a package name finds nothing', r.length === 0);
  r = await search(page, '(');
  check('   "(" is plain text here (nothing to compile): no note, and "Foo (beta)" is not an exact match', r.length === 0 && (await ev(page, () => getComputedStyle(document.getElementById('searchNote')).display)) === 'none');
  r = await search(page, 'foo (beta)');
  check('   the exact name "Foo (beta)" is found', r.join() === 'Foo (beta)');
  r = await search(page, '^cal.*');
  check('   a pattern is not a pattern: "^cal.*" is looked for as it is', r.length === 0);
  await row(page, 'searchOptNames');     // packages only, exact
  r = await search(page, 'calendar');
  check('   exact + packages only: the name "calendar" is not a package name', r.length === 0);
  r = await search(page, 'com.cal.tools');
  check('   but its package name is', r.join() === 'Toolbox');
  const sv = await saved(page);
  check('   the options are saved with the other view settings', sv && sv.names === false && sv.packages === true && sv.regex === false, JSON.stringify(sv));
  await page.click('#searchClear');

  // 9) kept between launches
  const store9 = await ev(page, () => ({ ui_state: window.__store.ui_state }));
  const p2 = await open(store9);
  check('9. after a restart the options are as they were left', JSON.stringify(await opts(p2)) === JSON.stringify({ names: false, packages: true, regex: false }), JSON.stringify(await opts(p2)));
  check('   and the box says what it searches', (await ev(p2, () => document.getElementById('searchInput').placeholder)) === 'Search by package name');
  r = await search(p2, 'com.cal.tools');
  check('   and searches that way', r.join() === 'Toolbox');
  await p2.close();
  const p3 = await open({ ui_state: JSON.stringify({ search: { names: false, packages: false, regex: true } }) });
  check('   a saved state with both names and packages off cannot happen: names come back on', JSON.stringify(await opts(p3)) === JSON.stringify({ names: true, packages: false, regex: true }), JSON.stringify(await opts(p3)));
  await p3.close();
  const p4 = await open({ ui_state: JSON.stringify({ appsFilter: 'user', search: { names: 'yes', packages: null } }) });
  check('   odd values in the saved state are ignored (all three stay on)', JSON.stringify(await opts(p4)) === JSON.stringify({ names: true, packages: true, regex: true }));
  await p4.close();
  const p5 = await open({ ui_state: JSON.stringify({ appsFilter: 'user' }) });
  check('   an older saved state without search options leaves them on', JSON.stringify(await opts(p5)) === JSON.stringify({ names: true, packages: true, regex: true }));
  await p5.close();

  // 10) the filter pills still work together with the search
  await page.close();
  page = await open();
  await ev(page, () => { setFilter('all'); });
  await page.fill('#searchInput', 'cal');
  await sleep(60);
  await ev(page, () => { allApps.find(a => a.pkg === 'com.example.calendar').isRunning = true; setFilter('running'); });
  r = await names(page);
  check('10. a filter pill and the search narrow the list together', r.join() === 'Calendar', r.join());
  await ev(page, () => setFilter('all'));

  // 11) keyboard
  await page.fill('#searchInput', '');
  await page.focus('#searchMenuBtn');
  await page.keyboard.press('Enter');
  check('11. Enter on the menu button opens the menu', await menuOpen(page));
  await page.focus('#searchOptRegex');
  await page.keyboard.press('Space');
  check('    Space on a switch flips it', (await opts(page)).regex === false);
  await page.keyboard.press('Space');
  check('    and again', (await opts(page)).regex === true);
  await page.keyboard.press('Escape');

  // 12) screen sizes
  await page.close();
  for (const w of [320, 360, 412]) {
    const p = await open({}, { width: w, height: 760 });
    await ev(p, () => document.getElementById('searchBox').scrollIntoView({ block: 'center' }));
    await p.click('#searchMenuBtn');
    await sleep(80);
    const m = await ev(p, () => {
      const menu = document.getElementById('searchMenu').getBoundingClientRect(), box = document.getElementById('searchBox').getBoundingClientRect();
      const names = Array.from(document.querySelectorAll('#searchMenu .sm-name')).every(n => n.scrollWidth <= n.clientWidth + 1);
      const tracks = Array.from(document.querySelectorAll('#searchMenu .switch-track')).every(t => { const r = t.getBoundingClientRect(); return r.right <= menu.right + 1 && r.width >= 40; });
      const rows = Array.from(document.querySelectorAll('#searchMenu .sm-row')).map(r => Math.round(r.getBoundingClientRect().height));
      return { inside: menu.left >= 0 && menu.right <= innerWidth, width: Math.round(menu.width), names, tracks, minRow: Math.min.apply(null, rows), noSideScroll: document.documentElement.scrollWidth <= innerWidth, inputRoom: Math.round(document.getElementById('searchInput').getBoundingClientRect().width) - 84 };
    });
    check('12. [' + w + ' px] the menu is inside the screen, switches inside it, rows at least 44 px, room left for typing', m.inside && m.names && m.tracks && m.minRow >= 44 && m.noSideScroll && m.inputRoom >= 150, JSON.stringify(m));
    await p.close();
  }

  // 13) a pattern that could take for ever is searched as plain text (the page must not hang on a long name)
  page = await open();
  const hang = await ev(page, () => { const patternMayHang = typeof window.patternMayHang === 'function' ? window.patternMayHang : () => false; return {
    bad: ['(.*)*x', '(.+)+x', '(a+)+$', '((a+))+', '(\\d+)*b', '(a*)*', '(x+x+)+y', '(.*a){12}', '(a|b+)+c', '([a-z]+\\s?)+$', '(a{2,})+', 'x'.repeat(201)].filter(p => !patternMayHang(p)),
    fine: ['^c(a|l)', 'a{2,5}', '(ab)+', '\\d+', '[a-z]+', '^cal.*', '(?:ab)+c', 'a|b', 'c\\+\\+', '(foo|bar)', 'x*y*z*', '[(]', '\\(', '(\\d+\\.){3}\\d+', '(a+)?', '.*cal.*', '(ab)*c+', '[a-z]+@[a-z]+'].filter(p => patternMayHang(p))
  }; });
  check('13. every nested repeat in the table is caught ((a+)+, (.*)*, ((a+))+, a repeat count of 12 over a group with .* ...), and a pattern over 200 characters', hang.bad.length === 0, JSON.stringify(hang.bad));
  check('    ordinary patterns are left alone (anchors, groups, classes, escapes, a small repeat count, alternatives)', hang.fine.length === 0, JSON.stringify(hang.fine));
  await page.close();
  const big = Array.from({ length: 3000 }, (_, i) => ({ pkg: 'com.vendor' + (i % 97) + '.app' + i, name: 'Samsung Calendar Photos for Android ' + i }));
  page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps: big, store: {} });
  await page.goto(PAGE);
  await page.waitForFunction(() => document.getElementById('statTotal').innerText === '3000');
  let t0 = Date.now();
  await page.fill('#searchInput', '(.*)*x');
  await sleep(350);                                                  // a long list waits for a pause in typing before it is searched
  const slow = await ev(page, () => ({ shown: getComputedStyle(document.getElementById('searchNote')).display !== 'none', text: document.getElementById('searchNote').innerText, cards: document.querySelectorAll('#appsListContainer .app-card').length }));
  const took = Date.now() - t0;
  check('    typing (.*)* on 3,000 apps answers at once (under 3 s), says why, and searches the text as it is', took < 3000 && slow.shown && /could freeze/.test(slow.text) && /plain text/.test(slow.text) && slow.cards === 0, JSON.stringify(slow));
  t0 = Date.now();
  await page.fill('#searchInput', 'calendar photos for android 29');
  await sleep(300);
  check('    and an ordinary search on that list is quick (under 3 s) and finds the app', (await ev(page, () => document.querySelectorAll('#appsListContainer .app-card').length)) > 0 && Date.now() - t0 < 3000);
  await page.close();

  // 14) the menu opens in view (not under the bottom of the screen) and the batch button steps aside while it is open
  for (const [w, h] of [[360, 640], [360, 740], [320, 568], [360, 520], [412, 915]]) {
    for (const withSel of [false, true]) {
      const p = await b.newPage({ viewport: { width: w, height: h } });
      p.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
      const many = Array.from({ length: 60 }, (_, i) => ({ pkg: 'com.vendor.app' + i, name: 'Application ' + i }));
      await p.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps: many, store: {} });
      await p.goto(PAGE);
      await p.waitForFunction(() => document.getElementById('statTotal').innerText === '60');
      if (withSel) { await ev(p, () => { selectedPkgs.add('com.vendor.app1'); updateBatchBar(); }); await sleep(400); }
      await p.click('#searchMenuBtn');
      let still = 0, last = -1;
      for (let i = 0; i < 40 && still < 4; i++) { await sleep(60); const y = await ev(p, () => Math.round(scrollY)); still = y === last ? still + 1 : 0; last = y; }
      const m = await ev(p, () => {
        const tabs = document.getElementById('tabBar').getBoundingClientRect();
        const rows = Array.from(document.querySelectorAll('#searchMenu .sm-row')).map(r => { const b = r.getBoundingClientRect(); const hit = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2); return { in: b.top >= tabs.bottom - 1 && b.bottom <= innerHeight, hit: !!hit && hit.closest('.sm-row') === r }; });
        const fab = document.getElementById('batchFab') || document.querySelector('.batch-fab');
        return { rows, fab: fab ? getComputedStyle(fab).visibility : 'none', boxBelowTabs: document.getElementById('searchBox').getBoundingClientRect().top >= tabs.bottom - 1 };
      });
      check('14. [' + w + 'x' + h + (withSel ? ', one app selected' : '') + '] all three rows are on screen and tappable, the search bar is not under the tabs' + (withSel ? ', the batch button is out of the way' : ''), m.rows.length === 3 && m.rows.every(r => r.in && r.hit) && m.boxBelowTabs && (!withSel || m.fab === 'hidden'), JSON.stringify(m));
      await p.click('#searchMenuBtn');
      if (withSel) check('    and it is back when the menu closes', (await ev(p, () => getComputedStyle(document.querySelector('.batch-fab')).visibility)) === 'visible');
      await p.close();
    }
  }

  // 15) when the menu closes: with typing, with the tab, never by a scroll; and the next Back is not swallowed
  page = await open();
  await page.click('#searchMenuBtn');
  await page.mouse.move(180, 300); await page.mouse.wheel(0, 150); await sleep(250);
  check('15. a scroll does not close the menu (a tap does)', await menuOpen(page));
  await ev(page, () => document.querySelector('.brand-text').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'touch' })));          // a finger goes down outside and starts to drag: no tap
  check('    nor does a finger that goes down outside the menu to scroll (only a tap, which ends with a click, does)', await menuOpen(page));
  await page.click('.brand-text');
  check('    a tap outside closes it', !(await menuOpen(page)));
  await page.click('#searchMenuBtn');
  await page.focus('#searchInput');
  check('    going into the box closes it (it would cover the first results)', !(await menuOpen(page)));
  await page.click('#searchMenuBtn');
  await ev(page, () => { const i = document.getElementById('searchInput'); i.value = 'c'; i.dispatchEvent(new Event('input', { bubbles: true })); });       // text arriving while the menu is open (a hardware keyboard, a paste)
  check('    so does text arriving in it', !(await menuOpen(page)));
  await page.fill('#searchInput', '');
  await page.click('#searchMenuBtn');
  await page.focus('.tab-btn[data-tab="files"]'); await page.keyboard.press('Enter');
  const s15 = await ev(page, () => ({ view: currentViewName(), open: searchMenuOpen(), cls: document.body.classList.contains('search-menu-open') }));
  check('    leaving the tab without a tap (the keyboard, or a screen reader) closes it too', s15.view === 'files' && !s15.open && !s15.cls, JSON.stringify(s15));
  await ev(page, () => switchView('apps'));
  await page.click('#searchMenuBtn');
  await ev(page, () => switchView('installer'));
  const back15 = await ev(page, () => { const r = handleAndroidBack(); return { r, view: currentViewName() }; });
  check('    and the next Back goes back a tab (it is not spent on a menu nobody can see)', back15.r === true && back15.view === 'apps', JSON.stringify(back15));
  await page.close();

  // 16) the rows of the menu are not text, the note can be read on the light page, and it is announced once
  page = await open();
  const us = await ev(page, () => ({ row: getComputedStyle(document.querySelector('.sm-row')).userSelect, flSwitch: getComputedStyle(document.querySelector('.fl-switch')).userSelect, flName: getComputedStyle(document.querySelector('.fl-name')).userSelect }));
  check('16. the rows of the menu and of the Feature List cannot be highlighted by a double tap', us.row === 'none' && us.flSwitch === 'none' && us.flName === 'none', JSON.stringify(us));
  const ratio = (a, b2) => { const lum = c => { const v = c.match(/[\d.]+/g).slice(0, 3).map(Number).map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; }; const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  for (const mode of ['dark', 'light']) {
    await ev(page, m => setAppearance(m), mode);
    await page.fill('#searchInput', '(');
    await sleep(80);
    const c = await ev(page, () => ({ fg: getComputedStyle(document.getElementById('searchNote')).color, bg: getComputedStyle(document.body).backgroundColor }));
    const rt = ratio(c.fg, c.bg);
    check('    the note under the bar is readable on the ' + mode + ' page (contrast ' + rt.toFixed(1) + ':1, at least 4.5)', rt >= 4.5, JSON.stringify(c));
  }
  await ev(page, () => { window.__noteChanges = 0; new MutationObserver(r => { window.__noteChanges += r.length; }).observe(document.getElementById('searchNote'), { childList: true, characterData: true, subtree: true }); });
  for (let i = 0; i < 5; i++) await ev(page, () => renderApps());
  await sleep(50);
  check('    drawing the list again with the same bad pattern does not set the note again (a screen reader would read it out each time)', (await ev(page, () => window.__noteChanges)) === 0);
  await page.close();

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
