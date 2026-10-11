// v7.9.22 File Manager search: the pills under the results (kinds of file, size, age) combine; kinds are "any of", size and age are one or none each, all of them have to fit;
// a Clear filters button shows with two or more on.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MB = 1048576, DAY = 864e5;
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fm.initScript, { files: { '/storage/emulated/0/Download': null }, archives: {}, zipFiles: {} });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.evaluate(() => switchView('files'));
  await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
  const now = Date.now();
  const hits = [
    { path: '/s/small.jpg', size: 2 * MB, mtime: now - DAY }, { path: '/s/big.jpg', size: 20 * MB, mtime: now - 2 * DAY }, { path: '/s/old.mp4', size: 300 * MB, mtime: now - 90 * DAY },
    { path: '/s/doc.pdf', size: 1 * MB, mtime: now - 10 * DAY }, { path: '/s/app.apk', size: 50 * MB, mtime: now - 3 * DAY }, { path: '/s/photos', dir: true, size: 0, mtime: now - DAY },
    { path: '/s/pack.zip', size: 11 * MB, mtime: now - 20 * DAY },
  ];
  await page.evaluate(h => { fmSearchShow(true); window.onFmSearchDone({ ok: true, hits: h, ms: 5 }); }, hits);
  const names = () => page.evaluate(() => Array.from(document.querySelectorAll('#fmSearchList .perm-row .perm-name')).map(e => e.innerText));
  const lit = () => page.evaluate(() => Array.from(document.querySelectorAll('#fmSearchFilterRow .filter-pill.active')).map(p => p.getAttribute('data-filter')));
  const chip = () => page.evaluate(() => { const r = document.getElementById('fmSearchFilterSummary'); return [getComputedStyle(r).display !== 'none', r.innerText.replace(/\s+/g, ' ').trim()]; });
  const tap = k => page.locator('#fmSearchFilterRow .filter-pill[data-filter="' + k + '"]').click().then(() => sleep(150));
  console.log('pills:', JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('#fmSearchFilterRow .filter-pill')).map(p => p.innerText))));
  console.log('no filter shows all:', JSON.stringify([await names(), await lit(), await chip()]));
  await tap('images');
  console.log('Images:', JSON.stringify([await names(), await chip()]));
  await tap('video');
  console.log('Images + Video (any of the kinds):', JSON.stringify([await names(), await lit(), await chip()]));
  await tap('big');
  console.log('+ Over 10 MB (all of them have to fit):', JSON.stringify([await names(), await chip()]));
  await tap('huge');
  console.log('Over 100 MB replaces Over 10 MB (one or none):', JSON.stringify([await names(), await lit()]));
  await tap('week'); await tap('month');
  console.log('Last 30 days replaces Last 7 days:', JSON.stringify([await lit(), await names()]));
  console.log('the count tells how many are shown:', JSON.stringify(await page.evaluate(() => document.getElementById('fmSearchCount').innerText)));
  await page.locator('#fmSearchFilterSummary .filter-clear-chip').click(); await sleep(150);
  console.log('Clear filters:', JSON.stringify([await names(), await lit(), await chip()]));
  await tap('folders'); await tap('archives');
  console.log('Folders + Archives:', JSON.stringify(await names()));
  await tap('docs'); await tap('docs');
  await tap('all');
  console.log('opening a filtered hit still opens that hit (data-h is the real index):', JSON.stringify(await page.evaluate(() => { fmSearchSetFilter('apk'); return document.querySelector('#fmSearchList .perm-row').getAttribute('data-h') + ' ' + fmSearchHits[+document.querySelector('#fmSearchList .perm-row').getAttribute('data-h')].path; })));
  await page.evaluate(() => fmSearchExit());
  console.log('leaving the results clears the filters:', JSON.stringify(await page.evaluate(() => [fmSearchFilters.size, getComputedStyle(document.getElementById('fmSearchFilterRow')).display])));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
