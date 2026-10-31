// v7.22.0 Overlays: "Add the ability to uninstall RRO's if SU, UID 0 or UID 1000 Shizuku is enabled" / "Use press and hold on an RRO that is
// disabled to initiate an uninstall prompt". A press and hold on a row that is off asks to uninstall it (the APK copied to Download/ADB App
// Manager/Overlays first), a hold on a row that is on still switches it off, the switch still turns a row on; at the shell (uid 2000) the hold only
// shows the lock toast (no prompt, no bridge call, the row is not switched on); the sheet's Uninstall button is locked at the shell, live with
// Shizuku as the system user, absent for a row that is on; a firmware overlay and a failed copy are refused with their reasons and the row stays;
// a cancelled prompt calls nothing.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const OFF = 'android.theme.customization.font', OFF2 = 'com.android.internal.systemui.navbar.threebutton', OFF3 = 'com.android.theme.icon_pack.circular.android', ON = 'android.theme.customization.accent_color';
const LOCK = 'Uninstalling an overlay needs Super User, or Shizuku as root (uid 0) or the system user (uid 1000)';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // a fresh page; post = a script that runs after the mocks (the privilege knobs)
  const mk = async (opts = {}) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 }, hasTouch: true });
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    page.on('dialog', d => d.accept());
    await page.addInitScript(sdb.initScript); await page.addInitScript(ovl.initScript);
    if (opts.post) await page.addInitScript(opts.post);
    await page.goto(PAGE); await page.waitForTimeout(450);
    return page;
  };
  const tools = page => ({
    ev: (fn, arg) => page.evaluate(fn, arg),
    sleep: ms => page.waitForTimeout(ms),
    toast: () => page.locator('#toastMsg').innerText(),
    calls: () => page.evaluate(() => ({ op: window.__ovlCalls.op, un: window.__ovlCalls.un })),
    rowIds: () => page.evaluate(() => Array.from(document.querySelectorAll('#ovlRows .ovl-row')).map(r => r.dataset.id)),
    rowState: id => page.evaluate(i => { const r = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).find(x => x.dataset.id === i); if (!r) return null; const s = r.querySelector('.sdb-sw'); return { on: s.classList.contains('on'), busy: r.classList.contains('busy') }; }, id),
    ask: () => page.evaluate(() => ({ shown: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').innerText, text: document.getElementById('mpAskText').innerText, ok: document.getElementById('mpAskOk').innerText })),
    results: () => page.evaluate(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText })),
    unBtn: () => page.evaluate(() => { const u = document.getElementById('ovlDetailUn'); return { shown: u.style.display !== 'none', disabled: u.disabled, locked: u.classList.contains('is-locked'), text: u.textContent, title: u.getAttribute('title') || '' }; }),
    closeAll: () => page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); }),
    open: async () => { await page.evaluate(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); ovlSetSub('list'); }); await page.waitForTimeout(700); },
    hold: async (id, ms = 650) => {
      const loc = page.locator('#ovlRows .ovl-row[data-id="' + id.replace(/"/g, '\\"') + '"]').first();
      await loc.scrollIntoViewIfNeeded();
      const box = await loc.boundingBox();
      await page.mouse.move(box.x + 24, box.y + box.height / 2); await page.mouse.down(); await page.waitForTimeout(ms); await page.mouse.up(); await page.waitForTimeout(150);
    }
  });

  // (a) with Super User: a hold on a row that is off opens the prompt; confirming uninstalls it
  {
    const page = await mk({ post: () => { window.__root = true; } }); const T = tools(page);
    await T.open();
    check('a. the page sees Super User (isRootActive) and the list is up', await T.ev(() => isRootActive() && ovlUsable() && !!ovlList), await T.ev(() => JSON.stringify(lastModesData)));
    const before = await T.rowState(OFF);
    check('   the row starts off', before && before.on === false, JSON.stringify(before));
    await T.hold(OFF);
    let a = await T.ask();
    check('   the hold opens the uninstall prompt, with the id in its title and the red Uninstall button', a.shown && a.title === 'Uninstall ' + OFF + '?' && a.ok === 'Uninstall', JSON.stringify(a));
    check('   the text says the APK is copied to Download/ADB App Manager/Overlays first and can be installed again from the Installer tab, and that the package is removed for the phone\'s current user', /copied to Download\/ADB App Manager\/Overlays first/.test(a.text) && /Installer tab/.test(a.text) && /removed for the phone's current user/.test(a.text) && /cannot be undone from this app/.test(a.text), a.text);
    check('   the hold did not switch the row on and did not open the sheet', (await T.rowState(OFF)).on === false && (await T.calls()).op.length === 0 && !(await T.ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))));
    await page.click('#mpAskOk'); await T.sleep(60);
    check('   while the answer is awaited the row is busy', (await T.rowState(OFF)) === null || (await T.rowState(OFF)).busy === true);
    await T.sleep(400);
    const c = await T.calls();
    check('   overlayUninstall was called once, with that id, and no switch was made', JSON.stringify(c.un) === JSON.stringify([OFF]) && c.op.length === 0, JSON.stringify(c));
    check('   the row has left the list', !(await T.rowIds()).includes(OFF) && (await T.ev(() => !ovlFind('android.theme.customization.font'))));
    check('   a toast names the uninstall', (await T.toast()) === 'Uninstalled: ' + OFF, await T.toast());
    check('   a line names where the copy went (the package\'s own folder under Download/ADB App Manager/Overlays)', await T.ev(() => document.getElementById('sdbSnack').classList.contains('show') && document.getElementById('sdbSnackMsg').innerText === 'A copy of its APK is in Download/ADB App Manager/Overlays/android.theme.customization.font' && document.getElementById('sdbSnackUndo').style.display === 'none'), await T.ev(() => document.getElementById('sdbSnackMsg').innerText));
    check('   no failure sheet', !(await T.results()).shown);
    await page.screenshot({ path: 'ovl_uninstall_done.png', fullPage: true });

    // (b) a hold on a row that is on still switches it off
    await T.hold(ON); await T.sleep(300);
    const c2 = await T.calls();
    check('b. a hold on a row that is on switches it off (overlayOp disable), with no uninstall call and no prompt', JSON.stringify(c2.op) === JSON.stringify([{ op: 'disable', id: ON }]) && c2.un.length === 1 && !(await T.ask()).shown && (await T.rowState(ON)).on === false, JSON.stringify(c2));
    // the switch still turns a row that is off on
    await page.locator('#ovlRows .ovl-row[data-id="' + OFF2 + '"] .sdb-sw').first().click(); await T.sleep(300);
    check('   a tap on the switch of a row that is off still turns it on', (await T.rowState(OFF2)).on === true && (await T.calls()).op.length === 2 && (await T.calls()).un.length === 1);
    // a tap on the row still opens the sheet
    await page.locator('#ovlRows .ovl-row[data-id="' + OFF3 + '"] .sdb-key').first().click(); await T.sleep(200);
    check('   a tap on the row still opens the sheet', await T.ev(() => document.getElementById('ovlDetailModal').classList.contains('show') && ovlDetailId === 'com.android.theme.icon_pack.circular.android'));
    const u = await T.unBtn();
    check('   with Super User the sheet of a row that is off has a live Uninstall button', u.shown && !u.disabled && !u.locked && u.text === 'Uninstall' && u.title === '', JSON.stringify(u));
    await page.click('#ovlDetailUn'); await T.sleep(100);
    a = await T.ask();
    check('   tapping it opens the same prompt', a.shown && a.title === 'Uninstall ' + OFF3 + '?', JSON.stringify(a));
    await page.click('#mpAskOk'); await T.sleep(400);
    check('   confirming uninstalls it and closes the sheet', (await T.calls()).un.length === 2 && !(await T.rowIds()).includes(OFF3) && !(await T.ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))));
    // the fabricated colour overlay (an id with a colon) is never a package
    await T.hold('com.android.systemui:fabricated_color'); await T.sleep(100);
    check('   a hold on the fabricated colour overlay (on) switches it off like any row that is on', (await T.calls()).op.filter(x => x.id === 'com.android.systemui:fabricated_color').length === 1);
    await T.hold('com.android.systemui:fabricated_color'); await T.sleep(100);
    check('   held again (off now) it says it is not a package, with no prompt and no bridge call', (await T.toast()) === 'This overlay is not an app package; switching it off is all there is to remove' && !(await T.ask()).shown && (await T.calls()).un.length === 2, await T.toast());
    await page.close();
  }

  // (c) at the plain shell (ADB TCP, uid 2000): the hold only shows the lock toast
  {
    const page = await mk(); const T = tools(page);
    await T.open();
    check('c. the shell: usable for switching, but not Super User', await T.ev(() => ovlUsable() && !isRootActive() && lastModesData.activeMode === 'adb_tcp'), await T.ev(() => JSON.stringify(lastModesData)));
    await T.hold(OFF); await T.sleep(100);
    check('   a hold on a row that is off shows the lock toast', (await T.toast()) === LOCK, await T.toast());
    check('   no prompt, no bridge call, and the row is NOT switched on', !(await T.ask()).shown && (await T.calls()).un.length === 0 && (await T.calls()).op.length === 0 && (await T.rowState(OFF)).on === false, JSON.stringify(await T.calls()));
    await T.hold(ON); await T.sleep(300);
    check('   a hold on a row that is on still switches it off at the shell', JSON.stringify((await T.calls()).op) === JSON.stringify([{ op: 'disable', id: ON }]) && (await T.rowState(ON)).on === false);

    // (d) the sheet: locked at the shell, absent for a row that is on, live with Shizuku as the system user
    await T.ev(id => ovlOpenDetail(id), OFF); await T.sleep(150);
    let u = await T.unBtn();
    check('d. the sheet of a row that is off at the shell: Uninstall present, disabled, locked, the lock glyph before its label, the reason as its title', u.shown && u.disabled && u.locked && u.text === '\u{1F512} Uninstall' && u.title === LOCK, JSON.stringify(u));
    await page.locator('#ovlDetailUn').click({ force: true }); await T.sleep(100);
    check('   a tap on the locked button does nothing', !(await T.ask()).shown && (await T.calls()).un.length === 0);
    check('   Switch on is still there and live', await T.ev(() => { const on = document.getElementById('ovlDetailOn'); return on.style.display !== 'none' && !on.disabled; }));
    await page.screenshot({ path: 'ovl_uninstall_locked.png', fullPage: true });
    await T.closeAll();
    await T.ev(id => ovlOpenDetail(id), OFF2); await T.sleep(150);        // OFF2 is still off on this page
    check('   another row that is off: locked too', (await T.unBtn()).locked);
    await T.closeAll();
    await T.ev(() => ovlOpenDetail('com.android.systemui.clocks.metro')); await T.sleep(150);      // a row that is on
    check('   the sheet of a row that is on has no Uninstall button', !(await T.unBtn()).shown);
    await T.closeAll();
    await T.ev(() => { window.__shizuku1000 = true; checkAllWorkingModes(false); }); await T.sleep(200);
    check('   Shizuku as the system user (uid 1000) opens the gate', await T.ev(() => isRootActive() && lastModesData.shizuku.uid === 1000));
    await T.ev(id => ovlOpenDetail(id), OFF); await T.sleep(150);
    u = await T.unBtn();
    check('   the Uninstall button is live there', u.shown && !u.disabled && !u.locked && u.text === 'Uninstall' && u.title === '', JSON.stringify(u));
    await T.closeAll();
    await T.hold(OFF); await T.sleep(100);
    check('   and the hold opens the prompt', (await T.ask()).shown && (await T.ask()).title === 'Uninstall ' + OFF + '?');

    // (g) cancelling the prompt calls nothing
    await page.click('#mpAskNo'); await T.sleep(200);
    check('g. Cancel: no bridge call, the row stays, off', (await T.calls()).un.length === 0 && (await T.rowIds()).includes(OFF) && (await T.rowState(OFF)).on === false && !(await T.ask()).shown, JSON.stringify(await T.calls()));
    // the list moved while the prompt was open (a re-read, a late answer, an Undo): the row is on by then, so OK does nothing but say so
    await T.hold(OFF);
    check('   the prompt is up again', (await T.ask()).shown);
    await T.ev(() => { ovlList = ovlList.map(o => o.id === 'android.theme.customization.font' ? Object.assign({}, o, { state: 1 }) : o); ovlListMemo = null; ovlRender(); });
    await page.click('#mpAskOk'); await T.sleep(300);
    check('   OK on a row that turned on meanwhile: no bridge call, the toast says to switch it off first', (await T.calls()).un.length === 0 && (await T.toast()) === 'It is on again; switch it off first', await T.toast());
    await T.ev(() => { ovlList = ovlList.map(o => o.id === 'android.theme.customization.font' ? Object.assign({}, o, { state: 0 }) : o); ovlListMemo = null; ovlRender(); });
    await T.hold(OFF);
    check('   the prompt is up once more', (await T.ask()).shown);
    await T.ev(() => { ovlList = ovlList.filter(o => o.id !== 'android.theme.customization.font'); ovlListMemo = null; ovlRender(); });
    await page.click('#mpAskOk'); await T.sleep(300);
    check('   OK on a row that left the list meanwhile: no bridge call, the toast says so', (await T.calls()).un.length === 0 && (await T.toast()) === 'That overlay is not listed any more', await T.toast());
    // the Java floor (mirrored by the mock): the phone's list says the overlay is on by now, so the job refuses it although the page asked
    await T.ev(() => { ovlList = ovlList.concat([{ id: 'android.theme.customization.font', target: 'android', state: 0 }]); ovlListMemo = null; ovlRender(); window.__ovl.find(x => x.id === 'android.theme.customization.font').state = 1; });
    await T.hold(OFF);
    await page.click('#mpAskOk'); await T.sleep(900);
    const rn = await T.results();
    check('   the phone lists it as on: the answer refuses in its words and the row stays', (await T.calls()).un.length === 1 && rn.shown && /is not a switched-off overlay on this phone\. Reload the list\./.test(rn.body) && (await T.rowIds()).includes(OFF), JSON.stringify(rn));
    await page.close();
  }

  // (e) a firmware overlay (under /product): the prompt confirms, the answer refuses, the row stays
  {
    const page = await mk({ post: () => { window.__root = true; window.__ovlPaths = { 'android.theme.customization.font': '/product/overlay/FontOverlay.apk' }; } }); const T = tools(page);
    await T.open();
    await T.hold(OFF);
    check('e. the prompt opens for a firmware overlay too (the page cannot know where the APK lives)', (await T.ask()).shown);
    await page.click('#mpAskOk'); await T.sleep(400);
    const rs = await T.results();
    check('   the firmware refusal is shown in the failure sheet, in its words', rs.shown && rs.title === 'Could not uninstall' && /is part of the phone's firmware, so it cannot be uninstalled\. Switching it off is the way to stop it\./.test(rs.body) && /read-only partition/.test(rs.body), JSON.stringify(rs));
    check('   the toast says it could not be uninstalled', /^Could not uninstall /.test(await T.toast()), await T.toast());
    check('   the row stays, off and not busy', (await T.rowIds()).includes(OFF) && JSON.stringify(await T.rowState(OFF)) === JSON.stringify({ on: false, busy: false }));
    check('   one bridge call went out', (await T.calls()).un.length === 1);
    await T.closeAll();

    // (f) a copy that cannot be saved: nothing is removed
    await T.ev(() => { window.__ovlNoBackup = true; });
    await T.hold(OFF2); await page.click('#mpAskOk'); await T.sleep(400);
    const rf = await T.results();
    check('f. a failed copy: "No copy of the APK could be saved" is shown and nothing was removed', rf.shown && /No copy of the APK could be saved to Download\/ADB App Manager\/Overlays, so nothing was removed/.test(rf.body), JSON.stringify(rf));
    check('   the row stays', (await T.rowIds()).includes(OFF2) && (await T.rowState(OFF2)).on === false);
    await T.closeAll();
    // a refusal by pm uninstall itself
    await T.ev(() => { window.__ovlNoBackup = false; window.__ovlUninstallDeny = { 'com.android.theme.icon_pack.circular.android': 'DELETE_FAILED_INTERNAL_ERROR' }; });
    await T.hold(OFF3); await page.click('#mpAskOk'); await T.sleep(400);
    check('   a refusal by Android: shown with its text, the row stays', (await T.results()).shown && /DELETE_FAILED_INTERNAL_ERROR/.test((await T.results()).body) && (await T.rowIds()).includes(OFF3));
    await T.closeAll();
    // the Java gate says no although the page thought yes (the mode changed under it): the reason in the gate's words, not the switch's
    await T.ev(() => { window.__ovlUninstallDeny = {}; });
    await T.hold(OFF3);
    await T.ev(() => { window.__root = false; });
    await page.click('#mpAskOk'); await T.sleep(400);
    const rg = await T.results();
    check('   the gate closing under the request: the sheet names Super User or Shizuku as root or the system user, not the working modes of the switch', rg.shown && /Super User, or Shizuku as root \(uid 0\) or the system user \(uid 1000\)/.test(rg.body) && !/Needs ADB, Wireless Debugging/.test(rg.body), JSON.stringify(rg));
    await T.closeAll();
    await page.close();
  }

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
