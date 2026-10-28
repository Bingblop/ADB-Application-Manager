// Apps tab: the three-dot menu of the search bar ends with the Row tints setting itself (the owner's wish, v7.18.1): a "Row tints" row with the five-step
// slider of Settings > Row tints (Off, Faint, Subtle, Normal, Vivid) under its name, then a "Gradient" row with the Gradient switch, drawn like the switch rows
// above it. They are the same setting as Settings: a change in the menu shows at once in the list behind it and in Settings, a change in Settings shows in the
// menu, and the menu stays open while they are used. The Gradient switch is off by default. The three search switches above them are as they were and still work.
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
  // the setting as the page has it, and as both places show it
  const tint = page => ev(page, () => ({
    lv: rowTintLevel(), attr: document.documentElement.getAttribute('data-row-tint'), grad: document.documentElement.getAttribute('data-row-grad'),
    menuLv: document.getElementById('searchRowTintRange').value, prefsLv: document.getElementById('rowTintRange').value,
    menuGrad: document.getElementById('searchRowTintGrad').checked, prefsGrad: document.getElementById('rowTintGradToggle').checked, view: currentViewName()
  }));
  const still = async page => { let n = 0, last = -1; for (let i = 0; i < 60 && n < 4; i++) { await sleep(60); const y = await ev(page, () => Math.round(scrollY)); n = y === last ? n + 1 : 0; last = y; } };
  const rowsInfo = page => ev(page, () => {
    const rows = Array.from(document.querySelectorAll('#searchMenu .sm-row')), menu = document.getElementById('searchMenu').getBoundingClientRect();
    const tintRow = document.getElementById('searchMenuRowTints'), gradRow = document.getElementById('searchMenuRowGrad');
    if (!tintRow || !gradRow) return null;
    const range = tintRow.querySelector('input[type="range"]'), ticks = Array.from(tintRow.querySelectorAll('.row-tint-ticks span')).map(s => s.innerText);
    const gIn = gradRow.querySelector('input'), r = tintRow.getBoundingClientRect(), g = gradRow.getBoundingClientRect(), rr = range.getBoundingClientRect();
    return {
      count: rows.length, names: rows.map(x => x.querySelector('.sm-name').innerText).join('|'), tintSecondLast: rows[rows.length - 2] === tintRow, gradLast: rows[rows.length - 1] === gradRow,
      range: range.id, min: range.min, max: range.max, step: range.step, label: range.getAttribute('aria-label'), ticks: ticks.join(','),
      rangeUnderName: rr.top >= tintRow.querySelector('.sm-name').getBoundingClientRect().bottom - 1, rangeWide: rr.width >= r.width * 0.8,
      gradTag: gradRow.tagName, gradType: gIn && gIn.type, gradId: gIn && gIn.id, gradTrack: !!gradRow.querySelector('.switch-input + .switch-track'), gradDiv: !!gradRow.querySelector('.sm-div'),
      gradSameLook: ['display', 'alignItems', 'paddingTop', 'paddingBottom', 'columnGap', 'borderTopStyle', 'cursor'].every(k => getComputedStyle(gradRow)[k] === getComputedStyle(rows[2])[k]),
      lines: getComputedStyle(tintRow).borderTopWidth !== '0px' && getComputedStyle(gradRow).borderTopWidth !== '0px', noChevron: !document.querySelector('#searchMenu svg'),
      inside: [r, g].every(x => x.left >= menu.left - 1 && x.right <= menu.right + 1 && x.bottom <= menu.bottom + 1), tall: g.height >= 44, inScreen: menu.left >= 0 && menu.right <= innerWidth,
      fits: [tintRow, gradRow].every(x => { const n = x.querySelector('.sm-name'); return n.scrollWidth <= n.clientWidth + 1; })
    };
  });

  // ---- 1. the rows: Row tints with its slider, then Gradient with its switch, at the end of the menu ----
  let page = await open();
  await page.tap('#searchMenuBtn');
  check('1. the menu opens', await menuOpen(page));
  let r = await rowsInfo(page);
  check('   its rows are Include application names, Include package names, Use regex matching, Row tints, Gradient', !!r && r.count === 5 && r.names === 'Include application names|Include package names|Use regex matching|Row tints|Gradient', JSON.stringify(r));
  check('   Row tints holds the slider of Settings (five steps, 0 to 4, named Row tints) under its name, across the row', !!r && r.tintSecondLast && r.range === 'searchRowTintRange' && r.min === '0' && r.max === '4' && r.step === '1' && r.label === 'Row tints' && r.ticks === 'Off,Faint,Subtle,Normal,Vivid' && r.rangeUnderName && r.rangeWide, JSON.stringify(r));
  check('   Gradient is the last row, a switch row like the ones above (label, checkbox, track, divider, same row box)', !!r && r.gradLast && r.gradTag === 'LABEL' && r.gradType === 'checkbox' && r.gradId === 'searchRowTintGrad' && r.gradTrack && r.gradDiv && r.gradSameLook, JSON.stringify(r));
  check('   both have the hairline above them, there is no chevron any more, and they are inside the menu and the screen, their names fit', !!r && r.lines && r.noChevron && r.inside && r.inScreen && r.tall && r.fits, JSON.stringify(r));
  let t = await tint(page);
  check('   at first: Subtle in both places, and the Gradient switch off in both places (off by default)', t.lv === 2 && t.attr === 'subtle' && t.menuLv === '2' && t.prefsLv === '2' && t.grad === 'off' && !t.menuGrad && !t.prefsGrad, JSON.stringify(t));
  const three = await ev(page, () => Array.from(document.querySelectorAll('#searchMenu label.sm-row')).slice(0, 3).map(l => ({ input: l.querySelector('input').id, on: l.querySelector('input').checked, sub: l.querySelector('.sm-sub') ? l.querySelector('.sm-sub').innerText : '' })));
  check('   the three search rows above them are as before: their switches, all on, the regex line unchanged', three.map(x => x.input).join() === 'searchOptNames,searchOptPackages,searchOptRegex' && three.every(x => x.on) && three[2].sub === 'If turned off, applications and packages are matched exactly: the whole name has to be typed.', JSON.stringify(three));
  await page.screenshot({ path: 'rowtints_menu.png' });
  if (!r) { console.log('the rows are not there: the rest of the script needs them'); console.log(bad + ' FAILED'); await b.close(); process.exit(1); }

  // ---- 2. the three search switches still work, and the menu stays open while they do ----
  const flip = async id => { await page.locator('.sm-row:has(#' + id + ') .sm-name').click(); };
  await flip('searchOptRegex');
  check('2. the regex switch turns off and the menu stays open', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: false }) && await menuOpen(page));
  await flip('searchOptRegex');
  await flip('searchOptNames');
  check('   the names switch turns off (packages stay on) and the menu stays open', JSON.stringify(await opts(page)) === JSON.stringify({ names: false, packages: true, regex: true }) && await menuOpen(page));
  await flip('searchOptPackages');
  check('   turning the last one (packages) off turns names on instead, as before', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: false, regex: true }));
  await flip('searchOptPackages');
  check('   all three are on again, and the Row tints did not change', JSON.stringify(await opts(page)) === JSON.stringify({ names: true, packages: true, regex: true }) && (await tint(page)).lv === 2);

  // ---- 3. the slider: by keyboard and by a tap on the track, the menu stays open, the list and Settings follow ----
  await page.focus('#searchRowTintRange');
  await page.keyboard.press('ArrowRight');
  t = await tint(page);
  check('3. an arrow key on the menu\'s slider sets Normal: the rows behind the menu, Settings\' slider and the saved choice follow', t.lv === 3 && t.attr === 'normal' && t.menuLv === '3' && t.prefsLv === '3' && t.view === 'apps' && await menuOpen(page) && (await ev(page, () => kvGet('row_tint_lv', -1))) === 3, JSON.stringify(t));
  const box = await ev(page, () => { const e = document.getElementById('searchRowTintRange').getBoundingClientRect(); return { x: e.left, y: e.top + e.height / 2, w: e.width }; });
  await page.mouse.click(box.x + 3, box.y);
  t = await tint(page);
  check('   a tap at the start of its track sets Off, and the menu stays open on the Apps tab', t.lv === 0 && t.attr === 'off' && t.prefsLv === '0' && t.view === 'apps' && await menuOpen(page), JSON.stringify(t));
  await page.mouse.click(box.x + box.w - 3, box.y);
  t = await tint(page);
  check('   a tap at its end sets Vivid', t.lv === 4 && t.attr === 'vivid' && t.prefsLv === '4' && await menuOpen(page), JSON.stringify(t));
  const glow = await ev(page, () => { const c = document.querySelector('#appsListContainer .app-card'); return c ? getComputedStyle(c).getPropertyValue('--rt-o').trim() : 'none'; });
  check('   the list behind the menu is tinted at the new strength right away (--rt-o of Vivid)', glow !== '' && glow !== 'none', glow);

  // ---- 4. the Gradient switch: a tap on its row turns it on and off, the menu stays open, Settings follows ----
  await page.locator('#searchMenuRowGrad .sm-name').click();
  t = await tint(page);
  check('4. a tap on the Gradient row turns the gradient on: html data-row-grad="on", Settings\' switch on too, saved, the menu still open', t.grad === 'on' && t.menuGrad && t.prefsGrad && await menuOpen(page) && (await ev(page, () => kvGet('row_tint_grad', null))) === true, JSON.stringify(t));
  await page.focus('#searchRowTintGrad'); await page.keyboard.press('Space');
  t = await tint(page);
  check('   Space on it turns it off again, in both places', t.grad === 'off' && !t.menuGrad && !t.prefsGrad && await menuOpen(page) && (await ev(page, () => kvGet('row_tint_grad', null))) === false, JSON.stringify(t));
  await page.keyboard.press('Escape');
  check('   Escape still closes the menu and the focus goes back to its button', !(await menuOpen(page)) && (await ev(page, () => document.activeElement.id)) === 'searchMenuBtn');

  // ---- 5. a change made in Settings shows in the menu ----
  await ev(page, () => switchView('prefs')); await sleep(150);
  await ev(page, () => { const r = document.getElementById('rowTintRange'); r.value = '1'; r.dispatchEvent(new Event('input', { bubbles: true })); });
  await page.click('#rowTintGradRow .switch-track');
  await ev(page, () => { handleAndroidBack(); }); await sleep(150);
  await page.tap('#searchMenuBtn');
  t = await tint(page);
  check('5. set in Settings (Faint, Gradient on), the menu shows the same when it opens', t.view === 'apps' && t.lv === 1 && t.menuLv === '1' && t.menuGrad && t.grad === 'on' && await menuOpen(page), JSON.stringify(t));
  await page.close();

  // ---- 6. kept across a reload, and a choice saved before (Gradient on) is kept ----
  page = await open(null, { kv: { row_tint_lv: '4', row_tint_grad: 'true' } });
  await page.tap('#searchMenuBtn');
  t = await tint(page);
  check('6. a saved choice (Vivid, Gradient on) is what the menu and Settings show after a start', t.lv === 4 && t.menuLv === '4' && t.prefsLv === '4' && t.grad === 'on' && t.menuGrad && t.prefsGrad, JSON.stringify(t));
  await page.close();

  // ---- 7. screen sizes: the two rows fit at 320, 360 and 412 px, on a tall and on a short screen, with the top tab bar alone and with the bottom bar ----
  const MANY = Array.from({ length: 60 }, (_, i) => ({ pkg: 'com.vendor.app' + i, name: 'Application ' + i }));
  for (const [wd, ht, bottomNav] of [[320, 568], [360, 640], [360, 520], [412, 915], [320, 568, true], [360, 520, true], [360, 640, true], [360, 720, true], [412, 915, true]]) {
    const p = await open({ width: wd, height: ht }, { apps: MANY, bottomNav: !!bottomNav });
    await p.tap('#searchMenuBtn'); await still(p);
    const m = await ev(p, () => {
      const vis = el => !!el && el.getBoundingClientRect().height > 0 && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
      const tabs = document.getElementById('tabBar'), bar = document.getElementById('bnav');
      const top = Math.max(document.querySelector('.app-header').getBoundingClientRect().bottom, vis(tabs) ? tabs.getBoundingClientRect().bottom : 0), bottom = vis(bar) ? bar.getBoundingClientRect().top : innerHeight;
      const hits = el => { const b = el.getBoundingClientRect(), h = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2); return !!h && (h === el || el.contains(h)); };
      // on a short screen the menu is cut to the room there is and scrolls inside: its end is scrolled to (as a finger would) before the rows are looked at
      const el = document.getElementById('searchMenu'), scrolls = el.scrollHeight > el.clientHeight + 1;
      if (scrolls) el.scrollTop = el.scrollHeight;
      const range = document.getElementById('searchRowTintRange'), grad = document.getElementById('searchMenuRowGrad'), g = grad.getBoundingClientRect(), rr = range.getBoundingClientRect();
      const menu = el.getBoundingClientRect();
      return { barShown: vis(bar), scrolls, menuOnScreen: menu.top >= top - 1 && menu.bottom <= bottom + 1, onScreen: rr.top >= top - 1 && g.bottom <= bottom + 1, sliderHit: hits(range), gradHit: hits(grad), inside: menu.left >= 0 && menu.right <= innerWidth, noSideScroll: document.documentElement.scrollWidth <= innerWidth,
        ticksFit: Array.from(document.querySelectorAll('#searchMenuRowTints .row-tint-ticks span')).every(s => s.getBoundingClientRect().right <= menu.right + 1 && s.getBoundingClientRect().left >= menu.left - 1) };
    });
    const tag = '[' + wd + 'x' + ht + (bottomNav ? ', bottom bar' : '') + ']';
    check('7. ' + tag + ' the menu is all on screen' + (bottomNav ? ' above the bottom bar (the bar is really shown)' : ' under the tab bar') + ' (scrolling inside when the screen is short), the slider and the Gradient row can be reached and nothing covers them, the menu and its step names fit', (!bottomNav || m.barShown) && m.menuOnScreen && m.onScreen && m.sliderHit && m.gradHit && m.inside && m.noSideScroll && m.ticksFit, JSON.stringify(m));
    if (wd === 360 && ht === 640 && !bottomNav) check('   [360x640] the menu fits without scrolling inside', !m.scrolls, JSON.stringify(m));
    await p.close();
  }
  // a short screen with the bottom bar: the page scrolls to show the menu, but never so far that the search box (and its button) goes under the header
  {
    const p = await open({ width: 360, height: 480 }, { apps: MANY, bottomNav: true });
    await p.tap('#searchMenuBtn'); await still(p);
    const m = await ev(p, () => {
      const hdr = document.querySelector('.app-header').getBoundingClientRect().bottom, btn = document.getElementById('searchMenuBtn'), bb = btn.getBoundingClientRect();
      const hit = document.elementFromPoint(bb.left + bb.width / 2, bb.top + bb.height / 2);
      return { boxBelowHeader: document.getElementById('searchBox').getBoundingClientRect().top >= hdr - 1, btnHit: hit === btn };
    });
    check('   [360x480, bottom bar] the search box and its button stay under the header', m.boxBelowHeader && m.btnHit, JSON.stringify(m));
    await p.close();
  }

  // ---- 8. the language: the rows are translated (the words of Settings' Row tints card) ----
  page = await open();
  await ev(page, () => I18N.set('de'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'Zeilenfärbung', null, { timeout: 5000 }).catch(() => {});
  await page.tap('#searchMenuBtn');
  const de = await ev(page, () => ({ tint: document.querySelector('#searchMenuRowTints .sm-name').textContent, grad: document.querySelector('#searchMenuRowGrad .sm-name').textContent, prefsGrad: document.querySelector('#rowTintGradRow .switch-title').textContent, off: document.querySelector('#searchMenuRowTints .row-tint-ticks span').textContent, prefsOff: document.querySelector('#rowTintCard .row-tint-ticks span').textContent }));
  check('8. in German the rows say Zeilenfärbung and the same word for Gradient as Settings, and the step names are translated', de.tint === 'Zeilenfärbung' && de.grad === de.prefsGrad, JSON.stringify(de));
  check('   the step names are the ones of Settings\' card', de.off === de.prefsOff && de.off !== 'Off', JSON.stringify(de));
  await ev(page, () => I18N.set('ar'));
  await page.waitForFunction(() => document.querySelector('#searchMenuRowTints .sm-name').textContent === 'تلوين الصفوف' && document.documentElement.dir === 'rtl', null, { timeout: 5000 }).catch(() => {});
  const ar = await ev(page, () => ({ dir: document.documentElement.dir, name: document.querySelector('#searchMenuRowTints .sm-name').innerText, inScreen: (() => { const m = document.getElementById('searchMenu').getBoundingClientRect(); return m.left >= 0 && m.right <= innerWidth; })() }));
  check('   in Arabic (right to left) the row is translated and the menu stays on the screen', ar.dir === 'rtl' && ar.name === 'تلوين الصفوف' && ar.inScreen, JSON.stringify(ar));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  process.exit(bad ? 1 : 0);
})();
