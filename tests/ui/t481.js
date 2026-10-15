// The Stop of an Ask window cancels only that window's requests (PG4-1). Every AI request used to be added to one global "Ask run", so
// stopping or closing the Ask agent window also cancelled a Terminal / Console agent turn that was running, and a second Ask window
// (log entry, Hidden Settings) took the first one's run over: Stop in the first then cancelled nothing.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, {});
  await page.addInitScript(() => {
    window.__ai.vault = { claude: { key: true, hint: 'sk-ant-…aaaa', base: '', savedAt: 1790000000000, readable: true } };
    window.__msgIds = () => window.__ai.reqs.filter(r => /\/v1\/messages$/.test(r.url)).map(r => r.id);
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => askAgentChoose('claude'));          // the Default Ask Agent
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, arg, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn, arg)) return true; await sleep(25); } return false; };
  const held = () => page.evaluate(s => window.__ai.queue.push({ match: sp => /\/v1\/messages$/.test(sp.url), sse: s, hold: true }), tx.claudeSse('An answer.'));
  const reqIds = () => page.evaluate(() => window.__msgIds());          // the chat requests (not the model list the Terminal asks for)
  const cancels = () => page.evaluate(() => window.__ai.cancels.slice());
  const live = id => page.evaluate(i => !!window.__ai.live[i], id);

  // ---------------------------------------------------------------- an Ask window and a Terminal agent turn at the same time
  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');
  await page.selectOption('#txAgent', 'claude'); await sleep(50);
  await held();
  await page.evaluate(() => askAgentAbout('package', 'com.example.app', ['App name: Example']));          // the Ask window first: its run is open
  await until(() => window.__msgIds().length === 1);
  const [askId] = await reqIds();
  await held();
  await page.fill('#txInput', 'hello'); await page.press('#txInput', 'Enter');          // then a Terminal agent turn
  await until(() => window.__msgIds().length === 2);
  const termId = (await reqIds())[1];
  check('1. the Terminal turn and the Ask window each sent a request', !!termId && !!askId && termId !== askId);
  await page.evaluate(() => agentAskStop()); await sleep(60);
  const c1 = await cancels();
  check('   Stop in the Ask window cancels its own request', c1.includes(askId), JSON.stringify(c1));
  check('   ... and not the Terminal agent turn', !c1.includes(termId), JSON.stringify(c1));
  check('   the Terminal turn is still running', await live(termId) && await page.evaluate(() => txChat.busy));
  await page.evaluate(() => agentAskClose()); await sleep(30);
  check('   closing the Ask window leaves the Terminal turn alone too', !(await cancels()).includes(termId));
  await page.evaluate(i => { const st = window.__ai.live[i]; if (st) st.release(); }, termId);
  check('   the Terminal turn then ends with its answer', await until(() => !txChat.busy) && (await page.evaluate(() => document.getElementById('cnSegTerm').innerText)).includes('An answer.'));

  // ---------------------------------------------------------------- two Ask windows at the same time
  await page.evaluate(() => { window.__ai.cancels.length = 0; window.__ai.reqs.length = 0; });
  await held(); await held();
  await page.evaluate(() => {
    const box = () => { const d = document.createElement('div'); document.body.appendChild(d); return d; };
    window.__done = 0;
    aiExplainInto(box(), 'sys', 'Package: one', () => true, '', null, 'agentAsk').then(() => window.__done++);
    aiExplainInto(box(), 'sys', 'Package: two', () => true, '', null, 'lcEnt').then(() => window.__done++);
  });
  await until(() => window.__msgIds().length === 2);
  const [first, second] = await reqIds();
  await page.evaluate(() => askStopRun('agentAsk')); await sleep(60);
  const c2 = await cancels();
  check('2. two Ask windows: Stop in the first cancels the first one\'s request', c2.includes(first), JSON.stringify(c2));
  check('   ... and not the second window\'s', !c2.includes(second) && await live(second), JSON.stringify(c2));
  await page.evaluate(() => askStopRun('lcEnt')); await sleep(60);
  check('   Stop in the second window cancels its request', (await cancels()).includes(second));
  await page.evaluate(() => Promise.all([window.__p1, window.__p2]));
  check('   both windows are done and no run is left over', await page.evaluate(() => Object.keys(askRuns).length === 0));

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
