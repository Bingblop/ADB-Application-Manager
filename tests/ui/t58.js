// v5.8 final front-end review fixes: compare edit target, terminal row width, failure sheet on top, store search failure note, terminal busy / other tab,
// compare / nested state, late sign result, bridge throwing, logcat clear, extract default folder, long-press touch, history filter box.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.accept(); });
  await page.addInitScript(() => {
    window.__calls = { edit: [], stage: [], release: [], sign: [], logcat: [], read: [] };
    const T = Date.UTC(2024, 0, 8);
    const ent = (n, extra) => Object.assign({ n, p: n, d: false, s: 5, c: 5, t: T, m: 8, e: false }, extra || {});
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      hasAllFilesAccess() { return true; }, loadSetting(k) { try { return localStorage.getItem('t58_' + k) || ''; } catch (e) { return ''; } }, saveSetting(k, v) { try { localStorage.setItem('t58_' + k, v); } catch (e) {} }, copyToClipboard() {},
      fmList(path) { return JSON.stringify({ path, entries: [{ name: 'one.txt', isDir: false, size: 3, perms: '-rw-' }, { name: 'two.txt', isDir: false, size: 4, perms: '-rw-' }, { name: 'three.txt', isDir: false, size: 5, perms: '-rw-' }] }); },
      archiveOpen(path) { return JSON.stringify({ ok: true, path, name: path.split('/').pop(), count: 2, files: 2, size: 100, zip64: false, staged: false, apk: /\.apk$/.test(path), editable: !/nested/.test(path), whyNot: '' }); },
      archiveList(path, dir, q, off, lim) { return JSON.stringify({ ok: true, dir, query: q, total: 2, offset: 0, more: false, entries: [ent('x.txt'), ent('inner.zip')] }); },
      archiveRead(path, entry) { window.__calls.read.push([path, entry]); return JSON.stringify({ ok: true, name: entry, size: 12, csize: 12, method: 8, crc: 1, mtime: T, kind: 'text', text: 'text of ' + path.split('/').pop(), editable: true }); },
      archiveEdit(path, op) { window.__calls.edit.push([path, JSON.parse(op)]); return 'started'; },
      archiveDiff(a, b) { window.__calls.diff = (window.__calls.diff || []).concat([[a, b]]); if (window.__diffThrows) throw new Error('boom'); return 'started'; },
      archiveStage(src, entry, kind) { window.__calls.stage.push([src, entry, kind]); if (window.__stageThrows) throw new Error('boom'); return 'started'; },
      archiveRelease(p) { window.__calls.release.push(p); }, archiveClose() {},
      archiveSign(path, mode) { window.__calls.sign.push([path, mode]); return 'started'; },
      archiveSignInfo(path) { setTimeout(() => window.onSignInfo({ ok: true, op: 'signinfo', path, name: path.split('/').pop(), editBlock: '', key: { ok: true, exists: true, sha256: 'aa'.repeat(32), hardware: true, bits: 2048 }, pkg: 'com.x', signed: true, signer: 'bb'.repeat(32), installed: false }), 20); return 'started'; },
      getLogcatAsync(id) { window.__calls.logcat.push(id); return 'started'; },
      storeSourceCatalog() {}, storeSourceRefresh() {},
      executeShellAsync(id) { return 'started'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(450);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none'; }, sel);
  const topSheet = () => ev(() => { const e = document.elementFromPoint(innerWidth / 2, innerHeight / 2); const o = e && e.closest('.modal-overlay.show'); return o ? o.id : ''; });
  const DIFF = { ok: true, a: { name: 'a.zip', path: '/sd/a.zip', count: 2 }, b: { name: 'b.zip', path: '/sd/b.zip', count: 2 }, same: 0, changed: [{ p: 'x.txt', a: 5, b: 6 }], added: [{ p: 'new.txt', b: 3 }], removed: [], truncated: false };
  const openA = async () => { await ev(() => { switchView('files'); arcOpen('/sd/a.zip'); }); await sleep(250); };
  const closeAll = () => ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });

  // 1) Compare: the "New" side is another archive, so it can't be edited (the save would write its text into this one)
  await openA();
  await ev(() => { arcStartDiff('/sd/b.zip'); }); await sleep(60);
  await ev(d => onArchiveDiff(d), DIFF); await sleep(80);
  await ev(() => arcDiffTap(0)); await sleep(60);
  await page.locator('#arcModalBtns button', { hasText: 'New' }).click(); await sleep(80);
  let btns = await page.locator('#arcModalBtns button').allInnerTexts();
  console.log('1. the New side of a comparison has no Edit text (it is the other archive):', !btns.some(t => /Edit text/.test(t)), JSON.stringify(btns));
  await page.locator('#arcModalBtns button', { hasText: 'Old' }).click(); await sleep(80);
  btns = await page.locator('#arcModalBtns button').allInnerTexts();
  console.log('   the Old side is this archive: Edit text is offered:', btns.some(t => /Edit text/.test(t)), JSON.stringify(btns));
  await ev(() => { arcCloseModal(); arcDiffClose(); }); await sleep(80);
  await ev(() => arcMenu(0, true)); await sleep(80);
  btns = await page.locator('#arcModalBtns button').allInnerTexts();
  console.log('   an ordinary entry of the open archive still can be edited:', btns.some(t => /Edit text/.test(t)));
  await closeAll();

  // 2) The terminal row keeps its RUN button on narrow screens
  await ev(() => (switchView('terminal'), txShowPane('console'))); await sleep(200);
  for (const w of [320, 360, 412]) {
    await page.setViewportSize({ width: w, height: 800 }); await sleep(150);
    const m = await ev(() => ({ run: document.getElementById('termRunBtn').getBoundingClientRect(), s: document.documentElement.scrollWidth, i: innerWidth, input: document.getElementById('termCmd').getBoundingClientRect().width }));
    console.log('2. ' + w + ' px: RUN stays 70 px wide, inside the screen, no sideways scroll:', Math.round(m.run.width) === 70 && m.run.right <= m.i + 0.5 && m.s <= m.i, JSON.stringify({ run: Math.round(m.run.width), right: Math.round(m.run.right), input: Math.round(m.input) }));
  }
  await page.setViewportSize({ width: 400, height: 860 }); await sleep(100);

  // 3) A failure report opens above the sheet it came from, and a successful retry takes it down
  await ev(() => switchView('files')); await sleep(100);
  await ev(() => { arcLastOp = { op: 'rename' }; arcMenu(0, false); }); await sleep(100);
  await ev(() => onArchiveResult({ ok: false, error: 'Destination exists' })); await sleep(120);
  console.log('3. with the entry sheet open, the failure report is the top sheet:', (await topSheet()) === 'commandResultsModal', await topSheet());
  await ev(() => document.getElementById('commandResultsModal').click()); await sleep(100);
  console.log('   closing it leaves the entry sheet for a retry:', await ev(() => document.getElementById('arcModal').classList.contains('show')) && !(await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))));
  await ev(() => onArchiveResult({ ok: false, error: 'Destination exists' })); await sleep(80);
  await ev(() => onArchiveResult({ ok: true, op: 'rename', message: 'Renamed' })); await sleep(120);
  console.log('   a retry that worked: no stale FAILED sheet is left under or over it:', !(await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))) && !(await ev(() => document.getElementById('arcModal').classList.contains('show'))));
  await closeAll();
  await ev(() => signOpen('/sd/a.apk')); await sleep(120);
  await ev(() => signRun('copy')); await sleep(30);
  await ev(() => onArchiveSigned({ ok: false, error: 'Not enough space' })); await sleep(100);
  console.log('   the sign sheet: its failure report is on top too:', (await topSheet()) === 'commandResultsModal');
  await ev(() => closeCommandResultsModal());
  await ev(() => signRun('copy')); await sleep(30);
  await ev(() => onArchiveSigned({ ok: true, op: 'sign', inPlace: false, path: '/sd/a-signed.apk', name: 'a-signed.apk', sha256: 'cc'.repeat(32), hardware: true, size: 10 })); await sleep(100);
  console.log('   and after the successful retry no report remains:', !(await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))));
  await closeAll();

  // 4) Store: a failed live search is shown even when no app matches; a catalog error is not mistaken for it
  const st = await ev(() => {
    const s = storeSrc.github;
    s.items = []; s.loaded = true; s.loading = false; s.error = ''; s.note = ''; s.searching = true;
    onStoreSource(JSON.stringify({ source: 'github', status: 'error', arg: 'q:zzz', error: 'rate limit' }));
    const msg = srcEl('github', 'Status').innerText;
    const note = s.note, searching = s.searching, error = s.error;
    // a catalog error while a search is also pending
    s.searching = true; s.loading = true; s.error = '';
    onStoreSource(JSON.stringify({ source: 'github', status: 'error', arg: '', error: 'boom' }));
    return { msg, note, searching, error, after: { loading: s.loading, searching: s.searching, error: s.error } };
  });
  console.log('4. an empty list still shows "Search failed":', /Search failed: rate limit/.test(st.msg) && !st.error && st.searching === false, JSON.stringify(st.msg));
  console.log('   the note has no icon:', !/[ℹ⚠]/.test(st.msg));
  console.log('   a catalog error while a search is pending ends the load and is shown as the error:', st.after.loading === false && st.after.searching === false && st.after.error === 'boom', JSON.stringify(st.after));
  await ev(() => { const s = storeSrc.github; s.error = ''; s.note = ''; s.loading = false; });

  // 5) Terminal: a busy shell keeps what was typed; a finished command never pops a result sheet (the output is
  // already right there in the terminal pane) - only a toast if the answer arrives on another tab.
  await ev(() => (switchView('terminal'), txShowPane('console'))); await sleep(100);
  await ev(() => { termShellRun = { id: 'sx', cmd: 'sleep 9' }; document.getElementById('termCmd').value = 'echo keep me'; termHistory = ['older']; });
  await ev(() => runTerminalCmd());
  const t5 = await ev(() => ({ val: document.getElementById('termCmd').value, hist: termHistory.slice() }));
  console.log('5. pressing RUN while one runs keeps the typed text and the history:', t5.val === 'echo keep me' && JSON.stringify(t5.hist) === '["older"]' && /still running/.test(await toast()), JSON.stringify(t5));
  await ev(() => { termShellRun = { id: 's1', cmd: 'ls' }; switchView('apps'); });
  await ev(() => onShellDone('s1', 'out')); await sleep(60);
  console.log('   the answer arriving on another tab is a toast, not a sheet over that tab:', !(await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))) && /see the ADB Console/.test(await toast()));
  await ev(() => { termShellRun = { id: 's2', cmd: 'ls' }; (switchView('terminal'), txShowPane('console')); });
  await ev(() => onShellDone('s2', 'out2')); await sleep(60);
  console.log('   on the Console tab itself, still no result sheet (no modal-on-success for an arbitrary command):', !(await ev(() => document.getElementById('commandResultsModal').classList.contains('show'))));
  await closeAll();

  // 6) Compare / nested state
  await ev(() => { switchView('files'); });
  await openA();
  await closeAll();
  await openA();
  await ev(d => { arcDiff = null; onArchiveDiff(d); }, DIFF); await sleep(80);
  console.log('6. in a comparison Add file / New folder are hidden:', !(await vis('#arcAddBtn')) && !(await vis('#arcFolderBtn')));
  await ev(() => { document.getElementById('arcSearch').value = 'new'; arcRenderDiff(); });
  await ev(() => arcDiffClose()); await sleep(80);
  console.log('   closing it clears the filter text, brings Add file / New folder back, and re-lists the folder:', (await ev(() => document.getElementById('arcSearch').value)) === '' && await vis('#arcAddBtn') && await vis('#arcFolderBtn') && (await page.locator('#arcList .perm-row').count()) === 2);
  await ev(d => { onArchiveDiff(d); }, DIFF); await sleep(80);
  await ev(() => arcOpenNested({ ref: '/cache/nested1.zip', name: 'inner.zip' })); await sleep(120);
  console.log('   opening an archive inside clears the comparison view (the header does not stay):', (await ev(() => arcDiff)) === null && !(await vis('#arcDiffHead')) && (await ev(() => arc.nested)) === true);
  await ev(() => document.getElementById('arcSearch').value = '');
  await ev(() => arcClose()); await sleep(120);
  console.log('   leaving it brings the comparison back:', (await ev(() => arcDiff && arcDiff.b.name)) === 'b.zip' && await vis('#arcDiffHead') && !(await ev(() => arc.nested)));
  await ev(() => arcDiffClose());
  // the archive closed while the entry was being copied out
  await ev(() => { arcClose(); window.__calls.release.length = 0; });
  const noThrow = await ev(() => { try { arcOpenNested({ ref: '/cache/late.zip', name: 'late.zip' }); return 'ok'; } catch (e) { return String(e); } });
  console.log('   an entry that arrives after the archive was closed is dropped (not an error) and released:', noThrow === 'ok' && JSON.stringify(await ev(() => window.__calls.release)) === '["/cache/late.zip"]', noThrow);
  await ev(() => { arcStack = []; });

  // 7) A late signing answer does not land on another file's sheet
  await ev(() => signOpen('/sd/a.apk')); await sleep(100);
  await ev(() => signRun('copy')); await sleep(20);
  await ev(() => signClose());
  await ev(() => signOpen('/sd/b.apk')); await sleep(100);
  await ev(() => onArchiveSigned({ ok: true, op: 'sign', inPlace: false, path: '/sd/a-signed.apk', name: 'a-signed.apk', sha256: 'cc'.repeat(32), hardware: true, size: 10 })); await sleep(80);
  const rows7 = await page.locator('#signRows').innerText();
  console.log('7. the late result for A shows nothing on the sheet of B:', !/a-signed/.test(rows7) && (await ev(() => signTarget && signTarget.done)) === null && !(await ev(() => signTarget.busy)), JSON.stringify(rows7.slice(0, 120)));
  await ev(() => signClose());

  // 8) A bridge that throws does not leave the archive busy for ever
  await openA();
  await ev(() => { window.__diffThrows = true; arcStartDiff('/sd/b.zip'); });
  console.log('8. a throwing compare start frees the archive:', (await ev(() => arcBusy)) === false && /could not start/.test(await toast()));
  await ev(() => { window.__diffThrows = false; window.__stageThrows = true; arcTarget = { path: 'inner.zip', dir: false, name: 'inner.zip' }; arcStageEntry('nested'); });
  console.log('   a throwing stage start too:', (await ev(() => arcBusy)) === false);
  await ev(() => { window.__stageThrows = false; arcClose(); });

  // 9) Clear drops a Refresh that was still on its way
  await ev(() => switchView('logcat')); await sleep(250);
  await ev(() => { window.__calls.logcat.length = 0; logcatRefresh(); });
  const id9 = await ev(() => window.__calls.logcat[window.__calls.logcat.length - 1]);
  await ev(() => logcatClear());
  await ev(id => onLogcatData(id, '10-03 12:00:00.000  1  2 I Tag: old line before the clear'), id9); await sleep(80);
  const lcText = await page.locator('#logcatOutput').innerText();
  console.log('9. an answer that was fetched before Clear does not bring the old log back:', !/old line before the clear/.test(lcText) && /cleared/.test(lcText), JSON.stringify(lcText.slice(0, 60)));

  // 10) The default extract folder
  const d10 = await ev(() => {
    const out = {};
    arc = { name: 'B.zip', path: '/sd/B.zip' };
    kvSet('arc_last_dest', '');
    arcTarget = { dir: true, path: '', name: 'B.zip' }; out.firstWhole = arcDefaultDest();
    kvSet('arc_last_dest', '/storage/emulated/0/Download/A');
    arcTarget = { dir: true, path: '', name: 'B.zip' }; out.nextWhole = arcDefaultDest();
    arcTarget = { dir: false, path: 'x.txt', name: 'x.txt' }; out.nextFile = arcDefaultDest();
    arcTarget = { dir: true, path: 'res/', name: 'res' }; out.nextFolder = arcDefaultDest();
    return out;
  });
  console.log('10. a whole archive never defaults into the folder of the last one:', d10.firstWhole === '/storage/emulated/0/Download/B' && d10.nextWhole === '/storage/emulated/0/Download/B', JSON.stringify(d10));
  console.log('    a single file / a folder still go where the last one went:', d10.nextFile === '/storage/emulated/0/Download/A' && d10.nextFolder === '/storage/emulated/0/Download/A');
  await ev(() => { arc = null; });

  // 11) Long press on a touch screen: the finger that starts selecting does not also "tap" the row
  await ev(() => { switchView('files'); fmGo('/storage/emulated/0'); }); await sleep(250);
  const t11 = await page.evaluate(async () => {
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const row = document.querySelector('#fmList .perm-info[data-i]');
    const mk = (type, x, y) => new TouchEvent(type, { bubbles: true, cancelable: true, touches: type === 'touchend' ? [] : [new Touch({ identifier: 1, target: row, clientX: x, clientY: y })], changedTouches: [new Touch({ identifier: 1, target: row, clientX: x, clientY: y })] });
    const r = row.getBoundingClientRect(), x = r.left + 30, y = r.top + 10;
    row.dispatchEvent(mk('touchstart', x, y));
    await sleep(650);                                                  // the hold: selecting starts
    const selecting = fmSelMode && fmSel.size === 1;
    const end = mk('touchend', x, y); row.dispatchEvent(end);
    const prevented = end.defaultPrevented;
    // what a browser may still make up after the touch: mouse events and a click
    const row2 = document.querySelector('#fmList .perm-info[data-i]');
    row2.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0, clientX: x, clientY: y }));
    row2.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, button: 0, clientX: x, clientY: y }));
    row2.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, clientX: x, clientY: y }));
    await sleep(50);
    const stillPicked = fmSel.size === 1;
    // then an ordinary tap on the same row un-picks it
    const row3 = document.querySelector('#fmList .perm-info[data-i]');
    const r3 = row3.getBoundingClientRect(), x3 = r3.left + 30, y3 = r3.top + 10;
    row3.dispatchEvent(mk('touchstart', x3, y3)); await sleep(60);
    row3.dispatchEvent(mk('touchend', x3, y3));
    row3.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 0 })); row3.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, button: 0 })); row3.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    await sleep(50);
    return { selecting, prevented, stillPicked, afterTap: fmSel.size };
  });
  console.log('11. press-and-hold starts selecting, the lifting finger is swallowed, and the made-up mouse events + click do not un-pick the row:', t11.selecting && t11.prevented && t11.stillPicked, JSON.stringify(t11));
  console.log('    an ordinary tap afterwards still toggles the row:', t11.afterTap === 0);
  await ev(() => fmSelExit());

  // 12) The history filter box keeps its element (and an IME's composition) while typing
  await ev(() => { termHistory = ['ls', 'pm list packages', 'dumpsys battery', 'getprop', 'id', 'ps -A', 'settings list global', 'cmd package list']; kvSet('term_hist', termHistory); (switchView('terminal'), txShowPane('console')); termOpenHistory(); }); await sleep(150);
  await ev(() => { window.__f1 = document.getElementById('termHistFilter'); });
  await page.locator('#termHistFilter').click();
  await page.keyboard.type('pm', { delay: 40 }); await sleep(100);
  const t12 = await ev(() => ({ same: document.getElementById('termHistFilter') === window.__f1, rows: document.querySelectorAll('#termHistList .term-row').length, focused: document.activeElement === window.__f1, val: window.__f1.value }));
  console.log('12. typing in the history filter keeps the same input element, focus and text, and narrows the rows:', t12.same && t12.focused && t12.val === 'pm' && t12.rows === 1, JSON.stringify(t12));
  await ev(() => { termCloseModal(); termOpenHistory(); }); await sleep(100);
  console.log('    reopening starts with an empty filter and every row:', (await ev(() => document.getElementById('termHistFilter').value)) === '' && (await ev(() => document.querySelectorAll('#termHistList .term-row').length)) === 8);
  await closeAll();

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
