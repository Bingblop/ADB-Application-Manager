// Installer: split-APK cards, install flags per authorizer, split selection, install call, no-privilege mode
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__installCalls = [];
    const PKG = {
      ref: 'content://pick/1', type: 'apkm', pkg: 'com.example.app', label: 'Example App',
      versionName: '2.0', versionCode: 200, minSdk: 24, targetSdk: 34, totalSize: 10485760,
      splits: [
        { path: '/data/user/0/com.bloatware.bingblop/cache/installer/base.apk', name: 'base.apk', size: 8000000, isBase: true, split: '' },
        { path: '/data/user/0/com.bloatware.bingblop/cache/installer/0__config.arm64.apk', name: '0__config.arm64.apk', size: 1500000, isBase: false, split: 'config.arm64_v8a' },
        { path: '/data/user/0/com.bloatware.bingblop/cache/installer/1__config.en.apk', name: '1__config.en.apk', size: 500000, isBase: false, split: 'config.en' },
      ],
      signed: true, installed: true, installedVersionName: '1.0', installedVersionCode: 100, signerMatchesInstalled: true,
    };
    window.__PKG = PKG;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      getDeviceProfile() { return JSON.stringify({ abis: ['arm64-v8a', 'armeabi-v7a'], dpi: 420, locales: ['en-US'], sdk: 34 }); },
      pickInstallerFile() { window.onInstallFilePicked(PKG.ref); },
      inspectInstallSource(ref) { window.onInstallInspected(JSON.stringify(PKG)); },
      installSelected(json) { window.__installCalls.push(JSON.parse(json)); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);

  await page.evaluate(() => switchView('installer'));
  await page.evaluate(() => pickInstallFile());
  await page.waitForTimeout(150);

  console.log('info card shown:', await page.isVisible('#installInfoCard'));
  console.log('splits card shown (apkm, >1 split):', await page.isVisible('#installSplitsCard'));
  console.log('options card shown:', await page.isVisible('#installOptionsCard'));
  console.log('pkg label:', await page.locator('#installPkgLabel').innerText());
  console.log('split rows:', await page.locator('#installSplitsList .switch-row').count());
  console.log('sign row:', await page.locator('#installSignRow').innerText());

  // Default: the base plus the splits that fit this phone (arm64 CPU, English), here all three
  let built = await page.evaluate(() => buildInstallOptions());
  console.log('1. default authorizer=adb, createFlags:', built.createFlags);
  console.log('   splits selected:', built.opts.splits.length, '(expect 3)');
  console.log('   blockMismatch/blockUnknown default on:', built.opts.blockMismatch, built.opts.blockUnknown);

  // Turn on a batch of privileged flags + enums + installer pkg
  built = await page.evaluate(() => {
    document.getElementById('optAuthorizer').value = 'shizuku'; installAuthorizerChanged();
    document.getElementById('optGrantAll').checked = true;
    document.getElementById('optDowngrade').checked = true;
    document.getElementById('optAllowTest').checked = true;
    document.getElementById('optAllUsers').checked = true;
    document.getElementById('optBypassSdk').checked = true;
    document.getElementById('optUpdateOwner').checked = true;
    document.getElementById('optInstallReason').value = '4';
    document.getElementById('optPackageSource').value = '2';
    document.getElementById('optInstaller').value = 'com.android.vending';
    document.getElementById('optOriginating').value = 'https://example.com/app';
    document.getElementById('optDexopt').checked = true;
    document.getElementById('optDexForce').checked = true;
    document.getElementById('optDexMode').value = 'everything';
    updateInstallPreview();
    return buildInstallOptions();
  });
  console.log('2. full flags:', built.createFlags);
  const expect = '-r -g -d -t --user all --bypass-low-target-sdk-block --update-ownership -i com.android.vending --originating-uri https://example.com/app --install-reason 4 --package-source 2';
  console.log('   matches expected:', built.createFlags === expect);
  console.log('   dexopt:', built.opts.dexopt, built.opts.dexoptMode, 'force:', built.opts.dexForce);
  console.log('   pkg/sourceRef carried:', built.opts.pkg, '|', built.opts.sourceRef);
  console.log('   installReason/packageSource numerics:', built.opts.installReason, built.opts.packageSource);

  // Deselect one config split -> 2 files
  built = await page.evaluate(() => {
    document.querySelector('#installSplitsList .install-split-cb[data-idx="2"]').checked = false;
    updateInstallPreview();
    return buildInstallOptions();
  });
  console.log('3. after deselecting config.en:', built.opts.splits.length, '(expect 2)');
  console.log('   base still included:', built.opts.splits.some(s => s.isBase));

  // Fire the install button -> bridge receives opts
  await page.evaluate(() => runInstall());
  await page.waitForTimeout(100);
  const call = await page.evaluate(() => window.__installCalls[0]);
  console.log('4. installSelected received authorizer:', call.authorizer, '| splits:', call.splits.length, '| createFlags:', call.createFlags);

  // Simulate a success result
  await page.evaluate(() => window.onInstallResult(JSON.stringify({ ok: true, method: 'shizuku', output: 'Success', dexopt: 'Success', sourceDeleted: false })));
  await page.waitForTimeout(100);
  console.log('5. results modal shown on success:', await page.isVisible('#commandResultsModal.show'));
  await page.evaluate(() => closeCommandResultsModal());

  // No-privilege disables the power toggles
  await page.evaluate(() => { document.getElementById('optAuthorizer').value = 'none'; installAuthorizerChanged(); });
  const none = await page.evaluate(() => {
    const b2 = buildInstallOptions();
    return { grantDisabled: document.getElementById('optGrantAll').disabled, instDisabled: document.getElementById('optInstaller').disabled, flags: b2.createFlags, preview: document.getElementById('installPreview').innerText };
  });
  console.log('6. no-priv: grantAll disabled:', none.grantDisabled, '| installer field disabled:', none.instDisabled);
  console.log('   no-priv createFlags (just -r):', JSON.stringify(none.flags));
  console.log('   no-priv preview mentions dialog:', none.preview.indexOf('dialog') >= 0);

  // Plain single APK: no splits card
  await page.evaluate(() => {
    const apk = { ref: 'content://pick/2', type: 'apk', pkg: 'com.single.app', label: 'Single', versionName: '1.0', versionCode: 1, minSdk: 26, targetSdk: 33, totalSize: 4000000,
      splits: [{ path: '/data/user/0/com.bloatware.bingblop/cache/installer/base.apk', name: 'base.apk', size: 4000000, isBase: true, split: '' }], signed: true, installed: false };
    window.onInstallInspected(JSON.stringify(apk));
  });
  await page.waitForTimeout(100);
  console.log('7. single APK -> splits card hidden:', !(await page.isVisible('#installSplitsCard')), '| info shown:', await page.isVisible('#installInfoCard'));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
