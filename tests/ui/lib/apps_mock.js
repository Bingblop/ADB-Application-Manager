// A phone with a short app list that carries every status badge (running, system, user, frozen, suspended, uninstalled, a patched app's MOD tag), for the
// type-scale and badge-contrast scripts. Run it after sdb_mock (and the other tab mocks) so its methods win:
//   await page.addInitScript(appsMock.initScript, { dark: true, preset: 'material3', lang: 'de', unpriv: false });
module.exports.initScript = function (opts) {
  opts = opts || {};
  const day = 864e5, now = Date.now();
  const raw = [
    ['com.whatsapp', 'WhatsApp', { isRunning: true }],
    ['com.facebook.katana', 'Facebook', { isSuspended: true }],
    ['com.android.chrome', 'Chrome', { isSystem: true, isRunning: true }],
    ['com.samsung.android.bixby.agent', 'Bixby Voice', { isSystem: true, isFrozen: true }],
    ['com.microsoft.skydrive', 'OneDrive', { isUninstalled: true }],
    ['com.amazon.mShop.android.shopping', 'Amazon Shopping', {}],
    ['org.mozilla.firefox', 'Firefox', { mods: ['Morphe'] }],
  ];
  const apps = raw.map(([pkg, name, x], i) => Object.assign({ pkg, name, isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false,
    version: (i + 1) + '.0', installedAt: now - (40 + i) * day, updatedAt: now - i * day, apkSize: (5 + i) * 1e6 }, x));
  const kv = Object.assign({}, opts.lang && opts.lang !== 'en' ? { app_language: opts.lang } : {});
  const b = window.AndroidBridge = window.AndroidBridge || {};
  Object.assign(b, {
    vibrate() {}, savePreferences() {}, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    loadPreferences() { return JSON.stringify({ version: 2, preset: opts.preset || 'material3', appearance: opts.dark === false ? 'light' : 'dark', overrides: { dark: {}, light: {} } }); },
    isSystemDarkMode() { return opts.dark !== false; },
    loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', model: 'SM-S928B', androidVersion: '14', sdk: 34 }); },
    loadSetting(k) { return Object.prototype.hasOwnProperty.call(kv, k) ? kv[k] : ''; }, saveSetting(k, v) { kv[k] = v; return true; },
    saveStore() {}, loadStore() { return ''; },
    loadPackages() { return JSON.stringify(apps); },
    loadAppIcons() { return 'started'; },
    getWorkingMode() { return JSON.stringify(opts.unpriv
      ? { adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'readonly', modeAvailable: false, isPrivileged: false }
      : { adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
    getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 2600031, firstInstallTime: now - 400 * day, lastUpdateTime: now - 3 * day, permissions: [], activities: [], services: [], receivers: [], providers: [] }); },
    getAppSizes() { return JSON.stringify({ apk: 180e6, splits: 1, usageAccess: true, stats: { app: 200e6, data: 412e6, cache: 88e6 } }); },
    executeAppAction() { return 'Success'; }, executeShell() { return ''; }, copyToClipboard() {}, openUrl() {}, shareText() {},
  });
};
