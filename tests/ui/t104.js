// Apps list: the "Enabled" filter pill (installed and not disabled), and filters that combine: running user apps, enabled system apps...
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [
    { pkg: 'com.example.on1', name: 'On One', isFrozen: false, isUninstalled: false, isSuspended: false, isRunning: true },
    { pkg: 'com.example.on2', name: 'On Two', isFrozen: false, isUninstalled: false, isSuspended: true, isSystem: true, isRunning: true },
    { pkg: 'com.example.sys', name: 'Sys Off', isFrozen: true, isUninstalled: false, isSuspended: false, isSystem: true },
    { pkg: 'com.example.sysgone', name: 'Sys Gone', isFrozen: false, isUninstalled: true, isSuspended: false, isSystem: true },
    { pkg: 'com.example.off', name: 'Off', isFrozen: true, isUninstalled: false, isSuspended: false },
    { pkg: 'com.example.gone', name: 'Gone', isFrozen: false, isUninstalled: true, isSuspended: false },
  ];
  await page.addInitScript(a => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; } };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  console.log('pills:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#filterScroll .filter-pill')).map(p => p.innerText.replace(/\s+/g, ' ').trim()))));
  await page.locator('#filterScroll .filter-pill[data-filter="enabled"]').click(); await page.waitForTimeout(250);
  console.log('Enabled shows installed apps that are not disabled (a suspended one counts):', JSON.stringify(await page.evaluate(() => [Array.from(activeFilters), document.querySelector('#filterScroll .filter-pill.active').getAttribute('data-filter'), Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText).sort()])));
  await page.locator('#filterScroll .filter-pill[data-filter="frozen"]').click(); await page.waitForTimeout(250);
  console.log('Frozen still works:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText))));
  const names = () => page.evaluate(() => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText).sort());
  const tap = f => page.locator('#filterScroll .filter-pill[data-filter="' + f + '"]').click().then(() => page.waitForTimeout(200));
  const lit = () => page.evaluate(() => [Array.from(document.querySelectorAll('#filterScroll .filter-pill.active')).map(p => p.getAttribute('data-filter')), Array.from(document.querySelectorAll('.stat-card.active')).map(c => c.getAttribute('data-filter'))]);
  // several at once
  await tap('all'); await tap('running'); await tap('user');
  console.log('Running + 3rd Party (your running user apps):', JSON.stringify([await names(), await lit(), await page.evaluate(() => document.getElementById('filterSummary').innerText)]));
  await tap('all'); await tap('enabled'); await tap('system');
  console.log('Enabled + System (your enabled system apps):', JSON.stringify([await names(), await lit()]));
  await tap('frozen');
  console.log('Frozen replaces Enabled (Enabled / Frozen are one filter: one on or none), System stays:', JSON.stringify([await names(), await lit()]));
  await tap('frozen');
  console.log('tapping Frozen again leaves none of the pair on:', JSON.stringify([await names(), await lit()]));
  await tap('user');
  console.log('3rd Party replaces System the same way:', JSON.stringify(await lit()));
  await tap('all'); await tap('system'); await tap('uninstalled');
  console.log('Uninstalled is its own filter: it combines with System and keeps the uninstalled system app:', JSON.stringify([await names(), await lit()]));
  await tap('frozen');
  console.log('and with Frozen next to it, nothing is replaced:', JSON.stringify(await lit()));
  await tap('all'); await tap('system'); await tap('uninstalled'); await tap('uninstalled');
  console.log('tapping an on filter turns it off:', JSON.stringify(await lit()));
  await tap('all');
  // the big boxes toggle the same filters
  await page.locator('.stat-card[data-filter="user"]').click(); await page.waitForTimeout(150);
  await page.locator('#filterScroll .filter-pill[data-filter="running"]').click(); await page.waitForTimeout(200);
  console.log('a box and a pill together:', JSON.stringify([await names(), await lit()]));
  await page.locator('.stat-card[data-filter="all"]').click(); await page.waitForTimeout(200);
  console.log('Total installed clears them all:', JSON.stringify([(await names()).length, await lit()]));
  // v7.9.21: the split boxes and the Clear filters chip
  console.log('top boxes:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('.stats-grid .stat-card')).map(c => [c.getAttribute('data-filter'), c.querySelector('.stat-label').innerText, c.querySelector('.stat-num').innerText, c.parentElement.classList.contains('stat-split')]))));
  console.log('pairs share a box:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('.stat-split')).map(b => Array.from(b.querySelectorAll('.stat-card')).map(c => c.getAttribute('data-filter'))))));
  const chip = () => page.evaluate(() => { const r = document.getElementById('filterSummary'); const c = document.getElementById('filterClearChip'); return [getComputedStyle(r).display !== 'none', getComputedStyle(c).display !== 'none', c.innerText]; });
  console.log('no chip with no filter:', JSON.stringify(await chip()));
  await page.locator('.stat-card[data-filter="enabled"]').click(); await page.waitForTimeout(150);
  console.log('no chip with one filter:', JSON.stringify([await chip(), await names()]));
  await page.locator('.stat-card[data-filter="system"]').click(); await page.waitForTimeout(150);
  console.log('Enabled box + System box (enabled system apps), chip shows:', JSON.stringify([await chip(), await names(), await lit()]));
  await page.locator('#filterClearChip').click(); await page.waitForTimeout(200);
  console.log('Clear filters turns them all off:', JSON.stringify([await chip(), (await names()).length, await lit()]));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
