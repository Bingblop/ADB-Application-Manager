// The File Manager's selection, paste and search bars stick below the header (and the top tab bar when it is shown) when the list is scrolled (PG1-2):
// they were sticky at top: 0 and slid under the sticky header, so their buttons (Copy, Move, Delete, Paste, Cancel) were hidden behind it.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const files = {};
  for (let i = 0; i < 60; i++) files['/storage/emulated/0/file' + String(i).padStart(2, '0') + '.txt'] = 'x';
  // where the bar is against everything sticky above it, after the list scrolled far down
  const place = (page, id) => page.evaluate(id => {
    window.scrollTo(0, 1500);
    const s = document.getElementById(id).getBoundingClientRect();
    const above = ['.app-header', '#tabBar'].map(q => document.querySelector(q)).filter(e => e && getComputedStyle(e).display !== 'none').map(e => e.getBoundingClientRect().bottom);
    const hit = document.elementFromPoint(s.left + 30, s.top + s.height / 2);
    return { shown: getComputedStyle(document.getElementById(id)).display !== 'none', scrolled: window.scrollY > 300, below: s.top >= Math.max(...above) - 1, onTop: !!(hit && hit.closest('#' + id)) };
  }, id);
  for (const mode of ['bottom', 'both']) {
    const page = await b.newPage({ viewport: { width: 360, height: 700 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(fm.initScript, { files, kv: { perm_intro_v62: '1', nav_mode: JSON.stringify(mode) } });
    await page.goto(PAGE); await page.waitForTimeout(800);
    await page.evaluate(() => switchView('files')); await page.waitForTimeout(800);
    const label = mode === 'both' ? 'Bottom bar + top bar' : 'Bottom bar only';
    await page.evaluate(() => fmToggleSelectMode()); await page.waitForTimeout(300);
    let r = await place(page, 'fmSelBar');
    check('[' + label + '] the selection bar, list scrolled: it sits below the header' + (mode === 'both' ? ' and the tab bar' : '') + ' and is not covered', r.shown && r.scrolled && r.below && r.onTop, JSON.stringify(r));
    await page.evaluate(() => { window.scrollTo(0, 0); fmToggleSelectMode(); }); await page.waitForTimeout(200);
    await page.evaluate(() => { const bar = document.getElementById('fmSearchBar'); bar.style.display = ''; }); await page.waitForTimeout(100);
    r = await place(page, 'fmSearchBar');
    check('[' + label + '] a bar of the same kind (search) does the same', r.shown && r.scrolled && r.below && r.onTop, JSON.stringify(r));
    await page.close();
  }
  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
