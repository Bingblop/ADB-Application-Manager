// The shell side of the mock bridge for Extra Settings > Power & reboot, Doze & power governor, Zram & memory (t251, t613): AndroidBridge.executeShellAsync(id, cmd)
// answers through window.onShellDone(id, out) the way the app does, and getWorkingMode follows window.__mode ({ priv, mode }).
// Add it after sdb_mock (settingsOp for low_power): page.addInitScript(xqMock.initScript). The test drives the answers:
//   window.__xq.calls            every command started, as the page sent it (with its exit-status marker): { id, cmd, bare } (bare: without the marker)
//   window.__xq.reply = fn(bare) what a command answers: a string (exit 0), { out, rc }, or { raw } (sent as it is, no marker: a shell that died, a bridge error)
//   window.__xq.hold = true      keep the answers until __xqRelease() is called (a command that is still running)
//   window.__xq.delay            milliseconds before an answer (default 15)
//   window.__xq.noStart          executeShellAsync answers 'error: ...' instead of starting
//   window.__xq.fixtures         the fixture texts the test loaded (deviceidle reports, mm_stat, meminfo, cmd power help, battery, cpufreq, pressure)
//   window.__phone               the phone the default answers describe; the writes of the sections change it the way the real commands would:
//     deep / light / force / dump   the Doze tokens and which deviceidle report (dump_<name> fixture) the grep reads
//     sdk, dc, consts, settings     the Android version; the device_idle overrides (Android 12+); the old constants string; the Settings block as { key: ms }
//                                   (null = the fixture's own block; __xqDozeDefaults() sets Android's defaults so a write shows in the read-back)
//     power, battery, cpu, adaptive the dumpsys power lines, the dumpsys battery lines, the cpufreq policy lines (one per cluster, as the read prints them), the adaptive setting
//     mm, disksize, meminfo, swaps, swappiness, psi, lmk, lmkRo, zramMissing   the memory read
module.exports.initScript = function (fixtures) {
  window.__xq = { calls: [], reply: null, hold: false, held: [], delay: 15, noStart: false, fixtures: fixtures || {} };
  const F = window.__xq.fixtures;
  const SEP = '__XQ_SEP__';
  // Android's own Doze constants (AOSP), in milliseconds
  const DOZE_DEFAULTS = { light_after_inactive_to: 240000, light_idle_to: 300000, light_max_idle_to: 900000, inactive_to: 1800000, idle_after_inactive_to: 1800000, motion_inactive_to: 600000, idle_pending_to: 300000, max_idle_pending_to: 600000, idle_to: 3600000, max_idle_to: 21600000 };
  // the phone the default answers describe: Doze active, zram 3.0x, Android 14, no battery saver lines, on battery, no readable CPU clusters, low_power from the settings mock
  window.__phone = { deep: 'ACTIVE', light: 'ACTIVE', force: 'false', dump: 'active', sdk: '34', dc: {}, consts: 'null', settings: null,
    mm: F.mm_stat || '', disksize: F.disksize || '', meminfo: F.meminfo || '', help: F.help11 || '', power: 'POWER MANAGER (dumpsys power)\n  mWakefulness=Awake\n',
    battery: F.battery_unplugged || 'AC powered: false\nUSB powered: false\nWireless powered: false\nDock powered: false\nlevel: 80\nstatus: 3\n', cpu: '', cpuWritable: true, adaptive: '1',
    swaps: 'Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n/dev/block/zram0                        partition\t6291452\t\t1550156\t\t-2\n', swappiness: '100\n',
    psi: F.pressure || 'some avg10=0.00 avg60=0.00 avg300=0.00 total=12345\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n', lmk: {}, lmkRo: {}, lmkReinitOk: true,
    sysuiPid: 2210, sysuiRestarts: true, zramMissing: false };
  // a duration as Android's TimeUtils prints it: +4m0s0ms, +1h0m0s0ms
  const fmtMs = ms => { let s = '+', rest = ms, on = false; const u = [[86400000, 'd'], [3600000, 'h'], [60000, 'm'], [1000, 's']]; for (const [n, k] of u) { const v = Math.floor(rest / n); rest -= v * n; if (v || on) { s += v + k; on = true; } } return s + rest + 'ms'; };
  const STATE_RE = /m(UseMotionSensor|MotionListener\.active|MotionSensor|State|LightState|ForceIdle|ScreenOn|Charging|NetworkConnected|LightEnabled|DeepEnabled|QuickDozeActivated|NextAlarmTime|NextLightAlarmTime|NextIdleDelay|NextIdlePendingDelay|InactiveTimeout)=/;
  // what `dumpsys deviceidle | grep -E ...` prints: the Settings block (the phone's own, or the fixture's), then the state lines of the report
  const grepDump = () => {
    const P = window.__phone, all = (F['dump_' + P.dump] || '').split('\n');
    let settings;
    if (P.settings) settings = ['  Settings:'].concat(Object.keys(P.settings).map(k => '    ' + k + '=' + fmtMs(P.settings[k])));
    else { settings = []; let inBlock = false; for (const l of all) { if (l.trim() === 'Settings:') { inBlock = true; settings.push(l); continue; } if (!inBlock) continue; if (/^ +[a-z][a-z0-9_]*=\S+\s*$/.test(l)) settings.push(l); else break; } }
    return settings.concat(all.filter(l => STATE_RE.test(l))).slice(0, 90).join('\n');
  };
  const dcList = () => Object.keys(window.__phone.dc).map(k => k + '=' + window.__phone.dc[k]).join('\n');
  const nl = s => { s = String(s == null ? '' : s); return s.endsWith('\n') || s === '' ? s : s + '\n'; };
  window.__xqDozeDefaults = () => { window.__phone.settings = Object.assign({}, DOZE_DEFAULTS); };
  window.__xqDefault = function (bare) {
    const P = window.__phone;
    let m;
    // ---- the reads ----
    if (bare.startsWith('dumpsys deviceidle get deep;')) return [P.deep, P.light, P.force, grepDump(), P.sdk, dcList(), P.consts].join('\n' + SEP + '\n') + '\n';
    if (bare.startsWith('cat /sys/block/zram0/mm_stat;')) {
      const lmk = nl(P.lmk.swap_free_low_percentage || '') + nl(P.lmk.thrashing_limit || ''), ro = nl(P.lmkRo.swap_free_low_percentage || '') + nl(P.lmkRo.thrashing_limit || '');
      if (P.zramMissing) return { out: 'cat: /sys/block/zram0/mm_stat: No such file or directory\n' + SEP + '\ncat: /sys/block/zram0/disksize: No such file or directory\n' + SEP + '\n' + [P.meminfo, P.swaps, P.swappiness, P.psi, lmk, ro].map(nl).join(SEP + '\n'), rc: 0 };
      return [P.mm, P.disksize, P.meminfo, P.swaps, P.swappiness, P.psi, lmk, ro].map(nl).join(SEP + '\n');
    }
    if (bare === 'cmd power help') return P.help;
    if (bare.startsWith('dumpsys power | grep')) return { out: [P.power.split('\n').filter(l => /^ *(Enabled|mIsPowered|mSettingBatterySaverEnabled|mSettingAutomaticBatterySaver)=|fixed.?performance/i.test(l)).slice(0, 12).join('\n'), P.battery.replace(/\n$/, ''), P.cpu.replace(/\n$/, ''), P.adaptive].join('\n' + SEP + '\n') + '\n', rc: 0 };
    if (bare === 'pidof com.android.systemui') return String(P.sysuiPid) + '\n';
    // ---- the writes: the phone changes the way the real command would ----
    if (bare === 'am crash com.android.systemui') { if (P.sysuiRestarts) P.sysuiPid += 1; return ''; }
    if (bare === 'dumpsys deviceidle force-idle deep') { P.deep = 'IDLE'; P.light = 'OVERRIDE'; P.force = 'true'; P.dump = 'forced'; return 'Now forced in to deep idle mode\n'; }
    if (bare === 'dumpsys deviceidle step deep') { const o = ['ACTIVE', 'INACTIVE', 'IDLE_PENDING', 'SENSING', 'LOCATING', 'IDLE', 'IDLE_MAINTENANCE']; const i = o.indexOf(P.deep); P.deep = i < 0 || i === o.length - 1 ? 'IDLE' : o[i + 1]; return 'Stepped to deep: ' + P.deep + '\n'; }
    if (bare === 'dumpsys deviceidle unforce') { P.deep = 'ACTIVE'; P.light = 'ACTIVE'; P.force = 'false'; P.dump = 'active'; return 'Light state: ACTIVE, Deep state: ACTIVE\n'; }
    if (bare === 'dumpsys deviceidle motion') { if (P.force !== 'true') { P.deep = 'ACTIVE'; P.light = 'ACTIVE'; P.dump = 'active'; } return ''; }
    if ((m = /^device_config put device_idle ([a-z][a-z0-9_]*) (\d+)$/.exec(bare))) { P.dc[m[1]] = m[2]; if (!P.settings) window.__xqDozeDefaults(); P.settings[m[1]] = +m[2]; return ''; }
    if ((m = /^device_config delete device_idle ([a-z][a-z0-9_]*)$/.exec(bare))) { delete P.dc[m[1]]; if (!P.settings) window.__xqDozeDefaults(); if (DOZE_DEFAULTS[m[1]] != null) P.settings[m[1]] = DOZE_DEFAULTS[m[1]]; else delete P.settings[m[1]]; return ''; }
    if ((m = /^settings put global device_idle_constants (\S+)$/.exec(bare))) { P.consts = m[1]; window.__xqDozeDefaults(); m[1].split(',').forEach(kv => { const p = kv.split('='); if (p.length === 2 && /^\d+$/.test(p[1])) P.settings[p[0]] = +p[1]; }); return ''; }
    if (bare === 'settings delete global device_idle_constants') { P.consts = 'null'; window.__xqDozeDefaults(); return ''; }
    if ((m = /^for p in \/sys\/devices\/system\/cpu\/cpufreq\/policy\*; do echo ([a-z][a-z0-9_-]*) > \$p\/scaling_governor; done$/.exec(bare))) {
      if (!P.cpuWritable) return { out: '/system/bin/sh: can\'t create /sys/devices/system/cpu/cpufreq/policy0/scaling_governor: Permission denied\n', rc: 1 };
      const gov = m[1]; let bad = false;
      P.cpu = P.cpu.split('\n').map(l => { if (!l.startsWith('policy')) return l; const f = l.split('|'); if ((f[6] || '').split(/\s+/).includes(gov)) f[1] = gov; else bad = true; return f.join('|'); }).join('\n');
      return bad ? { out: '/system/bin/sh: write error: Invalid argument\n', rc: 1 } : '';
    }
    if (/^cmd power set-fixed-performance-mode-enabled (true|false)$/.test(bare)) { if (!/set-fixed-performance-mode-enabled/.test(P.help)) return { out: 'Error: Unknown command: set-fixed-performance-mode-enabled\n', rc: 255 }; return ''; }
    if (/^cmd power set-adaptive-power-saver-enabled (true|false)$/.test(bare)) return '';
    if (bare.startsWith('reboot') || bare.startsWith('setprop ctl.restart')) return '';
    if (bare.startsWith('sync; echo')) {          // dropping the caches: 600 MB of Cached become free, MemAvailable rises by half of it (the kernel counted part of the cache as available already)
      const shift = (key, d) => { P.meminfo = P.meminfo.replace(new RegExp('^(' + key + ':\\s+)(\\d+)', 'm'), (s, k, v) => k + Math.max(0, +v + d)); };
      shift('Cached', -614400); shift('MemFree', 614400); shift('MemAvailable', 307200);
      return '';
    }
    if ((m = /^swapoff \/dev\/block\/zram0; echo 1 > \/sys\/block\/zram0\/reset; echo (\d+) > \/sys\/block\/zram0\/disksize; mkswap \/dev\/block\/zram0; swapon \/dev\/block\/zram0$/.exec(bare))) { P.disksize = m[1] + '\n'; P.mm = '0 0 0 0 0 0 0 0 0\n'; return 'Setting up swapspace version 1, size = ' + Math.round(+m[1] / 1024 - 4) + 'KiB\n'; }
    if ((m = /^echo (\d+) > \/proc\/sys\/vm\/swappiness$/.exec(bare))) { P.swappiness = m[1] + '\n'; return ''; }
    if ((m = /^device_config put lmkd_native (swap_free_low_percentage|thrashing_limit) (\d+)$/.exec(bare))) { P.lmk[m[1]] = m[2]; return ''; }
    if ((m = /^device_config delete lmkd_native (swap_free_low_percentage|thrashing_limit)$/.exec(bare))) { delete P.lmk[m[1]]; return ''; }
    if (bare === 'setprop lmkd.reinit 1') return P.lmkReinitOk ? '' : { out: 'setprop: failed to set property \'lmkd.reinit\' to \'1\'\n', rc: 1 };
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
