// Self-update card: auto-check, Update/Release-notes buttons, progress, up-to-date, error, hidden if unsupported
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));

  await page.addInitScript(() => {
    window.__calls = [];
    // self-update result the mock bridge returns on checkSelfUpdate()
    window.__self = { status: 'ok', installedVersion: '4.9.5-Pro', latestVersion: '5.0-Pro', newer: true, hasApk: true, page: 'https://github.com/x/releases/tag/v5.0', downloadUrl: 'https://github.com/x/releases/download/v5.0/app.apk', notes: 'New: self-update section.' };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true }, activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; }, copyToClipboard() {}, openUrl(u) { window.__calls.push('openUrl:' + u); },
      storeStatus() { return '{}'; }, isInstalled() { return false; },
      // general updates list includes a 'self' entry that must be filtered out of the general list
      getUpdateState() { return JSON.stringify({ running: false, checkedAt: Date.now(), currentVersion: '4.9.5-Pro', installing: [], updates: [
        { pkg: 'com.bloatware.bingblop', name: 'ADB Application Manager Pro', source: 'self', installedVersion: '4.9.5-Pro', availableVersion: '5.0-Pro', downloadUrl: 'x' },
        { pkg: 'com.sample.app', name: 'Sample', source: 'galaxy', installedVersion: '1', availableVersion: '2' },
      ] }); },
      checkForUpdates() { window.__calls.push('checkForUpdates'); },
      checkSelfUpdate() { window.__calls.push('checkSelfUpdate'); if (window.onSelfUpdate) window.onSelfUpdate(JSON.stringify(window.__self)); },
      installSelfUpdate() {
        window.__calls.push('installSelfUpdate');
        if (!window.onSelfUpdateProgress) return;
        window.onSelfUpdateProgress(JSON.stringify({ stage: 'downloading', percent: 40, message: '2 MB' }));
        window.onSelfUpdateProgress(JSON.stringify({ stage: 'installing', percent: 100, message: 'Installing 5.0-Pro...' }));
        window.onSelfUpdateProgress(JSON.stringify({ stage: 'done', percent: 100, message: 'Updated to 5.0-Pro. The app will restart.' }));
      },
    };
  });

  await page.goto(PAGE); await page.waitForTimeout(400);

  // open Updates -> self card auto-checks
  await page.evaluate(() => switchView('updates')); await page.waitForTimeout(200);
  console.log('1. self-check auto-fired on Updates open:', await page.evaluate(() => window.__calls.includes('checkSelfUpdate')));
  console.log('2. self card status:', JSON.stringify(await page.locator('#selfUpdStatus').innerText()));
  console.log('   Update button visible:', await page.isVisible('#selfUpdInstallBtn'));
  console.log('   Release-notes button visible:', await page.isVisible('#selfUpdPageBtn'));
  console.log('   notes shown:', (await page.locator('#selfUpdNotes').innerText()).includes('self-update'));

  // general list must NOT contain the self entry (only the galaxy one)
  const genNames = await page.evaluate(() => Array.from(document.querySelectorAll('#updContainer .uad-name')).map(e => e.innerText));
  console.log('3. general updates list (self filtered out):', JSON.stringify(genNames));

  // click Update -> installSelfUpdate + progress to done
  await page.evaluate(() => installSelfUpdateUI()); await page.waitForTimeout(150);
  console.log('4. installSelfUpdate called:', await page.evaluate(() => window.__calls.includes('installSelfUpdate')));
  console.log('   status after done:', JSON.stringify(await page.locator('#selfUpdStatus').innerText()));
  console.log('   progress bar width 100%:', await page.evaluate(() => document.querySelector('#selfUpdBar div').style.width));

  // Release-notes button opens the page
  await page.evaluate(() => openSelfUpdatePage());
  console.log('5. release notes openUrl:', await page.evaluate(() => window.__calls.filter(c => c.startsWith('openUrl')) ));

  // up-to-date path: re-check with newer=false
  await page.evaluate(() => { window.__self = { status: 'ok', installedVersion: '5.0-Pro', latestVersion: '5.0-Pro', newer: false, hasApk: true }; checkSelfUpdateUI(false); });
  await page.waitForTimeout(100);
  console.log('6. up-to-date status:', JSON.stringify(await page.locator('#selfUpdStatus').innerText()));
  console.log('   Update button hidden when up to date:', !(await page.isVisible('#selfUpdInstallBtn')));

  // no-privilege fallback: "opened" stage shows a confirm message, not done
  await page.evaluate(() => { window.__self = { status: 'ok', installedVersion: '4.9.5-Pro', latestVersion: '5.0-Pro', newer: true, hasApk: true }; checkSelfUpdateUI(false); onSelfUpdateProgress(JSON.stringify({ stage: 'opened', percent: 100, message: 'Confirm the install to update to 5.0-Pro.' })); });
  await page.waitForTimeout(80);
  console.log('7. no-priv "opened" message:', JSON.stringify(await page.locator('#selfUpdStatus').innerText()));

  // error path
  await page.evaluate(() => onSelfUpdateProgress(JSON.stringify({ stage: 'error', message: 'signed with a different key' })));
  console.log('8. error message shown:', (await page.locator('#selfUpdStatus').innerText()).toLowerCase().includes('failed'));

  // card hidden when the bridge lacks self-update (older build)
  await page.evaluate(() => { delete window.AndroidBridge.checkSelfUpdate; selfUpdChecked = false; checkSelfUpdateUI(true); });
  console.log('9. card hidden without bridge support:', !(await page.isVisible('#selfUpdateCard')));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
