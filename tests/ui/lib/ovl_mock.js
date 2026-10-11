// Used with sdb_mock.js (it defines the base bridge and the settings database): adds the overlay manager and the Material You
// theme to the mock AndroidBridge. Run it as a second init script.
module.exports.initScript = function () {
  const targets = ['android', 'com.android.systemui', 'com.android.settings', 'com.android.launcher3', 'com.samsung.android.app.spage'];
  const ov = [
    ['android', 1, 'android.theme.customization.accent_color'],
    ['android', 1, 'android.theme.customization.adaptive_icon_shape'],
    ['android', 0, 'android.theme.customization.font'],
    ['android', 1, 'com.android.internal.systemui.navbar.gestural'],
    ['android', 0, 'com.android.internal.systemui.navbar.threebutton'],
    ['android', -1, 'com.google.android.overlay.gmsconfig.photos'],
    ['android', 0, 'com.android.theme.icon_pack.circular.android'],
    ['com.android.systemui', 1, 'com.android.systemui.clocks.metro'],
    ['com.android.systemui', 0, 'com.android.systemui.theme.dark'],
    ['com.android.systemui', 0, 'weird<b>id</b>&"quote\'s'],
    ['com.android.systemui', 1, 'com.android.systemui:fabricated_color']
  ];
  for (let t = 2; t < targets.length; t++) for (let i = 0; i < 40; i++) ov.push([targets[t], i % 3 === 0 ? 1 : i % 3 === 1 ? 0 : (i % 7 === 2 ? -1 : 0), 'overlay.' + targets[t].split('.').pop() + '.n' + String(i).padStart(2, '0') + (i % 9 === 0 ? '.theme.color' : '')]);
  window.__ovl = ov.map(r => ({ target: r[0], state: r[1], id: r[2] }));
  window.__ovlCalls = { list: 0, op: [], apply: [], restore: [], restoreUndo: [], palette: 0 };
  window.__ovlDeny = {};          // id -> error text: the change is refused
  window.__ovlFixed = {};         // id -> true: stays on whatever is asked
  window.__ovlNoAnswer = false;
  window.__ovlEmptyAnswer = false; // a change is answered with an empty list (and "not listed any more")
  window.__themeDeny = null;      // error text: the theme write is refused
  window.__themeWarning = null;   // text: the theme is written, but the Samsung switch would not change
  window.__sdk = 34;
  window.__paletteDelay = 600;    // how long Android takes to repaint after a theme change

  // a believable palette for a seed colour: tones of one hue (the real Monet engine does far more, this is only to see colours move)
  function hexToHsl(hex) {
    const r = parseInt(hex.slice(0, 2), 16) / 255, g = parseInt(hex.slice(2, 4), 16) / 255, b = parseInt(hex.slice(4, 6), 16) / 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min; let h = 0; const l = (max + min) / 2;
    if (d) { if (max === r) h = ((g - b) / d) % 6; else if (max === g) h = (b - r) / d + 2; else h = (r - g) / d + 4; h *= 60; if (h < 0) h += 360; }
    return [h, d ? d / (1 - Math.abs(2 * l - 1)) : 0];
  }
  function hsl(h, s, l) {
    const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2; let r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; } else if (h < 120) { r = x; g = c; } else if (h < 180) { g = c; b = x; } else if (h < 240) { g = x; b = c; } else if (h < 300) { r = x; b = c; } else { r = c; b = x; }
    const t = v => ('0' + Math.round((v + m) * 255).toString(16)).slice(-2);
    return ('#' + t(r) + t(g) + t(b)).toUpperCase();
  }
  const LS = [1, .99, .95, .9, .8, .7, .6, .5, .4, .3, .2, .1, 0];
  function paletteFor(seed) {
    const [h, s] = hexToHsl(seed);
    const row = (hue, sat) => LS.map(l => hsl(hue % 360, Math.min(1, sat), l));
    return { ok: true, sdk: window.__sdk, tones: [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000], accent1: row(h, Math.max(.35, s)), accent2: row(h, .16), accent3: row(h + 60, .25), neutral1: row(h, .06), neutral2: row(h, .08) };
  }
  window.__seed = '4F8A5B';
  window.__palette = paletteFor(window.__seed);
  const KEY = 'theme_customization_overlay_packages';
  function seedOf(raw) {
    try { const o = JSON.parse(raw); if (o['android.theme.customization.color_source'] === 'preset' && o['android.theme.customization.system_palette']) return o['android.theme.customization.system_palette']; } catch (e) {}
    return '4F8A5B';                       // the wallpaper
  }
  function wire() { return window.__ovl.map(o => [o.id, o.target, o.state]); }

  const b = window.AndroidBridge;
  b.overlayList = function (req) {
    window.__ovlCalls.list++;
    if (window.__ovlNoAnswer) return 'started';
    setTimeout(() => {
      if (window.__ovlListError) { window.onOverlayList(JSON.stringify({ req, ok: false, error: window.__ovlListError, advice: 'This phone has no overlay manager to talk to.', mode: 'adb_tcp' })); return; }
      window.onOverlayList(JSON.stringify({ req, ok: true, list: wire(), mode: 'adb_tcp', ms: 30 }));
    }, window.__delay);
    return 'started';
  };
  b.overlayOp = function (req, op, id) {
    window.__ovlCalls.op.push({ op, id });
    if (op !== 'enable' && op !== 'disable') return 'error: unknown operation';
    if (!id) return 'error: No overlay named';
    if (window.__ovlNoAnswer) return 'started';
    setTimeout(() => {
      if (window.__ovlEmptyAnswer) {                                   // the listing after the change came back empty: the overlay seems gone
        window.onOverlayOp(JSON.stringify({ req, op, id, ok: false, state: -2, error: 'That overlay is not listed any more', list: [], mode: 'adb_tcp' }));
        return;
      }
      const o = window.__ovl.find(x => x.id === id);
      const r = { req, op, id, ok: true, mode: 'adb_tcp' };
      if (window.__ovlUnknown && window.__ovlUnknown[id]) {            // the link drops: the change may or may not have been applied
        const u = window.__ovlUnknown[id]; delete window.__ovlUnknown[id];
        if (u.apply && o && o.state >= 0) o.state = op === 'enable' ? 1 : 0;
        window.onOverlayOp(JSON.stringify({ req, op, id, ok: false, unknown: true, error: 'No answer from the device', mode: 'adb_tcp' }));
        return;
      }
      if (window.__ovlDeny[id]) { r.ok = false; r.error = window.__ovlDeny[id]; r.answer = 'Error: ' + window.__ovlDeny[id]; r.advice = 'Android refused the request.'; r.state = o ? o.state : -2; r.list = wire(); }
      else if (!o) { r.ok = false; r.error = 'That overlay is not listed any more'; r.state = -2; r.list = wire(); }
      else if (o.state < 0) { r.ok = false; r.error = 'Android lists this overlay as unavailable, so it cannot be switched'; r.state = -1; r.list = wire(); }
      else {
        if (!(op === 'disable' && window.__ovlFixed[id])) o.state = op === 'enable' ? 1 : 0;
        r.state = o.state; r.ok = r.state === (op === 'enable' ? 1 : 0);
        if (!r.ok) r.error = 'Android did not switch it off (some overlays are fixed on)';
        r.list = wire();
      }
      window.onOverlayOp(JSON.stringify(r));
    }, window.__delay);
    return 'started';
  };
  function themeWrite(kind, req, value, requested, before) {
    setTimeout(() => {
      const r = { req, kind, mode: 'adb_tcp', requested, flags: [] };
      if (kind === 'apply') r.before = before;
      if (window.__themeUnknown) {
        const u = window.__themeUnknown; window.__themeUnknown = null;
        if (u.apply) { window.__db.secure[KEY] = value; const seed = seedOf(value); setTimeout(() => { window.__seed = seed; window.__palette = paletteFor(seed); }, window.__paletteDelay); }
        window.onThemeOp(JSON.stringify({ req, kind, ok: false, unknown: true, error: 'No answer from the device', mode: 'adb_tcp', flags: [] }));
        return;
      }
      if (window.__themeDeny) { r.ok = false; r.value = window.__db.secure[KEY] === undefined ? 'null' : window.__db.secure[KEY]; r.error = window.__themeDeny; r.answer = 'java.lang.SecurityException: ' + window.__themeDeny; r.advice = 'Android refused the request.'; if (window.__themeWarning) r.warning = window.__themeWarning; }
      else {
        window.__db.secure[KEY] = value; r.value = value; r.ok = true;
        if (window.__themeWarning) r.warning = window.__themeWarning;
        if (/preset/.test(value)) r.flags = ['global'];
        const seed = seedOf(value);
        setTimeout(() => { window.__seed = seed; window.__palette = paletteFor(seed); }, window.__paletteDelay);
      }
      window.onThemeOp(JSON.stringify(r));
    }, window.__delay);
  }
  b.themeApply = function (req, source, hex, style) {
    window.__ovlCalls.apply.push({ source, hex, style });
    const ok = ['TONAL_SPOT', 'VIBRANT', 'EXPRESSIVE', 'FRUIT_SALAD', 'RAINBOW', 'SPRITZ'].includes(style || 'TONAL_SPOT');
    if (!ok) return 'error: Unknown theme style';
    if (source === 'preset') {
      const m = /^#?([0-9a-fA-F]{6})$/.exec(hex || '');
      if (!m) return 'error: That is not a colour (use six hex digits, like 6750A4)';
      hex = m[1].toUpperCase();
    } else if (source !== 'home_wallpaper') return 'error: Unknown colour source';
    if (window.__ovlNoAnswer) return 'started';
    const st = style || 'TONAL_SPOT';
    const value = source === 'preset'
      ? '{"android.theme.customization.system_palette":"' + hex + '","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"' + st + '","_applied_timestamp":' + Date.now() + '}'
      : '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"' + st + '","_applied_timestamp":' + Date.now() + '}';
    themeWrite('apply', req, value, value, window.__db.secure[KEY] === undefined ? 'null' : window.__db.secure[KEY]);
    return 'started';
  };
  b.themeRestore = function (req, raw, undo) {
    window.__ovlCalls.restore.push(raw);
    window.__ovlCalls.restoreUndo.push(undo);
    if (typeof raw !== 'string' || raw.length > 4000) return 'error: The theme value is too long';
    if (window.__ovlNoAnswer) return 'started';
    themeWrite(undo ? 'undo' : raw === '' ? 'reset' : 'restore', req, raw, raw);
    return 'started';
  };
  b.getSystemPalette = function () {
    window.__ovlCalls.palette++;
    if (window.__sdk < 31) return JSON.stringify({ ok: false, sdk: window.__sdk, error: 'Material You colors need Android 12 or newer (this phone runs Android ' + (window.__sdk === 28 ? 9 : 11) + ').' });
    return JSON.stringify(window.__palette);
  };
};
