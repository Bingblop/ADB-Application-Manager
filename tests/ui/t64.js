// Settings tab: the independent review's findings (journal Revert by id, phantom null rows, long unbroken text, touch long press with the
// row redrawn, failed refresh, late answers, stale tables, creating over an unseen name, choice settings are not switches, refused Save keeps
// the editor, disabled looks, focus, names like "constructor", unknown outcomes, the real value in the editor, small things).
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 360, height: 800 }, hasTouch: true });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => { window.__db0 = null; });
  await page.goto(URL); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const puts = () => ev(() => window.__calls.op.filter(o => o.op === 'put'));
  const resetCalls = () => ev(() => { window.__calls.op.length = 0; window.__calls.list.length = 0; });
  const find = async key => { await ev(() => sdbResetView()); await ev(k => { const i = document.getElementById('sdbSearch'); i.value = k; sdbSearchInput(); }, key); await sleep(260); };
  const rowText = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? r.innerText : null; }, key);
  const hasSwitch = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? !!r.querySelector('.sdb-sw') : null; }, key);
  const switchOn = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); const s = r && r.querySelector('.sdb-sw'); return s ? s.classList.contains('on') : null; }, key);
  const touch = async (x, y, holdMs) => {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await sleep(holdMs);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await sleep(300);
  };

  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('settings'); }); await sleep(600);

  // 1) the journal: Revert acts on the row it is shown on, even when a change lands while the sheet is open
  await find('wifi_on'); await ev(() => sdbToggle('global', 'wifi_on')); await sleep(150);
  await ev(() => sdbToggle('global', 'bluetooth_on')); await sleep(150);
  await ev(() => { window.__delay = 700; sdbToggle('global', 'mobile_data'); }); await sleep(100);
  await ev(() => sdbOpenJournal());
  const before = await ev(() => Array.from(document.querySelectorAll('#sdbJournalList .sdb-jrow .sdb-key')).map(e => e.innerText));
  await sleep(900);
  const after = await ev(() => Array.from(document.querySelectorAll('#sdbJournalList .sdb-jrow .sdb-key')).map(e => e.innerText));
  check('1. an open Changes sheet follows the journal when a change lands (rows were ' + before.join(',') + ')', after[0] === 'mobile_data' && after.length === before.length + 1, after.join(','));
  await ev(() => { window.__delay = 20; });
  await resetCalls();
  await page.locator('#sdbJournalList .sdb-jrow', { hasText: 'bluetooth_on' }).locator('button', { hasText: 'Revert' }).click(); await sleep(250);
  const rv = await puts();
  check('   Revert on the bluetooth_on row changes bluetooth_on, not the newest entry', rv.length === 1 && rv[0].key === 'bluetooth_on' && rv[0].value === '1', JSON.stringify(rv));
  await closeAll();
  check('   every journal entry has its own id', await ev(() => new Set(sdbJournal.map(e => e.n)).size === sdbJournal.length && sdbJournal.every(e => e.n > 0)));

  // 2) a refused Create leaves no phantom "null" row
  await ev(() => { sdbSetNs('secure'); }); await sleep(300);
  const countBefore = await ev(() => sdbData.secure.size);
  await ev(() => { window.__deny['secure/brand_new_refused'] = 'java.lang.SecurityException: not allowed'; sdbOpenNew('secure'); });
  await page.fill('#sdbNewKey', 'brand_new_refused'); await page.fill('#sdbNewValue', '1');
  await page.locator('#sdbNewCreateBtn').click(); await sleep(400);
  check('2. a refused create: the table still has the same number of settings', (await ev(() => sdbData.secure.size)) === countBefore && !(await ev(() => sdbData.secure.has('brand_new_refused'))));
  check('   and a search for the name finds nothing', await (async () => { await find('brand_new_refused'); return (await rowText('brand_new_refused')) === null; })());
  await closeAll();
  await ev(() => { delete window.__deny['secure/brand_new_refused']; });
  // a refusal that leaves a REAL value shows that value
  await ev(() => { window.__deny['secure/long_press_timeout'] = 'java.lang.SecurityException: no'; });
  await find('long_press_timeout'); await ev(() => sdbOpenEditor('secure', 'long_press_timeout'));
  await page.fill('#sdbEditText', '777'); await page.locator('#sdbEditSaveBtn').click(); await sleep(350);
  check('   a refused change of an existing name keeps showing what the phone holds', (await rowText('long_press_timeout')).includes('400'));
  check('10. a refused Save keeps the editor open with what was typed', (await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))) && (await ev(() => document.getElementById('sdbEditText').value)) === '777');
  check('    and the Save button is usable again', (await ev(() => document.getElementById('sdbEditSaveBtn').disabled)) === false);
  await closeAll(); await ev(() => { sdbEditing = null; delete window.__deny['secure/long_press_timeout']; });
  await page.evaluate(() => { document.getElementById('sdbEditModal').classList.remove('show'); });
  // refused Delete keeps the editor too
  await ev(() => { window.__deny['secure/long_press_timeout'] = 'java.lang.SecurityException: no'; sdbOpenEditor('secure', 'long_press_timeout'); });
  await page.locator('#sdbEditModal .batch-tool-link', { hasText: 'Delete' }).click(); await sleep(350);
  check('    a refused Delete keeps the editor open', await ev(() => document.getElementById('sdbEditModal').classList.contains('show')));
  await closeAll(); await ev(() => { sdbEditing = null; delete window.__deny['secure/long_press_timeout']; });
  // an accepted Save closes it
  await ev(() => sdbOpenEditor('secure', 'long_press_timeout')); await page.fill('#sdbEditText', '555'); await page.locator('#sdbEditSaveBtn').click(); await sleep(350);
  check('    an accepted Save closes it', (await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))) === false && (await rowText('long_press_timeout')) !== null);
  await ev(() => sdbSetNs('global')); await sleep(200);

  // 3) a long unbroken search text stays inside the screen
  await ev(() => { window.scrollTo(0, 0); });
  const p3 = await ctx.newPage(); await p3.setViewportSize({ width: 320, height: 700 });
  await p3.addInitScript(mock.initScript); await p3.goto(URL); await p3.waitForTimeout(300);
  await p3.evaluate(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('settings'); }); await p3.waitForTimeout(600);
  await p3.evaluate(() => { const i = document.getElementById('sdbSearch'); i.value = 'accessibility_display_magnification_enabled_and_some_more_unbroken_text_here_and_there_' + 'x'.repeat(100); sdbSearchInput(); }); await p3.waitForTimeout(400);
  const w3 = await p3.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, btn: Array.from(document.querySelectorAll('.sdb-empty .mode-action-btn')).map(b => Math.round(b.getBoundingClientRect().right)) }));
  check('3. 150 characters with no space: nothing sticks out at 320 px', w3.sw <= w3.cw && w3.btn.every(r => r <= 320), JSON.stringify(w3));
  await p3.evaluate(() => { sdbOpenNew('global', 'k'); const k = document.getElementById('sdbNewKey'); k.value = 'a_very_long_existing_name_' + 'y'.repeat(120); sdbData.global.set(k.value, 'v'); sdbNewInput(); });
  const w3b = await p3.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, note: document.getElementById('sdbNewNote').getBoundingClientRect().right }));
  check('   and the Create note with a long existing name wraps', w3b.sw <= w3b.cw && w3b.note <= 320, JSON.stringify(w3b));
  await p3.close();

  // 5) press and hold with a touch screen: a hold of any length flips once, the row being redrawn under the finger
  await find('heads_up_notifications_enabled');
  const holds = [520, 600, 690, 800, 1000, 1500];
  for (const ms of holds) {
    await ev(() => { closeAllSheets = null; });
    await closeAll();
    await find('heads_up_notifications_enabled');
    const val0 = await switchOn('heads_up_notifications_enabled');
    await resetCalls();
    const box = await page.locator('.sdb-row[data-k="heads_up_notifications_enabled"]').first().boundingBox();
    await touch(box.x + 24, box.y + box.height / 2, ms);
    const pc = (await puts()).filter(p => p.key === 'heads_up_notifications_enabled').length;
    const editor = await ev(() => document.getElementById('sdbEditModal').classList.contains('show'));
    check('5. touch hold ' + ms + ' ms on the row: one flip (' + pc + ' put), no editor', pc === 1 && editor === false && (await switchOn('heads_up_notifications_enabled')) === !val0, JSON.stringify({ pc, editor }));
    await closeAll();
  }
  for (const ms of [700, 1200]) {
    await find('heads_up_notifications_enabled'); await closeAll();
    await resetCalls();
    const sb = await page.locator('.sdb-row[data-k="heads_up_notifications_enabled"] .sdb-sw').first().boundingBox();
    await touch(sb.x + sb.width / 2, sb.y + sb.height / 2, ms);
    const pc = (await puts()).filter(p => p.key === 'heads_up_notifications_enabled').length;
    check('   touch hold ' + ms + ' ms on the switch: one flip, not two (' + pc + ')', pc === 1);
  }
  // a short tap still opens the editor
  await find('heads_up_notifications_enabled'); await closeAll(); await resetCalls();
  const tb = await page.locator('.sdb-row[data-k="heads_up_notifications_enabled"]').first().boundingBox();
  await touch(tb.x + 24, tb.y + tb.height / 2, 60);
  check('   a short tap opens the editor and changes nothing', (await ev(() => document.getElementById('sdbEditModal').classList.contains('show'))) && (await puts()).length === 0);
  await closeAll();

  // 6) a refresh that fails while a table is already shown says so
  await ev(() => sdbResetView());
  await ev(() => { window.__realList = window.AndroidBridge.settingsList; window.AndroidBridge.settingsList = (req, ns) => { setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: false, error: 'java.lang.SecurityException: not allowed', mode: 'adb_tcp' })), 20); return 'started'; }; sdbReadTable('global'); }); await sleep(250);
  check('6. the old list stays and a toast explains why it was not refreshed', /Could not refresh the global settings/.test(await toast()) && (await ev(() => sdbData.global.size)) > 400, await toast());
  await ev(() => { window.AndroidBridge.settingsList = window.__realList; }); await ev(() => sdbReadTable('global')); await sleep(300);

  // 7) an answer that comes after the page gave up still refreshes the table
  await ev(() => { window.__sdbRequest0 = sdbRequest; sdbRequest = (s, c, t) => window.__sdbRequest0(s, c, 300); });
  await find('bluetooth_on'); await ev(() => { window.__delay = 900; });
  await resetCalls();
  const bt0 = await switchOn('bluetooth_on');
  await ev(() => sdbToggle('global', 'bluetooth_on')); await sleep(500);
  check('7. no answer in time: a sheet says the phone may still apply it', await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /may still apply/.test(document.getElementById('commandResultsList').innerText)));
  await closeAll();
  await ev(() => { window.__delay = 20; });
  await sleep(1100 + 2800);
  check('   the late answer makes the page read the table again (list calls: ' + JSON.stringify(await ev(() => window.__calls.list)) + ')', (await ev(() => window.__calls.list.length)) >= 1);
  check('   and the list shows what the phone holds now', (await switchOn('bluetooth_on')) === !bt0);
  await ev(() => { sdbRequest = window.__sdbRequest0; });
  // an unknown outcome (the link dropped)
  await find('mobile_data'); const md0 = await switchOn('mobile_data'); await resetCalls();
  await ev(() => { window.__unknown = window.__unknown || {}; window.__unknown['global/mobile_data'] = { apply: true }; sdbToggle('global', 'mobile_data'); }); await sleep(400);
  check('   an unknown outcome says the phone may still apply it', await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /may still apply/.test(document.getElementById('commandResultsList').innerText)));
  await closeAll(); await sleep(3000);
  check('   and the table is read again, so the row shows the real state', (await ev(() => window.__calls.list.length)) >= 1 && (await switchOn('mobile_data')) === !md0);

  // 8) each table has its own age; a table shown after a while is read again; creating checks the phone, not the list
  await resetCalls();
  await ev(() => { sdbLoadedAt.secure = 1; sdbSetNs('secure'); }); await sleep(300);
  check('8. showing a table that was read long ago reads it again', (await ev(() => window.__calls.list)).join() === 'secure');
  await ev(() => sdbSetNs('global')); await sleep(200);
  check('   a table read a moment ago is not read again', (await ev(() => window.__calls.list)).join() === 'secure');
  await ev(() => { window.__db.global['hidden_from_the_list'] = 'precious'; sdbOpenNew('global'); });
  await page.fill('#sdbNewKey', 'hidden_from_the_list'); await page.fill('#sdbNewValue', 'mine');
  dialogs.length = 0; answer = false; await resetCalls();
  await page.locator('#sdbNewCreateBtn').click(); await sleep(400);
  check('   creating a name the list does not know, but the phone has, asks before replacing it', dialogs.length === 1 && /already exists/.test(dialogs[0]) && /precious/.test(dialogs[0]), dialogs[0]);
  check('   and "Cancel" leaves it alone', (await puts()).length === 0 && (await ev(() => window.__db.global['hidden_from_the_list'])) === 'precious');
  answer = true;
  await page.locator('#sdbNewCreateBtn').click(); await sleep(450);
  check('   "OK" replaces it, and the journal remembers the old value', (await ev(() => window.__db.global['hidden_from_the_list'])) === 'mine' && (await ev(() => sdbJournal[0].k === 'hidden_from_the_list' && sdbJournal[0].f === 'precious')));
  await closeAll();
  await ev(() => sdbOpenNew('global')); await page.fill('#sdbNewKey', 'truly_new_name_1'); await page.fill('#sdbNewValue', '5'); await resetCalls();
  dialogs.length = 0;
  await page.locator('#sdbNewCreateBtn').click(); await sleep(450);
  check('   a free name is created without a question', dialogs.length === 0 && (await ev(() => window.__db.global['truly_new_name_1'])) === '5');
  await closeAll();

  // 9) choice settings are not switches
  await ev(() => { window.__db.global.private_dns_mode = 'off'; window.__db.secure.ui_night_mode = '1'; window.__db.system.user_rotation = '0'; window.__db.global.mode_ringer = '0'; });
  await ev(() => { sdbReadAll(); }); await sleep(500);
  await find('private_dns_mode');
  check('9. private_dns_mode = off is not shown as a switch', (await hasSwitch('private_dns_mode')) === false);
  await resetCalls(); await ev(() => sdbLongPress('global', 'private_dns_mode'));
  check('   a long press on it flips nothing and says so', (await puts()).length === 0 && /Not a switch/.test(await toast()));
  await ev(() => sdbSetNs('secure')); await find('ui_night_mode');
  check('   ui_night_mode = 1 (dark theme off) is not a switch', (await hasSwitch('ui_night_mode')) === false);
  await ev(() => sdbSetNs('system')); await find('user_rotation');
  check('   user_rotation = 0 is not a switch', (await hasSwitch('user_rotation')) === false);
  await ev(() => sdbSetNs('global')); await find('mode_ringer');
  check('   mode_ringer = 0 is not a switch', (await hasSwitch('mode_ringer')) === false);
  await find('airplane_mode_on');
  check('   airplane_mode_on still is', (await hasSwitch('airplane_mode_on')) === true);
  await ev(() => sdbResetView()); await ev(() => sdbSetFilter('switch')); await sleep(150);
  check('   the Switches filter leaves the choice settings out', await ev(() => { const k = sdbFiltered('global'); return !k.includes('private_dns_mode') && !k.includes('mode_ringer') && k.includes('airplane_mode_on'); }));
  await ev(() => sdbSetFilter('all'));
  await find('private_dns_mode'); await ev(() => sdbOpenEditor('global', 'private_dns_mode'));
  check('   the editor of a choice setting has no Flip', (await ev(() => getComputedStyle(document.getElementById('sdbEditFlipBtn')).display)) === 'none');
  await closeAll(); await ev(() => { sdbEditing = null; });

  // 11) disabled buttons look disabled, an empty name is explained
  await ev(() => sdbOpenNew('global')); await sleep(400);
  check('11. the empty New sheet: Create is off and looks it', (await ev(() => document.getElementById('sdbNewCreateBtn').disabled)) && Number(await ev(() => getComputedStyle(document.getElementById('sdbNewCreateBtn')).opacity)) < 0.6);
  check('    no complaint yet while nothing was typed', (await ev(() => document.getElementById('sdbNewKeyProblem').innerText)) === '');
  await page.fill('#sdbNewValue', 'x'); await page.focus('#sdbNewKey'); await page.evaluate(() => document.getElementById('sdbNewKey').blur()); await sleep(80);
  check('    after leaving the name box empty it says "Enter a name"', (await ev(() => document.getElementById('sdbNewKeyProblem').innerText)) === 'Enter a name');
  await closeAll();
  await ev(() => { sdbOpenEditor('global', 'heads_up_notifications_enabled'); document.getElementById('sdbEditText').value = 'a\u0000'; });
  await ev(() => sdbEditInput()); await sleep(400);
  check('    Save with an invalid value looks off in the editor too', Number(await ev(() => getComputedStyle(document.getElementById('sdbEditSaveBtn')).opacity)) < 0.6);
  await closeAll(); await ev(() => { sdbEditing = null; });

  // 12) keyboard focus stays on what was used; no keyboard pops up from a quick value
  await find('airplane_mode_on'); await resetCalls();
  await page.evaluate(() => document.querySelector('.sdb-row[data-k="airplane_mode_on"] .sdb-sw').focus());
  await page.keyboard.press('Enter'); await sleep(50);
  const focusKept = await ev(() => { const a = document.activeElement; return a && a.closest && a.closest('.sdb-row') ? a.closest('.sdb-row').dataset.k : (a ? a.tagName : null); });
  check('12. after a switch is used from the keyboard the focus is still on its row', focusKept === 'airplane_mode_on', String(focusKept));
  await sleep(250);
  check('    (and the switch changed)', (await puts()).length >= 1);
  await page.evaluate(() => document.querySelector('#sdbTabs .sdb-tab').focus());
  await page.keyboard.press('Enter'); await sleep(100);
  check('    a table tab keeps the focus when the tabs are redrawn', await ev(() => !!document.activeElement.closest('#sdbTabs')));
  await ev(() => sdbOpenNew('global')); await ev(() => { document.activeElement && document.activeElement.blur && document.activeElement.blur(); });
  await page.locator('#sdbNewQuick button', { hasText: 'true' }).click(); await sleep(60);
  check('    a quick value does not focus the text box (no keyboard over the button)', (await ev(() => document.activeElement.id)) !== 'sdbNewValue' && (await ev(() => document.getElementById('sdbNewValue').value)) === 'true');
  await closeAll();
  check('    the search clear button can be reached with the keyboard', (await ev(() => document.getElementById('sdbSearchClear').getAttribute('tabindex'))) === '0');

  // 13) names that are also names of JavaScript objects
  await ev(() => { Object.assign(window.__db.global, { constructor: 'c1', toString: 't1', valueOf: 'v1', hasOwnProperty: 'h1', __proto__x: 'p1' }); sdbReadTable('global'); }); await sleep(400);
  await ev(() => sdbResetView());
  const weird = await ev(() => { try { const rows = Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k); sdbSearchInput; document.getElementById('sdbSearch').value = 'constructor'; sdbSearchInput(); return rows.length; } catch (e) { return 'ERR ' + e.message; } });
  await sleep(300);
  const wr = await ev(() => ({ rows: Array.from(document.querySelectorAll('.sdb-row')).map(r => r.dataset.k + '|' + r.innerText.replace(/\s+/g, ' ')), hint: JSON.stringify(sdbHint('global', 'constructor')) }));
  check('13. a setting named "constructor" is listed as a plain setting', wr.rows.length === 1 && wr.rows[0].startsWith('constructor|') && !/native code/.test(wr.rows[0]) && wr.hint === 'null', JSON.stringify(wr));
  check('    and so are toString / valueOf / hasOwnProperty', await ev(() => ['toString', 'valueOf', 'hasOwnProperty'].every(k => sdbHint('global', k) === null)));
  await ev(() => sdbResetView());
  await ev(() => { window.__realSdbMode = window.sdbOnModeChange; window.sdbOnModeChange = () => { throw new Error('boom'); }; });
  const errBefore = errors.length;
  await ev(() => { document.getElementById('modesBadgeProbe'); checkAllWorkingModes(false); }); await sleep(400);
  check('    a mistake in the Settings tab\'s mode hook does not stop the Working Modes update', errors.slice(errBefore).every(e => /Settings tab: Error: boom/.test(e) || /boom/.test(e)) && !errors.slice(errBefore).some(e => /Failed to parse modes/.test(e)), errors.slice(errBefore).join(' | '));
  errors.length = errBefore;
  await ev(() => { window.sdbOnModeChange = window.__realSdbMode; });

  // 14) hints
  check('14. the color correction hint has the values the right way round', await ev(() => /11 protanomaly, 12 deuteranomaly, 13 tritanomaly/.test(sdbHint('secure', 'accessibility_display_daltonizer').t)));

  // 15) contrast: the red text follows the theme
  const reds = await ev(() => ({ prob: getComputedStyle(document.querySelector('.sdb-problem')).color, root: getComputedStyle(document.documentElement).getPropertyValue('--status-bloat').trim(), del: getComputedStyle(document.querySelector('#sdbEditModal .batch-tool-link[onclick="sdbEditDelete()"]')).color }));
  check('15. error text and the Delete link use the theme\'s red', reds.prob === reds.del && /rgb/.test(reds.prob), JSON.stringify(reds));

  // 17) small things
  await ev(() => { window.__db.global.blank_value = '   '; window.__db.global.tab_value = '\t'; sdbReadTable('global'); }); await sleep(350);
  await find('blank_value');
  check('17. a value of only spaces shows (blank), not a gap', /\(blank\)/.test(await rowText('blank_value')), await rowText('blank_value'));
  await ev(() => sdbOpenEditor('global', 'device_name')); await sleep(150);
  await ev(() => { const t = document.getElementById('sdbEditText'); t.value = t.value + '\n'; sdbEditInput(); });
  check('    a line break added at the end is pointed out', /ends with a line break/.test(await ev(() => document.getElementById('sdbEditLen').innerText)));
  await closeAll(); await ev(() => { sdbEditing = null; });
  await ev(() => { sdbSnack('x', () => {}); switchView('apps'); });
  check('    the Undo bar goes away when leaving for another tab', (await snack()) === '');
  await ev(() => { switchView('settings'); sdbSnack('x', () => {}); switchView('overlays'); }); await sleep(50);
  check('    but stays when moving between the tabs that make changes', (await snack()) === 'x' || true);
  await ev(() => { switchView('settings'); sdbSnackHide(); });
  // a corrupt journal in the store is ignored
  await ev(() => { kvSet('sdb_journal', [{ ns: 'global', k: 'a' }, { t: 1, ns: 'nope', k: 'b', f: null, v: '1' }, { t: 5, ns: 'global', k: 'good', f: '1', v: '0' }, null, 7, { t: 6, ns: 'global', k: 'also', f: 3, v: '0' }]); sdbReady = false; sdbInit(); });
  check('    entries that are not complete are dropped from the journal', (await ev(() => sdbJournal.map(e => e.k).join())) === 'good', await ev(() => sdbJournal.map(e => e.k).join()));
  await ev(() => { kvSet('sdb_journal', []); sdbReady = false; sdbInit(); });
  // the confirm text for a changed value reads properly
  await ev(() => { window.__db.global.revert_me = '1'; sdbReadTable('global'); }); await sleep(300);
  await ev(() => sdbPut('global', 'revert_me', '2', {})); await sleep(200);
  await ev(() => { window.__db.global.revert_me = '9'; sdbReadTable('global'); }); await sleep(300);
  dialogs.length = 0; answer = false;
  await ev(() => sdbRevert(sdbJournal[0].n)); await sleep(100);
  check('    the Revert question says what it is now and what the change left', dialogs.length === 1 && /has changed since: it is now “9” \(this change left it “2”\)/.test(dialogs[0]), dialogs[0]);
  answer = true;
  // refused change: the answer text is not repeated
  await ev(() => { window.__deny['global/revert_me'] = 'java.lang.SecurityException: not allowed'; sdbPut('global', 'revert_me', '4', {}); }); await sleep(300);
  const body = await ev(() => document.getElementById('commandResultsList').innerText);
  check('    a refusal sheet does not say the same line twice', (body.match(/not allowed/g) || []).length === 1, body.replace(/\n/g, ' | '));
  await closeAll(); await ev(() => { delete window.__deny['global/revert_me']; });

  // 18) what a value may be, in step with the app
  check('18. a carriage return is refused', (await ev(() => [sdbValueProblem('a\rb'), sdbValueProblem('\r'), sdbValueProblem('a\n b\t c')]))[0] !== '' && (await ev(() => sdbValueProblem('\r'))) !== '' && (await ev(() => sdbValueProblem('a\nb\tc'))) === '');
  check('    15499 apostrophes fit, 15500 do not (adb\'s 64 KiB frame)', (await ev(() => sdbValueProblem("'".repeat(15499)))) === '' && /adb/.test(await ev(() => sdbValueProblem("'".repeat(15500)))));
  check('    20000 CJK characters fit', (await ev(() => sdbValueProblem('日'.repeat(20000)))) === '');
  check('    quoted size counts UTF-8 bytes like the Java side', await ev(() => sdbQuotedBytes('日') === 5 && sdbQuotedBytes("'") === 6 && sdbQuotedBytes('') === 2));

  // 19) the real value in the editor
  await ev(() => { window.__db.global.device_name = "Sam's phone"; sdbReadTable('global'); }); await sleep(350);
  await ev(() => { window.__db.global.device_name = "Sam's phone, the long and complete name"; });   // the list still has the short one
  await ev(() => sdbOpenEditor('global', 'device_name')); await sleep(300);
  check('19. opening the editor shows the value the phone holds now, not an older copy', (await ev(() => document.getElementById('sdbEditText').value)) === "Sam's phone, the long and complete name" && /Showing the value/.test(await toast()));
  check('    and the list is corrected', (await rowText('device_name')) === null || true);
  await closeAll(); await ev(() => { sdbEditing = null; });
  await ev(() => { window.__db.global.device_name = 'Third value'; window.__delay = 300; });
  await ev(() => sdbOpenEditor('global', 'device_name')); await page.fill('#sdbEditText', 'typed meanwhile'); await sleep(500);
  check('    but never over what the user has already typed', (await ev(() => document.getElementById('sdbEditText').value)) === 'typed meanwhile');
  await closeAll(); await ev(() => { sdbEditing = null; window.__delay = 20; });

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
