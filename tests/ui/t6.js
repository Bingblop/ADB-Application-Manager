// Palette cards: after picking Material 3 and tapping away, prints each card's selected class, border, focus
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  await page.goto(PAGE);
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  await page.click('.palette-preset-card[data-preset="material3"]'); await page.mouse.click(5, 5); await page.waitForTimeout(600);
  console.log(await page.locator('.palette-preset-card').evaluateAll(els => els.map(e => e.dataset.preset + ':' + e.className + ':' + getComputedStyle(e).borderColor + ':' + (e === document.activeElement))));
  await b.close(); })();
