// v7.17.0 Custom Fonts, the Make card's choices that would make a font that does not do what it says, and two small things around the tab. A variable
// font without a Weight axis keeps Bold at None (Android makes bold text from the Regular); choosing "Same variable font" for it says the Bold would
// look like the Regular. With a Weight axis, a Bold set at the Regular's weight or lighter says bold text would look like the rest; a heavier one says
// nothing. An own COLRv1 emoji file on Android 12 says it needs Android 13 and Build waits. A name made only of blank fillers (U+3164) is named as such
// and Build waits, and so is a name of only spaces; an emoji newer than the Unicode the page knows counts, and the bridge makes the font. That font,
// deleted from the Phone font list (a press and hold asks first, then Delete), leaves the list and takes the buttons under its build result with it
// (v7.19.1: My fonts and its preview faces are gone). Root Features has its "?" (HELP_LINKS) in the top card, beside the status tag and not over it,
// and it opens the guide at that tab. FULL-GUIDE's table of tabs says "right before …" as the tab bar has it, and gives the Phone font's gate with
// root (UID 0) as the app's lock does.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const NO_WEIGHT = 'This font has no Weight axis, so this Bold would look like the Regular. With None, Android makes bold text from the Regular.';
const NOT_HEAVIER = 'This Bold is no heavier than the Regular, so bold text would look like the rest of the text.';

