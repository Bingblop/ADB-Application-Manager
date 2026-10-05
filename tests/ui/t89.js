// v7.8 Coding Agents, an API-key agent (Claude) from the first pick to real work: the Connect sheet, the key test (a refused key
// explained, with Save anyway; a good one saved by the app, never by the page), the streamed chat, and the steps the agent takes:
// commands (Skip, Run, Always allow), files written, edited and read for real in a temporary folder (with a diff to approve), edits
// that do not match, reading outside the folder, /undo, STOP in the middle of an answer, and a provider error explained.
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
  const clean = s => String(s).split(env.base).join('<tmp>');
  const scr = async () => clean(await page.locator('#txScreen-priv').innerText());
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.priv);
  const reply = (text, extra) => page.evaluate(([s, extra]) => window.__ai.queue.push(Object.assign({ match: sp => /\/v1\/messages$/.test(sp.url) }, extra || {}, { sse: s })), [tx.claudeSse(text), extra || null]);
  const lastReq = () => page.evaluate(() => { const r = window.__ai.reqs.filter(q => /\/v1\/messages$/.test(q.url)).pop(); return r && r.json; });
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };

  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');

  // ---------------------------------------------------------------- the list
  const groups = await page.evaluate(() => [...document.getElementById('txAgent').children].map(o => o.tagName === 'OPTGROUP' ? o.label + ': ' + [...o.children].map(c => c.textContent).join(', ') : o.textContent));
  console.log('1. Coding Agents, None first, then the two groups:');
  groups.forEach(g => console.log('   ' + g));
  console.log('   the label above it:', await page.locator('#txPaneTerminal .tx-field-label').first().innerText());

  // ---------------------------------------------------------------- connecting
  await page.evaluate(() => {
    window.__ai.keyAnswer = (p, k) => k === 'sk-ant-api03-GOODKEY-0123456789abcd'
      ? { ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'claude-opus-5-5', display_name: 'Claude Opus 5.5' }, { id: 'claude-sonnet-5-5', display_name: 'Claude Sonnet 5.5' }, { id: 'claude-haiku-4-5', display_name: 'Claude Haiku 4.5' }] }) }
      : { ok: false, status: 401, body: JSON.stringify({ type: 'error', error: { type: 'authentication_error', message: 'invalid x-api-key' }, request_id: 'req_0123' }), headers: { 'request-id': 'req_0123' } };
  });
  await page.selectOption('#txAgent', 'claude'); await sleep(80);
  console.log('2. choosing Claude without a key opens its Connect sheet:', await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show')), await page.locator('#txConnectTitle').innerText());
  console.log('   tabs:', JSON.stringify(await page.locator('#txConnectBody .tx-tabs').innerText()), 'API key first:', await page.locator('#txConnectBody .sdb-tab.active').innerText());
  console.log('   it says where the key goes:', /only ever sent to api\.anthropic\.com/.test(await page.locator('#txConnectBody').innerText()));
  console.log('   the key field hides what is typed:', await page.locator('#txKeyInput').getAttribute('type'));
  await page.fill('#txKeyInput', 'sk-ant-wrong'); await page.click('#txKeyTestBtn'); await sleep(80);
  console.log('3. a refused key: the reason in words, the provider\'s own message and request id as details:');
  console.log('   ', await page.locator('#txAlertTitle').innerText(), '|', await page.locator('#txAlertBody').innerText());
  console.log('   ', JSON.stringify(await page.locator('#txAlertDetail').innerText()), JSON.stringify(await page.locator('#txAlertBtns').innerText()));
  console.log('   the sheet says it too:', await page.locator('#txKeyStatus').innerText());
  await page.locator('#txAlertBtns button', { hasText: 'Try again' }).click(); await sleep(30);
  await page.fill('#txKeyInput', 'sk-ant-api03-GOODKEY-0123456789abcd'); await page.click('#txKeyTestBtn'); await sleep(120);
  console.log('4. a good key: the sheet closes, the models come from the test itself, Sonnet is chosen first:', !(await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show'))),
    JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txModel option')].map(o => o.value))), await page.locator('#txModel').inputValue());
  console.log('   the key was handed to the app\'s own test (provider, key, no address):', JSON.stringify(await page.evaluate(() => window.__ai.tests.map(t => [t.provider, t.key.slice(0, 10), t.base]))));
  console.log('   the page keeps nothing of the key (only the app does):', await page.evaluate(() => !Object.values(window.__kv).some(v => String(v).includes('GOODKEY'))));
  console.log('   chat is on (AI), the button says SEND:', await page.locator('#txModeBtn').innerText(), await page.locator('#txSendBtn').innerText());
  console.log('   the screen:', JSON.stringify((await scr()).trim().split('\n').slice(-2)));

  // ---------------------------------------------------------------- a command: Skip
  await reply('I will list the folder.\n<run>ls -la</run>');
  await say('what is in here?'); await until(() => !!document.querySelector('#txScreen-priv .tx-card .tx-btnrow'));
  const req1 = await lastReq();
  console.log('5. the request: model, a system prompt that describes the shell, the user\'s message, streamed:', req1.model, req1.stream, /Shell: Android sh \(mksh with toybox\), running as the ADB shell user \(uid 2000\)/.test(req1.system), /Working folder: \/tmp\/txmock-[^/]+\/priv\./.test(req1.system), JSON.stringify(req1.messages));
  console.log('   the reply streamed in, the step shown as a card with its shell and folder:', JSON.stringify((await page.locator('#txScreen-priv .tx-card').last().innerText()).replace(env.base, '<tmp>')));
  console.log('   the step itself is not printed as raw text:', !(await scr()).includes('<run>'));
  await page.locator('#txScreen-priv .tx-card button', { hasText: 'Skip' }).click(); await idle();
  console.log('   Skip: nothing runs, the card says so, and the agent waits for the user:', JSON.stringify(await page.locator('#txScreen-priv .tx-card .tx-card-state').last().innerText()), (await scr()).trim().endsWith('[tell Claude what to do instead]'), await page.evaluate(() => window.__ai.reqs.filter(q => /messages$/.test(q.url)).length));

  // ---------------------------------------------------------------- Run, Always allow
  await reply('Listing it now.\n<run>echo step-one; ls</run>');
  await reply('And once more.\n<run>echo step-two</run>');
  await reply('All done: the folder is empty.');
  await say('ok, go ahead'); await until(() => !!document.querySelector('#txScreen-priv .tx-card .tx-btnrow'));
  const req2 = await lastReq();
  console.log('6. the skipped step went back to the model as a denial:', /<result action="run" status="denied">The user did not run it/.test(req2.messages.map(m => m.content).join('\n')), JSON.stringify(req2.messages.map(m => m.role)));
  await page.locator('#txScreen-priv .tx-card button', { hasText: 'Always allow in this chat' }).click(); await idle();
  const t6 = await scr();
  console.log('   Always allow: it runs, and the next command needs no OK:', t6.includes('<tmp>/priv $ echo step-one; ls\nstep-one\n'), t6.includes('<tmp>/priv $ echo step-two\nstep-two\n'), (await page.locator('#txScreen-priv .tx-card').count()) === 2);
  const req3 = await lastReq();
  const res3 = req3.messages[req3.messages.length - 1].content;
  console.log('   each result goes back with the exit status and the folder:', JSON.stringify(res3.replace(env.base, '<tmp>')));
  console.log('   the final answer:', JSON.stringify((await scr()).trim().split('\n').pop()));
  console.log('   tokens in the state line:', (await page.locator('#txState').innerText()).replace(env.base, '<tmp>'));

  // ---------------------------------------------------------------- files: write, edit, read, undo
  await reply('Creating it.\n<write path="hello.py">\nprint("hello")\nprint("world")\n</write>');
  await reply('Created.');
  await say('make hello.py'); await until(() => !!document.querySelector('#txScreen-priv .tx-card:last-of-type .tx-btnrow'));
  console.log('7. a new file: the card shows the lines that will be added:', JSON.stringify((await page.locator('#txScreen-priv .tx-card').last().innerText()).replace(env.base, '<tmp>')));
  await page.locator('#txScreen-priv .tx-card button', { hasText: 'Apply' }).last().click(); await idle();
  console.log('   Apply writes it for real:', JSON.stringify(env.read('priv', 'hello.py')));
  console.log('   the screen says so (and how to take it back):', JSON.stringify((await scr()).split('\n').find(l => l.startsWith('[created'))));
  const writes = await page.evaluate(() => window.__tx.runs.filter(r => r.quiet && /base64 -d/.test(r.cmd)).length);
  console.log('   it went through base64, quietly (no file content on the screen):', writes, !(await scr()).includes('cHJpbnQ'));

  await reply('Changing the second line.\n<edit path="hello.py">\n<<<<<<< SEARCH\nprint("world")\n=======\nprint("there")\nprint("again")\n>>>>>>> REPLACE\n</edit>');
  await reply('Changed.');
  await say('change world to there'); await until(() => document.querySelectorAll('#txScreen-priv .tx-card .tx-btnrow').length > 0);
  console.log('8. an edit: the card shows what goes and what comes, with context:', JSON.stringify((await page.locator('#txScreen-priv .tx-card').last().innerText()).replace(env.base, '<tmp>')));
  await page.locator('#txScreen-priv .tx-card button', { hasText: 'Apply' }).last().click(); await idle();
  console.log('   applied:', JSON.stringify(env.read('priv', 'hello.py')));

  await reply('Trying.\n<edit path="hello.py">\n<<<<<<< SEARCH\nprint("nope")\n=======\nprint("x")\n>>>>>>> REPLACE\n</edit>');
  await reply('Let me read it first.\n<read path="hello.py"/>');
  await reply('Now I see it.');
  await say('another change'); await idle();
  const req4 = await page.evaluate(() => window.__ai.reqs.filter(q => /messages$/.test(q.url)).map(r => r.json.messages[r.json.messages.length - 1].content).slice(-2));
  console.log('9. an edit that does not match is not applied; the model is told why:', JSON.stringify(req4[0]));
  console.log('   reading a file in the folder needs no OK, and returns its lines:', JSON.stringify(req4[1].replace(env.base, '<tmp>')));
  console.log('   the file is unchanged:', JSON.stringify(env.read('priv', 'hello.py')));

  await reply('Checking.\n<read path="/etc/hostname"/>');
  await reply('Fine.');
  await say('look at the host name'); await until(() => document.querySelectorAll('#txScreen-priv .tx-card .tx-btnrow').length > 0);
  console.log('10. reading outside the working folder asks first:', JSON.stringify(await page.locator('#txScreen-priv .tx-card').last().innerText()));
  await page.locator('#txScreen-priv .tx-card button', { hasText: 'Skip' }).last().click(); await idle();
  console.log('    after Skip the agent stops and waits (its next answer is never asked for):', await page.evaluate(() => window.__ai.queue.length));
  await page.evaluate(() => { window.__ai.queue.length = 0; });

  await say('/undo'); await idle(); await sleep(150);
  console.log('11. /undo puts the file back as it was before the last change:', JSON.stringify(env.read('priv', 'hello.py')), JSON.stringify((await scr()).trim().split('\n').pop()));
  await say('/undo'); await idle(); await sleep(150);
  console.log('    once more: the new file is removed again:', env.exists('priv', 'hello.py'), JSON.stringify((await scr()).trim().split('\n').pop()));
  await say('/undo'); await sleep(50);
  console.log('    nothing left:', JSON.stringify((await scr()).trim().split('\n').pop()));

  // ---------------------------------------------------------------- STOP and errors
  await reply('This is a long answer that is still being written', { hold: true });
  await say('write me a long story'); await sleep(80);
  console.log('12. while it answers the button is STOP:', await page.locator('#txSendBtn').innerText());
  await page.click('#txSendBtn'); await idle();
  console.log('    STOP cancels the request in the app and says so:', JSON.stringify(await page.evaluate(() => window.__ai.cancels.length)), (await scr()).trim().endsWith('[stopped]'), await page.locator('#txSendBtn').innerText());
  await page.evaluate((s) => window.__ai.queue.push({ match: sp => /messages$/.test(sp.url), sse: s }), tx.claudeSse('Start of an answer', { error: 'overloaded_error' }));
  await say('try again'); await idle();
  const lastNote = async () => (await scr()).split('\n').filter(l => l.startsWith('[Claude')).pop();
  console.log('13. an error event in the middle of the stream: what was written so far stays, then why it stopped:', (await scr()).includes('claude › Start of an answer'), JSON.stringify(await lastNote()));
  await page.locator('#txScreen-priv .tx-btnrow button', { hasText: 'Details' }).last().click(); await sleep(30);
  console.log('    Details:', JSON.stringify(await page.locator('#txAlertDetail').innerText()));
  await page.evaluate(() => txCloseAlert());
  await page.evaluate(() => window.__ai.queue.push({ match: sp => /messages$/.test(sp.url), status: 429, body: JSON.stringify({ type: 'error', error: { type: 'rate_limit_error', message: 'Number of request tokens has exceeded your per-minute rate limit' } }), headers: { 'retry-after': '20' } }));
  await say('again'); await idle();
  console.log('14. a rate limit, with the wait the provider asked for:', JSON.stringify(await lastNote()));
  await page.evaluate(() => window.__ai.queue.push({ match: sp => /messages$/.test(sp.url), status: 401, body: JSON.stringify({ type: 'error', error: { type: 'authentication_error', message: 'invalid x-api-key' } }) }));
  await say('and again'); await idle();
  console.log('15. a key that stopped working offers to change it:', JSON.stringify(await lastNote()), JSON.stringify(await page.locator('#txScreen-priv .tx-btnrow').last().innerText()));
  await page.locator('#txScreen-priv .tx-btnrow button', { hasText: 'Change the key' }).last().click(); await sleep(30);
  console.log('    which opens the Connect sheet, showing the saved key by its hint only:', await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show')), JSON.stringify((await page.locator('#txConnectBody p').nth(1).innerText()).replace(/ · .*$/, '')));

  console.log('errors:', JSON.stringify(errors));
  env.close();
  await b.close();
})();
