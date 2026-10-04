// v7.2 File Manager: a ".." row at the top of every list, + File, Show hidden files, Show thumbnails (cached by the app), a rule for names that are taken
// (replace / skip / keep both) with Cancel for a running job, Open with and Share on any file, the text editor, and viewers for pictures, PDF and fonts.
// The app's side is a small virtual file system in the page (lib/fm_mock.js) that follows the same rules as src/.../FileOps.java.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const FONT_B64 = fs.readFileSync(path.join(__dirname, 'fixtures', 'Fixture-Boxes-Regular.ttf')).toString('base64');
const ROOT = '/storage/emulated/0';

const FILES = {
  [ROOT + '/Download']: null, [ROOT + '/Download/report.pdf']: { size: 5000, bin: true }, [ROOT + '/Download/hello.txt']: 'Hello\nWorld\n',
  [ROOT + '/Pictures']: null, [ROOT + '/Pictures/cat.jpg']: { size: 2000, bin: true }, [ROOT + '/Pictures/clip.mp4']: { size: 90000, bin: true }, [ROOT + '/Pictures/notes.txt']: 'pictures notes',
  [ROOT + '/Empty']: null, [ROOT + '/OnlyHidden/.secret']: 'x', [ROOT + '/.hidden_dir/in.txt']: 'in', [ROOT + '/.nomedia']: '',
  [ROOT + '/a.txt']: 'A at root', [ROOT + '/b.bin']: { size: 4096, bin: true }, [ROOT + '/big.log']: { size: 3 * 1024 * 1024, bin: true },
  [ROOT + '/Copy1/a.txt']: 'A in Copy1', [ROOT + '/Copy1/new.txt']: 'new in Copy1', [ROOT + '/Copy1/sub/deep.txt']: 'deep',
  [ROOT + '/Fonts']: null, [ROOT + '/Fonts/Box.ttf']: { size: 3000, bin: true },
  [ROOT + '/Locked']: null,
};

