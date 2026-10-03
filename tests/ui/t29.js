// Inspector Components tab: four sections, enable/disable, exported filter; single and batch dex optimization
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'X App', isSystem: true }, { pkg: 'com.y', name: 'Y App', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() {
        return JSON.stringify({
          permissions: [], appopsRaw: '',
          activityInfo: [{ name: 'com.x.Main', exported: true, enabled: true, permission: '' }, { name: 'com.x.Hidden', exported: false, enabled: true, permission: '' }],
          receiverInfo: [{ name: 'com.x.BootRcv', exported: false, enabled: true, permission: 'android.permission.RECEIVE_BOOT_COMPLETED' }],
          serviceInfo: [{ name: 'com.x.Svc', exported: false, enabled: false, permission: '' }],
          providerInfo: [{ name: 'com.x.Provider', exported: true, enabled: true, permission: '', authority: 'com.x.files' }],
        });
      },
      executeAppAction(a, p) { window.__calls.push('action:' + a + ':' + p); return 'Success'; },
      setComponentEnabled(pkg, comp, enable) { window.__calls.push('toggle:' + comp + ':' + enable); return JSON.stringify({ ok: true, output: 'Component ' + comp + ' new state: ' + (enable ? 'enabled' : 'disabled') }); },
      optimizeApp(pkg, mode, force) { window.__calls.push('opt:' + pkg + ':' + mode + ':' + force); return JSON.stringify({ ok: true, output: 'Success' }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);

  await page.evaluate(() => openInspector('com.x'));
  await page.click('.sheet-tab-pill[data-tab="comps"]');
  await page.waitForTimeout(150);
  const txt = await page.locator('#compsContainer').innerText();
  console.log('headers present:', ['ACTIVITIES', 'RECEIVERS', 'SERVICES', 'PROVIDERS'].map(h => h + '=' + txt.includes(h)).join(' '));
  console.log('provider authority row rendered:', txt.includes('Provider'));
  console.log('service shows disabled badge:', txt.toLowerCase().includes('disabled'));

  // Disable the (currently enabled) boot receiver
  await page.evaluate(() => toggleComponent('com.x.BootRcv', false));
  await page.waitForTimeout(100);
  console.log('setComponentEnabled called:', await page.evaluate(() => window.__calls.filter(c => c.startsWith('toggle:'))));
  // After disabling, the receiver's button should now offer ENABLE
  const btnTxt = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('#compsContainer .perm-toggle-btn')].filter(b => b.getAttribute('data-comp') === 'com.x.BootRcv');
    return btns.map(b => b.innerText);
  });
  console.log('BootRcv buttons after disable (expect ENABLE present):', JSON.stringify(btnTxt));

  // enable an exported filter still works across kinds
  await page.click('#compsFilterRow [data-filter="exported"]'); await page.waitForTimeout(100);
  const expTxt = await page.locator('#compsContainer').innerText();
  console.log('exported filter: shows Main(act) & Provider, hides Hidden & Svc:', expTxt.includes('Main') && expTxt.includes('Provider') && !expTxt.includes('Hidden') && !expTxt.includes('Svc'));

  // ---- Single-app dexopt ----
  await page.evaluate(() => { window.__calls.length = 0; openOptimizeModal('single'); });
  console.log('optimize modal shown (single):', await page.isVisible('#optimizeModal.show'));
  await page.evaluate(() => { document.getElementById('optimizeMode').value = 'everything'; document.getElementById('optimizeForce').checked = true; confirmOptimize(); });
  await page.waitForTimeout(100);
  console.log('single optimize call:', await page.evaluate(() => window.__calls));

  // ---- Batch dexopt ----
  await page.evaluate(() => closeCommandResultsModal());
  await page.evaluate(() => { window.__calls.length = 0; toggleSelectPkg('com.x'); toggleSelectPkg('com.y'); openOptimizeModal('batch'); });
  console.log('optimize modal shown (batch):', await page.isVisible('#optimizeModal.show'));
  await page.evaluate(() => { document.getElementById('optimizeMode').value = 'speed'; document.getElementById('optimizeForce').checked = false; confirmOptimize(); });
  await page.waitForTimeout(150);
  console.log('batch optimize calls (expect 2, speed, false):', await page.evaluate(() => window.__calls));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
