// execShellOk (Stop Service in the App menu, the renderer switch in the Task Manager) judges a command by the exit status it echoes after it. When no
// status comes back the command never ran: the working mode is gone while the page still thinks it is active (Shizuku stopped, Wireless Debugging
// dropped, su refused) and executeShell answers with an error text instead. That used to count as a success ("Service stopped"); now it is a failure.
// The mock below answers like MainActivity.executeShell: either the Java error text, or the command's output followed by the marker and its status.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    const st = { cmds: [], answer: null, rc: 0 }; window.__st = st;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.example.app', name: 'Example', isSystem: false, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: { authorized: true }, configuredMode: 'auto', activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      executeShell(cmd) {
        st.cmds.push(cmd);
        if (st.answer !== null) return st.answer;                                   // the shell was not reached: an error text, no marker
        const m = /echo "([^"]+):\$\?"$/.exec(cmd);                                   // a shell that ran it: output, then the marker with the status
        return 'some output\n' + (m ? m[1] + ':' + st.rc : '') + '\n';
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const toast = async () => (await page.locator('#toastMsg').innerText()).trim();
  check('the page believes a privileged mode is active', await ev(() => isPrivilegedActive));

  // the answers MainActivity.executeShell gives when it never ran the command
  for (const ans of ['Error: Shizuku is not authorized for this app', "error: device '192.168.1.5:37000' not found",
    'Error: root access is not granted to this app (su refused). Allow it in the root manager and check Working Modes again.', 'Error: Wireless Debugging is not configured. Connect it in Working Modes.']) {
    const r = await ev(a => { __st.answer = a; return execShellOk('am stopservice x/y'); }, ans);
    check('no exit status (' + ans.slice(0, 40) + ') is a failure, and the error is kept as the output', r.ok === false && r.output === ans, JSON.stringify(r));
  }
  const empty = await ev(() => { __st.answer = ''; return execShellOk('true'); });
  check('an empty answer is a failure with a reason', empty.ok === false && empty.output === 'No answer from the device', JSON.stringify(empty));

  // a shell that ran the command: its status decides
  const ok0 = await ev(() => { __st.answer = null; __st.rc = 0; return execShellOk('true'); });
  check('status 0 is a success, the marker is cut from the output', ok0.ok === true && ok0.output === 'some output', JSON.stringify(ok0));
  const ok1 = await ev(() => { __st.answer = null; __st.rc = 1; return execShellOk('false'); });
  check('status 1 is a failure', ok1.ok === false && ok1.output === 'some output', JSON.stringify(ok1));

  // the two places that use it
  await ev(() => { __st.answer = 'Error: Shizuku is not authorized for this app'; inspectedPkg = 'com.example.app'; stopComponentService('com.example.app.SyncService'); });
  check('Stop Service with the shell gone says it could not stop the service', (await toast()) === 'Could not stop the service', await toast());
  check('and the command it tried was the stop', /^am stopservice 'com\.example\.app\/com\.example\.app\.SyncService'; echo "__RC/.test(await ev(() => __st.cmds[__st.cmds.length - 1])));
  await ev(() => { __st.answer = null; __st.rc = 0; stopComponentService('com.example.app.SyncService'); });
  check('Stop Service that ran says the service stopped', (await toast()) === 'Service stopped', await toast());

  const setRenderer = () => ev(() => { const s = document.getElementById('tmRendererSelect'); s.innerHTML = '<option value="skiagl">OpenGL</option>'; s.value = 'skiagl'; tmSetRenderer(); });
  await ev(() => { __st.answer = 'Error: Shizuku is not authorized for this app'; }); await setRenderer();
  check('the renderer switch with the shell gone says it could not switch', (await toast()) === 'Could not switch to OpenGL', await toast());
  await ev(() => { __st.answer = null; __st.rc = 0; }); await setRenderer();
  check('the renderer switch that ran says it was set', (await toast()) === 'OpenGL set - System UI restarted to apply it', await toast());

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
