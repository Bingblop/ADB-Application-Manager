// v7.17.0 Custom Fonts, Make a font: picking the files. A variable Regular (an Inter-like font: opsz + wght, nine named styles) shows its file, family,
// glyph count and outline kind, a Style drop-down and one slider per axis set to the app's Regular guess, and a live preview in the font itself (a
// CSS FontFace of the picked file, drawn with font-variation-settings: the text gets wider as the weight goes up, measured on a fixture font whose boxes
// widen with wght). Bold switches to "same variable font, another weight" at the Bold guess; a slider off every named style reads Custom; Bold = None
// draws the bold line as Android would (bold made from the Regular); a Bold file loads its own face. The name is filled from the family until typed.
// A file that cannot be used (WOFF) says why and keeps the file chosen before; a closed chooser says nothing. A static CFF font has no panel, keeps
// its warning and drops the "same variable font" choice. An own emoji file made of pictures (CBDT) is refused with the reason; a COLR one is taken.
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
  await page.addInitScript(fmk.initScript, { mode: 'root', b64: FONTS });
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(300);
  const txt = (id) => page.evaluate(id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; }, id);
  // the width of a line of the preview (one line: the big "Aa Bb 123")
  const pv = () => page.evaluate(() => {
    const box = document.getElementById('fntPreview'), big = document.getElementById('fntPvBig'), bold = document.getElementById('fntPvBold');
    const r = document.createRange(); r.selectNodeContents(big);
    const cs = getComputedStyle(big), bs = getComputedStyle(bold);
    return { shown: getComputedStyle(box).display !== 'none', family: getComputedStyle(box).fontFamily, fvs: cs.fontVariationSettings, width: Math.round(r.getBoundingClientRect().width),
      boldFvs: bs.fontVariationSettings, boldWeight: bs.fontWeight, boldFamily: bs.fontFamily, loaded: [...document.fonts].filter(f => /^FntPv/.test(f.family) && f.status === 'loaded').map(f => f.family) };
  });

  // ---- 1. a variable Regular ----
  await page.click('#fntPick_regular');
  await page.waitForTimeout(400);
  const row = await txt('fntRegRow');
  check('1. the Regular row names the file, family, glyphs and outline kind', /InterVariable\.ttf/.test(row) && /Inter Variable Regular · 2,548 glyphs · TrueType outlines · Variable font/.test(row) && /Change$/.test(row), row);
  const v = await page.evaluate(() => ({ inst: document.getElementById('fntInstR').value, opts: [...document.getElementById('fntInstR').options].map(o => o.textContent),
    sliders: [...document.querySelectorAll('#fntRegVar input[type=range]')].map(i => i.id + '=' + i.value + ' ' + i.min + '..' + i.max), labels: [...document.querySelectorAll('#fntRegVar .fnt-axis-head label')].map(l => l.textContent),
    name: document.getElementById('fntName').value, build: document.getElementById('fntBuildBtn').disabled }));
  check('   a Style drop-down: Custom and the nine named styles, Regular chosen (the app\'s guess)', v.opts.length === 10 && v.opts[0] === 'Custom' && v.opts[4] === 'Regular' && v.inst === '3', JSON.stringify([v.opts, v.inst]));
  check('   one slider per axis at the guess (opsz 14, wght 400), labelled', v.sliders.join(',') === 'fntAxR_opsz=14 14..32,fntAxR_wght=400 100..900' && v.labels.join(',') === 'Optical size,Weight', JSON.stringify(v));
  check('   the name is filled from the family; Build is enabled', v.name === 'Inter Variable' && !v.build, JSON.stringify([v.name, v.build]));
  await page.waitForTimeout(300);
  const p1 = await pv();
  check('2. the preview shows, in the picked font (a FontFace from fontMakerPreview)', p1.shown && /FntPvR/.test(p1.family) && p1.loaded.some(f => /FntPvR/.test(f)), JSON.stringify(p1));
  check('   drawn at the chosen point (font-variation-settings)', /"opsz" 14/.test(p1.fvs) && /"wght" 400/.test(p1.fvs), p1.fvs);
  const bold = await page.evaluate(() => ({ sel: document.getElementById('fntBoldSel').value, opts: [...document.getElementById('fntBoldSel').options].map(o => o.value),
    none: getComputedStyle(document.getElementById('fntBoldNone')).display, inst: (document.getElementById('fntInstB') || {}).value }));
  check('3. Bold becomes the same variable font at the Bold guess (wght 700)', bold.sel === 'instance' && bold.opts.join(',') === 'none,instance,file' && bold.inst === '6' && bold.none === 'none', JSON.stringify(bold));
  check('   the bold line of the preview uses wght 700, without faux bold', /"wght" 700/.test(p1.boldFvs) && p1.boldWeight === '400', JSON.stringify([p1.boldFvs, p1.boldWeight]));

  // ---- 2. the named styles and the sliders move the preview ----
  await page.selectOption('#fntInstR', '8');                               // Black
  await page.waitForTimeout(200);
  const pBlack = await pv();
  await page.selectOption('#fntInstR', '0');                               // Thin
  await page.waitForTimeout(200);
  const pThin = await pv();
  check('4. Black draws wider than Regular, Thin narrower (the font really varies)', pBlack.width > p1.width && pThin.width < p1.width && /"wght" 900/.test(pBlack.fvs) && /"wght" 100/.test(pThin.fvs), JSON.stringify([pThin.width, p1.width, pBlack.width, pBlack.fvs]));
  const wl = await page.evaluate(() => [document.getElementById('fntAxR_wght').value, document.getElementById('fntAxR_wghtv').textContent]);
  check('   the slider and its value follow the named style', wl[0] === '100' && wl[1] === '100', wl.join(','));
  await page.evaluate(() => { const el = document.getElementById('fntAxR_wght'); el.value = '650'; el.dispatchEvent(new Event('input', { bubbles: true })); });
  await page.waitForTimeout(200);
  const pS = await pv();
  const sv = await page.evaluate(() => [document.getElementById('fntInstR').value, document.getElementById('fntAxR_wghtv').textContent]);
  check('5. a slider off every named style: Style reads Custom, the value 650, the preview follows', sv[0] === '' && sv[1] === '650' && /"wght" 650/.test(pS.fvs) && pS.width > p1.width && pS.width < pBlack.width, JSON.stringify([sv, pS.fvs, pS.width]));
  await page.evaluate(() => { const el = document.getElementById('fntAxR_wght'); el.value = '400'; el.dispatchEvent(new Event('input', { bubbles: true })); });
  await page.waitForTimeout(150);
  check('   back at 400 the Style reads Regular again', await page.evaluate(() => document.getElementById('fntInstR').value) === '3');
  await page.screenshot({ path: 'variable.png', fullPage: false });

  // ---- 3. Bold = None, Bold from a file ----
  await page.selectOption('#fntBoldSel', 'none');
  await page.waitForTimeout(150);
  const pN = await pv();
  const nn = await page.evaluate(() => [getComputedStyle(document.getElementById('fntBoldNone')).display, document.getElementById('fntBoldVar').innerHTML, document.getElementById('fntBoldNone').textContent]);
  check('6. Bold None: the line that Android makes bold from the Regular, no Bold panel', nn[0] !== 'none' && nn[1] === '' && /Android makes bold text from the Regular/.test(nn[2]), JSON.stringify(nn));
  check('   the bold line is the Regular made bold (weight 700, the Regular\'s axes)', pN.boldWeight === '700' && pN.boldFvs === pN.fvs, JSON.stringify([pN.boldWeight, pN.boldFvs, pN.fvs]));
  await page.selectOption('#fntBoldSel', 'file');
  await page.waitForTimeout(150);
  check('7. Bold from a file: a row to choose it, Build waits for it', /^Bold font file/.test(await txt('fntBoldRow')) && await page.evaluate(() => document.getElementById('fntBuildBtn').disabled), await txt('fntBoldRow'));
  await page.click('#fntPick_bold');
  await page.waitForTimeout(500);
  const pB = await pv();
  check('   the Bold file is shown and the bold line uses its own face', /Lato-Bold\.ttf/.test(await txt('fntBoldRow')) && /FntPvB/.test(pB.boldFamily) && pB.boldWeight === '400', JSON.stringify([await txt('fntBoldRow'), pB.boldFamily]));
  check('   Build is enabled again', !(await page.evaluate(() => document.getElementById('fntBuildBtn').disabled)));

  // ---- 4. the name, a file that cannot be used, a closed chooser ----
  await page.fill('#fntName', 'My Inter');
  await page.evaluate(() => { window.__fmk.picks.regular = 'woff'; });
  await page.click('#fntPick_regular');
  await page.waitForTimeout(300);
  const er = await page.evaluate(() => [document.getElementById('fntRegMsg').textContent, !!document.querySelector('#fntRegMsg .is-err'), document.getElementById('fntRegRow').textContent]);
  check('8. a WOFF file: the reason, in the error colour, and the variable file stays chosen', /web font \(WOFF\)/.test(er[0]) && er[1] && /InterVariable\.ttf/.test(er[2]), JSON.stringify(er));
  await page.evaluate(() => { window.__fmk.picks.regular = 'cancelled'; });
  await page.click('#fntPick_regular');
  await page.waitForTimeout(300);
  check('   a closed chooser adds nothing (the last message stays as it was)', /web font/.test(await txt('fntRegMsg')));

  // ---- 5. a static CFF Regular ----
  await page.evaluate(() => { window.__fmk.picks.regular = 'cff'; });
  await page.selectOption('#fntBoldSel', 'none');
  await page.click('#fntPick_regular');
  await page.waitForTimeout(400);
  const cff = await page.evaluate(() => ({ row: document.getElementById('fntRegRow').textContent, panel: document.getElementById('fntRegVar').innerHTML, msg: document.getElementById('fntRegMsg').textContent,
    opts: [...document.getElementById('fntBoldSel').options].map(o => o.value), name: document.getElementById('fntName').value }));
  check('9. a static CFF font: CFF outlines, no axes, its warning listed, the error gone', /SourceSans3-Regular\.otf/.test(cff.row) && /CFF outlines/.test(cff.row) && !/Variable/.test(cff.row) && cff.panel === '' && /PostScript \(CFF\)/.test(cff.msg) && !/WOFF/.test(cff.msg), JSON.stringify(cff));
  check('   no "same variable font" Bold; the name typed by hand stays', cff.opts.join(',') === 'none,file' && cff.name === 'My Inter', JSON.stringify([cff.opts, cff.name]));

  // ---- 6. an own emoji file ----
  await page.selectOption('#fntEmojiSel', 'file');
  await page.waitForTimeout(150);
  check('10. Emoji from an own file: a row and the note that picture emoji cannot go in', /^Emoji font file/.test(await txt('fntEmojiRow')) && /Picture emoji \(CBDT, sbix\) cannot go inside a font/.test(await txt('fntEmojiNote')), await txt('fntEmojiNote'));
  await page.evaluate(() => { window.__fmk.picks.emoji = 'cbdt'; });
  await page.click('#fntPick_emoji');
  await page.waitForTimeout(300);
  check('    a CBDT emoji font is refused with the reason', /made of pictures \(CBDT or sbix\)/.test(await txt('fntEmojiMsg')) && /Choose$/.test(await txt('fntEmojiRow')), await txt('fntEmojiMsg'));
  await page.evaluate(() => { window.__fmk.picks.emoji = 'colr'; });
  await page.click('#fntPick_emoji');
  await page.waitForTimeout(300);
  const em = await txt('fntEmojiRow');
  check('    a COLR one is taken (file, glyphs, COLRv0)', /TwemojiMozilla\.ttf/.test(em) && /3,913 glyphs · COLRv0/.test(em) && (await txt('fntEmojiMsg')) === '', em);
  await page.screenshot({ path: 'static-emoji.png', fullPage: false });
  check('11. no page errors', !errors.length, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
