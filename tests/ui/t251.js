// v7.12.12 Extra Settings: Power & reboot, Doze & power governor, Zram & memory. Six power tiles each open the confirm sheet with its own words, the command and what to expect;
// Reboot, Soft reboot and Restart SystemUI confirm with a tap, Recovery, Power off and Bootloader only with a full press-and-hold (a short press does nothing); nothing runs
// without the confirm; the exact commands sent in ADB, Shizuku and Root mode; Soft reboot is locked outside Root; the 10-second watchdog says when nothing happened (and
// shows what the command printed); Restart SystemUI is read back by process id. Doze: the phase from real dumpsys deviceidle output (three phones), the motion line, Force /
// Step / Wake / Simulate motion with the read-back, the profile marked from the read-back only, an Android without the fixed-performance command. Zram: mm_stat / disksize /
// meminfo parsed, Not available with the reason, the before/after change, auto-refresh only while the tab is on the screen, Reclaim / Drop locked outside Root, Trim through
// the Settings page's own path. Read-Only and no bridge: everything locked with the reason. Layout at 320 / 360 / 412 / 768 px, four colour schemes, reduced motion.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, TESTS } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const xqMock = require('./lib/xq_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const FX = path.join(TESTS, 'java', 'fixtures', 'powertweaks');
const rd = f => fs.readFileSync(path.join(FX, f), 'utf8');
const fixtures = { mm_stat: rd('mm_stat.txt'), mm_stat_old: rd('mm_stat-old.txt'), disksize: rd('disksize.txt'), meminfo: rd('meminfo.txt'), help11: rd('cmd-power-help-11.txt'), help10: rd('cmd-power-help-10.txt'), helpNone: rd('cmd-power-none.txt'),
  dump_active: rd('deviceidle-active.txt'), dump_forced: rd('deviceidle-forced-idle.txt'), dump_old: rd('deviceidle-old-pending.txt') };

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 }, reducedMotion: opts.reduce ? 'reduce' : 'no-preference' });
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    if (opts.bridge !== false) { await page.addInitScript(mock.initScript); await page.addInitScript(xqMock.initScript, fixtures); }
    else await page.addInitScript(() => { window.AndroidBridge = undefined; });
    await page.addInitScript(m => { if (window.__mode) Object.assign(window.__mode, m); }, { priv: opts.priv !== false, mode: opts.mode || 'adb_tcp' });
    if (opts.init) await page.addInitScript(opts.init);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => { XQ_REBOOT_WATCH_MS = 600; XQ_SYSUI_WAIT_MS = 120; XZR_REFRESH_MS = 3600000; });        // the zram timer is tested on its own page (13.)
    return page;
  };
  const page = await mkPage({ mode: 'adb_tcp' });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = sel => ev(s => { const e = document.querySelector(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; }, sel);
  const open = async () => { await ev(() => switchView('extrasettings')); await sleep(400); };
  const calls = () => ev(() => window.__xq.calls.map(c => c.bare));
  const clearCalls = () => ev(() => { window.__xq.calls.length = 0; window.__calls.op.length = 0; });
  const ops = () => ev(() => window.__calls.op.map(o => o.op + ' ' + o.key + (o.op === 'put' ? '=' + o.value : '')));
  const sheet = () => ev(() => { const s = document.getElementById('xqSheet'); if (!s || !s.classList.contains('show')) return null; const q = i => document.getElementById(i); const vis = i => getComputedStyle(q(i)).display !== 'none'; return { title: q('xqSheetTitle').innerText, sub: q('xqSheetSub').innerText, text: q('xqSheetText').innerText, cmd: q('xqSheetCmd').innerText, mode: q('xqSheetMode').innerText, expect: q('xqSheetExpect').innerText.trim(), result: q('xqSheetResult').innerText.replace(/\s+/g, ' ').trim(), resultCls: q('xqSheetResult').className, ok: vis('xqSheetOk'), hold: vis('xqSheetHold'), okLabel: q('xqSheetOkT').innerText, holdLabel: q('xqSheetHoldT').innerText, okCls: q('xqSheetOk').className, holdCls: q('xqSheetHold').className, cancel: q('xqSheetCancelT').innerText, okDisabled: q('xqSheetOk').disabled, holdDisabled: q('xqSheetHold').disabled }; });
  const tile = id => ev(i => { const t = document.getElementById(i); return { disabled: t.disabled, why: t.querySelector('.xpw-why').innerText.replace(/\s+/g, ' ').trim(), title: t.querySelector('b').innerText, sub: t.querySelector('.xpw-sub').innerText, hold: !!t.querySelector('.xpw-hold'), tip: t.title }; }, id);
  const tap = async id => { await ev(i => document.getElementById(i).click(), id); await sleep(80); };
  const confirmTap = async () => { await ev(() => document.getElementById('xqSheetOk').click()); await sleep(80); };
  const holdFor = async ms => { const hb = await page.locator('#xqSheetHold').boundingBox(); await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2); await page.mouse.down(); await sleep(ms); await page.mouse.up(); await sleep(60); };
  const closeSheet = async () => { await ev(() => xqSheetClose()); await sleep(40); };

  // ---- 1. the three sections sit at the end of the tab, each its own section with the shared head ----
  await open();
  const shell = await ev(() => {
    const root = document.getElementById('extraSettingsRoot');
    const kids = [...root.children].map(e => e.id);
    const head = id => { const s = document.getElementById(id); return s.tagName === 'SECTION' && s.classList.contains('xs-sec') && !!s.querySelector('.xs-sec-head .xs-ico') && !!s.querySelector('.xs-sec-line .color-card-title') && !!s.querySelector('.xs-pill') && !!s.querySelector('.xs-trust') && !!s.querySelector('details.xs-more summary'); };
    return { kids, heads: ['extraPowerSection', 'extraDozeSection', 'extraZramSection'].map(head), titles: ['extraPowerSection', 'extraDozeSection', 'extraZramSection'].map(id => document.querySelector('#' + id + ' .xs-sec-line .color-card-title').innerText), chips: [...document.querySelectorAll('#extraSettingsChips .xs-chip')].map(c => c.id + ':' + c.dataset.target + ':' + getComputedStyle(c).display), marker: /<!-- extra: sections A end -->/.test(document.getElementById('view-extrasettings').innerHTML) };
  });
  check('1. the tab holds the three sections after Private DNS, in the order Power, Doze, Zram, each a <section class="xs-sec"> with the shared head (icon tile, title, one pill, the trust line, a What does this do? note)', shell.kids.slice(-3).join() === 'extraPowerSection,extraDozeSection,extraZramSection' && shell.kids.indexOf('extraDnsSection') < shell.kids.indexOf('extraPowerSection') && shell.heads.every(Boolean) && shell.titles.join('|') === 'Power & reboot|Doze & power governor|Zram & memory', JSON.stringify(shell));
  check('   two live chips in the header, Doze and RAM saved, scroll to their sections', shell.chips.some(c => /^xsChipDoze:extraDozeSection:(inline-)?flex$/.test(c)) && shell.chips.some(c => /^xsChipZram:extraZramSection:(inline-)?flex$/.test(c)), JSON.stringify(shell.chips));
  check('   the placeholder comment for the sibling sections sits after the DNS section', shell.marker);
  const scrolled = await ev(() => { const y0 = (document.scrollingElement || document.documentElement).scrollTop; const r0 = document.getElementById('extraZramSection').getBoundingClientRect().top; document.getElementById('xsChipZram').click(); return new Promise(res => setTimeout(() => res({ moved: document.getElementById('extraZramSection').getBoundingClientRect().top < r0 - 50 || (document.scrollingElement || document.documentElement).scrollTop > y0 + 50 }), 700)); });
  check('   tapping the RAM saved chip scrolls the tab to the Zram section', scrolled.moved, JSON.stringify(scrolled));
  await ev(() => (document.scrollingElement || document.documentElement).scrollTop = 0);

  // ---- 2. what was read when the tab opened: the reads only, no action ----
  const c0 = await calls();
  check('2. opening the tab reads Doze (get deep / light / force and the grep of the report), the zram counters (mm_stat, disksize, meminfo), the cmd power help and the dumpsys power fixed line: nothing else', c0.length === 4 && c0[0].startsWith("dumpsys deviceidle get deep; echo __XQ_SEP__; dumpsys deviceidle get light; echo __XQ_SEP__; dumpsys deviceidle get force; echo __XQ_SEP__; dumpsys deviceidle | grep -E 'm(UseMotionSensor|MotionListener\\.active|MotionSensor|State|LightState|ForceIdle)=' | head -n 12") && c0[1] === 'cat /sys/block/zram0/mm_stat; echo __XQ_SEP__; cat /sys/block/zram0/disksize; echo __XQ_SEP__; cat /proc/meminfo' && c0[2] === 'cmd power help' && c0[3] === "dumpsys power | grep -iE 'fixed.?performance' | head -n 3", JSON.stringify(c0));
  check('   every command carries its own exit-status marker and an id of its own', await ev(() => window.__xq.calls.every(c => /; echo "__XQRC_xq\d+__:\$\?"$/.test(c.cmd)) && new Set(window.__xq.calls.map(c => c.id)).size === window.__xq.calls.length));
  check('   the low_power setting is read through the Hidden Settings path (settingsOp get global low_power), not the shell (the other reads are Private DNS\'s own)', (await ops()).includes('get low_power') && !(await ops()).some(o => /^put/.test(o)) && !c0.some(c => /low_power/.test(c)), JSON.stringify(await ops()));

  // ---- 3. Power & reboot: the tiles ----
  const tiles = {};
  for (const id of ['xpwReboot', 'xpwSoft', 'xpwRecovery', 'xpwPoweroff', 'xpwBootloader', 'xpwSysui']) tiles[id] = await tile(id);
  check('3. six tiles with a bold title and a one-line subtitle in plain words', tiles.xpwReboot.title === 'Reboot' && tiles.xpwReboot.sub === 'Restarts the phone' && tiles.xpwSoft.title === 'Soft reboot' && tiles.xpwSoft.sub === 'Restarts Android only; open apps close' && tiles.xpwRecovery.sub === 'Restarts into recovery mode' && tiles.xpwPoweroff.title === 'Power off' && tiles.xpwPoweroff.sub === 'Switches the phone off' && tiles.xpwBootloader.sub === 'Restarts into fastboot mode' && tiles.xpwSysui.title === 'Restart SystemUI' && tiles.xpwSysui.sub === 'Redraws the status bar and launcher; the phone stays on', JSON.stringify(tiles));
  check('   in ADB mode Soft reboot is locked and says "Needs Root mode" (never a silent failure); the other five are live', tiles.xpwSoft.disabled && /Needs Root mode$/.test(tiles.xpwSoft.why) && tiles.xpwSoft.tip === 'Only Root may restart Android this way' && ['xpwReboot', 'xpwRecovery', 'xpwPoweroff', 'xpwBootloader', 'xpwSysui'].every(i => !tiles[i].disabled && tiles[i].why === ''), JSON.stringify(tiles.xpwSoft));
  check('   Recovery, Power off and Bootloader carry the "Hold to confirm" hint; Power off is tinted red, Recovery and Bootloader amber', tiles.xpwRecovery.hold && tiles.xpwPoweroff.hold && tiles.xpwBootloader.hold && !tiles.xpwReboot.hold && !tiles.xpwSysui.hold && (await ev(() => document.getElementById('xpwPoweroff').classList.contains('is-danger') && document.getElementById('xpwRecovery').classList.contains('is-warn') && document.getElementById('xpwBootloader').classList.contains('is-warn') && !document.getElementById('xpwReboot').classList.contains('is-warn'))));
  check('   the pill names the working mode', (await txt('#xpwPill')) === '● ADB TCP');
  // every tile's sheet
  const sheets = {};
  for (const [id, name] of [['xpwReboot', 'Reboot'], ['xpwRecovery', 'Recovery'], ['xpwPoweroff', 'Power off'], ['xpwBootloader', 'Bootloader'], ['xpwSysui', 'Restart SystemUI']]) { await tap(id); sheets[name] = await sheet(); await closeSheet(); }
  check('4. a tap opens the sheet, with the title, what will happen, the exact command and the mode it runs through, and what to expect; nothing was sent', !!sheets.Reboot && sheets.Reboot.title === 'Reboot' && sheets.Reboot.sub === 'Changes the phone right away' && /^The phone restarts now, the same as Restart in the power menu\. Open apps close and unsaved work in them is lost\./.test(sheets.Reboot.text) && sheets.Reboot.cmd === 'reboot' && sheets.Reboot.mode === 'Through the working mode: ADB TCP' && /^The screen goes dark and the phone restarts within a few seconds\. If nothing happens within 10 seconds, the mode could not do it, and the sheet says so\.$/.test(sheets.Reboot.expect) && (await calls()).length === 4, JSON.stringify(sheets.Reboot));
  check('   Reboot and Restart SystemUI confirm with a tap (a Confirm button named after the action); Recovery, Power off and Bootloader show only "Hold to confirm"', sheets.Reboot.ok && !sheets.Reboot.hold && sheets.Reboot.okLabel === 'Reboot' && sheets['Restart SystemUI'].ok && !sheets['Restart SystemUI'].hold && !sheets.Recovery.ok && sheets.Recovery.hold && sheets.Recovery.holdLabel === 'Hold to confirm' && !sheets['Power off'].ok && sheets['Power off'].hold && !sheets.Bootloader.ok && sheets.Bootloader.hold, JSON.stringify([sheets.Recovery.ok, sheets.Recovery.hold, sheets['Power off'].hold]));
  check('   Power off is the red button, Recovery and Bootloader amber, Reboot the gradient', /is-danger/.test(sheets['Power off'].holdCls) && /is-warn/.test(sheets.Recovery.holdCls) && /is-warn/.test(sheets.Bootloader.holdCls) && /is-primary/.test(sheets.Reboot.okCls));
  check('   each sheet says its own thing: Recovery names the recovery menu and warns about Wipe; Power off says how to switch on again; Bootloader names fastboot and START; Restart SystemUI says the phone stays on and the process id is checked', /recovery mode/.test(sheets.Recovery.text) && /Wipe/.test(sheets.Recovery.text) && /Hold the power button to switch it on again/.test(sheets['Power off'].text) && sheets['Power off'].cmd === 'reboot -p || svc power shutdown' && /fastboot/.test(sheets.Bootloader.text) && /START/.test(sheets.Bootloader.text) && sheets.Bootloader.cmd === 'reboot bootloader' && sheets.Recovery.cmd === 'reboot recovery' && /the phone does not restart/.test(sheets['Restart SystemUI'].text) && sheets['Restart SystemUI'].cmd === 'am crash com.android.systemui' && /process id before and after/.test(sheets['Restart SystemUI'].expect), JSON.stringify([sheets.Recovery.text, sheets['Power off'].text]));
  check('   the sheet is the app\'s bottom sheet (modal-overlay + modal-sheet, a dialog with a Close button and Cancel)', await ev(() => { const s = document.getElementById('xqSheet'); return s.classList.contains('modal-overlay') && !!s.querySelector('.modal-sheet[role="dialog"]') && !!s.querySelector('#xqSheetX[role="button"]') && document.getElementById('xqSheetCancelT').innerText === 'Cancel'; }));
  // the hold: a short press does nothing, a full one confirms
  await tap('xpwPoweroff');
  await holdFor(300);
  const afterShort = await sheet();
  check('5. a short press (0.3 s) on "Hold to confirm" does nothing: the sheet is still waiting, nothing was sent', !!afterShort && !afterShort.result && !afterShort.holdDisabled && (await calls()).length === 4 && /xq-hold/.test(afterShort.holdCls) && !/is-done/.test(afterShort.holdCls), JSON.stringify(afterShort));
  await ev(() => document.getElementById('xqSheetHold').click()); await sleep(60);
  check('   a click on the hold button does nothing either', (await calls()).length === 4 && !(await sheet()).result);
  const midRing = await ev(() => new Promise(res => { const h = document.getElementById('xqSheetHold'); h.dispatchEvent(new PointerEvent('pointerdown', { button: 0, pointerId: 1, pointerType: 'touch', bubbles: true })); setTimeout(() => { const p = parseFloat(getComputedStyle(h).getPropertyValue('--p')); const holding = h.classList.contains('is-holding'); h.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1, bubbles: true })); setTimeout(() => res({ p, holding, after: parseFloat(getComputedStyle(h).getPropertyValue('--p')), still: h.classList.contains('is-holding') }), 30); }, 500); }));
  check('   while the finger is down the ring fills (about 40 % after 0.5 s) and letting go before the end empties it again', midRing.holding && midRing.p > 0.25 && midRing.p < 0.7 && midRing.after === 0 && !midRing.still, midRing.holding && midRing.p > 0.25 && midRing.p < 0.7 && midRing.after === 0 && !midRing.still ? undefined : JSON.stringify(midRing));
  await holdFor(1450);
  const afterHold = await sheet();
  const c5 = await calls();
  check('   a full hold (1.2 s) confirms: the command "reboot -p || svc power shutdown" is sent, exactly once, the sheet says it was sent and what to expect', c5.length === 5 && c5[4] === 'reboot -p || svc power shutdown' && !!afterHold && /^Power off: sent\. The screen goes dark and the phone restarts within a few seconds\./.test(afterHold.result) && afterHold.holdDisabled && afterHold.cancel === 'Close', JSON.stringify([c5.slice(4), afterHold && afterHold.result]));
  await page.waitForFunction(() => /Nothing happened/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 4000 });
  const watch = await sheet();
  check('   the watchdog: the page is still here after the wait, so the sheet says "Nothing happened within 10 seconds, although the command reported no error: the mode could not do it." and shows what the command printed (nothing)', /^Nothing happened within 10 seconds, although the command reported no error: the mode could not do it\. \(the command printed nothing\)$/.test(watch.result) && /is-bad/.test(watch.resultCls) && (await txt('#xpwStatus')) === watch.result.replace(' (the command printed nothing)', ''), JSON.stringify(watch));
  await closeSheet();
  // a command that fails at once
  await ev(() => { window.__xq.reply = bare => bare === 'reboot recovery' ? { out: 'reboot: Operation not permitted\n', rc: 1 } : undefined; });
  await tap('xpwRecovery'); await holdFor(1450);
  await page.waitForFunction(() => /did not work/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 3000 });
  const failed = await sheet();
  check('   a command the mode refuses (exit 1) is reported at once with its words: "It did not work: the command reported an error. reboot: Operation not permitted"', failed.result === 'It did not work: the command reported an error. reboot: Operation not permitted' && (await calls()).slice(-1)[0] === 'reboot recovery' && /^It did not work/.test(await txt('#xpwStatus')), failed.result);
  await closeSheet();
  await ev(() => { window.__xq.reply = bare => bare === 'reboot' ? { raw: 'Error: Wireless Debugging is not configured. Connect it in Working Modes.' } : undefined; });
  await tap('xpwReboot'); await confirmTap();
  await page.waitForFunction(() => /did not work/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 3000 });
  check('   the bridge\'s own Error: line (no marker at all) is a failure too, with its words', /^It did not work: Error: Wireless Debugging is not configured/.test((await sheet()).result));
  await closeSheet();
  await ev(() => { window.__xq.reply = null; });
  // Restart SystemUI: read back by process id
  await clearCalls();
  await tap('xpwSysui'); await confirmTap();
  await page.waitForFunction(() => /System UI/.test(document.getElementById('xqSheetResult').innerText) && !/Restarting/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 4000 });
  const ui = await sheet(), cUi = await calls();
  check('6. Restart SystemUI: pidof before, "am crash com.android.systemui" (the renderer switch\'s own command), pidof after; the sheet says the process changed', cUi.join('|') === 'pidof com.android.systemui|am crash com.android.systemui|pidof com.android.systemui' && ui.result === 'System UI restarted: process 2210 became 2211.' && /is-ok/.test(ui.resultCls) && (await txt('#xpwStatus')) === ui.result, JSON.stringify([cUi, ui.result]));
  await closeSheet();
  await ev(() => { window.__phone.sysuiRestarts = false; });
  await tap('xpwSysui'); await confirmTap();
  await page.waitForFunction(() => /System UI did not restart/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 4000 });
  check('   when the process id is the same afterwards the sheet says so instead of "done"', /^System UI did not restart: still process 2211\./.test((await sheet()).result));
  await closeSheet();
  await ev(() => { window.__phone.sysuiRestarts = true; window.__xq.reply = bare => bare === 'pidof com.android.systemui' ? { out: '', rc: 1 } : undefined; });
  await tap('xpwSysui'); await confirmTap();
  await page.waitForFunction(() => /process id could not be read/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 4000 });
  check('   with no pidof the sheet says the restart is not confirmed', (await sheet()).result === 'The command ran, but the process id could not be read, so the restart is not confirmed.');
  await closeSheet(); await ev(() => { window.__xq.reply = null; });
  // nothing without the confirm: the tiles never send anything on their own
  await clearCalls();
  for (const id of ['xpwReboot', 'xpwRecovery', 'xpwPoweroff', 'xpwBootloader', 'xpwSysui']) { await tap(id); await closeSheet(); }
  check('   opening and closing every sheet sends nothing: nothing ever runs without the confirm', (await calls()).length === 0 && (await ops()).length === 0);
  // Esc / Cancel
  await tap('xpwBootloader'); await ev(() => document.getElementById('xqSheetCancel').click()); await sleep(60);
  check('   Cancel closes the sheet and sends nothing', (await sheet()) === null && (await calls()).length === 0);

  // ---- 7. Doze: the timeline from real reports ----
  const doze = () => ev(() => ({ now: document.getElementById('xdzNow').innerText.replace(/\s+/g, ' ').trim(), cur: [...document.querySelectorAll('#xdzLine .xdz-ph')].map(l => l.classList.contains('is-now') ? '*' : l.classList.contains('is-past') ? '-' : '.').join(''), sub: [...document.querySelectorAll('#xdzLine .xdz-sub')].map(s => s.innerText).join(','), light: [...document.querySelectorAll('#xdzLight .xdz-lc')].filter(c => c.classList.contains('is-now')).map(c => c.dataset.ls).join(), motion: document.getElementById('xdzMotionT').innerText, pill: document.getElementById('xdzPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipDoze').innerText.replace(/\s+/g, ' ').trim(), fill: document.getElementById('xdzFill').style.width, status: document.getElementById('xdzStatus').innerText, prof: document.getElementById('xdzProfNow').innerText, sel: [...document.querySelectorAll('.xdz-prof')].filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.dataset.prof).join(), perfWhy: document.getElementById('xdzProfPerfWhy').innerText, perfDisabled: document.getElementById('xdzProfPerf').disabled }));
  const d1 = await doze();
  check('7. a Pixel-like report, awake: "Deep Doze: Active · Light Doze: Active", the first phase lit, the token under it, the light chip Active, the pill and chip say Active', d1.now === 'Deep Doze: Active · Light Doze: Active ACTIVE / ACTIVE' && d1.cur === '*....' && d1.sub === 'ACTIVE,,,,' && d1.light === 'ACTIVE' && d1.pill === '● Active' && d1.chip === 'Doze Active' && d1.fill === '0px', JSON.stringify(d1));
  check('   the motion line from the report: Doze uses the motion sensor, not listening now, the sensor\'s name', d1.motion === 'Doze uses the motion sensor · not listening now · LSM6DSO Accelerometer -Wakeup', d1.motion);
  const parsed = await ev(f => ({
    a: xdzParseDump(f.dump_active), fo: xdzParseDump(f.dump_forced), o: xdzParseDump(f.dump_old),
    tok: [xdzToken('IDLE_PENDING\n', 'deep'), xdzToken('Stepped to deep: SENSING\n', 'deep'), xdzToken('Light state: OVERRIDE, Deep state: IDLE\n', 'light'), xdzToken('Light state: OVERRIDE, Deep state: IDLE\n', 'deep'), xdzToken('Now forced in to deep idle mode', 'deep'), xdzToken('UNKNOWN', 'deep'), xdzToken('PRE_IDLE', 'deep'), xdzToken('QUICK_DOZE_DELAY', 'deep')],
    ph: ['ACTIVE', 'INACTIVE', 'QUICK_DOZE_DELAY', 'IDLE_PENDING', 'SENSING', 'LOCATING', 'IDLE', 'IDLE_MAINTENANCE', 'OVERRIDE', null].map(xdzPhase),
  }), fixtures);
  check('   the parser on the three reports: states, light states, forced, motion (an old phone without the motion lines reads as unknown, mMotionSensor=null as no sensor)', parsed.a.state === 'ACTIVE' && parsed.a.lightState === 'ACTIVE' && parsed.a.forced === false && parsed.a.useMotion === true && parsed.a.listening === false && parsed.a.sensor === 'LSM6DSO Accelerometer -Wakeup' && parsed.fo.state === 'IDLE' && parsed.fo.lightState === 'OVERRIDE' && parsed.fo.forced === true && parsed.fo.listening === true && parsed.o.state === 'IDLE_PENDING' && parsed.o.lightState === 'IDLE' && parsed.o.useMotion === null && parsed.o.listening === null && parsed.o.sensor === '', JSON.stringify(parsed));
  check('   the tokens of get / step / unforce; UNKNOWN, a light token and a forced line give nothing; the eight deep tokens map to the five phases', parsed.tok.join() === 'IDLE_PENDING,SENSING,OVERRIDE,IDLE,,,,QUICK_DOZE_DELAY' && parsed.ph.join() === '0,1,1,2,2,2,3,4,-1,-1', JSON.stringify([parsed.tok, parsed.ph]));
  // a forced phone, as read
  await ev(() => { window.__phone.deep = 'IDLE'; window.__phone.light = 'OVERRIDE'; window.__phone.force = 'true'; window.__phone.dump = 'forced'; });
  await ev(() => document.getElementById('xdzRefresh').click()); await page.waitForFunction(() => !xdzReading, null, { timeout: 4000 }); await sleep(50);
  const d2 = await doze();
  check('   a phone forced into deep idle: Idle (deep) lit with the phases before it marked, "forced", light Override, the motion listener on', d2.now === 'Deep Doze: Idle (deep) · Light Doze: Override · forced IDLE / OVERRIDE' && d2.cur === '---*.' && d2.light === 'OVERRIDE' && d2.pill === '● Idle (deep)' && d2.chip === 'Doze Idle (deep)' && d2.fill === '60%' && /listening now · BMI260/.test(d2.motion), JSON.stringify(d2));
  await ev(() => { window.__phone.deep = 'SENSING'; window.__phone.light = 'IDLE'; window.__phone.force = 'false'; window.__phone.dump = 'old'; });
  await ev(() => document.getElementById('xdzRefresh').click()); await page.waitForFunction(() => !xdzReading, null, { timeout: 4000 }); await sleep(50);
  const d3 = await doze();
  check('   an older Samsung-like phone in SENSING: shown as Idle pending with the real token under it; the motion line says the state is unknown (no sensor printed)', d3.now === 'Deep Doze: Idle pending · Light Doze: Idle SENSING / IDLE' && d3.cur === '--*..' && d3.sub === ',,SENSING,,' && /State unknown/.test(d3.motion) === false && d3.motion === 'no sensor', JSON.stringify(d3));
  await ev(() => { window.__xq.reply = bare => bare.startsWith('dumpsys deviceidle get deep;') ? { out: 'Unknown command: get\n__XQ_SEP__\nUnknown command: get\n__XQ_SEP__\n\n__XQ_SEP__\n', rc: 0 } : undefined; });
  await ev(() => document.getElementById('xdzRefresh').click()); await page.waitForFunction(() => !xdzReading, null, { timeout: 4000 }); await sleep(50);
  const d4 = await doze();
  check('   a phone whose dumpsys does not know "get": the section says it could not read Doze, with the phone\'s words, no phase lit, the pill says Not read', d4.now === 'Could not read Doze: Unknown command: get' && d4.cur === '.....' && d4.light === '' && d4.pill === '● Not read' && d4.chip === 'Doze Not read' && /State unknown until the phone is read/.test(d4.motion), JSON.stringify(d4));
  await ev(() => { window.__xq.reply = null; window.__phone.deep = 'ACTIVE'; window.__phone.light = 'ACTIVE'; window.__phone.force = 'false'; window.__phone.dump = 'active'; });
  await ev(() => document.getElementById('xdzRefresh').click()); await page.waitForFunction(() => !xdzReading, null, { timeout: 4000 }); await sleep(50);

  // ---- 8. Doze actions with the read-back ----
  const act = async (id, name) => { await clearCalls(); await tap(id); const s = await sheet(); await confirmTap(); await page.waitForFunction(() => !xdzBusy, null, { timeout: 5000 }); await sleep(60); return { sheet: s, after: await sheet(), calls: (await calls()).filter(c => !c.startsWith('cat /sys/block/zram0')), doze: await doze() }; };
  const f1 = await act('xdzForce');
  check('8. Force deep Doze: the sheet explains, names "dumpsys deviceidle force-idle deep" and that the phase is read back; Confirm sends it, then the read; the result is the read-back (Idle (deep), forced) with the command\'s words under it', f1.sheet.title === 'Force deep Doze' && /^Puts the phone into deep Doze right now/.test(f1.sheet.text) && f1.sheet.cmd === 'dumpsys deviceidle force-idle deep' && f1.sheet.okLabel === 'Force deep Doze' && f1.calls[0] === 'dumpsys deviceidle force-idle deep' && f1.calls[1].startsWith('dumpsys deviceidle get deep;') && f1.calls.length === 2 && f1.after.result === 'Done, and read back from the phone. Deep Doze: Idle (deep) · Light Doze: Override · forced Now forced in to deep idle mode' && /is-ok/.test(f1.after.resultCls) && f1.doze.cur === '---*.' && /^Done, and read back/.test(f1.doze.status), JSON.stringify([f1.calls, f1.after && f1.after.result, f1.doze.cur]));
  await closeSheet();
  const w1 = await act('xdzWake');
  check('   Wake device: "dumpsys deviceidle unforce" only (never disable), read back to Active', w1.sheet.cmd === 'dumpsys deviceidle unforce' && w1.calls[0] === 'dumpsys deviceidle unforce' && !w1.calls.some(c => /disable/.test(c)) && w1.doze.cur === '*....' && /Deep Doze: Active/.test(w1.after.result), JSON.stringify([w1.calls, w1.after.result]));
  await closeSheet();
  const s1 = await act('xdzStep');
  check('   Step to next phase: "dumpsys deviceidle step deep", the phone answers "Stepped to deep: INACTIVE", the timeline moves to Inactive', s1.sheet.cmd === 'dumpsys deviceidle step deep' && s1.calls[0] === 'dumpsys deviceidle step deep' && s1.doze.cur === '-*...' && /Inactive/.test(s1.after.result) && /Stepped to deep: INACTIVE/.test(s1.after.result), JSON.stringify([s1.calls, s1.after.result]));
  await closeSheet();
  const m1 = await act('xdzMotion');
  check('   Simulate motion: "dumpsys deviceidle motion", one sentence (a Doze that is not forced ends), the phase read back (Active again)', m1.sheet.cmd === 'dumpsys deviceidle motion' && /picked up/.test(m1.sheet.text) && m1.calls[0] === 'dumpsys deviceidle motion' && m1.doze.cur === '*....', JSON.stringify([m1.calls, m1.doze.cur]));
  await closeSheet();
  await ev(() => { window.__xq.reply = bare => bare === 'dumpsys deviceidle force-idle deep' ? { out: 'Unable to go deep idle; not enabled\n', rc: 255 } : undefined; });
  const f2 = await act('xdzForce');
  check('   a phone that refuses ("Unable to go deep idle; not enabled"): the sheet says it did not work with those words and the phase it really is in', /^It did not work: Unable to go deep idle; not enabled Deep Doze: Active/.test(f2.after.result) && /is-bad/.test(f2.after.resultCls) && f2.doze.cur === '*....', f2.after.result);
  await closeSheet(); await ev(() => { window.__xq.reply = null; });
  check('   the motion sensor has no switch: the section says what the report says and offers Simulate motion, nothing more', await ev(() => !document.querySelector('#xdzMotionBox input[type="checkbox"]') && document.querySelector('#xdzMotionBox b').innerText === 'Motion sensor'));

  // ---- 9. power profiles from the read-back ----
  const p0 = await doze();
  check('9. the marked profile comes from the read-back: low_power 0 and no fixed-performance line = Balanced; the line says battery saver off and that the fixed state is not reported', p0.sel === 'balanced' && p0.prof === 'Battery saver: off · Fixed performance mode: not reported by this phone' && !p0.perfDisabled && p0.perfWhy === '', JSON.stringify(p0));
  await clearCalls();
  await tap('xdzProfSave'); const sv = await sheet();
  check('   the Ultra saver sheet: what it changes, "settings put global low_power 1" after turning fixed performance off, the note that the marked card comes from the read-back', sv.title === 'Ultra saver' && /battery saver on/.test(sv.text) && sv.cmd === 'cmd power set-fixed-performance-mode-enabled false\nsettings put global low_power 1' && sv.okLabel === 'Apply Ultra saver' && /marked card comes from reading the phone back/.test(sv.expect), JSON.stringify(sv));
  await confirmTap(); await page.waitForFunction(() => !xdzBusy, null, { timeout: 5000 }); await sleep(60);
  const sv2 = await doze();
  check('   Apply: the cmd through the shell, low_power through the settings path, then the read-back; Ultra saver is marked and the sheet says applied and read back', (await calls()).slice(0, 1).join() === 'cmd power set-fixed-performance-mode-enabled false' && (await ops()).join() === 'put low_power=1,get low_power' && sv2.sel === 'saver' && sv2.prof.startsWith('Battery saver: on') && (await sheet()).result === 'Applied and read back from the phone: Ultra saver.', JSON.stringify([await calls(), await ops(), sv2.sel]));
  await closeSheet();
  await ev(() => { window.__revert['global/low_power'] = '1'; });
  await clearCalls(); await tap('xdzProfBal'); await confirmTap(); await page.waitForFunction(() => !xdzBusy, null, { timeout: 5000 }); await sleep(60);
  const bl = await doze();
  check('   a phone that keeps low_power 1 (Samsung may): the sheet says the step failed and what the phone reports; Ultra saver stays marked, not Balanced', bl.sel === 'saver' && /^Could not apply Balanced: settings put global low_power 0 failed: Android did not keep the new value The phone now reports: Ultra saver\.$/.test((await sheet()).result), (await sheet()).result);
  await closeSheet(); await ev(() => { delete window.__revert['global/low_power']; window.__db.global.low_power = '0'; });
  await clearCalls(); await tap('xdzProfPerf'); const pf = await sheet(); await confirmTap(); await page.waitForFunction(() => !xdzBusy, null, { timeout: 5000 }); await sleep(60);
  const pf2 = await doze();
  check('   Performance: "cmd power set-fixed-performance-mode-enabled true" then low_power 0; the phone does not report the fixed state, so the sheet says so honestly and Balanced stays marked (never the tapped card)', pf.cmd === 'cmd power set-fixed-performance-mode-enabled true\nsettings put global low_power 0' && (await calls())[0] === 'cmd power set-fixed-performance-mode-enabled true' && pf2.sel === 'balanced' && (await sheet()).result === 'Applied. The phone reports battery saver off, but it does not report whether fixed performance mode is on, so the Balanced card stays marked.', JSON.stringify([pf2.sel, (await sheet()).result]));
  await closeSheet();
  await ev(() => { window.__phone.power = 'POWER MANAGER (dumpsys power)\n  mFixedPerformanceModeEnabled=true\n'; });
  await ev(() => document.getElementById('xdzRefresh').click()); await page.waitForFunction(() => !xdzReading, null, { timeout: 4000 }); await sleep(50);
  const pf3 = await doze();
  check('   a phone that does print the fixed state (on): Performance is marked from the read-back and the line says so', pf3.sel === 'performance' && pf3.prof === 'Battery saver: off · Fixed performance mode: on', JSON.stringify(pf3));
  await ev(() => { window.__phone.power = 'POWER MANAGER (dumpsys power)\n'; });
  await clearCalls(); await tap('xdzProfBal'); const bs = await sheet(); await confirmTap(); await page.waitForFunction(() => !xdzBusy, null, { timeout: 5000 }); await sleep(60);
  check('   Balanced: fixed performance off, low_power 0, adaptive saver on (the help knows it); marked after the read-back', bs.cmd === 'cmd power set-fixed-performance-mode-enabled false\nsettings put global low_power 0\ncmd power set-adaptive-power-saver-enabled true' && (await calls()).filter(c => /^cmd power set/.test(c)).join('|') === 'cmd power set-fixed-performance-mode-enabled false|cmd power set-adaptive-power-saver-enabled true' && (await doze()).sel === 'balanced' && (await sheet()).result === 'Applied and read back from the phone: Balanced.', JSON.stringify([bs.cmd, await calls()]));
  await closeSheet();
  check('   the parser of cmd power: Android 11 knows both commands, Android 10 only the adaptive one, "Can\'t find service" is no help at all', await ev(f => { const a = xdzParseHelp(f.help11), b = xdzParseHelp(f.help10), c = xdzParseHelp(f.helpNone); return a.fixed && a.adaptive && !b.fixed && b.adaptive && c === null && xdzParseFixed('  mFixedPerformanceModeEnabled=false') === false && xdzParseFixed('mWakefulness=Awake') === null; }, fixtures));
  await page.close();

  // ---- 10. an Android without the fixed-performance command, in Root mode: Soft reboot live, Performance locked ----
  const pr = await mkPage({ mode: 'root', init: () => { window.__phoneHelp = 'old'; } });
  await pr.evaluate(f => { window.__phone.help = f; }, fixtures.help10);
  await pr.evaluate(() => switchView('extrasettings')); await pr.waitForFunction(() => !xdzReading && xdz.read, null, { timeout: 5000 }); await pr.waitForTimeout(100);
  const rootState = await pr.evaluate(() => ({ soft: document.getElementById('xpwSoft').disabled, softWhy: document.getElementById('xpwSoft').querySelector('.xpw-why').innerText, pill: document.getElementById('xpwPill').innerText.replace(/\s+/g, ' ').trim(), perf: document.getElementById('xdzProfPerf').disabled, perfWhy: document.getElementById('xdzProfPerfWhy').innerText, prof: document.getElementById('xdzProfNow').innerText, reclaim: document.getElementById('xzrReclaim').disabled, drop: document.getElementById('xzrDrop').disabled, trim: document.getElementById('xzrTrim').disabled, whys: [...document.querySelectorAll('#xzrCard .xzr-why')].map(w => w.innerText).join('|') }));
  check('10. Root mode: Soft reboot is live, the pill says Root, Reclaim and Drop are live; an Android 10 "cmd power": the Performance card is locked with "Not on this Android version" and the line says so', !rootState.soft && rootState.softWhy === '' && rootState.pill === '● Root' && rootState.perf && rootState.perfWhy === 'Not on this Android version' && /this Android has no fixed performance command/.test(rootState.prof) && !rootState.reclaim && !rootState.drop && !rootState.trim && rootState.whys === '||', JSON.stringify(rootState));
  await pr.evaluate(() => document.getElementById('xpwSoft').click()); await pr.waitForTimeout(80);
  const softSheet = await pr.evaluate(() => ({ title: document.getElementById('xqSheetTitle').innerText, cmd: document.getElementById('xqSheetCmd').innerText, mode: document.getElementById('xqSheetMode').innerText, expect: document.getElementById('xqSheetExpect').innerText.trim(), tap: getComputedStyle(document.getElementById('xqSheetOk')).display !== 'none' }));
  check('   Soft reboot in Root: "setprop ctl.restart zygote" through Root, a tap confirms, what to expect says the screen goes black', softSheet.title === 'Soft reboot' && softSheet.cmd === 'setprop ctl.restart zygote' && softSheet.mode === 'Through the working mode: Root' && /^The screen goes black within a few seconds/.test(softSheet.expect) && softSheet.tap, JSON.stringify(softSheet));
  await pr.evaluate(() => { window.__xq.calls.length = 0; document.getElementById('xqSheetOk').click(); }); await pr.waitForTimeout(100);
  check('   confirmed: the command is sent, once', (await pr.evaluate(() => window.__xq.calls.map(c => c.bare))).join() === 'setprop ctl.restart zygote');
  await pr.evaluate(() => xqSheetClose());
  await pr.evaluate(() => document.getElementById('xdzProfPerf').click()); await pr.waitForTimeout(80);
  check('   tapping the locked Performance card opens no sheet and sends nothing', await pr.evaluate(() => !document.getElementById('xqSheet').classList.contains('show') && window.__xq.calls.length === 1));
  // zram in Root: reclaim with the delta
  await pr.evaluate(() => { window.__xq.calls.length = 0; document.getElementById('xzrReclaim').click(); }); await pr.waitForTimeout(80);
  const rs = await pr.evaluate(() => ({ title: document.getElementById('xqSheetTitle').innerText, cmd: document.getElementById('xqSheetCmd').innerText, text: document.getElementById('xqSheetText').innerText, expect: document.getElementById('xqSheetExpect').innerText.trim() }));
  check('11. Reclaim disk caches (Root): the sheet names "sync; echo 3 > /proc/sys/vm/drop_caches", says what it frees and that the numbers are read again', rs.title === 'Reclaim disk caches' && rs.cmd === 'sync; echo 3 > /proc/sys/vm/drop_caches' && /folder and inode caches/.test(rs.text) && /read again right after/.test(rs.expect), JSON.stringify(rs));
  await pr.evaluate(f => { window.__phone.mm = f; }, '1587396608 523767808 461664000 0 889876480 8402 15110 236 236\n');
  await pr.evaluate(() => document.getElementById('xqSheetOk').click()); await pr.waitForFunction(() => !xzrBusy, null, { timeout: 5000 }); await pr.waitForTimeout(80);
  const rz = await pr.evaluate(() => ({ calls: window.__xq.calls.map(c => c.bare.slice(0, 40)), result: document.getElementById('xqSheetResult').innerText.replace(/\s+/g, ' ').trim(), delta: document.getElementById('xzrDelta').innerText, comp: document.getElementById('xzrComp').innerText, compD: document.getElementById('xzrCompD').innerText + ':' + document.getElementById('xzrCompD').className, savedD: document.getElementById('xzrSavedD').innerText + ':' + document.getElementById('xzrSavedD').className, allocD: document.getElementById('xzrAllocD').innerText + ':' + document.getElementById('xzrAllocD').className }));
  check('   confirmed: the command, then the read; the sheet and the card show the change (compressed in RAM 536 MB → 440 MB, RAM saved up by 95 MB); the per-number deltas are coloured', rz.calls.join('|') === 'sync; echo 3 > /proc/sys/vm/drop_caches|cat /sys/block/zram0/mm_stat; echo __XQ_' && rz.result === 'Done. RAM saved 978 MB → 1.0 GB, compressed in RAM 536 MB → 440 MB, free swap 4.5 GB → 4.5 GB.' && rz.delta === rz.result && rz.comp === '440 MB' && rz.compD === '−95.4 MB:is-down' && rz.savedD === '+95.4 MB:is-up' && rz.allocD === 'no change:', JSON.stringify(rz));
  await pr.evaluate(() => xqSheetClose());
  await pr.evaluate(() => { window.__xq.calls.length = 0; document.getElementById('xzrDrop').click(); }); await pr.waitForTimeout(80);
  check('   Drop page caches: "sync; echo 1 > /proc/sys/vm/drop_caches"', (await pr.evaluate(() => document.getElementById('xqSheetCmd').innerText)) === 'sync; echo 1 > /proc/sys/vm/drop_caches');
  await pr.evaluate(() => { window.__xq.reply = bare => bare.startsWith('sync; echo 1') ? { out: '/system/bin/sh: can\'t create /proc/sys/vm/drop_caches: Permission denied\n', rc: 1 } : undefined; document.getElementById('xqSheetOk').click(); });
  await pr.waitForFunction(() => !xzrBusy, null, { timeout: 5000 }); await pr.waitForTimeout(80);
  check('   a write the kernel refuses is reported with its words', /^It did not work: \/system\/bin\/sh: can't create \/proc\/sys\/vm\/drop_caches: Permission denied/.test(await pr.evaluate(() => document.getElementById('xqSheetResult').innerText.replace(/\s+/g, ' ').trim())));
  await pr.evaluate(() => { window.__xq.reply = null; xqSheetClose(); });
  await pr.close();

  // ---- 12. Zram: the numbers, Not available, auto-refresh, Trim through the Settings path ----
  const pz = await mkPage({ mode: 'shizuku', init: () => { window.__xq && (window.__xq.delay = 5); } });
  const ez = (fn, a) => pz.evaluate(fn, a);
  await ez(() => { XZR_REFRESH_MS = 150; });
  await ez(() => switchView('extrasettings')); await pz.waitForFunction(() => xzr && !xzrReading, null, { timeout: 5000 }); await pz.waitForTimeout(60);
  const z1 = await ez(() => ({ ratio: document.getElementById('xzrRatio').innerText, badgeT: document.getElementById('xzrBadgeT').innerText, cap: document.getElementById('xzrCap').innerText, alloc: document.getElementById('xzrAlloc').innerText, comp: document.getElementById('xzrComp').innerText, orig: document.getElementById('xzrOrig').innerText, saved: document.getElementById('xzrSaved').innerText, leg: [document.getElementById('xzrLegC'), document.getElementById('xzrLegS'), document.getElementById('xzrLegF')].map(e => e.innerText).join('|'), bar: ['C', 'S', 'F'].map(k => document.getElementById('xzrBar' + k).style.width).join('|'), pill: document.getElementById('xzrPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipZram').innerText.replace(/\s+/g, ' ').trim(), savedOk: document.getElementById('xzrSaved').parentElement.classList.contains('is-ok'), whys: [...document.querySelectorAll('#xzrCard .xzr-why')].map(w => w.innerText).join('|'), dis: ['xzrReclaim', 'xzrDrop', 'xzrTrim'].map(i => document.getElementById(i).disabled).join() }));
  check('12. the zram numbers from mm_stat / disksize / meminfo: 3.0× compression, 1.5 GB of data in 536 MB, Allocated 6.0 GB, Compressed in RAM 536 MB, Uncompressed 1.5 GB, RAM saved 978 MB (in the ok colour), free swap 4.5 GB; the bar: 8.7 % / 15.9 % / 75.4 %', z1.ratio === '3.0×' && z1.badgeT === 'compression' && z1.cap === '1.5 GB of data fits in 536 MB of RAM' && z1.alloc === '6.0 GB' && z1.comp === '536 MB' && z1.orig === '1.5 GB' && z1.saved === '978 MB' && z1.savedOk && z1.leg === '536 MB|978 MB|4.5 GB' && z1.bar === '8.7%|15.9%|75.4%' && z1.pill === '● 978 MB saved' && z1.chip === 'RAM saved 978 MB', JSON.stringify(z1));
  check('   in Shizuku mode Reclaim and Drop are locked with "Needs Root mode" and Trim is live', z1.dis === 'true,true,false' && z1.whys === 'Needs Root mode|Needs Root mode|');
  const parsedZ = await ez(f => { const a = xzrParseMmStat(f.mm_stat), o = xzrParseMmStat(f.mm_stat_old), bad = xzrParseMmStat('cat: /sys/block/zram0/mm_stat: No such file or directory'), mi = xzrParseMeminfo(f.meminfo); return { a: [a.ok, a.orig, a.compr, a.memUsed, a.huge], o: [o.ok, o.orig, o.compr, o.memUsed, o.huge], bad: [bad.ok, bad.text], disk: xzrParseDisksize(f.disksize), diskBad: xzrParseDisksize('cat: x'), mi, ratio: xzrRatioText(xzrSummary(a, 6442450944, mi).ratio), ratio12: xzrRatioText(12.4), ratioNone: xzrRatioText(null), zeros: xzrSummary(xzrParseMmStat('0 0 0 0 0 0 0'), 0, mi).ratio }; }, fixtures);
  check('   the parsers: a 9-field and a 7-field mm_stat, cat\'s error kept as text, disksize, the four meminfo keys, the ratio text (one decimal, whole from 10, a dash for nothing)', parsedZ.a.join() === 'true,1587396608,523767808,561664000,236' && parsedZ.o.join() === 'true,2147483648,838860800,867123200,0' && parsedZ.bad[0] === false && /^cat: /.test(parsedZ.bad[1]) && parsedZ.disk === 6442450944 && parsedZ.diskBad === null && parsedZ.mi.swapTotalKb === 6291452 && parsedZ.mi.swapFreeKb === 4741296 && parsedZ.mi.memTotalKb === 7818100 && parsedZ.ratio === '3.0×' && parsedZ.ratio12 === '12×' && parsedZ.ratioNone === '—' && parsedZ.zeros === null, JSON.stringify(parsedZ));
  // auto-refresh while visible
  const n0 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  await pz.waitForTimeout(600);
  const n1 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  check('13. while the tab is on the screen the zram counters are read again on the timer (here every 150 ms: at least 2 more reads in 0.6 s)', n1 - n0 >= 2 && n1 - n0 <= 6, n1 - n0 >= 2 && n1 - n0 <= 6 ? undefined : n1 - n0);
  await ez(() => switchView('apps')); await pz.waitForTimeout(400);
  const n2 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  await pz.waitForTimeout(400);
  const n3 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  check('   leaving the tab stops the timer: no more reads', n3 === n2 && (await ez(() => xzrTimer === 0)), n3 === n2 ? undefined : JSON.stringify([n2, n3]));
  await ez(() => switchView('extrasettings')); await pz.waitForTimeout(500);
  const n4 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  check('   coming back reads at once and the timer runs again', n4 > n3 + 1 && (await ez(() => xzrTimer !== 0)), n4 > n3 + 1 ? undefined : n4 - n3);
  await ez(() => { Object.defineProperty(document, 'hidden', { get: () => true, configurable: true }); document.dispatchEvent(new Event('visibilitychange')); }); await pz.waitForTimeout(100);
  const n5 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  await pz.waitForTimeout(400);
  const n6 = await ez(() => window.__xq.calls.filter(c => c.bare.startsWith('cat /sys/block/zram0')).length);
  check('   the page going to the background (document.hidden) stops it too', n6 === n5 && (await ez(() => xzrTimer === 0)), n6 === n5 ? undefined : JSON.stringify([n5, n6]));
  await ez(() => { Object.defineProperty(document, 'hidden', { get: () => false, configurable: true }); document.dispatchEvent(new Event('visibilitychange')); }); await pz.waitForTimeout(100);
  check('   and coming to the front starts it again', await ez(() => xzrTimer !== 0));
  await ez(() => { XZR_REFRESH_MS = 5000; xzrSync(); clearInterval(xzrTimer); xzrTimer = 0; xzrSync(); });
  // not available
  await ez(() => { window.__phone.zramMissing = true; });
  await ez(() => document.getElementById('xzrRefresh').click()); await pz.waitForFunction(() => !xzrReading, null, { timeout: 4000 }); await pz.waitForTimeout(60);
  const na = await ez(() => ({ ratio: document.getElementById('xzrRatio').innerText, badgeT: document.getElementById('xzrBadgeT').innerText, na: document.getElementById('xzrBadge').classList.contains('is-na'), cap: document.getElementById('xzrCap').innerText, saved: document.getElementById('xzrSaved').innerText, pill: document.getElementById('xzrPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipZram').innerText.replace(/\s+/g, ' ').trim(), bar: document.getElementById('xzrBarC').style.width, wrap: document.getElementById('xzrBadge').getBoundingClientRect().height }));
  check('   a phone without zram: the badge reads "— Not available", the line says why with the phone\'s words, every number reads Not available, the pill and chip too, the bar is empty', na.ratio === '—' && na.badgeT === 'Not available' && na.na && na.cap === 'Not available: the phone said “cat: /sys/block/zram0/mm_stat: No such file or directory” for /sys/block/zram0/mm_stat (no zram, or the shell may not read it)' && na.saved === 'Not available' && na.pill === '● Not available' && na.chip === 'RAM saved Not available' && na.bar === '0px' && na.wrap <= 42, JSON.stringify(na));
  await ez(() => { window.__phone.zramMissing = false; });
  // Trim through the Settings page's own path
  await ez(() => { window.__xq.calls.length = 0; document.getElementById('xzrTrim').click(); }); await pz.waitForTimeout(80);
  const ts = await ez(() => ({ title: document.getElementById('xqSheetTitle').innerText, cmd: document.getElementById('xqSheetCmd').innerText, text: document.getElementById('xqSheetText').innerText }));
  check('14. Trim app caches: the sheet names "pm trim-caches 128G" and says it is the same as Settings > Trim Caches in All Applications, that apps and data stay and that the result opens in its own sheet', ts.title === 'Trim app caches' && ts.cmd === 'pm trim-caches 128G' && /same as Settings > Trim Caches in All Applications/.test(ts.text) && /logins and your files stay/.test(ts.text), JSON.stringify(ts));
  await ez(() => { window.__xq.hold = false; window.__trimSent = []; const orig = window.AndroidBridge.executeShellAsync; window.AndroidBridge.executeShellAsync = (id, cmd) => { if (/pm trim-caches/.test(cmd)) { window.__trimSent.push({ id, cmd }); return 'started'; } return orig(id, cmd); }; document.getElementById('xqSheetOk').click(); });
  await pz.waitForTimeout(150);
  const tr = await ez(() => ({ sent: window.__trimSent.map(t => t.cmd), running: !!trimRun, busy: xzrBusy, result: document.getElementById('xqSheetResult').innerText }));
  check('   confirmed: the Settings page\'s trim script runs (df before, pm trim-caches 128G, df after) through trimCachesStart, no second question; the sheet waits for it', tr.sent.length === 1 && /^df -k \/data \| tail -n 1; echo __TRIM_BEGIN__; pm trim-caches 128G; echo __TRIM_RC__\$\?; df -k \/data \| tail -n 1$/.test(tr.sent[0]) && tr.running && tr.busy === 'trim' && /Trimming the app caches/.test(tr.result), JSON.stringify(tr));
  await ez(f => { window.__phone.mm = f; window.onShellDone(window.__trimSent[0].id, '/dev/block/dm-48 227000000 100000000 126000000 45% /data\n__TRIM_BEGIN__\n__TRIM_RC__0\n/dev/block/dm-48 227000000 98000000 128500000 44% /data\n'); }, '1487396608 500000000 540000000 0 889876480 8402 15110 236 236\n');
  await pz.waitForFunction(() => !xzrBusy, null, { timeout: 6000 }); await pz.waitForTimeout(80);
  const tr2 = await ez(() => ({ result: document.getElementById('xqSheetResult').innerText.replace(/\s+/g, ' ').trim(), modal: document.getElementById('commandResultsModal') ? document.getElementById('commandResultsModal').classList.contains('show') : null, trimRun: !!trimRun }));
  check('   when Android is done the Settings page shows its own result sheet and the Zram sheet shows the change', /^Done\. RAM saved 978 MB → 904 MB, compressed in RAM 536 MB → 515 MB/.test(tr2.result) && !tr2.trimRun, JSON.stringify(tr2));
  await ez(() => xqSheetClose());
  await pz.close();

  // ---- 15. Read-Only and no bridge: locked with the reason, never hidden ----
  const pn = await mkPage({ priv: false });
  await pn.evaluate(() => switchView('extrasettings')); await pn.waitForTimeout(300);
  const ro = await pn.evaluate(() => {
    const t = i => document.getElementById(i);
    return { tiles: [...document.querySelectorAll('#xpwCard .xpw-tile')].map(b => b.disabled + ':' + b.querySelector('.xpw-why').innerText.replace(/\s+/g, ' ').trim()).join('|'), lock: t('xpwLock').innerText.replace(/\s+/g, ' ').trim(), setup: !!t('xpwLock').querySelector('button'), pills: ['xpwPill', 'xdzPill', 'xzrPill'].map(i => t(i).innerText.replace(/\s+/g, ' ').trim()).join('|'), chips: ['xsChipDoze', 'xsChipZram'].map(i => t(i).innerText.replace(/\s+/g, ' ').trim()).join('|'), dozeLock: t('xdzLock').innerText.replace(/\s+/g, ' ').trim(), now: t('xdzNow').innerText, acts: [...document.querySelectorAll('.xdz-act, .xdz-prof')].every(b => b.disabled), zram: [...document.querySelectorAll('.xzr-btn')].map(b => b.disabled + ':' + b.querySelector('.xzr-why').innerText).join('|'), cap: t('xzrCap').innerText, ref: t('xzrRefresh').disabled, calls: window.__xq.calls.length, visible: ['xpwCard', 'xdzCard', 'xzrCard'].every(i => t(i).offsetParent !== null) };
  });
  check('15. Read-Only: every tile is disabled with "Viewing only", the lock box says "Read-only: viewing only." with the reason and a Set Up button, the pills say Viewing only (lock), the Doze and Zram controls are off with the reason, nothing was read, nothing is hidden', ro.tiles === 'true:Viewing only|true:Viewing only|true:Viewing only|true:Viewing only|true:Viewing only|true:Viewing only' && /^Read-only: viewing only\. Restarting or switching off the phone changes it\. Needs a working mode \(ADB, Wireless Debugging, Shizuku or Root\)\. Set Up$/.test(ro.lock) && ro.setup && ro.pills === 'Viewing only|Viewing only|Viewing only' && ro.chips === 'Doze Viewing only|RAM saved Viewing only' && /^Read-only: viewing only\./.test(ro.dozeLock) && /^Needs a working mode/.test(ro.now) && ro.acts && ro.zram === 'true:Viewing only|true:Viewing only|true:Viewing only' && /^Needs a working mode/.test(ro.cap) && ro.ref && ro.calls === 0 && ro.visible, JSON.stringify(ro));
  await pn.evaluate(() => { xpwTap(document.getElementById('xpwReboot')); }); await pn.waitForTimeout(80);
  check('   forcing a tap on a locked tile opens the "requires working mode" sheet and sends nothing', await pn.evaluate(() => document.getElementById('privilegeModal').classList.contains('show') && !document.getElementById('xqSheet') && window.__xq.calls.length === 0));
  await pn.evaluate(() => { closePrivilegeModal(); window.__mode.priv = true; window.__mode.mode = 'root'; checkAllWorkingModes(); }); await pn.waitForFunction(() => xzr && !xzrReading && xdz.read, null, { timeout: 5000 }); await pn.waitForTimeout(60);
  const unlocked = await pn.evaluate(() => ({ pill: document.getElementById('xpwPill').innerText.replace(/\s+/g, ' ').trim(), soft: document.getElementById('xpwSoft').disabled, lock: document.getElementById('xpwLock').innerText, reads: window.__xq.calls.length, reclaim: document.getElementById('xzrReclaim').disabled }));
  check('   a working mode arriving (Root) unlocks everything, clears the lock box and reads the phone', unlocked.pill === '● Root' && !unlocked.soft && unlocked.lock === '' && unlocked.reads >= 4 && !unlocked.reclaim, JSON.stringify(unlocked));
  await pn.evaluate(() => { window.__mode.mode = 'adb_wireless'; checkAllWorkingModes(); }); await pn.waitForTimeout(100);
  check('   switching from Root to Wireless ADB locks Soft reboot and Reclaim again and names the mode', await pn.evaluate(() => document.getElementById('xpwSoft').disabled && document.getElementById('xzrReclaim').disabled && document.getElementById('xpwPill').innerText.replace(/\s+/g, ' ').trim() === '● Wireless ADB'));
  await pn.close();
  const pb = await mkPage({ bridge: false });
  await pb.evaluate(() => switchView('extrasettings')); await pb.waitForTimeout(250);
  const nb = await pb.evaluate(() => ({ why: document.querySelector('#xpwReboot .xpw-why').innerText.replace(/\s+/g, ' ').trim(), lock: document.getElementById('xpwLock').innerText.replace(/\s+/g, ' ').trim(), pill: document.getElementById('xzrPill').innerText.replace(/\s+/g, ' ').trim(), now: document.getElementById('xdzNow').innerText }));
  check('   in a browser (no bridge): "Needs the app", the lock box says it works in the app on a phone, nothing breaks', nb.why === 'Needs the app' && /^Needs the app\. This works in the app on a phone, not in a browser\.$/.test(nb.lock) && nb.pill === '● Needs the app' && nb.now === 'This works in the app on a phone, not in a browser.', JSON.stringify(nb));
  await pb.close();

  // ---- 16. layout at every width, two languages ----
  const probe = async (w, h, label, lang, opts) => {
    const p = await mkPage(Object.assign({ w, h, mode: 'adb_tcp' }, opts || {}));
    const e = (fn, a) => p.evaluate(fn, a);
    await e(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important;animation:none!important}'; document.head.appendChild(st); });
    if (lang) { await e(c => LANG_ENGINE.set(c), lang); await p.waitForTimeout(400); }
    await e(() => switchView('extrasettings'));
    if (opts && opts.priv === false) await p.waitForTimeout(400); else await p.waitForFunction(() => xzr && !xzrReading && xdz.read, null, { timeout: 5000 });
    await p.waitForTimeout(150);
    await e(() => { ['xpwMore', 'xdzMore', 'xzrMore'].forEach(i => document.getElementById(i).open = true); });
    await p.waitForTimeout(100);
    const r = await e(() => {
      const de = document.documentElement, rr = document.getElementById('extraSettingsRoot').getBoundingClientRect();
      const secs = '#extraPowerSection, #extraDozeSection, #extraZramSection';
      const oneLine = [...document.querySelectorAll('#extraSettingsRoot .xs-pill, #extraSettingsRoot .xs-chip, #extraPowerSection .xpw-why, #extraZramSection .xzr-why, #extraDozeSection .xdz-lc, #extraZramSection .xzr-stat b')].filter(x => x.offsetParent !== null && x.innerText.trim());
      const wrap = oneLine.filter(x => { const cs = getComputedStyle(x); const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.3; return x.getBoundingClientRect().height > lh * 2.3 + parseFloat(cs.paddingTop) * 2; }).map(x => x.id || x.className);
      const badge = document.getElementById('xzrBadge'); if (badge.getBoundingClientRect().height > 42 || badge.scrollWidth > badge.clientWidth + 1) wrap.push('xzrBadge');
      const out = [...document.querySelectorAll(secs + ', ' + secs.split(', ').map(s => s + ' *').join(', '))].filter(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0 && (x.getBoundingClientRect().right > rr.right + 0.5 || x.getBoundingClientRect().left < rr.left - 0.5)).map(x => x.id || x.className || x.tagName).slice(0, 5);
      const small = [...document.querySelectorAll('#extraPowerSection .xpw-tile, #extraDozeSection .xdz-act, #extraDozeSection .xdz-prof, #extraZramSection .xzr-btn, #extraZramSection .xzr-ref, #xdzRefresh, #extraSettingsRoot .xs-more summary, #extraSettingsRoot .xs-chip')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().height < 43.5).map(x => (x.id || x.className) + ':' + Math.round(x.getBoundingClientRect().height));
      const clip = [...document.querySelectorAll('#extraPowerSection .xpw-tile, #extraDozeSection .xdz-act, #extraDozeSection .xdz-prof, #extraZramSection .xzr-btn, #extraZramSection .xzr-stat')].filter(x => x.scrollWidth > x.clientWidth + 1 || x.scrollHeight > x.clientHeight + 1).map(x => x.id || x.className);
      const words = [...document.querySelectorAll('#xdzLine .xdz-lbl')].map(l => { const r = document.createRange(); r.selectNodeContents(l); const lines = Math.round(r.getBoundingClientRect().height / (parseFloat(getComputedStyle(l).lineHeight) || 12)); return l.innerText.split(/\s+/).length >= lines; });
      const cols = { pw: getComputedStyle(document.querySelector('#extraPowerSection .xpw-grid')).gridTemplateColumns.split(' ').length, acts: getComputedStyle(document.querySelector('#extraDozeSection .xdz-acts')).gridTemplateColumns.split(' ').length, profs: getComputedStyle(document.querySelector('#extraDozeSection .xdz-profs')).gridTemplateColumns.split(' ').length, stats: getComputedStyle(document.querySelector('#extraZramSection .xzr-grid')).gridTemplateColumns.split(' ').length };
      return { page: de.scrollWidth <= window.innerWidth, wrap, out, small, clip, wordsOk: words.every(Boolean), cols, w: Math.round(rr.width) };
    });
    await p.close();
    return Object.assign({ label }, r);
  };
  for (const [w, h] of [[320, 640], [360, 740], [412, 860], [768, 900]]) {
    const r = await probe(w, h, w + ' px');
    check('16. [' + w + ' px] the three sections: no sideways scroll, nothing outside the column, no pill / chip / badge / stat wraps, every control at least 44 px tall, no tile content cut, no timeline word split, tiles ' + r.cols.pw + ' column(s), stats ' + r.cols.stats, r.page && r.out.length === 0 && r.wrap.length === 0 && r.small.length === 0 && r.clip.length === 0 && r.wordsOk && (w < 640 ? r.cols.pw === 2 && r.cols.stats === 2 : r.cols.pw === 3 && r.cols.stats === 4) && (w < 400 ? r.cols.profs === 1 : r.cols.profs === 3), JSON.stringify(r));
  }
  for (const [code, label] of [['de', 'German'], ['ru', 'Russian']]) {
    const r = await probe(360, 740, label, code);
    check('   [360 px, ' + label + '] long translations: no sideways scroll, nothing outside the column, no pill wraps, targets 44 px, nothing cut', r.page && r.out.length === 0 && r.wrap.length === 0 && r.small.length === 0 && r.clip.length === 0, JSON.stringify(r));
  }
  const rRo = await probe(360, 740, 'read-only', null, { priv: false });
  check('   [360 px, Read-Only] the lock boxes and the "Viewing only" pills fit too', rRo.page && rRo.out.length === 0 && rRo.wrap.length === 0 && rRo.small.length === 0, JSON.stringify(rRo));
  // the sheet at 320 px
  const ps = await mkPage({ w: 320, h: 640 });
  await ps.evaluate(() => switchView('extrasettings')); await ps.waitForTimeout(300);
  await ps.evaluate(() => document.getElementById('xpwPoweroff').click()); await ps.waitForTimeout(150);
  const sh = await ps.evaluate(() => { const s = document.querySelector('#xqSheet .modal-sheet'), r = s.getBoundingClientRect(); const btns = [...document.querySelectorAll('#xqSheet .xq-btn')].filter(b => getComputedStyle(b).display !== 'none'); return { fits: r.right <= window.innerWidth + 0.5 && r.left >= -0.5 && s.scrollWidth <= s.clientWidth + 1, btns: btns.map(b => Math.round(b.getBoundingClientRect().height) + ':' + (b.scrollWidth <= b.clientWidth + 1)).join(), cmdFits: document.getElementById('xqSheetCmd').getBoundingClientRect().right <= r.right }; });
  check('   [320 px] the sheet fits, its buttons are 48 px and not cut, the command line wraps inside the box', sh.fits && sh.btns === '48:true,48:true' && sh.cmdFits, JSON.stringify(sh));
  await ps.close();

  // ---- 17. colours and motion ----
  const p4 = await mkPage({ w: 360, h: 800, reduce: true });
  const e4 = (fn, a) => p4.evaluate(fn, a);
  await e4(() => switchView('extrasettings')); await p4.waitForFunction(() => xzr && !xzrReading && xdz.read, null, { timeout: 5000 }); await p4.waitForTimeout(100);
  const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
  const cr = (a, b2) => { const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  for (const [mode, preset] of [['dark', 'amoled'], ['light', 'amoled'], ['dark', 'sunset'], ['light', 'sunset']]) {
    await e4(a => { applyPalettePreset(a[1]); setAppearance(a[0]); }, [mode, preset]); await p4.waitForTimeout(150);
    const s = await e4(() => {
      const parse = s => { s = s.trim(); if (/^#[0-9a-f]{6}$/i.test(s)) return { n: [1, 3, 5].map(i => parseInt(s.slice(i, i + 2), 16)), a: 1 }; const m = s.match(/[\d.]+/g) || []; const k = /^color\(/.test(s) ? 255 : 1; return { n: m.slice(0, 3).map(x => Math.round(+x * k)), a: m.length >= 4 ? +m[3] : 1 }; };
      const rgb = e => parse(getComputedStyle(e).color).n;
      const token = (e, v) => parse(getComputedStyle(e).getPropertyValue(v)).n;
      const bg = e => { for (let n = e; n; n = n.parentElement) { const c = parse(getComputedStyle(n).backgroundColor); if (c.a >= 0.5 && getComputedStyle(n).backgroundColor !== 'rgba(0, 0, 0, 0)') return c.n; } return [0, 0, 0]; };
      const q = s => document.querySelector(s);
      const pairs = { tileSub: [q('#xpwReboot .xpw-sub'), q('#xpwReboot')], tileWhy: [q('#xpwSoft .xpw-why'), q('#xpwSoft .xpw-why')], dangerTitle: [q('#xpwPoweroff b'), q('#xpwPoweroff')], phase: [q('#xdzLine .is-now .xdz-lbl'), q('#xdzCard')], lightChip: [q('#xdzLight .is-now'), q('#xdzLight .is-now')], actSub: [q('#xdzForce small'), q('#xdzForce')], profSub: [q('#xdzProfBal small'), q('#xdzProfBal')], badge: [q('#xzrBadge b'), q('#xzrBadge')], statOk: [q('#xzrSaved'), q('#xzrSaved').parentElement], statLbl: [q('#xzrAlloc').previousElementSibling, q('#xzrAlloc').parentElement], why: [q('#xzrReclaim .xzr-why'), q('#xzrReclaim .xzr-why')], pill: [q('#xzrPillT'), q('#xzrPill')], trust: [q('#xpwCard .xs-trust span'), q('#xpwCard')] };
      const out = {}; Object.keys(pairs).forEach(k => { out[k] = [rgb(pairs[k][0]), bg(pairs[k][1])]; });
      out.badge = [rgb(q('#xzrBadge b')), token(q('#xzrBadge'), '--accent')]; out.badge2 = [rgb(q('#xzrBadge b')), token(q('#xzrBadge'), '--secondary')];          // the badge sits on the accent-to-secondary gradient: both ends
      return out;
    });
    const low = Object.keys(s).filter(k => cr(s[k][0], s[k][1]) < 3.2).map(k => k + '=' + cr(s[k][0], s[k][1]).toFixed(1));
    check('17. [' + mode + ', ' + preset + '] text reads against its background (contrast at least 3.2:1 for subtitles, hints, lock pills, the lit phase, the badge, the ok number, the pill)', low.length === 0, low.join(' ') || 'all above 3.2');
  }
  await e4(() => { applyPalettePreset('amoled'); setAppearance('dark'); });
  const motion = await e4(() => { const d = e => getComputedStyle(e).transitionDuration + '/' + getComputedStyle(e).animationName; return [document.getElementById('xpwReboot'), document.getElementById('xdzFill'), document.getElementById('xzrBarC'), document.querySelector('#xdzLine .is-now .xdz-dot')].map(d); });
  check('   reduced motion: no transition and no animation on the tiles, the timeline fill, the bar and the lit dot', motion.every(m => m === '0s/none'), JSON.stringify(motion));
  await e4(() => document.getElementById('xpwBootloader').click()); await p4.waitForTimeout(120);
  const hb = await p4.locator('#xqSheetHold').boundingBox(); await p4.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2); await p4.mouse.down(); await p4.waitForTimeout(1450); await p4.mouse.up(); await p4.waitForTimeout(80);
  check('   with reduced motion the press-and-hold still fills and confirms (it is progress, not decoration): "reboot bootloader" sent once', (await e4(() => window.__xq.calls.filter(c => c.bare === 'reboot bootloader').length)) === 1 && /^Bootloader: sent/.test(await e4(() => document.getElementById('xqSheetResult').innerText)));
  await e4(() => document.getElementById('xpwRecovery').click());
  check('   while the watchdog of one action still runs, the other tiles do nothing (the section is busy)', await e4(() => document.getElementById('xqSheetTitle').innerText === 'Bootloader' && xpwBusy === 'bootloader'));
  await p4.waitForFunction(() => !xpwBusy, null, { timeout: 4000 });
  await e4(() => xqSheetClose());
  await e4(() => { document.getElementById('xpwRecovery').click(); }); await p4.waitForTimeout(120);
  const kb = await e4(() => new Promise(res => { const h = document.getElementById('xqSheetHold'); h.focus(); h.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true })); setTimeout(() => { const p = parseFloat(getComputedStyle(h).getPropertyValue('--p')); h.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true })); setTimeout(() => res({ p, sent: window.__xq.calls.filter(c => c.bare === 'reboot recovery').length }), 30); }, 400); }));
  check('   the hold works from the keyboard too (Space held fills the ring; released early, nothing is sent)', kb.p > 0.2 && kb.p < 0.6 && kb.sent === 0, kb.p > 0.2 && kb.p < 0.6 && kb.sent === 0 ? undefined : JSON.stringify(kb));
  await e4(() => xqSheetClose());
  await p4.close();

  await b.close();
  check('18. no page errors', errors.length === 0, JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL script error: ' + (e && e.stack || e)); process.exit(1); });
