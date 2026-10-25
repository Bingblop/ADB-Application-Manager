// The Read-Only banner on the Application Manager tab: it shows whenever the app is in read-only mode (every start), has an X in its top right corner that puts it away
// for now, and comes back the next time read-only is entered (a working mode came on and went again); with a working mode it is not shown at all.
// In Arabic (right to left) the X moves to the top left corner, above Set Up, and is drawn over no letter of the title.
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
    const meets = r => !(xr.bottom <= r.top || xr.top >= r.bottom || xr.right <= r.left || xr.left >= r.right);
    // every letter of the title, one by one: none may lie under the X (it has no background, so it would be drawn through the word, and a tap there would put the banner away)
    const t = c.querySelector('.mode-alert-title'), w = document.createTreeWalker(t, NodeFilter.SHOW_TEXT), under = [];
    for (let n; (n = w.nextNode());) for (let i = 0; i < n.length; i++) { const g = document.createRange(); g.setStart(n, i); g.setEnd(n, i + 1); for (const r of g.getClientRects()) if (r.width && meets(r)) under.push(n.data[i]); }
    return { shown: getComputedStyle(c).display !== 'none', dir: getComputedStyle(c).direction, title: t.innerText, xText: x.innerText, topRight: xr.top - cr.top < 12 && cr.right - xr.right < 14 && xr.left > cr.left + cr.width / 2,
      topLeft: xr.top - cr.top < 12 && xr.left - cr.left < 14 && xr.right < cr.left + cr.width / 2, overlapsSetUp: meets(br), underX: under.join(''), x: x.getAttribute('aria-label') }; });

  const a = await banner();
  check('in read-only mode the banner shows with its X in the top right corner, clear of Set Up', a.shown && /Read-Only/.test(a.title) && a.xText === '✕' && a.topRight && !a.overlapsSetUp && a.x === 'Dismiss', JSON.stringify(a));
  check('no letter of the title lies under the X', a.underX === '', JSON.stringify(a));
  await page.screenshot({ path: 'banner_shown.png', clip: { x: 0, y: 0, width: 400, height: 330 } });
  // Arabic mirrors the card's row (the title is now the right-most item); the X must follow to the top left corner, not stay on the title's first word
  for (const width of [320, 360, 400]) {
    await page.setViewportSize({ width, height: 860 });
    await ev(() => LANG_ENGINE.set('ar')); await wait(300);
    const r = await banner();
    check('Arabic at ' + width + ' px: the X sits in the top left corner, clear of Set Up and of every letter of the title', r.shown && r.dir === 'rtl' && /[\u0600-\u06FF]/.test(r.title) && r.topLeft && !r.overlapsSetUp && r.underX === '', JSON.stringify(r));
    if (width === 360) await page.screenshot({ path: 'banner_ar.png', clip: { x: 0, y: 0, width, height: 330 } });
    await ev(() => LANG_ENGINE.set('en')); await wait(200);
  }
  await page.setViewportSize({ width: 400, height: 860 });
  const e = await banner();
  check('back in English the X is in the top right corner again', e.shown && e.dir === 'ltr' && e.topRight && !e.overlapsSetUp && e.underX === '', JSON.stringify(e));
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
