// v5.7 installer: XAPK + OBB data, no default-installer button (text kept), automatic storage scan list.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { scan: 0, pick: 0, inspect: [], fmInstall: [], grant: 0 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku',
        adbTcp: { connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      scanApkFiles() { window.__calls.scan++; },
      pickInstallerFile() { window.__calls.pick++; },
      inspectInstallSource(ref) { window.__calls.inspect.push(ref); },
      fmInstall(p) { window.__calls.fmInstall.push(p); return JSON.stringify({ ok: true, ref: '/data/local/tmp/fm_install.apk' }); },
      requestAllFilesAccess() { window.__calls.grant++; },
      installSelected() {},
      loadSetting() { return ''; }, saveSetting() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  // 1) Opening the Installer tab starts the automatic storage search.
  await page.evaluate(() => switchView('installer')); await page.waitForTimeout(150);
  const scanned = await page.evaluate(() => window.__calls.scan);
  const status0 = await page.locator('#apkScanStatus').innerText();
  console.log('1. auto-scan started on first open:', scanned === 1 && /Searching/i.test(status0), '|', JSON.stringify(status0));

  // 2) No "Set as default installer" button; the explanation stays at the bottom of the card.
  const btnTexts = await page.locator('#view-installer .color-card').first().locator('button').allInnerTexts();
  const hasDefaultBtn = btnTexts.some(t => /default installer/i.test(t));
  const cardText = await page.locator('#view-installer .color-card').first().innerText();
  const lastLine = cardText.trim().split('\n').pop();
  console.log('2. default-installer button removed:', !hasDefaultBtn, '| text kept at the bottom:', /default installer/i.test(lastLine) && /Always/.test(lastLine), JSON.stringify(lastLine));
  console.log('   buttons:', JSON.stringify(btnTexts));

  // 3) Chooser + XAPK entry point.
  const hasChooser = btnTexts.some(t => /APK.*APKS.*APKM.*XAPK/i.test(t));
  const hasXapk = btnTexts.some(t => /XAPK/i.test(t) && /OBB/i.test(t));
  await page.locator('#installXapkBtn').click(); await page.waitForTimeout(40);
  const pickN = await page.evaluate(() => window.__calls.pick);
  const hint = await page.locator('#installPickHint').innerText();
  console.log('3. chooser mentions XAPK + XAPK button opens picker:', hasChooser && hasXapk && pickN === 1 && /xapk/i.test(hint));

  // 4) Scan results render as a list with kind badges, size, age and folder.
  const now = Date.now();
  await page.evaluate(n => onApkScan(JSON.stringify({ status: 'ok', truncated: false, files: [
    { path: '/storage/emulated/0/Download/Game.xapk', name: 'Game.xapk', kind: 'xapk', size: 734003200, mtime: n - 3600e3, shell: false },
    { path: '/storage/emulated/0/Download/App.apk', name: 'App.apk', kind: 'apk', size: 12582912, mtime: n - 2 * 86400e3, shell: false },
    { path: '/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/Bundle.apks', name: 'Bundle.apks', kind: 'apks', size: 9437184, mtime: n - 40 * 86400e3, shell: true },
    { path: '/storage/1234-ABCD/Backup/Mirror.apkm', name: 'Mirror.apkm', kind: 'apkm', size: 5242880, mtime: n - 86400e3, shell: false },
  ] })), now);
  await page.waitForTimeout(60);
  const rows = await page.locator('#apkScanList .apk-scan-row').count();
  const kinds = await page.locator('#apkScanList .apk-kind').allInnerTexts();
  const firstMeta = await page.locator('#apkScanList .apk-scan-row').first().innerText();
  console.log('4. 4 rows with kind badges:', rows === 4 && JSON.stringify(kinds) === JSON.stringify(['XAPK', 'APK', 'APKS', 'APKM']), JSON.stringify(kinds));
  console.log('   row shows size, age, short folder:', /700\.0 MB/.test(firstMeta) && /today/.test(firstMeta) && /Internal storage › Download/.test(firstMeta), JSON.stringify(firstMeta.replace(/\n/g, ' | ')));
  const status1 = await page.locator('#apkScanStatus').innerText();
  console.log('   count line:', /4 of 4 files found/.test(status1), JSON.stringify(status1));
  const sd = await page.locator('#apkScanList').innerText();
  console.log('   SD-card path shortened:', /SD card 1234-ABCD › Backup/.test(sd));

  // 5) Filters: by kind and by text.
  await page.selectOption('#apkScanKind', 'apk'); await page.waitForTimeout(40);
  const apkOnly = await page.locator('#apkScanList .apk-scan-row').count();
  await page.selectOption('#apkScanKind', '');
  await page.fill('#apkScanSearch', 'telegram'); await page.waitForTimeout(40);
  const byText = await page.locator('#apkScanList .apk-scan-row').count();
  await page.fill('#apkScanSearch', '');
  console.log('5. kind filter -> 1 APK:', apkOnly === 1, '| text filter "telegram" -> 1:', byText === 1);

  // 6) Tapping a readable file loads it directly; a shell-only file is staged via fmInstall first.
  await page.locator('#apkScanList .apk-scan-row', { hasText: 'App.apk' }).click(); await page.waitForTimeout(40);
  await page.locator('#apkScanList .apk-scan-row', { hasText: 'Bundle.apks' }).click(); await page.waitForTimeout(40);
  const calls = await page.evaluate(() => window.__calls);
  console.log('6. readable file inspected in place:', calls.inspect[0] === '/storage/emulated/0/Download/App.apk');
  console.log('   shell-only file staged first, then inspected:', calls.fmInstall[0] === '/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/Bundle.apks' && calls.inspect[1] === '/data/local/tmp/fm_install.apk');

  // 7) No access: asks for it, with a working button; then a manual re-scan works.
  await page.evaluate(() => { apkScanning = false; onApkScan(JSON.stringify({ status: 'noaccess' })); });
  const noacc = await page.locator('#apkScanStatus').innerText();
  await page.locator('#apkScanStatus button').click(); await page.waitForTimeout(40);
  console.log('7. no-access prompt + grant button:', /All-files access/i.test(noacc) && (await page.evaluate(() => window.__calls.grant)) === 1);
  await page.locator('#apkScanBtn').click(); await page.waitForTimeout(40);
  console.log('   Find APKs button re-runs the scan:', (await page.evaluate(() => window.__calls.scan)) === 2);
  await page.evaluate(() => onApkScan(JSON.stringify({ status: 'ok', files: [] })));
  console.log('   empty result message:', /No APK, APKS, APKM or XAPK files found/.test(await page.locator('#apkScanStatus').innerText()));

  // 8) A loaded XAPK shows the game-data card; the option controls what is sent natively.
  await page.evaluate(() => { installInspectRef = null; onInstallInspected(JSON.stringify({ ref: '/x/Game.xapk', type: 'xapk', pkg: 'com.foo.game', label: 'Foo Game', versionName: '1.2', versionCode: 12,
    minSdk: 24, targetSdk: 33, signed: true, installed: false, totalSize: 52428800,
    splits: [{ path: '/c/installer/0__base.apk', name: '0__base.apk', size: 40000000, isBase: true, split: '' }],
    extras: [{ name: 'main.12.com.foo.game.obb', dest: 'Android/obb/com.foo.game/main.12.com.foo.game.obb', size: 734003200 },
             { name: 'patch.12.com.foo.game.obb', dest: 'Android/obb/com.foo.game/patch.12.com.foo.game.obb', size: 10485760 }],
    extrasTotal: 744488960 })); });
  await page.waitForTimeout(80);
  const cardShown = await page.locator('#installExtrasCard').isVisible();
  const extraRows = await page.locator('#installExtrasList .switch-row').count();
  const extraText = await page.locator('#installExtrasList').innerText();
  console.log('8. data card shown with 2 files + destination:', cardShown && extraRows === 2 && /\/sdcard\/Android\/obb\/com\.foo\.game\//.test(extraText) && /700\.0 MB/.test(extraText));
  const hint8 = await page.locator('#installPickHint').innerText();
  console.log('   hint mentions XAPK + data files:', /XAPK with 2 game data files/.test(hint8), JSON.stringify(hint8));
  const on = await page.evaluate(() => buildInstallOptions().opts.copyExtras);
  const prevOn = await page.locator('#installPreview').innerText();
  await page.evaluate(() => { const c = document.getElementById('optCopyExtras'); c.checked = false; updateInstallPreview(); });
  const off = await page.evaluate(() => buildInstallOptions().opts.copyExtras);
  const prevOff = await page.locator('#installPreview').innerText();
  console.log('   copyExtras true by default / false when switched off:', on === true && off === false);
  console.log('   preview mentions the copy only when on:', /copy 2 data files/.test(prevOn) && !/copy 2 data files/.test(prevOff), JSON.stringify(prevOn.replace(/\n/g, ' | ')));

  // 9) Plain APK: no data card, copyExtras false.
  await page.evaluate(() => { installInspectRef = null; onInstallInspected(JSON.stringify({ ref: '/x/A.apk', type: 'apk', pkg: 'com.a', label: 'A', versionName: '1', versionCode: 1, signed: true, installed: false, totalSize: 100,
    splits: [{ path: '/c/installer/base.apk', name: 'base.apk', size: 100, isBase: true, split: '' }], extras: [], extrasTotal: 0 })); });
  await page.waitForTimeout(60);
  console.log('9. plain APK hides data card + copyExtras false:', !(await page.locator('#installExtrasCard').isVisible()) && (await page.evaluate(() => buildInstallOptions().opts.copyExtras)) === false);

  // 10) Progress + result text.
  await page.evaluate(() => onInstallProgress('Copying data file 1 of 2: main.12.com.foo.game.obb (700.0 MB)…'));
  const prog = await page.locator('#installProgress').innerText();
  console.log('10. live progress shown:', /Copying data file 1 of 2/.test(prog));
  await page.evaluate(() => onInstallResult(JSON.stringify({ ok: true, method: 'shizuku', output: 'Success', extras: '2 of 2 data files copied (710.0 MB) to Android/obb/com.foo.game/' })));
  await page.waitForTimeout(80);
  const modal = await page.locator('#commandResultsList').innerText();
  console.log('    result lists game data outcome + clears progress:', /Game data \(OBB\)/.test(modal) && /2 of 2 data files copied/.test(modal) && !(await page.locator('#installProgress').isVisible()));

  // 11) File manager offers Install for every package type.
  const offered = await page.evaluate(() => {
    const out = {};
    ['a.apk', 'b.APKS', 'c.apkm', 'd.xapk', 'e.zip'].forEach(n => { fmActions('/storage/emulated/0/' + n, false, n); out[n] = document.getElementById('fmActionBtns').innerHTML.includes('fmInstallFile'); });
    return out;
  });
  console.log('11. file manager Install offered for apk/apks/apkm/xapk only:', offered['a.apk'] && offered['b.APKS'] && offered['c.apkm'] && offered['d.xapk'] && !offered['e.zip'], JSON.stringify(offered));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
