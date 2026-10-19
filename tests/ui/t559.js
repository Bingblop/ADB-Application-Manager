// v7.15.1 Root Features tab (step B1a, the gate and the surface): the tab in the registry (before About, on, its own icon and short label, not pinned by
// default), the root gate (#rootGate shown and the feature area locked unless the working mode is Root AND su granted it; the Java verdict
// rootWorkingModeActive can veto), the status tag (ACTIVE / AVAILABLE / DENIED / NOT FOUND), the gate's Set Up opening Working Modes, Request Super User
// going through selectWorkingMode('root'), the re-render on a mode change, and the attempt-to-prompt funnel promptRootThenOpen: Super User active -> true;
// su on the phone but another mode -> a switch to Root by itself (true when su grants, the prompt when it denies); no su -> the prompt, a yes opens the tab
// (false either way, a no changes nothing). Deterministic output: ok / FAIL lines and the summary.
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
    window.__calls = { selectWorkingMode: [] };
    // what the phone reports as its working mode; a test sets the state (and whether su would grant a switch to Root)
    window.__wm = { state: 'root', suGrants: true };
    // which root manager (if any) openRootManager would hand off to
    window.__rootMgr = { ok: true, name: 'KernelSU' };
    const WM = {
      root: { activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: true },
      rootVetoed: { activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: false },
      rootDenied: { activeMode: 'root', configuredMode: 'root', modeAvailable: false, isPrivileged: false, rootAvailable: true, rootGranted: false, rootWorkingModeActive: false },
      shizuku: { activeMode: 'shizuku', configuredMode: 'shizuku', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootWorkingModeActive: false },
      none: { activeMode: 'unprivileged', configuredMode: 'auto', modeAvailable: false, isPrivileged: false, rootAvailable: false, rootWorkingModeActive: false },
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; },
      openRootManager() { window.__calls.openRootManager = (window.__calls.openRootManager || 0) + 1; return JSON.stringify(window.__rootMgr || { ok: false }); },
      rootManagerName() { return (window.__rootMgr && window.__rootMgr.ok) ? window.__rootMgr.name : ''; },
      getWorkingMode() { return JSON.stringify(Object.assign({ adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true } }, WM[window.__wm.state])); },
      selectWorkingMode(mode) {
        window.__calls.selectWorkingMode.push(mode);
        if (mode === 'root') { const ok = !!window.__wm.suGrants; window.__wm.state = ok ? 'root' : 'rootDenied'; return JSON.stringify({ ok, mode, message: ok ? 'Using Root (Superuser)' : 'Root was denied or is not available' }); }
        window.__wm.state = mode === 'shizuku' ? 'shizuku' : 'none';
        return JSON.stringify({ ok: true, mode, message: 'Mode: ' + mode });
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const disp = sel => ev(s => getComputedStyle(document.querySelector(s)).display, sel);
  const text = sel => page.locator(sel).innerText();
  const setMode = async (state, suGrants) => { await ev(a => { window.__wm.state = a.state; if (a.suGrants !== undefined) window.__wm.suGrants = a.suGrants; checkAllWorkingModes(false); }, { state, suGrants }); await sleep(50); };
  const tag = () => ev(() => { const t = document.getElementById('rfStatusTag'); return t.className.replace('mode-status-tag', '').trim() + ' ' + t.innerText; });
  const lockedRow = () => ev(() => !!document.querySelector('#rfFeatureList .rf-row.is-locked .op-mode-btn.locked') && !!document.querySelector('#rfFeatureList .pm-lock'));
  const view = () => ev(() => currentViewName());

  // ---- 1. the registry
  const reg = await ev(() => ({ idx: TAB_DEFS.findIndex(t => t.key === 'rootfeatures'), n: TAB_DEFS.length, first: TAB_DEFS[0].key, last: TAB_DEFS[TAB_DEFS.length - 1].key, label: tabDef('rootfeatures').label, name: tabDef('rootfeatures').name, icon: !!BNAV_ICONS.rootfeatures && bnavIcon('rootfeatures').indexOf(BNAV_ICON_FALLBACK) < 0, short: bnavShort('rootfeatures'), pinned: tabDefaultPinned().includes('rootfeatures'), on: tabIsOn('rootfeatures'), inBar: !!document.querySelector('.tab-btn[data-tab="rootfeatures"]') }));
  check('1. the tab is in the registry right before About (Application Manager still first), on, in the tab bar, with its two-line label and its name', reg.idx === reg.n - 2 && reg.first === 'apps' && reg.last === 'about' && reg.on && reg.inBar && reg.label === 'Root\nFeatures' && reg.name === 'Root Features', JSON.stringify(reg));
  check('   its own icon (not the fallback) and the short label "Root"; not pinned on the bottom bar by default', reg.icon && reg.short === 'Root' && !reg.pinned, JSON.stringify(reg));

  // ---- 2. (A) Root in use and su granted: the tab opens unlocked
  await ev(() => switchView('rootfeatures')); await sleep(100);
  check('2. (A) Root in use and su granted: the tab opens and the gate is hidden', (await ev(() => document.getElementById('view-rootfeatures').classList.contains('active'))) && (await disp('#rootGate')) === 'none');
  check('   isRootActive() is true, the tag is green ACTIVE, the Super User card is put away, the feature area shows the unlocked Super User for apps row', (await ev(() => isRootActive())) && (await tag()) === 'active ACTIVE' && (await disp('#rfSuCard')) === 'none' && /Super User for apps/.test(await text('#rfFeatureList')) && !(await lockedRow()), await tag());
  check('   promptRootThenOpen goes straight on (true), switches nothing and asks nothing', (await ev(() => promptRootThenOpen('Test feature'))) === true && dialogs.length === 0 && (await ev(() => window.__calls.selectWorkingMode.length)) === 0);
  // the active-state feature: "Super User for apps" opens the installed manager (reusing openRootManager) and marks the status pending so a change there is read back on return
  await ev(() => { window.__calls.openRootManager = 0; window.__rootMgr = { ok: true, name: 'KernelSU' }; rootGrantPending = false; });
  check('   the unlocked feature row offers Open manager (rfSuAppsBtn) and shows no lock', (await ev(() => !!document.getElementById('rfSuAppsBtn'))) && !(await lockedRow()));
  await page.click('#rfSuAppsBtn'); await sleep(50);
  check('   Open manager (apps) launches the installed manager (openRootManager) and marks the status pending', (await ev(() => window.__calls.openRootManager)) === 1 && (await ev(() => rootGrantPending === true)));
  await ev(() => { rootGrantPending = false; });
  await setMode('rootVetoed');
  check('   the Java verdict can veto: Root reported as the privileged mode but rootWorkingModeActive false -> locked (gate shown, feature area locked)', !(await ev(() => isRootActive())) && (await disp('#rootGate')) === 'flex' && (await lockedRow()));
  await setMode('root');
  check('   and back: a mode change while on the tab re-renders it (rootOnModeChange)', (await ev(() => isRootActive())) && (await disp('#rootGate')) === 'none' && !(await lockedRow()));

  // ---- 3. (B) su on the phone, the mode is Shizuku
  await setMode('shizuku', true);
  check('3. (B) Shizuku with su on the phone: the gate shows, the tag is amber AVAILABLE, the features are locked with the reason, the Super User card is there', (await disp('#rootGate')) === 'flex' && (await tag()) === 'warning AVAILABLE' && (await lockedRow()) && /Needs Super User/.test(await text('#rfFeatureList')) && (await disp('#rfSuCard')) !== 'none', await tag());
  check('   the gate names the way: choose Root in Working Modes', /Choose Root in Working Modes/.test(await text('#rootGateDesc')));
  check('   the su card explains temporary root is granted in the root manager and offers the Open root manager button', /Temporary root is granted inside your root manager/.test(await text('#rfSuCard')) && (await ev(() => !!document.getElementById('rfOpenManagerBtn'))));
  // the hand-off: Open root manager launches the installed manager and marks the grant pending so the status is read back on return
  await ev(() => { window.__calls.openRootManager = 0; window.__rootMgr = { ok: true, name: 'KernelSU' }; rootGrantPending = false; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   Open root manager launches the installed manager (openRootManager) and marks the grant pending', (await ev(() => window.__calls.openRootManager)) === 1 && (await ev(() => rootGrantPending === true)));
  await ev(() => { rootGrantPending = false; window.__rootMgr = { ok: false }; window.__calls.openRootManager = 0; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   with no manager installed it still calls the bridge but marks nothing pending (it shows the install message instead)', (await ev(() => window.__calls.openRootManager)) === 1 && (await ev(() => rootGrantPending === false)));
  await ev(() => { window.__rootMgr = { ok: true, name: 'KernelSU' }; });
  await page.click('#rootGate .mode-alert-btn'); await sleep(50);
  check('   the gate\'s Set Up opens the Working Modes sheet', await ev(() => document.getElementById('modesModal').classList.contains('show')));
  await ev(() => closeWorkingModesModal()); await sleep(50);
  const r3 = await ev(() => promptRootThenOpen('Test feature'));
  check('   promptRootThenOpen switches to Root by itself (selectWorkingMode("root")); su grants: true, no prompt, and the tab is unlocked now', r3 === true && (await ev(() => window.__calls.selectWorkingMode.join())) === 'root' && dialogs.length === 0 && (await ev(() => isRootActive())) && (await disp('#rootGate')) === 'none', JSON.stringify([r3, dialogs]));
  await setMode('shizuku', false);
  await ev(() => { window.__calls.selectWorkingMode = []; });
  const r3b = await ev(() => promptRootThenOpen('Test feature'));
  check('   su denies the switch: the prompt follows, naming the feature; a yes keeps the Root Features tab open; the result is false', r3b === false && (await ev(() => window.__calls.selectWorkingMode.join())) === 'root' && dialogs.length === 1 && /^confirm: Test feature needs Super User\.\n\nOpen Root Features to check if your device supports Super User\?$/.test(dialogs[0]) && (await view()) === 'rootfeatures', JSON.stringify([r3b, dialogs]));
  check('   after the denial the tag says DENIED and the gate says why', (await tag()) === 'warning DENIED' && /did not grant/.test(await text('#rootGateDesc')), await tag());

  // ---- 4. (C) no su at all
  await ev(() => switchView('about')); await sleep(50);
  await setMode('none');
  await ev(() => switchView('rootfeatures')); await sleep(50);
  check('4. (C) no su: the gate shows with the reason, the tag is grey NOT FOUND, the features are locked', (await disp('#rootGate')) === 'flex' && (await tag()) === 'inactive NOT FOUND' && /No su was found/.test(await text('#rootGateDesc')) && (await lockedRow()), await tag());
  await ev(() => switchView('about')); await sleep(50);
  dialogs = []; await ev(() => { window.__calls.selectWorkingMode = []; });
  const r4 = await ev(() => promptRootThenOpen());
  check('   promptRootThenOpen from another tab: no switch is tried, the prompt is asked (no feature name: the question alone), a yes opens the tab, false', r4 === false && (await ev(() => window.__calls.selectWorkingMode.length)) === 0 && dialogs.length === 1 && dialogs[0] === 'confirm: Open Root Features to check if your device supports Super User?' && (await view()) === 'rootfeatures', JSON.stringify([r4, dialogs]));
  await ev(() => switchView('about')); await sleep(50);
  answer = false; dialogs = [];
  const r4b = await ev(() => promptRootThenOpen('Other'));
  check('   a no: nothing opens, false', r4b === false && dialogs.length === 1 && (await view()) === 'about', JSON.stringify([r4b, await view()]));
  answer = true;

  // ---- 5. Request Super User
  await ev(() => switchView('rootfeatures')); await sleep(50);
  await ev(() => { window.__wm.suGrants = true; window.__calls.selectWorkingMode = []; });
  await page.click('#rfRequestBtn'); await sleep(250);
  check('5. Request Super User asks su through selectWorkingMode("root") (the path of Working Modes\' Request Root Access); a grant unlocks the tab', (await ev(() => window.__calls.selectWorkingMode.join())) === 'root' && (await ev(() => isRootActive())) && (await disp('#rootGate')) === 'none');

  // ---- 6. the look at 360 px, locked
  await page.setViewportSize({ width: 360, height: 740 }); await setMode('shizuku', true); await sleep(100);
  const geo = await ev(() => {
    const v = document.getElementById('view-rootfeatures');
    const btns = [...v.querySelectorAll('.mode-action-btn, .op-mode-btn')].filter(b => b.getClientRects().length).map(b => ({ id: b.id || b.className, h: Math.round(b.getBoundingClientRect().height) }));
    return { wide: document.documentElement.scrollWidth - innerWidth, btns, tagOneLine: document.getElementById('rfStatusTag').getBoundingClientRect().height < 24, lockedOpacity: getComputedStyle(v.querySelector('.op-mode-btn.locked')).opacity };
  });
  check('6. at 360 px nothing is wider than the screen, the Request, Open-manager and locked buttons are at least 36 px tall, the tag stays on one line, the locked button is greyed', geo.wide <= 0 && geo.btns.length === 3 && geo.btns.every(x => x.h >= 36) && geo.tagOneLine && +geo.lockedOpacity < 1, JSON.stringify(geo));
  await page.screenshot({ path: 'rootfeatures_locked.png', fullPage: true });

  check('7. no page errors', errors.length === 0, errors.join(' | '));
  console.log('errors: ' + JSON.stringify(errors));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
