// The Read-Only banner on the Application Manager tab: it shows whenever the app is in read-only mode (every start), has an X in its top right corner that puts it away
// for now, and comes back the next time read-only is entered (a working mode came on and went again); with a working mode it is not shown at all.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__mode = { configuredMode: 'unprivileged', activeMode: 'unprivileged', isPrivileged: false };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'X', isSystem: false }]); }, getWorkingMode() { return JSON.stringify(window.__mode); }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = fn => page.evaluate(fn);
  const wait = ms => page.waitForTimeout(ms);
  const banner = () => ev(() => { const c = document.getElementById('modeAlertCard'), x = c.querySelector('.mode-alert-x'); const cr = c.getBoundingClientRect(), xr = x.getBoundingClientRect(), br = c.querySelector('.mode-alert-btn').getBoundingClientRect();
    return { shown: getComputedStyle(c).display !== 'none', title: c.querySelector('.mode-alert-title').innerText, xText: x.innerText, topRight: xr.top - cr.top < 12 && cr.right - xr.right < 14 && xr.left > cr.left + cr.width / 2, overlapsSetUp: !(xr.bottom <= br.top || xr.top >= br.bottom || xr.right <= br.left || xr.left >= br.right), x: x.getAttribute('aria-label') }; });

  const a = await banner();
  check('in read-only mode the banner shows with its X in the top right corner, clear of Set Up', a.shown && /Read-Only/.test(a.title) && a.xText === '✕' && a.topRight && !a.overlapsSetUp && a.x === 'Dismiss', JSON.stringify(a));
  await page.screenshot({ path: 'banner_shown.png', clip: { x: 0, y: 0, width: 400, height: 330 } });
  await page.click('#modeAlertCard .mode-alert-x'); await wait(150);
  check('the X puts it away', !(await banner()).shown);
  await ev(() => { switchView('files'); switchView('apps'); checkAllWorkingModes(false); }); await wait(200);
  check('it stays away while read-only goes on (tab changes, a mode check)', !(await banner()).shown);
  check('nothing was kept: the choice is not saved', await ev(() => JSON.stringify(Object.keys(localStorage)).indexOf('modeAlert') < 0 && !JSON.stringify(localStorage).includes('dismiss')));
  await ev(() => { window.__mode = { configuredMode: 'adb_tcp', activeMode: 'adb_tcp', isPrivileged: true, modeAvailable: true, adbTcp: { port: 5555 } }; checkAllWorkingModes(false); }); await wait(150);
  check('with a working mode it is not shown', !(await banner()).shown);
  await ev(() => { window.__mode = { configuredMode: 'unprivileged', activeMode: 'unprivileged', isPrivileged: false }; checkAllWorkingModes(false); }); await wait(150);
  check('read-only again after a working mode: it is back', (await banner()).shown);
  await page.reload(); await wait(600);
  check('a fresh start in read-only shows it again', (await banner()).shown);
  await page.click('#modeAlertCard .mode-alert-btn'); await wait(250);
  check('Set Up still opens the Working modes sheet', await ev(() => document.getElementById('modesModal').classList.contains('show')));
  await ev(() => closeWorkingModesModal());
  console.log('errors:', JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
