// v7.14.0: the labels of Share, Copy package and Copy name in the app menu's header sit in the middle of their chips, across and down (they sat high).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const width of [360, 412]) {
    const page = await b.newPage({ viewport: { width, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => openInspector(allApps[0].pkg)); await page.waitForTimeout(400);
    const r = await page.evaluate(() => [...document.querySelectorAll('#inspectorModal .copy-chip')].map(c => {
      const box = c.getBoundingClientRect(), rg = document.createRange(); rg.selectNodeContents(c); const t = rg.getBoundingClientRect();
      return { name: c.textContent.trim(), dx: +((t.left + t.right) / 2 - (box.left + box.right) / 2).toFixed(1), dy: +((t.top + t.bottom) / 2 - (box.top + box.bottom) / 2).toFixed(1), h: Math.round(box.height) };
    }));
    check(width + 'px: Share, Copy package and Copy name have their label centred (within 1.5 px both ways) and are at least 36 px high',
      r.map(x => x.name).join('|') === 'Share|Copy package|Copy name' && r.every(x => Math.abs(x.dx) <= 1.5 && Math.abs(x.dy) <= 1.5 && x.h >= 36), JSON.stringify(r));
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
