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
  window.__ovlCalls = { list: 0, op: [], apply: [], restore: [], restoreUndo: [], palette: 0, fab: [], un: [] };
  // v7.22.0 uninstalling an overlay (overlayUninstall): where pm path finds each APK (the default is an installed app under /data/app), a refusal
  // of the pm uninstall itself, and a copy to Download/ADB App Manager/Overlays that cannot be saved (nothing is removed then)
  window.__ovlPaths = {};         // id -> the APK path pm path prints ('' : not installed as a package); default /data/app/~~mock/<id>-1/base.apk
  window.__ovlUninstallDeny = {}; // id -> error text: pm uninstall refuses, the package stays
  window.__ovlNoBackup = false;   // true: the copy of the APK could not be saved, so nothing is removed
  window.__ovlDeny = {};          // id -> error text: the change is refused
  window.__ovlFixed = {};         // id -> true: stays on whatever is asked
  window.__ovlNoAnswer = false;
  window.__ovlEmptyAnswer = false; // a change is answered with an empty list (and "not listed any more")
  window.__themeDeny = null;      // error text: the theme write is refused
  window.__themeWarning = null;   // text: the theme is written, but the Samsung switch would not change
  window.__sdk = 34;
  window.__paletteDelay = 600;    // how long Android takes to repaint after a theme change
  window.__wallpaper = null;      // PR 3: the wallpaper colours getSystemPalette reports (an array of RRGGBB), none when null
  // the root-only theme controls (overlayFabricate): __root makes the working mode Root with su granted (isRootActive() true) once
  // checkAllWorkingModes() has read it again; the __fab* knobs shape the answer
  window.__root = false;
  window.__fabNeedsRoot = false;  // the Java gate says no (needsPrivilege), whatever the page thinks
  window.__fabDeny = null;        // error text: the overlay is listed on but Android did not take the colours (the judge's refusal)
  window.__fabUnknown = false;    // no answer from the shell: unknown
  window.__fabNoAnswer = false;   // the bridge never answers at all (the page's own timeout)
  window.__dark = true;           // PR 4: the phone's light or dark mode as isSystemDarkMode reports it (sdb_mock's answer, true, unless set false)
  // T-049: what getSystemPalette reports from Android 12 on for the root colours (MonetProfile.java). __profile: undefined follows __sdk as
  // MonetProfile.of does without the colour and boolean names (31-33 P12, 34 P14, anything else unknown; 14 QPR1 to
  // QPR3, P14Q, is set as a string), a string is reported as it is, null leaves profile and profileReason out (an older APK);
  // __profileReason: undefined is 'ok' for P12 P14 P14Q P15 P15Q1 P15Q2 P16 P16Q1 P16Q2 P17 (16 QPR1 and QPR2 from T-049 PR 9 on, 17 from
  // PR 10 on, 15.0 from PR 11 on, 14 QPR1 to QPR3 from PR 12 on; an APK from before reported 15.0, 16 QPR1, QPR2 and 17 'notYet', which a
  // script sets explicitly, and 14 QPR1 to QPR3 as P14) and 'unknown' otherwise. __build (Build.FINGERPRINT) and __contrast (UiModeManager.getContrast) are reported as they
  // are, and left out when null.
  window.__profile = undefined;
  window.__profileReason = undefined;
  window.__build = 'google/mock/mock:14/UP1A.231005.007/10754064:user/release-keys';
  window.__contrast = 0;
  // PR 13: the One UI value getSystemPalette reports (MonetProfile.oneUi: the version, '?' when a Samsung phone's could not be read), left
  // out when '' or null (not One UI, or an older APK); it only shows the card's One UI note, the profile follows __sdk and __profile as above
  window.__oneUi = '';

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
  // PR 4: settable, so a script can switch the mode between the tap on Apply and the answer (spec 2025 and 2026 take the shades for the mode)
  b.isSystemDarkMode = function () { return window.__dark !== false; };
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
  // MainActivity.overlayUninstall / overlayUninstallJob: the id is checked before anything is queued (OverlayRules.idProblem, a fabricated id),
  // the gate is RootGate.unlocked (su granted, or Shizuku as uid 0 or uid 1000: the __root and __shizuku1000 knobs), then pm path decides
  // where the APK lives (missing / system / data), the APK is copied to Download/ADB App Manager/Overlays (or nothing is removed), pm uninstall
  // runs and ok comes only from the read-back (pm path prints nothing and the list no longer has the id). Answered as window.onOverlayUninstall(json).
  const UN_DIR = '/storage/emulated/0/Download/ADB App Manager/Overlays/';
  const UN_NEEDS = 'Needs Super User, or Shizuku as root (uid 0) or the system user (uid 1000)';
  b.overlayUninstall = function (req, id) {
    window.__ovlCalls.un.push(id);
    if (typeof id !== 'string' || !id) return 'error: No overlay named';
    if (id.length > 300) return 'error: The overlay name is too long (300 characters at most)';
    if (id.charAt(0) === '-') return "error: An overlay name can't start with a dash";
    if (/[\s\u0000-\u001f\u007f\ufeff]/.test(id)) return "error: An overlay name can't contain spaces or control characters";
    if (id.indexOf(':') >= 0) return 'error: This overlay is not an app package; switching it off is all there is to remove';
    if (id.indexOf('/') >= 0 || id === '.' || id === '..') return "error: An overlay name can't contain a slash";
    if (id.length + 4 > 255) return 'error: The overlay name is too long for a file name (251 characters at most)';
    if (window.__ovlNoAnswer) return 'started';
    setTimeout(() => {
      const mode = window.__root ? 'root' : window.__shizuku1000 ? 'shizuku' : 'adb_tcp';
      const r = { req, id, ok: false, mode, needsPrivilege: false, where: '', path: '', backup: '', answer: '', error: '', advice: '', unknown: false, stale: false };
      if (!window.__root && !window.__shizuku1000) { r.needsPrivilege = true; r.error = UN_NEEDS; window.onOverlayUninstall(JSON.stringify(r)); return; }
      // OverlayRules.judgeProbe: the id must be an overlay that is off on this phone (the page's rule, enforced in Java for every caller)
      const cur = window.__ovl.find(x => x.id === id);
      if (!cur || cur.state !== 0) { r.error = id + ' is not a switched-off overlay on this phone. Reload the list.'; r.list = wire(); window.onOverlayUninstall(JSON.stringify(r)); return; }
      const path = Object.prototype.hasOwnProperty.call(window.__ovlPaths, id) ? window.__ovlPaths[id] : '/data/app/~~mock/' + id + '-1/base.apk';
      if (!path) { r.where = 'missing'; r.error = id + ' is not installed as a package (it may be part of another app, or already gone)'; r.list = wire(); window.onOverlayUninstall(JSON.stringify(r)); return; }
      r.path = path; r.answer = 'package:' + path;
      // OverlayRules.isDataPath / SYSTEM_ADVICE: /data/ and adopted storage (/mnt/expand/) are an app's own; anything else is firmware
      if (!/^\/(data|mnt\/expand)\//.test(path)) { r.where = 'system'; r.error = id + " is part of the phone's firmware, so it cannot be uninstalled. Switching it off is the way to stop it."; r.advice = 'Overlays that ship with the phone live on a read-only partition. Leave this one off, or remove it with a custom ROM or a module that changes the firmware.'; r.list = wire(); window.onOverlayUninstall(JSON.stringify(r)); return; }
      r.where = 'data';
      if (window.__ovlNoBackup) { r.error = 'No copy of the APK could be saved to Download/ADB App Manager/Overlays, so nothing was removed'; r.list = wire(); window.onOverlayUninstall(JSON.stringify(r)); return; }
      r.backup = UN_DIR + id + '.apk';
      if (window.__ovlUninstallDeny[id]) { r.error = window.__ovlUninstallDeny[id]; r.answer = 'Failure [' + window.__ovlUninstallDeny[id] + ']\npackage:' + path; r.advice = 'Android refused the request.'; r.list = wire(); window.onOverlayUninstall(JSON.stringify(r)); return; }
      const i = window.__ovl.findIndex(x => x.id === id);
      if (i >= 0) window.__ovl.splice(i, 1);
      r.ok = true; r.answer = 'Success'; r.list = wire();
      window.onOverlayUninstall(JSON.stringify(r));
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
    const o = Object.assign({}, window.__palette, { sdk: window.__sdk });      // the Android level follows the knob, whenever the palette was made
    if (Array.isArray(window.__wallpaper)) o.wallpaper = window.__wallpaper.slice();        // PR 3: WallpaperColors, when the phone reports them
    if (window.__profile !== null) {                                                        // T-049: the profile, its reason, the build, the contrast
      const sdk = window.__sdk, p = typeof window.__profile === 'string' ? window.__profile : sdk >= 31 && sdk <= 33 ? 'P12' : sdk === 34 ? 'P14' : 'unknown';
      o.profile = p;
      o.profileReason = typeof window.__profileReason === 'string' ? window.__profileReason
        : ['P12', 'P14', 'P14Q', 'P15', 'P15Q1', 'P15Q2', 'P16', 'P16Q1', 'P16Q2', 'P17'].includes(p) ? 'ok' : 'unknown';
    }
    if (window.__build !== null) o.build = window.__build;
    if (window.__contrast !== null) o.contrast = window.__contrast;
    if (typeof window.__oneUi === 'string' && window.__oneUi !== '') o.oneUi = window.__oneUi;
    return JSON.stringify(o);
  };
  // Root as the working mode, su granted and not vetoed (the shape t559 uses), while __root is set; Shizuku as the system user (uid 1000:
  // privileged, sysPrivActive, but not uid 0) while __shizuku1000 is set (v7.22.0); the base mock's ADB TCP (the shell, uid 2000) otherwise
  window.__shizuku1000 = false;
  const baseWorkingMode = b.getWorkingMode;
  b.getWorkingMode = function () {
    if (window.__shizuku1000 && !window.__root) return JSON.stringify({ activeMode: 'shizuku', configuredMode: 'shizuku', modeAvailable: true, isPrivileged: true, sysPrivActive: true, rootUidActive: false, rootAvailable: false, adbTcp: { connected: false, port: 5555 }, adbWireless: { connected: false, port: 0 }, shizuku: { installed: true, running: true, authorized: true, uid: 1000 } });
    if (!window.__root) return baseWorkingMode.call(this);
    return JSON.stringify({ activeMode: 'root', configuredMode: 'root', modeAvailable: true, isPrivileged: true, rootAvailable: true, rootGranted: true, rootWorkingModeActive: true, adbTcp: { connected: false, port: 5555 }, adbWireless: { connected: false, port: 0 }, shizuku: { installed: false, running: false, authorized: false } });
  };
  // This app's fabricated colour overlay, as MainActivity.overlayFabricate / overlayFabricateJob answer it: the plan is checked before anything
  // is queued ("error: ..." in the words of OverlayRules.fabricatePlanProblem and disablePlanOk), the ids come from the phone's Android (one
  // overlay from 14, one per colour on 12 and 13), the overlay joins the list, and the answer is the list plus a lookup per colour (the first
  // four), judged from those, as window.onOverlayFab(json). v7.22.0 (ColorBlendr parity): any android:color/<name> (OverlayRules.FAB_RESOURCE),
  // an entry may carry config "night" (and only that), a resource is named twice only within one configuration, at most 512 entries
  // (OverlayRules.MAX_FAB_ENTRIES); on 12 and 13 one overlay per entry without a configuration, named after the resource
  // (aam_monet_system_accent1_100, OverlayRules.entryOverlayName), the night entries skipped (perEntryPlan). On the profile the
  // mock reports, when it is P16Q1, P16Q2 or P17 (MonetProfile.suffixedPalettes: Java takes the profile itself), an unsuffixed palette shade is
  // refused and the lookups are OverlayRules.QPR_LOOKUPS the plan has, in that order, then its first other entries up to four. PR 10: on
  // P17 (MonetProfile.suffixedFixed) also an unsuffixed fixed colour (the 12 names exactly) is refused, and the lookups are
  // OverlayRules.P17_LOOKUPS (the QPR five, then system_primary_fixed_dark), at most six.
  const FAB_OWNER = 'com.android.shell:', FAB_NAME = 'aam_monet', FAB_RES = 'android:color/system_', FAB_PREFIX = 'android:color/';
  const FAB_MAX = 512;
  const QPR_LOOKUPS = ['accent1_600_light', 'accent1_600_dark', 'primary_light', 'surface_dark', 'primary_dim_dark'].map(n => FAB_RES + n);
  const P17_LOOKUPS = QPR_LOOKUPS.concat([FAB_RES + 'primary_fixed_dark']);
  const suffixed = () => window.__profile === 'P16Q1' || window.__profile === 'P16Q2' || window.__profile === 'P17';
  const suffixedFixed = () => window.__profile === 'P17';
  function lookupEntries(es) {
    if (!suffixed()) return es.slice(0, 4);
    const out = [];
    for (const want of suffixedFixed() ? P17_LOOKUPS : QPR_LOOKUPS) { const e = es.find(x => x.resource === want); if (e) out.push(e); }
    for (const e of es) { if (out.length >= 4) break; if (!out.includes(e)) out.push(e); }
    return out;
  }
  b.overlayFabricate = function (req, planJson) {
    let plan;
    try { plan = JSON.parse(planJson); } catch (e) { return 'error: the plan is not JSON'; }
    window.__ovlCalls.fab.push(plan);
    const op = plan.action || 'fabricate';
    if (op === 'fabricate') {
      const es = plan.entries;
      if (!Array.isArray(es) || !es.length) return 'error: The plan has no entries';
      if (es.length > FAB_MAX) return 'error: The plan has too many entries (' + FAB_MAX + ' at most)';
      const seen = new Set();
      for (let i = 0; i < es.length; i++) {
        const e = es[i] || {};
        if (typeof e.resource !== 'string' || !/^android:color\/[a-z0-9_]+$/.test(e.resource)) return 'error: Entry ' + (i + 1) + ' does not name a framework colour (android:color/...)';
        if (typeof e.argb !== 'string' || !/^0x[0-9A-Fa-f]{8}$/.test(e.argb)) return 'error: Entry ' + (i + 1) + ' is not a colour (0xAARRGGBB)';
        if (e.config !== undefined && e.config !== null && e.config !== 'night') return 'error: Entry ' + (i + 1) + ' names a configuration other than night';
        const key = e.resource + (e.config ? '|' + e.config : '');
        if (seen.has(key)) return 'error: Entry ' + (i + 1) + ' names a resource twice';
        seen.add(key);
      }
      if (suffixed()) for (let i = 0; i < es.length; i++) if (/^android:color\/system_(accent[1-3]|neutral[12]|error)_[0-9]+$/.test(es[i].resource)) return 'error: Entry ' + (i + 1) + ' names a palette color without _light or _dark, which this Android version does not take';
      if (suffixedFixed()) for (let i = 0; i < es.length; i++) if (/^android:color\/system_((primary|secondary|tertiary)_fixed(_dim)?|on_(primary|secondary|tertiary)_fixed(_variant)?)$/.test(es[i].resource)) return 'error: Entry ' + (i + 1) + ' names a fixed color without _light or _dark, which this Android version does not take';
    } else if (op === 'disable') {
      const ids = plan.ids;
      if (!Array.isArray(ids) || !ids.length) return 'error: No overlay named';
      if (ids.length > FAB_MAX) return 'error: Too many overlays named (' + FAB_MAX + ' at most)';
      const seenIds = new Set();
      for (const id of ids) {
        if (typeof id !== 'string' || !new RegExp('^' + FAB_OWNER + FAB_NAME + '(_[a-z0-9_]+)?$').test(id)) return 'error: That overlay was not written by this app';
        if (seenIds.has(id)) return 'error: An overlay is named twice';
        seenIds.add(id);
      }
    } else return 'error: unknown action';
    if (window.__fabNoAnswer) return 'started';
    setTimeout(() => {
      const r = { req, op, mode: window.__root ? 'root' : 'adb_tcp' };
      if (window.__fabNeedsRoot || !window.__root) { r.ok = false; r.needsPrivilege = true; r.error = 'Needs Super User'; window.onOverlayFab(JSON.stringify(r)); return; }
      const ids = op === 'disable' ? plan.ids.slice() : (window.__sdk >= 34 ? [FAB_OWNER + FAB_NAME] : plan.entries.filter(e => !e.config).map(e => FAB_OWNER + FAB_NAME + '_' + e.resource.slice(FAB_PREFIX.length)));
      r.ids = ids;
      if (window.__fabUnknown) { r.ok = false; r.unknown = true; r.error = 'No answer from the device'; window.onOverlayFab(JSON.stringify(r)); return; }
      ids.forEach(id => { let o = window.__ovl.find(x => x.id === id); if (!o) { o = { target: 'android', state: 0, id }; window.__ovl.push(o); } o.state = op === 'disable' ? 0 : 1; });
      r.state = window.__ovl.find(x => x.id === ids[0]).state;
      r.list = wire();
      if (op === 'fabricate') {
        r.lookups = {};
        lookupEntries(plan.entries).forEach(e => { r.lookups[e.resource] = window.__fabDeny ? '#ff1b1b1b' : '#' + e.argb.slice(2).toLowerCase(); });
        if (window.__fabDeny) { r.ok = false; r.error = window.__fabDeny; r.advice = 'Android kept its own colours. The overlay is listed on; tap Apply again after the next repaint.'; }
        else r.ok = true;
      } else r.ok = true;
      window.onOverlayFab(JSON.stringify(r));
    }, window.__delay);
    return 'started';
  };
};
