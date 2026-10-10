// App icons in the list: drawn natively and cached (asked for once per app), to the left of the app's info, no letter box, press and hold saves one.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const apps = [{ pkg: 'com.example.one', name: 'One' }, { pkg: 'com.example.two', name: 'Two' }, { pkg: 'com.example.noicon', name: 'No Icon' }];
  await page.addInitScript(a => {
    window.__icon = { asked: [], saved: [], packs: [] };
    const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); }, getWorkingMode() { return '{}'; },
      getIconPacks() { return JSON.stringify([{ pkg: 'com.pack.one', label: 'Pack One' }]); },
      loadAppIcons(json, pack) { const l = JSON.parse(json); window.__icon.asked.push(...l); window.__icon.packs.push(pack); const m = {}; l.forEach(p => { if (p !== 'com.example.noicon') m[p] = PNG; }); setTimeout(() => window.onAppIcons(m), 20); return 'started'; },
      getAppDetails() { return '{}'; },
      clearAppIconCache() { window.__icon.cleared = (window.__icon.cleared || 0) + 1; return 7; },
      saveAppIcon(pkg, label, pack) { window.__icon.saved.push([pkg, label, pack]); setTimeout(() => window.onAppIconSaved({ ok: true, pkg, path: 'Download/ADB App Manager/Icons/' + label + ' (' + pkg + ').png' }), 10); return 'started'; },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  console.log('icons asked for once each:', JSON.stringify(await page.evaluate(() => window.__icon.asked.slice().sort())));
  console.log('icons with a picture:', await page.locator('img.app-icon[src]').count(), 'of', await page.locator('img.app-icon').count());
  console.log('no letter box any more:', await page.locator('.app-avatar').count());
  const geo = await page.locator('#card_com\\.example\\.one').evaluate(c => { const r = s => c.querySelector(s).getBoundingClientRect(); return { iconLeftOfInfo: r('.app-icon').right <= r('.app-meta').left + 1, checkboxRightOfInfo: r('.app-checkbox').left >= r('.app-meta').right - 1, iconW: Math.round(r('.app-icon').width) }; });
  console.log('icon sits left of the info, checkbox right of it:', JSON.stringify(geo));
  await page.evaluate(() => { renderApps(); });
  await page.waitForTimeout(200);
  console.log('re-render does not ask again:', await page.evaluate(() => window.__icon.asked.length));
  // a quick tap is not a save; press and hold is
  const icon = page.locator('#card_com\\.example\\.one img.app-icon');
  const box = await icon.boundingBox();
  await page.mouse.move(box.x + 5, box.y + 5); await page.mouse.down(); await page.waitForTimeout(100); await page.mouse.up(); await page.waitForTimeout(700);
  console.log('a short tap saves nothing:', await page.evaluate(() => window.__icon.saved.length));
  await page.mouse.move(box.x + 5, box.y + 5); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up(); await page.waitForTimeout(200);
  console.log('press and hold saves it:', JSON.stringify(await page.evaluate(() => window.__icon.saved)), '|', await page.locator('#toastMsg').innerText());
  console.log('and does not select the row:', await page.locator('#card_com\\.example\\.one.selected').count());
  // press and hold on the row (not the icon) opens that app's menu and does not select the row
  const meta = page.locator('#card_com\\.example\\.two .app-meta'); const mb = await meta.boundingBox();
  await page.mouse.move(mb.x + 20, mb.y + 10); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up(); await page.waitForTimeout(250);
  console.log('press and hold on the row opens the app menu:', await page.evaluate(() => { const m = document.getElementById('inspectorModal') || document.querySelector('.modal-overlay.show'); return !!(m && m.classList.contains('show')); }), '| row not selected:', await page.locator('#card_com\\.example\\.two.selected').count());
  await page.evaluate(() => closeInspector()); await page.waitForTimeout(150);
  // Icon pack: the choice sits in Settings under Font, and picking one re-asks for every icon with that pack
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  console.log('Icon pack card follows the Font card:', await page.evaluate(() => document.getElementById('fontCard').nextElementSibling.id), '| options:', JSON.stringify(await page.locator('#iconPackSelect option').allInnerTexts()));
  await page.evaluate(() => window.__icon.asked.length = 0);
  await page.selectOption('#iconPackSelect', 'com.pack.one'); await page.waitForTimeout(300);
  console.log('picking a pack asks again, with the pack:', JSON.stringify(await page.evaluate(() => [window.__icon.asked.slice().sort(), [...new Set(window.__icon.packs.slice(-3))]])));
  console.log('and the choice is kept:', await page.evaluate(() => JSON.parse(window.__kv ? window.__kv.icon_pack : localStorage.getItem('icon_pack'))));
  await page.evaluate(() => window.__icon.asked.length = 0);
  await page.click('#iconCacheClearBtn'); await page.waitForTimeout(300);
  console.log('Clear icon cache asks the app to clear, then redraws:', await page.evaluate(() => [window.__icon.cleared, window.__icon.asked.length > 0]), '|', await page.locator('#toastMsg').innerText());
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
