// The navigation rail (a screen 720 px or wider, such as a phone on its side) scrolls with a finger (PG1-1): it had touch-action: none from the bottom
// bar, so on a short screen the slots below the fold (More last) could not be reached. A hold that moves still cancels, and once a hold has opened the
// menu the finger still slides onto an entry without scrolling the rail.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const W = 740, H = 300;
  const open = async () => {
    const ctx = await b.newContext({ viewport: { width: W, height: H }, hasTouch: true, isMobile: true });
    const page = await ctx.newPage();
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: { bnav_tip: 'true' } });
    await page.goto(PAGE); await page.waitForTimeout(700);
    return { ctx, page, cdp: await ctx.newCDPSession(page) };
  };
  const rail = page => page.evaluate(() => { const nav = document.getElementById('bnav'), more = nav.querySelector('.bn-item[data-more]').getBoundingClientRect(); return { top: nav.scrollTop, overflow: nav.scrollHeight > nav.clientHeight + 1, moreIn: more.bottom <= innerHeight + 1 && more.top >= 0, rail: nav.getBoundingClientRect().width < 100 }; });
  const slotAt = (page, tab) => page.evaluate(t => { const r = document.querySelector('#bnav .bn-item[data-tab="' + t + '"]').getBoundingClientRect(); return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; }, tab);
  const touch = (cdp, type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
  const menuOpen = page => page.evaluate(() => !!document.getElementById('bnavMenu'));

  // ---------------------------------------------------------------- 1. a swipe scrolls the rail to More
  let { ctx, page, cdp } = await open();
  let r = await rail(page);
  check('1. at ' + W + 'x' + H + ' the bar is a rail, taller than the screen, More is below the fold', r.rail && r.overflow && !r.moreIn, JSON.stringify(r));
  await touch(cdp, 'touchStart', 38, H - 50);
  for (let i = 1; i <= 12; i++) { await touch(cdp, 'touchMove', 38, H - 50 - i * 15); await page.waitForTimeout(16); }
  await touch(cdp, 'touchEnd'); await page.waitForTimeout(400);
  r = await rail(page);
  check('   a finger swipe up on the rail scrolls it and More comes into view', r.top > 0 && r.moreIn, JSON.stringify(r));
  await ctx.close();

  // ---------------------------------------------------------------- 2. a hold that moves is cancelled
  ({ ctx, page, cdp } = await open());
  let s = await slotAt(page, 'installer');
  await touch(cdp, 'touchStart', s.x, s.y);
  await page.waitForTimeout(80);
  for (let i = 1; i <= 4; i++) { await touch(cdp, 'touchMove', s.x, s.y - i * 12); await page.waitForTimeout(16); }
  await page.waitForTimeout(600);
  check('2. a hold whose finger moves away opens no menu', !(await menuOpen(page)));
  await touch(cdp, 'touchEnd'); await page.waitForTimeout(150);
  check('   and no tab change follows', await page.evaluate(() => currentViewName()) === 'apps');
  await ctx.close();

  // ---------------------------------------------------------------- 3. a hold opens the menu; sliding onto an entry runs it, the rail stays put
  ({ ctx, page, cdp } = await open());
  s = await slotAt(page, 'installer');
  await touch(cdp, 'touchStart', s.x, s.y);
  await page.waitForTimeout(650);
  check('3. a still hold opens the menu beside the slot', await menuOpen(page));
  const e = await page.evaluate(() => { const all = document.querySelectorAll('#bnavMenu .bn-sc'), it = all[all.length - 1]; const q = it.getBoundingClientRect(); return { x: Math.round(q.left + q.width / 2), y: Math.round(q.top + q.height / 2), label: it.innerText.trim() }; });
  const steps = 8;
  for (let i = 1; i <= steps; i++) { await touch(cdp, 'touchMove', s.x, Math.round(s.y + (e.y - s.y) * i / steps)); await page.waitForTimeout(16); }          // first straight down the rail (the way a scroll goes) ...
  for (let i = 1; i <= steps; i++) { await touch(cdp, 'touchMove', Math.round(s.x + (e.x - s.x) * i / steps), e.y); await page.waitForTimeout(16); }          // ... then across onto the entry
  const hot = await page.evaluate(() => { const h = document.querySelector('#bnavMenu .bn-sc.hot'); return h ? h.innerText.trim() : null; });
  const top = await page.evaluate(() => document.getElementById('bnav').scrollTop);
  check('   sliding down and then onto the last entry lights it and the rail does not scroll', hot === e.label && top === 0, JSON.stringify({ hot, label: e.label, top }));
  await touch(cdp, 'touchEnd'); await page.waitForTimeout(300);
  check('   letting go runs it (the APK Installer/Updater tab opens) and closes the menu', await page.evaluate(() => currentViewName()) === 'installer' && !(await menuOpen(page)), await page.evaluate(() => currentViewName()));
  await ctx.close();

  check('no page errors', !errors.length, JSON.stringify(errors));
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
