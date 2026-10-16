// v7.14.0: the app menu's buttons have fixed places, in four groups of three (Open, Control, Maintenance, Package; Uninstall last). An installed, an uninstalled-for-the-user, a disabled and a suspended app put every button in the same cell; Suspend / Unsuspend stays for an uninstalled app and follows its real flag; an uninstall, a reinstall or a suspend while the menu is open moves nothing; a button that does not apply is dimmed in its place and a tap says why.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    // the phone: what loadPackages reports is what the actions below leave behind, so the menu is redrawn from the "read back" state
    const apps = {
      'com.example.installed': { name: 'Installed App', isSystem: true },
      'com.example.gone': { name: 'Gone App', isSystem: true, isUninstalled: true, isFrozen: true },
      'com.example.gonesus': { name: 'Gone Suspended', isSystem: true, isUninstalled: true, isFrozen: true, isSuspended: true },
      'com.example.disabled': { name: 'Disabled App', isSystem: true, isFrozen: true },
      'com.example.suspended': { name: 'Suspended App', isSystem: false, isSuspended: true },
    };
    const st = { calls: [], refuseSuspendWhenGone: true }; window.__st = st;
    const run = (action, pkg) => {
      const a = apps[pkg];
      if (action === 'uninstall') { a.isUninstalled = true; a.isFrozen = true; a.isSuspended = false; }          // Android clears the user's flags on an uninstall for the user
      if (action === 'reinstall') { a.isUninstalled = false; a.isFrozen = false; }
      if (action === 'suspend' && !(a.isUninstalled && st.refuseSuspendWhenGone)) a.isSuspended = true;      // some Android versions refuse to suspend an app not installed for the user
      if (action === 'unsuspend') a.isSuspended = false;
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: 'dark', overrides: { dark: {}, light: {} } }); }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify(Object.entries(apps).map(([pkg, a]) => Object.assign({ pkg, isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false }, a))); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      executeAppAction(action, pkg) { st.calls.push(action + ':' + pkg); run(action, pkg); return '\u00011done'; },
      // the checked way, as MainActivity does it: run, then read the state back and report that
      appActionChecked(action, pkg, token) {
        st.calls.push('checked ' + action + ':' + pkg); run(action, pkg);
        const a = apps[pkg];
        const want = { suspend: a.isSuspended, unsuspend: !a.isSuspended, uninstall: a.isUninstalled, reinstall: !a.isUninstalled }[action];
        const label = { suspend: want ? 'Suspended' : 'Not suspended', unsuspend: want ? 'Not suspended' : 'Still suspended', uninstall: 'Uninstalled', reinstall: 'Installed' }[action];
        setTimeout(() => window.onAppActionChecked(token, { pkg, action, success: !!want, label, verified: true, output: 'Checked afterwards: ' + label.toLowerCase() + (want ? '.' : ', although the command reported success.') }), 30);
        return 'started';
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // every cell of the grid: the visible button in it, its place inside the grid, and whether it is dimmed
  const cells = () => ev(() => {
    const g = document.getElementById('sheetActionGrid').getBoundingClientRect();
    return [...document.querySelectorAll('#sheetActionGrid .sheet-btn')].filter(x => getComputedStyle(x).display !== 'none').map(x => {
      const r = x.getBoundingClientRect();
      return { id: x.id, t: x.innerText.trim(), x: Math.round(r.left - g.left), y: Math.round(r.top - g.top), w: Math.round(r.width), h: Math.round(r.height), off: x.getAttribute('aria-disabled') === 'true', why: x.title || '' };
    });
  });
  const absTop = () => ev(() => Math.round(document.getElementById('sheetBtnExtract').getBoundingClientRect().top));
  const open = async pkg => { await ev(p => { closeInspector(); openInspector(p); document.querySelector('#inspectorModal .modal-sheet').scrollTop = 0; }, pkg); await page.waitForTimeout(250); };
  const boxes = c => c.map(e => `${e.x},${e.y},${e.w}x${e.h}`).join(' ');
  const names = c => c.map(e => e.t + (e.off ? '(dimmed)' : '')).join(', ');

  const heads = await ev(() => [...document.querySelectorAll('#sheetActionGrid .sheet-action-group-label')].map(h => h.innerText.trim()));
  check('1. four groups, each under a heading', heads.join('|') === 'OPEN|CONTROL|MAINTENANCE|PACKAGE', heads.join('|'));

  // 2. the same twelve cells for four kinds of app
  const seen = {};
  for (const pkg of ['com.example.installed', 'com.example.gone', 'com.example.disabled', 'com.example.suspended', 'com.example.gonesus']) {
    await open(pkg);
    const c = await cells();
    seen[pkg] = c;
    console.log(`     ${pkg}: ${names(c)}`);
  }
  const ref = boxes(seen['com.example.installed']);
  check('2. twelve buttons, three to a row, in four rows', seen['com.example.installed'].length === 12 && new Set(seen['com.example.installed'].map(e => e.y)).size === 4 && new Set(seen['com.example.installed'].map(e => e.x)).size === 3, ref);
  for (const pkg of Object.keys(seen)) check(`   ${pkg}: every button in the same cell as for the installed app`, boxes(seen[pkg]) === ref, boxes(seen[pkg]));
  const order = c => c.map(e => e.t).join(',');
  check('   the installed app: Launch, App Info, Logs | Force Stop, Freeze, Suspend | Clear Data, Optimize, Standby | Extract APK, Rem Updates, Uninstall', order(seen['com.example.installed']) === 'Launch,App Info,Logs,Force Stop,Freeze,Suspend,Clear Data,Optimize,Standby,Extract APK,Rem Updates,Uninstall', order(seen['com.example.installed']));
  check('   the uninstalled app: Reinstall in Uninstall\'s cell, Suspend kept in its cell (not suspended)', order(seen['com.example.gone']) === 'Launch,App Info,Logs,Force Stop,Enable,Suspend,Clear Data,Optimize,Standby,Extract APK,Rem Updates,Reinstall', order(seen['com.example.gone']));
  check('   the uninstalled app that is suspended: Unsuspend in that cell', order(seen['com.example.gonesus']).split(',')[5] === 'Unsuspend', order(seen['com.example.gonesus']));
  check('   the disabled app: Enable in Freeze\'s cell', order(seen['com.example.disabled']).split(',')[4] === 'Enable');
  check('   the suspended app: Unsuspend in Suspend\'s cell', order(seen['com.example.suspended']).split(',')[5] === 'Unsuspend');
  const dim = pkg => seen[pkg].filter(e => e.off).map(e => e.t + ' (' + e.why + ')').join(', ');
  check('3. a button that does not apply stays, dimmed, with its reason: Launch and Force Stop of an uninstalled app', dim('com.example.gone') === 'Launch (Not installed for this user), Force Stop (Not installed for this user)', dim('com.example.gone'));
  check('   Rem Updates of a user app', dim('com.example.suspended') === 'Rem Updates (Only a system app has updates to remove)', dim('com.example.suspended'));
  check('   nothing is dimmed for an installed system app', dim('com.example.installed') === '' && dim('com.example.disabled') === '', dim('com.example.installed'));

  // a tap on a dimmed button runs nothing and says why
  await open('com.example.gone');
  await ev(() => { window.__st.calls.length = 0; });
  await page.click('#sheetBtnLaunch', { force: true }); await page.waitForTimeout(150);
  const toast = await page.locator('#toastMsg').innerText();
  check('   a tap on the dimmed Launch runs nothing and says why', toast === 'Not installed for this user' && (await ev(() => window.__st.calls.length)) === 0, toast + ' ' + JSON.stringify(await ev(() => window.__st.calls)));

  // 4. actions while the menu stays open: nothing moves
  const settle = async () => { await page.waitForTimeout(700); await ev(() => { if (document.getElementById('commandResultsModal') && document.getElementById('commandResultsModal').classList.contains('show')) closeCommandResultsModal(); }); await page.waitForTimeout(100); };
  await open('com.example.installed');
  const c0 = await cells(), t0 = await absTop();
  await page.click('#sheetBtnUninstall'); await settle();
  const c1 = await cells(), t1 = await absTop();
  check('4. Uninstall while the menu is open: Reinstall comes in its cell, nothing else moves', boxes(c1) === boxes(c0) && t1 === t0 && c1[11].t === 'Reinstall' && c1[5].t === 'Suspend' && c1[4].t === 'Enable', names(c1) + ' | top ' + t0 + '→' + t1);
  check('   Launch and Force Stop are dimmed now, in their places', c1[0].off && c1[3].off && !c1[5].off, names(c1));
  await page.click('#sheetBtnReinstall'); await settle();
  const c2 = await cells(), t2 = await absTop();
  check('   Reinstall: Uninstall is back in the same cell, nothing moves', boxes(c2) === boxes(c0) && t2 === t0 && c2[11].t === 'Uninstall' && !c2[0].off && !c2[3].off, names(c2));
  await page.click('#sheetBtnSuspend'); await settle();
  const c3 = await cells();
  check('   Suspend: Unsuspend in the same cell, nothing moves', boxes(c3) === boxes(c0) && c3[5].t === 'Unsuspend', names(c3));
  await page.click('#sheetBtnUnsuspend'); await settle();

  // 5. Suspend / Unsuspend of an app uninstalled for the user: run, read back, and the label follows what the phone says
  await open('com.example.gonesus');
  const g0 = await cells();
  await page.click('#sheetBtnUnsuspend'); await page.waitForTimeout(700);
  const g1 = await cells();
  check('5. Unsuspend of an uninstalled, suspended app runs the checked action and the cell then says Suspend', boxes(g1) === boxes(g0) && g1[5].t === 'Suspend' && (await ev(() => window.__st.calls.includes('checked unsuspend:com.example.gonesus'))), names(g1));
  await page.click('#sheetBtnSuspend'); await page.waitForTimeout(700);
  const refused = await ev(() => ({ open: document.getElementById('commandResultsModal').classList.contains('show'), text: document.getElementById('commandResultsModal').innerText.replace(/\s+/g, ' ') }));
  check('   where Android refuses Suspend for it, the check afterwards says so and the button is still there, still Suspend', refused.open && /not suspended/i.test(refused.text) && (await cells())[5].t === 'Suspend', refused.text.slice(0, 200));
  await ev(() => closeCommandResultsModal());
  await ev(() => { window.__st.refuseSuspendWhenGone = false; });
  await page.click('#sheetBtnSuspend'); await page.waitForTimeout(700);
  check('   where Android allows it, the cell says Unsuspend', (await cells())[5].t === 'Unsuspend' && boxes(await cells()) === boxes(g0));

  check('no page errors', errors.length === 0, JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
