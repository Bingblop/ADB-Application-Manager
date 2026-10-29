// v7.18.0 Google Fonts, the cards' previews: only the cards in view (and 300 px past it) ask for theirs (gfontsPreview, 'card'), at most 6 at a time;
// each answer is loaded as a FontFace named gfpv<n> and the card's line is drawn in it. A card scrolled into view asks then; one already drawn does
// not ask again. Text typed in the preview field waits 400 ms, then the cards in view ask for it (a new generation): the answers of the old one, and
// those for a card a search took out of the list, are dropped (no face is made of them). Meanwhile a card shows the new text in the face it had only
// when that face holds every letter of it (a css2 text= face holds those of its own text alone); else, and a card that had failed, it is blank until
// its answer, never the text in the app's font (so too a family's tester line and style rows). The size slider sets the lines' size. A family that has no
// preview (gf_no_preview) or whose preview failed shows its name in the app's font with "No preview", and is not asked again until the text changes.
// The sample follows the family's main script (Japanese for Noto Sans JP, Arabic for Cairo) and a script the page has no sample of shows the family's
// name (Sunflower, Hangul). A family with no main script whose subsets leave Latin out takes their sample: the emoji fonts' sixteen emoji for Noto
// Color Emoji (on its page too), Khmer for Chenla; one with none the page has (Noto Sans Lycian) shows "No preview" without asking; no subsets
// listed (Playwrite) is Latin. A face that draws none of a Latin text's letters (Google's answer for an emoji family asked for letters) is no
// preview, and is not kept: a card's sample, a family page's heading, a typed text. Past 150 faces the oldest are taken out of document.fonts
// (a card that kept one of them for a new text then waits, blank, for its own).
// Closing the sheet drops what is still on its way. A preview that has not answered in 30 s stops counting toward the 6, and its answer is still
// drawn when it comes (on the cards and on a family's page). A line clips (for its ellipsis) .6em above and below itself, so the marks past it show
// (Tibetan vowel signs, Nastaliq's dots, Khmer subscripts), at every size and for "No preview" too; the line stays where it was, and the room stays
// inside its card (which grows by what of it the card's padding cannot hold), so a tap in the gap between two cards opens neither.
// Mock bridge: lib/fontmaker_mock.js (opts.gf.hold: previews answer when the script says; opts.gf.emojiFace: faces of assets/emoji/twemoji.ttf);
// the list: fixtures/gfonts-catalog.json, and three families of the live list with no main script (as fetched 2026-10-27).
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));
// no main script (k ""): Khmer only, Lycian only, no subsets listed (compact entries of the live list, 2026-10-27)
const NOSCRIPT = [
  { f: 'Chenla', c: 'display', d: ['Danh Hong'], p: 1623, t: 66, a: '2011-03-02', m: '2022-09-21', s: ['khmer'], w: ['400'], x: [], k: '' },
  { f: 'Noto Sans Lycian', c: 'sans', d: ['Google'], p: 1953, t: 242, a: '2020-11-19', m: '2025-09-08', s: ['lycian'], w: ['400'], x: [], k: '', noto: 1 },
  { f: 'Playwrite US Trad', c: 'hand', d: ['TypeTogether', 'Veronika Burian', 'José Scaglione'], p: 1168, t: 1548, a: '2024-05-29', m: '2025-09-04', s: [], w: ['100', '200', '300', '400'], x: [['wght', 100, 400, 400]], k: '' },
];
const CAT2 = Object.assign({}, CAT, { families: CAT.families.concat(NOSCRIPT) });
const EMOJI = fs.readFileSync(path.join(REPO, 'assets', 'emoji', 'twemoji.ttf')).toString('base64');   // sixteen emoji, no letter
const LATIN = 'The quick brown fox jumps over the lazy dog.';

