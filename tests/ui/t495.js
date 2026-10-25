// The Installer's storage search says when it stopped early (MA2-1): a partial list carries the "search stopped early" note, and a search
// that stopped before it found anything does not claim "No APK, APKS, APKM or XAPK files found on this device".
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { scan: 0 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku',
        adbTcp: { connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      scanApkFiles() { window.__calls.scan++; },
      pickInstallerFile() {}, inspectInstallSource() {}, requestAllFilesAccess() {}, installSelected() {},
      loadSetting() { return ''; }, saveSetting() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('installer')); await page.waitForTimeout(150);
  check('1. the Installer tab started a search', await page.evaluate(() => window.__calls.scan === 1));
  const status = () => page.locator('#apkScanStatus').innerText();
  const file = { path: '/storage/emulated/0/Download/App.apk', name: 'App.apk', kind: 'apk', size: 12582912, mtime: Date.now() - 3600e3, shell: false };

  // a search that stopped early with some files: they are listed and the note says the list is not complete
  await page.evaluate(f => onApkScan(JSON.stringify({ status: 'ok', truncated: true, files: [f] })), file);
  await page.waitForTimeout(60);
  const s2 = await status();
  check('2. a partial list says the search stopped early', /1 of 1 file found/.test(s2) && /search stopped early/.test(s2), JSON.stringify(s2));
  check('   ... and lists what was found', await page.locator('#apkScanList .apk-scan-row').count() === 1);

  // a search that stopped early before it found anything: not "no files on this device"
  await page.evaluate(() => onApkScan(JSON.stringify({ status: 'ok', truncated: true, files: [] })));
  await page.waitForTimeout(60);
  const s3 = await status();
  check('3. a search that stopped before it found anything says it took too long', s3 === 'The search took too long. Tap Find APKs to try again.', JSON.stringify(s3));
  check('   ... and does not say there are no package files on the device', !/No APK, APKS, APKM or XAPK files found/.test(s3), JSON.stringify(s3));
  check('   ... and lists nothing', await page.locator('#apkScanList .apk-scan-row').count() === 0);
  await page.evaluate(() => renderApkScan()); await page.waitForTimeout(30);
  check('   ... also when the list is drawn again', (await status()) === s3, JSON.stringify(await status()));

  // a search that ran to the end and found nothing still says so
  await page.evaluate(() => onApkScan(JSON.stringify({ status: 'ok', truncated: false, files: [] })));
  await page.waitForTimeout(60);
  const s4 = await status();
  check('4. a complete search with no files says none were found', s4 === 'No APK, APKS, APKM or XAPK files found on this device.', JSON.stringify(s4));

  // a complete search with files: no note
  await page.evaluate(f => onApkScan(JSON.stringify({ status: 'ok', truncated: false, files: [f] })), file);
  await page.waitForTimeout(60);
  const s5 = await status();
  check('5. a complete list carries no note', /1 of 1 file found/.test(s5) && !/stopped early/.test(s5), JSON.stringify(s5));

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
