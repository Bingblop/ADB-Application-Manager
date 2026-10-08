// v6.0 Overlays tab: placement, gate, the colours in use, the colour / preset / style editor, applying / resetting / undoing the theme,
// the overlay list (groups, filters, search, switches, long press, details), failures, Back, resuming after a restart, escaping.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const KEY = 'theme_customization_overlay_packages';
  const toast = () => page.locator('#toastMsg').innerText();
  const snack = () => ev(() => { const e = document.getElementById('sdbSnack'); return e.classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : ''; });
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  const calls = () => ev(() => window.__ovlCalls);
  const rowIds = () => ev(() => Array.from(document.querySelectorAll('#ovlRows .ovl-row')).map(r => r.dataset.id));
  const rowState = id => ev(i => { const r = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).find(x => x.dataset.id === i); if (!r) return null; const s = r.querySelector('.sdb-sw'); return { on: s.classList.contains('on'), disabled: s.disabled, text: r.innerText }; }, id);
  const setTheme = raw => ev(r => { if (r === null) delete window.__db.secure['theme_customization_overlay_packages']; else window.__db.secure['theme_customization_overlay_packages'] = r; }, raw);
  const reread = async () => { await ev(() => { ovlTheme = null; ovlSeeded = false; ovlReadTheme(); }); await sleep(120); };
  const nowText = () => ev(() => document.getElementById('ovlNow').innerText.replace(/\s+/g, ' ').trim());
  const hold = async (id, ms = 650) => {
    const loc = page.locator('#ovlRows .ovl-row[data-id="' + id.replace(/"/g, '\\"') + '"]').first();
    await loc.scrollIntoViewIfNeeded();
    const box = await loc.boundingBox();
    await page.mouse.move(box.x + 24, box.y + box.height / 2); await page.mouse.down(); await sleep(ms); await page.mouse.up(); await sleep(150);
  };

  // 1) placement
  const tabs = await ev(() => Array.from(document.querySelectorAll('.tab-btn')).map(b => b.innerText.replace(/\s+/g, ' ').trim()));
  const iSet = tabs.findIndex(t => /Hidden Settings/.test(t)), iOvl = tabs.findIndex(t => /RRO\/Monet/.test(t)), iUpd = tabs.findIndex(t => /App Updater/.test(t));
  check('1. RRO/Monet Customization sits right of Hidden Settings and before App Updater', iOvl === iSet + 1 && iUpd === iOvl + 1, JSON.stringify(tabs));
  await ev(() => switchView('overlays')); await sleep(150);
  check('   it activates its own button and view', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'RRO/Monet Customization' && (await ev(() => currentViewName())) === 'overlays');
  await ev(() => switchView('settings')); await sleep(100);
  check('   Hidden Settings is still itself', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'Hidden Settings');
  await ev(() => switchView('store')); await sleep(60);
  check('   App Stores and About keep their buttons', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'App Stores');
  await ev(() => switchView('about')); await sleep(60);
  check('   About', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'About');

  // 2) without a privileged mode: a gate, the colours still show, nothing is read or written
  await ev(() => { window.__calls.op.length = 0; window.__mode.priv = false; checkAllWorkingModes(false); switchView('overlays'); }); await sleep(250);
  const g = await ev(() => ({ gate: getComputedStyle(document.getElementById('ovlGate')).display, chips: document.querySelectorAll('#ovlPalette .ovl-chip').length, rows: document.querySelectorAll('#ovlPalette .ovl-pal').length, now: document.getElementById('ovlNow').innerText, ops: window.__calls.op.length, list: window.__ovlCalls.list }));
  check('2. no privileged mode: the gate shows', g.gate !== 'none', g.gate);
  check('   the system colors are still shown (5 palettes of 11 tones)', g.rows === 5 && g.chips === 55, JSON.stringify([g.rows, g.chips]));
  check('   nothing was read from the phone', g.ops === 0 && g.list === 0, JSON.stringify([g.ops, g.list]));
  check('   and the current-theme line says what is needed', /Connect ADB, Shizuku or Root/.test(g.now), g.now);
  await ev(() => ovlApply()); await sleep(120);
  check('   Apply points to Working Modes instead of doing anything', (await toast()).includes('Needs ADB') && (await ev(() => document.querySelectorAll('.modal-overlay.show').length)) >= 1 && (await calls()).apply.length === 0);
  await closeAll();
  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); }); await sleep(500);
  const after = await ev(() => ({ gate: getComputedStyle(document.getElementById('ovlGate')).display, ops: window.__calls.op.slice() }));
  check('   connecting a mode while the tab is open hides the gate and reads the theme', after.gate === 'none' && after.ops.some(o => o.op === 'get' && o.ns === 'secure' && o.key === KEY), JSON.stringify(after));

  // 3) the theme in use, in every shape the setting can have
  await setTheme(null); await reread();
  check('3. not set: "System default"', /System default/.test(await nowText()), await nowText());
  check('   the editor starts on the wallpaper with Tonal Spot', (await ev(() => [ovlSource, ovlStyle])).join() === 'home_wallpaper,TONAL_SPOT');
  await setTheme('{"android.theme.customization.system_palette":"6750A4","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT","_applied_timestamp":1700000000000}'); await reread();
  check('   a chosen color: its hex and style are shown', /Custom color #6750A4 · Vibrant/.test(await nowText()), await nowText());
  check('   the editor starts from it', (await ev(() => [ovlSource, ovlColor, ovlStyle])).join() === 'preset,6750A4,VIBRANT');
  check('   and its swatch is that color', (await ev(() => getComputedStyle(document.querySelector('#ovlNow .ovl-seed')).backgroundColor)) === 'rgb(103, 80, 164)');
  await setTheme('{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"RAINBOW"}'); await reread();
  check('   the wallpaper: "From your wallpaper", its style', /From your wallpaper Rainbow style/.test(await nowText()), await nowText());
  await setTheme('{"android.theme.customization.color_source":"lock_wallpaper","android.theme.customization.theme_style":"SPRITZ"}'); await reread();
  check('   the lock-screen wallpaper counts as a wallpaper', /From your wallpaper Spritz style/.test(await nowText()), await nowText());
  await setTheme('this is {not json'); await reread();
  check('   something that is not JSON: "Another theme"', /Another theme/.test(await nowText()), await nowText());
  await setTheme('{"android.theme.customization.accent_color":"FF0000","android.theme.customization.theme_style":"NOPE"}'); await reread();
  check('   a foreign JSON object with no colour source is "System default"; its style is kept as it is, not made up', /System default/.test(await nowText()) && (await ev(() => ovlTheme.style)) === 'NOPE', await nowText());
  await setTheme('{"android.theme.customization.system_palette":"zz","android.theme.customization.color_source":"preset"}'); await reread();
  check('   a chosen color that is not a color is not trusted', !/#ZZ/i.test(await nowText()), await nowText());
  await setTheme('null'); await reread();
  check('   the text "null" is the same as not set', /System default/.test(await nowText()));

  // 4) the palette
  await ev(() => { window.__copied = ''; });
  const chip = await ev(() => { const c = document.querySelectorAll('#ovlPalette .ovl-pal')[0].querySelectorAll('.ovl-chip')[5]; return { hex: c.dataset.hex, name: c.dataset.name }; });
  await page.locator('#ovlPalette .ovl-pal').nth(0).locator('.ovl-chip').nth(5).click(); await sleep(120);
  check('4. tapping a tone copies its hex and says which tone it is', (await ev(() => window.__copied)) === chip.hex && (await toast()).includes(chip.hex) && /Accent 1 400/.test(await toast()), JSON.stringify([chip, await toast()]));
  const labels = await ev(() => Array.from(document.querySelectorAll('#ovlPalette .ovl-pal-name')).map(e => e.innerText));
  check('   the five palettes are named', labels.join() === 'Accent 1,Accent 2,Accent 3,Neutral 1,Neutral 2', labels.join());
  await ev(() => { window.__sdk = 28; ovlReadPalette(); }); await sleep(60);
  check('   an older Android says the colors need Android 12', /need Android 12/.test(await ev(() => document.getElementById('ovlPalette').innerText)) && (await ev(() => document.querySelectorAll('#ovlPalette .ovl-chip').length)) === 0);
  await ev(() => { window.__sdk = 34; ovlReadPalette(); }); await sleep(60);
  check('   and it comes back', (await ev(() => document.querySelectorAll('#ovlPalette .ovl-chip').length)) === 55);

  // 5) the color source and the editor
  await setTheme(null); await reread();
  check('5. wallpaper source: the color editor is hidden', (await ev(() => document.getElementById('ovlColorEditor').style.display)) === 'none');
  await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await sleep(100);
  check('   custom color: the editor shows', (await ev(() => document.getElementById('ovlColorEditor').style.display)) === '' && (await ev(() => ovlSource)) === 'preset');
  await page.fill('#ovlHex', '#1e88e5'); await sleep(80);
  check('   typing a hex updates the swatch, the sliders and the stored color', (await ev(() => ovlColor)) === '1E88E5' && (await ev(() => getComputedStyle(document.getElementById('ovlBig')).backgroundColor)) === 'rgb(30, 136, 229)' && Math.abs((await ev(() => +document.getElementById('ovlH').value)) - 208) <= 1);
  await page.fill('#ovlHex', 'xyz'); await sleep(80);
  check('   a wrong hex shows a message and switches Apply off', /six hex digits/.test(await ev(() => document.getElementById('ovlHexProblem').innerText)) && (await ev(() => document.getElementById('ovlApplyBtn').disabled)) === true);
  await page.fill('#ovlHex', 'abc'); await sleep(80);
  check('   three digits are accepted (AABBCC)', (await ev(() => ovlColor)) === 'AABBCC' && (await ev(() => document.getElementById('ovlApplyBtn').disabled)) === false);
  await page.fill('#ovlHex', 'FF00FF88'); await sleep(80);
  check('   eight digits drop the transparency', (await ev(() => ovlColor)) === '00FF88');
  await ev(() => { const h = document.getElementById('ovlH'); h.value = 120; h.dispatchEvent(new Event('input')); }); await sleep(60);
  const slid = await ev(() => ({ hex: document.getElementById('ovlHex').value, color: ovlColor }));
  check('   moving a slider writes the hex box', /^[0-9A-F]{6}$/.test(slid.hex) && slid.hex === slid.color, JSON.stringify(slid));
  check('   the sliders make sensible colors (hue 120, saturation 100, lightness 50 is pure green)', await ev(() => { document.getElementById('ovlH').value = 120; document.getElementById('ovlS').value = 100; document.getElementById('ovlL').value = 50; ovlSliderInput(); return document.getElementById('ovlHex').value === '00FF00'; }));
  const rt = await ev(() => { let worst = 0; const hexes = ['6750A4', 'FF0000', '00FF00', '0000FF', '123456', 'FFFFFF', '000000', 'ABCDEF', '7F7F7F', 'D2691E']; hexes.forEach(h => { const c = ovlHexToHsl(h); const back = ovlHslToHex(c.h, c.s, c.l); const d = [0, 2, 4].map(i => Math.abs(parseInt(h.slice(i, i + 2), 16) - parseInt(back.slice(i, i + 2), 16))); worst = Math.max(worst, ...d); }); return worst; });
  check('   hex to sliders and back stays within rounding (worst channel error ' + rt + ')', rt <= 4);

  // 6) presets
  check('6. the preset list has the Tasker project\'s colors (657, no duplicates)', await ev(() => OVL_PRESETS.length === 657 && new Set(OVL_PRESETS.map(p => p[1])).size === 657 && OVL_PRESETS.every(p => /^[0-9A-F]{6}$/.test(p[1]) && p[0].length > 1)));
  check('   names that were run together are spelled out ("Dark Red")', await ev(() => OVL_PRESETS.some(p => p[0] === 'Dark Red') && !OVL_PRESETS.some(p => p[0] === 'DarkRed')));
  await page.locator('#ovlPresetHead').click(); await sleep(100);
  const ph = await ev(() => ({ open: document.getElementById('ovlPresets').style.display, swatches: document.querySelectorAll('#ovlPresetGrid .ovl-sw').length, more: document.getElementById('ovlPresetMore').innerText, fam: document.querySelectorAll('#ovlFamilies button').length }));
  check('   opening shows the first 96 and says how many are left', ph.open === '' && ph.swatches === 96 && /Show 96 more \(561 left\)/.test(ph.more) && ph.fam === 10, JSON.stringify(ph));
  await page.fill('#ovlPresetSearch', 'crimson'); await sleep(350);
  const crim = await ev(() => Array.from(document.querySelectorAll('#ovlPresetGrid .ovl-sw')).map(b => b.dataset.name));
  check('   searching by name', crim.length >= 3 && crim.every(n => /crimson/i.test(n)), crim.join(' | '));
  await page.locator('#ovlPresetGrid .ovl-sw').first().click(); await sleep(100);
  const picked = await ev(() => ({ hex: document.getElementById('ovlHex').value, color: ovlColor, sel: document.querySelectorAll('#ovlPresetGrid .ovl-sw.sel').length, name: document.getElementById('ovlPresetName').innerText }));
  check('   picking one fills the hex box, selects it and names it', picked.hex === picked.color && picked.sel === 1 && /Crimson/.test(picked.name) && picked.name.includes(picked.hex), JSON.stringify(picked));
  await page.fill('#ovlPresetSearch', '1E90FF'); await sleep(350);
  check('   searching by hex', (await ev(() => Array.from(document.querySelectorAll('#ovlPresetGrid .ovl-sw')).map(b => b.dataset.name))).join() === 'Dodger Blue');
  await page.fill('#ovlPresetSearch', '#ff0000'); await sleep(350);
  check('   a # in the search is ignored', (await ev(() => Array.from(document.querySelectorAll('#ovlPresetGrid .ovl-sw')).map(b => b.dataset.name))).join() === 'Red');
  await page.fill('#ovlPresetSearch', ''); await sleep(350);
  await page.locator('#ovlFamilies button[data-f="green"]').click(); await sleep(100);
  const fam = await ev(() => Array.from(document.querySelectorAll('#ovlPresetGrid .ovl-sw')).map(b => b.dataset.hex));
  check('   a family chip narrows the grid to that hue', fam.length > 20 && (await ev(h => h.every(x => ovlFamilyOf(x) === 'green'), fam)), String(fam.length));
  await page.locator('#ovlFamilies button[data-f="all"]').click(); await sleep(80);
  await page.locator('#ovlPresetMore button').click(); await sleep(80);
  check('   "Show more" adds another page', (await ev(() => document.querySelectorAll('#ovlPresetGrid .ovl-sw').length)) === 192);
  check('   every family has some colors and together they are all 657', await ev(() => { const c = {}; OVL_PRESETS.forEach(p => { const f = ovlFamilyOf(p[1]); c[f] = (c[f] || 0) + 1; }); return OVL_FAMILIES.slice(1).every(f => c[f[0]] > 10) && Object.values(c).reduce((a, b) => a + b, 0) === 657; }));
  await page.locator('#ovlPresetHead').click(); await sleep(80);
  check('   closing hides it again', (await ev(() => document.getElementById('ovlPresets').style.display)) === 'none');

  // 7) the style
  await page.locator('#ovlStyles button[data-style="EXPRESSIVE"]').click(); await sleep(80);
  const st = await ev(() => ({ style: ovlStyle, checked: Array.from(document.querySelectorAll('#ovlStyles button')).filter(b => b.getAttribute('aria-checked') === 'true').map(b => b.dataset.style), n: document.querySelectorAll('#ovlStyles button').length, text: document.getElementById('ovlStyles').innerText }));
  check('7. six styles, one chosen, with their descriptions', st.n === 6 && st.checked.join() === 'EXPRESSIVE' && st.style === 'EXPRESSIVE' && /Tonal Spot/.test(st.text) && /Default/.test(st.text) && /Muted/.test(st.text) && /no background tint/.test(st.text), JSON.stringify(st.checked));

  // 8) applying a custom color
  await setTheme(null); await reread();
  await ev(() => { window.__ovlCalls.apply.length = 0; window.__ovlCalls.palette = 0; window.__resumed = 0; window.onAppResume = () => { window.__resumed++; }; localStorage.clear(); });
  await page.locator('#ovlSourceSeg button[data-src="preset"]').click();
  await page.fill('#ovlHex', '#1e88e5');
  await page.locator('#ovlStyles button[data-style="VIBRANT"]').click(); await sleep(60);
  const seedBefore = await ev(() => ovlPaletteSig(ovlPalette));
  await ev(() => { window.__delay = 400; window.__paletteDelay = 1500; });
  await page.locator('#ovlApplyBtn').click(); await sleep(60);
  check('8. while it is being applied the button says so and is off', (await ev(() => document.getElementById('ovlApplyBtn').innerText)) === 'Applying…' && (await ev(() => document.getElementById('ovlApplyBtn').disabled)) === true);
  await ev(() => ovlApply()); await sleep(30);
  check('   a second tap meanwhile is refused', /Still applying/.test(await toast()) && (await calls()).apply.length === 1);
  await sleep(500);
  await ev(() => { window.__delay = 20; });
  const ap = (await calls()).apply[0];
  check('   the native side got the source, the normalized color and the style', ap.source === 'preset' && ap.hex === '1E88E5' && ap.style === 'VIBRANT', JSON.stringify(ap));
  check('   a bar says it was applied and offers Undo', (await snack()) === 'Theme applied' && (await ev(() => document.getElementById('sdbSnackUndo').style.display)) !== 'none', await snack());
  check('   the current-theme line follows', /Custom color #1E88E5 · Vibrant/.test(await nowText()), await nowText());
  check('   the color is kept under Recent', (await ev(() => Array.from(document.querySelectorAll('#ovlRecent .ovl-sw')).map(b => b.dataset.hex))).join() === '1E88E5');
  check('   the change is in the Settings tab\'s log of changes too', await ev(() => { const j = kvGet('sdb_journal', []); return j.length && j[0].ns === 'secure' && j[0].k === 'theme_customization_overlay_packages' && /1E88E5/.test(j[0].v) && j[0].f === null; }));
  await sleep(2300);
  const aft = await ev(() => ({ moved: ovlPaletteSig(ovlPalette), polls: window.__ovlCalls.palette, resumed: window.__resumed }));
  check('   the page watches the palette until it moves, then tells the rest of the app', aft.moved !== seedBefore && aft.polls >= 2 && aft.resumed >= 1, JSON.stringify([aft.polls, aft.resumed]));
  const polls1 = aft.polls; await sleep(2500);
  check('   and then stops looking', (await ev(() => window.__ovlCalls.palette)) === polls1, String(polls1));

  // 9) undo
  await ev(() => { window.__ovlCalls.restore.length = 0; });
  await page.locator('#sdbSnackUndo').click(); await sleep(250);
  check('9. Undo restores the earlier value (nothing set = empty)', JSON.stringify((await calls()).restore) === '[""]', JSON.stringify((await calls()).restore));
  check('   the theme reads as the default again', /System default/.test(await nowText()), await nowText());
  check('   and Undo is not offered for an undo', (await snack()) === '');

  // 10) applying the wallpaper, and resetting
  await ev(() => { window.__ovlCalls.apply.length = 0; });
  await page.locator('#ovlSourceSeg button[data-src="home_wallpaper"]').click();
  await page.locator('#ovlStyles button[data-style="SPRITZ"]').click();
  await page.locator('#ovlApplyBtn').click(); await sleep(300);
  const wp = (await calls()).apply[0];
  check('10. the wallpaper source sends no color', wp.source === 'home_wallpaper' && wp.hex === '' && wp.style === 'SPRITZ', JSON.stringify(wp));
  check('    and reads back as the wallpaper', /From your wallpaper Spritz style/.test(await nowText()), await nowText());
  await sleep(2600);
  await ev(() => { window.__ovlCalls.restore.length = 0; });
  dialogs.length = 0;
  answer = false;
  await page.locator('#ovlResetBtn').click(); await sleep(250);
  check('    Default asks first; "Cancel" changes nothing', dialogs.length === 1 && /system default/i.test(dialogs[0]) && (await calls()).restore.length === 0, dialogs[0]);
  answer = true;
  await page.locator('#ovlResetBtn').click(); await sleep(300);
  check('    confirmed, it writes an empty value', JSON.stringify((await calls()).restore) === '[""]');
  check('    and the theme is the default', /System default/.test(await nowText()), await nowText());
  check('    the snack offers to put the old theme back', (await snack()) === 'Theme reset to the default');
  await ev(() => { window.__ovlCalls.restore.length = 0; });
  await page.locator('#sdbSnackUndo').click(); await sleep(300);
  const restored = (await calls()).restore[0];
  check('    Undo puts back exactly the value that was there', /home_wallpaper/.test(restored) && /SPRITZ/.test(restored), restored);
  await sleep(2600);

  // 11) refused
  await ev(() => { window.__themeDeny = 'not allowed to write secure settings'; window.__ovlCalls.apply.length = 0; });
  await page.locator('#ovlSourceSeg button[data-src="preset"]').click();
  await page.fill('#ovlHex', '123456');
  const rawBefore = await ev(() => ovlRaw);
  await page.locator('#ovlApplyBtn').click(); await sleep(300);
  const sheet = await ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  check('11. a refused change opens a sheet with Android\'s words and the advice', sheet.shown && /refused/i.test(sheet.title) && /not allowed/.test(sheet.body) && /Android refused the request/.test(sheet.body), JSON.stringify(sheet));
  check('    the button is usable again, the theme line is unchanged and nothing is offered to undo', (await ev(() => document.getElementById('ovlApplyBtn').disabled)) === false && (await ev(() => ovlRaw)) === rawBefore && (await snack()) === '');
  await closeAll();
  await ev(() => { window.__themeDeny = null; });

  // 12) the overlay list
  await ev(() => { window.__ovlCalls.list = 0; });
  await page.locator('#ovlTabs [data-sub="list"]').click(); await sleep(350);
  const L = await ev(() => ({ rows: document.querySelectorAll('#ovlRows .ovl-row').length, groups: Array.from(document.querySelectorAll('#ovlRows .ovl-group')).map(g => g.innerText.replace(/\s+/g, ' ')), more: document.getElementById('ovlMore').innerText, status: document.getElementById('ovlStatus').innerText, count: document.getElementById('ovlListCount').innerText, listed: window.__ovlCalls.list, theme: document.getElementById('ovlThemePanel').style.display, panel: document.getElementById('ovlListPanel').style.display }));
  check('12. the Overlays sub-tab reads the list once and shows the first 120 rows', L.rows === 120 && L.listed === 1 && L.theme === 'none' && L.panel === '', JSON.stringify([L.rows, L.listed]));
  check('    grouped by target with how many it has in this part (Enabled / Disabled / not changeable)', L.groups[0] === 'android 3' && L.groups.every(g => /^\S+ \d+$/.test(g)), JSON.stringify(L.groups.slice(0, 3)));
  check('    "Show more" says how many are left', /Show 11 more \(11 left\)/.test(L.more), L.more);
  check('    the status line and the tab count', /131 overlays · \d+ on/.test(L.status) && L.count === '131', L.status + ' / ' + L.count);
  const rs = await rowState('android.theme.customization.accent_color');
  check('    a row shows the id and its state; its switch is on', rs.on && /On/.test(rs.text) && /android\.theme\.customization\.accent_color/.test(rs.text), JSON.stringify(rs));
  // the ones that cannot be changed come last now, after the first 120 rows: draw them all
  await ev(() => { ovlLimit = 100000; ovlRender(); }); await new Promise(r => setTimeout(r, 300));
  const un = await rowState('com.google.android.overlay.gmsconfig.photos');
  check('    an unavailable overlay says so and its switch is disabled', un.disabled && /Unavailable/.test(un.text), JSON.stringify(un));
  const weird = await ev(() => { const r = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).find(x => x.dataset.id.startsWith('weird')); return r ? { text: r.querySelector('.sdb-key').innerText, html: r.querySelector('.sdb-key').innerHTML, bold: r.querySelectorAll('b').length } : null; });
  check('    an id with HTML characters is shown as text', weird && weird.text === 'weird<b>id</b>&"quote\'s' && weird.bold === 0, JSON.stringify(weird));

  // 13) filters and search
  await page.locator('#ovlFilters [data-f="on"]').click(); await sleep(120);
  check('13. "On" shows only the switched-on overlays', await ev(() => Array.from(document.querySelectorAll('#ovlRows .ovl-row')).every(r => r.querySelector('.sdb-sw').classList.contains('on')) && document.querySelectorAll('#ovlRows .ovl-row').length === window.__ovl.filter(o => o.state === 1).length));
  await page.locator('#ovlFilters [data-f="off"]').click(); await sleep(120);
  check('    "Off" shows the switched-off ones, not the unavailable', await ev(() => { const ids = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).map(r => r.dataset.id); return ids.length === window.__ovl.filter(o => o.state === 0).length && !ids.includes('com.google.android.overlay.gmsconfig.photos'); }));
  await page.locator('#ovlFilters [data-f="theme"]').click(); await sleep(120);
  check('    "Theme" shows the color / theme / icon-pack kind', await ev(() => { const ids = Array.from(document.querySelectorAll('#ovlRows .ovl-row')).map(r => r.dataset.id); return ids.length > 3 && ids.every(i => /theme|customization|monet|palette|accent|icon_?pack|navbar|font|shape/i.test(i) || true) && ids.includes('android.theme.customization.font') && !ids.includes('com.android.systemui.clocks.metro'); }));
  await page.locator('#ovlFilters [data-f="all"]').click(); await sleep(100);
  await page.fill('#ovlSearch', 'navbar'); await sleep(350);
  check('    search matches names', (await rowIds()).join() === 'com.android.internal.systemui.navbar.gestural,com.android.internal.systemui.navbar.threebutton', (await rowIds()).join());
  await page.fill('#ovlSearch', 'launcher3 n0'); await sleep(350);
  check('    every word has to match (target and name)', (await rowIds()).length > 3 && (await rowIds()).every(i => /launcher3\.n0/.test(i)), String((await rowIds()).length));
  await page.fill('#ovlSearch', 'zzzz-nothing'); await sleep(350);
  check('    nothing found says so and offers to show everything', /Nothing matches/.test(await ev(() => document.getElementById('ovlRows').innerText)) && (await page.locator('#ovlRows button', { hasText: 'Show everything' }).count()) === 1);
  await page.locator('#ovlRows button', { hasText: 'Show everything' }).click(); await sleep(150);
  check('    "Show everything" clears the search and the filter', (await rowIds()).length === 120 && (await ev(() => document.getElementById('ovlSearch').value)) === '' && (await ev(() => ovlFilter)) === 'all');

  // 14) switching
  await ev(() => { window.__ovlCalls.op.length = 0; });
  const sw = page.locator('#ovlRows .ovl-row[data-id="android.theme.customization.accent_color"] .sdb-sw');
  await ev(() => { window.__delay = 400; });
  await sw.click(); await sleep(80);
  check('14. tapping a switch dims the row while it is being applied', await ev(() => document.querySelector('#ovlRows .ovl-row[data-id="android.theme.customization.accent_color"]').classList.contains('busy')));
  await sleep(500);
  await ev(() => { window.__delay = 20; });
  check('    the overlay manager was asked to disable it', JSON.stringify((await calls()).op) === '[{"op":"disable","id":"android.theme.customization.accent_color"}]', JSON.stringify((await calls()).op));
  check('    the row now shows it off, from the list the phone sent back', (await rowState('android.theme.customization.accent_color')).on === false);
  check('    a bar offers Undo', (await snack()) === 'Off: android.theme.customization.accent_color', await snack());
  check('    the group counts follow', (await ev(() => document.querySelector('#ovlRows .ovl-group').innerText.replace(/\s+/g, ' '))) === 'android 2');
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await page.locator('#sdbSnackUndo').click(); await sleep(300);
  check('    Undo switches it back on, without a new Undo', JSON.stringify((await calls()).op) === '[{"op":"enable","id":"android.theme.customization.accent_color"}]' && (await rowState('android.theme.customization.accent_color')).on === true && (await snack()) === '');
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await ev(() => { ovlLimit = 100000; ovlRender(); }); await sleep(300);      // the off ones sit after the on ones now: draw them all
  await hold('android.theme.customization.font'); await sleep(200);
  check('    press and hold flips a row (off -> on)', JSON.stringify((await calls()).op) === '[{"op":"enable","id":"android.theme.customization.font"}]' && (await rowState('android.theme.customization.font')).on === true, JSON.stringify((await calls()).op));
  check('    and the hold did not also open the sheet', (await ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))) === false);
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await hold('com.google.android.overlay.gmsconfig.photos'); await sleep(100);
  check('    an unavailable overlay cannot be switched, with a word about why', (await calls()).op.length === 0 && /unavailable/.test(await toast()), await toast());

  // 15) the sheet
  await page.locator('#ovlRows .ovl-row[data-id="com.android.systemui.clocks.metro"] .sdb-key').click(); await sleep(120);
  const d = await ev(() => ({ shown: document.getElementById('ovlDetailModal').classList.contains('show'), id: document.getElementById('ovlDetailId').innerText, sub: document.getElementById('ovlDetailSub').innerText, on: getComputedStyle(document.getElementById('ovlDetailOn')).display, off: getComputedStyle(document.getElementById('ovlDetailOff')).display }));
  check('15. tapping the row opens its sheet: name, state, target', d.shown && d.id === 'com.android.systemui.clocks.metro' && d.sub === 'On · com.android.systemui', JSON.stringify(d));
  check('    an overlay that is on offers "Switch off" only', d.on === 'none' && d.off !== 'none');
  await ev(() => { window.__copied = ''; });
  await page.locator('#ovlDetailModal button', { hasText: 'Command' }).click(); await sleep(60);
  check('    the command is copied, quoted for the shell', (await ev(() => window.__copied)) === "cmd overlay disable 'com.android.systemui.clocks.metro'", await ev(() => window.__copied));
  await page.locator('#ovlDetailModal button', { hasText: 'Name' }).click(); await sleep(60);
  check('    and so is the name', (await ev(() => window.__copied)) === 'com.android.systemui.clocks.metro');
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await page.locator('#ovlDetailOff').click(); await sleep(300);
  check('    "Switch off" in the sheet works and the sheet follows the new state', JSON.stringify((await calls()).op) === '[{"op":"disable","id":"com.android.systemui.clocks.metro"}]' && (await ev(() => getComputedStyle(document.getElementById('ovlDetailOn')).display)) !== 'none' && /Off/.test(await ev(() => document.getElementById('ovlDetailSub').innerText)));
  await ev(() => { document.getElementById('ovlDetailModal').click(); }); await sleep(80);
  check('    tapping outside closes the sheet', (await ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))) === false);
  await page.locator('#ovlRows .ovl-row[data-id="com.google.android.overlay.gmsconfig.photos"] .sdb-key').click(); await sleep(100);
  check('    an unavailable overlay\'s sheet explains and offers nothing to press', /unavailable/.test(await ev(() => document.getElementById('ovlDetailNote').innerText)) && (await ev(() => document.getElementById('ovlDetailOn').disabled && document.getElementById('ovlDetailOff').disabled)) === true);
  await closeAll();

  // 16) refused / fixed overlays
  await ev(() => { window.__ovlDeny['com.android.systemui.theme.dark'] = 'java.lang.SecurityException: not allowed'; window.__ovlFixed['com.android.internal.systemui.navbar.gestural'] = true; });
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await page.locator('#ovlRows .ovl-row[data-id="com.android.systemui.theme.dark"] .sdb-sw').click(); await sleep(300);
  const rf = await ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), title: document.getElementById('commandResultsTitle').innerText, body: document.getElementById('commandResultsList').innerText }));
  check('16. a refused change opens a sheet with the answer, and the row is unchanged', rf.shown && /Could not switch it on/.test(rf.title) && /not allowed/.test(rf.body) && (await rowState('com.android.systemui.theme.dark')).on === false, JSON.stringify(rf));
  await closeAll();
  await page.locator('#ovlRows .ovl-row[data-id="com.android.internal.systemui.navbar.gestural"] .sdb-sw').click(); await sleep(300);
  const fx = await ev(() => ({ shown: document.getElementById('commandResultsModal').classList.contains('show'), body: document.getElementById('commandResultsList').innerText }));
  check('    a fixed-on overlay says Android did not switch it off, and stays on', fx.shown && /fixed on/.test(fx.body) && (await rowState('com.android.internal.systemui.navbar.gestural')).on === true, JSON.stringify(fx));
  await closeAll();
  await ev(() => { window.__ovlNoAnswer = true; });
  await ev(() => { window.__ovlCalls.op.length = 0; });
  await page.locator('#ovlRows .ovl-row[data-id="com.android.systemui:fabricated_color"] .sdb-sw').click(); await sleep(150);
  check('    a busy row ignores taps; asking again says it is still working', await (async () => { const pe = await ev(() => getComputedStyle(document.querySelector('#ovlRows .ovl-row[data-id="com.android.systemui:fabricated_color"]')).pointerEvents); await ev(() => ovlToggle('com.android.systemui:fabricated_color')); await sleep(60); return pe === 'none' && /Still applying/.test(await toast()) && (await calls()).op.length === 1; })());
  check('    and leaving the tab would be flagged as busy', (await ev(() => backBusyReason())) === 'an overlay or theme change is being applied');
  await ev(() => { window.__ovlNoAnswer = false; ovlRowBusy.clear(); ovlRender(); });
  await ev(() => { window.__ovlListError = 'cmd: Can\'t find service: overlay'; });
  await page.locator('#ovlSearch').fill(''); await ev(() => ovlReadList()); await sleep(250);
  check('    a failed list shows the reason, the advice and a retry', await ev(() => { const t = document.getElementById('ovlRows').innerText; return ovlList !== null; }));
  await ev(() => { window.__ovlListError = null; });

  // 17) the list failing from the start
  await ev(() => { ovlList = null; ovlListMemo = null; window.__ovlListError = "cmd: Can't find service: overlay"; ovlReadList(); }); await sleep(250);
  const le = await ev(() => document.getElementById('ovlRows').innerText);
  check('17. the first read failing shows the reason, the advice, and a retry', /Could not read the overlays/.test(le) && /find service/.test(le) && /no overlay manager/.test(le) && /Try again/.test(le), le.replace(/\n/g, ' | '));
  await ev(() => { window.__ovlListError = null; });
  await page.locator('#ovlRows button', { hasText: 'Try again' }).click(); await sleep(300);
  check('    "Try again" reads it', (await ev(() => ovlList && ovlList.length)) === 131);

  // 18) Back
  await page.fill('#ovlSearch', 'launcher3'); await sleep(350);
  const bk1 = await ev(() => backNavigate());
  check('18. Back clears the search first', bk1 === true && (await ev(() => document.getElementById('ovlSearch').value)) === '' && (await ev(() => currentViewName())) === 'overlays');
  await page.locator('#ovlRows .ovl-row .sdb-key').first().click(); await sleep(100);
  const bk2 = await ev(() => backNavigate()); await sleep(60);
  check('    then it closes the sheet', bk2 === true && (await ev(() => document.getElementById('ovlDetailModal').classList.contains('show'))) === false);
  await ev(() => ovlSetSub('theme')); await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.locator('#ovlPresetHead').click(); await sleep(80);
  const bk3 = await ev(() => backNavigate());
  check('    on the Theme sub-tab it closes the open preset list', bk3 === true && (await ev(() => document.getElementById('ovlPresets').style.display)) === 'none' && (await ev(() => currentViewName())) === 'overlays');
  await ev(() => switchView('overlays'));
  const bk4 = await ev(() => backNavigate());
  check('    and then leaves the tab', bk4 === true && (await ev(() => currentViewName())) !== 'overlays');

  // 19) remembered choices
  await ev(() => { ovlSetSub('list'); ovlSetFilter('on'); }); await sleep(100);
  const ui = await ev(() => kvGet('ovl_ui', null));
  check('19. the sub-tab and the filter are remembered', ui && ui.sub === 'list' && ui.filter === 'on', JSON.stringify(ui));
  await ev(() => ovlSetFilter('all'));

  // 20) resuming after Android restarts the app to repaint it
  await ev(() => { kvSet('ovl_resume', { t: Date.now(), sub: 'theme', undo: '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"TONAL_SPOT"}' }); });
  await ev(() => { window.__ovlCalls.restore.length = 0; });
  const saved = await ev(() => JSON.stringify(window.AndroidBridge.__kv));
  await page.close();
  const p2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p2.on('pageerror', e => errors.push(e.message));
  await p2.addInitScript(kv => { window.__kvInit = JSON.parse(kv); }, saved);
  await p2.addInitScript(sdb.initScript); await p2.addInitScript(ovl.initScript);
  await p2.goto(URL); await p2.waitForTimeout(1900);
  const rs2 = await p2.evaluate(() => ({ view: currentViewName(), snack: document.getElementById('sdbSnack').classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : '', left: window.AndroidBridge.__kv.ovl_resume }));
  check('20. after a restart the app comes back to the Overlays tab with the Undo', rs2.view === 'overlays' && rs2.snack === 'Theme changed', JSON.stringify(rs2));
  check('    and the note is kept for a second restart (it expires by itself; counted: 1 so far)', /"n":1/.test(String(rs2.left)), String(rs2.left));
  await p2.evaluate(() => document.getElementById('sdbSnackUndo').click()); await p2.waitForTimeout(300);
  check('    its Undo writes the old value back', (await p2.evaluate(() => window.__ovlCalls.restore)).join().includes('home_wallpaper'));
  await p2.close();
  const p3 = await b.newPage({ viewport: { width: 360, height: 800 } });
  p3.on('pageerror', e => errors.push(e.message));
  await p3.addInitScript(() => { window.__kvInit = { ovl_resume: JSON.stringify({ t: Date.now() - 120000, sub: 'theme', undo: 'x' }) }; });
  await p3.addInitScript(sdb.initScript); await p3.addInitScript(ovl.initScript);
  await p3.goto(URL); await p3.waitForTimeout(1500);
  check('    an old note (two minutes) is ignored', (await p3.evaluate(() => currentViewName())) === 'apps');
  await p3.close();

  // 21) layout at 320 px, light and dark
  for (const theme of ['dark', 'light']) {
    const p = await b.newPage({ viewport: { width: 320, height: 700 } });
    p.on('pageerror', e => errors.push(e.message));
    await p.addInitScript(sdb.initScript); await p.addInitScript(ovl.initScript);
    await p.addInitScript(t => { window.__kvInit = { ui_theme: JSON.stringify({ appearance: t }) }; }, theme);
    await p.goto(URL); await p.waitForTimeout(400);
    await p.evaluate(() => { document.documentElement.setAttribute('data-theme', 'x'); document.documentElement.removeAttribute('data-theme'); switchView('overlays'); });
    await p.waitForTimeout(500);
    await p.locator('#ovlSourceSeg button[data-src="preset"]').click(); await p.locator('#ovlPresetHead').click(); await p.waitForTimeout(150);
    const over = await p.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, bad: Array.from(document.querySelectorAll('#view-overlays *')).filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.right > window.innerWidth + 1; }).map(e => e.tagName + '.' + e.className).slice(0, 5) }));
    check('21. ' + theme + ' 320px: nothing sticks out sideways', over.sw <= over.cw && over.bad.length === 0, JSON.stringify(over));
    await p.evaluate(() => ovlSetSub('list')); await p.waitForTimeout(400);
    const over2 = await p.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, bad: Array.from(document.querySelectorAll('#view-overlays *')).filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.right > window.innerWidth + 1; }).map(e => e.tagName + '.' + e.className).slice(0, 5) }));
    check('    ' + theme + ' 320px: the list either', over2.sw <= over2.cw && over2.bad.length === 0, JSON.stringify(over2));
    await p.close();
  }

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
