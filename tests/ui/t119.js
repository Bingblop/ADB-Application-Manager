// Morphe Patcher tab: it is in the tab bar after Connected Devices; the Apps pane (the official source to download, the apps its patches fit, Installed / Not installed, recommended versions, category and
// installed-only filters); the patch page (the APK installed here, a supported-version badge, Simple mode picks the patches that suit the version, Advanced lists app-specific and universal patches, options);
// the run (steps, live log, result with the saved APK, the install, the delete choice), a failed run with the details, the unsupported-version question; Community finder (by app / bundles, categories,
// the green Installed glow on a bundle that is added, apps on this phone); Sources, Morphe Helper (download, versions, VirusTotal), Patched APKs and the patcher settings.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const community = fs.readFileSync(fixture('morphe-community.json'), 'utf8');
  await page.addInitScript(comm => {
    const P = (name, desc, def, compat, opts) => ({ name, description: desc, default: def, compat, options: opts || [] });
    const YT = [{ package: 'com.google.android.youtube', name: 'YouTube', apkType: 'APK_REQUIRED', versions: ['20.21.37', '20.51.39'], experimental: ['21.40.161'] }];
    const OLD = [{ package: 'com.google.android.youtube', name: 'YouTube', apkType: 'APK_REQUIRED', versions: ['19.0.0'], experimental: [] }];
    const IG = [{ package: 'com.instagram.android', name: 'Instagram', apkType: '', versions: ['300.0.0'], experimental: [] }];
    window.__catalog = { ok: true, patches: [
      P('Hide ads', 'Hides ads in the feed.', true, YT), P('SponsorBlock', 'Skips sponsor segments.', true, YT),
      P('Custom branding', 'Changes the app icon and name.', false, YT, [
        { key: 'name', title: 'App name', description: 'The name\n   shown under the icon', required: false, type: 'string', default: 'YouTube Morphe', kind: '' },
        { key: 'dark', title: 'Dark icon', description: '', required: false, type: 'boolean', default: false, kind: '' },
        { key: 'size', title: 'Icon size', description: '', required: false, type: 'int', default: 48, kind: 'slider', min: 24, max: 96, step: 4 },
        { key: 'langs', title: 'Languages', description: '', required: false, type: 'list:string', default: ['en'], kind: '' },
        { key: 'theme', title: 'Theme', description: '', required: false, type: 'string', default: 'dark', kind: '', choices: [{ label: 'Dark', value: 'dark' }, { label: 'Light', value: 'light' }] },
        { key: 'logo', title: 'Logo file', description: '', required: false, type: 'string', default: null, kind: 'file' } ]),
      P('Old feature', 'Only for an old version.', true, OLD), P('Insta tweaks', 'Tweaks for Instagram.', true, IG),
      P('Change installer source', 'Makes the app think it came from the Play Store.', false, []), P('Spoof signature', 'Spoofs the signature.', false, [])] };
    window.__mp = { calls: [], downloaded: false, added: [], enabled: true, instVer: '20.21.37', failNext: false, installRetry: false, patched: [], helperFail: false, vtVerdict: 'clean', dlAccess: true, dlItems: [
      { path: '/storage/emulated/0/Download/youtube_20.21.37_apkmirror.com.apkm', name: 'youtube_20.21.37_apkmirror.com.apkm', folder: '', size: 90000000, modified: 1791000000000, format: 'apkm', pkg: 'com.google.android.youtube', versionName: '20.21.37', versionCode: 1546420000, splits: 5, abis: ['arm64-v8a'] },
      { path: '/storage/emulated/0/Download/yt-old.apk', name: 'yt-old.apk', folder: 'Browser', size: 120000000, modified: 1790000000000, format: 'apk', pkg: 'com.google.android.youtube', versionName: '19.0.0', versionCode: 1500000000, splits: 0, abis: [] }] };
    const M = window.__mp;
    const src = () => ({ id: 'morphe-official', name: 'Morphe Patches', kind: 'remote', host: 'github', repo: 'MorpheApp/morphe-patches', version: M.downloaded ? '1.46.0' : '', patchCount: M.downloaded ? 8 : -1, file: M.downloaded ? '/data/x/bundle.mpp' : '', size: M.downloaded ? 11282185 : 0, enabled: M.enabled, builtIn: true, prerelease: false, meta: null, error: '', needsNewerPatcher: false, updatedAt: 1791000000000 });
    const sources = () => [src()].concat(M.added.map(r => ({ id: 'gh-' + r.replace('/', '-'), name: r.split('/')[1], kind: 'remote', host: 'github', repo: r, version: '1.0.0', patchCount: 3, file: '/x', size: 1000, enabled: true, builtIn: false, prerelease: false, error: '' })));
    const ev = (e) => window.onMorpheEvent && window.onMorpheEvent(e);
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadSetting(k) { return window.__kv && window.__kv[k] ? window.__kv[k] : (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.google.android.youtube', name: 'YouTube', isSystem: false }, { pkg: 'com.instagram.android', name: 'Instagram', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      copyToClipboard(t) { window.__copied = t; }, shareTextFile(n, t) { window.__shared = n + ':' + t.length; return ''; }, openUrl(u) { (window.__urls = window.__urls || []).push(u); },
      morphe(tag, op, argsJson) {
        const a = JSON.parse(argsJson || '{}');
        M.calls.push(op);
        const ok = (data, ms) => setTimeout(() => window.onMorphe({ tag, ok: true, data }), ms || 5);
        const bad = (error, ms) => setTimeout(() => window.onMorphe({ tag, ok: false, error }), ms || 5);
        switch (op) {
          case 'info': return ok({ engine: true, patcher: '1.15.1', maxMemoryMb: 512 });
          case 'sources': return ok({ sources: sources() });
          case 'sourceUpdate': M.downloaded = true; return ok({ source: src() }, 20);
          case 'sourceAdd': { const m = /(github|gitlab)\.com\/([^/]+\/[^/?#]+)/.exec(a.input); if (!m) return bad('Invalid source URL'); M.added.push(m[2]); return ok({ source: { name: m[2].split('/')[1] } }); }
          case 'sourceEnable': M.enabled = a.on; return ok({});
          case 'sourceCheck': return ok({ id: a.id, newer: false, latest: '1.46.0' });
          case 'sourceRename': case 'sourcePre': case 'sourceRemove': M.calls.push(op + ':' + a.id); if (a.id !== 'morphe-official') M.added = M.added.filter(r => 'gh-' + r.replace('/', '-') !== a.id); return ok({});
          case 'catalog': { const o = {}; a.ids.forEach(id => { o[id] = id === 'morphe-official' ? window.__catalog : { ok: true, patches: [] }; }); return ok({ catalogs: o }); }
          case 'community': return ok(JSON.parse(comm));
          case 'apkInstalled': return a.pkg === 'com.google.android.youtube' ? ok({ ok: true, label: 'YouTube', versionName: M.instVer, versionCode: '1546420000', paths: ['/data/app/yt/base.apk'] }) : ok({ ok: false });
          case 'apkInspect': return ok({ ok: true, pkg: 'com.google.android.youtube', versionName: '20.51.39', versionCode: '1546999999', label: 'YouTube', format: 'apk', size: 123456789 });
          case 'pick': return ok({ files: [{ name: 'youtube-20.51.39.apk', path: '/cache/youtube-20.51.39.apk', size: 123456789 }] });
          case 'patch': {
            ok({ job: a.job });
            const J = a.job, t = (ms, e) => setTimeout(() => ev(Object.assign({ job: J, ts: 1791000000000 + ms }, e)), ms);
            t(10, { t: 'step', name: 'Preparing', state: 'RUNNING' }); t(20, { t: 'step', name: 'Preparing', state: 'OK' });
            t(30, { t: 'step', name: 'Loading', state: 'RUNNING' }); t(40, { t: 'log', level: 'INFO', text: 'Loading patches from patches-1.46.0.mpp' }); t(50, { t: 'step', name: 'Loading', state: 'OK' });
            t(60, { t: 'step', name: 'Unpacking', state: 'RUNNING' }); t(70, { t: 'app', pkg: a.pkg, versionName: a.version, versionCode: '1546420000' }); t(80, { t: 'step', name: 'Unpacking', state: 'OK' });
            t(90, { t: 'step', name: 'Patching', state: 'RUNNING' });
            if (M.failNext) {
              t(100, { t: 'patch', ok: true, name: 'Hide ads' }); t(110, { t: 'log', level: 'WARN', text: 'Skipping "x": incompatible' });
              t(120, { t: 'patch', ok: false, name: 'SponsorBlock' }); t(125, { t: 'log', level: 'ERROR', text: 'SponsorBlock failed:\napp.morphe.patcher.patch.PatchException: Fingerprint not found' });
              t(140, { t: 'step', name: 'Patching', state: 'FAIL' });
              t(150, { t: 'result', result: { success: false, error: 'A patch failed', failed: [{ name: 'SponsorBlock', error: 'app.morphe.patcher.patch.PatchException: Fingerprint not found\n  at x.y.z(Unknown)' }], applied: ['Hide ads'], steps: [{ name: 'Patching', ok: false, error: '"SponsorBlock" failed' }] } });
            } else {
              a.bundles.forEach(bd => bd.patches.forEach((n, i) => t(100 + i * 10, { t: 'patch', ok: true, name: n })));
              t(200, { t: 'step', name: 'Patching', state: 'OK' }); t(210, { t: 'step', name: 'Rebuilding', state: 'RUNNING' }); t(220, { t: 'step', name: 'Rebuilding', state: 'OK' });
              t(230, { t: 'step', name: 'Signing', state: 'RUNNING' }); t(240, { t: 'step', name: 'Signing', state: 'OK' }); t(250, { t: 'step', name: 'Saving', state: 'RUNNING' });
              const item = { id: 'p1', pkg: a.pkg, appName: a.name, versionName: a.version, fileName: a.pkg + '_' + a.version + '-patched.apk', size: 130000000, keptIn: ['the app folder', 'Downloads/Morphe Patcher'], patchedAt: 1791000000000, patches: a.bundles[0].patches, installed: false };
              M.patched = [item];
              t(260, { t: 'saved', item }); t(270, { t: 'step', name: 'Saving', state: 'OK' });
              if (a.installAfter) { t(290, { t: 'step', name: 'Installing', state: 'RUNNING' }); t(310, { t: 'install', ok: true, output: 'Success' }); t(315, { t: 'step', name: 'Installing', state: 'OK' }); if (a.deleteAfter) t(320, { t: 'deleted' }); }
              t(340, { t: 'result', result: { success: true, applied: a.bundles[0].patches, failed: [], package: a.pkg, item: item } });
            }
            return;
          }
          case 'cancel': return ok({});
          case 'patchedList': return ok({ items: M.patched });
          case 'install': if (M.installRetry && !a.uninstallFirst) { M.installRetry = false; return ok({ ok: false, retry: 'uninstall', output: 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' }); } return ok({ ok: true, output: 'Success' });
          case 'patchedShare': case 'patchedDelete': case 'shareFile': return ok({});
          case 'patchedExport': return ok({ path: '/storage/emulated/0/Download/Morphe Patcher/x.apk' });
          case 'patchedLog': return ok({ text: 'LOG line one\nLOG line two' });
          case 'helperSources': return ok({ sources: [
            { id: 'apkmirror', name: 'APKMirror', direct: false, note: 'Cloudflare challenge: open it in the browser' }, { id: 'uptodown', name: 'Uptodown', direct: true }, { id: 'apkpure', name: 'APKPure', direct: false, note: 'Blocks apps' },
            { id: 'apkcombo', name: 'APKCombo', direct: true }, { id: 'aptoide', name: 'Aptoide', direct: true }, { id: 'evozi', name: 'Evozi', direct: false }, { id: 'mi9', name: 'Mi9', direct: false },
            { id: 'apkdownloader', name: 'APK Downloader', direct: false }, { id: 'aurora', name: 'Aurora', direct: false }, { id: 'play', name: 'Play', direct: false }] });
          case 'helperVersions': return ok({ ok: true, pkg: a.pkg, name: 'YouTube', versions: [{ version: '20.51.39', format: 'apk', size: 123456789, url: 'https://x/y.apk' }, { version: '20.21.37', format: 'apks', abi: 'arm64-v8a', size: 99000000, url: 'https://x/z.apks' }] });
          case 'helperGet': case 'helperFast': if (M.helperBrowseUrl) return setTimeout(() => window.onMorphe({ tag, ok: false, error: 'APKMirror: blocked by its browser check. Open it in the browser.', browse: M.helperBrowseUrl }), 5); if (M.helperFail && !(op === 'helperFast' && M.fastOk)) return bad('Uptodown changed its page format, open it in the browser'); return ok({ ok: true, path: '/cache/h/youtube_20.21.37.apk', fileName: 'youtube_20.21.37.apk', pkg: a.pkg, versionName: a.version || '20.51.39', versionCode: 1546420000, format: 'apk', size: 123456789, sha256: 'ab'.repeat(32), tried: [] }, 30);
          case 'helperDownloads': return ok(M.dlAccess ? { ok: true, access: true, folder: '/storage/emulated/0/Download', items: a.pkg === 'com.google.android.youtube' ? M.dlItems : [], scanned: 4, other: a.pkg === 'com.google.android.youtube' ? 1 : 4, unreadable: 0 } : { ok: true, access: false, items: [] });
          case 'helperAdopt': { const x = M.dlItems.find(i => i.path === a.path); if (!x) return bad('that file is not one this app may read'); return ok({ ok: true, path: '/cache/h/' + x.name, fileName: x.name, pkg: x.pkg, versionName: x.versionName, versionCode: x.versionCode, format: x.format, splits: x.splits, size: x.size, sha256: 'ef'.repeat(32), source: 'downloads' }); }
          case 'helperBrowse': M.browseArgs = a; return ok({ url: a.url || 'https://www.apkmirror.com/?s=' + a.pkg });
          case 'helperManual': return ok({ url: 'https://www.apkmirror.com/?s=' + a.pkg });
          case 'vtQuota': return ok({ perMinuteUsed: 1, perMinuteLimit: 4, perDayUsed: 12, perDayLimit: 500 });
          case 'vtValidate': return a.key === 'good' ? ok({}) : bad('VirusTotal refused the key (401)');
          case 'vtScan': return ok({ found: true, cached: true, verdict: M.vtVerdict, malicious: M.vtVerdict === 'clean' ? 0 : 7, suspicious: 0, total: 70, permalink: 'https://www.virustotal.com/gui/file/abc' }, 20);
          case 'keyExport': return ok({ path: '/storage/emulated/0/Download/Morphe Patcher/morphe.keystore' });
          default: return ok({});
        }
      }
    };
  }, community);
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const text = sel => page.locator(sel).first().innerText();
  const calls = () => ev(() => window.__mp.calls.slice());
  const show = async sel => ev(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none' && !!e.offsetParent; }, sel);

  // ---- the tab ----
  const tabs = await ev(() => tabsShown());
  check('the tab bar has Morphe Patcher after Connected Devices', tabs.indexOf('morphe') === tabs.indexOf('devices') + 1 && tabs.indexOf('morphe') > 0, tabs.join());
  check('its label is on two lines', (await ev(() => tabDef('morphe').label)) === 'Morphe\nPatcher');
  await ev(() => { isPrivilegedActive = true; switchView('morphe'); }); await wait(600);
  check('the view is open and asked the app about the engine', (await show('#view-morphe')) && (await calls()).includes('info') && (await calls()).includes('sources'));
  check('the card names the project and credits Morphe, Morphe Manager and Helper for Morphe', await ev(() => { const t = document.getElementById('mpTop').innerText; return /Morphe Patcher/.test(t) && /MorpheApp/.test(t) && /Morphe Manager/.test(t) && /Helper for Morphe/.test(t) && /rushiranpise/.test(t) && /GPL-3\.0/.test(t); }));
  check('it has a help "?"', await ev(() => !!document.querySelector('#view-morphe .help-q') && document.querySelector('#view-morphe .help-q').dataset.help === 'tab-morphe'));
  check('the four buttons: Sources, Morphe Helper, Patched APKs, Settings', await ev(() => Array.from(document.querySelectorAll('#mpTop .mp-top-btns button')).map(x => x.innerText.trim()).join('|')) === 'Sources|Morphe Helper|Patched APKs|Settings');
  check('the engine warning is hidden when the engine is there', !(await show('#mpEngineWarn')));
  check('the Apps pane asks to download the official source first', /Get the Morphe patches/.test(await text('#mpAppList')) && /Download Morphe Patches/.test(await text('#mpAppList')));
  await page.screenshot({ path: 'morphe_empty.png' });

  // an icon that has an address is the image over its letter (the letter used to sit beside it), and the letter goes away when the image is there
  const ico = await ev(async () => {
    const d = document.createElement('div'); d.className = 'mp-ico'; d.id = 'icoT';
    d.innerHTML = mpIcon('no.such.pkg', 'Gboard', 'data:image/gif;base64,R0lGODlhAQABAAAAACwAAAAAAQABAAA=');
    document.getElementById('mpAppList').appendChild(d);
    await new Promise(r => setTimeout(r, 200));
    const i = d.querySelector('img'), l = d.querySelector('span');
    const out = { pos: i && getComputedStyle(i).position, hasLetter: !!l, letterHidden: !!l && getComputedStyle(l).visibility === 'hidden', imgW: i && Math.round(i.getBoundingClientRect().width), boxW: Math.round(d.getBoundingClientRect().width) };
    d.remove(); return out;
  });
  check('an icon with an address is the image over its letter, and the letter goes when the image is there', ico.pos === 'absolute' && ico.hasLetter && ico.letterHidden && ico.imgW === ico.boxW, JSON.stringify(ico));
  const ico2 = await ev(() => { const d = document.createElement('div'); d.innerHTML = mpIcon('no.such.pkg', 'Gboard', ''); return d.innerText; });
  check('an icon without an address is its letter', ico2 === 'G', ico2);

  // ---- download the official source ----
  await page.click('#mpAppList .mode-action-btn.primary'); await wait(500);
  check('Download asks the app to download it and then reads its patches', (await calls()).includes('sourceUpdate') && (await calls()).includes('catalog'));
  let rows = await ev(() => Array.from(document.querySelectorAll('#mpAppList .mp-card')).map(c => c.querySelector('.mp-name').innerText + ':' + Array.from(c.querySelectorAll('.mp-chip')).map(x => x.innerText).join('/')));
  check('YouTube and Instagram are listed (both on the phone: by name), with their patch counts', rows.length === 2 && rows[1].startsWith('YouTube:Installed/4 patches') && rows[0].startsWith('Instagram:Installed/1 patch'), JSON.stringify(rows));
  check('the recommended version is shown (the newest of the most patched)', /20\.51\.39/.test(rows[1]), rows[1]);
  check('a count of apps is written', /2 apps/.test(await text('#mpAppCount')));
  await ev(() => { document.getElementById('mpAppSearch').value = 'insta'; mpAppsRender(); });
  check('search narrows the list', (await ev(() => document.querySelectorAll('#mpAppList .mp-card').length)) === 1);
  await ev(() => { document.getElementById('mpAppSearch').value = ''; mpAppsRender(); });
  check('the category list has Other (no community data yet) ', await ev(() => Array.from(document.querySelectorAll('#mpAppCat option')).map(o => o.value).join('|')) === '|Other');
  const igApp = await ev(() => { const x = allApps.find(a => a.pkg === 'com.instagram.android'); allApps = allApps.filter(a => a.pkg !== 'com.instagram.android'); document.getElementById('mpAppOnlyInstalled').checked = true; mpAppsRender(); return x; });
  check('"only installed" keeps just YouTube', (await ev(() => Array.from(document.querySelectorAll('#mpAppList .mp-card .mp-name')).map(x => x.innerText).join())) === 'YouTube');
  await ev(() => { document.getElementById('mpAppOnlyInstalled').checked = false; mpAppsRender(); });
  check('without it the other app says Not installed', /Not installed/.test(await text('#mpAppList .mp-card:nth-child(2)')));
  await ev(x => { allApps.push(x); }, igApp);
  await page.screenshot({ path: 'morphe_apps.png' });

  // ---- the patch page: Simple ----
  await page.click('#mpAppList .mp-card'); await wait(400);
  check('the patch page opens for YouTube with the installed version', (await show('#mpPage')) && (await text('#mpPageTitle')) === 'YouTube' && /Installed on this phone/.test(await text('#mpTarget')) && /20\.21\.37/.test(await text('#mpTarget')));
  check('the version is a supported one (green)', await ev(() => !!document.querySelector('#mpTarget .mp-chip.glow') && /Supported version/.test(document.querySelector('#mpTarget .mp-chip.glow').innerText)));
  check('it lists the versions the patches were made for', /20\.51\.39, 20\.21\.37/.test(await text('#mpTarget')) && /21\.40\.161/.test(await text('#mpTarget')));
  check('Simple mode is selected and shows only what suits the version (not the old-version patch, not the off-by-default ones)', await ev(() => document.querySelector('#mpModeSeg button.active').dataset.m) === 'simple' && /Hide ads, SponsorBlock/.test(await text('#mpPatchList')) && !/Old feature/.test(await text('#mpPatchList')) && !/Custom branding/.test(await text('#mpPatchList')), await text('#mpPatchList'));
  check('Install when finished is on, Delete the APK is off', await ev(() => document.getElementById('mpInstallAfter').checked && !document.getElementById('mpDeleteAfter').checked));
  await page.screenshot({ path: 'morphe_simple.png' });

  // ---- Advanced ----
  await page.click('#mpModeSeg button[data-m="advanced"]'); await wait(150);
  const groups = await ev(() => Array.from(document.querySelectorAll('#mpPatchList .mp-group')).map(g => g.innerText.replace(/\s+/g, ' ')));
  check('Advanced lists app-specific and universal patches apart', groups.length === 2 && /App-specific.*4/i.test(groups[0]) && /Universal.*2/i.test(groups[1]), JSON.stringify(groups));
  check('the defaults are ticked (Hide ads, SponsorBlock) and the old-version one is not', await ev(() => { const on = Array.from(document.querySelectorAll('#mpPatchList .mp-patch')).filter(p => p.querySelector('input').checked).map(p => p.querySelector('.mp-name').innerText); return on.join() === 'Hide ads,SponsorBlock'; }));
  check('the old-version patch is flagged', /Not for this version/.test(await text('#mpPatchList')));
  await page.click('#mpPatchList .mp-patch:has(.mp-name:text-is("Custom branding")) input'); await wait(100);
  check('ticking a patch counts it', /3 selected/.test(await text('#mpPatchList')));
  await page.click('#mpPatchList .mp-patch:has(.mp-name:text-is("Custom branding")) .mp-gear'); await wait(200);
  check('the options sheet shows every kind of option', await ev(() => { const b = document.getElementById('mpSheetBody'); return !!b.querySelector('input[type=range]') && !!b.querySelector('input[type=checkbox]') && !!b.querySelector('select') && b.querySelectorAll('input.modal-text-input').length >= 3; }));
  await page.screenshot({ path: 'morphe_options.png' });
  await ev(() => { const b = document.getElementById('mpSheetBody'); b.querySelector('input.modal-text-input').value = 'My Tube'; b.querySelector('input[type=checkbox]').checked = true; });
  await page.click('#mpSheetBtns button:first-child'); await wait(150);
  check('saving keeps the options of that patch', await ev(() => { const o = mp.opts['morphe-official']['Custom branding']; return o.name === 'My Tube' && o.dark === true && o.size === 48 && o.theme === 'dark'; }), await ev(() => JSON.stringify(mp.opts)));
  await page.click('#mpPatchList .mp-row .mp-act:nth-child(2)'); await wait(100);
  check('None clears the selection', /0 selected/.test(await text('#mpPatchList')));
  await page.click('#mpPatchList .mp-row .mp-act:nth-child(3)'); await wait(100);
  check('Defaults brings the defaults back', /2 selected/.test(await text('#mpPatchList')));
  await page.click('#mpPatchList .mp-row .mp-act:nth-child(1)'); await wait(100);
  check('All ticks what applies to this version (not the old-version patch)', /5 selected/.test(await text('#mpPatchList')), await text('#mpPatchList'));
  await page.click('#mpPatchList .mp-row .mp-act:nth-child(3)'); await wait(100);

  // ---- run: success ----
  await page.click('#mpGo');
  await page.waitForFunction(() => /is patched/.test(document.getElementById('mpResult').innerText), null, { timeout: 15000 });
  check('the run view is shown with the steps and the log', (await show('#mpRun')) && !(await show('#mpPrep')) && (await ev(() => document.querySelectorAll('#mpSteps .mp-step').length)) === 8);
  check('the patch call carried the job: package, version, the patches and the install choice', await ev(() => { const r = mp.run.req; return r.pkg === 'com.google.android.youtube' && r.version === '20.21.37' && r.bundles[0].patches.join() === 'Hide ads,SponsorBlock' && r.installAfter === true && r.deleteAfter === false && r.from === 'installed'; }));
  check('every step finished', await ev(() => Array.from(document.querySelectorAll('#mpSteps .mp-step')).every(s => s.classList.contains('ok'))), await ev(() => Array.from(document.querySelectorAll('#mpSteps .mp-step')).map(s => s.className).join('|')));
  check('the log reads like logcat (time, level, tag)', await ev(() => /^\d\d:\d\d:\d\d\.\d{3} I\/Morphe: Loading patches from patches-1\.46\.0\.mpp/m.test(document.getElementById('mpLog').innerText)));
  check('it ends in a result with the patched app, the saved file and the install', await ev(() => { const r = document.getElementById('mpResult').innerText; return /YouTube is patched/.test(r) && /2 patches applied/.test(r) && /Installed\./.test(r) && /Downloads\/Morphe Patcher/.test(r); }), await text('#mpResult'));
  check('Cancel is gone, Share and Patched APKs are there', !(await show('#mpStop')) && await ev(() => /Share/.test(document.getElementById('mpResult').innerText) && /Patched APKs/.test(document.getElementById('mpResult').innerText)));
  check('the count of Patched APKs follows', /\(1\)/.test(await text('#mpTop .mp-top-btns button:nth-child(3)')));
  await page.screenshot({ path: 'morphe_done.png' });

  // ---- run: failure ----
  await ev(() => { mpBackToPatches(); window.__mp.failNext = true; });
  await page.click('#mpGo');
  await page.waitForFunction(() => /Patching failed/.test(document.getElementById('mpResult').innerText), null, { timeout: 15000 });
  const bad = await text('#mpResult');
  check('a failed run says so, names the patch and shows the details', /Patching failed/.test(bad) && /SponsorBlock/.test(bad) && /Fingerprint not found/.test(bad) && /does not fit|could not be applied|do not support/.test(bad), bad);
  check('the log has the warning and the error lines in color', await ev(() => !!document.querySelector('#mpLog .l-w') && !!document.querySelector('#mpLog .l-e')));
  await ev(() => { document.getElementById('mpLogLevel').value = 'error'; mpLogRender(); });
  check('the level filter keeps only the errors', await ev(() => document.querySelectorAll('#mpLog > div').length > 0 && Array.from(document.querySelectorAll('#mpLog > div')).every(d => d.classList.contains('l-e'))));
  await ev(() => { document.getElementById('mpLogLevel').value = 'all'; mpLogRender(); });
  await page.click('#mpResult .mode-action-btn:nth-child(2)'); await wait(100);
  check('Copy the log copies it', await ev(() => /SponsorBlock failed/.test(window.__copied || '')));
  await page.screenshot({ path: 'morphe_failed.png' });
  // patches that failed only because the one they need failed: the first error leads, the others are counted and folded
  await ev(() => mpResultRender({ success: false, error: 'Patching failed', failed: [
    { name: 'Gboard extension', error: 'java.lang.NoSuchMethodException: getPatchClasses$morphe_patcher []\n  at x.y.z' },
    { name: 'Hide ads', error: '"Hide ads" depends on "Gboard extension", which raised an exception' },
    { name: 'Dark theme', error: '"Dark theme" depends on "Gboard extension", which raised an exception' }] }));
  const chain = await ev(() => ({ t: document.getElementById('mpResult').innerText, shown: Array.from(document.querySelectorAll('#mpResult .mp-trace')).filter(e => getComputedStyle(e).display !== 'none').map(e => e.innerText) }));
  check('a chain of failures leads with the first error and counts the others', /Gboard extension/i.test(chain.t) && /NoSuchMethodException/.test(chain.shown[0] || '') && /2 more failed only because a patch they need failed/i.test(chain.t) && /different Morphe version/.test(chain.t) && chain.shown.length === 1, JSON.stringify(chain));
  check('the dependent ones are named only after Show, with the advice to fix the first', await ev(() => { const e = document.getElementById('mpTraceDeps'); const hidden = getComputedStyle(e).display === 'none'; mpToggleTrace('Deps'); return hidden && getComputedStyle(e).display !== 'none' && /Hide ads, Dark theme/.test(e.innerText) && /Fix the first failure/.test(e.innerText); }));
  await ev(() => mpResultRender({ success: false, error: 'x', failed: [{ name: 'A', error: 'boom' }, { name: 'B', error: 'boom too' }] }));
  check('independent failures are all listed as before', await ev(() => !/only because/.test(document.getElementById('mpResult').innerText) && /boom too/.test(document.getElementById('mpResult').innerHTML)));

  // ---- an unsupported version ----
  await ev(() => { window.__mp.failNext = false; window.__mp.instVer = '19.9.9'; });
  await ev(() => { mpPageBack(); }); await wait(100);
  await ev(() => { mpOpenApp('com.google.android.youtube'); }); await wait(400);
  check('another version is flagged on the page', /Other version/.test(await text('#mpTarget')) && /not one the patches list/.test(await text('#mpTarget')));
  await ev(() => { mpSetMode('advanced'); }); await wait(100);
  await page.click('#mpPatchList .mp-patch:has(.mp-name:text-is("Old feature")) input'); await wait(80);
  await page.click('#mpGo'); await wait(300);
  check('patching a patch that is not for this version asks first', await ev(() => document.getElementById('mpSheet').classList.contains('show') && /not supported/.test(document.getElementById('mpSheetTitle').innerText) && document.querySelectorAll('#mpSheetBtns button').length === 4));
  await page.screenshot({ path: 'morphe_version.png' });
  await page.click('#mpSheetBtns button:nth-child(4)'); await wait(100);
  check('Cancel keeps you on the patch page', (await show('#mpPrep')) && !(await show('#mpRun')));
  await ev(() => { mpPageBack(); window.__mp.instVer = '20.21.37'; });

  // ---- the checkboxes ----
  await ev(() => { mpOpenApp('com.google.android.youtube'); }); await wait(300);
  await ev(() => { const i = document.getElementById('mpInstallAfter'); i.checked = false; i.dispatchEvent(new Event('change')); });
  check('Install off makes the delete box unavailable', await ev(() => document.getElementById('mpDeleteAfter').disabled === true));
  await ev(() => { const i = document.getElementById('mpInstallAfter'); i.checked = true; i.dispatchEvent(new Event('change')); const d = document.getElementById('mpDeleteAfter'); d.checked = true; d.dispatchEvent(new Event('change')); });
  check('the choices are remembered with the settings', await ev(() => { const s = JSON.parse(window.__kv.morphe_cfg); return s.installAfter === true && s.deleteAfter === true; }));
  await ev(() => { mpSetMode('simple'); });
  await page.click('#mpGo');
  await page.waitForFunction(() => /is patched/.test(document.getElementById('mpResult').innerText), null, { timeout: 15000 });
  check('with delete on the log says the APK was deleted after the install', await ev(() => /deleted after the install/.test(document.getElementById('mpLog').innerText)));
  await ev(() => { mpBackToPatches(); mpPageBack(); const d = document.getElementById('mpDeleteAfter'); mp.cfg.deleteAfter = false; mpCfgSave(); });

  // ---- Community ----
  await page.click('#mpTabs .mp-tab[data-sub="community"]'); await wait(500);
  check('the Community tab loaded the finder data', (await calls()).includes('community') && /bundles/.test(await text('#mpComStatus')));
  let comRows = await ev(() => document.querySelectorAll('#mpComList .mp-card').length);
  check('apps are listed with their bundle counts', comRows > 20 && /bundles?/.test(await text('#mpComList .mp-card')));
  check('categories are a dropdown with the finder\'s categories', await ev(() => document.querySelectorAll('#mpComCat option').length > 3 && Array.from(document.querySelectorAll('#mpComCat option')).some(o => /Social/.test(o.value))));
  check('featured apps are shown on top', await ev(() => document.querySelectorAll('#mpComFeat .mp-feat-item').length > 0));
  await ev(() => { document.getElementById('mpComCat').value = Array.from(document.querySelectorAll('#mpComCat option')).find(o => /Social/.test(o.value)).value; mpComRender(); });
  const filtered = await ev(() => document.querySelectorAll('#mpComList .mp-card').length);
  check('a category narrows the list', filtered > 0 && filtered < comRows, filtered + ' of ' + comRows);
  await ev(() => { document.getElementById('mpComCat').value = ''; document.getElementById('mpComOnlyInstalled').checked = true; mpComRender(); });
  check('"installed apps only" keeps the apps on this phone (Instagram)', await ev(() => Array.from(document.querySelectorAll('#mpComList .mp-card .mp-name')).map(x => x.innerText).join()) === 'Instagram', await ev(() => Array.from(document.querySelectorAll('#mpComList .mp-card .mp-name')).map(x => x.innerText).join()));
  check('it says On this phone', /On this phone/.test(await text('#mpComList .mp-card')));
  await ev(() => { document.getElementById('mpComOnlyInstalled').checked = false; mpComSetView('bundles'); });
  check('the Bundles view lists bundles with stars, patches and an Add button', await ev(() => { const c = document.querySelector('#mpComList .mp-card'); return !!c && /patches/.test(c.innerText) && / stars/.test(c.innerText) && !!c.querySelector('.mp-act'); }));
  check('no bundle is Installed yet', await ev(() => !document.querySelector('#mpComList .mp-chip.glow')));
  const firstRepo = await ev(() => mp.community.bundles[0].repo);
  await ev(() => { mpComAdd(mp.community.bundles[0].repo); }); await wait(200);
  check('adding asks first, and warns to only add sources you trust', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /Only add sources you trust/.test(document.getElementById('mpAskText').innerText)));
  await page.click('#mpAskOk'); await wait(500);
  check('the bundle was added through the app', (await calls()).includes('sourceAdd') && (await ev(() => window.__mp.added.length)) === 1);
  check('and now it shows Installed in a green glow', await ev(() => { const c = document.querySelector('#mpComList .mp-chip.glow'); return !!c && /Installed/.test(c.innerText) && /rgb\(61, 220, 132\)/.test(getComputedStyle(c).color) && /rgba\(61, 220, 132/.test(getComputedStyle(c).boxShadow); }));
  await page.screenshot({ path: 'morphe_community.png' });
  await ev(() => { mpComBundle(mp.community.bundles[1].repo); }); await wait(150);
  check('a bundle opens with its description, apps and patches', await ev(() => /apps/i.test(document.getElementById('mpSheetBody').innerText) && /patches/i.test(document.getElementById('mpSheetBody').innerText) && /Add this source/.test(document.getElementById('mpSheetBtns').innerText)));
  await ev(() => mpSheetClose());
  await ev(() => { mpComSetView('apps'); mpComApp('com.instagram.android'); }); await wait(150);
  check('an app opens with the bundles that patch it', await ev(() => /bundles that patch it/i.test(document.getElementById('mpSheetBody').innerText) && document.querySelectorAll('#mpSheetBody .mp-card').length > 1));
  await ev(() => mpSheetClose());

  // ---- Sources ----
  await ev(() => { mpSub('apps'); mpSourcesOpen(); }); await wait(200);
  check('the sources sheet lists the official one, Pre-installed and with its version', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /Morphe Patches/.test(t) && /Pre-installed/.test(t) && /1\.46\.0/.test(t) && /8 patches/.test(t); }));
  check('the added one is listed and can be deleted', await ev(() => document.querySelectorAll('#mpSheetBody .color-card').length === 2 && /Delete/.test(document.querySelectorAll('#mpSheetBody .color-card')[1].innerText)));
  check('the pre-installed one cannot be deleted or renamed', await ev(() => !/Delete|Rename/.test(document.querySelectorAll('#mpSheetBody .color-card')[0].innerText)));
  await page.screenshot({ path: 'morphe_sources.png' });
  await ev(() => mpSourceAddOpen()); await wait(100);
  await ev(() => { document.getElementById('mpAddUrl').value = 'http://github.com/a/b'; mpAddCheck(); });
  check('an http address is refused with the Manager\'s wording', /must start with https/.test(await text('#mpAddMsg')));
  await ev(() => { document.getElementById('mpAddUrl').value = 'example.com/page'; mpAddCheck(); });
  check('an address that is not a repository or a .json is refused', /GitHub or GitLab repository, or to a \.json/.test(await text('#mpAddMsg')));
  await ev(() => { document.getElementById('mpAddUrl').value = 'https://morphe.software/add-source?github=owner/repo&name=X'; mpAddCheck(); });
  check('the deep link of the finder is understood', /can be used \(github\)/.test(await text('#mpAddMsg')));
  await ev(() => { document.getElementById('mpAddUrl').value = 'gitlab.com/owner/repo'; mpAddCheck(); });
  check('a GitLab repository is fine', /can be used \(gitlab\)/.test(await text('#mpAddMsg')));
  await ev(() => mpSheetClose());
  await ev(() => mpSourceToggle('morphe-official')); await wait(300);
  check('turning the official source off empties the app list', /No patches to show|Get the Morphe patches/.test(await text('#mpAppList')) || (await ev(() => document.querySelectorAll('#mpAppList .mp-card').length)) === 0);
  await ev(() => mpSourceToggle('morphe-official')); await wait(300);
  await ev(() => mpSheetClose());

  // ---- Patched APKs ----
  await page.click('#mpTop .mp-top-btns button:nth-child(3)'); await wait(300);
  check('Patched APKs lists the run with its patches and Install / Share / Save / Log / Delete', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /YouTube/.test(t) && /Hide ads, SponsorBlock/.test(t) && ['Install', 'Share', 'Save to Downloads', 'Log', 'Delete'].every(w => t.includes(w)); }));
  await ev(() => { window.__mp.installRetry = true; mpPatchedInstall('p1'); }); await wait(300);
  check('an install Android refuses (other key) offers to uninstall the installed copy first', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /Uninstall the installed copy first/.test(document.getElementById('mpAskTitle').innerText) && /removes its data/.test(document.getElementById('mpAskText').innerText)));
  await page.click('#mpAskOk'); await wait(300);
  check('after OK it installs with the uninstall', (await calls()).filter(c => c === 'install').length >= 2);
  await ev(() => mpPatchedLog('p1')); await wait(200);
  check('the log of a run is shown', /LOG line one/.test(await text('#mpSheetBody')));
  await ev(() => mpSheetClose());

  // ---- Morphe Helper ----
  await page.click('#mpTop .mp-top-btns button:nth-child(2)'); await wait(300);
  check('Morphe Helper opens on Download with the flows of Helper for Morphe', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return ['Fast mode', 'This version', 'Newest', 'All versions', 'Open the site', 'Find in Downloads', 'Pick a file'].every(w => t.includes(w)); }));
  await ev(() => { document.getElementById('mpHPkg').value = 'com.google.android.youtube'; document.getElementById('mpHVer').value = '20.21.37'; });
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(4)'); await wait(300);
  check('All versions lists the versions with their format and a Download button', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /20\.51\.39/.test(t) && /apks arm64-v8a/.test(t) && document.querySelectorAll('#mpSheetBody .mp-patch .mp-act').length === 2; }));
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(2)'); await wait(500);
  check('This version downloads and shows the file, its SHA-256 and the install / patch buttons', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /Downloaded/.test(t) && /SHA-256 abab/.test(t) && /Install this APK/.test(t) && /Patch this file/.test(t); }));
  check('VirusTotal is off, so there is no scan button', !/Scan with VirusTotal/.test(await text('#mpSheetBody')));
  await page.screenshot({ path: 'morphe_helper.png' });
  await page.click('#mpSheetBody .mp-tab:nth-child(2)'); await wait(150);
  check('Helper settings: the ten sources, connection, save, fast mode, VirusTotal and the credit', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return ['APKMirror', 'Uptodown', 'APKPure', 'APKCombo', 'Aptoide', 'Evozi', 'Mi9', 'APK Downloader', 'Aurora', 'Play'].every(w => t.includes(w)) && /Connection/.test(t) && /Save downloads/.test(t) && /Fast mode/.test(t) && /VirusTotal/.test(t) && /Helper for Morphe/.test(t) && /rushiranpise/.test(t); }));
  await page.screenshot({ path: 'morphe_helper_settings.png' });
  await ev(() => { const c = document.querySelector('#mpSheetBody .mp-sw input[onchange*="hVt=this"]'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(200);
  check('turning VirusTotal on shows the key box, the mode and the quota bars', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return !!document.getElementById('mpHKey') && /When to scan/.test(t) && /This minute: 1 of 4/.test(t) && /Today: 12 of 500/.test(t); }));
  check('the key is a password box', await ev(() => document.getElementById('mpHKey').type === 'password'));
  await ev(() => { document.getElementById('mpHKey').value = 'bad'; mpHKeyTest(); }); await wait(200);
  check('a refused key says so', await ev(() => /refused the key/.test(document.getElementById('toastMsg').innerText)));
  await ev(() => { document.getElementById('mpHKey').value = 'good'; mpHKeyTest(); }); await wait(200);
  check('a good key is accepted and saved', await ev(() => /key works/.test(document.getElementById('toastMsg').innerText) && JSON.parse(window.__kv.morphe_cfg).hVtKey === 'good'));
  await ev(() => { mp.cfg.hVtMode = 'ask'; mpCfgSave(); mpHTab('get'); });
  await ev(() => { mp.h.got = { path: '/cache/h/y.apk', pkg: 'com.google.android.youtube', versionName: '20.21.37', versionCode: 1, format: 'apk', size: 1000, sha256: 'cd'.repeat(32) }; mpHelperRender(); });
  check('with a key and "ask" a Scan button appears', /Scan with VirusTotal/.test(await text('#mpSheetBody')));
  await page.click('#mpSheetBody .mp-result .mode-action-btn:has-text("Scan")'); await wait(250);
  check('a clean file is shown as clean with the engine counts and a link', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /VirusTotal: clean/.test(t) && /0 malicious, 0 suspicious of 70/.test(t) && /cached report/.test(t) && /Open in VirusTotal/.test(t); }));
  await ev(() => { window.__mp.vtVerdict = 'malicious'; mp.h.vt = null; mpHScan(); }); await wait(250);
  check('a flagged file is shown in red', await ev(() => !!document.querySelector('#mpSheetBody .mp-chip.bad') && /malicious/.test(document.querySelector('#mpSheetBody .mp-chip.bad').innerText)));
  await ev(() => { window.__mp.helperFail = true; mp.h.got = null; mp.h.vt = null; mpHelperRender(); });
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(1)'); await wait(300);
  check('a source that changed its page says what to do', /changed its page format/.test(await text('#mpHMsg')));
  // the setting that goes on with the next source by itself
  check('Helper settings have "Try the other sources automatically", off by default', await ev(() => { mpHTab('set'); const t = document.getElementById('mpSheetBody').innerText; mpHTab('get'); return /Try the other sources automatically/.test(t) && mp.cfg.hCycle === false; }));
  await ev(() => { window.__mp.fastOk = true; mp.cfg.hCycle = true; mp.h.got = null; mp.h.err = false; mp.h.msg = ''; mpHelperRender(); });
  const c0 = (await calls()).length;
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(2)'); await wait(500);
  const cyc = (await calls()).slice(c0);
  check('with it on, a source that fails hands over to the others (Fast mode, the chosen one first) and the file is delivered', cyc.includes('helperGet') && cyc.includes('helperFast') && await ev(() => !!mp.h.got && !mp.h.err), cyc.join());
  await ev(() => { mp.cfg.hCycle = false; mp.h.got = null; mp.h.err = false; mp.h.msg = ''; mpHelperRender(); });
  const c1 = (await calls()).length;
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(2)'); await wait(400);
  check('with it off, a failure is just reported', !(await calls()).slice(c1).includes('helperFast') && await ev(() => !mp.h.got && mp.h.err));
  await ev(() => { window.__mp.fastOk = false; });

  // ---- the browser inside the app ----
  await ev(() => { window.__mp.helperFail = false; mp.h.got = null; mp.h.err = false; mp.h.msg = ''; mp.h.browse = ''; mp.cfg.hBrowser = 'inapp'; document.getElementById('mpHPkg').value = 'com.google.android.inputmethod.latin'; mpHRead(); mpHelperRender(); });
  await page.click('#mpSheetBody .mp-hflows .mp-act:has-text("Open the site")'); await wait(200);
  check('Open the site opens the browser in the app (not the phone\'s browser) with the package and the save choice', (await calls()).includes('helperBrowse') && await ev(() => window.__mp.browseArgs.pkg === 'com.google.android.inputmethod.latin' && window.__mp.browseArgs.save === 'cache' && !(window.__urls || []).length));
  check('it tells what to do there', /download button/.test(await text('#mpHMsg')));
  await ev(() => window.onMorpheEvent({ t: 'hb', k: 'status', text: 'Downloading 3 of 90 MB (3%)', pct: 3 })); await wait(50);
  check('a download in the browser shows its progress in the sheet', /Downloading 3 of 90 MB/.test(await text('#mpHMsg')));
  await ev(() => window.onMorpheEvent({ t: 'hb', k: 'done', ok: false, name: 'page.apkm', error: 'there is no AndroidManifest.xml' })); await wait(50);
  check('a file that is not an app is said so, with what to do', /page\.apkm was saved, but it is not an app file/.test(await text('#mpHMsg')) && !(await ev(() => !!mp.h.got)));
  await ev(() => window.onMorpheEvent({ t: 'hb', k: 'done', ok: true, name: 'gboard.apkm', info: { ok: true, path: '/cache/h/gboard.apkm', fileName: 'gboard.apkm', pkg: 'com.google.android.inputmethod.latin', versionName: '18.0.3.954559732-release-arm64-v8a', versionCode: 954559732, format: 'apkm', splits: 4, size: 90000000, sha256: 'ab'.repeat(32), source: 'browser' } })); await wait(100);
  check('a good download becomes the result: the bundle with its parts, ready to install or patch', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /APKM, 4 parts/.test(t) && /saved from the browser/.test(t) && /Install all parts/.test(t) && /Patch this file/.test(t); }));
  await ev(() => { window.__mp.helperBrowseUrl = 'https://www.apkmirror.com/apk/google-inc/gboard/'; mp.h.got = null; mp.h.msg = ''; mpHelperRender(); });
  await page.click('#mpSheetBody .mp-hflows .mp-act:nth-child(2)'); await wait(300);
  check('a source that needs a browser offers to open its page here', /Open it here, in the app/.test(await text('#mpSheetBody')) && /browser check/.test(await text('#mpHMsg')));
  await page.click('#mpSheetBody button:has-text("Open it here, in the app")'); await wait(200);
  check('and that opens exactly the page the source named', await ev(() => window.__mp.browseArgs.url === 'https://www.apkmirror.com/apk/google-inc/gboard/'));
  await ev(() => { window.__mp.helperBrowseUrl = ''; mp.cfg.hBrowser = 'external'; mp.h.browse = ''; mpHelperRender(); });
  const urls0 = await ev(() => (window.__urls || []).length);
  await page.click('#mpSheetBody .mp-hflows .mp-act:has-text("Open the site")'); await wait(250);
  check('with "The phone\'s browser" chosen in Settings it opens there as before', await ev(n => (window.__urls || []).length === n + 1, urls0));
  check('Helper settings offer the choice, in the app by default', await ev(() => { mpHTab('set'); const t = document.getElementById('mpSheetBody').innerText; const ok = /Open the sources in/.test(t) && MP_DEFAULTS.hBrowser === 'inapp'; mpHTab('get'); return ok; }));
  await ev(() => { mp.cfg.hBrowser = 'inapp'; mp.h.got = null; mp.h.msg = ''; mpHelperRender(); document.getElementById('mpHPkg').value = 'com.google.android.youtube'; mpHRead(); });

  // ---- bundles (APKM / APKS / XAPK) saved by the browser are found in Downloads ----
  await ev(() => { window.__mp.helperFail = false; mp.h.got = null; mp.h.err = false; mp.h.msg = ''; mpHelperRender(); });
  await page.click('#mpSheetBody .mp-hflows .mp-act:has-text("Find in Downloads")'); await wait(300);
  check('Find in Downloads asks the app and lists the files of this package, newest first, with format and parts', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /In Downloads/i.test(t) && /2 found/i.test(t) && /APKM, 5 parts/.test(t) && /youtube_20\.21\.37_apkmirror\.com\.apkm/.test(t) && t.indexOf('APKM, 5 parts') < t.indexOf('19.0.0'); }));
  check('it was asked for the package typed in the box', (await calls()).includes('helperDownloads'));
  await page.click('#mpSheetBody .mp-patch .mp-act:has-text("Use")'); await wait(300);
  check('Use takes the bundle: Found in Downloads, APKM with its parts, and an Install all parts button', await ev(() => { const t = document.getElementById('mpSheetBody').innerText; return /Found in Downloads/i.test(t) && /APKM, 5 parts/.test(t) && /Install all parts/.test(t) && /Patch this file/.test(t) && /The bundle is ready/.test(t) && !/\d+ found/i.test(t); }));
  await ev(() => { document.getElementById('mpHPkg').value = 'com.other.app'; mpHRead(); });
  await page.click('#mpSheetBody .mp-hflows .mp-act:has-text("Find in Downloads")'); await wait(300);
  check('another package finds nothing and says how many other apps were there', await ev(() => /No APK, APKM, APKS or XAPK for com\.other\.app in Downloads \(4 for other apps\)/.test(document.getElementById('mpSheetBody').innerText)));
  await ev(() => { window.__mp.dlAccess = false; });
  await page.click('#mpSheetBody .mp-hflows .mp-act:has-text("Find in Downloads")'); await wait(300);
  check('without All files access it says so and offers to grant it', await ev(() => /needs All files access/.test(document.getElementById('mpSheetBody').innerText) && !!document.querySelector('#mpSheetBody .batch-tool-link')));
  await ev(() => { window.__mp.dlAccess = true; document.getElementById('mpHPkg').value = 'com.google.android.youtube'; mpHRead(); mp.h.found = null; mp.h.got = null; mpHelperRender(); });
  const before = (await calls()).filter(c => c === 'helperDownloads').length;
  await ev(() => { mpHManual(); }); await wait(200);
  await ev(() => { onAppResume(); }); await wait(400);
  check('coming back from the browser looks in Downloads by itself, once', (await calls()).filter(c => c === 'helperDownloads').length === before + 1 && /2 found/i.test(await text('#mpSheetBody')));
  await ev(() => { onAppResume(); }); await wait(300);
  check('a second return does not look again', (await calls()).filter(c => c === 'helperDownloads').length === before + 1);
  await page.screenshot({ path: 'morphe_helper_downloads.png' });
  await ev(() => mpSheetClose());

  // ---- Settings ----
  await page.click('#mpTop .mp-top-btns button:nth-child(4)'); await wait(250);
  check('the patcher settings: mode, install, delete, where to keep, strip libraries, stop on failure, force, signer, the key, About with credits', await ev(() => { const t = document.getElementById('mpSheetBody').innerText.toLowerCase(); return ['Patching mode', 'Install when finished', 'Delete the APK after installing', 'Keep patched APKs in', 'Strip unused native libraries', 'Stop when a patch fails', 'Patch unsupported versions', 'Signing key', 'Export the key', 'MorpheApp', 'GPL-3.0'].every(w => t.includes(w.toLowerCase())); }));
  await page.screenshot({ path: 'morphe_settings.png' });
  await ev(() => mpSheetClose());

  // ---- no engine ----
  await ev(() => { mp.info = { engine: false }; mpEngineNote(); });
  check('without the engine the card says patching is not available', (await show('#mpEngineWarn')) && /does not contain the Morphe engine/.test(await text('#mpEngineWarn')));

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
