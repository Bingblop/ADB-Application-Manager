// v5.8 archive browser extras: install an APK from inside an archive, open nested archives, compare two archives (+ line diff), remembered extract folder.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const store = {};
  await page.exposeFunction('__kvSave', (k, v) => { store[k] = v; });
  await page.addInitScript(() => {
    window.__calls = { open: [], stage: [], release: [], diff: [], extract: [], intent: [], read: [] };
    const T = Date.UTC(2024, 0, 8);
    // two archive "files": a bundle with a nested apk, and a second archive to compare against
    const A = { name: 'bundle.xapk', files: [
      { p: 'manifest.json', s: 40, c: 30, m: 8, kind: 'text', text: '{"version": 1}\n' },
      { p: 'base.apk', s: 3000, c: 2900, m: 8, kind: 'hex', hex: '' },
      { p: 'config.arm64.apk', s: 1000, c: 900, m: 8, kind: 'hex', hex: '' },
      { p: 'data/readme.txt', s: 10, c: 10, m: 0, kind: 'text', text: 'one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve\n' },
      { p: 'AndroidManifest.xml', s: 300, c: 200, m: 8, kind: 'axml', text: '<manifest>\n  <uses-permission name="CAMERA"/>\n  <uses-permission name="INTERNET"/>\n</manifest>\n' },
    ] };
    const NESTED = { name: 'base.apk', files: [{ p: 'classes.dex', s: 99, c: 80, m: 8, kind: 'hex', hex: '' }, { p: 'res/a.xml', s: 5, c: 5, m: 0, kind: 'text', text: 'x' }] };
    const B = { name: 'bundle-v2.xapk', files: [
      { p: 'manifest.json', s: 40, c: 30, m: 8, kind: 'text', text: '{"version": 2}\n' },
      { p: 'base.apk', s: 3000, c: 2900, m: 8, kind: 'hex', hex: '' },
      { p: 'data/readme.txt', s: 10, c: 10, m: 0, kind: 'text', text: 'one\ntwo\nthree\nfour\nfive\nSIX CHANGED\nseven\neight\nnine\nten\neleven\ntwelve\nthirteen\n' },
      { p: 'AndroidManifest.xml', s: 340, c: 220, m: 8, kind: 'axml', text: '<manifest>\n  <uses-permission name="CAMERA"/>\n  <uses-permission name="INTERNET"/>\n  <uses-permission name="READ_SMS"/>\n</manifest>\n' },
      { p: 'extra/new.bin', s: 8, c: 8, m: 0, kind: 'hex', hex: '00000000  de ad  |..|\n' },
    ] };
    const files = { '/sd/bundle.xapk': A, '/sd/bundle-v2.xapk': B };
    const nestedPaths = {};
    const arcOf = p => files[p] || nestedPaths[p];
    const children = (arc, dir) => {
      const dirs = new Map(), out = [];
      for (const f of arc.files) {
        if (!f.p.startsWith(dir) || f.p.length <= dir.length) continue;
        const rest = f.p.slice(dir.length), slash = rest.indexOf('/');
        if (slash < 0) out.push({ n: rest, p: f.p, d: false, s: f.s, c: f.c, t: T, m: f.m, e: false });
        else { const n = rest.slice(0, slash); const a = dirs.get(n) || { n, p: dir + n + '/', d: true, s: 0, c: 0, t: T, m: -1, e: false, f: 0 }; a.f++; a.s += f.s; dirs.set(n, a); }
      }
      return [...dirs.values(), ...out];
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      hasAllFilesAccess() { return true; }, fmList(path) { return JSON.stringify({ path, entries: [] }); },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || ''; }, saveSetting(k, v) { window.__kv = window.__kv || {}; window.__kv[k] = v; window.__kvSave(k, v); },
      archiveOpen(path) {
        window.__calls.open.push(path);
        const a = arcOf(path);
        if (!a) return JSON.stringify({ ok: false, error: 'Not a zip archive (no central directory found)' });
        return JSON.stringify({ ok: true, path, name: a.name, count: a.files.length, files: a.files.length, size: 4000, zip64: false, staged: false, apk: false, editable: !nestedPaths[path], whyNot: '' });
      },
      archiveList(path, dir, q, off, lim) { const a = arcOf(path); const all = q ? a.files.filter(f => f.p.includes(q)).map(f => ({ n: f.p.split('/').pop(), p: f.p, d: false, s: f.s, c: f.c, t: T, m: f.m, e: false })) : children(a, dir); return JSON.stringify({ ok: true, dir, query: q, total: all.length, offset: off, more: false, entries: all }); },
      archiveRead(path, entry) {
        window.__calls.read.push(path + '::' + entry);
        const a = arcOf(path); const f = a && a.files.find(x => x.p === entry);
        if (!f) return JSON.stringify({ ok: false, error: 'Not found in the archive: ' + entry });
        const r = { ok: true, name: f.p, size: f.s, csize: f.c, method: f.m, crc: 1, mtime: T, kind: f.kind };
        if (f.kind === 'text') { r.text = f.text; r.truncated = false; r.editable = false; }
        if (f.kind === 'axml') r.text = f.text;
        if (f.kind === 'hex') r.hex = f.hex || '';
        return JSON.stringify(r);
      },
      archiveStage(path, entry, kind) {
        window.__calls.stage.push([path, entry, kind]);
        if (window.__stageFail) { setTimeout(() => window.onArchiveStaged({ ok: false, op: 'stage', kind, error: 'Not enough free space in the app cache for 3.0 MB' }), 20); return 'started'; }
        const ref = kind === 'nested' ? '/cache/archive_nested/1_' + entry.split('/').pop() : '/cache/archive_stage/1_' + entry.split('/').pop();
        if (kind === 'nested') nestedPaths[ref] = NESTED;
        setTimeout(() => window.onArchiveStaged({ ok: true, op: 'stage', kind, ref, name: entry.split('/').pop(), size: 3000 }), 20);
        return 'started';
      },
      archiveRelease(p) { window.__calls.release.push(p); delete nestedPaths[p]; },
      archiveClose() { window.__calls.closeAll = (window.__calls.closeAll || 0) + 1; },
      archiveDiff(a, b) {
        window.__calls.diff.push([a, b]);
        setTimeout(() => {
          const fa = arcOf(a), fb = files[b] || (b === 'com.example.app' ? B : null);
          if (!fb) { window.onArchiveDiff({ ok: false, op: 'diff', error: 'No installed app named ' + b }); return; }
          const mapA = new Map(fa.files.map(f => [f.p, f])), mapB = new Map(fb.files.map(f => [f.p, f]));
          const added = [], removed = [], changed = []; let same = 0;
          for (const [p, f] of mapB) { const o = mapA.get(p); if (!o) added.push({ p, a: -1, b: f.s }); else if (o.s !== f.s || (o.text || '') !== (f.text || '')) changed.push({ p, a: o.s, b: f.s }); else same++; }
          for (const [p, f] of mapA) if (!mapB.has(p)) removed.push({ p, a: f.s, b: -1 });
          window.onArchiveDiff({ ok: true, op: 'diff', a: { path: a, name: fa.name, count: fa.files.length }, b: { path: b, name: fb.name, count: fb.files.length }, same, truncated: false, added, removed, changed });
        }, 30);
        return 'started';
      },
      archiveExtract(path, entry, dest) { window.__calls.extract.push([path, entry, dest]); return 'started'; },
      // the installer hand-off
      inspectInstallSource(ref) { window.__calls.intent.push(ref); },
      pickInstallerFile() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(350);
  const sleep = ms => page.waitForTimeout(ms);
  const toast = () => page.locator('#toastMsg').innerText();
  await page.evaluate(() => switchView('files')); await sleep(150);
  await page.evaluate(() => arcOpen('/sd/bundle.xapk')); await sleep(300);
  const rowTexts = () => page.locator('#arcList .perm-row').allInnerTexts();
  const tapRow = async name => { await page.locator('#arcList .perm-row', { hasText: name }).first().locator('.perm-info').click(); await sleep(150); };
  const sheetBtns = () => page.locator('#arcModalBtns button').allInnerTexts();

  // 1) Install an APK from inside an archive.
  await tapRow('config.arm64.apk');
  const btns1 = await sheetBtns();
  console.log('1. an .apk entry offers Install and Open inside:', btns1.some(t => /Install/.test(t)) && btns1.some(t => /Open inside/.test(t)), JSON.stringify(btns1));
  await page.locator('#arcModalBtns button', { hasText: 'Install' }).click(); await sleep(250);
  const st1 = await page.evaluate(() => ({ stage: window.__calls.stage.slice(), tab: currentViewName(), inspected: window.__calls.intent.slice(), busy: arcBusy }));
  console.log('   staged through the bridge, then handed to the Installer tab:', JSON.stringify(st1.stage[0]) === JSON.stringify(['/sd/bundle.xapk', 'config.arm64.apk', 'install']) && st1.tab === 'installer' && st1.inspected[0] === '/cache/archive_stage/1_config.arm64.apk' && !st1.busy, JSON.stringify(st1));
  await page.evaluate(() => switchView('files')); await sleep(150);
  console.log('   the archive is still open when you come back:', await page.evaluate(() => !!arc && getComputedStyle(document.getElementById('arcCard')).display !== 'none'));
  await page.evaluate(() => arcGo('data/')); await sleep(60);
  await tapRow('readme.txt');
  const btns2 = await sheetBtns();
  console.log('   plain files do not offer Install / Open inside:', !btns2.some(t => /Install|Open inside/.test(t)), JSON.stringify(btns2));
  await page.evaluate(() => { arcCloseModal(); arcGo(''); });
  // stage failure
  await page.evaluate(() => { window.__stageFail = true; });
  await tapRow('config.arm64.apk'); await page.locator('#arcModalBtns button', { hasText: 'Install' }).click(); await sleep(200);
  console.log('   a staging failure is reported and nothing is left busy:', /Not enough free space/.test(await toast()) && !(await page.evaluate(() => arcBusy)));
  await page.evaluate(() => { window.__stageFail = false; });

  // 2) Nested archive.
  await tapRow('base.apk');
  await page.locator('#arcModalBtns button', { hasText: 'Open inside' }).click(); await sleep(300);
  const n1 = await page.evaluate(() => ({ title: document.getElementById('arcTitle').innerText, meta: document.getElementById('arcMeta').innerText, stack: arcStack.length, note: document.getElementById('arcNote').innerText, add: getComputedStyle(document.getElementById('arcAddBtn')).display, rows: [...document.querySelectorAll('#arcList .perm-name')].map(e => e.innerText) }));
  console.log('2. opening inside shows the nested archive with a chain title:', /bundle\.xapk › base\.apk/.test(n1.title) && /inside bundle\.xapk/.test(n1.meta) && n1.stack === 1 && n1.rows.length === 2, JSON.stringify([n1.title, n1.meta.split('\n')[1]]));
  console.log('   it is view-only (no add/folder, reason shown):', /View only/.test(n1.note) && /inside another archive/.test(n1.note) && n1.add === 'none');
  await tapRow('classes.dex');
  const nb = await sheetBtns();
  console.log('   entries in it have no rename/delete:', !nb.some(t => /Rename|Delete/.test(t)) && nb.some(t => /Extract/.test(t)), JSON.stringify(nb));
  await page.evaluate(() => arcCloseModal());
  // nesting twice and back out with Back
  const back1 = await page.evaluate(() => handleAndroidBack()); await sleep(200);
  const n2 = await page.evaluate(() => ({ stack: arcStack.length, title: document.getElementById('arcTitle').innerText, released: window.__calls.release.slice(), rows: [...document.querySelectorAll('#arcList .perm-name')].length, opened: window.__calls.open.length }));
  console.log('   Back leaves the nested archive, releases it and shows the parent again:', back1 === true && n2.stack === 0 && /bundle\.xapk$/.test(n2.title) && n2.released.length === 1 && n2.released[0].includes('/archive_nested/') && n2.rows >= 4, JSON.stringify(n2));
  // a nested archive that cannot be opened is released and the parent stays
  await page.evaluate(() => { window.AndroidBridge.archiveOpen = p => JSON.stringify({ ok: false, error: 'Not a zip archive (no central directory found)' }); });
  await page.evaluate(() => arcOpenNested({ ref: '/cache/archive_nested/9_x.apk', name: 'x.apk' })); await sleep(100);
  console.log('   an unreadable nested file is reported, released, and the parent stays open:', /Not a zip archive/.test(await toast()) && (await page.evaluate(() => arcStack.length)) === 0 && (await page.evaluate(() => window.__calls.release.some(r => r.endsWith('9_x.apk')))));
  await page.evaluate(() => location.reload()); await sleep(400);
  await page.evaluate(() => switchView('files')); await sleep(150);
  await page.evaluate(() => arcOpen('/sd/bundle.xapk')); await sleep(300);

  // 3) Compare with another archive.
  await page.locator('#arcCompareBtn').click(); await sleep(100);
  const cmp = await page.evaluate(() => ({ label: document.getElementById('arcInputLabel').innerText, val: document.getElementById('arcInput').value, name: document.getElementById('arcModalName').innerText }));
  console.log('3. Compare asks for the other archive (defaults to the same folder):', /Compare archives/.test(cmp.name) && /package name/.test(cmp.label) && cmp.val === '/sd/', JSON.stringify(cmp));
  await page.fill('#arcInput', '/sd/bundle-v2.xapk');
  await page.locator('#arcInputConfirm').click(); await sleep(300);
  const d1 = await page.evaluate(() => ({ diff: window.__calls.diff.slice(), head: document.getElementById('arcDiffHead').innerText, rows: [...document.querySelectorAll('#arcList .perm-row')].map(r => r.innerText.replace(/\n/g, ' | ')), crumbs: getComputedStyle(document.getElementById('arcCrumbs')).display, kv: window.__kv && window.__kv.arc_last_cmp }));
  console.log('   the app compares the two paths and shows a summary:', JSON.stringify(d1.diff[0]) === JSON.stringify(['/sd/bundle.xapk', '/sd/bundle-v2.xapk']) && /bundle\.xapk .*bundle-v2\.xapk/.test(d1.head) && /3 changed|≠ Changed 3/.test(d1.head) && /\+ New 1/.test(d1.head) && /− Gone 1/.test(d1.head) && /1 unchanged/.test(d1.head), JSON.stringify(d1.head.replace(/\n/g, ' ')));
  console.log('   changed / new / gone entries listed with sizes:', d1.rows.length === 5 && d1.rows.some(r => /^≠ ?AndroidManifest\.xml.*300 B → 340 B/.test(r)) && d1.rows.some(r => /^\+ ?extra\/new\.bin.*8 B/.test(r)) && d1.rows.some(r => /^− ?config\.arm64\.apk.*1000 B/.test(r)), JSON.stringify(d1.rows));
  console.log('   breadcrumbs hidden during a comparison; last target remembered:', d1.crumbs === 'none' && d1.kv === JSON.stringify('/sd/bundle-v2.xapk'));
  // filters
  await page.locator('#arcDiffHead .filter-pill', { hasText: 'New' }).click(); await sleep(60);
  const only = (await rowTexts()).length;
  await page.locator('#arcDiffHead .filter-pill', { hasText: 'Gone' }).click(); await sleep(60);
  const gone = (await rowTexts()).length;
  await page.fill('#arcSearch', 'readme'); await sleep(450);
  await page.locator('#arcDiffHead .filter-pill', { hasText: 'All' }).click(); await sleep(60);
  const searched = (await rowTexts()).length;
  console.log('   filter chips and the search box narrow the list:', only === 1 && gone === 1 && searched === 1, [only, gone, searched].join(','));
  await page.fill('#arcSearch', ''); await sleep(450);
  // open a changed entry: line diff
  await tapRow('readme.txt');
  const cb = await sheetBtns();
  console.log('   a changed entry offers Show differences / New / Old:', cb.some(t => /Show differences/.test(t)) && cb.some(t => /New/.test(t)) && cb.some(t => /Old/.test(t)) && !cb.some(t => /Rename|Delete/.test(t)), JSON.stringify(cb));
  await page.locator('#arcModalBtns button', { hasText: 'Show differences' }).click(); await sleep(150);
  const ld = await page.evaluate(() => ({ add: [...document.querySelectorAll('#arcViewText .dif-add')].map(e => e.textContent), del: [...document.querySelectorAll('#arcViewText .dif-del')].map(e => e.textContent), gap: document.querySelectorAll('#arcViewText .dif-gap').length, meta: document.getElementById('arcViewMeta').innerText }));
  console.log('   line diff: -six, +SIX CHANGED, +thirteen with a collapsed gap and a summary:', JSON.stringify(ld.del) === JSON.stringify(['- six']) && JSON.stringify(ld.add) === JSON.stringify(['+ SIX CHANGED', '+ thirteen']) && ld.gap >= 1 && /\+2 \/ −1 lines/.test(ld.meta), JSON.stringify(ld));
  await page.evaluate(() => arcCloseModal());
  // compiled-XML diff (permissions added)
  await tapRow('AndroidManifest.xml'); await page.locator('#arcModalBtns button', { hasText: 'Show differences' }).click(); await sleep(150);
  const md = await page.evaluate(() => [...document.querySelectorAll('#arcViewText .dif-add')].map(e => e.textContent));
  console.log('   manifest diff shows the new permission:', md.length === 1 && /READ_SMS/.test(md[0]), JSON.stringify(md));
  await page.evaluate(() => arcCloseModal());
  // New / Old views read the right archive
  await tapRow('manifest.json');
  await page.locator('#arcModalBtns button', { hasText: 'Old' }).click(); await sleep(100);
  const oldTxt = await page.locator('#arcViewText').innerText();
  await page.locator('#arcModalBtns button', { hasText: 'New' }).click(); await sleep(100);
  const newTxt = await page.locator('#arcViewText').innerText();
  console.log('   Old / New read the matching archive:', /"version": 1/.test(oldTxt) && /"version": 2/.test(newTxt));
  await page.evaluate(() => arcCloseModal());
  // a removed file reads from A, an added file from B; extract goes to the same side
  await tapRow('config.arm64.apk');
  await page.locator('#arcModalBtns button', { hasText: 'Extract' }).click(); await sleep(60);
  await page.locator('#arcInputConfirm').click(); await sleep(100);
  const ex = await page.evaluate(() => window.__calls.extract.slice(-1)[0]);
  console.log('   extracting a removed file reads it from the first archive:', ex[0] === '/sd/bundle.xapk' && ex[1] === 'config.arm64.apk', JSON.stringify(ex));
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', files: 1, bytes: 1000, skipped: 0, dest: '/x' })); await sleep(60);
  // Back closes the comparison first
  const bk = await page.evaluate(() => handleAndroidBack()); await sleep(150);
  console.log('   Back closes the comparison and returns to the folder view:', bk === true && (await page.evaluate(() => arcDiff === null)) && (await page.locator('#arcCrumbs').evaluate(e => getComputedStyle(e).display !== 'none')) && (await rowTexts()).length >= 4);
  // compare with an installed app (package name) and an error
  await page.locator('#arcCompareBtn').click(); await sleep(80);
  const lastCmp = await page.inputValue('#arcInput');
  console.log('   the last comparison target is pre-filled next time:', lastCmp === '/sd/bundle-v2.xapk');
  await page.fill('#arcInput', 'com.example.app'); await page.locator('#arcInputConfirm').click(); await sleep(200);
  console.log('   an installed app\'s package name is accepted:', (await page.evaluate(() => window.__calls.diff.slice(-1)[0][1])) === 'com.example.app' && (await page.evaluate(() => !!arcDiff)));
  await page.evaluate(() => arcDiffClose()); await sleep(60);
  await page.locator('#arcCompareBtn').click(); await sleep(80);
  await page.fill('#arcInput', 'org.nothing.here'); await page.locator('#arcInputConfirm').click(); await sleep(200);
  console.log('   an unknown target shows the error and leaves the browser as it was:', /No installed app named/.test(await toast()) && !(await page.evaluate(() => arcBusy)) && (await page.evaluate(() => arcDiff === null)));

  // 4) Identical archives
  await page.evaluate(() => { window.AndroidBridge.archiveDiff = (a, b) => { setTimeout(() => window.onArchiveDiff({ ok: true, op: 'diff', a: { path: a, name: 'a', count: 2 }, b: { path: b, name: 'a', count: 2 }, same: 2, truncated: false, added: [], removed: [], changed: [] }), 10); return 'started'; }; });
  await page.locator('#arcCompareBtn').click(); await sleep(60); await page.locator('#arcInputConfirm').click(); await sleep(150);
  console.log('4. identical archives say so:', /identical files/.test(await page.locator('#arcList').innerText()));
  await page.evaluate(() => arcDiffClose());

  // 5) The extract folder is remembered.
  await page.evaluate(() => arcGo('data/')); await sleep(60);
  await tapRow('readme.txt'); await page.locator('#arcModalBtns button', { hasText: 'Extract' }).click(); await sleep(60);
  const firstDefault = await page.inputValue('#arcInput');
  await page.fill('#arcInput', '/storage/emulated/0/Documents/out');
  await page.locator('#arcInputConfirm').click(); await sleep(100);
  await page.evaluate(() => onArchiveResult({ ok: true, op: 'extract', files: 1, bytes: 10, skipped: 0, dest: '/x' })); await sleep(60);
  await page.evaluate(() => arcGo('')); await sleep(60);
  await tapRow('manifest.json'); await page.locator('#arcModalBtns button', { hasText: 'Extract' }).click(); await sleep(60);
  const secondDefault = await page.inputValue('#arcInput');
  console.log('5. extract defaults to the folder used last time:', firstDefault === '/storage/emulated/0/Download/bundle' && secondDefault === '/storage/emulated/0/Documents/out', JSON.stringify([firstDefault, secondDefault]));
  await page.evaluate(() => arcCloseModal());
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
