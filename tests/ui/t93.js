// v7.8 Coding Agents: Perplexity, added after ChatGPT was asked to be double-checked and Perplexity was found missing from
// the screenshots. An API-key-only agent (no official CLI, so no "Sign in with subscription" tab), OpenAI-shaped chat
// completions at its own fixed address, and ChatGPT confirmed still in the list and still working.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.priv);
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };
  const lastReq = () => page.evaluate(() => { const r = window.__ai.reqs.filter(q => /\/chat\/completions$/.test(q.url)).pop(); return r; });

  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');

  // ---------------------------------------------------------------- the list
  const groups = await page.evaluate(() => [...document.getElementById('txAgent').children].map(o => o.tagName === 'OPTGROUP' ? o.label + ': ' + [...o.children].map(c => c.textContent).join(', ') : o.textContent));
  console.log('1. ChatGPT and Perplexity are both in API Key Required:', JSON.stringify(groups.find(g => g.startsWith('API Key Required'))));

  // ---------------------------------------------------------------- connecting: no CLI, so no subscription tab
  await page.evaluate(() => {
    window.__ai.keyAnswer = (p, k) => k === 'pplx-GOODKEY0123456789abcdef'
      ? { ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'sonar-pro' }, { id: 'sonar' }, { id: 'sonar-reasoning-pro' }] }) }
      : { ok: false, status: 401, body: JSON.stringify({ error: { message: 'Invalid API key' } }) };
  });
  await page.selectOption('#txAgent', 'perplexity'); await sleep(80);
  console.log('2. choosing it opens its Connect sheet, with no tab bar (no CLI to sign in with):', await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show')), (await page.locator('#txConnectBody .tx-tabs').count()) === 0);
  console.log('   it says where the key goes:', /only ever sent to.*api\.perplexity\.ai/.test(await page.locator('#txConnectBody').innerText()));
  await page.fill('#txKeyInput', 'pplx-wrong'); await page.click('#txKeyTestBtn'); await sleep(80);
  console.log('3. a refused key is explained:', await page.locator('#txAlertTitle').innerText());
  await page.locator('#txAlertBtns button', { hasText: 'Try again' }).click(); await sleep(30);
  await page.fill('#txKeyInput', 'pplx-GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(120);
  console.log('4. a good key: sheet closes, models come from the test itself:', !(await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show'))),
    JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txModel option')].map(o => o.value))));
  console.log('   the page keeps nothing of the key:', await page.evaluate(() => !Object.values(window.__kv).some(v => String(v).includes('GOODKEY'))));

  // ---------------------------------------------------------------- a chat turn: OpenAI-shaped streaming, its own address
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('Perplexity can search the web; here it just answers.'));
  await say('what can you do?'); await idle();
  const req = await lastReq();
  console.log('5. the request: its own address, the Bearer key never shown to the page, system role (not developer), streamed:', req.url, req.json.model, req.json.messages[0].role, req.json.stream);
  console.log('   the reply streamed in:', (await page.locator('#txScreen-priv').innerText()).trim().split('\n').pop());

  // ---------------------------------------------------------------- it shows up in Settings too (built from the same agent list)
  await page.evaluate(() => txOpenSettings()); await sleep(80);
  console.log('6. Settings lists it with its key hint:', await page.evaluate(() => [...document.querySelectorAll('#txSettingsBody .tx-row-name')].some(e => e.textContent === 'Perplexity')),
    await page.evaluate(() => { const row = [...document.querySelectorAll('#txSettingsBody .tx-row')].find(r => r.querySelector('.tx-row-name') && r.querySelector('.tx-row-name').textContent === 'Perplexity'); return row ? row.querySelector('.tx-row-sub').textContent : null; }));
  await page.evaluate(() => txCloseSettings());

  // ---------------------------------------------------------------- Help lists its website too
  await page.evaluate(() => txOpenHelp()); await sleep(50);
  console.log('7. Help lists a Perplexity website button, and still lists ChatGPT\'s:', await page.evaluate(() => [...document.querySelectorAll('#txHelpBody .tx-links button')].map(b => b.textContent)));
  await page.evaluate(() => txCloseHelp());

  console.log('page errors:', errors.length ? errors : 'none');
  env.close();
  await b.close();
})();
