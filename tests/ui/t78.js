// v7.0: no emoji anywhere in the app. The only one that stays is the settings gear of the Terminal (v7.8, asked for with the Terminal); the header's
// gear (the app's settings) is a monochrome SVG in the theme's text colour now, like the App Settings icon on every app in the list. Checked in the sources (page, changelog shown by What's new, widget layout, Java)
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
const LOCK = /\u{1F512}/gu;
const OKMARK = /\u2705/gu;         // v7.19.4 (asked for: "a green checkmark next to agents"): AK_OK_MARK, after an agent whose key was tested and works           // v7.9.22 (asked for): the lock on the button of a permission that cannot be toggled
// v7.18.0 (asked for, 2026-10-26: "show an emoji display of the top 5 emoji fonts"): the sixteen emoji the Emoji fonts card draws in each emoji
// font, written once in the page (FNT_EMO_SAMPLE) and once in the Java (EmojiSpecimen.SAMPLE); the strip is the emoji fonts' own art, not decoration
const EMO_SAMPLE = '😀😂🥰😎🥳😭👍👋❤️🔥🎉🐶🦄🍕🚀🌈';

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
  const withoutGears = decode(page_src).replace(GEAR, '').replace(LOCK, '').replace(OKMARK, '').split(EMO_SAMPLE).join('');
  check('1. the page has no emoji but the gears (© is not an emoji and is left)', found(withoutGears.replace(/©/g, '')).length === 0, JSON.stringify(found(withoutGears.replace(/©/g, ''))));
  check('   exactly one gear is written in it: the Terminal settings button (the header button and the app row button are SVG icons now)', (decode(page_src).match(GEAR) || []).length === 1);
  check('   exactly one green check mark is written in it (AK_OK_MARK, the agent lists of Settings and the Terminal)', (decode(page_src).match(OKMARK) || []).length === 1 && /const AK_OK_MARK = ' \\u2705'/.test(page_src));
  check('   exactly one lock is written in it (LOCK_ICON, used by every button that cannot change anything in the current state: permissions, app ops, activities, services, receivers, providers)', (decode(page_src).match(LOCK) || []).length === 1 && /const LOCK_ICON = '\u{1F512}';/u.test(page_src));
  check('   the emoji fonts\' sample is written once in it, as FNT_EMO_SAMPLE', decode(page_src).split(EMO_SAMPLE).length === 2 && page_src.includes("const FNT_EMO_SAMPLE = '" + EMO_SAMPLE + "';"));
  check('   it is the Terminal\'s; the header button holds an SVG gear', /id="prefsHeaderBtn"[^>]*><svg class="hdr-gear"/u.test(page_src) && /id="txSettingsBtn"[^>]*>⚙️<\/button>/u.test(page_src));
  check('   the changelog the What\'s new screen shows has none', found(fs.readFileSync(path.join(REPO, 'CHANGELOG.md'), 'utf8')).length === 0, JSON.stringify(found(fs.readFileSync(path.join(REPO, 'CHANGELOG.md'), 'utf8'))));
  const res = walk(path.join(REPO, 'res'), []).filter(f => /\.(xml|txt)$/.test(f)).filter(f => found(fs.readFileSync(f, 'utf8')).length);
  check('   the layouts and resources (home-screen widget, tiles) have none', res.length === 0, res.join(' '));
  // the one sample of EmojiSpecimen (SAMPLE) is left out of that file, once; any other emoji in it, or in any other file, counts
  const javaText = (f) => { const t = fs.readFileSync(f, 'utf8'); return path.basename(f) === 'EmojiSpecimen.java' ? t.replace('public static final String SAMPLE = "' + EMO_SAMPLE + '";', '') : t; };
  const java = walk(path.join(REPO, 'src'), []).filter(f => f.endsWith('.java')).filter(f => found(javaText(f)).length);
  check('   the Java sources have none (they put text on the page and in notifications)', java.length === 0, java.join(' '));

  // 2) the page as it is drawn
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  const apps = Array.from({ length: 12 }, (_, i) => ({ pkg: 'com.example.app' + i, name: 'App ' + i, isSystem: i % 3 === 0, isRunning: i % 4 === 0, isFrozen: i === 5, isSuspended: i === 6, isUninstalled: i === 7 }));
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, apps });
  await page.goto(PAGE);
  await page.waitForFunction(() => document.getElementById('countAll').innerText === '12');
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
      if (/^\u{1F512} /u.test(t) && p.classList.contains('locked') && (p.classList.contains('perm-toggle-btn') || p.classList.contains('op-mode-btn'))) continue;
      if (gear.test(t) && (p.id === 'prefsHeaderBtn' || p.id === 'txSettingsBtn' || (p.classList.contains('btn-mini') && p.getAttribute('title') === 'App Settings'))) continue;
      hits.push((p.id || p.className || p.tagName) + ': ' + t.slice(0, 40));
    }
    document.querySelectorAll('[title],[aria-label],[placeholder],[alt]').forEach(e => ['title', 'aria-label', 'placeholder', 'alt'].forEach(a => { const v = e.getAttribute(a); if (v && re.test(v)) hits.push(a + ': ' + v.slice(0, 40)); }));
    return hits;
  });
  const gears = () => ev(() => ({ header: document.querySelector('#prefsHeaderBtn > svg.hdr-gear') && document.getElementById('prefsHeaderBtn').innerText.trim() === '' ? 'svg' : document.getElementById('prefsHeaderBtn').innerText, rows: document.querySelectorAll('.app-card .ab-btn[data-ab="settings"] svg').length, cards: document.querySelectorAll('.app-card').length }));
  let g = await gears();
  check('2. the header shows the gear (an SVG, no text) and every app in the list has its Action Button icon (App Settings by default)', g.header === 'svg' && g.rows === g.cards && g.cards === 12, JSON.stringify(g));
  const tabs = ['apps', 'saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'overlays', 'updates', 'store', 'console', 'about', 'prefs'];
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
