// Header: the working-mode badge stays on one line and the settings gear is a monochrome SVG. The badge used to wrap its words onto a second line
// ("Setup Mode (Read-" / "Only)"); now it never wraps: at 320 and 360 px the English mode names are whole (since v7.15 the badge shows the short
// name, without "Auto • " and the port, so even Wireless ADB is whole at 320 px), a longer one (German) ends in "…" inside
// the badge, and nothing pushes the page sideways. The gear was a colour emoji (the same gear as the Extra Settings tab); now it is an SVG drawn in
// the theme's text colour, in dark and light, still called Settings and still opening Settings.
// The badge's colours and the Updater's error lines come from the theme's status colours (the script wrote them as fixed orange and red): in the light
// theme the orange of Read-Only and Setup reads (4.5:1) and an error is in the theme's danger colour; with the colours the token sheet declares
// everything is the colour the script used to write, and the dark theme's orange is unchanged.
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
      check(tag + '  the whole name shows (no "…")', m.full <= m.shown, JSON.stringify([m.label, m.full, m.shown]));
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
  // the colours of the badge and of the Updater's error lines
  const colours = page => page.evaluate(async () => {
    const rgba = c => { const m = /^rgba?\(([^)]+)\)/.exec(c), n = /^color\(srgb ([^)]+)\)/.exec(c);
      if (m) { const v = m[1].split(',').map(Number); return [v[0], v[1], v[2], v.length > 3 ? v[3] : 1]; }
      if (n) { const [rgb, a] = n[1].split('/'); const v = rgb.trim().split(/\s+/).map(x => Math.round(Number(x) * 255)); return [v[0], v[1], v[2], a === undefined ? 1 : Number(a)]; }
      return null; };
    const lum = v => { const f = x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }; return 0.2126 * f(v[0]) + 0.7152 * f(v[1]) + 0.0722 * f(v[2]); };
    const over = (top, base) => [0, 1, 2].map(i => top[i] * top[3] + base[i] * (1 - top[3])).concat(1);
    const contrast = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const token = n => { const e = document.createElement('div'); e.style.color = 'var(' + n + ')'; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return rgba(c); };
    const badge = document.getElementById('execModeBadge'), cs = getComputedStyle(badge), dot = getComputedStyle(document.getElementById('execModeDot'));
    const ground = rgba(getComputedStyle(document.querySelector('.app-header')).backgroundColor);
    const bg = rgba(cs.backgroundColor), text = rgba(cs.color);
    usStatus('The versions could not be read.', true); const st = getComputedStyle(document.getElementById('usStatus'));
    const app = document.createElement('div'); app.className = 'us-app'; app.innerHTML = '<div id="usV0"></div>'; document.getElementById('usStatus').after(app);
    us.cands = [{ pkg: 'com.x' }]; us.busy = false; window.usList = async () => ({ ok: false, error: 'No versions.' }); window.usRead = () => ({});
    await usVersions(0); const pc = getComputedStyle(document.querySelector('#usV0 .us-pkg')), pkg = { color: pc.color, fontWeight: pc.fontWeight }; app.remove(); usStatus('');
    return { label: document.getElementById('execModeText').innerText, bg, border: rgba(cs.borderTopColor), text, dot: rgba(dot.backgroundColor), contrast: Math.round(contrast(text, over(bg, ground)) * 100) / 100,
      bloat: token('--status-bloat'), usStatus: rgba(st.color), usWeight: st.fontWeight, usPkg: rgba(pkg.color), usPkgWeight: pkg.fontWeight };
  });
  const same = (a, b) => !!a && !!b && a.every((x, i) => Math.abs(x - b[i]) <= (i === 3 ? 0.01 : 1));
  for (const mode of ['setup', 'readonly']) {
    const page = await open({ width: 360, dark: false, mode });
    const c = await colours(page);
    check('light ' + mode + ': the badge\'s orange reads on its wash (4.5:1), the dot is in the same colour', c.contrast >= 4.5 && same(c.dot, c.text), JSON.stringify(c));
    check('light ' + mode + ':   the Updater\'s error lines are in the theme\'s danger colour (bold as an error, the versions line plain as before)', same(c.usStatus, c.bloat) && same(c.usPkg, c.bloat) && c.usWeight === '700' && c.usPkgWeight === '400', JSON.stringify(c));
    await page.close();
  }
  {
    const page = await open({ width: 360, dark: true, mode: 'readonly' });
    const c = await colours(page);
    check('dark readonly: the badge\'s orange is the one it always was (#FFA726)', same(c.text, [255, 167, 38, 1]) && same(c.dot, [255, 167, 38, 1]), JSON.stringify(c));
    // the token sheet's own colours (what the page declares before a theme adjusts them): every state looks as the script used to paint it
    const was = { none: { bg: [255, 145, 0, 0.15], border: [255, 145, 0, 0.4], text: [255, 167, 38, 1], dot: [255, 167, 38, 1] }, root: { bg: [124, 77, 255, 0.15], border: [124, 77, 255, 0.4], text: [124, 77, 255, 1], dot: [124, 77, 255, 1] },
      on: { bg: [0, 230, 118, 0.15], border: [0, 230, 118, 0.4], text: [0, 230, 118, 1], dot: [0, 230, 118, 1] } };
    for (const [tone, m] of [['none', MODES.setup], ['none', { configuredMode: 'shizuku', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false }], ['root', { configuredMode: 'root', activeMode: 'root', modeAvailable: true, isPrivileged: true }], ['on', MODES.tcp]]) {
      await page.evaluate(m => { window.AndroidBridge.getWorkingMode = () => JSON.stringify(m); checkAllWorkingModes(false); document.documentElement.removeAttribute('style'); }, m);
      await page.waitForTimeout(400);          // the badge eases into its new colours (transition: all 0.2s)
      const c = await colours(page), w = was[tone];
      check('token sheet colours, ' + c.label + ': the badge as the script used to paint it', same(c.bg, w.bg) && same(c.border, w.border) && same(c.text, w.text) && same(c.dot, w.dot), JSON.stringify(c));
      if (tone === 'on') check('token sheet colours: the Updater\'s error lines in #FF5252 as before', same(c.usStatus, [255, 82, 82, 1]) && same(c.usPkg, [255, 82, 82, 1]), JSON.stringify(c));
    }
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
