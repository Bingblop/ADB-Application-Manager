// v7.14.0: Hidden Settings keeps a history of the last 100 changes (the owner's wish) that can be reviewed: a clock button next to the search box
// opens it (the same Changes sheet as ⋯ > Changes), each entry has the table, how long ago and the date and time, and old → new; a 101st change drops
// the oldest; the history is there again after a new launch, and a longer one saved by an older version is cut to the newest 100.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  let page = null;
  const launch = async kv => {
    if (page) await page.close();
    page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    await page.addInitScript(k => { window.__kvInit = k; }, kv || {});
    await page.addInitScript(mock.initScript);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(400);
  };
  const ev = (fn, a) => page.evaluate(fn, a);

  await launch();
  const btn = await ev(() => { const b = document.getElementById('sdbHistBtn'); const r = b.getBoundingClientRect(), s = document.getElementById('sdbSearch').getBoundingClientRect();
    return { label: b.getAttribute('aria-label'), svg: !!b.querySelector('svg'), w: Math.round(r.width), h: Math.round(r.height), sameLine: Math.abs((r.top + r.bottom) / 2 - (s.top + s.bottom) / 2) < 2,
      fits: document.documentElement.scrollWidth <= window.innerWidth }; });
  check('1. a clock button "History of changes" sits on the search line, 40 px tall (v7.15: a 44 px tap), without widening the page', btn.label === 'History of changes' && btn.svg && btn.w >= 40 && btn.h === 40 && btn.sameLine && btn.fits, JSON.stringify(btn));
  await page.click('#sdbHistBtn'); await page.waitForTimeout(150);
  const empty = await ev(() => ({ open: document.getElementById('sdbJournalModal').classList.contains('show'), title: document.querySelector('#sdbJournalModal h3').innerText,
    sub: document.querySelector('#sdbJournalModal .sheet-title p').innerText, text: document.getElementById('sdbJournalList').innerText }));
  check('   a tap opens the Changes sheet, which says it keeps the last 100 between launches', empty.open && empty.title === 'Changes' && /last 100 changes .* kept between launches/.test(empty.sub) && /Nothing was changed/.test(empty.text), JSON.stringify(empty));
  await ev(() => sdbJournalClose());

  // a real change through the tab, then 104 more: the newest 100 stay, newest first
  await ev(() => sdbPut('global', 'device_name', 'Renamed', { quiet: true })); await page.waitForTimeout(400);
  const one = await ev(() => { sdbOpenJournal(); return Array.from(document.querySelectorAll('#sdbJournalList .sdb-jrow')).map(r => r.innerText.replace(/\s+/g, ' ')); });
  const when = await ev(() => lbWhen(sdbJournal[0].t));
  check('2. an entry shows the setting, the table, how long ago, the date and time, and old → new', one.length === 1 && /device_name/.test(one[0]) && /Global · just now · /.test(one[0]) && one[0].includes(when) && /→ Renamed/.test(one[0]), JSON.stringify(one) + ' ' + when);
  await ev(() => sdbJournalClose());
  await ev(() => { for (let i = 1; i <= 104; i++) sdbRecord('secure', 'probe_' + i, String(i - 1), String(i)); });
  const full = await ev(() => ({ n: sdbJournal.length, first: sdbJournal[0].k, last: sdbJournal[sdbJournal.length - 1].k, has: sdbJournal.some(e => e.k === 'device_name'), saved: JSON.parse(window.AndroidBridge.__kv.sdb_journal).length }));
  check('3. after 105 changes the newest 100 are kept (probe_104 … probe_5); the oldest are dropped, also from what is saved', full.n === 100 && full.first === 'probe_104' && full.last === 'probe_5' && !full.has && full.saved === 100, JSON.stringify(full));
  await ev(() => sdbOpenJournal());
  check('   and the sheet lists all 100', await ev(() => document.querySelectorAll('#sdbJournalList .sdb-jrow').length) === 100);

  // a new launch: the history is still there
  const kv = await ev(() => Object.assign({}, window.AndroidBridge.__kv));
  await launch(kv);
  const back = await ev(() => { sdbInit(); return { n: sdbJournal.length, first: sdbJournal[0].k }; });
  check('4. after a new launch the 100 entries are there, newest first', back.n === 100 && back.first === 'probe_104', JSON.stringify(back));

  // a 150-entry history from an older version is cut to its newest 100
  const old = []; for (let i = 150; i >= 1; i--) old.push({ n: i, t: Date.now() - (151 - i) * 60000, ns: 'global', k: 'old_' + i, f: '0', lf: false, v: '1', lv: false });
  await launch({ sdb_journal: JSON.stringify(old) });
  const cut = await ev(() => { sdbInit(); return { n: sdbJournal.length, first: sdbJournal[0].k, last: sdbJournal[sdbJournal.length - 1].k }; });
  check('5. a 150-entry history saved by an older version comes back as its newest 100', cut.n === 100 && cut.first === 'old_150' && cut.last === 'old_51', JSON.stringify(cut));
  await page.click('#sdbHistBtn'); await page.waitForTimeout(200);
  await page.screenshot({ path: 'sdb_history.png' });
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
