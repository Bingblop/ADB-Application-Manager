// v7.14 Permissions: the sheet lists every permission the app uses (Read/Write External Storage, DUMP, Read Logs, Write Secure Settings, Bluetooth Connect, ...),
// with a lock and a note on what only ADB, Shizuku or root can grant while none is active; with a working mode every Allow grants by command; the app starts again
// when the sheet closes after something was granted; Settings > Application Permissions opens it in full (Granted / Not granted, Grant, Grant all); the first
// working mode since install offers once to grant the rest; and at start an unauthorized Shizuku is asked for by itself (first-launch sheet after its answer).
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const FLAG = { perm_intro_v62: '1' };
const FLAGP = { perm_intro_v62: '1', perm_adb_offer_v714: '1' };          // and the one-time offer was made already (it would grant everything on load)
// a build that reports every row; nothing held
const NONE = { files: false, usage: false, overlay: false, install_unknown: false, restricted_settings: false, bluetooth: false, notifications: false,
  storage_legacy: false, secure_settings: false, dump: false, read_logs: false };
const ORDER = ['All files access', 'Allow Restricted Settings', 'Usage access', 'Display over other apps', 'Install unknown apps', 'Bluetooth Connect', 'Notifications',
  'Read/Write External Storage', 'Write Secure Settings', 'Dump system services (DUMP)', 'Read Logs'];
const LOCKABLE = ['storage_legacy', 'secure_settings', 'dump', 'read_logs'];

