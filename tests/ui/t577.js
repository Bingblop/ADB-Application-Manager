// Saved Applications > New List and Edit (v7.16.2, the owner's wish: "make editing saved lists easier by just selecting which applications to add or remove"):
// the sheet lists this phone's apps as the Apps tab's rows (the icon is the tick box) with the list's apps ticked and on top. A tap ticks or unticks a row and nothing
// moves; search and the All / In this list / System / User chips narrow the rows; a package of the list that is not on this phone keeps a ticked row of its own; the
// count line says how many apps the list has and how many were added and removed; "Edit as text" holds the package box, which a tick writes and typing ticks; Save keeps
// the list's id and order and nudges the quick list; a hold on a row ticks it (no app menu over the sheet); long lists draw 150 rows at a time; the rows fit a phone.
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(appsMock.initScript, { dark: true });
  await page.addInitScript(() => {
    window.__calls = [];
    window.__saved = null;
    const b = window.AndroidBridge;
    b.setQuickList = id => window.__calls.push('quicklist:' + id);
    b.getQuickList = () => 'list_q';
    b.loadAppIcons = json => { window.__calls.push('icons:' + JSON.parse(json).join(',')); return 'started'; };
    b.saveCustomLists = json => { window.__saved = JSON.parse(json); };
    b.loadCustomLists = () => JSON.stringify([{ id: 'list_q', name: 'Morning', description: 'stop these', packages: ['com.whatsapp', 'com.gone.app', 'com.android.chrome'], updatedAt: 1 }]);
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const st = () => ev(() => {
    const rows = Array.from(document.querySelectorAll('#listEditorList .le-row'));
    return {
      shown: document.getElementById('listEditorModal').classList.contains('show'),
      title: document.getElementById('listEditorTitle').innerText,
      count: document.getElementById('listEditorCount').innerText, diff: document.getElementById('listEditorDiff').innerText,
      rows: rows.map(r => r.dataset.pkg + (r.classList.contains('selected') ? '*' : '')),
      checked: rows.filter(r => r.querySelector('.app-sel').getAttribute('aria-checked') === 'true').length,
      text: document.getElementById('listEditorPackages').value.split('\n').filter(Boolean),
      chips: Array.from(document.querySelectorAll('#listEditorFilters .filter-chip')).map(c => c.innerText + (c.classList.contains('active') ? '*' : '')),
      empty: (document.querySelector('#listEditorList .empty-state-title') || {}).innerText || '',
      more: (document.querySelector('#listEditorList .le-more button') || {}).innerText || '',
    };
  });
  const row = pkg => `#listEditorList .le-row[data-pkg="${pkg}"]`;

  // 1) Edit on the list's card: the list's apps ticked and on top (by name), then the phone's other apps (by name); the package that is not on this phone has a row
  await ev(() => switchView('saved-lists'));
  await page.click('#savedListsContainer .saved-list-card .apps-tool-btn:nth-child(3)');          // Edit
  await page.waitForTimeout(400);
  let s = await st();
  console.log('1) opened:', JSON.stringify(s));
  check('1a. Edit opens "Edit Saved List"', s.shown && s.title === 'Edit Saved List');
  check('1b. the list\'s apps are ticked and on top, the rest follow by name',
    s.rows.join(' ') === 'com.android.chrome* com.gone.app* com.whatsapp* com.amazon.mShop.android.shopping com.samsung.android.bixby.agent com.facebook.katana org.mozilla.firefox com.microsoft.skydrive', s.rows.join(' '));
  check('1c. the tick boxes say which are ticked (aria-checked)', s.checked === 3, s.checked);
  check('1d. the count line, and no change yet', s.count === '3 apps in this list' && s.diff === '', s.count + ' | ' + s.diff);
  check('1e. the chips: All (on), In this list, System, User (the Apps tab\'s order)', s.chips.join(',') === 'All*,In this list,System,User', s.chips.join(','));
  const gone = await ev(() => { const r = document.querySelector('#listEditorList .le-row[data-pkg="com.gone.app"]'); return { badge: r.querySelector('.tag-badge').innerText, letter: r.querySelector('.app-sel .cd-icon').innerText, img: !!r.querySelector('img') }; });
  check('1f. the package that is not on this phone: a letter for its icon (as in Connected Devices) and NOT INSTALLED', gone.badge === 'NOT INSTALLED' && gone.letter === 'C' && !gone.img, JSON.stringify(gone));
  const states = await ev(() => ['com.microsoft.skydrive', 'com.samsung.android.bixby.agent'].map(p => document.querySelector('#listEditorList .le-row[data-pkg="' + p + '"]').className));
  check('1g. an uninstalled and a frozen app keep the Apps tab\'s row tint', /uninstalled/.test(states[0]) && /frozen/.test(states[1]), states.join(' | '));
  const icons = await ev(() => window.__calls.filter(c => c.startsWith('icons:')).join(' '));
  check('1h. the icons of the rows are asked for (not of the package that is not on this phone)', /org\.mozilla\.firefox/.test(icons) && !/com\.gone\.app/.test(icons), icons.slice(0, 160));
  await ev(() => window.onAppIcons({ 'org.mozilla.firefox': 'data:image/png;base64,iVBORw0KGgo=' }));
  check('1i. an icon that arrives lands on its row', await ev(() => !!document.querySelector('#listEditorList .le-row[data-pkg="org.mozilla.firefox"] img[src^="data:image/png"]')));

  // 2) A tap on a row's name ticks it, a tap on a ticked icon unticks it: the rows stay where they are, the package box and the count follow
  await page.click(row('com.facebook.katana') + ' .app-meta');
  await page.click(row('com.android.chrome') + ' .app-sel');
  s = await st();
  console.log('2) after a tick and an untick:', JSON.stringify({ rows: s.rows, text: s.text, count: s.count, diff: s.diff }));
  check('2a. nothing moved', s.rows.map(r => r.replace('*', '')).join(' ') === 'com.android.chrome com.gone.app com.whatsapp com.amazon.mShop.android.shopping com.samsung.android.bixby.agent com.facebook.katana org.mozilla.firefox com.microsoft.skydrive');
  check('2b. Facebook ticked, Chrome not', s.rows.includes('com.facebook.katana*') && s.rows.includes('com.android.chrome'), s.rows.join(' '));
  check('2c. the package box: the list\'s order, the new one at the end', s.text.join(',') === 'com.whatsapp,com.gone.app,com.facebook.katana', s.text.join(','));
  check('2d. the count line says what changed', s.count === '3 apps in this list' && s.diff === '1 added · 1 removed', s.count + ' | ' + s.diff);
  check('2e. the row just ticked gets the sweep of light', await ev(() => document.querySelector('#listEditorList .le-row[data-pkg="com.facebook.katana"]').classList.contains('sel-shine')));

  // 3) The chips: In this list shows the ticked rows, and a row unticked there stays until the chip or the search changes; User and System
  await page.click('#listEditorFilters .filter-chip:nth-child(2)');
  s = await st();
  check('3a. In this list: only the ticked rows', s.rows.join(' ') === 'com.gone.app* com.whatsapp* com.facebook.katana*' && s.chips[1] === 'In this list*', s.rows.join(' '));
  await page.click(row('com.whatsapp') + ' .app-meta');
  s = await st();
  check('3b. a row unticked under In this list stays, so a second tap brings it back', s.rows.join(' ') === 'com.gone.app* com.whatsapp com.facebook.katana*' && s.count === '2 apps in this list', s.rows.join(' ') + ' | ' + s.count);
  await page.click(row('com.whatsapp') + ' .app-meta');
  await page.focus('#listEditorFilters .filter-chip:nth-child(4)');
  await page.keyboard.press('Enter');
  s = await st();
  check('3c. User (by the keyboard): the user apps only, no system app and no package that is not on this phone',
    s.chips[3] === 'User*' && s.rows.join(' ') === 'com.whatsapp* com.amazon.mShop.android.shopping com.facebook.katana* org.mozilla.firefox com.microsoft.skydrive', s.rows.join(' '));
  await page.click('#listEditorFilters .filter-chip:nth-child(3)');
  s = await st();
  check('3d. System: the system apps only', s.chips[2] === 'System*' && s.rows.join(' ') === 'com.android.chrome com.samsung.android.bixby.agent', s.rows.join(' '));
  await page.click('#listEditorFilters .filter-chip:nth-child(1)');

  // 4) Search: by name or package; nothing found says so, with Clear search
  await page.fill('#listEditorSearch', 'FIRE');
  s = await st();
  check('4a. search by name', s.rows.join(' ') === 'org.mozilla.firefox', s.rows.join(' '));
  await page.fill('#listEditorSearch', 'katana');
  s = await st();
  check('4b. search by package', s.rows.join(' ') === 'com.facebook.katana*', s.rows.join(' '));
  await page.fill('#listEditorSearch', 'zzz');
  s = await st();
  check('4c. nothing found: "No app matches."', s.rows.length === 0 && s.empty === 'No app matches.', s.empty);
  await page.click('#listEditorList .empty-state .mode-action-btn');          // Clear search
  s = await st();
  check('4d. Clear search brings every row back', s.rows.length === 8 && (await page.inputValue('#listEditorSearch')) === '', s.rows.length);

  // 5) Edit as text: typing a name there ticks its row; a package typed there that is not on this phone gets a row
  await page.click('#listEditorText summary');
  await page.fill('#listEditorPackages', 'com.whatsapp\ncom.gone.app\ncom.facebook.katana\norg.mozilla.firefox, com.typed.only\n\ncom.whatsapp');
  s = await st();
  console.log('5) after typing:', JSON.stringify({ rows: s.rows, count: s.count, diff: s.diff }));
  check('5a. Firefox typed: ticked', s.rows.includes('org.mozilla.firefox*'));
  check('5b. a typed package that is not on this phone: a ticked row of its own', s.rows.includes('com.typed.only*'), s.rows.join(' '));
  check('5c. the count line follows the text (duplicates and blank lines left out)', s.count === '5 apps in this list' && s.diff === '3 added · 1 removed', s.count + ' | ' + s.diff);
  // a tick writes the box over (one package per line)
  await page.click(row('com.typed.only') + ' .app-meta');
  s = await st();
  check('5d. a tick rewrites the box, one package per line', s.text.join(',') === 'com.whatsapp,com.gone.app,com.facebook.katana,org.mozilla.firefox', s.text.join(','));
  check('5e. an unticked package that is not on this phone keeps its row until the sheet closes', s.rows.includes('com.typed.only'), s.rows.join(' '));

  // 6) A hold on a row is a tap that ticks it: the app menu does not open over the sheet
  await page.locator(row('com.amazon.mShop.android.shopping')).scrollIntoViewIfNeeded();
  const box = await page.locator(row('com.amazon.mShop.android.shopping') + ' .app-name').boundingBox();
  await page.mouse.move(box.x + 20, box.y + 5); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up();
  await page.waitForTimeout(150);
  const hold = await ev(() => ({ menu: document.getElementById('inspectorModal').classList.contains('show'), on: leSel.includes('com.amazon.mShop.android.shopping') }));
  check('6. a hold ticks the row and opens no app menu', !hold.menu && hold.on, JSON.stringify(hold));

  // 7) Save: same id, the box's order, the quick list nudged, the sheet closed, the card redrawn
  await ev(() => { window.__calls.length = 0; });
  await page.click('#listEditorModal .mode-action-btn.primary');
  await page.waitForTimeout(200);
  const saved = await ev(() => ({ shown: document.getElementById('listEditorModal').classList.contains('show'), lists: customLists.map(l => ({ id: l.id, name: l.name, packages: l.packages })), bridge: window.__saved && window.__saved.length,
    calls: window.__calls.slice(), meta: document.querySelector('#savedListsContainer .saved-list-meta').innerText }));
  console.log('7) saved:', JSON.stringify(saved));
  check('7a. saved in place: one list, its id, the new apps in the box\'s order',
    saved.lists.length === 1 && saved.lists[0].id === 'list_q' && saved.lists[0].packages.join(',') === 'com.whatsapp,com.gone.app,com.facebook.katana,org.mozilla.firefox,com.amazon.mShop.android.shopping', JSON.stringify(saved.lists));
  check('7b. written to the phone, the quick list nudged, the sheet closed, the card says 5 apps', saved.bridge === 1 && saved.calls.includes('quicklist:list_q') && !saved.shown && saved.meta === '5 apps', JSON.stringify(saved));

  // 8) Edit again: the order is the saved one, and "added / removed" counts from it; Cancel leaves the list as it was
  await page.click('#savedListsContainer .saved-list-card .apps-tool-btn:nth-child(3)');
  await page.waitForTimeout(200);
  s = await st();
  check('8a. reopened: the list\'s five on top (by name), ticked, no change counted',
    s.rows.slice(0, 5).join(' ') === 'com.amazon.mShop.android.shopping* com.gone.app* com.facebook.katana* org.mozilla.firefox* com.whatsapp*' && s.diff === '' && s.count === '5 apps in this list' && !s.rows.includes('com.typed.only'), s.rows.join(' '));
  check('8b. the sheet opens on All, an empty search and the text folded', s.chips[0] === 'All*' && (await page.inputValue('#listEditorSearch')) === '' && !(await ev(() => document.getElementById('listEditorText').open)));
  await page.click(row('org.mozilla.firefox') + ' .app-sel');
  await page.click('#listEditorModal .mode-action-btn:not(.primary)');          // Cancel
  check('8c. Cancel: nothing saved', await ev(() => customLists[0].packages.length === 5 && !document.getElementById('listEditorModal').classList.contains('show')));

  // 9) New List: nothing ticked, the phone's apps by name, no "added" line; two ticks and a name make a list at the top
  await page.click('.saved-lists-toolbar .bs-btn:first-child');          // New List
  await page.waitForTimeout(200);
  s = await st();
  check('9a. New List: "Create Saved List", nothing ticked, by name', s.title === 'Create Saved List' && s.checked === 0 && s.rows[0] === 'com.amazon.mShop.android.shopping' && s.count === 'No apps in this list yet' && s.text.length === 0, JSON.stringify(s));
  await page.fill('#listEditorName', 'Social');
  await page.click(row('com.whatsapp') + ' .app-meta');
  await page.click(row('com.facebook.katana') + ' .app-sel');
  s = await st();
  check('9b. two ticks: 2 apps, and a new list counts no "added"', s.count === '2 apps in this list' && s.diff === '', s.count + ' | ' + s.diff);
  await page.click('#listEditorModal .mode-action-btn.primary');
  await page.waitForTimeout(200);
  const made = await ev(() => customLists.map(l => l.name + ':' + l.packages.join('+')));
  check('9c. the new list is at the top with its two apps', made[0] === 'Social:com.whatsapp+com.facebook.katana' && made.length === 2, made.join(' | '));

  // 10) The rows fit a phone: no row wider than the sheet, the rows scroll inside it, the buttons stay on screen
  await page.click('#savedListsContainer .saved-list-card:nth-child(2) .apps-tool-btn:nth-child(3)');
  await page.waitForTimeout(400);
  const fit = await ev(() => {
    const sheet = document.querySelector('#listEditorModal .modal-sheet').getBoundingClientRect(), list = document.getElementById('listEditorList');
    const save = document.querySelector('#listEditorModal .mode-action-btn.primary').getBoundingClientRect();
    const rows = Array.from(list.querySelectorAll('.le-row')).map(r => r.getBoundingClientRect());
    return { wide: rows.filter(r => r.right > sheet.right + 0.5 || r.left < sheet.left - 0.5).length, overflowX: list.scrollWidth > list.clientWidth + 1, scrolls: list.scrollHeight > list.clientHeight,
      listH: list.clientHeight >= 160, saveOnScreen: save.bottom <= innerHeight + 0.5, sheetInView: sheet.top >= 0,
      searchH: document.getElementById('listEditorSearch').offsetHeight, rowsH: list.clientHeight };
  });
  check('10. the rows fit the sheet, scroll inside it, and Save stays on screen', !fit.wide && !fit.overflowX && fit.scrolls && fit.listH && fit.saveOnScreen && fit.sheetInView, JSON.stringify(fit));
  check('10b. the search box is one line high and the rows take the room that is left', fit.searchH < 60 && fit.rowsH > 300, fit.searchH + 'px search, ' + fit.rowsH + 'px rows');
  await page.screenshot({ path: 'edit-dark.png' });
  await ev(() => setAppearance('light'));
  await page.waitForTimeout(200);
  await page.screenshot({ path: 'edit-light.png' });
  await ev(() => setAppearance('dark'));

  // 11) A phone with many apps (327, and the list's package that is not on this phone): 150 rows at a time, Show more adds the next page; search still finds a row past the first page
  await ev(() => {
    const extra = [];
    for (let i = 0; i < 320; i++) extra.push({ pkg: 'com.bulk.app' + String(i).padStart(3, '0'), name: 'Bulk ' + String(i).padStart(3, '0'), isSystem: i % 2 === 0, isRunning: false, isFrozen: false, isUninstalled: false, isSuspended: false });
    allApps = allApps.concat(extra);
    openListEditorModal(customLists[1].id);
  });
  await page.waitForTimeout(200);
  s = await st();
  check('11a. 150 rows drawn, and Show more says what is left', s.rows.length === 150 && s.more === 'Show 150 more · 178 left', s.rows.length + ' | ' + s.more);
  await page.click('#listEditorList .le-more button');
  s = await st();
  check('11b. Show more: 300 rows', s.rows.length === 300 && s.more === 'Show 28 more · 28 left', s.rows.length + ' | ' + s.more);
  await page.fill('#listEditorSearch', 'bulk 319');
  const early = (await st()).rows.length;          // a long list waits for a pause in typing, as the Apps tab's search does
  await page.waitForTimeout(250);
  s = await st();
  check('11c. search finds a row past the first page (after a pause in typing, on a long list)', early > 1 && s.rows.join(' ') === 'com.bulk.app319', early + ' | ' + s.rows.join(' '));
  await page.click('#listEditorModal .sheet-close');

  // 12) No app was read from this phone: the Apps tab's empty state (with Reload Packages); a list's own packages keep their rows, with no NOT INSTALLED (nobody knows)
  await ev(() => { allApps = []; openListEditorModal(); });
  s = await st();
  check('12a. no apps, a new list: "No Applications Loaded", as on the Apps tab', s.rows.length === 0 && s.empty === 'No Applications Loaded', s.empty);
  await ev(() => { closeListEditorModal(); openListEditorModal(customLists[customLists.length - 1].id); });
  s = await st();
  const noTag = await ev(() => Array.from(document.querySelectorAll('#listEditorList .le-row .tag-badge')).length);
  const firstIsEmpty = await ev(() => document.getElementById('listEditorList').firstElementChild.classList.contains('empty-state'));
  check('12b. no apps, a saved list: its packages keep ticked rows without a tag, under the empty state', s.rows.length === 5 && s.checked === 5 && noTag === 0 && firstIsEmpty && s.empty === 'No Applications Loaded',
    JSON.stringify({ rows: s.rows, noTag, firstIsEmpty, empty: s.empty }));
  await ev(() => closeListEditorModal());

  await b.close();
  console.log('page errors:', errors.length ? errors : 'none');
  if (bad) console.log(bad + ' FAILED');
  process.exit(bad || errors.length ? 1 : 0);
})();
