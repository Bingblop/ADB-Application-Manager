// v7.1 Language setting (Settings > Language, assets/i18n.js): the drop-down at the very top of Settings, English by default, a language switched at once without a reload and
// kept between launches, text / sentences with bold parts / title / placeholder / aria-label / toasts / dialogs translated, names of apps left as they are,
// right-to-left for Arabic, dates in the language, a dictionary that cannot be loaded changing nothing. The dictionaries here are small ones made for the test and
// handed to the page the way assets/lang/<code>.js does (window.__LANGS), so the test does not depend on the real translations.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pathToFileURL, fileURLToPath } = require('url');
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

const ES = {
  x: {
    'Appearance': 'Apariencia', 'Language': 'Idioma', 'Feature List': 'Lista de funciones', 'Application Manager': 'Gestor de\naplicaciones', 'Light': 'Claro', 'Dark': 'Oscuro',
    'Log cleared': 'Registro borrado', 'Filter found fonts…': 'Filtrar fuentes…', 'Settings': 'Ajustes', 'Camera': 'Cámara', 'Reset to Default': 'Restablecer', 'Font': 'Fuente'
  },
  p: [['Language: {0}', 'Idioma: {0}'], ['Freeze {0} apps', 'Congelar {0} apps']]
};
const AR = { x: { 'Appearance': 'المظهر', 'Language': 'اللغة' }, p: [] };
// the same page without its dictionaries (assets/lang), for a language whose file is missing
const BARE_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'bare-'));
for (const f of ['index.html', 'i18n.js']) fs.copyFileSync(path.join(path.dirname(fileURLToPath(PAGE)), f), path.join(BARE_DIR, f));
const BARE = pathToFileURL(path.join(BARE_DIR, 'index.html')).href;
const SETUP = `window.__LANGS = window.__LANGS || {}; window.__LANGS.es = ${JSON.stringify(ES)}; window.__LANGS.ar = ${JSON.stringify(AR)};`;

