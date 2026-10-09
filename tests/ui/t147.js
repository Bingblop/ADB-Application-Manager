// v7.12.6: Crawl4AI (cloud) and Browser Use (cloud) as agents that answer the Ask agent buttons: their key is tested and kept in the
// vault for their own host only, they become the default agent when none is chosen, they are not chat agents in the Terminal, and the
// answer comes from Crawl4AI's /answer or from a Browser Use task that is asked about until it ends.
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
  await ev(() => { txBuPollMs = 30; });

  // ---- Crawl4AI ----
  await ev(() => switchView('terminal')); await sleep(300);
  const before = await ev(() => ({ st: txState.agent, mode: txState.mode }));
  await page.selectOption('#txAgent', 'crawl4ai'); await sleep(100);
  const sheet = await ev(() => ({ open: document.getElementById('txConnectModal').classList.contains('show'), text: document.getElementById('txConnectBody').innerText, sel: document.getElementById('txAgent').value }));
  check('1. choosing Crawl4AI in the Terminal opens its key sheet (host api.crawl4ai.com, free tier explained), and does not make it the chat agent', sheet.open && /api\.crawl4ai\.com/.test(sheet.text) && /free/i.test(sheet.text) && /Ask agent/.test(sheet.text) && sheet.sel === before.st, JSON.stringify(sheet).slice(0, 300));
  await ev(() => { window.__ai.keyAnswer = (p, k) => /^sk_live_GOOD/.test(k) ? { ok: true, status: 200, body: '{"results":[]}' } : { ok: false, status: 401, body: '{"detail":"bad key"}' }; });
  await page.fill('#txKeyInput', 'sk_' + 'live_GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(250);
  const done = await ev(() => ({ open: document.getElementById('txConnectModal').classList.contains('show'), ask: askAgentId, saved: kvGet('ask_agent', ''), st: txState.agent, mode: txState.mode, prov: window.__ai.tests.map(t => t.provider), ready: txAgentReady(txAgentDef('crawl4ai')) }));
  check('2. a good key connects it, it becomes the default agent (none was chosen) and the Terminal stays as it was', !done.open && done.ask === 'crawl4ai' && done.saved === 'crawl4ai' && done.st === before.st && done.mode === before.mode && done.ready && done.prov.indexOf('crawl4ai') >= 0, JSON.stringify(done));
  await ev(() => { window.__ai.queue.push({ match: sp => /^https:\/\/api\.crawl4ai\.com\/answer\?q=/.test(sp.url), status: 200, body: JSON.stringify({ answered: true, answer: { text: 'It is the Bixby helper. Caution.', source: 'crawl4ai', sources: [{ title: 'Samsung', url: 'https://samsung.example/bixby' }] }, query: 'x' }) }); });
  await ev(() => { askAgentAbout('package', 'com.samsung.android.bixby.agent', ['App name: Bixby']); }); await sleep(500);
  const a1 = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, req: window.__ai.reqs.filter(r => /crawl4ai\.com\/answer/.test(r.url)).map(r => ({ url: r.url.slice(0, 60), auth: r.spec ? r.spec.auth : r.auth, method: r.method })) }));
  check('3. the Ask agent window gets the answer from Crawl4AI\'s answer service, with its sources, the request carries the vault name and no key', /Bixby helper/.test(a1.t) && /Sources/.test(a1.t) && /samsung\.example/.test(a1.t) && a1.req.length === 1 && !/sk_live/.test(JSON.stringify(a1.req)), JSON.stringify(a1));
  await ev(() => { agentAskClose(); window.__ai.queue.push({ match: sp => /crawl4ai\.com\/answer/.test(sp.url), status: 200, body: JSON.stringify({ answered: false }) }); askAgentAbout('package', 'com.x.y', []); }); await sleep(500);
  check('4. no answer is said in words', await ev(() => /found no answer/.test(document.getElementById('agentAskAi').innerText)), await ev(() => document.getElementById('agentAskAi').innerText));
  await ev(() => { agentAskClose(); window.__ai.queue.push({ match: sp => /crawl4ai\.com\/answer/.test(sp.url), status: 401, body: '{"detail":"Invalid API key"}' }); askAgentAbout('package', 'com.x.y', []); }); await sleep(500);
  check('5. a refused key is reported like any agent\'s', await ev(() => document.getElementById('agentAskAi').classList.contains('is-err') && document.getElementById('agentAskAi').innerText.length > 5), await ev(() => document.getElementById('agentAskAi').innerText));
  await ev(() => agentAskClose());

  // ---- Browser Use ----
  await ev(() => switchView('terminal')); await sleep(200);
  await page.selectOption('#txAgent', 'browseruse'); await sleep(100);
  check('6. Browser Use\'s sheet names api.browser-use.com', await ev(() => /api\.browser-use\.com/.test(document.getElementById('txConnectBody').innerText)));
  await ev(() => { window.__ai.keyAnswer = (p, k) => /^bu_GOOD/.test(k) ? { ok: true, status: 200, body: '{"totalCreditsBalanceUsd":1}' } : { ok: false, status: 401, body: '{}' }; });
  await page.fill('#txKeyInput', 'bu_GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(250);
  const done2 = await ev(() => ({ ask: askAgentId, ready: txAgentReady(txAgentDef('browseruse')) }));
  check('7. a good key connects it; the default agent (Crawl4AI) is left alone', done2.ready && done2.ask === 'crawl4ai', JSON.stringify(done2));
  await ev(() => { askAgentId = 'browseruse'; kvSet('ask_agent', 'browseruse'); askAgentBuild(); });
  const TID = '3c90c3cc-0d44-4b50-8888-8dd25736052a';
  await ev(t => {
    const q = window.__ai.queue;
    q.push({ match: sp => /api\.browser-use\.com\/api\/v2\/tasks$/.test(sp.url) && sp.method === 'POST', status: 202, body: JSON.stringify({ id: t, sessionId: t }) });
    q.push({ match: sp => sp.url.endsWith('/tasks/' + t), status: 200, body: JSON.stringify({ id: t, status: 'started', steps: [{ number: 1 }], output: null }) });
    q.push({ match: sp => sp.url.endsWith('/tasks/' + t), status: 200, body: JSON.stringify({ id: t, status: 'finished', steps: [{ number: 1 }, { number: 2 }], output: 'Safe to disable. It is only a helper.' }) });
  }, TID);
  await ev(() => askAgentAbout('package', 'com.example.helper', [])); 
  const ok8 = await until(() => /Safe to disable/.test(document.getElementById('agentAskAi').innerText), 6000);
  const a2 = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, urls: window.__ai.reqs.map(r => r.method + ' ' + r.url.replace('https://api.browser-use.com', '')).filter(u => /v2\/tasks/.test(u)), body: (window.__ai.reqs.find(r => /v2\/tasks$/.test(r.url)) || {}).json }));
  check('8. a task is started (the question as its task, a step limit), asked about until it ends, and its output is the answer', ok8 && a2.urls.length === 3 && /^POST/.test(a2.urls[0]) && a2.body && /com\.example\.helper/.test(a2.body.task) && a2.body.maxSteps > 0, JSON.stringify(a2).slice(0, 400));
  await ev(() => { agentAskClose(); window.__ai.queue.length = 0; const q = window.__ai.queue; q.push({ match: sp => /v2\/tasks$/.test(sp.url), status: 202, body: JSON.stringify({ id: '3c90c3cc-0d44-4b50-8888-8dd25736052b' }) }); q.push({ match: sp => /tasks\/3c90c3cc-0d44-4b50-8888-8dd25736052b/.test(sp.url), status: 200, body: '{"status":"failed"}' }); askAgentAbout('package', 'com.x.z', []); }); await sleep(600);
  check('9. a failed task is said in words', await ev(() => /failed before it had an answer/.test(document.getElementById('agentAskAi').innerText)), await ev(() => document.getElementById('agentAskAi').innerText));
  await ev(() => agentAskClose());

  // ---- they are not chat agents ----
  const lists = await ev(() => ({ cmd: txAgentDef('crawl4ai').askOnly && txAgentDef('browseruse').askOnly, chatOpts: TX_AGENTS.filter(a => a.api !== 'info' && !a.askOnly).map(a => a.id) }));
  check('10. neither is listed as a chat agent', lists.cmd && lists.chatOpts.indexOf('crawl4ai') < 0 && lists.chatOpts.indexOf('browseruse') < 0);
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
