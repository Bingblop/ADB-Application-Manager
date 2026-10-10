// What's new shown only after an upgrade, watched-profile drift banner, and Quick list toggle on Saved Lists
const { chromium, PAGE, REPO } = require('./lib/pw');
const fs = require('fs');
const CHANGELOG = fs.readFileSync(REPO + '/CHANGELOG.md', 'utf8');
(async () => {
  const b = await chromium.launch();
  const mkPage = async (opts) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message)); page.__errors = errors;
    await page.addInitScript(([changelog, o]) => {
      const store = Object.assign({}, o.store); window.__store = store; window.__calls = [];
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return JSON.stringify(o.lists || []); }, saveCustomLists() {},
        getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', device: 'SM-S928B', release: '14', buildChanged: !!o.buildChanged }); }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore(k, v) { store[k] = v; }, loadStore(k) { return store[k] || ''; },
        loadPackages() { return JSON.stringify(o.apps); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getAppDetails() { return '{}'; }, executeAppAction(a, p) { window.__calls.push(a + ':' + p); return 'Success'; }, executeShell() { return ''; },
        getChangelog() { return changelog; },
        getAppVersion() { const now = 1700000000000; return JSON.stringify({ versionName: o.versionName || '4.7-Pro', versionCode: o.versionCode || 370,
          firstInstallTime: 'firstInstallTime' in o ? o.firstInstallTime : now, lastUpdateTime: 'lastUpdateTime' in o ? o.lastUpdateTime : now }); },
        setWatchedProfile(n) { window.__calls.push('watch:' + n); }, setQuickList(id) { window.__calls.push('quick:' + id); }, getQuickList() { return o.quick || ''; },
        openUrl(u) { window.__calls.push('url:' + u); }, copyToClipboard() {},
      };
    }, [CHANGELOG, opts]);
    await page.goto(PAGE); await page.waitForTimeout(1700);
    return page;
  };
  const apps = [
    { pkg: 'com.facebook.appmanager', name: 'Facebook App Manager', isSystem: true },
    { pkg: 'com.netflix.partner', name: 'Netflix Partner', isSystem: true },
    { pkg: 'com.example.keep', name: 'Keep', isSystem: false },
  ];

  // --- What's new: existing install upgrading from an older version ---
  let p = await mkPage({ apps, store: { seen_version: JSON.stringify({ code: 350 }), ui_state: '{}' } });
  console.log('upgrade from 350: modal shown:', await p.locator('#whatsNewModal.show').count() === 1);
  console.log('subtitle:', (await p.innerText('#whatsNewSubtitle')).replace(/^v\d+(\.\d+)*-Pro/, 'v<latest>-Pro'));   // the newest version is whatever CHANGELOG.md says
  const body = await p.innerText('#whatsNewBody');
  console.log('has a version section:', body.includes('-Pro (versionCode'), '| has bullets (li):', await p.locator('#whatsNewBody li').count() > 0, '| raw ** left:', body.includes('**'), '| raw ` left:', body.includes('`'));
  const heads = await p.locator('#whatsNewBody h4').allInnerTexts();
  const codes = heads.map(h => +((/versionCode (\d+)/.exec(h) || [])[1]));
  console.log('versions shown (h4):', heads.length, '| named like "v6.0-Pro (versionCode 600)":', heads.every(h => /^v\d+(\.\d+)*-Pro \(versionCode \d+\)$/.test(h)), '| newest first:', codes.every((c, i) => i === 0 || codes[i - 1] > c));
  console.log('seen version saved:', await p.evaluate(() => window.__store.seen_version));
  await p.screenshot({ path: 'whatsnew.png' });
  await p.evaluate(() => closeWhatsNew());
  console.log('about line:', await p.innerText('#aboutVersion'));
  await p.close();

  // same version again: nothing shown
  p = await mkPage({ apps, store: { seen_version: JSON.stringify({ code: 370 }), ui_state: '{}' } });
  console.log('same version: modal shown:', await p.locator('#whatsNewModal.show').count() === 1);
  await p.close();
  // fresh install (firstInstallTime == lastUpdateTime): nothing shown, but recorded
  p = await mkPage({ apps, store: {} });
  console.log('fresh install: modal shown:', await p.locator('#whatsNewModal.show').count() === 1, '| recorded:', await p.evaluate(() => window.__store.seen_version));
  await p.evaluate(() => openWhatsNew({ max: 3 }));
  console.log('manual open shows', await p.locator('#whatsNewBody h4').count(), 'versions');
  await p.close();
  // a stale UI setting alone must NOT trigger it any more (the old, unreliable heuristic this replaces)
  p = await mkPage({ apps, store: { ui_state: '{"x":1}' } });
  console.log('ui_state alone (fresh install): modal shown:', await p.locator('#whatsNewModal.show').count() === 1);
  await p.close();
  // a real upgrade (installed long before this run, updated just now) with no seen_version yet: must show
  p = await mkPage({ apps, firstInstallTime: 1600000000000, lastUpdateTime: 1700000000000, store: {} });
  console.log('real upgrade, no seen_version: modal shown:', await p.locator('#whatsNewModal.show').count() === 1, '| versions:', await p.locator('#whatsNewBody h4').count());
  await p.close();

  // --- Watched profile / drift banner ---
  const prof = { id: 'p1', name: 'Lean Samsung', created: Date.now(), watch: true, apps: [
    { pkg: 'com.facebook.appmanager', name: 'Facebook App Manager', state: 'disabled' },
    { pkg: 'com.netflix.partner', name: 'Netflix Partner', state: 'uninstalled' },
    { pkg: 'com.gone.app', name: 'Not here', state: 'disabled' } ] };
  p = await mkPage({ apps, buildChanged: true, store: { profiles: JSON.stringify([prof]), seen_version: JSON.stringify({ code: 370 }) } });
  const vis = await p.locator('#driftBanner').isVisible();
  console.log('drift banner visible:', vis, '|', (await p.innerText('#driftTitle')), '|', await p.innerText('#driftDesc'));
  await p.screenshot({ path: 'drift.png' });
  await p.click('#driftBanner button:has-text("Review")'); await p.waitForTimeout(250);
  console.log('review opens preview:', (await p.innerText('#profilePreview')).replace(/\s+/g, ' ').slice(0, 110));
  await p.evaluate(() => closeProfiles());
  await p.click('#driftBanner button:has-text("✕")').catch(() => {});
  console.log('dismissed hides banner:', !(await p.locator('#driftBanner').isVisible()));
  await p.close();
  // no drift once everything matches
  const apps2 = [{ pkg: 'com.facebook.appmanager', name: 'F', isSystem: true, isFrozen: true }, { pkg: 'com.netflix.partner', name: 'N', isSystem: true, isUninstalled: true }];
  p = await mkPage({ apps: apps2, store: { profiles: JSON.stringify([prof]), seen_version: JSON.stringify({ code: 370 }) } });
  console.log('all matching: banner visible:', await p.locator('#driftBanner').isVisible());
  // watch toggle calls the bridge
  await p.evaluate(() => { openProfiles(); toggleWatchProfile('p1'); });
  console.log('unwatch call:', JSON.stringify(await p.evaluate(() => window.__calls)), '| stored watch:', await p.evaluate(() => JSON.parse(window.__store.profiles)[0].watch));
  await p.evaluate(() => toggleWatchProfile('p1'));
  console.log('rewatch call:', JSON.stringify(await p.evaluate(() => window.__calls.slice(-1))));
  await p.close();

  // --- Quick list on Saved Lists ---
  const lists = [{ id: 'l1', name: 'Distractions', packages: ['com.a', 'com.b'] }, { id: 'l2', name: 'Other', packages: ['com.c'] }];
  p = await mkPage({ apps, lists, quick: 'l2', store: { seen_version: JSON.stringify({ code: 370 }) } });
  await p.evaluate(() => switchView('saved-lists')); await p.waitForTimeout(250);
  console.log('quick buttons:', JSON.stringify(await p.locator('button:has-text("Quick list")').allInnerTexts()));
  await p.locator('button:has-text("Quick list")').first().click();
  console.log('chose l1 →', JSON.stringify(await p.evaluate(() => window.__calls.slice(-1))), '|', JSON.stringify(await p.locator('button:has-text("Quick list")').allInnerTexts()));
  await p.locator('button:has-text("Quick list ✓")').first().click();
  console.log('toggle off →', JSON.stringify(await p.evaluate(() => window.__calls.slice(-1))));
  await p.screenshot({ path: 'quicklist.png' });
  console.log('errors:', JSON.stringify(p.__errors));
  await p.close();
  await b.close(); })();
