// App list: patched/modified detection badge, "Patched" filter, and inspector breakdown.
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
  await page.evaluate(() => setFilter('patched', document.querySelector('.filter-pill[data-filter="patched"]'))); await page.waitForTimeout(80);
  const shown = await page.locator('#appsListContainer .app-card').count();
  const shownHasChrome = await page.locator('#card_com\\.android\\.chrome').count();
  console.log('   filter shows only patched:', shown === 2, '| chrome hidden:', shownHasChrome === 0);
  await page.evaluate(() => setFilter('all', document.querySelector('.filter-pill[data-filter="all"]'))); await page.waitForTimeout(80);

  // 3) Inspector shows the modifications breakdown with the installer.
  await page.evaluate(() => openInspector('app.revanced.android.youtube')); await page.waitForTimeout(120);
  const modsVisible = await page.evaluate(() => {
    const el = document.getElementById('sheetMods');
    return el && el.style.display !== 'none' ? el.innerText : '(hidden)';
  });
  console.log('3. inspector mods line:', JSON.stringify(modsVisible));
  console.log('   names ReVanced + installer:', /revanced/i.test(modsVisible) && /revanced\.manager/i.test(modsVisible));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
