// Saved Applications > Back up and restore: the lists go to a folder the person chose (or Download/ADB App Manager when none), by hand with a time in the file name
// or automatically into one file that is replaced; Share sends the same file. Restoring reads a file (file chooser or the chosen folder), checks it (a stranger's JSON,
// a newer format, bad package names, too many lists), and adds to the lists or replaces them (the quick list follows). Nothing is written when there is nothing to back up,
// and an empty set of lists never replaces the automatic backup.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    const W = window;
    W.__lists = [
      { id: 'list_1', name: 'Samsung bloat', description: 'what I remove first', packages: ['com.samsung.android.bixby.agent', 'com.facebook.katana'], updatedAt: 1700000000000 },
      { id: 'list_2', name: 'Games', description: '', packages: ['com.king.candycrushsaga'], updatedAt: 1700000001000 }
    ];
    W.__quick = 'list_1'; W.__fs = {}; W.__dl = []; W.__share = []; W.__pick = null; W.__fail = null; W.__dirReply = 'ok'; W.__calls = []; W.__writes = 0;
    const reply = (fn, arg, ms) => setTimeout(() => W[fn] && W[fn](arg), ms || 5);
    W.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {},
      loadCustomLists() { return JSON.stringify(W.__lists); }, saveCustomLists(j) { W.__lists = JSON.parse(j); },
      getQuickList() { return W.__quick; }, setQuickList(id) { W.__quick = id; },
      saveTextToDownloads(name, text) { if (W.__fail === 'dl') return 'Error: no space left'; W.__dl.push({ name, text }); return 'Download/ADB App Manager/' + name; },
      shareTextFile(name, text, mime) { W.__share.push({ name, text, mime }); return ''; },
      pickBackupFolder(tag) { W.__calls.push('pick ' + tag); if (W.__dirReply === 'cancel') reply('onBackupFolderPicked', { tag, cancelled: true }); else reply('onBackupFolderPicked', { tag, uri: 'content://tree/backups', label: 'SD card/Backups' }); },
      treeWriteText(uri, name, text, replace) {
        W.__calls.push('write ' + name + ' replace=' + replace);
        if (W.__fail === 'sec') return JSON.stringify({ ok: false, error: 'Android no longer lets this app use that folder: choose it again' });
        const dir = W.__fs[uri] = W.__fs[uri] || [];
        W.__writes++;
        let f = replace ? dir.find(x => x.name === name) : null;
        if (!f) { f = { name, uri: uri + '/' + name + (dir.some(x => x.name === name) ? '(1)' : ''), text: '', modified: 0 }; dir.push(f); }
        f.text = text; f.modified = 1760000000000 + W.__writes * 60000;
        return JSON.stringify({ ok: true, name, bytes: text.length });
      },
      treeListFiles(uri, prefix) { const dir = (W.__fs[uri] || []).filter(f => f.name.startsWith(prefix) && f.name.endsWith('.json')).slice().sort((a, b) => b.modified - a.modified); return JSON.stringify({ ok: true, files: dir.map(f => ({ name: f.name, uri: f.uri, size: f.text.length, modified: f.modified })) }); },
      readTextUri(uri) { for (const k of Object.keys(W.__fs)) { const f = W.__fs[k].find(x => x.uri === uri); if (f) return JSON.stringify({ ok: true, text: f.text }); } return JSON.stringify({ ok: false, error: 'could not open the file' }); },
      pickTextFile(tag) { W.__calls.push('pickText ' + tag); if (W.__pick) reply('onTextFilePicked', Object.assign({ tag }, W.__pick)); }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const lists = () => ev(() => window.__lists.map(l => l.name + ':' + l.packages.length));
  const note = () => ev(() => document.getElementById('lbWhere').innerText.replace(/\s+/g, ' '));
  const sheet = () => ev(() => ({ open: document.getElementById('choiceSheet').classList.contains('show'), title: document.getElementById('choiceTitle').innerText, desc: document.getElementById('choiceDesc').innerText, rows: [...document.querySelectorAll('#choiceList .ab-sheet-row')].map(r => r.innerText.replace(/\s+/g, ' ')) }));
  const pickRow = async i => { await page.click('#choiceList .ab-sheet-row:nth-child(' + (i + 1) + ')'); await wait(120); };
  const bk = text => ({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2026-10-01T10:00:00.000Z', quickListId: '', lists: text });

  await ev(() => { loadSavedLists(); loadQuickList(); switchView('saved-lists'); }); await wait(300);

  // ---- the card ----
  check('the Saved Applications tab has a Back up and restore card with Back up now, Choose folder, Share, Restore from a file and Restore from the folder', await ev(() => { const c = document.getElementById('listsBackupCard'); const t = c.innerText; return /Back up and restore/.test(t) && /Back up now/.test(t) && /Choose folder/.test(t) && /Share/.test(t) && /Restore from a file/.test(t) && /Restore from the folder/.test(t) && /Back up automatically/.test(t); }));
  check('with no folder: says backups go to Download/ADB App Manager, no backup yet, 2 lists would be backed up; the folder-only controls are off', /Download\/ADB App Manager \(the default\)/.test(await note()) && /No backup made yet/.test(await note()) && await ev(() => document.getElementById('lbAuto').disabled && document.getElementById('lbFromFolder').disabled && getComputedStyle(document.getElementById('lbDefault')).display === 'none' && /2 lists would be backed up/.test(document.getElementById('lbNote').innerText)));

  // ---- by default: Downloads ----
  await page.click('#lbNow'); await wait(150);
  const d1 = await ev(() => window.__dl.slice());
  check('Back up now without a folder saves saved-lists-<date>-<time>.json to Download/ADB App Manager and says where', d1.length === 1 && /^saved-lists-\d{8}-\d{4}\.json$/.test(d1[0].name) && /Download\/ADB App Manager/.test(await toast()) && /Backed up 2 lists/.test(await toast()), JSON.stringify(d1.map(x => x.name)) + await toast());
  const doc = JSON.parse(d1[0].text);
  check('the file says what it is and holds both lists whole, with the quick list', doc.app === 'adb-app-manager' && doc.kind === 'saved-lists' && doc.format === 1 && doc.quickListId === 'list_1' && doc.lists.length === 2 && doc.lists[0].id === 'list_1' && doc.lists[0].name === 'Samsung bloat' && doc.lists[0].description === 'what I remove first' && JSON.stringify(doc.lists[0].packages) === '["com.samsung.android.bixby.agent","com.facebook.katana"]', d1[0].text.slice(0, 200));
  check('the card then shows the last backup: when, how many lists, the file name', /Last backup .* · 2 lists · saved-lists-\d{8}-\d{4}\.json/.test(await note()), await note());
  await ev(() => { window.__fail = 'dl'; }); await page.click('#lbNow'); await wait(100);
  check('a failed save says why, and the card says the last attempt failed', /backup failed: no space left/.test(await toast()) && /Last attempt .* failed: no space left/.test(await note()), await toast() + ' | ' + await note());
  await ev(() => { window.__fail = null; });

  // ---- choose a folder ----
  await ev(() => { window.__dirReply = 'cancel'; }); await page.click('#listsBackupCard button:has-text("Choose folder")'); await wait(100);
  check('backing out of the folder picker changes nothing', !(await ev(() => lbState().uri)));
  await ev(() => { window.__dirReply = 'ok'; }); await page.click('#listsBackupCard button:has-text("Choose folder")'); await wait(100);
  check('a chosen folder is remembered and shown, Use Downloads appears, and the folder controls come on', await ev(() => lbState().uri === 'content://tree/backups' && lbState().label === 'SD card/Backups' && !document.getElementById('lbAuto').disabled && !document.getElementById('lbFromFolder').disabled && getComputedStyle(document.getElementById('lbDefault')).display !== 'none') && /folder SD card\/Backups/.test(await note()) && /Backups now go to SD card\/Backups/.test(await toast()), await note());
  await page.click('#lbNow'); await wait(120);
  let fsd = await ev(() => window.__fs['content://tree/backups'] || []);
  check('Back up now writes a new dated file into the folder (not a replace)', fsd.length === 1 && /^saved-lists-\d{8}-\d{4}\.json$/.test(fsd[0].name) && (await ev(() => window.__calls.filter(c => /^write/.test(c)).pop())).endsWith('replace=false') && /to SD card\/Backups\/saved-lists-/.test(await toast()), await toast());
  check('and says so in the card with the file name', /Last backup .* · 2 lists · saved-lists-\d{8}-\d{4}\.json/.test(await note()));

  // ---- Share ----
  await page.click('#listsBackupCard button:has-text("Share")'); await wait(100);
  const sh = await ev(() => window.__share.slice());
  check('Share hands the same JSON to Android\'s share sheet', sh.length === 1 && /^saved-lists-\d{8}-\d{4}\.json$/.test(sh[0].name) && sh[0].mime === 'application/json' && JSON.parse(sh[0].text).lists.length === 2);

  // ---- a folder that cannot be used any more ----
  await ev(() => { window.__fail = 'sec'; }); await page.click('#lbNow'); await wait(100);
  check('a folder Android no longer allows says to choose it again', /choose it again/.test(await toast()) && /Last attempt .* failed: .*choose it again/.test(await note()), await toast());
  await ev(() => { window.__fail = null; });

  // ---- automatic ----
  await page.click('#lbAuto + .switch-track'); await wait(200);
  fsd = await ev(() => window.__fs['content://tree/backups']);
  check('Automatic on: a first backup is made at once, as saved-lists-latest.json, replacing', await ev(() => lbState().auto === true) && fsd.some(f => f.name === 'saved-lists-latest.json') && (await ev(() => window.__calls.filter(c => /^write/.test(c)).pop())) === 'write saved-lists-latest.json replace=true' && /Automatic backups on/.test(await toast()), await ev(() => JSON.stringify(window.__calls)));
  const w0 = await ev(() => window.__writes);
  await ev(() => { customLists.unshift({ id: 'list_3', name: 'Added later', description: '', packages: ['com.example.one'], updatedAt: Date.now() }); persistSavedLists(); persistSavedLists(); persistSavedLists(); });
  await wait(4600);
  const latest = () => ev(() => JSON.parse(window.__fs['content://tree/backups'].filter(f => f.name === 'saved-lists-latest.json').map(f => f.text)[0]));
  check('a change is backed up a few seconds later, once however many saves came, into the same single file', (await ev(() => window.__writes)) === w0 + 1 && (await ev(() => window.__fs['content://tree/backups'].filter(f => f.name === 'saved-lists-latest.json').length)) === 1 && (await latest()).lists.length === 3);
  check('the card says it was automatic', /\(automatic\)/.test(await note()));
  await ev(() => { customLists = []; persistSavedLists(); }); await wait(4600);
  check('an empty set of lists never replaces the automatic backup', (await latest()).lists.length === 3);
  await ev(() => { customLists = [
    { id: 'list_1', name: 'Samsung bloat', description: 'what I remove first', packages: ['com.samsung.android.bixby.agent', 'com.facebook.katana'], updatedAt: 1700000000000 },
    { id: 'list_2', name: 'Games', description: '', packages: ['com.king.candycrushsaga'], updatedAt: 1700000001000 }]; persistSavedLists(); }); await wait(4600);
  check('and the next real change updates it again', (await latest()).lists.length === 2);
  await page.click('#lbAuto + .switch-track'); await wait(100);
  const w1 = await ev(() => window.__writes);
  await ev(() => { customLists.push({ id: 'list_9', name: 'More', description: '', packages: ['a.b'], updatedAt: 1 }); persistSavedLists(); }); await wait(4600);
  check('Automatic off: changes are not written', (await ev(() => window.__writes)) === w1 && !(await ev(() => lbState().auto)));
  await ev(() => { customLists.pop(); persistSavedLists(); });

  // ---- restore from the folder: the choice of file ----
  await ev(() => { const dir = window.__fs['content://tree/backups']; dir.push({ name: 'notes.json', uri: 'content://tree/backups/notes.json', text: '{}', modified: 1 }); dir.push({ name: 'saved-lists-20250101-0900.json', uri: 'content://tree/backups/old', text: JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2025-01-01T09:00:00Z', quickListId: '', lists: [{ id: 'x1', name: 'Old list', description: 'from 2025', packages: ['org.old.app', 'org.old.two'], updatedAt: 5 }] }), modified: 1000 }); });
  await page.click('#lbFromFolder'); await wait(150);
  let sh2 = await sheet();
  check('Restore from the folder lists the saved-lists backups there, newest first, without other files', sh2.open && /Restore from the folder/.test(sh2.title) && sh2.rows.length >= 3 && /saved-lists-latest|saved-lists-2/.test(sh2.rows[0]) && /saved-lists-20250101-0900/.test(sh2.rows[sh2.rows.length - 1]) && !sh2.rows.some(r => /notes\.json/.test(r)), JSON.stringify(sh2.rows));
  await pickRow(sh2.rows.length - 1);
  sh2 = await sheet();
  check('choosing one reads it and asks: Restore 1 list with 2 apps, from the file, with Add and Replace', sh2.open && /Restore 1 list with 2 apps/.test(sh2.title) && /saved-lists-20250101-0900\.json/.test(sh2.desc) && /2025-01-01/.test(sh2.desc) && /You have 2 lists/.test(sh2.desc) && /Add them to my lists/.test(sh2.rows[0]) && /Replace my lists with them/.test(sh2.rows[1]), JSON.stringify(sh2));
  await pickRow(0);
  check('Add keeps the two lists and adds the old one', JSON.stringify(await lists()) === '["Samsung bloat:2","Games:1","Old list:2"]' && /Added 1 list/.test(await toast()), JSON.stringify(await lists()) + await toast());
  check('the added list is shown in the tab', await ev(() => /Old list/.test(document.getElementById('savedListsContainer').innerText)));

  // ---- add: duplicates, same name other apps, same id ----
  await ev(() => { window.__pick = { name: 'mine.json', text: JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2026-02-02T00:00:00Z', quickListId: '', lists: [
    { id: 'list_2', name: 'Games', description: '', packages: ['com.king.candycrushsaga'], updatedAt: 1 },
    { id: 'zz', name: 'Games', description: '', packages: ['other.game'], updatedAt: 2 },
    { id: 'list_1', name: 'Another', description: 'same id', packages: ['p.q'], updatedAt: 3 },
    { id: 'n1', name: 'Brand new', description: 'd', packages: ['new.one'], updatedAt: 4 }] }) }; });
  await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(150);
  await pickRow(0);
  const L = await ev(() => customLists.map(l => ({ id: l.id, name: l.name })));
  check('merging: the identical list is skipped, the same name with other apps comes in as "(imported)", the same id gets a new id, a new one comes in; toast counts them', JSON.stringify(L.map(x => x.name)) === '["Samsung bloat","Games","Old list","Games (imported)","Another","Brand new"]' && new Set(L.map(x => x.id)).size === L.length && /Added 3 lists \(1 already here\), 1 renamed/.test(await toast()), JSON.stringify(L) + await toast());
  check('what was there before is untouched', await ev(() => customLists[0].id === 'list_1' && customLists[0].packages.length === 2 && customLists[1].id === 'list_2'));

  // ---- replace, with the quick list ----
  await ev(() => { window.__pick = { name: 'full.json', text: JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2026-03-03T00:00:00Z', quickListId: 'b2', lists: [{ id: 'b1', name: 'One', description: '', packages: ['a.one'], updatedAt: 1 }, { id: 'b2', name: 'Two', description: 'second', packages: ['a.two', 'a.three'], updatedAt: 2 }] }) }; });
  await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(150);
  sh2 = await sheet();
  check('the question says how many lists Replace removes', /Your \d+ lists are removed first/.test(sh2.rows[1]), sh2.rows[1]);
  await pickRow(1);
  check('Replace leaves exactly the backup\'s lists', JSON.stringify(await lists()) === '["One:1","Two:2"]' && /Restored 2 lists from full\.json/.test(await toast()), JSON.stringify(await lists()) + await toast());
  check('the quick list is the one the old quick list pointed to no longer exists, so it follows the backup (Two)', await ev(() => window.__quick === 'b2' && quickListId === 'b2'), await ev(() => window.__quick));
  await ev(() => { window.__pick = { name: 'full.json', text: JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2026-03-03T00:00:00Z', quickListId: '', lists: [{ id: 'c1', name: 'Solo', description: '', packages: ['a.solo'], updatedAt: 1 }] }) }; });
  await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(150); await pickRow(1);
  check('a quick list that is not in the backup is cleared', await ev(() => window.__quick === '' && quickListId === ''));

  // ---- nothing here yet: restored at once, no question ----
  await ev(() => { customLists = []; persistSavedLists(); renderSavedLists(); window.__pick = { name: 'full.json', text: JSON.stringify({ app: 'adb-app-manager', format: 1, kind: 'saved-lists', exportedAt: '2026-03-03T00:00:00Z', quickListId: '', lists: [{ id: 'c1', name: 'Solo', description: '', packages: ['a.solo'], updatedAt: 1 }] }) }; });
  await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(150);
  check('with no lists at all, the backup is simply restored (no question)', JSON.stringify(await lists()) === '["Solo:1"]' && !(await sheet()).open);
  check('a backup of nothing says so (nothing to back up)', await ev(() => { customLists = []; renderSavedLists(); return true; }));
  await page.click('#lbNow'); await wait(100);
  check('Back up now with no lists writes nothing and says so', /no saved lists to back up/.test(await toast()));

  // ---- bad files ----
  const badFiles = [
    ['not JSON at all {', /not a backup file/],
    [JSON.stringify([1, 2, 3]), /not a saved-lists backup/],
    [JSON.stringify({ app: 'someone-else', kind: 'saved-lists', format: 1, lists: [] }), /not a saved-lists backup/],
    [JSON.stringify({ app: 'adb-app-manager', kind: 'presets', format: 1, lists: [] }), /not a saved-lists backup/],
    [JSON.stringify({ app: 'adb-app-manager', kind: 'saved-lists', format: 2, lists: [{ name: 'x', packages: [] }] }), /newer version of the app \(format 2\)/],
    [JSON.stringify(bk([])), /no usable lists/],
    [JSON.stringify(bk([{ name: '', packages: ['a.b'] }, { name: 'x' }, 5, null])), /no usable lists/]
  ];
  for (const [text, re] of badFiles) {
    await ev(t => { window.__pick = { name: 'bad.json', text: t }; }, text);
    const before = JSON.stringify(await lists());
    await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(120);
    check('a bad file is refused in words (' + re + ') and nothing changes', re.test(await toast()) && JSON.stringify(await lists()) === before && !(await sheet()).open, await toast());
  }
  await ev(() => { window.__pick = { error: 'the file is over 1 MB, too big for this' }; });
  await page.click('#listsBackupCard button:has-text("Restore from a file")'); await wait(120);
  check('a file the app could not read says why', /Could not read that file: the file is over 1 MB/.test(await toast()));

  // ---- what a file may bring in is cleaned ----
  const parsed = await ev(() => listsParse(JSON.stringify({ app: 'adb-app-manager', kind: 'saved-lists', format: 1, quickListId: 5, lists: [
    { id: 'ok', name: '  Fine  ', description: 'd'.repeat(900), packages: ['a.b', 'a.b', 'javascript:alert(1)', 'x y', '', 5, null, 'ok.pkg_1', 'q'.repeat(300)] },
    { id: 'ok', name: 'Same id twice', packages: ['c.d'] },
    { id: '../../evil', name: 'Odd id', packages: ['e.f'] },
    { name: 'N'.repeat(200), packages: ['g.h'] },
    { name: 'No packages array' }, 'string', null] })));
  check('a file is cleaned: names trimmed and cut, descriptions cut, only real package names kept once, a repeated or odd id replaced, entries that are not lists dropped', parsed.lists.length === 4 && parsed.lists[0].name === 'Fine' && parsed.lists[0].description.length === 500 && JSON.stringify(parsed.lists[0].packages) === '["a.b","ok.pkg_1"]' && new Set(parsed.lists.map(l => l.id)).size === 4 && parsed.lists[2].id !== '../../evil' && parsed.lists[3].name.length === 80 && parsed.dropped === 3 && parsed.quickListId === '', JSON.stringify(parsed));
  const many = await ev(() => { const ls = []; for (let i = 0; i < 260; i++) ls.push({ id: 'i' + i, name: 'L' + i, packages: ['a.b'] }); const r = listsParse(JSON.stringify({ app: 'adb-app-manager', kind: 'saved-lists', format: 1, lists: ls })); return { n: r.lists.length, dropped: r.dropped }; });
  check('at most 200 lists are taken from a file', many.n === 200 && many.dropped === 60, JSON.stringify(many));
  const pk = await ev(() => { const p = []; for (let i = 0; i < 6000; i++) p.push('a.p' + i); return listsParse(JSON.stringify({ app: 'adb-app-manager', kind: 'saved-lists', format: 1, lists: [{ id: 'x', name: 'Big', packages: p }] })).lists[0].packages.length; });
  check('and at most 5000 apps in a list', pk === 5000, String(pk));

  // ---- Use Downloads again ----
  await ev(() => { customLists = [{ id: 'q', name: 'Q', description: '', packages: ['a.b'], updatedAt: 1 }]; renderSavedLists(); });
  await page.click('#lbDefault'); await wait(100);
  check('Use Downloads goes back to the default, switches Automatic off, and the folder controls go off', await ev(() => !lbState().uri && lbState().auto === false && document.getElementById('lbAuto').disabled && document.getElementById('lbFromFolder').disabled) && /Download\/ADB App Manager \(the default\)/.test(await note()));

  // ---- round trip: what is backed up is what comes back ----
  await ev(() => { customLists = [{ id: 'a1', name: 'Ünïcode "quotes" & <tags>', description: 'line\nbreak', packages: ['com.a.b', 'com.c.d'], updatedAt: 42 }]; renderSavedLists(); });
  const text = await ev(() => lbText());
  const back = await ev(t => listsParse(t), text);
  check('a backup read back is the lists that were backed up (names with quotes, tags and non-ASCII, a line break in a description)', back.lists.length === 1 && back.lists[0].name === 'Ünïcode "quotes" & <tags>' && back.lists[0].description === 'line\nbreak' && JSON.stringify(back.lists[0].packages) === '["com.a.b","com.c.d"]' && back.lists[0].updatedAt === 42 && back.lists[0].id === 'a1');
  check('and the list is shown as text, not markup', await ev(() => !document.querySelector('#savedListsContainer b') && /<tags>/.test(document.getElementById('savedListsContainer').innerText)));

  await ev(() => { switchView('saved-lists'); document.getElementById('listsBackupCard').scrollIntoView(); }); await wait(150);
  await page.screenshot({ path: 'lists_backup.png' });
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
