// Overlays tab, review round: what the independent reviews found (journal overwrite, the restart record, editor state, unknown styles,
// Android 11, focus and keyboard, "constructor" targets, a dead Back, a stale list, an empty list after a change, the switch warning).
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const KEY = 'theme_customization_overlay_packages';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  // a fresh page: kv = what an earlier run of the app left behind; post = a script that runs after the mocks
  const mk = async (opts = {}) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 }, hasTouch: true });
    page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
    page.on('dialog', d => d.accept());
    if (opts.kv) await page.addInitScript(kv => { window.__kvInit = JSON.parse(kv); }, JSON.stringify(opts.kv));
    await page.addInitScript(sdb.initScript); await page.addInitScript(ovl.initScript);
    if (opts.post) await page.addInitScript(opts.post);
    await page.goto(URL); await page.waitForTimeout(opts.wait || 450);
    return page;
  };
  const tools = page => ({
    ev: (fn, arg) => page.evaluate(fn, arg),
    sleep: ms => page.waitForTimeout(ms),
    toast: () => page.locator('#toastMsg').innerText(),
    kv: k => page.evaluate(key => { const v = window.AndroidBridge.__kv[key]; return v === undefined ? undefined : v; }, k),
    closeAll: () => page.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); }),
    open: async (sub) => { await page.evaluate(sub => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); if (sub) ovlSetSub(sub); }, sub || null); await page.waitForTimeout(700); }
  });

  // 1) the Settings tab's saved change history survives a theme change made before Settings was ever opened
  {
    const journal = [1, 2, 3].map(i => ({ n: i, t: Date.now() - i * 1000, ns: 'global', k: 'key' + i, f: '0', v: '1' }));
    const page = await mk({ kv: { sdb_journal: JSON.stringify(journal) } }); const T = tools(page);
    await T.open('theme');
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047'); await T.sleep(100);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    const j = JSON.parse(await T.kv('sdb_journal'));
    check('1. a theme change made before Settings was opened keeps the 3 earlier journal entries (and adds its own)', j.length === 4 && j[0].k === KEY && j.slice(1).map(e => e.k).join() === 'key1,key2,key3', JSON.stringify(j.map(e => e.k)));
    await page.close();
  }

  // 2) the note of a change in flight: kept after the answer, finished after a restart, counted, expiring
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047');
    await page.locator('#ovlStyles button[data-style="VIBRANT"]').click(); await T.sleep(100);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    let rec = JSON.parse(await T.kv('ovl_resume') || 'null');
    check('2. after the answer the note is still there, marked finished, with the value to Undo to', !!rec && rec.done === true && rec.kind === 'apply' && rec.undo === 'null', JSON.stringify(rec));
    const saved = JSON.parse(await page.evaluate(() => JSON.stringify(window.AndroidBridge.__kv)));
    const phone = await page.evaluate(() => window.__db.secure['theme_customization_overlay_packages']);
    await page.close();
    // the app restarts after the answer
    const p2 = await mk({ kv: saved, wait: 1900, post: () => { /* a fresh mock */ } }); const T2 = tools(p2);
    const s2 = await p2.evaluate(() => ({ view: currentViewName(), snack: document.getElementById('sdbSnack').classList.contains('show') ? document.getElementById('sdbSnackMsg').innerText : '' }));
    check('   a restart after the answer comes back to Overlays with the Undo bar', s2.view === 'overlays' && s2.snack === 'Theme changed', JSON.stringify(s2));
    const saved2 = JSON.parse(await p2.evaluate(() => JSON.stringify(window.AndroidBridge.__kv)));
    check('   the note is counted (1) and kept for a second restart', JSON.parse(saved2.ovl_resume).n === 1);
    await p2.close();
    const p3 = await mk({ kv: saved2, wait: 1900 });
    const s3 = await p3.evaluate(() => ({ view: currentViewName(), n: JSON.parse(window.AndroidBridge.__kv.ovl_resume).n }));
    check('   a second restart still comes back (counted: 2)', s3.view === 'overlays' && s3.n === 2, JSON.stringify(s3));
    const saved3 = JSON.parse(await p3.evaluate(() => JSON.stringify(window.AndroidBridge.__kv)));
    await p3.close();
    const p4 = await mk({ kv: saved3, wait: 1900 });
    const s4 = await p4.evaluate(() => ({ view: currentViewName(), left: window.AndroidBridge.__kv.ovl_resume }));
    check('   a third one does not (the note is dropped, the app starts normally)', s4.view !== 'overlays' && (s4.left === 'null' || s4.left === undefined), JSON.stringify(s4));
    await p4.close();
    // an old note, and one stamped in the future, are ignored and dropped
    for (const [label, t] of [['an old note', Date.now() - 60000], ['a note from the future', Date.now() + 3600000]]) {
      const p5 = await mk({ kv: { ovl_resume: JSON.stringify({ t, sub: 'theme', undo: 'x', done: true, n: 0 }) }, wait: 1500 });
      const s5 = await p5.evaluate(() => ({ view: currentViewName(), left: window.AndroidBridge.__kv.ovl_resume }));
      check('   ' + label + ' is ignored', s5.view !== 'overlays' && (s5.left === 'null' || s5.left === undefined), JSON.stringify(s5));
      await p5.close();
    }
    // the restart came BEFORE the answer: the phone has the theme, the page never heard. It looks again and does what the answer would have done.
    const applied = '{"android.theme.customization.system_palette":"43A047","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT","_applied_timestamp":1}';
    const pending = { t: Date.now(), sub: 'theme', undo: 'null', done: false, n: 0, kind: 'apply', source: 'preset', hex: '43A047', style: 'VIBRANT', from: 'null' };
    const p6 = await mk({ kv: { ovl_resume: JSON.stringify(pending) }, post: () => { window.__db.secure['theme_customization_overlay_packages'] = '{"android.theme.customization.system_palette":"43A047","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT","_applied_timestamp":1}'; }, wait: 2400 });
    const s6 = await p6.evaluate(() => ({ view: currentViewName(), recent: JSON.parse(window.AndroidBridge.__kv.ovl_recent || '[]'), journal: JSON.parse(window.AndroidBridge.__kv.sdb_journal || '[]').map(e => e.k + ':' + e.f + '>' + (e.v || '').slice(0, 30)), done: JSON.parse(window.AndroidBridge.__kv.ovl_resume).done }));
    check('   a restart before the answer: the colour is added to the recent ones', s6.view === 'overlays' && s6.recent[0] === '43A047', JSON.stringify(s6.recent));
    check('   ... the change is logged in Settings (old value null)', s6.journal.length === 1 && /^theme_customization_overlay_packages:null>/.test(s6.journal[0]), JSON.stringify(s6.journal));
    check('   ... and the note is marked finished, so it is not done twice', s6.done === true);
    await p6.close();
    // ... but if the phone does not hold what was applied, nothing is claimed
    const p7 = await mk({ kv: { ovl_resume: JSON.stringify(pending) }, wait: 2400 });
    const s7 = await p7.evaluate(() => ({ recent: window.AndroidBridge.__kv.ovl_recent, journal: window.AndroidBridge.__kv.sdb_journal, done: JSON.parse(window.AndroidBridge.__kv.ovl_resume).done }));
    check('   a restart where the phone does not hold the theme: no recent colour, no log entry', !s7.recent && !s7.journal && s7.done === false, JSON.stringify(s7));
    await p7.close();
  }

  // 3) Undo leaves a note too (it repaints and restarts apps as well); a refused change forgets; an unsure one keeps it
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047'); await T.sleep(100);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    await page.locator('#sdbSnackUndo').click(); await T.sleep(100);
    let rec = JSON.parse(await T.kv('ovl_resume') || 'null');
    check('3. Undo writes a note (no second Undo in it)', !!rec && rec.kind === 'undo' && rec.undo === null, JSON.stringify(rec));
    await T.sleep(1500);
    rec = JSON.parse(await T.kv('ovl_resume') || 'null');
    check('   and keeps it once it is answered', !!rec && rec.kind === 'undo' && rec.done === true, JSON.stringify(rec));
    const flags = await T.ev(() => window.__ovlCalls.restoreUndo);
    check('   the bridge was told it is an Undo (the switch goes back too)', JSON.stringify(flags) === '[true]', JSON.stringify(flags));
    await T.ev(() => { window.__themeDeny = 'not allowed'; });
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    check('   a refused change leaves no note', (await T.kv('ovl_resume')) === 'null' || (await T.kv('ovl_resume')) === undefined, String(await T.kv('ovl_resume')));
    await T.closeAll();
    await T.ev(() => { window.__themeDeny = null; window.__themeUnknown = { apply: false }; });
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    rec = JSON.parse(await T.kv('ovl_resume') || 'null');
    check('   an unanswered one keeps the note (the restart may still come)', !!rec && rec.kind === 'apply' && rec.done === false, JSON.stringify(rec));
    await T.closeAll();
    // Default tells the bridge it is not an Undo
    await T.ev(() => { window.__ovlCalls.restoreUndo.length = 0; ovlReset(); }); await T.sleep(1500);
    check('   Default is not an Undo', JSON.stringify(await T.ev(() => window.__ovlCalls.restoreUndo)) === '[false]');
    await page.close();
  }

  // 4) a style the page does not list is shown as it is, and never called "already what the phone uses"
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await T.ev(() => { window.__db.secure['theme_customization_overlay_packages'] = '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"MONOCHROMATIC"}'; ovlReadTheme(); }); await T.sleep(400);
    const now = await T.ev(() => document.getElementById('ovlNow').innerText.replace(/\s+/g, ' '));
    check('4. the read-out says "Monochromatic", not Tonal Spot', /From your wallpaper Monochromatic style/.test(now), now);
    check('   Apply does not claim it is already the same', !/already what the phone uses/.test(await T.ev(() => document.getElementById('ovlApplyHint').innerText)), await T.ev(() => document.getElementById('ovlApplyHint').innerText));
    check('   the editor does not start on the unknown style (one of the six stays chosen)', await T.ev(() => OVL_STYLE_IDS.includes(ovlStyle)));
    await T.ev(() => { window.__db.secure['theme_customization_overlay_packages'] = '{"android.theme.customization.color_source":"home_wallpaper","android.theme.customization.theme_style":"X<img src=x onerror=window.__pwned=1>"}'; ovlReadTheme(); }); await T.sleep(400);
    check('   a style with markup in it is not taken for a style at all', (await T.ev(() => window.__pwned)) === undefined && (await T.ev(() => ovlTheme.style)) === '', await T.ev(() => document.getElementById('ovlNow').innerText.replace(/\s+/g, ' ')));
    await page.close();
  }

  // 5) the editor is the user's: a late first read does not override a choice, a reload does not eat what is being typed, Undo exists for an early Apply
  {
    const page = await mk({ wait: 300, post: () => { window.__delay = 1200; window.__db.secure['theme_customization_overlay_packages'] = '{"android.theme.customization.system_palette":"D32F2F","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"SPRITZ","_applied_timestamp":1}'; } }); const T = tools(page);
    await T.ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); ovlSetSub('theme'); }); await T.sleep(250);
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.locator('#ovlStyles button[data-style="VIBRANT"]').click(); await page.fill('#ovlHex', '#43A047'); await T.sleep(60);
    await T.sleep(1800);
    const ed = await T.ev(() => ({ src: ovlSource, color: ovlColor, style: ovlStyle, hex: document.getElementById('ovlHex').value, now: ovlTheme && ovlTheme.color }));
    check('5. the first read (1.2 s late) does not replace what was chosen meanwhile', ed.src === 'preset' && ed.color === '43A047' && ed.style === 'VIBRANT' && ed.now === 'D32F2F', JSON.stringify(ed));
    await T.ev(() => { window.__delay = 20; });
    // typing in the hex box while the screen is redrawn
    await page.focus('#ovlHex');
    await page.fill('#ovlHex', '12A'); await T.sleep(50);
    await page.evaluate(() => { const h = document.getElementById('ovlHex'); h.value = '12AB'; h.dispatchEvent(new Event('input', { bubbles: true })); });
    await T.ev(() => { ovlRender(); }); await T.sleep(100);
    check('   a redraw while the hex box has focus leaves what is typed alone', (await T.ev(() => document.getElementById('ovlHex').value)) === '12AB', await T.ev(() => document.getElementById('ovlHex').value));
    await page.evaluate(() => document.getElementById('ovlHex').blur());
    await T.ev(() => { ovlRender(); }); await T.sleep(100);
    check('   once it is left, the box shows the colour that is used', (await T.ev(() => document.getElementById('ovlHex').value)) === '1122AA', await T.ev(() => document.getElementById('ovlHex').value));
    // an Apply before the phone was ever read still has an Undo (the phone says what it held)
    await T.ev(() => { ovlRaw = null; ovlTheme = null; ovlPickColor('0288D1'); }); await T.sleep(60);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(1500);
    const snack = await T.ev(() => ({ shown: document.getElementById('sdbSnack').classList.contains('show'), msg: document.getElementById('sdbSnackMsg').innerText }));
    check('   an Apply made before the first read offers Undo, using what the phone reports it held', snack.shown && /Theme applied/.test(snack.msg), JSON.stringify(snack));
    await T.ev(() => { window.__ovlCalls.restore.length = 0; }); await page.locator('#sdbSnackUndo').click(); await T.sleep(1500);
    check('   ... and that Undo writes the old value back', (await T.ev(() => window.__ovlCalls.restore)).join('|').includes('D32F2F'), JSON.stringify(await T.ev(() => window.__ovlCalls.restore)));
    await page.close();
  }

  // 6) a warning from the phone is shown; a theme answer that is "ok" still says what did not work
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await T.ev(() => { window.__themeWarning = 'Applied, but the wallpaper-colors switch (global) would not change, so the phone\'s own palette may still win.'; });
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047'); await T.sleep(60);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(700);
    const snack = await T.ev(() => document.getElementById('sdbSnackMsg').innerText);
    check('6. the Undo bar says a switch would not change', /Theme applied, but a switch would not change/.test(snack), snack);
    check('   and a toast gives the phone\'s own words', /wallpaper-colors switch \(global\)/.test(await T.toast()), await T.toast());
    await page.close();
  }

  // 6b) a refused change whose rollback of the switch did not take says so in the failure sheet
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await T.ev(() => { window.__themeDeny = 'not allowed'; window.__themeWarning = 'The wallpaper-colors switch (global) could not be put back to what it was.'; });
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.fill('#ovlHex', '#43A047'); await T.sleep(60);
    await page.locator('#ovlApplyBtn').click(); await T.sleep(900);
    const sheet = await T.ev(() => document.getElementById('commandResultsModal').classList.contains('show') ? document.getElementById('commandResultsList').innerText : '');
    check('6b. the failure sheet carries the warning about the switch', /Theme change refused/.test(sheet) && /could not be put back/.test(sheet), sheet.replace(/\s+/g, ' '));
    await page.close();
  }

  // 7) keyboard: focus survives redraws; the clear button is reachable; sliders show where the focus is
  {
    const page = await mk({ kv: { ovl_recent: JSON.stringify(['43A047', '0288D1']) } }); const T = tools(page);
    await T.open('theme');
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await T.sleep(60);
    await page.locator('#ovlPresetHead').click(); await T.sleep(100);
    await page.evaluate(() => document.querySelector('#ovlPresetGrid .ovl-sw').focus());
    const first = await T.ev(() => document.activeElement.dataset.hex);
    await page.keyboard.press('Enter'); await T.sleep(120);
    check('7. a preset colour chosen from the keyboard keeps the focus on it', (await T.ev(() => document.activeElement.dataset.hex)) === first && (await T.ev(() => ovlColor)) === first, String(await T.ev(() => document.activeElement.dataset.hex)));
    await page.evaluate(() => document.querySelectorAll('#ovlRecent .ovl-sw')[1].focus());
    const rhex = await T.ev(() => document.activeElement.dataset.hex);
    await page.keyboard.press('Enter'); await T.sleep(120);
    check('   a recent colour chosen from the keyboard keeps the focus', (await T.ev(() => document.activeElement.dataset.hex)) === rhex && (await T.ev(() => ovlColor)) === rhex, String(await T.ev(() => document.activeElement.dataset.hex)));
    await page.evaluate(() => document.querySelector('#ovlPresetMore button').focus());
    await page.keyboard.press('Enter'); await T.sleep(150);
    const inMore = await T.ev(() => { const a = document.activeElement; return !!(a && a.closest && (a.closest('#ovlPresetMore') || a.closest('#ovlPresetGrid'))); });
    check('   "Show more" in the presets keeps the focus in the presets', inMore, await T.ev(() => document.activeElement.tagName + '.' + document.activeElement.className));
    for (let i = 0; i < 12; i++) { const has = await T.ev(() => !!document.querySelector('#ovlPresetMore button')); if (!has) break; await page.evaluate(() => document.querySelector('#ovlPresetMore button').focus()); await page.keyboard.press('Enter'); await T.sleep(60); }
    check('   when there is no more to show the focus moves onto the last colour, not into the page', await T.ev(() => { const a = document.activeElement; return !!(a && a.closest && a.closest('#ovlPresetGrid')); }), await T.ev(() => document.activeElement.tagName));
    check('   the three sliders have a visible focus ring', await T.ev(() => Array.from(document.styleSheets).some(sh => { try { return Array.from(sh.cssRules).some(r => /ovl-slider input\[type="?range"?\]:focus-visible/.test(r.selectorText || '')); } catch (e) { return false; } })));
    await T.ev(() => ovlSetSub('list')); await T.sleep(500);
    await T.ev(() => { document.getElementById('ovlSearch').value = 'overlay'; ovlSearchInput(); }); await T.sleep(400);
    check('   the clear button can be tabbed to and used with Enter', (await T.ev(() => document.getElementById('ovlSearchClear').getAttribute('tabindex'))) === '0');
    await page.evaluate(() => document.getElementById('ovlSearchClear').focus()); await page.keyboard.press('Enter'); await T.sleep(400);
    check('   ... Enter clears the search', (await T.ev(() => document.getElementById('ovlSearch').value)) === '' && (await T.ev(() => ovlQuery)) === '');
    // the list's own "Show more"
    await page.evaluate(() => document.querySelector('#ovlMore button').focus()); await page.keyboard.press('Enter'); await T.sleep(150);
    check('   "Show more" in the list keeps the focus in the list', await T.ev(() => { const a = document.activeElement; return !!(a && a.closest && (a.closest('#ovlMore') || a.closest('#ovlRows'))); }), await T.ev(() => document.activeElement.tagName));
    await page.close();
  }

  // 8) a target named like an Object method counts like any other
  {
    const page = await mk({ post: () => { window.__ovl.push({ target: 'constructor', state: 1, id: 'x.one' }, { target: 'constructor', state: 0, id: 'x.two' }, { target: '__proto__', state: 1, id: 'y.one' }, { target: 'toString', state: 0, id: 'z.one' }, { target: 'hasOwnProperty', state: 1, id: 'w.one' }); } }); const T = tools(page);
    await T.open('list');
    await T.ev(() => { ovlLimit = 100000; ovlRender(); }); await T.sleep(300);
    const groups = await T.ev(() => Array.from(document.querySelectorAll('#ovlRows .ovl-group')).filter(g => /constructor|__proto__|toString|hasOwnProperty/.test(g.innerText)).map(g => g.innerText.replace(/\s+/g, ' ')));
    check('8. targets "constructor", "__proto__", "toString" and "hasOwnProperty" show real counts', groups.length >= 4 && groups.every(g => !/NaN|undefined/.test(g)) && groups.some(g => /constructor 1$/.test(g)) && groups.some(g => /__proto__ 1$/.test(g)), JSON.stringify(groups));
    check('   and nothing was written onto Object.prototype', (await T.ev(() => Object.prototype.all === undefined && Object.prototype.on === undefined)));
    await page.close();
  }

  // 9) Back after choosing the wallpaper does not "close" a preset list nobody can see
  {
    const page = await mk(); const T = tools(page);
    await T.open('theme');
    await page.locator('#ovlSourceSeg button[data-src="preset"]').click(); await page.locator('#ovlPresetHead').click(); await T.sleep(100);
    check('9. the preset list is open', await T.ev(() => ovlPresetOpen === true));
    await page.locator('#ovlSourceSeg button[data-src="home_wallpaper"]').click(); await T.sleep(100);
    check('   choosing the wallpaper closes it (it is hidden with the editor)', await T.ev(() => ovlPresetOpen === false));
    check('   so Back is not used up on it', (await T.ev(() => ovlBackHandled())) === false);
    await page.close();
  }

  // 10) the list sub-tab re-reads a list that is old
  {
    const page = await mk(); const T = tools(page);
    await T.open('list');
    await T.ev(() => { ovlSetSub('theme'); window.__ovlCalls.list = 0; ovlListAt = Date.now() - 61000; ovlSetSub('list'); }); await T.sleep(500);
    check('10. opening the list a minute after it was read reads it again', (await T.ev(() => window.__ovlCalls.list)) === 1, String(await T.ev(() => window.__ovlCalls.list)));
    await T.ev(() => { ovlSetSub('theme'); window.__ovlCalls.list = 0; ovlSetSub('list'); }); await T.sleep(400);
    check('    a fresh one is not read again', (await T.ev(() => window.__ovlCalls.list)) === 0);
    await page.close();
  }

  // 11) Android 11 and older: the Theme half is off, and the tab opens on the list
  {
    const page = await mk({ post: () => { window.__sdk = 30; } }); const T = tools(page);
    await T.ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); }); await T.sleep(800);
    check('11. on Android 11 a first visit opens on the Overlays list', await T.ev(() => ovlSub === 'list' && document.getElementById('ovlListPanel').style.display !== 'none'));
    await T.ev(() => ovlSetSub('theme')); await T.sleep(300);
    check('    Apply and Default are off', await T.ev(() => document.getElementById('ovlApplyBtn').disabled && document.getElementById('ovlResetBtn').disabled));
    check('    and it says why', /Android 12 or newer/.test(await T.ev(() => document.getElementById('ovlApplyHint').innerText)), await T.ev(() => document.getElementById('ovlApplyHint').innerText));
    await T.ev(() => { window.__ovlCalls.apply.length = 0; ovlApply(); }); await T.sleep(300);
    check('    asking anyway changes nothing and says so', (await T.ev(() => window.__ovlCalls.apply.length)) === 0 && /Android 12/.test(await T.toast()), await T.toast());
    await page.close();
    const p2 = await mk({ post: () => { window.__sdk = 34; } }); const T2 = tools(p2);
    await T2.ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); }); await T2.sleep(800);
    check('    on Android 14 it opens on Theme, as before', await T2.ev(() => ovlSub === 'theme' && !document.getElementById('ovlApplyBtn').disabled));
    await p2.close();
    const p3 = await mk({ kv: { ovl_ui: JSON.stringify({ sub: 'theme', filter: 'all' }) }, post: () => { window.__sdk = 30; } }); const T3 = tools(p3);
    await T3.ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); }); await T3.sleep(800);
    check('    a saved choice of the Theme half is kept even there', await T3.ev(() => ovlSub === 'theme'));
    await p3.close();
  }

  // 12) a change answered with an empty list does not blank the page's list; a vanished overlay is looked at again
  {
    const page = await mk(); const T = tools(page);
    await T.open('list');
    const n0 = await T.ev(() => ovlList.length);
    await T.ev(() => { window.__ovlEmptyAnswer = true; window.__ovlCalls.list = 0; ovlToggle('com.android.internal.systemui.navbar.threebutton'); }); await T.sleep(500);
    check('12. the list the page has is kept (' + n0 + ' overlays)', (await T.ev(() => ovlList.length)) === n0 && !/lists no overlays/.test(await T.ev(() => document.getElementById('ovlRows').innerText)));
    await T.closeAll();
    await T.ev(() => { window.__ovlEmptyAnswer = false; });
    await T.sleep(3000);
    check('    and the page reads the list again (the overlay may be gone)', (await T.ev(() => window.__ovlCalls.list)) >= 1, String(await T.ev(() => window.__ovlCalls.list)));
    await page.close();
  }

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
