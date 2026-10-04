// v7.8 Coding Agents, switching and the other providers: one conversation carried from Claude to ChatGPT to Gemini (each in its
// own wire format: Anthropic messages, OpenAI chat completions with a developer prompt, Gemini contents with "model" turns and
// thoughts left out), the model menu and /model, slash commands, !command from the chat, a cut-off step asked for again, the step
// limit, and the conversation (but never a key) kept for the next launch.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.addInitScript(() => {
    // keys already saved (as the app would report them): this script is about switching
    window.__ai.vault = {
      claude: { key: true, hint: 'sk-ant-…aaaa', base: '', savedAt: 1790000000000, readable: true },
      chatgpt: { key: true, hint: 'sk-proj-…bbbb', base: '', savedAt: 1790000000000, readable: true },
      gemini: { key: true, hint: 'AIza…cccc', base: '', savedAt: 1790000000000, readable: true }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const clean = s => String(s).split(env.base).join('<tmp>');
  const scr = async () => clean(await page.locator('#txScreen-priv').innerText());
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.priv);
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };
  const reqs = () => page.evaluate(() => window.__ai.reqs);
  const last = async re => (await reqs()).filter(r => new RegExp(re).test(r.url)).pop();
  const tail = async n => (await scr()).trim().split('\n').slice(-(n || 1));

  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');
  // models are fetched in the background for an agent that already has a key: answer those lists
  await page.evaluate(() => {
    window.__ai.queue.push({ keep: true, match: sp => sp.method === 'GET' && /api\.openai\.com\/v1\/models$/.test(sp.url), body: JSON.stringify({ data: [{ id: 'gpt-6.1-sol' }, { id: 'gpt-6-luna' }, { id: 'text-embedding-4' }, { id: 'gpt-6-astra' }, { id: 'whisper-2' }, { id: 'o9-mini', shutdown_date: '2026-01-01' }] }) });
    window.__ai.queue.push({ keep: true, match: sp => sp.method === 'GET' && /generativelanguage.*\/models\?/.test(sp.url), body: JSON.stringify({ models: [
      { name: 'models/gemini-3.8-flash', displayName: 'Gemini 3.8 Flash', supportedGenerationMethods: ['generateContent', 'countTokens'] },
      { name: 'models/gemini-3.5-flash-lite', displayName: 'Gemini 3.5 Flash-Lite', supportedGenerationMethods: ['generateContent'] },
      { name: 'models/gemini-embedding-2', displayName: 'Embedding', supportedGenerationMethods: ['embedContent'] },
      { name: 'models/gemini-3.8-flash-tts', displayName: 'TTS', supportedGenerationMethods: ['generateContent'] }] }) });
    window.__ai.queue.push({ keep: true, match: sp => sp.method === 'GET' && /anthropic\.com\/v1\/models/.test(sp.url), body: JSON.stringify({ data: [{ id: 'claude-sonnet-5-5', display_name: 'Claude Sonnet 5.5' }, { id: 'claude-opus-5-5', display_name: 'Claude Opus 5.5' }] }) });
  });

  // ---------------------------------------------------------------- Claude
  await page.selectOption('#txAgent', 'claude'); await sleep(100);
  console.log('1. with a saved key the agent is ready at once (no sheet):', !(await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show'))), JSON.stringify(await tail()));
  await page.evaluate(s => window.__ai.queue.unshift({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Hi! I am Claude.'));
  await say('hello, my name is Kim'); await idle();
  console.log('   Claude answers:', JSON.stringify(await tail()));

  // ---------------------------------------------------------------- to ChatGPT, same conversation
  await page.selectOption('#txAgent', 'chatgpt'); await until(() => !!window.__kv.tx_models_chatgpt);
  console.log('2. switching keeps the conversation, and says so:', JSON.stringify(await tail()));
  console.log('   ChatGPT\'s models, without embeddings, speech or retired ones:', JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txModel option')].map(o => o.value))), await page.locator('#txModel').inputValue());
  await page.evaluate(s => window.__ai.queue.unshift({ match: sp => /chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('Nice to meet you, Kim.'));
  await say('what is my name?'); await idle();
  const o = await last('chat/completions$');
  console.log('   the OpenAI request: its host, the saved key by name, a developer prompt, the whole conversation, usage asked for:');
  console.log('   ', o.url, o.auth, o.json.model, o.json.messages[0].role, JSON.stringify(o.json.messages.slice(1).map(m => m.role + ': ' + m.content)), JSON.stringify(o.json.stream_options));
  console.log('   the answer and the tokens:', JSON.stringify(await tail()), (await page.locator('#txState').innerText()).replace(/.*· /, ''));

  // ---------------------------------------------------------------- to Gemini
  await page.selectOption('#txAgent', 'gemini'); await until(() => !!window.__kv.tx_models_gemini);
  console.log('3. Gemini\'s models (only those that chat):', JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txModel option')].map(o => o.value + '=' + o.textContent))));
  await page.evaluate(s => window.__ai.queue.unshift({ match: sp => /streamGenerateContent/.test(sp.url), sse: s }), tx.geminiSse('Your name is Kim, as you said.'));
  await say('remind me?'); await idle();
  const g = await last('streamGenerateContent');
  console.log('   the Gemini request: model in the address, server-sent events, the system prompt apart, "model" for the assistant:');
  console.log('   ', g.url.replace(/\?.*$/, '') + ' ?' + g.url.split('?')[1], g.auth, !!g.json.systemInstruction.parts[0].text, JSON.stringify(g.json.contents.map(c => c.role + ': ' + c.parts[0].text)));
  console.log('   its thoughts are left out of the answer:', JSON.stringify(await tail()));

  // ---------------------------------------------------------------- the model menu and /model
  await page.selectOption('#txModel', 'gemini-3.5-flash-lite'); await sleep(30);
  console.log('4. picking a model:', JSON.stringify(await tail()), JSON.parse(await page.evaluate(() => window.__kv.tx_state)).models.gemini);
  await say('/model gemini-3.1-pro-preview'); await sleep(30);
  console.log('   /model sets one by name (even one not in the list):', JSON.stringify(await tail()), await page.locator('#txModel').inputValue());
  await say('/models'); await sleep(50);
  console.log('   /models lists them:', JSON.stringify(await tail()));
  await say('/agent claude'); await sleep(80);
  console.log('   /agent switches the agent:', await page.locator('#txAgent').inputValue(), JSON.stringify(await tail()));
  await say('/agent nobody'); await sleep(30);
  console.log('   an unknown one:', JSON.stringify(await tail()));

  // ---------------------------------------------------------------- !command, /shell, /chat
  await say('!echo straight from the chat'); await idle();
  console.log('5. !command runs in the shell without leaving the chat:', JSON.stringify(await tail(2)), await page.locator('#txModeBtn').innerText());
  await say('/shell'); await sleep(30);
  console.log('   /shell switches the input to commands:', await page.locator('#txModeBtn').innerText(), await page.locator('#txSendBtn').innerText());
  await page.click('#txModeBtn'); await sleep(30);
  console.log('   the AI / $ button switches back:', await page.locator('#txModeBtn').innerText());
  await say('/sdcard/notes.txt is what I mean'); await sleep(50);
  console.log('   a path at the start of a message is a message, not a command:', await page.evaluate(() => window.__ai.reqs.filter(r => /messages$/.test(r.url)).pop().json.messages.slice(-1)[0].content));
  await idle();

  // ---------------------------------------------------------------- a cut-off step, the step limit
  await page.evaluate(() => { window.__ai.queue = window.__ai.queue.filter(q => q.keep); });
  await page.evaluate(s => window.__ai.queue.unshift({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Writing it.\n<write path="big.txt">\nline 1\nline 2'));
  await page.evaluate(s => window.__ai.queue.splice(1, 0, { match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Sorry, here it is.\n<done>Gave up on the big file.</done>'));
  await say('write a big file'); await idle();
  const cut = await page.evaluate(() => window.__ai.reqs.filter(r => /messages$/.test(r.url)).pop().json.messages.slice(-1)[0].content);
  console.log('6. a step cut off mid-way is never carried out; the model is asked again:', JSON.stringify(cut), !(await page.evaluate(() => window.__tx.runs.some(r => /big\.txt/.test(r.cmd)))));
  console.log('   <done> ends the turn and shows its summary:', JSON.stringify(await tail()));
  await page.evaluate(() => { txSettings.maxSteps = 2; });
  for (let i = 0; i < 3; i++) await page.evaluate(s => window.__ai.queue.unshift({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Next.\n<run>echo loop</run>'));
  await page.evaluate(() => { txChat.allowRun = true; });
  await say('loop'); await idle();
  console.log('7. after the step limit the agent pauses:', JSON.stringify(await tail()));
  await page.evaluate(() => { txSettings.maxSteps = 25; window.__ai.queue = window.__ai.queue.filter(q => q.keep); });

  // ---------------------------------------------------------------- kept for the next launch
  const saved = JSON.parse(await page.evaluate(() => window.__kv.tx_chat));
  console.log('8. the conversation is kept:', saved.msgs.length, 'messages, first:', JSON.stringify(saved.msgs[0]));
  console.log('   no key in anything the page stored:', await page.evaluate(() => !Object.values(window.__kv).some(v => /sk-ant-[a-z0-9]{6}|AIza[0-9A-Za-z]{8}/.test(String(v)))));
  const kv = await page.evaluate(() => JSON.stringify(window.__kv));
  const page2 = await b.newPage({ viewport: { width: 400, height: 860 } });
  page2.on('pageerror', e => errors.push('2: ' + e.message));
  const env2 = await tx.install(page2, { real: true });
  await page2.addInitScript(kv => { window.__kv = JSON.parse(kv); window.__ai && (window.__ai.vault = { claude: { key: true, hint: 'sk-ant-…aaaa', base: '', savedAt: 1, readable: true } }); }, kv);
  await page2.goto(PAGE); await page2.waitForTimeout(300);
  await page2.evaluate(() => switchView('terminal')); await page2.waitForTimeout(200);
  console.log('   after a restart: the same agent, the conversation remembered:', await page2.locator('#txAgent').inputValue(), JSON.stringify((await page2.locator('#txScreen-priv').innerText()).split('\n').find(l => /earlier chat messages/.test(l))));
  await page2.click('#txNewChatBtn'); await page2.waitForTimeout(30);
  console.log('   New chat starts over:', JSON.stringify(JSON.parse(await page2.evaluate(() => window.__kv.tx_chat)).msgs), JSON.stringify((await page2.locator('#txScreen-priv').innerText()).trim().split('\n').pop()));
  await page2.evaluate(() => { txSet('keepChat', false); });
  console.log('   with "Keep the conversation" off nothing is stored:', await page2.evaluate(() => window.__kv.tx_chat));
  env2.close();

  // ---------------------------------------------------------------- None
  await page.selectOption('#txAgent', 'none'); await sleep(30);
  console.log('9. None: commands only, the AI button is off:', await page.locator('#txModeBtn').innerText(), await page.locator('#txModeBtn').isDisabled(), await page.locator('#txModel').isDisabled(), JSON.stringify(await tail()));

  console.log('errors:', JSON.stringify(errors));
  env.close();
  await b.close();
})();
