// File manager: press and hold with a touch screen starts selecting (the list is redrawn under the finger); a hold of any length must leave
// exactly that row picked, not toggle it again or open it.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage(); const cdp = await ctx.newCDPSession(page);
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__fs = {
      '/storage/emulated/0': [{ name: 'Download', isDir: true }, { name: 'DCIM', isDir: true }, { name: 'a.txt', isDir: false, size: 10 }, { name: 'b.apk', isDir: false, size: 3000 }],
      '/storage/emulated/0/Download': [{ name: 'x.zip', isDir: false, size: 99 }], '/storage/emulated/0/DCIM': [],
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      hasAllFilesAccess() { return true; }, loadSetting() { return ''; }, saveSetting() {},
      fmList(path) { const entries = (window.__fs[path] || []).map(e => Object.assign({ isLink: false, perms: (e.isDir ? 'd' : '-') + 'rwx', size: 0 }, e)); return JSON.stringify({ path, entries }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(350);
  const sleep = ms => page.waitForTimeout(ms);
  const picked = () => page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-row.fm-picked')).map(r => fmRows[+r.dataset.i].name));
  const touch = async (x, y, ms) => { await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] }); await sleep(ms); await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await sleep(350); };
  await page.evaluate(() => switchView('files')); await sleep(250);
  for (const ms of [520, 700, 1000, 1500]) {
    await page.evaluate(() => { fmGo('/storage/emulated/0'); if (typeof fmSelExit === 'function') fmSelExit(); }); await sleep(200);
    const box = await page.locator('#fmList .perm-info', { hasText: 'a.txt' }).first().boundingBox();
    await touch(box.x + 40, box.y + box.height / 2, ms);
    const p = await picked();
    check('touch hold ' + ms + ' ms: the held file is picked, once', JSON.stringify(p) === '["a.txt"]', JSON.stringify(p));
    check('   and no folder was opened', (await page.evaluate(() => fmPath)) === '/storage/emulated/0');
  }
  // a hold on a folder selects it (it is not entered)
  await page.evaluate(() => { fmGo('/storage/emulated/0'); fmSelExit(); }); await sleep(200);
  const fb = await page.locator('#fmList .perm-info', { hasText: 'Download' }).first().boundingBox();
  await touch(fb.x + 40, fb.y + fb.height / 2, 900);
  check('touch hold 900 ms on a folder: picked, not entered', JSON.stringify(await picked()) === '["Download"]' && (await page.evaluate(() => fmPath)) === '/storage/emulated/0', JSON.stringify(await picked()));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close(); console.log(bad ? 'FAILURES: ' + bad : 'ALL OK'); process.exit(bad ? 1 : 0);
})();
