// v7.16.0: at launch, when no working mode is ready and the mode is Auto or Shizuku, a Shizuku that is installed but not running, with an ADB path to start it
// through, is offered once to start (the card's own start path runs it); "no" is counted and after two refusals it stops asking; it is not offered when a mode is
// already privileged, when Shizuku is already running (szAutoAsk asks to authorize instead), when there is no ADB path, when Shizuku is not installed, or when the
// mode is pinned to root; and the offer carries the "Start Shizuku" dialog title. The ADB path is a connected ADB TCP or Wireless Debugging target, the same
// `adb devices` check startShizukuNow makes: a TCP port that is open but not connected is not one (T-009, T-011: the start answered "Connect ADB first").
// The first-launch sheet waits for an offered start as long as the start may take, so a slow one is still asked to authorize before the sheet (T-012).
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
      window.__calls = []; window.__confirms = []; window.__answer = confirmAnswer; window.__mode = mode;
      window.confirm = (m) => { window.__confirms.push(m); return window.__answer; };
      const store = Object.assign({}, settings || {});
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        loadSetting(k) { return store[k] || ''; }, saveSetting(k, v) { store[k] = String(v); window.__calls.push('save:' + k + '=' + v); },
        getPermissionStatus() { return '{}'; }, getAdbKeyInfo() { return JSON.stringify({ fingerprint: 'AA', noticePending: false }); },
        getWorkingMode() { return JSON.stringify(window.__mode); }, getWorkingModeFresh() { return JSON.stringify(window.__mode); },
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

  // installed but not running, an ADB path (TCP target connected), mode pinned to Shizuku, nothing privileged
  const offerable = { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
    adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true }, adbWireless: { host: '', port: 0, connected: false },
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
      adbTcp: { portOpen: true, connected: true }, adbWireless: {}, shizuku: { installed: true, running: true, authorized: false } }, true);
  await noOffer('   no ADB path to start it through: not offered',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: false, connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: false } });
  // T-009, T-011: after a restart with the mode on Shizuku nothing has run `adb connect`, so the port is open but no target is listed as ready; startShizukuNow
  // only runs through a listed one and would answer no_adb ("Connect ADB TCP or Wireless Debugging first"), so the launch does not promise a start
  await noOffer('   a TCP port that is open but not connected (nothing connected yet, or this app\'s key not allowed): not offered',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: false }, adbWireless: { host: '', port: 0, connected: false },
      shizuku: { installed: true, running: false, authorized: false } });
  await noOffer('   Shizuku is not installed: not offered',
    { configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: true, connected: true }, adbWireless: {}, shizuku: { installed: false, running: false } });
  await noOffer('   mode pinned to root: not offered',
    { configuredMode: 'root', activeMode: 'root', modeAvailable: false, isPrivileged: false,
      adbTcp: { portOpen: true, connected: true }, adbWireless: {}, shizuku: { installed: true, running: false } });

  // 5. a connected Wireless Debugging target is an ADB path too (the TCP port closed)
  {
    const { page, errors } = await open({ configuredMode: 'shizuku', activeMode: 'shizuku', modeAvailable: false, isPrivileged: false,
      adbTcp: { host: '127.0.0.1', port: 5555, portOpen: false, connected: false }, adbWireless: { host: '127.0.0.1', port: 37001, connected: true },
      shizuku: { installed: true, running: false, authorized: false } }, true);
    const c = await confirms(page), k = await calls(page);
    check('5. a connected Wireless Debugging target: offered, and "yes" starts Shizuku through it',
      c.length === 1 && k.filter(x => x === 'startShizukuViaAdb').length === 1 && errors.length === 0, JSON.stringify({ confirms: c.length, calls: k, errors }));
    await page.close();
  }

  // 6. T-012: the first-launch sheet waits for a start it offered as long as the start may take. startShizukuNow's own bound is about 36 s (adb devices,
  // a reconnect, the script's 20 s, the binder poll), the old wait was 12 s: a start that answered after that opened the sheet first and the Shizuku
  // authorize dialog then came over it. Here the start has been running 13 s when the sheet looks again, then answers "started".
  {
    const { page, errors } = await open(offerable, true);
    await page.evaluate(() => {
      const orig = window.openPermSheet;
      window.openPermSheet = function (mode) { window.__calls.push('sheet:' + mode); return orig.apply(this, arguments); };
    });
    await page.waitForTimeout(1600);                                   // past initApp's 2300 ms first look at the sheet
    const early = await calls(page);
    await page.evaluate(() => { szAutoStartAt = Date.now() - 13000; maybeShowPermIntro(); });
    const at13 = await calls(page);
    await page.evaluate(() => {
      window.__mode = Object.assign({}, window.__mode, { shizuku: { installed: true, running: true, authorized: false } });
      onShizukuStartResult({ status: 'started', pkg: 'moe.shizuku.privileged.api', script: '', exit: 0, text: '' });
    });
    await page.waitForTimeout(700);
    const asked = await calls(page);
    await page.evaluate(() => {
      window.__mode = Object.assign({}, window.__mode, { activeMode: 'shizuku', modeAvailable: true, isPrivileged: true,
        shizuku: { installed: true, running: true, authorized: true } });
      onShizukuPermissionResult(true);
    });
    await page.waitForTimeout(700);
    const after = await calls(page);
    const sheet = k => k.filter(x => x.startsWith('sheet:'));
    check('6. a start still running after 13 s: the first-launch sheet keeps waiting (at 2.3 s and at 13 s)',
      early.includes('startShizukuViaAdb') && sheet(early).length === 0 && sheet(at13).length === 0, JSON.stringify(at13));
    check('   the late "started" asks to authorize Shizuku first, with no sheet open yet',
      asked.includes('requestShizukuPermission') && sheet(asked).length === 0, JSON.stringify(asked));
    check('   the first-launch sheet opens once, after the authorize answer',
      sheet(after).length === 1 && after.indexOf('sheet:intro') > after.indexOf('requestShizukuPermission'), JSON.stringify(after));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  await b.close();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
})();
