// App inspector: permission toggle/filter, App Ops modes/custom op, manifest search/copy/save, frozen app menu
const { chromium, PAGE, fixture } = require('./lib/pw');
const fs = require('fs');
const manifest = fs.readFileSync(fixture('manifest.xml'), 'utf8').split('\n').slice(0, 120).join('\n');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript((manifest) => {
    const st = { calls: [], ops: { CAMERA: 'allow', RUN_IN_BACKGROUND: 'allow', WAKE_LOCK: 'ignore' } };
    window.__st = st;
    const opsRaw = () => 'Uid mode: COARSE_LOCATION: foreground\n' + Object.entries(st.ops).map(([k, v]) => `${k}: ${v}; time=+1h ago`).join('\n');
    const perms = () => [
      { name: 'android.permission.CAMERA', granted: st.cam !== false, protection: 'runtime', changeable: true, appOp: false, label: 'take pictures and videos' },
      { name: 'android.permission.INTERNET', granted: true, protection: 'normal', changeable: false, appOp: false, label: 'have full network access' },
      { name: 'android.permission.WRITE_SECURE_SETTINGS', granted: false, protection: 'development', changeable: true, appOp: false, label: '' },
      { name: 'android.permission.SYSTEM_ALERT_WINDOW', granted: false, protection: 'signature', changeable: false, appOp: true, label: 'display over other apps' }];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.camera', name: 'Camera', isSystem: true, isRunning: true }, { pkg: 'com.facebook.katana', name: 'Facebook', isSystem: false, isFrozen: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true }, adbWireless: { port: 0 }, shizuku: {}, configuredMode: 'adb_tcp', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails(p) { return JSON.stringify({ versionName: '15.0', permissions: perms(), activities: ['com.x.Main'], services: [], appopsRaw: opsRaw() }); },
      getAppOpsRaw() { return opsRaw(); },
      setAppOp(p, op, m) { st.calls.push(`appop:${op}=${m}`); st.ops[op] = m; return ''; },
      setPermission(p, perm, g) { st.calls.push(`perm:${perm}=${g}`); if (perm.endsWith('CAMERA')) st.cam = g; return ''; },
      getAppManifest() { return manifest; },
      saveTextToDownloads(n, t) { st.calls.push('save:' + n + ':' + t.length); return 'Download/ADB App Manager/' + n; },
      copyToClipboard(t) { st.calls.push('copy:' + t.length); },
      executeAppAction(a, p) { st.calls.push('action:' + a + ':' + p); return ''; },
    };
  }, manifest);
  await page.goto(PAGE);
  await page.waitForTimeout(300);
  console.log('row buttons:', await page.locator('#card_com\\.sec\\.android\\.app\\.camera .btn-mini').evaluateAll(els => els.map(e => e.title).join(', ')));
  await page.locator('#card_com\\.sec\\.android\\.app\\.camera .btn-mini[title="App Settings"]').click();
  await page.screenshot({ path: 'list.png' });
  await page.locator('#card_com\\.facebook\\.katana .btn-mini[title="Menu"]').click(); await page.waitForTimeout(200);
  console.log('frozen app menu shows Enable:', await page.isVisible('#sheetBtnUnfreeze'), 'Freeze:', await page.isVisible('#sheetBtnFreeze'));
  await page.evaluate(() => closeInspector());
  await page.locator('#card_com\\.sec\\.android\\.app\\.camera .btn-mini[title="Menu"]').click(); await page.waitForTimeout(200);
  console.log('perm rows:', await page.locator('#permsContainer .perm-row').count(), '| tab label:', await page.locator('.sheet-tab-pill[data-tab="perms"]').innerText());
  await page.click('#permsFilterRow [data-filter="changeable"]');
  console.log('changeable rows:', await page.locator('#permsContainer .perm-row').count());
  await page.click('#permsFilterRow [data-filter="all"]');
  await page.locator('#permsContainer .perm-toggle-btn.granted').first().click(); await page.waitForTimeout(100);
  console.log('camera after toggle:', await page.locator('#permsContainer .perm-row').first().innerText());
  await page.locator('#sheetTabPerms').screenshot({ path: 'perms.png' }).catch(()=>{});
  await page.screenshot({ path: 'perms_full.png' });
  await page.click('.sheet-tab-pill[data-tab="ops"]');
  console.log('ops rows:', await page.locator('#opsContainer .perm-row').count());
  await page.click('#opsContainer .op-mode-btn.ignore[data-op="CAMERA"]'); await page.waitForTimeout(100);
  console.log('CAMERA mode now:', await page.locator('#opsContainer .op-mode-btn.on[data-op="CAMERA"]').innerText());
  await page.fill('#opCustomName', 'run any in background'); await page.selectOption('#opCustomMode', 'deny'); await page.click('.op-custom-row button');
  await page.click('#opsFilterRow [data-filter="restricted"]');
  console.log('restricted ops:', await page.locator('#opsContainer .perm-name').allInnerTexts());
  await page.screenshot({ path: 'ops.png' });
  await page.click('.sheet-tab-pill[data-tab="manifest"]'); await page.waitForTimeout(200);
  console.log('manifest meta:', await page.locator('#manifestMeta').innerText());
  await page.screenshot({ path: 'manifest.png' });
  await page.fill('#manifestSearch', 'SCREEN_O');
  console.log('find meta:', await page.locator('#manifestMeta').innerText(), '|', (await page.locator('#manifestContainer').innerText()).split('\n')[0]);
  await page.click('#sheetTabManifest >> text=Copy'); await page.click('#sheetTabManifest >> text=Save to Downloads');
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
