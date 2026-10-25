// Apps tab: the three-dot menu of the search bar has a fourth and last row, "Row tints", that opens Settings on the Row tints card. It is drawn like the
// switch rows (name, a one-line grey line under it, the divider) with a chevron where they have a switch. A tap or Enter / Space closes the menu (and clears
// body.search-menu-open), makes Settings the current view with the Row tints card in view and lit up for a moment (the helper the bottom bar's shortcuts use),
// the slider has the focus, and Back returns to the Apps tab with its search as it was. The three switches above it are as they were and still work.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }          // (the numbers behind a passing check stay out of the output: they depend on the fonts)
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APPS = [
  { pkg: 'com.example.calendar', name: 'Calendar' },
  { pkg: 'com.example.camera', name: 'Camera' },
  { pkg: 'com.example.clock', name: 'Clock' },
  { pkg: 'org.zz.notes', name: 'Calculator' }
];
const ROW = '#searchMenuRowTints';
const SUB = 'How strongly rows are tinted. Opens Settings.';

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (vp, opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: vp || { width: 360, height: 640 }, hasTouch: true });
    page.bottomNav = !!opts.bottomNav;
    page.on('pageerror', e => { errors.push(e.message); });
    const apps = opts.apps || APPS;
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps, store: opts.store || {} });
    await page.goto(PAGE);
    await page.waitForFunction(n => document.getElementById('countAll').innerText === String(n), apps.length);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const menuOpen = page => ev(page, () => document.getElementById('searchMenu').style.display !== 'none');
  const opts = page => ev(page, () => ({ names: document.getElementById('searchOptNames').checked, packages: document.getElementById('searchOptPackages').checked, regex: document.getElementById('searchOptRegex').checked }));
  const names = page => ev(page, () => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(n => n.innerText).sort().join());
  // the page has stopped scrolling (a smooth scroll takes a moment)
  const still = async page => { let n = 0, last = -1; for (let i = 0; i < 60 && n < 4; i++) { await sleep(60); const y = await ev(page, () => Math.round(scrollY)); n = y === last ? n + 1 : 0; last = y; } };
  // where things are: what the card is under (header, tab bar, bottom bar) and whether it is inside the rest of the screen
  const where = page => ev(page, () => {
    const vis = el => !!el && el.getBoundingClientRect().height > 0 && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
    const card = document.getElementById('rowTintCard'), r = card.getBoundingClientRect();
    const hdr = document.querySelector('.app-header').getBoundingClientRect().bottom, tb = document.getElementById('tabBar'), bn = document.getElementById('bnav');
    const top = Math.max(hdr, vis(tb) ? tb.getBoundingClientRect().bottom : 0), bottom = vis(bn) ? bn.getBoundingClientRect().top : innerHeight;
    return {
      view: currentViewName(), menu: searchMenuOpen(), expanded: document.getElementById('searchMenuBtn').getAttribute('aria-expanded'), cls: document.body.classList.contains('search-menu-open'),
      shown: vis(card), inView: vis(card) && r.top >= top - 1 && r.bottom <= bottom + 1, aboveFold: r.top >= innerHeight, flash: card.classList.contains('bn-flash'),
      focus: document.activeElement ? document.activeElement.id : '', gear: document.getElementById('prefsHeaderBtn').classList.contains('active'), stack: viewStack.join()
    };
  });
  const rowInfo = page => ev(page, () => {
    const row = document.getElementById('searchMenuRowTints');
    if (!row) return null;
    const rows = Array.from(document.querySelectorAll('#searchMenu .sm-row')), menu = document.getElementById('searchMenu').getBoundingClientRect(), r = row.getBoundingClientRect();
    const name = row.querySelector('.sm-name'), sub = row.querySelector('.sm-sub'), svg = row.querySelector('svg'), s = svg ? svg.getBoundingClientRect() : null;
    const track = document.querySelector('#searchMenu .switch-track'), t = track ? track.getBoundingClientRect() : null;
    return {
      last: rows[rows.length - 1] === row, count: rows.length, rowNames: rows.map(x => x.querySelector('.sm-name').innerText).join('|'), name: name ? name.innerText : null, sub: sub ? sub.innerText : null, subs: row.querySelectorAll('.sm-sub').length,
      role: row.getAttribute('role'), tabindex: row.getAttribute('tabindex'), tag: row.tagName, divider: !!row.querySelector('.sm-div'), dividers: document.querySelectorAll('#searchMenu .sm-div').length,
      chevron: !!svg && svg.getAttribute('aria-hidden') === 'true' && !svg.hasAttribute('role'), switchIn: !!row.querySelector('input'),
      insideMenu: r.left >= menu.left - 1 && r.right <= menu.right + 1 && r.bottom <= menu.bottom + 1, tall: r.height >= 44, textFits: name.scrollWidth <= name.clientWidth + 1,
      chevronAtEnd: !!s && !!t && Math.abs(s.right - t.right) <= 1 && s.left >= r.left && s.top >= r.top && s.bottom <= r.bottom, chevronColor: svg ? getComputedStyle(svg).color : '',
      cursor: getComputedStyle(row).cursor, userSelect: getComputedStyle(row).userSelect, lineAbove: getComputedStyle(row).borderTopWidth !== '0px', inScreen: r.left >= 0 && r.right <= innerWidth,
      sameLook: ['display', 'alignItems', 'paddingTop', 'paddingBottom', 'columnGap', 'borderTopStyle'].every(k => getComputedStyle(row)[k] === getComputedStyle(rows[2])[k]),
      fontLook: getComputedStyle(name).fontSize === getComputedStyle(rows[2].querySelector('.sm-name')).fontSize && getComputedStyle(sub).fontSize === getComputedStyle(rows[2].querySelector('.sm-sub')).fontSize && getComputedStyle(sub).color === getComputedStyle(rows[2].querySelector('.sm-sub')).color
    };
  });

  // ---- 1. the row: last, built like the others, the three above it unchanged ----
  let page = await open();
  await page.tap('#searchMenuBtn');
  check('1. the menu opens', await menuOpen(page));
  let r = await rowInfo(page);
  check('   a fourth row is there and it is the last one, after the regex row', !!r && r.last && r.count === 4, JSON.stringify(r));
  check('   the rows are Include application names, Include package names, Use regex matching, Row tints', !!r && r.rowNames === 'Include application names|Include package names|Use regex matching|Row tints', r && r.rowNames);
  check('   its line under the name is "' + SUB + '" (one line of grey text, as in the regex row)', !!r && r.name === 'Row tints' && r.sub === SUB && r.subs === 1 && r.fontLook, JSON.stringify(r));
  check('   it is drawn like the others: same row box, a hairline above, the divider before its end, not selectable text, a pointer', !!r && r.sameLook && r.lineAbove && r.divider && r.dividers === 4 && r.userSelect === 'none' && r.cursor === 'pointer', JSON.stringify(r));
  check('   at its end is a chevron (hidden from a screen reader), not a switch', !!r && r.chevron && !r.switchIn && r.chevronAtEnd, JSON.stringify(r));
  check('   it is a button for a screen reader and a keyboard (role button, tabindex 0, named by its text)', !!r && r.role === 'button' && r.tabindex === '0' && r.tag === 'DIV', JSON.stringify(r));
  check('   it is inside the menu and the screen, at least 44 px tall, and its name fits', !!r && r.insideMenu && r.inScreen && r.tall && r.textFits, JSON.stringify(r));
  const three = await ev(page, () => Array.from(document.querySelectorAll('#searchMenu label.sm-row')).map(l => ({ name: l.querySelector('.sm-name').innerText, sub: l.querySelector('.sm-sub') ? l.querySelector('.sm-sub').innerText : '', input: l.querySelector('input').id, type: l.querySelector('input').type, on: l.querySelector('input').checked, divider: !!l.querySelector('.sm-div'), track: !!l.querySelector('.switch-track') })));
  check('   the three rows above it are as before: labels with their switches, all on, the regex line unchanged', three.length === 3 && three.map(t => t.input).join() === 'searchOptNames,searchOptPackages,searchOptRegex' && three.every(t => t.type === 'checkbox' && t.on && t.divider && t.track) && three[0].sub === '' && three[1].sub === '' && three[2].sub === 'If turned off, applications and packages are matched exactly: the whole name has to be typed.', JSON.stringify(three));

  await page.screenshot({ path: 'rowtints_menu.png' });
  if (!r) { console.log('the row is not there: the rest of the script needs it'); console.log(bad + ' FAILED'); await b.close(); process.exit(1); }

  // ---- 2. the three switches still work, and the menu stays open while they do ----
  const flip = async id => { await page.locator('.sm-row:has(#' + id + ') .sm-name').click(); };
  await flip('searchOptRegex');
  check('2. the regex switch turns off and the menu stays open', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: false }) && await menuOpen(page));
  await flip('searchOptRegex');
  await flip('searchOptNames');
  check('   the names switch turns off (packages stay on) and the menu stays open', JSON.stringify(await opts(page)) === JSON.stringify({ names: false, packages: true, regex: true }) && await menuOpen(page));
  await flip('searchOptPackages');
  check('   turning the last one (packages) off turns names on instead, as before', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: false, regex: true }));
  await flip('searchOptPackages');
  check('   all three are on again, and the new row did not move them', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: true }) && !!(await rowInfo(page) || {}).last);
  await page.focus('#searchOptRegex'); await page.keyboard.press('Space');
  check('   Space on a switch flips it (the keyboard is as before)', (await opts(page)).regex === false);
  await page.keyboard.press('Space');
  check('   a tap on a switch does not open Settings', (await ev(page, () => currentViewName())) === 'apps' && await menuOpen(page));

  // ---- 3. a tap ----
  await page.keyboard.press('Escape');
  await page.fill('#searchInput', 'cal'); await sleep(80);
  const before = await names(page);
  await page.tap('#searchMenuBtn');
  const pre = await ev(page, () => ({ y: Math.round(scrollY), view: currentViewName() }));
  await page.tap(ROW);
  let w = await where(page);
  check('3. a tap on the row closes the menu at once (the button says closed, body.search-menu-open is gone)', !w.menu && w.expanded === 'false' && !w.cls && !(await menuOpen(page)), JSON.stringify(w));
  check('   Settings is the current view and its gear is lit', w.view === 'prefs' && w.gear, JSON.stringify(w));
  await page.waitForFunction(() => document.getElementById('rowTintCard').classList.contains('bn-flash') && document.activeElement && document.activeElement.id === 'rowTintRange', null, { timeout: 3000, polling: 'raf' }).catch(() => {});
  w = await where(page);
  check('   the Row tints card is lit up for a moment (the highlight of the bottom bar\'s shortcuts)', w.flash, JSON.stringify(w));
  check('   the slider of the card has the focus', w.focus === 'rowTintRange', JSON.stringify(w));
  await still(page);
  w = await where(page);
  check('   the card is shown and fully in view, under the header and above the bottom of the screen', w.shown && w.inView, JSON.stringify(w));
  await page.waitForFunction(() => !document.getElementById('rowTintCard').classList.contains('bn-flash'), null, { timeout: 4000 }).catch(() => {});
  check('   the highlight goes away by itself', !(await where(page)).flash);
  await page.screenshot({ path: 'rowtints_after_tap.png' });

  // ---- 4. Back ----
  const stack = (await where(page)).stack;
  const back = await ev(page, () => { const r = handleAndroidBack(); return { r, view: currentViewName(), menu: searchMenuOpen(), cls: document.body.classList.contains('search-menu-open') }; });
  check('4. Back goes back to the Apps tab (the tab the shortcut was used on)', stack === 'apps' && back.r === true && back.view === 'apps' && !back.menu && !back.cls, JSON.stringify({ stack, back }));
  check('   with the search as it was (text and list), and the menu closed', (await ev(page, () => document.getElementById('searchInput').value)) === 'cal' && (await names(page)) === before);
  const again = await ev(page, () => { const r = handleAndroidBack(); return { r, view: currentViewName() }; });
  check('   and the Back after that is the usual one (the first tab: it is not spent on a menu or on Settings)', again.view === 'apps' && !(await menuOpen(page)), JSON.stringify(again));
  await page.fill('#searchInput', ''); await sleep(60);

  // ---- 5. the card is not just near the top: from a Settings page scrolled to its end, from an Apps tab scrolled down ----
  await ev(page, () => switchView('prefs')); await sleep(150);
  await ev(page, () => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' })); await sleep(150);
  const farY = await ev(page, () => Math.round(scrollY));
  await ev(page, () => switchView('apps')); await sleep(150);
  await ev(page, () => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.tap('#searchMenuBtn');
  await page.tap(ROW); await still(page);
  w = await where(page);
  check('5. with Settings left scrolled to its end, the card is brought into view all the same', farY > 400 && w.view === 'prefs' && w.shown && w.inView, JSON.stringify({ farY, w }));
  await ev(page, () => { handleAndroidBack(); });
  await ev(page, () => { switchView('prefs'); window.scrollTo({ top: 0, behavior: 'instant' }); switchView('apps'); }); await sleep(100);
  const top0 = await ev(page, () => { switchView('prefs'); const r = document.getElementById('rowTintCard').getBoundingClientRect(); const out = { below: r.top >= innerHeight - 40 }; switchView('apps'); return out; });
  await page.tap('#searchMenuBtn'); await page.tap(ROW); await still(page);
  w = await where(page);
  check('   and from Settings scrolled to its top, where the card is not on the screen yet', top0.below && w.view === 'prefs' && w.shown && w.inView, JSON.stringify({ top0, w }));
  await ev(page, () => { handleAndroidBack(); });

  // ---- 6. the keyboard: Tab reaches the row, a focus ring shows, Enter and Space do what a tap does ----
  await sleep(100);
  await ev(page, () => switchView('apps'));
  await page.focus('#searchMenuBtn'); await page.keyboard.press('Enter');
  check('6. Enter on the menu button opens the menu', await menuOpen(page));
  // what the row's own onkeydown did with each key: a listener on the row itself, added after the inline handler, so it runs right after it and before anything above the row (a Space left alone scrolls the page)
  await ev(page, () => { window.__kd = []; document.getElementById('searchMenuRowTints').addEventListener('keydown', e => window.__kd.push({ key: e.key, prevented: e.defaultPrevented })); });
  const kd = key => ev(page, k => (window.__kd || []).filter(x => x.key === k).map(x => x.prevented), key);
  await page.focus('#searchOptRegex'); await page.keyboard.press('Tab');
  const tabbed = await ev(page, () => { const a = document.activeElement, cs = getComputedStyle(a); return { id: a.id, ring: cs.outlineStyle === 'solid' && parseFloat(cs.outlineWidth) >= 2 }; });
  check('   Tab from the last switch reaches the row, and it shows a focus ring', tabbed.id === 'searchMenuRowTints' && tabbed.ring, JSON.stringify(tabbed));
  await page.screenshot({ path: 'rowtints_menu_focus.png' });
  await page.keyboard.press('Enter');
  w = await where(page);
  check('   Enter closes the menu and opens Settings', !w.menu && w.expanded === 'false' && !w.cls && w.view === 'prefs', JSON.stringify(w));
  check('   and the row\'s handler took the Enter key (its default action is prevented)', (await kd('Enter')).join() === 'true', JSON.stringify(await kd('Enter')));
  await still(page);
  w = await where(page);
  check('   with the card in view and the slider focused (a keyboard goes on from there with the arrow keys)', w.shown && w.inView && w.focus === 'rowTintRange', JSON.stringify(w));
  const tint0 = await ev(page, () => rowTintLevel());
  await page.keyboard.press('ArrowRight');
  const tint1 = await ev(page, () => rowTintLevel());
  check('   an arrow key changes Row tints right there', tint1 === tint0 + 1 || tint1 === 4 && tint0 === 4, tint0 + ' -> ' + tint1);
  await ev(page, () => { setRowTint(String(2)); });
  await ev(page, () => { handleAndroidBack(); });
  check('   Back returns to the Apps tab', (await ev(page, () => currentViewName())) === 'apps');
  await page.focus('#searchMenuBtn'); await page.keyboard.press('Enter');
  await page.focus(ROW);
  await page.keyboard.press('Space');
  w = await where(page);
  check('   Space does the same as Enter', !w.menu && !w.cls && w.view === 'prefs', JSON.stringify(w));
  check('   and the row\'s handler took the Space key too: its default action (scrolling the page) is prevented', (await kd(' ')).join() === 'true', JSON.stringify(await kd(' ')));
  await still(page);
  w = await where(page);
  check('   the card is in view after Space too', w.shown && w.inView && w.focus === 'rowTintRange', JSON.stringify(w));
  await ev(page, () => { handleAndroidBack(); });
  await page.focus('#searchMenuBtn'); await page.keyboard.press('Enter');
  await page.focus(ROW); await page.keyboard.press('a'); await page.keyboard.press('Tab');
  check('   other keys on the row do nothing (the menu is still open) and are left alone (not prevented)', await menuOpen(page) && (await ev(page, () => currentViewName())) === 'apps' && (await kd('a')).join() === 'false' && (await kd('Tab')).join() === 'false', JSON.stringify({ a: await kd('a'), tab: await kd('Tab') }));
  await page.keyboard.press('Escape');
  check('   Escape still closes the menu and the focus goes back to its button', !(await menuOpen(page)) && (await ev(page, () => document.activeElement.id)) === 'searchMenuBtn');
  await page.close();

  // ---- 7. used twice, the second time is as good as the first ----
  page = await open();
  await page.tap('#searchMenuBtn'); await page.tap(ROW); await still(page);
  await ev(page, () => { handleAndroidBack(); });
  await page.tap('#searchMenuBtn'); await page.tap(ROW); await still(page);
  w = await where(page);
  check('7. used twice in a row it does the same again (one Back goes back to the Apps tab)', w.view === 'prefs' && w.shown && w.inView && w.stack === 'apps', JSON.stringify(w));
  await ev(page, () => { handleAndroidBack(); });
  check('   and the menu is closed when you are back', !(await menuOpen(page)) && (await ev(page, () => currentViewName())) === 'apps' && (await where(page)).stack === '');
  await page.close();

  // ---- 8. screen sizes: the row fits at 320, 360 and 412 px, on a tall and on a short screen, with the top tab bar alone (the harness default) and with the bottom bar (the app's default) ----
  // The menu is brought into view when it opens: above the bottom bar when that is shown (the extra row would be partly under it on a short screen otherwise).
  const MANY = Array.from({ length: 60 }, (_, i) => ({ pkg: 'com.vendor.app' + i, name: 'Application ' + i }));          // a long list: the page can scroll, which is what opens the menu in view
  for (const [wd, ht, bottomNav] of [[320, 568], [360, 640], [360, 520], [412, 915], [320, 568, true], [360, 520, true], [360, 640, true], [360, 720, true], [412, 915, true]]) {
    const p = await open({ width: wd, height: ht }, { apps: MANY, bottomNav: !!bottomNav });
    await p.tap('#searchMenuBtn'); await still(p);
    const m = await ev(p, () => {
      const vis = el => !!el && el.getBoundingClientRect().height > 0 && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
      const row = document.getElementById('searchMenuRowTints'), b = row.getBoundingClientRect(), tabs = document.getElementById('tabBar'), bar = document.getElementById('bnav');
      const top = Math.max(document.querySelector('.app-header').getBoundingClientRect().bottom, vis(tabs) ? tabs.getBoundingClientRect().bottom : 0), bottom = vis(bar) ? bar.getBoundingClientRect().top : innerHeight;
      const hit = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
      const lastLine = document.elementFromPoint(b.left + b.width / 2, b.bottom - 2);          // the low edge of the row too: its second text line is not hidden under the bar
      const menu = document.getElementById('searchMenu').getBoundingClientRect();
      return { barShown: vis(bar), onScreen: b.top >= top - 1 && b.bottom <= bottom + 1, hit: !!hit && hit.closest('.sm-row') === row, lastLine: !!lastLine && lastLine.closest('.sm-row') === row, inside: menu.left >= 0 && menu.right <= innerWidth, noSideScroll: document.documentElement.scrollWidth <= innerWidth,
        nameFits: row.querySelector('.sm-name').scrollWidth <= row.querySelector('.sm-name').clientWidth + 1, subFits: row.querySelector('.sm-sub').scrollWidth <= row.querySelector('.sm-sub').clientWidth + 1 };
    });
    const tag = '[' + wd + 'x' + ht + (bottomNav ? ', bottom bar' : '') + ']';
    check('8. ' + tag + ' the row is on screen' + (bottomNav ? ' above the bottom bar (the bar is really shown)' : ' under the tab bar') + ', tappable (nothing covers it, its low edge neither), inside the menu, and its texts fit', (!bottomNav || m.barShown) && m.onScreen && m.hit && m.lastLine && m.inside && m.noSideScroll && m.nameFits && m.subFits, JSON.stringify(m));
    await p.tap(ROW); await still(p);
    const x = await where(p);
    check('   and it takes you to the card, in view', x.view === 'prefs' && x.shown && x.inView && !x.menu, JSON.stringify(x));
    await p.close();
  }
  // a short screen with the bottom bar: the page scrolls to show the menu, but never so far that the search box (and its button) goes under the header, which is all there is at the top then
  {
    const p = await open({ width: 360, height: 480 }, { apps: MANY, bottomNav: true });
    await p.tap('#searchMenuBtn'); await still(p);
    const m = await ev(p, () => {
      const hdr = document.querySelector('.app-header').getBoundingClientRect().bottom, btn = document.getElementById('searchMenuBtn'), bb = btn.getBoundingClientRect();
      const hit = document.elementFromPoint(bb.left + bb.width / 2, bb.top + bb.height / 2), row = document.getElementById('searchMenuRowTints'), r = row.getBoundingClientRect();
      const bar = document.getElementById('bnav').getBoundingClientRect(), mid = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return { boxBelowHeader: document.getElementById('searchBox').getBoundingClientRect().top >= hdr - 1, btnHit: hit === btn, rowHit: !!mid && mid.closest('.sm-row') === row, rowAboveBar: r.top < bar.top };
    });
    check('   [360x480, bottom bar] the search box and its button stay under the header, and the row can still be tapped', m.boxBelowHeader && m.btnHit && m.rowHit && m.rowAboveBar, JSON.stringify(m));
    await p.close();
  }

  // ---- 9. with the bottom navigation bar (the app's default): the card ends up above the bar ----
  page = await open({ width: 360, height: 640 }, { bottomNav: true });
  const bn = await ev(page, () => { const n = document.getElementById('bnav'); return !!n && n.getBoundingClientRect().height > 0 && getComputedStyle(n).display !== 'none'; });
  await page.tap('#searchMenuBtn'); await page.tap(ROW); await still(page);
  w = await where(page);
  check('9. with the bottom bar on, the shortcut goes to Settings and the card is in view above the bar', bn && w.view === 'prefs' && w.shown && w.inView && !w.menu, JSON.stringify({ bn, w }));
  await page.screenshot({ path: 'rowtints_bottom_bar.png' });
  await ev(page, () => { handleAndroidBack(); });
  check('   Back comes back to the Apps tab', (await ev(page, () => currentViewName())) === 'apps');
  await page.close();

  // ---- 10. the language: the row is translated, the chevron points the way the language reads ----
  page = await open();
  await ev(page, () => I18N.set('de'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'Zeilenfärbung', null, { timeout: 5000 }).catch(() => {});
  await page.tap('#searchMenuBtn');
  const de = await rowInfo(page);
  check('10. in German the row says Zeilenfärbung with its German line (and still is the last row)', !!de && de.last && de.name === 'Zeilenfärbung' && de.sub === 'Wie stark Zeilen gefärbt werden. Öffnet die Einstellungen.', JSON.stringify(de));
  await page.tap(ROW); await still(page);
  w = await where(page);
  check('    and it still goes to the card', w.view === 'prefs' && w.shown && w.inView, JSON.stringify(w));
  await ev(page, () => I18N.set('ar'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'تلوين الصفوف' && document.documentElement.dir === 'rtl', null, { timeout: 5000 }).catch(() => {});
  await ev(page, () => { handleAndroidBack(); });
  await page.tap('#searchMenuBtn');
  const ar = await ev(page, () => { const row = document.getElementById('searchMenuRowTints'), svg = row.querySelector('svg'); return { dir: document.documentElement.dir, name: row.querySelector('.sm-name').innerText, flipped: getComputedStyle(svg).transform, last: Array.from(document.querySelectorAll('#searchMenu .sm-row')).pop() === row }; });
  check('    in Arabic (right to left) the row is translated and the chevron is turned to point left', ar.dir === 'rtl' && ar.name === 'تلوين الصفوف' && ar.last && /^matrix\(-1,/.test(ar.flipped), JSON.stringify(ar));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  process.exit(bad ? 1 : 0);
})();
