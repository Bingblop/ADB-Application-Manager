// ShizuStore tab: catalog load, search, sort, detail, install.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = [];
    const items = [
      { slug: 'alpha', name: 'Alpha Tool', description: 'First test app', packageName: 'com.alpha', versionName: '1.0', stars: 50, installCount: 10, downloadTotal: 100, categorySlug: 'tools', iconHash: '', updatedAt: '2026-01-01', versionUpdatedAt: '2026-01-02', listUpdatedAt: '2026-01-03' },
      { slug: 'bravo', name: 'Bravo Utility', description: 'Second test app', packageName: 'com.bravo', versionName: '2.1', stars: 500, installCount: 999, downloadTotal: 5000, categorySlug: 'utilities', iconHash: '', requiresRoot: true, updatedAt: '2026-02-01', versionUpdatedAt: '2026-02-02', listUpdatedAt: '2026-02-03' },
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      storeLoadCatalog() {
        window.__calls.push('catalog');
        setTimeout(() => window.onStoreCatalog(JSON.stringify({ status: 'ok', base: 'https://store.example', items, total: items.length })), 0);
      },
      storeLoadApp(slug) {
        window.__calls.push('app:' + slug);
        setTimeout(() => window.onStoreApp(JSON.stringify({
          status: 'ok', slug, base: 'https://store.example',
          app: { slug, name: slug === 'bravo' ? 'Bravo Utility' : 'Alpha Tool', packageName: 'com.' + slug, versionName: '2.1',
            fullDescription: 'Full description for ' + slug, stars: 500, downloadTotal: 5000, authorName: 'dev', license: 'GPL-3.0',
            categorySlug: 'utilities', permissions: ['android.permission.INTERNET'], screenshots: [], sourceUrl: 'https://github.com/x/' + slug },
          download: { apkUrl: 'https://store.example/' + slug + '.apk', packageName: 'com.' + slug, versionName: '2.1', size: 5242880, source: 'GitHub' }
        })), 0);
      },
      storeInstall(apkUrl, pkg, label) {
        window.__calls.push('install:' + pkg + ':' + apkUrl);
        setTimeout(() => window.onStoreInstallProgress(JSON.stringify({ pkg, stage: 'downloading', percent: 50, message: '2 MB' })), 0);
        setTimeout(() => window.onStoreInstallProgress(JSON.stringify({ pkg, stage: 'done', percent: 100, message: 'Installed 2.1.' })), 5);
      },
      openUrlExternal() { window.__calls.push('openUrl'); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  // 1) Store tab is the last tab button, and opening it loads the catalog.
  const tabLabels = await page.locator('.tab-btn').allInnerTexts();
  console.log('1. App Stores comes right before Logcat Viewer, then Task Manager, then About:', tabLabels[tabLabels.length - 4].includes('Stores') && tabLabels[tabLabels.length - 3].includes('Logcat') && tabLabels[tabLabels.length - 2].includes('Task') && tabLabels[tabLabels.length - 1].includes('About'));
  await page.evaluate(() => switchView('store')); await page.waitForTimeout(80);
  console.log('   catalog requested:', (await page.evaluate(() => window.__calls)).includes('catalog'));
  const cards = await page.locator('#storeList > div').count();
  console.log('   app cards rendered:', cards);

  // 2) Default sort is most-starred -> Bravo (500) before Alpha (50).
  const names = await page.locator('#storeList > div').allInnerTexts();
  console.log('2. default sort most-starred, Bravo first:', names[0].includes('Bravo'));

  // 3) Search filters the list.
  await page.fill('#storeSearch', 'alpha'); await page.waitForTimeout(50);
  const afterSearch = await page.locator('#storeList > div').count();
  console.log('3. search "alpha" narrows to:', afterSearch);
  await page.fill('#storeSearch', ''); await page.waitForTimeout(50);

  // 4) Sort by name A-Z puts Alpha first.
  await page.selectOption('#storeSort', 'name'); await page.waitForTimeout(50);
  const sorted = await page.locator('#storeList > div').allInnerTexts();
  console.log('4. name sort, Alpha first:', sorted[0].includes('Alpha'));

  // 5) Open a detail -> modal shows, install enabled.
  await page.locator('#storeList > div').first().click(); await page.waitForTimeout(60);
  const modalShown = await page.evaluate(() => document.getElementById('storeModal').classList.contains('show'));
  const installEnabled = await page.evaluate(() => !document.getElementById('storeInstallBtn').disabled);
  console.log('5. detail modal shown:', modalShown, '| install enabled:', installEnabled);
  console.log('   detail fetched:', (await page.evaluate(() => window.__calls)).some(c => c.startsWith('app:')));

  // 6) Install triggers the bridge and progresses to done.
  await page.click('#storeInstallBtn'); await page.waitForTimeout(60);
  const installed = (await page.evaluate(() => window.__calls)).some(c => c.startsWith('install:'));
  const progressText = await page.locator('#storeInstallProgress').innerText();
  console.log('6. install bridge called:', installed, '| progress:', JSON.stringify(progressText));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
