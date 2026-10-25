// Apps tab: Profiles, CSV and Backups live on Saved Applications (v7.14, the owner's choice). While Saved Applications is switched off in the Feature List
// they are on the Apps tab too, in a row of their own under Sort and Button, so App Profiles, the CSV export and Backups can still be reached; with the tab on
// (the default) that row is not there and the Apps tab looks as before.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async kv => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv });
    await page.goto(PAGE); await page.waitForTimeout(500);
    return page;
  };
  const look = page => page.evaluate(() => {
    const fb = document.getElementById('appsSavedFallback'), row = document.querySelector('#view-apps .apps-sort-row');
    const bs = fb ? [...fb.querySelectorAll('button')] : [];
    return { shown: !!fb && fb.offsetParent !== null, names: bs.map(e => e.innerText.trim()).join('|'), csv: bs[1] ? bs[1].getAttribute('aria-label') : '',
      oneRow: new Set(bs.map(e => Math.round(e.getBoundingClientRect().top))).size === 1, fits: document.documentElement.scrollWidth <= window.innerWidth,
      sortRow: [...row.children].map(e => e.id || e.tagName.toLowerCase() + ':' + e.innerText.trim()).join('|'), afterSort: !!fb && row.nextElementSibling === fb };
  });
  const SORT_ROW = 'label:Sort|appSort|appsActionBtnPickTitle|appsActionBtnPick';

  // 1. the default layout: Saved Applications is on, the Apps tab has no extra row
  let page = await open({});
  let r = await look(page);
  check('1. Saved Applications on (the default): the Apps tab shows no Profiles / CSV / Backups row', !r.shown && r.sortRow === SORT_ROW, JSON.stringify(r));
  await page.close();

  // 2. Saved Applications switched off in the Feature List
  page = await open({ tab_config: JSON.stringify({ off: ['saved-lists'] }) });
  const off = await page.evaluate(() => ({ on: tabIsOn('saved-lists'), go: switchView('saved-lists') }));
  check('2. Saved Applications is off and cannot be opened', !off.on && off.go === false, JSON.stringify(off));
  await page.evaluate(() => switchView('apps')); await page.waitForTimeout(200);
  r = await look(page);
  check('   the Apps tab has Profiles, CSV and Backups in one row under Sort and Button (CSV still named for what it does)', r.shown && r.afterSort && r.names === 'Profiles|CSV|Backups' && r.csv === 'Share the app list as CSV' && r.oneRow && r.fits, JSON.stringify(r));
  check('   the Sort and Button row itself is unchanged', r.sortRow === SORT_ROW, r.sortRow);
  const calls = await page.evaluate(() => { const got = []; const keep = { p: window.openProfiles, c: window.exportAppList, b: window.openBackups };
    window.openProfiles = () => got.push('profiles'); window.exportAppList = s => got.push('csv:' + s); window.openBackups = () => got.push('backups');
    document.querySelectorAll('#appsSavedFallback button').forEach(e => e.click());
    window.openProfiles = keep.p; window.exportAppList = keep.c; window.openBackups = keep.b; return got; });
  check('   they open App Profiles, share the CSV and open Backups', calls.join('|') === 'profiles|csv:true|backups', calls.join('|'));
  await page.screenshot({ path: 'apps_saved_off.png' });

  // 3. switched on and off again in the Feature List: the row follows at once
  await page.evaluate(() => featureToggle('saved-lists', true)); await page.waitForTimeout(150);
  r = await look(page);
  check('3. Saved Applications switched back on: the row goes away', !r.shown, JSON.stringify(r));
  await page.evaluate(() => featureToggle('saved-lists', false)); await page.waitForTimeout(150);
  r = await look(page);
  check('   switched off again: it is back', r.shown && r.names === 'Profiles|CSV|Backups', JSON.stringify(r));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
