// The real terminal: keys and a resize sent while the session is still starting. The phone stores a session only after the process has been spawned, so keys and a resize sent
// before the start notice are dropped there: the page now holds the keys while the session is starting and, when the start succeeds, sends the terminal's current size and then
// the held keys in the order they were typed (a failed start, a restart and Close drop them).
// The mock phone below behaves like the Java side: ptyWrite / ptyResize reach a session only when it has been published (the start notice sent) and is still the current one.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__ph = { starts: [], closes: 0, cur: null, got: [], lost: 0 };
    window.__publish = (ok, message) => { const c = window.__ph.cur; if (!c) return; if (ok) c.published = true; onPtyStarted('rt', ok ? { ok: true } : { ok: false, message: message || 'no' }, c.n); };
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; }, getWorkingMode() { return '{}'; },
      copyToClipboard() {}, getClipboardText() { return 'clip'; },
      ptyStart(id, backend, rows, cols) { const p = window.__ph; const n = p.starts.push({ backend, rows, cols }); p.cur = { n, published: false }; return 'started:' + n; },
      ptyWrite(id, b64) { const p = window.__ph; if (p.cur && p.cur.published) p.got.push({ w: atob(b64) }); else p.lost++; },
      ptyResize(id, r, c) { const p = window.__ph; if (p.cur && p.cur.published) p.got.push({ r, c }); else p.lost++; },
      ptyAck() {}, ptyClose() { window.__ph.closes++; window.__ph.cur = null; }, ptyInfo() { return '{"helper":true}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const ph = () => ev(() => JSON.parse(JSON.stringify(window.__ph)));

  // ---- (1) keys and a resize during the start
  await ev(() => switchView('terminal')); await wait(500);
  await page.click('#txRealBtn'); await wait(700);
  check('the session is starting (not published yet)', await ev(() => rt.state === 'starting' && window.__ph.starts.length === 1 && !window.__ph.cur.published));
  await page.setViewportSize({ width: 400, height: 560 }); await wait(400);          // the keyboard comes up: the terminal gets fewer rows
  await ev(() => rt.term.focus()); await page.keyboard.type('ls'); await page.click('#rtKeys button[data-rk=up]'); await wait(150);
  const size = await ev(() => ({ r: rt.term.rows, c: rt.term.cols }));
  const st1 = await ph();
  check('the terminal is now smaller than when the session was asked for', size.r < st1.starts[0].rows, size.r + ' vs ' + st1.starts[0].rows);
  check('nothing was handed to the phone as a write while it was starting (the phone would have dropped it)', st1.got.length === 0, JSON.stringify(st1.got));
  await ev(() => window.__publish(true)); await wait(200);
  const st2 = await ph();
  const writes = st2.got.filter(x => x.w !== undefined).map(x => x.w);
  const resizes = st2.got.filter(x => x.r !== undefined);
  check('after the start notice the keys typed meanwhile arrive, in order', writes.join('') === 'ls\x1b[A', JSON.stringify(writes));
  check('and the current size is sent', resizes.length > 0 && resizes[resizes.length - 1].r === size.r && resizes[resizes.length - 1].c === size.c, JSON.stringify(resizes) + ' term ' + size.r + 'x' + size.c);
  check('the size goes before the keys', st2.got.length > 0 && st2.got[0].r !== undefined, JSON.stringify(st2.got));
  const n0 = st2.got.length;
  await page.keyboard.type('x'); await wait(100);
  check('once running, keys go straight through', (await ph()).got.length === n0 + 1);

  // the held keys are limited
  await ev(() => rtRestart()); await wait(200);
  await ev(() => { window.__ph.got = []; for (let i = 0; i < 100; i++) rtOnData('x'.repeat(100)); window.__publish(true); }); await wait(150);
  const big = (await ph()).got.filter(x => x.w !== undefined).map(x => x.w).join('').length;
  check('only a few KB of keys are held (a long paste during the start is cut, not queued without end)', big > 0 && big <= 4096, String(big));

  // a failed start drops them
  await ev(() => rtRestart()); await wait(200);
  await ev(() => { rtOnData('abc'); window.__ph.got = []; window.__publish(false, 'no working mode'); }); await wait(150);
  check('a failed start sends nothing', (await ph()).got.length === 0 && await ev(() => rt.state === 'exited'), JSON.stringify((await ph()).got));
  await ev(() => rtRestart()); await wait(200);
  await ev(() => window.__publish(true)); await wait(150);
  check('and the keys of the failed start are not sent into the next session', (await ph()).got.filter(x => x.w !== undefined).length === 0, JSON.stringify((await ph()).got));

  // closing the screen and starting again drops them too
  await ev(() => rtRestart()); await wait(200);
  await ev(() => rtOnData('old')); await page.click('.rt-x'); await wait(200);
  await ev(() => { window.__ph.got = []; rtRestart(); }); await wait(200);
  await ev(() => window.__publish(true)); await wait(150);
  check('keys held when the screen was closed are not sent into the session started afterwards', (await ph()).got.filter(x => x.w !== undefined).length === 0, JSON.stringify((await ph()).got));
  await ev(() => rtOpen()); await wait(300);

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
