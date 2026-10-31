// Apply, Undo and Reset with Super User on the one Theme screen (v7.22.0, ColorBlendr parity; the PR 2 flows carried over): every Apply writes
// the theme setting and then this app's overlay (the fabricate plan from MonetPlan.cb, one overlay on Android 14 and newer); the record
// (kv ovl_root_applied) made before the write and settled by the answer; the Undo bar after Apply switches the overlay off first when there was
// none before, and writes the one from before again (from its record's options, T-069) when there was; an older record without options, or
// one still pending, is switched off with the message; Reset asks first, naming the overlay, then resets the theme and switches the overlay off,
// and its Undo writes it again; a disable Android answers with "not listed" counts as removed (T-073); the choices are taken when Apply is
// tapped (T-067), so a switch flipped while it is busy counts for the next Apply; a mode change locks everything and Apply calls the bridge no more.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
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
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const note = () => ev(() => document.getElementById('ovlRootNote').innerText);
  const settle = (p = page) => p.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const undo = async (p = page) => { await p.locator('#sdbSnackUndo').click(); await settle(p); await p.waitForTimeout(120); };
  const listed = id => ev(i => { const o = window.__ovl.find(x => x.id === i); return o ? o.state : -9; }, id);
  const AAM = 'com.android.shell:aam_monet';

  await ev(() => switchView('overlays')); await sleep(400);
  check('0. Root on Android 14: live, nothing written', await ev(() => ovlRootLive() && kvGet('ovl_root_applied', null) === null));

  // 1) the first Apply: the theme setting, then the overlay; the record
  await ev(() => ovlPickColor('6750A4')); await reset();
  await apply();
  let k = await calls(), rec = await kv('ovl_root_applied');
  check('1. Apply writes the theme (preset 6750A4, Tonal Spot) and then the overlay: 181 entries for Android 14', k.apply.length === 1 && k.apply[0].hex === '6750A4' && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 1 && k.fab[0].action === 'fabricate' && k.fab[0].entries.length === 181, JSON.stringify([k.apply, k.fab.length]));
  check('   the overlay is listed on, remembered with its options and the level; the toasts and the Undo bar', (await listed(AAM)) === 1 && rec && rec.ids.join() === AAM && rec.style === 'TONAL_SPOT' && rec.opts.seed === '6750A4' && rec.sdk === 34 && !rec.pending && (await toast()) === 'Theme overlay written' && (await snack()) === 'Theme applied' && (await undoShown()), JSON.stringify([rec, await toast(), await snack()]));
  check('   the note under the switches says it is on the phone', (await note()) === 'The theme overlay is written on this phone.', await note());

  // 2) Undo with nothing before: the overlay off first, then the theme
  await reset(); await undo();
  k = await calls();
  check('2. Undo with no overlay before: a disable with the remembered id first, then the theme back', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && k.restore.length === 1 && k.restoreUndo[0] === true, JSON.stringify([k.fab, k.restore]));
  check('   the overlay is forgotten and listed off; Theme put back; no new Undo', (await kv('ovl_root_applied')) === null && (await listed(AAM)) === 0 && (await snack()) === '' && (await toast()) === 'Theme put back', JSON.stringify([await kv('ovl_root_applied'), await toast()]));

  // 3) Undo with an overlay before (T-069): the theme back, then the overlay from before written again from its record's options
  await reset(); await apply();
  const first = (await calls()).fab[0];
  await ev(() => { ovlPickColor('E91E63'); kvSet('ovl_black', true); ovlRender(); }); await reset(); await apply();
  k = await calls();
  check('3. a second Apply (another colour, Pure black) writes the overlay again: 182 entries, the record says black', k.fab.length === 1 && k.fab[0].action === 'fabricate' && k.fab[0].entries.length === 182 && ((await kv('ovl_root_applied')) || {}).black === true && ((await kv('ovl_root_applied')) || {}).opts.seed === 'E91E63', JSON.stringify([k.fab[0].entries.length, await kv('ovl_root_applied')]));
  await reset(); await undo();
  k = await calls();
  check('   its Undo puts the theme back and then writes the overlay from before, entry for entry (no disable); the record is the earlier one again', k.restore.length === 1 && k.restoreUndo[0] === true && k.fab.length === 1 && k.fab[0].action === 'fabricate' && JSON.stringify(k.fab[0].entries) === JSON.stringify(first.entries) && ((await kv('ovl_root_applied')) || {}).opts.seed === '6750A4' && ((await kv('ovl_root_applied')) || {}).black === false && (await listed(AAM)) === 1 && (await toast()) === 'Theme put back', JSON.stringify([k.fab.map(f => f.action + ':' + (f.entries || []).length), await kv('ovl_root_applied'), await toast()]));
  // an older record without options (one from before this version) cannot be rebuilt: switched off, the theme back, the message says so
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now() - 86400000, black: false, style: 'FIDELITY' }); ovlRender(); }, AAM);
  await reset(); await apply(); await reset(); await undo();
  k = await calls();
  check('   an older record without options: its Undo switches the overlay off first, puts the theme back, and the message says the overlay from before could not be written again', k.fab.length === 1 && k.fab[0].action === 'disable' && k.restore.length === 1 && (await kv('ovl_root_applied')) === null && (await toast()) === 'Theme put back. The overlay from before could not be written again, so it is off.', JSON.stringify([k.fab.map(f => f.action), await toast()]));
  // a pending record (maybe written, never confirmed) is not written again as if it had been on
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now() - 60000, pending: true, black: true, style: 'TONAL_SPOT', opts: MonetPlan.cb.defaults(), sdk: 34 }); ovlRender(); }, AAM);
  await reset(); await apply();
  check('   a pending record before the Apply: the Apply writes and confirms its own overlay', (await calls()).fab.length === 1 && (await calls()).fab[0].action === 'fabricate' && ((await kv('ovl_root_applied')) || {}).pending !== true);
  await reset(); await undo();
  k = await calls();
  check('   its Undo does not rebuild the pending record: switched off first, the theme back, the same message', k.fab.length === 1 && k.fab[0].action === 'disable' && k.restore.length === 1 && (await kv('ovl_root_applied')) === null && (await toast()) === 'Theme put back. The overlay from before could not be written again, so it is off.', JSON.stringify([k.fab.map(f => f.action), await toast()]));
  // PR 2's record (kv ovl_black_applied) is still read, and cleared by the first write
  await ev(id => { kvSet('ovl_black_applied', { ids: [id], t: Date.now() - 86400000 }); window.__ovl.find(x => x.id === id).state = 1; ovlRender(); }, AAM);
  check('   a Pure black record of the v7.18 build is read: the note says so', (await note()) === 'The pure black overlay is written on this phone.', await note());
  await reset(); await apply();
  check('   the next Apply moves the record to the new key', (await kv('ovl_black_applied')) === null && ((await kv('ovl_root_applied')) || {}).style === 'TONAL_SPOT' && (await note()) === 'The theme overlay is written on this phone.');

  // 4) Reset: asks first, resets the theme, switches the overlay off; its Undo writes it again
  await reset(); dialogs.length = 0;
  await page.locator('#ovlResetBtn').click(); await settle(); await sleep(120);
  k = await calls();
  check('4. Reset asks first, naming the overlay; confirmed, it resets the theme and then switches the overlay off', dialogs.length === 1 && /theme overlay is switched off too/.test(dialogs[0]) && k.restore.length === 1 && k.restore[0] === '' && k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]', JSON.stringify([dialogs[0], k.fab]));
  check('   forgotten and listed off; the switch stays as set (Pure black on); the bar offers the old theme', (await kv('ovl_root_applied')) === null && (await listed(AAM)) === 0 && (await kv('ovl_black')) === true && (await snack()) === 'Theme reset to the default' && (await toast()) === 'Theme overlay removed', JSON.stringify([await kv('ovl_root_applied'), await snack(), await toast()]));
  await reset(); await undo();
  k = await calls();
  check('   its Undo puts the theme back, then writes the overlay Reset switched off again (182 entries), the record back', k.restore.length === 1 && k.fab.length === 1 && k.fab[0].action === 'fabricate' && k.fab[0].entries.length === 182 && ((await kv('ovl_root_applied')) || {}).black === true, JSON.stringify([k.fab.map(f => f.action), await kv('ovl_root_applied')]));
  await ev(id => { kvSet('ovl_root_applied', null); window.__ovl.find(x => x.id === id).state = 0; }, AAM);
  dialogs.length = 0; answer = false; await reset();
  await page.locator('#ovlResetBtn').click(); await sleep(200);
  check('   with nothing written the question does not mention the overlay; Cancel changes nothing', dialogs.length === 1 && !/overlay/.test(dialogs[0]) && (await calls()).restore.length === 0 && (await calls()).fab.length === 0, dialogs[0]);
  answer = true;

  // 5) T-073: a disable Android answers with "not listed" or "listed off" counts as removed; listed on is a failure; no answer keeps the record
  const sheet = () => ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), pending: true, style: 'TONAL_SPOT' }); ovlRootDone({ req: 1, op: 'disable', ok: false, state: -2, ids: [id], error: 'That overlay is not listed any more (' + id + ')', list: [['android.theme.customization.accent_color', 'android', 1], [id + '_other', 'android', 1]] }, 'disable', null, null); }, AAM);
  check('5. a disable of an overlay Android does not list: removed, no sheet, forgotten', !(await sheet()).shown && (await toast()) === 'Theme overlay removed' && (await kv('ovl_root_applied')) === null && (await note()) === '', JSON.stringify([(await sheet()).shown, await toast()]));
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), style: 'TONAL_SPOT' }); ovlRootDone({ req: 1, op: 'disable', ok: false, state: 1, ids: [id], error: 'Android did not switch it off (' + id + ')', list: [[id, 'android', 1]] }, 'disable', null, null); }, AAM);
  check('   one still listed on: the failure toast and sheet, the record kept', (await sheet()).shown && (await sheet()).title === 'Could not remove the theme overlay' && (await toast()) === 'The theme overlay was not removed' && JSON.stringify(((await kv('ovl_root_applied')) || {}).ids) === '["' + AAM + '"]', JSON.stringify([(await sheet()).title, await toast()]));
  await closeAll();
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), style: 'TONAL_SPOT' }); ovlRootDone({ req: 1, op: 'disable', ok: false, unknown: true, ids: [id], error: 'No answer from the device' }, 'disable', null, null); }, AAM);
  check('   a disable without an answer keeps the overlay remembered', JSON.stringify(((await kv('ovl_root_applied')) || {}).ids) === '["' + AAM + '"]');
  await closeAll(); await sleep(2600);
  await ev(id => { kvSet('ovl_root_applied', null); window.__ovl.find(x => x.id === id).state = 0; }, AAM);

  // 6) T-067: the choices are taken when Apply is tapped; a switch flipped while it is busy counts for the next Apply
  await ev(() => { window.__delay = 400; kvSet('ovl_black', false); ovlRender(); }); await reset();
  await page.locator('#ovlApplyBtn').click(); await sleep(80);
  const busy = await ev(() => ({ busy: ovlBusy, text: document.getElementById('ovlApplyBtn').innerText, disabled: document.getElementById('ovlBlackSw').disabled === true }));
  await page.locator('#ovlBlackSw').click();
  await settle(); await sleep(100);
  k = await calls();
  check('6. Pure black off when Apply is tapped, on while it reads Applying…: the overlay written without it (181 entries); the switch is on for the next Apply', busy.busy && busy.text === 'Applying…' && !busy.disabled && k.fab.length === 1 && k.fab[0].entries.length === 181 && ((await kv('ovl_root_applied')) || {}).black === false && (await kv('ovl_black')) === true, JSON.stringify([busy, k.fab.length, await kv('ovl_black')]));
  await ev(() => { window.__delay = 20; });

  // 7) the lock follows the working mode: everything locked, Apply writes the theme alone; a root-only style chosen falls back to Tonal Spot
  await ev(() => { ovlStyle = 'FIDELITY'; ovlRender(); });
  await ev(() => { window.__root = false; checkAllWorkingModes(false); }); await sleep(150);
  check('7. the working mode leaving Root: the rows are locked, the note says the overlay stays on', await ev(() => document.querySelectorAll('#ovlSwitches .rf-row.is-locked').length === 3) && (await note()) === 'Shizuku and ADB cannot write these colors; Super User (uid 0) can.\nThe theme overlay is written on this phone.', await note());
  check('   the Fidelity chosen before is greyed and Tonal Spot is the one marked', await ev(() => document.querySelector('#ovlStyles .ovl-style.active').dataset.style === 'TONAL_SPOT' && document.querySelector('#ovlStyles .ovl-style[data-style="FIDELITY"]').disabled));
  await reset(); await apply();
  k = await calls();
  check('   Apply writes the theme with Tonal Spot and calls the bridge no more; the record stays (the overlay is still on)', k.apply.length === 1 && k.apply[0].style === 'TONAL_SPOT' && k.fab.length === 0 && !!(await kv('ovl_root_applied')), JSON.stringify(k.apply));
  dialogs.length = 0; answer = false;
  await page.locator('#ovlResetBtn').click(); await sleep(200);
  check('   Reset\'s question says the overlay stays on: switching it off needs Super User (uid 0)', dialogs.length === 1 && /The overlay stays on: switching it off needs Super User \(uid 0\)\./.test(dialogs[0]), dialogs[0]);
  answer = true;
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(150);
  check('   Root back: live again, Fidelity marked again (the choice was kept)', await ev(() => ovlRootLive() && ovlStyle === 'FIDELITY' && document.querySelector('#ovlStyles .ovl-style.active').dataset.style === 'FIDELITY'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
