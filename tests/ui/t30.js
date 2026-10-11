// File manager: browse, up, send APK to installer, view, rename, delete, new folder; Logcat load, filter, clear
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = [];
    const DIRS = {
      '/sdcard': [
        'drwxrwx--- 2 u0 sdcard_rw 3452 2024-05-01 12:00 Download',
        '-rw-rw---- 1 u0 sdcard_rw 4000000 2024-05-01 12:00 app.apk',
        '-rw-rw---- 1 u0 sdcard_rw 120 2024-05-01 12:00 notes.txt',
      ].join('\n'),
      '/sdcard/Download': '-rw-rw---- 1 u0 sdcard_rw 999 2024-05-01 12:00 sub.bin',
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      fmList(path) { window.__calls.push('list:' + path); return JSON.stringify({ path, parent: path === '/sdcard/Download' ? '/sdcard' : '/', raw: DIRS[path] || '' }); },
      fmRead(path) { window.__calls.push('read:' + path); return 'file contents of ' + path; },
      fmOp(op, a, b) { window.__calls.push('op:' + op + ':' + a + (b ? ':' + b : '')); return JSON.stringify({ ok: true, output: 'OK' }); },
      fmInstall(path) { window.__calls.push('install:' + path); return JSON.stringify({ ok: true, ref: '/data/local/tmp/fm_install.apk' }); },
      inspectInstallSource(ref) { window.__calls.push('inspect:' + ref); },
      getLogcat(level, filter, lines) { window.__calls.push('logcat:' + level + ':' + filter + ':' + lines); return 'LOG level=' + level + ' lines=' + lines + (filter ? ' filter=' + filter : ''); },
      clearLogcat() { window.__calls.push('clearLogcat'); return 'cleared'; },
      copyToClipboard(t) { window.__calls.push('copy:' + (t || '').slice(0, 10)); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);

  // ---- File manager ----
  await page.evaluate(() => switchView('files'));
  await page.waitForTimeout(150);
  console.log('1. files listed on open:', await page.locator('#fmList .perm-row[data-i]').count(), '(expect 3)');
  console.log('   current path:', await page.locator('#fmCurrentPath').innerText());

  // navigate into Download
  await page.evaluate(() => fmTap(true, '/sdcard/Download', 'Download'));
  await page.waitForTimeout(100);
  console.log('2. navigated into Download:', await page.locator('#fmCurrentPath').innerText(), '| entries:', await page.locator('#fmList .perm-row').count());
  await page.evaluate(() => fmUp());
  await page.waitForTimeout(100);
  console.log('3. up back to:', await page.locator('#fmCurrentPath').innerText());

  // actions on the apk -> Install present, routes to installer
  await page.evaluate(() => { window.__calls.length = 0; fmActions('/sdcard/app.apk', false, 'app.apk'); });
  const btns = await page.locator('#fmActionBtns .batch-grid-btn').allInnerTexts();
  console.log('4. apk action buttons:', JSON.stringify(btns));
  await page.evaluate(() => fmInstallFile());
  await page.waitForTimeout(100);
  console.log('   install staged + routed to installer tab:', await page.evaluate(() => window.__calls), '| installer active:', await page.evaluate(() => document.getElementById('view-installer').classList.contains('active')));

  // view a text file
  await page.evaluate(() => { window.__calls.length = 0; fmActions('/sdcard/notes.txt', false, 'notes.txt'); fmViewFile(); });
  await page.waitForTimeout(100);
  console.log('5. view file read call + viewer shown:', await page.evaluate(() => window.__calls), '|', await page.isVisible('#fmViewer'));

  // rename via dest confirm
  await page.evaluate(() => { window.__calls.length = 0; fmActions('/sdcard/notes.txt', false, 'notes.txt'); fmStartDest('rename'); document.getElementById('fmDestInput').value = 'renamed.txt'; fmDestConfirm(); });
  await page.waitForTimeout(100);
  console.log('6. rename op (expect mv to /sdcard/renamed.txt):', await page.evaluate(() => window.__calls.filter(c => c.startsWith('op:'))));

  // delete
  await page.evaluate(() => { window.__calls.length = 0; fmActions('/sdcard/notes.txt', false, 'notes.txt'); fmStartDest('rm'); fmDestConfirm(); });
  await page.waitForTimeout(100);
  console.log('7. delete op (expect rm):', await page.evaluate(() => window.__calls.filter(c => c.startsWith('op:'))));

  // new folder
  await page.evaluate(() => { window.__calls.length = 0; fmNewFolder(); document.getElementById('fmDestInput').value = 'NewDir'; fmDestConfirm(); });
  await page.waitForTimeout(100);
  console.log('8. mkdir op (expect mkdir /sdcard/NewDir):', await page.evaluate(() => window.__calls.filter(c => c.startsWith('op:'))));

  // ---- Logcat ----
  await page.evaluate(() => switchView('logcat'));
  await page.waitForTimeout(150);
  console.log('9. logcat loaded on open:', await page.evaluate(() => window.__calls.filter(c => c.startsWith('logcat:'))));
  console.log('   output shown:', (await page.locator('#logcatOutput').innerText()).slice(0, 40));
  await page.evaluate(() => { document.getElementById('logcatLevel').value = 'E'; document.getElementById('logcatFilter').value = 'ActivityManager'; window.__calls.length = 0; logcatRefresh(); });
  await page.waitForTimeout(100);
  console.log('10. logcat refresh with level E + filter:', await page.evaluate(() => window.__calls.filter(c => c.startsWith('logcat:'))));
  await page.evaluate(() => { window.__calls.length = 0; logcatClear(); });
  await page.waitForTimeout(100);
  console.log('11. logcat clear called:', await page.evaluate(() => window.__calls.filter(c => c === 'clearLogcat').length === 1));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
