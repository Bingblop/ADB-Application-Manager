// Settings, "Try the Kotlin build (beta)": a card for the separate Kotlin app. Installed: it says so with the version and a button opens the app. Not installed: it says so and,
// when a test build is in a release, offers "Download and install" (which only asks the app to start: the page sends no package, address or checksum, and the install is the
// app's existing Store install with its progress and confirmation); with no release it says "No Kotlin test build has been published yet." and offers the releases page; when the
// lookup fails it says so. A note says the Kotlin build is a separate app with fewer features that does not replace this one. The text is translated.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const PKG = 'com.example.kotlinapp';   // what the mock app reports as the Kotlin build's package id (the page never names it)
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts, withBridge) => {
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([pkg, bridge]) => {
      window.__calls = []; window.__opened = []; window.__toasts = [];
      // what the app would answer to the next check
      window.__kb = { installed: false, installedVersion: '', release: 'ok', version: '8.0.0', tag: 'kotlin-8.0.0', size: 18000000, update: false, error: '', openResult: true };
      const base = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        openUrl(u) { window.__opened.push(u); },
        storeInstall(...a) { window.__calls.push(['storeInstall', ...a]); },
      };
      const kotlin = {
        kotlinBuildCheck() {
          window.__calls.push(['kotlinBuildCheck', ...arguments]);
          const k = window.__kb;
          const seq = (window.__kbSeq = (window.__kbSeq || 0) + 1);
          const o = { seq, package: pkg, page: 'https://github.com/Bingblop/ADB-Application-Manager/releases', installed: k.installed, installedVersion: k.installedVersion, update: k.update, release: k.release };
          if (k.release === 'ok') { o.version = k.version; o.tag = k.tag; o.size = k.size; }
          if (k.release === 'error') o.error = k.error;
          if (window.__kbNever) return seq;
          setTimeout(() => window.onKotlinBuild(JSON.stringify(o)), window.__kbDelay || 5);
          return seq;
        },
        kotlinBuildOpen() { window.__calls.push(['kotlinBuildOpen', ...arguments]); return window.__kb.openResult; },
        kotlinBuildInstall() { window.__calls.push(['kotlinBuildInstall', ...arguments]); },
        kotlinBuildReleases() { window.__calls.push(['kotlinBuildReleases', ...arguments]); },
      };
      window.AndroidBridge = bridge ? Object.assign(base, kotlin) : base;
    }, [PKG, withBridge !== false]);
    await page.goto(PAGE); await page.waitForTimeout(700);
    return page;
  };

  const page = await mkPage({});
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });
  const vis = id => ev(i => { const e = document.getElementById(i); return !!e && getComputedStyle(e).display !== 'none' && e.offsetParent !== null; }, id);
  const txt = id => ev(i => document.getElementById(i).innerText.replace(/\s+/g, ' ').trim(), id);
  const calls = () => ev(() => window.__calls.map(c => c[0] + (c.length > 1 ? '(' + c.slice(1).join(',') + ')' : '')));
  const countOf = name => ev(n => window.__calls.filter(c => c[0] === n).length, name);
  const state = async () => ({ status: await txt('kbStatus'), latest: (await vis('kbLatest')) ? await txt('kbLatest') : '', open: await vis('kbOpenBtn'), install: await vis('kbInstallBtn'), page: await vis('kbPageBtn'), again: await vis('kbCheckBtn') });
  const set = o => ev(o2 => Object.assign(window.__kb, o2), o);
  const settings = async () => { await ev(() => switchView('prefs')); await sleep(250); };
  const again = async () => { await ev(() => kbCheck(true)); await sleep(150); };
  const progress = (stage, percent, message) => ev(a => window.onStoreInstallProgress(JSON.stringify({ pkg: a[0], stage: a[1], percent: a[2], message: a[3], installedPkg: '', label: '' })), [PKG, stage, percent, message]);

  // ---- 1. the card in Settings: its title, the note, and the buttons ----
  await settings();
  const s0 = await state();
  const card = await ev(() => { const c = document.getElementById('kotlinBuildCard'); const prefs = document.getElementById('view-prefs'); return { inPrefs: prefs.contains(c), shown: getComputedStyle(c).display !== 'none', title: c.querySelector('.color-card-title').innerText, note: c.querySelector('.color-card-subtitle').innerText }; });
  check('1. Settings has the card "Try the Kotlin build (beta)", with the note that it is a separate app', card.inPrefs && card.shown && card.title === 'Try the Kotlin build (beta)' && /separate app/.test(card.note), JSON.stringify(card));
  check('   the note says: its own app id, fewer features (no Morphe, File Manager, archives, no real ADB, Shizuku or root yet), does not replace or update this app, keep both, and the Kotlin version has the swap back',
    /way to move to the Kotlin version/.test(card.note) && /own app id/.test(card.note) && /smaller feature set/.test(card.note) && /no Morphe, File Manager or archives/.test(card.note) && /no real ADB, Shizuku or root yet/.test(card.note) && /does not replace or update this app/.test(card.note) && /you keep both/.test(card.note) && /same swap back to this one/.test(card.note), card.note);
  check('   opening Settings asked the app once', (await countOf('kotlinBuildCheck')) === 1, JSON.stringify(await calls()));

  const live = await ev(() => { const c = document.getElementById('kotlinBuildCard'); const at = e => [e.getAttribute('role'), e.getAttribute('aria-live')].join('/'); return { three: ['kbStatus', 'kbLatest', 'kbProgress'].map(i => at(document.getElementById(i))), card: at(c), title: at(c.querySelector('.color-card-title')), note: at(c.querySelector('.color-card-subtitle')), btns: [...c.querySelectorAll('button')].map(at), others: [...c.querySelectorAll('[aria-live], [role=status], [role=alert]')].map(e => e.id).join() }; });
  check('1b. the three status lines are polite live regions (role status), so a screen reader announces a new result or progress line; the card, its title, note and buttons are not', live.three.join() === 'status/polite,status/polite,status/polite' && live.card === '/' && live.title === '/' && live.note === '/' && live.btns.every(x => x === '/') && live.others === 'kbStatus,kbLatest,kbProgress', JSON.stringify(live));

  // ---- 2. not installed, a test build in a release ----
  check('2. not installed, a test build published: "Not installed." and "A test build is available: 8.0.0."; Download and install and Check again, no Open, no releases page',
    s0.status === 'Not installed.' && s0.latest === 'A test build is available: 8.0.0.' && !s0.open && s0.install && !s0.page && s0.again, JSON.stringify(s0));
  check('   the buttons read: Download and install | Check again', await ev(() => [...document.querySelectorAll('#kotlinBuildCard .mode-btn-row button')].filter(x => x.offsetParent !== null).map(x => x.innerText).join(' | ')) === 'Download and install | Check again');
  await ev(() => document.getElementById('kbInstallBtn').click()); await sleep(100);
  const c1 = await calls();
  check('3. Download and install asks the app to install, with no argument (the page sends no package, address or checksum), and uses no other install call', c1.filter(x => /^kotlinBuildInstall/.test(x)).join() === 'kotlinBuildInstall' && !c1.some(x => /storeInstall|installApk|installSelected|installUpdate|installSelfUpdate/.test(x)), JSON.stringify(c1));
  const p1 = await ev(() => ({ btnDisabled: document.getElementById('kbInstallBtn').disabled, prog: getComputedStyle(document.getElementById('kbProgress')).display !== 'none', progText: document.getElementById('kbProgress').innerText }));
  check('   the button is off while it runs and a progress line says Starting', p1.btnDisabled && p1.prog && p1.progText === 'Starting…', JSON.stringify(p1));
  await ev(() => kbInstall()); await sleep(50);
  check('   a second press while it runs does nothing', (await countOf('kotlinBuildInstall')) === 1);

  // the app's own progress (the same events the Store installs send, keyed by the package) is shown in the card
  await progress('resolving', -1, 'Finding the latest Kotlin test build…');
  check('4. the app\'s progress is shown in the card: finding the build', (await txt('kbProgress')) === 'Finding the latest Kotlin test build…');
  await progress('downloading', 40, '7 MB');
  check('   downloading with a percent', (await txt('kbProgress')) === 'Downloading the Kotlin build… 40%', await txt('kbProgress'));
  await progress('confirming', 100, 'Waiting for your OK to install ADB Application Manager (Kotlin build)…');
  check('   waiting for the app\'s own question (the native confirmation) before a privileged install', (await txt('kbProgress')) === 'Waiting for your OK to install the Kotlin build…' && (await ev(() => document.getElementById('kbInstallBtn').disabled)));
  check('   the page has no way to answer that question (no install, confirm or accept call was made)', (await calls()).every(x => /^kotlinBuild(Check|Install)/.test(x)), JSON.stringify(await calls()));
  await progress('error', 0, 'Install cancelled.');
  const cancelled = await ev(() => ({ prog: document.getElementById('kbProgress').innerText, btn: document.getElementById('kbInstallBtn').disabled, shown: getComputedStyle(document.getElementById('kbInstallBtn')).display !== 'none', err: !!document.querySelector('#kbProgress .is-err') }));
  check('5. the person said no (or it failed): the reason is shown in red (its own words) and Download and install is back', cancelled.prog === 'Install cancelled.' && cancelled.err && !cancelled.btn && cancelled.shown, JSON.stringify(cancelled));
  await ev(() => document.getElementById('kbInstallBtn').click()); await sleep(80);
  check('   and it can be started again', (await countOf('kotlinBuildInstall')) === 2);

  // done: the card looks again and now shows the app installed
  await set({ installed: true, installedVersion: '8.0.0-Pro', update: false });
  await ev(() => { window.__kbDelay = 600; });
  await progress('done', 100, 'Installed ADB Application Manager (Kotlin build) 8.0.0-Pro.');
  await sleep(150);
  const busy = await ev(() => ({ btn: document.getElementById('kbInstallBtn').disabled, shown: getComputedStyle(document.getElementById('kbInstallBtn')).display !== 'none', text: document.getElementById('kbProgress').innerText }));
  await ev(() => kbInstall()); await ev(() => document.getElementById('kbInstallBtn').click()); await sleep(50);
  check('6a. at "done", while the look after the install is out, Download and install stays off (the old answer still offers the build) and a press starts nothing', busy.btn && busy.shown && busy.text === 'Installed the Kotlin build.' && (await countOf('kotlinBuildInstall')) === 2, JSON.stringify(busy));
  await sleep(700); await ev(() => { window.__kbDelay = 0; });
  const s1 = await state();
  check('6. when the install is done the card looks again: "Installed, version 8.0.0-Pro." with Open the Kotlin build, no Download and install', s1.status === 'Installed, version 8.0.0-Pro.' && s1.open && !s1.install && s1.latest === '', JSON.stringify(s1));
  check('   the check after it was asked of the app, not worked out by the page', (await countOf('kotlinBuildCheck')) === 2);

  // the system installer case (no privileged mode): "opened" waits until the person is back
  await set({ installed: false, installedVersion: '' });
  await again();
  await ev(() => document.getElementById('kbInstallBtn').click()); await sleep(60);
  await progress('opened', -1, 'Confirm the install of ADB Application Manager (Kotlin build).');
  const before = await countOf('kotlinBuildCheck');
  check('7. with the system installer open ("opened") the card waits; the button is back', (await txt('kbProgress')) === 'Confirm the install of the Kotlin build.' && !(await ev(() => document.getElementById('kbInstallBtn').disabled)));
  await set({ installed: true, installedVersion: '8.0.0-Pro' });
  await ev(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' }); document.dispatchEvent(new Event('visibilitychange')); }); await sleep(200);
  check('   when the person comes back it looks again and shows the app installed', (await countOf('kotlinBuildCheck')) === before + 1 && (await txt('kbStatus')) === 'Installed, version 8.0.0-Pro.');
  await ev(() => document.dispatchEvent(new Event('visibilitychange'))); await sleep(100);
  check('   and only once (coming back again asks nothing)', (await countOf('kotlinBuildCheck')) === before + 1);

  // ---- 8. installed: Open ----
  await ev(() => { window.__calls.length = 0; });
  await again();
  const s2 = await state();
  check('8. installed, the same version: "Installed, version 8.0.0-Pro." and Open the Kotlin build; nothing to install; no releases page', s2.status === 'Installed, version 8.0.0-Pro.' && s2.latest === '' && s2.open && !s2.install && !s2.page && s2.again, JSON.stringify(s2));
  await ev(() => document.getElementById('kbOpenBtn').click()); await sleep(80);
  check('9. Open the Kotlin build opens the app (no argument: the app knows its package)', (await calls()).filter(x => /kotlinBuildOpen/.test(x)).join() === 'kotlinBuildOpen');
  await set({ openResult: false });
  await ev(() => document.getElementById('kbOpenBtn').click()); await sleep(250);
  check('   if it cannot be opened (it was removed meanwhile) a toast says it is not installed and the card looks again', (await ev(() => document.getElementById('toastMsg').innerText)) === 'The Kotlin build is not installed' && (await countOf('kotlinBuildCheck')) >= 2);
  await set({ openResult: true });

  // ---- 10. installed, a newer test build ----
  await set({ installed: true, installedVersion: '8.0.0-Pro', release: 'ok', version: '8.0.1', tag: 'kotlin-8.0.1', update: true });
  await again();
  const s3 = await state();
  check('10. installed and a newer test build is published: both lines, Open and Download and install', s3.status === 'Installed, version 8.0.0-Pro.' && s3.latest === 'A newer test build is available: 8.0.1.' && s3.open && s3.install && !s3.page, JSON.stringify(s3));

  // ---- 11. no release yet ----
  await set({ installed: false, installedVersion: '', release: 'none', update: false });
  await again();
  const s4 = await state();
  check('11. not installed and no release: "No Kotlin test build has been published yet." and a button to open the releases page; no install, no Open', s4.status === 'Not installed.' && s4.latest === 'No Kotlin test build has been published yet.' && !s4.open && !s4.install && s4.page, JSON.stringify(s4));
  await ev(() => document.getElementById('kbPageBtn').click()); await sleep(80);
  check('12. Open the releases page asks the app to open it (the page names no address) ', (await calls()).filter(x => /kotlinBuildReleases/.test(x)).join() === 'kotlinBuildReleases' && (await ev(() => window.__opened.length)) === 0);
  await set({ installed: true, installedVersion: '8.0.0-Pro', release: 'none' });
  await again();
  const s4b = await state();
  check('   installed and no release: Open stays, no message about releases, no install', s4b.open && !s4b.install && s4b.latest === '' && s4b.status === 'Installed, version 8.0.0-Pro.', JSON.stringify(s4b));

  // ---- 13. the lookup failed ----
  await set({ installed: false, installedVersion: '', release: 'error', error: 'HTTP 403' });
  await again();
  const s5 = await state();
  check('13. the lookup failed: "Could not check for a test build (HTTP 403)." with the releases page and Check again; no install', s5.status === 'Not installed.' && s5.latest === 'Could not check for a test build (HTTP 403).' && s5.page && !s5.install && !s5.open && s5.again, JSON.stringify(s5));
  await set({ installed: true, installedVersion: '8.0.0-Pro', release: 'error', error: 'Unable to resolve host "api.github.com"' });
  await again();
  const s5b = await state();
  check('   installed and the lookup failed: still installed with Open, and the failure in words', s5b.status === 'Installed, version 8.0.0-Pro.' && /^Could not check for a test build \(Unable to resolve host/.test(s5b.latest) && s5b.open && !s5b.install, JSON.stringify(s5b));
  await set({ installed: false, installedVersion: '', release: 'ok', version: '8.0.0', tag: 'kotlin-8.0.0', update: false });
  await again();
  check('   Check again after a failure works: the build is offered', (await state()).install);
  await ev(() => { window.__kbDelay = 400; });
  await ev(() => document.getElementById('kbCheckBtn').click()); await sleep(100);
  check('   Check again is off while it looks', await ev(() => document.getElementById('kbCheckBtn').disabled));
  await sleep(500); await ev(() => { window.__kbDelay = 0; });
  check('   and back after', !(await ev(() => document.getElementById('kbCheckBtn').disabled)));

  // ---- 14. text from the app is shown as text ----
  await set({ release: 'error', error: '<img src=x onerror=window.__pwned=1><b>bold</b>' });
  await again();
  const inj = await ev(() => ({ pwned: !!window.__pwned, tags: document.querySelectorAll('#kotlinBuildCard img, #kotlinBuildCard b').length, latest: document.getElementById('kbLatest').innerText }));
  check('14. an error with markup in it is shown as text, nothing runs', !inj.pwned && inj.tags === 0 && inj.latest.includes('<img src=x'), JSON.stringify(inj));
  await set({ release: 'ok', version: '<b>9</b>', installed: true, installedVersion: '<i>1</i>', update: true });
  await again();
  const inj2 = await ev(() => ({ tags: document.querySelectorAll('#kotlinBuildCard b, #kotlinBuildCard i').length, status: document.getElementById('kbStatus').innerText, latest: document.getElementById('kbLatest').innerText }));
  check('   so are a version and an installed version', inj2.tags === 0 && inj2.status === 'Installed, version <i>1</i>.' && inj2.latest === 'A newer test build is available: <b>9</b>.', JSON.stringify(inj2));
  await set({ installed: false, installedVersion: '', release: 'ok', version: '8.0.0', update: false });

  // ---- 15. not asked on every visit ----
  await again();
  await ev(() => switchView('apps')); await sleep(120);
  const n0 = await countOf('kotlinBuildCheck');
  await settings(); await ev(() => switchView('apps')); await settings();
  check('15. opening Settings again soon after does not ask GitHub again (Check again does)', (await countOf('kotlinBuildCheck')) === n0, 'asked ' + n0 + ' then ' + (await countOf('kotlinBuildCheck')));
  await ev(() => { kbAt = Date.now() - 6 * 60 * 1000; switchView('apps'); }); await sleep(100);
  await settings();
  check('   after five minutes it does', (await countOf('kotlinBuildCheck')) === n0 + 1);

  // ---- 16. translated ----
  await ev(() => LANG_ENGINE.set('de')); await sleep(500);
  await set({ installed: true, installedVersion: '8.0.0-Pro', release: 'ok', version: '8.0.1', update: true });
  await again(); await sleep(250);
  const de = await ev(() => ({ title: document.querySelector('#kotlinBuildCard .color-card-title').innerText, status: document.getElementById('kbStatus').innerText, latest: document.getElementById('kbLatest').innerText, btns: [...document.querySelectorAll('#kotlinBuildCard .mode-btn-row button')].filter(x => x.offsetParent !== null).map(x => x.innerText) }));
  check('16. in German: title, status with the version, the newer-build line and the buttons are translated', de.title === 'Kotlin-Build ausprobieren (Beta)' && de.status === 'Installiert, Version 8.0.0-Pro.' && de.latest === 'Ein neueres Testbuild ist verfügbar: 8.0.1.'
    && de.btns.join('|') === 'Kotlin-Build öffnen|Herunterladen und installieren|Erneut prüfen', JSON.stringify(de));
  await set({ installed: false, installedVersion: '', release: 'none', update: false });
  await again(); await sleep(200);
  const de2 = await ev(() => ({ s: document.getElementById('kbStatus').innerText, l: document.getElementById('kbLatest').innerText, p: document.getElementById('kbPageBtn').innerText }));
  check('   and the no-release state', de2.s === 'Nicht installiert.' && de2.l === 'Es wurde noch kein Kotlin-Testbuild veröffentlicht.' && de2.p === 'Releases-Seite öffnen', JSON.stringify(de2));
  await ev(() => { window.__kb.release = 'ok'; window.__kb.installed = false; });
  await again(); await sleep(200);
  await ev(() => kbInstall()); await sleep(60);
  const deP = {};
  for (const [stage, pct, msg] of [['resolving', -1, 'Finding the latest Kotlin test build…'], ['downloading', 40, '7 MB'], ['downloading', -1, '7 MB'], ['verifying', 100, "Checking the download's SHA-256…"], ['confirming', 100, 'Waiting for your OK to install X…'], ['installing', 100, 'Installing X…']]) {
    await progress(stage, pct, msg); await sleep(120);
    deP[stage + (stage === 'downloading' && pct < 0 ? '-' : '')] = await txt('kbProgress');
  }
  check('16d. in German the install progress lines are the card\'s own translated words, not the English the app sends', deP.resolving === 'Neuester Kotlin-Testbuild wird gesucht …' && deP.downloading === 'Kotlin-Build wird heruntergeladen … 40 %' && deP['downloading-'] === 'Kotlin-Build wird heruntergeladen …'
    && deP.verifying === 'SHA-256 des Downloads wird geprüft …' && deP.confirming === 'Warte auf Ihr OK zur Installation des Kotlin-Builds …' && deP.installing === 'Kotlin-Build wird installiert …', JSON.stringify(deP));
  await progress('error', 0, 'Install cancelled.'); await sleep(120);
  const deC = await txt('kbProgress');
  await progress('opened', -1, 'Confirm the install of X.'); await sleep(120);
  const deO = await txt('kbProgress');
  check('    the cancel and the system-installer lines too', deC === 'Installation abgebrochen.' && deO === 'Bestätigen Sie die Installation des Kotlin-Builds.', JSON.stringify([deC, deO]));
  const langs = {}, prog = {};
  for (const code of ['ar', 'es', 'fr', 'hi', 'id', 'it', 'ja', 'ko', 'pt-BR', 'ru', 'tr', 'zh-CN']) {
    await ev(c => LANG_ENGINE.set(c), code); await sleep(250);
    langs[code] = await ev(() => document.querySelector('#kotlinBuildCard .color-card-title').innerText);
    await progress('installing', 100, 'Installing X…'); await sleep(100);
    prog[code] = await txt('kbProgress');
  }
  check('   in every other language the title is translated (not the English one, and not left empty)', Object.values(langs).every(t => t && t !== 'Try the Kotlin build (beta)'), JSON.stringify(langs));
  check('    and so is a progress line in each of them', Object.values(prog).every(t => t && t !== 'Installing the Kotlin build…'), JSON.stringify(prog));
  await ev(() => LANG_ENGINE.set('en')); await sleep(300);

  // ---- 16b. one question at a time: leaving Settings and coming back while the first lookup is still out asks nothing more ----
  {
    const fp = await mkPage({});
    const f2 = (fn, arg) => fp.evaluate(fn, arg);
    const asked = () => f2(() => window.__calls.filter(c => c[0] === 'kotlinBuildCheck').length);
    await f2(() => { window.__kbDelay = 900; switchView('prefs'); });
    await fp.waitForTimeout(150);
    for (let i = 0; i < 3; i++) { await f2(() => switchView('apps')); await fp.waitForTimeout(60); await f2(() => switchView('prefs')); await fp.waitForTimeout(60); }
    check('16b. the first lookup is still out: leaving Settings and coming back three times asks GitHub nothing more', (await asked()) === 1, 'asked ' + (await asked()));
    check('    and the card says it is checking, with Check again off', (await f2(() => document.getElementById('kbStatus').innerText)) === 'Checking…' && (await f2(() => document.getElementById('kbCheckBtn').disabled)));
    await fp.waitForTimeout(1000);
    const done = await f2(() => ({ status: document.getElementById('kbStatus').innerText, again: !document.getElementById('kbCheckBtn').disabled }));
    check('    when the answer comes the card shows it', done.status === 'Not installed.' && done.again, JSON.stringify(done));
    await f2(() => { switchView('apps'); switchView('prefs'); });
    await fp.waitForTimeout(150);
    check('    and it is then asked at most once in five minutes (the visit after the answer asks nothing)', (await asked()) === 1);
    // an answer that never comes is given up after 90 seconds
    await f2(() => { window.__kbNever = true; kbAt = 0; switchView('apps'); switchView('prefs'); });
    await fp.waitForTimeout(100);
    await f2(() => { switchView('apps'); switchView('prefs'); });
    await fp.waitForTimeout(100);
    check('    a lookup whose answer never comes is not repeated at once either', (await asked()) === 2, 'asked ' + (await asked()));
    await f2(() => { kbPendingAt -= 91 * 1000; switchView('apps'); switchView('prefs'); });
    await fp.waitForTimeout(100);
    check('    but is asked again after 90 seconds', (await asked()) === 3, 'asked ' + (await asked()));
    await fp.close();
  }

  // ---- 16c. an answer to an older question changes nothing: it neither ends the wait nor overwrites the newer state ----
  {
    const sp = await mkPage({});
    const f3 = (fn, arg) => sp.evaluate(fn, arg);
    const asked3 = () => f3(() => window.__calls.filter(c => c[0] === 'kotlinBuildCheck').length);
    // question 1 (the app will say: not installed) is slow; the page gives up waiting after 90 s and asks again; question 2 (installed) is quick
    await f3(() => { window.__kbDelay = 700; switchView('prefs'); });
    await sp.waitForTimeout(100);
    await f3(() => { window.__kb.installed = true; window.__kb.installedVersion = '2.0.0'; window.__kbDelay = 250; kbPendingAt -= 91 * 1000; switchView('apps'); switchView('prefs'); });
    await sp.waitForTimeout(100);
    check('16c. the page asked twice (the first answer was given up on)', (await asked3()) === 2);
    await sp.waitForTimeout(400);
    const mid = await f3(() => ({ status: document.getElementById('kbStatus').innerText, again: !document.getElementById('kbCheckBtn').disabled }));
    check('    the newer answer is shown: Installed, version 2.0.0, and the wait is over', mid.status === 'Installed, version 2.0.0.' && mid.again, JSON.stringify(mid));
    await sp.waitForTimeout(500);
    const late = await f3(() => ({ status: document.getElementById('kbStatus').innerText, open: getComputedStyle(document.getElementById('kbOpenBtn')).display !== 'none' }));
    check('    the older answer (not installed) arrives later and changes nothing', late.status === 'Installed, version 2.0.0.' && late.open, JSON.stringify(late));
    // an older answer must not end the wait of a newer question either
    await f3(() => { window.__kbDelay = 900; kbAt = 0; switchView('apps'); switchView('prefs'); });
    await sp.waitForTimeout(100);
    await f3(() => window.onKotlinBuild(JSON.stringify({ seq: 1, package: 'x', installed: false, release: 'none', page: 'p' })));
    const guard = await f3(() => ({ pending: kbPendingAt > 0, btn: document.getElementById('kbCheckBtn').disabled, status: document.getElementById('kbStatus').innerText }));
    check('    a stale answer arriving while a newer question is out does not end its wait (Check again stays off, the card keeps its text)', guard.pending && guard.btn && guard.status === 'Installed, version 2.0.0.', JSON.stringify(guard));
    await f3(() => { switchView('apps'); switchView('prefs'); });
    await sp.waitForTimeout(100);
    check('    so leaving and coming back still asks nothing more', (await asked3()) === 3, 'asked ' + (await asked3()));
    await sp.close();
  }

  // ---- 16e. the look after the install gets no answer: the wait ends by itself after 90 s ----
  {
    const tp = await mkPage({});
    const f4 = (fn, arg) => tp.evaluate(fn, arg);
    await f4(() => switchView('prefs'));
    await tp.waitForTimeout(200);
    await f4(() => { window.__t90 = null; const orig = window.setTimeout; window.setTimeout = function (f, ms, ...a) { if (ms === 90000) { window.__t90 = f; return 0; } return orig.call(window, f, ms, ...a); }; window.__kbNever = true; });
    await f4(() => kbCheck(true)); await tp.waitForTimeout(50);
    const before = await f4(() => window.__calls.filter(c => c[0] === 'kotlinBuildCheck').length);
    await f4(a => window.onStoreInstallProgress(JSON.stringify({ pkg: a, stage: 'done', percent: 100, message: 'Installed', installedPkg: '', label: '' })), PKG);
    await tp.waitForTimeout(100);
    const askedAtDone = await f4(() => window.__calls.filter(c => c[0] === 'kotlinBuildCheck').length);
    const held = await f4(() => ({ off: document.getElementById('kbInstallBtn').disabled, timer: typeof window.__t90 === 'function' }));
    await f4(() => window.__t90());
    const freed = await f4(() => ({ off: document.getElementById('kbInstallBtn').disabled, shown: getComputedStyle(document.getElementById('kbInstallBtn')).display !== 'none' }));
    check('16e. with no answer to the look after the install, Download and install is held, then freed by itself after 90 s; and the look after the install is asked even while an older question is out', held.off && held.timer && !freed.off && freed.shown && askedAtDone === before + 1, JSON.stringify([held, freed, before, askedAtDone]));
    await tp.close();
  }

  // ---- 17. narrow screens: nothing sticks out sideways ----
  for (const [w, h] of [[320, 640], [360, 800]]) {
    const np = await mkPage({ w, h });
    const e2 = (fn, arg) => np.evaluate(fn, arg);
    await e2(() => { window.__kb.installed = true; window.__kb.installedVersion = '8.0.0-Pro'; window.__kb.update = true; window.__kb.version = '8.0.10'; switchView('prefs'); });
    await np.waitForTimeout(400);
    const g = await e2(() => {
      const c = document.getElementById('kotlinBuildCard'); const r = c.getBoundingClientRect();
      const inside = [...c.querySelectorAll('button, .uad-status')].filter(x => x.offsetParent !== null).every(x => { const q = x.getBoundingClientRect(); return q.left >= r.left - 0.5 && q.right <= r.right + 0.5; });
      return { overflowX: document.documentElement.scrollWidth > window.innerWidth, inside, cardRight: Math.round(r.right), vw: window.innerWidth };
    });
    check(`17. [${w}px] the card and its buttons fit the width`, !g.overflowX && g.inside && g.cardRight <= g.vw, JSON.stringify(g));
    await np.close();
  }

  // ---- 18. a plain browser (no app): the card is not shown ----
  const plain = await mkPage({}, false);
  await plain.evaluate(() => switchView('prefs')); await plain.waitForTimeout(300);
  check('18. without the app (no Kotlin bridge) the card is hidden', await plain.evaluate(() => getComputedStyle(document.getElementById('kotlinBuildCard')).display === 'none'));
  await plain.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
