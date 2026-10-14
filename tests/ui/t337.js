// Header: the working-mode badge stays on one line and the settings gear is a monochrome SVG. The badge used to wrap its words onto a second line
// ("Setup Mode (Read-" / "Only)"); now it never wraps: at 320 and 360 px the English mode names are whole (but the longest, Wireless ADB with its
// port, at 320 px), a longer one (German) ends in "…" inside
// the badge, and nothing pushes the page sideways. The gear was a colour emoji (the same gear as the Extra Settings tab); now it is an SVG drawn in
// the theme's text colour, in dark and light, still called Settings and still opening Settings.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const MODES = {
    tcp: { adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true },
    wireless: { adbTcp: {}, adbWireless: { connected: true, port: 41235 }, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_wireless', modeAvailable: true, isPrivileged: true },
    setup: { adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'readonly', modeAvailable: false, isPrivileged: false },
    readonly: { adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'unprivileged', activeMode: 'unprivileged', modeAvailable: true, isPrivileged: false },
  };
  const open = async ({ width, dark = true, mode = 'tcp', lang = 'en' }) => {
    const page = await b.newPage({ viewport: { width, height: 760 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([dark, m]) => {
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: dark ? 'dark' : 'light', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
        loadCustomLists() { return '[]'; }, saveCustomLists() {}, getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung', model: 'SM-S928B', androidVersion: '14' }); }, isSystemDarkMode() { return dark; }, setSystemBarColor() {},
        loadSetting(k) { return k === 'perm_intro_v62' || k === 'perm_intro_v61' ? '1' : ''; }, saveSetting() { return true; },
        loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'Example', isSystem: false }]); },
        getWorkingMode() { return JSON.stringify(m); },
      };
    }, [dark, MODES[mode]]);
    await page.goto(PAGE); await page.waitForTimeout(450);
    if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(350); }
    return page;
  };
  const measure = page => page.evaluate(() => {
    const badge = document.getElementById('execModeBadge'), text = document.getElementById('execModeText'), gear = document.getElementById('prefsHeaderBtn');
    const br = badge.getBoundingClientRect(), tr = text.getBoundingClientRect(), gr = gear.getBoundingClientRect(), cs = getComputedStyle(text);
    const svg = gear.querySelector('svg.hdr-gear');
    const probe = v => { const e = document.createElement('div'); e.style.color = 'var(' + v + ')'; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return c; };
    return {
      label: text.innerText, lines: Math.round(tr.height / parseFloat(cs.lineHeight)), nowrap: cs.whiteSpace === 'nowrap', ellipsis: cs.textOverflow === 'ellipsis',
      full: text.scrollWidth, shown: text.clientWidth, badgeRight: Math.round(br.right), badgeH: Math.round(br.height), W: innerWidth, docW: document.documentElement.scrollWidth,
      gearText: gear.innerText.trim(), svg: !!svg, svgFill: svg ? getComputedStyle(svg).fill : '', svgColor: svg ? getComputedStyle(svg).color : '', textMain: probe('--text-main'),
      gearW: Math.round(gr.width), gearH: Math.round(gr.height), label2: gear.getAttribute('aria-label'), title: document.querySelector('.brand-text h1').getBoundingClientRect().right <= gr.left + 1,
    };
  });

  // English: every mode name is whole and on one line at 320 and 360 px
  for (const width of [320, 360]) {
    for (const mode of ['tcp', 'wireless', 'setup', 'readonly']) {
      const page = await open({ width, mode });
      const m = await measure(page);
      const tag = 'en ' + width + ' ' + mode + ': ';
      check(tag + 'one line, not wrapped', m.lines === 1 && m.nowrap && m.badgeH <= 40, JSON.stringify([m.label, m.lines, m.badgeH]));
      if (width === 320 && mode === 'wireless') check(tag + '  the longest name ("Auto • Wireless ADB (41235)") ends in "…" at 320 px', m.ellipsis && m.full > m.shown, JSON.stringify([m.label, m.full, m.shown]));
      else check(tag + '  the whole name shows (no "…")', m.full <= m.shown, JSON.stringify([m.label, m.full, m.shown]));
      check(tag + '  inside the screen, no sideways scroll, the title clear of the gear', m.badgeRight <= m.W && m.docW <= m.W && m.title, JSON.stringify([m.badgeRight, m.docW]));
      await page.close();
    }
  }
  // German: the longer names end in "…" inside the badge instead of wrapping or pushing the page
  for (const width of [320, 360]) {
    for (const mode of ['tcp', 'setup']) {
      const page = await open({ width, mode, lang: 'de' });
      const m = await measure(page);
      const tag = 'de ' + width + ' ' + mode + ': ';
      check(tag + 'one line, cut with an ellipsis when it is longer than its room', m.lines === 1 && m.nowrap && m.ellipsis && m.label !== '' && m.badgeH <= 40, JSON.stringify([m.label, m.lines, m.full, m.shown]));
      check(tag + '  inside the screen, no sideways scroll, the title clear of the gear', m.badgeRight <= m.W && m.docW <= m.W && m.title, JSON.stringify([m.badgeRight, m.docW]));
      await page.close();
    }
  }
  // the gear: an SVG in the theme's text colour, no emoji, still Settings
  for (const dark of [true, false]) {
    const page = await open({ width: 360, dark });
    const m = await measure(page);
    const tag = (dark ? 'dark' : 'light') + ': ';
    check(tag + 'the header gear is an SVG with no text (no colour emoji)', m.svg && m.gearText === '', JSON.stringify(m.gearText));
    check(tag + '  drawn in the theme\'s text colour', m.svgFill === m.textMain && m.svgColor === m.textMain, JSON.stringify([m.svgFill, m.textMain]));
    check(tag + '  a tap target of at least 38 x 38 px called Settings', m.gearW >= 38 && m.gearH >= 38 && m.label2 === 'Settings', JSON.stringify([m.gearW, m.gearH, m.label2]));
    await page.click('#prefsHeaderBtn'); await page.waitForTimeout(300);
    check(tag + '  a tap opens Settings', await page.evaluate(() => currentViewName() === 'prefs'));
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
