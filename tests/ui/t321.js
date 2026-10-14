// One close button for every sheet: each ✕ that closes a sheet is a <button class="sheet-close"> with a label a screen reader reads (and the
// page translates), a round 44 x 44 px target in the muted text colour with a title-size glyph, a pressed fill and a keyboard focus ring; it
// still runs the same close function (the app menu, the More sheet, the batch menu and What's new are tried).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadSetting() { return ''; }, saveSetting() { return true; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.whatsapp', name: 'WhatsApp' }, { pkg: 'com.spotify.music', name: 'Spotify' }]); },
      getWorkingMode() { return JSON.stringify({ isPrivileged: true, activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return JSON.stringify({ versionName: '1', permissions: [] }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  const all = await ev(() => {
    const closes = [...document.querySelectorAll('.sheet-close')];
    const glyph = el => /^(✕)$/.test(el.textContent.trim());
    const oldDivs = [...document.querySelectorAll('div, span')].filter(e => glyph(e) && !e.children.length && (e.style.fontSize === '20px' || e.classList.contains('hg-x')));
    return {
      n: closes.length, buttons: closes.every(c => c.tagName === 'BUTTON' && c.type === 'button'), labelled: closes.every(c => (c.getAttribute('aria-label') || '').startsWith('Close')),
      onclick: closes.every(c => /\w+\(.*\)/.test(c.getAttribute('onclick') || '')), glyph: closes.every(glyph), oldDivs: oldDivs.length,
      inSheets: closes.filter(c => c.closest('.modal-sheet, .batch-bottom-sheet')).length,
    };
  });
  check('1. every sheet closes with a .sheet-close button (type=button, aria-label "Close...", an onclick, the ✕ glyph), none with the old 20 px div', all.n >= 70 && all.buttons && all.labelled && all.onclick && all.glyph && all.oldDivs === 0 && all.inSheets === all.n, JSON.stringify(all));
  console.log('   close buttons in the page:', all.n);

  const rules = await ev(() => {
    const out = {};
    for (const ss of document.styleSheets) { let rs; try { rs = ss.cssRules; } catch (e) { continue; } for (const r of rs) if (r.selectorText && /^\.sheet-close(:active|:focus-visible)?$/.test(r.selectorText)) out[r.selectorText] = r.style.cssText; }
    return out;
  });
  check('2. a pressed state with a fill token and a focus ring in the accent colour', /background: var\(--fill-\d\)/.test(rules['.sheet-close:active'] || '') && /outline: 2px solid var\(--accent\)/.test(rules['.sheet-close:focus-visible'] || ''), JSON.stringify(rules));

  const look = sel => ev(sel => {
    const c = document.querySelector(sel), r = c.getBoundingClientRect(), cs = getComputedStyle(c), root = getComputedStyle(document.documentElement);
    const probe = document.createElement('span'); probe.style.color = 'var(--text-muted)'; probe.style.fontSize = 'var(--fs-title)'; document.body.appendChild(probe);
    const want = getComputedStyle(probe); const o = { color: want.color, fs: want.fontSize }; probe.remove();
    return { w: Math.round(r.width), h: Math.round(r.height), radius: cs.borderRadius, colorOk: cs.color === o.color, fsOk: cs.fontSize === o.fs, inView: r.left >= 0 && r.right <= innerWidth && r.top >= 0, bg: cs.backgroundColor, label: c.getAttribute('aria-label') };
  }, sel);
  const target = l => l.w === 44 && l.h === 44 && l.radius === '50%' && l.colorOk && l.fsOk && l.inView && /rgba\(0, 0, 0, 0\)|transparent/.test(l.bg);

  await ev(() => openInspector('com.whatsapp')); await page.waitForTimeout(450);
  let l = await look('#inspectorModal .sheet-close');
  check('3. the app menu\'s close: 44 x 44, round, muted, title-size glyph, no fill until pressed, inside the screen', target(l), JSON.stringify(l));
  await page.click('#inspectorModal .sheet-close'); await page.waitForTimeout(350);
  check('   a tap closes the app menu', !(await ev(() => document.getElementById('inspectorModal').classList.contains('show'))));

  await ev(() => bnavMoreOpen()); await page.waitForTimeout(450);
  l = await look('#bnavMoreModal .sheet-close');
  check('4. the More sheet has the same close (it was a square arrow button)', target(l), JSON.stringify(l));
  await page.click('#bnavMoreModal .sheet-close'); await page.waitForTimeout(350);
  check('   a tap closes the More sheet', !(await ev(() => document.getElementById('bnavMoreModal').classList.contains('show'))));

  await ev(() => { switchView('apps'); toggleSelectPkg('com.whatsapp'); expandBatchPanel(); }); await page.waitForTimeout(450);
  l = await look('#floatingBatchBar .sheet-close');
  check('5. the batch menu\'s close too, keeping its own label', target(l) && l.label === 'Close the batch menu', JSON.stringify(l));

  await ev(() => openWhatsNew()); await page.waitForTimeout(450);
  l = await look('#whatsNewModal .sheet-close');
  check('6. a sheet that had the plain div (What\'s new): same button', target(l), JSON.stringify(l));
  await page.keyboard.press('Tab'); await ev(() => document.querySelector('#whatsNewModal .sheet-close').focus()); await page.waitForTimeout(100);
  const ring = await ev(() => { const c = document.querySelector('#whatsNewModal .sheet-close'), cs = getComputedStyle(c); return { focused: document.activeElement === c, outline: cs.outlineStyle + ' ' + cs.outlineWidth }; });
  check('   it takes the keyboard focus and shows a ring', ring.focused && ring.outline === 'solid 2px', JSON.stringify(ring));
  await page.keyboard.press('Enter'); await page.waitForTimeout(350);
  check('   Enter on it closes the sheet', !(await ev(() => document.getElementById('whatsNewModal').classList.contains('show'))));

  // a language: the label is translated by the page's dictionary
  await ev(() => I18N.set('de')); await page.waitForTimeout(600);
  const de = await ev(() => ({ want: (window.__LANGS.de && window.__LANGS.de.x && window.__LANGS.de.x.Close) || '', got: [...document.querySelectorAll('.sheet-close')].map(c => c.getAttribute('aria-label')) }));
  check('7. in German the label reads "' + de.want + '" on every close but the batch menu\'s (its own translation)', de.want && de.want !== 'Close' && de.got.filter(x => x === de.want).length === de.got.length - 1 && !de.got.includes('Close'), JSON.stringify(de));

  check('8. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
