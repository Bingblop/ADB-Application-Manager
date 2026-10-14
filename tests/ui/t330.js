// Small polish, measured: the batch sheet's "Keep selection" switch keeps a row gap from the button grid below it; the Debloater's Uninstall,
// Disable, Restore and Save to List are off while nothing is selected and on again with a selection; at 320 px the Console's "?" has its own
// room at the end of the tab row (no tab text under it, left to right and right to left) and the underline still lines up with the tab; and
// the toast sits on the inverse surface (the text colour as its fill, the page colour as its text) so it stands out from the cards in both themes.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const open = async ({ width = 400, dark = true } = {}) => {
    const page = await b.newPage({ viewport: { width, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([mock, dark]) => {
      const data = JSON.parse(mock);
      window.__calls = [];
      window.prompt = () => null;
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: dark ? 'dark' : 'light', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
        loadCustomLists() { return '[]'; }, saveCustomLists() {}, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return dark; }, setSystemBarColor() {},
        loadSetting() { return ''; }, saveSetting() { return true; },
        loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'Example', isSystem: false }, { pkg: 'com.y', name: 'Other', isSystem: true }]); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getUadStatus() { return JSON.stringify({ cached: true, count: 5381, updatedAt: Date.now(), stale: false, downloading: false }); },
        updateUadList() {}, getUadMatches() { return JSON.stringify(data); },
        executeAppAction(a, p) { window.__calls.push(a + ':' + p); return 'Success'; },
      };
    }, [MOCK, dark]);
    await page.goto(PAGE); await page.waitForTimeout(500);
    return page;
  };

  // ---- the batch sheet: a row gap between the switch's second line and the grid ----
  let page = await open();
  await page.evaluate(() => { switchView('apps'); toggleSelectPkg('com.x'); expandBatchPanel(); }); await page.waitForTimeout(500);
  const gap = await page.evaluate(() => {
    const sub = document.querySelector('#batchKeepSelectionToggle').closest('.switch-row').querySelector('.switch-sub').getBoundingClientRect();
    const grid = document.querySelector('#floatingBatchBar .batch-actions-grid').getBoundingClientRect();
    return Math.round(grid.top - sub.bottom);
  });
  check('batch sheet: the switch line ends at least 12 px above the button grid', gap >= 12, gap);
  await page.evaluate(() => { collapseBatchPanel(); clearBatchSelection(); });

  // ---- Debloater: the selection buttons are off with nothing selected ----
  await page.evaluate(() => switchView('debloater')); await page.waitForTimeout(500);
  const btns = () => page.evaluate(() => Array.from(document.querySelectorAll('.uad-action-grid .batch-grid-btn')).map(x => x.innerText.trim() + ':' + (x.disabled ? 'off' : 'on')).join(' | '));
  check('nothing selected: Uninstall, Disable, Restore and Save to List are off', (await btns()) === 'Uninstall:off | Disable:off | Restore:off | Save to List:off', await btns());
  check('and they look it (dimmed)', await page.evaluate(() => getComputedStyle(document.querySelector('.uad-action-grid .batch-grid-btn')).opacity) === '0.4');
  await page.click('.uad-action-grid >> text=Uninstall', { force: true }); await page.waitForTimeout(150);
  check('a tap on an off button opens nothing and calls nothing', await page.evaluate(() => !document.getElementById('batchConfirmModal').classList.contains('show') && window.__calls.length === 0));
  const one = await page.evaluate(() => { const p = uadVisiblePackages()[0].pkg; toggleUadSelect(p); return p; });
  check('one package selected: all four are on', (await btns()) === 'Uninstall:on | Disable:on | Restore:on | Save to List:on', await btns());
  await page.evaluate(p => toggleUadSelect(p), one);
  check('unselected again: off', (await btns()).split(' | ').every(s => s.endsWith(':off')), await btns());
  await page.click('text=Select shown');
  check('Select shown: on', (await btns()).split(' | ').every(s => s.endsWith(':on')), await btns());
  await page.evaluate(() => clearUadSelection());
  check('Clear: off', (await btns()).split(' | ').every(s => s.endsWith(':off')), await btns());
  await page.close();

  // ---- Console at 320 px: the "?" has its own room, in both directions ----
  const consoleRow = async (lang) => {
    const p = await open({ width: 320 });
    if (lang) { await p.evaluate(l => { const s = document.getElementById('langSelect'); if (s) s.value = l; chooseLanguage(l); }, lang); await p.waitForTimeout(500); }
    await p.evaluate(() => switchView('console')); await p.waitForTimeout(400);
    const m = async () => p.evaluate(() => {
      const q = document.querySelector('#cnTabs .help-q').getBoundingClientRect();
      const text = id => { const r = document.createRange(); r.selectNodeContents(document.getElementById(id)); return r.getBoundingClientRect(); };
      const box = id => document.getElementById(id).getBoundingClientRect();
      const hit = (a, c) => a.left < c.right && c.left < a.right && a.top < c.bottom && c.top < a.bottom;
      const ind = document.querySelector('#cnTabs .cn-ind').getBoundingClientRect();
      const sel = document.querySelector('#cnTabs .cn-tab[aria-selected="true"]').getBoundingClientRect();
      return {
        overText: ['cnTabShell', 'cnTabLog'].filter(id => hit(q, text(id))),
        overTab: ['cnTabShell', 'cnTabLog'].filter(id => hit(q, box(id))),
        inRow: q.left >= document.getElementById('cnTabs').getBoundingClientRect().left && q.right <= document.getElementById('cnTabs').getBoundingClientRect().right,
        indOff: Math.round(Math.abs(ind.left - sel.left) + Math.abs(ind.width - sel.width)),
        clipped: ['cnTabShell', 'cnTabLog'].filter(id => { const e = document.getElementById(id); return e.scrollWidth > e.clientWidth; }),
      };
    });
    const a = await m();
    await p.evaluate(() => cnShowSeg('log')); await p.waitForTimeout(450);
    const c = await m();
    await p.close();
    return { a, c };
  };
  for (const lang of ['', 'de', 'ar']) {
    const { a, c } = await consoleRow(lang);
    const tag = lang || 'en';
    check(tag + ': the "?" covers neither tab nor its text, and stays inside the row', !a.overText.length && !a.overTab.length && a.inRow, JSON.stringify(a));
    check(tag + ': both tab labels fit', !a.clipped.length, JSON.stringify(a.clipped));
    check(tag + ': the underline lines up with the selected tab (ADB Shell, then Live Logcat)', a.indOff <= 1 && c.indOff <= 1, a.indOff + ' ' + c.indOff);
  }

  // ---- the toast on the inverse surface ----
  const lum = s => { const v = s.match(/[\d.]+/g).slice(0, 3).map(n => +n / 255).map(c => c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
  const ratio = (x, y) => { const a = lum(x), c = lum(y); return (Math.max(a, c) + 0.05) / (Math.min(a, c) + 0.05); };
  for (const dark of [false, true]) {
    const p = await open({ dark });
    await p.evaluate(() => showToast('Spotify was frozen')); await p.waitForTimeout(450);
    const t = await p.evaluate(() => {
      const probe = v => { const e = document.createElement('div'); e.style.color = 'var(' + v + ')'; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return c; };
      const cs = getComputedStyle(document.getElementById('toast'));
      return { bg: cs.backgroundColor, fg: cs.color, text: probe('--text-main'), page: probe('--bg-base'), card: probe('--bg-card') };
    });
    const tag = dark ? 'dark' : 'light';
    check(tag + ': the toast is filled with the text colour and written in the page colour', t.bg === t.text && t.fg === t.page, JSON.stringify(t));
    check(tag + ': it stands out from the cards (contrast at least 7:1) and its text reads (at least 7:1)', ratio(t.bg, t.card) >= 7 && ratio(t.bg, t.fg) >= 7, ratio(t.bg, t.card).toFixed(1) + ' ' + ratio(t.bg, t.fg).toFixed(1));
    if (!dark) check('light: the toast is dark (luminance under 0.2)', lum(t.bg) < 0.2, lum(t.bg).toFixed(3));
    await p.close();
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
