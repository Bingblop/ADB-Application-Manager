// v7.15.1 A1: saving a saved-app list under a name an existing list already uses prompts Overwrite /
// Create new list / Cancel instead of silently making a duplicate. Covers the Save-Selected path and the
// list editor path; Overwrite replaces in place keeping the id (and nudges the quick-list widget).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(() => {
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.a', name: 'A' }, { pkg: 'com.b', name: 'B' }, { pkg: 'com.c', name: 'C' }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAppDetails() { return '{}'; },
      setQuickList(id) { window.__calls.push('quicklist:' + JSON.stringify(id)); },
      getQuickList() { return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // one existing saved list "My List" (id list_keep) with 2 packages, and a helper to reset to it
  const reset = () => ev(() => {
    window.__calls.length = 0;
    customLists = [{ id: 'list_keep', name: 'My List', description: 'old desc', packages: ['com.a', 'com.b'], updatedAt: 1 }];
    quickListId = '';
    selectedPkgs = new Set(['com.a', 'com.b', 'com.c']);
    document.getElementById('listNameCollisionModal').classList.remove('show');
    document.getElementById('saveListModal').classList.remove('show');
    document.getElementById('listEditorModal').classList.remove('show');
  });
  const state = () => ev(() => ({
    n: customLists.length,
    keep: (() => { const l = customLists.find(x => x.id === 'list_keep'); return l ? { pkgs: l.packages.length, desc: l.description } : null; })(),
    collisionShown: document.getElementById('listNameCollisionModal').classList.contains('show'),
    saveShown: document.getElementById('saveListModal').classList.contains('show'),
    calls: window.__calls.slice(),
    names: customLists.map(l => l.name),
    ids: customLists.map(l => l.id),
  }));

  // ---------- Save-Selected path ----------
  // A. a brand-new name just saves, no prompt
  await reset();
  await ev(() => { openSaveSelectedModal(); document.getElementById('saveListName').value = 'Brand New'; document.getElementById('saveListDesc').value = ''; confirmSaveSelectedApps(); });
  let s = await state();
  check('A1. new name: no collision prompt', !s.collisionShown);
  check('A2. new name: a second list is created', s.n === 2, JSON.stringify(s.names));
  check('A3. new name: the save sheet closed', !s.saveShown);

  // B. a name already in use prompts
  await reset();
  await ev(() => { openSaveSelectedModal(); document.getElementById('saveListName').value = 'My List'; document.getElementById('saveListDesc').value = 'fresh'; confirmSaveSelectedApps(); });
  s = await state();
  check('B1. existing name: the collision prompt is shown', s.collisionShown);
  check('B2. existing name: nothing saved yet (still one list)', s.n === 1 && s.keep.pkgs === 2, JSON.stringify(s));

  // B-overwrite: replaces the existing list in place, keeping its id; count unchanged
  await ev(() => resolveListNameCollision('overwrite'));
  s = await state();
  check('B3. Overwrite: still one list (no duplicate)', s.n === 1, JSON.stringify(s.names));
  check('B4. Overwrite: the kept list now has the 3 selected packages', s.keep && s.keep.pkgs === 3, JSON.stringify(s.keep));
  check('B5. Overwrite: its id is unchanged (quick-list binding survives)', s.ids[0] === 'list_keep', JSON.stringify(s.ids));
  check('B6. Overwrite: its description was replaced', s.keep && s.keep.desc === 'fresh', JSON.stringify(s.keep));
  check('B7. Overwrite: both sheets closed', !s.collisionShown && !s.saveShown);

  // B-new: makes a separate second list, original untouched
  await reset();
  await ev(() => { openSaveSelectedModal(); document.getElementById('saveListName').value = 'My List'; confirmSaveSelectedApps(); resolveListNameCollision('new'); });
  s = await state();
  check('B8. Create new list: a second list is created', s.n === 2, JSON.stringify(s.names));
  check('B9. Create new list: the original is untouched (still 2 packages)', s.keep && s.keep.pkgs === 2, JSON.stringify(s.keep));
  check('B10. Create new list: the new one has a different id', s.ids.filter(id => id === 'list_keep').length === 1 && s.ids.some(id => id !== 'list_keep'), 'kept=' + s.ids.filter(id => id === 'list_keep').length + ' distinct-new=' + s.ids.some(id => id !== 'list_keep'));

  // B-cancel: nothing changes, the save sheet stays open
  await reset();
  await ev(() => { openSaveSelectedModal(); document.getElementById('saveListName').value = 'My List'; confirmSaveSelectedApps(); closeListNameCollisionModal(); });
  s = await state();
  check('B11. Cancel: nothing saved (still one list, 2 packages)', s.n === 1 && s.keep.pkgs === 2, JSON.stringify(s));
  check('B12. Cancel: the collision sheet is gone but the Save sheet stays open', !s.collisionShown && s.saveShown);

  // ---------- List editor path ----------
  // C. a NEW list typed in the editor under an existing name prompts, then overwrites in place
  await reset();
  await ev(() => { openListEditorModal(); document.getElementById('listEditorName').value = 'My List'; document.getElementById('listEditorDesc').value = 'via editor'; document.getElementById('listEditorPackages').value = 'com.a\ncom.b\ncom.c'; saveListFromEditor(); });
  s = await state();
  check('C1. editor, existing name: the collision prompt is shown', s.collisionShown);
  await ev(() => resolveListNameCollision('overwrite'));
  s = await state();
  check('C2. editor Overwrite: still one list', s.n === 1, JSON.stringify(s.names));
  check('C3. editor Overwrite: kept id, 3 packages, new description', s.ids[0] === 'list_keep' && s.keep.pkgs === 3 && s.keep.desc === 'via editor', JSON.stringify(s));

  // editor Create new -> a separate list
  await reset();
  await ev(() => { openListEditorModal(); document.getElementById('listEditorName').value = 'My List'; document.getElementById('listEditorPackages').value = 'com.a'; saveListFromEditor(); resolveListNameCollision('new'); });
  s = await state();
  check('C4. editor Create new list: a second list is created with a different id', s.n === 2 && s.ids.some(id => id !== 'list_keep'), 'count=' + s.n + ' distinct-new=' + s.ids.some(id => id !== 'list_keep'));

  // editing the SAME list (same id) and keeping its name must NOT prompt (not a collision with itself)
  await reset();
  await ev(() => { openListEditorModal('list_keep'); document.getElementById('listEditorPackages').value = 'com.a\ncom.c'; saveListFromEditor(); });
  s = await state();
  check('C5. editing a list without renaming does not prompt and saves in place', !s.collisionShown && s.n === 1 && s.keep.pkgs === 2, JSON.stringify(s));

  // D. overwriting the current quick list nudges the widget (setQuickList called with its id)
  await reset();
  await ev(() => { quickListId = 'list_keep'; window.__calls.length = 0; openSaveSelectedModal(); document.getElementById('saveListName').value = 'My List'; confirmSaveSelectedApps(); resolveListNameCollision('overwrite'); });
  s = await state();
  check('D1. Overwrite of the quick list nudges the widget', s.calls.indexOf('quicklist:"list_keep"') >= 0, JSON.stringify(s.calls));

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  await b.close();
})();
