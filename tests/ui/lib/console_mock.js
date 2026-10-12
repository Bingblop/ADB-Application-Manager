// Mock bridge for the Console tab (ADB Shell and Live Logcat), driven by the script:
//
//   await page.addInitScript(consoleMock.initScript, { kv: { console_seg: '"log"' }, holdShell: true });
//
// Inside the page:
//   window.__kv                 the saved settings (loadSetting / saveSetting), as JSON text
//   window.__calls              { shell: [{ id, cmd }], logcat: [[id, level, filter, lines, pkg]], clear: n, copied: [text], rish: [cmd], toasts: [] }
//   window.__shellDone(cmd, out) answers the newest pending shell command with this output (holdShell: true keeps every command pending until then)
//   window.__logLines           the log text the log bridge answers with (one logcat -v threadtime line per row)
//   window.__rishExit           what onRishDone gets as exit for the next Rish command (a number, or undefined for "no exit code given")
exports.initScript = function (opts) {
  opts = opts || {};
  const calls = { shell: [], logcat: [], clear: 0, copied: [], rish: [], rishStop: 0 };
  window.__calls = calls;
  window.__kv = Object.assign({}, opts.kv || {});
  window.__holdShell = opts.holdShell !== false;
  window.__logLines = opts.log || '10-12 10:00:01.123  1234  1234 I ActivityManager: Start proc\n10-12 10:00:02.500  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main\n';
  window.__rishExit = 0;
  window.__shellDone = (cmd, out) => {
    const r = calls.shell.filter(x => x.cmd === cmd && !x.done).pop() || calls.shell.filter(x => !x.done).pop();
    if (!r) return false;
    r.done = true;
    window.onShellDone(r.id, out);
    return true;
  };
  window.AndroidBridge = {
    vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
    loadPackages() { return JSON.stringify([{ pkg: 'com.example.one', name: 'One', isSystem: false, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '1.0', versionCode: 1, mods: [] }, { pkg: 'com.example.two', name: 'Two', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '2.0', versionCode: 2, mods: [] }]); }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    loadSetting(k) { return window.__kv[k] || ''; }, saveSetting(k, v) { window.__kv[k] = v; },
    copyToClipboard(t) { calls.copied.push(t); },
    getWorkingMode() {
      return JSON.stringify({ activeMode: 'shizuku', configuredMode: 'shizuku', modeAvailable: true, isPrivileged: true, adbTcp: {}, adbWireless: {}, rootAvailable: false, shizuku: { installed: true, running: true, authorized: true } });
    },
    checkShizukuStatus() { return JSON.stringify({ installed: true, running: true, authorized: true }); },
    selectWorkingMode(mode) { return JSON.stringify({ ok: true, mode, message: 'Using Shizuku (UID 2000)' }); },
    executeShellAsync(id, cmd) {
      calls.shell.push({ id, cmd, done: false });
      if (!window.__holdShell) setTimeout(() => { const r = calls.shell.find(x => x.id === id); if (r && !r.done) { r.done = true; window.onShellDone(id, 'out-of-' + cmd + '\n'); } }, 30);
      return 'started';
    },
    getLogcatAsync(id, level, filter, lines, pkg) {
      calls.logcat.push([id, level, filter, lines, pkg]);
      setTimeout(() => window.onLogcatData(id, window.__logLines), 20);
      return 'started';
    },
    clearLogcat() { calls.clear++; return 'cleared'; },
    getAppDetails() { return JSON.stringify({ versionName: '1.0', versionCode: 1, permissions: [], appopsRaw: '', activities: [], services: [], receivers: [], providers: [] }); },
    logRecStatus() { return JSON.stringify({ recording: false }); },
    rishStart() { setTimeout(() => window.onRishStarted({ ok: true, reused: false, uid: 2000, host: 'husky', cwd: '/', prompt: 'husky:/ $' }), 20); return 'starting'; },
    rishRun(cmd, id) {
      calls.rish.push(cmd);
      setTimeout(() => {
        if (cmd === 'forever') { window.__rishStopId = id; return; }
        window.onRishOutput(id, 'ran:' + cmd + '\n');
        const info = { cwd: '/', prompt: 'husky:/ $' };
        if (cmd === 'false') info.exit = 1; else if (window.__rishExit !== undefined) info.exit = window.__rishExit;
        window.onRishDone(id, info);
      }, 20);
      return 'ok';
    },
    rishStop() { calls.rishStop++; if (window.__rishStopId) { const id = window.__rishStopId; window.__rishStopId = null; setTimeout(() => window.onRishDone(id, { exit: 143, stopped: true, cwd: '/', prompt: 'husky:/ $' }), 20); } },
    rishClose() {},
  };
};
