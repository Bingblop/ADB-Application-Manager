// v7.17.0 Custom Fonts, Make a font: what the engine and the bridge refuse, in the page's words (FNT_ERR, and FNT_SAID for a code Java words in more
// than one way), with Java's details kept and the face the engine names in front ("Regular font: ", "Bold font: ", "Emoji font: "). Build refusals:
// text_has_color (Regular), symbol_font (Bold), emoji_outlines, no_palette, palette_damaged and emoji_symbol (Emoji); a GSUB table too large to write
// (the emoji's doing) told apart from a font over 96 MB; too_many_glyphs with both counts; not_usable for an own COLRv1 file; a failure the page has
// no sentence for keeps Java's words after "Something went wrong:"; a code the page does not know shows Java's sentence. At the pick: an emoji font
// with PostScript outlines is refused (emoji_outlines); an emoji font chosen as the Regular carries the no-Latin warning and, being in color, holds
// Build back while an emoji is chosen; in the emoji slot it has no such warning. The engine's warnings after a build are the page's sentences ("color",
// the counts in the page's number format), each line in its own direction (dir="auto") and indented at its start; one the page does not know stays as Java wrote it.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };

(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, { mode: 'root', b64: FONTS, picks: { regular: 'static' } });
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(300);
  const txt = (id) => page.evaluate(id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; }, id);
  const pickFile = async (slot, what) => { await page.evaluate(([s, w]) => { window.__fmk.picks[s] = w; }, [slot, what]); await page.click('#fntPick_' + slot); await page.waitForTimeout(250); };
  // one build that ends with the given answer: the result line, and whether it is in the error colour
  const buildEnds = async (answer) => {
    await page.evaluate(a => { window.__fmk.buildError = a; }, answer);
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(350);
    return page.evaluate(() => ({ text: document.getElementById('fntBuildResult').textContent.trim(), err: !!document.querySelector('#fntBuildResult .is-err') }));
  };
  await pickFile('regular', 'static');
  await page.selectOption('#fntEmojiSel', 'cat:twemoji');

  // ---- 1. the build's refusals: the page's sentence, the face in front ----
  const cases = [
    ['text_has_color', 'Regular font: This font already has colour glyphs (COLR), so emoji cannot be added to it. Set Emoji to None.',
      'Regular font: This font already has color glyphs, so emoji cannot be added to it. Set Emoji to None.'],
    ['symbol_font', 'Bold font: This is a symbol font, so emoji cannot be added to it. Set Emoji to None.',
      'Bold font: This is a symbol font, so emoji cannot be added to it. Set Emoji to None.'],
    ['emoji_outlines', "Emoji font: This emoji font's outlines are PostScript ones; only an emoji font with TrueType outlines (glyf) can be added. Choose another emoji font.",
      'Emoji font: Only an emoji font with TrueType outlines can go inside a font; this one has PostScript (CFF) outlines. Choose another emoji font.'],
    ['no_palette', 'Emoji font: This emoji font has colour layers (COLR) but no colour palette (CPAL). Choose another emoji font.',
      'Emoji font: This font has color layers (COLR) but no color palette (CPAL). Choose another emoji font.'],
    ['palette_damaged', "Emoji font: This emoji font's colour palette (CPAL) is damaged. Choose another emoji font.",
      "Emoji font: This font's color palette (CPAL) is damaged. Choose another emoji font."],
    ['emoji_symbol', "Emoji font: This emoji font's character map is a symbol one, so its emoji cannot be found. Choose another emoji font.",
      "Emoji font: This font's characters are not at their Unicode places, so its emoji cannot be found. Choose another emoji font."],
    ['bitmap_emoji', 'Emoji font: This emoji font draws its emoji as bitmaps (CBDT): they cannot be put inside a font. Choose a COLR emoji font.',
      "Emoji font: This font's emoji are made of pictures (CBDT or sbix), which cannot go inside a font. Choose a COLR emoji font."],
  ];
  for (const [code, java, want] of cases) {
    const r = await buildEnds({ code, error: java });
    check('1. ' + code + ': "' + want + '"', r.err && r.text === want, r.text);
  }
  // ---- 2. too_big: the emoji's layout table, or the whole font ----
  const gsub = await buildEnds({ code: 'too_big', error: 'Regular font: The GSUB table would be too big to write.' });
  check('2. a GSUB table too large to write is the emoji\'s doing: it says so and what to change', gsub.text === "Regular font: With the emoji inside, the font's GSUB table would be too large to write. Choose another emoji font, or set Emoji to None.", gsub.text);
  const big = await buildEnds({ code: 'too_big', error: 'Bold font: The finished font would be over 96 MB, more than a font package can hold.' });
  check('   a font over 96 MB: the font package\'s limit', big.text === 'Bold font: The finished font would be too large for a font package (over 96 MB).', big.text);
  const many = await buildEnds({ code: 'too_many_glyphs', error: 'Regular font: The text font has 62,410 glyphs and the emoji font 3,913: together they pass the 65,535 glyphs a font can hold.' });
  check('   too_many_glyphs keeps both counts', many.text === 'Regular font: The font has 62,410 glyphs and the emoji font 3,913: together they pass the 65,535 glyphs one font can hold.', many.text);
  // ---- 3. a failure without a sentence of its own, a code the page does not know ----
  const made = await buildEnds({ code: 'failed', error: 'The font could not be made: Broken pipe' });
  check('3. failed: the page\'s lead, Java\'s detail kept', made.text === 'The font could not be made: Broken pipe', made.text);
  const odd = await buildEnds({ code: 'failed', error: 'OutOfMemoryError' });
  check('   failed with a bare detail: "Something went wrong: …"', odd.text === 'Something went wrong: OutOfMemoryError', odd.text);
  const unknown = await buildEnds({ code: 'brand_new', error: 'A sentence the page has no words for.' });
  check('   a code the page does not know: Java\'s sentence as it is', unknown.err && unknown.text === 'A sentence the page has no words for.', unknown.text);
  // the bridge's answer before a build starts (fontMakerBuild): an own COLRv1 file on an older Android
  await page.evaluate(() => { window.__fmk.realBuild = window.AndroidBridge.fontMakerBuild; window.AndroidBridge.fontMakerBuild = () => JSON.stringify({ ok: false, code: 'not_usable', error: 'Emoji font: this COLRv1 emoji font needs Android 13 or newer to show inside a font.' }); });
  await page.click('#fntBuildBtn');
  await page.waitForTimeout(150);
  check('   not_usable at the start: the page\'s sentence after "Emoji font: "', await txt('fntBuildResult') === 'Emoji font: This COLRv1 emoji font needs Android 13 or newer to show inside a font.', await txt('fntBuildResult'));
  await page.evaluate(() => { window.AndroidBridge.fontMakerBuild = window.__fmk.realBuild; window.__fmk.buildError = null; });

  // ---- 4. the picks ----
  await page.selectOption('#fntEmojiSel', 'file');
  await pickFile('emoji', 'colrCff');
  check('4. an emoji font with PostScript outlines is refused at the pick, in the page\'s words', await txt('fntEmojiMsg') === 'Only an emoji font with TrueType outlines can go inside a font; this one has PostScript (CFF) outlines. Choose another emoji font.'
    && /Choose$/.test(await txt('fntEmojiRow')), await txt('fntEmojiMsg'));
  await pickFile('emoji', 'colr');
  check('   a TrueType COLR emoji font is taken, with no word about Latin letters', /TwemojiMozilla\.ttf/.test(await txt('fntEmojiRow')) && await txt('fntEmojiMsg') === '', await txt('fntEmojiMsg'));
  await pickFile('regular', 'colr');
  const asReg = await page.evaluate(() => ({ msg: document.getElementById('fntRegMsg').textContent, emo: document.getElementById('fntEmojiMsg').textContent, err: !!document.querySelector('#fntEmojiMsg .is-err'), build: document.getElementById('fntBuildBtn').disabled }));
  check('5. an emoji font chosen as the Regular: the no-Latin warning, in the page\'s words', asReg.msg === "This font has no basic Latin letters and digits (A-Z, a-z, 0-9): English text would show in the phone's own font.", asReg.msg);
  check('   it has color glyphs: with an emoji chosen, the refusal shows before the build and Build waits', asReg.err && asReg.emo === 'Regular font: This font already has color glyphs, so emoji cannot be added to it. Set Emoji to None.' && asReg.build, JSON.stringify(asReg));
  await page.selectOption('#fntEmojiSel', 'none');
  check('   Emoji None: the line goes, Build is on', await txt('fntEmojiMsg') === '' && !(await page.evaluate(() => document.getElementById('fntBuildBtn').disabled)));

  // ---- 6. the build's warnings ----
  await pickFile('regular', 'static');
  await page.evaluate(() => { window.__fmk.buildWarnings = [
    "This font's colour glyphs are bitmaps (CBDT): bitmap emoji cannot be put inside a font, so only its outlines would be used.",
    'The font has more characters than the older part of its character map (format 4) can hold: it lists the first 31000 of the 40112 characters below U+FFFF. Android reads the newer part, which has them all; a few older apps may miss the rest.',
    'The Bold font is no heavier than the Regular one, so bold text will look like the rest of the text.',
    'A warning the page has no words for.']; });
  await page.click('#fntBuildBtn');
  await page.waitForTimeout(400);
  const w = await page.evaluate(() => [...document.querySelectorAll('#fntBuildResult .fnt-warns li')].map(li => [li.textContent, li.getAttribute('dir')]));
  check('6. the warnings in the page\'s words: color, not colour; the bitmap kind kept', w[0] && w[0][0] === "This font's color glyphs are bitmaps (CBDT): bitmap emoji cannot be put inside a font, so only its outlines would be used.", JSON.stringify(w[0]));
  check('   the character map line keeps its counts, in the page\'s number format', w[1] && /lists the first 31,000 of the 40,112 characters below U\+FFFF/.test(w[1][0]), JSON.stringify(w[1]));
  check('   a known sentence is the page\'s; one the page does not know stays as it is', w[2] && w[2][0] === 'The Bold font is no heavier than the Regular one, so bold text will look like the rest of the text.' && w[3] && w[3][0] === 'A warning the page has no words for.', JSON.stringify(w.slice(2)));
  check('   each line takes its own direction (dir="auto")', w.length === 4 && w.every(x => x[1] === 'auto'), JSON.stringify(w.map(x => x[1])));
  const pad = await page.evaluate(() => [...document.querySelectorAll('#fntBuildResult .fnt-warns li')].map(li => { const c = getComputedStyle(li); return [parseFloat(c.marginLeft), parseFloat(c.marginRight)]; }));
  check('   each line is indented at its start, here the left (margin-inline-start)', pad.length === 4 && pad.every(m => m[0] > 0 && m[1] === 0), JSON.stringify(pad));
  await page.screenshot({ path: 'warnings.png' });
  check('7. no page errors', !errors.length, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
