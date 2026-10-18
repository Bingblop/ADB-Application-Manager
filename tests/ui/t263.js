// Bottom navigation, long-press shortcuts: a hold of 450 ms opens a menu above the slot (beside it on the rail); a move of more than 10 px cancels; no click follows; tap or slide-and-release
// runs an entry; an outside tap, Back, Escape, a scroll close it; entries whose target is missing are not listed; the Update All (N) entry asks the install question and installs nothing;
// the personal choice of entries; the switch; the one-time hint; the keyboard and the accessibility gesture; and a scan that every run() only navigates
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }          // (the numbers behind a passing check stay out of the output: they depend on the fonts)
const updates = [1, 2, 3].map(i => ({ pkg: 'org.example.app' + i, name: 'App ' + i, installedVersion: '1.0', availableVersion: '1.1', source: 'github', downloadUrl: 'https://github.com/x/y/a' + i + '.apk' }));
async function open(b, opts, vp) {
  const page = await b.newPage({ viewport: vp || { width: 360, height: 740 } });
  page.bottomNav = true; page.errors = [];
  page.on('pageerror', e => page.errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') page.errors.push('console: ' + m.text()); });
  await page.addInitScript(mock.initScript, opts || {});
  await page.goto(PAGE); await page.waitForTimeout(700);
  return page;
}
(async () => {
  const b = await chromium.launch();
  const errors = [];
  let page = await open(b, { kv: { bnav_tip: 'true' } });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const slot = async tab => { const r = await page.locator(tab === 'more' ? '#bnav .bn-item[data-more]' : '#bnav .bn-item[data-tab="' + tab + '"]').boundingBox(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, r }; };
  const menu = () => ev(() => { const m = document.getElementById('bnavMenu'); return m ? { items: [...m.querySelectorAll('.bn-sc')].map(i => i.innerText.trim()), roles: [...m.querySelectorAll('.bn-sc')].map(i => i.getAttribute('role')), menuRole: m.getAttribute('role'), label: m.getAttribute('aria-label'), r: (() => { const q = m.getBoundingClientRect(); return { l: q.left, t: q.top, r: q.right, b: q.bottom }; })(), fav: m.querySelector('.bn-sc').classList.contains('fav'), up: m.classList.contains('up') } : null; });
  const hold = async (tab, ms) => { const s = await slot(tab); await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.waitForTimeout(ms || 650); return s; };

  // ---- 1. open, what it lists, where ----
  let s = await hold('installer');
  let m = await menu();
  check('1. a hold on the Installer slot opens a menu above it with the entries that apply (no Update All yet: no updates found)', !!m && m.items.join('|') === 'Updater|Check for updates|Find APKs on storage', JSON.stringify(m && m.items));
  check('   it is a menu of real buttons (role menuitem), labelled with the tab, the first entry highlighted and nearest the thumb', m.menuRole === 'menu' && m.roles.every(r => r === 'menuitem') && m.label === 'APK Installer/Updater' && m.fav && m.up);
  check('   it stands above the slot, inside the screen, not clipped', m.r.b <= s.r.y + 1 && m.r.l >= 0 && m.r.r <= 360 && m.r.t >= 0, JSON.stringify(m.r));
  check('   the hold gave a haptic tick', await ev(() => window.__calls.includes('vibrate')));
  await page.screenshot({ path: 'menu_installer.png' });
  await page.mouse.up(); await page.waitForTimeout(100);
  check('2. no click follows the long press (the tab did not change), the menu stays', await ev(() => currentViewName()) === 'apps' && !!(await menu()));
  await page.waitForTimeout(600);
  // outside tap closes without doing anything else
  await page.mouse.click(180, 300); await page.waitForTimeout(200);
  check('3. a tap outside closes it and does nothing else', !(await menu()) && await ev(() => currentViewName()) === 'apps');
  // Back and Escape
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await ev(() => handleAndroidBack()); await page.waitForTimeout(100);
  check('4. Android Back closes it first', !(await menu()));
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.keyboard.press('Escape'); await page.waitForTimeout(100);
  check('   Escape closes it', !(await menu()));
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await ev(() => window.dispatchEvent(new Event('scroll'))); await ev(() => { document.dispatchEvent(new Event('scroll')); });
  check('   a scroll closes it', !(await menu()));

  // ---- 2. a short press, a move: nothing opens ----
  s = await slot('console');
  await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.waitForTimeout(200); await page.mouse.up(); await page.waitForTimeout(500);
  check('5. a short press is an ordinary tap: the tab opens, no menu', !(await menu()) && await ev(() => currentViewName()) === 'console');
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  s = await slot('installer');
  await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.mouse.move(s.x + 15, s.y); await page.waitForTimeout(650); await page.mouse.up(); await page.waitForTimeout(100);
  check('6. a finger that moves more than 10 px cancels the hold', !(await menu()));
  await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.mouse.move(s.x + 6, s.y + 4); await page.waitForTimeout(650);
  check('   a small wobble does not', !!(await menu()));
  await page.mouse.up(); await page.waitForTimeout(600); await page.keyboard.press('Escape');

  // ---- 3. run an entry: by a tap, by sliding onto it and letting go ----
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="updater"]'); await page.waitForTimeout(500);
  check('7. a tap on "Updater" opens the Installer/Updater tab on the updater side and closes the menu', await ev(() => currentViewName() === 'installer' && instPane === 'updater') && !(await menu()));
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  s = await hold('installer');
  const it = await page.locator('#bnavMenu .bn-sc[data-id="apks"]').boundingBox();
  await page.mouse.move(it.x + it.width / 2, it.y + it.height / 2, { steps: 6 }); await page.waitForTimeout(100);
  const hot = await ev(() => document.querySelector('#bnavMenu .bn-sc.hot') && document.querySelector('#bnavMenu .bn-sc.hot').dataset.id);
  await page.mouse.up(); await page.waitForTimeout(600);
  check('8. press, slide onto "Find APKs on storage", let go: it runs (the install side, the button lit up) and the menu closes', hot === 'apks' && await ev(() => currentViewName() === 'installer' && instPane === 'install') && !(await menu()) && await ev(() => document.getElementById('apkScanBtn').classList.contains('bn-flash')), hot);
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  // check for updates: a harmless read-only check
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="check"]'); await page.waitForTimeout(600);
  check('9. "Check for updates" opens the updater and starts the check (nothing else)', await ev(() => window.__calls.includes('check') && instPane === 'updater') && !(await ev(() => window.__calls.some(c => /^install|^approve/.test(c)))));
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  // Apps: Search apps focuses the search box; Console: a segment; Terminal: full screen and its sheets; Hidden Settings: search
  s = await hold('apps'); await page.mouse.up(); await page.waitForTimeout(600);
  const am = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="search"]'); await page.waitForTimeout(400);
  // v7.15: the Apps tab's menu also lists Running, Frozen and Uninstalled (the list with only that filter on)
  check('10. Apps: "Search apps" focuses the search box', am.items.join() === 'Search apps,Running,Frozen,Uninstalled' && await ev(() => document.activeElement.id) === 'searchInput', am.items.join());
  await ev(() => { document.activeElement.blur(); }); await page.waitForTimeout(600);
  // v7.14.0: Console holds both shells ("ADB Shell | Terminal"); its menu lists the two segments, then the Terminal's full screen and Coding Agents
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  const cm = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="term"]'); await page.waitForTimeout(500);
  const cl = await ev(() => ({ view: currentViewName(), seg: cnSeg, termShown: !document.getElementById('cnSegTerm').hidden, shellShown: !document.getElementById('cnSegShell').hidden, kv: window.__kv.console_seg }));
  check('11. Console: the menu lists its two segments and two Terminal entries, "Terminal" opens the tab on the Terminal (and keeps that choice)', cm.items.join() === 'ADB Shell,Terminal,Full-screen terminal,Coding Agents' && cl.view === 'console' && cl.seg === 'term' && cl.termShown && !cl.shellShown && cl.kv === '"term"', cm.items.join() + ' ' + JSON.stringify(cl));
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="shell"]'); await page.waitForTimeout(500);
  check('    "ADB Shell" switches the open tab to the shell segment', await ev(() => currentViewName() === 'console' && cnSeg === 'shell' && !document.getElementById('cnSegShell').hidden && document.getElementById('cnSegTerm').hidden));
  // v7.15: "History" (the last entry: chosen here, Settings > Navigation > Shortcuts) opens the shell's command history from anywhere
  await ev(() => { cnShowSeg('term'); tabConfig.shortcuts.console = ['history']; switchView('apps'); }); await page.waitForTimeout(700);
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="history"]'); await page.waitForTimeout(500);
  check('    v7.15: chosen, "History" opens the ADB Shell with its command history', await ev(() => TAB_SHORTCUTS.console.map(i => i.id).pop() === 'history' && currentViewName() === 'console' && cnSeg === 'shell' && document.getElementById('termModal').classList.contains('show')));
  await ev(() => { termCloseModal(); delete tabConfig.shortcuts.console; }); await page.waitForTimeout(300);
  // v7.15: Files has a menu too: the search and the two places opened most
  check('    v7.15: Files lists Search files, Storage and Download', await ev(() => TAB_SHORTCUTS.files.map(i => i.id).join() === 'search,storage,download'));
  await ev(() => switchView('apps')); await page.waitForTimeout(400);
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="full"]'); await page.waitForTimeout(500);
  check('11b. Console: "Full-screen terminal" opens the Terminal segment in full screen (and the bar goes away)', await ev(() => currentViewName() === 'console' && cnSeg === 'term' && txFullOn === true && document.getElementById('bnav').classList.contains('bnav-off')));
  await ev(() => txFullToggle(false)); await page.waitForTimeout(500);
  await ev(() => { cnShowSeg('shell'); switchView('apps'); }); await page.waitForTimeout(500);
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="agents"]'); await page.waitForTimeout(500);
  check('    "Coding Agents" opens the Terminal segment with the agent picker lit up and focused', await ev(() => currentViewName() === 'console' && cnSeg === 'term' && document.getElementById('txAgent').classList.contains('bn-flash') && document.activeElement.id === 'txAgent'), await ev(() => document.activeElement.id));
  // Termux setup and Terminal settings are entries five and six: chosen here (Settings > Navigation > Shortcuts)
  await ev(() => { document.activeElement.blur(); tabConfig.shortcuts.console = ['termux', 'settings']; switchView('apps'); }); await page.waitForTimeout(700);
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  const cm2 = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="termux"]'); await page.waitForTimeout(500);
  check('    chosen, "Termux setup" opens the Terminal segment and its Termux setup sheet', cm2.items.join() === 'Termux setup,Terminal settings' && await ev(() => currentViewName() === 'console' && cnSeg === 'term' && document.getElementById('txTermuxModal').classList.contains('show')), cm2.items.join());
  await ev(() => { txCloseTermux(); switchView('apps'); }); await page.waitForTimeout(700);
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="settings"]'); await page.waitForTimeout(500);
  check('    "Terminal settings" opens the Terminal segment and its settings sheet', await ev(() => currentViewName() === 'console' && cnSeg === 'term' && document.getElementById('txSettingsModal').classList.contains('show')));
  await ev(() => { txCloseSettings(); delete tabConfig.shortcuts.console; cnShowSeg('shell'); switchView('apps'); }); await page.waitForTimeout(700);
  // Live Logcat (its own tab again) is not on the bar by default: pinned here for its menu
  await ev(() => { tabConfig.pinned = ['apps', 'installer', 'console', 'logcat']; tabsApplyConfig(); }); await page.waitForTimeout(300);
  await hold('logcat'); await page.mouse.up(); await page.waitForTimeout(600);
  const lm = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="filter"]'); await page.waitForTimeout(500);
  check('11c. Live Logcat: three entries; "Filter the log" opens the tab with its filter focused', lm.items.join() === 'Filter the log,Log of one app,Recordings' && await ev(() => currentViewName() === 'logcat' && document.activeElement.id === 'logcatFilter'), lm.items.join());
  await ev(() => { document.activeElement.blur(); switchView('apps'); }); await page.waitForTimeout(700);
  await ev(() => { tabConfig.pinned = ['apps', 'installer', 'extrasettings', 'console']; tabsApplyConfig(); switchView('apps'); }); await page.waitForTimeout(700);          // the default pins again
  // The Extra Settings slot (third place by default) lists the first four sections of the registry that are on the page: Private DNS, Animations, Brightness, Telemetry (Power, Doze and Zram are there too, but a menu lists four at most unless the user picks them)
  s = await hold('extrasettings'); await page.mouse.up(); await page.waitForTimeout(600);
  const xm = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="dns"]'); await page.waitForTimeout(700);
  check('11b. Extra Settings: the menu lists the first four sections (Private DNS, Animations, Brightness, Telemetry), and "Private DNS" opens the tab and lights that section up', xm.items.join() === 'Private DNS,Animations,Brightness,Telemetry' && await ev(() => currentViewName() === 'extrasettings' && document.getElementById('extraDnsSection').classList.contains('bn-flash')), xm.items.join());
  await ev(() => switchView('apps')); await page.waitForTimeout(400);
  // Hidden Settings (not on the bar by default: pinned here): its search box only works with a privileged mode, so without one the slot has no menu; with one, "Search settings" opens the tab and focuses the box
  await ev(() => { tabConfig.pinned = ['apps', 'installer', 'settings', 'console']; tabsApplyConfig(); });
  s = await slot('settings'); await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.waitForTimeout(650); await page.mouse.up(); await page.waitForTimeout(100);
  const gated = !(await menu());
  await ev(() => { window.sdbUsable = () => true; window.__focused = ''; window.bnavFocus = id => { window.__focused = id; }; });
  await page.waitForTimeout(500);
  await hold('settings'); await page.mouse.up(); await page.waitForTimeout(600);
  const hm = await menu();
  await page.click('#bnavMenu .bn-sc[data-id="search"]'); await page.waitForTimeout(500);
  check('12. Hidden Settings: no menu while its list is not usable; then "Search settings" opens the tab and focuses its search box', gated && hm.items.join() === 'Search settings' && await ev(() => currentViewName() === 'settings' && window.__focused === 'sdbSearch'), String(gated) + ' ' + hm.items.join());
  await ev(() => { tabConfig.pinned = ['apps', 'installer', 'extrasettings', 'console']; tabsApplyConfig(); switchView('apps'); }); await page.waitForTimeout(600);

  // ---- 4. a slot with no entry: nothing happens (More); an entry whose target is missing is not listed ----
  s = await slot('more');
  await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.waitForTimeout(650); await page.mouse.up(); await page.waitForTimeout(100);
  check('13. a hold on More has no menu (it is a tap: the More sheet opens)', !(await menu()) && await ev(() => document.getElementById('bnavMoreModal').classList.contains('show')));
  await ev(() => bnavMoreClose());
  await page.waitForTimeout(500);
  await ev(() => { document.getElementById('apkScanBtn').id = 'apkScanBtnGone'; });
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  check('14. an entry whose target is not on the page ("Find APKs") is simply not listed', (await menu()).items.join('|') === 'Updater|Check for updates');
  await page.keyboard.press('Escape'); await ev(() => { document.getElementById('apkScanBtnGone').id = 'apkScanBtn'; });
  await ev(() => { document.getElementById('searchInput').id = 'searchInputGone'; document.getElementById('filterScroll').id = 'filterScrollGone'; });
  s = await slot('apps'); await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.waitForTimeout(650); await page.mouse.up(); await page.waitForTimeout(100);
  check('    a slot left with no entry does nothing on a hold', !(await menu()));
  await ev(() => { document.getElementById('searchInputGone').id = 'searchInput'; document.getElementById('filterScrollGone').id = 'filterScroll'; });
  // a registry entry added by another tab shows up, with a section filter
  await ev(() => { TAB_SHORTCUTS.console.push({ id: 'ghost', label: 'Ghost section', icon: 'tune', when: () => !!document.querySelector('#ghost'), run: () => switchView('apps') }); });
  await ev(() => { tabConfig.shortcuts.console = ['shell', 'ghost']; });
  await hold('console'); await page.mouse.up(); await page.waitForTimeout(600);
  check('15. the registry takes more entries: one whose section is missing is not listed', (await menu()).items.join() === 'ADB Shell');
  await ev(() => { delete tabConfig.shortcuts.console; });
  await page.keyboard.press('Escape');

  // ---- 5. Update All (N): listed first when more than one update waits; it asks the install question and installs nothing ----
  await page.close();
  page = await open(b, { kv: { bnav_tip: 'true' }, updates });
  await ev(() => switchView('updates')); await page.waitForTimeout(300);
  await ev(() => { window.onUpdatesChecked(JSON.stringify({ updates: JSON.parse(window.AndroidBridge.getUpdateState()).updates, untracked: [], external: [], checked: 10, errors: 0, checkedAt: Date.now() })); });
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  m = await menu();
  check('16. with 3 updates waiting, "Update All (3)" is listed first (the one under the thumb), then the others up to four', m.items.join('|') === 'Update All (3)|Updater|Check for updates|Find APKs on storage', m.items.join('|'));
  await page.screenshot({ path: 'menu_updateall.png' });
  await page.click('#bnavMenu .bn-sc[data-id="updateall"]'); await page.waitForTimeout(500);
  const ua = await ev(() => ({ view: currentViewName(), pane: instPane, calls: window.__calls.filter(c => /^approve|^install/.test(c)), asking: updAllAsking }));
  check('17. it opens the updater and asks the install question (the app\'s own approval) once; nothing is installed', ua.view === 'installer' && ua.pane === 'updater' && ua.calls.length === 1 && /^approveUpdateAll:/.test(ua.calls[0]) && ua.asking, JSON.stringify(ua));
  await page.waitForTimeout(500);
  check('    still nothing installs while the question is open', !(await ev(() => window.__calls.some(c => /^install/.test(c)))));
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: false, error: 'cancelled' }))); await page.waitForTimeout(300);
  check('    and a "no" starts nothing', !(await ev(() => window.__calls.some(c => /^install/.test(c)))));
  await ev(() => switchView('apps'));
  await page.close();
  // read-only: the entry follows the button (the privilege message), nothing is started
  page = await open(b, { kv: { bnav_tip: 'true' }, updates, privileged: false });
  await ev(() => switchView('updates')); await page.waitForTimeout(300);
  await ev(() => { window.onUpdatesChecked(JSON.stringify({ updates: JSON.parse(window.AndroidBridge.getUpdateState()).updates, untracked: [], external: [], checked: 10, errors: 0, checkedAt: Date.now() })); });
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  await page.click('#bnavMenu .bn-sc[data-id="updateall"]'); await page.waitForTimeout(600);
  const ro = await ev(() => ({ calls: window.__calls.filter(c => /^approve|^install/.test(c)), modal: [...document.querySelectorAll('.modal-overlay.show')].map(x => x.id) }));
  check('18. in Read-Only mode "Update All" shows the same privilege message as the button and starts nothing', ro.calls.length === 0 && ro.modal.includes('privilegeModal'), JSON.stringify(ro));
  await page.close();

  // ---- 6. personal choice: Settings > Navigation > Shortcuts ----
  page = await open(b, { kv: { bnav_tip: 'true' } });
  await ev(() => switchView('prefs')); await page.waitForTimeout(300);
  await page.evaluate(() => { document.querySelector('.nav-sc-details').open = true; });
  const rows = () => ev(() => [...document.querySelectorAll('#navScList .fl-row[data-sc^="installer:"]')].map(r => r.dataset.sc.split(':')[1] + (r.querySelector('.switch-input').checked ? '+' : '-')).join());
  check('19. Settings > Navigation > Shortcuts lists every entry of each pinned tab: the first four are on', await rows() === 'updateall+,updater+,check+,apks+', await rows());
  check('    only pinned tabs with entries are listed', await ev(() => [...document.querySelectorAll('#navScList .nav-sc-group')].map(g => g.dataset.tab).join()) === 'apps,installer,console,extrasettings');
  await page.click('#navScList .fl-row[data-sc="installer:check"] .fl-move:nth-of-type(1)'); await page.waitForTimeout(100);
  await page.click('#navScList .fl-row[data-sc="installer:apks"] .switch-track'); await page.waitForTimeout(100);
  check('20. moving "Check for updates" up and switching "Find APKs" off is saved in tab_config', await ev(() => JSON.stringify(JSON.parse(window.__kv.tab_config).shortcuts)) === '{"installer":["updateall","check","updater"]}', await ev(() => window.__kv.tab_config));
  await page.evaluate(() => { bnavMoreClose(); switchView('apps'); }); await page.waitForTimeout(300);
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  check('    the menu follows: the favourite first, the switched-off entry gone', (await menu()).items.join('|') === 'Check for updates|Updater');
  await page.keyboard.press('Escape');
  await ev(() => switchView('prefs')); await page.waitForTimeout(300);
  await page.evaluate(() => { document.querySelector('.nav-sc-details').open = true; });
  // the limit of four
  await ev(() => { TAB_SHORTCUTS.taskmgr.forEach(() => {}); });
  const lim = await ev(() => { tabConfig.shortcuts.installer = ['updater', 'check', 'apks']; const r = []; navScToggle('installer', 'updateall', true); r.push(tabConfig.shortcuts.installer.length); TAB_SHORTCUTS.installer.push({ id: 'x1', label: 'X1', icon: 'tune', run: () => {} }); navScToggle('installer', 'x1', true); r.push(tabConfig.shortcuts.installer.length); return r; });
  check('21. a menu lists at most four: the fifth is refused', lim.join() === '4,4' && /at most 4/.test(await page.locator('#toastMsg').innerText()), lim.join());
  await page.reload(); await page.waitForTimeout(600);
  check('22. unknown tabs and entries in the saved choice are dropped when it is read', await ev(() => { window.__kv.tab_config = JSON.stringify({ shortcuts: { installer: ['check', 'bogus', 'check'], nonsense: ['x'], apps: 'no' } }); tabLoadConfig(); return JSON.stringify(tabConfig.shortcuts); }) === '{"installer":["check"]}');
  await ev(() => { tabConfig.shortcuts = { installer: ['check'] }; navScReset(); });
  check('    Reset shortcuts returns to the default', await ev(() => JSON.stringify(tabConfig.shortcuts)) === '{}' && await rows() === 'updateall+,updater+,check+,apks+');
  // the switch
  await ev(() => switchView('prefs')); await page.waitForTimeout(300);
  await page.click('#bnavScToggle + .switch-track'); await page.waitForTimeout(150);
  await ev(() => switchView('apps')); await page.waitForTimeout(300);
  await hold('installer'); await page.mouse.up(); await page.waitForTimeout(600);
  check('23. with "Long-press shortcuts" switched off a hold opens nothing (and the choice is saved)', !(await menu()) && await ev(() => window.__kv.bnav_shortcuts) === 'false' && await ev(() => document.querySelectorAll('#bnav [aria-haspopup="menu"]').length) === 0);
  await page.close();

  // ---- 7. the one-time hint ----
  page = await open(b, {});
  await page.waitForTimeout(1800);
  const tip = await ev(() => { const t = document.getElementById('bnavTip'); return t ? { text: t.innerText, role: t.getAttribute('role'), bottom: t.getBoundingClientRect().bottom, barTop: document.getElementById('bnav').getBoundingClientRect().top, kv: window.__kv.bnav_tip } : null; });
  check('24. the first time the bar is shown, a hint says "Long-press a tab for shortcuts" above the bar', !!tip && tip.text === 'Long-press a tab for shortcuts' && tip.role === 'status' && tip.bottom <= tip.barTop && tip.kv === 'true', JSON.stringify(tip));
  await page.screenshot({ path: 'tip.png' });
  await page.click('#bnavTip'); await page.waitForTimeout(150);
  check('    a tap dismisses it', !(await ev(() => !!document.getElementById('bnavTip'))));
  await page.reload(); await page.waitForTimeout(2200);
  check('    after a reload (the choice is kept) it is never shown again', !(await ev(() => !!document.getElementById('bnavTip'))));
  await page.close();

  // ---- 8. the keyboard, the accessibility gesture, the focus ----
  page = await open(b, { kv: { bnav_tip: 'true' } });
  await page.focus('#bnav .bn-item[data-tab="installer"]');
  await page.keyboard.press('Shift+Enter'); await page.waitForTimeout(200);
  check('25. Shift+Enter on a slot opens its menu with the focus in it', !!(await menu()) && await ev(() => document.activeElement.classList.contains('bn-sc')) && await ev(() => document.activeElement.dataset.id) === 'updater');
  await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.waitForTimeout(100);
  check('    Tab stays inside the menu (it wraps)', await ev(() => document.activeElement.classList.contains('bn-sc') && document.activeElement.dataset.id === 'updater'));
  await page.keyboard.press('ArrowUp'); await page.waitForTimeout(100);
  check('    arrow keys move between entries', await ev(() => document.activeElement.dataset.id) === 'check');
  await page.keyboard.press('Escape'); await page.waitForTimeout(150);
  check('    Escape closes it and the focus returns to the slot', !(await menu()) && await ev(() => document.activeElement.dataset.tab) === 'installer');
  await ev(() => { document.querySelector('#bnav .bn-item[data-tab="installer"]').dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 })); });
  await page.waitForTimeout(150);
  check('26. the context-menu event (the accessibility long press, a right button) opens it too', !!(await menu()));
  await page.keyboard.press('Escape');
  check('    slots with a menu say so (aria-haspopup="menu"), More opens a dialog', await ev(() => [...document.querySelectorAll('#bnav .bn-item')].map(i => i.getAttribute('aria-haspopup') || '-').join()) === 'menu,menu,menu,menu,dialog');
  await page.close();

  // ---- 9. the rail: the menu stands beside the item ----
  page = await open(b, { kv: { bnav_tip: 'true' } }, { width: 800, height: 600 });
  s = await hold('installer');
  m = await menu();
  check('27. on the rail the menu opens beside the item, inside the screen', !!m && !m.up && m.r.l >= 76 && m.r.b <= 600 && m.r.t >= 0, JSON.stringify(m && m.r));
  await page.screenshot({ path: 'menu_rail.png' });
  await page.mouse.up(); await page.keyboard.press('Escape');
  await page.close();

  // ---- 10. a narrow screen: the menu is not clipped at the edge for the last slots ----
  page = await open(b, { kv: { bnav_tip: 'true' } }, { width: 320, height: 640 });
  for (const t of ['extrasettings', 'console']) {
    await hold(t); m = await menu();
    check('28. at 320 px the menu of "' + t + '" is inside the screen', !!m && m.r.l >= 0 && m.r.r <= 320, JSON.stringify(m && m.r));
    await page.mouse.up(); await page.keyboard.press('Escape');
  }
  await page.close();

  // ---- 11. SAFETY: every run() only navigates ----
  page = await open(b, { kv: { bnav_tip: 'true' } });
  const src = await ev(() => { const o = {}; Object.keys(TAB_SHORTCUTS).forEach(k => TAB_SHORTCUTS[k].forEach(i => { o[k + ':' + i.id] = String(i.run); })); return o; });
  // what a shortcut may call: moving between and opening screens, sections, segments and sheets, focusing a field, scrolling and lighting up a section, a harmless read-only check, and the Update All button's own function
  const NAV = new Set(['switchView', 'instPaneShow', 'checkUpdatesUI', 'bnavGo', 'bnavReveal', 'bnavFocus', 'txFullToggle', 'tmSetSub', 'cnShowSeg', 'txOpenTermuxSetup', 'txOpenSettings', 'lcAppOpen', 'lcRecListOpen', 'appsShowOnly', 'setTimeout', 'fmGo', 'termOpenHistory']);          // lcAppOpen / lcRecListOpen: the app picker and the recordings sheet of Live Logcat (they only open a sheet); appsShowOnly: the Apps list with one filter on (v7.15); fmGo: lists a folder in Files, termOpenHistory: the shell's history sheet (v7.15)
  const EXCEPTION = new Set(['updateAllUI']);            // the one state-changing path: it asks the install question first (checked above)
  const bad2 = [];
  Object.keys(src).forEach(k => {
    const calls = [...src[k].matchAll(/([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(/g)].map(x => x[1]).filter(n => n !== 'run');
    calls.forEach(n => { if (!NAV.has(n) && !EXCEPTION.has(n)) bad2.push(k + ' calls ' + n); });
    if (/AndroidBridge|executeShell|executeAppAction|\.click\(|eval|Function\(/.test(src[k])) bad2.push(k + ' touches the bridge');
    if (/updateAllUI/.test(src[k]) && k !== 'installer:updateall') bad2.push(k + ' calls updateAllUI');
  });
  check('29. SAFETY: every shortcut\'s run() calls only navigation functions (' + Object.keys(src).length + ' entries); updateAllUI only from "Update All (N)"', bad2.length === 0, JSON.stringify(bad2));
  check('    and the scan sees every entry of every tab (no empty source)', Object.keys(src).length >= 20 && Object.values(src).every(x => x.length > 10));
  errors.push(...page.errors);
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad) process.exit(1);
})();
