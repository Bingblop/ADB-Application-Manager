// Inspector Activities and Components tabs: activities on their own, receivers / services / providers together, enable/disable, exported filter; single and batch dex optimization
// (async, off the page's thread, with a live progress bar - the single-app case shows a toast only on success,
// matching toggleComponent's "modal only on failure" convention; the batch case keeps its per-app breakdown).
const { chromium, PAGE } = require('./lib/pw');
const optimizeBatchMock = require('./lib/optimizebatch_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'X App', isSystem: true }, { pkg: 'com.y', name: 'Y App', isSystem: false }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() {
        return JSON.stringify({
          permissions: [], appopsRaw: '',
          activityInfo: [{ name: 'com.x.Main', exported: true, enabled: true, permission: '' }, { name: 'com.x.Hidden', exported: false, enabled: true, permission: '' }],
          receiverInfo: [{ name: 'com.x.BootRcv', exported: false, enabled: true, permission: 'android.permission.RECEIVE_BOOT_COMPLETED' }],
          serviceInfo: [{ name: 'com.x.Svc', exported: false, enabled: false, permission: '' }],
          providerInfo: [{ name: 'com.x.Provider', exported: true, enabled: true, permission: '', authority: 'com.x.files' }],
        });
      },
      executeAppAction(a, p) { window.__calls.push('action:' + a + ':' + p); return 'Success'; },
      setComponentEnabled(pkg, comp, enable) { window.__calls.push('toggle:' + comp + ':' + enable); return JSON.stringify({ ok: true, output: 'Component ' + comp + ' new state: ' + (enable ? 'enabled' : 'disabled') }); },
      getStandbyBucket(pkg) { if (arguments.length !== 1) throw new Error('Java bridge: wrong argument count'); window.__calls.push('sbget:' + pkg); if (window.__sbReadFail) return JSON.stringify({ ok: false, bucket: '', output: 'Error: the standby bucket needs ADB, Shizuku or Root.' }); return JSON.stringify({ ok: true, bucket: window.__sb || 'rare', output: window.__sb || 'rare' }); },
      setStandbyBucket(pkg, b) { if (arguments.length !== 2) throw new Error('Java bridge: wrong argument count'); window.__calls.push('sbset:' + pkg + ':' + b); if (window.__sbFail) return JSON.stringify({ ok: false, bucket: 'rare', output: 'The phone did not change it: it is in rare.' }); window.__sb = b; return JSON.stringify({ ok: true, bucket: b, output: b }); },
      optimizeApp(pkg, mode, force) { window.__calls.push('opt:' + pkg + ':' + mode + ':' + force); return JSON.stringify({ ok: true, output: 'Success' }); },
    };
  });
  await page.addInitScript(optimizeBatchMock.installOptimizeBatchMock);
  await page.goto(PAGE); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  const resultsShown = () => page.evaluate(() => document.getElementById('commandResultsModal').classList.contains('show'));

  await page.evaluate(() => openInspector('com.x'));
  await page.click('.sheet-tab-pill[data-tab="acts"]');
  await page.waitForTimeout(150);
  const actsTxt = await page.locator('#actsContainer').innerText();
  await page.click('.sheet-tab-pill[data-tab="comps"]');
  await page.waitForTimeout(150);
  const txt = await page.locator('#compsContainer').innerText();
  console.log('headers present:', 'ACTIVITIES=' + actsTxt.includes('ACTIVITIES'), ['RECEIVERS', 'SERVICES', 'PROVIDERS'].map(h => h + '=' + txt.includes(h)).join(' '));
  console.log('Activities tab holds only activities, Components tab none:', actsTxt.includes('Main') && !actsTxt.includes('Svc') && !actsTxt.includes('BootRcv') && !txt.includes('ACTIVITIES') && !txt.includes('Main'));
  console.log('tab counts (activities, components):', await page.evaluate(() => [document.getElementById('actsCount').innerText, document.getElementById('compsCount').innerText].join(' / ')));
  console.log('provider authority row rendered:', txt.includes('Provider'));
  console.log('service shows disabled badge:', txt.toLowerCase().includes('disabled'));

  // Disable the (currently enabled) boot receiver
  await page.evaluate(() => toggleComponent('com.x.BootRcv', false));
  await page.waitForTimeout(100);
  console.log('setComponentEnabled called:', await page.evaluate(() => window.__calls.filter(c => c.startsWith('toggle:'))));
  // After disabling, the receiver's button should now offer ENABLE
  const btnTxt = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('#compsContainer .perm-toggle-btn')].filter(b => b.getAttribute('data-comp') === 'com.x.BootRcv');
    return btns.map(b => b.innerText);
  });
  console.log('BootRcv buttons after disable (expect ENABLE present):', JSON.stringify(btnTxt));

  // enable an exported filter still works across kinds
  await page.click('#compsFilterRow [data-filter="exported"]'); await page.waitForTimeout(100);
  const expTxt = await page.locator('#compsContainer').innerText();
  console.log('exported filter (Components): shows Provider, hides Svc and BootRcv:', expTxt.includes('Provider') && !expTxt.includes('Svc') && !expTxt.includes('BootRcv'));
  await page.click('.sheet-tab-pill[data-tab="acts"]');
  await page.click('#actsFilterRow [data-filter="exported"]'); await page.waitForTimeout(100);
  const expActs = await page.locator('#actsContainer').innerText();
  console.log('exported filter (Activities): shows Main, hides Hidden:', expActs.includes('Main') && !expActs.includes('Hidden'));

  // ---- Single-app dexopt: async (a progress bar shows while it runs), toast-only on success ----
  await page.evaluate(() => { window.__calls.length = 0; openOptimizeModal('single'); });
  console.log('optimize modal shown (single):', await page.isVisible('#optimizeModal.show'));
  const midRun = await page.evaluate(() => {
    document.getElementById('optimizeMode').value = 'everything'; document.getElementById('optimizeForce').checked = true;
    confirmOptimize();
    // read state in the same turn as the call above - the mock resolves on the next tick, same as the real
    // native batch runner would eventually do after a real (slow) pm compile call, and a separate round trip
    // through Playwright is itself slow enough to miss that window.
    return { shown: document.getElementById('batchConfirmModal').classList.contains('show'), text: document.getElementById('batchProgressText').innerText };
  });
  console.log('a progress bar shows while it runs (not frozen, no indication it still works):', midRun.shown && /com\.x/.test(midRun.text), JSON.stringify(midRun));
  await sleep(150);
  console.log('single optimize call:', await page.evaluate(() => window.__calls));
  console.log('single success: toast only, no result modal (nuisance fix - matches toggleComponent):', (await toastText()) === 'Optimized' && !(await resultsShown()));
  console.log('progress modal closes itself when done:', !(await page.evaluate(() => document.getElementById('batchConfirmModal').classList.contains('show'))));

  // ---- Batch dexopt: same async path, but keeps its useful per-app breakdown modal either way ----
  await page.evaluate(() => { window.__calls.length = 0; toggleSelectPkg('com.x'); toggleSelectPkg('com.y'); openOptimizeModal('batch'); });
  console.log('optimize modal shown (batch):', await page.isVisible('#optimizeModal.show'));
  await page.evaluate(() => { document.getElementById('optimizeMode').value = 'speed'; document.getElementById('optimizeForce').checked = false; confirmOptimize(); });
  await sleep(200);
  console.log('batch optimize calls (expect 2, speed, false):', await page.evaluate(() => window.__calls));
  console.log('batch keeps the per-app breakdown modal even on success:', await resultsShown());
  await page.evaluate(() => closeCommandResultsModal());

  // ---- "reset" (pm compile --reset) restores the platform's post-install state: no Force switch, a note says what it does, the mode reaches the bridge ----
  await page.evaluate(() => { window.__calls.length = 0; clearBatchSelection(); openOptimizeModal('single'); });
  console.log('the mode list offers space and reset:', await page.evaluate(() => Array.from(document.querySelectorAll('#optimizeMode option')).map(o => o.value).join(',')));
  console.log('speed: the Force switch shows, no reset note:', await page.evaluate(() => document.getElementById('optimizeForceRow').style.display === '' && document.getElementById('optimizeResetNote').style.display === 'none'));
  await page.evaluate(() => { const s = document.getElementById('optimizeMode'); s.value = 'reset'; s.dispatchEvent(new Event('change')); });
  console.log('reset: the Force switch is hidden and the note shows:', await page.evaluate(() => document.getElementById('optimizeForceRow').style.display === 'none' && document.getElementById('optimizeResetNote').style.display === '' && /pm compile --reset/.test(document.getElementById('optimizeResetNote').innerText)));
  await page.evaluate(() => confirmOptimize());
  await sleep(150);
  console.log('reset reaches the bridge as mode reset:', await page.evaluate(() => JSON.stringify(window.__calls)));
  console.log('the sub label names the reset command:', await page.evaluate(() => optimizeSubLabel));
  await page.evaluate(() => { openOptimizeModal('single'); });
  console.log('opening the sheet again re-reads the mode (reset still chosen -> note still shown):', await page.evaluate(() => document.getElementById('optimizeResetNote').style.display === ''));
  console.log('reset: the subtitle says reset, not recompile or faster:', await page.evaluate(() => document.getElementById('optimizeSubtitle').innerText));
  console.log('speed: the subtitle goes back to recompile:', await page.evaluate(() => { const s = document.getElementById('optimizeMode'); s.value = 'speed'; s.dispatchEvent(new Event('change')); const t = document.getElementById('optimizeSubtitle').innerText; s.value = 'reset'; s.dispatchEvent(new Event('change')); return t; }));
  await page.evaluate(() => { const s = document.getElementById('optimizeMode'); s.value = 'speed'; s.dispatchEvent(new Event('change')); closeOptimizeModal(); });

  // ---- A failed single optimize still gets the modal (the toast alone wouldn't explain why) ----
  await page.evaluate(() => { window.AndroidBridge.optimizeApp = (pkg) => JSON.stringify({ ok: false, output: 'pm compile: Failed to optimize package' }); clearBatchSelection(); window.__calls.length = 0; openOptimizeModal('single'); confirmOptimize(); });
  await sleep(150);
  console.log('a failed single optimize still shows the result modal:', (await toastText()) === 'Failed' && await resultsShown());
  await page.evaluate(() => closeCommandResultsModal());

  // ---- App standby bucket: the sheet shows what the phone says, Apply sets and reads back ----
  await page.evaluate(() => { window.__calls.length = 0; openStandbyModal(); });
  console.log('standby sheet shown:', await page.isVisible('#standbyModal.show'));
  console.log('it asked the phone and shows its answer (rare):', await page.evaluate(() => JSON.stringify(window.__calls) + ' ' + document.getElementById('standbyBucket').value));
  console.log('the title line names the app and package:', await page.evaluate(() => /com\.x/.test(document.getElementById('standbySubtitle').innerText)));
  await page.evaluate(() => { document.getElementById('standbyBucket').value = 'restricted'; window.__calls.length = 0; confirmStandby(); });
  console.log('apply sets the chosen bucket and closes the sheet:', await page.evaluate(() => JSON.stringify(window.__calls)), !(await page.isVisible('#standbyModal.show')));
  console.log('success is a toast only:', (await toastText()) === 'Standby bucket changed' && !(await resultsShown()));
  await page.evaluate(() => { openStandbyModal(); });
  console.log('opened again it shows the bucket the phone is in now (restricted):', await page.evaluate(() => document.getElementById('standbyBucket').value));
  await page.evaluate(() => { window.__sb = 'exempted'; openStandbyModal(); });
  console.log('a bucket the list cannot set (exempted) is shown, and cannot be picked:', await page.evaluate(() => { const o = document.querySelector('#standbyBucket option[data-extra]'); return document.getElementById('standbyBucket').value + ' ' + (o ? o.disabled : 'no option'); }));
  await page.evaluate(() => { window.__sb = 'rare'; openStandbyModal(); });
  console.log('the extra option is gone the next time:', await page.evaluate(() => document.querySelectorAll('#standbyBucket option[data-extra]').length + ' ' + document.querySelectorAll('#standbyBucket option').length));
  await page.evaluate(() => { window.__sbFail = true; document.getElementById('standbyBucket').value = 'active'; confirmStandby(); });
  await sleep(100);
  console.log('a refusal by the phone gives a toast and the result window with its words:', (await toastText()) === 'Could not change the standby bucket' && await resultsShown());
  await page.evaluate(() => { window.__sbFail = false; closeCommandResultsModal(); });
  // the phone cannot be read: no editable sheet with a guessed bucket, a toast and the result window with its words instead
  await page.evaluate(() => { window.__sbReadFail = true; document.getElementById('standbyModal').classList.remove('show'); document.getElementById('standbyBucket').value = 'active'; openStandbyModal(); });
  await sleep(100);
  console.log('a failed read does not open the sheet, and says why:', !(await page.isVisible('#standbyModal.show')), await toastText(), await resultsShown());
  await page.evaluate(() => { window.__sbReadFail = false; closeCommandResultsModal(); });

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
