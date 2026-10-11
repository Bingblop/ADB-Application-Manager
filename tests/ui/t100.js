// File Manager: "Add storage" via a SAF document-tree picker (an SD card, USB drive, another app's folder...).
// Browsing, Up (no parent lookup in SAF, so a breadcrumb stack is kept), new folder/file, rename, delete and
// removing a root all work without any working mode or All-files access; copy/move/compress/open-with/share stay
// hidden for now (still POSIX-path-only), and multi-select is refused with a clear toast.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = []; page.on('dialog', async d => { dialogs.push(d.message()); await d.accept(); });
  await page.addInitScript(() => {
    window.__calls = { fmList: [], fmNewSaf: [], fmRenameSaf: [], fmOp: [], fmReadText: [], pickAddStorage: 0, removeStorageRoot: [] };
    const store = {};
    // A tiny mock SAF tree: content://mock/<rootId>/<docId>, opaque strings round-tripped exactly as the real
    // DocumentsContract URIs are - the page never parses them, only passes them back.
    const mkUri = (rootId, docId) => 'content://mock/' + rootId + '/' + docId;
    const parseUri = u => { const m = /^content:\/\/mock\/([^/]+)\/(.+)$/.exec(u); return m ? { rootId: m[1], docId: m[2] } : null; };
    window.__saf = { root1: {
      root: { name: 'SD Card', dir: true, parent: null },
      f1: { name: 'Documents', dir: true, parent: 'root' },
      f2: { name: 'photo.jpg', dir: false, size: 2048, parent: 'root' },
      f3: { name: 'notes.txt', dir: false, size: 12, parent: 'f1' },
    } };
    let seq = 0;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, loadPackages() { return '[]'; },
      hasAllFilesAccess() { return false; },        // no All-files access and no working mode: SAF must not need either
      getWorkingMode() { return JSON.stringify({ activeMode: 'standard', modeAvailable: false, isPrivileged: false }); },
      loadStore(k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
      saveStore(k, v) { store[k] = v; return true; },
      fmList(path) {
        window.__calls.fmList.push(path);
        if (!path.startsWith('content://')) return JSON.stringify({ path, entries: [] });
        const { rootId, docId } = parseUri(path);
        const tree = window.__saf[rootId] || {};
        const entries = Object.keys(tree).filter(k => tree[k].parent === docId).map(k => ({
          name: tree[k].name, isDir: !!tree[k].dir, isLink: false, size: tree[k].size || 0, lastModified: 0, uri: mkUri(rootId, k),
        }));
        return JSON.stringify({ path, isSaf: true, entries, source: 'saf' });
      },
      fmNewSaf(parentUri, name, type) {
        window.__calls.fmNewSaf.push([parentUri, name, type]);
        const { rootId, docId } = parseUri(parentUri);
        const id = 'n' + (++seq);
        window.__saf[rootId][id] = { name, dir: type === 'dir', size: 0, parent: docId };
        return JSON.stringify({ ok: true, output: 'OK', uri: mkUri(rootId, id) });
      },
      fmRenameSaf(uri, newName) {
        window.__calls.fmRenameSaf.push([uri, newName]);
        const { rootId, docId } = parseUri(uri);
        if (window.__saf[rootId] && window.__saf[rootId][docId]) window.__saf[rootId][docId].name = newName;
        return JSON.stringify({ ok: true, output: 'OK' });
      },
      fmOp(op, a, b) {
        window.__calls.fmOp.push([op, a, b]);
        if (op === 'rm' && a.startsWith('content://')) {
          const { rootId, docId } = parseUri(a);
          if (window.__saf[rootId]) delete window.__saf[rootId][docId];
          return JSON.stringify({ ok: true, output: 'OK' });
        }
        return JSON.stringify({ ok: false, output: 'not used by this test' });
      },
      fmReadText(path) { window.__calls.fmReadText.push(path); return JSON.stringify({ ok: true, text: '', mtime: 0, writable: true }); },
      pickAddStorage() { window.__calls.pickAddStorage++; },
      removeStorageRoot(uri) { window.__calls.removeStorageRoot.push(uri); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const toastText = () => page.locator('#toastMsg').innerText();
  const rowNames = async () => (await page.locator('#fmList .perm-row:not(.fm-up-row) .perm-name').allInnerTexts()).map(s => s.replace(/\/$/, ''));
  const rootBtns = () => page.locator('#fmRootsRow button').allInnerTexts();

  await page.evaluate(() => switchView('files')); await sleep(200);

  // 1) No roots yet: just the Add storage button.
  console.log('1. roots row starts with only "Add storage":', JSON.stringify(await rootBtns()) === JSON.stringify(['＋ Add storage']));

  // 2) Tapping it calls the native picker; the test then plays the picker's result itself (as the real app's
  // onActivityResult would), the way other async-native flows are simulated in these scripts.
  await page.click('#fmRootsRow button'); await sleep(50);
  console.log('2. Add storage calls the native picker:', (await page.evaluate(() => window.__calls.pickAddStorage)) === 1);
  await page.evaluate(() => window.onStorageRootPicked('content://mock/root1/root', 'SD Card')); await sleep(150);
  const afterPick = await rootBtns();
  console.log('   picking a root adds it to the row and navigates into it:', afterPick[0] === 'SD Card' && afterPick[2] === '＋ Add storage', JSON.stringify(afterPick));
  console.log('   root listing shows its entries:', JSON.stringify((await rowNames()).sort()) === JSON.stringify(['Documents', 'photo.jpg']));
  const persisted = await page.evaluate(() => JSON.parse(window.AndroidBridge.loadStore('fm_saf_roots')));
  console.log('   the root is persisted (uri + label):', persisted.length === 1 && persisted[0].uri === 'content://mock/root1/root' && persisted[0].label === 'SD Card', JSON.stringify(persisted));

  // 3) Navigate into a folder, then Up (SAF has no parent lookup - a breadcrumb stack gets this right).
  await page.locator('#fmList .perm-row', { hasText: 'Documents' }).locator('.perm-info').click(); await sleep(100);
  console.log('3. descending into Documents lists its own entries:', JSON.stringify(await rowNames()) === JSON.stringify(['notes.txt']));
  await page.click('#fmUpRow'); await sleep(100);
  console.log('   Up returns to the root listing:', JSON.stringify((await rowNames()).sort()) === JSON.stringify(['Documents', 'photo.jpg']));
  await page.evaluate(() => fmUp()); await sleep(80);
  console.log('   Up again at the top of this storage does not error, just says so:', /top of this storage/.test(await toastText()));

  // 4) New folder and new file reach the SAF create method (not the POSIX mkdir/touch path) - with no working
  // mode and no All-files access, proving SAF goes through its own grant instead of being blocked by fmCanWork.
  await page.click('button[onclick="fmNewFolder()"]'); await sleep(60);
  await page.fill('#fmDestInput', 'Pics');
  await page.click('#fmDestConfirm'); await sleep(100);
  let nf = await page.evaluate(() => window.__calls.fmNewSaf.slice());
  console.log('4. New Folder calls fmNewSaf(parentUri, name, "dir"):', nf.length === 1 && nf[0][0] === 'content://mock/root1/root' && nf[0][1] === 'Pics' && nf[0][2] === 'dir', JSON.stringify(nf));
  console.log('   not blocked by the "needs a working mode" prompt:', !(await page.locator('#privilegeModal').evaluate(e => e.classList.contains('show'))));
  console.log('   the new folder shows up:', (await rowNames()).includes('Pics'));
  await page.click('button[onclick="fmNewFile()"]'); await sleep(60);
  await page.fill('#fmDestInput', 'todo.txt');
  await page.click('#fmDestConfirm'); await sleep(100);
  nf = await page.evaluate(() => window.__calls.fmNewSaf.slice());
  console.log('   New File calls fmNewSaf(parentUri, name, "file"):', nf[1][1] === 'todo.txt' && nf[1][2] === 'file', JSON.stringify(nf[1]));
  const readCalls = await page.evaluate(() => window.__calls.fmReadText.slice());
  console.log('   a text file opens the editor right away, using the uri fmNewSaf handed back:', readCalls.length === 1 && /^content:\/\/mock\/root1\/n/.test(readCalls[0]) && await page.locator('#fmEditModal').evaluate(e => e.classList.contains('show')));
  await page.evaluate(() => { fmEd = null; document.getElementById('fmEditModal').classList.remove('show'); }); await sleep(60);

  // 5) The action sheet on a SAF text file: View + Edit + Rename + Delete, but not Copy/Move/Compress/Open with/Share.
  await page.locator('#fmList .perm-row', { hasText: 'todo.txt' }).locator('.perm-toggle-btn').click(); await sleep(60);
  const btns = await page.locator('#fmActionBtns button').allInnerTexts();
  console.log('5. SAF file sheet offers View, Edit, Rename, Delete:', ['View', 'Edit', 'Rename', 'Delete'].every(w => btns.some(t => t.includes(w))), JSON.stringify(btns));
  console.log('   offers Copy and Move (through the clipboard), Open with and Share, but not Compress:', ['Copy', 'Move', 'Open with', 'Share'].every(w => btns.some(t => t.includes(w))) && !btns.some(t => t.includes('Compress')));

  // 6) Rename goes through fmRenameSaf with a bare new name, not fmOp.
  await page.locator('#fmActionBtns button', { hasText: 'Rename' }).click(); await sleep(60);
  const prefill = await page.locator('#fmDestInput').inputValue();
  await page.fill('#fmDestInput', 'todo-list.txt');
  await page.click('#fmDestConfirm'); await sleep(100);
  const rn = await page.evaluate(() => window.__calls.fmRenameSaf.slice());
  console.log('6. Rename prefilled with the bare name, calls fmRenameSaf(uri, newName):', prefill === 'todo.txt' && rn.length === 1 && rn[0][1] === 'todo-list.txt', JSON.stringify(rn));
  console.log('   the renamed item shows up:', (await rowNames()).includes('todo-list.txt'));
  console.log('   fmOp was never called for a rename:', (await page.evaluate(() => window.__calls.fmOp.length)) === 0);

  // 7) Delete goes through the regular fmOp("rm", uri, null) path (fmOp already branches on content://).
  await page.locator('#fmList .perm-row', { hasText: 'Pics' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#fmActionBtns button', { hasText: 'Delete' }).click(); await sleep(60);
  await page.click('#fmDestConfirm'); await sleep(100);
  const del = await page.evaluate(() => window.__calls.fmOp.slice());
  console.log('7. Delete calls fmOp("rm", uri, null):', del.length === 1 && del[0][0] === 'rm' && del[0][1].startsWith('content://') && del[0][2] === null, JSON.stringify(del));
  console.log('   the deleted folder is gone:', !(await rowNames()).includes('Pics'));

  // 8) Copy and Move: the item goes on the clipboard, Paste in the open folder sends content:// uris to fmBatch2 (the app copies / moves them one by one).
  await page.evaluate(() => { window.__batch = []; window.AndroidBridge.fmBatch2 = (op, paths, dest, policy) => { window.__batch.push([op, JSON.parse(paths), dest, policy]); return 'started'; }; window.AndroidBridge.fmBatch = window.AndroidBridge.fmBatch2; });
  await page.locator('#fmList .perm-row', { hasText: 'todo-list.txt' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#fmActionBtns button', { hasText: 'Copy' }).click(); await sleep(80);
  console.log('8. Copy puts the item on the clipboard and shows the Paste bar:', await page.evaluate(() => fmClip && fmClip.op === 'cp' && fmClip.paths.length === 1 && fmClip.paths[0].startsWith('content://')), await page.locator('#fmClipBar').evaluate(e => e.style.display !== 'none'));
  await page.locator('#fmList .perm-row', { hasText: 'Documents' }).click(); await sleep(150);
  await page.click('#fmPasteBtn'); await sleep(100);
  const bt = await page.evaluate(() => window.__batch.slice());
  console.log('   Paste calls fmBatch2("cp", [uri], <this folder uri>) with the uri untouched:', bt.length === 1 && bt[0][0] === 'cp' && bt[0][1][0].startsWith('content://mock/root1/') && bt[0][2].startsWith('content://mock/root1/') && !/[^:]\/\//.test(bt[0][2]), JSON.stringify(bt));
  await page.evaluate(() => { fmBatchRunning = false; fmClip = null; fmClipRender(); });
  await page.click('#fmUpRow'); await sleep(100);

  // Multi-select now works in added storage, and offers Copy / Move / Delete but not Compress.
  await page.click('#fmSelectBtn'); await sleep(60);
  console.log('   Select turns selection mode on in added storage:', await page.locator('#fmSelBar').evaluate(e => getComputedStyle(e).display !== 'none'), '| Compress hidden:', await page.locator('#fmSelCompressBtn').evaluate(e => getComputedStyle(e).display === 'none'));
  await page.click('#fmSelectBtn'); await sleep(60);

  // Open with and Share work on a copy in the app's cache: the page asks for it, then hands the copy's path to the usual call.
  await page.evaluate(() => {
    window.__stage = []; window.__with = []; window.__share = [];
    window.AndroidBridge.fmStageSaf = uri => { window.__stage.push(uri); setTimeout(() => window.onFmSafStaged({ ok: true, uri, path: '/data/cache/saf_stage/x_todo-list.txt', name: 'todo-list.txt' }), 20); return 'started'; };
    window.AndroidBridge.fmOpenWith = (p, m, c) => { window.__with.push(p); return 'started'; };
    window.AndroidBridge.shareStoredFile = (p, m, n) => { window.__share.push(p); return ''; };
  });
  await page.locator('#fmList .perm-row', { hasText: 'todo-list.txt' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#fmActionBtns button', { hasText: 'Open with' }).click(); await sleep(150);
  const stg = await page.evaluate(() => [window.__stage.slice(), window.__with.slice()]);
  console.log('   Open with copies the file to the cache first, then opens that copy:', stg[0].length === 1 && stg[0][0].startsWith('content://') && stg[1][0] === '/data/cache/saf_stage/x_todo-list.txt', JSON.stringify(stg));
  await page.evaluate(() => closeFmAction()); await sleep(60);
  await page.locator('#fmList .perm-row', { hasText: 'todo-list.txt' }).locator('.perm-toggle-btn').click(); await sleep(60);
  await page.locator('#fmActionBtns button', { hasText: 'Share' }).click(); await sleep(150);
  console.log('   Share does the same:', JSON.stringify(await page.evaluate(() => window.__share)) === JSON.stringify(['/data/cache/saf_stage/x_todo-list.txt']));
  await page.evaluate(() => closeFmAction());

  // Search in added storage needs no All-files access and sends the folder's uri.
  await page.evaluate(() => { window.__search = []; window.AndroidBridge.fmSearchPlaces = () => '[]'; window.AndroidBridge.fmSearch = (q, roots, n, a, h) => { window.__search.push([q, JSON.parse(roots)]); return 'started'; }; fmSearchInit(); });
  await page.fill('#fmSearchInput', 'todo'); await page.click('#fmSearchBtn'); await sleep(100);
  const sr = await page.evaluate(() => window.__search.slice());
  console.log('   Search here in added storage runs with the folder uri (no All-files prompt):', sr.length === 1 && sr[0][0] === 'todo' && sr[0][1][0].startsWith('content://mock/root1/'), JSON.stringify(sr), '| prompt shown:', await page.locator('#privilegeModal').evaluate(e => e.classList.contains('show')));
  await page.evaluate(() => { fmSearchRunning = false; fmSearchExit(); });

  // 9) Removing a root releases it natively, drops it from the row and the persisted list, and backs out to storage.
  await page.click('#fmRootsRow button:has-text("✕")'); await sleep(150);
  const removed = await page.evaluate(() => window.__calls.removeStorageRoot.slice());
  console.log('9. removing the root calls removeStorageRoot(uri):', removed.length === 1 && removed[0] === 'content://mock/root1/root', JSON.stringify(removed));
  console.log('   the confirm names the root:', dialogs.some(d => /SD Card/.test(d)));
  console.log('   gone from the row and the persisted list:', JSON.stringify(await rootBtns()) === JSON.stringify(['＋ Add storage']) && JSON.parse(await page.evaluate(() => window.AndroidBridge.loadStore('fm_saf_roots'))).length === 0);
  console.log('   backed out to internal storage:', await page.evaluate(() => fmPath) === '/storage/emulated/0');

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
