// The look of the one Theme screen (v7.22.0, ColorBlendr parity): the sticky action bar stays at the bottom of the viewport (above the bottom
// navigation) while the panel scrolls, locked or live; at 320 px nothing overflows sideways (the locked rows, the segments, the sliders, the
// accents, the style grid); the locked rows are the Root Features ones (the Locked button, the lock glyph); dark and light; every control has
// an accessible name; a tap on a locked row's Locked button does nothing; Apply and Reset disabled while busy and the bar still in place.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (width, dark, root) => {
    const page = await b.newPage({ viewport: { width, height: 720 } });
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    page.on('dialog', d => d.accept());
    await page.addInitScript(sdb.initScript);
    await page.addInitScript(ovl.initScript);
    await page.addInitScript(([d, r]) => { window.__sdk = 35; window.__dark = d; window.__root = r; window.__wallpaper = ['4F8A5B']; }, [dark, root]);
    await page.goto(PAGE); await page.waitForTimeout(500);
    await page.evaluate(() => switchView('overlays')); await page.waitForTimeout(400);
    return page;
  };
  const geometry = p => p.evaluate(() => {
    const bar = document.getElementById('ovlBar').getBoundingClientRect(), nav = document.querySelector('.bnav, #bottomNav, nav.bottom-nav');
    const navTop = nav && getComputedStyle(nav).display !== 'none' ? nav.getBoundingClientRect().top : window.innerHeight;
    const wide = Array.from(document.querySelectorAll('#ovlThemePanel *')).filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (r.right > window.innerWidth + 1 || r.left < -1); }).map(e => (e.id || e.className || e.tagName).toString().slice(0, 40));
    return { barTop: bar.top, barBottom: bar.bottom, navTop, inner: window.innerHeight, wide, scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth, scrollY: window.scrollY };
  });

  // 1) 320 px, dark, the shell: locked
  let page = await open(320, true, false);
  let g = await geometry(page);
  check('1. 320 px, locked: nothing overflows sideways and the page has no horizontal scroll', g.wide.length === 0 && g.scrollW <= g.innerW, JSON.stringify(g));
  check('   the bar sits at the bottom of the viewport, above the bottom navigation, at the top of the panel', g.barBottom <= g.navTop + 1 && g.barBottom <= g.inner && g.barTop > 0, JSON.stringify(g));
  await page.evaluate(() => window.scrollTo(0, 900)); await page.waitForTimeout(200);
  const g2 = await geometry(page);
  check('   scrolled 900 px, the bar is still there (sticky), at the same place', g2.scrollY > 0 && Math.abs(g2.barBottom - g.barBottom) < 2, JSON.stringify([g.barBottom, g2.barBottom, g2.scrollY]));
  const locked = await page.evaluate(() => Array.from(document.querySelectorAll('#ovlThemePanel .rf-row.is-locked')).map(r => ({ btn: r.querySelector('.op-mode-btn.locked').innerText, disabled: r.querySelector('.op-mode-btn.locked').disabled, svg: !!r.querySelector('.pm-lock svg'), glyph: r.querySelector('.rf-name').textContent.includes('🔒') })));
  check('   seven locked rows of the Root Features kind: a disabled Locked button, the lock icon and the glyph', locked.length === 7 && locked.every(x => x.btn === 'Locked' && x.disabled && x.svg && x.glyph), JSON.stringify(locked));
  await page.locator('#ovlSwitches .rf-row.is-locked .op-mode-btn').first().click({ force: true }); await page.waitForTimeout(100);
  check('   a tap on Locked does nothing', await page.evaluate(() => window.__ovlCalls.fab.length === 0 && !document.getElementById('privilegeModal').classList.contains('show')));
  await page.screenshot({ path: 'ovl_screen_320_locked_dark.png', fullPage: true });
  await page.close();

  // 2) 320 px, light, Root: live
  page = await open(320, false, true);
  g = await geometry(page);
  check('2. 320 px, live, light: nothing overflows sideways', g.wide.length === 0 && g.scrollW <= g.innerW, JSON.stringify(g.wide));
  const names = await page.evaluate(() => Array.from(document.querySelectorAll('#ovlThemePanel button, #ovlThemePanel input')).filter(e => e.offsetParent !== null && !e.closest('details:not([open])')).map(e => ({ tag: e.tagName, id: e.id, name: (e.getAttribute('aria-label') || (e.getAttribute('aria-labelledby') ? Array.from(e.getAttribute('aria-labelledby').split(' ')).map(i => (document.getElementById(i) || {}).innerText || '').join(' ') : '') || e.innerText || (e.labels && e.labels[0] ? e.labels[0].innerText : '') || e.title || '').replace(/\s+/g, ' ').trim() })).filter(e => !e.name));
  check('   every visible button and input has an accessible name', names.length === 0, JSON.stringify(names));
  const roles = await page.evaluate(() => ({ styles: document.getElementById('ovlStyles').getAttribute('role'), spec: document.getElementById('ovlSpecSeg').getAttribute('role'), con: document.getElementById('ovlContrastSeg').getAttribute('role'), adj: document.querySelector('#ovlAdjust .ovl-adj').getAttribute('role'), acc: document.querySelector('#ovlAccents .ovl-acc').getAttribute('role'), sw: Array.from(document.querySelectorAll('#ovlSwitches .sdb-sw')).map(x => x.getAttribute('role')).join() }));
  check('   the groups carry their roles', JSON.stringify(roles) === JSON.stringify({ styles: 'radiogroup', spec: 'radiogroup', con: 'radiogroup', adj: 'group', acc: 'group', sw: 'switch,switch,switch' }), JSON.stringify(roles));
  const colours = await page.evaluate(() => { const bar = getComputedStyle(document.getElementById('ovlBar')); return { position: bar.position, border: bar.borderTopColor, bg: bar.backgroundColor, locked: getComputedStyle(document.querySelector('#ovlStyles .ovl-style.is-locked')).opacity }; });
  check('   the bar is sticky with the accent border on the sheet colour; a greyed style is faded', colours.position === 'sticky' && colours.border !== 'rgba(0, 0, 0, 0)' && colours.bg !== 'rgba(0, 0, 0, 0)' && +colours.locked < 1, JSON.stringify(colours));
  await page.screenshot({ path: 'ovl_screen_320_live_light.png', fullPage: true });
  // busy: both buttons disabled, the bar in place
  await page.evaluate(() => { window.__delay = 600; });
  await page.locator('#ovlApplyBtn').click(); await page.waitForTimeout(80);
  const busy = await page.evaluate(() => ({ apply: document.getElementById('ovlApplyBtn').disabled, reset: document.getElementById('ovlResetBtn').disabled, text: document.getElementById('ovlApplyBtn').innerText, sticky: getComputedStyle(document.getElementById('ovlBar')).position }));
  check('   while busy Apply reads Applying… and both buttons are disabled, the bar in place', busy.apply && busy.reset && busy.text === 'Applying…' && busy.sticky === 'sticky', JSON.stringify(busy));
  await page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).catch(() => {});
  await page.close();

  // 3) 360 px, dark, Root: the whole screen
  page = await open(360, true, true);
  await page.screenshot({ path: 'ovl_screen_360_live_dark.png', fullPage: true });
  g = await geometry(page);
  check('3. 360 px live: no overflow, the bar at the bottom', g.wide.length === 0 && g.barBottom <= g.inner, JSON.stringify(g.wide));
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
