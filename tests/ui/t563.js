// v7.15.1 A3: enabling/disabling a component is Super User (Root) only, for every app (user and system alike). Off root the
// ENABLE/DISABLE control is locked (lock icon, "Needs Super User (Root)") while LAUNCH/STOP keep the ordinary working-mode lock,
// and a tap funnels through promptRootThenOpen instead of firing a doomed pm command. On root the control works: a user app's
// component toggles with no extra prompt; disabling a SYSTEM app's component asks for confirmation first (enabling does not).
// The bulk activity toggle (Activity Launcher) is Root-gated the same way. T-054: without su the funnel offers the Root Features tab, and a yes
// closes the sheet the tap came from (the app sheet, the Activity Launcher) so the tab is on screen; a no leaves the sheet open. T-055: with su on
// the phone the funnel switches to Root, and the rows are drawn unlocked then even when the retried toggle stops at a Cancel. T-051: the Help
// Guide (built assets/guide.js), FULL-GUIDE.md and the device checklist describe this control as it is: unlocked in Root mode and when Shizuku
// runs as the system user (UID 1000) or root (UID 0), padlocked with "Needs Super User (Root)" for the ADB shell user (UID 2000) and Read-Only,
// nothing sent while locked, the Root way on a tap, the system-app confirm, each quoted as the page shows it; no "runs, says Failed" off Root.
// Deterministic ok/FAIL lines and a summary.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { chromium, PAGE, REPO } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let dialogs = [], answer = true;
  page.on('dialog', d => { dialogs.push(d.type() + ': ' + d.message()); if (answer) d.accept(); else d.dismiss(); });
  await page.addInitScript(() => {
    window.__kv = {};
    window.__calls = { toggle: [], selectWorkingMode: [] };
    window.__wm = { state: 'adb' };
    const WM = {
      adb:  { activeMode: 'adb_tcp', configuredMode: 'auto', modeAvailable: true, isPrivileged: true, rootAvailable: false, rootWorkingModeActive: false },
      // the same with su on the phone: the funnel switches to Root by itself, and su grants it (selectWorkingMode below)
      adbSu: { activeMode: 'adb_tcp', configuredMode: 'auto', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootWorkingModeActive: false },
      root: { activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: true },
      none: { activeMode: 'unprivileged', configuredMode: 'auto', modeAvailable: false, isPrivileged: false, rootAvailable: false, rootWorkingModeActive: false },
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      hasAllFilesAccess() { return true; }, saveStore() { return true; }, loadStore() { return ''; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; }, saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sys', name: 'Sys App', isSystem: true }, { pkg: 'com.usr', name: 'User App', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify(Object.assign({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {} }, WM[window.__wm.state])); },
      selectWorkingMode(mode) { window.__calls.selectWorkingMode.push(mode); window.__wm.state = mode === 'root' ? 'root' : 'none'; return JSON.stringify({ ok: mode === 'root', mode, message: 'm' }); },
      getAppDetails() {
        return JSON.stringify({
          permissions: [], appopsRaw: '',
          activityInfo: [{ name: 'com.a.Main', exported: true, enabled: true, permission: '' }, { name: 'com.a.Hidden', exported: false, enabled: true, permission: '' }],
          receiverInfo: [{ name: 'com.a.BootRcv', exported: false, enabled: true, permission: '' }],
          serviceInfo: [{ name: 'com.a.Svc', exported: true, enabled: true, permission: '' }],
          providerInfo: [{ name: 'com.a.Provider', exported: true, enabled: true, permission: '', authority: 'com.a.files' }],
        });
      },
      setComponentEnabled(pkg, comp, enable) { window.__calls.toggle.push(comp + ':' + enable); return JSON.stringify({ ok: true, output: 'done' }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const setMode = async state => { await ev(s => { window.__wm.state = s; checkAllWorkingModes(false); }, state); await sleep(40); };
  const compBtns = () => ev(() => [...document.querySelectorAll('#compsContainer .perm-toggle-btn')].map(x => ({
    comp: x.getAttribute('data-comp'), text: x.innerText.trim(), locked: x.classList.contains('locked'), lock: x.innerText.indexOf('🔒') >= 0, title: x.getAttribute('title') || '' })));
  const openComps = async pkg => { await ev(p => openInspector(p), pkg); await sleep(120); await page.click('.sheet-tab-pill[data-tab="comps"]'); await sleep(120); };
  const toggleBtn = btns => btns.find(x => /ENABLE|DISABLE/.test(x.text));
  const stopBtn = btns => btns.find(x => /STOP/.test(x.text) && x.comp === 'com.a.Svc');

  // ---- 1. Off root (ADB), a user app: the toggle is Root-locked, STOP is not; a tap funnels and runs no pm
  await setMode('adb');
  await openComps('com.usr');
  let btns = await compBtns();
  const tog1 = toggleBtn(btns), stop1 = stopBtn(btns);
  check('1. off root the ENABLE/DISABLE control is locked, with the lock icon and the "Needs Super User (Root)" reason', !!tog1 && tog1.locked && tog1.lock && tog1.title === 'Needs Super User (Root)', JSON.stringify(tog1));
  check('   LAUNCH/STOP keep the ordinary working-mode lock (not root): STOP is not locked while privileged', !!stop1 && !stop1.locked && !stop1.lock, JSON.stringify(stop1));
  dialogs = []; answer = false; await ev(() => { window.__calls.toggle = []; });
  await ev(() => toggleComponent('com.a.BootRcv', false)); await sleep(60);
  check('   a tap off root runs no pm command (setComponentEnabled not called) and funnels through the root prompt', (await ev(() => window.__calls.toggle.length)) === 0 && dialogs.length === 1 && /needs Super User/.test(dialogs[0]), JSON.stringify([await ev(() => window.__calls.toggle), dialogs]));

  // ---- 2. On root, a user app: the control is unlocked and toggles with no extra prompt
  await setMode('root');
  await ev(() => renderComponentTabs()); await sleep(40);
  btns = await compBtns();
  const tog2 = toggleBtn(btns);
  check('2. on root the control is unlocked (no lock icon, no Root reason)', !!tog2 && !tog2.locked && !tog2.lock && !tog2.title, JSON.stringify(tog2));
  dialogs = []; answer = true; await ev(() => { window.__calls.toggle = []; });
  await ev(() => toggleComponent('com.a.BootRcv', false)); await sleep(60);
  check('   a user app component toggles straight away, no confirmation dialog', (await ev(() => window.__calls.toggle.join())) === 'com.a.BootRcv:false' && dialogs.length === 0, JSON.stringify([await ev(() => window.__calls.toggle), dialogs]));

  // ---- 3. On root, a SYSTEM app: disabling a component asks to confirm first; a yes goes through, a no does not
  await openComps('com.sys');
  dialogs = []; answer = true; await ev(() => { window.__calls.toggle = []; });
  await ev(() => toggleComponent('com.a.Svc', false)); await sleep(60);
  check('3. disabling a system app component asks to confirm, naming the component and the system app and the risk', dialogs.length === 1 && /^confirm: Disable Svc of the system app com\.sys\? This can break the app or the system UI\./.test(dialogs[0]), JSON.stringify(dialogs));
  const sysConfirm = (dialogs[0] || '').replace(/^confirm: /, '');
  check('   a yes carries the toggle through', (await ev(() => window.__calls.toggle.join())) === 'com.a.Svc:false', JSON.stringify(await ev(() => window.__calls.toggle)));
  dialogs = []; answer = false; await ev(() => { window.__calls.toggle = []; });
  await ev(() => toggleComponent('com.a.Svc', false)); await sleep(60);
  check('   a no leaves it alone (nothing run)', (await ev(() => window.__calls.toggle.length)) === 0 && dialogs.length === 1, JSON.stringify([await ev(() => window.__calls.toggle), dialogs]));

  // ---- 4. On root, a SYSTEM app: ENABLING a component does not ask (only disabling is the risky one)
  dialogs = []; answer = true; await ev(() => { window.__calls.toggle = []; });
  await ev(() => toggleComponent('com.a.Svc', true)); await sleep(60);
  check('4. enabling a system app component does not prompt (only disabling warns)', (await ev(() => window.__calls.toggle.join())) === 'com.a.Svc:true' && dialogs.length === 0, JSON.stringify([await ev(() => window.__calls.toggle), dialogs]));

  // ---- 5. The bulk activity toggle (Activity Launcher) is Root-gated too
  await setMode('adb');
  await openComps('com.usr');
  await ev(() => { alsSel.clear(); alsSel.add('com.a.Main'); });
  dialogs = []; answer = false; await ev(() => { window.__calls.toggle = []; });
  await ev(() => alsBulkComp(false)); await sleep(60);
  check('5. the bulk toggle off root runs no pm command and funnels through the root prompt', (await ev(() => window.__calls.toggle.length)) === 0 && dialogs.length === 1 && /needs Super User/.test(dialogs[0]), JSON.stringify([await ev(() => window.__calls.toggle), dialogs]));

  // ---- 6. T-054: off root without su, a yes to "Open Root Features ...?" closes the sheet the tap came from (the app sheet or the Activity Launcher)
  // before it opens the tab, so Root Features is on screen and not behind the sheet; a no leaves the sheet open
  const sheets = () => ev(() => ['inspectorModal', 'appListSheet'].filter(id => document.getElementById(id).classList.contains('show')));
  const onTop = () => ev(() => { const el = document.elementFromPoint(innerWidth / 2, innerHeight / 2); const m = el && el.closest('.modal-overlay'); return m ? m.id : 'page'; });
  const afterOffer = async () => ({ view: await ev(() => currentViewName()), sheets: await sheets(), top: await onTop(), pm: await ev(() => window.__calls.toggle.length), dialogs: dialogs.slice() });
  await ev(() => { closeInspector(); switchView('apps'); }); await sleep(40);
  await openComps('com.usr');
  dialogs = []; answer = true; await ev(() => { window.__calls.toggle = []; });
  await page.click('#compsContainer .perm-toggle-btn[data-comp="com.a.BootRcv"]'); await sleep(80);
  let o = await afterOffer();
  check('6. T-054 the app sheet: a tap on the locked DISABLE, OK on the offer: the app sheet is closed and Root Features is the view on screen, no pm command', o.view === 'rootfeatures' && o.sheets.length === 0 && o.top === 'page' && o.pm === 0 && o.dialogs.length === 1 && /Open Root Features/.test(o.dialogs[0]), JSON.stringify(o));
  const rootOffer = (o.dialogs[0] || '').replace(/^confirm: /, '').replace(/\s+/g, ' ');
  await ev(() => switchView('apps')); await sleep(40);
  await openComps('com.usr');
  dialogs = []; answer = false;
  await page.click('#compsContainer .perm-toggle-btn[data-comp="com.a.BootRcv"]'); await sleep(80);
  o = await afterOffer();
  check('   Cancel on the offer leaves the app sheet open where it was and opens nothing', o.view === 'apps' && o.sheets.join() === 'inspectorModal' && o.dialogs.length === 1, JSON.stringify(o));
  await ev(() => closeInspector()); await sleep(40);
  await ev(() => { openAppListSheet('acts', 'com.usr'); alsSel.clear(); alsSel.add('com.a.Main'); alsRender(); }); await sleep(80);
  dialogs = []; answer = true;
  await ev(() => alsBulkComp(false)); await sleep(80);
  o = await afterOffer();
  check('   the Activity Launcher: Disable selected, OK on the offer: the Activity Launcher is closed and Root Features is the view on screen, no pm command', o.view === 'rootfeatures' && o.sheets.length === 0 && o.top === 'page' && o.pm === 0 && o.dialogs.length === 1, JSON.stringify(o));
  await ev(() => switchView('apps')); await sleep(40);
  await ev(() => openAppListSheet('acts', 'com.usr')); await sleep(80);
  dialogs = []; answer = true;
  await page.click('#alsList .perm-toggle-btn[data-comp="com.a.Main"]:not([data-exported])'); await sleep(80);
  o = await afterOffer();
  check('   a single locked DISABLE in the Activity Launcher, OK: the same, the sheet closed and Root Features on screen', o.view === 'rootfeatures' && o.sheets.length === 0 && o.top === 'page' && o.pm === 0 && o.dialogs.length === 1, JSON.stringify(o));

  // ---- 7. T-055: with su on the phone the funnel switches to Root by itself (su grants here). The rows are drawn again then, so the lock and the
  // Root reason go away even when the retried toggle stops: Cancel on the system-app confirm, or Cancel on Change activities
  const toggleLocks = sel => ev(s => [...document.querySelectorAll(s + ' .perm-toggle-btn')].filter(x => /ENABLE|DISABLE/.test(x.innerText))
    .map(x => x.classList.contains('locked') || x.innerText.indexOf('🔒') >= 0 || !!x.getAttribute('title')), sel);
  await ev(() => switchView('apps')); await sleep(40);
  await setMode('adbSu');
  await openComps('com.sys');
  const locks0 = await toggleLocks('#compsContainer');
  dialogs = []; answer = false; await ev(() => { window.__calls.toggle = []; window.__calls.selectWorkingMode = []; });
  await page.click('#compsContainer .perm-toggle-btn[data-comp="com.a.BootRcv"]'); await sleep(300);
  let a7 = { root: await ev(() => isRootActive()), sel: await ev(() => window.__calls.selectWorkingMode.join()), pm: await ev(() => window.__calls.toggle.length), dialogs: dialogs.slice(), locks: await toggleLocks('#compsContainer'), sheets: await sheets() };
  check('7. T-055 su on the phone, a system app: the tap switches to Root (su grants), Cancel on the system-app confirm runs nothing, and every row is drawn unlocked (no lock, no Root reason)', locks0.length === 3 && locks0.every(Boolean) && a7.root && a7.sel === 'root' && a7.pm === 0 && a7.dialogs.length === 1 && /of the system app com\.sys\?/.test(a7.dialogs[0]) && a7.locks.length === 3 && !a7.locks.some(Boolean) && a7.sheets.join() === 'inspectorModal', JSON.stringify([locks0, a7]));
  await ev(() => closeInspector()); await sleep(40);
  await setMode('adbSu');
  await ev(() => { openAppListSheet('acts', 'com.usr'); alsSel.clear(); alsSel.add('com.a.Main'); alsRender(); }); await sleep(80);
  const alsLocks0 = await toggleLocks('#alsList');
  dialogs = []; answer = false; await ev(() => { window.__calls.toggle = []; window.__calls.selectWorkingMode = []; });
  await ev(() => alsBulkComp(false)); await sleep(80);
  a7 = { root: await ev(() => isRootActive()), sel: await ev(() => window.__calls.selectWorkingMode.join()), pm: await ev(() => window.__calls.toggle.length), dialogs: dialogs.slice(), locks: await toggleLocks('#alsList'), sheets: await sheets() };
  check('   the Activity Launcher\'s Disable selected: switches to Root, Cancel on Change activities runs nothing, and its rows are drawn unlocked', alsLocks0.length === 2 && alsLocks0.every(Boolean) && a7.root && a7.sel === 'root' && a7.pm === 0 && a7.dialogs.length === 1 && /^confirm: Disable 1 activity\?/.test(a7.dialogs[0]) && a7.locks.length === 2 && !a7.locks.some(Boolean) && a7.sheets.join() === 'appListSheet', JSON.stringify([alsLocks0, a7]));
  await ev(() => closeAppListSheet()); await sleep(40);

  // ---- 8. T-051: the guide describes this control as it is. Read the built in-app guide (assets/guide.js), FULL-GUIDE.md and the device
  // checklist: the lock's reason, the Root Features offer and the system-app question are quoted as the page shows them (tog1.title, rootOffer and
  // sysConfirm above, the refusal toast from the page's source), and nothing says the button runs and says "Failed" off Root, or that Shizuku
  // started with root counts as root
  const sb = { window: {} };
  vm.runInNewContext(fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8'), sb);
  const g = {};
  ((sb.window.HELP_GUIDE || {}).sections || []).forEach(s => { g[s.id] = s.html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' '); });
  const lockQ = '"' + ((tog1 && tog1.title) || '?') + '"';
  const sysTail = sysConfirm.replace(/^.*? of the system app \S+\? /, '');
  const pageSrc = fs.readFileSync(path.join(REPO, 'assets', 'index.html'), 'utf8');
  const refusal = 'Super User was not granted. The working mode stays';
  const gMenu = g['apps-menu'] || '', gComp = g['rootfeatures-components'] || '';
  check('8. T-051 the guide\'s app menu topic: ENABLE/DISABLE carries the padlock ' + lockQ + ' in every mode but Root and sends nothing, a tap goes the Root way; no "runs, says Failed", no "(started by ADB)"',
    gMenu.includes('padlock, which here means ' + lockQ) && /sends nothing to Android/.test(gMenu) && /switches the working mode to Root/.test(gMenu) && !/runs, says "Failed"/.test(gMenu) && !/started by ADB/.test(gMenu), (gMenu.match(/DISABLE or ENABLE: switches that one screen.{0,300}/) || [''])[0]);
  check('   its App components under Super User topic: the same padlock, Shizuku started with root included; the switch to Root, the refusal toast and the Root Features offer as the page words them; the system-app question; no "fail with Failed"',
    gComp.includes('padlock, which here means ' + lockQ) && /the ENABLE and DISABLE buttons on a part unlock in Root mode \(with su granted\) and when your Shizuku server runs as the system user \(UID 1000\) or as root \(UID 0\)/.test(gComp) && gComp.includes(refusal) && pageSrc.includes("'" + refusal + " {0}.'") && rootOffer.length > 0 && gComp.includes('"' + rootOffer + '"')
      && sysTail.length > 0 && sysTail !== sysConfirm && gComp.includes('of the system app') && gComp.includes(sysTail + '"') && !/fail with "Failed"/.test(gComp) && !/counts as root for this/.test(gComp), JSON.stringify([rootOffer, sysTail, (gComp.match(/Why this is a root feature.{0,200}/) || [''])[0]]));
  const ckl = fs.readFileSync(path.join(REPO, 'docs', 'DEVICE-TEST-CHECKLIST.md'), 'utf8');
  const fullG = fs.readFileSync(path.join(REPO, 'docs', 'FULL-GUIDE.md'), 'utf8').replace(/\s+/g, ' ');
  check('   the other lock paragraphs (Read-Only\'s lock symbol, the quick start, Troubleshooting), FULL-GUIDE.md and the device checklist name the ENABLE/DISABLE padlock as Root-only',
    (g['mode-readonly'] || '').includes(lockQ) && !/the ENABLE, DISABLE and STOP buttons/.test(g['mode-readonly'] || '') && (g['quick-start'] || '').includes(lockQ) && (g['troubleshooting'] || '').includes(lockQ)
      && fullG.includes(lockQ) && /Enable \/ Disable\*\* on an activity or component works in Root mode \(su granted\) or with Shizuku running as root \(UID 0\) or the system user \(UID 1000\)/.test(fullG) && ckl.includes(lockQ) && !/only install-time permissions keep the lock\./.test(ckl), lockQ);

  check('9. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
