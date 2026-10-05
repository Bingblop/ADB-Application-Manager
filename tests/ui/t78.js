// v7.0: no emoji anywhere in the app. The only ones that stay are the settings gears: the one in the header (the app's settings),
// the one on every app in the list (that app's settings), and (v7.8, asked for with the Terminal) the Terminal's own settings button. Checked in the sources (page, changelog shown by What's new, widget layout, Java)
// and in the page as it is drawn: every text, title, label and placeholder on every tab and in the main sheets.
const fs = require('fs');
const path = require('path');
const { fileURLToPath } = require('url');
const { chromium, PAGE, REPO } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const EMOJI = /\p{Extended_Pictographic}|️|⃣/gu;
const GEAR = /⚙️?/gu;

function walk(dir, out) {
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f);
    if (fs.statSync(p).isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}
// A symbol can be written as itself or as an escape (Java and JS "\u26a1" and "\uD83D\uDE00", JS "\u{26A1}", HTML "&#x26A1;" and "&#9889;"): look at what they mean
const decode = (text) => text
  .replace(/\\u\{([0-9a-fA-F]{1,6})\}/g, (m, h) => String.fromCodePoint(parseInt(h, 16)))
  .replace(/\\u([0-9a-fA-F]{4})/g, (m, h) => String.fromCharCode(parseInt(h, 16)))
  .replace(/&#x([0-9a-fA-F]{1,6});/g, (m, h) => String.fromCodePoint(parseInt(h, 16)))
  .replace(/&#(\d{1,7});/g, (m, d) => String.fromCodePoint(+d));
const found = (text) => { const m = decode(text).match(EMOJI); return m ? Array.from(new Set(m)).map(c => 'U+' + c.codePointAt(0).toString(16)) : []; };

(async () => {
  // 1) the sources
  check('0. the scan reads symbols written as escapes too (\\u26a1, a surrogate pair, \\u{1F4A5}, &#x26A1;, &#9889;)', found('a \\u26a1 b').length === 1 && found('\\uD83D\\uDE00').length === 1 && found('\\u{1F4A5}').length === 1 && found('&#x26A1;').length === 1 && found('&#9889;').length === 1 && found('plain \\u00a0 text &#233; &#8364;').length === 0);
  const page_src = fs.readFileSync(fileURLToPath(PAGE), 'utf8');                       // the page under test (assets/index.html unless PAGE_URL says otherwise)
  const withoutGears = decode(page_src).replace(GEAR, '');
  check('1. the page has no emoji but the gears (© is not an emoji and is left)', found(withoutGears.replace(/©/g, '')).length === 0, JSON.stringify(found(withoutGears.replace(/©/g, ''))));
  check('   exactly three gears are written in it: the header button, the button on an app row and the Terminal settings button', (decode(page_src).match(GEAR) || []).length === 3);
  check('   the header gear is the first, the row gear the one named App Settings, the third the Terminal\'s', /id="prefsHeaderBtn"[^>]*>⚙️<\/div>/u.test(page_src) && /title="App Settings"[^>]*>⚙️<\/button>/u.test(page_src) && /id="txSettingsBtn"[^>]*>⚙️<\/button>/u.test(page_src));
  check('   the changelog the What\'s new screen shows has none', found(fs.readFileSync(path.join(REPO, 'CHANGELOG.md'), 'utf8')).length === 0, JSON.stringify(found(fs.readFileSync(path.join(REPO, 'CHANGELOG.md'), 'utf8'))));
  const res = walk(path.join(REPO, 'res'), []).filter(f => /\.(xml|txt)$/.test(f)).filter(f => found(fs.readFileSync(f, 'utf8')).length);
  check('   the layouts and resources (home-screen widget, tiles) have none', res.length === 0, res.join(' '));
  const java = walk(path.join(REPO, 'src'), []).filter(f => f.endsWith('.java')).filter(f => found(fs.readFileSync(f, 'utf8')).length);
  check('   the Java sources have none (they put text on the page and in notifications)', java.length === 0, java.join(' '));

  // 2) the page as it is drawn
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  const apps = Array.from({ length: 12 }, (_, i) => ({ pkg: 'com.example.app' + i, name: 'App ' + i, isSystem: i % 3 === 0, isRunning: i % 4 === 0, isFrozen: i === 5, isSuspended: i === 6, isUninstalled: i === 7 }));
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps });
  await page.goto(PAGE);
  await page.waitForFunction(() => document.getElementById('statTotal').innerText === '12');
  const ev = (fn, arg) => page.evaluate(fn, arg);
  // everything the person can read: text of every element (shown or not, a sheet that is closed is text too) and the attributes that are read aloud or shown on hover
  const scan = () => ev(() => {
    const re = /\p{Extended_Pictographic}|️|⃣/u;
    const gear = /^⚙️?$/u;
    const hits = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = walker.nextNode())) {
      const p = n.parentElement;
      if (!p || /^(SCRIPT|STYLE)$/.test(p.tagName)) continue;
      const t = n.nodeValue.trim();
      if (!t || !re.test(t)) continue;
      if (gear.test(t) && (p.id === 'prefsHeaderBtn' || p.id === 'txSettingsBtn' || (p.classList.contains('btn-mini') && p.getAttribute('title') === 'App Settings'))) continue;
      hits.push((p.id || p.className || p.tagName) + ': ' + t.slice(0, 40));
    }
    document.querySelectorAll('[title],[aria-label],[placeholder],[alt]').forEach(e => ['title', 'aria-label', 'placeholder', 'alt'].forEach(a => { const v = e.getAttribute(a); if (v && re.test(v)) hits.push(a + ': ' + v.slice(0, 40)); }));
    return hits;
  });
  const gears = () => ev(() => ({ header: document.getElementById('prefsHeaderBtn').innerText.replace(/️/g, ''), rows: document.querySelectorAll('.app-card .btn-mini[title="App Settings"]').length, cards: document.querySelectorAll('.app-card').length }));
  let g = await gears();
  check('2. the header shows the gear and every app in the list has its own gear', g.header === '⚙' && g.rows === g.cards && g.cards === 12, JSON.stringify(g));
  const tabs = ['apps', 'saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'updates', 'store', 'logcat', 'about', 'prefs'];
  for (const t of tabs) {
    await ev(k => switchView(k), t);
    await page.waitForTimeout(120);
    const hits = await scan();
    check('   ' + t + ': no emoji in what is shown or hidden on the page', hits.length === 0, JSON.stringify(hits.slice(0, 5)));
  }
  await ev(() => switchView('apps'));
  const sheets = [['the single-app sheet', () => openInspector('com.example.app1')], ['Working Modes', () => openWorkingModesModal()], ['Permissions', () => openPermSheet('manual')],
    ['Profiles', () => openProfiles()], ['Backups', () => openBackups()], ['the cheat sheet', () => openCheatSheet()], ['the debloat history', () => openHistoryModal()], ['the batch bar', () => { toggleSelectPkg('com.example.app1'); expandBatchPanel(); }]];
  for (const [name, open] of sheets) {
    try { await ev(open); } catch (e) { check('   could not open ' + name + ': ' + e.message, false); continue; }
    await page.waitForTimeout(200);
    const hits = await scan();
    check('   ' + name + ': no emoji', hits.length === 0, JSON.stringify(hits.slice(0, 5)));
    await ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); if (typeof clearBatchSelection === 'function') clearBatchSelection(); });
  }
  // a toast carries no icon any more
  await ev(() => showToast('Hello'));
  check('   a toast is just its message (no icon element)', (await ev(() => document.getElementById('toast').innerText.trim())) === 'Hello' && (await ev(() => !document.getElementById('toastIcon'))));
  // a list row in the file manager and the archive browser: folders are marked in words (a slash), not by a picture
  await ev(() => { switchView('files'); });
  await page.waitForTimeout(200);
  const fm = await ev(() => Array.from(document.querySelectorAll('#fmList .perm-name')).map(n => n.innerText));
  check('   folders in the file manager end in a slash (no folder picture)', fm.length > 0 && fm.some(n => /\/$/.test(n)) && fm.every(n => !/[\u{1F300}-\u{1FAFF}]/u.test(n)), JSON.stringify(fm));

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  process.exit(bad ? 1 : 0);
})();
