// v5.7 file manager: View on an .apk / .zip / package file opens its contents (no extracting) with preview, extract and in-place edit.
const { chromium, PAGE, OUT } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; let dialogAnswer = true;
  page.on('dialog', async d => { dialogs.push(d.message()); await (dialogAnswer ? d.accept() : d.dismiss()); });
  await page.addInitScript(() => {
    window.__calls = { open: [], list: [], read: [], extract: [], edit: [], close: 0, fmRead: [], fmList: [] };
    window.__arc = { editable: true, apk: true, whyNot: '', failOpen: false };
    const T = Date.UTC(2024, 0, 8, 12);
    const files = [
      { p: 'AndroidManifest.xml', s: 2048, c: 900, m: 8, kind: 'axml', text: '<?xml version="1.0" encoding="utf-8"?>\n<manifest package="com.example">\n</manifest>\n' },
      { p: 'classes.dex', s: 3000000, c: 1200000, m: 8, kind: 'hex', hex: '00000000  64 65 78 0a 30 33 35 00  |dex.035.|\n' },
      { p: 'resources.arsc', s: 100000, c: 100000, m: 0, kind: 'hex', hex: '00000000  02 00 0c 00  |....|\n' },
      { p: 'res/layout/main.xml', s: 640, c: 300, m: 8, kind: 'axml', text: '<LinearLayout/>\n' },
      { p: 'res/drawable/icon.png', s: 4000, c: 4000, m: 0, kind: 'image', mime: 'image/png', b64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==' },
      { p: 'assets/config.json', s: 24, c: 30, m: 8, kind: 'text', text: '{"a": 1, "b": [1, 2, 3]}\n', editable: true, crlf: false },
      { p: 'assets/notes.txt', s: 12, c: 14, m: 8, kind: 'text', text: 'line1\r\nline2\r\n', editable: true, crlf: true },
      { p: 'lib/arm64-v8a/libx.so', s: 5003, c: 5003, m: 0, kind: 'hex', hex: '00000000  7f 45 4c 46  |.ELF|\n' },
      { p: 'META-INF/MANIFEST.MF', s: 40, c: 40, m: 8, kind: 'text', text: 'Manifest-Version: 1.0\n', editable: true },
      { p: 'secret.bin', s: 100, c: 100, m: 8, e: true, kind: 'hex', hex: '', note: 'encrypted' },
    ];
    for (let i = 0; i < 900; i++) files.push({ p: 'big/file_' + String(i).padStart(4, '0') + '.txt', s: 100 + i, c: 60, m: 8, kind: 'text', text: 'x', editable: true });
    window.__files = files;
    const children = dir => {
      const dirs = new Map(), out = [];
      for (const f of window.__files) {
        if (!f.p.startsWith(dir) || f.p.length <= dir.length) continue;
        const rest = f.p.slice(dir.length), slash = rest.indexOf('/');
        if (slash < 0) out.push({ n: rest, p: f.p, d: false, s: f.s, c: f.c, t: T, m: f.m, e: !!f.e, f: 0 });
        else { const n = rest.slice(0, slash); const a = dirs.get(n) || { n, p: dir + n + '/', d: true, s: 0, c: 0, t: T, m: -1, e: false, f: 0 }; a.f++; a.s += f.s; a.c += f.c; dirs.set(n, a); }
      }
      const cmp = (x, y) => x.n.toLowerCase() < y.n.toLowerCase() ? -1 : 1;
      return [...[...dirs.values()].sort(cmp), ...out.sort(cmp)];
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadSetting() { return ''; }, saveSetting() {}, hasAllFilesAccess() { return true; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      fmList(path) {
        window.__calls.fmList.push(path);
        return JSON.stringify({ path, entries: [
          { name: 'app.apk', isDir: false, isLink: false, size: 5242880, perms: '-rw-' }, { name: 'data.zip', isDir: false, isLink: false, size: 1048576, perms: '-rw-' },
          { name: 'notes.txt', isDir: false, isLink: false, size: 12, perms: '-rw-' }, { name: 'fake.zip', isDir: false, isLink: false, size: 10, perms: '-rw-' }] });
      },
      fmRead(path) { window.__calls.fmRead.push(path); return 'plain text of ' + path; },
      archiveOpen(path) {
        window.__calls.open.push(path);
        if (window.__arc.failOpen || /fake\.zip$/.test(path)) return JSON.stringify({ ok: false, error: 'Not a zip archive (no central directory found)' });
        return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: window.__files.length, files: window.__files.length, size: 5242880, zip64: false, staged: false,
          apk: /\.apk$/i.test(path) && window.__arc.apk, editable: window.__arc.editable, whyNot: window.__arc.whyNot });
      },
      archiveList(path, dir, query, offset, limit) {
        window.__calls.list.push([dir, query, offset, limit]);
        let all;
        if (query) all = window.__files.filter(f => f.p.toLowerCase().includes(query.toLowerCase())).map(f => ({ n: f.p.split('/').pop(), p: f.p, d: false, s: f.s, c: f.c, t: T, m: f.m, e: !!f.e }));
        else all = children(dir);
        return JSON.stringify({ ok: true, dir, query, total: all.length, offset, more: offset + limit < all.length, entries: all.slice(offset, offset + limit) });
      },
      archiveRead(path, entry) {
        window.__calls.read.push(entry);
        const f = window.__files.find(x => x.p === entry);
        if (!f) return JSON.stringify({ ok: false, error: 'Not found in the archive: ' + entry });
        const r = { ok: true, name: f.p, size: f.s, csize: f.c, method: f.m, crc: 0xdeadbeef, mtime: Date.UTC(2024, 0, 8), kind: f.kind };
        if (f.kind === 'text') { r.text = f.text; r.truncated = false; r.crlf = !!f.crlf; r.editable = !!f.editable && window.__arc.editable; if (!window.__arc.editable) r.editNote = window.__arc.whyNot; }
        if (f.kind === 'axml') r.text = f.text;
        if (f.kind === 'hex') { r.hex = f.hex; if (f.note) r.note = f.note; }
        if (f.kind === 'image') { r.mime = f.mime; r.b64 = f.b64; }
        return JSON.stringify(r);
      },
      archiveExtract(path, entry, dest) { window.__calls.extract.push([path, entry, dest]); return window.__busy ? 'busy' : 'started'; },
      archiveExtract2(path, entry, dest, policy, del) { window.__calls.extract.push([path, entry, dest, policy, del]); return window.__busy ? 'busy' : 'started'; },
      archiveEdit(path, op) { window.__calls.edit.push(JSON.parse(op)); return window.__busy ? 'busy' : 'started'; },
      archiveClose() { window.__calls.close++; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  await page.evaluate(() => switchView('files')); await sleep(200);

  // 1) The action sheet: archives say "View contents", other files offer "Open as archive".
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/app.apk', false, 'app.apk'));
  let btns = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('1. apk sheet: "View contents" (no plain View), still has Install:', btns.some(t => /View contents/.test(t)) && !btns.some(t => /^View$/.test(t)) && btns.some(t => /Install/.test(t)), JSON.stringify(btns));
  await page.evaluate(() => closeFmAction());
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/data.zip', false, 'data.zip'));
  btns = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('   zip sheet: "View contents", no "Open as archive" duplicate:', btns.some(t => /View contents/.test(t)) && !btns.some(t => /Open as archive/.test(t)));
  await page.evaluate(() => closeFmAction());
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/notes.txt', false, 'notes.txt'));
  btns = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('   text file sheet: "View" + "Open as archive" (any package file):', btns.some(t => /^View$/.test(t)) && btns.some(t => /Open as archive/.test(t)), JSON.stringify(btns));
  await page.evaluate(() => closeFmAction());

  // 2) View contents opens the archive browser in place of the file list.
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/app.apk', false, 'app.apk'));
  await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(250);
  const st1 = await page.evaluate(() => ({ calls: window.__calls.open.slice(), card: getComputedStyle(document.getElementById('arcCard')).display, top: getComputedStyle(document.getElementById('fmTopCard')).display, list: getComputedStyle(document.getElementById('fmListCard')).display, modal: document.getElementById('fmActionModal').classList.contains('show') }));
  console.log('2. archive opened through the bridge, browser shown, file list hidden, sheet closed:', st1.calls[0] === '/storage/emulated/0/Download/app.apk' && st1.card !== 'none' && st1.top === 'none' && st1.list === 'none' && !st1.modal, JSON.stringify(st1));
  const title = await page.locator('#arcTitle').innerText(), meta = await page.locator('#arcMeta').innerText(), note = await page.locator('#arcNote').innerText();
  console.log('   header: name, file count, size, path:', /app\.apk/.test(title) && /910 files/.test(meta) && /5\.0 MB/.test(meta) && /Download\/app\.apk/.test(meta), JSON.stringify(meta));
  console.log('   APK signature warning shown:', /signature/i.test(note) && /signed again|re-signed/.test(note));

  // 3) Root listing: folders first, then files; details per row.
  const rows0 = await page.locator('#arcList .perm-row').allInnerTexts();
  const names0 = rows0.map(r => r.split('\n')[0].replace(/^\S+\s/, ''));
  console.log('3. root: folders first then files:', JSON.stringify(names0.slice(0, 5)) , names0.slice(0, 4).join() === 'assets,big,lib,META-INF' || names0.slice(0, 4).join() === 'assets,big,lib,META-INF');
  const firstFile = rows0.find(r => /AndroidManifest/.test(r)), arsc = rows0.find(r => /resources\.arsc/.test(r)), dex = rows0.find(r => /classes\.dex/.test(r)), enc = rows0.find(r => /secret\.bin/.test(r));
  console.log('   file rows show size + packed% / stored / encrypted + date:', /2\.0 KB/.test(firstFile) && /44% packed/.test(firstFile) && /2024-01-08/.test(firstFile) && /stored/.test(arsc) && /39% packed|40% packed/.test(dex) && /encrypted/.test(enc), JSON.stringify(firstFile.replace(/\n/g, ' | ')));
  const assetsRow = rows0.find(r => /^assets\//.test(r));
  console.log('   folder row shows file count + total size:', /2 files/.test(assetsRow) && /36 B/.test(assetsRow), JSON.stringify(assetsRow && assetsRow.replace(/\n/g, ' | ')));
  const bigRow = rows0.find(r => /^big\//.test(r));
  console.log('   big folder: 900 files:', /900 files/.test(bigRow));
  const crumb0 = await page.locator('#arcCrumbs').innerText();
  console.log('   breadcrumb root:', /app\.apk/.test(crumb0), JSON.stringify(crumb0));

  // 4) Navigate into a folder, breadcrumbs, Up.
  await page.locator('#arcList .perm-row', { hasText: 'res' }).first().locator('.perm-info').click().catch(() => {});
  await page.evaluate(() => arcGo('res/')); await sleep(80);
  let crumbs = await page.locator('#arcCrumbs .arc-crumb').allInnerTexts();
  let rows1 = (await page.locator('#arcList .perm-row').allInnerTexts()).map(r => r.split('\n')[0]);
  console.log('4. inside res/: crumbs', JSON.stringify(crumbs), 'rows', JSON.stringify(rows1), crumbs.length === 2 && /res/.test(crumbs[1]) && rows1.length === 2);
  await page.evaluate(() => arcGo('res/layout/')); await sleep(60);
  crumbs = await page.locator('#arcCrumbs .arc-crumb').allInnerTexts();
  console.log('   nested crumbs 3 deep:', crumbs.length === 3 && /layout/.test(crumbs[2]));
  await page.locator('#arcCrumbs .arc-crumb', { hasText: 'res' }).click(); await sleep(60);
  const dirNow = await page.evaluate(() => arcDir);
  console.log('   tapping a crumb jumps there:', dirNow === 'res/');
  await page.evaluate(() => arcUp()); await sleep(40);
  console.log('   Up goes to the parent / root:', (await page.evaluate(() => arcDir)) === '');

  // 5) Pagination of a big folder.
  await page.evaluate(() => arcGo('big/')); await sleep(80);
  let n = await page.locator('#arcList .perm-row').count();
  let more = await page.locator('#arcMoreBtn').innerText();
  console.log('5. 400 rows first, "Show more (500 left)":', n === 400 && /500 left/.test(more), n, JSON.stringify(more));
  await page.locator('#arcMoreBtn').click(); await sleep(80);
  n = await page.locator('#arcList .perm-row').count();
  await page.locator('#arcMoreBtn').click(); await sleep(80);
  const n2 = await page.locator('#arcList .perm-row').count();
  const moreHidden = await page.locator('#arcMoreBtn').evaluate(e => getComputedStyle(e).display === 'none');
  console.log('   then 800, then all 900 and the button disappears:', n === 800 && n2 === 900 && moreHidden);
  await page.evaluate(() => arcGo(''));

  // 6) Search across the whole archive.
  await page.fill('#arcSearch', 'config'); await sleep(450);
  let sres = (await page.locator('#arcList .perm-row').allInnerTexts()).map(r => r.split('\n')[0]);
  const scr = await page.locator('#arcCrumbs').innerText();
  console.log('6. search shows matching files by full path:', sres.length === 1 && /assets\/config\.json/.test(sres[0]) && /Search results/.test(scr), JSON.stringify(sres));
  await page.fill('#arcSearch', 'zzzz'); await sleep(450);
  console.log('   no matches message:', /Nothing matches/.test(await page.locator('#arcList').innerText()));
  await page.fill('#arcSearch', ''); await sleep(450);
  console.log('   clearing the search returns to the folder:', (await page.locator('#arcList .perm-row').count()) >= 8);

  // 7) View a text entry, edit it, save (APK confirm accepted).
  await page.evaluate(() => arcGo('assets/')); await sleep(60);
  await page.locator('#arcList .perm-row', { hasText: 'config.json' }).locator('.perm-info').click(); await sleep(120);
  const view1 = await page.evaluate(() => ({ modal: document.getElementById('arcModal').classList.contains('show'), text: document.getElementById('arcViewText').textContent, meta: document.getElementById('arcViewMeta').innerText, btns: [...document.querySelectorAll('#arcModalBtns button')].map(b => b.innerText) }));
  console.log('7. tapping a file opens its preview:', view1.modal && view1.text.startsWith('{"a": 1') && /CRC deadbeef/.test(view1.meta) && /2024-01-08/.test(view1.meta), JSON.stringify(view1.meta));
  console.log('   sheet offers View, Edit text, Extract, Rename, Delete:', ['View', 'Edit text', 'Extract', 'Rename', 'Delete'].every(w => view1.btns.some(t => t.includes(w))), JSON.stringify(view1.btns));
  await page.locator('#arcModalBtns button', { hasText: 'Edit text' }).click(); await sleep(60);
  const ta = await page.locator('#arcEditText').inputValue();
  console.log('   editor opens with the text:', ta === '{"a": 1, "b": [1, 2, 3]}\n' && await page.locator('#arcEditor').isVisible());
  await page.fill('#arcEditText', '{"a": 2}\n');
  await page.locator('#arcEditor button', { hasText: 'Save to archive' }).click(); await sleep(80);
  const e1 = await page.evaluate(() => window.__calls.edit.slice());
  console.log('   save sends a replaceText edit with the new text:', e1.length === 1 && e1[0].op === 'replaceText' && e1[0].name === 'assets/config.json' && e1[0].text === '{"a": 2}\n' && e1[0].crlf === false, JSON.stringify(e1[0]));
  console.log('   APK warning confirm shown once:', dialogs.length === 1 && /signature/.test(dialogs[0]));
  const busy1 = await page.evaluate(() => ({ busy: arcBusy, status: document.getElementById('arcStatus').innerText, modal: document.getElementById('arcModal').classList.contains('show') }));
  console.log('   busy status shown while rewriting (the sheet stays open until it worked, so a failure loses nothing):', busy1.busy && /Rewriting/.test(busy1.status) && busy1.modal);
  await page.evaluate(() => onArchiveProgress('Rewriting… 12 entries, 3.0 MB')); await sleep(30);
  console.log('   progress text updates:', /12 entries/.test(await page.locator('#arcStatus').innerText()));
  const listCallsBefore = await page.evaluate(() => window.__calls.open.length);
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'replaceText', message: 'Saved assets/config.json', apk: true })); await sleep(200);
  const after = await page.evaluate(() => ({ busy: arcBusy, reopened: window.__calls.open.length, dir: arcDir, rows: document.querySelectorAll('#arcList .perm-row').length, status: getComputedStyle(document.getElementById('arcStatus')).display }));
  console.log('   success: toast, archive re-opened, same folder reloaded:', !after.busy && after.reopened === listCallsBefore + 1 && after.dir === 'assets/' && after.rows === 2 && after.status === 'none' && /Saved/.test(await toastText()), JSON.stringify(after));

  // 8) CRLF text keeps its line endings flag; other previews.
  await page.locator('#arcList .perm-row', { hasText: 'notes.txt' }).locator('.perm-info').click(); await sleep(120);
  await page.locator('#arcModalBtns button', { hasText: 'Edit text' }).click(); await sleep(60);
  await page.fill('#arcEditText', 'a\nb\n');
  await page.locator('#arcEditor button', { hasText: 'Save to archive' }).click(); await sleep(60);
  const e2 = await page.evaluate(() => window.__calls.edit[1]);
  console.log('8. CRLF files are flagged so the app writes them back with CRLF:', e2 && e2.crlf === true && e2.text === 'a\nb\n');
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'replaceText', message: 'Saved' })); await sleep(120);
  await page.evaluate(() => arcGo('')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'AndroidManifest.xml' }).locator('.perm-info').click(); await sleep(100);
  const axml = await page.evaluate(() => ({ text: document.getElementById('arcViewText').textContent, meta: document.getElementById('arcViewMeta').innerText, btns: [...document.querySelectorAll('#arcModalBtns button')].map(b => b.innerText) }));
  console.log('   compiled XML shown decoded, read-only (no Edit button):', /<manifest package/.test(axml.text) && /compiled XML/.test(axml.meta) && !axml.btns.some(t => /Edit text/.test(t)), JSON.stringify(axml.meta));
  await page.evaluate(() => arcCloseModal());
  await page.evaluate(() => arcGo('res/drawable/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'icon.png' }).locator('.perm-info').click(); await sleep(100);
  const img = await page.evaluate(() => { const i = document.getElementById('arcViewImg'); return { shown: getComputedStyle(i).display !== 'none', src: (i.getAttribute('src') || '').slice(0, 30) }; });
  console.log('   image preview uses a data URL:', img.shown && img.src.startsWith('data:image/png;base64,'), JSON.stringify(img));
  await page.evaluate(() => arcCloseModal());
  await page.evaluate(() => arcGo('lib/arm64-v8a/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'libx.so' }).locator('.perm-info').click(); await sleep(100);
  const hex = await page.evaluate(() => ({ text: document.getElementById('arcViewText').textContent, ws: document.getElementById('arcViewText').style.whiteSpace }));
  console.log('   binary entries show a hex dump (monospaced, no wrapping):', /\|\.ELF\|/.test(hex.text) && hex.ws === 'pre');
  await page.evaluate(() => arcCloseModal());
  await page.evaluate(() => arcGo(''));
  await page.locator('#arcList .perm-row', { hasText: 'secret.bin' }).locator('.perm-info').click(); await sleep(100);
  console.log('   encrypted entry explains why it has no preview:', /encrypted/.test(await page.locator('#arcViewText').textContent()));
  await page.evaluate(() => arcCloseModal());

  // 9) Extract a file and a folder.
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'config.json' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#arcModalBtns button', { hasText: 'Extract' }).click(); await sleep(40);
  const dflt = await page.evaluate(() => ({ shown: document.getElementById('exModal').classList.contains('show'), named: document.getElementById('exNamedPath').innerText, where: document.querySelector('input[name=exWhere]:checked').value }));
  console.log('9. extract opens the dialog, a new folder named after the archive is the default:', dflt.shown && dflt.named === '/storage/emulated/0/Download/app' && dflt.where === 'named', JSON.stringify(dflt));
  await page.check('input[name=exWhere][value=other]');
  await page.fill('#exOtherInput', '/storage/emulated/0/Download/out');
  await page.locator('#exGoBtn').click(); await sleep(80);
  let ex = await page.evaluate(() => window.__calls.extract.slice());
  console.log('   bridge called with archive, entry, destination:', JSON.stringify(ex[0].slice(0, 3)) === JSON.stringify(['/storage/emulated/0/Download/app.apk', 'assets/config.json', '/storage/emulated/0/Download/out']));
  await page.evaluate(() => onArchiveProgress('Extracting 1: assets/config.json')); await sleep(30);
  console.log('   progress shown:', /Extracting 1/.test(await page.locator('#arcStatus').innerText()));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', files: 1, bytes: 24, skipped: 0, dest: '/storage/emulated/0/Download/out/config.json' })); await sleep(60);
  console.log('   done toast names the destination:', /Extracted 1 file/.test(await toastText()) && /out\/config\.json/.test(await toastText()), JSON.stringify(await toastText()));
  await page.evaluate(() => arcGo('res/')); await sleep(40);
  await page.locator('#arcCard .batch-tool-link', { hasText: 'Extract this folder' }).click(); await sleep(80);
  const exh = await page.evaluate(() => ({ sub: (document.getElementById('exWhat').innerText + document.getElementById('exNames').innerText), open: document.getElementById('exModal').classList.contains('show') }));
  console.log('   "Extract this folder" targets the folder being viewed:', exh.open && /res/.test(exh.sub) && /app\.apk/.test(exh.sub), JSON.stringify(exh));
  await page.locator('#exGoBtn').click(); await sleep(60);
  ex = await page.evaluate(() => window.__calls.extract.slice());
  console.log('   ...and sends the folder path (trailing slash = tree):', ex[1][1] === 'res/');
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', files: 2, bytes: 940, skipped: 1, dest: '/x' })); await sleep(40);
  console.log('   skipped unsafe names are mentioned:', /1 skipped/.test(await toastText()));
  await page.evaluate(() => arcGo('')); await sleep(40);
  await page.locator('#arcCard .batch-tool-link', { hasText: 'Extract this folder' }).click(); await sleep(60);
  const whole = await page.evaluate(() => ({ sub: (document.getElementById('exWhat').innerText + document.getElementById('exNames').innerText) }));
  await page.locator('#exGoBtn').click(); await sleep(40);
  ex = await page.evaluate(() => window.__calls.extract.slice());
  console.log('   at the root it extracts the whole archive (empty path):', whole.sub === 'app.apk' && ex[2][1] === '');
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', files: 910, bytes: 1, skipped: 0, dest: '/x' })); await sleep(40);

  // 10) Rename, delete, new folder, add file.
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'notes.txt' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#arcModalBtns button', { hasText: 'Rename' }).click(); await sleep(40);
  const rn = await page.locator('#arcInput').inputValue();
  await page.fill('#arcInput', 'docs/readme.txt');
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  let ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('10. rename prefilled with the full path; sends rename with the new path:', rn === 'assets/notes.txt' && ed[2].op === 'rename' && ed[2].name === 'assets/notes.txt' && ed[2].to === 'docs/readme.txt', JSON.stringify(ed[2]));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'rename', message: 'Renamed' })); await sleep(120);
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'config.json' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#arcModalBtns button', { hasText: 'Delete' }).click(); await sleep(40);
  const delBtn = await page.locator('#arcInputConfirm').innerText();
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('    delete asks by name and sends delete:', /Delete .config\.json. from the archive/.test(delBtn) && ed[3].op === 'delete' && ed[3].name === 'assets/config.json', JSON.stringify(delBtn));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'delete', message: 'Deleted' })); await sleep(120);
  await page.evaluate(() => arcGo('res/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'drawable' }).locator('.perm-toggle-btn').click(); await sleep(60);
  const folderBtns = await page.locator('#arcModalBtns button').allInnerTexts();
  console.log('    a folder sheet has Extract/Rename/Delete but no View:', !folderBtns.some(t => /View/.test(t)) && folderBtns.some(t => /Extract/.test(t)) && folderBtns.some(t => /Rename/.test(t)) && folderBtns.some(t => /Delete/.test(t)), JSON.stringify(folderBtns));
  await page.locator('#arcModalBtns button', { hasText: 'Delete' }).click(); await sleep(40);
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('    deleting a folder sends its path with a trailing slash:', ed[4].op === 'delete' && ed[4].name === 'res/drawable/');
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'delete', message: 'Deleted' })); await sleep(120);
  await page.evaluate(() => arcGo('res/')); await sleep(40);
  await page.locator('#arcFolderBtn').click(); await sleep(60);
  await page.fill('#arcInput', 'newdir');
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('    new folder goes into the current folder:', ed[5].op === 'mkdir' && ed[5].to === 'res/newdir', JSON.stringify(ed[5]));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'mkdir', message: 'Created' })); await sleep(120);
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcCard .batch-tool-link', { hasText: 'Add file' }).click(); await sleep(60);
  const addUi = await page.evaluate(() => ({ from: document.getElementById('arcInput').value, to: document.getElementById('arcInput2').value, lbl2: document.getElementById('arcInput2Label').innerText }));
  console.log('    add file: source field + name field defaulting to the current folder:', addUi.from === '/storage/emulated/0/Download/' && addUi.to === 'assets/' && /Name inside the archive/.test(addUi.lbl2), JSON.stringify(addUi));
  await page.fill('#arcInput', '/storage/emulated/0/Download/extra.png');
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('    add appends the file name to a folder target:', ed[6].op === 'add' && ed[6].from === '/storage/emulated/0/Download/extra.png' && ed[6].to === 'assets/extra.png' && ed[6].overwrite === false, JSON.stringify(ed[6]));
  dialogAnswer = true;
  await page.evaluate(() => onArchiveResult({ ok: false, op: 'add', error: '"assets/extra.png" is already in the archive' })); await sleep(120);
  ed = await page.evaluate(() => window.__calls.edit.slice());
  console.log('    existing name: asks to replace, then retries with overwrite:', dialogs.some(d => /already in the archive\. Replace it\?/.test(d)) && ed[7] && ed[7].op === 'add' && ed[7].overwrite === true, ed.length);
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'add', message: 'Added' })); await sleep(120);
  // decline -> no retry
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcCard .batch-tool-link', { hasText: 'Add file' }).click(); await sleep(60);
  await page.fill('#arcInput', '/storage/emulated/0/Download/again.bin');
  await page.locator('#arcInputConfirm').click(); await sleep(60);
  dialogAnswer = false;
  const before = (await page.evaluate(() => window.__calls.edit.length));
  await page.evaluate(() => onArchiveResult({ ok: false, op: 'add', error: '"assets/again.bin" is already in the archive' })); await sleep(120);
  console.log('    declining the replace prompt does not retry:', (await page.evaluate(() => window.__calls.edit.length)) === before);
  dialogAnswer = true;

  // 11) Errors from the app are shown; a busy app is respected.
  await page.evaluate(() => { arcSetBusy(false); });
  await page.evaluate(() => arcRunEdit({ op: 'delete', name: 'x' })); await sleep(40);
  await page.evaluate(() => onArchiveResult({ ok: false, op: 'delete', error: 'Couldn\'t replace app.apk' })); await sleep(60);
  console.log('11. a failed job shows the reason in a toast:', /Couldn.t replace/.test(await toastText()));
  await page.evaluate(() => { document.getElementById('toast').classList.remove('show'); try { closeCommandResultsModal(); } catch (e) {} });
  await page.evaluate(() => { arcSetBusy(true, 'Working…'); });
  const editsBefore = await page.evaluate(() => window.__calls.edit.length);
  await page.evaluate(() => arcRunEdit({ op: 'delete', name: 'y' }));
  console.log('    a second job while one is running is refused:', (await page.evaluate(() => window.__calls.edit.length)) === editsBefore && /still running/.test(await toastText()));
  await page.evaluate(() => { arcSetBusy(false); window.__busy = true; });
  await page.evaluate(() => arcRunEdit({ op: 'delete', name: 'z' })); await sleep(60);
  console.log('    app says busy -> not left spinning:', (await page.evaluate(() => arcBusy)) === false);
  await page.evaluate(() => { window.__busy = false; });

  // 12) View-only archive: no editing UI at all.
  await page.evaluate(() => arcClose()); await sleep(80);
  await page.evaluate(() => { window.__arc.editable = false; window.__arc.whyNot = 'Installed and system packages can\'t be edited in place. Copy it to storage first.'; window.__arc.apk = true; });
  await page.evaluate(() => arcOpen('/data/app/~~x/com.foo-1/base.apk')); await sleep(250);
  const ro = await page.evaluate(() => ({ note: document.getElementById('arcNote').innerText, add: getComputedStyle(document.getElementById('arcAddBtn')).display, folder: getComputedStyle(document.getElementById('arcFolderBtn')).display }));
  console.log('12. view-only: reason shown, Add/Folder hidden:', /View only/.test(ro.note) && /system packages/.test(ro.note) && ro.add === 'none' && ro.folder === 'none', JSON.stringify(ro.note));
  await page.evaluate(() => arcGo('assets/')); await sleep(40);
  await page.locator('#arcList .perm-row', { hasText: 'config.json' }).locator('.perm-info').click(); await sleep(100);
  const roBtns = await page.locator('#arcModalBtns button').allInnerTexts();
  console.log('    view-only sheet: View + Extract only, text not editable:', roBtns.length === 2 && roBtns.some(t => /View/.test(t)) && roBtns.some(t => /Extract/.test(t)) && !roBtns.some(t => /Edit/.test(t)), JSON.stringify(roBtns));
  const editsRO = await page.evaluate(() => window.__calls.edit.length);
  await page.evaluate(() => arcRunEdit({ op: 'delete', name: 'q' }));
  console.log('    editing through the API is refused too:', (await page.evaluate(() => window.__calls.edit.length)) === editsRO);
  await page.evaluate(() => { arcCloseModal(); window.__arc.editable = true; window.__arc.whyNot = ''; });

  // 13) Not an archive -> toast and fall back to the text viewer.
  await page.evaluate(() => arcClose()); await sleep(60);
  const closeCount = await page.evaluate(() => window.__calls.close);
  console.log('13. closing tells the app to drop the archive, file list is back:', closeCount >= 2 && (await page.locator('#fmListCard').evaluate(e => getComputedStyle(e).display !== 'none')) && (await page.locator('#arcCard').evaluate(e => getComputedStyle(e).display === 'none')));
  await page.evaluate(() => fmActions('/storage/emulated/0/Download/fake.zip', false, 'fake.zip'));
  await page.locator('#fmActionBtns button', { hasText: 'View contents' }).click(); await sleep(250);
  const fb = await page.evaluate(() => ({ toast: document.getElementById('toastMsg').innerText, viewer: document.getElementById('fmViewer').innerText, shown: getComputedStyle(document.getElementById('fmViewer')).display !== 'none', reads: window.__calls.fmRead.slice(), card: getComputedStyle(document.getElementById('arcCard')).display }));
  console.log('    a ".zip" that is not an archive: error toast + plain text viewer, no archive card:', /Not a zip archive/.test(fb.toast) && fb.shown && fb.viewer === 'plain text of /storage/emulated/0/Download/fake.zip' && fb.card === 'none', JSON.stringify(fb.toast));

  // 14) Screenshot of the browser
  await page.evaluate(() => closeFmAction());
  await page.evaluate(() => { arcOpen('/storage/emulated/0/Download/app.apk'); }); await sleep(300);
  await page.screenshot({ path: OUT + '/arc_root.png' });
  await page.evaluate(() => arcGo('assets/')); await sleep(60);
  await page.locator('#arcList .perm-row', { hasText: 'config.json' }).locator('.perm-info').click(); await sleep(150);
  await page.screenshot({ path: OUT + '/arc_view.png' });

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
