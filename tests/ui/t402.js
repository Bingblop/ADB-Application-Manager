// A tab_config saved by v7.12.0 still names the App Updater ('updates') and Logcat Viewer ('logcat') tabs (PG2-1). They are now a box of the APK
// Installer/Updater tab and a segment of Console: with APK Installer off and App Updater on the updater used to disappear, and with the Command-Line
// Interface off and Logcat Viewer on the Console tab (and the log) did. The tab that took the old one in now stays on; with both off it stays off.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  // the v7.12.0 order of the tabs that can move
  const order = ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'updates', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm'];
  const open = async off => {
    const p = await b.newPage({ viewport: { width: 360, height: 740 } });
    p.bottomNav = true;
    p.on('pageerror', e => errors.push(e.message));
    await p.addInitScript(mock.initScript, { kv: { tab_config: JSON.stringify({ order, off }) } });
    await p.goto(PAGE); await p.waitForTimeout(600);
    return p;
  };
  const state = p => p.evaluate(() => ({ off: tabConfig.off.slice().sort().join(), shown: tabsShown(), updates: tabIsOn('updates'), logcat: tabIsOn('logcat') }));

  let p = await open(['installer']);
  let s = await state(p);
  check('1. APK Installer off, App Updater on: the APK Installer/Updater tab stays on', s.updates && s.shown.includes('installer') && !s.off.includes('installer'), JSON.stringify(s));
  await p.evaluate(() => switchView('updates')); await p.waitForTimeout(100);
  check('   the updater opens', await p.evaluate(() => currentViewName()) === 'installer', await p.evaluate(() => currentViewName()));
  await p.close();

  p = await open(['terminal']);
  s = await state(p);
  check('2. Command-Line Interface off, Logcat Viewer on: Console stays on (the log is in it)', s.logcat && s.shown.includes('console') && !s.off.includes('console'), JSON.stringify(s));
  check('   the new Terminal tab follows the Command-Line Interface and stays off', !s.shown.includes('terminal') && s.off.split(',').includes('terminal'), JSON.stringify(s));
  await p.close();

  p = await open(['installer', 'updates', 'terminal', 'logcat']);
  s = await state(p);
  check('3. both old tabs off as well: APK Installer/Updater and Console stay off', !s.updates && !s.logcat && !s.shown.includes('installer') && !s.shown.includes('console'), JSON.stringify(s));
  await p.close();

  p = await open(['updates', 'logcat']);
  s = await state(p);
  check('4. only the old tabs off: the tabs that took them in are on (as before)', s.updates && s.logcat, JSON.stringify(s));
  await p.close();

  // a record saved by this version has neither old key: an APK Installer/Updater or Console switched off stays off
  p = await b.newPage({ viewport: { width: 360, height: 740 } });
  p.bottomNav = true;
  p.on('pageerror', e => errors.push(e.message));
  await p.addInitScript(mock.initScript, { kv: { tab_config: JSON.stringify({ order: order.filter(k => k !== 'updates' && k !== 'logcat').concat(['console', 'extrasettings']), off: ['installer', 'console'] }) } });
  await p.goto(PAGE); await p.waitForTimeout(600);
  s = await state(p);
  check('5. a current record: APK Installer/Updater and Console switched off stay off', !s.updates && !s.logcat, JSON.stringify(s));
  await p.close();

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
