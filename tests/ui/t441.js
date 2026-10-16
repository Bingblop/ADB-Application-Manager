// v7.14.0: Console is the home of the shells: its segments are "ADB Shell | Terminal" (the Terminal tab moved in whole: the real terminal, the Coding Agents,
// full screen). The segment used last is kept ('console_seg'), switchView('terminal') and every way that opened the Terminal tab open Console on its Terminal
// segment, the long-press menu of Console lists both segments and the Terminal's entries, and the Terminal wears the Console's look: the screen is the card
// colour with the theme's text, the input row and the keys are the Console's field and chips, ANSI colours come from the theme (light and dark sets), and the
// real terminal (xterm) gets the same background, text, cursor and palette.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async kv => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await tx.install(page, { real: false });
    await page.addInitScript(kv => { window.__tx.info.termux = { installed: false, permission: false }; Object.assign(window.__kv = window.__kv || {}, kv || {}); }, kv || {});
    await page.goto(PAGE); await page.waitForTimeout(350);
    return page;
  };
  let page = await open();
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);
  const state = () => ev(() => ({ view: currentViewName(), seg: cnSeg, shell: !document.getElementById('cnSegShell').hidden, term: !document.getElementById('cnSegTerm').hidden,
    tabs: [...document.querySelectorAll('#cnTabs .cn-tab')].map(t => t.textContent.trim() + ':' + t.getAttribute('aria-selected')).join(), kv: window.__kv.console_seg,
    active: (document.querySelector('.tab-btn.active') || {}).dataset ? document.querySelector('.tab-btn.active').dataset.tab : null, paneShown: !!document.getElementById('txPaneTerminal').offsetParent }));

  await ev(() => switchView('console')); await sleep(200);
  let s = await state();
  check('1. Console opens on ADB Shell, its segments are "ADB Shell | Terminal"', s.view === 'console' && s.seg === 'shell' && s.shell && !s.term && s.tabs === 'ADB Shell:true,Terminal:false' && !s.paneShown, JSON.stringify(s));
  check('   the Terminal pane, the Coding Agents and full screen live in Console now (no Terminal tab, no view-terminal)', await ev(() => !!document.querySelector('#view-console #cnSegTerm #txPaneTerminal #txAgent') && !!document.querySelector('#view-console #txFullBtn') && !document.getElementById('view-terminal') && !document.querySelector('.tab-btn[data-tab="terminal"]')));
  await page.click('#cnTabTerm'); await sleep(400);
  s = await state();
  check('2. a tap on Terminal shows the terminal (and starts it) and remembers the choice', s.seg === 'term' && s.term && !s.shell && s.paneShown && s.tabs === 'ADB Shell:false,Terminal:true' && s.kv === '"term"' && await ev(() => txOpened), JSON.stringify(s));
  const ind = await ev(() => Math.round(document.querySelector('#cnTabs .cn-ind').getBoundingClientRect().left - document.getElementById('cnTabTerm').getBoundingClientRect().left));
  check('   the underline moved under Terminal', Math.abs(ind) <= 1, ind);
  await ev(() => switchView('apps')); await sleep(100); await ev(() => switchView('console')); await sleep(300);
  s = await state();
  check('3. leaving and coming back opens Console on Terminal again', s.seg === 'term' && s.term && s.paneShown, JSON.stringify(s));
  await page.click('#cnTabShell'); await sleep(200);
  await ev(() => switchView('apps')); await sleep(100);
  const ok = await ev(() => switchView('terminal')); await sleep(300);
  s = await state();
  check('4. switchView(\'terminal\') (links, shortcuts, Termux setup, agent buttons) opens Console on its Terminal segment', ok === true && s.view === 'console' && s.seg === 'term' && s.active === 'console' && s.paneShown, JSON.stringify(s));
  await ev(() => { switchView('apps'); aiOpenAgentSetup(); }); await sleep(300);
  check('   "Open it" from the Default Ask Agent note does too', (await state()).seg === 'term' && (await state()).view === 'console');

  // full screen goes with the segment and with the tab
  await ev(() => txFullToggle(true)); await sleep(150);
  await ev(() => cnShowSeg('shell')); await sleep(150);
  check('5. full screen ends when the ADB Shell segment is chosen', await ev(() => !txFullOn && !document.getElementById('txPaneTerminal').classList.contains('tx-full') && cnSeg === 'shell'));
  await ev(() => { cnShowSeg('term'); }); await sleep(200); await ev(() => txFullToggle(true)); await sleep(150);
  await ev(() => switchView('files')); await sleep(150);
  check('   and when another tab is opened', await ev(() => !txFullOn && currentViewName() === 'files'));

  // the long-press menu of Console
  const sc = await ev(() => ({ ids: TAB_SHORTCUTS.console.map(i => i.id).join(), shown: bnavScList('console').map(i => bnavScLabel(i)).join(), terminal: 'terminal' in TAB_SHORTCUTS }));
  check('6. Console\'s long-press menu: both segments, then the Terminal\'s full screen and Coding Agents (Termux setup and settings can be chosen)', sc.ids === 'shell,term,full,agents,termux,settings' && sc.shown === 'ADB Shell,Terminal,Full-screen terminal,Coding Agents' && !sc.terminal, JSON.stringify(sc));
  await ev(() => { switchView('apps'); TAB_SHORTCUTS.console.find(i => i.id === 'agents').run(); }); await sleep(400);
  check('   "Coding Agents" opens Console on Terminal with the agent picker focused', await ev(() => currentViewName() === 'console' && cnSeg === 'term' && document.activeElement.id === 'txAgent'));
  await ev(() => { switchView('apps'); TAB_SHORTCUTS.console.find(i => i.id === 'shell').run(); }); await sleep(250);
  check('   "ADB Shell" opens Console on the shell', await ev(() => currentViewName() === 'console' && cnSeg === 'shell' && !document.getElementById('cnSegShell').hidden));

  // the look
  await ev(() => cnShowSeg('term')); await sleep(300);
  const look = () => ev(() => {
    const cs = getComputedStyle(document.documentElement), v = n => cs.getPropertyValue(n).trim();
    const probe = c => { const e = document.createElement('div'); e.style.color = c; document.body.appendChild(e); const r = getComputedStyle(e).color; e.remove(); return r; };
    const sc = document.querySelector('#txScreens .tx-screen:not([style*="display: none"])') || document.querySelector('#txScreens .tx-screen');
    txWrite(txCurShell(), '\x1b[31mred\x1b[0m plain\n');
    const red = [...sc.querySelectorAll('span')].filter(x => x.textContent === 'red').pop();
    const g = (sel, p) => getComputedStyle(document.querySelector(sel))[p];
    return {
      screenBg: getComputedStyle(sc).backgroundColor === probe(v('--bg-card')), screenFg: getComputedStyle(sc).color === probe(v('--text-main')),
      screenRadius: getComputedStyle(sc).borderTopLeftRadius === '16px', red: red && getComputedStyle(red).color === probe(v('--ansi-1')), redVal: v('--ansi-1'),
      send: g('#txSendBtn', 'backgroundColor') === probe(v('--accent')) && g('#txSendBtn', 'backgroundImage') === 'none',
      input: g('#txInput', 'backgroundColor') === g('#termCmd', 'backgroundColor') && g('#txInput', 'borderTopLeftRadius') === g('#termCmd', 'borderTopLeftRadius'),
      keys: g('#txKeys button', 'color') === probe(v('--accent')) && g('#txKeys button', 'backgroundColor') === probe(v('--bg-surface')),
      tool: g('#txFullBtn', 'color') === probe(v('--accent')) && g('#txFullBtn', 'height') === '32px',
      xterm: (() => { const t = rtTheme(); return t.background === v('--bg-card') && t.foreground === v('--text-main') && t.cursor === v('--accent') && t.red === v('--ansi-1') && t.brightWhite === v('--ansi-15') && /rgba/.test(t.selectionBackground); })()
    };
  });
  const dark = await look();
  check('7. dark: the screen is the card colour with the theme\'s text and 16px corners, like the Console\'s output', dark.screenBg && dark.screenFg && dark.screenRadius, JSON.stringify(dark));
  check('   the Run button is the solid accent (no gradient), the input is the Console\'s field, the keys and tools are its chips', dark.send && dark.input && dark.keys && dark.tool);
  check('   ANSI red is the theme\'s red', dark.red);
  check('   the real terminal (xterm) gets the same background, text, cursor and palette', dark.xterm);
  await ev(() => setAppearance('light')); await sleep(250);
  const light = await look();
  check('8. light: the same, in the light colours (the screen is not black)', light.screenBg && light.screenFg && light.red && light.xterm && light.redVal !== dark.redVal, JSON.stringify(light));
  check('   the red already on the screen took the light red too', await ev(() => { const cs = getComputedStyle(document.documentElement); const spans = [...document.querySelectorAll('#txScreens span')].filter(x => x.textContent === 'red'); const p = document.createElement('div'); p.style.color = cs.getPropertyValue('--ansi-1').trim(); document.body.appendChild(p); const want = getComputedStyle(p).color; p.remove(); return spans.length >= 2 && spans.every(x => getComputedStyle(x).color === want); }));
  await page.close();

  // what was saved
  page = await open({ console_seg: '"term"' });
  await page.evaluate(() => switchView('console')); await page.waitForTimeout(400);
  check('9. saved "term": Console opens on Terminal after a restart', await page.evaluate(() => cnSeg === 'term' && !document.getElementById('cnSegTerm').hidden && txOpened));
  await page.close();
  page = await open({ console_seg: '"log"' });
  await page.evaluate(() => switchView('console')); await page.waitForTimeout(300);
  check('   saved "log" (Live Logcat was the second segment): Console opens on ADB Shell', await page.evaluate(() => cnSeg === 'shell' && !document.getElementById('cnSegShell').hidden && document.getElementById('cnSegTerm').hidden));
  await page.close();

  check('10. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
