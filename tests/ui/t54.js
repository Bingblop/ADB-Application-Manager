// v5.8 async bridge calls: the normal terminal and logcat no longer block the page while adb / shell works.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { shell: [], logcat: [] };
    window.__logLines = ['10-03 12:00:00.000  100  200 I Tag: first'];
    window.__shellMode = 'ok';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      loadSetting() { return ''; }, saveSetting() {}, copyToClipboard() {},
      executeShellAsync(id, cmd) {
        window.__calls.shell.push([id, cmd]);
        if (window.__shellMode === 'refuse') return 'nope';
        window.__lastShellId = id;
        if (window.__shellMode === 'ok') setTimeout(() => window.onShellDone(id, 'out-of-' + cmd + '\n'), 250);
        return 'started';
      },
      getLogcatAsync(id, level, filter, lines, pkg) {
        window.__calls.logcat.push([id, level, filter, lines, pkg]);
        window.__lastLogId = id;
        if (window.__logHold) return 'started';
        setTimeout(() => window.onLogcatData(id, window.__logLines.join('\n')), 40);
        return 'started';
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const term = () => page.locator('#termOutput').innerText();
  await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await sleep(150);

  // 1) The command runs without freezing the page.
  await page.fill('#termCmd', 'echo hi'); await page.press('#termCmd', 'Enter'); await sleep(40);
  console.log('1. the command is echoed with a "running…" line while it works:', /\$ echo hi/.test(await term()) && /running…/.test(await term()));
  const alive = await page.evaluate(() => { switchView('apps'); const v = currentViewName(); (switchView('terminal'), txShowPane('console')); return v; });
  console.log('   the page is not frozen meanwhile (a tab switch works):', alive === 'apps');
  await page.fill('#termCmd', 'ls'); await page.press('#termCmd', 'Enter'); await sleep(30);
  console.log('   a second command is refused while one runs:', /still running/.test(await page.locator('#toastMsg').innerText()) && (await page.evaluate(() => window.__calls.shell.length)) === 1);
  await sleep(350);
  const t1 = await term();
  console.log('   the output replaces the running line:', /out-of-echo hi/.test(t1) && !/running…/.test(t1));
  console.log('   the result sheet opens as before:', await page.locator('#commandResultsModal.show').count() === 1);
  await page.evaluate(() => closeCommandResultsModal());

  // 2) The next command works; a refused start is reported.
  await page.fill('#termCmd', 'id'); await page.press('#termCmd', 'Enter'); await sleep(400);
  console.log('2. the next command runs once the first has finished:', /out-of-id/.test(await term()));
  await page.evaluate(() => closeCommandResultsModal());
  await page.evaluate(() => { window.__shellMode = 'refuse'; });
  await page.fill('#termCmd', 'x'); await page.press('#termCmd', 'Enter'); await sleep(100);
  console.log('   a start the app refuses shows an error line and frees the box:', /could not be started/.test(await term()) && (await page.evaluate(() => termShellRun)) === null);
  await page.evaluate(() => closeCommandResultsModal());
  await page.evaluate(() => { window.__shellMode = 'ok'; });
  // a stale answer is ignored
  await page.evaluate(() => onShellDone('s999', 'STALE'));
  console.log('   an answer for a command that is not the current one is ignored:', !/STALE/.test(await term()));

  // 3) Logcat: async fetch, one poll at a time, late answers dropped.
  await page.evaluate(() => switchView('logcat')); await sleep(300);
  console.log('3. logcat is fetched through the async call and drawn:', (await page.evaluate(() => window.__calls.logcat.length)) >= 1 && /first/.test(await page.locator('#logcatOutput').innerText()));
  await page.evaluate(() => { window.__calls.logcat.length = 0; window.__logHold = true; });
  await page.evaluate(() => { logcatFetch(true); logcatFetch(true); logcatFetch(true); });
  console.log('   while a live poll is out, further polls are not sent:', (await page.evaluate(() => window.__calls.logcat.length)) === 1);
  await page.evaluate(() => { window.__logLines = ['10-03 12:00:01.000  100  200 E Tag: second']; });
  await page.evaluate(() => onLogcatData(window.__lastLogId, window.__logLines.join('\n')));
  console.log('   its answer is drawn and the next poll may go:', /second/.test(await page.locator('#logcatOutput').innerText()) && (await page.evaluate(() => lcPending)) === null);
  await page.evaluate(() => { window.__calls.logcat.length = 0; logcatFetch(true); });
  const staleId = await page.evaluate(() => window.__lastLogId);
  await page.evaluate(() => { logcatStop(); });
  await page.evaluate(id => { window.__logLines = ['10-03 12:00:02.000  100  200 W Tag: late']; onLogcatData(id, window.__logLines.join('\n')); }, staleId);
  console.log('   Pause drops the answer of a poll that was still out:', !/late/.test(await page.locator('#logcatOutput').innerText()));
  await page.evaluate(() => { window.__logHold = false; });
  await page.evaluate(() => { window.__logLines = ['10-03 12:00:03.000  100  200 I Tag: refreshed']; logcatRefresh(); }); await sleep(150);
  console.log('   Refresh fetches and draws again:', /refreshed/.test(await page.locator('#logcatOutput').innerText()));
  const lastCall = await page.evaluate(() => window.__calls.logcat.slice(-1)[0]);
  console.log('   the level, line count and app go along:', lastCall[1] === 'I' && lastCall[3] === 500 && lastCall[4] === '', JSON.stringify(lastCall));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