(async () => {
  const b = await chromium.launch();
  const open = async (opts) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error: ' + e.message); });
    page.__dialogs = []; page.__answer = true;
    page.on('dialog', d => { page.__dialogs.push(d.message()); page.__answer ? d.accept() : d.dismiss(); });
    await page.addInitScript(inst.initScript, Object.assign({ kv: FLAG, perm: NONE, mode: { priv: false } }, opts || {}));
    // the title the native dialog gets (nextDialogTitle hands it to the bridge's setNextDialogTitle): recorded here
    await page.addInitScript(() => { window.__titles = []; if (window.AndroidBridge) window.AndroidBridge.setNextDialogTitle = t => { window.__titles.push(t); }; });
    await page.goto(PAGE);
    await page.waitForFunction(() => typeof window.maybeShowPermIntro === 'function' && typeof window.openAppPermissions === 'function');
    await page.evaluate(() => { window.__toasts = []; const o = window.showToast; window.showToast = function (m) { window.__toasts.push(String(m)); return o.apply(this, arguments); }; });
    return page;
  };
  const scenario = async (opts, fn) => { const page = await open(opts); try { await fn(page); } finally { await page.close(); } };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const sheet = page => ev(page, () => {
    const m = document.getElementById('permModal'), vis = id => getComputedStyle(document.getElementById(id)).display !== 'none';
    return { shown: m.classList.contains('show'), title: document.getElementById('permTitle').innerText.trim(),
      rows: Array.from(m.querySelectorAll('.pm-row')).map(r => ({ key: r.dataset.perm, name: r.querySelector('.pm-name').innerText.trim(), perm: (r.querySelector('.pm-perm') || {}).innerText || '',
        ok: r.querySelector('.pm-ok') ? r.querySelector('.pm-ok').innerText.trim() : '', btn: r.querySelector('button') ? r.querySelector('button').innerText.trim() : '',
        lock: r.querySelector('.pm-lock') ? r.querySelector('.pm-lock').innerText.trim() : '', lockIcon: !!r.querySelector('.pm-lock svg'),
        state: r.querySelector('.pm-state') ? r.querySelector('.pm-state').innerText.trim() : '' })),
      all: vis('permAllBtn') ? document.getElementById('permAllBtn').innerText.trim() : '', close: document.getElementById('permCloseBtn').innerText.trim() };
  });
  const row = (s, k) => s.rows.find(r => r.key === k) || {};
  const perms = page => ev(page, () => window.__calls.perms.join(','));
  const calls = (page, k) => ev(page, k => window.__calls[k] || 0, k);
  const kv = (page, k) => ev(page, k => window.__kv[k] || '', k);
  const toasts = page => ev(page, () => window.__toasts.slice());
  const grant = (page, k) => page.click('#permRows .pm-row[data-perm="' + k + '"] button');
  const settle = (page, ms) => page.waitForTimeout(ms || 700);

  // 1) without a working mode
  await scenario({}, async page => {
    await ev(page, () => openPermSheet('manual'));
    let s = await sheet(page);
    check('1. the sheet lists every permission the app uses, in this order', JSON.stringify(s.rows.map(r => r.name)) === JSON.stringify(ORDER), JSON.stringify(s.rows.map(r => r.name)));
    check('   each row names the Android permission under it (READ_LOGS, BLUETOOTH_CONNECT, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, DUMP, ...)',
      row(s, 'read_logs').perm === 'READ_LOGS' && row(s, 'bluetooth').perm === 'BLUETOOTH_CONNECT' && row(s, 'usage').perm === 'PACKAGE_USAGE_STATS' && row(s, 'overlay').perm === 'SYSTEM_ALERT_WINDOW'
      && row(s, 'dump').perm === 'DUMP' && row(s, 'secure_settings').perm === 'WRITE_SECURE_SETTINGS' && row(s, 'storage_legacy').perm === 'READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE', JSON.stringify(s.rows.map(r => r.perm)));
    check('   the permission names are not translated', await ev(page, () => Array.from(document.querySelectorAll('#permRows .pm-perm')).every(e => e.getAttribute('translate') === 'no')));
    check('   what only ADB, Shizuku or root can grant is listed with a lock and a note, no Allow button', LOCKABLE.every(k => row(s, k).lock === 'Needs ADB, Shizuku or root' && row(s, k).lockIcon && !row(s, k).btn),
      JSON.stringify(LOCKABLE.map(k => row(s, k))));
    check('   the rest has an Allow button (Bluetooth Connect and Notifications too: their Android dialog)', s.rows.filter(r => !LOCKABLE.includes(r.key)).every(r => r.btn === 'Allow' && !r.lock));
    await ev(page, () => permAllow('read_logs'));
    check('   a locked one cannot be asked for (nothing is called, a toast says why)', (await perms(page)) === '' && (await toasts(page)).includes('Needs ADB, Shizuku or root'));
    await grant(page, 'bluetooth');
    check('   Allow on Bluetooth Connect asks the app (its permission dialog) and the sheet stays', (await perms(page)) === 'bluetooth' && (await sheet(page)).shown);
    await ev(page, () => { window.__perm.bluetooth = true; window.onPermissionsChanged(); });
    s = await sheet(page);
    check('   the dialog answered yes: the row shows the tick', row(s, 'bluetooth').ok === '✓ Allowed' && !row(s, 'bluetooth').btn, JSON.stringify(row(s, 'bluetooth')));
    await ev(page, () => { window.__calls.perms.length = 0; });
    await page.click('#permAllBtn');
    for (let i = 0; i < 8; i++) { await settle(page, 350); await ev(page, () => window.onAppResume()); }
    check('   "Allow all" walks only through what can be asked for here: never a locked row', (await perms(page)).split(',').every(k => !LOCKABLE.includes(k)) && (await perms(page)).split(',')[0] === 'files', await perms(page));
    await page.click('#permCloseBtn');
    await settle(page);
    check('   closing after Bluetooth Connect was allowed starts the app again (once)', (await calls(page, 'reload')) === 1);
  });
  await scenario({ perm: Object.assign({}, NONE, { bluetooth: true, bluetooth_na: true }) }, async page => {
    await ev(page, () => openPermSheet('manual'));
    check('   below Android 12 Bluetooth Connect is not needed and the row says so', row(await sheet(page), 'bluetooth').ok === '✓ Not needed');
  });
  await scenario({}, async page => {
    await ev(page, () => openPermSheet('manual'));
    await page.click('#permCloseBtn');
    await settle(page);
    check('   closing with nothing granted does not start the app again', (await calls(page, 'reload')) === 0);
  });

  // 2) with a working mode
  await scenario({ kv: FLAGP, mode: { priv: true }, grantFilesByShell: true }, async page => {
    await ev(page, () => openPermSheet('manual'));
    let s = await sheet(page);
    check('2. with a working mode nothing is locked: every missing row has an Allow button', s.rows.every(r => r.btn === 'Allow' && !r.lock), JSON.stringify(s.rows.filter(r => r.lock)));
    await grant(page, 'read_logs');
    s = await sheet(page);
    check('   Allow on Read Logs grants it by command at once (the phone holds it after) and ticks the row', (await perms(page)) === 'read_logs' && row(s, 'read_logs').ok === '✓ Allowed');
    await grant(page, 'files');
    s = await sheet(page);
    check('   All files access is granted by command too (no Android screen)', (await perms(page)) === 'read_logs,files' && row(s, 'files').ok === '✓ Allowed');
    await grant(page, 'bluetooth');
    check('   and Bluetooth Connect (pm grant)', row(await sheet(page), 'bluetooth').ok === '✓ Allowed');
    check('   the app does not start again after each one', (await calls(page, 'reload')) === 0);
    await page.click('#permCloseBtn');
    await settle(page);
    check('   it starts again (once) when the sheet closes', (await calls(page, 'reload')) === 1);
  });
  await scenario({ kv: FLAGP, mode: { priv: true }, refuseReadLogs: true }, async page => {
    await ev(page, () => openPermSheet('manual'));
    await grant(page, 'read_logs');
    const s = await sheet(page);
    check('   a grant the phone does not hold afterwards leaves the row asking, and says so', row(s, 'read_logs').btn === 'Allow' && (await toasts(page)).includes('The phone did not grant this permission'));
  });
  await scenario({ kv: FLAGP, mode: { priv: true }, grantAll: true }, async page => {
    await ev(page, () => openPermSheet('manual'));
    await page.click('#permAllBtn');
    await settle(page);
    check('   "Allow all" grants everything by command (one call), closes the sheet and starts the app again', (await calls(page, 'grantAll')) === 1 && !(await sheet(page)).shown && (await calls(page, 'reload')) === 1
      && (await ev(page, () => permStatus.read_logs && permStatus.bluetooth && permStatus.dump)));
  });
  await scenario({}, async page => {
    await ev(page, () => openPermSheet('manual'));
    await ev(page, () => { window.__mode.priv = true; checkAllWorkingModes(false); });
    const s = await sheet(page);
    check('   a working mode arriving while the sheet is open turns the locks into Allow buttons', LOCKABLE.every(k => row(s, k).btn === 'Allow' && !row(s, k).lock));
  });

  // 3) Settings > Application Permissions
  await scenario({ kv: FLAGP, mode: { priv: true }, grantAll: true, perm: Object.assign({}, NONE, { files: true, usage: true, overlay: true }) }, async page => {
    const pos = await ev(page, () => { const btn = document.getElementById('appPermsBtn'); return { after: !!btn && !!btn.previousElementSibling && btn.previousElementSibling.id === 'langSelect' && btn.parentElement.id === 'languageCard', inPrefs: !!btn && !!btn.closest('#view-prefs'), text: btn && btn.innerText.trim() }; });
    check('3. Settings has an "Application Permissions" button right after the language setting', pos.after && pos.inPrefs && pos.text === 'Application Permissions', JSON.stringify(pos));
    await ev(page, () => document.getElementById('appPermsBtn').click());
    let s = await sheet(page);
    check('   it opens the sheet listing every permission', s.shown && s.title === 'Application Permissions' && s.rows.length === ORDER.length, s.title + ' ' + s.rows.length);
    check('   each says whether it is granted ("✓ Granted", or "Not granted" with a Grant button)', s.rows.every(r => r.ok ? r.ok === '✓ Granted' && !r.state : r.state === 'Not granted' && r.btn === 'Grant'), JSON.stringify(s.rows.map(r => [r.key, r.ok, r.state, r.btn])));
    check('   and there is a "Grant all" button', s.all === 'Grant all');
    await grant(page, 'dump');
    s = await sheet(page);
    check('   Grant on one grants it', row(s, 'dump').ok === '✓ Granted' && !row(s, 'dump').state);
    await page.click('#permAllBtn');
    await settle(page);
    check('   Grant all grants the rest by command and starts the app again', (await calls(page, 'grantAll')) === 1 && (await calls(page, 'reload')) === 1 && !(await sheet(page)).shown);
  });
  await scenario({ perm: Object.assign({}, NONE, { files: true, usage: true, overlay: true, install_unknown: true, restricted_settings: true, bluetooth: true, notifications: true, storage_legacy: true, secure_settings: true, dump: true }) }, async page => {
    await ev(page, () => openAppPermissions());
    const s = await sheet(page);
    check('   without a working mode, with only a locked one missing, there is nothing to grant here: it shows the lock, no Grant all, "Done"', row(s, 'read_logs').lock && row(s, 'read_logs').state === 'Not granted' && !s.all && s.close === 'Done', JSON.stringify([row(s, 'read_logs'), s.all, s.close]));
  });

  // 4) the first working mode since install: one offer
  await scenario({ mode: { priv: true }, grantAll: true }, async page => {
    await page.waitForFunction(() => window.__titles.length > 0, null, { timeout: 5000 }).catch(() => {});
    await settle(page);
    check('4. the first time a working mode is ready with permissions missing, a question offers to grant them all', page.__dialogs.length === 1 && /Grant all remaining permissions/.test(page.__dialogs[0]), JSON.stringify(page.__dialogs));
    check('   the native dialog gets its title first ("Grant permissions")', JSON.stringify(await ev(page, () => window.__titles)) === '["Grant permissions"]');
    check('   yes: everything is granted by command and the app starts again', (await calls(page, 'grantAll')) === 1 && (await calls(page, 'reload')) === 1);
    check('   it is remembered in the app\'s settings', (await kv(page, 'perm_adb_offer_v714')) === '1');
    await ev(page, () => checkAllWorkingModes(false));
    await settle(page);
    check('   and not asked again', page.__dialogs.length === 1);
  });
  await scenario({ mode: { priv: true }, grantAll: true, kv: Object.assign({ perm_adb_offer_v714: '1' }, FLAG) }, async page => {
    await settle(page, 1200);
    check('   a phone where it was asked before is not asked again (a new start of the app)', page.__dialogs.length === 0 && (await calls(page, 'grantAll')) === 0);
  });
  await scenario({ mode: { priv: false }, grantAll: true }, async page => {
    page.__answer = false;
    await settle(page, 1000);
    check('   without a working mode nothing is asked', page.__dialogs.length === 0 && (await kv(page, 'perm_adb_offer_v714')) === '');
    await ev(page, () => { window.__mode.priv = true; checkAllWorkingModes(false); });
    await settle(page);
    check('   when one comes later, it is asked then', page.__dialogs.length === 1);
    check('   no: nothing is granted, nothing starts again, and it is not asked again', (await calls(page, 'grantAll')) === 0 && (await calls(page, 'reload')) === 0 && (await kv(page, 'perm_adb_offer_v714')) === '1');
  });
  await scenario({ mode: { priv: true }, grantAll: true, perm: Object.assign({}, NONE, { files: true, usage: true, overlay: true, install_unknown: true, restricted_settings: true, bluetooth: true, notifications: true, storage_legacy: true, secure_settings: true, dump: true, read_logs: true }) }, async page => {
    await settle(page, 1000);
    check('   with nothing missing there is nothing to ask', page.__dialogs.length === 0);
  });
  await scenario({ mode: { priv: false }, grantAll: true }, async page => {
    await ev(page, () => openPermSheet('manual'));
    await ev(page, () => { window.__mode.priv = true; checkAllWorkingModes(false); });
    await settle(page);
    check('   while the sheet is open it does not ask (the sheet\'s "Allow all" does the same)', page.__dialogs.length === 0);
    await page.click('#permCloseBtn');
    await settle(page);
    check('   and once the sheet was seen with the working mode, it does not ask afterwards either', page.__dialogs.length === 0 && (await kv(page, 'perm_adb_offer_v714')) === '1');
  });
  await scenario({ kv: {}, mode: { priv: true }, grantAll: true }, async page => {
    await page.waitForSelector('#permModal.show', { timeout: 6000 }).catch(() => {});
    check('   on the very first launch the first-launch sheet comes instead of the question', page.__dialogs.length === 0 && (await sheet(page)).title === 'Allow a few permissions');
  });

  // 5) Shizuku at start
  const SZ = { running: true, authorized: false };
  await scenario({ kv: {}, mode: { priv: false, shizuku: Object.assign({}, SZ) } }, async page => {
    await page.waitForFunction(() => window.__calls.shizukuAsk >= 1, null, { timeout: 4000 }).catch(() => {});
    check('5. first launch, no working mode, Shizuku running but not authorized: its dialog is asked for by itself', (await calls(page, 'shizukuAsk')) === 1);
    await settle(page, 2600);
    check('   the first-launch sheet waits for the answer', !(await sheet(page)).shown && (await ev(page, () => permIntroWaiting)) === true);
    await ev(page, () => { window.__mode.shizuku.authorized = true; window.__mode.priv = true; window.onShizukuPermissionResult(true); });
    await page.waitForSelector('#permModal.show', { timeout: 3000 }).catch(() => {});
    const s = await sheet(page);
    check('   after a yes it comes, already able to grant through Shizuku (no locks)', s.shown && s.title === 'Allow a few permissions' && s.rows.every(r => !r.lock) && row(s, 'read_logs').btn === 'Allow', JSON.stringify(s.rows.filter(r => r.lock)));
    check('   and the one-time question does not come on top of it', page.__dialogs.length === 0);
    check('   asked once only', (await calls(page, 'shizukuAsk')) === 1);
  });
  await scenario({ kv: {}, mode: { priv: false, shizuku: Object.assign({}, SZ) } }, async page => {
    await page.waitForFunction(() => window.__calls.shizukuAsk >= 1, null, { timeout: 4000 }).catch(() => {});
    await settle(page, 2600);
    await ev(page, () => { szAutoAskAt = Date.now() - 16000; maybeShowPermIntro(); });          // no answer within the wait
    check('   with no answer for a while the sheet comes anyway (with locks: nothing was authorized)', (await sheet(page)).shown && row(await sheet(page), 'dump').lock === 'Needs ADB, Shizuku or root');
  });
  await scenario({ mode: { priv: false, shizuku: Object.assign({}, SZ) } }, async page => {
    await page.waitForFunction(() => window.__calls.shizukuAsk >= 1, null, { timeout: 4000 }).catch(() => {});
    check('   asked on a later start too (not only the first)', (await calls(page, 'shizukuAsk')) === 1);
    await ev(page, () => window.onShizukuPermissionResult(false));
    check('   a no is counted in the app\'s settings', (await kv(page, 'shizuku_auto_denied')) === '1');
    await ev(page, () => checkAllWorkingModes(false));
    check('   and not asked again in the same start', (await calls(page, 'shizukuAsk')) === 1);
  });
  await scenario({ kv: Object.assign({ shizuku_auto_denied: '1' }, FLAG), mode: { priv: false, shizuku: Object.assign({}, SZ) } }, async page => {
    await page.waitForFunction(() => window.__calls.shizukuAsk >= 1, null, { timeout: 4000 }).catch(() => {});
    await ev(page, () => window.onShizukuPermissionResult(false));
    check('   after one no it still asks; the second no is counted', (await calls(page, 'shizukuAsk')) === 1 && (await kv(page, 'shizuku_auto_denied')) === '2');
  });
  const notAsked = async (label, opts) => scenario(opts, async page => {
    await settle(page, 1500);
    check(label, (await calls(page, 'shizukuAsk')) === 0);
  });
  await notAsked('   after two noes it stops asking by itself', { kv: Object.assign({ shizuku_auto_denied: '2' }, FLAG), mode: { priv: false, shizuku: Object.assign({}, SZ) } });
  await scenario({ kv: Object.assign({ shizuku_auto_denied: '2' }, FLAG), mode: { priv: false, shizuku: Object.assign({}, SZ) } }, async page => {
    await settle(page, 1200);
    await ev(page, () => handleShizukuTap());
    check('   the Shizuku card still asks when tapped', (await calls(page, 'shizukuAsk')) === 1);
  });
  await notAsked('   not while a working mode is ready (ADB)', { mode: { priv: true, shizuku: Object.assign({}, SZ) } });
  await notAsked('   not when another mode is chosen (ADB TCP)', { mode: { priv: false, configured: 'adb_tcp', shizuku: Object.assign({}, SZ) } });
  await notAsked('   not when Shizuku is not running', { mode: { priv: false, shizuku: { running: false } } });
  await notAsked('   not when it is too old (before v11)', { mode: { priv: false, shizuku: { running: true, preV11: true } } });
  await scenario({ mode: { priv: false, configured: 'shizuku', shizuku: Object.assign({}, SZ) } }, async page => {
    await page.waitForFunction(() => window.__calls.shizukuAsk >= 1, null, { timeout: 4000 }).catch(() => {});
    check('   asked when Shizuku is the chosen mode', (await calls(page, 'shizukuAsk')) === 1);
  });

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
