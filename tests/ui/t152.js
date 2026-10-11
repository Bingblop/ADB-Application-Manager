// v7.12.8: Exa as a research agent for the Ask agent buttons: it is in the Web research group of the Default Ask Agent list, its key is tested and kept for api.exa.ai only,
// it is not a chat agent in the Terminal, and the answer comes from Exa's /answer (one POST) with its citations.
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

  await ev(() => { switchView('prefs'); askAgentBuild(); }); await sleep(200);
  const g = await ev(() => [...document.querySelectorAll('#askAgentSelect optgroup')].map(x => x.label + ':' + [...x.querySelectorAll('option')].map(o => o.value).join('+')));
  check('1. Exa is in the Web research group, right after Perplexity', g[0] === 'Web research (recommended):perplexity+exa+browseruse+crawl4ai', JSON.stringify(g));
  await ev(() => switchView('terminal')); await sleep(300);
  const before = await ev(() => ({ st: txState.agent, mode: txState.mode }));
  await page.selectOption('#txAgent', 'exa'); await sleep(100);
  const sheet = await ev(() => ({ open: document.getElementById('txConnectModal').classList.contains('show'), text: document.getElementById('txConnectBody').innerText }));
  check('2. choosing Exa in the Terminal opens its key sheet (api.exa.ai, what it is, the test costs a fraction of a cent) and does not make it the chat agent', sheet.open && /api\.exa\.ai/.test(sheet.text) && /search engine built for AI/.test(sheet.text) && /fraction of a cent/.test(sheet.text) && await ev(() => txState.agent) === before.st, JSON.stringify(sheet.text.slice(0, 200)));
  await ev(() => { window.__ai.keyAnswer = (p, k) => p === 'exa' && /^GOOD/.test(k) ? { ok: true, status: 200, body: '{"results":[]}' } : { ok: false, status: 401, body: '{"error":"bad key"}' }; });
  await page.fill('#txKeyInput', 'GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(250);
  const done = await ev(() => ({ open: document.getElementById('txConnectModal').classList.contains('show'), prov: window.__ai.tests.map(t => t.provider), st: txState.agent, ready: txAgentReady(txAgentDef('exa')), vault: Object.keys(window.__ai.vault || {}) }));
  check('3. a good key connects it, only the provider "exa" was tested, and the Terminal stays as it was', !done.open && done.prov.join() === 'exa' && done.st === before.st && done.ready, JSON.stringify(done));
  await ev(() => { askAgentChoose('exa'); window.__ai.queue.push({ match: sp => /^https:\/\/api\.exa\.ai\/answer$/.test(sp.url) && sp.method === 'POST', status: 200, body: JSON.stringify({ answer: 'It is the Bixby helper from Samsung.', citations: [{ title: 'Samsung Bixby', url: 'https://samsung.example/bixby' }] }) }); askAgentAbout('package', 'com.samsung.android.bixby.agent', ['App name: Bixby']); }); await sleep(500);
  const a = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, req: window.__ai.reqs.filter(r => /api\.exa\.ai\/answer/.test(r.url)).map(r => ({ method: r.method, auth: r.spec ? r.spec.auth : r.auth, body: String(r.body || (r.spec && r.spec.body) || '').slice(0, 80) })) }));
  check('4. the Ask agent window gets Exa\'s answer with its sources; one POST to /answer carrying the vault name, never the key', /Bixby helper/.test(a.t) && /Sources/.test(a.t) && /samsung\.example/.test(a.t) && a.req.length === 1 && a.req[0].method === 'POST' && /"query"/.test(a.req[0].body) && !/GOODKEY/.test(JSON.stringify(a.req)), JSON.stringify(a));
  await ev(() => { agentAskClose(); window.__ai.queue.push({ match: sp => /api\.exa\.ai\/answer/.test(sp.url), status: 200, body: JSON.stringify({ answer: '' }) }); askAgentAbout('package', 'com.x.y', []); }); await sleep(500);
  check('5. no answer is said in words', await ev(() => /found no answer/.test(document.getElementById('agentAskAi').innerText)), await ev(() => document.getElementById('agentAskAi').innerText));
  await ev(() => { agentAskClose(); window.__ai.queue.push({ match: sp => /api\.exa\.ai\/answer/.test(sp.url), status: 402, body: '{"error":"no credits"}' }); askAgentAbout('package', 'com.x.y', []); }); await sleep(500);
  check('6. a refused call is reported like any agent\'s', await ev(() => document.getElementById('agentAskAi').classList.contains('is-err') && document.getElementById('agentAskAi').innerText.length > 5));
  await ev(() => agentAskClose());
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
