// Console tab (v7.12.12): what happens to tab choices saved by an older version, and every way into the log that used to open the Logcat Viewer tab.
// The Command-Line Interface tab ('terminal') became Console ('console') and Terminal ('terminal'); the Logcat Viewer tab ('logcat') moved into Console.
// v7.14.0: Terminal became Console's second segment and Live Logcat ('logcat') a tab again, in Terminal's place: a choice saved before v7.12.12 goes through
// both steps (Console in the old place of the Command-Line Interface, Live Logcat right after it with what was saved for Terminal), switchView('logcat')
// opens the Live Logcat tab, switchView('terminal') Console on its Terminal segment.
//   - a saved choice that has 'terminal' and no 'console': Console takes the old place of 'terminal' (right before it), and is off when 'terminal' was off;
//     what was saved for 'logcat' (position, off) is dropped, except that a Logcat Viewer that was on keeps Console on (t402); a choice that already has 'console' is left alone; damaged choices fall back to the default,
//   - switchView('logcat') (the old name) opens Console on Live Logcat, or says Console is off; the app picker, the Logs button of an app's sheet,
//     the "?" of the tab, the Back trail and the Terminal tab (no console pane in it any more).
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const open = async (kv, opts) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, Object.assign({ kv: Object.assign({ perm_intro_v61: '1' }, kv || {}) }, opts || {}));
    await page.goto(PAGE); await page.waitForTimeout(350);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const bar = page => ev(page, () => Array.from(document.querySelectorAll('.tab-btn')).map(b => b.dataset.tab));
  const OLD_ORDER = ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm'];
  const cfg = (order, off) => ({ tab_config: JSON.stringify({ order, off }) });
  const join = a => a.join(',');

  // ---- 1) migration of a saved tab choice ----
  let page = await open({});
  check('no saved choice: the default bar has Console right before Live Logcat and no Terminal tab', join(await bar(page)) === 'apps,saved-lists,debloater,installer,files,console,logcat,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about');
  await page.close();

  page = await open(cfg(OLD_ORDER, []));
  check('an old choice (everything on, the old order): Console takes the place of the Command-Line Interface, Live Logcat follows (in Terminal\'s place), the old Logcat Viewer place is gone', join(await bar(page)) === 'apps,saved-lists,debloater,installer,files,console,logcat,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about', join(await bar(page)));
  await page.close();

  // moved around by the person: the Command-Line Interface at the very front, the Logcat Viewer second to last
  const moved = ['terminal', 'saved-lists', 'debloater', 'installer', 'files', 'settings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'logcat', 'sdm'];
  page = await open(cfg(moved, []));
  check('a moved Command-Line Interface keeps its place for Console (first), Live Logcat right after it', join(await bar(page)) === 'apps,console,logcat,saved-lists,debloater,installer,files,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about', join(await bar(page)));
  await page.close();

  // the Command-Line Interface and the Logcat Viewer off: Console is off too, and so is Live Logcat (with the Logcat Viewer on, Live Logcat stays on: t402)
  page = await open(cfg(OLD_ORDER, ['terminal', 'logcat']));
  let r = await ev(page, () => ({ bar: Array.from(document.querySelectorAll('.tab-btn')).map(b => b.dataset.tab).join(), off: tabConfig.off.join(), on: tabIsOn('console') + ':' + tabIsOn('logcat') }));
  check('the Command-Line Interface and the Logcat Viewer were off: Console is off and so is Live Logcat', !r.bar.split(',').includes('console') && !r.bar.split(',').includes('logcat') && r.off === 'logcat,console' && r.on === 'false:false', JSON.stringify(r));
  await ev(page, () => { switchView('prefs'); });
  const rows = await ev(page, () => Array.from(document.querySelectorAll('#featureList .fl-row')).map(x => x.dataset.tab + ':' + x.querySelector('.switch-input').checked));
  check('the Feature List shows both switched off and has no row for Terminal', rows.includes('console:false') && rows.includes('logcat:false') && !rows.some(x => /terminal/.test(x)), join(rows));
  await ev(page, () => featureToggle('console', true));
  check('switching Console on again brings it back before the (still off) Live Logcat', (await bar(page)).join() === 'apps,saved-lists,debloater,installer,files,console,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about', join(await bar(page)));
  await page.close();

  // the old logcat state is ignored whatever it was
  page = await open(cfg(OLD_ORDER, ['logcat', 'files']));
  r = await ev(page, () => ({ bar: Array.from(document.querySelectorAll('.tab-btn')).map(b => b.dataset.tab).join(), off: tabConfig.off.join() }));
  check('the Logcat Viewer was off (and File Manager): Console is still on, only File Manager stays off, the old logcat state is dropped (Live Logcat takes Terminal\'s, on)', r.bar === 'apps,saved-lists,debloater,installer,console,logcat,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about' && r.off === 'files', JSON.stringify(r));
  await page.close();
  page = await open(cfg(OLD_ORDER.filter(k => k !== 'logcat'), ['terminal']));
  r = await ev(page, () => ({ bar: Array.from(document.querySelectorAll('.tab-btn')).map(b => b.dataset.tab).join(), off: tabConfig.off.join() }));
  check('an old choice without a logcat entry at all, the Command-Line Interface off: Console off too', !r.bar.includes('console') && r.off === 'logcat,console', JSON.stringify(r));
  await page.close();

  // a v7.12.12 choice (it knows Console) is not touched by the first step; the second puts Live Logcat in Terminal's place
  const NEW_ORDER = ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'console', 'settings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'sdm'];
  page = await open(cfg(NEW_ORDER, ['console']));
  r = await ev(page, () => ({ bar: Array.from(document.querySelectorAll('.tab-btn')).map(b => b.dataset.tab).join(), off: tabConfig.off.join(), order: tabConfig.order.join() }));
  check('a choice that already knows Console keeps Console as it was (here after Terminal and off); Live Logcat takes Terminal\'s place, on; the Extra Settings tab it does not know is slotted in after Hidden Settings', r.order === NEW_ORDER.join().replace('terminal', 'logcat').replace('settings,overlays', 'settings,extrasettings,overlays') + ',rootfeatures' && r.off === 'console' && r.bar === 'apps,saved-lists,debloater,installer,files,logcat,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures,about', JSON.stringify(r));
  await page.close();
  page = await open(cfg(NEW_ORDER, ['terminal']));
  r = await ev(page, () => ({ off: tabConfig.off.join(), console: tabIsOn('console'), logcat: tabIsOn('logcat') }));
  check('a choice that knows Console with only Terminal off keeps Console on (Live Logcat, in Terminal\'s place, is off)', r.off === 'logcat' && r.console && !r.logcat, JSON.stringify(r));
  await page.close();

  // nothing is written until the person changes something; after a change the new shape is saved
  page = await open(cfg(OLD_ORDER, []));
  check('migrating writes nothing by itself', JSON.parse(await ev(page, () => window.__kv.tab_config)).order.includes('terminal'));
  await ev(page, () => { switchView('prefs'); featureToggle('files', false); });
  const sv = JSON.parse(await ev(page, () => window.__kv.tab_config));
  check('the first change saves the migrated shape (Console and Live Logcat in, terminal out)', sv.order.join() === 'saved-lists,debloater,installer,files,console,logcat,settings,extrasettings,overlays,store,taskmgr,devices,morphe,sdm,rootfeatures' && sv.off.join() === 'files', JSON.stringify(sv));
  await page.close();

  // damaged choices
  for (const bad of ['{"order": 7, "off": "x"}', '{"order":["logcat","terminal"],"off":["logcat"]}', 'null', '[]', '"x"']) {
    page = await open({ tab_config: bad });
    const k = join(await bar(page));
    check('a damaged or odd saved choice (' + bad + ') leaves a sane bar: first Application Manager, last About, Console and Live Logcat each once, no terminal', k.startsWith('apps,') && k.endsWith(',about') && k.split(',').filter(x => x === 'console').length <= 1 && k.split(',').filter(x => x === 'logcat').length <= 1 && !k.includes('terminal') && new Set(k.split(',')).size === k.split(',').length, k);
    await page.close();
  }
  page = await open({ tab_config: '{"order":["logcat","terminal"],"off":["logcat"]}' });
  r = await ev(page, () => ({ order: tabConfig.order.join(), off: tabConfig.off.join() }));
  check('a short old choice with logcat and terminal: Console right before Live Logcat (the old logcat entry not used), no terminal, the rest in their default places', r.order.includes('console,logcat') && r.order.split(',').filter(k => k === 'logcat').length === 1 && !r.order.includes('terminal') && r.off === '' && r.order.split(',').length === 15, JSON.stringify(r));
  await page.close();

  // ---- 2) the old way into the log: switchView('logcat') and the other entry points ----
  page = await open({}, { log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
  await ev(page, () => switchView('apps')); await sleep(100);
  let ok = await ev(page, () => switchView('logcat'));
  await sleep(500);
  r = await ev(page, () => ({ view: currentViewName(), shown: !!document.getElementById('cnSegLog').offsetParent, timer: !!logcatTimer, active: document.querySelector('.tab-btn.active').dataset.tab }));
  check('switchView(\'logcat\') opens the Live Logcat tab and starts the log', ok === true && r.view === 'logcat' && r.shown && r.timer && r.active === 'logcat', JSON.stringify(r));
  await ev(page, () => switchView('console')); await sleep(100);
  await page.click('#cnTabShell'); await sleep(100);
  ok = await ev(page, () => switchView('logcat')); await sleep(400);
  check('...also from Console on ADB Shell', ok === true && await ev(page, () => currentViewName() === 'logcat' && !!logcatTimer));
  // back trail
  await ev(page, () => { switchView('files'); switchView('logcat'); switchView('terminal'); });
  const trail = [];
  for (let i = 0; i < 2; i++) { await ev(page, () => backNavigate()); await sleep(80); trail.push(await ev(page, () => currentViewName())); }
  check('the Back trail goes console (Terminal) -> logcat -> files', trail.join() === 'logcat,files', trail.join());
  // the app picker
  await ev(page, () => { switchView('logcat'); }); await sleep(300);
  await page.click('#logcatAppBtn'); await sleep(150);
  check('All apps opens the app picker over Live Logcat', await ev(page, () => document.getElementById('lcAppModal').classList.contains('show')));
  await page.locator('#lcAppList .perm-info').nth(1).click(); await sleep(300);
  r = await ev(page, () => ({ btn: document.getElementById('logcatAppBtn').innerText, clr: getComputedStyle(document.getElementById('logcatAppClear')).display, scope: document.getElementById('logcatScope').innerText, app: logcatApp && logcatApp.pkg, view: currentViewName() }));
  check('picking an app limits the log, relabels the button, shows Show all apps and says which package', /One/.test(r.btn) && r.clr !== 'none' && /com\.example\.one/.test(r.scope) && r.app === 'com.example.one' && r.view === 'logcat', JSON.stringify(r));
  await page.click('#logcatAppClear'); await sleep(200);
  check('Show all apps goes back to the whole log and clears the note', await ev(page, () => !logcatApp && document.getElementById('logcatAppBtn').innerText === 'All apps ▾' && document.getElementById('logcatScope').innerText === ''));
  // the Logs button of an app's sheet
  await ev(page, () => { switchView('apps'); }); await sleep(100);
  await ev(page, () => openInspector('com.example.one')); await sleep(300);
  await ev(page, () => openAppLog()); await sleep(500);
  r = await ev(page, () => ({ view: currentViewName(), app: logcatApp && logcatApp.pkg, sheet: document.getElementById('inspectorModal').classList.contains('show'), timer: !!logcatTimer, last: window.__calls.logcat.slice(-1)[0] }));
  check('Logs in an app\'s sheet: closes the sheet, opens Live Logcat limited to that app, reading', r.view === 'logcat' && r.app === 'com.example.one' && !r.sheet && r.timer && r.last[4] === 'com.example.one', JSON.stringify(r));
  // the ? of the tab
  const help = await ev(page, () => { const h = document.querySelector('#view-console .help-q'); return h ? { topic: h.dataset.help, inTabs: !!h.closest('#cnTabs') } : null; });
  check('the tab has a ? that opens the guide at the Console topic', help && help.topic === 'tab-console' && help.inTabs, JSON.stringify(help));
  const guide = await ev(page, () => { const h = document.querySelector('#view-console .help-q'); h.click(); return new Promise(res => setTimeout(() => res({ open: document.getElementById('helpGuideModal').classList.contains('show') }), 600)); });
  check('...and the guide opens', guide.open);
  const topics = await ev(page, () => new Promise(res => { const t = () => { const d = window.HELP_GUIDE; if (d) res(JSON.stringify(d).match(/"id":"(tab-console|console-logcat|tab-terminal|tab-logcat)"/g)); else setTimeout(t, 100); }; t(); }));
  check('the guide has Console, Terminal and Live Logcat pages (tab-logcat since v7.14.0), and no console-logcat page', topics && topics.length === 3 && !topics.some(x => /console-logcat/.test(x)), JSON.stringify(topics));
  await ev(page, () => { document.getElementById('helpGuideModal').classList.remove('show'); });
  await page.close();

  // an old choice with the Command-Line Interface and the Logcat Viewer off (Console and Live Logcat off): every way into the log says so and changes nothing
  page = await open(cfg(OLD_ORDER, ['terminal', 'logcat']), { log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
  await ev(page, () => openInspector('com.example.one')); await sleep(300);
  await ev(page, () => openAppLog()); await sleep(200);
  r = await ev(page, () => ({ view: currentViewName(), toast: document.getElementById('toastMsg').innerText, sheet: document.getElementById('inspectorModal').classList.contains('show'), app: logcatApp, timer: !!logcatTimer, n: window.__calls.logcat.length, seg: cnSeg }));
  check('Live Logcat off: Logs in an app\'s sheet says Live Logcat is off, keeps the sheet, sets no filter, reads nothing and keeps Console\'s saved part', /Live Logcat is turned off/.test(r.toast) && r.sheet && r.view === 'apps' && !r.app && !r.timer && r.n === 0 && r.seg === 'shell', JSON.stringify(r));
  ok = await ev(page, () => switchView('logcat'));
  check('Live Logcat off: switchView(\'logcat\') returns false and stays', ok === false && (await ev(page, () => currentViewName())) === 'apps');
  await page.close();

  // ---- 3) the Terminal (a segment of Console since v7.14.0) without the console pane ----
  page = await open({});
  await ev(page, () => switchView('terminal')); await sleep(300);
  r = await ev(page, () => ({ pane: !!document.getElementById('txPaneTerminal').offsetParent, cmd: !!document.querySelector('#cnSegTerm #termCmd'), sw: !!document.getElementById('txSwitch'), agent: !!document.getElementById('txAgent'), shell: !!document.getElementById('txShell'), full: !!document.getElementById('txFullBtn'), real: !!document.getElementById('txRealBtn'), input: !!document.getElementById('txInput'), tabs: !!window.txShowPane, opened: txOpened }));
  check('switchView(\'terminal\') opens Console on the Terminal, the terminal and the coding agents only: its pane shows at once, with agents, shells, Full screen, Real terminal and the input; no console box, no switch', r.pane && !r.cmd && !r.sw && r.agent && r.shell && r.full && r.real && r.input && !r.tabs && r.opened, JSON.stringify(r));

  // no text of the page sends a person to the Command-Line Interface tab (it is Terminal and Console now)
  const stale = await ev(page, () => { const c = document.documentElement.cloneNode(true); c.querySelectorAll('script, style').forEach(e => e.remove()); askAgentNote(); return { markup: /Command-Line/.test(c.innerHTML), note: document.getElementById('askAgentNote').innerText, sub: [...document.querySelectorAll('.color-card-subtitle')].map(e => e.innerText).filter(t => /Command-Line|Terminal tab|Console > Terminal/.test(t)) }; });
  check('no text of the page names the Command-Line Interface tab; the Default Ask Agent texts say Console → Terminal', !stale.markup && /Console → Terminal/.test(stale.note + ' ' + stale.sub.join(' ')) && !/Command-Line|Terminal tab/.test(stale.note + ' ' + stale.sub.join(' ')), JSON.stringify(stale));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
