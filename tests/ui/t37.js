// Installer VirusTotal card: key persistence, scan, clean/malicious verdicts, upload offer.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__vt = [];
    window.__settings = {};
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      saveSetting(k, v) { window.__settings[k] = v; }, loadSetting(k) { return window.__settings[k] || ''; },
      virusTotalScan(key, path) { window.__vt.push('scan:' + key + ':' + path); },
      virusTotalUpload(key, path) { window.__vt.push('upload:' + key + ':' + path); },
      openUrlExternal() { window.__vt.push('openUrl'); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('installer')); await page.waitForTimeout(60);

  // Load a fake package so the installer cards (incl. the VT card) appear.
  await page.evaluate(() => window.onInstallInspected(JSON.stringify({
    type: 'apk', pkg: 'com.test.app', label: 'Test App', versionName: '1.0', versionCode: 1, totalSize: 1048576,
    signed: true, splits: [{ path: '/data/work/base.apk', name: 'base.apk', size: 1048576, isBase: true, split: '' }]
  })));
  await page.waitForTimeout(60);
  console.log('1. VirusTotal card visible after a package loads:', await page.isVisible('#installVtCard'));

  // 2) Scanning with no key does not call the bridge.
  console.log('2a. no key -> the card says so and links to Settings, the scan button is off:', await page.evaluate(() => /No VirusTotal API key yet/.test(document.getElementById('vtKeyNote').innerText) && !!document.querySelector('#vtKeyNote button') && document.getElementById('vtScanBtn').disabled));
  await page.evaluate(() => vtScan(false)); await page.waitForTimeout(40);
  console.log('2. no key -> bridge not called:', (await page.evaluate(() => window.__vt)).length === 0);
  await page.evaluate(() => { switchView('installer'); });

  // 3) Entering a key persists it and a scan calls the bridge with the base APK path.
  await page.evaluate(() => vtKeyTyped('MYKEY123')); await page.waitForTimeout(20);
  console.log('3. key persisted via saveSetting:', await page.evaluate(() => window.__settings.vt_api_key) === 'MYKEY123');
  await page.evaluate(() => { document.getElementById('vtScanBtn').click(); }); await page.waitForTimeout(40);
  const calls = await page.evaluate(() => window.__vt);
  console.log('   scan bridge called with key+path:', calls.includes('scan:MYKEY123:/data/work/base.apk'));

  // 4) A clean result renders a clean verdict.
  await page.evaluate(() => window.onVtResult(JSON.stringify({ stage: 'done', found: true, malicious: 0, suspicious: 0, harmless: 60, undetected: 10, total: 70, permalink: 'https://www.virustotal.com/gui/file/abc', name: 'Test App' })));
  await page.waitForTimeout(30);
  let box = await page.locator('#vtResultBox').innerText();
  console.log('4. clean verdict:', /Clean/i.test(box), '| shows report link:', await page.locator('#vtResultBox a').count() > 0);

  // 5) A malicious result renders a malicious verdict with the count.
  await page.evaluate(() => window.onVtResult(JSON.stringify({ stage: 'done', found: true, malicious: 3, suspicious: 1, harmless: 50, undetected: 16, total: 70, permalink: 'https://www.virustotal.com/gui/file/abc' })));
  await page.waitForTimeout(30);
  box = await page.locator('#vtResultBox').innerText();
  console.log('5. malicious verdict:', /malicious/i.test(box) && box.includes('3'));

  // 6) An unknown file offers an upload action that calls the upload bridge.
  await page.evaluate(() => window.onVtResult(JSON.stringify({ stage: 'done', found: false, sha256: 'abc' })));
  await page.waitForTimeout(30);
  const hasUpload = await page.locator('#vtResultBox button').count() > 0;
  console.log('6. unknown file offers upload:', hasUpload);
  if (hasUpload) { await page.click('#vtResultBox button'); await page.waitForTimeout(40); }
  console.log('   upload bridge called:', (await page.evaluate(() => window.__vt)).some(c => c.startsWith('upload:')));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
