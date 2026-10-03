// v6.1 Permissions: the first time the app opens it offers the three accesses that have no dialog of their own (All files access, Usage access,
// Display over other apps); the same sheet is under About; and an action that fails for want of file access asks for it on the spot, then carries on by itself
// once the access is there (file manager, storage search, reading a package).
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const NONE = { files: false, usage: false, overlay: false };
const ALL = { files: true, usage: true, overlay: true };
const FLAG = { perm_intro_v61: '1' };          // the first-launch sheet has been dealt with
const NAMES = ['All files access', 'Usage access', 'Display over other apps'];
const PKG = { type: 'apk', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 9e6,
  splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 9e6, isBase: true, split: '' }], signed: true, installed: false };

(async () => {
  const b = await chromium.launch();
  // opts go to the mock bridge (perm, kv, mode, grantUsageByShell, ...); by default nothing is allowed, there is no working mode (ADB, Shizuku, Root)
  // and the first-launch sheet is done with
  const open = async (opts, viewport, extra) => {
    const page = await b.newPage({ viewport: viewport || { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, Object.assign({ kv: FLAG, perm: NONE, mode: { priv: false } }, opts || {}));
    await page.addInitScript(o => {
      // a script can wait for the first-launch check (the app runs it a little after opening, and again when What's new closes)
      const st = window.setTimeout;
      window.__introRan = 0;
      window.setTimeout = function (fn, ms, ...rest) {
        if (typeof fn === 'function' && fn.name === 'maybeShowPermIntro') { const f = fn; fn = function () { try { return f.apply(this, arguments); } finally { window.__introRan++; } }; }
        return st.call(this, fn, ms, ...rest);
      };
      if (o.whatsNewOpen) document.addEventListener('DOMContentLoaded', () => document.getElementById('whatsNewModal').classList.add('show'));
      if (o.otherModal) document.addEventListener('DOMContentLoaded', () => document.getElementById(o.otherModal).classList.add('show'));
    }, extra || {});
    await page.goto(PAGE);
    await page.waitForFunction(() => typeof window.onFileAccessNeeded === 'function' && typeof window.maybeShowPermIntro === 'function');
    // what the page says in toasts is kept, so a script does not race the two seconds a toast stays
    await page.evaluate(() => { window.__toasts = []; const o = window.showToast; window.showToast = function (m) { window.__toasts.push(String(m)); return o.apply(this, arguments); }; });
    return page;
  };
  const scenario = async (opts, fn, viewport, extra) => { const page = await open(opts, viewport, extra); try { await fn(page); } finally { await page.close(); } };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const sheet = page => ev(page, () => {
    const m = document.getElementById('permModal'), vis = id => getComputedStyle(document.getElementById(id)).display !== 'none';
    return { shown: m.classList.contains('show'), title: document.getElementById('permTitle').innerText.trim(), sub: document.getElementById('permSubtitle').innerText.trim(),
      reason: vis('permReason') ? document.getElementById('permReason').innerText.trim() : null,
      rows: Array.from(m.querySelectorAll('.pm-row')).map(r => ({ name: r.querySelector('.pm-name').innerText.trim(), ok: !!r.querySelector('.pm-ok'), btn: !!r.querySelector('button') })),
      all: vis('permAllBtn'), close: document.getElementById('permCloseBtn').innerText.trim() };
  });
  const rowsOf = s => s.rows.map(r => (r.ok ? '✓' : '—')).join(' ');           // ✓ allowed, — still to allow
  const perms = page => ev(page, () => window.__calls.perms.join(','));
  const saved = page => ev(page, () => window.__calls.kv.filter(k => /^perm_intro_v61=/.test(k)).length);
  const toasts = page => ev(page, () => window.__toasts.slice());
  const wasToast = async (page, re) => (await toasts(page)).some(t => re.test(t));
  // the person is back from an Android screen where `now` was switched on (or not: {})
  const back = (page, now) => ev(page, w => { Object.assign(window.__perm, w); window.onAppResume(); }, now);
  const intro = page => ev(page, () => maybeShowPermIntro());
  const allow = (page, n) => page.click('#permRows .pm-row:nth-child(' + n + ') button');
  const closed = async page => (await sheet(page)).shown === false;
  const waitPerms = (page, n) => page.waitForFunction(k => window.__calls.perms.length >= k, n);
  const ask = (page, why) => ev(page, w => { window.__ran = 0; return promptFileAccess(w || 'To do that, this app needs All-files access.', () => { window.__ran++; }, false); }, why);
  const ran = page => ev(page, () => window.__ran);
  const settle = page => page.waitForTimeout(600);           // for "nothing more happens": longer than the pauses between the steps of the walk

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 1) the first time the app is opened: five pages at once, each waits for the app's own timer
  const A = await open({ kv: {} });
  const D1 = await open({ kv: FLAG });
  const D2 = await open({ kv: {}, perm: ALL });
  const D3 = await open({ kv: {}, perm: { files: true, usage: true, overlay: false } });
  const E = await open({ kv: {} }, null, { whatsNewOpen: true });
  const F = await open({ kv: {} }, null, { otherModal: 'fmActionModal' });
  await Promise.all([A, D1, D2, D3, E, F].map(p => p.waitForFunction(() => window.__introRan >= 1)));

  let s = await sheet(A);
  check('1. a few seconds after the app opens for the first time, a sheet offers the three permissions', s.shown && s.title === 'Allow a few permissions' && s.reason === null, s.title);
  check('   they are All files access, Usage access and Display over other apps, none allowed yet, each with an Allow button', JSON.stringify(s.rows.map(r => r.name)) === JSON.stringify(NAMES) && s.rows.every(r => !r.ok && r.btn), JSON.stringify(s.rows));
  check('   there is an "Allow all" and a "Not now"; the subtitle says each can be skipped and changed later', s.all && s.close === 'Not now' && /skip the rest/.test(s.sub) && /changed later/.test(s.sub), s.sub);
  check('   each one says what it is for', await ev(A, () => Array.from(document.querySelectorAll('#permRows .pm-why')).every(e => e.innerText.trim().length > 25)));
  check('   the three Allow buttons are told apart for a screen reader ("Allow All files access", …)', JSON.stringify(await ev(A, () => Array.from(document.querySelectorAll('#permRows button')).map(b => b.getAttribute('aria-label')))) === JSON.stringify(NAMES.map(n => 'Allow ' + n)));
  check('   nothing is remembered until the sheet is dealt with', (await saved(A)) === 0 && (await perms(A)) === '');
  await allow(A, 1);
  check('   Allow opens the Android screen of that access (bridge: requestAllFilesAccess) and the sheet stays', (await perms(A)) === 'files' && (await sheet(A)).shown);
  await back(A, {});
  s = await sheet(A);
  check('   coming back without having allowed it changes nothing', rowsOf(s) === '— — —' && s.shown && s.all);
  await back(A, { files: true });
  s = await sheet(A);
  check('   coming back after allowing it ticks that row ("✓ Allowed", no button) and the rest stay', rowsOf(s) === '✓ — —' && !s.rows[0].btn && s.rows[1].btn && s.rows[2].btn && s.all && s.close === 'Not now', rowsOf(s));
  check('   the tick says "Allowed"', (await ev(A, () => document.querySelector('#permRows .pm-ok').innerText.trim())) === '✓ Allowed');
  await allow(A, 2);
  await back(A, { usage: true });
  s = await sheet(A);
  check('   with one left "Allow all" goes (its own Allow button is enough)', (await perms(A)) === 'files,usage' && rowsOf(s) === '✓ ✓ —' && !s.all && s.close === 'Not now', rowsOf(s));
  await allow(A, 3);
  await back(A, { overlay: true });
  s = await sheet(A);
  check('   with all three allowed the closing button reads "Done"', (await perms(A)) === 'files,usage,overlay' && rowsOf(s) === '✓ ✓ ✓' && s.rows.every(r => !r.btn) && !s.all && s.close === 'Done', rowsOf(s));
  await A.click('#permCloseBtn');
  check('   Done closes it and the app remembers it was shown (it does not come back)', (await closed(A)) && (await saved(A)) === 1 && (await ev(A, () => window.__kv.perm_intro_v61)) === '1');
  check('   no "file access allowed" toast for something nobody was waiting on', !(await wasToast(A, /File access allowed/)));
  await A.close();

  s = await sheet(D1);
  check('2. a phone where the sheet was already dealt with is not asked again (even though nothing is allowed)', !s.shown && (await ev(D1, () => window.__calls.kv.length)) === 0 && (await perms(D1)) === '');
  await D1.close();
  s = await sheet(D2);
  check('   a phone that has all three allowed is not asked, and is marked as done', !s.shown && (await saved(D2)) === 1);
  await D2.close();
  s = await sheet(D3);
  check('   with only one missing the sheet comes: the two allowed show ✓, the third has its Allow button, no "Allow all"', s.shown && rowsOf(s) === '✓ ✓ —' && s.rows[2].btn && !s.all && s.close === 'Not now', rowsOf(s));
  await D3.close();

  s = await sheet(E);
  check('3. while another sheet is open (What\'s new) the first-launch sheet waits', !s.shown && (await ev(E, () => permIntroWaiting)) === true && (await saved(E)) === 0);
  await ev(E, () => closeWhatsNew());
  await E.waitForSelector('#permModal.show');
  s = await sheet(E);
  check('   and comes as soon as that one is closed', s.shown && s.title === 'Allow a few permissions' && (await ev(E, () => window.__introRan)) >= 2 && !(await ev(E, () => permIntroWaiting)));
  await E.close();
  s = await sheet(F);
  check('   the same for any other sheet that is open (a menu, say): it waits and looks again every few seconds', !s.shown && (await saved(F)) === 0 && (await ev(F, () => permIntroWaiting)) === true);
  await ev(F, () => document.getElementById('fmActionModal').classList.remove('show'));          // closed by something other than What's new
  await F.waitForSelector('#permModal.show');
  check('   so it comes by itself soon after that sheet is closed', (await sheet(F)).title === 'Allow a few permissions');
  await F.close();

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 2) Allow all
  await scenario({ kv: {} }, async page => {
    await intro(page);
    await page.click('#permAllBtn');
    check('4. "Allow all" opens the first Android screen at once (All files access)', (await perms(page)) === 'files');
    await back(page, { files: true });
    await waitPerms(page, 2);
    let s = await sheet(page);
    check('   once the person is back it opens the next one (Usage access) and the first shows ✓', (await perms(page)) === 'files,usage' && rowsOf(s) === '✓ — —', rowsOf(s));
    await back(page, {});
    await waitPerms(page, 3);
    check('   leaving a screen without allowing does not stop the walk: the last one opens', (await perms(page)) === 'files,usage,overlay');
    await back(page, { overlay: true });
    await settle(page);
    s = await sheet(page);
    check('   after the last it stops; the skipped one is still there to allow by hand', (await perms(page)) === 'files,usage,overlay' && rowsOf(s) === '✓ — ✓' && s.shown && !s.all && s.close === 'Not now', rowsOf(s));
    await allow(page, 2);
    check('   its Allow button opens that screen again', (await perms(page)) === 'files,usage,overlay,usage');
  });
  await scenario({ kv: {} }, async page => {
    await intro(page);
    await page.click('#permAllBtn');
    await page.click('#permCloseBtn');
    await back(page, { files: true });
    await settle(page);
    check('5. closing the sheet in the middle of "Allow all" cancels the rest (no Android screen opens behind the person\'s back)', (await perms(page)) === 'files' && (await closed(page)));
  });
  // a working mode (ADB / Shizuku / Root) can switch two of them on itself
  await scenario({ kv: {}, mode: { priv: true }, grantUsageByShell: true, grantOverlayByShell: true }, async page => {
    await intro(page);
    await allow(page, 2);
    let s = await sheet(page);
    check('6. with a working mode, Usage access is allowed on the spot (no Android screen, no coming back)', (await perms(page)) === 'usage' && rowsOf(s) === '— ✓ —', rowsOf(s));
    await page.click('#permAllBtn');
    check('   "Allow all" then goes to the one that needs the screen (All files access)', (await perms(page)) === 'usage,files');
    await back(page, { files: true });
    await waitPerms(page, 3);
    await page.waitForFunction(() => document.querySelectorAll('#permRows .pm-ok').length === 3);
    s = await sheet(page);
    check('   and Display over other apps follows by itself: all three ✓ and "Done"', (await perms(page)) === 'usage,files,overlay' && rowsOf(s) === '✓ ✓ ✓' && s.close === 'Done', rowsOf(s));
  });
  await scenario({ kv: {}, mode: { priv: true }, grantUsageByShell: true, grantOverlayByShell: true }, async page => {
    await intro(page);
    await page.click('#permAllBtn');
    await back(page, { files: true });
    await page.waitForFunction(() => document.querySelectorAll('#permRows .pm-ok').length === 3);
    check('   from nothing, "Allow all" needs just the one screen: the other two are allowed by the mode in between', (await perms(page)) === 'files,usage,overlay' && (await sheet(page)).close === 'Done');
  });

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 3) every way of closing the first-launch sheet remembers it
  for (const [how, close] of [['"Not now"', p => p.click('#permCloseBtn')], ['the ✕ at the top', p => p.click('#permModal .sheet-header > div:last-child')],
    ['a tap beside the sheet', p => p.click('#permModal', { position: { x: 4, y: 4 } })], ['the Back button', p => ev(p, () => handleAndroidBack())]]) {
    await scenario({ kv: {} }, async page => {
      await intro(page);
      const r = await close(page);
      check('7. ' + how + ' closes the first-launch sheet and it is not offered again' + (how === 'the Back button' ? ' (Back says it handled it)' : ''), (await closed(page)) && (await saved(page)) === 1 && (how !== 'the Back button' || r === true));
      await ev(page, () => maybeShowPermIntro());
      check('   a second look finds it done: nothing opens', await closed(page));
    });
  }

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 4) About -> Permissions
  await scenario({ perm: { files: true, usage: false, overlay: false } }, async page => {
    await ev(page, () => switchView('about'));
    await page.click('#view-about button:has-text("Permissions")');
    let s = await sheet(page);
    check('8. About has a Permissions button: it opens the sheet with what is allowed now', s.shown && s.title === 'Permissions' && rowsOf(s) === '✓ — —' && s.all && s.close === 'Not now' && s.reason === null, rowsOf(s));
    await page.click('#permCloseBtn');
    check('   closing it from there writes nothing (it is not the first-launch sheet)', (await closed(page)) && (await ev(page, () => window.__calls.kv.length)) === 0);
    await back(page, { usage: true, overlay: true });
    check('   coming back from Android settings with the sheet closed opens nothing', await closed(page));
    await page.click('#view-about button:has-text("Permissions")');
    s = await sheet(page);
    check('   with everything allowed it says so and closes with "Done"', rowsOf(s) === '✓ ✓ ✓' && !s.all && s.close === 'Done', rowsOf(s));
    await page.click('#permCloseBtn');
    check('   Done closes it', await closed(page));
  });
  await scenario({ kv: {} }, async page => {
    await ev(page, () => { switchView('about'); openPermSheet('manual'); });
    await page.click('#permCloseBtn');
    check('   about the first-launch sheet: closing the About one does not count as having seen that one', (await saved(page)) === 0);
  });
  await scenario({}, async page => {
    await ev(page, () => openPermSheet('manual'));
    await ev(page, () => { window.__perm.usage = true; window.onPermissionsChanged(); });
    const s = await sheet(page);
    check('9. the page also follows the app\'s own notice that a permission was answered (window.onPermissionsChanged)', rowsOf(s) === '— ✓ —', rowsOf(s));
  });

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 5) an action needs file access and there is none
  await scenario({}, async page => {
    await ev(page, () => onFileAccessNeeded('To save a file there'));
    let s = await sheet(page);
    check('10. when the app reports that an action failed for want of file access, a sheet asks for exactly that one', s.shown && s.title === 'File access needed' && s.rows.length === 1 && s.rows[0].name === 'All files access' && !s.rows[0].ok && !s.all && s.close === 'Not now', JSON.stringify(s.rows));
    check('   it says what was being done and why', s.reason === 'To save a file there, this app needs All-files access.' && s.sub === 'Allow it to continue', s.reason);
    await ev(page, () => onFileAccessNeeded('To open this file'));
    check('   a second report while it is open does not stack another one', (await ev(page, () => document.querySelectorAll('.modal-overlay.show').length)) === 1);
    await allow(page, 1);
    check('   Allow opens the Android screen for it', (await perms(page)) === 'files');
    await back(page, { files: true });
    check('   when the access is there the sheet closes by itself', await closed(page));
    check('   with nothing waiting to be redone there is no toast', !(await wasToast(page, /File access allowed/)));
  });
  await scenario({}, async page => {
    await ev(page, () => onFileAccessNeeded('To save a file there'));
    await page.click('#permCloseBtn');
    check('11. "Not now" closes it and writes nothing', (await closed(page)) && (await ev(page, () => window.__calls.kv.length)) === 0);
    await ev(page, () => onFileAccessNeeded('To save a file there'));
    check('   the failures that follow straight away (one action, many files) do not ask again', await closed(page));
    await ev(page, () => promptFileAccess('', null, true));
    let s = await sheet(page);
    check('   something the person asked for (a button) shows it anyway; with no reason it uses a general one', s.shown && s.reason === 'This action needs access to files on this phone.', s.reason);
    await page.click('#permCloseBtn');
    await ev(page, () => { permClosedAt = Date.now() - 6000; onFileAccessNeeded('To save a file there'); });
    check('   a few seconds later it asks again (the next thing the person does)', (await sheet(page)).shown);
    await page.click('#permCloseBtn');
    await ev(page, () => { permClosedAt = 0; window.__perm.files = true; onFileAccessNeeded('To save a file there'); });
    check('   if the access has been given in the meantime it does not ask at all', await closed(page));
  });
  await scenario({ perm: { files: true, usage: false, overlay: false } }, async page => {
    await ev(page, () => onFileAccessNeeded('To save a file there'));
    check('   a report from an app that has the access (the failure was something else) shows nothing', await closed(page));
  });

  // the prompt is on top of any other sheet, whichever one the failing action was started in
  await scenario({}, async page => {
    const ids = ['arcModal', 'fmActionModal', 'signModal', 'modesModal', 'privilegeModal', 'commandResultsModal'];
    const bad2 = [];
    for (const id of ids) {
      await ev(page, i => { document.getElementById(i).classList.add('show'); permClosedAt = 0; onFileAccessNeeded('To save a file there'); }, id);
      await page.waitForFunction(() => document.querySelector('#permModal .modal-sheet').getBoundingClientRect().bottom <= innerHeight + 0.5);
      const top = await ev(page, () => { const b = document.querySelector('#permRows button'), r = b.getBoundingClientRect(), e = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return !!(e && e.closest('#permModal')); });
      if (!top) bad2.push(id);
      await ev(page, i => { document.getElementById(i).classList.remove('show'); closePermSheet(); }, id);
    }
    check('   the prompt is painted over every other sheet (archive, file action, signing, working modes, privilege, a result report)', bad2.length === 0, JSON.stringify(bad2));
  });

  // the action that asked carries on once the access is there
  await scenario({}, async page => {
    check('12. an action that asks gets told the access is missing', (await ask(page)) === true && (await sheet(page)).shown);
    check('   it has not run yet', (await ran(page)) === 0);
    await back(page, { files: true });
    check('   the sheet closes when the access arrives, says so, and the action runs (once)', (await closed(page)) && (await wasToast(page, /File access allowed/)) && (await ran(page)) === 1);
    await back(page, {});
    await ev(page, () => window.onPermissionsChanged());
    check('   another return to the app does not run it again', (await ran(page)) === 1);
  });
  await scenario({}, async page => {
    await ask(page);
    await ev(page, () => { permRetryAt = Date.now() - 301000; });
    await back(page, { files: true });
    check('   an action asked for long ago (more than five minutes) is not run: the access may have come from anywhere', (await closed(page)) && (await ran(page)) === 0 && (await wasToast(page, /File access allowed/)));
  });
  await scenario({}, async page => {
    await ask(page);
    await ev(page, () => { permRetryAt = Date.now() - 299000; });
    await back(page, { files: true });
    check('   one asked for just now (under five minutes) is', (await ran(page)) === 1);
  });
  await scenario({ perm: { files: true, usage: false, overlay: false } }, async page => {
    const r = await ask(page);
    check('   when the access is already there nothing is asked and the action runs at once', r === false && (await closed(page)) && (await ran(page)) === 1);
  });
  await scenario({}, async page => {
    await ask(page);
    await page.click('#permCloseBtn');
    await back(page, { files: true });
    check('13. "Not now" cancels the action: allowing the access later (from About, say) does not run it', (await closed(page)) && (await ran(page)) === 0 && !(await wasToast(page, /File access allowed/)));
  });
  await scenario({}, async page => {
    await ask(page);
    await page.click('#permCloseBtn');
    check('   (asking again straight away shows nothing)', (await ask(page)) === true && (await closed(page)));
    await back(page, { files: true });
    check('   but that second ask is waiting: the access arriving from elsewhere runs it', (await ran(page)) === 1 && (await wasToast(page, /File access allowed/)));
  });
  await scenario({}, async page => {
    await ev(page, () => promptFileAccess('Why', () => { throw new Error('boom'); }, false));
    await back(page, { files: true });
    check('   an action that fails when it is run again does not break the page', (await closed(page)) && (await wasToast(page, /File access allowed/)));
  });

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 6) the file manager
  await scenario({}, async page => {
    await ev(page, () => switchView('files'));
    const listed = () => ev(page, () => window.__calls.opened.filter(x => x === 'fmList:/storage/emulated/0').length);
    check('14. opening the Files tab by itself shows its own hint but no sheet', (await listed()) === 1 && (await closed(page)) && (await ev(page, () => getComputedStyle(document.getElementById('fmAccessHint')).display)) !== 'none');
    await page.click('#fmTopCard .list-toolbar .term-run-btn');
    const s = await sheet(page);
    check('   asking for the storage folder (Go) when nothing can read it asks for the access, saying why', s.shown && s.title === 'File access needed' && /^To browse files on this phone, this app needs All-files access/.test(s.reason), s.reason);
    await back(page, { files: true });
    await page.waitForSelector('#fmList .perm-row');
    check('   once it is allowed the sheet closes, the folder is listed again by itself and the hint goes', (await closed(page)) && (await listed()) === 3 && (await wasToast(page, /File access allowed/))
      && (await ev(page, () => getComputedStyle(document.getElementById('fmAccessHint')).display)) === 'none' && (await page.locator('#fmList .perm-row').count()) === 2);
  });
  await scenario({ mode: { priv: true } }, async page => {
    await ev(page, () => { switchView('files'); fmGo('/storage/emulated/0'); });
    check('15. with a working mode the storage can be read without the access, so nothing is asked', await closed(page));
  });
  await scenario({}, async page => {
    await ev(page, () => { window.__fmEmptyFile = true; switchView('files'); fmGo('/storage/emulated/0'); });
    check('   Android may answer an empty list (not an error) for a folder the app is not allowed to read: that asks too', (await sheet(page)).shown);
    await page.click('#permCloseBtn');
    await ev(page, () => fmGo('/storage/emulated/0'));
    check('   and the person asking again right after "Not now" is asked again (they asked)', (await sheet(page)).shown);
  });
  await scenario({ mode: { priv: true } }, async page => {
    await ev(page, () => { window.__dirs = { '/storage/emulated/0/Empty': [] }; switchView('files'); fmGo('/storage/emulated/0/Empty'); fmGo('/storage/emulated/0/NoSuchFolder'); });
    check('   through a working mode an empty folder is just empty (and one that is not there is not a matter of access): nothing is asked', await closed(page));
  });
  await scenario({}, async page => {
    await ev(page, () => { switchView('files'); fmGo('/system'); });
    check('   a system folder that cannot be read is not a matter for this access: nothing is asked', await closed(page));
  });
  await scenario({}, async page => {
    await ev(page, () => switchView('files'));
    await page.click('#fmAccessHint button');
    check('16. the hint\'s own button goes to the Android screen without a sheet on top', (await perms(page)) === 'files' && (await closed(page)));
    await back(page, { files: true });
    check('   and when the person is back the folder is listed again', (await ev(page, () => window.__calls.opened.filter(x => x === 'fmList:/storage/emulated/0').length)) === 2
      && (await ev(page, () => getComputedStyle(document.getElementById('fmAccessHint')).display)) === 'none' && (await wasToast(page, /File access allowed/)));
  });

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 7) the Installer
  const REF = '/storage/emulated/0/Download/app.apk';
  await scenario({}, async page => {
    await ev(page, ([r, pkg]) => { window.__pkgs[r] = pkg; switchView('installer'); window.__inspectError = 'EACCES (Permission denied)'; onInstallFilePicked(r); }, [REF, PKG]);
    await page.waitForFunction(() => /EACCES/.test(document.getElementById('installPickHint').innerText));
    const s = await sheet(page);
    check('17. a package on storage that cannot be read for want of the access asks for it, saying so', s.shown && s.title === 'File access needed' && s.reason === 'To read this package from storage, this app needs All-files access.', s.reason);
    check('   the Installer says what failed', (await ev(page, () => document.getElementById('installPickHint').innerText)) === '⚠️ EACCES (Permission denied)');
    await ev(page, () => { window.__inspectError = ''; });
    await back(page, { files: true });
    await page.waitForFunction(() => /^Loaded/.test(document.getElementById('installPickHint').innerText));
    check('   once it is allowed the package is read again by itself, without picking it again', (await closed(page)) && (await ev(page, () => window.__calls.inspect.join(','))) === REF + ',' + REF
      && (await ev(page, () => document.getElementById('installInfoCard').style.display)) !== 'none');
  });
  await scenario({}, async page => {
    await ev(page, () => { switchView('installer'); window.__inspectError = 'EACCES (Permission denied)'; onInstallFilePicked('content://pick/9'); });
    await page.waitForFunction(() => /EACCES/.test(document.getElementById('installPickHint').innerText));
    await back(page, { files: true });
    await settle(page);
    check('18. a package picked through Android\'s own picker (content://) has nothing to do with this access: no sheet, no second read', (await closed(page)) && (await ev(page, () => window.__calls.inspect.length)) === 1);
  });
  await scenario({}, async page => {
    await ev(page, r => { switchView('installer'); window.__inspectError = 'Not a valid package'; onInstallFilePicked(r); }, REF);
    await page.waitForFunction(() => /Not a valid package/.test(document.getElementById('installPickHint').innerText));
    await back(page, { files: true });
    await settle(page);
    check('   an error that is not about permission asks for nothing and is not tried again', (await closed(page)) && (await ev(page, () => window.__calls.inspect.length)) === 1);
  });

  // the storage search
  await scenario({}, async page => {
    await ev(page, () => switchView('installer'));
    await page.waitForFunction(() => window.__calls.scan === 1);
    await ev(page, () => window.__scanFinish({ status: 'noaccess', files: [] }));
    check('19. the search the Installer starts by itself when it opens does not nag: a note with a "Grant access" button, no sheet', (await closed(page)) && /All-files access/.test(await ev(page, () => document.getElementById('apkScanStatus').innerText)) && (await page.locator('#apkScanStatus button').count()) === 1);
    await ev(page, () => document.querySelector('#apkScanStatus button').click());
    check('   the button goes to the Android screen and says the search starts again', (await perms(page)) === 'files' && (await wasToast(page, /search starts again/)) && (await closed(page)));
    await back(page, { files: true });
    await page.waitForFunction(() => window.__calls.scan === 2);
    check('   and when the person is back the search starts again by itself', (await wasToast(page, /File access allowed/)) && (await ev(page, () => window.__scanPending)) === true);
  });
  await scenario({}, async page => {
    await ev(page, () => switchView('installer'));
    await page.waitForFunction(() => window.__calls.scan === 1);
    await ev(page, () => window.__scanFinish({ status: 'ok', files: [] }));
    await page.click('#apkScanBtn');
    await page.waitForFunction(() => window.__calls.scan === 2);
    await ev(page, () => window.__scanFinish({ status: 'noaccess', files: [] }));
    const s = await sheet(page);
    check('20. a search the person started themselves, when it cannot read storage, asks for the access with a sheet', s.shown && s.title === 'File access needed' && /^To search storage for APK, APKS, APKM and XAPK files, this app needs All-files access/.test(s.reason), s.reason);
    await back(page, { files: true });
    await page.waitForFunction(() => window.__calls.scan === 3);
    check('   once it is allowed the sheet closes and the search starts again', (await closed(page)) && (await ev(page, () => window.__scanPending)) === true);
  });

  // ---------------------------------------------------------------------------------------------------------------------------------------------
  // 8) how the sheet looks
  for (const vp of [{ width: 360, height: 800 }, { width: 320, height: 640 }, { width: 412, height: 915 }]) {
    const tag = '[' + vp.width + 'x' + vp.height + '] ';
    for (const mode of ['intro', 'files']) {
      await scenario({ kv: {} }, async page => {
        await ev(page, m => { if (m === 'intro') openPermSheet('intro'); else openPermSheet('files', 'To browse files on this phone, this app needs All-files access (or a working mode: ADB, Shizuku or Root).'); }, mode);
        await page.waitForFunction(() => document.querySelector('#permModal .modal-sheet').getBoundingClientRect().bottom <= innerHeight + 0.5);   // slid in
        const g = await ev(page, () => {
          const r = e => e.getBoundingClientRect(), sh = document.querySelector('#permModal .modal-sheet'), sr = r(sh);
          const rows = Array.from(document.querySelectorAll('#permRows .pm-row')), btns = Array.from(document.querySelectorAll('#permRows .pm-row button'));
          const vis = id => getComputedStyle(document.getElementById(id)).display !== 'none';
          const sub = document.getElementById('permSubtitle');
          return { subWholeWords: getComputedStyle(sub).wordBreak !== 'break-all', inside: sr.left >= 0 && sr.right <= innerWidth && sr.top >= 0 && sr.bottom <= innerHeight + 0.5, scrolls: sh.scrollHeight > sh.clientHeight + 1, wide: sh.scrollWidth > sh.clientWidth + 1,
            rowsIn: rows.every(x => r(x).left >= sr.left - 0.5 && r(x).right <= sr.right + 0.5), textIn: Array.from(document.querySelectorAll('#permRows .pm-txt')).every(e => e.scrollWidth <= e.clientWidth + 1),
            allowMin: Math.round(Math.min(...btns.map(x => r(x).height))), allowW: Math.round(Math.min(...btns.map(x => r(x).width))), btnsIn: btns.every(x => r(x).right <= sr.right - 8),
            bottomMin: Math.round(Math.min(r(document.getElementById('permCloseBtn')).height, vis('permAllBtn') ? r(document.getElementById('permAllBtn')).height : 99)), rows: rows.length, h: Math.round(sr.height),
            closeW: Math.round(r(document.getElementById('permCloseBtn')).width), rowsW: Math.round(r(document.getElementById('permRows')).width) };
        });
        check(tag + mode + ': the sheet is inside the screen, its rows and buttons inside the sheet, no text cut off sideways', g.inside && !g.wide && g.rowsIn && g.textIn && g.btnsIn, JSON.stringify(g));
        check(tag + mode + ': the subtitle wraps between words, not in the middle of one', g.subWholeWords);
        check(tag + mode + ': the Allow buttons are at least 40 px tall and the bottom buttons at least 44', g.allowMin >= 40 && g.bottomMin >= 44, g.allowMin + ' / ' + g.bottomMin);
        check(tag + mode + (mode === 'files' ? ': alone, "Not now" takes the whole row' : ': "Not now" shares the row with "Allow all"'), mode === 'files' ? g.closeW >= g.rowsW - 2 : g.closeW < g.rowsW / 2, g.closeW + ' of ' + g.rowsW);
        if (vp.height >= 800) check(tag + mode + ': on a normal screen the sheet fits without scrolling', !g.scrolls, g.h);
        if (vp.width === 360 && vp.height === 800) await page.screenshot({ path: 'perm_' + mode + '.png' });
      }, vp);
    }
  }

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
