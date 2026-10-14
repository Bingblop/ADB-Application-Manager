// File Manager top card: the list starts high on a phone. The card holds the path bar (Go), the search bar (Search) and one row of the actions used
// most (Select, ＋ Folder, ＋ File); "⋯ More" beside the title opens a sheet with the rest: where to search, Include subfolders, Search inside archives,
// Search help, Show hidden files, Show thumbnails, Refresh, Clear thumbnail cache, Storage, /data/local/tmp, /system and the added storage. The ".." row
// begins near the top of a 360x800 and a 320x640 screen (bottom bar on) and the first file is whole above the bottom bar; every moved control keeps its
// id, is reached through ⋯ More, works from there and answers a 44x44px tap; the sheet closes with ✕, its backdrop and Back; no sideways scroll at
// 320, 360 and 412px, in English and German.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = '/storage/emulated/0';
const FILES = {
  [ROOT + '/Download/report.pdf']: { size: 5000, bin: true }, [ROOT + '/Download/notes.txt']: 'notes', [ROOT + '/DCIM']: null, [ROOT + '/Music']: null,
  [ROOT + '/a.txt']: 'A', [ROOT + '/.nomedia']: '', '/data/local/tmp/x.apk': { size: 100, bin: true }, '/system/build.prop': 'ro.x=1',
};
const MOVED = ['fmSearchScope', 'fmSearchNested', 'fmSearchArc', 'fmSearchHelp', 'fmHiddenChk', 'fmThumbChk', 'fmThumbClearBtn', 'fmRefreshBtn', 'fmRootsRow', 'fmPlacesRow'];

