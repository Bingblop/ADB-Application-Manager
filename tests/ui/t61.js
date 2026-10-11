// v5.9 Settings tab, part 2: history of changes and revert, the "Edited" filter, Back, a change in flight, text that looks like markup,
// the tools sheet, risky delete / create, a mode change while the tab is open, keyboard use, narrow screens.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => {
    // rows that must stay text
    const add = () => {
      window.__db.global['<img src=x onerror="window.__pwned=1">'] = '1';
      window.__db.global['xss_value'] = '<script>window.__pwned=2<\/script><b>bold</b>';
      window.__db.global['quote"key\'s'] = '"><svg onload=window.__pwned=3>';
    };
    const iv = setInterval(() => { if (window.__db) { add(); clearInterval(iv); } }, 1);
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const hasRow = key => ev(k => Array.from(document.querySelectorAll('.sdb-row')).some(x => x.dataset.k === k), key);
  const rowText = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? r.innerText : null; }, key);
  const find = async (key, ns = 'global') => { await ev(n => { sdbSetNs(n); sdbResetView(); }, ns); await sleep(80); await ev(k => { const i = document.getElementById('sdbSearch'); i.value = k; sdbSearchInput(); }, key); await sleep(260); };
  const hold = async (key, ms = 650) => {
    const box = await page.locator('.sdb-row[data-k="' + key.replace(/"/g, '\\"') + '"]').first().boundingBox();
    await page.mouse.move(box.x + 24, box.y + box.height / 2); await page.mouse.down(); await sleep(ms); await page.mouse.up(); await sleep(120);
  };
  const lastOp = () => ev(() => window.__calls.op[window.__calls.op.length - 1]);
  const opCount = () => ev(() => window.__calls.op.length);
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  await ev(() => { checkAllWorkingModes(false); switchView('settings'); }); await sleep(500);

  // 1) text that looks like markup stays text
  await find('img src');
  const x1 = await ev(() => ({ imgs: document.querySelectorAll('#sdbList img, #sdbList svg, #sdbList script').length, pwned: window.__pwned || 0, rows: document.querySelectorAll('.sdb-row').length }));
  check('1. a name that looks like an <img onerror> is shown as text, nothing runs', x1.imgs === 0 && !x1.pwned && x1.rows === 1, JSON.stringify(x1));
  await find('xss_value');
  const x2 = await ev(() => ({ html: document.querySelector('.sdb-row').innerHTML, pwned: window.__pwned || 0, bold: document.querySelectorAll('#sdbList b').length }));
  check('   a value with <script> and <b> is text too', !x2.pwned && x2.bold === 0 && /&lt;script&gt;/.test(x2.html), x2.html.slice(0, 200));
  await find('quote');
  const x3 = await ev(() => ({ n: document.querySelectorAll('.sdb-row').length, pwned: window.__pwned || 0, key: (document.querySelector('.sdb-row') || {}).dataset ? document.querySelector('.sdb-row').dataset.k : null }));
  check('   quotes in a name survive the round trip to the editor', x3.n === 1 && !x3.pwned && x3.key === 'quote"key\'s', JSON.stringify(x3));
  await page.locator('.sdb-row').first().click(); await sleep(120);
  check('   and it opens', (await ev(() => document.getElementById('sdbEditKey').innerText)) === 'quote"key\'s');
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Command' }).click(); await sleep(60);
  check('   Copy command escapes both kinds of quote', (await ev(() => window.__copied)) === "settings put global 'quote\"key'\\''s' '\"><svg onload=window.__pwned=3>'", await ev(() => window.__copied));
  check('   nothing ran while the editor filled in', !(await ev(() => window.__pwned)));
  await closeAll();

  // 2) the history of changes
  await find('stay_on_while');
  await ev(() => { window.__calls.op.length = 0; });
  await page.locator('.sdb-row[data-k="stay_on_while_plugged_in"]').click(); await sleep(100);
  await page.locator('#sdbEditText').fill('3'); await page.locator('#sdbEditSaveBtn').click(); await sleep(250);
  await find('device_name');
  await page.locator('.sdb-row[data-k="device_name"]').click(); await sleep(100);
  await page.locator('#sdbEditText').fill('Renamed'); await page.locator('#sdbEditSaveBtn').click(); await sleep(250);
  await ev(() => sdbSnackHide());
  const j = await ev(() => JSON.parse(JSON.stringify(sdbJournal)));
  check('2. every change is recorded, newest first, with the old and new value', j.length === 2 && j[0].k === 'device_name' && j[0].f === "Sam's phone" && j[0].v === 'Renamed' && j[1].k === 'stay_on_while_plugged_in' && j[1].f === '7' && j[1].v === '3', JSON.stringify(j.map(e => [e.ns, e.k, e.f, e.v])));
  check('   and kept in the app\'s settings store', JSON.parse(await ev(() => window.AndroidBridge.__kv.sdb_journal)).length === 2);
  await ev(() => sdbResetView()); await sleep(100);
  await page.locator('#sdbFilters .filter-chip[data-f="edited"]').click(); await sleep(200);
  const ed = await ev(() => Array.from(document.querySelectorAll('.sdb-row')).map(r => ({ k: r.dataset.k, dot: !!r.querySelector('.sdb-dot') })));
  check('   the Edited filter lists exactly those, each with a dot', ed.length === 2 && ed.every(r => r.dot) && ed.map(r => r.k).sort().join() === 'device_name,stay_on_while_plugged_in', JSON.stringify(ed));
  await page.locator('#sdbFilters .filter-chip[data-f="all"]').click(); await sleep(150);
  await page.locator('#view-settings .sdb-iconbtn:not(.accent)').click(); await sleep(150);
  await page.locator('#sdbMenuModal .batch-grid-btn', { hasText: 'Changes' }).click(); await sleep(150);
  const jr = await ev(() => Array.from(document.querySelectorAll('#sdbJournalList .sdb-jrow')).map(r => r.innerText.replace(/\s+/g, ' ')));
  check('   the Changes sheet shows them with "old → new" and a time', jr.length === 2 && /device_name/.test(jr[0]) && /Sam's phone → Renamed/.test(jr[0]) && /just now/.test(jr[0]), JSON.stringify(jr));
  await page.locator('#sdbJournalList .sdb-jrow', { hasText: 'device_name' }).locator('button').click(); await sleep(300);
  check('   Revert puts the old value back', (await ev(() => window.__db.global.device_name)) === "Sam's phone" && !(await ev(() => document.getElementById('sdbJournalModal').classList.contains('show'))));
  check('   and the revert is itself recorded', (await ev(() => sdbJournal.length)) === 3 && (await ev(() => sdbJournal[0].v)) === "Sam's phone");
  // revert when the value moved on
  await ev(() => { window.__db.global.stay_on_while_plugged_in = '5'; sdbData.global.set('stay_on_while_plugged_in', '5'); sdbDirty(); });
  await ev(() => sdbOpenJournal()); await sleep(100);
  dialogs.length = 0; answer = false;
  const nRev = await opCount();
  await page.locator('#sdbJournalList .sdb-jrow', { hasText: 'stay_on_while_plugged_in' }).locator('button').click(); await sleep(200);
  check('   reverting something that has changed since asks first', dialogs.length === 1 && /is now/.test(dialogs[0]) && /“5”/.test(dialogs[0]) && (await opCount()) === nRev, JSON.stringify(dialogs));
  answer = true;
  await ev(() => sdbOpenJournal()); await sleep(100);
  await page.locator('#sdbJournalList .sdb-jrow', { hasText: 'stay_on_while_plugged_in' }).locator('button').click(); await sleep(300);
  check('   and goes ahead when told to', (await ev(() => window.__db.global.stay_on_while_plugged_in)) === '7');
  // a created setting reverts by deleting it
  await ev(() => { sdbOpenNew('system'); }); await sleep(80);
  await page.locator('#sdbNewKey').fill('made_up_one'); await page.locator('#sdbNewValue').fill('5'); await page.locator('#sdbNewCreateBtn').click(); await sleep(300);
  await ev(() => sdbSnackHide()); await ev(() => sdbOpenJournal()); await sleep(100);
  await page.locator('#sdbJournalList .sdb-jrow', { hasText: 'made_up_one' }).locator('button').click(); await sleep(300);
  check('   a setting that was created reverts by being removed', !(await ev(() => 'made_up_one' in window.__db.system)));
  // clear
  await ev(() => sdbOpenJournal()); await sleep(100);
  await page.locator('#sdbJournalClear').click(); await sleep(150);
  check('   Clear history forgets the list but not the settings', (await ev(() => sdbJournal.length)) === 0 && (await ev(() => window.__db.global.device_name)) === "Sam's phone" && /Nothing was changed/.test(await ev(() => document.getElementById('sdbJournalList').innerText)));
  await closeAll();

  // 3) a very long value is recorded without its text, and can't be reverted
  await find('long_value');
  await page.locator('.sdb-row[data-k="long_value"]').click(); await sleep(100);
  check('3. a long value shows clipped in the list but whole in the editor', (await ev(() => document.getElementById('sdbEditText').value.length)) === 900 && (await rowText('long_value')).length < 400);
  await page.locator('#sdbEditText').fill('x'.repeat(2500)); await page.locator('#sdbEditSaveBtn').click(); await sleep(250); await ev(() => sdbSnackHide());
  const longJ = await ev(() => JSON.parse(JSON.stringify(sdbJournal[0])));
  check('   too long to keep: the entry has no text for the new value', longJ.lv === true && longJ.v === null && longJ.lf === false && longJ.f.length === 900, JSON.stringify({ lv: longJ.lv, v: longJ.v, lf: longJ.lf }));
  await ev(() => sdbOpenJournal()); await sleep(100);
  check('   and says so in the Changes sheet', /too long to keep/.test(await ev(() => document.getElementById('sdbJournalList').innerText)));
  await closeAll();

  // 4) Back
  await ev(() => { document.getElementById('sdbSearch').value = 'adb'; sdbSearchInput(); }); await sleep(300);
  check('4. Back clears the search first', (await ev(() => handleAndroidBack())) === true && (await ev(() => sdbQuery)) === '' && (await ev(() => document.getElementById('sdbSearch').value)) === '' && (await ev(() => currentViewName())) === 'settings');
  await find('device_name');
  await page.locator('.sdb-row[data-k="device_name"]').click(); await sleep(120);
  check('   with the editor open it closes the editor', (await ev(() => handleAndroidBack())) === true && !(await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))));
  await ev(() => sdbOpenNew()); await sleep(100);
  check('   and the create sheet', (await ev(() => handleAndroidBack())) === true && !(await ev(() => document.getElementById('sdbNewModal').classList.contains('show'))));
  await ev(() => { sdbOpenMenu(); }); await sleep(100);
  check('   and the tools sheet', (await ev(() => handleAndroidBack())) === true && !(await ev(() => document.getElementById('sdbMenuModal').classList.contains('show'))));
  await ev(() => sdbResetView());
  await ev(() => { switchView('files'); switchView('settings'); }); await sleep(100);
  check('   then it goes back to the tab you came from', (await ev(() => handleAndroidBack())) === true && (await ev(() => currentViewName())) === 'files');

  // 5) a change in flight
  await ev(() => { switchView('settings'); window.__delay = 900; }); await sleep(200);
  await find('always_finish');
  const bef = await opCount();
  await page.locator('.sdb-row[data-k="always_finish_activities"] .sdb-sw').click(); await sleep(120);
  check('5. while a change is on its way the row is dimmed and Back warns it would be cut off', (await ev(() => document.querySelector('.sdb-row[data-k="always_finish_activities"]').classList.contains('busy'))) && /settings change/.test(await ev(() => backBusyReason())), await ev(() => backBusyReason()));
  await ev(() => sdbToggle('global', 'always_finish_activities')); await sleep(60);
  check('   a second flip of the same setting is refused until the first is done', (await opCount()) === bef + 1);
  await sleep(1100);
  check('   afterwards it is free again', (await ev(() => backBusyReason())) === '' && !(await ev(() => document.querySelector('.sdb-row[data-k="always_finish_activities"]').classList.contains('busy'))));
  await ev(() => { window.__delay = 20; sdbSnackHide(); });

  // 6) the tools sheet
  await ev(() => sdbResetView()); await sleep(100);
  await page.locator('#sdbFilters .filter-chip[data-f="known"]').click(); await sleep(150);
  const nShown = await ev(() => sdbFiltered('global').length);
  await page.locator('#view-settings .sdb-iconbtn:not(.accent)').click(); await sleep(120);
  check('6. the tools sheet names the table and how many are shown', new RegExp('Global · ' + nShown + ' shown').test(await ev(() => document.getElementById('sdbMenuSub').innerText)));
  await page.locator('#sdbMenuModal .batch-grid-btn', { hasText: 'Copy shown' }).click(); await sleep(100);
  const copied = await ev(() => window.__copied);
  check('   Copy shown copies name=value lines of what is on screen', copied.split('\n').length >= nShown && /^adb_enabled=1$/m.test(copied) && !/sample_global/.test(copied), copied.split('\n').length + ' lines');
  await page.locator('#view-settings .sdb-iconbtn:not(.accent)').click(); await sleep(120);
  await page.locator('#sdbMenuModal .batch-grid-btn', { hasText: 'Share shown' }).click(); await sleep(100);
  check('   Share shown hands the same text to the share sheet', (await ev(() => window.__shared.subject)) === 'Android global settings' && (await ev(() => window.__shared.text)) === copied);
  await page.locator('#view-settings .sdb-iconbtn:not(.accent)').click(); await sleep(120);
  await page.locator('#sdbSortSeg button', { hasText: 'Name Z–A' }).click(); await sleep(150);
  check('   the sort is chosen there', (await ev(() => sdbSort)) === 'za' && (await ev(() => document.querySelector('#sdbSortSeg button.active').innerText)) === 'Name Z–A');
  await ev(() => sdbSetSort('az')); await closeAll();
  const nl = await ev(() => window.__calls.list.length);
  await ev(() => sdbOpenMenu()); await page.locator('#sdbMenuModal .batch-grid-btn', { hasText: 'Reload all' }).click(); await sleep(400);
  check('   Reload all reads the three tables again, the shown one first', (await ev(n => window.__calls.list.length === n + 3 && window.__calls.list[n] === 'global', nl)));
  await page.locator('#sdbFilters .filter-chip[data-f="all"]').click();

  // 7) delete and create of a risky setting also ask
  await find('device_provisioned');
  await page.locator('.sdb-row[data-k="device_provisioned"]').click(); await sleep(100);
  check('7. a risky setting shows its warning in the editor', /leave this at 1/.test(await ev(() => document.getElementById('sdbEditSub').innerText)) && (await ev(() => document.getElementById('sdbEditRisk').style.display)) !== 'none');
  dialogs.length = 0; answer = false;
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Delete' }).click(); await sleep(150);
  check('   Delete asks about the risk first', dialogs.length === 1 && /Delete device_provisioned/.test(dialogs[0]) && /break setup/.test(dialogs[0]), JSON.stringify(dialogs[0]));
  await closeAll(); answer = true; dialogs.length = 0;
  await ev(() => sdbOpenNew('secure')); await sleep(80);
  await page.locator('#sdbNewKey').fill('user_setup_complete'); await page.locator('#sdbNewValue').fill('0'); answer = false;
  await page.locator('#sdbNewCreateBtn').click(); await sleep(200);
  check('   so does replacing one (first "already exists", then the risk)', dialogs.length >= 1 && /already exists/.test(dialogs[0]));
  await closeAll(); answer = true; dialogs.length = 0;

  // 8) the working mode goes away while the tab is open
  await ev(() => { window.__mode.priv = false; checkAllWorkingModes(false); }); await sleep(200);
  check('8. losing the privileged mode shows the gate in place of the list', (await ev(() => document.getElementById('sdbGate').style.display)) === 'flex' && (await ev(() => document.querySelectorAll('.sdb-row').length)) === 0);
  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); }); await sleep(500);
  check('   and getting it back brings the list back', (await ev(() => document.getElementById('sdbGate').style.display)) === 'none' && (await ev(() => document.querySelectorAll('.sdb-row').length)) > 0);

  // 9) a table that can't be read says why and can be retried
  await ev(() => { sdbResetView(); sdbData.secure = null; window.AndroidBridge.settingsList = (req, ns) => { setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: false, error: 'java.lang.SecurityException: not allowed', advice: 'Android refused the change.', mode: 'adb_tcp' })), 20); return 'started'; }; sdbSetNs('secure'); }); await sleep(300);
  const err9 = await ev(() => ({ box: document.querySelector('.sdb-err') ? document.querySelector('.sdb-err').innerText : '', tab: document.querySelector('.sdb-tab.active').innerText.replace(/\s+/g, ' ').trim() }));
  check('9. a failed read shows the reason, the advice and a retry', /Could not read the secure settings/.test(err9.box) && /SecurityException/.test(err9.box) && /Try again/.test(err9.box) && /!/.test(err9.tab), JSON.stringify(err9));
  await ev(() => { window.AndroidBridge.settingsList = function (req, ns) { setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: true, entries: [['recovered', '1']], mode: 'adb_tcp' })), 20); return 'started'; }; });
  await page.locator('.sdb-err .mode-action-btn', { hasText: 'Try again' }).click(); await sleep(250);
  check('   Try again reads it', (await hasRow('recovered')));

  await b.close();
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
})();
