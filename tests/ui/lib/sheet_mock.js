// Mock bridge for the single-app sheet with realistic content: a header with an update hint and sizes, 45 permissions, app ops, components and a manifest.
// Used by the sheet-height measurements and by t69.
exports.initScript = function (opts) {
  opts = opts || {};
  const now = Date.now(), day = 864e5;
  const perms = [];
  const names = ['INTERNET', 'CAMERA', 'RECORD_AUDIO', 'READ_CONTACTS', 'WRITE_CONTACTS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'READ_CALENDAR', 'WRITE_CALENDAR', 'POST_NOTIFICATIONS',
    'READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO', 'READ_PHONE_STATE', 'CALL_PHONE', 'SEND_SMS', 'READ_SMS', 'BODY_SENSORS', 'ACTIVITY_RECOGNITION', 'BLUETOOTH_CONNECT',
    'BLUETOOTH_SCAN', 'NEARBY_WIFI_DEVICES', 'VIBRATE', 'WAKE_LOCK', 'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE', 'FOREGROUND_SERVICE', 'RECEIVE_BOOT_COMPLETED', 'REQUEST_INSTALL_PACKAGES', 'SYSTEM_ALERT_WINDOW',
    'USE_BIOMETRIC', 'USE_FINGERPRINT', 'CHANGE_WIFI_STATE', 'NFC', 'GET_ACCOUNTS', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'SET_WALLPAPER', 'EXPAND_STATUS_BAR',
    'REORDER_TASKS', 'KILL_BACKGROUND_PROCESSES', 'QUERY_ALL_PACKAGES', 'SCHEDULE_EXACT_ALARM', 'USE_FULL_SCREEN_INTENT'];
  names.forEach((n, i) => perms.push({ name: 'android.permission.' + n, granted: i % 3 !== 1, changeable: i % 4 < 2, protection: i % 4 < 2 ? 'dangerous' : 'normal', label: '' }));
  const ops = ['CAMERA: allow; time=+2h ago', 'COARSE_LOCATION: foreground; time=+3d ago', 'RECORD_AUDIO: ignore', 'READ_CLIPBOARD: allow', 'WAKE_LOCK: allow; time=+5m ago', 'POST_NOTIFICATION: allow', 'RUN_IN_BACKGROUND: allow', 'GET_USAGE_STATS: deny',
    'SYSTEM_ALERT_WINDOW: ignore', 'VIBRATE: allow; time=+1h ago', 'TAKE_AUDIO_FOCUS: allow', 'ACTIVATE_VPN: deny'].join('\n');
  const acts = []; for (let i = 0; i < 18; i++) acts.push('com.sec.android.app.sbrowser.ui.Activity' + i);
  const svcs = []; for (let i = 0; i < 8; i++) svcs.push('com.sec.android.app.sbrowser.svc.Service' + i);
  const apps = [
    { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false, version: '26.0.3.1', installedAt: now - 400 * day, updatedAt: now - 3 * day, apkSize: 180e6 },
    { pkg: 'com.microsoft.skydrive', name: 'OneDrive', isSystem: false, isUninstalled: true, version: '6.80', installedAt: now - 300 * day, updatedAt: now - 30 * day, apkSize: 60e6 },
    { pkg: 'com.facebook.appmanager', name: 'Facebook App Manager', isSystem: true, isFrozen: true, isSuspended: true, version: '371.0', installedAt: now - 300 * day, updatedAt: now - 30 * day, apkSize: 20e6 },
  ];
  const updates = [{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', installedVersion: '26.0.3.1', availableVersion: '27.1.0.103', source: 'galaxy', isSystem: true }];
  window.__sheetCalls = [];
  window.AndroidBridge = {
    vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: opts.dark === false ? 'light' : 'dark', overrides: { dark: {}, light: {} } }); }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', model: 'SM-S928B', androidVersion: '14' }); }, isSystemDarkMode() { return opts.dark !== false; }, setSystemBarColor() {},
    saveStore() {}, loadStore() { return ''; },
    loadPackages() { return JSON.stringify(apps); },
    getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
    getUpdateState() { return JSON.stringify({ running: false, checkedAt: now, updates, checked: 120, errors: 0, selfStatus: 'ok' }); },
    getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 2600031, firstInstallTime: now - 400 * day, lastUpdateTime: now - 3 * day, permissions: perms, appopsRaw: ops, activities: acts, services: svcs, receivers: [], providers: [] }); },
    getAppSizes() { return JSON.stringify({ apk: 180e6, splits: 1, usageAccess: true, stats: { app: 200e6, data: 412e6, cache: 88e6 } }); },
    getAllAppSizes() { return '{}'; },
    getAppManifest() { let s = '<?xml version="1.0" encoding="utf-8"?>\n<manifest package="com.sec.android.app.sbrowser" versionName="26.0.3.1">\n'; for (let i = 0; i < 60; i++) s += '  <uses-permission name="android.permission.P' + i + '"/>\n'; return s + '</manifest>'; },
    executeAppAction(a, p) { window.__sheetCalls.push(a + ':' + p); return 'Success'; }, executeShell() { return ''; },
    copyToClipboard() {}, openUrl() {}, extractApk() {}, shareStoredFile() { return ''; }, shareTextFile() { return ''; }, shareText() {},
  };
};
