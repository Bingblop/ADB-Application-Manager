// v5.7 terminal: "adb devices" button replaced by Rish mode (switch to Shizuku + persistent Rish shell).
const { chromium, PAGE, OUT } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__log = [];
    window.__mode = { configured: 'adb_tcp', shizukuAuthorized: true, shizukuMsg: '', shizukuOk: true };
    window.__shell = { cwd: '/', alive: true, running: false };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {}, openShizukuApp() { window.__log.push('openShizukuApp'); },
      getWorkingMode() {
        const m = window.__mode;
        return JSON.stringify({ activeMode: m.configured, configuredMode: m.configured, modeAvailable: true, isPrivileged: true,
          adbTcp: { connected: m.configured === 'adb_tcp', port: 5555 }, adbWireless: { connected: false }, rootAvailable: false,
          shizuku: { installed: true, running: true, authorized: m.shizukuAuthorized } });
      },
      checkShizukuStatus() { return JSON.stringify({ installed: true, running: true, authorized: window.__mode.shizukuAuthorized }); },
      selectWorkingMode(mode) {
        window.__log.push('select:' + mode);
        window.__mode.configured = mode;
        if (mode === 'shizuku') return JSON.stringify({ ok: window.__mode.shizukuOk, mode, message: window.__mode.shizukuMsg || 'Using Shizuku (UID 2000)' });
        return JSON.stringify({ ok: true, mode, message: 'ok' });
      },
      executeShell(cmd) { window.__log.push('exec:' + cmd); return 'plain-output\n'; },
      rishStart() {
        window.__log.push('rishStart');
        setTimeout(() => window.onRishStarted({ ok: true, reused: false, uid: 2000, host: 'husky', cwd: '/', prompt: 'husky:/ $' }), 30);
        return 'starting';
      },
      rishRun(cmd, id) {
        window.__log.push('rishRun:' + cmd);
        if (!window.__shell.alive) return 'no_shell';
        if (window.__shell.running) return 'busy';
        window.__shell.running = true;
        const done = (extra) => { window.__shell.running = false; window.onRishDone(id, Object.assign({ exit: 0, cwd: window.__shell.cwd, prompt: 'husky:' + window.__shell.cwd + ' $' }, extra || {})); };
        if (/^cd /.test(cmd)) { window.__shell.cwd = cmd.slice(3).trim(); setTimeout(() => done(), 20); }
        else if (cmd === 'pwd') { setTimeout(() => { window.onRishOutput(id, window.__shell.cwd + '\n'); done(); }, 20); }
        else if (cmd === 'printf nonl') { setTimeout(() => { window.onRishOutput(id, 'nonl'); done(); }, 20); }
        else if (cmd === 'false') { setTimeout(() => done({ exit: 1 }), 20); }
        else if (cmd === 'stream') { setTimeout(() => window.onRishOutput(id, 'line1\n'), 20); setTimeout(() => window.onRishOutput(id, 'line2\n'), 300); setTimeout(() => done(), 700); }   // long enough to press Enter in the middle
        else if (cmd === 'forever') { window.__stopId = id; }   // runs until rishStop
        else if (cmd === 'bye') { setTimeout(() => { window.__shell.alive = false; done({ exited: true, exit: 3 }); }, 20); }
        else setTimeout(() => { window.onRishOutput(id, 'ran:' + cmd + '\n'); done(); }, 20);
        return 'ok';
      },
      rishStop() {
        window.__log.push('rishStop');
        if (window.__stopId) { const id = window.__stopId; window.__stopId = null; setTimeout(() => { window.__shell.running = false; window.onRishDone(id, { exit: 143, stopped: true, cwd: window.__shell.cwd, prompt: 'husky:' + window.__shell.cwd + ' $' }); }, 30); }
      },
      rishClose() { window.__log.push('rishClose'); window.__shell.running = false; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.waitForTimeout(150);
  const term = () => page.locator('#termOutput').innerText();
  const sleep = ms => page.waitForTimeout(ms);
  const type = async cmd => { await page.fill('#termCmd', cmd); await page.press('#termCmd', 'Enter'); };

  // 1) The ADB-devices button is gone; a Rish mode button replaced it.
  const btns = await page.locator('#view-terminal .mode-btn-row button').allInnerTexts();
  console.log('1. no "adb devices" button:', !btns.some(t => /adb devices/i.test(t)), '| Rish button present:', btns.some(t => /Rish mode/.test(t)), JSON.stringify(btns));
  const html = await page.content();
  console.log('   no stale adb-devices handler in markup:', !/value = 'adb devices'/.test(html));

  // 2) Before Rish: normal commands go through executeShell.
  await type('id'); await sleep(60);
  await page.evaluate(() => closeCommandResultsModal()); await sleep(60);
  const log0 = await page.evaluate(() => window.__log.slice());
  console.log('2. normal mode uses executeShell:', log0.includes('exec:id') && !log0.some(l => l.startsWith('rishRun')));

  // 3) Tap Rish: working mode switches to Shizuku, then a Rish shell opens.
  await page.locator('#rishBtn').click(); await sleep(400);
  const log1 = await page.evaluate(() => window.__log.slice());
  const selIdx = log1.indexOf('select:shizuku'), startIdx = log1.indexOf('rishStart');
  console.log('3. switched to Shizuku, then started the shell:', selIdx >= 0 && startIdx > selIdx, JSON.stringify(log1));
  const btnText = await page.locator('#rishBtn').innerText();
  const btnOn = await page.locator('#rishBtn').evaluate(e => e.classList.contains('rish-on'));
  const promptLine = await page.locator('#termPromptLine').evaluate(e => ({ shown: getComputedStyle(e).display !== 'none', text: e.innerText }));
  const title = await page.locator('#termActiveModeTitle').innerText();
  const badge = await page.locator('#execModeText').innerText();
  console.log('   button shows Exit Rish + highlighted:', /Exit Rish/.test(btnText) && btnOn, JSON.stringify(btnText));
  console.log('   prompt line shown:', promptLine.shown && promptLine.text === 'husky:/ $', JSON.stringify(promptLine));
  console.log('   header says Rish shell (uid 2000):', /Rish shell/.test(title) && /uid 2000/.test(title), JSON.stringify(title));
  console.log('   working-mode badge now Shizuku:', /Shizuku/.test(badge), JSON.stringify(badge));
  const banner = await term();
  console.log('   banner printed:', /Rish shell ready/.test(banner) && /shell \(uid 2000\)/.test(banner));
  const ph = await page.locator('#termCmd').getAttribute('placeholder');
  console.log('   placeholder changed:', /Rish/.test(ph), JSON.stringify(ph));

  // 4) Commands go to the Rish shell, echoed with the prompt; cd persists in the prompt.
  await type('pwd'); await sleep(120);
  let t = await term();
  console.log('4. command echoed with prompt + output shown:', /husky:\/ \$ pwd\n\/\n/.test(t), JSON.stringify(t.slice(-40)));
  const log2 = await page.evaluate(() => window.__log.slice());
  console.log('   went through rishRun, not executeShell:', log2.includes('rishRun:pwd') && !log2.includes('exec:pwd'));
  await type('cd /sdcard/Download'); await sleep(120);
  const pl = await page.locator('#termPromptLine').innerText();
  console.log('   prompt follows cd:', pl === 'husky:/sdcard/Download $', JSON.stringify(pl));
  await type('pwd'); await sleep(120);
  t = await term();
  console.log('   next echo uses the new prompt + state kept:', /husky:\/sdcard\/Download \$ pwd\n\/sdcard\/Download\n/.test(t));

  // 5) Output without a trailing newline does not glue onto the next prompt; non-zero exit is noted.
  await type('printf nonl'); await sleep(120);
  await type('false'); await sleep(120);
  t = await term();
  console.log('5. no-newline output separated from next prompt:', /nonl\nhusky:\/sdcard\/Download \$ false\n/.test(t), JSON.stringify(t.slice(-70)));
  console.log('   non-zero exit shown:', /\[exit 1\]/.test(t));

  await page.screenshot({ path: OUT + '/rish_term.png' });

  // 6) Streaming output while running + RUN turns into STOP, Enter ignored while running.
  await type('stream'); await sleep(35);
  const runBtn1 = await page.locator('#termRunBtn').evaluate(e => ({ text: e.innerText, stop: e.classList.contains('stop') }));
  await page.fill('#termCmd', 'pwd'); await page.press('#termCmd', 'Enter'); await sleep(10);
  const stillTyped = await page.locator('#termCmd').inputValue();
  const logMid = await page.evaluate(() => window.__log.filter(l => l === 'rishRun:pwd').length);
  console.log('6. RUN became STOP while running:', runBtn1.text === 'STOP' && runBtn1.stop, JSON.stringify(runBtn1));
  console.log('   Enter during a run is ignored and keeps the typed text:', stillTyped === 'pwd' && logMid === 2, 'pwd runs so far:', logMid);
  await page.waitForFunction(() => document.getElementById('termRunBtn').innerText === 'RUN', null, { timeout: 5000 });
  t = await term();
  const runBtn2 = await page.locator('#termRunBtn').evaluate(e => ({ text: e.innerText, stop: e.classList.contains('stop') }));
  console.log('   streamed lines arrived in order:', /line1\nline2\n/.test(t), '| button back to RUN:', runBtn2.text === 'RUN' && !runBtn2.stop);
  await page.fill('#termCmd', '');

  // 7) STOP ends a long-running command; shell stays open.
  await type('forever'); await sleep(40);
  await page.locator('#termRunBtn').click(); await sleep(150);
  t = await term();
  const log3 = await page.evaluate(() => window.__log.slice());
  const stillActive = await page.evaluate(() => rishActive);
  console.log('7. STOP calls rishStop and notes it:', log3.includes('rishStop') && /\[stopped\]/.test(t), '| shell still open:', stillActive);

  // 8) clear is local; no round trip to the shell.
  const nBefore = await page.evaluate(() => window.__log.filter(l => l.startsWith('rishRun')).length);
  await type('clear'); await sleep(40);
  const nAfter = await page.evaluate(() => window.__log.filter(l => l.startsWith('rishRun')).length);
  t = await term();
  console.log('8. "clear" clears locally:', t.trim() === '' && nBefore === nAfter);

  // 9) Tapping Exit Rish closes the shell and returns to normal commands (mode stays Shizuku).
  await page.locator('#rishBtn').click(); await sleep(150);
  const log4 = await page.evaluate(() => window.__log.slice());
  const btnText2 = await page.locator('#rishBtn').innerText();
  const lineHidden = await page.locator('#termPromptLine').evaluate(e => getComputedStyle(e).display === 'none');
  t = await term();
  console.log('9. Exit Rish closes the shell:', log4.includes('rishClose') && /Rish mode/.test(btnText2) && lineHidden, JSON.stringify(btnText2));
  console.log('   note says mode is still Shizuku:', /still Shizuku/.test(t), '| configured mode untouched:', await page.evaluate(() => window.__mode.configured) === 'shizuku');
  await type('whoami'); await sleep(60);
  await page.evaluate(() => closeCommandResultsModal()); await sleep(60);
  const log5 = await page.evaluate(() => window.__log.slice());
  console.log('   normal executeShell again:', log5.includes('exec:whoami'));

  // 10) Re-enter: already on Shizuku + authorized, so no extra mode switch; typing exit leaves.
  const selBefore = log5.filter(l => l === 'select:shizuku').length;
  await page.locator('#rishBtn').click(); await sleep(300);
  const log6 = await page.evaluate(() => window.__log.slice());
  console.log('10. re-enter skips the mode switch:', log6.filter(l => l === 'select:shizuku').length === selBefore && log6.filter(l => l === 'rishStart').length === 2);
  await type('exit'); await sleep(80);
  const active2 = await page.evaluate(() => rishActive);
  console.log('    typing exit leaves Rish:', active2 === false, '| not sent to the shell:', !(await page.evaluate(() => window.__log.includes('rishRun:exit'))));

  // 11) The shell ending itself (exit inside the shell) leaves Rish with a note.
  await page.locator('#rishBtn').click(); await sleep(300);
  await type('bye'); await sleep(150);
  t = await term();
  const active3 = await page.evaluate(() => rishActive);
  console.log('11. shell exit leaves Rish with a note:', !active3 && /Rish shell ended \(exit 3\)/.test(t), JSON.stringify(t.trim().split('\n').pop()));
  await page.evaluate(() => { window.__shell.alive = true; });

  // 12) Switching to another working mode while in Rish leaves it.
  await page.locator('#rishBtn').click(); await sleep(300);
  const activeA = await page.evaluate(() => rishActive);
  await page.evaluate(() => selectModeUI('adb_tcp')); await sleep(250);
  const activeB = await page.evaluate(() => rishActive);
  t = await term();
  console.log('12. picking another mode leaves Rish:', activeA === true && activeB === false && /working mode changed/.test(t));

  // 13) Shizuku not yet authorized: the prompt is shown first, then Rish starts when it is approved.
  await page.evaluate(() => { window.__mode.configured = 'adb_tcp'; window.__mode.shizukuAuthorized = false; window.__mode.shizukuOk = false; window.__mode.shizukuMsg = 'Approve the Shizuku prompt to finish switching'; });
  const startsBefore = await page.evaluate(() => window.__log.filter(l => l === 'rishStart').length);
  await page.locator('#rishBtn').click(); await sleep(300);
  const waiting = await page.evaluate(() => ({ active: rishActive, awaiting: rishAwaitingAuth, starts: window.__log.filter(l => l === 'rishStart').length }));
  console.log('13. waits for the Shizuku prompt (no shell yet):', !waiting.active && waiting.awaiting && waiting.starts === startsBefore, JSON.stringify(waiting));
  await page.evaluate(() => { window.__mode.shizukuAuthorized = true; window.onShizukuPermissionResult(true); });
  await sleep(300);
  const afterAuth = await page.evaluate(() => ({ active: rishActive, awaiting: rishAwaitingAuth }));
  console.log('    approval starts the shell:', afterAuth.active && !afterAuth.awaiting);
  await page.locator('#rishBtn').click(); await sleep(150);   // leave

  // 14) Denied / not running / not installed: stays out of Rish and shows the reason.
  await page.evaluate(() => { window.__mode.configured = 'adb_tcp'; window.__mode.shizukuAuthorized = false; window.__mode.shizukuOk = false; window.__mode.shizukuMsg = 'Shizuku is not running. Start it in the Shizuku app first.'; });
  await page.locator('#rishBtn').click(); await sleep(300);
  const nr = await page.evaluate(() => ({ active: rishActive, awaiting: rishAwaitingAuth, opened: window.__log.includes('openShizukuApp') }));
  const toast = await page.locator('#toast, .toast').first().innerText().catch(() => '');
  console.log('14. Shizuku not running: no shell, Shizuku app opened:', !nr.active && !nr.awaiting && nr.opened, JSON.stringify(toast));
  await page.evaluate(() => { window.onShizukuPermissionResult(false); });
  await sleep(100);
  console.log('    a denied prompt does not start anything:', !(await page.evaluate(() => rishActive)));

  // 15) Start failure from the app is reported and leaves the button usable.
  await page.evaluate(() => {
    window.__mode.shizukuAuthorized = true; window.__mode.shizukuOk = true; window.__mode.shizukuMsg = '';
    window.AndroidBridge.rishStart = () => { setTimeout(() => window.onRishStarted({ ok: false, message: 'Could not start the Rish shell: boom' }), 20); return 'starting'; };
  });
  await page.locator('#rishBtn').click(); await sleep(300);
  const failed = await page.evaluate(() => ({ active: rishActive, starting: rishStarting }));
  const btnAfterFail = await page.locator('#rishBtn').innerText();
  console.log('15. start failure leaves Rish off and the button usable:', !failed.active && !failed.starting && /Rish mode/.test(btnAfterFail), JSON.stringify(btnAfterFail));

  // 16) Large streamed output stays responsive (text nodes appended, not re-parsed HTML).
  await page.evaluate(() => {
    window.AndroidBridge.rishStart = () => { setTimeout(() => window.onRishStarted({ ok: true, reused: true, uid: 0, host: 'husky', cwd: '/', prompt: 'husky:/ #' }), 10); return 'starting'; };
  });
  await page.locator('#rishBtn').click(); await sleep(200);
  const rootInfo = await page.evaluate(() => ({ title: document.getElementById('termActiveModeTitle').innerText, pl: document.getElementById('termPromptLine').innerText }));
  console.log('16. root shell labelled and # prompt:', /root \(uid 0\)/.test(rootInfo.title) && rootInfo.pl === 'husky:/ #', JSON.stringify(rootInfo));
  const t0 = Date.now();
  await page.evaluate(() => { let s = ''; for (let i = 0; i < 3000; i++) s += 'line number ' + i + ' of the stream\n'; for (let k = 0; k < 40; k++) window.onRishOutput('x', s + (k === 39 ? 'THE-END\n' : '')); });
  const dt = Date.now() - t0;
  await sleep(100);
  const big = await page.evaluate(() => { const t = document.getElementById('termOutput'); return { len: t.textContent.length, head: t.firstChild.textContent, tail: t.textContent.slice(-8), top: t.scrollTop, max: t.scrollHeight - t.clientHeight }; });
  console.log('    4 MB streamed in under 3 s stays responsive and bounded:', dt < 3000 && big.len < 900000 && big.len > 400000, 'len=' + big.len);
  console.log('    older output trimmed with a marker, newest kept, scrolled to the end:', /older output trimmed/.test(big.head) && big.tail === 'THE-END\n' && big.max - big.top < 5, JSON.stringify({ head: big.head.trim(), top: big.top, max: big.max }));

  // 17) Long prompts are shortened from the left so the folder at the end stays visible.
  await page.evaluate(() => { rishPromptText = 'husky:/storage/emulated/0/Android/data/com.example.verylongpackagename/files $'; rishSyncUi(); });
  const longPl = await page.locator('#termPromptLine').innerText();
  console.log('17. long prompt shortened from the left:', longPl.length <= 36 && longPl.startsWith('…') && longPl.endsWith('files $'), JSON.stringify(longPl));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
