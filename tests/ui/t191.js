// App Stores, ShizuStore tab: every row has the same Install button on its right as the GitHub, F-Droid and Orion rows. It installs without opening the details window
// (details loaded once and kept, the download chosen exactly as the details window chooses it), shows progress and errors on the row, and copes with an app that has no APK,
// a root app, details that fail to load, a second tap, and the list being drawn again (search, sort) while an install runs.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__loads = []; window.__installs = []; window.__hold = {}; window.__held = {};
    const items = [
      { slug: 'alpha', name: 'Alpha Tool', description: 'First test app', packageName: 'com.alpha', versionName: '1.0', stars: 90, categorySlug: 'tools', iconHash: '' },
      { slug: 'bravo', name: 'Bravo Root', description: 'Needs root', packageName: 'com.bravo', versionName: '2.0', stars: 80, categorySlug: 'tools', iconHash: '', requiresRoot: true },
      { slug: 'charlie', name: 'Charlie Nodl', description: 'No APK', packageName: 'com.charlie', stars: 70, categorySlug: 'tools', iconHash: '' },
      { slug: 'delta', name: 'Delta Play', description: 'Play Store only', packageName: 'com.delta', stars: 60, categorySlug: 'tools', iconHash: '' },
      { slug: 'echo', name: 'Echo Broken', description: 'Details fail', packageName: 'com.echo', stars: 50, categorySlug: 'tools', iconHash: '' },
      { slug: 'foxtrot', name: 'Foxtrot NoPkg', description: 'Download without package name', packageName: '', stars: 40, categorySlug: 'tools', iconHash: '' },
      { slug: 'golf', name: 'Golf Tool', description: 'Another one', packageName: 'com.golf', stars: 30, categorySlug: 'tools', iconHash: '' },
      { slug: 'hotel', name: 'Hotel Plain', description: 'Only an http address', packageName: 'com.hotel', stars: 20, categorySlug: 'tools', iconHash: '' },
    ];
    const detail = slug => {
      const it = items.find(x => x.slug === slug);
      const base = { status: 'ok', slug, base: 'https://store.example', app: { slug, name: it.name, packageName: it.packageName, versionName: '1.0', fullDescription: 'Full ' + slug, categorySlug: 'tools', permissions: [], screenshots: [], requiresRoot: !!it.requiresRoot } };
      if (slug === 'charlie') return base;
      if (slug === 'delta') { base.app.storeUrl = 'https://play.google.com/store/apps/details?id=com.delta'; return base; }
      if (slug === 'echo') return { status: 'error', slug, error: 'boom' };
      base.download = { apkUrl: (slug === 'hotel' ? 'http://' : 'https://') + 'store.example/' + slug + '.apk', packageName: slug === 'foxtrot' ? '' : 'com.' + slug, versionName: '1.0', size: 3145728, sha256: slug === 'foxtrot' ? '' : (slug.charCodeAt(0).toString(16).padStart(2, '0')).repeat(32), source: 'GitHub' };
      return base;
    };
    window.__detail = detail;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'adb', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      storeLoadCatalog() { setTimeout(() => window.onStoreCatalog(JSON.stringify({ status: 'ok', base: 'https://store.example', items, total: items.length })), 0); },
      // an answer is kept until the script lets it go, so the script can look at the page while the details are on their way
      storeLoadApp(slug) {
        window.__loads.push(slug);
        const answer = () => window.onStoreApp(JSON.stringify(detail(slug)));
        if (window.__hold[slug]) window.__held[slug] = answer; else setTimeout(answer, 0);
      },
      storeInstall(apkUrl, pkg, label, sha256) { window.__installs.push([apkUrl, pkg, label, sha256]); },
      openUrlExternal() {}, executeAppAction() { return 'ok'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const prog = o => ev(o => onStoreInstallProgress(JSON.stringify(o)), o);
  const row = slug => `#storeList > div[data-slug="${slug}"]`;
  const rowInfo = slug => ev(s => {
    const r = document.querySelector('#storeList > div[data-slug="' + s + '"]'); if (!r) return null;
    const bt = r.querySelector('.store-inst-btn'), st = r.querySelector('.sr-status');
    return { btn: bt ? bt.innerText : null, dis: bt ? bt.disabled : null, aria: bt ? bt.getAttribute('aria-label') : null, status: st ? st.innerText : null, statusShown: st ? st.style.display !== 'none' : false, err: !!(st && st.querySelector('.is-err')) };
  }, slug);
  const modalShown = () => ev(() => document.getElementById('storeModal').classList.contains('show'));
  const loads = () => ev(() => window.__loads.slice());
  const installs = () => ev(() => window.__installs.map(a => a.slice()));
  const release = slug => ev(s => { const f = window.__held[s]; delete window.__held[s]; delete window.__hold[s]; if (f) f(); return !!f; }, slug);
  const closeResults = () => ev(() => { try { closeCommandResultsModal(); } catch (e) {} });

  await ev(() => switchView('store')); await wait(150);

  // ---- 1. every row has the button, on the right ----
  const slugs = ['alpha', 'bravo', 'charlie', 'delta', 'echo', 'foxtrot', 'golf', 'hotel'];
  const geo = await ev(() => [...document.querySelectorAll('#storeList > div[data-slug]')].map(r => {
    const bt = r.querySelectorAll('.store-inst-btn'), b0 = bt[0], rr = r.getBoundingClientRect(), br = b0 ? b0.getBoundingClientRect() : null;
    const col = r.querySelector('.sr-status').parentElement.getBoundingClientRect();
    return { slug: r.dataset.slug, n: bt.length, cls: b0 && b0.className, right: br && Math.round(rr.right - br.right), leftOfBtn: br && Math.round(br.left - col.right), vmid: br && Math.abs((br.top + br.height / 2) - (rr.top + rr.height / 2)) < 4, name: r.querySelector('div[style*="font-weight:700"]').innerText, aria: b0 && b0.getAttribute('aria-label') };
  }));
  check('8 rows, each with exactly one Install button of the class the other stores use', geo.length === 8 && geo.every(g => g.n === 1 && g.cls === 'store-inst-btn'), JSON.stringify(geo.map(g => [g.slug, g.n, g.cls])));
  check('the button is on the right of the row: inside its padding (about 12 px from the edge), right of the text column, centred vertically', geo.every(g => g.right >= 8 && g.right <= 16 && g.leftOfBtn >= 0 && g.vmid), JSON.stringify(geo.map(g => [g.right, g.leftOfBtn, g.vmid])));
  check('the aria-label of each button is "Install <name>"', geo.every(g => g.aria === 'Install ' + g.name), JSON.stringify(geo.map(g => g.aria)));
  check('before any tap the buttons are enabled and no row shows a status line', (await Promise.all(slugs.map(rowInfo))).every(r => r.btn === 'Install' && !r.dis && !r.statusShown));
  const root = await ev(() => document.querySelector('#storeList > div[data-slug="bravo"]').innerText);
  check('the root app keeps its "root" chip', /\broot\b/.test(root.replace('Bravo Root', '').replace('Needs root', '')), JSON.stringify(root));

  // ---- 2. a tap on the button installs without opening the details; the details are loaded once ----
  await ev(() => { window.__hold.alpha = true; });
  await page.click(row('alpha') + ' .store-inst-btn'); await wait(60);
  check('tapping Install does not open the details window', !(await modalShown()));
  let r = await rowInfo('alpha');
  check('while the details load: the row says Loading…, the button is off, nothing is installed yet', r.status === 'Loading…' && r.dis && r.statusShown && (await installs()).length === 0, JSON.stringify(r));
  check('storeLoadApp was asked once, for that app', JSON.stringify(await loads()) === '["alpha"]');
  await release('alpha'); await wait(80);
  const alphaArgs = ['https://store.example/alpha.apk', 'com.alpha', 'Alpha Tool', '61'.repeat(32)];
  check('then storeInstall is called once with the apk address, package, name and checksum of the download', JSON.stringify(await installs()) === JSON.stringify([alphaArgs]), JSON.stringify(await installs()));
  r = await rowInfo('alpha');
  check('the row says Starting…, the button stays off, the window stays closed', r.status === 'Starting…' && r.dis && !(await modalShown()), JSON.stringify(r));

  // ---- 3. progress on the row, not in the window ----
  await prog({ pkg: 'com.alpha', stage: 'downloading', percent: 50, message: '2 MB' }); await wait(30);
  r = await rowInfo('alpha');
  check('progress is told on the row: "2 MB 50%", button still off', r.status === '2 MB 50%' && r.dis && !r.err, JSON.stringify(r));
  const sheetP = await ev(() => document.getElementById('storeInstallProgress').style.display);
  check('the (closed) window\'s own progress line is not used', sheetP === 'none', sheetP);
  await prog({ pkg: 'com.alpha', stage: 'confirming', percent: 100, message: 'Waiting for your OK to install Alpha Tool…' }); await wait(30);
  r = await rowInfo('alpha');
  check('while the phone asks "Install this app?" the row says so and the button stays off', /Waiting for your OK/.test(r.status) && r.dis, JSON.stringify(r));
  await prog({ pkg: 'com.alpha', stage: 'done', percent: 100, message: 'Installed Alpha Tool 1.0.', installedPkg: 'com.alpha', label: 'Alpha Tool' }); await wait(60);
  r = await rowInfo('alpha');
  check('done: the row says Installed…, the button is back on', r.status === 'Installed Alpha Tool 1.0. 100%' && !r.dis && !r.err, JSON.stringify(r));
  check('done also opens the result sheet with Launch Application', await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /Launch Application/.test(document.getElementById('commandResultsActions').innerText)));
  await closeResults(); await wait(30);

  // ---- 4. the details are kept: a second install of the same app asks storeLoadApp nothing ----
  await page.click(row('alpha') + ' .store-inst-btn'); await wait(60);
  check('a second Install on the same app uses the kept details: storeLoadApp is not asked again', JSON.stringify(await loads()) === '["alpha"]', JSON.stringify(await loads()));
  check('... and storeInstall gets the same arguments again', JSON.stringify(await installs()) === JSON.stringify([alphaArgs, alphaArgs]));
  // the details window of the same app (kept details) installs with the same arguments as the row did
  await prog({ pkg: 'com.alpha', stage: 'error', percent: 0, message: 'Install cancelled.' }); await wait(30);
  r = await rowInfo('alpha');
  check('an error is told on the row, in the error style, with the button back on', r.status === 'Install failed: Install cancelled.' && r.err && !r.dis, JSON.stringify(r));
  check('an error does not open the window', !(await modalShown()));
  await page.click(row('alpha') + ' > div:nth-child(2)'); await wait(80);
  check('a tap on the row itself still opens the details', await modalShown());
  check('opening the details of an app does not load them again (kept)', JSON.stringify(await loads()) === '["alpha"]');
  await page.click('#storeInstallBtn'); await wait(60);
  check('the details window installs with exactly the arguments the row used', JSON.stringify(await installs()) === JSON.stringify([alphaArgs, alphaArgs, alphaArgs]), JSON.stringify(await installs()));
  r = await rowInfo('alpha');
  check('an install started in the window shows on the row as well', r.status === 'Starting…' && r.dis, JSON.stringify(r));
  await prog({ pkg: 'com.alpha', stage: 'downloading', percent: 10, message: '1 MB' }); await wait(30);
  const sp = await ev(() => document.getElementById('storeInstallProgress').innerText);
  check('and in the window, since the window started it', sp === '1 MB 10%', sp);
  await ev(() => installStoreRow('alpha')); await wait(30);
  check('the row\'s Install is ignored while the window\'s install of the same app runs', (await installs()).length === 3);
  await prog({ pkg: 'com.alpha', stage: 'error', percent: 0, message: 'Install cancelled.' }); await wait(30);
  check('the window\'s button is back on after the error', await ev(() => !document.getElementById('storeInstallBtn').disabled));
  await ev(() => closeStoreModal());

  // ---- 5. the root app: the same rule as the details window (it installs like any other) ----
  await page.click(row('bravo') + ' .store-inst-btn'); await wait(80);
  check('a root app: Install loads its details once and installs it', JSON.stringify((await loads()).slice(1)) === '["bravo"]' && JSON.stringify((await installs())[3]) === JSON.stringify(['https://store.example/bravo.apk', 'com.bravo', 'Bravo Root', '62'.repeat(32)]), JSON.stringify(await installs()));
  await prog({ pkg: 'com.bravo', stage: 'error', percent: 0, message: 'needs root' }); await wait(30);
  check('its failure is told on its own row, not on the first one', (await rowInfo('bravo')).status === 'Install failed: needs root' && (await rowInfo('alpha')).status !== 'Install failed: needs root');

  // ---- 6. no APK ----
  await page.click(row('charlie') + ' .store-inst-btn'); await wait(80);
  r = await rowInfo('charlie');
  check('an app without an APK: the row says so, the button is off and says No APK', r.status === 'No direct APK is available for this app.' && r.dis && r.btn === 'No APK' && !r.err, JSON.stringify(r));
  const n0 = (await installs()).length;
  await ev(() => installStoreRow('charlie')); await wait(30);
  check('... and nothing is installed or asked again', (await installs()).length === n0 && (await loads()).filter(s => s === 'charlie').length === 1);
  await page.click(row('delta') + ' .store-inst-btn'); await wait(80);
  r = await rowInfo('delta');
  check('an app that is on the Play Store only says that, with the button off', /Play Store/.test(r.status) && r.dis && r.btn === 'No APK', JSON.stringify(r));

  // the download is chosen by one function for the row and the window: an address that is not https is no APK for either
  await page.click(row('hotel') + ' .store-inst-btn'); await wait(80);
  r = await rowInfo('hotel');
  check('a download that is not https counts as no APK on the row', r.dis && r.btn === 'No APK' && r.status === 'No direct APK is available for this app.' && (await installs()).length === n0, JSON.stringify(r));
  await page.click(row('hotel') + ' > div:nth-child(2)'); await wait(80);
  const hs = await ev(() => ({ b: document.getElementById('storeInstallBtn').innerText, d: document.getElementById('storeInstallBtn').disabled, t: document.getElementById('storeDetailBody').innerText }));
  check('and the details window agrees: No APK, button off, no "Install source" line', hs.b === 'No APK' && hs.d && /No direct APK/.test(hs.t) && !/Install source/.test(hs.t), JSON.stringify(hs));
  await ev(() => closeStoreModal());

  // ---- 7. details that fail to load ----
  await page.click(row('echo') + ' .store-inst-btn'); await wait(80);
  r = await rowInfo('echo');
  check('details that fail to load: the error is on the row (error style), the button is back on', r.status === 'Could not load this app: boom' && r.err && !r.dis && r.btn === 'Install', JSON.stringify(r));
  check('... and the window is not opened', !(await modalShown()));
  await page.click(row('echo') + ' .store-inst-btn'); await wait(80);
  check('a failed load is not kept: the next tap asks again', (await loads()).filter(s => s === 'echo').length === 2);

  // ---- 8. a second tap while it works ----
  const l0 = (await loads()).length, i0 = (await installs()).length;
  await ev(() => { window.__hold.golf = true; });
  await page.click(row('golf') + ' .store-inst-btn'); await wait(40);
  await ev(() => installStoreRow('golf')); await ev(() => document.querySelector('#storeList > div[data-slug="golf"] .store-inst-btn').click()); await wait(30);
  check('taps while the details load are ignored: storeLoadApp once', (await loads()).length === l0 + 1);
  await release('golf'); await wait(80);
  await ev(() => installStoreRow('golf')); await ev(() => document.querySelector('#storeList > div[data-slug="golf"] .store-inst-btn').click()); await wait(30);
  check('taps while the install runs are ignored: storeInstall once', (await installs()).length === i0 + 1);
  await prog({ pkg: 'com.golf', stage: 'done', percent: 100, message: 'Installed Golf Tool 1.0.', installedPkg: 'com.golf', label: 'Golf Tool' }); await wait(40);
  await closeResults();

  // ---- 9. a download without a package name is followed by its address ----
  await page.click(row('foxtrot') + ' .store-inst-btn'); await wait(80);
  const fa = (await installs()).slice(-1)[0];
  check('a download with no package name is installed with an empty package and no checksum', JSON.stringify(fa) === JSON.stringify(['https://store.example/foxtrot.apk', '', 'Foxtrot NoPkg', '']), JSON.stringify(fa));
  await prog({ pkg: 'https://store.example/foxtrot.apk', stage: 'downloading', percent: 20, message: '1 MB' }); await wait(30);
  check('its progress comes under the address and reaches its row', (await rowInfo('foxtrot')).status === '1 MB 20%');
  await prog({ pkg: 'https://store.example/foxtrot.apk', stage: 'error', percent: 0, message: 'x' }); await wait(30);

  // ---- 10. the list drawn again while an install runs ----
  await page.click(row('alpha') + ' .store-inst-btn'); await wait(60);
  await prog({ pkg: 'com.alpha', stage: 'downloading', percent: 40, message: '3 MB' }); await wait(30);
  const iBefore = (await installs()).length;
  await page.fill('#storeSearch', 'tool'); await wait(60);
  r = await rowInfo('alpha');
  check('search draws the list again: the running row keeps its status and its button stays off', r && r.status === '3 MB 40%' && r.dis, JSON.stringify(r));
  await ev(() => installStoreRow('alpha')); await page.selectOption('#storeSort', 'name'); await wait(60);
  r = await rowInfo('alpha');
  check('sorting too; a tap after the drawing does not start a second install', r.status === '3 MB 40%' && r.dis && (await installs()).length === iBefore);
  await page.fill('#storeSearch', 'zzz'); await wait(60);
  await prog({ pkg: 'com.alpha', stage: 'downloading', percent: 80, message: '5 MB' }); await wait(30);
  await page.fill('#storeSearch', ''); await wait(60);
  r = await rowInfo('alpha');
  check('progress that arrived while the row was not drawn is there when it is drawn again', r.status === '5 MB 80%' && r.dis, JSON.stringify(r));
  await prog({ pkg: 'com.alpha', stage: 'opened', percent: 100, message: 'Confirm the install of Alpha Tool.' }); await wait(30);
  r = await rowInfo('alpha');
  check('"opened" (the phone\'s installer took over) ends it: the button is back on', /Confirm the install/.test(r.status) && !r.dis, JSON.stringify(r));
  const w1 = await rowInfo('charlie');
  check('rows of other apps kept what they showed (No APK stays)', w1.dis && w1.btn === 'No APK');

  // ---- 11. progress goes to the window only when the window is the one that started it ----
  await page.selectOption('#storeSort', 'stars');
  await page.click(row('golf') + ' .store-inst-btn'); await wait(60);
  await page.click(row('bravo') + ' > div:nth-child(2)'); await wait(80);
  check('the details of another app are open', await modalShown() && (await ev(() => document.getElementById('storeDetailName').innerText)) === 'Bravo Root');
  const leakAt = () => ev(() => ({ shown: document.getElementById('storeInstallProgress').style.display, text: document.getElementById('storeInstallProgress').textContent, btn: document.getElementById('storeInstallBtn').disabled }));
  const leak0 = await leakAt();
  await prog({ pkg: 'com.golf', stage: 'downloading', percent: 30, message: '1 MB' }); await wait(30);
  const leak = await leakAt();
  check('progress of another app\'s install does not appear in the open window, and does not change its button', leak.shown === 'none' && leak.text === leak0.text && leak.btn === false, JSON.stringify([leak0, leak]));
  check('... it is on the row of that app', (await rowInfo('golf')).status === '1 MB 30%');
  await prog({ pkg: 'com.golf', stage: 'error', percent: 0, message: 'later' }); await wait(30);
  const leak2 = await ev(() => document.getElementById('storeInstallBtn').disabled);
  check('a finished install of another app does not switch the window\'s button either', leak2 === false);
  await ev(() => closeStoreModal());
  // a window opened on the app that is installing: its button is off and it shows how far it is
  await page.click(row('golf') + ' .store-inst-btn'); await wait(60);
  await prog({ pkg: 'com.golf', stage: 'downloading', percent: 60, message: '2 MB' }); await wait(30);
  await page.click(row('golf') + ' > div:nth-child(2)'); await wait(80);
  const gd = await ev(() => ({ dis: document.getElementById('storeInstallBtn').disabled, txt: document.getElementById('storeInstallProgress').innerText, shown: document.getElementById('storeInstallProgress').style.display }));
  check('the window of the app that is installing has its button off and shows the progress so far (no second start from there)', gd.dis && gd.txt === '2 MB 60%' && gd.shown !== 'none', JSON.stringify(gd));
  const i1 = (await installs()).length;
  await ev(() => installStoreApp()); await wait(30);
  check('... and a start from the window is ignored', (await installs()).length === i1);
  await prog({ pkg: 'com.golf', stage: 'downloading', percent: 70, message: '3 MB' }); await wait(30);
  check('its further progress shows in the window and on the row', (await ev(() => document.getElementById('storeInstallProgress').innerText)) === '3 MB 70%' && (await rowInfo('golf')).status === '3 MB 70%');
  await prog({ pkg: 'com.golf', stage: 'error', percent: 0, message: 'stop' }); await wait(30);
  check('when it ends the window\'s button is back', await ev(() => !document.getElementById('storeInstallBtn').disabled));
  await ev(() => closeStoreModal());
  await prog({ pkg: 'com.unknown', stage: 'downloading', percent: 5, message: 'x' }); await wait(30);
  check('progress for an install no row started changes no row', (await Promise.all(slugs.map(rowInfo))).every(x => !/^x/.test(x.status || '')));

  // ---- 12. the other stores' window keeps working: progress of the item it installs shows in it, others do not ----
  await ev(() => {
    window.__sinst = []; AndroidBridge.storeSourceInstall = j => window.__sinst.push(JSON.parse(j)); AndroidBridge.storeSourceCatalog = () => {}; AndroidBridge.storeSourceDetail = () => {};
    onStoreSource(JSON.stringify({ source: 'github', status: 'ok', items: [{ id: 'own/app', key: 'own/app', name: 'Own App', desc: 'd', owner: 'own', repo: 'app', pkg: '', stars: 5, downloads: 1, updated: 1700000000000, ver: 'v1', cats: ['Other'], source: 'github', resolveKind: 'github' }], append: false, done: true, total: 1 }));
    switchStoreTab('github');
  });
  await wait(100);
  await page.click('#githubList .store-row'); await wait(80);
  await page.click('#storeInstallBtn'); await wait(60);
  check('the window of a GitHub app starts its install and says Starting…', (await ev(() => window.__sinst.length)) === 1 && (await ev(() => document.getElementById('storeInstallProgress').innerText)) === 'Starting…');
  await prog({ pkg: 'com.golf', stage: 'downloading', percent: 5, message: 'other' }); await wait(30);
  check('progress of some other install does not enter that window', (await ev(() => document.getElementById('storeInstallProgress').innerText)) === 'Starting…');
  await prog({ pkg: 'own/app', stage: 'downloading', percent: 5, message: '1 MB' }); await wait(30);
  check('its own progress does', (await ev(() => document.getElementById('storeInstallProgress').innerText)) === '1 MB 5%');
  await prog({ pkg: 'own/app', stage: 'error', percent: 0, message: 'nope' }); await wait(30);
  check('and its end switches the button back on', await ev(() => !document.getElementById('storeInstallBtn').disabled));

  // ---- 13. one request for the details at a time: the row and the window wait for the same answer ----
  await ev(() => closeStoreModal());
  await ev(() => switchStoreTab('shizu')); await wait(60);        // back from the GitHub list of section 12
  await ev(() => { delete storeDetailCache.bravo; window.__hold.bravo = true; });
  const lb = (await loads()).filter(x => x === 'bravo').length, ib = (await installs()).length;
  await page.click(row('bravo') + ' .store-inst-btn'); await wait(40);
  await page.click(row('bravo') + ' > div:nth-child(2)'); await wait(60);
  check('Install, then the row opened before the answer: storeLoadApp was asked once for the two', (await loads()).filter(x => x === 'bravo').length === lb + 1 && (await modalShown()));
  await release('bravo'); await wait(100);
  const bm = await ev(() => ({ body: document.getElementById('storeDetailBody').innerText, dis: document.getElementById('storeInstallBtn').disabled, txt: document.getElementById('storeInstallProgress').innerText }));
  check('one answer serves both: the install starts once and the window shows the details, its button held, with the progress', (await installs()).length === ib + 1 && /Full bravo/.test(bm.body) && bm.dis && bm.txt === 'Starting…', JSON.stringify(bm));
  await prog({ pkg: 'com.bravo', stage: 'error', percent: 0, message: 'stop' }); await wait(30);
  await ev(() => closeStoreModal());
  await ev(() => { window.__hold.echo = true; });
  const le = (await loads()).filter(x => x === 'echo').length;
  await page.click(row('echo') + ' .store-inst-btn'); await wait(40);
  await page.click(row('echo') + ' > div:nth-child(2)'); await wait(60);
  await release('echo'); await wait(100);
  const em = await ev(() => document.getElementById('storeDetailBody').innerText);
  r = await rowInfo('echo');
  check('an error answer is shown on the row and in the window, the row is usable again, and there was one request', (await loads()).filter(x => x === 'echo').length === le + 1 && /Could not load this app: boom/.test(em) && r.err && !r.dis && r.status === 'Could not load this app: boom', JSON.stringify([em, r]));
  await ev(() => closeStoreModal());
  // the other way round: the window is opened first, Install is tapped on the row while it loads
  await ev(() => { delete storeDetailCache.golf; window.__hold.golf = true; });
  const lg = (await loads()).filter(x => x === 'golf').length, ig = (await installs()).length;
  await page.click(row('golf') + ' > div:nth-child(2)'); await wait(60);
  await ev(() => document.querySelector('#storeList > div[data-slug="golf"] .store-inst-btn').click()); await wait(40);
  check('the window first, then Install on the row: still one request', (await loads()).filter(x => x === 'golf').length === lg + 1);
  await release('golf'); await wait(100);
  check('... and the install starts once', (await installs()).length === ig + 1);
  await prog({ pkg: 'com.golf', stage: 'error', percent: 0, message: 'stop' }); await wait(30);
  await ev(() => closeStoreModal());

  check('no script errors', errors.length === 0, errors.join('|'));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  console.log('all passed');
})().catch(e => { console.error(e); process.exit(1); });
