// Morphe Helper > All versions: one download at a time. mpHGetVersion did not check h.busy (mpHGo does), and the versions list stays tappable while a
// download runs, so a second tap on a row, or a tap on another row of the same version (another CPU type), started a second helperGet; both wrote the
// same <pkg>_<version>.dl.part, one deleted the other's file and the person was told the source's file was broken. The mock keeps the helperGet answer
// until the script lets it go, and answers through window.onMorphe({tag, ok, data}) as MainActivity does.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__store = {}; window.__gets = []; window.__held = [];
    window.__release = () => { const h = window.__held.splice(0); h.forEach(f => f()); return h.length; };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      loadStore(k) { return window.__store[k] || null; }, saveStore(k, v) { window.__store[k] = v; return true; },
      morphe(tag, op, args) {
        const a = JSON.parse(args);
        const answer = data => setTimeout(() => window.onMorphe({ tag, ok: true, data }), 5);
        if (op === 'helperSources') return answer({ sources: [{ id: 'uptodown', name: 'Uptodown', direct: true }] });
        if (op === 'helperDownloadList') return answer({ jobs: [], background: true });
        if (op === 'helperVersions') return answer({ ok: true, pkg: a.pkg, name: 'YouTube', versions: [
          { version: '20.21.37', format: 'apk', abi: 'arm64-v8a', size: 99000000, url: 'https://x/a.apk' },
          { version: '20.21.37', format: 'apk', abi: 'armeabi-v7a', size: 98000000, url: 'https://x/b.apk' }] });
        if (op === 'helperGet') {
          window.__gets.push(a.version + ' ' + a.abi);
          window.__held.push(() => window.onMorphe({ tag, ok: true, data: { ok: true, path: '/cache/h/youtube_20.21.37.apk', fileName: 'youtube_20.21.37.apk', pkg: a.pkg, versionName: a.version, versionCode: 1546420000, format: 'apk', size: 99000000, sha256: 'ab'.repeat(32) } }));
          return;
        }
        answer({});
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(800);
  const ev = fn => page.evaluate(fn);
  await ev(() => { mp.cfg.hSources = ['uptodown']; mp.cfg.hDefault = 'uptodown'; mp.cfg.hVt = false; return mpHelperOpen('com.google.android.youtube', ''); });
  await ev(() => mpHGo('versions'));
  await page.waitForFunction(() => document.querySelectorAll('#mpSheetBody .mp-patch .mp-act').length === 2);
  const rows = page.locator('#mpSheetBody .mp-patch .mp-act');

  await rows.nth(0).click();
  await page.waitForFunction(() => window.__gets.length === 1);
  check('1. Download on a row starts one download', await ev(() => mp.h.busy === true && /Downloading 20\.21\.37/.test(document.getElementById('mpHMsg').innerText)));
  await rows.nth(0).click();
  await rows.nth(1).click();
  await page.waitForTimeout(100);
  check('2. a second tap on that row, and a tap on the other CPU type of the same version, start nothing while it runs', (await ev(() => window.__gets.length)) === 1, (await ev(() => window.__gets.join(' | '))));
  check('   and the download keeps its message', await ev(() => mp.h.busy === true && /Downloading 20\.21\.37/.test(document.getElementById('mpHMsg').innerText)));

  await ev(() => window.__release());
  await page.waitForFunction(() => !mp.h.busy);
  check('3. its answer is the result', await ev(() => !!mp.h.got && !mp.h.err && document.getElementById('mpHMsg').innerText === 'The APK is ready.'), await ev(() => document.getElementById('mpHMsg').innerText));

  await rows.nth(1).click();
  await page.waitForFunction(() => window.__gets.length === 2);
  check('4. once it is done, another row downloads again', await ev(() => window.__gets[1] === '20.21.37 armeabi-v7a'), await ev(() => window.__gets.join(' | ')));
  await ev(() => window.__release());
  await page.waitForFunction(() => !mp.h.busy);

  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
