// v7.16.0: "Start Shizuku (ADB)" in the Shizuku card of Working Modes: the button asks the phone side once and waits for its answer, a second tap while it
// runs is refused, each answer (started, already running, not installed, no ADB connection, no start script, failed) gets its own sentence, the script's
// own text is shown as data only when the start failed, "no start script" opens the Shizuku app, a bridge without the method says so, at 320 px nothing
// sticks out sideways, and in German the answers say "Sie" as the card's other sentences do.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };

  const open = async (w, h, withStart, reply) => {
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(({ withStart, reply }) => {
      window.__calls = []; window.__reply = reply;
      const mode = { configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, adbTcp: { host: '127.0.0.1', port: 5555, connected: true }, adbWireless: { host: '', port: 0, connected: false }, shizuku: { installed: true, running: false } };
      const br = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        getWorkingMode() { return JSON.stringify(mode); }, getWorkingModeFresh() { return JSON.stringify(mode); },
        openShizukuApp() { window.__calls.push('openShizukuApp'); },
        getAdbKeyInfo() { return JSON.stringify({ fingerprint: 'AA:BB', noticePending: false }); } };
      if (withStart) br.startShizukuViaAdb = function () { window.__calls.push('startShizukuViaAdb'); return window.__reply; };
      window.AndroidBridge = br;
    }, { withStart, reply });
    await page.goto(PAGE); await page.waitForTimeout(450);
    await page.evaluate(() => openWorkingModesModal()); await page.waitForTimeout(250);
    return { page, errors };
  };
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').textContent);
  const state = page => page.evaluate(() => { const o = document.getElementById('shizukuStartOut'); return { disabled: document.getElementById('shizukuStartBtn').disabled, outShown: o.style.display !== 'none', out: o.textContent, label: document.getElementById('shizukuStartBtn').textContent.trim() }; });
  const answer = async (page, res) => { await page.evaluate(r => window.onShizukuStartResult(r), res); await page.waitForTimeout(120); };

  // 1. tap, wait, each answer
  {
    const { page, errors } = await open(400, 860, true, 'started_async');
    check('1. the card has the button, named Start Shizuku (ADB)', (await state(page)).label === 'Start Shizuku (ADB)');
    await page.evaluate(() => document.getElementById('shizukuStartBtn').click()); await page.waitForTimeout(150);
    check('   one tap asks the phone side once, disables the button and says it is starting', (await page.evaluate(() => window.__calls.filter(c => c === 'startShizukuViaAdb').length)) === 1 && (await state(page)).disabled && (await toast(page)) === 'Starting Shizuku through ADB...');
    const cases = [
      ['started', 'Shizuku is running. Tap Authorize & Use Shizuku next.'],
      ['already_running', 'Shizuku is already running. Tap Authorize & Use Shizuku.'],
      ['not_installed', 'Shizuku is not installed'],
      ['no_adb', 'Connect ADB TCP or Wireless Debugging first, then start Shizuku.'],
      ['no_script', 'Shizuku has not written its start script yet. Open the Shizuku app once, then try again.'],
      ['failed', 'Shizuku did not start.'],     // no script text (a failure before the script ran, or a script that printed nothing): nothing "below" to point at
    ];
    for (const [status, msg] of cases) {
      await answer(page, { status, pkg: 'moe.shizuku.privileged.api', script: '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh', exit: 0, text: '' });
      const st = await state(page);
      check('   ' + status + ': the sentence, the button is back, no text box without text', (await toast(page)) === msg && !st.disabled && !st.outShown, (await toast(page)));
    }
    check('   only "no script" opened the Shizuku app', (await page.evaluate(() => window.__calls.filter(c => c === 'openShizukuApp').length)) === 1);
    await answer(page, { status: 'failed', pkg: 'x.y', script: '/s', exit: 3, text: 'exec app_process failed\n<b>not html</b>' });
    const f = await state(page);
    check('   a failed start shows the script text as plain text', f.outShown && f.out === 'exec app_process failed\n<b>not html</b>' && (await page.evaluate(() => document.getElementById('shizukuStartOut').children.length)) === 0, JSON.stringify(f));
    check('   and only then the sentence points at the text below', (await toast(page)) === 'Shizuku did not start. What the start script printed is shown below.', (await toast(page)));
    await answer(page, { status: 'failed', pkg: '' });
    const g = await state(page);
    check('   a failure with no text at all (the phone side threw): the short sentence, the box hidden and empty', (await toast(page)) === 'Shizuku did not start.' && !g.outShown && g.out === '', (await toast(page)) + ' ' + JSON.stringify(g));
    await answer(page, { status: 'started', pkg: 'x.y', script: '/s', exit: 0, text: 'Shizuku is running' });
    check('   a start that worked hides the text again', !(await state(page)).outShown);
    check('   an answer with nothing in it is "Could not start Shizuku" and does not throw', await (async () => { await answer(page, null); return (await toast(page)) === 'Could not start Shizuku'; })());
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 2. a start that is still running refuses a second one; an unexpected reply does not leave the button stuck
  {
    const { page } = await open(400, 860, true, 'busy');
    await page.evaluate(() => document.getElementById('shizukuStartBtn').click()); await page.waitForTimeout(150);
    check('2. "busy" from the phone side: a sentence, the button stays usable', (await toast(page)) === 'Shizuku is already being started...' && !(await state(page)).disabled);
    await page.evaluate(() => { window.__reply = 'error'; document.getElementById('shizukuStartBtn').click(); }); await page.waitForTimeout(150);
    check('   any other reply: "Could not start Shizuku", the button stays usable', (await toast(page)) === 'Could not start Shizuku' && !(await state(page)).disabled);
    await page.close();
  }

  // 3. an old bridge
  {
    const { page, errors } = await open(400, 860, false, '');
    await page.evaluate(() => document.getElementById('shizukuStartBtn').click()); await page.waitForTimeout(150);
    check('3. a bridge without the method: says the app is too old, nothing thrown', (await toast(page)) === 'Starting Shizuku needs a newer app version' && errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 4. 320 px: nothing sticks out sideways, long script text wraps
  {
    const { page } = await open(320, 700, true, 'started_async');
    await answer(page, { status: 'failed', pkg: 'x.y', script: '/s', exit: 1, text: 'A'.repeat(300) + ' ' + '/very/long/path/'.repeat(20) });
    const over = await page.evaluate(() => { const sheet = document.getElementById('modeCardShizuku'); const r = sheet.getBoundingClientRect(); const o = document.getElementById('shizukuStartOut').getBoundingClientRect(); return { cardRight: Math.round(r.right), outRight: Math.round(o.right), vw: window.innerWidth, sw: document.documentElement.scrollWidth }; });
    check('4. at 320 px the text box stays inside its card and the page does not scroll sideways', over.outRight <= over.cardRight && over.sw <= over.vw, JSON.stringify(over));
    await page.close();
  }

  // 5. German: the answers that tell the user what to do next say "Sie", as the card's other sentences do ("Starten Sie es in der Shizuku-App ..."), never "du"
  {
    const { page, errors } = await open(400, 860, true, 'started_async');
    await page.evaluate(() => I18N.set('de')); await page.waitForTimeout(500);
    const seen = [];
    for (const status of ['started', 'already_running', 'no_adb', 'no_script']) { await answer(page, { status, pkg: 'x.y', text: '' }); seen.push(await toast(page)); }
    check('5. in German every start answer that asks for a step says "Sie", none says "du"', seen.every(s => /\bSie\b/.test(s) && !/\b(Tippe|Öffne|Verbinde|starte|versuche)\b/.test(s)) && errors.length === 0, JSON.stringify(seen) + ' ' + errors.join(' | '));
    await page.close();
  }

  await b.close();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
})();
