// v6.0.3: the single-app sheet (the ⋯ menu of an app) stands a little taller than the other sheets (93% of the screen, not 85%),
// so the lists under its buttons get more room. Nothing else changes: the other sheets keep their height, a strip above stays tappable,
// the end of a long list can still be reached, and the sheet follows a shorter window (on-screen keyboard).
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sheet_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [w, h] of [[360, 800], [320, 640], [412, 915]]) {
    const tag = `${w}x${h}`;
    const ctx = await b.newContext({ viewport: { width: w, height: h }, hasTouch: true, colorScheme: 'dark' });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(tag + ' ' + e.message)); page.on('console', m => { if (m.type() === 'error') errors.push(tag + ' console: ' + m.text()); });
    await page.addInitScript(mock.initScript, { dark: true });
    await page.goto(URL); await page.waitForTimeout(450);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const open = async pkg => { await ev(p => { closeInspector(); openInspector(p); }, pkg); await page.waitForTimeout(600); };
    // where the sheet and the first list are, with the sheet scrolled to its top
    const geo = () => ev(() => {
      const sh = document.querySelector('#inspectorModal .modal-sheet'); sh.scrollTop = 0;
      const r = sh.getBoundingClientRect(); const list = document.getElementById('permsContainer').getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, height: r.height, listTop: list.top, vh: window.innerHeight, maxH: parseFloat(getComputedStyle(sh).maxHeight), overflowX: document.documentElement.scrollWidth > window.innerWidth };
    });

    await open('com.sec.android.app.sbrowser');
    const g = await geo();
    check(`1. [${tag}] the app sheet may stand 93% of the screen high`, Math.abs(g.maxH - 0.93 * g.vh) < 1, `${g.maxH}px of ${g.vh}px`);
    check('   and with this much in it, it does', Math.abs(g.height - 0.93 * g.vh) < 1.5, `${Math.round(g.height)}px`);
    check('   a strip is left above it to tap and close (at least 4% of the height and 24px)', g.top >= 0.04 * g.vh && g.top >= 24, `${Math.round(g.top)}px`);
    check('   nothing sticks out sideways', !g.overflowX);

    // the room for the first list, against what 85% would give
    await ev(() => { const s = document.createElement('style'); s.id = '__t'; s.textContent = '#inspectorModal .modal-sheet{max-height:85vh !important}'; document.head.appendChild(s); });
    await page.waitForTimeout(120);
    const g85 = await geo();
    await ev(() => document.getElementById('__t').remove()); await page.waitForTimeout(120);
    const g93 = await geo();
    const room85 = g85.vh - g85.listTop, room93 = g93.vh - g93.listTop;
    check(`2. [${tag}] the lists get 8% of the screen height more room`, Math.abs((room93 - room85) - 0.08 * g.vh) <= 2, `${Math.round(room85)}px -> ${Math.round(room93)}px`);

    // only this sheet is taller
    const others = await ev(() => Array.from(document.querySelectorAll('.modal-overlay')).filter(o => o.id !== 'inspectorModal').map(o => { const s = o.querySelector('.modal-sheet'); return s ? [o.id, parseFloat(getComputedStyle(s).maxHeight), window.innerHeight] : null; }).filter(Boolean));
    const wrong = others.filter(([id, mh, vh]) => Math.abs(mh - 0.85 * vh) >= 1);
    check(`3. [${tag}] the other sheets keep 85% (${others.length} checked)`, others.length >= 20 && wrong.length === 0, JSON.stringify(wrong));

    // the end of a long list can still be reached: scroll the sheet to its end, then the list to its end
    await open('com.sec.android.app.sbrowser');
    const reach = await ev(() => {
      const sh = document.querySelector('#inspectorModal .modal-sheet'); sh.scrollTop = sh.scrollHeight;
      const tc = document.getElementById('sheetTabPerms'); tc.scrollTop = tc.scrollHeight;
      const rows = document.querySelectorAll('#permsContainer .perm-row'); const last = rows[rows.length - 1].getBoundingClientRect();
      const sr = sh.getBoundingClientRect(); const tr = tc.getBoundingClientRect();
      return { n: rows.length, lastTop: Math.round(last.top), lastBottom: Math.round(last.bottom), sheetTop: Math.round(sr.top), sheetBottom: Math.round(sr.bottom), tabTop: Math.round(tr.top), tabBottom: Math.round(tr.bottom) };
    });
    check(`4. [${tag}] the last of ${reach.n} permissions can be scrolled into view`, reach.n === 45 && reach.lastBottom <= reach.sheetBottom + 1 && reach.lastBottom <= reach.tabBottom + 1 && reach.lastTop >= reach.sheetTop && reach.lastTop >= reach.tabTop - 1, JSON.stringify(reach));

    // a tap on the strip above the sheet closes it
    const topNow = (await geo()).top;
    await page.mouse.click(w / 2, topNow / 2); await page.waitForTimeout(350);
    check(`5. [${tag}] a tap on the strip above the sheet closes it`, !(await ev(() => document.getElementById('inspectorModal').classList.contains('show'))), `tapped at y=${Math.round(topNow / 2)}`);

    // a frozen and suspended app, and one removed for the user (different buttons, same sheet)
    for (const pkg of ['com.facebook.appmanager', 'com.microsoft.skydrive']) {
      await open(pkg); const gg = await geo();
      check(`6. [${tag}] ${pkg}: the sheet fits the screen`, gg.top >= 0.04 * gg.vh && gg.bottom <= gg.vh + 1 && !gg.overflowX && gg.height <= 0.93 * gg.vh + 1, `top ${Math.round(gg.top)}px, ${Math.round(gg.height)}px high`);
    }

    // a shorter window (the on-screen keyboard is open): the sheet follows it
    if (w === 360) {
      await open('com.sec.android.app.sbrowser');
      await page.setViewportSize({ width: w, height: 480 }); await page.waitForTimeout(250);
      const gk = await geo();
      check(`7. [${tag}] in a window only 480px high the sheet is 93% of that`, Math.abs(gk.maxH - 0.93 * 480) < 1 && gk.top >= 0 && gk.bottom <= 481, `max ${gk.maxH}px, top ${Math.round(gk.top)}px`);
      await page.setViewportSize({ width: w, height: h }); await page.waitForTimeout(200);
    }
    await ctx.close();
  }
  await b.close();
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
})();
