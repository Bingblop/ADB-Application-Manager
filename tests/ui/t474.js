// v7.14.0: Live Logcat reads 1000 lines unless told otherwise, and keeps its choices between launches (the owner's wish): the level, the number of lines,
// the filter text and the app it is limited to come back after a reload (kv 'logcat_prefs'); a damaged or unknown saved value is ignored.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // the app's settings store (the mock's AndroidBridge.saveSetting) lives in the page: a "launch" is a new page started with what the last one saved
  let page = null, saved = {};
  const launch = async kv => {
    if (page) { saved = await page.evaluate(() => Object.assign({}, window.__kv)); await page.close(); }
    page = await b.newPage({ viewport: { width: 390, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv || saved), log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
    await page.goto(PAGE); await page.waitForTimeout(400);
  };
  const ev = (fn, a) => page.evaluate(fn, a);
  const state = () => ev(() => ({ level: document.getElementById('logcatLevel').value, lines: document.getElementById('logcatLines').value, filter: document.getElementById('logcatFilter').value,
    app: logcatApp && logcatApp.pkg, btn: document.getElementById('logcatAppBtn').innerText, scope: document.getElementById('logcatScope').innerText }));

  await launch({});
  let s = await state();
  check('1. with nothing saved: 1000 lines, Info, no filter, all apps', s.lines === '1000' && s.level === 'I' && s.filter === '' && !s.app, JSON.stringify(s));
  await ev(() => { switchView('logcat'); logcatStop(); logcatRefresh(); }); await page.waitForTimeout(300);
  check('   and a full read (Refresh) asks for 1000 lines (a live read once a second asks for the new lines only, at most 300, as before)', await ev(() => { const c = window.__calls.logcat; return c.length > 0 && JSON.stringify(c[c.length - 1]).includes('1000'); }), await ev(() => JSON.stringify(window.__calls.logcat.slice(-1))));

  // change everything through the controls themselves
  await page.selectOption('#logcatLevel', 'W');
  await page.selectOption('#logcatLines', '2000');
  await page.fill('#logcatFilter', 'ActivityManager'); await page.press('#logcatFilter', 'Enter');
  await ev(() => logcatSetApp({ pkg: 'com.android.chrome', name: 'Chrome' })); await page.waitForTimeout(200);
  await ev(() => logcatStop());
  await launch();
  s = await state();
  check('2. after a new launch the level (Warn), the lines (2000), the filter text and the app (Chrome) are as they were left',
    s.level === 'W' && s.lines === '2000' && s.filter === 'ActivityManager' && s.app === 'com.android.chrome' && /Chrome/.test(s.btn) && /com\.android\.chrome/.test(s.scope), JSON.stringify(s));

  await ev(() => { logcatSetApp(null); }); await launch();
  s = await state();
  check('3. Show all apps is kept too', !s.app && !/Chrome/.test(s.btn) && s.level === 'W', JSON.stringify(s));

  await launch({ logcat_prefs: JSON.stringify({ level: 'Z', lines: '99999', filter: 42, app: { pkg: 7 } }) });
  s = await state();
  check('4. values that are not choices of the page are ignored (Info, 1000, no filter, all apps)', s.level === 'I' && s.lines === '1000' && s.filter === '' && !s.app, JSON.stringify(s));
  await launch({ logcat_prefs: '{not json' });
  check('   and so is a damaged entry', (await state()).lines === '1000');

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
