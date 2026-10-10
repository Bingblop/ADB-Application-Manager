// SD Maid SE tab: in the tab bar after Morphe Patcher; the working mode and the data areas; the four tool cards (Scan, live progress, the two-at-a-time limit with "In queue", Cancel, the
// result with its dismiss X, Details, Delete); the 1-tap scan and delete checkbox (off by default, asks before it is turned on, then Scan runs everything); the list of results with take-out checkboxes, items inside
// a group, Exclude with Undo, the confirmation texts; Delete all results; the exclusion manager (create app / path / segment, remove, restore defaults); History with paths; the settings of a tool; the
// accessibility service card; the credits with the GitHub and Google Play links. The tool side is a mock of AndroidBridge.sdm: the page is driven with the same events Java sends.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__calls = []; window.__urls = [];
    const idle = t => ({ tool: t, state: 'idle', hasData: false, running: false });
    window.__st = { systemcleaner: idle('systemcleaner'), appcleaner: idle('appcleaner'), corpsefinder: idle('corpsefinder'), deduplicator: idle('deduplicator') };
    window.__running = 0; window.__queued = 0;
    window.__acs = { enabled: false, connected: false, consent: false };
    window.__data = {
      systemcleaner: { groups: [{ id: 'logfiles', label: 'Log files', sub: 'Log files of apps and the system', count: 3, bytes: 3145728 }, { id: 'trash', label: 'Trash', sub: 'Trashed files', count: 2, bytes: 2097152 }, { id: 'empty', label: 'Empty directories', sub: '', count: 1, bytes: 0 }],
        items: { logfiles: [{ id: '/s/a.log', path: '/storage/emulated/0/a.log', name: 'a.log', size: 1048576, mtime: 1, type: 0 }, { id: '/s/b.log', path: '/storage/emulated/0/b.log', name: 'b.log', size: 1048576, mtime: 1, type: 0 }, { id: '/s/c.log', path: '/storage/emulated/0/c.log', name: 'c.log', size: 1048576, mtime: 1, type: 0 }] } },
      appcleaner: { groups: [{ id: 'com.a', pkg: 'com.a', label: 'App A', sub: 'com.a', count: 5, bytes: 5242880, system: false }, { id: 'com.sys', pkg: 'com.sys', label: 'System thing', sub: 'com.sys', count: 2, bytes: 1048576, system: true }], items: {} },
      corpsefinder: { groups: [{ id: '/storage/emulated/0/Android/data/com.gone', path: '/storage/emulated/0/Android/data/com.gone', label: 'com.gone', sub: '/storage/emulated/0/Android/data', count: 4, bytes: 8388608, area: 'PUBLIC_DATA', risk: 'Low' }], items: {} },
      deduplicator: { groups: [{ id: 'c1', label: 'Content checksum', sub: '2 files', count: 2, bytes: 4194304, method: 'Content checksum' }], items: { c1: [{ id: '/d/x1', path: '/storage/emulated/0/x1.bin', name: 'x1.bin', size: 2097152, mtime: 1, type: 0, keep: true }, { id: '/d/x2', path: '/storage/emulated/0/x2.bin', name: 'x2.bin', size: 2097152, mtime: 1, type: 0 }] } }
    };
    window.__excl = [{ id: 'PathExclusion-/data/rootfs', kind: 'path', path: '/data/rootfs', tags: ['systemcleaner'], label: '/data/rootfs', isDefault: true }, { id: 'PkgExclusion-com.keep', kind: 'pkg', pkg: 'com.keep', label: 'Keeper', tags: ['general'], isDefault: false }];
    window.__hist = [{ id: 'h1', tool: 'systemcleaner', startAt: 1790000000000, endAt: 1790000005000, status: 'success', primary: '4 match(es) deleted', secondary: 'Freed 4 MB space.', error: '', count: 4, bytes: 4194304, hasPaths: true },
      { id: 'h2', tool: 'appcleaner', startAt: 1789000000000, endAt: 1789000005000, status: 'partial', primary: '9 expendable item(s) deleted', secondary: 'Freed 2 MB space.', error: 'Stopped because the screen was off or locked', count: 9, bytes: 2097152, hasPaths: false }];
    window.__settings = { general: { dryRun: false, 'retention.reports': 30, 'retention.paths': 7 } };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return window.__kv && window.__kv[k] ? window.__kv[k] : (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      copyToClipboard(t) { window.__copied = t; }, openUrl(u) { window.__urls.push(u); },
      sdm(tag, op, argsJson) {
        const a = JSON.parse(argsJson || '{}');
        window.__calls.push({ op, a });
        let r = { ok: true };
        if (op === 'state') r = { ok: true, tools: window.__st, running: window.__running, queued: window.__queued };
        else if (op === 'areas') r = { ok: true, areas: [{ area: 'SDCARD', label: 'Public storage', via: 'java', available: true }, { area: 'PUBLIC_DATA', label: 'Public app data', via: 'shell', available: true }, { area: 'PUBLIC_OBB', label: 'Public app resources', via: '', available: false, reason: 'Needs root or ADB' }, { area: 'PRIVATE_DATA', label: 'Private app data', via: '', available: false, reason: 'Needs root' }], mode: { name: 'adb_tcp', uid: 2000, privileged: true }, access: { storage: true, usage: true }, acs: window.__acs };
        else if (op === 'acs') { if (a.op === 'consent') window.__acs.consent = true; if (a.op === 'revoke') window.__acs.consent = false; r = Object.assign({ ok: true }, window.__acs); }
        else if (op === 'groups') { const d = window.__data[a.tool]; let g = d.groups; if (a.q) g = g.filter(x => (x.label + x.sub).toLowerCase().includes(a.q.toLowerCase())); r = { ok: true, total: g.length, groups: g.slice(a.offset || 0, (a.offset || 0) + (a.limit || 200)) }; }
        else if (op === 'items') { const it = (window.__data[a.tool].items[a.group] || []); r = { ok: true, total: it.length, items: it.slice(a.offset || 0, (a.offset || 0) + (a.limit || 200)) }; }
        else if (op === 'exclude') r = { ok: true, handle: 'hnd1', count: (a.paths || a.pkgs || []).length, ids: ['x'] };
        else if (op === 'exclusions') r = { ok: true, list: window.__excl };
        else if (op === 'exclusionSave') { window.__excl.push(Object.assign({ id: 'new' + window.__excl.length, label: a.exclusion.pkg || a.exclusion.path || (a.exclusion.segments || []).join('/') }, a.exclusion)); r = { ok: true, id: 'n' }; }
        else if (op === 'exclusionRemove') { window.__excl = window.__excl.filter(x => !a.ids.includes(x.id)); }
        else if (op === 'exclusionsExport') r = { ok: true, json: '{"exclusions":1}' };
        else if (op === 'resolvePkgs') r = { ok: true, apps: [{ pkg: 'com.photos', label: 'Photos', system: false }, { pkg: 'com.android.sys', label: 'System UI', system: true }].filter(x => !a.q || (x.label + x.pkg).toLowerCase().includes(a.q.toLowerCase())) };
        else if (op === 'historyList') r = { ok: true, total: window.__hist.length, reports: window.__hist, stats: { freedBytes: 6291456, items: 13, sinceMs: 1780000000000 } };
        else if (op === 'historyPaths') r = { ok: true, total: 2, bytes: 4194304, paths: [{ path: '/storage/emulated/0/a.log', action: 'deleted' }, { path: '/storage/emulated/0/b.log', action: 'deleted' }] };
        else if (op === 'settingsGet') r = { ok: true, values: window.__settings[a.tool] || {} };
        else if (op === 'settingsSet') { const s = (window.__settings[a.tool] = window.__settings[a.tool] || {}); s[a.key] = a.value; r = { ok: true, values: s }; }
        else if (op === 'settingsReset') { window.__settings[a.tool] = {}; r = { ok: true, values: {} }; }
        setTimeout(() => window.onSdmReply({ tag, r }), 0);
      }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const calls = () => ev(() => window.__calls.slice());
  const ops = async op => (await calls()).filter(c => c.op === op);
  const text = sel => page.locator(sel).first().innerText();
  const okAsk = async () => { await ev(() => { mpAskDone(true); }); await wait(120); };
  const emit = e => ev(x => window.onSdm(x), e);

  // ---- the tab ----
  const tabs = await ev(() => tabsShown());
  check('SD Maid is in the tab bar after Morphe Patcher', tabs.indexOf('sdm') === tabs.indexOf('morphe') + 1, tabs.join());
  check('its label is just SD Maid (v7.10.10), one line', (await ev(() => tabDef('sdm').label)) === 'SD Maid' && (await ev(() => tabDef('sdm').name)) === 'SD Maid');
  await ev(() => { switchView('sdm'); }); await wait(500);
  check('it has a help "?" that points to its guide topic', await ev(() => !!document.querySelector('#view-sdm .help-q') && document.querySelector('#view-sdm .help-q').dataset.help === 'tab-sdm'));
  check('four tool cards in the order SystemCleaner, AppCleaner, CorpseFinder, Deduplicator', await ev(() => Array.from(document.querySelectorAll('#sdCards .sd-card .color-card-title')).map(e => e.innerText).join('|')) === 'SystemCleaner|AppCleaner|CorpseFinder|Deduplicator');
  check('each card says what the tool does', /Superfluous data/.test(await text('#sdCard-systemcleaner')) && /Expendable data/.test(await text('#sdCard-appcleaner')) && /already removed apps/.test(await text('#sdCard-corpsefinder')) && /duplicate data/.test(await text('#sdCard-deduplicator')));
  check('the working mode is named', /ADB_TCP/.test(await text('#sdStatus')));
  check('the data areas are shown: available ones, one through the working mode, and the ones that are not', await ev(() => { const t = document.getElementById('sdAreas').innerText; return /Public storage/.test(t) && /Public app data . working mode/.test(t) && /Private app data . not available/.test(t); }));
  check('the 1-tap checkbox of every card is off', await ev(() => Array.from(document.querySelectorAll('#sdCards .sd-one input')).every(i => !i.checked)) && (await ev(() => document.querySelectorAll('#sdCards .sd-one input').length)) === 4);
  check('credits: SD Maid SE by darken, GPL-3.0, with the GitHub and the Google Play link', await ev(() => { const t = document.getElementById('sdTop').innerText; return /darken/.test(t) && /GPL-3\.0/.test(t) && /SD Maid SE on GitHub/.test(t) && /SD Maid SE on Google Play/.test(t); }));
  await ev(() => { Array.from(document.querySelectorAll('#sdTop .sd-credit a')).filter(a => /Google Play/.test(a.innerText))[0].click(); Array.from(document.querySelectorAll('#sdTop .sd-credit a')).filter(a => /GitHub/.test(a.innerText))[0].click(); }); await wait(100);
  const urls = await ev(() => window.__urls);
  check('the links open the Play Store page and the repository', urls.includes('https://play.google.com/store/apps/details?id=eu.darken.sdmse') && urls.includes('https://github.com/d4rken-org/sdmaid-se'), JSON.stringify(urls));
  check('Delete all results is off while there is nothing to delete', await ev(() => document.getElementById('sdDelAll').disabled));
  await page.screenshot({ path: 'sdm_top.png' });

  // ---- scanning, live status, at most two at a time ----
  await page.click('#sdScanAll'); await wait(200);
  check('Scan all asks for a scan of every tool', (await ops('scan')).map(c => c.a.tool).join() === 'systemcleaner,appcleaner,corpsefinder,deduplicator');
  await ev(() => {
    window.__st.systemcleaner = { tool: 'systemcleaner', state: 'scanning', running: true, hasData: false, progress: { primary: 'Searching', secondary: '/storage/emulated/0/DCIM/Camera', countType: 'percent', current: 45, max: 100, bytes: 1048576 } };
    window.__st.appcleaner = { tool: 'appcleaner', state: 'scanning', running: true, hasData: false, progress: { primary: 'Loading app data', secondary: 'App A', countType: 'counter', current: 12, max: 300, bytes: 0 } };
    window.__st.corpsefinder = { tool: 'corpsefinder', state: 'queued', running: false, hasData: false, progress: { primary: 'In queue', countType: 'indeterminate', queued: true } };
    window.__st.deduplicator = { tool: 'deduplicator', state: 'queued', running: false, hasData: false, progress: { primary: 'In queue', countType: 'indeterminate', queued: true } };
    window.__running = 2; window.__queued = 2;
  });
  await emit({ ev: 'state', tool: 'systemcleaner', state: await ev(() => window.__st.systemcleaner) }); await wait(100);
  await emit({ ev: 'state', tool: 'appcleaner', state: await ev(() => window.__st.appcleaner) });
  await emit({ ev: 'state', tool: 'corpsefinder', state: await ev(() => window.__st.corpsefinder) });
  await emit({ ev: 'state', tool: 'deduplicator', state: await ev(() => window.__st.deduplicator) });
  await ev(() => { sdApplyState({ tools: window.__st, running: 2, queued: 2 }); }); await wait(100);
  check('the live status shows at the top while something works, with "2 of 2 running, 2 in queue"', await ev(() => { const l = document.getElementById('sdLive'); return l.classList.contains('on') && /2 of 2 running, 2 in queue/.test(l.innerText); }), await text('#sdLive'));
  check('SystemCleaner shows its step, a percent and the path', await ev(() => { const t = document.getElementById('sdBody-systemcleaner').innerText; return /Searching/.test(t) && /45%/.test(t) && /DCIM\/Camera/.test(t) && /1(\.0)? MB found/.test(t); }), await text('#sdBody-systemcleaner'));
  check('AppCleaner shows a counter', /12\/300/.test(await text('#sdBody-appcleaner')));
  check('CorpseFinder and Deduplicator say "In queue"', /In queue/.test(await text('#sdBody-corpsefinder')) && /In queue/.test(await text('#sdBody-deduplicator')));
  check('a working card offers Cancel and a card in the queue too', await ev(() => ['systemcleaner', 'corpsefinder'].every(i => /Cancel/.test(document.querySelector('#sdCard-' + i + ' .sd-act').innerText))));
  check('Scan all is off and Cancel all is shown', await ev(() => document.getElementById('sdScanAll').disabled && document.getElementById('sdCancelAll').style.display !== 'none'));
  await ev(() => { window.__calls.length = 0; });
  await emit({ ev: 'progress', tool: 'systemcleaner', progress: { primary: 'Searching', secondary: '/storage/emulated/0/Download', countType: 'percent', current: 80, max: 100, bytes: 2097152 } }); await wait(60);
  check('a progress event changes the line and the percent without redrawing the card', /80%/.test(await text('#sdBody-systemcleaner')) && /Download/.test(await text('#sdBody-systemcleaner')));
  await page.click('#sdCard-corpsefinder .sd-act .mode-action-btn'); await wait(100);
  check('Cancel sends the cancel of that tool', (await ops('cancel')).some(c => c.a.tool === 'corpsefinder'));
  await page.screenshot({ path: 'sdm_working.png' });

  // ---- results ----
  await ev(() => {
    window.__st.systemcleaner = { tool: 'systemcleaner', state: 'ready', hasData: true, summary: { primary: '6 filter match(es)', secondary: '5.0 MB can be freed', itemCount: 6, groupCount: 3, bytes: 5242880 } };
    window.__st.appcleaner = { tool: 'appcleaner', state: 'ready', hasData: true, summary: { primary: '7 expendable item(s) found', secondary: '6.0 MB can be freed', itemCount: 7, groupCount: 2, bytes: 6291456 } };
    window.__st.corpsefinder = { tool: 'corpsefinder', state: 'idle', hasData: false };
    window.__st.deduplicator = { tool: 'deduplicator', state: 'ready', hasData: true, summary: { primary: '1 duplicate set(s) found', secondary: '2.0 MB is occupied by duplicates', itemCount: 1, groupCount: 1, bytes: 2097152 } };
    sdApplyState({ tools: window.__st, running: 0, queued: 0 });
  }); await wait(100);
  check('the live status is gone when nothing works', await ev(() => !document.getElementById('sdLive').classList.contains('on')));
  check('a card with data shows the two result lines and Details, Delete, Scan', await ev(() => { const c = document.getElementById('sdCard-systemcleaner'); return /6 filter match\(es\)/.test(c.innerText) && /5\.0 MB can be freed/.test(c.innerText) && /Details/.test(c.innerText) && /Delete/.test(c.innerText) && /Scan/.test(c.innerText); }));
  check('Delete all results is on', await ev(() => !document.getElementById('sdDelAll').disabled));
  await page.screenshot({ path: 'sdm_results.png' });
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdCard-corpsefinder .sd-act .primary'); await wait(100);
  check('Scan asks for a scan (not a 1-tap run)', (await ops('scan')).length === 1 && (await ops('oneclick')).length === 0);

  // ---- the list of results: take items out ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdCard-systemcleaner .sd-act .ico'); await wait(300);
  check('Details opens the sheet with the groups of the scan and their sizes', await ev(() => document.getElementById('sdSheet').classList.contains('show') && /Log files/.test(document.getElementById('sdSheetBody').innerText) && /Trash/.test(document.getElementById('sdSheetBody').innerText) && /3\.0 MB/.test(document.getElementById('sdSheetBody').innerText)));
  check('every group starts ticked', await ev(() => Array.from(document.querySelectorAll('#sdSheetBody .sd-row input')).every(i => i.checked)) && (await ev(() => document.querySelectorAll('#sdSheetBody .sd-row').length)) === 3);
  await page.screenshot({ path: 'sdm_list.png' });
  await ev(() => { document.querySelectorAll('#sdSheetBody .sd-row input')[1].click(); }); await wait(100);
  check('taking a group out dims its row', await ev(() => document.querySelectorAll('#sdSheetBody .sd-row')[1].classList.contains('is-off')));
  await ev(() => { sdDeleteTool('systemcleaner'); }); await wait(150);
  check('Delete selected asks with the number of items and the size that is left', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /Confirm deletion/.test(document.getElementById('mpAskTitle').innerText) && /Delete 4 selected items \(3\.0 MB\)\?/.test(document.getElementById('mpAskText').innerText)), await text('#mpAskText'));
  await ev(() => { mpAskDone(false); }); await wait(100);
  check('Cancel deletes nothing', (await ops('delete')).length === 0);
  await page.click('#sdSheetBody .sd-row .sd-main'); await wait(300);
  check('a group opens to its items with the path and the size', await ev(() => /a\.log/.test(document.getElementById('sdSheetBody').innerText) && /\/storage\/emulated\/0\/b\.log/.test(document.getElementById('sdSheetBody').innerText)) && (await ev(() => document.querySelectorAll('#sdSheetBody .sd-row').length)) === 3);
  check('the back arrow is shown', await ev(() => document.getElementById('sdSheetBack').style.display !== 'none'));
  await ev(() => { document.querySelectorAll('#sdSheetBody .sd-row input')[2].click(); }); await wait(100);
  await ev(() => { sdDeleteTool('systemcleaner'); }); await wait(150);
  check('one item taken out: 3 selected', /Delete 3 selected items/.test(await text('#mpAskText')), await text('#mpAskText'));
  await okAsk();
  const del = (await ops('delete'))[0];
  check('Delete sends the groups and items that were taken out and closes the sheet', !!del && del.a.tool === 'systemcleaner' && JSON.stringify(del.a.selection.dropGroups) === '["trash"]' && JSON.stringify(del.a.selection.dropItems) === '["/s/c.log"]' && await ev(() => !document.getElementById('sdSheet').classList.contains('show')), JSON.stringify(del));
  await emit({ ev: 'done', tool: 'systemcleaner', d: { kind: 'delete', status: 'ok', primary: '3 match(es) deleted', secondary: 'Freed 2 MB space.', count: 3, bytes: 2097152 } }); await wait(200);
  check('after a delete the selection starts again from everything', await ev(() => !SD.sel.systemcleaner));

  // ---- Exclude with Undo ----
  await ev(() => { window.__calls.length = 0; window.__st.systemcleaner = { tool: 'systemcleaner', state: 'ready', hasData: true, summary: { primary: '6 filter match(es)', secondary: '5.0 MB can be freed', itemCount: 6, groupCount: 3, bytes: 5242880 } }; sdApplyState({ tools: window.__st, running: 0, queued: 0 }); sdReview('systemcleaner'); }); await wait(300);
  await page.click('#sdSheetBody .sd-row .sd-main'); await wait(300);
  await page.click('#sdSheetBody .sd-row .sd-ex'); await wait(300);
  const ex = (await ops('exclude'))[0];
  check('Exclude on an item sends its path', !!ex && ex.a.tool === 'systemcleaner' && JSON.stringify(ex.a.paths) === '["/storage/emulated/0/a.log"]', JSON.stringify(ex));
  check('a bar says "1 exclusion created" with Undo', await ev(() => document.getElementById('sdUndo').classList.contains('on') && /1 exclusion created/.test(document.getElementById('sdUndoText').innerText)));
  await page.click('#sdUndo button'); await wait(200);
  check('Undo sends the handle back', (await ops('undoExclude')).some(c => c.a.handle === 'hnd1') && await ev(() => !document.getElementById('sdUndo').classList.contains('on')));
  await ev(() => { sdSheetClose(); });
  // an app group (AppCleaner) is excluded by package
  await ev(() => { window.__calls.length = 0; sdReview('appcleaner'); }); await wait(300);
  check('AppCleaner rows show the System tag', await ev(() => /System/.test(document.querySelectorAll('#sdSheetBody .sd-row')[1].innerText)));
  await page.click('#sdSheetBody .sd-row .sd-ex'); await wait(200);
  check('Exclude on an app sends its package', (await ops('exclude')).some(c => JSON.stringify(c.a.pkgs) === '["com.a"]'));
  await ev(() => { document.getElementById('sdRevQ').value = 'system'; sdRevSearch('system'); }); await wait(500);
  check('the search asks for the matches', (await ops('groups')).some(c => c.a.q === 'system') && (await ev(() => document.querySelectorAll('#sdSheetBody .sd-row').length)) === 1);
  await ev(() => { sdSheetClose(); });

  // ---- 1-tap ----
  await ev(() => { window.__calls.length = 0; });
  await ev(() => { const c = document.querySelector('#sdCard-appcleaner .sd-one input'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(150);
  check('turning 1-tap on asks first and says what it does', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /1-tap/.test(document.getElementById('mpAskTitle').innerText) && /without a list/.test(document.getElementById('mpAskText').innerText) || /no list to look at/.test(document.getElementById('mpAskText').innerText)));
  await ev(() => { mpAskDone(false); }); await wait(100);
  check('Cancel leaves it off', await ev(() => !document.querySelector('#sdCard-appcleaner .sd-one input').checked && !SD.one.appcleaner));
  await ev(() => { const c = document.querySelector('#sdCard-appcleaner .sd-one input'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(150);
  await okAsk();
  check('OK turns it on, remembers it and renames the button', await ev(() => document.querySelector('#sdCard-appcleaner .sd-one input').checked && JSON.parse(window.__kv.sdm_onetap).appcleaner === true && /Scan and delete/.test(document.querySelector('#sdCard-appcleaner .sd-act').innerText)));
  await ev(() => { window.__calls.length = 0; document.querySelector('#sdCard-appcleaner .sd-act .primary').click(); }); await wait(120);
  check('Scan on a 1-tap card runs a one-click task', (await ops('oneclick')).some(c => c.a.tool === 'appcleaner') && (await ops('scan')).length === 0);
  await ev(() => { const c = document.querySelector('#sdCard-appcleaner .sd-one input'); c.checked = false; c.dispatchEvent(new Event('change')); }); await wait(100);
  check('turning it off needs no question', await ev(() => !document.getElementById('mpAskModal').classList.contains('show') && !SD.one.appcleaner));

  // ---- the result, dismissed; Delete from the card ----
  await emit({ ev: 'done', tool: 'appcleaner', d: { kind: 'delete', status: 'ok', primary: '7 expendable item(s) deleted', secondary: 'Freed 6.0 MB space.' } });
  await ev(() => { window.__st.appcleaner = { tool: 'appcleaner', state: 'idle', hasData: false, lastResult: { kind: 'delete', status: 'ok', primary: '7 expendable item(s) deleted', secondary: 'Freed 6.0 MB space.' } }; sdApplyState({ tools: window.__st, running: 0, queued: 0 }); }); await wait(100);
  check('a receipt shows what was freed with an X', await ev(() => { const c = document.getElementById('sdCard-appcleaner'); return /7 expendable item\(s\) deleted/.test(c.innerText) && /Freed 6\.0 MB space\./.test(c.innerText) && !!c.querySelector('.sd-x'); }));
  await ev(() => { window.__calls.length = 0; }); await page.click('#sdCard-appcleaner .sd-x'); await wait(150);
  check('the X discards the result', (await ops('discard')).some(c => c.a.tool === 'appcleaner'));
  await ev(() => { window.__st.appcleaner = { tool: 'appcleaner', state: 'idle', hasData: false }; window.__st.deduplicator = { tool: 'deduplicator', state: 'ready', hasData: true, summary: { primary: '1 duplicate set(s) found', secondary: '2.0 MB is occupied by duplicates', itemCount: 1, groupCount: 1, bytes: 2097152 } }; sdApplyState({ tools: window.__st, running: 0, queued: 0 }); }); await wait(100);
  await ev(() => { window.__calls.length = 0; sdReview('deduplicator'); }); await wait(250);
  await page.click('#sdSheetBody .sd-row .sd-main'); await wait(300);
  check('the copy that is kept cannot be ticked and says so', await ev(() => { const r = document.querySelectorAll('#sdSheetBody .sd-row')[0]; return r.querySelector('input').disabled && /Kept/.test(r.innerText); }));
  await ev(() => { sdSheetClose(); window.__calls.length = 0; });
  await page.click('#sdCard-deduplicator .sd-act .danger'); await wait(150);
  check('Delete on a card asks the question of that tool', /Delete all but one copy in all sets of duplicate files\?/.test(await text('#mpAskText')));
  await okAsk();
  const d2 = (await ops('delete'))[0];
  check('and sends the delete of that tool with nothing taken out', !!d2 && d2.a.tool === 'deduplicator' && d2.a.selection.dropGroups.length === 0 && d2.a.selection.dropItems.length === 0 && d2.a.selection.options.deleteAll === false, JSON.stringify(d2));

  // ---- Delete all results ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdDelAll'); await wait(150);
  check('Delete all results asks once, listing the tools with data', /Delete results in all tools\?/.test(await text('#mpAskText')) && /SystemCleaner/.test(await text('#mpAskText')) && /Deduplicator/.test(await text('#mpAskText')));
  await okAsk();
  check('it deletes in every tool that has data', (await ops('delete')).map(c => c.a.tool).join() === 'systemcleaner,deduplicator', JSON.stringify((await ops('delete')).map(c => c.a.tool)));

  // ---- the accessibility service ----
  check('the card says the service is off and consent is not given', /Service off/.test(await text('#sdAcsChips')) && /Consent not given/.test(await text('#sdAcsChips')));
  await ev(() => { window.__calls.length = 0; sdAcsConsent(); }); await wait(150);
  check('consent explains what the service does and that it stops with the screen', await ev(() => /Clear cache/.test(document.getElementById('mpAskText').innerText) && /turns off or locks/.test(document.getElementById('mpAskText').innerText)));
  await okAsk();
  check('I agree sends the consent and the card shows it', (await ops('acs')).some(c => c.a.op === 'consent') && /Consent given/.test(await text('#sdAcsChips')));
  await ev(() => { window.__acs.enabled = true; window.__acs.connected = true; return sdAcsOp('status'); }); await wait(150);
  check('with the service on and consent given the card says it is ready', /Service on/.test(await text('#sdAcsChips')) && /Ready/.test(await text('#sdAcsNote')));
  await ev(() => { window.__st.appcleaner = { tool: 'appcleaner', state: 'ready', hasData: true, summary: { primary: '7 expendable item(s) found', secondary: '6.0 MB can be freed', itemCount: 7, groupCount: 2, bytes: 6291456 } }; sdApplyState({ tools: window.__st, running: 0, queued: 0 }); window.__calls.length = 0; sdDeleteTool('appcleaner'); }); await wait(150);
  check('AppCleaner\'s delete question mentions the accessibility service', /accessibility service/.test(await text('#mpAskText')));
  await okAsk();
  check('and its delete asks for the automation', (await ops('delete')).some(c => c.a.selection.options.useAutomation === true && c.a.selection.options.includeInaccessible === true));
  await page.screenshot({ path: 'sdm_acs.png' });

  // ---- exclusion manager ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdBtns2 .mode-action-btn:nth-child(2)'); await wait(300);
  check('the manager lists the exclusions with their tools and marks the defaults', await ev(() => { const t = document.getElementById('sdSheetBody').innerText; return /Exclusion manager/.test(document.getElementById('sdSheetTitle').innerText) && /\/data\/rootfs/.test(t) && /default/.test(t) && /Keeper/.test(t) && /All tools/.test(t) && /SystemCleaner/.test(t); }));
  await page.screenshot({ path: 'sdm_exclusions.png' });
  await ev(() => { sdExclEdit(); }); await wait(200);
  check('Create exclusion offers App, Path and Segment and the affected tools', await ev(() => /App/.test(document.getElementById('sdSheetBody').innerText) && /Path/.test(document.getElementById('sdSheetBody').innerText) && /Segment/.test(document.getElementById('sdSheetBody').innerText) && /Affected tools/i.test(document.getElementById('sdSheetBody').innerText)));
  await ev(() => { sdExclSave(); }); await wait(100);
  check('saving an app exclusion with no app chosen says so', /Choose an app/.test(await text('#toast, .toast, #toastMsg') .catch(() => '')) || (await ops('exclusionSave')).length === 0);
  await ev(() => { document.getElementById('sdEeQ').value = 'photo'; sdEeSearch('photo'); }); await wait(500);
  await ev(() => { sdEePick(0); }); await wait(100);
  await ev(() => { sdEeTag('appcleaner'); sdEeTag('corpsefinder'); }); await wait(50);
  await ev(() => { sdExclSave(); }); await wait(300);
  const sv = (await ops('exclusionSave'))[0];
  check('an app exclusion for two tools is saved with its package and tags', !!sv && sv.a.exclusion.kind === 'pkg' && sv.a.exclusion.pkg === 'com.photos' && sv.a.exclusion.tags.sort().join() === 'appcleaner,corpsefinder', JSON.stringify(sv));
  check('after saving the list is back with the new one', await ev(() => /Photos|com\.photos/.test(document.getElementById('sdSheetBody').innerText) && !SD.stack.length));
  await ev(() => { sdExclEdit(); sdEeKind('path'); document.getElementById('sdEePath').value = 'nonsense'; sdExclSave(); }); await wait(100);
  check('a path that is not absolute is refused (nothing saved)', (await ops('exclusionSave')).length === 1);
  await ev(() => { document.getElementById('sdEePath').value = '/storage/emulated/0/DCIM/'; sdExclSave(); }); await wait(300);
  check('a path exclusion is saved without the trailing slash', (await ops('exclusionSave')).some(c => c.a.exclusion.kind === 'path' && c.a.exclusion.path === '/storage/emulated/0/DCIM' && c.a.exclusion.tags.join() === 'general'));
  await ev(() => { sdExclEdit(); sdEeKind('segment'); document.getElementById('sdEeSeg').value = 'DCIM/Camera'; SD.ee.partial = true; sdExclSave(); }); await wait(300);
  check('a segment exclusion is saved with its segments and options', (await ops('exclusionSave')).some(c => c.a.exclusion.kind === 'segment' && c.a.exclusion.segments.join('/') === 'DCIM/Camera' && c.a.exclusion.allowPartial === true && c.a.exclusion.ignoreCase === true));
  await ev(() => { sdExclRemove(1); }); await wait(150);
  check('removing asks "Remove this exclusion?"', /Remove this exclusion\?/.test(await text('#mpAskTitle')));
  await okAsk();
  check('and removes it by id', (await ops('exclusionRemove')).some(c => c.a.ids[0] === 'PkgExclusion-com.keep'));
  await ev(() => { sdExclRestore(); }); await wait(120); await okAsk();
  check('Restore defaults asks and sends the restore', (await ops('exclusionsRestoreDefaults')).length === 1);
  await ev(() => { sdExclExport(); }); await wait(200);
  check('Export copies the exclusions', await ev(() => window.__copied === '{"exclusions":1}'));
  await ev(() => { sdSheetClose(); });

  // ---- History ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdBtns2 .mode-action-btn:nth-child(1)'); await wait(300);
  check('History lists the reports with the status of each and the totals', await ev(() => { const t = document.getElementById('sdSheetBody').innerText; return /History/.test(document.getElementById('sdSheetTitle').innerText) && /SystemCleaner/.test(t) && /Done/.test(t) && /Partial success/.test(t) && /Stopped because the screen was off/.test(t) && /6\.0 MB.*freed/.test(t); }), await text('#sdSheetBody'));
  await page.screenshot({ path: 'sdm_history.png' });
  await page.click('#sdSheetBody .sd-row .sd-main'); await wait(300);
  check('a report with paths opens them', await ev(() => /Affected paths/.test(document.getElementById('sdSheetTitle').innerText) && /a\.log/.test(document.getElementById('sdSheetBody').innerText) && /deleted/.test(document.getElementById('sdSheetBody').innerText)) && (await ops('historyPaths')).some(c => c.a.id === 'h1'));
  await page.click('#sdSheetBack'); await wait(200);
  check('Back returns to the list', await ev(() => /History/.test(document.getElementById('sdSheetTitle').innerText) && /Partial success/.test(document.getElementById('sdSheetBody').innerText)));
  await ev(() => { sdHistoryReset(); }); await wait(120);
  check('Reset all asks and says what it does', /set all statistics to zero/.test(await text('#mpAskText')));
  await okAsk();
  check('and resets', (await ops('historyReset')).length === 1);
  await ev(() => { sdSheetClose(); });

  // ---- settings ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#sdCard-systemcleaner .sd-gear'); await wait(300);
  const nSet = await ev(() => document.querySelectorAll('#sdSheetBody .sd-set').length);
  check('a tool\'s settings are drawn from the schema (one row per setting, grouped)', nSet >= 8 && /SystemCleaner settings/.test(await text('#sdSheetTitle')), String(nSet));
  await page.screenshot({ path: 'sdm_settings.png' });
  await ev(() => { document.querySelector('#sdSheetBody .sd-set input[type=checkbox]').click(); }); await wait(150);
  const ss = (await ops('settingsSet'))[0];
  check('a switch is saved by its key', !!ss && ss.a.tool === 'systemcleaner' && typeof ss.a.key === 'string' && typeof ss.a.value === 'boolean', JSON.stringify(ss));
  await ev(() => { sdSheetClose(); sdSettingsOpen('general'); }); await wait(300);
  check('the general settings show the dry run and the retention days', await ev(() => { const t = document.getElementById('sdSheetBody').innerText; return /Dry run/.test(t) && /General reports/.test(t) && /Detailed path information/.test(t); }));
  // schema features: a row that depends on a switch, days for a setting kept in milliseconds, the deletion strategy editor
  await ev(() => { sdSheetClose(); window.__settings.systemcleaner = { 'filter.screenshots.enabled': false }; sdSettingsOpen('systemcleaner'); }); await wait(300);
  check('a row that depends on a switch is hidden while the switch is off', await ev(() => !/Screenshot Age/.test(document.getElementById('sdSheetBody').innerText)));
  await ev(() => { window.__settings.systemcleaner = { 'filter.screenshots.enabled': true, 'filter.screenshots.age': 7 * 86400000 }; sdSheetClose(); sdSettingsOpen('systemcleaner'); }); await wait(300);
  check('it shows when the switch is on, and a duration kept in milliseconds is shown in days', await ev(() => { const r = [...document.querySelectorAll('#sdSheetBody .sd-set')].find(x => /Screenshot Age/.test(x.innerText)); return !!r && r.querySelector('input[type=number]').value === '7'; }));
  await ev(() => { window.__calls.length = 0; const r = [...document.querySelectorAll('#sdSheetBody .sd-set')].find(x => /Screenshot Age/.test(x.innerText)); const i = r.querySelector('input'); i.value = '3'; i.dispatchEvent(new Event('change')); }); await wait(150);
  const sa = (await ops('settingsSet'))[0];
  check('and saved back in milliseconds', !!sa && sa.a.key === 'filter.screenshots.age' && sa.a.value === 3 * 86400000, JSON.stringify(sa));
  await ev(() => { sdSheetClose(); window.__settings.deduplicator = {}; sdSettingsOpen('deduplicator'); }); await wait(300);
  check('the Deduplicator shows its deletion strategy as an ordered list with a mode for each criterion', await ev(() => { const t = document.getElementById('sdSheetBody').innerText; return /Deletion strategy/.test(t) && /1\. Duplicate type/.test(t) && /7\. /.test(t); }));
  await ev(() => { window.__calls.length = 0; [...document.querySelectorAll('#sdSheetBody button')].find(b => /Move down/.test(b.getAttribute('aria-label') || '')).click(); }); await wait(150);
  const ar = (await ops('settingsSet'))[0];
  check('moving a criterion down saves the new order as JSON', !!ar && ar.a.key === 'arbiter.config' && JSON.parse(ar.a.value).criteria[0].criteriumType === 'PREFERRED_PATH', JSON.stringify(ar));
  await ev(() => { sdSheetClose(); });

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
