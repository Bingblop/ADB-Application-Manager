// v7.18.0 Google Fonts, the cards' previews: only the cards in view (and 300 px past it) ask for theirs (gfontsPreview, 'card'), at most 6 at a time;
// each answer is loaded as a FontFace named gfpv<n> and the card's line is drawn in it. A card scrolled into view asks then; one already drawn does
// not ask again. Text typed in the preview field waits 400 ms, then the cards in view ask for it (a new generation): the answers of the old one, and
// those for a card a search took out of the list, are dropped (no face is made of them). The size slider sets the lines' size. A family that has no
// preview (gf_no_preview) or whose preview failed shows its name in the app's font with "No preview", and is not asked again until the text changes.
// The sample follows the family's main script (Japanese for Noto Sans JP, Arabic for Cairo) and a script the page has no sample of shows the family's
// name (Sunflower, Hangul). Past 150 faces the oldest are taken out of document.fonts. Closing the sheet drops what is still on its way.
// Mock bridge: lib/fontmaker_mock.js (opts.gf.hold: previews answer when the script says); the list: fixtures/gfonts-catalog.json.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));
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
    key: pv.dataset.pvk, size: getComputedStyle(pv).fontSize, noTr: pv.getAttribute('translate') };
}, f);
// answer every preview on its way, and the ones asked after those, until none is left
const drain = async (page) => { for (let i = 0; i < 40; i++) { const n = await page.evaluate(() => __fmk.gfPending().length); if (!n) break; await page.evaluate(() => __fmk.gfAnswerAll()); await page.waitForTimeout(120); } await page.waitForTimeout(150); };
// text typed in the preview field (the cards wait 400 ms for it)
const typeText = async (page, t, wait) => { await page.evaluate((t) => { const el = document.getElementById('gfText'); el.value = t; el.dispatchEvent(new Event('input', { bubbles: true })); }, t); if (wait !== false) await page.waitForTimeout(500); };
const scrollTo = async (page, f) => { await page.evaluate((f) => { const c = document.querySelector('#gfList .gf-card[data-f="' + f + '"]'), sc = document.getElementById('gfScroll'); sc.scrollTop += c.getBoundingClientRect().top - sc.getBoundingClientRect().top - 60; }, f); await page.waitForTimeout(250); };

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
    check('   a card shows the new text at once, in the face it had until its new one comes', rH.text === 'Hello' && /gfpv/.test(rH.ff), JSON.stringify(rH));
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
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, previewError: ['Inter'], previewFail: ['Open Sans'] } });
    await page.waitForTimeout(600);
    const inter = await card(page, 'Inter'), os = await card(page, 'Open Sans'), rob = await card(page, 'Roboto');
    check('9. no preview (gf_no_preview): the name in the app font and "No preview"', inter.failed && !inter.wait && inter.ff === '' && inter.text === 'InterNo preview' && inter.nopv === 'No preview', JSON.stringify(inter));
    check('   a failed preview (network): the same', os.failed && os.text === 'Open SansNo preview', JSON.stringify(os));
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
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
