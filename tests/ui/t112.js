// The app menu's tabs, in their new order and with the four new ones at the end: Activities on its own (Components keeps receivers, services and providers), then Features
// (uses-feature, with what this phone has), Configurations (touch screen, keyboard, navigation, screens, Android versions), Signatures (schemes, certificates and their fingerprints)
// and Libraries (uses-library, shared libraries, native .so files by CPU type). They are read once, the first time one of them is opened, and read again for another app.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = []; window.__copied = [];
    const MB = 1024 * 1024;
    const cert = (cn, sha) => ({ subject: 'CN=' + cn + ', O=Example, C=US', issuer: 'CN=' + cn + ', O=Example, C=US', selfSigned: true, serial: '4A1F9C03', notBefore: Date.UTC(2019, 0, 15), notAfter: Date.UTC(2049, 0, 15),
      sigAlg: 'SHA256withRSA', version: 3, keyAlg: 'RSA', keyBits: 2048, md5: '0A:1B:2C:3D:4E:5F:60:71:82:93:A4:B5:C6:D7:E8:F9', sha1: sha.slice(0, 59), sha256: sha });
    const SHA = 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'Example', isSystem: false }, { pkg: 'com.y', name: 'Other', isSystem: false }]); },
      getWorkingMode() { return '{}'; },
      copyToClipboard(t) { window.__copied.push(t); },
      getAppDetails(pkg) {
        const c = n => ({ name: pkg + '.' + n, exported: n === 'Main', enabled: true, permission: '' });
        return JSON.stringify({ versionName: '2.0', permissions: [], appopsRaw: '', activityInfo: [c('Main'), c('Settings')], serviceInfo: [c('Sync')], receiverInfo: [c('Boot')], providerInfo: [c('Files')] });
      },
      getAppExtras(pkg) {
        window.__calls.push(pkg);
        if (pkg === 'com.y') return JSON.stringify({ error: 'package not found' });
        return JSON.stringify({
          features: [
            { name: 'android.hardware.touchscreen', required: false, device: true }, { name: 'android.hardware.camera', required: true, device: false },
            { name: 'android.hardware.bluetooth_le', required: false, device: false }, { name: 'android.software.leanback', required: false, device: false },
            { name: '', required: false, glEs: '3.2' },
          ],
          deviceGlEs: '3.2',
          configs: [{ touchScreen: 'Finger', keyboard: 'Any', navigation: 'Any', inputFeatures: 'None', glEs: '2.0' }],
          screen: { requiresSmallestWidthDp: 320, compatibleWidthLimitDp: 0, largestWidthLimitDp: 0, targetSdk: 34, minSdk: 26, compileSdk: 35, largeHeap: false, hardwareAccelerated: true, resizeable: true, supportsRtl: true,
            smallScreens: true, normalScreens: true, largeScreens: true, xlargeScreens: true, anyDensity: true },
          deviceAbis: ['arm64-v8a', 'armeabi-v7a', 'armeabi'],
          signatures: { signers: [cert('Example Release', SHA)], history: [cert('Example Old', SHA.replace(/AA/g, 'BB'))], rotated: true, multiple: false, schemes: ['v1', 'v2', 'v3'] },
          libraries: {
            declared: [{ name: 'org.apache.http.legacy', kind: 'library', required: false, version: '' }, { name: 'androidx.window.extensions', kind: 'library', required: false, version: '' }, { name: 'com.example.static', kind: 'static', required: true, version: '12' }],
            shared: ['/system/framework/org.apache.http.legacy.jar'],
            native: [{ name: 'libapp.so', abi: 'arm64-v8a', size: 9 * MB, apk: 'base.apk' }, { name: 'libflutter.so', abi: 'arm64-v8a', size: 11 * MB, apk: 'base.apk' }, { name: 'libapp.so', abi: 'armeabi-v7a', size: 7 * MB, apk: 'base.apk' }],
            nativeDir: '/data/app/com.x/lib/arm64', primaryAbi: 'arm64-v8a',
          },
        });
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => { window.__getAppExtras = window.AndroidBridge.getAppExtras; });
  const sleep = ms => page.waitForTimeout(ms);
  const tab = async t => { await page.click('.sheet-tab-pill[data-tab="' + t + '"]'); await sleep(200); };
  const calls = () => page.evaluate(() => window.__calls.slice());

  await page.evaluate(() => openInspector('com.x')); await sleep(400);
  console.log('tabs in order:', JSON.stringify((await page.locator('.sheet-tab-pill').allInnerTexts()).map(t => t.replace(/\s+/g, ' ').trim())));
  console.log('copy chips (no Copy version):', JSON.stringify(await page.locator('.copy-row .copy-chip').allInnerTexts()));
  console.log('nothing read yet for the new tabs:', JSON.stringify(await calls()));
  await page.screenshot({ path: 'menu.png' });

  // Activities and Components
  await tab('acts');
  console.log('activities:', JSON.stringify(await page.locator('#actsContainer .perm-name').allInnerTexts()), '| count:', await page.locator('#actsCount').innerText());
  await tab('comps');
  console.log('components:', JSON.stringify(await page.locator('#compsContainer .perm-name').allInnerTexts()), '| count:', await page.locator('#compsCount').innerText());

  // Features
  await tab('features');
  console.log('read once for the app:', JSON.stringify(await calls()));
  console.log('features tab count:', await page.locator('#featuresCount').innerText());
  console.log('features:', JSON.stringify(await page.locator('#featuresContainer .perm-row').evaluateAll(rows => rows.map(r => r.innerText.replace(/\s+/g, ' ').trim()))));
  console.log('summary:', await page.locator('#featuresContainer .ax-note').first().innerText());
  await page.screenshot({ path: 'features.png' });
  for (const f of ['required', 'optional', 'missing']) {
    await page.click('#featuresFilterRow [data-filter="' + f + '"]'); await sleep(80);
    console.log('features, ' + f + ':', JSON.stringify(await page.locator('#featuresContainer .perm-name').allInnerTexts()));
  }
  await page.click('#featuresFilterRow [data-filter="all"]');
  await page.fill('#featuresSearch', 'blue'); await sleep(80);
  console.log('features, search "blue":', JSON.stringify(await page.locator('#featuresContainer .perm-name').allInnerTexts()));
  await page.fill('#featuresSearch', ''); await sleep(50);

  // Configurations
  await tab('configs');
  console.log('configurations:', JSON.stringify(await page.locator('#configsContainer .ax-kv').evaluateAll(rows => rows.map(r => r.innerText.replace(/\s+/g, ' ').trim()))));
  console.log('configuration headings:', JSON.stringify(await page.locator('#configsContainer .ax-sec').allInnerTexts()));
  await page.screenshot({ path: 'configs.png' });

  // Signatures
  await tab('sigs');
  console.log('schemes:', JSON.stringify(await page.locator('#sigsContainer .ax-scheme').evaluateAll(a => a.map(e => e.innerText.trim() + ':' + (e.classList.contains('on') ? 'on' : 'off')))));
  console.log('signature headings:', JSON.stringify(await page.locator('#sigsContainer .ax-sec').allInnerTexts()));
  console.log('first certificate:', JSON.stringify(await page.locator('#sigsContainer .ax-card').nth(1).locator('.ax-kv').evaluateAll(rows => rows.map(r => r.innerText.replace(/\s+/g, ' ').trim()))));
  console.log('badges:', JSON.stringify(await page.locator('#sigsContainer .ax-card-title .perm-kind').allInnerTexts()));
  await page.screenshot({ path: 'signatures.png' });
  await page.locator('#sigsContainer [data-copy]').nth(2).click(); await sleep(100);
  console.log('a fingerprint copies on tap:', JSON.stringify(await page.evaluate(() => window.__copied)));
  await page.evaluate(() => { window.__copied.length = 0; }); await page.click('#sigsContainer .filter-chip'); await sleep(100);
  console.log('Copy all:', JSON.stringify((await page.evaluate(() => window.__copied))[0].split('\n').slice(0, 6)));

  // Libraries
  await tab('libs');
  console.log('library headings:', JSON.stringify(await page.locator('#libsContainer .ax-sec').allInnerTexts()));
  console.log('libraries:', JSON.stringify(await page.locator('#libsContainer .perm-row').evaluateAll(rows => rows.map(r => r.innerText.replace(/\s+/g, ' ').trim()))));
  console.log('CPU groups:', JSON.stringify(await page.locator('#libsContainer .ax-abi').allInnerTexts()));
  await page.screenshot({ path: 'libraries.png' });
  await page.fill('#libsSearch', 'flutter'); await sleep(80);
  console.log('libraries, search "flutter":', JSON.stringify(await page.locator('#libsContainer .perm-name').allInnerTexts()));
  await page.fill('#libsSearch', 'zzz'); await sleep(80);
  console.log('libraries, nothing found:', JSON.stringify(await page.locator('#libsContainer .list-empty').allInnerTexts()));
  await page.fill('#libsSearch', '');

  console.log('still one read after opening all four:', JSON.stringify(await calls()));

  // The same app again: the menu is read again; another app: its own read, and a failure is said plainly
  await page.evaluate(() => { closeInspector(); openInspector('com.y'); }); await sleep(300);
  await tab('features');
  console.log('another app, failing read:', JSON.stringify(await page.locator('#featuresContainer').innerText()));
  console.log('reads:', JSON.stringify(await calls()));
  await page.evaluate(() => { closeInspector(); openInspector('com.x'); }); await sleep(300);
  console.log('reset when the menu is opened again (features pane):', JSON.stringify(await page.locator('#featuresContainer').innerText()));
  await tab('sigs');
  console.log('reads after reopening:', JSON.stringify(await calls()));

  // An older build without the bridge method
  await page.evaluate(() => { delete window.AndroidBridge.getAppExtras; closeInspector(); openInspector('com.x'); }); await sleep(300);
  await tab('libs');
  console.log('no bridge method:', JSON.stringify(await page.locator('#libsContainer').innerText()));

  // A narrow phone: no pane is wider than the sheet, and the tab that was picked is brought into view in the tab bar
  await page.evaluate(() => { closeInspector(); });
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => { window.AndroidBridge.getAppExtras = window.__getAppExtras; });
  await sleep(100);
  const narrow = {};
  for (const t of ['acts', 'comps', 'features', 'configs', 'sigs', 'libs']) {
    if (t === 'acts') { await page.evaluate(() => openInspector('com.x')); await sleep(300); }
    await tab(t);
    narrow[t] = await page.evaluate(t => {
      const pane = document.querySelector('.sheet-tab-content.active'), bar = document.querySelector('.sheet-tabs'), pill = document.querySelector('.sheet-tab-pill[data-tab="' + t + '"]');
      const pr = pill.getBoundingClientRect(), br = bar.getBoundingClientRect();
      return { fits: pane.scrollWidth <= pane.clientWidth + 1, pillInView: pr.left >= br.left - 1 && pr.right <= br.right + 1 };
    }, t);
    if (t === 'sigs' || t === 'features') await page.screenshot({ path: 'narrow_' + t + '.png' });
  }
  console.log('narrow phone (pane fits, tab in view):', JSON.stringify(narrow));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
