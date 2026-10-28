// v7.18.2 Root file management (the owner's request): Root Features lists "Root File Management" (locked without root; with it, Open files opens
// the File Manager), and as the root user a file's sheet has Permissions… (only then; never for added storage). It reads the mode, owner, group
// and SELinux label (fmOp stat) and shows them, ticks for read / write / run of owner, group and others kept in step with the mode field (setuid,
// setgid and sticky kept), Apply permissions sends chmod with the four-digit mode, Apply owner asks first and sends chown with owner:group, a
// folder offers "Also everything inside this folder" (chmod-r / chown-r, asked first), a bad mode or an empty owner is refused before the app is
// asked, a refusal of the app is shown, and after a change the panel reads the path again and shows what it was before. 360 px, nothing wider.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const dialogs = []; let answer = true;
  page.on('dialog', d => { dialogs.push(d.message()); answer ? d.accept() : d.dismiss(); });
  await page.addInitScript(fm.initScript, { files: { '/storage/emulated/0/Download/a.sh': 'echo hi', '/storage/emulated/0/Download/dir': null } });
  // stat / chmod / chown over the mock: one record per path
  await page.addInitScript(() => {
    window.__perm = { '/storage/emulated/0/Download/a.sh': { mode: '660', user: 'u0_a123', group: 'media_rw', uid: '10123', gid: '1023' }, '/storage/emulated/0/Download/dir': { mode: '2770', user: 'root', group: 'sdcard_rw', uid: '0', gid: '1015' },
      '/storage/emulated/0/Download/ln': { mode: '777', user: 'UNKNOWN', group: 'everybody', uid: '2001', gid: '9997', type: 'symbolic link' } };
    window.__permCalls = []; window.__permFail = '';
    const br = window.AndroidBridge, orig = br.fmOp.bind(br);
    br.fmOp = (op, a, bb) => {
      if (op === 'stat' || /^ch(mod|own)/.test(op)) {
        window.__permCalls.push(op + ' ' + a + (bb ? ' ' + bb : ''));
        const r = window.__perm[a];
        if (op === 'stat') return JSON.stringify(r ? Object.assign({ ok: true, output: 'OK', type: 'regular file', label: 'u:object_r:media_rw_data_file:s0' }, r) : { ok: false, output: 'no such file' });
        if (window.__permFail) return JSON.stringify({ ok: false, output: window.__permFail });
        if (op.startsWith('chmod')) r.mode = bb.replace(/^0(?=\d{3})/, '');
        else { const [u, g] = bb.split(':'); if (u) r.user = u; if (g) r.group = g; }
        return JSON.stringify({ ok: true, output: 'OK' });
      }
      return orig(op, a, bb);
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const setRoot = on => ev(v => { lastModesData = Object.assign({}, lastModesData || {}, { rootUidActive: v, sysPrivActive: v }); }, on);

  // 1) Root Features
  await ev(() => switchView('rootfeatures')); await page.waitForTimeout(250);
  const row = () => ev(() => { const r = Array.from(document.querySelectorAll('.rf-row')).find(x => /Root File Management/.test(x.innerText)); return r ? { locked: r.classList.contains('is-locked'), btn: !!document.getElementById('rfFilesBtn') } : null; });
  const r0 = await row();
  check('1. Root Features lists Root File Management, locked without root', !!r0 && r0.locked && !r0.btn, JSON.stringify(r0));
  await setRoot(true); await ev(() => renderRootFeatures()); await page.waitForTimeout(150);
  const r1 = await row();
  check('   with root it has Open files', !!r1 && !r1.locked && r1.btn && (await page.locator('#rfFilesBtn').innerText()).trim() === 'Open files', JSON.stringify(r1));
  await page.locator('#rfFilesBtn').click(); await page.waitForTimeout(300);
  check('   Open files opens the File Manager', await ev(() => currentViewName() === 'files'));

  // 2) the sheet: Permissions… only as root
  const openSheet = async (full, isDir) => { await ev(([f, d]) => fmActions(f, d, fmBase(f)), [full, isDir]); await page.waitForTimeout(100); };
  const F = '/storage/emulated/0/Download/a.sh', D = '/storage/emulated/0/Download/dir';
  await setRoot(false); await openSheet(F, false);
  const noBtn = await ev(() => !Array.from(document.querySelectorAll('#fmActionBtns button')).some(x => x.innerText === 'Permissions…'));
  await setRoot(true); await openSheet(F, false);
  const btns = await ev(() => Array.from(document.querySelectorAll('#fmActionBtns button')).map(x => x.innerText));
  check('2. Permissions… is in a file\'s sheet only as root, right before Delete', noBtn && btns[btns.length - 2] === 'Permissions…' && btns[btns.length - 1] === 'Delete', JSON.stringify(btns));
  await ev(() => fmActions('content://x/doc/1', false, 'doc.txt')); await page.waitForTimeout(80);
  check('   not for added storage', await ev(() => !Array.from(document.querySelectorAll('#fmActionBtns button')).some(x => x.innerText === 'Permissions…')));

  // 3) reading
  await openSheet(F, false);
  await page.locator('#fmActionBtns button', { hasText: 'Permissions…' }).click(); await page.waitForTimeout(100);
  const st = await ev(() => ({ now: document.getElementById('fmPermNow').textContent, mode: document.getElementById('fmPermMode').value, rwx: document.getElementById('fmPermRwx').textContent,
    ticks: Array.from(document.querySelectorAll('#fmPermRow .fm-perm-grid input')).map(i => i.checked ? 1 : 0).join(''), user: document.getElementById('fmPermUser').value, group: document.getElementById('fmPermGroup').value,
    rec: !!document.getElementById('fmPermModeRec'), shown: getComputedStyle(document.getElementById('fmPermRow')).display !== 'none' }));
  check('3. it reads the path: mode 0660 (rw-rw----), owner u0_a123 (10123), group media_rw (1023), the label', st.shown && st.now === 'Now: 0660 (rw-rw----) · u0_a123 (10123):media_rw (1023) · u:object_r:media_rw_data_file:s0', st.now);
  check('   the ticks, the mode field and the owner fields say the same', st.mode === '0660' && st.rwx === 'rw-rw----' && st.ticks === '110110000' && st.user === 'u0_a123' && st.group === 'media_rw', JSON.stringify(st));
  check('   a file has no "everything inside" choice', !st.rec);
  check('   it asked the app with fmOp stat', (await ev(() => window.__permCalls.join(','))) === 'stat ' + F);

  // 4) ticks <-> field
  await page.locator('#fmPermRow input[aria-label="Owner: Run"]').check(); await page.locator('#fmPermRow input[aria-label="Others: Read"]').check(); await page.waitForTimeout(50);
  const t4 = await ev(() => [document.getElementById('fmPermMode').value, document.getElementById('fmPermRwx').textContent]);
  check('4. ticking Owner Run and Others Read makes 0764 (rwxrw-r--)', t4[0] === '0764' && t4[1] === 'rwxrw-r--', JSON.stringify(t4));
  await page.fill('#fmPermMode', '755'); await page.waitForTimeout(50);
  const t5 = await ev(() => Array.from(document.querySelectorAll('#fmPermRow .fm-perm-grid input')).map(i => i.checked ? 1 : 0).join(''));
  check('   typing 755 ticks rwx r-x r-x', t5 === '111101101', t5);
  await page.fill('#fmPermMode', '8a9'); await page.waitForTimeout(30);
  check('   only octal digits stay in the field', (await ev(() => document.getElementById('fmPermMode').value)) === '');

  // 5) apply permissions
  await page.fill('#fmPermMode', '755'); dialogs.length = 0;
  await ev(() => { window.__permCalls.length = 0; });
  await page.locator('#fmPermModeBtn').click(); await page.waitForTimeout(150);
  const c5 = await ev(() => window.__permCalls.slice());
  check('5. Apply permissions on a file sends chmod 0755 at once (no question), then reads it again', dialogs.length === 0 && c5[0] === 'chmod ' + F + ' 0755' && c5[1] === 'stat ' + F, JSON.stringify(c5));
  check('   the toast and the panel show the new mode, and what it was before', /^Done: 0755 \(rwxr-xr-x\)$/.test(await toast()) && (await ev(() => document.getElementById('fmPermNow').textContent)).startsWith('Now: 0755 (rwxr-xr-x)')
    && (await ev(() => (document.getElementById('fmPermBefore') || {}).textContent)) === 'Before the last change: 0660 (rw-rw----) u0_a123:media_rw', await toast());
  await page.locator('#fmPermModeBtn').click(); await page.waitForTimeout(80);
  check('   the same mode again: Nothing to change, nothing sent', (await toast()) === 'Nothing to change' && (await ev(() => window.__permCalls.length)) === 2);

  // 6) apply owner (asked first)
  await page.fill('#fmPermUser', 'root'); await page.fill('#fmPermGroup', ''); answer = false; dialogs.length = 0;
  await page.locator('#fmPermOwnBtn').click(); await page.waitForTimeout(80);
  check('6. Apply owner asks first; No sends nothing', dialogs.length === 1 && /^Set the owner root:media_rw on a\.sh\?/.test(dialogs[0]) && (await ev(() => window.__permCalls.length)) === 2, JSON.stringify(dialogs));
  answer = true; dialogs.length = 0;
  await page.locator('#fmPermOwnBtn').click(); await page.waitForTimeout(150);
  check('   Yes sends chown root: (the group as it is)', (await ev(() => window.__permCalls[2])) === 'chown ' + F + ' root:', await ev(() => window.__permCalls.join(' | ')));
  check('   the panel reads root:media_rw now', (await ev(() => document.getElementById('fmPermNow').textContent)).includes('· root (10123):media_rw'), await ev(() => document.getElementById('fmPermNow').textContent));
  await page.fill('#fmPermUser', ''); await page.fill('#fmPermGroup', ''); await page.locator('#fmPermOwnBtn').click(); await page.waitForTimeout(60);
  check('   an empty owner and group is refused before the app is asked', (await toast()) === 'Enter an owner or a group' && (await ev(() => window.__permCalls.length)) === 4);

  // 7) a folder, everything inside, a refusal
  await openSheet(D, true);
  await page.locator('#fmActionBtns button', { hasText: 'Permissions…' }).click(); await page.waitForTimeout(100);
  const d7 = await ev(() => ({ mode: document.getElementById('fmPermMode').value, rec: !!document.getElementById('fmPermModeRec') && !!document.getElementById('fmPermOwnRec') }));
  check('7. a folder keeps its setgid bit (2770) and offers Also everything inside this folder', d7.mode === '2770' && d7.rec, JSON.stringify(d7));
  await page.locator('#fmPermRow input[aria-label="Others: Read"]').check(); await page.waitForTimeout(30);
  check('   a tick keeps setgid: 2774', (await ev(() => document.getElementById('fmPermMode').value)) === '2774');
  await page.fill('#fmPermMode', '775'); await page.waitForTimeout(30);
  await page.locator('#fmPermModeRec').check(); dialogs.length = 0; await ev(() => { window.__permCalls.length = 0; });
  await page.locator('#fmPermModeBtn').click(); await page.waitForTimeout(150);
  check('   three digits keep setgid too; everything inside asks first, then sends chmod-r 2775', dialogs.length === 1 && /and everything inside it\? What each item inside had before is not kept/.test(dialogs[0]) && (await ev(() => window.__permCalls[0])) === 'chmod-r ' + D + ' 2775', await ev(() => window.__permCalls.join(' | ')));
  await ev(() => { window.__permFail = 'System location: not touched'; });
  await page.fill('#fmPermMode', '700'); await page.locator('#fmPermModeBtn').click(); await page.waitForTimeout(120);
  check('   a refusal of the app is shown as it is', (await toast()) === 'System location: not touched', await toast());
  await ev(() => { window.__permFail = ''; });

  // 8) 360 px
  const wide = await ev(() => { const s = document.querySelector('#fmActionModal .modal-sheet'); return Array.from(document.querySelectorAll('#fmPermRow *')).every(e => e.getBoundingClientRect().right <= s.getBoundingClientRect().right + 1) && document.documentElement.scrollWidth <= innerWidth; });
  check('8. at 360 px everything of the panel is inside the sheet, nothing scrolls sideways', wide);
  const tall = await ev(() => Array.from(document.querySelectorAll('#fmPermRow .fm-perm-grid label')).every(l => l.getBoundingClientRect().height >= 44));
  check('   each tick is at least 44 px tall to tap', tall);

  // 9) review fixes: three typed digits then a tick keep the special bits; a link offers no mode (chmod would follow it); an id with no name
  //    shows its number; View contents closes the panel
  await openSheet(D, true);
  await page.locator('#fmActionBtns button', { hasText: 'Permissions…' }).click(); await page.waitForTimeout(100);
  await page.fill('#fmPermMode', '770'); await page.waitForTimeout(30);
  await page.locator('#fmPermRow input[aria-label="Others: Read"]').check(); await page.waitForTimeout(30);
  check('9. typing 770 and then ticking Others Read keeps the folder\'s setgid: 2774', (await ev(() => document.getElementById('fmPermMode').value)) === '2774', await ev(() => document.getElementById('fmPermMode').value));
  const LN = '/storage/emulated/0/Download/ln';
  await openSheet(LN, false);
  await page.locator('#fmActionBtns button', { hasText: 'Permissions…' }).click(); await page.waitForTimeout(100);
  const l9 = await ev(() => ({ grid: !!document.querySelector('#fmPermRow .fm-perm-grid'), modeBtn: !!document.getElementById('fmPermModeBtn'), note: (document.getElementById('fmPermLink') || {}).textContent,
    ownBtn: !!document.getElementById('fmPermOwnBtn'), user: document.getElementById('fmPermUser').value, now: document.getElementById('fmPermNow').textContent }));
  check('   a symbolic link: no ticks and no Apply permissions, a line says to change the file it points to; Apply owner stays', !l9.grid && !l9.modeBtn && l9.ownBtn && l9.note === 'This is a link: open the file it points to and change the permissions there', JSON.stringify(l9));
  check('   an owner with no name (stat says UNKNOWN) shows its number 2001', l9.user === '2001' && l9.now.includes('· 2001 (2001):everybody (9997)'), JSON.stringify(l9));
  await openSheet(F, false);
  await page.locator('#fmActionBtns button', { hasText: 'Permissions…' }).click(); await page.waitForTimeout(80);
  await ev(() => fmViewText()); await page.waitForTimeout(80);
  check('   View contents closes the Permissions panel', await ev(() => getComputedStyle(document.getElementById('fmPermRow')).display === 'none'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
