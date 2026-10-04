// v7.8 Terminal sheets and the other ways to connect: Settings (keys by hint only, default agent / model / shell, what agents may
// do), Help (every agent's website), the Termux setup checklist (install, allow, permission refused for good, the connection test),
// the free and open-source options (Jan found on this phone by itself, AnythingLLM asking for its key, Ollama installed through
// Termux), subscription sign-in through the providers' own command-line tools (install steps, the sign-in window, chatting through
// them, the saved key handed over only to its own tool), Copilot, Cursor's cloud agents, and DroidMind / Leon explained.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.addInitScript(() => {
    window.__ai.vault = {
      claude: { key: true, hint: 'sk-ant-…aaaa', base: '', savedAt: 1790000000000, readable: true },
      cursor: { key: true, hint: 'key_…dddd', base: '', savedAt: 1790000000000, readable: true }
    };
    window.__tx.realFor.termux = false;             // no pkg or proot here: Termux commands are recorded and answered
    window.__tx.fake = (b, cmd) => {
      const dir = window.__tx.dirs[b];
      if (/claude -p/.test(cmd)) return { out: 'Claude Code says: done.\n', exit: 0, cwd: dir };
      if (/copilot -p/.test(cmd)) return { out: 'Copilot: here you go\n', exit: 0, cwd: dir };
      if (/gemini -p/.test(cmd)) return { out: 'bash: gemini: command not found\n', exit: 127, cwd: dir };
      return { out: '', exit: 0, cwd: dir };
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const shown = id => page.evaluate(i => document.getElementById(i).classList.contains('show'), id);
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };
  const termuxRuns = () => page.evaluate(() => window.__tx.runs.filter(r => r.b === 'termux' && !r.quiet).map(r => r.cmd));
  const scrT = async () => (await page.locator('#txScreen-termux').innerText()).split(env.base).join('<tmp>');

  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');

  // ---------------------------------------------------------------- Help
  await page.click('#txHelpBtn'); await sleep(30);
  console.log('1. Help opens with its sections:', await shown('txHelpModal'), JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txHelpBody h4')].map(h => h.textContent))));
  const links = await page.evaluate(() => [...document.querySelectorAll('#txHelpBody .tx-links button')].map(x => x.textContent));
  console.log('   a link to every agent\'s website (and Termux):', JSON.stringify(links));
  await page.locator('#txHelpBody .tx-links button', { hasText: 'OpenCode' }).click();
  console.log('   a link opens in the browser:', JSON.stringify(await page.evaluate(() => window.__tx.opens.slice(-1))));
  await page.evaluate(() => txCloseHelp());

  // ---------------------------------------------------------------- Settings
  await page.click('#txSettingsBtn'); await sleep(30);
  console.log('2. Settings: the sections:', await shown('txSettingsModal'), JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txSettingsBody .tx-sheet-sec')].map(h => h.textContent))));
  console.log('   keys by their hint only:', JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txSettingsBody .tx-row')].slice(0, 8).map(r => r.querySelector('.tx-row-name').textContent + ': ' + r.querySelector('.tx-row-sub').textContent + ' [' + r.querySelector('button').textContent + ']'))));
  await page.locator('#txSettingsBody select').first().selectOption('claude');
  console.log('   the default agent is stored:', JSON.parse(await page.evaluate(() => window.__kv.tx_settings)).defaultAgent);
  const modelSel = page.locator('#txSettingsBody .tx-row', { hasText: 'Claude (Anthropic)' }).locator('select');
  console.log('   a default model can be set for each connected agent:', await modelSel.count() >= 1);
  await modelSel.last().selectOption('claude-opus-5-5');
  console.log('   ... stored, and in use now:', JSON.parse(await page.evaluate(() => window.__kv.tx_settings)).models.claude, JSON.parse(await page.evaluate(() => window.__kv.tx_state)).models.claude);
  const sw = await page.evaluate(() => [...document.querySelectorAll('#txSettingsBody .switch-row')].map(r => r.querySelector('.switch-title').textContent + '=' + r.querySelector('input').checked));
  console.log('   switches and their defaults:', JSON.stringify(sw));
  await page.locator('#txSettingsBody .switch-row', { hasText: 'Ask before running commands' }).locator('.switch-track').click();
  console.log('   turning one off is stored:', JSON.parse(await page.evaluate(() => window.__kv.tx_settings)).askRun);
  await page.evaluate(() => txCloseSettings());

  // ---------------------------------------------------------------- with "ask before running" off
  await page.selectOption('#txAgent', 'claude'); await sleep(50);
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Running it.\n<run>echo no-question</run>'));
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Done.'));
  await say('run it'); await until(() => !txChat.busy);
  console.log('3. with Ask before running off, commands run without a card, and the default model is used:', (await page.locator('#txScreen-priv').innerText()).includes('no-question\n'), await page.locator('#txScreen-priv .tx-card').count(), await page.evaluate(() => window.__ai.reqs.filter(r => /messages$/.test(r.url))[0].json.model));
  await page.evaluate(() => { txSet('askRun', true); });

  // ---------------------------------------------------------------- Termux setup
  await page.evaluate(() => { window.__tx.info.termux = { installed: false, permission: false }; });
  await page.evaluate(() => txOpenTermuxSetup()); await sleep(30);
  const steps = async () => JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txTermuxBody .tx-check')].map(c => c.querySelector('.tx-check-n').textContent + ' ' + c.querySelector('.tx-check-title').textContent + (c.querySelector('.mode-btn-row') ? ' [' + [...c.querySelectorAll('button')].map(x => x.textContent + (x.disabled ? '(off)' : '')).join(', ') + ']' : ''))));
  console.log('4. Termux missing:', await steps());
  console.log('   its sub text:', JSON.stringify(await page.locator('#txTermuxBody .tx-check-sub').first().innerText()));
  await page.evaluate(() => { window.__tx.info.termux = { installed: true, permission: false, version: '0.118.3', installer: 'org.fdroid.fdroid' }; window.__tx.permAnswer = [false, false]; txTermuxChanged(); });
  console.log('   installed, not allowed yet:', await steps());
  console.log('   the command for step 3:', JSON.stringify(await page.locator('#txTermuxBody .tx-sheet-code').innerText()));
  await page.locator('#txTermuxBody button', { hasText: 'Allow' }).click(); await sleep(60);
  console.log('   Android refuses for good: the way to allow it by hand:', await shown('txAlertModal'), JSON.stringify(await page.locator('#txAlertBody').innerText()), JSON.stringify(await page.locator('#txAlertBtns').innerText()));
  await page.locator('#txAlertBtns button', { hasText: 'App info' }).click();
  console.log('   App info opens this app\'s page:', JSON.stringify(await page.evaluate(() => window.__tx.appActions.slice(-1))));
  await page.evaluate(() => { window.__tx.permAnswer = [true, false]; });
  await page.locator('#txTermuxBody button', { hasText: 'Allow' }).click(); await sleep(60);
  console.log('   allowed:', await steps());
  await page.evaluate(() => { window.__tx.probeAnswer = { ok: false, message: 'Termux: allow-external-apps property is not set', needsExternalApps: true }; });
  await page.locator('#txTermuxBody button', { hasText: 'Test' }).click(); await sleep(60);
  console.log('   the test while Termux still refuses other apps:', JSON.stringify(await page.locator('#txTermuxBody .tx-check').nth(4).locator('.tx-check-sub').innerText()));
  await page.evaluate(() => { window.__tx.probeAnswer = { ok: true, message: '' }; });
  await page.locator('#txTermuxBody button', { hasText: 'Test' }).click(); await sleep(60);
  console.log('   and once it is set:', await steps());
  await page.locator('#txTermuxBody button', { hasText: 'Use Termux as the shell' }).click(); await until(() => txSess.termux.st === 'ready');
  console.log('   Use Termux as the shell:', await page.locator('#txShell').inputValue(), !(await shown('txTermuxModal')));
  await page.evaluate(() => { const s = document.getElementById('txShell'); s.value = 'priv'; txPickShell('priv'); });

  // ---------------------------------------------------------------- Jan, found on this phone
  await page.evaluate(() => window.__ai.queue.push({ match: sp => sp.method === 'GET' && sp.url === 'http://127.0.0.1:1337/v1/models' && !sp.auth, body: JSON.stringify({ object: 'list', data: [{ id: 'qwen3-coder-7b', object: 'model' }, { id: 'llama-4-8b', object: 'model' }] }) }));
  await page.selectOption('#txAgent', 'jan'); await until(() => txState.agent === 'jan' && !document.getElementById('txConnectModal').classList.contains('show'));
  console.log('5. Jan: looks on this phone by itself, finds it, saves the address and is ready:', JSON.stringify(await page.evaluate(() => window.__ai.vault.jan)), JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txModel option')].map(o => o.value))));
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /1337\/v1\/chat\/completions$/.test(sp.url), sse: s }), tx.openaiSse('Local and free.', { usage: false }));
  await say('hi jan'); await until(() => !txChat.busy);
  const j = await page.evaluate(() => window.__ai.reqs.filter(r => /chat\/completions$/.test(r.url)).pop());
  console.log('   chatting: the saved address, its own provider name for the app, a plain system prompt:', j.url, j.auth, j.json.model, j.json.messages[0].role, j.json.stream_options === undefined);

  // ---------------------------------------------------------------- AnythingLLM: a key is needed
  await page.evaluate(() => window.__ai.queue.push({ match: sp => sp.url === 'http://127.0.0.1:3001/api/v1/openai/models', status: 403, body: JSON.stringify({ error: 'No valid api key found.' }) }));
  await page.selectOption('#txAgent', 'anythingllm'); await sleep(150);
  console.log('6. AnythingLLM: found, but it needs a key:', JSON.stringify(await page.locator('#txProbeStatus').innerText()));
  await page.fill('#txKeyInput', 'ALLM-KEY-123456'); await page.click('#txKeyTestBtn'); await sleep(60);
  console.log('   Connect tests the key at that address:', JSON.stringify(await page.evaluate(() => window.__ai.tests.slice(-1)[0])), !(await shown('txConnectModal')));

  // ---------------------------------------------------------------- Ollama, installed through Termux
  await page.selectOption('#txAgent', 'ollama'); await sleep(120);
  console.log('7. Ollama not running here yet:', JSON.stringify(await page.locator('#txProbeStatus').innerText()));
  await page.locator('#txConnectBody button', { hasText: 'Install Ollama in Termux' }).click();
  await until(() => !txRuns.termux && window.__tx.runs.some(r => /ollama pull/.test(r.cmd))); await sleep(200);
  console.log('   the steps, run one by one in the Termux shell:', JSON.stringify(await termuxRuns()));
  console.log('   and on its screen:', JSON.stringify((await scrT()).split('\n').filter(l => /^(▶|✓|--)/.test(l))));
  await page.evaluate(() => txCloseConnect());

  // ---------------------------------------------------------------- Claude, signed in through Claude Code
  await page.evaluate(() => { const s = document.getElementById('txShell'); s.value = 'priv'; txPickShell('priv'); });
  await page.evaluate(() => txOpenConnect('claude')); await sleep(30);
  await page.locator('#txConnectBody .sdb-tab', { hasText: 'Sign in with subscription' }).click(); await sleep(30);
  console.log('8. Claude by subscription: why it goes through Claude Code:', JSON.stringify(await page.locator('#txConnectBody p').first().innerText()));
  console.log('   the steps:', JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txConnectBody .tx-check')].map(c => c.querySelector('.tx-check-n').textContent + ' ' + c.querySelector('.tx-check-title').textContent))));
  const before = (await termuxRuns()).length;
  await page.locator('#txConnectBody .tx-check', { hasText: 'Install Claude Code' }).locator('button').click();
  await until(() => window.__tx.runs.some(r => /claude\.ai\/install\.sh/.test(r.cmd)) && !txRuns.termux); await sleep(100);
  console.log('   Install: Debian in Termux, then Anthropic\'s installer inside it:', JSON.stringify((await termuxRuns()).slice(before)));
  console.log('   then: sign in, or use it now:', JSON.stringify(await page.locator('#txScreen-termux .tx-btnrow').last().innerText()));
  await page.locator('#txScreen-termux .tx-btnrow button', { hasText: 'Sign in' }).last().click(); await sleep(30);
  console.log('   Sign in opens Claude Code in a Termux window:', JSON.stringify(await page.evaluate(() => window.__tx.opens.slice(-1)[0])));
  await page.locator('#txScreen-termux .tx-btnrow button', { hasText: 'Use it now' }).last().click(); await sleep(30);
  console.log('   in use:', JSON.parse(await page.evaluate(() => window.__kv.tx_settings)).connect.claude, await page.locator('#txAgent').inputValue());
  await say('fix the build, please'); await until(() => !txChat.busy && !txRuns.termux);
  let cl = await page.evaluate(() => window.__tx.runs.filter(r => /claude -p/.test(r.cmd)).pop());
  console.log('   a message goes to claude -p in the Debian container, with the saved key handed to it as ANTHROPIC_API_KEY (by the app):');
  console.log('   ', JSON.stringify(cl.cmd.replace(env.base, '<tmp>')), JSON.stringify(cl.env));
  console.log('   its answer:', JSON.stringify((await scrT()).trim().split('\n').slice(-2)));
  await say('and run the tests'); await until(() => !txChat.busy && !txRuns.termux);
  cl = await page.evaluate(() => window.__tx.runs.filter(r => /claude -p/.test(r.cmd)).pop());
  console.log('   the next one continues the same Claude Code conversation:', /--continue/.test(cl.cmd), 'with the model chosen:', cl.cmd.includes("--model '\\''claude-opus-5-5'\\''"));

  // ---------------------------------------------------------------- Gemini CLI not installed
  await page.evaluate(() => { txSettings.connect.gemini = 'cli'; txSaveSettings(); });
  await page.selectOption('#txAgent', 'gemini'); await sleep(50);
  await say('hello gemini'); await until(() => !txChat.busy && !txRuns.termux);
  const gm = await page.evaluate(() => window.__tx.runs.filter(r => /gemini -p/.test(r.cmd)).pop());
  console.log('9. Gemini CLI runs in Termux itself (no container), its own sign-in (no key saved):', JSON.stringify(gm.cmd), JSON.stringify(gm.env));
  console.log('   not installed: said, with the install button:', JSON.stringify((await scrT()).trim().split('\n').filter(l => /does not seem/.test(l)).pop()), JSON.stringify(await page.locator('#txScreen-termux .tx-btnrow').last().innerText()));

  // ---------------------------------------------------------------- Copilot
  await page.evaluate(() => { window.__ai.keyAnswer = (p, k) => ({ ok: true, status: 200, body: JSON.stringify({ login: 'octocat' }) }); });
  await page.selectOption('#txAgent', 'copilot'); await sleep(50);
  console.log('10. Copilot: its sheet:', JSON.stringify(await page.locator('#txConnectBody p').first().innerText()));
  await page.fill('#txKeyInput', 'github_pat_11ABCDEFG0123456789_abcdefghijklmnop'); await page.click('#txKeyTestBtn'); await sleep(60);
  console.log('    the token is tested against GitHub and kept for the CLI:', JSON.stringify(await page.evaluate(() => window.__ai.tests.slice(-1)[0].provider)), JSON.stringify(await page.locator('#txKeyStatus').innerText()));
  await page.locator('#txConnectBody button', { hasText: 'Use Copilot CLI' }).click(); await sleep(30);
  await say('explain this repo'); await until(() => !txChat.busy && !txRuns.termux);
  const cp = await page.evaluate(() => window.__tx.runs.filter(r => /copilot -p/.test(r.cmd)).pop());
  console.log('    a message goes to copilot -p, the token as COPILOT_GITHUB_TOKEN:', JSON.stringify(cp.cmd.split(env.base).join('<tmp>')), JSON.stringify(cp.env));

  // ---------------------------------------------------------------- Cursor: cloud agents
  await page.evaluate(() => { const s = document.getElementById('txShell'); s.value = 'priv'; txPickShell('priv'); });
  await page.evaluate(() => {
    window.__ai.queue.push({ match: sp => sp.method === 'POST' && sp.url === 'https://api.cursor.com/v1/agents', body: JSON.stringify({ agent: { id: 'bc-123', latestRunId: 'run-1' }, run: { id: 'run-1', status: 'CREATING' } }) });
    window.__ai.queue.push({ match: sp => /agents\/bc-123\/runs\/run-1\/stream$/.test(sp.url), sse: [
      'event: status\ndata: {"runId":"run-1","status":"RUNNING"}\n\n', 'id: 1-0\nevent: assistant\ndata: {"text":"I\'ll look at the "}\n\n',
      'id: 2-0\nevent: tool_call\ndata: {"callId":"c1","name":"read_file","status":"running","args":{"path":"README.md"}}\n\n',
      'id: 3-0\nevent: assistant\ndata: {"text":"README now."}\n\n', 'id: 4-0\nevent: result\ndata: {"runId":"run-1","status":"FINISHED","text":"I read the README: it describes the app."}\n\nid: 4-0\nevent: done\ndata: {}\n\n'] });
    window.__ai.queue.push({ match: sp => sp.method === 'GET' && /api\.cursor\.com\/v1\/models$/.test(sp.url), keep: true, body: JSON.stringify({ items: [{ id: 'composer-2', displayName: 'Composer 2' }] }) });
  });
  await page.selectOption('#txAgent', 'cursor'); await sleep(80);
  await say('summarize the readme'); await until(() => !txChat.busy);
  const ca = await page.evaluate(() => window.__ai.reqs.filter(r => r.url === 'https://api.cursor.com/v1/agents').pop());
  console.log('11. Cursor: a cloud agent is started with the message (and the chosen model):', ca.auth, JSON.stringify(ca.json));
  console.log('    its stream shows the text and the tools it used; the final reply stays:', JSON.stringify((await page.locator('#txScreen-priv').innerText()).trim().split('\n').slice(-1)));
  await page.evaluate(() => {
    window.__ai.queue.push({ match: sp => sp.method === 'POST' && /agents\/bc-123\/runs$/.test(sp.url), body: JSON.stringify({ run: { id: 'run-2', status: 'CREATING' } }) });
    window.__ai.queue.push({ match: sp => /runs\/run-2\/stream$/.test(sp.url), sse: ['event: assistant\ndata: {"text":"Working on it"}\n\n'], hold: true });
    window.__ai.queue.push({ match: sp => /runs\/run-2\/cancel$/.test(sp.url), body: '{"id":"run-2"}' });
  });
  await say('now add a section'); await sleep(150);
  console.log('    a follow-up continues the same agent (a new run):', JSON.stringify(await page.evaluate(() => window.__ai.reqs.filter(r => /agents\/bc-123\/runs$/.test(r.url)).map(r => r.json))));
  await page.click('#txSendBtn'); await until(() => !txChat.busy); await sleep(60);
  console.log('    STOP cancels the run at Cursor too:', JSON.stringify(await page.evaluate(() => window.__ai.reqs.filter(r => /cancel$/.test(r.url)).map(r => r.method + ' ' + r.url))));

  // ---------------------------------------------------------------- DroidMind and Leon
  await page.selectOption('#txAgent', 'droidmind'); await sleep(50);
  console.log('12. DroidMind is explained (a tool for other agents), and the chosen agent stays:', await shown('txConnectModal'), await page.locator('#txConnectTitle').innerText(), await page.locator('#txAgent').inputValue());
  console.log('    ', JSON.stringify(await page.locator('#txConnectBody p').first().innerText()));
  await page.evaluate(() => txCloseConnect());
  await page.selectOption('#txAgent', 'leon'); await sleep(50);
  console.log('    Leon too:', JSON.stringify(await page.locator('#txConnectBody p').first().innerText()), await page.locator('#txAgent').inputValue());
  await page.evaluate(() => txCloseConnect());

  console.log('errors:', JSON.stringify(errors));
  env.close();
  await b.close();
})();
