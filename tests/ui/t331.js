// The Apps tab is less crowded: the seven stat boxes (Installed, Running, Bloatware, Enabled / Frozen, 3rd Party / System) are no longer a grid of their own
// above the filters; each count is merged into its filter pill (one scrolling row, every pill with its count, the counted ones in their own colour and buttons
// with a pressed state), the "scroll the filters sideways" tip is an arrow at the end of that row (its text is the arrow's name and tooltip), and the sort menu
// is as wide as what it shows. The first app row starts well under 300 px at 400 x 860 (it was at 420), at 320 and 360 px and in German too, and every count,
// filter and sort is still there and does the same thing.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
const initMock = () => {
  const day = 864e5, now = Date.now();
  const apps = [
    { pkg: 'com.u.run', name: 'Alpha Run', isRunning: true, updatedAt: now - day },
    { pkg: 'com.u.cold', name: 'Bravo Cold', isFrozen: true },
    { pkg: 'com.u.pause', name: 'Charlie Pause', isSuspended: true },
    { pkg: 'com.u.plain', name: 'Delta Plain' },
    { pkg: 'com.s.on', name: 'Echo System', isSystem: true, isRunning: true },
    { pkg: 'com.s.cold', name: 'Foxtrot System', isSystem: true, isFrozen: true },
    { pkg: 'com.s.gone', name: 'Golf Gone', isSystem: true, isUninstalled: true },
    { pkg: 'com.u.more1', name: 'Hotel' }, { pkg: 'com.u.more2', name: 'India' }, { pkg: 'com.u.more3', name: 'Juliet' },
  ].map(a => Object.assign({ isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false, updatedAt: now - 60 * day, installedAt: now - 90 * day }, a));
  const kv = {};
  window.AndroidBridge = {
    vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    saveStore() { return true; }, loadStore() { return ''; }, saveSetting(k, v) { kv[k] = v; }, loadSetting(k) { return kv[k] || ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    loadPackages() { return JSON.stringify(apps); },
    getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
  };
};
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (w, h, lang) => {
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    page.bottomNav = true;                                                   // the app's own start: bottom navigation, no top tab bar
    page.on('pageerror', e => errors.push(w + ' ' + e.message));
    await page.addInitScript(initMock);
    await page.goto(PAGE); await page.waitForTimeout(700);
    if (lang) { await page.evaluate(l => { const s = document.getElementById('langSelect'); if (s) s.value = l; chooseLanguage(l); }, lang); await page.waitForTimeout(600); }
    await page.evaluate(() => { switchView('apps'); window.scrollTo(0, 0); }); await page.waitForTimeout(200);
    return page;
  };
  const top = page => page.evaluate(() => {
    const c = document.querySelector('#appsListContainer .app-card'), f = document.getElementById('filterScroll').getBoundingClientRect();
    return { firstRow: c ? Math.round(c.getBoundingClientRect().top) : -1, rowH: Math.round(f.height), sideScroll: document.documentElement.scrollWidth > innerWidth + 1, grid: !!document.querySelector('.stats-grid') };
  });

  // 1) where the list starts
  const page = await open(400, 860);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const t = await top(page);
  check('1. at 400 x 860 the first app row starts well under 300 px (it was at 420)', t.firstRow > 0 && t.firstRow < 280, t.firstRow + ' px');
  check('   there is no block of stat boxes above the filters any more; the filters are one row', !t.grid && t.rowH < 50, JSON.stringify(t));

  // 2) every filter is still there, each with its count
  const row = await ev(() => Array.from(document.querySelectorAll('#filterScroll .filter-pill')).map(p => p.getAttribute('data-filter') + '=' + p.innerText.replace(/\s+/g, ' ').trim()));
  console.log('   the row:', JSON.stringify(row));
  const want = ['all', 'installed', 'running', 'user', 'system', 'enabled', 'frozen', 'recent', 'suspended', 'patched', 'trackers', 'uninstalled'];
  check('2. every filter has its pill, each once: All Apps, the seven counted ones (first, so they show), then Updated 7d, Suspended, Patched, Trackers, Uninstalled last', row.map(r => r.split('=')[0]).join() === want.join(), row.map(r => r.split('=')[0]).join());
  const counts = await ev(() => {
    const n = { installed: 0, running: 0, enabled: 0, frozen: 0, user: 0, system: 0, uninstalled: 0 };
    allApps.forEach(a => { if (a.isRunning) n.running++; if (a.isFrozen && !a.isUninstalled) n.frozen++; if (!a.isFrozen && !a.isUninstalled) n.enabled++; if (a.isUninstalled) n.uninstalled++; else { n.installed++; if (a.isSystem) n.system++; else n.user++; } });
    const shown = {}; Object.keys(n).forEach(k => { shown[k] = Number(document.querySelector('#filterScroll .stat-card[data-filter="' + k + '"] .stat-num').innerText); });
    return { n, shown, all: document.getElementById('countAll').innerText, ids: ['statTotal', 'statRunning', 'statEnabled', 'statFrozen', 'statUser', 'statSystem', 'statUninstalled'].every(id => document.getElementById(id) && document.getElementById(id).closest('#filterScroll')) };
  });
  check('   each of the seven counts is in its pill and right (Installed 9, Running 2, 3rd Party 7, System 2, Enabled 7, Frozen 2, Uninstalled 1); All Apps counts 10', JSON.stringify(counts.n) === JSON.stringify(counts.shown) && counts.all === '10' && counts.ids && counts.shown.installed === 9 && counts.shown.uninstalled === 1, JSON.stringify(counts));

  // 3) the tip (before anything scrolls the row)
  const tip = await ev(() => { const h = document.getElementById('filtersHint'), r = h.getBoundingClientRect(), f = document.getElementById('filterScroll').getBoundingClientRect();
    return { shown: getComputedStyle(h).display !== 'none', inRow: h.parentElement.id === 'filterScroll' && Math.abs(r.right - f.right) <= 1 && r.top >= f.top - 1 && r.bottom <= f.bottom + 1, label: h.getAttribute('aria-label'), w: Math.round(r.width), h: Math.round(r.height) }; });
  check('3. the tip is an arrow held at the right end of the filter row; its name says what it said', tip.shown && tip.inRow && tip.label === 'Scroll the filters below sideways for more filters' && tip.w >= 36 && tip.h >= 36, JSON.stringify(tip));
  await page.click('#filtersHint');
  await page.waitForFunction(() => getComputedStyle(document.getElementById('filtersHint')).display === 'none', null, { timeout: 3000 }).catch(() => {});
  const after = await ev(() => ({ left: document.getElementById('filterScroll').scrollLeft, gone: getComputedStyle(document.getElementById('filtersHint')).display === 'none' }));
  check('   tapping it scrolls the filters on and puts the tip away', after.left > 24 && after.gone, JSON.stringify({ scrolled: after.left > 24, gone: after.gone }));
  await ev(() => { document.getElementById('filterScroll').scrollLeft = 0; });

  // 4) the counted pills are buttons, in their colours, and filter like before
  const a11y = await ev(() => Array.from(document.querySelectorAll('#filterScroll .stat-card')).every(c => c.getAttribute('role') === 'button' && c.tabIndex === 0 && c.getAttribute('aria-pressed') === 'false'));
  check('4. the seven counted pills are buttons with a pressed state, reachable with Tab', a11y);
  const tones = await ev(() => {
    const want = { running: 'apps-running', user: 'status-user', system: 'apps-system', enabled: 'apps-enabled', frozen: 'apps-frozen', uninstalled: 'apps-uninstalled', installed: 'accent' };
    const rgb = v => { const i = document.createElement('i'); i.style.color = 'var(--' + v + ')'; document.body.appendChild(i); const c = getComputedStyle(i).color; i.remove(); return c; };
    return Object.keys(want).filter(k => getComputedStyle(document.querySelector('.stat-card[data-filter="' + k + '"] .stat-num')).color !== rgb(want[k]));
  });
  check('   each count is written in its own colour (the colour its badge has)', tones.length === 0, JSON.stringify(tones));
  const names = () => ev(() => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(n => n.innerText).join('|'));
  const expectList = { installed: 9, running: 2, user: 7, system: 2, enabled: 7, frozen: 2, uninstalled: 1 };
  const taps = [];
  for (const f of Object.keys(expectList)) {
    await page.locator('#filterScroll .stat-card[data-filter="' + f + '"]').click(); await page.waitForTimeout(150);
    const st = await ev(f => ({ on: Array.from(activeFilters).join(), pressed: document.querySelector('.stat-card[data-filter="' + f + '"]').getAttribute('aria-pressed'), rows: document.querySelectorAll('#appsListContainer .app-card').length, lit: Array.from(document.querySelectorAll('#filterScroll .filter-pill.active')).map(p => p.getAttribute('data-filter')).join() }), f);
    taps.push(f + ':' + st.rows);
    if (!(st.on === f && st.pressed === 'true' && st.rows === expectList[f] && st.lit === f)) taps.push('WRONG ' + JSON.stringify(st));
    await page.locator('#filterScroll .filter-pill[data-filter="all"]').click(); await page.waitForTimeout(120);
  }
  check('   tapping each one shows just its apps, lights it and presses it; All Apps turns it off', !taps.some(x => /WRONG/.test(x)), taps.join(' '));
  const pair = await ev(() => { const c = document.querySelector('.stat-card[data-filter="frozen"]'), box = c.closest('.stat-split'); setFilter('frozen', c);
    const i = document.createElement('i'); i.style.color = 'var(--apps-frozen)'; document.body.appendChild(i); const want = getComputedStyle(i).color; i.remove();
    const r = { pair: Array.from(box.querySelectorAll('.stat-card')).map(x => x.dataset.filter).join('/'), frame: getComputedStyle(box).borderTopColor === want, other: document.querySelector('.stat-card[data-filter="user"]').closest('.stat-split') !== box };
    setFilter('all'); return r; });
  check('   Enabled / Frozen and 3rd Party / System are pairs that share a frame, the frame in the colour of the side that is on', pair.pair === 'enabled/frozen' && pair.frame && pair.other, JSON.stringify(pair));
  await ev(() => document.querySelector('.stat-card[data-filter="running"]').focus());
  await page.keyboard.press('Enter'); await page.waitForTimeout(120);
  check('   Enter on a focused count pill uses it', (await ev(() => Array.from(activeFilters).join())) === 'running');
  await ev(() => setFilter('all'));

  // 5) the sort menu is as wide as what it shows, and it still sorts
  const sorts = [];
  for (const v of ['name', 'updated', 'installed', 'size', 'updates']) {
    await page.selectOption('#appSort', v); await page.waitForTimeout(100);
    sorts.push(await ev(v => {
      const s = document.getElementById('appSort'), cs = getComputedStyle(s), span = document.createElement('span');
      span.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font:' + cs.font; span.textContent = s.options[s.selectedIndex].text; document.body.appendChild(span);
      const textW = span.getBoundingClientRect().width; span.remove();
      return { v, w: Math.round(s.getBoundingClientRect().width), text: Math.round(textW), fits: s.clientWidth >= textW + parseFloat(cs.paddingLeft), sorted: appSort === v };
    }, v));
  }
  check('5. the sort menu shows its whole choice for every choice (it was cut to 60 px), and is no wider than it needs (under 170 px)', sorts.every(s => s.fits && s.sorted && s.w < 170 && s.w >= 60), JSON.stringify(sorts));
  check('   it follows the choice: "Size" is narrower than "Updates first"', sorts.find(s => s.v === 'size').w < sorts.find(s => s.v === 'updates').w);
  check('   the row keeps the menu and all its buttons', (await ev(() => Array.from(document.querySelectorAll('.apps-sort-row > *')).map(e => e.id || e.innerText.trim()).join('|'))) === 'appSort|Share CSV|Profiles|Backups|appsActionBtnPick');
  await page.selectOption('#appSort', 'name');

  await page.screenshot({ path: 'apps_400.png', clip: { x: 0, y: 0, width: 400, height: 420 } });
  await page.close();

  // 6) narrow phones and a long language
  for (const [w, h, lang] of [[320, 640, ''], [360, 760, ''], [360, 760, 'de']]) {
    const p = await open(w, h, lang);
    const m = await top(p);
    const label = lang ? await p.evaluate(() => document.querySelector('.stat-card[data-filter="installed"]').innerText) : '';
    check('6. ' + w + ' px' + (lang ? ' in German' : '') + ': the first app row starts under 280 px, the filters are one row, the page does not scroll sideways', m.firstRow > 0 && m.firstRow < 280 && m.rowH < 50 && !m.sideScroll, JSON.stringify(m) + (lang ? ' ' + label : ''));
    await p.screenshot({ path: 'apps_' + w + (lang ? '_' + lang : '') + '.png', clip: { x: 0, y: 0, width: w, height: 420 } });
    await p.close();
  }

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
