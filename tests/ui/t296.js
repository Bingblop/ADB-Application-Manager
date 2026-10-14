// Console, the "UID (id)" quick button: it runs `id` and leaves the box alone. It used to write "id" over what was typed in the box and run the box,
// so the typed command was lost, and in AI mode (the $/AI switch) the word "id" went to the coding agent as a question instead of running. Now the
// button hands `id` to runTerminalCmd as a command of its own: a shell command in shell mode and in AI mode, a Rish command in Rish mode.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1', perm_intro_v62: '1' }, holdShell: false });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  await ev(() => switchView('console')); await wait(400);
  const uid = page.locator('button.apps-tool-btn', { hasText: 'UID (id)' });
  const state = () => ev(() => ({ shell: window.__calls.shell.map(s => s.cmd), rish: window.__calls.rish.slice(), ai: window.__ai.slice(), box: document.getElementById('termCmd').value }));
  await ev(() => { window.__ai = []; window.termAiSend = q => window.__ai.push(q); });

  // shell mode
  await page.fill('#termCmd', 'pm list packages -3');
  await uid.click(); await wait(250);
  let s = await state();
  check('shell mode: the button runs id', s.shell.length === 1 && s.shell[0] === 'id', JSON.stringify(s));
  check('shell mode: what was typed stays in the box', s.box === 'pm list packages -3', JSON.stringify(s));

  // AI mode
  await ev(() => { termMode = 'chat'; window.__calls.shell.length = 0; });
  await uid.click(); await wait(250);
  s = await state();
  check('AI mode: the button still runs id as a command', s.shell.length === 1 && s.shell[0] === 'id', JSON.stringify(s));
  check('AI mode: nothing goes to the agent', s.ai.length === 0, JSON.stringify(s));
  check('AI mode: what was typed stays in the box', s.box === 'pm list packages -3', JSON.stringify(s));
  await ev(() => { termMode = 'shell'; window.__calls.shell.length = 0; });

  // Rish mode
  await page.locator('#rishBtn').click(); await wait(400);
  check('Rish mode is on', await ev(() => rishActive));
  await page.fill('#termCmd', 'ls /sdcard');
  await uid.click(); await wait(250);
  s = await state();
  check('Rish mode: id goes to the Rish shell', s.rish.length === 1 && s.rish[0] === 'id' && s.shell.length === 0, JSON.stringify(s));
  check('Rish mode: what was typed stays in the box', s.box === 'ls /sdcard', JSON.stringify(s));

  // the box still runs what is typed in it
  await ev(() => leaveRish('')); await wait(200);
  await page.fill('#termCmd', 'whoami'); await page.press('#termCmd', 'Enter'); await wait(250);
  s = await state();
  check('Enter runs what is typed and empties the box', s.shell.slice(-1)[0] === 'whoami' && s.box === '', JSON.stringify(s));

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
