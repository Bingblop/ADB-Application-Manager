/*
 * monet.js: the colour maths of Android 14's Material You engine, so this app can compute the theme colours that Android itself
 * does not offer as a setting (the Fidelity, Content and Monochrome styles) and write them with root (the Overlays tab, Root-only
 * theme controls). The app's own Settings theme (psPalettes, psScheme in index.html) does not use this file.
 *
 * Copyright 2021-2023 Google LLC
 * Copyright (C) 2021-2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Provenance (what was ported from where; the Java is AOSP frameworks/base at refs/heads/android14-release, the TypeScript is Google's
 * npm package @material/material-color-utilities 0.4.0; both Apache-2.0):
 *   packages/SystemUI/monet/src/com/android/systemui/monet/
 *     utils/MathUtils.java, utils/ColorUtils.java, hct/ViewingConditions.java, hct/Cam16.java, hct/HctSolver.java, hct/Hct.java,
 *     contrast/Contrast.java, palettes/TonalPalette.java (its key colour search), temperature/TemperatureCache.java,
 *     dislike/DislikeAnalyzer.java, scheme/DynamicScheme.java (getRotatedHue), scheme/Variant.java,
 *     scheme/SchemeTonalSpot.java, SchemeVibrant.java, SchemeExpressive.java, SchemeRainbow.java, SchemeFruitSalad.java,
 *     SchemeNeutral.java, SchemeFidelity.java, SchemeContent.java, SchemeMonochrome.java,
 *     dynamiccolor/DynamicColor.java, dynamiccolor/ToneDeltaConstraint.java, dynamiccolor/TonePolarity.java,
 *     dynamiccolor/MaterialDynamicColors.java (the Android 14 roles, control_*, text_*_inverse* and palette_key_color_* included)
 *   packages/SystemUI/src/com/android/systemui/theme/DynamicColors.kt (ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED: the 44 roles
 *     and 12 fixed colours Android 14 writes, in that order), ThemeOverlayController.java (the fixed colours come from the light scheme)
 *   packages/SystemUI/monet/src/com/android/systemui/monet/Shades.java and ColorScheme.kt (the 13 shades of a palette row:
 *     shade 0 = tone 100, shades 10 and 50 = tones 99 and 95 with the chroma capped at 40, shade 500 = tone 49.6, every other shade
 *     s = tone 100 - s / 10; GOOGLE_BLUE and MIN_CHROMA, the seed rule)
 *   @material/material-color-utilities 0.4.0 (typescript/): read beside every file above; it is the same code in TypeScript and was
 *     the oracle this port was checked against. Where Android 14 and 0.4.0 differ, Android 14 is followed (its engine writes the
 *     colours this app replaces): the key colour of a palette (a linear search from T50; 0.4.0 bisects), DislikeAnalyzer (a tone
 *     under 70 is disliked; 0.4.0 says 65), TonalPalette.tone (no averaging of tones 98 and 100 for a yellow at tone 99), and the
 *     whole DynamicColor machinery (Android 14 adjusts tones with toneMinContrast/toneMaxContrast, ToneDeltaConstraint and the
 *     Albers viewing-conditions step for Fidelity and Content; 0.4.0 has ContrastCurve and ToneDeltaPair instead), and the role
 *     tones of MaterialDynamicColors (on_*_container is tone 10 in light here, 30 in 0.4.0). What the oracle run showed (seeds
 *     6750A4 1B6EF3 B5651D 00FF00 808080, all nine styles, light and dark): every primitive (HCT, CAM16, the solver, Contrast,
 *     TemperatureCache, viewing conditions), every palette hue and chroma, every one of the 65 shade entries, the 12 fixed colours
 *     and the 8 control_* and text_* roles (0.4.0 has no such roles; they were compared as the palette tones Android 14 defines them
 *     by, taken from 0.4.0's palettes) agree with 0.4.0 bit for bit. The 5 palette_key_color_* roles follow Android 14's key colour
 *     search, a linear walk from T50: a chroma-0 palette gets tone 2, not T50 (CAM16 gives greys a chroma near 2 at T50 and the walk
 *     goes down until it rounds to 0), and other palettes can sit a tone off 0.4.0's bisection. Every other role that differs does so
 *     through the DynamicColor machinery above (for Fidelity and Content the primary roles and the containers can move by several
 *     tones: ToneDeltaConstraint, Albers and the contrast clamp) and the role tones named above (on_*_container; outline is tone 49
 *     in light, the "enableLightForeground" rule, not 50). A second oracle, Android 14's own monet Java (android14-release) compiled
 *     and run for the same seeds and styles, gave the same value as this file for every entry compared (palettes, shades, the 44
 *     roles light and dark, the fixed colours and the order of entries()).
 *
 * The maths is all doubles; Android's ColorScheme.kt feeds Shades.of with the seed's hue and chroma as floats (its framework Cam
 * class), so a palette entry computed here may differ from the phone's own by one RGB step in rare cases. Not checked on a device.
 *
 * API (window.MonetPlan in the page, module.exports in node):
 *   MonetPlan.STYLES                       the nine style ids this file knows: TONAL_SPOT VIBRANT EXPRESSIVE RAINBOW FRUIT_SALAD SPRITZ
 *                                          FIDELITY CONTENT MONOCHROMATIC (NEUTRAL and MONOCHROME are accepted as aliases)
 *   MonetPlan.hct(argb)                    { hue, chroma, tone } of a colour (CAM16 hue and chroma, L* as the tone)
 *   MonetPlan.seed(style, argb)            the seed the style really uses: a seed with chroma under 5 becomes Google blue, except for
 *                                          Fidelity and Content (they keep it) and Monochrome (no difference); 0 / nothing is Google blue
 *   MonetPlan.palettes(style, argb)        { seed, accent1..neutral2: { hue, chroma, key: 'RRGGBB', shades: ['RRGGBB' x 13] } }
 *   MonetPlan.roles(style, argb, isDark)   the 44 roles of Android 14 as [{ name, argb }] (argb a '0xAARRGGBB' string)
 *   MonetPlan.entries(style, argb)         the 165 overlay entries [{ resource, argb }]: 65 palette entries, the 44 roles for dark then
 *                                          light (as Android 14 writes them), the 12 fixed colours from the light scheme
 *   MonetPlan.lib                          the ported classes, for tests
 * Colours go in as 0xAARRGGBB numbers or 'RRGGBB' / '#RRGGBB' strings and come out as '0xAARRGGBB' or 'RRGGBB' strings.
 */
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    root.MonetPlan = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    // ---------- utils/MathUtils.java ----------
    function signum(num) { return num < 0 ? -1 : num === 0 ? 0 : 1; }
    function lerp(start, stop, amount) { return (1.0 - amount) * start + amount * stop; }
    function clampInt(min, max, input) { return input < min ? min : input > max ? max : input; }
    function clampDouble(min, max, input) { return input < min ? min : input > max ? max : input; }
    function sanitizeDegreesInt(degrees) { degrees = degrees % 360; if (degrees < 0) degrees = degrees + 360; return degrees; }
    function sanitizeDegreesDouble(degrees) { degrees = degrees % 360.0; if (degrees < 0) degrees = degrees + 360.0; return degrees; }
    function matrixMultiply(row, matrix) {
        const a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2];
        const b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2];
        const c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2];
        return [a, b, c];
    }

    // ---------- utils/ColorUtils.java (argb values are kept unsigned: 0xFF000000 and up, as JS cannot keep Java's signed int) ----------
    const SRGB_TO_XYZ = [[0.41233895, 0.35762064, 0.18051042], [0.2126, 0.7152, 0.0722], [0.01932141, 0.11916382, 0.95034478]];
    const XYZ_TO_SRGB = [
        [3.2413774792388685, -1.5376652402851851, -0.49885366846268053],
        [-0.9691452513005321, 1.8758853451067872, 0.04156585616912061],
        [0.05562093689691305, -0.20395524564742123, 1.0571799111220335]];
    const WHITE_POINT_D65 = [95.047, 100.0, 108.883];
    function argbFromRgb(red, green, blue) { return ((255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255)) >>> 0; }
    function argbFromLinrgb(linrgb) { return argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2])); }
    function redFromArgb(argb) { return (argb >> 16) & 255; }
    function greenFromArgb(argb) { return (argb >> 8) & 255; }
    function blueFromArgb(argb) { return argb & 255; }
    function argbFromXyz(x, y, z) {
        const m = XYZ_TO_SRGB;
        const linearR = m[0][0] * x + m[0][1] * y + m[0][2] * z;
        const linearG = m[1][0] * x + m[1][1] * y + m[1][2] * z;
        const linearB = m[2][0] * x + m[2][1] * y + m[2][2] * z;
        return argbFromRgb(delinearized(linearR), delinearized(linearG), delinearized(linearB));
    }
    function xyzFromArgb(argb) {
        return matrixMultiply([linearized(redFromArgb(argb)), linearized(greenFromArgb(argb)), linearized(blueFromArgb(argb))], SRGB_TO_XYZ);
    }
    function labFromArgb(argb) {
        const linearR = linearized(redFromArgb(argb)), linearG = linearized(greenFromArgb(argb)), linearB = linearized(blueFromArgb(argb));
        const m = SRGB_TO_XYZ;
        const x = m[0][0] * linearR + m[0][1] * linearG + m[0][2] * linearB;
        const y = m[1][0] * linearR + m[1][1] * linearG + m[1][2] * linearB;
        const z = m[2][0] * linearR + m[2][1] * linearG + m[2][2] * linearB;
        const fx = labF(x / WHITE_POINT_D65[0]), fy = labF(y / WHITE_POINT_D65[1]), fz = labF(z / WHITE_POINT_D65[2]);
        return [116.0 * fy - 16, 500.0 * (fx - fy), 200.0 * (fy - fz)];
    }
    function argbFromLstar(lstar) { const component = delinearized(yFromLstar(lstar)); return argbFromRgb(component, component, component); }
    function lstarFromArgb(argb) { return 116.0 * labF(xyzFromArgb(argb)[1] / 100.0) - 16.0; }
    function yFromLstar(lstar) { return 100.0 * labInvf((lstar + 16.0) / 116.0); }
    function lstarFromY(y) { return labF(y / 100.0) * 116.0 - 16.0; }
    function linearized(rgbComponent) {
        const normalized = rgbComponent / 255.0;
        return normalized <= 0.040449936 ? normalized / 12.92 * 100.0 : Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
    }
    function delinearized(rgbComponent) {
        const normalized = rgbComponent / 100.0;
        const d = normalized <= 0.0031308 ? normalized * 12.92 : 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
        return clampInt(0, 255, Math.round(d * 255.0));
    }
    function labF(t) { const e = 216.0 / 24389.0, kappa = 24389.0 / 27.0; return t > e ? Math.pow(t, 1.0 / 3.0) : (kappa * t + 16) / 116; }
    function labInvf(ft) { const e = 216.0 / 24389.0, kappa = 24389.0 / 27.0, ft3 = ft * ft * ft; return ft3 > e ? ft3 : (116 * ft - 16) / kappa; }

    // ---------- hct/ViewingConditions.java ----------
    const XYZ_TO_CAM16RGB = [[0.401288, 0.650173, -0.051461], [-0.250268, 1.204414, 0.045854], [-0.002079, 0.048952, 0.953127]];
    const CAM16RGB_TO_XYZ = [[1.8620678, -1.0112547, 0.14918678], [0.38752654, 0.62144744, -0.00897398], [-0.01584150, -0.03412294, 1.0499644]];
    class ViewingConditions {
        constructor(n, aw, nbb, ncb, c, nc, rgbD, fl, flRoot, z) {
            this.n = n; this.aw = aw; this.nbb = nbb; this.ncb = ncb; this.c = c; this.nc = nc; this.rgbD = rgbD; this.fl = fl; this.flRoot = flRoot; this.z = z;
        }
        static make(whitePoint, adaptingLuminance, backgroundLstar, surround, discountingIlluminant) {
            backgroundLstar = Math.max(0.1, backgroundLstar);
            const matrix = XYZ_TO_CAM16RGB, xyz = whitePoint;
            const rW = xyz[0] * matrix[0][0] + xyz[1] * matrix[0][1] + xyz[2] * matrix[0][2];
            const gW = xyz[0] * matrix[1][0] + xyz[1] * matrix[1][1] + xyz[2] * matrix[1][2];
            const bW = xyz[0] * matrix[2][0] + xyz[1] * matrix[2][1] + xyz[2] * matrix[2][2];
            const f = 0.8 + surround / 10.0;
            const c = f >= 0.9 ? lerp(0.59, 0.69, (f - 0.9) * 10.0) : lerp(0.525, 0.59, (f - 0.8) * 10.0);
            let d = discountingIlluminant ? 1.0 : f * (1.0 - (1.0 / 3.6) * Math.exp((-adaptingLuminance - 42.0) / 92.0));
            d = clampDouble(0.0, 1.0, d);
            const nc = f;
            const rgbD = [d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d];
            const k = 1.0 / (5.0 * adaptingLuminance + 1.0), k4 = k * k * k * k, k4F = 1.0 - k4;
            const fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance);
            const n = yFromLstar(backgroundLstar) / whitePoint[1];
            const z = 1.48 + Math.sqrt(n);
            const nbb = 0.725 / Math.pow(n, 0.2), ncb = nbb;
            const rgbAFactors = [Math.pow(fl * rgbD[0] * rW / 100.0, 0.42), Math.pow(fl * rgbD[1] * gW / 100.0, 0.42), Math.pow(fl * rgbD[2] * bW / 100.0, 0.42)];
            const rgbA = [400.0 * rgbAFactors[0] / (rgbAFactors[0] + 27.13), 400.0 * rgbAFactors[1] / (rgbAFactors[1] + 27.13), 400.0 * rgbAFactors[2] / (rgbAFactors[2] + 27.13)];
            const aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb;
            return new ViewingConditions(n, aw, nbb, ncb, c, nc, rgbD, fl, Math.pow(fl, 0.25), z);
        }
        static defaultWithBackgroundLstar(lstar) {
            return ViewingConditions.make(WHITE_POINT_D65, 200.0 / Math.PI * yFromLstar(50.0) / 100.0, lstar, 2.0, false);
        }
    }
    ViewingConditions.DEFAULT = ViewingConditions.defaultWithBackgroundLstar(50.0);

    // ---------- hct/Cam16.java (the parts Hct needs) ----------
    class Cam16 {
        constructor(hue, chroma, j, q, m, s, jstar, astar, bstar) {
            this.hue = hue; this.chroma = chroma; this.j = j; this.q = q; this.m = m; this.s = s; this.jstar = jstar; this.astar = astar; this.bstar = bstar;
        }
        static fromInt(argb) { return Cam16.fromIntInViewingConditions(argb, ViewingConditions.DEFAULT); }
        static fromIntInViewingConditions(argb, vc) {
            const red = (argb & 0x00ff0000) >> 16, green = (argb & 0x0000ff00) >> 8, blue = argb & 0x000000ff;
            const redL = linearized(red), greenL = linearized(green), blueL = linearized(blue);
            const x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL;
            const y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL;
            const z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL;
            return Cam16.fromXyzInViewingConditions(x, y, z, vc);
        }
        static fromXyzInViewingConditions(x, y, z, vc) {
            const matrix = XYZ_TO_CAM16RGB;
            const rT = x * matrix[0][0] + y * matrix[0][1] + z * matrix[0][2];
            const gT = x * matrix[1][0] + y * matrix[1][1] + z * matrix[1][2];
            const bT = x * matrix[2][0] + y * matrix[2][1] + z * matrix[2][2];
            const rD = vc.rgbD[0] * rT, gD = vc.rgbD[1] * gT, bD = vc.rgbD[2] * bT;
            const rAF = Math.pow(vc.fl * Math.abs(rD) / 100.0, 0.42), gAF = Math.pow(vc.fl * Math.abs(gD) / 100.0, 0.42), bAF = Math.pow(vc.fl * Math.abs(bD) / 100.0, 0.42);
            const rA = signum(rD) * 400.0 * rAF / (rAF + 27.13), gA = signum(gD) * 400.0 * gAF / (gAF + 27.13), bA = signum(bD) * 400.0 * bAF / (bAF + 27.13);
            const a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
            const b = (rA + gA - 2.0 * bA) / 9.0;
            const u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
            const p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
            const atanDegrees = Math.atan2(b, a) * 180.0 / Math.PI;
            const hue = atanDegrees < 0 ? atanDegrees + 360.0 : atanDegrees >= 360 ? atanDegrees - 360.0 : atanDegrees;
            const hueRadians = hue * Math.PI / 180.0;
            const ac = p2 * vc.nbb;
            const j = 100.0 * Math.pow(ac / vc.aw, vc.c * vc.z);
            const q = 4.0 / vc.c * Math.sqrt(j / 100.0) * (vc.aw + 4.0) * vc.flRoot;
            const huePrime = hue < 20.14 ? hue + 360 : hue;
            const eHue = 1.0 / 4.0 * (Math.cos(huePrime * Math.PI / 180.0 + 2.0) + 3.8);
            const p1 = 50000.0 / 13.0 * eHue * vc.nc * vc.ncb;
            const t = p1 * Math.hypot(a, b) / (u + 0.305);
            const alpha = Math.pow(t, 0.9) * Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73);
            const c = alpha * Math.sqrt(j / 100.0);
            const m = c * vc.flRoot;
            const s = 50.0 * Math.sqrt(alpha * vc.c / (vc.aw + 4.0));
            const jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j);
            const mstar = 1.0 / 0.0228 * Math.log(1.0 + 0.0228 * m);
            return new Cam16(hue, c, j, q, m, s, jstar, mstar * Math.cos(hueRadians), mstar * Math.sin(hueRadians));
        }
        xyzInViewingConditions(vc) {
            const alpha = this.chroma === 0.0 || this.j === 0.0 ? 0.0 : this.chroma / Math.sqrt(this.j / 100.0);
            const t = Math.pow(alpha / Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73), 1.0 / 0.9);
            const hRad = this.hue * Math.PI / 180.0;
            const eHue = 0.25 * (Math.cos(hRad + 2.0) + 3.8);
            const ac = vc.aw * Math.pow(this.j / 100.0, 1.0 / vc.c / vc.z);
            const p1 = eHue * (50000.0 / 13.0) * vc.nc * vc.ncb;
            const p2 = ac / vc.nbb;
            const hSin = Math.sin(hRad), hCos = Math.cos(hRad);
            const gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11 * t * hCos + 108.0 * t * hSin);
            const a = gamma * hCos, b = gamma * hSin;
            const rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
            const gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
            const bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
            const rCBase = Math.max(0, 27.13 * Math.abs(rA) / (400.0 - Math.abs(rA)));
            const rC = signum(rA) * (100.0 / vc.fl) * Math.pow(rCBase, 1.0 / 0.42);
            const gCBase = Math.max(0, 27.13 * Math.abs(gA) / (400.0 - Math.abs(gA)));
            const gC = signum(gA) * (100.0 / vc.fl) * Math.pow(gCBase, 1.0 / 0.42);
            const bCBase = Math.max(0, 27.13 * Math.abs(bA) / (400.0 - Math.abs(bA)));
            const bC = signum(bA) * (100.0 / vc.fl) * Math.pow(bCBase, 1.0 / 0.42);
            const rF = rC / vc.rgbD[0], gF = gC / vc.rgbD[1], bF = bC / vc.rgbD[2];
            const matrix = CAM16RGB_TO_XYZ;
            return [rF * matrix[0][0] + gF * matrix[0][1] + bF * matrix[0][2],
                rF * matrix[1][0] + gF * matrix[1][1] + bF * matrix[1][2],
                rF * matrix[2][0] + gF * matrix[2][1] + bF * matrix[2][2]];
        }
    }

    // ---------- hct/HctSolver.java ----------
    const SCALED_DISCOUNT_FROM_LINRGB = [
        [0.001200833568784504, 0.002389694492170889, 0.0002795742885861124],
        [0.0005891086651375999, 0.0029785502573438758, 0.0003270666104008398],
        [0.00010146692491640572, 0.0005364214359186694, 0.0032979401770712076]];
    const LINRGB_FROM_SCALED_DISCOUNT = [
        [1373.2198709594231, -1100.4251190754821, -7.278681089101213],
        [-271.815969077903, 559.6580465940733, -32.46047482791194],
        [1.9622899599665666, -57.173814538844006, 308.7233197812385]];
    const Y_FROM_LINRGB = [0.2126, 0.7152, 0.0722];
    const CRITICAL_PLANES = [
        0.015176349177441876, 0.045529047532325624, 0.07588174588720938, 0.10623444424209313, 0.13658714259697685, 0.16693984095186062,
        0.19729253930674434, 0.2276452376616281, 0.2579979360165119, 0.28835063437139563, 0.3188300904430532, 0.350925934958123,
        0.3848314933096426, 0.42057480301049466, 0.458183274052838, 0.4976837250274023, 0.5391024159806381, 0.5824650784040898,
        0.6277969426914107, 0.6751227633498623, 0.7244668422128921, 0.775853049866786, 0.829304845476233, 0.8848452951698498,
        0.942497089126609, 1.0022825574869039, 1.0642236851973577, 1.1283421258858297, 1.1946592148522128, 1.2631959812511864,
        1.3339731595349034, 1.407011200216447, 1.4823302800086415, 1.5599503113873272, 1.6398909516233677, 1.7221716113234105,
        1.8068114625156377, 1.8938294463134073, 1.9832442801866852, 2.075074464868551, 2.1693382909216234, 2.2660538449872063,
        2.36523901573795, 2.4669114995532007, 2.5710888059345764, 2.6777882626779785, 2.7870270208169257, 2.898822059350997,
        3.0131901897720907, 3.1301480604002863, 3.2497121605402226, 3.3718988244681087, 3.4967242352587946, 3.624204428461639,
        3.754355295633311, 3.887192587735158, 4.022731918402185, 4.160988767090289, 4.301978482107941, 4.445716283538092,
        4.592217266055746, 4.741496401646282, 4.893568542229298, 5.048448422192488, 5.20615066083972, 5.3666897647573375,
        5.5300801301023865, 5.696336044816294, 5.865471690767354, 6.037501145825082, 6.212438385869475, 6.390297286737924,
        6.571091626112461, 6.7548350853498045, 6.941541251256611, 7.131223617812143, 7.323895587840543, 7.5195704746346665,
        7.7182615035334345, 7.919981813454504, 8.124744458384042, 8.332562408825165, 8.543448553206703, 8.757415699253682,
        8.974476575321063, 9.194643831691977, 9.417930041841839, 9.644347703669503, 9.873909240696694, 10.106627003236781,
        10.342513269534024, 10.58158024687427, 10.8238400726681, 11.069304815507364, 11.317986476196008, 11.569896988756009,
        11.825048221409341, 12.083451977536606, 12.345119996613247, 12.610063955123938, 12.878295467455942, 13.149826086772048,
        13.42466730586372, 13.702830557985108, 13.984327217668513, 14.269168601521828, 14.55736596900856, 14.848930523210871,
        15.143873411576273, 15.44220572664832, 15.743938506781891, 16.04908273684337, 16.35764934889634, 16.66964922287304,
        16.985093187232053, 17.30399201960269, 17.62635644741625, 17.95219714852476, 18.281524751807332, 18.614349837764564,
        18.95068293910138, 19.290534541298456, 19.633915083172692, 19.98083495742689, 20.331304511189067, 20.685334046541502,
        21.042933821039977, 21.404114048223256, 21.76888489811322, 22.137256497705877, 22.50923893145328, 22.884842241736916,
        23.264076429332462, 23.6469514538663, 24.033477234264016, 24.42366364919083, 24.817520537484558, 25.21505769858089,
        25.61628489293138, 26.021211842414342, 26.429848230738664, 26.842203703840827, 27.258287870275353, 27.678110301598522,
        28.10168053274597, 28.529008062403893, 28.96010235337422, 29.39497283293396, 29.83362889318845, 30.276079891419332,
        30.722335150426627, 31.172403958865512, 31.62629557157785, 32.08401920991837, 32.54558406207592, 33.010999283389665,
        33.4802739966603, 33.953417292456834, 34.430438229418264, 34.911345834551085, 35.39614910352207, 35.88485700094671,
        36.37747846067349, 36.87402238606382, 37.37449765026789, 37.87891309649659, 38.38727753828926, 38.89959975977785,
        39.41588851594697, 39.93615253289054, 40.460400508064545, 40.98864111053629, 41.520882981230194, 42.05713473317016,
        42.597404951718396, 43.141702194811224, 43.6900349931913, 44.24241185063697, 44.798841244188324, 45.35933162437017,
        45.92389141541209, 46.49252901546552, 47.065252796817916, 47.64207110610409, 48.22299226451468, 48.808024568002054,
        49.3971762874833, 49.9904556690408, 50.587870934119984, 51.189430279724725, 51.79514187861014, 52.40501387947288,
        53.0190544071392, 53.637271562750364, 54.259673423945976, 54.88626804504493, 55.517063457223934, 56.15206766869424,
        56.79128866487574, 57.43473440856916, 58.08241284012621, 58.734331877617365, 59.39049941699807, 60.05092333227251,
        60.715611475655585, 61.38457167773311, 62.057811747619894, 62.7353394731159, 63.417162620860914, 64.10328893648692,
        64.79372614476921, 65.48848194977529, 66.18756403501224, 66.89098006357258, 67.59873767827808, 68.31084450182222,
        69.02730813691093, 69.74813616640164, 70.47333615344107, 71.20291564160104, 71.93688215501312, 72.67524319850172,
        73.41800625771542, 74.16517879925733, 74.9167682708136, 75.67278210128072, 76.43322770089146, 77.1981124613393,
        77.96744375590167, 78.74122893956174, 79.51947534912904, 80.30219030335869, 81.08938110306934, 81.88105503125999,
        82.67721935322541, 83.4778813166706, 84.28304815182372, 85.09272707154808, 85.90692527145302, 86.72564993000343,
        87.54890820862819, 88.3767072518277, 89.2090541872801, 90.04595612594655, 90.88742016217518, 91.73345337380438,
        92.58406282226491, 93.43925555268066, 94.29903859396902, 95.16341895893969, 96.03240364439274, 96.9059996312159,
        97.78421388448044, 98.6670533535366, 99.55452497210776];
    const HctSolver = {
        sanitizeRadians(angle) { return (angle + Math.PI * 8) % (Math.PI * 2); },
        trueDelinearized(rgbComponent) {
            const normalized = rgbComponent / 100.0;
            const d = normalized <= 0.0031308 ? normalized * 12.92 : 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
            return d * 255.0;
        },
        chromaticAdaptation(component) { const af = Math.pow(Math.abs(component), 0.42); return signum(component) * 400.0 * af / (af + 27.13); },
        hueOf(linrgb) {
            const scaledDiscount = matrixMultiply(linrgb, SCALED_DISCOUNT_FROM_LINRGB);
            const rA = HctSolver.chromaticAdaptation(scaledDiscount[0]), gA = HctSolver.chromaticAdaptation(scaledDiscount[1]), bA = HctSolver.chromaticAdaptation(scaledDiscount[2]);
            const a = (11.0 * rA + -12.0 * gA + bA) / 11.0, b = (rA + gA - 2.0 * bA) / 9.0;
            return Math.atan2(b, a);
        },
        areInCyclicOrder(a, b, c) { return HctSolver.sanitizeRadians(b - a) < HctSolver.sanitizeRadians(c - a); },
        intercept(source, mid, target) { return (mid - source) / (target - source); },
        lerpPoint(source, t, target) { return [source[0] + (target[0] - source[0]) * t, source[1] + (target[1] - source[1]) * t, source[2] + (target[2] - source[2]) * t]; },
        setCoordinate(source, coordinate, target, axis) { return HctSolver.lerpPoint(source, HctSolver.intercept(source[axis], coordinate, target[axis]), target); },
        isBounded(x) { return 0.0 <= x && x <= 100.0; },
        nthVertex(y, n) {
            const kR = Y_FROM_LINRGB[0], kG = Y_FROM_LINRGB[1], kB = Y_FROM_LINRGB[2];
            const coordA = n % 4 <= 1 ? 0.0 : 100.0, coordB = n % 2 === 0 ? 0.0 : 100.0;
            if (n < 4) { const g = coordA, b = coordB, r = (y - g * kG - b * kB) / kR; return HctSolver.isBounded(r) ? [r, g, b] : [-1.0, -1.0, -1.0]; }
            if (n < 8) { const b = coordA, r = coordB, g = (y - r * kR - b * kB) / kG; return HctSolver.isBounded(g) ? [r, g, b] : [-1.0, -1.0, -1.0]; }
            const r = coordA, g = coordB, b = (y - r * kR - g * kG) / kB; return HctSolver.isBounded(b) ? [r, g, b] : [-1.0, -1.0, -1.0];
        },
        bisectToSegment(y, targetHue) {
            let left = [-1.0, -1.0, -1.0], right = left, leftHue = 0.0, rightHue = 0.0, initialized = false, uncut = true;
            for (let n = 0; n < 12; n++) {
                const mid = HctSolver.nthVertex(y, n);
                if (mid[0] < 0) continue;
                const midHue = HctSolver.hueOf(mid);
                if (!initialized) { left = mid; right = mid; leftHue = midHue; rightHue = midHue; initialized = true; continue; }
                if (uncut || HctSolver.areInCyclicOrder(leftHue, midHue, rightHue)) {
                    uncut = false;
                    if (HctSolver.areInCyclicOrder(leftHue, targetHue, midHue)) { right = mid; rightHue = midHue; }
                    else { left = mid; leftHue = midHue; }
                }
            }
            return [left, right];
        },
        midpoint(a, b) { return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2]; },
        criticalPlaneBelow(x) { return Math.floor(x - 0.5); },
        criticalPlaneAbove(x) { return Math.ceil(x - 0.5); },
        bisectToLimit(y, targetHue) {
            const segment = HctSolver.bisectToSegment(y, targetHue);
            let left = segment[0], leftHue = HctSolver.hueOf(left), right = segment[1];
            for (let axis = 0; axis < 3; axis++) {
                if (left[axis] !== right[axis]) {
                    let lPlane = -1, rPlane = 255;
                    if (left[axis] < right[axis]) { lPlane = HctSolver.criticalPlaneBelow(HctSolver.trueDelinearized(left[axis])); rPlane = HctSolver.criticalPlaneAbove(HctSolver.trueDelinearized(right[axis])); }
                    else { lPlane = HctSolver.criticalPlaneAbove(HctSolver.trueDelinearized(left[axis])); rPlane = HctSolver.criticalPlaneBelow(HctSolver.trueDelinearized(right[axis])); }
                    for (let i = 0; i < 8; i++) {
                        if (Math.abs(rPlane - lPlane) <= 1) break;
                        const mPlane = Math.floor((lPlane + rPlane) / 2.0);
                        const midPlaneCoordinate = CRITICAL_PLANES[mPlane];
                        const mid = HctSolver.setCoordinate(left, midPlaneCoordinate, right, axis);
                        const midHue = HctSolver.hueOf(mid);
                        if (HctSolver.areInCyclicOrder(leftHue, targetHue, midHue)) { right = mid; rPlane = mPlane; }
                        else { left = mid; leftHue = midHue; lPlane = mPlane; }
                    }
                }
            }
            return HctSolver.midpoint(left, right);
        },
        inverseChromaticAdaptation(adapted) {
            const adaptedAbs = Math.abs(adapted);
            const base = Math.max(0, 27.13 * adaptedAbs / (400.0 - adaptedAbs));
            return signum(adapted) * Math.pow(base, 1.0 / 0.42);
        },
        findResultByJ(hueRadians, chroma, y) {
            let j = Math.sqrt(y) * 11.0;
            const vc = ViewingConditions.DEFAULT;
            const tInnerCoeff = 1 / Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73);
            const eHue = 0.25 * (Math.cos(hueRadians + 2.0) + 3.8);
            const p1 = eHue * (50000.0 / 13.0) * vc.nc * vc.ncb;
            const hSin = Math.sin(hueRadians), hCos = Math.cos(hueRadians);
            for (let iterationRound = 0; iterationRound < 5; iterationRound++) {
                const jNormalized = j / 100.0;
                const alpha = chroma === 0.0 || j === 0.0 ? 0.0 : chroma / Math.sqrt(jNormalized);
                const t = Math.pow(alpha * tInnerCoeff, 1.0 / 0.9);
                const ac = vc.aw * Math.pow(jNormalized, 1.0 / vc.c / vc.z);
                const p2 = ac / vc.nbb;
                const gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11 * t * hCos + 108.0 * t * hSin);
                const a = gamma * hCos, b = gamma * hSin;
                const rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
                const gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
                const bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
                const linrgb = matrixMultiply([HctSolver.inverseChromaticAdaptation(rA), HctSolver.inverseChromaticAdaptation(gA), HctSolver.inverseChromaticAdaptation(bA)], LINRGB_FROM_SCALED_DISCOUNT);
                if (linrgb[0] < 0 || linrgb[1] < 0 || linrgb[2] < 0) return 0;
                const fnj = Y_FROM_LINRGB[0] * linrgb[0] + Y_FROM_LINRGB[1] * linrgb[1] + Y_FROM_LINRGB[2] * linrgb[2];
                if (fnj <= 0) return 0;
                if (iterationRound === 4 || Math.abs(fnj - y) < 0.002) {
                    if (linrgb[0] > 100.01 || linrgb[1] > 100.01 || linrgb[2] > 100.01) return 0;
                    return argbFromLinrgb(linrgb);
                }
                j = j - (fnj - y) * j / (2 * fnj);
            }
            return 0;
        },
        solveToInt(hueDegrees, chroma, lstar) {
            if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) return argbFromLstar(lstar);
            hueDegrees = sanitizeDegreesDouble(hueDegrees);
            const hueRadians = hueDegrees / 180 * Math.PI;
            const y = yFromLstar(lstar);
            const exactAnswer = HctSolver.findResultByJ(hueRadians, chroma, y);
            if (exactAnswer !== 0) return exactAnswer;
            return argbFromLinrgb(HctSolver.bisectToLimit(y, hueRadians));
        }
    };

    // ---------- hct/Hct.java ----------
    class Hct {
        constructor(argb) { this.setInternalState(argb >>> 0); }
        static from(hue, chroma, tone) { return new Hct(HctSolver.solveToInt(hue, chroma, tone)); }
        static fromInt(argb) { return new Hct(argb); }
        toInt() { return this.argb; }
        setInternalState(argb) {
            this.argb = argb;
            const cam = Cam16.fromInt(argb);
            this.hue = cam.hue; this.chroma = cam.chroma; this.tone = lstarFromArgb(argb);
        }
        inViewingConditions(vc) {
            const cam16 = Cam16.fromInt(this.toInt());
            const viewedInVc = cam16.xyzInViewingConditions(vc);
            const recastInVc = Cam16.fromXyzInViewingConditions(viewedInVc[0], viewedInVc[1], viewedInVc[2], ViewingConditions.DEFAULT);
            return Hct.from(recastInVc.hue, recastInVc.chroma, lstarFromY(viewedInVc[1]));
        }
    }

    // ---------- contrast/Contrast.java ----------
    const Contrast = {
        RATIO_MIN: 1.0, RATIO_MAX: 21.0, RATIO_30: 3.0, RATIO_45: 4.5, RATIO_70: 7.0,
        ratioOfYs(y1, y2) { const lighter = Math.max(y1, y2), darker = lighter === y2 ? y1 : y2; return (lighter + 5.0) / (darker + 5.0); },
        ratioOfTones(t1, t2) { return Contrast.ratioOfYs(yFromLstar(t1), yFromLstar(t2)); },
        lighter(tone, ratio) {
            if (tone < 0.0 || tone > 100.0) return -1.0;
            const darkY = yFromLstar(tone), lightY = ratio * (darkY + 5.0) - 5.0;
            if (lightY < 0.0 || lightY > 100.0) return -1.0;
            const realContrast = Contrast.ratioOfYs(lightY, darkY), delta = Math.abs(realContrast - ratio);
            if (realContrast < ratio && delta > 0.04) return -1.0;
            const returnValue = lstarFromY(lightY) + 0.4;
            return returnValue < 0 || returnValue > 100 ? -1.0 : returnValue;
        },
        lighterUnsafe(tone, ratio) { const lighterSafe = Contrast.lighter(tone, ratio); return lighterSafe < 0.0 ? 100.0 : lighterSafe; },
        darker(tone, ratio) {
            if (tone < 0.0 || tone > 100.0) return -1.0;
            const lightY = yFromLstar(tone), darkY = (lightY + 5.0) / ratio - 5.0;
            if (darkY < 0.0 || darkY > 100.0) return -1.0;
            const realContrast = Contrast.ratioOfYs(lightY, darkY), delta = Math.abs(realContrast - ratio);
            if (realContrast < ratio && delta > 0.04) return -1.0;
            const returnValue = lstarFromY(darkY) - 0.4;
            return returnValue < 0 || returnValue > 100 ? -1.0 : returnValue;
        },
        darkerUnsafe(tone, ratio) { return Math.max(0.0, Contrast.darker(tone, ratio)); }
    };

    // ---------- palettes/TonalPalette.java (Android 14's key colour search, not 0.4.0's bisection) ----------
    class TonalPalette {
        constructor(hue, chroma, keyColor) { this.hue = hue; this.chroma = chroma; this.keyColor = keyColor; this.cache = new Map(); }
        static fromInt(argb) { return TonalPalette.fromHct(Hct.fromInt(argb)); }
        static fromHct(hct) { return new TonalPalette(hct.hue, hct.chroma, hct); }
        static fromHueAndChroma(hue, chroma) { return new TonalPalette(hue, chroma, TonalPalette.createKeyColor(hue, chroma)); }
        static createKeyColor(hue, chroma) {
            const startTone = 50.0;
            let smallestDeltaHct = Hct.from(hue, chroma, startTone);
            let smallestDelta = Math.abs(smallestDeltaHct.chroma - chroma);
            for (let delta = 1.0; delta < 50.0; delta += 1.0) {
                if (Math.round(chroma) === Math.round(smallestDeltaHct.chroma)) return smallestDeltaHct;
                const hctAdd = Hct.from(hue, chroma, startTone + delta);
                const hctAddDelta = Math.abs(hctAdd.chroma - chroma);
                if (hctAddDelta < smallestDelta) { smallestDelta = hctAddDelta; smallestDeltaHct = hctAdd; }
                const hctSubtract = Hct.from(hue, chroma, startTone - delta);
                const hctSubtractDelta = Math.abs(hctSubtract.chroma - chroma);
                if (hctSubtractDelta < smallestDelta) { smallestDelta = hctSubtractDelta; smallestDeltaHct = hctSubtract; }
            }
            return smallestDeltaHct;
        }
        tone(tone) {
            let color = this.cache.get(tone);
            if (color === undefined) { color = Hct.from(this.hue, this.chroma, tone).toInt(); this.cache.set(tone, color); }
            return color;
        }
        getHct(tone) { return Hct.from(this.hue, this.chroma, tone); }
    }

    // ---------- temperature/TemperatureCache.java ----------
    class TemperatureCache {
        constructor(input) { this.input = input; this.precomputedComplement = null; this.precomputedHctsByTemp = null; this.precomputedHctsByHue = null; this.precomputedTempsByHct = null; }
        getComplement() {
            if (this.precomputedComplement !== null) return this.precomputedComplement;
            const coldestHue = this.getColdest().hue, coldestTemp = this.getTempsByHct().get(this.getColdest());
            const warmestHue = this.getWarmest().hue, warmestTemp = this.getTempsByHct().get(this.getWarmest());
            const range = warmestTemp - coldestTemp;
            const startHueIsColdestToWarmest = TemperatureCache.isBetween(this.input.hue, coldestHue, warmestHue);
            const startHue = startHueIsColdestToWarmest ? warmestHue : coldestHue;
            const endHue = startHueIsColdestToWarmest ? coldestHue : warmestHue;
            const directionOfRotation = 1.0;
            let smallestError = 1000.0;
            let answer = this.getHctsByHue()[Math.round(this.input.hue)];
            const complementRelativeTemp = 1.0 - this.getRelativeTemperature(this.input);
            for (let hueAddend = 0.0; hueAddend <= 360.0; hueAddend += 1.0) {
                const hue = sanitizeDegreesDouble(startHue + directionOfRotation * hueAddend);
                if (!TemperatureCache.isBetween(hue, startHue, endHue)) continue;
                const possibleAnswer = this.getHctsByHue()[Math.round(hue)];
                const relativeTemp = (this.getTempsByHct().get(possibleAnswer) - coldestTemp) / range;
                const error = Math.abs(complementRelativeTemp - relativeTemp);
                if (error < smallestError) { smallestError = error; answer = possibleAnswer; }
            }
            this.precomputedComplement = answer;
            return answer;
        }
        getAnalogousColors(count, divisions) {
            const startHue = Math.round(this.input.hue);
            const startHct = this.getHctsByHue()[startHue];
            let lastTemp = this.getRelativeTemperature(startHct);
            const allColors = [startHct];
            let absoluteTotalTempDelta = 0.0;
            for (let i = 0; i < 360; i++) {
                const hue = sanitizeDegreesInt(startHue + i);
                const hct = this.getHctsByHue()[hue];
                const temp = this.getRelativeTemperature(hct);
                const tempDelta = Math.abs(temp - lastTemp);
                lastTemp = temp;
                absoluteTotalTempDelta += tempDelta;
            }
            let hueAddend = 1;
            const tempStep = absoluteTotalTempDelta / divisions;
            let totalTempDelta = 0.0;
            lastTemp = this.getRelativeTemperature(startHct);
            while (allColors.length < divisions) {
                const hue = sanitizeDegreesInt(startHue + hueAddend);
                const hct = this.getHctsByHue()[hue];
                const temp = this.getRelativeTemperature(hct);
                const tempDelta = Math.abs(temp - lastTemp);
                totalTempDelta += tempDelta;
                let desiredTotalTempDeltaForIndex = allColors.length * tempStep;
                let indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
                let indexAddend = 1;
                while (indexSatisfied && allColors.length < divisions) {
                    allColors.push(hct);
                    desiredTotalTempDeltaForIndex = (allColors.length + indexAddend) * tempStep;
                    indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
                    indexAddend++;
                }
                lastTemp = temp;
                hueAddend++;
                if (hueAddend > 360) { while (allColors.length < divisions) allColors.push(hct); break; }
            }
            const answers = [this.input];
            const ccwCount = Math.floor((count - 1.0) / 2.0);
            for (let i = 1; i < ccwCount + 1; i++) {
                let index = 0 - i;
                while (index < 0) index = allColors.length + index;
                if (index >= allColors.length) index = index % allColors.length;
                answers.splice(0, 0, allColors[index]);
            }
            const cwCount = count - ccwCount - 1;
            for (let i = 1; i < cwCount + 1; i++) {
                let index = i;
                while (index < 0) index = allColors.length + index;
                if (index >= allColors.length) index = index % allColors.length;
                answers.push(allColors[index]);
            }
            return answers;
        }
        getRelativeTemperature(hct) {
            const range = this.getTempsByHct().get(this.getWarmest()) - this.getTempsByHct().get(this.getColdest());
            const differenceFromColdest = this.getTempsByHct().get(hct) - this.getTempsByHct().get(this.getColdest());
            if (range === 0.0) return 0.5;
            return differenceFromColdest / range;
        }
        static rawTemperature(color) {
            const lab = labFromArgb(color.toInt());
            const hue = sanitizeDegreesDouble(Math.atan2(lab[2], lab[1]) * 180.0 / Math.PI);
            const chroma = Math.hypot(lab[1], lab[2]);
            return -0.5 + 0.02 * Math.pow(chroma, 1.07) * Math.cos(sanitizeDegreesDouble(hue - 50.0) * Math.PI / 180.0);
        }
        getColdest() { return this.getHctsByTemp()[0]; }
        getHctsByHue() {
            if (this.precomputedHctsByHue !== null) return this.precomputedHctsByHue;
            const hcts = [];
            for (let hue = 0.0; hue <= 360.0; hue += 1.0) hcts.push(Hct.from(hue, this.input.chroma, this.input.tone));
            this.precomputedHctsByHue = hcts;
            return hcts;
        }
        getHctsByTemp() {
            if (this.precomputedHctsByTemp !== null) return this.precomputedHctsByTemp;
            const hcts = this.getHctsByHue().concat([this.input]);
            const temps = this.getTempsByHct();
            hcts.sort((a, b) => temps.get(a) - temps.get(b));        // a stable sort, as Java's Collections.sort is
            this.precomputedHctsByTemp = hcts;
            return hcts;
        }
        getTempsByHct() {
            if (this.precomputedTempsByHct !== null) return this.precomputedTempsByHct;
            const allHcts = this.getHctsByHue().concat([this.input]);
            const temperaturesByHct = new Map();
            for (const hct of allHcts) temperaturesByHct.set(hct, TemperatureCache.rawTemperature(hct));
            this.precomputedTempsByHct = temperaturesByHct;
            return temperaturesByHct;
        }
        getWarmest() { const byTemp = this.getHctsByTemp(); return byTemp[byTemp.length - 1]; }
        static isBetween(angle, a, b) { if (a < b) return a <= angle && angle <= b; return a <= angle || angle <= b; }
    }

    // ---------- dislike/DislikeAnalyzer.java (Android 14: a tone under 70 is disliked; 0.4.0 says 65) ----------
    const DislikeAnalyzer = {
        isDisliked(hct) {
            const huePasses = Math.round(hct.hue) >= 90.0 && Math.round(hct.hue) <= 111.0;
            const chromaPasses = Math.round(hct.chroma) > 16.0;
            const tonePasses = Math.round(hct.tone) < 70.0;
            return huePasses && chromaPasses && tonePasses;
        },
        fixIfDisliked(hct) { return DislikeAnalyzer.isDisliked(hct) ? Hct.from(hct.hue, hct.chroma, 70.0) : hct; }
    };

    // ---------- scheme/Variant.java, scheme/DynamicScheme.java and the nine Scheme*.java ----------
    const Variant = { MONOCHROME: 'MONOCHROME', NEUTRAL: 'NEUTRAL', TONAL_SPOT: 'TONAL_SPOT', VIBRANT: 'VIBRANT', EXPRESSIVE: 'EXPRESSIVE', FIDELITY: 'FIDELITY', CONTENT: 'CONTENT', RAINBOW: 'RAINBOW', FRUIT_SALAD: 'FRUIT_SALAD' };
    // Android's names for the styles (the theme setting, ColorBlendr): SPRITZ is MCU's NEUTRAL, MONOCHROMATIC its MONOCHROME
    const STYLE_VARIANT = { TONAL_SPOT: Variant.TONAL_SPOT, VIBRANT: Variant.VIBRANT, EXPRESSIVE: Variant.EXPRESSIVE, RAINBOW: Variant.RAINBOW, FRUIT_SALAD: Variant.FRUIT_SALAD,
        SPRITZ: Variant.NEUTRAL, NEUTRAL: Variant.NEUTRAL, FIDELITY: Variant.FIDELITY, CONTENT: Variant.CONTENT, MONOCHROMATIC: Variant.MONOCHROME, MONOCHROME: Variant.MONOCHROME };
    const STYLES = ['TONAL_SPOT', 'VIBRANT', 'EXPRESSIVE', 'RAINBOW', 'FRUIT_SALAD', 'SPRITZ', 'FIDELITY', 'CONTENT', 'MONOCHROMATIC'];

    class DynamicScheme {
        constructor(sourceColorHct, variant, isDark, contrastLevel, primaryPalette, secondaryPalette, tertiaryPalette, neutralPalette, neutralVariantPalette) {
            this.sourceColorArgb = sourceColorHct.toInt();
            this.sourceColorHct = sourceColorHct;
            this.variant = variant;
            this.isDark = isDark;
            this.contrastLevel = contrastLevel;
            this.primaryPalette = primaryPalette;
            this.secondaryPalette = secondaryPalette;
            this.tertiaryPalette = tertiaryPalette;
            this.neutralPalette = neutralPalette;
            this.neutralVariantPalette = neutralVariantPalette;
            this.errorPalette = TonalPalette.fromHueAndChroma(25.0, 84.0);
        }
        // Android 14's DynamicScheme.getRotatedHue: a hue strictly between two breakpoints takes the rotation of the lower one
        static getRotatedHue(sourceColorHct, hues, rotations) {
            const sourceHue = sourceColorHct.hue;
            if (rotations.length === 1) return sanitizeDegreesDouble(sourceHue + rotations[0]);
            const size = hues.length;
            for (let i = 0; i <= size - 2; i++) {
                const thisHue = hues[i], nextHue = hues[i + 1];
                if (thisHue < sourceHue && sourceHue < nextHue) return sanitizeDegreesDouble(sourceHue + rotations[i]);
            }
            return sourceHue;
        }
    }
    const VIBRANT_HUES = [0, 41, 61, 101, 131, 181, 251, 301, 360], VIBRANT_SECONDARY = [18, 15, 10, 12, 15, 18, 15, 12, 12], VIBRANT_TERTIARY = [35, 30, 20, 25, 30, 35, 30, 25, 25];
    const EXPRESSIVE_HUES = [0, 21, 51, 121, 151, 191, 271, 321, 360], EXPRESSIVE_SECONDARY = [45, 95, 45, 20, 45, 90, 45, 45, 45], EXPRESSIVE_TERTIARY = [120, 120, 20, 45, 20, 15, 20, 120, 120];
    // the five palettes of a variant for a source colour: the constructors of the nine Scheme*.java files
    function schemePalettes(variant, hct) {
        const h = hct.hue, c = hct.chroma, P = TonalPalette.fromHueAndChroma;
        switch (variant) {
            case Variant.TONAL_SPOT: return [P(h, 36.0), P(h, 16.0), P(sanitizeDegreesDouble(h + 60.0), 24.0), P(h, 6.0), P(h, 8.0)];
            case Variant.VIBRANT: return [P(h, 200.0), P(DynamicScheme.getRotatedHue(hct, VIBRANT_HUES, VIBRANT_SECONDARY), 24.0), P(DynamicScheme.getRotatedHue(hct, VIBRANT_HUES, VIBRANT_TERTIARY), 32.0), P(h, 10.0), P(h, 12.0)];
            case Variant.EXPRESSIVE: return [P(sanitizeDegreesDouble(h + 240.0), 40.0), P(DynamicScheme.getRotatedHue(hct, EXPRESSIVE_HUES, EXPRESSIVE_SECONDARY), 24.0), P(DynamicScheme.getRotatedHue(hct, EXPRESSIVE_HUES, EXPRESSIVE_TERTIARY), 32.0), P(sanitizeDegreesDouble(h + 15.0), 8.0), P(sanitizeDegreesDouble(h + 15.0), 12.0)];
            case Variant.RAINBOW: return [P(h, 48.0), P(h, 16.0), P(sanitizeDegreesDouble(h + 60.0), 24.0), P(h, 0.0), P(h, 0.0)];
            case Variant.FRUIT_SALAD: return [P(sanitizeDegreesDouble(h - 50.0), 48.0), P(sanitizeDegreesDouble(h - 50.0), 36.0), P(h, 36.0), P(h, 10.0), P(h, 16.0)];
            case Variant.NEUTRAL: return [P(h, 12.0), P(h, 8.0), P(h, 16.0), P(h, 2.0), P(h, 2.0)];
            case Variant.FIDELITY: return [P(h, c), P(h, Math.max(c - 32.0, c * 0.5)), TonalPalette.fromHct(DislikeAnalyzer.fixIfDisliked(new TemperatureCache(hct).getComplement())), P(h, c / 8.0), P(h, c / 8.0 + 4.0)];
            case Variant.CONTENT: return [P(h, c), P(h, Math.max(c - 32.0, c * 0.5)), TonalPalette.fromHct(DislikeAnalyzer.fixIfDisliked(new TemperatureCache(hct).getAnalogousColors(3, 6)[2])), P(h, c / 8.0), P(h, c / 8.0 + 4.0)];
            case Variant.MONOCHROME: return [P(h, 0.0), P(h, 0.0), P(h, 0.0), P(h, 0.0), P(h, 0.0)];
            default: throw new Error('Unsupported variant: ' + variant);
        }
    }
    function makeScheme(variant, hct, isDark, contrastLevel) {
        const p = schemePalettes(variant, hct);
        return new DynamicScheme(hct, variant, isDark, contrastLevel, p[0], p[1], p[2], p[3], p[4]);
    }

    // ---------- dynamiccolor/DynamicColor.java, ToneDeltaConstraint.java, TonePolarity.java (Android 14's version) ----------
    const TonePolarity = { DARKER: 'DARKER', LIGHTER: 'LIGHTER', NO_PREFERENCE: 'NO_PREFERENCE' };
    class ToneDeltaConstraint { constructor(delta, keepAway, keepAwayPolarity) { this.delta = delta; this.keepAway = keepAway; this.keepAwayPolarity = keepAwayPolarity; } }
    class DynamicColor {
        constructor(hue, chroma, tone, opacity, background, toneMinContrast, toneMaxContrast, toneDeltaConstraint) {
            this.hue = hue; this.chroma = chroma; this.tone = tone; this.opacity = opacity; this.background = background;
            this.toneMinContrast = toneMinContrast; this.toneMaxContrast = toneMaxContrast; this.toneDeltaConstraint = toneDeltaConstraint;
            this.hctCache = new Map();
        }
        static fromPalette(palette, tone, background, toneDeltaConstraint) {
            background = background || null; toneDeltaConstraint = toneDeltaConstraint || null;
            return new DynamicColor(
                scheme => palette(scheme).hue,
                scheme => palette(scheme).chroma,
                tone, null, background,
                scheme => DynamicColor.toneMinContrastDefault(tone, background, scheme, toneDeltaConstraint),
                scheme => DynamicColor.toneMaxContrastDefault(tone, background, scheme, toneDeltaConstraint),
                toneDeltaConstraint);
        }
        static backgroundHasBackground(background, scheme) {
            return background !== null && background(scheme) !== null && background(scheme).background !== null && background(scheme).background(scheme) !== null;
        }
        static toneMinContrastDefault(tone, background, scheme, toneDeltaConstraint) {
            return DynamicColor.calculateDynamicTone(scheme, tone, c => c.toneMinContrast(scheme),
                (stdRatio, bgTone) => {
                    let answer = tone(scheme);
                    if (stdRatio >= Contrast.RATIO_70) answer = DynamicColor.contrastingTone(bgTone, Contrast.RATIO_45);
                    else if (stdRatio >= Contrast.RATIO_30) answer = DynamicColor.contrastingTone(bgTone, Contrast.RATIO_30);
                    else if (DynamicColor.backgroundHasBackground(background, scheme)) answer = DynamicColor.contrastingTone(bgTone, stdRatio);
                    return answer;
                },
                background, toneDeltaConstraint, null, standardRatio => standardRatio);
        }
        static toneMaxContrastDefault(tone, background, scheme, toneDeltaConstraint) {
            return DynamicColor.calculateDynamicTone(scheme, tone, c => c.toneMaxContrast(scheme),
                (stdRatio, bgTone) => DynamicColor.backgroundHasBackground(background, scheme)
                    ? DynamicColor.contrastingTone(bgTone, Contrast.RATIO_70)
                    : DynamicColor.contrastingTone(bgTone, Math.max(Contrast.RATIO_70, stdRatio)),
                background, toneDeltaConstraint, null, null);
        }
        static calculateDynamicTone(scheme, toneStandard, toneToJudge, desiredTone, background, toneDeltaConstraint, minRatio, maxRatio) {
            const toneStd = toneStandard(scheme);
            let answer = toneStd;
            const bgDynamic = background === null ? null : background(scheme);
            if (bgDynamic === null) return answer;
            const bgToneStd = bgDynamic.tone(scheme);
            const stdRatio = Contrast.ratioOfTones(toneStd, bgToneStd);
            const bgTone = toneToJudge(bgDynamic);
            const myDesiredTone = desiredTone(stdRatio, bgTone);
            const currentRatio = Contrast.ratioOfTones(bgTone, myDesiredTone);
            const minRatioRealized = minRatio === null ? Contrast.RATIO_MIN : minRatio(stdRatio) === null ? Contrast.RATIO_MIN : minRatio(stdRatio);
            const maxRatioRealized = maxRatio === null ? Contrast.RATIO_MAX : maxRatio(stdRatio) === null ? Contrast.RATIO_MAX : maxRatio(stdRatio);
            const desiredRatio = clampDouble(minRatioRealized, maxRatioRealized, currentRatio);
            answer = desiredRatio === currentRatio ? myDesiredTone : DynamicColor.contrastingTone(bgTone, desiredRatio);
            if (bgDynamic.background === null || bgDynamic.background(scheme) === null) answer = DynamicColor.enableLightForeground(answer);
            answer = DynamicColor.ensureToneDelta(answer, toneStd, scheme, toneDeltaConstraint, toneToJudge);
            return answer;
        }
        static ensureToneDelta(tone, toneStandard, scheme, toneDeltaConstraint, toneToDistanceFrom) {
            const constraint = toneDeltaConstraint === null ? null : toneDeltaConstraint(scheme);
            if (constraint === null) return tone;
            const requiredDelta = constraint.delta;
            const keepAwayTone = toneToDistanceFrom(constraint.keepAway);
            const delta = Math.abs(tone - keepAwayTone);
            if (delta >= requiredDelta) return tone;
            switch (constraint.keepAwayPolarity) {
                case TonePolarity.DARKER: return clampDouble(0, 100, keepAwayTone + requiredDelta);
                case TonePolarity.LIGHTER: return clampDouble(0, 100, keepAwayTone - requiredDelta);
                case TonePolarity.NO_PREFERENCE: {
                    const keepAwayToneStandard = constraint.keepAway.tone(scheme);
                    const preferLighten = toneStandard > keepAwayToneStandard;
                    const alterAmount = Math.abs(delta - requiredDelta);
                    const lighten = preferLighten ? tone + alterAmount <= 100.0 : tone < alterAmount;
                    return lighten ? tone + alterAmount : tone - alterAmount;
                }
            }
            return tone;
        }
        static contrastingTone(bgTone, ratio) {
            const lighterTone = Contrast.lighterUnsafe(bgTone, ratio), darkerTone = Contrast.darkerUnsafe(bgTone, ratio);
            const lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone), darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone);
            const preferLighter = DynamicColor.tonePrefersLightForeground(bgTone);
            if (preferLighter) {
                const negligibleDifference = Math.abs(lighterRatio - darkerRatio) < 0.1 && lighterRatio < ratio && darkerRatio < ratio;
                return lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference ? lighterTone : darkerTone;
            }
            return darkerRatio >= ratio || darkerRatio >= lighterRatio ? darkerTone : lighterTone;
        }
        static enableLightForeground(tone) { return DynamicColor.tonePrefersLightForeground(tone) && !DynamicColor.toneAllowsLightForeground(tone) ? 49.0 : tone; }
        static tonePrefersLightForeground(tone) { return Math.round(tone) < 60; }
        static toneAllowsLightForeground(tone) { return Math.round(tone) <= 49; }
        getArgb(scheme) {
            const argb = this.getHct(scheme).toInt();
            if (this.opacity === null) return argb;
            const alpha = clampInt(0, 255, Math.round(this.opacity(scheme) * 255));
            return ((argb & 0x00ffffff) | (alpha << 24)) >>> 0;
        }
        getHct(scheme) {
            const cached = this.hctCache.get(scheme);
            if (cached !== undefined) return cached;
            const answer = Hct.from(this.hue(scheme), this.chroma(scheme), this.getTone(scheme));
            if (this.hctCache.size > 4) this.hctCache.clear();
            this.hctCache.set(scheme, answer);
            return answer;
        }
        getTone(scheme) {
            let answer = this.tone(scheme);
            const decreasingContrast = scheme.contrastLevel < 0.0;
            if (scheme.contrastLevel !== 0.0) {
                const startTone = this.tone(scheme);
                const endTone = decreasingContrast ? this.toneMinContrast(scheme) : this.toneMaxContrast(scheme);
                answer = (endTone - startTone) * Math.abs(scheme.contrastLevel) + startTone;
            }
            const bgDynamicColor = this.background === null ? null : this.background(scheme);
            let minRatio = Contrast.RATIO_MIN, maxRatio = Contrast.RATIO_MAX;
            if (bgDynamicColor !== null) {
                const bgHasBg = bgDynamicColor.background !== null && bgDynamicColor.background(scheme) !== null;
                const standardRatio = Contrast.ratioOfTones(this.tone(scheme), bgDynamicColor.tone(scheme));
                if (decreasingContrast) {
                    const minContrastRatio = Contrast.ratioOfTones(this.toneMinContrast(scheme), bgDynamicColor.toneMinContrast(scheme));
                    minRatio = bgHasBg ? minContrastRatio : 1.0;
                    maxRatio = standardRatio;
                } else {
                    const maxContrastRatio = Contrast.ratioOfTones(this.toneMaxContrast(scheme), bgDynamicColor.toneMaxContrast(scheme));
                    minRatio = bgHasBg ? Math.min(maxContrastRatio, standardRatio) : 1.0;
                    maxRatio = bgHasBg ? Math.max(maxContrastRatio, standardRatio) : 21.0;
                }
            }
            const finalMinRatio = minRatio, finalMaxRatio = maxRatio, finalAnswer = answer;
            return DynamicColor.calculateDynamicTone(scheme, this.tone, dynamicColor => dynamicColor.getTone(scheme), () => finalAnswer,
                () => bgDynamicColor, this.toneDeltaConstraint, () => finalMinRatio, () => finalMaxRatio);
        }
    }

    // ---------- dynamiccolor/MaterialDynamicColors.java (Android 14) ----------
    const CONTAINER_ACCENT_TONE_DELTA = 15.0;
    function isFidelity(s) { return s.variant === Variant.FIDELITY || s.variant === Variant.CONTENT; }
    function isMonochrome(s) { return s.variant === Variant.MONOCHROME; }
    function viewingConditionsForAlbers(scheme) { return ViewingConditions.defaultWithBackgroundLstar(scheme.isDark ? 30.0 : 80.0); }
    function findDesiredChromaByTone(hue, chroma, tone, byDecreasingTone) {
        let answer = tone;
        let closestToChroma = Hct.from(hue, chroma, tone);
        if (closestToChroma.chroma < chroma) {
            let chromaPeak = closestToChroma.chroma;
            while (closestToChroma.chroma < chroma) {
                answer += byDecreasingTone ? -1.0 : 1.0;
                const potentialSolution = Hct.from(hue, chroma, answer);
                if (chromaPeak > potentialSolution.chroma) break;
                if (Math.abs(potentialSolution.chroma - chroma) < 0.4) break;
                const potentialDelta = Math.abs(potentialSolution.chroma - chroma), currentDelta = Math.abs(closestToChroma.chroma - chroma);
                if (potentialDelta < currentDelta) closestToChroma = potentialSolution;
                chromaPeak = Math.max(chromaPeak, potentialSolution.chroma);
            }
        }
        return answer;
    }
    function performAlbers(prealbers, scheme) {
        const albersd = prealbers.inViewingConditions(viewingConditionsForAlbers(scheme));
        if (DynamicColor.tonePrefersLightForeground(prealbers.tone) && !DynamicColor.toneAllowsLightForeground(albersd.tone)) return DynamicColor.enableLightForeground(prealbers.tone);
        return DynamicColor.enableLightForeground(albersd.tone);
    }
    // One object per role (the Java makes a new one per call; nothing depends on identity). The roles refer to each other by name
    // through MDC so that a role's background can be defined before or after it.
    const MDC = {};
    const def = (name, make) => { let dc = null; MDC[name] = () => dc || (dc = make()); };
    const FP = (palette, tone, background, tdc) => DynamicColor.fromPalette(palette, tone, background || null, tdc || null);
    const highestSurface = s => s.isDark ? MDC.surfaceBright() : MDC.surfaceDim();
    def('primaryPaletteKeyColor', () => FP(s => s.primaryPalette, s => s.primaryPalette.keyColor.tone));
    def('secondaryPaletteKeyColor', () => FP(s => s.secondaryPalette, s => s.secondaryPalette.keyColor.tone));
    def('tertiaryPaletteKeyColor', () => FP(s => s.tertiaryPalette, s => s.tertiaryPalette.keyColor.tone));
    def('neutralPaletteKeyColor', () => FP(s => s.neutralPalette, s => s.neutralPalette.keyColor.tone));
    def('neutralVariantPaletteKeyColor', () => FP(s => s.neutralVariantPalette, s => s.neutralVariantPalette.keyColor.tone));
    def('background', () => FP(s => s.neutralPalette, s => s.isDark ? 6.0 : 98.0));
    def('onBackground', () => FP(s => s.neutralPalette, s => s.isDark ? 90.0 : 10.0, s => MDC.background()));
    def('surface', () => FP(s => s.neutralPalette, s => s.isDark ? 6.0 : 98.0));
    def('inverseSurface', () => FP(s => s.neutralPalette, s => s.isDark ? 90.0 : 20.0));
    def('surfaceBright', () => FP(s => s.neutralPalette, s => s.isDark ? 24.0 : 98.0));
    def('surfaceDim', () => FP(s => s.neutralPalette, s => s.isDark ? 6.0 : 87.0));
    def('surfaceContainerLowest', () => FP(s => s.neutralPalette, s => s.isDark ? 4.0 : 100.0));
    def('surfaceContainerLow', () => FP(s => s.neutralPalette, s => s.isDark ? 10.0 : 96.0));
    def('surfaceContainer', () => FP(s => s.neutralPalette, s => s.isDark ? 12.0 : 94.0));
    def('surfaceContainerHigh', () => FP(s => s.neutralPalette, s => s.isDark ? 17.0 : 92.0));
    def('surfaceContainerHighest', () => FP(s => s.neutralPalette, s => s.isDark ? 22.0 : 90.0));
    def('onSurface', () => FP(s => s.neutralPalette, s => s.isDark ? 90.0 : 10.0, highestSurface));
    def('inverseOnSurface', () => FP(s => s.neutralPalette, s => s.isDark ? 20.0 : 95.0, s => MDC.inverseSurface()));
    def('surfaceVariant', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 30.0 : 90.0));
    def('onSurfaceVariant', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 80.0 : 30.0, s => MDC.surfaceVariant()));
    def('outline', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 60.0 : 50.0, highestSurface));
    def('outlineVariant', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 30.0 : 80.0, highestSurface));
    def('shadow', () => FP(s => s.neutralPalette, s => 0.0));
    def('scrim', () => FP(s => s.neutralPalette, s => 0.0));
    def('surfaceTint', () => FP(s => s.primaryPalette, s => s.isDark ? 80.0 : 40.0));
    def('primaryContainer', () => FP(s => s.primaryPalette, s => {
        if (isFidelity(s)) return performAlbers(s.sourceColorHct, s);
        if (isMonochrome(s)) return s.isDark ? 85.0 : 25.0;
        return s.isDark ? 30.0 : 90.0;
    }, highestSurface));
    def('onPrimaryContainer', () => FP(s => s.primaryPalette, s => {
        if (isFidelity(s)) return DynamicColor.contrastingTone(MDC.primaryContainer().tone(s), 4.5);
        if (isMonochrome(s)) return s.isDark ? 0.0 : 100.0;
        return s.isDark ? 90.0 : 10.0;
    }, s => MDC.primaryContainer(), null));
    def('primary', () => FP(s => s.primaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 100.0 : 0.0;
        return s.isDark ? 80.0 : 40.0;
    }, highestSurface, s => new ToneDeltaConstraint(CONTAINER_ACCENT_TONE_DELTA, MDC.primaryContainer(), s.isDark ? TonePolarity.DARKER : TonePolarity.LIGHTER)));
    def('inversePrimary', () => FP(s => s.primaryPalette, s => s.isDark ? 40.0 : 80.0, s => MDC.inverseSurface()));
    def('onPrimary', () => FP(s => s.primaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 10.0 : 90.0;
        return s.isDark ? 20.0 : 100.0;
    }, s => MDC.primary()));
    def('secondaryContainer', () => FP(s => s.secondaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 30.0 : 85.0;
        const initialTone = s.isDark ? 30.0 : 90.0;
        if (!isFidelity(s)) return initialTone;
        let answer = findDesiredChromaByTone(s.secondaryPalette.hue, s.secondaryPalette.chroma, initialTone, !s.isDark);
        answer = performAlbers(s.secondaryPalette.getHct(answer), s);
        return answer;
    }, highestSurface));
    def('onSecondaryContainer', () => FP(s => s.secondaryPalette, s => {
        if (!isFidelity(s)) return s.isDark ? 90.0 : 10.0;
        return DynamicColor.contrastingTone(MDC.secondaryContainer().tone(s), 4.5);
    }, s => MDC.secondaryContainer()));
    def('secondary', () => FP(s => s.secondaryPalette, s => s.isDark ? 80.0 : 40.0, highestSurface,
        s => new ToneDeltaConstraint(CONTAINER_ACCENT_TONE_DELTA, MDC.secondaryContainer(), s.isDark ? TonePolarity.DARKER : TonePolarity.LIGHTER)));
    def('onSecondary', () => FP(s => s.secondaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 10.0 : 100.0;
        return s.isDark ? 20.0 : 100.0;
    }, s => MDC.secondary()));
    def('tertiaryContainer', () => FP(s => s.tertiaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 60.0 : 49.0;
        if (!isFidelity(s)) return s.isDark ? 30.0 : 90.0;
        const albersTone = performAlbers(s.tertiaryPalette.getHct(s.sourceColorHct.tone), s);
        const proposedHct = s.tertiaryPalette.getHct(albersTone);
        return DislikeAnalyzer.fixIfDisliked(proposedHct).tone;
    }, highestSurface));
    def('onTertiaryContainer', () => FP(s => s.tertiaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 0.0 : 100.0;
        if (!isFidelity(s)) return s.isDark ? 90.0 : 10.0;
        return DynamicColor.contrastingTone(MDC.tertiaryContainer().tone(s), 4.5);
    }, s => MDC.tertiaryContainer()));
    def('tertiary', () => FP(s => s.tertiaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 90.0 : 25.0;
        return s.isDark ? 80.0 : 40.0;
    }, highestSurface, s => new ToneDeltaConstraint(CONTAINER_ACCENT_TONE_DELTA, MDC.tertiaryContainer(), s.isDark ? TonePolarity.DARKER : TonePolarity.LIGHTER)));
    def('onTertiary', () => FP(s => s.tertiaryPalette, s => {
        if (isMonochrome(s)) return s.isDark ? 10.0 : 90.0;
        return s.isDark ? 20.0 : 100.0;
    }, s => MDC.tertiary()));
    def('errorContainer', () => FP(s => s.errorPalette, s => s.isDark ? 30.0 : 90.0, highestSurface));
    def('onErrorContainer', () => FP(s => s.errorPalette, s => s.isDark ? 90.0 : 10.0, s => MDC.errorContainer()));
    def('error', () => FP(s => s.errorPalette, s => s.isDark ? 80.0 : 40.0, highestSurface,
        s => new ToneDeltaConstraint(CONTAINER_ACCENT_TONE_DELTA, MDC.errorContainer(), s.isDark ? TonePolarity.DARKER : TonePolarity.LIGHTER)));
    def('onError', () => FP(s => s.errorPalette, s => s.isDark ? 20.0 : 100.0, s => MDC.error()));
    def('primaryFixed', () => FP(s => s.primaryPalette, s => isMonochrome(s) ? 40.0 : 90.0, highestSurface));
    def('primaryFixedDim', () => FP(s => s.primaryPalette, s => isMonochrome(s) ? 30.0 : 80.0, highestSurface));
    def('onPrimaryFixed', () => FP(s => s.primaryPalette, s => isMonochrome(s) ? 100.0 : 10.0, s => MDC.primaryFixedDim()));
    def('onPrimaryFixedVariant', () => FP(s => s.primaryPalette, s => isMonochrome(s) ? 90.0 : 30.0, s => MDC.primaryFixedDim()));
    def('secondaryFixed', () => FP(s => s.secondaryPalette, s => isMonochrome(s) ? 80.0 : 90.0, highestSurface));
    def('secondaryFixedDim', () => FP(s => s.secondaryPalette, s => isMonochrome(s) ? 70.0 : 80.0, highestSurface));
    def('onSecondaryFixed', () => FP(s => s.secondaryPalette, s => 10.0, s => MDC.secondaryFixedDim()));
    def('onSecondaryFixedVariant', () => FP(s => s.secondaryPalette, s => isMonochrome(s) ? 25.0 : 30.0, s => MDC.secondaryFixedDim()));
    def('tertiaryFixed', () => FP(s => s.tertiaryPalette, s => isMonochrome(s) ? 40.0 : 90.0, highestSurface));
    def('tertiaryFixedDim', () => FP(s => s.tertiaryPalette, s => isMonochrome(s) ? 30.0 : 80.0, highestSurface));
    def('onTertiaryFixed', () => FP(s => s.tertiaryPalette, s => isMonochrome(s) ? 100.0 : 10.0, s => MDC.tertiaryFixedDim()));
    def('onTertiaryFixedVariant', () => FP(s => s.tertiaryPalette, s => isMonochrome(s) ? 90.0 : 30.0, s => MDC.tertiaryFixedDim()));
    // the colours Android had before Android 14 and MDC controls still use: no background, so no contrast adjustment
    def('controlActivated', () => FP(s => s.primaryPalette, s => s.isDark ? 30.0 : 90.0, null));
    def('controlNormal', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 80.0 : 30.0));
    def('controlHighlight', () => {
        const tone = s => s.isDark ? 100.0 : 0.0;
        return new DynamicColor(s => 0.0, s => 0.0, tone, s => s.isDark ? 0.20 : 0.12, null,
            scheme => DynamicColor.toneMinContrastDefault(tone, null, scheme, null),
            scheme => DynamicColor.toneMaxContrastDefault(tone, null, scheme, null), null);
    });
    def('textPrimaryInverse', () => FP(s => s.neutralPalette, s => s.isDark ? 10.0 : 90.0));
    def('textSecondaryAndTertiaryInverse', () => FP(s => s.neutralVariantPalette, s => s.isDark ? 30.0 : 80.0));
    def('textPrimaryInverseDisableOnly', () => FP(s => s.neutralPalette, s => s.isDark ? 10.0 : 90.0));
    def('textSecondaryAndTertiaryInverseDisabled', () => FP(s => s.neutralPalette, s => s.isDark ? 10.0 : 90.0));
    def('textHintInverse', () => FP(s => s.neutralPalette, s => s.isDark ? 10.0 : 90.0));

    // ---------- theme/DynamicColors.kt: the 44 roles and 12 fixed colours Android 14 writes, in its order ----------
    const ALL_DYNAMIC_COLORS_MAPPED = [
        ['primary_container', 'primaryContainer'], ['on_primary_container', 'onPrimaryContainer'], ['primary', 'primary'], ['on_primary', 'onPrimary'],
        ['secondary_container', 'secondaryContainer'], ['on_secondary_container', 'onSecondaryContainer'], ['secondary', 'secondary'], ['on_secondary', 'onSecondary'],
        ['tertiary_container', 'tertiaryContainer'], ['on_tertiary_container', 'onTertiaryContainer'], ['tertiary', 'tertiary'], ['on_tertiary', 'onTertiary'],
        ['background', 'background'], ['on_background', 'onBackground'], ['surface', 'surface'], ['on_surface', 'onSurface'],
        ['surface_container_low', 'surfaceContainerLow'], ['surface_container_lowest', 'surfaceContainerLowest'], ['surface_container', 'surfaceContainer'],
        ['surface_container_high', 'surfaceContainerHigh'], ['surface_container_highest', 'surfaceContainerHighest'], ['surface_bright', 'surfaceBright'],
        ['surface_dim', 'surfaceDim'], ['surface_variant', 'surfaceVariant'], ['on_surface_variant', 'onSurfaceVariant'], ['outline', 'outline'],
        ['outline_variant', 'outlineVariant'], ['error', 'error'], ['on_error', 'onError'], ['error_container', 'errorContainer'], ['on_error_container', 'onErrorContainer'],
        ['control_activated', 'controlActivated'], ['control_normal', 'controlNormal'], ['control_highlight', 'controlHighlight'],
        ['text_primary_inverse', 'textPrimaryInverse'], ['text_secondary_and_tertiary_inverse', 'textSecondaryAndTertiaryInverse'],
        ['text_primary_inverse_disable_only', 'textPrimaryInverseDisableOnly'], ['text_secondary_and_tertiary_inverse_disabled', 'textSecondaryAndTertiaryInverseDisabled'],
        ['text_hint_inverse', 'textHintInverse'], ['palette_key_color_primary', 'primaryPaletteKeyColor'], ['palette_key_color_secondary', 'secondaryPaletteKeyColor'],
        ['palette_key_color_tertiary', 'tertiaryPaletteKeyColor'], ['palette_key_color_neutral', 'neutralPaletteKeyColor'], ['palette_key_color_neutral_variant', 'neutralVariantPaletteKeyColor']];
    const FIXED_COLORS_MAPPED = [
        ['primary_fixed', 'primaryFixed'], ['primary_fixed_dim', 'primaryFixedDim'], ['on_primary_fixed', 'onPrimaryFixed'], ['on_primary_fixed_variant', 'onPrimaryFixedVariant'],
        ['secondary_fixed', 'secondaryFixed'], ['secondary_fixed_dim', 'secondaryFixedDim'], ['on_secondary_fixed', 'onSecondaryFixed'], ['on_secondary_fixed_variant', 'onSecondaryFixedVariant'],
        ['tertiary_fixed', 'tertiaryFixed'], ['tertiary_fixed_dim', 'tertiaryFixedDim'], ['on_tertiary_fixed', 'onTertiaryFixed'], ['on_tertiary_fixed_variant', 'onTertiaryFixedVariant']];

    // ---------- Shades.java and ColorScheme.kt: the 13 shades of a palette row, the seed rule ----------
    const GOOGLE_BLUE = 0xFF1B6EF3, MIN_CHROMA = 5;
    const SHADE_KEYS = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];
    const PALETTE_ROWS = [['accent1', 'primaryPalette'], ['accent2', 'secondaryPalette'], ['accent3', 'tertiaryPalette'], ['neutral1', 'neutralPalette'], ['neutral2', 'neutralVariantPalette']];
    // shade 0 = tone 100, 10 and 50 = tones 99 and 95 at a chroma of at most 40 (blues and yellows reach far more up there), 500 = tone 49.6
    // (the smallest tone with a 4.5 contrast against white), every other shade s = tone 100 - s / 10
    function shadeTone(shade) { return shade === 500 ? 49.6 : 100 - shade / 10; }
    function shadesOf(palette) {
        return SHADE_KEYS.map(shade => {
            const chroma = shade === 10 || shade === 50 ? Math.min(40.0, palette.chroma) : palette.chroma;
            return Hct.from(palette.hue, chroma, shadeTone(shade)).toInt();
        });
    }
    function variantOf(style) {
        const v = STYLE_VARIANT[String(style || '').toUpperCase()];
        if (!v) throw new Error('Unknown style: ' + style);
        return v;
    }
    // 0xAARRGGBB number, 'RRGGBB', '#RRGGBB', 'AARRGGBB' or '0xAARRGGBB' -> an opaque unsigned argb number; anything else -> 0
    function parseArgb(color) {
        if (typeof color === 'number') return Number.isFinite(color) ? (color >>> 0) | 0xFF000000 : 0;
        const m = /^(?:#|0x)?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.exec(String(color || '').trim());
        if (!m) return 0;
        return (parseInt(m[1].slice(-6), 16) | 0xFF000000) >>> 0;
    }
    function hex6(argb) { return (argb & 0xFFFFFF).toString(16).toUpperCase().padStart(6, '0'); }
    function hex8(argb) { return '0x' + (argb >>> 0).toString(16).toUpperCase().padStart(8, '0'); }
    function hct(color) { const h = Hct.fromInt(parseArgb(color)); return { hue: h.hue, chroma: h.chroma, tone: h.tone }; }
    // the seed a style uses: Fidelity and Content keep what they are given (that is their point), Monochrome does not care; the other
    // styles turn a seed with chroma under 5 (a grey) into Google blue, as Android's ColorScheme does. No colour at all (an unparsable
    // string, or the number 0 = Color.TRANSPARENT, which ColorScheme also maps) is Google blue. ColorScheme.kt exempts only CONTENT
    // from the grey rule; its style table has no FIDELITY, so the brief's choice (Fidelity keeps the seed too) is followed here.
    function seed(style, color) {
        const variant = variantOf(style), argb = parseArgb(color);
        if (!argb || color === 0) return GOOGLE_BLUE;
        if (variant === Variant.FIDELITY || variant === Variant.CONTENT || variant === Variant.MONOCHROME) return argb;
        return Hct.fromInt(argb).chroma < MIN_CHROMA ? GOOGLE_BLUE : argb;
    }
    function scheme(style, color, isDark) { return makeScheme(variantOf(style), Hct.fromInt(seed(style, color)), !!isDark, 0.0); }
    function palettes(style, color) {
        const s = scheme(style, color, false), out = { seed: hex6(s.sourceColorArgb), style: String(style).toUpperCase() };
        for (const [row, field] of PALETTE_ROWS) {
            const p = s[field];
            out[row] = { hue: p.hue, chroma: p.chroma, key: hex6(p.keyColor.toInt()), shades: shadesOf(p).map(hex6) };
        }
        return out;
    }
    function roles(style, color, isDark) {
        const s = scheme(style, color, isDark);
        return ALL_DYNAMIC_COLORS_MAPPED.map(([name, role]) => ({ name, argb: hex8(MDC[role]().getArgb(s)) }));
    }
    function entries(style, color) {
        const light = scheme(style, color, false), dark = scheme(style, color, true), out = [];
        for (const [row, field] of PALETTE_ROWS) {
            const shades = shadesOf(light[field]);
            SHADE_KEYS.forEach((shade, i) => out.push({ resource: 'android:color/system_' + row + '_' + shade, argb: hex8(shades[i]) }));
        }
        for (const s of [dark, light]) {
            const suffix = s.isDark ? 'dark' : 'light';
            for (const [name, role] of ALL_DYNAMIC_COLORS_MAPPED) out.push({ resource: 'android:color/system_' + name + '_' + suffix, argb: hex8(MDC[role]().getArgb(s)) });
        }
        for (const [name, role] of FIXED_COLORS_MAPPED) out.push({ resource: 'android:color/system_' + name, argb: hex8(MDC[role]().getArgb(light)) });
        return out;
    }

    return {
        STYLES, GOOGLE_BLUE: hex6(GOOGLE_BLUE), MIN_CHROMA, SHADE_KEYS,
        hct, seed: (style, color) => hex6(seed(style, color)), palettes, roles, entries, scheme,
        lib: { Hct, Cam16, HctSolver, ViewingConditions, Contrast, TonalPalette, TemperatureCache, DislikeAnalyzer, DynamicScheme, DynamicColor, Variant, MDC,
            ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED, schemePalettes, shadesOf, shadeTone, parseArgb, hex6, hex8, variantOf }
    };
});
