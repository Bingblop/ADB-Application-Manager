// v5.9 Settings tab: placement, loading, sub-tabs, search / filters / sort, long-press and switch toggles, editor, create, delete, undo,
// failures, journal, Back, risky-key confirmations, escaping.
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
    // fixture rows that must be shown as text, never as markup
    const prev = window.__db;
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const rowText = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? r.innerText : null; }, key);
  const hasRow = key => ev(k => Array.from(document.querySelectorAll('.sdb-row')).some(x => x.dataset.k === k), key);
  const switchOn = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); const s = r && r.querySelector('.sdb-sw'); return s ? s.classList.contains('on') : null; }, key);
  const find = async key => { await ev(() => sdbResetView()); await ev(k => { const i = document.getElementById('sdbSearch'); i.value = k; sdbSearchInput(); }, key); await sleep(260); };
  const hold = async (key, ms = 650) => {
    const box = await page.locator('.sdb-row[data-k="' + key + '"]').first().boundingBox();
    await page.mouse.move(box.x + 24, box.y + box.height / 2); await page.mouse.down(); await sleep(ms); await page.mouse.up(); await sleep(120);
  };
  const lastOp = () => ev(() => window.__calls.op[window.__calls.op.length - 1]);
  const opCount = () => ev(() => window.__calls.op.length);
  const putCount = () => ev(() => window.__calls.op.filter(o => o.op !== 'get').length);          // changes only: opening the editor reads the real value
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // 1) placement
  const tabs = await ev(() => Array.from(document.querySelectorAll('.tab-btn')).map(b => b.innerText.replace(/\s+/g, ' ').trim()));
  const iTerm = tabs.findIndex(t => /ADB Console/.test(t)), iSet = tabs.findIndex(t => /Hidden Settings/.test(t));
  check('1. the Hidden Settings tab sits right after ADB Console (RRO/Monet Customization, added in v6.0, follows it)', iSet === iTerm + 1 && /RRO\/Monet/.test(tabs[iSet + 1]), JSON.stringify(tabs));
  await ev(() => switchView('settings')); await sleep(80);
  check('   it activates its own button and view', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'Hidden Settings' && (await ev(() => currentViewName())) === 'settings');
  await ev(() => switchView('store')); await sleep(60);
  check('   and the neighbours still work (App Stores, About)', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'App Stores');
  await ev(() => switchView('about')); await sleep(60);
  check('   About keeps its button', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'About');

  // 2) without a privileged mode there is a gate and nothing is read
  await ev(() => { window.__calls.list.length = 0; for (const n of ['global', 'secure', 'system']) sdbData[n] = null; window.__mode.priv = false; checkAllWorkingModes(false); switchView('settings'); }); await sleep(200);
  const gate = await ev(() => ({ gate: document.getElementById('sdbGate').style.display, controls: document.getElementById('sdbControls').style.display, rows: document.querySelectorAll('.sdb-row').length, listed: window.__calls.list.length }));
  check('2. no privileged mode: the gate shows, the controls hide, nothing is read', gate.gate === 'flex' && gate.controls === 'none' && gate.rows === 0 && gate.listed === 0, JSON.stringify(gate));
  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); }); await sleep(500);
  const after = await ev(() => ({ gate: document.getElementById('sdbGate').style.display, rows: document.querySelectorAll('.sdb-row').length, listed: window.__calls.list.slice() }));
  check('   connecting a mode while the tab is open opens it and reads the tables, the shown one first', after.gate === 'none' && after.rows === 120 && after.listed[0] === 'global' && after.listed.length === 3, JSON.stringify(after.listed));

  // 3) the tables, counts, paging
  const counts = await ev(() => Array.from(document.querySelectorAll('#sdbTabs .sdb-tab')).map(t => t.innerText.replace(/\s+/g, ' ').trim()));
  const expectCounts = await ev(() => ['global', 'secure', 'system'].map(n => Object.keys(window.__db[n]).length));
  check('3. each table shows its count', counts.join('|') === ['Global ' + expectCounts[0], 'Secure ' + expectCounts[1], 'System ' + expectCounts[2]].join('|'), JSON.stringify(counts));
  check('   the first page is 120 rows, sorted A-Z', (await ev(() => { const k = Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k); return k.length === 120 && k.every((x, i) => i === 0 || k[i - 1] <= x); })));
  // The button is also what loads the next page where there is no IntersectionObserver. With one, the page loads it by itself as soon as the button
  // is near the screen, and Playwright scrolls the button into view before it clicks, so a click here would race that (and sometimes load two
  // pages). So the button is tried without the observer, and the automatic loading on its own.
  await page.waitForFunction(() => !sdbLoading.global && !sdbLoading.secure && !sdbLoading.system);
  await ev(() => { window.__IO = window.IntersectionObserver; window.IntersectionObserver = undefined; sdbRender(); });
  const more = await page.locator('#sdbMoreBtn').innerText();
  check('   a "Show more" button says how many are left', /Show 120 more \(\d+ left\)/.test(more), more);
  await page.locator('#sdbMoreBtn').click();
  check('   it adds the next page', (await ev(() => document.querySelectorAll('.sdb-row').length)) === 240);
  await ev(() => { window.IntersectionObserver = window.__IO; sdbRender(); window.scrollTo(0, document.body.scrollHeight); });
  await page.waitForFunction(() => document.querySelectorAll('.sdb-row').length >= 360, null, { timeout: 5000 }).catch(() => {});
  check('   scrolling to the end adds more by itself', (await ev(() => document.querySelectorAll('.sdb-row').length)) >= 360, String(await ev(() => document.querySelectorAll('.sdb-row').length)));
  await ev(() => window.scrollTo(0, 0));

  // 4) sub-tabs
  await page.locator('.sdb-tab', { hasText: 'Secure' }).click(); await sleep(120);
  check('4. the Secure tab shows the secure table', (await hasRow('accessibility_enabled')) && !(await hasRow('adb_enabled')) && (await ev(() => sdbNs)) === 'secure');
  check('   its button is the active one', (await ev(() => document.querySelector('.sdb-tab.active').innerText.includes('Secure'))));
  check('   the table is remembered for next time', JSON.parse(await ev(() => window.AndroidBridge.__kv.sdb_ui)).ns === 'secure');
  await page.locator('.sdb-tab', { hasText: 'System' }).click(); await sleep(120);
  check('   System too', (await hasRow('screen_brightness')) && (await ev(() => sdbNs)) === 'system');
  await page.locator('.sdb-tab', { hasText: 'Global' }).click(); await sleep(120);

  // 5) search
  await ev(() => { const i = document.getElementById('sdbSearch'); i.value = 'adb'; sdbSearchInput(); }); await sleep(300);
  const s5 = await ev(() => ({ allHit: Array.from(document.querySelectorAll('.sdb-row')).every(r => /adb/i.test(r.innerText)), keys: Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k), marks: document.querySelectorAll('.sdb-row mark.find-hit').length, status: document.getElementById('sdbStatus').innerText, tab: document.querySelector('.sdb-tab.active').innerText.replace(/\s+/g, ' ').trim(), clear: document.getElementById('sdbSearchClear').style.display }));
  check('5. search narrows the list by name, with the matches marked', s5.keys.includes('adb_enabled') && s5.keys.includes('adb_wifi_enabled') && s5.marks >= 2 && s5.allHit, JSON.stringify(s5.keys.slice(0, 5)));
  check('   the status line and the table button show matches / total', /\d+ of \d+ global settings/.test(s5.status) && /Global \d+ \/ \d+/.test(s5.tab), s5.status + ' | ' + s5.tab);
  check('   a clear button appears', s5.clear === 'block');
  await ev(() => { const i = document.getElementById('sdbSearch'); i.value = 'opportunistic'; sdbSearchInput(); }); await sleep(300);
  check('   it also searches values', (await hasRow('private_dns_mode')) && (await ev(() => document.querySelectorAll('.sdb-row').length)) === 1);
  await ev(() => { const i = document.getElementById('sdbSearch'); i.value = 'usb debugging'; sdbSearchInput(); }); await sleep(300);
  check('   and the descriptions', (await hasRow('adb_enabled')));
  await ev(() => { const i = document.getElementById('sdbSearch'); i.value = 'ADB  Enabled'; sdbSearchInput(); }); await sleep(300);
  check('   several words must all match, in any case', (await hasRow('adb_enabled')) && (await hasRow('adb_wifi_enabled')) && !(await hasRow('device_name')) && (await ev(() => Array.from(document.querySelectorAll('.sdb-row')).every(r => /adb/i.test(r.innerText) && /enabled/i.test(r.innerText)))));
  await ev(() => { const i = document.getElementById('sdbSearch'); i.value = 'zzz_nothing_like_this'; sdbSearchInput(); }); await sleep(300);
  const empty = await ev(() => document.querySelector('.sdb-empty') ? document.querySelector('.sdb-empty').innerText : '');
  check('   no match: says so and offers to create that name', /Nothing matches/.test(empty) && /Create/.test(empty) && /Show everything/.test(empty), JSON.stringify(empty));
  await page.locator('.sdb-empty .mode-action-btn', { hasText: 'Create' }).click(); await sleep(150);
  check('   the offer opens the create sheet with the name filled in', (await ev(() => document.getElementById('sdbNewModal').classList.contains('show'))) && (await ev(() => document.getElementById('sdbNewKey').value)) === 'zzz_nothing_like_this');
  await closeAll();
  await page.locator('#sdbSearchClear').click(); await sleep(250);
  check('   clearing the search shows everything again', (await ev(() => sdbQuery)) === '' && (await ev(() => document.querySelectorAll('.sdb-row').length)) === 120);

  // 6) filters
  await page.locator('#sdbFilters .filter-chip[data-f="switch"]').click(); await sleep(200);
  check('6. "Switches" keeps only 1/0, true/false, on/off, yes/no values', await ev(() => Array.from(document.querySelectorAll('.sdb-row')).every(r => r.querySelector('.sdb-sw')) && document.querySelectorAll('.sdb-row').length > 5));
  await page.locator('#sdbFilters .filter-chip[data-f="known"]').click(); await sleep(200);
  check('   "Described" keeps the ones with a description', await ev(() => { const r = Array.from(document.querySelectorAll('.sdb-row')); return r.length > 10 && r.every(x => x.querySelector('.sdb-hint')); }));
  await page.locator('#sdbFilters .filter-chip[data-f="edited"]').click(); await sleep(200);
  check('   "Edited" is empty before anything was changed, and says so', /Nothing here was changed/.test(await ev(() => document.querySelector('.sdb-empty').innerText)));
  await page.locator('#sdbFilters .filter-chip[data-f="all"]').click(); await sleep(200);

  // 7) sort
  await ev(() => sdbSetSort('za')); await sleep(150);
  check('7. Name Z-A', await ev(() => { const k = Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k); return k.every((x, i) => i === 0 || k[i - 1] >= x); }));
  await ev(() => sdbSetSort('val')); await sleep(150);
  check('   by value', await ev(() => { const v = Array.from(document.querySelectorAll('.sdb-row')).map(r => { const e = r.querySelector('.sdb-val'); return e.classList.contains('empty') ? '' : e.innerText.toLowerCase(); }); return v.every((x, i) => i === 0 || v[i - 1] <= x); }));
  await ev(() => sdbSetSort('az')); await sleep(100);

  // 8) long press flips a switch
  await find('heads_up_notifications_enabled');
  check('8. a 1 shows a switch that is on', (await switchOn('heads_up_notifications_enabled')) === true);
  const n0 = await opCount();
  await hold('heads_up_notifications_enabled');
  await sleep(150);
  const op8 = await lastOp();
  check('   press and hold flips 1 to 0 with one put', (await opCount()) === n0 + 1 && op8.op === 'put' && op8.ns === 'global' && op8.key === 'heads_up_notifications_enabled' && op8.value === '0', JSON.stringify(op8));
  check('   the row shows the new state (read back)', (await switchOn('heads_up_notifications_enabled')) === false && /\n0\s*$/.test(await rowText('heads_up_notifications_enabled')), JSON.stringify(await rowText('heads_up_notifications_enabled')));
  check('   an undo bar names the change', /heads_up_notifications_enabled: 1 → 0/.test(await snack()), await snack());
  await page.locator('#sdbSnackUndo').click(); await sleep(200);
  check('   Undo puts the old value back', (await lastOp()).value === '1' && (await switchOn('heads_up_notifications_enabled')) === true);
  check('   a short press does not flip (it opens the editor)', await (async () => { const n = await putCount(); const box = await page.locator('.sdb-row[data-k="heads_up_notifications_enabled"]').first().boundingBox(); await page.mouse.click(box.x + 24, box.y + box.height / 2); await sleep(150); return (await putCount()) === n && (await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))); })());
  await closeAll();
  await find('some_');
  for (const [key, from, to] of [['some_flag_true', 'true', 'false'], ['some_flag_False', 'False', 'True'], ['some_flag_UPPER', 'ON', 'OFF'], ['some_yes', 'yes', 'no']]) {
    await hold(key); await sleep(150);
    const o = await lastOp();
    check('   ' + from + ' flips to ' + to + ' (letter case kept)', o.key === key && o.value === to, JSON.stringify(o));
  }
  await find('device_name');
  const n8 = await opCount();
  await hold('device_name'); await sleep(150);
  check('   a value that is not a switch is left alone, with a hint', (await opCount()) === n8 && /Not a switch/.test(await toast()), await toast());
  await find('multi_line');
  const n8b = await opCount(); await hold('multi_line'); await sleep(120);
  check('   so is a multi-line value', (await opCount()) === n8b);

  // 9) tapping the switch itself flips; the rest of the row edits
  await find('bluetooth_on');
  const n9 = await opCount();
  await page.locator('.sdb-row[data-k="bluetooth_on"] .sdb-sw').click(); await sleep(200);
  check('9. a tap on the switch flips it', (await opCount()) === n9 + 1 && (await lastOp()).value === '0' && !(await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))));
  await page.locator('#sdbSnackUndo').click(); await sleep(150);

  // 10) risky settings ask first
  await find('adb_enabled');
  dialogs.length = 0; answer = false;
  const n10 = await opCount(); await hold('adb_enabled'); await sleep(150);
  check('10. a risky switch asks first, naming what it does', dialogs.length === 1 && /USB debugging/.test(dialogs[0]) && /cut the ADB connection/.test(dialogs[0]), JSON.stringify(dialogs));
  check('    declining changes nothing', (await opCount()) === n10);
  answer = true; dialogs.length = 0;
  await hold('adb_enabled'); await sleep(200);
  check('    accepting goes ahead', (await opCount()) === n10 + 1 && (await lastOp()).value === '0');
  await page.locator('#sdbSnackUndo').click(); await sleep(150);
  dialogs.length = 0;

  // 11) the editor
  await find('screen_brightness');
  await ev(() => sdbSetNs('system')); await sleep(200); await find('screen_brightness');
  await page.locator('.sdb-row[data-k="screen_brightness"]').click(); await sleep(150);
  const ed = await ev(() => ({ key: document.getElementById('sdbEditKey').innerText, sub: document.getElementById('sdbEditSub').innerText, val: document.getElementById('sdbEditText').value, flip: document.getElementById('sdbEditFlipBtn').style.display, note: document.getElementById('sdbEditNote').innerText, chips: document.querySelectorAll('#sdbEditQuick button').length }));
  check('11. tapping a row opens the editor with its name, table, description and value', ed.key === 'screen_brightness' && /System · Brightness/.test(ed.sub) && ed.val === '128', JSON.stringify(ed));
  check('    it hides Flip for a number, names its kind, and offers quick values', ed.flip === 'none' && /whole number/.test(ed.note) && ed.chips === 7, JSON.stringify(ed.note));
  await page.locator('#sdbEditText').fill('200'); await sleep(60);
  check('    changing the text notes what it was', /was 128/.test(await ev(() => document.getElementById('sdbEditNote').innerText)));
  await page.locator('#sdbEditSaveBtn').click(); await sleep(250);
  const o11 = await lastOp();
  check('    Save puts the new value and closes the sheet', o11.op === 'put' && o11.ns === 'system' && o11.key === 'screen_brightness' && o11.value === '200' && !(await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))), JSON.stringify(o11));
  check('    the list shows what Android now holds', /200/.test(await rowText('screen_brightness')));
  check('    the undo bar says Saved', /Saved screen_brightness/.test(await snack()));
  await page.locator('#sdbSnackUndo').click(); await sleep(200);
  check('    Undo restores 128', (await lastOp()).value === '128' && /128/.test(await rowText('screen_brightness')));
  // unchanged
  await page.locator('.sdb-row[data-k="screen_brightness"]').click(); await sleep(120);
  const n11 = await opCount();
  await page.locator('#sdbEditSaveBtn').click(); await sleep(150);
  check('    saving without a change sends nothing', (await opCount()) === n11 && /No change/.test(await toast()));
  // invalid value
  await page.locator('.sdb-row[data-k="screen_brightness"]').click(); await sleep(120);
  await ev(() => { const t = document.getElementById('sdbEditText'); t.value = 'a\u0007b'; sdbEditInput(); });
  check('    a value with a control character is refused on the spot', (await ev(() => document.getElementById('sdbEditSaveBtn').disabled)) && /control characters/.test(await ev(() => document.getElementById('sdbEditProblem').innerText)));
  await ev(() => { const t = document.getElementById('sdbEditText'); t.value = 'x'.repeat(20001); sdbEditInput(); });
  check('    and one that is too long', /too long/.test(await ev(() => document.getElementById('sdbEditProblem').innerText)));
  await page.locator('#sdbEditQuick button[data-v="true"]').click(); await sleep(60);
  check('    a quick value fills the box', (await ev(() => document.getElementById('sdbEditText').value)) === 'true');
  await page.locator('#sdbEditQuick button', { hasText: '(empty)' }).click(); await sleep(60);
  check('    (empty) empties it', (await ev(() => document.getElementById('sdbEditText').value)) === '');
  await closeAll();
  // copy
  await ev(() => sdbSetNs('global')); await sleep(150); await find('device_name');
  await page.locator('.sdb-row[data-k="device_name"]').click(); await sleep(120);
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Command' }).click(); await sleep(60);
  check('    Copy command gives a shell line with every quote escaped', (await ev(() => window.__copied)) === "settings put global 'device_name' 'Sam'\\''s phone'", await ev(() => window.__copied));
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Name' }).click(); await sleep(40);
  check('    Copy name', (await ev(() => window.__copied)) === 'device_name');
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Value' }).click(); await sleep(40);
  check('    Copy value', (await ev(() => window.__copied)) === "Sam's phone");
  // reload picks up a change made elsewhere
  await ev(() => { window.__db.global.device_name = 'Changed elsewhere'; });
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Reload' }).click(); await sleep(200);
  check('    Reload reads the value again', (await ev(() => document.getElementById('sdbEditText').value)) === 'Changed elsewhere');
  await closeAll();

  // 12) delete
  await find('empty_one');
  await page.locator('.sdb-row[data-k="empty_one"]').click(); await sleep(120);
  check('12. an empty value opens as an empty box and says so', (await ev(() => document.getElementById('sdbEditText').value)) === '' && /empty/.test(await ev(() => document.getElementById('sdbEditNote').innerText)));
  dialogs.length = 0; answer = false;
  const n12 = await opCount();
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Delete' }).click(); await sleep(150);
  check('    Delete asks first; declining deletes nothing', dialogs.length === 1 && /Delete empty_one/.test(dialogs[0]) && (await opCount()) === n12 && (await hasRow('empty_one')));
  answer = true;
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Delete' }).click(); await sleep(250);
  check('    accepting deletes it and it leaves the list', (await lastOp()).op === 'delete' && !(await hasRow('empty_one')) && !(await ev(() => 'empty_one' in window.__db.global)));
  check('    the undo bar offers to bring it back', /Deleted empty_one/.test(await snack()));
  await page.locator('#sdbSnackUndo').click(); await sleep(250);
  check('    Undo puts it back with its (empty) value', (await ev(() => window.__db.global.empty_one)) === '' && (await hasRow('empty_one')));

  // 13) create
  await ev(() => sdbResetView()); await sleep(100);
  await page.locator('#view-settings .sdb-iconbtn.accent').click(); await sleep(150);
  check('13. the plus button opens the create sheet on the table being shown', (await ev(() => document.getElementById('sdbNewModal').classList.contains('show'))) && (await ev(() => document.querySelector('#sdbNewSeg button.active').innerText)) === 'Global');
  check('    it cannot be created empty-named', await ev(() => document.getElementById('sdbNewCreateBtn').disabled));
  await page.locator('#sdbNewKey').fill('bad name'); await sleep(60);
  check('    a name with a space is refused on the spot', /spaces/.test(await ev(() => document.getElementById('sdbNewKeyProblem').innerText)) && (await ev(() => document.getElementById('sdbNewCreateBtn').disabled)));
  await page.locator('#sdbNewKey').fill('--user'); await sleep(60);
  check('    so is a name that starts with a dash', /dash/.test(await ev(() => document.getElementById('sdbNewKeyProblem').innerText)));
  await page.locator('#sdbNewKey').fill('my_new_setting'); await page.locator('#sdbNewValue').fill('hello world'); await sleep(60);
  await page.locator('#sdbNewSeg button', { hasText: 'Secure' }).click(); await sleep(60);
  check('    the table can be picked', (await ev(() => document.querySelector('#sdbNewSeg button.active').innerText)) === 'Secure');
  await page.locator('#sdbNewCreateBtn').click(); await sleep(300);
  const o13 = await lastOp();
  check('    Create puts it into that table', o13.op === 'put' && o13.ns === 'secure' && o13.key === 'my_new_setting' && o13.value === 'hello world', JSON.stringify(o13));
  check('    the list moves to that table and shows it', (await ev(() => sdbNs)) === 'secure' && (await hasRow('my_new_setting')) && (await ev(() => document.getElementById('sdbSearch').value)) === 'my_new_setting');
  check('    the undo bar says Created', /Created my_new_setting/.test(await snack()));
  await page.locator('#sdbSnackUndo').click(); await sleep(250);
  check('    Undo removes it again', !(await ev(() => 'my_new_setting' in window.__db.secure)) && !(await hasRow('my_new_setting')));
  // replacing an existing name
  await ev(() => sdbOpenNew('global')); await sleep(80);
  await page.locator('#sdbNewKey').fill('device_name'); await sleep(60);
  check('    an existing name is called out and the button says Replace', /already exists/.test(await ev(() => document.getElementById('sdbNewNote').innerText)) && /Replace/.test(await ev(() => document.getElementById('sdbNewCreateBtn').innerText)));
  await page.locator('#sdbNewValue').fill('Replaced'); dialogs.length = 0; answer = false;
  await page.locator('#sdbNewCreateBtn').click(); await sleep(150);
  check('    replacing asks first', dialogs.length === 1 && /already exists/.test(dialogs[0]) && (await ev(() => window.__db.global.device_name)) === 'Changed elsewhere');
  answer = true;
  await page.locator('#sdbNewCreateBtn').click(); await sleep(300);
  check('    and then does it', (await ev(() => window.__db.global.device_name)) === 'Replaced');
  await ev(() => { sdbSnackHide(); });

  // 14) failures
  await ev(() => { sdbResetView(); sdbSetNs('secure'); }); await sleep(150); await find('ui_night_mode');
  await ev(() => { window.__deny['secure/ui_night_mode'] = 'java.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS'; });
  await page.locator('.sdb-row[data-k="ui_night_mode"]').click(); await sleep(120);
  await page.locator('#sdbEditText').fill('1');
  const j0 = await ev(() => sdbJournal.length);
  await page.locator('#sdbEditSaveBtn').click(); await sleep(300);
  const res14 = await ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), text: document.getElementById('commandResultsList').innerText, row: Array.from(document.querySelectorAll('.sdb-row')).find(r => r.dataset.k === 'ui_night_mode').innerText }));
  check('14. a refused change says so, with Android\'s words and what to try', res14.shown && /SecurityException/.test(res14.text) && /Security settings/.test(res14.text), JSON.stringify(res14.text.slice(0, 160)));
  check('    the list still shows the old value and nothing is recorded as changed', /\b2\b/.test(res14.row) && (await ev(() => sdbJournal.length)) === j0);
  await closeAll();
  await ev(() => { delete window.__deny['secure/ui_night_mode']; window.__revert['secure/ui_night_mode'] = '2'; });
  await page.locator('.sdb-row[data-k="ui_night_mode"]').click(); await sleep(120);
  await page.locator('#sdbEditText').fill('1'); await page.locator('#sdbEditSaveBtn').click(); await sleep(300);
  check('    a change that Android quietly puts back is reported as not kept', /did not keep/.test(await ev(() => document.getElementById('commandResultsList').innerText)));
  await closeAll();
  await ev(() => { delete window.__revert['secure/ui_night_mode']; });
  // the bridge refusing outright
  await ev(() => { window.AndroidBridge.settingsOp = () => 'error: A name can\'t contain spaces or control characters'; });
  await ev(() => sdbPut('secure', 'ui_night_mode', '1'));
  check('    a request the app refuses before sending is reported too', /Could not change/.test(await toast()) && /can't contain/.test(await ev(() => document.getElementById('commandResultsList').innerText)));
  await closeAll();
  // a request that never gets an answer is given up on
  const to = await ev(() => new Promise(res => sdbRequest(() => 'started', r => res(r), 60)));
  check('    a request nobody answers times out with a message', to.ok === false && to.timedOut === true && /No answer/.test(to.error), JSON.stringify(to));
  // an answer that arrives after the page gave up is ignored
  const late = await ev(() => { let called = 0; const id = sdbReqId + 1; sdbRequest(() => 'started', () => { called++; }, 30); return new Promise(res => setTimeout(() => { window.onSettingsOp(JSON.stringify({ req: id, ok: true })); res(called); }, 120)); });
  check('    and a late answer does not run it twice', late === 1, String(late));

  await b.close();
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
})();
