// Bottom navigation: the bar slides away while the soft keyboard is up (a text field in use, or the window much shorter), while a sheet or dialog is open, in the full-screen Terminal,
// the real terminal and the first-launch permission sheet, and until the page has started; it comes back afterwards; it stays on Settings
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 740 } });
  page.bottomNav = true;
  page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(mock.initScript, {});
  // the start-up: the bar is away until the page is up
  await page.addInitScript(() => { window.__bootSeen = []; document.addEventListener('DOMContentLoaded', () => { const n = document.getElementById('bnav'); window.__bootSeen.push(n.classList.contains('bnav-off')); }); });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  // the bar's slide takes a moment (longer on a busy machine): wait for the state instead of a fixed time
  const wait = (fn) => page.waitForFunction(fn, null, { timeout: 4000 }).then(() => true, () => false);
  const away = () => wait(() => { const n = document.getElementById('bnav'); return n.classList.contains('bnav-off') && getComputedStyle(n).visibility === 'hidden' && n.hasAttribute('inert') && document.documentElement.classList.contains('bnav-away'); });
  const here = () => wait(() => { const n = document.getElementById('bnav'); return !n.classList.contains('bnav-off') && getComputedStyle(n).visibility === 'visible' && !n.hasAttribute('inert') && !document.documentElement.classList.contains('bnav-away'); });
  const settle = () => page.waitForTimeout(450);
  check('1. the bar was away while the page started, and is here now', (await ev(() => window.__bootSeen[0])) === true && await here());
  check('   while it is away nothing needs room: --bnav-h is 0', await ev(() => { const h = document.getElementById('bnav'); document.documentElement.classList.add('bnav-away'); const v = getComputedStyle(document.documentElement).getPropertyValue('--bnav-h').trim(); document.documentElement.classList.remove('bnav-away'); return v; }) === '0px');

  // ---- the keyboard ----
  await page.focus('#searchInput'); await settle();
  check('2. a text field in use (the keyboard is up): the bar slides away', await away());
  await ev(() => document.activeElement.blur()); await page.waitForTimeout(700);
  check('   the field is left: it comes back', await here());
  await page.click('#bnav .bn-item[data-tab="terminal"]'); await page.waitForTimeout(500);
  const fid = await ev(() => { const e = [...document.querySelectorAll('.view-content.active input[type="text"], .view-content.active textarea, .view-content.active input:not([type])')].find(x => x.offsetParent !== null && !x.disabled); if (!e) return ''; if (!e.id) e.id = 'bnProbe'; return e.id; });
  await page.focus('#' + fid); await settle();
  check('   (another tab\'s field too: ' + fid + ')', fid !== '' && await away());
  await ev(() => document.activeElement.blur()); await page.waitForTimeout(700);
  // a button or a checkbox has focus without a keyboard: the bar stays
  await page.click('#bnav .bn-item[data-tab="apps"]'); await page.waitForTimeout(300);
  await page.focus('#prefsHeaderBtn'); await settle();
  check('   a focused button is no keyboard: the bar stays', await here());
  // the window much shorter with a field in use (a keyboard that resizes the window)
  await page.focus('#searchInput'); await page.setViewportSize({ width: 360, height: 400 }); await settle();
  check('3. the window a lot shorter with a field in use: away', await away());
  await ev(() => document.activeElement.blur()); await page.setViewportSize({ width: 360, height: 740 }); await page.waitForTimeout(700);
  check('   and back at the full height', await here());

  // ---- sheets and dialogs ----
  for (const id of ['whatsNewModal', 'modesModal', 'permModal', 'inspectorModal', 'shotViewer', 'cheatSheetModal']) {
    await ev(i => document.getElementById(i).classList.add('show'), id); await settle();
    const a = await away();
    await ev(i => document.getElementById(i).classList.remove('show'), id); await settle();
    check('4. ' + id + ' open: the bar is away; closed: it is back', a && await here());
  }
  // More is a sheet too, but the bar stays under it (it is where the sheet came from)
  await page.click('#bnav .bn-item[data-more]'); await settle();
  check('   the More sheet does not send the bar away', await here());
  await page.keyboard.press('Escape'); await ev(() => bnavMoreClose());

  // ---- the full-screen Terminal and the real terminal ----
  await ev(() => switchView('terminal')); await page.waitForTimeout(500);
  await ev(() => { document.getElementById('txPaneTerminal').classList.add('tx-full'); }); await settle();
  const a1 = await away();
  await ev(() => { document.getElementById('txPaneTerminal').classList.remove('tx-full'); }); await settle();
  check('5. the full-screen Terminal: away; leaving it: back', a1 && await here());
  await ev(() => { document.getElementById('rtOverlay').classList.add('show'); }); await settle();
  const a2 = await away();
  await ev(() => { document.getElementById('rtOverlay').classList.remove('show'); }); await settle();
  check('   the real terminal overlay: away; closed: back', a2 && await here());

  // ---- Settings: the bar stays, no slot lit ----
  await ev(() => switchView('prefs')); await settle();
  check('6. on Settings (the gear) the bar is here and no slot is lit', await here() && await ev(() => document.querySelectorAll('#bnav .bn-item.active').length) === 0);
  // one handler rule: Back with a sheet open closes the sheet and leaves the bar
  await ev(() => { document.getElementById('whatsNewModal').classList.add('show'); }); await settle();
  await ev(() => handleAndroidBack()); await settle();
  check('7. Android Back closes the sheet and the bar returns', !(await ev(() => document.getElementById('whatsNewModal').classList.contains('show'))) && await here());

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad) process.exit(1);
})();
