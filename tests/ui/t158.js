// v7.12.10: Connected Devices shows real app icons where it can. The icons come from this phone's icon cache, so an app that is also installed here gets its icon in the list
// and in the app menu (the tile left of its name); an app that exists only on the other device keeps its first letter. The icons are asked for once; one that arrives while the menu
// is open fills the tile; a menu that is not about an app has no tile.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sheet_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAD0lEQVQI12P4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [w, h] of [[360, 800], [412, 915]]) {
    const tag = w + 'x' + h;
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    page.on('pageerror', e => errors.push(tag + ' ' + e.message));
    await page.addInitScript(mock.initScript, { dark: true });
    await page.addInitScript(() => {
      window.__asks = [];
      const old = window.AndroidBridge;
      Object.defineProperty(window, 'AndroidBridge', { configurable: true, get() { return old; } });
      old.loadAppIcons = function (json) {
        const pkgs = JSON.parse(json); window.__asks.push(pkgs.slice());
        setTimeout(() => { const map = {}; pkgs.forEach(p => { if (p === 'com.sec.android.app.sbrowser') map[p] = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAD0lEQVQI12P4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=='; }); if (Object.keys(map).length) window.onAppIcons(map); }, 60);
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(450);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const sleep = ms => page.waitForTimeout(ms);
    await ev(() => {
      window.__asks.length = 0;
      cd.apps = [
        { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', system: true, disabled: false, uninstalled: false },
        { pkg: 'com.remote.only', name: 'Remote Only', system: false, disabled: false, uninstalled: false },
      ];
      cdAppsRender();
    });
    await sleep(250);
    const row = pkg => ev(p => { const c = document.querySelector('#cdAppList .app-card[data-pkg="' + p + '"]'); const i = c.querySelector('.cd-icon'); return { text: i.textContent.trim(), img: !!i.querySelector('img'), src: i.querySelector('img') ? i.querySelector('img').getAttribute('src') : null }; }, pkg);
    const tile = () => ev(() => { const a = document.getElementById('cdSheetAvatar'); const r = a.getBoundingClientRect(); return { text: a.textContent.trim(), img: !!a.querySelector('img'), src: a.querySelector('img') ? a.querySelector('img').getAttribute('src') : null, shown: getComputedStyle(a).display !== 'none', w: Math.round(r.width) }; });

    let r1 = await row('com.sec.android.app.sbrowser'), r2 = await row('com.remote.only');
    check(tag + ' 1. a row of an app that is also installed here shows its real icon', r1.img && r1.src === PNG && r1.text === '', JSON.stringify(r1));
    check(tag + ' 2. a row of an app that exists only on the other device keeps its letter', !r2.img && r2.text === 'R', JSON.stringify(r2));
    await ev(() => cdAppsRender()); await sleep(100);
    const asks = await ev(() => window.__asks.slice());
    check(tag + ' 3. only the icon that is not known yet was asked for (the other was in the cache), once, however often the list is drawn', asks.length === 1 && asks[0].join() === 'com.remote.only', JSON.stringify(asks));

    await ev(() => cdAppMenu('com.sec.android.app.sbrowser')); await sleep(100);
    let t = await tile();
    check(tag + ' 4. the app menu shows the real icon in its tile (44 px)', t.img && t.src === PNG && t.shown && t.w === 44, JSON.stringify(t));
    await ev(() => { cdSheetClose(); cdAppMenu('com.remote.only'); }); await sleep(100);
    t = await tile();
    check(tag + ' 5. the menu of an app that is only on the other device shows its letter', !t.img && t.text === 'R' && t.shown, JSON.stringify(t));
    await ev(() => window.onAppIcons({ 'com.remote.only': 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAD0lEQVQI12P4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==' })); await sleep(100);
    t = await tile(); r2 = await row('com.remote.only');
    check(tag + ' 6. an icon that arrives while the menu is open fills the tile and the row', t.img && t.src === PNG && r2.img, JSON.stringify([t, r2]));
    await ev(() => cdSheetOpen({ title: 'Other', sub: '', buttons: [] })); await sleep(50);
    t = await tile();
    check(tag + ' 7. a menu that is not about an app has no tile', !t.shown && !t.img && t.text === '', JSON.stringify(t));
    const ov = await ev(() => document.documentElement.scrollWidth > window.innerWidth);
    check(tag + ' 8. no sideways scroll', !ov);
    await page.close();
  }
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
