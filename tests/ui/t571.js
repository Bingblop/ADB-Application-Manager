// The failures of the overlay write on the one Theme screen (v7.22.0; the PR 2 sheets carried over): refused colours (the overlay listed on
// but Android showing its own: the sheet names it so, with the reason, the advice and what Android reports for each colour; the record kept),
// the Java gate saying no (Needs Super User, with the uid 0 wording and how to get it; nothing remembered, an earlier record put back as it
// was), no answer from the shell (the phone may still apply it; remembered as possibly written), the theme setting itself refused (no overlay
// written), no answer at all (the page's own timeout), a late answer (not taken for a live one, but the list is read again).
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => { window.__sdk = 34; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const undoShown = () => ev(() => document.getElementById('sdbSnackUndo').style.display !== 'none');
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const note = () => ev(() => document.getElementById('ovlRootNote').innerText);
  const sheet = () => ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const AAM = 'com.android.shell:aam_monet';

  await ev(() => switchView('overlays')); await sleep(400);

  // 1) refused colours: listed on, Android shows its own
  await ev(() => { window.__fabDeny = 'Android did not take the colours'; }); await reset();
  await apply();
  let s = await sheet();
  check('1. refused colours: the sheet and the toast say the overlay is on but its colours did not take, with the reason, the advice and what Android reports for the colours looked up', s.shown && s.title === 'The theme overlay is on, but Android shows its own colors' && /Monet · root overlay/.test(s.body) && /did not take the colours/.test(s.body) && /kept its own colours/.test(s.body) && /What Android reports for each color:/.test(s.body) && /android:color\/system_accent1_0 → #ff1b1b1b/.test(s.body) && (await toast()) === 'The theme overlay is on, but Android shows its own colors' && (await note()) === 'The theme overlay is written on this phone.', JSON.stringify(s).slice(0, 600));
  check('   the overlay is listed on all the same, so it stays remembered for Reset and Undo; the theme bar still offers Undo', JSON.stringify(((await kv('ovl_root_applied')) || {}).ids) === '["' + AAM + '"]' && (await snack()) === 'Theme applied' && (await undoShown()));
  await closeAll();

  // 2) the Java gate says no
  await ev(() => { window.__fabDeny = null; window.__fabNeedsRoot = true; kvSet('ovl_root_applied', null); }); await reset();
  await apply();
  s = await sheet();
  check('2. the Java gate saying no: the sheet says Needs Super User, that Shizuku and ADB cannot write these colors and how to get uid 0; nothing remembered', s.shown && s.title === 'Could not write the theme overlay' && /Needs Super User/.test(s.body) && /Shizuku and ADB cannot write these colors; Super User \(uid 0\) can\. Choose Root in Working Modes, then tap Apply again\./.test(s.body) && !/Wireless Debugging/.test(s.body) && (await kv('ovl_root_applied')) === null && (await toast()) === 'The theme overlay was not written', JSON.stringify(s).slice(0, 400));
  await closeAll();
  const t0 = await ev(() => { const t = Date.now() - 5000; kvSet('ovl_root_applied', { ids: ['com.android.shell:aam_monet'], t, style: 'TONAL_SPOT' }); return t; }); await reset();
  await apply();
  check('   the same with an overlay written before: the record made before the write is put back as it was (written, not "may be")', JSON.stringify(await kv('ovl_root_applied')) === JSON.stringify({ ids: [AAM], t: t0, style: 'TONAL_SPOT' }) && (await note()) === 'The theme overlay is written on this phone.', JSON.stringify(await kv('ovl_root_applied')));
  await closeAll();

  // 3) no answer from the shell
  await ev(() => { kvSet('ovl_root_applied', null); window.__fabNeedsRoot = false; window.__fabUnknown = true; }); await reset();
  await apply();
  s = await sheet();
  check('3. no answer from the shell: the sheet says the phone may still apply it; the overlay is remembered as possibly written and the note says so', s.shown && /may still apply it/.test(s.body) && JSON.stringify(((await kv('ovl_root_applied')) || {}).ids) === '["' + AAM + '"]' && ((await kv('ovl_root_applied')) || {}).pending === true && (await note()) === 'The theme overlay may be written on this phone.', JSON.stringify(s).slice(0, 300));
  await closeAll();

  // 4) the theme setting itself refused
  await ev(() => { window.__fabUnknown = false; window.__themeDeny = 'not allowed to write secure settings'; kvSet('ovl_root_applied', null); }); await reset();
  await apply();
  check('4. when the theme setting itself is refused, the overlay is not written either', (await calls()).apply.length === 1 && (await calls()).fab.length === 0 && (await sheet()).shown && /Theme change refused/.test((await sheet()).title));
  await closeAll();
  await ev(() => { window.__themeDeny = null; });
  await sleep(2600);                                            // the "no answer" above re-reads the list after 2.5 s: let that pass first

  // 5) no answer at all: the page's own timeout (60 s, shortened to 300 ms here)
  await ev(() => { kvSet('ovl_root_applied', null); window.__fabNoAnswer = true; window.__realST = window.setTimeout; window.setTimeout = function (fn, ms, ...a) { return window.__realST.call(window, fn, ms === 60000 ? 300 : ms, ...a); }; }); await reset();
  await apply();
  s = await sheet();
  const noAns = await ev(() => { window.setTimeout = window.__realST; window.__fabNoAnswer = false; window.__ovlCalls.list = 0; return { applied: kvGet('ovl_root_applied', null), pending: sdbReqs.size, note: document.getElementById('ovlRootNote').innerText }; });
  check('5. no answer at all: after the page\'s own timeout the sheet says No answer from the device (timed out) and that the phone may still apply it', (await calls()).fab.length === 1 && s.shown && s.title === 'Could not write the theme overlay' && /No answer from the device \(timed out\)/.test(s.body) && /may still apply it/.test(s.body) && (await toast()) === 'The theme overlay was not written' && noAns.pending === 0, JSON.stringify(s).slice(0, 300));
  check('   the overlay is remembered as possibly written; the theme bar offers Undo', JSON.stringify((noAns.applied || {}).ids) === '["' + AAM + '"]' && (noAns.applied || {}).pending === true && noAns.note === 'The theme overlay may be written on this phone.' && (await snack()) === 'Theme applied' && (await undoShown()), JSON.stringify([noAns.applied, noAns.note]));
  await closeAll();
  await sleep(2600);
  check('   and the overlay list is read again shortly (after 2.5 s)', (await calls()).list === 1, String((await calls()).list));

  // 6) a late answer
  await ev(() => { kvSet('ovl_root_applied', null); window.__ovlCalls.list = 0; window.onOverlayFab(JSON.stringify({ req: 999999, op: 'fabricate', ok: true, ids: ['com.android.shell:aam_monet'] })); });
  await sleep(200);
  check('6. an answer that comes after the page gave up on it is not taken for a live one', (await calls()).list === 0 && (await kv('ovl_root_applied')) === null && errors.length === 0);
  await sleep(2600);
  check('   but makes the list be read again, quietly', (await calls()).list === 1, String((await calls()).list));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
