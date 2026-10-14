// Task Manager: the six tabs (Processes, CPU, RAM, GPU, Battery, Network) are two even rows of three at 320, 360 and 400 px, in English, German,
// Russian and French. They used to wrap 4 + 2, the last two stretched wider than the rest. Measured: two rows, three equal cells in each, the same
// left edges in both rows, every label on one line and not cut off, at least 36 px tall, no sideways scroll, and a tab still switches the pane.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  for (const lang of ['en', 'de', 'ru', 'fr']) {
    for (const width of [320, 360, 400]) {
      const page = await b.newPage({ viewport: { width, height: 800 } });
      page.on('pageerror', e => errors.push(e.message));
      await page.addInitScript(sdb.initScript);
      await page.goto(PAGE); await page.waitForTimeout(400);
      if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(300); }
      await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(400);
      const g = await page.evaluate(() => {
        const tabs = [...document.querySelectorAll('#tmTabs .sdb-tab')];
        return {
          display: getComputedStyle(document.getElementById('tmTabs')).display,
          docW: document.documentElement.scrollWidth, W: innerWidth,
          tabs: tabs.map(t => {
            const r = t.getBoundingClientRect(), cs = getComputedStyle(t);
            const range = document.createRange(); range.selectNodeContents(t);
            const lines = range.getClientRects().length ? Math.round(range.getBoundingClientRect().height / parseFloat(cs.lineHeight)) : 0;
            return { text: t.innerText.trim(), l: Math.round(r.left), t: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height), lines, clipped: t.scrollWidth > t.clientWidth + 1 };
          }),
        };
      });
      const tag = lang + ' ' + width + ': ';
      const T = g.tabs, rows = [...new Set(T.map(t => t.t))];
      check(tag + 'six tabs in a grid, two rows', g.display === 'grid' && T.length === 6 && rows.length === 2, JSON.stringify(rows));
      check(tag + '  three in each row, the same left edges in both', rows.every(y => T.filter(t => t.t === y).length === 3) && [0, 1, 2].every(i => T[i].l === T[i + 3].l), T.map(t => t.l + ',' + t.t).join(' '));
      check(tag + '  all six the same width and height', T.every(t => Math.abs(t.w - T[0].w) <= 1 && t.h === T[0].h), T.map(t => t.w + 'x' + t.h).join(' '));
      check(tag + '  every label on one line, none cut off, at least 36 px tall', T.every(t => t.lines === 1 && !t.clipped && t.h >= 36), JSON.stringify(T.map(t => [t.text, t.lines, t.h, t.clipped])));
      check(tag + '  the page does not scroll sideways', g.docW <= g.W, g.docW);
      if (width === 360) {
        await page.click('#tmTabs .sdb-tab[data-sub="network"]'); await page.waitForTimeout(250);
        const on = await page.evaluate(() => [...document.querySelectorAll('#tmTabs .sdb-tab.active')].map(t => t.dataset.sub).join());
        check(tag + '  a tap on Network switches to it', on === 'network', on);
      }
      await page.close();
    }
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
