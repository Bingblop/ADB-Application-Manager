// The "Updated 7d" filter is gone (v7.14.1, the owner's request): "The last updated sort feature supersedes the need for this filter and makes it add
// unnecessary redundancy and UI bloat." The Application Manager's pill row, Saved Applications' pills and the Debloater's "Also show only" row have no such
// pill and no count for it; Connected Devices never had one; a filter set remembered by an older version that holds it loads without it; sorting by Last
// updated is still there and puts the newest update first (the dates on the rows); the Help Guide no longer mentions the pill.
const { chromium, PAGE } = require('./lib/pw');
const fs = require('fs');
const { fileURLToPath } = require('url');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const open = async remembered => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.__errors = []; page.on('pageerror', e => page.__errors.push(e.message));
    await page.addInitScript(remembered => {
      const day = 864e5, now = Date.now();
      const apps = [
        { pkg: 'com.u.fresh', name: 'Fresh Update', isSystem: false, isRunning: true, updatedAt: now - 1 * day },
        { pkg: 'com.u.week', name: 'Week Old', isSystem: false, updatedAt: now - 6 * day },
        { pkg: 'com.u.old', name: 'Old Timer', isSystem: false, updatedAt: now - 300 * day },
        { pkg: 'com.s.sys', name: 'System Thing', isSystem: true, updatedAt: now - 2 * day },
        { pkg: 'com.s.gone', name: 'Gone', isSystem: true, isUninstalled: true },
      ];
      window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(apps); }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        getUadMatches() { return '{"packages":[]}'; }, getUadStatus() { return '{"cached":true,"count":0}'; },
        loadStore(k) { return k === 'ui_state' && remembered ? JSON.stringify(remembered) : ''; }, saveStore() {} };
    }, remembered || null);
    await page.goto(PAGE); await page.waitForTimeout(900);
    return page;
  };
  const pills = (page, sel) => page.evaluate(s => Array.from(document.querySelectorAll(s)).map(p => p.getAttribute('data-filter')), sel);
  const WANT = ['all', 'installed', 'uninstalled', 'system', 'user', 'enabled', 'frozen', 'running', 'suspended', 'trackers', 'patched'];

  // ---------------------------------------------------------------- 1) the Application Manager
  let page = await open();
  const row = await pills(page, '#filterScroll .filter-pill');
  check('1. the Application Manager\'s pills are All Apps, Installed | Uninstalled, System | User, Enabled | Frozen, Running, Suspended, Trackers, Patched: no Updated 7d', row.join() === WANT.join(), row.join());
  const gone = await page.evaluate(() => ({
    pill: !!document.querySelector('[data-filter="recent"]'), count: !!document.getElementById('countRecent'), text: /Updated 7d/.test(document.getElementById('view-apps').innerText),
    names: typeof FILTER_NAMES === 'object' && 'recent' in FILTER_NAMES, fn: typeof isRecentlyUpdated, days: typeof RECENT_DAYS,
  }));
  check('   no pill, no count element, no word in the tab, no name in the filter table, no helper left', !gone.pill && !gone.count && !gone.text && !gone.names && gone.fn === 'undefined' && gone.days === 'undefined', JSON.stringify(gone));
  // the whole list still filters and counts without it (the count code used to write to the removed element)
  await page.evaluate(() => { for (const k of ['running', 'system', 'suspended', 'trackers', 'patched']) { setFilter('all', document.querySelector('[data-filter="all"]')); setFilter(k, document.querySelector('#filterScroll [data-filter="' + k + '"]')); } setFilter('all', document.querySelector('[data-filter="all"]')); loadPackageData(); });
  await page.waitForTimeout(300);
  check('   every other filter still works and the list reloads with no page error', page.__errors.length === 0 && (await page.locator('#appsListContainer .app-card').count()) === 5, page.__errors.join(' | '));

  // ---------------------------------------------------------------- 2) Last updated does the job
  await page.selectOption('#appSort', 'updated'); await page.waitForTimeout(250);
  const sorted = await page.evaluate(() => ({ names: Array.from(document.querySelectorAll('#appsListContainer .app-name')).map(e => e.innerText), metas: Array.from(document.querySelectorAll('#appsListContainer .app-meta-line')).map(e => e.innerText),
    opt: Array.from(document.querySelectorAll('#appSort option')).map(o => o.value + '=' + o.textContent.trim()) }));
  check('2. Sort by Last updated is still in the sort menu', sorted.opt.includes('updated=Last updated'), sorted.opt.join());
  check('   it lists the newest update first and each row shows the date of its update (what the pill used to count)', sorted.names.slice(0, 4).join() === 'Fresh Update,System Thing,Week Old,Old Timer' && sorted.metas.filter(m => /^• updated /.test(m)).length >= 4, JSON.stringify(sorted));
  check('   no page error', page.__errors.length === 0, page.__errors.join(' | '));
  await page.close();

  // ---------------------------------------------------------------- 3) Saved Applications, the Debloater, Connected Devices
  page = await open();
  await page.evaluate(() => { customLists = [{ id: 'l1', name: 'Mixed', packages: ['com.u.fresh', 'com.s.sys'] }]; switchView('saved-lists'); renderSavedLists(); });
  await page.waitForTimeout(250);
  const saved = await pills(page, '#savedFilterRow .filter-pill');
  check('3. Saved Applications has All, Uninstalled, System, User, Enabled, Frozen, Running, Suspended, Patched: no Updated 7d', saved.join() === 'all,uninstalled,system,user,enabled,frozen,running,suspended,patched', saved.join());
  await page.evaluate(() => { uadLoaded = true; uadPackages = []; switchView('debloater'); renderUadList(); renderUadExtra(); });
  await page.waitForTimeout(250);
  const uad = await pills(page, '#uadExtraRow .filter-pill');
  check('   the Debloater\'s "Also show only" row has All, System, User, Running, Suspended, Patched: no Updated 7d', uad.join() === 'all,system,user,running,suspended,patched', uad.join());
  check('   Connected Devices never had it: its filters are Installed, Uninstalled, System, User, Enabled, Frozen', await page.evaluate(() => CD_FILTERS.map(f => f[0]).join() === 'installed,uninstalled,system,user,enabled,disabled'));
  check('   none of the three pill rows says Updated 7d', await page.evaluate(() => !/Updated 7d/.test(document.getElementById('savedFilterRow').innerText + document.getElementById('uadExtraRow').innerText)));
  await page.close();

  // ---------------------------------------------------------------- 4) a filter set that an older version remembered
  page = await open({ rememberFilters: true, filters: { apps: ['recent', 'running'], uad: ['recent', 'system'], saved: ['recent'] } });
  const kept = await page.evaluate(() => ({ apps: Array.from(activeFilters), uad: Array.from(uadExtra), saved: Array.from(savedFilters), lit: Array.from(document.querySelectorAll('#filterScroll .filter-pill.active')).map(p => p.getAttribute('data-filter')) }));
  check('4. remembered filters from before (Updated 7d among them) load without it and keep the rest', JSON.stringify([kept.apps, kept.uad, kept.saved]) === JSON.stringify([['running'], ['system'], []]) && kept.lit.join() === 'running' && page.__errors.length === 0, JSON.stringify(kept) + ' ' + page.__errors.join(' | '));
  await page.close();
  // a set that held only the removed filter comes back as All Apps, not as an empty list
  page = await open({ rememberFilters: true, filters: { apps: ['recent'] } });
  check('   a set that held only Updated 7d comes back as All Apps and lists every app', await page.evaluate(() => activeFilters.size === 0 && document.querySelector('#filterScroll .filter-pill.active').getAttribute('data-filter') === 'all') && (await page.locator('#appsListContainer .app-card').count()) === 5);
  await page.close();

  // ---------------------------------------------------------------- 5) the Help Guide (read from the file that ships next to the page)
  const guideText = fs.readFileSync(fileURLToPath(new URL('guide.js', PAGE)), 'utf8');
  check('5. the Help Guide no longer lists Updated 7d, and tells how to see what changed lately: sort by Last updated', guideText.length > 100000 && !/Updated 7d/.test(guideText) && /sort by[^"]{0,40}Last updated/i.test(guideText), 'has Updated 7d: ' + /Updated 7d/.test(guideText));

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e && e.message); process.exit(1); });
