// Wireless Debugging: Pair via Notification button calls the bridge, shows a toast, falls back when missing
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__pair = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true },
        adbWireless: { host: '192.168.1.20', port: 0, connected: false }, shizuku: { installed: false, running: false, authorized: false },
        rootAvailable: false, configuredMode: 'adb_tcp', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getDeviceIp() { return '192.168.1.20'; },
      discoverWirelessDebugging() { return JSON.stringify({ connect: ['192.168.1.20:41235'], pairing: ['192.168.1.20:37155'] }); },
      showWirelessPairingNotification() { window.__pair.push('notify'); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  // ---- The Wireless Debugging card has a "Pair via Notification" button ----
  await page.evaluate(() => openWorkingModesModal()); await page.waitForTimeout(150);
  const btnVisible = await page.isVisible('button:has-text("Pair via Notification")');
  console.log('1. Pair-via-Notification button present:', btnVisible);

  // ---- Clicking it invokes the native bridge ----
  await page.click('button:has-text("Pair via Notification")'); await page.waitForTimeout(120);
  const calls = await page.evaluate(() => window.__pair);
  console.log('2. bridge showWirelessPairingNotification invoked:', JSON.stringify(calls), '| once:', calls.length === 1);

  // ---- A confirmation toast is shown ----
  const toastSeen = await page.evaluate(() => {
    const t = document.getElementById('toast');
    return t ? t.innerText : '(no toast element)';
  });
  console.log('3. toast text:', JSON.stringify(toastSeen), '| mentions shade/code:', /shade|code/i.test(toastSeen));

  // ---- Graceful fallback when the bridge method is missing (older webview / browser) ----
  await page.evaluate(() => { delete window.AndroidBridge.showWirelessPairingNotification; });
  await page.click('button:has-text("Pair via Notification")'); await page.waitForTimeout(120);
  const toast2 = await page.evaluate(() => document.getElementById('toast')?.innerText || '');
  const stillOnce = (await page.evaluate(() => window.__pair)).length === 1;
  console.log('4. fallback toast when bridge absent:', JSON.stringify(toast2), '| no extra bridge call:', stillOnce);

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
