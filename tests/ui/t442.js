// v7.14.0: Live Logcat is its own tab again ('logcat', "Live Logcat"), in the place the Terminal tab had (Console holds the shells now). It reads the log
// while it is open and stops when it is left, exactly as it did as a segment of Console; switchView('logcat'), the Logs button of an app's sheet and the
// app picker lead there; its title row has the "?" of the guide at the Live Logcat topic; its long-press menu lists its filter, the log of one app and the
// recordings; when it is switched off, every way in says so.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async kv => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv || {}), log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
    await page.goto(PAGE); await page.waitForTimeout(350);
    return page;
  };
  let page = await open();
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);

  const tab = await ev(() => ({ def: tabDef('logcat'), bar: [...document.querySelectorAll('.tab-btn')].map(x => x.dataset.tab).join(), btn: (document.querySelector('.tab-btn[data-tab="logcat"]') || {}).innerText }));
  check('1. a Live Logcat tab right after Console, in the place of the Terminal tab', tab.def && tab.def.name === 'Live Logcat' && tab.def.label === 'Live\nLogcat' && /console,logcat,settings/.test(tab.bar) && tab.btn === 'Live\nLogcat', JSON.stringify(tab));
  check('   Console no longer has a Live Logcat segment', await ev(() => !document.getElementById('cnTabLog') && !document.querySelector('#view-console #cnSegLog') && !!document.querySelector('#view-logcat #cnSegLog #logcatOutput')));
  check('   the log is not read before the tab is opened', await ev(() => !logcatTimer && window.__calls.logcat.length === 0));
  await page.click('.tab-btn[data-tab="logcat"]'); await sleep(500);
  let r = await ev(() => ({ view: currentViewName(), timer: !!logcatTimer, n: window.__calls.logcat.length, rows: document.querySelectorAll('#logcatOutput .lc-row').length, play: document.getElementById('logcatPlayBtn').innerText, shown: !!document.getElementById('logcatOutput').offsetParent }));
  check('2. opening the tab starts the log (Play turns into Stop) and shows its lines', r.view === 'logcat' && r.timer && r.n >= 1 && r.rows >= 1 && r.shown && r.play !== 'Play', JSON.stringify(r));
  await ev(() => switchView('console')); await sleep(200);
  r = await ev(() => ({ timer: !!logcatTimer, play: document.getElementById('logcatPlayBtn').innerText }));
  check('3. leaving the tab (here to Console) stops the log', !r.timer && r.play === 'Play', JSON.stringify(r));
  const n = await ev(() => window.__calls.logcat.length); await sleep(2500);
  check('   and nothing is read while it is away', await ev(n => window.__calls.logcat.length === n, n));
  const ok = await ev(() => switchView('logcat')); await sleep(400);
  check('4. switchView(\'logcat\') opens the tab and reads again', ok === true && await ev(() => currentViewName() === 'logcat' && !!logcatTimer && document.querySelector('.tab-btn.active').dataset.tab === 'logcat'));

  // the ? and the title row
  const help = await ev(() => { const h = document.querySelector('#view-logcat .help-q'); return h ? { topic: h.dataset.help, row: !!h.closest('#lcTitleRow') } : null; });
  check('5. the title row has the ? of the guide at the Live Logcat topic', help && help.topic === 'tab-logcat' && help.row, JSON.stringify(help));
  const geo = await ev(() => { const row = document.getElementById('lcTitleRow').getBoundingClientRect(), t = document.getElementById('lcTitle').getBoundingClientRect(), q = document.querySelector('#view-logcat .help-q').getBoundingClientRect(), u = document.querySelector('#lcTitleRow .cn-ind').getBoundingClientRect(); return { rowH: Math.round(row.height), titleRight: Math.round(t.right), qLeft: Math.round(q.left), ul: Math.round(u.width), titleW: Math.round(t.width) }; });
  check('   it is the Console\'s row: as tall, the title clear of the ?, the underline under the title', geo.rowH >= 48 && geo.titleRight <= geo.qLeft + 1 && Math.abs(geo.ul - geo.titleW) <= 2, JSON.stringify(geo));

  // the app picker and the Logs button of an app's sheet
  await page.click('#logcatAppBtn'); await sleep(150);
  await page.locator('#lcAppList .perm-info').nth(1).click(); await sleep(300);
  r = await ev(() => ({ app: logcatApp && logcatApp.pkg, view: currentViewName(), last: window.__calls.logcat.slice(-1)[0] }));
  check('6. the app picker limits the log to one app, in the tab', r.app === 'com.example.one' && r.view === 'logcat' && r.last[4] === 'com.example.one', JSON.stringify(r));
  await ev(() => { logcatSetApp(null); switchView('apps'); openInspector('com.example.two'); }); await sleep(300);
  await ev(() => openAppLog()); await sleep(500);
  r = await ev(() => ({ view: currentViewName(), app: logcatApp && logcatApp.pkg, sheet: document.getElementById('inspectorModal').classList.contains('show'), timer: !!logcatTimer }));
  check('   Logs in an app\'s sheet closes the sheet and opens Live Logcat limited to that app', r.view === 'logcat' && r.app === 'com.example.two' && !r.sheet && r.timer, JSON.stringify(r));

  // the long-press menu
  const sc = await ev(() => ({ ids: TAB_SHORTCUTS.logcat.map(i => i.id).join(), shown: bnavScList('logcat').map(i => bnavScLabel(i)).join(), icon: !!BNAV_ICONS.logcat, short: bnavShort('logcat') }));
  check('7. its long-press menu: Filter the log, Log of one app, Recordings; it has its own icon and "Logcat" on the bottom bar', sc.ids === 'filter,app,recordings' && sc.shown === 'Filter the log,Log of one app,Recordings' && sc.icon && sc.short === 'Logcat', JSON.stringify(sc));
  await ev(() => { switchView('apps'); TAB_SHORTCUTS.logcat.find(i => i.id === 'filter').run(); }); await sleep(400);
  check('   "Filter the log" opens the tab with the filter focused', await ev(() => currentViewName() === 'logcat' && document.activeElement.id === 'logcatFilter'));
  await ev(() => { switchView('apps'); TAB_SHORTCUTS.logcat.find(i => i.id === 'app').run(); }); await sleep(400);
  check('   "Log of one app" opens the tab and its app picker', await ev(() => currentViewName() === 'logcat' && document.getElementById('lcAppModal').classList.contains('show')));
  await ev(() => lcAppClose()); await sleep(100);
  await page.close();

  // switched off in the Feature List
  page = await open({ tab_config: JSON.stringify({ order: ['saved-lists', 'debloater', 'installer', 'files', 'console', 'logcat', 'settings', 'extrasettings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'sdm'], off: ['logcat'] }) });
  await page.evaluate(() => openInspector('com.example.one')); await page.waitForTimeout(300);
  await page.evaluate(() => openAppLog()); await page.waitForTimeout(200);
  r = await page.evaluate(() => ({ view: currentViewName(), toast: document.getElementById('toastMsg').innerText, sheet: document.getElementById('inspectorModal').classList.contains('show'), app: logcatApp, timer: !!logcatTimer, n: window.__calls.logcat.length, ok: switchView('logcat'), view2: currentViewName() }));
  check('8. Live Logcat off: Logs in an app\'s sheet says so and keeps the sheet; switchView(\'logcat\') returns false; nothing is read', /Live Logcat is turned off/.test(r.toast) && r.sheet && !r.app && !r.timer && r.n === 0 && r.ok === false && r.view2 === 'apps', JSON.stringify(r));
  check('   Console stays on', await page.evaluate(() => tabIsOn('console') && !!document.querySelector('.tab-btn[data-tab="console"]') && !document.querySelector('.tab-btn[data-tab="logcat"]')));
  await page.close();

  check('9. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
