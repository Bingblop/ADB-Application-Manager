// v7.19.4 (the owner's list of 2026-10-29). 1) Settings > Navigation > Smart Tabs, off by default: turned on, the tabs between Application Manager and
// About go in the order of how often each was opened (kv 'tab_opens'), on the top bar and the bottom bar; the order is not changed while tabs are tapped,
// only when Smart Tabs is turned on or the app comes back to the front; the Feature List follows it with its arrows resting; off brings the Feature List
// order back. 2) Connected Devices > Hidden Settings: an Ask agent button that names the device and keeps nothing (no data-ar, no "In short:" request).
// 3) Settings > Default Ask Agent > Agent API keys: a key is tested (aiTestKey) and saved only when it works, then ✅ follows the agent's name there, in the
// Default Ask Agent list and in the Terminal's Coding Agents list (4); a failed test leaves no mark; Remove key takes the key and the mark away.
// Mock bridge: lib/tx_mock.js (the key store and key tests).
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 412, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, { real: false });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);
  const top = () => ev(() => [...document.querySelectorAll('#tabBar .tab-btn')].map(t => t.dataset.tab).join(','));

  // ---- 1) Smart Tabs ----
  const before = await top();
  await ev(() => switchView('prefs')); await sleep(200);
  const sw = await ev(() => { const t = document.getElementById('smartTabsToggle'); return { on: t.checked, inNav: !!t.closest('#navCard'), sub: t.closest('.switch-row').querySelector('.switch-sub').textContent }; });
  check('1. Smart Tabs is a switch in the Navigation card, off by default, with a line under it', !sw.on && sw.inNav && /most/.test(sw.sub), JSON.stringify(sw));
  for (let i = 0; i < 3; i++) await ev(() => { switchView('fonts'); switchView('apps'); });
  await ev(() => { switchView('devices'); switchView('apps'); });
  check('   opening tabs while it is off changes nothing on the bar', await top() === before, await top());
  check('   but each opening is counted', await ev(() => JSON.stringify(kvGet('tab_opens', {}))) === '{"fonts":3,"devices":1}', await ev(() => JSON.stringify(kvGet('tab_opens', {}))));
  await ev(() => setSmartTabs(true)); await sleep(150);
  const on = (await top()).split(',');
  check('   turned on: most opened right after Application Manager, About last', on[0] === 'apps' && on[1] === 'fonts' && on[2] === 'devices' && on[on.length - 1] === 'about' && on.length === before.split(',').length, on.join(','));
  const bn = await ev(() => bnavPinnedShown().join(',') + '|' + bnavMoreKeys().slice(0, 2).join(','));
  check('   the bottom bar follows the same order (More lists Custom Fonts first)', /\|fonts,devices$/.test(bn), bn);
  const fl = await ev(() => ({ note: getComputedStyle(document.getElementById('featureSmartNote')).display !== 'none', first: document.querySelector('#featureList .fl-row').dataset.tab, arrows: [...document.querySelectorAll('#featureList .fl-move')].every(x => x.disabled) }));
  check('   the Feature List shows that order, says why, and its arrows rest', fl.note && fl.first === 'fonts' && fl.arrows, JSON.stringify(fl));
  for (let i = 0; i < 5; i++) await ev(() => { switchView('logcat'); switchView('apps'); });
  check('   no tab moves while tabs are being opened', (await top()).split(',')[1] === 'fonts', await top());
  await ev(() => { Object.defineProperty(document, 'hidden', { value: false, configurable: true }); document.dispatchEvent(new Event('visibilitychange')); }); await sleep(100);
  check('   coming back to the app puts the new favourite first', (await top()).split(',')[1] === 'logcat', await top());
  check('   the choice is kept', await ev(() => kvGet('smart_tabs', false)) === true);
  await ev(() => setSmartTabs(false)); await sleep(100);
  check('   turned off: the Feature List order is back and the arrows work', await top() === before && await ev(() => !document.querySelectorAll('#featureList .fl-move')[1].disabled), await top());

  // ---- 2) Connected Devices: Ask agent on a device's hidden setting ----
  const cdb = await ev(() => { cd.info = { release: '14', maker: 'google', model: 'Pixel Watch 3' }; const d = document.createElement('div'); d.innerHTML = cdSetAskBtn('global', 'adb_enabled', '1', null); const x = d.firstChild; return { ar: x.hasAttribute('data-ar'), aw: x.dataset.aw, text: x.textContent }; });
  check('2. a connected device\'s setting has Ask agent, about that device, kept with nothing', !cdb.ar && /Android 14 on google Pixel Watch 3/.test(cdb.aw) && cdb.text === 'Ask agent', JSON.stringify(cdb));

  // ---- 3) Agent API keys in Settings ----
  await ev(() => { window.__ai.keyAnswer = (p, k) => k === 'pplx-good-key-123456' ? { ok: true, status: 200, body: '{"data":[]}' } : { ok: false, status: 401, body: '{"error":{"message":"Invalid API key"}}' }; window.confirm = () => true; });
  await ev(() => { switchView('prefs'); askAgentChoose('perplexity'); }); await sleep(150);
  const ui = await ev(() => ({ cur: document.getElementById('akAgentSelect').value, type: document.getElementById('akKeyInput').type, res: document.getElementById('akTestRes').textContent, n: document.getElementById('akAgentSelect').options.length }));
  check('3. Agent API keys: the Default Ask Agent\'s box, a password field, no key yet', ui.cur === 'perplexity' && ui.type === 'password' && /No key saved for Perplexity/.test(ui.res) && ui.n === 12, JSON.stringify(ui));
  await page.fill('#akKeyInput', 'pplx-wrong-key-000000'); await page.click('#akTestBtn'); await sleep(200);
  const f1 = await ev(() => ({ res: document.getElementById('akTestRes').textContent, err: !!document.querySelector('#akTestRes .is-err'), mark: document.getElementById('askAgentSelect').selectedOptions[0].textContent }));
  check('   a key that fails: the reason in red, no ✅', f1.err && f1.res.length > 0 && !/✅/.test(f1.mark), JSON.stringify(f1));
  await page.fill('#akKeyInput', 'pplx-good-key-123456'); await page.click('#akTestBtn'); await sleep(200);
  const ok = await ev(() => ({ res: document.getElementById('akTestRes').textContent, dots: document.getElementById('akKeyInput').value.length > 0 && document.getElementById('akKeyInput').dataset.saved === '1', ask: document.getElementById('askAgentSelect').selectedOptions[0].textContent, ak: document.getElementById('akAgentSelect').selectedOptions[0].textContent, tests: window.__ai.tests.length }));
  check('   a key that works: saved, shown as dots, ✅ in both lists', /It works/.test(ok.res) && ok.dots && /Perplexity ✅$/.test(ok.ask) && /Perplexity ✅$/.test(ok.ak) && ok.tests === 2, JSON.stringify(ok));
  // ---- 4) the Terminal's Coding Agents list ----
  await ev(() => switchView('terminal')); await sleep(400);
  const term = await ev(() => { const o = [...document.getElementById('txAgent').options]; return { p: o.find(x => x.value === 'perplexity').textContent, c: o.find(x => x.value === 'claude').textContent, v: document.getElementById('txAgent').value }; });
  check('4. the Terminal\'s Coding Agents list has the same ✅, only for that agent', /Perplexity ✅$/.test(term.p) && !/✅/.test(term.c) && term.v === 'none', JSON.stringify(term));
  await ev(() => switchView('prefs')); await sleep(150);
  await page.click('#akForgetBtn'); await sleep(150);
  const gone = await ev(() => ({ ask: document.getElementById('askAgentSelect').querySelector('option[value="perplexity"]').textContent, term: document.querySelector('#txAgent option[value="perplexity"]').textContent, res: document.getElementById('akTestRes').textContent, val: document.getElementById('akKeyInput').value }));
  check('   Remove key: the key and every ✅ are gone', !/✅/.test(gone.ask) && !/✅/.test(gone.term) && /No key saved/.test(gone.res) && gone.val === '', JSON.stringify(gone));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
