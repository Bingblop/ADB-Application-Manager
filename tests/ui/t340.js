// The type scale has a readable floor: the smallest labels (badges, stat and section overlines) are 11px, package names and the other
// secondary lines 12px, an app's name 14px, and each step of the scale stays larger than the one under it. No text the page draws on any
// tab, in the app sheet or in the More sheet is under 11px, at 320 and 360 px. Where the bigger text needed room, it still fits:
// the mode badge at 320 px, the Feature List names, the "Apps, Debloater" tag of the long-press guide, the More tiles and the
// Debloater's four buttons in German, the Doze phase names (two rows on a phone, none over another) and the terminal's SHIFT key.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const fm = require('./lib/fm_mock.js');
const xs = require('./lib/xs_mock.js');
const ovl = require('./lib/ovl_mock.js');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }
const VIEWS = ['apps', 'saved-lists', 'debloater', 'installer', 'files', 'console', 'terminal', 'settings', 'extrasettings', 'overlays', 'store', 'taskmgr', 'devices', 'morphe', 'sdm', 'about', 'prefs'];

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (w, lang, opts) => {
    const page = await b.newPage({ viewport: { width: w, height: 860 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(w + ' ' + e.message));
    await page.addInitScript(sdb.initScript);
    await page.addInitScript(fm.initScript, { files: { '/storage/emulated/0/a.txt': 'x' }, priv: true });
    await page.addInitScript(xs.initScript);
    await page.addInitScript(ovl.initScript);
    await page.addInitScript(appsMock.initScript, Object.assign({ dark: true, lang }, opts || {}));
    await page.goto(PAGE); await page.waitForTimeout(800);
    if (lang && lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(800); }
    return page;
  };
  const px = (page, v) => page.evaluate(v => parseFloat(getComputedStyle(document.documentElement).getPropertyValue(v)), v);

  // ---- the scale itself
  {
    const page = await open(360, 'en');
    const steps = ['--fs-overline', '--fs-pkg', '--fs-caption', '--fs-pill', '--fs-body', '--fs-code', '--fs-item', '--fs-label', '--fs-sheet', '--fs-lead', '--fs-title'];
    const v = {}; for (const s of steps) v[s] = await px(page, s);
    check('the smallest step, --fs-overline (badges, overlines), is at least 11px', v['--fs-overline'] >= 11, v['--fs-overline']);
    check('package names, --fs-pkg, are at least 12px', v['--fs-pkg'] >= 12, v['--fs-pkg']);
    check('captions and helper lines, --fs-caption, are at least 12px', v['--fs-caption'] >= 12, v['--fs-caption']);
    check('an app\'s name in a row, --fs-item, is at least 14px', v['--fs-item'] >= 14, v['--fs-item']);
    const order = steps.every((s, i) => i === 0 || v[s] > v[steps[i - 1]]);
    check('each step of the scale is larger than the one under it', order, JSON.stringify(v));

    // ---- the elements that used to be the smallest
    await page.evaluate(() => switchView('apps')); await page.waitForTimeout(500);
    const row = await page.evaluate(() => {
      const f = s => [...document.querySelectorAll(s)].filter(e => e.getClientRects().length).map(e => parseFloat(getComputedStyle(e).fontSize));
      return { badge: f('#view-apps .tag-badge'), pkg: f('#view-apps .app-pkg'), name: f('#view-apps .app-name'), kinds: [...new Set([...document.querySelectorAll('#view-apps .tag-badge')].map(e => e.className.replace('tag-badge', '').trim()))].sort() };
    });
    check('the list shows every status badge', row.kinds.join(' ') === 'tag-bloat tag-frozen tag-running tag-suspended tag-system tag-user', row.kinds.join(' '));
    check('status badges are at least 11px', row.badge.length > 5 && Math.min(...row.badge) >= 11, Math.min(...row.badge));
    check('package names in the list are at least 12px', row.pkg.length > 5 && Math.min(...row.pkg) >= 12, Math.min(...row.pkg));
    check('app names in the list are at least 14px', row.name.length > 5 && Math.min(...row.name) >= 14, Math.min(...row.name));
    await page.evaluate(() => openInspector('com.whatsapp')); await page.waitForTimeout(800);
    const sheetPkg = await page.evaluate(() => parseFloat(getComputedStyle(document.getElementById('sheetPkg')).fontSize));
    check('the app sheet\'s package name is at least 12px', sheetPkg >= 12, sheetPkg);
    await page.close();
  }

  // ---- nothing on any screen under 11px
  for (const w of [320, 360]) {
    const page = await open(w, 'en');
    const small = [];
    const sweep = async where => {
      const r = await page.evaluate(() => {
        const out = []; const tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
        while ((n = tw.nextNode())) {
          if (!n.textContent.trim()) continue;
          const el = n.parentElement; if (!el.getClientRects().length) continue;
          const cs = getComputedStyle(el); if (cs.visibility === 'hidden') continue;
          if (parseFloat(cs.fontSize) < 11) out.push((el.id ? '#' + el.id : el.tagName.toLowerCase() + '.' + [...el.classList].join('.')) + ' ' + cs.fontSize);
        }
        return out;
      });
      r.forEach(x => small.push(where + ' ' + x));
    };
    for (const v of VIEWS) { await page.evaluate(v => switchView(v), v); await page.waitForTimeout(450); await sweep(v); }
    await page.evaluate(() => { switchView('apps'); openInspector('com.whatsapp'); }); await page.waitForTimeout(800); await sweep('app sheet');
    await page.evaluate(() => closeInspector()); await page.waitForTimeout(300);
    await page.evaluate(() => bnavMoreOpen()); await page.waitForTimeout(500); await sweep('More');
    await page.evaluate(() => bnavMoreClose()); await page.waitForTimeout(300);
    check(w + ' px: no text under 11px on any tab, in the app sheet or the More sheet', small.length === 0, [...new Set(small)].slice(0, 8).join(', '));
    if (w === 320) {
      const mode = await page.evaluate(() => { const t = document.getElementById('execModeText'); return { fs: parseFloat(getComputedStyle(t).fontSize), whole: t.scrollWidth <= t.clientWidth, text: t.innerText }; });
      check('320 px: the mode badge reads "' + mode.text + '" whole, at no less than 11px', mode.whole && mode.fs >= 11, JSON.stringify(mode));
      await page.evaluate(() => switchView('terminal')); await page.waitForTimeout(500);
      const shift = await page.evaluate(() => { const k = document.querySelector('.tx-keys .tx-key-shift'); return k.scrollWidth <= k.clientWidth; });
      check('320 px: the terminal\'s SHIFT key shows the whole word', shift);
    }
    if (w === 360) {
      await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(500);
      const fl = await page.evaluate(() => [...document.querySelectorAll('#featureList .fl-name')].filter(e => { const r = document.createRange(); r.selectNodeContents(e); const box = e.getBoundingClientRect(); return [...r.getClientRects()].some(q => q.right > box.right + 1); }).map(e => e.innerText));
      check('360 px: every Feature List name stays inside its own box (not under the pin)', fl.length === 0, fl.join(', '));
    }
    await page.close();
  }

  // ---- German at 320 px, and the Doze phase names in a few languages
  for (const lang of ['en', 'de', 'es']) {
    const page = await open(320, lang);
    if (lang === 'de') {
      await page.evaluate(() => switchView('debloater')); await page.waitForTimeout(700);
      const uad = await page.evaluate(() => { const g = document.getElementById('uadActionGrid').getBoundingClientRect(); return [...document.querySelectorAll('#uadActionGrid .batch-grid-btn')].map(b => { const r = b.getBoundingClientRect(); return b.scrollWidth <= b.clientWidth && r.left >= g.left - 0.5 && r.right <= g.right + 0.5; }); });
      check('de 320 px: the Debloater\'s four buttons fit, every name whole, none past the card', uad.length === 4 && uad.every(Boolean), JSON.stringify(uad));
      await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(500);
      const tags = await page.evaluate(() => [...document.querySelectorAll('.hold-where')].filter(t => t.getClientRects().length).filter(t => t.getBoundingClientRect().right > t.closest('.hold-txt').getBoundingClientRect().right + 1).map(t => t.innerText));
      check('de 320 px: the long-press guide\'s place tags stay inside the card ("Apps, Debloater" moves under the name)', tags.length === 0, tags.join(', '));
      await page.evaluate(() => bnavMoreOpen()); await page.waitForTimeout(500);
      const tiles = await page.evaluate(() => [...document.querySelectorAll('#bnavMoreGrid .bn-tl')].filter(l => { const was = l.style.overflowWrap; l.style.overflowWrap = 'normal'; const cut = l.scrollWidth > l.clientWidth + 0.5; l.style.overflowWrap = was; return cut; }).map(l => l.innerText.replace(/\n/g, ' ')));
      check('de 320 px: every word on the More tiles fits its tile whole ("Gespeicherte", "Verbundene")', tiles.length === 0, tiles.join(', '));
      await page.evaluate(() => bnavMoreClose()); await page.waitForTimeout(300);
    } else if (lang === 'en') {
      await page.evaluate(() => switchView('debloater')); await page.waitForTimeout(700);
      const rows = await page.evaluate(() => new Set([...document.querySelectorAll('#uadActionGrid .batch-grid-btn')].map(b => Math.round(b.getBoundingClientRect().top))).size);
      check('en 320 px: the Debloater\'s four buttons stay on one row', rows === 1, rows);
    }
    await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(600);
    const dz = await page.evaluate(() => {
      const card = document.getElementById('extraDozeSection').getBoundingClientRect();
      const rects = [...document.querySelectorAll('#extraDozeSection .xdz-lbl')].map(l => { const r = document.createRange(); r.selectNodeContents(l); return [...r.getClientRects()]; });
      const hit = [];
      rects.forEach((a, i) => rects.forEach((c, j) => { if (j <= i) return; a.forEach(p => c.forEach(q => { if (p.left < q.right && q.left < p.right && p.top < q.bottom && q.top < p.bottom) hit.push(i + 1 + '/' + (j + 1)); })); }));
      const out = rects.flat().some(q => q.left < card.left || q.right > card.right);
      return { fs: parseFloat(getComputedStyle(document.querySelector('#extraDozeSection .xdz-lbl')).fontSize), hit: [...new Set(hit)], out };
    });
    check(lang + ' 320 px: the five Doze phase names, at 11px or more, do not run into each other or past the card', dz.fs >= 11 && !dz.hit.length && !dz.out, JSON.stringify(dz));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
