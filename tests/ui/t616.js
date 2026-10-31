// What Apply theme writes with Super User (v7.22.0, ColorBlendr parity): the plan overlayFabricate gets is MonetPlan.cb.entries for the phone's
// Android level, each entry { resource: 'android:color/<name>', argb: '0xAARRGGBB' } with config 'night' where Android takes one (passed through
// unchanged); the controls reach it: Tint text color off puts plain white text on the dark surfaces (system_on_surface_dark 0xFFFFFFFF),
// system_primary_fixed comes once without and once with the night configuration, Contrast High changes system_primary_light against Standard,
// Pure black puts system_surface_dark at 0xFF000000, an own secondary accent moves the accent2 shades, a root-only style (Monochrome) greys
// accent1; the theme setting takes the list style (Tonal Spot for a root-only one); the record (kv ovl_root_applied) keeps the options, the
// Android level and the build, and the preview follows the choices. The mock reports the build family each Android level really
// has (__profile: 37 P17, 36 P16Q1 and P16Q2, 35 P15Q1, 34 P14, 33 P12), and the plan follows it: on Android 16 QPR1 and QPR2 it names no
// unsuffixed palette shade (Java refuses them there as aliases of _light / _dark), carries the _light / _dark shades those builds define
// (16.0 does not) and reaches overlayFabricate; on 17 the unsuffixed fixed
// colours come paired with their night entries, which Java takes.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => { window.__sdk = 37; window.__profile = 'P17'; window.__root = true; window.__wallpaper = ['4F8A5B']; window.__build = 'google/mock/mock:17/ZP1A.250905.001/1234567:user/release-keys'; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(150); };
  const calls = () => ev(() => window.__ovlCalls);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  const lastPlan = async () => { const c = await calls(); return c.fab[c.fab.length - 1]; };
  const entry = (plan, name, night) => plan.entries.find(e => e.resource === 'android:color/' + name && (night ? e.config === 'night' : !e.config));
  const PAL = /^android:color\/system_(accent[123]|neutral[12]|error)_\d+$/, SUF = /^android:color\/system_(accent[123]|neutral[12]|error)_\d+_(light|dark)$/;
  const FIXED = /^android:color\/system_((primary|secondary|tertiary)_fixed(_dim)?|on_(primary|secondary|tertiary)_fixed(_variant)?)$/;
  const listed = () => ev(() => { const o = window.__ovl.find(x => x.id === 'com.android.shell:aam_monet'); return o ? o.state : -9; });
  // the phone's Android level and build family as getSystemPalette reports them, the palette read again
  const phone = (sdk, profile) => ev(([s, p]) => { window.__sdk = s; window.__profile = p; ovlReadPalette(); ovlRender(); }, [sdk, profile]);

  await ev(() => switchView('overlays')); await sleep(400);
  check('0. Root and Android 17: the controls are live', await ev(() => ovlRootLive() && document.querySelectorAll('#ovlSwitches .rf-row.is-locked').length === 0));

  // 1) the defaults: the theme setting, then the plan
  await apply();
  let c = await calls(), plan = await lastPlan();
  check('1. Apply writes the theme setting first (wallpaper, Tonal Spot), then one fabricate', c.apply.length === 1 && c.apply[0].source === 'home_wallpaper' && c.apply[0].style === 'TONAL_SPOT' && c.fab.length === 1 && plan.action === 'fabricate', JSON.stringify(c.apply));
  check('   the plan is MonetPlan.cb.entries for Android 17: 372 entries, every one android:color/<name> with 0xAARRGGBB', plan.entries.length === 372 && plan.entries.every(e => /^android:color\/[a-z0-9_]+$/.test(e.resource) && /^0x[0-9A-F]{8}$/.test(e.argb)), plan.entries.length);
  check('   the same entries as the maths give, in the same order, the night configuration passed through unchanged', await ev(p => { const e = MonetPlan.cb.entries(ovlRootApplied().opts, 37); return e.length === p.entries.length && e.every((x, i) => x.resource === p.entries[i].resource && '0x' + x.argb.slice(1) === p.entries[i].argb && (x.config || null) === (p.entries[i].config || null)); }, plan));
  check('   system_primary_fixed comes once plain and once with config night', !!entry(plan, 'system_primary_fixed') && !!entry(plan, 'system_primary_fixed', true) && plan.entries.filter(e => e.config).length === 13, JSON.stringify(plan.entries.filter(e => /system_primary_fixed$/.test(e.resource))));
  check('   with Tint text color on, the text on dark surfaces is tinted (not plain white)', entry(plan, 'system_on_surface_dark').argb !== '0xFFFFFFFF', entry(plan, 'system_on_surface_dark').argb);
  const unqFixed = plan.entries.filter(e => !e.config && FIXED.test(e.resource)), night = new Set(plan.entries.filter(e => e.config === 'night').map(e => e.resource));
  check('   on Android 17 (P17) the 12 unsuffixed fixed colours each come with their night entry, and the mock takes the plan as Java does: the overlay is listed on', unqFixed.length === 12 && unqFixed.every(e => night.has(e.resource)) && (await listed()) === 1 && (await ev(() => ovlPalette.profile)) === 'P17', JSON.stringify([unqFixed.length, await listed()]));
  const rec = await ev(() => kvGet('ovl_root_applied', null));
  check('   the record keeps the ids, the options, the Android level and the build', rec && rec.ids.join() === 'com.android.shell:aam_monet' && rec.style === 'TONAL_SPOT' && rec.opts && rec.opts.tintText === true && rec.opts.contrast === 0 && rec.sdk === 37 && /ZP1A/.test(rec.build) && !rec.pending, JSON.stringify(rec));
  check('   the toast', (await page.locator('#toastMsg').innerText()) === 'Theme overlay written');
  const stdPrimaryLight = entry(plan, 'system_primary_light').argb, stdAccent2 = entry(plan, 'system_accent2_500_light').argb, stdAccent1 = entry(plan, 'system_accent1_500_light').argb;

  // 2) Tint text color off
  await reset();
  await page.locator('#ovlTintSw').click(); await sleep(100);
  check('2. the Tint text color switch goes off and is remembered; nothing is written yet', await ev(() => document.getElementById('ovlTintSw').getAttribute('aria-checked') === 'false' && kvGet('ovl_tint_text', null) === false && window.__ovlCalls.fab.length === 0));
  await apply(); plan = await lastPlan();
  check('   the plan: system_on_surface_dark is plain white, 0xFFFFFFFF', entry(plan, 'system_on_surface_dark').argb === '0xFFFFFFFF', entry(plan, 'system_on_surface_dark').argb);
  check('   and system_on_surface_light plain black', entry(plan, 'system_on_surface_light').argb === '0xFF000000', entry(plan, 'system_on_surface_light').argb);
  check('   the record carries tintText false', await ev(() => kvGet('ovl_root_applied', null).opts.tintText === false));

  // 3) Contrast High
  await reset();
  await page.locator('#ovlContrastSeg button[data-id="ovlCon1"]').click(); await sleep(100);
  check('3. Contrast High is chosen and remembered as 1', await ev(() => document.querySelector('#ovlContrastSeg button.active').innerText === 'High' && kvGet('ovl_contrast', null) === 1));
  await apply(); plan = await lastPlan();
  check('   system_primary_light differs from the Standard one', entry(plan, 'system_primary_light').argb !== stdPrimaryLight, entry(plan, 'system_primary_light').argb + ' vs ' + stdPrimaryLight);
  check('   as the maths say for contrast 1', await ev(p => '0x' + MonetPlan.cb.entries(Object.assign(MonetPlan.cb.defaults(), { seed: '4F8A5B', tintText: false, contrast: 1, dark: true }), 37).find(e => e.resource === 'android:color/system_primary_light').argb.slice(1) === p, entry(plan, 'system_primary_light').argb));
  await page.locator('#ovlContrastSeg button[data-id="ovlCon0"]').click(); await sleep(60);

  // 4) Pure black
  await reset();
  await page.locator('#ovlBlackSw').click(); await sleep(100);
  await apply(); plan = await lastPlan();
  check('4. Pure black: system_surface_dark is 0xFF000000, the record says black', entry(plan, 'system_surface_dark').argb === '0xFF000000' && plan.entries.length === 373 && (await ev(() => kvGet('ovl_root_applied', null).black === true)), entry(plan, 'system_surface_dark').argb + ' ' + JSON.stringify(await ev(() => { const r = kvGet('ovl_root_applied', null); return [r.black, r.opts.black, kvGet('ovl_black', null)]; })));
  await page.locator('#ovlBlackSw').click(); await sleep(60);

  // 5) an own secondary accent (set through the colour picker's own hook, as the sheet's Apply does)
  await reset();
  await ev(() => ovlAccSet('secondary', 'ff0000')); await sleep(100);
  check('5. the Secondary accent is set, shown as #FF0000 with Clear', await ev(() => kvGet('ovl_accent_secondary', null) === 'FF0000' && document.querySelector('#ovlAccSecondary .bubble-hex').innerText === '#FF0000' && !!document.getElementById('ovlAccSecondaryClear')));
  await apply(); plan = await lastPlan();
  check('   the accent2 shades move, accent1 stays', entry(plan, 'system_accent2_500_light').argb !== stdAccent2 && entry(plan, 'system_accent1_500_light').argb === stdAccent1, entry(plan, 'system_accent2_500_light').argb + ' vs ' + stdAccent2);
  check('   the record carries the accent', await ev(() => kvGet('ovl_root_applied', null).opts.secondary === 'FF0000'));
  await page.locator('#ovlAccSecondaryClear').click(); await sleep(60);
  check('   Clear puts it back to From the style', await ev(() => kvGet('ovl_accent_secondary', null) === '' && document.querySelector('#ovlAccSecondary .ovl-acc-from').innerText === 'From the style'));

  // 6) a root-only style: the setting takes Tonal Spot, the overlay the style
  await reset();
  await page.locator('#ovlStyles .ovl-style[data-style="MONOCHROMATIC"]').click(); await sleep(100);
  check('6. Monochrome chosen: the preview names it', await ev(() => ovlStyle === 'MONOCHROMATIC' && /Monochrome/.test(document.getElementById('ovlPreviewLine').innerText)));
  await apply(); c = await calls(); plan = await lastPlan();
  check('   the theme setting gets Tonal Spot, the overlay the Monochrome colours (accent1 500 grey), the record the style', c.apply[0].style === 'TONAL_SPOT' && /^0xFF(..)\1\1$/.test(entry(plan, 'system_accent1_500_light').argb) && (await ev(() => { const r = kvGet('ovl_root_applied', null); return r.style === 'MONOCHROMATIC' && r.opts.style === 'MONOCHROMATIC'; })), JSON.stringify([c.apply[0], entry(plan, 'system_accent1_500_light')]));
  const now = await ev(() => document.getElementById('ovlNow').innerText);
  check('   Colors in use still reads the setting (Tonal Spot), the preview the overlay', /Tonal Spot/.test(now), now);

  // 7) CMF needs 2026: greyed at 2021, open at 2026; leaving 2026 with CMF chosen falls back to Tonal Spot
  check('7. CMF is greyed at spec 2021', await ev(() => document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]').disabled));
  await page.locator('#ovlSpecSeg button[data-spec="2026"]').click(); await sleep(100);
  check('   at 2026 it opens', await ev(() => !document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]').disabled && kvGet('ovl_spec', null) === '2026'));
  await page.locator('#ovlStyles .ovl-style[data-style="CMF"]').click(); await sleep(100);
  await reset(); await apply(); plan = await lastPlan();
  const recCmf = await ev(() => kvGet('ovl_root_applied', null));
  check('   Apply with CMF at 2026: the record carries it, the plan has its 372 entries', recCmf.style === 'CMF' && recCmf.opts.spec === '2026' && plan.entries.length === 372, JSON.stringify([recCmf.style, recCmf.opts.spec]));
  await page.locator('#ovlSpecSeg button[data-spec="2021"]').click(); await sleep(100);
  check('   back to 2021 with CMF chosen: Tonal Spot is chosen instead and CMF greyed again', await ev(() => ovlStyle === 'TONAL_SPOT' && document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]').disabled && document.querySelector('#ovlStyles .ovl-style.active').dataset.style === 'TONAL_SPOT'));

  // 8) Android 16 QPR1 and QPR2 (SDK 36, P16Q1 / P16Q2): Java refuses an unsuffixed palette shade there, so the plan names none, carries the 156 _light / _dark shades QPR1 and QPR2 define, and reaches the mock
  for (const profile of ['P16Q1', 'P16Q2']) {
    await phone(36, profile); await sleep(100);
    await reset(); await apply(); c = await calls(); plan = await lastPlan();
    const rec36 = await ev(() => kvGet('ovl_root_applied', null));
    check('8. Android 16 ' + (profile === 'P16Q1' ? 'QPR1' : 'QPR2') + ' (' + profile + '): the plan reaches overlayFabricate with 328 entries (250 less the 78 unsuffixed shades, plus the 156 _light / _dark shades), no unsuffixed palette name, system_accent1_600_light among them (what Java reads back), and is taken (listed on, the record at 36)', c.fab.length === 1 && plan.action === 'fabricate' && plan.entries.length === 328 && !plan.entries.some(e => PAL.test(e.resource)) && plan.entries.some(e => e.resource === 'android:color/system_accent1_600_light') && (await listed()) === 1 && rec36 && rec36.sdk === 36 && !rec36.pending && (await page.locator('#toastMsg').innerText()) === 'Theme overlay written', JSON.stringify([c.fab.length, plan.entries.length, plan.entries.filter(e => PAL.test(e.resource)).length, await listed()]));
    check('   the same list as MonetPlan.cb.entries(opts, 36, \'' + profile + '\'), with the 156 _light / _dark shades the plan for 37 has (16.0\'s table lacks them, QPR1 and QPR2 define them)', await ev(([p, pr, suf]) => { const re = new RegExp(suf), e = MonetPlan.cb.entries(ovlRootApplied().opts, 36, pr), all = MonetPlan.cb.entries(ovlRootApplied().opts, 37); return e.length === p.entries.length && e.every((x, i) => x.resource === p.entries[i].resource && '0x' + x.argb.slice(1) === p.entries[i].argb) && p.entries.filter(x => re.test(x.resource)).length === 156 && all.filter(x => re.test(x.resource)).map(x => x.resource).join() === p.entries.filter(x => re.test(x.resource)).map(x => x.resource).join(); }, [plan, profile, SUF.source]));
  }
  // Android 16 (P16, SDK 36 before QPR1) and Android 15 QPR1 (P15Q1, 35) keep the unsuffixed shades, which those builds take
  await phone(36, 'P16'); await sleep(100); await reset(); await apply(); plan = await lastPlan();
  check('   Android 16 before QPR1 (P16): 250 entries, the 78 unsuffixed shades kept', plan.entries.length === 250 && plan.entries.filter(e => PAL.test(e.resource)).length === 78 && (await listed()) === 1, plan.entries.length);
  await phone(35, 'P15Q1'); await sleep(100); await reset(); await apply(); plan = await lastPlan();
  check('   Android 15 QPR1 (P15Q1): 236 entries, the 78 unsuffixed shades kept', plan.entries.length === 236 && plan.entries.filter(e => PAL.test(e.resource)).length === 78 && (await listed()) === 1, plan.entries.length);

  // 9) Android 13: one overlay per entry, the night ones skipped; Android 14: one overlay
  await phone(33, 'P12'); await sleep(100);
  await reset(); await apply(); plan = await lastPlan();
  const rec13 = await ev(() => kvGet('ovl_root_applied', null));
  check('9. Android 13 (P12): 168 entries, none with a configuration, one overlay per colour named after it (the 17-era overlay kept in the record while the list shows it on)', plan.entries.length === 168 && plan.entries.every(e => !e.config) && rec13.ids.length === 169 && rec13.ids[0] === 'com.android.shell:aam_monet_system_accent1_0' && rec13.ids[168] === 'com.android.shell:aam_monet' && rec13.sdk === 33, JSON.stringify([plan.entries.length, rec13.ids.slice(0, 2)]));
  await phone(34, 'P14'); await sleep(100);
  check('   with the phone on Android 14 now the note says the colours were written for another version', (await ev(() => document.getElementById('ovlRootStale').innerText)) === 'Android was updated after these colors were written. Tap Apply theme to write them for this version.');
  await reset(); await apply(); plan = await lastPlan();
  const rec14 = await ev(() => kvGet('ovl_root_applied', null));
  check('   Android 14 (P14): 181 entries (13 with night), one overlay, the 13-era ids kept with it while the list shows them on, the note gone', plan.entries.length === 181 && plan.entries.filter(e => e.config === 'night').length === 13 && rec14.ids.includes('com.android.shell:aam_monet') && rec14.ids.length === 169 && rec14.sdk === 34 && (await ev(() => document.getElementById('ovlRootStale').style.display === 'none')), JSON.stringify([plan.entries.length, rec14.ids.length]));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
