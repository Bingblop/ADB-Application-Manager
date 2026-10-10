// Debloater brand filter: phone's brand first, counts follow other filters, switching brands, brand-aware search
const { chromium, PAGE, fixture } = require('./lib/pw');
const fs = require('fs');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', brand: 'samsung', device: 'SM-S938B', release: '16' }); },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getUadStatus() { return JSON.stringify({ cached: true, count: 5381, updatedAt: Date.now(), stale: false }); },
      updateUadList() {}, getUadMatches() { return mock; },
    };
  }, MOCK);
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.click('.tab-btn:has-text("Debloater")'); await page.waitForTimeout(300);
  console.log('chips (Recommended):', (await page.locator('#uadBrandRow .filter-chip').allInnerTexts()).join(' | '));
  console.log('first brand chip is device:', await page.locator('#uadBrandRow .filter-chip').nth(1).evaluate(e => e.classList.contains('device-brand')));
  await page.click('#uadBrandRow .filter-chip.device-brand');
  console.log('Samsung only:', await page.locator('#uadCount').innerText(), '| brands in rows:', [...new Set(await page.locator('.uad-row').evaluateAll(r => r.map(x => x.querySelectorAll('.uad-tag')[0].innerText)))].join(','));
  await page.screenshot({ path: 'brand_filter.png' });
  // counts follow other filters: add all levels
  await page.evaluate(() => { ['Advanced','Expert','Unsafe'].forEach(l => toggleUadRemoval(l)); });
  console.log('chips (all levels):', (await page.locator('#uadBrandRow .filter-chip').allInnerTexts()).slice(0, 4).join(' | '));
  console.log('Samsung all levels:', await page.locator('#uadCount').innerText());
  await page.click('#uadBrandRow [data-brand="Google"]');
  console.log('Google:', await page.locator('#uadCount').innerText());
  await page.fill('#uadSearch', 'meta'); await page.click('#uadBrandRow [data-brand="all"]');
  console.log('search "meta" (brand-aware):', await page.locator('#uadCount').innerText());
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
