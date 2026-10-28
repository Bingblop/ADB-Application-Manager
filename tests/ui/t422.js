// v7.15: the app was renamed ADB/Root Full System Toolbox (it was ADB System Manager); v7.19.1: ADB/Root Toolbox (the owner's choice). The header title reads "ADB/Root" over "Toolbox"
// (two lines of near equal width, so it fits at 320 px), all of it in the text colour (v7.15, the owner's request; "ADB" and "Manager" were in the accent), and every word is drawn in a solid colour (no gradient clipped to
// transparent text, which some phones' WebView drew as nothing and left only "Full" / "System"), sized by fitHeaderTitle to the room beside the gear and the mode
// badge, at 320, 360 and 412 px, in dark and light. The page title, the About card and the share text use the new name.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
// the details (sizes, positions: they differ a little from one machine's fonts to another's) are printed only when a check fails
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const scheme of ['dark', 'light']) for (const w of [320, 360, 412]) {
    const tag = '[' + w + ' px, ' + scheme + ']';
    const ctx = await b.newContext({ viewport: { width: w, height: 760 }, colorScheme: scheme });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(tag + ' ' + e.message));
    await page.addInitScript(() => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, isSystemDarkMode() { return matchMedia('(prefers-color-scheme: dark)').matches; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getSystemInfo() { return JSON.stringify({ device: 'SM-N986U1', manufacturer: 'samsung', release: '13', oneUi: '5.0' }); },
      getWorkingMode() { return JSON.stringify({ configuredMode: 'auto', activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; } }; });
    await page.goto(PAGE); await page.waitForTimeout(700);
    const r = await page.evaluate(() => {
      const h1 = document.querySelector('.brand-text h1'), box = document.querySelector('.brand-box').getBoundingClientRect(), hdr = document.querySelector('.app-header').getBoundingClientRect();
      const bg = getComputedStyle(document.body).backgroundColor;
      const words = [...h1.querySelectorAll('.bt-line > span')].map(s => {
        const cs = getComputedStyle(s), rc = s.getBoundingClientRect();
        return { t: s.textContent, color: cs.color, fill: cs.webkitTextFillColor, clip: cs.backgroundClip || cs.webkitBackgroundClip, img: cs.backgroundImage, w: rc.width, h: rc.height, l: rc.left, r: rc.right, top: rc.top, bottom: rc.bottom };
      });
      const lines = [...h1.querySelectorAll('.bt-line')].map(l => l.textContent.trim());
      return { text: h1.textContent.replace(/\s+/g, ' ').trim(), lines, words, bg, font: parseFloat(h1.style.fontSize), box: { l: box.left, r: box.right }, hdr: { top: hdr.top, bottom: hdr.bottom }, accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(), overflow: document.documentElement.scrollWidth > innerWidth };
    });
    const solid = c => { const m = /rgba?\(([^)]+)\)/.exec(c || ''); if (!m) return false; const p = m[1].split(',').map(Number); return p.length < 4 || p[3] > 0.9; };
    check('1. ' + tag + ' the title is "ADB/Root Toolbox", on two lines: "ADB/Root" and "Toolbox"', r.text === 'ADB/Root Toolbox' && r.lines.join('|') === 'ADB/Root|Toolbox', JSON.stringify(r.lines));
    check('   every word is drawn: a solid colour and a solid text fill, no gradient clipped to the text', r.words.length === 2 && r.words.every(x => solid(x.color) && solid(x.fill) && x.clip !== 'text' && x.img === 'none'), JSON.stringify(r.words.map(x => [x.t, x.color, x.fill, x.clip])));
    check('   and the colour is not the background\'s', r.words.every(x => x.color !== r.bg), r.bg);
    check('   every word has a size and lies inside the header and the title\'s room', r.words.every(x => x.w > 10 && x.h > 8 && x.l >= r.box.l - 1 && x.r <= r.box.r + 1 && x.top >= r.hdr.top && x.bottom <= r.hdr.bottom), JSON.stringify(r.words.map(x => [x.t, Math.round(x.l), Math.round(x.r)])) + ' room ' + JSON.stringify(r.box));
    check('   the whole title is one colour, the text colour (no word in the accent)', await page.evaluate(() => { const ws = [...document.querySelectorAll('.brand-text h1 .bt-line > span')].map(s => getComputedStyle(s).color); const probe = document.createElement('span'); probe.style.color = 'var(--text-main)'; document.body.appendChild(probe); const want = getComputedStyle(probe).color; probe.remove(); return ws.length === 2 && ws.every(c => c === want); }));
    check('   fitHeaderTitle sized it (13 to 26 px) and nothing is wider than the screen', r.font >= 13 && r.font <= 26 && !r.overflow, r.font + ' px');
    if (w === 360 && scheme === 'dark') {
      await page.screenshot({ path: 'header-360.png', clip: { x: 0, y: 0, width: 360, height: 130 } });
      const names = await page.evaluate(() => ({ title: document.title, about: document.querySelector('.about-name').textContent }));
      check('2. the page title and the About card say ADB/Root Toolbox', names.title === 'ADB/Root Toolbox' && names.about === 'ADB/Root Toolbox', JSON.stringify(names));
      await page.evaluate(() => { window.__shared = []; window.AndroidBridge.shareText = (t, x) => window.__shared.push([t, x]); });
      await page.evaluate(() => { try { aboutShare(); } catch (e) { window.__shareErr = String(e); } });
      const sh = await page.evaluate(() => window.__shared && window.__shared[0] || window.__shareErr || null);
      check('   the share text names the app ADB/Root Toolbox', !!sh && /^ADB\/Root Toolbox: /.test(Array.isArray(sh) ? sh[1] : String(sh)), JSON.stringify(sh));
    }
    await ctx.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
