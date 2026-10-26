// v7.18.0 Google Fonts, a family's page: a card opens it at the top, titled with the family. The head is the name in the family's own font (the
// card preview of its name) with a line of category, designers, styles, the variable axes and Colour. The type tester starts with the sample, its size
// 14-96 (40), one slider per axis (named from the list, starting at the axis' default) that drives font-variation-settings on the tester line (the
// 'axes' preview); the Styles list has a row per style ("Thin 100", "Thin 100 Italic", ...; "Weight N" for the others) in that style (the 'styles'
// preview). New tester text waits 400 ms and keeps the faces shown until the new ones come. About: the details are asked once (gfontsInfo), with a
// line while they come; the description shows three lines and More, then the licence's name and its text under <details>, the designers, the dates
// and "Open on fonts.google.com". A failure says why with Retry. A colour family and a symbol family say Make a font needs a plain text font. Back
// and a tap outside go back to the list where it was, then close; what the page asked for is dropped once it is left.
// Mock bridge: lib/fontmaker_mock.js (opts.gf.hold: the details and previews answer when the script says); the list: fixtures/gfonts-catalog.json.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));
const LATIN = 'The quick brown fox jumps over the lazy dog.';

async function open(b, opts) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  await page.click('#gfOpenBtn');
  await page.waitForTimeout(300);
  return { ctx, page, errors };
}
const drain = async (page) => { for (let i = 0; i < 40; i++) { const n = await page.evaluate(() => __fmk.gfPending().length); if (!n) break; await page.evaluate(() => __fmk.gfAnswerAll()); await page.waitForTimeout(150); } await page.waitForTimeout(200); };
const showFam = async (page, f, q) => { await page.evaluate((q) => { const s = document.getElementById('gfSearch'); s.value = q; s.dispatchEvent(new Event('input', { bubbles: true })); }, q || f); await page.waitForTimeout(250); await page.click('#gfList .gf-card[data-f="' + f + '"]'); await page.waitForTimeout(200); };
// a block's words as they read: its pieces one space apart (what is hidden left out)
const words = (el) => { const out = []; const walk = (n) => { if (n.nodeType === 3) { out.push(n.textContent); return; } if (n.nodeType !== 1 || getComputedStyle(n).display === 'none') return; out.push(' '); n.childNodes.forEach(walk); out.push(' '); }; walk(el); return out.join('').replace(/\s+/g, ' ').trim(); };
const detail = (page) => page.evaluate((wsrc) => {
  const words = eval(wsrc);
  const t = id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; };
  const line = document.getElementById('gfDLine'), head = document.getElementById('gfDHead');
  return {
    title: t('gfTitle'), head: { text: head.textContent, ff: head.style.fontFamily, wait: head.classList.contains('gf-wait'), noTr: head.getAttribute('translate'), size: parseFloat(getComputedStyle(head).fontSize) },
    meta: t('gfDMeta'), dtext: document.getElementById('gfDText').value, dsize: [document.getElementById('gfDSize').min, document.getElementById('gfDSize').max, document.getElementById('gfDSize').value, t('gfDSizeV')].join(','),
    axes: [...document.querySelectorAll('#gfDAxes .fnt-axis')].map(a => { const i = a.querySelector('input'); return [a.querySelector('label').textContent, i.id, i.min, i.max, i.value, a.querySelector('b').textContent].join(':'); }),
    line: { text: line.textContent, ff: line.style.fontFamily, fvs: line.style.fontVariationSettings, size: line.style.fontSize, wait: line.classList.contains('gf-wait') },
    styles: [...document.querySelectorAll('#gfStyles .gf-style')].map(r => { const pv = r.querySelector('.gf-style-pv'); return { name: r.querySelector('.gf-style-name').textContent, w: pv.style.fontWeight, it: pv.style.fontStyle, ff: pv.style.fontFamily, text: pv.textContent, wait: pv.classList.contains('gf-wait') }; }),
    about: words(document.getElementById('gfAbout')), get: words(document.getElementById('gfGet')), scroll: document.getElementById('gfScroll').scrollTop,
    calls: __fmk.calls.filter(c => /^gfontsInfo/.test(c)), asked: __fmk.gfPreviews.map(p => p.family + '|' + p.mode + '|' + p.text), keys: [...gf.faces.keys()],
  };
}, '(' + words.toString() + ')');

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], hold: true } });
    await drain(page);
    // scrolled down the list, then a family: its page opens at the top
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 1200; });
    await page.waitForTimeout(300);
    await drain(page);
    const listTop = await page.evaluate(() => document.getElementById('gfScroll').scrollTop);
    await page.evaluate(() => { const c = document.querySelector('#gfList .gf-card[data-f="Lato"]'); c.click(); });
    await page.waitForTimeout(250);
    let d = await detail(page);
    check('1. Lato: its page at the top, titled Lato', d.title === 'Lato' && d.scroll === 0, JSON.stringify([d.title, d.scroll]));
    check('   the head: the name, waiting for its own font (translate="no")', d.head.text === 'Lato' && d.head.wait && d.head.noTr === 'no', JSON.stringify(d.head));
    check('   meta: "Sans serif · Łukasz Dziedzic · 10 styles"', d.meta === 'Sans serif · Łukasz Dziedzic · 10 styles', d.meta);
    check('   tester: the sample, size 14-96 at 40, no axis for a static family', d.dtext === LATIN && d.dsize === '14,96,40,40' && !d.axes.length && d.line.text === LATIN && d.line.size === '40px', JSON.stringify([d.dtext, d.dsize, d.axes, d.line]));
    check('   styles: ten rows, named', d.styles.map(s => s.name).join(', ') === 'Thin 100, Thin 100 Italic, Light 300, Light 300 Italic, Regular 400, Regular 400 Italic, Bold 700, Bold 700 Italic, Black 900, Black 900 Italic', d.styles.map(s => s.name).join(', '));
    check('   each row in its weight and style, with the tester text', d.styles.map(s => s.w + (s.it === 'italic' ? 'i' : '')).join(',') === '100,100i,300,300i,400,400i,700,700i,900,900i' && d.styles.every(s => s.text === LATIN && s.wait), JSON.stringify(d.styles.map(s => [s.w, s.it])));
    check('   the details asked once (gfontsInfo), "Getting the details from Google Fonts…" meanwhile', d.calls.join(',') === 'gfontsInfo:Lato' && /^About Getting the details from Google Fonts…/.test(d.about), JSON.stringify([d.calls, d.about]));
    check('   asked: the head (card, the name), the tester (axes), the styles', ['Lato|card|Lato', 'Lato|axes|' + LATIN, 'Lato|styles|' + LATIN].every(k => d.asked.includes(k)), d.asked.filter(a => /^Lato/.test(a)).join(' / '));
    await drain(page);
    d = await detail(page);
    const styleFaces = await page.evaluate(() => { const r = gf.faces.get('Lato|styles|The quick brown fox jumps over the lazy dog.'); return r ? r.list.length : 0; });
    check('2. drawn: the head and the tester in their faces, every style row too', !d.head.wait && /gfpv\d+/.test(d.head.ff) && !d.line.wait && /gfpv\d+/.test(d.line.ff) && d.styles.every(s => !s.wait && /gfpv\d+/.test(s.ff)), JSON.stringify([d.head, d.line, d.styles[0]]));
    check('   the head is big', d.head.size >= 28, d.head.size);
    check('   the styles preview: one face per style (10)', styleFaces === 10, styleFaces);
    // the details come
    await page.evaluate(() => __fmk.gfStepAll());
    await page.waitForTimeout(200);
    d = await detail(page);
    const ab = await page.evaluate(() => {
      const desc = document.getElementById('gfDesc'), more = document.getElementById('gfDescMore'), lic = document.getElementById('gfLic');
      return { clamp: desc && desc.classList.contains('gf-clamp'), h: desc && desc.clientHeight, sh: desc && desc.scrollHeight, more: more && getComputedStyle(more).display !== 'none' ? more.textContent : '',
        lic: lic ? { tag: lic.tagName, open: lic.open, sum: lic.querySelector('summary').textContent, pre: lic.querySelector('pre').textContent, dir: lic.querySelector('pre').getAttribute('dir') } : null,
        rows: [...document.querySelectorAll('#gfAbout .fnt-now')].map(r => [...r.children].map(c => c.textContent.trim()).join(' ')), site: (document.getElementById('gfOpenSite') || {}).textContent, noTr: desc && desc.getAttribute('translate') };
    });
    check('3. About: the description in three lines with More', ab.clamp && ab.sh > ab.h + 1 && ab.more === 'More' && ab.noTr === 'no', JSON.stringify(ab));
    check('   the licence: "SIL Open Font License 1.1", its text under a closed <details>', ab.rows[0] === 'Licence SIL Open Font License 1.1' && ab.lic && ab.lic.tag === 'DETAILS' && !ab.lic.open && ab.lic.sum === 'Licence text' && /Reserved Font Name "Lato"/.test(ab.lic.pre) && ab.lic.dir === 'ltr', JSON.stringify([ab.rows[0], ab.lic]));
    check('   designer, added, updated', ab.rows.slice(1).join(' | ') === 'Designer Łukasz Dziedzic | Added Dec 15, 2010 | Updated Sep 16, 2025', ab.rows.join(' | '));
    check('   "Open on fonts.google.com"', ab.site === 'Open on fonts.google.com', ab.site);
    await page.click('#gfDescMore');
    const opened = await page.evaluate(() => ({ clamp: document.getElementById('gfDesc').classList.contains('gf-clamp'), more: getComputedStyle(document.getElementById('gfDescMore')).display }));
    check('   More: the whole description, More gone', !opened.clamp && opened.more === 'none', JSON.stringify(opened));
    await page.click('#gfOpenSite');
    check('   the site opens through the app (openUrl), the family with + for spaces', (await page.evaluate(() => __fmk.calls.filter(c => /^openUrl:/.test(c)).pop())) === 'openUrl:https://fonts.google.com/specimen/Lato');
    await page.evaluate(() => { document.getElementById('gfLic').open = true; });
    check('   the licence text opens', await page.evaluate(() => document.querySelector('#gfLic pre').getBoundingClientRect().height > 20));
    // the tester: new text after 400 ms, the faces kept meanwhile; the size
    const n0 = d.asked.length, ff0 = d.line.ff;
    await page.fill('#gfDText', 'Sphinx of black quartz');
    const right = (await detail(page)).asked.length;
    await page.waitForTimeout(550);
    d = await detail(page);
    const nw = d.asked.slice(n0);
    check('4. new tester text: asked after 400 ms (axes and styles, the head unchanged)', right === n0 && nw.sort().join(' / ') === ['Lato|axes|Sphinx of black quartz', 'Lato|styles|Sphinx of black quartz'].sort().join(' / '), JSON.stringify([right, n0, nw]));
    check('   meanwhile the tester and the rows show the new text in the faces they had', d.line.text === 'Sphinx of black quartz' && d.line.ff === ff0 && d.styles.every(s => s.text === 'Sphinx of black quartz' && /gfpv/.test(s.ff)), JSON.stringify([d.line, d.styles[0]]));
    await drain(page);
    d = await detail(page);
    check('   then their new faces', d.line.ff !== ff0 && /gfpv/.test(d.line.ff) && !d.line.wait, JSON.stringify(d.line));
    await page.evaluate(() => { const r = document.getElementById('gfDSize'); r.value = '72'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    d = await detail(page);
    check('   size 72: the tester line at 72 px', d.dsize === '14,96,72,72' && d.line.size === '72px', JSON.stringify([d.dsize, d.line.size]));
    // Back: the list where it was
    await page.evaluate(() => backNavigate());
    await page.waitForTimeout(250);
    const back = await page.evaluate(() => ({ title: document.getElementById('gfTitle').textContent, top: document.getElementById('gfScroll').scrollTop, list: getComputedStyle(document.getElementById('gfListView')).display }));
    check('5. Back: the list, scrolled where it was', back.title === 'Google Fonts' && back.list !== 'none' && Math.abs(back.top - listTop) < 2, JSON.stringify([back, listTop]));
    // again: the details are not asked again
    await page.evaluate(() => { document.querySelector('#gfList .gf-card[data-f="Lato"]').click(); });
    await page.waitForTimeout(250);
    d = await detail(page);
    check('   Lato again: its details at once, not asked again; the tester starts over', d.calls.join(',') === 'gfontsInfo:Lato' && /SIL Open Font License 1\.1/.test(d.about) && d.dtext === LATIN && d.dsize === '14,96,40,40', JSON.stringify([d.calls, d.dtext, d.dsize]));
    await drain(page);
    // a variable family: the axis sliders
    await page.evaluate(() => gfBackToList());
    await showFam(page, 'Roboto');
    d = await detail(page);
    check('6. Roboto: meta with its axes', d.meta === 'Sans serif · Christian Robertson, ParaType, Font Bureau · 18 styles · Variable: Width, Weight', d.meta);
    check('   two sliders from the list\'s axis names, at their defaults', d.axes.join(' | ') === 'Width:gfAx_wdth:75:100:100:100 | Weight:gfAx_wght:100:900:400:400', d.axes.join(' | '));
    check('   the tester line at the defaults', /"wdth" 100/.test(d.line.fvs) && /"wght" 400/.test(d.line.fvs), d.line.fvs);
    check('   18 style rows, the first "Thin 100", the last "Black 900 Italic"', d.styles.length === 18 && d.styles[0].name === 'Thin 100' && d.styles[1].name === 'Thin 100 Italic' && d.styles[17].name === 'Black 900 Italic', d.styles.map(s => s.name).join(','));
    await page.evaluate(() => { const r = document.getElementById('gfAx_wght'); r.value = '700'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.evaluate(() => { const r = document.getElementById('gfAx_wdth'); r.value = '80'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    d = await detail(page);
    check('   Weight 700, Width 80: the values shown, font-variation-settings follows', /"wght" 700/.test(d.line.fvs) && /"wdth" 80/.test(d.line.fvs) && d.axes.join(' | ') === 'Width:gfAx_wdth:75:100:80:80 | Weight:gfAx_wght:100:900:700:700', JSON.stringify([d.line.fvs, d.axes]));
    check('   no new preview for a slider', !(await page.evaluate(() => __fmk.gfPending().some(p => p.mode === 'axes' && p.family === 'Roboto' && __fmk.gfPreviews.filter(q => q.family === 'Roboto' && q.mode === 'axes').length > 1))));
    await drain(page);
    const desc = await page.evaluate(() => { const r = gf.faces.get('Roboto|axes|The quick brown fox jumps over the lazy dog.'); return r ? r.list.map(f => [f.weight, f.stretch, f.style].join(' ')) : null; });
    check('   the axes face covers the ranges (weight 100 900, width 75% 100%), upright and italic', desc && desc.join(' / ') === '100 900 75% 100% normal / 100 900 75% 100% italic', JSON.stringify(desc));
    await page.evaluate(() => __fmk.gfStepAll());
    await page.waitForTimeout(150);
    d = await detail(page);
    check('   Apache License 2.0, three designers', /Licence Apache License 2\.0/.test(d.about) && /Designers Christian Robertson, ParaType, Font Bureau/.test(d.about) && !(await page.evaluate(() => document.querySelectorAll('#gfGet .gf-rfn').length)), d.about);
    check('   "Weight N" for a weight with no name', await page.evaluate(() => gfStyleName('250') === 'Weight 250' && gfStyleName('950i') === 'Weight 950 Italic' && gfStyleName('500') === 'Medium 500'));
    // the details fail: why, and Retry
    await page.evaluate(() => { __fmk.gfInfoError = { code: 'network', error: 'The download failed: timeout' }; gfBackToList(); });
    await showFam(page, 'Inter');
    await page.evaluate(() => __fmk.gfStepAll());
    await page.waitForTimeout(150);
    d = await detail(page);
    const er = await page.evaluate(() => ({ err: (document.querySelector('#gfInfoErr .is-err') || {}).textContent, retry: !!document.getElementById('gfInfoRetry'), dl: !!document.getElementById('gfDlBtn'), n: !!document.getElementById('gfFileCount') }));
    check('7. the details fail: the reason in the page\'s words and Retry; the designer from the list', er.err === 'The download failed (timeout). Check the connection and try again.' && er.retry && /Designer Rasmus Andersson/.test(d.about) && !/Licence/.test(d.about), JSON.stringify([er, d.about]));
    check('   Download still there, without the number of files', er.dl && !er.n, JSON.stringify(er));
    await page.evaluate(() => { __fmk.gfInfoError = null; });
    await page.click('#gfInfoRetry');
    d = await detail(page);
    check('   Retry: asked again, "Getting the details…"', d.calls.filter(c => c === 'gfontsInfo:Inter').length === 2 && /Getting the details from Google Fonts…/.test(d.about), JSON.stringify(d.calls));
    await page.evaluate(() => __fmk.gfStepAll());
    await page.waitForTimeout(150);
    d = await detail(page);
    check('   then the details, and "2 font files" under Download', /Licence SIL Open Font License 1\.1/.test(d.about) && (await page.evaluate(() => (document.getElementById('gfFileCount') || {}).textContent)) === '2 font files', d.about);
    // leaving the page drops what it asked for
    await page.evaluate(() => gfBackToList());
    await drain(page);
    await showFam(page, 'Nabla');
    const pend = await page.evaluate(() => [...new Set(__fmk.gfPending().filter(p => p.family === 'Nabla').map(p => p.mode))].sort().join(','));
    await page.evaluate(() => gfBackToList());
    await page.waitForTimeout(100);
    await drain(page);
    d = await detail(page);
    check('8. a family\'s page left before its previews came: dropped', pend === 'axes,card,styles' && !d.keys.some(k => /^Nabla\|(axes|styles)\|/.test(k) || k === 'Nabla|card|Nabla'), JSON.stringify([pend, d.keys.filter(k => /^Nabla/.test(k))]));
    await page.evaluate(() => __fmk.gfStepAll());                             // its details come while the list is shown
    await page.waitForTimeout(100);
    await showFam(page, 'Nabla');
    d = await detail(page);
    check('   its details came meanwhile: shown at once, not asked again', d.calls.filter(c => c === 'gfontsInfo:Nabla').length === 1 && /Licence SIL/.test(d.about), JSON.stringify(d.calls));
    const note = await page.evaluate(() => (document.getElementById('gfPlainNote') || {}).textContent);
    check('9. Nabla (colour): meta says Colour; a note that Make a font needs a plain text font', /· Colour$/.test(d.meta) && /^Display · Arthur Reinders Folmer, Just van Rossum · 1 style · Variable: Extrusion Depth, Edge Highlight/.test(d.meta)
      && note === 'This family has colour glyphs. Make a font needs a plain text font, so this one may not work there.', JSON.stringify([d.meta, note]));
    await page.evaluate(() => gfBackToList());
    await showFam(page, 'Material Symbols Outlined', 'material');
    d = await detail(page);
    const note2 = await page.evaluate(() => (document.getElementById('gfPlainNote') || {}).textContent);
    check('   Material Symbols (symbols, no category): the symbol note; the meta starts with its designer', note2 === 'This is a symbol family. Make a font needs a plain text font, so this one may not work there.' && /^Google · 7 styles · Variable: Optical Size, Weight, Fill, Grade$/.test(d.meta), JSON.stringify([note2, d.meta]));
    // Back steps: the family, the list, closed; a tap outside does the same
    await page.evaluate(() => backNavigate()); await page.waitForTimeout(150);
    const st1 = await page.evaluate(() => [document.getElementById('gfSheet').classList.contains('show'), document.getElementById('gfTitle').textContent]);
    await page.evaluate(() => backNavigate()); await page.waitForTimeout(150);
    const st2 = await page.evaluate(() => document.getElementById('gfSheet').classList.contains('show'));
    check('10. Back: the list, then closed', st1[0] && st1[1] === 'Google Fonts' && !st2, JSON.stringify([st1, st2]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
