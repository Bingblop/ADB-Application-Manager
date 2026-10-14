// File Manager, added storage (UI3-1): Share, Open with and View of a file in added storage (an SD card, USB, another app's folder) hand a copy
// in the app's cache to the one call that needs a path; the action sheet stays open, and its other actions (Edit and Save, Delete, Rename) still act
// on the file in added storage, not on the hidden cache copy. Also when an archive-named file turns out not to be an archive and is shown as text.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  let failed = 0;
  const check = (what, ok, info) => { console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok || info === undefined ? '' : ' :: ' + info)); if (!ok) failed++; };
  await page.addInitScript(() => {
    const st = window.__st = { staged: [], shared: [], openWith: [], reads: [], writes: [], ops: [], renames: [], fmRead: [] };
    const ROOT = 'content://com.android.externalstorage.documents/tree/1234-5678%3A';
    window.__URI = ROOT + '/document/1234-5678%3Anotes.txt';
    window.__ZIP = ROOT + '/document/1234-5678%3Aold.zip';
    const stagedPath = u => '/data/user/0/x/cache/saf_stage/abc_' + decodeURIComponent(u).split(':').pop();
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      fmStageSaf(u) { st.staged.push(u); setTimeout(() => window.onFmSafStaged({ ok: true, uri: u, path: stagedPath(u), name: '' }), 20); return 'started'; },
      shareStoredFile(p) { st.shared.push(p); return ''; },
      fmOpenWith(p) { st.openWith.push(p); },
      fmReadText(p) { st.reads.push(p); return JSON.stringify({ ok: true, text: 'hello', mtime: 5, writable: true }); },
      fmRead(p) { st.fmRead.push(p); return 'not really a zip'; },
      fmWriteText(p) { st.writes.push(p); return JSON.stringify({ ok: true, mtime: 6 }); },
      fmOp(op, a, bb) { st.ops.push([op, a, bb]); return JSON.stringify({ ok: true, output: 'OK' }); },
      fmRenameSaf(u, n) { st.renames.push([u, n]); return JSON.stringify({ ok: true, output: 'OK' }); },
      archiveOpen() { return JSON.stringify({ ok: false, error: 'Not a readable archive' }); },
      fmList(p) { return JSON.stringify({ path: p, isSaf: p.startsWith('content://'), entries: [], source: 'saf' }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = f => page.evaluate(f);
  const wait = ms => page.waitForTimeout(ms);
  const STAGE = /\/cache\/saf_stage\//;
  const URI = await ev(() => window.__URI), ZIP = await ev(() => window.__ZIP);

  // Share, then Edit and Save in the sheet that is still open
  await ev(() => { fmActions(window.__URI, false, 'notes.txt'); fmShareFile(); }); await wait(120);
  let s = await ev(() => ({ open: document.getElementById('fmActionModal').classList.contains('show'), target: fmActionTarget.full, shared: __st.shared.slice(), staged: __st.staged.slice() }));
  check('Share hands the cache copy to the share sheet', s.shared.length === 1 && STAGE.test(s.shared[0]) && s.staged.length === 1, JSON.stringify(s));
  check('the action sheet stays open and its target is still the file in added storage', s.open && s.target === URI, JSON.stringify(s));
  await ev(() => { fmEditOpen(); document.getElementById('fmEditArea').value = 'hello edited'; fmEditInput(); fmEditSave(); }); await wait(80);
  s = await ev(() => ({ reads: __st.reads.slice(), writes: __st.writes.slice() }));
  check('Edit reads and saves the file in added storage, not the cache copy', s.reads.length === 1 && s.reads[0] === s.writes[0] && s.writes.length === 1 && s.writes[0] === URI, JSON.stringify(s));

  // Share, then Delete
  await ev(() => { fmActions(window.__URI, false, 'notes.txt'); fmShareFile(); }); await wait(120);
  await ev(() => { fmStartDest('rm'); fmDestConfirm(); }); await wait(50);
  s = await ev(() => __st.ops.slice());
  check('Delete after Share removes the file in added storage', s.length === 1 && s[0][0] === 'rm' && s[0][1] === URI, JSON.stringify(s));
  check('and nothing in the cache folder is touched', !s.some(o => STAGE.test(o[1] || '')), JSON.stringify(s));

  // Open with, then Rename
  await ev(() => { fmActions(window.__URI, false, 'notes.txt'); fmOpenWith(); }); await wait(120);
  await ev(() => { fmStartDest('rename'); document.getElementById('fmDestInput').value = 'renamed.txt'; fmDestConfirm(); }); await wait(50);
  s = await ev(() => ({ openWith: __st.openWith.slice(), renames: __st.renames.slice(), ops: __st.ops.length }));
  check('Open with hands the cache copy to the chooser', s.openWith.length === 1 && STAGE.test(s.openWith[0]), JSON.stringify(s));
  check('Rename after Open with renames the file in added storage', s.renames.length === 1 && s.renames[0][0].startsWith('content://') && s.renames[0][1] === 'renamed.txt' && s.ops === 1, JSON.stringify(s));

  // View on a .zip in added storage that is not an archive: the cache copy is shown as text, and the sheet's Delete still means the original
  await ev(() => { fmActions(window.__ZIP, false, 'old.zip'); fmViewFile(); }); await wait(250);
  s = await ev(() => ({ open: document.getElementById('fmActionModal').classList.contains('show'), read: __st.fmRead.slice(), target: fmActionTarget.full, viewer: document.getElementById('fmViewer').innerText }));
  check('the cache copy of the not-an-archive file is shown as text', s.read.length === 1 && STAGE.test(s.read[0]) && /not really a zip/.test(s.viewer), JSON.stringify(s));
  check('and the sheet\'s target is the file in added storage again', s.target === ZIP, JSON.stringify(s));
  await ev(() => { fmStartDest('rm'); fmDestConfirm(); }); await wait(50);
  s = await ev(() => __st.ops.slice());
  check('Delete then removes the file in added storage', s.length === 2 && s[1][0] === 'rm' && s[1][1] === ZIP, JSON.stringify(s));

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
