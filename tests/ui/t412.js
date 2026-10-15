// A WebView before Chrome 111 (no color-mix()). 1) The tokens of the first :root sheet that are color-mix() mixes (--tint-*, --line-*) are
// written inline as plain colours computed from the theme (the colour at the mix's alpha), only where color-mix() is missing, and the JS mix
// agrees with the browser's own color-mix() (two colours, a translucent one, transparent). The badges, sheet buttons, the gear, the
// Navigation choice and the stat pills then have their colours instead of a white outline and no background. 2) On start, a WebView older
// than 111 (read from the user agent) gets a one-time notice with Update (the WebView's store page) and Not now; a current one gets none.
// About shows the WebView version from the user agent when the app cannot name the package.
const fs = require('fs');
const { fileURLToPath } = require('url');
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }

const SRC = fs.readFileSync(fileURLToPath(PAGE), 'utf8');
const OLD_UA = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Mobile Safari/537.36';

// [r, g, b, a] (0-255, 0-1) of a computed colour: rgb()/rgba() or color(srgb r g b / a)
function parse(c) {
  c = String(c || '').trim();
  let m = /^rgba?\(([^)]*)\)$/.exec(c);
  if (m) { const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; }
  m = /^color\(srgb ([^)]*)\)$/.exec(c);
  if (m) { const p = m[1].split(/[\s/]+/).filter(Boolean).map(Number); return [p[0] * 255, p[1] * 255, p[2] * 255, p.length > 3 ? p[3] : 1]; }
  return null;
}
function near(a, b, tol) { const x = parse(a), y = parse(b); if (!x || !y) return false; tol = tol || 2; return Math.abs(x[3] - y[3]) <= 0.012 && (x[3] < 0.01 || [0, 1, 2].every(i => Math.abs(x[i] - y[i]) <= tol)); }
function clear(c) { const x = parse(c); return !x || x[3] < 0.01; }

