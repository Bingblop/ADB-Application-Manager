// v5.9 Settings tab, part 3: switch detection rules, keyboard use, state that survives a restart, big tables stay quick.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mk = async (kv, extraInit) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
    page.on('dialog', d => d.accept());
    if (kv) await page.addInitScript(k => { window.__kvInit = k; }, kv);
    await page.addInitScript(mock.initScript);
    if (extraInit) await page.addInitScript(extraInit);
    await page.goto(PAGE); await page.waitForTimeout(500);
    await page.evaluate(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(450);
    return page;
  };
  let page = await mk();
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // 1) what counts as a switch, and what a flip gives
  const cases = [['1', '0'], ['0', '1'], ['true', 'false'], ['false', 'true'], ['True', 'False'], ['False', 'True'], ['TRUE', 'FALSE'], ['FALSE', 'TRUE'], ['on', 'off'], ['off', 'on'], ['On', 'Off'], ['ON', 'OFF'], ['yes', 'no'], ['no', 'yes'], ['Yes', 'No'], ['YES', 'NO']];
  const got = await ev(c => c.map(([v]) => { const s = sdbSwitchInfo(v); return s ? s.next : null; }), cases);
  check('1. 0/1, true/false, on/off, yes/no flip to their partner, keeping the letter case', got.every((n, i) => n === cases[i][1]), JSON.stringify(got.map((n, i) => n === cases[i][1] ? '' : [cases[i][0], n])));
  const nots = ['', '2', '-1', '00', '01', ' 1', '1 ', 'null', 'NULL', 'enabled', 'disabled', 'y', 'n', 'T', 'F', 'truee', 'fals', '1.0', 'maybe', 'true\n', 'tRuE x'];
  const gotN = await ev(c => c.map(v => sdbSwitchInfo(v) !== null), nots);
  check('   everything else is left alone', gotN.every(x => x === false), JSON.stringify(nots.filter((v, i) => gotN[i])));
  check('   the state reads right (1 / true / on / yes are on)', await ev(() => ['1', 'true', 'On', 'YES'].every(v => sdbSwitchInfo(v).on) && ['0', 'false', 'Off', 'NO'].every(v => !sdbSwitchInfo(v).on)));
  check('   and a non-string is never a switch', await ev(() => sdbSwitchInfo(1) === null && sdbSwitchInfo(null) === null && sdbSwitchInfo(undefined) === null));

  // 2) the shell command preview matches what the app sends
  check('2. the copied command quotes like the app does', await ev(() => sdbShellCommand('global', "a'b", "it's") === "settings put global 'a'\\''b' 'it'\\''s'" && sdbShellCommand('system', 'k', '') === "settings put system 'k' ''"));

  // 3) keyboard
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'screen_off'; sdbSearchInput(); sdbSetNs('system'); }); await sleep(300);
  const attrs = await ev(() => { const r = document.querySelector('.sdb-row'); return { tab: r.getAttribute('tabindex'), role: r.getAttribute('role'), list: document.getElementById('sdbList').getAttribute('role') }; });
  check('3. rows can take focus and have roles', attrs.tab === '0' && attrs.role === 'listitem' && attrs.list === 'list', JSON.stringify(attrs));
  await page.locator('.sdb-row[data-k="screen_off_timeout"]').focus();
  await page.keyboard.press('Enter'); await sleep(150);
  check('   Enter on a row opens the editor', await ev(() => document.getElementById('sdbEditModal').classList.contains('show')) && (await ev(() => document.getElementById('sdbEditKey').innerText)) === 'screen_off_timeout');
  await ev(() => sdbEditClose());
  await page.locator('.sdb-row[data-k="screen_off_timeout"]').focus();
  await page.keyboard.press(' '); await sleep(150);
  check('   Space too', await ev(() => document.getElementById('sdbEditModal').classList.contains('show')));
  await ev(() => sdbEditClose());
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'accelerometer_rotation'; sdbSearchInput(); }); await sleep(300);
  await ev(() => { window.__calls.op.length = 0; });
  await page.locator('.sdb-row[data-k="accelerometer_rotation"] .sdb-sw').focus();
  await page.keyboard.press('Enter'); await sleep(250);
  check('   Enter on a switch flips it (and does not open the editor)', (await ev(() => window.__calls.op.length)) === 1 && (await ev(() => window.__calls.op[0].value)) === '0' && !(await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))));
  check('   the switch says what state it is in', (await ev(() => document.querySelector('.sdb-row[data-k="accelerometer_rotation"] .sdb-sw').getAttribute('aria-checked'))) === 'false');
  check('   the search box and the buttons have labels', await ev(() => ['sdbSearch'].every(id => document.getElementById(id).getAttribute('aria-label')) && Array.from(document.querySelectorAll('.sdb-iconbtn')).every(b => b.getAttribute('aria-label'))));
  await ev(() => sdbSnackHide());

  // 4) what is remembered
  await ev(() => { sdbResetView(); sdbSetNs('secure'); sdbSetFilter('switch'); sdbSetSort('za'); });
  await ev(() => { sdbPut('secure', 'ui_night_mode', '1', {}); }); await sleep(250);
  await ev(() => sdbPut('global', 'device_name', 'Persisted name', {})); await sleep(250);
  const kv = await ev(() => JSON.parse(JSON.stringify(window.AndroidBridge.__kv)));
  check('4. the table, filter, sort and changes are stored', JSON.parse(kv.sdb_ui).ns === 'secure' && JSON.parse(kv.sdb_ui).filter === 'switch' && JSON.parse(kv.sdb_ui).sort === 'za' && JSON.parse(kv.sdb_journal).length === 3, JSON.stringify(kv).slice(0, 200));
  await page.close();
  page = await mk(kv);
  const re = await ev(() => ({ ns: sdbNs, filter: sdbFilter, sort: sdbSort, journal: sdbJournal.length, active: document.querySelector('.sdb-tab.active').innerText, chip: document.querySelector('#sdbFilters .filter-chip.active').dataset.f, edited: Array.from(sdbEdited).sort().join() }));
  check('   after a restart they are back', re.ns === 'secure' && re.filter === 'switch' && re.sort === 'za' && re.journal === 3 && /Secure/.test(re.active) && re.chip === 'switch' && re.edited === 'global/device_name,secure/ui_night_mode,system/accelerometer_rotation', JSON.stringify(re));
  await ev(() => { sdbSetFilter('edited'); sdbSetNs('global'); }); await new Promise(r => setTimeout(r, 300));
  check('   and the Edited filter still knows what was changed before', JSON.stringify(await ev(() => Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k))) === '["device_name"]');
  await page.close();
  // a damaged store does no harm
  page = await mk({ sdb_ui: '{"ns":"bogus","filter":"nope","sort":5}', sdb_journal: '[1,null,{"ns":"x","k":"a"},{"ns":"global","k":"ok","f":"1","v":"0","t":1}]' });
  const dm = await ev(() => ({ ns: sdbNs, filter: sdbFilter, sort: sdbSort, journal: sdbJournal.map(e => e.k) }));
  check('   a damaged store falls back to the defaults and keeps only the good entries', dm.ns === 'global' && dm.filter === 'all' && dm.sort === 'az' && JSON.stringify(dm.journal) === '["ok"]', JSON.stringify(dm));
  await page.close();

  // 5) a big table stays quick
  page = await mk(null, () => {
    const g = window.__db.global;
    for (let i = 0; i < 5000; i++) g['bulk_setting_' + String(i).padStart(5, '0')] = i % 7 === 0 ? String(i % 2) : 'value number ' + i + ' ' + 'z'.repeat(i % 40);
  });
  const perf = await ev(async () => {
    const t0 = performance.now();
    sdbSetNs('global'); sdbResetView();
    const t1 = performance.now();
    const i = document.getElementById('sdbSearch'); i.value = 'bulk_setting_049'; sdbSearchInput();
    await new Promise(r => setTimeout(r, 300));
    const t2 = performance.now();
    return { total: sdbData.global.size, first: Math.round(t1 - t0), search: Math.round(t2 - t1 - 300), rows: document.querySelectorAll('.sdb-row').length };
  });
  check('5. 5,500 settings: the first page and a search are quick', perf.total > 5400 && perf.first < 400 && perf.search < 400 && perf.rows === 100, JSON.stringify({ total: perf.total, rows: perf.rows }));
  const more = await ev(async () => { const t0 = performance.now(); sdbResetView(); for (let i = 0; i < 5; i++) sdbShowMore(); return { ms: Math.round(performance.now() - t0), rows: document.querySelectorAll('.sdb-row').length }; });
  check('   drawing 720 rows takes well under a second', more.rows === 720 && more.ms < 900, JSON.stringify({ rows: more.rows }));
  const sw = await ev(() => { const t0 = performance.now(); sdbSetFilter('switch'); const n = document.querySelectorAll('.sdb-row').length; sdbSetFilter('all'); return { n, ms: Math.round(performance.now() - t0) }; });
  check('   and so does filtering the whole table', sw.ms < 600 && sw.n > 100, JSON.stringify({ n: sw.n }));
  await page.close();

  await b.close();
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
})();
