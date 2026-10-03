// App inspector Components: exported filter, search, activity launch (dialog only if refused), read-only guard
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const st = { calls: [], privileged: true }; window.__st = st;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.android.settings', name: 'Settings', isSystem: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { port: 5555, connected: st.privileged }, adbWireless: {}, shizuku: {}, configuredMode: st.privileged ? 'shizuku' : 'unprivileged', activeMode: st.privileged ? 'shizuku' : 'unprivileged', modeAvailable: st.privileged, isPrivileged: st.privileged }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', services: ['com.android.settings.SettingsService$Inner'],
        activities: ['com.android.settings.Settings', 'com.android.settings.TestingSettings', 'com.android.settings.Hidden$Debug'],
        activityInfo: [{ name: 'com.android.settings.Settings', exported: true, enabled: true, permission: '' },
                       { name: 'com.android.settings.TestingSettings', exported: false, enabled: true, permission: '' },
                       { name: 'com.android.settings.Hidden$Debug', exported: false, enabled: false, permission: 'android.permission.DUMP' }] }); },
      launchActivity(pkg, cls, exported) { st.calls.push(`launch:${cls}:${exported}`);
        if (exported) return JSON.stringify({ ok: true, method: 'intent', output: 'Started' });
        if (cls.includes('Hidden')) return JSON.stringify({ ok: false, method: 'shell', output: 'Starting: Intent { cmp=com.android.settings/.Hidden$Debug }\nError: Activity class does not exist / Permission Denial: not exported from uid 1000' });
        return JSON.stringify({ ok: true, method: 'shell', output: 'Starting: Intent { cmp=com.android.settings/.TestingSettings }\nStatus: ok\nLaunchState: COLD' }); },
      executeShell(c) { st.calls.push('shell:' + c); return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => openInspector('com.android.settings'));
  await page.click('.sheet-tab-pill[data-tab="comps"]');
  console.log('header:', await page.locator('#compsContainer > div').first().innerText());
  await page.click('#compsFilterRow [data-filter="unexported"]');
  console.log('unexported rows:', await page.locator('#compsContainer .perm-name').allInnerTexts());
  await page.screenshot({ path: 'comps.png' });
  // A launch that passes opens no result dialog (the activity opening is the answer, a toast says so); one Android refuses shows its answer.
  const dialogShown = () => page.isVisible('#commandResultsModal.show');
  const toast = () => page.locator('#toastMsg').innerText();
  await page.locator('#compsContainer button[data-comp="com.android.settings.TestingSettings"][onclick^="launchComponent"]').click(); await page.waitForTimeout(300);
  console.log('result 1 (unexported, passes): dialog shown:', await dialogShown(), '| toast:', await toast());
  await page.locator('#compsContainer button[data-comp="com.android.settings.Hidden$Debug"][onclick^="launchComponent"]').click(); await page.waitForTimeout(300);
  console.log('result 2 (unexported, refused): dialog shown:', await dialogShown(), '| toast:', await toast());
  console.log('   title:', await page.locator('#commandResultsTitle').innerText(), '| subtitle:', await page.locator('#commandResultsSubtitle').innerText());
  console.log('   answer:', (await page.locator('#commandResultsList').innerText()).replace(/\n/g, ' | '));
  await page.screenshot({ path: 'launch_fail.png' });
  await page.evaluate(() => closeCommandResultsModal());
  await page.click('#compsFilterRow [data-filter="exported"]');
  await page.locator('#compsContainer button[data-comp="com.android.settings.Settings"][onclick^="launchComponent"]').click(); await page.waitForTimeout(300);
  console.log('result 3 (exported, intent, passes): dialog shown:', await dialogShown(), '| toast:', await toast());
  // after a refused launch, one that passes leaves no dialog behind
  await page.click('#compsFilterRow [data-filter="unexported"]');
  await page.locator('#compsContainer button[data-comp="com.android.settings.Hidden$Debug"][onclick^="launchComponent"]').click(); await page.waitForTimeout(300);
  await page.evaluate(() => closeCommandResultsModal());
  await page.locator('#compsContainer button[data-comp="com.android.settings.TestingSettings"][onclick^="launchComponent"]').click(); await page.waitForTimeout(300);
  console.log('a passing launch after a refused one: dialog shown:', await dialogShown());
  // Read-only mode: unexported launch must be blocked by the privilege guard
  await page.evaluate(() => { window.__st.privileged = false; checkAllWorkingModes(false); });
  await page.click('#compsFilterRow [data-filter="unexported"]');
  await page.locator('#compsContainer button[data-comp="com.android.settings.TestingSettings"][onclick^="launchComponent"]').click(); await page.waitForTimeout(200);
  console.log('read-only guard shown:', await page.isVisible('#privilegeModal.show'));
  await page.evaluate(() => closePrivilegeModal());
  await page.fill('#compsSearch', 'debug'); await page.click('#compsFilterRow [data-filter="all"]');
  console.log('search debug:', await page.locator('#compsContainer .perm-name').allInnerTexts());
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
