// Working Modes: IP:port typed in the wireless, pairing and ADB TCP fields is split into host and port
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('console', m => console.log('console:', m.text()));
  page.on('pageerror', e => console.log('pageerror', e.message));
  await page.addInitScript(() => { window.__calls = []; window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true }, adbWireless: {port:0}, shizuku: {}, configuredMode: 'adb_tcp', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      connectAdbWireless(h, p) { window.__calls.push('wireless:' + h + ':' + p); return 'ok'; },
      connectAdbTcp(h, p) { window.__calls.push('tcp:' + h + ':' + p); return 'ok'; },
      pairAdbWireless(h, p, c) { window.__calls.push('pair:' + h + ':' + p + ':' + c); return 'ok'; } }; });
  await page.goto(PAGE);
  await page.evaluate(() => openWorkingModesModal());
  await page.locator('#adbWirelessPortInput').pressSequentially('192.168.1.20:41235');
  await page.evaluate(() => connectAdbWirelessUI());
  await page.locator('#adbPairPortInput').pressSequentially('192.168.1.20:37155');
  await page.fill('#adbPairCodeInput', '123456');
  await page.evaluate(() => pairAdbWirelessUI());
  await page.fill('#adbTcpHostInput', '10.0.0.5:5556');
  await page.evaluate(() => connectAdbTcpUI());
  await page.waitForTimeout(500);
  console.log(JSON.stringify(await page.evaluate(() => window.__calls)), await page.locator('#toastMsg').innerText());
  await b.close(); })();
