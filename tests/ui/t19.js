// Copy/share and find: menu chips, no Share APK button, manifest/terminal find, CSV, package lists, profiles
const { chromium, PAGE } = require('./lib/pw');
const appBatchMock = require('./lib/appbatch_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = []; window.__store = {};
    const apps = [
      { pkg: 'com.facebook.appmanager', name: 'Facebook App Manager', isSystem: true, version: '1.0', isFrozen: true },
      { pkg: 'com.netflix.partner', name: 'Netflix Partner', isSystem: true, version: '2.0', isSuspended: true },
      { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0.3.1' },
      { pkg: 'com.example.gone', name: 'Gone App', isSystem: false, version: '3', isUninstalled: true },
      { pkg: 'com.example.keep', name: 'Keep App', isSystem: false, version: '4' },
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore(k, v) { window.__store[k] = v; }, loadStore(k) { return window.__store[k] || ''; },
      loadPackages() { return JSON.stringify(apps); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 1, firstInstallTime: 1700000000000, permissions: [] }); },
      getAppSizes() { return JSON.stringify({ apk: 1e6, splits: 1 }); },
      getAppManifest() { return '<manifest package="x">\n  <uses-permission name="INTERNET"/>\n  <uses-permission name="CAMERA"/>\n  <application label="internet app"/>\n</manifest>'; },
      executeAppAction(a, p) { window.__calls.push(a + ':' + p); return 'Success'; },
      executeShell(c) { return 'alpha\nbeta alpha\ngamma'; },
      copyToClipboard(t) { window.__calls.push('copy:' + t); },
      shareText(s, t) { window.__calls.push('shareText:' + s + '|' + t); },
      shareTextFile(n, t, m) { window.__calls.push('shareFile:' + n + '|' + m + '|' + t.length); return ''; },
      shareStoredFile(r, m, n) { window.__calls.push('shareStored:' + r + '|' + m + '|' + n); return ''; },
      extractApk(p) { setTimeout(() => window.onApkExtracted(JSON.stringify({ ok: true, path: 'Download/ADB App Manager/APKs/x.apk', ref: 'content://media/1', mime: 'application/vnd.android.package-archive', bytes: 10, splits: 1 })), 20); },
    };
  });
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(400);
  const calls = () => page.evaluate(() => window.__calls.slice());
  // copy chips in app menu
  await page.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await page.waitForTimeout(300);
  await page.evaluate(() => { copyAppField('pkg'); copyAppField('version'); shareAppInfo(); });
  console.log('menu copy/share:', JSON.stringify(await calls()));
  // the app menu has no Share APK button; Extract APK saves the file and shares nothing
  console.log('share apk button:', await page.locator('#sheetBtnShareApk').count(), '| any menu button saying Share APK:', await page.locator('.sheet-action-grid .sheet-btn', { hasText: 'Share APK' }).count(), '| visible menu buttons:', await page.evaluate(() => [...document.querySelectorAll('.sheet-action-grid .sheet-btn')].filter(b => b.offsetParent !== null).length));
  await page.click('#sheetBtnExtract'); await page.waitForTimeout(200);
  console.log('extract toast:', await page.locator('#toastMsg').innerText(), '| shared:', (await calls()).filter(c => c.startsWith('shareStored')).join() || '(nothing)');
  // manifest find: highlight + nav
  await page.evaluate(() => switchSheetTab('manifest')); await page.waitForTimeout(200);
  await page.fill('#manifestSearch', 'internet');
  console.log('manifest matches-only meta:', await page.innerText('#manifestMeta'), '| marks:', await page.locator('#manifestContainer mark.find-hit').count());
  await page.evaluate(() => toggleManifestOnly());
  console.log('full view meta:', await page.innerText('#manifestMeta'), '| lines shown:', (await page.innerText('#manifestContainer')).split('\n').length);
  await page.evaluate(() => manifestStep(1));
  console.log('next →', await page.innerText('#manifestMeta'), '| current:', await page.locator('mark.find-hit.current').count());
  await page.evaluate(() => manifestStep(1));
  console.log('wraps →', await page.innerText('#manifestMeta'));
  await page.evaluate(() => manifestStep(-1));
  console.log('prev →', await page.innerText('#manifestMeta'));
  await page.evaluate(() => shareManifest());
  await page.evaluate(() => closeInspector());
  // terminal find
  await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.fill('#termCmd', 'x');
  await page.evaluate(() => { runTerminalCmd(); closeCommandResultsModal(); }); await page.waitForTimeout(200);
  await page.fill('#termSearch', 'alpha');
  console.log('terminal find:', await page.innerText('#termFindCount'));
  await page.evaluate(() => termFind(1));
  console.log('terminal next:', await page.innerText('#termFindCount'));
  await page.fill('#termCmd', 'y'); await page.evaluate(() => { runTerminalCmd(); closeCommandResultsModal(); });
  console.log('after new output (re-highlighted):', await page.innerText('#termFindCount'));
  await page.evaluate(() => { copyTerminal(); shareTerminal(); });
  // CSV share + selected packages
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(200);
  await page.evaluate(() => { exportAppList(true); selectedPkgs.add('com.example.keep'); selectedPkgs.add('com.sec.android.app.sbrowser'); copySelectedPackages(); shareSelectedPackages(); selectedPkgs.clear(); });
  const c = await calls();
  console.log('csv share:', c.find(x => x.startsWith('shareFile:app_list')));
  console.log('copy pkgs:', JSON.stringify(c.find(x => x.startsWith('copy:com.example'))), '| share list:', JSON.stringify(c.find(x => x.startsWith('shareText:Package list'))));
  // profiles: save, share, apply on a "fresh" state
  await page.evaluate(() => openProfiles()); await page.fill('#profileName', 'Lean');
  await page.evaluate(() => saveProfile());
  console.log('profile saved:', await page.innerText('#profilesList').then(t => t.replace(/\s+/g, ' ').slice(0, 120)));
  console.log('persisted:', await page.evaluate(() => JSON.parse(window.__store.profiles)[0].apps.map(a => a.pkg + '=' + a.state).join(',')));
  await page.screenshot({ path: 'profiles.png' });
  // pretend this is a new phone: everything enabled, then apply
  await page.evaluate(() => { allApps.forEach(a => { a.isFrozen = a.isSuspended = a.isUninstalled = false; }); window.__calls.length = 0; const id = profiles[0].id; previewProfile(id); });
  console.log('preview:', (await page.innerText('#profilePreview')).replace(/\s+/g, ' '));
  await page.evaluate(() => applyProfile()); await page.waitForTimeout(300);
  console.log('applied:', JSON.stringify((await calls()).filter(x => !x.startsWith('copy'))));
  console.log('history entry:', await page.evaluate(() => JSON.parse(window.__store.debloat_history)[0].title));
  // import round trip + bad import
  await page.evaluate(() => { document.getElementById('closeCommandResults') ; closeCommandResultsModal(); openProfiles(); const j = profileJson(profiles[0].id); document.getElementById('profileImport').value = j; importProfile(); });
  console.log('profiles after import:', await page.evaluate(() => profiles.length));
  await page.evaluate(() => { document.getElementById('profileImport').value = '{"x":1}'; importProfile(); });
  console.log('bad import rejected, still:', await page.evaluate(() => profiles.length));
  // menus unselectable
  console.log('chip user-select:', await page.locator('.copy-chip').first().evaluate(e => getComputedStyle(e).userSelect));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
