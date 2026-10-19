// v7.14.0: a tab choice saved by v7.12.12 - v7.13.x (Console and Terminal tabs) is carried over: Live Logcat ('logcat') takes what was saved for Terminal
// ('terminal'): its place in the order, off, pinned on the bottom bar. The long-press shortcuts chosen for Terminal lead into Console now, so they join
// Console's choice (its old Live Logcat entry goes). A choice from before v7.12.12 goes through both steps. Nothing is written until the person changes
// something, and a choice in the new shape is left alone.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async cfg => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1', tab_config: JSON.stringify(cfg) } });
    await page.goto(PAGE); await page.waitForTimeout(350);
    return page;
  };
  const st = page => page.evaluate(() => ({ order: tabConfig.order.join(), off: tabConfig.off.join(), pinned: tabConfig.pinned.join(), sc: JSON.stringify(tabConfig.shortcuts),
    bar: [...document.querySelectorAll('.tab-btn')].map(x => x.dataset.tab).join(), bnav: [...document.querySelectorAll('#bnav .bn-item[data-tab]')].map(x => x.dataset.tab).join() }));
  const V13 = ['saved-lists', 'debloater', 'installer', 'files', 'console', 'terminal', 'settings', 'extrasettings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'sdm'];

  // a v7.13 choice: Terminal pinned, its shortcuts and Console's chosen
  let page = await open({ order: V13, off: [], pinned: ['apps', 'installer', 'terminal', 'console'], shortcuts: { terminal: ['termux', 'settings'], console: ['log', 'shell'], taskmgr: ['cpu'] } });
  let r = await st(page);
  check('1. Live Logcat takes the place of Terminal in the order', r.order === V13.join().replace('terminal', 'logcat') + ',rootfeatures', r.order);
  check('   and its pin on the bottom bar', r.pinned === 'apps,installer,logcat,console' && r.bnav === 'apps,installer,console,logcat', JSON.stringify(r));
  check('   Terminal\'s chosen shortcuts join Console\'s (its Live Logcat entry goes), other tabs keep theirs, Live Logcat starts with its defaults', r.sc === JSON.stringify({ console: ['shell', 'termux', 'settings'], taskmgr: ['cpu'] }), r.sc);
  check('   the Console menu then lists them', await page.evaluate(() => bnavScList('console').map(i => i.id).join()) === 'shell,termux,settings');
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  const rows = await page.evaluate(() => [...document.querySelectorAll('#featureList .fl-row')].map(x => x.dataset.tab + ':' + x.querySelector('.fl-name').textContent + ':' + x.querySelector('.fl-pin').getAttribute('aria-pressed')));
  check('2. the Feature List names it Live Logcat, pinned, and has no Terminal row', rows.includes('logcat:Live Logcat:true') && rows.includes('console:Console:true') && !rows.some(x => x.startsWith('terminal')), rows.join(' | '));
  check('   migrating writes nothing by itself', JSON.parse(await page.evaluate(() => window.__kv.tab_config)).order.includes('terminal'));
  await page.evaluate(() => featureToggle('files', false)); await page.waitForTimeout(100);
  const saved = JSON.parse(await page.evaluate(() => window.__kv.tab_config));
  check('   the first change saves the new shape (no terminal anywhere)', !JSON.stringify(saved).includes('terminal') && saved.order.includes('logcat') && saved.off.join() === 'files', JSON.stringify(saved));
  await page.close();
  page = await open(saved);
  r = await st(page);
  check('   and loading it again changes nothing', r.order === saved.order.join() && r.off === 'files' && r.pinned === saved.pinned.join() && r.sc === JSON.stringify(saved.shortcuts), JSON.stringify(r));
  await page.close();

  // Terminal off, moved to the front
  page = await open({ order: ['terminal'].concat(V13.filter(k => k !== 'terminal')), off: ['terminal', 'store'] });
  r = await st(page);
  check('3. Terminal was off: Live Logcat is off (Console stays on)', r.off === 'logcat,store' && !r.bar.split(',').includes('logcat') && r.bar.includes('console') && await page.evaluate(() => !tabIsOn('logcat') && tabIsOn('console') && tabIsOn('terminal')), JSON.stringify(r));
  check('   Terminal moved to the front: Live Logcat is first', r.order.startsWith('logcat,saved-lists'), r.order);
  await page.close();

  // Terminal's shortcuts without a Console choice: Console's two segments first, then those
  page = await open({ order: V13, off: [], pinned: ['apps', 'console'], shortcuts: { terminal: ['agents'] } });
  r = await st(page);
  check('4. Terminal\'s shortcuts and no Console choice: ADB Shell, Terminal, then those', r.sc === JSON.stringify({ console: ['shell', 'term', 'agents'] }), r.sc);
  await page.close();
  page = await open({ order: V13, off: [], pinned: ['apps', 'console'], shortcuts: { console: ['log', 'shell'] } });
  check('   a Console choice alone loses its Live Logcat entry', (await st(page)).sc === JSON.stringify({ console: ['shell'] }));
  await page.close();

  // a choice from before v7.12.12 (Command-Line Interface 'terminal', Logcat Viewer 'logcat'): both steps
  const V12 = ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm'];
  page = await open({ order: V12, off: ['logcat'], pinned: ['apps', 'terminal', 'logcat'] });
  r = await st(page);
  check('5. before v7.12.12: Console and Live Logcat where the Command-Line Interface was; the old Logcat Viewer entry (its place, off, pin) is not used', r.order.startsWith('saved-lists,debloater,installer,files,console,logcat,settings') && r.order.split(',').filter(k => k === 'logcat').length === 1 && r.off === '' && r.pinned === 'apps,console', JSON.stringify(r));
  await page.close();

  // a new choice is left alone
  const NEW = ['logcat', 'saved-lists', 'debloater', 'installer', 'files', 'console', 'settings', 'extrasettings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'sdm'];
  page = await open({ order: NEW, off: ['console'], pinned: ['apps', 'logcat'], shortcuts: { console: ['full'], logcat: ['recordings'] } });
  r = await st(page);
  check('6. a choice in the new shape is not touched', r.order === NEW.join() + ',rootfeatures' && r.off === 'console' && r.pinned === 'apps,logcat' && r.sc === JSON.stringify({ console: ['full'], logcat: ['recordings'] }), JSON.stringify(r));
  await page.close();

  // damaged
  for (const bad of ['{"order":["terminal"],"off":"x","pinned":7,"shortcuts":[1]}', '{"order":["logcat","terminal"],"off":["logcat"]}', '{"order":null,"off":["terminal"]}']) {
    page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1', tab_config: bad } });
    await page.goto(PAGE); await page.waitForTimeout(300);
    const k = (await st(page)).bar.split(',');
    check('7. an odd saved choice (' + bad + ') leaves a sane bar: Application Manager first, About last, no terminal, each tab once', k[0] === 'apps' && k[k.length - 1] === 'about' && !k.includes('terminal') && new Set(k).size === k.length, k.join());
    await page.close();
  }

  check('8. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
