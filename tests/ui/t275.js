// Connected Devices, Send and Files: a new pick tells the app which earlier picks the page still holds (the Send queue, a push in progress), so the
// clean-up of picks over an hour old in the app's cache keeps them (#189; DeviceLinkTest checks the clean-up itself).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const A = '192.168.1.20:5555';
    const st = window.__st = { picks: [], next: {}, pushes: {} };
    const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
      cdCancel() {},
      cdPickPackages(tag, inUse) {
        st.picks.push({ tag, inUse: inUse === undefined ? null : JSON.parse(inUse) });
        setTimeout(() => window.onCdPicked({ tag, files: st.next[tag] || [] }), 10);
      },
      cdAdb(tag, serial, argsJson) {
        const a = JSON.parse(argsJson);
        if (a[0] === 'devices') return reply(tag, 'List of devices attached\n' + A + '\tdevice product:a model:Watch device:a transport_id:2\n');
        if (a[0] === 'push') { st.pushes[a[1]] = () => reply(tag, a[1] + ': 1 file pushed, 0 skipped.'); return; }      // held until the script lets it finish
        if (a[0] === 'shell' && /^for p in/.test(a[1])) return reply(tag, 'ro.product.model=Watch\nro.build.version.release=14\nro.build.version.sdk=34');
        if (a[0] === 'shell' && /^ls /.test(a[1])) return reply(tag, '');
        return reply(tag, '');
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => kvSet('cd_autore', false));
  await page.evaluate(() => switchView('devices')); await page.waitForTimeout(600);
  const last = () => page.evaluate(() => { const p = window.__st.picks; return p[p.length - 1]; });

  // Send: the first pick has nothing to keep; the next one names what is in the queue
  await page.evaluate(() => { cdSub('send'); window.__st.next.send = [{ name: 'face.apk', path: '/cache/cd_pick/1/face.apk', size: 10 }, { name: 'game.xapk', path: '/cache/cd_pick/2/game.xapk', size: 20 }]; cdPickFiles(); });
  await page.waitForTimeout(150);
  let p = await last();
  check('1. the first pick passes an empty list of files to keep', p.tag === 'send' && Array.isArray(p.inUse) && p.inUse.length === 0, JSON.stringify(p));
  check('   the two files are in the Send queue', await page.evaluate(() => cd.queue.length === 2));
  await page.evaluate(() => { window.__st.next.send = []; cdPickFiles(); }); await page.waitForTimeout(150);
  p = await last();
  check('2. a later pick asks the app to keep the queued files (once each)', JSON.stringify(p.inUse.slice().sort()) === JSON.stringify(['/cache/cd_pick/1/face.apk', '/cache/cd_pick/2/game.xapk']), JSON.stringify(p.inUse));
  await page.evaluate(() => cdQueueDrop(0)); await page.waitForTimeout(50);
  await page.evaluate(() => cdPickFiles()); await page.waitForTimeout(150);
  p = await last();
  check('3. a file taken off the queue is no longer kept', JSON.stringify(p.inUse) === JSON.stringify(['/cache/cd_pick/2/game.xapk']), JSON.stringify(p.inUse));

  // Files > Push: a file being pushed is kept while the push runs, and let go afterwards
  await page.evaluate(() => { cdQueueClear(); cdSub('files'); }); await page.waitForTimeout(200);
  await page.evaluate(() => { window.__st.next.push = [{ name: 'big.zip', path: '/cache/cd_pick/3/big.zip', size: 30 }]; cdFmPush(); });
  await page.waitForTimeout(200);
  check('4. the push has started', await page.evaluate(() => !!window.__st.pushes['/cache/cd_pick/3/big.zip']));
  await page.evaluate(() => { window.__st.next.send = []; cdPickFiles(); }); await page.waitForTimeout(150);
  p = await last();
  check('   a pick while it runs keeps the file being pushed', JSON.stringify(p.inUse) === JSON.stringify(['/cache/cd_pick/3/big.zip']), JSON.stringify(p.inUse));
  await page.evaluate(() => window.__st.pushes['/cache/cd_pick/3/big.zip']()); await page.waitForTimeout(200);
  await page.evaluate(() => cdPickFiles()); await page.waitForTimeout(150);
  p = await last();
  check('5. after the push the file is let go', JSON.stringify(p.inUse) === '[]', JSON.stringify(p.inUse));

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
