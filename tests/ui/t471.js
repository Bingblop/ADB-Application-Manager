// v7.14.0: the Apps tab's top row, after the owner's list: the Profiles button is gone (App Profiles opens from the Backups sheet and the drift
// banner), Share CSV is just "CSV", CSV and Backups are plain buttons (not drawn like a pressed filter), and the Sort menu and the Action
// Button picker get the room, so the row fits a phone without scrolling sideways.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const w of [360, 390, 412]) {
    const page = await b.newPage({ viewport: { width: w, height: 800 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(PAGE); await page.waitForTimeout(400);
    const r = await page.evaluate(() => {
      const row = document.querySelector('.apps-sort-row'), kids = [...row.children];
      const btns = [...row.querySelectorAll('.mode-action-btn')].map(e => { const s = getComputedStyle(e); return { t: e.innerText.trim(), label: e.getAttribute('aria-label'), border: s.borderTopColor, color: s.color }; });
      const probe = v => { const e = document.createElement('i'); e.style.color = v; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return c; };
      const sort = document.getElementById('appSort'), pick = document.getElementById('appsActionBtnPick');
      return { names: kids.map(e => e.id || e.innerText.trim()), btns, accent: probe('var(--accent)'), text: probe('var(--text-main)'), fits: row.scrollWidth <= row.clientWidth + 1,
        oneLine: new Set(kids.map(e => Math.round(e.getBoundingClientRect().top))).size === 1, sortW: Math.round(sort.getBoundingClientRect().width), pickW: Math.round(pick.getBoundingClientRect().width),
        noProfiles: !kids.some(e => /Profiles/.test(e.innerText)) };
    });
    const tag = w + 'px';
    check(tag + ' the row is Sort, CSV, Backups and the Action Button picker; no Profiles button', r.names.join('|') === 'appSort|CSV|Backups|appsActionBtnPick' && r.noProfiles, r.names.join('|'));
    check(tag + ' CSV is still named for what it does (Share the app list as CSV)', r.btns[0].label === 'Share the app list as CSV', JSON.stringify(r.btns[0]));
    check(tag + ' CSV and Backups are plain: text colour, no accent outline', r.btns.every(x => x.color === r.text && x.border !== r.accent), JSON.stringify(r.btns) + ' accent ' + r.accent);
    check(tag + ' the row fits on one line without scrolling sideways, and the two menus get the room (each at least 96px)', r.fits && r.oneLine && r.sortW >= 96 && r.pickW >= 96, JSON.stringify(r));
    if (w === 390) {
      await page.evaluate(() => openBackups()); await page.waitForTimeout(300);
      check('   the Backups sheet has App Profiles…', await page.evaluate(() => { const e = document.getElementById('backupsProfilesBtn'); return !!e && document.getElementById('backupsModal').contains(e) && /App Profiles/.test(e.innerText); }));
      await page.evaluate(() => document.getElementById('backupsProfilesBtn').click()); await page.waitForTimeout(300);
      check('   and it opens App Profiles in place of Backups', await page.evaluate(() => document.getElementById('profilesModal').classList.contains('show') && !document.getElementById('backupsModal').classList.contains('show')));
    }
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
