// v7.0 Tabs: the tab bar is built from one registry (new names, two-line labels, new order: Updates before Stores, Logcat Viewer before About); the
// header gear opens Settings; Settings has a Feature List (turn tabs off and on, move them, Reset to Default) with Application Manager and About fixed;
// the choice is kept, and a saved choice from an older version is made to fit the tabs this version has.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const DEFAULT_ORDER = ['apps', 'saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'updates', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm', 'about'];

(async () => {
  const b = await chromium.launch();
  const open = async (kv, size, apps) => {
    const page = await b.newPage({ viewport: size || { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv || {}), apps });
    await page.goto(PAGE);
    await page.waitForFunction(() => document.querySelectorAll('.tab-btn').length > 0);
    if (apps) await page.waitForFunction(n => document.getElementById('statTotal').innerText === String(n), apps.length);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const bar = page => ev(page, () => Array.from(document.querySelectorAll('.tab-btn')).map(b => ({ key: b.dataset.tab, text: b.innerText, active: b.classList.contains('active') })));
  const keys = async page => (await bar(page)).map(t => t.key);
  const rows = page => ev(page, () => Array.from(document.querySelectorAll('#featureList .fl-row')).map(r => ({ key: r.dataset.tab, name: r.querySelector('.fl-name').innerText, on: r.querySelector('.switch-input').checked, up: !r.querySelectorAll('.fl-move')[0].disabled, down: !r.querySelectorAll('.fl-move')[1].disabled })));
  const saved = page => ev(page, () => { const v = window.__kv.tab_config; return v ? JSON.parse(v) : null; });

  // 1) the bar: names, two lines, order
  let page = await open();
  let t = await bar(page);
  check('1. sixteen tabs in the new order', JSON.stringify(t.map(x => x.key)) === JSON.stringify(DEFAULT_ORDER), JSON.stringify(t.map(x => x.key)));
  check('   named as asked, two lines where it reads better', JSON.stringify(t.map(x => x.text)) === JSON.stringify(['Application\nManager', 'Saved\nApplications', 'UAD-NG\nDebloater', 'APK\nInstaller', 'File\nManager', 'Command-Line\nInterface', 'Hidden\nSettings', 'RRO/Monet\nCustomization', 'App\nUpdater', 'App\nStores', 'Logcat\nViewer', 'Task\nManager', 'Connected\nDevices', 'Morphe\nPatcher', 'SD Maid',  'About']), JSON.stringify(t.map(x => x.text)));
  check('   Application Manager is active at the start', t[0].active && t.filter(x => x.active).length === 1);
  const geo = await ev(page, () => Array.from(document.querySelectorAll('.tab-btn')).map(b => ({ h: Math.round(b.getBoundingClientRect().height), clipped: b.scrollWidth > b.clientWidth + 1, font: getComputedStyle(b).fontSize, lines: b.innerText.split('\n').length })));
  check('   every tab is at least 44 px tall and no label is cut off', geo.every(g => g.h >= 44 && !g.clipped), JSON.stringify(geo.map(g => g.h + (g.clipped ? '!' : ''))));
  check('   the text is a little larger than before (13.5 px, it was 12 px)', geo.every(g => g.font === '13.5px'), geo[0].font);
  check('   the page itself does not scroll sideways', await ev(page, () => document.documentElement.scrollWidth <= window.innerWidth));
  await ev(page, () => switchView('installer'));
  t = await bar(page);
  check('   switching marks the matching tab and only that one', t.filter(x => x.active).map(x => x.key).join() === 'installer');
  await ev(page, () => switchView('store'));
  check('   (also for a tab that was moved)', (await bar(page)).filter(x => x.active).map(x => x.key).join() === 'store');

  // 2) the header gear opens Settings
  const gear = await ev(page, () => { const g = document.getElementById('prefsHeaderBtn'); return { text: g.innerText.replace(/️/g, ''), label: g.getAttribute('aria-label'), role: g.getAttribute('role') }; });
  check('2. the header button is a gear called Settings (not the colours palette)', gear.text === '⚙' && gear.label === 'Settings' && gear.role === 'button', JSON.stringify(gear));
  await page.click('#prefsHeaderBtn');
  const s2 = await ev(page, () => ({ view: currentViewName(), anyTab: !!document.querySelector('.tab-btn.active'), gearOn: document.getElementById('prefsHeaderBtn').classList.contains('active') }));
  check('   it opens Settings, no tab is lit and the gear shows that you are there', s2.view === 'prefs' && !s2.anyTab && s2.gearOn, JSON.stringify(s2));
  await ev(page, () => switchView('apps'));
  check('   the gear goes dark again on a tab', !(await ev(page, () => document.getElementById('prefsHeaderBtn').classList.contains('active'))));

  // 3) the Feature List: after the colour options, every tab but the two fixed ones
  await ev(page, () => switchView('prefs'));
  const order3 = await ev(page, () => Array.from(document.querySelectorAll('#view-prefs > .color-card')).map(c => c.querySelector('.color-card-title').innerText.trim()));
  check('3. Settings: the language at the very top, then Appearance and the colour cards, the Feature List after them, then the Press and hold guide at the very bottom (Trim Caches moved to the SD Maid tab in v7.10.10)', order3[0] === 'Language' && /Appearance/.test(order3[1]) && order3.indexOf('Feature List') >= 0 && order3[order3.length - 2] === 'Feature List' && order3[order3.length - 1] === 'Press and hold guide' && order3.indexOf('Trim Caches') < 0, JSON.stringify(order3));
  let r = await rows(page);
  check('   fourteen rows, every tab except Application Manager and About, in the tab bar order, all on', r.length === 14 && r.map(x => x.key).join() === DEFAULT_ORDER.slice(1, 15).join() && r.every(x => x.on), JSON.stringify(r.map(x => x.name)));
  check('   the first row cannot move up and the last cannot move down', r[0].up === false && r[0].down === true && r[13].up === true && r[13].down === false);
  check('   the names are the full names', r.map(x => x.name).join('|') === 'Saved Applications|UAD-NG Debloater|APK Installer|File Manager|Command-Line Interface|Hidden Settings|RRO/Monet Customization|App Updater|App Stores|Logcat Viewer|Task Manager|Connected Devices|Morphe Patcher|SD Maid');
  const a11y = await ev(page, () => ({ switches: Array.from(document.querySelectorAll('#featureList .switch-input')).every(i => i.getAttribute('aria-label')), moves: Array.from(document.querySelectorAll('#featureList .fl-move')).every(b => /^Move .+ (up|down)$/.test(b.getAttribute('aria-label'))) }));
  check('   every switch and arrow has a name for a screen reader', a11y.switches && a11y.moves, JSON.stringify(a11y));
  check('   there is a Reset to Default button', (await ev(page, () => document.getElementById('featureResetBtn').innerText)) === 'Reset to Default');
  check('   nothing is saved before the person changes something', (await saved(page)) === null);

  // 4) turning a tab off takes it from the bar; on puts it back where it was
  await page.locator('#featureList .fl-row[data-tab="files"] .fl-switch').click();
  r = await rows(page);
  check('4. turning File Manager off: it leaves the bar, the row stays (dimmed) so it can be turned on again', !(await keys(page)).includes('files') && r.find(x => x.key === 'files').on === false && (await keys(page)).length === 15);
  check('   Application Manager and About are still first and last', (await keys(page))[0] === 'apps' && (await keys(page)).slice(-1)[0] === 'about');
  check('   the choice is saved', JSON.stringify(await saved(page)) === JSON.stringify({ order: DEFAULT_ORDER.slice(1, 15), off: ['files'] }), JSON.stringify(await saved(page)));
  await page.locator('#featureList .fl-row[data-tab="debloater"] .fl-switch').click();
  check('   two off at once', (await keys(page)).join() === 'apps,saved-lists,installer,terminal,settings,overlays,updates,store,logcat,taskmgr,devices,morphe,sdm,about');
  await page.locator('#featureList .fl-row[data-tab="files"] .fl-switch').click();
  check('   File Manager back on: it returns to its place in the bar', (await keys(page)).join() === 'apps,saved-lists,installer,files,terminal,settings,overlays,updates,store,logcat,taskmgr,devices,morphe,sdm,about');
  await page.locator('#featureList .fl-row[data-tab="debloater"] .fl-switch').click();

  // 5) a tab that is off cannot be opened by the app's own links either
  await page.locator('#featureList .fl-row[data-tab="logcat"] .fl-switch').click();
  await ev(page, () => switchView('logcat'));
  const s5 = await ev(page, () => ({ view: currentViewName(), toast: document.getElementById('toastMsg').innerText }));
  check('5. switchView on a tab that is off stays where it is and says why', s5.view === 'prefs' && /Logcat Viewer is turned off/.test(s5.toast) && /Feature List/.test(s5.toast), JSON.stringify(s5));
  await ev(page, () => { viewStack = ['files', 'logcat', 'installer']; tabsApplyConfig(); });
  check('   Back never lands on a tab that is off', (await ev(page, () => viewStack.join())) === 'files,installer');
  await page.locator('#featureList .fl-row[data-tab="logcat"] .fl-switch').click();
  await ev(page, () => switchView('logcat'));
  check('   and when it is on again the link works', (await ev(page, () => currentViewName())) === 'logcat');
  await ev(page, () => switchView('prefs'));

  // 6) moving
  await page.locator('#featureList .fl-row[data-tab="terminal"] .fl-move').first().click();
  r = await rows(page);
  check('6. ADB Console up one: Feature List and tab bar agree', r.map(x => x.key).slice(0, 6).join() === 'saved-lists,debloater,installer,terminal,files,settings', r.map(x => x.key).join());
  const barKeys6 = (await keys(page)).filter(k => k !== 'apps' && k !== 'about');
  check('   the tab bar follows (same order, same tabs)', barKeys6.join() === r.map(x => x.key).join(), barKeys6.join());
  const focus6 = await ev(page, () => ({ key: document.activeElement.closest('.fl-row') ? document.activeElement.closest('.fl-row').dataset.tab : '', cls: document.activeElement.className }));
  check('   the arrow that was pressed keeps focus, so it can be pressed again', focus6.key === 'terminal' && focus6.cls === 'fl-move', JSON.stringify(focus6));
  await page.locator('#featureList .fl-row[data-tab="logcat"] .fl-move').first().click();
  await page.locator('#featureList .fl-row[data-tab="logcat"] .fl-move').first().click();
  check('   several steps in a row', (await rows(page)).findIndex(x => x.key === 'logcat') === 7);
  await page.locator('#featureList .fl-row[data-tab="saved-lists"] .fl-move').nth(1).click();
  r = await rows(page);
  check('   down works too, and the first row then can move up', r[1].key === 'saved-lists' && r[0].up === false && r[1].up === true);
  const sv = await saved(page);
  check('   the order is saved', sv && sv.order.join() === r.map(x => x.key).join() && sv.off.length === 0, JSON.stringify(sv));

  // 7) the choice survives a restart; Reset to Default
  await page.locator('#featureList .fl-row[data-tab="installer"] .fl-switch').click();
  const kvNow = await ev(page, () => window.__kv.tab_config);
  const p2 = await open({ tab_config: kvNow });
  const k2 = await keys(p2);
  const want = JSON.parse(kvNow);
  check('7. after a restart the same tabs show in the same order', k2.join() === ['apps'].concat(want.order.filter(k => !want.off.includes(k)), ['about']).join() && !k2.includes('installer'), k2.join());
  await ev(p2, () => switchView('prefs'));
  check('   and the Feature List shows the same switches', (await rows(p2)).find(x => x.key === 'installer').on === false);
  await p2.close();
  await page.click('#featureResetBtn');
  r = await rows(page);
  check('   Reset to Default: every tab on and in the first order again', r.map(x => x.key).join() === DEFAULT_ORDER.slice(1, 15).join() && r.every(x => x.on) && (await keys(page)).join() === DEFAULT_ORDER.join(), (await keys(page)).join());
  check('   and says so', /default order/.test(await ev(page, () => document.getElementById('toastMsg').innerText)));
  check('   the saved choice is the default one', JSON.stringify(await saved(page)) === JSON.stringify({ order: DEFAULT_ORDER.slice(1, 15), off: [] }));
  await page.close();

  // 8) a saved choice from an older version is made to fit this version's tabs
  const odd = { order: ['files', 'bogus', 'debloater', 'files'], off: ['bogus', 'installer', 'installer', 'apps', 'about'] };
  page = await open({ tab_config: JSON.stringify(odd) });
  const k8 = await keys(page);
  check('8. unknown tabs, repeats and the two fixed tabs in a saved choice are ignored', !k8.includes('bogus') && k8[0] === 'apps' && k8.slice(-1)[0] === 'about' && new Set(k8).size === k8.length, k8.join());
  check('   the tabs it does not know are placed after their default neighbour, the saved ones keep their order', k8.join() === 'apps,saved-lists,files,terminal,settings,overlays,updates,store,logcat,taskmgr,devices,morphe,sdm,debloater,about', k8.join());
  check('   the one it turned off stays off, once', !k8.includes('installer') && JSON.stringify(await ev(page, () => tabConfig.off)) === '["installer"]', JSON.stringify(await ev(page, () => tabConfig.off)));
  await page.close();
  page = await open({ tab_config: '{"order": 7, "off": "x"}' });
  check('   a damaged choice falls back to the default', (await keys(page)).join() === DEFAULT_ORDER.join());
  await page.close();

  // 9) the update count rides on the label of the App Updater tab (and does not fail when that tab is off)
  page = await open();
  await ev(page, () => { updList = [{ pkg: 'a' }, { pkg: 'b' }]; updateUpdatesTabBadge(); });
  check('9. App Updater shows how many updates wait', (await ev(page, () => document.getElementById('updatesTabBtn').innerText)) === 'App\nUpdater (2)');
  await ev(page, () => { switchView('prefs'); featureToggle('updates', false); });
  const err9 = await ev(page, () => { try { updateUpdatesTabBadge(); return ''; } catch (e) { return String(e); } });
  check('   with the tab off there is no button to change and nothing fails', err9 === '' && !(await ev(page, () => document.getElementById('updatesTabBtn'))));
  await ev(page, () => featureToggle('updates', true));
  check('   switching it on again keeps the count', (await ev(page, () => document.getElementById('updatesTabBtn').innerText)) === 'App\nUpdater (2)');
  await page.close();

  // 10) narrow and wide screens
  for (const w of [320, 360, 412]) {
    const p = await open({}, { width: w, height: 760 });
    await ev(p, () => switchView('prefs'));
    await ev(p, () => document.getElementById('featureCard').scrollIntoView({ block: 'start' }));
    await sleep(120);
    const m = await ev(p, () => {
      const card = document.getElementById('featureCard').getBoundingClientRect();
      const row = Array.from(document.querySelectorAll('#featureList .fl-row')).map(r => { const b = r.getBoundingClientRect(), sw = r.querySelector('.fl-switch').getBoundingClientRect(), name = r.querySelector('.fl-name'); return { inside: b.left >= card.left - 1 && b.right <= card.right + 1, switchIn: sw.right <= card.right + 1, nameWhole: name.scrollWidth <= name.clientWidth + 1, h: Math.round(b.height) }; });
      const btn = Array.from(document.querySelectorAll('#featureList .fl-move')).map(x => Math.round(x.getBoundingClientRect().width));
      return { rowsIn: row.every(x => x.inside && x.switchIn && x.nameWhole), minArrow: Math.min.apply(null, btn), noSideScroll: document.documentElement.scrollWidth <= window.innerWidth };
    });
    check('10. [' + w + ' px] the Feature List fits: rows inside the card, names whole, arrows at least 34 px, no sideways scroll', m.rowsIn && m.minArrow >= 34 && m.noSideScroll, JSON.stringify(m));
    await p.close();
  }

  // 11) a link inside the app to a tab that is switched off does nothing else either: no sheet closes, nothing starts in the background
  const OFF = off => JSON.stringify({ order: DEFAULT_ORDER.slice(1, 15), off });
  const open11 = async (off, extraKv) => {
    const p = await open(Object.assign({ tab_config: OFF(off) }, extraKv || {}), undefined, [{ pkg: 'com.example.one', name: 'One', isSystem: false, version: '1.0' }, { pkg: 'com.example.two', name: 'Two', isSystem: false, version: '1.0' }]);
    await ev(p, () => {
      window.__bg = [];
      const B = window.AndroidBridge;
      B.getUpdateState = () => JSON.stringify({ updates: [{ pkg: 'com.example.one', name: 'One', source: 'galaxy', availableVersion: '2.0', installedVersion: '1.0' }, { pkg: 'com.example.self', name: 'Self', source: 'self', availableVersion: '9' }], checkedAt: 1, running: false });
      B.installUpdate = pkg => { window.__bg.push('install:' + pkg); };
      B.checkSelfUpdate = () => { window.__bg.push('selfcheck'); return '{}'; };
      B.getLogcatAsync = (id, lvl, f, n, pkg) => { window.__bg.push('logcat:' + pkg); };
      B.getLogcat = () => ''; B.getLogcatFor = () => ''; B.checkForUpdates = () => {};
      bridgeUpdatesCache = { at: 0, list: [] };
    });
    return p;
  };
  const toast11 = p => ev(p, () => document.getElementById('toastMsg').innerText);
  const sheet11 = p => ev(p, () => document.getElementById('inspectorModal').classList.contains('show'));
  let p11 = await open11(['updates']);
  await ev(p11, () => openInspector('com.example.one'));
  await sleep(250);
  await ev(p11, () => { const b = document.querySelector('#sheetUpdateHint button'); b.click(); });
  const u11 = await ev(p11, () => ({ view: currentViewName(), bg: window.__bg.slice(), prog: Object.keys(updProgress), busy: backBusyReason() }));
  check('11. App Updater off: "Update" in an app\'s sheet says it is off, starts no download and leaves the sheet open', /App Updater is turned off/.test(await toast11(p11)) && await sheet11(p11) && u11.view === 'apps' && u11.bg.length === 0 && u11.prog.length === 0 && !u11.busy, JSON.stringify(u11));
  await ev(p11, () => openSelfUpdate());
  const su11 = await ev(p11, () => ({ view: currentViewName(), bg: window.__bg.slice() }));
  check('    "Download" for this app itself: the same, nothing is checked behind the scenes', await sheet11(p11) && su11.view === 'apps' && su11.bg.length === 0, JSON.stringify(su11));
  await ev(p11, () => { closeInspector(); switchView('about'); selfUpdChecked = true; aboutCheckUpdate(); });          // checked once before: the button would check again
  const ab11 = await ev(p11, () => ({ view: currentViewName(), bg: window.__bg.slice() }));
  check('    About > check for update: says where to switch the tab on, and stays on About', /App Updater is turned off/.test(await toast11(p11)) && ab11.view === 'about' && ab11.bg.length === 0, JSON.stringify(ab11));
  await p11.close();
  p11 = await open11(['logcat']);
  await ev(p11, () => openInspector('com.example.one'));
  await sleep(250);
  await ev(p11, () => openAppLog());
  const lg11 = await ev(p11, () => ({ view: currentViewName(), app: logcatApp, bg: window.__bg.slice() }));
  check('    Logcat Viewer off: "Logs" in an app\'s sheet says so, keeps the sheet and does not set a filter for later', /Logcat Viewer is turned off/.test(await toast11(p11)) && await sheet11(p11) && lg11.view === 'apps' && !lg11.app && lg11.bg.length === 0, JSON.stringify(lg11));
  await ev(p11, () => { closeInspector(); switchView('prefs'); featureToggle('logcat', true); switchView('logcat'); });
  check('    and once the tab is on, the log shows everything (no app filter left behind)', !(await ev(p11, () => logcatApp)));
  await p11.close();
  p11 = await open11(['updates']);
  await ev(p11, () => { openInspector('com.example.one'); });
  await sleep(250);
  await ev(p11, () => { switchView('prefs'); featureToggle('updates', true); });
  await ev(p11, () => { openInspector('com.example.one'); });
  await sleep(250);
  await ev(p11, () => { document.querySelector('#sheetUpdateHint button').click(); });
  const on11 = await ev(p11, () => ({ view: currentViewName(), bg: window.__bg.slice() }));
  check('    with the tab on again "Update" goes to it and starts the update', on11.view === 'updates' && on11.bg.includes('install:com.example.one'), JSON.stringify(on11));
  await p11.close();
  p11 = await open11(['installer']);
  await ev(p11, () => { window.__pkgs['content://x/1'] = { pkg: 'com.new.app', label: 'New App', version: '1', versionCode: 1, splits: [{ name: 'base.apk', size: 1 }], kind: 'apk' }; });
  await ev(p11, () => window.onInstallIntent('content://x/1'));
  await sleep(300);
  const ii11 = await ev(p11, () => ({ view: currentViewName(), on: tabIsOn('installer'), bar: !!document.querySelector('.tab-btn[data-tab="installer"]'), pkg: installData && installData.pkg, saved: JSON.parse(window.__kv.tab_config).off }));
  check('    a package opened from outside is not turned away by a switch: APK Installer comes back, says so and loads it', ii11.view === 'installer' && ii11.on && ii11.bar && ii11.pkg === 'com.new.app' && ii11.saved.length === 0 && /on again/.test(await toast11(p11)), JSON.stringify(ii11));
  await p11.close();
  const rec = { t: Date.now(), n: 0, sub: 'theme', undo: 'x', kind: 'apply', done: true };
  p11 = await open11(['overlays'], { ovl_resume: JSON.stringify(rec) });
  await sleep(1500);
  const ov11 = await ev(p11, () => ({ view: currentViewName(), snack: !!document.querySelector('.sdb-snack.show, #sdbSnack.show'), kept: window.__kv.ovl_resume }));
  check('    Overlays off at launch: no "Theme changed / Undo" bar on another tab, and the record is left to expire', ov11.view === 'apps' && !ov11.snack && JSON.parse(ov11.kept).n === 0, JSON.stringify(ov11));
  await p11.close();

  // 12) the Feature List keeps the keyboard focus where it was, and the row that moved stays where the thumb is
  page = await open();
  await ev(page, () => switchView('prefs'));
  await page.focus('#featureList .fl-row[data-tab="files"] .switch-input');
  await page.keyboard.press('Space');
  const f12 = await ev(page, () => { const a = document.activeElement; return { row: a.closest('.fl-row') && a.closest('.fl-row').dataset.tab, cls: a.className, on: a.checked }; });
  check('12. Space on a switch: it turns off and the focus stays on that switch', f12.row === 'files' && f12.cls === 'switch-input' && f12.on === false, JSON.stringify(f12));
  await page.keyboard.press('Space');
  check('    pressed again it turns on, still focused', (await ev(page, () => { const a = document.activeElement, r = a.closest ? a.closest('.fl-row') : null; return !!a.checked && !!r && r.dataset.tab === 'files'; })));
  await page.focus('#featureList .fl-row[data-tab="debloater"] .fl-move >> nth=0');
  await page.keyboard.press('Enter');
  const f12b = await ev(page, () => { const a = document.activeElement, r = a.closest ? a.closest('.fl-row') : null; return { row: r ? r.dataset.tab : '', label: a.getAttribute ? a.getAttribute('aria-label') : '', first: tabConfig.order[0] }; });
  check('    Enter on an arrow: the row moves to the top and the focus goes to the other arrow (the one pressed has nowhere to go)', f12b.first === 'debloater' && f12b.row === 'debloater' && /down/.test(f12b.label), JSON.stringify(f12b));
  await page.keyboard.press('Enter');
  check('    and that one keeps working', (await ev(page, () => tabConfig.order.slice(0, 2).join())) === 'saved-lists,debloater');
  await ev(page, () => featureReset());
  await ev(page, () => { const c = document.getElementById('featureCard'); window.scrollTo(0, c.getBoundingClientRect().top + scrollY - 20); });
  await sleep(150);
  const top0 = await ev(page, () => document.querySelector('#featureList .fl-row[data-tab="logcat"]').getBoundingClientRect().top);
  await page.locator('#featureList .fl-row[data-tab="logcat"] .fl-move').first().click();
  await sleep(120);
  const top1 = await ev(page, () => document.querySelector('#featureList .fl-row[data-tab="logcat"]').getBoundingClientRect().top);
  check('    after a tap on an arrow the row is where it was on the screen (' + Math.round(top0) + ' px, now ' + Math.round(top1) + ' px), so the next tap moves the same row', Math.abs(top1 - top0) <= 2);
  await page.close();

  // 13) the bars that stick below the header sit under it whatever its height, and scrolling to something leaves room for them
  const many13 = Array.from({ length: 80 }, (_, i) => ({ pkg: 'com.vendor.app' + i, name: 'Application ' + i }));
  for (const w of [320, 360, 412]) {
    const p = await open({}, { width: w, height: 760 }, many13);
    await ev(p, () => window.scrollTo(0, 500));
    await sleep(150);
    const g = await ev(p, () => {
      const h = document.querySelector('.app-header'), t = document.getElementById('tabBar'), hb = h.getBoundingClientRect(), tb = t.getBoundingClientRect();
      const btn = t.querySelector('.tab-btn').getBoundingClientRect();
      const hit = document.elementFromPoint(btn.left + btn.width / 2, btn.top + 6);
      const uad = document.querySelector('.uad-actions');
      return { hdr: Math.round(hb.height), tabs: Math.round(tb.height), tabTop: Math.round(tb.top), hdrBottom: Math.round(hb.bottom), firstLineUncovered: !!hit && !!hit.closest('.tab-btn'), uadTop: uad ? getComputedStyle(uad).top : null, pad: getComputedStyle(document.documentElement).scrollPaddingTop };
    });
    check('13. [' + w + ' px, scrolled] the tab bar sticks right under the header (header ' + g.hdr + ' px), and the top of its first line is not covered', g.tabTop === g.hdrBottom && g.firstLineUncovered, JSON.stringify(g));
    check('    the Debloater action bar sticks right under the tab bar, and scroll-padding leaves room for both', g.uadTop === (g.hdr + g.tabs + 4) + 'px' && g.pad === (g.hdr + g.tabs + 8) + 'px', JSON.stringify(g));
    await p.close();
  }

  // 14) the same with a web view that has no ResizeObserver (an old one): the heights are still measured at the start and when the window changes
  {
    const p = await b.newPage({ viewport: { width: 360, height: 760 } });
    p.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await p.addInitScript(() => { delete window.ResizeObserver; });
    await p.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' } });
    await p.goto(PAGE);
    await p.waitForFunction(() => document.querySelectorAll('.tab-btn').length > 0);
    await sleep(1200);                                              // it looks again every second
    const v = await p.evaluate(() => ({ hdr: getComputedStyle(document.documentElement).getPropertyValue('--hdr-h'), real: document.querySelector('.app-header').offsetHeight + 'px', tabs: getComputedStyle(document.documentElement).getPropertyValue('--tabs-h'), realTabs: document.getElementById('tabBar').offsetHeight + 'px', ro: typeof ResizeObserver }));
    check('14. without ResizeObserver the heights of the header and the tab bar are still followed (' + v.hdr + ' and ' + v.tabs + ' a second after the start)', v.ro === 'undefined' && v.hdr === v.real && v.tabs === v.realTabs, JSON.stringify(v));
    await p.setViewportSize({ width: 412, height: 760 });
    await sleep(150);
    const v2 = await p.evaluate(() => ({ hdr: getComputedStyle(document.documentElement).getPropertyValue('--hdr-h'), real: document.querySelector('.app-header').offsetHeight + 'px' }));
    check('    and again when the window changes (' + v2.hdr + ')', v2.hdr === v2.real && v2.hdr !== v.hdr, JSON.stringify([v, v2]));
    await p.close();
  }

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
