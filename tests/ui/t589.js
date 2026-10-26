// v7.17.0 Custom Fonts, Emoji fonts: the five catalog rows (Noto, Twemoji, Blobmoji, OpenMoji, iOS) with version, format, size, license and the
// License / Source links (through the app's external-link path, openUrl). Download shows a bar and "x of y" as the app reports it (onEmojiProgress),
// Cancel stops it, Done turns the button into Delete and the font becomes a choice under Make a font (Emoji) and System emoji. A checksum that differs
// is said in the page's words. The iOS row says it is Apple's art from a third-party build, only the phone's system emoji with root until the next
// restart and never inside a font, and it is never offered for a font. On Android 12 (API 32) the COLRv1 rows say they need Android 13 to show inside a
// font and are not offered for a font; Twemoji (COLRv0) is.
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
  await page.evaluate(() => window.__fmk.stepAll());
  await page.waitForTimeout(100);
  return { ctx, page, errors };
}
const row = (page, id) => page.evaluate(id => {
  const r = document.getElementById('fntEmo_' + id);
  if (!r) return null;
  const bar = r.querySelector('.agent-ask-bar'), fill = bar && bar.querySelector('i');
  return { text: r.textContent.replace(/\s+/g, ' ').trim(), btn: r.querySelector('.rf-row .op-mode-btn').textContent, notes: [...r.querySelectorAll('.fnt-note')].map(n => n.textContent),
    links: [...r.querySelectorAll('.fnt-links a')].map(a => a.textContent), bar: !!bar, indet: bar ? bar.classList.contains('indet') : null, width: fill ? fill.style.width : '',
    prog: (r.querySelector('.agent-ask-prog-txt') || {}).textContent || '', err: (r.querySelector('.is-err') || {}).textContent || '' };
}, id);
const opts = (page, id) => page.evaluate(id => [...document.getElementById(id).options].map(o => o.value), id);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, {});
    const noto = await row(page, 'noto'), tw = await row(page, 'twemoji'), ap = await row(page, 'apple');
    check('1. Noto: version, COLRv1, size, OFL-1.1, License and Source links, Download', /Noto Color Emoji/.test(noto.text) && /Unicode 18 · COLRv1 · 4\.8 MB/.test(noto.text) && /OFL-1\.1/.test(noto.text) && noto.links.join() === 'License,Source' && noto.btn === 'Download', JSON.stringify(noto));
    check('   Twemoji is on the phone: Downloaded, Delete', /Downloaded/.test(tw.text) && tw.btn === 'Delete', JSON.stringify(tw));
    check('   iOS: Apple\'s art from a third-party build, system emoji with root until restart, never inside a font',
      ap.notes.length === 1 && ap.notes[0] === "Apple's emoji art from a third-party build. Used only as the phone's system emoji with root, until the next restart; never inside a font." && /CBDT · 41\.8 MB/.test(ap.text), JSON.stringify(ap));
    check('   on Android 16 no row says it needs Android 13', !noto.notes.length && !tw.notes.length, JSON.stringify([noto.notes, tw.notes]));
    // the links go out through the app (openUrl), not a page inside the WebView
    await page.click('#fntEmo_noto .fnt-links a:nth-of-type(1)');
    await page.click('#fntEmo_noto .fnt-links a:nth-of-type(2)');
    const urls = await page.evaluate(() => window.__fmk.calls.filter(c => /^openUrl:/.test(c)));
    check('2. License and Source open through the app (openUrl)', urls.join(' ') === 'openUrl:https://github.com/googlefonts/noto-emoji/blob/v2026-09-24-unicode18_0/2D/fonts/LICENSE openUrl:https://github.com/googlefonts/noto-emoji', urls.join(' '));
    // ---- a download ----
    await page.click('#fntEmo_noto .op-mode-btn');
    await page.waitForTimeout(100);
    const d0 = await row(page, 'noto');
    check('3. Download: Cancel, an open-ended bar and "Downloading…" until the size is known', d0.btn === 'Cancel' && d0.bar && d0.indet && d0.prog === 'Downloading…', JSON.stringify(d0));
    await page.evaluate(() => window.__fmk.step(2));
    await page.waitForTimeout(50);
    const d1 = await row(page, 'noto');
    check('   progress: 45% of the bar, "2.2 MB of 4.8 MB"', !d1.indet && d1.width === '45%' && d1.prog === '2.2 MB of 4.8 MB', JSON.stringify(d1));
    await page.screenshot({ path: 'downloading.png', clip: await page.evaluate(() => { const r = document.getElementById('fntEmojiCard').getBoundingClientRect(); return { x: 0, y: Math.max(0, r.top), width: 412, height: Math.min(560, r.height) }; }) }).catch(() => {});
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(150);
    const d2 = await row(page, 'noto');
    check('4. done: Downloaded, Delete, no bar; a toast names it', /Downloaded/.test(d2.text) && d2.btn === 'Delete' && !d2.bar && await page.evaluate(() => document.getElementById('toastMsg').textContent) === 'Noto Color Emoji downloaded', JSON.stringify(d2));
    check('   Make a font offers it (Twemoji and Noto; never iOS)', (await opts(page, 'fntEmojiSel')).join() === 'none,cat:noto,cat:twemoji,file', (await opts(page, 'fntEmojiSel')).join());
    check('   System emoji offers it too', (await opts(page, 'fntSysSel')).join() === 'noto,twemoji', (await opts(page, 'fntSysSel')).join());
    // ---- Cancel ----
    await page.click('#fntEmo_openmoji .op-mode-btn');
    await page.evaluate(() => window.__fmk.step());
    await page.click('#fntEmo_openmoji .op-mode-btn');
    await page.waitForTimeout(50);
    const c1 = await row(page, 'openmoji');
    check('5. Cancel: emojiCancel, "Cancelling…"', c1.prog === 'Cancelling…' && await page.evaluate(() => window.__fmk.calls.includes('emojiCancel:openmoji')), JSON.stringify(c1));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    const c2 = await row(page, 'openmoji');
    check('   the app\'s "cancelled": Download again, no error, not downloaded', c2.btn === 'Download' && !c2.bar && !c2.err && !/Downloaded/.test(c2.text), JSON.stringify(c2));
    // ---- Delete ----
    await page.click('#fntEmo_twemoji .op-mode-btn');
    await page.waitForTimeout(100);
    const t2 = await row(page, 'twemoji');
    check('6. Delete: emojiDelete, Download again, gone from both choices', t2.btn === 'Download' && (await opts(page, 'fntEmojiSel')).join() === 'none,cat:noto,file' && (await opts(page, 'fntSysSel')).join() === 'noto', JSON.stringify([t2.btn, await opts(page, 'fntEmojiSel')]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- a checksum that differs ----
  {
    const { ctx, page, errors } = await open(b, { downloadError: { blobmoji: { code: 'sha_mismatch', error: 'SHA-256 mismatch: be12... expected, 0000... got.' } } });
    await page.click('#fntEmo_blobmoji .op-mode-btn');
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(150);
    const e = await row(page, 'blobmoji');
    check('7. a checksum that differs: said in the page\'s words, the file not kept, Download again', /not the expected one \(its checksum differs\), so it was not kept/.test(e.err) && e.btn === 'Download', JSON.stringify(e));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- Android 12 ----
  {
    const { ctx, page, errors } = await open(b, { sdk: 32, downloaded: ['twemoji', 'noto', 'apple'] });
    const rows = {};
    for (const id of ['noto', 'twemoji', 'blobmoji', 'openmoji', 'apple']) rows[id] = await row(page, id);
    check('8. Android 12: the three COLRv1 rows need Android 13 to show inside a font', ['noto', 'blobmoji', 'openmoji'].every(id => rows[id].notes.join() === 'Needs Android 13 to show inside a font.'), JSON.stringify(['noto', 'blobmoji', 'openmoji'].map(id => rows[id].notes)));
    check('   Twemoji (COLRv0) has no such line; iOS keeps its own', !rows.twemoji.notes.length && rows.apple.notes.length === 1 && /third-party build/.test(rows.apple.notes[0]), JSON.stringify([rows.twemoji.notes, rows.apple.notes]));
    check('   Make a font offers only Twemoji (Noto is on the phone but would not show)', (await opts(page, 'fntEmojiSel')).join() === 'none,cat:twemoji,file', (await opts(page, 'fntEmojiSel')).join());
    check('   System emoji offers Twemoji and iOS (not Noto COLRv1)', (await opts(page, 'fntSysSel')).join() === 'twemoji,apple', (await opts(page, 'fntSysSel')).join());
    await page.evaluate(() => document.getElementById('fntEmojiCard').scrollIntoView());
    await page.waitForTimeout(100);
    await page.screenshot({ path: 'android12.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
