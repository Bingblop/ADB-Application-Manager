// v7.9 Grok, Muse, Deepseek and Kilo Code, plus the Effort drop-down's actual effect on the request sent to each provider's
// own API: Anthropic's extended-thinking budget, OpenAI/xAI's reasoning_effort, Gemini's thinkingConfig, and the agents
// that have no such knob (Deepseek, Muse) simply not getting one. Kilo Code (no confirmed CLI) is an explained entry only.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.addInitScript(() => { window.__tx.info.termux = { installed: false, permission: false }; });   // a plain device: Termux is not set up, so the default shell is still Working mode
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.priv);
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };
  const lastReq = () => page.evaluate(() => { const r = window.__ai.reqs.filter(q => /(\/chat\/completions|\/messages|generateContent)/i.test(q.url)).pop(); return r && r.json; });

  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');

  // ---------------------------------------------------------------- the list
  const groups = await page.evaluate(() => [...document.getElementById('txAgent').children].map(o => o.tagName === 'OPTGROUP' ? o.label + ': ' + [...o.children].map(c => c.textContent).join(', ') : o.textContent));
  console.log('1. Grok, Muse and Deepseek are in API Key Required; Kilo Code is in Free Open-Source:', JSON.stringify(groups));

  // ---------------------------------------------------------------- Kilo Code: explained only, no chat
  await page.selectOption('#txAgent', 'kilocode'); await sleep(60);
  console.log('2. Kilo Code opens an explainer, not a Connect sheet for chatting:', await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show')),
    /editor extension/.test(await page.locator('#txConnectBody').innerText()));
  await page.evaluate(() => txCloseConnect());

  // ---------------------------------------------------------------- Grok, Muse, Deepseek: connect and chat, each its own host
  await page.evaluate(() => {
    window.__ai.keyAnswer = (p, k) => /^(xai-|LLM\||sk-)GOOD/.test(k)
      ? { ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'm1' }] }) }
      : { ok: false, status: 401, body: JSON.stringify({ error: { message: 'bad key' } }) };
  });
  const cases = [
    ['grok', 'xai-GOODKEY0123456789abcdef', 'api.x.ai'],
    ['muse', 'LLM|GOODKEY0123456789|abcdef', 'api.llama.com'],
    ['deepseek', 'sk-GOODKEY0123456789abcdef', 'api.deepseek.com'],
  ];
  for (const [id, key, host] of cases) {
    await page.selectOption('#txAgent', id); await sleep(60);
    console.log('3. ' + id + ": its Connect sheet names its own host (" + host + "):", (await page.locator('#txConnectBody').innerText()).includes(host));
    await page.fill('#txKeyInput', key); await page.click('#txKeyTestBtn'); await sleep(100);
    console.log('   a good key connects it:', !(await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show'))));
    await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('hi from ' + id));
    await say('hello'); await idle();
    const req = await lastReq();
    console.log('   the request went to its own address:', req ? 'ok' : 'MISSING');
    await page.evaluate(() => txNewChat());
  }

  // ---------------------------------------------------------------- Effort: Low/Balanced/High actually change the request
  // Claude (Anthropic): thinking budget appears at Balanced/High, not at Low; max_tokens grows with it
  await page.evaluate(() => {
    window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'claude-opus-5-5' }] }) });
  });
  await page.selectOption('#txAgent', 'claude'); await sleep(60);
  await page.fill('#txKeyInput', 'sk-ant-GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(100);
  console.log('4. Claude defaults to Balanced the first time it is picked:', await page.inputValue('#txEffort'));
  const claudeTurn = async () => { await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('ok')); await say('hi'); await idle(); return lastReq(); };
  await page.selectOption('#txEffort', 'low'); let r = await claudeTurn();
  console.log('   Low: no thinking block:', !r.thinking, r.max_tokens);
  await page.selectOption('#txEffort', 'high'); r = await claudeTurn();
  console.log('   High: a bigger thinking budget, and max_tokens grows past it:', JSON.stringify(r.thinking), r.max_tokens > r.thinking.budget_tokens);

  // ChatGPT: reasoning_effort follows the drop-down directly (low/medium/high)
  await page.evaluate(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'gpt-6.1-sol' }] }) }); });
  await page.selectOption('#txAgent', 'chatgpt'); await sleep(60);
  await page.fill('#txKeyInput', 'sk-GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(100);
  const gptTurn = async () => { await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('ok')); await say('hi'); await idle(); return lastReq(); };
  await page.selectOption('#txEffort', 'balanced'); r = await gptTurn();
  console.log('5. ChatGPT, Balanced: reasoning_effort medium:', r.reasoning_effort);
  await page.selectOption('#txEffort', 'high'); r = await gptTurn();
  console.log('   High: reasoning_effort high:', r.reasoning_effort);

  // Gemini: thinkingConfig.thinkingBudget: 0 at Low, -1 (dynamic) at Balanced, a fixed budget at High
  await page.evaluate(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ models: [{ name: 'models/gemini-3.8-flash', supportedGenerationMethods: ['generateContent'] }] }) }); });
  await page.selectOption('#txAgent', 'gemini'); await sleep(60);
  await page.fill('#txKeyInput', 'AIzaGOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(150);
  const gemTurn = async () => { await page.evaluate(s => window.__ai.queue.push({ match: sp => /generateContent/.test(sp.url), sse: s }), tx.geminiSse('ok')); await say('hi'); await idle(); return lastReq(); };
  await page.selectOption('#txEffort', 'low'); r = await gemTurn();
  console.log('6. Gemini, Low: thinkingBudget 0 (off):', r.generationConfig.thinkingConfig.thinkingBudget);
  await page.selectOption('#txEffort', 'balanced'); r = await gemTurn();
  console.log('   Balanced: thinkingBudget -1 (dynamic):', r.generationConfig.thinkingConfig.thinkingBudget);
  await page.selectOption('#txEffort', 'high'); r = await gemTurn();
  console.log('   High: a fixed, larger budget:', r.generationConfig.thinkingConfig.thinkingBudget);

  // Deepseek: the drop-down is still there and remembered, but nothing effort-shaped is sent (no real knob for it)
  // (already connected by the loop above, so picking it again goes straight to chat, no Connect sheet)
  await page.selectOption('#txAgent', 'deepseek'); await sleep(60);
  await page.selectOption('#txEffort', 'high');
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('ok'));
  await say('hi'); await idle();
  r = await lastReq();
  console.log('7. Deepseek: Effort is kept (High) but sends no reasoning_effort/thinking field:', await page.inputValue('#txEffort'), r.reasoning_effort === undefined && r.thinking === undefined);

  console.log('page errors:', errors.length ? errors : 'none');
  env.close();
  await b.close();
})();
