// Root-only theme controls on the Overlays tab's Theme panel (PR 2 of the ColorBlendr series): the card group under "How this works"; locked
// rows without Super User (the Root Features locked row: a disabled Locked button, "Needs Super User", no bridge call, Apply unchanged); the
// Pure black backgrounds switch with Super User (kv ovl_black, off by default, toggles, persists); the plan Apply sends through
// overlayFabricate after the theme setting (11 entries on Android 14, 2 on Android 13, the greys from the page's own psHex); the disable plan
// when the switch is off after a write; the result (toast, kv ovl_black_applied with the ids, the list, the palette watch); the failure sheet
// (refused colours with the lookups, Needs Super User, no answer from the shell, no answer at all: the page's own timeout); the record made
// before the write and settled by the answer; Undo and Revert switching the overlay off; a mode change locking the rows again; an app
// without the bridge ("Update the app"); Android 11 (nothing); a late answer; a restart with the Undo, also one that comes before the
// overlay's answer; 320 px, dark and light.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const undoShown = () => ev(() => document.getElementById('sdbSnackUndo').style.display !== 'none');
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; window.__ovlCalls.restoreUndo.length = 0; });
  const kv = key => ev(k => kvGet(k, null), key);
  const setRoot = async on => { await ev(v => { window.__root = v; checkAllWorkingModes(false); }, on); await sleep(120); };
  const card = () => ev(() => {
    const c = document.getElementById('ovlRootCard'), box = document.getElementById('ovlRootCards'), row = box.querySelector('.rf-row');
    const sw = document.getElementById('ovlBlackSw'), lockBtn = box.querySelector('.op-mode-btn.locked'), lock = box.querySelector('.pm-lock');
    return { shown: getComputedStyle(c).display !== 'none', title: c.querySelector('.color-card-title').innerText, rows: box.querySelectorAll('.rf-row').length,
      locked: !!(row && row.classList.contains('is-locked')), lockBtn: lockBtn ? { text: lockBtn.innerText, disabled: lockBtn.disabled } : null, lock: lock ? lock.innerText : '',
      name: row ? row.querySelector('.rf-name').innerText.replace(/\s+/g, ' ').trim() : '', sw: sw ? { on: sw.classList.contains('on'), checked: sw.getAttribute('aria-checked'), role: sw.getAttribute('role') } : null,
      note: document.getElementById('ovlRootNote').innerText, hint: document.getElementById('ovlApplyHint').innerText.replace(/\s+/g, ' ').trim(), main: (document.querySelector('#ovlApplyHint span') || { innerText: '' }).innerText, rootHint: (document.getElementById('ovlApplyHintRoot') || { innerText: '' }).innerText };
  });
  const USUAL = /^(This is already what the phone uses\.|Android repaints in a moment, and apps may restart\.)$/;      // the hint's own sentence (the editor starts on what the phone uses)
  const sheet = () => ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  // the replies come on timers: wait until the tab has nothing in flight (no request out, not busy) rather than for a fixed time
  const settle = (p = page) => p.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async ms => { await page.locator('#ovlApplyBtn').click(); if (ms) await sleep(ms); else { await settle(); await sleep(100); } };
  const undo = async (p = page) => { await p.locator('#sdbSnackUndo').click(); await settle(p); await p.waitForTimeout(100); };
  const AAM = 'com.android.shell:aam_monet';

  // 1) without Super User (ADB TCP): locked, nothing interactive, Apply as before
  await ev(() => switchView('overlays')); await sleep(300);
  let c = await card();
  check('1. without Super User: the card group is there with one locked row', c.shown && c.title === 'Root-only theme controls' && c.rows === 1 && c.locked, JSON.stringify(c));
  check('   it is the last card of the Theme panel, right after "How this works"', await ev(() => { const cards = Array.from(document.querySelectorAll('#ovlThemePanel > .color-card')); const i = cards.findIndex(x => /How this works/.test(x.querySelector('.color-card-title').innerText)); return i >= 0 && cards[i + 1] && cards[i + 1].id === 'ovlRootCard' && cards.length === i + 2; }));
  check('   the row is a Root Features locked row: the name and its line, a disabled Locked button, the caption Needs Super User, no switch', c.name === 'Pure black backgrounds True black dark backgrounds, panels in a few dark greys. Light mode is unchanged.' && c.lockBtn && c.lockBtn.text === 'Locked' && c.lockBtn.disabled && c.lock === 'Needs Super User' && !c.sw, JSON.stringify(c));
  check('   the line under it says why', c.note === 'Shizuku and ADB cannot write these colors; Root can.', c.note);
  check('   the hint under Apply says nothing about the overlay', c.rootHint === '' && USUAL.test(c.hint), JSON.stringify([c.hint, c.rootHint]));
  check('   nothing of the card is interactive (its only button is disabled)', await ev(() => Array.from(document.querySelectorAll('#ovlRootCards button')).every(x => x.disabled) && document.querySelectorAll('#ovlRootCards button').length === 1));
  await ev(() => { kvSet('ovl_black', true); kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t: Date.now() }); ovlRender(); });      // remembered from a rooted session: still locked, still no call
  c = await card();
  check('   a switch and an overlay remembered from a rooted session change nothing while locked', c.locked && c.rootHint === '', JSON.stringify([c.locked, c.rootHint]));
  await reset(); await apply();
  let k = await calls();
  check('   Apply writes the theme setting as before and never calls overlayFabricate', k.apply.length === 1 && k.fab.length === 0 && (await snack()) === 'Theme applied' && (await undoShown()), JSON.stringify([k.apply.length, k.fab.length, await snack()]));
  await reset(); await undo();
  check('   its Undo puts the theme back only (the overlay cannot be touched without root)', (await calls()).restore.length === 1 && (await calls()).fab.length === 0 && (await kv('ovl_black_applied')) !== null);
  await ev(() => { delete window.AndroidBridge.__kv.ovl_black; kvSet('ovl_black_applied', null); });

  // 2) with Super User: the switch
  await setRoot(true);
  c = await card();
  check('2. with Super User the row unlocks: a switch (role switch), off, no Locked button, no caption, no line', !c.locked && c.sw && !c.sw.on && c.sw.checked === 'false' && c.sw.role === 'switch' && !c.lockBtn && c.lock === '' && c.note === '' && c.rows === 1, JSON.stringify(c));
  check('   the gate is isRootActive()', await ev(() => isRootActive() && ovlRootLive()));
  check('   off by default: nothing stored yet', (await kv('ovl_black')) === null);
  await page.locator('#ovlBlackSw').click(); await sleep(80);
  c = await card();
  check('   tapping the switch turns it on and stores it (kv ovl_black)', c.sw.on && c.sw.checked === 'true' && (await kv('ovl_black')) === true, JSON.stringify(c.sw));
  check('   the hint under Apply now says the overlay is written too, after the usual sentence (each its own element, translated whole)', c.rootHint === 'Also writes the pure black overlay with root.' && USUAL.test(c.main) && c.hint === c.main + ' Also writes the pure black overlay with root.', JSON.stringify([c.hint, c.main, c.rootHint]));
  await page.locator('#ovlBlackSw').click(); await sleep(80);
  c = await card();
  check('   tapping again turns it off, stores that, and the hint drops the sentence', !c.sw.on && c.sw.checked === 'false' && (await kv('ovl_black')) === false && c.rootHint === '', JSON.stringify([c.sw, c.rootHint]));
  await ev(() => { kvSet('ovl_black', true); ovlRender(); }); c = await card();
  check('   a redraw reads the switch from the store', c.sw.on && c.rootHint === 'Also writes the pure black overlay with root.');

  // 3) Apply with the switch on, Android 14: the plan, the result
  await reset(); await ev(() => { window.__ovlCalls.palette = 0; ovlList = null; });
  // who starts the palette watch: the overlay's answer itself (ovlRootDone), not only the theme's finish that follows it
  await ev(() => { const real = ovlWatchPalette; window.__realWatch = real; window.__watchFrom = []; window.ovlWatchPalette = function () { const m = /at (?:Object\.)?([\w$.]+)/.exec(String(new Error().stack || '').split('\n')[2] || ''); window.__watchFrom.push(m ? m[1] : '?'); return real.apply(this, arguments); }; });
  const greys = await ev(() => [4, 6, 8, 11, 14, 18].map(t => psHex(0, 0, t).slice(1)));
  await ev(() => { window.__delay = 400; });
  await apply(100);
  check('3. Apply with the switch on: the theme setting goes first, the button is busy meanwhile', (await calls()).apply.length === 1 && (await calls()).fab.length === 0 && (await ev(() => document.getElementById('ovlApplyBtn').innerText)) === 'Applying…' && (await ev(() => document.getElementById('ovlApplyBtn').disabled)), JSON.stringify([(await calls()).apply.length, (await calls()).fab.length]));
  await page.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000, polling: 20 }).catch(() => {});
  check('   once the setting is in, the overlay is asked for and the button stays busy until that has answered too', (await calls()).fab.length === 1 && (await ev(() => document.getElementById('ovlApplyBtn').disabled)) && (await snack()) === '', JSON.stringify([(await calls()).fab.length, await snack()]));
  await settle(); await sleep(100);
  await ev(() => { window.__delay = 20; });
  k = await calls();
  const plan = k.fab[0] || {};
  check('   then overlayFabricate gets the pure black plan: action fabricate, 11 entries on Android 14', k.fab.length === 1 && plan.action === 'fabricate' && Array.isArray(plan.entries) && plan.entries.length === 11, JSON.stringify(plan));
  const byRes = Object.fromEntries((plan.entries || []).map(e => [String(e.resource).replace('android:color/', ''), e.argb]));
  check('   the two darkest neutral shades, the dark background, surface and surface_dim are black', ['system_neutral1_900', 'system_neutral2_900', 'system_background_dark', 'system_surface_dark', 'system_surface_dim_dark'].every(r => byRes[r] === '0xFF000000'), JSON.stringify(byRes));
  check('   the five containers are tones 4, 6, 8, 11, 14 of a neutral grey and surface_bright tone 18, as the page\'s own psHex makes them (' + greys.join(' ') + ')', ['system_surface_container_lowest_dark', 'system_surface_container_low_dark', 'system_surface_container_dark', 'system_surface_container_high_dark', 'system_surface_container_highest_dark', 'system_surface_bright_dark'].every((r, i) => byRes[r] === '0xFF' + greys[i]), JSON.stringify([greys, byRes]));
  check('   the two _900 entries come first (the phone is asked to look the first four up afterwards)', plan.entries[0].resource === 'android:color/system_neutral1_900' && plan.entries[1].resource === 'android:color/system_neutral2_900' && Object.keys(byRes).length === 11);
  check('   every value is 0xAARRGGBB and every resource android:color/system_*', plan.entries.every(e => /^0x[0-9A-F]{8}$/.test(e.argb) && /^android:color\/system_[a-z0-9_]+$/.test(e.resource)));
  check('   the answer: a toast, the overlay remembered as written with its id (kv ovl_black_applied), the Undo bar for the whole change', (await toast()) === 'Pure black overlay written' && JSON.stringify(((await kv('ovl_black_applied')) || {}).ids) === '["' + AAM + '"]' && (await snack()) === 'Theme applied' && (await undoShown()), JSON.stringify([await toast(), await snack(), await kv('ovl_black_applied')]));
  check('   the overlay list the phone sent back is kept: the overlay is listed on', await ev(() => !!ovlList && ovlList.some(o => o.id === 'com.android.shell:aam_monet' && o.state === 1)));
  c = await card();
  check('   the card says it is on the phone; the button is usable again', c.note === 'The pure black overlay is written on this phone.' && c.sw.on && (await ev(() => document.getElementById('ovlApplyBtn').disabled)) === false, c.note);
  await sleep(1100);
  const watchFrom = await ev(() => { window.ovlWatchPalette = window.__realWatch; return window.__watchFrom; });
  check('   the colours in use are watched after the write: the overlay\'s answer starts the watch itself (then the theme\'s finish), and the palette is read', watchFrom[0] === 'ovlRootDone' && watchFrom.includes('finish') && (await calls()).palette >= 1, JSON.stringify(watchFrom));

  // 4) Undo: the overlay off first, then the theme
  await reset();
  await undo();
  k = await calls();
  check('4. Undo switches the overlay off first (a disable plan with the remembered id), then puts the theme back', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && k.restore.length === 1 && k.restoreUndo[0] === true, JSON.stringify([k.fab, k.restore]));
  check('   the overlay is forgotten (kv cleared) and listed off; no new Undo', (await kv('ovl_black_applied')) === null && (await ev(() => window.__ovl.find(x => x.id === 'com.android.shell:aam_monet').state)) === 0 && (await snack()) === '' && (await toast()) === 'Theme put back', JSON.stringify([await kv('ovl_black_applied'), await toast()]));

  // 5) the switch off after a write: Apply removes it
  await reset(); await apply();
  check('5. a second Apply writes it again (the same plan) and remembers it', (await calls()).fab.length === 1 && (await calls()).fab[0].action === 'fabricate' && (await calls()).fab[0].entries.length === 11 && !!(await kv('ovl_black_applied')));
  await page.locator('#ovlBlackSw').click(); await sleep(80);
  c = await card();
  check('   the switch off while it is written: the hint says Apply removes it, the card still says it is on the phone', c.rootHint === 'Also removes the pure black overlay.' && c.note === 'The pure black overlay is written on this phone.', JSON.stringify([c.rootHint, c.note]));
  await reset(); await apply();
  k = await calls();
  check('   Apply sends the disable plan with the remembered id; the overlay is forgotten, the toast says so', k.fab.length === 1 && k.fab[0].action === 'disable' && JSON.stringify(k.fab[0].ids) === '["' + AAM + '"]' && (await kv('ovl_black_applied')) === null && (await toast()) === 'Pure black overlay removed' && (await snack()) === 'Theme applied', JSON.stringify([k.fab, await toast()]));
  c = await card();
  check('   now nothing follows the theme: the hint is plain, the line gone', c.rootHint === '' && c.note === '' && !c.sw.on, JSON.stringify(c));
  await reset(); await apply();
  check('   Apply with the switch off and nothing written does not call the bridge at all', (await calls()).fab.length === 0 && (await calls()).apply.length === 1);

  // 6) Android 13: one overlay per colour
  await ev(() => { window.__sdk = 33; ovlReadPalette(); kvSet('ovl_black', true); ovlRender(); }); await reset();
  await apply();
  k = await calls();
  check('6. on Android 13 the plan has the two _900 entries only (no role resources there), both black', k.fab.length === 1 && k.fab[0].entries.length === 2 && k.fab[0].entries.every(e => e.argb === '0xFF000000') && k.fab[0].entries.map(e => e.resource).join() === 'android:color/system_neutral1_900,android:color/system_neutral2_900', JSON.stringify(k.fab[0]));
  check('   the phone answers with one overlay per colour and both are remembered', JSON.stringify(((await kv('ovl_black_applied')) || {}).ids) === '["' + AAM + '_neutral1_900","' + AAM + '_neutral2_900"]', JSON.stringify(await kv('ovl_black_applied')));

  // 7) Revert (the Default button)
  await reset(); dialogs.length = 0;
  await page.locator('#ovlResetBtn').click(); await settle(); await sleep(100);
  k = await calls();
  check('7. Revert asks first, naming the overlay; confirmed, it resets the theme and then switches both overlays off', dialogs.length === 1 && /pure black overlay is switched off too/.test(dialogs[0]) && k.restore.length === 1 && k.restore[0] === '' && k.fab.length === 1 && k.fab[0].action === 'disable' && k.fab[0].ids.length === 2, JSON.stringify([dialogs[0], k.fab]));
  check('   both are forgotten and listed off; the switch stays as the person set it (on); the bar offers the old theme', (await kv('ovl_black_applied')) === null && (await ev(() => window.__ovl.filter(o => o.id.startsWith('com.android.shell:aam_monet')).every(o => o.state === 0))) && (await kv('ovl_black')) === true && (await snack()) === 'Theme reset to the default', JSON.stringify([await kv('ovl_black_applied'), await snack()]));
  await reset();
  await undo();
  check('   its Undo puts the theme back and writes no overlay', (await calls()).restore.length === 1 && (await calls()).fab.length === 0 && (await kv('ovl_black_applied')) === null);
  dialogs.length = 0; answer = false; await reset();
  await page.locator('#ovlResetBtn').click(); await sleep(200);
  check('   with nothing written the question does not mention the overlay; Cancel changes nothing', dialogs.length === 1 && !/pure black/.test(dialogs[0]) && (await calls()).restore.length === 0 && (await calls()).fab.length === 0, dialogs[0]);
  answer = true;

  // 8) failures
  await ev(() => { window.__sdk = 34; ovlReadPalette(); window.__fabDeny = 'Android did not take the colours'; }); await reset();
  await apply();
  let s = await sheet();
  check('8. refused colours: a sheet with the reason, the advice and what Android reports for each colour; a toast', s.shown && s.title === 'Could not write the pure black overlay' && /Monet · root overlay/.test(s.body) && /did not take the colours/.test(s.body) && /kept its own colours/.test(s.body) && /What Android reports for each color:/.test(s.body) && /android:color\/system_neutral1_900 → #ff1b1b1b/.test(s.body) && (await toast()) === 'The pure black overlay was not written', JSON.stringify(s));
  check('   the overlay is listed on all the same, so it stays remembered for Revert and Undo; the theme bar still offers Undo', JSON.stringify(((await kv('ovl_black_applied')) || {}).ids) === '["' + AAM + '"]' && (await snack()) === 'Theme applied' && (await undoShown()));
  await closeAll();
  await ev(() => { window.__fabDeny = null; window.__fabNeedsRoot = true; kvSet('ovl_black_applied', null); }); await reset();
  await apply();
  s = await sheet();
  check('   the Java gate saying no: the sheet says Needs Super User and how to get it; nothing remembered', s.shown && /Needs Super User/.test(s.body) && /Choose Root in Working Modes/.test(s.body) && !/Wireless Debugging/.test(s.body) && (await kv('ovl_black_applied')) === null, JSON.stringify(s));
  await closeAll();
  const t0 = await ev(() => { const t = Date.now() - 5000; kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t }); return t; }); await reset();
  await apply();
  check('   the same with an overlay written before: the record made before the write is put back as it was (written, not "may be")', JSON.stringify(await kv('ovl_black_applied')) === JSON.stringify({ ids: [AAM], t: t0 }) && (await card()).note === 'The pure black overlay is written on this phone.', JSON.stringify(await kv('ovl_black_applied')));
  await closeAll();
  await ev(() => { kvSet('ovl_black_applied', null); });
  await ev(() => { window.__fabNeedsRoot = false; window.__fabUnknown = true; }); await reset();
  await apply();
  s = await sheet();
  check('   no answer from the shell: the sheet says the phone may still apply it, and the overlay is remembered as possibly written (the card says so)', s.shown && /may still apply it/.test(s.body) && JSON.stringify(((await kv('ovl_black_applied')) || {}).ids) === '["' + AAM + '"]' && ((await kv('ovl_black_applied')) || {}).pending === true && (await card()).note === 'The pure black overlay may be written on this phone.', JSON.stringify(s));
  await closeAll();
  await ev(() => { window.__fabUnknown = false; window.__themeDeny = 'not allowed to write secure settings'; kvSet('ovl_black_applied', null); }); await reset();
  await apply();
  check('   when the theme setting itself is refused, the overlay is not written either', (await calls()).apply.length === 1 && (await calls()).fab.length === 0 && (await sheet()).shown && /Theme change refused/.test((await sheet()).title));
  await closeAll();
  await ev(() => { window.__themeDeny = null; });
  await sleep(2600);                                            // the "no answer" above re-reads the list after 2.5 s: let that pass first
  // no answer at all: the bridge takes the request and never replies, so the page's own timeout (60 s) ends it; a stand-in setTimeout
  // shortens that one timer to 300 ms for this check, and the page's own callback does the rest
  await ev(() => { kvSet('ovl_black_applied', null); window.__fabNoAnswer = true; window.__realST = window.setTimeout; window.setTimeout = function (fn, ms, ...a) { return window.__realST.call(window, fn, ms === 60000 ? 300 : ms, ...a); }; }); await reset();
  await apply();
  s = await sheet(); c = await card();
  const noAns = await ev(() => { window.setTimeout = window.__realST; window.__fabNoAnswer = false; window.__ovlCalls.list = 0; return { applied: kvGet('ovl_black_applied', null), pending: sdbReqs.size }; });
  check('   no answer at all: after the page\'s own timeout the sheet says No answer from the device (timed out) and that the phone may still apply it', (await calls()).fab.length === 1 && s.shown && s.title === 'Could not write the pure black overlay' && /No answer from the device \(timed out\)/.test(s.body) && /may still apply it/.test(s.body) && (await toast()) === 'The pure black overlay was not written' && noAns.pending === 0, JSON.stringify(s));
  check('   the overlay is remembered as possibly written, so Undo, Revert and Apply with the switch off still switch it off; the theme bar offers Undo', JSON.stringify((noAns.applied || {}).ids) === '["' + AAM + '"]' && (noAns.applied || {}).pending === true && c.note === 'The pure black overlay may be written on this phone.' && (await snack()) === 'Theme applied' && (await undoShown()), JSON.stringify([noAns.applied, c.note]));
  await closeAll();
  await sleep(2600);
  check('   and the overlay list is read again shortly (after 2.5 s)', (await calls()).list === 1, String((await calls()).list));
  // a disable whose overlays Android does not list at all (the write never ran: the app restarted first): the Java side calls it a
  // refusal, but nothing of this app's is on, so the record goes and the next Apply does not try again
  await ev(id => { kvSet('ovl_black_applied', { ids: [id], t: Date.now(), pending: true }); ovlRootDone({ req: 1, op: 'disable', ok: false, state: -2, ids: [id], error: 'That overlay is not listed any more (' + id + ')', list: [['android.theme.customization.accent_color', 'android', 1], [id + '_other', 'android', 1]] }, 'disable', null, null); }, AAM);
  s = await sheet();
  check('   a disable of an overlay Android does not list: the sheet says so, and the overlay is forgotten (nothing of it is on)', s.shown && s.title === 'Could not remove the pure black overlay' && /not listed any more/.test(s.body) && (await kv('ovl_black_applied')) === null && (await card()).note === '', JSON.stringify(s));
  await closeAll();
  await ev(id => { kvSet('ovl_black_applied', { ids: [id], t: Date.now() }); ovlRootDone({ req: 1, op: 'disable', ok: false, unknown: true, ids: [id], error: 'No answer from the device' }, 'disable', null, null); }, AAM);
  check('   a disable without an answer keeps the overlay remembered (it may still be on)', JSON.stringify(((await kv('ovl_black_applied')) || {}).ids) === '["' + AAM + '"]');
  await closeAll(); await sleep(2600);                         // its list re-read passes before the late answer below counts them
  await ev(() => { kvSet('ovl_black_applied', null); });
  await ev(() => { window.__ovlCalls.list = 0; window.onOverlayFab(JSON.stringify({ req: 999999, op: 'fabricate', ok: true, ids: ['com.android.shell:aam_monet'] })); });
  await sleep(200);
  check('   an answer that comes after the page gave up on it is not taken for a live one', (await calls()).list === 0 && errors.length === 0);
  await sleep(2600);
  check('   but makes the list be read again, quietly', (await calls()).list === 1, String((await calls()).list));

  // 9) the lock follows the working mode
  await setRoot(false);
  c = await card();
  check('9. the working mode leaving Root locks the row again while the tab is open', c.locked && c.lock === 'Needs Super User' && !c.sw && c.rootHint === '' && c.note === 'Shizuku and ADB cannot write these colors; Root can.', JSON.stringify(c));
  await reset(); await apply();
  check('   and Apply calls the bridge no more, although the switch is on', (await calls()).fab.length === 0 && (await calls()).apply.length === 1);
  await setRoot(true);
  c = await card();
  check('   Root back: unlocked again, the switch as it was', !c.locked && c.sw && c.sw.on && c.rootHint === 'Also writes the pure black overlay with root.', JSON.stringify(c));

  // 10) an app without the bridge
  await ev(() => { window.__fabSaved = window.AndroidBridge.overlayFabricate; delete window.AndroidBridge.overlayFabricate; ovlRender(); });
  c = await card();
  check('10. an app without overlayFabricate (older APK): the row is locked with "Update the app", and the line says so', c.locked && c.lock === 'Update the app' && c.lockBtn && c.lockBtn.disabled && c.lockBtn.text === 'Locked' && !c.sw && c.note === 'This version of the app cannot write color overlays. Update it, then come back.' && c.rootHint === '', JSON.stringify(c));
  await reset(); await apply();
  check('   Apply writes the theme and nothing else; the rest of the tab is unchanged (ovlBridgeOk)', (await calls()).apply.length === 1 && (await calls()).fab.length === 0 && (await ev(() => ovlBridgeOk())) && (await snack()) === 'Theme applied');
  await ev(() => { window.AndroidBridge.overlayFabricate = window.__fabSaved; ovlRender(); });
  check('    with the bridge back the switch is back', !!(await card()).sw);

  // 11) Android 11
  await ev(() => { window.__sdk = 28; ovlReadPalette(); ovlRender(); });
  check('11. below Android 12 nothing renders: the card is hidden with the rest of Material You, and empty', await ev(() => getComputedStyle(document.getElementById('ovlRootCard')).display === 'none' && document.getElementById('ovlRootCards').innerHTML === '' && document.getElementById('ovlRootNote').innerText === ''));
  check('    and the hint under Apply does not promise the overlay', (await card()).rootHint === '' && /needs Android 12/.test((await card()).hint));
  await ev(() => { window.__sdk = 34; ovlReadPalette(); ovlRender(); });
  check('    back on Android 14 it is there again', (await card()).shown && !!(await card()).sw);

  // 12) a restart (Android repaints every app after a theme change) with the overlay written: the switch, the line and the Undo come back
  await ev(() => { kvSet('ovl_black_applied', { ids: ['com.android.shell:aam_monet'], t: Date.now() }); kvSet('ovl_resume', { t: Date.now(), sub: 'theme', undo: '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT"}' }); });
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(kvs => { window.__kvInit = JSON.parse(kvs); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.addInitScript(() => { window.__root = true; });
  await p2.goto(URL); await p2.waitForTimeout(1900);
  const r2 = await p2.evaluate(() => ({ view: currentViewName(), snack: document.getElementById('sdbSnack').classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : '', on: !!document.getElementById('ovlBlackSw') && document.getElementById('ovlBlackSw').classList.contains('on'), note: document.getElementById('ovlRootNote').innerText }));
  check('12. after a restart the switch is still on, the card still says the overlay is on the phone, and the Undo bar is back', r2.view === 'overlays' && r2.on && r2.note === 'The pure black overlay is written on this phone.' && r2.snack === 'Theme changed', JSON.stringify(r2));
  await p2.evaluate(() => { window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; document.getElementById('sdbSnackUndo').click(); }); await settle(p2); await p2.waitForTimeout(100);
  const r2b = await p2.evaluate(() => ({ fab: window.__ovlCalls.fab, restore: window.__ovlCalls.restore, applied: kvGet('ovl_black_applied', null) }));
  check('    its Undo switches the overlay off and then puts the old theme back', r2b.fab.length === 1 && r2b.fab[0].action === 'disable' && r2b.restore.length === 1 && /home_wallpaper/.test(r2b.restore[0]) && r2b.applied === null, JSON.stringify(r2b));
  await p2.close();

  // 13) a restart before the overlay's answer: Android repaints every app when the overlay comes on, so the answer may never reach the page.
  //     The overlay was remembered (as possibly written) before the write went out, so the restart's Undo still switches it off
  const p3 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p3.on('pageerror', e => errors.push(e.message));
  await p3.addInitScript(() => { window.__kvInit = { ovl_black: 'true' }; });
  await p3.addInitScript(sdb.initScript); await p3.addInitScript(ovl.initScript);
  await p3.addInitScript(() => { window.__root = true; window.__fabNoAnswer = true; });
  await p3.goto(URL); await p3.waitForTimeout(500);
  await p3.evaluate(() => switchView('overlays')); await p3.waitForTimeout(400);
  await p3.locator('#ovlApplyBtn').click();
  await p3.waitForFunction(() => window.__ovlCalls.fab.length === 1, null, { timeout: 8000 }).catch(() => {});
  const mid = await p3.evaluate(() => ({ fab: window.__ovlCalls.fab.length, busy: ovlBusy, applied: kvGet('ovl_black_applied', null), resume: kvGet('ovl_resume', null), note: document.getElementById('ovlRootNote').innerText }));
  check('13. the overlay\'s write is out and unanswered: it is already remembered as possibly written, and the theme\'s Undo record is kept', mid.fab === 1 && mid.busy && JSON.stringify((mid.applied || {}).ids) === '["' + AAM + '"]' && mid.applied.pending === true && !!mid.resume && typeof mid.resume.undo === 'string' && mid.resume.done === true && mid.note === 'The pure black overlay may be written on this phone.', JSON.stringify(mid));
  const saved3 = await p3.evaluate(() => JSON.stringify(window.AndroidBridge.__kv));
  await p3.close();                                              // the restart, before the answer
  const p4 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p4.on('pageerror', e => errors.push(e.message));
  await p4.addInitScript(kvs => { window.__kvInit = JSON.parse(kvs); }, saved3);
  await p4.addInitScript(sdb.initScript); await p4.addInitScript(ovl.initScript);
  await p4.addInitScript(() => { window.__root = true; });
  await p4.goto(URL); await p4.waitForTimeout(1900);
  const r4 = await p4.evaluate(() => ({ view: currentViewName(), snack: document.getElementById('sdbSnack').classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : '', on: !!document.getElementById('ovlBlackSw') && document.getElementById('ovlBlackSw').classList.contains('on'), note: document.getElementById('ovlRootNote').innerText }));
  check('    after the restart: back on the tab with the Undo bar (Theme changed); the card says the overlay may be on the phone', r4.view === 'overlays' && r4.snack === 'Theme changed' && r4.on && r4.note === 'The pure black overlay may be written on this phone.', JSON.stringify(r4));
  await p4.evaluate(() => { window.__ovlCalls.fab.length = 0; window.__ovlCalls.restore.length = 0; });
  await undo(p4);
  const r4b = await p4.evaluate(() => ({ fab: window.__ovlCalls.fab, restore: window.__ovlCalls.restore.length, applied: kvGet('ovl_black_applied', null), note: document.getElementById('ovlRootNote').innerText }));
  check('    its Undo switches the overlay off first (the disable plan with the remembered id), then puts the old theme back; nothing is remembered any more', r4b.fab.length === 1 && r4b.fab[0].action === 'disable' && JSON.stringify(r4b.fab[0].ids) === '["' + AAM + '"]' && r4b.restore === 1 && r4b.applied === null && r4b.note === '', JSON.stringify(r4b));
  await p4.close();

  // 14) layout at 320 px, unlocked and locked, dark and light
  for (const theme of ['dark', 'light']) {
    const p = await b.newPage({ viewport: { width: 320, height: 700 } });
    p.on('pageerror', e => errors.push(e.message));
    await p.addInitScript(t => { window.__kvInit = { ui_theme: JSON.stringify({ appearance: t }), ovl_black: 'true', ovl_black_applied: JSON.stringify({ ids: ['com.android.shell:aam_monet'], t: Date.now() }) }; }, theme);
    await p.addInitScript(sdb.initScript); await p.addInitScript(ovl.initScript);
    await p.addInitScript(() => { window.__root = true; });
    await p.goto(URL); await p.waitForTimeout(400);
    await p.evaluate(() => { document.documentElement.setAttribute('data-theme', 'x'); document.documentElement.removeAttribute('data-theme'); switchView('overlays'); });
    await p.waitForTimeout(500);
    const geo = () => p.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, bad: Array.from(document.querySelectorAll('#ovlRootCard *')).filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.right > window.innerWidth + 1; }).map(e => e.tagName + '.' + e.className).slice(0, 5), swH: (document.getElementById('ovlBlackSw') || document.querySelector('#ovlRootCards .op-mode-btn')).getBoundingClientRect().height, on: !!document.getElementById('ovlBlackSw'), note: document.getElementById('ovlRootNote').innerText }));
    let g = await geo();
    check('14. ' + theme + ' 320px, unlocked: nothing sticks out sideways, the switch is drawn, the line says it is on the phone', g.sw <= g.cw && g.bad.length === 0 && g.on && g.swH >= 24 && /on this phone/.test(g.note), JSON.stringify(g));
    await p.evaluate(() => { window.__root = false; checkAllWorkingModes(false); }); await p.waitForTimeout(200);
    g = await geo();
    check('    ' + theme + ' 320px, locked: either, with the Locked button at least 36 px tall', g.sw <= g.cw && g.bad.length === 0 && !g.on && g.swH >= 36, JSON.stringify(g));
    if (theme === 'dark') await p.screenshot({ path: 'ovl_root_locked.png', fullPage: true });
    await p.close();
  }

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