// the color-mix() tokens of the first :root sheet, as written in the page
const rootBlock = SRC.slice(SRC.indexOf(':root {'), SRC.indexOf('}', SRC.indexOf(':root {')));
const TOKENS = [...rootBlock.matchAll(/(--[\w-]+):\s*color-mix\(in srgb, var\((--[\w-]+)\) (\d+)%, transparent\);/g)].map(m => ({ name: m[1], src: m[2], pct: +m[3] }));
const ALL_MIX = (rootBlock.match(/--[\w-]+:\s*color-mix\(/g) || []).length;

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (opts, noColorMix, ctxOpts) => {
    const ctx = await b.newContext(Object.assign({ viewport: { width: 400, height: 1200 } }, ctxOpts || {}));
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(appsMock.initScript, opts);
    await page.addInitScript(o => {          // a settings store that outlives the page (handed in, read back), the store opener, About's info
      const kv = Object.assign({}, o.kv || {});
      window.__t412 = { kv, store: [], urls: [] };
      Object.assign(window.AndroidBridge, {
        loadSetting(k) { return Object.prototype.hasOwnProperty.call(kv, k) ? kv[k] : ''; }, saveSetting(k, v) { kv[k] = v; return true; },
        openInStore(p) { window.__t412.store.push(p); }, openUrl(u) { window.__t412.urls.push(u); },
        getAboutInfo() { return JSON.stringify(o.about || {}); },
      });
    }, opts);
    if (noColorMix) await page.route(u => u.href === PAGE, r => r.fulfill({ contentType: 'text/html', body: SRC.replace(/color-mix\(/g, 'no-color-mix(') }));
    await page.goto(PAGE); await page.waitForTimeout(600);
    page.ctx = ctx;
    return page;
  };
  const close = page => page.ctx.close();

  // ---- 1. the token fallback ----
  check(`the token sheet has its color-mix() tokens (${TOKENS.length}), each a colour mixed with transparent`, TOKENS.length >= 25 && TOKENS.length === ALL_MIX, TOKENS.length + ' of ' + ALL_MIX);
  const sample = page => page.evaluate(() => {
    const root = document.documentElement, rs = getComputedStyle(root);
    const host = document.createElement('div'); host.id = 't412host'; document.body.appendChild(host);
    host.innerHTML = '<span class="tag-badge tag-frozen">F</span><span class="tag-badge tag-system">S</span><span class="tag-badge tag-running">R</span>'
      + '<button class="sheet-btn-red">r</button><button class="sheet-btn-blue">b</button><button class="sheet-btn-green">g</button><button class="batch-grid-btn blue">x</button>'
      + '<button class="nav-opt" aria-checked="true">n</button><button class="filter-pill stat-card active" data-filter="frozen">1</button><button class="filter-pill stat-card" data-filter="running">2</button>'
      + '<button class="cd-row-act danger">d</button><span class="tag-badge tag-frozen cd-frozen">CF</span>';
    const cs = sel => getComputedStyle(host.querySelector(sel) || document.querySelector(sel));
    const probe = v => { const e = document.createElement('i'); e.style.color = v; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return c; };
    const out = {
      supported: colorMixSupported(), text: probe(rs.getPropertyValue('--text-main').trim()),
      inline: {}, srcs: {}, lineAccent: probe('var(--line-accent)'), lineFrozen: probe('var(--line-apps-frozen)'), lineRed: probe('var(--line-apps-uninstalled)'),
    };
    for (let i = 0; i < root.style.length; i++) { const n = root.style[i]; out.srcs[n] = root.style.getPropertyValue(n).trim(); }
    const el = {};
    for (const [k, sel] of Object.entries({ frozen: '.tag-frozen', system: '.tag-system', running: '.tag-running', red: '.sheet-btn-red', blue: '.sheet-btn-blue', green: '.sheet-btn-green', grid: '.batch-grid-btn', nav: '.nav-opt', pillOn: '.stat-card.active', pillOff: '.stat-card:not(.active)', cdRed: '.cd-row-act', cdFrozen: '.cd-frozen', gear: '#prefsHeaderBtn' })) {
      const s = cs(sel); el[k] = { bg: s.backgroundColor, img: s.backgroundImage, border: s.borderTopColor, color: s.color };
    }
    out.el = el;
    host.remove();
    return out;
  });
  for (const dark of [true, false]) for (const preset of ['material3', 'cyberpunk']) {
    const t = (dark ? 'dark' : 'light') + ' ' + preset;
    const modern = await open({ dark, preset });
    const m = await sample(modern);
    const old = await open({ dark, preset }, true);
    const o = await sample(old);
    check(`${t}: color-mix() is there in the test browser, and missing in the rewritten page`, m.supported === true && o.supported === false);
    check(`${t}: with color-mix(), no token is written inline (the sheet's own mixes are used)`, TOKENS.every(k => !m.srcs[k.name]), TOKENS.filter(k => m.srcs[k.name]).map(k => k.name).join(' '));
    const wrong = TOKENS.filter(k => {
      const hex = o.srcs[k.src], v = o.srcs[k.name];
      if (!/^#[0-9a-f]{6}$/i.test(hex || '') || !v) return true;
      const n = parseInt(hex.slice(1), 16), want = 'rgba(' + [(n >> 16) & 255, (n >> 8) & 255, n & 255].join(', ') + ', ' + k.pct / 100 + ')';
      return v !== want;
    }).map(k => k.name + '=' + o.srcs[k.name] + ' (' + k.src + ' ' + o.srcs[k.src] + ')');
    check(`${t}, no color-mix(): every color-mix() token is written inline as its colour at the mix's alpha (${TOKENS.length})`, wrong.length === 0, wrong.join(' | '));
    // the rules drawn straight from the tokens look exactly as they do with color-mix()
    const same = [];
    for (const k of ['frozen', 'system', 'running']) { if (!near(o.el[k].bg, m.el[k].bg)) same.push(k + ' bg ' + o.el[k].bg + ' vs ' + m.el[k].bg); if (!near(o.el[k].border, m.el[k].border)) same.push(k + ' border ' + o.el[k].border + ' vs ' + m.el[k].border); }
    for (const k of ['red', 'blue', 'green', 'grid']) if (!near(o.el[k].bg, m.el[k].bg)) same.push(k + ' bg ' + o.el[k].bg + ' vs ' + m.el[k].bg);
    check(`${t}, no color-mix(): the FROZEN, SYSTEM and RUNNING badges and the sheet and batch buttons have the same fill and outline as with color-mix()`, same.length === 0, same.join(' | '));
    // the rules that mix two colours themselves: their @supports not fallback, never the white outline or a missing fill
    const fb = [];
    if (!near(o.el.gear.border, o.lineAccent)) fb.push('gear border ' + o.el.gear.border);
    if (o.el.gear.img === 'none') fb.push('gear fill none');
    // the coloured action buttons are filled (v7.14) from fixed gradients that need no color-mix()
    if (!/linear-gradient/.test(o.el.red.img)) fb.push('red sheet button fill ' + o.el.red.img);
    if (!/linear-gradient/.test(o.el.cdRed.img)) fb.push('Connected Devices row button fill ' + o.el.cdRed.img);
    if (!near(o.el.cdFrozen.border, o.lineFrozen) || clear(o.el.cdFrozen.bg)) fb.push('Connected Devices FROZEN ' + o.el.cdFrozen.border + ' ' + o.el.cdFrozen.bg);
    if (o.el.nav.img === 'none') fb.push('Navigation choice fill none');
    if (o.el.pillOn.img === 'none') fb.push('active stat pill fill none');
    if (clear(o.el.pillOff.bg)) fb.push('stat pill fill ' + o.el.pillOff.bg);
    for (const k of ['gear', 'red', 'cdRed', 'frozen', 'system']) if (near(o.el[k].border, o.text, 1)) fb.push(k + ' has the text colour as outline');
    check(`${t}, no color-mix(): the gear, the sheet and row buttons, the Navigation choice and the stat pills are drawn from the tokens (no text-coloured outline, no missing fill)`, fb.length === 0, fb.join(' | '));
    await close(modern); await close(old);
  }
  // the JS mix is the browser's own color-mix()
  {
    const page = await open({ dark: true });
    const cases = ['color-mix(in srgb, #FF0000 25%, #0000FF)', 'color-mix(in srgb, #2979FF 14%, transparent)', 'color-mix(in srgb, #00E676 72%, #E8EAED)',
      'color-mix(in srgb, rgba(0, 0, 0, 0.35) 88%, #2979FF)', 'color-mix(in srgb, #FF9100 30%, rgba(255, 255, 255, 0.09))', 'color-mix(in srgb, var(--accent) 16%, var(--bg-card))',
      'color-mix(in srgb, #123456, #FEDCBA)', 'color-mix(in srgb, #A33CFF 40%, transparent 20%)'];
    const res = await page.evaluate(cases => cases.map(c => {
      const mine = colorMixCompute(c);
      const e = document.createElement('i'); document.body.appendChild(e);
      e.style.color = c; const theirs = getComputedStyle(e).color; e.style.color = mine || ''; const back = getComputedStyle(e).color; e.remove();
      return { c, mine, theirs, back };
    }), cases);
    const off = res.filter(r => !r.mine || !near(r.back, r.theirs, 1.5)).map(r => r.c + ' -> ' + r.mine + ' / browser ' + r.theirs);
    check(`colorMixCompute() gives the browser's own color-mix() result (${cases.length} mixes: two colours, translucent, transparent, var(), no percentage, both percentages)`, off.length === 0, off.join(' | '));
    const r = await page.evaluate(() => colorMixCompute('color-mix(in srgb, #FF0000 25%, #0000FF)') + ' ' + colorMixCompute('color-mix(in srgb, #FF0000 40%, transparent)') + ' ' + colorMixCompute('linear-gradient(red, blue)'));
    check('two colours give a blended hex, a mix with transparent the colour at that alpha, anything else nothing', r === '#4000BF rgba(255, 0, 0, 0.4) null', r);
    await close(page);
  }
  // a theme change in a page without color-mix(): the tokens follow the new accent
  {
    const page = await open({ dark: true, preset: 'material3' }, true);
    const r = await page.evaluate(() => {
      const before = document.documentElement.style.getPropertyValue('--tint-accent');
      themeState.preset = 'cyberpunk'; applyTheme(false);
      const acc = document.documentElement.style.getPropertyValue('--accent').trim(), after = document.documentElement.style.getPropertyValue('--tint-accent');
      return { before, after, want: hexToRgba(acc, 0.12).replace(/,/g, ', ') };
    });
    check('no color-mix(), a theme change: --tint-accent is computed again from the new accent', r.before !== r.after && r.after === r.want, JSON.stringify(r));
    await close(page);
  }

  // ---- 2. the out-of-date WebView notice ----
  const notice = page => page.evaluate(() => ({ shown: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, text: document.getElementById('mpAskText').textContent, ok: document.getElementById('mpAskOk').textContent, no: document.getElementById('mpAskNo').textContent, noShown: getComputedStyle(document.getElementById('mpAskNo')).display !== 'none', kv: window.__t412.kv, store: window.__t412.store.slice(), urls: window.__t412.urls.slice() }));
  {
    const page = await open({ dark: true, about: { versionName: '7.14.0' } }, false, { userAgent: OLD_UA });
    await page.waitForTimeout(1600);
    const n = await notice(page);
    check('Chrome/100: the notice is shown on start', n.shown, JSON.stringify(n));
    check('it names the version, says colours may look wrong, where to update it and the debloat case', /version 100\b/.test(n.text) && /colours may look wrong/i.test(n.text) && /Play Store/.test(n.text) && /debloat/.test(n.text) && /Android System WebView/.test(n.text), n.text);
    check('its buttons are Update and Not now', n.ok === 'Update' && n.no === 'Not now' && n.noShown, n.ok + ' / ' + n.no);
    check('it is remembered as soon as it is shown', !!n.kv.webview_old_notice && JSON.parse(n.kv.webview_old_notice).version === 100, JSON.stringify(n.kv));
    await page.click('#mpAskOk'); await page.waitForTimeout(150);
    const n2 = await notice(page);
    check('Update opens the store page of Android System WebView (market:// through openInStore) and closes the notice', !n2.shown && n2.store.join() === 'com.google.android.webview', JSON.stringify(n2.store));
    const about = await page.evaluate(() => { renderAbout(); const dt = [...document.querySelectorAll('#aboutBuild dt')].find(d => d.textContent === 'WebView'); return dt ? dt.nextElementSibling.textContent : ''; });
    check('About: the WebView line carries the version from the user agent when the app does not name the package', about === 'Chrome 100.0.4896.127', about);
    const ask = await page.evaluate(() => { mpAsk({ title: 'x', text: 'y' }); const t = document.getElementById('mpAskNo').textContent; mpAskDone(false); return t; });
    check('the next question has its Cancel button back', ask === 'Cancel', ask);
    const kv = n2.kv;
    await close(page);
    const again = await open({ dark: true, kv }, false, { userAgent: OLD_UA });
    await again.waitForTimeout(2200);
    const n3 = await notice(again);
    check('Chrome/100, the next start: no notice (it is shown once)', !n3.shown, JSON.stringify(n3));
    await close(again);
  }
  {
    const page = await open({ dark: true, about: { webview: 'com.android.chrome 99.0.4844.88' } }, false, { userAgent: OLD_UA });
    await page.waitForTimeout(1600);
    const n = await notice(page);
    await page.click('#mpAskNo'); await page.waitForTimeout(150);
    const n2 = await notice(page);
    check('Not now closes it without opening anything, and it stays remembered', n.shown && !n2.shown && !n2.store.length && !n2.urls.length && !!n2.kv.webview_old_notice, JSON.stringify(n2));
    const about = await page.evaluate(() => { renderAbout(); const dt = [...document.querySelectorAll('#aboutBuild dt')].find(d => d.textContent === 'WebView'); return dt ? dt.nextElementSibling.textContent : ''; });
    check('About: the WebView line is the package and version the app reads', about === 'com.android.chrome 99.0.4844.88', about);
    const pkg = await page.evaluate(() => { openWebViewStore(); return window.__t412.store.slice(); });
    check('where Chrome provides the WebView (Android 7-9), Update opens Chrome\'s store page', pkg.join() === 'com.android.chrome', pkg.join());
    await close(page);
  }
  {
    const page = await open({ dark: true });
    await page.waitForTimeout(2200);
    const n = await notice(page);
    const ua = await page.evaluate(() => webviewChromeMajor());
    check(`a current WebView (Chrome/${ua}): no notice, nothing remembered`, ua >= 111 && !n.shown && !n.kv.webview_old_notice, JSON.stringify(n));
    const v = await page.evaluate(() => [webviewChromeMajor('Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/74.0.3729.136 Mobile Safari/537.36'), webviewChromeMajor('Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/120.0'), webviewChromeMajor('')]);
    check('the version is read from the user agent: 74 for a WebView UA, 0 (no notice) for one without Chrome', v.join() === '74,0,0', v.join());
    await close(page);
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
