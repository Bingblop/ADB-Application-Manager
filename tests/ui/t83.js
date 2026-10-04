// v7.3 File Manager II: the search bar (name, ext:, content:, archive:), where to search (a drop-down), the two boxes (subfolders, inside archives), progress and Stop,
// the results list (open, show in folder, archive entries), and the extract dialog (this folder / a new folder named after the archive / another folder, what to do
// with a taken name, delete the archive afterwards). The app's side is lib/fm_mock.js; the rules themselves are checked by the Java suites (filesearch, fileops).
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = '/storage/emulated/0';
const FILES = {
  [ROOT + '/Download']: null, [ROOT + '/Download/report.pdf']: { size: 5000, bin: true }, [ROOT + '/Download/notes.txt']: 'Shopping list\nmilk and eggs\nCall Dr. Smith\n',
  [ROOT + '/Pictures/cat.jpg']: { size: 2000, bin: true }, [ROOT + '/Pictures/notes backup.txt']: 'old notes', [ROOT + '/.hidden/secret-notes.txt']: 'hidden notes',
  [ROOT + '/pack.zip']: { size: 3000, bin: true }, [ROOT + '/Download/data.tar.gz']: { size: 4000, bin: true }, [ROOT + '/Docs/a.txt']: 'A', [ROOT + '/Copy/one.txt']: 'OLD1',
  [ROOT + '/<img src=x onerror=window.__pwn=1>.txt']: 'x',
};
const ARCHIVES = { [ROOT + '/pack.zip']: ['readme.txt', 'img/logo.png', 'docs/'] };
const ZIPFILES = { [ROOT + '/pack.zip']: [{ name: 'one.txt', text: 'NEW1' }, { name: 'two.txt', text: 'NEW2' }, { name: 'three.txt', text: 'NEW3' }], [ROOT + '/Download/data.tar.gz']: [{ name: 'x.txt', text: 'X' }] };

