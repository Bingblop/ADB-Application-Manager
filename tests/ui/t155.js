// v7.12.10: the tile left of the app's name in the single-app menu shows the app's own icon instead of its first letter. The letter stays until the icon is known
// (the icon is asked for once), a cached icon is there at once, an icon that cannot be drawn falls back to the letter, and an icon that arrives for an app that is
// no longer the one in the menu does not replace the tile of the current one.
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
    const ctx = await b.newContext({ viewport: { width: w, height: h }, hasTouch: true, colorScheme: 'dark' });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(tag + ' ' + e.message));
    await page.addInitScript(mock.initScript, { dark: true });
    await page.addInitScript(() => {
      window.__iconAsks = [];
      window.__iconMode = 'none';                                       // what the bridge answers: png | broken | none
      const old = window.AndroidBridge;
      Object.defineProperty(window, 'AndroidBridge', { configurable: true, get() { return old; } });
      old.loadAppIcons = function (json) {
        const pkgs = JSON.parse(json); window.__iconAsks.push(pkgs.join(','));
        setTimeout(() => {
          const map = {};
          if (window.__iconMode === 'png') pkgs.forEach(p => { map[p] = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAD0lEQVQI12P4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=='; });
          if (window.__iconMode === 'broken') pkgs.forEach(p => { map[p] = 'data:image/png;base64,AAAA'; });
          if (Object.keys(map).length) window.onAppIcons(map);
        }, 60);
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(450);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const sleep = ms => page.waitForTimeout(ms);
    const tile = () => ev(() => { const a = document.getElementById('sheetAvatar'); const i = a.querySelector('img'); const r = a.getBoundingClientRect(); const ir = i ? i.getBoundingClientRect() : null; return { text: a.textContent, hasIcon: a.classList.contains('has-icon'), img: !!i, src: i ? i.getAttribute('src') : null, w: r.width, h: r.height, iw: ir ? ir.width : 0, ih: ir ? ir.height : 0 }; });
    const open = async pkg => { await ev(p => { closeInspector(); openInspector(p); }, pkg); };
    const SB = 'com.sec.android.app.sbrowser', OD = 'com.microsoft.skydrive', FB = 'com.facebook.appmanager';

    await ev(() => { window.__iconMode = 'png'; });                       // the list asked for every icon at load and was told nothing
    await open(SB);
    let t = await tile();
    check(tag + ' 1. before the icon is known the tile shows the first letter', t.text === 'S' && !t.img && !t.hasIcon && Math.round(t.w) === 44, JSON.stringify(t));
    await sleep(200);
    t = await tile();
    check(tag + ' 2. when the icon arrives the tile shows it (44x44, no letter)', t.img && t.hasIcon && t.src === PNG && t.text === '' && Math.round(t.w) === 44 && Math.round(t.iw) === 44 && Math.round(t.ih) === 44, JSON.stringify(t));
    const asks1 = await ev(() => window.__iconAsks.slice());
    check(tag + ' 3. the menu asked for the icon once, for this app alone', asks1.filter(x => x === SB).length === 1, JSON.stringify(asks1));

    await open(SB);
    t = await tile();
    const asks2 = await ev(() => window.__iconAsks.slice());
    check(tag + ' 4. opening the same app again shows the icon at once and asks nothing more', t.img && t.src === PNG && asks2.length === asks1.length, JSON.stringify([t.img, asks2.length, asks1.length]));

    await ev(() => { window.__iconMode = 'broken'; });
    await open(OD); await sleep(200);
    t = await tile();
    check(tag + ' 5. an icon that cannot be drawn falls back to the first letter', t.text === 'O' && !t.hasIcon && !t.img, JSON.stringify(t));

    await ev(() => { window.__iconMode = 'none'; });
    await open(FB); await sleep(200);
    t = await tile();
    check(tag + ' 6. an app with no icon from the phone keeps the letter', t.text === 'F' && !t.img, JSON.stringify(t));

    // an icon that arrives for another app while the menu shows this one
    await ev(() => { window.__iconMode = 'none'; closeInspector(); });
    await open(OD); await sleep(100);
    await ev(p => { window.onAppIcons({ 'com.not.shown': 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAD0lEQVQI12P4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==' }); });
    t = await tile();
    check(tag + ' 7. an icon that arrives for another app does not replace this tile', !t.img && t.text === 'O', JSON.stringify(t));
    await ev(() => closeInspector());

    const ov = await ev(() => document.documentElement.scrollWidth > window.innerWidth);
    check(tag + ' 8. no sideways scroll', !ov);
    await ctx.close();
  }
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