async function open(b, opts) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
// the Phone font list: open it from its drop-down, read its rows (data-k: 'default' or the package), press and hold one with the mouse
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const listKeys = (page) => page.evaluate(() => [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => r.dataset.k));
async function hold(page, k) {
  const box = await page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.waitForTimeout(750); await page.mouse.up();
  await page.waitForTimeout(250);
}
const make = (page) => page.evaluate(() => {
  const sel = document.getElementById('fntBoldSel'), hint = document.getElementById('fntNameMsg');
  return { bold: sel.value, options: [...sel.options].map(o => o.value).join(), boldMsg: document.getElementById('fntBoldMsg').textContent, emojiMsg: document.getElementById('fntEmojiMsg').textContent,
    build: !document.getElementById('fntBuildBtn').disabled, hint: getComputedStyle(hint).display !== 'none' ? hint.textContent : '' };
});

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', picks: { regular: 'vfWidth' } });
    // ---- 1. a variable font without a Weight axis ----
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    const m0 = await make(page);
    check('1. no Weight axis: Bold stays None, "Same variable font" can still be chosen', m0.bold === 'none' && m0.options === 'none,instance,file' && m0.boldMsg === '' && m0.build, JSON.stringify(m0));
    await page.selectOption('#fntBoldSel', 'instance');
    await page.waitForTimeout(100);
    check('   chosen anyway: the Bold would look like the Regular, None is the better way', (await make(page)).boldMsg === NO_WEIGHT, (await make(page)).boldMsg);
    // ---- 2. with a Weight axis ----
    await page.evaluate(() => { window.__fmk.picks.regular = 'vf'; });
    await page.selectOption('#fntBoldSel', 'none');
    await page.evaluate(() => { fnt.boldTouched = false; });                     // as on a fresh card
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    const m1 = await make(page);
    check('2. a Weight axis that goes heavier: Bold is the same font at 700, nothing to say', m1.bold === 'instance' && await page.evaluate(() => fnt.coords.bold.wght) === 700 && m1.boldMsg === '', JSON.stringify(m1));
    await page.selectOption('#fntInstB', '0');                                    // Thin
    await page.waitForTimeout(100);
    check('   a Bold set at Thin: bold text would look like the rest', (await make(page)).boldMsg === NOT_HEAVIER, (await make(page)).boldMsg);
    await page.selectOption('#fntInstB', '3');                                    // Regular: the same weight
    await page.waitForTimeout(100);
    check('   at the Regular\'s own weight: the same', (await make(page)).boldMsg === NOT_HEAVIER);
    await page.evaluate(() => { const s = document.querySelector('#fntBoldVar input[type=range][id*="wght"]'); s.value = '650'; s.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(100);
    check('   the Weight slider moved past the Regular: the line goes', (await make(page)).boldMsg === '', (await make(page)).boldMsg);
    await page.evaluate(() => document.getElementById('fntMakeCard').scrollIntoView());
    await page.screenshot({ path: 'bold-note.png' });
    // ---- 3. the name ----
    await page.fill('#fntName', 'ㅤㅤ');
    await page.waitForTimeout(100);
    const n0 = await make(page);
    check('3. a name of blank fillers (U+3164): named as such, Build waits', n0.hint === 'The name needs at least one letter, digit or sign that can be seen.' && !n0.build, JSON.stringify(n0));
    await page.fill('#fntName', 'ㅤInter');
    await page.waitForTimeout(100);
    const n1 = await make(page);
    check('   a letter after it: the line goes, Build is on', n1.hint === '' && n1.build, JSON.stringify(n1));
    await page.fill('#fntName', '   ');
    await page.waitForTimeout(100);
    const n2 = await make(page);
    check('   a name of only spaces: named as such too, Build waits', n2.hint === 'The name needs at least one letter, digit or sign that can be seen.' && !n2.build, JSON.stringify(n2));
    // a character of the emoji blocks this browser's Unicode does not know yet stands for an emoji newer than the phone's: it counts for the page
    // and for the bridge alike (FontPackage.visible), so the build is not refused as bad_name
    await page.fill('#fntName', '\u{1F0F6}');
    await page.waitForTimeout(100);
    const n3 = await make(page);
    check('   a name of an emoji newer than this Unicode (U+1F0F6, unknown here): no line, Build is on', n3.hint === '' && n3.build
      && await page.evaluate(() => !/[\p{L}\p{N}\p{P}\p{S}]/u.test('\u{1F0F6}')), JSON.stringify(n3));
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(400);
    const made = await page.evaluate(() => document.getElementById('fntBuildResult').textContent.trim());
    check('   the bridge takes that name: the font is made', made.indexOf('\u{1F0F6} is ready. Use it now, or choose it later under Phone font.') === 0, made);
    await page.fill('#fntName', '');
    await page.waitForTimeout(100);
    check('   an empty name: no line (nothing typed yet), Build waits', (await make(page)).hint === '' && !(await make(page)).build);
    // ---- 4. the font just made, deleted from the Phone font list, takes the buttons under its build result with it ----
    const built = await page.evaluate(() => ({ id: fnt.resultId, entry: window.__fmk.lib[0], acts: [...document.querySelectorAll('#fntBuildActs button')].map(x => x.textContent) }));
    const PKG = built.entry.pkg;
    await openList(page);
    const before = await listKeys(page);
    check('4. the font just made: the buttons for it under the build result, and a row of its own in the Phone font list', built.id && built.id === built.entry.id && built.entry.name === '\u{1F0F6}'
      && built.acts.join() === 'Use it,Install,Export,Make again' && before.includes(PKG), JSON.stringify([built.id, built.acts, PKG, before]));
    await hold(page, PKG);
    const ask = await page.evaluate(() => ({ open: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, ok: document.getElementById('mpAskOk').textContent }));
    check('   a press and hold on it (not installed): it asks to delete it from the app', ask.open && ask.title === 'Delete \u{1F0F6}?' && ask.ok === 'Delete', JSON.stringify(ask));
    await page.click('#mpAskOk');
    await page.waitForTimeout(250);
    await openList(page);
    const after = await page.evaluate(() => ({ calls: window.__fmk.calls.filter(c => /^fontMakerDelete/.test(c)), resultId: fnt.resultId, acts: document.querySelectorAll('#fntBuildActs button').length,
      result: document.getElementById('fntBuildResult').textContent, keys: [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => r.dataset.k) }));
    check('   deleted: fontMakerDelete with its id, it leaves the list, and the build result and its buttons go with it', after.calls.join() === 'fontMakerDelete:' + built.id && !after.keys.includes(PKG)
      && after.keys.length === before.length - 1 && !after.resultId && !after.acts && !after.result, JSON.stringify(after));
    await page.click('#fntPhoneSheet .sheet-close');
    await page.waitForTimeout(200);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 5. an own COLRv1 emoji file on Android 12 ----
    const { ctx, page, errors } = await open(b, { mode: 'root', sdk: 32, picks: { regular: 'static', emoji: 'colr1' } });
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    await page.selectOption('#fntEmojiSel', 'file');
    await page.click('#fntPick_emoji');
    await page.waitForTimeout(300);
    const m = await make(page);
    check('5. COLRv1 on Android 12: "Needs Android 13 to show inside a font.", Build waits', m.emojiMsg === 'Needs Android 13 to show inside a font.' && !m.build, JSON.stringify(m));
    await page.evaluate(() => { window.__fmk.picks.emoji = 'colr'; });
    await page.click('#fntPick_emoji');
    await page.waitForTimeout(300);
    check('   a COLRv0 file instead: no line, Build is on', (await make(page)).emojiMsg === '' && (await make(page)).build, JSON.stringify(await make(page)));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 6. Root Features' "?" ----
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    await page.evaluate(() => switchView('rootfeatures'));
    await page.waitForTimeout(400);
    const q = await page.evaluate(() => {
      const top = document.getElementById('rfTop'), b = top.querySelector(':scope > .help-q'), tag = document.getElementById('rfStatusTag'), title = top.querySelector('.color-card-title');
      if (!b) return null;
      const r = b.getBoundingClientRect(), t = tag.getBoundingClientRect(), h = title.getBoundingClientRect(), c = top.getBoundingClientRect();
      const apart = (a, z) => a.right <= z.left || z.right <= a.left || a.bottom <= z.top || z.bottom <= a.top;
      return { help: b.dataset.help, apart: apart(r, t) && apart(r, h), inside: r.left >= c.left && r.right <= c.right && r.top >= c.top, level: Math.abs((r.top + r.bottom) / 2 - (t.top + t.bottom) / 2) <= 4, tag: tag.textContent };
    });
    check('6. Root Features has its "?" in the top card, pointing at its topic', q && q.help === 'tab-rootfeatures' && q.inside, JSON.stringify(q));
    check('   beside the status tag and the title, never over them, level with the tag', q && q.apart && q.level, JSON.stringify(q));
    await page.screenshot({ path: 'rootfeatures-help.png', clip: { x: 0, y: 0, width: 412, height: 260 } });
    await page.click('#rfTop > .help-q');
    await page.waitForTimeout(900);
    check('   it opens the guide at Root Features', await page.evaluate(() => document.getElementById('helpGuideModal').classList.contains('show') && hg.cur === 'tab-rootfeatures'), await page.evaluate(() => String(hg.cur)));
    // ---- 7. the full guide's table of tabs: "right before X" as the tab bar has it, and the Phone font's gate as the app words it ----
    const tabs = await page.evaluate(() => TAB_DEFS.map(t => t.name));
    const md = fs.readFileSync(require('path').join(__dirname, '..', '..', 'docs', 'FULL-GUIDE.md'), 'utf8').split('\n').filter(l => /^\| \*\*[^*]+\*\* \|/.test(l));
    const before = md.map(l => { const m = /right before \*{0,2}([A-Z][\w/ -]*?)\*{0,2}[:,.]/.exec(l); return m ? [/^\| \*\*([^*]+)\*\*/.exec(l)[1], m[1]] : null; }).filter(Boolean);
    const wrong = before.filter(([tab, next]) => tabs[tabs.indexOf(tab) + 1] !== next);
    check('7. FULL-GUIDE: every "right before …" in the table of tabs matches the tab bar (Root Features right before Custom Fonts)', before.length >= 2 && !wrong.length
      && before.some(([t, n]) => t === 'Root Features' && n === 'Custom Fonts'), JSON.stringify({ before, wrong }));
    const fontsRow = md.find(l => l.startsWith('| **Custom Fonts** |')) || '';
    check('   the Custom Fonts row gives the Phone font\'s gate with the system user (UID 1000) and root (UID 0)', /Phone font\*\* \(a drop-down[^;]*; a font Samsung or Monotype did not sign needs Root, or Shizuku as the system user \(UID 1000\) or as root \(UID 0\)\)/.test(fontsRow), fontsRow.slice(0, 600));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
