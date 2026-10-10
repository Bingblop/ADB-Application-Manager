// v7.12.8: the App Updater is no longer a tab of its own and the App Stores tab keeps its name: the APK Installer tab became "Installer/Updater", with two boxes at the top,
// Application Installer (the default, on the left) and Application Updater (on the right), like the Terminal and the ADB Console. The Updater has a search (APKMirror by
// default, other sources in a list, a small Version box, check boxes, install on download, a save place, the Default Ask Agent) and a KeyStore import / export.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [{ pkg: 'com.example.maps', name: 'Example Maps', version: '2.0.0', isFrozen: false, isSuspended: false, isUninstalled: false }];
  await page.addInitScript(a => {
    window.__calls = []; window.__opened = []; window.__vt = ''; window.__vers = null; window.__helperGet = null;
    const VERS = [
      { version: '3.0.0-beta1', versionCode: 30, format: 'apk', abi: 'arm64-v8a', size: 1000, url: 'https://x/3b', page: 'p3b' },
      { version: '2.5.0', versionCode: 25, format: 'apkm', abi: 'universal', size: 2000, url: 'https://x/25', page: 'p25' },
      { version: '2.4.1', versionCode: 24, format: 'apk', abi: 'arm64-v8a', size: 3000, url: 'https://x/241', page: 'p241' },
      { version: '2.0.0', versionCode: 20, format: 'apk', abi: 'arm64-v8a', size: 3000, url: 'https://x/20', page: 'p20' }
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      pickBackupFolder(tag) { setTimeout(() => window.onBackupFolderPicked({ tag, uri: 'content://tree/apks', label: 'My APKs' }), 5); },
      morphe(tag, op, args) {
        const x = JSON.parse(args); window.__calls.push([op, x]);
        let r = { tag, ok: true, data: {} };
        if (op === 'helperSources') r.data = { sources: [{ id: 'apkmirror', name: 'APKMirror', recommended: true, latest: true, history: true, direct: true }, { id: 'uptodown', name: 'Uptodown', recommended: true, latest: true, history: true, direct: false }], abi: 'arm64-v8a,armeabi-v7a' };
        else if (op === 'helperFind') r.data = { ok: true, items: [{ name: 'Example Maps', pkg: 'com.example.maps', version: '2.5.0', icon: '' }, { name: 'Example Mapper', pkg: 'org.example.mapper', version: '1.0', icon: '' }] };
        else if (op === 'helperVersions') { if (window.__vers === 'browser') r = { tag, ok: false, error: 'APKMirror wants a browser check.', browse: 'https://www.apkmirror.com/x' }; else r.data = { ok: true, source: x.source, pkg: x.pkg, name: 'Example Maps', versions: VERS }; }
        else if (op === 'helperGet') { window.__helperGet = x; if (x.policy === 'requested' && !x.url && ['9.9.9', '2.4.5'].includes(x.version)) r = { tag, ok: false, error: 'Version 9.9.9 was not found.' }; else r.data = { path: '/data/helper/' + x.pkg + '_' + (x.version || 'new') + '.apk', pkg: x.pkg, versionName: x.version || '3.1.0', versionCode: 31, format: 'apk', size: 4096, sha256: 'ab'.repeat(32), fileName: 'x.apk' }; }
        else if (op === 'vtScan') r.data = { found: true, verdict: window.__vt || 'clean', malicious: window.__vt === 'malicious' ? 7 : 0, suspicious: 0, total: 70 };
        else if (op === 'install') r.data = { ok: true, output: 'Success' };
        else if (op === 'copyToTree') r.data = { name: 'x.apk' };
        else if (op === 'keyExport') r.data = { path: '/sdcard/Download/Morphe Patcher/morphe.keystore' };
        else if (op === 'helperManual') r.data = { url: 'https://www.apkmirror.com/?s=' + x.pkg };
        setTimeout(() => window.onMorphe && window.onMorphe(r), 5);
      }
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); window.openUrlExternal = u => window.__opened.push(u); allApps = [{ pkg: 'com.example.maps', name: 'Example Maps', version: '2.0.0' }]; });

  // ---- 1. the tab bar ----
  const bar = await ev(() => [...document.querySelectorAll('.tab-btn')].map(b => b.dataset.tab + '=' + b.innerText.replace(/\n/g, '|')));
  check('1. no App Updater tab; the installer tab is "Installer/Updater" and the stores tab is "App Stores" again', !bar.some(x => /^updates=/.test(x)) && bar.some(x => x === 'installer=Installer/|Updater') && bar.some(x => x === 'store=App|Stores') && !bar.some(x => /Third Party/.test(x)), JSON.stringify(bar));

  // ---- 2. the two boxes ----
  await page.click('.tab-btn[data-tab="installer"]'); await sleep(300);
  const a = await ev(() => {
    const r = id => document.getElementById(id).getBoundingClientRect();
    const sw = r('instSwitchInstall'), up = r('instSwitchUpdater'), host = document.getElementById('view-installer').getBoundingClientRect(), first = document.querySelector('#instPaneInstall .color-card').getBoundingClientRect();
    const vis = id => getComputedStyle(document.getElementById(id)).display !== 'none';
    return { left: sw.left < up.left && Math.abs(sw.top - up.top) < 2, top: sw.top - host.top < 40 && sw.bottom <= first.top, inst: vis('instPaneInstall'), updater: vis('instPaneUpdater'),
      active: document.getElementById('instSwitchInstall').classList.contains('active') && !document.getElementById('instSwitchUpdater').classList.contains('active'),
      names: [document.getElementById('instSwitchInstall').innerText, document.getElementById('instSwitchUpdater').innerText], hasPick: !!document.querySelector('#instPaneInstall button[onclick*="pickInstallFile"]') };
  });
  check('2. two boxes at the top, Application Installer on the left (selected) and Application Updater on the right; the installer is shown', a.left && a.top && a.inst && !a.updater && a.active && a.names[0] === 'Application Installer' && a.names[1] === 'Application Updater' && a.hasPick, JSON.stringify(a));
  await page.click('#instSwitchUpdater'); await sleep(300);
  const u = await ev(() => {
    const vis = id => { const e = document.getElementById(id); return !!e && e.offsetParent !== null; };
    return { inst: vis('instPaneInstall'), updater: vis('instPaneUpdater'), search: vis('usQuery'), self: vis('selfUpdateCard'), check: vis('updCheckBtn'), aurora: vis('openAuroraBtn'), key: vis('usKeyCard'), tab: currentViewName() };
  });
  check('3. the right box shows the search, Check for Updates, the Play Store apps and the KeyStore, and hides the installer', !u.inst && u.updater && u.search && u.check && u.aurora && u.key && u.tab === 'installer', JSON.stringify(u));
  await page.click('#instSwitchInstall'); await sleep(150);
  check('   the left box brings the installer back', await ev(() => getComputedStyle(document.getElementById('instPaneInstall')).display !== 'none' && getComputedStyle(document.getElementById('instPaneUpdater')).display === 'none'));
  await page.click('#instSwitchUpdater'); await sleep(100);
  await page.click('.tab-btn[data-tab="apps"]'); await sleep(200);
  await page.click('.tab-btn[data-tab="installer"]'); await sleep(300);
  check('4. tapping the tab again starts on Application Installer', await ev(() => document.getElementById('instSwitchInstall').classList.contains('active') && getComputedStyle(document.getElementById('instPaneUpdater')).display === 'none'));
  await ev(() => switchView('apps')); await sleep(100);
  const o = await ev(() => { const ok = switchView('updates'); return { ok, view: currentViewName(), updater: getComputedStyle(document.getElementById('instPaneUpdater')).display !== 'none', act: document.getElementById('instSwitchUpdater').classList.contains('active') }; });
  await ev(() => switchView('apps')); await sleep(50);
  await ev(() => { openSelfUpdate(); }); await sleep(150);
  const o2 = await ev(() => ({ view: currentViewName(), updater: getComputedStyle(document.getElementById('instPaneUpdater')).display !== 'none' }));
  check('5. what used to open the App Updater (an app\'s Update button, About, "Download") opens its box in the installer tab', o.ok && o.view === 'installer' && o.updater && o.act && o2.view === 'installer' && o2.updater, JSON.stringify({ o, o2 }));
  await ev(() => { updList = [{ pkg: 'a' }, { pkg: 'b' }]; updateUpdatesTabBadge(); });
  const c = await ev(() => ({ tab: document.getElementById('updatesTabBtn').innerText.replace(/\n/g, '|'), box: document.getElementById('instSwitchUpdater').innerText }));
  check('6. the number of updates waiting shows on the tab and on the Application Updater box', c.tab === 'Installer/|Updater (2)' && c.box === 'Application Updater (2)', JSON.stringify(c));
  await ev(() => { updList = []; updateUpdatesTabBadge(); });

  // ---- 7. the search card ----
  const L = await ev(() => {
    const r = id => document.getElementById(id).getBoundingClientRect();
    const q = r('usQuery'), s = r('usSource'), v = r('usVersion');
    const src = [...document.getElementById('usSource').options].map(o => o.value);
    return { under: s.top >= q.bottom - 1 && v.top >= q.bottom - 1, next: v.left >= s.right - 1 && Math.abs(v.top - s.top) < 4, small: v.width < s.width, src, def: document.getElementById('usSource').value,
      chk: ['usSplit', 'usExact', 'usNear', 'usStable', 'usVt', 'usInstall', 'usAgent'].map(id => document.getElementById(id).checked ? 1 : 0).join(''), ph: [document.getElementById('usQuery').placeholder, document.getElementById('usVersion').placeholder] };
  });
  check('7. a search bar; under it a source list (APKMirror first) and a small Version box next to it', L.under && L.next && L.small && L.src[0] === 'apkmirror' && L.def === 'apkmirror' && L.src.includes('uptodown') && L.src.includes('fdroid') && /name/i.test(L.ph[0]) && L.ph[1] === 'Version', JSON.stringify(L));
  check('   the check boxes: split APK, nearby version, skip beta on; exact version, VirusTotal, install on download, agent off', L.chk === '1011000', L.chk);
  const sv = await ev(() => ({ n: document.getElementById('usSave').value, opts: [...document.getElementById('usSave').options].map(o => o.value), note: document.getElementById('usSrcNote').textContent }));
  check('   the save place list: private, Downloads/App Updater, a folder I choose; the source note is shown', sv.n === 'cache' && sv.opts.join() === 'cache,updater,custom' && /APKMirror/.test(sv.note), JSON.stringify(sv));

  // ---- 8. the pure part: which version ----
  const V = await ev(() => {
    const items = [{ version: '3.0.0-beta1', format: 'apk' }, { version: '2.5.0', format: 'apkm' }, { version: '2.4.1', format: 'apk' }, { version: '2.0.0', format: 'apk' }, { version: '1.9', format: 'apk' }];
    const base = { split: true, exact: false, near: true, stable: true };
    const p = (ver, o) => { const r = usPickVersion(items, ver, Object.assign({}, base, o)); return r.item ? r.item.version + '/' + r.how : 'ERR:' + r.error; };
    return [p('', {}), p('', { stable: false }), p('', { split: false }), p('2.0.0', {}), p('v2.4.1', {}), p('2.4.5', {}), p('2.4.5', { exact: true }), p('2.4.5', { near: false }), p('2.5.0', { split: false }), p('2.2', {}), p('9.0', {}), p('1.0', {}), p('3.0.0', {})];
  });
  check('8. newest skips beta by default; beta when asked; without splits the newest single APK; an exact version; v prefix ignored', V[0] === '2.5.0/newest' && V[1] === '3.0.0-beta1/newest' && V[2] === '2.4.1/newest' && V[3] === '2.0.0/exact' && V[4] === '2.4.1/exact', JSON.stringify(V.slice(0, 5)));
  check('   nearby: 2.4.5 takes 2.4.1; exact only or no nearby refuses; a split bundle is not taken without splits (2.5.0 -> 2.4.1); 9.0 takes the closest (2.5.0); 1.0 takes 1.9', V[5] === '2.4.1/near' && /^ERR:Version 2.4.5 is not listed/.test(V[6]) && /^ERR:/.test(V[7]) && V[8] === '2.4.1/near' && V[10] === '2.5.0/near' && V[11] === '1.9/near', JSON.stringify(V.slice(5)));

  const sug = await ev(() => { const items = [{ version: '2.0-rc1', format: 'apk' }, { version: '1.9', format: 'apk', suggested: true }, { version: '1.8', format: 'apk' }]; const r = usPickVersion(items, '', { split: true, exact: false, near: true, stable: true }), r2 = usPickVersion(items, '', { split: true, exact: false, near: true, stable: false }); return [r.item.version, r2.item.version]; });
  check('   the repository\'s suggested build is the "newest" when stable builds are wanted; without that option the highest number is', sug[0] === '1.9' && sug[1] === '2.0-rc1', JSON.stringify(sug));

  // ---- 9. searching by a package name ----
  await ev(() => { document.getElementById('usQuery').value = 'com.example.maps'; });
  await page.click('#usSearchBtn'); await sleep(250);
  const s1 = await ev(() => ({ rows: document.querySelectorAll('#usResults .us-app').length, name: (document.querySelector('#usResults .us-name') || {}).innerText, btns: [...document.querySelectorAll('#usResults .us-app .us-acts button')].map(x => x.innerText), find: window.__calls.some(c => c[0] === 'helperFind'), inst: (document.querySelector('#usResults .us-app') || {}).innerText }));
  check('9. a package name goes straight to that app (no name search), shows it installed, with Download, Versions, Open on the web', s1.rows === 1 && s1.name === 'Example Maps' && !s1.find && s1.btns.join() === 'Download,Versions,Open on the web' && /Installed: 2\.0\.0/.test(s1.inst), JSON.stringify(s1));

  // ---- 10. searching by a name ----
  await ev(() => { window.__calls.length = 0; document.getElementById('usQuery').value = 'example map'; });
  await page.click('#usSearchBtn'); await sleep(250);
  const s2 = await ev(() => ({ pk: [...document.querySelectorAll('#usResults .us-pkg')].map(x => x.innerText), call: window.__calls.filter(c => c[0] === 'helperFind').map(c => c[1].query) }));
  check('10. a name finds the installed app first and the catalog\'s other matches, once each', s2.call.join() === 'example map' && s2.pk.filter(x => x === 'com.example.maps').length === 1 && s2.pk.includes('org.example.mapper'), JSON.stringify(s2));

  // ---- 11. download the newest (list first: skip beta, splits allowed -> 2.5.0 bundle) ----
  await ev(() => { window.__calls.length = 0; usGet(0); }); await sleep(300);
  const g1 = await ev(() => ({ get: window.__helperGet, st: document.getElementById('usStatus').textContent, got: document.getElementById('usGot').innerText }));
  check('11. Download with no version takes the newest stable one from the list (skipping the beta), from APKMirror, with the CPU the list names, into the private folder', g1.get && g1.get.version === '2.5.0' && g1.get.source === 'apkmirror' && g1.get.save === 'cache' && g1.get.abi === 'universal' && g1.get.url === 'https://x/25' && /Downloaded/.test(g1.st) && /com\.example\.maps/.test(g1.got), JSON.stringify(g1));
  // ---- 12. a typed version, nearby ----
  await ev(() => { document.getElementById('usVersion').value = '2.4.5'; window.__calls.length = 0; window.__helperGet = null; usGet(0); }); await sleep(400);
  const g2 = await ev(() => ({ get: window.__helperGet, st: document.getElementById('usStatus').textContent }));
  check('12. a version that is not there but allowed nearby: the closest (2.4.1) is downloaded and the status says so', g2.get && g2.get.version === '2.4.1' && /closest/i.test(g2.st), JSON.stringify(g2));
  await ev(() => { document.getElementById('usVersion').value = '9.9.9'; document.getElementById('usExact').checked = true; usSave(); window.__calls.length = 0; window.__helperGet = null; usGet(0); }); await sleep(400);
  const g3 = await ev(() => ({ st: document.getElementById('usStatus').textContent, err: getComputedStyle(document.getElementById('usStatus')).color, lists: window.__calls.filter(c => c[0] === 'helperVersions').length, gets: window.__calls.filter(c => c[0] === 'helperGet').length }));
  check('13. exact version only: a missing version is an error and nothing else is downloaded', /not found|not listed/i.test(g3.st) && g3.lists === 0 && g3.gets === 1, JSON.stringify(g3));
  await ev(() => { document.getElementById('usExact').checked = false; document.getElementById('usVersion').value = '2.0.0'; usSave(); window.__calls.length = 0; usGet(0); }); await sleep(400);
  check('14. a version that is listed is asked for by that name', await ev(() => window.__helperGet && window.__helperGet.version === '2.0.0' && window.__helperGet.policy === 'requested'));

  // ---- 15. no split ----
  await ev(() => { document.getElementById('usVersion').value = ''; document.getElementById('usSplit').checked = false; usSave(); window.__helperGet = null; usGet(0); }); await sleep(400);
  check('15. without Allow split APK the newest single APK is taken (2.4.1, not the 2.5.0 bundle)', await ev(() => window.__helperGet && window.__helperGet.version === '2.4.1'), await ev(() => JSON.stringify(window.__helperGet)));
  await ev(() => { document.getElementById('usSplit').checked = true; usSave(); });

  // ---- 16. VirusTotal, install on download, a folder of my choice ----
  await ev(() => { vtKey = () => 'k'.repeat(64); vtLoadQuota = () => {}; document.getElementById('usVt').checked = true; document.getElementById('usInstall').checked = true; document.getElementById('usSave').value = 'custom'; usSaveChanged(); });
  await page.click('#usSavePathRow button'); await sleep(100);
  check('16. Choose folder remembers the folder and shows its name', await ev(() => us.cfg.tree === 'content://tree/apks' && /My APKs/.test(document.getElementById('usSavePathText').textContent)), await ev(() => document.getElementById('usSavePathText').textContent));
  await ev(() => { document.getElementById('usVersion').value = ''; window.__calls.length = 0; window.__helperGet = null; usGet(0); }); await sleep(500);
  const g4 = await ev(() => ({ ops: window.__calls.map(c => c[0]), get: window.__helperGet, inst: (window.__calls.find(c => c[0] === 'install') || [])[1], copy: (window.__calls.find(c => c[0] === 'copyToTree') || [])[1] }));
  check('17. with all three on: it downloads to Downloads/App Updater, scans with VirusTotal, copies to the chosen folder, then installs', g4.get && g4.get.save === 'updater' && g4.ops.indexOf('vtScan') > g4.ops.indexOf('helperGet') && g4.ops.indexOf('copyToTree') > g4.ops.indexOf('vtScan') && g4.ops.indexOf('install') > g4.ops.indexOf('copyToTree') && g4.copy.tree === 'content://tree/apks' && g4.inst && g4.inst.pkg === 'com.example.maps', JSON.stringify(g4));
  await ev(() => { document.getElementById('usInstall').checked = false; document.getElementById('usVt').checked = false; document.getElementById('usSave').value = 'cache'; usSave(); });

  // ---- 17b. a flagged file is questioned on the Install button too ----
  await ev(() => { window.__vt = 'malicious'; document.getElementById('usVt').checked = true; document.getElementById('usInstall').checked = false; document.getElementById('usSave').value = 'cache'; usSave(); document.getElementById('usVersion').value = ''; window.__calls.length = 0; usGet(0); }); await sleep(500);
  await ev(() => { window.__calls.length = 0; usInstallGot(); }); await sleep(200);
  const mal = await ev(() => ({ asked: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, installs: window.__calls.filter(c => c[0] === 'install').length }));
  check('17b. with VirusTotal calling the file malicious, tapping Install asks first and installs nothing yet', mal.asked && /VirusTotal flags this file/.test(mal.title) && mal.installs === 0, JSON.stringify(mal));
  await ev(() => mpAskDone(false));
  await ev(() => { window.__vt = ''; document.getElementById('usVt').checked = false; usSave(); });

  // ---- 18. the browser check ----
  await ev(() => { window.__vers = 'browser'; document.getElementById('usSplit').checked = false; usSave(); usGet(0); }); await sleep(400);
  const g5 = await ev(() => ({ st: document.getElementById('usStatus').textContent, btns: [...document.querySelectorAll('#usGot button')].map(x => x.innerText) }));
  check('18. when the site wants a browser check, the message is shown with "Open it here, in the app" and "Open on the web"', /browser check/.test(g5.st) && g5.btns.includes('Open it here, in the app') && g5.btns.includes('Open on the web'), JSON.stringify(g5));
  await ev(() => { window.__vers = null; document.getElementById('usSplit').checked = true; usSave(); });

  // ---- 19. other sources ----
  await ev(() => { document.getElementById('usSource').value = 'play'; usSrcChanged(); });
  await ev(() => { document.getElementById('usQuery').value = 'com.example.maps'; }); await page.click('#usSearchBtn'); await sleep(200);
  const w2 = await ev(() => ({ btns: [...document.querySelectorAll('#usResults .us-app .us-acts button')].map(x => x.innerText) }));
  await ev(() => { window.__calls.length = 0; usGet(0); }); await sleep(100);
  check('19. Google Play is a page to open: its button is "Open the page" and opens the Play Store listing, nothing is downloaded', w2.btns.join() === 'Open the page' && (await ev(() => window.__opened.includes('https://play.google.com/store/apps/details?id=com.example.maps') && !window.__calls.some(c => c[0] === 'helperGet'))), JSON.stringify(w2));
  await ev(() => { document.getElementById('usSource').value = 'fdroid'; usSrcChanged(); window.__calls.length = 0; window.__helperGet = null; document.getElementById('usQuery').value = 'com.example.maps'; }); await page.click('#usSearchBtn'); await sleep(200);
  const w3 = await ev(() => [...document.querySelectorAll('#usResults .us-app .us-acts button')].map(x => x.innerText));
  await ev(() => { document.getElementById('usVersion').value = ''; usGet(0); }); await sleep(400);
  check('   F-Droid and IzzyOnDroid download inside the app: Download and Versions, and the file is asked of that source with the "latest" policy (the repository\'s own recommended build), without reading the list first', w3.join() === 'Download,Versions,Open on the web' && (await ev(() => window.__helperGet && window.__helperGet.source === 'fdroid' && window.__helperGet.policy === 'latest' && !window.__calls.some(c => c[0] === 'helperVersions'))), JSON.stringify(w3));
  await ev(() => { window.__helperGet = null; return usVersions(0); }); await sleep(300);
  await ev(() => { usGetVersion(0, 2); }); await sleep(300);
  check('   a build picked in Versions is asked for by name and build number (two builds can share a name)', await ev(() => window.__helperGet && /^2\.4\.1 \(24\)$/.test(window.__helperGet.version) && window.__helperGet.policy === 'requested'), await ev(() => JSON.stringify(window.__helperGet)));
  await ev(() => { document.getElementById('usSource').value = 'apkmirror'; usSrcChanged(); });

  // ---- 20. the Default Ask Agent searches too ----
  await ev(() => { window.aiExplainInto = async (box, system, text) => { box.textContent = 'It is com.example.found.app by Example.'; window.__agentText = text; return 'It is com.example.found.app by Example.'; }; document.getElementById('usAgent').checked = true; usSave(); document.getElementById('usQuery').value = 'found app'; });
  await page.click('#usSearchBtn'); await sleep(500);
  const ag = await ev(() => ({ box: getComputedStyle(document.getElementById('usAgentBox')).display !== 'none', txt: window.__agentText, pk: [...document.querySelectorAll('#usResults .us-pkg')].map(x => x.innerText) }));
  check('20. with the agent box ticked, the Default Ask Agent is asked and the package it names is offered as a result', ag.box && /found app/.test(ag.txt) && ag.pk.includes('com.example.found.app'), JSON.stringify(ag));
  await ev(() => { document.getElementById('usAgent').checked = false; usSave(); });

  // ---- 21. the KeyStore ----
  await ev(() => { window.__calls.length = 0; }); await page.click('#usKeyExportBtn'); await sleep(150);
  const k = await ev(() => ({ ops: window.__calls.map(c => c[0]), btns: [...document.querySelectorAll('#usKeyCard button')].map(x => x.innerText) }));
  check('21. the KeyStore card has Export KeyStore, Import KeyStore and New key; Export asks the app to save the key', k.btns.join() === 'Export KeyStore,Import KeyStore,New key' && k.ops.includes('keyExport'), JSON.stringify(k));

  // ---- 22. Settings > Feature List ----
  await ev(() => { switchView('prefs'); featureRender(); }); await sleep(200);
  const f = await ev(() => [...document.querySelectorAll('#featureList .fl-row')].map(r => r.dataset.tab + '=' + r.querySelector('.fl-name').innerText));
  check('22. the Feature List names them APK Installer/Updater and App Stores (no App Updater, no Third Party)', f.includes('installer=APK Installer/Updater') && f.includes('store=App Stores') && !f.some(x => /^updates=|App Updater|Third Party/.test(x)), JSON.stringify(f));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
