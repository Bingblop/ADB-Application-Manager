// Morphe > Sources: the Pre-releases toast says what was just set. mpSourcePre read s.prerelease after mpSourcesLoad had replaced the
// source objects, so s held the old value and the toast said "off" when pre-releases were turned on, and "on" when they were turned off;
// it also said so when the change was refused. The mock answers through window.onMorphe({tag, ok, data}) as MainActivity does.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__store = {}; window.__pre = false; window.__refuse = false;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      loadStore(k) { return window.__store[k] || null; }, saveStore(k, v) { window.__store[k] = v; return true; },
      morphe(tag, op, args) {
        const a = JSON.parse(args); let r = { tag, ok: true, data: {} };
        if (op === 'sourcePre') { if (window.__refuse) r = { tag, ok: false, error: 'No source with that id' }; else window.__pre = !!a.on; }
        if (op === 'sources') r.data = { sources: [{ id: 's1', name: 'Morphe', prerelease: window.__pre, enabled: true }] };
        setTimeout(() => window.onMorphe(r), 5);
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(800);
  const toast = () => page.evaluate(() => document.getElementById('toastMsg').innerText);
  await page.evaluate(() => mpSourcesLoad());

  await page.evaluate(() => mpSourcePre('s1'));
  check('1. turning pre-releases on stores on', await page.evaluate(() => window.__pre === true));
  check('   and the toast says on', (await toast()) === 'Pre-releases are on for Morphe. Update it to switch.', await toast());

  await page.evaluate(() => mpSourcePre('s1'));
  check('2. turning them off stores off', await page.evaluate(() => window.__pre === false));
  check('   and the toast says off', (await toast()) === 'Pre-releases are off for Morphe. Update it to switch.', await toast());

  await page.evaluate(() => { window.__refuse = true; return mpSourcePre('s1'); });
  check('3. a refused change says why instead of claiming it switched', (await toast()) === 'No source with that id', await toast());
  check('   and nothing changed', await page.evaluate(() => window.__pre === false && mp.sources[0].prerelease === false));

  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
