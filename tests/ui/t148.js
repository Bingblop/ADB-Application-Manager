// v7.12.6: the Row tints slider in Settings (Off, Subtle, Normal), green Enable / Reinstall buttons in the app menu, the Ask agent window's
// progress bar and Stop button (a Browser Use task, a request on its way, the web lookup), and an Own server (OpenAI-compatible) agent.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  await ev(() => { txBuPollMs = 40; const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });

  // ---- 1. Row tints ----
  const rgb = k => ev(k => { const c = getComputedStyle(document.getElementById('card_com.t.' + k)).backgroundColor; const n = c.match(/\d+(\.\d+)?/g).slice(0, 3).map(Number); return /^color\(/.test(c) ? n.map(v => v * 255) : n; }, k);
  await ev(() => { switchView('apps'); allApps.length = 0; allApps.push({ name: 'Run', pkg: 'com.t.run', isRunning: true }, { name: 'Plain', pkg: 'com.t.plain' }); renderApps(); });
  const dist = async () => { const a = await rgb('run'), p = await rgb('plain'); return Math.max(Math.abs(a[0] - p[0]), Math.abs(a[1] - p[1]), Math.abs(a[2] - p[2])); };
  const subtle = await dist();
  const def = await ev(() => ({ attr: document.documentElement.getAttribute('data-row-tint'), range: document.getElementById('rowTintRange').value, card: !!document.getElementById('rowTintCard') }));
  check('1. Settings has a Row tints slider; it starts on Subtle', def.card && def.attr === 'subtle' && def.range === '1' && subtle > 1 && subtle <= 14, JSON.stringify({ def, subtle }));
  await ev(() => setRowTint(2)); const normal = await dist();
  await ev(() => setRowTint(0)); const off = await dist();
  check('   Normal tints the row more than Subtle, and Off not at all', normal > subtle * 1.8 && off < 0.6, JSON.stringify({ off, subtle, normal }));
  check('   the choice is kept', await ev(() => kvGet('row_tint', -1) === 0 && document.documentElement.getAttribute('data-row-tint') === 'off'));
  await ev(() => setRowTint(1));
  await ev(() => switchView('prefs')); await sleep(200);
  check('   the slider has the three labels', await ev(() => /Off\s+Subtle\s+Normal/.test(document.querySelector('#rowTintCard .row-tint-ticks').innerText.replace(/\n/g, ' '))));

  // ---- 2. green buttons ----
  const gb = await ev(() => { const f = (id) => { const e = document.getElementById(id); return { cls: e.className, col: getComputedStyle(e).color }; }; return { en: f('sheetBtnUnfreeze'), re: f('sheetBtnReinstall'), fr: f('sheetBtnFreeze'), un: f('sheetBtnUninstall') }; });
  check('2. Enable and Reinstall are tinted green like Freeze is blue and Uninstall red', /sheet-btn-green/.test(gb.en.cls) && /sheet-btn-green/.test(gb.re.cls) && /sheet-btn-blue/.test(gb.fr.cls) && /sheet-btn-red/.test(gb.un.cls), JSON.stringify(gb));

  // ---- 3. progress and Stop (Browser Use) ----
  await ev(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: '{}' }); switchView('terminal'); });
  await sleep(200);
  await page.selectOption('#txAgent', 'browseruse'); await sleep(100);
  await page.fill('#txKeyInput', 'bu_' + 'GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(250);
  await ev(() => { askAgentId = 'browseruse'; kvSet('ask_agent', 'browseruse'); askAgentBuild(); });
  const TID = '3c90c3cc-0d44-4b50-8888-8dd25736052a';
  await ev(t => {
    const q = window.__ai.queue;
    q.push({ match: sp => /v2\/tasks$/.test(sp.url) && sp.method === 'POST', status: 202, body: JSON.stringify({ id: t }) });
    q.push({ keep: true, match: sp => sp.method === 'GET' && sp.url.endsWith('/tasks/' + t), status: 200, body: JSON.stringify({ id: t, status: 'started', steps: [{}, {}, {}] }) });
    q.push({ match: sp => sp.method === 'PATCH' && sp.url.endsWith('/tasks/' + t), status: 200, body: '{}' });
    window.__sdone = true;
  }, TID);
  await ev(() => askAgentAbout('package', 'com.example.slow', []));
  const prog = await until(() => document.getElementById('agentAskProg').classList.contains('on') && !document.getElementById('agentAskBar').classList.contains('indet') && /3 of up to 12/.test(document.getElementById('agentAskProgTxt').innerText), 4000);
  const st1 = await ev(() => ({ stop: getComputedStyle(document.getElementById('agentAskStop')).display, w: document.getElementById('agentAskBarFill').style.width, txt: document.getElementById('agentAskProgTxt').innerText, go: document.getElementById('agentAskGo').disabled }));
  check('3. while a Browser Use task runs the window shows a progress bar (by steps) and a Stop button', prog && st1.stop !== 'none' && /^\d+%$/.test(st1.w) && st1.go, JSON.stringify(st1));
  await page.click('#agentAskStop'); await sleep(300);
  const st2 = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, prog: document.getElementById('agentAskProg').classList.contains('on'), stop: getComputedStyle(document.getElementById('agentAskStop')).display, go: document.getElementById('agentAskGo').disabled, reqs: window.__ai.reqs.map(r => r.method + ' ' + r.url.replace('https://api.browser-use.com', '')), json: (window.__ai.reqs.find(r => r.method === 'PATCH') || {}).json }));
  check('   Stop ends it at once: "Stopped.", the bar and Stop are gone, Ask again is back, and the task is asked to stop', /Stopped\./.test(st2.t) && !st2.prog && st2.stop === 'none' && !st2.go && st2.reqs.some(r => /^PATCH \/api\/v2\/tasks\//.test(r)) && st2.json && st2.json.action === 'stop', JSON.stringify({ t: st2.t, prog: st2.prog, stop: st2.stop, go: st2.go }));
  const n1 = await ev(() => window.__ai.reqs.length); await sleep(300);
  check('   and no more questions about the task are sent', n1 === await ev(() => window.__ai.reqs.length));
  await ev(() => { agentAskClose(); window.__ai.queue.length = 0; });

  // ---- 4. Stop on a request on its way (Crawl4AI) ----
  await ev(() => switchView('terminal')); await sleep(150);
  await page.selectOption('#txAgent', 'crawl4ai'); await sleep(100);
  await page.fill('#txKeyInput', 'sk_' + 'live_GOODKEY0123456789abcdef'); await page.click('#txKeyTestBtn'); await sleep(250);
  await ev(() => { askAgentId = 'crawl4ai'; kvSet('ask_agent', 'crawl4ai'); askAgentBuild(); window.__ai.cancels.length = 0; window.__ai.queue.push({ match: sp => /crawl4ai\.com\/answer/.test(sp.url), hold: true, status: 200, body: '{}' }); askAgentAbout('package', 'com.example.hold', []); });
  await sleep(300);
  const h1 = await ev(() => ({ stop: getComputedStyle(document.getElementById('agentAskStop')).display, indet: document.getElementById('agentAskBar').classList.contains('indet'), prog: document.getElementById('agentAskProg').classList.contains('on') }));
  await page.click('#agentAskStop'); await sleep(300);
  const h2 = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, cancels: window.__ai.cancels.length, prog: document.getElementById('agentAskProg').classList.contains('on') }));
  check('4. a request on its way shows a moving bar and Stop; Stop cancels the request and says Stopped.', h1.prog && h1.indet && h1.stop !== 'none' && /Stopped\./.test(h2.t) && h2.cancels >= 1 && !h2.prog, JSON.stringify({ h1, h2 }));
  await ev(() => agentAskClose());

  // ---- 5. Stop on the web lookup (no agent) ----
  await ev(() => { askAgentId = ''; kvSet('ask_agent', ''); askAgentBuild(); window.__ai.cancels.length = 0; window.__ai.queue.length = 0; window.__ai.queue.push({ match: sp => /bing\.com/.test(sp.url), hold: true, status: 200, body: '' }); askAgentAbout('package', 'com.example.web', []); });
  await sleep(300);
  await page.click('#agentAskStop'); await sleep(300);
  check('5. the web lookup can be stopped too', await ev(() => /Stopped\./.test(document.getElementById('agentAskAi').innerText) && window.__ai.cancels.length >= 1));
  await ev(() => agentAskClose());

  // ---- 6. Own server ----
  const opts = await ev(() => { askAgentBuild(); return [...document.querySelectorAll('#askAgentSelect option')].map(o => o.value).filter(v => v === 'ollama' || v === 'ownserver'); });
  check('6. Ollama and Own server are in the Default agent choice', opts.length === 2, JSON.stringify(opts));
  await ev(() => { switchView('terminal'); }); await sleep(150);
  await page.selectOption('#txAgent', 'ownserver'); await sleep(150);
  const sheet = await ev(() => ({ txt: document.getElementById('txConnectBody').innerText, ph: (document.getElementById('txBaseInput') || {}).placeholder, val: (document.getElementById('txBaseInput') || {}).value, look: /Look on this phone/.test(document.getElementById('txConnectBody').innerText) }));
  check('   its sheet asks for the server address (no phone search), names LM Studio / llama.cpp and says a key is optional', /LM Studio/.test(sheet.txt) && /llama\.cpp/.test(sheet.txt) && /only if the server asks/i.test(sheet.txt) && !sheet.look && /192\.168/.test(sheet.ph) && sheet.val === '', JSON.stringify(sheet).slice(0, 300));
  await ev(() => { window.__ai.keyAnswer = () => ({ ok: true, status: 200, body: JSON.stringify({ data: [{ id: 'qwen2.5-7b' }] }) }); });
  await page.fill('#txBaseInput', '192.168.1.20:1234/v1'); await page.click('#txKeyTestBtn'); await sleep(300);
  const conn = await ev(() => ({ ready: txAgentReady(txAgentDef('ownserver')), base: txBase(txAgentDef('ownserver')), tests: window.__ai.tests.filter(t => t.provider === 'ownserver').map(t => t.base) }));
  check('   the address is kept (http:// added) and the server is connected', conn.ready && /192\.168\.1\.20:1234/.test(conn.base), JSON.stringify(conn));
  await ev(() => { txCloseConnect(); askAgentId = 'ownserver'; kvSet('ask_agent', 'ownserver'); askAgentBuild(); window.__ai.queue.length = 0; });
  await ev(s => { window.__ai.queue.push({ match: sp => /\/chat\/completions$/.test(sp.url), sse: s }); }, tx.openaiSse('It is a harmless helper.'));
  await ev(() => askAgentAbout('package', 'com.example.helper', [])); await sleep(500);
  const ans = await ev(() => ({ t: document.getElementById('agentAskAi').innerText, url: (window.__ai.reqs.filter(r => /chat\/completions/.test(r.url)).pop() || {}).url }));
  check('   an Ask agent button is answered by it, the request going to that address only', /harmless helper/.test(ans.t) && /^(http:\/\/)?192\.168\.1\.20:1234\/v1\/chat\/completions/.test(ans.url || ''), JSON.stringify(ans));
  await ev(() => agentAskClose());

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
