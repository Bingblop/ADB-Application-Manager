// The header: no icon, the title flush left on two lines as large as the room next to the gear and the mode badge allows, two small lines under it in capitals ("SAMSUNG SM-S948U1" and
// "ANDROID 17 • ONE UI 9.0", the One UI part only on a Samsung), and the settings gear and the mode badge the same height.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  for (const [w, label, info, want] of [
    [400, 'Samsung', { device: 'SM-S948U1', manufacturer: 'samsung', release: '17', oneUi: '9.0' }, 'SAMSUNG SM-S948U1\nANDROID 17 • ONE UI 9.0'],
    [360, 'Samsung, 360 wide', { device: 'SM-S948U1', manufacturer: 'samsung', release: '17', oneUi: '6.1.1' }, 'SAMSUNG SM-S948U1\nANDROID 17 • ONE UI 6.1.1'],
    [320, 'Samsung, 320 wide', { device: 'SM-S948U1', manufacturer: 'samsung', release: '17', oneUi: '9.0' }, 'SAMSUNG SM-S948U1\nANDROID 17 • ONE UI 9.0'],
    [400, 'Pixel', { device: 'Pixel 9 Pro XL', manufacturer: 'Google', release: '16', oneUi: '' }, 'GOOGLE PIXEL 9 PRO XL\nANDROID 16'],
  ]) {
    const page = await b.newPage({ viewport: { width: w, height: 400 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(i => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return JSON.stringify(i); }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, adbTcp: { connected: true, port: 5555 } }); }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; } }; }, info);
    await page.goto(PAGE); await page.waitForTimeout(800);
    const r = await page.evaluate(() => {
      const h1 = document.querySelector('.brand-text h1'), g = document.getElementById('prefsHeaderBtn').getBoundingClientRect(), m = document.getElementById('execModeBadge').getBoundingClientRect();
      const box = document.querySelector('.brand-box').getBoundingClientRect(), hd = document.querySelector('.app-header').getBoundingClientRect();
      const lines = Array.from(h1.querySelectorAll('.bt-line')).map(l => { l.style.display = 'inline-block'; const wd = l.getBoundingClientRect().width; l.style.display = ''; return wd; });
      const sub = document.getElementById('deviceInfoSubtitle');
      return { icon: !!document.querySelector('.app-header .app-icon, .app-header img'), left: Math.round(box.left - hd.left), titleLines: h1.querySelectorAll('.bt-line').length, widest: Math.max.apply(null, lines), room: box.width, font: parseFloat(h1.style.fontSize),
        gearH: Math.round(g.height * 10) / 10, badgeH: Math.round(m.height * 10) / 10, gearW: Math.round(g.width), sub: sub.innerText, subLines: sub.querySelectorAll('.bt-sub').length, upper: getComputedStyle(sub).textTransform, overflow: document.documentElement.scrollWidth > innerWidth,
        subWide: Math.max.apply(null, Array.from(sub.querySelectorAll('.bt-sub')).map(l => l.scrollWidth)), subBox: box.width };
    });
    check(label + ': no icon in the header, the title starts at the left (' + r.left + ' px)', !r.icon && r.left <= 14);
    // Font metrics vary across Linux hosts. Check the layout, not an exact
    // font size in the golden output, and never waive overflow for small text.
    check(label + ': two title lines, as large as fits, the widest line inside the room', r.titleLines === 2 && r.widest <= r.room && (r.widest >= r.room * 0.8 - 14 || r.font <= 13.01), JSON.stringify(r));
    check(label + ': the subtitle is "' + want.replace('\n', '" and "') + '" in capitals', r.sub === want && r.subLines === 2 && r.upper === 'uppercase', JSON.stringify(r.sub));
    check(label + ': the subtitle is not wider than the room', r.subWide <= r.subBox + 1, r.subWide + ' > ' + r.subBox);
    check(label + ': the gear and the mode badge are the same height (' + r.gearH + ')', Math.abs(r.gearH - r.badgeH) < 0.6 && r.gearH >= 36, r.gearH + ' vs ' + r.badgeH);
    check(label + ': the page is not wider than the screen', !r.overflow);
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
