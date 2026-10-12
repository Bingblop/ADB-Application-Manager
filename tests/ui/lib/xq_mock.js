// The shell side of the mock bridge for Extra Settings > Power & reboot, Doze & power governor, Zram & memory (t251): AndroidBridge.executeShellAsync(id, cmd)
// answers through window.onShellDone(id, out) the way the app does, and getWorkingMode follows window.__mode ({ priv, mode }).
// Add it after sdb_mock (settingsOp for low_power): page.addInitScript(xqMock.initScript). The test drives the answers:
//   window.__xq.calls            every command started, as the page sent it (with its exit-status marker): { id, cmd, bare } (bare: without the marker)
//   window.__xq.reply = fn(bare) what a command answers: a string (exit 0), { out, rc }, or { raw } (sent as it is, no marker: a shell that died, a bridge error)
//   window.__xq.hold = true      keep the answers until __xqRelease() is called (a command that is still running)
//   window.__xq.delay            milliseconds before an answer (default 15)
//   window.__xq.noStart          executeShellAsync answers 'error: ...' instead of starting
//   window.__xq.fixtures         the fixture texts the test loaded (deviceidle reports, mm_stat, meminfo, cmd power help)
module.exports.initScript = function (fixtures) {
  window.__xq = { calls: [], reply: null, hold: false, held: [], delay: 15, noStart: false, fixtures: fixtures || {} };
  const F = window.__xq.fixtures;
  const SEP = '__XQ_SEP__';
  // the phone the default answers describe: Doze active, zram 3.0x, Android 11 power commands, low_power from the settings mock
  window.__phone = { deep: 'ACTIVE', light: 'ACTIVE', force: 'false', dump: 'active', mm: F.mm_stat || '', disksize: F.disksize || '', meminfo: F.meminfo || '', help: F.help11 || '', power: 'POWER MANAGER (dumpsys power)\n  mWakefulness=Awake\n', sysuiPid: 2210, sysuiRestarts: true, zramMissing: false };
  const grepDump = () => (F['dump_' + window.__phone.dump] || '').split('\n').filter(l => /m(UseMotionSensor|MotionListener\.active|MotionSensor|State|LightState|ForceIdle)=/.test(l)).slice(0, 12).join('\n');
  window.__xqDefault = function (bare) {
    const P = window.__phone;
    if (bare.startsWith('dumpsys deviceidle get deep;')) return [P.deep, P.light, P.force, grepDump()].join('\n' + SEP + '\n') + '\n';
    if (bare.startsWith('cat /sys/block/zram0/mm_stat;')) {
      if (P.zramMissing) return { out: 'cat: /sys/block/zram0/mm_stat: No such file or directory\n' + SEP + '\ncat: /sys/block/zram0/disksize: No such file or directory\n' + SEP + '\n' + P.meminfo, rc: 0 };
      return [P.mm, P.disksize, P.meminfo].join(SEP + '\n');
    }
    if (bare === 'cmd power help') return P.help;
    if (bare.startsWith('dumpsys power | grep')) return { out: P.power.split('\n').filter(l => /fixed.?performance/i.test(l)).slice(0, 3).join('\n'), rc: 0 };
    if (bare === 'pidof com.android.systemui') return String(P.sysuiPid) + '\n';
    if (bare === 'am crash com.android.systemui') { if (P.sysuiRestarts) P.sysuiPid += 1; return ''; }
    if (bare === 'dumpsys deviceidle force-idle deep') { P.deep = 'IDLE'; P.light = 'OVERRIDE'; P.force = 'true'; P.dump = 'forced'; return 'Now forced in to deep idle mode\n'; }
    if (bare === 'dumpsys deviceidle step deep') { const o = ['ACTIVE', 'INACTIVE', 'IDLE_PENDING', 'SENSING', 'LOCATING', 'IDLE', 'IDLE_MAINTENANCE']; const i = o.indexOf(P.deep); P.deep = i < 0 || i === o.length - 1 ? 'IDLE' : o[i + 1]; return 'Stepped to deep: ' + P.deep + '\n'; }
    if (bare === 'dumpsys deviceidle unforce') { P.deep = 'ACTIVE'; P.light = 'ACTIVE'; P.force = 'false'; P.dump = 'active'; return 'Light state: ACTIVE, Deep state: ACTIVE\n'; }
    if (bare === 'dumpsys deviceidle motion') { if (P.force !== 'true') { P.deep = 'ACTIVE'; P.light = 'ACTIVE'; P.dump = 'active'; } return ''; }
    if (/^cmd power set-fixed-performance-mode-enabled (true|false)$/.test(bare)) { if (!/set-fixed-performance-mode-enabled/.test(P.help)) return { out: 'Error: Unknown command: set-fixed-performance-mode-enabled\n', rc: 255 }; return ''; }
    if (/^cmd power set-adaptive-power-saver-enabled (true|false)$/.test(bare)) return '';
    if (bare.startsWith('reboot') || bare.startsWith('setprop ctl.restart')) return '';
    if (bare.startsWith('sync; echo')) return '';
    return { out: '/system/bin/sh: ' + bare.split(' ')[0] + ': inapplicable\n', rc: 127 };
  };
  const answer = (id, cmd) => {
    const m = /; echo "(__XQRC_[^"]+__):\$\?"$/.exec(cmd);
    const bare = m ? cmd.slice(0, m.index) : cmd;
    window.__xq.calls.push({ id, cmd, bare });
    let r = (window.__xq.reply && window.__xq.reply(bare));
    if (r === undefined || r === null) r = window.__xqDefault(bare);
    let text;
    if (typeof r === 'string') text = r + (m ? (r.endsWith('\n') || r === '' ? '' : '\n') + m[1] + ':0\n' : '');
    else if (r.raw !== undefined) text = r.raw;
    else text = (r.out || '') + (m ? ((r.out || '').endsWith('\n') || !r.out ? '' : '\n') + m[1] + ':' + (r.rc === undefined ? 0 : r.rc) + '\n' : '');
    const send = () => window.onShellDone && window.onShellDone(id, text);
    if (window.__xq.hold) { window.__xq.held.push(send); return; }
    setTimeout(send, window.__xq.delay);
  };
  window.__xqRelease = () => { const h = window.__xq.held.slice(); window.__xq.held.length = 0; window.__xq.hold = false; h.forEach(fn => fn()); };
  const B = window.AndroidBridge || (window.AndroidBridge = {});
  B.executeShellAsync = function (id, cmd) { if (window.__xq.noStart) return 'error: the app is closing'; answer(id, cmd); return 'started'; };
  B.executeShell = function (cmd) { return ''; };
  window.__mode = Object.assign({ priv: true, mode: 'adb_tcp' }, window.__mode || {});
  B.getWorkingMode = function () {
    const p = window.__mode.priv, mode = p ? (window.__mode.mode || 'adb_tcp') : 'unprivileged';
    return JSON.stringify({ activeMode: mode, modeAvailable: p, isPrivileged: p, configuredMode: p ? mode : 'unprivileged', adbTcp: { connected: mode === 'adb_tcp', port: 5555 }, adbWireless: { connected: mode === 'adb_wireless', port: 0 }, shizuku: { installed: mode === 'shizuku', running: mode === 'shizuku', authorized: mode === 'shizuku' }, rootAvailable: mode === 'root', rootGranted: mode === 'root' });
  };
};
