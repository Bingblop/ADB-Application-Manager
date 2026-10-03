// v5.8 logcat: log of one app (picker + inspector "Logs" button), save and share a bug-report text.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { logcat: [], logcatFor: [], save: [], share: [], clear: 0 };
    const pk = [
      { pkg: 'com.example.alpha', name: 'Alpha', isSystem: false, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '1.0', versionCode: 1, mods: [] },
      { pkg: 'com.example.beta', name: 'Beta <img src=x onerror=window.__xss=1>', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '2.0', versionCode: 2, mods: [] },
      { pkg: 'com.android.sysui', name: 'System UI', isSystem: true, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '14', versionCode: 14, mods: [] },
      { pkg: 'com.example.gamma', name: 'Gamma', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '3.0', versionCode: 3, mods: [] },
    ];
    const line = (lvl, tag, msg, pid) => `10-03 12:00:00.123  ${pid}  ${pid} ${lvl} ${tag}: ${msg}`;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify(pk); },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      loadSetting() { return ''; }, saveSetting() {},
      getLogcat(level, filter, lines) {
        window.__calls.logcat.push([level, filter, lines]);
        return [line('I', 'ActivityManager', 'Start proc for all', 100), line('E', 'AndroidRuntime', 'FATAL EXCEPTION in alpha', 200), line('D', 'Tag', 'debug line', 300)].join('\n');
      },
      getLogcatFor(level, filter, lines, pkg) {
        window.__calls.logcatFor.push([level, filter, lines, pkg]);
        if (pkg === 'com.example.gamma') return '(com.example.gamma is not running, so there are no log lines to show)';
        if (pkg === 'com.example.beta') return 'Error: com.example.beta is not installed.';
        return ['note: ' + pkg + ' shares its user ID with 1 other package(s), so their lines appear too', line('I', 'AlphaTag', 'hello from alpha', 4242), line('W', 'AlphaTag', 'careful', 4242), line('E', 'AlphaTag', 'boom', 4242)].join('\n');
      },
      clearLogcat() { window.__calls.clear++; return 'cleared'; },
      getAppDetails(pkg) { return JSON.stringify({ versionName: '1.0', versionCode: 1, permissions: [], appopsRaw: '', activities: [], services: [], receivers: [], providers: [] }); },
      saveTextToDownloads(name, text) { window.__calls.save.push([name, text]); return 'Download/ADB App Manager/' + name; },
      shareTextFile(name, text, mime) { window.__calls.share.push([name, text, mime]); return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const toast = () => page.locator('#toastMsg').innerText();
  await page.evaluate(() => switchView('logcat')); await sleep(300);

  // 1) Default: all apps, nothing filtered.
  console.log('1. the app button starts at "All apps" with no clear button:', (await page.locator('#logcatAppBtn').innerText()) === 'All apps ▾' && !(await page.locator('#logcatAppClear').isVisible()));
  console.log('   the all-apps fetch used getLogcat:', (await page.evaluate(() => window.__calls.logcat.length)) >= 1 && (await page.evaluate(() => window.__calls.logcatFor.length)) === 0);

  // 2) Picker: running apps first, search, escaping.
  await page.locator('#logcatAppBtn').click(); await sleep(150);
  const names = await page.locator('#lcAppList .perm-name').allInnerTexts();
  console.log('2. the picker opens with "All apps" first, then running apps, then the rest:', /^All apps/.test(names[0]) && /Alpha/.test(names[1]) && /System UI/.test(names[2]) && names.length === 5, JSON.stringify(names));
  console.log('   an injected <img> in an app name is escaped:', await page.evaluate(() => !document.querySelector('#lcAppList img') && window.__xss !== 1));
  await page.fill('#lcAppSearch', 'gam'); await sleep(80);
  const filtered = await page.locator('#lcAppList .perm-name').allInnerTexts();
  console.log('   searching narrows the list (plus the All apps row):', filtered.length === 2 && /Gamma/.test(filtered[1]), JSON.stringify(filtered));
  await page.fill('#lcAppSearch', ''); await sleep(60);

  // 3) Pick an app.
  await page.locator('#lcAppList .perm-row', { hasText: 'Alpha' }).first().click(); await sleep(250);
  console.log('3. picking an app closes the picker, relabels the button and shows the clear button:', !(await page.locator('#lcAppModal.show').count()) && (await page.locator('#logcatAppBtn').innerText()) === 'Alpha ▾' && await page.locator('#logcatAppClear').isVisible());
  const calls3 = await page.evaluate(() => window.__calls.logcatFor.slice());
  console.log('   the log was fetched for that package with the chosen level and lines:', calls3.length >= 1 && calls3[calls3.length - 1][3] === 'com.example.alpha' && calls3[calls3.length - 1][0] === 'I' && calls3[calls3.length - 1][2] === 500, JSON.stringify(calls3));
  console.log('   only that app’s lines are drawn (+ the shared-UID note):', /hello from alpha/.test(await page.locator('#logcatOutput').innerText()) && !/Start proc for all/.test(await page.locator('#logcatOutput').innerText()) && /shares its user ID/.test(await page.locator('#logcatOutput').innerText()));
  console.log('   the card subtitle names the package:', /com\.example\.alpha/.test(await page.locator('#view-logcat .color-card-subtitle').innerText()));

  // 4) Not-running / not-installed messages are notices, not log rows.
  await page.evaluate(() => logcatSetApp({ pkg: 'com.example.gamma', name: 'Gamma' })); await sleep(200);
  console.log('4. an app that is not running shows a single notice:', /is not running/.test(await page.locator('#logcatOutput').innerText()) && (await page.locator('#logcatOutput .lc-row').count()) === 0 && !/No log lines/.test(await page.locator('#logcatOutput').innerText()));
  await page.evaluate(() => logcatSetApp({ pkg: 'com.example.beta', name: 'Beta' })); await sleep(200);
  console.log('   an uninstalled app shows the error:', /is not installed/.test(await page.locator('#logcatOutput').innerText()));

  // 5) Save the (filtered) log.
  await page.evaluate(() => logcatSetApp({ pkg: 'com.example.alpha', name: 'Alpha' })); await sleep(200);
  await page.evaluate(() => { logcatToggleLevel('W'); });           // hide Warn
  await page.locator('#view-logcat button', { hasText: 'Save' }).click(); await sleep(100);
  const saved = await page.evaluate(() => window.__calls.save.slice());
  console.log('5. Save writes one text file named after the app:', saved.length === 1 && /^logcat_com\.example\.alpha_\d{14}\.txt$/.test(saved[0][0]), JSON.stringify(saved.map(x => x[0])));
  const txt = saved[0] ? saved[0][1] : '';
  console.log('   the file has a header (app, level, lines) and the raw lines minus the hidden level:', /^# ADB Application Manager - log export/.test(txt) && /# App: Alpha \(com\.example\.alpha\)/.test(txt) && /Level: Info and above/.test(txt) && /Hidden levels: Warn/.test(txt) && /hello from alpha/.test(txt) && /boom/.test(txt) && !/careful/.test(txt), JSON.stringify(txt.split('\n').slice(0, 6)));
  console.log('   the toast says where it went:', /Download\/ADB App Manager\/logcat_com\.example\.alpha/.test(await toast()));
  await page.evaluate(() => logcatToggleLevel('W'));

  // 6) Share the same text.
  await page.locator('#view-logcat button', { hasText: 'Share' }).click(); await sleep(100);
  const shared = await page.evaluate(() => window.__calls.share.slice());
  console.log('6. Share hands the same kind of text file to the share sheet:', shared.length === 1 && /^logcat_com\.example\.alpha_/.test(shared[0][0]) && shared[0][2] === 'text/plain' && /careful/.test(shared[0][1]));

  // 7) Text filter still goes through to the bridge; clear button resets to all apps.
  await page.fill('#logcatFilter', 'boom'); await page.press('#logcatFilter', 'Enter'); await sleep(150);
  const last = await page.evaluate(() => window.__calls.logcatFor.slice(-1)[0]);
  console.log('7. the text filter is passed along with the app:', last[1] === 'boom' && last[3] === 'com.example.alpha', JSON.stringify(last));
  await page.fill('#logcatFilter', '');
  await page.locator('#logcatAppClear').click(); await sleep(200);
  console.log('   ✕ Show all apps goes back to the plain log:', (await page.locator('#logcatAppBtn').innerText()) === 'All apps ▾' && !(await page.locator('#logcatAppClear').isVisible()) && /Start proc for all/.test(await page.locator('#logcatOutput').innerText()));

  // 8) Nothing to save before a fetch.
  await page.evaluate(() => { logcatNotice('(cleared)'); window.__calls.save.length = 0; });
  await page.locator('#view-logcat button', { hasText: 'Save' }).click(); await sleep(60);
  console.log('8. saving an empty log says so and writes nothing:', /Nothing to save/.test(await toast()) && (await page.evaluate(() => window.__calls.save.length)) === 0);

  // 9) From the app sheet: Logs.
  await page.evaluate(() => switchView('apps')); await sleep(200);
  await page.evaluate(() => openInspector('com.example.alpha')); await sleep(300);
  console.log('9. the app sheet has a Logs button:', (await page.locator('#sheetBtnLogs').count()) === 1);
  await page.evaluate(() => { window.__calls.logcatFor.length = 0; });
  await page.locator('#sheetBtnLogs').click(); await sleep(400);
  console.log('   it opens Logcat limited to that app:', (await page.evaluate(() => currentViewName())) === 'logcat' && (await page.locator('#logcatAppBtn').innerText()) === 'Alpha ▾' && (await page.evaluate(() => window.__calls.logcatFor.some(c => c[3] === 'com.example.alpha'))) && !(await page.locator('#inspectorModal.show').count()));

  // 10) Back closes the picker.
  await page.locator('#logcatAppBtn').click(); await sleep(100);
  const back = await page.evaluate(() => { const r = handleAndroidBack(); return { r, open: document.getElementById('lcAppModal').classList.contains('show') }; });
  console.log('10. Back closes the app picker:', back.r === true && back.open === false);
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
