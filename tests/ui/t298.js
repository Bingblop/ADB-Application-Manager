// Translations: a name inside a sentence stays a name. A template's changing part that is itself a word of the dictionary is translated ("Showing apps
// that are Frozen." -> "... Congelada."), which is right for the page's own words (filters, modes, streams) and wrong for names: in Spanish the
// confirmation for deleting the folder Download read "¿Eliminar Descargar?", a folder that does not exist. The templates whose changing part is the
// name of the app, file or item an action is about are now listed with I18N.names(...) in the page, and their names stay as they are. Uses the real
// Spanish dictionary (assets/lang/es.js).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.dismiss().catch(() => {}); });
  await page.addInitScript(() => {
    window.__kv = { lang: '"es"', perm_intro_v62: '1' };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadSetting(k) { return window.__kv[k] || ''; }, saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  check('the page runs in Spanish', await ev(() => I18N.code === 'es' && I18N.active));
  const t = s => ev(x => I18N.t(x), s);

  // names stay names
  const names = [
    ['Delete Download?', '¿Eliminar Download?'],
    ['Delete Music after it was extracted?', '¿Eliminar Music después de extraerlo?'],
    ['Uninstall Settings?', '¿Desinstalar Settings?'],
    ['Launching Settings...', 'Iniciando Settings...'],
    ['Delete 1 items (Download)? Folders go with everything in them. This can not be undone.', '¿Eliminar 1 elementos (Download)? Las carpetas se eliminan con todo su contenido. Esta acción no se puede deshacer.'],
  ];
  for (const [en, es] of names) { const got = await t(en); check('a name in "' + en + '" stays as it is', got === es, JSON.stringify(got)); }

  // the page's own words in other templates are still translated
  const words = [
    ['Showing apps that are Frozen.', 'Mostrando apps: Congelada.'],
    ["Following your phone's setting (currently Dark)", 'Siguiendo el ajuste del teléfono (actualmente Oscuro)'],
    ['Music volume (3 of 15)', 'Volumen de Música (3 of 15)'],
  ];
  for (const [en, es] of words) { const got = await t(en); check('a word of the page in "' + en + '" is still translated', got === es, JSON.stringify(got)); }

  // the same through the dialogs of the browser and through text the page writes into an element
  await ev(() => { confirm('Delete Camera after it was extracted?'); });
  await page.waitForTimeout(100);
  check('a confirm keeps the name', dialogs.slice(-1)[0] === '¿Eliminar Camera después de extraerlo?', JSON.stringify(dialogs));
  await ev(() => { const d = document.createElement('div'); d.id = 'i18nProbe'; document.body.appendChild(d); d.innerText = 'Delete Download?'; });
  await page.waitForTimeout(150);
  check('text written into the page keeps the name', (await ev(() => document.getElementById('i18nProbe').innerText)) === '¿Eliminar Download?', await ev(() => document.getElementById('i18nProbe').innerText));
  await ev(() => { document.getElementById('i18nProbe').innerText = 'Showing apps that are Frozen.'; });
  await page.waitForTimeout(150);
  check('and still translates a word of the page there', (await ev(() => document.getElementById('i18nProbe').innerText)) === 'Mostrando apps: Congelada.', await ev(() => document.getElementById('i18nProbe').innerText));

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
