// v7.14.0: Connected Devices' Apps list takes the Apps tab's filters and row tints (the owner's wish). The pills are the Apps tab's pills in its order and pairs
// (All Apps, Installed | Uninstalled, System | User, Enabled | Frozen) with the same colours; a device knows no running, updated, suspended, tracker or patched
// state, so those pills are not there. Each pair keeps one member (Installed | Uninstalled too, since the owner's request after v7.14.0; t543); Uninstalled starts a clean list. A frozen or uninstalled app's row is
// tinted as in the Apps tab, as strongly as Row tints says. The row itself is the Apps tab's: the icon is the selection box with the check badge on its corner, and its Action Button
// (chosen in the Apps tab's Sort / Button row, which the list has too) is the same icon button with the choices a device allows.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const APPS = [
  { pkg: 'com.a.clock', name: 'Clock', system: true, disabled: true, uninstalled: false },
  { pkg: 'com.a.fit', name: 'Fit', system: false, disabled: false, uninstalled: false },
  { pkg: 'com.a.maps', name: 'Maps', system: true, disabled: false, uninstalled: false },
  { pkg: 'com.a.gone', name: 'Gone', system: true, disabled: false, uninstalled: true },
  { pkg: 'com.a.weather', name: 'Weather', system: false, disabled: false, uninstalled: false },
];
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(400);
  await page.evaluate(() => setRowTintGrad(true));   // (Row tints > Gradient on: the glow measured here, the default until v7.18.1; the even colour, the default since, is t549's)
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(apps => { switchView('devices'); document.getElementById('cdWork').style.display = ''; cd.apps = apps; cd.filters.clear(); cdAppsRender(); }, APPS);

  const pills = () => ev(() => Array.from(document.querySelectorAll('#cdAppFilters .filter-pill')).map(p => p.innerText.replace(/[()]/g, '').replace(/\s+/g, ' ').trim() + (p.classList.contains('active') ? '*' : '')).join('|'));
  const shown2 = () => ev(() => Array.from(document.querySelectorAll('#cdAppList .app-name')).map(e => e.innerText).join(','));          // in the list's order
  const shown = () => ev(() => Array.from(document.querySelectorAll('#cdAppList .app-name')).map(e => e.innerText).sort().join(','));
  check('the pills are the Apps tab\'s, in its order, with counts', await pills() === 'All Apps 5*|Installed 4|Uninstalled 1|System 2|User 2|Enabled 3|Frozen 1', await pills());
  const shape = await ev(() => {
    const box = document.getElementById('cdAppFilters');
    const splits = Array.from(box.querySelectorAll('.stat-split')).map(s => Array.from(s.querySelectorAll('.filter-pill')).map(p => p.dataset.filter).join('+'));
    const main = Array.from(document.querySelectorAll('#filterScroll .stat-split')).map(s => Array.from(s.querySelectorAll('.filter-pill')).map(p => p.dataset.filter).join('+'));
    return { cls: box.className, splits, main, roles: Array.from(box.querySelectorAll('.filter-pill')).every(p => p.getAttribute('role') === 'button' && p.tabIndex === 0) };
  });
  check('they sit in pairs like the Apps tab\'s (Frozen is the device\'s disabled)', shape.cls === 'filter-scroll' && shape.splits.join() === 'installed+uninstalled,system+user,enabled+frozen' && shape.splits.every(s => shape.main.includes(s)) && shape.roles, JSON.stringify(shape));
  // a pill wears the colour of the Apps tab pill with the same filter, off and on
  const tones = async () => ev(() => {
    const out = {};
    for (const f of ['installed', 'uninstalled', 'system', 'user', 'enabled', 'frozen']) {
      const a = document.querySelector('#filterScroll .filter-pill[data-filter="' + f + '"]'), c = document.querySelector('#cdAppFilters .filter-pill[data-filter="' + f + '"]');
      const sa = getComputedStyle(a), sc = getComputedStyle(c);
      out[f] = sa.backgroundColor === sc.backgroundColor && sa.color === sc.color && sa.getPropertyValue('--tone') === sc.getPropertyValue('--tone') ? 'same' : sa.backgroundColor + ' / ' + sc.backgroundColor;
    }
    return out;
  });
  const t0 = await tones();
  check('each pill is coloured as the Apps tab\'s pill', Object.values(t0).every(v => v === 'same'), JSON.stringify(t0));
  await ev(() => { document.querySelector('#cdAppFilters [data-filter="frozen"]').click(); document.querySelector('#filterScroll [data-filter="frozen"]').classList.add('active'); });
  const on = await ev(() => { const a = getComputedStyle(document.querySelector('#filterScroll [data-filter="frozen"]')), c = getComputedStyle(document.querySelector('#cdAppFilters [data-filter="frozen"]')); return { same: a.backgroundColor === c.backgroundColor && a.borderColor === c.borderColor, pressed: document.querySelector('#cdAppFilters [data-filter="frozen"]').getAttribute('aria-pressed') }; });
  await ev(() => document.querySelector('#filterScroll [data-filter="frozen"]').classList.remove('active'));
  check('an active pill is coloured as the Apps tab\'s active pill and says it is pressed', on.same && on.pressed === 'true', JSON.stringify(on));
  check('Frozen shows the frozen app', await shown() === 'Clock', await shown());

  // the rules
  await ev(() => cdFilter('installed'));
  check('Installed narrows within Frozen', await shown() === 'Clock' && /Installed 4\*/.test(await pills()), await shown());
  await ev(() => { cd.filters.clear(); cdFilter('installed'); });
  check('Installed alone leaves out the uninstalled app', await shown() === 'Clock,Fit,Maps,Weather', await shown());
  await ev(() => cdFilter('uninstalled'));
  check('Uninstalled takes the place of Installed (a pair, as in the Apps tab)', await shown() === 'Gone' && await pills() === 'All Apps 5|Installed 4|Uninstalled 1*|System 2|User 2|Enabled 3|Frozen 1', await shown() + ' / ' + await pills());
  await ev(() => { cd.filters.clear(); cdFilter('user'); cdFilter('uninstalled'); });
  check('Uninstalled starts a clean list', await shown() === 'Gone' && await pills() === 'All Apps 5|Installed 4|Uninstalled 1*|System 2|User 2|Enabled 3|Frozen 1', await pills());
  await ev(() => cdFilter('system'));
  check('System narrows the uninstalled apps', await shown() === 'Gone');
  await ev(() => { document.querySelector('#cdAppFilters [data-filter="user"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); });
  check('Enter on a pill works it (User takes System\'s place)', await shown() === '' && /User 2\*/.test(await pills()) && !/System 2\*/.test(await pills()), await pills());
  await ev(() => cdFilter('all'));
  check('All Apps clears the filters', await pills() === 'All Apps 5*|Installed 4|Uninstalled 1|System 2|User 2|Enabled 3|Frozen 1');

  // the row tint: frozen and uninstalled rows, as the Apps tab's, at every step of Row tints; a plain row has none
  for (const lv of [0, 1, 2, 3, 4]) {
    const r = await ev(lv => {
      setRowTint(lv);
      const card = p => document.getElementById('cdcard_' + p);
      const probe = cls => { const e = document.createElement('div'); e.className = 'app-card ' + cls; document.getElementById('appsListContainer').appendChild(e); const s = getComputedStyle(e, '::before'); const v = { op: s.opacity, bg: s.backgroundImage }; e.remove(); return v; };
      const lay = e => { const s = getComputedStyle(e, '::before'); return { op: s.opacity, bg: s.backgroundImage }; };
      const fz = lay(card('com.a.clock')), un = lay(card('com.a.gone')), plain = getComputedStyle(card('com.a.fit'), '::before').content;
      const pf = probe('frozen'), pu = probe('uninstalled');
      return { cls: card('com.a.clock').className + '/' + card('com.a.gone').className, frozen: fz.op === pf.op && fz.bg === pf.bg, uninst: un.op === pu.op && un.bg === pu.bg, op: fz.op, plain };
    }, lv);
    const want = ['0', '0.06', '0.11', '0.2', '0.24'][lv];
    check('Row tints step ' + lv + ': frozen and uninstalled rows are tinted as the Apps tab\'s, a plain row is not',
      /\bfrozen\b/.test(r.cls) && /\buninstalled\b/.test(r.cls) && r.frozen && r.uninst && r.op === want && (r.plain === 'none' || r.plain === 'normal'), JSON.stringify(r));
  }
  // the row is the Apps tab's row: the icon is the selection box (a button, checkbox role) on the left with the check badge on its corner, then the name, then the buttons
  const geo = async () => ev(() => {
    const probeRow = () => { const h = document.createElement('div'); h.innerHTML = appCardHtml({ pkg: 'com.probe', name: 'Probe', isSystem: true }); const c = h.firstElementChild; document.getElementById('cdAppList').appendChild(c); return c; };
    const shape = c => { const r = c.getBoundingClientRect(), s = c.querySelector(':scope > .app-sel'), b = s && s.querySelector('.app-checkbox'), m = c.querySelector('.app-meta'); if (!s) return null; const sr = s.getBoundingClientRect(), br = b.getBoundingClientRect();
      return { first: c.firstElementChild === s, role: s.getAttribute('role'), w: Math.round(sr.width), h: Math.round(sr.height), left: Math.round(sr.left - r.left), badgeDx: Math.round(br.right - sr.right), badgeDy: Math.round(br.bottom - sr.bottom), badgeW: Math.round(br.width), shown: getComputedStyle(b).visibility, metaGap: Math.round(m.getBoundingClientRect().left - sr.right), last: c.lastElementChild.className }; };
    const main = probeRow(); main.classList.add('selected'); const pm = shape(main); main.remove();
    const row = document.getElementById('cdcard_com.a.fit'); cdToggleSel('com.a.fit'); const pd = shape(row);
    const label = row.querySelector('.app-sel').getAttribute('aria-label'), checked = row.querySelector('.app-sel').getAttribute('aria-checked'), tick = row.querySelector('.app-checkbox').innerText;
    row.querySelector('.app-sel').click(); const off = { checked: row.querySelector('.app-sel').getAttribute('aria-checked'), sel: cd.sel.has('com.a.fit'), shown: getComputedStyle(row.querySelector('.app-checkbox')).visibility };
    return { pm, pd, label, checked, tick, off };
  });
  const g = await geo();
  const same = g.pm && g.pd && ['first', 'role', 'w', 'h', 'left', 'badgeDx', 'badgeDy', 'badgeW', 'shown', 'metaGap', 'last'].every(k => g.pm[k] === g.pd[k]);
  check('a device row has the Apps tab\'s icon selection box, check badge and layout', same && g.pd.first && g.pd.shown === 'visible' && g.label === 'Select Fit' && g.checked === 'true' && g.tick === '\u2713', JSON.stringify(g));
  check('tapping the icon again clears the selection and hides the badge', g.off.checked === 'false' && !g.off.sel && g.off.shown === 'hidden', JSON.stringify(g.off));
  // the Action Button and the Sort / Button row, as in the Apps tab
  const ab = await ev(() => {
    const h = document.createElement('div'); h.innerHTML = appCardHtml({ pkg: 'com.probe', name: 'Probe', isSystem: true }); const pc = h.firstElementChild; document.getElementById('cdAppList').appendChild(pc);
    const look = b => { const s = getComputedStyle(b), r = b.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height), s.color, s.backgroundColor, s.borderRadius].join(); };
    const out = { main: look(pc.querySelector('.ab-btn')), menuMain: look(pc.querySelector('.row-menu')) }; pc.remove();
    const row = p => document.getElementById('cdcard_' + p);
    out.dev = look(row('com.a.fit').querySelector('.ab-btn')); out.menuDev = look(row('com.a.fit').querySelector('.row-menu'));
    out.kinds = ['com.a.clock', 'com.a.fit', 'com.a.gone'].map(p => row(p).querySelector('.ab-btn').dataset.ab + ':' + row(p).querySelector('.ab-btn').getAttribute('aria-label')).join();
    out.oldBtn = !!document.querySelector('#cdAppList .cd-row-act');
    out.bar = Array.from(document.querySelectorAll('#cdAppSortRow > *')).map(e => e.id || e.textContent.trim()).join('|');
    out.label = document.getElementById('cdAbPickLabel').textContent;
    return out;
  });
  check('a device row\'s Action Button and ⋯ are drawn as the Apps tab\'s', ab.main === ab.dev && ab.menuMain === ab.menuDev && !ab.oldBtn, JSON.stringify(ab));
  check('by default it enables or disables the app, and puts an uninstalled one back', ab.kinds === 'enable:Enable,disable:Disable,install:Reinstall', ab.kinds);
  check('the Sort / Button row sits above the list, with the button\'s choice named', ab.bar === 'Sort|cdAppSort|Button|cdAbPick' && ab.label === 'Enable / Disable', JSON.stringify(ab));
  await ev(() => document.getElementById('cdAbPick').click()); await page.waitForTimeout(150);
  const pick = await ev(() => ({ open: document.getElementById('choiceSheet').classList.contains('show'), opts: Array.from(document.querySelectorAll('#choiceList .ab-sheet-row')).map(b => b.dataset.v).join(), cur: (document.querySelector('#choiceList .current') || {}).dataset }));
  check('Button opens the choices a device allows (no suspend, permission or activity lists)', pick.open && pick.opts === 'settings,forcestop,launch,toggle,uninstall,none', JSON.stringify(pick));
  await ev(() => choiceSheetPick('forcestop'));
  const fs = await ev(() => ({ kind: document.querySelector('#cdcard_com\\.a\\.fit .ab-btn').dataset.ab, gone: document.querySelector('#cdcard_com\\.a\\.gone .ab-btn').dataset.ab, kv: kvGet('cd_action_btn'), label: document.getElementById('cdAbPickLabel').textContent, apps: actionBtn }));
  check('choosing Force Stop changes every row (an uninstalled app still gets Reinstall), is kept, and leaves the Apps tab\'s own choice alone', fs.kind === 'forcestop' && fs.gone === 'install' && fs.kv === 'forcestop' && fs.label === 'Force Stop' && fs.apps === 'settings', JSON.stringify(fs));
  const tapped = await ev(async () => { const seen = []; const keep = window.cdAppAct; window.cdAppAct = (p, a) => seen.push(p + ':' + a); document.querySelector('#cdcard_com\\.a\\.fit .ab-btn').click(); window.cdAppAct = keep; return seen.join(); });
  check('a tap runs it on the device', tapped === 'com.a.fit:stop', tapped);
  await ev(() => { cdActionBtn = 'none'; cdAppsRender(); });
  check('None takes the button off the rows', await ev(() => !document.querySelector('#cdAppList .ab-btn') && !!document.querySelector('#cdAppList .row-menu')));
  await ev(() => { cdActionBtn = 'toggle'; kvSet('cd_action_btn', 'toggle'); cdAppsRender(); });
  const held = await ev(async () => { const seen = []; const k1 = window.cdAppMenu, k2 = window.openAbSheet; window.cdAppMenu = p => seen.push('cd:' + p); window.openAbSheet = p => seen.push('phone:' + p);
    // a scroll cancels a hold (as on the phone): wait until nothing has scrolled for 300 ms, then hold; a slow runner gets up to three tries
    let lastScroll = Date.now(); const onScroll = () => { lastScroll = Date.now(); }; document.addEventListener('scroll', onScroll, true);
    for (let i = 0; i < 3 && !seen.length; i++) {
      while (Date.now() - lastScroll < 300) await new Promise(z => setTimeout(z, 50));
      const b = document.querySelector('#cdcard_com\\.a\\.fit .ab-btn'), r = b.getBoundingClientRect();
      b.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: r.left + 5, clientY: r.top + 5 })); await new Promise(z => setTimeout(z, 900)); document.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
    }
    document.removeEventListener('scroll', onScroll, true);
    window.cdAppMenu = k1; window.openAbSheet = k2; return seen.join(); });
  check('holding it opens that device app\'s menu, not this phone\'s', held === 'cd:com.a.fit', held);
  await ev(() => { const s = document.getElementById('cdAppSort'); s.value = 'name_desc'; s.dispatchEvent(new Event('change')); });
  check('Sort Name (Z–A) turns the list round and is kept', await shown2() === 'Weather,Maps,Gone,Fit,Clock' && await ev(() => kvGet('cd_sort')) === 'name_desc', await shown2());
  await ev(() => cdSetSort('name'));
  check('Name (A–Z) puts it back', await shown2() === 'Clock,Fit,Gone,Maps,Weather', await shown2());
  // the phone's filters and selection stay on the phone (the v7.14 review): the Apps tab repaints only its own pills and unticks only its own rows
  await ev(() => { cd.filters.clear(); cdFilter('system'); });
  await ev(() => { setFilter('user'); clearBatchSelectionNow(); });
  const pillState = await ev(() => Array.from(document.querySelectorAll('#cdAppFilters .filter-pill[data-filter]')).filter(p => p.classList.contains('active') || p.getAttribute('aria-pressed') === 'true').map(p => p.dataset.filter + ':' + p.getAttribute('aria-pressed')).join());
  check('a filter tapped in the Apps tab leaves the device\'s pills as they were (System on, User off)', pillState === 'system:true', pillState);
  await ev(() => { cd.filters.clear(); setFilter('user'); cdAppsRender(); cd.sel.clear(); cdToggleSel('com.a.maps'); clearBatchSelectionNow(); clearBatchSelection(); });
  const kept = await ev(() => { const c = document.getElementById('cdcard_com.a.maps'); return { sel: cd.sel.has('com.a.maps'), row: c.classList.contains('selected'), aria: c.querySelector('.app-sel').getAttribute('aria-checked') }; });
  check('clearing the Apps tab\'s selection leaves a ticked device row ticked, on screen and in its selection', kept.sel && kept.row && kept.aria === 'true', JSON.stringify(kept));
  const shines = await ev(() => { const c = document.getElementById('cdcard_com.a.maps'); let n = 0; const add = c.addEventListener.bind(c);
    c.addEventListener = (t, f, o) => { if (t === 'animationend') n++; return add(t, f, o); }; const rm = c.removeEventListener.bind(c); let r = 0; c.removeEventListener = (t, f, o) => { if (t === 'animationend') r++; return rm(t, f, o); };
    for (let i = 0; i < 4; i++) { cdToggleSel('com.a.maps'); cdToggleSel('com.a.maps'); } return { added: n, removed: r }; });
  check('the sweep of light keeps one listener per row (each new one replaces the last)', shines.added === 4 && shines.removed >= 3, JSON.stringify(shines));
  await ev(() => { setFilter('user'); cd.sel.clear(); cd.filters.clear(); cdAppsRender(); });
  check('no page errors', errors.length === 0, errors.join(' | '));
  await ev(() => { setRowTint(2); cdToggleSel('com.a.clock'); document.getElementById('cdAppSortRow').scrollIntoView(); }); await page.waitForTimeout(900);
  await page.screenshot({ path: 'cd_apps_filters.png' });
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
