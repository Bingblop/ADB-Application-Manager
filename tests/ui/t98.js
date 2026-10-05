// Terminal tab: the Cheat Sheet button shows a different set of commands depending on which of the three
// shells (this app's sandbox, the privileged working mode, Termux) is currently picked, and drops a tapped
// command into the Terminal's own input - never the separate ADB Console's.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await tx.install(page, { real: true });
  await page.addInitScript(() => { window.__tx.info.termux = { installed: true, permission: true, version: '0.118' }; });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const until = async (fn, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 4000)) { if (await page.evaluate(fn)) return true; await sleep(25); } return false; };
  const title = () => page.locator('#cheatSheetTitle').innerText();
  const cats = () => page.locator('#cheatSheetBody .cheat-cat').allInnerTexts();
  const gistVisible = () => page.isVisible('#cheatGistSection');

  await page.evaluate(() => switchView('terminal')); await sleep(150);
  await page.selectOption('#txShell', 'priv'); await sleep(50);
  await until(() => txSess.priv.st === 'ready');

  // ---------------------------------------------------------------- working mode (privileged): the ADB cheat sheet
  console.log('1. On the Working mode shell:', await page.locator('#txShell').inputValue());
  await page.click('#txPaneTerminal button:has-text("Cheat Sheet")'); await sleep(100);
  console.log('   its Cheat Sheet is the ADB one, with the gist section shown:', (await title()) === 'ADB Cheat Sheet' && (await gistVisible()));
  const privCats = await cats();
  console.log('   categories look like the ADB set:', privCats.includes('PACKAGES') && privCats.includes('APP CONTROL'));
  await page.evaluate(() => closeCheatSheet());

  // ---------------------------------------------------------------- this app's own sandbox shell
  await page.selectOption('#txShell', 'app'); await sleep(50);
  await until(() => txSess.app.st === 'ready');
  await page.click('#txPaneTerminal button:has-text("Cheat Sheet")'); await sleep(100);
  console.log('2. Switched to the sandbox shell -> a different, smaller cheat sheet, no gist section:', (await title()) === 'Sandbox Cheat Sheet' && !(await gistVisible()));
  const appCats = await cats();
  console.log('   it has no package-manager / ADB categories:', JSON.stringify(appCats), !appCats.includes('PACKAGES') && !appCats.includes('APP CONTROL'));
  await page.locator('#cheatSheetBody .cheat-row', { hasText: 'ls -la' }).first().click(); await sleep(50);
  console.log('   tapping a command fills the Terminal\'s own input (not the ADB Console\'s):', (await page.locator('#txInput').inputValue()) === 'ls -la' && (await page.locator('#termCmd').inputValue()) === '');

  // ---------------------------------------------------------------- Termux
  await page.selectOption('#txShell', 'termux'); await sleep(50);
  await until(() => txSess.termux.st === 'ready' || txSess.termux.st === 'error', 6000);
  await page.click('#txPaneTerminal button:has-text("Cheat Sheet")'); await sleep(100);
  console.log('3. Switched to Termux -> a bash-flavoured cheat sheet, no gist section:', (await title()) === 'Termux Cheat Sheet' && !(await gistVisible()));
  const termuxCats = await cats();
  console.log('   it covers packages, git and network (none of which the sandbox shell has):', termuxCats.includes('PACKAGES') && termuxCats.includes('GIT') && termuxCats.includes('NETWORK'));
  await page.evaluate(() => closeCheatSheet());

  // ---------------------------------------------------------------- the ADB Console keeps its own cheat sheet
  await page.evaluate(() => txShowPane('console')); await sleep(100);
  await page.click('#txPaneConsole button:has-text("Cheat Sheet")'); await sleep(100);
  console.log('4. the ADB Console\'s own Cheat Sheet button still shows the ADB set, unaffected by the Terminal\'s shell picker:', (await title()) === 'ADB Cheat Sheet' && (await gistVisible()));
  await page.locator('#cheatSheetBody .cheat-row').first().click(); await sleep(50);
  console.log('   and still fills the Console\'s own input:', (await page.locator('#termCmd').inputValue()).length > 0 && (await page.locator('#txInput').inputValue()) === 'ls -la');

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
