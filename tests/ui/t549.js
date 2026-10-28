// Settings > Row tints > Gradient (the owner's wish): a switch right under the Row tints slider, in Settings' switch style, off by default since v7.18.1 (the
// owner's wish: one even colour until the person turns the gradient on). Off makes every tinted row one even colour, no gradient image in its tint layer: the
// Apps tab's running, frozen, uninstalled and selected rows, Connected Devices' app rows, Live Logcat's rows and Connected Devices' Logcat rows; the strength
// still follows the slider (Off draws none), and on the even colour the package line leans toward the main text. On draws the tints as before v7.18.1 (a glow
// and wash in the Apps rows, a wash from the leading edge in the Logcats). A choice of either is kept across a reload (on, the one a default would undo, too),
// the even colour is drawn without color-mix() too, and at Vivid every text of a tinted row that reads at 4.5:1 with Row tints Off still does on the
// even fill, in every palette, dark and light (the Apps rows measured on the pixels with the text hidden, the Logcats on the mixed colour, as t411, t476 and
// t477 do).
const fs = require('fs');
const { fileURLToPath } = require('url');
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
const consoleMock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const LOG = ['V', 'D', 'I', 'W', 'E', 'F'].map((l, i) => '10-12 10:00:0' + i + '.123  1  1 ' + l + ' Tag' + l + ': message ' + l).join('\n') + '\n';
const CD_APPS = [
  { pkg: 'com.a.clock', name: 'Clock', system: true, disabled: true, uninstalled: false },
  { pkg: 'com.a.gone', name: 'Gone', system: true, disabled: false, uninstalled: true },
  { pkg: 'com.a.maps', name: 'Maps', system: false, disabled: false, uninstalled: false },
];
const STEPS = [[0, 'Off'], [1, 'Faint'], [2, 'Subtle'], [3, 'Normal'], [4, 'Vivid']];
const PRESETS = ['material3', 'materialyou', 'amoled', 'cyberpunk', 'emerald', 'amber', 'slate', 'crimson'];          // every palette of Settings > Theme

// the native settings store that survives a reload (handed to the page again by the script, as t118 does), over the mocks' own
function settingsStore(saved) {
  window.__settings = Object.assign({}, saved || {});
  const b = window.AndroidBridge = window.AndroidBridge || {};
  b.loadSetting = k => window.__settings[k] || '';
  b.saveSetting = (k, v) => { window.__settings[k] = v; return true; };
}

// Every tint layer of the page: the Apps tab's rows, Connected Devices' app rows, Live Logcat's and Connected Devices' Logcat rows
function layers() {
  const probe = c => { const e = document.createElement('i'); e.style.color = c; document.body.appendChild(e); const v = getComputedStyle(e).color; e.remove(); return v; };
  const one = (el, kind, want) => {
    const a = getComputedStyle(el, '::before');
    return { kind, img: a.backgroundImage, color: a.backgroundColor, want, o: a.content === 'none' || a.content === 'normal' ? null : Math.round(+a.opacity * 1000) / 1000 };
  };
  const out = [];
  const state = c => ['running', 'frozen', 'uninstalled'].find(k => c.classList.contains(k)) || '';
  const ink = { running: probe('var(--apps-running)'), frozen: probe('var(--apps-frozen)'), uninstalled: probe('var(--apps-uninstalled)') };
  const accent = probe('var(--accent)');
  document.querySelectorAll('#view-apps .app-card').forEach(c => { if (state(c) || c.classList.contains('selected')) out.push(one(c, 'apps ' + (c.classList.contains('selected') ? 'selected' : state(c)), c.classList.contains('selected') ? accent : ink[state(c)])); });
  document.querySelectorAll('#cdAppList .app-card').forEach(c => { if (state(c)) out.push(one(c, 'device ' + state(c), ink[state(c)])); });
  const dot = l => probe(getComputedStyle(document.querySelector('#logcatKey .lvl-' + l + ' .lc-dot')).backgroundColor);
  document.querySelectorAll('#logcatOutput .lc-row').forEach(r => { const l = r.className.match(/lvl-(\w)/)[1]; out.push(one(r, 'live logcat ' + l, dot(l))); });
  document.querySelectorAll('#cdLcOut .lc-row').forEach(r => { const l = r.className.match(/lvl-(\w)/)[1]; out.push(one(r, 'device logcat ' + l, dot(l))); });
  return out;
}

