// The small things asked for with "all your suggestions": the app menu asks before Uninstall, Clear Data and Remove updates (and not before the rest); the batch Freeze is called Freeze in its
// question as on its button; "Install unknown apps" is a row of the permissions sheet (granted through a working mode, or opened in Settings, and left out when the app build does not report it);
// Root is "DENIED" and not ready when su exists but was not granted; and a "?" on the first card of each tab opens the Help Guide at that tab's topic.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(() => {
    window.__acts = []; window.__perm = []; window.__status = { files: true, usage: true, overlay: true, install_unknown: false, sdk: 34 };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'Example', isSystem: false }, { pkg: 'com.y', name: 'Other', isSystem: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, rootAvailable: true }); },
      getAppDetails() { return JSON.stringify({ versionName: '1', permissions: [], appopsRaw: '' }); },
      executeAppAction(a, p) { window.__acts.push(a + ':' + p); return 'Success'; },
      getPermissionStatus() { return JSON.stringify(window.__status); },
      requestInstallUnknownAccess() { window.__perm.push('install_unknown'); if (window.__grantNow) { window.__status.install_unknown = true; return 'granted'; } return 'settings'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const acts = () => ev(() => window.__acts.slice());

  // ---- the app menu asks first, for the three that cannot be taken back with one tap ----
  await ev(() => { isPrivilegedActive = true; openInspector('com.x'); }); await wait(300);
  for (const [action, word] of [['uninstall', /Uninstall Example\?/], ['clear_data', /Clear all data of Example\?/], ['uninstall_updates', /Remove the updates of Example\?/]]) {
    dialogs.length = 0; answer = false;
    await ev(a => singleAction(a), action); await wait(150);
    check(action + ': asks, in words that say what is lost', dialogs.length === 1 && word.test(dialogs[0]), JSON.stringify(dialogs));
    check(action + ': "Cancel" runs nothing', (await acts()).length === 0);
    answer = true;
    await ev(a => singleAction(a), action); await wait(250);
    check(action + ': "OK" runs it', (await acts()).includes(action + ':com.x'), JSON.stringify(await acts()));
    await ev(() => { window.__acts.length = 0; });
  }
  dialogs.length = 0;
  for (const action of ['launch', 'force_stop', 'freeze', 'unfreeze', 'suspend']) { await ev(a => singleAction(a), action); await wait(120); }
  check('the others do not ask', dialogs.length === 0 && (await acts()).length === 5, JSON.stringify([dialogs, await acts()]));
  await ev(() => { window.__acts.length = 0; isPrivilegedActive = false; });
  dialogs.length = 0;
  await ev(() => singleAction('uninstall')); await wait(150);
  check('without a working mode the lock answers first, with no question', dialogs.length === 0 && await ev(() => document.getElementById('privilegeModal').classList.contains('show')));
  await ev(() => { closePrivilegeModal(); closeInspector(); isPrivilegedActive = true; });

  // ---- batch Freeze is called Freeze ----
  await ev(() => { requestBatchConfirmation('freeze', ['com.x']); });
  check('the batch question says Freeze, as the button does', await ev(() => [document.getElementById('batchConfirmTitle').innerText, document.getElementById('batchConfirmExecuteBtn').innerText].join(' | ')) === 'Confirm Freeze Selected Apps | Confirm Freeze');
  check('the batch button of the grid is still Freeze', await ev(() => Array.from(document.querySelectorAll('.batch-grid-btn')).some(x => x.innerText.trim() === 'Freeze')));
  await ev(() => closeBatchConfirmModal());

  // ---- Install unknown apps ----
  await ev(() => { openPermSheet('manual'); }); await wait(200);
  const rows = () => ev(() => Array.from(document.querySelectorAll('#permRows .pm-row')).map(r => r.querySelector('.pm-name').innerText + ':' + (r.querySelector('.pm-ok') ? 'allowed' : 'allow')));
  const r1 = await rows();
  check('the sheet lists Install unknown apps after Display over other apps', r1.slice(0, 4).join('|') === 'All files access:allowed|Usage access:allowed|Display over other apps:allowed|Install unknown apps:allow', r1.join('|'));
  check('it says what it is for', await ev(() => /APK, APKS and XAPK/.test(document.querySelectorAll('#permRows .pm-why')[3].innerText)));
  await page.click('#permRows .pm-row:nth-child(4) button'); await wait(200);
  check('Allow asks the app, which opens Settings (no working mode answer)', (await ev(() => window.__perm)).join() === 'install_unknown' && (await rows())[3] === 'Install unknown apps:allow');
  await ev(() => { window.__status.install_unknown = true; permOnChange(); }); await wait(100);
  check('back from Settings with it on: the tick', (await rows())[3] === 'Install unknown apps:allowed');
  await ev(() => { window.__status.install_unknown = false; window.__grantNow = true; permRefresh(); permRender(); });
  await page.click('#permRows .pm-row:nth-child(4) button'); await wait(250);
  check('granted through a working mode: the tick at once', (await rows())[3] === 'Install unknown apps:allowed');
  await ev(() => closePermSheet(true));
  await ev(() => { delete window.__status.install_unknown; openPermSheet('manual'); }); await wait(150);
  const old = await rows();
  check('an app build that does not report it shows no such row', old.slice(0, 3).join('|') === 'All files access:allowed|Usage access:allowed|Display over other apps:allowed' && !old.some(r => /Install unknown/.test(r)), old.join('|'));
  await page.screenshot({ path: 'perm_old.png' });
  await ev(() => { window.__status.install_unknown = false; window.__grantNow = false; openPermSheet('manual'); }); await wait(150);
  await page.screenshot({ path: 'perm_sheet.png' });
  await ev(() => closePermSheet(true));

  // ---- Root: su exists but the root manager did not grant it ----
  const tag = () => ev(() => document.getElementById('statusTagRoot').innerText.trim());
  const modes = (o) => ev(o => { window.AndroidBridge.getWorkingMode = () => JSON.stringify(Object.assign({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false, rootAvailable: true }, o)); checkAllWorkingModes(false); }, o);
  const fn = await ev(() => typeof checkAllWorkingModes);
  if (fn === 'function') {
    await modes({ rootAvailable: true }); await wait(300);
    check('Root, not chosen: AVAILABLE', (await tag()) === 'AVAILABLE', await tag());
    await modes({ configuredMode: 'root', activeMode: 'root', modeAvailable: true, isPrivileged: true, rootGranted: true }); await wait(300);
    check('Root chosen and granted: ACTIVE', (await tag()) === 'ACTIVE', await tag());
    await modes({ configuredMode: 'root', activeMode: 'root', modeAvailable: false, isPrivileged: false, rootGranted: false }); await wait(300);
    check('Root chosen but not granted: DENIED, and the app is not privileged', (await tag()) === 'DENIED' && await ev(() => isPrivilegedActive === false), await tag());
    await modes({ rootAvailable: false }); await wait(300);
    check('No su: NOT FOUND', (await tag()) === 'NOT FOUND', await tag());
  } else check('the page has checkAllWorkingModes', false);

  // ---- the "?" ----
  const helps = await ev(() => Object.keys(HELP_LINKS).map(v => { const q = document.querySelector('#view-' + v + ' .help-q'); return [v, q ? q.dataset.help : null]; }));
  const missing = helps.filter(h => !h[1]).map(h => h[0]).sort().join();
  check('every tab with a card has one; Apps and the Terminal have no card to hold it', missing === 'apps,terminal', missing);
  check('each points at its own topic', helps.filter(h => h[1]).every(h => h[1] === (h[0] === 'prefs' ? 'prefs' : 'tab-' + h[0])), JSON.stringify(helps));
  check('they sit inside the first card, not inside its title', await ev(() => Array.from(document.querySelectorAll('.help-q')).every(q => q.parentNode.classList.contains('color-card') && q.parentNode.classList.contains('has-help') && !q.closest('.color-card-title'))));
  check('and nothing is doubled', await ev(() => Array.from(document.querySelectorAll('.color-card')).every(c => c.querySelectorAll(':scope > .help-q').length <= 1)));
  await ev(() => switchView('debloater')); await wait(300);
  await page.screenshot({ path: 'help_q.png' });
  await page.click('#view-debloater .help-q'); await wait(900);
  check('tapping it opens the Help Guide at the Debloater topic', await ev(() => document.getElementById('helpGuideModal').classList.contains('show') && hg.cur === 'tab-debloater'), await ev(() => String(hg.cur)));
  await page.screenshot({ path: 'help_open.png' });
  await ev(() => closeHelpGuide());
  await ev(() => switchView('devices')); await wait(300);
  await page.click('#view-devices .help-q'); await wait(700);
  check('the Connected Devices one opens that topic', await ev(() => hg.cur === 'tab-devices'), await ev(() => String(hg.cur)));
  await ev(() => closeHelpGuide());
  await ev(() => switchView('prefs')); await wait(300);
  await page.click('#view-prefs .help-q'); await wait(700);
  check('the Settings one opens the settings topic', await ev(() => hg.cur === 'prefs'), await ev(() => String(hg.cur)));
  await ev(() => closeHelpGuide());

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
