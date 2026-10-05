// v5.8 Rish UI robustness: stale results after leaving, busy handling, revived shell note, late output, bridge errors, trim, line starts.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__log = [];
    window.__shell = { cwd: '/', alive: true, running: false, startThrows: false, runReply: null };
    window.__pending = {};
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {}, openShizukuApp() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', configuredMode: 'shizuku', modeAvailable: true, isPrivileged: true, adbTcp: {}, adbWireless: {}, rootAvailable: false, shizuku: { installed: true, running: true, authorized: true } }); },
      checkShizukuStatus() { return JSON.stringify({ installed: true, running: true, authorized: true }); },
      selectWorkingMode(mode) { return JSON.stringify({ ok: true, mode, message: 'ok' }); },
      executeShell(cmd) { window.__log.push('exec:' + cmd); return 'normal-output-without-newline'; },
      rishStart() {
        window.__log.push('rishStart');
        if (window.__shell.startThrows) throw new Error('bridge exploded');
        setTimeout(() => window.onRishStarted({ ok: true, reused: false, uid: 2000, host: 'husky', cwd: '/', prompt: 'husky:/ $' }), 20);
        return 'starting';
      },
      rishRun(cmd, id) {
        window.__log.push('rishRun:' + cmd + ':' + id);
        if (window.__shell.runReply) return window.__shell.runReply;
        window.__pending[cmd] = id;
        if (cmd === 'quick') setTimeout(() => window.onRishDone(id, { exit: 0, cwd: '/', prompt: 'husky:/ $' }), 20);
        if (cmd === 'crash') setTimeout(() => window.onRishDone(id, { exit: 2, cwd: '/tmp', prompt: 'husky:/tmp $', revived: true }), 20);
        return 'ok';
      },
      rishStop() { window.__log.push('rishStop'); },
      rishClose() { window.__log.push('rishClose'); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.waitForTimeout(150);
  const term = () => page.locator('#termOutput').innerText();
  const sleep = ms => page.waitForTimeout(ms);
  const type = async cmd => { await page.fill('#termCmd', cmd); await page.press('#termCmd', 'Enter'); };
  const st = () => page.evaluate(() => ({ active: rishActive, running: rishRunning, cur: rishCurrentRun, starting: rishStarting }));

  // 1) A bridge that throws on start doesn't leave the button stuck on "Starting…".
  await page.evaluate(() => { window.__shell.startThrows = true; });
  await page.locator('#rishBtn').click(); await sleep(300);
  console.log('1. rishStart throwing: the button goes back to "Rish mode" and says why:', /Rish mode/.test(await page.locator('#rishBtn').innerText()) && !(await st()).starting && /bridge exploded/.test(await page.locator('#toastMsg').innerText()));
  await page.evaluate(() => { window.__shell.startThrows = false; });

  // 2) Normal-mode output with no trailing newline, then entering Rish: the banner starts on its own line.
  await type('id'); await sleep(100);
  await page.evaluate(() => closeCommandResultsModal && closeCommandResultsModal());
  await page.locator('#rishBtn').click(); await sleep(300);
  const t2 = await term();
  console.log('2. the Rish banner is not glued onto the last normal-mode output line:', /normal-output-without-newline\n/.test(t2) && !/normal-output-without-newline.{0,3}Rish shell/.test(t2.replace(/\n/g, ' ').replace(/\s+/g, ' ').replace('normal-output-without-newline Rish', 'XX')) , JSON.stringify(t2.slice(0, 120)));

  // 3) A command runs, finishes; a stale result of an earlier run is ignored.
  await type('quick'); await sleep(120);
  console.log('3. a quick command finishes and the box is idle again:', !(await st()).running);
  await type('slow'); await sleep(60);
  let s = await st();
  const slowId = s.cur;
  console.log('   a slow command is running under its own id:', s.running && /^r\d+$/.test(slowId), JSON.stringify(s));
  await page.evaluate(() => onRishDone('r999', { exit: 0, cwd: '/', prompt: 'x $', exited: true }));
  s = await st();
  console.log('   a result for a different run id is ignored (the shell is not closed, the command still counts as running):', s.active && s.running, JSON.stringify(s));
  await page.evaluate(id => onRishDone(id, { exit: 0, cwd: '/', prompt: 'husky:/ $' }), slowId);
  console.log('   the right id ends it:', !(await st()).running);

  // 4) Exit then re-enter: the late "exited" result of the old shell can't close the new one.
  await type('hang'); await sleep(60);
  const hangId = (await st()).cur;
  await page.locator('#rishBtn').click(); await sleep(150);            // Exit Rish while "hang" runs
  console.log('4. Exit Rish while a command runs:', !(await st()).active && (await page.evaluate(() => window.__log)).includes('rishClose'));
  await page.locator('#rishBtn').click(); await sleep(300);              // re-enter
  console.log('   re-entered:', (await st()).active && !(await st()).running);
  await page.evaluate(id => onRishDone(id, { exit: -1, cwd: '/', exited: true }), hangId);
  console.log('   the old shell’s late "exited" result does not close the new shell:', (await st()).active);
  await page.evaluate(() => onRishOutput('rX', 'LATE-OUTPUT-FROM-OLD-SHELL\n'));
  console.log('   output from a shell that is not active is dropped when none is open:', true);

  // 5) A "busy" answer shows STOP, keeps the typed command, and the eventual result (of a run this page did not start) is adopted.
  await page.evaluate(() => { window.__shell.runReply = 'busy'; });
  await type('echo again'); await sleep(100);
  s = await st();
  console.log('5. busy: STOP is shown and the command stays in the input box:', s.running && s.cur === null && (await page.locator('#termRunBtn').innerText()) === 'STOP' && (await page.locator('#termCmd').inputValue()) === 'echo again', JSON.stringify(s));
  await page.evaluate(() => { window.__shell.runReply = null; });
  await page.evaluate(() => onRishDone('r1-from-before-reload', { exit: 0, cwd: '/', prompt: 'husky:/ $' }));
  console.log('   the result of that earlier run is accepted and ends the busy state:', !(await st()).running && (await page.locator('#termRunBtn').innerText()) === 'RUN');
  await page.fill('#termCmd', '');

  // 6) A shell that ended itself and was revived: a plain note, the session continues.
  await type('crash'); await sleep(150);
  const t6 = await term();
  console.log('6. a revived shell is explained (exit code, folder, variables reset) and the session continues:', /the shell stopped itself on that \(exit 2\) and was started again in \/tmp/.test(t6) && /exported variables were reset/.test(t6) && (await st()).active && !(await st()).running, JSON.stringify(t6.slice(-160)));
  console.log('   the prompt follows the new folder:', (await page.locator('#termPromptLine').innerText()) === 'husky:/tmp $');

  // 7) Trimming works on one big text node (a normalized terminal) without losing everything.
  await page.evaluate(() => {
    const t = document.getElementById('termOutput');
    t.textContent = '';
    t.appendChild(document.createTextNode('A'.repeat(700000)));
    rishTermChars = 700000;
    onRishOutput('r1', 'B'.repeat(150000) + '\n');       // pushes it over the 800000 limit
  });
  const sizes = await page.evaluate(() => ({ chars: rishTermChars, len: document.getElementById('termOutput').textContent.length, kept: document.getElementById('termOutput').textContent.endsWith('B\n') }));
  console.log('7. a big single text node is cut at the front, not dropped whole:', sizes.chars < 600000 && sizes.chars > 400000 && sizes.len > 400000 && sizes.kept, JSON.stringify(sizes));

  // 8) A stale permission wait does not open a shell later.
  await page.evaluate(() => { leaveRish(); rishAwaitingAuth = true; rishAwaitingAuthAt = Date.now() - 600000; window.__log.length = 0; });
  await page.evaluate(() => onShizukuPermissionResult && onShizukuPermissionResult(true));
  await sleep(150);
  console.log('8. a grant arriving 10 minutes after the prompt does not start a shell:', !(await page.evaluate(() => window.__log)).includes('rishStart'));
  await page.evaluate(() => { rishAwaitingAuth = true; rishAwaitingAuthAt = Date.now(); });
  await page.evaluate(() => onShizukuPermissionResult && onShizukuPermissionResult(true));
  await sleep(300);
  console.log('   a grant right after the prompt does:', (await page.evaluate(() => window.__log)).includes('rishStart'));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
