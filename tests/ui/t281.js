// Hidden Settings restore: entries of a table the phone would not read are not dropped silently - the final message counts them and names them
// (the readable ones are still put back); a file whose entries are all in the unreadable table says so and changes nothing.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 400, height: 860 } }); page.on('pageerror', e => errors.push(e.message));
  const toast = () => page.evaluate(() => document.getElementById('toastMsg').innerText);
  const dialogs = []; page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(() => {                     // the Secure table is never readable on this phone
    const iv = setInterval(() => {
      if (!window.AndroidBridge) return;
      clearInterval(iv);
      const real = window.AndroidBridge.settingsList;
      window.AndroidBridge.settingsList = function (req, ns) {
        if (ns === 'secure') { window.__calls.list.push(ns); setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: false, error: 'Permission denied' })), 10); return 'started'; }
        return real.call(this, req, ns);
      };
    }, 1);
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  await page.evaluate(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(500);
  check('the Settings tab opened with the Secure table unread', await page.evaluate(() => !sdbData.secure && !!sdbError.secure && !!sdbData.global));
  const file = settings => JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'hidden-settings', settings });
  const ops = () => page.evaluate(() => window.__calls.op.map(o => o.op + ' ' + o.ns + '/' + o.key + '=' + o.value).sort().join('|'));
  const pick = async text => { await page.evaluate(() => { document.getElementById('toastMsg').innerText = ''; }); await page.evaluate(t => window.onTextFilePicked({ tag: 'hs-restore', text: t }), text); await page.waitForFunction(() => /\S/.test(document.getElementById('toastMsg').innerText) && !/^Restoring/.test(document.getElementById('toastMsg').innerText), null, { timeout: 8000 }); };

  await page.evaluate(() => { window.__calls.op.length = 0; });
  await pick(file([{ ns: 'secure', key: 'ui_night_mode', value: '1' }, { ns: 'global', key: 'zen_mode', value: '1' }, { ns: 'system', key: 'screen_brightness', value: '200' }]));
  check('the readable entries are put back', await ops() === 'put global/zen_mode=1|put system/screen_brightness=200', await ops());
  check('the question counts what it will do', dialogs.length === 1 && /Put 2 hidden settings back/.test(dialogs[0]), JSON.stringify(dialogs));
  const t = await toast();
  check('the result counts the entry that could not be read', /Restored 2 of 3/.test(t), t);
  check('and names it', /could not change ui_night_mode/.test(t), t);

  await page.evaluate(() => { window.__calls.op.length = 0; });
  await pick(file([{ ns: 'secure', key: 'ui_night_mode', value: '1' }]));
  check('a file with only unreadable entries changes nothing', await ops() === '', await ops());
  check('and says so', /Could not read the settings/.test(await toast()), await toast());

  await page.evaluate(() => { window.__calls.op.length = 0; window.__db.global.zen_mode = '1'; return sdbReadTable('global'); }); await page.waitForTimeout(200);
  await pick(file([{ ns: 'secure', key: 'ui_night_mode', value: '1' }, { ns: 'global', key: 'zen_mode', value: '1' }]));
  const t3 = await toast();
  check('readable entries that already match, next to an unreadable one, still name the unreadable one', /ui_night_mode/.test(t3) && /Restored 0 of 1/.test(t3), t3);
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
  console.log('all ok');
  process.exit(0);
})();
