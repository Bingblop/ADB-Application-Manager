// Morphe Patcher result (MOR-1): a run in which no patch went in (every selected patch failed with "Stop when a patch fails" off) is never shown as
// "<App> is patched": the app reports it as a failure ("No patch could be applied"), and the page itself treats a success with an empty applied list
// (an older app or engine) the same way, without the Install / Share buttons. A run with at least one patch applied still says "is patched".
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ' ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, {});
  await page.goto(PAGE); await page.waitForTimeout(700);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  // a finished run as mpOnEvent leaves it, then the result the app sent last
  const render = (res, applied) => ev(([res, applied]) => {
    mp.t = { name: 'YouTube', pkg: 'com.google.android.youtube' };
    mp.run = { job: 'j1', applied: applied, failed: (res.failed || []).map(f => ({ name: f.name })), steps: {}, log: [], seq: 0, total: 2, req: { installAfter: true } };
    mpResultRender(res);
    const el = document.getElementById('mpResult');
    return { text: el.innerText, ok: !!el.querySelector('.mp-result.ok'), bad: !!el.querySelector('.mp-result.bad'), install: /mpRunInstall|mpPatchedShare/.test(el.innerHTML) };
  }, [res, applied]);
  const failedList = [{ name: 'Hide ads', error: 'app.morphe.patcher.patch.PatchException: Fingerprint not found' }, { name: 'SponsorBlock', error: 'app.morphe.patcher.patch.PatchException: Fingerprint not found' }];

  const old = await render({ success: true, applied: [], failed: failedList, steps: [{ name: 'Patching', ok: false }, { name: 'Rebuilding', ok: true }, { name: 'Signing', ok: true }] }, []);
  check('1. a success with nothing applied (an older app): "Patching failed", not "is patched"', old.bad && !old.ok && /Patching failed/.test(old.text) && !/is patched/.test(old.text), JSON.stringify(old));
  check('   it says no patch could be applied and names the failed patches', /No patch could be applied/.test(old.text) && /Hide ads/.test(old.text) && !/The rest was applied/.test(old.text), old.text);
  check('   no Install or Share button for the unpatched APK', !old.install, JSON.stringify(old));

  const app = await render({ success: false, error: 'No patch could be applied', applied: [], failed: failedList, steps: [{ name: 'Patching', ok: false }] }, []);
  check('2. the app\'s own answer for it (a failure) reads the same', app.bad && /Patching failed/.test(app.text) && /No patch could be applied/.test(app.text) && !/is patched/.test(app.text) && !app.install, app.text);

  const some = await render({ success: true, applied: ['SponsorBlock'], failed: [failedList[0]], item: { id: 'p1' } }, ['SponsorBlock']);
  check('3. one applied, one failed: still "YouTube is patched", 1 applied, 1 failed, "The rest was applied."', some.ok && /YouTube is patched/.test(some.text) && /1 patches applied, 1 failed/.test(some.text) && /The rest was applied/.test(some.text), some.text);

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
