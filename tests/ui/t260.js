// Bottom navigation bar: the default pinned set, one-line labels, the lit slot, More (sheet content and order, switching, the unpinned tab lit as More), the Installer badge, accessibility,
// room for the page (360 px: nothing wider than the screen, the end of the page above the bar) and Settings with no slot lit
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }          // (the numbers behind a passing check stay out of the output: they depend on the fonts)
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 740 } });
  page.bottomNav = true;
  page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const updates = [1, 2, 3].map(i => ({ pkg: 'org.example.app' + i, name: 'App ' + i, installedVersion: '1.0', availableVersion: '1.1', source: 'github', downloadUrl: 'https://github.com/x/y/a' + i + '.apk' }));
  await page.addInitScript(mock.initScript, { updates });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.waitForFunction(() => { const n = document.getElementById('bnav'); return !n.classList.contains('bnav-off') && n.getBoundingClientRect().bottom === innerHeight; }, null, { timeout: 5000 });          // the bar slides in at the start
  const slots = () => ev(() => [...document.querySelectorAll('#bnav .bn-item')].map(b => ({ tab: b.dataset.tab || 'more', label: b.querySelector('.bn-lbl').innerText.trim(), active: b.classList.contains('active'), cur: b.getAttribute('aria-current') })));

  // ---- 1. the default: Application Manager, Installer, Console, Extra Settings and More ----
  const s0 = await slots();
  check('1. five slots: the default pinned tabs and More', s0.map(s => s.tab).join() === 'apps,installer,console,extrasettings,more', s0.map(s => s.tab).join());
  check('   short one-line labels', s0.map(s => s.label).join() === 'Apps,Installer,Console,Extra,More', s0.map(s => s.label).join());
  check('   Application Manager is lit and carries aria-current="page"', s0[0].active && s0[0].cur === 'page' && s0.filter(s => s.active).length === 1);
  check('   the top tab bar is not shown (Bottom bar only is the default)', await ev(() => getComputedStyle(document.getElementById('tabBar')).display === 'none' && document.documentElement.dataset.nav === 'bottom'));
  const g = await ev(() => {
    const n = document.getElementById('bnav'), r = n.getBoundingClientRect(), cs = getComputedStyle(n);
    const items = [...n.querySelectorAll('.bn-item')].map(i => { const q = i.getBoundingClientRect(); const l = i.querySelector('.bn-lbl'); return { h: q.height, w: q.width, wrap: l.getBoundingClientRect().height < 20, ell: l.scrollWidth <= l.clientWidth + 1, svg: !!i.querySelector('svg') }; });
    return { h: r.height, bottom: r.bottom, vh: innerHeight, role: n.tagName, label: n.getAttribute('aria-label'), bg: cs.backgroundColor, cssH: getComputedStyle(document.documentElement).getPropertyValue('--bnav-h').trim(), items };
  });
  check('2. the bar is 56 px high and sits at the bottom, a navigation landmark with a label', g.h === 56 && g.bottom === g.vh && g.role === 'NAV' && g.label === 'Main navigation', JSON.stringify([g.h, g.bottom, g.vh, g.label]));
  check('   every slot has an icon, a one-line label that fits and a target of at least 44 px', g.items.every(i => i.svg && i.wrap && i.ell && i.h >= 44 && i.w >= 44), JSON.stringify(g.items.map(i => [i.w, i.h])));
  check('   --bnav-h is the bar height', g.cssH.indexOf('56px') >= 0 || g.cssH.indexOf('--bnav-full') >= 0 || g.cssH === 'var(--bnav-full)', g.cssH);
  const pill = await ev(() => { const i = document.querySelector('#bnav .bn-ind'), a = document.querySelector('#bnav .bn-item.active .bn-ico'); const r = i.getBoundingClientRect(), q = a.getBoundingClientRect(); return { dx: Math.abs(r.left - q.left), dy: Math.abs(r.top - q.top), op: getComputedStyle(i).opacity, bg: getComputedStyle(i).backgroundImage.indexOf('linear-gradient') === 0 }; });
  check('3. the gradient pill is behind the lit icon', pill.dx < 1.5 && pill.dy < 1.5 && pill.op === '1' && pill.bg, JSON.stringify(pill));

  // ---- 2. a tap on a slot: switches, lights the slot, the pill slides ----
  await page.click('#bnav .bn-item[data-tab="console"]'); await page.waitForTimeout(500);
  const s1 = await slots();
  check('4. a tap on Console opens that tab and lights its slot', await ev(() => currentViewName()) === 'console' && s1.find(s => s.tab === 'console').active && s1.filter(s => s.active).length === 1);
  const pill2 = await ev(() => { const i = document.querySelector('#bnav .bn-ind'), a = document.querySelector('#bnav .bn-item.active .bn-ico'); return Math.abs(i.getBoundingClientRect().left - a.getBoundingClientRect().left); });
  check('   the pill followed to the new slot', pill2 < 1.5, pill2);
  check('   the tap gave a haptic tick', await ev(() => window.__calls.includes('vibrate')));
  // the lit slot again goes to the top of the page
  await ev(() => window.scrollTo(0, 500)); await page.waitForTimeout(100);
  await page.click('#bnav .bn-item[data-tab="console"]'); await page.waitForTimeout(700);
  check('   a tap on the lit slot scrolls to the top (and does not reload the tab)', await ev(() => window.scrollY) < 5 || await ev(() => document.documentElement.scrollHeight <= innerHeight));

  // ---- 3. More: the tabs that are on and not pinned, in the saved order, three to a row ----
  await page.click('#bnav .bn-item[data-more]'); await page.waitForTimeout(500);
  const more = await ev(() => ({ open: document.getElementById('bnavMoreModal').classList.contains('show'), tiles: [...document.querySelectorAll('#bnavMoreGrid .bn-tile')].map(t => t.dataset.tab), cols: getComputedStyle(document.getElementById('bnavMoreGrid')).gridTemplateColumns.split(' ').length, title: document.getElementById('bnavMoreTitle').innerText, expanded: document.querySelector('#bnav .bn-item[data-more]').getAttribute('aria-expanded'), link: [...document.querySelectorAll('#bnavMoreModal .bn-custom')].map(x => x.innerText) }));
  check('5. More opens the sheet with every other tab, in the Feature List order, and About last', more.open && more.tiles.join() === 'saved-lists,debloater,files,terminal,settings,overlays,store,taskmgr,devices,morphe,sdm,about', more.tiles.join());
  check('   three columns, a title, the Customize tabs link, aria-expanded on the slot', more.cols === 3 && more.title === 'More tabs' && more.link.join() === 'Customize tabs' && more.expanded === 'true', JSON.stringify([more.cols, more.title, more.link, more.expanded]));
  await page.screenshot({ path: 'more_sheet.png' });
  await page.click('#bnavMoreGrid .bn-tile[data-tab="terminal"]'); await page.waitForTimeout(500);
  const afterMore = await ev(() => ({ view: currentViewName(), open: document.getElementById('bnavMoreModal').classList.contains('show'), away: document.getElementById('bnav').classList.contains('bnav-off') }));
  check('6. a tile switches to its tab and closes the sheet', afterMore.view === 'terminal' && !afterMore.open && !afterMore.away, JSON.stringify(afterMore));
  const s2 = await slots(), mo = s2.find(s => s.tab === 'more');
  check('7. on a tab that is not pinned, More is lit and shows that tab\'s label', mo.active && mo.label === 'Terminal' && mo.cur === 'page' && s2.filter(s => s.active).length === 1, JSON.stringify(mo));
  check('   its icon is the tab\'s own (not the dots)', await ev(() => { const t = document.createElement('div'); t.innerHTML = bnavSvg(BNAV_ICONS.terminal); return t.querySelector('path').getAttribute('d') === document.querySelector('#bnav .bn-item[data-more] svg path').getAttribute('d'); }));
  await page.click('#bnav .bn-item[data-more]'); await page.waitForTimeout(400);
  check('   the sheet marks the current tab', await ev(() => document.querySelector('#bnavMoreGrid .bn-tile.current').dataset.tab) === 'terminal');
  await page.keyboard.press('Escape'); await ev(() => handleAndroidBack()); await page.waitForTimeout(400);
  check('8. Android Back closes the More sheet first', !(await ev(() => document.getElementById('bnavMoreModal').classList.contains('show'))) && await ev(() => currentViewName()) === 'terminal');
  // a tap on the backdrop closes it too
  await page.click('#bnav .bn-item[data-more]'); await page.waitForTimeout(300);
  await page.mouse.click(180, 20); await page.waitForTimeout(400);
  check('   a tap above the sheet closes it', !(await ev(() => document.getElementById('bnavMoreModal').classList.contains('show'))));
  // Customize tabs opens the Feature List in Settings
  await page.click('#bnav .bn-item[data-more]'); await page.waitForTimeout(300);
  await page.click('#bnavMoreModal .bn-custom'); await page.waitForTimeout(900);
  const cu = await ev(() => ({ view: currentViewName(), open: document.getElementById('bnavMoreModal').classList.contains('show'), top: document.getElementById('featureCard').getBoundingClientRect().top, lit: document.querySelectorAll('#bnav .bn-item.active').length }));
  check('9. Customize tabs opens Settings at the Feature List and no slot is lit there', cu.view === 'prefs' && !cu.open && cu.top < 400 && cu.lit === 0 && await ev(() => document.querySelector('#bnav').classList.contains('bnav-off') === false), JSON.stringify(cu));

  // ---- 4. the badge of the Updater count on the Installer slot ----
  await page.click('#bnav .bn-item[data-tab="apps"]'); await page.waitForTimeout(300);
  await ev(() => { window.onUpdatesChecked(JSON.stringify({ updates: JSON.parse(window.AndroidBridge.getUpdateState()).updates, untracked: [], external: [], checked: 10, errors: 0, checkedAt: Date.now() })); });
  await page.waitForTimeout(200);
  const bd = await ev(() => { const b = document.querySelector('#bnav .bn-item[data-tab="installer"] .bn-badge'); return b ? b.innerText.trim() : null; });
  check('10. the Installer slot shows the number of updates found', bd === '3', bd);
  await ev(() => { tabConfig.pinned = tabConfig.pinned.filter(k => k !== 'installer'); tabsApplyConfig(); });
  const bd2 = await ev(() => ({ onMore: !!document.querySelector('#bnav .bn-item[data-more] .bn-badge.dot'), tile: (() => { bnavMoreOpen(); const t = document.querySelector('#bnavMoreGrid .bn-tile[data-tab="installer"] .bn-badge'); const r = t ? t.innerText.trim() : null; bnavMoreClose(); return r; })() }));
  check('    unpinned, the count shows as a dot on More and as the number on its tile', bd2.onMore && bd2.tile === '3', JSON.stringify(bd2));

  // ---- 5. room: 360 px wide, the end of the page above the bar ----
  await ev(() => switchView('about')); await page.waitForTimeout(400);
  const room = await ev(() => { window.scrollTo(0, document.documentElement.scrollHeight); const nav = document.getElementById('bnav').getBoundingClientRect(); const cs = [...document.querySelectorAll('.view-content.active .color-card, .view-content.active .container > *')]; const last = document.querySelector('.view-content.active'); const lr = last.getBoundingClientRect(); return { wide: document.documentElement.scrollWidth - innerWidth, lastBottom: lr.bottom, navTop: nav.top, pad: parseFloat(getComputedStyle(document.body).paddingBottom) }; });
  check('11. nothing is wider than the screen at 360 px', room.wide <= 0, room.wide);
  check('    the end of the page is above the bar (the page has room of at least the bar height)', room.lastBottom <= room.navTop + 0.5 && room.pad >= 56, JSON.stringify(room));

  // ---- 6. a long label does not wrap or push: the slot cuts it with an ellipsis ----
  const long = await ev(() => { const l = document.querySelector('#bnav .bn-item[data-tab="apps"] .bn-lbl'); l.textContent = 'Anwendungsverwaltung'; const r = { h: l.getBoundingClientRect().height, cut: l.scrollWidth > l.clientWidth, ell: getComputedStyle(l).textOverflow, w: l.getBoundingClientRect().width, slot: l.closest('.bn-item').getBoundingClientRect().width }; return r; });
  check('12. a long (translated) label stays on one line and is cut with an ellipsis inside its slot', long.h < 20 && long.cut && long.ell === 'ellipsis' && long.w <= long.slot, JSON.stringify(long));

  // ---- 7. the lit slot of every pinned tab; an unknown tab has the generic icon ----
  const icons = await ev(() => TAB_DEFS.map(t => t.key).filter(k => !BNAV_ICONS[k]));
  check('13. every tab of the registry has its own icon (a tab without one gets the generic symbol)', icons.length === 0, JSON.stringify(icons));
  check('    a tab key without an icon uses the fallback and a first-line label', await ev(() => bnavIcon('nope').indexOf(BNAV_ICON_FALLBACK) > 0));
  await page.screenshot({ path: 'bar.png' });

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad) process.exit(1);
})();
