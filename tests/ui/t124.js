// Bottom sheets: the handle at the top of every sheet works. Pulled up, the sheet takes more of the screen; pulled down, it first gets smaller again and then closes;
// a drag that starts lower in the sheet (where the content scrolls) does nothing; a sheet that was pulled up opens normal the next time.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {}, getWorkingMode() { return '{}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  const open = () => ev(() => { const o = document.getElementById('abSheet'); document.getElementById('abSheetList').innerHTML = '<div style="height:1400px">long</div>'; o.classList.add('show'); });
  const box = () => ev(() => { const r = document.querySelector('#abSheet .modal-sheet').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top, h: r.height }; });
  const shown = () => ev(() => document.getElementById('abSheet').classList.contains('show'));
  const expanded = () => ev(() => document.querySelector('#abSheet .modal-sheet').classList.contains('sheet-expanded'));
  const drag = async (x, y0, dy) => { await page.mouse.move(x, y0); await page.mouse.down(); for (let i = 1; i <= 8; i++) await page.mouse.move(x, y0 + dy * i / 8); await page.mouse.up(); await page.waitForTimeout(450); };

  await open(); await page.waitForTimeout(450);
  let r = await box();
  const h0 = r.h;
  await drag(r.x, r.y + 120, 150);
  check('a drag that starts in the content does nothing', (await shown()) && !(await expanded()));
  r = await box();
  await drag(r.x, r.y + 12, -140);
  check('pulling the handle up makes the sheet bigger', (await shown()) && (await expanded()) && (await box()).h > h0 + 20, JSON.stringify(await box()) + ' was ' + h0);
  r = await box();
  await drag(r.x, r.y + 12, 150);
  check('pulling it down makes it smaller again first, it stays open', (await shown()) && !(await expanded()) && Math.abs((await box()).h - h0) < 3);
  r = await box();
  await drag(r.x, r.y + 12, 25);
  check('a small pull down does not close it', (await shown()));
  r = await box();
  await drag(r.x, r.y + 12, 160);
  check('pulling it down closes it', !(await shown()));
  check('after it, the sheet has no leftover offset', await ev(() => document.querySelector('#abSheet .modal-sheet').style.transform === ''));
  await open(); await page.waitForTimeout(450);
  r = await box();
  await drag(r.x, r.y + 12, -140);
  await ev(() => closeAbSheet()); await page.waitForTimeout(400);
  await open(); await page.waitForTimeout(450);
  check('a sheet that was pulled up opens normal the next time', !(await expanded()));
  // every sheet is covered: all of them are .modal-sheet in a .modal-overlay
  check('every bottom sheet is a modal-sheet inside a modal-overlay', await ev(() => Array.from(document.querySelectorAll('.modal-sheet')).every(s => s.closest('.modal-overlay'))));
  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
