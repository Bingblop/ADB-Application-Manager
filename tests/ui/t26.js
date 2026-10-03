// Component launch: name-only activities default to unexported, privilege guard blocks them, real flags kept
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
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'standard', modeAvailable: false, isPrivileged: false }); },
      getAppDetails() { return '{}'; },
      launchActivity(pkg, cls, exported) {
        window.__calls.push({ pkg, cls, exported });
        return JSON.stringify({ ok: false, method: 'none', output: 'should not be called in this test' });
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);

  // ---- legacy bridge shape (no activityInfo, just names) must default to NOT exported, not exported ----
  await page.evaluate(() => {
    inspectedPkg = 'com.legacy.app';
    inspectorData = { activities: ['com.legacy.app.MainActivity', 'com.legacy.app.HiddenActivity'] };
    renderComponentsList();
  });
  const dataExported = await page.evaluate(() =>
    Array.from(document.querySelectorAll('#compsContainer [data-exported]')).map(el => el.getAttribute('data-exported')));
  console.log('legacy-shape activities data-exported attrs:', JSON.stringify(dataExported));
  console.log('all default to false (unexported, safe):', dataExported.length === 2 && dataExported.every(v => v === 'false'));

  // tapping LAUNCH on a legacy-shape (now unexported-by-default) activity with no privileged mode must be
  // blocked by guardPrivilege, never reaching the bridge at all
  await page.evaluate(() => { window.__calls.length = 0; isPrivilegedActive = false; });
  await page.evaluate(() => document.querySelector('#compsContainer [data-exported="false"]').click());
  console.log('blocked by guardPrivilege (bridge not called):', await page.evaluate(() => window.__calls.length === 0));
  const modalShown = await page.evaluate(() => document.getElementById('privilegeModal') ? document.getElementById('privilegeModal').classList.contains('show') : null);
  console.log('privilege-required modal shown instead:', modalShown);

  // ---- real per-activity detail (activityInfo present) must still be used as-is, not overridden ----
  await page.evaluate(() => {
    isPrivilegedActive = true;
    inspectorData = { activityInfo: [
      { name: 'com.real.app.PublicActivity', exported: true, enabled: true, permission: '' },
      { name: 'com.real.app.InternalActivity', exported: false, enabled: true, permission: '' },
    ] };
    inspectedPkg = 'com.real.app';
    renderComponentsList();
  });
  const realExported = await page.evaluate(() =>
    Array.from(document.querySelectorAll('#compsContainer [data-exported]')).map(el => el.getAttribute('data-exported')));
  console.log('real activityInfo data-exported attrs (true, false expected):', JSON.stringify(realExported));
  console.log('real per-activity detail respected, not overridden:', realExported[0] === 'true' && realExported[1] === 'false');

  // ---- launchComponent's own standalone default (called with exported===undefined) must be false too ----
  await page.evaluate(() => { window.__calls.length = 0; isPrivilegedActive = true; inspectedPkg = 'com.real.app'; });
  await page.evaluate(() => launchComponent('com.real.app.SomeActivity', undefined));
  await page.waitForTimeout(150);
  const call = await page.evaluate(() => window.__calls[0]);
  console.log('launchComponent(comp, undefined) call:', JSON.stringify(call));
  console.log('defaults exported to false when omitted:', call && call.exported === false);

  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
