// Bug fixes of the Updater, the Morphe Patcher and the bottom bar: a badge update during a long press still opens the shortcut menu above the slot (the bar is redrawn,
// the slot is found again); a second Update All joins the apps still waiting instead of replacing the queue (none stays Queued forever); a row the app never answers
// becomes an error after the start watchdog; a cancelled update (stage cancelled) is not a failure in the history; one switchView('morphe') calls onMorpheShown once
// (not seven times); mpChoice resolves to the index of the button that was tapped (it was -1, so Patch anyway / Skip them / Get the right APK did nothing).
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }
const updates = [1, 2, 3, 4].map(i => ({ pkg: 'org.example.app' + i, name: 'App ' + i, installedVersion: '1.0', availableVersion: '1.1', source: 'github', downloadUrl: 'https://github.com/x/y/a' + i + '.apk' }));
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 740 } });
  page.bottomNav = true; page.errors = [];
  page.on('pageerror', e => page.errors.push(e.message)); page.on('console', m => { if (m.type() === 'error' && !m.text().includes('Failed to load resource: net::ERR_FILE_NOT_FOUND')) page.errors.push('console: ' + m.text()); });
  await page.addInitScript(mock.initScript, { kv: { bnav_tip: 'true' }, updates });
  // a Morphe bridge that counts what the page asks for (the engine answers nothing here)
  await page.addInitScript(() => { window.__mpOps = []; window.AndroidBridge.morphe = (tag, op) => { window.__mpOps.push(op); setTimeout(() => window.onMorphe({ tag, ok: false, error: 'no engine in this mock' }), 5); }; });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const stages = () => ev(() => Object.fromEntries(Object.entries(updProgress).map(([k, v]) => [k, v.stage])));
  const slot = async tab => { const r = await page.locator('#bnav .bn-item[data-tab="' + tab + '"]').boundingBox(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, r }; };
  const menu = () => ev(() => { const m = document.getElementById('bnavMenu'); if (!m) return null; const q = m.getBoundingClientRect(); return { l: q.left, t: q.top, b: q.bottom, items: m.querySelectorAll('.bn-sc').length, slotConnected: !!(bnavMenuSt && bnavMenuSt.slot && bnavMenuSt.slot.isConnected) }; });

  // ---- 1. the long press survives a redraw of the bar ----
  let s = await slot('installer'); await page.mouse.move(s.x, s.y); await page.mouse.down(); await sleep(650);
  let m = await menu();
  check('1. a plain hold on the Installer slot opens the shortcut menu above it', !!m && m.items > 0 && m.b <= s.r.y + 1, JSON.stringify(m));
  await page.mouse.up(); await sleep(100); await ev(() => bnavMenuClose(true)); await sleep(100);
  s = await slot('installer'); await page.mouse.move(s.x, s.y); await page.mouse.down(); await sleep(200);
  const redrawn = await ev(() => { const before = document.querySelector('#bnav .bn-item[data-tab="installer"]'); bnavSetBadge('installer', 7); return !before.isConnected; });   // an update check / an install stage finishing re-renders the bar mid-hold
  await sleep(450);
  m = await menu();
  check('   a badge update 200 ms into the hold redraws the bar, and the menu still opens above the slot (not at the top-left corner)', redrawn && !!m && m.items > 0 && m.b <= s.r.y + 1 && m.t > 8 && m.slotConnected, JSON.stringify({ redrawn, m }));
  await page.mouse.up(); await sleep(100); await ev(() => bnavMenuClose(true)); await ev(() => bnavSetBadge('installer', 4)); await sleep(100);

  // ---- 2. a second Update All joins the queue ----
  await ev(() => switchView('updates')); await sleep(300);
  await ev(() => window.onUpdatesChecked(JSON.stringify({ updates: JSON.parse(window.AndroidBridge.getUpdateState()).updates, untracked: [], external: [], checked: 10, errors: 0, checkedAt: Date.now() })));
  await ev(() => updateAllUI());
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: true, pkgs: ['org.example.app1', 'org.example.app2', 'org.example.app3', 'org.example.app4'] }))); await sleep(100);
  const prog = (pkg, stage) => ev(a => window.onUpdateProgress(JSON.stringify({ pkg: a[0], stage: a[1], percent: 0, message: a[1] })), [pkg, stage]);
  await prog('org.example.app1', 'error'); await sleep(400);     // app2 starts
  await prog('org.example.app2', 'error'); await sleep(400);     // app3 starts, app4 still queued
  let st = await stages();
  check('2. after two failures the third app downloads, the fourth waits, the first two can be retried', st['org.example.app1'] === 'error' && st['org.example.app2'] === 'error' && st['org.example.app3'] === 'downloading' && st['org.example.app4'] === 'queued', JSON.stringify(st));
  check('   Update All offers the two that failed', await ev(() => document.getElementById('updAllBtn').innerText.replace(/\s+/g, ' ').trim()) === 'Update All (2)', await ev(() => document.getElementById('updAllBtn').innerText));
  await ev(() => updateAllUI());
  await ev(() => window.onUpdateAllAnswer(JSON.stringify({ ok: true, pkgs: ['org.example.app1', 'org.example.app2'] }))); await sleep(100);
  st = await stages();
  const q = await ev(() => updQueue.slice());
  check('   the second Update All adds them behind the app still waiting; nothing is dropped', q.join(',') === 'org.example.app4,org.example.app1,org.example.app2' && st['org.example.app3'] === 'downloading' && st['org.example.app4'] === 'queued' && st['org.example.app1'] === 'queued' && st['org.example.app2'] === 'queued', JSON.stringify({ q, st }));
  await prog('org.example.app3', 'done'); await sleep(400);
  await prog('org.example.app4', 'done'); await sleep(400);
  await prog('org.example.app1', 'done'); await sleep(400);
  await prog('org.example.app2', 'done'); await sleep(400);
  st = await stages();
  const installs = await ev(() => window.__calls.filter(c => /^install:/.test(c)).map(c => c.slice(8).replace('org.example.', '')));
  check('   every app was started once per approval, in queue order, and all four end Updated', installs.join(',') === 'app1,app2,app3,app4,app1,app2' && Object.values(st).every(x => x === 'done') && Object.keys(st).length === 4, JSON.stringify({ installs, st }));
  await ev(() => window.onUpdatesChecked(JSON.stringify({ updates: JSON.parse(window.AndroidBridge.getUpdateState()).updates, untracked: [], external: [], checked: 10, errors: 0, checkedAt: Date.now() }))); await sleep(100);
  st = await stages();
  check('   a new check leaves no row Queued', !Object.values(st).includes('queued') && await ev(() => [...document.querySelectorAll('#updContainer .upd-row button')].every(x => x.innerText.trim() !== 'Queued')), JSON.stringify(st));

  // ---- 3. the start watchdog, and a cancelled update ----
  await ev(() => { UPD_START_WAIT_MS = 300; updateOneUI('org.example.app1'); }); await sleep(100);
  check('3. a single Update starts as Downloading', (await stages())['org.example.app1'] === 'downloading');
  await sleep(500);
  const rowW = await page.locator('.upd-row:has-text("App 1")').innerText();
  check('   with no answer from the app it becomes an error that can be retried, not a row stuck at Downloading', (await stages())['org.example.app1'] === 'error' && /Could not start the update/.test(rowW) && /Retry/.test(rowW), rowW.replace(/\s+/g, ' '));
  const histBefore = await ev(() => getHistory().length);
  await ev(() => { UPD_START_WAIT_MS = 15000; updateOneUI('org.example.app2'); }); await sleep(50);
  await ev(() => window.onUpdateProgress(JSON.stringify({ pkg: 'org.example.app2', stage: 'cancelled', percent: 0, message: 'Update cancelled. Nothing was installed.' }))); await sleep(100);
  const rowC = await page.locator('.upd-row:has-text("App 2")').innerText();
  check('   a cancelled update shows its message with a plain Update button, and is not a failure in the history', /Update cancelled/.test(rowC) && /Update/.test(rowC) && !/Retry/.test(rowC) && await ev(() => getHistory().length) === histBefore, rowC.replace(/\s+/g, ' '));

  // ---- 4. one switchView('morphe') asks the engine once ----
  await ev(() => { isPrivilegedActive = true; switchView('morphe'); }); await sleep(800);
  const ops = await ev(() => window.__mpOps.slice());
  check('4. switchView(morphe) calls onMorpheShown once: one info and one sources request', ops.filter(o => o === 'info').length === 1 && ops.filter(o => o === 'sources').length === 1, JSON.stringify(ops));

  // ---- 5. mpChoice answers with the button that was tapped ----
  const r = await ev(async () => {
    const p = mpChoice({ title: 'T', text: 'x', buttons: ['Skip them and patch', 'Patch anyway', 'Get the right APK', 'Cancel'] });
    await new Promise(r => setTimeout(r, 50));
    document.querySelectorAll('#mpSheetBtns button')[1].click();
    const v = await p;
    const closed = !document.getElementById('mpSheet').classList.contains('show');
    const p2 = mpChoice({ title: 'T', text: 'x', buttons: ['A', 'B'] });
    await new Promise(r => setTimeout(r, 50));
    document.querySelectorAll('#mpSheetBtns button')[0].click();
    const first = await p2;
    const p3 = mpChoice({ title: 'T', text: 'x', buttons: ['A', 'B'] });
    await new Promise(r => setTimeout(r, 50));
    mpSheetClose();
    return { patchAnyway: v, closed, first, dismissed: await p3 };
  });
  check('5. "Patch anyway" resolves 1 and closes the sheet, the first button 0, and closing the sheet without a choice -1', r.patchAnyway === 1 && r.closed && r.first === 0 && r.dismissed === -1, JSON.stringify(r));

  check('errors', page.errors.length === 0, JSON.stringify(page.errors));
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
