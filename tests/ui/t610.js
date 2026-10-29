// v7.19.3: Connected Devices, an app of the chosen device in the Apps tab's app menu layout (#cdInspModal), from the app's menu (Permissions, App Ops,
// Activities, Components, Manifest, Details). One shell command reads the package (dumpsys package cut to its block, cmd appops get, pm path); the manifest
// comes from the APK on the device (AndroidBridge.cdManifest), what this phone knows of each permission from AndroidBridge.cdPermInfo.
// Permissions: pm grant / revoke of a run-time one, read back; an install-time one is locked; an app-op permission is switched through its op. App Ops:
// cmd appops set (the uid's mode too when it has one), read back, "Android kept" when the device did not take it; a custom op. Activities start on the device
// with its screen woken first; one that is not exported, and a component switched on or off, are retried through the device's su, or say that only root or
// the system may when it has none; a part of a system app asks first. Manifest: the decoded XML, find, Copy. Features: against pm list features. Libraries.
// The same maximize button as the Apps tab's app menu: the tab fills the screen, Back comes out of it, then closes the sheet.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const W = '192.168.1.30:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (o) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((o) => {
      const W = '192.168.1.30:5555', PKG = 'com.wear.weather';
      const st = window.__st = { log: [], su: o.su !== false, suStyle: o.suStyle || 'c', suRefuses: !!o.suRefuses, cancelled: [], runtime: { 'android.permission.CAMERA': false, 'android.permission.POST_NOTIFICATIONS': true },
        ops: { CAMERA: 'allow', RUN_IN_BACKGROUND: 'allow', WAKE_LOCK: 'allow', BOOT_COMPLETED: 'allow' }, uidOps: { WAKE_LOCK: 'allow' }, stuck: new Set(['BOOT_COMPLETED']),
        disabled: new Set([PKG + '.OldReceiver']) };
      const MAN = ['<?xml version="1.0" encoding="utf-8"?>',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wear.weather" android:versionCode="12" android:versionName="2.4.1">',
        '    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="34" />',
        '    <uses-feature android:name="android.hardware.type.watch" />',
        '    <uses-feature android:name="android.hardware.camera" android:required="false" />',
        '    <uses-feature android:glEsVersion="0x00020000" android:required="true" />',
        '    <uses-permission android:name="android.permission.INTERNET" />',
        '    <application android:label="Weather">',
        '        <uses-library android:name="com.google.android.wearable" android:required="true" />',
        '        <activity android:name=".MainActivity" android:exported="true">',
        '            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>',
        '        </activity>',
        '        <activity android:name=".SettingsActivity" android:exported="false" />',
        '        <activity-alias android:name=".Launcher2" android:targetActivity=".MainActivity" android:exported="true" />',
        '        <receiver android:name=".BootReceiver"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter></receiver>',
        '        <receiver android:name=".OldReceiver" android:exported="false" />',
        '        <service android:name="com.wear.weather.SyncService" android:exported="false" />',
        '        <provider android:name=".DataProvider" android:authorities="com.wear.weather.data" android:exported="false" />',
        '    </application>',
        '</manifest>'].join('\n');
      st.man = MAN;
      const dump = () => ['Packages:', '  Package [' + PKG + '] (1a2b3c):', '    userId=10123', '    primaryCpuAbi=arm64-v8a', '    versionCode=12 minSdk=30 targetSdk=34', '    versionName=2.4.1',
        '    usesLibraries:', '      com.google.android.wearable', '    usesLibraryFiles:', '      /system/framework/com.google.android.wearable.jar',
        '    timeStamp=2026-01-02 03:04:05', '    lastUpdateTime=2026-02-03 04:05:06', '    installerPackageName=com.android.vending', '    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]',
        '    declared permissions:', '      com.wear.weather.permission.SYNC: prot=signature, INSTALLED',
        '    requested permissions:', '      android.permission.INTERNET', '      android.permission.CAMERA', '      android.permission.POST_NOTIFICATIONS', '      android.permission.SYSTEM_ALERT_WINDOW', '      android.permission.WAKE_LOCK', '      com.wear.weather.permission.SYNC',
        '    install permissions:', '      android.permission.INTERNET: granted=true', '      android.permission.WAKE_LOCK: granted=true', '      com.wear.weather.permission.SYNC: granted=true',
        '    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0', '      firstInstallTime=2026-01-02 03:04:05', '      gids=[3003]',
        '      runtime permissions:'].concat(Object.keys(st.runtime).map(n => '        ' + n + ': granted=' + st.runtime[n] + ', flags=[ USER_SENSITIVE_WHEN_GRANTED ]'),
        st.disabled.size ? ['      disabledComponents:'].concat(Array.from(st.disabled).map(c => '        ' + c)) : [],
        ['    User 10: ceDataInode=2 installed=true', '      runtime permissions:', '        android.permission.SYSTEM_ALERT_WINDOW: granted=true, flags=[ ]', '      disabledComponents:', '        com.wear.weather.MainActivity',
          'Queries:', '  system apps queryable: false']).join('\n');
      const opsOut = () => Object.keys(st.ops).map(k => (st.uidOps[k] ? 'Uid mode: ' + k + ': ' + st.uidOps[k] + '\n' : '') + k + ': ' + st.ops[k] + (k === 'CAMERA' ? '; time=+2h ago' : '')).join('\n');
      const reply = (tag, out) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), 8);
      const denied = 'Exception occurred while executing:\njava.lang.SecurityException: Shell cannot change component state for x to 2';
      const one = (cmd, root) => {
        let m;
        if ((m = /^pm (grant|revoke) '([^']+)' '([^']+)'$/.exec(cmd))) { st.runtime[m[3]] = m[1] === 'grant'; return ''; }
        if ((m = /^cmd appops set (--uid )?'([^']+)' (\w+) (\w+)(?: >\/dev\/null)? 2>&1$/.exec(cmd))) { if (st.stuck.has(m[3])) return ''; (m[1] ? st.uidOps : st.ops)[m[3]] = m[4]; return ''; }
        if ((m = /^input keyevent KEYCODE_WAKEUP 2>\/dev\/null; am start -n '([^']+)' 2>&1$/.exec(cmd))) {
          if (/SettingsActivity/.test(m[1]) && !root) return 'Starting: Intent { cmp=' + m[1] + ' }\nSecurity exception: Permission Denial: starting Intent { cmp=' + m[1] + ' } from null (pid=1, uid=2000) not exported from uid 10123\njava.lang.SecurityException: Permission Denial: starting Intent';
          return 'Starting: Intent { cmp=' + m[1] + ' }';
        }
        if ((m = /^am stopservice -n '([^']+)' 2>&1$/.exec(cmd))) return 'Stopping service: Intent { cmp=' + m[1] + ' }\nService stopped';
        if ((m = /^pm (enable|disable) '[^'/]+\/([^']+)' 2>&1$/.exec(cmd))) {
          if (!root) return denied;
          if (m[1] === 'enable') st.disabled.delete(m[2]); else st.disabled.add(m[2]);
          return 'Component {' + PKG + '/' + m[2] + '} new state: ' + (m[1] === 'enable' ? 'enabled' : 'disabled');
        }
        return null;
      };
      const shell = (cmd) => {
        let m, r;
        if (/^for p in/.test(cmd)) return ['ro.product.manufacturer=samsung', 'ro.product.model=SM-R930', 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a',
          'ro.sf.lcd_density=320', 'ro.build.characteristics=watch', 'wmsize=Physical size: 450x450 ', 'wmdens=Physical density: 320 ', 'batt=  level: 83   status: 3 '].join('\n');
        if (/^echo '#ALL'/.test(cmd)) return ['#ALL', 'package:/data/app/~~ab==/' + PKG + '-1==/base.apk=' + PKG, 'package:/system/app/Clock/Clock.apk=com.google.android.deskclock', '#INST', 'package:' + PKG, 'package:com.google.android.deskclock', '#DIS', '#SYS', 'package:com.google.android.deskclock'].join('\n');
        if ((m = /^dumpsys package '([^']+)' \| sed -n '\/\^Packages:\/,\/\^\[\^ \]\/p; \/\^Shared users:\/,\/\^\[\^ \]\/p'; echo '#CDI-OPS'; cmd appops get '([^']+)' 2>&1; echo '#CDI-PATH'; pm path '([^']+)' 2>&1$/.exec(cmd))) {
          if (m[1] !== PKG) return 'Packages:\n#CDI-OPS\nError: No UID for ' + m[1] + ' in user 0\n#CDI-PATH\n';
          return dump().split('\nQueries:')[0] + '\nQueries:\n#CDI-OPS\n' + opsOut() + '\n#CDI-PATH\npackage:/data/app/~~ab==/' + PKG + '-1==/base.apk\npackage:/data/app/~~ab==/' + PKG + '-1==/split_config.arm64_v8a.apk';
        }
        if ((m = /^(cmd appops set .*); echo '#CDI-OPS'; cmd appops get '[^']+' 2>&1$/.exec(cmd))) { m[1].split('; ').forEach(c => one(c)); return '#CDI-OPS\n' + opsOut(); }
        if (cmd === 'command -v su 2>/dev/null') return st.su ? '/system/bin/su' : '';
        // the su of root managers (su -c), or of an Android userdebug build (su 0 sh -c, and -c is an unknown uid); a su that refuses
        if ((m = /^su -c '(.*)'$/s.exec(cmd)) && st.suStyle === 'uid') return "su: invalid uid/gid '-c'";
        if ((m = /^su -c '(.*)'$/s.exec(cmd)) || (m = /^su 0 sh -c '(.*)'$/s.exec(cmd))) { if (st.suRefuses) return 'Permission denied'; const inner = m[1].replace(/'\\''/g, "'"); return (r = one(inner, true)) === null ? 'sh: not found' : r; }
        if (cmd === 'pm list features' && st.featFail) return 'Error: device offline';
        if (cmd === 'pm list features') return ['feature:reqGlEsVersion=0x30002', 'feature:android.hardware.type.watch', 'feature:android.hardware.wifi', 'feature:android.software.leanback=1'].join('\n');
        if ((r = one(cmd)) !== null) return r;
        return '';
      };
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        copyToClipboard(t) { st.copied = t; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdPermInfo(json) {
          st.permAsked = JSON.parse(json);
          const known = { 'android.permission.INTERNET': { protection: 'normal', changeable: false, appOp: false, label: 'have full network access' },
            'android.permission.CAMERA': { protection: 'runtime', changeable: true, appOp: false, op: 'CAMERA', label: 'take pictures and videos' },
            'android.permission.POST_NOTIFICATIONS': { protection: 'runtime', changeable: true, appOp: false, op: 'POST_NOTIFICATION', label: 'show notifications' },
            'android.permission.SYSTEM_ALERT_WINDOW': { protection: 'development', changeable: true, appOp: true, op: 'SYSTEM_ALERT_WINDOW', label: 'display over other apps' },
            'android.permission.WAKE_LOCK': { protection: 'normal', changeable: false, appOp: false, op: 'WAKE_LOCK', label: 'prevent phone from sleeping' } };
          const res = {};
          st.permAsked.forEach(n => { res[n] = known[n] || { protection: 'undefined', changeable: false, appOp: false, label: '' }; });
          return JSON.stringify(res);
        },
        cdManifest(tag, serial, apk) { st.log.push('manifest ' + serial + ' ' + apk); setTimeout(() => window.onCdResult({ tag, out: st.manError || st.man, ms: 5 }), st.manDelay || 20); },
        cdCancel(tag) { st.cancelled.push(tag); },
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') return reply(tag, 'List of devices attached\n' + W + '\tdevice product:w model:Galaxy_Watch6 device:w transport_id:2\n');
          if (a[0] === 'shell') { st.log.push(serial + ' ' + a[1]); return reply(tag, shell(a[1])); }
          return reply(tag, '');
        },
      };
    }, o);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(() => switchView('devices'));
    await page.waitForFunction(() => cd.info && cd.info.serial, null, { timeout: 5000 });
    await page.waitForTimeout(300);
    return page;
  };
  const ev = (page, f, a) => page.evaluate(f, a);
  const log = page => ev(page, () => window.__st.log);
  const clearLog = page => ev(page, () => { window.__st.log = []; });
  const toast = page => ev(page, () => document.getElementById('toastMsg').innerText);
  const text = (page, id) => ev(page, id => document.getElementById(id).innerText, id);
  const btnOf = (page, list, name) => ev(page, ([list, name]) => { const r = Array.from(document.querySelectorAll('#' + list + ' .perm-row')).find(x => x.querySelector('.perm-name').innerText.replace(/\s/g, '') === name); return r ? Array.from(r.querySelectorAll('button')).map(b => b.innerText.trim()) : null; }, [list, name]);
  const press = (page, list, name, label) => ev(page, ([list, name, label]) => { const r = Array.from(document.querySelectorAll('#' + list + ' .perm-row')).find(x => x.querySelector('.perm-name').innerText.replace(/\s/g, '') === name); Array.from(r.querySelectorAll('button')).find(b => b.innerText.trim() === label).click(); }, [list, name, label]);

  // ---- 1. from the app's menu: the sheet opens on the tab that was tapped, with what the device says ----
  let page = await open({});
  await ev(page, () => cdAppMenu('com.wear.weather')); await page.waitForTimeout(150);
  await ev(page, () => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(x => x.innerText === 'Permissions').click()); await page.waitForTimeout(400);
  const head = await ev(page, () => ({ show: document.getElementById('cdInspModal').classList.contains('show'), menu: document.getElementById('cdSheet').classList.contains('show'), tab: document.querySelector('#cdInspModal .sheet-tab-pill.active').getAttribute('data-tab'),
    title: document.getElementById('cdiTitle').innerText, pkg: document.getElementById('cdiPkg').innerText, ver: document.getElementById('cdiVersion').innerText, dev: document.getElementById('cdiDevice').innerText, dates: document.getElementById('cdiDates').innerText }));
  check('1. Permissions in the app menu opens the app details on that tab (the menu closes)', head.show && !head.menu && head.tab === 'perms', JSON.stringify(head));
  check('   name, package, version (code), the device and the dates', head.title === 'Weather' && head.pkg === 'com.wear.weather' && head.ver === 'Version 2.4.1 (12)' && /192\.168\.1\.30:5555/.test(head.dev) && /^Installed .+ • Updated /.test(head.dates), JSON.stringify(head));
  const read = (await log(page)).filter(l => /dumpsys package/.test(l));
  check('   one shell command read the package, its app ops and its APK path', read.length === 1 && read[0].startsWith(W + " dumpsys package 'com.wear.weather' | sed -n"), JSON.stringify(read));
  check('   the manifest came from the base APK on that device', (await log(page)).includes('manifest ' + W + ' /data/app/~~ab==/com.wear.weather-1==/base.apk'), JSON.stringify(await log(page)));
  check('   this phone was asked about every permission the app asks for or holds', await ev(page, () => window.__st.permAsked.slice().sort().join(',')) === ['android.permission.CAMERA', 'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS', 'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.WAKE_LOCK', 'com.wear.weather.permission.SYNC'].join(','));
  check('   counts on the tabs', await text(page, 'cdiPermsCount') === '6' && await text(page, 'cdiActsCount') === '3' && await text(page, 'cdiCompsCount') === '4', [await text(page, 'cdiPermsCount'), await text(page, 'cdiActsCount'), await text(page, 'cdiCompsCount')].join(' '));
  await page.screenshot({ path: 'cdi_perms.png' });

  // ---- 2. Permissions ----
  check('2. a run-time permission the app does not have: DENIED, a button', JSON.stringify(await btnOf(page, 'cdiPerms', 'CAMERA')) === '["DENIED"]', JSON.stringify(await btnOf(page, 'cdiPerms', 'CAMERA')));
  check('   one it has: GRANTED', JSON.stringify(await btnOf(page, 'cdiPerms', 'POST_NOTIFICATIONS')) === '["GRANTED"]');
  check('   an install-time one is locked', /GRANTED/.test((await btnOf(page, 'cdiPerms', 'INTERNET'))[0]) && await ev(page, () => !!document.querySelector('#cdiPerms .perm-toggle-btn.locked')));
  check('   the app\'s own permission, from its declared section: signature, locked', await ev(page, () => { const r = Array.from(document.querySelectorAll('#cdiPerms .perm-row')).find(x => /SYNC/.test(x.innerText)); return !!r && /signature/i.test(r.innerText) && !!r.querySelector('.locked'); }));
  check('   another user\'s grant is not user 0\'s: Display over other apps is DENIED (switched by its op)', JSON.stringify(await btnOf(page, 'cdiPerms', 'SYSTEM_ALERT_WINDOW')) === '["DENIED"]');
  await clearLog(page);
  await press(page, 'cdiPerms', 'CAMERA', 'DENIED'); await page.waitForTimeout(400);
  check('   tapped: pm grant on that device, then read back: GRANTED', (await log(page))[0] === W + " pm grant 'com.wear.weather' 'android.permission.CAMERA'" && JSON.stringify(await btnOf(page, 'cdiPerms', 'CAMERA')) === '["GRANTED"]' && /Granted CAMERA/.test(await toast(page)), JSON.stringify(await log(page)) + ' ' + await toast(page));
  await clearLog(page);
  await press(page, 'cdiPerms', 'POST_NOTIFICATIONS', 'GRANTED'); await page.waitForTimeout(400);
  check('   and pm revoke', (await log(page))[0] === W + " pm revoke 'com.wear.weather' 'android.permission.POST_NOTIFICATIONS'" && JSON.stringify(await btnOf(page, 'cdiPerms', 'POST_NOTIFICATIONS')) === '["DENIED"]');
  await clearLog(page);
  await press(page, 'cdiPerms', 'SYSTEM_ALERT_WINDOW', 'DENIED'); await page.waitForTimeout(400);
  check('   an app-op permission is switched through its op', (await log(page))[0] === W + " cmd appops set 'com.wear.weather' SYSTEM_ALERT_WINDOW allow 2>&1; echo '#CDI-OPS'; cmd appops get 'com.wear.weather' 2>&1" && JSON.stringify(await btnOf(page, 'cdiPerms', 'SYSTEM_ALERT_WINDOW')) === '["GRANTED"]', JSON.stringify(await log(page)));
  await ev(page, () => cdiFilter('perms', 'installtime'));
  check('   the Install-time filter shows only the locked ones', await ev(page, () => Array.from(document.querySelectorAll('#cdiPerms .perm-name')).map(x => x.innerText.replace(/\s/g, '')).join(',')) === 'INTERNET,WAKE_LOCK,com.wear.weather.permission.SYNC');
  await ev(page, () => cdiFilter('perms', 'all'));
  await ev(page, () => { document.getElementById('cdiPermsSearch').value = 'camera'; cdiRenderPerms(); });
  check('   the search finds by name', await ev(page, () => document.querySelectorAll('#cdiPerms .perm-row').length) === 1);
  await ev(page, () => { document.getElementById('cdiPermsSearch').value = ''; cdiRenderPerms(); });

  // ---- 3. App Ops ----
  await ev(page, () => cdInspTab('ops'));
  const opNames = await ev(page, () => Array.from(document.querySelectorAll('#cdiOps .perm-name')).map(x => x.innerText));
  check('3. App Ops lists the device\'s ops and those of the app\'s permissions not set yet', ['BOOT_COMPLETED', 'CAMERA', 'POST_NOTIFICATION', 'RUN_IN_BACKGROUND', 'SYSTEM_ALERT_WINDOW', 'WAKE_LOCK'].every(n => opNames.includes(n)), opNames.join(','));
  await clearLog(page);
  await press(page, 'cdiOps', 'RUN_IN_BACKGROUND', 'Ignore'); await page.waitForTimeout(400);
  check('   a mode: cmd appops set, then read back, the mode is on', (await log(page))[0] === W + " cmd appops set 'com.wear.weather' RUN_IN_BACKGROUND ignore 2>&1; echo '#CDI-OPS'; cmd appops get 'com.wear.weather' 2>&1" && await ev(page, () => { const r = Array.from(document.querySelectorAll('#cdiOps .perm-row')).find(x => x.querySelector('.perm-name').innerText === 'RUN_IN_BACKGROUND'); return r.querySelector('.op-mode-btn.ignore').classList.contains('on'); }) && /RUN_IN_BACKGROUND → ignore/.test(await toast(page)), JSON.stringify(await log(page)));
  await clearLog(page);
  await press(page, 'cdiOps', 'WAKE_LOCK', 'Deny'); await page.waitForTimeout(400);
  check('   an op with a uid mode: the uid mode is set too', /^192\.168\.1\.30:5555 cmd appops set --uid 'com\.wear\.weather' WAKE_LOCK deny >\/dev\/null 2>&1; cmd appops set 'com\.wear\.weather' WAKE_LOCK deny 2>&1; echo/.test((await log(page))[0]), JSON.stringify(await log(page)));
  await press(page, 'cdiOps', 'BOOT_COMPLETED', 'Ignore'); await page.waitForTimeout(400);
  check('   the device kept its mode: said so', /Android kept BOOT_COMPLETED at allow/.test(await toast(page)), await toast(page));
  await clearLog(page);
  await ev(page, () => { document.getElementById('cdiOpName').value = 'run any in background'; document.getElementById('cdiOpMode').value = 'ignore'; cdiSetCustomOp(); }); await page.waitForTimeout(400);
  check('   another op by its name', (await log(page))[0] === W + " cmd appops set 'com.wear.weather' RUN_ANY_IN_BACKGROUND ignore 2>&1; echo '#CDI-OPS'; cmd appops get 'com.wear.weather' 2>&1", JSON.stringify(await log(page)));

  // ---- 4. Activities: they start on the device (its screen woken first), an unexported one through su ----
  await ev(page, () => cdInspTab('acts'));
  const acts = await ev(page, () => Array.from(document.querySelectorAll('#cdiActs .perm-row')).map(r => r.querySelector('.perm-name').innerText + ':' + (/unexported/i.test(r.innerText) ? 'u' : 'e') + (/disabled/i.test(r.innerText) ? 'd' : '')));
  check('4. Activities: the launcher one first, the alias, the unexported one; another user\'s switch is not user 0\'s', acts.join(',') === 'MainActivity:e,Launcher2:e,SettingsActivity:u', acts.join(','));
  check('   the alias says what it opens', await ev(page, () => /Launcher2 → com\.wear\.weather\.MainActivity/.test(document.getElementById('cdiActs').innerText)));
  await clearLog(page);
  await press(page, 'cdiActs', 'MainActivity', 'LAUNCH'); await page.waitForTimeout(300);
  check('   LAUNCH: the screen is woken and the activity started on the device', (await log(page))[0] === W + " input keyevent KEYCODE_WAKEUP 2>/dev/null; am start -n 'com.wear.weather/com.wear.weather.MainActivity' 2>&1" && (await log(page)).length === 1 && await toast(page) === 'Opened on the device', JSON.stringify(await log(page)) + ' ' + await toast(page));
  await clearLog(page);
  await press(page, 'cdiActs', 'SettingsActivity', 'LAUNCH'); await page.waitForTimeout(400);
  const l4 = await log(page);
  check('   an unexported one: refused to the shell user, so once more through the device\'s su', l4.length === 3 && l4[1] === W + ' command -v su 2>/dev/null' && l4[2] === W + " su -c 'input keyevent KEYCODE_WAKEUP 2>/dev/null; am start -n '\\''com.wear.weather/com.wear.weather.SettingsActivity'\\'' 2>&1'" && await toast(page) === 'Opened on the device', JSON.stringify(l4) + ' ' + await toast(page));

  // ---- 5. Components ----
  await ev(page, () => cdInspTab('comps'));
  const comps = await ev(page, () => document.getElementById('cdiComps').innerText);
  check('5. Components: receivers, services, providers; the one the device turned off says disabled', /RECEIVERS \(2\)/.test(comps) && /SERVICES \(1\)/.test(comps) && /PROVIDERS \(1\)/.test(comps) && JSON.stringify(await btnOf(page, 'cdiComps', 'OldReceiver')) === '["ENABLE"]' && /com\.wear\.weather\.data/.test(comps), comps);
  await clearLog(page);
  await press(page, 'cdiComps', 'SyncService', 'DISABLE'); await page.waitForTimeout(500);
  const l5 = await log(page);
  check('   DISABLE: pm disable refused to the shell user, then through su (asked once per device): disabled', l5[0] === W + " pm disable 'com.wear.weather/com.wear.weather.SyncService' 2>&1" && l5.length === 2 && /^192\.168\.1\.30:5555 su -c 'pm disable/.test(l5[1]) && await toast(page) === 'Component disabled' && JSON.stringify(await btnOf(page, 'cdiComps', 'SyncService')) === '["STOP","ENABLE"]', JSON.stringify(l5) + ' ' + await toast(page));
  await clearLog(page);
  await press(page, 'cdiComps', 'SyncService', 'STOP'); await page.waitForTimeout(300);
  check('   STOP: am stopservice', (await log(page))[0] === W + " am stopservice -n 'com.wear.weather/com.wear.weather.SyncService' 2>&1" && await toast(page) === 'Service stopped');
  await ev(page, () => cdiFilter('comps', 'exported'));
  check('   the Exported filter', await ev(page, () => Array.from(document.querySelectorAll('#cdiComps .perm-name')).map(x => x.innerText).join(',')) === 'BootReceiver');
  await ev(page, () => cdiFilter('comps', 'all'));

  // ---- 6. Manifest ----
  await ev(page, () => cdInspTab('manifest'));
  check('6. Manifest: the decoded XML with its lines counted', /<activity android:name="\.SettingsActivity"/.test(await text(page, 'cdiManifest')) && await text(page, 'cdiManMeta') === '20 lines', await text(page, 'cdiManMeta'));
  await ev(page, () => { document.getElementById('cdiManSearch').value = 'receiver'; cdiRenderManifest(); });
  check('   find: Matches only shows the two lines, the count is of every hit in them', await text(page, 'cdiManMeta') === '1 / 5 matches' && (await text(page, 'cdiManifest')).split('\n').length === 2, await text(page, 'cdiManMeta'));
  await ev(page, () => cdiManStep(1));
  check('   ▼ goes to the next hit', await text(page, 'cdiManMeta') === '2 / 5 matches');
  await ev(page, () => cdiManCopy());
  check('   Copy copies the whole manifest', await ev(page, () => window.__st.copied === window.__st.man));

  // ---- 7. Features and Libraries ----
  await ev(page, () => cdInspTab('features')); await page.waitForTimeout(300);
  const feats = await ev(page, () => Array.from(document.querySelectorAll('#cdiFeatures .perm-row')).map(r => r.innerText.replace(/\s+/g, ' ').trim().toLowerCase()));
  check('7. Features: against what the device has', feats.join(' | ') === 'opengl es 2.0 on the device | android.hardware.type.watch required on the device | android.hardware.camera optional not on the device', feats.join(' | '));
  check('   read once with pm list features', (await log(page)).filter(l => /pm list features/.test(l)).length === 1);
  await ev(page, () => cdiFilter('features', 'missing'));
  check('   Not on the device', await ev(page, () => document.querySelectorAll('#cdiFeatures .perm-row').length) === 1);
  await ev(page, () => cdiFilter('features', 'all'));
  await ev(page, () => cdInspTab('libs'));
  const libs = await text(page, 'cdiLibs');
  check('   Libraries: what the manifest asks for, the files Android links in, the CPU type', /com\.google\.android\.wearable/.test(libs) && /com\.google\.android\.wearable\.jar/.test(libs) && /arm64-v8a/.test(libs), libs);

  // ---- 8. the maximize button, and Back ----
  await ev(page, () => cdInspTab('acts'));
  await ev(page, () => document.querySelector('#cdiTabActs .sheet-full-btn').click()); await page.waitForTimeout(300);
  const full = await ev(page, () => { const sh = document.querySelector('#cdInspModal .modal-sheet'), r = sh.getBoundingClientRect(); return { full: sh.classList.contains('tab-full'), h: Math.round(r.height), title: sh.querySelector('.sheet-full-titles h3').innerText, sub: sh.querySelector('.sheet-full-titles p').innerText, apps: document.querySelector('#inspectorModal .modal-sheet').classList.contains('tab-full') }; });
  check('8. the maximize button: the tab fills the screen, titled with the tab and the app', full.full && full.h >= 850 && full.title === 'Activities' && full.sub === 'Weather' && !full.apps, JSON.stringify(full));
  await page.screenshot({ path: 'cdi_full.png' });
  await ev(page, () => cdInspTab('comps'));
  check('   another tab keeps the full screen and its title', await ev(page, () => document.querySelector('#cdInspModal .sheet-full-titles h3').innerText) === 'Components');
  await ev(page, () => backNavigate()); await page.waitForTimeout(300);
  check('   Back comes out of the full screen, the sheet stays', await ev(page, () => !document.querySelector('#cdInspModal .modal-sheet').classList.contains('tab-full') && document.getElementById('cdInspModal').classList.contains('show')));
  await ev(page, () => backNavigate()); await page.waitForTimeout(300);
  check('   Back again closes it', await ev(page, () => !document.getElementById('cdInspModal').classList.contains('show')));
  await ev(page, () => cdInspOpen('com.wear.weather', 'raw')); await page.waitForTimeout(400);
  check('   opened again it is not full screen; Details shows what dumpsys says', await ev(page, () => !document.querySelector('#cdInspModal .modal-sheet').classList.contains('tab-full')) && /versionName=2\.4\.1/.test(await text(page, 'cdiRaw')) && /# cmd appops get com\.wear\.weather/.test(await text(page, 'cdiRaw')));

  // ---- 9. a system app's part asks first; an app the device does not have ----
  await ev(page, () => cdInspClose());
  page.once('dialog', d => { page.__dlg = d.message(); d.dismiss(); });
  await ev(page, () => { cd.apps = [{ pkg: 'com.wear.weather', name: 'Weather', system: true }]; cdInspOpen('com.wear.weather', 'comps'); }); await page.waitForTimeout(400);
  await clearLog(page);
  await press(page, 'cdiComps', 'BootReceiver', 'DISABLE'); await page.waitForTimeout(300);
  check('9. a part of a system app: asks first, and nothing runs when declined', /Disable BootReceiver of the system app com\.wear\.weather\?/.test(page.__dlg || '') && !(await log(page)).length, page.__dlg + ' ' + JSON.stringify(await log(page)));
  await ev(page, () => cdInspOpen('com.example.gone', 'perms')); await page.waitForTimeout(400);
  check('   an app the device does not have says so', /Not installed for this user|No UID/.test(await text(page, 'cdiPerms')) && await text(page, 'cdiVersion') === 'Version unavailable', await text(page, 'cdiPerms'));
  await page.close();

  // ---- 10. a device without su: what only root or the system may do says so ----
  page = await open({ su: false });
  await ev(page, () => cdInspOpen('com.wear.weather', 'comps')); await page.waitForTimeout(500);
  await clearLog(page);
  await press(page, 'cdiComps', 'SyncService', 'DISABLE'); await page.waitForTimeout(400);
  check('10. no su on the device: the component stays, and the toast says only root or the system may', /only root or the system/.test(await toast(page)) && JSON.stringify(await btnOf(page, 'cdiComps', 'SyncService')) === '["STOP","DISABLE"]' && !(await log(page)).some(l => /su -c/.test(l)), await toast(page) + ' ' + JSON.stringify(await log(page)));
  await ev(page, () => cdInspTab('acts'));
  await press(page, 'cdiActs', 'SettingsActivity', 'LAUNCH'); await page.waitForTimeout(300);
  check('    an unexported activity too', /only root or the system/.test(await toast(page)));
  await ev(page, () => { window.__st.manError = 'Error: failed to stat remote object'; cdInspOpen('com.wear.weather', 'manifest'); }); await page.waitForTimeout(500);
  check('    a manifest that could not be read: its reason, in the Manifest and Activities tabs', /failed to stat remote object/.test(await text(page, 'cdiManifest')) && /failed to stat remote object/.test(await text(page, 'cdiActs')), await text(page, 'cdiActs'));
  await clearLog(page);
  await ev(page, () => { window.__st.manError = ''; cdInspLoad(true); }); await page.waitForTimeout(500);
  check('    Reload reads a manifest that failed once more', (await log(page)).some(l => /^manifest /.test(l)) && /AndroidManifest|<manifest/.test(await text(page, 'cdiManifest')), JSON.stringify(await log(page)));
  await page.close();

  // ---- 11. the su of an Android userdebug build (su 0 sh -c), a su that refuses, a manifest read stopped, a failed feature list asked again ----
  page = await open({ suStyle: 'uid' });
  await ev(page, () => cdInspOpen('com.wear.weather', 'comps')); await page.waitForTimeout(500);
  await clearLog(page);
  await press(page, 'cdiComps', 'SyncService', 'DISABLE'); await page.waitForTimeout(400);
  let l11 = await log(page);
  check('11. su that takes a uid: su -c is called an unknown uid, so su 0 sh -c, and it works', l11.length === 4 && /^192\.168\.1\.30:5555 su -c 'pm disable/.test(l11[2]) && /^192\.168\.1\.30:5555 su 0 sh -c 'pm disable/.test(l11[3]) && await toast(page) === 'Component disabled', JSON.stringify(l11) + ' ' + await toast(page));
  await clearLog(page);
  await press(page, 'cdiComps', 'SyncService', 'ENABLE'); await page.waitForTimeout(400);
  l11 = await log(page);
  check('    the next time straight to su 0 sh -c (remembered for the device)', l11.length === 2 && /^192\.168\.1\.30:5555 su 0 sh -c 'pm enable/.test(l11[1]) && await toast(page) === 'Component enabled', JSON.stringify(l11) + ' ' + await toast(page));
  await clearLog(page);
  await ev(page, () => { window.__st.manDelay = 3000; cdInspOpen('com.wear.weather', 'manifest'); }); await page.waitForTimeout(300);
  await ev(page, () => cdInspClose()); await page.waitForTimeout(100);
  check('    a manifest read still running when the sheet closes is stopped', (await ev(page, () => window.__st.cancelled)).length === 1 && await ev(page, () => !cdi.manBusy && !cdi.manTag), JSON.stringify(await ev(page, () => window.__st.cancelled)));
  await ev(page, () => { window.__st.manDelay = 20; cdInspOpen('com.wear.weather', 'acts'); }); await page.waitForTimeout(600);
  check('    ... and opened again the manifest is read anew', JSON.stringify(await btnOf(page, 'cdiActs', 'MainActivity')) === '["LAUNCH","DISABLE"]', JSON.stringify(await btnOf(page, 'cdiActs', 'MainActivity')));
  await page.waitForTimeout(2600);
  check('    the stopped read\'s late answer changes nothing', JSON.stringify(await btnOf(page, 'cdiActs', 'MainActivity')) === '["LAUNCH","DISABLE"]' && await ev(page, () => !cdi.manBusy));
  await clearLog(page);
  await ev(page, () => { window.__st.featFail = true; cdInspTab('features'); }); await page.waitForTimeout(400);
  await ev(page, () => { window.__st.featFail = false; cdInspTab('perms'); cdInspTab('features'); }); await page.waitForTimeout(400);
  check('    a feature list that failed is asked again the next time', (await log(page)).filter(l => / pm list features$/.test(l)).length === 2 && /on the device/i.test(await text(page, 'cdiFeatures')), JSON.stringify(await log(page)));
  await page.close();

  page = await open({ suRefuses: true });
  await ev(page, () => cdInspOpen('com.wear.weather', 'acts')); await page.waitForTimeout(500);
  await press(page, 'cdiActs', 'SettingsActivity', 'LAUNCH'); await page.waitForTimeout(400);
  check('    su refuses: the activity is not reported as opened', await toast(page) === 'Permission denied', await toast(page));
  await ev(page, () => cdInspTab('comps'));
  await press(page, 'cdiComps', 'SyncService', 'DISABLE'); await page.waitForTimeout(400);
  check('    ... nor the component as switched', await toast(page) === 'Permission denied' && JSON.stringify(await btnOf(page, 'cdiComps', 'SyncService')) === '["STOP","DISABLE"]', await toast(page));

  // ---- 12. what the device answers, read right: errors on their own lines, a shared user's grants ----
  const errs = await ev(page, () => [
    cdiErr('Component {com.wear.weather/com.wear.weather.ExceptionReceiver} new state: disabled'),
    String(cdiDenied('Component {com.wear.weather/com.wear.weather.SecurityExceptionReceiver} new state: disabled')),
    cdiErr("Exception occurred while executing 'grant':\njava.lang.SecurityException: Permission android.permission.INTERNET requested by x is not a changeable permission type\n\tat com.android.server.pm.Foo(Foo.java:1)"),
    cdiErr('Starting: Intent { cmp=x/.A }\nError type 3\nError: Activity class {x/x.A} does not exist.'),
    cdiErr('Operation not allowed: java.lang.SecurityException: Package x has not requested permission y'),
    cdiErr('/system/bin/sh: su: not found'),
    cdiErr('Starting: Intent { cmp=x/.A }\nWarning: Activity not started, intent has been delivered to currently running top-most instance.')]);
  check('12. a part named ...ExceptionReceiver is no error, and SecurityExceptionReceiver no refusal', errs[0] === '' && errs[1] === 'false', JSON.stringify(errs));
  check('    an exception: the line after "Exception occurred", without its class', errs[2] === 'Permission android.permission.INTERNET requested by x is not a changeable permission type', errs[2]);
  check('    am start: the Error line, not "Error type 3"', errs[3] === 'Activity class {x/x.A} does not exist.', errs[3]);
  check('    older pm: "Operation not allowed" and the class taken off', errs[4] === 'Package x has not requested permission y', errs[4]);
  check('    the shell\'s not found is one; am start\'s Warning is not', errs[5] === '/system/bin/sh: su: not found' && errs[6] === '', JSON.stringify(errs.slice(5)));
  const sh = await ev(page, () => { const d = cdParsePkgDump(['Packages:', '  Package [com.wear.sys] (abc):', '    userId=1000', '    sharedUser=SharedUserSetting{9f8e android.uid.system/1000}', '    versionCode=34 minSdk=30 targetSdk=34',
    '    requested permissions:', '      android.permission.WRITE_SECURE_SETTINGS', '      android.permission.CAMERA', '    User 0: ceDataInode=1 installed=true', '      firstInstallTime=2026-01-02 03:04:05',
    'Shared users:', 'Shared users:', '  SharedUser [android.uid.system] (9f8e):', '    userId=1000', '    Packages', '      PackageSetting{1 com.wear.sys/1000}', '    install permissions:', '      android.permission.WRITE_SECURE_SETTINGS: granted=true',
    '    User 0: ', '      gids=[1000]', '      runtime permissions:', '        android.permission.CAMERA: granted=true, flags=[ GRANTED_BY_DEFAULT ]', '    User 10: ', '      runtime permissions:', '        android.permission.CAMERA: granted=false, flags=[ ]'].join('\n'), 'com.wear.sys');
    return { inst: d.install, rt: d.runtime, v: d.versionCode, raw: /SharedUser \[android\.uid\.system\]/.test(d.raw), su: d.sharedUser }; });
  check('    an app with a sharedUserId: its grants from the shared user (user 0\'s), its own fields kept', sh.inst['android.permission.WRITE_SECURE_SETTINGS'] === true && sh.rt['android.permission.CAMERA'] && sh.rt['android.permission.CAMERA'].granted === true && sh.v === '34' && sh.raw && sh.su === 'android.uid.system', JSON.stringify(sh));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
