// Batch selection: floating button vs expanded sheet, collapse, clear, reset after an action or list recall
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
    ];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify(apps); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
      executeAppAction(action, pkg) { return `Package ${pkg} ${action} ok`; },
    };
  });
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(400);

  // Both the FAB and the sheet reveal via a CSS transition (opacity/transform), so give it a
  // moment to settle before reading rendered visibility - otherwise we can sample mid-transition.
  const state = async () => {
    await page.waitForTimeout(350);
    return {
      fab: await page.isVisible('#batchFab.show'),
      sheet: await page.isVisible('#floatingBatchBar.show'),
      badge: await page.locator('#batchFabBadge').innerText(),
      count: await page.locator('#batchCountText').innerText(),
    };
  };

  console.log('0. nothing selected:', JSON.stringify(await state()));

  await page.evaluate(() => toggleSelectPkg('com.a'));
  console.log('1. one selected -> FAB only:', JSON.stringify(await state()));

  await page.evaluate(() => toggleSelectPkg('com.b'));
  console.log('2. two selected -> FAB badge updates, still collapsed:', JSON.stringify(await state()));

  await page.click('#batchFab');
  console.log('3. tapped FAB -> panel expands, FAB hides:', JSON.stringify(await state()));

  // Simulate selecting one more app while the panel is already expanded (scrolled up to the list).
  await page.evaluate(() => toggleSelectPkg('com.c'));
  console.log('4. selected a 3rd app while expanded -> stays expanded, count updates:', JSON.stringify(await state()));

  await page.click('#floatingBatchBar .batch-sheet-tools [aria-label="Close the batch menu"]');
  console.log('5. tapped collapse -> back to FAB, selection kept:', JSON.stringify(await state()));

  await page.click('#batchFab');
  console.log('6. re-tapped FAB -> expands again:', JSON.stringify(await state()));

  await page.click('#floatingBatchBar button.batch-tool-link:has-text("Clear")');
  console.log('7. cleared -> both hidden:', JSON.stringify(await state()));

  await page.evaluate(() => toggleSelectPkg('com.a'));
  console.log('8. selecting again after clear -> starts collapsed (FAB), not expanded:', JSON.stringify(await state()));

  // A completed batch action clears the selection; next selection should again start collapsed.
  await page.evaluate(() => { expandBatchPanel(); });
  await page.click('.batch-grid-btn:has-text("Force Stop")'); await page.waitForTimeout(600);
  await page.evaluate(() => closeCommandResultsModal());
  console.log('9. after batch action completes -> selection + panel reset:', JSON.stringify(await state()));
  await page.evaluate(() => toggleSelectPkg('com.b'));
  console.log('10. next selection after that -> collapsed again:', JSON.stringify(await state()));
  await page.evaluate(() => clearBatchSelection());

  // recallSavedList starts a fresh selection session - should also start collapsed even if a
  // previous session was left expanded.
  await page.evaluate(() => {
    customLists.push({ id: 'list_test', name: 'Test List', packages: ['com.a', 'com.b'] });
    toggleSelectPkg('com.c');
    expandBatchPanel();
  });
  console.log('11. before recall, panel expanded:', JSON.stringify(await state()));
  await page.evaluate(() => recallSavedList('list_test'));
  console.log('12. after recallSavedList -> fresh selection starts collapsed:', JSON.stringify(await state()));
  await page.evaluate(() => clearBatchSelection());

  // "Keep selection after running" - ticking it stops a batch action from clearing the selection, so another
  // action can run on the same apps right away; unticking it restores the normal clear-after-running behavior.
  await page.evaluate(() => { toggleSelectPkg('com.a'); toggleSelectPkg('com.b'); expandBatchPanel(); });
  await page.locator('.switch-row:has(#batchKeepSelectionToggle)').click();
  console.log('13. ticked "Keep selection after running":', await page.isChecked('#batchKeepSelectionToggle'));
  await page.click('.batch-grid-btn:has-text("Force Stop")'); await page.waitForTimeout(600);
  await page.evaluate(() => closeCommandResultsModal());
  console.log('14. after a batch action with it ticked -> selection kept, sheet still shown:', JSON.stringify(await state()));

  await page.locator('.switch-row:has(#batchKeepSelectionToggle)').click();
  console.log('15. unticked it:', await page.isChecked('#batchKeepSelectionToggle'));
  await page.click('.batch-grid-btn:has-text("Force Stop")'); await page.waitForTimeout(600);
  await page.evaluate(() => closeCommandResultsModal());
  console.log('16. after a batch action with it unticked -> selection + panel reset, same as before:', JSON.stringify(await state()));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
