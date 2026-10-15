// v7.14.0: the Apps tab's top row, after the owner's list: it keeps only "Sort" and its menu and "Button" and the Action Button picker,
// on one line without scrolling sideways. Profiles, CSV (was Share CSV) and Backups moved to Saved Applications, beside New List; the
// drift banner's Review still opens App Profiles.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const w of [320, 360, 412]) {
    const page = await b.newPage({ viewport: { width: w, height: 800 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(PAGE); await page.waitForTimeout(400);
    const r = await page.evaluate(() => {
      const row = document.querySelector('.apps-sort-row'), kids = [...row.children];
      const sort = document.getElementById('appSort'), pick = document.getElementById('appsActionBtnPick'), l1 = kids[0], l2 = document.getElementById('appsActionBtnPickTitle');
      const lines = e => Math.round(e.getBoundingClientRect().height / parseFloat(getComputedStyle(e).lineHeight));
      return { names: kids.map(e => e.id || e.tagName.toLowerCase() + ':' + e.innerText.trim().replace(/\n/g, ' ')), fits: row.scrollWidth <= row.clientWidth + 1,
        oneLine: kids.every(e => { const q = e.getBoundingClientRect(), m = row.getBoundingClientRect(); return q.top >= m.top - 1 && q.bottom <= m.bottom + 1; }) && new Set(kids.map(e => Math.round(e.getBoundingClientRect().top + e.getBoundingClientRect().height / 2))).size <= 2,
        sortW: Math.round(sort.getBoundingClientRect().width), pickW: Math.round(pick.getBoundingClientRect().width), forSort: l1.htmlFor === 'appSort', l2text: l2.innerText.trim(), bold: [l1, l2].every(e => +getComputedStyle(e).fontWeight >= 700), pickName: pick.getAttribute('aria-label'),
        buttons: row.querySelectorAll('button:not(#appsActionBtnPick)').length };
    });
    const tag = w + 'px';
    check(tag + ' the row is "Sort", the sort menu, "Button" and the picker, nothing else', r.names.join('|') === 'label:Sort|appSort|appsActionBtnPickTitle|appsActionBtnPick' && r.buttons === 0, r.names.join('|'));
    check(tag + ' "Sort" is the sort menu\'s label; "Button" stands before the picker, which keeps its full name for a screen reader; both words bold', r.forSort && r.bold && r.l2text === 'Button' && r.pickName === 'Action Button', JSON.stringify(r));
    check(tag + ' the row fits on one line without scrolling sideways, and both menus get room (at least 90px)', r.fits && r.oneLine && r.sortW >= 90 && r.pickW >= 90, JSON.stringify(r));
    if (w === 360) {
      await page.evaluate(() => switchView('saved-lists')); await page.waitForTimeout(300);
      const tb = await page.evaluate(() => { const bs = [...document.querySelectorAll('.saved-lists-toolbar > button')]; const tops = bs.map(e => Math.round(e.getBoundingClientRect().top));
        return { names: bs.map(e => e.innerText.trim()), csv: document.getElementById('savedCsvBtn').getAttribute('aria-label'), oneRow: new Set(tops).size === 1, noWrap: bs.every(e => e.getBoundingClientRect().height < 48) }; });
      check('   Saved Applications has New List, Profiles, CSV and Backups in one row (CSV still named for what it does)', tb.names.join('|') === 'New List|Profiles|CSV|Backups' && tb.csv === 'Share the app list as CSV' && tb.oneRow && tb.noWrap, JSON.stringify(tb));
      const calls = await page.evaluate(() => { const got = []; const keep = { p: window.openProfiles, c: window.exportAppList, b: window.openBackups };
        window.openProfiles = () => got.push('profiles'); window.exportAppList = s => got.push('csv:' + s); window.openBackups = () => got.push('backups');
        ['savedProfilesBtn', 'savedCsvBtn', 'savedBackupsBtn'].forEach(id => document.getElementById(id).click());
        window.openProfiles = keep.p; window.exportAppList = keep.c; window.openBackups = keep.b; return got; });
      check('   and they open App Profiles, share the CSV and open Backups', calls.join('|') === 'profiles|csv:true|backups', calls.join('|'));
    }
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
