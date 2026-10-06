// Action Button: the extra button on each row of the Apps list is chosen in Settings (after Icon pack). Always an icon; the dynamic ones follow the app's state;
// Permission Manager and Activity Launcher open a list sheet for the app.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  const apps = [
    { pkg: 'com.example.live', name: 'Live', isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.frozen', name: 'Frozen', isFrozen: true, isSuspended: true, isUninstalled: false },
    { pkg: 'com.example.gone', name: 'Gone', isFrozen: false, isSuspended: false, isUninstalled: true },
  ];
  await page.addInitScript(a => {
    window.__acts = []; window.__perm = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); }, getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected' }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      executeAppAction(act, pkg) { window.__acts.push([act, pkg]); return '\u00011ok'; },
      getAppDetails(pkg) {
        return JSON.stringify({ versionName: '1', versionCode: 1, permissions: [
          { name: 'android.permission.CAMERA', granted: true, changeable: true, protection: 'runtime', label: 'take pictures and videos', description: 'Allows the app to take photos.', group: 'android.permission-group.CAMERA' },
          { name: 'android.permission.INTERNET', granted: true, changeable: false, protection: 'normal', label: 'full network access' },
          { name: 'android.permission.READ_CONTACTS', granted: false, changeable: true, protection: 'runtime', label: 'read your contacts' }],
          activityInfo: [{ name: 'com.example.live.Main', exported: true, enabled: true, permission: '' }, { name: 'com.example.live.Secret', exported: false, enabled: false, permission: 'com.x.PERM' }] });
      },
      getUadMatches() { return '{"packages":[]}'; }, getUadStatus() { return '{"cached":true,"count":0}'; },
      setPermission(pkg, perm, on) { window.__perm.push([pkg, perm, on]); return '\u00011ok'; },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const btn = pkg => page.locator('#card_' + pkg.replace(/\./g, '\\.') + ' .ab-btn');
  const info = async pkg => (await btn(pkg).count() === 0) ? { ab: 'none' } : btn(pkg).first().evaluate(e => ({ ab: e.getAttribute('data-ab'), title: e.title, svg: !!e.querySelector('svg'), text: e.innerText.trim() }));
  console.log('default is App Settings, as an icon:', JSON.stringify(await info('com.example.live')));
  await btn('com.example.live').click(); await sleep(100);
  console.log('tap runs app_settings:', JSON.stringify(await page.evaluate(() => window.__acts)));

  // the card in Settings, after Icon pack
  await page.evaluate(() => switchView('prefs')); await sleep(200);
  console.log('card order:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#view-prefs > .color-card')).map(c => c.querySelector('.color-card-title').innerText.trim()).filter(t => ['Icon pack', 'Action Button', 'Feature List'].includes(t)))));
  console.log('options:', JSON.stringify(await page.locator('#actionBtnSelect option').allInnerTexts()));
  const pick = async v => { await page.selectOption('#actionBtnSelect', v); await sleep(150); };
  const state = async () => JSON.stringify(await Promise.all(['com.example.live', 'com.example.frozen', 'com.example.gone'].map(async p => (await info(p)).ab)));
  for (const v of ['forcestop', 'launch', 'toggle', 'uninstall', 'suspend', 'perms', 'acts', 'none', 'settings']) { await pick(v); console.log('icons for ' + v + ' (live, frozen, gone):', await state(), '| kept:', await page.evaluate(() => kvGet('action_btn', ''))); }

  console.log('None: no button on any row, the ⋯ menu stays:', await page.evaluate(() => { actionBtn = 'none'; renderApps(); return [document.querySelectorAll('.ab-btn').length, document.querySelectorAll('.app-actions .btn-mini').length === document.querySelectorAll('.app-card').length]; }));
  await page.evaluate(() => { actionBtn = 'settings'; renderApps(); });
  // each one does its thing
  await page.evaluate(() => switchView('apps')); await sleep(200);
  const run = async (v, pkg) => { await page.evaluate(() => { window.__acts.length = 0; }); await page.evaluate(v => { actionBtn = v; renderApps(); }, v); await sleep(120); await btn(pkg).click(); await sleep(150); return JSON.stringify(await page.evaluate(() => window.__acts)); };
  console.log('force stop:', await run('forcestop', 'com.example.live'));
  console.log('launch:', await run('launch', 'com.example.live'));
  console.log('toggle live -> freeze:', await run('toggle', 'com.example.live'));
  console.log('toggle frozen -> unfreeze:', await run('toggle', 'com.example.frozen'));
  console.log('uninstall live (asks first) -> uninstall:', await run('uninstall', 'com.example.live'), '| asked:', JSON.stringify(dialogs.slice(-1)));
  console.log('uninstall gone -> reinstall:', await run('uninstall', 'com.example.gone'));
  console.log('suspend live -> suspend:', await run('suspend', 'com.example.live'));
  console.log('suspend suspended -> unsuspend:', await run('suspend', 'com.example.frozen'));

  // Permission Manager
  await page.evaluate(() => { actionBtn = 'perms'; renderApps(); }); await sleep(120);
  await btn('com.example.live').click(); await sleep(250);
  console.log('Permission Manager sheet:', await page.evaluate(() => [document.getElementById('appListSheet').classList.contains('show'), document.getElementById('alsTitle').innerText, document.getElementById('alsSummary').innerText, document.querySelectorAll('#alsList .als-row').length]));
  console.log('  shows description, group and how it can change:', await page.evaluate(() => /Allows the app to take photos/.test(document.getElementById('alsList').innerText) && /Group: CAMERA/.test(document.getElementById('alsList').innerText) && /cannot be changed here/.test(document.getElementById('alsList').innerText)));
  await page.click('#alsFilterRow [data-filter="denied"]'); await sleep(100);
  console.log('  Denied filter:', await page.locator('#alsList .als-row').count());
  await page.click('#alsFilterRow [data-filter="all"]'); await page.fill('#alsSearch', 'camera'); await sleep(100);
  console.log('  search camera:', await page.locator('#alsList .als-row').count());
  await page.locator('#alsList .perm-toggle-btn.granted').first().click(); await sleep(200);
  console.log('  revoking calls setPermission:', JSON.stringify(await page.evaluate(() => window.__perm)));
  await page.evaluate(() => closeAppListSheet()); await sleep(100);

  // Bulk grant / revoke, on what the filter and search show
  await page.evaluate(() => { window.__perm.length = 0; });
  await page.evaluate(() => closeAppListSheet()); await page.evaluate(() => { actionBtn = 'perms'; renderApps(); }); await sleep(120);
  await btn('com.example.live').click(); await sleep(250);
  console.log('bulk buttons (counts):', await page.evaluate(() => [document.getElementById('alsGrantAll').innerText, document.getElementById('alsRevokeAll').innerText]));
  await page.click('#alsGrantAll'); await sleep(400);
  console.log('Grant all shown asks, then grants the one denied permission:', JSON.stringify(await page.evaluate(() => window.__perm)), '| asked:', JSON.stringify(dialogs.slice(-1)));
  await page.evaluate(() => { window.__perm.length = 0; });
  await page.click('#alsRevokeAll'); await sleep(400);
  console.log('Revoke all shown revokes the changeable granted ones (not the locked INTERNET):', JSON.stringify(await page.evaluate(() => window.__perm)));
  await page.click('#alsFilterRow [data-filter="locked"]'); await sleep(100);
  console.log('with only locked permissions shown both are empty:', await page.evaluate(() => [document.getElementById('alsGrantAll').disabled, document.getElementById('alsRevokeAll').disabled]));
  await page.evaluate(() => closeAppListSheet()); await sleep(100);

  // Hold the Action Button: every action for that app; a hold is not also a tap
  await page.evaluate(() => { actionBtn = 'settings'; window.__acts.length = 0; renderApps(); }); await sleep(120);
  const hb = await btn('com.example.frozen').boundingBox();
  await page.mouse.move(hb.x + 8, hb.y + 8); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up(); await sleep(200);
  console.log('hold opens the actions sheet:', await page.evaluate(() => [document.getElementById('abSheet').classList.contains('show'), document.getElementById('abSheetTitle').innerText, Array.from(document.querySelectorAll('#abSheetList .ab-sheet-row')).map(r => r.innerText.trim())]), '| the hold did not also tap:', JSON.stringify(await page.evaluate(() => window.__acts)));
  await page.locator('#abSheetList [data-kind="toggle"]').click(); await sleep(200);
  console.log('picking Enable (the app is frozen) runs unfreeze and closes the sheet:', JSON.stringify(await page.evaluate(() => window.__acts)), !(await page.evaluate(() => document.getElementById('abSheet').classList.contains('show'))));

  // Debloater rows get the same button
  await page.evaluate(() => {
    actionBtn = 'toggle';
    uadFilters.removal = new Set(['Recommended']); uadFilters.state = 'all'; uadFilters.list = 'all'; uadFilters.brand = 'all';
    uadPackages = [{ pkg: 'com.example.live', name: 'Live', state: 'enabled', removal: 'Recommended', brand: 'x', list: 'Oem', description: '' }, { pkg: 'com.example.frozen', name: 'Frozen', state: 'disabled', removal: 'Recommended', brand: 'x', list: 'Oem', description: '' }, { pkg: 'com.not.installed', name: 'Nope', state: 'enabled', removal: 'Recommended', brand: 'x', list: 'Oem', description: '' }];
    uadLoaded = true; renderUadList();
  });
  console.log('Debloater rows show the button for apps on the phone:', await page.evaluate(() => Array.from(document.querySelectorAll('.uad-row')).map(r => r.dataset.pkg + ':' + (r.querySelector('.ab-btn') ? r.querySelector('.ab-btn').getAttribute('data-ab') : 'none'))));
  await page.evaluate(() => { window.__acts.length = 0; });
  await page.evaluate(() => document.querySelector('.uad-row[data-pkg="com.example.live"] .ab-btn').click()); await sleep(150);
  console.log('tapping it on a Debloater row acts on that app:', JSON.stringify(await page.evaluate(() => window.__acts)));
  await page.evaluate(() => { actionBtn = 'none'; renderUadList(); });
  console.log('None hides it there too:', await page.evaluate(() => document.querySelectorAll('.uad-row .ab-btn').length));

  // Activity Launcher
  await page.evaluate(() => { actionBtn = 'acts'; renderApps(); }); await sleep(120);
  await btn('com.example.live').click(); await sleep(250);
  console.log('Activity Launcher sheet:', await page.evaluate(() => [document.getElementById('alsTitle').innerText, document.getElementById('alsSummary').innerText, document.querySelectorAll('#alsList .als-row').length, /Needs permission: com.x.PERM/.test(document.getElementById('alsList').innerText), /needs a working mode/.test(document.getElementById('alsList').innerText)]));
  await page.click('#alsFilterRow [data-filter="disabled"]'); await sleep(100);
  console.log('  Disabled filter:', await page.locator('#alsList .als-row').count());
  // Back closes the sheet
  await page.evaluate(() => handleAndroidBack()); await sleep(100);
  console.log('Back closes the sheet:', await page.evaluate(() => !document.getElementById('appListSheet').classList.contains('show')));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
