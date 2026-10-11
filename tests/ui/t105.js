// The combining filters in the Debloater (extra pills next to its own rows) and in Saved Applications (applied to the apps inside each list).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [
    { pkg: 'com.u.run', name: 'User Run', isRunning: true, isSystem: false, isFrozen: false, isUninstalled: false, isSuspended: false },
    { pkg: 'com.u.idle', name: 'User Idle', isRunning: false, isSystem: false, isFrozen: false, isUninstalled: false, isSuspended: false },
    { pkg: 'com.s.run', name: 'Sys Run', isRunning: true, isSystem: true, isFrozen: false, isUninstalled: false, isSuspended: false },
    { pkg: 'com.s.off', name: 'Sys Off', isRunning: false, isSystem: true, isFrozen: true, isUninstalled: false, isSuspended: false },
    { pkg: 'com.s.gone', name: 'Sys Gone', isRunning: false, isSystem: true, isFrozen: false, isUninstalled: true, isSuspended: false },
  ];
  await page.addInitScript(a => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      getUadMatches() { return '{"packages":[]}'; }, getUadStatus() { return '{"cached":true,"count":0}'; } };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);

  // ---- Saved Applications
  await page.evaluate(() => {
    customLists = [{ id: 'l1', name: 'Mixed', packages: ['com.u.run', 'com.u.idle', 'com.s.run', 'com.s.off', 'com.not.here'] }, { id: 'l2', name: 'Only idle', packages: ['com.u.idle'] }];
    switchView('saved-lists'); renderSavedLists();
  }); await sleep(250);
  const cards = () => page.evaluate(() => Array.from(document.querySelectorAll('.saved-list-card')).map(c => c.querySelector('.saved-list-title').innerText + ' ' + c.querySelector('.saved-list-meta').innerText + ' [' + Array.from(c.querySelectorAll('.saved-pkg-chip')).map(x => x.innerText).join(',') + '] ' + c.querySelector('.saved-list-actions button').innerText));
  const tapS = k => page.locator('#savedFilterRow .filter-pill[data-filter="' + k + '"]').click().then(() => sleep(200));
  const litS = () => page.evaluate(() => Array.from(document.querySelectorAll('#savedFilterRow .filter-pill.active')).map(p => p.getAttribute('data-filter')));
  console.log('Saved: pills:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#savedFilterRow .filter-pill')).map(p => p.innerText))));
  console.log('Saved: no filter shows everything:', JSON.stringify(await cards()));
  await tapS('running'); await tapS('user');
  console.log('Saved: Running + 3rd Party (lists without a match disappear):', JSON.stringify([await cards(), await litS(), await page.evaluate(() => document.getElementById('savedFilterSummary').innerText)]));
  await page.locator('#savedFilterSummary .filter-clear-chip').click(); await sleep(200);
  console.log('Saved: Clear filters button turns them all off:', JSON.stringify([await litS(), await cards()]));
  await tapS('all'); await tapS('system'); await tapS('frozen');
  console.log('Saved: System + Frozen:', JSON.stringify([await cards(), await litS()]));
  await tapS('enabled');
  console.log('Saved: Enabled replaces Frozen, System stays:', JSON.stringify([await cards(), await litS()]));
  await page.locator('.saved-list-card', { hasText: 'Mixed' }).locator('button', { hasText: /^Recall/ }).click(); await sleep(250);
  console.log('Saved: Recall takes only the matching apps:', JSON.stringify(await page.evaluate(() => [Array.from(selectedPkgs).sort(), document.getElementById('toastMsg').innerText])));
  await page.evaluate(() => { clearBatchSelection(); switchView('saved-lists'); }); await sleep(200);
  await tapS('uninstalled'); await tapS('running');
  console.log('Saved: nothing matches -> a message:', await page.evaluate(() => document.getElementById('savedListsContainer').innerText.trim()));
  await tapS('all');

  // ---- Debloater
  await page.evaluate(list => {
    uadFilters.removal = new Set(['Recommended']); uadFilters.state = 'all'; uadFilters.list = 'all'; uadFilters.brand = 'all';
    uadPackages = list.map(a => ({ pkg: a.pkg, name: a.name, state: a.isUninstalled ? 'uninstalled' : a.isFrozen ? 'disabled' : 'enabled', removal: 'Recommended', brand: 'x', list: 'Oem', description: '', isSuspended: false }));
    uadLoaded = true; switchView('debloater'); renderUadList();
  }, apps);
  await sleep(250);
  const rowsD = () => page.evaluate(() => Array.from(document.querySelectorAll('.uad-row .uad-name')).map(e => e.innerText).sort());
  const tapD = k => page.locator('#uadExtraRow .filter-pill[data-filter="' + k + '"]').click().then(() => sleep(200));
  console.log('Debloater: extra pills:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#uadExtraRow .filter-pill')).map(p => p.innerText))));
  console.log('Debloater: none on shows all five:', JSON.stringify(await rowsD()));
  await tapD('running'); await tapD('system');
  console.log('Debloater: Running + System:', JSON.stringify(await rowsD()));
  console.log('Debloater: chip shows with two on, and clears them:', JSON.stringify([await page.evaluate(() => getComputedStyle(document.getElementById('uadExtraSummary')).display !== 'none'), await page.locator('#uadExtraSummary .filter-clear-chip').click().then(() => sleep(200)).then(() => page.evaluate(() => [getComputedStyle(document.getElementById('uadExtraSummary')).display !== 'none', Array.from(document.querySelectorAll('#uadExtraRow .filter-pill.active')).map(p => p.getAttribute('data-filter'))]))]));
  await tapD('running'); await tapD('system');
  await tapD('user');
  console.log('Debloater: 3rd Party replaces System:', JSON.stringify([await rowsD(), await page.evaluate(() => Array.from(document.querySelectorAll('#uadExtraRow .filter-pill.active')).map(p => p.getAttribute('data-filter')))]));
  await tapD('all');
  console.log('Debloater: All clears them:', JSON.stringify((await rowsD()).length));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