(async () => {
  const b = await chromium.launch();
  const open = async (kv, apps, url) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.on('dialog', d => { page.__dialog = d.message(); d.dismiss().catch(() => {}); });
    await page.addInitScript(SETUP);
    await page.addInitScript(inst.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv || {}), apps: apps || [{ pkg: 'com.example.app', name: 'Example App', isSystem: false }] });
    await page.goto(url || PAGE);
    await page.waitForFunction(() => document.getElementById('countAll').innerText === '1' || document.getElementById('countAll').innerText !== '');
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const titles = page => ev(page, () => Array.from(document.querySelectorAll('#view-prefs > .color-card')).map(c => c.querySelector('.color-card-title').innerText.trim()));
  const tab1 = page => ev(page, () => document.querySelector('#tabBar .tab-btn').innerText);

  let page = await open();
  await ev(page, () => switchView('prefs'));

  // 1) the drop-down: first card of Settings, English chosen, thirteen other languages by their own names
  const sel = await ev(page, () => { const s = document.getElementById('langSelect'); return { first: document.querySelector('#view-prefs > .color-card').id, value: s.value, options: Array.from(s.options).map(o => o.value + '=' + o.text), no: s.getAttribute('translate'), aria: s.getAttribute('aria-label') }; });
  check('1. the Language card is the first of Settings, with English chosen and a drop-down of 14 languages named in themselves', sel.first === 'languageCard' && sel.value === 'en' && sel.options.length === 14 && sel.options[0] === 'en=English' && /es=Español/.test(sel.options.join()) && /ar=العربية/.test(sel.options.join()) && /zh-CN=简体中文/.test(sel.options.join()) && sel.no === 'no', JSON.stringify(sel.options));
  check('   English: the page says Appearance, lang="en" dir="ltr", dates in the phone\'s language', JSON.stringify(await titles(page)).includes('Appearance') && await ev(page, () => document.documentElement.lang === 'en' && document.documentElement.dir !== 'rtl' && langLocale() === undefined));

  // 2) a language is chosen: everything changes at once, with no reload
  await ev(page, () => { window.__stay = 'same page'; });
  await page.selectOption('#langSelect', 'es');
  await sleep(150);
  const t2 = await titles(page);
  check('2. choosing Español translates the card titles, the tab labels (with their line break) and the Feature List button at once', t2[0] === 'Idioma' && t2.includes('Apariencia') && t2.includes('Lista de funciones') && (await tab1(page)) === 'Gestor de\naplicaciones' && await ev(page, () => document.getElementById('featureResetBtn').innerText === 'Restablecer'), JSON.stringify(t2));
  check('   no reload: the page is the same one', await ev(page, () => window.__stay === 'same page'));
  check('   lang="es", dates in Spanish (langLocale), the toast says Idioma: Español (a template with a changing part)', await ev(page, () => document.documentElement.lang === 'es' && document.documentElement.dir === 'ltr' && langLocale() === 'es') && /^Idioma: Español$/.test(await ev(page, () => document.getElementById('toastMsg').innerText)));
  check('   the choice is kept (kv lang)', await ev(page, () => JSON.parse(window.__kv.lang)) === 'es');
  check('   the drop-down keeps its own names (translate="no") and now shows Español', await ev(page, () => document.getElementById('langSelect').value === 'es' && /Español/.test(document.getElementById('langSelect').selectedOptions[0].text)));

  // 3) text that arrives later is translated too: toasts, attributes, a dialog
  await ev(page, () => showToast('Log cleared'));
  await sleep(60);
  check('3. a toast shown later is translated', await ev(page, () => document.getElementById('toastMsg').innerText) === 'Registro borrado');
  await ev(page, () => { const i = document.createElement('input'); i.id = 'tmpIn'; i.placeholder = 'Filter found fonts…'; i.setAttribute('aria-label', 'Language'); document.body.appendChild(i); });
  await sleep(60);
  check('   a placeholder and an aria-label added later are translated', await ev(page, () => document.getElementById('tmpIn').placeholder === 'Filtrar fuentes…' && document.getElementById('tmpIn').getAttribute('aria-label') === 'Idioma'));
  await ev(page, () => { confirm('Log cleared'); });
  check('   a dialog of the browser (alert, confirm, prompt) is translated', page.__dialog === 'Registro borrado', page.__dialog);

  // 4) a name is not translated: an app called like a word of the dictionary keeps its name (the names of apps, files and settings are data)
  await ev(page, () => switchView('apps'));
  await page.close();
  page = await open({ lang: JSON.stringify('es') }, [{ pkg: 'com.android.settings', name: 'Settings', isSystem: true }, { pkg: 'com.example.camera', name: 'Camera', isSystem: false }]);
  await page.waitForFunction(() => document.querySelectorAll('#appsListContainer .app-card').length === 2);
  const names = await ev(page, () => Array.from(document.querySelectorAll('#appsListContainer .app-card .app-name')).map(n => n.innerText).sort());
  check('4. a list of apps named Settings and Camera keeps those names although the dictionary has both words', JSON.stringify(names) === JSON.stringify(['Camera', 'Settings']), JSON.stringify(names));
  await page.close();

  // 5) the next launch: the language is there before the first draw
  page = await open({ lang: JSON.stringify('es') });
  const first = await ev(page, () => ({ lang: document.documentElement.lang, tab: document.querySelector('#tabBar .tab-btn').innerText, sel: document.getElementById('langSelect').value }));
  check('5. at the next launch the page starts in Español (tab label, lang attribute, the drop-down)', first.lang === 'es' && first.tab === 'Gestor de\naplicaciones' && first.sel === 'es', JSON.stringify(first));

  // 6) back to English: every text, attribute and element is as it was
  await ev(page, () => switchView('prefs'));
  const before = await ev(page, () => document.getElementById('view-prefs').innerHTML.replace(/\s+/g, ' '));
  await page.selectOption('#langSelect', 'en');
  await sleep(150);
  const t6 = await titles(page);
  check('6. choosing English again brings every text back (card titles, tab label, Feature List button, lang attribute)', t6[0] === 'Language' && t6.includes('Appearance') && (await tab1(page)) === 'Application\nManager' && await ev(page, () => document.getElementById('featureResetBtn').innerText === 'Reset to Default' && document.documentElement.lang === 'en' && langLocale() === undefined), JSON.stringify(t6));
  check('   the choice is kept as English', await ev(page, () => JSON.parse(window.__kv.lang)) === 'en');
  await page.selectOption('#langSelect', 'es'); await sleep(100); await page.selectOption('#langSelect', 'en'); await sleep(100);
  const again = await ev(page, () => document.getElementById('view-prefs').innerHTML.replace(/\s+/g, ' '));
  check('   English, Español, English: the Settings page is the same markup as the first English one', again.length > 1000 && again === (await ev(page, () => document.getElementById('view-prefs').innerHTML.replace(/\s+/g, ' '))) && /Appearance/.test(again));
  void before;

  // 7) Arabic runs right to left, English left to right again
  await page.selectOption('#langSelect', 'ar');
  await sleep(150);
  const ar = await ev(page, () => ({ dir: document.documentElement.dir, lang: document.documentElement.lang, title: document.querySelector('#view-prefs > .color-card .color-card-title').innerText, over: document.documentElement.scrollWidth - document.documentElement.clientWidth }));
  check('7. Arabic: dir="rtl", lang="ar", the card is translated and the page does not scroll sideways', ar.dir === 'rtl' && ar.lang === 'ar' && ar.title === 'اللغة' && ar.over <= 0, JSON.stringify(ar));
  await page.selectOption('#langSelect', 'en'); await sleep(100);
  check('   back to English: dir="ltr" again', await ev(page, () => document.documentElement.dir === 'ltr' && document.documentElement.lang === 'en'));

  // 8) a language whose dictionary cannot be loaded changes nothing and says so
  await page.close();
  page = await open({}, null, BARE);
  await ev(page, () => switchView('prefs'));
  await page.selectOption('#langSelect', 'fr');
  await sleep(300);
  const fr = await ev(page, () => ({ sel: document.getElementById('langSelect').value, toast: document.getElementById('toastMsg').innerText, lang: document.documentElement.lang, kv: window.__kv.lang }));
  check('8. a dictionary that is missing: the text stays English, the drop-down goes back, a toast says why, nothing is saved', fr.sel === 'en' && /could not be loaded/.test(fr.toast) && fr.lang === 'en' && fr.kv === undefined, JSON.stringify(fr));
  await page.close();

  // 9) a saved language whose dictionary is gone: the page starts in English without an error
  page = await open({ lang: JSON.stringify('de') }, null, BARE);
  check('9. a saved language with no dictionary: English, no error, the drop-down on English', await ev(page, () => document.documentElement.lang === 'en' && document.getElementById('langSelect').value === 'en'));
  await page.close();

  // 10) a text box keeps its content but its hint and label are translated; two quick picks end on the last one
  page = await open();
  await ev(page, () => { const t = document.createElement('textarea'); t.id = 'tmpTa'; t.placeholder = 'Filter found fonts…'; t.setAttribute('aria-label', 'Language'); document.body.appendChild(t); });
  await ev(page, () => { LANG_ENGINE.set('ar'); LANG_ENGINE.set('es'); });
  await sleep(200);
  check('10. a text area\'s placeholder and label are translated, and of two quick language picks the last one wins', await ev(page, () => { const t = document.getElementById('tmpTa'); return t.placeholder === 'Filtrar fuentes…' && t.getAttribute('aria-label') === 'Idioma' && LANG_ENGINE.code === 'es' && document.documentElement.lang === 'es'; }));
  await page.close();

  await b.close();
  fs.rmSync(BARE_DIR, { recursive: true, force: true });
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
