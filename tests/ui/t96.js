// v7.9 AI assist in the ADB Console: the same Coding Agent picked in the Terminal can help with syntax/code here too, through
// a $ / AI toggle next to the input. A one-shot question/answer (its own small memory, separate from the Terminal's own chat),
// at most one proposed command per answer, shown with Run / Skip (or run straight away when "Ask before running" is off),
// carried out through the console's own execution path - never a parallel shell of its own.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.addInitScript(() => { window.__tx.info.termux = { installed: false, permission: false }; });   // a plain device: Termux is not set up, so the default shell is still Working mode
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => {
    window.__execCalls = [];
    window.AndroidBridge.executeShell = function (c) { window.__execCalls.push(c); return 'ran: ' + c; };
  });
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const ask = async q => { await page.fill('#termCmd', q); await page.click('#termRunBtn'); await until(() => !termAiBusy); };
  const out = () => page.locator('#termOutput').innerText();

  await page.evaluate(() => switchView('terminal'));

  // ---------------------------------------------------------------- the toggle, before any agent is picked
  await page.click('#txSwitchConsole'); await sleep(60);
  console.log('1. the console starts in shell mode ($):', await page.locator('#termModeBtn').innerText());
  await page.click('#termModeBtn'); await sleep(30);
  console.log('   with no agent picked, toggling stays in shell mode:', await page.locator('#termModeBtn').innerText());

  // ---------------------------------------------------------------- pick and connect Claude in the Terminal pane first
  await page.click('#txSwitchTerminal'); await sleep(60);
  await page.evaluate(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'claude-opus-5-5', display_name: 'Claude Opus 5.5' }] }) }); });
  await page.selectOption('#txAgent', 'claude'); await sleep(60);
  await page.fill('#txKeyInput', 'sk-ant-GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(100);
  console.log('2. Claude is connected:', !(await page.evaluate(() => document.getElementById('txConnectModal').classList.contains('show'))));

  // ---------------------------------------------------------------- now the console toggle works, and names the agent
  await page.click('#txSwitchConsole'); await sleep(60);
  await page.click('#termModeBtn'); await sleep(30);
  console.log('3. with Claude connected, toggling switches to AI:', await page.locator('#termModeBtn').innerText(), await page.locator('#termModeBtn').getAttribute('class'));
  console.log('   the note says who it is asking:', await page.locator('#termAiNote').innerText());
  console.log('   the placeholder changes too:', await page.getAttribute('#termCmd', 'placeholder'));

  // ---------------------------------------------------------------- asking a question that proposes a command: Run it
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Use this to list packages:\n<run>pm list packages</run>'));
  await ask('how do I list installed packages?');
  console.log('4. the question is echoed, and the answer (without the <run> tag) is shown:', (await out()).includes('? how do I list installed packages?'), !(await out()).includes('<run>'));
  console.log('   a Run / Skip card appears with the exact command:', await page.locator('#termOutput').getByText('pm list packages', { exact: true }).count());
  await page.locator('#termOutput button', { hasText: 'Run' }).click(); await sleep(60);
  console.log('   Run calls the console\'s own executeShell with that exact command:', await page.evaluate(() => window.__execCalls.slice(-1)[0]));
  await page.evaluate(() => closeCommandResultsModal());

  // ---------------------------------------------------------------- Skip: nothing runs
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Try:\n<run>pm list packages -3</run>'));
  await ask('only the ones I installed?');
  const before = await page.evaluate(() => window.__execCalls.length);
  await page.locator('#termOutput button', { hasText: 'Skip' }).last().click(); await sleep(30);
  console.log('5. Skip leaves the command alone:', (await out()).trim().split('\n').pop(), await page.evaluate(() => window.__execCalls.length) === before);

  // ---------------------------------------------------------------- a plain answer with no command: no card at all
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('pm list packages lists every package on the device.'));
  await ask('what does that command do?');
  console.log('6. a plain explanation shows no Run/Skip card, and earlier decided ones are gone too:', await page.locator('#termOutput button').count() === 0);

  // ---------------------------------------------------------------- "Ask before running commands" off: it just runs
  await page.click('#txSwitchTerminal'); await sleep(30);
  await page.evaluate(() => txOpenSettings());
  await page.locator('#txSettingsBody .switch-row', { hasText: 'Ask before running commands' }).locator('.switch-track').click();
  await page.evaluate(() => txCloseSettings());
  await page.click('#txSwitchConsole'); await sleep(30);
  await page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s }), tx.claudeSse('Here:\n<run>pm list packages -s</run>'));
  const before2 = await page.evaluate(() => window.__execCalls.length);
  await ask('and the system ones?');
  console.log('7. with "Ask before running" off, the proposed command runs on its own:', await page.evaluate(() => window.__execCalls.length) === before2 + 1, await page.evaluate(() => window.__execCalls.slice(-1)[0]));
  await page.evaluate(() => closeCommandResultsModal());

  // ---------------------------------------------------------------- a pinned/saved command still runs as a command, even in AI mode
  await page.evaluate(() => runTerminalCmd('id'));
  console.log('8. a command passed straight to runTerminalCmd (a pinned chip) still runs, not asked to the agent:', await page.evaluate(() => window.__execCalls.slice(-1)[0]) === 'id');

  console.log('page errors:', errors.length ? errors : 'none');
  env.close();
  await b.close();
})();
