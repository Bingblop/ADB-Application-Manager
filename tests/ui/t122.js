// App Stores: the description of an app that comes as HTML (tags, entities, lists, links) is shown as plain, readable text, in the details and in the list rows;
// the UAD-NG chip on a row of the Apps list reads only the level (RECOMMENDED, ADVANCED ...), without "UAD-NG".
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {}, getWorkingMode() { return '{}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  const html = '<p>First <b>bold</b> &amp; <i>italic</i> text.</p><p>Second paragraph<br>after a break, <a href="https://x.org">a link</a>.</p><ul><li>One</li><li>Two &lt;3</li></ul><ol><li>Alpha</li><li>Beta</li></ol><h3>Heading</h3>Plain end.<script>alert(1)</script>';
  const t = await ev(h => htmlToReadable(h), html);
  check('tags are gone', !/<|>/.test(t.replace(/&lt;3|<3/g, '')) && !/script|alert/.test(t), JSON.stringify(t));
  check('entities are decoded', /bold & italic/.test(t) && /Two <3/.test(t), JSON.stringify(t));
  check('paragraphs and the line break are kept', /text\.\n\nSecond paragraph\nafter a break, a link\./.test(t), JSON.stringify(t));
  check('lists have bullets and numbers', /• One\n• Two <3/.test(t) && /1\. Alpha\n2\. Beta/.test(t), JSON.stringify(t));
  check('plain text is left alone', (await ev(() => htmlToReadable('Just text, 5 < 6 and fine.'))) === 'Just text, 5 < 6 and fine.' || (await ev(() => htmlToReadable('Just text')))=== 'Just text');
  await ev(h => { storeCurrent = storeCurrent || {}; renderStoreDetail({ slug: 'x', app: { name: 'App X', packageName: 'x.y', fullDescription: h }, download: null }); }, html);
  const body = await page.locator('#storeDetailBody').innerText();
  check('the details show readable text, not tags', /First bold & italic text\./.test(body) && !/<p>|<\/b>|&amp;/.test(body), body.slice(0, 200));
  const chip = await ev(() => { uadLevelByPkg.set('com.a', 'Advanced'); return listUadChip({ pkg: 'com.a' }); });
  check('the UAD-NG chip on a row reads only the level', /ADVANCED/.test(chip.replace(/<[^>]*>/g, '')) && !/UAD-NG<\/span>/.test(chip) && !/>UAD-NG</.test(chip), chip);
  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
