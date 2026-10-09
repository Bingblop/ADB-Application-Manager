// v7.12.0: the default agent (Settings, under Language) and the Ask agent window; the app menu's tags carry no "UAD-NG" text and an app the list does not know gets an Ask agent button; without a default agent (or one that is not connected) the button opens Settings.
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
  check('1. an app the UAD-NG list knows shows its classification and no Ask agent chip', await ev(() => getComputedStyle(document.getElementById('sheetUad')).display !== 'none' && getComputedStyle(document.getElementById('sheetAsk')).display === 'none'));
  check('   the classification tag says only the level, no "UAD-NG" text', await ev(() => document.getElementById('sheetUad').innerText.trim() === 'ADVANCED' && !/UAD-NG/.test(document.getElementById('sheetUad').innerText)));
  await ev(() => closeInspector()); await sleep(150);
  await ev(() => openInspector('com.example.unknown')); await sleep(250);
  const chip = await ev(() => ({ ask: getComputedStyle(document.getElementById('sheetAsk')).display !== 'none', uad: getComputedStyle(document.getElementById('sheetUad')).display !== 'none', t: document.getElementById('sheetAsk').innerText.replace(/\s+/g, ' ').trim() }));
  check('2. an app the list does not know shows an Ask agent button where the tag would be, without "UAD-NG"', chip.ask && !chip.uad && /Ask agent/.test(chip.t) && !/UAD-NG/.test(chip.t), JSON.stringify(chip));

  const grid = await ev(() => { const names = Array.from(document.querySelectorAll('.sheet-action-grid .sheet-btn')).filter(b => getComputedStyle(b).display !== 'none').map(b => b.innerText.trim()); const col = id => (getComputedStyle(document.getElementById(id)).color.match(/\d+/g) || []).map(Number); return { names, red: col('sheetBtnUninstall'), blue: col('sheetBtnFreeze'), ask: document.getElementById('sheetAsk').parentElement.className, askAlign: getComputedStyle(document.getElementById('sheetAsk')).alignSelf, symbol: document.getElementById('sheetAsk').innerText }; });
  check('2b. the sheet\'s buttons: App Info took the place of Uninstall (after Clear Data) and Uninstall that of App Info (after Rem Updates); Uninstall is red, Freeze is blue', grid.names.indexOf('App Info') === grid.names.indexOf('Clear Data') + 1 && grid.names.indexOf('Uninstall') === grid.names.indexOf('Rem Updates') + 1 && grid.names.indexOf('Uninstall') === grid.names.indexOf('App Info') + 2 && grid.red[0] > grid.red[1] + 60 && grid.red[0] > grid.red[2] + 60 && grid.blue[2] > grid.blue[0] + 60 && grid.blue[2] > grid.blue[1] - 20, JSON.stringify(grid));
  check('   the Ask agent chip is back in the header\'s right column, towards the left of it, and has no symbol', /sheet-header-actions/.test(grid.ask) && grid.askAlign === 'flex-start' && grid.symbol === 'Ask agent', JSON.stringify(grid));

  // no default agent chosen: the window still opens, says so and leaves the free Web search; nothing is asked
  await ev(() => { window.__sent.length = 0; document.getElementById('sheetAsk').click(); }); await sleep(450);
  const noAgent = await ev(() => ({ modal: document.getElementById('agentAskModal').classList.contains('show'), ai: document.getElementById('agentAskAi').innerText, sent: window.__sent.length, web: !!document.querySelector('#agentAskModal [onclick="agentAskWeb()"]') }));
  check('3. with no default agent chosen the button opens the window, which says to choose and connect an agent and keeps Web search (free, no setup); nothing is sent', noAgent.modal && /choose a default agent/.test(noAgent.ai) && /Web search/.test(noAgent.ai) && noAgent.web && noAgent.sent === 0, JSON.stringify(noAgent));
  await ev(() => document.querySelector('#agentAskAi a').click()); await sleep(450);
  const redirected = await ev(() => ({ view: currentViewName(), card: document.getElementById('agentCard').classList.contains('flash'), modal: document.getElementById('agentAskModal').classList.contains('show'), insp: document.getElementById('inspectorModal').classList.contains('show') }));
  check('   its Open Settings link leads to the Default agent card', redirected.view === 'prefs' && redirected.card && !redirected.modal && !redirected.insp, JSON.stringify(redirected));

  // the Settings card: right after Language
  const card = await ev(() => { const l = document.getElementById('languageCard'), c = document.getElementById('agentCard'); return { after: l.nextElementSibling === c, opts: [...document.querySelectorAll('#askAgentSelect option')].map(o => o.value), val: document.getElementById('askAgentSelect').value, note: document.getElementById('askAgentNote').innerText }; });
  check('4. the Default agent card follows the Language card; no agent is chosen, and the agents can be picked', card.after && card.val === '' && card.opts[0] === '' && card.opts.includes('claude') && card.opts.includes('gemini') && !card.opts.includes('none') && /No agent is chosen/.test(card.note), JSON.stringify(card));
  const oss = await ev(() => { txBuildAgentSelect(); return ({ bu: txAgentDef('browseruse'), c4: txAgentDef('crawl4ai'), inDefault: [...document.querySelectorAll('#askAgentSelect option')].map(o => o.value).filter(v => v === 'browseruse' || v === 'crawl4ai'), inCli: [...document.querySelectorAll('#txAgent option')].map(o => o.value).filter(v => v === 'browseruse' || v === 'crawl4ai') }); });
  check('4b. Browser Use and Crawl4AI are explained entries of the free open-source list (not chat agents): in the Command-Line Interface list, not in the Default agent choice', oss.bu && oss.c4 && oss.bu.api === 'info' && oss.c4.api === 'info' && oss.bu.group === 'oss' && oss.c4.group === 'oss' && oss.inDefault.length === 0 && oss.inCli.length === 2, JSON.stringify(oss));
  await ev(() => { const s = document.getElementById('askAgentSelect'); s.value = 'claude'; s.dispatchEvent(new Event('change')); }); await sleep(100);
  const picked = await ev(() => ({ id: askAgentId, saved: kvGet('ask_agent', ''), note: document.getElementById('askAgentNote').innerText }));
  check('5. choosing an agent saves it; one that is not connected yet says so', picked.id === 'claude' && picked.saved === 'claude' && /not connected yet/.test(picked.note), JSON.stringify(picked));
  await ev(() => { switchView('apps'); openInspector('com.example.unknown'); }); await sleep(250);
  await ev(() => document.getElementById('sheetAsk').click()); await sleep(450);
  check('   pressing Ask agent with an agent that is not connected also opens the window with the same message, and nothing is sent', await ev(() => document.getElementById('agentAskModal').classList.contains('show') && /choose a default agent/.test(document.getElementById('agentAskAi').innerText) && window.__sent.length === 0));
  await ev(() => { agentAskClose(); closeInspector(); });

  // with an agent that works
  await ev(() => {
    window.txAgentReady = () => true;
    window.txTurnApi = async (ag, model, effort, system, msgs, onText) => { window.__sent.push({ system, msgs }); onText('Caution: '); onText('this is the Bixby-like helper.'); return { text: 'Caution: this is the Bixby-like helper.', usage: { in: 1, out: 1 } }; };
    switchView('apps'); openInspector('com.example.unknown');
  }); await sleep(300);
  await ev(() => document.getElementById('sheetAsk').click()); await sleep(350);
  const m = await ev(() => ({ open: document.getElementById('agentAskModal').classList.contains('show'), title: document.getElementById('agentAskTitle').innerText, subj: document.getElementById('agentAskSubject').innerText, sentText: document.getElementById('agentAskSent').innerText }));
  check('6. it opens a window with the package and shows exactly what is sent', m.open && /safe to disable/i.test(m.title) && m.subj === 'com.example.unknown' && /Package: com\.example\.unknown/.test(m.sentText) && /App name: Unknown One/.test(m.sentText), JSON.stringify(m));
  const sent = await ev(() => window.__sent[0]);
  check('7. the agent was asked at once: the package name, the app name, that it is a system app and that it is disabled', sent && /Package: com\.example\.unknown/.test(sent.msgs[0].text) && /App name: Unknown One/.test(sent.msgs[0].text) && /system app/.test(sent.msgs[0].text) && /State: disabled/.test(sent.msgs[0].text), JSON.stringify(sent));
  check('   and a system prompt that asks for a verdict and admits not knowing', sent && /Safe, Caution or Do not touch/.test(sent.system) && /do not recognize/.test(sent.system));
  const ans = await ev(() => ({ t: document.getElementById('agentAskAi').innerText }));
  check('8. the answer is shown, with a note of what was sent', /Caution: this is the Bixby-like helper\./.test(ans.t) && /Asking Claude/.test(ans.t) && /The text above is sent to them/.test(ans.t), JSON.stringify(ans));
  await ev(() => document.querySelector('#agentAskModal .lc-ent-btns .mode-action-btn:nth-child(2)').click()); await sleep(100);
  check('9. Web search opens a search for the package', (await ev(() => window.__opened.join('|'))).includes('google.com/search?q=android%20com.example.unknown%20safe%20to%20disable'));
  await ev(() => agentAskClose());
  check('10. closing leaves the window shut', await ev(() => !document.getElementById('agentAskModal').classList.contains('show')));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
