// v7.11.1: Hidden Settings > Changes: "Back up changes" writes what was changed with the app (the value each setting has now, and the one it had before) to a file;
// "Restore…" reads such a file, asks, and puts the values back one by one (each goes into the history again), reporting once at the end.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => {
    const iv = setInterval(() => {
      if (!window.AndroidBridge) return;
      clearInterval(iv);
      window.__files = []; window.__picks = [];
      window.AndroidBridge.shareTextFile = (name, text, mime) => { window.__files.push({ name, text, mime }); return ''; };
      window.AndroidBridge.pickTextFile = tag => { window.__picks.push(tag); };
    }, 1);
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const find = async (key, ns = 'global') => { await ev(n => { sdbSetNs(n); sdbResetView(); }, ns); await sleep(80); await ev(k => { const i = document.getElementById('sdbSearch'); i.value = k; sdbSearchInput(); }, key); await sleep(120); };
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  await ev(() => { checkAllWorkingModes(false); switchView('settings'); }); await sleep(500);

  // two changes made with the app
  await find('stay_on_while');
  await page.locator('.sdb-row[data-k="stay_on_while_plugged_in"]').click(); await sleep(100);
  await page.locator('#sdbEditText').fill('3'); await page.locator('#sdbEditSaveBtn').click(); await sleep(250);
  await find('device_name');
  await page.locator('.sdb-row[data-k="device_name"]').click(); await sleep(100);
  await page.locator('#sdbEditText').fill('Renamed'); await page.locator('#sdbEditSaveBtn').click(); await sleep(250);
  await ev(() => sdbSnackHide());

  // 1. back up
  await ev(() => sdbOpenJournal());
  check('1. the Changes sheet has Back up changes and Restore…', await ev(() => document.getElementById('sdbBackupBtn').innerText === 'Back up changes' && document.getElementById('sdbRestoreBtn').innerText === 'Restore…'));
  await page.click('#sdbBackupBtn'); await sleep(100);
  const f = await ev(() => window.__files[0]);
  const doc = f ? JSON.parse(f.text) : null;
  check('   it shares one JSON file named for the day', !!f && /^hidden_settings_\d{4}-\d{2}-\d{2}\.json$/.test(f.name) && f.mime === 'application/json', JSON.stringify(f && f.name));
  const byKey = doc ? Object.fromEntries(doc.settings.map(s => [s.key, s])) : {};
  check('   it holds the value each changed setting has now and the one it had before', doc && doc.app === 'adb-app-manager' && doc.kind === 'hidden-settings' && doc.format === 1 && doc.settings.length === 2
    && byKey.stay_on_while_plugged_in.ns === 'global' && byKey.stay_on_while_plugged_in.value === '3' && byKey.stay_on_while_plugged_in.original === '7'
    && byKey.device_name.value === 'Renamed' && byKey.device_name.original === "Sam's phone", JSON.stringify(doc && doc.settings));
  await ev(() => sdbJournalClose());

  // 2. the phone was reset; restore
  await ev(() => { window.__db.global.stay_on_while_plugged_in = '7'; window.__db.global.device_name = "Sam's phone"; });
  await ev(() => { sdbOpenJournal(); }); await page.click('#sdbRestoreBtn'); await sleep(60);
  check('2. Restore… asks the app for a file', (await ev(() => window.__picks)).join() === 'hs-restore');
  await ev(() => sdbJournalClose());
  await ev(() => { sdbReadTable('global'); }); await sleep(250);
  await ev(() => { window.__calls.op.length = 0; });
  await ev(text => window.onTextFilePicked({ tag: 'hs-restore', text }), f.text); await sleep(900);
  const q = dialogs[dialogs.length - 1] || '';
  check('   it asks first, naming the settings and saying they are in the history', /Put 2 hidden settings back from the backup\?/.test(q) && /stay_on_while_plugged_in → “3”/.test(q) && /device_name → “Renamed”/.test(q) && /history/.test(q), q);
  const ops = await ev(() => window.__calls.op.map(o => o.op + ' ' + o.key + '=' + o.value));
  check('   then puts both values back, one after the other', ops.join('|') === 'put stay_on_while_plugged_in=3|put device_name=Renamed' || ops.join('|') === 'put device_name=Renamed|put stay_on_while_plugged_in=3', ops.join('|'));
  check('   and says once at the end how many were restored', /Restored 2 of 2/.test(await toast()), await toast());
  check('   without a sheet or a message for each setting', (await ev(() => document.getElementById('cmdResultsModal') ? document.getElementById('cmdResultsModal').classList.contains('show') : false)) === false);
  check('   every change is in the history again', (await ev(() => sdbJournal.length)) >= 4);

  // 3. nothing to do
  await ev(() => { window.__calls.op.length = 0; });
  const nd = dialogs.length;
  await ev(text => window.onTextFilePicked({ tag: 'hs-restore', text }), f.text); await sleep(200);
  check('3. a backup that already matches changes nothing and says so', /already as in the backup/.test(await toast()) && (await ev(() => window.__calls.op.length)) === 0 && dialogs.length === nd, await toast());

  // 4. a damaged or foreign file
  await ev(() => window.onTextFilePicked({ tag: 'hs-restore', text: 'not json' })); await sleep(80);
  check('4. a file that is not JSON is refused', /not a Hidden Settings backup/.test(await toast()));
  await ev(() => window.onTextFilePicked({ tag: 'hs-restore', text: JSON.stringify({ app: 'adb-app-manager', kind: 'presets', settings: [] }) })); await sleep(80);
  check('   so is a file of another kind', /not a Hidden Settings backup/.test(await toast()));
  const mixed = { app: 'adb-app-manager', format: 1, kind: 'hidden-settings', settings: [
    { ns: 'global', key: 'a b; rm -rf /', value: '1' }, { ns: 'bogus', key: 'x', value: '1' }, { ns: 'global', key: 'good_key_x', value: 5 }, { ns: 'global', key: 'zen_mode', value: '1' }, { ns: 'global', key: 'zen_mode', value: '2' }, { ns: 'global', key: 'low_power', value: null } ] };
  await ev(() => { window.__calls.op.length = 0; });
  await ev(text => window.onTextFilePicked({ tag: 'hs-restore', text }), JSON.stringify(mixed)); await sleep(900);
  const q2 = dialogs[dialogs.length - 1];
  const ops2 = await ev(() => window.__calls.op.map(o => o.op + ' ' + o.key + '=' + o.value));
  check('   names with spaces or shell characters, unknown tables, numbers as values and doubles are left out; a null value removes the setting', /Put 2 hidden settings back/.test(q2) && ops2.join('|') === 'put zen_mode=1|delete low_power=' , q2 + ' :: ' + ops2.join('|'));
  check('   the removed one is gone from the fake phone', (await ev(() => window.__db.global.low_power)) === undefined);

  // 5. cancelling at the question
  answer = false;
  await ev(() => { window.__calls.op.length = 0; window.__db.global.zen_mode = '0'; sdbReadTable('global'); }); await sleep(250);
  await ev(text => window.onTextFilePicked({ tag: 'hs-restore', text }), JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'hidden-settings', settings: [{ ns: 'global', key: 'zen_mode', value: '1' }] })); await sleep(200);
  check('5. saying no at the question changes nothing', (await ev(() => window.__calls.op.length)) === 0);
  answer = true;

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
