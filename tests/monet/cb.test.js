// MonetPlan.cb (assets/monet.js): the ColorBlendr-identical engine. (a) with the sliders at 100, Accurate shades on and no rule, every
// SYSTEM, FIXED and CUSTOM token is the colour its DynamicColor gives on the same scheme (control_highlight with its opacity) for
// several seeds, all ten styles, the three specs, the three contrasts and both modes, so the palette-by-identity, tone, chroma multiplier
// and opacity resolution is right; a token with a chroma multiplier goes through ColorBlendr's clipper, which solves the colour a second
// time at the chroma the display reached, so it is held to that path and to within one step per channel of the direct colour. (b) the
// tuning maths against hand-computed values: the two lightness curves, the vivid tone shift, the chroma factor, the pivot tone, the
// tuning each role gets. (c) Pure black: the dark neutral surface tokens darken by (tone - surface tone) x 1.15, shade 900 of the
// neutral rows is black in dark mode and nothing else moves. (d) tintless text: seven on_* tokens white in dark and black in light,
// on_error the other way, nothing else moves. (e) the framework plan per SDK: unsuffixed palettes on 34, _light / _dark on 37, the
// fixed colours as a day/night pair, no name outside the table, under 512 entries, well-formed resources and colours, no duplicate
// (resource, config), the extras of Pure black and tintless text. (f) CMF is always spec 2026; a second or third colour replaces the
// palette by that colour's primary palette. (g) the per-SDK existence table equals the one derived from tests/fixtures/framework-colors.json.
// (h) normalize: defaults, clamps, aliases, what throws. Plain node: `node tests/monet/cb.test.js`, exit code 1 on a failure.
'use strict';
const path = require('path');
const fs = require('fs');
const MonetPlan = require(path.join(__dirname, '..', '..', 'assets', 'monet.js'));
const cb = MonetPlan.cb, lib = cb.lib, MCU = MonetPlan.lib.mcu, Hct = MCU.Hct;
let bad = 0, checks = 0;
function check(label, ok, extra) {
    checks++;
    if (!ok) bad++;
    console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + (typeof extra === 'string' ? extra : JSON.stringify(extra))));
}
const near = (a, b, eps) => Math.abs(a - b) <= (eps === undefined ? 1e-9 : eps);
const argbOf = hex => parseInt(hex.slice(1), 16) >>> 0;
const channels = argb => [(argb >>> 24) & 255, (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255];
const within1 = (a, b) => channels(a).every((c, i) => Math.abs(c - channels(b)[i]) <= 1);

// ---------- (a) identity ----------
(function identity() {
    const t0 = Date.now();
    let tokens = 0, exact = 0, clipped = 0, failed = 0, first = null;
    for (const seed of ['6750A4', 'B5651D', '0061A4']) for (const style of cb.STYLES) for (const spec of cb.SPECS) for (const contrast of cb.CONTRASTS) for (const dark of [false, true]) {
        const o = { style, seed, spec, contrast, dark }, s = cb.scheme(o, dark), got = cb.tokens(o, dark);
        if (got.length !== lib.TOKENS.length) { failed++; continue; }
        got.forEach((t, i) => {
            tokens++;
            const [name, group, make] = lib.TOKENS[i], dc = make();
            let direct = dc.getArgb(s);
            if (dc.opacity) direct = lib.withAlpha(direct, Math.round(dc.opacity(s) * 255));
            const mult = dc.chromaMultiplier ? dc.chromaMultiplier(s) : 1;
            let ok;
            if (mult === 1) { ok = argbOf(t.argb) === direct; if (ok) exact++; }
            else {
                // ColorBlendr's path for a multiplier: Hct.from at the chroma the first solve reached, the tone kept
                const p = dc.palette(s), tone = dc.getTone(s);
                const expected = lib.preserveTone(p.hue, Hct.from(p.hue, p.chroma * mult, tone).chroma, tone) >>> 0;
                ok = argbOf(t.argb) === expected && within1(expected, direct) && t.name === name && t.group === group;
                if (ok) clipped++;
            }
            if (!ok) { failed++; if (!first) first = [style, spec, contrast, dark, t.name, t.argb, lib.hex8(direct), mult]; }
        });
    }
    check('(a) every token equals its DynamicColor on the scheme (' + tokens + ' tokens, ' + exact + ' direct, ' + clipped + ' through the clipper within one step, ' + (Date.now() - t0) + ' ms)', failed === 0 && tokens > 40000, first);
    const toks = cb.tokens({}, true);
    const count = g => toks.filter(t => t.group === g).length;
    check('    groups: 56 SYSTEM, 12 FIXED, 23 CUSTOM, in ColorBlendr\'s order', count('SYSTEM') === 56 && count('FIXED') === 12 && count('CUSTOM') === 23 && toks[0].name === 'palette_key_color_primary' && toks[55].name === 'gen_widget' && toks[56].name === 'primary_fixed' && toks[68].name === 'widget_background' && toks[90].name === 'overview_background', toks.map(t => t.name).join(' '));
    const hl = name => toks.find(t => t.name === name).argb, hlLight = cb.tokens({}, false).find(t => t.name === 'control_highlight').argb;
    check('    control_highlight carries its opacity: 0x33 (20%) over white in dark, 0x1F (12%) over black in light', hl('control_highlight') === '#33FFFFFF' && hlLight === '#1F000000', [hl('control_highlight'), hlLight]);
    check('    on_theme_app_ring is theme_notif\'s colour', hl('on_theme_app_ring') === hl('theme_notif'));
    check('    every colour is #AARRGGBB upper case', toks.every(t => /^#[0-9A-F]{8}$/.test(t.argb)));
    // the Android roles at 2021 are fixed tones, from 2025 the Material role's tone (upstream's extendSpecVersion)
    const s21 = cb.scheme({ spec: '2021' }, true), s25 = cb.scheme({ spec: '2025' }, true);
    const ca = lib.ANDROID.control_activated();
    check('    control_activated: tone 30 on the primary palette at 2021 in dark, primary_container\'s tone at 2025', ca.getTone(s21) === 30 && ca.palette(s21) === s21.primaryPalette && ca.getTone(s25) === MCU.colorSpec.primaryContainer().getTone(s25) && ca.palette(s25) === s25.primaryPalette, [ca.getTone(s21), ca.getTone(s25)]);
    const cn = lib.ANDROID.control_normal();
    check('    control_normal: neutral variant 80 at 2021 in dark, on_surface_variant at 2025', cn.getTone(s21) === 80 && cn.palette(s21) === s21.neutralVariantPalette && cn.getTone(s25) === MCU.colorSpec.onSurfaceVariant().getTone(s25));
    check('    text_primary_inverse: neutral 10 at 2021 in dark, inverse_on_surface at 2025; gen_widget primary 45 / 75', lib.ANDROID.text_primary_inverse().getTone(s21) === 10 && lib.ANDROID.text_primary_inverse().getTone(s25) === MCU.colorSpec.inverseOnSurface().getTone(s25) && lib.ANDROID.gen_widget().getTone(s21) === 45 && lib.ANDROID.gen_widget().getTone(cb.scheme({}, false)) === 75);
})();

// ---------- (b) tuning maths ----------
(function tuning() {
    const T = lib.Tuning;
    const darkest = new T(100, 0, true), half = new T(100, 50, true), brightest = new T(100, 200, true), halfBright = new T(100, 150, true), none = new T(100, 100, true);
    check('(b) lightness 0 (DARKEST curve): 6 -> 0, 12 -> 2, 50 -> 40, 90 -> 80, 95 -> 90, 100 -> 100, 0 -> 0', [6, 12, 50, 90, 95, 100, 0].map(t => darkest.tone(t)).every((v, i) => near(v, [0, 2, 40, 80, 90, 100, 0][i])), [6, 12, 50, 90, 95, 100, 0].map(t => darkest.tone(t)));
    check('    lightness 50: half way along the curve: 50 -> 45, 90 -> 85, 6 -> 3', near(half.tone(50), 45) && near(half.tone(90), 85) && near(half.tone(6), 3), [half.tone(50), half.tone(90), half.tone(6)]);
    check('    lightness 200 (BRIGHTEST curve): 10 -> 20, 50 -> 60, 88 -> 98, 95 -> 99.1666.., 100 -> 100', near(brightest.tone(10), 20) && near(brightest.tone(50), 60) && near(brightest.tone(88), 98) && near(brightest.tone(95), 98 + 2 * 7 / 12) && near(brightest.tone(100), 100), [10, 50, 88, 95, 100].map(t => brightest.tone(t)));
    check('    lightness 150: 50 -> 55; a tone outside 0..100 is clamped first (-5 -> 0, 105 -> 100)', near(halfBright.tone(50), 55) && near(brightest.tone(-5), 0) && near(darkest.tone(105), 100), [halfBright.tone(50), brightest.tone(-5), darkest.tone(105)]);
    check('    lightness 100: the tone as it is, even outside 0..100', none.tone(37.5) === 37.5 && none.tone(-5) === -5 && none.tone(105) === 105);
    const vivid = new T(100, 100, false);
    check('    vivid (Accurate shades off): 60 -> 54, 90 -> 84, 95 -> 92, 100 -> 100, 50 -> 50, 55 -> 52, 40 -> 40', [60, 90, 95, 100, 50, 55, 40].map(t => vivid.tone(t)).every((v, i) => near(v, [54, 84, 92, 100, 50, 52, 40][i])), [60, 90, 95, 100, 50, 55, 40].map(t => vivid.tone(t)));
    check('    vivid after the curve: lightness 0 and vivid, tone 95 -> 90 -> 84', near(new T(100, 0, false).tone(95), 84), new T(100, 0, false).tone(95));
    check('    chromaFactor: 150 accurate -> 1.5, 150 vivid -> 1.875, 100 vivid -> 1.25, 0 -> 0, 200 -> 2', new T(150, 100, true).chromaFactor === 1.5 && new T(150, 100, false).chromaFactor === 1.875 && vivid.chromaFactor === 1.25 && new T(0, 100, true).chromaFactor === 0 && new T(200, 100, true).chromaFactor === 2);
    check('    isIdentity only at 100 / 100 / accurate', none.isIdentity && !vivid.isIdentity && !half.isIdentity && !new T(101, 100, true).isIdentity);
    check('    adaptiveMidTone: offset 0 -> 0.5; (0.8, 0.5) -> 0.627985..; (0.2, 0.5) -> 0.372015.. (mirror)', near(lib.adaptiveMidTone(0.5, 0.3, 1), 0.5) && near(lib.adaptiveMidTone(0.8, 0.5, 1), 0.5 * (1 + (1.3 - Math.sqrt(1.09)))) && near(lib.adaptiveMidTone(0.2, 0.5, 1), 1 - lib.adaptiveMidTone(0.8, 0.5, 1)), [lib.adaptiveMidTone(0.8, 0.5, 1), lib.adaptiveMidTone(0.2, 0.5, 1)]);
    // the tuning per role (PaletteTuning.forRole)
    const o = cb.normalize({ accentSaturation: 150, backgroundSaturation: 60, backgroundLightness: 120, accurate: false });
    const f = (role, style) => T.forRole(role, style, o);
    check('    forRole Tonal Spot: accent1 saturation 150 vivid lightness 100; neutral1 saturation 60 lightness 120 accurate; error saturation 150 accurate lightness 100', f('accent1', 'TONAL_SPOT').saturation === 150 && f('accent1', 'TONAL_SPOT').vivid && f('accent1', 'TONAL_SPOT').lightness === 100 && f('neutral1', 'TONAL_SPOT').saturation === 60 && f('neutral1', 'TONAL_SPOT').lightness === 120 && !f('neutral1', 'TONAL_SPOT').vivid && f('error', 'TONAL_SPOT').saturation === 150 && !f('error', 'TONAL_SPOT').vivid && f('error', 'TONAL_SPOT').lightness === 100);
    check('    forRole Monochromatic: saturation 100, lightness 120 and accurate for every role; Rainbow: neutral saturation 100, accent 150', cb.ROLES.every(r => f(r, 'MONOCHROMATIC').saturation === 100 && f(r, 'MONOCHROMATIC').lightness === 120 && !f(r, 'MONOCHROMATIC').vivid) && f('neutral2', 'RAINBOW').saturation === 100 && f('neutral2', 'RAINBOW').lightness === 120 && f('accent2', 'RAINBOW').saturation === 150 && f('accent2', 'RAINBOW').vivid);
    // the tuned colour: identity tuning reads the palette; a factor at or under 1 keeps the tone; above 1 the tone moves towards the middle only when the chroma does not fit
    const s = cb.scheme({ seed: '6750A4' }, false), p = s.primaryPalette;
    const idP = new lib.TunedPalette('accent1', p, none), lowP = new lib.TunedPalette('accent1', p, new T(50, 100, true)), hiP = new lib.TunedPalette('accent1', p, new T(200, 100, true));
    check('    identity tuning: colorAt(t) is the palette\'s tone(t) for every shade tone', cb.TONES.every(t => idP.colorAt(t) === p.tone(t)));
    check('    saturation 50: half the chroma at the same tone (tone 40 stays 40 +-0.5, chroma near half)', (function () { const h = Hct.fromInt(lowP.colorAt(40)); return near(h.tone, 40, 0.5) && near(h.chroma, Hct.fromInt(p.tone(40)).chroma / 2, 1.5); })(), Hct.fromInt(lowP.colorAt(40)));
    check('    saturation 200 at tone 40: more chroma than the palette, tone between the pivot and 40', (function () { const h = Hct.fromInt(hiP.colorAt(40)), base = Hct.fromInt(p.tone(40)); return h.chroma > base.chroma + 5 && h.tone <= 40.5 && h.tone > 30; })(), Hct.fromInt(hiP.colorAt(40)));
    check('    saturation 200 at tones 0 and 100: black and white (the clipper keeps the ends)', hiP.colorAt(0) === 0xFF000000 && hiP.colorAt(100) === 0xFFFFFFFF);
    check('    a chroma that fits is kept at its tone: fits() true for the palette\'s own chroma at tone 40, false for 3x', lib.fits(p.hue, p.chroma, 40) && !lib.fits(p.hue, p.chroma * 3, 40));
    check('    preserveTone: a yellow at tone 99 is the palette\'s averaged tone 99, another hue the direct colour', lib.preserveTone(110, 40, 99) === MCU.TonalPalette.fromHueAndChroma(110, 40).tone(99) && lib.preserveTone(110, 40, 99) !== Hct.from(110, 40, 99).toInt() && lib.preserveTone(300, 40, 99) === Hct.from(300, 40, 99).toInt());
    // the sliders reach the plan and the preview: the chroma asked for shows, the slider at 100 changes nothing
    const base = cb.palettes({}, false), sat = cb.palettes({ accentSaturation: 150 }, false), light = cb.palettes({ backgroundLightness: 50 }, false);
    check('    accentSaturation 150: accent rows change, neutral rows and the error row\'s... accent1 shade 500 more saturated, neutral1 unchanged', sat.accent1.shades[7] !== base.accent1.shades[7] && sat.neutral1.shades.join() === base.neutral1.shades.join() && Hct.fromInt(parseInt(sat.accent1.shades[7], 16)).chroma > Hct.fromInt(parseInt(base.accent1.shades[7], 16)).chroma, [base.accent1.shades[7], sat.accent1.shades[7]]);
    check('    backgroundLightness 50: neutral rows darker (shade 100: tone 90 -> 85), accent rows unchanged', near(Hct.fromInt(parseInt(light.neutral1.shades[3], 16)).tone, 85, 0.6) && light.accent1.shades.join() === base.accent1.shades.join(), Hct.fromInt(parseInt(light.neutral1.shades[3], 16)).tone);
    check('    sliders at 100 / 100 / 100 with Accurate shades on: the same bytes as no sliders', JSON.stringify(cb.palettes({ accentSaturation: 100, backgroundSaturation: 100, backgroundLightness: 100, accurate: true }, true)) === JSON.stringify(cb.palettes({}, true)));
    const vividP = cb.palettes({ accurate: false }, false);
    // (tone 84 asked for; a quarter more chroma does not fit at 84 for this purple, so the clipper gives up a little tone for it)
    check('    Accurate shades off: accent shade 100 (tone 90) is at tone 84 or a little under with more chroma than the base, neutral and error rows unchanged', (function () { const v = Hct.fromInt(parseInt(vividP.accent1.shades[3], 16)), b = Hct.fromInt(parseInt(base.accent1.shades[3], 16)); return v.tone <= 84.5 && v.tone > 78 && v.chroma > b.chroma + 2; })() && vividP.neutral1.shades.join() === base.neutral1.shades.join() && vividP.error.shades.join() === base.error.shades.join(), Hct.fromInt(parseInt(vividP.accent1.shades[3], 16)));
    check('    Accurate shades off at tone 90 where the chroma fits (a yellow at saturation 100): exactly tone 84', (function () { const s = cb.scheme({ seed: 'F5C400' }, false), vp = new lib.TunedPalette('accent1', s.primaryPalette, new T(100, 100, false)); return near(Hct.fromInt(vp.colorAt(90)).tone, 84, 0.6); })(), Hct.fromInt(new lib.TunedPalette('accent1', cb.scheme({ seed: 'F5C400' }, false).primaryPalette, new T(100, 100, false)).colorAt(90)));
})();

// ---------- (c) pitch black ----------
(function pitchBlack() {
    for (const spec of cb.SPECS) {
        const o = { seed: '6750A4', spec, black: true }, s = cb.scheme(o, true), surfaceTone = MCU.colorSpec.surface().getTone(s);
        const plain = cb.tokens({ seed: '6750A4', spec }, true), black = cb.tokens(o, true), res = lib.resolve(s), r = lib.resolved(cb.normalize(o), true);
        let moved = 0, wrong = [];
        black.forEach((t, i) => {
            const x = res[i], shouldMove = x.role !== null && lib.isNeutral(x.role) && lib.PITCH_BLACK_TOKENS.indexOf(t.name) >= 0;
            if (!shouldMove) { if (t.argb !== plain[i].argb) wrong.push(t.name); return; }
            moved++;
            const tone = Math.max(x.tone - surfaceTone, 0) * 1.15, expected = lib.hex8(r.palettes[x.role].colorAt(tone, x.chromaMultiplier));
            if (t.argb !== expected) wrong.push(t.name + ' ' + t.argb + ' != ' + expected);
        });
        check('(c) spec ' + spec + ' dark, Pure black: the ' + moved + ' neutral surface tokens darken by (tone - ' + surfaceTone + ') x 1.15, every other token unchanged', wrong.length === 0 && moved === 9, wrong);
        const by = name => black.find(t => t.name === name).argb;
        check('    surface and background (at the surface tone) are black; surface_container_high above it is not', by('surface') === '#FF000000' && by('background') === '#FF000000' && by('surface_container_high') !== '#FF000000' && by('surface_container_high') !== plain.find(t => t.name === 'surface_container_high').argb, [by('surface'), by('background'), by('surface_container_high')]);
        const pal = cb.palettes(o, true), palPlain = cb.palettes({ seed: '6750A4', spec }, true);
        check('    shade 900 of neutral1 and neutral2 is 000000 in dark; the other 12 shades and the accent / error rows are as without the rule', pal.neutral1.shades[11] === '000000' && pal.neutral2.shades[11] === '000000' && pal.neutral1.shades.filter((x, i) => i !== 11).join() === palPlain.neutral1.shades.filter((x, i) => i !== 11).join() && ['accent1', 'accent2', 'accent3', 'error'].every(k => pal[k].shades.join() === palPlain[k].shades.join()) && palPlain.neutral1.shades[11] !== '000000');
    }
    check('    light mode: Pure black changes nothing (tokens and palettes)', JSON.stringify(cb.tokens({ black: true }, false)) === JSON.stringify(cb.tokens({}, false)) && JSON.stringify(cb.palettes({ black: true }, false)) === JSON.stringify(cb.palettes({}, false)));
    check('    Pure black with the lightness slider: the rule works on the tone, the tuned palette gives the colour (surface still black)', cb.tokens({ black: true, backgroundLightness: 150 }, true).find(t => t.name === 'surface').argb === '#FF000000');
})();

// ---------- (d) tintless text ----------
(function tintless() {
    for (const dark of [false, true]) {
        const on = cb.tokens({ seed: 'B5651D', spec: '2025', style: 'VIBRANT' }, dark), off = cb.tokens({ seed: 'B5651D', spec: '2025', style: 'VIBRANT', tintText: false }, dark);
        const wrong = [];
        off.forEach((t, i) => {
            const expected = lib.TINTLESS_TOKENS.indexOf(t.name) >= 0 ? (dark ? '#FFFFFFFF' : '#FF000000') : t.name === 'on_error' ? (dark ? '#FF000000' : '#FFFFFFFF') : on[i].argb;
            if (t.argb !== expected) wrong.push(t.name + ' ' + t.argb);
            if (t.argb === expected && (lib.TINTLESS_TOKENS.indexOf(t.name) >= 0 || t.name === 'on_error') && on[i].argb === expected) wrong.push(t.name + ' already so without the rule');
        });
        check('(d) ' + (dark ? 'dark' : 'light') + ': the 7 on_* tokens ' + (dark ? 'white' : 'black') + ', on_error ' + (dark ? 'black' : 'white') + ', the other 83 as with tinted text', wrong.length === 0 && lib.TINTLESS_TOKENS.length === 7, wrong);
    }
    check('    tintless text leaves the palettes alone', JSON.stringify(cb.palettes({ tintText: false }, true)) === JSON.stringify(cb.palettes({}, true)));
    check('    both rules: Pure black on the surfaces and white text, in one plan', (function () { const t = cb.tokens({ black: true, tintText: false }, true); return t.find(x => x.name === 'surface').argb === '#FF000000' && t.find(x => x.name === 'on_surface').argb === '#FFFFFFFF'; })());
})();

// ---------- (e) entries per SDK ----------
(function entriesPerSdk() {
    const RES = /^android:color\/[a-z0-9_]+$/, HEX = /^#[0-9A-F]{8}$/;
    const names = e => e.map(x => x.resource.slice('android:color/'.length));
    for (const sdk of [33, 34, 35, 36, 37, 40]) for (const opts of [{}, { dark: true, black: true, tintText: false, style: 'CMF', spec: '2025', accentSaturation: 130 }]) {
        const e = cb.entries(opts, sdk), level = Math.min(37, Math.max(34, sdk)), n = names(e);
        const seen = new Set(), dup = [];
        for (const x of e) { const k = x.resource + '|' + (x.config || ''); if (seen.has(k)) dup.push(k); seen.add(k); }
        check('(e) SDK ' + sdk + ' ' + JSON.stringify(opts) + ': ' + e.length + ' entries, every resource android:color/[a-z0-9_]+, every colour #AARRGGBB, no (resource, config) twice, under 512, config only night, count() agrees', e.length > 100 && e.length <= 512 && e.every(x => RES.test(x.resource) && HEX.test(x.argb) && (x.config === undefined || x.config === 'night')) && dup.length === 0 && cb.count(opts, sdk) === e.length, dup.slice(0, 5));
        check('    every name is in the table for that SDK (' + level + ')', n.every(name => cb.exists(name, level)), n.filter(name => !cb.exists(name, level)).slice(0, 5));
        const night = e.filter(x => x.config === 'night');
        check('    config night entries: ' + night.length + ', each with an unqualified twin before it', night.every(x => e.some(y => y.resource === x.resource && y.config === undefined)) && (sdk < 34 ? night.length === 0 : night.length > 0));
    }
    const e34 = cb.entries({}, 34), e37 = cb.entries({}, 37), e33 = cb.entries({}, 33), n34 = names(e34), n37 = names(e37), n33 = names(e33);
    check('    SDK 34 writes the unsuffixed palettes (system_accent1_0 .. system_neutral2_1000, 65) and no _light / _dark palette name', n34.filter(x => /^system_(accent[123]|neutral[12]|error)_\d+$/.test(x)).length === 65 && !n34.some(x => /^system_(accent[123]|neutral[12]|error)_\d+_(light|dark)$/.test(x)) && !n34.includes('system_error_500'), n34.filter(x => /^system_(accent[123]|neutral[12]|error)_\d+/.test(x)).length);
    check('    SDK 37 writes _light and _dark palettes (78 + 78) and no unsuffixed palette name', n37.filter(x => /^system_(accent[123]|neutral[12]|error)_\d+_(light|dark)$/.test(x)).length === 156 && !n37.some(x => /^system_(accent[123]|neutral[12]|error)_\d+$/.test(x)));
    check('    SDK 35 and 36 write system_error_500 unsuffixed, 37 as _light / _dark', names(cb.entries({}, 35)).includes('system_error_500') && names(cb.entries({}, 36)).includes('system_error_500') && n37.includes('system_error_500_light') && !n37.includes('system_error_500'));
    check('    the custom SystemUI colours from SDK 35 (system_widget_background_light), not on 34', !n34.includes('system_widget_background_light') && names(cb.entries({}, 35)).includes('system_widget_background_light') && n37.includes('system_clock_hour_dark'));
    const fixed = e37.filter(x => x.resource === 'android:color/system_primary_fixed');
    const light = cb.tokens({}, false).find(t => t.name === 'primary_fixed').argb, dark = cb.tokens({}, true).find(t => t.name === 'primary_fixed').argb;
    check('    fixed tokens: system_primary_fixed unqualified (light) + config night (dark) + _light + _dark on 37; the unqualified pair alone on 34', fixed.length === 2 && fixed[0].argb === light && fixed[0].config === undefined && fixed[1].argb === dark && fixed[1].config === 'night' && n37.includes('system_primary_fixed_light') && n37.includes('system_primary_fixed_dark') && e34.filter(x => x.resource === 'android:color/system_primary_fixed').length === 2 && !n34.includes('system_primary_fixed_light'), fixed);
    check('    SDK 33 (below 34): no configuration, the current mode\'s fixed colour unqualified, otherwise 34\'s table', e33.filter(x => x.resource === 'android:color/system_primary_fixed').length === 1 && e33.find(x => x.resource === 'android:color/system_primary_fixed').argb === light && cb.entries({ dark: true }, 33).find(x => x.resource === 'android:color/system_primary_fixed').argb === dark && n33.join() === n34.filter((x, i) => n34[i - 1] !== x).join(), [e33.length, e34.length]);
    const order = n => [n.findIndex(x => x.startsWith('system_accent1_')), n.findIndex(x => x === 'system_palette_key_color_primary_light'), n.findIndex(x => x === 'system_primary_fixed'), n.findIndex(x => x === 'accent_device_default_light')];
    check('    order: palettes, then the tokens, then the fixed colours, then the device default accent', (function () { const o = order(n37); return o.every(i => i >= 0) && o[0] < o[1] && o[1] < o[2] && o[2] < o[3]; })(), order(n37));
    const eos = e37.filter(x => x.resource.startsWith('android:color/accent_device_default')), pal = cb.palettes({}, false), palD = cb.palettes({}, true);
    check('    accent_device_default_light = light primary shade 600 (tone 40), _dark = dark primary shade 200 (tone 80), day/night pair after them; nothing e_*', eos.length === 4 && eos[0].resource.endsWith('_light') && eos[0].argb === '#FF' + pal.accent1.shades[8] && eos[1].argb === '#FF' + palD.accent1.shades[4] && eos[2].resource === 'android:color/accent_device_default' && eos[2].argb === eos[0].argb && eos[3].config === 'night' && eos[3].argb === eos[1].argb && !n37.some(x => x.startsWith('e_')), eos);
    const fx = e37.filter(x => /surface_effect/.test(x.resource));
    // (ColorBlendr's fifth effect, system_surface_effect_4, is no framework colour on Android 17: the table drops it, as it drops the unsuffixed surface_effect_N_color)
    check('    surface effects on 37: the fallback (alpha FF) light / dark, then system_surface_effect_0..3 _light / _dark with their alphas (80, 8A, 52/26, 26/1A); no effect 4, no unsuffixed name; none on 36', fx.length === 10 && fx[0].resource === 'android:color/system_surface_effect_0_fallback_light' && fx[0].argb.startsWith('#FF') && fx[1].resource === 'android:color/system_surface_effect_0_fallback_dark' && fx[2].resource === 'android:color/system_surface_effect_0_light' && fx[2].argb.startsWith('#80') && fx[3].argb.startsWith('#80') && fx[4].argb.startsWith('#8A') && fx[5].argb.startsWith('#8A') && fx[6].argb.startsWith('#52') && fx[7].argb.startsWith('#26') && fx[8].argb.startsWith('#26') && fx[9].argb.startsWith('#1A') && fx[9].resource === 'android:color/system_surface_effect_3_dark' && !names(cb.entries({}, 36)).some(x => /surface_effect/.test(x)), fx.map(x => x.resource.slice(14) + ' ' + x.argb));
    const rl = lib.resolved(cb.normalize({}), false), rd = lib.resolved(cb.normalize({}), true);
    check('    surface effect 0 light is primary tone 90 at 50%, dark primary tone 20 at 50%; effect 1 neutral tone 98 light / 6 dark at 54%; the fallback secondary 80 / 20 opaque', fx[2].argb === lib.hex8(lib.withAlpha(parseInt(pal.accent1.shades[3], 16), 128)) && fx[3].argb === lib.hex8(lib.withAlpha(parseInt(palD.accent1.shades[10], 16), 128)) && fx[4].argb === lib.hex8(lib.withAlpha(rl.palettes.neutral1.colorAt(98), 138)) && fx[5].argb === lib.hex8(lib.withAlpha(rd.palettes.neutral1.colorAt(6), 138)) && fx[0].argb === '#FF' + pal.accent2.shades[4] && fx[1].argb === '#FF' + palD.accent2.shades[10], [fx[2].argb, pal.accent1.shades[3], fx[5].argb]);
    for (const sdk of [34, 37]) {
        const b = cb.entries({ black: true, dark: true }, sdk), nb = names(b);
        check('    Pure black on ' + sdk + ': background_dark #FF000000 is written; surface_header_dark_sysui and shade_panel_fg_color are not (no such framework colours)', b.find(x => x.resource === 'android:color/background_dark') && b.find(x => x.resource === 'android:color/background_dark').argb === '#FF000000' && !nb.includes('surface_header_dark_sysui') && !nb.includes('shade_panel_fg_color') && !names(cb.entries({ dark: true }, sdk)).includes('background_dark'));
        const t = cb.entries({ tintText: false }, sdk), nt = names(t);
        check('    tintless text on ' + sdk + ': the framework has none of the writer\'s extra names (text_color_*), so only the token colours change', !nt.some(x => x.startsWith('text_color_') || x.startsWith('m3_') || x.startsWith('gm3_')) && t.length === cb.entries({}, sdk).length && t.find(x => x.resource === 'android:color/system_on_surface_dark').argb === '#FFFFFFFF' && t.find(x => x.resource === 'android:color/system_on_surface_light').argb === '#FF000000' && t.find(x => x.resource === 'android:color/system_on_error_dark').argb === '#FF000000', nt.filter(x => x.startsWith('text_color_')));
    }
    check('    the plan carries the tokens\' colours: system_primary_light / _dark on 37 equal tokens()', e37.find(x => x.resource === 'android:color/system_primary_light').argb === cb.tokens({}, false).find(t => t.name === 'primary').argb && e37.find(x => x.resource === 'android:color/system_primary_dark').argb === cb.tokens({}, true).find(t => t.name === 'primary').argb);
    check('    the plan carries the palettes: system_accent1_500 on 34 is the current mode\'s shade 500 (light by default, dark with dark: true)', e34.find(x => x.resource === 'android:color/system_accent1_500').argb === '#FF' + pal.accent1.shades[7] && cb.entries({ dark: true }, 34).find(x => x.resource === 'android:color/system_accent1_500').argb === '#FF' + palD.accent1.shades[7]);
    check('    exists(): below 34 reads as 34, above 37 as 37; an unknown name is false; a bad SDK throws', cb.exists('system_accent1_500', 21) && !cb.exists('system_accent1_500', 99) && cb.exists('system_accent1_500_light', 99) && !cb.exists('no_such_colour', 34) && (function () { try { cb.exists('x', 'abc'); return false; } catch (e) { return true; } })());
})();

// ---------- (f) CMF and the second / third colour ----------
(function cmfAndAccents() {
    const specs = cb.SPECS.map(spec => cb.scheme({ style: 'CMF', spec }, true));
    check('(f) CMF: the scheme is at spec 2026 whatever spec is asked for, with upstream\'s CMF variant', specs.every(s => s.specVersion === '2026' && s.variant === MCU.Variant.CMF));
    check('    CMF tokens and entries are the same for the three specs', cb.SPECS.every(spec => JSON.stringify(cb.tokens({ style: 'CMF', spec }, false)) === JSON.stringify(cb.tokens({ style: 'CMF' }, false)) && JSON.stringify(cb.entries({ style: 'CMF', spec }, 37)) === JSON.stringify(cb.entries({ style: 'CMF' }, 37))));
    check('    the other styles keep the spec asked for after upstream\'s fallback: Tonal Spot 2026 -> 2025, Rainbow 2025 -> 2021, Vibrant 2021 -> 2021', cb.scheme({ style: 'TONAL_SPOT', spec: '2026' }).specVersion === '2025' && cb.scheme({ style: 'RAINBOW', spec: '2025' }).specVersion === '2021' && cb.scheme({ style: 'VIBRANT', spec: '2021' }).specVersion === '2021');
    for (const dark of [false, true]) for (const [style, spec] of [['TONAL_SPOT', '2021'], ['VIBRANT', '2025'], ['CMF', '2026'], ['FIDELITY', '2021']]) {
        const base = cb.scheme({ style, spec, seed: '6750A4' }, dark), two = cb.scheme({ style, spec, seed: '6750A4', secondary: 'B5651D', tertiary: '#0061a4' }, dark);
        const sec = cb.scheme({ style, spec, seed: 'B5651D' }, dark).primaryPalette, ter = cb.scheme({ style, spec, seed: '0061A4' }, dark).primaryPalette;
        const same = (a, b) => a.hue === b.hue && a.chroma === b.chroma && a.keyColor.toInt() === b.keyColor.toInt();
        check('    ' + style + ' ' + spec + (dark ? ' dark' : ' light') + ': a second and third colour replace accent2 / accent3 by that colour\'s primary palette; the other palettes, the variant, the spec and the seed stay', same(two.secondaryPalette, sec) && same(two.tertiaryPalette, ter) && !same(two.secondaryPalette, base.secondaryPalette) && !same(two.tertiaryPalette, base.tertiaryPalette) && same(two.primaryPalette, base.primaryPalette) && same(two.neutralPalette, base.neutralPalette) && same(two.neutralVariantPalette, base.neutralVariantPalette) && same(two.errorPalette, base.errorPalette) && two.variant === base.variant && two.specVersion === base.specVersion && two.isDark === dark && two.sourceColorArgb === base.sourceColorArgb && two.contrastLevel === base.contrastLevel && two.platform === 'phone');
        check('    ' + style + ' ' + spec + (dark ? ' dark' : ' light') + ': the tokens of accent2 / accent3 move, those of accent1 / neutral / error stay', (function () { const a = cb.tokens({ style, spec, seed: '6750A4' }, dark), b = cb.tokens({ style, spec, seed: '6750A4', secondary: 'B5651D', tertiary: '0061A4' }, dark), res = lib.resolve(base); return a.every((t, i) => (t.argb === b[i].argb) === !(res[i].role === 'accent2' || res[i].role === 'accent3') || res[i].role === null || (res[i].role === 'accent2' || res[i].role === 'accent3') && t.argb === b[i].argb && (/^(shadow|scrim)$/.test(t.name) || res[i].tone === 0 || res[i].tone === 100)); })());
    }
    const p = cb.palettes({ secondary: 'B5651D' }, false), pSec = cb.palettes({ seed: 'B5651D' }, false), pBase = cb.palettes({}, false);
    check('    palettes(): accent2 is the second colour\'s accent1 row (shades and key), accent3 the style\'s own', p.accent2.shades.join() === pSec.accent1.shades.join() && p.accent2.key === pSec.accent1.key && p.accent3.shades.join() === pBase.accent3.shades.join());
    check('    the secondary tokens follow: system secondary = the second colour\'s primary token colour? no: the role\'s own tone on the replaced palette (secondary_container differs from the base)', cb.tokens({ secondary: 'B5651D' }, true).find(t => t.name === 'secondary_container').argb !== cb.tokens({}, true).find(t => t.name === 'secondary_container').argb && cb.tokens({ secondary: 'B5651D' }, true).find(t => t.name === 'primary').argb === cb.tokens({}, true).find(t => t.name === 'primary').argb);
    check('    a third colour alone: accent2 stays the style\'s, accent3 is replaced', (function () { const one = cb.scheme({ tertiary: 'B5651D' }), base = cb.scheme({}); return one.secondaryPalette.keyColor.toInt() === base.secondaryPalette.keyColor.toInt() && one.tertiaryPalette.keyColor.toInt() !== base.tertiaryPalette.keyColor.toInt() && one.tertiaryPalette.keyColor.toInt() === cb.scheme({ seed: 'B5651D' }).primaryPalette.keyColor.toInt(); })());
    check('    no second or third colour (null, \'\', left out): the scheme is upstream\'s own (SchemeCmf for CMF), with a colour a plain DynamicScheme', cb.scheme({ secondary: null, tertiary: '' }) instanceof MCU.DynamicScheme && cb.scheme({ style: 'CMF' }) instanceof MCU.SchemeCmf && !(cb.scheme({ style: 'CMF', secondary: 'B5651D' }) instanceof MCU.SchemeCmf) && cb.scheme({ style: 'CMF', secondary: 'B5651D' }) instanceof MCU.DynamicScheme);
})();

// ---------- (g) the table against the fixture ----------
(function table() {
    const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'framework-colors.json'), 'utf8'));
    const levels = ['34', '35', '36', '37'];
    check('(g) the fixture has the four SDK levels with hundreds of names each', levels.every(k => Array.isArray(fixture[k]) && fixture[k].length > 400), levels.map(k => (fixture[k] || []).length));
    // the generator: every name the writers can produce, looked up in the four sets, as name -> bitmask (0 left out)
    const sets = {}; levels.forEach(k => { sets[k] = new Set(fixture[k]); });
    const derived = new Map();
    for (const name of lib.writerNames()) { let mask = 0; levels.forEach((k, i) => { if (sets[k].has(name)) mask |= 1 << i; }); if (mask) derived.set(name, mask); }
    const embedded = lib.CB_FRAMEWORK_COLORS, diff = [];
    for (const [name, mask] of derived) if (embedded.get(name) !== mask) diff.push(name + ' ' + mask + ' vs ' + embedded.get(name));
    for (const name of embedded.keys()) if (!derived.has(name)) diff.push(name + ' not derived');
    check('    the embedded table equals the derived one (' + derived.size + ' names)', diff.length === 0 && derived.size === embedded.size && derived.size > 400, diff.slice(0, 10));
    check('    known facts: unsuffixed system_accent1_500 on 34-36 only, _light on 37 only, system_error_500 on 35 and 36, accent_device_default and background_dark on all, system_surface_effect_0_light and system_primary_fixed_dark on 37 only, system_primary_fixed on all, system_palette_key_color_error_light on 37 only, system_widget_background_light 35+, system_on_theme_app_ring_light 35 only', embedded.get('system_accent1_500') === 7 && embedded.get('system_accent1_500_light') === 8 && embedded.get('system_error_500') === 6 && embedded.get('accent_device_default') === 15 && embedded.get('background_dark') === 15 && embedded.get('system_surface_effect_0_light') === 8 && embedded.get('system_primary_fixed_dark') === 8 && embedded.get('system_primary_fixed') === 15 && embedded.get('system_palette_key_color_error_light') === 8 && embedded.get('system_widget_background_light') === 14 && embedded.get('system_on_theme_app_ring_light') === 2 && embedded.get('system_gen_widget_light') === undefined, ['system_accent1_500', 'system_accent1_500_light', 'system_error_500', 'system_surface_effect_0_light', 'system_on_theme_app_ring_light'].map(n => embedded.get(n)));
    check('    writerNames() covers both modes and both rules: ' + lib.writerNames().length + ' names, among them the e_* names, background_dark, shade_panel_fg_color, text_color_primary_device_default_dark, surface_effect_0_color', (function () { const n = new Set(lib.writerNames()); return ['e_accent_light', 'background_dark', 'shade_panel_fg_color', 'text_color_primary_device_default_dark', 'surface_effect_0_color', 'gm3_ref_palette_dynamic_neutral10', 'system_primary_fixed', 'system_widget_background_dark'].every(x => n.has(x)); })());
})();

