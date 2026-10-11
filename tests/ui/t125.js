// Terminal: Full screen. The button makes the Terminal pane cover the whole app (header and tabs too), the screen takes the room above the input and
// the extra keys, the height follows the on-screen keyboard, Back and the same button come out of it, and leaving the tab does too.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await tx.install(page, { real: false });
  await page.addInitScript(() => { window.__tx.info.termux = { installed: false, permission: false }; });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (f, a) => page.evaluate(f, a);
  await ev(() => switchView('terminal')); await sleep(500);
  const geo = () => ev(() => {
    const r = id => { const e = document.querySelector(id); const b = e.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height), w: Math.round(b.width), left: Math.round(b.left) }; };
    const sc = document.querySelector('#txScreens .tx-screen:not([style*="display: none"])') || document.querySelector('#txScreens .tx-screen');
    const sb = sc.getBoundingClientRect();
    return { pane: r('#txPaneTerminal'), screen: { h: Math.round(sb.height), top: Math.round(sb.top), bottom: Math.round(sb.bottom) }, input: r('.tx-inputline'), keys: r('#txKeys'), btn: document.getElementById('txFullBtn').innerText, pressed: document.getElementById('txFullBtn').getAttribute('aria-pressed') };
  });
  const before = await geo();
  check('the tools row has a Full screen button', before.btn === 'Full screen' && before.pressed === 'false');
  await ev(() => txFullToggle()); await sleep(200);
  let g = await geo();
  check('full screen: the pane covers the whole window (top to bottom, side to side)', g.pane.top === 0 && g.pane.h === 860 && g.pane.w === 400 && g.pane.left === 0, JSON.stringify(g.pane));
  check('the screen got most of the room, more than before', g.screen.h > before.screen.h + 30 && g.screen.h > 450 && Math.abs(g.screen.bottom - g.input.top) < 16, JSON.stringify([before.screen.h, g.screen.h]));
  check('the input line and the extra keys sit below the screen, inside the window', g.input.top >= g.screen.bottom - 1 && g.keys.top >= g.input.top && g.keys.bottom <= 860, JSON.stringify(g));
  check('the button now says Exit full screen', g.btn === 'Exit full screen' && g.pressed === 'true');
  check('the header of the app is behind it (nothing of the app shows above)', await ev(() => { const e = document.elementFromPoint(200, 4); return !!e.closest('#txPaneTerminal'); }));
  await page.setViewportSize({ width: 400, height: 520 }); await sleep(250);
  g = await geo();
  check('when the keyboard takes room (the window gets shorter) the pane gets shorter with it, the input stays in view', g.pane.h === 520 && g.input.bottom <= 520 && g.keys.bottom <= 520, JSON.stringify(g));
  await page.setViewportSize({ width: 400, height: 860 }); await sleep(250);
  // typing still works in full screen
  await ev(() => { document.getElementById('txInput').value = 'echo hi'; });
  check('the input is usable in full screen', await ev(() => document.getElementById('txInput').value === 'echo hi' && document.elementFromPoint(100, document.getElementById('txInput').getBoundingClientRect().top + 5).closest('.tx-inputline') !== null));
  await ev(() => { backNavigate(); }); await sleep(150);
  g = await geo();
  check('Back comes out of full screen first (and does not leave the tab)', g.btn === 'Full screen' && g.pane.h < 860 && (await ev(() => currentViewName())) === 'terminal', JSON.stringify(g.pane));
  await ev(() => txFullToggle()); await sleep(100);
  await ev(() => txShowPane('console')); await sleep(100);
  check('going to the ADB Console pane leaves full screen', (await ev(() => !document.getElementById('txPaneTerminal').classList.contains('tx-full'))));
  await ev(() => txShowPane('terminal')); await sleep(100);
  await ev(() => txFullToggle()); await sleep(100);
  await ev(() => switchView('apps')); await sleep(200);
  check('leaving the tab leaves full screen, and the page scrolls again', await ev(() => !document.getElementById('txPaneTerminal').classList.contains('tx-full') && !document.body.classList.contains('tx-full-on')));
  await ev(() => switchView('terminal')); await sleep(300);
  await ev(() => txFullToggle()); await sleep(100);
  await ev(() => { document.getElementById('txSettingsBtn').click(); }); await sleep(250);
  check('the settings sheet opens above the full-screen terminal', await ev(() => { const m = document.getElementById('txSettingsModal'); const e = document.elementFromPoint(200, 600); return m.classList.contains('show') && !!e.closest('#txSettingsModal'); }));
  await page.screenshot({ path: 'terminal_full.png' });
  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
