// The real terminal after a start that failed. The reason used to show only in the note above the screen and nothing was written into the terminal, so closing the screen and
// opening it again left a blank screen that took no keys. Now the reason (the text the phone returned) is also written into the terminal, and opening the screen again after a
// failed start tries to start again (a shell that ended after running is not restarted by opening the screen: it keeps its output and waits for Enter).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__ph = { starts: [], closes: 0, cur: null, writes: [] };
    window.__notice = (ok, message) => { const c = window.__ph.cur; if (!c) return; onPtyStarted('rt', ok ? { ok: true } : { ok: false, message }, c.n); };
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; }, getWorkingMode() { return '{}'; },
      copyToClipboard() {}, getClipboardText() { return 'clip'; },
      ptyStart(id, backend, rows, cols) { const p = window.__ph; const n = p.starts.push({ backend, rows, cols }); p.cur = { n }; return 'started:' + n; },
      ptyWrite(id, b64) { window.__ph.writes.push(atob(b64)); }, ptyResize() {}, ptyAck() {}, ptyClose() { window.__ph.closes++; window.__ph.cur = null; }, ptyInfo() { return '{"helper":true}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const ph = () => ev(() => JSON.parse(JSON.stringify(window.__ph)));
  const screen = () => ev(() => { const t = rt.term, b = t.buffer.active, o = []; for (let i = 0; i < b.length; i++) { const l = b.getLine(i); if (l) o.push(l.translateToString(true)); } return o.join('\n').trim(); });

  await ev(() => switchView('terminal')); await wait(500);
  await page.click('#txRealBtn'); await wait(700);
  await ev(() => window.__notice(false, 'There is no working mode')); await wait(150);
  check('the start failed: state exited, the note shows the reason', await ev(() => rt.state === 'exited' && document.getElementById('rtNote').textContent.indexOf('There is no working mode') >= 0));
  check('the reason is also written into the terminal', (await screen()).indexOf('There is no working mode') >= 0, JSON.stringify(await screen()));

  await page.click('.rt-x'); await wait(200);
  await ev(() => rtOpen()); await wait(500);
  const a = await ph();
  check('opening the screen again after a failed start tries to start again', a.starts.length === 2, 'starts ' + a.starts.length);
  check('and the screen is a running attempt, not a dead one', await ev(() => rt.state === 'starting'), await ev(() => rt.state));
  await ev(() => window.__notice(true)); await wait(150);
  await page.keyboard.type('ls'); await wait(100);
  check('keys reach the new session', (await ph()).writes.join('') === 'ls', JSON.stringify((await ph()).writes));

  // a shell that ended after running is not restarted by opening the screen
  await ev(() => onPtyExit('rt', 0, window.__ph.cur.n)); await wait(100);
  await page.click('.rt-x'); await wait(200);
  await ev(() => rtOpen()); await wait(400);
  check('a shell that ended is not restarted by opening the screen (it waits for Enter)', (await ph()).starts.length === 2 && await ev(() => rt.state === 'exited'), 'starts ' + (await ph()).starts.length);
  check('its banner is still on the screen', (await screen()).indexOf('process ended') >= 0, JSON.stringify(await screen()));

  // the bridge refusing the start outright ("error: ...") is a failed start too
  await ev(() => { window.AndroidBridge.ptyStart = () => 'error: no helper'; rtRestart(); }); await wait(200);
  check('a start the phone refuses at once also shows its reason in the terminal', (await screen()).indexOf('no helper') >= 0, JSON.stringify(await screen()));
  let n = 0;
  await ev(() => { window.AndroidBridge.ptyStart = (id, backend, rows, cols) => { window.__ph.starts.push({ backend, rows, cols }); window.__ph.cur = { n: window.__ph.starts.length }; return 'started:' + window.__ph.starts.length; }; });
  n = (await ph()).starts.length;
  await page.click('.rt-x'); await wait(200);
  await ev(() => rtOpen()); await wait(400);
  check('and opening the screen again tries again', (await ph()).starts.length === n + 1, 'starts ' + (await ph()).starts.length + ' was ' + n);
  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
