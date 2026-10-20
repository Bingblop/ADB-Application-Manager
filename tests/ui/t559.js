// v7.15.1 Root Features tab (the gate, the Super User card, the Super User for apps and App components rows): the tab in the registry (before About,
// on, its own icon and short label, not pinned by default), the feature rows (#rfSuAppsBtn opens the root manager, #rfComponentsBtn the Application
// Manager), the root-manager hand-off (Open root manager, its toasts, and the status read back on the way back), the root gate (#rootGate shown and the feature area locked unless the working mode is Root AND su granted it; the Java verdict
// rootWorkingModeActive can veto), the status tag (ACTIVE / AVAILABLE / DENIED / NOT FOUND), the gate's Set Up opening Working Modes, Request Super User
// going through selectWorkingMode('root'), the re-render on a mode change, and the attempt-to-prompt funnel promptRootThenOpen: Super User active -> true;
// su on the phone but another mode -> a switch to Root by itself (true when su grants, the prompt when it denies); no su -> the prompt, a yes opens the tab
// (false either way, a no changes nothing). Coming back from Open root manager with another mode chosen asks to switch to Root (T-017): a no changes
// nothing, a yes goes through selectWorkingMode('root'); with Root already chosen nothing is asked. Open root manager that finds no known manager says
// it was not found by its usual name while su is on the phone (a hidden Magisk, T-018), and asks to install one only without su; so does Open manager
// on the unlocked Super User for apps row while root is ACTIVE (T-025). The note says to tap Request Super User first when the app is not in the
// manager's list (Magisk lists an app only after it has asked su, T-019).
// Then the Help Guide (T-029): its Root Features topic names the Features card, the Super User for apps row
// with Open manager and the Open root manager hand-off (no "later version" of the card), and Open manager is described as what openRootManager is, a
// plain launch intent (the manager's start screen, not its list of apps with root), in the in-app guide and in FULL-GUIDE.md alike.
// The App components row's line names every kind of component the inspector can enable or disable (activities, receivers, services and
// providers; T-033). T-043: a refused or impossible switch (Request Super User or the funnel) puts the mode chosen before back
// (setWorkingMode) and the tag still reads DENIED; Root chosen before stays chosen. T-044: a tab about to open unlocked asks su again
// (getWorkingModeFresh), so a grant taken back in the root manager locks it; a locked tab asks nothing. T-046: with the tab switched off the funnel
// asks no question, it shows the "turned off" toast. Deterministic output: ok / FAIL lines and the summary.
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
    window.__calls = { selectWorkingMode: [], setWorkingMode: [] };
    // what the phone reports as its working mode; a test sets the state (and whether su would grant a switch to Root). refused: su's last answer was a
    // no, which the Java probe keeps reporting as rootGranted false in the other modes (until a fresh check forgets it)
    window.__wm = { state: 'root', suGrants: true, refused: false };
    // which root manager (if any) openRootManager would hand off to
    window.__rootMgr = { ok: true, name: 'KernelSU' };
    const WM = {
      root: { activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: true },
      rootVetoed: { activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: false },
      rootDenied: { activeMode: 'root', configuredMode: 'root', modeAvailable: false, isPrivileged: false, rootAvailable: true, rootGranted: false, rootWorkingModeActive: false },
      rootNoSu: { activeMode: 'root', configuredMode: 'root', modeAvailable: false, isPrivileged: false, rootAvailable: false, rootWorkingModeActive: false },
      shizuku: { activeMode: 'shizuku', configuredMode: 'shizuku', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootWorkingModeActive: false },
      none: { activeMode: 'unprivileged', configuredMode: 'auto', modeAvailable: false, isPrivileged: false, rootAvailable: false, rootWorkingModeActive: false },
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; },
      // one component of each kind, for the inspector the App components row sends the user to
      getAppDetails() {
        return JSON.stringify({ permissions: [], appopsRaw: '',
          activityInfo: [{ name: 'com.t.Main', exported: true, enabled: true, permission: '' }],
          receiverInfo: [{ name: 'com.t.BootRcv', exported: false, enabled: true, permission: '' }],
          serviceInfo: [{ name: 'com.t.Svc', exported: false, enabled: true, permission: '' }],
          providerInfo: [{ name: 'androidx.core.content.FileProvider', exported: false, enabled: true, permission: '' }] });
      },
      openRootManager() { window.__calls.openRootManager = (window.__calls.openRootManager || 0) + 1; return JSON.stringify(window.__rootMgr || { ok: false }); },
      getWorkingMode() {
        const s = WM[window.__wm.state], refusal = window.__wm.refused && s.rootAvailable && s.configuredMode !== 'root' ? { rootGranted: false } : {};
        return JSON.stringify(Object.assign({ adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true } }, s, refusal));
      },
      // like probeAndCacheMode(true): while Root is chosen su is asked again (a grant taken back in the manager, revoked, now reads as a refusal); in the
      // other modes su's last answer is forgotten
      getWorkingModeFresh() {
        window.__calls.fresh = (window.__calls.fresh || 0) + 1;
        if (WM[window.__wm.state].configuredMode === 'root') {
          if (window.__wm.revoked && window.__wm.state === 'root') window.__wm.state = 'rootDenied';
          window.__wm.refused = window.__wm.state !== 'root';
        } else window.__wm.refused = false;
        return window.AndroidBridge.getWorkingMode();
      },
      // like the Java side: the mode is chosen whatever su answers (ok false: chosen but not ready); with no su the test command cannot even start
      selectWorkingMode(mode) {
        window.__calls.selectWorkingMode.push(mode);
        if (mode === 'root') {
          const noSu = !WM[window.__wm.state].rootAvailable, ok = !noSu && !!window.__wm.suGrants;
          window.__wm.state = ok ? 'root' : noSu ? 'rootNoSu' : 'rootDenied'; window.__wm.refused = !ok;
          return JSON.stringify({ ok, mode, message: ok ? 'Using Root (Superuser)' : 'Root was denied or is not available' });
        }
        window.__wm.state = mode === 'shizuku' ? 'shizuku' : 'none';
        return JSON.stringify({ ok: true, mode, message: 'Mode: ' + mode });
      },
      // only writes the chosen mode back (no prompt, no connection)
      setWorkingMode(mode) { window.__calls.setWorkingMode.push(mode); window.__wm.state = mode === 'shizuku' ? 'shizuku' : mode === 'root' ? 'rootDenied' : 'none'; },
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
  const toastText = () => ev(() => document.getElementById('toastMsg').textContent);

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
  await ev(() => { rootGrantPending = false; window.__rootMgr = { ok: false }; });
  await page.click('#rfSuAppsBtn'); await sleep(50);
  check('   with root ACTIVE but no known manager (hidden or renamed), Open manager says it was not found by its usual name, not "install one", and marks nothing pending (T-025)', (await text('#toastMsg')) === 'Your root manager was not found by its usual name (it may be hidden or renamed). Open it from your home screen and manage apps in its Super User list.' && (await ev(() => rootGrantPending === false)), await text('#toastMsg'));
  // a manager that is installed but did not start (the bridge answers reason 'launch'): no "Opening ..." toast, nothing pending, not the "install one" text
  await ev(() => { window.__rootMgr = { ok: false, reason: 'launch', name: 'KernelSU' }; });
  await page.click('#rfSuAppsBtn'); await sleep(50);
  check('   a manager that did not start (reason launch): the toast says it could not be opened from here and nothing is pending', (await toastText()) === 'KernelSU could not be opened from here. Open it from your home screen.' && (await ev(() => rootGrantPending === false)), await toastText());
  await ev(() => { window.__rootMgr = { ok: true, name: 'KernelSU' }; });
  // the way back from Open manager: this app's own su was revoked in the manager; the page shown again reads the status fresh once, spends the mark,
  // and re-renders the tab from that read
  const fresh1 = await ev(() => window.__calls.fresh || 0);
  await page.click('#rfSuAppsBtn'); await sleep(50);
  await ev(() => { window.__wm.state = 'rootDenied'; document.dispatchEvent(new Event('visibilitychange')); }); await sleep(50);
  const back1 = await ev(() => ({ fresh: window.__calls.fresh || 0, pending: rootGrantPending }));
  check('   back from Open manager (the page shown again): one fresh status read, the mark spent, the tab re-rendered (revoked there -> DENIED, rows locked)', back1.fresh === fresh1 + 1 && back1.pending === false && (await tag()) === 'warning DENIED' && (await lockedRow()), JSON.stringify([fresh1, back1, await tag()]));
  await setMode('root');
  // the second active-state feature: "App components" is a signpost to the Application Manager (no new backend; the component toggle already lives in the app inspector)
  check('   the feature area also shows the unlocked App components row with Open apps (rfComponentsBtn)', /App components/.test(await text('#rfFeatureList')) && (await ev(() => !!document.getElementById('rfComponentsBtn'))));
  await page.click('#rfComponentsBtn'); await sleep(50);
  check('   Open apps goes to the Application Manager (switchView apps)', (await view()) === 'apps');
  // T-033: the row's line must name what the user finds there: every kind of component that gets an ENABLE/DISABLE button, read from the
  // inspector itself (the Activities tab, and each group of the Components tab: receivers, services, providers)
  const rowSub = await ev(() => { const n = [...document.querySelectorAll('#rfFeatureList .rf-name')].find(x => /App components/.test(x.textContent)); const s = n && n.querySelector('span'); return s ? s.textContent : ''; });
  await ev(() => openInspector('com.t')); await sleep(120);
  const toggled = await ev(() => {
    const hasToggle = el => [...el.querySelectorAll('.perm-toggle-btn')].some(x => /ENABLE|DISABLE/.test(x.textContent));
    const out = hasToggle(document.getElementById('actsContainer')) ? ['activities'] : [];
    let group = null;
    [...document.getElementById('compsContainer').children].forEach(el => {
      const m = /^([A-Z]+) \(\d+\)$/.exec(el.textContent.trim());
      if (m) { group = m[1].toLowerCase(); return; }
      if (group && !out.includes(group) && hasToggle(el)) out.push(group);
    });
    return out;
  });
  check('   its line names every kind the inspector can enable or disable: activities, receivers, services and providers (T-033)', toggled.join() === 'activities,receivers,services,providers' && toggled.every(k => rowSub.toLowerCase().includes(k)), JSON.stringify({ toggled, rowSub }));
  await ev(() => closeInspector()); await sleep(50);
  await ev(() => switchView('rootfeatures')); await sleep(50);
  await setMode('rootVetoed');
  check('   the Java verdict can veto: Root reported as the privileged mode but rootWorkingModeActive false -> locked (gate shown, feature area locked)', !(await ev(() => isRootActive())) && (await disp('#rootGate')) === 'flex' && (await lockedRow()));
  await setMode('root');
  check('   and back: a mode change while on the tab re-renders it (rootOnModeChange)', (await ev(() => isRootActive())) && (await disp('#rootGate')) === 'none' && !(await lockedRow()));
  // T-044: ACTIVE is su's last answer. The grant is taken back in the root manager while the app is on another tab: the quiet checks still read the
  // remembered yes, so opening the tab unlocked asks su again (one fresh check) and the no locks it
  const fresh = () => ev(() => window.__calls.fresh || 0);
  // that fresh check is not one the user asked for, so it shows no "Mode: ..." toast (a tab open is far more frequent than Check Status)
  const clearToast = () => ev(() => { document.getElementById('toastMsg').textContent = ''; });
  await ev(() => { switchView('apps'); window.__calls.fresh = 0; }); await clearToast();
  await ev(() => switchView('rootfeatures')); await sleep(50);
  check('   T-044: opening the tab with the grant still there: one fresh check, the tab stays unlocked (ACTIVE) and no "Mode:" toast comes', (await fresh()) === 1 && (await tag()) === 'active ACTIVE' && (await disp('#rootGate')) === 'none' && !(await lockedRow()) && (await toastText()) === '', JSON.stringify([await fresh(), await tag(), await toastText()]));
  await ev(() => { switchView('apps'); window.__calls.fresh = 0; window.__wm.revoked = true; checkAllWorkingModes(false); }); await sleep(50);
  check('   after a grant is taken back in the manager, the quiet check still reads the remembered yes (Root active, no fresh check asked)', (await ev(() => isRootActive())) && (await fresh()) === 0);
  await clearToast(); await ev(() => switchView('rootfeatures')); await sleep(50);
  check('   opening the tab unlocked asks su again (one fresh check): it now reads DENIED, the gate shows and the rows are locked', (await fresh()) === 1 && (await tag()) === 'warning DENIED' && (await disp('#rootGate')) === 'flex' && (await lockedRow()) && !(await ev(() => isRootActive())), JSON.stringify([await fresh(), await tag()]));
  check('   and the refusal it read shows no "Mode:" toast either (the tag and the gate say it)', (await toastText()) === '', await toastText());
  await ev(() => { switchView('apps'); window.__calls.fresh = 0; switchView('rootfeatures'); }); await sleep(50);
  check('   a tab that opens locked asks nothing more (no second su prompt after a no)', (await fresh()) === 0 && (await tag()) === 'warning DENIED', JSON.stringify([await fresh(), await tag()]));
  await ev(() => { window.__wm.revoked = false; window.__wm.refused = false; }); await setMode('root');

  // ---- 3. (B) su on the phone, the mode is Shizuku
  await setMode('shizuku', true);
  check('3. (B) Shizuku with su on the phone: the gate shows, the tag is amber AVAILABLE, the features are locked with the reason, the Super User card is there', (await disp('#rootGate')) === 'flex' && (await tag()) === 'warning AVAILABLE' && (await lockedRow()) && /Needs Super User/.test(await text('#rfFeatureList')) && (await disp('#rfSuCard')) !== 'none', await tag());
  check('   the gate names the way: choose Root in Working Modes', /Choose Root in Working Modes/.test(await text('#rootGateDesc')));
  check('   the su card explains temporary root is granted in the root manager and offers the Open root manager button', /Temporary root is granted inside your root manager/.test(await text('#rfSuCard')) && (await ev(() => !!document.getElementById('rfOpenManagerBtn'))));
  // the note under the buttons is the step to take, styled as the card's text (body size, upright), not as a small italic "coming soon" footnote
  const noteLook = await ev(() => { const n = getComputedStyle(document.getElementById('rfManagerNote')), intro = getComputedStyle(document.querySelector('#rfSuCard .rf-note')); return { style: n.fontStyle, size: n.fontSize, introSize: intro.fontSize }; });
  check('   the hand-off note reads as the card\'s text: upright, the size of the line above the buttons', noteLook.style === 'normal' && noteLook.size === noteLook.introSize, JSON.stringify(noteLook));
  // the hand-off: Open root manager launches the installed manager and marks the grant pending so the status is read back on return
  await ev(() => { window.__calls.openRootManager = 0; window.__rootMgr = { ok: true, name: 'KernelSU' }; rootGrantPending = false; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   Open root manager launches the installed manager (openRootManager) and marks the grant pending', (await ev(() => window.__calls.openRootManager)) === 1 && (await ev(() => rootGrantPending === true)));
  // coming back from the manager (T-017): su counts only while Root is the chosen mode, so with Shizuku chosen the fresh check alone unlocks nothing;
  // the page asks to switch to Root (a no changes nothing, a yes goes the way of Request Super User)
  const comeBack = async () => { await ev(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' }); document.dispatchEvent(new Event('visibilitychange')); }); await sleep(250); };
  await ev(() => { window.__calls.selectWorkingMode = []; }); dialogs = []; answer = false;
  await comeBack();
  check('   back from the manager with Shizuku chosen: the page asks to switch to Root; a no switches nothing, the tag stays AVAILABLE and nothing is pending', dialogs.length === 1 && /^confirm: Switch the working mode to Root now\? Root Features unlock once su grants this app access\.$/.test(dialogs[0]) && (await ev(() => window.__calls.selectWorkingMode.length)) === 0 && (await tag()) === 'warning AVAILABLE' && (await ev(() => rootGrantPending === false)), JSON.stringify(dialogs));
  answer = true; dialogs = [];
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  await comeBack();
  check('   a yes switches to Root (selectWorkingMode("root")); su was allowed in the manager, so the tag reads ACTIVE and the gate is gone', dialogs.length === 1 && (await ev(() => window.__calls.selectWorkingMode.join())) === 'root' && (await tag()) === 'active ACTIVE' && (await disp('#rootGate')) === 'none', JSON.stringify([dialogs, await tag()]));
  await setMode('rootDenied'); dialogs = []; await ev(() => { window.__calls.selectWorkingMode = []; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  await comeBack();
  check('   with Root already chosen (DENIED) the fresh check is the whole of it: no question, no switch', dialogs.length === 0 && (await ev(() => window.__calls.selectWorkingMode.length)) === 0, JSON.stringify(dialogs));
  check('   the note says to come back and let the app switch to Root', /then come back and let the app switch to Root\./.test(await text('#rfManagerNote')));
  // T-019: Magisk lists an app in its Superuser screen only after the app has asked su once, and su is asked only through Root; the note says so
  check('   the note says what to do when the app is not in the manager\'s list (Magisk): Request Super User first', /If this app is not in its list \(Magisk lists an app only after it has asked for su\), tap Request Super User first\./.test(await text('#rfManagerNote')), await text('#rfManagerNote'));
  await setMode('shizuku', true); dialogs = []; await ev(() => { window.__calls.selectWorkingMode = []; });
  await ev(() => { rootGrantPending = false; window.__rootMgr = { ok: false }; window.__calls.openRootManager = 0; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   with su on the phone but no known manager (a hidden Magisk) it calls the bridge, marks nothing pending, and says the manager was not found by its usual name instead of asking to install one (T-018)', (await ev(() => window.__calls.openRootManager)) === 1 && (await ev(() => rootGrantPending === false)) && /^Your root manager was not found by its usual name \(a hidden Magisk app, for example\)\. Open it yourself, allow this app, then tap Request Super User\.$/.test(await text('#toastMsg')), await text('#toastMsg'));
  await ev(() => { window.__rootMgr = { ok: false, reason: 'launch', name: 'Magisk' }; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   a manager that is installed but did not start (reason launch): not the install message, the could-not-open one, nothing pending', (await toastText()) === 'Magisk could not be opened from here. Open it from your home screen.' && (await ev(() => rootGrantPending === false)), await toastText());
  await ev(() => { window.__rootMgr = { ok: true, name: 'KernelSU' }; });
  // the way back when this app was never hidden (split screen or freeform: the manager opened beside it): the activity resuming (onAppResume) and
  // the window getting focus back also read the status fresh, once, while a hand-off is pending - and not without one. Shizuku is the chosen mode
  // here, so each read also brings the offer to switch to Root (T-017); it is declined, so the mode stays and the gate with it
  answer = false;
  let f0 = await fresh();
  await ev(() => { rootGrantPending = true; onAppResume(); }); await sleep(50);
  check('   back without being hidden, by the activity resuming (onAppResume): a fresh status read, and the pending mark is spent', (await fresh()) === f0 + 1 && (await ev(() => rootGrantPending === false)), JSON.stringify([f0, await fresh()]));
  f0 = await fresh();
  await ev(() => onAppResume()); await sleep(50);
  check('   onAppResume with nothing pending reads nothing fresh', (await fresh()) === f0, JSON.stringify([f0, await fresh()]));
  await ev(() => { rootGrantPending = true; window.dispatchEvent(new Event('focus')); }); await sleep(50);
  check('   back by the window getting focus again (Android 10+ can keep both windows resumed and shown): a fresh status read, the mark spent', (await fresh()) === f0 + 1 && (await ev(() => rootGrantPending === false)), JSON.stringify([f0, await fresh()]));
  // a hidden page reads nothing: onAppResume also runs after a wallpaper or theme change, which can come while the user is still in the manager
  f0 = await fresh();
  await ev(() => { rootGrantPending = true; Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' }); onAppResume(); window.dispatchEvent(new Event('focus')); }); await sleep(50);
  const whileHidden = await ev(() => ({ fresh: window.__calls.fresh || 0, pending: rootGrantPending }));
  await ev(() => { delete document.visibilityState; document.dispatchEvent(new Event('visibilitychange')); }); await sleep(50);
  check('   while the page is hidden (the user still in the manager) nothing is read and the mark waits; shown again, one fresh read', whileHidden.fresh === f0 && whileHidden.pending === true && (await fresh()) === f0 + 1 && (await ev(() => rootGrantPending === false)), JSON.stringify([f0, whileHidden, await fresh()]));
  check('   each of those ways back offered the switch to Root once (T-017), and the declined offers switched nothing', dialogs.length === 3 && dialogs.every(d => /^confirm: Switch the working mode to Root now\?/.test(d)) && (await ev(() => window.__calls.selectWorkingMode.length)) === 0 && (await tag()) === 'warning AVAILABLE', JSON.stringify(dialogs));
  answer = true; dialogs = [];
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
  check('   T-043: the denied switch puts Shizuku back (setWorkingMode("shizuku") after selectWorkingMode("root")): chosen and privileged again, the badge reads Shizuku', (await ev(() => window.__calls.setWorkingMode.join())) === 'shizuku' && (await ev(() => window.__wm.state)) === 'shizuku' && (await ev(() => lastModesData.configuredMode === 'shizuku' && isPrivilegedActive === true)) && (await text('#execModeText')) === 'Shizuku', JSON.stringify(await ev(() => [window.__calls.setWorkingMode, window.__wm.state, isPrivilegedActive, document.getElementById('execModeText').innerText])));

  // ---- 4. (C) no su at all
  await ev(() => switchView('about')); await sleep(50);
  await setMode('none');
  await ev(() => switchView('rootfeatures')); await sleep(50);
  check('4. (C) no su: the gate shows with the reason, the tag is grey NOT FOUND, the features are locked', (await disp('#rootGate')) === 'flex' && (await tag()) === 'inactive NOT FOUND' && /No su was found/.test(await text('#rootGateDesc')) && (await lockedRow()), await tag());
  await ev(() => { window.__rootMgr = { ok: false }; rootGrantPending = false; });
  await page.click('#rfOpenManagerBtn'); await sleep(50);
  check('   Open root manager with no su and no manager: the install message, nothing pending', (await text('#toastMsg')) === 'No root manager was found. Install KernelSU, APatch or Magisk, then come back.' && (await ev(() => rootGrantPending === false)), await text('#toastMsg'));
  await ev(() => { window.__rootMgr = { ok: true, name: 'KernelSU' }; });
  await ev(() => switchView('about')); await sleep(50);
  dialogs = []; await ev(() => { window.__calls.selectWorkingMode = []; });
  const r4 = await ev(() => promptRootThenOpen());
  check('   promptRootThenOpen from another tab: no switch is tried, the prompt is asked (no feature name: the question alone), a yes opens the tab, false', r4 === false && (await ev(() => window.__calls.selectWorkingMode.length)) === 0 && dialogs.length === 1 && dialogs[0] === 'confirm: Open Root Features to check if your device supports Super User?' && (await view()) === 'rootfeatures', JSON.stringify([r4, dialogs]));
  await ev(() => switchView('about')); await sleep(50);
  answer = false; dialogs = [];
  const r4b = await ev(() => promptRootThenOpen('Other'));
  check('   a no: nothing opens, false', r4b === false && dialogs.length === 1 && (await view()) === 'about', JSON.stringify([r4b, await view()]));
  answer = true;
  // T-046: with the tab switched off in Settings > Feature List the question is not asked at all: the page's "turned off" toast says where to turn it on
  await ev(() => { tabConfig.off.push('rootfeatures'); }); dialogs = [];
  const r4c = await ev(() => promptRootThenOpen('Other'));
  check('   T-046: the tab switched off: no question, the toast says it is turned off and where to turn it on, nothing opens, false', r4c === false && dialogs.length === 0 && (await text('#toastMsg')) === 'Root Features is turned off. Turn it on in Settings, Feature List.' && (await view()) === 'about', JSON.stringify([r4c, dialogs, await text('#toastMsg'), await view()]));
  await ev(() => { tabConfig.off = tabConfig.off.filter(k => k !== 'rootfeatures'); });

  // ---- 5. Request Super User
  await ev(() => switchView('rootfeatures')); await sleep(50);
  const calls = () => ev(() => window.__calls.selectWorkingMode.join() + ' / ' + window.__calls.setWorkingMode.join());
  const resetCalls = () => ev(() => { window.__calls.selectWorkingMode = []; window.__calls.setWorkingMode = []; });
  // T-043: from Shizuku with su that says no, the switch to Root is undone: Shizuku is chosen and privileged again, the tag reads DENIED
  await setMode('shizuku', false); await resetCalls();
  await page.click('#rfRequestBtn'); await sleep(250);
  check('5. T-043 Request Super User from Shizuku, su says no: Root is asked (selectWorkingMode "root"), then Shizuku is put back (setWorkingMode "shizuku")', (await calls()) === 'root / shizuku' && (await ev(() => window.__wm.state)) === 'shizuku', await calls());
  check('   Shizuku is chosen and privileged again (badge Shizuku), the tag reads DENIED with the reason, the rows stay locked', (await ev(() => lastModesData.configuredMode === 'shizuku' && isPrivilegedActive === true)) && (await text('#execModeText')) === 'Shizuku' && (await tag()) === 'warning DENIED' && /did not grant/.test(await text('#rootGateDesc')) && (await lockedRow()), JSON.stringify([await tag(), await text('#execModeText')]));
  check('   the toast says so: "Super User was not granted. The working mode stays Shizuku ADB (UID 2000)."', (await toastText()) === 'Super User was not granted. The working mode stays Shizuku ADB (UID 2000).', await toastText());
  // a phone without su, on Automatic: the switch cannot even ask, and Automatic comes back
  await setMode('none'); await resetCalls();
  await page.click('#rfRequestBtn'); await sleep(250);
  check('   no su, Automatic chosen: the switch fails and Automatic is put back (configuredMode auto), the tag reads NOT FOUND', (await calls()) === 'root / auto' && (await ev(() => lastModesData.configuredMode)) === 'auto' && (await tag()) === 'inactive NOT FOUND' && /stays Automatic\.$/.test(await toastText()), JSON.stringify([await calls(), await tag(), await toastText()]));
  // Root already chosen (DENIED before): a new no keeps Root chosen, nothing is put back
  await setMode('rootDenied', false); await resetCalls();
  await page.click('#rfRequestBtn'); await sleep(250);
  check('   Root already chosen and su says no again: Root stays chosen (no setWorkingMode), DENIED', (await calls()) === 'root / ' && (await ev(() => lastModesData.configuredMode)) === 'root' && (await tag()) === 'warning DENIED', JSON.stringify([await calls(), await tag()]));
  await setMode('shizuku', true); await resetCalls();
  await page.click('#rfRequestBtn'); await sleep(250);
  check('   Request Super User asks su through selectWorkingMode("root") (the path of Working Modes\' Request Root Access); a grant unlocks the tab and puts nothing back', (await calls()) === 'root / ' && (await ev(() => isRootActive())) && (await disp('#rootGate')) === 'none', await calls());

  // ---- 6. the look at 360 px, locked
  await page.setViewportSize({ width: 360, height: 740 }); await setMode('shizuku', true); await sleep(100);
  const geo = await ev(() => {
    const v = document.getElementById('view-rootfeatures');
    const btns = [...v.querySelectorAll('.mode-action-btn, .op-mode-btn')].filter(b => b.getClientRects().length).map(b => ({ id: b.id || b.className, h: Math.round(b.getBoundingClientRect().height) }));
    return { wide: document.documentElement.scrollWidth - innerWidth, btns, tagOneLine: document.getElementById('rfStatusTag').getBoundingClientRect().height < 24, lockedOpacity: getComputedStyle(v.querySelector('.op-mode-btn.locked')).opacity };
  });
  check('6. at 360 px nothing is wider than the screen, the Request, Open-manager and the two locked feature buttons are at least 36 px tall, the tag stays on one line, the locked button is greyed', geo.wide <= 0 && geo.btns.length === 4 && geo.btns.every(x => x.h >= 36) && geo.tagOneLine && +geo.lockedOpacity < 1, JSON.stringify(geo));
  await page.screenshot({ path: 'rootfeatures_locked.png', fullPage: true });

  // ---- 7. the Help Guide matches the tab (T-029): read the built in-app guide (assets/guide.js) and FULL-GUIDE.md's tab table
  const sb = { window: {} };
  vm.runInNewContext(fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8'), sb);
  const plain = {};
  ((sb.window.HELP_GUIDE || {}).sections || []).forEach(s => { plain[s.id] = s.html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' '); });
  const gTab = plain['tab-rootfeatures'] || '', gSuApps = plain['rootfeatures-su-apps'] || '';
  check('7. the guide\'s Root Features topic names the Features card, Super User for apps with Open manager, and Open root manager; no "later version" of the card', /The "Features" card/.test(gTab) && /Super User for apps/.test(gTab) && /Open manager/.test(gTab) && /Open root manager/.test(gTab) && !/later version|until then the card says so/i.test(gTab), gTab.slice(0, 160));
  check('   its Super User for apps topic: Open manager opens the manager the way its icon does, not on its list of apps with root; the status is read again on return', /opens the way its icon on the home screen opens it/.test(gSuApps) && /not on its list of apps with root/.test(gSuApps) && /Come back\. The app checks its own root status again/.test(gSuApps), gSuApps.slice(0, 160));
  const fullRow = (fs.readFileSync(path.join(REPO, 'docs', 'FULL-GUIDE.md'), 'utf8').match(/^\| \*\*Root Features\*\* \|.*$/m) || [''])[0];
  check('   FULL-GUIDE.md\'s Root Features row says the same (not "opens the manager\'s list of apps with root")', /\*\*Super User for apps\*\*/.test(fullRow) && /not on its list of apps with root/.test(fullRow) && !/opens the manager's list/.test(fullRow), fullRow);

  check('8. no page errors', errors.length === 0, errors.join(' | '));
  console.log('errors: ' + JSON.stringify(errors));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
