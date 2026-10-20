// v7.15.1 A3: enabling/disabling a component is Super User (Root) only, for every app (user and system alike). Off root the
// ENABLE/DISABLE control is locked (lock icon, "Needs Super User (Root)") while LAUNCH/STOP keep the ordinary working-mode lock,
// and a tap funnels through promptRootThenOpen instead of firing a doomed pm command. On root the control works: a user app's
// component toggles with no extra prompt; disabling a SYSTEM app's component asks for confirmation first (enabling does not).
// The bulk activity toggle (Activity Launcher) is Root-gated the same way. T-054: without su the funnel offers the Root Features tab, and a yes
// closes the sheet the tap came from (the app sheet, the Activity Launcher) so the tab is on screen; a no leaves the sheet open.
// Deterministic ok/FAIL lines and a summary.
const { chromium, PAGE } = require('./lib/pw');
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

  check('7. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
