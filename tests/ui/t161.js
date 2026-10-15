// The Apps tab: a Refresh button at the very end of the search bar (after the search options button) reloads the installed-apps list from the phone with the same load
// the app already uses (loadPackageData), once at a time. It is a round 40 x 40 icon button with a name for a screen reader (the existing "Refresh" string, so every language
// has it), it spins and is off while the load runs, ticks like the other buttons of the tab (and stays quiet with Haptic feedback off), and keeps the search text, the sort,
// the filters and the selection. It does not take room from the other rows and leaves the search box usable at 320 and 412 px.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
const initMock = () => {
      window.__loads = 0; window.__vib = 0; window.__fail = false; window.__gen = 1;
      const apps = () => (window.__many ? Array.from({ length: 60 }, (_, i) => ({ pkg: 'com.f.n' + i, name: 'Filler ' + String(i).padStart(2, '0'), isSystem: false })) : []).concat(window.__gen === 1
        ? [{ pkg: 'com.a.one', name: 'Alpha One', isSystem: false }, { pkg: 'com.a.two', name: 'Alpha Two', isSystem: false }, { pkg: 'com.sys.x', name: 'Alpha System', isSystem: true }, { pkg: 'com.b.zed', name: 'Zed', isSystem: false }]
        : [{ pkg: 'com.a.one', name: 'Alpha One', isSystem: false }, { pkg: 'com.a.three', name: 'Alpha Three', isSystem: false }, { pkg: 'com.sys.x', name: 'Alpha System', isSystem: true }, { pkg: 'com.b.zed', name: 'Zed', isSystem: false }]);
      window.AndroidBridge = {
        vibrate() { window.__vib++; }, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore() { return true; }, loadStore() { return ''; }, saveSetting() {}, loadSetting() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        loadPackages() { window.__loads++; if (window.__fail) throw new Error('device not reachable'); return JSON.stringify(apps()); },
        getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false }); },
      };
    };
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [w, h] of [[320, 640], [412, 915]]) {
    const tag = w + 'px';
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    page.on('pageerror', e => errors.push(tag + ' ' + e.message));
    await page.addInitScript(initMock);
    await page.goto(PAGE); await page.waitForTimeout(700);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const sleep = ms => page.waitForTimeout(ms);
    const names = () => ev(() => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(n => n.innerText).sort().join('|'));
    const loads = () => ev(() => window.__loads);
    const btnState = () => ev(() => { const x = document.getElementById('appsRefreshBtn'); return { disabled: x.disabled, busy: x.classList.contains('busy'), ariaBusy: x.getAttribute('aria-busy') }; });

    // 1) placement and size
    const g = await ev(() => {
      const r = e => { const q = e.getBoundingClientRect(); return { l: Math.round(q.left), r: Math.round(q.right), t: Math.round(q.top), b: Math.round(q.bottom), w: Math.round(q.width), h: Math.round(q.height) }; };
      const box = document.getElementById('searchBox'), btn = document.getElementById('appsRefreshBtn'), menu = document.getElementById('searchMenuBtn'), inp = document.getElementById('searchInput');
      const buttons = Array.from(box.querySelectorAll(':scope > button'));
      return { box: r(box), btn: r(btn), menu: r(menu), inp: r(inp), vw: innerWidth, lastButton: buttons[buttons.length - 1].id, visible: getComputedStyle(btn).display !== 'none' && getComputedStyle(btn).visibility !== 'hidden',
        label: btn.getAttribute('aria-label'), title: btn.title, text: btn.innerText.trim(), svg: !!btn.querySelector('svg'), type: btn.type, inSearchRow: btn.parentElement === box, scrollX: document.documentElement.scrollWidth > innerWidth };
    });
    check(tag + ' 1. a Refresh button sits in the search bar, visible, an icon with no text', g.inSearchRow && g.visible && g.svg && g.text === '' && g.type === 'button', JSON.stringify(g));
    check(tag + '    it is the last control: the right-most thing in the bar, after the search options button, inside the bar and the screen', g.lastButton === 'appsRefreshBtn' && g.btn.l >= g.menu.r - 1 && g.btn.r <= g.box.r && g.btn.r <= g.vw && g.btn.l >= 0 && g.box.r - g.btn.r <= 8, JSON.stringify(g));
    check(tag + '    at least 40 x 40, within the height of the bar', g.btn.w >= 40 && g.btn.h >= 40 && g.btn.t >= g.box.t && g.btn.b <= g.box.b, JSON.stringify(g.btn));
    check(tag + '    it is named for a screen reader and has a tool tip, with the existing word "Refresh"', g.label === 'Refresh' && g.title === 'Refresh');
    check(tag + '    the page does not scroll sideways', !g.scrollX);
    const sizes = await ev(() => { const row = document.querySelector('.apps-sort-row'); return { rowH: Math.round(row.getBoundingClientRect().height), boxH: Math.round(document.getElementById('searchBox').getBoundingClientRect().height), kids: row.children.length, inpW: Math.round(document.getElementById('searchInput').getBoundingClientRect().width), room: document.getElementById('searchInput').clientWidth - parseFloat(getComputedStyle(document.getElementById('searchInput')).paddingLeft) - parseFloat(getComputedStyle(document.getElementById('searchInput')).paddingRight), hint: (() => { const i = document.getElementById('searchInput'), c = document.createElement('canvas').getContext('2d'), cs = getComputedStyle(i); c.font = cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily; return Math.ceil(c.measureText(i.placeholder).width); })() }; });
    // one line: 36px controls with 4px of room above and below for their 44px hit areas (it was 32 before the controls grew)
    check(tag + '    the Sort row is untouched (4 controls since v7.14, one line) and the bar keeps its height', sizes.rowH <= 44 && sizes.kids === 4 && sizes.boxH === 46, JSON.stringify(sizes));
    check(tag + '    the search box keeps room to type: the empty box shows the whole hint ("Search by name or package")', sizes.room >= sizes.hint, JSON.stringify(sizes));
    await ev(() => { const i = document.getElementById('searchInput'); i.value = 'alpha'; handleSearch(); }); await sleep(100);
    const typed = await ev(() => { const i = document.getElementById('searchInput'); const c = document.getElementById('searchClear').getBoundingClientRect(), m = document.getElementById('searchMenuBtn').getBoundingClientRect(), f = document.getElementById('appsRefreshBtn').getBoundingClientRect(); return { clearShown: getComputedStyle(document.getElementById('searchClear')).display !== 'none', clearLeftOfMenu: c.right <= m.left + 1, menuLeftOfRefresh: m.right <= f.left + 1, room: Math.round(i.clientWidth - parseFloat(getComputedStyle(i).paddingLeft) - parseFloat(getComputedStyle(i).paddingRight)) }; });
    check(tag + '    with text in the box the clear x stays, left of the menu button, and Refresh is still the last', typed.clearShown && typed.clearLeftOfMenu && typed.menuLeftOfRefresh, JSON.stringify(typed));
    check(tag + '    and there is still room to type', typed.room >= 140, JSON.stringify(typed));
    await page.screenshot({ path: 'search_' + w + '.png', clip: { x: 0, y: Math.max(0, g.box.t - 60), width: w, height: 140 } });
    await ev(() => clearSearch());

    // 2) one tap, one load; busy while it runs; a second tap does nothing
    const l0 = await loads();
    await page.tap('#appsRefreshBtn');
    const during = await btnState();
    check(tag + ' 2. a tap shows the busy state at once: the button is off, spinning and marked busy', during.disabled && during.busy && during.ariaBusy === 'true', JSON.stringify(during));
    const spin = await ev(() => { const cs = getComputedStyle(document.querySelector('#appsRefreshBtn svg')); return { name: cs.animationName, dur: cs.animationDuration, iter: cs.animationIterationCount }; });
    check(tag + '    and the icon really turns: the spin animation runs on the SVG (0.8 s, forever)', spin.name === 'appsRefreshSpin' && spin.dur === '0.8s' && spin.iter === 'infinite', JSON.stringify(spin));
    await ev(() => refreshAppsList());                                       // a second request while the first one runs
    await page.tap('#appsRefreshBtn', { force: true, timeout: 800 }).catch(() => {});     // and a second tap on the off button
    await ev(() => document.getElementById('appsRefreshBtn').click());
    await sleep(700);
    check(tag + '    the phone was asked once, however often it was tapped meanwhile', (await loads()) - l0 === 1, String((await loads()) - l0));
    const after = await btnState();
    check(tag + '    when the load is done the button is on again and still', !after.disabled && !after.busy && after.ariaBusy === null, JSON.stringify(after));
    check(tag + '    and the icon stops turning', (await ev(() => getComputedStyle(document.querySelector('#appsRefreshBtn svg')).animationName)) === 'none');
    await page.tap('#appsRefreshBtn'); await sleep(700);
    check(tag + '    and a later tap loads again', (await loads()) - l0 === 2, String((await loads()) - l0));

    // 3) what a refresh keeps
    await ev(() => { window.__gen = 1; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    await page.fill('#searchInput', 'alpha'); await sleep(150);
    await ev(() => setFilter('user')); await sleep(100);
    await ev(() => { document.getElementById('appSort').value = 'updated'; setAppSort('updated'); }); await sleep(100);   // not the default (name), so a refresh that reset the sort would show
    await ev(() => toggleSelectPkg('com.a.one'));
    const before = { names: await names(), search: await ev(() => document.getElementById('searchInput').value), sort: await ev(() => document.getElementById('appSort').value + '/' + appSort), filters: await ev(() => Array.from(activeFilters).join()), sel: await ev(() => Array.from(selectedPkgs).join()) };
    check(tag + ' 3. before: search "alpha", 3rd Party filter, one app selected: two apps listed', before.names === 'Alpha One|Alpha Two' && before.search === 'alpha' && before.filters === 'user' && before.sel === 'com.a.one', JSON.stringify(before));
    await ev(() => { window.__gen = 2; });                                    // the phone changed: Alpha Two is gone, Alpha Three is new
    await page.tap('#appsRefreshBtn'); await sleep(700);
    const post = { names: await names(), search: await ev(() => document.getElementById('searchInput').value), sort: await ev(() => document.getElementById('appSort').value + '/' + appSort), filters: await ev(() => Array.from(activeFilters).join()), sel: await ev(() => Array.from(selectedPkgs).join()), checked: await ev(() => document.getElementById('card_com.a.one').classList.contains('selected')), stat: await ev(() => document.getElementById('statTotal').innerText) };
    check(tag + '    after: the list shows the new data (Alpha Two gone, Alpha Three there) and the counts follow', post.names === 'Alpha One|Alpha Three' && post.stat === '4', JSON.stringify(post));
    check(tag + '    the search text, the sort and the filter are as they were', post.search === 'alpha' && post.sort === 'updated/updated' && before.sort === 'updated/updated' && post.filters === 'user', JSON.stringify(post));
    check(tag + '    the selected app is still selected and drawn selected', post.sel === 'com.a.one' && post.checked, JSON.stringify(post));
    await ev(() => { clearBatchSelectionNow(); setFilter('all'); clearSearch(); });

    // 3b) the list stays where it was scrolled to (sixty more apps make the page long enough to scroll)
    await ev(() => { window.__many = true; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    const rows = await ev(() => document.querySelectorAll('#appsListContainer .app-card').length);
    await ev(() => { const el = document.scrollingElement; el.scrollTo({ top: 900, behavior: 'instant' }); }); await sleep(200);
    const y0 = await ev(() => Math.round(document.scrollingElement.scrollTop));
    await ev(() => refreshAppsList()); await sleep(700);
    const y1 = await ev(() => Math.round(document.scrollingElement.scrollTop));
    check(tag + ' 3b. with ' + rows + ' apps listed and the page scrolled down, a refresh keeps the scroll position', rows >= 60 && y0 > 300 && Math.abs(y1 - y0) <= 2, 'rows ' + rows + ', before ' + y0 + ', after ' + y1);
    await ev(() => { window.__many = false; document.scrollingElement.scrollTo({ top: 0, behavior: 'instant' }); });
    await page.tap('#appsRefreshBtn'); await sleep(700);

    // 4) haptic feedback like the other buttons of the tab, and none when it is off
    await ev(() => { window.__vib = 0; hapticLast = 0; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    const v1 = await ev(() => window.__vib);
    await ev(() => { hapticsOn = false; window.__vib = 0; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    const v2 = await ev(() => window.__vib);
    check(tag + ' 4. the tap ticks once, and nothing with Haptic feedback off', v1 === 1 && v2 === 0, v1 + ' / ' + v2);
    await ev(() => { hapticsOn = true; });

    // 5) a phone that cannot be read: the same message as the other loads, the button is usable again
    await ev(() => { window.__fail = true; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    const err = await ev(() => ({ text: document.getElementById('appsListContainer').innerText, btnOn: !document.getElementById('appsRefreshBtn').disabled }));
    check(tag + ' 5. when the load fails the list says so (as every load does) and the button is on again', /No Applications Loaded/.test(err.text) && /device not reachable/.test(err.text) && err.btnOn, JSON.stringify(err));
    await ev(() => { window.__fail = false; });
    await page.tap('#appsRefreshBtn'); await sleep(700);
    check(tag + '    and a later tap brings the list back', (await names()).includes('Alpha One'), await names());
    // 6) the name and the tool tip follow the language (the word is already in every dictionary)
    await ev(() => { const s = document.getElementById('langSelect'); s.value = 'de'; chooseLanguage('de'); }); await sleep(600);
    const de = await ev(() => { const x = document.getElementById('appsRefreshBtn'); return { label: x.getAttribute('aria-label'), title: x.title }; });
    check(tag + ' 6. in German the name and the tool tip read "Aktualisieren"', de.label === 'Aktualisieren' && de.title === 'Aktualisieren', JSON.stringify(de));
    await page.close();
  }
  // reduced motion: the icon does not turn (the button is still off and marked busy)
  {
    const page = await b.newPage({ viewport: { width: 412, height: 915 }, hasTouch: true, reducedMotion: 'reduce' });
    page.on('pageerror', e => errors.push('reduced ' + e.message));
    await page.addInitScript(initMock);
    await page.goto(PAGE); await page.waitForTimeout(700);
    await page.tap('#appsRefreshBtn');
    const r = await page.evaluate(() => { const x = document.getElementById('appsRefreshBtn'); return { busy: x.classList.contains('busy'), disabled: x.disabled, anim: getComputedStyle(x.querySelector('svg')).animationName }; });
    check('7. with "reduce motion" on, the busy button does not turn its icon, but is still off and marked busy', r.busy && r.disabled && r.anim === 'none', JSON.stringify(r));
    await page.close();
  }
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})();
