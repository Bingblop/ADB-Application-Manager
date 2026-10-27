// v7.18.0 Google Fonts, the sheet: the Custom Fonts tab's top card has a search-field button "Search Google Fonts" with the count of families, and
// Make a font's empty Regular slot a "Find one on Google Fonts" link; both open the sheet #gfSheet. Its bar has the title, Close and the search; the
// list view has the category, language and sort selects, the Variable and Downloaded chips (aria-pressed), the status line ("List updated
// Oct 1, 2026 · Refresh"), the count, 30 cards and "Show N more · M left", and the credit. The search ignores case and accents (ł, ø, ß and the other
// letters NFD leaves whole as their plain ones: "lukasz" finds Łukasz Dziedzic), looks at the name and the designers, every word must match, and
// waits 120 ms; the sorts are Popular, Trending, Newest and Name (A–Z); an unknown subset is shown title-cased ("Canadian Aboriginal"); no match
// shows an empty state whose "Clear filters" brings every family back. No list on the phone: the sheet
// asks for one (gfontsRefresh), says it is getting it, then shows it; a failure, and another window of the app getting it (busy), is an empty
// state with Retry. A stale list stays in use while it updates, and a failed update says why beside Retry. A list that grows older than 7 days
// while the page stays open is updated the next time the sheet opens; one another window of the app got meanwhile (gfontsRefresh answers fresh) is
// read again, its date and count shown, without the busy sentence. Back (backNavigate) and a tap outside
// step from a family's page to the list, then close. An older app build (no gfontsCatalog) has no button and no link. No horizontal scroll at
// 360 px, in the light theme and right-to-left.
// The chips are outlined like the app's other chips (filled only when pressed), a card's badges stand apart, a new search starts at the top of its
// results, and the sheet's handle pulls it up to the whole screen (down once: its size again; down again: the usual step back). The Help Guide
// says what the page shows: its examples of the count are ones the page can show, and its privacy row sends the typed preview text, not the search.
// Mock bridge: lib/fontmaker_mock.js (opts.gf); the list: fixtures/gfonts-catalog.json (64 real families).
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));

