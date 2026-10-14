// MCP servers for Codex: adding a remote server again with a new token replaces the token Codex reads (MCP_<NAME>_TOKEN in ~/.bashrc).
// The step used to be `grep -q MCP_X_TOKEN= ~/.bashrc || echo 'export ...' >> ~/.bashrc`: once a line was there (the GitHub preset's
// YOUR_GITHUB_TOKEN placeholder, or a token that has since expired) nothing new was written, the step still said done, and Codex kept the old token.
// The step's command is taken from the page and run for real against a temporary HOME.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(300);
  const tokenStep = token => page.evaluate(t => {
    const steps = txMcpSteps('codex', 'github', txMcpEntry('codex', { name: 'github', transport: 'http', url: 'https://api.githubcopilot.com/mcp/', env: 'Authorization: Bearer ' + t, command: '' }));
    return { labels: steps.map(s => s.label), step: steps.find(s => /Save the token/.test(s.label)) };
  }, token);

  const first = await tokenStep('YOUR_GITHUB_TOKEN');
  console.log('1. the Codex steps:', JSON.stringify(first.labels));
  check('   the token step is not guarded by a grep that skips it when a line is there', !/grep -q [^|]*\|\|/.test(first.step.cmd), first.step.cmd);

  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'mcptoken-'));
  const rc = path.join(home, '.bashrc');
  const run = cmd => { try { execSync(cmd, { cwd: home, env: Object.assign({}, process.env, { HOME: home }), shell: '/bin/bash', stdio: 'pipe' }); return 0; } catch (e) { return e.status || 1; } };
  const lines = () => fs.readFileSync(rc, 'utf8').split('\n').filter(Boolean);
  const tokenNow = () => execSync('bash -c \'. ~/.bashrc; printf %s "$MCP_GITHUB_TOKEN"\'', { cwd: home, env: Object.assign({}, process.env, { HOME: home }) }).toString();

  // no ~/.bashrc yet
  check('2. with no ~/.bashrc yet the step succeeds', run(first.step.cmd) === 0);
  check('   and writes the line', JSON.stringify(lines()) === JSON.stringify(['export MCP_GITHUB_TOKEN=YOUR_GITHUB_TOKEN']), JSON.stringify(lines()));

  // other lines of the user's own, then the real token
  fs.appendFileSync(rc, 'alias ll="ls -l"\nexport MCP_GITHUB_TOKENS_OTHER=keep\n');
  const second = await tokenStep('ghp_new123');
  check('3. adding it again with the real token succeeds', run(second.step.cmd) === 0);
  check('   the old line is gone and the new one is there once', lines().filter(l => /^export MCP_GITHUB_TOKEN=/.test(l)).join(' | ') === 'export MCP_GITHUB_TOKEN=ghp_new123', JSON.stringify(lines()));
  check('   the user\'s other lines are kept', lines().includes('alias ll="ls -l"') && lines().includes('export MCP_GITHUB_TOKENS_OTHER=keep'), JSON.stringify(lines()));
  check('   bash now gives Codex the new token', tokenNow() === 'ghp_new123', tokenNow());

  // the same token again: still one line
  check('4. the same token a third time', run(second.step.cmd) === 0 && lines().filter(l => /^export MCP_GITHUB_TOKEN=/.test(l)).length === 1, JSON.stringify(lines()));

  fs.rmSync(home, { recursive: true, force: true });

  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
