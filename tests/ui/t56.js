// v5.8 Back must not leave the app by accident: a double tap is not a decision, a late press re-warns, nothing is left mid-way, and a running job asks first.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {}, fmList(path) { return JSON.stringify({ path, entries: [] }); }, hasAllFilesAccess() { return true; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(450);
  const sleep = ms => page.waitForTimeout(ms);
  // a clock we control: Date.now() is whatever the test says
  await page.evaluate(() => { window.__t = 1000000; Date.now = () => window.__t; });
  const press = async advance => page.evaluate(ms => { window.__t += ms; return handleAndroidBack(); }, advance);
  const toast = () => page.locator('#toastMsg').innerText();
  const reset = () => page.evaluate(() => { backExitArmedAt = 0; backLastAt = 0; window.__t += 100000; });

  // 1) At the first tab with nothing open the first press only warns
  await reset();
  let r = await press(0);
  console.log('1. the first Back at the first tab only warns:', r === true && /Press back again to exit/.test(await toast()));

  // 2) A double tap is not a decision: it warns again instead of leaving
  r = await press(250);
  console.log('2. a second press 250 ms later (a double tap) does not leave:', r === true && /Press back again/.test(await toast()));
  r = await press(250);
  console.log('   nor a third one 250 ms after that:', r === true);

  // 3) A deliberate second press leaves
  r = await press(900);
  console.log('3. a press 900 ms later (within the window) leaves:', r === false);

  // 4) Too late: warns again
  await reset(); await press(0);
  r = await press(4000);
  console.log('4. a press 4 s after the warning (too late) warns again instead of leaving:', r === true);
  r = await press(1000);
  console.log('   and the one after that, a second later, leaves:', r === false);

  // 5) Held-down / mashed Back never leaves
  await reset();
  let leftEarly = false;
  for (let i = 0; i < 12; i++) { if ((await press(200)) === false) leftEarly = true; }
  console.log('5. twelve presses 200 ms apart never leave:', leftEarly === false);
  r = await press(900);
  console.log('   after a pause the next press does:', r === false);

  // 6) The warning does not survive navigation: closing a sheet / changing tabs disarms it
  await reset(); await press(0);
  await page.evaluate(() => { document.getElementById('modesModal').classList.add('show'); });
  r = await press(1000);
  const closed = await page.evaluate(() => !document.getElementById('modesModal').classList.contains('show'));
  console.log('6. with the warning showing, Back on an open sheet closes the sheet (does not leave):', r === true && closed);
  r = await press(1000);
  console.log('   and the next press warns again (the old warning was dropped):', r === true && /Press back again/.test(await toast()));
  await page.evaluate(() => { switchView('files'); });
  r = await press(1500);
  const tab = await page.evaluate(() => currentViewName());
  console.log('   Back from another tab goes to the tab before it, not out:', r === true && tab === 'apps');
  r = await press(1500);
  console.log('   and only then warns:', r === true && /Press back again/.test(await toast()));

  // 7) Something is running: warns in words, and asks before leaving
  await reset();
  await page.evaluate(() => { termShellRun = { id: 's1' }; });
  await press(0);
  console.log('7. with a command running the warning says so:', /terminal command is running/.test(await toast()), JSON.stringify(await toast()));
  dialogs.length = 0; answer = false;
  r = await press(1000);
  console.log('   the second press asks first; declined, it stays:', r === true && dialogs.length === 1 && /Leave now\? A terminal command is running/.test(dialogs[0]), JSON.stringify(dialogs));
  await press(0);                                                                   // re-warn (the decline disarmed it)
  answer = true; dialogs.length = 0;
  r = await press(1000);
  console.log('   accepted, it leaves:', r === false && dialogs.length === 1);
  await page.evaluate(() => { termShellRun = null; });

  // 8) What counts as running
  const reasons = await page.evaluate(() => {
    const out = {};
    const probe = (name, set, clear) => { set(); out[name] = backBusyReason(); clear(); };
    probe('install', () => { installRunning = true; }, () => { installRunning = false; });
    probe('selfUpdate', () => { selfUpdBusy = true; }, () => { selfUpdBusy = false; });
    probe('fileOp', () => { fmBatchRunning = true; }, () => { fmBatchRunning = false; });
    probe('archive', () => { arcBusy = true; }, () => { arcBusy = false; });
    probe('backup', () => { backupRunning = true; }, () => { backupRunning = false; });
    probe('rish', () => { rishRunning = true; }, () => { rishRunning = false; });
    probe('update', () => { updProgress['com.x'] = { stage: 'downloading' }; }, () => { delete updProgress['com.x']; });
    out.idle = backBusyReason();
    return out;
  });
  console.log('8. each long job is recognised, and idle is empty:',
    reasons.install === 'an install is running' && /app update/.test(reasons.selfUpdate) && /file operation/.test(reasons.fileOp) && /archive/.test(reasons.archive)
    && /backup/.test(reasons.backup) && /terminal/.test(reasons.rish) && /update is downloading/.test(reasons.update) && reasons.idle === '', JSON.stringify(reasons));

  // 9) The page answers 1 (handled) or 0 (leave) the way the app asks: same boolean, and a crash in it is not taken for "leave"
  await reset();
  const wrapped = await page.evaluate(() => (function(){try{return window.handleAndroidBack?(window.handleAndroidBack()?1:0):-1;}catch(e){return -1;}})());
  console.log('9. the answer the app reads is 1 for "handled" (the first warning):', wrapped === 1);
  const broken = await page.evaluate(() => { const o = window.handleAndroidBack; window.handleAndroidBack = () => { throw new Error('boom'); }; const v = (function(){try{return window.handleAndroidBack?(window.handleAndroidBack()?1:0):-1;}catch(e){return -1;}})(); window.handleAndroidBack = o; return v; });
  console.log('   a handler that throws answers -1 (the app then applies its own two-press rule), not 0:', broken === -1);

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
