// App Updater offline with an earlier result kept (PG3-3): the app keeps the last result and adds offline: true, with no failed apps. The section under the
// updates used to draw "Couldn't check (0)" and "These apps were not checked, so an update for them may be waiting." over an empty list; now it shows only the
// no-internet line. With apps that could not be checked the count and that line are there as before.
const { chromium, PAGE } = require('./lib/pw');
const ROW = (pkg, name) => ({ pkg, name, installedVersion: '1.0', installedCode: 10, availableVersion: '2.0', source: 'github', downloadUrl: 'https://github.com/x/' + pkg + '.apk', page: 'https://github.com/x/' + pkg });
function bridgeInit() {
  const m = { calls: [], state: { running: false, checkedAt: 0, updates: [] } }; window.__m = m; window.confirm = () => true;
  window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return '{}'; }, isSystemDarkMode() { return false; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
    getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
    saveStore() {}, loadStore() { return ''; }, openUrl() {}, getUpdateState() { return JSON.stringify(m.state); },
    checkForUpdates() { m.calls.push('check'); }, cancelUpdateCheck() {}, recheckUpdates() {}, retryFailedUpdates() {}, hasGithubToken() { return false; }, setGithubToken() { return true; } };
}
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 900 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(bridgeInit); await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('updates')); await page.waitForTimeout(150);
  const t0 = Date.now() - 3600e3;
  const result = async extra => {
    await page.evaluate(o => onUpdatesChecked(JSON.stringify(o)), Object.assign({ updates: [ROW('a.b', 'Alpha')], untracked: [], external: [], failed: [], hostsDown: [], cancelled: false, offline: false, total: 40, notChecked: 0, checked: 40, checkedAt: t0 }, extra));
    await page.waitForTimeout(200);
    return (await page.locator('#updExtraContainer').innerText()).replace(/\s+/g, ' ').trim();
  };
  const fails = [{ pkg: 'c.d', name: 'Gamma', code: 'timeout' }, { pkg: 'e.f', name: 'Delta', code: 'io' }];

  let t = await result({ offline: true, checked: 0 });
  check('1. offline, the earlier result kept (no failed apps): no "Couldn’t check (0)" and no "These apps were not checked"', !/Couldn’t check \(/.test(t) && !/These apps were not checked/.test(t), JSON.stringify(t));
  check('   the no-internet line is shown', /No internet connection/.test(t) && /Check your Wi-Fi or mobile data/.test(t), JSON.stringify(t));

  t = await result({ offline: true, failed: fails, checked: 38 });
  check('2. offline with apps that could not be checked: the count, the line and the apps', /Couldn’t check \(2\)/.test(t) && /These apps were not checked/.test(t) && /No internet connection/.test(t) && /Gamma/.test(t) && /Delta/.test(t), JSON.stringify(t));

  t = await result({ failed: fails, checked: 38 });
  check('3. online with apps that could not be checked: the count and the line as before, no no-internet line', /Couldn’t check \(2\)/.test(t) && /These apps were not checked/.test(t) && !/No internet connection/.test(t), JSON.stringify(t));

  t = await result({});
  check('4. online, nothing failed: no such section', !/Couldn’t check/.test(t) && !/No internet connection/.test(t), JSON.stringify(t));

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
