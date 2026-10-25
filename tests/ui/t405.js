// Extra Settings > Zram > Trim app caches reports what Android answered (PG3-1): it used to say "Done. RAM saved …" (in green) whenever the trim ended,
// also when Android refused it, because the Zram action never read the verdict of the Settings page's trim. Now a refused trim says it did not work
// with Android's words, a trim that cannot start says so, a trim still running after 3 minutes says that, and a trim that worked still says Done.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, TESTS } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const xqMock = require('./lib/xq_mock.js');
const FX = path.join(TESTS, 'java', 'fixtures', 'powertweaks');
const rd = f => fs.readFileSync(path.join(FX, f), 'utf8');
const fixtures = { mm_stat: rd('mm_stat.txt'), mm_stat_old: rd('mm_stat-old.txt'), disksize: rd('disksize.txt'), meminfo: rd('meminfo.txt'), help11: rd('cmd-power-help-11.txt'), help10: rd('cmd-power-help-10.txt'), helpNone: rd('cmd-power-none.txt'),
  dump_active: rd('deviceidle-active.txt'), dump_forced: rd('deviceidle-forced-idle.txt'), dump_old: rd('deviceidle-old-pending.txt') };
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript); await page.addInitScript(xqMock.initScript, fixtures);
  await page.addInitScript(m => { if (window.__mode) Object.assign(window.__mode, m); }, { priv: true, mode: 'shizuku' });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  await ev(() => { XZR_REFRESH_MS = 3600000; switchView('extrasettings'); }); await page.waitForTimeout(500);
  // the trim command is held (answered by the script through onShellDone); asyncStart: what executeShellAsync answers for it
  await ev(() => {
    window.__trimSent = []; window.__asyncStart = 'started';
    const orig = window.AndroidBridge.executeShellAsync;
    window.AndroidBridge.executeShellAsync = (id, cmd) => { if (/pm trim-caches/.test(cmd)) { window.__trimSent.push({ id, cmd }); return window.__asyncStart; } return orig(id, cmd); };
  });
  const runTrim = async () => { await ev(() => { xqSheetClose(); document.getElementById('xzrTrim').click(); }); await page.waitForTimeout(80); await ev(() => document.getElementById('xqSheetOk').click()); await page.waitForTimeout(150); };
  const answer = out => ev(o => window.onShellDone(window.__trimSent[window.__trimSent.length - 1].id, o), out);
  const settle = () => page.waitForFunction(() => !xzrBusy, null, { timeout: 8000 }).then(() => page.waitForTimeout(80));
  const result = () => ev(() => ({ sheet: document.getElementById('xqSheetResult').innerText.replace(/\s+/g, ' ').trim(), sheetCls: document.getElementById('xqSheetResult').className, card: document.getElementById('xzrDelta').innerText.replace(/\s+/g, ' ').trim(), cardCls: document.getElementById('xzrDelta').className }));
  const DF1 = '/dev/block/dm-48 227000000 100000000 126000000 45% /data\n', DF2 = '/dev/block/dm-48 227000000 98000000 128500000 44% /data\n';
  const green = r => /\bis-ok\b|\bok\b/.test(r.sheetCls + ' ' + r.cardCls);

  // ---------------------------------------------------------------- 1. Android refuses the trim
  await runTrim();
  await answer(DF1 + '__TRIM_BEGIN__\nError: java.lang.SecurityException: Permission Denial\n__TRIM_RC__255\n' + DF1);
  await settle();
  let r = await result();
  check('1. a trim Android refuses: the Zram sheet says it did not work, with Android\'s words', /^It did not work: Error: java\.lang\.SecurityException: Permission Denial/.test(r.sheet) && !/Done\./.test(r.sheet), JSON.stringify(r));
  check('   ... and the line under the numbers says the same, not in green', /^It did not work/.test(r.card) && !green(r), JSON.stringify(r));

  // ---------------------------------------------------------------- 2. a trim that exits 0 but says it failed (the Settings page's own rule)
  await runTrim();
  await answer(DF1 + '__TRIM_BEGIN__\nFailure [not allowed]\n__TRIM_RC__0\n' + DF1);
  await settle();
  r = await result();
  check('2. a trim that printed a failure: not reported as done', /^It did not work: Failure \[not allowed\]/.test(r.sheet) && !green(r), JSON.stringify(r));

  // ---------------------------------------------------------------- 3. the command cannot start
  await ev(() => { window.__asyncStart = 'error'; });
  await runTrim();
  await settle();
  r = await result();
  check('3. a trim that could not start says so', /^It did not work: Error: the command could not be started/.test(r.sheet) && !green(r), JSON.stringify(r));
  await ev(() => { window.__asyncStart = 'started'; });

  // ---------------------------------------------------------------- 4. no answer after 3 minutes
  await runTrim();
  await ev(() => { const D = Date.now; window.__realNow = D; Date.now = () => D() + 181000; });          // the clock jumps past the 3-minute wait
  await settle();
  await ev(() => { Date.now = window.__realNow; });
  r = await result();
  check('4. a trim still running after 3 minutes: says Android has not finished yet (not Done, and not that it did not work)', /^Android has not finished after 3 minutes\. The result opens in its own sheet when it is done\./.test(r.sheet) && !green(r), JSON.stringify(r));
  await answer(DF1 + '__TRIM_BEGIN__\n__TRIM_RC__0\n' + DF2); await page.waitForTimeout(100);          // it ends later: the Settings page's own sheet takes it

  // ---------------------------------------------------------------- 5. a trim that works
  await ev(() => { const m = document.getElementById('commandResultsModal'); if (m) m.classList.remove('show'); });
  await runTrim();
  await answer(DF1 + '__TRIM_BEGIN__\n__TRIM_RC__0\n' + DF2);
  await settle();
  r = await result();
  check('5. a trim that worked still says Done with the numbers, in green', /^Done\./.test(r.sheet) && green(r), JSON.stringify(r));

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
