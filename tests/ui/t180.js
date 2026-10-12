// v7.12.11 the install question on every privileged path, and no privileged authorizer in Read-Only mode: the Installer's Authorizer follows the working mode until the
// person picks one (it used to stop following after the first visit), in Read-Only mode ADB, Shizuku and Root are not offered (the system installer is, with a sentence that
// says why) even when a backend is still connected, and the Install button sends "No privilege"; a package handed over by the file manager needs a privileged working mode;
// "Update All" asks once, in a dialog of the app (not of the page), and starts only the apps the app approved; an update waiting for its OK shows as such.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => { bad++; errors.push(e.message); console.log('FAIL page error:', e.message); });
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.type() + ':' + d.message().slice(0, 60)); d.dismiss(); });
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' } });
  await page.addInitScript(() => {
    // Read-Only mode with ADB and Shizuku still connected underneath: what the working-mode probe reports when the person chose Read-Only
    window.__am = 'unprivileged';
    const B = window.AndroidBridge;
    B.getWorkingMode = function () {
      const m = window.__am, priv = m !== 'unprivileged';
      return JSON.stringify({ activeMode: m, configuredMode: m === 'unprivileged' ? 'unprivileged' : 'auto', modeAvailable: priv, isPrivileged: priv,
        adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: true });
    };
    window.__pkgs['content://pick/1'] = { type: 'apk', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 9e6,
      splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 9e6, isBase: true, split: '' }], signed: true, installed: false };
    // the Updater: three updates to install, and a bridge that asks once for all of them (answered when the script says so)
    window.__upd = { approve: [], installs: [], ended: 0 };
    const mk = (pkg, name) => ({ pkg, name, installedVersion: '1.0', availableVersion: '2.0', source: 'github', origin: 'yours', downloadUrl: 'https://github.com/x/' + pkg + '.apk', page: 'https://github.com/x/' + pkg });
    const updates = [mk('com.a', 'Alpha'), mk('com.b', 'Bravo'), mk('com.c', 'Charlie'), mk('com.d', 'Delta')];
    B.getUpdateState = () => JSON.stringify({ running: false, checkedAt: 0, updates: [] });
    B.checkForUpdates = () => { setTimeout(() => window.onUpdatesChecked(JSON.stringify({ updates, untracked: [], external: [], checked: 4, errors: 0, selfStatus: 'no_releases', checkedAt: Date.now() })), 30); };
    B.approveUpdateAll = json => { window.__upd.approve.push(JSON.parse(json)); };
    B.installUpdate = pkg => {
      window.__upd.installs.push(pkg);
      const steps = window.__upd.confirmFirst === pkg ? [['confirming', 100, 'Waiting for your OK to update ' + pkg + '...']] : [['downloading', 50, '2 MB'], ['installing', 100, ''], ['done', 100, 'Updated']];
      steps.forEach((s, i) => setTimeout(() => window.onUpdateProgress(JSON.stringify({ pkg, stage: s[0], percent: s[1], message: s[2] })), 40 * (i + 1)));
    };
    B.endUpdateAll = () => { window.__upd.ended++; };
    B.hasGithubToken = () => false;
  });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.toggleInstallCombo === 'function');
  await page.waitForTimeout(300);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const setMode = async m => { await ev(x => { window.__am = x; checkAllWorkingModes(false); }, m); await sleep(30); };
  const auth = () => ev(() => {
    const sel = document.getElementById('optAuthorizer'), opt = v => sel.querySelector('option[value="' + v + '"]'), note = document.getElementById('installStandardNote');
    const priv = ['adb', 'shizuku', 'root'];
    return { value: sel.value, hidden: priv.map(v => opt(v).hidden), disabled: priv.map(v => opt(v).disabled), noneUsable: !opt('none').disabled && !opt('none').hidden,
      noteShown: getComputedStyle(note).display !== 'none', note: note.innerText.trim(),
      powerOff: [...document.querySelectorAll('#installOptionsCard [data-priv="1"]')].every(e => e.disabled), preview: document.getElementById('installPreview').innerText.slice(0, 40) };
  });
  const allTrue = a => a.length === 3 && a.every(x => x === true), allFalse = a => a.length === 3 && a.every(x => x === false);

  // 1) Read-Only mode (ADB and Shizuku still connected): the system installer is the only authorizer, and a sentence says why
  await ev(() => switchView('installer'));
  await ev(() => pickInstallFile());
  await page.waitForFunction(() => /^Loaded/.test(document.getElementById('installPickHint').innerText));
  let s = await auth();
  check('1. Read-Only mode: the Authorizer is the system installer (No privilege)', s.value === 'none' && s.noneUsable, JSON.stringify(s.value));
  check('   ADB, Shizuku and Root are not offered (hidden and disabled)', allTrue(s.hidden) && allTrue(s.disabled), JSON.stringify([s.hidden, s.disabled]));
  check('   a plain sentence says why', s.noteShown && /^Read-Only mode: the system installer asks you before anything is installed, so ADB, Shizuku and Root are not offered here\.$/.test(s.note), s.note);
  check('   the privileged power options are off', s.powerOff === true);
  check('   the preview says the system installer will ask', /^No privilege: a system install dialog/.test(s.preview), s.preview);
  await ev(() => runInstall()); await sleep(100);
  let sent = await ev(() => window.__calls.install.slice());
  check('   Install sends "No privilege"', sent.length === 1 && sent[0].authorizer === 'none' && sent[0].fromFiles === false, JSON.stringify(sent.map(x => x.authorizer)));
  await ev(() => { window.onInstallResult(JSON.stringify({ ok: true, method: 'No privilege', output: 'Success', pkg: 'com.example.app' })); closeCommandResultsModal(); });

  // 2) A working mode comes on: the Authorizer follows it while nothing was picked (it used to stay where the first visit left it)
  await setMode('adb_tcp');
  s = await auth();
  check('2. ADB comes on: the Authorizer becomes ADB, all four are offered again, the sentence is gone', s.value === 'adb' && allFalse(s.hidden) && allFalse(s.disabled) && !s.noteShown && !s.powerOff, JSON.stringify([s.value, s.noteShown, s.powerOff]));
  await setMode('shizuku');
  check('   the mode changes to Shizuku: the Authorizer follows (not picked by hand)', (await auth()).value === 'shizuku');
  await setMode('root');
  check('   to Root: it follows', (await auth()).value === 'root');
  await setMode('adb_wireless');
  check('   to Wireless Debugging: ADB', (await auth()).value === 'adb');
  // the owner's case: it was ADB, then the app went to Read-Only mode while the page stayed open
  await setMode('unprivileged');
  s = await auth();
  check('   Read-Only again: no stale ADB is left, the system installer is chosen and the sentence is back', s.value === 'none' && allTrue(s.hidden) && s.noteShown && s.powerOff, JSON.stringify([s.value, s.noteShown]));
  await ev(() => { window.__calls.install.length = 0; runInstall(); }); await sleep(100);
  sent = await ev(() => window.__calls.install.slice());
  check('   and an install started now sends "No privilege", not ADB', sent.length === 1 && sent[0].authorizer === 'none', JSON.stringify(sent.map(x => x.authorizer)));
  await ev(() => { window.onInstallResult(JSON.stringify({ ok: true, method: 'No privilege', output: 'Success', pkg: 'com.example.app' })); closeCommandResultsModal(); });

  // 3) A pick made by hand is kept across mode changes, and comes back after Read-Only
  await setMode('adb_tcp');
  await page.selectOption('#optAuthorizer', 'root'); await sleep(30);
  await setMode('shizuku');
  check('3. picked Root by hand: a change of mode to Shizuku does not override it', (await auth()).value === 'root');
  await setMode('unprivileged');
  s = await auth();
  check('   Read-Only: Root is not honoured (the system installer instead)', s.value === 'none' && allTrue(s.hidden));
  await setMode('adb_tcp');
  check('   the mode is back: the person\'s Root pick comes back with it', (await auth()).value === 'root');
  await page.selectOption('#optAuthorizer', 'none'); await sleep(30);
  await setMode('shizuku');
  check('   picking the system installer by hand is kept in a privileged mode too', (await auth()).value === 'none');

  // 4) Opening the tab again does not undo anything either way
  await ev(() => { switchView('apps'); switchView('installer'); }); await sleep(60);
  check('4. leaving the Installer and coming back keeps the pick', (await auth()).value === 'none');
  await setMode('unprivileged');
  await ev(() => { switchView('apps'); switchView('installer'); }); await sleep(60);
  s = await auth();
  check('   in Read-Only mode the tab still offers only the system installer', s.value === 'none' && allTrue(s.hidden));

  // 5) A package from the file manager needs a privileged working mode: a backend that is merely connected does not count in Read-Only mode
  dialogs.length = 0;
  const files = await ev(() => { const a = installFilesReady(); return { ready: a, backend: privilegedInstallBackend() }; });
  check('5. Read-Only mode with ADB and Shizuku still connected: the file manager has no backend to install with', files.ready === false && files.backend === null, JSON.stringify(files));
  check('   the person is told (and offered Working Modes) instead', dialogs.length === 1 && /^confirm:Installing from File Manager runs through/.test(dialogs[0]), JSON.stringify(dialogs));
  await setMode('shizuku');
  const files2 = await ev(() => ({ ready: installFilesReady(), backend: privilegedInstallBackend() }));
  check('   with Shizuku as the working mode it does', files2.ready === true && files2.backend === 'shizuku', JSON.stringify(files2));

  // 5b) A package from the file manager is open when the app goes to Read-Only mode: Install does not cross the bridge, it says what is needed
  await setMode('shizuku');
  await ev(() => { installFromFiles = true; applyFilesInstallMode(); });
  await setMode('unprivileged');
  await ev(() => { window.__calls.install.length = 0; runInstall(); }); await sleep(80);
  check('5b. Files install open, then Read-Only mode: Install sends nothing to the app and says a backend is needed', (await ev(() => window.__calls.install.length)) === 0 && /^Installing from the File Manager needs ADB, Wireless Debugging, Shizuku or Root\. Set one up in Working Modes, then try again\.$/.test(await page.locator('#toastMsg').innerText()));
  const fstate = () => ev(() => ({ btn: document.getElementById('installRunBtn').disabled, value: document.getElementById('optAuthorizer').value, note: getComputedStyle(document.getElementById('installFilesNote')).display !== 'none' ? document.getElementById('installFilesNote').innerText : '' }));
  const fs0 = await fstate();
  check('   the stale backend is cleared, Install is off and a sentence names what is needed', fs0.btn === true && fs0.value === 'none' && /^Installing from the File Manager needs ADB, Wireless Debugging, Shizuku or Root\./.test(fs0.note), JSON.stringify(fs0));
  await setMode('shizuku');
  const fs1 = await fstate();
  check('   a working mode is back: Install is on again and the backend is Shizuku', fs1.btn === false && fs1.value === 'shizuku' && /Shizuku/.test(fs1.note), JSON.stringify(fs1));
  await ev(() => { installFromFiles = false; applyFilesInstallMode(); });
  check('   a normal install leaves Install on', (await fstate()).btn === false);

  // 6) "Update All" asks once, in the app's own dialog
  await setMode('adb_tcp');
  await page.click('#instSwitchUpdater');
  await page.waitForFunction(() => /Update All \(4\)/.test(document.getElementById('updAllBtn').innerText), null, { timeout: 8000 });
  dialogs.length = 0;
  await ev(() => { updList.forEach(u => { updProgress[u.pkg] = { stage: 'queued', percent: 0, message: '' }; }); updateAllUI(); });
  check('   apps that are already queued are not asked about again (no second question for a queue that is running)', (await ev(() => window.__upd.approve.length)) === 0);
  await ev(() => { updList.forEach(u => { delete updProgress[u.pkg]; }); renderUpdates(); });
  await ev(() => { updProgress['com.c'] = { stage: 'confirming', percent: 100, message: '' }; updProgress['com.d'] = { stage: 'queued', percent: 0, message: '' }; renderUpdates(); });
  const countText = await ev(() => document.getElementById('updAllBtn').textContent.trim());
  check('   the Update All button counts only what Update All would start (not an app waiting for OK, nor a queued one)', countText === 'Update All (2)', countText);
  await ev(() => { updProgress['com.a'] = { stage: 'done', percent: 100, message: '' }; updProgress['com.b'] = { stage: 'downloading', percent: 5, message: '' }; renderUpdates(); });
  check('   done and downloading apps are not counted either (nothing left to start: the button is hidden)', (await ev(() => document.getElementById('updAllBtn').textContent.trim())) === 'Update All (0)' && (await ev(() => getComputedStyle(document.getElementById('updAllBtn')).display)) === 'none');
  await ev(() => { updList.forEach(u => { delete updProgress[u.pkg]; }); renderUpdates(); });
  const endedAtStart = await ev(() => window.__upd.ended);
  await page.click('#updAllBtn'); await sleep(60);
  let u = await ev(() => JSON.parse(JSON.stringify(window.__upd)));
  check('6. Update All asks the app, once, with the packages (no dialog of the page)', u.approve.length === 1 && JSON.stringify(u.approve[0]) === '["com.a","com.b","com.c","com.d"]' && dialogs.length === 0, JSON.stringify([u.approve, dialogs]));
  check('   nothing is downloaded or installed before the answer', u.installs.length === 0);
  check('   the button waits for the answer (it cannot be pressed again)', await ev(() => document.getElementById('updAllBtn').disabled === true));
  await ev(() => updateAllUI()); await sleep(30);
  check('   pressing it again (or calling it) asks nothing more', (await ev(() => window.__upd.approve.length)) === 1);
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: true, pkgs: ['com.a', 'com.b'] })));
  await page.waitForFunction(() => window.__upd.installs.length === 2 && [...document.querySelectorAll('#updContainer .upd-row')].filter(r => /Alpha|Bravo/.test(r.innerText)).every(r => /Updated/.test(r.innerText)), null, { timeout: 8000 });
  u = await ev(() => JSON.parse(JSON.stringify(window.__upd)));
  check('   the answer starts only the apps the app approved, one after the other', JSON.stringify(u.installs) === '["com.a","com.b"]', JSON.stringify(u.installs));
  check('   the button is usable again and the other two are untouched', await ev(() => document.getElementById('updAllBtn').disabled === false && /Update All \(2\)/.test(document.getElementById('updAllBtn').innerText)));

  // 7) A No (or an error) starts nothing and says so
  await page.click('#updAllBtn'); await sleep(60);
  check('7. asked again for the two that are left', (await ev(() => window.__upd.approve.length)) === 2 && (await ev(() => JSON.stringify(window.__upd.approve[1]))) === '["com.c","com.d"]');
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: false, pkgs: [] }))); await sleep(60);
  check('   No: nothing is installed, a toast says so, the button is back', (await ev(() => window.__upd.installs.length)) === 2 && /Updates cancelled/.test(await page.locator('#toastMsg').innerText()) && (await ev(() => document.getElementById('updAllBtn').disabled)) === false);
  await page.click('#updAllBtn'); await sleep(60);
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: false, pkgs: [], error: 'installing updates needs ADB, Shizuku or Root. Set up a working mode first.' }))); await sleep(60);
  check('   an error from the app is shown as it is', /needs ADB, Shizuku or Root/.test(await page.locator('#toastMsg').innerText()) && (await ev(() => window.__upd.installs.length)) === 2);

  // 7b) The queue is over / the working mode goes read-only: the app is told, so approvals that were not used are dropped
  check('7b. the app is told when the queue is over (every approval left is dropped)', (await ev(() => window.__upd.ended)) > endedAtStart);
  const endedBefore = await ev(() => window.__upd.ended);
  await ev(() => { updQueue = ['com.c', 'com.d']; });
  await setMode('unprivileged');
  check('   the working mode goes read-only: the page\'s queue is emptied and the app is told', (await ev(() => updQueue.length)) === 0 && (await ev(() => window.__upd.ended)) > endedBefore);
  await setMode('adb_tcp');

  // 8) A single Update goes straight to the app, which asks after the download; the row shows it is waiting for the person
  await ev(() => { window.__upd.confirmFirst = 'com.c'; });
  const askedBefore = await ev(() => window.__upd.approve.length);
  await page.click('.upd-row:has-text("Charlie") >> button:has-text("Update")'); await sleep(120);
  const row = await page.locator('.upd-row:has-text("Charlie") .upd-head button').innerText();
  check('8. one Update asks nothing of the page: the app asks after the download, and the row says it is waiting', row.trim() === 'Waiting for OK' && (await ev(() => window.__upd.approve.length)) === askedBefore && (await ev(() => window.__upd.installs.slice(-1)[0])) === 'com.c', row);
  check('   the waiting button cannot be pressed', await page.locator('.upd-row:has-text("Charlie") .upd-head button').isDisabled());
  await ev(() => window.onUpdatesChecked(JSON.stringify({ updates: updList, untracked: [], external: [], checked: 4, errors: 0, selfStatus: 'no_releases', checkedAt: Date.now() }))); await sleep(60);
  const rowAfter = await page.locator('.upd-row:has-text("Charlie") .upd-head button').innerText();
  check('   an update check that ends while the question is open keeps the row waiting (Update stays off)', rowAfter.trim() === 'Waiting for OK' && await page.locator('.upd-row:has-text("Charlie") .upd-head button').isDisabled(), rowAfter);
  check('   Update All does not count the app that is waiting', await ev(() => !document.getElementById('updAllBtn') || document.getElementById('updAllBtn').style.display === 'none' || !/Charlie/.test(JSON.stringify(updList.filter(x => !(updProgress[x.pkg] && ['done', 'downloading', 'installing', 'confirming'].includes(updProgress[x.pkg].stage))).map(x => x.name)))));
  await ev(() => window.onUpdateProgress(JSON.stringify({ pkg: 'com.c', stage: 'error', percent: 0, message: 'Update cancelled. Nothing was installed.' }))); await sleep(40);
  const rowErr = await page.locator('.upd-row:has-text("Charlie")').innerText();
  check('   a No from the app ends as a failed row that can be tried again', /Update cancelled\. Nothing was installed\./.test(rowErr) && /Retry/.test(rowErr), rowErr.replace(/\s+/g, ' ').slice(0, 120));

  check('errors', errors.length === 0, JSON.stringify(errors));
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
