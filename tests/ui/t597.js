// v7.17.0 Custom Fonts, the Make card's choices that would make a font that does not do what it says, and two small things around the tab. A variable
// font without a Weight axis keeps Bold at None (Android makes bold text from the Regular); choosing "Same variable font" for it says the Bold would
// look like the Regular. With a Weight axis, a Bold set at the Regular's weight or lighter says bold text would look like the rest; a heavier one says
// nothing. An own COLRv1 emoji file on Android 12 says it needs Android 13 and Build waits. A name made only of blank fillers (U+3164) is named as such
// and Build waits. A font deleted from My fonts takes its preview face with it (document.fonts). Root Features has its "?" (HELP_LINKS) in the top
// card, beside the status tag and not over it, and it opens the guide at that tab.
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
    await page.fill('#fntName', '');
    await page.waitForTimeout(100);
    check('   an empty name: no line (nothing typed yet), Build waits', (await make(page)).hint === '' && !(await make(page)).build);
    // ---- 4. a deleted font takes its preview face with it ----
    const had = await page.evaluate(() => { const k = fnt.libFaces.c4d5e6; window.__face = k && k.face; return !!(window.__face && [...document.fonts].includes(window.__face)); });
    check('4. Lato\'s row shows its first line in Lato (a face of its own)', had);
    await page.evaluate(() => [...document.querySelectorAll('#fntLib_c4d5e6 .mode-action-btn')].find(x => x.textContent === 'Delete').click());
    await page.waitForTimeout(250);
    await page.click('#mpAskOk');
    await page.waitForTimeout(250);
    check('   deleted: the face is gone from document.fonts', await page.evaluate(() => !document.getElementById('fntLib_c4d5e6') && ![...document.fonts].includes(window.__face) && !fnt.libFaces.c4d5e6));
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
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
