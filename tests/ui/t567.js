// The overlay plan per Android level (v7.22.0, ColorBlendr parity; T-049's profiles replaced by MonetPlan.cb's own level rule): the plan Apply
// writes is MonetPlan.cb.entries(opts, sdk) for the level getSystemPalette reports, every one taken by the plan checks of the Java side (the
// mock's copy of OverlayRules): 168 entries on Android 12 and 13 (no night ones, one overlay per entry), 181 on 14 (13 with config night),
// 236 on 15, 250 on 16, 372 on 17 (the suffixed _light / _dark names, no unsuffixed palette shade); the record keeps the level and the build;
// the note on colours written for another level shows after a system update (the level changed), not after a monthly build; without a level
// in the record (one from before this version) it says nothing.
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
  await page.addInitScript(() => { window.__sdk = 31; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const kv = key => ev(k => kvGet(k, null), key);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const toast = () => page.locator('#toastMsg').innerText();
  const stale = () => ev(() => { const e = document.getElementById('ovlRootStale'); return e.style.display === 'none' ? '' : e.innerText; });
  const level = async (sdk, build) => { await ev(([s, bl]) => { window.__sdk = s; if (bl) window.__build = bl; kvSet('ovl_root_applied', null); window.__ovl.filter(o => o.id.startsWith('com.android.shell:aam_monet')).forEach(o => { o.state = 0; }); ovlReadPalette(); ovlRender(); }, [sdk, build || null]); await sleep(100); };
  const AAM = 'com.android.shell:aam_monet';

  await ev(() => switchView('overlays')); await sleep(400);
  const want = { 31: [168, 0], 33: [168, 0], 34: [181, 13], 35: [236, 13], 36: [250, 13], 37: [372, 13] };
  for (const sdk of [31, 33, 34, 35, 36, 37]) {
    await level(sdk); await reset(); await apply();
    const f = await ev(() => window.__ovlCalls.fab[0]), rec = await kv('ovl_root_applied');
    const night = f.entries.filter(e => e.config === 'night').length, other = f.entries.filter(e => e.config && e.config !== 'night').length;
    const ids = sdk >= 34 ? 1 : f.entries.length;
    check(sdk + '. Android ' + ({ 31: 12, 33: 13, 34: 14, 35: 15, 36: 16, 37: 17 }[sdk]) + ': ' + want[sdk][0] + ' entries (' + want[sdk][1] + ' with config night), taken by the plan checks, ' + (sdk >= 34 ? 'one overlay' : 'one overlay per entry') + ', the record with the level',
      f.entries.length === want[sdk][0] && night === want[sdk][1] && other === 0 && (await toast()) === 'Theme overlay written' && rec && rec.ids.length === ids && rec.sdk === sdk && (sdk >= 34 ? rec.ids[0] === AAM : rec.ids[0] === AAM + '_system_accent1_0') && f.entries.every(e => /^android:color\/[a-z0-9_]+$/.test(e.resource) && /^0x[0-9A-F]{8}$/.test(e.argb)),
      JSON.stringify([f.entries.length, night, other, await toast(), rec && rec.ids.length, rec && rec.sdk]));
    check('   the same as the maths give for that level', await ev(s => { const r = kvGet('ovl_root_applied', null), e = MonetPlan.cb.entries(r.opts, s), f = window.__ovlCalls.fab[0]; return e.length === f.entries.length && e.every((x, i) => x.resource === f.entries[i].resource && '0x' + x.argb.slice(1) === f.entries[i].argb); }, sdk));
  }
  check('   on Android 17 the names are suffixed (_light / _dark), no unsuffixed palette shade', await ev(() => { const f = window.__ovlCalls.fab[0]; return f.entries.some(e => e.resource === 'android:color/system_accent1_500_light') && !f.entries.some(e => /system_(accent[1-3]|neutral[12]|error)_[0-9]+$/.test(e.resource)); }));
  check('   and the Android 12 plan names the base shades unsuffixed (system_accent1_500), with the key colours and the device default accent in both modes, no night entry', await ev(() => { const o = kvGet('ovl_root_applied', null).opts; const e = MonetPlan.cb.entries(o, 31); return e.some(x => x.resource === 'android:color/system_accent1_500') && !e.some(x => /^android:color\/system_(accent[1-3]|neutral[12])_[0-9]+_(light|dark)$/.test(x.resource)) && e.some(x => x.resource === 'android:color/system_palette_key_color_primary_light') && e.some(x => x.resource === 'android:color/accent_device_default_dark') && !e.some(x => x.config); }));

  // the stale note: the level changed since the write
  await ev(() => { window.__sdk = 36; ovlReadPalette(); ovlRender(); }); await sleep(100);
  check('S. the phone now on Android 16 with colours written for 17: the note says Android was updated and to tap Apply theme', (await stale()) === 'Android was updated after these colors were written. Tap Apply theme to write them for this version.', await stale());
  await ev(() => { window.__sdk = 37; ovlReadPalette(); ovlRender(); }); await sleep(100);
  check('   back on 17: no note', (await stale()) === '');
  await ev(() => { window.__build = 'google/mock/mock:17/ZP1A.251105.002/2222222:user/release-keys'; ovlReadPalette(); ovlRender(); }); await sleep(100);
  check('   a monthly build of the same level: no note (the entries do not depend on the build)', (await stale()) === '' && (await ev(() => ovlBuild())) !== (await kv('ovl_root_applied')).build);
  await ev(() => { window.__root = false; checkAllWorkingModes(false); window.__sdk = 36; ovlReadPalette(); ovlRender(); }); await sleep(150);
  check('   without uid 0 the note says nothing (Apply could not write them again)', (await stale()) === '');
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(150);
  check('   with uid 0 back it shows', (await stale()) !== '');
  await ev(id => { kvSet('ovl_root_applied', { ids: [id], t: Date.now(), style: 'TONAL_SPOT' }); ovlRender(); }, AAM);
  check('   a record without a level (one from before this version) says nothing', (await stale()) === '');
  await reset(); await apply();
  check('   Apply writes the colours for the level the phone runs now (250) and the note goes', (await ev(() => window.__ovlCalls.fab[0].entries.length)) === 250 && (await kv('ovl_root_applied')).sdk === 36 && (await stale()) === '');

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