(async () => {
  const b = await chromium.launch();
  const open = async opts => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = [];
    page.on('pageerror', e => { errors.push(e.message); bad++; console.log('FAIL page error:', e.message); });
    page.__dialogs = [];
    page.on('dialog', d => { page.__dialogs.push(d.message()); (page.__accept ? d.accept() : d.dismiss()).catch(() => {}); });
    await page.addInitScript(fm.initScript, Object.assign({ files: FILES, fontB64: FONT_B64 }, opts || {}));
    await page.goto(PAGE);
    await page.waitForTimeout(300);
    await page.evaluate(() => switchView('files'));
    await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const names = page => ev(page, () => Array.from(document.querySelectorAll('#fmList .perm-row .perm-name')).map(n => n.innerText));
  const go = async (page, p) => { await ev(page, p => fmGo(p), p); await sleep(60); };
  const toast = page => ev(page, () => document.getElementById('toastMsg').innerText);
  const shown = (page, sel) => ev(page, s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none' && e.classList.contains('show') !== false; }, sel);
  const modalOpen = (page, id) => ev(page, id => document.getElementById(id).classList.contains('show'), id);

  // ---------------------------------------------------------------- 1) the ".." row
  let page = await open();
  await go(page, ROOT + '/Pictures');
  let n = await names(page);
  check('1. the first row of a list is ".." (a drawn folder and the name), then the folders, then the files', n[0] === '..' && n[1] === undefined ? false : n[0] === '..' && n.slice(1).join() === 'cat.jpg,clip.mp4,notes.txt'.split(',').join(), JSON.stringify(n));
  const up = await ev(page, () => { const r = document.getElementById('fmUpRow'); return { cls: r.className, folder: !!r.querySelector('.fm-folder'), text: r.querySelector('.perm-name').innerText, emoji: /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(r.innerText), h: Math.round(r.getBoundingClientRect().height), first: r === document.querySelector('#fmList').firstElementChild }; });
  check('   it is a row of its own at the very top with a CSS folder (no emoji), at least 44 px tall to tap', up.folder && up.text === '..' && !up.emoji && up.first && up.h >= 44, JSON.stringify(up));
  check('   it is not one of the files: it has no index, so Select all, the selection and a long press leave it out', await ev(page, () => document.querySelectorAll('#fmList .perm-row[data-i]').length === 3 && fmRows.length === 3));
  await page.click('#fmUpRow'); await sleep(80);
  check('   tapping it goes up one folder', (await ev(page, () => fmPath)) === ROOT && (await names(page))[0] === '..');
  await go(page, ROOT + '/Empty');
  n = await names(page);
  check('   an empty folder still shows it (and says it is empty)', n.join() === '..' && /Empty folder/.test(await ev(page, () => document.getElementById('fmList').innerText)), JSON.stringify(n));
  await go(page, ROOT + '/Missing');
  check('   a folder that cannot be listed still shows it, so there is always a way out', (await names(page))[0] === '..' && /No such folder/.test(await ev(page, () => document.getElementById('fmList').innerText)));
  await page.click('#fmUpRow'); await sleep(80);
  check('   ... and it works from there', (await ev(page, () => fmPath)) === ROOT);
  await go(page, '/');
  check('   at the root of the phone there is nothing above: no row', !(await ev(page, () => !!document.getElementById('fmUpRow'))));
  await go(page, ROOT + '/Pictures');
  await ev(page, () => fmUp()); await sleep(60);
  check('   the Up button is still there and does the same', (await ev(page, () => fmPath)) === ROOT);
  await page.close();

  // ---------------------------------------------------------------- 2) hidden files
  page = await open();
  n = await names(page);
  check('2. hidden files are not shown at the start (names that begin with a dot), and the list says how many', !n.some(x => x.startsWith('.') && x !== '..') && /2 hidden items not shown/.test(await ev(page, () => document.getElementById('fmList').innerText)), JSON.stringify(n));
  check('   the box "Show hidden files" is there and not ticked', (await ev(page, () => document.getElementById('fmHiddenChk').checked)) === false);
  await page.click('#fmHiddenChk'); await sleep(80);
  n = await names(page);
  check('   ticking it shows them at once (no new listing), dimmed', n.includes('.hidden_dir/') && n.includes('.nomedia') && (await ev(page, () => document.querySelectorAll('#fmList .fm-hidden-row').length)) === 2 && !/hidden items? not shown/.test(await ev(page, () => document.getElementById('fmList').innerText)), JSON.stringify(n));
  check('   the choice is kept (kv fm_opts)', await ev(page, () => JSON.parse(window.__kv ? window.__kv.fm_opts : '{}').hidden === true) || (await ev(page, () => window.__fm.calls.includes('saveSetting:fm_opts'))));
  await go(page, ROOT + '/OnlyHidden');
  check('   a folder with only hidden files says so, and shows them when asked', (await names(page)).join() === '..,.secret');
  await page.click('#fmHiddenChk'); await sleep(60);
  check('   unticked: "Only hidden files are here"', /Only hidden files are here/.test(await ev(page, () => document.getElementById('fmList').innerText)));
  // a new file called like a hidden one is refused even while hidden files are not shown
  await ev(page, () => fmNewFile()); await sleep(40);
  await ev(page, () => { document.getElementById('fmDestInput').value = '.secret'; fmDestConfirm(); }); await sleep(60);
  check('   a name taken by a hidden file counts as taken', /already used/.test(await toast(page)));
  await page.close();

  // ---------------------------------------------------------------- 3) + File
  page = await open();
  check('3. the toolbar has "＋ Folder" and "＋ File" side by side', await ev(page, () => { const t = Array.from(document.querySelectorAll('#fmTopCard .batch-sheet-tools button')).map(x => x.innerText.trim()); return t.indexOf('＋ File') === t.indexOf('＋ Folder') + 1 && t.indexOf('Up') >= 0; }));
  await ev(page, () => fmNewFile()); await sleep(40);
  check('   it asks for a name', (await modalOpen(page, 'fmActionModal')) && (await ev(page, () => document.getElementById('fmActionName').innerText)) === 'New file');
  for (const [bad1, why] of [['', /Enter a name/], ['a/b.txt', /slash/], ['..', /not allowed/], ['a.txt', /already used/]]) {
    await ev(page, v => { document.getElementById('fmDestInput').value = v; fmDestConfirm(); }, bad1); await sleep(40);
    check('   a bad name is refused with the reason: ' + JSON.stringify(bad1), why.test(await toast(page)) && !(await ev(page, () => window.__fm.calls.some(c => c.startsWith('fmOp:touch')))));
  }
  await ev(page, () => { document.getElementById('fmDestInput').value = 'todo.txt'; fmDestConfirm(); }); await sleep(120);
  check('   a good name creates an empty file and opens the editor on it', (await ev(page, () => window.__fm.fs['/storage/emulated/0/todo.txt'] && window.__fm.fs['/storage/emulated/0/todo.txt'].text === '')) && (await modalOpen(page, 'fmEditModal')) && (await ev(page, () => document.getElementById('fmEditName').innerText)) === 'todo.txt');
  await ev(page, () => { document.getElementById('fmEditArea').value = 'buy milk'; fmEditInput(); fmEditSave(); }); await sleep(60);
  check('   saving writes the text and shows Saved', (await ev(page, () => window.__fm.fs['/storage/emulated/0/todo.txt'].text)) === 'buy milk' && /Saved/.test(await ev(page, () => document.getElementById('fmEditStatus').innerText)));
  await ev(page, () => fmEditClose()); await sleep(40);
  check('   the new file is in the list', (await names(page)).includes('todo.txt'));
  await ev(page, () => fmNewFile()); await sleep(40);
  await ev(page, () => { document.getElementById('fmDestInput').value = 'photo.png'; fmDestConfirm(); }); await sleep(100);
  check('   a file whose ending is not text is created but the editor does not open on it', (await ev(page, () => window.__fm.fs['/storage/emulated/0/photo.png'] !== undefined)) && !(await modalOpen(page, 'fmEditModal')));
  await page.close();
  page = await open({ opFail: 'Read-only file system' });
  await ev(page, () => fmNewFile()); await sleep(40);
  await ev(page, () => { document.getElementById('fmDestInput').value = 'x.txt'; fmDestConfirm(); }); await sleep(60);
  check('   when the app cannot create it the toast says why', /Read-only file system/.test(await toast(page)) && !(await modalOpen(page, 'fmEditModal')));
  await page.close();

  // ---------------------------------------------------------------- 4) the editor
  page = await open();
  await ev(page, () => fmActions('/storage/emulated/0/a.txt', false, 'a.txt'));
  const btns = await ev(page, () => Array.from(document.querySelectorAll('#fmActionBtns button')).map(x => x.innerText));
  check('4. a text file offers View, Edit, Open with…, Share, Open as archive, Rename, Copy, Move, Delete', btns.join('|') === 'View|Edit|Open with…|Share|Open as archive|Rename|Copy|Move|Delete', btns.join('|'));
  await ev(page, () => fmEditOpen()); await sleep(40);
  const ed = await ev(page, () => ({ open: document.getElementById('fmEditModal').classList.contains('show'), name: document.getElementById('fmEditName').innerText, text: document.getElementById('fmEditArea').value, save: document.getElementById('fmEditSave').disabled, status: document.getElementById('fmEditStatus').innerText, pos: document.getElementById('fmEditPos').innerText }));
  check('   Edit opens the file in the editor: name, whole text, Save off until something changes, a status line', ed.open && ed.name === 'a.txt' && ed.text === 'A at root' && ed.save === true && /Saved · 9 characters/.test(ed.status) && /Line 1, column 1/.test(ed.pos), JSON.stringify(ed));
  await ev(page, () => { const t = document.getElementById('fmEditArea'); t.value = 'A at root\nsecond line'; t.dispatchEvent(new Event('input')); });
  check('   typing turns Save on and says there are unsaved changes', !(await ev(page, () => document.getElementById('fmEditSave').disabled)) && /Unsaved changes · 21 characters/.test(await ev(page, () => document.getElementById('fmEditStatus').innerText)));
  await ev(page, () => fmEditClose()); await sleep(40);
  check('   closing with unsaved changes asks first; saying no keeps the editor open', page.__dialogs.length === 1 && /without saving/.test(page.__dialogs[0]) && (await modalOpen(page, 'fmEditModal')));
  await ev(page, () => fmEditSave()); await sleep(40);
  const w = await ev(page, () => window.__fm.writes.slice(-1)[0]);
  check('   Save sends the text with the time the file had when it was opened', w.path === ROOT + '/a.txt' && w.text === 'A at root\nsecond line' && w.expect > 0, JSON.stringify(w));
  check('   after saving the editor closes without asking, and the file is changed', await ev(page, () => { fmEditClose(); return !document.getElementById('fmEditModal').classList.contains('show') && window.__fm.fs['/storage/emulated/0/a.txt'].text === 'A at root\nsecond line'; }) && page.__dialogs.length === 1);
  // the file changed on disk meanwhile
  await ev(page, () => fmActions('/storage/emulated/0/Download/hello.txt', false, 'hello.txt')); await ev(page, () => fmEditOpen()); await sleep(40);
  await ev(page, () => { window.__fm.changeOnDisk = true; const t = document.getElementById('fmEditArea'); t.value = 'mine'; t.dispatchEvent(new Event('input')); });
  page.__accept = false;
  await ev(page, () => fmEditSave()); await sleep(40);
  check('   a file changed by something else is not overwritten unasked: the editor asks, and "no" leaves it', /changed by something else/.test(page.__dialogs[page.__dialogs.length - 1]) && (await ev(page, () => window.__fm.fs['/storage/emulated/0/Download/hello.txt'].text)) === 'Hello\nWorld\n');
  page.__accept = true;
  await ev(page, () => fmEditSave()); await sleep(60);
  check('   "yes" saves it over', (await ev(page, () => window.__fm.fs['/storage/emulated/0/Download/hello.txt'].text)) === 'mine');
  await ev(page, () => fmEditClose()); await sleep(30);
  page.__accept = false;
  // not text, too big, read only, wrap
  await ev(page, () => { fmActions('/storage/emulated/0/b.bin', false, 'b.bin'); }); await ev(page, () => fmEditOpen()); await sleep(30);
  check('   a file that is not text is not opened for editing (a hint says what to use)', !(await modalOpen(page, 'fmEditModal')) && /not a text file/.test(await toast(page)));
  await ev(page, () => { fmActions('/storage/emulated/0/big.log', false, 'big.log'); }); await ev(page, () => fmEditOpen()); await sleep(30);
  check('   a file over 2 MB is refused with the reason', !(await modalOpen(page, 'fmEditModal')) && /over 2 MB/.test(await toast(page)));
  await page.close();
  page = await open({ readonly: true });
  await ev(page, () => fmActions('/storage/emulated/0/a.txt', false, 'a.txt')); await ev(page, () => fmEditOpen()); await sleep(30);
  check('   a file the app cannot write opens read only: the box cannot be typed in and Save stays off', await ev(page, () => document.getElementById('fmEditArea').readOnly && document.getElementById('fmEditSave').disabled && /Read only/.test(document.getElementById('fmEditStatus').innerText)));
  await ev(page, () => fmEditClose());
  await ev(page, () => { fmActions('/storage/emulated/0/Download/hello.txt', false, 'hello.txt'); fmEditOpen(); document.getElementById('fmEditWrap').click(); });
  check('   "Wrap lines" switches the box between long lines and wrapped ones', await ev(page, () => document.getElementById('fmEditArea').classList.contains('wrap')));
  await page.close();
  page = await open({ writeError: 'No space left on device' });
  await ev(page, () => { fmActions('/storage/emulated/0/a.txt', false, 'a.txt'); fmEditOpen(); const t = document.getElementById('fmEditArea'); t.value = 'x'; t.dispatchEvent(new Event('input')); fmEditSave(); }); await sleep(40);
  check('   a failed save says why and keeps the text and the editor', /No space left/.test(await toast(page)) && (await modalOpen(page, 'fmEditModal')) && (await ev(page, () => document.getElementById('fmEditArea').value)) === 'x' && /Unsaved/.test(await ev(page, () => document.getElementById('fmEditStatus').innerText)));
  await page.close();

  // ---------------------------------------------------------------- 5) names that are taken
  const prep = async (opts) => {
    const pg = await open(opts);
    await ev(pg, () => { fmGo('/storage/emulated/0'); fmSelEnter('/storage/emulated/0/a.txt'); fmSel.add('/storage/emulated/0/Copy1'); fmClipSet('cp'); });
    return pg;
  };
  const text = (pg, p) => ev(pg, p => { const n = window.__fm.fs[p]; return n ? (n.dir ? '<dir>' : n.text) : null; }, p);
  page = await prep();
  await go(page, ROOT + '/Download');
  await page.click('#fmPasteBtn'); await sleep(60);
  check('5. pasting into a folder where no name is taken starts at once, without asking, and never overwrites (rule keep)', !(await modalOpen(page, 'fmConflictModal')) && (await ev(page, () => window.__fm.batch && window.__fm.batch.policy)) === 'keep');
  await sleep(150);
  await go(page, ROOT + '/Pictures');
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt', '/storage/emulated/0/Copy1'] }; fmClipRender(); });
  // Copy1/a.txt vs a.txt: clash is by the name of what is pasted (a.txt exists in Pictures? no) -> use root with a.txt
  await go(page, ROOT + '/Copy1');
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt', '/storage/emulated/0/Pictures/notes.txt'] }; fmClipRender(); });
  await page.click('#fmPasteBtn'); await sleep(60);
  const ask = await ev(page, () => ({ open: document.getElementById('fmConflictModal').classList.contains('show'), title: document.getElementById('fmConflictTitle').innerText, sub: document.getElementById('fmConflictSub').innerText, btns: Array.from(document.querySelectorAll('#fmConflictModal .batch-grid-btn')).map(x => x.innerText) }));
  check('   a taken name opens a sheet: what is taken, and Replace / Skip / Keep both; nothing is sent yet', ask.open && ask.btns.join() === 'Replace,Skip,Keep both' && /1 of 2/.test(ask.sub) && /a\.txt/.test(ask.sub) && (await ev(page, () => !window.__fm.batch || window.__fm.batch.dest !== '/storage/emulated/0/Copy1')), JSON.stringify(ask));
  await ev(page, () => fmConflictCancel()); await sleep(30);
  check('   closing the sheet cancels the paste and keeps the clipboard', !(await modalOpen(page, 'fmConflictModal')) && (await ev(page, () => !!fmClip)));
  await page.click('#fmPasteBtn'); await sleep(40);
  await ev(page, () => fmConflictChoose('replace')); await sleep(200);
  check('   Replace puts the new file over the old one', (await text(page, ROOT + '/Copy1/a.txt')) === 'A at root\nsecond line'.slice(0, 0) + 'A at root' && (await text(page, ROOT + '/Copy1/notes.txt')) === 'pictures notes' && /Copied 2 of 2/.test(await toast(page)), await toast(page));
  await page.close();

  page = await prep();
  await go(page, ROOT + '/Copy1');
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt', '/storage/emulated/0/Pictures/notes.txt'] }; fmClipRender(); });
  await page.click('#fmPasteBtn'); await sleep(40);
  await ev(page, () => fmConflictChoose('skip')); await sleep(200);
  check('   Skip leaves the old file and still copies the others; the toast counts what was skipped', (await text(page, ROOT + '/Copy1/a.txt')) === 'A in Copy1' && (await text(page, ROOT + '/Copy1/notes.txt')) === 'pictures notes' && /Copied 1 of 2 • 1 skipped/.test(await toast(page)), await toast(page));
  await page.close();

  page = await prep();
  await go(page, ROOT + '/Copy1');
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt', '/storage/emulated/0/Copy1/sub'] }; fmClipRender(); });
  await page.click('#fmPasteBtn'); await sleep(40);
  await ev(page, () => fmConflictChoose('keep')); await sleep(200);
  check('   Keep both adds the new one as "a (1).txt" and keeps the old one', (await text(page, ROOT + '/Copy1/a.txt')) === 'A in Copy1' && (await text(page, ROOT + '/Copy1/a (1).txt')) === 'A at root', await toast(page));
  check('   a folder is kept as "sub (1)" with what is in it', (await text(page, ROOT + '/Copy1/sub (1)/deep.txt')) === 'deep');
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt'] }; fmClipRender(); fmPaste(); }); await sleep(40);
  await ev(page, () => fmConflictChoose('keep')); await sleep(200);
  check('   again: "a (2).txt"', (await text(page, ROOT + '/Copy1/a (2).txt')) === 'A at root');
  await page.close();

  // a copy into the folder it came from is a duplicate; a move there does nothing
  page = await open();
  await go(page, ROOT);
  await ev(page, () => { fmClip = { op: 'cp', paths: ['/storage/emulated/0/a.txt'] }; fmClipRender(); fmPaste(); }); await sleep(40);
  check('   a copy pasted into its own folder asks, and Keep both makes the duplicate', await modalOpen(page, 'fmConflictModal'));
  await ev(page, () => fmConflictChoose('keep')); await sleep(200);
  check('   "a (1).txt" next to a.txt', (await text(page, ROOT + '/a (1).txt')) === 'A at root' && (await text(page, ROOT + '/a.txt')) === 'A at root');
  await ev(page, () => { fmClip = { op: 'mv', paths: ['/storage/emulated/0/a.txt'] }; fmClipRender(); fmPaste(); }); await sleep(40);
  check('   a move into its own folder is refused', !(await modalOpen(page, 'fmConflictModal')) && /already in this folder/.test(await toast(page)));
  await page.close();

  // move with a taken name, and a job that is stopped
  page = await open({ hold: true });
  await go(page, ROOT + '/Copy1');
  await ev(page, () => { fmClip = { op: 'mv', paths: ['/storage/emulated/0/Pictures/notes.txt', '/storage/emulated/0/b.bin'] }; fmClipRender(); fmPaste(); }); await sleep(40);
  check('   a running job shows its progress and a Cancel button', (await ev(page, () => document.getElementById('fmBatchCancelBtn') !== null)) && /Copying/.test(await ev(page, () => document.getElementById('fmBatchText').innerText)));
  await page.click('#fmBatchCancelBtn'); await sleep(120);
  check('   Cancel tells the app to stop; the toast says it was stopped', (await ev(page, () => window.__fm.cancels)) === 1 && /stopped/.test(await toast(page)));
  await page.close();

  // ---------------------------------------------------------------- 6) Open with and Share
  page = await open({ openWithError: 'No app on this phone can open this kind of file.' });
  await ev(page, () => fmActions('/storage/emulated/0/Pictures/clip.mp4', false, 'clip.mp4'));
  const vb = await ev(page, () => Array.from(document.querySelectorAll('#fmActionBtns button')).map(x => x.innerText));
  check('6. every file has "Open with…" and "Share" (a video: View hands it to another app)', vb.includes('Open with…') && vb.includes('Share') && !vb.includes('Edit'), vb.join('|'));
  await ev(page, () => fmOpenWith()); await sleep(80);
  const ow = await ev(page, () => window.__fm.opened.slice(-1)[0]);
  check('   Open with… asks the app to hand over that file, with the chooser', ow && ow.path === ROOT + '/Pictures/clip.mp4' && ow.chooser === true, JSON.stringify(ow));
  check('   when no app can open it the toast says so', /No app on this phone/.test(await toast(page)));
  await ev(page, () => { fmActions('/storage/emulated/0/Pictures/clip.mp4', false, 'clip.mp4'); fmViewFile(); }); await sleep(30);
  check('   View on a video or sound opens the chooser', (await ev(page, () => window.__fm.opened.length)) === 2);
  await ev(page, () => { fmActions('/storage/emulated/0/b.bin', false, 'b.bin'); fmShareFile(); });
  check('   Share sends that file', await ev(page, () => window.__fm.calls.includes('share:/storage/emulated/0/b.bin')));
  await ev(page, () => fmActions('/storage/emulated/0/Pictures', true, 'Pictures'));
  check('   a folder has neither Open with… nor Share', !(await ev(page, () => Array.from(document.querySelectorAll('#fmActionBtns button')).some(x => /Open with|Share/.test(x.innerText)))));
  await page.close();

  // ---------------------------------------------------------------- 7) viewers
  page = await open();
  await ev(page, () => { fmActions('/storage/emulated/0/Pictures/cat.jpg', false, 'cat.jpg'); fmViewFile(); }); await sleep(80);
  const im = await ev(page, () => ({ open: document.getElementById('fmMediaModal').classList.contains('show'), name: document.getElementById('fmMediaName').innerText, meta: document.getElementById('fmMediaMeta').innerText, img: !!document.querySelector('#fmMediaStage img'), tools: Array.from(document.querySelectorAll('#fmMediaBar button')).map(x => x.innerText) }));
  check('7. View on a picture opens the viewer: name, the size of the picture, the picture, Fit / actual size and Open with…', im.open && im.name === 'cat.jpg' && im.meta === '640 × 480' && im.img && im.tools.join() === 'Fit / actual size,Open with…', JSON.stringify(im));
  await ev(page, () => fmMediaToggleSize());
  check('   Fit / actual size switches the picture between fitted and full size', await ev(page, () => document.getElementById('fmMediaStage').classList.contains('actual')));
  await ev(page, () => fmMediaClose());
  check('   closing the viewer empties it', !(await modalOpen(page, 'fmMediaModal')) && (await ev(page, () => document.getElementById('fmMediaStage').innerHTML)) === '');
  await ev(page, () => { fmActions('/storage/emulated/0/Download/report.pdf', false, 'report.pdf'); fmViewFile(); }); await sleep(80);
  let pdf = await ev(page, () => ({ pos: document.getElementById('fmPdfPos').innerText, prev: document.getElementById('fmPdfPrev').disabled, next: document.getElementById('fmPdfNext').disabled, img: !!document.querySelector('#fmMediaStage img') }));
  check('   View on a PDF shows page 1 of 3 with Next on and Previous off', pdf.pos === '1 / 3' && pdf.prev && !pdf.next && pdf.img, JSON.stringify(pdf));
  await ev(page, () => fmPdfGo(1)); await sleep(60); await ev(page, () => fmPdfGo(1)); await sleep(60);
  pdf = await ev(page, () => ({ pos: document.getElementById('fmPdfPos').innerText, prev: document.getElementById('fmPdfPrev').disabled, next: document.getElementById('fmPdfNext').disabled }));
  check('   Next twice: 3 of 3, Next off, Previous on', pdf.pos === '3 / 3' && !pdf.prev && pdf.next, JSON.stringify(pdf));
  await ev(page, () => fmPdfGo(1)); await sleep(40);
  check('   past the last page nothing is asked', (await ev(page, () => window.__fm.calls.filter(c => c.startsWith('fmPdf:')).length)) === 3);
  await ev(page, () => fmMediaClose());
  await ev(page, () => { fmActions('/storage/emulated/0/Fonts/Box.ttf', false, 'Box.ttf'); fmViewFile(); }); await sleep(250);
  const fo = await ev(page, () => ({ face: Array.from(document.fonts).some(f => f.family.replace(/"/g, '') === 'FmSample' && f.status === 'loaded'), sample: !!document.querySelector('#fmMediaStage .font-sample'), fam: getComputedStyle(document.querySelector('#fmMediaStage .fs-big')).fontFamily }));
  check('   View on a font file shows a sample set in that font (and the sample line really uses it)', fo.face && fo.sample && /FmSample/.test(fo.fam), JSON.stringify(fo));
  await ev(page, () => fmMediaClose());
  check('   closing the viewer removes the sample font from memory', !(await ev(page, () => Array.from(document.fonts).some(f => f.family.replace(/"/g, '') === 'FmSample'))));
  await page.close();
  page = await open({ imageOk: false, pdfError: 'This PDF is protected or could not be opened.' });
  await ev(page, () => { fmActions('/storage/emulated/0/Pictures/cat.jpg', false, 'cat.jpg'); fmViewFile(); }); await sleep(80);
  check('   a picture that cannot be decoded says so and points to Open with…', /could not be shown here/.test(await ev(page, () => document.getElementById('fmMediaStage').innerText)));
  await ev(page, () => { fmMediaClose(); fmActions('/storage/emulated/0/Download/report.pdf', false, 'report.pdf'); fmViewFile(); }); await sleep(80);
  check('   a PDF that cannot be opened says why', /protected or could not be opened/.test(await ev(page, () => document.getElementById('fmMediaStage').innerText)));
  await page.close();
  page = await open({ fontB64: '' });
  await ev(page, () => { fmActions('/storage/emulated/0/Fonts/Box.ttf', false, 'Box.ttf'); fmViewFile(); }); await sleep(60);
  check('   a font the app cannot read says so, with no empty viewer', !(await modalOpen(page, 'fmMediaModal')) && /could not be read/.test(await toast(page)));
  await page.close();

  // ---------------------------------------------------------------- 8) thumbnails
  page = await open();
  await go(page, ROOT + '/Pictures'); await sleep(250);
  const th = await ev(page, () => ({ reqs: window.__fm.thumbReqs.map(r => r.paths).flat(), imgs: Array.from(document.querySelectorAll('#fmList .fm-ico img')).length, px: window.__fm.thumbReqs[0] && window.__fm.thumbReqs[0].px }));
  check('8. a folder of pictures asks the app for the small pictures of its images and videos only (96 px), and shows them in place of the page drawing', th.reqs.sort().join() === [ROOT + '/Pictures/cat.jpg', ROOT + '/Pictures/clip.mp4'].join() && th.imgs === 2 && th.px === 96, JSON.stringify(th));
  check('   a text file keeps its drawn page', await ev(page, () => { const r = Array.from(document.querySelectorAll('#fmList .perm-row[data-i]')).find(x => x.innerText.indexOf('notes.txt') >= 0); return !!r.querySelector('.fm-page') && !r.querySelector('img'); }));
  await ev(page, () => { window.onFmThumb('/storage/emulated/0/Pictures/cat.jpg', 'javascript:alert(1)'); window.onFmThumb('/storage/emulated/0/Pictures/notes.txt', 'data:image/jpeg;base64,AAAA'); });
  check('   an answer that is not a plain picture address is ignored (no script from a thumbnail)', await ev(page, () => window.__pwned === undefined) && (await ev(page, () => document.querySelectorAll('#fmList .fm-ico img').length)) >= 2);
  await page.click('#fmThumbChk'); await sleep(100);
  const before = await ev(page, () => window.__fm.thumbReqs.length);
  check('   "Show thumbnails" off: the drawn pages come back and the app is not asked', (await ev(page, () => document.querySelectorAll('#fmList .fm-ico img').length)) === 0 && (await ev(page, () => window.__fm.thumbReqs.length)) === before);
  await go(page, ROOT + '/Pictures'); await sleep(200);
  check('   ... also for the next folder you open', (await ev(page, () => window.__fm.thumbReqs.length)) === before);
  await page.click('#fmThumbChk'); await sleep(250);
  check('   switched on again, the pictures come back', (await ev(page, () => window.__fm.thumbReqs.length)) > before && (await ev(page, () => document.querySelectorAll('#fmList .fm-ico img').length)) === 2);
  await ev(page, () => fmThumbClear()); await sleep(40);
  check('   "Clear thumbnail cache" empties the cache and says how much was freed', /Thumbnail cache cleared \(3\.0 MB\)/.test(await toast(page)) && (await ev(page, () => window.__fm.calls.includes('fmThumbCache:clear'))));
  await page.close();

  // ---------------------------------------------------------------- 9) no working mode needed
  page = await open({ priv: false });
  await ev(page, () => { fmSelEnter('/storage/emulated/0/a.txt'); });
  await ev(page, () => fmDeleteSelected()); await sleep(30);
  check('9. with the All-files access and no working mode, Delete is asked (no "working mode required" sheet)', !(await modalOpen(page, 'privilegeModal')) && page.__dialogs.length >= 1 && /Delete 1 item/.test(page.__dialogs[page.__dialogs.length - 1]));
  await ev(page, () => fmNewFolder()); await sleep(30);
  check('   ＋ Folder works the same way', !(await modalOpen(page, 'privilegeModal')) && (await modalOpen(page, 'fmActionModal')));
  await page.close();
  page = await open({ priv: false, access: false });
  await ev(page, () => fmNewFolder()); await sleep(30);
  check('   with neither, the sheet that explains what is needed opens', await modalOpen(page, 'privilegeModal'));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