(async () => {
  const b = await chromium.launch();
  const open = async opts => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.__dialogs = [];
    page.on('dialog', d => { page.__dialogs.push(d.message()); (page.__accept ? d.accept() : d.dismiss()).catch(() => {}); });
    await page.addInitScript(fm.initScript, Object.assign({ files: FILES, archives: ARCHIVES, zipFiles: ZIPFILES }, opts || {}));
    await page.goto(PAGE);
    await page.waitForTimeout(300);
    await page.evaluate(() => switchView('files'));
    await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const toast = page => ev(page, () => document.getElementById('toastMsg').innerText);
  const modal = (page, id) => ev(page, id => document.getElementById(id).classList.contains('show'), id);
  const search = async (page, q, wait) => { await ev(page, q => { document.getElementById('fmSearchInput').value = q; fmSearchGo(); }, q); await sleep(wait === undefined ? 200 : wait); };
  const rows = page => ev(page, () => Array.from(document.querySelectorAll('#fmSearchList .perm-row')).map(r => r.querySelector('.perm-name').innerText));
  const lastSearch = page => ev(page, () => window.__fm.searches.slice(-1)[0]);

  // ---------------------------------------------------------------- 1) the bar and its options
  let page = await open();
  const bar = await ev(page, () => ({ ph: document.getElementById('fmSearchInput').placeholder, scope: Array.from(document.getElementById('fmSearchScope').options).map(o => o.text), nested: document.getElementById('fmSearchNested').checked, arc: document.getElementById('fmSearchArc').checked, help: getComputedStyle(document.getElementById('fmSearchHelp')).display }));
  check('1. a search bar with a drop-down of where to search, and two boxes: subfolders (on) and inside archives (off)', /Search/.test(bar.ph) && bar.scope.join('|') === 'This folder|Internal storage|Downloads|SD card or drive (1234-ABCD)|Whole phone' && bar.nested === true && bar.arc === false, JSON.stringify(bar));
  check('   the help is folded away until "Search help" is tapped', bar.help === 'none' && (await ev(page, () => { fmSearchHelpToggle(); return getComputedStyle(document.getElementById('fmSearchHelp')).display !== 'none' && /content:word/.test(document.getElementById('fmSearchHelp').innerText); })));
  await ev(page, () => fmSearchHelpToggle());
  await search(page, '   ', 50);
  check('   an empty query asks for something to search for and sends nothing', /Type what to search for/.test(await toast(page)) && !(await ev(page, () => !!window.__fm.searches)));

  // ---------------------------------------------------------------- 2) a search and its results
  await search(page, 'notes', 80);
  check('2. a search sends the query, the folder on screen, subfolders on, archives off, hidden off', JSON.stringify(await lastSearch(page)) === JSON.stringify({ q: 'notes', roots: [ROOT], nested: true, archives: false, hidden: false }), JSON.stringify(await lastSearch(page)));
  await sleep(250);
  const r1 = await rows(page);
  check('   the results take the place of the folder list: names found (hidden ones left out)', r1.join('|') === 'notes.txt|notes backup.txt' && (await ev(page, () => getComputedStyle(document.getElementById('fmList')).display)) === 'none' && (await ev(page, () => getComputedStyle(document.getElementById('fmSearchList')).display)) !== 'none', JSON.stringify(r1));
  const cnt = await ev(page, () => document.getElementById('fmSearchCount').innerText);
  check('   a line says how many were found and how long it took', /^2 found · 0\.1 s$/.test(cnt), cnt);
  const row = await ev(page, () => { const r = document.querySelector('#fmSearchList .perm-row'); return { name: r.querySelector('.perm-name').innerText, subs: Array.from(r.querySelectorAll('.perm-sub')).map(x => x.innerText), role: r.getAttribute('role'), tab: r.tabIndex, btn: r.querySelector('button').getAttribute('aria-label') }; });
  check('   each result shows its name, the folder it is in, size and date, and a button to show it in its folder', row.name === 'notes.txt' && row.subs[0] === ROOT + '/Download' && /^\d+(\.\d)? (B|KB|MB)/.test(row.subs[1]) && row.role === 'button' && row.tab === 0 && !!row.btn, JSON.stringify(row));
  await ev(page, () => fmSearchExit());
  check('   "Back to the folder" brings the folder list back', (await ev(page, () => getComputedStyle(document.getElementById('fmList')).display)) !== 'none' && (await ev(page, () => getComputedStyle(document.getElementById('fmSearchList')).display)) === 'none');

  // options and places
  await ev(page, () => { document.getElementById('fmSearchNested').click(); document.getElementById('fmSearchArc').click(); });
  await search(page, 'notes', 300);
  check('   the boxes go with the search: subfolders off, archives on', JSON.stringify(await lastSearch(page)) === JSON.stringify({ q: 'notes', roots: [ROOT], nested: false, archives: true, hidden: false }));
  check('   with subfolders off only what is directly in the folder is found', (await rows(page)).length === 0 && /Nothing found/.test(await ev(page, () => document.getElementById('fmSearchList').innerText)));
  await ev(page, () => fmSearchExit());
  await page.close();
  page = await open({ kv: { fm_search: JSON.stringify({ nested: false, arc: true }) } });
  check('   the two boxes are kept between launches', (await ev(page, () => document.getElementById('fmSearchNested').checked)) === false && (await ev(page, () => document.getElementById('fmSearchArc').checked)) === true);
  await ev(page, () => { document.getElementById('fmSearchNested').checked = true; document.getElementById('fmSearchArc').checked = false; document.getElementById('fmSearchScope').value = '1'; });
  await search(page, 'notes', 300);
  check('   "Downloads" in the drop-down searches that folder, not the one on screen', JSON.stringify((await lastSearch(page)).roots) === JSON.stringify([ROOT + '/Download']) && (await rows(page)).join('|') === 'notes.txt');
  await ev(page, () => { fmSearchExit(); document.getElementById('fmSearchScope').value = '3'; });
  await search(page, 'cat', 300);
  check('   "Whole phone" searches /', JSON.stringify((await lastSearch(page)).roots) === JSON.stringify(['/']));
  await ev(page, () => { fmSearchExit(); document.getElementById('fmSearchScope').value = 'here'; document.getElementById('fmHiddenChk').click(); }); await sleep(60);
  await search(page, 'notes', 300);
  check('   Show hidden files also applies to the search', (await lastSearch(page)).hidden === true && (await rows(page)).includes('secret-notes.txt'));
  await ev(page, () => { fmSearchExit(); document.getElementById('fmHiddenChk').click(); });

  // ---------------------------------------------------------------- 3) content: and archive:
  await search(page, 'content:smith', 300);
  const hit = await ev(page, () => { const r = document.querySelector('#fmSearchList .perm-row'); return { name: r && r.querySelector('.perm-name').innerText, line: r && r.querySelector('.fm-hit-line') && r.querySelector('.fm-hit-line').innerText }; });
  check('3. content: shows the line that matched, with its number', hit.name === 'notes.txt' && hit.line === '3: Call Dr. Smith', JSON.stringify(hit));
  await ev(page, () => fmSearchExit());
  await search(page, 'archive:readme', 300);
  const ah = await ev(page, () => { const r = document.querySelector('#fmSearchList .perm-row'); return { name: r.querySelector('.perm-name').innerText, where: r.querySelector('.perm-sub').innerText }; });
  check('   archive: shows the entry, and the archive it is in', ah.name === 'readme.txt' && ah.where === ROOT + '/pack.zip › readme.txt', JSON.stringify(ah));
  await ev(page, () => { window.__arc = []; window.arcOpen = (p) => window.__arc.push(p); fmSearchOpen(0); }); await sleep(30);
  check('   tapping an entry opens that archive (and leaves the search)', (await ev(page, () => window.__arc.join())) === ROOT + '/pack.zip' && !(await ev(page, () => fmSearchMode)));
  await search(page, 'type:banana cat', 300);
  check('   what could not be understood is said, the rest of the query still works', /banana is not a kind I know/.test(await ev(page, () => document.getElementById('fmSearchList').innerText)) && (await rows(page)).join() === 'cat.jpg');
  await ev(page, () => fmSearchExit());

  // ---------------------------------------------------------------- 4) opening a result
  await search(page, 'notes', 300);
  await ev(page, () => fmSearchOpen(1)); await sleep(60);
  check('4. tapping a file opens its action sheet (View, Edit, Open with ...)', (await modal(page, 'fmActionModal')) && /notes/.test(await ev(page, () => document.getElementById('fmActionName').innerText)));
  await ev(page, () => closeFmAction());
  await ev(page, () => fmSearchReveal(1)); await sleep(100);
  check('   the arrow button shows it in its own folder and leaves the search', (await ev(page, () => fmPath)) === ROOT + '/Pictures' && !(await ev(page, () => fmSearchMode)));
  await ev(page, () => fmGo('/storage/emulated/0'));
  await search(page, 'type:folder', 300);
  check('   type:folder lists the folders; tapping one opens it', (await rows(page)).includes('Download/') && (await ev(page, () => { const i = fmSearchHits.findIndex(h => h.path.endsWith('/Pictures')); fmSearchOpen(i); return fmPath; })) === ROOT + '/Pictures');
  await search(page, 'notes', 300);
  await ev(page, () => fmUp()); await sleep(60);
  check('   going up a folder while looking at results leaves the results', !(await ev(page, () => fmSearchMode)) && (await ev(page, () => fmPath)) === ROOT);
  await search(page, 'notes', 300);
  const back = await ev(page, () => backNavigate());
  check('   the Back button leaves the results first', back === true && !(await ev(page, () => fmSearchMode)));

  // names are text, not markup
  await search(page, 'img src', 300);
  check('   a file named like markup is shown as text', (await ev(page, () => window.__pwn === undefined)) && (await rows(page)).some(x => x.indexOf('<img src=x') === 0) && (await ev(page, () => document.querySelectorAll('#fmSearchList img').length)) === 0);
  await ev(page, () => fmSearchExit());
  await page.close();

  // ---------------------------------------------------------------- 5) stop, and a limit
  page = await open({ holdSearch: true });
  await search(page, 'notes', 80);
  const run = await ev(page, () => ({ btn: document.getElementById('fmSearchBtn').innerText, status: document.getElementById('fmSearchText').innerText, running: fmSearchRunning }));
  check('5. while it runs: the button says Stop, a line shows the folder, how many were looked at and found', run.btn === 'Stop' && run.running && /Searching .* · 7 looked at · 1 found/.test(run.status), JSON.stringify(run));
  await ev(page, () => document.getElementById('fmSearchBtn').click()); await sleep(200);
  check('   Stop asks the app to stop; the answer says it was stopped; the button is Search again', (await ev(page, () => window.__fm.searchCancels)) === 1 && /stopped/.test(await ev(page, () => document.getElementById('fmSearchCount').innerText)) && (await ev(page, () => document.getElementById('fmSearchBtn').innerText)) === 'Search');
  await page.close();
  page = await open({ searchTruncated: true });
  await search(page, 'notes', 300);
  check('   a search that hit its limit says so', /stopped at the limit/.test(await ev(page, () => document.getElementById('fmSearchCount').innerText)));
  await page.close();

  // ---------------------------------------------------------------- 6) the extract dialog
  page = await open();
  await ev(page, () => fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'));
  const ab = await ev(page, () => Array.from(document.querySelectorAll('#fmActionBtns button')).map(x => x.innerText));
  check('6. a zip in the file manager offers Extract… (a text file does not)', ab.includes('Extract…') && !(await ev(page, () => { fmActions('/storage/emulated/0/Docs/a.txt', false, 'a.txt'); return Array.from(document.querySelectorAll('#fmActionBtns button')).some(x => x.innerText === 'Extract…'); })));
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); }); await sleep(40);
  const d1 = await ev(page, () => ({ open: document.getElementById('exModal').classList.contains('show'), sub: (document.getElementById('exWhat').innerText + document.getElementById('exNames').innerText), here: document.getElementById('exHerePath').innerText, named: document.getElementById('exNamedPath').innerText, where: document.querySelector('input[name=exWhere]:checked').value, policy: document.querySelector('input[name=exPolicy]:checked').value, del: getComputedStyle(document.getElementById('exDelRow')).display, other: getComputedStyle(document.getElementById('exOtherInput')).display }));
  check('   the dialog names the archive, offers its folder and a new folder named after it (the ending dropped); a new folder and Keep both are chosen at first', d1.open && d1.sub === 'pack.zip' && d1.here === ROOT && d1.named === ROOT + '/pack' && d1.where === 'named' && d1.policy === 'keep' && d1.del !== 'none' && d1.other === 'none', JSON.stringify(d1));
  await ev(page, () => exGo()); await sleep(250);
  const e1 = await ev(page, () => window.__fm.extracts.slice(-1)[0]);
  check('   Extract sends the archive, the whole of it, the new folder, the rule, no delete', e1.path === ROOT + '/pack.zip' && e1.entry === '' && e1.dest === ROOT + '/pack' && e1.policy === 'keep' && e1.del === false, JSON.stringify(e1));
  check('   the files are there and the toast says how many', (await ev(page, () => Object.keys(window.__fm.fs).filter(p => p.startsWith('/storage/emulated/0/pack/')).length)) === 3 && /Extracted 3 files/.test(await toast(page)), await toast(page));
  // taken names
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); document.querySelector('input[name=exPolicy][value=keep]').checked = true; exGo(); }); await sleep(250);
  check('   a second time with Keep both: "one (1).txt" next to "one.txt"', (await ev(page, () => !!window.__fm.fs['/storage/emulated/0/pack/one (1).txt'] && window.__fm.fs['/storage/emulated/0/pack/one.txt'].text === 'NEW1')));
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); document.querySelector('input[name=exPolicy][value=skip]').checked = true; exGo(); }); await sleep(250);
  check('   with Skip nothing is written and the toast says they were left as they were', /3 left as they were/.test(await toast(page)), await toast(page));
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); document.querySelector('input[name=exWhere][value=here]').checked = true; exWhereChange(); document.querySelector('input[name=exPolicy][value=replace]').checked = true; exGo(); }); await sleep(250);
  const e2 = await ev(page, () => window.__fm.extracts.slice(-1)[0]);
  check('   "This folder" with Replace: the archive\'s own folder and the rule', e2.dest === ROOT && e2.policy === 'replace');
  // another folder
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); document.querySelector('input[name=exWhere][value=other]').checked = true; exWhereChange(); document.getElementById('exOtherInput').value = 'relative/path'; });
  const shownOther = await ev(page, () => getComputedStyle(document.getElementById('exOtherInput')).display !== 'none');
  await ev(page, () => exGo()); await sleep(40);
  check('   "Another folder" shows a box; a path that is not full is refused', shownOther && /full path/.test(await toast(page)) && (await modal(page, 'exModal')));
  await ev(page, () => { document.getElementById('exOtherInput').value = '/storage/emulated/0/Elsewhere/'; exGo(); }); await sleep(250);
  check('   a full path is used (the end slash dropped) and remembered for the next time', (await ev(page, () => window.__fm.extracts.slice(-1)[0].dest)) === ROOT + '/Elsewhere');
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); });
  check('   the choices of last time are there again', (await ev(page, () => document.querySelector('input[name=exWhere]:checked').value)) === 'other' && (await ev(page, () => document.getElementById('exOtherInput').value)) === '/storage/emulated/0/Elsewhere/' && (await ev(page, () => document.querySelector('input[name=exPolicy]:checked').value)) === 'replace');
  await ev(page, () => exClose());
  // delete afterwards
  page.__accept = false;
  await ev(page, () => { fmActions('/storage/emulated/0/Download/data.tar.gz', false, 'data.tar.gz'); fmExtractFile(); });
  check('   a .tar.gz is named without its whole ending', (await ev(page, () => document.getElementById('exNamedPath').innerText)) === ROOT + '/Download/data');
  await ev(page, () => { document.querySelector('input[name=exWhere][value=named]').checked = true; document.getElementById('exDel').checked = true; exGo(); }); await sleep(60);
  check('   "Delete the archive afterwards" asks first; saying no sends nothing and keeps the dialog open', page.__dialogs.slice(-1)[0] === 'Delete data.tar.gz after it was extracted?' && (await modal(page, 'exModal')) && !(await ev(page, () => window.__fm.extracts.some(e => e.path.endsWith('data.tar.gz')))));
  page.__accept = true;
  await ev(page, () => exGo()); await sleep(300);
  check('   yes: extracted, the archive is deleted, the toast says so and the list no longer has it', (await ev(page, () => window.__fm.extracts.slice(-1)[0].del)) === true && !(await ev(page, () => !!window.__fm.fs['/storage/emulated/0/Download/data.tar.gz'])) && /archive deleted/.test(await toast(page)), await toast(page));
  page.__accept = false;
  // from inside the archive browser: a folder of it, no delete option
  await ev(page, () => { arc = { path: '/storage/emulated/0/pack.zip', name: 'pack.zip', nested: false }; arcTarget = { path: 'docs/', dir: true, name: 'docs' }; arcCloseModal = () => {}; arcExtractStart(); });
  const d2 = await ev(page, () => ({ sub: (document.getElementById('exWhat').innerText + document.getElementById('exNames').innerText), del: getComputedStyle(document.getElementById('exDelRow')).display }));
  check('   from the archive browser a folder of the archive: the dialog says which, and there is no "delete the archive"', /docs/.test(d2.sub) && /pack\.zip/.test(d2.sub) && d2.del === 'none', JSON.stringify(d2));
  await ev(page, () => exClose());
  await page.close();

  // a running extraction: progress line and Cancel
  page = await open({ holdExtract: true });
  await ev(page, () => { fmActions('/storage/emulated/0/pack.zip', false, 'pack.zip'); fmExtractFile(); exGo(); }); await sleep(120);
  const pr = await ev(page, () => ({ shown: getComputedStyle(document.getElementById('fmBatchStatus')).display !== 'none', text: document.getElementById('fmBatchText').innerText, cancel: !!document.querySelector('#fmBatchStatus button') }));
  check('   while it runs: a line with the file, percent, speed and time left, and a Cancel button', pr.shown && /33% · 1\.2 MB\/s · 4 s left/.test(pr.text) && pr.cancel, JSON.stringify(pr));
  await ev(page, () => fmExtractFile && document.querySelector('#fmBatchStatus button').click()); await sleep(200);
  check('   Cancel tells the app to stop and the line goes away', (await ev(page, () => window.__fm.extractCancels)) === 1 && getComputedStyleNone(await ev(page, () => getComputedStyle(document.getElementById('fmBatchStatus')).display)));
  await page.close();


  // ---------------------------------------------------------------- 7) what the reviews found
  page = await open();
  // a search keeps working when the folder underneath is changed, and starting one leaves selecting
  await ev(page, () => { fmSelEnter('/storage/emulated/0/a.txt'); fmClip = { op: 'cp', paths: ['/storage/emulated/0/Docs/a.txt'] }; fmClipRender(); });
  await search(page, 'notes', 300);
  check('7. starting a search leaves selecting, and the Paste bar is hidden under the results', !(await ev(page, () => fmSelMode)) && (await ev(page, () => getComputedStyle(document.getElementById('fmSelBar')).display)) === 'none' && (await ev(page, () => getComputedStyle(document.getElementById('fmClipBar')).display)) === 'none');
  await ev(page, () => fmRefresh()); await sleep(400);
  check('   a refresh of the folder (after a rename, a delete or an extraction) makes the results again instead of throwing them away', (await ev(page, () => fmSearchMode)) && (await rows(page)).length === 2 && (await ev(page, () => window.__fm.searches.length)) === 2);
  await ev(page, () => fmSearchExit());
  check('   leaving the results brings the Paste bar back', (await ev(page, () => getComputedStyle(document.getElementById('fmClipBar')).display)) !== 'none');
  // keyboard: Enter on the "In folder" button does one thing
  await search(page, 'notes', 300);
  await ev(page, () => { window.__log = []; const o = window.fmSearchOpen, r = window.fmSearchReveal; window.fmSearchOpen = i => { window.__log.push('open' + i); }; window.fmSearchReveal = i => { window.__log.push('reveal' + i); }; });
  await page.focus('#fmSearchList .perm-row button'); await page.keyboard.press('Enter'); await sleep(30);
  check('   Enter on the "In folder" button shows it in its folder and does not also open the file', (await ev(page, () => window.__log.join())) === 'reveal0' || (await ev(page, () => window.__log.join())) === 'reveal0');
  await page.close();
  // a second search after leaving a slow one
  page = await open({ holdSearch: true });
  await search(page, 'notes', 80);
  await ev(page, () => fmSearchExit());
  check('   leaving the results while a search runs puts the button back to Search, so the next search can start', (await ev(page, () => document.getElementById('fmSearchBtn').innerText)) === 'Search' && !(await ev(page, () => fmSearchRunning)));
  await page.close();
  // the real "Extract this folder" of the archive browser
  page = await open();
  await ev(page, () => { arc = { path: '/storage/emulated/0/pack.zip', name: 'pack.zip', nested: false, count: 3 }; arcDir = 'docs/'; arcExtractHere(); });
  const eh = await ev(page, () => ({ ex: document.getElementById('exModal').classList.contains('show'), arcm: document.getElementById('arcModal').classList.contains('show'), what: document.getElementById('exWhat').innerText, names: document.getElementById('exNames').innerText }));
  check('   "Extract this folder" in the archive browser opens only the extract dialog (the old sheet is not left on top)', eh.ex && !eh.arcm && eh.what === 'The folder' && /docs/.test(eh.names), JSON.stringify(eh));
  await ev(page, () => exClose());
  // names for the new folder
  for (const [name, want] of [['data.tar.gz', 'data'], ['a.b.c.zip', 'a.b.c'], ['.zip', 'zip (extracted)'], ['README', 'README (extracted)']]) {
    await ev(page, n => extractOpen({ src: '/storage/emulated/0/' + n, name: n, whole: true, deletable: true }), name);
    const got = await ev(page, () => document.getElementById('exNamedPath').innerText);
    check('   the folder for ' + JSON.stringify(name) + ' is "' + want + '" (never the file itself, never a hidden folder)', got === '/storage/emulated/0/' + want, got);
    await ev(page, () => exClose());
  }
  await ev(page, () => extractOpen({ src: '/pack.zip', name: 'pack.zip', whole: true }));
  check('   an archive at the root of the phone does not give a double slash', (await ev(page, () => document.getElementById('exNamedPath').innerText)) === '/pack' && (await ev(page, () => (document.querySelector('input[name=exWhere][value=named]').checked = true, exDest()))) === '/pack');
  await ev(page, () => exClose());
  // an extraction does not start while a copy runs
  await ev(page, () => { fmBatchRunning = true; extractOpen({ src: '/storage/emulated/0/pack.zip', name: 'pack.zip', whole: true }); exGo(); }); await sleep(30);
  check('   an extraction does not start while a copy or move is running (it would take over its status line and Cancel)', /still running/.test(await toast(page)) && !(await ev(page, () => !!window.__fm.extracts)));
  await ev(page, () => { fmBatchRunning = false; });
  await page.close();
  // the search texts are built so that they can be translated
  page = await open();
  await search(page, 'notes', 300);
  check('   the count line is made of pieces the translation can match ("N found", the time kept as a number)', await ev(page, () => Array.from(document.querySelectorAll('#fmSearchCount > span')).map(x => x.innerText).join('|').replace(/\d/g, '#')) === '# found|# s'.replace('# s', '#.# s'));
  await page.close();
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
function getComputedStyleNone(d) { return d === 'none'; }
