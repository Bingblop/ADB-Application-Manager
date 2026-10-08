// The Connected Devices tab (adb to another device, a Wear OS watch first): the tab itself, adding a device (pair, connect, scan, the Bluetooth link), the device card, the
// Apps list (filters, enable / disable / uninstall / reinstall, one and many), sending packages (files, apps from this phone, progress, results), the console, logcat, the
// device's files, hidden settings and the density / size controls with their automatic undo.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    const WATCH = '192.168.1.20:5555';
    const st = window.__st = { calls: [], devs: ['self', 'watch'], dens: 280, size: '', disabled: new Set(['com.google.android.deskclock']), uninstalled: new Set(['com.google.android.apps.fitness']), settings: { 'screen_off_timeout': '30000', 'airplane_mode_on': '0', 'zen_mode': '0' }, installFail: false, pickNext: [] };
    const apps = [['com.google.android.deskclock', 1], ['com.example.fit', 0], ['com.google.android.apps.maps', 1], ['com.google.android.apps.fitness', 1], ['com.wear.weather', 0]];
    const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
    const shell = (cmd) => {
      let m;
      if (/^for p in/.test(cmd)) return ['ro.product.manufacturer=samsung', 'ro.product.model=SM-R930', 'ro.product.name=e1sxxx', 'ro.build.version.release=14', 'ro.build.version.sdk=34', 'ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi', 'ro.sf.lcd_density=320', 'ro.build.characteristics=watch', 'persist.sys.locale=en-US', 'ro.product.locale=en-US',
        'wmsize=Physical size: 450x450 ' + (st.size ? 'Override size: ' + st.size + ' ' : ''), 'wmdens=Physical density: 320 ' + (st.dens !== 320 ? 'Override density: ' + st.dens + ' ' : ''), 'batt=  level: 83   status: 3 '].join('\n');
      if (/^echo '#ALL'/.test(cmd)) {
        const all = apps.map(a => 'package:' + (a[1] ? '/system/app/' + a[0] + '/x.apk' : '/data/app/~~ab==/' + a[0] + '-1==/base.apk') + '=' + a[0]);
        const inst = apps.filter(a => !st.uninstalled.has(a[0])).map(a => 'package:' + a[0]);
        return ['#ALL'].concat(all, ['#INST'], inst, ['#DIS'], Array.from(st.disabled).map(p => 'package:' + p), ['#SYS'], apps.filter(a => a[1]).map(a => 'package:' + a[0])).join('\n');
      }
      if ((m = /^pm disable-user --user 0 '(.+)'$/.exec(cmd))) { if (m[1] === 'com.example.fit' && st.refuseFit) return 'Exception occurred while executing: Shell cannot change component state'; st.disabled.add(m[1]); return 'Package ' + m[1] + ' new state: disabled-user'; }
      if ((m = /^pm enable '(.+)'$/.exec(cmd))) { st.disabled.delete(m[1]); return 'Package ' + m[1] + ' new state: enabled'; }
      if ((m = /^pm uninstall --user 0 '(.+)'$/.exec(cmd))) { st.uninstalled.add(m[1]); return 'Success'; }
      if ((m = /^cmd package install-existing '(.+)'$/.exec(cmd))) { st.uninstalled.delete(m[1]); return 'Package ' + m[1] + ' installed for user: 0'; }
      if ((m = /^am force-stop/.exec(cmd))) return '';
      if ((m = /^monkey -p '(.+)'/.exec(cmd))) return 'Events injected: 1';
      if ((m = /^pm clear/.exec(cmd))) return 'Success';
      if ((m = /^pm path '(.+)'$/.exec(cmd))) return 'package:/data/app/~~ab==/' + m[1] + '-1==/base.apk\npackage:/data/app/~~ab==/' + m[1] + '-1==/split_config.xxhdpi.apk';
      if (/^dumpsys package/.test(cmd)) return '    versionCode=12 minSdk=30 targetSdk=34\n    versionName=2.4.1\n    firstInstallTime=2026-01-02 03:04:05';
      if (/^logcat -c/.test(cmd)) return '';
      if (/^logcat -d/.test(cmd)) return '--------- beginning of main\n10-03 04:00:01.123  1000  1001 I WatchFace: tick 1\n10-03 04:00:02.456  1000  1002 W Battery: low\n10-03 04:00:03.789  1000  1003 E Sensors: failed to read';
      if (/^ls -la '\/sdcard\/'$/.test(cmd)) return 'total 12\ndrwxrwx--x 2 u0_a1 media_rw 4096 2026-01-02 03:04 Download\ndrwxrwx--x 2 u0_a1 media_rw 4096 2026-01-02 03:04 Music\n-rw-rw---- 1 u0_a1 media_rw 12 2026-01-02 03:04 notes.txt';
      if (/^ls -la '\/sdcard\/Download\/'$/.test(cmd)) return 'total 0\n-rw-rw---- 1 u0_a1 media_rw 2048 2026-01-02 03:04 face.apk';
      if (/^ls -la '\/nope\/'$/.test(cmd)) return "ls: /nope/: No such file or directory";
      if (/^head -c 40000 '\/sdcard\/notes.txt'$/.test(cmd)) return 'hello from the watch';
      if ((m = /^settings list (\w+)$/.exec(cmd))) return Object.keys(st.settings).map(k => k + '=' + st.settings[k]).join('\n');
      if ((m = /^settings put (\w+) '(.+?)' '(.*)'$/.exec(cmd))) { st.settings[m[2]] = m[3]; return ''; }
      if ((m = /^settings delete (\w+) '(.+)'$/.exec(cmd))) { delete st.settings[m[2]]; return 'Deleted 1 rows'; }
      if ((m = /^wm density (\d+)$/.exec(cmd))) { st.dens = +m[1]; return ''; }
      if (cmd === 'wm density reset') { st.dens = 320; return ''; }
      if ((m = /^wm size (\S+)$/.exec(cmd))) { st.size = m[1]; return ''; }
      if (cmd === 'wm size reset') { st.size = ''; return ''; }
      if (/^mv /.test(cmd) || /^rm /.test(cmd) || /^mkdir /.test(cmd)) return '';
      if (cmd === 'getprop ro.product.model') return 'SM-R930';
      if (cmd === 'reboot -p') return '';
      return 'sh: ' + cmd.split(' ')[0] + ': not found';
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.camera', name: 'Camera', isSystem: true }, { pkg: 'com.example.notes', name: 'Notes', isSystem: false }, { pkg: 'com.example.fit', name: 'Fit', isSystem: false }]); },
      getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      copyToClipboard(t) { st.copied = t; }, shareTextFile(n, t) { st.shared = n + ':' + t.length; return ''; },
      cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555', '192.168.1.5:41000', 'localhost:5555']); },
      cdAdb(tag, serial, argsJson, timeout) {
        const a = JSON.parse(argsJson); st.calls.push({ serial, a });
        if (a[0] === 'devices') {
          let out = 'List of devices attached\n';
          if (st.devs.includes('self')) out += '127.0.0.1:5555\tdevice product:phone model:Pixel_8 device:husky transport_id:1\n';
          if (st.devs.includes('watch')) out += WATCH + '\tdevice product:e1sxxx model:SM_R930 device:e1s transport_id:2\n';
          if (st.devs.includes('tablet')) out += 'R52W1234\tunauthorized transport_id:3\n';
          if (st.devs.includes('bt')) out += '127.0.0.1:4444\tdevice product:bt model:Bluetooth_Watch device:bt transport_id:4\n';
          return reply(tag, out);
        }
        if (a[0] === 'pair') return reply(tag, a[2] === '000000' ? 'Failed: Wrong password or connection dropped' : 'Successfully paired to ' + a[1] + ' [guid=adb-AAA-bbb]');
        if (a[0] === 'connect') { if (!st.devs.includes('watch') && /192\.168\.1\.20/.test(a[1])) st.devs.push('watch'); if (a[1] === '127.0.0.1:4444') st.devs.push('bt'); return reply(tag, /192\.168\.1\.99/.test(a[1]) ? 'failed to connect to \'' + a[1] + '\': Connection refused' : 'connected to ' + a[1]); }
        if (a[0] === 'disconnect') { st.devs = st.devs.filter(d => d !== 'watch'); return reply(tag, 'disconnected ' + a[1]); }
        if (a[0] === 'mdns') return reply(tag, 'List of discovered mdns services\nadb-ABC-xyz\t_adb-tls-connect._tcp.\t192.168.1.20:41235\nadb-ABC-pair\t_adb-tls-pairing._tcp.\t192.168.1.21:37215\n');
        if (a[0] === 'forward') return reply(tag, '4444');
        if (a[0] === 'pull') return reply(tag, '/x: 1 file pulled, 0 skipped. 2.0 MB/s (2048 bytes in 0.001s)');
        if (a[0] === 'push') return reply(tag, '/x: 1 file pushed, 0 skipped.');
        if (a[0] === 'reboot') return reply(tag, '');
        if (a[0] === 'shell') { if (st.slow && /^sleep/.test(a[1])) { st.slowTag = tag; return; } return reply(tag, shell(a[1])); }
        return reply(tag, 'adb: unknown command ' + a[0]);
      },
      cdCancel(tag) { st.cancelled = tag; if (tag === st.slowTag) setTimeout(() => window.onCdResult({ tag, out: 'Error: stopped', ms: 1 }), 5); },
      cdDownloadDir(s) { return '/storage/emulated/0/Download/ADB App Manager/Devices/' + s.replace(':', '_'); },
      cdPickPackages(tag) {
        st.calls.push({ pick: tag });
        const files = tag === 'push' ? [{ name: 'face.apk', path: '/cache/cd_pick/1/face.apk', size: 4096 }] : st.pickNext;
        setTimeout(() => window.onCdPicked({ tag, files }), 20);
      },
      cdPhoneApk(pkg) { return JSON.stringify(pkg === 'com.example.gone' ? { ok: false, error: 'no' } : { ok: true, pkg, label: pkg === 'com.example.notes' ? 'Notes' : 'Fit', paths: ['/data/app/' + pkg + '/base.apk', '/data/app/' + pkg + '/split_en.apk'] }); },
      cdInstall(tag, serial, items, opts, profile) {
        st.install = { tag, serial, items: JSON.parse(items), opts: JSON.parse(opts), profile: JSON.parse(profile) };
        const its = st.install.items;
        its.forEach((it, i) => setTimeout(() => window.onCdProgress({ tag, index: i, total: its.length, name: it.name, line: 'Installing 1 file' }), 10 + i * 10));
        setTimeout(() => window.onCdResult({ tag, install: { ok: !st.installFail, results: its.map((it, i) => ({ name: it.name, ok: !(st.installFail && i === 1), out: st.installFail && i === 1 ? 'Failure [INSTALL_FAILED_OLDER_SDK]' : 'Success', files: 1 })) } }), 120);
      },
      cdBtDevices() { return JSON.stringify({ available: true, enabled: true, devices: [{ name: 'Galaxy Watch6', address: 'AA:BB', kind: 'wearable' }, { name: 'Headphones', address: 'CC:DD', kind: 'audio' }] }); },
      cdBtSend(files, mime) { st.bt = { files: JSON.parse(files), mime }; return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => kvSet('cd_autore', false));        // this script moves devices in and out by hand: no automatic reconnecting (t128 covers it)
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const calls = () => ev(() => window.__st.calls.map(c => c.pick ? 'pick:' + c.pick : (c.serial ? c.serial + ' ' : '') + c.a.join(' ')));
  // the last thing the page asked for, leaving out the device-list polls the tab makes now and then (they can land after any step)
  const lastCall = () => ev(() => { const cs = window.__st.calls.filter(c => c.pick || c.a.join(' ') !== 'devices -l'); const c = cs[cs.length - 1]; return c.pick ? 'pick:' + c.pick : c.a.join(' '); });

  // the tab
  const tab = await ev(() => { const t = TAB_DEFS.map(x => x.key); return { ix: t.indexOf('devices'), after: t[t.indexOf('devices') - 1], onBar: !!document.querySelector('.tab-btn[data-tab="devices"]'), label: document.querySelector('.tab-btn[data-tab="devices"]').innerText.replace(/\n/g, ' ') }; });
  check('the tab sits after the Task Manager (before About) and is on the bar', tab.after === 'taskmgr' && tab.onBar && tab.label === 'Connected Devices', JSON.stringify(tab));

  // no device yet: this phone's own endpoints are left out, the empty state offers Add device
  await ev(() => { window.__st.devs = ['self']; switchView('devices'); }); await wait(400);
  const empty = await ev(() => ({ empty: getComputedStyle(document.getElementById('cdEmpty')).display !== 'none', work: getComputedStyle(document.getElementById('cdWork')).display, sel: document.getElementById('cdDeviceSel').disabled, opt: document.getElementById('cdDeviceSel').innerText }));
  check('this phone itself is not listed; the empty state shows', empty.empty && empty.work === 'none' && empty.sel && /No device/.test(empty.opt), JSON.stringify(empty));

  // add device: pair, wrong code, connect, a refused connect
  await page.click('#cdEmpty .mode-action-btn'); await wait(250);
  check('Add device opens on the Wi-Fi tab', await ev(() => document.getElementById('cdAddModal').classList.contains('show') && document.getElementById('cdAddPane-wifi').classList.contains('active')));
  await ev(() => { document.getElementById('cdPairAddr').value = '192.168.1.20:37215'; document.getElementById('cdPairCode').value = '12'; }); await page.click('#cdPairBtn'); await wait(100);
  check('a short pairing code is turned down before adb runs', /6 digits/.test(await toast()) && !(await calls()).some(c => /^pair/.test(c)));
  await ev(() => { document.getElementById('cdPairCode').value = '000000'; }); await page.click('#cdPairBtn'); await wait(150);
  check('a wrong code shows adb\'s answer', /Wrong password/.test(await ev(() => document.getElementById('cdAddLog').innerText)));
  await ev(() => { document.getElementById('cdPairCode').value = '123456'; }); await page.click('#cdPairBtn'); await wait(150);
  check('pairing sends the address and the code', (await calls()).includes('pair 192.168.1.20:37215 123456') && /Successfully paired/.test(await ev(() => document.getElementById('cdAddLog').innerText)));
  await ev(() => { document.getElementById('cdConnAddr').value = '192.168.1.99:5555'; }); await page.click('#cdConnBtn'); await wait(200);
  check('a refused connect says so and stays open', /Connection refused/.test(await ev(() => document.getElementById('cdAddLog').innerText)) && await ev(() => document.getElementById('cdAddModal').classList.contains('show')));
  await ev(() => { document.getElementById('cdConnAddr').value = '192.168.1.20:5555'; }); await page.click('#cdConnBtn'); await wait(600);
  const conn = await ev(() => ({ open: document.getElementById('cdAddModal').classList.contains('show'), cur: cd.serial, work: getComputedStyle(document.getElementById('cdWork')).display, recent: kvGet('cd_recent', []).map(r => r.addr), name: document.querySelector('.cd-dev-name').innerText }));
  check('connecting closes the sheet, selects the watch and remembers it', !conn.open && conn.cur === '192.168.1.20:5555' && conn.work !== 'none' && conn.recent[0] === '192.168.1.20:5555' && conn.name === 'Samsung SM-R930', JSON.stringify(conn));

  // the device card
  const card = await ev(() => ({ text: document.getElementById('cdInfo').innerText.replace(/\s+/g, ' '), ico: document.querySelector('.cd-dev-ico').innerText }));
  check('the card shows the model, Android, screen, density, battery, link and Wear OS', /Samsung SM-R930/.test(card.text) && /Android 14 \(API 34\)/.test(card.text) && /450x450/.test(card.text) && /280 dpi/.test(card.text) && /Battery 83%/i.test(card.text) && /Wi-Fi/i.test(card.text) && /Wear OS/i.test(card.text) && card.ico === 'W', card.text);
  await page.screenshot({ path: 'cd_card.png' });

  // scan and the Bluetooth link
  await ev(() => cdAddOpen('scan')); await wait(150); await page.click('#cdScanBtn'); await wait(200);
  const scan = await ev(() => Array.from(document.querySelectorAll('.cd-found')).map(f => f.innerText.replace(/\s+/g, ' ') ));
  check('the scan lists a device to connect and one waiting for a code', scan.length === 2 && /192\.168\.1\.20:41235.*ready to connect/.test(scan[0]) && /37215.*pairing code/.test(scan[1]), JSON.stringify(scan));
  await ev(() => document.querySelectorAll('.cd-found .cd-row-act')[1].click()); await wait(100);
  check('Pair from the scan fills the pairing address on the Wi-Fi tab', await ev(() => document.getElementById('cdPairAddr').value === '192.168.1.21:37215' && document.getElementById('cdAddPane-wifi').classList.contains('active')));
  await ev(() => cdAddTab('bt')); await wait(50);
  await ev(() => { window.__st.devs = ['watch']; }); await page.click('#cdBtConnBtn'); await wait(500);
  check('the Bluetooth link needs this phone\'s own ADB', /not connected to its own ADB/.test(await ev(() => document.getElementById('cdAddLog').innerText)));
  await ev(() => { window.__st.devs = ['self', 'watch']; window.__st.calls.length = 0; }); await page.click('#cdBtConnBtn'); await wait(900);
  const bt = await calls();
  check('the Bluetooth link forwards through this phone then connects to the port', bt.some(c => c === '127.0.0.1:5555 forward tcp:4444 localabstract:/adb-hub') && bt.includes('connect 127.0.0.1:4444'), JSON.stringify(bt));
  const btSel = await ev(() => ({ serials: cd.devices.map(d => d.serial), cur: cd.serial, tr: cdTransport('127.0.0.1:4444'), saved: kvGet('cd_bt', []) }));
  check('the linked watch is a device with the Bluetooth link transport, kept for next time', btSel.serials.includes('127.0.0.1:4444') && btSel.cur === '127.0.0.1:4444' && btSel.tr === 'Bluetooth link' && btSel.saved[0] === '127.0.0.1:4444', JSON.stringify(btSel));
  await ev(() => { window.__st.devs = ['self', 'watch', 'tablet']; cdSelect('192.168.1.20:5555'); }); await wait(700);
  const picker = await ev(() => Array.from(document.getElementById('cdDeviceSel').options).map(o => o.innerText));
  check('the picker lists every device with its state', picker.length === 2 && /SM-R930/.test(picker[0]) && /not allowed yet/.test(picker[1]), JSON.stringify(picker));
  await ev(() => cdSelect('R52W1234')); await wait(500);
  check('a device that has not allowed this phone says what to do and hides the tools', await ev(() => /has not allowed this phone yet/.test(document.getElementById('cdInfo').innerText) && getComputedStyle(document.getElementById('cdWork')).display === 'none'));
  await ev(() => { window.__st.devs = ['self', 'watch']; cdSelect('192.168.1.20:5555'); }); await wait(900);

  // Apps
  const apps0 = await ev(() => ({ n: document.querySelectorAll('#cdAppList .app-card').length, names: Array.from(document.querySelectorAll('#cdAppList .app-name')).map(e => e.innerText), count: document.getElementById('cdAppCount').innerText, chips: Array.from(document.querySelectorAll('#cdAppFilters .filter-chip')).map(c => c.innerText) }));
  check('the apps of the device are listed with phone names where the phone has the app', apps0.n === 5 && apps0.names.includes('Fit') && apps0.names.includes('Deskclock') && /5 apps/.test(apps0.count), JSON.stringify(apps0));
  check('the filter chips carry counts; an uninstalled app counts only as Uninstalled', apps0.chips.join('|') === 'All 5|3rd Party 2|System 2|Enabled 3|Disabled 1|Uninstalled 1', apps0.chips.join('|'));
  const rowOf = pkg => `#cdcard_${pkg.replace(/\./g, '\\.')}`;
  const badges = await ev(() => { const g = p => Array.from(document.getElementById('cdcard_' + p).querySelectorAll('.tag-badge')).map(b => b.innerText).join(',') + '/' + document.getElementById('cdcard_' + p).querySelector('.cd-row-act').innerText; return [g('com.google.android.deskclock'), g('com.example.fit'), g('com.google.android.apps.fitness')]; });
  check('each row shows its state and the action that fits it', badges[0] === 'DISABLED,SYSTEM/Enable' && badges[1] === 'USER/Disable' && badges[2] === 'UNINSTALLED,SYSTEM/Reinstall', JSON.stringify(badges));
  await ev(() => cdFilter('system')); await ev(() => cdFilter('disabled'));
  check('3rd Party / System and Enabled / Disabled combine and show the Clear filters chip', await ev(() => ({ n: document.querySelectorAll('#cdAppList .app-card').length, sum: getComputedStyle(document.getElementById('cdAppSummary')).display !== 'none' && /System \+ Disabled/.test(document.getElementById('cdAppSummary').innerText) })).then(r => r.n === 1 && r.sum));
  await ev(() => { cd.filters.clear(); cdFilter('uninstalled'); cdFilter('system'); });
  check('Uninstalled with System narrows to the removed system app', await ev(() => Array.from(document.querySelectorAll('#cdAppList .app-card')).map(c => c.dataset.pkg).join()) === 'com.google.android.apps.fitness');
  await ev(() => cdFilter('all'));
  await ev(() => { document.getElementById('cdAppSearch').value = 'weather'; cdAppsRender(); });
  check('search finds an app by name or package', await ev(() => document.querySelectorAll('#cdAppList .app-card').length) === 1);
  await ev(() => { document.getElementById('cdAppSearch').value = ''; cdAppsRender(); });

  // one app: Disable then Enable, Reinstall, Uninstall (asks first)
  await ev(() => document.querySelector('#cdcard_com\\.example\\.fit .cd-row-act').click()); await wait(250);
  check('Disable runs pm disable-user and the row changes', (await calls()).includes("192.168.1.20:5555 shell pm disable-user --user 0 'com.example.fit'") && await ev(() => /DISABLED/.test(document.getElementById('cdcard_com.example.fit').innerText) && document.querySelector('#cdcard_com\\.example\\.fit .cd-row-act').innerText === 'Enable'));
  await ev(() => document.querySelector('#cdcard_com\\.google\\.android\\.apps\\.fitness .cd-row-act').click()); await wait(250);
  check('Reinstall runs install-existing and the app is back', (await calls()).some(c => /cmd package install-existing 'com\.google\.android\.apps\.fitness'/.test(c)) && await ev(() => !/UNINSTALLED/.test(document.getElementById('cdcard_com.google.android.apps.fitness').innerText)));
  await ev(() => cdAppMenu('com.example.fit')); await wait(200);
  const menu = await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).map(b => b.innerText));
  check('the app menu has Open, Force stop, Clear data, Enable, Uninstall, Pull APK, Details, App page, Select, Copy', menu.join('|') === 'Open|Force stop|Clear data|Enable|Uninstall|Pull APK|Details|App page on device|Select|Copy package', menu.join('|'));
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Uninstall').click()); await wait(150);
  check('Uninstall asks first and nothing runs until it is confirmed', await ev(() => document.getElementById('cdAskModal').classList.contains('show') && /Uninstall Fit/.test(document.getElementById('cdAskTitle').innerText)) && !(await calls()).some(c => /pm uninstall/.test(c)));
  await page.click('#cdAskOk'); await wait(250);
  check('confirmed, it runs pm uninstall --user 0 and the row says UNINSTALLED', (await calls()).includes("192.168.1.20:5555 shell pm uninstall --user 0 'com.example.fit'") && await ev(() => /UNINSTALLED/.test(document.getElementById('cdcard_com.example.fit').innerText)));
  await ev(() => cdAppMenu('com.example.fit')); await wait(150);
  check('an uninstalled app\'s menu offers Reinstall, not the running tools', await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).map(b => b.innerText).join('|')) === 'Reinstall|Select|Copy package');
  await ev(() => cdSheetClose());
  await ev(() => cdAppMenu('com.wear.weather')); await wait(150);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Pull APK').click()); await wait(400);
  const pulls = (await calls()).filter(c => /^192\.168\.1\.20:5555 pull/.test(c));
  check('Pull APK pulls the base and its splits to the phone\'s Download folder', pulls.length === 2 && /com\.wear\.weather\.apk$/.test(pulls[0]) && /com\.wear\.weather_split_config\.xxhdpi\.apk$/.test(pulls[1]) && /Download\/ADB App Manager\/Devices\/192\.168\.1\.20_5555\//.test(pulls[0]), JSON.stringify(pulls));
  await ev(() => cdAppMenu('com.wear.weather')); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Details').click()); await wait(300);
  check('Details shows what dumpsys says', await ev(() => /versionName=2\.4\.1/.test(document.getElementById('cdSheetBody').innerText)));
  await ev(() => cdSheetClose());

  // many apps: select two, disable, a failure is reported
  await ev(async () => { await cdDo('reinstall', 'com.example.fit'); });             // (fit was uninstalled above: an uninstalled app cannot be enabled, and the app now checks)
  await ev(() => { window.__st.refuseFit = true; cdFilter('all'); cdToggleSel('com.wear.weather'); cdToggleSel('com.example.fit'); });
  check('selecting shows the batch bar with a count', await ev(() => document.getElementById('cdBatch').classList.contains('show') && document.getElementById('cdBatchCount').innerText === '2 selected'));
  await ev(() => cdBatch('enable')); await wait(500);
  check('a batch with no failure toasts the count and clears the selection', /Enabled 2 apps/.test(await toast()) && await ev(() => !cd.sel.size && !document.getElementById('cdBatch').classList.contains('show')));
  await ev(() => { cdToggleSel('com.wear.weather'); cdToggleSel('com.example.fit'); cdBatch('disable'); }); await wait(250);
  check('disabling several asks first', await ev(() => document.getElementById('cdAskModal').classList.contains('show') && /Disable 2 apps/.test(document.getElementById('cdAskTitle').innerText)));
  await page.click('#cdAskOk'); await wait(2200);          // (the commands, then the look at the device, which asks twice when an app is not as wanted)
  const fail = await ev(() => ({ open: document.getElementById('cdSheet').classList.contains('show'), title: document.getElementById('cdSheetTitle').innerText, body: document.getElementById('cdSheetBody').innerText }));
  check('the one the device refused is named with its reason, the other went through', fail.open && /1 of 2 did not work/.test(fail.title) && /Shell cannot change component state/.test(fail.body) && await ev(() => cdApp('com.wear.weather').disabled && !cdApp('com.example.fit').disabled), JSON.stringify(fail));
  await ev(() => { cdSheetClose(); window.__st.refuseFit = false; });
  await page.screenshot({ path: 'cd_apps.png' });

  // Send: files
  await ev(() => cdSub('send')); await wait(150);
  check('nothing chosen yet', await ev(() => /Nothing chosen yet/.test(document.getElementById('cdQueue').innerText)));
  await ev(() => { window.__st.pickNext = [{ name: 'face.apk', path: '/cache/cd_pick/1/face.apk', size: 2048000 }, { name: 'game.xapk', path: '/cache/cd_pick/1/game.xapk', size: 90000000 }, { name: 'notes.txt', path: '/cache/cd_pick/1/notes.txt', size: 5 }, { name: 'gone.apk', error: 'could not be read' }]; cdPickFiles(); }); await wait(250);
  const q1 = await ev(() => cd.queue.map(q => q.name + '|' + (q.archive ? 'archive' : 'paths') + '|' + q.sub));
  check('picked files queue by kind; a file that is no package is turned down', q1.length === 2 && /face\.apk\|paths\|APK/.test(q1[0]) && /game\.xapk\|archive\|Package file/.test(q1[1]), JSON.stringify(q1));
  await ev(() => { window.__st.pickNext = [{ name: 'base.apk', path: '/c/base.apk', size: 1 }, { name: 'split_config.en.apk', path: '/c/s1.apk', size: 1 }, { name: 'split_config.xxhdpi.apk', path: '/c/s2.apk', size: 1 }]; cdPickFiles(); }); await wait(200);
  check('several APKs that are a base and its splits queue as one split APK', await ev(() => { const q = cd.queue[2]; return cd.queue.length === 3 && q.paths.length === 3 && /Split APK/.test(q.sub); }));
  // from the phone
  await ev(() => cdPhoneOpen()); await wait(200);
  const ph = await ev(() => Array.from(document.querySelectorAll('#cdPhoneList .app-name')).map(e => e.innerText));
  check('the phone\'s 3rd party apps are offered', ph.join() === 'Fit,Notes', ph.join());
  await ev(() => { cdPhoneToggle('com.example.notes'); }); await ev(() => cdPhoneAdd()); await wait(100);
  const q2 = await ev(() => cd.queue[3]);
  check('an app from this phone queues with its base and splits', q2.name === 'Notes' && q2.paths.length === 2 && q2.sendFiles[0].name === 'com.example.notes.apk' && q2.sendFiles[1].name === 'com.example.notes_split_en.apk', JSON.stringify(q2));
  await ev(() => cdQueueDrop(0));
  check('an item can be removed', await ev(() => cd.queue.length) === 3);
  // send
  await ev(() => { document.getElementById('cdOptGrant').checked = true; window.__st.installFail = true; }); await page.click('#cdSendBtn'); await wait(40);
  const sending = await ev(() => ({ btn: getComputedStyle(document.getElementById('cdSendBtn')).display, stop: getComputedStyle(document.getElementById('cdSendStop')).display, busy: cdBusyReason(), back: backBusyReason() }));
  check('while sending: Send is replaced by Stop and leaving is warned about', sending.btn === 'none' && sending.stop !== 'none' && /sent to a device/.test(sending.busy) && /sent to a device/.test(sending.back), JSON.stringify(sending));
  await wait(400);
  const inst = await ev(() => ({ serial: window.__st.install.serial, opts: window.__st.install.opts, profile: window.__st.install.profile, items: window.__st.install.items.map(i => i.name + ':' + (i.archive ? 'a' : i.paths.length)) }));
  check('the device, the options and its profile (ABIs, density, language) go to the installer', inst.serial === '192.168.1.20:5555' && inst.opts.grantAll === true && inst.opts.reinstall === true && inst.opts.downgrade === false && inst.profile.abis[0] === 'arm64-v8a' && inst.profile.dpi === 280 && inst.profile.lang === 'en', JSON.stringify(inst));
  const res = await ev(() => ({ rows: Array.from(document.querySelectorAll('.cd-queue-row')).map(r => r.className.replace('cd-queue-row', '').trim() + ':' + r.querySelector('.cd-queue-sub').innerText.replace(/\n/g, ' ')), line: document.getElementById('cdSendLine').innerText, sending: !!cd.sending }));
  check('each row shows its result; the line says how many went in', !res.sending && /^ok:Installed/.test(res.rows[0]) && /^bad:Failure \[INSTALL_FAILED_OLDER_SDK\]/.test(res.rows[1]) && /^ok:Installed/.test(res.rows[2]) && /2 of 3 packages installed/.test(res.line), JSON.stringify(res));
  await page.screenshot({ path: 'cd_send.png', fullPage: false });
  // Bluetooth
  await ev(() => cdBtLoad()); await ev(() => cdBtSend());
  const btr = await ev(() => ({ list: document.getElementById('cdBtList').innerText.replace(/\s+/g, ' '), sent: window.__st.bt && window.__st.bt.files.length, mime: window.__st.bt && window.__st.bt.mime }));
  check('paired Bluetooth devices are listed and the queued files go to Bluetooth sharing', /Galaxy Watch6/.test(btr.list) && /watch or wearable/.test(btr.list) && btr.sent === 6 && btr.mime === 'application/octet-stream', JSON.stringify(btr));

  // Console
  await ev(() => cdSub('console')); await wait(100);
  check('the console offers command chips', await ev(() => document.querySelectorAll('#cdChips .cd-chip').length) > 10);
  await ev(() => { document.getElementById('cdInput').value = 'getprop ro.product.model'; }); await page.click('#cdRunBtn'); await wait(200);
  check('a shell command runs on the device and its output shows', /\$ getprop ro\.product\.model\s+SM-R930/.test(await ev(() => document.getElementById('cdTerm').innerText)) && (await lastCall()) === 'shell getprop ro.product.model');
  await ev(() => { document.getElementById('cdInput').value = 'adb pull /sdcard/a.txt /sdcard/'; cdRunOrStop(); }); await wait(200);
  check('a line that starts with adb goes to adb itself, with the device', (await lastCall()) === 'pull /sdcard/a.txt /sdcard/' && (await calls()).slice(-1)[0].startsWith('192.168.1.20:5555 pull'));
  await ev(() => { document.getElementById('cdInput').value = 'adb devices -l'; cdRunOrStop(); }); await wait(200);
  check('adb\'s own commands go without a device', (await ev(() => window.__st.calls.slice(-1)[0].serial)) === '');
  await ev(() => { document.getElementById('cdInput').value = 'adb kill-server'; cdRunOrStop(); }); await wait(100);
  check('stopping the adb this app runs on is not sent', /would stop the adb this app runs on/.test(await ev(() => document.getElementById('cdTerm').innerText)) && !(await calls()).some(c => /kill-server/.test(c)));
  await ev(() => { document.getElementById('cdInput').value = 'adb -s other-device kill-server'; cdRunOrStop(); }); await wait(100);
  check('kill-server is not sent even after other words (-s serial)', !(await calls()).some(c => /kill-server/.test(c)) && (await ev(() => document.getElementById('cdTerm').innerText.match(/would stop the adb/g).length)) === 2);
  await ev(() => cdModeToggle());
  await ev(() => { document.getElementById('cdInput').value = 'adb shell "getprop ro.product.model"'; cdRunOrStop(); }); await wait(200);
  check('in adb mode a leading adb is taken off (and a shell command may hold the word kill-server)', (await lastCall()) === 'shell getprop ro.product.model');
  await ev(() => { document.getElementById('cdInput').value = 'shell "getprop ro.product.model"'; cdRunOrStop(); }); await wait(200);
  check('adb mode takes quoted arguments', (await lastCall()) === 'shell getprop ro.product.model' && await ev(() => document.getElementById('cdModeBtn').innerText) === 'adb');
  await ev(() => cdModeToggle());
  await ev(() => { window.__st.slow = true; document.getElementById('cdInput').value = 'sleep 100'; cdRunOrStop(); }); await wait(100);
  check('a running command turns Run into Stop', await ev(() => document.getElementById('cdRunBtn').innerText === 'Stop' && /running/.test(document.getElementById('cdTerm').innerText)));
  await page.click('#cdRunBtn'); await wait(150);
  check('Stop ends it', await ev(() => document.getElementById('cdRunBtn').innerText === 'Run' && window.__st.cancelled === cd.termRun + '' || document.getElementById('cdRunBtn').innerText === 'Run'));
  await ev(() => { window.__st.slow = false; cdHistStep(-1); });
  check('the history steps back through earlier lines', await ev(() => document.getElementById('cdInput').value) === 'sleep 100');
  await ev(() => { document.getElementById('cdInput').value = ''; cdTermCopy(); });
  check('Copy gives the whole session', await ev(() => /getprop ro\.product\.model/.test(window.__st.copied || '')));
  await page.screenshot({ path: 'cd_console.png' });

  // Logcat
  await ev(() => cdSub('logcat')); await wait(500);
  const lc = await ev(() => ({ rows: document.querySelectorAll('#cdLcOut .lc-row').length, lvls: Array.from(document.querySelectorAll('#cdLcOut .lc-row')).map(r => r.className.split(' ').pop()).join(), tag: document.querySelector('#cdLcOut .lc-tag').innerText }));
  check('the device log is read and drawn like the phone\'s own logcat', lc.rows === 3 && lc.lvls === 'lvl-I,lvl-W,lvl-E' && lc.tag === 'WatchFace', JSON.stringify(lc));
  check('the level goes to the device as a logcat filter', (await calls()).some(c => /shell logcat -d -v threadtime -t 500 '\*:I'$/.test(c)));
  await ev(() => { document.getElementById('cdLcFilter').value = 'sensors'; cdLcDraw(false); });
  check('the filter box narrows the rows and marks the match', await ev(() => document.querySelectorAll('#cdLcOut .lc-row').length === 1 && document.querySelectorAll('#cdLcOut mark').length >= 1));
  await ev(() => { document.getElementById('cdLcFilter').value = ''; cdLcTogglePlay(); }); await wait(100);
  check('Play follows the log and the button says Pause', await ev(() => document.getElementById('cdLcPlay').innerText === 'Pause' && !!cd.lcTimer));
  await ev(() => cdSub('apps'));
  check('leaving the Logcat sub-tab stops following', await ev(() => !cd.lcTimer && document.getElementById('cdLcPlay').innerText === 'Play'));
  await ev(() => { cdSub('logcat'); cdLcClear(); }); await wait(200);
  check('Clear empties the device\'s log buffer', (await calls()).some(c => /shell logcat -c$/.test(c)));

  // Files
  await ev(() => cdSub('files')); await wait(400);
  const fm = await ev(() => ({ path: document.getElementById('cdFmPath').innerText, rows: Array.from(document.querySelectorAll('#cdFmList .cd-fm-name')).map(e => e.innerText), count: document.getElementById('cdFmCount').innerText }));
  check('the device\'s storage opens with folders first', fm.path === '/sdcard' && fm.rows.join() === 'Download,Music,notes.txt' && fm.count === '3 items', JSON.stringify(fm));
  await ev(() => document.querySelector('#cdFmList .cd-fm-row').click()); await wait(300);
  check('a folder opens', await ev(() => document.getElementById('cdFmPath').innerText) === '/sdcard/Download' && await ev(() => document.querySelectorAll('#cdFmList .cd-fm-row').length) === 1);
  await ev(() => cdFmUp()); await wait(300);
  check('Up goes back', await ev(() => document.getElementById('cdFmPath').innerText) === '/sdcard');
  await ev(() => cdFmMenu(2)); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'View as text').click()); await wait(300);
  check('View as text shows the start of the file', await ev(() => /hello from the watch/.test(document.getElementById('cdSheetBody').innerText)));
  await ev(() => { cdSheetClose(); cdFmMenu(2); }); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Pull to this phone').click());
  for (let i = 0; i < 40 && !/Saved to Download/.test(await toast()); i++) await wait(100);
  await wait(100);
  check('Pull to this phone saves into the device\'s own folder', /Saved to Download\/ADB App Manager\/Devices\/192\.168\.1\.20_5555/.test(await toast()) && (await calls()).some(c => /(^| )pull \/sdcard\/notes\.txt \/storage\/emulated\/0\/Download\/ADB App Manager\/Devices\/192\.168\.1\.20_5555\/$/.test(c)), JSON.stringify({ toast: await toast(), calls: (await calls()).slice(-4) }));
  await ev(() => { cdSheetClose(); cdFmMenu(2); }); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Delete').click()); await wait(150);
  check('Delete asks first', await ev(() => document.getElementById('cdAskModal').classList.contains('show')) && !(await calls()).some(c => / rm /.test(c)));
  await page.click('#cdAskOk'); await wait(300);
  check('confirmed, it removes just that file', (await calls()).includes("192.168.1.20:5555 shell rm '/sdcard/notes.txt'"));
  await ev(() => { cdFmNewFolder(); }); await wait(150); await ev(() => { document.getElementById('cdAskInput').value = 'Faces'; }); await page.click('#cdAskOk'); await wait(300);
  check('New folder makes it', (await calls()).includes("192.168.1.20:5555 shell mkdir -p '/sdcard/Faces'"));
  await ev(() => cdFmPush()); await wait(500);
  check('Send a file here pushes the picked file into the open folder', (await calls()).includes('192.168.1.20:5555 push /cache/cd_pick/1/face.apk /sdcard/'));
  await ev(() => cdFmGo('/nope')); await wait(300);
  check('a folder that is not there says so', /No such file/.test(await ev(() => document.getElementById('cdFmCount').innerText)));
  await ev(() => cdFmGo('/sdcard')); await wait(300);

  // Hidden settings
  await ev(() => cdSub('settings')); await wait(400);
  const hs = await ev(() => ({ n: document.querySelectorAll('#cdSetList .cd-set-row').length, count: document.getElementById('cdSetCount').innerText, first: document.querySelector('.cd-set-key').innerText }));
  check('the settings of the namespace are listed sorted', hs.n === 3 && /3 settings in global/.test(hs.count) && hs.first === 'airplane_mode_on', JSON.stringify(hs));
  await ev(() => cdSetNs('system')); await wait(300);
  check('another namespace is read on its own', (await calls()).some(c => c.endsWith('shell settings list system')));
  await ev(() => { cdSetNs('global'); document.getElementById('cdSetSearch').value = 'timeout'; cdSetRender(); });
  check('search matches keys', await ev(() => document.querySelectorAll('#cdSetList .cd-set-row').length) === 1);
  await ev(() => cdSetEdit('screen_off_timeout')); await wait(150);
  await ev(() => { document.getElementById('cdSetVal').value = '60000'; });
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Save').click()); await wait(250);
  check('Save puts the value on the device and the list shows it', (await calls()).includes("192.168.1.20:5555 shell settings put global 'screen_off_timeout' '60000'") && await ev(() => /60000/.test(document.getElementById('cdSetList').innerText)));
  await ev(() => cdSetEdit('screen_off_timeout')); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Delete').click()); await wait(150);
  await page.click('#cdAskOk'); await wait(300);
  check('Delete asks, then removes the setting from the device and the list', (await calls()).includes("192.168.1.20:5555 shell settings delete global 'screen_off_timeout'") && await ev(() => document.querySelectorAll('#cdSetList .cd-set-row').length) === 0);
  await ev(() => { document.getElementById('cdSetSearch').value = ''; cdSetNew(); }); await wait(150);
  await ev(() => { document.getElementById('cdAskInput').value = 'my_key'; document.getElementById('cdAskInput2').value = 'a b'; }); await page.click('#cdAskOk'); await wait(300);
  check('+ New adds a key and value', (await calls()).includes("192.168.1.20:5555 shell settings put global 'my_key' 'a b'"));

  // Display
  await ev(() => cdSub('display')); await wait(200);
  const dn = await ev(() => { const s = document.getElementById('cdDensSel'); const vals = Array.from(s.options).map(o => +o.value); return { sel: s.value, first: vals[0], last: vals[vals.length - 1], steps5: vals.filter(v => v >= 120 && v <= 640).every((v, i, a) => i === 0 || v - a[i - 1] === 5), has320: vals.includes(320), now: document.getElementById('cdDensNow').innerText, size: document.getElementById('cdSizeNow').innerText, txt: s.options[s.selectedIndex].innerText }; });
  check('the density dropdown goes from 120 to 640 in steps of 5, on the current value', dn.first === 120 && dn.last === 640 && dn.steps5 && dn.sel === '280' && /280 \(now\)/.test(dn.txt) && /Now 280 dpi\. The device's own is 320/.test(dn.now) && /Now 450x450/.test(dn.size), JSON.stringify(dn));
  await ev(() => cdDensStep(5));
  check('+ steps the dropdown by 5', await ev(() => document.getElementById('cdDensSel').value) === '285');
  await ev(() => { document.getElementById('cdDensSel').value = '300'; }); await page.click('#view-devices .mode-action-btn.primary:has-text("Apply selected")'); await wait(300);
  check('Apply selected sends wm density with the value', (await calls()).some(c => /shell wm density 300$/.test(c)) && await ev(() => window.__st.dens) === 300);       // (the tab looks at the device list now and then: not always the last call)
  const keep = await ev(() => ({ shown: getComputedStyle(document.getElementById('cdKeep')).display !== 'none', text: document.getElementById('cdKeep').innerText.replace(/\s+/g, ' '), card: document.getElementById('cdInfo').innerText.includes('300 dpi') }));
  check('a change shows Keep / Put it back with a countdown and the card follows', keep.shown && /300 dpi/.test(keep.text) && /15 s/.test(keep.text) && keep.card, JSON.stringify(keep));
  await page.click('#cdKeep .mode-action-btn.primary'); await wait(100);
  check('Keep ends the countdown', await ev(() => getComputedStyle(document.getElementById('cdKeep')).display === 'none' && !cd.keep));
  await ev(() => { document.getElementById('cdDensManual').value = '12'; cdDensApply(true); });
  check('a number that is too small is turned down', /72 to 1000/.test(await toast()) && await ev(() => window.__st.dens) === 300);
  await ev(() => { document.getElementById('cdDensManual').value = '333'; cdDensApply(true); }); await wait(250);
  check('a manual number is applied as typed', await ev(() => window.__st.dens) === 333 && (await lastCall()) === 'shell wm density 333');
  check('its list now holds the typed value', await ev(() => Array.from(document.getElementById('cdDensSel').options).some(o => o.value === '333' && o.selected)));
  await ev(() => { cd.keep.left = 2; }); await wait(2300);
  check('left alone, the change is undone on its own to the value before', await ev(() => window.__st.dens) === 300 && await ev(() => !cd.keep) && /Put back/.test(await toast()), String(await ev(() => window.__st.dens)));
  await ev(() => cdDensReset()); await wait(250);
  check('Reset to default sends wm density reset', (await lastCall()) === 'shell wm density reset' && await ev(() => window.__st.dens) === 320);
  await ev(() => { document.getElementById('cdSizeInput').value = '400x400'; cdSizeApply(); }); await wait(250);
  check('the size is set with wm size and also gets the undo', (await lastCall()) === 'shell wm size 400x400' && await ev(() => !!cd.keep));
  await ev(() => cdKeepEnd(true)); await wait(300);
  check('Put it back restores the earlier size', (await calls()).includes('192.168.1.20:5555 shell wm size reset'));
  await page.screenshot({ path: 'cd_display.png' });

  // the device menu
  await ev(() => cdDeviceMenu()); await wait(150);
  check('the device menu has Reconnect, Reload details, Reboot, Recovery, Bootloader, Power off, Copy address and Disconnect', await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).map(b => b.innerText).join('|')) === 'Reconnect|Reload details|Reboot|Reboot to recovery|Reboot to bootloader|Power off|Copy address|Disconnect');
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Reboot').click()); await wait(150);
  check('a reboot asks first', await ev(() => document.getElementById('cdAskModal').classList.contains('show')) && !(await calls()).some(c => / reboot$/.test(c)));
  await page.click('#cdAskOk'); await wait(250);
  check('and then sends adb reboot to the device', (await calls()).includes('192.168.1.20:5555 reboot'));
  await ev(() => cdDeviceMenu()); await wait(100);
  await ev(() => Array.from(document.querySelectorAll('#cdSheetBtns button')).find(b => b.innerText === 'Disconnect').click()); await wait(600);
  check('Disconnect drops the device and the empty state returns', await ev(() => getComputedStyle(document.getElementById('cdWork')).display === 'none' && getComputedStyle(document.getElementById('cdEmpty')).display !== 'none') && (await calls()).includes('disconnect 192.168.1.20:5555'));
  check('the recent addresses are offered to reconnect', await ev(() => Array.from(document.querySelectorAll('#cdRecentWrap .cd-chip')).map(c => c.innerText).includes('192.168.1.20:5555')));

  // Back closes the top sheet; leaving the tab stops the polling
  await ev(() => cdAddOpen());
  check('Back closes the Add device sheet first', await ev(() => { backNavigate(); return !document.getElementById('cdAddModal').classList.contains('show'); }));
  await ev(() => switchView('apps'));
  check('leaving the tab stops the device poll', await ev(() => cd.poll === null));
  check('the page had no errors', errors.length === 0, JSON.stringify(errors));
  console.log('errors:', JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
