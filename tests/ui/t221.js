// Console tab: the Screenshot and Record screen buttons. A press calls the phone side once (screenshot after 3 s; recording for up to 180 s), each answer of the
// phone side gets its own sentence, the Record button turns into Stop recording while it records and back when the file is done, a saved file is named with its
// size, a failure shows the phone's last line, and a browser without the bridge says so instead of failing.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const open = async (answers, withBridge) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 }, hasTouch: true });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(({ answers, withBridge }) => {
      window.__calls = []; window.__ans = answers;
      if (!withBridge) return;
      window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getWorkingMode() { return '{}'; },
        captureScreenshot(d) { window.__calls.push('shot:' + d); return window.__ans.shot; },
        startScreenRecord(s) { window.__calls.push('rec:' + s); return window.__ans.rec; },
        stopScreenRecord() { window.__calls.push('stop'); return window.__ans.stop; } };
    }, { answers, withBridge });
    await page.goto(PAGE); await page.waitForTimeout(450);
    await page.evaluate(() => switchView('console')); await page.waitForTimeout(300);
    return { page, errors };
  };
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').textContent);
  const calls = page => page.evaluate(() => window.__calls.join());
  const click = async (page, id) => { await page.evaluate(id => document.getElementById(id).click(), id); await page.waitForTimeout(120); };
  const label = (page, id) => page.evaluate(id => document.getElementById(id).textContent, id);
  const result = (page, r) => page.evaluate(r => { window.onScreenCaptureResult(JSON.stringify(r)); }, r);

  // 1. the screenshot
  {
    const { page, errors } = await open({ shot: 'started', rec: 'started', stop: 'stopping' }, true);
    check('1. both buttons are in the Console tools row', (await label(page, 'screenShotBtn')) === 'Screenshot' && (await label(page, 'screenRecBtn')) === 'Record screen');
    await click(page, 'screenShotBtn');
    check('   one press: captureScreenshot(3) and a toast that says to open the screen', (await calls(page)) === 'shot:3' && /3 seconds/.test(await toast(page)), await calls(page) + ' | ' + await toast(page));
    await result(page, { kind: 'shot', status: 'ok', path: '/sdcard/Pictures/ADB App Manager/screenshot-20251012-201530.png', bytes: 1536000, text: '' });
    const t = await toast(page);
    check('   an ok answer names the saved file (without /sdcard) and its size', /Saved Pictures\/ADB App Manager\/screenshot-20251012-201530\.png \(1\.5 MB\)\./.test(t) || /Saved Pictures\/ADB App Manager\/screenshot-20251012-201530\.png \(1[.,]4\d* MB\)\./.test(t), t);
    await result(page, { kind: 'shot', status: 'failed', path: '/sdcard/Pictures/x.png', bytes: -1, text: 'screencap: cannot open display\nscreencap: failed to capture' });
    check('   a failed answer shows the phone\'s last line', /The phone did not save it: screencap: failed to capture$/.test(await toast(page)), await toast(page));
    await result(page, { kind: 'shot', status: 'failed', path: '', bytes: -1, text: '' });
    check('   a failed answer with no text still says so', (await toast(page)) === 'The phone did not save it.', await toast(page));
    await result(page, { kind: 'shot', status: 'no_access', path: '', bytes: -1, text: '' });
    check('   no_access says a working mode is needed', /working mode/.test(await toast(page)), await toast(page));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 2. each refusal of the phone side
  {
    for (const [ans, re] of [['busy', /already running/], ['no_access', /working mode/], ['', /could not be started/]]) {
      const { page } = await open({ shot: ans, rec: ans, stop: 'idle' }, true);
      await click(page, 'screenShotBtn');
      const a = await toast(page);
      await click(page, 'screenRecBtn');
      const r = await toast(page);
      check(`2. answer "${ans || '(nothing)'}": screenshot and recording each say so`, re.test(a) && re.test(r), a + ' | ' + r);
      check('   a refused recording leaves the button as Record screen', (await label(page, 'screenRecBtn')) === 'Record screen');
      await page.close();
    }
  }

  // 3. the recording: start, Stop recording, finish
  {
    const { page } = await open({ shot: 'started', rec: 'started', stop: 'stopping' }, true);
    await click(page, 'screenRecBtn');
    check('3. start: startScreenRecord(180), the button reads Stop recording and is marked pressed', (await calls(page)) === 'rec:180' && (await label(page, 'screenRecBtn')) === 'Stop recording' && (await page.evaluate(() => document.getElementById('screenRecBtn').getAttribute('aria-pressed'))) === 'true');
    check('   the toast says it stops by itself after 3 minutes', /3 minutes/.test(await toast(page)), await toast(page));
    await click(page, 'screenRecBtn');
    check('   the second press asks to stop (stopScreenRecord), not to start again', (await calls(page)) === 'rec:180,stop' && /Stopping/.test(await toast(page)), await calls(page));
    await result(page, { kind: 'record', status: 'ok', path: '/sdcard/Movies/ADB App Manager/screenrecord-20251012-201530.mp4', bytes: 5242880, text: '' });
    check('   the finished file resets the button and is named with its size', (await label(page, 'screenRecBtn')) === 'Record screen' && /Saved Movies\/ADB App Manager\/screenrecord-20251012-201530\.mp4 \(5(\.0)? MB\)\./.test(await toast(page)), await label(page, 'screenRecBtn') + ' | ' + await toast(page));
    await click(page, 'screenRecBtn');
    check('   after that a press starts a new recording', (await calls(page)).endsWith('rec:180,stop,rec:180'));
    await result(page, { kind: 'record', status: 'failed', path: '', bytes: 0, text: '' });
    check('   a recording that failed also resets the button', (await label(page, 'screenRecBtn')) === 'Record screen' && (await page.evaluate(() => document.getElementById('screenRecBtn').getAttribute('aria-pressed'))) === 'false');
    await page.close();
  }

  // 4. stop when the phone says nothing is recording
  {
    const { page } = await open({ shot: 'started', rec: 'started', stop: 'idle' }, true);
    await click(page, 'screenRecBtn'); await click(page, 'screenRecBtn');
    check('4. stop answered "idle": the toast says nothing is recording', /Nothing is recording/.test(await toast(page)), await toast(page));
    await page.close();
  }

  // 5. no bridge (a browser)
  {
    const { page, errors } = await open({}, false);
    await click(page, 'screenShotBtn');
    const a = await toast(page);
    await click(page, 'screenRecBtn');
    const r = await toast(page);
    check('5. without the app both buttons say it works on a phone, no errors', /not in a browser/.test(a) && /not in a browser/.test(r) && errors.length === 0, a + ' | ' + r + ' | ' + errors.join(' | '));
    await page.close();
  }

  await b.close();
  console.log(failed ? failed + ' FAILED' : 'all ok');
  process.exit(failed ? 1 : 0);
})();
