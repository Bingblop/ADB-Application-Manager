// Quick list sync: editing or deleting the active list updates the native side; profile import dedupes packages
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = []; window.__store = {};
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore(k, v) { window.__store[k] = v; return true; }, loadStore(k) { return window.__store[k] || ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A', isSystem: true, isFrozen: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      setQuickList(id) { window.__calls.push('quicklist:' + JSON.stringify(id)); },
      getQuickList() { return 'listA'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);

  // ---- editing the active quick list's packages must re-nudge the native side (so the widget label refreshes) ----
  await page.evaluate(() => {
    customLists = [ { id: 'listA', name: 'List A', description: '', packages: ['com.a'], updatedAt: 1 } ];
    quickListId = 'listA';
    document.getElementById('listEditorId').value = 'listA';
    document.getElementById('listEditorName').value = 'List A';
    document.getElementById('listEditorDesc').value = '';
    document.getElementById('listEditorPackages').value = 'com.a\ncom.b';
  });
  await page.evaluate(() => saveListFromEditor());
  console.log('edit active quick list -> bridge calls:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('edited list now has 2 packages:', await page.evaluate(() => customLists.find(l => l.id === 'listA').packages.length));

  // editing a list that is NOT the active quick list must not call setQuickList again
  await page.evaluate(() => { window.__calls.length = 0; quickListId = 'listA'; customLists.push({ id: 'listB', name: 'List B', description: '', packages: ['com.c'], updatedAt: 1 }); });
  await page.evaluate(() => {
    document.getElementById('listEditorId').value = 'listB';
    document.getElementById('listEditorName').value = 'List B';
    document.getElementById('listEditorPackages').value = 'com.c\ncom.d';
  });
  await page.evaluate(() => saveListFromEditor());
  console.log('edit a DIFFERENT (non-active) list -> bridge calls (should be empty):', JSON.stringify(await page.evaluate(() => window.__calls)));

  // ---- deleting the active quick list must clear quickListId and tell the native side ----
  await page.evaluate(() => { window.__calls.length = 0; quickListId = 'listA'; pendingDeleteListId = 'listA'; });
  await page.evaluate(() => confirmDeleteList());
  console.log('delete active quick list -> bridge calls:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('quickListId cleared:', await page.evaluate(() => quickListId === ''));

  // deleting a list that is NOT the active quick list must leave quickListId alone
  await page.evaluate(() => { window.__calls.length = 0; customLists = [{ id: 'listA', name: 'A', packages: [], updatedAt: 1 }, { id: 'listB', name: 'B', packages: [], updatedAt: 1 }]; quickListId = 'listA'; pendingDeleteListId = 'listB'; });
  await page.evaluate(() => confirmDeleteList());
  console.log('delete a DIFFERENT (non-active) list -> bridge calls (should be empty):', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('quickListId unchanged:', await page.evaluate(() => quickListId === 'listA'));

  // ---- importing a profile with a duplicate package keeps exactly one entry for it ----
  await page.evaluate(() => {
    profiles = [];
    const dup = JSON.stringify({ format: 'adb-app-manager-profile', name: 'Dup test', apps: [
      { pkg: 'com.a', name: 'A', state: 'disabled' },
      { pkg: 'com.b', name: 'B', state: 'suspended' },
      { pkg: 'com.a', name: 'A', state: 'uninstalled' }, // same package again, different (incompatible) state
    ]});
    document.getElementById('profileImport').value = dup;
  });
  await page.evaluate(() => importProfile());
  const imported = await page.evaluate(() => profiles[0] && profiles[0].apps);
  console.log('imported profile apps:', JSON.stringify(imported));
  console.log('exactly one entry for the duplicated package:', imported.filter(a => a.pkg === 'com.a').length === 1);
  console.log('the surviving entry is the LAST one listed (uninstalled, not disabled):', imported.find(a => a.pkg === 'com.a').state === 'uninstalled');
  console.log('total apps after dedup is 2, not 3:', imported.length === 2);

  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