async function open(b, opts, width) {
  const ctx = await b.newContext({ viewport: { width: width || 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const T = (s) => String(s || '').replace(/\s+/g, ' ').trim();
const sheet = (page) => page.evaluate(() => {
  const t = id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; };
  const vis = id => { const e = document.getElementById(id); return !!e && getComputedStyle(e).display !== 'none' && !!e.getClientRects().length; };
  return {
    open: document.getElementById('gfSheet').classList.contains('show'), title: t('gfTitle'), back: vis('gfBack'), search: vis('gfSearch'),
    list: vis('gfListView'), detail: vis('gfDetail'), status: t('gfStatus'), count: t('gfCount'), more: t('gfMore'),
    cards: [...document.querySelectorAll('#gfList .gf-card')].map(c => c.dataset.f), empty: t('gfList'),
    calls: __fmk.calls.filter(c => /^gfontsRefresh/.test(c)),
  };
});
// the search as typed: the list waits 120 ms
const search = async (page, q) => {
  const now = await page.evaluate((v) => { const s = document.getElementById('gfSearch'); s.value = v; s.dispatchEvent(new Event('input', { bubbles: true })); return document.querySelectorAll('#gfList .gf-card').length; }, q);
  await page.waitForTimeout(250);
  return now;
};
const pick = async (page, id, v) => { await page.selectOption('#' + id, v); await page.waitForTimeout(100); };
const wide = (page) => page.evaluate(() => {
  const cw = document.documentElement.clientWidth, sc = document.getElementById('gfScroll');
  return { scrollW: document.documentElement.scrollWidth, cw, sheetW: sc.scrollWidth, sheetCw: sc.clientWidth,
    out: [...document.querySelectorAll('#gfSheet *')].filter(e => { const r = e.getBoundingClientRect(); return r.width && (r.right > cw + 0.5 || r.left < -0.5); }).map(e => e.id || e.className).slice(0, 5) };
});

(async () => {
  const b = await chromium.launch();
  // a list on the phone (fresh), Lato and Roboto downloaded
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato', 'Roboto'] } });
    const entry = await page.evaluate(() => ({ btn: getComputedStyle(document.getElementById('gfOpenBtn')).display !== 'none', text: document.getElementById('gfOpenBtn').textContent.replace(/\s+/g, ' ').trim(),
      link: document.getElementById('gfFindLink') ? document.getElementById('gfFindLink').textContent : null, inReg: !!document.querySelector('#fntRegRow #gfFindLink'),
      primaries: document.querySelectorAll('#view-fonts .mode-action-btn.primary').length }));
    check('1. the top card: "Search Google Fonts" with the count of families', entry.btn && entry.text === 'Search Google Fonts64 families', JSON.stringify(entry));
    check('   Make a font: "Find one on Google Fonts" under the empty Regular slot (not inside its row)', entry.link === 'Find one on Google Fonts' && !entry.inReg, JSON.stringify(entry));
    check('   still one primary button in the tab', entry.primaries === 1, entry.primaries);
    await page.click('#gfOpenBtn');
    await page.waitForTimeout(300);
    let s = await sheet(page);
    check('2. the sheet opens on the list: title "Google Fonts", search shown, no Back', s.open && s.title === 'Google Fonts' && s.search && !s.back && s.list && !s.detail, JSON.stringify(s));
    check('   status: "List updated Oct 1, 2026 · Refresh"', s.status === 'List updated Oct 1, 2026 · Refresh', s.status);
    check('   a fresh list is not asked for again', !s.calls.length, s.calls.join(','));
    check('   count "64 families", 30 cards, "Show 30 more · 34 left"', s.count === '64 families' && s.cards.length === 30 && s.more === 'Show 30 more · 34 left', JSON.stringify([s.count, s.cards.length, s.more]));
    check('   Popular first: Roboto, Open Sans, Google Sans, Inter, Montserrat', s.cards.slice(0, 5).join(',') === 'Roboto,Open Sans,Google Sans,Inter,Montserrat', s.cards.slice(0, 5).join(','));
    const bits = await page.evaluate(() => {
      const card = f => { const c = document.querySelector('#gfList .gf-card[data-f="' + f + '"]'); return c ? { name: c.querySelector('.gf-name').textContent, meta: c.querySelector('.gf-meta').textContent,
        badges: [...c.querySelectorAll('.uad-badge')].map(x => x.textContent), tag: c.tagName, noTr: c.querySelector('.gf-name').getAttribute('translate') } : null; };
      return { lato: card('Lato'), roboto: card('Roboto'), cairo: card('Cairo'), credit: document.querySelector('#gfListView .uad-credit').textContent.trim(),
        aria: [...document.querySelectorAll('#gfChipVar, #gfChipDl')].map(c => c.tagName + ':' + c.getAttribute('aria-pressed')).join(','),
        size: [document.getElementById('gfSize').min, document.getElementById('gfSize').max, document.getElementById('gfSize').value, document.getElementById('gfSizeV').textContent].join(','),
        ph: [document.getElementById('gfSearch').placeholder, document.getElementById('gfText').placeholder].join(' | '),
        rgap: (rb => { const bs = rb.querySelectorAll('.uad-badge'); return { disp: getComputedStyle(rb).display, gap: Math.round(bs[1].getBoundingClientRect().left - bs[0].getBoundingClientRect().right) }; })(document.querySelector('#gfList .gf-card[data-f="Roboto"] .gf-badges')),
        chipBg: [...document.querySelectorAll('#gfChipVar, #gfChipDl')].map(c => getComputedStyle(c).backgroundColor).join(',') };
    });
    check('3. a card is one button: Lato "Sans serif · Łukasz Dziedzic · 10 styles", Downloaded', bits.lato && bits.lato.tag === 'BUTTON' && bits.lato.meta === 'Sans serif · Łukasz Dziedzic · 10 styles' && bits.lato.badges.join(',') === 'Downloaded' && bits.lato.noTr === 'no', JSON.stringify(bits.lato));
    check('   Roboto "Sans serif · Christian Robertson (+2) · 18 styles", Variable and Downloaded', bits.roboto && bits.roboto.meta === 'Sans serif · Christian Robertson (+2) · 18 styles' && bits.roboto.badges.join(',') === 'Variable,Downloaded', JSON.stringify(bits.roboto));
    check('   the credit names Google Fonts and the licences', bits.credit === 'Fonts and their list come from Google Fonts (fonts.google.com). Each family is free to use under its own licence, shown with it.', bits.credit);
    check('   the chips are buttons, not pressed', bits.aria === 'BUTTON:false,BUTTON:false', bits.aria);
    check('   the chips are outlined, not filled (no grey button fill)', bits.chipBg === 'rgba(0, 0, 0, 0),rgba(0, 0, 0, 0)', bits.chipBg);
    check('   Roboto\'s badges are a row 4 px apart', bits.rgap.disp === 'flex' && bits.rgap.gap === 4, JSON.stringify(bits.rgap));
    check('   the preview size: 14-72, 28', bits.size === '14,72,28,28', bits.size);
    check('   placeholders', bits.ph === 'Search fonts or designers | Type something to preview', bits.ph);
    // Show more
    await page.click('#gfMoreBtn'); await page.waitForTimeout(100);
    s = await sheet(page);
    check('4. Show more: 60 cards, "Show 4 more · 4 left"', s.cards.length === 60 && s.more === 'Show 4 more · 4 left', JSON.stringify([s.cards.length, s.more]));
    await page.click('#gfMoreBtn'); await page.waitForTimeout(100);
    s = await sheet(page);
    check('   then all 64, no more button; the family without popularity comes last', s.cards.length === 64 && s.more === '' && s.cards[63] === 'Material Symbols Outlined', JSON.stringify([s.cards.length, s.more, s.cards[63]]));
    // a search typed far down the list starts at the top of its results (the search field is in the sticky bar)
    const from = await page.evaluate(() => { const sc = document.getElementById('gfScroll'); sc.scrollTop = sc.scrollHeight; return sc.scrollTop; });
    await search(page, 'sans');
    const sr = await page.evaluate(() => {
      const sc = document.getElementById('gfScroll'), first = document.querySelector('#gfList .gf-card'), bar = document.querySelector('#gfSheet .gf-bar').getBoundingClientRect(), r = first.getBoundingClientRect();
      return { top: sc.scrollTop, first: first.dataset.f, inView: r.top >= bar.bottom - 0.5 && r.top < sc.getBoundingClientRect().bottom };
    });
    check('   a new search far down the list starts at the top: Open Sans in view under the bar', from > 2000 && sr.top === 0 && sr.first === 'Open Sans' && sr.inView, JSON.stringify([from, sr]));
    await search(page, '');
    // sorts
    const firsts = {};
    for (const v of ['trend', 'new', 'name', 'pop']) { await pick(page, 'gfSort', v); firsts[v] = (await sheet(page)).cards.slice(0, 3).join(','); }
    check('5. Trending: Noto Sans Georgian, Arimo, Rajdhani', firsts.trend === 'Noto Sans Georgian,Arimo,Rajdhani', firsts.trend);
    check('   Newest: Arimo, Google Sans, Roboto Mono', firsts.new === 'Arimo,Google Sans,Roboto Mono', firsts.new);
    check('   Name (A–Z): Abhaya Libre, Arimo, Bebas Neue', firsts.name === 'Abhaya Libre,Arimo,Bebas Neue', firsts.name);
    check('   a new sort starts again at 30 cards', (await sheet(page)).cards.length === 30);
    // search
    const before = await search(page, 'ROBOTO slab');
    s = await sheet(page);
    check('6. the search waits (120 ms) before the list changes', before === 30, before);
    check('   "ROBOTO slab": every word, any case: Roboto Slab, "1 of 64"', s.cards.join(',') === 'Roboto Slab' && s.count === '1 of 64', JSON.stringify([s.cards, s.count]));
    await search(page, 'adele');
    s = await sheet(page);
    check('   "adele" finds Buda by its designer Adèle Antignac (accents aside)', s.cards.join(',') === 'Buda', s.cards.join(','));
    await search(page, 'lukasz dziedzic');
    s = await sheet(page);
    check('   "lukasz dziedzic" finds Lato by Łukasz Dziedzic (ł as l)', s.cards.join(',') === 'Lato', s.cards.join(','));
    await search(page, 'sorensen');
    s = await sheet(page);
    check('   "sorensen" finds Playfair Display by Claus Eggers Sørensen (ø as o)', s.cards.join(',') === 'Playfair Display', s.cards.join(','));
    await search(page, 'ŁUKASZ');
    s = await sheet(page);
    check('   "ŁUKASZ" still finds Lato', s.cards.join(',') === 'Lato', s.cards.join(','));
    const fold = await page.evaluate(() => ['Grießhammer', 'Bæck', 'Duru Sans ı', 'ẞ Œuvre Þorn Đorđe'].map(gfFold).join('|'));
    check('   the other letters NFD leaves whole: ß ss, æ ae, ı i, ẞ ss, œ oe, þ th, đ d', fold === 'griesshammer|baeck|duru sans i|ss oeuvre thorn dorde', fold);
    await search(page, 'christian robertson');
    s = await sheet(page);
    check('   "christian robertson": his four families', s.cards.join(',') === 'Roboto,Roboto Mono,Roboto Condensed,Roboto Slab' && s.count === '4 of 64', JSON.stringify([s.cards, s.count]));
    await search(page, '');
    // category, language
    await pick(page, 'gfCat', 'mono');
    s = await sheet(page);
    check('7. Monospace: four families', s.cards.join(',') === 'Roboto Mono,JetBrains Mono,Inconsolata,Source Code Pro' && s.count === '4 of 64', JSON.stringify([s.cards, s.count]));
    await pick(page, 'gfCat', '');
    const langs = await page.evaluate(() => [...document.getElementById('gfLang').options].map(o => o.value + '=' + o.textContent));
    check('   languages: All languages first, then the subsets by name', langs[0] === '=All languages' && langs.includes('canadian-aboriginal=Canadian Aboriginal') && langs.includes('japanese=Japanese')
      && langs.includes('chinese-simplified=Chinese (Simplified)') && langs.includes('emoji=Emoji') && langs.slice(1).map(x => x.split('=')[1]).join('|') === langs.slice(1).map(x => x.split('=')[1]).sort((a, b) => a.localeCompare(b, 'en')).join('|'), langs.join(' | '));
    await pick(page, 'gfLang', 'canadian-aboriginal');
    s = await sheet(page);
    check('   Canadian Aboriginal: Google Sans', s.cards.join(',') === 'Google Sans', s.cards.join(','));
    await pick(page, 'gfLang', 'japanese');
    s = await sheet(page);
    check('   Japanese: Noto Sans JP', s.cards.join(',') === 'Noto Sans JP', s.cards.join(','));
    await pick(page, 'gfLang', '');
    // chips
    await page.click('#gfChipVar'); await page.waitForTimeout(100);
    s = await sheet(page);
    const nVar = CAT.families.filter(e => (e.x || []).length).length;
    const ap = () => page.evaluate(() => [...document.querySelectorAll('#gfChipVar, #gfChipDl')].map(c => c.getAttribute('aria-pressed') + (c.classList.contains('active') ? '+' : '')).join(','));
    check('8. Variable: only variable families (' + nVar + '), the chip pressed', s.count === nVar + ' of 64' && (await ap()) === 'true+,false', JSON.stringify([s.count, await ap()]));
    const pbg = await page.evaluate(() => {
      const t = document.createElement('button'); t.className = 'filter-chip active'; document.body.appendChild(t);
      const want = getComputedStyle(t).backgroundColor; t.remove();
      return { on: getComputedStyle(document.getElementById('gfChipVar')).backgroundColor, want, off: getComputedStyle(document.getElementById('gfChipDl')).backgroundColor };
    });
    check('   the pressed chip has the app\'s pressed fill, the other none', pbg.on === pbg.want && pbg.on !== 'rgba(0, 0, 0, 0)' && pbg.off === 'rgba(0, 0, 0, 0)', JSON.stringify(pbg));
    await page.click('#gfChipDl'); await page.waitForTimeout(100);
    s = await sheet(page);
    check('   and Downloaded: Roboto', s.cards.join(',') === 'Roboto' && (await ap()) === 'true+,true+', JSON.stringify([s.cards, await ap()]));
    await page.click('#gfChipVar'); await page.waitForTimeout(100);
    s = await sheet(page);
    check('   Downloaded alone: Roboto and Lato', s.cards.join(',') === 'Roboto,Lato', s.cards.join(','));
    await search(page, 'lato zzz');
    s = await sheet(page);
    const es = await page.evaluate(() => { const e = document.querySelector('#gfList .empty-state, #gfList [class*="empty"]'); const btn = [...document.querySelectorAll('#gfList button')].map(x => x.textContent.trim()); return { has: !!e, btn }; });
    check('9. no match: "No fonts match." with Clear filters, "0 of 64", no more button', /No fonts match\./.test(s.empty) && es.btn.includes('Clear filters') && s.count === '0 of 64' && s.more === '', JSON.stringify([s.empty, es, s.count]));
    await page.evaluate(() => [...document.querySelectorAll('#gfList button')].find(x => x.textContent.trim() === 'Clear filters').click());
    await page.waitForTimeout(100);
    s = await sheet(page);
    const cleared = await page.evaluate(() => ({ q: document.getElementById('gfSearch').value, c: document.getElementById('gfCat').value, l: document.getElementById('gfLang').value, sort: document.getElementById('gfSort').value }));
    check('   Clear filters: every family again, search and chips cleared (the sort kept)', s.count === '64 families' && s.cards.length === 30 && (await ap()) === 'false,false' && cleared.q === '' && cleared.c === '' && cleared.l === '' && cleared.sort === 'pop', JSON.stringify([s.count, await ap(), cleared]));
    // a family's page, and Back
    await page.click('#gfList .gf-card[data-f="Lato"]');
    await page.waitForTimeout(200);
    s = await sheet(page);
    check('10. a card opens the family: title Lato (translate="no"), Back shown, no search', s.title === 'Lato' && s.back && !s.search && !s.list && s.detail
      && (await page.evaluate(() => document.getElementById('gfTitle').getAttribute('translate'))) === 'no', JSON.stringify(s));
    await page.evaluate(() => backNavigate()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   Back: the list again, still open', s.open && s.list && !s.detail && s.title === 'Google Fonts' && s.search && !s.back, JSON.stringify(s));
    await page.evaluate(() => backNavigate()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   Back again: the sheet closes, the tab stays', !s.open && (await page.evaluate(() => currentViewName())) === 'fonts', JSON.stringify(s));
    await page.click('#gfFindLink'); await page.waitForTimeout(200);
    s = await sheet(page);
    check('11. the Make a font link opens the sheet on the list', s.open && s.list && s.cards.length === 30, JSON.stringify([s.open, s.cards.length]));
    await page.click('#gfList .gf-card[data-f="Roboto"]'); await page.waitForTimeout(150);
    await page.mouse.click(200, 10); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   a tap outside the sheet: from the family to the list', s.open && s.list && !s.detail, JSON.stringify(s));
    await page.mouse.click(200, 10); await page.waitForTimeout(150);
    check('   and then closed', !(await sheet(page)).open);
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    await page.click('#gfList .gf-card[data-f="Roboto"]'); await page.waitForTimeout(150);
    await page.click('#gfSheet .gf-bar .sheet-close[aria-label="Close"]'); await page.waitForTimeout(150);
    check('   Close shuts the sheet from a family\'s page', !(await sheet(page)).open);
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   opened again: the list, at the top', s.open && s.list && !s.detail && (await page.evaluate(() => document.getElementById('gfScroll').scrollTop)) === 0, JSON.stringify(s));
    // the handle: pulled up, the whole screen; down once, its size again; down again, the usual step back (the list closes, a family's page goes back to the list)
    const hold = () => page.evaluate(() => { const r = document.getElementById('gfScroll').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + 6 }; });
    const drag = async (dy) => { const p = await hold(); await page.mouse.move(p.x, p.y); await page.mouse.down(); await page.mouse.move(p.x, p.y + dy / 2, { steps: 4 }); await page.mouse.move(p.x, p.y + dy, { steps: 4 }); await page.mouse.up(); await page.waitForTimeout(350); };
    const tall = () => page.evaluate(() => { const sc = document.getElementById('gfScroll'); return { h: Math.round(sc.getBoundingClientRect().height), full: innerHeight - 14, part: Math.round(innerHeight * 0.85), up: sc.classList.contains('sheet-expanded'), open: document.getElementById('gfSheet').classList.contains('show'), view: gf.view }; });
    const h0 = await tall();
    await drag(-140);
    const h1 = await tall();
    check('   the handle pulled up: the sheet takes the whole screen', h0.h === h0.part && h1.up && Math.abs(h1.h - h1.full) <= 2, JSON.stringify([h0, h1]));
    await drag(140);
    const h2 = await tall();
    check('   pulled down once: its size again, still open on the list', !h2.up && h2.h === h2.part && h2.open && h2.view === 'list', JSON.stringify(h2));
    await page.click('#gfList .gf-card[data-f="Roboto"]'); await page.waitForTimeout(150);
    await drag(-140); await drag(140); await drag(140);
    const h3 = await tall();
    check('   from a family\'s page: up, down, down again goes back to the list', !h3.up && h3.open && h3.view === 'list', JSON.stringify(h3));
    await drag(140);
    check('   and down from the list closes the sheet', !(await tall()).open);
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    const w = await wide(page);
    check('12. dark, 412 px: nothing wider than the screen', w.scrollW <= w.cw && w.sheetW <= w.sheetCw && !w.out.length, JSON.stringify(w));
    await page.screenshot({ path: 'list-412.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // no list on the phone: asked for, "Getting the list…", then shown
  {
    const { ctx, page, errors } = await open(b, { gf: { remote: CAT, hold: true } });
    let e = await page.evaluate(() => ({ btn: getComputedStyle(document.getElementById('gfOpenBtn')).display !== 'none', n: document.getElementById('gfOpenCount').textContent }));
    check('13. no list yet: the button shows without a count', e.btn && e.n === '', JSON.stringify(e));
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    let s = await sheet(page);
    check('   opening asks for it (gfontsRefresh false) and says so; no cards, no count', s.calls.join(',') === 'gfontsRefresh:false' && s.status === 'Getting the list of fonts from Google Fonts…' && !s.cards.length && s.count === '', JSON.stringify(s));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(200);
    s = await sheet(page);
    e = await page.evaluate(() => document.getElementById('gfOpenCount').textContent);
    check('   the list comes: status, 30 cards, the button counts 64', s.status === 'List updated Oct 1, 2026 · Refresh' && s.cards.length === 30 && e === '64 families', JSON.stringify([s.status, s.cards.length, e]));
    await page.click('#gfRefreshBtn'); await page.waitForTimeout(100);
    s = await sheet(page);
    check('   Refresh: gfontsRefresh true, "Updating the list…" while the list stays', s.calls.slice(-1)[0] === 'gfontsRefresh:true' && s.status === 'List updated Oct 1, 2026 · Updating the list…' && s.cards.length === 30, JSON.stringify([s.calls, s.status]));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    check('   and back to Refresh', (await sheet(page)).status === 'List updated Oct 1, 2026 · Refresh');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // no list, and the refresh fails: an empty state with the reason and Retry
  {
    const { ctx, page, errors } = await open(b, { gf: { remote: CAT, hold: true, refreshError: { code: 'network', error: 'The download failed: timeout' } } });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(100);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    let s = await sheet(page);
    const btns = () => page.evaluate(() => [...document.querySelectorAll('#gfStatus button')].map(x => x.textContent.trim()));
    check('14. a failed first list: "The list of fonts is not on this phone yet.", the reason, Retry', /The list of fonts is not on this phone yet\./.test(s.status) && /The download failed \(timeout\)\. Check the connection and try again\./.test(s.status)
      && (await btns()).join(',') === 'Retry' && !s.cards.length, JSON.stringify([s.status, await btns()]));
    await page.evaluate(() => { __fmk.gfRefreshError = null; [...document.querySelectorAll('#gfStatus button')].find(x => x.textContent.trim() === 'Retry').click(); });
    await page.waitForTimeout(100);
    s = await sheet(page);
    check('   Retry asks again (gfontsRefresh true)', s.calls.join(',') === 'gfontsRefresh:false,gfontsRefresh:true' && s.status === 'Getting the list of fonts from Google Fonts…', JSON.stringify([s.calls, s.status]));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   and the list comes', s.cards.length === 30 && s.count === '64 families', JSON.stringify([s.cards.length, s.count]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // no list, and another window of the app is getting it: the bridge answers busy (that job's answer goes to that window only); the sheet says so
  // with Retry instead of waiting for an answer that never comes, and Retry asks again
  {
    const { ctx, page, errors } = await open(b, { gf: { remote: CAT, hold: true, otherWindow: true } });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    let s = await sheet(page);
    const btns = () => page.evaluate(() => [...document.querySelectorAll('#gfStatus button')].map(x => x.textContent.trim()));
    check('14b. another window is getting the list: its sentence and Retry, not "Getting the list…"', s.calls.join(',') === 'gfontsRefresh:false' && /The list of fonts is not on this phone yet\./.test(s.status)
      && /Another window of the app is getting the list of fonts\. Try again in a moment\./.test(s.status) && !/Getting the list/.test(s.status) && (await btns()).join(',') === 'Retry', JSON.stringify([s.status, s.calls, await btns()]));
    await page.evaluate(() => { __fmk.gfOtherWindow = false; [...document.querySelectorAll('#gfStatus button')].find(x => x.textContent.trim() === 'Retry').click(); });
    await page.waitForTimeout(100);
    s = await sheet(page);
    check('   once it ended, Retry asks again and this window gets the list', s.calls.join(',') === 'gfontsRefresh:false,gfontsRefresh:true' && s.status === 'Getting the list of fonts from Google Fonts…', JSON.stringify([s.calls, s.status]));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   and the list comes', s.cards.length === 30 && s.count === '64 families', JSON.stringify([s.cards.length, s.count]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a stale list: used while it updates; a failed update says why, with Retry
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, stale: true, hold: true, refreshError: { code: 'gf_bad_answer', error: 'Google Fonts sent an answer the app cannot read.' } } });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(100);
    let s = await sheet(page);
    check('15. a list older than 7 days: in use (30 cards) while it updates (gfontsRefresh false)', s.cards.length === 30 && s.calls.join(',') === 'gfontsRefresh:false' && s.status === 'List updated Oct 1, 2026 · Updating the list…', JSON.stringify([s.cards.length, s.calls, s.status]));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    s = await sheet(page);
    const st = await page.evaluate(() => ({ err: (document.querySelector('#gfStatus .fnt-err .is-err') || {}).textContent, retry: !!document.getElementById('gfRetryBtn'), refresh: !!document.getElementById('gfRefreshBtn') }));
    check('   the update fails: the reason in the page\'s words, Retry; the list stays', st.err === 'Google Fonts sent an answer the app cannot read. Try again later.' && st.retry && st.refresh && s.cards.length === 30, JSON.stringify([st, s.cards.length]));
    await page.evaluate(() => { __fmk.gfRefreshError = null; document.getElementById('gfRetryBtn').click(); });
    await page.waitForTimeout(50);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   Retry: updated, the reason gone', s.status === 'List updated Oct 1, 2026 · Refresh' && s.calls.slice(-1)[0] === 'gfontsRefresh:true', JSON.stringify([s.status, s.calls]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a list that grows older than 7 days while the page stays open (gfontsCatalog's stale was false when it was read): updated on the next open
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, remote: Object.assign({}, CAT, { at: 0 }), hold: true } });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    let s = await sheet(page);
    check('15b. a list 2 days old: not asked for again', !s.calls.length && s.status === 'List updated Oct 1, 2026 · Refresh', JSON.stringify([s.calls, s.status]));
    await page.evaluate(() => gfClose());
    await page.evaluate(() => { const D = Date.now; Date.now = () => D() + 6 * 864e5; });   // 6 days on: the same list is now 8 days old
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   6 days later, the page still open: asked for (gfontsRefresh false), in use while it updates', s.calls.join(',') === 'gfontsRefresh:false' && s.status === 'List updated Oct 1, 2026 · Updating the list…' && s.cards.length === 30, JSON.stringify([s.calls, s.status, s.cards.length]));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    s = await sheet(page);
    check('   the new list: "List updated Oct 9, 2026"', s.status === 'List updated Oct 9, 2026 · Refresh', s.status);
    await page.evaluate(() => gfClose());
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    check('   opened again: not asked for again', (await sheet(page)).calls.join(',') === 'gfontsRefresh:false');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // another window of the app gets the list while this one has a stale one: this window's ask is answered busy (that job's answer goes to that window
  // only); on the next open the bridge answers fresh, and the page reads the new list: its date, its count, no busy sentence
  {
    const REMOTE = Object.assign({}, CAT, { at: 0, families: CAT.families.filter(e => e.f !== 'Arimo') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, stale: true, remote: REMOTE, otherWindow: true, hold: true } });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    let s = await sheet(page);
    check('15c. a stale list, another window getting the list: its sentence and Retry; the list in use', s.calls.join(',') === 'gfontsRefresh:false' && /Another window of the app is getting the list of fonts\. Try again in a moment\./.test(s.status)
      && (await page.evaluate(() => !!document.getElementById('gfRetryBtn'))) && s.cards.length === 30, JSON.stringify([s.calls, s.status]));
    await page.evaluate(() => { gfClose(); __fmk.gfOtherWindowDone(); __fmk.calls.length = 0; });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    s = await sheet(page);
    const now = await page.evaluate(() => ({ calls: __fmk.calls.filter(c => /^gfonts(Refresh|Catalog)/.test(c)).join(','), btn: document.getElementById('gfOpenCount').textContent, err: !!document.querySelector('#gfStatus .fnt-err'),
      retry: !!document.getElementById('gfRetryBtn'), arimo: !!document.querySelector('#gfList .gf-card[data-f="Arimo"]') }));
    check('   its list is on the phone: opened again, the bridge says fresh and the page reads it (no job)', now.calls === 'gfontsRefresh:false,gfontsCatalog' && !(await page.evaluate(() => gf.refreshing)), JSON.stringify(now));
    check('   "List updated Oct 3, 2026 · Refresh", no busy sentence, no Retry', s.status === 'List updated Oct 3, 2026 · Refresh' && !now.err && !now.retry, JSON.stringify([s.status, now]));
    check('   the new list\'s count on the button and the sheet: "63 families"; Arimo gone', now.btn === '63 families' && s.count === '63 families' && !now.arimo, JSON.stringify([now, s.count]));
    await page.evaluate(() => { gfClose(); __fmk.calls.length = 0; });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(150);
    check('   opened once more: not asked for again', !(await sheet(page)).calls.length);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // an older app build: nothing of it shows
  {
    const all = ['gfontsCatalog', 'gfontsRefresh', 'gfontsPreview', 'gfontsInfo', 'gfontsDownloads', 'gfontsDownload', 'gfontsCancel', 'gfontsDelete', 'gfontsUse', 'gfontsSave', 'emojiSpecimenFont', 'emojiSpecimenCached', 'emojiSpecimenFetch'];
    const { ctx, page, errors } = await open(b, { missing: all });
    const e = await page.evaluate(() => ({ btn: getComputedStyle(document.getElementById('gfOpenBtn')).display, link: !!document.getElementById('gfFindLink'), reg: document.getElementById('fntRegRow').textContent.replace(/\s+/g, ' ').trim() }));
    check('16. an older build: no button, no link; the Regular row as before', e.btn === 'none' && !e.link && /^Regular/.test(e.reg) && /Choose$/.test(e.reg), JSON.stringify(e));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the light theme at 360 px, and right to left
  for (const [dark, rtl] of [[false, false], [true, true]]) {
    const { ctx, page, errors } = await open(b, { dark, gf: { catalog: CAT, downloads: ['Lato'] } }, 360);
    if (rtl) await page.evaluate(() => document.documentElement.setAttribute('dir', 'rtl'));
    await page.click('#gfOpenBtn'); await page.waitForTimeout(300);
    let w = await wide(page);
    const name = (dark ? 'dark' : 'light') + '-360' + (rtl ? '-rtl' : '');
    check('17. ' + name + ': the list, nothing wider than the screen', w.scrollW <= w.cw && w.sheetW <= w.sheetCw && !w.out.length, JSON.stringify(w));
    const cbg = await page.evaluate(() => [...document.querySelectorAll('#gfChipVar, #gfChipDl')].map(c => getComputedStyle(c).backgroundColor).join(','));
    check('   the chips are outlined, not filled', cbg === 'rgba(0, 0, 0, 0),rgba(0, 0, 0, 0)', cbg);
    await page.screenshot({ path: name + '-list.png' });
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(300);
    w = await wide(page);
    check('   a family\'s page, nothing wider than the screen', w.scrollW <= w.cw && w.sheetW <= w.sheetCw && !w.out.length, JSON.stringify(w));
    if (rtl) {
      const flip = await page.evaluate(() => getComputedStyle(document.querySelector('#gfBack svg')).transform);
      check('   right to left: the Back arrow points the other way', flip && flip !== 'none', flip);
    } else {
      const bg = await page.evaluate(() => getComputedStyle(document.getElementById('gfScroll')).backgroundColor);
      check('   the light theme: a light sheet', (() => { const m = /(\d+), (\d+), (\d+)/.exec(bg); return m && (+m[1] + +m[2] + +m[3]) / 3 > 160; })(), bg);
    }
    await page.screenshot({ path: name + '-family.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the Help Guide (assets/guide.js): the count line's examples are what gfRenderList writes (the whole list, or "x of y"), and the privacy row says the
  // text typed to preview goes to Google Fonts while the search words stay on the phone
  {
    const gsb = { window: {} }; require('vm').runInNewContext(fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8'), gsb);
    const flat = t => String(t || '').replace(/<[^>]+>/g, '').replace(/&quot;/g, '"').replace(/\s+/g, ' ');
    const sec = id => flat((((gsb.window.HELP_GUIDE || {}).sections || []).find(x => x.id === id) || {}).html);
    const fg = sec('fonts-google'), cnt = (/The line above the list counts what is shown[^.]*\./.exec(fg) || [''])[0];
    const ex = (cnt.match(/"[^"]+"/g) || []).map(q => q.slice(1, -1));
    const total = '1,950', pageCan = q => q === total + ' families' || new RegExp('^\\d[\\d,]* of ' + total + '$').test(q);
    check('18. the guide\'s count examples are ones the page shows for a list of ' + total, ex.length === 2 && ex.every(pageCan) && fg.includes('Search Google Fonts') && fg.includes('"' + total + ' families"'), JSON.stringify(ex));
    const row = (sec('privacy').split(/(?=Google Fonts \(fonts\.google\.com)/)[1] || '').split(/(?=GitHub's gist)/)[0];
    check('   the privacy row: the text typed in Type something to preview and Type tester goes out; the search words stay',
      /Type something to preview/.test(row) && /Type tester/.test(row) && /What you type in Search fonts or designers, and the filters, stay on the phone/.test(row) && !/Your search words/.test(row), row);
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
