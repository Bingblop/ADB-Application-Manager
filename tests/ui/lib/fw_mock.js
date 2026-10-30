// Mock bridge for the Android Firewall tab scripts (t614): a key-value store behind loadSetting / saveSetting (kept across page.reload(), as lib/bnav_mock.js
// keeps it), a working mode chosen by opts.mode (the shapes of lib/fontmaker_mock.js: isRootActive reads sysPrivActive), a package list, saved lists, and the
// four firewall methods of the design (fwApps, fwRun, fwNetState, fwBoot) answered the way MainActivity answers them, against a small pretend phone that
// remembers what was applied: a status read says "on" only after an apply, "off" after a disable, the rule count grows with the uids applied.
//   await page.addInitScript(mock.initScript, { mode: 'root', kv: {}, ... });
// opts.mode: 'root' (Root with su) | 'system' (Shizuku as UID 1000) | 'uid0' (Shizuku as UID 0) | 'shell' (Shizuku as UID 2000) | 'none' (no working mode)
// opts.kv: settings to start with (name -> JSON text); opts.lists: the saved lists (default two); opts.noBin: a phone without iptables (apply answers noBin);
// opts.failApply: { error } every apply ends with; opts.roaming: the phone roams at the start; opts.log: dmesg groups the log read answers (default two);
// opts.hold: jobs wait for window.__fw.step() (step(n), stepAll()) instead of answering on the next tick; opts.bootError: fwBoot answers { ok:false, error }
// window.__fw: { calls (every fwRun: { id, kind, cfg }), boot (the last fwBoot: { on, cfg, delay }), applied (the cfg in force or null), setMode(m), step(),
//   stepAll(), pending(), roaming (set it, then call window.onFwNet()), pick(text, name) (answers the open file chooser), saved (what saveTextToDownloads got) }
exports.initScript = function (opts) {
  opts = opts || {};
  let stored = {}, phone = {};          // the settings store and the pretend phone's netfilter state, both kept across a reload (window.name survives it)
  try { const w = window.name && window.name.indexOf('{"__fw":1') === 0 ? JSON.parse(window.name) : null; stored = w ? w.kv : Object.assign({}, opts.kv || {}); phone = w && w.phone || {}; } catch (e) { stored = Object.assign({}, opts.kv || {}); }
  const save = () => { try { window.name = JSON.stringify({ __fw: 1, kv: stored, phone: { applied: st.applied, boot: st.boot } }); } catch (e) {} };
  save();
  window.__kv = stored;
  const J = JSON.stringify;
  const WM = {
    root: { activeMode: 'root', configuredMode: 'root', isPrivileged: true, modeAvailable: true, rootAvailable: true, rootGranted: true, sysPrivActive: true, rootUidActive: true, rootWorkingModeActive: true, adbTcp: {}, adbWireless: {}, shizuku: {} },
    system: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: true, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 1000 } },
    uid0: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: true, rootUidActive: true, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 0 } },
    shell: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: false, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 2000 } },
    none: { activeMode: 'readonly', configuredMode: 'auto', isPrivileged: false, modeAvailable: false, rootAvailable: false, sysPrivActive: false, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: {} },
  };
  const st = { mode: opts.mode || 'root', applied: phone.applied || null, roaming: !!opts.roaming, boot: phone.boot || null, queue: [], pick: null, saved: [] };
  const gate = () => st.mode === 'root' ? 'root' : st.mode === 'system' ? 'system' : st.mode === 'uid0' ? 'uid0' : null;
  const PKGS = [
    { pkg: 'com.example.alpha', uid: 10101, name: 'Alpha Browser', isSystem: false, net: true },
    { pkg: 'com.example.beta', uid: 10102, name: 'Beta Chat', isSystem: false, net: true },
    { pkg: 'com.example.gamma', uid: 10103, name: 'Gamma Notes', isSystem: false, net: false },
    { pkg: 'com.vendor.shared.a', uid: 10104, name: 'Shared One', isSystem: true, net: true },
    { pkg: 'com.vendor.shared.b', uid: 10104, name: 'Shared Two', isSystem: true, net: true },
    { pkg: 'com.android.phone', uid: 1001, name: 'Phone', isSystem: true, net: true },
    { pkg: 'com.bloatware.bingblop', uid: 10200, name: 'ADB/Root Toolbox', isSystem: false, net: true },
  ];
  const SPECIALS = [
    { key: 'kernel', uid: -11, name: 'Linux kernel', why: 'Packets no app owns: ping, parts of IPv6, tethering.' },
    { key: 'root', uid: 0, name: 'Root (UID 0)', why: 'Processes running as root.' },
    { key: 'system', uid: 1000, name: 'Android system (UID 1000)', why: 'The system server: time sync, captive portal checks.' },
    { key: 'dns', uid: 1051, name: 'DNS resolver (UID 1051)', why: 'Answers every app\'s name lookups.' },
    { key: 'shell', uid: 2000, name: 'ADB shell (UID 2000)', why: 'Commands run over adb or Shizuku as the shell user.' },
  ];
  const lists = opts.lists || [
    { id: 'l1', name: 'Social', description: 'Chat and feeds', packages: ['com.example.alpha', 'com.example.beta'], updatedAt: Date.now() - 864e5 },
    { id: 'l2', name: 'Offline tools', description: '', packages: ['com.example.gamma'], updatedAt: Date.now() - 2 * 864e5 },
  ];
  const status = () => {
    const a = st.applied;
    const n = a ? Object.keys(a.uids || {}).length : 0;
    const chains = { atbx: a ? 20 : 0, 'atbx-wifi': a ? n + (a.mode === 'allow' ? 1 : 0) : 0, 'atbx-lan': 0, 'atbx-mobile': a ? n + (a.mode === 'allow' ? 1 : 0) : 0, 'atbx-roam': 0, 'atbx-vpn': 0, 'atbx-reject': a ? (a.log ? 2 : 1) : 0 };
    const rules4 = Object.values(chains).reduce((x, y) => x + y, 0);
    return { bin4: !opts.noBin, bin6: !opts.noBin, jump4: !!a, jump6: !!(a && a.ipv6), inbound: !!(a && a.inbound), tether: !!(a && a.tether), tor: !!(a && a.tor), applied: false, cleared: false, noBin: !!opts.noBin,
      rules4, rules6: a && a.ipv6 ? 12 : 0, uid: st.mode === 'system' ? 1000 : 0, on: !!a && rules4 > 0, chains };
  };
  const LOG = opts.log || [
    { uid: 10102, count: 7, dst: '157.240.1.35', dport: 443, proto: 'TCP', iface: 'wlan0', when: '812.100' },
    { uid: -11, count: 2, dst: '224.0.0.251', dport: -1, proto: 'ICMP', iface: 'wlan0', when: '640.000' },
  ];
  const answer = (id, res) => { try { window.onFwDone(id, J(res)); } catch (e) {} };
  const run = (id, kind, arg) => {
    const base = { ok: true, backend: gate(), kind, output: '' };
    if (kind === 'apply') {
      let cfg = null;
      try { cfg = JSON.parse(arg); } catch (e) { answer(id, Object.assign(base, { ok: false, error: 'not JSON' })); return; }
      if (opts.noBin) { answer(id, Object.assign(base, { ok: false, error: 'iptables is not on this phone', status: status() })); return; }
      if (opts.failApply) { answer(id, Object.assign(base, { ok: false, error: opts.failApply.error || 'iptables: Permission denied', status: Object.assign(status(), { applied: true }) })); return; }
      st.applied = cfg; save();
      const s = status(); s.applied = true;
      answer(id, Object.assign(base, { status: s }));
    } else if (kind === 'disable') {
      st.applied = null; save();
      const s = status(); s.cleared = true;
      answer(id, Object.assign(base, { status: s }));
    } else if (kind === 'status') {
      answer(id, Object.assign(base, { status: status() }));
    } else if (kind === 'list') {
      answer(id, Object.assign(base, { output: st.applied ? '-N atbx\n-A atbx -o lo -j RETURN\n-A atbx -o wlan+ -j atbx-wifi\n# IPv6\n' : '' }));
    } else if (kind === 'log' || kind === 'logclear') {
      answer(id, Object.assign(base, { log: LOG, lines: LOG.reduce((x, g) => x + g.count, 0) }));
    } else answer(id, Object.assign(base, { ok: false, error: 'unknown kind ' + kind }));
  };
  window.__fw = {
    calls: [], boot: null, saved: st.saved,
    get applied() { return st.applied; },
    get roaming() { return st.roaming; }, set roaming(v) { st.roaming = !!v; },
    setMode(m) { st.mode = m; },
    pending() { return st.queue.length; },
    step(n) { n = n || 1; while (n-- > 0 && st.queue.length) { const q = st.queue.shift(); run(q.id, q.kind, q.arg); } },
    stepAll() { while (st.queue.length) { const q = st.queue.shift(); run(q.id, q.kind, q.arg); } },
    pick(text, name) { const tag = st.pick; st.pick = null; if (tag && window.onTextFilePicked) window.onTextFilePicked({ tag, text, name: name || 'firewall.json' }); },
  };
  window.__calls = [];
  window.AndroidBridge = {
    vibrate() { window.__calls.push('vibrate'); }, loadPreferences() { return J({ version: 2, preset: 'material3', appearance: opts.dark === false ? 'light' : 'dark', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
    loadCustomLists() { return J(lists); }, saveCustomLists(json) { try { const l = JSON.parse(json); lists.length = 0; l.forEach(x => lists.push(x)); } catch (e) {} },
    getSystemInfo() { return J({ manufacturer: 'samsung', model: 'SM-S948U', androidVersion: '17' }); }, isSystemDarkMode() { return opts.dark !== false; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    loadSetting(k) { return Object.prototype.hasOwnProperty.call(stored, k) ? stored[k] : ''; },
    saveSetting(k, v) { stored[k] = v; save(); return true; },
    saveStore() {}, loadStore() { return ''; },
    loadPackages() { return J(PKGS.map(p => ({ pkg: p.pkg, name: p.name, isSystem: p.isSystem, isRunning: false, version: '1.0' }))); },
    getWorkingMode() { return J(WM[st.mode] || WM.none); },
    getUpdateState() { return J({ running: false, checkedAt: 0, updates: [], checked: 0, errors: 0, selfStatus: 'ok' }); },
    checkForUpdates() {}, endUpdateAll() {}, openUrl() {}, copyToClipboard(t) { window.__calls.push('copy:' + t.length); },
    // ---- the firewall ----
    fwApps() { return J({ ok: true, apps: PKGS.map(p => Object.assign({ enabled: true }, p)), selfUid: 10200, selfPkg: 'com.bloatware.bingblop', specials: SPECIALS }); },
    fwRun(id, kind, arg) {
      let cfg = null; try { cfg = kind === 'apply' ? JSON.parse(arg) : null; } catch (e) {}
      window.__fw.calls.push({ id, kind, cfg });
      if (!gate()) return 'error: Needs Super User, or Shizuku as the system user or root';
      if (opts.hold) st.queue.push({ id, kind, arg }); else setTimeout(() => run(id, kind, arg), 0);
      return 'started';
    },
    fwNetState() { return J({ roaming: st.roaming, transport: st.roaming ? 'cellular' : 'wifi', vpn: false, gate: gate(), boot: !!(st.boot && st.boot.on) }); },
    fwBoot(on, cfgJson, delay) {
      if (opts.bootError) return J({ ok: false, error: opts.bootError });
      st.boot = { on: !!on, cfg: cfgJson, delay: +delay || 0 };
      window.__fw.boot = st.boot; save();
      return J({ ok: true, boot: !!on, delay: +delay || 0 });
    },
    saveTextToDownloads(name, text) { st.saved.push({ name, text }); return 'Download/' + name; },
    pickTextFile(tag) { st.pick = tag; return 'opened'; },
    shareText() {}, shareFile() { return 'ok'; },
    executeShellAsync() { return 'started'; },          // the confirm sheet (xqOpen) checks that the bridge can run commands; the firewall's own sheet runs through fwRun
  };
};
