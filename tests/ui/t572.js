// Restarts around the overlay write on the one Theme screen (v7.22.0; the PR 2 and T-068 / T-069 flows carried over). Android repaints every
// app after a theme change and when the overlay comes on, so this app may restart before an answer reaches the page. A restart with the
// overlay written brings back the tab, the note and the Undo bar; one before the overlay's answer finds the record made before the write (as
// possibly written), so the restart's Undo still switches it off; one before the theme's own answer (T-068) keeps what the tap planned
// (fabricate, with the options) in the resume record: the theme read back as written completes that Apply (the overlay written with the
// usual toast), read back as not written nothing is written and a toast says so; an Undo whose theme answer was lost (T-069) likewise writes
// the overlay from before once the theme reads back as put back.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const KEY = 'theme_customization_overlay_packages', AAM = 'com.android.shell:aam_monet';
  const settle = p => p.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const undo = async p => { await p.locator('#sdbSnackUndo').click(); await settle(p); await p.waitForTimeout(120); };
  const openWith = async (kvs, init) => {
    const p = await b.newPage({ viewport: { width: 360, height: 800 } });
    p.on('pageerror', e => errors.push(e.message));
    await p.addInitScript(k => { window.__kvInit = JSON.parse(k); }, kvs);
    await p.addInitScript(sdb.initScript); await p.addInitScript(ovl.initScript);
    await p.addInitScript(init.fn, init.arg);
    await p.addInitScript(() => { const hook = () => { if (typeof showToast !== 'function') { setTimeout(hook, 5); return; } const real = showToast; window.__toasts = []; window.showToast = function (m) { window.__toasts.push(m); return real.apply(this, arguments); }; }; hook(); });
    return p;
  };
  const state = p => p.evaluate(() => ({ view: currentViewName(), snack: document.getElementById('sdbSnack').classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : '', fab: window.__ovlCalls.fab.map(f => f.action + (f.entries ? ':' + f.entries.length : '')), restore: window.__ovlCalls.restore.length, toasts: (window.__toasts || []).slice(), applied: kvGet('ovl_root_applied', null), done: (kvGet('ovl_resume', null) || {}).done === true, note: document.getElementById('ovlRootNote').innerText, black: document.getElementById('ovlBlackSw') ? document.getElementById('ovlBlackSw').getAttribute('aria-checked') : null }));

  // 1) a restart with the overlay written: the tab, the note, the switch and the Undo come back
  let p = await openWith(JSON.stringify({ ovl_black: 'true', ovl_root_applied: JSON.stringify({ ids: [AAM], t: Date.now(), style: 'TONAL_SPOT', sdk: 34 }), ovl_resume: JSON.stringify({ t: Date.now(), sub: 'theme', undo: '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT"}' }) }), { fn: () => { window.__root = true; window.__sdk = 34; }, arg: null });
  await p.goto(URL); await p.waitForTimeout(1900);
  let r = await state(p);
  check('1. after a restart the tab is back with Pure black still on, the note says the overlay is on the phone, and the Undo bar is back', r.view === 'overlays' && r.black === 'true' && r.note === 'The theme overlay is written on this phone.' && r.snack === 'Theme changed', JSON.stringify(r));
  await p.evaluate(() => { window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; });
  await undo(p);
  r = await state(p);
  check('   its Undo switches the overlay off (no record from before to rebuild) and then puts the old theme back', JSON.stringify(r.fab) === '["disable"]' && r.restore === 1 && r.applied === null, JSON.stringify(r));
  await p.close();

  // 2) a restart before the overlay's answer: remembered as possibly written before the write went out
  p = await openWith(JSON.stringify({}), { fn: () => { window.__root = true; window.__sdk = 34; window.__fabNoAnswer = true; }, arg: null });
  await p.goto(URL); await p.waitForTimeout(500);
  await p.evaluate(() => switchView('overlays')); await p.waitForTimeout(400);
  await p.locator('#ovlApplyBtn').click();
  await p.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000 }).catch(() => {});
  const mid = await p.evaluate(() => ({ fab: window.__ovlCalls.fab.length, busy: ovlBusy, applied: kvGet('ovl_root_applied', null), resume: kvGet('ovl_resume', null), note: document.getElementById('ovlRootNote').innerText }));
  check('2. the overlay\'s write is out and unanswered: already remembered as possibly written, with its options, and the theme\'s Undo record is kept and done', mid.fab === 1 && mid.busy && JSON.stringify((mid.applied || {}).ids) === '["' + AAM + '"]' && mid.applied.pending === true && !!mid.applied.opts && !!mid.resume && typeof mid.resume.undo === 'string' && mid.resume.done === true && mid.note === 'The theme overlay may be written on this phone.', JSON.stringify(mid).slice(0, 500));
  const saved = await p.evaluate(() => JSON.stringify(window.AndroidBridge.__kv));
  await p.close();                                              // the restart, before the answer
  p = await openWith(saved, { fn: () => { window.__root = true; window.__sdk = 34; }, arg: null });
  await p.goto(URL); await p.waitForTimeout(1900);
  r = await state(p);
  check('   after the restart: back on the tab with the Undo bar; the note says the overlay may be on the phone', r.view === 'overlays' && r.snack === 'Theme changed' && r.note === 'The theme overlay may be written on this phone.', JSON.stringify(r).slice(0, 400));
  await p.evaluate(() => { window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; });
  await undo(p);
  r = await state(p);
  check('   its Undo switches the overlay off first (the pending record is not written again), then puts the old theme back; nothing remembered', JSON.stringify(r.fab) === '["disable"]' && r.restore === 1 && r.applied === null && r.note === '', JSON.stringify(r).slice(0, 400));
  await p.close();

  // 3) T-068: a restart before the theme's own answer
  const tapLost = async (black) => {
    const q = await openWith(JSON.stringify({ ovl_black: black ? 'true' : 'false' }), { fn: () => { window.__root = true; window.__sdk = 34; }, arg: null });
    await q.goto(URL); await q.waitForTimeout(500);
    await q.evaluate(() => switchView('overlays')); await q.waitForTimeout(400);
    await q.evaluate(() => { window.__ovlNoAnswer = true; });
    await q.locator('#ovlApplyBtn').click(); await q.waitForTimeout(150);
    const out = await q.evaluate(() => {
      const a = window.__ovlCalls.apply[0] || {}, st = a.style || 'TONAL_SPOT';
      const value = a.source === 'preset' ? JSON.stringify({ 'android.theme.customization.system_palette': a.hex, 'android.theme.customization.color_source': 'preset', 'android.theme.customization.theme_style': st, _applied_timestamp: Date.now() })
        : JSON.stringify({ 'android.theme.customization.color_source': 'home_wallpaper', 'android.theme.customization.theme_style': st, _applied_timestamp: Date.now() });
      return { kv: JSON.stringify(window.AndroidBridge.__kv), value, resume: kvGet('ovl_resume', null), fab: window.__ovlCalls.fab.length };
    });
    await q.close();
    return out;
  };
  const restarted = async (lost, written) => {
    const q = await openWith(lost.kv, { fn: ([key, v]) => { window.__root = true; window.__sdk = 34; if (v) window.__db.secure[key] = v; }, arg: [KEY, written ? lost.value : null] });
    await q.goto(URL); await q.waitForTimeout(1900);
    await settle(q); await q.waitForTimeout(100);
    return { p: q, r: await state(q) };
  };
  let lost = await tapLost(true);
  check('3. Apply with Pure black on, the theme\'s answer lost to a restart: the resume record keeps what the tap planned (fabricate) and its options (Pure black on); nothing written yet', lost.fab === 0 && !!lost.resume && lost.resume.kind === 'apply' && lost.resume.root === 'fabricate' && !!lost.resume.at && !!lost.resume.at.opts && lost.resume.at.opts.black === true && lost.resume.done === false, JSON.stringify(lost.resume).slice(0, 400));
  let rs = await restarted(lost, true);
  check('   after the restart, the theme read back as written: that Apply\'s overlay is written (182 entries with Pure black) with the usual toast, the Undo bar is back, the record written, the resume record done', rs.r.view === 'overlays' && rs.r.snack === 'Theme changed' && JSON.stringify(rs.r.fab) === '["fabricate:182"]' && rs.r.toasts.includes('Theme overlay written') && !!rs.r.applied && rs.r.applied.black === true && rs.r.applied.pending !== true && rs.r.done && rs.r.note === 'The theme overlay is written on this phone.', JSON.stringify(rs.r).slice(0, 500));
  await rs.p.close();
  lost = await tapLost(false);
  rs = await restarted(lost, false);
  check('   the theme read back as not written: no overlay is written, and a toast says the colors were not written and to tap Apply theme again', JSON.stringify(rs.r.fab) === '[]' && rs.r.toasts.includes('The colors were not written. Tap Apply theme again.') && rs.r.applied === null && rs.r.note === '', JSON.stringify(rs.r).slice(0, 400));
  await rs.p.close();

  // 4) T-069: an Undo whose theme answer was lost, with an overlay from before to put back
  p = await openWith(JSON.stringify({}), { fn: () => { window.__root = true; window.__sdk = 34; }, arg: null });
  await p.goto(URL); await p.waitForTimeout(500);
  await p.evaluate(() => switchView('overlays')); await p.waitForTimeout(400);
  await p.evaluate(() => ovlPickColor('6750A4'));
  await p.locator('#ovlApplyBtn').click(); await settle(p); await p.waitForTimeout(120);
  const before = await p.evaluate(() => ({ raw: ovlRaw, rec: kvGet('ovl_root_applied', null), plan: window.__ovlCalls.fab[0] }));
  await p.evaluate(() => { ovlPickColor('E91E63'); kvSet('ovl_black', true); ovlRender(); });
  await p.locator('#ovlApplyBtn').click(); await settle(p); await p.waitForTimeout(120);
  await p.evaluate(() => { window.__ovlNoAnswer = true; window.__ovlCalls.fab.length = 0; });
  await p.locator('#sdbSnackUndo').click(); await p.waitForTimeout(150);
  const undoLost = await p.evaluate(() => ({ fab: window.__ovlCalls.fab.length, resume: kvGet('ovl_resume', null), kv: JSON.stringify(window.AndroidBridge.__kv) }));
  check('4. an Undo with an overlay from before, its theme answer lost: the resume record keeps the Undo with the overlay to put back (the options of the first Apply)', undoLost.fab === 0 && !!undoLost.resume && undoLost.resume.kind === 'undo' && !!undoLost.resume.restore && undoLost.resume.restore.opts.seed === '6750A4' && undoLost.resume.target === before.raw, JSON.stringify(undoLost.resume).slice(0, 400));
  await p.close();
  p = await openWith(undoLost.kv, { fn: ([key, v]) => { window.__root = true; window.__sdk = 34; window.__db.secure[key] = v; }, arg: [KEY, before.raw] });
  await p.goto(URL); await p.waitForTimeout(1900);
  await settle(p); await p.waitForTimeout(100);
  r = await state(p);
  check('   after the restart, the theme read back as put back: the overlay from before is written again, entry for entry, with Theme put back', JSON.stringify(r.fab) === '["fabricate:181"]' && r.toasts.includes('Theme put back') && !!r.applied && r.applied.opts.seed === '6750A4' && r.done, JSON.stringify(r).slice(0, 400));
  check('   entry for entry', await p.evaluate(e => JSON.stringify(window.__ovlCalls.fab[0].entries) === JSON.stringify(e), before.plan.entries));
  await p.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
