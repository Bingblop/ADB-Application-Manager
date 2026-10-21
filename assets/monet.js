/*
 * monet.js: the colour maths of Android's Material You engine and of Google's newer Material colour library, so this app can compute
 * the theme colours that Android itself does not offer as a setting (the Fidelity, Content and Monochrome styles; the 2025 and 2026
 * colour specs; the CMF style) and write them with root (the Overlays tab, Root-only theme controls). The app's own Settings theme
 * (psPalettes, psScheme in index.html) does not use this file.
 *
 * Copyright 2021-2026 Google LLC
 * Copyright (C) 2019-2024 The Android Open Source Project
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
 * Two parts. Part 1 is Android 14's engine, for all nine styles at spec 2021. Part 2 is Google's Material Color Utilities at main: the
 * 2021, 2025 and 2026 colour specs and the CMF style. Which part computes a result depends on the profile, the Android build the
 * colours are for (see "Profiles" below). P14, and no profile: part 2 gets a style only where Google's rule gives it a spec other than
 * 2021 (Tonal Spot, Vibrant, Expressive and Spritz at 2025 or 2026, CMF at 2026), so every P14 result at spec 2021 is part 1's, the
 * same as before part 2 was added. P15Q1, P15Q2 and P16 (Android 15 QPR1, 15 QPR2 and 16.0): part 2 at every spec, 2021 included.
 * P15 (Android 15.0): as P15Q1, with the two rules of AOSP's libmonet android15-release that differ from it at 2021 (the profile below).
 * P16Q1 and P16Q2 (Android 16 QPR1 and QPR2): part 2 with the rules of AOSP's libmonet on those branches, at 2025 for every style
 * (2021 when asked for). P17 (Android 17): part 2 with the rules of AOSP's libmonet android17-release, at the spec asked for with
 * upstream's fallback (2026 gives Tonal Spot, Vibrant, Expressive and Spritz 2025, CMF 2026, the others 2021). Android 14, 15 and 16.0
 * build their own theme at spec 2021 (15.0 with an older libmonet: the P15 profile below), Android 16 QPR1 and QPR2 at 2025 with
 * changes of their own (the P16Q1 and P16Q2 profiles below), and Android 17 at 2026 (the P17 profile below; read in the AOSP sources
 * and computed with them compiled, not run on a phone).
 *
 * Part 1, provenance (what was ported from where; the Java is AOSP frameworks/base at refs/heads/android14-release, the TypeScript is
 * Google's npm package @material/material-color-utilities 0.4.0; both Apache-2.0):
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
 * Part 2, provenance: Google's Material Color Utilities on GitHub (github.com/material-foundation/material-color-utilities), branch
 * main at commit bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3 (2026-10-19), Apache-2.0, from typescript/:
 *   dynamiccolor/variant.ts (CMF), hct/cam16.ts (the hue and chroma of Cam16.fromInt), hct/hct.ts, contrast/contrast.ts,
 *   dislike/dislike_analyzer.ts, palettes/tonal_palette.ts (with its KeyColor), dynamiccolor/contrast_curve.ts, tone_delta_pair.ts,
 *   dynamic_color.ts (DynamicColor, extendSpecVersion, ColorCalculationDelegateImpl2021 and ColorCalculationDelegateImpl2025),
 *   color_spec_2021.ts (spec 2021, and the base class 2025 extends), color_spec_2025.ts, color_spec_2026.ts,
 *   material_dynamic_colors.ts (its one ColorSpecDelegateImpl2026), dynamic_scheme.ts (maybeFallbackSpecVersion, getPiecewiseHue,
 *   getRotatedHue, the palette delegates 2021 and 2025, the 2021 one with its Fidelity and Content cases: the source colour's own
 *   chroma, and a tertiary palette from the temperature cache, fixed by DislikeAnalyzer), temperature/temperature_cache.ts
 *   (TemperatureCache), scheme/scheme_cmf.ts, and
 *   scheme_tonal_spot.ts, scheme_vibrant.ts, scheme_expressive.ts, scheme_neutral.ts, scheme_rainbow.ts, scheme_fruit_salad.ts,
 *   scheme_fidelity.ts, scheme_content.ts and scheme_monochrome.ts, each the DynamicScheme constructor with its variant (schemeOf).
 *   Shared with part 1 because they are equal to main: the colour and maths utilities, ViewingConditions.DEFAULT (every field) and
 *   HctSolver (its three tables as text; solveToInt for 2,074,883 inputs, random and on the palettes' grid, no difference). Kept
 *   apart because they differ: Cam16 (main's chroma takes Math.sqrt(a * a + b * b), part 1 Java's hypot: the chroma differs by up to
 *   7.1e-14 for 3,914,617 of the 16,777,216 colours, and SchemeCmf's palettes take the seed's chroma as it is), Contrast (main
 *   clamps the tones in ratioOfTones; a 'farther' tone pair can ask for a tone outside 0..100), TonalPalette (key colour by
 *   bisection, tone 99 of a yellow averaged), DislikeAnalyzer (65, not 70), TemperatureCache (the same algorithm as part 1's, on
 *   this part's Hct; its raw temperature takes Math.sqrt(a * a + b * b) where part 1 Java's takes hypot) and getRotatedHue (a hue
 *   on a breakpoint takes that interval's rotation; part 1's only takes hues strictly between two breakpoints).
 *   Left out: the watch platform (every platform === 'watch' branch, and mainTone, dimTone, containerTone and the 2025
 *   findDesiredChromaByTone, which only the watch uses); a second source colour (the API passes one; color_spec_2026.ts's and
 *   SchemeCmf's reads of it fall back to the source colour, as upstream's do with one colour); color_spec.ts (the interfaces),
 *   blend/, quantize/, score/, palettes/core_palette.ts and core_palettes.ts, scheme/scheme.ts and scheme_android.ts, utils/
 *   image_utils.ts, string_utils.ts, theme_utils.ts and test_utils.ts, DynamicScheme's per-role getters and toString, and the
 *   deprecated static members of MaterialDynamicColors. The API passes the contrast level it is given (opts.contrast, 0 by default).
 *   The dim roles (primary_dim and the like) are ported though Android does not write them, so the oracle check covers every role.
 *   Android's own roles that the library does not have (control_activated, control_normal, control_highlight, text_primary_inverse,
 *   text_secondary_and_tertiary_inverse, text_primary_inverse_disable_only, text_secondary_and_tertiary_inverse_disabled,
 *   text_hint_inverse) keep part 1's Android 14 definition, evaluated on part 2's palettes (libmonet on Android 15 and 16 defines
 *   them the same way at 2021: the profile oracle below). The 65 shades are taken from the scheme of the mode asked for (opts.dark),
 *   as Android 15's and 16's ThemeOverlayController takes them from the mode the phone is in; for P14 with part 1's rule (Android
 *   14's Shades.of), for P15, P15Q1, P15Q2 and P16 with Android 15's and 16's (below).
 *   What the oracle run showed (the library at that commit, bundled with esbuild and run in node; seeds 6750A4 1B6EF3 B5651D 00FF00
 *   808080 FFDE3F, the grey taken to Google blue as the API does; Tonal Spot, Vibrant, Expressive and Spritz at 2025, and at 2026,
 *   which gives them 2025; CMF at 2026; light and dark): every palette's hue, chroma and key colour (error included), every role
 *   upstream defines (argb and tone, the dims and palette key colours included) and the fixed colours agree bit for bit; so do
 *   roles() and entries() for every upstream role, the 8 Android roles against Android 14's palette tones on the library's palettes,
 *   and the 65 shades for dark and light. Hct.fromInt agrees for all 16,777,216 colours, TonalPalette on a hue x chroma grid
 *   (1,384,320 values), Contrast on 1,000,000 random calls; part 2 at spec 2021 (seven variants) and at contrast levels -1, -0.5, 0.5
 *   and 1 agrees too. Fidelity and Content at spec 2021 (ported with the profiles), against the same bundle: every palette (hue and
 *   chroma exactly, key colour, tones 0 to 100; error included) and the 59 roles both define, for the six seeds above at contrast 0,
 *   0.5 and 1 and 56 more seeds (black, white, a grey, primaries, yellows, 40 random) at -1, -0.5, 0, 0.25, 0.5, 0.75 and 1, light
 *   and dark: no difference in 9,840 palettes, 993,840 tones and 96,760 role values.
 *   With no opts, with spec 2021, and at 2025 and 2026 for Rainbow, Fruit Salad, Fidelity, Content and Monochrome, every entries(),
 *   palettes(), roles() and seed() result for the nine older styles and the six seeds is the same as before part 2, byte for byte.
 *   No phone this file serves computes 2025, 2026 or CMF colours itself, so there is nothing on a phone to compare them with; none of
 *   it was run on a device.
 *
 * Profiles P15Q1, P15Q2 and P16, provenance (AOSP, Apache-2.0, read at the branch heads refs/heads/android15-qpr1-release,
 * android15-qpr2-release and android16-release): what SystemUI writes there.
 *   frameworks/base packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java: createOverlays,
 *     createNeutralOverlay, createAccentOverlay, createDynamicOverlay, assignTonalPaletteToOverlay and assignColorsToOverlay (the
 *     same on the three branches): the neutral overlay (neutral1, neutral2) and the accent overlay (accent1-3) from the scheme of the
 *     mode the phone is in, each role as _light (light scheme) then _dark (dark scheme), the fixed colours unsuffixed from the light
 *     scheme, both schemes at the phone's contrast (UiModeManager.getContrast()), then SystemUI's custom colours.
 *   frameworks/libs/systemui monet/src/com/android/systemui/monet/: ColorScheme.java (the scheme of a style; the seed rule: Google
 *     blue for no colour and, except for Content, for a chroma under 5), TonalPalette.java (getAtTone: shade s is libmonet's
 *     tone((int) ((1000 - s) / 10)), so shade 500 is tone 50 and nothing caps the chroma; allShadesMapped, a HashMap of SHADE_KEYS made
 *     by Collectors.toMap, gives the write order 0, 800, 100, 900, 200, 1000, 10, 300, 400, 50, 500, 600, 700), DynamicColors.java
 *     (15 QPR1: Android 14's 44 roles and 12 fixed colours in Android 14's order; 15 QPR2 and 16.0, the same file: 50 roles, the 44
 *     plus inverse_surface, inverse_on_surface, inverse_primary, shadow, scrim and surface_tint, named by generateSysUINames
 *     (palette_key_color moved to the front) and sorted by name, and the 12 fixed colours sorted by name).
 *   external/libmonet (android15-qpr2-release, the same tree as android15-qpr1-release, and android16-release): the maths those
 *     call, spec 2021 for every style; part 2 computes it. Where they differ from part 2: libmonet's TonalPalette does not average
 *     tone 99 of a yellow (the shades here take Hct.from directly, and no role at spec 2021 asked a yellow palette for tone 99 in
 *     153,000 plans: 3,400 seeds, the nine styles, five contrast levels); its getRotatedHue skips a hue exactly on a breakpoint (no
 *     sRGB colour of chroma 5 or more has a hue within 1e-9 of one, the closest is 9.3e-7 away); its Cam16 takes Math.toDegrees and
 *     Math.hypot (hue and chroma can differ from part 2's in the last bits). The oracle found no output any of these changes.
 *   Not written: SystemUI's custom colours (their names and maths differ per branch). Flags.COLOR_FIDELITY, which SystemUI passes
 *     to MaterialDynamicColors, is taken as off (an unreleasedFlag; its value on shipping builds was not checked). The seed: the
 *     API's rule (seed() below) keeps a grey for Monochrome where SystemUI takes Google blue; a chroma-0 scheme gives the same colours
 *     for either (the oracle compares SystemUI's choice of seed with the API's).
 *   What the oracle run showed: SystemUI's own ColorScheme.java, TonalPalette.java and DynamicColors.java of each branch, compiled
 *   with that branch's libmonet (stubs only for Android classes the computation does not reach) and driven by the six functions
 *   above, transcribed, against entries(style, seed, { spec: '2021', dark, profile, contrast }): the seven styles SystemUI builds
 *   (Tonal Spot, Vibrant, Expressive, Spritz, Rainbow, Fruit Salad, Monochrome), seeds 6750A4 1B6EF3 B5651D 00FF00 808080 FFDE3F,
 *   contrast 0, 0.5 and 1, light and dark: the same resources in the same order with the same values (P15Q1: 252 plans of 165
 *   entries; P15Q2 and P16: 252 plans of 177), and the same for Content (through SystemUI's ColorScheme) and Fidelity (libmonet's
 *   SchemeFidelity with SystemUI's write order); 56 more seeds at contrast -1, -0.5, 0, 0.25, 0.5, 0.75 and 1 (7,056 plans per
 *   profile, 3,662,064 entries in all): no difference. P16's write order equals the list made by hand from the sources (177).
 *   Profile P14 at contrast 0.5 and 1 (part 1, new with the contrast option): the 44 roles and 12 fixed colours equal Android 14.0's
 *   own monet Java (android14-release) compiled and run with the seed this file uses (seed() below: Google blue for a grey in the six
 *   list styles), for the nine styles and the six seeds, light and dark (32,400 values). Android 14's SystemUI itself builds its roles
 *   from the raw seed (ThemeOverlayController.dynamicSchemeFromStyle takes the colour as it is; only its ColorScheme, which gives the
 *   palettes, takes Google blue), so for a grey seed in Tonal Spot, Vibrant, Expressive, Spritz, Rainbow and Fruit Salad, P14's roles
 *   are not what Android 14 computes, at any contrast (808080, Tonal Spot, light, contrast 0: primary 0xFF006874 there, 0xFF475D92
 *   here). That was so before the profiles too and is not changed by them.
 *   Android 14's quarterly updates (android14-qpr1, -qpr2 and -qpr3-release) compute the same 44 roles and 12 fixed colours with an
 *   early libmonet (com.google.ux.material.libmonet, through DynamicColors.kt's MaterialDynamicColors) instead of SystemUI's own
 *   monet; their palettes and shades are 14.0's. No framework colour name tells 14.0 from QPR1 or QPR2 (QPR3 adds system_error_*), so
 *   P14 is 14.0's maths on every Android 14 build. Measured against libmonet android14-qpr3-release compiled and run (the six seeds,
 *   light and dark): at contrast 0 Fidelity and Content differ in 58-59 of 672 role and fixed values (the on-colours and
 *   outline_light), the seven styles SystemUI accepts (Monochrome among them) in outline_light only (6 of 672); at contrast 0.5 and 1
 *   about 40 % differ. Not handled yet.
 *
 * Profile P15, provenance (AOSP, Apache-2.0; libmonet Copyright 2021-2023 Google LLC, SystemUI Copyright (C) 2019-2024 The Android
 * Open Source Project, both covered by the notices at the top of this file): what SystemUI writes on Android 15.0 (API 35), read at the
 * branch heads refs/heads/android15-release.
 *   frameworks/base packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java (createOverlays, createNeutralOverlay,
 *     createAccentOverlay, createDynamicOverlay, assignTonalPaletteToOverlay, assignColorsToOverlay) and frameworks/libs/systemui
 *     monet/src/com/android/systemui/monet/ (ColorScheme.java, TonalPalette.java, DynamicColors.java): as on 15 QPR1 (P15Q1, above)
 *     for everything this file writes. TonalPalette.java is byte for byte QPR1's; ColorScheme.java differs only in its hue-distance
 *     helper and DynamicColors.java only in the name of a custom colour (on_theme_app_ring, theme_notif on QPR1), neither of which this
 *     file uses or writes. So P15 writes P15Q1's 165 names in P15Q1's order: the 65 shades unsuffixed from the scheme of the mode the
 *     phone is in, the 44 roles as _light and _dark, the 12 fixed colours unsuffixed from the light scheme; SystemUI's 21 custom
 *     colours are not written. Flags.COLOR_FIDELITY as for P15Q1 (taken as off).
 *   external/libmonet (android15-release fb9d98335a10561ead899a78bdd6bf2938adb52a, 2024-05-22, "Snap ... to 24Q3-release"): spec 2021
 *     for every style, the same as android15-qpr1-release (part 2 at 2021) but for two things, ported here (MCU.schemeOf 'a15', which
 *     sets DynamicScheme's a15 on a scheme whose spec is 2021): palettes/TonalPalette.java :59-60, :71-100 (createKeyColor: the key
 *     colour by a linear walk out from T50, Android 14's search, where QPR1 bisects; MCU.keyColorA15) and dynamiccolor/
 *     MaterialDynamicColors.java :426-444, :517-532, :595-613, :657-667 (on_primary_container, on_secondary_container,
 *     on_tertiary_container and on_error_container: light tone 10, where QPR1 has 30, and ContrastCurve(4.5, 7, 11, 21), where QPR1
 *     has (3, 4.5, 7, 11); Monochrome's own tones as on QPR1). Nothing else differs between the two branches (diff -r of the trees:
 *     those two files and TEST_MAPPING). The key colour gives palette_key_color_* and palettes().key; the palettes' hue and chroma,
 *     and so every shade, are QPR1's. Its other differences from part 2 are QPR1's (no tone-99 averaging of a yellow in TonalPalette,
 *     getRotatedHue's bounds, Cam16 with Math.hypot), with no output changed (the oracle below).
 *   At 2025 and 2026, and for CMF, which no Android 15.0 build computes (its libmonet has no other spec), P15 is P15Q1 (the brief's
 *     choice: upstream's main, with no a15 rule, as there is no a15 reference to compare with); that is, wherever the style's spec after
 *     upstream's fallback (specFor) is not 2021. A style that falls back to 2021 at 2025 or 2026 (Rainbow, Fruit Salad, Fidelity,
 *     Content, Monochrome) is computed at 2021, so with a15's rules, as at 2021 itself. The palettes the accents and the Intensity
 *     sliders build for a P15 scheme at 2021 take a15's key colour too (MCU.TonalPaletteA15; SystemUI has no such options, so there is
 *     no output of its to compare; the key colour of one slider-built palette was checked against libmonet a15's TonalPalette, t573 C7).
 *   What the oracle run showed (scripts on this container): SystemUI's own ColorScheme.java, TonalPalette.java and DynamicColors.java
 *   of android15-release compiled with libmonet android15-release (stubs only for Android classes the computation does not reach) and
 *   driven by the six functions above, transcribed (the harness of P15Q1's check), against entries(style, seed, { spec: '2021', dark,
 *   profile: 'P15', contrast }): the seven styles SystemUI builds, Content through SystemUI's ColorScheme and Fidelity through
 *   libmonet's SchemeFidelity, seeds 6750A4 1B6EF3 B5651D 00FF00 808080 FFDE3F at contrast 0, 0.5 and 1, the 56 more seeds of P15Q1's
 *   check at contrast -1, -0.5, 0, 0.25, 0.5, 0.75 and 1, and 9 picked for a yellow or cyan hue at 0, 0.5 and 1, light and dark: the
 *   same 165 resources in the same order with the same values (7,866 plans, 1,297,890 entries, no difference). Every
 *   MaterialDynamicColors method of libmonet android15-release (62, the palette key colours and the eight Android-only roles
 *   included) on the raw six seeds, the nine styles, contrast 0, 0.5 and 1, light and dark, against lib.roleArgb on
 *   MCU.schemeOf(..., '2021', 'a15'), and every palette's hue and chroma (within 1e-9) and key colour: no difference in 20,088 role
 *   values and 1,944 palettes (upstream's 2021, P15Q1's, differs in 735 role values and 294 key colours). The write order equals a
 *   list made from TOC android15-release's rules and DynamicColors.java read as text (165), and every name is a system_* colour of
 *   core/res values/colors.xml on android15-release. P15Q1's rules on P15, the key colour by bisection, and QPR1's on_*_container
 *   rules (tone 30, curve (3, 4.5, 7, 11), or either alone) each make the oracle check fail (1,470, 588, 882, 312 and 570 entries of
 *   the six seeds' 53,460); a15's rules at 2025 and 2026 too make the check below fail. At 2025, 2026 and CMF every P15 result
 *   equals P15Q1's; at 2021 they differ only in the four on_*_container roles and the palette_key_color_* roles (and palettes()'
 *   key colours). Every other profile's results are those of the file before P15, byte for byte (no profile and the seven others,
 *   every style and spec, the six seeds, light and dark, no contrast, 0 and 0.5, six option settings: 251,274 results; PROFILES
 *   aside, which now names P15). Not run on a device.
 *
 * Profiles P16Q1 and P16Q2, provenance (AOSP, Apache-2.0; libmonet Copyright 2021-2025 Google LLC, SystemUI Copyright (C)
 * 2019-2024 The Android Open Source Project, both covered by the notices at the top of this file): what SystemUI writes on Android
 * 16 QPR1 and QPR2, read at the branch heads refs/heads/android16-qpr1-release and android16-qpr2-release.
 *   external/libmonet (android16-qpr1-release d965376ed6506ecbc422b95431b73011e19a7b55, android16-qpr2-release
 *     bb0335b40e573ab05b0cc1690fb8a54c31044841; the copy of Google's library SystemUI calls): dynamiccolor/DynamicScheme.java
 *     (DEFAULT_SPEC_VERSION is SPEC_2025 and DEFAULT_PLATFORM PHONE, and every SchemeX(Hct, boolean, double) constructor, the one
 *     SystemUI's ColorScheme calls, passes them; the spec is kept as given, there is no maybeFallbackSpecVersion and no 2026), so all
 *     nine styles get the 2025 roles; dynamiccolor/ColorSpec2025.java (getPalettes: Tonal Spot, Vibrant, Expressive and Spritz take
 *     the 2025 palettes, Rainbow, Fruit Salad, Content, Monochrome and Fidelity keep 2021's, so those five are 2021 palettes with 2025
 *     roles, which neither upstream's main nor any other profile computes), and where its roles differ from upstream's main on a
 *     phone (MCU.ColorSpecDelegateLibmonet): getContrastCurve(1.5) is (1.5, 1.5, 3, 4.5), main (1.5, 1.5, 3, 5.5) (outline_variant and
 *     the containers and fixed colours above contrast 0); Expressive's primary is tMaxC(primary, 0, yellow ? 25 : cyan ? 88 : 98) in
 *     both modes (main: dark cyan ? 88 : 98, light yellow ? 25 : 98); Expressive's dark primary_container is tMaxC(primary, 30, 93)
 *     (main: tMinC); getHct is Hct.from(hue, chroma x multiplier, tone), with no averaging of tones 98 and 100 for a yellow's tone 99
 *     (main averages it through palette.getHct; ColorSpec2021.getHct reads libmonet's TonalPalette.getHct(double), also Hct.from);
 *     on_background is on_surface renamed (main also sets its tone to on_surface's: the same value on a phone); at 2025
 *     control_activated, control_normal and text_primary_inverse are primary_container, on_surface_variant and inverse_on_surface
 *     renamed (the other five Android-only roles keep their 2021 definition). QPR1 differs from QPR2 (the only change between the two
 *     branches is in ColorSpec2025.java): on_surface_variant takes curve 4.5 in both modes (QPR2: dark 6, light 4.5, as main), and
 *     primary_fixed, secondary_fixed and tertiary_fixed take the container's tone on DynamicScheme.from(s, false), which keeps the
 *     contrast level, with no background or curve (QPR2: at contrast 0, against surface_bright / surface_dim with curve 1.5 above
 *     contrast 0, as main). ColorSpec2021 returns null for primary_dim, secondary_dim, tertiary_dim and error_dim, so at spec 2021
 *     this file leaves those four out (51 roles); the brief's choice, as SystemUI itself reaches them only through ColorSpec2025.
 *     Spec 2021 is otherwise upstream's (libmonet's ColorSpec2021 equals it on a phone). CMF, which libmonet does not have, stays
 *     upstream's main at 2026.
 *   frameworks/libs/systemui monet/src/com/android/systemui/monet/ (the same files on both branches): DynamicColors.java
 *     (getAllDynamicColorsMapped: libmonet's allDynamicColors(), 67 colours, less the 12 whose name contains "fixed", named by
 *     generateSysUINames and sorted by name: P15Q2's 50 plus palette_key_color_error, primary_dim, secondary_dim, tertiary_dim and
 *     error_dim, 55; getFixedColorsMapped: the 12 sorted by name; getAllAccentPalette, getAllNeutralPalette), TonalPalette.java
 *     (shade s of a palette: Hct.from(hue, chroma, (1000 - s) / 10) of that scheme's palette, no chroma cap, no averaging; shadeA15).
 *   frameworks/base packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java (the same on both branches):
 *     createOverlays (qpr2 :689-706) writes the accent overlay (accent1, accent2, accent3), the neutral overlay (neutral1, neutral2),
 *     then the dynamic one; assignColorsToOverlay (:709-725) writes each palette shade and each role as _light (light scheme) then
 *     _dark (dark scheme), in list order (shades in SHADE_KEYS order: 0, 10, 50, 100, ..., 1000), and the fixed colours unsuffixed
 *     from the light scheme. So entries() gives 130 palette entries from both schemes, 110 role entries and 12 fixed colours: 252
 *     (244 at 2021), then SystemUI's custom colours, which are not included.
 *   What the oracle run showed (scripts on this container): a Java oracle, each branch's SystemUI DynamicColors.java and
 *   TonalPalette.java compiled with that branch's libmonet (stubs only for android.util.Pair and the custom colours) and
 *   createOverlays / assignColorsToOverlay transcribed, against entries(style, seed, { profile, spec, contrast }) with the oracle's
 *   default constructor (SPEC_2025) taken as spec 2025 and as 2026, SPEC_2021 as 2021 (the four dims left out) and SPEC_2025 as 2025:
 *   the nine styles, seeds 6750A4 1B6EF3 B5651D 00FF00 808080 FFDE3F, 56 more (black, white, a grey, primaries, yellows, 40 random)
 *   and 9 picked for a yellow or cyan Expressive primary, contrast 0, 0.5 and 1, light and dark: the same resources in the same order
 *   with the same values for every role, fixed colour and palette shade (P16Q1 and P16Q2 each 7,668 plans, 1,917,000 values, no
 *   difference). Every MaterialDynamicColors method of each branch (67, palette key colours and the eight Android-only roles
 *   included) on raw seeds, against lib.roleArgb, and every palette's hue and chroma (within 1e-9) and key colour, the six seeds,
 *   contrast 0, 0.5 and 1, light and dark, specs default, 2021 and 2025: no difference in 63,828 role values and 5,832 palettes per
 *   branch (the four dims at 2021, which this file leaves out, differ in 886 of 1,296). The write order equals the list made from the
 *   sources' rules and the research list of TOC qpr2 (252), and every name is a system_* colour of core/res on android16-qpr2-release.
 *   QPR1's rules on P16Q2, upstream's main 2025 (every style at 2025) without libmonet's changes, one scheme's palettes for both
 *   modes' shades, and the yellow tone-99 averaging each make the oracle check fail (324, 5,565, 2,223 and 27 values). Expressive's
 *   primary rule (main's in its place) changed no value for any seed tried. Every other profile's results are those of the file
 *   before these profiles, byte for byte (the 26,328 results of the Intensity check below, six option settings). Not run on a device.
 *
 * Profile P17, provenance (AOSP, Apache-2.0; libmonet Copyright 2021-2026 Google LLC, SystemUI Copyright (C) 2019-2024 The Android
 * Open Source Project, both covered by the notices at the top of this file): what SystemUI writes on Android 17 (API 37), read at the
 * branch heads refs/heads/android17-release.
 *   external/libmonet (android17-release f56ffa0, 2026-03-12): compared file by file with upstream's main Java at bd811e4 (the same
 *     commit part 2 ports; package names aside). Equal on a phone: ColorSpec2026.java (only an import differs), SchemeCmf.java,
 *     MaterialDynamicColors.java (its colorSpec is ColorSpec2026), ColorSpec2021.java (watch branches only), DynamicColor.java
 *     (extendSpecVersion takes a spec at or above the one named, as upstream's), DynamicScheme.java (DEFAULT_SPEC_VERSION is SPEC_2025,
 *     upstream's SPEC_2021, which the constructors SystemUI calls do not use; maybeFallbackSpecVersion as upstream's on a phone), Hct,
 *     Cam16, TemperatureCache, the other schemes and palettes. Different on a phone, so ported (MCU.ColorSpecDelegateLibmonet 'a17'):
 *     TonalPalette.getHct(double) and ColorSpec2025.getHct are Hct.from(hue, chroma, tone), with no averaging of a yellow's tone 99
 *     (libmonetHct, as on QPR); Expressive's primary is tMaxC(primary, 0, yellow ? 25 : cyan ? 88 : 98) in both modes and its dark
 *     primary_container tMaxC(primary, 30, 93) (as on QPR); from 2025 on, control_activated, control_normal and text_primary_inverse
 *     are primary_container, on_surface_variant and inverse_on_surface under their names (at 2026, for CMF, the 2026 roles:
 *     MCU.ColorSpecDelegateLibmonet2026); ColorSpec2021.getTone reads a 'darker' tone pair as nearer in light mode (upstream's
 *     TypeScript: in dark mode). No 2021 role has such a pair; the 2025 dim colours do, and SystemUI writes them on the styles that
 *     fall back to 2021 (on QPR the same rule makes this file's dims at spec 2021 equal libmonet's, which are not written there).
 *     getContrastCurve(1.5) is upstream's (1.5, 1.5, 3, 5.5) again, so the QPR overrides that only change that curve (outline_variant,
 *     the containers, the fixed colours) are upstream's roles on 17, and so are on_surface_variant and on_background.
 *   frameworks/libs/systemui monet/src/com/android/systemui/monet/: ColorScheme.java (every style built with the spec passed in; the
 *     4-argument constructor SystemUI calls passes SPEC_2026 and PHONE, :141-146, :158; the seed rule as on 16: Google blue for no
 *     colour and, except for Content, for a chroma under 5; the seed is a List of colours there, this file passes one; a CMF case, but
 *     ThemeOverlayController's validStyles leave CMF out, so SystemUI never builds it system-wide), DynamicColors.java and
 *     TonalPalette.java (byte for byte QPR2's: the 55 roles and 12 fixed colours sorted by name, shade s = Hct.from(hue, chroma,
 *     (1000 - s) / 10) of that scheme's palette, which on 17 is the same at 2021, 2025 and 2026).
 *   frameworks/base packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java: createOverlays (:688-706) as on
 *     QPR2; assignColorsToOverlay (:708-718) has no fixed branch, so every colour, the fixed ones included, is written as _light (light
 *     scheme) then _dark (dark scheme). core/res values/colors.xml defines system_primary_fixed_light and _dark (:677, :810) and makes
 *     the unsuffixed fixed names aliases of _light (:936-937); values-night/colors.xml makes them aliases of _dark (:63-74). So entries() gives 130 palette entries, 110 role entries and 24 fixed
 *     entries: 264, in that order, then SystemUI's custom colours (27 on 17), which are not included. At spec 2021 the four dim roles
 *     are left out (256), as on QPR (the brief's choice: libmonet's ColorSpec2021 has no dim colours); at 2025 and 2026 they are
 *     written for every style, as SystemUI writes them.
 *   What the oracle run showed (scripts on this container): a Java oracle, a17's SystemUI DynamicColors.java and TonalPalette.java
 *   compiled with a17's libmonet (stubs only for android.util.Pair and the custom colours), ColorScheme's scheme switch with the
 *   spec passed in and createOverlays / assignColorsToOverlay transcribed, against entries(style, seed, { profile: 'P17', spec,
 *   contrast }) and palettes() (hue and chroma within 1e-9, key colour exact; light and dark): the nine styles and CMF, the six seeds
 *   above, the 56 more and the 9 of the QPR check, contrast 0, 0.5 and 1, specs 2021, 2025 and 2026 and ColorScheme's default
 *   (SPEC_2026, taken as 2026): the same resources in the same order with the same values for every role, fixed colour and palette
 *   shade (8,094 plans, 2,121,480 values, 80,940 palettes, no difference), and CMF at 2021 and 2025 throws in both (426 cases). The
 *   write order equals the oracle's and a list made by hand from TOC a17's rules (QPR2's 252 with each fixed colour as _light and
 *   _dark: 264), and every name is a system_* colour of a17 core/res. CMF on 17 differs from upstream's main CMF (P16Q2's) in 1,271
 *   of 51,120 role and shade values (the three renamed roles, and inverse_primary through the tone-99 rule; 71 seeds), so P17 takes
 *   libmonet's. QPR2's rules on P17, upstream's main 2026 without a17's changes, the fixed colours unsuffixed, one scheme for both
 *   modes (shades, or the fixed colours), upstream's TypeScript polarity, the dims left out by the scheme's spec, upstream's CMF and
 *   QPR's 1.5 curve each make the oracle check fail. Every other profile's results are those of the file before P17, byte for byte
 *   (every profile, P16Q1 and P16Q2 included, the 36,696 results of the regression check, six option settings). Not run on a device.
 *
 * The maths is all doubles; Android 14's ColorScheme.kt feeds Shades.of with the seed's hue and chroma as floats (its framework Cam
 * class), so a P14 palette entry computed here may differ from the phone's own by one RGB step in rare cases (Android 15 and 16 take
 * their shades from libmonet's palettes, doubles). Not checked on a device.
 *
 * Intensity (opts.adjust): the root card's three sliders. Android and Google's library have no such sliders; the definitions below were
 * written for this project. On the 13 shades, Background lightness gives the same tone steps as another theming app's lightness slider
 * (compared by behaviour; no other app's code, strings or tables are used); above 100 the saturation sliders here multiply the chroma,
 * which that app does not. They act on the scheme's tonal palettes before anything is derived from them, so the 65 shades, the roles and
 * the fixed colours all come from the same adjusted palettes; the error palette is never touched. No phone computes these colours itself.
 *   Accent saturation, a = accentSat / 100: the primary, secondary and tertiary palettes become the palettes of the same hue with chroma
 *     x a (the part's own TonalPalette.fromHueAndChroma, key colour included). The HCT solver clips each tone to the sRGB gamut, so above
 *     100 the display's maximum is the ceiling. At 2021 Vibrant's primary, chroma 200, is there already at 100, except P14's shades 10 and
 *     50, whose chroma is capped at 40 before the factor: for yellow and yellow-green seeds those still get stronger (FFFF00's accent1_50
 *     F7F692 at 100, F8F858 at 150, F9F900 at 200).
 *   Background saturation, b = bgSat / 100: the neutral and neutral variant palettes, chroma x b.
 *   Background lightness, v = bgLight: the neutral and neutral variant palettes, asked for tone t, give their colour at tone
 *     f(t) = clamp(t + K * (v - 100) * d(t), 0, 100), K = ADJUST_TONE_STEP = 0.1 (ten tones at the slider ends) and d(t) = min(1, t / 10,
 *     (100 - t) / 10) (adjustRamp): tones 0 and 100 never move (white stays white, black stays black), tone 99 moves a tenth as far as
 *     tones 10 to 90, tone 95 half as far; hue and chroma stay. Above 100 is lighter, below darker.
 *   The roles: the engine still solves every tone (contrast curves, tone-delta pairs, Android 14's Albers step for Fidelity and Content)
 *     on the adjusted palettes' hue and chroma; the tone map comes after it: a role on a neutral palette gets the colour at f of the tone
 *     the engine picked (part 1: DynamicColor.getHct; part 2: the palette's getHct, and in the 2025 delegate's chroma-multiplier path,
 *     which asks no palette for a tone, Hct.from at f(tone)). Text and its background then move by different amounts near the ends, so
 *     contrast can drop. Measured (WCAG ratio; the saturation sliders at 100, contrast level 0, the six seeds above, light and dark
 *     together; P14 and P16 gave the same numbers), worst case at bgLight 100 / 0 / 200: on_surface on surface 14.29 / 12.29 / 13.07
 *     for the nine styles at 2021, 11.53 / 13.05 / 8.29 for Tonal Spot, Vibrant, Expressive and Spritz at 2025, 12.09 / 13.07 / 9.00 for
 *     CMF at 2026; on_surface_variant on surface_container_highest 7.17 / 7.10 / 6.42 at 2021, 4.84 / 5.38 / 4.13 at 2025, 4.92 / 5.41 /
 *     4.42 for CMF. Most of the drop is in light mode at 200: the text lightens about ten tones while a background cannot pass white
 *     (the surface moves 2.1 tones at most at 2021 and for CMF, 3.1 at 2025; surface_container_highest, tone 90, the full ten). At 2021
 *     the lowest on_surface ratio is in dark mode at 0 (the text darkens about ten tones, the surface at most 6.2; at 2025 and for CMF
 *     the surface about four, the text 3.9 to 9.2). The background tiers merge at the ends: at 200 background, surface, surface_bright
 *     and the five surface containers are white in light mode (at 2025 surface_container_highest in 38 of 48 cases, else next to it),
 *     at 0 background, surface, surface_dim and the two lowest containers (at 2025 and for CMF also surface_container) are black in dark
 *     mode (P14 and P16, the six seeds, every style and spec, contrast 0).
 *   The shades: P14 (Android 14's rule) shade s at tone f(shadeTone(s)), the palette's hue, and chroma min(40, c) x the factor for
 *     shades 10 and 50, c x the factor for the others (c: the chroma before the sliders); P15, P15Q1, P15Q2, P16, P16Q1, P16Q2 and P17
 *     tone f(trunc((1000 - s) / 10)) at chroma c x the factor (P16Q1, P16Q2 and P17: each mode's shades from that mode's
 *     palettes). Shade 0 (white) and shade 1000 (black) never move; at 200 shades 10, 50 and 100 of a neutral row
 *     reach white, at 0 shade 900 reaches black.
 *   A palette with no chroma (every Monochrome palette, Rainbow's neutrals) is unchanged by the saturation sliders: 0 x anything is 0.
 *   What was checked (scripts on this container): no adjust, adjust null or {}, and all three at 100 give the file's results from before
 *   the option byte for byte (entries(), palettes() and roles() for no profile, P14, P15Q1, P15Q2 and P16, every style id and alias, no
 *   spec, 2021, 2025 and 2026, seeds 6750A4 1B6EF3 B5651D 00FF00 808080 FFDE3F, light and dark, no contrast, 0 and 0.5: 26,328 results).
 *   Part 2 against Google's library at bd811e4 with its palettes adjusted by a separate script written from the definitions above:
 *   entries(), palettes(), roles() and scheme()'s six palettes (hue, chroma, key colour, tones 0 to 100), the six seeds, contrast 0 and
 *   0.5, light and dark; the nine styles at 2021 for P16, P15Q2 and P15Q1 at 10 slider settings, Tonal Spot, Vibrant, Expressive and
 *   Spritz at 2025 and 2026 and CMF at 2026 for P16 and P15Q2 at 9: no difference. P14 at 2021 against the file from before the option
 *   run by hand (its engine on the adjusted palettes, the tone map after), 8 settings, contrast 0 and 0.5: every role, fixed colour and
 *   scheme() palette; P14's shades at 2021, 2025 and 2026 by the rule above: no difference. A changed K, a changed d, the cap, each
 *   tone-map step left out and an adjusted error palette each make at least one of these checks fail. None of it was run on a device.
 *
 * Secondary and tertiary accents (opts.accents): the root card's two colours, one for the second accent row (accent2 and the secondary
 * roles), one for the third (accent3 and the tertiary roles). Android and Google's library have no such option; the rules below were
 * written for this project. No phone computes these colours itself. For a colour X set for a row:
 *   1. The scheme of the same style, spec, mode, contrast and profile (so the same part) is built from X as the seed, and its primary
 *      palette P is taken. X is taken as it is: seed()'s grey rule (Android's ColorScheme turns a source colour with chroma under 5
 *      into Google blue) is not applied to a colour picked for a row. Measured with two mutants of this file (X as the secondary,
 *      808080, 404040, 000000 and FFFFFF, whose chroma is under 5, and 7F8085 and A0A090, whose chroma is not and which change
 *      nothing; every style and spec, P14 and P16, light and dark): seed() applied to X itself would turn a grey X into Google blue,
 *      hue included, in every style but Fidelity, Content and Monochrome (seed()'s exemptions), so Tonal Spot's accent2_500 for 808080
 *      on P14 at 2021 would be 5F75AB (blue) in place of 27818D (teal); seed() applied only to the seed of X's scheme, with step 2
 *      still keeping X's hue, would change only Spritz at 2025 and 2026 (its chroma depends on whether the hue is blue) and CMF (a
 *      grey would take Google blue's chroma). The mode is the one of the scheme X's palette goes into: entries() builds a
 *      light and a dark scheme and each gets the palette of X's scheme of its own mode (at 2021 they are the same; at 2025 Tonal
 *      Spot's and Expressive's chroma differ).
 *   2. If P's hue is X's hue (within ACCENT_HUE_TOLERANCE, 0.5 degrees), P is used as it is; otherwise the palette at X's hue with P's
 *      chroma (the part's own TonalPalette.fromHueAndChroma). So the hue picked is kept in every style, and the style sets how strong
 *      the colour is. The styles that turn the primary's hue away from the seed's, read in the sources: at 2021, Android 14's engine
 *      (SchemeExpressive.java, SchemeFruitSalad.java, android14-release) and Google's library at main (getPrimaryPalette of
 *      DynamicSchemePalettesDelegateImpl2021) give Expressive the seed's hue + 240 and Fruit Salad the seed's hue - 50; Tonal Spot,
 *      Vibrant, Rainbow, Spritz, Fidelity, Content and Monochrome keep the seed's hue. At 2025 (DynamicSchemePalettesDelegateImpl2025,
 *      phone) Tonal Spot, Vibrant, Expressive and Spritz all keep it, Expressive included; at 2026 those four get 2025 and CMF keeps the
 *      seed's hue (and chroma); every other style is 2021 at every spec (maybeFallbackSpecVersion). So the second branch serves
 *      Expressive at 2021 and Fruit Salad at every spec, and no other case (the oracle below counts which styles took it).
 *   3. That palette replaces the scheme's secondary (or tertiary) palette before anything is derived from it: the row's 13 shades (by
 *      the profile's shade rule, as for every row: P14 Android 14's, with its chroma cap of 40 on shades 10 and 50, P15, P15Q1, P15Q2
 *      and P16 tone trunc((1000 - s) / 10)), and the roles: secondary, on_secondary, secondary_container, on_secondary_container, the four
 *      secondary fixed colours and palette_key_color_secondary (likewise tertiary). The engine still solves each role's tone against
 *      its background (contrast curves, tone-delta pairs, Android 14's Albers step for Fidelity and Content), so Android's contrast
 *      rules apply on top. The primary, neutral, neutral variant and error palettes are never touched: nothing outside the row and its
 *      roles changes.
 *   4. The accents come before the Intensity sliders: Accent saturation scales the replaced palettes too.
 *   CMF (spec 2026) and the tertiary: the 2026 rules (color_spec_2026.ts, ported in part 2) take the tone of tertiary and
 *   tertiary_container, and so of the tertiary fixed colours, from the scheme's second source colour, or from its source colour when it
 *   has only one; this file gives CMF one source colour, the seed. Step 3 replaces the tertiary palette only (as in every style), so
 *   with a tertiary accent those roles are X's hue and chroma at the seed's tone, and tertiary_container is not X. Measured against
 *   Google's library at bd811e4 with X as SchemeCmf's second source colour (seed 6750A4, X FFDE3F, contrast 0, P16): the tertiary
 *   palettes are the same; light mode gives tertiary 615200 and tertiary_container 6F5E00 here, 6F5E00 and FFDE3F there. Giving X to
 *   CMF as a second source colour for these tones is a choice left open (the brief's rule replaces the palette). The 2026 secondary
 *   roles take their tones from the secondary palette itself (tMaxC / tMinC), so a secondary accent is not concerned.
 *   Monochrome: P has chroma 0, so the row stays the same grey; every colour written is unchanged (palettes() and scheme() report X's
 *   hue for that row, which a chroma-0 colour does not show). Fidelity and Content keep X's hue and chroma; their key colour
 *   (palettes().key, palette_key_color_secondary / _tertiary) is the palette's own key colour search at that hue and chroma, as for the
 *   seed's primary row, so it is X only when X sits at the tone that search finds (00897B does; 6750A4 gives 8069BF, 00FF00 00FD00).
 *   What was checked (scripts on this container): no accents, accents null, {}, both null, or other keys only give the file's results
 *   from before the option byte for byte, alone and with two Intensity settings (the 26,328 results of the Intensity check above, nine
 *   runs). Part 2 against Google's library at bd811e4, the scheme rebuilt from the replaced palettes by a separate script written from
 *   the rules above (X's scheme from the library's own Scheme classes, the hue rule, then the sliders): entries(), palettes(), roles()
 *   and scheme()'s six palettes (hue, chroma, key colour, tones 0 to 100); seeds 6750A4 1B6EF3 B5651D 00FF00 808080 FFDE3F; eight
 *   accent settings (the seed itself, greys, black and white, colours far from the seed, both string forms, one row or both); three
 *   slider settings; contrast 0 and 0.5; light and dark. The nine styles at 2021 for P16, P15Q2 and P15Q1 (15,552 cases); Tonal Spot,
 *   Vibrant, Expressive and Spritz at 2025 and 2026 and CMF at 2026 for P16, P15Q2 and P14 (15,552 cases); the five other styles at
 *   2025 and 2026 for P16, P15Q2 and P15Q1 (11,520 cases, two slider settings, scheme() tones left out): no difference, and the hue
 *   rule's second branch was taken by Expressive at 2021 and Fruit Salad only. P14 at 2021 against the file from before the option run
 *   by hand (its own palettes for X, the hue rule, its engine for the roles, Android 14's shade rule; four slider settings): every
 *   entry, palette, role and scheme() tone, no difference. Over the whole grid of the identity check, with a secondary, a tertiary and
 *   both set, with and without the sliders: only accent2 / secondary* (accent3 / tertiary*) values change; the accents change nothing
 *   written under Monochrome, and nowhere else but where the gamut clips both palettes to the same colours (Fidelity and Content,
 *   seed 00FF00 with 00FF00 as the secondary, at accentSat 150). The accents applied after the sliders, the hue rule left out, X's
 *   scheme at the other mode or at spec 2021 (caught at 2025 and 2026, where those change the palettes), the primary palette replaced
 *   instead, seed()'s grey rule applied to X, and X's scheme for P14 at 2021 built by part 2 each make at least one of these checks
 *   fail. None of it was run on a device.
 *
 * Accurate shades (opts.accurate): the root card's switch, on by default. Android and Google's library have no such option; the rule
 * below was written for this project. On (true, null or left out): nothing changes. Off (false): the scheme's three accent palettes
 * (primary, secondary, tertiary; the rows accent1, accent2, accent3), as the accents and the Intensity sliders leave them, answer
 * tone 90 with their tone-70 colour. So everything taken from those palettes at tone 90 becomes darker (tone 70 in place of 90; not
 * always more saturated: measured on the shades, shade 300's chroma is the same as or lower than shade 100's in about a quarter of
 * the accent rows, e.g. P14 2021 Tonal Spot 4285F4 accent2 16.05 -> 15.90):
 *   - shade 100 of each accent row (system_accentN_100) is shade 300 of the same row, on every profile; the shades are made by the
 *     profile's rule from the palette (P14's chroma cap on shades 10 and 50 included) and shade 100 is then given shade 300's value;
 *   - each role colour, fixed colour and palette_key_color taken from an accent palette whose tone, computed by the spec's own rules
 *     with the phone's contrast, is exactly 90 gets that palette's tone-70 colour. The tones are not changed: a role whose tone is
 *     moved off 90 by contrast, or that 2025 / 2026 compute to another tone, keeps its colour, and a rule that reads a palette to
 *     compute a tone (Fidelity and Content at 2021) reads it as it was. Which roles that is depends on the spec, the style and the
 *     mode: at 2021 the light containers (primary, secondary, tertiary), the dark on-containers and the three _fixed colours
 *     (tertiary_fixed as the other two); control_activated in light mode on every spec; at 2025 and 2026 only some of them, for some
 *     seeds (those that land on 90). The neutral, neutral variant and error palettes, and so the neutral rows, the surfaces and the
 *     text colours from them, never change; nor do palettes()' hue, chroma and key (the palette's own key colour).
 * On Android 14 and later most apps take their colours from the role colours, so with the switch off they see the changed
 * containers, on-containers and fixed colours where those are at tone 90; which screens use which roles is not known here.
 *   What was checked (scripts on this container, against this file from before the option, 5b44669b): accurate left out, null or true
 *   gives its results byte for byte (the 26,328 results of the checks above: every profile, style, spec, the six seeds, light and
 *   dark, contrast 0 and 0.5) with no other option, with two Intensity settings, with both accents, and with a tertiary accent and
 *   the sliders together (15 runs). With accurate false, over the same grid and five settings, every result against the rule
 *   applied by hand to the old file's engine (its own role objects on its own schemes, for P14 at 2021 too: palette and computed
 *   tone of each role, tone 90 of an accent palette replaced by Hct(hue, chroma, 70); shade 100 := shade 300): no difference. Part 2
 *   against Google's library at bd811e4 with its three accent palettes wrapped so that tone(90) is tone(70), built as the accents
 *   check above builds it: P16 and P15Q2 at 2021, 2025 and 2026 (CMF at 2026), P14 for Tonal Spot, Vibrant, Expressive and Spritz
 *   at 2025 and 2026 and CMF at 2026; the six seeds, contrast 0 and 0.5, light and dark, three slider settings, no accents and two
 *   accent settings (14,040 cases): entries(), palettes(), roles() and scheme()'s six palettes (tones 0 to 100), no difference, and
 *   shade 100 equals shade 300 in every accent row. scheme() with accurate false: the three accent palettes give tone 70 for 90
 *   (tone and getHct), everything else as before; a value other than a boolean, null or left out throws in all four calls.
 *   Wrapping the neutral palettes too, wrapping nothing (only the shade swap), reading tone 80 as 70, wrapping before the sliders,
 *   letting Fidelity's tone rules read the wrapped palette, leaving out P14's shade swap, and this file's earlier rule (shade 100
 *   only) each make at least one of these checks fail. None of it was run on a device.
 *
 * API (window.MonetPlan in the page, module.exports in node):
 *   MonetPlan.STYLES                       the ten style ids this file knows: TONAL_SPOT VIBRANT EXPRESSIVE RAINBOW FRUIT_SALAD SPRITZ
 *                                          FIDELITY CONTENT MONOCHROMATIC CMF (NEUTRAL and MONOCHROME are accepted as aliases)
 *   MonetPlan.SPECS                        the colour specs: '2021' '2025' '2026'
 *   MonetPlan.PROFILES                     the Android builds this file computes for: 'P14' (Android 14), 'P15' (15.0), 'P15Q1' (15
 *                                          QPR1), 'P15Q2' (15 QPR2), 'P16' (16.0), 'P16Q1' (16 QPR1), 'P16Q2' (16 QPR2), 'P17' (17)
 *   MonetPlan.specFor(style, spec, profile)  the spec the style really gets on a phone. No profile, or P14 to P16 (upstream's
 *                                          maybeFallbackSpecVersion): CMF keeps the spec, TONAL_SPOT VIBRANT EXPRESSIVE SPRITZ turn
 *                                          2026 into 2025, every other style is always 2021. P16Q1 and P16Q2 (libmonet): every style
 *                                          but CMF gets 2021 for 2021 and 2025 for 2025 and 2026; CMF as before. P17 (libmonet
 *                                          android17-release) as upstream's. No spec is 2021; an unknown spec, style or profile throws
 *   MonetPlan.hct(argb)                    { hue, chroma, tone } of a colour (CAM16 hue and chroma, L* as the tone)
 *   MonetPlan.seed(style, argb)            the seed the style really uses: a seed with chroma under 5 becomes Google blue, except for
 *                                          Fidelity and Content (they keep it) and Monochrome (no difference); CMF follows the rule
 *                                          like the stock styles; 0 / nothing is Google blue
 *   opts = { spec, dark, profile, contrast, adjust, accents, accurate }  optional on the four calls below. spec: one of SPECS, '2021' when
 *                                          left out. profile: one of PROFILES, 'P14' when left out; any other value throws ('Profile not
 *                                          supported').
 *                                          contrast: the phone's contrast level, a number from -1 to 1 (clamped), 0 when left out;
 *                                          anything but a number throws. dark: the mode the phone is in; for P15, P15Q1, P15Q2 and P16,
 *                                          and for P14 at 2025 and 2026 (where light and dark palettes differ), the palettes and the
 *                                          65 shades come from the scheme of that mode, for P14 at 2021 from the light one; for
 *                                          P16Q1, P16Q2 and P17 palettes() follows it and entries() writes both modes. No opts,
 *                                          or P14 at contrast 0, gives the same results as before the profiles, byte for byte, and
 *                                          a spec the style falls back to 2021 at the same as before part 2. CMF with any spec but
 *                                          '2026' throws, as upstream's SchemeCmf does. adjust: the Intensity sliders (above),
 *                                          { accentSat, bgSat, bgLight }, each a percentage, 100 when left out, clamped to 0..200; a
 *                                          value that is not a number throws, as an adjust that is not an object does. No adjust, null,
 *                                          {} or all three at 100 give the results without it, byte for byte. accents: the secondary
 *                                          and tertiary accents (above), { secondary, tertiary }, each a colour as 'RRGGBB' or
 *                                          '#RRGGBB' (any case), or null or left out for the style's own; any other value (a number,
 *                                          three digits, '') throws, as accents that are not an object (or an array) do; other keys
 *                                          are ignored. No accents, null, {} or both null give the results without them, byte for
 *                                          byte. With adjust too, the accents come first. accurate: Accurate shades (above), a
 *                                          boolean; true, null or left out give the results without it, byte for byte; false makes
 *                                          the three accent palettes (after the accents and adjust) give tone 70 where tone 90 is
 *                                          asked: system_accentN_100 (palettes(): accentN.shades[3]) is the row's shade 300, and
 *                                          the roles and fixed colours at tone 90 of those palettes take tone 70; any other value
 *                                          throws, as adjust does.
 *   MonetPlan.palettes(style, argb, opts)  { seed, style, accent1..neutral2: { hue, chroma, key: 'RRGGBB', shades: ['RRGGBB' x 13] } },
 *                                          the shades in SHADE_KEYS order by the profile's rule. With adjust: the adjusted palette's
 *                                          chroma (the chroma asked for, which the display may not reach) and key colour (Background
 *                                          lightness moves the tones a palette gives, not its key colour). With accents: the accent
 *                                          palette's hue, chroma and key colour in accent2 / accent3
 *   MonetPlan.roles(style, argb, isDark, opts)  the profile's roles as [{ name, argb }] (argb a '0xAARRGGBB' string), in its order:
 *                                          44 for P14, P15 and P15Q1, 50 for P15Q2 and P16, 55 for P16Q1, P16Q2 and P17 (51 at spec 2021,
 *                                          without primary_dim, secondary_dim, tertiary_dim and error_dim; on P17 the spec asked for
 *                                          decides, so a style that falls back to 2021 from 2025 or 2026 has 55)
 *   MonetPlan.entries(style, argb, opts)   the overlay entries [{ resource, argb }] the profile's SystemUI writes, in its order. P14,
 *                                          165: 65 palette entries (accent1 first, SHADE_KEYS order), the 44 roles for dark then light,
 *                                          the 12 fixed colours from the light scheme. P15 and P15Q1, 165, and P15Q2 and P16, 177: 65
 *                                          palette entries in SystemUI's write order, each role _light then _dark, the 12 fixed colours from
 *                                          the light scheme. P16Q1 and P16Q2, 252 (244 at spec 2021): 130 palette entries, accent1-3
 *                                          then neutral1-2, each shade in SHADE_KEYS order as _light (light scheme) then _dark (dark
 *                                          scheme), each role _light then _dark (55, or 51 at 2021, sorted by name), the 12 fixed
 *                                          colours from the light scheme. P17, 264 (256 at spec 2021): as P16Q1 and P16Q2, then the
 *                                          12 fixed colours (sorted by name) each as _light (light scheme) then _dark (dark scheme);
 *                                          no unsuffixed name. SystemUI's custom colours are not included
 *   MonetPlan.scheme(style, argb, isDark, opts)  the scheme behind them (part 1's for P14 at 2021, part 2's otherwise; with accents
 *                                          the secondary / tertiary palette is the accent palette (the part's TonalPalette); with adjust
 *                                          the palettes a slider changes are AdjustedPalette objects, the others and the error palette
 *                                          the scheme's own; with accurate false the three accent palettes are AccuratePalette objects
 *                                          over those, .base the palette before the switch), for tests
 *   MonetPlan.lib                          the ported classes, for tests (lib.mcu is part 2). lib.adjustOf, lib.accentsOf and
 *                                          lib.accurateOf are what the page tests to know that this file has opts.adjust, opts.accents
 *                                          and opts.accurate (an older monet.js would ignore them)
 * Colours go in as 0xAARRGGBB numbers or 'RRGGBB' / '#RRGGBB' strings and come out as '0xAARRGGBB' or 'RRGGBB' strings.
 */
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    root.MonetPlan = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    // ==========================================================================================================================
    // Part 1: Android 14's monet engine (AOSP android14-release), PR 3's port. It computes spec 2021, the colours Android computes
    // itself, for every style; the API sends every scheme whose spec falls back to 2021 here. Part 2 (Google's library at main, for
    // the 2025 and 2026 specs and CMF) follows the shade rule below.
    // ==========================================================================================================================

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
        // palette: the role's palette (fromPalette), kept only for the Intensity sliders' tone map in getHct; Android 14 has no such field
        constructor(hue, chroma, tone, opacity, background, toneMinContrast, toneMaxContrast, toneDeltaConstraint, palette) {
            this.hue = hue; this.chroma = chroma; this.tone = tone; this.opacity = opacity; this.background = background;
            this.toneMinContrast = toneMinContrast; this.toneMaxContrast = toneMaxContrast; this.toneDeltaConstraint = toneDeltaConstraint;
            this.palette = palette || null;
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
                toneDeltaConstraint, palette);
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
            // the Intensity sliders (AdjustedPalette, in the API part): a palette with a tone map gives the colour at the mapped tone of the
            // tone the engine picked (Background lightness); an Android 14 palette has none, and the colour is Android 14's
            const hue = this.hue(scheme), chroma = this.chroma(scheme), tone = this.getTone(scheme), palette = this.palette === null ? null : this.palette(scheme);
            const answer = Hct.from(hue, chroma, palette && palette.mapTone ? palette.mapTone(tone) : tone);
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
        // accurateBase (Accurate shades, in the API part; Android has no such step): a tone rule reads the palette as it was before the switch
        answer = performAlbers(accurateBase(s.secondaryPalette).getHct(answer), s);
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
        // accurateBase: as in secondaryContainer above
        const albersTone = performAlbers(accurateBase(s.tertiaryPalette).getHct(s.sourceColorHct.tone), s);
        const proposedHct = accurateBase(s.tertiaryPalette).getHct(albersTone);
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
    // ==========================================================================================================================
    // Part 2: Google's Material Color Utilities at main, commit bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3 (2026-10-19), typescript/:
    // the 2021, 2025 and 2026 colour specs (Tonal Spot, Vibrant, Expressive and Spritz at 2025; every style at 2021) and the CMF style
    // (2026 only). Upstream's names are kept so a reader can follow this part next to its source. Platform phone only (the watch
    // branches are left out), one source colour, any contrast level. Part 1's colour utilities, ViewingConditions and HctSolver are
    // shared (equal to main bit for bit); what this part keeps of its own, and why, is in the header. For profile P14 the API sends
    // every scheme whose spec falls back to 2021 (upstream's maybeFallbackSpecVersion) to part 1, so this part serves 2025 and 2026;
    // for P15, P15Q1, P15Q2 and P16 it serves every spec, 2021 included (P15 with libmonet android15-release's two differences at 2021).
    // ==========================================================================================================================
    const MCU = (function () {
        // ---------- dynamiccolor/variant.ts (the same names and values as part 1's Variant, plus CMF) ----------
        const Variant = { MONOCHROME: 'MONOCHROME', NEUTRAL: 'NEUTRAL', TONAL_SPOT: 'TONAL_SPOT', VIBRANT: 'VIBRANT', EXPRESSIVE: 'EXPRESSIVE', FIDELITY: 'FIDELITY', CONTENT: 'CONTENT', RAINBOW: 'RAINBOW', FRUIT_SALAD: 'FRUIT_SALAD', CMF: 'CMF' };

        // ---------- hct/cam16.ts: the hue and chroma of Cam16.fromInt (all Hct reads of a Cam16). Main computes t from
        // Math.sqrt(a * a + b * b); part 1's Cam16 keeps Java's Math.hypot, whose chroma is off by up to 7.1e-14 for 23% of all colours,
        // which shows in SchemeCmf's palettes (they take the seed's chroma as it is) and in the key colours' chroma ----------
        function cam16HueChroma(argb) {
            const vc = ViewingConditions.DEFAULT;
            const red = (argb & 0x00ff0000) >> 16, green = (argb & 0x0000ff00) >> 8, blue = argb & 0x000000ff;
            const redL = linearized(red), greenL = linearized(green), blueL = linearized(blue);
            const x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL;
            const y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL;
            const z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL;
            const rC = 0.401288 * x + 0.650173 * y - 0.051461 * z;
            const gC = -0.250268 * x + 1.204414 * y + 0.045854 * z;
            const bC = -0.002079 * x + 0.048952 * y + 0.953127 * z;
            const rD = vc.rgbD[0] * rC, gD = vc.rgbD[1] * gC, bD = vc.rgbD[2] * bC;
            const rAF = Math.pow(vc.fl * Math.abs(rD) / 100.0, 0.42), gAF = Math.pow(vc.fl * Math.abs(gD) / 100.0, 0.42), bAF = Math.pow(vc.fl * Math.abs(bD) / 100.0, 0.42);
            const rA = signum(rD) * 400.0 * rAF / (rAF + 27.13), gA = signum(gD) * 400.0 * gAF / (gAF + 27.13), bA = signum(bD) * 400.0 * bAF / (bAF + 27.13);
            const a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
            const b = (rA + gA - 2.0 * bA) / 9.0;
            const u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
            const p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
            const hue = sanitizeDegreesDouble(Math.atan2(b, a) * 180.0 / Math.PI);
            const ac = p2 * vc.nbb;
            const j = 100.0 * Math.pow(ac / vc.aw, vc.c * vc.z);
            const huePrime = hue < 20.14 ? hue + 360 : hue;
            const eHue = 0.25 * (Math.cos(huePrime * Math.PI / 180.0 + 2.0) + 3.8);
            const p1 = (50000.0 / 13.0) * eHue * vc.nc * vc.ncb;
            const t = p1 * Math.sqrt(a * a + b * b) / (u + 0.305);
            const alpha = Math.pow(t, 0.9) * Math.pow(1.64 - Math.pow(0.29, vc.n), 0.73);
            return [hue, alpha * Math.sqrt(j / 100.0)];
        }

        // ---------- hct/hct.ts (the solver is part 1's HctSolver, equal to main bit for bit) ----------
        class Hct {
            constructor(argb) { argb = argb >>> 0; const hc = cam16HueChroma(argb); this.argb = argb; this.hue = hc[0]; this.chroma = hc[1]; this.tone = lstarFromArgb(argb); }
            static from(hue, chroma, tone) { return new Hct(HctSolver.solveToInt(hue, chroma, tone)); }
            static fromInt(argb) { return new Hct(argb); }
            toInt() { return this.argb; }
            static isBlue(hue) { return hue >= 250 && hue < 270; }
            static isYellow(hue) { return hue >= 105 && hue < 125; }
            static isCyan(hue) { return hue >= 170 && hue < 207; }
        }

        // ---------- contrast/contrast.ts (main clamps the tones of ratioOfTones to 0..100, part 1 does not; lighter and darker agree
        // with part 1's for every ratio of at least 1, where part 1's extra range checks on the Y value change nothing) ----------
        const Contrast = {
            ratioOfTones(toneA, toneB) {
                toneA = clampDouble(0.0, 100.0, toneA);
                toneB = clampDouble(0.0, 100.0, toneB);
                return Contrast.ratioOfYs(yFromLstar(toneA), yFromLstar(toneB));
            },
            ratioOfYs(y1, y2) { const lighter = y1 > y2 ? y1 : y2, darker = lighter === y2 ? y1 : y2; return (lighter + 5.0) / (darker + 5.0); },
            lighter(tone, ratio) {
                if (tone < 0.0 || tone > 100.0) return -1.0;
                const darkY = yFromLstar(tone), lightY = ratio * (darkY + 5.0) - 5.0;
                const realContrast = Contrast.ratioOfYs(lightY, darkY), delta = Math.abs(realContrast - ratio);
                if (realContrast < ratio && delta > 0.04) return -1;
                const returnValue = lstarFromY(lightY) + 0.4;
                if (returnValue < 0 || returnValue > 100) return -1;
                return returnValue;
            },
            darker(tone, ratio) {
                if (tone < 0.0 || tone > 100.0) return -1.0;
                const lightY = yFromLstar(tone), darkY = (lightY + 5.0) / ratio - 5.0;
                const realContrast = Contrast.ratioOfYs(lightY, darkY), delta = Math.abs(realContrast - ratio);
                if (realContrast < ratio && delta > 0.04) return -1;
                const returnValue = lstarFromY(darkY) - 0.4;
                if (returnValue < 0 || returnValue > 100) return -1;
                return returnValue;
            },
            lighterUnsafe(tone, ratio) { const lighterSafe = Contrast.lighter(tone, ratio); return lighterSafe < 0.0 ? 100.0 : lighterSafe; },
            darkerUnsafe(tone, ratio) { const darkerSafe = Contrast.darker(tone, ratio); return darkerSafe < 0.0 ? 0.0 : darkerSafe; }
        };

        // ---------- dislike/dislike_analyzer.ts (main: a tone under 65 is disliked; part 1 keeps Android 14's 70). Only spec 2021's
        // Fidelity and Content reach it (their tertiary palette and tertiary container) ----------
        const DislikeAnalyzer = {
            isDisliked(hct) {
                const huePasses = Math.round(hct.hue) >= 90.0 && Math.round(hct.hue) <= 111.0;
                const chromaPasses = Math.round(hct.chroma) > 16.0;
                const tonePasses = Math.round(hct.tone) < 65.0;
                return huePasses && chromaPasses && tonePasses;
            },
            fixIfDisliked(hct) { return DislikeAnalyzer.isDisliked(hct) ? Hct.from(hct.hue, hct.chroma, 70.0) : hct; }
        };

        // ---------- temperature/temperature_cache.ts: the warm/cold order of the hues at the input's chroma and tone, for the
        // tertiary palette of spec 2021's Fidelity (the complement) and Content (the third of three analogous colours). The same
        // algorithm as part 1's TemperatureCache (Android 14's Java), on this part's Hct; the temperature's chroma takes
        // Math.sqrt(a * a + b * b), part 1's Math.hypot ----------
        class TemperatureCache {
            constructor(input) {
                this.input = input;
                this.hctsByTempCache = []; this.hctsByHueCache = []; this.tempsByHctCache = new Map();
                this.inputRelativeTemperatureCache = -1.0; this.complementCache = null;
            }
            // the 361 hues and the input, coldest first (a stable sort)
            get hctsByTemp() {
                if (this.hctsByTempCache.length > 0) return this.hctsByTempCache;
                const hcts = this.hctsByHue.concat([this.input]);
                const temperaturesByHct = this.tempsByHct;
                hcts.sort((a, b) => temperaturesByHct.get(a) - temperaturesByHct.get(b));
                this.hctsByTempCache = hcts;
                return hcts;
            }
            get warmest() { return this.hctsByTemp[this.hctsByTemp.length - 1]; }
            get coldest() { return this.hctsByTemp[0]; }
            // count colours analogous to the input, from a circle of divisions colours spaced by equal steps of temperature
            analogous(count, divisions) {
                if (count === undefined) count = 5;
                if (divisions === undefined) divisions = 12;
                const startHue = Math.round(this.input.hue);
                const startHct = this.hctsByHue[startHue];
                let lastTemp = this.relativeTemperature(startHct);
                const allColors = [startHct];
                let absoluteTotalTempDelta = 0.0;
                for (let i = 0; i < 360; i++) {
                    const hue = sanitizeDegreesInt(startHue + i);
                    const hct = this.hctsByHue[hue];
                    const temp = this.relativeTemperature(hct);
                    const tempDelta = Math.abs(temp - lastTemp);
                    lastTemp = temp;
                    absoluteTotalTempDelta += tempDelta;
                }
                let hueAddend = 1;
                const tempStep = absoluteTotalTempDelta / divisions;
                let totalTempDelta = 0.0;
                lastTemp = this.relativeTemperature(startHct);
                while (allColors.length < divisions) {
                    const hue = sanitizeDegreesInt(startHue + hueAddend);
                    const hct = this.hctsByHue[hue];
                    const temp = this.relativeTemperature(hct);
                    const tempDelta = Math.abs(temp - lastTemp);
                    totalTempDelta += tempDelta;
                    const desiredTotalTempDeltaForIndex = allColors.length * tempStep;
                    let indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
                    let indexAddend = 1;
                    // a hue is added again while its temperature still reaches the next step (white and black have no analogues)
                    while (indexSatisfied && allColors.length < divisions) {
                        allColors.push(hct);
                        const desiredTotalTempDeltaForIndex = (allColors.length + indexAddend) * tempStep;
                        indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
                        indexAddend++;
                    }
                    lastTemp = temp;
                    hueAddend++;
                    if (hueAddend > 360) {
                        while (allColors.length < divisions) allColors.push(hct);
                        break;
                    }
                }
                const answers = [this.input];
                const increaseHueCount = Math.floor((count - 1) / 2.0);
                for (let i = 1; i < (increaseHueCount + 1); i++) {
                    let index = 0 - i;
                    while (index < 0) index = allColors.length + index;
                    if (index >= allColors.length) index = index % allColors.length;
                    answers.splice(0, 0, allColors[index]);
                }
                const decreaseHueCount = count - increaseHueCount - 1;
                for (let i = 1; i < (decreaseHueCount + 1); i++) {
                    let index = i;
                    while (index < 0) index = allColors.length + index;
                    if (index >= allColors.length) index = index % allColors.length;
                    answers.push(allColors[index]);
                }
                return answers;
            }
            // the colour on the other side of the warm/cold divide, nearest the input's inverse relative temperature
            get complement() {
                if (this.complementCache != null) return this.complementCache;
                const coldestHue = this.coldest.hue, coldestTemp = this.tempsByHct.get(this.coldest);
                const warmestHue = this.warmest.hue, warmestTemp = this.tempsByHct.get(this.warmest);
                const range = warmestTemp - coldestTemp;
                const startHueIsColdestToWarmest = TemperatureCache.isBetween(this.input.hue, coldestHue, warmestHue);
                const startHue = startHueIsColdestToWarmest ? warmestHue : coldestHue;
                const endHue = startHueIsColdestToWarmest ? coldestHue : warmestHue;
                const directionOfRotation = 1.0;
                let smallestError = 1000.0;
                let answer = this.hctsByHue[Math.round(this.input.hue)];
                const complementRelativeTemp = 1.0 - this.inputRelativeTemperature;
                for (let hueAddend = 0.0; hueAddend <= 360.0; hueAddend += 1.0) {
                    const hue = sanitizeDegreesDouble(startHue + directionOfRotation * hueAddend);
                    if (!TemperatureCache.isBetween(hue, startHue, endHue)) continue;
                    const possibleAnswer = this.hctsByHue[Math.round(hue)];
                    const relativeTemp = (this.tempsByHct.get(possibleAnswer) - coldestTemp) / range;
                    const error = Math.abs(complementRelativeTemp - relativeTemp);
                    if (error < smallestError) { smallestError = error; answer = possibleAnswer; }
                }
                this.complementCache = answer;
                return this.complementCache;
            }
            relativeTemperature(hct) {
                const range = this.tempsByHct.get(this.warmest) - this.tempsByHct.get(this.coldest);
                const differenceFromColdest = this.tempsByHct.get(hct) - this.tempsByHct.get(this.coldest);
                if (range === 0.0) return 0.5;        // no spread (at T100 there is only white)
                return differenceFromColdest / range;
            }
            get inputRelativeTemperature() {
                if (this.inputRelativeTemperatureCache >= 0.0) return this.inputRelativeTemperatureCache;
                this.inputRelativeTemperatureCache = this.relativeTemperature(this.input);
                return this.inputRelativeTemperatureCache;
            }
            get tempsByHct() {
                if (this.tempsByHctCache.size > 0) return this.tempsByHctCache;
                const allHcts = this.hctsByHue.concat([this.input]);
                const temperaturesByHct = new Map();
                for (const e of allHcts) temperaturesByHct.set(e, TemperatureCache.rawTemperature(e));
                this.tempsByHctCache = temperaturesByHct;
                return temperaturesByHct;
            }
            // hues 0 to 360 at the input's chroma and tone (361 colours; 0 and 360 are the same hue)
            get hctsByHue() {
                if (this.hctsByHueCache.length > 0) return this.hctsByHueCache;
                const hcts = [];
                for (let hue = 0.0; hue <= 360.0; hue += 1.0) hcts.push(Hct.from(hue, this.input.chroma, this.input.tone));
                this.hctsByHueCache = hcts;
                return this.hctsByHueCache;
            }
            static isBetween(angle, a, b) {
                if (a < b) return a <= angle && angle <= b;
                return a <= angle || angle <= b;
            }
            static rawTemperature(color) {
                const lab = labFromArgb(color.toInt());
                const hue = sanitizeDegreesDouble(Math.atan2(lab[2], lab[1]) * 180.0 / Math.PI);
                const chroma = Math.sqrt((lab[1] * lab[1]) + (lab[2] * lab[2]));
                return -0.5 + 0.02 * Math.pow(chroma, 1.07) * Math.cos(sanitizeDegreesDouble(hue - 50.0) * Math.PI / 180.0);
            }
        }

        // ---------- palettes/tonal_palette.ts: the key colour by bisection around T50 (part 1: Android 14's linear walk), tone 99 of a
        // yellow as the average of tones 98 and 100 (part 1: no averaging) ----------
        class TonalPalette {
            constructor(hue, chroma, keyColor) { this.hue = hue; this.chroma = chroma; this.keyColor = keyColor; this.cache = new Map(); }
            static fromInt(argb) { return TonalPalette.fromHct(Hct.fromInt(argb)); }
            static fromHct(hct) { return new TonalPalette(hct.hue, hct.chroma, hct); }
            // (while a P15 scheme at 2021 builds its palettes, keyColorWalk is set: libmonet android15-release's linear walk, keyColorA15)
            static fromHueAndChroma(hue, chroma) { return new TonalPalette(hue, chroma, keyColorWalk ? keyColorA15(hue, chroma) : new KeyColor(hue, chroma).create()); }
            tone(tone) {
                let argb = this.cache.get(tone);
                if (argb === undefined) {
                    if (tone === 99 && Hct.isYellow(this.hue)) argb = this.averageArgb(this.tone(98), this.tone(100));
                    else argb = Hct.from(this.hue, this.chroma, tone).toInt();
                    this.cache.set(tone, argb);
                }
                return argb;
            }
            getHct(tone) { return Hct.fromInt(this.tone(tone)); }
            averageArgb(argb1, argb2) {
                const red1 = (argb1 >>> 16) & 0xff, green1 = (argb1 >>> 8) & 0xff, blue1 = argb1 & 0xff;
                const red2 = (argb2 >>> 16) & 0xff, green2 = (argb2 >>> 8) & 0xff, blue2 = argb2 & 0xff;
                const red = Math.round((red1 + red2) / 2), green = Math.round((green1 + green2) / 2), blue = Math.round((blue1 + blue2) / 2);
                return (255 << 24 | (red & 255) << 16 | (green & 255) << 8 | (blue & 255)) >>> 0;
            }
        }
        class KeyColor {
            constructor(hue, requestedChroma) { this.hue = hue; this.requestedChroma = requestedChroma; this.chromaCache = new Map(); this.maxChromaValue = 200.0; }
            // the first tone, searched from T50, whose maximum chroma reaches the requested chroma
            create() {
                const pivotTone = 50, toneStepSize = 1, epsilon = 0.01;
                let lowerTone = 0, upperTone = 100;
                while (lowerTone < upperTone) {
                    const midTone = Math.floor((lowerTone + upperTone) / 2);
                    const isAscending = this.maxChroma(midTone) < this.maxChroma(midTone + toneStepSize);
                    const sufficientChroma = this.maxChroma(midTone) >= this.requestedChroma - epsilon;
                    if (sufficientChroma) {
                        if (Math.abs(lowerTone - pivotTone) < Math.abs(upperTone - pivotTone)) upperTone = midTone;
                        else {
                            if (lowerTone === midTone) return Hct.from(this.hue, this.requestedChroma, lowerTone);
                            lowerTone = midTone;
                        }
                    } else if (isAscending) lowerTone = midTone + toneStepSize;
                    else upperTone = midTone;
                }
                return Hct.from(this.hue, this.requestedChroma, lowerTone);
            }
            maxChroma(tone) {
                if (this.chromaCache.has(tone)) return this.chromaCache.get(tone);
                const chroma = Hct.from(this.hue, this.maxChromaValue, tone).chroma;
                this.chromaCache.set(tone, chroma);
                return chroma;
            }
        }
        // ---------- AOSP external/libmonet palettes/TonalPalette.java, android15-release :59-60, :71-100 (Android 15.0, P15) ----------
        // createKeyColor: the key colour by a linear walk out from T50 (the tone of T50 +/- 1, 2, ... 49 whose chroma is nearest the one
        // asked for, stopping once the rounded chromas agree), Android 14's search (part 1's TonalPalette.createKeyColor) on this part's
        // Hct. From android15-qpr1-release on, libmonet takes KeyColor's bisection above. keyColorWalk is true only while a P15 scheme at
        // spec 2021 builds its palettes (DynamicScheme below); TonalPaletteA15 is the same factory for the palettes the API builds for
        // such a scheme (the accents and the Intensity sliders)
        let keyColorWalk = false;
        function keyColorA15(hue, chroma) {
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
        const TonalPaletteA15 = { fromHueAndChroma(hue, chroma) { return new TonalPalette(hue, chroma, keyColorA15(hue, chroma)); } };

        // ---------- dynamiccolor/contrast_curve.ts: a value for the contrast levels -1, 0, 0.5 and 1 ----------
        class ContrastCurve {
            constructor(low, normal, medium, high) { this.low = low; this.normal = normal; this.medium = medium; this.high = high; }
            get(contrastLevel) {
                if (contrastLevel <= -1.0) return this.low;
                if (contrastLevel < 0.0) return lerp(this.low, this.normal, (contrastLevel - (-1)) / 1);
                if (contrastLevel < 0.5) return lerp(this.normal, this.medium, (contrastLevel - 0) / 0.5);
                if (contrastLevel < 1.0) return lerp(this.medium, this.high, (contrastLevel - 0.5) / 0.5);
                return this.high;
            }
        }

        // ---------- dynamiccolor/tone_delta_pair.ts: polarity 'darker' | 'lighter' | 'nearer' | 'farther' | 'relative_darker' | 'relative_lighter', constraint 'exact' | 'nearer' | 'farther' ----------
        class ToneDeltaPair {
            constructor(roleA, roleB, delta, polarity, stayTogether, constraint) {
                this.roleA = roleA; this.roleB = roleB; this.delta = delta; this.polarity = polarity; this.stayTogether = stayTogether;
                this.constraint = constraint === undefined ? 'exact' : constraint;
            }
        }

        // ---------- dynamiccolor/dynamic_color.ts ----------
        function validateExtendedColor(originalColor, specVersion, extendedColor) {
            if (originalColor.name !== extendedColor.name) throw new Error('Attempting to extend color ' + originalColor.name + ' with color ' + extendedColor.name + ' of different name for spec version ' + specVersion + '.');
            if (originalColor.isBackground !== extendedColor.isBackground) throw new Error('Attempting to extend color ' + originalColor.name + ' as a ' + (originalColor.isBackground ? 'background' : 'foreground') + ' with color ' + extendedColor.name + ' as a ' + (extendedColor.isBackground ? 'background' : 'foreground') + ' for spec version ' + specVersion + '.');
        }
        // the original colour for a scheme below specVersion, the extended one from specVersion on (the spec strings compare as '2021' < '2025' < '2026')
        function extendSpecVersion(originalColor, specVersion, extendedColor) {
            validateExtendedColor(originalColor, specVersion, extendedColor);
            const pick = s => s.specVersion >= specVersion ? extendedColor : originalColor;
            return DynamicColor.fromPalette({
                name: originalColor.name,
                palette: s => pick(s).palette(s),
                tone: s => pick(s).tone(s),
                isBackground: originalColor.isBackground,
                chromaMultiplier: s => { const chromaMultiplier = pick(s).chromaMultiplier; return chromaMultiplier !== undefined ? chromaMultiplier(s) : 1; },
                background: s => { const background = pick(s).background; return background !== undefined ? background(s) : undefined; },
                secondBackground: s => { const secondBackground = pick(s).secondBackground; return secondBackground !== undefined ? secondBackground(s) : undefined; },
                contrastCurve: s => { const contrastCurve = pick(s).contrastCurve; return contrastCurve !== undefined ? contrastCurve(s) : undefined; },
                toneDeltaPair: s => { const toneDeltaPair = pick(s).toneDeltaPair; return toneDeltaPair !== undefined ? toneDeltaPair(s) : undefined; }
            });
        }
        class DynamicColor {
            static fromPalette(args) {
                return new DynamicColor(args.name === undefined ? '' : args.name, args.palette,
                    args.tone === undefined ? DynamicColor.getInitialToneFromBackground(args.background) : args.tone,
                    args.isBackground === undefined ? false : args.isBackground,
                    args.chromaMultiplier, args.background, args.secondBackground, args.contrastCurve, args.toneDeltaPair);
            }
            static getInitialToneFromBackground(background) {
                if (background === undefined) return s => 50;
                return s => background(s) ? background(s).getTone(s) : 50;
            }
            constructor(name, palette, tone, isBackground, chromaMultiplier, background, secondBackground, contrastCurve, toneDeltaPair) {
                this.name = name; this.palette = palette; this.tone = tone; this.isBackground = isBackground; this.chromaMultiplier = chromaMultiplier;
                this.background = background; this.secondBackground = secondBackground; this.contrastCurve = contrastCurve; this.toneDeltaPair = toneDeltaPair;
                this.hctCache = new Map();
                if (!background && secondBackground) throw new Error('Color ' + name + ' has secondBackground defined, but background is not defined.');
                if (!background && contrastCurve) throw new Error('Color ' + name + ' has contrastCurve defined, but background is not defined.');
                if (background && !contrastCurve) throw new Error('Color ' + name + ' has background defined, but contrastCurve is not defined.');
            }
            clone() {
                return DynamicColor.fromPalette({ name: this.name, palette: this.palette, tone: this.tone, isBackground: this.isBackground, chromaMultiplier: this.chromaMultiplier,
                    background: this.background, secondBackground: this.secondBackground, contrastCurve: this.contrastCurve, toneDeltaPair: this.toneDeltaPair });
            }
            getArgb(scheme) { return this.getHct(scheme).toInt(); }
            getHct(scheme) {
                const cachedAnswer = this.hctCache.get(scheme);
                if (cachedAnswer != null) return cachedAnswer;
                const answer = getCalculationSpec(scheme.specVersion).getHct(scheme, this);
                if (this.hctCache.size > 4) this.hctCache.clear();
                this.hctCache.set(scheme, answer);
                return answer;
            }
            getTone(scheme) { return getCalculationSpec(scheme.specVersion).getTone(scheme, this); }
            static foregroundTone(bgTone, ratio) {
                const lighterTone = Contrast.lighterUnsafe(bgTone, ratio), darkerTone = Contrast.darkerUnsafe(bgTone, ratio);
                const lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone), darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone);
                const preferLighter = DynamicColor.tonePrefersLightForeground(bgTone);
                if (preferLighter) {
                    const negligibleDifference = Math.abs(lighterRatio - darkerRatio) < 0.1 && lighterRatio < ratio && darkerRatio < ratio;
                    return lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference ? lighterTone : darkerTone;
                }
                return darkerRatio >= ratio || darkerRatio >= lighterRatio ? darkerTone : lighterTone;
            }
            static tonePrefersLightForeground(tone) { return Math.round(tone) < 60.0; }
            static toneAllowsLightForeground(tone) { return Math.round(tone) <= 49.0; }
            static enableLightForeground(tone) { return DynamicColor.tonePrefersLightForeground(tone) && !DynamicColor.toneAllowsLightForeground(tone) ? 49.0 : tone; }
        }
        // the last case of both delegates' getTone: a colour with two backgrounds
        function dualBackgroundTone(scheme, color, answer, desiredRatio) {
            const bgTone1 = color.background(scheme).getTone(scheme), bgTone2 = color.secondBackground(scheme).getTone(scheme);
            const upper = Math.max(bgTone1, bgTone2), lower = Math.min(bgTone1, bgTone2);
            if (Contrast.ratioOfTones(upper, answer) >= desiredRatio && Contrast.ratioOfTones(lower, answer) >= desiredRatio) return answer;
            const lightOption = Contrast.lighter(upper, desiredRatio), darkOption = Contrast.darker(lower, desiredRatio);
            const availables = [];
            if (lightOption !== -1) availables.push(lightOption);
            if (darkOption !== -1) availables.push(darkOption);
            const prefersLight = DynamicColor.tonePrefersLightForeground(bgTone1) || DynamicColor.tonePrefersLightForeground(bgTone2);
            if (prefersLight) return lightOption < 0 ? 100 : lightOption;
            if (availables.length === 1) return availables[0];
            return darkOption < 0 ? 0 : darkOption;
        }
        // libmonet (P16Q1, P16Q2): ColorSpec2025.getHct and ColorSpec2021.getHct (through libmonet's TonalPalette.getHct(double)) are
        // both Hct.from(hue, chroma, tone): no averaging of a yellow's tone 99 (upstream's main averages it, through palette.getHct).
        // On a palette the Intensity sliders or the Accurate switch wrapped, the colour at the palette's mapped tone, as the multiplier path
        function libmonetHct(palette, chroma, tone) { return Hct.from(palette.hue, chroma, palette.mapTone ? palette.mapTone(tone) : tone); }
        // ColorCalculationDelegateImpl2021: spec 2021's tone rules (a pair solved nearer/farther, the awkward zone T50-59 avoided)
        const ColorCalculationDelegateImpl2021 = {
            getHct(scheme, color) {
                const tone = color.getTone(scheme), palette = color.palette(scheme);
                if (scheme.libmonet) return libmonetHct(palette, palette.chroma, tone);
                return palette.getHct(tone);
            },
            getTone(scheme, color) {
                const decreasingContrast = scheme.contrastLevel < 0;
                const toneDeltaPair = color.toneDeltaPair ? color.toneDeltaPair(scheme) : undefined;
                if (toneDeltaPair) {
                    const roleA = toneDeltaPair.roleA, roleB = toneDeltaPair.roleB, delta = toneDeltaPair.delta, polarity = toneDeltaPair.polarity, stayTogether = toneDeltaPair.stayTogether;
                    // (libmonet's ColorSpec2021.getTone, every branch, reads 'darker' as nearer in light mode, upstream's TypeScript in dark mode.
                    // No 2021 role has a 'darker' pair; the 2025 dim colours do, and libmonet a17 writes them on schemes that fall back to 2021)
                    const aIsNearer = polarity === 'nearer' || (polarity === 'lighter' && !scheme.isDark) || (polarity === 'darker' && (scheme.libmonet ? !scheme.isDark : scheme.isDark));
                    const nearer = aIsNearer ? roleA : roleB, farther = aIsNearer ? roleB : roleA;
                    const amNearer = color.name === nearer.name;
                    const expansionDir = scheme.isDark ? 1 : -1;
                    let nTone = nearer.tone(scheme), fTone = farther.tone(scheme);
                    if (color.background && nearer.contrastCurve && farther.contrastCurve) {
                        const bg = color.background(scheme), nContrastCurve = nearer.contrastCurve(scheme), fContrastCurve = farther.contrastCurve(scheme);
                        if (bg && nContrastCurve && fContrastCurve) {
                            const bgTone = bg.getTone(scheme);
                            const nContrast = nContrastCurve.get(scheme.contrastLevel), fContrast = fContrastCurve.get(scheme.contrastLevel);
                            if (Contrast.ratioOfTones(bgTone, nTone) < nContrast) nTone = DynamicColor.foregroundTone(bgTone, nContrast);
                            if (Contrast.ratioOfTones(bgTone, fTone) < fContrast) fTone = DynamicColor.foregroundTone(bgTone, fContrast);
                            if (decreasingContrast) { nTone = DynamicColor.foregroundTone(bgTone, nContrast); fTone = DynamicColor.foregroundTone(bgTone, fContrast); }
                        }
                    }
                    if ((fTone - nTone) * expansionDir < delta) {
                        fTone = clampDouble(0, 100, nTone + delta * expansionDir);
                        if (!((fTone - nTone) * expansionDir >= delta)) nTone = clampDouble(0, 100, fTone - delta * expansionDir);
                    }
                    if (50 <= nTone && nTone < 60) {
                        if (expansionDir > 0) { nTone = 60; fTone = Math.max(fTone, nTone + delta * expansionDir); }
                        else { nTone = 49; fTone = Math.min(fTone, nTone + delta * expansionDir); }
                    } else if (50 <= fTone && fTone < 60) {
                        if (stayTogether) {
                            if (expansionDir > 0) { nTone = 60; fTone = Math.max(fTone, nTone + delta * expansionDir); }
                            else { nTone = 49; fTone = Math.min(fTone, nTone + delta * expansionDir); }
                        } else fTone = expansionDir > 0 ? 60 : 49;
                    }
                    return amNearer ? nTone : fTone;
                }
                let answer = color.tone(scheme);
                if (!color.background || color.background(scheme) === undefined || !color.contrastCurve || color.contrastCurve(scheme) === undefined) return answer;
                const bgTone = color.background(scheme).getTone(scheme);
                const desiredRatio = color.contrastCurve(scheme).get(scheme.contrastLevel);
                if (!(Contrast.ratioOfTones(bgTone, answer) >= desiredRatio)) answer = DynamicColor.foregroundTone(bgTone, desiredRatio);
                if (decreasingContrast) answer = DynamicColor.foregroundTone(bgTone, desiredRatio);
                if (color.isBackground && 50 <= answer && answer < 60) answer = Contrast.ratioOfTones(49, bgTone) >= desiredRatio ? 49 : 60;
                if (!color.secondBackground || color.secondBackground(scheme) === undefined) return answer;
                return dualBackgroundTone(scheme, color, answer, desiredRatio);
            }
        };
        // ColorCalculationDelegateImpl2025: the chroma multiplier, the pair constraint exact/nearer/farther, backgrounds kept out of T50-64 (phone)
        const ColorCalculationDelegateImpl2025 = {
            getHct(scheme, color) {
                const palette = color.palette(scheme), tone = color.getTone(scheme);
                const multiplier = color.chromaMultiplier ? color.chromaMultiplier(scheme) : 1;
                if (scheme.libmonet) return libmonetHct(palette, palette.chroma * multiplier, tone);
                if (multiplier === 1) return palette.getHct(tone);
                const chroma = palette.chroma * multiplier;
                // the Intensity sliders (AdjustedPalette, in the API part; upstream has no such step): the colour at the palette's mapped tone
                const at = palette.mapTone ? palette.mapTone(tone) : tone;
                if (at === 99 && Hct.isYellow(palette.hue)) return TonalPalette.fromHueAndChroma(palette.hue, chroma).getHct(at);
                return Hct.from(palette.hue, chroma, at);
            },
            getTone(scheme, color) {
                const toneDeltaPair = color.toneDeltaPair ? color.toneDeltaPair(scheme) : undefined;
                if (toneDeltaPair) {
                    const roleA = toneDeltaPair.roleA, roleB = toneDeltaPair.roleB, polarity = toneDeltaPair.polarity, constraint = toneDeltaPair.constraint;
                    const absoluteDelta = polarity === 'darker' || (polarity === 'relative_lighter' && scheme.isDark) || (polarity === 'relative_darker' && !scheme.isDark) ? -toneDeltaPair.delta : toneDeltaPair.delta;
                    const amRoleA = color.name === roleA.name;
                    const selfRole = amRoleA ? roleA : roleB, refRole = amRoleA ? roleB : roleA;
                    let selfTone = selfRole.tone(scheme);
                    const refTone = refRole.getTone(scheme);
                    const relativeDelta = absoluteDelta * (amRoleA ? 1 : -1);
                    if (constraint === 'exact') selfTone = clampDouble(0, 100, refTone + relativeDelta);
                    else if (constraint === 'nearer') {
                        if (relativeDelta > 0) selfTone = clampDouble(0, 100, clampDouble(refTone, refTone + relativeDelta, selfTone));
                        else selfTone = clampDouble(0, 100, clampDouble(refTone + relativeDelta, refTone, selfTone));
                    } else if (constraint === 'farther') {
                        if (relativeDelta > 0) selfTone = clampDouble(refTone + relativeDelta, 100, selfTone);
                        else selfTone = clampDouble(0, refTone + relativeDelta, selfTone);
                    }
                    if (color.background && color.contrastCurve) {
                        const background = color.background(scheme), contrastCurve = color.contrastCurve(scheme);
                        if (background && contrastCurve) {
                            const bgTone = background.getTone(scheme), selfContrast = contrastCurve.get(scheme.contrastLevel);
                            selfTone = Contrast.ratioOfTones(bgTone, selfTone) >= selfContrast && scheme.contrastLevel >= 0 ? selfTone : DynamicColor.foregroundTone(bgTone, selfContrast);
                        }
                    }
                    // (phone) a background avoids the awkward tones 50-64; the accent fixed dim colours are not adjusted
                    if (color.isBackground && !color.name.endsWith('_fixed_dim')) selfTone = selfTone >= 57 ? clampDouble(65, 100, selfTone) : clampDouble(0, 49, selfTone);
                    return selfTone;
                }
                let answer = color.tone(scheme);
                if (!color.background || color.background(scheme) === undefined || !color.contrastCurve || color.contrastCurve(scheme) === undefined) return answer;
                const bgTone = color.background(scheme).getTone(scheme);
                const desiredRatio = color.contrastCurve(scheme).get(scheme.contrastLevel);
                answer = Contrast.ratioOfTones(bgTone, answer) >= desiredRatio && scheme.contrastLevel >= 0 ? answer : DynamicColor.foregroundTone(bgTone, desiredRatio);
                if (color.isBackground && !color.name.endsWith('_fixed_dim')) answer = answer >= 57 ? clampDouble(65, 100, answer) : clampDouble(0, 49, answer);
                if (!color.secondBackground || color.secondBackground(scheme) === undefined) return answer;
                return dualBackgroundTone(scheme, color, answer, desiredRatio);
            }
        };
        // dynamic_color.ts getSpec
        function getCalculationSpec(specVersion) { return specVersion === '2021' ? ColorCalculationDelegateImpl2021 : ColorCalculationDelegateImpl2025; }

        // ---------- dynamiccolor/color_spec_2021.ts ----------
        const FP = args => DynamicColor.fromPalette(args);
        const CC = (low, normal, medium, high) => new ContrastCurve(low, normal, medium, high);
        const TDP = (roleA, roleB, delta, polarity, stayTogether, constraint) => new ToneDeltaPair(roleA, roleB, delta, polarity, stayTogether, constraint);
        function isFidelity(scheme) { return scheme.variant === Variant.FIDELITY || scheme.variant === Variant.CONTENT; }
        function isMonochrome(scheme) { return scheme.variant === Variant.MONOCHROME; }
        // on_primary_container, on_secondary_container, on_tertiary_container and on_error_container at 2021: light tone 30 (Monochrome's
        // own tones apart) and curve (3, 4.5, 7, 11). A P15 scheme (scheme.a15: libmonet android15-release, MaterialDynamicColors.java
        // :426-444, :517-532, :595-613, :657-667): light tone 10 and curve (4.5, 7, 11, 21); android15-qpr1-release made them upstream's
        function onContainerLightTone(scheme) { return scheme.a15 ? 10 : 30; }
        function onContainerCurve(scheme) { return scheme.a15 ? CC(4.5, 7, 11, 21) : CC(3, 4.5, 7, 11); }
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
        class ColorSpecDelegateImpl2021 {
            primaryPaletteKeyColor() { return FP({ name: 'primary_palette_key_color', palette: s => s.primaryPalette, tone: s => s.primaryPalette.keyColor.tone }); }
            secondaryPaletteKeyColor() { return FP({ name: 'secondary_palette_key_color', palette: s => s.secondaryPalette, tone: s => s.secondaryPalette.keyColor.tone }); }
            tertiaryPaletteKeyColor() { return FP({ name: 'tertiary_palette_key_color', palette: s => s.tertiaryPalette, tone: s => s.tertiaryPalette.keyColor.tone }); }
            neutralPaletteKeyColor() { return FP({ name: 'neutral_palette_key_color', palette: s => s.neutralPalette, tone: s => s.neutralPalette.keyColor.tone }); }
            neutralVariantPaletteKeyColor() { return FP({ name: 'neutral_variant_palette_key_color', palette: s => s.neutralVariantPalette, tone: s => s.neutralVariantPalette.keyColor.tone }); }
            errorPaletteKeyColor() { return FP({ name: 'error_palette_key_color', palette: s => s.errorPalette, tone: s => s.errorPalette.keyColor.tone }); }
            background() { return FP({ name: 'background', palette: s => s.neutralPalette, tone: s => s.isDark ? 6 : 98, isBackground: true }); }
            onBackground() { return FP({ name: 'on_background', palette: s => s.neutralPalette, tone: s => s.isDark ? 90 : 10, background: s => this.background(), contrastCurve: s => CC(3, 3, 4.5, 7) }); }
            surface() { return FP({ name: 'surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 6 : 98, isBackground: true }); }
            surfaceDim() { return FP({ name: 'surface_dim', palette: s => s.neutralPalette, tone: s => s.isDark ? 6 : CC(87, 87, 80, 75).get(s.contrastLevel), isBackground: true }); }
            surfaceBright() { return FP({ name: 'surface_bright', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(24, 24, 29, 34).get(s.contrastLevel) : 98, isBackground: true }); }
            surfaceContainerLowest() { return FP({ name: 'surface_container_lowest', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(4, 4, 2, 0).get(s.contrastLevel) : 100, isBackground: true }); }
            surfaceContainerLow() { return FP({ name: 'surface_container_low', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(10, 10, 11, 12).get(s.contrastLevel) : CC(96, 96, 96, 95).get(s.contrastLevel), isBackground: true }); }
            surfaceContainer() { return FP({ name: 'surface_container', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(12, 12, 16, 20).get(s.contrastLevel) : CC(94, 94, 92, 90).get(s.contrastLevel), isBackground: true }); }
            surfaceContainerHigh() { return FP({ name: 'surface_container_high', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(17, 17, 21, 25).get(s.contrastLevel) : CC(92, 92, 88, 85).get(s.contrastLevel), isBackground: true }); }
            surfaceContainerHighest() { return FP({ name: 'surface_container_highest', palette: s => s.neutralPalette, tone: s => s.isDark ? CC(22, 22, 26, 30).get(s.contrastLevel) : CC(90, 90, 84, 80).get(s.contrastLevel), isBackground: true }); }
            onSurface() { return FP({ name: 'on_surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 90 : 10, background: s => this.highestSurface(s), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            surfaceVariant() { return FP({ name: 'surface_variant', palette: s => s.neutralVariantPalette, tone: s => s.isDark ? 30 : 90, isBackground: true }); }
            onSurfaceVariant() { return FP({ name: 'on_surface_variant', palette: s => s.neutralVariantPalette, tone: s => s.isDark ? 80 : 30, background: s => this.highestSurface(s), contrastCurve: s => CC(3, 4.5, 7, 11) }); }
            inverseSurface() { return FP({ name: 'inverse_surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 90 : 20, isBackground: true }); }
            inverseOnSurface() { return FP({ name: 'inverse_on_surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 20 : 95, background: s => this.inverseSurface(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            outline() { return FP({ name: 'outline', palette: s => s.neutralVariantPalette, tone: s => s.isDark ? 60 : 50, background: s => this.highestSurface(s), contrastCurve: s => CC(1.5, 3, 4.5, 7) }); }
            outlineVariant() { return FP({ name: 'outline_variant', palette: s => s.neutralVariantPalette, tone: s => s.isDark ? 30 : 80, background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5) }); }
            shadow() { return FP({ name: 'shadow', palette: s => s.neutralPalette, tone: s => 0 }); }
            scrim() { return FP({ name: 'scrim', palette: s => s.neutralPalette, tone: s => 0 }); }
            surfaceTint() { return FP({ name: 'surface_tint', palette: s => s.primaryPalette, tone: s => s.isDark ? 80 : 40, isBackground: true }); }
            primary() {
                return FP({ name: 'primary', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? (s.isDark ? 100 : 0) : (s.isDark ? 80 : 40), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(3, 4.5, 7, 7), toneDeltaPair: s => TDP(this.primaryContainer(), this.primary(), 10, 'nearer', false) });
            }
            primaryDim() { return undefined; }     // the dim colours start in 2025
            onPrimary() { return FP({ name: 'on_primary', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? (s.isDark ? 10 : 90) : (s.isDark ? 20 : 100), background: s => this.primary(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            primaryContainer() {
                return FP({ name: 'primary_container', palette: s => s.primaryPalette,
                    tone: s => isFidelity(s) ? s.sourceColorHct.tone : isMonochrome(s) ? (s.isDark ? 85 : 25) : (s.isDark ? 30 : 90), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.primaryContainer(), this.primary(), 10, 'nearer', false) });
            }
            onPrimaryContainer() {
                return FP({ name: 'on_primary_container', palette: s => s.primaryPalette,
                    tone: s => isFidelity(s) ? DynamicColor.foregroundTone(this.primaryContainer().tone(s), 4.5) : isMonochrome(s) ? (s.isDark ? 0 : 100) : (s.isDark ? 90 : onContainerLightTone(s)),
                    background: s => this.primaryContainer(), contrastCurve: s => onContainerCurve(s) });
            }
            inversePrimary() { return FP({ name: 'inverse_primary', palette: s => s.primaryPalette, tone: s => s.isDark ? 40 : 80, background: s => this.inverseSurface(), contrastCurve: s => CC(3, 4.5, 7, 7) }); }
            secondary() {
                return FP({ name: 'secondary', palette: s => s.secondaryPalette, tone: s => s.isDark ? 80 : 40, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(3, 4.5, 7, 7), toneDeltaPair: s => TDP(this.secondaryContainer(), this.secondary(), 10, 'nearer', false) });
            }
            secondaryDim() { return undefined; }
            onSecondary() { return FP({ name: 'on_secondary', palette: s => s.secondaryPalette, tone: s => isMonochrome(s) ? (s.isDark ? 10 : 100) : (s.isDark ? 20 : 100), background: s => this.secondary(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            secondaryContainer() {
                return FP({ name: 'secondary_container', palette: s => s.secondaryPalette,
                    tone: s => {
                        const initialTone = s.isDark ? 30 : 90;
                        if (isMonochrome(s)) return s.isDark ? 30 : 85;
                        if (!isFidelity(s)) return initialTone;
                        return findDesiredChromaByTone(s.secondaryPalette.hue, s.secondaryPalette.chroma, initialTone, s.isDark ? false : true);
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.secondaryContainer(), this.secondary(), 10, 'nearer', false) });
            }
            onSecondaryContainer() {
                return FP({ name: 'on_secondary_container', palette: s => s.secondaryPalette,
                    tone: s => isMonochrome(s) ? (s.isDark ? 90 : 10) : !isFidelity(s) ? (s.isDark ? 90 : onContainerLightTone(s)) : DynamicColor.foregroundTone(this.secondaryContainer().tone(s), 4.5),
                    background: s => this.secondaryContainer(), contrastCurve: s => onContainerCurve(s) });
            }
            tertiary() {
                return FP({ name: 'tertiary', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? (s.isDark ? 90 : 25) : (s.isDark ? 80 : 40), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(3, 4.5, 7, 7), toneDeltaPair: s => TDP(this.tertiaryContainer(), this.tertiary(), 10, 'nearer', false) });
            }
            tertiaryDim() { return undefined; }
            onTertiary() { return FP({ name: 'on_tertiary', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? (s.isDark ? 10 : 90) : (s.isDark ? 20 : 100), background: s => this.tertiary(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            tertiaryContainer() {
                return FP({ name: 'tertiary_container', palette: s => s.tertiaryPalette,
                    tone: s => {
                        if (isMonochrome(s)) return s.isDark ? 60 : 49;
                        if (!isFidelity(s)) return s.isDark ? 30 : 90;
                        // accurateBase (Accurate shades, in the API part; upstream has no such step): the palette as it was before the switch
                        // (libmonet: its TonalPalette.getHct(double) is Hct.from, with no averaging of a yellow's tone 99)
                        const base = accurateBase(s.tertiaryPalette);
                        const proposedHct = s.libmonet ? libmonetHct(base, base.chroma, s.sourceColorHct.tone) : base.getHct(s.sourceColorHct.tone);
                        return DislikeAnalyzer.fixIfDisliked(proposedHct).tone;
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.tertiaryContainer(), this.tertiary(), 10, 'nearer', false) });
            }
            onTertiaryContainer() {
                return FP({ name: 'on_tertiary_container', palette: s => s.tertiaryPalette,
                    tone: s => isMonochrome(s) ? (s.isDark ? 0 : 100) : !isFidelity(s) ? (s.isDark ? 90 : onContainerLightTone(s)) : DynamicColor.foregroundTone(this.tertiaryContainer().tone(s), 4.5),
                    background: s => this.tertiaryContainer(), contrastCurve: s => onContainerCurve(s) });
            }
            error() {
                return FP({ name: 'error', palette: s => s.errorPalette, tone: s => s.isDark ? 80 : 40, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(3, 4.5, 7, 7), toneDeltaPair: s => TDP(this.errorContainer(), this.error(), 10, 'nearer', false) });
            }
            errorDim() { return undefined; }
            onError() { return FP({ name: 'on_error', palette: s => s.errorPalette, tone: s => s.isDark ? 20 : 100, background: s => this.error(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            errorContainer() {
                return FP({ name: 'error_container', palette: s => s.errorPalette, tone: s => s.isDark ? 30 : 90, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.errorContainer(), this.error(), 10, 'nearer', false) });
            }
            onErrorContainer() { return FP({ name: 'on_error_container', palette: s => s.errorPalette, tone: s => isMonochrome(s) ? (s.isDark ? 90 : 10) : (s.isDark ? 90 : onContainerLightTone(s)), background: s => this.errorContainer(), contrastCurve: s => onContainerCurve(s) }); }
            primaryFixed() {
                return FP({ name: 'primary_fixed', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? 40.0 : 90.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.primaryFixed(), this.primaryFixedDim(), 10, 'lighter', true) });
            }
            primaryFixedDim() {
                return FP({ name: 'primary_fixed_dim', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? 30.0 : 80.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.primaryFixed(), this.primaryFixedDim(), 10, 'lighter', true) });
            }
            onPrimaryFixed() { return FP({ name: 'on_primary_fixed', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? 100.0 : 10.0, background: s => this.primaryFixedDim(), secondBackground: s => this.primaryFixed(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            onPrimaryFixedVariant() { return FP({ name: 'on_primary_fixed_variant', palette: s => s.primaryPalette, tone: s => isMonochrome(s) ? 90.0 : 30.0, background: s => this.primaryFixedDim(), secondBackground: s => this.primaryFixed(), contrastCurve: s => CC(3, 4.5, 7, 11) }); }
            secondaryFixed() {
                return FP({ name: 'secondary_fixed', palette: s => s.secondaryPalette, tone: s => isMonochrome(s) ? 80.0 : 90.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.secondaryFixed(), this.secondaryFixedDim(), 10, 'lighter', true) });
            }
            secondaryFixedDim() {
                return FP({ name: 'secondary_fixed_dim', palette: s => s.secondaryPalette, tone: s => isMonochrome(s) ? 70.0 : 80.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.secondaryFixed(), this.secondaryFixedDim(), 10, 'lighter', true) });
            }
            onSecondaryFixed() { return FP({ name: 'on_secondary_fixed', palette: s => s.secondaryPalette, tone: s => 10.0, background: s => this.secondaryFixedDim(), secondBackground: s => this.secondaryFixed(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            onSecondaryFixedVariant() { return FP({ name: 'on_secondary_fixed_variant', palette: s => s.secondaryPalette, tone: s => isMonochrome(s) ? 25.0 : 30.0, background: s => this.secondaryFixedDim(), secondBackground: s => this.secondaryFixed(), contrastCurve: s => CC(3, 4.5, 7, 11) }); }
            tertiaryFixed() {
                return FP({ name: 'tertiary_fixed', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? 40.0 : 90.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.tertiaryFixed(), this.tertiaryFixedDim(), 10, 'lighter', true) });
            }
            tertiaryFixedDim() {
                return FP({ name: 'tertiary_fixed_dim', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? 30.0 : 80.0, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => CC(1, 1, 3, 4.5), toneDeltaPair: s => TDP(this.tertiaryFixed(), this.tertiaryFixedDim(), 10, 'lighter', true) });
            }
            onTertiaryFixed() { return FP({ name: 'on_tertiary_fixed', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? 100.0 : 10.0, background: s => this.tertiaryFixedDim(), secondBackground: s => this.tertiaryFixed(), contrastCurve: s => CC(4.5, 7, 11, 21) }); }
            onTertiaryFixedVariant() { return FP({ name: 'on_tertiary_fixed_variant', palette: s => s.tertiaryPalette, tone: s => isMonochrome(s) ? 90.0 : 30.0, background: s => this.tertiaryFixedDim(), secondBackground: s => this.tertiaryFixed(), contrastCurve: s => CC(3, 4.5, 7, 11) }); }
            highestSurface(s) { return s.isDark ? this.surfaceBright() : this.surfaceDim(); }
        }

        // ---------- dynamiccolor/color_spec_2025.ts (phone; its mainTone, dimTone, containerTone and the 2025 findDesiredChromaByTone are watch-only) ----------
        // the best tone for a chroma: from tone 100 downwards (tMaxC) or from 0 upwards (tMinC), the first tone where the chroma stops growing
        function findBestToneForChroma(hue, chroma, tone, byDecreasingTone) {
            let answer = tone;
            let bestCandidate = Hct.from(hue, chroma, answer);
            while (bestCandidate.chroma < chroma) {
                if (tone < 0 || tone > 100) break;
                tone += byDecreasingTone ? -1.0 : 1.0;
                const newCandidate = Hct.from(hue, chroma, tone);
                if (bestCandidate.chroma < newCandidate.chroma) { bestCandidate = newCandidate; answer = tone; }
            }
            return answer;
        }
        function tMaxC(palette, lowerBound, upperBound, chromaMultiplier) {
            if (lowerBound === undefined) lowerBound = 0;
            if (upperBound === undefined) upperBound = 100;
            if (chromaMultiplier === undefined) chromaMultiplier = 1;
            const answer = findBestToneForChroma(palette.hue, palette.chroma * chromaMultiplier, 100, true);
            return clampDouble(lowerBound, upperBound, answer);
        }
        function tMinC(palette, lowerBound, upperBound) {
            if (lowerBound === undefined) lowerBound = 0;
            if (upperBound === undefined) upperBound = 100;
            const answer = findBestToneForChroma(palette.hue, palette.chroma, 0, false);
            return clampDouble(lowerBound, upperBound, answer);
        }
        function getCurve(defaultContrast) {
            if (defaultContrast === 1.5) return CC(1.5, 1.5, 3, 5.5);
            if (defaultContrast === 3) return CC(3, 3, 4.5, 7);
            if (defaultContrast === 4.5) return CC(4.5, 4.5, 7, 11);
            if (defaultContrast === 6) return CC(6, 6, 7, 11);
            if (defaultContrast === 7) return CC(7, 7, 11, 21);
            if (defaultContrast === 9) return CC(9, 9, 11, 21);
            if (defaultContrast === 11) return CC(11, 11, 21, 21);
            if (defaultContrast === 21) return CC(21, 21, 21, 21);
            return CC(defaultContrast, defaultContrast, 7, 21);
        }
        // the neutral palette's chroma multiplier of the 2025 surfaces and text (the yellow and the mode decide Expressive's)
        const neutralChromaMultiplier2025 = (s, neutral, tonalSpot, expressive, expressiveYellow, vibrant) => {
            if (s.variant === Variant.NEUTRAL) return neutral;
            if (s.variant === Variant.TONAL_SPOT) return tonalSpot;
            if (s.variant === Variant.EXPRESSIVE) return Hct.isYellow(s.neutralPalette.hue) ? expressiveYellow : expressive;
            if (s.variant === Variant.VIBRANT) return vibrant;
            return 1;
        };
        const textChromaMultiplier2025 = s => {
            if (s.variant === Variant.NEUTRAL) return 2.2;
            if (s.variant === Variant.TONAL_SPOT) return 1.7;
            if (s.variant === Variant.EXPRESSIVE) return Hct.isYellow(s.neutralPalette.hue) ? (s.isDark ? 3.0 : 2.3) : 1.6;
            return 1;
        };
        // a copy of the scheme as light at contrast 0, for the fixed colours (Object.assign keeps the own fields: palettes, variant, specVersion)
        const asLight = s => Object.assign({}, s, { isDark: false, contrastLevel: 0 });
        class ColorSpecDelegateImpl2025 extends ColorSpecDelegateImpl2021 {
            surface() {
                const color2025 = FP({ name: 'surface', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 4 : Hct.isYellow(s.neutralPalette.hue) ? 99 : s.variant === Variant.VIBRANT ? 97 : 98, isBackground: true });
                return extendSpecVersion(super.surface(), '2025', color2025);
            }
            surfaceDim() {
                const color2025 = FP({ name: 'surface_dim', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 4 : Hct.isYellow(s.neutralPalette.hue) ? 90 : s.variant === Variant.VIBRANT ? 85 : 87, isBackground: true,
                    chromaMultiplier: s => !s.isDark ? neutralChromaMultiplier2025(s, 2.5, 1.7, 1.75, 2.7, 1.36) : 1 });
                return extendSpecVersion(super.surfaceDim(), '2025', color2025);
            }
            surfaceBright() {
                const color2025 = FP({ name: 'surface_bright', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 18 : Hct.isYellow(s.neutralPalette.hue) ? 99 : s.variant === Variant.VIBRANT ? 97 : 98, isBackground: true,
                    chromaMultiplier: s => s.isDark ? neutralChromaMultiplier2025(s, 2.5, 1.7, 1.75, 2.7, 1.36) : 1 });
                return extendSpecVersion(super.surfaceBright(), '2025', color2025);
            }
            surfaceContainerLowest() {
                const color2025 = FP({ name: 'surface_container_lowest', palette: s => s.neutralPalette, tone: s => s.isDark ? 0 : 100, isBackground: true });
                return extendSpecVersion(super.surfaceContainerLowest(), '2025', color2025);
            }
            surfaceContainerLow() {
                const color2025 = FP({ name: 'surface_container_low', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 6 : Hct.isYellow(s.neutralPalette.hue) ? 98 : s.variant === Variant.VIBRANT ? 95 : 96, isBackground: true,
                    chromaMultiplier: s => neutralChromaMultiplier2025(s, 1.3, 1.25, 1.15, 1.3, 1.08) });
                return extendSpecVersion(super.surfaceContainerLow(), '2025', color2025);
            }
            surfaceContainer() {
                const color2025 = FP({ name: 'surface_container', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 9 : Hct.isYellow(s.neutralPalette.hue) ? 96 : s.variant === Variant.VIBRANT ? 92 : 94, isBackground: true,
                    chromaMultiplier: s => neutralChromaMultiplier2025(s, 1.6, 1.4, 1.3, 1.6, 1.15) });
                return extendSpecVersion(super.surfaceContainer(), '2025', color2025);
            }
            surfaceContainerHigh() {
                const color2025 = FP({ name: 'surface_container_high', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 12 : Hct.isYellow(s.neutralPalette.hue) ? 94 : s.variant === Variant.VIBRANT ? 90 : 92, isBackground: true,
                    chromaMultiplier: s => neutralChromaMultiplier2025(s, 1.9, 1.5, 1.45, 1.95, 1.22) });
                return extendSpecVersion(super.surfaceContainerHigh(), '2025', color2025);
            }
            surfaceContainerHighest() {
                const color2025 = FP({ name: 'surface_container_highest', palette: s => s.neutralPalette,
                    tone: s => s.isDark ? 15 : Hct.isYellow(s.neutralPalette.hue) ? 92 : s.variant === Variant.VIBRANT ? 88 : 90, isBackground: true,
                    chromaMultiplier: s => neutralChromaMultiplier2025(s, 2.2, 1.7, 1.6, 2.3, 1.29) });
                return extendSpecVersion(super.surfaceContainerHighest(), '2025', color2025);
            }
            onSurface() {
                const color2025 = FP({ name: 'on_surface', palette: s => s.neutralPalette,
                    tone: s => s.variant === Variant.VIBRANT ? tMaxC(s.neutralPalette, 0, 100, 1.1) : DynamicColor.getInitialToneFromBackground(s => this.highestSurface(s))(s),
                    chromaMultiplier: textChromaMultiplier2025, background: s => this.highestSurface(s), contrastCurve: s => s.isDark ? getCurve(11) : getCurve(9) });
                return extendSpecVersion(super.onSurface(), '2025', color2025);
            }
            onSurfaceVariant() {
                const color2025 = FP({ name: 'on_surface_variant', palette: s => s.neutralPalette, chromaMultiplier: textChromaMultiplier2025,
                    background: s => this.highestSurface(s), contrastCurve: s => s.isDark ? getCurve(6) : getCurve(4.5) });
                return extendSpecVersion(super.onSurfaceVariant(), '2025', color2025);
            }
            outline() {
                const color2025 = FP({ name: 'outline', palette: s => s.neutralPalette, chromaMultiplier: textChromaMultiplier2025,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(3) });
                return extendSpecVersion(super.outline(), '2025', color2025);
            }
            outlineVariant() {
                const color2025 = FP({ name: 'outline_variant', palette: s => s.neutralPalette, chromaMultiplier: textChromaMultiplier2025,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(1.5) });
                return extendSpecVersion(super.outlineVariant(), '2025', color2025);
            }
            inverseSurface() {
                const color2025 = FP({ name: 'inverse_surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 98 : 4, isBackground: true });
                return extendSpecVersion(super.inverseSurface(), '2025', color2025);
            }
            inverseOnSurface() {
                const color2025 = FP({ name: 'inverse_on_surface', palette: s => s.neutralPalette, background: s => this.inverseSurface(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.inverseOnSurface(), '2025', color2025);
            }
            primary() {
                const color2025 = FP({ name: 'primary', palette: s => s.primaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? 80 : 40;
                        if (s.variant === Variant.TONAL_SPOT) return s.isDark ? 80 : tMaxC(s.primaryPalette);
                        if (s.variant === Variant.EXPRESSIVE) return tMaxC(s.primaryPalette, 0, s.isDark ? (Hct.isCyan(s.primaryPalette.hue) ? 88 : 98) : (Hct.isYellow(s.primaryPalette.hue) ? 25 : 98));
                        return tMaxC(s.primaryPalette, 0, Hct.isCyan(s.primaryPalette.hue) ? 88 : 98);     // VIBRANT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.primaryContainer(), this.primary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.primary(), '2025', color2025);
            }
            // the dim colours are new in 2025 (not extended: spec 2021 has none). On a phone nothing else refers to them; Android does not write them
            primaryDim() {
                return FP({ name: 'primary_dim', palette: s => s.primaryPalette,
                    tone: s => s.variant === Variant.NEUTRAL ? 85 : s.variant === Variant.TONAL_SPOT ? tMaxC(s.primaryPalette, 0, 90) : tMaxC(s.primaryPalette), isBackground: true,
                    background: s => this.surfaceContainerHigh(), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.primaryDim(), this.primary(), 5, 'darker', true, 'farther') });
            }
            onPrimary() {
                const color2025 = FP({ name: 'on_primary', palette: s => s.primaryPalette, background: s => this.primary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onPrimary(), '2025', color2025);
            }
            primaryContainer() {
                const color2025 = FP({ name: 'primary_container', palette: s => s.primaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? 30 : 90;
                        if (s.variant === Variant.TONAL_SPOT) return s.isDark ? tMinC(s.primaryPalette, 35, 93) : tMaxC(s.primaryPalette, 0, 90);
                        if (s.variant === Variant.EXPRESSIVE) return s.isDark ? tMinC(s.primaryPalette, 30, 93) : tMaxC(s.primaryPalette, 78, Hct.isCyan(s.primaryPalette.hue) ? 88 : 90);
                        return s.isDark ? tMinC(s.primaryPalette, 66, 93) : tMaxC(s.primaryPalette, 66, Hct.isCyan(s.primaryPalette.hue) ? 88 : 93);     // VIBRANT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.primaryContainer(), '2025', color2025);
            }
            onPrimaryContainer() {
                const color2025 = FP({ name: 'on_primary_container', palette: s => s.primaryPalette, background: s => this.primaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onPrimaryContainer(), '2025', color2025);
            }
            primaryFixed() {
                const color2025 = FP({ name: 'primary_fixed', palette: s => s.primaryPalette, tone: s => this.primaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.primaryFixed(), '2025', color2025);
            }
            primaryFixedDim() {
                const color2025 = FP({ name: 'primary_fixed_dim', palette: s => s.primaryPalette, tone: s => this.primaryFixed().getTone(s), isBackground: true,
                    toneDeltaPair: s => TDP(this.primaryFixedDim(), this.primaryFixed(), 5, 'darker', true, 'exact') });
                return extendSpecVersion(super.primaryFixedDim(), '2025', color2025);
            }
            onPrimaryFixed() {
                const color2025 = FP({ name: 'on_primary_fixed', palette: s => s.primaryPalette, background: s => this.primaryFixedDim(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onPrimaryFixed(), '2025', color2025);
            }
            onPrimaryFixedVariant() {
                const color2025 = FP({ name: 'on_primary_fixed_variant', palette: s => s.primaryPalette, tone: s => DynamicColor.getInitialToneFromBackground(scheme => this.primaryFixedDim())(s),
                    background: s => this.primaryFixedDim(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onPrimaryFixedVariant(), '2025', color2025);
            }
            inversePrimary() {
                const color2025 = FP({ name: 'inverse_primary', palette: s => s.primaryPalette, tone: s => tMaxC(s.primaryPalette), background: s => this.inverseSurface(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.inversePrimary(), '2025', color2025);
            }
            secondary() {
                const color2025 = FP({ name: 'secondary', palette: s => s.secondaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? tMinC(s.secondaryPalette, 0, 98) : tMaxC(s.secondaryPalette);
                        if (s.variant === Variant.VIBRANT) return tMaxC(s.secondaryPalette, 0, s.isDark ? 90 : 98);
                        return s.isDark ? 80 : tMaxC(s.secondaryPalette);     // EXPRESSIVE and TONAL_SPOT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.secondaryContainer(), this.secondary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.secondary(), '2025', color2025);
            }
            secondaryDim() {
                return FP({ name: 'secondary_dim', palette: s => s.secondaryPalette,
                    tone: s => s.variant === Variant.NEUTRAL ? 85 : tMaxC(s.secondaryPalette, 0, 90), isBackground: true,
                    background: s => this.surfaceContainerHigh(), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.secondaryDim(), this.secondary(), 5, 'darker', true, 'farther') });
            }
            onSecondary() {
                const color2025 = FP({ name: 'on_secondary', palette: s => s.secondaryPalette, background: s => this.secondary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onSecondary(), '2025', color2025);
            }
            secondaryContainer() {
                const color2025 = FP({ name: 'secondary_container', palette: s => s.secondaryPalette,
                    tone: s => {
                        if (s.variant === Variant.VIBRANT) return s.isDark ? tMinC(s.secondaryPalette, 30, 40) : tMaxC(s.secondaryPalette, 84, 90);
                        if (s.variant === Variant.EXPRESSIVE) return s.isDark ? 15 : tMaxC(s.secondaryPalette, 90, 95);
                        return s.isDark ? 25 : 90;
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.secondaryContainer(), '2025', color2025);
            }
            onSecondaryContainer() {
                const color2025 = FP({ name: 'on_secondary_container', palette: s => s.secondaryPalette, background: s => this.secondaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onSecondaryContainer(), '2025', color2025);
            }
            secondaryFixed() {
                const color2025 = FP({ name: 'secondary_fixed', palette: s => s.secondaryPalette, tone: s => this.secondaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.secondaryFixed(), '2025', color2025);
            }
            secondaryFixedDim() {
                const color2025 = FP({ name: 'secondary_fixed_dim', palette: s => s.secondaryPalette, tone: s => this.secondaryFixed().getTone(s), isBackground: true,
                    toneDeltaPair: s => TDP(this.secondaryFixedDim(), this.secondaryFixed(), 5, 'darker', true, 'exact') });
                return extendSpecVersion(super.secondaryFixedDim(), '2025', color2025);
            }
            onSecondaryFixed() {
                const color2025 = FP({ name: 'on_secondary_fixed', palette: s => s.secondaryPalette, background: s => this.secondaryFixedDim(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onSecondaryFixed(), '2025', color2025);
            }
            onSecondaryFixedVariant() {
                const color2025 = FP({ name: 'on_secondary_fixed_variant', palette: s => s.secondaryPalette, tone: s => DynamicColor.getInitialToneFromBackground(scheme => this.secondaryFixedDim())(s),
                    background: s => this.secondaryFixedDim(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onSecondaryFixedVariant(), '2025', color2025);
            }
            tertiary() {
                const color2025 = FP({ name: 'tertiary', palette: s => s.tertiaryPalette,
                    tone: s => {
                        if (s.variant === Variant.EXPRESSIVE || s.variant === Variant.VIBRANT) return tMaxC(s.tertiaryPalette, 0, Hct.isCyan(s.tertiaryPalette.hue) ? 88 : (s.isDark ? 98 : 100));
                        return s.isDark ? tMaxC(s.tertiaryPalette, 0, 98) : tMaxC(s.tertiaryPalette);     // NEUTRAL and TONAL_SPOT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.tertiaryContainer(), this.tertiary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.tertiary(), '2025', color2025);
            }
            tertiaryDim() {
                return FP({ name: 'tertiary_dim', palette: s => s.tertiaryPalette,
                    tone: s => s.variant === Variant.TONAL_SPOT ? tMaxC(s.tertiaryPalette, 0, 90) : tMaxC(s.tertiaryPalette), isBackground: true,
                    background: s => this.surfaceContainerHigh(), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.tertiaryDim(), this.tertiary(), 5, 'darker', true, 'farther') });
            }
            onTertiary() {
                const color2025 = FP({ name: 'on_tertiary', palette: s => s.tertiaryPalette, background: s => this.tertiary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onTertiary(), '2025', color2025);
            }
            tertiaryContainer() {
                const color2025 = FP({ name: 'tertiary_container', palette: s => s.tertiaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? tMaxC(s.tertiaryPalette, 0, 93) : tMaxC(s.tertiaryPalette, 0, 96);
                        if (s.variant === Variant.TONAL_SPOT) return tMaxC(s.tertiaryPalette, 0, s.isDark ? 93 : 100);
                        if (s.variant === Variant.EXPRESSIVE) return tMaxC(s.tertiaryPalette, 75, Hct.isCyan(s.tertiaryPalette.hue) ? 88 : (s.isDark ? 93 : 100));
                        return s.isDark ? tMaxC(s.tertiaryPalette, 0, 93) : tMaxC(s.tertiaryPalette, 72, 100);     // VIBRANT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.tertiaryContainer(), '2025', color2025);
            }
            onTertiaryContainer() {
                const color2025 = FP({ name: 'on_tertiary_container', palette: s => s.tertiaryPalette, background: s => this.tertiaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onTertiaryContainer(), '2025', color2025);
            }
            tertiaryFixed() {
                const color2025 = FP({ name: 'tertiary_fixed', palette: s => s.tertiaryPalette, tone: s => this.tertiaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.tertiaryFixed(), '2025', color2025);
            }
            tertiaryFixedDim() {
                const color2025 = FP({ name: 'tertiary_fixed_dim', palette: s => s.tertiaryPalette, tone: s => this.tertiaryFixed().getTone(s), isBackground: true,
                    toneDeltaPair: s => TDP(this.tertiaryFixedDim(), this.tertiaryFixed(), 5, 'darker', true, 'exact') });
                return extendSpecVersion(super.tertiaryFixedDim(), '2025', color2025);
            }
            onTertiaryFixed() {
                const color2025 = FP({ name: 'on_tertiary_fixed', palette: s => s.tertiaryPalette, background: s => this.tertiaryFixedDim(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onTertiaryFixed(), '2025', color2025);
            }
            onTertiaryFixedVariant() {
                const color2025 = FP({ name: 'on_tertiary_fixed_variant', palette: s => s.tertiaryPalette, tone: s => DynamicColor.getInitialToneFromBackground(scheme => this.tertiaryFixedDim())(s),
                    background: s => this.tertiaryFixedDim(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onTertiaryFixedVariant(), '2025', color2025);
            }
            error() {
                const color2025 = FP({ name: 'error', palette: s => s.errorPalette, tone: s => s.isDark ? tMinC(s.errorPalette, 0, 98) : tMaxC(s.errorPalette), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.errorContainer(), this.error(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.error(), '2025', color2025);
            }
            errorDim() {
                return FP({ name: 'error_dim', palette: s => s.errorPalette, tone: s => tMinC(s.errorPalette), isBackground: true,
                    background: s => this.surfaceContainerHigh(), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.errorDim(), this.error(), 5, 'darker', true, 'farther') });
            }
            onError() {
                const color2025 = FP({ name: 'on_error', palette: s => s.errorPalette, background: s => this.error(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onError(), '2025', color2025);
            }
            errorContainer() {
                const color2025 = FP({ name: 'error_container', palette: s => s.errorPalette, tone: s => s.isDark ? tMinC(s.errorPalette, 30, 93) : tMaxC(s.errorPalette, 0, 90), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.errorContainer(), '2025', color2025);
            }
            onErrorContainer() {
                const color2025 = FP({ name: 'on_error_container', palette: s => s.errorPalette, background: s => this.errorContainer(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onErrorContainer(), '2025', color2025);
            }
            // four roles defined as another role under a new name (the name decides which side of a tone pair a colour is)
            surfaceVariant() { return extendSpecVersion(super.surfaceVariant(), '2025', Object.assign(this.surfaceContainerHighest().clone(), { name: 'surface_variant' })); }
            surfaceTint() { return extendSpecVersion(super.surfaceTint(), '2025', Object.assign(this.primary().clone(), { name: 'surface_tint' })); }
            background() { return extendSpecVersion(super.background(), '2025', Object.assign(this.surface().clone(), { name: 'background' })); }
            onBackground() { return extendSpecVersion(super.onBackground(), '2025', Object.assign(this.onSurface().clone(), { name: 'on_background', tone: s => this.onSurface().getTone(s) })); }
        }

        // ---------- dynamiccolor/color_spec_2026.ts (phone): only CMF reaches it; the "undefined use case" values of the other variants are kept as upstream has them ----------
        const cmf = s => s.variant === Variant.CMF;
        class ColorSpecDelegateImpl2026 extends ColorSpecDelegateImpl2025 {
            surface() {
                const color2026 = FP({ name: 'surface', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 4 : 98) : 0, isBackground: true });
                return extendSpecVersion(super.surface(), '2026', color2026);
            }
            surfaceDim() {
                const color2026 = FP({ name: 'surface_dim', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 4 : 87) : 0, chromaMultiplier: s => cmf(s) ? (s.isDark ? 1 : 1.7) : 0, isBackground: true });
                return extendSpecVersion(super.surfaceDim(), '2026', color2026);
            }
            surfaceBright() {
                const color2026 = FP({ name: 'surface_bright', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 18 : 98) : 0, chromaMultiplier: s => cmf(s) ? (s.isDark ? 1.7 : 1) : 0, isBackground: true });
                return extendSpecVersion(super.surfaceBright(), '2026', color2026);
            }
            surfaceContainerLowest() {
                const color2026 = FP({ name: 'surface_container_lowest', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 0 : 100) : 0, isBackground: true });
                return extendSpecVersion(super.surfaceContainerLowest(), '2026', color2026);
            }
            surfaceContainerLow() {
                const color2026 = FP({ name: 'surface_container_low', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 6 : 96) : 0, chromaMultiplier: s => cmf(s) ? 1.25 : 0, isBackground: true });
                return extendSpecVersion(super.surfaceContainerLow(), '2026', color2026);
            }
            surfaceContainer() {
                const color2026 = FP({ name: 'surface_container', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 9 : 94) : 0, chromaMultiplier: s => cmf(s) ? 1.4 : 0, isBackground: true });
                return extendSpecVersion(super.surfaceContainer(), '2026', color2026);
            }
            surfaceContainerHigh() {
                const color2026 = FP({ name: 'surface_container_high', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 12 : 92) : 0, chromaMultiplier: s => cmf(s) ? 1.5 : 0, isBackground: true });
                return extendSpecVersion(super.surfaceContainerHigh(), '2026', color2026);
            }
            surfaceContainerHighest() {
                const color2026 = FP({ name: 'surface_container_highest', palette: s => s.neutralPalette, tone: s => cmf(s) ? (s.isDark ? 15 : 90) : 0, chromaMultiplier: s => cmf(s) ? 1.7 : 0, isBackground: true });
                return extendSpecVersion(super.surfaceContainerHighest(), '2026', color2026);
            }
            onSurface() {
                const color2026 = FP({ name: 'on_surface', palette: s => s.neutralPalette, chromaMultiplier: s => cmf(s) ? 1.7 : 0, background: s => this.highestSurface(s), contrastCurve: s => s.isDark ? getCurve(11) : getCurve(9) });
                return extendSpecVersion(super.onSurface(), '2026', color2026);
            }
            onSurfaceVariant() {
                const color2026 = FP({ name: 'on_surface_variant', palette: s => s.neutralPalette, chromaMultiplier: s => cmf(s) ? 1.7 : 0, background: s => this.highestSurface(s), contrastCurve: s => s.isDark ? getCurve(6) : getCurve(4.5) });
                return extendSpecVersion(super.onSurfaceVariant(), '2026', color2026);
            }
            outline() {
                const color2026 = FP({ name: 'outline', palette: s => s.neutralPalette, chromaMultiplier: s => cmf(s) ? 1.7 : 0, background: s => this.highestSurface(s), contrastCurve: s => getCurve(3) });
                return extendSpecVersion(super.outline(), '2026', color2026);
            }
            outlineVariant() {
                const color2026 = FP({ name: 'outline_variant', palette: s => s.neutralPalette, chromaMultiplier: s => cmf(s) ? 1.7 : 0, background: s => this.highestSurface(s), contrastCurve: s => getCurve(1.5) });
                return extendSpecVersion(super.outlineVariant(), '2026', color2026);
            }
            inverseSurface() {
                const color2026 = FP({ name: 'inverse_surface', palette: s => s.neutralPalette, tone: s => s.isDark ? 98 : 4, chromaMultiplier: s => cmf(s) ? 1.7 : 0, isBackground: true });
                return extendSpecVersion(super.inverseSurface(), '2026', color2026);
            }
            inverseOnSurface() {
                const color2026 = FP({ name: 'inverse_on_surface', palette: s => s.neutralPalette, background: s => this.inverseSurface(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.inverseOnSurface(), '2026', color2026);
            }
            primary() {
                const color2026 = FP({ name: 'primary', palette: s => s.primaryPalette, tone: s => s.sourceColorHct.chroma <= 12 ? (s.isDark ? 80 : 40) : s.sourceColorHct.tone, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.primaryContainer(), this.primary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.primary(), '2026', color2026);
            }
            onPrimary() {
                const color2026 = FP({ name: 'on_primary', palette: s => s.primaryPalette, background: s => this.primary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onPrimary(), '2026', color2026);
            }
            primaryContainer() {
                const color2026 = FP({ name: 'primary_container', palette: s => s.primaryPalette,
                    tone: s => {
                        if (!s.isDark && s.sourceColorHct.chroma <= 12) return 90;
                        return s.sourceColorHct.tone > 55 ? clampDouble(61, 90, s.sourceColorHct.tone) : clampDouble(30, 49, s.sourceColorHct.tone);
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.primaryContainer(), '2026', color2026);
            }
            onPrimaryContainer() {
                const color2026 = FP({ name: 'on_primary_container', palette: s => s.primaryPalette, background: s => this.primaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onPrimaryContainer(), '2026', color2026);
            }
            primaryFixed() {
                const color2026 = FP({ name: 'primary_fixed', palette: s => s.primaryPalette, tone: s => this.primaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.primaryFixed(), '2026', color2026);
            }
            primaryFixedDim() {
                const color2026 = FP({ name: 'primary_fixed_dim', palette: s => s.primaryPalette, tone: s => this.primaryFixed().getTone(s), isBackground: true,
                    background: s => this.highestSurface(s), toneDeltaPair: s => TDP(this.primaryFixedDim(), this.primaryFixed(), 5, 'darker', true, 'exact'),
                    contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.primaryFixedDim(), '2026', color2026);
            }
            onPrimaryFixed() {
                const color2026 = FP({ name: 'on_primary_fixed', palette: s => s.primaryPalette, background: s => this.primaryFixed().getTone(s) > 57 ? this.primaryFixedDim() : this.primaryFixed(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onPrimaryFixed(), '2026', color2026);
            }
            onPrimaryFixedVariant() {
                const color2026 = FP({ name: 'on_primary_fixed_variant', palette: s => s.primaryPalette, background: s => this.primaryFixed().getTone(s) > 57 ? this.primaryFixedDim() : this.primaryFixed(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onPrimaryFixedVariant(), '2026', color2026);
            }
            secondary() {
                const color2026 = FP({ name: 'secondary', palette: s => s.secondaryPalette, tone: s => s.isDark ? tMinC(s.secondaryPalette) : tMaxC(s.secondaryPalette), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.secondaryContainer(), this.secondary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.secondary(), '2026', color2026);
            }
            onSecondary() {
                const color2026 = FP({ name: 'on_secondary', palette: s => s.secondaryPalette, background: s => this.secondary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onSecondary(), '2026', color2026);
            }
            secondaryContainer() {
                const color2026 = FP({ name: 'secondary_container', palette: s => s.secondaryPalette, tone: s => s.isDark ? tMinC(s.secondaryPalette, 20, 49) : tMaxC(s.secondaryPalette, 61, 90), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.secondaryContainer(), '2026', color2026);
            }
            onSecondaryContainer() {
                const color2026 = FP({ name: 'on_secondary_container', palette: s => s.secondaryPalette, background: s => this.secondaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onSecondaryContainer(), '2026', color2026);
            }
            secondaryFixed() {
                const color2026 = FP({ name: 'secondary_fixed', palette: s => s.secondaryPalette, tone: s => this.secondaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.secondaryFixed(), '2026', color2026);
            }
            secondaryFixedDim() {
                const color2026 = FP({ name: 'secondary_fixed_dim', palette: s => s.secondaryPalette, tone: s => this.secondaryFixed().getTone(s), isBackground: true,
                    background: s => this.highestSurface(s), toneDeltaPair: s => TDP(this.secondaryFixedDim(), this.secondaryFixed(), 5, 'darker', true, 'exact'),
                    contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.secondaryFixedDim(), '2026', color2026);
            }
            onSecondaryFixed() {
                const color2026 = FP({ name: 'on_secondary_fixed', palette: s => s.secondaryPalette, background: s => this.secondaryFixed().getTone(s) > 57 ? this.secondaryFixedDim() : this.secondaryFixed(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onSecondaryFixed(), '2026', color2026);
            }
            onSecondaryFixedVariant() {
                const color2026 = FP({ name: 'on_secondary_fixed_variant', palette: s => s.secondaryPalette, background: s => this.secondaryFixed().getTone(s) > 57 ? this.secondaryFixedDim() : this.secondaryFixed(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onSecondaryFixedVariant(), '2026', color2026);
            }
            // the tertiary follows a second source colour when there is one (the API passes one colour, so it is the source colour)
            tertiary() {
                const color2026 = FP({ name: 'tertiary', palette: s => s.tertiaryPalette, tone: s => (s.sourceColorHcts[1] !== undefined ? s.sourceColorHcts[1] : s.sourceColorHct).tone, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.tertiaryContainer(), this.tertiary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.tertiary(), '2026', color2026);
            }
            onTertiary() {
                const color2026 = FP({ name: 'on_tertiary', palette: s => s.tertiaryPalette, background: s => this.tertiary(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onTertiary(), '2026', color2026);
            }
            tertiaryContainer() {
                const color2026 = FP({ name: 'tertiary_container', palette: s => s.tertiaryPalette,
                    tone: s => {
                        const secondarySourceColorHct = s.sourceColorHcts[1] !== undefined ? s.sourceColorHcts[1] : s.sourceColorHct;
                        return secondarySourceColorHct.tone > 55 ? clampDouble(61, 90, secondarySourceColorHct.tone) : clampDouble(20, 49, secondarySourceColorHct.tone);
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.tertiaryContainer(), '2026', color2026);
            }
            onTertiaryContainer() {
                const color2026 = FP({ name: 'on_tertiary_container', palette: s => s.tertiaryPalette, background: s => this.tertiaryContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onTertiaryContainer(), '2026', color2026);
            }
            tertiaryFixed() {
                const color2026 = FP({ name: 'tertiary_fixed', palette: s => s.tertiaryPalette, tone: s => this.tertiaryContainer().getTone(asLight(s)), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.tertiaryFixed(), '2026', color2026);
            }
            tertiaryFixedDim() {
                const color2026 = FP({ name: 'tertiary_fixed_dim', palette: s => s.tertiaryPalette, tone: s => this.tertiaryFixed().getTone(s), isBackground: true,
                    background: s => this.highestSurface(s), toneDeltaPair: s => TDP(this.tertiaryFixedDim(), this.tertiaryFixed(), 5, 'darker', true, 'exact'),
                    contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.tertiaryFixedDim(), '2026', color2026);
            }
            onTertiaryFixed() {
                const color2026 = FP({ name: 'on_tertiary_fixed', palette: s => s.tertiaryPalette, background: s => this.tertiaryFixed().getTone(s) > 57 ? this.tertiaryFixedDim() : this.tertiaryFixed(), contrastCurve: s => getCurve(7) });
                return extendSpecVersion(super.onTertiaryFixed(), '2026', color2026);
            }
            onTertiaryFixedVariant() {
                const color2026 = FP({ name: 'on_tertiary_fixed_variant', palette: s => s.tertiaryPalette, background: s => this.tertiaryFixed().getTone(s) > 57 ? this.tertiaryFixedDim() : this.tertiaryFixed(), contrastCurve: s => getCurve(4.5) });
                return extendSpecVersion(super.onTertiaryFixedVariant(), '2026', color2026);
            }
            error() {
                const color2026 = FP({ name: 'error', palette: s => s.errorPalette, tone: s => tMaxC(s.errorPalette), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => getCurve(4.5),
                    toneDeltaPair: s => TDP(this.errorContainer(), this.error(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.error(), '2026', color2026);
            }
            onError() {
                const color2026 = FP({ name: 'on_error', palette: s => s.errorPalette, background: s => this.error(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onError(), '2026', color2026);
            }
            errorContainer() {
                const color2026 = FP({ name: 'error_container', palette: s => s.errorPalette, tone: s => s.isDark ? tMinC(s.errorPalette) : tMaxC(s.errorPalette), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? getCurve(1.5) : undefined });
                return extendSpecVersion(super.errorContainer(), '2026', color2026);
            }
            onErrorContainer() {
                const color2026 = FP({ name: 'on_error_container', palette: s => s.errorPalette, background: s => this.errorContainer(), contrastCurve: s => getCurve(6) });
                return extendSpecVersion(super.onErrorContainer(), '2026', color2026);
            }
            // in 2026 each dim colour is its accent under the dim name
            primaryDim() { return extendSpecVersion(super.primaryDim(), '2026', Object.assign(this.primary().clone(), { name: 'primary_dim' })); }
            secondaryDim() { return extendSpecVersion(super.secondaryDim(), '2026', Object.assign(this.secondary().clone(), { name: 'secondary_dim' })); }
            tertiaryDim() { return extendSpecVersion(super.tertiaryDim(), '2026', Object.assign(this.tertiary().clone(), { name: 'tertiary_dim' })); }
            errorDim() { return extendSpecVersion(super.errorDim(), '2026', Object.assign(this.error().clone(), { name: 'error_dim' })); }
        }
        // material_dynamic_colors.ts: MaterialDynamicColors.colorSpec, the one delegate every role of every spec comes through (the extended
        // colours pick the definition by the scheme's specVersion)
        const colorSpec = new ColorSpecDelegateImpl2026();

        // ---------- AOSP external/libmonet dynamiccolor/ColorSpec2025.java, android16-qpr1-release and android16-qpr2-release (phone) ----------
        // What SystemUI computes on Android 16 QPR1 and QPR2 (profiles P16Q1, P16Q2): upstream's 2025 roles above, except where libmonet's
        // ColorSpec2025 differs from it on a phone. Every other role, and spec 2021, is upstream's (libmonet's ColorSpec2021 equals it on a
        // phone). branch: 'qpr1' or 'qpr2' (the two differ in on_surface_variant and the three accent fixed colours), or 'a17'
        // (android17-release, profile P17: its ColorSpec2025 differs from qpr2's on a phone only in getContrastCurve(1.5), which is
        // upstream's again, so on 17 only Expressive's primary and primary_container, the getHct rule and the three renamed Android
        // roles differ from upstream's main; every override below that exists for the curve alone gives upstream's role on 'a17')
        // libmonet getContrastCurve(1.5) is (1.5, 1.5, 3, 4.5) (upstream's main: 5.5 at contrast 1); the others are getCurve's
        const libmonetCurve = c => c === 1.5 ? CC(1.5, 1.5, 3, 4.5) : getCurve(c);
        class ColorSpecDelegateLibmonet extends ColorSpecDelegateImpl2025 {
            constructor(branch) { super(); this.branch = branch; }
            // the branch's getContrastCurve: qpr1 and qpr2 libmonetCurve, a17 upstream's getCurve
            curve(c) { return this.branch === 'a17' ? getCurve(c) : libmonetCurve(c); }
            // qpr1: curve 4.5 in both modes (qpr2 and a17: dark 6, light 4.5, as upstream's)
            onSurfaceVariant() {
                if (this.branch !== 'qpr1') return super.onSurfaceVariant();
                const color2025 = FP({ name: 'on_surface_variant', palette: s => s.neutralPalette, chromaMultiplier: textChromaMultiplier2025,
                    background: s => this.highestSurface(s), contrastCurve: s => libmonetCurve(4.5) });
                return extendSpecVersion(super.onSurfaceVariant(), '2025', color2025);
            }
            outlineVariant() {
                if (this.branch === 'a17') return super.outlineVariant();
                const color2025 = FP({ name: 'outline_variant', palette: s => s.neutralPalette, chromaMultiplier: textChromaMultiplier2025,
                    background: s => this.highestSurface(s), contrastCurve: s => libmonetCurve(1.5) });
                return extendSpecVersion(super.outlineVariant(), '2025', color2025);
            }
            // Expressive: tMaxC(primary, 0, yellow ? 25 : cyan ? 88 : 98) in both modes (upstream's main: dark cyan ? 88 : 98, light yellow ? 25 : 98)
            primary() {
                const color2025 = FP({ name: 'primary', palette: s => s.primaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? 80 : 40;
                        if (s.variant === Variant.TONAL_SPOT) return s.isDark ? 80 : tMaxC(s.primaryPalette);
                        if (s.variant === Variant.EXPRESSIVE) return tMaxC(s.primaryPalette, 0, Hct.isYellow(s.primaryPalette.hue) ? 25 : Hct.isCyan(s.primaryPalette.hue) ? 88 : 98);
                        return tMaxC(s.primaryPalette, 0, Hct.isCyan(s.primaryPalette.hue) ? 88 : 98);     // VIBRANT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => this.curve(4.5),
                    toneDeltaPair: s => TDP(this.primaryContainer(), this.primary(), 5, 'relative_lighter', true, 'farther') });
                return extendSpecVersion(super.primary(), '2025', color2025);
            }
            // Expressive dark: tMaxC(primary, 30, 93) (upstream's main: tMinC)
            primaryContainer() {
                const color2025 = FP({ name: 'primary_container', palette: s => s.primaryPalette,
                    tone: s => {
                        if (s.variant === Variant.NEUTRAL) return s.isDark ? 30 : 90;
                        if (s.variant === Variant.TONAL_SPOT) return s.isDark ? tMinC(s.primaryPalette, 35, 93) : tMaxC(s.primaryPalette, 0, 90);
                        if (s.variant === Variant.EXPRESSIVE) return s.isDark ? tMaxC(s.primaryPalette, 30, 93) : tMaxC(s.primaryPalette, 78, Hct.isCyan(s.primaryPalette.hue) ? 88 : 90);
                        return s.isDark ? tMinC(s.primaryPalette, 66, 93) : tMaxC(s.primaryPalette, 66, Hct.isCyan(s.primaryPalette.hue) ? 88 : 93);     // VIBRANT
                    }, isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? this.curve(1.5) : undefined });
                return extendSpecVersion(super.primaryContainer(), '2025', color2025);
            }
            // the other three containers: upstream's tone rules with libmonet's curve (an extended colour of the same tone and a new curve)
            secondaryContainer() { return this.withLibmonetContainerCurve(super.secondaryContainer()); }
            tertiaryContainer() { return this.withLibmonetContainerCurve(super.tertiaryContainer()); }
            errorContainer() { return this.withLibmonetContainerCurve(super.errorContainer()); }
            withLibmonetContainerCurve(upstream) {
                if (this.branch === 'a17') return upstream;
                const color2025 = FP({ name: upstream.name, palette: s => upstream.palette(s), tone: s => upstream.tone(s), isBackground: true,
                    background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? libmonetCurve(1.5) : undefined });
                return extendSpecVersion(upstream, '2025', color2025);
            }
            // the accent fixed colours: the container's tone on the scheme as light. qpr2 (as upstream's main): at contrast 0, against the
            // highest surface with curve 1.5 above contrast 0. qpr1: DynamicScheme.from(s, false) keeps the contrast, and there is no
            // background or curve. a17: qpr2's rule with upstream's curve, so upstream's role
            primaryFixed() { return this.libmonetFixed(super.primaryFixed(), 'primary_fixed', s => s.primaryPalette, () => this.primaryContainer()); }
            secondaryFixed() { return this.libmonetFixed(super.secondaryFixed(), 'secondary_fixed', s => s.secondaryPalette, () => this.secondaryContainer()); }
            tertiaryFixed() { return this.libmonetFixed(super.tertiaryFixed(), 'tertiary_fixed', s => s.tertiaryPalette, () => this.tertiaryContainer()); }
            libmonetFixed(upstream, name, palette, container) {
                if (this.branch === 'a17') return upstream;
                const color2025 = this.branch === 'qpr1'
                    ? FP({ name, palette, tone: s => container().getTone(Object.assign({}, s, { isDark: false })), isBackground: true })
                    : FP({ name, palette, tone: s => container().getTone(asLight(s)), isBackground: true,
                        background: s => this.highestSurface(s), contrastCurve: s => s.contrastLevel > 0 ? libmonetCurve(1.5) : undefined });
                return extendSpecVersion(upstream, '2025', color2025);
            }
            // on_background: on_surface under the new name (upstream's main also sets its tone to on_surface's; the same value on a phone;
            // a17 sets it too, so upstream's role there)
            onBackground() {
                if (this.branch === 'a17') return super.onBackground();
                return extendSpecVersion(super.onBackground(), '2025', Object.assign(this.onSurface().clone(), { name: 'on_background' })); }
            // ColorSpec2025's Android-only colours: at 2025 three of them are other roles under their names (ColorSpec2021's, and the five
            // others, are the API's Android-only definitions)
            controlActivated() { return Object.assign(this.primaryContainer().clone(), { name: 'control_activated' }); }
            controlNormal() { return Object.assign(this.onSurfaceVariant().clone(), { name: 'control_normal' }); }
            textPrimaryInverse() { return Object.assign(this.inverseOnSurface().clone(), { name: 'text_primary_inverse' }); }
        }
        const colorSpecLibmonet = { qpr1: new ColorSpecDelegateLibmonet('qpr1'), qpr2: new ColorSpecDelegateLibmonet('qpr2'), a17: new ColorSpecDelegateLibmonet('a17') };
        // ---------- AOSP external/libmonet dynamiccolor/ColorSpec2026.java, android17-release (phone) ----------
        // libmonet a17's MaterialDynamicColors takes ColorSpec2026, which equals upstream's main (its file differs only in an import), on
        // a17's ColorSpec2025. Only CMF reaches 2026 (maybeFallbackSpecVersion), and every role a17's ColorSpec2025 changes for a phone
        // (Expressive's primary and primary_container) has a 2026 definition of its own, so at 2026 the roles are upstream's, except
        // the three Android roles a17's ColorSpec2025 renames from 2025 on (its extendSpecVersion takes a spec at or above the one named):
        // control_activated, control_normal and text_primary_inverse are 2026's primary_container, on_surface_variant and
        // inverse_on_surface under their names. The getHct rule (libmonetHct) is the scheme's (scheme.libmonet)
        class ColorSpecDelegateLibmonet2026 extends ColorSpecDelegateImpl2026 {
            controlActivated() { return Object.assign(this.primaryContainer().clone(), { name: 'control_activated' }); }
            controlNormal() { return Object.assign(this.onSurfaceVariant().clone(), { name: 'control_normal' }); }
            textPrimaryInverse() { return Object.assign(this.inverseOnSurface().clone(), { name: 'text_primary_inverse' }); }
        }
        const colorSpecLibmonet2026 = { a17: new ColorSpecDelegateLibmonet2026() };

        // the libmonet branches whose DynamicScheme has maybeFallbackSpecVersion and a 2026 spec, and SchemeCmf (android17-release); qpr1
        // and qpr2 keep the spec they are given and have neither
        const LIBMONET_FALLS_BACK = { a17: true };
        // ---------- dynamiccolor/dynamic_scheme.ts ----------
        class DynamicScheme {
            // the spec a variant really gets on a platform: CMF keeps what it is given, the four 2025 variants map 2026 to 2025, every other variant is 2021
            static maybeFallbackSpecVersion(specVersion, variant, platform) {
                if (variant === Variant.CMF) return specVersion;
                if (variant === Variant.EXPRESSIVE || variant === Variant.VIBRANT || variant === Variant.TONAL_SPOT || variant === Variant.NEUTRAL || (platform === 'watch' && variant === Variant.CONTENT)) {
                    return specVersion === '2026' ? '2025' : specVersion;
                }
                return '2021';
            }
            constructor(args) {
                this.sourceColorHct = args.sourceColorHct;
                this.sourceColorHcts = [args.sourceColorHct];
                this.sourceColorArgb = this.sourceColorHct.toInt();
                this.variant = args.variant;
                this.contrastLevel = args.contrastLevel;
                this.isDark = args.isDark;
                this.platform = 'phone';
                // libmonet (P16Q1, P16Q2: 'qpr1' or 'qpr2'; undefined for upstream's main): libmonet's DynamicScheme keeps the spec it is
                // given (no maybeFallbackSpecVersion) and has no 2026, so every variant at 2025 gets the 2025 roles, on the palettes the
                // 2025 delegate gives it (2021's for Rainbow, Fruit Salad, Content, Monochrome and Fidelity). 'a17' (P17): libmonet
                // android17-release's DynamicScheme falls back as upstream's main does (maybeFallbackSpecVersion: CMF keeps its spec, the
                // four 2025 variants turn 2026 into 2025, the others are 2021), so its specVersion is upstream's
                this.libmonet = args.libmonet;
                this.specVersion = this.libmonet && !LIBMONET_FALLS_BACK[this.libmonet]
                    ? (args.specVersion === undefined || args.specVersion === '2021' ? '2021' : '2025')
                    : DynamicScheme.maybeFallbackSpecVersion(args.specVersion === undefined ? '2021' : args.specVersion, this.variant, this.platform);
                // a15 (P15, Android 15.0: libmonet android15-release, upstream's 2021 but for the key colour search and the on_*_container
                // rules): only a scheme whose spec is 2021 (after the fallback above) is a15's; at 2025 and 2026 upstream's, as for P15Q1
                this.a15 = !!args.a15 && this.specVersion === '2021';
                const spec = getPalettesSpec(this.specVersion), v = this.variant, h = this.sourceColorHct, d = this.isDark, p = this.platform, c = this.contrastLevel;
                const walk = keyColorWalk;
                keyColorWalk = this.a15;
                try {
                    this.primaryPalette = args.primaryPalette || spec.getPrimaryPalette(v, h, d, p, c);
                    this.secondaryPalette = args.secondaryPalette || spec.getSecondaryPalette(v, h, d, p, c);
                    this.tertiaryPalette = args.tertiaryPalette || spec.getTertiaryPalette(v, h, d, p, c);
                    this.neutralPalette = args.neutralPalette || spec.getNeutralPalette(v, h, d, p, c);
                    this.neutralVariantPalette = args.neutralVariantPalette || spec.getNeutralVariantPalette(v, h, d, p, c);
                    this.errorPalette = args.errorPalette || spec.getErrorPalette(v, h, d, p, c) || TonalPalette.fromHueAndChroma(25.0, 84.0);
                } finally {
                    keyColorWalk = walk;
                }
            }
            // the hue of the breakpoint interval the source hue falls in (>= the lower, < the upper), else the source hue
            static getPiecewiseHue(sourceColorHct, hueBreakpoints, hues) {
                const size = Math.min(hueBreakpoints.length - 1, hues.length);
                const sourceHue = sourceColorHct.hue;
                for (let i = 0; i < size; i++) {
                    if (sourceHue >= hueBreakpoints[i] && sourceHue < hueBreakpoints[i + 1]) return sanitizeDegreesDouble(hues[i]);
                }
                return sourceHue;
            }
            // the source hue plus the rotation of its interval (part 1's Android 14 getRotatedHue uses strict bounds on both sides)
            static getRotatedHue(sourceColorHct, hueBreakpoints, rotations) {
                let rotation = DynamicScheme.getPiecewiseHue(sourceColorHct, hueBreakpoints, rotations);
                if (Math.min(hueBreakpoints.length - 1, rotations.length) <= 0) rotation = 0;
                return sanitizeDegreesDouble(sourceColorHct.hue + rotation);
            }
        }
        const P = TonalPalette.fromHueAndChroma;
        // DynamicSchemePalettesDelegateImpl2021: the five palettes of spec 2021 (the same tables as part 1's schemePalettes, with
        // upstream's TonalPalette). Fidelity and Content take the source colour's own chroma, and their tertiary comes from the
        // temperature cache above
        class DynamicSchemePalettesDelegateImpl2021 {
            getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.CONTENT: case Variant.FIDELITY: return P(h, sourceColorHct.chroma);
                    case Variant.FRUIT_SALAD: return P(sanitizeDegreesDouble(h - 50.0), 48.0);
                    case Variant.MONOCHROME: return P(h, 0.0);
                    case Variant.NEUTRAL: return P(h, 12.0);
                    case Variant.RAINBOW: return P(h, 48.0);
                    case Variant.TONAL_SPOT: return P(h, 36.0);
                    case Variant.EXPRESSIVE: return P(sanitizeDegreesDouble(h + 240), 40);
                    case Variant.VIBRANT: return P(h, 200.0);
                    default: throw new Error('Unsupported variant: ' + variant);
                }
            }
            getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.CONTENT: case Variant.FIDELITY: return P(h, Math.max(sourceColorHct.chroma - 32.0, sourceColorHct.chroma * 0.5));
                    case Variant.FRUIT_SALAD: return P(sanitizeDegreesDouble(h - 50.0), 36.0);
                    case Variant.MONOCHROME: return P(h, 0.0);
                    case Variant.NEUTRAL: return P(h, 8.0);
                    case Variant.RAINBOW: return P(h, 16.0);
                    case Variant.TONAL_SPOT: return P(h, 16.0);
                    case Variant.EXPRESSIVE: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 21, 51, 121, 151, 191, 271, 321, 360], [45, 95, 45, 20, 45, 90, 45, 45, 45]), 24.0);
                    case Variant.VIBRANT: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 41, 61, 101, 131, 181, 251, 301, 360], [18, 15, 10, 12, 15, 18, 15, 12, 12]), 24.0);
                    default: throw new Error('Unsupported variant: ' + variant);
                }
            }
            getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.CONTENT: return TonalPalette.fromHct(DislikeAnalyzer.fixIfDisliked(new TemperatureCache(sourceColorHct).analogous(3, 6)[2]));
                    case Variant.FIDELITY: return TonalPalette.fromHct(DislikeAnalyzer.fixIfDisliked(new TemperatureCache(sourceColorHct).complement));
                    case Variant.FRUIT_SALAD: return P(h, 36.0);
                    case Variant.MONOCHROME: return P(h, 0.0);
                    case Variant.NEUTRAL: return P(h, 16.0);
                    case Variant.RAINBOW: case Variant.TONAL_SPOT: return P(sanitizeDegreesDouble(h + 60.0), 24.0);
                    case Variant.EXPRESSIVE: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 21, 51, 121, 151, 191, 271, 321, 360], [120, 120, 20, 45, 20, 15, 20, 120, 120]), 32.0);
                    case Variant.VIBRANT: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 41, 61, 101, 131, 181, 251, 301, 360], [35, 30, 20, 25, 30, 35, 30, 25, 25]), 32.0);
                    default: throw new Error('Unsupported variant: ' + variant);
                }
            }
            getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.CONTENT: case Variant.FIDELITY: return P(h, sourceColorHct.chroma / 8.0);
                    case Variant.FRUIT_SALAD: return P(h, 10.0);
                    case Variant.MONOCHROME: return P(h, 0.0);
                    case Variant.NEUTRAL: return P(h, 2.0);
                    case Variant.RAINBOW: return P(h, 0.0);
                    case Variant.TONAL_SPOT: return P(h, 6.0);
                    case Variant.EXPRESSIVE: return P(sanitizeDegreesDouble(h + 15), 8);
                    case Variant.VIBRANT: return P(h, 10);
                    default: throw new Error('Unsupported variant: ' + variant);
                }
            }
            getNeutralVariantPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.CONTENT: case Variant.FIDELITY: return P(h, (sourceColorHct.chroma / 8.0) + 4.0);
                    case Variant.FRUIT_SALAD: return P(h, 16.0);
                    case Variant.MONOCHROME: return P(h, 0.0);
                    case Variant.NEUTRAL: return P(h, 2.0);
                    case Variant.RAINBOW: return P(h, 0.0);
                    case Variant.TONAL_SPOT: return P(h, 8.0);
                    case Variant.EXPRESSIVE: return P(sanitizeDegreesDouble(h + 15), 12);
                    case Variant.VIBRANT: return P(h, 12);
                    default: throw new Error('Unsupported variant: ' + variant);
                }
            }
            getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel) { return undefined; }
        }
        // DynamicSchemePalettesDelegateImpl2025 (phone): the four variants the 2025 spec tunes; everything else falls through to 2021
        class DynamicSchemePalettesDelegateImpl2025 extends DynamicSchemePalettesDelegateImpl2021 {
            getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.NEUTRAL: return P(h, Hct.isBlue(h) ? 12 : 8);
                    case Variant.TONAL_SPOT: return P(h, isDark ? 26 : 32);
                    case Variant.EXPRESSIVE: return P(h, isDark ? 36 : 48);
                    case Variant.VIBRANT: return P(h, 74);
                    default: return super.getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
            getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue;
                switch (variant) {
                    case Variant.NEUTRAL: return P(h, Hct.isBlue(h) ? 6 : 4);
                    case Variant.TONAL_SPOT: return P(h, 16);
                    case Variant.EXPRESSIVE: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 105, 140, 204, 253, 278, 300, 333, 360], [-160, 155, -100, 96, -96, -156, -165, -160]), isDark ? 16 : 24);
                    case Variant.VIBRANT: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 38, 105, 140, 333, 360], [-14, 10, -14, 10, -14]), 56);
                    default: return super.getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
            getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                switch (variant) {
                    case Variant.NEUTRAL: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 38, 105, 161, 204, 278, 333, 360], [-32, 26, 10, -39, 24, -15, -32]), 20);
                    case Variant.TONAL_SPOT: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 20, 71, 161, 333, 360], [-40, 48, -32, 40, -32]), 28);
                    case Variant.EXPRESSIVE: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 105, 140, 204, 253, 278, 300, 333, 360], [-165, 160, -105, 101, -101, -160, -170, -165]), 48);
                    case Variant.VIBRANT: return P(DynamicScheme.getRotatedHue(sourceColorHct, [0, 38, 71, 105, 140, 161, 253, 333, 360], [-72, 35, 24, -24, 62, 50, 62, -72]), 56);
                    default: return super.getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
            static getExpressiveNeutralHue(sourceColorHct) { return DynamicScheme.getRotatedHue(sourceColorHct, [0, 71, 124, 253, 278, 300, 360], [10, 0, 10, 0, 10, 0]); }
            static getExpressiveNeutralChroma(sourceColorHct, isDark) {
                const neutralHue = DynamicSchemePalettesDelegateImpl2025.getExpressiveNeutralHue(sourceColorHct);
                return isDark ? (Hct.isYellow(neutralHue) ? 6 : 14) : 18;
            }
            static getVibrantNeutralHue(sourceColorHct) { return DynamicScheme.getRotatedHue(sourceColorHct, [0, 38, 105, 140, 333, 360], [-14, 10, -14, 10, -14]); }
            static getVibrantNeutralChroma(sourceColorHct) { return 28; }
            getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue, D = DynamicSchemePalettesDelegateImpl2025;
                switch (variant) {
                    case Variant.NEUTRAL: return P(h, 1.4);
                    case Variant.TONAL_SPOT: return P(h, 5);
                    case Variant.EXPRESSIVE: return P(D.getExpressiveNeutralHue(sourceColorHct), D.getExpressiveNeutralChroma(sourceColorHct, isDark));
                    case Variant.VIBRANT: return P(D.getVibrantNeutralHue(sourceColorHct), D.getVibrantNeutralChroma(sourceColorHct));
                    default: return super.getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
            getNeutralVariantPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const h = sourceColorHct.hue, D = DynamicSchemePalettesDelegateImpl2025;
                switch (variant) {
                    case Variant.NEUTRAL: return P(h, 1.4 * 2.2);
                    case Variant.TONAL_SPOT: return P(h, 5 * 1.7);
                    case Variant.EXPRESSIVE: {
                        const expressiveNeutralHue = D.getExpressiveNeutralHue(sourceColorHct);
                        const expressiveNeutralChroma = D.getExpressiveNeutralChroma(sourceColorHct, isDark);
                        return P(expressiveNeutralHue, expressiveNeutralChroma * (expressiveNeutralHue >= 105 && expressiveNeutralHue < 125 ? 1.6 : 2.3));
                    }
                    case Variant.VIBRANT: {
                        const vibrantNeutralHue = D.getVibrantNeutralHue(sourceColorHct);
                        const vibrantNeutralChroma = D.getVibrantNeutralChroma(sourceColorHct);
                        return P(vibrantNeutralHue, vibrantNeutralChroma * 1.29);
                    }
                    default: return super.getNeutralVariantPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
            getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel) {
                const errorHue = DynamicScheme.getPiecewiseHue(sourceColorHct, [0, 3, 13, 23, 33, 43, 153, 273, 360], [12, 22, 32, 12, 22, 32, 22, 12]);
                switch (variant) {
                    case Variant.NEUTRAL: return P(errorHue, 50);
                    case Variant.TONAL_SPOT: return P(errorHue, 60);
                    case Variant.EXPRESSIVE: return P(errorHue, 64);
                    case Variant.VIBRANT: return P(errorHue, 80);
                    default: return super.getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel);
                }
            }
        }
        const palettesSpec2021 = new DynamicSchemePalettesDelegateImpl2021(), palettesSpec2025 = new DynamicSchemePalettesDelegateImpl2025();
        // dynamic_scheme.ts getSpec: 2026 has no palette delegate of its own (CMF brings its palettes along)
        function getPalettesSpec(specVersion) { return specVersion === '2025' ? palettesSpec2025 : palettesSpec2021; }

        // ---------- scheme/scheme_cmf.ts: one source colour gives every palette (chroma c, 0.5c, 0.75c, 0.2c, 0.2c) and an error hue chosen to sit with it ----------
        class SchemeCmf extends DynamicScheme {
            // libmonet: 'a17' for libmonet android17-release's SchemeCmf (the same palettes and spec rule; the scheme then takes libmonet's
            // getHct rule and roles, P17), left out for upstream's
            constructor(sourceColorHct, isDark, contrastLevel, specVersion, libmonet) {
                if (specVersion === undefined) specVersion = '2026';
                if (specVersion !== '2026') throw new Error('SchemeCmf can only be used with spec version 2026.');
                const secondarySourceColorHct = sourceColorHct;     // upstream's optional second colour (for the tertiary) is left out: one colour
                const h = sourceColorHct.hue, c = sourceColorHct.chroma;
                super({
                    sourceColorHct, variant: Variant.CMF, contrastLevel, isDark, specVersion, libmonet,
                    primaryPalette: P(h, c), secondaryPalette: P(h, c * 0.5), tertiaryPalette: P(h, c * 0.75), neutralPalette: P(h, c * 0.2), neutralVariantPalette: P(h, c * 0.2),
                    errorPalette: P(SchemeCmf.getErrorHue(h, secondarySourceColorHct.hue), Math.max(c, 50.0))
                });
            }
            static getErrorHue(primaryHue, tertiaryHue) {
                if (primaryHue <= 8) return tertiaryHue <= 24 ? 28 : (tertiaryHue <= 32 ? 16 : 20);
                if (primaryHue <= 16) return tertiaryHue <= 24 ? 32 : (tertiaryHue <= 32 ? 20 : 24);
                if (primaryHue <= 20) return tertiaryHue <= 28 ? 32 : (tertiaryHue <= 32 ? 24 : 28);
                if (primaryHue <= 28) return tertiaryHue <= 24 ? 32 : 16;
                if (primaryHue <= 32) return tertiaryHue <= 20 ? 24 : (tertiaryHue <= 28 ? 16 : 20);
                if (primaryHue <= 40) return (tertiaryHue > 20 && tertiaryHue <= 28) ? 16 : 24;
                if (primaryHue <= 152) return (tertiaryHue > 24 && tertiaryHue <= 36) ? 20 : 32;
                if (primaryHue <= 272) return (tertiaryHue > 20 && tertiaryHue <= 28) ? 16 : 24;
                return (tertiaryHue > 12 && tertiaryHue <= 28) ? 32 : 16;
            }
        }
        // scheme_tonal_spot.ts, scheme_vibrant.ts, scheme_expressive.ts, scheme_neutral.ts (and _rainbow, _fruit_salad, _fidelity, _content,
        // _monochrome): each is the DynamicScheme constructor with its variant; SchemeCmf is the one with palettes of its own
        // libmonet: 'qpr1', 'qpr2' or 'a17' for the AOSP libmonet branch of P16Q1 / P16Q2 / P17 (left out: upstream's main); CMF, which
        // libmonet qpr1 and qpr2 do not have, is main's there, and libmonet's SchemeCmf on a17. 'a15' (P15): upstream's scheme with
        // libmonet android15-release's key colours and on_*_container rules at spec 2021 (DynamicScheme's a15); CMF is upstream's
        function schemeOf(variant, sourceColorHct, isDark, contrastLevel, specVersion, libmonet) {
            if (variant === Variant.CMF) return new SchemeCmf(sourceColorHct, isDark, contrastLevel, specVersion, LIBMONET_FALLS_BACK[libmonet] ? libmonet : undefined);
            if (libmonet === 'a15') return new DynamicScheme({ sourceColorHct, variant, contrastLevel, isDark, specVersion, a15: true });
            if (libmonet) return new DynamicScheme({ sourceColorHct, variant, contrastLevel, isDark, specVersion, libmonet });
            return new DynamicScheme({ sourceColorHct, variant, contrastLevel, isDark, specVersion });
        }

        return { Variant, Hct, Contrast, DislikeAnalyzer, TemperatureCache, TonalPalette, KeyColor, ContrastCurve, ToneDeltaPair, DynamicColor, extendSpecVersion,
            ColorCalculationDelegateImpl2021, ColorCalculationDelegateImpl2025, ColorSpecDelegateImpl2021, ColorSpecDelegateImpl2025, ColorSpecDelegateImpl2026,
            colorSpec, tMaxC, tMinC, findBestToneForChroma, getCurve, DynamicScheme, DynamicSchemePalettesDelegateImpl2021, DynamicSchemePalettesDelegateImpl2025,
            SchemeCmf, schemeOf, libmonetHct, libmonetCurve, keyColorA15, TonalPaletteA15, ColorSpecDelegateLibmonet, colorSpecLibmonet, ColorSpecDelegateLibmonet2026,
            colorSpecLibmonet2026, LIBMONET_FALLS_BACK };
    })();

    // ==========================================================================================================================
    // The API. Profile P14 (the default): a style at a spec that falls back to 2021 (upstream's rule, specFor below) is computed by
    // part 1 exactly as in PR 3, a style whose spec stays 2025 or 2026, and CMF at any spec, by part 2. P15, P15Q1, P15Q2, P16: part 2.
    // ==========================================================================================================================
    const SPECS = ['2021', '2025', '2026'];
    const API_STYLES = STYLES.concat(['CMF']);
    // the roles Android writes that Google's library does not define: at 2025 and 2026 they keep part 1's (Android 14's) definition,
    // evaluated on part 2's palettes (every other role in ALL_DYNAMIC_COLORS_MAPPED and FIXED_COLORS_MAPPED is upstream's of the same name)
    const ANDROID_ONLY_ROLES = ['controlActivated', 'controlNormal', 'controlHighlight', 'textPrimaryInverse', 'textSecondaryAndTertiaryInverse',
        'textPrimaryInverseDisableOnly', 'textSecondaryAndTertiaryInverseDisabled', 'textHintInverse'];

    function variantOf(style) {
        const name = String(style || '').toUpperCase();
        const v = name === 'CMF' ? MCU.Variant.CMF : STYLE_VARIANT[name];
        if (!v) throw new Error('Unknown style: ' + style);
        return v;
    }
    // the spec asked for: '2021' when none is given, a spec outside SPECS is an error
    function specOf(opts) {
        const spec = opts && opts.spec !== undefined && opts.spec !== null ? String(opts.spec) : '2021';
        if (SPECS.indexOf(spec) < 0) throw new Error('Unknown spec: ' + opts.spec);
        return spec;
    }
    // the spec the style really gets on a phone: dynamic_scheme.ts maybeFallbackSpecVersion (CMF keeps the spec it is given, Tonal Spot,
    // Vibrant, Expressive and Spritz map 2026 to 2025, every other style is 2021). On P16Q1 and P16Q2 (profile given), libmonet's rule:
    // every style but CMF gets 2021 when 2021 is asked for and 2025 otherwise (2026 included); CMF, which libmonet does not have, keeps
    // upstream's rule. P17: libmonet android17-release falls back as upstream's main does, so upstream's rule
    function specFor(style, spec, profile) {
        const variant = variantOf(style), asked = specOf({ spec });
        const lm = profile !== undefined && profile !== null ? LIBMONET[profileOf({ profile })] : undefined;
        if (lm && !MCU.LIBMONET_FALLS_BACK[lm] && variant !== MCU.Variant.CMF) return asked === '2021' ? '2021' : '2025';
        return MCU.DynamicScheme.maybeFallbackSpecVersion(asked, variant, 'phone');
    }
    function inPart2(variant, spec) { return variant === MCU.Variant.CMF || MCU.DynamicScheme.maybeFallbackSpecVersion(spec, variant, 'phone') !== '2021'; }
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
    // CMF, which Android does not have, follows the stock styles' rule.
    function seed(style, color) {
        const variant = variantOf(style), argb = parseArgb(color);
        if (!argb || color === 0) return GOOGLE_BLUE;
        if (variant === Variant.FIDELITY || variant === Variant.CONTENT || variant === Variant.MONOCHROME) return argb;
        return Hct.fromInt(argb).chroma < MIN_CHROMA ? GOOGLE_BLUE : argb;
    }
    // ---------- The profiles: which Android build's SystemUI the output follows (the page detects it, MonetProfile.java) ----------
    // P14: Android 14 (part 1 at 2021, as in PR 3; also what no profile gives). P15, P15Q1, P15Q2, P16: Android 15.0, 15 QPR1, 15 QPR2
    // and 16.0, whose SystemUI computes spec 2021 with external/libmonet (android15-release for P15; android15-qpr2-release, the same
    // tree as android15-qpr1-release, and android16-release for the others): part 2 at 2021 (P15 with android15-release's two
    // differences, LIBMONET_A15), the other specs as for P14. P16Q1, P16Q2: Android 16 QPR1 and QPR2, whose SystemUI computes
    // with external/libmonet android16-qpr1-release / android16-qpr2-release, which defaults to spec 2025 for every style: part 2 with
    // libmonet's rules (MCU.ColorSpecDelegateLibmonet, specFor). P17: Android 17, whose SystemUI computes with external/libmonet
    // android17-release at spec 2026 for every style (which falls back to 2025 or 2021 as upstream's main does): part 2 with that
    // branch's rules (MCU.ColorSpecDelegateLibmonet 'a17', MCU.ColorSpecDelegateLibmonet2026). Any other profile is an error (the page
    // writes nothing then).
    const PROFILES = ['P14', 'P15', 'P15Q1', 'P15Q2', 'P16', 'P16Q1', 'P16Q2', 'P17'];
    // the libmonet branch of a profile (MCU.schemeOf's libmonet argument), undefined for the others
    const LIBMONET = { P16Q1: 'qpr1', P16Q2: 'qpr2', P17: 'a17' };
    // P15 (Android 15.0): P15Q1's names, layout and spec rule (it is not in LIBMONET), on schemes that take libmonet android15-release's
    // two differences from upstream's 2021 where the scheme's spec is 2021 (MCU.schemeOf 'a15': the key colour walk, the on_*_container
    // rules); at 2025 and 2026, and CMF, P15's results are P15Q1's (no Android 15.0 build computes those specs)
    const LIBMONET_A15 = { P15: 'a15' };
    function profileOf(opts) {
        const p = opts && opts.profile !== undefined && opts.profile !== null ? String(opts.profile) : 'P14';
        if (PROFILES.indexOf(p) < 0) throw new Error('Profile not supported: ' + opts.profile);
        return p;
    }
    // the phone's contrast (UiModeManager.getContrast(), -1..1; 0 when none is given), which every profile's SystemUI passes to its schemes
    function contrastOf(opts) {
        const c = opts ? opts.contrast : undefined;
        if (c === undefined || c === null) return 0.0;
        if (typeof c !== 'number' || !Number.isFinite(c)) throw new Error('Unknown contrast: ' + c);
        return Math.min(1.0, Math.max(-1.0, c)) || 0.0;
    }
    // ---------- Intensity (opts.adjust): the root card's three sliders. Formulas written for this project, applied to a scheme's tonal
    // palettes before anything is derived from them (the 65 shades, the roles, the fixed colours); the error palette is never touched ----------
    const ADJUST_KEYS = ['accentSat', 'bgSat', 'bgLight'];
    // Background lightness: a neutral palette asked for tone t gives the colour at tone t + ADJUST_TONE_STEP * (v - 100) * adjustRamp(t),
    // clamped to 0..100. K = 0.1: ten tone steps at the slider ends (v = 0 or 200). d(t) = min(1, t / 10, (100 - t) / 10): tones 0 and
    // 100 never move (white stays white, black stays black), 99 moves a tenth as far, 95 half as far, 10 to 90 the whole way
    const ADJUST_TONE_STEP = 0.1, ADJUST_RAMP_TONES = 10;
    function adjustRamp(t) { return Math.min(1, t / ADJUST_RAMP_TONES, (100 - t) / ADJUST_RAMP_TONES); }
    function adjustTone(t, v) { return clampDouble(0, 100, t + ADJUST_TONE_STEP * (v - 100) * adjustRamp(t)); }
    // opts.adjust -> { accentSat, bgSat, bgLight } (percent, 100 when left out, clamped to 0..200), or null when there is none or all
    // three are 100 (then nothing is adjusted: every result is the one without adjust). A value that is not a number throws, as an
    // adjust that is not an object does
    function adjustOf(opts) {
        const a = opts ? opts.adjust : undefined;
        if (a === undefined || a === null) return null;
        if (typeof a !== 'object' || Array.isArray(a)) throw new Error('Unknown adjust: ' + a);
        const out = {};
        for (const k of ADJUST_KEYS) {
            const v = a[k];
            if (v === undefined || v === null) out[k] = 100;
            else if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error('Unknown adjust.' + k + ': ' + v);
            else out[k] = Math.min(200, Math.max(0, v));
        }
        return out.accentSat === 100 && out.bgSat === 100 && out.bgLight === 100 ? null : out;
    }
    // a palette as the sliders leave it: the palette of the same hue with chroma x factor (the part's own TonalPalette.fromHueAndChroma,
    // so its key colour is that part's search; factor 1 keeps the palette itself), whose tone t is that palette's colour at mapTone(t)
    // (adjustTone for Background lightness, else t). Part 1's DynamicColor.getHct and part 2's chroma-multiplier path read mapTone;
    // every other reader sees an ordinary palette (hue, chroma, keyColor, tone, getHct)
    class AdjustedPalette {
        constructor(Palette, base, factor, light) {
            const inner = factor === 1 ? base : Palette.fromHueAndChroma(base.hue, base.chroma * factor);
            this.inner = inner; this.hue = inner.hue; this.chroma = inner.chroma; this.keyColor = inner.keyColor;
            this.baseChroma = base.chroma; this.chromaFactor = factor; this.light = light;
        }
        mapTone(t) { return this.light === 100 ? t : adjustTone(t, this.light); }
        tone(t) { return this.inner.tone(this.mapTone(t)); }
        getHct(t) { return this.inner.getHct(this.mapTone(t)); }
    }
    // the scheme with its five palettes adjusted (Palette: the part's TonalPalette); a palette the sliders leave alone stays as it is
    function adjustScheme(s, adj, Palette) {
        if (!adj) return s;
        const a = adj.accentSat / 100, b = adj.bgSat / 100, v = adj.bgLight;
        const wrap = (p, factor, light) => factor === 1 && light === 100 ? p : new AdjustedPalette(Palette, p, factor, light);
        s.primaryPalette = wrap(s.primaryPalette, a, 100);
        s.secondaryPalette = wrap(s.secondaryPalette, a, 100);
        s.tertiaryPalette = wrap(s.tertiaryPalette, a, 100);
        s.neutralPalette = wrap(s.neutralPalette, b, v);
        s.neutralVariantPalette = wrap(s.neutralVariantPalette, b, v);
        return s;
    }
    // ---------- Secondary and tertiary accents (opts.accents): the root card's two colours. Rules written for this project (Android and
    // Google's library have no such option): a colour set for the secondary (accent2) or tertiary (accent3) row replaces that palette of
    // the scheme before anything is derived from it (the 13 shades, the roles, the fixed colours) and before the Intensity sliders, so
    // Accent saturation scales it too. The primary, neutral, neutral variant and error palettes are never touched ----------
    const ACCENT_KEYS = ['secondary', 'tertiary'];
    // opts.accents -> { secondary, tertiary } (each an opaque argb number, or null for "from the style"), or null when there are none or
    // both are null (then every result is the one without accents). A value that is not 'RRGGBB' or '#RRGGBB' (any case), null or left
    // out throws, as accents that are not an object do; other keys are ignored
    function accentsOf(opts) {
        const a = opts ? opts.accents : undefined;
        if (a === undefined || a === null) return null;
        if (typeof a !== 'object' || Array.isArray(a)) throw new Error('Unknown accents: ' + a);
        const out = { secondary: null, tertiary: null };
        for (const k of ACCENT_KEYS) {
            const v = a[k];
            if (v === undefined || v === null) continue;
            const m = typeof v === 'string' ? /^#?([0-9a-fA-F]{6})$/.exec(v) : null;
            if (!m) throw new Error('Unknown accents.' + k + ': ' + v);
            out[k] = (parseInt(m[1], 16) | 0xFF000000) >>> 0;
        }
        return out.secondary === null && out.tertiary === null ? null : out;
    }
    // the angle between two hues in degrees, 0..180
    function hueDistance(a, b) { const d = Math.abs(sanitizeDegreesDouble(a) - sanitizeDegreesDouble(b)); return Math.min(d, 360 - d); }
    const ACCENT_HUE_TOLERANCE = 0.5;
    // the palette a colour x gives its row: the primary palette P of the scheme built from x as the seed (x as it is: the grey rule of
    // seed() is Android's for a wallpaper colour, and x is the user's own) by build, which has the base scheme's style, spec, mode,
    // contrast and part. P as it is when its hue is x's (within ACCENT_HUE_TOLERANCE); else, for a style that turns the primary's hue
    // (Expressive at 2021, Fruit Salad), the palette at x's hue with P's chroma (the part's own TonalPalette.fromHueAndChroma), so the
    // hue picked is kept in every style and the style sets how strong it is. H, Palette: the part's Hct and TonalPalette
    function accentPalette(x, build, H, Palette) {
        const h = H.fromInt(x), p = build(h).primaryPalette;
        return hueDistance(p.hue, h.hue) <= ACCENT_HUE_TOLERANCE ? p : Palette.fromHueAndChroma(h.hue, p.chroma);
    }
    // the scheme with its secondary and tertiary palettes replaced by the accents set (acc from accentsOf); nothing else changes
    function accentScheme(s, acc, build, H, Palette) {
        if (!acc) return s;
        if (acc.secondary !== null) s.secondaryPalette = accentPalette(acc.secondary, build, H, Palette);
        if (acc.tertiary !== null) s.tertiaryPalette = accentPalette(acc.tertiary, build, H, Palette);
        return s;
    }
    // ---------- Accurate shades (opts.accurate): the root card's switch. A rule written for this project (Android and Google's library
    // have no such option). Off: the scheme's three accent palettes (primary, secondary, tertiary: accent1, accent2, accent3), as the
    // accents and the Intensity sliders leave them, answer tone 90 with their tone-70 colour. Everything that takes a colour from those
    // palettes at tone 90 gets the tone-70 colour: shade 100 of each accent row, and each role, fixed colour and palette_key_color whose
    // tone, computed by the spec's own rules and contrast, is exactly 90. The tones themselves are not changed (a rule that reads a
    // palette to compute a tone reads it as it was), and the neutral, neutral variant and error palettes are never touched ----------
    const ACCURATE_ROWS = ['accent1', 'accent2', 'accent3'];
    const ACCURATE_FROM_TONE = 90, ACCURATE_TO_TONE = 70;
    // opts.accurate -> true (on: every result is the one without the option) when it is true, null or left out; false (off) when it is
    // false. Any other value throws, as an adjust that is not an object does
    function accurateOf(opts) {
        const a = opts ? opts.accurate : undefined;
        if (a === undefined || a === null || a === true) return true;
        if (a === false) return false;
        throw new Error('Unknown accurate: ' + a);
    }
    function accurateTone(t) { return t === ACCURATE_FROM_TONE ? ACCURATE_TO_TONE : t; }
    // an accent palette with the switch off: base's hue, chroma and key colour; tone, getHct and mapTone (the colour lookups of both parts:
    // part 1's DynamicColor.getHct and part 2's chroma-multiplier path read mapTone, the rest tone or getHct) give tone 70 where tone
    // 90 is asked for, then whatever base does with that tone (an AdjustedPalette's own map)
    class AccuratePalette {
        constructor(base) { this.base = base; this.hue = base.hue; this.chroma = base.chroma; this.keyColor = base.keyColor; }
        mapTone(t) { const r = accurateTone(t); return this.base.mapTone ? this.base.mapTone(r) : r; }
        tone(t) { return this.base.tone(accurateTone(t)); }
        getHct(t) { return this.base.getHct(accurateTone(t)); }
    }
    // the palette as it was before the switch: what the tone rules that read a palette (Fidelity and Content) and the shade rules use
    function accurateBase(p) { return p instanceof AccuratePalette ? p.base : p; }
    // the scheme with its three accent palettes wrapped when accurate is off (after the accents and the sliders); nothing else changes
    function accurateScheme(s, accurate) {
        if (accurate) return s;
        s.primaryPalette = new AccuratePalette(s.primaryPalette);
        s.secondaryPalette = new AccuratePalette(s.secondaryPalette);
        s.tertiaryPalette = new AccuratePalette(s.tertiaryPalette);
        return s;
    }
    // a row's 13 shades (SHADE_KEYS order) as written: the shades themselves when accurate is on or the row is not an accent row; else a
    // copy whose shade 100 (tone 90) is its shade 300 (tone 70). The shade rules read the palette before the switch (accurateBase), so
    // this is where shade 100 is redirected, on every profile (P14's rule also caps the chroma of shades 10 and 50, not of 100 or 300)
    function accurateShades(row, shades, accurate) {
        if (accurate || ACCURATE_ROWS.indexOf(row) < 0) return shades;
        const out = shades.slice();
        out[SHADE_KEYS.indexOf(100)] = out[SHADE_KEYS.indexOf(300)];
        return out;
    }
    // P14's 13 shades of a row: part 1's shadesOf (Android 14's rule). On an adjusted palette, the same rule: shade s at tone
    // mapTone(shadeTone(s)), the palette's hue, and the shade's own chroma (capped at 40 for shades 10 and 50) x the factor
    function shadesP14(p) {
        if (!(p instanceof AdjustedPalette)) return shadesOf(p);
        return SHADE_KEYS.map(shade => {
            const chroma = (shade === 10 || shade === 50 ? Math.min(40.0, p.baseChroma) : p.baseChroma) * p.chromaFactor;
            return Hct.from(p.hue, chroma, p.mapTone(shadeTone(shade))).toInt();
        });
    }
    // frameworks/libs/systemui monet DynamicColors.java (android15-qpr2-release = android16-release): the 44 roles plus six more, each
    // named by generateSysUINames (DynamicColor.name with palette_key_color moved to the front) and the list sorted by name; the
    // fixed colours sorted the same way. Android 15 QPR1's DynamicColors keeps Android 14's 44 names and order (the lists above).
    const byName = (a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);
    const ROLES_A15Q2 = ALL_DYNAMIC_COLORS_MAPPED.concat([['inverse_surface', 'inverseSurface'], ['inverse_on_surface', 'inverseOnSurface'],
        ['inverse_primary', 'inversePrimary'], ['shadow', 'shadow'], ['scrim', 'scrim'], ['surface_tint', 'surfaceTint']]).sort(byName);
    const FIXED_A15Q2 = FIXED_COLORS_MAPPED.slice().sort(byName);
    // Android 16 QPR1 and QPR2 (the same DynamicColors.java, on libmonet's allDynamicColors): the 50 plus palette_key_color_error and
    // the four dim colours, 55 sorted by name; the fixed colours as on QPR2
    const DIM_ROLES = ['primaryDim', 'secondaryDim', 'tertiaryDim', 'errorDim'];
    const ROLES_A16Q = ROLES_A15Q2.concat([['palette_key_color_error', 'errorPaletteKeyColor'], ['primary_dim', 'primaryDim'],
        ['secondary_dim', 'secondaryDim'], ['tertiary_dim', 'tertiaryDim'], ['error_dim', 'errorDim']]).sort(byName);
    // Android 17 (DynamicColors.java identical to QPR2's): the same 55 roles and 12 fixed colours
    const PROFILE_COLORS = { P14: [ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED], P15: [ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED],
        P15Q1: [ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED],
        P15Q2: [ROLES_A15Q2, FIXED_A15Q2], P16: [ROLES_A15Q2, FIXED_A15Q2], P16Q1: [ROLES_A16Q, FIXED_A15Q2], P16Q2: [ROLES_A16Q, FIXED_A15Q2],
        P17: [ROLES_A16Q, FIXED_A15Q2] };
    // the roles written for a scheme: the profile's list, less the dim colours on a libmonet scheme at 2021 (libmonet's ColorSpec2021
    // returns null for them, so SystemUI leaves them out)
    // P17 (libmonet a17, which falls back as upstream's main): the spec asked for decides (asked, the spec of opts), not the scheme's: at
    // 2021 the dims are left out; at 2025 and 2026 SystemUI writes them for every style, also where the style falls back to 2021
    // (Rainbow, Fruit Salad, Monochrome, Content, Fidelity: MaterialDynamicColors takes ColorSpec2025's dim definitions, which are not
    // tied to a spec, on that 2021 scheme), so they are written there too. asked left out: the scheme's spec
    function roleListFor(profile, s, asked) {
        const list = PROFILE_COLORS[profile][0];
        const fallsBack = s && s.libmonet && MCU.LIBMONET_FALLS_BACK[s.libmonet];
        const at2021 = s && s.libmonet && (fallsBack && asked !== undefined ? asked : s.specVersion) === '2021';
        if (!at2021) return list;
        return list.filter(([name, role]) => DIM_ROLES.indexOf(role) < 0);
    }
    // the profiles whose ThemeOverlayController writes the fixed colours as _light (light scheme) and _dark (dark scheme), like the roles:
    // Android 17 (its assignColorsToOverlay has no fixed branch, TOC android17-release :708-718; core/res makes the unsuffixed names
    // aliases of _light in values/ and of _dark in values-night/). Every other profile writes them unsuffixed from the light scheme
    const FIXED_BOTH_MODES = { P17: true };
    // ThemeOverlayController (frameworks/base, android15-qpr1-release, android15-qpr2-release, android16-release) writes the neutral
    // overlay (neutral1, neutral2) before the accent one (accent1-3), each row in the order of a HashMap of SHADE_KEYS (TonalPalette's
    // Collectors.toMap), then for each role _light and _dark, then the fixed colours
    const PALETTE_WRITE_ROWS = [['neutral1', 'neutralPalette'], ['neutral2', 'neutralVariantPalette'], ['accent1', 'primaryPalette'],
        ['accent2', 'secondaryPalette'], ['accent3', 'tertiaryPalette']];
    const SHADE_WRITE_ORDER = [0, 800, 100, 900, 200, 1000, 10, 300, 400, 50, 500, 600, 700];
    // frameworks/libs/systemui monet TonalPalette.getAtTone: shade s is libmonet tone((int) ((1000 - s) / 10)) of the palette's hue and
    // chroma, so shade 500 is tone 50, with no chroma cap (part 1's shadesOf is Android 14's rule)
    // (on an adjusted palette: its hue and chroma, at mapTone of that tone)
    function shadeA15(palette, shade) {
        const tone = Math.trunc((1000 - shade) / 10);
        return MCU.Hct.from(palette.hue, palette.chroma, palette.mapTone ? palette.mapTone(tone) : tone).toInt();
    }
    // P14: part 1's DynamicScheme at 2021, part 2's otherwise (its own Hct of the seed; SchemeCmf throws for a spec other than 2026).
    // P15, P15Q1, P15Q2, P16: part 2 at every spec (2021 included; P15 at 2021 with libmonet android15-release's rules, LIBMONET_A15).
    // Each at the contrast asked for, its secondary and tertiary palettes replaced by opts.accents, then its palettes adjusted by
    // opts.adjust, then (opts.accurate false) its accent palettes wrapped.
    function scheme(style, color, isDark, opts) {
        const variant = variantOf(style), spec = specOf(opts), profile = profileOf(opts), contrast = contrastOf(opts), adj = adjustOf(opts), acc = accentsOf(opts), argb = seed(style, color);
        const accurate = accurateOf(opts), dark = !!isDark;
        if (profile === 'P14' && !inPart2(variant, spec)) {
            const build1 = h => makeScheme(variant, h, dark, contrast);
            return accurateScheme(adjustScheme(accentScheme(build1(Hct.fromInt(argb)), acc, build1, Hct, TonalPalette), adj, TonalPalette), accurate);
        }
        const lm = LIBMONET[profile] || LIBMONET_A15[profile], build2 = h => MCU.schemeOf(variant, h, dark, contrast, spec, lm);
        // (a P15 scheme at 2021: the palettes the accents and the sliders build take libmonet android15-release's key colour, as its own do)
        const s2 = build2(MCU.Hct.fromInt(argb)), Palette2 = s2.a15 ? MCU.TonalPaletteA15 : MCU.TonalPalette;
        return accurateScheme(adjustScheme(accentScheme(s2, acc, build2, MCU.Hct, Palette2), adj, Palette2), accurate);
    }
    // a role's colour on a scheme of either part. Part 2: upstream's role of that name, or, for the Android-only roles, part 1's
    // definition on a scheme that carries part 2's palettes (those roles read the palettes' hue and chroma, isDark and the contrast level)
    const androidView = s => ({ isDark: s.isDark, contrastLevel: s.contrastLevel, variant: s.variant, sourceColorHct: s.sourceColorHct,
        primaryPalette: s.primaryPalette, secondaryPalette: s.secondaryPalette, tertiaryPalette: s.tertiaryPalette,
        neutralPalette: s.neutralPalette, neutralVariantPalette: s.neutralVariantPalette, errorPalette: s.errorPalette });
    // A libmonet scheme (P16Q1, P16Q2): libmonet's role, and at 2025 its control_activated, control_normal and text_primary_inverse
    // (other roles under their names); the other Android-only roles, and those three at 2021, as above
    const LIBMONET_REMAPPED_ROLES = ['controlActivated', 'controlNormal', 'textPrimaryInverse'];
    // P17 (libmonet 'a17'): the 2026 delegate for a scheme at 2026 (CMF), and the renamed roles at 2025 and 2026
    function roleArgb(s, role) {
        if (!(s instanceof MCU.DynamicScheme)) return MDC[role]().getArgb(s);
        const spec = !s.libmonet ? MCU.colorSpec : s.specVersion === '2026' ? MCU.colorSpecLibmonet2026[s.libmonet] : MCU.colorSpecLibmonet[s.libmonet];
        if (s.libmonet && s.specVersion >= '2025' && LIBMONET_REMAPPED_ROLES.indexOf(role) >= 0) return spec[role]().getArgb(s);
        if (ANDROID_ONLY_ROLES.indexOf(role) >= 0) return MDC[role]().getArgb(androidView(s));
        return spec[role]().getArgb(s);
    }
    // P14: the palettes of the light scheme (PR 3), at 2025 and 2026 of the scheme of the mode opts.dark names, with part 1's shades.
    // P15, P15Q1, P15Q2, P16: of the scheme of the mode opts.dark names, with Android 15/16's shades. Shades in SHADE_KEYS order; with
    // opts.accurate false, accentN's shade 100 is its shade 300 (hue, chroma and key stay the palette's: the switch changes colours read
    // from it, not the palette's key colour).
    function palettes(style, color, opts) {
        const variant = variantOf(style), spec = specOf(opts), profile = profileOf(opts);
        const s = scheme(style, color, (profile !== 'P14' || inPart2(variant, spec)) && !!(opts && opts.dark), opts);
        const accurate = accurateOf(opts), out = { seed: hex6(s.sourceColorArgb), style: String(style).toUpperCase() };
        for (const [row, field] of PALETTE_ROWS) {
            const p = s[field], q = accurateBase(p);
            const shades = accurateShades(row, profile === 'P14' ? shadesP14(q) : SHADE_KEYS.map(shade => shadeA15(q, shade)), accurate);
            out[row] = { hue: p.hue, chroma: p.chroma, key: hex6(p.keyColor.toInt()), shades: shades.map(hex6) };
        }
        return out;
    }
    // the profile's roles (44, or 50 on P15Q2 and P16, 55 on P16Q1 and P16Q2 or 51 there at 2021) on the scheme of the mode isDark
    // names, in the profile's order
    function roles(style, color, isDark, opts) {
        const s = scheme(style, color, isDark, opts);
        return roleListFor(profileOf(opts), s, specOf(opts)).map(([name, role]) => ({ name, argb: hex8(roleArgb(s, role)) }));
    }
    // What the profile's SystemUI writes, in its order (the custom SystemUI colours of 15, 16 and 17 are not part of it).
    // P14, 165 entries: the 65 shades (part 1's rule) of the light scheme's palettes, at 2025 and 2026 of the scheme for opts.dark (as
    // Android 16's ThemeOverlayController takes them from the mode the phone is in), accent1-3 then neutral1-2 in SHADE_KEYS order; the
    // 44 roles of the dark scheme, then of the light one; the 12 fixed colours of the light scheme.
    // P15 and P15Q1 (165) and P15Q2 and P16 (177): the 65 shades (Android 15/16's rule) of the scheme for opts.dark, neutral1-2 then accent1-3,
    // each row in SHADE_WRITE_ORDER; each role _light then _dark (44 roles in Android 14's order, or 50 sorted by name); the 12 fixed
    // colours of the light scheme (in Android 14's order, or sorted by name). P16Q1, P16Q2 (252, 244 at 2021) and P17 (264, 256 at
    // 2021): each shade (accent1-3, neutral1-2, SHADE_KEYS order) and each role _light (light scheme) then _dark (dark scheme); the 12
    // fixed colours unsuffixed from the light scheme, on P17 each _light then _dark. With opts.accurate false, the entries
    // system_accentN_100 (N = 1..3) carry the value of system_accentN_300, and each role and fixed colour taken from an accent palette
    // at tone 90 carries that palette's tone-70 colour (see Accurate shades).
    function entries(style, color, opts) {
        const variant = variantOf(style), spec = specOf(opts), profile = profileOf(opts);
        const light = scheme(style, color, false, opts), dark = scheme(style, color, true, opts), accurate = accurateOf(opts), out = [];
        const [roleList, fixedList] = PROFILE_COLORS[profile];
        const add = (name, argb) => out.push({ resource: 'android:color/system_' + name, argb: hex8(argb) });
        if (profile === 'P14') {
            const base = inPart2(variant, spec) && opts && opts.dark ? dark : light;
            for (const [row, field] of PALETTE_ROWS) {
                const shades = accurateShades(row, shadesP14(accurateBase(base[field])), accurate);
                SHADE_KEYS.forEach((shade, i) => add(row + '_' + shade, shades[i]));
            }
            for (const s of [dark, light]) {
                const suffix = s.isDark ? 'dark' : 'light';
                for (const [name, role] of roleList) add(name + '_' + suffix, roleArgb(s, role));
            }
        } else if (LIBMONET[profile]) {
            for (const [row, field] of PALETTE_ROWS) {
                const lightShades = accurateShades(row, SHADE_KEYS.map(shade => shadeA15(accurateBase(light[field]), shade)), accurate);
                const darkShades = accurateShades(row, SHADE_KEYS.map(shade => shadeA15(accurateBase(dark[field]), shade)), accurate);
                SHADE_KEYS.forEach((shade, i) => { add(row + '_' + shade + '_light', lightShades[i]); add(row + '_' + shade + '_dark', darkShades[i]); });
            }
            for (const [name, role] of roleListFor(profile, light, spec)) { add(name + '_light', roleArgb(light, role)); add(name + '_dark', roleArgb(dark, role)); }
        } else {
            const current = opts && opts.dark ? dark : light;
            for (const [row, field] of PALETTE_WRITE_ROWS) {
                const shades = accurateShades(row, SHADE_KEYS.map(shade => shadeA15(accurateBase(current[field]), shade)), accurate);
                for (const shade of SHADE_WRITE_ORDER) add(row + '_' + shade, shades[SHADE_KEYS.indexOf(shade)]);
            }
            for (const [name, role] of roleList) { add(name + '_light', roleArgb(light, role)); add(name + '_dark', roleArgb(dark, role)); }
        }
        if (FIXED_BOTH_MODES[profile]) for (const [name, role] of fixedList) { add(name + '_light', roleArgb(light, role)); add(name + '_dark', roleArgb(dark, role)); }
        else for (const [name, role] of fixedList) add(name, roleArgb(light, role));
        return out;
    }

    return {
        STYLES: API_STYLES, SPECS, PROFILES, GOOGLE_BLUE: hex6(GOOGLE_BLUE), MIN_CHROMA, SHADE_KEYS,
        hct, seed: (style, color) => hex6(seed(style, color)), specFor, palettes, roles, entries, scheme,
        lib: { Hct, Cam16, HctSolver, ViewingConditions, Contrast, TonalPalette, TemperatureCache, DislikeAnalyzer, DynamicScheme, DynamicColor, Variant, MDC,
            ALL_DYNAMIC_COLORS_MAPPED, FIXED_COLORS_MAPPED, ROLES_A15Q2, FIXED_A15Q2, ANDROID_ONLY_ROLES, SHADE_WRITE_ORDER,
            ROLES_A16Q, DIM_ROLES, LIBMONET, LIBMONET_A15, LIBMONET_REMAPPED_ROLES, FIXED_BOTH_MODES, roleListFor, roleArgb, PROFILE_COLORS, PALETTE_ROWS, specOf,
            schemePalettes, shadesOf, shadeTone, shadeA15, shadesP14, parseArgb, hex6, hex8, variantOf, profileOf, contrastOf,
            adjustOf, adjustTone, adjustRamp, adjustScheme, AdjustedPalette, ADJUST_TONE_STEP, ADJUST_RAMP_TONES,
            accentsOf, accentPalette, accentScheme, hueDistance, ACCENT_HUE_TOLERANCE, accurateOf, accurateTone, accurateBase, accurateScheme,
            accurateShades, AccuratePalette, ACCURATE_ROWS, ACCURATE_FROM_TONE, ACCURATE_TO_TONE, mcu: MCU }
    };
});
