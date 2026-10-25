// Bottom navigation: what is pinned (the saved tab_config record, with and without a "pinned" list from before the bar existed), the Feature List pin control and its limit of four,
// a tab switched off, Settings > Navigation (Bottom bar + top bar, Bottom bar only, Top bar only, kept after a reload), the things at the bottom of the screen sitting above the bar,
// and the rail at 800 px
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }          // (the numbers behind a passing check stay out of the output: they depend on the fonts)
const NEWPAGE = async (b, kv, vp) => {
  const page = await b.newPage({ viewport: vp || { width: 360, height: 740 } });
  page.bottomNav = true;
  page.errors = [];
  page.on('pageerror', e => page.errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') page.errors.push('console: ' + m.text()); });
  await page.addInitScript(mock.initScript, { kv: kv || {} });
  await page.goto(PAGE); await page.waitForTimeout(600);
  return page;
};
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const bar = page => page.evaluate(() => [...document.querySelectorAll('#bnav .bn-item[data-tab]')].map(s => s.dataset.tab).join());

  // ---- 1. a saved record from before the bar existed (no pinned list) gets the defaults; unknown keys go ----
  let page = await NEWPAGE(b, { tab_config: JSON.stringify({ order: ['files', 'terminal', 'nonsense', 'installer'], off: ['store', 'gone'] }) });
  check('1. an old tab_config without "pinned" gets the default pinned tabs (in the saved order; its Command-Line Interface is Console now)', await bar(page) === 'apps,console,extrasettings,installer', await bar(page));
  const cfg = await page.evaluate(() => tabConfig);
  check('   unknown keys are dropped, the order is completed, the pinned list is made', !cfg.order.includes('nonsense') && !cfg.off.includes('gone') && cfg.order.length === 15 && cfg.pinned.join() === 'apps,installer,extrasettings,console', JSON.stringify(cfg.pinned));
  await page.close();

  // ---- 2. a saved pinned list: Application Manager is always there, at most four, off tabs and unknown keys leave ----
  page = await NEWPAGE(b, { tab_config: JSON.stringify({ order: ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm'], off: ['debloater'], pinned: ['store', 'debloater', 'bogus', 'files', 'logcat', 'sdm', 'terminal', 'store'] }) });
  const cfg2 = await page.evaluate(() => tabConfig.pinned);
  check('2. pinned: Application Manager first, the tab that is off, unknown keys and the old Logcat Viewer dropped, no repeats, at most four', cfg2.join() === 'apps,store,files,sdm', cfg2.join());
  check('   the bar shows them in the Feature List order', await bar(page) === 'apps,files,store,sdm', await bar(page));
  await page.close();
  // a record from before the Console tab (v7.12.12) with the Command-Line Interface ('terminal') pinned: Console takes its place on the bar; Live Logcat (in the old place of Terminal since v7.14.0) waits under More
  page = await NEWPAGE(b, { tab_config: JSON.stringify({ order: ['installer', 'terminal', 'files'], off: [], pinned: ['apps', 'terminal', 'files'] }) });
  check('   an old record with the Command-Line Interface pinned: Console is pinned in its place (Live Logcat is not)', await page.evaluate(() => tabConfig.pinned.join()) === 'apps,console,files' && await bar(page) === 'apps,console,files', await bar(page));

  // ---- 3. the Feature List: pin, unpin, the limit, off, reset ----
  page = await NEWPAGE(b);
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(300);
  const pins = () => page.evaluate(() => [...document.querySelectorAll('#featureList .fl-pin[aria-pressed="true"]')].map(b => b.closest('.fl-row').dataset.tab).join());
  check('3. the Feature List marks the pinned tabs (the defaults)', await pins() === 'installer,console,extrasettings', await pins());
  await page.click('#featureList .fl-row[data-tab="files"] .fl-pin'); await page.waitForTimeout(100);
  check('   a fourth tab (Application Manager is the first of the four) is refused with a message', await pins() === 'installer,console,extrasettings' && /holds 4 tabs/.test(await page.locator('#toastMsg').innerText()), await page.locator('#toastMsg').innerText());
  await page.click('#featureList .fl-row[data-tab="extrasettings"] .fl-pin'); await page.waitForTimeout(100);
  check('   unpinning one frees a place', await pins() === 'installer,console' && await bar(page) === 'apps,installer,console');
  await page.click('#featureList .fl-row[data-tab="files"] .fl-pin'); await page.waitForTimeout(100);
  check('   and the tab goes on the bar in its place in the list', await pins() === 'installer,files,console' && await bar(page) === 'apps,installer,files,console', await bar(page));
  const labels = await page.evaluate(() => [...document.querySelectorAll('#featureList .fl-row[data-tab="files"] .fl-pin')].map(b => b.getAttribute('aria-label') + '|' + b.getAttribute('aria-pressed')));
  check('   the pin button says what it does for a screen reader', labels.join() === 'Unpin File Manager from the bottom bar|true', labels.join());
  check('   the choice is saved in tab_config (the same record as the order)', await page.evaluate(() => { const c = JSON.parse(window.__kv.tab_config); return c.pinned.slice().sort().join() + '/' + c.order.length; }) === 'apps,console,files,installer/15');
  // switch a pinned tab off: it leaves the bar and the pins; back on, it is not pinned again
  await page.evaluate(() => featureToggle('files', false)); await page.waitForTimeout(100);
  check('4. a tab switched off leaves the bar and the pinned list', await bar(page) === 'apps,installer,console' && !(await page.evaluate(() => tabConfig.pinned.includes('files'))));
  check('   its pin button cannot be pressed while it is off', await page.evaluate(() => document.querySelector('#featureList .fl-row[data-tab="files"] .fl-pin').disabled));
  await page.evaluate(() => featureToggle('files', true)); await page.waitForTimeout(100);
  check('   switched on again it waits under More', await bar(page) === 'apps,installer,console' && await page.evaluate(() => bnavMoreKeys().includes('files')));
  await page.evaluate(() => featureReset()); await page.waitForTimeout(100);
  check('5. Reset to Default brings back the default pinned tabs', await bar(page) === 'apps,installer,console,extrasettings', await bar(page));
  // the saved record survives a reload
  await page.evaluate(() => featurePin('extrasettings')); await page.evaluate(() => featurePin('files')); await page.waitForTimeout(100);
  await page.reload(); await page.waitForTimeout(600);
  check('   a reload keeps what was pinned', await bar(page) === 'apps,installer,files,console', await bar(page));
  await page.close();

  // ---- 4. Settings > Navigation ----
  page = await NEWPAGE(b);
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(300);
  const nav = () => page.evaluate(() => ({ attr: document.documentElement.dataset.nav, top: getComputedStyle(document.getElementById('tabBar')).display, bottom: getComputedStyle(document.getElementById('bnav')).display, h: getComputedStyle(document.documentElement).getPropertyValue('--bnav-h').trim(), padB: parseFloat(getComputedStyle(document.body).paddingBottom), checked: [...document.querySelectorAll('.nav-opt')].filter(o => o.getAttribute('aria-checked') === 'true').map(o => o.dataset.navMode).join(), tabsH: getComputedStyle(document.documentElement).getPropertyValue('--tabs-h').trim() }));
  let n = await nav();
  check('6. Navigation: three choices, "Bottom bar only" is the default', (await page.locator('.nav-opt').allInnerTexts()).map(t => t.trim()).join('|') === 'Bottom bar + top bar|Bottom bar only|Top bar only' && n.checked === 'bottom' && n.top === 'none' && n.bottom !== 'none', JSON.stringify(n));
  check('   with the bar only, the top tab bar takes no room (--tabs-h is 0)', n.tabsH === '0px', n.tabsH);
  await page.click('.nav-opt[data-nav-mode="both"]'); await page.waitForTimeout(250);
  n = await nav();
  check('7. Bottom bar + top bar: both are there, the page has room for the bar', n.checked === 'both' && n.top !== 'none' && n.bottom !== 'none' && n.padB >= 144 && n.tabsH !== '0px', JSON.stringify(n));
  check('   the top tab bar and the bottom bar agree on the lit tab (a tab that is not pinned lights More)', await page.evaluate(() => { switchView('files'); return document.querySelector('#tabBar .tab-btn.active').dataset.tab + '/' + document.querySelector('#bnav .bn-item.active').dataset.more + '/' + document.querySelector('#bnav .bn-item.active .bn-lbl').innerText; }) === 'files/1/Files');
  await page.screenshot({ path: 'nav_both.png' });
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  await page.click('.nav-opt[data-nav-mode="top"]'); await page.waitForTimeout(250);
  n = await nav();
  check('8. Top bar only: the bar is gone, the page is as it was (no extra room, --bnav-h is 0)', n.checked === 'top' && n.bottom === 'none' && n.top !== 'none' && n.h === '0px' && n.padB === 88, JSON.stringify(n));
  check('   the choice is saved as nav_mode', await page.evaluate(() => window.__kv.nav_mode) === '"top"');
  await page.reload(); await page.waitForTimeout(600);
  n = await nav();
  check('   and kept after a reload', n.attr === 'top' && n.bottom === 'none', JSON.stringify(n));
  // an unknown saved value falls back to the default
  await page.close();
  page = await NEWPAGE(b, { nav_mode: '"sideways"' });
  check('   an unknown saved value gives the default', (await nav()).attr === 'bottom');
  await page.close();

  // ---- 5. everything fixed near the bottom sits above the bar ----
  page = await NEWPAGE(b);
  const fx = await page.evaluate(async () => {
    const out = {}, barTop = document.getElementById('bnav').getBoundingClientRect().top;
    showToast('hello'); await new Promise(r => setTimeout(r, 500));
    out.toast = barTop - document.getElementById('toast').getBoundingClientRect().bottom;
    const fab = document.querySelector('.batch-fab'); fab.style.transform = 'none'; out.fab = barTop - fab.getBoundingClientRect().bottom;
    const sheet = document.querySelector('.batch-bottom-sheet'); sheet.classList.add('show'); await new Promise(r => setTimeout(r, 600)); out.sheet = barTop - sheet.getBoundingClientRect().bottom; sheet.classList.remove('show'); await new Promise(r => setTimeout(r, 700));
    const mk = (cls) => { const e = document.createElement('div'); e.className = cls; e.style.display = 'flex'; e.style.transform = 'none'; e.style.opacity = '1'; document.body.appendChild(e); const r = barTop - e.getBoundingClientRect().bottom; e.remove(); return r; };
    out.snack = mk('sdb-snack'); out.undo = mk('sd-undo');
    // while the batch sheet is hidden it must not peek over the bar
    const hid = document.querySelector('.batch-bottom-sheet').getBoundingClientRect().top - barTop;
    out.hiddenSheet = hid;
    const t = document.getElementById('toast'); t.classList.remove('show'); await new Promise(r => setTimeout(r, 500)); out.toastHidden = t.getBoundingClientRect().top - barTop;
    return out;
  });
  check('9. the toast, the batch button, the batch sheet, the Undo bars sit above the bar', fx.toast >= 0 && fx.fab >= 0 && fx.sheet >= -0.5 && fx.snack >= 0 && fx.undo >= 0, JSON.stringify(fx));
  check('   a hidden batch sheet or toast does not show over the bar', fx.hiddenSheet >= 0 && fx.toastHidden >= 0, JSON.stringify([fx.hiddenSheet, fx.toastHidden]));
  await page.close();

  // ---- 6. a wide screen: a rail at the left, the page offset by its width ----
  page = await NEWPAGE(b, {}, { width: 800, height: 600 });
  await page.waitForFunction(() => document.getElementById('bnav').getBoundingClientRect().left === 0 && !document.getElementById('bnav').classList.contains('bnav-off'), null, { timeout: 5000 });          // the rail slides in at the start
  const rail = await page.evaluate(() => { const n = document.getElementById('bnav'), r = n.getBoundingClientRect(), c = document.querySelector('.container').getBoundingClientRect(), hdr = document.querySelector('.app-header').getBoundingClientRect(); const items = [...n.querySelectorAll('.bn-item')].map(i => { const q = i.getBoundingClientRect(); return { w: q.width, h: q.height, lbl: i.querySelector('.bn-lbl').getBoundingClientRect().top > i.querySelector('.bn-ico').getBoundingClientRect().bottom - 1 }; }); const ind = document.querySelector('.bn-ind').getBoundingClientRect(), act = document.querySelector('.bn-item.active .bn-ico').getBoundingClientRect(); return { left: r.left, w: r.width, h: r.height, vh: innerHeight, contentLeft: c.left, hdrLeft: hdr.left, items, bw: getComputedStyle(document.documentElement).getPropertyValue('--bnav-w').trim(), bh: getComputedStyle(document.documentElement).getPropertyValue('--bnav-h').trim(), scrollW: document.documentElement.scrollWidth - innerWidth, pill: Math.abs(ind.top - act.top) + Math.abs(ind.left - act.left) }; });
  check('10. at 800 px wide the bar is a rail at the left edge, full height', rail.left === 0 && rail.w === 76 && rail.h === rail.vh && rail.bw === '76px' && rail.bh === '0px', JSON.stringify([rail.left, rail.w, rail.h, rail.bw, rail.bh]));
  check('    the page and the header start after the rail, nothing overflows', rail.contentLeft >= 76 && rail.hdrLeft >= 76 && rail.scrollW <= 0, JSON.stringify([rail.contentLeft, rail.hdrLeft, rail.scrollW]));
  check('    the icons have their labels under them, the targets are 44 px or more, the pill is on the lit icon', rail.items.every(i => i.w >= 44 && i.h >= 44 && i.lbl) && rail.pill < 1.5, JSON.stringify(rail.items.map(i => [i.w, i.h, i.lbl])) + ' ' + rail.pill);
  await page.click('#bnav .bn-item[data-tab="console"]'); await page.waitForTimeout(500);
  check('    a tap on a rail item switches tabs', await page.evaluate(() => currentViewName()) === 'console' && await page.evaluate(() => document.querySelector('.bn-item.active').dataset.tab) === 'console');
  await page.click('#bnav .bn-item[data-more]'); await page.waitForTimeout(500);
  check('    More opens the same sheet on the rail', await page.evaluate(() => document.getElementById('bnavMoreModal').classList.contains('show')));
  await page.screenshot({ path: 'rail_more.png' });
  await page.keyboard.press('Escape'); await page.evaluate(() => bnavMoreClose());
  // the keyboard does not make the rail go (it is at the side), a sheet does
  await page.evaluate(() => { document.getElementById('searchInput'); switchView('apps'); }); await page.waitForTimeout(300);
  await page.focus('#searchInput'); await page.waitForTimeout(300);
  check('    an open text field does not hide the rail', !(await page.evaluate(() => document.getElementById('bnav').classList.contains('bnav-off'))));
  await page.screenshot({ path: 'rail.png' });
  errors.push(...page.errors);
  await page.close();

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad) process.exit(1);
})();
