// v7.16.0: at launch, when no working mode is ready and the mode is Auto or Shizuku, a Shizuku that is installed but not running, with an ADB path to start it
// through, is offered once to start (the card's own start path runs it); "no" is counted and after two refusals it stops asking; it is not offered when a mode is
// already privileged, when Shizuku is already running (szAutoAsk asks to authorize instead), when there is no ADB path, when Shizuku is not installed, or when the
// mode is pinned to root; and the offer carries the "Start Shizuku" dialog title.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };

  // Open the page with a given getWorkingMode() JSON, a confirm() answer, and any pre-set settings. The launch timer (initApp, 700 ms) decides on its own whether to
  // offer to start Shizuku; window.confirm is captured, the bridge calls are recorded.
  const open = async (mode, confirmAnswer, settings) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 }, hasTouch: true });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(({ mode, confirmAnswer, settings }) => {
      window.__calls = []; window.__confirms = []; window.__answer = confirmAnswer;
      window.confirm = (m) => { window.__confirms.push(m); return window.__answer; };
      const store = Object.assign({}, settings || {});
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        loadSetting(k) { return store[k] || ''; }, saveSetting(k, v) { store[k] = String(v); window.__calls.push('save:' + k + '=' + v); },
        getPermissionStatus() { return '{}'; }, getAdbKeyInfo() { return JSON.stringify({ fingerprint: 'AA', noticePending: false }); },
        getWorkingMode() { return JSON.stringify(mode); }, getWorkingModeFresh() { return JSON.stringify(mode); },
        requestShizukuPermission() { window.__calls.push('requestShizukuPermission'); return 'requested'; },
        startShizukuViaAdb() { window.__calls.push('startShizukuViaAdb'); return 'started_async'; },
        setNextDialogTitle(t) { window.__calls.push('title:' + t); },
        openShizukuApp() { window.__calls.push('openShizukuApp'); }
      };
    }, { mode, confirmAnswer, settings });
    await page.goto(PAGE);
    await page.waitForTimeout(1000);   // past initApp's 700 ms timer, before the 2300 ms first-launch sheet
    return { page, errors };
  };
  const confirms = page => page.evaluate(() => window.__confirms.slice());
  const calls = page => page.evaluate(() => window.__calls.slice());

  // installed but not running, an ADB path (TCP port open), mode pinned to Shizuku, nothing privileged
  const offerable = { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
    adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: false }, adbWireless: { host: '', port: 0, connected: false },
    shizuku: { installed: true, running: false, authorized: false } };

  // 1. the offer fires once at launch; "yes" starts Shizuku through ADB; it is not asked again the same launch
  {
    const { page, errors } = await open(offerable, true);
    const c = await confirms(page), k = await calls(page);
    check('1. installed-not-running + ADB path + not privileged: offered once at launch',
      c.length === 1 && /installed but not running/i.test(c[0]), JSON.stringify(c));
    check('   the offer carries the "Start Shizuku" dialog title', k.includes('title:Start Shizuku'));
    check('   "yes" starts Shizuku through ADB, exactly once', k.filter(x => x === 'startShizukuViaAdb').length === 1);
    const again = await page.evaluate(() => szAutoStart());
    const c2 = await confirms(page);
    check('   not offered again in the same launch', again === false && c2.length === 1);
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 2. "no" is counted and Shizuku is not started
  {
    const { page } = await open(offerable, false);
    const k = await calls(page);
    check('2. "no" is counted (denial saved) and the start is not run',
      k.includes('save:shizuku_autostart_denied=1') && !k.includes('startShizukuViaAdb'), JSON.stringify(k));
    await page.close();
  }

  // 3. after two earlier refusals, it stops asking
  {
    const { page } = await open(offerable, true, { shizuku_autostart_denied: '2' });
    const c = await confirms(page), k = await calls(page);
    check('3. two earlier refusals: not offered, not started', c.length === 0 && !k.includes('startShizukuViaAdb'), JSON.stringify(c));
    await page.close();
  }

  // 4. it is not offered in the cases that do not apply
  const noOffer = async (label, mode, expectAuthorizeAsk) => {
    const { page, errors } = await open(mode, true);
    const c = await confirms(page), k = await calls(page);
    const askedToAuthorize = k.includes('requestShizukuPermission');
    check(label, c.length === 0 && !k.includes('startShizukuViaAdb') && (expectAuthorizeAsk ? askedToAuthorize : true),
      JSON.stringify({ confirms: c.length, calls: k, errors }));
    await page.close();
  };
  await noOffer('4. a mode is already privileged (ADB TCP connected): not offered',
    { configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true,
      adbTcp: { connected: true, portOpen: true }, adbWireless: {}, shizuku: { installed: true, running: false } });
  await noOffer('   Shizuku is already running: not offered (szAutoAsk asks to authorize instead)',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: true }, adbWireless: {}, shizuku: { installed: true, running: true, authorized: false } }, true);
  await noOffer('   no ADB path to start it through: not offered',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: false, connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: false } });
  await noOffer('   Shizuku is not installed: not offered',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: true }, adbWireless: {}, shizuku: { installed: false, running: false } });
  await noOffer('   mode pinned to root: not offered',
    { configuredMode: 'root', activeMode: 'root', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: true }, adbWireless: {}, shizuku: { installed: true, running: false } });

  await b.close();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
})();
