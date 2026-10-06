// Apps list: the "Enabled" filter pill (installed and not disabled), with its count, between Updated 7d... and Frozen.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [
    { pkg: 'com.example.on1', name: 'On One', isFrozen: false, isUninstalled: false, isSuspended: false },
    { pkg: 'com.example.on2', name: 'On Two', isFrozen: false, isUninstalled: false, isSuspended: true },
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
  console.log('Enabled shows installed apps that are not disabled (a suspended one counts):', JSON.stringify(await page.evaluate(() => [currentFilter, document.querySelector('.filter-pill.active').getAttribute('data-filter'), Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText).sort()])));
  await page.locator('#filterScroll .filter-pill[data-filter="frozen"]').click(); await page.waitForTimeout(250);
  console.log('Frozen still works:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(e => e.innerText))));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
