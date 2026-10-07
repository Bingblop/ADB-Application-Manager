// The Apps tab: the Export button is gone (Share CSV stays) and the row ends with an Action Button chooser that works like the one in Settings (the two stay in step); the Versions pill and the
// versions in the rows are gone; a row shows the UAD-NG classification of its package when the project lists it, and tapping it opens the same full-description prompt as the app menu's chip.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__kv = {}; window.__sel = [];
    // Persist the native settings mock across a page reload, like Android's
    // settings store. saveStore/loadStore are a different bridge API.
    window.__settings = {};                      // handed to the page again by the test itself before a reload (sessionStorage is not: a write just before a reload may not reach the new page)
    const uad = { 'com.sec.hearingadjust': { found: true, pkg: 'com.sec.hearingadjust', list: 'Oem', removal: 'Advanced', description: 'Adapt sound\nTunes the sound to your hearing.', dependencies: ['com.sec.core'], neededBy: ['com.sec.audio'] },
      'com.danger': { found: true, pkg: 'com.danger', list: 'Aosp', removal: 'Unsafe', description: '', dependencies: [], neededBy: [] },
      'com.rec': { found: true, pkg: 'com.rec', list: 'Carrier', removal: 'Recommended', description: 'Carrier app', dependencies: [], neededBy: [] } };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore(k, v) { window.__kv[k] = v; return true; }, loadStore(k) { return window.__kv[k] || ''; },
      saveSetting(k, v) { window.__settings[k] = v; },
      loadSetting(k) { return window.__settings[k] || ''; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.hearingadjust', name: 'Adapt sound', isSystem: true, version: '1.2.3' }, { pkg: 'com.danger', name: 'Danger', isSystem: true, version: '9' }, { pkg: 'com.rec', name: 'Carrier thing', isSystem: true, version: '2' }, { pkg: 'com.plain', name: 'Plain', isSystem: false, version: '3.4' }]); },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      getUadStatus() { return JSON.stringify({ cached: true, count: 1500, updatedAt: Date.now(), stale: false }); },
      getUadMatches() { return JSON.stringify({ packages: Object.values(uad).map(u => ({ pkg: u.pkg, name: u.pkg, removal: u.removal, list: u.list, state: 'enabled', description: u.description })) }); },
      getUadInfo(p) { return JSON.stringify(uad[p] || { found: false, downloaded: true }); },
      openUrl(u) { window.__opened = u; }, getAppDetails() { return JSON.stringify({ versionName: '1', permissions: [], appopsRaw: '' }); },
      executeAppAction(a, p) { window.__sel.push(a + ':' + p); return 'Success'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(2500);
  const ev = (fn, a) => page.evaluate(fn, a);
  const wait = ms => page.waitForTimeout(ms);

  // the button row
  const row = await ev(() => Array.from(document.querySelectorAll('.apps-sort-row > *')).map(e => (e.tagName === 'SELECT' ? 'select#' + e.id : e.innerText.trim())));
  check('the row: Sort, its menu, Share CSV, Profiles, Backups, Action Button and its menu (no Export)', row.join('|') === 'Sort|select#appSort|Share CSV|Profiles|Backups|Action Button|select#appsActionBtnSelect', row.join('|'));
  check('there is no Export CSV button', (await ev(() => Array.from(document.querySelectorAll('#view-apps button')).filter(b => b.innerText.trim() === 'Export').length)) === 0);
  const opts = await ev(() => Array.from(document.querySelectorAll('#appsActionBtnSelect option')).map(o => o.value + (o.selected ? '*' : '')));
  check('the Action Button menu offers the same choices as Settings, the current one picked', opts.join() === 'settings*,forcestop,launch,toggle,uninstall,suspend,perms,acts,none', opts.join());
  await page.screenshot({ path: 'apps_row.png', clip: { x: 0, y: 150, width: 400, height: 520 } });
  const abBefore = await ev(() => Array.from(document.querySelectorAll('#card_com\\.plain .ab-btn')).length);
  await page.selectOption('#appsActionBtnSelect', 'forcestop'); await wait(200);
  check('choosing one changes the buttons on the rows at once', await ev(() => document.querySelector('#card_com\\.plain .ab-btn').getAttribute('aria-label') || document.querySelector('#card_com\\.plain .ab-btn').title) !== null && (await ev(() => /Force Stop/i.test(document.querySelector('#card_com\\.plain .ab-btn').outerHTML))), await ev(() => document.querySelector('#card_com\\.plain .ab-btn').outerHTML.slice(0, 200)));
  check('and Settings shows the same choice (and the other way round)', await ev(() => { actionBtnRender(); return document.getElementById('actionBtnSelect').value === 'forcestop' && document.getElementById('appsActionBtnSelect').value === 'forcestop'; }));
  await ev(() => setActionBtn('toggle')); await wait(100);
  check('a change from Settings moves the one in the row', await ev(() => document.getElementById('appsActionBtnSelect').value === 'toggle'));
  check('it is saved in the native settings store', await ev(() => window.__settings.action_btn === JSON.stringify('toggle')));
  const savedSettings = await ev(() => JSON.parse(JSON.stringify(window.__settings)));
  await page.addInitScript(saved => { Object.assign(window.__settings, saved); }, savedSettings);          // the native store survives a reload
  await page.reload();
  await page.waitForFunction(() => document.getElementById('appsActionBtnSelect').value === 'toggle' && document.querySelector('#card_com\\.plain .ab-btn'));
  check('both menus restore the saved choice after a reload', await ev(() => actionBtn === 'toggle' && document.getElementById('appsActionBtnSelect').value === 'toggle' && document.getElementById('actionBtnSelect').value === 'toggle'));
  check('the restored choice also restores the row action', await ev(() => /Enable|Disable/.test(document.querySelector('#card_com\\.plain .ab-btn').outerHTML)));
  await ev(() => setActionBtn('settings'));
  await page.waitForFunction(() => document.querySelectorAll('.uad-chip-row').length === 3);

  // versions are gone
  check('no Versions pill, no version in any row', (await ev(() => document.querySelectorAll('#versionTogglePill, .app-version').length)) === 0 && !(await ev(() => /v1\.2\.3|v9\b/.test(document.getElementById('appsListContainer').innerText))));

  // UAD-NG chips
  const chips = await ev(() => Array.from(document.querySelectorAll('.app-card')).map(c => c.dataset.pkg + ':' + (c.querySelector('.uad-chip-row') ? c.querySelector('.uad-chip-row').innerText.replace(/\s+/g, ' ').trim() : '-')));
  check('the rows of listed packages carry a chip with their level, the others none', chips.sort().join('|') === 'com.danger:UNSAFE|com.plain:-|com.rec:RECOMMENDED|com.sec.hearingadjust:ADVANCED', chips.join('|'));
  check('the chips have the level colors', await ev(() => ['recommended', 'advanced', 'unsafe'].every(l => document.querySelector('.uad-chip-row.uad-r-' + l))));
  await page.screenshot({ path: 'apps_uad.png', clip: { x: 0, y: 330, width: 400, height: 420 } });
  const selBefore = await ev(() => selectedPkgs.size);
  await page.click('#card_com\\.sec\\.hearingadjust .uad-chip-row'); await wait(250);
  const pr = await ev(() => ({ open: document.getElementById('uadInfoModal').classList.contains('show'), app: document.getElementById('uadInfoApp').innerText, body: document.getElementById('uadInfoBody').innerText.replace(/\n+/g, ' | ') }));
  check('tapping it opens the same prompt as the app menu (level, meaning, description, needs)', pr.open && /Adapt sound/.test(pr.app) && /Advanced:/.test(pr.body) && /Tunes the sound/.test(pr.body) && /Needed by: com\.sec\.audio/.test(pr.body) && /Depends on: com\.sec\.core/.test(pr.body), JSON.stringify(pr));
  check('the row is not selected by that tap, and the app menu did not open', (await ev(() => selectedPkgs.size)) === selBefore && !(await ev(() => document.getElementById('inspectorModal').classList.contains('show'))));
  await page.screenshot({ path: 'apps_uad_prompt.png' });
  await ev(() => closeUadInfo());
  await page.click('#card_com\\.danger .uad-chip-row'); await wait(200);
  check('an Unsafe one with no description says so', await ev(() => /Unsafe:/.test(document.getElementById('uadInfoBody').innerText) && /gives no description/.test(document.getElementById('uadInfoBody').innerText)));
  await ev(() => closeUadInfo());
  // the menu's chip still works, with the same box
  await ev(() => openInspector('com.rec')); await wait(300);
  await page.click('#sheetUad'); await wait(200);
  check('the app menu chip still opens its prompt', await ev(() => document.getElementById('uadInfoModal').classList.contains('show') && /Recommended:/.test(document.getElementById('uadInfoBody').innerText)));
  await ev(() => { closeUadInfo(); closeInspector(); });
  // a list that was not downloaded: no chips and nothing breaks
  await ev(() => { uadLevelByPkg = new Map(); renderApps(); });
  check('without a downloaded list no row has a chip', (await ev(() => document.querySelectorAll('.uad-chip-row').length)) === 0);

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
