// v7.10.3 Termux one-tap setup: the "Set up Termux now" / "Choose tools" buttons, the steps (pkg update, pkg install with the chosen
// packages), and the starter files, which are written for real into a temporary HOME by the same shell commands the app sends:
// a marked block only, replaced (never duplicated) when it runs again, the person's own lines kept, a file without a final newline
// handled, ~/.bash_profile made only when the person has none, and the starter .bashrc loading cleanly in an interactive bash.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
(async () => {
  let failed = 0;
  const check = (name, ok, extra) => { console.log((ok ? 'ok   ' : 'FAIL ') + name + (!ok && extra ? '  ' + extra : '')); if (!ok) failed++; };
  // a stand-in for Termux's pkg: it only records what it was asked
  const bin = fs.mkdtempSync(path.join(os.tmpdir(), 'fakepkg-'));
  fs.writeFileSync(path.join(bin, 'pkg'), '#!/bin/sh\necho "$@" >> "$HOME/.pkg-calls"\nexit ${PKG_EXIT:-0}\n', { mode: 0o755 });
  process.env.PATH = bin + ':' + process.env.PATH;
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = fn => page.evaluate(fn);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 8000)) { if (await page.evaluate(fn)) return true; await sleep(30); } return false; };
  const shown = id => page.evaluate(i => document.getElementById(i).classList.contains('show'), id);
  await ev(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');
  const home = env.dirs.termux;
  const calls = () => (env.exists('termux', '.pkg-calls') ? env.read('termux', '.pkg-calls').trim().split('\n') : []);

  // ---------------------------------------------------------------- the buttons
  await ev(() => txOpenTermuxSetup()); await sleep(100);
  check('the Termux setup sheet has Set up Termux now and Choose tools', await shown('txTermuxModal') && await page.locator('#txToolsNow').count() === 1 && await page.locator('#txToolsChoose').count() === 1);
  check('both are usable once Termux is installed and allowed', !(await page.locator('#txToolsNow').isDisabled()) && !(await page.locator('#txToolsChoose').isDisabled()));
  await ev(() => { window.__tx.info.termux = { installed: true, permission: false }; txInfo = window.__tx.info; txRenderTermux(); });
  check('... and disabled until then', await page.locator('#txToolsNow').isDisabled() && await page.locator('#txToolsChoose').isDisabled());
  await ev(() => { window.__tx.info.termux = { installed: true, permission: true }; txInfo = window.__tx.info; txRenderTermux(); });

  // ---------------------------------------------------------------- choose tools
  await page.click('#txToolsChoose'); await sleep(100);
  const rows = await ev(() => [...document.querySelectorAll('#txToolsBody .tx-tool-row')].map(r => r.querySelector('b').textContent + ':' + r.querySelector('input').checked));
  check('the list shows the tools, the defaults ticked', await shown('txToolsModal') && /vim:true/.test(rows.join()) && /nano:true/.test(rows.join()) && /git:true/.test(rows.join()) && /python:true/.test(rows.join()) && /tmux:false/.test(rows.join()), rows.join(' '));
  check('the starter files are listed, all on', ['~/.bashrc:true', '~/.vimrc:true', '~/.nanorc:true'].every(x => rows.includes(x)));
  await page.locator('#txToolsBody .tx-tool-row', { hasText: 'tmux' }).locator('input').check();
  await page.locator('#txToolsBody .tx-tool-row', { hasText: 'htop' }).locator('input').uncheck();
  await page.locator('#txToolsBody .tx-tool-row', { hasText: '~/.nanorc' }).locator('input').uncheck();
  const saved = JSON.parse(await ev(() => window.__kv.tx_tools));
  check('the choice is remembered', saved.pkgs.includes('tmux') && !saved.pkgs.includes('htop') && saved.nanorc === false);

  // ---------------------------------------------------------------- run with the choice
  await page.locator('#txToolsBody .mode-action-btn.primary').click();
  check('Install closes the sheet and runs the steps', await until(() => !document.getElementById('txToolsModal').classList.contains('show')));
  check('it finishes ("done" and the real terminal offered)', await until(() => /Set(ting)? up Termux|Setting up Termux: done/.test(document.getElementById('txScreen-termux').innerText) && /Termux is ready/.test(document.getElementById('txScreen-termux').innerText), 15000));
  const c1 = calls();
  check('pkg update ran first, then pkg install with exactly the chosen packages', c1[0] === 'update -y' && c1[1] === 'install -y vim nano git python openssh curl wget tmux', JSON.stringify(c1));
  check('~/.bashrc and ~/.vimrc were written, ~/.nanorc was not', env.exists('termux', '.bashrc') && env.exists('termux', '.vimrc') && !env.exists('termux', '.nanorc'));
  check('~/.bash_profile was made and reads ~/.bashrc', env.exists('termux', '.bash_profile') && /\.bashrc/.test(env.read('termux', '.bash_profile')));
  check('a button opens the real terminal', await page.locator('#txScreen-termux button', { hasText: 'Open the real terminal' }).count() >= 1);
  const count = (f, s) => env.read('termux', f).split(s).length - 1;
  check('exactly one marked block in .bashrc', count('.bashrc', '# >>> ADB App Manager starter >>>') === 1 && count('.bashrc', '# <<< ADB App Manager starter <<<') === 1);

  // ---------------------------------------------------------------- run again with the defaults: refreshed, never doubled, the person's lines stay
  const own = '# mine\nalias hello=\'echo hi\'';
  env.write('termux', '.bashrc', own + '\n' + env.read('termux', '.bashrc') + '\n# after\nexport AFTER=1');   // no final newline
  env.write('termux', '.vimrc', '" my vim line\nset hidden\n');
  fs.writeFileSync(path.join(home, '.bash_profile'), 'echo mine\n');
  await ev(() => txToolsRun(false));
  check('the second run finishes', await until(() => (document.getElementById('txScreen-termux').innerText.match(/Setting up Termux: done/g) || []).length >= 2, 15000));
  const c2 = calls();
  check('the defaults install vim nano git python openssh curl wget htop', c2.slice(-1)[0] === 'install -y vim nano git python openssh curl wget htop', c2.slice(-1)[0]);
  const rc = env.read('termux', '.bashrc');
  check('still exactly one block in .bashrc', count('.bashrc', '# >>> ADB App Manager starter >>>') === 1 && count('.bashrc', '# <<< ADB App Manager starter <<<') === 1);
  check('the lines above and below the block stay', rc.startsWith(own + '\n') && /# after\nexport AFTER=1/.test(rc));
  check('the line after the block was not glued to its end marker', /<<< ADB App Manager starter <<<\n/.test(rc));
  check('~/.vimrc: the person\'s lines stay, one block', /^" my vim line\nset hidden\n/.test(env.read('termux', '.vimrc')) && count('.vimrc', '" >>> ADB App Manager starter >>>') === 1);
  check('~/.nanorc is written when ticked (defaults)', env.exists('termux', '.nanorc') && count('.nanorc', '# >>> ADB App Manager starter >>>') === 1);
  check('a ~/.bash_profile of the person is left alone', env.read('termux', '.bash_profile') === 'echo mine\n');

  // ---------------------------------------------------------------- the starter really loads
  const run = (script, extra) => spawnSync('bash', ['-c', script], { env: Object.assign({}, process.env, { HOME: home, TERM: 'xterm-256color' }, extra || {}), encoding: 'utf8' });
  const syn = run('bash -n "$HOME/.bashrc" && echo SYNTAX_OK');
  check('bash accepts the starter .bashrc (syntax)', /SYNTAX_OK/.test(syn.stdout), syn.stderr);
  const ia = spawnSync('bash', ['--noprofile', '--rcfile', path.join(home, '.bashrc'), '-i'], { input: 'echo "PC=$PROMPT_COMMAND"; mkcd "$HOME/t1/t2" && pwd; type ll | head -1; echo "HIST=$HISTSIZE"; exit\n', env: Object.assign({}, process.env, { HOME: home, TERM: 'xterm-256color' }), encoding: 'utf8' });
  check('an interactive bash loads it without an error', !/syntax error|command not found: |unbound/.test(ia.stderr) && /PC=__adbmgr_prompt/.test(ia.stdout), (ia.stderr || '').slice(0, 200));
  check('its helpers and aliases work (mkcd, ll, history size)', /t1\/t2/.test(ia.stdout) && /ll is aliased/.test(ia.stdout) && /HIST=50000/.test(ia.stdout), ia.stdout.slice(0, 300));
  const ni = run('. "$HOME/.bashrc" >/dev/null 2>&1; echo "rc=$? PC=${PROMPT_COMMAND-unset}"');
  check('sourced by a non-interactive shell (the app\'s own sync) it sets no prompt and does not fail', /rc=0 PC=unset/.test(ni.stdout), ni.stdout + ni.stderr);
  const vi = run('command -v vim >/dev/null && vim -u "$HOME/.vimrc" -es -c "q" </dev/null 2>&1; echo vim_rc=$?');
  if (/vim_rc=/.test(vi.stdout) && spawnSync('sh', ['-c', 'command -v vim']).status === 0) check('vim reads the starter .vimrc without an error', /vim_rc=0/.test(vi.stdout) && !/E\d+:/.test(vi.stdout), vi.stdout);
  else console.log('skip vim (not installed on this machine)');

  // ---------------------------------------------------------------- a failing pkg stops the setup and says so
  fs.writeFileSync(path.join(bin, 'pkg'), '#!/bin/sh\necho "E: unable to reach the mirror"\nexit 100\n', { mode: 0o755 });
  const before = env.read('termux', '.bashrc');
  await ev(() => { document.getElementById('txScreen-termux').innerHTML = ''; });
  await ev(() => txToolsRun(false));
  check('a failing pkg stops at that step and says which', await until(() => /step 1 failed \(exit 100\)/.test(document.getElementById('txScreen-termux').innerText), 15000));
  check('... and the starter files are not touched', env.read('termux', '.bashrc') === before);

  await page.screenshot({ path: 'termux_tools.png' });
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
