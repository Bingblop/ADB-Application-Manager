// Empty lists look the same everywhere and say the right thing. Every main list with nothing in it draws one shared block (.empty-state): a muted
// outline icon, a title, a hint when there is one and the button that fills the list again. The Apps list says to clear the search or the filters
// only when one is on, with the button that does it (Clear search, Clear filters, or both); with neither on (or no app read at all) it says no app
// was read and offers Reload Packages. The same for Saved Lists, the Debloater, the Files tab, the app store search, Backups, Profiles, the Task
// Manager, Updates, Logcat and the settings lists: the state is checked, then its button is pressed and the list is full again.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APPS = [
  { pkg: 'com.example.calendar', name: 'Calendar' },
  { pkg: 'com.example.camera', name: 'Camera' },
  { pkg: 'com.example.clock', name: 'Clock', isSystem: true },
];

(async () => {
  const b = await chromium.launch();
  const open = async (apps, size, dark) => {
    const page = await b.newPage({ viewport: size || { width: 360, height: 800 }, colorScheme: dark === false ? 'light' : 'dark' });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps });
    await page.goto(PAGE);
    await sleep(500);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  // what the empty block of a container shows, and how it is built
  const state = (page, sel) => ev(page, sel => {
    const box = document.querySelector(sel);
    const e = box && box.querySelector('.empty-state');
    if (!e) return { none: true, text: box ? box.innerText.replace(/\s+/g, ' ').trim().slice(0, 120) : null };
    const icon = e.querySelector('.empty-state-icon'), svg = icon && icon.querySelector('svg');
    const t = e.querySelector('.empty-state-title'), h = e.querySelector('.empty-state-hint');
    const cs = getComputedStyle(e), ti = t && getComputedStyle(t), ic = icon && getComputedStyle(icon);
    const r = e.getBoundingClientRect();
    return {
      key: e.dataset.empty, title: t ? t.innerText : null, hint: h ? h.innerText : null,
      buttons: Array.from(e.querySelectorAll('.empty-state-actions button')).map(x => x.innerText + (x.classList.contains('mode-action-btn') ? '' : ' (not mode-action-btn)')),
      icon: !!(svg && svg.children.length) && icon.getAttribute('aria-hidden') === 'true',
      iconMuted: !!ic && ic.color !== (ti && ti.color),
      centered: cs.textAlign === 'center' && cs.alignItems === 'center',
      inside: r.left >= -1 && r.right <= innerWidth + 1,
    };
  }, sel);
  const shared = s => !s.none && s.icon && s.iconMuted && s.centered && s.inside && !!s.title;
  const cards = page => ev(page, () => document.querySelectorAll('#appsListContainer .app-card').length);
  const press = (page, sel, label) => page.locator(sel + ' .empty-state-actions button', { hasText: label }).click();

  // ---------- Apps ----------
  let page = await open(APPS);
  await page.waitForFunction(() => document.getElementById('countAll').innerText === '3');
  check('apps: three apps are listed to begin with', await cards(page) === 3);

  await page.fill('#searchInput', 'zzzz'); await sleep(150);
  let s = await state(page, '#appsListContainer');
  check('apps, a search finds nothing: the shared block (icon, title, centered)', shared(s), JSON.stringify(s));
  check('  it says the search found nothing and offers Clear search only (no filter is on)', s.key === 'apps-nomatch-search' && s.title === 'No Applications Found' && s.hint === 'No apps match your search.'
    && s.buttons.join() === 'Clear search' && !/filter/i.test(s.hint), JSON.stringify(s));
  await press(page, '#appsListContainer', 'Clear search'); await sleep(150);
  check('  Clear search empties the field and lists the apps again', await cards(page) === 3 && await ev(page, () => document.getElementById('searchInput').value) === '');

  await ev(page, () => setFilter('frozen')); await sleep(150);
  s = await state(page, '#appsListContainer');
  check('apps, a filter matches nothing: says so and offers Clear filters only (no search is on)', shared(s) && s.key === 'apps-nomatch-filters' && s.hint === 'No apps match your filters.'
    && s.buttons.join() === 'Clear filters' && !/search/i.test(s.hint), JSON.stringify(s));
  await press(page, '#appsListContainer', 'Clear filters'); await sleep(150);
  check('  Clear filters turns every filter off and lists the apps again', await cards(page) === 3 && await ev(page, () => activeFilters.size === 0 && document.querySelector('#filterScroll .filter-pill[data-filter="all"]').classList.contains('active')));

  await ev(page, () => setFilter('system')); await page.fill('#searchInput', 'cal'); await sleep(150);
  s = await state(page, '#appsListContainer');
  check('apps, a search and a filter together: both buttons', shared(s) && s.key === 'apps-nomatch' && s.buttons.join() === 'Clear search,Clear filters', JSON.stringify(s));
  await press(page, '#appsListContainer', 'Clear search'); await sleep(150);
  check('  Clear search leaves the filter on (one system app)', await cards(page) === 1 && await ev(page, () => activeFilters.has('system')));
  await ev(page, () => setFilter('all'));

  // ---------- Saved Lists: none yet, then none that match ----------
  await ev(page, () => switchView('saved-lists')); await sleep(200);
  s = await state(page, '#savedListsContainer');
  check('saved lists, none yet: the shared block with Go to Application Manager', shared(s) && s.key === 'saved-none' && s.title === 'No Saved Lists Yet' && s.buttons.join() === 'Go to Application Manager', JSON.stringify(s));
  await ev(page, () => { customLists = [{ name: 'Mine', packages: ['com.example.calendar'] }]; setSavedFilter('system'); });
  s = await state(page, '#savedListsContainer');
  check('saved lists, a filter hides every list: says so with Clear filters', shared(s) && s.key === 'saved-nomatch' && s.buttons.join() === 'Clear filters', JSON.stringify(s));
  await press(page, '#savedListsContainer', 'Clear filters'); await sleep(100);
  check('  Clear filters shows the list again', await ev(page, () => savedFilters.size === 0 && document.querySelectorAll('#savedListsContainer .saved-list-card, #savedListsContainer [class*="saved-list"]').length > 0 && !document.querySelector('#savedListsContainer .empty-state')));
  await ev(page, () => { customLists = []; renderSavedLists(); });
  await press(page, '#savedListsContainer', 'Go to Application Manager'); await sleep(200);
  check('  Go to Application Manager opens the Apps tab', await ev(page, () => document.getElementById('view-apps').classList.contains('active')));

  // ---------- Debloater: the default chips hide the only package; a search adds Clear search ----------
  await ev(page, () => {
    const pk = [{ pkg: 'com.oem.thing', name: 'Thing', description: 'An OEM app', removal: 'Expert', list: 'Oem', state: 'enabled' }];
    Object.assign(window.AndroidBridge, { getUadMatches: () => JSON.stringify({ packages: pk }), getUadStatus: () => JSON.stringify({ cached: true }), updateUadList() {} });
    switchView('debloater');
  }); await sleep(200);
  s = await state(page, '#uadContainer');
  check('debloater, the chips hide everything (no search): the shared block, no Clear search', shared(s) && s.key === 'uad-nomatch' && s.title === 'No packages match these filters.' && s.buttons.length === 0, JSON.stringify(s));
  await page.fill('#uadSearch', 'zzzz'); await sleep(150);
  s = await state(page, '#uadContainer');
  check('  with a search on: Clear search is offered', s.key === 'uad-nomatch-search' && s.buttons.join() === 'Clear search', JSON.stringify(s));
  await press(page, '#uadContainer', 'Clear search'); await sleep(100);
  check('  Clear search empties the search field', await ev(page, () => document.getElementById('uadSearch').value === '' && !!document.querySelector('#uadContainer [data-empty="uad-nomatch"]')));
  await ev(page, () => { uadPackages = []; renderUadList(); });
  s = await state(page, '#uadContainer');
  check('  nothing of this phone is in the list: says so, no button', shared(s) && s.key === 'uad-none' && s.buttons.length === 0, JSON.stringify(s));

  // ---------- Files: an empty folder, a folder of hidden files ----------
  await ev(page, () => switchView('files')); await sleep(400);
  await ev(page, () => { fmLast = { entries: [] }; renderFm(fmLast); });
  s = await state(page, '#fmList');
  check('files, an empty folder: the shared block, the .. row stays', shared(s) && s.key === 'fm-empty' && s.title === 'Empty folder.' && await ev(page, () => /\.\./.test(document.getElementById('fmList').innerText)), JSON.stringify(s));
  await ev(page, () => { fmOpts.hidden = false; document.getElementById('fmHiddenChk').checked = false; fmLast = { entries: [{ name: '.nomedia', size: 0 }] }; renderFm(fmLast); });
  s = await state(page, '#fmList');
  check('  only hidden files: says so, with Show hidden files', shared(s) && s.key === 'fm-hidden' && s.title === 'Only hidden files are here.' && s.hint === 'Tick Show hidden files to see them.' && s.buttons.join() === 'Show hidden files', JSON.stringify(s));
  await press(page, '#fmList', 'Show hidden files'); await sleep(100);
  check('  Show hidden files ticks the option and lists the file', await ev(page, () => fmOpts.hidden === true && document.getElementById('fmHiddenChk').checked && !document.querySelector('#fmList .empty-state') && /\.nomedia/.test(document.getElementById('fmList').innerText)));
  await ev(page, () => { fmOpts.hidden = false; });

  // ---------- App store search ----------
  await ev(page, () => switchView('store')); await sleep(300);
  await ev(page, () => { storeLoaded = true; storeApps = [{ slug: 'alpha', name: 'Alpha', description: '' }]; document.getElementById('storeSearch').value = 'zzzz'; renderStoreList(); });
  s = await state(page, '#storeList');
  check('store, a search finds nothing: the shared block with Clear search', shared(s) && s.key === 'store-nomatch-search' && s.buttons.join() === 'Clear search', JSON.stringify(s));
  await press(page, '#storeList', 'Clear search'); await sleep(100);
  check('  Clear search lists the app again', await ev(page, () => document.getElementById('storeSearch').value === '' && !document.querySelector('#storeList .empty-state') && /Alpha/.test(document.getElementById('storeList').innerText)));
  await ev(page, () => { storeApps = []; renderStoreList(); });
  s = await state(page, '#storeList');
  check('  an empty catalog with no search: "No apps found." and no Clear search', s.key === 'store-none' && s.title === 'No apps found.' && s.buttons.length === 0, JSON.stringify(s));

  // ---------- Backups, Profiles, Task Manager, Updates, Logcat, settings ----------
  await ev(page, () => { backupTargetPkg = null; renderBackups(); });
  s = await state(page, '#backupsList');
  check('backups, none yet: title and hint split', shared(s) && s.key === 'backups-none' && s.title === 'No backups yet.' && s.hint === 'Choose a backup file from another phone.', JSON.stringify(s));
  await ev(page, () => { profiles = []; renderProfiles(); });
  s = await state(page, '#profilesList');
  check('profiles, none yet: title and hint split', shared(s) && s.key === 'profiles-none' && s.title === 'No profiles yet.' && /save them here/.test(s.hint), JSON.stringify(s));
  await ev(page, () => { switchView('taskmgr'); tmLastData = { privileged: false }; tmRenderProcesses(); }); await sleep(150);
  s = await state(page, '#tmProcList');
  check('task manager without a working mode: says what it needs', shared(s) && s.key === 'tm-procs-locked' && /Shizuku or Root/.test(s.title), JSON.stringify(s));
  await ev(page, () => { tmLastData = { privileged: true, procs: [] }; tmRenderProcesses(); });
  s = await state(page, '#tmProcList');
  check('  with one, but no process read: says so', s.key === 'tm-procs-none' && s.title === 'No processes read.', JSON.stringify(s));
  await ev(page, () => { switchView('settings'); }); await sleep(300);
  await ev(page, () => { if (typeof updList !== 'undefined') { updList = []; renderUpdates(null); } });
  s = await state(page, '#updContainer');
  check('updates, nothing found: the shared block', shared(s) && s.key === 'upd-none', JSON.stringify(s));
  await ev(page, () => { switchView('logcat'); logcatClear(); logcatDraw(false); }); await sleep(200);
  s = await state(page, '#logcatOutput');
  check('logcat, no lines: the shared block', shared(s) && s.key === 'logcat-none' && s.title === 'No log lines.', JSON.stringify(s));

  // every empty block of the page is the shared one: no older style left in the lists checked above
  const old = await ev(page, () => ['#appsListContainer', '#savedListsContainer', '#uadContainer', '#fmList', '#storeList', '#backupsList', '#profilesList', '#tmProcList', '#updContainer', '#logcatOutput']
    .filter(sel => { const e = document.querySelector(sel); return e && e.querySelector('.list-empty, .uad-status, .lc-meta, .mp-empty, .sdb-empty, p[style]'); }));
  check('none of these lists still draws its own empty text', old.length === 0, JSON.stringify(old));
  await page.close();

  // ---------- Apps: nothing read at all, with and without a filter ----------
  page = await open([]);
  s = await state(page, '#appsListContainer');
  check('apps, nothing read: "No Applications Loaded", no word of search or filters, Reload Packages', shared(s) && s.key === 'apps-none' && s.title === 'No Applications Loaded'
    && !/search|filter/i.test(s.hint) && s.buttons.join() === 'Reload Packages', JSON.stringify(s));
  await ev(page, () => setFilter('frozen')); await sleep(150);
  s = await state(page, '#appsListContainer');
  check('  a filter on but no app read: still says no app was read (clearing the filter would not help)', s.key === 'apps-none' && !/filter/i.test(s.hint), JSON.stringify(s));
  await ev(page, () => { setFilter('all'); window.AndroidBridge.loadPackages = () => JSON.stringify([{ pkg: 'com.example.app', name: 'Example App', isSystem: false }]); });
  await press(page, '#appsListContainer', 'Reload Packages'); await sleep(900);
  check('  Reload Packages reads the list again', await cards(page) === 1);
  await page.close();

  // ---------- light theme, 320 px: the block fits and the icon is muted ----------
  page = await open([], { width: 320, height: 700 }, false);
  s = await state(page, '#appsListContainer');
  check('320 px, light: the block fits inside the screen with its icon and button', shared(s) && s.buttons.length === 1, JSON.stringify(s));
  const geo = await ev(page, () => {
    const e = document.querySelector('#appsListContainer .empty-state'), i = e.querySelector('.empty-state-icon').getBoundingClientRect(), t = e.querySelector('.empty-state-title').getBoundingClientRect(), bt = e.querySelector('button').getBoundingClientRect();
    return { iconW: Math.round(i.width), iconAboveTitle: i.bottom <= t.top, btnH: Math.round(bt.height), docW: document.documentElement.scrollWidth, W: innerWidth };
  });
  check('  icon 48 px above the title, the button at least 36 px tall, no sideways scroll', geo.iconW === 48 && geo.iconAboveTitle && geo.btnH >= 36 && geo.docW <= geo.W, JSON.stringify(geo));
  await page.close();

  // ---------- German: the same block, translated ----------
  page = await open(APPS);
  await page.waitForFunction(() => document.getElementById('countAll').innerText === '3');
  await ev(page, () => I18N.set('de')); await sleep(800);
  await page.fill('#searchInput', 'zzzz'); await sleep(300);
  s = await state(page, '#appsListContainer');
  check('de: the title, the hint and the button are translated', shared(s) && s.title !== 'No Applications Found' && s.hint !== 'No apps match your search.' && s.buttons[0] !== 'Clear search', JSON.stringify(s));
  await page.close();

  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
