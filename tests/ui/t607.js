// v7.18.3 (the owner's request): Live Logcat has a Row tints button that opens a sheet with the row-tint slider and the Gradient switch,
// the same setting as Settings > Row tints and the Apps tab's search menu (all three stay in step); Back or the backdrop closes it.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1' }, log: '' });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(() => switchView('logcat')); await page.waitForTimeout(200);
  check('1. the Row tints button is on the Live Logcat tab', await page.locator('#lcTintBtn').isVisible());
  await page.click('#lcTintBtn'); await page.waitForTimeout(300);
  const s1 = await ev(() => ({ show: document.getElementById('lcTintModal').classList.contains('show'), v: document.getElementById('lcRowTintRange').value,
    g: document.getElementById('lcRowTintGrad').checked, exp: document.getElementById('lcTintBtn').getAttribute('aria-expanded') }));
  check('2. it opens a sheet showing the setting as it is (Subtle, Gradient off)', s1.show && s1.v === '2' && !s1.g && s1.exp === 'true', JSON.stringify(s1));
  await page.locator('#lcRowTintRange').fill('4'); await page.waitForTimeout(80);
  await page.click('#lcRowTintGradRow .switch-track'); await page.waitForTimeout(80);
  const s2 = await ev(() => ({ t: document.documentElement.dataset.rowTint, g: document.documentElement.dataset.rowGrad, kvT: kvGet('row_tint_lv', -1), kvG: kvGet('row_tint_grad', false),
    set: document.getElementById('rowTintRange').value, srch: document.getElementById('searchRowTintRange').value,
    setG: document.getElementById('rowTintGradToggle').checked, srchG: document.getElementById('searchRowTintGrad').checked }));
  check('3. the slider and the switch change the one setting, and the other two places follow', s2.t === 'vivid' && s2.g === 'on' && s2.kvT === 4 && s2.kvG === true
    && s2.set === '4' && s2.srch === '4' && s2.setG && s2.srchG, JSON.stringify(s2));
  await ev(() => setRowTint(1)); await page.waitForTimeout(50);
  check('   a change made elsewhere shows in the sheet', await ev(() => document.getElementById('lcRowTintRange').value === '1'));
  await page.mouse.click(200, 30); await page.waitForTimeout(300);
  check('4. tapping outside closes the sheet', await ev(() => !document.getElementById('lcTintModal').classList.contains('show') && document.getElementById('lcTintBtn').getAttribute('aria-expanded') === 'false'));
  await page.click('#lcTintBtn'); await page.waitForTimeout(250);
  await page.click('#lcTintModal .sheet-close'); await page.waitForTimeout(250);
  check('   so does its close button', await ev(() => !document.getElementById('lcTintModal').classList.contains('show')));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
