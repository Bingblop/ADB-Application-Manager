// v7.10.17: a row says "Working…" / "Checking…" with a spinner while its app is changed and checked (visible with the progress messages off), Force stop and Clear data come back with what the
// phone says, and the Debloater's own list has the Removal Levels button.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  const apps = ['alpha', 'bravo'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isFrozen: false, isSuspended: false, isUninstalled: false }));
  await page.addInitScript(a => {
    window.__tokens = {};
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      getUadMatches() { return JSON.stringify({ ok: true, packages: [] }); }, getUadStatus() { return JSON.stringify({ cached: false }); }, getUadInfo() { return '{}'; },
      appActionChecked(action, pkg, token) { window.__tokens[token] = { action, pkg }; return 'started'; },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { switchView('apps'); renderApps(); }); await sleep(300);
  const chip = pkg => ev(pkg => { const c = document.querySelector('.app-card[data-pkg="' + pkg + '"]'); const k = c && c.querySelector(':scope > .row-chk'); return k ? { text: k.innerText.trim(), cls: c.classList.contains('row-checking'), spin: !!k.querySelector('i'), shown: getComputedStyle(k).display !== 'none' } : null; }, pkg);

  // ---- 1. the spinner on the row, with the progress messages off ----
  await ev(() => { kvSet('progress_toasts', false); progressToastsOn = false; window.__toasts = []; const o = showToast; window.showToast = m => { window.__toasts.push(m); o(m); }; });
  await ev(() => quickAction('force_stop', 'com.example.alpha')); await sleep(60);
  let c = await chip('com.example.alpha');
  check('1. the row says "Working…" with a spinner as soon as the change starts', c && c.text === 'Working…' && c.cls && c.spin && c.shown, JSON.stringify(c));
  check('   the other row is left alone', (await chip('com.example.bravo')) === null);
  const token = await ev(() => Object.keys(window.__tokens)[0]);
  await ev(t => window.onAppActionChecking(t), token); await sleep(40);
  c = await chip('com.example.alpha');
  check('   then "Checking…" while the phone is asked', c && c.text === 'Checking…', JSON.stringify(c));
  check('   with the progress messages off no toast says so', await ev(() => !window.__toasts.some(t => /Working on it|Checking the phone/.test(t))), JSON.stringify(await ev(() => window.__toasts)));
  await ev(() => renderApps()); await sleep(60);
  check('   the spinner survives the list being drawn again', (await chip('com.example.alpha')) !== null);
  await ev(t => window.onAppActionChecked(t, { pkg: 'com.example.alpha', action: 'force_stop', success: true, label: 'Stopped', verified: true, output: 'Checked afterwards: stopped.' }), token); await sleep(60);
  check('   gone when the result is in, and the result is shown', (await chip('com.example.alpha')) === null && await ev(() => window.__toasts[window.__toasts.length - 1] === 'Alpha: Stopped'), JSON.stringify(await ev(() => window.__toasts)));

  // ---- 2. a batch marks all its rows ----
  await ev(() => { window.AndroidBridge.appActionBatch = () => 'started'; runBatchAction('freeze', ['com.example.alpha', 'com.example.bravo']); }); await sleep(60);
  check('2. a batch marks every selected row "Working…"', (await chip('com.example.alpha')).text === 'Working…' && (await chip('com.example.bravo')).text === 'Working…');
  await ev(() => window.onAppBatchChecking(2)); await sleep(40);
  check('   and "Checking…" for the look at the phone', (await chip('com.example.bravo')).text === 'Checking…');
  await ev(() => window.onAppBatchDone(JSON.stringify({ ok: true, action: 'freeze', total: 2, done: 2, cancelled: false, rows: [{ pkg: 'com.example.alpha', success: true, output: 'ok' }, { pkg: 'com.example.bravo', success: true, output: 'ok' }] }))); await sleep(80);
  check('   all gone when the results open', (await chip('com.example.alpha')) === null && (await chip('com.example.bravo')) === null);
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); });

  // ---- 3. the Debloater's own list ----
  await ev(() => {
    document.querySelectorAll('.view-content').forEach(v => v.classList.remove('active')); document.getElementById('view-debloater').classList.add('active');          // (the tab's own start-up calls need the native list)
    uadLoaded = true;
    uadPackages = [{ pkg: 'com.example.alpha', name: 'Alpha', removal: 'Recommended', list: 'Oem', brand: 'Samsung', description: 'Does alpha things.', state: 'enabled', neededBy: [], dependencies: [] }];
    uadFilters.removal = new Set(['Recommended', 'Advanced', 'Expert', 'Unsafe']); uadFilters.list = 'all'; uadFilters.state = 'all'; uadFilters.brand = 'all';
    uadExpanded.clear(); renderUadList();
  }); await sleep(150);
  const row0 = await ev(() => ({ rows: document.querySelectorAll('#uadContainer .uad-row').length, btn: !!document.querySelector('#uadContainer .uad-row-btns') }));
  check('3. a row of the Debloater list that is closed has no extra button', row0.rows === 1 && !row0.btn, JSON.stringify(row0));
  await ev(() => { toggleUadExpand('com.example.alpha'); }); await sleep(100);
  const row1 = await ev(() => ({ txt: Array.from(document.querySelectorAll('#uadContainer .uad-row-btns button')).map(x => x.innerText.trim()), expanded: uadExpanded.has('com.example.alpha') }));
  check('   opened, it shows the Removal Levels button', row1.expanded && row1.txt.join() === 'Removal Levels', JSON.stringify(row1));
  await page.click('#uadContainer .uad-row-btns button'); await sleep(150);
  const lv = await ev(() => ({ open: document.getElementById('uadLevelsModal').classList.contains('show'), n: document.querySelectorAll('#uadLevelsBody .uad-level').length, mine: document.getElementById('uadLevelsBody').innerText.replace(/\s+/g, ' ').trim(), tab: document.getElementById('uadLevels').innerText.replace(/\s+/g, ' ').trim(), still: uadExpanded.has('com.example.alpha') }));
  check('   it opens the same sheet with the same four levels as the Debloater tab, and the row stays open', lv.open && lv.n === 4 && lv.mine === lv.tab && lv.still, JSON.stringify(lv).slice(0, 200));
  await ev(() => closeUadLevels());

  // ---- 4. the Connected Devices list ----
  await ev(() => {
    switchView('devices'); document.getElementById('cdWork').style.display = ''; document.getElementById('cdEmpty').style.display = 'none'; cdSub('apps');
    cd.serial = 'watch'; cd.apps = [{ pkg: 'com.w.one', name: 'One', system: false, disabled: false, uninstalled: false }, { pkg: 'com.w.two', name: 'Two', system: false, disabled: false, uninstalled: false }];
    cd.filters.clear(); cdAppsRender();
  }); await sleep(100);
  await ev(() => rowBusySet('com.w.one', 'Checking…', 'd')); await sleep(40);
  const cdChip = await ev(() => { const c = document.getElementById('cdcard_com.w.one'); const k = c && c.querySelector(':scope > .row-chk'); const o = document.getElementById('cdcard_com.w.two'); return { text: k && k.innerText.trim(), other: !!(o && o.querySelector('.row-chk')) }; });
  check('4. the Connected Devices list shows the spinner on the app being checked only', cdChip.text === 'Checking…' && !cdChip.other, JSON.stringify(cdChip));
  await ev(() => { cdAppsRender(); }); await sleep(40);
  check('   and keeps it when the list is drawn again; the same package on this phone\'s list is not marked', await ev(() => !!document.querySelector('#cdcard_com\\.w\\.one > .row-chk') && !document.querySelector('.app-card[data-pkg="com.w.one"]:not([id^="cdcard_"]) .row-chk')));
  await ev(() => rowBusySet('com.w.one', '', 'd'));
  check('   no extra text leaks into the list after the cards ("undefined")', await ev(() => !/undefined/.test(document.getElementById('cdAppList').innerText)));

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
