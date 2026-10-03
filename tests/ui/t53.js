// v5.8 performance: the apps list is drawn a page at a time, search typing on a big list is debounced, logcat draws a tail and skips unchanged polls.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const pk = [];
    for (let i = 0; i < 600; i++) pk.push({ pkg: 'com.example.app' + String(i).padStart(3, '0'), name: (i === 599 ? 'Zeta ' : 'App ') + String(i).padStart(3, '0'), isSystem: i % 3 === 0, isRunning: i % 7 === 0, isFrozen: false, isUninstalled: false, isSuspended: false, versionName: '1.0', versionCode: i, mods: [] });
    const lines = [];
    for (let i = 0; i < 1500; i++) lines.push(`10-03 12:00:${String(i % 60).padStart(2, '0')}.${String(i % 1000).padStart(3, '0')}  100  200 ${'IWE'[i % 3]} Tag${i % 5}: message ${i}`);
    window.__logText = lines.join('\n');
    window.__calls = { logcat: 0 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify(pk); },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      loadSetting() { return ''; }, saveSetting() {},
      getLogcat() { window.__calls.logcat++; return window.__logText; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const sleep = ms => page.waitForTimeout(ms);
  const cards = () => page.locator('#appsListContainer .app-card').count();

  // 1) First page only; the rest on demand.
  console.log('1. 600 apps: the first 250 cards are drawn, with a "Show 250 more · 350 left" row:', (await cards()) === 250 && /Show 250 more · 350 left/.test(await page.locator('#appsMore').innerText()), String(await cards()));
  console.log('   the counters still count every app (not only the drawn ones):', (await page.locator('#statTotal').innerText()) === '600' && (await page.locator('#countAll').innerText()) === '600');
  // The row is also what loads the next page where there is no IntersectionObserver. With one, the page loads it by itself as soon as the row is near
  // the screen, and Playwright scrolls the row into view before it clicks, so a click here would race that (and sometimes load two pages). So the
  // button is tried without the observer, and the automatic loading on its own.
  await page.evaluate(() => { window.__IO = window.IntersectionObserver; window.IntersectionObserver = undefined; appsWatchMore(); });
  await page.locator('#appsMore button').click();
  console.log('2. the button adds the next page (500 cards, 100 left):', (await cards()) === 500 && /100 left/.test(await page.locator('#appsMore').innerText().catch(() => '')), String(await cards()));
  await page.evaluate(() => { window.IntersectionObserver = window.__IO; appsWatchMore(); window.scrollTo(0, document.body.scrollHeight); });
  await page.waitForFunction(() => document.querySelectorAll('#appsListContainer .app-card').length === 600, null, { timeout: 5000 }).catch(() => {});
  console.log('3. scrolling to the end loads the last page by itself (all 600), and the more-row is gone:', (await cards()) === 600 && (await page.locator('#appsMore').count()) === 0, String(await cards()));

  // 2) Selection works on the data, not on what is drawn.
  await page.evaluate(() => selectAllVisible(true)); await sleep(100);
  console.log('4. Select all selects all 600, drawn or not:', (await page.evaluate(() => selectedPkgs.size)) === 600);
  await page.evaluate(() => selectAllVisible(false)); await sleep(60);
  await page.evaluate(() => toggleSelectPkg('com.example.app400')); await sleep(40);
  await page.fill('#searchInput', 'zeta'); await sleep(450);
  console.log('5. search finds the app that was far down the list, and the window starts over:', (await cards()) === 1 && (await page.locator('.app-card .app-name').first().innerText()) === 'Zeta 599');
  await page.fill('#searchInput', ''); await sleep(450);
  console.log('   clearing the search is back to the first page:', (await cards()) === 250);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)); await sleep(600);
  const sel400 = await page.evaluate(() => document.getElementById('card_com.example.app400') && document.getElementById('card_com.example.app400').classList.contains('selected'));
  console.log('6. a selected app that is drawn later still shows as selected:', sel400 === true);

  // 3) Typing on a big list is debounced: several keystrokes, one render.
  await page.evaluate(() => { window.__renders = 0; const o = window.renderApps; window.renderApps = function () { window.__renders++; return o.apply(this, arguments); }; });
  const el = page.locator('#searchInput');
  await el.click();
  for (const ch of 'app 12') { await page.keyboard.type(ch); await sleep(20); }
  console.log('7. six quick keystrokes on a long list: nothing is drawn until typing pauses:', (await page.evaluate(() => window.__renders)) === 0, String(await page.evaluate(() => window.__renders)));
  await sleep(300);
  console.log('   then one render shows the result:', (await page.evaluate(() => window.__renders)) === 1 && (await cards()) === 10, String(await page.evaluate(() => window.__renders)) + ' renders, ' + (await cards()) + ' cards');
  await page.fill('#searchInput', ''); await sleep(300);

  // 4) A filter tap starts from the first page.
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)); await sleep(600);
  await page.locator('.filter-pill', { hasText: 'System' }).first().click(); await sleep(250);
  console.log('8. a filter tap draws that filter from its first page:', (await cards()) === 200, String(await cards()));
  await page.locator('.filter-pill[data-filter="all"]').first().click(); await sleep(200);

  // 5) Logcat: tail of 800, a hint row, unchanged polls cost nothing.
  await page.evaluate(() => switchView('logcat')); await sleep(500);
  const rows1 = await page.locator('#logcatOutput .lc-row').count();
  const hint = await page.locator('#logcatOutput .lc-meta').first().innerText();
  console.log('9. logcat draws the latest 800 of 1500 entries and says how many are not shown:', rows1 === 800 && /700 earlier entries not shown/.test(hint), rows1 + ' rows, ' + JSON.stringify(hint));
  await page.locator('#logcatOutput .lc-meta', { hasText: 'earlier entries' }).click(); await sleep(150);
  console.log('   tapping the hint shows them all:', (await page.locator('#logcatOutput .lc-row').count()) === 1500);
  await page.evaluate(() => { window.__draws = 0; const o = window.logcatDraw; window.logcatDraw = function () { window.__draws++; return o.apply(this, arguments); }; });
  await page.evaluate(() => { window.logcatFetch(true); window.logcatFetch(true); window.logcatFetch(true); });
  console.log('10. polling an unchanged log (live) redraws nothing:', (await page.evaluate(() => window.__draws)) === 0, String(await page.evaluate(() => window.__draws)));
  await page.evaluate(() => { window.__logText += '\n10-03 12:01:00.000  100  200 E NewTag: a brand new line'; window.logcatFetch(true); });
  console.log('    a new line makes it draw once and shows it:', (await page.evaluate(() => window.__draws)) === 1 && /a brand new line/.test(await page.locator('#logcatOutput').innerText()));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