// The package line's colour on a running row of the Apps tab (not selected) and on a plain row
function pkgInk() {
  const c = s => getComputedStyle(document.querySelector('#view-apps .app-card' + s + ' .app-pkg')).color;
  return { plain: c(':not(.running):not(.frozen):not(.uninstalled):not(.selected)'), running: c('.running:not(.selected)') };
}

(async () => {
  const b = await chromium.launch();
  const errors = [];

  // ---- 1. the switch, its default, the even colour, the reload ----
  {
    const page = await b.newPage({ viewport: { width: 400, height: 1500 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(consoleMock.initScript, { kv: { perm_intro_v61: '1' }, log: LOG });
    await page.addInitScript(appsMock.initScript, { dark: true });
    await page.addInitScript(settingsStore, { perm_intro_v61: '1' });
    await page.goto(PAGE); await page.waitForTimeout(600);
    const ev = (fn, a) => page.evaluate(fn, a);
    await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });

    await ev(() => switchView('prefs')); await page.waitForTimeout(200);
    const sw = await ev(() => {
      const card = document.getElementById('rowTintCard'), tog = document.getElementById('rowTintGradToggle');
      if (!card || !tog) return { missing: true };
      const row = tog.closest('label.switch-row'), slider = card.querySelector('.row-tint-slider');
      const other = document.getElementById('hapticToggle').closest('label.switch-row');
      const st = e => { const s = getComputedStyle(e); return [s.display, s.borderTopStyle, s.borderTopWidth, s.marginTop, s.paddingTop, s.justifyContent].join(' '); };
      const sr = slider.getBoundingClientRect(), rr = row.getBoundingClientRect();
      return { inCard: !!row && card.contains(row), afterSlider: !!row && row.previousElementSibling === slider, below: rr.top >= sr.bottom - 1, last: !!row && !row.nextElementSibling,
        title: row && row.querySelector('.switch-title').textContent, sub: !!(row && row.querySelector('.switch-sub').textContent.trim()), track: !!(row && row.querySelector('.switch-input + .switch-track')),
        sameStyle: st(row) === st(other), checked: tog.checked, type: tog.type, attr: document.documentElement.getAttribute('data-row-grad'), shown: rr.height > 0 };
    });
    check('Settings > Row tints has a Gradient switch right under the slider, inside the card, in the switch-row style of the other cards', !sw.missing && sw.inCard && sw.afterSlider && sw.below && sw.last && sw.title === 'Gradient' && sw.sub && sw.track && sw.sameStyle && sw.type === 'checkbox' && sw.shown, JSON.stringify(sw));
    check('   it is off by default (html data-row-grad="off", nothing stored)', sw.checked === false && sw.attr === 'off' && !('row_tint_grad' in await ev(() => window.__settings)), JSON.stringify(sw));

    // the lists, with every kind of tinted row
    // (Live Logcat and a device's Apps and Logcat are filled first: leaving the Apps tab clears its selection. The layers of the other tabs are read while hidden.)
    await ev(() => switchView('logcat')); await page.waitForTimeout(600);
    await ev(apps => { switchView('devices'); document.getElementById('cdWork').style.display = ''; cd.apps = apps; cd.filters.clear(); cdAppsRender(); }, CD_APPS);
    await ev(log => { window.cdReady = () => true; window.cdSh = async () => log; cd.sub = 'logcat'; document.querySelectorAll('.cd-pane').forEach(p => p.classList.toggle('active', p.id === 'cdPane-logcat')); }, LOG);
    await ev(() => cdLcRefresh()); await page.waitForTimeout(300);
    const toApps = async () => { await ev(() => { switchView('apps'); toggleSelectPkg('com.android.chrome'); toggleSelectPkg('com.amazon.mShop.android.shopping'); }); await page.waitForTimeout(200); };
    const tap = async () => { await ev(() => switchView('prefs')); await page.waitForTimeout(150); await page.click('#rowTintGradRow .switch-track'); await page.waitForTimeout(150); };
    await toApps();
    const kinds = l => l.map(x => x.kind.replace(/ \w$/, '')).filter((k, i, a) => a.indexOf(k) === i).join(', ');
    const dflt = await ev(layers);
    check('   so by default every tinted row is one even colour (' + kinds(dflt) + ')', dflt.length >= 18 && dflt.every(x => x.img === 'none' && x.color === x.want), JSON.stringify(dflt.filter(x => !(x.img === 'none' && x.color === x.want))));

    // on: a real tap on the switch
    await tap();
    const on = await ev(() => ({ checked: document.getElementById('rowTintGradToggle').checked, attr: document.documentElement.getAttribute('data-row-grad'), kv: window.__settings.row_tint_grad }));
    check('a tap turns it on: html data-row-grad="on", kept as row_tint_grad true', on.checked === true && on.attr === 'on' && on.kv === 'true', JSON.stringify(on));
    await toApps();
    const before = await ev(layers), inkOn = await ev(pkgInk);
    check('   with it on, every tinted row is a gradient (' + kinds(before) + ')', before.length >= 18 && before.every(x => /gradient\(/.test(x.img)), JSON.stringify(before.filter(x => !/gradient\(/.test(x.img))));

    // off again: a tap
    await tap();
    const off = await ev(() => ({ checked: document.getElementById('rowTintGradToggle').checked, attr: document.documentElement.getAttribute('data-row-grad'), kv: window.__settings.row_tint_grad }));
    check('another tap turns it off: html data-row-grad="off", kept as row_tint_grad false', off.checked === false && off.attr === 'off' && off.kv === 'false', JSON.stringify(off));
    await toApps();

    const want = { Off: 0, Faint: 0.06, Subtle: 0.11, Normal: 0.2, Vivid: 0.24 };
    const seen = { apps: [], logcat: [] }, inks = {};
    for (const [v, name] of STEPS) {
      await ev(x => setRowTint(x), v); await page.waitForTimeout(80);
      const l = await ev(layers);
      inks[name] = await ev(pkgInk);
      const even = l.filter(x => x.img === 'none' && x.color === x.want);
      check('Row tints ' + name + ', Gradient off: every tinted row is one even colour, its state\'s, the accent or its level dot\'s (' + l.length + ' rows)', l.length >= 18 && even.length === l.length, JSON.stringify(l.filter(x => !even.includes(x))));
      const st = l.filter(x => /^(apps|device) (running|frozen|uninstalled)$/.test(x.kind)), sel = l.filter(x => x.kind === 'apps selected'), lc = l.filter(x => /logcat/.test(x.kind));
      const so = [...new Set(st.map(x => x.o))], lo = [...new Set(lc.map(x => x.o))];
      seen.apps.push(so[0]); seen.logcat.push(lo[0]);
      check('   the app rows (Apps and Connected Devices) and the Logcat rows at 0.4 of the step\'s strength, the selected rows at 0.1',
        so.length === 1 && Math.abs(so[0] - want[name] * 0.4) < 0.002 && lo.length === 1 && Math.abs(lo[0] - want[name] * 0.4) < 0.002 && sel.length === 2 && sel.every(x => x.o === 0.1), JSON.stringify({ so, lo, sel: sel.map(x => x.o) }));
    }
    check('on the even colour the package line leans toward the main text (Faint to Vivid); with the gradient, or at Off, it keeps the colour of a plain row',
      inkOn.running === inkOn.plain && inks.Off.running === inks.Off.plain && ['Faint', 'Subtle', 'Normal', 'Vivid'].every(k => inks[k].running !== inks[k].plain && inks[k].plain === inkOn.plain), JSON.stringify({ inkOn, inks }));
    check('the strength still follows the slider: Off none, each step more than the one before', seen.apps[0] === 0 && seen.logcat[0] === 0 && seen.apps.every((o, i) => !i || o > seen.apps[i - 1]) && seen.logcat.every((o, i) => !i || o > seen.logcat[i - 1]), JSON.stringify(seen));

    // on again: the very same gradients as before
    await ev(() => setRowTint(2));
    await tap(); await toApps();
    const back = await ev(layers);
    check('on again, every row is drawn exactly as before (the same gradients, the same strength)', JSON.stringify(back) === JSON.stringify(before) && await ev(() => document.documentElement.getAttribute('data-row-grad') === 'on' && window.__settings.row_tint_grad === 'true'), JSON.stringify(back.filter((x, i) => JSON.stringify(x) !== JSON.stringify(before[i]))));

    // kept across a reload: on (the choice the default would undo), then off
    const reload = async () => {
      const saved = await ev(() => JSON.parse(JSON.stringify(window.__settings)));
      await page.addInitScript(s => { Object.assign(window.__settings, s); }, saved);
      await page.reload(); await page.waitForTimeout(600);
      await ev(() => switchView('apps')); await page.waitForTimeout(300);
      return ev(() => { const a = getComputedStyle(document.querySelector('#view-apps .app-card.running'), '::before'); return { attr: document.documentElement.getAttribute('data-row-grad'), checked: document.getElementById('rowTintGradToggle').checked, menu: document.getElementById('smRowTintGrad').checked, img: a.backgroundImage, o: +a.opacity }; });
    };
    let re = await reload();
    check('after a reload the switch is still on (in Settings and in the search menu) and a running app\'s row is still a gradient', re.attr === 'on' && re.checked === true && re.menu === true && /gradient\(/.test(re.img) && Math.abs(re.o - 0.11) < 0.002, JSON.stringify(re));
    await tap();
    re = await reload();
    check('after a tap and a reload the switch is still off and a running app\'s row is still one even colour', re.attr === 'off' && re.checked === false && re.menu === false && re.img === 'none' && Math.abs(re.o - 0.11 * 0.4) < 0.002, JSON.stringify(re));

    await ev(() => { logcatStop(); cdLcStop(); });
    await page.close();
  }

  // ---- 1b. the Logcats at Vivid, Gradient off, in every palette (dark and light): every text (badge, tag, time, pid, message) that reads at 4.5:1 untinted
  // still does on the even colour, each on what is behind it, the row's mixed colour or the badge's own ----
  const lcLook = () => {
    const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
    const ratio = (a, c) => { const x = lum(a), y = lum(c); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const rgba = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 4).map(Number); return /^color\(srgb/.test(s) ? [n[0] * 255, n[1] * 255, n[2] * 255, n.length > 3 ? n[3] : 1] : [n[0], n[1], n[2], n.length > 3 ? n[3] : 1]; };
    const out = {};
    document.querySelectorAll('#logcatOutput .lc-row, #cdLcOut .lc-row').forEach(row => {
      const where = row.closest('#cdLcOut') ? 'device' : 'live', l = row.className.match(/lvl-(\w)/)[1];
      let up = row; while (up && rgba(getComputedStyle(up).backgroundColor)[3] < 1) up = up.parentElement;
      const base = rgba(up ? getComputedStyle(up).backgroundColor : 'rgb(0, 0, 0)'), a = getComputedStyle(row, '::before'), tint = rgba(a.backgroundColor), op = +a.opacity;
      const mixed = base.slice(0, 3).map((x, i) => x * (1 - op) + tint[i] * op);
      row.querySelectorAll('.lc-badge, .lc-tag, .lc-time, .lc-pid, .lc-msg').forEach(e => {
        for (let p = e; p && p !== row; p = p.parentElement) if (getComputedStyle(p).display === 'none') return;
        const own = rgba(getComputedStyle(e).backgroundColor), bg = own[3] >= 1 ? own.slice(0, 3) : mixed;
        out[where + ' ' + l + ' ' + e.className] = Math.round(ratio(rgba(getComputedStyle(e).color).slice(0, 3), bg) * 100) / 100;
      });
    });
    return out;
  };
  for (const dark of [true, false]) for (const preset of PRESETS) {
    const t = (dark ? 'dark' : 'light') + ' ' + preset;
    const page = await b.newPage({ viewport: { width: 400, height: 1500 } });
    page.on('pageerror', e => errors.push(t + ' ' + e.message));
    await page.addInitScript(consoleMock.initScript, { kv: { perm_intro_v61: '1' }, log: LOG });
    await page.addInitScript(appsMock.initScript, { dark, preset });
    await page.addInitScript(settingsStore, { perm_intro_v61: '1', row_tint_grad: 'false' });
    await page.goto(PAGE); await page.waitForTimeout(600);
    const ev = (fn, a) => page.evaluate(fn, a);
    await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });
    await ev(log => { switchView('devices'); document.getElementById('cdWork').style.display = ''; window.cdReady = () => true; window.cdSh = async () => log; cd.sub = 'logcat'; document.querySelectorAll('.cd-pane').forEach(p => p.classList.toggle('active', p.id === 'cdPane-logcat')); }, LOG);
    await ev(() => switchView('logcat')); await page.waitForTimeout(600);
    await ev(() => cdLcRefresh()); await page.waitForTimeout(300);
    await ev(() => setRowTint(0)); await page.waitForTimeout(80);
    const plain = await ev(lcLook);
    await ev(() => setRowTint(4)); await page.waitForTimeout(80);
    const flat = await ev(lcLook);
    const grad = await ev(() => document.documentElement.getAttribute('data-row-grad'));
    const keys = Object.keys(flat), low = keys.filter(k => plain[k] >= 4.5 && !(flat[k] >= 4.5)), already = keys.filter(k => !(plain[k] >= 4.5));
    check(t + ', Vivid, Gradient off: every text of both Logcats (badge, tag, time, pid, message) that reads at 4.5:1 untinted still does on the even colour (' + keys.length + ' texts)', grad === 'off' && keys.length >= 50 && !low.length, low.map(k => k + ' ' + plain[k] + ' -> ' + flat[k]).join(', '));
    if (already.length) console.log('note ' + t + ': under 4.5:1 already untinted (not the switch\'s doing): ' + already.join(', '));
    await ev(() => { logcatStop(); cdLcStop(); });
    await page.close();
  }

  // ---- 2. a WebView without color-mix() (before Chrome 111): the even colour is still drawn ----
  for (const dark of [true, false]) {
    const page = await b.newPage({ viewport: { width: 400, height: 1500 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(consoleMock.initScript, { kv: { perm_intro_v61: '1' }, log: LOG });
    await page.addInitScript(appsMock.initScript, { dark });
    await page.addInitScript(settingsStore, { perm_intro_v61: '1', row_tint_grad: 'false', row_tint_lv: '3' });
    await page.route(u => u.href === PAGE, r => r.fulfill({ contentType: 'text/html', body: fs.readFileSync(fileURLToPath(PAGE), 'utf8').replace(/color-mix\(/g, 'no-color-mix(') }));
    await page.goto(PAGE); await page.waitForTimeout(600);
    await page.evaluate(() => { switchView('apps'); }); await page.waitForTimeout(300);
    await page.evaluate(() => toggleSelectPkg('com.android.chrome'));
    await page.evaluate(() => switchView('logcat')); await page.waitForTimeout(600);
    const l = await page.evaluate(layers);
    const ok = l.filter(x => x.img === 'none' && x.color === x.want && x.o > 0);
    check((dark ? 'dark' : 'light') + ', no color-mix(), Gradient off (kept from before): the app rows and Live Logcat\'s rows are one even colour, in their colours (' + l.length + ' rows)', l.length >= 10 && ok.length === l.length, JSON.stringify(l.filter(x => !ok.includes(x))));
    await page.evaluate(() => logcatStop());
    await page.close();
  }

  // ---- 3. the Apps rows at Vivid, Gradient off: every text of a tinted or selected row on the pixels behind it (the text hidden) ----
  for (const dark of [true, false]) for (const preset of PRESETS) {
    const t = (dark ? 'dark' : 'light') + ' ' + preset;
    const page = await b.newPage({ viewport: { width: 400, height: 1500 } });
    page.on('pageerror', e => errors.push(t + ' ' + e.message));
    await page.addInitScript(appsMock.initScript, { dark, preset });
    await page.goto(PAGE); await page.waitForTimeout(600);
    await page.evaluate(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important;animation:none!important}'; document.head.appendChild(st); switchView('apps'); });
    await page.waitForTimeout(300);
    await page.evaluate(() => { toggleSelectPkg('com.android.chrome'); toggleSelectPkg('com.amazon.mShop.android.shopping'); document.querySelectorAll('.sel-shine').forEach(c => c.classList.remove('sel-shine')); });
    // every element of those rows with text of its own
    const items = await page.evaluate(() => {
      const out = [];
      document.querySelectorAll('#view-apps .app-card').forEach(card => {
        const kind = (card.classList.contains('selected') ? 'selected ' : '') + (['running', 'frozen', 'uninstalled'].find(k => card.classList.contains(k)) || '');
        if (!kind.trim()) return;
        card.querySelectorAll('*').forEach(e => {
          if (![...e.childNodes].some(n => n.nodeType === 3 && n.textContent.trim()) || !e.getClientRects().length) return;
          e.dataset.t549 = out.length;
          out.push({ name: card.querySelector('.app-name').textContent + ' (' + kind.trim() + ') ' + (e.classList.contains('tag-badge') ? e.textContent : e.className.split(' ').pop()), badge: e.classList.contains('tag-badge') });
        });
      });
      return out;
    });
    const measure = async () => {
      const at = await page.evaluate(() => [...document.querySelectorAll('[data-t549]')].map(e => { const r = e.getBoundingClientRect(); return { i: +e.dataset.t549, color: getComputedStyle(e).color, x: Math.round(r.left) + 2, y: Math.round(r.top) + 2, w: Math.max(2, Math.round(r.width) - 4), h: Math.max(2, Math.round(r.height) - 4) }; }));
      const hide = await page.addStyleTag({ content: '[data-t549] { color: transparent !important; }' });
      const png = (await page.screenshot()).toString('base64');
      await hide.evaluate(e => e.remove());
      return page.evaluate(async ([png, at]) => {
        const img = new Image(); img.src = 'data:image/png;base64,' + png; await img.decode();
        const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0);
        const lum = ([r, g, b]) => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
        const rgb = s => { const n = s.match(/[\d.]+/g).slice(0, 3).map(Number); return /^color\(srgb/.test(s) ? n.map(v => v * 255) : n; };
        const out = {};
        for (const g of at) {
          const d = x.getImageData(g.x, g.y, g.w, g.h).data; let r = 0, gg = 0, bb = 0, n = 0;
          for (let i = 0; i < d.length; i += 4) { r += d[i]; gg += d[i + 1]; bb += d[i + 2]; n++; }
          const p = lum(rgb(g.color)), q = lum([r / n, gg / n, bb / n]);
          out[g.i] = Math.round((Math.max(p, q) + 0.05) / (Math.min(p, q) + 0.05) * 100) / 100;
        }
        return out;
      }, [png, at]);
    };
    await page.evaluate(() => { setRowTint(0); }); await page.waitForTimeout(150);
    const plain = await measure();
    await page.evaluate(() => { setRowTint(4); setRowTintGrad(false); }); await page.waitForTimeout(150);
    const flat = await measure();
    const low = items.map((it, i) => Object.assign({ i }, it)).filter(it => (it.badge || plain[it.i] >= 4.5) && !(flat[it.i] >= 4.5));
    const already = items.filter((it, i) => !it.badge && !(plain[i] >= 4.5));
    check(t + ', Vivid, Gradient off: every badge, and every other text of a tinted or selected row that reads at 4.5:1 with Row tints Off, still does on the even colour (' + items.length + ' texts)',
      items.length >= 25 && items.filter(x => x.badge).length >= 9 && !low.length, low.map(it => it.name + ' ' + plain[it.i] + ' -> ' + flat[it.i]).join(', '));
    if (already.length) console.log('note ' + t + ': under 4.5:1 already with Row tints Off (not the switch\'s doing): ' + already.map(x => x.name).join(', '));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
