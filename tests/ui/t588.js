// v7.17.0 Custom Fonts, Build: the request the page sends (fontMakerBuild: the name, the Regular's token and point, the Bold as the same variable
// font at its own point, the emoji by catalog id, emojiScale 1, no rebuild), then the progress as the app reports it (onFontMakerProgress: a bar that
// fills stage by stage, "Reading the font files… 1 of 2", "Putting the emoji in… 1,200 of 3,913", Cancel shown, Build and the choices locked), and the
// result: "<name> is ready in My fonts." with the app's warnings, the new font first in My fonts. Cancel stops it ("Cancelling…", then "The build was
// cancelled."); an error the page knows (too_many_glyphs) is said in the page's words. The mock holds each event until the script steps it.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };

async function open(b, opts) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ mode: 'root', b64: FONTS, hold: true }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(200);
  await page.evaluate(() => window.__fmk.stepAll());                       // the privileged cards' status
  await page.click('#fntPick_regular');
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const state = (page) => page.evaluate(() => {
  const prog = document.getElementById('fntBuildProg'), fill = document.getElementById('fntBuildBarFill');
  return { on: prog.classList.contains('on'), text: document.getElementById('fntBuildProgTxt').textContent, indet: document.getElementById('fntBuildBar').classList.contains('indet'),
    width: parseFloat(fill.style.width) || 0, now: prog.getAttribute('aria-valuenow'),
    build: document.getElementById('fntBuildBtn').disabled, cancel: getComputedStyle(document.getElementById('fntCancelBtn')).display !== 'none',
    cancelOff: document.getElementById('fntCancelBtn').disabled, pick: document.getElementById('fntPick_regular').disabled, boldSel: document.getElementById('fntBoldSel').disabled,
    slider: document.getElementById('fntAxR_wght') ? document.getElementById('fntAxR_wght').disabled : null, name: document.getElementById('fntName').disabled,
    result: document.getElementById('fntBuildResult').textContent.replace(/\s+/g, ' ').trim(), err: !!document.querySelector('#fntBuildResult .is-err') };
});

(async () => {
  const b = await chromium.launch();
  // ---- 1. a whole build: variable Regular + Bold at another weight + Twemoji ----
  {
    const { ctx, page, errors } = await open(b, {});
    await page.selectOption('#fntEmojiSel', 'cat:twemoji');
    await page.fill('#fntName', 'Inter Plus');
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    const req = await page.evaluate(() => window.__fmk.requests[0]);
    check('1. the request: name, Regular token and point, Bold same token at wght 700, Twemoji, scale 1, no rebuild',
      req && req.name === 'Inter Plus' && /^tok-regular-/.test(req.regular.token) && req.regular.coords.wght === 400 && req.regular.coords.opsz === 14
      && req.bold && req.bold.token === req.regular.token && req.bold.coords.wght === 700 && req.emoji && req.emoji.catalogId === 'twemoji' && req.emojiScale === 1 && req.rebuildId === null, JSON.stringify(req));
    const s0 = await state(page);
    check('2. building: the progress shows, Cancel appears, Build and the choices are locked', s0.on && s0.indet && s0.cancel && s0.build && s0.pick && s0.boldSel && s0.slider && s0.name, JSON.stringify(s0));
    const seen = [];
    let last = 0, grows = true;
    while (await page.evaluate(() => window.__fmk.pending()) > 1) {
      await page.evaluate(() => window.__fmk.step());
      await page.waitForTimeout(30);
      const s = await state(page);
      seen.push(s.text);
      if (s.width < last) grows = false;
      last = s.width;
    }
    check('3. every stage is named, with its counts where the app gives them',
      seen.join(' | ') === 'Reading the font files… 1 of 2 | Reading the font files… 2 of 2 | Fixing the variable font at the chosen weight… 1 of 2 | Fixing the variable font at the chosen weight… 2 of 2 | Putting the emoji in… 1,200 of 3,913 | Putting the emoji in… 3,913 of 3,913 | Making the font package… | Signing the package… | Saving…', seen.join(' | '));
    const s1 = await state(page);
    check('   the bar fills stage by stage and never goes back', grows && !s1.indet && s1.width > 80 && s1.width <= 100 && s1.now === String(Math.round(s1.width)), JSON.stringify([grows, s1.width, s1.now]));
    await page.screenshot({ path: 'building.png' });
    await page.evaluate(() => window.__fmk.step());
    await page.waitForTimeout(400);
    const s2 = await state(page);
    check('4. done: the progress goes, Build is back, Cancel hidden', !s2.on && !s2.build && !s2.cancel && !s2.pick, JSON.stringify(s2));
    check('   the result names the font and lists the app\'s warning', /^Inter Plus is ready in My fonts\./.test(s2.result) && /12 that the text font already has keep their letters/.test(s2.result) && !s2.err, s2.result);
    const first = await page.evaluate(() => { const r = document.querySelector('#fntLibList .fnt-lib'); return r ? r.textContent.replace(/\s+/g, ' ') : ''; });
    check('   the new font is first in My fonts, not installed, with its emoji', /Inter Plus/.test(first) && /InterPlus_a1b2c3/.test(first) && /Not installed/.test(first) && /Emoji: Twemoji/.test(first) && /Regular and Bold/.test(first), first);
    await page.screenshot({ path: 'built.png', fullPage: false });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- 2. Cancel ----
  {
    const { ctx, page, errors } = await open(b, {});
    await page.click('#fntBuildBtn');
    await page.evaluate(() => window.__fmk.step(2));
    await page.click('#fntCancelBtn');
    await page.waitForTimeout(100);
    const c = await state(page);
    const calls = await page.evaluate(() => [window.__fmk.calls.filter(x => /^fontMakerCancel:/.test(x)), window.__fmk.buildJob]);
    check('5. Cancel: fontMakerCancel with the build\'s job, "Cancelling…", Cancel itself disabled', calls[0].join() === 'fontMakerCancel:' + calls[1] && c.text === 'Cancelling…' && c.indet && c.cancelOff, JSON.stringify([calls, c]));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(200);
    const d = await state(page);
    check('   the app\'s answer: "The build was cancelled.", nothing added to My fonts', !d.on && d.result === 'The build was cancelled.' && !d.err && await page.evaluate(() => document.querySelectorAll('#fntLibList .fnt-lib').length) === 2, JSON.stringify(d));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- 3. an error the page knows; a static font (no point sent) ----
  {
    const { ctx, page, errors } = await open(b, { buildError: { code: 'too_many_glyphs', error: 'Too many glyphs (70211).' }, picks: { regular: 'static' } });
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    const req = await page.evaluate(() => window.__fmk.requests[0]);
    check('6. a static Regular: no point, no Bold, no emoji in the request', req && req.regular.coords === null && req.bold === null && req.emoji === null && req.name === 'Lato', JSON.stringify(req));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(200);
    const e = await state(page);
    check('   too_many_glyphs is said in the page\'s words, in the error colour', e.err && /more glyphs than one font can hold \(65,535\)/.test(e.result), e.result);
    // a bridge without the build: the toast, nothing starts
    await page.evaluate(() => { delete window.AndroidBridge.fontMakerBuild; });
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(150);
    const toast = await page.evaluate(() => document.getElementById('toastMsg').textContent);
    check('7. an app build without fontMakerBuild: "Needs the app build", no progress', toast === 'Needs the app build' && !(await state(page)).on, toast);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
