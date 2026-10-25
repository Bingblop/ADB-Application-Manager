// Hidden Settings: Revert from the saved history while the entry's table is not read (its read failed, or is still running) reads the table first,
// like a restore: it never says the setting "is now not set", never journals the value put back as new, so reverting that Revert puts the old
// value back instead of deleting the setting; a table that still cannot be read is not written to at all.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  let page = null, dialogs = [];
  // yesterday's change, saved by an earlier launch: long_press_timeout 500 -> 400 (the phone holds 400)
  const saved = { sdb_journal: JSON.stringify([{ n: 1, t: Date.now() - 86400000, ns: 'secure', k: 'long_press_timeout', f: '500', lf: false, v: '400', lv: false }]) };
  // secure: 'fail' (every read fails), 'once' (the first read fails), 'slow' (the reads take 800 ms)
  const launch = async secure => {
    if (page) await page.close();
    dialogs = [];
    page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
    await page.addInitScript(k => { window.__kvInit = k; }, saved);
    await page.addInitScript(mock.initScript);
    await page.addInitScript(how => {
      const real = window.AndroidBridge.settingsList;
      let failed = 0;
      window.AndroidBridge.settingsList = function (req, ns) {
        if (ns !== 'secure') return real.call(this, req, ns);
        if (how === 'fail' || (how === 'once' && !failed++)) {
          window.__calls.list.push(ns);
          setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: false, error: 'settings list timed out' })), 10);
          return 'started';
        }
        if (how === 'slow') { window.__calls.list.push(ns); const t = window.__db[ns]; setTimeout(() => window.onSettingsList(JSON.stringify({ req, ns, ok: true, entries: Object.keys(t).map(k => [k, t[k]]) })), 800); return 'started'; }
        return real.call(this, req, ns);
      };
    }, secure);
    await page.goto(PAGE); await page.waitForTimeout(400);
  };
  const ev = (fn, a) => page.evaluate(fn, a);
  const openTab = async wait => { await ev(() => { checkAllWorkingModes(false); switchView('settings'); }); await page.waitForTimeout(wait); };
  const tapRevert = async n => { await page.click('#sdbHistBtn'); await page.waitForTimeout(150); await page.click('#sdbJournalList button[onclick="sdbRevert(' + n + ')"]'); };
  const state = () => ev(() => ({ ops: window.__calls.op.map(o => o.op + ' ' + o.ns + '/' + o.key + (o.op === 'put' ? '=' + o.value : '')), phone: window.__db.secure.long_press_timeout,
    journal: sdbJournal.map(e => e.n + ':' + e.f + '>' + e.v), saved: JSON.parse(window.AndroidBridge.__kv.sdb_journal).length, toast: document.getElementById('toastMsg').innerText }));

  // 1. every read of the Secure table fails: Revert reads it again, then gives up without writing
  await launch('fail');
  await openTab(500);
  check('1. the tab is open with the Secure table unread', await ev(() => !sdbData.secure && !!sdbError.secure && !!sdbData.global));
  await ev(() => { window.__calls.list.length = 0; });
  await tapRevert(1); await page.waitForTimeout(600);
  let s = await state();
  const reads = await ev(() => window.__calls.list.join());
  check('   Revert tries to read the Secure table again', reads === 'secure', reads);
  check('   and, as it still cannot be read, changes nothing and says so', s.ops.length === 0 && s.phone === '400' && s.journal.join() === '1:500>400' && s.saved === 1 && s.toast === 'Could not read the settings', JSON.stringify(s));
  check('   no question claims the setting "is now not set"', !dialogs.some(d => /not set/.test(d)), JSON.stringify(dialogs));

  // 2. the first read failed, the second works: Revert reads the table, then puts the old value back with the right "before"
  await launch('once');
  await openTab(500);
  check('2. the tab is open with the Secure table unread (its first read failed)', await ev(() => !sdbData.secure && !!sdbError.secure));
  await tapRevert(1); await page.waitForTimeout(600);
  s = await state();
  check('   Revert reads the table, asks nothing about "not set" and puts 500 back', s.ops.join() === 'put secure/long_press_timeout=500' && s.phone === '500' && !dialogs.some(d => /not set/.test(d)), JSON.stringify(s) + ' ' + JSON.stringify(dialogs));
  check('   the history says 400 → 500, not (not set) → 500', s.journal.join() === '2:400>500,1:500>400', JSON.stringify(s.journal));
  await ev(() => { window.__calls.op.length = 0; });
  await tapRevert(2); await page.waitForTimeout(400);
  s = await state();
  check('   reverting that Revert puts 400 back; it does not delete the setting', s.ops.join() === 'put secure/long_press_timeout=400' && s.phone === '400', JSON.stringify(s));

  // 3. the Secure table is still being read when Revert is tapped (the clock right after opening the tab)
  await launch('slow');
  await openTab(150);
  check('3. Revert is tapped while the Secure table is still being read', await ev(() => !sdbData.secure && sdbLoading.secure));
  await tapRevert(1); await page.waitForTimeout(1400);
  s = await state();
  const lists = await ev(() => window.__calls.list.filter(n => n === 'secure').length);
  check('   it waits for that read (no second one), then puts 500 back with no "not set" question', lists === 1 && s.ops.join() === 'put secure/long_press_timeout=500' && !dialogs.some(d => /not set/.test(d)), lists + ' ' + JSON.stringify(s) + ' ' + JSON.stringify(dialogs));
  check('   the history says 400 → 500', s.journal.join() === '2:400>500,1:500>400', JSON.stringify(s.journal));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
