// ADB Console: runs a shell command, then prints the terminal box background colour in light and dark mode
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  for (const dark of [false, true]) {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    await page.addInitScript(d => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return d; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'Camera', isSystem: true }]); }, executeShell(c) { return 'package:com.a\npackage:com.b'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); } }; }, dark);
    await page.goto(PAGE); await page.waitForTimeout(200);
    await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.fill('#termCmd', 'pm list packages'); await page.evaluate(() => runTerminalCmd());
    await page.waitForTimeout(300);
    await page.screenshot({ path: `term_${dark ? 'dark' : 'light'}.png` });
    console.log(dark ? 'dark' : 'light', await page.evaluate(() => getComputedStyle(document.querySelector('.terminal-box')).backgroundColor + ' / avatar ' + 'n/a'));
  }
  await b.close(); })();
