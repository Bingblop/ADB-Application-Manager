// App list: patched/modified detection badge and "Patched" filter; the app sheet itself shows no patched line or mod tags (v7.14, the owner's choice).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const apps = [
      { pkg: 'app.revanced.android.youtube', name: 'YouTube ReVanced', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 34, version: '19.0', mods: ['ReVanced'] },
      { pkg: 'com.example.module', name: 'Some Xposed Module', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 33, version: '1.2', mods: ['Xposed/LSPosed module'] },
      { pkg: 'com.android.chrome', name: 'Chrome', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 34, version: '120', mods: [] },
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadPackages() { return JSON.stringify(apps); },
      getAppDetails(pkg) {
        const a = apps.find(x => x.pkg === pkg) || {};
        return JSON.stringify({
          versionName: a.version || '1.0', versionCode: 1, firstInstallTime: 0, lastUpdateTime: 0,
          sourceDir: '/data/app/' + pkg + '/base.apk', permissions: [], activities: [], services: [], receivers: [], providers: [],
          activityInfo: [], serviceInfo: [], receiverInfo: [], providerInfo: [], appopsRaw: '',
          mods: a.mods || [], installer: pkg.indexOf('revanced') >= 0 ? 'app.revanced.manager.flutter' : ''
        });
      },
      getApkSize() { return '0'; }, getAppStorage() { return '{}'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => { loadPackageData(); renderApps(); }); await page.waitForTimeout(120);

  // 1) Patched apps carry the 🧩 badge; the clean app does not.
  const rvBadges = await page.locator('#card_app\\.revanced\\.android\\.youtube .tag-mod').allInnerTexts();
  const chromeBadges = await page.locator('#card_com\\.android\\.chrome .tag-mod').count();
  console.log('1. ReVanced card badge:', JSON.stringify(rvBadges), '| Chrome has no mod badge:', chromeBadges === 0);

  // 2) The Patched filter count and filtering.
  const count = await page.locator('#countPatched').innerText();
  console.log('2. Patched count:', count, '(expect 2)');
  await page.evaluate(() => setFilter('patched', document.querySelector('#filterScroll .filter-pill[data-filter="patched"]'))); await page.waitForTimeout(80);
  const shown = await page.locator('#appsListContainer .app-card').count();
  const shownHasChrome = await page.locator('#card_com\\.android\\.chrome').count();
  console.log('   filter shows only patched:', shown === 2, '| chrome hidden:', shownHasChrome === 0);
  await page.evaluate(() => setFilter('all', document.querySelector('#filterScroll .filter-pill[data-filter="all"]'))); await page.waitForTimeout(80);

  // 3) The app sheet carries no "Patched / modified" line and no mod tags (the owner's choice, v7.14): the row tag and the Patched filter are where it shows.
  await page.evaluate(() => openInspector('app.revanced.android.youtube')); await page.waitForTimeout(120);
  const sheet = await page.evaluate(() => { const sh = document.getElementById('inspectorModal'); return { text: sh ? sh.innerText : '', tags: sh ? sh.querySelectorAll('.tag-mod').length : -1, old: !!document.getElementById('sheetMods') }; });
  console.log('3. app sheet has no Patched / modified line and no mod tags:', !/Patched \/ modified/.test(sheet.text) && sheet.tags === 0 && !sheet.old && /YouTube ReVanced/.test(sheet.text));

  // the sheet's deeper check still counts: a tag only it finds goes to the row and the Patched filter
  await page.evaluate(() => {
    const real = window.AndroidBridge.getAppDetails;
    window.AndroidBridge.getAppDetails = p => { const d = JSON.parse(real(p)); if (p === 'com.android.chrome') d.mods = ['Debug-signed (repackaged)']; return JSON.stringify(d); };
    closeInspector && closeInspector(); openInspector('com.android.chrome'); closeInspector && closeInspector(); renderApps();
  }); await page.waitForTimeout(150);
  console.log('   a tag found only by the sheet\'s deeper check shows on the row afterwards:', await page.evaluate(() => [...document.querySelectorAll('#card_com\\.android\\.chrome .tag-mod')].map(e => e.textContent).join('|')));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
