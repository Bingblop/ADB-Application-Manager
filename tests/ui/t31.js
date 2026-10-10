// File manager: ls parsing (toybox, busybox, fallback), path joins, access hint; Logcat play/pause polling
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  // toybox-style /sdcard listing (YYYY-MM-DD HH:MM) + a busybox-style line + a symlink + an error-free mix
  const TOYBOX = [
    'total 96',
    'drwxrwx--x 4 root sdcard_rw 3452 2024-05-01 12:33 Android',
    'drwxrwx--- 2 u0_a1 sdcard_rw 3452 2024-05-02 09:10 Download',
    'drwxrwx--- 2 u0_a1 sdcard_rw 3452 2024-05-02 09:10 DCIM',
    '-rw-rw---- 1 u0_a1 sdcard_rw 24117248 2024-05-03 18:00 app-release.apk',
    '-rw-rw---- 1 u0_a1 sdcard_rw 2048 2024-05-03 18:01 my notes.txt',
    'lrwxrwxrwx 1 root root 21 2024-05-01 00:00 sdcard -> /storage/self/primary',
  ].join('\n');
  const BUSYBOX = [
    'drwxrwx--x    4 root     sdcard_r      3452 May  1 12:33 Android',
    '-rw-rw----    1 u0_a1    sdcard_r  24117248 May  3 18:00 app-release.apk',
    'lrwxrwxrwx    1 root     root            21 May  1 00:00 sdcard -> /storage/self/primary',
  ].join('\n');
  const PERMDENIED = 'ls: /data/system: Permission denied';

  await page.addInitScript((data) => {
    window.__which = 'toybox';
    window.__data = data;
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      fmList(p) { return JSON.stringify({ path: p, parent: '/', raw: window.__data[window.__which] }); },
      hasAllFilesAccess() { return window.__hasAccess !== false; },
      requestAllFilesAccess() { window.__calls.push('grantReq'); },
      getLogcat(level, filter, lines) { window.__calls.push('logcat:' + level); return 'LINE @ ' + Date.now() + ' level=' + level; },
      clearLogcat() { return 'cleared'; }, copyToClipboard() {},
    };
  }, { toybox: TOYBOX, busybox: BUSYBOX, denied: PERMDENIED });

  await page.goto(PAGE); await page.waitForTimeout(400);

  // ---- toybox parse ----
  await page.evaluate(() => switchView('files')); await page.waitForTimeout(150);
  let names = await page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(e => e.innerText));
  console.log('1. toybox entries:', JSON.stringify(names));
  console.log('   has "my notes.txt" (name with space):', names.some(n => n.includes('my notes.txt')));
  console.log('   has symlink sdcard:', names.some(n => n.includes('sdcard')));
  console.log('   dirs first (Android before app-release):', names.findIndex(n=>n.includes('Android')) < names.findIndex(n=>n.includes('app-release')));

  // ---- busybox parse ----
  await page.evaluate(() => { window.__which = 'busybox'; fmRefresh(); }); await page.waitForTimeout(100);
  names = await page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(e => e.innerText));
  console.log('2. busybox entries:', JSON.stringify(names));
  console.log('   busybox apk parsed with correct name:', names.some(n => n.includes('app-release.apk')));

  // ---- permission-denied error surfaces when no entries ----
  await page.evaluate(() => { window.__which = 'denied'; fmRefresh(); }); await page.waitForTimeout(100);
  console.log('3. denied -> shows error text:', (await page.locator('#fmList').innerText()).toLowerCase().includes('permission denied'));

  // ---- parse unit check for sizes ----
  const parsed = await page.evaluate(() => parseLsOutput(window.__data.toybox));
  const apk = parsed.entries.find(e => e.name === 'app-release.apk');
  console.log('4. apk size parsed:', apk && apk.size, '(expect 24117248)');
  const link = parsed.entries.find(e => e.isLink);
  console.log('   symlink target parsed:', link && link.link);

  // ---- logcat play/pause: opening the tab now starts playing on its own ----
  await page.evaluate(() => { window.__which = 'toybox'; window.__calls.length = 0; switchView('logcat'); }); await page.waitForTimeout(150);
  console.log('5. opening the tab already starts playing (a fetch happened, Pause shown):', (await page.evaluate(() => window.__calls.length >= 1)) && (await page.locator('#logcatPlayBtn').innerText()) === 'Pause');
  await page.waitForTimeout(3300); // ~3 more polls at 1s, with no need to press Play by hand
  const pollCount = await page.evaluate(() => window.__calls.length);
  console.log('6. polling continues on its own (expect >=2):', pollCount >= 2, pollCount);
  await page.evaluate(() => logcatTogglePlay()); // pause
  const afterPause = await page.evaluate(() => window.__calls.length);
  await page.waitForTimeout(1800);
  const afterWait = await page.evaluate(() => window.__calls.length);
  console.log('7. pausing stops polling (counts equal):', afterPause === afterWait, '(', afterPause, '==', afterWait, ')');
  console.log('   play btn back to Play:', await page.locator('#logcatPlayBtn').innerText());

  // leaving the tab stops polling, even resumed mid-play (not just when already paused)
  await page.evaluate(() => { logcatTogglePlay(); switchView('apps'); });
  await page.waitForTimeout(100);
  const c1 = await page.evaluate(() => window.__calls.length);
  await page.waitForTimeout(1800);
  const c2 = await page.evaluate(() => window.__calls.length);
  console.log('8. leaving logcat tab stops polling:', c1 === c2);

  // taller terminal box
  const h = await page.evaluate(() => { switchView('logcat'); return getComputedStyle(document.getElementById('logcatOutput')).height; });
  console.log('9. logcat box height:', h);

  // ---- name-list fallback when ls -la can't be parsed ----
  const fb = await page.evaluate(() => parseNameList('Android/\nDownload/\napp-release.apk\n'));
  console.log('10. parseNameList fallback:', JSON.stringify(fb.map(e => e.name + (e.isDir ? '/' : ''))));
  await page.evaluate(() => { switchView('files'); window.AndroidBridge.fmList = (p) => JSON.stringify({ path: p, parent: '/', raw: 'garbage line one\nmore junk', names: 'Pictures/\nfoo.txt' }); fmRefresh(); });
  await page.waitForTimeout(80);
  console.log('11. fallback entries rendered:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(e => e.innerText))));
  await page.evaluate(() => { window.AndroidBridge.fmList = (p) => JSON.stringify({ path: p, parent: '/', raw: 'weird unparseable blob', names: '' }); fmRefresh(); });
  await page.waitForTimeout(80);
  console.log('12. raw diagnostic shown when nothing parses:', (await page.locator('#fmList').innerText()).includes('Raw output'));

  // ---- logcat Clear empties the terminal ----
  await page.evaluate(() => switchView('logcat')); await page.waitForTimeout(120);
  await page.evaluate(() => logcatClear());
  console.log('13. logcat clear -> terminal text:', JSON.stringify(await page.locator('#logcatOutput').innerText()));

  // ---- File-API direct entries (storage) ----
  await page.evaluate(() => {
    window.__hasAccess = true;
    window.AndroidBridge.fmList = (p) => JSON.stringify({ path: p, parent: '/', source: 'file', entries: [{ name: 'Download', isDir: true, isLink: false, size: 0, perms: 'drwx' }, { name: 'photo.jpg', isDir: false, isLink: false, size: 123456, perms: '-rw-' }] });
    switchView('files'); fmGo('/sdcard');
  });
  await page.waitForTimeout(80);
  console.log('14. File-API entries rendered:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(e => e.innerText))));
  console.log('    access hint hidden (has access + entries):', !(await page.isVisible('#fmAccessHint')));

  // ---- storage empty + no access -> grant hint shows ----
  await page.evaluate(() => { window.__hasAccess = false; window.AndroidBridge.fmList = (p) => JSON.stringify({ path: p, parent: '/', source: 'shell', raw: '', names: '' }); fmGo('/sdcard'); });
  await page.waitForTimeout(80);
  console.log('15. access hint shown (no access, empty /sdcard):', await page.isVisible('#fmAccessHint'));
  await page.evaluate(() => fmGrantAccess());
  console.log('16. grant request fired:', await page.evaluate(() => window.__calls.includes('grantReq')));

  // ---- /sdcard symlink listed as its own entry must not produce /sdcard//sdcard ----
  const LONE_SYMLINK = 'lrwxrwxrwx 1 root root 21 2024-05-01 00:00 /sdcard -> /storage/self/primary';
  const lone = await page.evaluate((ln) => parseLsLine(ln), LONE_SYMLINK);
  console.log('17. lone symlink name basenamed to "sdcard":', lone && lone.name, '(expect sdcard)');
  // fmJoin + normalize never yields a doubled slash, even with a slashed name or trailing-slash cwd
  const joins = await page.evaluate(() => {
    fmPath = '/sdcard';
    const a = fmJoin('photo.jpg');
    const b = fmJoin('/sdcard');          // defensive: name with leading slash
    fmPath = '/sdcard/';                   // defensive: cwd with trailing slash
    const c = fmJoin('Download');
    fmPath = '/sdcard';
    return { a, b, c, norm: fmNormalizePath('/sdcard//sdcard'), parent: fmParent('/sdcard//x') };
  });
  console.log('18. fmJoin normal:', joins.a, '| leading-slash name:', joins.b, '| trailing-slash cwd:', joins.c);
  console.log('    no "//" in any join:', !/\/\//.test(joins.a + joins.b + joins.c));
  console.log('    fmNormalizePath("/sdcard//sdcard"):', joins.norm, '| fmParent("/sdcard//x"):', joins.parent);
  // end-to-end: a raw listing that is only the lone symlink renders a clean "sdcard" entry whose action path has no //
  await page.evaluate(() => {
    window.AndroidBridge.fmList = (p) => JSON.stringify({ path: '/sdcard', parent: '/', raw: 'lrwxrwxrwx 1 root root 21 2024-05-01 00:00 /sdcard -> /storage/self/primary' });
    switchView('files'); fmGo('/sdcard');
  });
  await page.waitForTimeout(80);
  const loneNames = await page.evaluate(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(e => e.innerText));
  console.log('19. lone-symlink listing entries:', JSON.stringify(loneNames));

  // ---- default storage path is the concrete /storage/emulated/0 ----
  const defPath = await page.evaluate(() => {
    window.AndroidBridge.fmList = (p) => JSON.stringify({ path: p, parent: '/storage/emulated', entries: [] });
    switchView('files'); fmGo('/storage/emulated/0');
    return { current: document.getElementById('fmCurrentPath').innerText, input: document.getElementById('fmPathInput').value };
  });
  console.log('20. default storage path:', defPath.current, '| input:', defPath.input);
  console.log('    uses /storage/emulated/0:', defPath.current === '/storage/emulated/0' && defPath.input === '/storage/emulated/0');

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
