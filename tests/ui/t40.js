// v5.7 Store: GitHub (renamed from Komi) full catalog + live search + owner/repo; F-Droid repo picker with the whole
// catalog; Orion; ShizuStore categories; a category dropdown in every store; progressive chunks; install progress.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__src = []; window.__refresh = []; window.__install = []; window.__copied = []; window.__metered = false; window.__appActions = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku',
        adbTcp: { connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      storeLoadCatalog() {}, storeLoadApp() {}, storeInstall() {},
      storeSourceCatalog(source, arg) { window.__src.push([source, arg || '']); },
      storeSourceRefresh(source, arg) { window.__refresh.push([source, arg || '']); },
      storeSourceInstall(json) { window.__install.push(JSON.parse(json)); },
      isNetworkMetered() { return window.__metered; },
      copyToClipboard(t) { window.__copied.push(t); }, openAuroraStore() {}, openUrlExternal() {},
      executeAppAction(action, pkg) { window.__appActions.push(action + ':' + pkg); return action === 'launch' ? 'Launched' : 'Settings opened'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const ev = (obj) => page.evaluate(o => onStoreSource(JSON.stringify(o)), obj);
  const text = sel => page.locator(sel).innerText();

  await page.evaluate(() => switchView('store')); await page.waitForTimeout(120);

  // 1) Sub-tabs: Komi is renamed to GitHub.
  const pills = await page.locator('.store-subtab').allInnerTexts();
  console.log('1. sub-tabs ShizuStore/GitHub/F-Droid/Orion (no Komi, no Aurora):', pills.length === 4 && /GitHub/.test(pills[1]) && !pills.some(t => /Komi|Aurora/.test(t)), JSON.stringify(pills));

  // 2) GitHub: opening requests the browse catalog; chunks arrive progressively.
  await page.evaluate(() => switchStoreTab('github')); await page.waitForTimeout(60);
  const askedGh = await page.evaluate(() => window.__src.filter(c => c[0] === 'github' && c[1] === '').length);
  const cats = ['Video', 'Games', 'Privacy & Security', 'Networking', 'Other'];
  const mk = (i) => ({ id: 'own' + i + '/app' + i, key: 'own' + i + '/app' + i, name: 'App ' + String(i).padStart(3, '0'), desc: 'Description of app ' + i, icon: '', owner: 'own' + i, repo: 'app' + i,
    pkg: '', stars: 10000 - i, downloads: 500000 - i * 100, updated: 1700000000000 + i * 86400000, ver: 'v1.' + i, cats: [cats[i % 5]], source: 'github', resolveKind: 'github', assetFilter: '' });
  const chunk1 = Array.from({ length: 100 }, (_, i) => mk(i));
  await ev({ source: 'github', status: 'ok', items: chunk1, append: false, done: false, total: 100 });
  await page.waitForTimeout(60);
  const st1 = await text('#githubStatus');
  const rows1 = await page.locator('#githubList .store-row').count();
  console.log('2. github requested; first chunk shows 60 of 100 with loading note:', askedGh === 1 && rows1 === 60 && /Loading more… 100 apps so far/.test(st1), '|', rows1, JSON.stringify(st1));
  const chunk2 = Array.from({ length: 100 }, (_, i) => mk(100 + i));
  await ev({ source: 'github', status: 'ok', items: chunk2, append: true, done: true, total: 200 });
  await page.waitForTimeout(60);
  const cnt = await text('#githubCount');
  console.log('   second chunk appends (200 total), loading note gone:', /60? ?\/ ?200|200 apps/.test(cnt) === true || /200/.test(cnt), JSON.stringify(cnt), '| status hidden:', !(await page.locator('#githubStatus').isVisible()));

  // 3) "Show more" reveals the next page.
  const moreTxt = await text('#githubMore');
  await page.locator('#githubMore button').click(); await page.waitForTimeout(40);
  const rows2 = await page.locator('#githubList .store-row').count();
  console.log('3. show-more: label + next 60 rows:', /Show 60 more · 140 left/.test(moreTxt) && rows2 === 120, JSON.stringify(moreTxt), rows2);

  // 4) Category dropdown with counts; filtering; categories survive more data.
  const opts = await page.locator('#githubCat option').allInnerTexts();
  console.log('4. category dropdown has All + 5 categories with counts, "Other" last:', opts.length === 6 && /^All categories \(200\)/.test(opts[0]) && /^Other \(40\)/.test(opts[5]) && /^(Video|Games|Privacy & Security|Networking) \(40\)/.test(opts[1]), JSON.stringify(opts));
  await page.selectOption('#githubCat', 'Games'); await page.waitForTimeout(40);
  const gamesRows = await page.locator('#githubList .store-row').count();
  const gamesCnt = await text('#githubCount');
  const onlyGames = await page.evaluate(() => Array.from(document.querySelectorAll('#githubList .sr-chip.cat')).every(c => c.innerText === 'Games'));
  console.log('   filter Games -> 40 apps (shows 40), every card tagged Games:', gamesRows === 40 && /40 \/ 200/.test(gamesCnt) && onlyGames, JSON.stringify(gamesCnt));
  await ev({ source: 'github', status: 'ok', items: [mk(500)], append: true, done: true, total: 201 });
  await page.waitForTimeout(40);
  const stillGames = await page.evaluate(() => document.getElementById('githubCat').value);
  console.log('   selected category preserved when more apps arrive:', stillGames === 'Games');
  await page.selectOption('#githubCat', '');

  // 5) Search + sort.
  await page.fill('#githubSearch', 'app 007'); await page.waitForTimeout(40);
  const found = await page.locator('#githubList .store-row').count();
  const firstName = await page.locator('#githubList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  console.log('5. search "app 007" -> 1 match:', found === 1 && firstName === 'App 007');
  await page.fill('#githubSearch', ''); await page.waitForTimeout(30);
  await page.selectOption('#githubSort', 'name'); await page.waitForTimeout(30);
  const nameFirst = await page.locator('#githubList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  await page.selectOption('#githubSort', 'stars'); await page.waitForTimeout(30);
  const starFirst = await page.locator('#githubList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  await page.selectOption('#githubSort', 'updated'); await page.waitForTimeout(30);
  const updFirst = await page.locator('#githubList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  console.log('   sort name -> App 000 first, stars -> App 000 (most), updated -> newest (App 500):', nameFirst === 'App 000' && starFirst === 'App 000' && updFirst === 'App 500', nameFirst, starFirst, updFirst);
  await page.selectOption('#githubSort', 'stars');

  // 6) Card chips: stars, downloads, version.
  const chips = await page.locator('#githubList .store-row').first().locator('.sr-chip').allInnerTexts();
  console.log('6. card chips (stars/downloads/version/category):', chips.some(c => /^10k stars$/.test(c)) && chips.some(c => /^500k downloads$/.test(c)) && chips.some(c => /^v1\.0$/.test(c)) && chips.some(c => c === 'Video'), JSON.stringify(chips));

  // 7) Install from a list card: sends the item, shows live progress, then done.
  await page.locator('#githubList .store-row').first().locator('.store-inst-btn').click(); await page.waitForTimeout(40);
  const inst = await page.evaluate(() => window.__install[0]);
  console.log('7. install sends key/owner/repo/resolveKind:', inst && inst.key === 'own0/app0' && inst.owner === 'own0' && inst.repo === 'app0' && inst.resolveKind === 'github');
  await page.evaluate(() => onStoreInstallProgress(JSON.stringify({ pkg: 'own0/app0', stage: 'downloading', percent: 40, message: '3 MB' })));
  const prog = await page.locator('#githubList .store-row').first().locator('.sr-status').innerText();
  await page.evaluate(() => onStoreInstallProgress(JSON.stringify({ pkg: 'own0/app0', stage: 'done', percent: 100, message: 'Installed App 000 v1.0.', installedPkg: 'com.example.app000', label: 'App 000' })));
  const done = await page.locator('#githubList .store-row').first().locator('.sr-status').innerText();
  const reEnabled = await page.locator('#githubList .store-row').first().locator('.store-inst-btn').isEnabled();
  console.log('   progress keyed by repo (no package name yet): downloading -> done, button re-enabled:', /40%/.test(prog) && /Installed App 000/.test(done) && reEnabled, JSON.stringify(prog), JSON.stringify(done));
  const actionLabels = await page.locator('#commandResultsActions button').allInnerTexts();
  console.log('   the result sheet opens too, with Launch Application and Application Settings for the REAL installed package (not the repo key):', await page.evaluate(() => document.getElementById('commandResultsModal').classList.contains('show')), JSON.stringify(actionLabels));
  await page.locator('#commandResultsActions button', { hasText: 'Launch Application' }).click();
  await page.locator('#commandResultsActions button', { hasText: 'Application Settings' }).click();
  console.log('   both act on com.example.app000, never on the repo key own0/app0:', JSON.stringify(await page.evaluate(() => window.__appActions)));
  await page.evaluate(() => closeCommandResultsModal());

  // 8) Live search + merge, even for results the local filter would hide.
  await page.fill('#githubSearch', 'zzqq'); await page.waitForTimeout(40);
  const prompt = await text('#githubExtra');
  await page.press('#githubSearch', 'Enter'); await page.waitForTimeout(40);
  const live = await page.evaluate(() => window.__src.filter(c => c[0] === 'github' && c[1] === 'q:zzqq').length);
  await ev({ source: 'github', arg: 'q:zzqq', status: 'ok', search: true, query: 'zzqq', items: [
    { id: 'x/hidden', key: 'x/hidden', name: 'Totally Different', desc: 'matched on readme', owner: 'x', repo: 'hidden', cats: ['Other'], stars: 5, source: 'github', resolveKind: 'github' }], append: false, done: true });
  await page.waitForTimeout(50);
  const mergedRows = await page.locator('#githubList .store-row').count();
  const mergedName = await text('#githubList .store-row');
  const note = await text('#githubStatus');
  console.log('8. live search: prompt shown, bridge asked q:zzqq, hit merged + visible:', /Search/.test(prompt) && live === 1 && mergedRows === 1 && /Totally Different/.test(mergedName) && /Found 1 more/.test(note), '|', JSON.stringify(note));

  // 9) owner/repo installs any GitHub project directly.
  await page.fill('#githubSearch', 'someone/cool-app'); await page.waitForTimeout(40);
  const adhoc = await page.locator('#githubExtra').innerText();
  await page.locator('#bt-github-adhoc').click(); await page.waitForTimeout(40);
  const adhocInst = await page.evaluate(() => window.__install[window.__install.length - 1]);
  await page.evaluate(() => onStoreInstallProgress(JSON.stringify({ pkg: 'someone/cool-app', stage: 'resolving', percent: -1, message: 'Finding the latest release of cool-app…' })));
  const adhocSt = await text('#st-github-adhoc');
  console.log('9. owner/repo prompt installs directly:', /someone\/cool-app/.test(adhoc) && adhocInst.owner === 'someone' && adhocInst.repo === 'cool-app' && adhocInst.resolveKind === 'github' && /Finding the latest release/.test(adhocSt), '|', JSON.stringify(adhocSt));
  await page.fill('#githubSearch', '');

  // 10) Fallback note when Komi's server is unreachable.
  await ev({ source: 'github', status: 'ok', items: chunk1.slice(0, 5), append: false, done: true, fallback: true, note: 'Couldn’t reach the Komi catalog server, so this is its offline mirror (5 popular apps).' });
  await page.waitForTimeout(40);
  console.log('10. offline-mirror fallback explained to the user:', /offline mirror/.test(await text('#githubStatus')));

  // 11) Refresh uses the cache-bypassing bridge call.
  await page.locator('#substore-github button', { hasText: 'Refresh' }).click(); await page.waitForTimeout(40);
  console.log('11. Refresh calls storeSourceRefresh(github):', (await page.evaluate(() => window.__refresh.filter(c => c[0] === 'github').length)) === 1);

  // ===== F-Droid =====
  await page.evaluate(() => switchStoreTab('fdroid')); await page.waitForTimeout(60);
  const askedRepos = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repos').length);
  const repos = [
    { id: 'fdroid', name: 'F-Droid', address: 'https://f-droid.org/repo', fingerprint: '43238D51', desc: 'Main repo' },
    { id: 'izzyondroid', name: 'IzzyOnDroid', address: 'https://apt.izzysoft.de/fdroid/repo', fingerprint: '3BF0D6AB', desc: 'Izzy' },
    { id: 'guardian', name: 'Guardian Project', address: 'https://guardianproject.info/fdroid/repo', fingerprint: 'B7C2EEFD', desc: 'Privacy' } ];
  await ev({ source: 'fdroid-repos', status: 'ok', items: repos, append: false, done: true, total: 3 });
  await page.waitForTimeout(60);
  const repoOpts = await page.locator('#fdroidRepo option').allInnerTexts();
  const askedRepo = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo'));
  console.log('12. repo list requested; select has repos, F-Droid default; main catalog requested:', askedRepos === 1 && JSON.stringify(repoOpts) === JSON.stringify(['F-Droid', 'IzzyOnDroid', 'Guardian Project']) && askedRepo.length === 1 && askedRepo[0][1] === 'https://f-droid.org/repo');

  await ev({ source: 'fdroid-repo', arg: 'https://f-droid.org/repo', status: 'progress', message: 'Reading the catalog… 12.3 MB · 840 apps' });
  const fdProg = await text('#fdroidStatus');
  const fcats = ['Internet', 'Games', 'System', 'Multimedia', 'Security'];
  const fm = (i) => ({ id: 'org.fd.app' + i, key: 'org.fd.app' + i, pkg: 'org.fd.app' + i, name: 'FApp ' + String(i).padStart(4, '0'), desc: 'F-Droid app ' + i, icon: 'https://f-droid.org/repo/x.png',
    ver: '1.' + i, vc: i, size: 1048576 * (i % 9 + 1), apkUrl: 'https://f-droid.org/repo/org.fd.app' + i + '_' + i + '.apk', sha256: 'ab'.repeat(32), cats: [fcats[i % 5]], updated: 1700000000000 + i * 1000, source: 'fdroid', resolveKind: 'direct' });
  const fdItems = Array.from({ length: 450 }, (_, i) => fm(i));
  await ev({ source: 'fdroid-repo', arg: 'https://f-droid.org/repo', status: 'ok', items: fdItems.slice(0, 400), append: false, done: false, total: 450 });
  await ev({ source: 'fdroid-repo', arg: 'https://f-droid.org/repo', status: 'ok', items: fdItems.slice(400), append: true, done: true, total: 450, repoName: 'F-Droid', format: 'v2', skipped: 12 });
  await page.waitForTimeout(80);
  const fdCount = await text('#fdroidCount');
  const fdFirst = await page.locator('#fdroidList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  const fdStatus = await text('#fdroidStatus');
  console.log('13. progress text shown while reading:', /Reading the catalog… 12\.3 MB · 840 apps/.test(fdProg));
  console.log('    whole catalog loaded (450 apps), newest first by default, hidden-apps note:', /450 apps/.test(fdCount) && fdFirst === 'FApp 0449' && /12 apps hidden/.test(fdStatus), '|', JSON.stringify(fdCount), fdFirst, JSON.stringify(fdStatus));
  const fOpts = await page.locator('#fdroidCat option').allInnerTexts();
  await page.selectOption('#fdroidCat', 'Security'); await page.waitForTimeout(40);
  const secRows = await page.locator('#fdroidList .store-row').count();
  console.log('    category dropdown (5 + All) and filter Security -> 60 shown of 90:', fOpts.length === 6 && secRows === 60 && /90 \/ 450/.test(await text('#fdroidCount')), JSON.stringify(fOpts));
  await page.selectOption('#fdroidCat', '');

  // 14) Switching repo requests the other catalog; a late answer for the old one is ignored; switching back is instant (cached).
  await page.selectOption('#fdroidRepo', 'izzyondroid'); await page.waitForTimeout(50);
  const izzyAsked = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo' && c[1] === 'https://apt.izzysoft.de/fdroid/repo').length);
  await ev({ source: 'fdroid-repo', arg: 'https://f-droid.org/repo', status: 'ok', items: [fm(9999)], append: true, done: true, total: 451 });   // stale
  await ev({ source: 'fdroid-repo', arg: 'https://apt.izzysoft.de/fdroid/repo', status: 'ok', items: [fm(1), fm(2)], append: false, done: true, total: 2, repoName: 'IzzyOnDroid', format: 'v2', skipped: 0 });
  await page.waitForTimeout(60);
  const izzyRows = await page.locator('#fdroidList .store-row').count();
  const noStale = !(await page.locator('#fdroidList').innerText()).includes('FApp 9999');
  const before = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo').length);
  await page.selectOption('#fdroidRepo', 'fdroid'); await page.waitForTimeout(50);
  const after = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo').length);
  const backRows = await page.locator('#fdroidList .store-row').count();
  console.log('14. switch repo asks Izzy, stale answer ignored, own list shown:', izzyAsked === 1 && izzyRows === 2 && noStale);
  console.log('    switching back to F-Droid is served from the in-memory cache (no new request):', before === after && backRows === 60);

  // 15) Install a F-Droid app: sends the checksum + direct url.
  await page.locator('#fdroidList .store-row').first().locator('.store-inst-btn').click(); await page.waitForTimeout(40);
  const fi = await page.evaluate(() => window.__install[window.__install.length - 1]);
  console.log('15. F-Droid install carries apkUrl + sha256 + pkg:', fi.resolveKind === 'direct' && /f-droid\.org\/repo\/org\.fd\.app449_449\.apk$/.test(fi.apkUrl) && fi.sha256.length === 64 && fi.pkg === 'org.fd.app449');

  // 16) Repo directory view: all repos, Browse switches back to apps; copy address+fingerprint.
  await page.locator('#fdroidReposBtn').click(); await page.waitForTimeout(40);
  const dirRows = await page.locator('#fdroidList .store-row').count();
  const filtersHidden = !(await page.locator('#fdroidFilters').isVisible());
  await page.locator('#fdroidList .store-row', { hasText: 'Guardian Project' }).locator('button', { hasText: 'Browse apps' }).click(); await page.waitForTimeout(50);
  const guardianAsked = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo' && c[1] === 'https://guardianproject.info/fdroid/repo').length);
  await page.locator('#fdroidCopyBtn').click(); await page.waitForTimeout(30);
  const copied = await page.evaluate(() => window.__copied[window.__copied.length - 1]);
  console.log('16. repo directory lists 3 repos (filters hidden), Browse loads that repo:', dirRows === 3 && filtersHidden && guardianAsked === 1 && (await page.locator('#fdroidRepo').inputValue()) === 'guardian');
  console.log('    copy address & fingerprint:', copied === 'https://guardianproject.info/fdroid/repo\nB7C2EEFD', JSON.stringify(copied));
  await ev({ source: 'fdroid-repo', arg: 'https://guardianproject.info/fdroid/repo', status: 'error', error: 'this repository has no browsable catalog (index-v2: HTTP 404, index-v1: HTTP 404)' });
  await page.waitForTimeout(40);
  console.log('    error from a repo is shown plainly:', /no browsable catalog/.test(await text('#fdroidStatus')));

  // 17) Metered connection: asks before downloading a catalog.
  await page.evaluate(() => { window.__metered = true; });
  const mAsk0 = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo').length);
  await page.selectOption('#fdroidRepo', 'izzyondroid'); await page.waitForTimeout(40);   // cached from earlier -> no prompt needed
  await page.selectOption('#fdroidRepo', 'fdroid'); await page.waitForTimeout(40);
  await page.evaluate(() => { storeSrc.fdroid.cache = {}; });   // simulate a fresh session
  await page.selectOption('#fdroidRepo', 'guardian'); await page.waitForTimeout(40);
  const mAsk1 = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo').length);
  const mPrompt = await text('#fdroidExtra');
  await page.locator('#fdroidExtra button').click(); await page.waitForTimeout(40);
  const mAsk2 = await page.evaluate(() => window.__src.filter(c => c[0] === 'fdroid-repo').length);
  console.log('17. metered: prompt shown, no download until confirmed, then requested:', mAsk1 === mAsk0 && /metered connection/.test(mPrompt) && mAsk2 === mAsk1 + 1, mAsk0, mAsk1, mAsk2);

  // ===== Orion =====
  await page.evaluate(() => switchStoreTab('orion')); await page.waitForTimeout(50);
  const orionAsked = await page.evaluate(() => window.__src.filter(c => c[0] === 'orion').length);
  await ev({ source: 'orion', status: 'ok', items: [
    { id: 'yt', key: 'yt', name: 'YouTube Morphe', desc: 'ad-free', icon: '', version: 'Latest', pkg: 'app.morphe.android.youtube', cats: ['Media'], author: 'Morphe', owner: 'RookieEnough', repo: 'Morphe-AutoBuilds', assetFilter: 'youtube-universal', resolveKind: 'github', source: 'orion' },
    { id: 'zed', key: 'zed', name: 'Zed Utility', desc: 'tool', icon: '', version: '2.0', pkg: 'z.z', cats: ['Utility', 'Automation'], author: 'Z', resolveKind: 'direct', apkUrl: 'https://x.y/z.apk', source: 'orion' } ], append: false, done: true, total: 2 });
  await page.waitForTimeout(50);
  const oOpts = await page.locator('#orionCat option').allInnerTexts();
  await page.selectOption('#orionSort', 'name-desc'); await page.waitForTimeout(30);
  const oFirst = await page.locator('#orionList .store-row').first().locator('div[style*="font-weight:700"]').first().innerText();
  await page.selectOption('#orionCat', 'Automation'); await page.waitForTimeout(30);
  console.log('18. orion requested, categories (incl. multi-tag) + Z–A sort + filter:', orionAsked === 1 && oOpts.length === 4 && oFirst === 'Zed Utility' && (await page.locator('#orionList .store-row').count()) === 1, JSON.stringify(oOpts));

  // ===== ShizuStore =====
  await page.evaluate(() => switchStoreTab('shizu')); await page.waitForTimeout(50);
  await page.evaluate(() => onStoreCatalog(JSON.stringify({ status: 'ok', base: 'https://s.example', total: 130, items:
    Array.from({ length: 130 }, (_, i) => ({ slug: 's' + i, name: 'SApp ' + i, description: 'd', packageName: 'p.s' + i, stars: 200 - i, categorySlug: i % 2 ? 'system-tools' : 'automation' })) })));
  await page.waitForTimeout(60);
  const sOpts = await page.locator('#storeCat option').allInnerTexts();
  const sRows = await page.locator('#storeList > div').count();
  await page.selectOption('#storeCat', 'System Tools'); await page.waitForTimeout(40);
  const sFiltered = await page.locator('#storeList > div').count();
  const sMore = await text('#storeMore');
  console.log('19. ShizuStore: category dropdown from slugs, show-more paging, filter:', sOpts.length === 3 && /System Tools \(65\)/.test(sOpts.join('|')) && sRows === 60 && sFiltered === 60 && /Show 5 more/.test(sMore), JSON.stringify(sOpts), sRows, sFiltered, JSON.stringify(sMore));

  // ===== Aurora: no sub-tab any more (the Play Store card of the Updates tab still opens it) =====
  console.log('20. no Aurora sub-tab or pane in App Stores:', (await page.locator('.store-subtab[data-store="aurora"]').count()) === 0 && (await page.locator('#substore-aurora').count()) === 0);
  console.log('    the Updates tab keeps its Play Store card with the Aurora button:', (await page.locator('#openAuroraBtn').count()) === 1);

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
