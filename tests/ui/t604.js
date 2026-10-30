// v7.18.1 (the owner's requests), kept through v7.22.0's one Theme screen: the RRO/Monet Customization tab's "Back up and restore" card (the last
// card of the Theme panel, before the sticky action bar, as Saved lists' backup: Back up now writes monet-theme-<stamp>.json to Download/ADB App
// Manager, Share…, Restore from a file…); a backup (format 2) holds the colour, the style, the spec, the contrast, the three switches, the sliders
// and the accents; a restore asks first, fills the tab in, changes nothing on the phone (no theme write, no overlay call) and says to tap Apply
// theme; a format 1 backup (v7.18's rootStyle and short slider names) still restores; a file that is not a Monet backup, or not JSON, is refused
// with nothing changed, and odd values in a backup fall back to the tab's own. Root Features lists "Monet theme — ColorBlendr palettes" (locked
// without Super User; with it, Open tab goes to the Monet tab's Theme half). The App Updater's "Check for updates on mobile data" and the App
// Stores' "Update stores on mobile data" switches are off by default and kept (kv updates_mobile_data / store_mobile_data), each with its own key.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => {
    window.__saved = []; window.__picked = [];
    const br = window.AndroidBridge = window.AndroidBridge || {};
    br.saveTextToDownloads = (name, text) => { window.__saved.push({ name, text }); return 'Download/ADB App Manager/' + name; };
    br.pickTextFile = tag => { window.__picked.push(tag); };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const calls = () => ev(() => ({ apply: window.__ovlCalls.apply.length, fab: window.__ovlCalls.fab.length }));

  // 1) the card
  await ev(() => switchView('overlays')); await sleep(300);
  await ev(() => { if (ovlSub !== 'theme') ovlSetSub('theme'); }); await sleep(150);
  const card = await ev(() => {
    const c = document.getElementById('ovlBackupCard'), cards = Array.from(document.querySelectorAll('#ovlThemePanel > .color-card'));
    const kids = Array.from(document.getElementById('ovlThemePanel').children);
    return { last: cards[cards.length - 1] === c, afterHow: cards.indexOf(c) === cards.indexOf(document.getElementById('ovlHowCard').closest('.color-card')) + 1, beforeBar: kids[kids.length - 1].id === 'ovlBar' && kids[kids.length - 2] === c,
      title: c.querySelector('.color-card-title').innerText, buttons: Array.from(c.querySelectorAll('button')).map(x => x.innerText.trim()), where: document.getElementById('ovlBkWhere').innerText,
      wide: c.getBoundingClientRect().right <= innerWidth };
  });
  check('1. the Theme panel\'s last card is "Back up and restore", after How this works and before the action bar', card.last && card.afterHow && card.beforeBar && card.title === 'Back up and restore', JSON.stringify(card));
  check('   its buttons are Back up now, Share…, Restore from a file…, and it says where backups go', card.buttons.join('|') === 'Back up now|Share…|Restore from a file…' && /Download\/ADB App Manager/.test(card.where), JSON.stringify(card));
  check('   it fits a 360 px screen', card.wide);

  // 2) Back up now: the theme as it is
  await ev(() => { ovlInit(); ovlColor = '3366CC'; ovlSource = 'preset'; ovlStyle = 'VIBRANT'; kvSet('ovl_black', true); kvSet('ovl_tint_text', false); kvSet('ovl_spec', '2025'); kvSet('ovl_contrast', 0.5); kvSet('ovl_adj_bg_light', 140); kvSet('ovl_accent_tertiary', 'AA5500'); });
  await page.locator('#ovlBackupCard button', { hasText: 'Back up now' }).click(); await sleep(100);
  const saved = await ev(() => window.__saved[window.__saved.length - 1]);
  const doc = saved ? JSON.parse(saved.text) : {};
  check('2. Back up now writes monet-theme-<stamp>.json to Download', !!saved && /^monet-theme-.+\.json$/.test(saved.name), saved && saved.name);
  check('   it holds the app, the kind, format 2 and the theme: colour, style, the switches, the spec, the contrast, the sliders, the accents',
    doc.app === 'adb-app-manager' && doc.kind === 'monet-theme' && doc.format === 2 && doc.theme.color === '3366CC' && doc.theme.source === 'preset' && doc.theme.style === 'VIBRANT'
    && doc.theme.black === true && doc.theme.accurate === true && doc.theme.tintText === false && doc.theme.spec === '2025' && doc.theme.contrast === 0.5
    && doc.theme.adjust.backgroundLightness === 140 && doc.theme.adjust.accentSaturation === 100 && doc.theme.accents.tertiary === 'AA5500' && doc.theme.accents.secondary === '', JSON.stringify(doc.theme));
  check('   the toast says where', /^Backed up the theme to Download\/ADB App Manager\/monet-theme-/.test(await toast()), await toast());

  // 3) Restore from a file…: asks the app for a file with the monet tag
  await page.locator('#ovlBackupCard button', { hasText: 'Restore from a file…' }).click(); await sleep(60);
  check('3. Restore from a file… asks the app for a file (tag monet-file)', (await ev(() => window.__picked.join(','))) === 'monet-file');

  // 4) a restore: asked first, fills the tab, nothing written to the phone
  await ev(() => { ovlColor = '112233'; ovlSource = 'home_wallpaper'; ovlStyle = 'TONAL_SPOT'; kvSet('ovl_black', false); kvSet('ovl_tint_text', true); kvSet('ovl_spec', '2021'); kvSet('ovl_contrast', 0); kvSet('ovl_adj_bg_light', 100); kvSet('ovl_accent_tertiary', ''); });
  const c0 = await calls();
  answer = false; dialogs.length = 0;
  await ev(t => window.onTextFilePicked({ tag: 'monet-file', name: 'mine.json', text: t }), saved.text); await sleep(100);
  const declined = await ev(() => ovlColor === '112233' && kvGet('ovl_black', null) === false);
  check('4. a restore asks first; No leaves the tab as it was', dialogs.length === 1 && /^Restore the theme from mine\.json\?/.test(dialogs[0]) && declined, JSON.stringify(dialogs));
  answer = true; dialogs.length = 0;
  await ev(t => window.onTextFilePicked({ tag: 'monet-file', name: 'mine.json', text: t }), saved.text); await sleep(150);
  const back = await ev(() => ({ color: ovlColor, source: ovlSource, style: ovlStyle, black: kvGet('ovl_black', null), tint: kvGet('ovl_tint_text', null), spec: kvGet('ovl_spec', null), contrast: kvGet('ovl_contrast', null), light: kvGet('ovl_adj_bg_light', null), acc: kvGet('ovl_accent_tertiary', null) }));
  check('   Yes brings back the colour, the style, the switches, the spec, the contrast, the slider and the accent', back.color === '3366CC' && back.source === 'preset' && back.style === 'VIBRANT' && back.black === true && back.tint === false && back.spec === '2025' && back.contrast === 0.5 && back.light === 140 && back.acc === 'AA5500', JSON.stringify(back));
  check('   nothing is written to the phone (no theme write, no overlay)', JSON.stringify(await calls()) === JSON.stringify(c0), JSON.stringify([c0, await calls()]));
  check('   the toast says to tap Apply theme', (await toast()) === 'Theme restored from mine.json. Tap Apply theme to use it.', await toast());

  // 5) refused files, odd values
  dialogs.length = 0;
  await ev(() => window.onTextFilePicked({ tag: 'monet-file', name: 'x.json', text: '{"app":"adb-app-manager","kind":"saved-lists","format":1,"lists":[]}' })); await sleep(60);
  const t1 = await toast();
  await ev(() => window.onTextFilePicked({ tag: 'monet-file', name: 'x.txt', text: 'hello' })); await sleep(60);
  const t2 = await toast();
  check('5. a Saved lists backup and a text file are refused, nothing asked, nothing changed', dialogs.length === 0 && t1 === 'That is not a Monet theme backup from this app.' && /not JSON/.test(t2) && (await ev(() => ovlColor)) === '3366CC', JSON.stringify([t1, t2]));
  await ev(() => window.onTextFilePicked({ tag: 'monet-file', name: 'odd.json', text: JSON.stringify({ app: 'adb-app-manager', kind: 'monet-theme', format: 1,
    theme: { source: 'preset', color: 'zz', style: '<b>', black: 'yes', adjust: { bgLight: 999, bgSat: 'x' }, accents: { secondary: 'red', tertiary: '00ff00' }, spec: 'nope', rootStyle: 'CMF' } }) })); await sleep(120);
  const odd = await ev(() => ({ color: ovlColor, style: ovlStyle, black: kvGet('ovl_black', null), light: kvGet('ovl_adj_bg_light', null), sat: kvGet('ovl_adj_bg_sat', null), a2: kvGet('ovl_accent_secondary', null), a3: kvGet('ovl_accent_tertiary', null), spec: kvGet('ovl_spec', null), contrast: kvGet('ovl_contrast', null) }));
  check('   odd values fall back: the colour and style stay (CMF needs the 2026 spec), the slider is held to 200, a bad accent is none, the 2021 spec, Standard contrast', odd.color === '3366CC' && odd.style === 'VIBRANT' && odd.black === false && odd.light === 200 && odd.sat === 100 && odd.a2 === '' && odd.a3 === '00FF00' && odd.spec === '2021' && odd.contrast === 0, JSON.stringify(odd));
  // a format 1 backup (v7.18): rootStyle and the short slider names are read
  await ev(() => window.onTextFilePicked({ tag: 'monet-file', name: 'old.json', text: JSON.stringify({ app: 'adb-app-manager', kind: 'monet-theme', format: 1,
    theme: { source: 'preset', color: '00AA88', style: 'EXPRESSIVE', rootStyle: 'MONOCHROMATIC', black: true, accurate: '0', adjust: { accentSat: 60, bgSat: 120, bgLight: 80 }, accents: { secondary: '123456' } } }) })); await sleep(120);
  const old = await ev(() => ({ color: ovlColor, style: ovlStyle, black: kvGet('ovl_black', null), accurate: kvGet('ovl_accurate_shades', null), acc: kvGet('ovl_adj_accent_sat', null), sat: kvGet('ovl_adj_bg_sat', null), light: kvGet('ovl_adj_bg_light', null), a2: kvGet('ovl_accent_secondary', null), a3: kvGet('ovl_accent_tertiary', null) }));
  check('   a format 1 backup restores: its rootStyle wins over its style, the short slider names are read, accurate stays on unless false', old.color === '00AA88' && old.style === 'MONOCHROMATIC' && old.black === true && old.accurate === '1' && old.acc === 60 && old.sat === 120 && old.light === 80 && old.a2 === '123456' && old.a3 === '', JSON.stringify(old));
  await ev(() => window.onTextFilePicked({ tag: 'monet-file', name: 'new.json', text: '{"app":"adb-app-manager","kind":"monet-theme","format":3,"theme":{}}' })); await sleep(60);
  check('   a format from a newer app is refused and says so', /newer version of the app \(format 3\)/.test(await toast()) && (await ev(() => ovlColor)) === '00AA88', await toast());

  // 6) Root Features: Monet theme — ColorBlendr palettes
  await ev(() => switchView('rootfeatures')); await sleep(250);
  const locked = await ev(() => { const r = Array.from(document.querySelectorAll('.rf-row')).find(x => /Monet theme — ColorBlendr palettes/.test(x.innerText)); return r ? { locked: r.classList.contains('is-locked'), btn: !!document.getElementById('rfMonetBtn'), sub: r.querySelector('.rf-name span').innerText } : null; });
  check('6. Root Features lists Monet theme — ColorBlendr palettes, locked without Super User, with its line', !!locked && locked.locked && !locked.btn && locked.sub === 'Color spec, contrast, intensity, accents, Pure black, Tint text color. Written with Super User.', JSON.stringify(locked));
  await ev(() => { window.__root = true; checkAllWorkingModes(false); }); await sleep(200);
  await ev(() => switchView('rootfeatures')); await sleep(250);
  const btn = page.locator('#rfMonetBtn');
  check('   with Super User it has an Open tab button', (await btn.count()) === 1 && (await btn.innerText()).trim() === 'Open tab');
  await ev(() => switchView('overlays')); await ev(() => ovlSetSub('list')); await ev(() => switchView('rootfeatures')); await sleep(200);
  await btn.click(); await sleep(400);
  check('   Open tab goes to the Monet tab, on its Theme half', (await ev(() => currentViewName() === 'overlays' && ovlSub === 'theme')), await ev(() => currentViewName() + ' ' + ovlSub));

  // 7) the two mobile data switches
  const sw = () => ev(() => ({ store: document.getElementById('storeMobileToggle').checked, upd: document.getElementById('updMobileToggle').checked, ks: kvGet('store_mobile_data', null), ku: kvGet('updates_mobile_data', null),
    names: ['#storeMobileCard', '#updMobileCard'].map(q => document.querySelector(q + ' .switch-title').textContent.trim()) }));
  const s0 = await sw();
  check('7. both mobile data switches are off by default', !s0.store && !s0.upd && s0.ks === null && s0.ku === null, JSON.stringify(s0));
  check('   they are "Update stores on mobile data" and "Check for updates on mobile data"', s0.names[0] === 'Update stores on mobile data' && s0.names[1] === 'Check for updates on mobile data', JSON.stringify(s0.names));
  await ev(() => setMobileDataOk('updates_mobile_data', true)); await sleep(60);
  const s1 = await sw();
  check('   the updater one turns on alone, kept as updates_mobile_data true', s1.upd && !s1.store && s1.ku === true && s1.ks === null && (await toast()) === 'Updates are now checked on mobile data too', JSON.stringify(s1));
  await ev(() => setMobileDataOk('store_mobile_data', true)); await sleep(60);
  const s2 = await sw();
  check('   the store one too, kept as store_mobile_data true', s2.store && s2.ks === true && (await toast()) === 'Stores now update on mobile data too', JSON.stringify(s2));
  await ev(() => setMobileDataOk('updates_mobile_data', false)); await sleep(60);
  const s3 = await sw();
  check('   turning the updater one off leaves the store one on', !s3.upd && s3.ku === false && s3.store && (await toast()) === 'Updates are checked only on Wi-Fi', JSON.stringify(s3));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
