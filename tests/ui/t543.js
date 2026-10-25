// Installed | Uninstalled is a pair like System | User and Enabled | Frozen (the owner's request after v7.14.0): only one of the two can be on, and tapping one
// turns the other off, in the Apps tab, in Saved Applications (its row has no Installed pill; it shares the Apps tab's rule) and in Connected Devices. They used to
// stack, and both together showed every app. Uninstalled still starts a clean list and Running still turns Uninstalled off. A filter set remembered by an older
// version with both on comes back with neither on (both together were every app).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const APPS = [
  { pkg: 'com.u.run', name: 'Alpha Run', isSystem: false, isRunning: true, isFrozen: false, isSuspended: false, isUninstalled: false },
  { pkg: 'com.u.plain', name: 'Bravo Plain', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false },
  { pkg: 'com.s.cold', name: 'Charlie Cold', isSystem: true, isRunning: false, isFrozen: true, isSuspended: false, isUninstalled: false },
  { pkg: 'com.s.gone', name: 'Delta Gone', isSystem: true, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: true },
  { pkg: 'com.u.gone', name: 'Echo Gone', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: true },
];
const LISTS = [{ id: 'l1', name: 'Everything', packages: APPS.map(a => a.pkg) }];
async function open(b, uiState) {
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(([apps, lists, ui]) => {
    const store = ui ? { ui_state: JSON.stringify(ui) } : {};
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return JSON.stringify(lists); }, saveCustomLists() {},
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(apps); },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      loadStore(k) { return store[k] || ''; }, saveStore(k, v) { store[k] = v; } };
  }, [APPS, LISTS, uiState || null]);
  await page.goto(PAGE);
  await page.waitForFunction(() => document.querySelectorAll('#appsListContainer .app-card').length > 0, null, { timeout: 10000 });
  return { page, errors };
}
(async () => {
  const b = await chromium.launch();
  const allErrors = [];

  // ---- 1. the Apps tab: the pills themselves ----
  let { page, errors } = await open(b);
  const ev = (fn, a) => page.evaluate(fn, a);
  const tap = async f => { await ev(f => document.querySelector('#filterScroll .filter-pill[data-filter="' + f + '"]').click(), f); await page.waitForTimeout(120); };
  const state = () => ev(() => ({
    on: Array.from(activeFilters).sort().join(),
    lit: Array.from(document.querySelectorAll('#filterScroll .filter-pill.active')).map(p => p.getAttribute('data-filter')).sort().join(),
    pressed: ['installed', 'uninstalled'].map(f => document.querySelector('#filterScroll .stat-card[data-filter="' + f + '"]').getAttribute('aria-pressed')).join(),
    names: Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText).sort().join(),
  }));
  await tap('installed');
  let s = await state();
  check('1. Installed alone lists the installed apps', s.on === 'installed' && s.lit === 'installed' && s.names === 'Alpha Run,Bravo Plain,Charlie Cold', JSON.stringify(s));
  await tap('uninstalled');
  s = await state();
  check('   Uninstalled takes the place of Installed (one of the pair, not both): only the uninstalled apps', s.on === 'uninstalled' && s.lit === 'uninstalled' && s.pressed === 'false,true' && s.names === 'Delta Gone,Echo Gone', JSON.stringify(s));
  await tap('installed');
  s = await state();
  check('   and Installed takes the place of Uninstalled', s.on === 'installed' && s.lit === 'installed' && s.pressed === 'true,false' && s.names === 'Alpha Run,Bravo Plain,Charlie Cold', JSON.stringify(s));
  await tap('installed');
  s = await state();
  check('   tapping the half that is on turns it off: All Apps, every app', s.on === '' && s.lit === 'all' && s.names.split(',').length === 5, JSON.stringify(s));
  await tap('installed'); await tap('system');
  s = await state();
  check('   Installed combines with another pair: Installed + System', s.on === 'installed,system' && s.names === 'Charlie Cold', JSON.stringify(s));
  await tap('uninstalled');
  s = await state();
  check('   Uninstalled still starts a clean list (System and Installed go off)', s.on === 'uninstalled' && s.names === 'Delta Gone,Echo Gone', JSON.stringify(s));
  await tap('system');
  s = await state();
  check('   a filter tapped after it narrows it: Uninstalled + System', s.on === 'system,uninstalled' && s.names === 'Delta Gone', JSON.stringify(s));
  await tap('installed');
  s = await state();
  check('   Installed then replaces Uninstalled and System stays', s.on === 'installed,system' && s.names === 'Charlie Cold', JSON.stringify(s));
  await tap('all'); await tap('uninstalled'); await tap('running');
  s = await state();
  check('   Running still turns Uninstalled off (a running app is installed)', s.on === 'running' && s.names === 'Alpha Run', JSON.stringify(s));
  await tap('all');
  const both = await ev(() => { const a = allApps.find(x => x.pkg === 'com.s.gone'), i = allApps.find(x => x.pkg === 'com.u.plain');
    return [appMatchesFilterSet(a, new Set(['installed'])), appMatchesFilterSet(a, new Set(['uninstalled'])), appMatchesFilterSet(i, new Set(['installed'])), appMatchesFilterSet(i, new Set(['uninstalled']))].join(); });
  check('   the rule itself: Installed leaves the uninstalled app out, Uninstalled the installed one', both === 'false,true,true,false', both);

  // ---- 2. Saved Applications: the same rule ----
  await ev(() => switchView('saved-lists')); await page.waitForTimeout(200);
  const saved = () => ev(() => ({
    on: Array.from(savedFilters).sort().join(),
    pills: Array.from(document.querySelectorAll('#savedFilterRow .filter-pill')).map(p => p.getAttribute('data-filter') + (p.classList.contains('active') ? '*' : '')).join(),
    meta: (document.querySelector('#savedListsContainer .saved-list-meta') || {}).innerText || '',
    chips: Array.from(document.querySelectorAll('#savedListsContainer .saved-pkg-chip')).map(c => c.innerText).sort().join(),
  }));
  await ev(() => document.querySelector('#savedFilterRow .filter-pill[data-filter="uninstalled"]').click()); await page.waitForTimeout(100);
  let sv = await saved();
  check('2. Saved Applications: the Uninstalled pill shows the uninstalled apps of a list', sv.on === 'uninstalled' && /uninstalled\*/.test(sv.pills) && sv.meta === '2 of 5 apps' && sv.chips === 'com.s.gone,com.u.gone', JSON.stringify(sv));
  await ev(() => setSavedFilter('installed')); await page.waitForTimeout(100);
  sv = await saved();
  check('   its row has no Installed pill, but its filters share the Apps tab\'s pairs: Installed takes Uninstalled\'s place', sv.on === 'installed' && !sv.pills.split(',').some(p => p.replace('*', '') === 'installed') && sv.meta === '3 of 5 apps' && sv.chips === 'com.s.cold,com.u.plain,com.u.run', JSON.stringify(sv));
  await ev(() => document.querySelector('#savedFilterRow .filter-pill[data-filter="uninstalled"]').click()); await page.waitForTimeout(100);
  sv = await saved();
  check('   and a tap on Uninstalled turns Installed off again (never both)', sv.on === 'uninstalled' && sv.meta === '2 of 5 apps', JSON.stringify(sv));
  await ev(() => setSavedFilter('all'));

  // ---- 3. Connected Devices: the device's pills ----
  const CD_APPS = [
    { pkg: 'com.a.clock', name: 'Clock', system: true, disabled: true, uninstalled: false },
    { pkg: 'com.a.fit', name: 'Fit', system: false, disabled: false, uninstalled: false },
    { pkg: 'com.a.gone', name: 'Gone', system: true, disabled: false, uninstalled: true },
    { pkg: 'com.a.weather', name: 'Weather', system: false, disabled: false, uninstalled: false },
  ];
  await ev(apps => { switchView('devices'); document.getElementById('cdWork').style.display = ''; cd.apps = apps; cd.filters.clear(); cdAppsRender(); }, CD_APPS);
  const cdTap = async f => { await ev(f => document.querySelector('#cdAppFilters .filter-pill[data-cd="' + f + '"]').click(), f); await page.waitForTimeout(80); };
  const cds = () => ev(() => ({
    on: Array.from(cd.filters).sort().join(),
    lit: Array.from(document.querySelectorAll('#cdAppFilters .filter-pill.active')).map(p => p.getAttribute('data-filter')).sort().join(),
    pressed: ['installed', 'uninstalled'].map(f => document.querySelector('#cdAppFilters [data-cd="' + f + '"]').getAttribute('aria-pressed')).join(),
    names: Array.from(document.querySelectorAll('#cdAppList .app-name')).map(e => e.innerText).sort().join(),
    count: document.getElementById('cdAppCount').innerText,
  }));
  await cdTap('installed');
  let c = await cds();
  check('3. Connected Devices: Installed alone lists the installed apps', c.on === 'installed' && c.lit === 'installed' && c.names === 'Clock,Fit,Weather', JSON.stringify(c));
  await cdTap('uninstalled');
  c = await cds();
  check('   Uninstalled takes the place of Installed: only the uninstalled app', c.on === 'uninstalled' && c.lit === 'uninstalled' && c.pressed === 'false,true' && c.names === 'Gone' && c.count === '1 app of 4', JSON.stringify(c));
  await cdTap('installed');
  c = await cds();
  check('   and Installed takes the place of Uninstalled', c.on === 'installed' && c.pressed === 'true,false' && c.names === 'Clock,Fit,Weather', JSON.stringify(c));
  await cdTap('user');
  c = await cds();
  check('   Installed combines with another pair: Installed + User (the line says so)', c.on === 'installed,user' && c.names === 'Fit,Weather' && await ev(() => document.getElementById('cdAppSummary').innerText.startsWith('Showing apps that are Installed + User.')), JSON.stringify(c));
  await cdTap('uninstalled');
  c = await cds();
  check('   Uninstalled still starts a clean list (User and Installed go off)', c.on === 'uninstalled' && c.names === 'Gone', JSON.stringify(c));
  await cdTap('uninstalled');
  c = await cds();
  check('   tapping it again turns it off: All Apps', c.on === '' && c.lit === 'all' && c.names === 'Clock,Fit,Gone,Weather', JSON.stringify(c));
  allErrors.push(...errors);
  await page.close();

  // ---- 4. a filter set remembered by an older version ----
  const remembered = async apps => {
    const o = await open(b, { rememberFilters: true, filters: { apps, uad: [], saved: [] } });
    const r = await o.page.evaluate(() => ({ on: Array.from(activeFilters).sort().join(), lit: Array.from(document.querySelectorAll('#filterScroll .filter-pill.active')).map(p => p.getAttribute('data-filter')).sort().join(), rows: document.querySelectorAll('#appsListContainer .app-card').length }));
    allErrors.push(...o.errors); await o.page.close(); return r;
  };
  let r = await remembered(['installed', 'uninstalled']);
  check('4. Installed + Uninstalled remembered by an older version comes back as neither: All Apps, every app (what both together showed)', r.on === '' && r.lit === 'all' && r.rows === 5, JSON.stringify(r));
  r = await remembered(['uninstalled', 'installed', 'user']);
  check('   with another filter next to them, that one stays', r.on === 'user' && r.lit === 'user', JSON.stringify(r));
  r = await remembered(['uninstalled']);
  check('   a remembered Uninstalled alone is kept', r.on === 'uninstalled' && r.rows === 2, JSON.stringify(r));

  check('no page errors', allErrors.length === 0, JSON.stringify(allErrors));
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
