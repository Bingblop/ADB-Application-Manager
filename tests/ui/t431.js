// v7.14 Task Manager > GPU: the renderer dropdown has ANGLE (OpenGL ES on Vulkan), the global setting angle_gl_driver_all_angle. Outside Root
// mode the entry carries a lock: it cannot be chosen, and a tap on it (or on the lock) says it needs Root. In Root mode choosing it runs
// "settings put global angle_gl_driver_all_angle 1" and believes only what "settings get" reads back. Leaving ANGLE for another renderer runs
// "... 0" first, reads it back, and only then applies the other renderer as before (setprop + System UI restart); a failed read-back stops
// there. Opening the tab with the setting at 1 shows ANGLE. The mock answers like executeShell: output, then execShellOk's marker and status.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let bad = 0;
  const check = (name, ok, extra) => { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || extra === undefined ? '' : ' ' + extra)); };
  const errors = [];

  async function open(mode, angle) {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([mode, angle]) => {
      const st = window.__st = { cmds: [], angle, prop: '', stuck: null };       // stuck: the value the phone keeps whatever is put
      window.__kv = { taskmgr_ui: JSON.stringify({ sub: 'gpu' }) };
      window.AndroidBridge = {
        executeShell(cmd) {
          st.cmds.push(cmd.split('; echo "')[0]);
          const m = /echo "([^"]+):\$\?"$/.exec(cmd);
          const tail = m ? '\n' + m[1] + ':0\n' : '';
          const base = cmd.split('; echo "')[0];
          let pm;
          if (base === 'settings get global angle_gl_driver_all_angle') return (st.angle === null ? 'null' : st.angle) + '\n' + tail;
          if ((pm = /^settings put global angle_gl_driver_all_angle (\d)$/.exec(base))) { st.angle = st.stuck !== null ? st.stuck : pm[1]; return tail; }
          if (base === 'getprop debug.hwui.renderer') return st.prop + '\n' + tail;
          if ((pm = /^setprop debug\.hwui\.renderer (\S+)$/.exec(base))) { st.prop = pm[1]; return tail; }
          return tail;
        },
        vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
        loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
        saveSetting(k, v) { window.__kv[k] = v; },
        loadPackages() { return '[]'; },
        hasRoot() { return mode === 'root'; },
        getWorkingMode() { return JSON.stringify({ activeMode: mode, modeAvailable: true, isPrivileged: true, configuredMode: mode, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: mode === 'shizuku' }, rootAvailable: mode === 'root', rootGranted: mode === 'root' ? true : undefined }); },
        tmStart() {}, tmStop() {}, tmRefreshNow() {}, tmKill() {},
      };
    }, [mode, angle]);
    await page.goto(PAGE); await page.waitForTimeout(300);
    await page.evaluate(() => switchView('taskmgr')); await page.waitForTimeout(60);
    await page.evaluate(() => onTaskMgrData({ ts: 0, ok: true, privileged: true, gpu: { available: true, percent: 10, vulkanSupported: true } }));
    await page.evaluate(() => tmLoadRenderer()); await page.waitForTimeout(30);
    return page;
  }
  const state = page => page.evaluate(() => {
    const sel = document.getElementById('tmRendererSelect');
    return { value: sel.value, opts: [...sel.options].map(o => o.value), angleText: ([...sel.options].find(o => o.value === 'angle') || {}).text, lock: getComputedStyle(document.getElementById('tmAngleLock')).display !== 'none',
      lockSvg: !!document.querySelector('#tmAngleLockIc svg'), now: document.getElementById('tmRendererNow').innerText, note: document.getElementById('tmRendererNote').innerText };
  });
  const cmdsSince = (page, n) => page.evaluate(n => __st.cmds.slice(n), n);
  const nCmds = page => page.evaluate(() => __st.cmds.length);
  const toast = async page => (await page.locator('#toastMsg').innerText()).trim();
  const pick = (page, v) => page.selectOption('#tmRendererSelect', v).then(() => page.waitForTimeout(40));

  // ---------------------------------------------------------------- without Root (Shizuku): locked
  let page = await open('shizuku', '0');
  let s = await state(page);
  check('1. Shizuku: the dropdown lists ANGLE (OpenGL ES on Vulkan) after the HWUI renderers', JSON.stringify(s.opts) === JSON.stringify(['', 'skiagl', 'skiavk', 'angle']) && /^ANGLE \(OpenGL ES on Vulkan\)/.test(s.angleText), JSON.stringify(s));
  check('   it is marked as needing Root, with a lock (a drawing, no emoji) next to the dropdown', /needs Root/.test(s.angleText) && s.lock && s.lockSvg, JSON.stringify(s));
  let n = await nCmds(page);
  await pick(page, 'angle');
  s = await state(page);
  let c = await cmdsSince(page, n);
  check('2. choosing it without Root runs no settings put and goes back to the renderer in use', !c.some(x => /settings put/.test(x)) && s.value === '' && s.now === 'Default', JSON.stringify(c) + ' ' + JSON.stringify(s));
  check('   and says it needs Root mode', /ANGLE needs Root mode/.test(await toast(page)), await toast(page));
  await page.evaluate(() => { document.getElementById('toastMsg').innerText = ''; });
  await page.click('#tmAngleLock'); await page.waitForTimeout(30);
  check('   a tap on the lock explains the same', /ANGLE needs Root mode/.test(await toast(page)), await toast(page));
  await page.close();

  page = await open('shizuku', '1');
  s = await state(page);
  check('3. opening the tab with the setting at 1 shows ANGLE (read in any working mode)', s.value === 'angle' && s.now === 'ANGLE' && /apps pick up a change when they restart/i.test(s.note), JSON.stringify(s));
  n = await nCmds(page);
  await pick(page, 'skiagl');
  c = await cmdsSince(page, n);
  check('   leaving it without Root is refused before any command: turning ANGLE off needs Root', !c.some(x => /settings put|setprop|am crash/.test(x)) && /Turning ANGLE off needs Root mode/.test(await toast(page)) && (await state(page)).value === 'angle', JSON.stringify(c));
  await page.close();

  // ---------------------------------------------------------------- Root: on, read back
  page = await open('root', null);
  s = await state(page);
  check('4. Root: no lock, the entry reads plainly; a setting never written ("null") counts as off', !s.lock && s.angleText === 'ANGLE (OpenGL ES on Vulkan)' && s.value === '' && s.now === 'Default', JSON.stringify(s));
  n = await nCmds(page);
  await pick(page, 'angle');
  c = await cmdsSince(page, n);
  s = await state(page);
  check('5. choosing ANGLE runs settings put ... 1, then reads it back with settings get', c[0] === 'settings put global angle_gl_driver_all_angle 1' && c[1] === 'settings get global angle_gl_driver_all_angle', JSON.stringify(c));
  check('   no HWUI renderer change and no System UI restart for ANGLE', !c.some(x => /setprop|am crash/.test(x)), JSON.stringify(c));
  check('   the phone now says 1: the dropdown and the card show ANGLE, the note says apps pick it up when they restart', s.value === 'angle' && s.now === 'ANGLE' && /restart/.test(s.note) && /ANGLE on/.test(await toast(page)), JSON.stringify(s) + ' ' + await toast(page));

  // ---------------------------------------------------------------- Root: off first, then the other renderer
  n = await nCmds(page);
  await pick(page, 'skiavk');
  c = await cmdsSince(page, n);
  const order = c.filter(x => /settings (put|get) global angle|setprop debug|am crash/.test(x));
  check('6. ANGLE -> Vulkan: ANGLE off (0), read back, THEN setprop skiavk and the System UI restart, in that order', JSON.stringify(order.slice(0, 4)) === JSON.stringify(['settings put global angle_gl_driver_all_angle 0', 'settings get global angle_gl_driver_all_angle', 'setprop debug.hwui.renderer skiavk', 'am crash com.android.systemui']), JSON.stringify(order));
  s = await state(page);
  check('   the dropdown then shows Vulkan, ANGLE is off on the phone', s.value === 'skiavk' && s.now === 'Vulkan' && (await page.evaluate(() => __st.angle)) === '0', JSON.stringify(s));

  // a failed turn-on: the phone keeps 0
  await page.evaluate(() => { __st.stuck = '0'; });
  await pick(page, 'angle');
  s = await state(page);
  check('7. when the read-back does not say 1, it is not shown as ANGLE and the toast says it could not', s.value === 'skiavk' && /Could not turn on ANGLE/.test(await toast(page)), JSON.stringify(s) + ' ' + await toast(page));

  // a failed turn-off: the phone keeps 1, the other renderer is not applied
  await page.evaluate(() => { __st.stuck = null; __st.angle = '1'; tmLoadRenderer(); });
  await page.waitForTimeout(20);
  await page.evaluate(() => { __st.stuck = '1'; });
  n = await nCmds(page);
  await pick(page, 'skiagl');
  c = await cmdsSince(page, n);
  s = await state(page);
  check('8. when turning ANGLE off does not read back 0, the other renderer is NOT applied (no setprop, no restart)', c.includes('settings put global angle_gl_driver_all_angle 0') && !c.some(x => /setprop debug|am crash/.test(x)) && s.value === 'angle' && /Could not turn off ANGLE/.test(await toast(page)), JSON.stringify(c) + ' ' + JSON.stringify(s));

  // ANGLE -> Default: only ANGLE off
  await page.evaluate(() => { __st.stuck = null; __st.prop = ''; tmLoadRenderer(); });
  n = await nCmds(page);
  await pick(page, '');
  c = await cmdsSince(page, n);
  s = await state(page);
  check('9. ANGLE -> Default turns ANGLE off (0, read back) and changes nothing else', c[0] === 'settings put global angle_gl_driver_all_angle 0' && c[1] === 'settings get global angle_gl_driver_all_angle' && !c.some(x => /setprop debug|am crash/.test(x)) && s.value === '' && s.now === 'Default' && /ANGLE off/.test(await toast(page)), JSON.stringify(c) + ' ' + JSON.stringify(s));

  // an ordinary renderer change with ANGLE off runs no ANGLE command
  n = await nCmds(page);
  await pick(page, 'skiagl');
  c = await cmdsSince(page, n);
  check('10. OpenGL with ANGLE off: setprop and the restart as before, no ANGLE command', JSON.stringify(c.filter(x => /settings put|setprop|am crash/.test(x))) === JSON.stringify(['setprop debug.hwui.renderer skiagl', 'am crash com.android.systemui']), JSON.stringify(c));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
