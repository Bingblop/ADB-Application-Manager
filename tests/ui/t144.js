// v7.11.2: for an app the UAD-NG list does not know, the app menu offers an "Ask" chip; it opens a window where the Coding Agent is asked whether the package is safe to disable.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [
    { pkg: 'com.samsung.android.bixby.agent', name: 'Bixby Voice', isSystem: true, isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.unknown', name: 'Unknown One', isSystem: true, isFrozen: true, isSuspended: false, isUninstalled: false },
  ];
  await page.addInitScript(a => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      getUadInfo(p) { return p === 'com.samsung.android.bixby.agent' ? JSON.stringify({ found: true, pkg: p, removal: 'Advanced', list: 'OEM', description: 'Bixby.' }) : '{}'; },
    };
  }, apps);
  await page.addInitScript(() => { window.__opened = []; window.open = u => { window.__opened.push(u); return null; }; });
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { isPrivilegedActive = true; window.__sent = []; });

  await ev(() => openInspector('com.samsung.android.bixby.agent')); await sleep(250);
  check('1. an app the UAD-NG list knows shows its classification and no Ask chip', await ev(() => getComputedStyle(document.getElementById('sheetUad')).display !== 'none' && getComputedStyle(document.getElementById('sheetAsk')).display === 'none'));
  await ev(() => closeInspector()); await sleep(150);
  await ev(() => openInspector('com.example.unknown')); await sleep(250);
  const chip = await ev(() => ({ ask: getComputedStyle(document.getElementById('sheetAsk')).display !== 'none', uad: getComputedStyle(document.getElementById('sheetUad')).display !== 'none', t: document.getElementById('sheetAsk').innerText.replace(/\s+/g, ' ').trim() }));
  check('2. an app the list does not know shows the Ask chip instead', chip.ask && !chip.uad && /Ask/.test(chip.t), JSON.stringify(chip));
  await ev(() => document.getElementById('sheetAsk').click()); await sleep(150);
  const m = await ev(() => ({ open: document.getElementById('appAskModal').classList.contains('show'), app: document.getElementById('appAskApp').innerText, wrap: document.getElementById('appAskWrap').style.display }));
  check('3. it opens a window with the app and the package, and asks nothing yet', m.open && /Unknown One · com\.example\.unknown/.test(m.app) && m.wrap === 'none', JSON.stringify(m));

  // no agent yet: say what is missing
  await ev(() => document.getElementById('appAskGo').click()); await sleep(200);
  const noAg = await ev(() => document.getElementById('appAskAi').innerText);
  check('4. with no Coding Agent connected it says what to do first', /pick and connect a Coding Agent/.test(noAg) && await ev(() => document.getElementById('appAskGo').disabled === false), noAg);

  // with an agent
  await ev(() => {
    window.txAgentReady = () => true; txState.agent = Object.keys(window.TX_AGENTS || {})[0] || txState.agent;
    window.txAgentDef = () => ({ id: 'claude', name: 'Claude Code (API)' });
    window.txTurnApi = async (ag, model, effort, system, msgs, onText) => { window.__sent.push({ system, msgs }); onText('Caution: '); onText('this is the Bixby-like helper.'); return { text: 'Caution: this is the Bixby-like helper.', usage: { in: 1, out: 1 } }; };
  });
  await ev(() => document.getElementById('appAskGo').click()); await sleep(250);
  const sent = await ev(() => window.__sent[0]);
  check('5. the agent gets the package name, the app name, that it is a system app and that it is disabled', sent && /Package name: com\.example\.unknown/.test(sent.msgs[0].text) && /App name: Unknown One/.test(sent.msgs[0].text) && /system app/.test(sent.msgs[0].text) && /State: disabled/.test(sent.msgs[0].text), JSON.stringify(sent));
  check('   and a system prompt that asks for a verdict and admits not knowing', /Safe, Caution or Do not touch/.test(sent.system) && /do not recognize/.test(sent.system));
  const ans = await ev(() => ({ t: document.getElementById('appAskAi').innerText, wrap: document.getElementById('appAskWrap').style.display }));
  check('6. the answer is shown, with a note of what was sent', /Caution: this is the Bixby-like helper\./.test(ans.t) && /The package name, the app's name/.test(ans.t) && ans.wrap !== 'none', JSON.stringify(ans));
  await ev(() => document.querySelector('#appAskModal .mode-btn-row .mode-action-btn:not(.primary)').click()); await sleep(100);
  check('7. Web search opens a search for the package', (await ev(() => window.__opened.join('|'))).includes('google.com/search?q=android%20package%20com.example.unknown%20safe%20to%20disable'));
  await ev(() => appAskClose());
  check('8. closing leaves the window shut', await ev(() => !document.getElementById('appAskModal').classList.contains('show')));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
