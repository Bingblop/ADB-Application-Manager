// Settings links to the Kotlin test prereleases and makes clear that the experimental app installs separately.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }

(async () => {
  const browser = await chromium.launch();
  const errors = [];
  for (const [width, height] of [[360, 800], [412, 915]]) {
    const size = width + 'x' + height;
    const page = await browser.newPage({ viewport: { width, height } });
    page.on('pageerror', error => errors.push(size + ' ' + error.message));
    await page.goto(PAGE);
    await page.waitForTimeout(250);
    await page.evaluate(() => switchView('prefs'));

    const copy = await page.evaluate(() => {
      const card = document.getElementById('kotlinTestBuildCard');
      return {
        title: card.querySelector('.color-card-title').textContent.trim(),
        subtitle: card.querySelector('.color-card-subtitle').textContent.trim(),
        button: document.getElementById('kotlinTestBuildBtn').textContent.trim()
      };
    });
    check(size + ' Settings labels the Kotlin build experimental and separate', copy.title === 'Kotlin test build' && /Experimental Kotlin app/.test(copy.subtitle) && /installs separately/.test(copy.subtitle), JSON.stringify(copy));

    const opened = await page.evaluate(() => {
      window.__openedUrls = [];
      window.AndroidBridge = window.AndroidBridge || {};
      window.AndroidBridge.openUrl = url => window.__openedUrls.push(url);
      document.getElementById('kotlinTestBuildBtn').click();
      return window.__openedUrls.slice();
    });
    check(size + ' button opens the stable repository releases page', opened.length === 1 && opened[0] === 'https://github.com/Bingblop/ADB-Application-Manager/releases', JSON.stringify(opened));
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    check(size + ' no sideways scroll', !overflow);
    await page.close();
  }
  check('no page errors', errors.length === 0, errors.join(' | '));
  await browser.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(error => { console.error(error); process.exit(1); });
