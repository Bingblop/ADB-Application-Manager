// v7.12.8: "Test the default agent" in Settings, green Enable / Reinstall in the Connected Devices tab, the history of the Ask agent window, and the
// progress bar + Stop in the log-entry and Hidden Settings windows.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });

  // ---- 1. Test the default agent ----
  await ev(() => switchView('prefs')); await sleep(300);
  check('1. the Default Ask Agent card has a "Test the Default Ask Agent" button', await ev(() => /Test the Default Ask Agent/.test(document.getElementById('askTestBtn').innerText)));
  await ev(() => askAgentChoose('')); await page.click('#askTestBtn'); await sleep(100);
  check('   with no default agent it says there is nothing to test (and sends nothing)', await ev(() => /nothing to test/.test(document.getElementById('askTestRes').innerText) && window.__ai.reqs.length === 0), await ev(() => document.getElementById('askTestRes').innerText));
  await ev(() => { askAgentId = 'claude'; askAgentBuild(); });
  await page.click('#askTestBtn'); await sleep(100);
  check('   a default agent that is not connected says so', await ev(() => /not connected yet/.test(document.getElementById('askTestRes').innerText)), await ev(() => document.getElementById('askTestRes').innerText));
  // connect an own server
  await ev(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'qwen2.5-7b' }] }) }); switchView('terminal'); }); await sleep(200);
  await page.selectOption('#txAgent', 'ownserver'); await sleep(150);
  await page.fill('#txBaseInput', '192.168.1.20:1234/v1'); await page.click('#txKeyTestBtn'); await sleep(300);
  await ev(() => { txCloseConnect(); askAgentId = 'ownserver'; kvSet('ask_agent', 'ownserver'); switchView('prefs'); askAgentBuild(); }); await sleep(300);
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('The Calculator can be disabled safely.'));
  await page.click('#askTestBtn'); await sleep(600);
  const t1 = await ev(() => ({ out: document.getElementById('askTestOut').innerText, res: document.getElementById('askTestRes').innerText, btn: document.getElementById('askTestBtn').disabled, body: JSON.stringify((window.__ai.reqs.filter(r => /chat\/completions/.test(r.url)).pop() || {}).json || {}) }));
  check('   a connected agent answers the sample question; the line says it works and how long it took; only the sample is sent (no phone details)', /Calculator can be disabled/.test(t1.out) && /It works/.test(t1.res) && /answered in \d+ s/.test(t1.res) && !t1.btn && /calculator2/.test(t1.body) && !/Pixel|Samsung|SM-/i.test(t1.body));
  await ev(() => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), status: 500, body: '{"error":{"message":"boom"}}' }); });
  await page.click('#askTestBtn'); await sleep(500);
  check('   a failing agent is reported', await ev(() => /did not work/.test(document.getElementById('askTestRes').innerText) && document.getElementById('askTestOut').classList.contains('is-err')), await ev(() => document.getElementById('askTestRes').innerText));
  await ev(() => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), hold: true, sse: [] }); });
  await page.click('#askTestBtn'); await sleep(300);
  const hold = await ev(() => ({ stop: getComputedStyle(document.getElementById('askTestStop')).display, prog: document.getElementById('askTestProg').classList.contains('on') }));
  await page.click('#askTestStop'); await sleep(300);
  const stopped = await ev(() => ({ out: document.getElementById('askTestOut').innerText, prog: document.getElementById('askTestProg').classList.contains('on'), btn: document.getElementById('askTestBtn').disabled }));
  check('   while it waits there is a progress bar and Stop; Stop ends the test', hold.stop !== 'none' && hold.prog && /Stopped\./.test(stopped.out) && !stopped.prog && !stopped.btn, JSON.stringify({ hold, stopped }));

  // ---- 2. Connected Devices buttons ----
  const cd = await ev(() => {
    const row = (o) => cdAppCardHtml(Object.assign({ pkg: 'com.t.a', name: 'A', system: false, disabled: false, uninstalled: false }, o));
    const g = h => /cd-row-act green" onclick="cdAppAct/.test(h), bl = h => /cd-row-act blue" onclick="cdAppAct/.test(h);
    const batch = [...document.querySelectorAll('#cdBatch .cd-row-act')].map(e => [e.getAttribute('onclick'), e.className]);
    return { disabled: g(row({ disabled: true })), gone: g(row({ uninstalled: true })), plain: g(row({})), plainBlue: bl(row({})), col: ['enable', 'disable', 'uninstall', 'reinstall'].map(n => { const e = document.querySelector('#cdBatch .cd-row-act[onclick*="' + n + '"]'); return getComputedStyle(e).color; }), batch };
  });
  const bg = n => cd.batch.find(x => x[0].indexOf(n) >= 0)[1];
  check('2. Connected Devices: Enable and Reinstall are green, Disable is blue and Uninstall red, like the app menu', cd.disabled && cd.gone && !cd.plain && cd.plainBlue && /green/.test(bg("'enable'")) && /green/.test(bg("'reinstall'")) && /blue/.test(bg("'disable'")) && /danger/.test(bg("'uninstall'")) && cd.col[0] === 'rgb(0, 240, 80)' && cd.col[1] === 'rgb(41, 121, 255)' && cd.col[2] === 'rgb(255, 82, 82)' && cd.col[3] === 'rgb(0, 240, 80)', JSON.stringify(cd.col));

  // ---- 3. history ----
  await ev(() => { askAgentId = 'ownserver'; askAgentBuild(); kvSet('ask_history', []); window.__ai.queue.length = 0; });
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('First answer about alpha.'));
  await ev(() => askAgentAbout('package', 'com.example.alpha', ['App name: Alpha'])); await sleep(600);
  await ev(() => agentAskClose());
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('Second answer about beta.'));
  await ev(() => askAgentAbout('setting', 'zen_mode', ['Table: global'])); await sleep(600);
  await page.click('#agentAskHistBtn'); await sleep(100);
  const hist = await ev(() => ({ rows: [...document.querySelectorAll('#agentAskHist .agent-ask-hist-row')].map(r => r.innerText.replace(/\s+/g, ' ')), stored: kvGet('ask_history', []).length }));
  check('3. the answers are kept, newest first, with a Clear history row', hist.stored === 2 && hist.rows.length === 3 && /zen_mode/.test(hist.rows[0]) && /com\.example\.alpha/.test(hist.rows[1]) && /Clear history/.test(hist.rows[2]), JSON.stringify(hist));
  await page.click('#agentAskHist .agent-ask-hist-item:nth-child(2) .agent-ask-hist-row'); await sleep(150);
  const open = await ev(() => ({ subj: document.getElementById('agentAskSubject').innerText, ai: document.getElementById('agentAskAi').innerText, hidden: document.getElementById('agentAskHist').style.display === 'none', sent: document.getElementById('agentAskSent').innerText }));
  check('   tapping one shows the saved answer (no new request), with its question', /alpha/.test(open.subj) && /First answer about alpha/.test(open.ai) && /Saved answer from/.test(open.ai) && open.hidden && /Package: com\.example\.alpha/.test(open.sent), JSON.stringify(open));
  await ev(() => { askAgentAbout('package', 'com.example.alpha', ['App name: Alpha']); }); await sleep(50);
  await ev(() => agentAskClose());
  await ev(s => { window.__ai.queue.length = 0; window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('Newer answer about alpha.'));
  await ev(() => askAgentAbout('package', 'com.example.alpha', ['App name: Alpha'])); await sleep(600);
  check('   asking about the same thing again replaces the old entry (no duplicates)', await ev(() => { const h = kvGet('ask_history', []); return h.length === 2 && h[0].name === 'com.example.alpha' && /Newer answer/.test(h[0].answer); }));
  await ev(() => { for (let i = 0; i < 20; i++) askHistAdd('agentAsk', { id: 'package|pkg' + i, kind: 'package', name: 'pkg' + i, label: 'Package', text: 't', web: '', answer: 'a' + i, agent: '' }); });
  check('   only the last 12 are kept', await ev(() => kvGet('ask_history', []).length === 12));
  await ev(() => { document.getElementById('agentAskHist').style.display = 'none'; }); await page.click('#agentAskHistBtn'); await sleep(50);
  // pins: a pinned answer stays when newer ones push the others out, Clear history keeps it, Pin again lets go
  await ev(() => { askHistAdd('agentAsk', { id: 'package|com.pinned.one', kind: 'package', name: 'com.pinned.one', label: 'Package', text: 't', web: '', answer: 'keep me', agent: '' }); });
  await ev(() => { document.getElementById('agentAskHist').style.display = 'none'; }); await page.click('#agentAskHistBtn'); await sleep(50);
  const idx = await ev(() => askHistSorted('agentAsk').findIndex(e => e.name === 'com.pinned.one'));
  await page.click('#agentAskHist .agent-ask-hist-item:nth-child(' + (idx + 1) + ') .agent-ask-hist-pin'); await sleep(50);
  await ev(() => { for (let i = 0; i < 20; i++) askHistAdd('agentAsk', { id: 'package|later' + i, kind: 'package', name: 'later' + i, label: 'Package', text: 't', web: '', answer: 'b' + i, agent: '' }); });
  const pinned = await ev(() => { const l = askHistGet('agentAsk'); return { n: l.length, kept: l.some(e => e.name === 'com.pinned.one' && e.pinned), first: askHistSorted('agentAsk')[0].name, ui: [...document.querySelectorAll('#agentAskHist .agent-ask-hist-pin')][0].innerText }; });
  check('   a pinned answer survives the limit (12 more are kept beside it), is listed first and says Pinned', pinned.n === 13 && pinned.kept && pinned.first === 'com.pinned.one' && /Pinned/.test(pinned.ui), JSON.stringify(pinned));
  await page.click('#agentAskHist .agent-ask-hist-row:last-child'); await sleep(100);
  check('   Clear history removes the others and keeps the pinned one', await ev(() => { const l = kvGet('ask_history', []); return l.length === 1 && l[0].name === 'com.pinned.one'; }));
  await page.click('#agentAskHist .agent-ask-hist-item:nth-child(1) .agent-ask-hist-pin'); await sleep(50);
  await page.click('#agentAskHist .agent-ask-hist-row:last-child'); await sleep(100);
  check('   Unpinned, it goes with Clear history', await ev(() => kvGet('ask_history', []).length === 0 && /No answers are kept yet/.test(document.getElementById('agentAskHist').innerText)));
  await ev(() => agentAskClose());

  // ---- 4. progress + Stop in the log-entry and Hidden Settings windows ----
  await ev(() => { window.__ai.queue.length = 0; window.__ai.cancels.length = 0; });
  await ev(() => { switchView('logcat'); });
  const LOG = '10-08 12:00:01.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main\n10-08 12:00:01.001  1000  1000 E AndroidRuntime: java.lang.NullPointerException';
  await ev(l => { logcatRender(l, true); lcEntryOpen(0); }, LOG); await sleep(250);
  await ev(() => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), hold: true, sse: [] }); });
  await page.click('#lcEntMore'); await sleep(300);
  const l1 = await ev(() => ({ stop: getComputedStyle(document.getElementById('lcEntStop')).display, prog: document.getElementById('lcEntProg').classList.contains('on'), busy: document.getElementById('lcEntMore').disabled }));
  await page.click('#lcEntStop'); await sleep(300);
  const l2 = await ev(() => ({ ai: document.getElementById('lcEntAi').innerText, prog: document.getElementById('lcEntProg').classList.contains('on'), stop: getComputedStyle(document.getElementById('lcEntStop')).display, busy: document.getElementById('lcEntMore').disabled, c: window.__ai.cancels.length }));
  check('4. the log-entry window shows the bar and Stop while asking; Stop says Stopped. and frees the Ask agent button', l1.stop !== 'none' && l1.prog && l1.busy && /Stopped\./.test(l2.ai) && !l2.prog && l2.stop === 'none' && !l2.busy && l2.c >= 1, JSON.stringify({ l1, l2 }));
  await ev(() => lcEntryClose());
  await ev(() => { window.__ai.cancels.length = 0; window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), hold: true, sse: [] }); sdbData.global = new Map([['zen_mode', '0']]); sdbOpenEditor('global', 'zen_mode'); }); await sleep(300);
  const opened = await ev(() => document.getElementById('sdbEditModal').classList.contains('show'));
  await page.click('#sdbEditAiBtn'); await sleep(300);
  const s1 = await ev(() => ({ stop: getComputedStyle(document.getElementById('sdbEditStop')).display, prog: document.getElementById('sdbEditProg').classList.contains('on') }));
  await page.click('#sdbEditStop'); await sleep(300);
  const s2 = await ev(() => ({ ai: document.getElementById('sdbEditAi').innerText, prog: document.getElementById('sdbEditProg').classList.contains('on'), busy: document.getElementById('sdbEditAiBtn').disabled, c: window.__ai.cancels.length }));
  check('   the Hidden Settings window too', opened && s1.stop !== 'none' && s1.prog && /Stopped\./.test(s2.ai) && !s2.prog && !s2.busy && s2.c >= 1, JSON.stringify({ opened, s1, s2 }));
  // ---- 5. history in the log-entry and Hidden Settings windows ----
  await ev(() => { sdbEditClose(); window.__ai.queue.length = 0; kvSet('ask_history_sdb', []); kvSet('ask_history_log', []); });
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('Zen mode silences the phone.'));
  await ev(() => { sdbData.global = new Map([['zen_mode', '0']]); sdbOpenEditor('global', 'zen_mode'); }); await sleep(250);
  await page.click('#sdbEditAiBtn'); await sleep(600);
  const sh = await ev(() => ({ stored: kvGet('ask_history_sdb', []).map(e => e.name + '|' + e.pinned) }));
  await ev(() => sdbEditAiReset()); await page.click('#sdbEditHistBtn'); await sleep(80);
  const rowTxt = await ev(() => [...document.querySelectorAll('#sdbEditHist .agent-ask-hist-row')].map(r => r.innerText.replace(/\s+/g, ' ')));
  await page.click('#sdbEditHist .agent-ask-hist-item:nth-child(1) .agent-ask-hist-row'); await sleep(100);
  const shOpen = await ev(() => ({ ai: document.getElementById('sdbEditAi').innerText, shown: getComputedStyle(document.getElementById('sdbEditAi')).display !== 'none' }));
  check('5. the Hidden Settings window keeps its answers; History lists them and a tap shows the saved answer', sh.stored.length === 1 && /^zen_mode\|false$/.test(sh.stored[0]) && /zen_mode/.test(rowTxt[0]) && /Saved answer about zen_mode/.test(shOpen.ai) && /silences the phone/.test(shOpen.ai) && shOpen.shown, JSON.stringify({ sh, rowTxt, shOpen }));
  await ev(() => sdbEditClose());
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('A null pointer crashed the app.'));
  await ev(l => { switchView('logcat'); logcatRender(l, true); lcEntryOpen(0); }, LOG); await sleep(250);
  await page.click('#lcEntMore'); await sleep(600);
  const lh = await ev(() => kvGet('ask_history_log', []).map(e => e.name));
  await ev(() => { lcEntryClose(); }); await ev(l => { lcEntryOpen(0); }, LOG); await sleep(150);
  await page.click('#lcEntHistBtn'); await sleep(80);
  await page.click('#lcEntHist .agent-ask-hist-item:nth-child(1) .agent-ask-hist-row'); await sleep(100);
  const lo = await ev(() => ({ ai: document.getElementById('lcEntAi').innerText, wrap: getComputedStyle(document.getElementById('lcEntAiWrap')).display !== 'none' }));
  check('   the log-entry window too', lh.length === 1 && /AndroidRuntime/.test(lh[0]) && /Saved answer about AndroidRuntime/.test(lo.ai) && /null pointer/.test(lo.ai) && lo.wrap, JSON.stringify({ lh, lo }));
  await ev(() => lcEntryClose());

  // ---- 6. the Test button of a Connect sheet ----
  await ev(() => { switchView('terminal'); }); await sleep(200);
  await ev(() => txOpenConnect('ownserver')); await sleep(300);
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('Safe to disable.'));
  await page.click('#txConnTestBtn'); await sleep(600);
  const ct = await ev(() => ({ out: document.getElementById('txConnTestOut').innerText, res: document.getElementById('txConnTestRes').innerText }));
  check('6. a Connect sheet has a Test button: it asks the agent the sample question and says it works', /Safe to disable/.test(ct.out) && /It works: Own server/.test(ct.res), JSON.stringify(ct));
  await ev(() => txCloseConnect());
  await page.selectOption('#txAgent', 'claude'); await sleep(150);
  await page.click('#txConnTestBtn'); await sleep(100);
  check('   an agent without a key says it is not connected', await ev(() => /not connected yet/.test(document.getElementById('txConnTestRes').innerText)), await ev(() => document.getElementById('txConnTestRes').innerText));
  await ev(() => txCloseConnect());
  await page.selectOption('#txAgent', 'kilocode'); await sleep(150);
  check('   explained entries (Kilo Code) have none', await ev(() => !document.getElementById('txConnTestBtn')));
  await ev(() => txCloseConnect());
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
