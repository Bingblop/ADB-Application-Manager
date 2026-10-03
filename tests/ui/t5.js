// Colors view: palette preset order, cards sit directly in the grid, picking Material 3 sets the accent colour
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE);
  await page.evaluate(() => switchView('colors')); await page.waitForTimeout(300);
  console.log(await page.locator('.palette-presets-grid .preset-name').allInnerTexts());
  console.log('cards inside grid:', await page.locator('.palette-presets-grid > .palette-preset-card').count());
  await page.click('.palette-preset-card[data-preset="material3"]');
  console.log('accent:', await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--accent')));
  await page.screenshot({ path: 'order.png' });
  console.log('errors:', JSON.stringify(errors)); await b.close(); })();
