// v7.14.0: the Apps tab's top row, after the owner's list: the Profiles button moved to Saved Applications (next to New List; the drift banner
// still opens it), Share CSV is just "CSV", CSV and Backups are plain buttons (not drawn like a pressed filter), and the Sort menu and the Action
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
      await page.evaluate(() => switchView('saved-lists')); await page.waitForTimeout(300);
      const pb = await page.evaluate(() => { const e = document.getElementById('savedProfilesBtn'), n = e && e.previousElementSibling; const r = e && e.getBoundingClientRect(), q = n && n.getBoundingClientRect();
        return { inView: !!e && document.getElementById('view-saved-lists').contains(e), text: e && e.innerText.trim(), nextToNew: !!n && /New List/.test(n.innerText) && Math.abs(r.top - q.top) < 2, h: r && Math.round(r.height) }; });
      check('   Saved Applications has a Profiles button beside New List', pb.inView && pb.text === 'Profiles' && pb.nextToNew && pb.h > 0, JSON.stringify(pb));
      await page.click('#savedProfilesBtn'); await page.waitForTimeout(300);
      check('   and it opens App Profiles', await page.evaluate(() => document.getElementById('profilesModal').classList.contains('show')));
    }
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
