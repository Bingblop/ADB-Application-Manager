// Connected Devices: a device that dropped. It is remembered while connected; when it is gone it stays listed as "Not connected" with Reconnect and Forget, the drop is
// told, Reconnect works for each way of connecting (address, a new Wireless debugging port, the service name, USB, the Bluetooth link) and says in words why it could not,
// and one automatic attempt series runs when a device drops (it can be turned off). Disconnect from the menu is not a drop.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    // up: what `adb devices` lists (serial -> state); answers: what `adb connect <addr>` does (addr -> true = the device is up under that serial)
    const st = window.__st = { calls: [], up: { '127.0.0.1:5555': 'device' }, canConnect: {}, mdns: [], usbBack: false, fwdFail: false, models: {} };
    const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 5);
    const label = s => (st.models[s] ? ' product:p model:' + st.models[s] + ' device:d transport_id:1' : ' transport_id:1');
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {},
      cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555', 'localhost:5555']); },
      cdAdb(tag, serial, argsJson) {
        const a = JSON.parse(argsJson); st.calls.push((serial ? serial + ' ' : '') + a.join(' '));
        if (a[0] === 'devices') return reply(tag, 'List of devices attached\n' + Object.keys(st.up).map(s => s + '\t' + st.up[s] + label(s) + '\n').join(''));
        if (a[0] === 'connect') {
          const to = st.canConnect[a[1]];
          if (to) { st.up[to] = 'device'; return reply(tag, 'connected to ' + to); }
          return reply(tag, "failed to connect to '" + a[1] + "': Connection refused");
        }
        if (a[0] === 'disconnect') { delete st.up[a[1]]; return reply(tag, 'disconnected ' + a[1]); }
        if (a[0] === 'reconnect') { if (st.usbBack) st.up['R5CT1234'] = 'device'; return reply(tag, 'reconnecting'); }
        if (a[0] === 'mdns') return reply(tag, 'List of discovered mdns services\n' + st.mdns.map(m => m[0] + '\t_adb-tls-connect._tcp.\t' + m[1] + '\n').join(''));
        if (a[0] === 'forward') return reply(tag, st.fwdFail ? 'error: more than one device/emulator' : '4444');
        if (a[0] === 'shell') return reply(tag, /^for p in/.test(a[1]) ? 'ro.product.manufacturer=samsung\nro.product.model=' + (st.models[serial] || 'SM-R930') + '\nro.build.version.release=14\nro.build.version.sdk=34\nbatt=  level: 83   status: 3' : '');
        return reply(tag, 'adb: unknown command ' + a[0]);
      },
      cdCancel() {}
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const calls = () => ev(() => window.__st.calls.slice());
  const lostText = () => ev(() => document.getElementById('cdLost').innerText.replace(/\s+/g, ' '));
  const refresh = async () => { await ev(() => cdRefreshDevices(false)); await wait(250); };

  await ev(() => { kvSet('cd_autore', false); window.__st.up['192.168.1.20:5555'] = 'device'; window.__st.models['192.168.1.20:5555'] = 'SM_R930'; switchView('devices'); }); await wait(600);
  check('a connected watch is shown and remembered', await ev(() => cd.serial === '192.168.1.20:5555' && !!cdKnown()['192.168.1.20:5555'] && document.getElementById('cdLost').innerText.trim() === ''));
  await ev(() => { window.__st.models['192.168.1.20:5555'] = 'SM_R930'; });

  // ---- it drops ----
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; }); await refresh();
  check('the drop is told in a toast', /Lost the connection to/.test(await toast()), await toast());
  check('the device stays listed as Not connected, with its name, how it was connected and Reconnect and Forget', await ev(() => { const t = document.getElementById('cdLost').innerText.replace(/\s+/g, ' '); const bs = [...document.querySelectorAll('#cdLost .cd-lost button.cd-row-act')].map(x => x.innerText); return /Not connected/i.test(t) && /SM[ -]R930/.test(t) && /Wi-Fi/.test(t) && /lost just now/.test(t) && bs.join() === 'Reconnect,Forget'; }), await lostText());
  check('the picker and the empty state show there is no device now', await ev(() => document.getElementById('cdDeviceSel').disabled && getComputedStyle(document.getElementById('cdEmpty')).display !== 'none'));
  check('the automatic setting is shown, and it is off here', /On its own when a device drops|Reconnect on its own when a device drops: Off/i.test(await lostText()) && /Off/.test(await ev(() => document.getElementById('cdAutoBtn').innerText)));

  // ---- Reconnect that fails: in words, nothing changes ----
  await ev(() => { window.__st.calls.length = 0; }); await page.click('#cdLost .cd-row-act'); await wait(500);
  const c1 = await calls();
  check('Reconnect disconnects, connects to the same address, then looks for it by name', c1.includes('disconnect 192.168.1.20:5555') && c1.includes('connect 192.168.1.20:5555') && c1.some(c => /^mdns services/.test(c)), JSON.stringify(c1));
  check('a failed Reconnect says why: no answer, and that Wireless debugging changes its port', /No answer from 192\.168\.1\.20:5555/.test(await lostText()) && /Connection refused/.test(await lostText()) && /Add device > Scan/.test(await lostText()), await lostText());
  check('the buttons work again afterwards', await ev(() => ![...document.querySelectorAll('#cdLost .cd-row-act')].some(b => b.disabled)));

  // ---- Reconnect that works (same address) ----
  await ev(() => { window.__st.canConnect['192.168.1.20:5555'] = '192.168.1.20:5555'; }); await page.click('#cdLost .cd-row-act'); await wait(700);
  check('Reconnect brings the watch back: selected, listed as connected, the Not connected card gone', await ev(() => cd.serial === '192.168.1.20:5555' && cdIsUp('192.168.1.20:5555') && document.getElementById('cdLost').innerText.trim() === ''), await lostText());
  check('and says so', /Reconnected/.test(await toast()), await toast());

  // ---- Wireless debugging took a new port: found through mDNS on the same address ----
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; window.__st.canConnect = { '192.168.1.20:40111': '192.168.1.20:40111' }; window.__st.mdns = [['adb-ABC-xyz', '192.168.1.20:40111'], ['adb-OTHER-zzz', '192.168.1.77:43000']]; window.__st.models['192.168.1.20:40111'] = 'SM_R930'; }); await refresh();
  await page.click('#cdLost .cd-row-act'); await wait(900);
  const np = await ev(() => ({ cur: cd.serial, up: cdIsUp('192.168.1.20:40111'), old: !!cdKnown()['192.168.1.20:5555'], neu: !!cdKnown()['192.168.1.20:40111'], lost: document.getElementById('cdLost').innerText.trim() }));
  check('a new Wireless debugging port on the same address is found and used, and the device keeps its place in the list', np.cur === '192.168.1.20:40111' && np.up && !np.old && np.neu && np.lost === '', JSON.stringify(np));
  check('only the entry on the same address was tried (not the other phone\'s)', !(await calls()).includes('connect 192.168.1.77:43000'));

  // ---- a device known by its service name ----
  const TLS = 'adb-ABC123-xyz._adb-tls-connect._tcp';
  await ev(s => { window.__st.up = { '127.0.0.1:5555': 'device' }; window.__st.up[s] = 'device'; window.__st.models[s] = 'Pixel_Tablet'; }, TLS); await refresh();
  await ev(s => { delete window.__st.up[s]; window.__st.canConnect = { '192.168.1.30:38000': '192.168.1.30:38000' }; window.__st.mdns = [['adb-ABC123-xyz', '192.168.1.30:38000']]; }, TLS); await refresh();
  check('a device known by its service name is listed', /Pixel Tablet/.test(await lostText()), await lostText());
  await ev(() => { document.querySelector('#cdLost .cd-lost[data-serial^="adb-ABC123"] .cd-row-act').click(); }); await wait(900);
  const tls = await ev(() => ({ up: cd.devices.filter(d => d.state === 'device').map(d => d.serial), known: Object.keys(cdKnown()) }));
  check('Reconnect finds it by name through mDNS and connects to the address it announces', tls.up.includes('192.168.1.30:38000') && !tls.known.includes('adb-ABC123-xyz._adb-tls-connect._tcp') && tls.known.includes('192.168.1.30:38000'), JSON.stringify(tls));

  // ---- USB ----
  await ev(() => { window.__st.up = { '127.0.0.1:5555': 'device', 'R5CT1234': 'device' }; window.__st.models['R5CT1234'] = 'Pixel_8'; }); await refresh();
  await ev(() => { delete window.__st.up['R5CT1234']; }); await refresh();
  await page.click('#cdLost .cd-lost[data-serial="R5CT1234"] .cd-row-act'); await wait(500);
  check('a USB device that is not there says what to check (cable, unlock, USB debugging)', /Not found on USB/.test(await lostText()) && /USB debugging/.test(await lostText()), await lostText());
  await ev(() => { window.__st.usbBack = true; window.__st.calls.length = 0; }); await page.click('#cdLost .cd-lost[data-serial="R5CT1234"] .cd-row-act'); await wait(600);
  check('when it answers after "adb reconnect offline" it is back', (await calls()).includes('reconnect offline') && await ev(() => cdIsUp('R5CT1234')));

  // ---- Bluetooth link ----
  await ev(() => { cd.bt.add('127.0.0.1:4444'); window.__st.up['127.0.0.1:4444'] = 'device'; window.__st.models['127.0.0.1:4444'] = 'Bluetooth_Watch'; }); await refresh();
  await ev(() => { delete window.__st.up['127.0.0.1:4444']; window.__st.canConnect = { '127.0.0.1:4444': '127.0.0.1:4444' }; window.__st.calls.length = 0; }); await refresh();
  await page.click('#cdLost .cd-lost[data-serial="127.0.0.1:4444"] .cd-row-act'); await wait(900);
  const bt = await calls();
  check('the Bluetooth link is set up again through this phone\'s own ADB, then connected', bt.includes('127.0.0.1:5555 forward tcp:4444 localabstract:/adb-hub') && bt.includes('connect 127.0.0.1:4444') && await ev(() => cdIsUp('127.0.0.1:4444')), JSON.stringify(bt));
  await ev(() => { delete window.__st.up['127.0.0.1:4444']; window.__st.up = { 'R5CT1234': 'device' }; }); await refresh();
  await page.click('#cdLost .cd-lost[data-serial="127.0.0.1:4444"] .cd-row-act'); await wait(700);
  check('without this phone\'s own ADB the Bluetooth link says what to turn on', /own ADB/.test(await lostText()), await lostText());
  await ev(() => { window.__st.up['127.0.0.1:5555'] = 'device'; window.__st.fwdFail = true; }); await page.click('#cdLost .cd-lost[data-serial="127.0.0.1:4444"] .cd-row-act'); await wait(700);
  check('a refused forward is told in adb\'s words', /more than one device/.test(await lostText()), await lostText());
  await ev(() => { window.__st.fwdFail = false; });

  // ---- Forget ----
  await ev(() => { cdForget('127.0.0.1:4444'); });
  check('Forget takes a device off the list for good', await ev(() => !cdKnown()['127.0.0.1:4444'] && !document.querySelector('#cdLost [data-serial="127.0.0.1:4444"]')));

  // ---- the device menu: Reconnect, and Disconnect is not a drop ----
  await ev(() => { window.__st.up = { '127.0.0.1:5555': 'device' }; window.__st.up['192.168.1.20:5555'] = 'device'; window.__st.models['192.168.1.20:5555'] = 'SM_R930'; cd.lastKey = ''; }); await refresh(); await ev(() => cdSelect('192.168.1.20:5555')); await wait(500);
  await ev(() => cdDeviceMenu()); await wait(100);
  check('the device menu starts with Reconnect', await ev(() => document.querySelector('#cdSheetBtns button').innerText) === 'Reconnect');
  await ev(() => { window.__st.calls.length = 0; document.querySelector('#cdSheetBtns button').click(); }); await wait(500);
  check('Reconnect from the menu cycles the connection of the selected device', (await calls()).includes('disconnect 192.168.1.20:5555'), JSON.stringify(await calls()));
  await ev(() => { window.__st.canConnect = { '192.168.1.20:5555': '192.168.1.20:5555' }; }); await refresh(); await wait(300);
  await ev(() => cdDeviceMenu()); await wait(100);
  await ev(() => { [...document.querySelectorAll('#cdSheetBtns button')].find(x => x.innerText === 'Disconnect').click(); }); await wait(700);
  check('Disconnect from the menu is a decision, not a drop: no card, no toast about a lost connection', await ev(() => !document.querySelector('#cdLost [data-serial="192.168.1.20:5555"]') && !cdKnown()['192.168.1.20:5555']));

  // ---- the device card of an offline device ----
  await ev(() => { window.__st.up = { '127.0.0.1:5555': 'device', 'R5CT1234': 'offline' }; cd.lastKey = ''; cd.serial = ''; }); await refresh();
  check('an offline device offers Reconnect in its card, and is also listed as not connected', await ev(() => /Reconnect/.test(document.getElementById('cdInfo').innerText) && !!document.querySelector('#cdLost [data-serial="R5CT1234"]')));

  // ---- automatic: one series of attempts after a drop, the setting turns it off ----
  await ev(() => { kvSet('cd_autore', true); CD_AUTO_WAITS.splice(0, CD_AUTO_WAITS.length, 0, 60, 120); window.__st.up = { '127.0.0.1:5555': 'device' }; window.__st.up['192.168.1.20:5555'] = 'device'; window.__st.canConnect = {}; window.__st.calls.length = 0; cd.lastKey = ''; kvSet('cd_known', {}); cd.up = new Set(); cd.dropped = {}; cd.rc = {}; }); await refresh();
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; window.__st.calls.length = 0; }); await refresh(); await wait(900);
  const auto = (await calls()).filter(c => c === 'connect 192.168.1.20:5555').length;
  check('with the setting on, a drop is followed by three attempts on its own, and then it stops', auto === 3, String(auto));
  check('the card then says why it could not (the buttons are free again)', /No answer/.test(await lostText()) && await ev(() => ![...document.querySelectorAll('#cdLost .cd-row-act')].some(b => b.disabled)), await lostText());
  await ev(() => { window.__st.canConnect = { '192.168.1.20:5555': '192.168.1.20:5555' }; window.__st.up['192.168.1.20:5555'] = 'device'; }); await refresh(); await wait(300);
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; window.__st.calls.length = 0; }); await refresh(); await wait(900);
  check('when the first attempt works, it stops there and the watch is back', (await calls()).filter(c => c === 'connect 192.168.1.20:5555').length === 1 && await ev(() => cdIsUp('192.168.1.20:5555') && document.getElementById('cdLost').innerText.trim() === ''), JSON.stringify(await calls()));
  await ev(() => { kvSet('cd_autore', true); document.getElementById('cdAutoBtn') || (window.__st.up = {}); });
  await ev(() => { window.__st.canConnect = {}; delete window.__st.up['192.168.1.20:5555']; window.__st.up['192.168.1.20:5555'] = 'device'; }); await refresh();
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; }); await ev(() => { kvSet('cd_autore', false); window.__st.calls.length = 0; }); await refresh(); await wait(500);
  check('with the setting off, nothing is tried until the button is pressed', !(await calls()).some(c => /^connect /.test(c)), JSON.stringify(await calls()));
  await page.click('#cdAutoBtn'); await wait(100);
  check('the setting is switched in the card (and remembered)', await ev(() => kvGet('cd_autore', true) === true && document.getElementById('cdAutoBtn').innerText === 'On'));
  await page.screenshot({ path: 'cd_lost.png' });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
