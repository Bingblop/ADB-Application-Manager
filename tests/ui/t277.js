// Connected Devices: Hidden Settings Save / Delete / New and the device menu's Reboot and Power off go to the device they were opened for. When the
// device list moves to another device while the sheet or the question is open (the chosen one dropped off), nothing is sent and a toast says so.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async () => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      const A = '192.168.1.20:5555', B = '10.0.0.9:5555';
      const st = window.__st = { calls: [], devs: ['a', 'b'] };
      const reply = (tag, out, ms) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), ms || 8);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        cdSelfSerials() { return JSON.stringify(['127.0.0.1:5555']); },
        cdCancel() {},
        cdAdb(tag, serial, argsJson) {
          const a = JSON.parse(argsJson);
          if (a[0] === 'devices') {
            let out = 'List of devices attached\n';
            if (st.devs.includes('a')) out += A + '\tdevice product:a model:Watch device:a transport_id:2\n';
            if (st.devs.includes('b')) out += B + '\tdevice product:b model:Tablet device:b transport_id:3\n';
            return reply(tag, out);
          }
          const cmd = a[0] === 'shell' ? a[1] : a.join(' ');
          if (/^for p in/.test(cmd)) return reply(tag, 'ro.product.model=M-' + serial + '\nro.build.version.release=14\nro.build.version.sdk=34');
          if (/^settings list/.test(cmd)) return reply(tag, 'animator_duration_scale=1.0\n');
          if (/^(settings (put|delete)|reboot)/.test(cmd)) st.calls.push(serial + ' ' + cmd);
          return reply(tag, '');
        },
      };
    });
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => kvSet('cd_autore', false));
    await page.evaluate(a => kvSet('cd_ui', { serial: a, sub: 'settings' }), A);
    await page.evaluate(() => switchView('devices')); await page.waitForTimeout(700);
    return page;
  };
  const drop = page => page.evaluate(() => { window.__st.devs = ['b']; return cdRefreshDevices(false); });
  const act = (page, label) => page.evaluate(l => cdSheetAct(cd.acts.findIndex(x => x.label === l)), label);
  const calls = page => page.evaluate(() => window.__st.calls.join('|'));
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const KEY = 'animator_duration_scale';

  // each action twice: the device stays (it goes to A), or A drops while it is open (nothing goes anywhere)
  const cases = [
    ['Settings > Save', async page => { await page.evaluate(k => cdSetEdit(k), KEY); }, async page => { await page.evaluate(() => { document.getElementById('cdSetVal').value = '0'; }); await act(page, 'Save'); }, "settings put global 'animator_duration_scale' '0'"],
    ['Settings > Delete', async page => { await page.evaluate(k => cdSetEdit(k), KEY); await act(page, 'Delete'); }, async page => { await page.evaluate(() => cdAskDone(true)); }, "settings delete global 'animator_duration_scale'"],
    ['Settings > New', async page => { await page.evaluate(() => { cdSetNew(); }); await page.waitForTimeout(50); await page.evaluate(() => { document.getElementById('cdAskInput').value = 'my_key'; document.getElementById('cdAskInput2').value = '5'; }); }, async page => { await page.evaluate(() => cdAskDone(true)); }, "settings put global 'my_key' '5'"],
    ['Device menu > Reboot', async page => { await page.evaluate(() => cdDeviceMenu()); await act(page, 'Reboot'); }, async page => { await page.evaluate(() => cdAskDone(true)); }, 'reboot'],
    ['Device menu > Reboot to recovery', async page => { await page.evaluate(() => cdDeviceMenu()); await act(page, 'Reboot to recovery'); }, async page => { await page.evaluate(() => cdAskDone(true)); }, 'reboot recovery'],
    ['Device menu > Power off', async page => { await page.evaluate(() => cdDeviceMenu()); await act(page, 'Power off'); }, async page => { await page.evaluate(() => cdAskDone(true)); }, 'reboot -p'],
  ];
  for (const [name, openIt, confirm, cmd] of cases) {
    let page = await open();
    check(name + ': A is chosen', await page.evaluate(a => cd.serial === a, A));
    await openIt(page); await page.waitForTimeout(100);
    await confirm(page); await page.waitForTimeout(250);
    check('   with A still there it goes to A', await calls(page) === A + ' ' + cmd, await calls(page));
    await page.close();

    page = await open();
    await openIt(page); await page.waitForTimeout(100);
    await drop(page); await page.waitForTimeout(250);
    check('   A drops while it is open and B is chosen', await page.evaluate(b => cd.serial === b, B));
    await confirm(page); await page.waitForTimeout(250);
    check('   then nothing is sent, to A or B, and the toast says why', await calls(page) === '' && await toast(page) === 'Another device was chosen: nothing was sent', await calls(page) + ' / ' + await toast(page));
    if (/^Settings/.test(name)) check('   and the settings of B are not changed on screen', await page.evaluate(() => !('my_key' in (cd.set.data.global || {})) && (cd.set.data.global || {}).animator_duration_scale !== '0'));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
