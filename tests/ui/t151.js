// v7.12.8: Settings > "Default Ask Agent" (a research agent, never a coding one; Perplexity until the person chooses) and About > "Authorization Manager"
// (this app's own code for the auth extra of an intent: the switch, the code, copy, the refresh icon button, the example, the recent uses).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__auth = { enabled: false, code: 'ABCDE-FGHJK-MNPQR-STVWX-YZ012', locked: 0, recent: [] }; window.__authCalls = []; window.__copied = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getAboutInfo() { return JSON.stringify({ versionName: '7.12.8-Pro', versionCode: 850, pkg: 'com.bloatware.bingblop' }); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      authStatus() { window.__authCalls.push('status'); return JSON.stringify(window.__auth); },
      authSetEnabled(on) { window.__authCalls.push('enabled:' + on); window.__auth.enabled = on; return JSON.stringify(window.__auth); },
      authRefresh() { window.__authCalls.push('refresh'); window.__auth.code = 'NEWCO-DE123-45678-9ABCD-EFGHJ'; return JSON.stringify(window.__auth); },
      authClearRecent() { window.__authCalls.push('clear'); window.__auth.recent = []; return JSON.stringify(window.__auth); }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); window.copyText = t => window.__copied.push(t); });

  // ---- Default Ask Agent ----
  await ev(() => { switchView('prefs'); }); await sleep(300);
  const d = await ev(() => {
    const card = document.getElementById('agentCard'), sel = document.getElementById('askAgentSelect');
    const groups = [...sel.querySelectorAll('optgroup')].map(g => g.label + ':' + [...g.querySelectorAll('option')].map(o => o.value).join('+'));
    return { title: card.querySelector('.color-card-title').innerText, sub: card.querySelector('.color-card-subtitle').innerText, id: askAgentId, val: sel.value, first: sel.options[0].text, groups, aria: sel.getAttribute('aria-label'), note: document.getElementById('askAgentNote').innerText };
  });
  check('1. the card is "Default Ask Agent" and says it is a research agent, not a coding agent', d.title === 'Default Ask Agent' && /research agent/.test(d.sub) && /not a coding agent/.test(d.sub) && d.aria === 'Default Ask Agent', d.title + ' | ' + d.sub.slice(0, 80));
  check('2. nothing chosen yet means Perplexity (web research); the first line of the list is the free web lookup', d.id === 'perplexity' && d.val === 'perplexity' && d.first === 'No default (free web lookup only)', JSON.stringify([d.id, d.val, d.first]));
  check('3. the list: web research (Perplexity, Browser Use, Crawl4AI) first; no coding agent (Cursor, Copilot, OpenCode) anywhere', d.groups[0] === 'Web research (recommended):perplexity+browseruse+crawl4ai' && !/cursor|copilot|opencode/.test(d.groups.join()) && /claude/.test(d.groups.join()) && /ollama/.test(d.groups.join()), JSON.stringify(d.groups));
  check('4. without a key it says it reads the web for you until the agent is connected', /not connected yet/.test(d.note) && /reads the web for you/.test(d.note), d.note);
  const rd = await ev(() => ({ ready: askAgentReadyNow(), def: askAgentDef() && askAgentDef().id }));
  check('   so the Ask agent buttons fall back to the web lookup (not ready) while the default is Perplexity', rd.ready === false && rd.def === 'perplexity', JSON.stringify(rd));
  await ev(() => askAgentChoose('')); await sleep(100);
  const n = await ev(() => ({ id: askAgentId, def: askAgentDef(), val: document.getElementById('askAgentSelect').value, note: document.getElementById('askAgentNote').innerText }));
  check('5. choosing "No default" is kept (it does not go back to Perplexity) and the card says so', n.id === '' && n.def === null && n.val === '' && /No agent is chosen/.test(n.note), JSON.stringify(n));
  await ev(() => askAgentChoose('opencode')); await sleep(50);
  check('6. a coding agent cannot be chosen by id either', await ev(() => askAgentDef() === null));
  await ev(() => askAgentChoose('perplexity'));

  // ---- Authorization Manager ----
  await ev(() => switchView('about')); await sleep(300);
  const a = await ev(() => ({
    title: document.querySelector('#authCard .color-card-title').innerText, code: document.getElementById('authCode').innerText, on: document.getElementById('authOn').checked,
    note: document.getElementById('authNote').innerText, ex: document.getElementById('authExample').innerText, svgs: document.querySelectorAll('#authCard .auth-icon-btn svg').length,
    labels: [...document.querySelectorAll('#authCard .auth-icon-btn')].map(x => x.getAttribute('aria-label')), emoji: /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(document.getElementById('authCard').innerText) }));
  check('7. About has an Authorization Manager card with the code (5 groups of 5), the switch off, and a note that nothing is accepted', a.title === 'Authorization Manager' && a.code === 'ABCDE-FGHJK-MNPQR-STVWX-YZ012' && !a.on && /Off/.test(a.note), JSON.stringify(a));
  check('8. a copy button and a refresh button, both icons (SVG) with names, no emoji', a.svgs === 2 && a.labels.join() === 'Copy the code,Make a new code' && !a.emoji, JSON.stringify(a.labels));
  check('9. the example is an adb command with the action, the auth extra with the code and a package', /am start -n com\.bloatware\.bingblop\/\.AuthLaunchActivity/.test(a.ex) && /-a com\.bloatware\.bingblop\.action\.AUTH_LAUNCH/.test(a.ex) && /--es auth ABCDE-FGHJK-MNPQR-STVWX-YZ012/.test(a.ex) && /--es package /.test(a.ex), a.ex);
  await page.click('#authOn + .switch-track'); await sleep(150);
  const t = await ev(() => ({ calls: window.__authCalls.slice(), on: document.getElementById('authOn').checked, note: document.getElementById('authNote').innerText }));
  check('10. the switch turns the door on in the app, and the note says an intent with the code is accepted', t.calls.includes('enabled:true') && t.on && /intent with this code/.test(t.note), JSON.stringify(t));
  await page.click('#authCopyBtn'); await sleep(50);
  check('11. Copy puts the code on the clipboard', await ev(() => window.__copied[0] === 'ABCDE-FGHJK-MNPQR-STVWX-YZ012'));
  await page.click('#authRefreshBtn'); await sleep(200);
  const ask = await ev(() => ({ open: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, calls: window.__authCalls.filter(c => c === 'refresh').length }));
  check('12. the refresh button asks first, and nothing changes yet', ask.open && ask.title === 'Make a new code?' && ask.calls === 0, JSON.stringify(ask));
  await page.click('#mpAskOk'); await sleep(250);
  const r = await ev(() => ({ code: document.getElementById('authCode').innerText, ex: document.getElementById('authExample').innerText, calls: window.__authCalls.filter(c => c === 'refresh').length }));
  check('13. confirming makes the new code, shown at once and in the example', r.calls === 1 && r.code === 'NEWCO-DE123-45678-9ABCD-EFGHJ' && /--es auth NEWCO-DE123-45678-9ABCD-EFGHJ/.test(r.ex), JSON.stringify(r));
  await ev(() => { window.__auth.recent = [{ at: Date.now(), verdict: 'ok', what: 'package com.android.settings' }, { at: Date.now() - 5000, verdict: 'wrong', what: 'package com.example.x' }]; window.__auth.locked = 42; authLoad(); });
  const rc = await ev(() => ({ rows: document.querySelectorAll('#authRecent .auth-recent-row').length, txt: document.getElementById('authRecent').innerText, note: document.getElementById('authNote').innerText }));
  check('14. recent uses are listed (what and the verdict), and a lock after wrong codes is shown with its seconds', rc.rows === 2 && /com\.android\.settings/.test(rc.txt) && /wrong/.test(rc.txt) && /Locked for 42 more seconds/.test(rc.note), JSON.stringify(rc));
  await ev(() => { document.querySelector('#authRecent .batch-tool-link').click(); }); await sleep(100);
  check('15. Clear the list empties them', await ev(() => window.__authCalls.includes('clear') && document.querySelectorAll('#authRecent .auth-recent-row').length === 0));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
