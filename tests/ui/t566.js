// Color spec and Contrast on the one Theme screen (v7.22.0, ColorBlendr parity; PR 4's spec folded in): two segment rows (2021 / 2025 / 2026,
// Standard / Medium / High), each a radio group under its name and line, locked rows without uid 0, remembered in the store (ovl_spec,
// ovl_contrast) across a restart, defaults 2021 and Standard; the plan follows them (2025 changes the shades against 2021, Medium and High
// change the roles); CMF opens at 2026 only and leaving 2026 with CMF chosen falls back to Tonal Spot; the focus survives a redraw.
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
  await page.addInitScript(() => { window.__sdk = 36; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const kv = key => ev(k => kvGet(k, null), key);
  const reset = () => ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.fab.length = 0; });
  const settle = () => page.waitForFunction(() => !ovlBusy && sdbReqs.size === 0, null, { timeout: 8000, polling: 25 }).then(() => true, () => false);
  const apply = async () => { await page.locator('#ovlApplyBtn').click(); await settle(); await sleep(120); };
  const lastPlan = () => ev(() => { const f = window.__ovlCalls.fab; return f[f.length - 1]; });
  const seg = id => ev(i => { const g = document.getElementById(i); return g ? { role: g.getAttribute('role'), label: g.getAttribute('aria-label'), items: Array.from(g.querySelectorAll('button')).map(x => x.innerText + (x.classList.contains('active') ? '*' : '') + '/' + x.getAttribute('aria-checked')).join() } : null; }, id);
  const row = box => ev(i => { const r = document.getElementById(i).querySelector('.rf-row'); return { name: r.querySelector('.rf-name').childNodes[0].textContent.trim(), sub: r.querySelector('.rf-name span').innerText, locked: r.classList.contains('is-locked') }; }, box);

  await ev(() => switchView('overlays')); await sleep(400);
  check('1. Color spec: its name and line, a radio group of 2021, 2025 and 2026 with 2021 checked', JSON.stringify(await row('ovlSpecRow')) === JSON.stringify({ name: 'Color spec', sub: 'Google\'s color rules. 2021 is what Android computes itself; CMF needs 2026.', locked: false }) && JSON.stringify(await seg('ovlSpecSeg')) === JSON.stringify({ role: 'radiogroup', label: 'Color spec', items: '2021*/true,2025/false,2026/false' }), JSON.stringify([await row('ovlSpecRow'), await seg('ovlSpecSeg')]));
  check('   Contrast: its name and line, Standard, Medium and High with Standard checked', JSON.stringify(await row('ovlContrastRow')) === JSON.stringify({ name: 'Contrast', sub: 'How far apart the light and dark tones sit.', locked: false }) && JSON.stringify(await seg('ovlContrastSeg')) === JSON.stringify({ role: 'radiogroup', label: 'Contrast', items: 'Standard*/true,Medium/false,High/false' }), JSON.stringify([await row('ovlContrastRow'), await seg('ovlContrastSeg')]));
  check('   nothing stored yet (the defaults are ColorBlendr\'s)', (await kv('ovl_spec')) === null && (await kv('ovl_contrast')) === null && (await ev(() => ovlSpec() === '2021' && ovlContrastLevel() === 0)));

  // 2) the plan follows them
  await reset(); await apply();
  const base = await lastPlan();
  await page.locator('#ovlSpecSeg button[data-spec="2025"]').click(); await sleep(120);
  check('2. 2025 tapped: checked, stored, the focus kept on it', (await seg('ovlSpecSeg')).items === '2021/false,2025*/true,2026/false' && (await kv('ovl_spec')) === '2025' && (await ev(() => document.activeElement && document.activeElement.dataset.spec === '2025')));
  await reset(); await apply();
  let plan = await lastPlan();
  check('   the plan at 2025 differs from the one at 2021 (250 entries on Android 16 either way), and is what the maths give for 2025', plan.entries.length === 250 && base.entries.length === 250 && plan.entries.some((e, i) => e.argb !== base.entries[i].argb) && (await ev(() => { const r = kvGet('ovl_root_applied', null); return r.opts.spec === '2025'; })));
  await page.locator('#ovlContrastSeg button[data-id="ovlCon0.5"]').click(); await sleep(120);
  check('   Medium tapped: checked, stored as 0.5, the focus kept', (await seg('ovlContrastSeg')).items === 'Standard/false,Medium*/true,High/false' && (await kv('ovl_contrast')) === 0.5 && (await ev(() => document.activeElement && document.activeElement.dataset.id === 'ovlCon0.5')));
  await reset(); await apply();
  const medium = await lastPlan();
  const role = (p, n) => p.entries.find(e => e.resource === 'android:color/system_' + n && !e.config).argb;
  check('   Medium changes the roles (system_primary_light) against Standard', role(medium, 'primary_light') !== role(plan, 'primary_light') && (await ev(() => kvGet('ovl_root_applied', null).opts.contrast === 0.5)), role(medium, 'primary_light') + ' vs ' + role(plan, 'primary_light'));
  await page.locator('#ovlContrastSeg button[data-id="ovlCon1"]').click(); await sleep(60);
  await reset(); await apply();
  const high = await lastPlan();
  check('   High changes them again', role(high, 'primary_light') !== role(medium, 'primary_light') && (await kv('ovl_contrast')) === 1, role(high, 'primary_light'));

  // 3) CMF and 2026
  check('3. CMF is greyed at 2025 with its own reason', await ev(() => { const c = document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]'); return c.disabled && c.querySelector('.ovl-lockwhy').innerText === 'Needs Color spec 2026'; }));
  await page.locator('#ovlSpecSeg button[data-spec="2026"]').click(); await sleep(120);
  check('   at 2026 CMF opens', await ev(() => !document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]').disabled));
  await page.locator('#ovlStyles .ovl-style[data-style="CMF"]').click(); await sleep(100);
  await reset(); await apply();
  check('   Apply with CMF: the setting gets Tonal Spot, the record CMF at 2026', await ev(() => window.__ovlCalls.apply[0].style === 'TONAL_SPOT' && kvGet('ovl_root_applied', null).style === 'CMF' && kvGet('ovl_root_applied', null).opts.spec === '2026'));
  await page.locator('#ovlSpecSeg button[data-spec="2025"]').click(); await sleep(120);
  check('   back to 2025 with CMF chosen: Tonal Spot is chosen instead, CMF greyed', await ev(() => ovlStyle === 'TONAL_SPOT' && document.querySelector('#ovlStyles .ovl-style[data-style="CMF"]').disabled && document.querySelector('#ovlStyles .ovl-style.active').dataset.style === 'TONAL_SPOT'));

  // 4) a restart keeps both
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(k => { window.__kvInit = JSON.parse(k); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.addInitScript(() => { window.__sdk = 36; window.__root = true; window.__wallpaper = ['4F8A5B']; });
  await p2.goto(PAGE); await p2.waitForTimeout(500);
  await p2.evaluate(() => switchView('overlays')); await p2.waitForTimeout(400);
  const after = await p2.evaluate(() => ({ spec: Array.from(document.querySelectorAll('#ovlSpecSeg button')).find(x => x.classList.contains('active')).innerText, con: Array.from(document.querySelectorAll('#ovlContrastSeg button')).find(x => x.classList.contains('active')).innerText }));
  check('4. after a restart 2025 and High are still the ones checked', after.spec === '2025' && after.con === 'High', JSON.stringify(after));

  // 5) locked without uid 0: a locked row each, nothing stored changes
  await p2.evaluate(() => { window.__root = false; checkAllWorkingModes(false); }); await p2.waitForTimeout(150);
  const locked = await p2.evaluate(() => ['ovlSpecRow', 'ovlContrastRow'].map(id => { const r = document.getElementById(id).querySelector('.rf-row'); return r.classList.contains('is-locked') + ':' + r.querySelector('.rf-name').childNodes[0].textContent.trim() + ':' + r.querySelector('.pm-lock span').innerText; }));
  check('5. without uid 0 both are locked rows with the glyph and the reason; no segment is drawn', locked.join('|') === 'true:🔒 Color spec:Needs Super User (uid 0) to write Material You colors|true:🔒 Contrast:Needs Super User (uid 0) to write Material You colors' && (await p2.evaluate(() => !document.getElementById('ovlSpecSeg') && !document.getElementById('ovlContrastSeg'))), locked.join('|'));
  await p2.evaluate(() => { ovlPickSpec('2021'); ovlPickContrast('0'); });
  check('   the pickers refuse while locked (the store keeps 2025 and High)', await p2.evaluate(() => kvGet('ovl_spec', null) === '2025' && kvGet('ovl_contrast', null) === 1));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
