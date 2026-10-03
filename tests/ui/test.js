// Working Modes (switching, Shizuku, IP:port entry, auto-detect) and Material 3 / Material You color presets
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 860 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    // Mock of the native bridge: ADB TCP 5555 connected, Shizuku running but unauthorized
    const st = { configured: 'adb_tcp', shizukuAuth: false, prefs: '{}', calls: [] };
    window.__st = st;
    window.AndroidBridge = {
      vibrate() {}, savePreferences(s) { st.prefs = s; }, loadPreferences() { return st.prefs; },
      loadCustomLists() { return '[]'; }, saveCustomLists() {}, setSystemBarColor(c) { st.bar = c; },
      getSystemInfo() { return JSON.stringify({ device: 'SM-S938B', manufacturer: 'samsung', release: '16' }); },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: false }]); },
      getMaterialYouColors() { return JSON.stringify({ supported: true, accent: '#A8C7FA', bg: '#0B0D11', surface: '#111318', card: '#1A1C21', running: '#D7BDE4', frozen: '#A8C7FA', system: '#BFC6DC', bloat: '#F2B8B5', text: '#E2E2E9', muted: '#C4C6D0' }); },
      getWorkingMode() {
        const shizukuOk = st.shizukuAuth;
        const avail = { adb_tcp: true, adb_wireless: false, shizuku: shizukuOk, root: false }[st.configured];
        return JSON.stringify({ adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true },
          adbWireless: { host: '192.168.1.20', port: 0, connected: false },
          shizuku: { installed: true, running: true, authorized: shizukuOk }, rootAvailable: false,
          configuredMode: st.configured, activeMode: st.configured === 'auto' ? 'adb_tcp' : st.configured,
          modeAvailable: st.configured === 'auto' ? true : !!avail, isPrivileged: st.configured === 'auto' ? true : !!avail && st.configured !== 'unprivileged' });
      },
      selectWorkingMode(m) { st.calls.push('select:' + m); st.configured = m;
        const ok = m !== 'shizuku' || st.shizukuAuth; return JSON.stringify({ ok, mode: m, message: ok ? 'Using ' + m : 'Approve the Shizuku prompt' }); },
      requestShizukuPermission() { st.calls.push('shizukuReq'); setTimeout(() => { st.shizukuAuth = true; st.configured = 'shizuku'; window.onShizukuPermissionResult(true); }, 50); return 'requested'; },
      connectAdbWireless(h, p) { st.calls.push('wireless:' + h + ':' + p); return 'connected to ' + h + ':' + p; },
      connectAdbTcp(h, p) { st.calls.push('tcp:' + h + ':' + p); return 'connected'; },
      pairAdbWireless(h, p, c) { st.calls.push('pair:' + h + ':' + p + ':' + c); return 'Successfully paired'; },
      getDeviceIp() { return '192.168.1.20'; },
      discoverWirelessDebugging() { return JSON.stringify({ connect: ['192.168.1.20:41235'], pairing: ['192.168.1.20:37155'] }); },
    };
  });
  await page.goto(PAGE);
  await page.waitForTimeout(300);
  const badge = async () => page.locator('#execModeText').innerText();
  console.log('start badge:', await badge());

  await page.evaluate(() => openWorkingModesModal());
  // 1) Switch away from ADB TCP to Read-Only, then to Automatic
  await page.click('button.mode-use-btn[data-mode="unprivileged"]'); await page.waitForTimeout(200);
  console.log('after read-only:', await badge());
  await page.evaluate(() => openWorkingModesModal());
  await page.click('button.mode-use-btn[data-mode="auto"]'); await page.waitForTimeout(200);
  console.log('after auto:', await badge());
  // 2) Shizuku authorize while TCP 5555 connected
  await page.click('text=Authorize & Use Shizuku'); await page.waitForTimeout(200);
  console.log('after shizuku:', await badge(), '| tag:', await page.locator('#statusTagShizuku').innerText());
  // 3) Type full IP:port in wireless connect field (previously blocked by type=number)
  await page.fill('#adbWirelessHostInput', '');
  await page.locator('#adbWirelessPortInput').pressSequentially('192.168.1.20:41235');
  console.log('typed port field:', await page.inputValue('#adbWirelessPortInput'));
  await page.click('text=Connect Wireless ADB');
  await page.locator('#adbPairPortInput').pressSequentially('192.168.1.20:37155');
  await page.fill('#adbPairCodeInput', '123456');
  await page.click('text=Pair Device');
  await page.fill('#adbTcpHostInput', '10.0.0.5:5556');
  await page.click('#modeCardAdbTcp >> text=Connect');
  await page.waitForTimeout(300);
  await page.click('text=Auto-Detect Ports'); await page.waitForTimeout(200);
  console.log('autodetect:', await page.inputValue('#adbPairPortInput'), await page.inputValue('#adbWirelessHostInput'), await page.inputValue('#adbWirelessPortInput'));
  await page.screenshot({ path: 'modes.png', fullPage: false });
  await page.evaluate(() => closeWorkingModesModal());
  // 4) Material 3 palettes
  await page.evaluate(() => switchView('colors')); await page.waitForTimeout(200);
  await page.click('.palette-preset-card[data-preset="material3"]'); await page.waitForTimeout(100);
  const m3 = await page.evaluate(() => [getComputedStyle(document.documentElement).getPropertyValue('--accent'), getComputedStyle(document.documentElement).getPropertyValue('--bg-base'), getComputedStyle(document.documentElement).getPropertyValue('--text-main'), window.__st.bar]);
  console.log('m3 vars:', m3.join(' '));
  await page.click('.palette-preset-card[data-preset="materialyou"]'); await page.waitForTimeout(400);
  await page.locator('.palette-preset-card[data-preset="material3"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'colors.png' });
  console.log('saved prefs preset:', JSON.parse(await page.evaluate(() => window.__st.prefs)).preset);
  // 5) Reload restores the saved theme
  const prefs = await page.evaluate(() => window.__st.prefs);
  await page.addInitScript(p => { window.__savedPrefs = p; }, prefs);
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  console.log('errors:', JSON.stringify(errors));
  await browser.close();
})();
