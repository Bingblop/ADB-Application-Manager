// Unexported-activity launch: passes exported=false to the bridge and renders the assistant-method result.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__launch = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku',
        adbTcp: { connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      getAppDetails(pkg) {
        return JSON.stringify({ versionName: '1', versionCode: 1,
          activities: ['com.android.phone.SecretActivity'],
          activityInfo: [{ name: 'com.android.phone.SecretActivity', exported: false, enabled: true, permission: '' }],
          services: [], receivers: [], providers: [], serviceInfo: [], receiverInfo: [], providerInfo: [], permissions: [], appopsRaw: '' });
      },
      launchActivity(pkg, comp, exported) {
        window.__launch.push(pkg + '|' + comp + '|' + exported);
        if (window.__launchFails) return JSON.stringify({ ok: false, method: 'assistant', output: '$ input keyevent KEYCODE_ASSIST\nError: injection failed (assistant method)' });
        return JSON.stringify({ ok: true, method: 'assistant',
          output: 'Launched "' + pkg + '/' + comp + '" via the assistant method: set it as the device assistant, pressed the ASSIST key so the system started it, then restored your assistant.' });
      },
      getApkSize() { return '0'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  await page.evaluate(() => openInspector('com.android.phone')); await page.waitForTimeout(150);

  // 1) The unexported activity shows a LAUNCH button in the Activities tab.
  await page.evaluate(() => switchSheetTab('acts')); await page.waitForTimeout(80);
  const launchBtns = await page.locator('#actsContainer button[data-comp]').count();
  console.log('1. launch button rendered for the component:', launchBtns >= 1);

  // 2) Launching an unexported activity passes exported=false to the native bridge.
  await page.evaluate(() => launchComponent('com.android.phone.SecretActivity', false));
  await page.waitForTimeout(150);
  const calls = await page.evaluate(() => window.__launch);
  console.log('2. launchActivity called with exported=false:', calls.includes('com.android.phone|com.android.phone.SecretActivity|false'));

  // 3) The launch passed (the system started it), so no result dialog opens: a toast says so.
  const shown = await page.evaluate(() => document.getElementById('commandResultsModal').classList.contains('show'));
  console.log('3. no result dialog for a launch that passed:', shown === false, '| toast:', await page.locator('#toastMsg').innerText());

  // 4) When the assistant method fails, the dialog opens with the method and what went wrong.
  await page.evaluate(() => { window.__launchFails = true; launchComponent('com.android.phone.SecretActivity', false); });
  await page.waitForTimeout(150);
  const shown2 = await page.evaluate(() => document.getElementById('commandResultsModal').classList.contains('show'));
  const sub = await page.locator('#commandResultsSubtitle').innerText();
  const body = await page.locator('#commandResultsList').innerText();
  console.log('4. result dialog for a launch that failed:', shown2, '| toast:', await page.locator('#toastMsg').innerText());
  console.log('   subtitle shows Unexported + assistant:', /unexported/i.test(sub) && /assistant/i.test(sub), '|', JSON.stringify(sub));
  console.log('   body has the answer, marked failed:', /injection failed/i.test(body) && /failed/i.test(body), '|', JSON.stringify(body.replace(/\n/g, ' | ')));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
