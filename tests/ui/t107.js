// The lock on every button that cannot change anything in the current state: without a working mode, the permission toggles, the app op modes and the
// Enable / Disable / Stop / Launch (unexported) buttons of activities, services, receivers and providers carry it; with a working mode only an install-time
// permission (and an unexported activity's Launch nothing) is locked.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const comp = (n, ex) => ({ name: 'com.x.' + n, exported: ex, enabled: true, permission: '' });
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'X', isSystem: false }]); }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      getAppDetails() { return JSON.stringify({ versionName: '1', permissions: [
        { name: 'android.permission.CAMERA', granted: true, protection: 'runtime', changeable: true, label: '' }, { name: 'android.permission.INTERNET', granted: true, protection: 'normal', changeable: false, label: '' }],
        activityInfo: [comp('Main', true), comp('Hidden', false)], serviceInfo: [comp('Svc', false)], receiverInfo: [comp('Rcv', true)], providerInfo: [comp('Prov', false)],
        appopsRaw: 'CAMERA: allow; time=+1h ago\nWAKE_LOCK: ignore; time=+1h ago' }); },
      getAppOpsRaw() { return 'CAMERA: allow; time=+1h ago\nWAKE_LOCK: ignore; time=+1h ago'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const snap = async (priv) => {
    await page.evaluate(p => { isPrivilegedActive = p; closeInspector(); openInspector('com.x'); }, priv);
    await page.waitForTimeout(400);
    const out = {};
    for (const tab of ['perms', 'ops', 'acts', 'comps']) {
      await page.evaluate(t => switchSheetTab(t), tab); await page.waitForTimeout(250);
      const sel = tab === 'perms' ? '#permsContainer .perm-toggle-btn' : tab === 'ops' ? '#opsContainer .op-mode-btn.on, #opsContainer .op-mode-btn.locked' : tab === 'acts' ? '#actsContainer .perm-toggle-btn' : '#compsContainer .perm-toggle-btn';
      out[tab] = await page.evaluate(sel => Array.from(document.querySelectorAll(sel)).map(b => [b.classList.contains('locked'), b.innerText.trim()]), sel);
    }
    return out;
  };
  console.log('without a working mode:', JSON.stringify(await snap(false)));
  console.log('with a working mode:', JSON.stringify(await snap(true)));
  console.log('the lock is written once in the page (LOCK_ICON):', await page.evaluate(() => LOCK_ICON === '\u{1F512}' && lk(true) === '\u{1F512} ' && lk(false) === ''));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
