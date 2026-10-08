// v7.12.0: the Logcat keeps its lines (a quiet or failed poll no longer empties it), the log can be recorded to a file and looked at afterwards as a still picture,
// the Ask agent button sits on every hidden setting (and the other places that need an explanation), and SD Maid's two data cards come after its four tools.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => {
    const base = window.AndroidBridge;
    window.__opened = []; window.__recs = {}; window.__rec = null; window.__recSeq = 0;
    base.openUrl = u => window.__opened.push(u);
    base.getLogcatAsync = () => 'started';
    base.logRecStart = (level, filter, pkg, name) => { const n = 'logcat_20261008_12000' + (++window.__recSeq) + '.log'; window.__rec = { name: n, lines: 5, level, filter, pkg }; window.__recs[n] = { text: '', size: 100, modified: Date.now() }; return JSON.stringify({ ok: true, name: n }); };
    base.logRecStatus = () => JSON.stringify({ ok: true, running: !!window.__rec, name: window.__rec ? window.__rec.name : '', lines: window.__rec ? window.__rec.lines : 0, bytes: 0, startedAt: 0, stopReason: '', lastError: '' });
    base.logRecStop = () => { const r = window.__rec; window.__rec = null; if (r) window.__recs[r.name].text = window.__recText || ''; return JSON.stringify({ ok: true, running: false, name: r ? r.name : '', lines: r ? r.lines : 0, bytes: 0, startedAt: 0, stopReason: 'stopped', lastError: '' }); };
    base.logRecList = () => JSON.stringify({ ok: true, items: Object.keys(window.__recs).sort().reverse().map(n => ({ name: n, size: window.__recs[n].size, modified: window.__recs[n].modified })) });
    base.logRecRead = n => window.__recs[n] ? window.__recs[n].text : 'Error: that recording is gone';
    base.logRecDelete = n => { delete window.__recs[n]; return 'ok'; };
    base.saveTextToDownloads = (n, t) => { window.__saved = { n, t }; return 'Download/' + n; };
  });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { isPrivilegedActive = true; });

  // ---- 1. the log keeps its lines ----
  const L = (t, tag, msg, lvl) => `10-08 12:00:${t}.000  1000  1001 ${lvl || 'E'} ${tag}: ${msg}`;
  const rowsN = () => ev(() => document.querySelectorAll('#logcatOutput .lc-row').length);
  await ev(() => { switchView('logcat'); logcatStop(); document.getElementById('logcatLevel').value = 'E'; logcatClear(); });
  await ev(t => logcatRender(t, false, true, lcSigNow()), [L('01', 'A', 'one'), L('02', 'B', 'two'), L('03', 'C', 'three')].join('\n')); await sleep(80);
  check('1. a first poll shows its three lines', await rowsN() === 3);
  await ev(t => logcatRender(t, false, true, lcSigNow()), [L('02', 'B', 'two'), L('03', 'C', 'three'), L('04', 'D', 'four')].join('\n')); await sleep(80);
  check('   the next poll adds only what is new: four lines, the overlap is not doubled', await rowsN() === 4);
  await ev(() => logcatRender('(no matching log lines)', false, true, lcSigNow())); await sleep(80);
  check('2. a poll that finds nothing leaves the four lines on the screen (it used to empty the view)', await rowsN() === 4 && await ev(() => !/no matching/.test(document.getElementById('logcatOutput').innerText)));
  await ev(() => logcatRender('', false, true, lcSigNow())); await sleep(50);
  check('   an empty answer does the same', await rowsN() === 4);
  await ev(() => logcatRender('Error: adb is busy', false, true, lcSigNow())); await sleep(50);
  check('   a failed poll while live changes nothing', await rowsN() === 4);
  await ev(() => logcatRender('(no matching log lines)', true, false, lcSigNow())); await sleep(50);
  check('   Refresh finding nothing new keeps the lines too, and says so in a toast', await rowsN() === 4);
  const rec = 'AndroidRuntime: \tat com.x.Y.f(Y.java:1)';
  await ev(t => logcatRender(t, false, true, lcSigNow()), [L('05', 'R', 'x'), L('05', 'R', 'x'), L('05', 'R', 'x')].join('\n')); await sleep(80);
  check('3. identical lines in one poll (a recursion) are all kept', await rowsN() === 4 + 1 || await ev(() => logcatEntries.filter(e => e.kind === 'entry').reduce((n, e) => n + e.lines.length, 0) === 7));
  await ev(t => logcatRender(t, false, true, lcSigNow()), [L('05', 'R', 'x'), L('05', 'R', 'x'), L('05', 'R', 'x')].join('\n')); await sleep(60);
  check('   and not added again by the next poll', await ev(() => logcatEntries.filter(e => e.kind === 'entry').reduce((n, e) => n + e.lines.length, 0) === 7));
  const staleSig = await ev(() => lcSigNow());
  await ev(() => { document.getElementById('logcatLevel').value = 'W'; });
  await ev(([t, s]) => logcatRender(t, false, true, s), [L('06', 'S', 'stale'), staleSig]); await sleep(60);
  check('4. an answer for a level that was changed since is dropped', await ev(() => !/stale/.test(document.getElementById('logcatOutput').innerText)));
  await ev(t => logcatRender(t, false, true, lcSigNow()), L('07', 'W', 'fresh', 'W')); await sleep(60);
  check('   a new level starts a new view: only the new answer is shown', await rowsN() === 1 && await ev(() => /fresh/.test(document.getElementById('logcatOutput').innerText)));
  await ev(() => { document.getElementById('logcatLevel').value = 'E'; logcatClear(); }); await sleep(60);
  check('5. Clear empties the view and the kept lines', await ev(() => /\(cleared\)/.test(document.getElementById('logcatOutput').innerText) && lcStore.length === 0));

  // ---- 2. recording ----
  check('6. the Logcat tab has Record to file and Recordings', await ev(() => document.getElementById('logcatRecBtn').innerText.trim() === '● Record to file' && document.getElementById('logcatRecListBtn').innerText.trim() === 'Recordings'));
  await ev(() => logcatRecToggle()); await sleep(100);
  const started = await ev(() => ({ rec: window.__rec && window.__rec.level, btn: document.getElementById('logcatRecBtn').innerText }));
  check('   pressing it starts a recording with the level of the view, and the button becomes Stop with the line count', started.rec === 'E' && /^■ Stop recording · 5 lines$/.test(started.btn), JSON.stringify(started));
  const REC = ['# ADB Application Manager - log recording', '# Level: E and above']
    .concat(Array.from({ length: 1200 }, (_, i) => `10-08 12:${String(Math.floor(i / 60) % 60).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}.000  1000  1001 ${i % 7 === 0 ? 'E' : i % 5 === 0 ? 'W' : 'I'} Tag${i % 3}: message number ${i}`)).join('\n');
  await ev(t => { window.__recText = t; window.__rec.lines = 1200; }, REC);
  await ev(() => logcatRecToggle()); await sleep(250);
  const vw = await ev(() => ({ open: document.getElementById('lcvModal').classList.contains('show'), title: document.getElementById('lcvTitle').innerText, sub: document.getElementById('lcvSub').innerText, rows: document.querySelectorAll('#lcvOut .lc-row').length, pager: document.getElementById('lcvPager').innerText.replace(/\s+/g, ' '), btn: document.getElementById('logcatRecBtn').innerText }));
  check('7. stopping saves the file and opens it as a still picture', vw.open && /^logcat_2026/.test(vw.title) && /1200 entries/.test(vw.sub) && vw.rows === 500 && /Entries 1.500 of 1200/.test(vw.pager.replace('–', '.')) && vw.btn === '● Record to file', JSON.stringify(vw));
  await ev(() => lcvDraw(-1)); await sleep(60);
  check('   the pages go on to the end (the last one holds the rest)', await ev(() => document.querySelectorAll('#lcvOut .lc-row').length === 200 && /Entries 1001.1200 of 1200/.test(document.getElementById('lcvPager').innerText.replace('–', '.').replace(/\s+/g, ' '))));
  await ev(() => lcvDraw(0));
  await ev(() => { document.getElementById('lcvFilter').value = 'number 4'; lcvDraw(0); }); await sleep(60);
  check('8. the search narrows the recording to the matching entries and marks the match', await ev(() => { const n = document.querySelectorAll('#lcvOut .lc-row').length; return n > 0 && n < 500 && document.querySelectorAll('#lcvOut mark.lc-hit').length >= n; }));
  await ev(() => { document.getElementById('lcvFilter').value = ''; lcvToggleLevel('I'); lcvToggleLevel('W'); }); await sleep(60);
  const onlyE = await ev(() => ({ n: document.querySelectorAll('#lcvOut .lc-row').length, all: [...document.querySelectorAll('#lcvOut .lc-row')].every(r => r.classList.contains('lvl-E')), pager: document.getElementById('lcvPager').innerText.replace(/\s+/g, ' ') }));
  check('   the level key hides levels in the recording', onlyE.all && onlyE.n === 172 && /of 172/.test(onlyE.pager), JSON.stringify(onlyE));
  await ev(() => document.querySelector('#lcvOut .lc-row').click()); await sleep(120);
  check('   tapping an entry opens its window above the recording', await ev(() => document.getElementById('lcEntryModal').classList.contains('show') && /message number 0/.test(document.getElementById('lcEntMsg').innerText)));
  await ev(() => lcEntryClose());
  await ev(() => { window.__copied = ''; lcvCopy(); }); await sleep(60);
  check('9. Copy shown copies the lines that are shown', await ev(() => window.__copied.split('\n').length === 172 && /message number 0$/m.test(window.__copied)));
  await ev(() => lcvSave()); await sleep(50);
  check('   Save writes the whole recording to Downloads', await ev(() => window.__saved && /^logcat_2026/.test(window.__saved.n) && window.__saved.t.split('\n').length === 1202));
  await ev(() => lcvClose());
  await ev(() => lcRecListOpen()); await sleep(100);
  check('10. Recordings lists it, newest first, with its size', await ev(() => document.querySelectorAll('#lcRecList .lc-rec-row').length === 1 && /logcat_2026/.test(document.querySelector('#lcRecList .lc-rec-sub').innerText)));
  await ev(() => document.querySelector('#lcRecList .lc-rec-row').click()); await sleep(200);
  check('    tapping one opens the still picture again (and closes the list)', await ev(() => document.getElementById('lcvModal').classList.contains('show') && !document.getElementById('lcRecModal').classList.contains('show')));
  await ev(() => lcvDelete()); await sleep(100);
  check('11. Delete removes the recording', await ev(() => !document.getElementById('lcvModal').classList.contains('show') && Object.keys(window.__recs).length === 0));
  await ev(() => lcRecListOpen()); await sleep(80);
  check('    and the list says there are none', await ev(() => /No recordings yet/.test(document.getElementById('lcRecList').innerText)));
  await ev(() => lcRecListClose());

  // ---- 3. the Ask agent button ----
  await ev(() => switchView('settings')); await sleep(900);
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'zen_mode'; sdbSearchInput(); }); await sleep(300);
  const row = await ev(() => { const r = document.querySelector('#sdbList .sdb-row[data-k="zen_mode"]'); const bt = r && r.querySelector('.agent-btn'); return { has: !!bt, ak: bt && bt.dataset.ak, an: bt && bt.dataset.an, ax: bt && bt.dataset.ax, txt: bt && bt.innerText.trim() }; });
  check('12. a hidden setting\'s row carries the Ask agent button', row.has && row.ak === 'setting' && row.an === 'zen_mode' && /Table: global/.test(row.ax) && /Value now: /.test(row.ax) && row.txt === '✦ Ask agent', JSON.stringify(row));
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'zzz_no_such'; sdbSearchInput(); }); await sleep(100);
  const hasUndescribed = await ev(() => { const out = []; for (const ns of ['global', 'secure', 'system']) if (sdbData[ns]) for (const k of sdbData[ns].keys()) if (!sdbHint(ns, k)) { out.push(sdbRowHtml(ns, k, sdbData[ns].get(k), [])); if (out.length > 2) break; } return out; });
  check('    a setting without a description says so, next to the button', hasUndescribed.length > 0 && hasUndescribed.every(h => /No description yet/.test(h) && /agent-btn/.test(h)), String(hasUndescribed.length));
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'zen_mode'; sdbSearchInput(); }); await sleep(300);
  await ev(() => { askAgentId = ''; window.__sent = []; });
  await page.locator('#sdbList .sdb-row[data-k="zen_mode"] .agent-btn').click(); await sleep(450);
  check('13. without a default agent the button leads to Settings (the row did not open its editor)', await ev(() => currentViewName() === 'prefs' && !document.getElementById('sdbEditModal').classList.contains('show') && !document.getElementById('agentAskModal').classList.contains('show')));
  await ev(() => {
    askAgentId = 'fake';
    window.txAgentDef = () => ({ id: 'fake', name: 'Fake AI (test)', api: 'x', provider: 'fake' });
    window.txAgentReady = () => true; window.txModelFor = () => 'fake-model'; window.txEffortFor = () => 'balanced';
    window.txTurnApi = async (ag, model, effort, system, msgs, onText) => { window.__sent.push({ system, msgs }); onText('A '); onText('mode.'); return { text: 'A mode.', usage: { in: 1, out: 1 } }; };
    switchView('settings');
  }); await sleep(900);
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'zen_mode'; sdbSearchInput(); }); await sleep(300);
  await page.locator('#sdbList .sdb-row[data-k="zen_mode"] .agent-btn').click(); await sleep(400);
  const asked = await ev(() => ({ modal: document.getElementById('agentAskModal').classList.contains('show'), editor: document.getElementById('sdbEditModal').classList.contains('show'), title: document.getElementById('agentAskTitle').innerText, subj: document.getElementById('agentAskSubject').innerText, ans: document.getElementById('agentAskAi').innerText, sent: window.__sent[0] }));
  check('14. with a default agent it asks at once, in its own window, about that setting', asked.modal && !asked.editor && asked.title === 'About this setting' && asked.subj === 'zen_mode' && /A mode\./.test(asked.ans) && asked.sent && /Setting: zen_mode/.test(asked.sent.msgs[0].text) && /Table: global/.test(asked.sent.msgs[0].text) && /hidden setting/.test(asked.sent.system), JSON.stringify(asked));
  await ev(() => agentAskClose());

  // the other places
  const htmls = await ev(() => ({
    ovl: ovlRowHtml({ id: 'com.android.theme.color.x', target: 'android', state: 1, category: 'x' }, true),
    cfg: agentBtnHtml('permission', 'android.permission.CAMERA', ['Protection level: dangerous']),
  }));
  check('15. overlays carry the button too', /agent-btn/.test(htmls.ovl) && /data-ak="overlay"/.test(htmls.ovl));
  check('    and a button made for a name with quotes keeps them out of the markup', await ev(() => { const h = agentBtnHtml('item', 'a"b<c', ['x"y']); const d = document.createElement('div'); d.innerHTML = h; const bt = d.firstChild; return d.childNodes.length === 1 && bt.dataset.an === 'a"b<c' && bt.dataset.ax === 'x"y'; }));

  // ---- 4. SD Maid ----
  await ev(() => switchView('sdm')); await sleep(500);
  check('16. in SD Maid, the Leftover data card and Trim Caches come after the four tools, in that order', await ev(() => {
    const c = document.getElementById('sdCards'), u = document.getElementById('uninstDataCard'), t = document.getElementById('trimCachesCard');
    const f = n => (c.compareDocumentPosition(n) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    return f(u) && f(t) && (u.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0 && u.parentElement === c.parentElement;
  }));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
