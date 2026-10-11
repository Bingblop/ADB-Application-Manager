// v7.1 Font setting (Settings > Font): the system font, or a .ttf / .otf of the user's for the text of this app only. A search of storage with a progress bar and a
// list, the file chooser, a sheet that shows the font before it is used, the font kept between launches, a font that cannot be loaded giving way to the system font.
// The fixture font (fixtures/Fixture-Boxes-*.ttf) draws every lowercase letter as a box 0.6 em wide, so text in it is easy to tell from any other.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
const fonts = require('./lib/font_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

const B64 = fs.readFileSync(fixture('Fixture-Boxes-Regular.ttf')).toString('base64');
const BOLD64 = fs.readFileSync(fixture('Fixture-Boxes-Bold.ttf')).toString('base64');
const meta = (family, style, name, size, variable) => ({ family, style, name, kind: 'ttf', variable: !!variable, size });
const FOUND = [
  { path: '/storage/emulated/0/Download/Fixture-Boxes-Regular.ttf', name: 'Fixture-Boxes-Regular.ttf', kind: 'ttf', family: 'Fixture Boxes', style: 'Regular', variable: false, size: 2796, mtime: 0 },
  { path: '/storage/emulated/0/Download/Fixture-Boxes-Bold.ttf', name: 'Fixture-Boxes-Bold.ttf', kind: 'ttf', family: 'Fixture Boxes', style: 'Bold', variable: false, size: 2788, mtime: 0 },
  { path: '/storage/emulated/0/Fonts/Inter.ttf', name: 'Inter.ttf', kind: 'ttf', family: 'Inter', style: 'Regular', variable: true, size: 876544, mtime: 0 }
];
const FILES = {
  '/storage/emulated/0/Download/Fixture-Boxes-Regular.ttf': { meta: meta('Fixture Boxes', 'Regular', 'Fixture-Boxes-Regular.ttf', 2796), b64: B64 },
  '/storage/emulated/0/Download/Fixture-Boxes-Bold.ttf': { meta: meta('Fixture Boxes', 'Bold', 'Fixture-Boxes-Bold.ttf', 2788), b64: BOLD64 },
  '/storage/emulated/0/Fonts/Inter.ttf': { error: 'That file is not a TrueType or OpenType font.' },
  'content://font/1': { meta: meta('Fixture Boxes', 'Regular', 'chosen.ttf', 2796), b64: B64 }
};

(async () => {
  const b = await chromium.launch();
  const open = async (o) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, (o && o.kv) || {}), perm: (o && o.perm) || undefined, apps: [{ pkg: 'com.example.app', name: 'Example App', isSystem: false }] });
    await page.addInitScript(fonts.initScript, Object.assign({ fonts: FOUND, fontFiles: FILES }, o || {}));
    await page.goto(PAGE);
    await page.waitForFunction(() => document.getElementById('countAll').innerText === '1');
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  // the width of "iiii" set in the font the page uses now (0.6 em each in the fixture font; a narrow letter in any other)
  const iiii = page => ev(page, () => { const s = document.createElement('span'); s.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font-size:20px'; s.textContent = 'iiii'; document.body.appendChild(s); const w = Math.round(s.getBoundingClientRect().width); s.remove(); return w; });
  const toast = page => ev(page, () => document.getElementById('toastMsg').innerText);
  const calls = page => ev(page, () => window.__font.calls.slice());

  let page = await open();
  await ev(page, () => switchView('prefs'));

  // 1) the card: after the colors, before the Feature List; the system font is in use
  const order = await ev(page, () => Array.from(document.querySelectorAll('#view-prefs > .color-card')).map(c => c.querySelector('.color-card-title').innerText.trim()));
  check('1. the Font card sits after the colors, then the Icon pack card, the Action Button card, then the Feature List', order.indexOf('Font') > 1 && order.indexOf('Icon pack') === order.indexOf('Font') + 1 && order.indexOf('Action Button') === order.indexOf('Icon pack') + 1 && order.indexOf('Action Button') === order.indexOf('Feature List') - 1, JSON.stringify(order));
  const sysW = await iiii(page);
  check('   the system font is in use (a narrow letter is narrow) and there is no button to go back to it', sysW < 40 && /system font/i.test(await ev(page, () => document.getElementById('fontCurrent').innerText)) && await ev(page, () => getComputedStyle(document.getElementById('fontSystemBtn')).display === 'none'), sysW);

  // 2) the search: a bar with a message while it runs, then the list
  await page.close();
  page = await open({ holdScan: true });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn');
  await sleep(120);
  const run = await ev(page, () => ({ bar: getComputedStyle(document.getElementById('fontScanBar')).display !== 'none', now: document.getElementById('fontScanBar').getAttribute('aria-valuenow'), msg: document.getElementById('fontScanStatus').innerText, btn: document.getElementById('fontScanBtn').disabled }));
  check('2. while it runs: a progress bar, what it has searched, and the button is off', run.bar && +run.now >= 2 && /Searched Download/.test(run.msg) && run.btn, JSON.stringify(run));
  await ev(page, () => window.__font.finishScan());
  await sleep(60);
  const done = await ev(page, () => ({ bar: getComputedStyle(document.getElementById('fontScanBar')).display, rows: Array.from(document.querySelectorAll('#fontScanList .font-row')).map(r => r.innerText.replace(/\s+/g, ' ')), note: document.getElementById('fontScanStatus').innerText, filter: getComputedStyle(document.getElementById('fontScanSearch')).display, btn: document.getElementById('fontScanBtn').disabled }));
  check('   at the end: the bar is gone, three fonts are listed with their file and size, and the note says so', done.bar === 'none' && done.rows.length === 3 && /Fixture-Boxes-Regular\.ttf · 3 KB/.test(done.rows[0]) && /Inter\.ttf · 856 KB · variable/.test(done.rows[2]) && /3 fonts found/.test(done.note) && !done.btn, JSON.stringify(done));
  check('   the list says Bold for the bold file and leaves Regular out of the name', /^Fixture Boxes Bold/.test(done.rows[1]) && /^Fixture Boxes Fixture/.test(done.rows[0]), JSON.stringify(done.rows));
  check('   a short list has no filter box', done.filter === 'none');

  // 3) a font is looked at before it is used: the sheet shows it, Cancel changes nothing
  await page.click('#fontScanList .font-row:nth-child(1)');
  await sleep(120);
  const sheet = await ev(page, () => ({ open: document.getElementById('fontPreviewModal').classList.contains('show'), name: document.getElementById('fontPvName').innerText, file: document.getElementById('fontPvFile').innerText, fam: getComputedStyle(document.getElementById('fontPvSample')).fontFamily, big: getComputedStyle(document.querySelector('#fontPvSample .fs-big')).fontFamily, w: (() => { const s = document.createElement('span'); s.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font-size:20px;font-family:inherit'; s.textContent = 'iiii'; document.getElementById('fontPvSample').appendChild(s); const w = Math.round(s.getBoundingClientRect().width); s.remove(); return w; })() }));
  check('3. tapping a font opens a sheet with its name and file, and a sample set in that font', sheet.open && sheet.name === 'Fixture Boxes' && /Fixture-Boxes-Regular\.ttf · 3 KB/.test(sheet.file) && /PreviewFont/.test(sheet.fam) && /PreviewFont/.test(sheet.big) && sheet.w === 48, JSON.stringify(sheet));
  check('   the app was asked for exactly that file', (await calls(page)).includes('fontPreview:/storage/emulated/0/Download/Fixture-Boxes-Regular.ttf'));
  await page.click('#fontPreviewModal .mode-btn-row .mode-action-btn:not(.primary)');
  check('   Cancel closes the sheet and the page keeps the system font', !(await ev(page, () => document.getElementById('fontPreviewModal').classList.contains('show'))) && (await iiii(page)) === sysW && !(await calls(page)).includes('fontApply'));

  // 4) a file that is not a font: the reason is told, nothing opens
  await page.click('#fontScanList .font-row:nth-child(3)');
  await sleep(120);
  check('4. a file that is not a font gives the reason in a toast and opens no sheet', /Could not use that file: That file is not a TrueType or OpenType font\./.test(await toast(page)) && !(await ev(page, () => document.getElementById('fontPreviewModal').classList.contains('show'))), await toast(page));

  // 5) use it: the whole page changes, the choice is kept, code boxes keep their own font
  await page.click('#fontScanList .font-row:nth-child(1)');
  await sleep(120);
  await page.click('#fontPreviewModal .mode-btn-row .primary');
  await sleep(150);
  const used = await ev(page, () => ({ cur: document.getElementById('fontCurrent').innerText, body: getComputedStyle(document.body).fontFamily, kv: JSON.parse(window.__kv.ui_font), css: document.documentElement.style.getPropertyValue('--ui-font'), sys: getComputedStyle(document.getElementById('fontSystemBtn')).display, mono: getComputedStyle(document.getElementById('fmCurrentPath')).fontFamily, sheet: document.getElementById('fontPreviewModal').classList.contains('show') }));
  check('5. Use this font: the sheet closes, the page is set in it, "In use" names it', !used.sheet && /^"?AppFont/.test(used.body.replace(/'/g, '"')) && /Fixture Boxes/.test(used.cur) && /Fixture-Boxes-Regular\.ttf/.test(used.cur) && used.sys !== 'none', JSON.stringify(used));
  check('   every letter of the page is now a 0.6 em box', (await iiii(page)) === 48);
  check('   the choice is kept (kv ui_font) with the font\'s details', used.kv.mode === 'custom' && used.kv.family === 'Fixture Boxes' && used.kv.name === 'Fixture-Boxes-Regular.ttf' && used.kv.size === 2796, JSON.stringify(used.kv));
  check('   code-style text (the path of the file manager) keeps its monospace font', /monospace/.test(used.mono), used.mono);
  check('   the toast says which font', /Font: Fixture Boxes/.test(await toast(page)), await toast(page));
  await page.close();

  // 6) the next launch: the font is loaded again before anything is used
  const cur = { meta: meta('Fixture Boxes', 'Regular', 'Fixture-Boxes-Regular.ttf', 2796), b64: B64 };
  page = await open({ current: cur, kv: { ui_font: JSON.stringify({ mode: 'custom', family: 'Fixture Boxes', style: 'Regular', name: 'Fixture-Boxes-Regular.ttf', kind: 'ttf', variable: false, size: 2796 }) } });
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--ui-font') !== '', null, { timeout: 4000 });
  check('6. at the next launch the chosen font is applied again', (await iiii(page)) === 48 && /Fixture Boxes/.test(await ev(page, () => document.getElementById('fontCurrent').innerText)));

  // 7) back to the system font
  await ev(page, () => switchView('prefs'));
  await page.click('#fontSystemBtn');
  await sleep(60);
  const back = await ev(page, () => ({ css: document.documentElement.style.getPropertyValue('--ui-font'), kv: JSON.parse(window.__kv.ui_font), btn: getComputedStyle(document.getElementById('fontSystemBtn')).display }));
  check('7. Use system font: the app is told to drop the file, the page is back in the system font, the choice is kept as system', (await calls(page)).includes('fontClear') && back.css === '' && back.kv.mode === 'system' && back.btn === 'none' && (await iiii(page)) === sysW, JSON.stringify(back));
  await page.close();

  // 8) a saved font that cannot be loaded: the system font stays, and the choice is dropped with a word about it
  page = await open({ current: { meta: cur.meta, b64: '' }, kv: { ui_font: JSON.stringify({ mode: 'custom', family: 'Gone', style: 'Regular', name: 'Gone.ttf', kind: 'ttf', variable: false, size: 10 }) } });
  await sleep(300);
  const gone = await ev(page, () => ({ css: document.documentElement.style.getPropertyValue('--ui-font'), kv: JSON.parse(window.__kv.ui_font), cur: document.getElementById('fontCurrent').innerText }));
  check('8. a saved font that is missing: the system font stays, the choice is reset and a toast says so', gone.css === '' && gone.kv.mode === 'system' && /system font/i.test(gone.cur) && /could not be loaded/.test(await toast(page)), JSON.stringify(gone) + ' ' + await toast(page));
  await page.close();

  // 9) the file chooser: no access needed; its answer goes the same way as a font from the list
  page = await open();
  await ev(page, () => switchView('prefs'));
  await page.click('.font-cur ~ .mode-btn-row .mode-action-btn:nth-child(3)');
  await sleep(150);
  check('9. Choose a file: the chooser opens, its answer is read and shown on the sheet', (await calls(page)).join() === 'pickFontFile,fontPreview:content://font/1' && /chosen\.ttf/.test(await ev(page, () => document.getElementById('fontPvFile').innerText)) && await ev(page, () => document.getElementById('fontPreviewModal').classList.contains('show')), (await calls(page)).join());
  await page.close();

  // 10) a search that finds nothing, one that cannot read storage, one that fails
  page = await open({ fonts: [] });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn'); await sleep(150);
  check('10. nothing found: a sentence says so and that a file can still be chosen by hand', /No \.ttf or \.otf files were found/.test(await ev(page, () => document.getElementById('fontScanStatus').innerText)));
  await page.close();
  page = await open({ noAccess: true, perm: { files: false } });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn'); await sleep(200);
  const na = await ev(page, () => ({ note: document.getElementById('fontScanStatus').innerText, err: !!document.querySelector('#fontScanStatus .is-err'), prompt: document.querySelector('.modal-overlay.show') ? document.querySelector('.modal-overlay.show').innerText : '' }));
  check('    no access to storage: the note says so in the failed color and the access prompt opens', /cannot read storage/.test(na.note) && na.err && /All-files access/i.test(na.prompt), JSON.stringify(na));
  await page.close();
  page = await open({ scanError: 'disk on fire' });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn'); await sleep(150);
  check('    a failed search starts with words and is in the failed color', /^The search failed: disk on fire/.test(await ev(page, () => document.getElementById('fontScanStatus').innerText)) && await ev(page, () => !!document.querySelector('#fontScanStatus .is-err')));
  await page.close();

  // 10b) a variable font is declared with a weight range, so that bold text uses the font's own bold
  page = await open({ fonts: [Object.assign({}, FOUND[2], { path: '/storage/emulated/0/Fonts/Var.ttf', name: 'Var.ttf', family: 'Var Sans', variable: true })], fontFiles: { '/storage/emulated/0/Fonts/Var.ttf': { meta: meta('Var Sans', 'Regular', 'Var.ttf', 5000, true), b64: B64 } } });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn'); await sleep(150);
  await page.click('#fontScanList .font-row:nth-child(1)'); await sleep(120);
  await page.click('#fontPreviewModal .mode-btn-row .primary'); await sleep(150);
  check('10b. a variable font is loaded with a weight range (1 to 1000), a static one without', await ev(page, () => Array.from(document.fonts).some(f => f.family.replace(/"/g, '') === 'AppFont' && f.weight === '1 1000')), await ev(page, () => JSON.stringify(Array.from(document.fonts).map(f => [f.family, f.weight]))));
  await page.close();

  // 11) a long list gets a filter box
  const many = Array.from({ length: 12 }, (_, i) => ({ path: '/storage/emulated/0/Fonts/F' + i + '.ttf', name: 'F' + i + '.ttf', kind: 'ttf', family: i === 5 ? 'Zebra Sans' : 'Family ' + i, style: 'Regular', variable: false, size: 1000 + i, mtime: 0 }));
  page = await open({ fonts: many });
  await ev(page, () => switchView('prefs'));
  await page.click('#fontScanBtn'); await sleep(150);
  check('11. twelve fonts: the filter box shows and narrows the list by name', await ev(page, () => getComputedStyle(document.getElementById('fontScanSearch')).display !== 'none') && (await ev(page, () => document.querySelectorAll('#fontScanList .font-row').length)) === 12);
  await page.fill('#fontScanSearch', 'zebra');
  check('    typing zebra leaves one font', (await ev(page, () => document.querySelectorAll('#fontScanList .font-row').length)) === 1);
  await page.fill('#fontScanSearch', 'nothing like it');
  check('    a name nothing has says so', /No font matches/.test(await ev(page, () => document.getElementById('fontScanList').innerText)));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
