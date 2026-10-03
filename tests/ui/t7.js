// Light/dark themes: follow phone, forced modes, per-mode tweaks, Material You, light contrast, prefs migration
const { chromium, PAGE } = require('./lib/pw');
const DYN = { supported: true,
  dark: { accent: '#A8C7FA', bg: '#0B0D11', surface: '#2E3036', card: '#1A1C21', sheet: '#1B1B1F', running: '#D7BDE4', frozen: '#A8C7FA', system: '#BFC6DC', secondary: '#BFC6DC', bloat: '#F2B8B5', text: '#E2E2E9', muted: '#C4C6D0' },
  light: { accent: '#415F91', bg: '#FDFBFF', surface: '#E2E2E9', card: '#F0F0F7', sheet: '#F7F6FB', running: '#705575', frozen: '#415F91', system: '#565F71', secondary: '#565F71', bloat: '#B3261E', text: '#1A1C20', muted: '#44474E' } };
async function boot(b, opts) {
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.__errors = []; page.on('pageerror', e => page.__errors.push(e.message));
  await page.addInitScript(([o, dyn]) => {
    const st = { prefs: o.prefs || '{}', dark: o.dark, bar: null }; window.__st = st;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return st.prefs; }, savePreferences(p) { st.prefs = p; }, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return st.dark; }, setSystemBarColor(c) { st.bar = c; },
      getMaterialYouColors() { return JSON.stringify(dyn); },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.camera', name: 'Camera', isSystem: true, isRunning: true }, { pkg: 'com.facebook.katana', name: 'Facebook', isFrozen: true }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: true }, adbWireless: {}, shizuku: { installed: true, running: true }, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return JSON.stringify({ permissions: [{ name: 'android.permission.CAMERA', granted: true, protection: 'runtime', changeable: true }, { name: 'android.permission.INTERNET', granted: true, protection: 'normal', changeable: false }], appopsRaw: 'CAMERA: allow\nWAKE_LOCK: ignore', activityInfo: [{ name: 'a.Main', exported: true, enabled: true }, { name: 'a.Hidden', exported: false, enabled: true }], services: [] }); },
    };
  }, [opts, DYN]);
  await page.goto(PAGE); await page.waitForTimeout(300);
  return page;
}
const vars = p => p.evaluate(() => { const c = getComputedStyle(document.documentElement); return ['--accent','--bg-base','--bg-card','--text-main','--on-accent'].map(v => c.getPropertyValue(v).trim()).join(' ') + ' | mode=' + document.documentElement.dataset.appearance + ' bar=' + window.__st.bar; });
const saved = p => p.evaluate(() => { const s = JSON.parse(window.__st.prefs); return `preset=${s.preset} appearance=${s.appearance} overrides=${JSON.stringify(s.overrides)}`; });
(async () => {
  const b = await chromium.launch();
  // 1) New install, phone in light mode -> Material 3 light
  let p = await boot(b, { dark: false });
  console.log('new install (phone light):', await vars(p)); console.log('  saved:', await saved(p));
  await p.screenshot({ path: 'light_apps.png' });
  await p.evaluate(() => switchView('colors')); await p.waitForTimeout(300);
  console.log('  note:', await p.locator('#appearanceNote').innerText());
  await p.screenshot({ path: 'light_colors.png' });
  // 2) Phone switches to dark mode -> follows
  await p.evaluate(() => { window.__st.dark = true; onSystemAppearanceChanged(); });
  console.log('phone -> dark:', await vars(p));
  // 3) Force Light while phone is dark
  await p.click('.appearance-btn[data-appearance="light"]');
  console.log('forced light:', await vars(p));
  // 4) Tweak accent in light only, check dark untouched
  await p.evaluate(() => updateCustomColor('accent', '#0061A4'));
  console.log('  after light tweak:', await saved(p), '| reset visible:', await p.isVisible('#resetTweaksBtn'));
  await p.click('.appearance-btn[data-appearance="dark"]');
  console.log('dark (tweak not applied):', await vars(p), '| reset visible:', await p.isVisible('#resetTweaksBtn'));
  // 5) Material You both modes
  await p.click('.palette-preset-card[data-preset="materialyou"]'); console.log('material you dark:', await vars(p));
  await p.click('.appearance-btn[data-appearance="light"]'); console.log('material you light:', await vars(p));
  // 6) Classic palettes in light: contrast report
  const report = await p.evaluate(() => ['amoled','cyberpunk','emerald','amber','slate','crimson','material3'].map(n => {
    const s = resolvePresetScheme(n, 'light');
    return n + ': accent ' + contrastRatio(s.accent, s.card).toFixed(1) + ' text ' + contrastRatio(s.text, s.bg).toFixed(1) + ' muted ' + contrastRatio(s.muted, s.card).toFixed(1) + ' running ' + contrastRatio(s.running, s.card).toFixed(1) + ' bloat ' + contrastRatio(s.bloat, s.card).toFixed(1);
  }));
  console.log('light contrast (vs card):\n  ' + report.join('\n  '));
  await p.click('.palette-preset-card[data-preset="amber"]'); await p.waitForTimeout(300);
  await p.screenshot({ path: 'light_amber_colors.png' });
  await p.evaluate(() => { switchView('apps'); openInspector('com.sec.android.app.camera'); }); await p.waitForTimeout(300);
  await p.screenshot({ path: 'light_inspector.png' });
  await p.evaluate(() => { closeInspector(); applyPalettePreset('material3'); openWorkingModesModal(); }); await p.waitForTimeout(300);
  await p.screenshot({ path: 'light_modes.png' });
  console.log('errors1:', JSON.stringify(p.__errors));
  // 7) Existing v3.4 user (old flat prefs) keeps dark AMOLED
  p = await boot(b, { dark: false, prefs: JSON.stringify({ preset: 'amoled', accent: '#00E5FF', bg: '#000000', card: '#141A28', running: '#00E676', frozen: '#00E5FF', system: '#7C4DFF', bloat: '#FF5252', surface: '#10141E', text: '#FFFFFF', muted: '#8E9BAE', secondary: '#7C4DFF' }) });
  console.log('migrated v3.4 user:', await vars(p)); console.log('  saved:', await saved(p));
  // 8) Old custom colors migrate as a custom palette
  p = await boot(b, { dark: true, prefs: JSON.stringify({ preset: 'custom', accent: '#FF00AA', bg: '#050505', card: '#202020', running: '#00E676', frozen: '#FF00AA', system: '#7C4DFF', bloat: '#FF5252' }) });
  console.log('migrated custom:', await vars(p));
  await p.evaluate(() => switchView('colors')); await p.click('.appearance-btn[data-appearance="light"]'); console.log('  custom in light:', await vars(p));
  console.log('errors2:', JSON.stringify(p.__errors));
  await b.close(); })();
