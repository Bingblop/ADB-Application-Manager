// The app menu's header at 320, 360 and 400 px (and in German and Spanish): the icon sits at the top of the name block, the dates and sizes
// wrap between words (never "Update/d" or "Ca/che"), Copy package and Copy name are whole and inside the sheet, the close and Share stay
// inside it (the close's round 44 px target may reach into the sheet's padding, its glyph not), and a long package name wraps inside the
// text column instead of running out of it.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const day = 864e5;
  const apps = [
    { pkg: 'com.whatsapp', name: 'WhatsApp', isSystem: false },
    { pkg: 'com.example.averyveryverylongpackagenamewithoutanybreakopportunity.app', name: 'TikTok - Videos, Music & LIVE Streaming with a very long name', isSystem: false },
  ];
  async function open(width, lang) {
    const page = await b.newPage({ viewport: { width, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message)); page.errors = errors;
    await page.addInitScript(([a, day]) => {
      const now = Date.now();
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(a); },
        getWorkingMode() { return JSON.stringify({ isPrivileged: true, activeMode: 'adb_tcp', modeAvailable: true }); },
        getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getUadInfo() { return '{}'; }, loadSetting() { return ''; }, saveSetting() { return true; },
        getAppDetails() { return JSON.stringify({ versionName: '26.0.3.1', versionCode: 2600031, firstInstallTime: now - 400 * day, lastUpdateTime: now - 3 * day, permissions: [], activities: [], services: [], appopsRaw: '' }); },
        getAppSizes() { return JSON.stringify({ apk: 180e6, splits: 1, usageAccess: true, stats: { app: 200e6, data: 412e6, cache: 88e6 } }); },
      };
    }, [apps, day]);
    await page.goto(PAGE); await page.waitForTimeout(500);
    if (lang) { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(500); }
    return page;
  }
  const measure = page => page.evaluate(() => {
    const q = s => document.querySelector('#inspectorModal ' + s);
    const sheet = q('.modal-sheet'), cs = getComputedStyle(sheet), sr = sheet.getBoundingClientRect();
    const left = sr.left + parseFloat(cs.paddingLeft) - 1, right = sr.right - parseFloat(cs.paddingRight) + 1;
    const vis = el => el && getComputedStyle(el).display !== 'none' && el.getBoundingClientRect().width > 0;
    // a word split over two lines has two client rects
    const splitWords = el => {
      const out = [], t = el.firstChild; if (!t || t.nodeType !== 3) return out;
      const re = /\S+/g; let m;
      while ((m = re.exec(t.nodeValue))) { const r = document.createRange(); r.setStart(t, m.index); r.setEnd(t, m.index + m[0].length); const lines = new Set([...r.getClientRects()].map(x => Math.round(x.top))); if (lines.size > 1) out.push(m[0]); }
      return out;
    };
    const outside = [];
    ['.sheet-header', '.sheet-header-actions', '.sheet-meta'].forEach(s => { const box = q(s); if (!box) return; [box, ...box.querySelectorAll('*')].forEach(el => { if (!vis(el)) return; const r = el.getBoundingClientRect(), wide = el.classList.contains('sheet-close'); if (r.left < (wide ? sr.left : left) || r.right > (wide ? sr.right : right)) outside.push((el.id || el.className || el.tagName) + ' ' + Math.round(r.left) + '..' + Math.round(r.right)); }); });
    const chips = [...document.querySelectorAll('#inspectorModal .copy-row .copy-chip')].map(c => { const r = c.getBoundingClientRect(); return { t: c.innerText, whole: c.scrollWidth <= c.clientWidth + 1 && r.left >= left && r.right <= right }; });
    const row = q('.copy-row');
    const av = q('#sheetAvatar').getBoundingClientRect(), h3 = q('#sheetTitle').getBoundingClientRect(), pkg = q('#sheetPkg'), pr = pkg.getBoundingClientRect(), col = q('.sheet-app-hero .sheet-title').getBoundingClientRect();
    const close = [...q('.sheet-header-top').children].find(e => e.textContent.trim() === '✕'), cr = close.getBoundingClientRect(), share = q('.sheet-header-top .copy-chip').getBoundingClientRect();
    return {
      split: [...splitWords(q('#sheetDates')), ...splitWords(q('#sheetSizes'))], dates: q('#sheetDates').innerText, sizes: q('#sheetSizes').innerText,
      outside, chips, rowClips: row.scrollWidth > row.clientWidth + 1,
      avatarTop: Math.abs(av.top - h3.top) <= 4, avatarDy: Math.round(av.top - h3.top),
      pkgInside: pr.right <= col.right + 1 && pkg.scrollWidth <= pkg.clientWidth + 1, pkgLines: Math.round(pr.height / parseFloat(getComputedStyle(pkg).lineHeight || 12) || 0), pkgH: Math.round(pr.height),
      closeIn: cr.right <= sr.right && cr.left >= sr.left && cr.right - (cr.width - 20) / 2 <= right, shareBeforeClose: share.right <= cr.left,
      ask: vis(document.getElementById('sheetAsk')), docW: document.documentElement.scrollWidth, W: innerWidth,
    };
  });
  for (const [w, lang] of [[320], [360], [400], [360, 'de'], [320, 'es']]) {
    const page = await open(w, lang);
    for (const a of apps) {
      await page.evaluate(p => openInspector(p), a.pkg); await page.waitForTimeout(450);
      const m = await measure(page);
      const tag = w + ' px' + (lang ? ' ' + lang : '') + ', ' + (a.pkg === 'com.whatsapp' ? 'WhatsApp' : 'long name and package');
      check(tag + ': the dates and sizes wrap only between words', m.split.length === 0, JSON.stringify(m.split) + ' | ' + m.dates + ' | ' + m.sizes);
      check(tag + ': Copy package and Copy name are whole and inside the sheet', m.chips.length === 2 && m.chips.every(c => c.whole) && !m.rowClips, JSON.stringify(m.chips));
      check(tag + ': nothing in the header (Ask agent shown too) runs past the sheet\'s padding', m.ask && m.outside.length === 0 && m.docW <= m.W, m.outside.slice(0, 4).join(' | '));
      check(tag + ': the icon is at the top of the name block', m.avatarTop, 'icon top - name top = ' + m.avatarDy);
      check(tag + ': the package name wraps inside the text column', m.pkgInside, JSON.stringify(m));
      check(tag + ': the close and Share are inside the sheet, Share left of the close', m.closeIn && m.shareBeforeClose, JSON.stringify(m));
      if (a.pkg !== 'com.whatsapp' && w === 320 && !lang) console.log('   the long package name takes more than one line at 320 px:', m.pkgH > 20);
      await page.evaluate(() => closeInspector()); await page.waitForTimeout(350);
    }
    check(w + ' px' + (lang ? ' ' + lang : '') + ': no page errors', page.errors.length === 0, page.errors.join(' | '));
    await page.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
