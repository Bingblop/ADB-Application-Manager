// Mock bridge for the bottom navigation scripts (t260-t263): a real key-value store behind loadSetting / saveSetting (so a saved choice survives page.reload()),
// an optional list of updates, and the calls the page makes that matter here (the install question, installs).
// initScript(opts): opts.kv = settings to start with (name -> JSON text), opts.updates = [...], opts.privileged (default true), opts.dark (default true).
exports.initScript = function (opts) {
  opts = opts || {};
  // the store lives in window.name, which a reload of the same tab keeps (sessionStorage did not always survive it)
  let stored = {};
  try { stored = window.name && window.name.indexOf('{"__bnav":1') === 0 ? JSON.parse(window.name).kv : Object.assign({}, opts.kv || {}); } catch (e) { stored = Object.assign({}, opts.kv || {}); }
  const save = () => { try { window.name = JSON.stringify({ __bnav: 1, kv: stored }); } catch (e) {} };
  save();
  window.__kv = stored;
  window.__calls = [];
  const now = Date.now();
  const updates = opts.updates || [];
  const priv = opts.privileged !== false;
  window.AndroidBridge = {
    vibrate() { window.__calls.push('vibrate'); }, loadPreferences() { return JSON.stringify({ version: 2, preset: opts.preset || 'material3', appearance: opts.dark === false ? 'light' : 'dark', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
    loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', model: 'SM-S928B', androidVersion: '14' }); }, isSystemDarkMode() { return opts.dark !== false; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    loadSetting(k) { return Object.prototype.hasOwnProperty.call(stored, k) ? stored[k] : ''; },
    saveSetting(k, v) { stored[k] = v; save(); return true; },
    saveStore() {}, loadStore() { return ''; },
    loadPackages() { return JSON.stringify([
      { pkg: 'com.example.alpha', name: 'Alpha', isSystem: false, isRunning: true, version: '1.0' },
      { pkg: 'com.example.beta', name: 'Beta', isSystem: false, version: '2.0' }]); },
    getWorkingMode() { return JSON.stringify(priv
      ? { adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }
      : { adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'readonly', modeAvailable: false, isPrivileged: false }); },
    getUpdateState() { return JSON.stringify({ running: false, checkedAt: updates.length ? now : 0, updates, checked: 10, errors: 0, selfStatus: 'ok' }); },
    checkForUpdates() { window.__calls.push('check'); },
    approveUpdateAll(json) { window.__calls.push('approveUpdateAll:' + json); },
    installUpdate(pkg) { window.__calls.push('install:' + pkg); },
    endUpdateAll() {},
    openUrl() {}, copyToClipboard() {},
  };
};
