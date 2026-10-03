// Text selection: names, packages, menu version, terminal selectable; buttons, tabs, pills not; row tap selects
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, saveStore() {}, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0.3.1' }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 2600031, firstInstallTime: 1700000000000, permissions: [] }); },
      getAppSizes() { return JSON.stringify({ apk: 180e6, splits: 1 }); },
      executeShell(c) { return 'package:com.android.chrome\npackage:com.sec.android.app.sbrowser'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const us = sel => page.locator(sel).first().evaluate(e => getComputedStyle(e).userSelect);
  // Drag-select across an element's text and read the selection
  const dragSelect = async sel => {
    const box = await page.locator(sel).first().boundingBox();
    await page.mouse.move(box.x + 1, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width - 1, box.y + box.height / 2, { steps: 8 });
    await page.mouse.up();
    const t = await page.evaluate(() => window.getSelection().toString());
    await page.evaluate(() => window.getSelection().removeAllRanges());
    return t;
  };
  console.log('selectable  app name:', await us('.app-name'), '| pkg:', await us('.app-pkg'), '| header:', await us('.brand-text h1'));
  console.log('unselectable tab:', await us('.tab-btn'), '| pill:', await us('.filter-pill'), '| row button:', await us('.btn-mini'), '| checkbox:', await us('.app-checkbox'));
  const box = await page.locator('.app-pkg').first().boundingBox();
  await page.mouse.move(box.x + 1, box.y + box.height / 2); await page.mouse.down();
  await page.mouse.move(box.x + box.width - 1, box.y + box.height / 2, { steps: 8 }); await page.mouse.up();
  console.log('drag on package name →', JSON.stringify(await page.evaluate(() => window.getSelection().toString())), '| row toggled by the drag:', await page.locator('.app-card.selected').count() === 1);
  await page.evaluate(() => window.getSelection().removeAllRanges());
  console.log('drag on a button     →', JSON.stringify(await dragSelect('.btn-mini')));
  // Tap on the row still selects the app (click behaviour unchanged)
  await page.click('.app-left');
  console.log('row tap still selects app:', await page.locator('.app-card.selected').count() === 1);
  await page.click('.app-left');
  // App menu header text
  await page.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await page.waitForTimeout(300);
  console.log('drag on menu version  →', JSON.stringify(await dragSelect('#sheetVersion')));
  await page.evaluate(() => closeInspector()); await page.waitForTimeout(200);
  // Terminal output
  await page.evaluate(() => switchView('terminal')); await page.fill('#termCmd', 'pm list packages');
  await page.evaluate(() => { runTerminalCmd(); closeCommandResultsModal(); }); await page.waitForTimeout(200);
  const term = await page.evaluate(() => { const r = document.createRange(); r.selectNodeContents(document.getElementById('termOutput')); const s = window.getSelection(); s.removeAllRanges(); s.addRange(r); return s.toString(); });
  console.log('terminal selection contains output:', term.includes('package:com.android.chrome'));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
