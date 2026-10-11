// Batch actions: live progress (per-app name/package + fill bar) and the Stop button, including the sheet's
// own close (✕ / tap-outside) having the same halting effect as Stop while a run is active, instead of
// silently hiding the dialog while the remaining apps keep running unseen.
const { chromium, PAGE } = require('./lib/pw');
const appBatchMock = require('./lib/appbatch_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const apps = [
      { pkg: 'com.a', name: 'App A', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
      { pkg: 'com.b', name: 'App B', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
      { pkg: 'com.c', name: 'App C', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
      { pkg: 'com.d', name: 'App D', isSystem: false, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false },
    ];
    // Only the 2nd app's "action" takes real wall-clock time (a privileged shell round-trip) - that is what
    // gives Stop a real, comfortably wide window to land in between apps #1 and #3, without the test having to
    // guess exactly which step a fixed sleep lands in. __seen is captured from INSIDE the mock, at the moment
    // each app's action is actually invoked, so it records what the progress UI showed for that app with no
    // timing race of its own - and its final length/contents are what prove whether a later app ever ran at all.
    window.__seen = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify(apps); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      executeAppAction(action, pkg) {
        window.__seen.push({
          pkg: pkg,
          text: document.getElementById('batchProgressText').innerText,
          bar: document.getElementById('batchProgressBar').style.width,
        });
        if (pkg === 'com.b') { const end = Date.now() + 150; while (Date.now() < end) {} }
        if (pkg === 'com.c') return 'Error: permission denied';
        return `Package ${pkg} ${action} ok`;
      },
    };
  });
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const $eval = fn => page.evaluate(fn);

  const progress = () => page.evaluate(() => ({
    modalShown: document.getElementById('batchConfirmModal').classList.contains('show'),
    confirmBodyHidden: document.getElementById('batchConfirmBody').style.display === 'none',
    progressBodyShown: document.getElementById('batchProgressBody').style.display !== 'none',
    title: document.getElementById('batchConfirmTitle').innerText,
    stopDisabled: document.getElementById('batchProgressStopBtn').disabled,
    stopLabel: document.getElementById('batchProgressStopBtn').innerText,
  }));
  const resultRows = () => page.evaluate(() => document.querySelectorAll('#commandResultsList .result-item').length);
  const resultFailedRows = () => page.evaluate(() => document.querySelectorAll('#commandResultsList .result-item.failed').length);
  const toast = () => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const subtitle = () => page.evaluate(() => document.getElementById('commandResultsSubtitle').innerText);
  const seen = () => page.evaluate(() => window.__seen);
  const resetSeen = () => page.evaluate(() => { window.__seen = []; });
  const selectAll = () => page.evaluate(() => { ['com.a', 'com.b', 'com.c', 'com.d'].forEach(toggleSelectPkg); batchKeepSelection = false; });       // (Keep selection is on by default since v7.10.16: these checks are about the other way)

  // ---------------------------------------------------------------- 1. normal run to completion
  await selectAll();
  await $eval(() => batchAction('force_stop'));

  const p0 = await progress();
  console.log('1. starting a batch action opens the modal straight into progress mode (confirm body hidden), with a header naming the action and count:', p0.modalShown && p0.progressBodyShown && p0.confirmBodyHidden && p0.title === 'Force-stopped 4 apps');

  await sleep(400);
  const after = await progress();
  const rows = await resultRows();
  const failedRows = await resultFailedRows();
  console.log('2. each app is processed in order, with the progress text naming it by name and package and the bar showing how many are already done (before that app\'s own action runs):', JSON.stringify(await seen()) === JSON.stringify([
    { pkg: 'com.a', text: '1 of 4: App A (com.a)', bar: '0%' },
    { pkg: 'com.b', text: '2 of 4: App B (com.b)', bar: '25%' },
    { pkg: 'com.c', text: '3 of 4: App C (com.c)', bar: '50%' },
    { pkg: 'com.d', text: '4 of 4: App D (com.d)', bar: '75%' },
  ]));
  console.log('3. once every app has run, the modal closes on its own and the results dialog reports the full count with no "stopped" caveat:', !after.modalShown && (await subtitle()) === 'FORCE_STOP • 4 of 4 apps' && rows === 4 && failedRows === 1);
  console.log('   the toast tallies success vs failure from each app\'s own result, not just "done":', (await toast()) === 'Batch Done: 3 succeeded, 1 failed');
  console.log('   the selection is cleared afterwards (default: do not keep selection running):', (await page.evaluate(() => selectedPkgs.size)) === 0);

  // ---------------------------------------------------------------- 2. the Stop button ends it early
  await resetSeen();
  await selectAll();
  await $eval(() => batchAction('force_stop'));   // app #1 (com.a) already ran synchronously by the time this resolves
  await sleep(30);                                 // lands comfortably inside app #2's (com.b) own 150ms action
  await $eval(() => batchActionStop());

  await sleep(500);
  const stoppedFinal = await progress();
  const seenStopped = await seen();
  console.log('4. no app after the one already in flight when Stop was pressed is ever even started:', seenStopped.length === 2 && seenStopped[0].pkg === 'com.a' && seenStopped[1].pkg === 'com.b');
  console.log('5. the Stop button itself ends up disabled and relabelled, and the sheet closes once that last app finishes:', stoppedFinal.stopDisabled && stoppedFinal.stopLabel === 'Stopping…' && !stoppedFinal.modalShown);
  console.log('   the toast and the results header both say what was and was not covered:', (await toast()) === 'Stopped: 2 succeeded, 0 failed, 2 not run' && (await subtitle()) === 'FORCE_STOP • 2 of 4 apps (stopped)');
  console.log('   only the apps that actually ran appear in the results list:', (await resultRows()) === 2);

  // ---------------------------------------------------------------- 3. closing the sheet mid-run has the same effect as Stop
  await page.evaluate(() => closeCommandResultsModal());
  await resetSeen();
  await selectAll();
  await $eval(() => batchAction('force_stop'));
  await sleep(30);
  await $eval(() => closeBatchConfirmModal());     // the ✕ / tap-outside handler - redirected into Stop while a run is active

  await sleep(500);
  const seenClosed = await seen();
  console.log('6. closing the sheet mid-run is not a silent hide - the remaining apps never run, same as pressing Stop directly:', seenClosed.length === 2 && seenClosed[1].pkg === 'com.b');
  console.log('   it settles into the same stopped-run outcome (2 ran, 2 skipped):', (await toast()) === 'Stopped: 2 succeeded, 0 failed, 2 not run' && (await subtitle()) === 'FORCE_STOP • 2 of 4 apps (stopped)' && !(await progress()).modalShown);

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
