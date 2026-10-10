// v7.12.6: "Test the default agent" in Settings, green Enable / Reinstall in the Connected Devices tab, the history of the Ask agent window, and the
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
  check('1. the Default agent card has a "Test the default agent" button', await ev(() => /Test the default agent/.test(document.getElementById('askTestBtn').innerText)));
  await page.click('#askTestBtn'); await sleep(100);
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
    const g = h => /cd-row-act green" onclick="cdAppAct/.test(h);
    const batch = [...document.querySelectorAll('#cdBatch .cd-row-act')].map(e => [e.getAttribute('onclick'), e.className]);
    return { disabled: g(row({ disabled: true })), gone: g(row({ uninstalled: true })), plain: g(row({})), batch };
  });
  const bg = n => cd.batch.find(x => x[0].indexOf(n) >= 0)[1];
  check('2. Connected Devices: Enable and Reinstall (row and batch) are green, Disable and Uninstall are not', cd.disabled && cd.gone && !cd.plain && /green/.test(bg("'enable'")) && /green/.test(bg("'reinstall'")) && !/green/.test(bg("'disable'")) && !/green/.test(bg("'uninstall'")), JSON.stringify(cd));

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
  await page.click('#agentAskHist .agent-ask-hist-row:nth-child(2)'); await sleep(150);
  const open = await ev(() => ({ subj: document.getElementById('agentAskSubject').innerText, ai: document.getElementById('agentAskAi').innerText, hidden: document.getElementById('agentAskHist').style.display === 'none', sent: document.getElementById('agentAskSent').innerText }));
  check('   tapping one shows the saved answer (no new request), with its question', /alpha/.test(open.subj) && /First answer about alpha/.test(open.ai) && /Saved answer from/.test(open.ai) && open.hidden && /Package: com\.example\.alpha/.test(open.sent), JSON.stringify(open));
  await ev(() => { askAgentAbout('package', 'com.example.alpha', ['App name: Alpha']); }); await sleep(50);
  await ev(() => agentAskClose());
  await ev(s => { window.__ai.queue.length = 0; window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('Newer answer about alpha.'));
  await ev(() => askAgentAbout('package', 'com.example.alpha', ['App name: Alpha'])); await sleep(600);
  check('   asking about the same thing again replaces the old entry (no duplicates)', await ev(() => { const h = kvGet('ask_history', []); return h.length === 2 && h[0].name === 'com.example.alpha' && /Newer answer/.test(h[0].answer); }));
  await ev(() => { for (let i = 0; i < 20; i++) askHistAdd({ kind: 'package', name: 'pkg' + i, text: 't', web: '', answer: 'a' + i }, ''); });
  check('   only the last 12 are kept', await ev(() => kvGet('ask_history', []).length === 12));
  await ev(() => { document.getElementById('agentAskHist').style.display = 'none'; }); await page.click('#agentAskHistBtn'); await sleep(50);
  await page.click('#agentAskHist .agent-ask-hist-row:last-child'); await sleep(100);
  check('   Clear history removes them', await ev(() => kvGet('ask_history', []).length === 0 && /No answers are kept yet/.test(document.getElementById('agentAskHist').innerText)));
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
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
