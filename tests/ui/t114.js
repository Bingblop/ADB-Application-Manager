// Connected Devices: uninstalling a SYSTEM app on the other device uses the same way round as on this phone. The page hands the removal to the app (AndroidBridge.cdUninstall: pm uninstall --user 0, and
// when that device says only root may remove a system app, the Binder helper sent there and run); a plain app, a system app the helper removes, a refusal (with the note and the device's own words),
// several apps at once, and an older build without the bridge method (the plain command as before).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__un = []; window.__adb = []; window.__gone = new Set();
    window.__apps = [['com.example.fit', 0], ['com.google.android.apps.maps', 1], ['com.google.android.deskclock', 1], ['com.wear.policy', 1], ['com.wear.gone', 0]];
    const NOTE = '\n\nNote: That device only lets root remove a system app for one user. The direct Binder call this app tries as a fallback (a small helper sent to the device and run there) was not able to either; the reason is in the text above. Disable the app instead: it disappears from the launcher and stops running, but stays installed.';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, cdSelfSerials() { return '[]'; },
      cdAdb(tag, serial, argsJson) {
        const a = JSON.parse(argsJson); window.__adb.push(serial + ' ' + a.join(' '));
        let out = '';
        if (a[0] === 'devices') out = 'List of devices attached\n192.168.1.20:5555\tdevice product:w model:Watch\n';
        else if (a[0] === 'shell' && /^echo '#ALL'/.test(a[1])) {
          const all = window.__apps.map(x => 'package:' + (x[1] ? '/system/app/' + x[0] + '/x.apk' : '/data/app/~~ab==/' + x[0] + '-1==/base.apk') + '=' + x[0]);
          const inst = window.__apps.filter(x => !window.__gone.has(x[0])).map(x => 'package:' + x[0]);
          out = ['#ALL'].concat(all, ['#INST'], inst, ['#DIS'], ['#SYS'], window.__apps.filter(x => x[1]).map(x => 'package:' + x[0])).join('\n');
        } else if (a[0] === 'shell' && /^pm uninstall --user 0 '(.+)'$/.test(a[1])) { window.__gone.add(/'(.+)'/.exec(a[1])[1]); out = 'Success'; }
        setTimeout(() => window.onCdResult({ tag, out, ms: 3 }), 5);
      },
      cdUninstall(tag, serial, pkg) {
        window.__un.push(serial + ' ' + pkg);
        let r;
        if (pkg === 'com.example.fit' || pkg === 'com.wear.gone') r = { ok: true, out: 'Success' };
        else if (pkg === 'com.google.android.apps.maps') r = { ok: true, out: 'Success\n\nRemoved with a direct Binder call (IPackageManager.deletePackageAsUser), because the device\'s shell-level `pm uninstall` needs actual root for a system app.' };
        else if (pkg === 'com.google.android.deskclock') r = { ok: false, out: 'Failure [only root can delete system app for a particular user]\n\nRESULT:FAIL:-3' + NOTE };
        else if (pkg === 'com.wear.policy') r = { ok: false, out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n\nNote: A device policy requires this app.' };
        else r = { ok: false, out: 'Error: device offline' };
        if (r.ok) window.__gone.add(pkg);
        setTimeout(() => window.onCdResult({ tag, ok: r.ok, out: r.out }), 10);
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => page.locator('#toastMsg').innerText();
  const un = () => ev(() => window.__un.slice());

  await ev(() => { switchView('devices'); });
  await wait(1200);
  await ev(() => { if (!cd.serial) cd.serial = '192.168.1.20:5555'; cd.sub = 'apps'; });
  await ev(() => cdAppsLoad(true)); await wait(500);
  check('the device and its apps are there', await ev(() => cd.serial === '192.168.1.20:5555' && (cd.apps || []).length === 5), JSON.stringify(await ev(() => [cd.serial, (cd.apps || []).length])));

  // a plain app
  await ev(() => { cdAppAct('com.example.fit', 'uninstall'); }); await wait(150);
  check('it asks first, and the bridge is not called before the answer', await ev(() => document.getElementById('cdAskModal').classList.contains('show')) && (await un()).length === 0);
  await page.click('#cdAskOk'); await wait(300);
  check('the removal goes to the app\'s own method for that device and package', (await un()).join('|') === '192.168.1.20:5555 com.example.fit');
  check('no pm uninstall is typed from the page any more', !(await ev(() => window.__adb)).some(c => /pm uninstall/.test(c)));
  check('the row says UNINSTALLED and a toast says so', await ev(() => /UNINSTALLED/.test(document.getElementById('cdcard_com.example.fit').innerText)) && /Uninstalled: Fit/.test(await toast()), await toast());

  // a system app that the helper removes
  await ev(() => { cdAppAct('com.google.android.apps.maps', 'uninstall'); }); await wait(120);
  await page.click('#cdAskOk'); await wait(300);
  check('a system app removed with the Binder helper is just as gone', await ev(() => /UNINSTALLED/.test(document.getElementById('cdcard_com.google.android.apps.maps').innerText)) && /Uninstalled: Maps/.test(await toast()));
  check('and can be reinstalled from its menu', await ev(() => { cdAppMenu('com.google.android.apps.maps'); return Array.from(document.querySelectorAll('#cdSheetBtns button')).map(b => b.innerText)[0]; }) === 'Reinstall');
  await ev(() => cdSheetClose());

  // a refusal: the device's own words and the note
  await ev(() => { cdAppAct('com.google.android.deskclock', 'uninstall'); }); await wait(120);
  await page.click('#cdAskOk'); await wait(1800);          // (the command, then two looks at the device with a pause between: the app is still there)
  const sheet = await ev(() => ({ open: document.getElementById('cdSheetModal') ? document.getElementById('cdSheetModal').classList.contains('show') : null, title: document.getElementById('cdSheetTitle').innerText, body: document.getElementById('cdSheetBody').innerText }));
  check('a refusal opens "That did not work" with the answer', sheet.title === 'That did not work' && /only root can delete system app/.test(sheet.body) && /RESULT:FAIL:-3/.test(sheet.body), JSON.stringify(sheet));
  check('with the note about disabling instead', /Disable the app instead/.test(sheet.body));
  check('the row is not marked uninstalled', await ev(() => !/UNINSTALLED/.test(document.getElementById('cdcard_com.google.android.deskclock').innerText)));
  await page.screenshot({ path: 'refused.png' });
  await ev(() => cdSheetClose());

  // several at once
  await ev(() => { cd.sel.clear(); cdToggleSel('com.wear.gone'); cdToggleSel('com.wear.policy'); cdToggleSel('com.google.android.deskclock'); cdBatch('uninstall'); }); await wait(150);
  check('several: asked once for all of them', await ev(() => /Uninstall 3 apps/.test(document.getElementById('cdAskTitle').innerText)));
  await ev(() => { window.__un.length = 0; });
  await page.click('#cdAskOk'); await wait(2800);
  check('each one goes through the same method, for that device', (await un()).length === 3 && (await un()).every(c => /^192\.168\.1\.20:5555 com\./.test(c)), (await un()).join('|'));
  const report = await ev(() => document.getElementById('cdSheetBody').innerText);
  const title = await ev(() => document.getElementById('cdSheetTitle').innerText);
  check('the report counts the two that did not work out of three', title === '2 of 3 did not work', title);
  check('and names each with the first line of its answer', /Policy: Failure \[DELETE_FAILED_DEVICE_POLICY_MANAGER\]/.test(report) && /Deskclock: Failure \[only root can delete system app/.test(report) && !/Gone:/.test(report), report);
  check('the one that worked is gone from the list of running apps', await ev(() => /UNINSTALLED/.test(document.getElementById('cdcard_com.wear.gone').innerText)));
  await ev(() => cdSheetClose());

  // an older build: no bridge method, so the plain command as before
  await ev(() => { delete window.AndroidBridge.cdUninstall; window.__un.length = 0; window.__adb.length = 0; cd.apps.find(a => a.pkg === 'com.wear.gone').uninstalled = false; cdAppsRender(); });
  await ev(() => { cdAppAct('com.wear.gone', 'uninstall'); }); await wait(120);
  await page.click('#cdAskOk'); await wait(300);
  check('without the bridge method it runs pm uninstall --user 0 as before', (await ev(() => window.__adb)).some(c => c === "192.168.1.20:5555 shell pm uninstall --user 0 'com.wear.gone'") && (await un()).length === 0);

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
