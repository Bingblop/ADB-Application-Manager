// The Stop apps notification (v7.14.1, the owner's request): its Cancel button and progress are Java (tests/java/src/QuickStopRunTest.java); what the page does is covered here.
//  1) A tap on the notification opens Saved Applications with the sheet of the quick list ("Edit Saved List"), and touches nothing of the run: no app is stopped, the quick list is not
//     changed, nothing is saved. A tab that was turned off comes back (with a toast that says so); a list that is gone falls back to the quick list, and with none says so.
//  2) The permissions sheet has a "Live updates" row on Android 16 and later (the app reports it) and none before. It has no command: a working mode cannot flip the switch on the phone's
//     Live updates screen, so "Allow all" ends there. Without a working mode (read-only) its Allow asks for Notifications first, with Android's own dialog, and once that is allowed the
//     phone's Live updates screen opens by itself; a refusal opens nothing more. Closing the sheet after only Live updates changed does not start the app again, and the one-time offer
//     of a working mode's "grant everything" is not made for a row no command can grant.
//  3) In every language the Quick actions card and the quick-list toast name the tiles as the phone shows them: "Working mode" and "Kill Apps" (T-006).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const open = async () => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.__errors = []; page.on('pageerror', e => page.__errors.push(e.message));
    page.__dialogs = []; page.on('dialog', d => { page.__dialogs.push(d.message()); d.dismiss(); });
    await page.addInitScript(() => {
      window.__calls = []; window.__perm = []; window.__saved = []; window.__settings = {}; window.__reloads = 0; window.__quick = 'l2';
      window.__lists = [
        { id: 'l1', name: 'Gaming', description: 'Games to stop before a call', packages: ['com.game.a', 'com.game.b'], updatedAt: 1 },
        { id: 'l2', name: 'Night', description: '', packages: ['com.chat.c', 'com.chat.d', 'com.chat.e'], updatedAt: 2 },
      ];
      window.__status = { files: true, restricted_settings: true, usage: true, overlay: true, install_unknown: true, bluetooth: true, notifications: false, live_updates: false };
      const real = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadStore() { return ''; }, saveStore() { return true; },
        loadCustomLists() { return JSON.stringify(window.__lists); }, saveCustomLists() { return true; },
        getQuickList() { return window.__quick; }, setQuickList(id) { window.__quick = id; },
        loadSetting(k) { return k === 'perm_intro_v62' ? '1' : (window.__settings[k] || ''); }, saveSetting(k, v) { window.__settings[k] = v; window.__saved.push(k); },
        executeAppAction(a, p) { return '\u00011'; },
        getPermissionStatus() { return JSON.stringify(window.__status); }, getGrantCommands() { return ''; }, reloadApp() { window.__reloads++; },
        // the app's own answers: a runtime permission is granted by command in a working mode, otherwise Android's dialog comes up (the answer arrives as onPermissionsChanged)
        requestNotificationAccess() { window.__perm.push('notifications'); if (window.__status.notifications) return 'granted'; window.__perm.push('dialog:notifications'); return 'dialog'; },
        // Live updates: Notifications first (the same dialog), then the phone's own screen for it; no command sets it
        requestLiveUpdatesAccess() {
          window.__perm.push('live_updates');
          if (window.__status.live_updates) return 'granted';
          if (!window.__status.notifications) { window.__perm.push('dialog:notifications'); return 'dialog'; }
          window.__perm.push('screen:live_updates'); return 'settings';
        },
        grantAllPermissions() { window.__perm.push('grantAll'); window.__status.notifications = true; window.__status.bluetooth = true; return JSON.stringify(window.__status); },
      };
      window.AndroidBridge = new Proxy(real, { get(t, k) { const v = t[k]; return typeof v === 'function' ? (...a) => { window.__calls.push(String(k)); return v.apply(t, a); } : v; } });
    });
    await page.goto(PAGE); await page.waitForTimeout(800);
    return page;
  };
  const wait = (page, ms) => page.waitForTimeout(ms);
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);

  // ======================================================================== 1) the tap on the notification
  let page = await open();
  const view = () => page.evaluate(() => currentViewName());
  const sheet = () => page.evaluate(() => ({
    shown: document.getElementById('listEditorModal').classList.contains('show'), title: document.getElementById('listEditorTitle').innerText,
    id: document.getElementById('listEditorId').value, name: document.getElementById('listEditorName').value, packages: document.getElementById('listEditorPackages').value.split('\n'),
  }));
  const touched = () => page.evaluate(() => window.__calls.filter(c => /^(execute|force|kill|stop|setQuickList|saveCustomLists|clear|uninstall|freeze|disable)/i.test(c)));
  check('1. the page starts on the Application Manager with no sheet up', (await view()) === 'apps' && !(await sheet()).shown);
  await page.evaluate(() => { window.__calls.length = 0; window.onQuickStopOpen('l2'); }); await wait(page, 200);
  let s = await sheet();
  check('   a tap on the notification opens Saved Applications with the quick list\'s sheet: "Edit Saved List", Night, its three apps', (await view()) === 'saved-lists' && s.shown && s.title === 'Edit Saved List' && s.id === 'l2' && s.name === 'Night' && s.packages.join() === 'com.chat.c,com.chat.d,com.chat.e', JSON.stringify(s));
  check('   it does not touch the run: no app is stopped, the quick list is the same, nothing is saved', (await touched()).length === 0 && (await page.evaluate(() => window.__quick)) === 'l2', (await touched()).join());
  check('   the Saved Applications tab is the one that is marked as open', await page.evaluate(() => (document.querySelector('.tab-btn.active') || {}).dataset && document.querySelector('.tab-btn.active').dataset.tab === 'saved-lists'));
  await page.evaluate(() => closeListEditorModal()); await wait(page, 100);
  await page.evaluate(() => { switchView('apps'); window.onQuickStopOpen('l1'); }); await wait(page, 150);
  s = await sheet();
  check('   the id in the notification decides: another list is shown when that is the one the tile used', s.shown && s.id === 'l1' && s.name === 'Gaming', JSON.stringify(s));
  await page.evaluate(() => window.onQuickStopOpen('l2')); await wait(page, 150);
  s = await sheet();
  check('   with a sheet already up the new one replaces it (one sheet, not two)', s.shown && s.id === 'l2' && (await page.locator('#listEditorModal.show').count()) === 1, JSON.stringify(s));
  await page.evaluate(() => { closeListEditorModal(); window.onQuickStopOpen('gone'); }); await wait(page, 150);
  s = await sheet();
  check('   a list that is gone falls back to the quick list', s.shown && s.id === 'l2' && s.name === 'Night', JSON.stringify(s));
  await page.evaluate(() => { closeListEditorModal(); quickListId = ''; window.onQuickStopOpen('gone'); }); await wait(page, 150);
  s = await sheet();
  check('   with no list at all it stays on Saved Applications and says so', !s.shown && (await view()) === 'saved-lists' && (await toast(page)) === 'That saved list is not there any more.', (await toast(page)));
  await page.evaluate(() => { quickListId = 'l2'; switchView('apps'); tabConfig.off.push('saved-lists'); tabSaveConfig(); tabsApplyConfig(); });
  check('   (set up) Saved Applications is turned off', await page.evaluate(() => !tabIsOn('saved-lists')));
  await page.evaluate(() => window.onQuickStopOpen('l2')); await wait(page, 250);
  s = await sheet();
  check('   a tab that was turned off comes back, with a toast that says so, and the sheet opens', (await page.evaluate(() => tabIsOn('saved-lists'))) && (await view()) === 'saved-lists' && s.shown && s.id === 'l2'
    && (await toast(page)) === 'Saved Applications was turned off. It is on again to show this list.', (await toast(page)));
  check('   no page error', page.__errors.length === 0, page.__errors.join(' | '));
  await page.close();

  // ======================================================================== 2) the Live updates row
  page = await open();
  const rows = () => page.evaluate(() => Array.from(document.querySelectorAll('#permRows .pm-row')).map(r => r.querySelector('.pm-name').innerText + ':' + (r.querySelector('.pm-ok') ? 'allowed' : r.querySelector('.pm-lock') ? 'locked' : 'allow')));
  const perm = () => page.evaluate(() => window.__perm.join(' > '));
  const around = r => { const i = r.findIndex(x => x.startsWith('Notifications:')); return r.slice(i, i + 3).join('|'); };
  await page.evaluate(() => { isPrivilegedActive = false; openPermSheet('manual'); }); await wait(page, 250);
  check('2. the sheet lists Live updates right after Notifications (Android 16 reports it)', around(await rows()) === 'Notifications:allow|Live updates:allow|Read/Write External Storage:locked', around(await rows()));
  const row = await page.evaluate(() => { const r = document.querySelector('#permRows .pm-row[data-perm="live_updates"]'); return { perm: r.querySelector('.pm-perm').innerText, why: r.querySelector('.pm-why').innerText, btn: r.querySelector('button').innerText }; });
  check('   it shows the permission and says what it is for', row.perm === 'POST_PROMOTED_NOTIFICATIONS' && /Kill Apps notification/.test(row.why) && !/Stop apps/.test(row.why) && /Notifications too/.test(row.why) && row.btn === 'Allow', JSON.stringify(row));

  // read-only: Notifications' own Allow asks the person with Android's dialog
  await page.evaluate(() => { window.__calls.length = 0; window.__perm.length = 0; });
  await page.click('#permRows .pm-row[data-perm="notifications"] button'); await wait(page, 200);
  check('   read-only (no working mode): Allow on Notifications asks the person, with Android\'s dialog', (await perm()) === 'notifications > dialog:notifications' && (await page.evaluate(() => window.__calls.filter(c => c === 'executeAppAction' || c === 'grantAllPermissions').length)) === 0, await perm());
  // read-only: Live updates' Allow asks for Notifications first
  await page.evaluate(() => { window.__perm.length = 0; });
  await page.click('#permRows .pm-row[data-perm="live_updates"] button'); await wait(page, 700);
  check('   read-only: Allow on Live updates asks for Notifications first (the same dialog) and opens nothing else yet', (await perm()) === 'live_updates > dialog:notifications', await perm());
  // the dialog is answered with Allow: the phone's Live updates screen opens by itself
  await page.evaluate(() => { window.__status.notifications = true; window.onPermissionsChanged(); }); await wait(page, 800);
  check('   Notifications allowed in the dialog: the phone\'s Live updates screen for this app opens by itself', (await perm()) === 'live_updates > dialog:notifications > live_updates > screen:live_updates', await perm());
  check('   the sheet shows Notifications ticked and Live updates still to do until the switch is on', around(await rows()) === 'Notifications:allowed|Live updates:allow|Read/Write External Storage:locked', around(await rows()));
  await page.evaluate(() => { window.__status.live_updates = true; permOnChange(); }); await wait(page, 250);
  check('   back from the screen with the switch on: the tick', around(await rows()) === 'Notifications:allowed|Live updates:allowed|Read/Write External Storage:locked', around(await rows()));
  await page.evaluate(() => { window.__reloads = 0; window.__status.live_updates = false; openPermSheet('manual'); window.__status.live_updates = true; closePermSheet(true); }); await wait(page, 700);
  check('   closing the sheet with only Live updates changed does not start the app again', (await page.evaluate(() => window.__reloads)) === 0);
  await page.evaluate(() => { window.__status.notifications = false; window.__status.live_updates = false; openPermSheet('manual'); window.__status.notifications = true; closePermSheet(true); }); await wait(page, 700);
  check('   (closing it after Notifications changed still does, as before)', (await page.evaluate(() => window.__reloads)) === 1);

  // a refusal opens nothing more
  await page.evaluate(() => { window.__reloads = 0; window.__perm.length = 0; window.__status.notifications = false; window.__status.live_updates = false; openPermSheet('manual'); }); await wait(page, 200);
  await page.click('#permRows .pm-row[data-perm="live_updates"] button'); await wait(page, 300);
  await page.evaluate(() => window.onPermissionsChanged()); await wait(page, 800);
  check('   Notifications refused in the dialog: nothing more is opened', (await perm()) === 'live_updates > dialog:notifications', await perm());

  // Allow all, read-only: Notifications, then Live updates, one after the other
  await page.evaluate(() => { window.__perm.length = 0; window.__status.notifications = false; window.__status.live_updates = false; permRefresh(); permRender(); });
  check('   "Allow all" is offered with both missing', await page.evaluate(() => document.getElementById('permAllBtn').style.display !== 'none'));
  await page.click('#permAllBtn'); await wait(page, 300);
  check('   "Allow all", read-only: Notifications is asked first', (await perm()) === 'notifications > dialog:notifications', await perm());
  await page.evaluate(() => { window.__status.notifications = true; window.onPermissionsChanged(); }); await wait(page, 900);
  check('   once it is allowed the walk goes on to Live updates, whose screen opens', (await perm()) === 'notifications > dialog:notifications > live_updates > screen:live_updates', await perm());
  await page.evaluate(() => { window.__status.live_updates = true; permOnChange(); }); await wait(page, 700);
  check('   both ticked: the sheet offers Done', await page.evaluate(() => document.getElementById('permCloseBtn').innerText) === 'Done' && (await perm()).split(' > ').length === 4);
  await page.evaluate(() => closePermSheet(true));

  // a working mode: Allow all grants Notifications by command and ends on the Live updates screen (no command can set it)
  await page.evaluate(() => { isPrivilegedActive = true; window.__perm.length = 0; window.__status.notifications = false; window.__status.live_updates = false; openPermSheet('manual'); }); await wait(page, 200);
  check('   with a working mode the rows with a command have no lock and Live updates still has an Allow', around(await rows()).startsWith('Notifications:allow|Live updates:allow|'), around(await rows()));
  await page.click('#permAllBtn'); await wait(page, 900);
  check('   "Allow all" with a working mode: Notifications by command, then the Live updates screen (the sheet stays up for it)', (await perm()) === 'grantAll > live_updates > screen:live_updates'
    && await page.evaluate(() => document.getElementById('permModal').classList.contains('show')), await perm());
  await page.evaluate(() => { window.__status.live_updates = true; permOnChange(); closePermSheet(true); }); await wait(page, 100);

  // an app build before Android 16 does not report it: no row
  await page.evaluate(() => { delete window.__status.live_updates; openPermSheet('manual'); }); await wait(page, 200);
  check('   an Android before 16 (the key is not reported) shows no Live updates row', !(await rows()).some(x => x.startsWith('Live updates')) && (await rows()).some(x => x.startsWith('Notifications:')), (await rows()).join('|'));
  await page.evaluate(() => closePermSheet(true));

  // the one-time offer of "grant everything" by a working mode ignores a row no command can grant
  await page.evaluate(() => {
    isPrivilegedActive = true; permOfferDone = false; window.__settings = { perm_intro_v62: '1' }; window.__saved.length = 0;
    window.__status = { files: true, restricted_settings: true, usage: true, overlay: true, install_unknown: true, bluetooth: true, notifications: true, live_updates: false, storage_legacy: true, secure_settings: true, dump: true, read_logs: true };
    permMaybeOfferAdb();
  }); await wait(page, 900);
  check('   the offer of a working mode\'s "grant everything" is not made when only Live updates is missing', page.__dialogs.length === 0 && !(await page.evaluate(() => window.__saved.includes('perm_adb_offer_v714'))), page.__dialogs.join('|'));
  await page.evaluate(() => { window.__status.notifications = false; permOfferDone = false; permMaybeOfferAdb(); }); await wait(page, 900);
  check('   it is made when something with a command is missing too', page.__dialogs.length === 1 && /Grant all remaining permissions/.test(page.__dialogs[0]) && (await page.evaluate(() => window.__saved.includes('perm_adb_offer_v714'))), page.__dialogs.join('|'));
  check('   no page error', page.__errors.length === 0, page.__errors.join(' | '));
  await page.close();

  // ======================================================================== 3) the tile names in every language (T-006)
  // The tiles are labelled "Working mode" and "Kill Apps" in English on every phone (Java and the manifest; the app has no res/values-xx), so the
  // Quick actions help card and the quick-list toast name them that way in every translation, not with a translated name the phone never shows.
  page = await open();
  const langs = await page.evaluate(() => I18N.langs.map(l => l.code).filter(c => c !== 'en'));
  const wrong = [];
  for (const lang of langs) {
    await page.evaluate(l => I18N.set(l), lang); await wait(page, 300);
    await page.evaluate(() => { window.__quick = ''; quickListId = ''; toggleQuickList('l1'); }); await wait(page, 200);
    const seen = await page.evaluate(() => ({ names: Array.from(document.querySelectorAll('#quickActionsHelp .color-card-subtitle b')).slice(1, 3).map(x => x.innerText), toast: document.getElementById('toast').innerText }));
    if (seen.names.join('|') !== 'Working mode|Kill Apps' || seen.toast.indexOf('Kill Apps') < 0 || seen.toast.indexOf('Gaming') < 0) wrong.push(lang + ': ' + JSON.stringify(seen));
  }
  check('3. in all ' + langs.length + ' languages the Quick actions card says to drag "Working mode" and "Kill Apps", and the quick-list toast names the "Kill Apps" tile', langs.length === 13 && wrong.length === 0, wrong.join(' | ') || langs.join(','));
  check('   no page error', page.__errors.length === 0, page.__errors.join(' | '));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL script error: ' + e.stack); process.exit(1); });
