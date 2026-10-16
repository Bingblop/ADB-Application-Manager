// App list: a tag that only the app sheet's deeper check finds (signing key, the app's file) shows on the row and in the Patched count as soon as the
// sheet has loaded, and stays there when the list reloads (after an action from the sheet, Refresh) while the app is the same install; an update drops it.
// The sheet itself still has no Patched / modified box (v7.14, the owner's choice).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    // the list's cheap check finds nothing on Chrome; the sheet's deep check finds the debug signing key
    const apps = [
      { pkg: 'app.revanced.android.youtube', name: 'YouTube ReVanced', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 34, version: '19.0', updatedAt: 1700000000000, mods: ['ReVanced'] },
      { pkg: 'com.android.chrome', name: 'Chrome', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 34, version: '120', updatedAt: 1710000000000, mods: [] },
      { pkg: 'com.example.notes', name: 'Notes', isSystem: false, isRunning: false, isFrozen: false, isSuspended: false, isUninstalled: false, targetSdk: 34, version: '2', updatedAt: 1720000000000, mods: [] },
    ];
    const deep = { 'app.revanced.android.youtube': ['ReVanced'], 'com.android.chrome': ['Debug-signed (repackaged)'] };
    window.__apps = apps; window.__loads = 0;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadPackages() { window.__loads++; return JSON.stringify(apps); },
      getAppDetails(pkg) {
        const a = apps.find(x => x.pkg === pkg) || {};
        return JSON.stringify({
          versionName: a.version || '1.0', versionCode: 1, firstInstallTime: 0, lastUpdateTime: a.updatedAt || 0,
          sourceDir: '/data/app/' + pkg + '/base.apk', permissions: [], activities: [], services: [], receivers: [], providers: [],
          activityInfo: [], serviceInfo: [], receiverInfo: [], providerInfo: [], appopsRaw: '', mods: deep[pkg] || [], installer: ''
        });
      },
      getApkSize() { return '0'; }, getAppStorage() { return '{}'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => { loadPackageData(); }); await page.waitForTimeout(120);
  const row = () => page.evaluate(() => ({ tags: [...document.querySelectorAll('#card_com\\.android\\.chrome .tag-mod')].map(e => e.textContent).join('|'), count: document.getElementById('countPatched').innerText }));
  const patchedFilter = async () => {
    await page.evaluate(() => setFilter('patched', document.querySelector('#filterScroll .filter-pill[data-filter="patched"]'))); await page.waitForTimeout(80);
    const shown = await page.evaluate(() => [...document.querySelectorAll('#appsListContainer .app-card')].map(c => c.id.replace(/^card_/, '')).join(','));
    await page.evaluate(() => setFilter('all', document.querySelector('#filterScroll .filter-pill[data-filter="all"]'))); await page.waitForTimeout(80);
    return shown;
  };

  let r = await row();
  check('1. before the sheet: Chrome has no tag and only YouTube ReVanced is counted', r.tags === '' && r.count === '1', JSON.stringify(r));

  // a sheet whose deeper check finds nothing new does not redraw the list
  await page.evaluate(() => { window.__draws = 0; const real = window.renderApps; window.renderApps = function () { window.__draws++; return real.apply(this, arguments); }; });
  await page.evaluate(() => openInspector('app.revanced.android.youtube')); await page.waitForTimeout(150);
  check('2. the sheet of an app the list already tags does not redraw the list', await page.evaluate(() => window.__draws) === 0, await page.evaluate(() => window.__draws));
  await page.evaluate(() => closeInspector()); await page.waitForTimeout(150);

  await page.evaluate(() => openInspector('com.android.chrome')); await page.waitForTimeout(150);
  r = await row();
  check('3. as soon as Chrome\'s sheet has loaded, its row carries the deeper check\'s tag and the Patched count is 2', r.tags === 'Debug-signed (repackaged)' && r.count === '2', JSON.stringify(r));
  const sheet = await page.evaluate(() => { const sh = document.getElementById('inspectorModal'); return { box: !!document.getElementById('sheetMods'), tags: sh.querySelectorAll('.tag-mod').length, said: /Patched \/ modified/.test(sh.innerText) }; });
  check('   the sheet itself still shows no Patched / modified box', !sheet.box && sheet.tags === 0 && !sheet.said, JSON.stringify(sheet));

  // an action from the sheet reloads the list 400 ms later; Refresh reloads it too
  const loads = await page.evaluate(() => window.__loads);
  await page.evaluate(() => loadPackageData()); await page.waitForTimeout(120);
  r = await row();
  check('4. the list reloads with the sheet open: the tag and the count stay', await page.evaluate(() => window.__loads) === loads + 1 && r.tags === 'Debug-signed (repackaged)' && r.count === '2', JSON.stringify(r));
  await page.evaluate(() => closeInspector()); await page.waitForTimeout(150);
  await page.evaluate(() => refreshAppsList()); await page.waitForTimeout(700);
  r = await row();
  check('   and after Refresh with the sheet closed', await page.evaluate(() => window.__loads) === loads + 2 && r.tags === 'Debug-signed (repackaged)' && r.count === '2', JSON.stringify(r));
  const shown = await patchedFilter();
  check('   the Patched filter still shows Chrome', shown === 'com.android.chrome,app.revanced.android.youtube' || shown === 'app.revanced.android.youtube,com.android.chrome', shown);

  // Chrome is updated (or replaced with the store build): the old finding is dropped
  await page.evaluate(() => { window.__apps[1].updatedAt = 1730000000000; loadPackageData(); }); await page.waitForTimeout(120);
  r = await row();
  check('5. after an update of Chrome the tag is gone and the count is 1 again', r.tags === '' && r.count === '1', JSON.stringify(r));
  await page.evaluate(() => loadPackageData()); await page.waitForTimeout(120);
  r = await row();
  check('   and it does not come back on the next reload', r.tags === '' && r.count === '1', JSON.stringify(r));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
