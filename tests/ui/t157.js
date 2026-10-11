// The font search stops when Settings is left. The page asks the phone to stop it (AndroidBridge.cancelFontScan), the phone answers {status:'cancelled'}, and the page
// tidies up without a message: the list is empty, the box is hidden and the button works again. A search started afterwards is not affected by the old one. On an older
// build without the call the search runs to its end and its answer is dropped, as before. The native side is lib/font_mock.js (cancelFontScan stops a running search).
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
const fonts = require('./lib/font_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

const FOUND = [
  { path: '/storage/emulated/0/Download/A.ttf', name: 'A.ttf', kind: 'ttf', family: 'Alpha', style: 'Regular', variable: false, size: 2796, mtime: 0 },
  { path: '/storage/emulated/0/Download/B.ttf', name: 'B.ttf', kind: 'ttf', family: 'Beta', style: 'Regular', variable: false, size: 2788, mtime: 0 }
];

(async () => {
  const b = await chromium.launch();
  const open = async (o) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps: [{ pkg: 'com.example.app', name: 'Example App', isSystem: false }] });
    await page.addInitScript(fonts.initScript, Object.assign({ fonts: FOUND, fontFiles: {} }, o || {}));
    await page.goto(PAGE);
    await page.waitForFunction(() => document.getElementById('countAll').innerText === '1');
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const calls = page => ev(page, () => window.__font.calls.slice());
  const state = page => ev(page, () => ({
    btnOff: document.getElementById('fontScanBtn').disabled,
    box: getComputedStyle(document.getElementById('fontScanBox')).display,
    bar: getComputedStyle(document.getElementById('fontScanBar')).display,
    rows: document.querySelectorAll('#fontScanList .font-row').length,
    note: document.getElementById('fontScanStatus').innerText
  }));

  // 1) leaving Settings during the search asks the phone to stop it, once
  let page = await open({ holdScan: true });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn');
  await sleep(120);
  let s = await state(page);
  check('1. a search is running: the bar shows and the button is off', s.btnOff && s.bar !== 'none', JSON.stringify(s));
  check('   nothing has been cancelled yet', !(await calls(page)).includes('cancelFontScan'));
  await ev(page, () => switchView('apps'));
  await sleep(100);
  let c = await calls(page);
  check('   leaving Settings calls cancelFontScan on the phone, once', c.filter(x => x === 'cancelFontScan').length === 1, JSON.stringify(c));
  s = await state(page);
  check('   the phone answered "cancelled": button works again, no bar, no list, box hidden, no message', !s.btnOff && s.bar === 'none' && s.rows === 0 && s.box === 'none' && s.note === '', JSON.stringify(s));

  // 2) a search started afterwards works, and the old one does not touch it
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn');
  await sleep(120);
  await ev(page, () => window.__font.finishScan());
  await sleep(80);
  s = await state(page);
  check('2. a new search after the cancelled one lists its fonts', s.rows === 2 && /2 fonts found/.test(s.note) && !s.btnOff, JSON.stringify(s));
  check('   and it was not cancelled', (await calls(page)).filter(x => x === 'cancelFontScan').length === 1);

  // 3) leaving Settings with a finished list or with no search does not call it, and still empties the list
  await ev(page, () => switchView('apps'));
  await sleep(60);
  check('3. leaving Settings with nothing running does not call cancelFontScan', (await calls(page)).filter(x => x === 'cancelFontScan').length === 1);
  check('   the list of fonts found is emptied', (await ev(page, () => fontFound.length)) === 0);
  await page.close();

  // 4) a "cancelled" answer that arrives while the page still waits (not left): the page tidies up and does not show an error
  page = await open({ holdScan: true });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn');
  await sleep(80);
  await ev(page, () => window.onFontScan(JSON.stringify({ status: 'cancelled' })));
  s = await state(page);
  check('4. a "cancelled" answer ends the wait quietly: button on, no bar, no list, no message', !s.btnOff && s.bar === 'none' && s.rows === 0 && s.box === 'none' && s.note === '', JSON.stringify(s));
  await page.close();

  // 5) an older build without the call: nothing breaks, the search runs on and its late answer is dropped
  page = await open({ holdScan: true, noCancel: true });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn');
  await sleep(80);
  await ev(page, () => switchView('apps'));
  await sleep(60);
  await ev(page, () => window.__font.finishScan());
  await sleep(60);
  s = await state(page);
  check('5. without the call the late answer is dropped and the page is usable again', !s.btnOff && s.rows === 0 && s.box === 'none' && !(await calls(page)).includes('cancelFontScan'), JSON.stringify(s));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
