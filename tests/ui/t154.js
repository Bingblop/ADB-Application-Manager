// The real terminal: a notice from an older start must not reach a newer one. The phone tags the start, output and end notices of a session with the number of its start
// (ptyStart answers "started:N"); a notice queued before a restart can still run after it, because the page was busy inside ptyClose/ptyStart when the phone decided it was
// current. This runs the page's handlers with stale and current numbers: a stale output is not drawn and not acknowledged, a stale end or start does not change the state,
// and a notice with the current number still works. Without a number (older phone code) a notice is accepted as before.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__n = 0; window.__acks = []; window.__closes = 0;
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; }, getWorkingMode() { return '{}'; },
      ptyStart() { return 'started:' + (100 + ++window.__n); }, ptyWrite() {}, ptyResize() {}, ptyAck(id, n) { window.__acks.push(n); }, ptyClose() { window.__closes++; }, ptyInfo() { return '{"helper":true}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const b64 = s => Buffer.from(s).toString('base64');
  const screen = () => ev(() => { const t = rt.term.buffer.active; const out = []; for (let i = 0; i < t.length; i++) { const l = t.getLine(i); if (l) out.push(l.translateToString(true)); } return out.join('\n'); });

  await ev(() => switchView('terminal')); await wait(500);
  await page.click('#txRealBtn'); await wait(800);
  check('the first start is numbered', await ev(() => rt.token === '101'), await ev(() => String(rt.token)));
  await ev(() => onPtyStarted('rt', { ok: true }, 101));
  check('the start notice of the running start is taken', await ev(() => rt.state === 'running'));
  await ev(([d]) => onPtyData('rt', d, 101), [b64('first-session-output\r\n')]); await wait(200);
  check('output of the running start is drawn', /first-session-output/.test(await screen()));
  check('and acknowledged', await ev(() => window.__acks.length === 1));

  // restart: the new start is number 102
  await ev(() => rtRestart()); await wait(300);
  check('a restart is numbered again', await ev(() => rt.token === '102'), await ev(() => String(rt.token)));
  const acks0 = await ev(() => window.__acks.length);
  await ev(([d]) => onPtyData('rt', d, 101), [b64('STALE-OUTPUT\r\n')]); await wait(200);
  check('output of the older start is not drawn in the new terminal', !/STALE-OUTPUT/.test(await screen()));
  check('and not acknowledged', await ev(n => window.__acks.length === n, acks0));
  await ev(() => { rt.state = 'starting'; onPtyExit('rt', 0, 101); });
  check('the end of the older start does not end the new one', await ev(() => rt.state === 'starting'));
  await ev(() => onPtyStarted('rt', { ok: false, message: 'old failure' }, 101));
  check('a failure of the older start does not fail the new one', await ev(() => rt.state === 'starting'));
  await ev(() => onPtyStarted('rt', { ok: true }, 102));
  check('the start notice of the new start is taken', await ev(() => rt.state === 'running'));
  await ev(([d]) => onPtyData('rt', d, 102), [b64('second-session-output\r\n')]); await wait(200);
  check('output of the new start is drawn', /second-session-output/.test(await screen()));
  await ev(() => onPtyExit('rt', 3, 102));
  check('its end is taken', await ev(() => rt.state === 'exited') && /process ended \(3\)/.test(await screen()));

  // a close: nothing from the closed start arrives afterwards
  await ev(() => { rtEnd(); });
  const acks1 = await ev(() => window.__acks.length);
  await ev(([d]) => onPtyData('rt', d, 102), [b64('AFTER-CLOSE\r\n')]); await wait(200);
  check('after a close, late output of that start is dropped', !/AFTER-CLOSE/.test(await screen()) && await ev(n => window.__acks.length === n, acks1));
  // a restart between the write and its callback: the old acknowledgement is not sent
  await ev(() => rtStart()); await wait(200);
  const tk = await ev(() => rt.token);
  await ev(([d]) => { onPtyData('rt', d, rt.token); rtEnd(); }, [b64('x'.repeat(50) + '\r\n')]); await wait(300);
  check('an acknowledgement for output shown after a close is not sent to the next session', await ev(n => window.__acks.length === n, acks1), tk);

  // no number (older phone code): accepted
  await ev(() => { rtRestart(); }); await wait(300);
  await ev(([d]) => onPtyData('rt', d), [b64('untagged-output\r\n')]); await wait(200);
  check('a notice without a number is still accepted', /untagged-output/.test(await screen()));
  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