(async () => {
  const b = await chromium.launch();
  const open = async (width, height, opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width, height }, colorScheme: opts.light ? 'light' : 'dark' });
    page.bottomNav = true;                                           // the app's default: the bottom bar, no tab bar at the top
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.on('dialog', d => d.accept().catch(() => {}));
    await page.addInitScript(fm.initScript, { files: FILES, priv: true, kv: { perm_intro_v61: '1' } });
    await page.addInitScript(() => { window.__picks = 0; window.AndroidBridge.pickAddStorage = () => { window.__picks++; }; window.AndroidBridge.vibrate = () => {}; });
    await page.goto(PAGE);
    await sleep(400);
    if (opts.lang) { await page.evaluate(l => I18N.set(l), opts.lang); await sleep(400); }
    await page.evaluate(() => switchView('files'));
    await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 2);
    await page.evaluate(() => { const t = document.getElementById('toast'); if (t) t.classList.remove('show'); window.scrollTo(0, 0); });
    await sleep(300);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const isOpen = page => ev(page, () => document.getElementById('fmMoreModal').classList.contains('show'));
  const openMore = async page => { await page.click('#fmMoreBtn'); await sleep(400); };
  // where the list starts, and what the top card shows
  const layout = page => ev(page, () => {
    const r = el => el.getBoundingClientRect();
    const up = document.getElementById('fmUpRow'), first = document.querySelector('#fmList .perm-row[data-i]');
    const nav = document.getElementById('bnav'), navTop = nav && nav.getClientRects().length ? r(nav).top : innerHeight;
    const shown = id => { const e = document.getElementById(id); return !!e && e.checkVisibility({ checkVisibilityCSS: true }) && r(e).height > 0 && r(e).bottom <= navTop; };
    const acts = [...document.querySelectorAll('#fmTopCard .fm-actions button')];
    return {
      up: Math.round(r(up).top), first: Math.round(r(first).top), firstBottom: Math.round(r(first).bottom), navTop: Math.round(navTop),
      acts: acts.map(a => a.innerText.trim()), oneRow: acts.every(a => Math.abs(r(a).top - r(acts[0]).top) < 1),
      main: ['fmPathInput', 'fmSearchInput', 'fmSearchBtn', 'fmSelectBtn', 'fmMoreBtn', 'fmCurrentPath', 'fmUpRow'].filter(id => !shown(id)),
      go: !!document.querySelector('#fmTopCard .list-toolbar .term-run-btn[onclick^="fmGo"]'),
      movedHidden: ['fmSearchScope', 'fmSearchNested', 'fmSearchArc', 'fmHiddenChk', 'fmThumbChk', 'fmThumbClearBtn', 'fmRefreshBtn', 'fmRootsRow'].every(id => !document.getElementById(id).checkVisibility({ checkVisibilityCSS: true })),
      docW: document.documentElement.scrollWidth, W: innerWidth,
    };
  });

  // ---------------------------------------------------------------- 1) the list starts high, the main controls stay on the card
  for (const [w, h, lang, light] of [[360, 800], [360, 800, 'de'], [320, 640], [320, 640, 'de', true], [412, 915]]) {
    const page = await open(w, h, { lang, light });
    const m = await layout(page);
    const tag = w + 'x' + h + (lang ? ' ' + lang : '') + (light ? ' light' : '');
    check(tag + ': the ".." row starts at most 340px down, the first file at most 400px', m.up <= 340 && m.first <= 400, JSON.stringify(m));
    check(tag + ': the first file is whole above the bottom bar', m.firstBottom <= m.navTop, JSON.stringify(m));
    check(tag + ': path bar with Go, search bar, Select, ⋯ More, the folder line and ".." are on screen', m.go && !m.main.length, JSON.stringify(m.main));
    check(tag + ': Select, ＋ Folder and ＋ File stay one row', m.acts.length === 3 && m.oneRow, JSON.stringify(m.acts));
    check(tag + ': the moved options are kept in the page, hidden until ⋯ More', m.movedHidden);
    check(tag + ': no sideways scroll', m.docW <= m.W, m.docW + ' > ' + m.W);
    await openMore(page);
    const s = await ev(page, () => {
      const sheet = document.querySelector('#fmMoreModal .modal-sheet');
      return { docW: document.documentElement.scrollWidth, W: innerWidth, inside: sheet.scrollWidth <= sheet.clientWidth + 1,
        out: [...sheet.querySelectorAll('button, select, label')].filter(e => { const r = e.getBoundingClientRect(), q = sheet.getBoundingClientRect(); return r.width && (r.left < q.left - 1 || r.right > q.right + 1); }).map(e => e.id || e.innerText.trim()) };
    });
    check(tag + ': the open sheet has no sideways scroll and nothing in it runs past its edges', s.docW <= s.W && s.inside && !s.out.length, JSON.stringify(s));
    await page.close();
  }

  // ---------------------------------------------------------------- 2) ⋯ More: what it is, how it opens and closes
  let page = await open(360, 800);
  const btn = await ev(page, () => { const e = document.getElementById('fmMoreBtn'); return { text: e.innerText.trim(), popup: e.getAttribute('aria-haspopup'), exp: e.getAttribute('aria-expanded'), ctl: e.getAttribute('aria-controls'), cls: e.className, inTitleRow: !!e.closest('.fm-head') }; });
  check('2. "⋯ More" is a labelled button beside the title, in the File Manager button style, that says it opens a dialog', btn.text === '⋯ More' && btn.popup === 'dialog' && btn.exp === 'false' && btn.ctl === 'fmMoreModal' && /batch-tool-link/.test(btn.cls) && btn.inTitleRow, JSON.stringify(btn));
  check('   every id of the old card is still in the page', (await ev(page, ids => ids.filter(id => !document.getElementById(id)), MOVED.concat(['fmPathInput', 'fmSearchInput', 'fmSearchBtn', 'fmSelectBtn', 'fmTopCard', 'fmListCard', 'fmAccessHint']))).length === 0);
  await openMore(page);
  const sh = await ev(page, () => {
    const m = document.getElementById('fmMoreModal'), sheet = m.querySelector('.modal-sheet');
    return { open: m.classList.contains('show'), exp: document.getElementById('fmMoreBtn').getAttribute('aria-expanded'), role: sheet.getAttribute('role'), modal: sheet.getAttribute('aria-modal'),
      title: document.getElementById(sheet.getAttribute('aria-labelledby')).innerText, focus: sheet.contains(document.activeElement),
      sections: [...sheet.querySelectorAll('.mode-section-label')].map(e => e.innerText.trim()), note: sheet.querySelector('.modal-helper').innerText };
  });
  check('   tapping it opens the sheet "File Manager options" (a modal dialog, focus inside) with Search options, In the list and Go to', sh.open && sh.exp === 'true' && sh.role === 'dialog' && sh.modal === 'true' && sh.title === 'File Manager options' && sh.focus && sh.sections.join('|') === 'Search options|In the list|Go to', JSON.stringify(sh));
  check('   the card\'s old note about access is at the foot of the sheet', /All-files access; system folders need ADB \/ Shizuku \/ Root/.test(sh.note), sh.note);
  // the controls in the open sheet: drawn 36px tall at least, a 44x44 tap each that no neighbour takes (as t325 measures the tabs)
  for (const w of [360, 320]) {
    await page.setViewportSize({ width: w, height: w === 320 ? 640 : 800 }); await sleep(250);
    const t = await ev(page, () => {
      const out = { n: 0, small: [], missed: [] };
      for (const el of document.querySelectorAll('#fmMoreModal .batch-tool-link, #fmMoreModal .fm-opt, #fmMoreModal .sheet-close')) {
        el.scrollIntoView({ block: 'center' });
        const rc = el.getBoundingClientRect(); if (!rc.width) continue;
        out.n++;
        const name = el.id || el.innerText.trim().slice(0, 16);
        if (rc.height < 35.5) out.small.push(name + ' ' + Math.round(rc.height));
        const cx = rc.left + rc.width / 2, cy = rc.top + rc.height / 2, w = Math.max(rc.width, 44), h = Math.max(rc.height, 44);
        const pts = [[cx, cy], [cx, cy - h / 2 + 1], [cx, cy + h / 2 - 1], [cx - w / 2 + 1, cy], [cx + w / 2 - 1, cy]];
        if (pts.some(([x, y]) => { const e = document.elementFromPoint(x, y); return !e || !(e === el || el.contains(e)); })) out.missed.push(name);
      }
      return out;
    });
    check('   ' + w + 'px: each of the ' + t.n + ' controls in the sheet is at least 36px tall and answers a 44x44 tap', t.n >= 12 && !t.small.length && !t.missed.length, JSON.stringify(t));
  }
  await page.setViewportSize({ width: 360, height: 800 }); await sleep(200);
  await page.click('#fmMoreModal .sheet-close'); await sleep(350);
  check('   ✕ closes it, the button says so and gets the focus back', !(await isOpen(page)) && (await ev(page, () => document.getElementById('fmMoreBtn').getAttribute('aria-expanded'))) === 'false' && (await ev(page, () => document.activeElement && document.activeElement.id)) === 'fmMoreBtn');
  check('   closed, its controls take no tap and are not shown (hidden, not only see-through)', await ev(page, () => getComputedStyle(document.getElementById('fmMoreModal')).visibility === 'hidden'));
  await openMore(page);
  await page.mouse.click(180, 40); await sleep(350);
  check('   a tap on the backdrop closes it', !(await isOpen(page)));
  await openMore(page);
  check('   Android Back closes it and stays in the tab', (await ev(page, () => backNavigate())) === true && !(await isOpen(page)) && (await ev(page, () => currentViewName())) === 'files');

  // ---------------------------------------------------------------- 3) every moved control works from the sheet
  await openMore(page);
  await page.click('#fmHiddenChk'); await sleep(100);
  check('3. Show hidden files: ticked in the sheet, the hidden file is listed behind it at once and the choice is kept', (await ev(page, () => fmOpts.hidden && [...document.querySelectorAll('#fmList .perm-name')].some(n => n.innerText === '.nomedia') && /"hidden":true/.test(window.AndroidBridge.loadSetting('fm_opts')))) && (await isOpen(page)));
  await page.click('#fmThumbChk'); await sleep(100);
  check('   Show thumbnails: unticked, the option is off and kept', await ev(page, () => fmOpts.thumbs === false && /"thumbs":false/.test(window.AndroidBridge.loadSetting('fm_opts'))));
  await page.click('#fmThumbClearBtn'); await sleep(120);
  check('   Clear thumbnail cache: the app empties the cache and says how much was freed', await ev(page, () => window.__fm.calls.includes('fmThumbCache:clear') && /Thumbnail cache cleared/.test(document.getElementById('toastMsg').innerText)));
  if (!(await isOpen(page))) await openMore(page);
  await page.click('#fmSearchNested'); await page.click('#fmSearchArc'); await sleep(60);
  await page.selectOption('#fmSearchScope', { label: 'Downloads' }); await sleep(60);
  check('   the two search boxes are kept (subfolders off, archives on)', await ev(page, () => /"nested":false/.test(window.AndroidBridge.loadSetting('fm_search')) && /"arc":true/.test(window.AndroidBridge.loadSetting('fm_search'))));
  await page.click('#fmMoreModal button:has-text("Search help")'); await sleep(60);
  check('   Search help unfolds the help in the sheet', await ev(page, () => document.getElementById('fmSearchHelp').checkVisibility({ checkVisibilityCSS: true }) && /content:word/.test(document.getElementById('fmSearchHelp').innerText)));
  await page.click('#fmMoreModal .sheet-close'); await sleep(350);
  await page.fill('#fmSearchInput', 'notes'); await page.click('#fmSearchBtn'); await sleep(250);
  check('   a search then uses what the sheet set: Downloads, no subfolders, inside archives, hidden files', JSON.stringify(await ev(page, () => window.__fm.searches.slice(-1)[0])) === JSON.stringify({ q: 'notes', roots: [ROOT + '/Download'], nested: false, archives: true, hidden: true }), JSON.stringify(await ev(page, () => window.__fm.searches.slice(-1)[0])));
  await ev(page, () => fmSearchExit()); await sleep(100);
  for (const [label, path] of [['/system', '/system'], ['/data/local/tmp', '/data/local/tmp'], ['Storage', ROOT]]) {
    await openMore(page);
    await page.click(`#fmPlacesRow button:text-is("${label}")`); await sleep(200);
    check('   Go to ' + label + ': opens that folder and closes the sheet', (await ev(page, () => fmPath)) === path && !(await isOpen(page)) && (await ev(page, () => document.getElementById('fmCurrentPath').innerText)) === path, await ev(page, () => fmPath));
  }
  await openMore(page);
  const lists = await ev(page, () => window.__fm.calls.filter(c => c === 'fmList:' + fmPath).length);
  await page.click('#fmRefreshBtn'); await sleep(200);
  check('   Refresh: reads the folder again and closes the sheet', (await ev(page, () => window.__fm.calls.filter(c => c === 'fmList:' + fmPath).length)) === lists + 1 && !(await isOpen(page)));
  await openMore(page);
  await page.click('#fmRootsRow button:has-text("Add storage")'); await sleep(80);
  check('   ＋ Add storage asks the app for its folder picker', (await ev(page, () => window.__picks)) === 1);
  await ev(page, () => window.onStorageRootPicked('content://mock/tree/sd', 'SD Card')); await sleep(150);
  check('   the picked storage is opened and the sheet is closed', !(await isOpen(page)) && (await ev(page, () => window.__fm.calls.filter(c => c === 'fmList:content:/mock/tree/sd').length)) === 1);
  await openMore(page);
  check('   the sheet then lists it next to ＋ Add storage', (await page.locator('#fmRootsRow button').allInnerTexts()).join('|') === 'SD Card|✕|＋ Add storage');
  await page.click('#fmRootsRow button:text-is("SD Card")'); await sleep(150);
  check('   tapping it opens it again and closes the sheet', !(await isOpen(page)) && (await ev(page, () => window.__fm.calls.filter(c => c === 'fmList:content:/mock/tree/sd').length)) === 2);
  await ev(page, () => fmGo('/storage/emulated/0')); await sleep(100);

  // ---------------------------------------------------------------- 4) the actions on the card are one tap
  await page.click('#fmSelectBtn'); await sleep(80);
  check('4. Select (on the card) starts selecting in one tap', await ev(page, () => fmSelMode && getComputedStyle(document.getElementById('fmSelBar')).display !== 'none'));
  await page.click('#fmSelectBtn'); await sleep(60);
  await page.click('#fmTopCard button:text-is("＋ Folder")'); await sleep(80);
  check('   ＋ Folder asks for a name in one tap', await ev(page, () => document.getElementById('fmActionModal').classList.contains('show') && document.getElementById('fmActionName').innerText === 'New folder'));
  await ev(page, () => closeFmAction()); await sleep(300);
  await page.click('#fmTopCard button:text-is("＋ File")'); await sleep(80);
  check('   ＋ File asks for a name in one tap', await ev(page, () => document.getElementById('fmActionModal').classList.contains('show') && document.getElementById('fmActionName').innerText === 'New file'));
  await ev(page, () => closeFmAction()); await sleep(300);
  await page.fill('#fmPathInput', ROOT + '/Download'); await page.click('#fmTopCard .term-run-btn:text-is("Go")'); await sleep(150);
  check('   the path bar and Go open a folder', (await ev(page, () => fmPath)) === ROOT + '/Download');
  // a sheet left open does not come back by itself when the tab opens again
  await openMore(page);
  await ev(page, () => { switchView('apps'); switchView('files'); }); await sleep(300);
  check('   leaving the tab with the sheet open and coming back shows the list, not the sheet', !(await isOpen(page)));
  await page.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e && e.message); process.exit(1); });
