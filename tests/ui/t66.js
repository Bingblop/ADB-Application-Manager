// Overlays tab, second pass (the lessons of the Settings tab's reviews): touch press and hold with the row redrawn, answers that come late
// or never, keyboard focus, refresh failures, disabled looks, the sheet and Back with the preset list.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 360, height: 800 }, hasTouch: true });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript); await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(400);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const ops = () => ev(() => window.__ovlCalls.op.slice());
  const reset = () => ev(() => { window.__ovlCalls.op.length = 0; window.__ovlCalls.list = 0; window.__ovlCalls.apply.length = 0; window.__ovlCalls.restore.length = 0; });
  const stateOf = id => ev(i => { const o = window.__ovl.find(x => x.id === i); return o ? o.state : null; }, id);
  const rowOn = id => ev(i => { const r = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).find(x => x.dataset.id === i); const s = r && r.querySelector('.sdb-sw'); return s ? s.classList.contains('on') : null; }, id);
  const touch = async (x, y, ms) => {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await sleep(ms);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await sleep(350);
  };
  const find = async text => { await ev(t => { const i = document.getElementById('ovlSearch'); i.value = t; ovlSearchInput(); }, text); await sleep(320); };

  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); ovlSetSub('list'); }); await sleep(700);

  // 1) touch: a hold flips the overlay once, whatever its length, though the row is redrawn under the finger
  const target = 'overlay.launcher3.n01';
  for (const ms of [520, 600, 690, 800, 1000, 1500]) {
    await closeAll(); await find(target); await reset();
    const before = await rowOn(target);
    const box = await page.locator('#ovlRows .ovl-row[data-id="' + target + '"]').first().boundingBox();
    await touch(box.x + 24, box.y + box.height / 2, ms);
    const o = await ops();
    const sheet = await ev(() => document.getElementById('ovlDetailModal').classList.contains('show'));
    check('1. touch hold ' + ms + ' ms on the row: one change (' + o.length + '), no sheet', o.length === 1 && !sheet && (await rowOn(target)) === !before, JSON.stringify({ o: o.length, sheet }));
  }
  for (const ms of [700, 1200]) {
    await closeAll(); await find(target); await reset();
    const sb = await page.locator('#ovlRows .ovl-row[data-id="' + target + '"] .sdb-sw').first().boundingBox();
    await touch(sb.x + sb.width / 2, sb.y + sb.height / 2, ms);
    check('   touch hold ' + ms + ' ms on the switch: one change, not two', (await ops()).length === 1);
  }
  await closeAll(); await find(target); await reset();
  const tb = await page.locator('#ovlRows .ovl-row[data-id="' + target + '"]').first().boundingBox();
  await touch(tb.x + 24, tb.y + tb.height / 2, 60);
  check('   a short tap opens the sheet and changes nothing', (await ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))) && (await ops()).length === 0);
  await closeAll();

  // 2) the page gives up after a while, the phone answers later: the list is read again and shows the truth
  await ev(() => { window.__sdbRequest0 = sdbRequest; sdbRequest = (s, c, t) => window.__sdbRequest0(s, c, 300); window.__delay = 900; });
  await find('navbar'); await reset();
  const gestBefore = await rowOn('com.android.internal.systemui.navbar.threebutton');
  await ev(() => ovlToggle('com.android.internal.systemui.navbar.threebutton')); await sleep(500);
  check('2. no answer in time: a sheet says the phone may still apply it', await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /may still apply/.test(document.getElementById('commandResultsList').innerText)));
  await closeAll();
  await ev(() => { window.__delay = 20; });
  await sleep(1100 + 2900);
  check('   the late answer makes the page read the list again (list reads: ' + (await ev(() => window.__ovlCalls.list)) + ')', (await ev(() => window.__ovlCalls.list)) >= 1);
  check('   and the row shows what the phone holds now', (await rowOn('com.android.internal.systemui.navbar.threebutton')) === !gestBefore);
  await ev(() => { sdbRequest = window.__sdbRequest0; });
  // an unknown outcome (the link dropped)
  await find('navbar'); await reset();
  const g0 = await rowOn('com.android.internal.systemui.navbar.gestural');
  await ev(() => { window.__ovlUnknown = { 'com.android.internal.systemui.navbar.gestural': { apply: true } }; ovlToggle('com.android.internal.systemui.navbar.gestural'); }); await sleep(400);
  check('   an unknown outcome says the phone may still apply it', await ev(() => document.getElementById('commandResultsModal').classList.contains('show') && /may still apply/.test(document.getElementById('commandResultsList').innerText)));
  await closeAll(); await sleep(3000);
  check('   and the list is read again, so the row shows the real state', (await ev(() => window.__ovlCalls.list)) >= 1 && (await rowOn('com.android.internal.systemui.navbar.gestural')) === !g0);
  // the theme
  await ev(() => { ovlSetSub('theme'); }); await sleep(200);
  await ev(() => { window.__ovlCalls.restore.length = 0; delete window.__db.secure['theme_customization_overlay_packages']; ovlReadTheme(); }); await sleep(250);
  await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047'); await reset();
  await ev(() => { window.__themeUnknown = { apply: true }; }); await page.locator('#ovlApplyBtn').click(); await sleep(500);
  check('   a theme change with no answer: the page says it may still change', /may still change/.test(await toast()) && (await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))));
  await closeAll(); await sleep(3200);
  check('   and then reads the theme again, which now is the new one', /Custom color #43A047/.test(await ev(() => document.getElementById('ovlNow').innerText.replace(/\s+/g, ' '))), await ev(() => document.getElementById('ovlNow').innerText.replace(/\s+/g, ' ')));

  // 3) a refresh that fails while a list is shown says so
  await ev(() => ovlSetSub('list')); await sleep(300);
  await ev(() => { window.__ovlListError = 'cmd: Can\'t find service: overlay'; ovlReadList(); }); await sleep(300);
  check('3. the old list stays and a toast explains why it was not refreshed', /Could not refresh the overlays/.test(await toast()) && (await ev(() => ovlList.length)) === 131, await toast());
  await ev(() => { window.__ovlListError = null; ovlReadList(); }); await sleep(300);

  // 4) keyboard focus survives a redraw
  await find('navbar');
  await page.evaluate(() => document.querySelector('#ovlRows .ovl-row[data-id="com.android.internal.systemui.navbar.threebutton"] .sdb-sw').focus());
  await page.keyboard.press('Enter'); await sleep(350);
  const kept = await ev(() => { const a = document.activeElement; return a && a.closest && a.closest('.ovl-row') ? a.closest('.ovl-row').dataset.id : (a ? a.tagName : null); });
  check('4. a switch used from the keyboard keeps the focus on its row', kept === 'com.android.internal.systemui.navbar.threebutton', String(kept));
  await ev(() => ovlSetSub('theme')); await sleep(150);
  await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await sleep(80);
  await page.evaluate(() => document.querySelector('#ovlStyles button[data-style="RAINBOW"]').focus());
  await page.keyboard.press('Enter'); await sleep(100);
  check('   a style chosen from the keyboard keeps the focus', (await ev(() => document.activeElement.dataset.style)) === 'RAINBOW' && (await ev(() => ovlStyle)) === 'RAINBOW');
  await page.locator('#ovlPresetHead').click(); await sleep(100);
  await page.evaluate(() => document.querySelector('#ovlFamilies button[data-f="blue"]').focus());
  await page.keyboard.press('Enter'); await sleep(100);
  check('   a preset family chosen from the keyboard keeps the focus', (await ev(() => document.activeElement.dataset.f)) === 'blue');
  await page.locator('#ovlPresetHead').click(); await sleep(60);

  // 5) disabled buttons look disabled
  await ev(() => ovlSetSub('list')); await sleep(200); await find('gmsconfig');
  await page.locator('#ovlRows .ovl-row .sdb-key').first().click(); await sleep(450);
  check('5. the sheet of an unavailable overlay has both buttons off and looking it', (await ev(() => document.getElementById('ovlDetailOn').disabled && document.getElementById('ovlDetailOff').disabled)) && Number(await ev(() => getComputedStyle(document.getElementById('ovlDetailOn')).opacity)) < 0.6);
  await closeAll();

  // 6) the preset list and Back
  await ev(() => ovlSetSub('theme')); await sleep(150);
  await ev(() => { ovlPresetOpen = true; ovlRenderPresets(); });
  check('6. with the preset list open, Back closes it first', (await ev(() => backNavigate())) === true && (await ev(() => ovlPresetOpen)) === false && (await ev(() => currentViewName())) === 'overlays');

  // 7) switching tabs: the undo bar of an overlay change goes away on the way to another tab, but stays on the two tabs that make changes
  await ev(() => { ovlSetSub('list'); sdbSnack('x', () => {}); switchView('apps'); }); await sleep(60);
  check('7. leaving for an unrelated tab hides the undo bar', await ev(() => !document.getElementById('sdbSnack').classList.contains('show')));
  await ev(() => { switchView('overlays'); sdbSnack('y', () => {}); switchView('settings'); }); await sleep(60);
  check('   moving between Overlays and Settings keeps it', await ev(() => document.getElementById('sdbSnack').classList.contains('show')));
  await ev(() => { sdbSnackHide(); switchView('overlays'); });

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