// ---------- (h) normalize ----------
(function normalize() {
    const d = cb.defaults();
    check('(h) defaults: Tonal Spot, 6750A4, no second / third colour, 2021, contrast 0, 100 / 100 / 100, accurate, no Pure black, tinted text, light', JSON.stringify(d) === JSON.stringify({ style: 'TONAL_SPOT', seed: '6750A4', secondary: null, tertiary: null, spec: '2021', contrast: 0, accentSaturation: 100, backgroundSaturation: 100, backgroundLightness: 100, accurate: true, black: false, tintText: true, dark: false }), d);
    check('    normalize() of nothing, {} and null is the defaults; a copy, not the object given', JSON.stringify(cb.normalize()) === JSON.stringify(d) && JSON.stringify(cb.normalize({})) === JSON.stringify(d) && JSON.stringify(cb.normalize(null)) === JSON.stringify(d) && (function () { const o = { style: 'VIBRANT' }; return cb.normalize(o) !== o && o.seed === undefined; })());
    const n = cb.normalize({ style: 'neutral', seed: '#b5651d', secondary: 0xFF0061A4, tertiary: '', spec: 2025, contrast: '0.5', accentSaturation: 250, backgroundSaturation: -5, backgroundLightness: '120.4', accurate: 0, black: 1, tintText: '', dark: 'yes' });
    check('    aliases, colour forms, spec as a number, contrast as a string, sliders clamped to 0..200 and rounded, flags by truth', n.style === 'SPRITZ' && n.seed === 'B5651D' && n.secondary === '0061A4' && n.tertiary === null && n.spec === '2025' && n.contrast === 0.5 && n.accentSaturation === 200 && n.backgroundSaturation === 0 && n.backgroundLightness === 120 && n.accurate === false && n.black === true && n.tintText === false && n.dark === true, n);
    check('    MONOCHROME -> MONOCHROMATIC; contrast clamped to 0..1', cb.normalize({ style: 'monochrome' }).style === 'MONOCHROMATIC' && cb.normalize({ contrast: 3 }).contrast === 1 && cb.normalize({ contrast: -1 }).contrast === 0);
    const throws = o => { try { cb.normalize(o); return false; } catch (e) { return true; } };
    check('    throws: an unknown style, an unknown spec, a bad seed, a bad second colour, a slider or contrast that is not a number', throws({ style: 'NOPE' }) && throws({ spec: '2024' }) && throws({ seed: 'zzz' }) && throws({ secondary: '12' }) && throws({ accentSaturation: 'lots' }) && throws({ contrast: 'high' }));
    check('    STYLES (10), SPECS, CONTRASTS, SHADES / TONES (13, shade 500 is tone 50), ROLES (6)', cb.STYLES.length === 10 && cb.STYLES.includes('CMF') && cb.SPECS.join() === '2021,2025,2026' && cb.CONTRASTS.join() === '0,0.5,1' && cb.SHADES.length === 13 && cb.TONES.length === 13 && cb.TONES[cb.SHADES.indexOf(500)] === 50 && cb.TONES[cb.SHADES.indexOf(0)] === 100 && cb.TONES[cb.SHADES.indexOf(1000)] === 0 && cb.ROLES.join() === 'accent1,accent2,accent3,neutral1,neutral2,error');
    check('    the old engine is untouched: MonetPlan.entries / palettes / roles still answer (P14 Tonal Spot: 165 entries)', MonetPlan.entries('TONAL_SPOT', '6750A4').length === 165 && MonetPlan.palettes('TONAL_SPOT', '6750A4').accent1.shades.length === 13);
    const pal = cb.palettes({}, false);
    check('    palettes(): seed, style and six rows { hue, chroma, key, shades[13] of RRGGBB } (the preview\'s shape, plus error)', pal.seed === '6750A4' && pal.style === 'TONAL_SPOT' && cb.ROLES.every(r => typeof pal[r].hue === 'number' && typeof pal[r].chroma === 'number' && /^[0-9A-F]{6}$/.test(pal[r].key) && pal[r].shades.length === 13 && pal[r].shades.every(x => /^[0-9A-F]{6}$/.test(x))) && pal.accent1.shades[0] === 'FFFFFF' && pal.accent1.shades[12] === '000000');
    // (the old engine turns a seed with chroma under 5 into Google blue, as Android's ColorScheme does; ColorBlendr keeps the seed's hue)
    const greyHue = Hct.fromInt(MonetPlan.lib.parseArgb('8E8E90')).hue, greyPal = cb.palettes({ seed: '8E8E90' }, false);
    check('    the seed is taken as it is: a near-grey seed keeps its hue in every palette (the old engine remaps it to Google blue); Monochromatic has chroma 0; contrast reaches the tokens', near(greyPal.accent1.hue, greyHue) && near(greyPal.neutral1.hue, greyHue) && greyPal.seed === '8E8E90' && MonetPlan.seed('TONAL_SPOT', '8E8E90') === MonetPlan.GOOGLE_BLUE && cb.palettes({ style: 'MONOCHROMATIC' }, false).accent1.chroma === 0 && cb.tokens({ contrast: 1 }, false).find(t => t.name === 'primary').argb !== cb.tokens({}, false).find(t => t.name === 'primary').argb, [greyPal.accent1.hue, greyHue]);
    check('    the mode: palettes / tokens / scheme follow isDark when given, opts.dark otherwise', cb.scheme({ dark: true }).isDark === true && cb.scheme({ dark: true }, false).isDark === false && cb.scheme({}, true).isDark === true && JSON.stringify(cb.tokens({ dark: true })) === JSON.stringify(cb.tokens({}, true)));
})();

console.log(bad === 0 ? 'all ' + checks + ' checks passed' : bad + ' of ' + checks + ' checks FAILED');
process.exit(bad === 0 ? 0 : 1);
