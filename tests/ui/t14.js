// Updates tab: progress, update rows, Update All (one signature failure), Obtainium, set source, GitHub token
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const st = { calls: [], sources: {}, token: false }; window.__st = st; window.__holdCheck = true;
    window.confirm = () => true;
    let promptAnswers = ['https://github.com/AntennaPod/AntennaPod', 'ghp_example', 'ghp_refused'];
    window.prompt = () => promptAnswers.shift();
    const updates = [
      { pkg: 'com.duckduckgo.mobile.android', name: 'DuckDuckGo', installedVersion: '5.210.0', availableVersion: '5.212.1', source: 'github', origin: 'obtainium-catalog', downloadUrl: 'https://github.com/duckduckgo/Android/releases/download/5.212.1/duckduckgo-5.212.1-play-release.apk', page: 'https://github.com/duckduckgo/Android/releases/tag/5.212.1', notes: 'Bug fixes and improvements', obtainium: 'obtainium://app/%7B%22id%22%3A%22com.duckduckgo.mobile.android%22%7D' },
      { pkg: 'org.fdroid.fdroid', name: 'F-Droid', installedVersion: '1.20.0', availableVersion: '1.21.0', source: 'fdroid', origin: 'fdroid', downloadUrl: 'https://f-droid.org/repo/org.fdroid.fdroid_1021000.apk', page: 'https://f-droid.org/packages/org.fdroid.fdroid/' },
      { pkg: 'dev.imranr.obtainium', name: 'Obtainium', installedVersion: '1.1.40', availableVersion: '1.1.46', source: 'izzy', origin: 'izzy', downloadUrl: 'https://apt.izzysoft.de/fdroid/repo/dev.imranr.obtainium_1146.apk', page: 'https://apt.izzysoft.de/fdroid/index/apk/dev.imranr.obtainium' },
      { pkg: 'org.woheller69.weather', name: 'Weather', installedVersion: '3.1', availableVersion: '3.2', source: 'codeberg', origin: 'obtainium-import', downloadUrl: 'https://codeberg.org/x.apk', page: 'https://codeberg.org/woheller69/weather' },
      { pkg: 'com.example.noapk', name: 'Desktop-only release', installedVersion: '1.0', availableVersion: '1.1', source: 'github', origin: 'yours', noApk: true, page: 'https://github.com/x/y/releases/tag/1.1' },
      { pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', installedVersion: '26.0', availableVersion: '27.1', source: 'galaxy', isSystem: true },
    ];
    const external = [{ pkg: 'org.mozilla.firefox', name: 'Firefox', installedVersion: '131.0', source: 'external', sourceUrl: 'https://download.cdn.mozilla.net/pub/fenix/', page: 'https://download.cdn.mozilla.net/pub/fenix/', obtainium: 'obtainium://app/%7B%22id%22%3A%22org.mozilla.firefox%22%7D', unsupported: true }];
    const untracked = [{ pkg: 'de.danoeh.antennapod', name: 'AntennaPod', installedVersion: '3.4.0', installer: '' }, { pkg: 'com.example.myapp', name: 'My Sideloaded App', installedVersion: '1.0', installer: 'com.google.android.packageinstaller' }];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
      getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung' }); }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      saveStore() {}, loadStore() { return ''; }, openUrl(u) { st.calls.push('url:' + u); }, openObtainiumLink(l) { st.calls.push('obtainium:' + l.slice(0, 40)); },
      getUpdateState() { return JSON.stringify({ running: false, checkedAt: 0, updates: [] }); },
      checkForUpdates() { st.calls.push('check');
        const done = () => window.onUpdatesChecked(JSON.stringify({ updates, untracked, external, checked: 120, openSourceChecked: 12, errors: 0, openSourceErrors: 1, selfStatus: 'no_releases', checkedAt: Date.now() }));
        setTimeout(() => window.onUpdateCheckProgress(JSON.stringify({ phase: 'open-source', done: 6, total: 12 })), 30);
        if (window.__holdCheck) { window.__holdCheck = false; window.__finishCheck = done; } else setTimeout(done, 120); },          // the first check ends when the test says so
      installUpdate(pkg) { st.calls.push('install:' + pkg);
        const fail = pkg === 'org.fdroid.fdroid';
        [['downloading', 50, '2 MB'], ['installing', 100, ''], fail ? ['error', 0, 'signed with a different key than the installed app, so Android won\'t accept it as an update. Update from the source you originally installed from (F-Droid signs its own builds).'] : ['done', 100, 'Updated']]
          .forEach((s, i) => setTimeout(() => window.onUpdateProgress(JSON.stringify({ pkg, stage: s[0], percent: s[1], message: s[2] })), 60 * (i + 1))); },
      setUpdateSource(pkg, url) { st.sources[pkg] = url; st.calls.push('source:' + pkg + '=' + url); return url.includes('github.com/') ? 'ok' : 'Error: bad'; },
      getUpdateSources() { return JSON.stringify(st.sources); },
      importObtainiumExport() { st.calls.push('import-picker'); setTimeout(() => window.onObtainiumImported(JSON.stringify({ imported: 27 })), 50); },
      setGithubToken(t) { st.token = !!t; st.calls.push('token:' + (t ? 'set' : 'cleared')); }, hasGithubToken() { return st.token; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.click('.tab-btn[data-tab="installer"]'); await page.click('#instSwitchUpdater');
  await page.waitForFunction(() => /6\/12/.test(document.getElementById('updStatus').innerText));          // the first check is held at its progress report
  console.log('progress:', await page.locator('#updStatus').innerText());
  await page.evaluate(() => window.__finishCheck());
  await page.waitForFunction(() => /6 updates available/.test(document.getElementById('updStatus').innerText));
  console.log('status:', await page.locator('#updStatus').innerText());
  console.log('rows:', (await page.locator('#updContainer .upd-row').evaluateAll(r => r.map(x => x.querySelector('.uad-name').innerText + ' [' + [...x.querySelectorAll('.uad-badge')].map(b => b.innerText).join('/') + '] ' + x.querySelector('.upd-head button').innerText))).join('\n      '));
  console.log('update all:', await page.locator('#updAllBtn').innerText(), '| tab:', (await page.locator('#updatesTabBtn').innerText()).replace(/\s+/g, ' '));
  console.log('extras headers:', (await page.locator('#updExtraContainer .color-card-title').allInnerTexts()).join(' | '));
  await page.screenshot({ path: 'updates_os.png', fullPage: true });
  // Update all → F-Droid fails with signature message, others succeed; noApk excluded
  await page.click('#updAllBtn');
  // the installs run one after another (the Samsung Internet one last); wait until every row has its final state instead of a fixed time
  await page.waitForFunction(() => { const t = [...document.querySelectorAll('#updContainer .upd-row .upd-head button')].map(b => b.innerText.trim()); return t.length > 0 && t.every(x => /^(✓ Updated|Retry|Release)$/.test(x)); }, null, { timeout: 30000 });
  console.log('after update all:', (await page.locator('#updContainer .upd-row .upd-head button').allInnerTexts()).join(' | '));
  console.log('fdroid msg:', (await page.locator('.upd-row:has-text("F-Droid") .upd-msg').first().innerText()).slice(0, 90));
  // Extras actions
  await page.click('#updExtraContainer >> text=Open in Obtainium');
  await page.click('.upd-row:has-text("DuckDuckGo") >> text=Open in Obtainium');
  await page.click('#updExtraContainer .upd-row:has-text("AntennaPod") >> text=＋ Set source'); await page.waitForTimeout(250);
  await page.click('text=Import Obtainium List'); await page.waitForTimeout(300);
  await page.click('#ghTokenBtn');
  console.log('token button:', await page.locator('#ghTokenBtn').innerText());
  // the Keystore refuses the next token: the bridge answers false, so the page says it was not saved and the button keeps its old state
  await page.evaluate(() => { window.AndroidBridge.setGithubToken = t => { window.__st.calls.push('token:refused'); return false; }; });
  await page.click('#ghTokenBtn'); await page.waitForTimeout(150);
  console.log('token refused:', await page.locator('#toastMsg').innerText(), '|', await page.locator('#ghTokenBtn').innerText());
  await page.click('.upd-row:has-text("Desktop-only") >> button:has-text("Release")');
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
