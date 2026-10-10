// Profiles: apply plan per app state, refused saves rolled back; APK extraction one at a time, no share sheet
const { chromium, PAGE } = require('./lib/pw');
const appBatchMock = require('./lib/appbatch_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const sleep = ms => page.waitForTimeout(ms);
  await page.addInitScript(() => {
    window.__calls = []; window.__store = {}; window.__saveOk = true; window.__extractCbs = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore(k, v) { if (!window.__saveOk) return false; window.__store[k] = v; return true; }, loadStore(k) { return window.__store[k] || ''; },
      loadPackages() { return JSON.stringify(window.__apps); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; }, getAppSizes() { return '{}'; },
      executeAppAction(a, p) { window.__calls.push(a + ':' + p); return 'Success'; }, executeShell() { return ''; },
      extractApk(p) { window.__calls.push('extract:' + p); window.__extractCbs.push(p); }, shareStoredFile(r, m, n) { window.__calls.push('share:' + n); return ''; },
      shareTextFile() { return ''; }, copyToClipboard() {},
    };
    window.__apps = [
      { pkg: 'a.suspended', name: 'A', isSystem: true, isSuspended: true },
      { pkg: 'b.frozen', name: 'B', isSystem: true, isFrozen: true },
      { pkg: 'c.uninstalled', name: 'C', isSystem: true, isUninstalled: true },
      { pkg: 'd.enabled', name: 'D', isSystem: true },
      { pkg: 'e.both', name: 'E', isSystem: true, isFrozen: true, isSuspended: true },
      { pkg: 'f.frozenSusp', name: 'F', isSystem: true, isFrozen: true, isSuspended: true },
    ];
  });
  await page.addInitScript(appBatchMock.installAppBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(500);
  const plan = (entries) => page.evaluate(es => { const p = profilePlan({ apps: es }); const o = {}; PLAN_ORDER.forEach(k => { if (p.steps[k].length) o[k] = p.steps[k]; }); return JSON.stringify({ steps: o, same: p.same, missing: p.missing, apps: p.apps }); }, entries);

  // --- the review finding: mismatched states must produce steps, not count as "already matching" ---
  console.log('disabled wanted, app is suspended :', await plan([{ pkg: 'a.suspended', state: 'disabled' }]));
  console.log('suspended wanted, app is disabled :', await plan([{ pkg: 'b.frozen', state: 'suspended' }]));
  console.log('disabled wanted, app is uninstalled:', await plan([{ pkg: 'c.uninstalled', state: 'disabled' }]));
  console.log('suspended wanted, app uninstalled :', await plan([{ pkg: 'c.uninstalled', state: 'suspended' }]));
  console.log('uninstalled wanted, app is disabled:', await plan([{ pkg: 'b.frozen', state: 'uninstalled' }]));
  console.log('already matching (legacy states)  :', await plan([{ pkg: 'b.frozen', state: 'disabled' }, { pkg: 'a.suspended', state: 'suspended' }, { pkg: 'c.uninstalled', state: 'uninstalled' }]));
  console.log('suspended wanted, app frozen+susp  :', await plan([{ pkg: 'e.both', state: 'suspended' }]));
  console.log('flags: frozen+suspended both       :', await plan([{ pkg: 'd.enabled', state: 'suspended', frozen: true, suspended: true }]));
  console.log('flags: frozen only, app frozen+susp:', await plan([{ pkg: 'f.frozenSusp', state: 'disabled', frozen: true, suspended: false }]));
  console.log('flags: susp only, app frozen+susp  :', await plan([{ pkg: 'f.frozenSusp', state: 'suspended', frozen: false, suspended: true }]));
  console.log('not on this phone                  :', await plan([{ pkg: 'zz.none', state: 'disabled' }]));

  // apply runs the steps in order
  await page.evaluate(() => { profiles = [{ id: 'p', name: 'P', created: 1, apps: [{ pkg: 'a.suspended', name: 'A', state: 'disabled' }, { pkg: 'c.uninstalled', name: 'C', state: 'disabled' }, { pkg: 'd.enabled', name: 'D', state: 'uninstalled' }] }]; openProfiles(); previewProfile('p'); });
  console.log('preview:', (await page.innerText('#profilePreview')).replace(/\s+/g, ' ').slice(0, 150));
  await page.evaluate(() => { window.__calls.length = 0; applyProfile(); });
  await sleep(400);
  console.log('apply order:', JSON.stringify(await page.evaluate(() => window.__calls)));
  console.log('sheet stays open behind the result dialog (not closed first):', await page.evaluate(() => document.getElementById('profilesModal').classList.contains('show')));
  await page.evaluate(() => closeCommandResultsModal());
  await page.evaluate(() => closeProfiles());

  // --- saving that the app could not keep is reported and rolled back ---
  await page.evaluate(() => { profiles = []; window.__saveOk = false; openProfiles(); });
  await page.fill('#profileName', 'Too big'); await page.evaluate(() => saveProfile());
  console.log('save refused → list size:', await page.evaluate(() => profiles.length), '| toast:', await page.locator('#toast, .toast').first().innerText().catch(() => '(none)'));
  await page.evaluate(() => { window.__saveOk = true; });
  await page.fill('#profileName', 'Fits'); await page.evaluate(() => saveProfile());
  console.log('save ok → list size:', await page.evaluate(() => profiles.length), '| stored:', await page.evaluate(() => !!window.__store.profiles));
  await page.evaluate(() => { window.__saveOk = false; const id = profiles[0].id; deleteProfile(id); });
  console.log('delete refused → still there:', await page.evaluate(() => profiles.length === 1));
  await page.evaluate(() => { window.__saveOk = false; toggleWatchProfile(profiles[0].id); });
  console.log('watch refused → not watching:', await page.evaluate(() => !profiles[0].watch));
  await page.evaluate(() => { document.getElementById('profileImport').value = JSON.stringify({ format: 'adb-app-manager-profile', v: 1, name: 'Imp', apps: [{ pkg: 'x.y', name: 'X', state: 'disabled', frozen: true, suspended: false }] }); importProfile(); });
  console.log('import refused → size:', await page.evaluate(() => profiles.length));
  await page.evaluate(() => { window.__saveOk = true; importProfile(); });
  console.log('import ok keeps flags:', await page.evaluate(() => JSON.stringify(profiles[0].apps[0])));
  await page.evaluate(() => closeProfiles());

  // --- extractions cannot interleave, and the extract flow never opens the share sheet ---
  const extractCalls = () => page.evaluate(() => JSON.stringify(window.__calls.filter(c => c.startsWith('extract'))));
  const shareCalls = () => page.evaluate(() => JSON.stringify(window.__calls.filter(c => c.startsWith('share'))));
  await page.evaluate(() => openInspector('b.frozen')); await page.waitForTimeout(250);
  await page.evaluate(() => { extractApkUI(); extractApkUI(); });
  console.log('second tap while extracting is ignored:', await extractCalls());
  console.log('and says why:', await page.locator('#toastMsg').innerText());
  await page.evaluate(() => onApkExtracted(JSON.stringify({ ok: true, pkg: 'b.frozen', path: 'Download/x/B.apk', ref: 'content://m/1', mime: 'application/vnd.android.package-archive', bytes: 5, splits: 1 })));
  console.log('extract finished → saved toast, nothing shared:', await page.locator('#toastMsg').innerText(), '|', await shareCalls());
  await page.evaluate(() => { extractApkUI(); });
  console.log('next tap after the result extracts again:', await extractCalls());
  await page.evaluate(() => onApkExtracted(JSON.stringify({ ok: false, pkg: 'b.frozen', error: 'APK not readable' })));
  console.log('failed extract → toast, nothing shared:', await page.locator('#toastMsg').innerText(), '|', await shareCalls());
  await page.evaluate(() => { extractApkUI(); });
  console.log('and again after a failure:', await extractCalls());
  await page.evaluate(() => onApkExtracted(JSON.stringify({ ok: true, pkg: 'b.frozen', path: 'Download/x/B.apks', ref: 'content://m/3', mime: 'application/octet-stream', bytes: 9, splits: 3 })));
  console.log('split app → install hint, nothing shared:', await page.locator('#toastMsg').innerText(), '|', await shareCalls());
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
