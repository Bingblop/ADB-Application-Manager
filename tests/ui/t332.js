// The long intro paragraphs at the top of SD Maid (and its credit), Saved lists (Quick actions), Hidden Settings, the four App Stores, the Default Ask Agent
// and the Morphe credit show two lines ending in "…". The text itself opens and closes them: a button with aria-expanded (Enter and Space too), so no new
// words are needed. A paragraph with links in it is not made a button (a link inside a button): a tap outside its links opens it, a tap on a link only
// follows the link, and moving into a link with the keyboard opens it. A paragraph that fits in two lines is left as it is. The whole text stays in the page.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
const initMock = () => {
  const b = window.AndroidBridge = window.AndroidBridge || {};
  window.__urls = [];
  Object.assign(b, {
    vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    saveStore() { return true; }, loadStore() { return ''; }, saveSetting() {}, loadSetting() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    loadPackages() { return JSON.stringify([{ pkg: 'com.a.one', name: 'Alpha', isSystem: false }]); }, openUrl(u) { window.__urls.push(u); },
    getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
  });
};
// where each paragraph is: [name, view, selector, store tab]
const PLACES = [
  ['SD Maid intro', 'sdm', '#sdTop > .color-card-subtitle'],
  ['SD Maid credit', 'sdm', '#sdTop .sd-credit'],
  ['Saved lists: Quick actions', 'saved-lists', '#quickActionsHelp .color-card-subtitle'],
  ['Hidden Settings intro', 'settings', '#view-settings .color-card-subtitle.clamp-2'],
  ['ShizuStore intro', 'store', '#substore-shizu .color-card-subtitle', 'shizu'],
  ['GitHub store intro', 'store', '#substore-github .color-card-subtitle', 'github'],
  ['F-Droid store intro', 'store', '#substore-fdroid .color-card-subtitle', 'fdroid'],
  ['Orion store intro', 'store', '#substore-orion .color-card-subtitle', 'orion'],
  ['Default Ask Agent', 'prefs', '#agentCard > .color-card-subtitle'],
  ['Morphe credit', 'morphe', '#view-morphe .mp-credit'],
];
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (w, lang) => {
    const page = await b.newPage({ viewport: { width: w, height: 860 }, hasTouch: true });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(w + ' ' + e.message));
    await page.addInitScript(sdb.initScript);
    await page.addInitScript(initMock);
    await page.goto(PAGE); await page.waitForTimeout(700);
    if (lang) { await page.evaluate(l => { const s = document.getElementById('langSelect'); if (s) s.value = l; chooseLanguage(l); }, lang); await page.waitForTimeout(600); }
    return page;
  };
  const show = (page, view, store) => page.evaluate(([v, s]) => { switchView(v); if (s) switchStoreTab(s); window.scrollTo(0, 0); }, [view, store]).then(() => page.waitForTimeout(250));
  const state = (page, sel) => page.evaluate(sel => {
    const e = document.querySelector(sel), cs = getComputedStyle(e);
    const one = e.cloneNode(false); one.classList.add('open'); one.textContent = 'x'; e.after(one); const lh = one.clientHeight; one.remove();      // the height of one line of it
    return { lines: Math.round(e.clientHeight / lh), h: e.clientHeight, full: e.scrollHeight, clamped: e.classList.contains('is-clamped'), open: e.classList.contains('open'), role: e.getAttribute('role'), tab: e.tabIndex,
      expanded: e.getAttribute('aria-expanded'), links: e.querySelectorAll('a').length, ellipsis: cs.webkitLineClamp, text: e.textContent.replace(/\s+/g, ' ').trim().length };
  }, sel);

  // 1) every intro, at 400 px: two lines, then the whole text on a tap, then two lines again
  const page = await open(400);
  const full = {};
  for (const [name, view, sel, store] of PLACES) {
    await show(page, view, store);
    const s0 = await state(page, sel);
    full[name] = s0.text;
    const asButton = s0.links ? s0.role === null : s0.role === 'button' && s0.tab === 0 && s0.expanded === 'false';
    check(name + ': two lines, ending in "…", with more to read; ' + (s0.links ? 'it has links, so it is not made a button' : 'a button saying it is closed'), s0.clamped && !s0.open && s0.lines === 2 && s0.full > s0.h + 4 && s0.ellipsis === '2' && asButton, JSON.stringify(s0));
    await page.locator(sel).click({ position: { x: 10, y: 8 } }); await page.waitForTimeout(120);
    const s1 = await state(page, sel);
    check('   a tap on it opens the whole text' + (s0.links ? '' : ' (aria-expanded true)'), s1.open && s1.h >= s1.full - 1 && s1.lines > 2 && (s0.links || s1.expanded === 'true'), JSON.stringify({ lines: s1.lines, h: s1.h, full: s1.full, expanded: s1.expanded }));
    await page.locator(sel).click({ position: { x: 10, y: 8 } }); await page.waitForTimeout(120);
    const s2 = await state(page, sel);
    check('   another tap closes it again', !s2.open && s2.lines === 2 && (s0.links || s2.expanded === 'false'), JSON.stringify({ lines: s2.lines, expanded: s2.expanded }));
  }

  // 2) the keyboard
  await show(page, 'settings');
  const hs = '#view-settings .color-card-subtitle.clamp-2';
  await page.evaluate(sel => document.querySelector(sel).focus(), hs);
  await page.keyboard.press('Enter'); await page.waitForTimeout(80);
  const k1 = await state(page, hs);
  await page.keyboard.press(' '); await page.waitForTimeout(80);
  const k2 = await state(page, hs);
  check('2. Enter opens a focused intro and Space closes it (the page does not scroll)', k1.open && k1.expanded === 'true' && !k2.open && k2.expanded === 'false' && (await page.evaluate(() => scrollY)) === 0, JSON.stringify([k1.expanded, k2.expanded]));

  // 3) the links in a credit
  await show(page, 'morphe');
  const mp = '#view-morphe .mp-credit';
  await page.locator(mp + ' a').first().click(); await page.waitForTimeout(150);
  const l1 = await state(page, mp);
  check('3. a tap on a link of the Morphe credit follows the link and does not open the text', !l1.open && (await page.evaluate(() => window.__urls.join())) === 'https://morphe.software', await page.evaluate(() => window.__urls.join()));
  await show(page, 'sdm');
  const sc = '#sdTop .sd-credit';
  const before = await state(page, sc);
  await page.evaluate(() => document.querySelector('#sdBtns2 button:last-child').focus());
  await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.waitForTimeout(80);
  const l2 = await state(page, sc);
  const seen = await page.evaluate(sel => { const a = document.activeElement, c = document.querySelector(sel).getBoundingClientRect(), r = a.getBoundingClientRect(); return { link: a.innerText, inside: r.top >= c.top - 1 && r.bottom <= c.bottom + 1 }; }, sc);
  check('   moving into the SD Maid credit with Tab opens it, so its last link (once hidden) can be seen', !before.open && l2.open && l2.h >= l2.full - 1 && seen.link === 'SD Maid SE on Google Play' && seen.inside, JSON.stringify(seen));

  // 4) the whole text is still in the page (a screen reader reads it all)
  check('4. the text of each one is whole in the page', Object.keys(full).every(k => full[k] > 120), JSON.stringify(full));

  // 5) one that fits in two lines is left alone: no "…", not a button, a tap does nothing
  const fit = await page.evaluate(async () => {
    switchView('settings');
    const e = document.querySelector('#view-settings .color-card-subtitle.clamp-2');
    e.textContent = 'Read and change the settings.';
    await new Promise(r => setTimeout(r, 120));
    e.click(); await new Promise(r => setTimeout(r, 60));
    return { clamped: e.classList.contains('is-clamped'), role: e.getAttribute('role'), tab: e.getAttribute('tabindex'), expanded: e.getAttribute('aria-expanded'), open: e.classList.contains('open') };
  });
  check('5. an intro that fits in two lines (here: changed text, as a new language may do) is not a button and a tap does nothing', !fit.clamped && fit.role === null && fit.tab === null && fit.expanded === null && !fit.open, JSON.stringify(fit));
  await page.close();

  // 6) narrow phones and German
  for (const [w, lang] of [[320, ''], [360, ''], [360, 'de']]) {
    const p = await open(w, lang);
    const out = [];
    for (const [name, view, sel, store] of PLACES) {
      await show(p, view, store);
      const s = await state(p, sel);
      if (!(s.clamped && s.lines === 2 && s.full > s.h + 4)) out.push(name + ' ' + JSON.stringify(s));
    }
    const de = lang ? await p.evaluate(() => document.querySelector('#agentCard > .color-card-subtitle').textContent.slice(0, 30)) : '';
    check('6. ' + w + ' px' + (lang ? ' in German' : '') + ': every one is two lines with more to read', out.length === 0, out.join(' | ') + (lang ? ' ' + de : ''));
    if (w === 360) { await show(p, 'sdm'); await p.screenshot({ path: 'sdm_' + w + (lang ? '_' + lang : '') + '.png', clip: { x: 0, y: 0, width: w, height: 420 } }); }
    await p.close();
  }

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})();
