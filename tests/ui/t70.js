// v6.1 Application Manager tab top: the header button (the colors palette in v6.1, the settings gear since v7.0) is bigger, the big stat buttons (Installed, Running, ...) light up for the filter the list shows,
// and a tip under Export / Share CSV says the filters scroll sideways (and goes once they have been).
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const apps = [];
for (let i = 0; i < 40; i++) apps.push({ pkg: 'com.example.app' + i, name: 'App ' + i, isSystem: i % 3 === 0, isRunning: i % 7 === 0, isFrozen: i % 11 === 0, isUninstalled: i % 13 === 0, isSuspended: i % 17 === 0, version: '1.' + i, installedAt: 1.7e12 - i * 864e5, updatedAt: 1.7e12 - i * 864e5, apkSize: 1e6 * i });

(async () => {
  const b = await chromium.launch();
  const open = async (store, viewport) => {
    const page = await b.newPage({ viewport: viewport || { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { apps, kv: { perm_intro_v61: '1' }, store: store || {} });
    await page.goto(PAGE);
    await page.waitForFunction(() => document.getElementById('statTotal').innerText !== '--');          // the list is read (a filter may leave it empty)
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const active = page => ev(page, () => Array.from(document.querySelectorAll('.stat-card.active')).map(c => c.getAttribute('data-filter') + ':' + c.getAttribute('aria-pressed')).join(',') || 'none');
  const pill = page => ev(page, () => document.querySelector('#filterScroll .filter-pill.active').getAttribute('data-filter'));

  // 1) the theme button
  let page = await open();
  const m = await ev(page, () => { const r = id => { const b = document.querySelector(id).getBoundingClientRect(); return { w: Math.round(b.width), h: Math.round(b.height) }; };
    return { colors: r('#prefsHeaderBtn'), mode: r('#execModeBadge'), header: r('.app-header'), tabTop: Math.round(document.querySelector('.tab-bar').getBoundingClientRect().top), headerBottom: Math.round(document.querySelector('.app-header').getBoundingClientRect().bottom), fs: getComputedStyle(document.getElementById('prefsHeaderBtn')).fontSize }; });
  check('1. the settings button is a good tap target (at least 44 x 36, it was 40 x 25)', m.colors.w >= 44 && m.colors.h >= 36, JSON.stringify(m.colors));
  check('   its emoji is bigger (22px, it was 11px)', m.fs === '22px', m.fs);
  check('   it fits in the header (no taller than the header) and the tab bar still starts where the header ends', m.colors.h < m.header.h && m.tabTop === m.headerBottom, 'header ' + m.header.h + 'px, tab bar at ' + m.tabTop + ', header ends ' + m.headerBottom);
  check('   it still opens Settings', await (async () => { await page.click('#prefsHeaderBtn'); return (await ev(page, () => currentViewName())) === 'prefs'; })());
  await ev(page, () => switchView('apps'));

  // 2) the stat buttons follow the filter
  check('2. on opening, no box is lit and the All Apps pill is (the list shows every app)', (await active(page)) === 'none' && (await pill(page)) === 'all', await active(page));
  await page.click('.stat-card[data-filter="running"]');
  const lit = await ev(page, () => { const c = document.querySelector('.stat-card.active'), o = document.querySelector('.stat-card:not(.active)'); const a = getComputedStyle(c), n = getComputedStyle(o); return { lit: a.borderTopColor + '|' + a.boxShadow !== 'none', borderDiffers: a.borderTopColor !== n.borderTopColor, shadow: a.boxShadow !== 'none' && n.boxShadow === 'none' }; });
  check('   a lit card looks different (its own border colour and a glow), the others do not', lit.borderDiffers && lit.shadow, JSON.stringify(lit));
  check('   tapping Running lights Running only, and the Running pill follows', (await active(page)) === 'running:true' && (await pill(page)) === 'running', await active(page));
  check('   the list below shows what the lit card counts', (await ev(page, () => document.querySelectorAll('#appsListContainer .app-card').length)) === Number(await page.locator('#statRunning').innerText()));
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  await page.click('#filterScroll .filter-pill[data-filter="system"]');
  check('   choosing the System pill lights System', (await active(page)) === 'system:true', await active(page));
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  await page.click('#filterScroll .filter-pill[data-filter="suspended"]');
  check('   a filter with no card (Suspended) lights none', (await active(page)) === 'none', await active(page));
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  await page.click('#filterScroll .filter-pill[data-filter="recent"]');
  check('   same for Updated 7d', (await active(page)) === 'none', await active(page));
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  check('   tapping All Apps is back to the start', (await active(page)) === 'none' && (await pill(page)) === 'all', await active(page));
  for (const f of ['frozen', 'user', 'uninstalled']) {
    await page.click('#filterScroll .filter-pill[data-filter="all"]');
    await page.click('.stat-card[data-filter="' + f + '"]');
    check('   ' + f + ' card lights ' + f + ' and the pill follows', (await active(page)) === f + ':true' && (await pill(page)) === f, await active(page));
  }
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  await ev(page, () => document.querySelector('.stat-card[data-filter="system"]').focus());
  await page.keyboard.press('Enter');
  check('   a card can be used from the keyboard (Enter)', (await active(page)) === 'system:true', await active(page));
  await page.click('#filterScroll .filter-pill[data-filter="all"]');
  await ev(page, () => document.querySelector('.stat-card[data-filter="running"]').focus());
  await page.keyboard.press(' ');
  check('   …and Space', (await active(page)) === 'running:true', await active(page));
  check('   each card is a button with a pressed state for a screen reader', await ev(page, () => Array.from(document.querySelectorAll('.stat-card')).every(c => c.getAttribute('role') === 'button' && c.hasAttribute('aria-pressed') && c.tabIndex === 0)));
  check('   the highlight does not shrink the card or move the grid', await ev(page, () => { const w = k => Math.round(document.querySelector('.stat-card[data-filter="' + k + '"]').getBoundingClientRect().width); return w('installed') === w('running') && w('running') === w('uninstalled') && w('enabled') === w('frozen') && w('user') === w('system'); }));
  await page.close();

  // 3) a filter left on last time is not brought back: the app always opens on All Apps
  page = await open({ ui_state: JSON.stringify({ appsFilter: 'frozen', showVersions: true, appSort: 'name' }) });
  check('3. a remembered filter is ignored: the app opens on All Apps', (await active(page)) === 'none' && (await pill(page)) === 'all', await active(page));
  await page.close();
  page = await open({ ui_state: JSON.stringify({ appsFilter: 'patched', showVersions: true, appSort: 'name' }) });
  check('   a remembered filter with no card is ignored too', (await active(page)) === 'none', await active(page));
  await page.close();

  // 3b) the opt-in "Remember my filters" setting (Settings, Lists): off by default, and when on the filters come back
  page = await open({ ui_state: JSON.stringify({ rememberFilters: true, filters: { apps: ['running', 'user', 'bogus'], uad: ['system'], saved: ['frozen'] } }) });
  check('3b. with "Remember my filters" on, the filters left on come back (running + user, unknown ones dropped)', (await active(page)) === 'running:true,user:true', await active(page));
  check('   the setting shows as on, and the Debloater and Saved rows come back too', await ev(page, () => document.getElementById('rememberFiltersToggle').checked && uadExtra.has('system') && savedFilters.has('frozen')));
  await page.click('#filterScroll .filter-pill[data-filter="all"]'); await ev(page, () => new Promise(r => setTimeout(r, 200)));
  check('   it saves the change (tapping All Apps saves an empty set)', await ev(page, () => JSON.parse(window.AndroidBridge.loadStore('ui_state') || '{}').filters.apps.length === 0));
  await page.close();
  page = await open({ ui_state: JSON.stringify({ filters: { apps: ['running'] } }) });
  check('   with the setting off (the default), a saved filter set is ignored', (await active(page)) === 'none' && await ev(page, () => !document.getElementById('rememberFiltersToggle').checked), await active(page));
  await page.close();

  // 4) the tip under Export / Share CSV
  page = await open();
  const t = await ev(page, () => { const h = document.getElementById('filtersHint'), r = x => document.querySelector(x).getBoundingClientRect();
    const btns = Array.from(document.querySelectorAll('.apps-sort-row .mode-action-btn')); const lastBtn = btns[btns.length - 1].getBoundingClientRect();
    const exp = btns.find(x => /Profiles/.test(x.innerText)).getBoundingClientRect(), csv = btns.find(x => /Share CSV/.test(x.innerText)).getBoundingClientRect();
    const hr = h.getBoundingClientRect(), fr = r('#filterScroll');
    return { shown: getComputedStyle(h).display !== 'none', text: h.innerText.replace(/\s+/g, ' ').trim(), belowButtons: hr.top >= Math.max(exp.bottom, csv.bottom, lastBtn.bottom) - 1, aboveFilters: hr.bottom <= fr.top + 1, width: Math.round(hr.width), sticky: getComputedStyle(h).position };
  });
  check('4. a tip sits under the Share CSV / Profiles buttons and above the filters', t.shown && t.belowButtons && t.aboveFilters, JSON.stringify(t));
  check('   it tells to scroll the filters sideways for more', /scroll/i.test(t.text) && /sideways/i.test(t.text) && /more filters/i.test(t.text), t.text);
  check('   the filters really do scroll sideways (more pills than fit)', await ev(page, () => { const f = document.getElementById('filterScroll'); return f.scrollWidth > f.clientWidth + 20; }));
  await ev(page, () => { document.getElementById('filterScroll').scrollLeft = 200; });
  await page.waitForFunction(() => getComputedStyle(document.getElementById('filtersHint')).display === 'none');
  check('   scrolling the filters sideways makes it go', true);
  check('   and it stays gone: the choice is kept with the other remembered view settings', JSON.parse(await ev(page, () => window.__store.ui_state)).filtersHintDone === true);
  await page.close();
  // keyboard and screen readers
  page = await open();
  check('   its ✕ is a button for a screen reader, reachable with Tab, and big enough to hit', await ev(page, () => { const x = document.querySelector('.filters-hint-x'), r = x.getBoundingClientRect(); return x.getAttribute('role') === 'button' && x.tabIndex === 0 && r.width >= 28 && r.height >= 28; }));
  await ev(page, () => document.querySelector('.filters-hint-x').focus());
  await page.keyboard.press('Enter');
  check('   …and Enter on it closes the tip', (await ev(page, () => getComputedStyle(document.getElementById('filtersHint')).display)) === 'none');
  check('   the settings button is a button too: focusable and named', await ev(page, () => { const c = document.getElementById('prefsHeaderBtn'); return c.getAttribute('role') === 'button' && c.tabIndex === 0 && /Settings/.test(c.getAttribute('aria-label')); }));
  await ev(page, () => document.getElementById('prefsHeaderBtn').focus());
  await page.keyboard.press('Enter');
  check('   …and Enter on it opens Settings', (await ev(page, () => currentViewName())) === 'prefs');
  await page.close();
  page = await open({ ui_state: JSON.stringify({ appsFilter: 'all', filtersHintDone: true }) });
  check('   on the next start it is not shown', await ev(page, () => getComputedStyle(document.getElementById('filtersHint')).display) === 'none');
  await page.close();
  page = await open();
  await page.click('.filters-hint-x');
  check('   its ✕ closes it too (and remembers)', (await ev(page, () => getComputedStyle(document.getElementById('filtersHint')).display)) === 'none' && JSON.parse(await ev(page, () => window.__store.ui_state)).filtersHintDone === true);
  await page.close();
  // narrow phone: nothing overflows sideways
  page = await open({}, { width: 320, height: 640 });
  check('   at 320 px wide the page does not scroll sideways', await ev(page, () => document.documentElement.scrollWidth <= window.innerWidth + 1), await ev(page, () => document.documentElement.scrollWidth + ' of ' + window.innerWidth));
  await page.screenshot({ path: 'apps_top_320.png', clip: { x: 0, y: 0, width: 320, height: 520 } });
  await page.close();

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
