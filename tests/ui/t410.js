// v7.14 back to top and the Undo bars. A round up-arrow button (#backToTop) comes in on any screen scrolled down more than one and a
// half screens and scrolls that screen back to the top: the Apps list, the long screens (Settings, Extra Settings, SD Maid, About) and a
// list that scrolls in a box of its own. It is a 48px button with a name, at the right edge above the bottom bar, in the checkmark
// button's column (above it while it is up, above an Undo bar), and it goes while a sheet, the batch sheet, the keyboard (a text field in
// use) or the full-screen terminal is up. The Undo bars last 3 seconds at most (v7.14, the owner's wish; before: 5 s, the installer's 8 s and 6 s, SD Maid's 7 s)
// and stand just above the bottom bar (near the screen's edge when the bar is off), above the checkmark button or the batch sheet while
// those are up; a toast shown with an Undo bar goes above it.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (kv) => {
    const ctx = await b.newContext({ viewport: { width: 400, height: 800 }, hasTouch: true });
    const page = await ctx.newPage();
    page.bottomNav = !(kv && kv.nav_mode === '"top"');          // (the harness puts a page in Top bar only unless told)
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(appsMock.initScript, { dark: true });
    await page.addInitScript(kv => {          // a phone with 200 apps (and, when asked, saved settings)
      const bb = window.AndroidBridge, now = Date.now();
      const apps = Array.from({ length: 200 }, (_, i) => ({ pkg: 'com.example.app' + String(i).padStart(3, '0'), name: 'App ' + String(i).padStart(3, '0'), isSystem: i % 3 === 0, isRunning: i % 7 === 0, isFrozen: i % 11 === 0, isUninstalled: false, isSuspended: false, version: '1.0', installedAt: now - 864e5 * 40, updatedAt: now, apkSize: 5e6 }));
      bb.loadPackages = () => JSON.stringify(apps);
      if (kv) { const load = bb.loadSetting; bb.loadSetting = k => Object.prototype.hasOwnProperty.call(kv, k) ? kv[k] : load(k); }
    }, Object.assign({ nav_mode: '"bottom"' }, kv || {}));          // the bottom bar on (the default of a new install)
    await page.goto(PAGE); await sleep(700);
    await page.evaluate(() => switchView('apps')); await sleep(400);
    return page;
  };
  const st = page => settle(page, "#backToTop").then(() => page.evaluate(() => {
    const btn = document.getElementById('backToTop'), r = btn.getBoundingClientRect(), cs = getComputedStyle(btn);
    const nav = document.getElementById('bnav'), nr = nav ? nav.getBoundingClientRect() : null;
    return { show: btn.classList.contains('show'), visible: cs.visibility === 'visible' && +cs.opacity > 0.5, inert: btn.hasAttribute('inert'), w: r.width, h: r.height, top: r.top, bottom: r.bottom, left: r.left, right: r.right,
      vw: innerWidth, vh: innerHeight, navTop: nr && getComputedStyle(nav).display !== 'none' ? nr.top : innerHeight, y: (document.scrollingElement || document.documentElement).scrollTop, label: btn.getAttribute('aria-label') };
  }));
  const scrollTo = async (page, y) => { await page.evaluate(y => window.scrollTo(0, y), y); await sleep(350); };
  const overlap = (a, b) => !(a.bottom <= b.top || b.bottom <= a.top || a.right <= b.left || b.right <= a.left);
  const box = (page, sel) => page.evaluate(sel => { const r = document.querySelector(sel).getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right }; }, sel);
  // where a bar or button comes to rest: it slides in, so wait until it stands still (on a busy machine a slide can take a while)
  async function settle(page, sel) { await page.waitForFunction(sel => !document.querySelector(sel).getAnimations().length, sel, { timeout: 10000 }).catch(() => {}); let prev = ''; for (let i = 0; i < 60; i++) { const cur = JSON.stringify(await box(page, sel)); if (cur === prev) return; prev = cur; await sleep(80); } }
  const rect = async (page, sel) => { await settle(page, sel); return box(page, sel); };

  // ---- 1. the Apps list ----
  let page = await open();
  let s = await st(page);
  check('1. Apps, at the top of a list of 200: no back-to-top button (and it cannot be reached by a screen reader or Tab)', !s.show && !s.visible && s.inert);
  await scrollTo(page, 600);
  s = await st(page);
  check('   less than one and a half screens down: still none', !s.show, JSON.stringify(s));
  await scrollTo(page, 2400);
  s = await st(page);
  check('   three screens down: it comes in', s.show && s.visible && !s.inert, JSON.stringify(s));
  check('   a round button of 44px or more, named "Back to top"', s.w >= 44 && s.h >= 44 && Math.abs(s.w - s.h) < 0.5 && s.label === 'Back to top', JSON.stringify(s));
  await page.screenshot({ path: 'apps-scrolled.png' });
  check('   at the right edge, above the bottom bar, on the screen', s.right <= s.vw && s.vw - s.right <= 32 && s.bottom <= s.navTop - 8 && s.top > s.vh / 2, JSON.stringify(s));
  const html = fs.readFileSync(path.join(REPO, 'assets', 'index.html'), 'utf8');
  check('   it scrolls smoothly, or at once when the phone asks for reduced motion', /prefers-reduced-motion: reduce\)'\)\.matches \? 'auto' : 'smooth'/.test(html));
  await page.click('#backToTop'); await sleep(1500);
  s = await st(page);
  check('   a tap scrolls the list back to the top, and the button goes', s.y === 0 && !s.show, JSON.stringify(s));

  // ---- 2. next to the checkmark button, the batch sheet, a sheet, the keyboard ----
  await scrollTo(page, 2400);
  await page.evaluate(() => toggleSelectPkg(filteredApps[40].pkg)); await sleep(500);
  s = await st(page);
  const fab = await rect(page, '#batchFab');
  check('2. with an app selected the button stands above the round checkmark button, in its column, not on it', s.show && !overlap(s, fab) && s.bottom <= fab.top && Math.abs((s.left + s.right) / 2 - (fab.left + fab.right) / 2) < 2, JSON.stringify([s, fab]));
  await page.screenshot({ path: 'apps-selected.png' });
  await page.evaluate(() => expandBatchPanel()); await sleep(500);
  check('   the batch sheet open: it goes', !(await st(page)).show);
  await page.evaluate(() => collapseBatchPanel()); await sleep(400);
  check('   the sheet closed again: it is back', (await st(page)).show);
  await page.evaluate(() => clearBatchSelectionNow()); await sleep(500);
  s = await st(page);
  check('   the selection cleared: it takes the checkmark button\'s place again', s.show && s.bottom > fab.top + 30, JSON.stringify([s.bottom, fab.top]));
  await page.evaluate(() => openInspector(filteredApps[3].pkg)); await sleep(500);
  check('   an app\'s sheet open: it goes', !(await st(page)).show);
  await page.evaluate(() => closeInspector()); await sleep(500);
  check('   the sheet closed: it is back', (await st(page)).show);
  await page.evaluate(() => { const i = document.getElementById('searchInput') || document.querySelector('#view-apps input[type="search"], #view-apps input[type="text"]'); i.focus({ preventScroll: true }); }); await sleep(400);
  check('   a text field in use (the keyboard is up): it goes', !(await st(page)).show);
  await page.evaluate(() => document.activeElement.blur()); await sleep(400);
  check('   the keyboard down: it is back', (await st(page)).show);
  await page.close();

  // ---- 3. the other long screens, and a list in a box of its own ----
  page = await open();
  for (const v of ['prefs', 'extrasettings', 'sdm', 'about']) {
    // v7.15: SD Maid's explanations and About's reading cards start folded; opened here, so each screen is long again
    await page.evaluate(v => { switchView(v); const view = document.querySelector('.view-content.active');
      view.querySelectorAll('details.sd-more').forEach(d => { d.open = true; }); view.querySelectorAll('.cc.cc-closed').forEach(c => ccApply(c, true, false)); window.scrollTo(0, 0); }, v); await sleep(400);
    const h = await page.evaluate(() => (document.scrollingElement || document.documentElement).scrollHeight);
    await scrollTo(page, 2000);
    const a = await st(page);
    await page.click('#backToTop'); await sleep(1500);
    const z = await st(page);
    check(`3. ${v} (${h > 2800 ? 'a long screen' : 'short: ' + h}): it comes in scrolled down, and takes the screen to the top`, h > 2800 && a.show && z.y === 0 && !z.show, JSON.stringify([a.show, z.y, z.show]));
  }
  await page.evaluate(() => { switchView('settings'); window.scrollTo(0, 0); }); await sleep(300);
  await page.evaluate(() => {          // a list that scrolls in its own box (as the Logcat Viewer's does)
    const box = document.createElement('div'); box.id = 't410box'; box.style.cssText = 'height:70vh; overflow-y:auto';
    box.innerHTML = '<div style="height:5000px"></div>'; document.querySelector('.view-content.active').appendChild(box);
  });
  await page.evaluate(() => { const box = document.getElementById('t410box'); box.scrollIntoView(); }); await sleep(200);
  const y0 = (await st(page)).y;
  await page.evaluate(() => { document.getElementById('t410box').scrollTop = 1500; }); await sleep(350);
  s = await st(page);
  check('   a list in a box of its own, scrolled two screens of the box: it comes in', s.show, JSON.stringify(s));
  await page.click('#backToTop'); await sleep(1500);
  const boxTop = await page.evaluate(() => document.getElementById('t410box').scrollTop);
  s = await st(page);
  check('   a tap scrolls that box to its top (and leaves the page where it is)', boxTop === 0 && Math.abs(s.y - y0) < 2 && !s.show, JSON.stringify([boxTop, s.y, y0]));
  // the full-screen terminal, the bottom bar's More sheet
  await page.evaluate(() => { switchView('about'); }); await scrollTo(page, 2000);
  await page.evaluate(() => document.getElementById('txPaneTerminal').classList.add('tx-full')); await sleep(300);
  check('   the full-screen terminal up: it goes', !(await st(page)).show);
  await page.evaluate(() => document.getElementById('txPaneTerminal').classList.remove('tx-full')); await sleep(300);
  await page.evaluate(() => document.getElementById('bnavMoreModal').classList.add('show')); await sleep(300);
  check('   the bottom bar\'s More sheet open: it goes', !(await st(page)).show);
  await page.evaluate(() => document.getElementById('bnavMoreModal').classList.remove('show')); await sleep(300);
  check('   and then it is back', (await st(page)).show);
  await page.close();

  // ---- 4. the Undo bars ----
  page = await open();
  await page.evaluate(() => {          // what the bars are timed with
    const orig = window.setTimeout; window.__t410 = [];
    window.setTimeout = function (f, ms) { if (f === sdbSnackHide) window.__t410.push(ms); return orig.apply(this, arguments); };
  });
  await page.evaluate(() => { switchView('settings'); sdbSnack('Deleted test_key', () => {}); }); await sleep(450);
  const snack = await rect(page, '#sdbSnack');
  s = await st(page);
  check('4. the Undo bar stands just above the bottom bar (at most 12px over it)', snack.bottom <= s.navTop && s.navTop - snack.bottom <= 12, JSON.stringify([snack, s.navTop]));
  check('   it lasts 3 seconds (v7.14, the owner\'s wish: no longer than 3 s)', JSON.stringify(await page.evaluate(() => window.__t410)) === '[3000]', JSON.stringify(await page.evaluate(() => window.__t410)));
  await page.evaluate(() => { window.__t410.length = 0; sdbSnack('Deleted 5 files', () => {}, { ms: 8000 }); sdbSnack('Deleted test_key', () => {}); }); await sleep(450);
  check('   a caller that asks for longer still gets 3 s, and every Undo bar (the installer\'s, SD Maid\'s) is timed with that 3 s', JSON.stringify(await page.evaluate(() => window.__t410)) === '[3000,3000]' && await page.evaluate(() => UNDO_MS) === 3000
    && !/view: 'installer', ms: \d/.test(html) && /classList\.remove\('on'\); \}, UNDO_MS\)/.test(html), JSON.stringify(await page.evaluate(() => window.__t410)));
  await page.evaluate(() => showToast('Put back: test_key')); await sleep(500);
  const toast = await rect(page, '#toast');
  check('   a toast shown with it goes above it, not over it', toast.bottom <= snack.top, JSON.stringify([toast, snack]));
  await scrollTo(page, 0);
  await page.evaluate(() => { switchView('prefs'); window.scrollTo(0, 2400); sdbSnack('Theme changed', () => {}); }); await sleep(500);
  s = await st(page);
  const snack2 = await rect(page, '#sdbSnack');
  await page.screenshot({ path: 'undo-bar.png' });
  check('   the back-to-top button stands above an Undo bar, not on it', s.show && !overlap(s, snack2) && s.bottom <= snack2.top, JSON.stringify([s, snack2]));
  await sleep(5000);
  const gone = await page.evaluate(() => !document.getElementById('sdbSnack').classList.contains('show'));
  s = await st(page);
  check('   after 5 seconds the bar is gone and the button comes back down', gone && s.bottom > snack2.top, JSON.stringify([gone, s.bottom, snack2.top]));
  // with an app selected (the checkmark button) and with the batch sheet open, an Undo bar stands above them
  await page.evaluate(() => { switchView('apps'); window.scrollTo(0, 0); }); await sleep(300);
  await page.evaluate(() => { toggleSelectPkg(filteredApps[1].pkg); sdbSnack('x', () => {}); }); await sleep(500);
  const fab2 = await rect(page, '#batchFab'), sn3 = await rect(page, '#sdbSnack');
  check('   with the checkmark button up, the Undo bar stands above it', !overlap(fab2, sn3) && sn3.bottom <= fab2.top, JSON.stringify([fab2, sn3]));
  await page.evaluate(() => { expandBatchPanel(); sdbSnack('y', () => {}); }); await sleep(1500);          // (a new bar: the first may have run out on a busy machine)
  const sheet = await rect(page, '#floatingBatchBar'), sn4 = await rect(page, '#sdbSnack');
  check('   with the batch sheet open, above the sheet', sn4.bottom <= sheet.top + 0.5, JSON.stringify([sheet, sn4]));
  await page.evaluate(() => { collapseBatchPanel(); clearBatchSelectionNow(); sdbSnackHide(); }); await sleep(500);
  // SD Maid's bar
  await page.evaluate(() => { switchView('sdm'); const u = document.getElementById('sdUndo'); u.classList.add('on'); }); await sleep(300);
  const sdu = await rect(page, '#sdUndo'); s = await st(page);
  check('   SD Maid\'s Undo bar too stands just above the bottom bar', sdu.bottom <= s.navTop && s.navTop - sdu.bottom <= 12, JSON.stringify([sdu, s.navTop]));
  await page.close();
  // the bar off (tabs at the top only): near the bottom of the screen
  page = await open({ nav_mode: '"top"' });
  await page.evaluate(() => { switchView('settings'); sdbSnack('Deleted test_key', () => {}); }); await sleep(450);
  const sn5 = await rect(page, '#sdbSnack');
  check('   with the bottom bar off, near the bottom edge of the screen', 800 - sn5.bottom >= 4 && 800 - sn5.bottom <= 12, JSON.stringify(sn5));
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
