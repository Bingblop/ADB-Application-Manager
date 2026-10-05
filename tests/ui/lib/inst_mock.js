// Mock bridge for the Installer, the permission sheets and the storage search, driven by the script step by step:
// the search and every file operation answer only when the script says so (window.__scanStep / __scanFinish / __opsFlush), so a script can
// look at the page while something is "still running" without racing a timer.
//
//   await page.addInitScript(inst.initScript, { perm: { files: false, usage: false, overlay: false }, device: { abis: ['arm64-v8a'], dpi: 420, locales: ['en-US'] } });
//
// Inside the page:
//   window.__perm            what the phone allows now; the script flips a value and calls window.onAppResume() to play "came back from Settings"
//   window.__pkgs[ref]       what inspectInstallSource answers for a picked file
//   window.__fs              the files of the fake storage (a Set of paths) the search finds and apkFileOp moves around
//   window.__dirs[path]      what fmList answers for a folder (default: Download/ and a.txt); window.__fmEmptyFile: an app without the access gets an empty list
//   window.__calls           everything the page asked of the bridge
exports.initScript = function (opts) {
  opts = opts || {};
  const calls = { actions: [], opened: [], perms: [], scan: 0, ops: [], inspect: [], install: [], kv: [], settings: [], toasts: [] };
  window.__calls = calls;
  window.__kv = Object.assign({}, opts.kv || {});
  window.__perm = Object.assign({ files: true, usage: true, overlay: true, storage_legacy: true, secure_settings: true, restricted_settings: true }, opts.perm || {});
  window.__device = Object.assign({ abis: ['arm64-v8a', 'armeabi-v7a', 'armeabi'], dpi: 420, locales: ['en-US'], sdk: 34 }, opts.device || {});
  window.__mode = Object.assign({ priv: true }, opts.mode || {});
  window.__pkgs = {};
  window.__fs = new Set(opts.files || []);
  window.__fileMeta = {};
  window.__trashSeq = 0;
  window.__opQueue = [];
  window.__opsHold = opts.holdOps === true;
  window.__opFail = '';
  window.__scanPending = false;
  window.__scanHold = opts.holdScan !== false;
  window.__scanFiles = null;
  window.__scanResult = null;
  const store = Object.assign({}, opts.store || {});
  window.__store = store;
  const answerOp = (id, res) => {
    const send = () => window.onApkFileOp(id, JSON.stringify(res));
    if (window.__opsHold) window.__opQueue.push(send); else setTimeout(send, 0);
  };
  window.__opsFlush = () => { const q = window.__opQueue.splice(0); q.forEach(f => f()); return q.length; };
  const entryOf = path => Object.assign({ path, name: path.slice(path.lastIndexOf('/') + 1), kind: (path.split('.').pop() || 'apk').toLowerCase(), size: 12345678, mtime: Date.now() - 3 * 864e5, shell: false }, window.__fileMeta[path] || {});
  window.__analyzeFinish = (files) => window.onApkAnalyze({ files: files || [], ms: 30 });
  window.__scanStep = (pct, msg, found) => window.onApkScanProgress(JSON.stringify({ pct, msg, found: found || 0 }));
  window.__scanFinish = (extra) => {
    window.__scanPending = false;
    const files = (window.__scanFiles || Array.from(window.__fs).filter(p => !p.includes('/.adb_manager_trash/'))).map(p => typeof p === 'string' ? entryOf(p) : p);
    window.onApkScan(JSON.stringify(Object.assign({ status: 'ok', files, truncated: false, ms: 1200, fs: true, shell: true }, window.__scanResult || {}, extra || {})));
  };
  window.AndroidBridge = {
    vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', device: 'SM-S928B', release: '14' }); }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    saveStore(k, v) { store[k] = v; return true; }, loadStore(k) { return store[k] || ''; },
    loadPackages() { return JSON.stringify(opts.apps || [{ pkg: 'com.example.app', name: 'Example App', isSystem: false }]); },
    getWorkingMode() {
      const priv = window.__mode.priv;
      return JSON.stringify({ adbTcp: priv ? { connected: true, port: 5555 } : {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: priv ? 'adb_tcp' : 'unprivileged', modeAvailable: true, isPrivileged: priv });
    },
    getAppDetails() { return '{}'; },
    loadSetting(k) { return window.__kv[k] || ''; }, saveSetting(k, v) { window.__kv[k] = v; calls.kv.push(k + '=' + v); },
    // ---- permissions ----
    hasAllFilesAccess() { return !!window.__perm.files; },
    getPermissionStatus() { return JSON.stringify(Object.assign({ sdk: window.__device.sdk }, window.__perm)); },
    requestAllFilesAccess() { calls.perms.push('files'); },
    requestUsageAccess() { calls.perms.push('usage'); if (window.__mode.priv && opts.grantUsageByShell) { window.__perm.usage = true; return 'granted'; } return 'settings'; },
    requestOverlayAccess() { calls.perms.push('overlay'); if (window.__mode.priv && opts.grantOverlayByShell) { window.__perm.overlay = true; return 'granted'; } return 'settings'; },
    requestLegacyStorageAccess() { calls.perms.push('storage_legacy'); if (window.__mode.priv) { window.__perm.storage_legacy = true; return 'granted'; } return 'settings'; },
    requestWriteSecureSettings() { calls.perms.push('secure_settings'); if (window.__mode.priv) { window.__perm.secure_settings = true; return 'granted'; } return 'settings'; },
    requestRestrictedSettingsAccess() { calls.perms.push('restricted_settings'); if (window.__mode.priv) { window.__perm.restricted_settings = true; return 'granted'; } return 'settings'; },
    // ---- the device, for the splits ----
    getDeviceProfile() { return JSON.stringify(window.__device); },
    // ---- picking and reading a package ----
    pickInstallerFile() { window.onInstallFilePicked(opts.pickRef || 'content://pick/1'); },
    inspectInstallSource(ref) {
      calls.inspect.push(ref);
      const pkg = window.__pkgs[ref];
      // like MainActivity: a package on storage that can't be read for want of the access says so first (needFileAccess), then fails
      if (window.__inspectError && !String(ref).startsWith('content://') && /EACCES|permission denied/i.test(window.__inspectError) && !window.__perm.files && window.onFileAccessNeeded) window.onFileAccessNeeded('To read this package from storage');
      setTimeout(() => window.onInstallInspected(JSON.stringify(window.__inspectError ? { error: window.__inspectError } : pkg ? Object.assign({ ref }, pkg) : { error: 'could not read ' + ref })), 0);
    },
    fmInstall(path) { calls.opened.push('fmInstall:' + path); return JSON.stringify({ ok: true, ref: '/data/local/tmp/fm_install.apk' }); },
    installSelected(json) { calls.install.push(JSON.parse(json)); },
    // ---- the file manager: storage can be read with the access, or through a working mode ----
    fmList(path) {
      calls.opened.push('fmList:' + path);
      const viaShell = !window.__perm.files;          // the app can't read storage itself: a working mode answers, or nothing does
      if (viaShell && !window.__mode.priv) {
        // window.__fmEmptyFile: Android 11 and newer may answer an empty list, not an error, for a folder this app is not allowed to read
        if (window.__fmEmptyFile) return JSON.stringify({ path, entries: [], source: 'file' });
        return JSON.stringify({ path, error: "Can't read this folder. For storage, grant All-files access; for system folders, set up ADB, Shizuku or Root." });
      }
      return JSON.stringify({ path, source: viaShell ? 'shell' : 'file', entries: (window.__dirs && window.__dirs[path]) || [{ name: 'Download', isDir: true, perms: 'drwxrwx---', size: 0 }, { name: 'a.txt', isDir: false, perms: '-rw-rw----', size: 12 }] });
    },
    // ---- the search for package files on storage ----
    scanApkFiles() {
      calls.scan++;
      window.__scanPending = true;
      if (!window.__scanHold) setTimeout(() => window.__scanFinish(), 0);
    },
    // ---- what the search found: package names, versions, copies and older versions (answers when the script says so: window.__analyzeFinish(files)) ----
    apkAnalyze(json) { calls.analyze = (calls.analyze || []).concat([JSON.parse(json)]); },
    // ---- deleting one of them, with Undo ----
    apkFileOp(id, op, a, b) {
      calls.ops.push(op + ':' + a + (b ? '>' + b : ''));
      if (window.__opFail === op) { answerOp(id, { ok: false, error: 'Android refused (' + op + ')' }); return; }
      if (op === 'trash') {
        if (!window.__fs.has(a)) { answerOp(id, { ok: false, error: 'That file is already gone.' }); return; }
        const dest = a.replace(/\/[^\/]*$/, '').replace(/^(\/storage\/[^\/]+\/[^\/]+|\/storage\/[^\/]+)\/.*/, '$1') + '/.adb_manager_trash/' + (++window.__trashSeq) + '_' + a.slice(a.lastIndexOf('/') + 1);
        window.__fs.delete(a); window.__fs.add(dest); window.__fileMeta[dest] = window.__fileMeta[a] || {};
        answerOp(id, { ok: true, trash: dest });
      } else if (op === 'untrash') {
        let target = b;
        for (let i = 2; window.__fs.has(target); i++) target = b.replace(/(\.[A-Za-z]+)$/, ' (' + i + ')$1');
        window.__fs.delete(a); window.__fs.add(target);
        answerOp(id, { ok: true, path: target });
      } else if (op === 'purge') {
        if (a) window.__fs.delete(a); else Array.from(window.__fs).filter(p => p.includes('/.adb_manager_trash/')).forEach(p => window.__fs.delete(p));
        answerOp(id, { ok: true });
      } else answerOp(id, { ok: false, error: 'unknown op' });
    },
    // ---- the app that was just installed ----
    executeAppAction(action, pkg) { calls.actions.push(action + ':' + pkg); return window.__actionAnswer ? window.__actionAnswer(action, pkg) : (action === 'launch' ? 'Launched' : 'Settings opened'); },
    executeShell() { return ''; }, copyToClipboard() {}, openUrl() {},
  };
};
