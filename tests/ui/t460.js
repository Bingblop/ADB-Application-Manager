// v7.14.0: every tab of the single-app sheet (Permissions, App Ops, Activities, Components ... Libraries) has a round button at the right end of its
// chip row (or of its search row) that lets the tab fill the screen. At full screen the app's header and the menu buttons step aside; a slim bar
// with the tab's name, the app's name and a ✕ is on top, the tab strip stays (another tab opens at full screen too), and the list below is the
// same live list (search, chips, rows). The ✕, a pull down by the handle and Back go back to the menu, on the tab that is open then, with the
// search text, the chip, the list's scroll and the menu's scroll kept. Closing the whole menu leaves the full screen too.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sheet_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const TABS = [['perms', 'Permissions'], ['ops', 'App Ops'], ['acts', 'Activities'], ['comps', 'Components'], ['manifest', 'Manifest'], ['raw', 'Raw'],
  ['features', 'Features'], ['configs', 'Configurations'], ['sigs', 'Signatures'], ['libs', 'Libraries']];
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [w, h] of [[360, 800], [412, 915]]) {
    const tag = `${w}x${h}`;
    const ctx = await b.newContext({ viewport: { width: w, height: h }, hasTouch: true, colorScheme: 'dark' });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(tag + ' ' + e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(tag + ' console: ' + m.text()); });
    await page.addInitScript(mock.initScript, { dark: true });
    await page.goto(PAGE); await page.waitForTimeout(450);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const open = async pkg => { await ev(p => { closeInspector(); openInspector(p); }, pkg); await page.waitForTimeout(600); };
    const full = () => ev(() => document.querySelector('#inspectorModal .modal-sheet').classList.contains('tab-full'));
    const shown = () => ev(() => document.getElementById('inspectorModal').classList.contains('show'));
    const curTab = () => ev(() => { const p = document.querySelector('#inspectorModal .sheet-tab-pill.active'); return p && p.getAttribute('data-tab'); });
    const rows = id => ev(i => document.querySelectorAll('#' + i + ' .perm-row').length, id);
    const tapSel = async sel => { const r = await ev(s => { const e = document.querySelector(s); e.scrollIntoView({ block: 'nearest', inline: 'nearest' }); const q = e.getBoundingClientRect(); return { x: q.left + q.width / 2, y: q.top + q.height / 2 }; }, sel); await page.touchscreen.tap(r.x, r.y); await page.waitForTimeout(350); };
    // a pull of the handle with real touch events, from the top middle of the sheet
    const pullDown = (dy) => page.evaluate(async (dy) => {
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const sh = document.querySelector('#inspectorModal .modal-sheet'), r = sh.getBoundingClientRect();
      const x = r.left + r.width / 2, y0 = r.top + 12, target = document.elementFromPoint(x, y0) || sh;
      const mk = (type, y) => new TouchEvent(type, { bubbles: true, cancelable: true, touches: type === 'touchend' ? [] : [new Touch({ identifier: 1, target, clientX: x, clientY: y })], changedTouches: [new Touch({ identifier: 1, target, clientX: x, clientY: y })] });
      target.dispatchEvent(mk('touchstart', y0));
      for (let i = 1; i <= 8; i++) { await sleep(16); target.dispatchEvent(mk('touchmove', y0 + dy * i / 8)); }
      target.dispatchEvent(mk('touchend', y0 + dy));
      await sleep(400);
      return target.className || target.tagName;
    }, dy);

    await open('com.sec.android.app.sbrowser');

    // 1. every tab has its button, at the right end of its chip row (Libraries: of its search row; Raw, Configurations, Signatures: a row of their own)
    const btns = [];
    for (const [tab, name] of TABS) {
      await ev(t => switchSheetTab(t, true), tab); await page.waitForTimeout(80);
      btns.push(await ev(([t, n]) => {
        const panel = document.querySelector('.sheet-tab-content.active'), bt = panel.querySelector('.sheet-full-btn');
        if (!bt) return { t, missing: true };
        const r = bt.getBoundingClientRect(), row = bt.parentElement, rr = row.getBoundingClientRect(), pr = panel.getBoundingClientRect();
        const before = Array.from(row.children).filter(c => c !== bt && c.offsetParent).map(c => c.getBoundingClientRect());
        return { t, label: bt.getAttribute('aria-label'), title: bt.getAttribute('title'), want: 'Expand ' + n, w: Math.round(r.width), h: Math.round(r.height),
          inRow: row.classList.contains('chip-row') || row.classList.contains('list-toolbar'), last: row.lastElementChild === bt,
          rightEnd: Math.abs(r.right - rr.right) <= 6 && r.right <= pr.right + 6, rightOfAll: before.every(c => c.bottom <= r.top + 1 || c.right <= r.left + 1),
          hasIcon: !!bt.querySelector('svg'), type: bt.type };
      }, [tab, name]));
    }
    const badBtns = btns.filter(x => x.missing || x.label !== x.want || x.title !== x.want || x.w !== 44 || x.h !== 44 || !x.inRow || !x.last || !x.rightEnd || !x.rightOfAll || !x.hasIcon || x.type !== 'button');
    check(`1. [${tag}] all ${TABS.length} tabs have a 44px round expand button named after the tab, at the right end of their chip row`, btns.length === 10 && badBtns.length === 0, JSON.stringify(badBtns));

    // 2. Permissions: search, a chip, both lists scrolled, then the button
    await ev(() => switchSheetTab('perms', true));
    const allPerms = await rows('permsContainer');
    await page.fill('#permsSearch', 'read'); await page.waitForTimeout(100);
    const readPerms = await rows('permsContainer');
    const before = await ev(() => {
      const sh = document.querySelector('#inspectorModal .modal-sheet'); sh.scrollTop = 120;
      return { sheet: sh.scrollTop };
    });
    await tapSel('#sheetTabPerms .sheet-full-btn');
    const g = await ev(() => {
      const sh = document.querySelector('#inspectorModal .modal-sheet'), r = sh.getBoundingClientRect();
      const vis = id => { const e = document.getElementById(id); return !!(e && e.offsetParent && e.getBoundingClientRect().height > 0); };
      const tc = document.getElementById('sheetTabPerms').getBoundingClientRect();
      const x = document.querySelector('#sheetFullHead .sheet-close'), xr = x.getBoundingClientRect();
      const strip = document.querySelector('#inspectorModal .sheet-tabs');
      return { top: r.top, bottom: r.bottom, vh: window.innerHeight, left: r.left, right: r.right, vw: window.innerWidth,
        grid: vis('sheetActionGrid'), header: vis('sheetTitle'), dates: vis('sheetDates'), headShown: vis('sheetFullHead'),
        title: document.getElementById('sheetFullTitle').textContent, app: document.getElementById('sheetFullApp').textContent,
        strip: !!(strip.offsetParent && strip.getBoundingClientRect().height > 20), tabTop: tc.top, tabBottom: tc.bottom, search: vis('permsSearch'), chips: vis('permsFilterRow'),
        xLabel: x.getAttribute('aria-label'), xW: Math.round(xr.width), xH: Math.round(xr.height), xRight: xr.right, xTop: xr.top,
        expandHidden: !document.querySelector('#sheetTabPerms .sheet-full-btn').offsetParent, focusX: document.activeElement === x,
        nav: typeof bnavCovered === 'function' ? bnavCovered() : null, scroller: getComputedStyle(document.getElementById('sheetTabPerms')).overflowY };
    });
    check(`2. [${tag}] the button opens Permissions at full screen`, await full(), '');
    check('   it covers the whole screen (the bottom bar steps aside as for every sheet)', Math.abs(g.top) < 1 && Math.abs(g.bottom - g.vh) < 1 && g.nav === true, JSON.stringify([g.top, g.bottom, g.vh, g.nav]));
    check('   the app header and the menu buttons are not shown', !g.grid && !g.header && !g.dates, JSON.stringify([g.grid, g.header, g.dates]));
    check('   a slim bar names the tab and the app', g.headShown && g.title === 'Permissions' && g.app === 'Samsung Internet', g.title + ' / ' + g.app);
    check('   the ✕ is a 44px button in the top right corner, named, with the focus', g.xLabel === 'Close' && g.xW === 44 && g.xH === 44 && g.xRight > g.right - 30 && g.xTop < 70 && g.focusX, JSON.stringify([g.xLabel, g.xW, g.xRight, g.right, g.xTop, g.focusX]));
    check('   the tab strip stays, the search and the chips are there', g.strip && g.search && g.chips);
    check('   the list fills the rest of the screen and scrolls on its own', g.tabBottom > g.vh - 40 && g.tabBottom - g.tabTop > g.vh * 0.6 && g.scroller === 'auto', JSON.stringify([g.tabTop, g.tabBottom]));
    check('   the expand button itself is hidden there', g.expandHidden);
    check('   the search typed in the menu is kept', (await ev(() => document.getElementById('permsSearch').value)) === 'read' && (await rows('permsContainer')) === readPerms, readPerms + ' rows');

    // 3. the same live list: type, tap a chip, the rows change
    await page.fill('#permsSearch', ''); await page.waitForTimeout(80);
    const n0 = await rows('permsContainer');
    await page.fill('#permsSearch', 'camera'); await page.waitForTimeout(80);
    const n1 = await rows('permsContainer');
    await page.fill('#permsSearch', ''); await page.waitForTimeout(80);
    await tapSel('#permsFilterRow .filter-chip[data-filter="granted"]');
    const n2 = await rows('permsContainer');
    check(`3. [${tag}] search and chips work at full screen`, n0 === allPerms && n1 === 1 && n2 > 0 && n2 < n0 && (await full()), JSON.stringify([allPerms, n0, n1, n2]));
    const listScroll = await ev(() => { const tc = document.getElementById('sheetTabPerms'); tc.scrollTop = 160; return tc.scrollTop; });

    // 4. ✕: back to the menu, as it was
    await tapSel('#sheetFullHead .sheet-close');
    const a = await ev(() => {
      const sh = document.querySelector('#inspectorModal .modal-sheet');
      return { sheet: sh.scrollTop, list: document.getElementById('sheetTabPerms').scrollTop, grid: !!document.getElementById('sheetActionGrid').offsetParent, head: !!document.getElementById('sheetFullHead').offsetParent,
        chip: document.querySelector('#permsFilterRow .filter-chip.active').getAttribute('data-filter'), maxH: getComputedStyle(sh).maxHeight };
    });
    check(`4. [${tag}] the ✕ goes back to the menu (sheet still open, menu buttons back, slim bar gone)`, !(await full()) && (await shown()) && a.grid && !a.head, JSON.stringify(a));
    check('   on the same tab, with its chip, the menu and the list scrolled where they were', (await curTab()) === 'perms' && a.chip === 'granted' && Math.abs(a.sheet - before.sheet) <= 1 && listScroll > 0 && Math.abs(a.list - listScroll) <= 1, JSON.stringify([a.chip, a.sheet, before.sheet, a.list, listScroll]));
    check('   the sheet has its usual height again', a.maxH !== 'none', a.maxH);

    // 5. switching tabs at full screen: Activities opens at full screen, with its own search and chips; a pull down by the handle goes back to it
    await ev(() => setPermsFilter('all'));
    await tapSel('#sheetTabPerms .sheet-full-btn');
    await tapSel('#inspectorModal .sheet-tab-pill[data-tab="acts"]');
    const s = await ev(() => ({ title: document.getElementById('sheetFullTitle').textContent, acts: !!document.getElementById('actsContainer').offsetParent, perms: !!document.getElementById('permsContainer').offsetParent,
      grid: !!document.getElementById('sheetActionGrid').offsetParent, bottom: document.getElementById('sheetTabActs').getBoundingClientRect().bottom, vh: window.innerHeight }));
    const a0 = await rows('actsContainer');
    check(`5. [${tag}] a tap on Activities at full screen shows Activities at full screen`, (await full()) && (await curTab()) === 'acts' && s.title === 'Activities' && s.acts && !s.perms && !s.grid && s.bottom > s.vh - 40, JSON.stringify(s));
    await page.fill('#actsSearch', 'activity1'); await page.waitForTimeout(80);
    const a1 = await rows('actsContainer');
    await tapSel('#actsFilterRow .filter-chip[data-filter="exported"]');
    const a2 = await rows('actsContainer');
    await tapSel('#actsFilterRow .filter-chip[data-filter="all"]');
    check('   its rows change with its search and its chips', a0 === 18 && a1 === 9 && a2 === 0 && (await rows('actsContainer')) === 9, JSON.stringify([a0, a1, a2]));
    await pullDown(25);
    check('   a small pull down by the handle does not leave the full screen', await full());
    const hit = await pullDown(170);
    check(`6. [${tag}] a pull down from the top middle goes back to the menu, which stays open`, !(await full()) && (await shown()), hit);
    check('   on Activities, with its search text', (await curTab()) === 'acts' && (await ev(() => document.getElementById('actsSearch').value)) === 'activity1' && (await rows('actsContainer')) === 9);
    check('   the sheet has no leftover offset', await ev(() => document.querySelector('#inspectorModal .modal-sheet').style.transform === ''));

    // 7. Back: first the full screen, then the menu
    await tapSel('#sheetTabActs .sheet-full-btn');
    await ev(() => backNavigate()); await page.waitForTimeout(300);
    check(`7. [${tag}] Back leaves the full screen first, the menu stays`, !(await full()) && (await shown()));
    await ev(() => backNavigate()); await page.waitForTimeout(300);
    check('   a second Back closes the menu', !(await shown()));

    // 8. the menu closed while a tab is at full screen: nothing left over, the next app opens on the menu
    await open('com.sec.android.app.sbrowser');
    await tapSel('#sheetTabPerms .sheet-full-btn');
    await ev(() => closeInspector()); await page.waitForTimeout(300);
    check(`8. [${tag}] closing the menu at full screen leaves no full screen behind`, !(await full()) && !(await shown()));
    await tapSel('body');
    await open('com.microsoft.skydrive');
    check('   the next app opens on the menu', !(await full()) && (await ev(() => !!document.getElementById('sheetActionGrid').offsetParent)));
    await tapSel('#sheetTabPerms .sheet-full-btn');
    await ev(() => document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show'))); await page.waitForTimeout(200);
    check('   every sheet closed at once: no full screen left either', !(await full()));

    // 9. a menu pulled up by its handle is pulled up again when it comes back
    await open('com.sec.android.app.sbrowser');
    await ev(() => document.querySelector('#inspectorModal .modal-sheet').classList.add('sheet-expanded'));
    await tapSel('#sheetTabPerms .sheet-full-btn');
    const both = await ev(() => document.querySelector('#inspectorModal .modal-sheet').classList.contains('sheet-expanded'));
    await tapSel('#sheetFullHead .sheet-close');
    check(`9. [${tag}] a menu pulled up is pulled up again after the full screen`, !both && (await ev(() => document.querySelector('#inspectorModal .modal-sheet').classList.contains('sheet-expanded'))));
    await ev(() => closeInspector());
    await ctx.close();
  }

  // 10. reduced motion: no animation in or out
  {
    const ctx = await b.newContext({ viewport: { width: 360, height: 800 }, hasTouch: true, colorScheme: 'dark', reducedMotion: 'reduce' });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push('rm ' + e.message));
    await page.addInitScript(mock.initScript, { dark: true });
    await page.goto(PAGE); await page.waitForTimeout(450);
    await page.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await page.waitForTimeout(500);
    const anim = await page.evaluate(() => { sheetFullOpen(); const sh = document.querySelector('#inspectorModal .modal-sheet'); const a = getComputedStyle(sh).animationName; sheetFullClose(); return [a, getComputedStyle(sh).animationName]; });
    check('10. with reduced motion the full screen opens and closes without an animation', anim[0] === 'none' && anim[1] === 'none', JSON.stringify(anim));
    const ctx2 = await b.newContext({ viewport: { width: 360, height: 800 }, colorScheme: 'dark' });
    const p2 = await ctx2.newPage();
    await p2.addInitScript(mock.initScript, { dark: true });
    await p2.goto(PAGE); await p2.waitForTimeout(450);
    await p2.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await p2.waitForTimeout(500);
    const anim2 = await p2.evaluate(() => { sheetFullOpen(); return getComputedStyle(document.querySelector('#inspectorModal .modal-sheet')).animationName; });
    check('    without it, it slides in', anim2 === 'tabFullIn', anim2);
    await ctx.close(); await ctx2.close();
  }
  await b.close();
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
})();
