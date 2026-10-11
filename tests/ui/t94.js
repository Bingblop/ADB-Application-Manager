// v7.8 MCP servers for the coding agents: a preset or a custom connector, applied to any of the six Termux CLI tools (two at
// once, so the same server reaches more than one agent); Termux now defaults to bash when it is set up. The merge commands
// are real python3 run for real against temp files (not just strings), so this checks what actually lands on disk.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');

// Runs one of txMcpMergeJson/txMcpMergeToml's python3 command for real, against a fresh temp HOME, and returns the written file's text.
function runMerge(cmd, relPath) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpmerge-'));
  execSync(cmd, { cwd: home, env: Object.assign({}, process.env, { HOME: home }), shell: '/bin/sh' });
  const text = fs.readFileSync(path.join(home, relPath.replace(/^~\//, '')), 'utf8');
  fs.rmSync(home, { recursive: true, force: true });
  return text;
}

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms, arg) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn, arg)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.termux);

  // ---------------------------------------------------------------- Termux (bash) is now the default shell once it is set up
  await page.evaluate(() => window.__tx.info.termux = { installed: true, permission: true, version: '0.119.0' });
  await page.evaluate(() => switchView('terminal'));
  await until(() => txSess[txCurShell()] && txSess[txCurShell()].st === 'ready');
  console.log('1. with Termux set up, the Terminal opens on it (bash, more compatible with the agents than the sandbox):', await page.evaluate(() => txState.shell));
  console.log('   the welcome note names it:', (await page.locator('#txScreen-termux').innerText()).includes('Termux (bash and packages)'));
  console.log('   Settings explains the automatic rule:', await page.evaluate(() => { txOpenSettings(); const t = [...document.querySelectorAll('#txSettingsBody option')].find(o => o.value === '').textContent; txCloseSettings(); return t; }));

  // ---------------------------------------------------------------- the MCP sheet: presets, agents, validation
  await page.click('#txMcpBtn'); await sleep(60);
  console.log('2. the MCP sheet opens, over the six CLI agents, closed to API-key-only ones:', await page.evaluate(() => document.getElementById('txMcpModal').classList.contains('show')),
    JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('#txMcpAgents input')].map(c => c.value))));
  await page.selectOption('#txMcpPreset', 'filesystem'); await sleep(30);
  console.log('   picking Filesystem fills the name and command, and shows its note:', await page.inputValue('#txMcpName'), await page.inputValue('#txMcpCommand'), !!(await page.locator('#txMcpPresetNote').innerText()).trim());
  await page.click('button:has-text("Add to the agents picked above")'); await sleep(30);
  console.log('3. nothing picked yet: asks for an agent:', await page.locator('#txMcpStatus').innerText());

  // ---------------------------------------------------------------- apply to Gemini CLI (Termux-hosted): the real end-to-end path
  await page.fill('#txMcpName', 'memory');
  await page.fill('#txMcpCommand', 'npx -y @modelcontextprotocol/server-memory');
  await page.check('#txMcpAgents input[value="gemini"]');
  await page.click('button:has-text("Add to the agents picked above")');
  await until(() => !txRuns.termux, 15000);
  const settingsText = env.read('termux', '.gemini/settings.json');
  console.log('4. applied to Gemini CLI: its settings.json now has the server, command and args intact:', JSON.stringify(JSON.parse(settingsText).mcpServers.memory));
  console.log('   the screen shows the steps, python checked, then saved:', JSON.stringify((await page.locator('#txScreen-termux').innerText()).trim().split('\n').slice(-4)));
  console.log('   it is remembered locally too:', await page.evaluate(() => JSON.parse(window.__kv.tx_mcp_servers || '[]')));

  // ---------------------------------------------------------------- two agents at once: Gemini (termux) and Claude Code (debian, no proot-distro here)
  await page.click('#txMcpBtn'); await sleep(60);
  await page.fill('#txMcpName', 'fetcher');
  await page.fill('#txMcpCommand', 'uvx mcp-server-fetch');
  await page.check('#txMcpAgents input[value="gemini"]');
  await page.check('#txMcpAgents input[value="claude"]');
  await page.click('button:has-text("Add to the agents picked above")');
  await until(() => !txRuns.termux, 15000);
  console.log('5. two agents picked at once: both get their own step run (Gemini, then Claude Code):',
    JSON.stringify((await page.locator('#txScreen-termux').innerText()).split('\n').filter(l => /^»/.test(l))));
  console.log('   Gemini (no Debian needed) got it for real:', JSON.parse(env.read('termux', '.gemini/settings.json')).mcpServers.fetcher);

  // ---------------------------------------------------------------- "Already added" list and Forget
  await page.click('#txMcpBtn'); await sleep(60);
  console.log('6. both servers are listed, with which agents they went to:', await page.evaluate(() => [...document.querySelectorAll('#txMcpBody .tx-row-name')].map(e => e.textContent)));
  await page.locator('#txMcpBody .tx-row button', { hasText: 'Forget' }).first().click(); await sleep(30);
  console.log('   Forget removes it from the list here:', (await page.evaluate(() => JSON.parse(window.__kv.tx_mcp_servers || '[]'))).length);
  await page.evaluate(() => txCloseMcp());

  // ---------------------------------------------------------------- the exact shape for every agent, run for real against a fresh file (not just a string)
  const cases = await page.evaluate(() => {
    const form = (t, extra) => Object.assign({ name: 'srv', transport: t, command: 'npx -y server', url: 'https://example.com/mcp', env: '' }, extra || {});
    const out = {};
    for (const k of Object.keys(TX_MCP_AGENTS)) {
      const stdioEntry = txMcpEntry(k, form('stdio'));
      const remoteEntry = txMcpEntry(k, form('http', { env: 'Authorization: Bearer secret-token' }));
      const meta = TX_MCP_AGENTS[k];
      out[k] = {
        path: meta.path,
        stdio: stdioEntry.toml ? { toml: true, cmd: txMcpMergeToml(meta.path, 'srv', stdioEntry.toml) } : { toml: false, cmd: txMcpMergeJson(meta.path, meta.top, 'srv', stdioEntry.json) },
        remoteSteps: txMcpSteps(k, 'srv2', remoteEntry).map(s => s.label)
      };
    }
    return out;
  });
  console.log('7. every agent\'s own config file and shape:');
  for (const k of Object.keys(cases)) {
    const c = cases[k];
    const text = runMerge(c.stdio.cmd, c.path);
    console.log('   ' + k + ' (' + c.path + '):', c.stdio.toml ? text.trim().replace(/\n/g, ' | ') : JSON.stringify(JSON.parse(text)));
  }
  console.log('   Codex remembers a remote server\'s token as an environment variable, not in its own file:', JSON.stringify(cases.codex.remoteSteps));

  // re-running the same merge (adding it again, or a second server) does not duplicate or corrupt the file
  const twice = (() => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'mcpmerge2-'));
    try {
      execSync(cases.claude.stdio.cmd, { cwd: home, env: Object.assign({}, process.env, { HOME: home }), shell: '/bin/sh' });
      execSync(cases.claude.stdio.cmd, { cwd: home, env: Object.assign({}, process.env, { HOME: home }), shell: '/bin/sh' });
      return JSON.parse(fs.readFileSync(path.join(home, '.claude.json'), 'utf8'));
    } finally { fs.rmSync(home, { recursive: true, force: true }); }
  })();
  console.log('8. adding the same server twice replaces it rather than duplicating it:', Object.keys(twice.mcpServers).length, JSON.stringify(twice.mcpServers.srv));

  console.log('page errors:', errors.length ? errors : 'none');
  env.close();
  await b.close();
})();
