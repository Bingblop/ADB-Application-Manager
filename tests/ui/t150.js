// v7.12.8: the App Updater is no longer a tab of its own: the tab "Third Party Stores/Updater" has two boxes at the top, Application Stores (the default,
// on the left) and Application Updater (on the right), like the Terminal and the ADB Console.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, { real: true });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });

  // ---- 1. the tab bar ----
  const bar = await ev(() => [...document.querySelectorAll('.tab-btn')].map(b => b.dataset.tab + '=' + b.innerText.replace(/\n/g, '|')));
  check('1. no App Updater tab; the stores tab is "Third Party Stores/Updater"', !bar.some(x => /^updates=/.test(x)) && bar.some(x => x === 'store=Third Party|Stores/Updater') && !bar.some(x => /App\|Stores|App\|Updater/.test(x)), JSON.stringify(bar));

  // ---- 2. the two boxes ----
  await page.click('.tab-btn[data-tab="store"]'); await sleep(300);
  const a = await ev(() => {
    const r = id => document.getElementById(id).getBoundingClientRect();
    const sw = r('storeSwitchStores'), up = r('storeSwitchUpdater'), sub = r('storeSubtabs'), host = document.getElementById('view-store').getBoundingClientRect();
    const vis = id => getComputedStyle(document.getElementById(id)).display !== 'none';
    return { left: sw.left < up.left && Math.abs(sw.top - up.top) < 2, top: sw.top - host.top < 40 && sw.bottom <= sub.top, stores: vis('storePaneStores'), updater: vis('storePaneUpdater'),
      active: document.getElementById('storeSwitchStores').classList.contains('active') && !document.getElementById('storeSwitchUpdater').classList.contains('active'),
      names: [document.getElementById('storeSwitchStores').innerText, document.getElementById('storeSwitchUpdater').innerText] };
  });
  check('2. two boxes at the top, Application Stores on the left (selected) and Application Updater on the right; the stores are shown', a.left && a.top && a.stores && !a.updater && a.active && a.names[0] === 'Application Stores' && a.names[1] === 'Application Updater', JSON.stringify(a));

  // ---- 3. the right box ----
  await page.click('#storeSwitchUpdater'); await sleep(300);
  const u = await ev(() => {
    const vis = id => { const e = document.getElementById(id); return !!e && e.offsetParent !== null; };
    return { stores: vis('storePaneStores'), updater: vis('storePaneUpdater'), self: vis('selfUpdateCard'), check: vis('updCheckBtn'), aurora: vis('openAuroraBtn'), active: document.getElementById('storeSwitchUpdater').classList.contains('active'), tab: currentViewName() };
  });
  check('3. the right box shows the updater (Check for Updates, Play Store apps) and hides the stores', !u.stores && u.updater && u.check && u.aurora && u.active && u.tab === 'store', JSON.stringify(u));
  await page.click('#storeSwitchStores'); await sleep(200);
  check('   the left box brings the stores back', await ev(() => getComputedStyle(document.getElementById('storePaneStores')).display !== 'none' && getComputedStyle(document.getElementById('storePaneUpdater')).display === 'none'));

  // ---- 4. the default is the left box every time the tab is tapped ----
  await page.click('#storeSwitchUpdater'); await sleep(100);
  await page.click('.tab-btn[data-tab="apps"]'); await sleep(200);
  await page.click('.tab-btn[data-tab="store"]'); await sleep(300);
  check('4. leaving and coming back by tapping the tab starts on Application Stores again', await ev(() => document.getElementById('storeSwitchStores').classList.contains('active') && getComputedStyle(document.getElementById('storePaneUpdater')).display === 'none'));

  // ---- 5. the old way in still works ----
  await ev(() => switchView('apps')); await sleep(100);
  const o = await ev(() => { const ok = switchView('updates'); return { ok, view: currentViewName(), updater: getComputedStyle(document.getElementById('storePaneUpdater')).display !== 'none', act: document.getElementById('storeSwitchUpdater').classList.contains('active') }; });
  await ev(() => { openSelfUpdate(); }); await sleep(150);
  const o2 = await ev(() => ({ view: currentViewName(), updater: getComputedStyle(document.getElementById('storePaneUpdater')).display !== 'none' }));
  check('5. what used to open the App Updater (an app\'s Update button, About, "Download") opens its box in this tab', o.ok && o.view === 'store' && o.updater && o.act && o2.view === 'store' && o2.updater, JSON.stringify({ o, o2 }));

  // ---- 6. the count ----
  await ev(() => { updList = [{ pkg: 'a' }, { pkg: 'b' }]; updateUpdatesTabBadge(); });
  const c = await ev(() => ({ tab: document.getElementById('updatesTabBtn').innerText.replace(/\n/g, '|'), box: document.getElementById('storeSwitchUpdater').innerText }));
  check('6. the number of updates waiting shows on the tab and on the Application Updater box', c.tab === 'Third Party|Stores/Updater (2)' && c.box === 'Application Updater (2)', JSON.stringify(c));

  // ---- 7. Settings > Feature List ----
  await ev(() => { switchView('prefs'); featureRender(); }); await sleep(200);
  const f = await ev(() => [...document.querySelectorAll('#featureList .fl-row')].map(r => r.dataset.tab + '=' + r.querySelector('.fl-name').innerText));
  check('7. the Feature List has one row for it, named Third Party Stores/Updater (no App Updater, no App Stores)', f.includes('store=Third Party Stores/Updater') && !f.some(x => /^updates=|App Updater|App Stores/.test(x)), JSON.stringify(f));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
