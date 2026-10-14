// Terminal: the input line and the extra-keys row sit above the bottom navigation bar without scrolling. The screen was 50vh tall, which put the
// extra keys (and part of the input) under the bar at 320 x 800 and 360 x 800. Now the screen is as tall as the window allows with both rows above
// the bar: measured at 320 x 800, 360 x 800, 360 x 700 and 400 x 860, in German, in "Top bar only" (no bottom bar: above the window's edge), after the
// window changes size, after full screen and back. Full screen keeps its own layout (the bar goes away, the keys sit inside the window), and a short
// window keeps a 160 px screen and scrolls as before.
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const open = async ({ width, height, bottomNav = true, lang = 'en' }) => {
    const page = await b.newPage({ viewport: { width, height } });
    page.bottomNav = bottomNav;
    page.on('pageerror', e => errors.push(e.message));
    await tx.install(page, { real: false });
    await page.addInitScript(() => { window.__tx.info.termux = { installed: false, permission: false }; });
    await page.goto(PAGE); await page.waitForTimeout(400);
    if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(300); }
    await page.evaluate(() => switchView('terminal')); await page.waitForTimeout(500);
    return page;
  };
  const geo = page => page.evaluate(() => {
    const r = s => { const e = document.querySelector(s); const x = e.getBoundingClientRect(); return { top: Math.round(x.top), bottom: Math.round(x.bottom), h: Math.round(x.height) }; };
    const sc = document.querySelector('#txScreens .tx-screen:not([style*="display: none"])') || document.querySelector('#txScreens .tx-screen');
    const nav = document.getElementById('bnav'), navShown = nav && getComputedStyle(nav).display !== 'none' && !document.documentElement.classList.contains('bnav-away');
    return { screen: Math.round(sc.getBoundingClientRect().height), input: r('.tx-inputline'), keys: r('#txKeys'), navTop: navShown ? Math.round(nav.getBoundingClientRect().top) : null, H: innerHeight, scrollY: Math.round(scrollY), docW: document.documentElement.scrollWidth, W: innerWidth };
  });
  const above = (g) => g.keys.bottom <= (g.navTop === null ? g.H : g.navTop) && g.input.top >= 0;

  for (const [width, height] of [[320, 800], [360, 800], [360, 700], [400, 860]]) {
    const page = await open({ width, height });
    const g = await geo(page);
    const tag = width + ' x ' + height + ': ';
    check(tag + 'the bottom bar is shown and the page is at the top', g.navTop !== null && g.scrollY === 0, JSON.stringify(g));
    check(tag + '  the input line and the extra keys are above the bar, the screen keeps at least 160 px', above(g) && g.keys.bottom >= g.navTop - 24 && g.screen >= 160, JSON.stringify(g));
    check(tag + '  no sideways scroll', g.docW <= g.W);
    if (width === 360 && height === 800) {
      await page.evaluate(() => txFullToggle(true)); await page.waitForTimeout(300);
      const f = await geo(page);
      check(tag + 'full screen: the bar goes away, the screen grows, the keys stay inside the window', f.navTop === null && f.screen > g.screen + 100 && f.keys.bottom <= f.H, JSON.stringify(f));
      await page.evaluate(() => txFullToggle(false)); await page.waitForTimeout(300);
      const back = await geo(page);
      check(tag + 'back from full screen: the same fit as before', above(back) && Math.abs(back.screen - g.screen) <= 1, JSON.stringify(back));
      await page.setViewportSize({ width: 360, height: 720 }); await page.waitForTimeout(400);
      const small = await geo(page);
      check(tag + 'the window gets shorter (360 x 720): the screen shrinks and the keys stay above the bar', above(small) && small.screen < g.screen, JSON.stringify(small));
      await page.evaluate(() => { const s = document.getElementById('txShell'); s.value = 'app'; txPickShell('app'); }); await page.waitForTimeout(300);
      check(tag + 'another shell\'s screen has the same size', Math.abs((await geo(page)).screen - small.screen) <= 1);
    }
    await page.close();
  }
  // German: the pickers above the screen wrap differently, the fit follows
  {
    const page = await open({ width: 360, height: 760, lang: 'de' });
    const g = await geo(page);
    check('de 360 x 760: the input line and the extra keys are above the bar', above(g) && g.keys.bottom >= g.navTop - 24, JSON.stringify(g));
    await page.close();
  }
  // Top bar only: no bottom bar, the keys end above the window's bottom edge
  {
    const page = await open({ width: 360, height: 760, bottomNav: false });
    const g = await geo(page);
    check('top bar only, 360 x 760: no bottom bar, the extra keys end inside the window', g.navTop === null && above(g) && g.keys.bottom >= g.H - 24, JSON.stringify(g));
    await page.close();
  }
  // a short window: the 160 px floor, and the page scrolls to the keys as before
  {
    const page = await open({ width: 360, height: 560 });
    const g = await geo(page);
    check('360 x 560: the screen keeps its 160 px floor', g.screen === 160, JSON.stringify(g));
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
