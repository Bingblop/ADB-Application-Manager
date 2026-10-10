// ADB Console: no auto-capitalize, Cheat Sheet search and tap-to-insert, full reference loaded from a gist
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__sh = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, getWorkingMode() { return JSON.stringify({ activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      executeShell(c) { window.__sh.push(c); return 'ok'; }, copyToClipboard() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  // ---- terminal input no auto-capitalize ----
  await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.waitForTimeout(100);
  const caps = await page.getAttribute('#termCmd', 'autocapitalize');
  const corr = await page.getAttribute('#termCmd', 'autocorrect');
  console.log('1. termCmd autocapitalize:', caps, '| autocorrect:', corr, '| spellcheck:', await page.getAttribute('#termCmd', 'spellcheck'));
  console.log('   lowercase-by-default (autocapitalize none/off):', caps === 'none' || caps === 'off');

  // ---- cheat sheet button opens modal with commands ----
  // (the Terminal pane has its own, separate Cheat Sheet button now too - scoped to the Console pane here)
  console.log('2. cheat sheet button present:', await page.isVisible('#txPaneConsole button:has-text("Cheat Sheet")'));
  await page.click('#txPaneConsole button:has-text("Cheat Sheet")'); await page.waitForTimeout(150);
  console.log('   modal shown:', await page.evaluate(() => document.getElementById('cheatSheetModal').classList.contains('show')));
  const cats = await page.locator('#cheatSheetBody .cheat-cat').allInnerTexts();
  console.log('   categories:', cats.length, JSON.stringify(cats.slice(0, 4)));
  const rowCount = await page.locator('#cheatSheetBody .cheat-row').count();
  console.log('   total command rows:', rowCount);

  // ---- search filters ----
  await page.fill('#cheatSearch', 'keyevent'); await page.waitForTimeout(80);
  const filtered = await page.locator('#cheatSheetBody .cheat-cmd').allInnerTexts();
  console.log('3. search "keyevent" rows:', filtered.length, '| all contain keyevent:', filtered.every(t => t.includes('keyevent')));
  await page.fill('#cheatSearch', 'screenshot'); await page.waitForTimeout(80);
  console.log('   search "screenshot" finds screencap:', (await page.locator('#cheatSheetBody .cheat-cmd').allInnerTexts()).some(t => t.includes('screencap')));
  await page.fill('#cheatSearch', 'zzzznope'); await page.waitForTimeout(80);
  console.log('   no-match shows empty state:', (await page.locator('#cheatSheetBody').innerText()).includes('No commands match'));

  // ---- tapping a command inserts it into the terminal input and closes the modal ----
  await page.fill('#cheatSearch', 'version'); await page.waitForTimeout(80);
  await page.locator('#cheatSheetBody .cheat-row').first().click(); await page.waitForTimeout(100);
  console.log('4. modal closed after insert:', await page.evaluate(() => !document.getElementById('cheatSheetModal').classList.contains('show')));
  const val = await page.inputValue('#termCmd');
  console.log('   inserted into termCmd:', JSON.stringify(val), '| no "adb shell" prefix:', !val.startsWith('adb '));

  // ---- a command with a placeholder gets inserted and is runnable ----
  await page.click('#txPaneConsole button:has-text("Cheat Sheet")'); await page.waitForTimeout(100);
  await page.fill('#cheatSearch', 'force-stop'); await page.waitForTimeout(80);
  await page.locator('#cheatSheetBody .cheat-row').first().click(); await page.waitForTimeout(80);
  console.log('5. placeholder command inserted:', JSON.stringify(await page.inputValue('#termCmd')));

  // ---- full reference: the gist is opened in the browser, never loaded into the page as a script or HTML ----
  await page.click('#txPaneConsole button:has-text("Cheat Sheet")'); await page.waitForTimeout(100);
  console.log('6. gist button present:', await page.isVisible('#cheatGistBtn'));
  await page.evaluate(() => { window.__opened = []; window.AndroidBridge.openUrl = u => window.__opened.push(u); loadCheatGist(); });
  await page.waitForTimeout(80);
  const gist = await page.evaluate(() => ({ opened: window.__opened, scripts: [...document.scripts].map(s => s.src).filter(u => /gist\.github\.com/.test(u)), cb: typeof window.__onPulimetGist, box: document.getElementById('cheatGistBox').innerText }));
  console.log('   the gist was opened in the browser, once:', JSON.stringify(gist.opened) === JSON.stringify(['https://gist.github.com/Pulimet/5013acf2cd5b28e55036c82c91bd56d8']));
  console.log('   no script from the gist was added to the page and no JSONP callback exists:', gist.scripts.length === 0 && gist.cb === 'undefined');
  console.log('   the box says so:', gist.box.trim() === 'Opened in your browser.');

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
