// v7.8 Command-Line Interface tab, the Terminal itself: the two-line tab label, the big Terminal | ADB Console switch (Terminal first,
// the choice remembered), one persistent shell per backend with its own screen (commands run in a real /bin/sh here), history,
// Termux's extra keys, TAB completion, colours / progress lines / split escape codes, full-screen programs offered in Termux, exit
// and restart, CTRL-C, and what the screen says (with the button that fixes it) when a shell cannot start.
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
  const scr = b2 => page.locator('#txScreen-' + b2).innerText();
  const clean = s => s.split(env.base).join('<tmp>');
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 4000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };

  // ---------------------------------------------------------------- the tab and the switch
  console.log('1. the tab reads Terminal over ADB Console:', JSON.stringify(await page.locator('.tab-btn[data-tab="terminal"]').innerText()));
  console.log('   the Feature List calls it Terminal / ADB Console:', await page.evaluate(() => tabDef('terminal').name));
  await page.evaluate(() => switchView('terminal')); await sleep(150);
  console.log('2. the Terminal page comes first, the ADB Console page is hidden:', await page.locator('#txPaneTerminal').isVisible() && !(await page.locator('#txPaneConsole').isVisible()));
  console.log('   the switch is big (at least 48 px tall):', (await page.locator('#txSwitchTerminal').boundingBox()).height >= 48, 'Terminal is the selected one:', await page.locator('#txSwitchTerminal').getAttribute('aria-selected'));
  await page.click('#txSwitchConsole'); await sleep(50);
  console.log('3. ADB Console shows the classic console (Rish mode, cheat sheet, its own output box):', await page.locator('#txPaneConsole').isVisible() && await page.locator('#rishBtn').isVisible() && await page.locator('#termOutput').isVisible() && !(await page.locator('#txPaneTerminal').isVisible()));
  console.log('   the choice is kept:', await page.evaluate(() => window.__kv.term_subtab));
  await page.evaluate(() => switchView('apps')); await page.evaluate(() => switchView('terminal')); await sleep(50);
  console.log('   leaving and coming back keeps ADB Console:', await page.locator('#txPaneConsole').isVisible());
  await page.click('#txSwitchTerminal'); await sleep(200);
  console.log('   and back to Terminal:', await page.locator('#txPaneTerminal').isVisible(), await page.evaluate(() => window.__kv.term_subtab));

  // ---------------------------------------------------------------- the first shell
  await until(() => txSess.priv.st === 'ready');
  console.log('4. with a working mode connected the Terminal opens on it, and starts its shell by itself:', await page.locator('#txShell').inputValue(), JSON.stringify(await page.evaluate(() => window.__tx.starts.map(s => s[0]))));
  const welcome = clean(await scr('priv'));
  console.log('   welcome and the ready line:', welcome.startsWith('Welcome to the Terminal.') && /\[sh ready through Shizuku, running as the shell user \(uid 2000\) in <tmp>\/priv\]/.test(welcome));
  console.log('   the state line names the shell and its folder:', clean(await page.locator('#txState').innerText()));
  console.log('   the mode button shows $, and is off while no agent is chosen:', await page.locator('#txModeBtn').innerText(), await page.locator('#txModeBtn').isDisabled(), 'the button says', await page.locator('#txSendBtn').innerText());

  // ---------------------------------------------------------------- commands
  await page.fill('#txInput', 'echo hello world; pwd'); await page.press('#txInput', 'Enter');
  await until(() => !txRuns.priv);
  let s1 = clean(await scr('priv'));
  console.log('5. a command and its output, Termux style (folder $ command):', s1.includes('<tmp>/priv $ echo hello world; pwd\nhello world\n<tmp>/priv\n'));
  console.log('   the input is empty again:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.fill('#txInput', 'mkdir -p sub && cd sub && export TXV=kept'); await page.press('#txInput', 'Enter'); await until(() => !txRuns.priv);
  await page.fill('#txInput', 'pwd; echo $TXV'); await page.press('#txInput', 'Enter'); await until(() => !txRuns.priv);
  s1 = clean(await scr('priv'));
  console.log('6. cd and export carry over to the next command, and the prompt follows:', s1.includes('<tmp>/priv/sub $ pwd; echo $TXV\n<tmp>/priv/sub\nkept\n'));
  await page.fill('#txInput', 'false'); await page.press('#txInput', 'Enter'); await until(() => !txRuns.priv);
  console.log('   a failing command shows its exit status:', (await scr('priv')).trim().endsWith('[exit 1]'));
  await page.fill('#txInput', 'cd ..'); await page.press('#txInput', 'Enter'); await until(() => !txRuns.priv);

  // ---------------------------------------------------------------- history and extra keys
  await page.fill('#txInput', 'half typed');
  await page.click('#txKeys button[data-k="up"]'); await sleep(20);
  console.log('7. ▲ brings back the last command:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.click('#txKeys button[data-k="up"]'); await sleep(20);
  console.log('   ▲ again, the one before:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.click('#txKeys button[data-k="down"]'); await page.click('#txKeys button[data-k="down"]'); await sleep(20);
  console.log('   ▼ back down to what was being typed:', JSON.stringify(await page.locator('#txInput').inputValue()));
  console.log('   history is kept for the next launch:', JSON.stringify(JSON.parse(await page.evaluate(() => window.__kv.tx_hist)).slice(0, 3)));
  await page.fill('#txInput', 'cat file');
  await page.focus('#txInput');
  await page.evaluate(() => { const i = document.getElementById('txInput'); i.setSelectionRange(3, 3); });
  await page.click('#txKeys button[data-k="|"]');
  console.log('8. | goes in at the cursor:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.click('#txKeys button[data-k="home"]'); await page.click('#txKeys button[data-k="~"]');
  console.log('   HOME then ~:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.click('#txKeys button[data-k="end"]'); await page.click('#txKeys button[data-k="-"]'); await page.click('#txKeys button[data-k="/"]');
  console.log('   END then - and /:', JSON.stringify(await page.locator('#txInput').inputValue()));
  console.log('   tapping the keys keeps the focus in the input (the keyboard stays up):', await page.evaluate(() => document.activeElement && document.activeElement.id));
  await page.click('#txKeys button[data-k="esc"]');
  console.log('   ESC clears the line:', JSON.stringify(await page.locator('#txInput').inputValue()));

  // ---------------------------------------------------------------- TAB completion
  env.write('priv', 'alpha.txt', 'a\n'); env.write('priv', 'alpine/x', 'x\n'); env.write('priv', 'beta.txt', 'b\n');
  await page.fill('#txInput', 'cat al'); await page.click('#txKeys button[data-k="tab"]'); await until(() => !txRuns.priv); await sleep(50);
  console.log('9. TAB completes as far as the names agree:', JSON.stringify(await page.locator('#txInput').inputValue()), 'and lists them:', (await scr('priv')).trim().endsWith('alpha.txt   alpine/'));
  await page.fill('#txInput', 'cat alph'); await page.click('#txKeys button[data-k="tab"]'); await until(() => !txRuns.priv); await sleep(50);
  console.log('   one match: the whole name and a space:', JSON.stringify(await page.locator('#txInput').inputValue()));
  await page.fill('#txInput', 'ls alpi'); await page.click('#txKeys button[data-k="tab"]'); await until(() => !txRuns.priv); await sleep(50);
  console.log('   a folder ends in / (no space):', JSON.stringify(await page.locator('#txInput').inputValue()));
  console.log('   completion asks the shell quietly (nothing about it on the screen):', !(await scr('priv')).includes('printf'), JSON.stringify(await page.evaluate(() => window.__tx.runs.filter(r => /for f in/.test(r.cmd)).map(r => r.quiet))));
  await page.click('#txKeys button[data-k="esc"]');

  // ---------------------------------------------------------------- full-screen programs, clear, colours
  await page.fill('#txInput', 'vim notes.txt'); await page.press('#txInput', 'Enter'); await sleep(50);
  console.log('10. vim is offered in Termux instead of hanging here:', (await scr('priv')).includes('[vim is a full-screen program: it needs a real terminal, so run it in Termux]'), JSON.stringify(await page.locator('#txScreen-priv .tx-btnrow').last().innerText()));
  await page.locator('#txScreen-priv .tx-btnrow').last().locator('button', { hasText: 'Open in Termux' }).click(); await sleep(30);
  console.log('    Termux is not set up on this device yet, so it offers the setup sheet instead of hanging:', await page.evaluate(() => document.getElementById('txTermuxModal').classList.contains('show')));
  await page.evaluate(() => txCloseTermux());
  await page.fill('#txInput', 'top -n 1 -b');
  console.log('    prompts only count when started bare (top -n 1 prints once, python3 x.py runs a script):', JSON.stringify(await page.evaluate(() => ['top', 'top -n 1 -b', 'python3', 'python3 x.py', 'nano', 'nano a.txt', 'less a.txt', 'man ls', 'ssh me@host', 'ssh me@host uptime', 'ollama run qwen2.5-coder:1.5b', 'ollama run qwen2.5-coder:1.5b hi', 'claude', 'claude -p hi'].map(c => c + '=' + txIsFullscreen(c)))));
  await page.fill('#txInput', 'clear'); await page.press('#txInput', 'Enter'); await sleep(30);
  console.log('11. clear empties the screen:', JSON.stringify(await scr('priv')));
  const colour = await page.evaluate(() => {
    onTermOutput('priv', '', '\x1b[31mred\x1b[0m plain \x1b[1;32mbold green\x1b[0m\n');
    onTermOutput('priv', '', 'split: \x1b[3');                       // an escape code cut in two
    onTermOutput('priv', '', '4mblue\x1b[0m end\n');
    onTermOutput('priv', '', '\x1b[38;5;208m256\x1b[0m \x1b[38;2;10;20;30mtrue\x1b[0m \x1b[7minv\x1b[0m\n');
    onTermOutput('priv', '', 'Downloading  10%\rDownloading  55%\rDownloading 100%\n');
    onTermOutput('priv', '', 'line one\r');                         // \r\n cut in two is still a newline
    onTermOutput('priv', '', '\nline two\n');
    onTermOutput('priv', '', 'abc\b\bX\n\x1b]0;window title\x07after title\n\x1b[2Kbar 1/3\r\x1b[Kbar 3/3\n');
    const el = document.getElementById('txScreen-priv');
    const spans = [...el.querySelectorAll('span[style]')].map(s => s.textContent + '=' + s.getAttribute('style'));
    return { text: el.innerText, spans };
  });
  console.log('12. program output keeps its colours and drops other escape codes:');
  console.log('    text:', JSON.stringify(colour.text));
  console.log('    styled:', JSON.stringify(colour.spans));

  // ---------------------------------------------------------------- one screen per shell
  await page.selectOption('#txShell', 'app'); await until(() => txSess.app.st === 'ready');
  console.log('13. the app shell has its own screen, the other one is kept:', await page.locator('#txScreen-app').isVisible() && !(await page.locator('#txScreen-priv').isVisible()));
  await page.fill('#txInput', 'pwd'); await page.press('#txInput', 'Enter'); await until(() => !txRuns.app);
  console.log('    it runs in its own folder:', clean(await scr('app')).includes('[sh ready through this app, running as this app (uid 10234) in ~]') && clean(await scr('app')).includes('~ $ pwd\n<tmp>/app\n'));
  await page.selectOption('#txShell', 'priv'); await sleep(50);
  console.log('    switching back shows the first screen as it was:', (await scr('priv')).includes('bar 3/3'), 'the choice is kept:', JSON.parse(await page.evaluate(() => window.__kv.tx_state)).shell);

  // ---------------------------------------------------------------- CTRL-C, busy
  await page.evaluate(() => { window.__tx.hold.priv = true; });
  await page.fill('#txInput', 'sleep 100'); await page.press('#txInput', 'Enter'); await sleep(30);
  console.log('14. while a command runs the button turns into STOP:', await page.locator('#txSendBtn').innerText());
  await page.fill('#txInput', 'echo second'); await page.press('#txInput', 'Enter'); await sleep(20);
  console.log('    a second command waits its turn with a note:', (await scr('priv')).trim().endsWith('[a command is still running: CTRL-C (or STOP) ends it]'));
  await page.click('#txKeys button[data-k="ctrlc"]'); await sleep(20);
  console.log('    CTRL-C asks the shell to stop it:', JSON.stringify(await page.evaluate(() => window.__tx.stops)), (await scr('priv')).trim().endsWith('^C'));
  await page.evaluate(() => window.__tx.held.priv.finish({ out: '', exit: 130, stopped: true }));
  await sleep(30);
  console.log('    the end shows [stopped] and RUN comes back:', (await scr('priv')).trim().endsWith('[stopped]'), await page.locator('#txSendBtn').innerText());

  // ---------------------------------------------------------------- exit, restart
  await page.fill('#txInput', 'exit'); await page.press('#txInput', 'Enter'); await until(() => txSess.priv.st === 'ended');
  console.log('15. exit ends the shell, Termux style:', (await scr('priv')).trim().split('\n').pop());
  const startsBefore = await page.evaluate(() => window.__tx.starts.length);
  await page.press('#txInput', 'Enter'); await until(() => txSess.priv.st === 'ready');
  console.log('    Enter on an empty line starts a new one:', (await page.evaluate(() => window.__tx.starts.length)) === startsBefore + 1, (await scr('priv')).trim().split('\n').pop().replace(env.base, '<tmp>'));
  await page.click('#txRestartBtn'); await until(() => txSess.priv.st === 'ready');
  console.log('    Restart shell closes it and opens a fresh one:', JSON.stringify(await page.evaluate(() => window.__tx.closes)), (await page.evaluate(() => window.__tx.starts.length)) === startsBefore + 2);

  // ---------------------------------------------------------------- shells that cannot start
  await page.evaluate(() => { window.__tx.startFail.termux = 'termux_permission: this app may not run commands in Termux yet'; window.__tx.info.termux.installed = true; window.__tx.info.termux.permission = false; });
  await page.selectOption('#txShell', 'termux'); await sleep(80);
  console.log('16. Termux without the permission: the setup sheet opens, and the screen says so with an Allow button:', await page.evaluate(() => document.getElementById('txTermuxModal').classList.contains('show')),
    (await scr('termux')).includes('[This app may not run commands in Termux yet.]'), JSON.stringify(await page.locator('#txScreen-termux .tx-btnrow').last().innerText()));
  await page.evaluate(() => txCloseTermux());
  await page.evaluate(() => { window.__tx.startFail.termux = ''; });
  await page.locator('#txScreen-termux .tx-btnrow button', { hasText: 'Allow' }).click(); await until(() => txSess.termux.st === 'ready');
  console.log('    Allow asks Android, and once granted the Termux shell starts by itself:', await page.evaluate(() => window.__tx.perms), clean(await scr('termux')).includes('[bash ready through Termux, running as Termux (uid 10345) in <tmp>/termux]'));
  await page.evaluate(() => { txSess.termux.st = 'off'; window.__tx.startFail.termux = 'Termux: allow-external-apps property is not set to "true"'; });
  await page.click('#txRestartBtn'); await sleep(80);
  console.log('17. Termux refusing other apps: the exact command to run there, with Copy:', (await scr('termux')).includes("mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"));
  await page.locator('#txScreen-termux .tx-btnrow button', { hasText: 'Copy' }).last().click();
  console.log('    Copy puts it on the clipboard:', await page.evaluate(() => window.__tx.clip === TX_TERMUX_ALLOW_CMD));
  await page.evaluate(() => { window.__tx.startFail.priv = 'no privileged working mode is active: connect ADB, Shizuku or Root in Working Modes first'; txSess.priv.st = 'off'; });
  await page.selectOption('#txShell', 'priv'); await sleep(80);
  console.log('18. no working mode: the screen says so and offers Working Modes:', (await scr('priv')).includes('[No working mode is connected: connect ADB, Shizuku or Root first.]'), JSON.stringify(await page.locator('#txScreen-priv .tx-btnrow').last().innerText()));

  console.log('errors:', JSON.stringify(errors));
  env.close();
  await b.close();
})();
