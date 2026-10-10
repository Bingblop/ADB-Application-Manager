// SD Maid > Trim Caches in All Applications (a card of Settings until v7.10.10): a button that says exactly that with a long explanation under it; it runs
// `pm trim-caches 128G` through the working mode (locked without one), asks first, runs off the page's thread, and reports success or failure with the free space before and after.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(() => {
    window.__shell = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: window.__mode || 'unprivileged', modeAvailable: !!window.__mode, isPrivileged: !!window.__mode }); },
      executeShellAsync(id, cmd) { window.__shell.push({ id, cmd }); return 'started'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const shell = () => page.evaluate(() => window.__shell.slice());
  const modal = () => page.isVisible('#commandResultsModal.show');
  const setMode = on => page.evaluate(on => { isPrivilegedActive = on; trimSyncLock(); }, on);

  await page.evaluate(() => switchView('sdm')); await sleep(300);
  const card = page.locator('#trimCachesCard');
  console.log('the card is in the SD Maid tab and no longer in Settings:', await page.evaluate(() => !!document.querySelector('#view-sdm #trimCachesCard') && !document.querySelector('#view-prefs #trimCachesCard')));
  console.log('button text:', JSON.stringify((await page.locator('#trimCachesBtn').innerText()).trim()));
  console.log('explanation is below the button:', await page.evaluate(() => { const a = document.getElementById('trimCachesBtn'), e = document.getElementById('trimCachesExplain'); return !!(a.compareDocumentPosition(e) & Node.DOCUMENT_POSITION_FOLLOWING); }));
  const text = await page.locator('#trimCachesExplain').innerText();
  console.log('explanation parts:', JSON.stringify(['What it runs', 'What it does', 'What is trimmed', 'What is not touched', 'What you may notice', 'How long it takes'].map(h => h + '=' + text.includes(h))));
  console.log('names the command (both forms):', text.includes('pm trim-caches 128G') && text.includes('adb shell pm trim-caches 128G'), '| words:', text.split(/\s+/).length);
  await card.scrollIntoViewIfNeeded(); await sleep(200);
  await page.screenshot({ path: 'trim_locked.png' });

  // without a working mode: locked, the privilege modal, nothing runs
  console.log('locked without a working mode:', await page.locator('#trimCachesBtn').evaluate(e => e.classList.contains('locked')), '| lock shown:', (await page.locator('#trimCachesLock').innerText()).trim() !== '');
  await setMode(false);
  await page.click('#trimCachesBtn'); await sleep(150);
  console.log('privilege modal:', await page.isVisible('#privilegeModal.show'), '| asked:', dialogs.length, '| ran:', (await shell()).length);
  await page.evaluate(() => closePrivilegeModal());

  // with a working mode: asks first (declined = nothing runs)
  await setMode(true);
  console.log('lock gone with a working mode:', (await page.locator('#trimCachesLock').innerText()).trim() === '');
  answer = false; await page.click('#trimCachesBtn'); await sleep(150);
  console.log('declined: asked', dialogs.length, 'time, ran', (await shell()).length);
  answer = true; await page.click('#trimCachesBtn'); await sleep(150);
  const sent = await shell();
  console.log('the question:', JSON.stringify(dialogs[dialogs.length - 1]));
  console.log('ran once:', sent.length, '| the command:', JSON.stringify(sent[0] && sent[0].cmd));
  console.log('busy: button', JSON.stringify((await page.locator('#trimCachesBtn').innerText()).trim()), 'disabled', await page.locator('#trimCachesBtn').isDisabled(), '| status:', await page.locator('#trimCachesStatus').isVisible());
  await page.screenshot({ path: 'trim_busy.png' });
  await page.click('#trimCachesBtn', { force: true }).catch(() => {}); await sleep(100);
  console.log('a second tap while it runs starts nothing:', (await shell()).length);

  // it finishes: free space before and after, success
  await page.evaluate(id => window.onShellDone(id, '/dev/block/dm-48 227000000 100000000 126000000 45% /data\n__TRIM_BEGIN__\n__TRIM_RC__0\n/dev/block/dm-48 227000000 98000000 128500000 44% /data\n'), sent[0].id);
  await sleep(200);
  console.log('report shown:', await modal(), '| title:', await page.locator('#commandResultsTitle').innerText(), '| subtitle:', await page.locator('#commandResultsSubtitle').innerText());
  console.log('report text:', JSON.stringify((await page.locator('#commandResultsList').innerText()).replace(/\n+/g, ' | ')));
  console.log('marked success:', await page.locator('#commandResultsList .result-item.success').count());
  await page.screenshot({ path: 'trim_done.png' });
  console.log('button back:', JSON.stringify((await page.locator('#trimCachesBtn').innerText()).trim()), 'enabled', await page.locator('#trimCachesBtn').isEnabled(), '| status hidden:', !(await page.locator('#trimCachesStatus').isVisible()));
  await page.evaluate(() => closeCommandResultsModal());

  // a failure: Android's own words are shown
  await page.click('#trimCachesBtn'); await sleep(150);
  const second = (await shell())[1];
  await page.evaluate(id => window.onShellDone(id, '/dev/block/dm-48 227000000 100000000 126000000 45% /data\n__TRIM_BEGIN__\nError: java.lang.SecurityException: Neither user 2000 nor current process has android.permission.CLEAR_APP_CACHE\n__TRIM_RC__255\n/dev/block/dm-48 227000000 100000000 126000000 45% /data\n'), second.id);
  await sleep(200);
  console.log('failure report:', await modal(), '| subtitle:', await page.locator('#commandResultsSubtitle').innerText(), '| failed rows:', await page.locator('#commandResultsList .result-item.failed').count(), '| text:', JSON.stringify((await page.locator('#commandResultsList').innerText()).replace(/\n+/g, ' | ').slice(0, 200)));
  await page.evaluate(() => closeCommandResultsModal());

  // nothing freed (already clean), and a free space that cannot be read
  await page.click('#trimCachesBtn'); await sleep(150);
  const third = (await shell())[2];
  await page.evaluate(id => window.onShellDone(id, '/dev/block/dm-48 227000000 100000000 126000000 45% /data\n__TRIM_BEGIN__\n__TRIM_RC__0\n/dev/block/dm-48 227000000 100000000 126000000 45% /data\n'), third.id);
  await sleep(150);
  console.log('nothing to free:', JSON.stringify((await page.locator('#commandResultsList').innerText()).replace(/\n+/g, ' | ')));
  await page.evaluate(() => closeCommandResultsModal());
  await page.click('#trimCachesBtn'); await sleep(150);
  const fourth = (await shell())[3];
  await page.evaluate(id => window.onShellDone(id, '__TRIM_BEGIN__\n__TRIM_RC__0\n'), fourth.id);
  await sleep(150);
  console.log('free space unreadable:', JSON.stringify((await page.locator('#commandResultsList').innerText()).replace(/\n+/g, ' | ')), '| success rows:', await page.locator('#commandResultsList .result-item.success').count());
  await page.evaluate(() => closeCommandResultsModal());

  // the terminal's own answers are not mixed up with this one
  await page.evaluate(() => { window.onShellDone('s999', 'x'); });
  console.log('an answer for another run is ignored:', !(await modal()));

  // a bridge that cannot start it
  await page.evaluate(() => { window.AndroidBridge.executeShellAsync = () => 'error'; });
  await page.click('#trimCachesBtn'); await sleep(200);
  console.log('could not start:', await modal(), '| subtitle:', await page.locator('#commandResultsSubtitle').innerText(), '| button enabled again:', await page.locator('#trimCachesBtn').isEnabled());

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
