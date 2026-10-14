// MCP servers: "Already added" lists only the agents the server really went to. txMcpApply used to save { name, agents } for every agent
// that was picked, whatever txRunSteps answered, so a setup that failed (a step with an error, Termux not set up, another command still
// running) was listed as added although nothing was written to that agent's config.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, {});
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  await page.evaluate(() => switchView('terminal'));
  await until(() => txSess[txCurShell()] && txSess[txCurShell()].st === 'ready');
  // the config step of an agent fails when its config path is in window.__failPaths
  await page.evaluate(() => { window.__failPaths = []; window.__tx.fake = (bb, cmd) => ({ out: '', exit: window.__failPaths.some(p => cmd.includes(p)) ? 1 : 0, cwd: window.__tx.dirs[bb] }); });
  const apply = (name, agents) => page.evaluate(async ([name, agents]) => {
    txOpenMcp();
    document.getElementById('txMcpName').value = name;
    document.getElementById('txMcpCommand').value = 'npx -y some-server';
    agents.forEach(a => { document.querySelector('#txMcpAgents input[value="' + a + '"]').checked = true; });
    await txMcpApply();
  }, [name, agents]);
  const saved = () => page.evaluate(() => JSON.stringify(JSON.parse(window.__kv.tx_mcp_servers || '[]').map(s => s.name + ':' + s.agents.join(','))));
  const listed = () => page.evaluate(() => { txOpenMcp(); const r = [...document.querySelectorAll('#txMcpBody .tx-row')].map(e => e.innerText.replace(/\s+/g, ' ').replace(/ Forget$/, '')); txCloseMcp(); return JSON.stringify(r); });

  // 1. Gemini works, Codex's config step fails
  await page.evaluate(() => { window.__failPaths = ['~/.codex/config.toml']; });
  await apply('memory', ['gemini', 'codex']);
  check('1. Gemini added, Codex failed: only Gemini is saved', (await saved()) === '["memory:gemini"]', await saved());
  check('   and listed', (await listed()) === '["memory Gemini CLI"]', await listed());
  check('   the screen says the Codex step failed', /step \d+ failed/.test(await page.locator('#txScreen-termux').innerText()));

  // 2. every agent fails: nothing new is saved and the earlier record stays as it was
  await page.evaluate(() => { window.__failPaths = ['~/.gemini/settings.json', '~/.claude.json']; });
  await apply('fetcher', ['gemini', 'claude']);
  check('2. a server whose every setup failed is not saved', (await saved()) === '["memory:gemini"]', await saved());
  await apply('memory', ['gemini']);
  check('   adding a saved one again, failing, leaves its record alone', (await saved()) === '["memory:gemini"]', await saved());

  // 3. adding it to Codex later, now working: both agents are listed
  await page.evaluate(() => { window.__failPaths = []; });
  await apply('memory', ['codex']);
  check('3. added to Codex later: Gemini (from before) and Codex', (await saved()) === '["memory:gemini,codex"]', await saved());

  // 4. another Termux command is still running: nothing runs and nothing is saved
  await page.evaluate(() => { window.__runsBefore = window.__tx.runs.length; window.__keep = txRuns.termux; txRuns.termux = { id: 'busy' }; });
  await apply('later', ['gemini']);
  check('4. while another command runs nothing is sent', await page.evaluate(() => window.__tx.runs.length === window.__runsBefore));
  check('   and nothing is saved', (await saved()) === '["memory:gemini,codex"]', await saved());
  await page.evaluate(() => { txRuns.termux = window.__keep; });

  check('no page errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : undefined);
  console.log(bad ? bad + ' FAILED' : 'all passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
