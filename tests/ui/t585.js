// v7.16.2: switching an app's trackers off and on (Root, or Shizuku as the system user UID 1000 or root UID 0). The Trackers box of the app menu lists, under
// each tracker, the app's parts that belong to it (screens, services, receivers, providers) with their state, a switch per tracker and Disable / Enable all
// trackers; a tracker with no part of its own says so. Off the gate every switch carries the lock and a tap goes the Root way (nothing is sent). The batch
// menu has Disable Trackers (confirmed first, with a note for system apps) and Enable Trackers, run by the native batch runner with results, Run again and
// the History's Undo, which switches back exactly the parts that step changed. Root Features has a "Trackers in apps" row that opens the apps a tracker
// was found in. The mock keeps every answer of the app until the script lets it go (__release), and the script waits for what the page shows.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
// what was seen is printed only on a failure: the expected output is the list of checks, not text that changes with the fonts or the order of a list
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = false;
  page.on('dialog', d => { dialogs.push(d.message()); (answer ? d.accept() : d.dismiss()).catch(() => {}); });
  const apps = [
    { pkg: 'com.example.alpha', name: 'Alpha', isSystem: false, isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.example.bravo', name: 'Bravo', isSystem: false, isFrozen: false, isSuspended: false, isUninstalled: false },
    { pkg: 'com.android.sysapp', name: 'Sys App', isSystem: true, isFrozen: false, isSuspended: false, isUninstalled: false },
  ];
  await page.addInitScript(a => {
    window.__set = []; window.__batch = []; window.__exec = [];
    // the app's answers wait here until the script lets them go, so what the page shows while it waits can be looked at, at any speed
    window.__held = [];
    window.__release = () => { const h = window.__held.splice(0); h.forEach(f => f()); return h.length; };
    // the parts on the "phone": name -> { kind, manifest, override (true / false / null) }
    const P = (kind, override, manifest) => ({ kind, manifest: manifest !== false, override: override === undefined ? null : override });
    window.__parts = {
      'com.example.alpha': {
        49: { name: 'Google Firebase Analytics', categories: 'Analytics', website: 'https://firebase.google.com/', parts: {
          'com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar': P('service'),
          'com.google.android.gms.measurement.AppMeasurementReceiver': P('receiver'),
          'com.google.android.gms.measurement.AppMeasurementJobService': P('service', null, false) } },
        70: { name: 'Facebook Ads', categories: 'Advertisement', website: 'https://developers.facebook.com/docs/android', parts: {
          'com.facebook.ads.AudienceNetworkActivity': P('activity') } },
      },
      'com.example.bravo': {
        70: { name: 'Facebook Ads', categories: 'Advertisement', website: '', parts: { 'com.facebook.ads.AudienceNetworkActivity': P('activity') } },
      },
      'com.android.sysapp': {
        49: { name: 'Google Firebase Analytics', categories: 'Analytics', website: '', parts: { 'com.google.android.gms.measurement.AppMeasurementReceiver': P('receiver') } },
      },
    };
    const on = x => x.override !== null ? x.override : x.manifest;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
      getWorkingMode() { return JSON.stringify({ mode: 'shizuku', isPrivileged: true, status: 'connected', activeMode: 'shizuku', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; }, getUadInfo() { return '{}'; },
      trackerCached() { return JSON.stringify({ 'com.example.alpha': { ok: true, ids: [49, 312] }, 'com.example.bravo': { ok: true, ids: [] }, 'com.android.sysapp': { ok: true, ids: [49] } }); },
      trackerScan() { return 'started'; },
      trackerInfo() { return JSON.stringify({ count: 428, retrieved: '2026-10-03', trackers: { 49: { id: 49, name: 'Google Firebase Analytics', categories: 'Analytics', website: 'https://firebase.google.com/' }, 312: { id: 312, name: 'Google AdMob', categories: 'Advertisement', website: 'https://admob.google.com' } } }); },
      trackerParts(pkg) {
        const app = window.__parts[pkg] || {};
        let parts = 0, ons = 0, offs = 0;
        const trackers = Object.keys(app).map(id => {
          const t = app[id];
          const list = Object.keys(t.parts).sort().map(n => { const x = t.parts[n]; parts++; if (on(x)) ons++; if (x.override === false) offs++; return { name: n, kind: x.kind, on: on(x), off: x.override === false }; });
          return { id: +id, name: t.name, categories: t.categories, website: t.website, parts: list };
        });
        return JSON.stringify({ ok: true, parts, on: ons, off: offs, trackers });
      },
      trackerPartsSet(pkg, idsJson, enable, token) {
        window.__set.push([pkg, idsJson, enable]);
        const ids = JSON.parse(idsJson), app = window.__parts[pkg] || {};
        const denied = !!window.__denied;
        window.__held.push(() => {
          if (denied) { window.onTrackerPartsSet(token, { ok: false, needsRoot: true, changed: 0, changedParts: [], output: 'Error: switching tracker parts on or off needs Super User (Root) or the system user (UID 1000).' }); return; }
          const changedParts = [];
          Object.keys(app).forEach(id => { if (ids.length && !ids.includes(+id)) return; Object.keys(app[id].parts).forEach(n => { const x = app[id].parts[n];
            if (enable ? x.override === false : on(x)) { x.override = enable ? null : false; changedParts.push(n); } }); });
          window.onTrackerPartsSet(token, { ok: true, changed: changedParts.length, changedParts, output: (enable ? 'Enabled ' : 'Disabled ') + changedParts.length + ' tracker parts' });
        });
        return 'started';
      },
      appActionBatch(action, json) {
        const pkgs = JSON.parse(json); window.__batch.push([action, pkgs]);
        // Disable Trackers: Alpha switches two parts off; Bravo one, and Android refuses another (a failed row that still changed a part).
        // Enable Trackers: nothing was switched off, so nothing changes (rows with no parts).
        const row = p => action !== 'trackers_off' ? { pkg: p, success: true, output: 'No tracker part was switched off, so nothing was enabled.', parts: [] }
          : p === 'com.example.bravo' ? { pkg: p, success: false, output: 'Disabled 1 tracker part:\n  Flurry: FlurryReceiver\nNot changed by Android (1):\n  Facebook Ads: AudienceNetworkActivity', parts: ['com.flurry.android.FlurryReceiver'] }
          : { pkg: p, success: true, output: 'Disabled 2 tracker parts:', parts: ['com.example.alpha.trk.A', 'com.example.alpha.trk.B'] };
        window.__held.push(() => window.onAppBatchDone({ ok: true, action, total: pkgs.length, rows: pkgs.map(row) }));
        return 'started';
      },
      appBatchCancel() {},
      executeAppAction(action, pkg) { window.__exec.push([action, pkg]); return 'OK: done'; },
      // the History's Undo of a tracker step: at once, with the whole answer; the parts named are switched where the "phone" has them and need it
      trackerActionResult(action, pkg) {
        window.__exec.push([action, pkg]);
        const m = /^trackers_(on|off):parts=(.*)$/.exec(action); if (!m) return JSON.stringify({ ok: false, output: 'Error: not a tracker action' });
        const enable = m[1] === 'on', app = window.__parts[pkg] || {}, changedParts = []; let gone = 0;
        m[2].split(',').forEach(n => {
          const t = Object.values(app).find(t => t.parts[n]); if (!t) { gone++; return; }
          const x = t.parts[n]; if (enable ? x.override === false : on(x)) { x.override = enable ? null : false; changedParts.push(n); }
        });
        return JSON.stringify({ ok: true, changed: changedParts.length, changedParts, output: (enable ? 'Enabled ' : 'Disabled ') + changedParts.length + ' tracker parts' + (gone ? '\n' + gone + ' parts are no longer in the app.' : '') });
      },
    };
  }, apps);
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  // waits for what the page shows (true), or gives up after 8 s (false: the check that follows prints what it saw)
  const until = (fn, arg) => page.waitForFunction(fn, arg, { timeout: 8000, polling: 20 }).then(() => true, () => false);
  const release = () => ev(() => window.__release());
  // a dialog is answered in Node (page.on('dialog')): wait here until that many have been seen, up to 8 s
  const dialogsUntil = async n => { for (let i = 0; i < 400 && dialogs.length < n; i++) await sleep(20); await ev(() => new Promise(r => setTimeout(r, 0))); };
  const openBox = async pkg => {
    await ev(p => openInspector(p), pkg); await until(() => !!document.getElementById('sheetTrk'));
    await ev(() => document.getElementById('sheetTrk').click()); await until(() => document.getElementById('trackersModal').classList.contains('show') && !!document.querySelector('#trackersBody .trk-ctl-sum'));
  };
  const closeBox = async () => { await ev(() => { closeTrackersInfo(); closeInspector(); }); await until(() => !document.getElementById('trackersModal').classList.contains('show')); };
  const gate = (open) => ev(o => {
    isPrivilegedActive = true; currentWorkingMode = 'shizuku';
    lastModesData = { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, sysPrivActive: o, rootUidActive: false, shizuku: { authorized: true, uid: o ? 1000 : 2000 } };
    trkOnModeChange();
  }, open);
  await ev(() => trackersLoadCached());

  // ---- locked: Shizuku as the ADB shell user (UID 2000)
  await gate(false);
  await openBox('com.example.alpha');
  const box = await ev(() => ({ body: document.getElementById('trackersBody').innerText, sum: (document.querySelector('#trackersBody .trk-ctl-sum') || {}).innerText,
    offAll: (() => { const e = document.getElementById('trkOffAll'); return e ? { locked: e.classList.contains('locked'), svg: !!e.querySelector('svg'), title: e.title } : null; })(),
    lockLine: (document.querySelector('#trackersBody .trk-ctl .pm-lock') || {}).innerText, perTracker: [...document.querySelectorAll('#trackersBody .trk-parts-head .perm-toggle-btn')].map(x => x.innerText.trim()) }));
  check('1. the box sums up the tracker parts of the app: 4 parts, none switched off', box.sum === '4 tracker parts · 0 switched off', box.sum);
  check('   under each tracker its parts with their kind; a part the app ships off says so', /AnalyticsConnectorRegistrar\s*service/i.test(box.body) && /AppMeasurementReceiver\s*receiver/i.test(box.body) && /AppMeasurementJobService\s*service\s*off by default/i.test(box.body) && /AudienceNetworkActivity\s*screen/i.test(box.body), box.body);
  check('   a tracker found only by its parts (Facebook Ads) is listed too; one with no part of its own (Google AdMob) says it runs inside the app\'s code', /Facebook Ads/.test(box.body) && /Google AdMob[\s\S]*No part of its own to switch off: it runs inside the app's code\./.test(box.body), box.body);
  check('2. off the gate the two switches and each tracker\'s switch carry the lock, with the reason', box.offAll && box.offAll.locked && box.offAll.svg && box.offAll.title === 'Needs Super User (Root)' && box.lockLine === 'Needs Super User (Root)' && box.perTracker.length === 2 && box.perTracker.every(t => /DISABLE/.test(t)), JSON.stringify(box));
  dialogs.length = 0; answer = false;
  await ev(() => document.getElementById('trkOffAll').click()); await dialogsUntil(1);
  check('   a tap offers Root Features (the Super User check) and sends nothing', dialogs.length === 1 && /Switching trackers off or on needs Super User/.test(dialogs[0]) && (await ev(() => window.__set.length)) === 0, JSON.stringify(dialogs));

  // ---- unlocked: Shizuku as the system user (UID 1000)
  await gate(true);
  const un = await ev(() => ({ off: document.getElementById('trkOffAll').disabled, on: document.getElementById('trkOnAll').disabled, locked: !!document.querySelector('#trackersBody .locked'), lock: !!document.querySelector('#trackersBody .trk-ctl .pm-lock') }));
  check('3. with the system user (UID 1000) the box unlocks at once: Disable all on, Enable all off (nothing is switched off yet)', !un.off && un.on && !un.locked && !un.lock, JSON.stringify(un));
  await ev(() => document.getElementById('trkOffAll').click());
  const run = await ev(() => ({ set: JSON.stringify(window.__set), busy: document.getElementById('trkOffAll').disabled && document.getElementById('trkOnAll').disabled, toast: document.getElementById('toastMsg').innerText }));
  check('4. Disable all trackers asks the app for every tracker of the app (no ids) and shows that it is working', run.set === '[["com.example.alpha","[]",false]]' && run.busy && /Disabling tracker parts/.test(run.toast), JSON.stringify(run));
  await release(); await until(() => /3 switched off/.test(document.querySelector('#trackersBody .trk-ctl-sum').innerText));
  const after = await ev(() => ({ sum: document.querySelector('#trackersBody .trk-ctl-sum').innerText, toast: document.getElementById('toastMsg').innerText, off: document.getElementById('trkOffAll').disabled, on: document.getElementById('trkOnAll').disabled,
    tbtn: [...document.querySelectorAll('#trackersBody .trk-parts-head .perm-toggle-btn')].map(x => x.innerText.trim()), hist: (({ title, items }) => ({ title, items }))(getHistory()[0] || {}) }));
  check('   the answer is read back from the phone: 3 parts switched off, the toast says so, Enable all is on and Disable all off', after.sum === '4 tracker parts · 3 switched off' && after.toast === 'Disabled 3 tracker parts' && after.off && !after.on, JSON.stringify(after));
  check('   each tracker\'s switch now reads ENABLE', after.tbtn.join() === 'ENABLE,ENABLE', after.tbtn.join());
  check('   one History step with Undo: "Disabled trackers in Alpha"', after.hist.title === 'Disabled trackers in Alpha' && after.hist.items.length === 1 && after.hist.items[0].action === 'trackers_off' && after.hist.items[0].success, JSON.stringify(after.hist));
  check('   the step keeps the 3 parts it switched off (not the one the app ships off): its Undo switches back exactly those',
    JSON.stringify(after.hist.items[0].parts) === JSON.stringify(['com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar', 'com.google.android.gms.measurement.AppMeasurementReceiver', 'com.facebook.ads.AudienceNetworkActivity']),
    JSON.stringify(after.hist.items[0].parts));

  // one tracker's own switch
  await ev(() => document.querySelector('#trackersBody .perm-toggle-btn[data-trk="70"]').click());
  await release(); await until(() => /2 switched off/.test(document.querySelector('#trackersBody .trk-ctl-sum').innerText));
  const one = await ev(() => ({ set: JSON.stringify(window.__set[window.__set.length - 1]), sum: document.querySelector('#trackersBody .trk-ctl-sum').innerText, hist: getHistory().length }));
  check('5. a tracker\'s own ENABLE switches only its parts back on (ids [70]); it is not a History step of its own', one.set === '["com.example.alpha","[70]",true]' && one.sum === '4 tracker parts · 2 switched off' && one.hist === 1, JSON.stringify(one));

  // a refusal from Android
  await ev(() => { window.__denied = true; document.getElementById('trkOnAll').click(); });
  await release(); await until(() => document.getElementById('commandResultsModal').classList.contains('show'));
  const den = await ev(() => ({ toast: document.getElementById('toastMsg').innerText, res: document.getElementById('commandResultsModal').classList.contains('show'), out: document.getElementById('commandResultsModal').innerText }));
  check('6. when the app refuses (the gate on the Java side), the toast names the reason and the results show the answer', den.toast === 'Needs Super User (Root)' && den.res && /needs Super User \(Root\) or the system user \(UID 1000\)/.test(den.out), JSON.stringify(den).slice(0, 300));
  check('   a refused switch that changed nothing is no History step', (await ev(() => getHistory().length)) === 1);
  await ev(() => { window.__denied = false; document.getElementById('commandResultsModal').classList.remove('show'); });

  // 360 px: nothing wider than the screen
  await page.setViewportSize({ width: 360, height: 760 }); await until(() => innerWidth === 360);
  const geo = await ev(() => ({ wide: document.documentElement.scrollWidth - innerWidth, btns: ['trkOffAll', 'trkOnAll'].map(id => Math.round(document.getElementById(id).getBoundingClientRect().height)) }));
  check('7. at 360 px the box fits the screen and its buttons are at least 36 px tall', geo.wide <= 0 && geo.btns.every(h => h >= 36), JSON.stringify(geo));
  await page.setViewportSize({ width: 400, height: 860 }); await until(() => innerWidth === 400);
  await closeBox();

  // a system app asks first
  await openBox('com.android.sysapp');
  dialogs.length = 0; answer = false;
  const before = await ev(() => window.__set.length);
  await ev(() => document.getElementById('trkOffAll').click()); await dialogsUntil(1);
  check('8. switching off parts of a system app asks first (it can break the app or the system UI); a no sends nothing', dialogs.length === 1 && /system app com\.android\.sysapp/.test(dialogs[0]) && /break the app or the system UI/.test(dialogs[0]) && (await ev(() => window.__set.length)) === before, JSON.stringify(dialogs));
  await closeBox();

  // ---- the batch menu
  await gate(false);
  await ev(() => { selectedPkgs.add('com.example.alpha'); selectedPkgs.add('com.example.bravo'); batchPanelExpanded = true; updateBatchBar(); });
  await until(() => !!document.getElementById('batchTrkOff').getClientRects().length);
  const bl = await ev(() => ['batchTrkOff', 'batchTrkOn'].map(id => { const e = document.getElementById(id); return { text: e.innerText.trim(), svg: !!e.querySelector('svg'), title: e.title, shown: !!e.getClientRects().length }; }));
  check('9. the batch menu has Disable Trackers and Enable Trackers, with the lock while the switch is locked', bl[0].text === 'Disable Trackers' && bl[1].text === 'Enable Trackers' && bl.every(x => x.svg && x.shown && x.title === 'Needs Super User (Root)'), JSON.stringify(bl));
  dialogs.length = 0; answer = false;
  await ev(() => document.getElementById('batchTrkOff').click()); await dialogsUntil(1);
  check('   off the gate a tap goes the Root way and runs nothing', dialogs.length === 1 && (await ev(() => window.__batch.length)) === 0 && !(await ev(() => document.getElementById('batchConfirmModal').classList.contains('show'))), JSON.stringify(dialogs));
  await gate(true);
  check('   the lock goes when the gate opens', await ev(() => !document.querySelector('#batchTrkOff svg') && !document.getElementById('batchTrkOff').title));
  await ev(() => document.getElementById('batchTrkOff').click()); await until(() => document.getElementById('batchConfirmModal').classList.contains('show'));
  const conf = await ev(() => ({ open: document.getElementById('batchConfirmModal').classList.contains('show'), title: document.getElementById('batchConfirmTitle').innerText, sub: document.getElementById('batchConfirmSubtitle').innerText, btn: document.getElementById('batchConfirmExecuteBtn').innerText }));
  check('10. Disable Trackers is confirmed first, on the batch confirmation sheet', conf.open && conf.title === 'Confirm Disable Trackers' && conf.btn === 'Disable Trackers' && /^2 apps selected • their tracker parts are switched off \(Enable Trackers switches them back on\)$/.test(conf.sub), JSON.stringify(conf));
  await ev(() => confirmPendingBatchAction()); await until(() => window.__held.length > 0);
  await release(); await until(() => document.getElementById('commandResultsModal').classList.contains('show'));
  const res = await ev(() => ({ batch: JSON.stringify(window.__batch), sub: document.getElementById('commandResultsModal').innerText, hist: (({ title, items }) => ({ title, items }))(getHistory()[0] || {}) }));
  check('    then runs in the native batch runner, with results titled DISABLE TRACKERS and one History step with Undo', res.batch === '[["trackers_off",["com.example.alpha","com.example.bravo"]]]' && /DISABLE TRACKERS • 2 of 2 apps/.test(res.sub) && res.hist.title === 'Disabled trackers in 2 apps' && res.hist.items.every(i => i.action === 'trackers_off'), JSON.stringify(res).slice(0, 400));
  check('    each app of the step keeps the parts its run changed, a failed run too (Bravo: one part changed, one refused)',
    JSON.stringify(res.hist.items.map(i => [i.pkg, i.success, i.parts])) === JSON.stringify([['com.example.alpha', true, ['com.example.alpha.trk.A', 'com.example.alpha.trk.B']], ['com.example.bravo', false, ['com.flurry.android.FlurryReceiver']]]),
    JSON.stringify(res.hist.items));
  await ev(() => document.getElementById('commandResultsModal').classList.remove('show'));
  await ev(() => { selectedPkgs.clear(); selectedPkgs.add('com.example.alpha'); selectedPkgs.add('com.android.sysapp'); batchPanelExpanded = true; updateBatchBar(); });
  await ev(() => document.getElementById('batchTrkOff').click()); await until(() => document.getElementById('batchConfirmModal').classList.contains('show'));
  check('    with a system app selected, the note says how many and what it risks', /• system apps among them: 1 \(switching their parts off can break them or the system UI\)$/.test(await ev(() => document.getElementById('batchConfirmSubtitle').innerText)));
  await ev(() => { pendingBatchAction = null; document.getElementById('batchConfirmModal').classList.remove('show'); });
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); document.getElementById('batchTrkOn').click(); });
  await until(() => window.__held.length > 0); await release(); await until(() => document.getElementById('commandResultsModal').classList.contains('show'));
  check('11. Enable Trackers runs at once (as Enable does after Freeze), results titled ENABLE TRACKERS', (await ev(() => JSON.stringify(window.__batch[window.__batch.length - 1]))) === '["trackers_on",["com.example.alpha","com.android.sysapp"]]' && /ENABLE TRACKERS • 2 of 2 apps/.test(await ev(() => document.getElementById('commandResultsModal').innerText)));
  const noUndo = await ev(() => { const e = getHistory()[0]; renderHistory(); return { title: e.title, undo: e.items.map(histUndoAction), btn: !!document.querySelector('#historyList .hist-entry:first-child [data-id]') }; });
  check('    a step that changed no part has nothing to undo: no Undo button', noUndo.title === 'Enabled trackers in 2 apps' && noUndo.undo.join('') === '' && !noUndo.btn, JSON.stringify(noUndo));
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); clearBatchSelection(); });

  // ---- Undo from the History: the other switch, on exactly the parts the step changed
  const id = await ev(() => getHistory().find(e => e.title === 'Disabled trackers in Alpha').id);
  // off the gate (Shizuku as the shell user) a tracker step's Undo goes the Root way first, from over the History, and runs nothing
  await gate(false);
  await ev(() => { openHistoryModal(); }); await until(() => document.getElementById('historyModal').classList.contains('show'));
  dialogs.length = 0; answer = false;
  await ev(i => undoHistory(i), id); await dialogsUntil(1);
  check('12. off the gate, Undo of a tracker step offers Root Features and runs nothing', dialogs.length === 1 && /Switching trackers off or on needs Super User/.test(dialogs[0])
    && (await ev(() => window.__exec.length)) === 0, JSON.stringify(dialogs));
  answer = true; dialogs.length = 0;
  await ev(i => undoHistory(i), id); await dialogsUntil(1); await until(() => document.getElementById('historyModal').classList.contains('show') === false);
  const yes = await ev(() => ({ v: currentViewName(), h: document.getElementById('historyModal').classList.contains('show'), n: window.__exec.length }));
  check('    a yes opens Root Features with the History closed (it would cover the tab), and still runs nothing', yes.v === 'rootfeatures' && !yes.h && yes.n === 0, JSON.stringify({ yes, dialogs }));
  await gate(true); await ev(() => switchView('apps'));
  // on the gate: ENABLE under Facebook Ads (check 5) already switched one of the step's 3 parts back on, so the Undo changes 2
  await ev(i => undoHistory(i), id); await until(() => window.__exec.length > 0 && document.getElementById('commandResultsModal').classList.contains('show'));
  const ex1 = await ev(() => JSON.stringify(window.__exec));
  check('    on the gate, Undo of "Disabled trackers in Alpha" switches back on the 3 parts that step switched off, and nothing else',
    ex1 === JSON.stringify([['trackers_on:parts=com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar,com.google.android.gms.measurement.AppMeasurementReceiver,com.facebook.ads.AudienceNetworkActivity', 'com.example.alpha']]), ex1);
  const u1 = await ev(() => { const e = getHistory()[0]; return { title: e.title, items: e.items.map(i => [i.action, i.success, i.parts]), undo: e.items.map(histUndoAction) }; });
  check('    the Undo step keeps the 2 parts the Undo really switched on (not the one ENABLE had already switched on), and its own Undo names only those',
    u1.title === 'Undo: Disabled trackers in Alpha' && JSON.stringify(u1.items) === JSON.stringify([['trackers_on', true, ['com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar', 'com.google.android.gms.measurement.AppMeasurementReceiver']]])
    && u1.undo[0] === 'trackers_off:parts=com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar,com.google.android.gms.measurement.AppMeasurementReceiver', JSON.stringify(u1));
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); window.__exec = []; });
  const id2 = await ev(() => getHistory().find(e => e.title === 'Disabled trackers in 2 apps').id);
  await ev(i => undoHistory(i), id2); await until(() => window.__exec.length > 1 && document.getElementById('commandResultsModal').classList.contains('show'));
  const ex2 = await ev(() => JSON.stringify(window.__exec));
  check('    Undo of the batch step does so per app, Bravo\'s one changed part too though its run failed (newest first)',
    ex2 === JSON.stringify([['trackers_on:parts=com.flurry.android.FlurryReceiver', 'com.example.bravo'], ['trackers_on:parts=com.example.alpha.trk.A,com.example.alpha.trk.B', 'com.example.alpha']]), ex2);
  const u2 = await ev(() => { const e = getHistory()[0]; renderHistory(); return { title: e.title, undo: e.items.map(histUndoAction).join(''), res: document.getElementById('commandResultsModal').innerText, btn: !!document.querySelector('#historyList .hist-entry:first-child [data-id]') }; });
  check('    parts the app no longer has are said in the results; that Undo changed nothing, so it has no Undo of its own',
    u2.title === 'Undo: Disabled trackers in 2 apps' && u2.undo === '' && !u2.btn && /parts are no longer in the app/.test(u2.res), JSON.stringify(u2).slice(0, 400));
  answer = false;
  await ev(() => { document.getElementById('commandResultsModal').classList.remove('show'); closeHistoryModal(); });

  // ---- Root Features: the row
  await gate(false);
  await ev(() => switchView('rootfeatures')); await until(() => [...document.querySelectorAll('#rfFeatureList .rf-row')].some(r => /Trackers in apps/.test(r.textContent)));
  const rl = await ev(() => { const n = [...document.querySelectorAll('#rfFeatureList .rf-row')].find(r => /Trackers in apps/.test(r.textContent)); return n ? { locked: n.classList.contains('is-locked'), text: n.innerText } : null; });
  check('13. Root Features has a "Trackers in apps" row, locked off the gate', rl && rl.locked && /Switch off the tracker parts \(ads, analytics\) of any app\./.test(rl.text), JSON.stringify(rl));
  await gate(true); await ev(() => renderRootFeatures()); await until(() => !!document.getElementById('rfTrackersBtn'));
  check('    and open with the gate', await ev(() => !!document.getElementById('rfTrackersBtn')));
  await ev(() => document.getElementById('rfTrackersBtn').click()); await until(() => currentViewName() === 'apps');
  const go = await ev(() => ({ view: currentViewName(), f: activeFilters.has('trackers'), toast: document.getElementById('toastMsg').innerText }));
  check('    Open apps lands on the Apps tab with the Trackers filter on, and says where the switches are', go.view === 'apps' && go.f && /TRACKERS chip/.test(go.toast), JSON.stringify(go));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
