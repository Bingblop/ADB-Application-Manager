// v7.12.5: a first launch starts in the language of the phone when the app has it (English otherwise), and the first-launch permission sheet has a Language drop-down.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  async function open(langs, store) {
    const ctx = await b.newContext({ viewport: { width: 400, height: 860 } });
    const page = await ctx.newPage();
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(([l, st]) => {
      Object.defineProperty(navigator, 'languages', { get: () => l }); Object.defineProperty(navigator, 'language', { get: () => l[0] });
      const store = window.__store = Object.assign({}, st);
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        loadSetting(k) { return store[k] || ''; }, saveSetting(k, v) { store[k] = v; },
        getPermissionStatus() { return JSON.stringify({ files: false, usage: false, overlay: false, install_unknown: false }); },
      };
    }, [langs, store || {}]);
    await page.goto(PAGE); await page.waitForTimeout(500);
    return { page, ev: fn => page.evaluate(fn) };
  }
  const state = ev => ev(() => ({ code: I18N.code, saved: window.__store.lang || '', title: document.title, sel: document.getElementById('langSelect').value }));

  let t = await open(['de-DE', 'en-US']);
  let s = await state(t.ev);
  check('1. a first launch on a German phone starts in German and keeps it', s.code === 'de' && s.saved === '"de"' && s.sel === 'de', JSON.stringify(s));
  await t.page.close();

  t = await open(['pt-PT']);
  s = await state(t.ev);
  check('2. Portuguese of Portugal gets the Brazilian dictionary', s.code === 'pt-BR', JSON.stringify(s));
  await t.page.close();
  t = await open(['zh-TW']); s = await state(t.ev);
  check('   zh-TW -> zh-CN', s.code === 'zh-CN', JSON.stringify(s)); await t.page.close();
  t = await open(['in-ID']); s = await state(t.ev);
  check('   the old Android code in -> Indonesian', s.code === 'id', JSON.stringify(s)); await t.page.close();

  t = await open(['sv-SE', 'fr-FR']); s = await state(t.ev);
  check('3. the first language of the phone that the app has is used (Swedish is not there, French is)', s.code === 'fr', JSON.stringify(s)); await t.page.close();
  t = await open(['sv-SE']); s = await state(t.ev);
  check('4. a language the app does not have: English, and nothing is saved', s.code === 'en' && s.saved === '', JSON.stringify(s)); await t.page.close();
  t = await open(['en-GB', 'de-DE']); s = await state(t.ev);
  check('   English first on the phone: English', s.code === 'en', JSON.stringify(s)); await t.page.close();

  t = await open(['de-DE'], { lang: '"en"' }); s = await state(t.ev);
  check('5. a language chosen earlier (English) is not replaced by the phone\'s', s.code === 'en', JSON.stringify(s)); await t.page.close();
  t = await open(['de-DE'], { perm_intro_v62: '1' }); s = await state(t.ev);
  check('6. an app that is in use already (first-launch sheet seen) stays in English', s.code === 'en' && s.saved === '', JSON.stringify(s)); await t.page.close();

  // the sheet
  t = await open(['de-DE']);
  await t.ev(() => openPermSheet('intro')); await t.page.waitForTimeout(300);
  let sh = await t.ev(() => ({ row: getComputedStyle(document.getElementById('permLangRow')).display, val: document.getElementById('permLangSelect').value, n: document.getElementById('permLangSelect').options.length, first: document.getElementById('permLangSelect').options[0].text }));
  check('7. the first-launch sheet has a Language drop-down with every language, set to the phone\'s (German here)', sh.row !== 'none' && sh.val === 'de' && sh.n === 14 && sh.first === 'English', JSON.stringify(sh));
  await t.ev(() => { const s = document.getElementById('permLangSelect'); s.value = 'en'; s.dispatchEvent(new Event('change')); }); await t.page.waitForTimeout(500);
  s = await state(t.ev);
  check('8. choosing English there switches the app at once and saves it (Settings shows it too)', s.code === 'en' && s.saved === '"en"' && s.sel === 'en', JSON.stringify(s));
  await t.ev(() => closePermSheet()); await t.ev(() => openPermSheet('manual')); await t.page.waitForTimeout(200);
  check('9. the sheet opened by hand (About) has no Language drop-down', await t.ev(() => getComputedStyle(document.getElementById('permLangRow')).display === 'none'));
  await t.page.close();
  await b.close();
  console.log(bad ? bad + ' failing' : 'all ok');
  process.exit(bad ? 1 : 0);
})();