async function open(b, opts, height) {
  const ctx = await b.newContext({ viewport: { width: 412, height: height || 900 } });
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
// the cards in view of the sheet, 300 px past its edges (what the IntersectionObserver is given)
const view = (page) => page.evaluate(() => {
  const sc = document.getElementById('gfScroll').getBoundingClientRect();
  return [...document.querySelectorAll('#gfList .gf-card')].filter(c => { const r = c.querySelector('.gf-pv').getBoundingClientRect(); return r.bottom > sc.top - 300 && r.top < sc.bottom + 300; }).map(c => c.dataset.f);
});
const state = (page) => page.evaluate(() => ({
  asked: __fmk.gfPreviews.map(p => p.family + '|' + p.mode + '|' + p.text), pending: __fmk.gfPending().map(p => p.family), out: __fmk.gfOut, max: __fmk.gfOutMax,
  keys: [...gf.faces.keys()], docFaces: [...document.fonts].filter(f => /^gfpv\d+$/.test(f.family)).length,
}));
const card = (page, f) => page.evaluate((f) => {
  const c = document.querySelector('#gfList .gf-card[data-f="' + f + '"]');
  if (!c) return null;
  const pv = c.querySelector('.gf-pv');
  return { text: pv.textContent, ff: pv.style.fontFamily, wait: pv.classList.contains('gf-wait'), failed: pv.classList.contains('gf-failed'), nopv: (pv.querySelector('.gf-nopv') || {}).textContent || '',
    key: pv.dataset.pvk, size: getComputedStyle(pv).fontSize, noTr: pv.getAttribute('translate'), vis: getComputedStyle(pv).visibility };
}, f);
// answer every preview on its way, and the ones asked after those, until none is left
const drain = async (page) => { for (let i = 0; i < 40; i++) { const n = await page.evaluate(() => __fmk.gfPending().length); if (!n) break; await page.evaluate(() => __fmk.gfAnswerAll()); await page.waitForTimeout(120); } await page.waitForTimeout(150); };
// text typed in the preview field (the cards wait 400 ms for it)
const typeText = async (page, t, wait) => { await page.evaluate((t) => { const el = document.getElementById('gfText'); el.value = t; el.dispatchEvent(new Event('input', { bubbles: true })); }, t); if (wait !== false) await page.waitForTimeout(500); };
const scrollTo = async (page, f) => { await page.evaluate((f) => { const c = document.querySelector('#gfList .gf-card[data-f="' + f + '"]'), sc = document.getElementById('gfScroll'); sc.scrollTop += c.getBoundingClientRect().top - sc.getBoundingClientRect().top - 60; }, f); await page.waitForTimeout(250); };
// a one-line preview clips at its padding box (overflow: hidden, for the ellipsis), above and below too: it needs room there for the marks past its
// 1.35 line (Tibetan vowel signs, Nastaliq's dots, Khmer subscripts; at least .55em), while the line stays where it was without that room (the rule
// before it, put on the element for a moment). A card's room below ends inside the card (its border): the card grows by what of it its own padding
// cannot hold, so the room never reaches the gap between cards, where it took the taps and drew the marks over the next card. What is wrong, by
// element; n: how many were looked at
const room = (page, sel) => page.evaluate((sel) => {
  const els = [...document.querySelectorAll(sel)], near = (a, b) => Math.abs(a - b) <= 0.5;
  const bad = els.map(el => {
    const box = el.closest('.gf-card, .gf-style'), cs = getComputedStyle(el), fs = parseFloat(cs.fontSize);
    const at = () => { const c = getComputedStyle(el), r = el.getBoundingClientRect(), b = box.getBoundingClientRect(), pt = parseFloat(c.paddingTop), pb = parseFloat(c.paddingBottom); return { pt, pb, line: r.height - pt - pb, top: r.top + pt - b.top, h: b.height, below: r.bottom - b.bottom }; };
    const now = at(), card = el.classList.contains('gf-pv'), bs = getComputedStyle(box);
    el.style.setProperty('padding-block', '0'); el.style.setProperty('margin-block', card ? 'var(--space-2) 0' : '0');
    const was = at();
    el.style.removeProperty('padding-block'); el.style.removeProperty('margin-block');
    const why = [], grow = card ? Math.max(0, 0.6 * fs - parseFloat(bs.paddingBottom)) : 0;
    if (cs.overflowY !== 'visible' && !(now.pt >= 0.55 * fs && now.pb >= 0.55 * fs)) why.push('room above/below ' + now.pt + '/' + now.pb + ' px at ' + fs + ' px');
    if (!near(now.line, 1.35 * fs)) why.push('line ' + now.line + ' px, not ' + 1.35 * fs);
    if (!near(now.h, was.h + grow) || !near(now.top, was.top)) why.push('moved: height ' + now.h + ' (was ' + was.h + ', + ' + grow + '), line at ' + now.top + ' (was ' + was.top + ')');
    if (card && now.below > -parseFloat(bs.borderBottomWidth) + 0.5) why.push('its room ends ' + now.below + ' px past the card\'s bottom, past its border');
    return why.length ? (box.dataset.f || box.querySelector('.gf-style-name').textContent) + ': ' + why.join('; ') : '';
  }).filter(Boolean);
  return { n: els.length, bad };
}, sel);
// a tap in the gap between the first two cards lands on the list, not on the card above; one on the middle of the first card's line still is that
// card's. What elementFromPoint gives at both points (a card's family, else the element's id), and the families a click at each would open
// (gfShow stubbed meanwhile, so nothing opens)
const gapTap = async (page) => {
  const pt = await page.evaluate(() => {
    const [c1, c2] = document.querySelectorAll('#gfList .gf-card'), bar = document.querySelector('#gfSheet .gf-bar').getBoundingClientRect(), sc = document.getElementById('gfScroll').getBoundingClientRect();
    const r1 = c1.getBoundingClientRect(), r2 = c2.getBoundingClientRect(), pv = c1.querySelector('.gf-pv').getBoundingClientRect();
    const x = (r1.left + r1.right) / 2, gy = (r1.bottom + r2.top) / 2, ly = (pv.top + pv.bottom) / 2;
    const at = (y) => { const e = document.elementFromPoint(x, y), c = e && e.closest('.gf-card'); return c ? c.dataset.f : e ? e.id || e.tagName : ''; };
    return { f1: c1.dataset.f, x, gy, ly, gapPx: r2.top - r1.bottom, inView: ly > bar.bottom && gy < sc.bottom, gap: at(gy), line: at(ly) };
  });
  await page.evaluate(() => { window.__gfShowWas = window.gfShow; window.__shown = []; window.gfShow = f => { window.__shown.push(f); }; });
  await page.mouse.click(pt.x, pt.gy);
  pt.gapOpens = await page.evaluate(() => window.__shown.splice(0));
  await page.mouse.click(pt.x, pt.ly);
  pt.lineOpens = await page.evaluate(() => { window.gfShow = window.__gfShowWas; const s = window.__shown.splice(0); if (document.activeElement) document.activeElement.blur(); return s; });
  return pt;
};
const gapOk = (g) => g.inView && g.gapPx > 0 && g.gap === 'gfList' && !g.gapOpens.length && g.line === g.f1 && g.lineOpens.join() === g.f1;

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true } }, 1400);
    let v = await view(page), s = await state(page);
    const fams = s.asked.map(a => a.split('|')[0]);
    check('1. only cards in view ask, 6 of them at most', v.length > 6 && fams.length === 6 && fams.every(f => v.includes(f)) && s.out === fams.length && s.max <= 6, JSON.stringify([fams, v, s.out, s.max]));
    check('   in the card mode, with the sample text', s.asked.every(a => /\|card\|/.test(a)) && s.asked.includes('Roboto|card|' + LATIN), s.asked.join(' / '));
    const r0 = await card(page, 'Roboto');
    check('   a card waits with its text in the app font (translate="no")', r0.wait && r0.ff === '' && r0.text === LATIN && r0.noTr === 'no', JSON.stringify(r0));
    await drain(page);
    s = await state(page);
    const asked1 = s.asked.map(a => a.split('|')[0]);
    check('2. answered: every card in view asked once, none past it, never more than 6 on their way', v.every(f => asked1.filter(x => x === f).length === 1) && asked1.every(f => v.includes(f)) && s.max <= 6, JSON.stringify([asked1, v, s.max]));
    const r1 = await card(page, 'Roboto');
    check('   the card is drawn in its face (gfpv<n>)', !r1.wait && /^['"]?gfpv\d+['"]?, /.test(r1.ff) && r1.text === LATIN, JSON.stringify(r1));
    let rm = await room(page, '#gfList .gf-pv:not(.gf-wait)');
    check('   the drawn lines (28 px) clip .55em or more above and below the line, so marks past it show, inside their cards; the lines where they were', rm.n >= 6 && !rm.bad.length, JSON.stringify(rm));
    check('   one face per card in document.fonts', s.docFaces === asked1.length && s.keys.length === asked1.length, JSON.stringify([s.docFaces, s.keys.length]));
    // scroll: the cards that come into view ask then
    const n1 = s.asked.length;
    await scrollTo(page, 'Roboto Condensed');
    v = await view(page);
    await drain(page);
    s = await state(page);
    const newly = s.asked.slice(n1).map(a => a.split('|')[0]);
    check('3. scrolled: the cards now in view ask, the ones drawn before do not', newly.length > 0 && newly.every(f => v.includes(f) && !asked1.includes(f)) && v.filter(f => !asked1.includes(f)).every(f => newly.includes(f)), JSON.stringify([newly, v]));
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 0; });
    await page.waitForTimeout(250);
    check('   scrolled back: nothing asked again', (await state(page)).asked.length === s.asked.length);
    // a new text whose every letter the sample has (a css2 text= face holds those of its own text alone): the cards keep their faces until the new ones come
    const rL = await card(page, 'Roboto');
    await typeText(page, 'The lazy dog');
    const rK = await card(page, 'Roboto');
    check('3b. "The lazy dog" (all of it in the sample): a card shows it at once in the face it had, until its new one comes', rK.text === 'The lazy dog' && !rK.wait && rK.ff === rL.ff && /gfpv/.test(rK.ff) && rK.vis === 'visible'
      && (await state(page)).pending.includes('Roboto'), JSON.stringify([rL.ff, rK]));
    await drain(page);
    const rK2 = await card(page, 'Roboto');
    check('   then in its own', rK2.text === 'The lazy dog' && !rK2.wait && /gfpv/.test(rK2.ff) && rK2.ff !== rL.ff, JSON.stringify(rK2));
    // a new text: 400 ms, a new generation; the old generation's answers are dropped
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 0; });
    await page.waitForTimeout(200);
    const n2 = (await state(page)).asked.length;
    await typeText(page, 'Hamburgefonstiv', false);
    await typeText(page, 'Hello', false);
    const right = (await state(page)).asked.length;
    await page.waitForTimeout(550);
    s = await state(page);
    v = await view(page);
    const hello = s.asked.slice(n2).filter(a => /\|Hello$/.test(a)).map(a => a.split('|')[0]);
    check('4. typing waits 400 ms; then the cards in view ask for the last text only', right === n2 && hello.length === Math.min(6, v.length) && hello.every(f => v.includes(f)) && !s.asked.slice(n2).some(a => /Hamburgefonstiv/.test(a)), JSON.stringify([right, n2, hello, v]));
    const rH = await card(page, hello[0]);
    check('   "Hello" ("H" is not in the face shown): the card has the new text at once, blank (gf-wait) until its face comes, never in the app\'s font', rH.text === 'Hello' && rH.wait && rH.ff === '' && rH.vis === 'hidden', JSON.stringify(rH));
    // another text before those answers come: they come for a text no card shows any more
    const before = s.keys.length;
    await typeText(page, 'Hey');
    const stale = await page.evaluate(() => __fmk.gfPending().filter(p => p.text === 'Hello').length);
    await page.evaluate((n) => __fmk.gfAnswer(n), stale);
    await page.waitForTimeout(300);
    s = await state(page);
    check('5. answers of an older text are dropped: no face made of them', stale === hello.length && !s.keys.some(k => /\|Hello$/.test(k)) && s.keys.length === before, JSON.stringify([stale, s.keys.filter(k => !/\|The quick/.test(k)), before]));
    await drain(page);
    s = await state(page);
    const heyKeys = s.keys.filter(k => /\|Hey$/.test(k)).map(k => k.split('|')[0]);
    v = await view(page);
    check('   the new text\'s answers draw the cards in view', v.every(f => heyKeys.includes(f)) && (await card(page, v[0])).text === 'Hey' && !(await card(page, v[0])).wait, JSON.stringify([heyKeys, v]));
    check('   never more than 6 on their way', s.max <= 6, s.max);
    // the size
    await page.evaluate(() => { const r = document.getElementById('gfSize'); r.value = '50'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(100);
    const sz = await page.evaluate(() => ({ lab: document.getElementById('gfSizeV').textContent, v: document.getElementById('gfList').style.getPropertyValue('--gf-pv') }));
    check('6. the size slider: 50, every line at 50 px', sz.lab === '50' && sz.v.trim() === '50px' && (await card(page, 'Roboto')).size === '50px', JSON.stringify([sz, (await card(page, 'Roboto')).size]));
    let gt = await gapTap(page);
    check('   at 50 px a tap in the gap between two cards opens neither (it lands on the list); one on the first card\'s line opens that card', gapOk(gt), JSON.stringify(gt));
    await page.evaluate(() => { const r = document.getElementById('gfSize'); r.value = '72'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(100);
    rm = await room(page, '#gfList .gf-pv:not(.gf-wait)');
    check('   at 72 px the room above and below grows with the line, inside the card, which grows by what its padding cannot hold; the lines where they were', rm.n >= 1 && !rm.bad.length, JSON.stringify(rm));
    gt = await gapTap(page);
    check('   at 72 px too, a tap in the gap lands on the list, one on the line opens the card', gapOk(gt), JSON.stringify(gt));
    await page.evaluate(() => { const r = document.getElementById('gfSize'); r.value = '50'; r.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(100);
    // a card a search takes out of the list: its answer is dropped
    await typeText(page, 'Abc');
    const gone = await page.evaluate(() => __fmk.gfPending().map(p => p.family));
    await page.evaluate(() => { const s = document.getElementById('gfSearch'); s.value = 'lato'; s.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(300);
    await page.evaluate(() => { const n = __fmk.gfPending().filter(p => p.family !== 'Lato').length; __fmk.gfAnswer(n); });
    await page.waitForTimeout(300);
    s = await state(page);
    check('7. a search took the cards out: their answers are dropped', gone.length > 0 && !gone.includes('Lato') && !s.keys.some(k => /\|Abc$/.test(k) && k.split('|')[0] !== 'Lato'), JSON.stringify([gone, s.keys.filter(k => /Abc/.test(k))]));
    await drain(page);
    const lato = await card(page, 'Lato');
    check('   the one card left asks and is drawn', lato && lato.text === 'Abc' && !lato.wait && /gfpv/.test(lato.ff), JSON.stringify(lato));
    // closing the sheet: what is still on its way is dropped
    await page.evaluate(() => { const s = document.getElementById('gfSearch'); s.value = ''; s.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(300);
    await drain(page);
    await typeText(page, 'Bye');
    const k0 = (await state(page)).keys.length, p0 = (await state(page)).pending.length;
    await page.evaluate(() => gfClose());
    await page.evaluate(() => __fmk.gfAnswerAll());
    await page.waitForTimeout(300);
    s = await state(page);
    check('8. closed with previews on their way: no face made of them', p0 > 0 && s.keys.length === k0 && !s.keys.some(k => /\|Bye$/.test(k)), JSON.stringify([p0, k0, s.keys.length]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // no preview, a failed one; the samples by script
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT2, previewError: ['Inter'], previewFail: ['Open Sans'] } });
    await page.waitForTimeout(600);
    const inter = await card(page, 'Inter'), os = await card(page, 'Open Sans'), rob = await card(page, 'Roboto');
    check('9. no preview (gf_no_preview): the name in the app font and "No preview"', inter.failed && !inter.wait && inter.ff === '' && inter.text === 'InterNo preview' && inter.nopv === 'No preview', JSON.stringify(inter));
    check('   a failed preview (network): the same', os.failed && os.text === 'Open SansNo preview', JSON.stringify(os));
    const rmF = await room(page, '#gfList .gf-card[data-f="Inter"] .gf-pv, #gfList .gf-card[data-f="Open Sans"] .gf-pv');
    check('   the room above and below follows the name\'s smaller size; the cards and the lines where they were', rmF.n === 2 && !rmF.bad.length, JSON.stringify(rmF));
    check('   the others are drawn', !rob.failed && /gfpv/.test(rob.ff), JSON.stringify(rob));
    const n0 = await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Inter').length);
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 4000; });
    await page.waitForTimeout(400);
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 0; });
    await page.waitForTimeout(400);
    check('   scrolled away and back: not asked again', (await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Inter').length)) === n0, n0);
    await typeText(page, 'Go');
    await page.waitForTimeout(300);
    const inter2 = await card(page, 'Inter');
    check('   a new text asks again (and it fails again: "No preview" under the name)', (await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Inter').length)) === n0 + 1 && inter2.text === 'InterNo preview', JSON.stringify([inter2]));
    await typeText(page, '');
    await page.waitForTimeout(300);
    const samp = async (q, f) => {
      await page.evaluate((q) => { const s = document.getElementById('gfSearch'); s.value = q; s.dispatchEvent(new Event('input', { bubbles: true })); }, q);
      await page.waitForTimeout(800);
      const c = await card(page, f), asked = await page.evaluate((f) => __fmk.gfPreviews.filter(p => p.family === f).map(p => p.text).pop(), f);
      return { text: c && c.text, asked, dir: await page.evaluate((f) => document.querySelector('#gfList .gf-card[data-f="' + f + '"] .gf-pv').getAttribute('dir'), f) };
    };
    const jp = await samp('noto sans jp', 'Noto Sans JP'), ar = await samp('cairo', 'Cairo'), sun = await samp('sunflower', 'Sunflower'), mat = await samp('material', 'Material Symbols Outlined');
    check('10. Noto Sans JP (Jpan): the Japanese sample, asked with it', jp.text === '私はガラスを食べられます。それは私を傷つけません。' && jp.asked === jp.text, JSON.stringify(jp));
    check('   Cairo (Arab): the Arabic sample, its direction from the text (dir="auto")', ar.text === 'أنا قادر على أكل الزجاج و هذا لا يؤلمني.' && ar.asked === ar.text && ar.dir === 'auto', JSON.stringify(ar));
    check('   Sunflower (Hang, no sample in the table): its own name', sun.text === 'Sunflower' && sun.asked === 'Sunflower', JSON.stringify(sun));
    check('   no script: Latin', mat.text === LATIN, JSON.stringify(mat));
    // no main script, and subsets without Latin: what they name (the samples read from the page: no emoji written here)
    const S = await page.evaluate(() => ({ emo: FNT_EMO_SAMPLE, khmr: GF_SAMPLES.Khmr }));
    const emo = await samp('noto color emoji', 'Noto Color Emoji'), ch = await samp('chenla', 'Chenla'), ly = await samp('lycian', 'Noto Sans Lycian'), pw = await samp('playwrite', 'Playwrite US Trad');
    check('   Noto Color Emoji (no script, emoji only): the emoji fonts\' sixteen, asked with them', emo.text === S.emo && emo.asked === S.emo, JSON.stringify(emo));
    check('   Chenla (no script, khmer only): the Khmer sample, asked with it', ch.text === S.khmr && ch.asked === S.khmr, JSON.stringify(ch));
    check('   Noto Sans Lycian (no script, lycian only: no sample): its name and "No preview", nothing asked', ly.text === 'Noto Sans LycianNo preview' && ly.asked === undefined, JSON.stringify(ly));
    check('   Playwrite US Trad (no script, no subsets listed): Latin', pw.text === LATIN && pw.asked === LATIN, JSON.stringify(pw));
    const unit = await page.evaluate(() => [gfSample({ f: 'Noto Serif Myanmar', k: '', s: ['myanmar'] }) === GF_SAMPLES.Mymr, gfSample({ f: 'Siemreap', k: '', s: ['khmer'] }) === GF_SAMPLES.Khmr,
      gfSample({ f: 'Noto Emoji', k: '', s: ['emoji'] }) === FNT_EMO_SAMPLE, gfSample({ f: 'Libertinus Math', k: '', s: [] }) === GF_LATIN, gfSample({ f: 'Noto Sans Khmer', k: 'Khmr', s: ['khmer', 'latin'] }) === GF_SAMPLES.Khmr,
      gfSample({ f: 'Lato', k: '', s: ['latin', 'latin-ext'] }) === GF_LATIN, gfKeyParts(gfKey('Noto Sans Lycian', 'card', '')).text === '']);
    check('   gfSample: Noto Serif Myanmar Myanmar, Siemreap Khmer, Noto Emoji the emoji, Libertinus Math and Lato Latin, Noto Sans Khmer its script; an empty text keeps its key', unit.every(Boolean), JSON.stringify(unit));
    // Noto Color Emoji's page: the tester starts with the emoji, and its line and style rows ask with them
    await samp('noto color emoji', 'Noto Color Emoji');
    await page.click('#gfList .gf-card[data-f="Noto Color Emoji"]');
    await page.waitForTimeout(800);
    const det = await page.evaluate(() => ({ t: document.getElementById('gfDText').value, asked: __fmk.gfPreviews.filter(p => p.family === 'Noto Color Emoji' && p.mode !== 'card').map(p => p.mode + '|' + p.text) }));
    check('   its page: the tester starts with the sixteen, and its line and style row ask with them', det.t === S.emo && det.asked.sort().join(' / ') === 'axes|' + S.emo + ' / styles|' + S.emo, JSON.stringify(det));
    await page.evaluate(() => gfBackToList());
    await page.waitForTimeout(200);
    // Noto Sans Lycian's page: nothing to draw, nothing asked but its heading
    await samp('lycian', 'Noto Sans Lycian');
    await page.click('#gfList .gf-card[data-f="Noto Sans Lycian"]');
    await page.waitForTimeout(800);
    const lyd = await page.evaluate(() => ({ t: document.getElementById('gfDText').value, line: document.getElementById('gfDLine').textContent, rows: [...document.querySelectorAll('#gfStyles .gf-style-pv')].map(x => x.textContent),
      asked: __fmk.gfPreviews.filter(p => p.family === 'Noto Sans Lycian').map(p => p.mode + '|' + p.text) }));
    check('   Noto Sans Lycian\'s page: the tester empty, its line and style row "No preview", only its heading asked', lyd.t === '' && lyd.line === 'No preview' && lyd.rows.join() === 'No preview' && lyd.asked.join() === 'card|Noto Sans Lycian', JSON.stringify(lyd));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a tall screen with every card in view: past 150 faces the oldest go
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT } }, 11000);
    await page.click('#gfMoreBtn'); await page.waitForTimeout(100);
    await page.click('#gfMoreBtn'); await page.waitForTimeout(1500);
    let s = await state(page);
    check('11. 64 cards in view: 64 faces, never more than 6 asked at a time', s.keys.length === 64 && s.docFaces === 64 && s.max <= 6, JSON.stringify([s.keys.length, s.docFaces, s.max]));
    for (const t of ['One', 'Two']) { await typeText(page, t); await page.waitForTimeout(1500); }
    s = await state(page);
    const counts = await page.evaluate(() => ({ n: gf.nFaces, drawn: [...document.querySelectorAll('#gfList .gf-pv')].filter(e => !e.classList.contains('gf-wait') && /gfpv/.test(e.style.fontFamily) && e.textContent === 'Two').length }));
    check('   three texts later: at most 150 faces kept, the oldest taken out of document.fonts', counts.n <= 150 && s.docFaces <= 150 && s.docFaces === counts.n && !s.keys.some(k => /\|The quick/.test(k) && k.split('|')[0] === 'Roboto'), JSON.stringify([counts.n, s.docFaces]));
    check('   every card still drawn in its newest face', counts.drawn === 64, counts.drawn);
    check('   never more than 6 asked at a time', s.max <= 6, s.max);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // answers that come after the page's 30 s cutoff (the app's own timeouts are longer): still drawn
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true, previewFail: ['Inter'] } }, 1400);
    const first = await page.evaluate(() => __fmk.gfPending().map(p => p.family));
    const later = async () => { await page.evaluate(() => { const D = window.__realNow || Date.now; window.__realNow = D; Date.now = () => D() + 31000; }); };
    const now = async () => { await page.evaluate(() => { if (window.__realNow) Date.now = window.__realNow; }); };
    await later();                                                          // the page's clock 31 s on: the next answer's pump finds the others slow
    await page.evaluate(() => __fmk.gfAnswer(1));
    await page.waitForTimeout(250);
    const mid = await page.evaluate(() => ({ late: Object.keys(gf.late || {}).length, inflight: Object.keys(gf.inflight).length }));
    await drain(page);
    const cs = [];
    for (const f of first) cs.push(Object.assign({ f }, await card(page, f)));
    const late0 = await page.evaluate(() => Object.keys(gf.late || {}).length);
    check('12. answers after 30 s: every card first asked is drawn in its face', first.length === 6 && first.includes('Inter') && cs.filter(c => c.f !== 'Inter').every(c => !c.wait && /gfpv\d+/.test(c.ff)), JSON.stringify(cs));
    check('   a late failure says "No preview"', cs.filter(c => c.f === 'Inter').every(c => c.failed && !c.wait && c.nopv === 'No preview'), JSON.stringify(cs.filter(c => c.f === 'Inter')));
    check('   the slow ones stopped counting (5 of them), and none is left once all came', mid.late === 5 && late0 === 0, JSON.stringify([mid, late0]));
    // a family's page whose previews come late
    await now();
    await page.evaluate(() => gfShow('Roboto'));
    await page.waitForTimeout(250);
    const asked = await page.evaluate(() => __fmk.gfPending().filter(p => p.family === 'Roboto').map(p => p.mode).sort().join(','));
    await later();
    await page.evaluate(() => __fmk.gfAnswer(1));
    await page.waitForTimeout(250);
    await drain(page);
    const fam = () => page.evaluate(() => {
      const one = (el) => ({ wait: el.classList.contains('gf-wait'), ff: el.style.fontFamily });
      return { head: one(document.getElementById('gfDHead')), line: one(document.getElementById('gfDLine')), styles: [...document.querySelectorAll('#gfStyles .gf-style-pv')].map(one) };
    });
    let d = await fam();
    const ok = (x) => !x.wait && /gfpv\d+/.test(x.ff);
    check('   Roboto\'s page, its previews late: the head, the tester line and all 18 style rows drawn', asked === 'axes,card,styles' && ok(d.head) && ok(d.line) && d.styles.length === 18 && d.styles.every(ok), JSON.stringify([asked, d.head, d.line, d.styles.filter(x => !ok(x)).length]));
    await now();
    const n0 = await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Roboto').length);
    await page.evaluate(() => { gfBackToList(); gfShow('Roboto'); });
    await page.waitForTimeout(250);
    d = await fam();
    check('   Back and Roboto again: drawn at once, nothing asked again', ok(d.head) && ok(d.line) && d.styles.every(ok) && (await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Roboto').length)) === n0, JSON.stringify([d.head, d.line, n0]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // faces that draw the emoji and no letter (Google's answer for an emoji family asked for letters holds the space alone): a Latin text in them is no
  // preview, an emoji text is drawn
  {
    const { ctx, page, errors } = await open(b, { b64: Object.assign({ emoji: EMOJI }, FONTS), gf: { catalog: CAT, emojiFace: ['Inter', 'Noto Color Emoji'] } });
    await page.waitForTimeout(600);
    const ES = await page.evaluate(() => FNT_EMO_SAMPLE);
    const inter = await card(page, 'Inter'), rob = await card(page, 'Roboto');
    const kept = await page.evaluate(() => ({ inter: [...gf.faces.keys()].filter(k => /^Inter\|/.test(k)), n: gf.nFaces, doc: [...document.fonts].filter(f => /^gfpv\d+$/.test(f.family)).length, asked: gf.asked['Inter|card|' + GF_LATIN] }));
    check('13. Inter answered with a face that draws none of the Latin sample: its name in the app font and "No preview"', inter.failed && !inter.wait && inter.ff === '' && inter.text === 'InterNo preview' && inter.nopv === 'No preview', JSON.stringify(inter));
    check('   that face is not kept (gf.faces, document.fonts), the others are drawn', !kept.inter.length && kept.doc === kept.n && kept.asked === 'failed' && !rob.failed && /gfpv/.test(rob.ff), JSON.stringify([kept, rob]));
    const n0 = await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Inter').length);
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 4000; });
    await page.waitForTimeout(400);
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 0; });
    await page.waitForTimeout(400);
    check('   scrolled away and back: not asked again', (await page.evaluate(() => __fmk.gfPreviews.filter(p => p.family === 'Inter').length)) === n0 && n0 === 1, n0);
    const search = async (q) => { await page.evaluate((q) => { const s = document.getElementById('gfSearch'); s.value = q; s.dispatchEvent(new Event('input', { bubbles: true })); }, q); await page.waitForTimeout(800); };
    await search('noto color emoji');
    const emo = await card(page, 'Noto Color Emoji');
    check('   Noto Color Emoji, the same face: its sixteen emoji are drawn in it', !emo.failed && !emo.wait && /gfpv/.test(emo.ff) && emo.text === ES, JSON.stringify(emo));
    // its page: the heading is its name in letters (no preview); the tester line and the style row, the emoji, are drawn
    await page.click('#gfList .gf-card[data-f="Noto Color Emoji"]');
    await page.waitForTimeout(800);
    const one = (el) => ({ text: el.textContent, wait: el.classList.contains('gf-wait'), failed: el.classList.contains('gf-failed'), ff: el.style.fontFamily });
    const fam = await page.evaluate((src) => { const one = new Function('el', 'return (' + src + ')(el)'); return { head: one(document.getElementById('gfDHead')), line: one(document.getElementById('gfDLine')),
      rows: [...document.querySelectorAll('#gfStyles .gf-style-pv')].map(one) }; }, one.toString());
    check('   its page: the heading (its name) "No preview" in the app font', fam.head.failed && fam.head.text === 'Noto Color EmojiNo preview' && fam.head.ff === '', JSON.stringify(fam.head));
    check('   the tester line and its style row (the emoji) drawn in the face', !fam.line.failed && /gfpv/.test(fam.line.ff) && fam.line.text === ES && fam.rows.length === 1 && !fam.rows[0].failed && /gfpv/.test(fam.rows[0].ff) && fam.rows[0].text === ES, JSON.stringify([fam.line, fam.rows]));
    await page.evaluate(() => gfBackToList());
    await page.waitForTimeout(200);
    // a typed Latin text: the emoji family has no preview of it, a Latin family draws it
    await search('noto');
    await typeText(page, 'Hello');
    await page.waitForTimeout(600);
    const emoH = await card(page, 'Noto Color Emoji'), sans = await card(page, 'Noto Sans');
    check('   "Hello" typed: Noto Color Emoji "No preview", Noto Sans drawn', emoH.failed && emoH.text === 'Noto Color EmojiNo preview' && !sans.failed && /gfpv/.test(sans.ff) && sans.text === 'Hello', JSON.stringify([emoH, sans]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a new text while its answers are on their way: a line whose face lacks a letter of it, or that had failed, is blank (gf-wait) until its answer,
  // never the text in the app's font shown as the family's look
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true, previewFail: ['Roboto'] } }, 1400);
    await drain(page);
    const r0 = await card(page, 'Roboto');
    await typeText(page, 'Hello');
    const r1 = await card(page, 'Roboto'), asked = await page.evaluate(() => __fmk.gfPending().some(p => p.family === 'Roboto' && p.text === 'Hello'));
    check('14. a failed card ("RobotoNo preview"), then "Hello": blank (gf-wait, no font) while its preview is asked', r0.text === 'RobotoNo preview' && r0.failed
      && r1.text === 'Hello' && r1.wait && !r1.failed && r1.ff === '' && r1.vis === 'hidden' && asked, JSON.stringify([r0, r1, asked]));
    await drain(page);
    const r2 = await card(page, 'Roboto');
    check('   it fails again: "RobotoNo preview"', r2.text === 'RobotoNo preview' && r2.failed && !r2.wait, JSON.stringify(r2));
    // a face kept for a new text, then taken out of document.fonts (past 150 faces, gfTrim): the card waits, blank, for its own, never left in a face
    // that is gone (the app's font)
    await typeText(page, '');
    await typeText(page, 'The lazy dog');
    const tr = await page.evaluate(() => {
      const pv = [...document.querySelectorAll('#gfList .gf-card')].find(c => c.dataset.f !== 'Roboto').querySelector('.gf-pv'), one = () => ({ text: pv.textContent, ff: pv.style.fontFamily, wait: pv.classList.contains('gf-wait') });
      const kept = one();
      gf.nFaces += 1000; gfTrim(''); gf.nFaces -= 1000;
      return { kept, after: one(), faces: gf.faces.size, n: gf.nFaces };
    });
    check('   a card keeping its face for "The lazy dog", that face taken out: blank (gf-wait, no font) until its own comes', !tr.kept.wait && /gfpv/.test(tr.kept.ff) && tr.kept.text === 'The lazy dog'
      && tr.after.wait && tr.after.ff === '' && tr.after.text === 'The lazy dog' && !tr.faces && !tr.n, JSON.stringify(tr));
    await drain(page);
    const r3 = await card(page, await page.evaluate(() => [...document.querySelectorAll('#gfList .gf-card')].find(c => c.dataset.f !== 'Roboto').dataset.f));
    check('   then drawn in its own', !r3.wait && /gfpv/.test(r3.ff) && r3.text === 'The lazy dog', JSON.stringify(r3));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    const { ctx, page, errors } = await open(b, { b64: Object.assign({ emoji: EMOJI }, FONTS), gf: { catalog: CAT, hold: true, emojiFace: ['Noto Color Emoji'] } });
    await drain(page);
    await page.evaluate(() => { const s = document.getElementById('gfSearch'); s.value = 'noto color emoji'; s.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(400);
    await drain(page);
    const e0 = await card(page, 'Noto Color Emoji');
    await typeText(page, 'Hello');
    const e1 = await card(page, 'Noto Color Emoji');
    check('15. Noto Color Emoji drawn in its emoji face, then "Hello": blank (gf-wait, no font) until its answer', !e0.wait && /gfpv/.test(e0.ff) && e1.text === 'Hello' && e1.wait && e1.ff === '' && e1.vis === 'hidden', JSON.stringify([e0, e1]));
    await drain(page);
    const e2 = await card(page, 'Noto Color Emoji');
    check('   then "No preview" (its face draws no letter)', e2.failed && e2.text === 'Noto Color EmojiNo preview', JSON.stringify(e2));
    // its page: the tester line and the style row drawn with the emoji, then "Hello" typed into the tester
    await typeText(page, '');
    await page.click('#gfList .gf-card[data-f="Noto Color Emoji"]');
    await page.waitForTimeout(300);
    await drain(page);
    const lines = () => page.evaluate(() => [document.getElementById('gfDLine')].concat([...document.querySelectorAll('#gfStyles .gf-style-pv')]).map(el => ({ text: el.textContent, ff: el.style.fontFamily,
      wait: el.classList.contains('gf-wait'), failed: el.classList.contains('gf-failed'), vis: getComputedStyle(el).visibility })));
    const d0 = await lines();
    await page.fill('#gfDText', 'Hello');
    await page.waitForTimeout(550);
    const d1 = await lines();
    check('   its page, the tester line and the style row in the emoji face, then "Hello" in the tester: both blank (gf-wait, no font) until their answers',
      d0.length === 2 && d0.every(x => !x.wait && /gfpv/.test(x.ff)) && d1.every(x => x.text === 'Hello' && x.wait && x.ff === '' && x.vis === 'hidden'), JSON.stringify([d0, d1]));
    await drain(page);
    const d2 = await lines();
    check('   then "No preview"', d2.every(x => x.failed && x.text === 'HelloNo preview'), JSON.stringify(d2));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
