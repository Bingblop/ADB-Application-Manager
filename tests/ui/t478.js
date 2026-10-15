// v7.14.0: Connected Devices' Apps list takes the Apps tab's filters and row tints (the owner's wish). The pills are the Apps tab's pills in its order and pairs
// (All Apps, Installed | Uninstalled, System | User, Enabled | Frozen) with the same colours; a device knows no running, updated, suspended, tracker or patched
// state, so those pills are not there. Installed and Uninstalled together are every app; Uninstalled starts a clean list. A frozen or uninstalled app's row is
// tinted as in the Apps tab, as strongly as Row tints says.
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
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(apps => { switchView('devices'); document.getElementById('cdWork').style.display = ''; cd.apps = apps; cd.filters.clear(); cdAppsRender(); }, APPS);

  const pills = () => ev(() => Array.from(document.querySelectorAll('#cdAppFilters .filter-pill')).map(p => p.innerText.replace(/[()]/g, '').replace(/\s+/g, ' ').trim() + (p.classList.contains('active') ? '*' : '')).join('|'));
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
  check('Installed and Uninstalled together are every app', await shown() === 'Clock,Fit,Gone,Maps,Weather', await shown());
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
  check('no page errors', errors.length === 0, errors.join(' | '));
  await ev(() => { setRowTint(2); document.getElementById('cdAppFilters').scrollIntoView(); });
  await page.screenshot({ path: 'cd_apps_filters.png' });
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
