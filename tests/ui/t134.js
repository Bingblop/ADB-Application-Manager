// v7.10.10: haptic feedback on every tap with a switch in Settings (on by default), the + tab of the App Stores (a repository, a GitHub or Codeberg user or project, named by the person or
// from the address), the details and screenshots of an app in every store tab, Apps first in the Processes list, the list of fonts emptied when Settings is left, the gear in the app's own
// colours, SD Maid (the tab's new name) with Trim Caches in it, and AppCleaner's accessibility service: asked for at the moment of deleting, and a button for the caches that are left.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const dialogs = []; let promptAnswer = null, confirmAnswer = true;
  page.on('dialog', d => { dialogs.push(d.type() + ':' + d.message()); if (d.type() === 'prompt') (promptAnswer === null ? d.dismiss() : d.accept(promptAnswer)); else (confirmAnswer ? d.accept() : d.dismiss()); });
  await page.addInitScript(() => {
    window.__vib = 0; window.__urls = []; window.__calls = []; window.__cat = []; window.__det = []; window.__inst = []; window.__probe = [];
    window.__acs = { enabled: false, connected: false, consent: false };
    const T = k => 't134_' + k;
    const reply = (fn, o, ms) => setTimeout(() => window[fn](JSON.stringify(o)), ms || 5);
    const item = (key, name, extra) => Object.assign({ id: key, key: key, name: name, pkg: '', cats: ['Tools'], desc: 'About ' + name, icon: '', source: 'github', resolveKind: 'github', owner: key.split('/')[0], repo: key.split('/')[1] || key, stars: 5, updated: 1, ver: '' }, extra || {});
    window.AndroidBridge = {
      vibrate() { window.__vib++; }, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {}, openUrl(u) { window.__urls.push(u); },
      loadSetting(k) { try { return localStorage.getItem(T(k)) || (k === 'perm_intro_v62' ? '1' : ''); } catch (e) { return ''; } }, saveSetting(k, v) { try { localStorage.setItem(T(k), v); } catch (e) {} },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      scanFonts() { return 'started'; },
      storeSourceCatalog(source, arg) {
        window.__cat.push([source, arg]);
        if (source === 'github') return reply('onStoreSource', { source: 'github', arg: '', status: 'ok', items: [item('alice/one', 'One App', { icon: 'https://i/one.png' }), item('bob/two', 'Two App')], append: false, done: true, total: 2 });
        if (source === 'custom') {
          const a = JSON.parse(arg);
          if (a.kind === 'fdroid') return reply('onStoreSource', { source: 'custom', arg: arg, status: 'ok', items: [item('f.one', 'F One', { source: 'fdroid', resolveKind: 'direct', pkg: 'f.one', owner: '', repo: '', apkUrl: 'https://r/f1.apk', ver: '1.0' })], append: false, done: true, total: 1, repoName: 'Probe Repo', skipped: 2 });
          return reply('onStoreSource', { source: 'custom', arg: arg, status: 'ok', items: [item(a.owner + '/' + (a.repo || 'x'), 'Custom ' + (a.repo || a.owner), { source: 'custom' })], append: false, done: true, total: 1, note: a.kind.endsWith('owner') ? 'Not every project publishes an APK.' : '' });
        }
      },
      storeSourceRefresh(source, arg) { window.__cat.push(['refresh:' + source, arg]); return this.storeSourceCatalog(source, arg); },
      storeSourceDetail(json) {
        const q = JSON.parse(json); window.__det.push(q);
        reply('onStoreSourceDetail', { key: q.key, source: q.source, ok: true, note: '', detail: { d: 'The long description of ' + q.key + '.\n\n## Features\n- one\n- two', shots: ['https://i/s1.png', 'https://i/s2.png'], web: 'https://web.example', src: 'https://git.example/' + q.key, lic: 'MIT', stars: 99, forks: 3, topics: ['android'], by: 'someone' } }, 20);
      },
      storeCustomProbe(id, url) { window.__probe.push([id, url]); reply('onStoreCustomProbe', url.indexOf('bad') >= 0 ? { id: id, ok: false, error: 'That address is not a repository the app can read.' } : { id: id, ok: true, kind: 'fdroid', address: url, title: 'Probe Repo', format: 'v2' }, 20); },
      storeSourceInstall(json) { window.__inst.push(JSON.parse(json)); },
      sdm(tag, op, argsJson) {
        const a = JSON.parse(argsJson || '{}'); window.__calls.push({ op, a });
        let r = { ok: true };
        if (op === 'state') r = { ok: true, tools: { systemcleaner: { tool: 'systemcleaner', state: 'idle', hasData: false }, appcleaner: { tool: 'appcleaner', state: 'done', hasData: true, summary: { itemCount: 3, bytes: 3000000, primary: '3 items', secondary: '3 MB' } }, corpsefinder: { tool: 'corpsefinder', state: 'idle', hasData: false }, deduplicator: { tool: 'deduplicator', state: 'idle', hasData: false } }, running: 0, queued: 0 };
        else if (op === 'areas') r = { ok: true, areas: [], mode: { name: 'adb_tcp', uid: 2000, privileged: true }, access: {} };
        else if (op === 'acs') r = Object.assign({ ok: true }, window.__acs);
        setTimeout(() => window.onSdmReply({ tag, r }), 0);
      }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const vib = () => ev(() => window.__vib);
  const resetVib = () => ev(() => { window.__vib = 0; hapticLast = 0; });

  // ---- 1. haptics ----
  check('1. haptic feedback is on until the person turns it off', await ev(() => hapticsEnabled() === true));
  await resetVib(); await page.click('.tab-btn[data-tab="about"]'); await wait(80);
  check('   tapping a tab ticks once', (await vib()) === 1, String(await vib()));
  await resetVib(); await page.click('#prefsHeaderBtn'); await wait(80);
  check('   tapping the gear ticks once (a button that is not a <button>)', (await vib()) === 1, String(await vib()));
  await ev(() => { switchView('taskmgr'); }); await wait(150); await resetVib();
  await page.click('#tmSortMem'); await wait(80);
  check('   a button whose own handler also ticks is felt once, not twice', (await vib()) === 1, String(await vib()));
  await resetVib(); await ev(() => { const x = document.createElement('button'); x.id = 'probeBtn'; x.setAttribute('onclick', 'window.__probeClicks = 1'); document.body.appendChild(x); x.click(); }); await wait(50);
  check('   a click the page makes by itself (not a tap) does not tick', (await vib()) === 0, String(await vib()));
  await ev(() => switchView('prefs')); await wait(150);
  check('   Settings has the Haptic feedback card with its switch on', await ev(() => !!document.getElementById('hapticCard') && document.getElementById('hapticToggle').checked === true));
  await resetVib(); await ev(() => document.getElementById('hapticToggle').scrollIntoView()); await page.click('#hapticCard .switch-row'); await wait(100);
  const offState = await ev(() => ({ on: hapticsEnabled(), kv: kvGet('haptics', true) }));
  const vAfterOff = await vib();
  await resetVib(); await page.click('.tab-btn[data-tab="apps"]'); await wait(60);
  const vTap = await vib();
  check('   switching it off: no tick, the setting is kept, a tap now does not tick', offState.on === false && offState.kv === false && vAfterOff === 0 && vTap === 0, JSON.stringify([offState, vAfterOff, vTap]));
  await page.reload(); await wait(700);
  check('   after a restart it is still off', await ev(() => hapticsEnabled() === false));
  await ev(() => switchView('prefs')); await wait(150);
  check('   the switch shows it off', await ev(() => document.getElementById('hapticToggle').checked === false));
  await resetVib(); await ev(() => document.getElementById('hapticToggle').scrollIntoView()); await page.click('#hapticCard .switch-row'); await wait(100);
  check('   switching it on ticks to show it, and taps tick again', (await vib()) === 1 && await ev(() => hapticsEnabled() === true));

  // ---- 2. the gear ----
  check('2. the gear wears the app\'s colours: not the green of the mode badge', await ev(() => { const g = getComputedStyle(document.getElementById('prefsHeaderBtn')), m = getComputedStyle(document.getElementById('execModeBadge').closest('.status-badge') || document.getElementById('execModeBadge')); return g.backgroundColor !== m.backgroundColor && g.backgroundColor !== 'rgba(0, 230, 118, 0.12)'; }));

  // ---- 3. Apps first ----
  await ev(() => { switchView('taskmgr'); tmSetSub('processes'); tmSetSort('cpu'); tmLastData = { ok: true, privileged: true, procs: [
    { pid: 1, name: 'kswapd0', pkg: '', cpuPercent: 9, rssKb: 10 }, { pid: 2, name: 'com.big.app', pkg: 'com.big.app', cpuPercent: 3, rssKb: 500 }, { pid: 3, name: 'surfaceflinger', pkg: '', cpuPercent: 2, rssKb: 90 }, { pid: 4, name: 'com.small.app', pkg: 'com.small.app', cpuPercent: 1, rssKb: 50 }] }; tmRenderProcesses(); }); await wait(100);
  const names = () => ev(() => [...document.querySelectorAll('#tmProcList .tm-row-name')].map(e => e.innerText));
  check('3. the Processes list starts in the order of the sort (CPU)', (await names()).join() === 'kswapd0,com.big.app,surfaceflinger,com.small.app', (await names()).join());
  await page.click('#tmAppsFirst'); await wait(80);
  check('   Apps first puts the apps above the system processes, each group keeping its order', (await names()).join() === 'com.big.app,com.small.app,kswapd0,surfaceflinger' && await ev(() => document.getElementById('tmAppsFirst').getAttribute('aria-pressed') === 'true'), (await names()).join());
  await page.click('#tmSortMem'); await wait(80);
  check('   it works together with Sort by memory', (await names()).join() === 'com.big.app,com.small.app,surfaceflinger,kswapd0', (await names()).join());
  await ev(() => { tmReady = false; tmInit(); }); await wait(60);
  check('   the choice is remembered', await ev(() => tmAppsFirst === true && document.getElementById('tmAppsFirst').classList.contains('on')));
  await page.click('#tmAppsFirst'); await wait(60);
  check('   tapping it again goes back to the plain order', (await names()).join() === 'com.big.app,surfaceflinger,com.small.app,kswapd0', (await names()).join());

  // ---- 4. the list of fonts ----
  await ev(() => switchView('prefs')); await wait(150);
  await ev(() => { fontScanStart(); window.onFontScan(JSON.stringify({ status: 'ok', fonts: [{ path: '/a/Roboto.ttf', name: 'Roboto.ttf', family: 'Roboto', style: 'Regular', size: 1000 }, { path: '/a/Lato.ttf', name: 'Lato.ttf', family: 'Lato', style: 'Bold', size: 2000 }] })); }); await wait(100);
  check('4. a search for fonts lists them, with a Clear this list link', await ev(() => document.querySelectorAll('#fontScanList .font-row').length === 2 && getComputedStyle(document.getElementById('fontScanClearBtn')).display !== 'none'));
  await ev(() => switchView('about')); await wait(100); await ev(() => switchView('prefs')); await wait(100);
  check('   leaving Settings empties the list and hides the box', await ev(() => document.querySelectorAll('#fontScanList .font-row').length === 0 && getComputedStyle(document.getElementById('fontScanBox')).display === 'none' && fontFound.length === 0));
  await ev(() => { fontScanStart(); window.onFontScan(JSON.stringify({ status: 'ok', fonts: [{ path: '/a/Roboto.ttf', name: 'Roboto.ttf', family: 'Roboto', style: 'Regular', size: 1000 }] })); }); await wait(60);
  await page.click('#fontScanClearBtn'); await wait(60);
  check('   Clear this list does the same without leaving', await ev(() => document.querySelectorAll('#fontScanList .font-row').length === 0 && fontFound.length === 0));
  await ev(() => { fontScanStart(); switchView('about'); }); await wait(60); await ev(() => window.onFontScan(JSON.stringify({ status: 'ok', fonts: [{ path: '/a/x.ttf', name: 'x.ttf', family: 'X', style: 'Regular', size: 10 }] }))); await wait(60); await ev(() => switchView('prefs')); await wait(100);
  check('   a search that ends after Settings was left is thrown away', await ev(() => fontFound.length === 0 && document.querySelectorAll('#fontScanList .font-row').length === 0));

  // ---- 5. SD Maid ----
  check('5. the tab is called SD Maid, and Trim Caches is in it, no longer in Settings', await ev(() => tabDef('sdm').name === 'SD Maid' && tabDef('sdm').label === 'SD Maid' && !document.querySelector('#view-prefs #trimCachesCard') && !!document.querySelector('#view-sdm #trimCachesCard')));
  check('   the tab bar says SD Maid', await ev(() => [...document.querySelectorAll('.tab-btn')].some(t => t.innerText.trim() === 'SD Maid')));
  await ev(() => switchView('sdm')); await wait(500);
  check('   the Trim Caches button is there and has its padlock logic (a working mode is on here)', await ev(() => !!document.getElementById('trimCachesBtn') && document.getElementById('trimCachesBtn').innerText.indexOf('Trim Caches in All Applications') >= 0));

  // ---- 6. AppCleaner and the accessibility service ----
  check('6. AppCleaner has "Clear the rest with accessibility" when it has results', await ev(() => !!document.getElementById('sdClearRest')));
  await ev(() => { window.__calls.length = 0; });
  await ev(() => { sdDeleteTool('appcleaner'); }); await wait(300);
  check('   Delete asks the phone for the state of the service first (the page\'s idea may be old) and says it is not ready', (await ev(() => window.__calls.map(c => c.op + (c.a.op ? ':' + c.a.op : '')))).includes('acs:status') && await ev(() => /accessibility service is not ready/.test(document.getElementById('mpAskText') ? document.getElementById('mpAskText').innerText : document.body.innerText)));
  await ev(() => { mpAskDone(true); }); await wait(200);
  const del = await ev(() => window.__calls.filter(c => c.op === 'delete').pop());
  check('   it still asks for the automation (the phone decides), never a page-side "off"', !!del && del.a.selection.options.useAutomation === true && del.a.selection.options.includeInaccessible === true, JSON.stringify(del && del.a.selection.options));
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdClearRest'); await wait(300);
  check('   "Clear the rest" with the service not ready offers to set it up instead of failing', await ev(() => /not ready/.test(document.body.innerText) && !window.__calls.some(c => c.op === 'delete')));
  await ev(() => { mpAskDone(false); }); await wait(100);
  await ev(() => { window.__acs = { enabled: true, connected: true, consent: true }; window.__calls.length = 0; });
  await page.click('#sdClearRest'); await wait(300);
  check('   with the service ready it asks once', await ev(() => !window.__calls.some(c => c.op === 'delete')));
  await ev(() => { mpAskDone(true); }); await wait(250);
  const rest = await ev(() => window.__calls.filter(c => c.op === 'delete').pop());
  check('   and then deletes only the caches the files cannot reach, with the automation', !!rest && rest.a.tool === 'appcleaner' && rest.a.selection.options.onlyInaccessible === true && rest.a.selection.options.useAutomation === true, JSON.stringify(rest && rest.a.selection.options));

  // ---- 7. the + tab ----
  await ev(() => switchView('store')); await wait(300);
  check('7. the last tab of the App Stores is a +', await ev(() => { const t = [...document.querySelectorAll('#storeSubtabs .store-subtab')]; return t[t.length - 1].id === 'storeAddTab' && t[t.length - 1].innerText.trim() === '+'; }));
  await page.click('#storeAddTab'); await wait(100);
  check('   it opens a window asking for an address and, if wanted, a name', await ev(() => document.getElementById('storeAddModal').classList.contains('show') && !!document.getElementById('storeAddUrl') && !!document.getElementById('storeAddName')));
  await page.fill('#storeAddUrl', 'https://github.com/alice/proj'); await wait(50);
  check('   it says what it understood from the address', await ev(() => /GitHub project/.test(document.getElementById('storeAddNote').innerText)));
  await page.click('#storeAddBtn'); await wait(300);
  check('   a GitHub project becomes a tab named after the project, before the +, and it loads its apps', await ev(() => { const t = [...document.querySelectorAll('#storeSubtabs .store-subtab')].map(x => x.innerText.trim()); return t.join() === 'ShizuStore,GitHub,F-Droid,Orion,proj,+' && document.querySelector('#storeSubtabs .store-subtab.active').innerText.trim() === 'proj'; }) && await ev(() => window.__cat.some(c => c[0] === 'custom' && JSON.parse(c[1]).kind === 'github-repo' && JSON.parse(c[1]).owner === 'alice' && JSON.parse(c[1]).repo === 'proj')));
  check('   its list shows the project with an Install button', await ev(() => { const id = document.querySelector('#storeSubtabs .store-subtab.active').dataset.store; return document.querySelectorAll('#' + id + 'List .store-row').length === 1 && /Custom proj/.test(document.getElementById(id + 'List').innerText); }));
  await page.click('#storeAddTab'); await page.fill('#storeAddUrl', 'github.com/bob'); await page.fill('#storeAddName', 'Bob stuff'); await page.click('#storeAddBtn'); await wait(300);
  check('   a name typed by the person is used: a GitHub user is a tab of all of their projects', await ev(() => [...document.querySelectorAll('#storeSubtabs .store-subtab')].map(x => x.innerText.trim()).join() === 'ShizuStore,GitHub,F-Droid,Orion,proj,Bob stuff,+') && await ev(() => window.__cat.some(c => c[0] === 'custom' && JSON.parse(c[1]).kind === 'github-owner' && JSON.parse(c[1]).owner === 'bob')));
  check('   a note on such a store says that not every project has an APK', await ev(() => { const id = document.querySelector('#storeSubtabs .store-subtab.active').dataset.store; return /Not every project publishes an APK/.test(document.getElementById(id + 'Status').innerText); }));
  await page.click('#storeAddTab'); await page.fill('#storeAddUrl', 'https://repo.example/fdroid/repo'); await page.click('#storeAddBtn'); await wait(400);
  check('   a repository is looked at first, and its own name fills the tab', await ev(() => window.__probe.length === 1) && await ev(() => [...document.querySelectorAll('#storeSubtabs .store-subtab')].map(x => x.innerText.trim()).join() === 'ShizuStore,GitHub,F-Droid,Orion,proj,Bob stuff,Probe Repo,+'));
  check('   its apps load through the repository reader', await ev(() => window.__cat.some(c => c[0] === 'custom' && JSON.parse(c[1]).kind === 'fdroid' && JSON.parse(c[1]).url === 'https://repo.example/fdroid/repo')));
  await page.click('#storeAddTab'); await page.fill('#storeAddUrl', 'https://bad.example/nothing'); await page.click('#storeAddBtn'); await wait(300);
  check('   an address that is no repository says so, in the window, and adds nothing', await ev(() => document.getElementById('storeAddModal').classList.contains('show') && /not a repository the app can read/.test(document.getElementById('storeAddNote').innerText) && document.querySelectorAll('#storeSubtabs .store-subtab').length === 8));
  await page.fill('#storeAddUrl', 'not a url at all'); await page.click('#storeAddBtn'); await wait(100);
  check('   something that is no address is refused', await ev(() => /web address/.test(document.getElementById('storeAddNote').innerText) && document.querySelectorAll('#storeSubtabs .store-subtab').length === 8));
  await page.fill('#storeAddUrl', 'https://github.com/alice/proj.git'); await page.click('#storeAddBtn'); await wait(200);
  check('   the same project again is not added twice', await ev(() => document.querySelectorAll('#storeSubtabs .store-subtab').length === 8 && !document.getElementById('storeAddModal').classList.contains('show')));
  await page.reload(); await wait(700); await ev(() => switchView('store')); await wait(400);
  check('   the extra stores are still there after a restart, in the same order', await ev(() => [...document.querySelectorAll('#storeSubtabs .store-subtab')].map(x => x.innerText.trim()).join() === 'ShizuStore,GitHub,F-Droid,Orion,proj,Bob stuff,Probe Repo,+'));
  promptAnswer = 'My tools';
  await ev(() => { switchStoreTab(storeCustomList()[0].id); }); await wait(200);
  await page.click('#substore-' + await ev(() => storeCustomList()[0].id) + ' >> text=Rename'); await wait(150);
  check('   Rename changes the tab and the card', await ev(() => { const id = storeCustomList()[0].id; return document.getElementById('subtab-' + id).innerText === 'My tools' && document.getElementById(id + 'Title').innerText === 'My tools' && storeCustomList()[0].title === 'My tools'; }));
  confirmAnswer = true;
  await page.click('#substore-' + await ev(() => storeCustomList()[0].id) + ' >> text=Remove this store'); await wait(200);
  check('   Remove this store takes the tab and its list away and goes back to ShizuStore', await ev(() => [...document.querySelectorAll('#storeSubtabs .store-subtab')].map(x => x.innerText.trim()).join() === 'ShizuStore,GitHub,F-Droid,Orion,Bob stuff,Probe Repo,+' && storeCustomList().length === 2 && document.querySelector('#storeSubtabs .store-subtab.active').innerText.trim() === 'ShizuStore'));

  // ---- 8. the details of an app in every store ----
  await ev(() => switchStoreTab('github')); await wait(250);
  check('8. a row of the GitHub store opens the details when tapped (it only worked in ShizuStore before)', await ev(() => document.querySelectorAll('#githubList .store-row[data-src="github"]').length === 2));
  await ev(() => { window.__det.length = 0; });
  await page.click('#githubList .store-row >> nth=0 >> text=One App'); await wait(400);
  const dm = await ev(() => ({ shown: document.getElementById('storeModal').classList.contains('show'), name: document.getElementById('storeDetailName').innerText, sub: document.getElementById('storeDetailSub').innerText, body: document.getElementById('storeDetailBody').innerText, shots: document.querySelectorAll('#storeDetailBody .shot-thumb').length, btn: document.getElementById('storeInstallBtn').innerText, dis: document.getElementById('storeInstallBtn').disabled }));
  check('   the window shows the app, its long description, chips and the install button', dm.shown && dm.name === 'One App' && dm.sub === 'alice/one' && /The long description of alice\/one/.test(dm.body) && /99 stars/.test(dm.body) && /MIT/.test(dm.body) && dm.btn === 'Install' && !dm.dis, JSON.stringify(dm));
  check('   the phone was asked for the rest, with what it needs to find it', await ev(() => window.__det.length === 1 && window.__det[0].key === 'alice/one' && window.__det[0].owner === 'alice' && window.__det[0].repo === 'one' && window.__det[0].resolveKind === 'github'));
  check('   its screenshots are shown and open the picture viewer', dm.shots === 2 && await (async () => { await page.click('#storeDetailBody .shot-thumb >> nth=0'); await wait(150); return ev(() => shotIsOpen() && shotUrls.length === 2); })());
  await ev(() => shotClose());
  check('   Website and Source links are there', await ev(() => [...document.querySelectorAll('#storeDetailBody .batch-tool-link')].map(x => x.innerText).join() === 'Website,Source'));
  await page.click('#storeSourceBtn'); await wait(80);
  check('   the Source button of the window opens the project', await ev(() => window.__urls.some(u => u === 'https://git.example/alice/one')), await ev(() => window.__urls.join()));
  await ev(() => { window.__inst.length = 0; });
  await page.click('#storeInstallBtn'); await wait(120);
  check('   Install in the window installs this app, the same way as the button of the row', await ev(() => window.__inst.length === 1 && window.__inst[0].key === 'alice/one' && window.__inst[0].resolveKind === 'github') && await ev(() => /Starting/.test(document.getElementById('storeInstallProgress').innerText)));
  await ev(() => { onStoreInstallProgress(JSON.stringify({ stage: 'error', message: 'No APK in the latest release', pkg: 'alice/one' })); }); await wait(60);
  check('   a failure is told in the window and the button is back', await ev(() => /No APK in the latest release/.test(document.getElementById('storeInstallProgress').innerText) && !document.getElementById('storeInstallBtn').disabled));
  await ev(() => closeStoreModal());
  await ev(() => { window.__det.length = 0; });
  await page.click('#githubList .store-row >> nth=0 >> text=One App'); await wait(200);
  check('   a second look at the same app does not ask again', await ev(() => window.__det.length === 0) && await ev(() => document.querySelectorAll('#storeDetailBody .shot-thumb').length === 2));
  await ev(() => closeStoreModal()); await ev(() => { window.__inst.length = 0; });
  await page.click('#githubList .store-row >> nth=1 >> button.store-inst-btn'); await wait(100);
  check('   the Install button of a row installs without opening the details', await ev(() => window.__inst.length === 1 && !document.getElementById('storeModal').classList.contains('show')));
  // a store the person added, an F-Droid style one: the phone is told which repository the app came from
  await ev(() => { const l = storeCustomList(); switchStoreTab(l[1].id); }); await wait(300);
  await ev(() => { window.__det.length = 0; });
  await page.click('#' + await ev(() => storeCustomList()[1].id) + 'List .store-row >> text=F One'); await wait(300);
  check('   in a repository store the detail request carries the repository and the package', await ev(() => window.__det.length === 1 && window.__det[0].fdroidRepo === 'https://repo.example/fdroid/repo' && window.__det[0].pkg === 'f.one'), await ev(() => JSON.stringify(window.__det)));
  await ev(() => closeStoreModal());
  // a detail that fails
  await ev(() => switchStoreTab('github')); await wait(200);
  await ev(() => { window.AndroidBridge.storeSourceDetail = function (json) { const q = JSON.parse(json); setTimeout(() => window.onStoreSourceDetail(JSON.stringify({ key: q.key, ok: false, error: 'the limit for requests is used up (HTTP 403). Save a GitHub token (Updates tab) to raise it.' })), 10); }; });
  await page.click('#githubList .store-row >> nth=1 >> text=Two App'); await wait(250);
  check('   when the details cannot be loaded, what the list knows stays and the reason is told', await ev(() => /About Two App/.test(document.getElementById('storeDetailBody').innerText) && /limit for requests is used up/.test(document.getElementById('storeDetailBody').innerText)));
  await ev(() => closeStoreModal());

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
