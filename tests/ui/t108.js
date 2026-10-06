// The UAD-NG classification in the single-app menu: a chip under Share (Recommended / Advanced / Expert / Unsafe), shown only for an app the project lists;
// tapping it opens a prompt with the project's full description, the level's meaning, the category and what the package needs or is needed by.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const uad = { 'com.sec.hearingadjust': { found: true, pkg: 'com.sec.hearingadjust', list: 'Oem', removal: 'Advanced', description: 'Adapt sound\nTunes the sound to your hearing.\nRemoving it hides the Adapt sound setting.', dependencies: ['com.sec.core'], neededBy: ['com.sec.audio'] },
      'com.danger': { found: true, pkg: 'com.danger', list: 'Aosp', removal: 'Unsafe', description: '', dependencies: [], neededBy: [] } };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.hearingadjust', name: 'Adapt sound', isSystem: true }, { pkg: 'com.danger', name: 'Danger', isSystem: true }, { pkg: 'com.plain', name: 'Plain', isSystem: false }]); },
      getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      getUadInfo(p) { return JSON.stringify(uad[p] || { found: false, downloaded: true }); },
      openUrl(u) { window.__opened = u; },
      getAppDetails() { return JSON.stringify({ versionName: '1', permissions: [], activities: [], services: [], appopsRaw: '' }); }, getAppOpsRaw() { return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const chip = () => page.evaluate(() => { const c = document.getElementById('sheetUad'); const r = c.getBoundingClientRect(); const sh = document.querySelector('#inspectorModal .sheet-header-top .copy-chip').getBoundingClientRect(); return { shown: getComputedStyle(c).display !== 'none', text: c.innerText.replace(/\s+/g, ' ').trim(), cls: c.className, belowShare: r.top >= sh.bottom - 1, inView: r.right <= innerWidth && r.left >= 0 }; });
  await page.evaluate(() => openInspector('com.sec.hearingadjust')); await page.waitForTimeout(300);
  console.log('listed app (Advanced):', JSON.stringify(await chip()));
  await page.waitForTimeout(700); await page.screenshot({ path: '/tmp/claude-0/uad_chip.png' });
  await page.click('#sheetUad'); await page.waitForTimeout(250);
  console.log('prompt:', JSON.stringify(await page.evaluate(() => ({ open: document.getElementById('uadInfoModal').classList.contains('show'), app: document.getElementById('uadInfoApp').innerText, body: document.getElementById('uadInfoBody').innerText.replace(/\n+/g, ' | ') }))));
  await page.waitForTimeout(600); await page.screenshot({ path: '/tmp/claude-0/uad_prompt.png' });
  await page.click('#uadInfoModal .mode-action-btn:has-text("UAD-NG Wiki")');
  console.log('wiki link opened:', JSON.stringify(await page.evaluate(() => window.__opened)));
  await page.click('#uadInfoModal .mode-btn-row .mode-action-btn:has-text("Close"), #uadInfoModal .mode-action-btn:has-text("Close")'); await page.waitForTimeout(150);
  console.log('closed, the app menu is still open:', JSON.stringify(await page.evaluate(() => [!document.getElementById('uadInfoModal').classList.contains('show'), document.getElementById('inspectorModal').classList.contains('show')])));
  await page.evaluate(() => { closeInspector(); openInspector('com.danger'); }); await page.waitForTimeout(250);
  console.log('Unsafe app:', JSON.stringify(await chip()));
  await page.click('#sheetUad'); await page.waitForTimeout(200);
  console.log('no description given:', JSON.stringify(await page.evaluate(() => document.getElementById('uadInfoBody').innerText.replace(/\n+/g, ' | '))));
  await page.evaluate(() => { document.getElementById('uadInfoModal').classList.remove('show'); closeInspector(); openInspector('com.plain'); }); await page.waitForTimeout(250);
  console.log('an app the project does not list shows no chip:', JSON.stringify(await chip()));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
