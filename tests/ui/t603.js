// v7.18.0 Emoji fonts, the samples: under each row the sixteen sample emoji, in the font itself. Noto, Twemoji, Blobmoji and OpenMoji each load their
// cut-down copy once (emojiSpecimenFont) as the FontFace 'fntEmo_<id>'; the strip stays hidden until it is loaded, never the phone's own emoji in its
// place, and a font that does not load takes its strip away. On a WebView older than Chrome 98 the two COLRv1 fonts (Noto, Blobmoji) say it is too
// old to draw them, and are not loaded. The iOS emoji: the pictures the app kept are asked at render (emojiSpecimenCached); without them a note
// ("Reads about 0.3 MB of the iOS emoji file.", or "Read from the downloaded file." when the iOS emoji are on the phone) and "Show these emoji"
// (emojiSpecimenFetch), "Reading the iOS emoji…" while it runs, then sixteen 28 px pictures (alt: the emoji), kept for the next render and on a
// fresh page. A failure says why in the page's words with the button again. An older app build shows no strip. The row's Download stays its first
// button; nothing is wider than the screen at 360 px. The licences travel with the samples: assets/emoji/OFL.txt holds the full SIL Open Font
// License 1.1 with the copyright lines of noto.ttf and blobmoji.ttf (OFL condition 2), assets/emoji/NOTICE.txt points to it, and the root NOTICE
// lists the four fonts with their licences (ASCII, the iOS emoji never shipped).
// Mock bridge: lib/fontmaker_mock.js (opts.emoB64: assets/emoji/<id>.ttf; opts.gf: hold, specimenError, appleCached).
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const IDS = ['noto', 'twemoji', 'blobmoji', 'openmoji'];
const EMO = {}; IDS.forEach(id => { EMO[id] = fs.readFileSync(path.join(REPO, 'assets', 'emoji', id + '.ttf')).toString('base64'); });
const SAMPLE = '😀😂🥰😎🥳😭👍👋❤️🔥🎉🐶🦄🍕🚀🌈';
const OLD_UA = 'Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.98 Mobile Safari/537.36';

async function open(b, opts, ctxOpts) {
  const ctx = await b.newContext(Object.assign({ viewport: { width: 412, height: 900 } }, ctxOpts || {}));
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS, emoB64: EMO }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(800);
  return { ctx, page, errors };
}
const strips = (page) => page.evaluate(() => {
  const out = {};
  document.querySelectorAll('#fntEmojiList .fnt-emo').forEach(row => {
    const id = row.id.replace(/^fntEmo_/, ''), pv = row.querySelector('.fnt-emo-pv'), say = row.querySelector('.fnt-emo-say'), show = row.querySelector('#fntEmoShow');
    out[id] = {
      pv: pv ? { id: pv.id, wait: pv.classList.contains('is-wait'), hidden: getComputedStyle(pv).visibility === 'hidden', role: pv.getAttribute('role'), label: pv.getAttribute('aria-label'), noTr: pv.getAttribute('translate'),
        spans: [...pv.querySelectorAll('span')].map(s => s.textContent).join(''), n: pv.querySelectorAll('span').length, ff: [...new Set([...pv.querySelectorAll('span')].map(s => s.style.fontFamily))].join(','),
        imgs: [...pv.querySelectorAll('img')].map(i => i.alt).join(''), nImg: pv.querySelectorAll('img').length, imgSize: pv.querySelector('img') ? [pv.querySelector('img').getBoundingClientRect().width, pv.querySelector('img').getBoundingClientRect().height].join('x') : '',
        size: getComputedStyle(pv).fontSize, afterName: !!(pv.previousElementSibling && pv.previousElementSibling.classList.contains('rf-row')) } : null,
      say: say ? say.textContent : '', show: show ? show.textContent + (show.classList.contains('batch-tool-link') ? '' : '!') : '', err: (row.querySelector('.fnt-err .is-err') || {}).textContent || '',
      first: (row.querySelector('.op-mode-btn') || {}).textContent || '', notes: row.querySelectorAll('.fnt-note').length,
    };
  });
  return out;
});
const calls = (page, re) => page.evaluate((src) => __fmk.calls.filter(c => new RegExp(src).test(c)), re);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { hold: true } });
    let s = await strips(page);
    for (const id of IDS) {
      const p = s[id].pv;
      const ff = p && p.ff.replace(/["']/g, '');
      check('1. ' + id + ': the sixteen emoji under the name, in fntEmo_' + id + ' once loaded', !!p && p.id === 'fntEmoPv_' + id && p.afterName && p.n === 16 && p.spans === SAMPLE && !p.wait && !p.hidden && ff === 'fntEmo_' + id, JSON.stringify(p));
    }
    const labels = IDS.map(id => s[id].pv.label + '/' + s[id].pv.role + '/' + s[id].pv.noTr).join(' | ');
    check('   each is an image "Sample of <name>" (translate="no"), about 28 px', labels === 'Sample of Noto Color Emoji/img/no | Sample of Twemoji/img/no | Sample of Blobmoji/img/no | Sample of OpenMoji/img/no' && IDS.every(id => s[id].pv.size === '28px'), labels);
    const faces = await page.evaluate(() => [...document.fonts].filter(f => /^fntEmo_/.test(f.family)).map(f => f.family.replace(/"/g, '') + ':' + f.status).sort().join(','));
    check('   the four faces in document.fonts, loaded', faces === 'fntEmo_blobmoji:loaded,fntEmo_noto:loaded,fntEmo_openmoji:loaded,fntEmo_twemoji:loaded', faces);
    const drawn = await page.evaluate(() => ['noto', 'twemoji', 'blobmoji', 'openmoji'].every(id => document.fonts.check("28px 'fntEmo_" + id + "'", '😀🦄')));
    check('   each face draws the sample', drawn);
    check('   each font read once (emojiSpecimenFont)', (await calls(page, '^emojiSpecimenFont:')).sort().join(',') === 'emojiSpecimenFont:blobmoji,emojiSpecimenFont:noto,emojiSpecimenFont:openmoji,emojiSpecimenFont:twemoji', (await calls(page, '^emojiSpecimenFont:')).join(','));
    check('   the rows keep Download (or Delete) as their first button', s.noto.first === 'Download' && s.twemoji.first === 'Delete' && s.apple.first === 'Download', JSON.stringify(IDS.concat('apple').map(id => s[id].first)));
    // drawn again: the faces are not read again, the strips at once
    await page.evaluate(() => { switchView('apps'); });
    await page.waitForTimeout(200);
    await page.evaluate(() => { switchView('fonts'); });
    await page.waitForTimeout(100);
    s = await strips(page);
    check('2. the tab shown again: the strips at once, the fonts not read again', IDS.every(id => s[id].pv && !s[id].pv.wait) && (await calls(page, '^emojiSpecimenFont:')).length === 4, JSON.stringify((await calls(page, '^emojiSpecimenFont:'))));
    // the iOS emoji
    check('3. iOS: the kept pictures asked (none); the note and "Show these emoji"', (await calls(page, '^emojiSpecimenCached:')).length >= 1 && (await calls(page, '^emojiSpecimenCached:')).every(c => c === 'emojiSpecimenCached:apple')
      && !s.apple.pv && s.apple.say === 'Reads about 0.3 MB of the iOS emoji file.' && s.apple.show === 'Show these emoji' && s.apple.notes === 1, JSON.stringify(s.apple));
    await page.click('#fntEmoShow');
    s = await strips(page);
    check('   Show: emojiSpecimenFetch, "Reading the iOS emoji…", no button meanwhile', (await calls(page, '^emojiSpecimenFetch:')).join(',') === 'emojiSpecimenFetch:apple' && s.apple.say === 'Reading the iOS emoji…' && !s.apple.show, JSON.stringify(s.apple));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(200);
    s = await strips(page);
    check('   then sixteen pictures, 28 x 28, alt the emoji', s.apple.pv && s.apple.pv.nImg === 16 && s.apple.pv.imgs === SAMPLE && s.apple.pv.imgSize === '28x28' && s.apple.pv.label === 'Sample of iOS emoji' && !s.apple.say && !s.apple.show, JSON.stringify(s.apple));
    const src = await page.evaluate(() => document.querySelector('#fntEmoPv_apple img').getAttribute('src').slice(0, 22));
    check('   each a PNG in the page (data:image/png)', src === 'data:image/png;base64,', src);
    await page.evaluate(() => { switchView('apps'); });
    await page.waitForTimeout(150);
    await page.evaluate(() => { switchView('fonts'); });
    await page.waitForTimeout(150);
    s = await strips(page);
    check('   the next render: the pictures at once, not read again', s.apple.pv && s.apple.pv.nImg === 16 && (await calls(page, '^emojiSpecimenFetch:')).length === 1, JSON.stringify(s.apple));
    // dark and light at 360 px: nothing wider
    await page.setViewportSize({ width: 360, height: 900 });
    await page.waitForTimeout(200);
    const wide = await page.evaluate(() => { const cw = document.documentElement.clientWidth; return { sw: document.documentElement.scrollWidth, cw, out: [...document.querySelectorAll('#fntEmojiCard *')].filter(e => { const r = e.getBoundingClientRect(); return r.width && r.right > cw + 0.5; }).map(e => e.id || e.className).slice(0, 5) }; });
    check('4. 360 px: the strips wrap, nothing wider than the screen', wide.sw <= wide.cw && !wide.out.length, JSON.stringify(wide));
    await page.evaluate(() => document.getElementById('fntEmojiCard').scrollIntoView());
    await page.screenshot({ path: 'emoji-360.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a fresh page: the pictures the app kept
  {
    const { ctx, page, errors } = await open(b, { gf: { appleCached: true } });
    const s = await strips(page);
    check('5. a fresh page with the pictures kept: shown at once, nothing read', s.apple.pv && s.apple.pv.nImg === 16 && !(await calls(page, '^emojiSpecimenFetch:')).length, JSON.stringify(s.apple));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the iOS emoji downloaded; a failed read; an answer without pictures
  {
    const { ctx, page, errors } = await open(b, { downloaded: ['twemoji', 'apple'], gf: { specimenError: { code: 'emo_changed', error: 'The iOS emoji file at its address is not the expected one.' } } });
    let s = await strips(page);
    check('6. the iOS emoji on the phone: "Read from the downloaded file."', s.apple.say === 'Read from the downloaded file.' && s.apple.show === 'Show these emoji', JSON.stringify(s.apple));
    await page.click('#fntEmoShow'); await page.waitForTimeout(200);
    s = await strips(page);
    check('7. the read fails: the page\'s sentence and the button again', s.apple.err === 'The iOS emoji file at its address is not the expected one, so its sample was not read. Update the app.' && s.apple.show === 'Show these emoji' && !s.apple.pv && s.apple.notes === 1, JSON.stringify(s.apple));
    await page.evaluate(() => { __fmk.specimenError = null; });
    await page.click('#fntEmoShow'); await page.waitForTimeout(200);
    s = await strips(page);
    check('   again: the pictures, the error gone', s.apple.pv && s.apple.pv.nImg === 16 && !s.apple.err, JSON.stringify(s.apple));
    await page.evaluate(() => { fnt.emoApple.images = null; fnt.emoApple.busy = true; onEmojiSpecimen('apple', JSON.stringify({ ok: true, images: [{ cp: 0x1F600, b64: 'not base64!' }] })); });
    s = await strips(page);
    check('   an answer without a usable picture: "The sample could not be read from the emoji font."', s.apple.err === 'The sample could not be read from the emoji font.' && s.apple.show === 'Show these emoji', JSON.stringify(s.apple));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a WebView older than Chrome 98; a font the app does not have
  {
    const emo = Object.assign({}, EMO); delete emo.openmoji;
    const { ctx, page, errors } = await open(b, { emoB64: emo }, { userAgent: OLD_UA });
    const s = await strips(page);
    const old = 'This phone\'s WebView is too old to draw these emoji.';
    check('8. Chrome 97: Noto and Blobmoji (COLRv1) say the WebView is too old, and are not read', s.noto.say === old && !s.noto.pv && s.blobmoji.say === old && !s.blobmoji.pv
      && (await calls(page, '^emojiSpecimenFont:')).sort().join(',') === 'emojiSpecimenFont:openmoji,emojiSpecimenFont:twemoji', JSON.stringify([s.noto, await calls(page, '^emojiSpecimenFont:')]));
    check('   Twemoji (COLRv0) is drawn', s.twemoji.pv && !s.twemoji.pv.wait && s.twemoji.pv.n === 16, JSON.stringify(s.twemoji));
    check('9. no font for OpenMoji: no strip, nothing said', !s.openmoji.pv && !s.openmoji.say && !s.openmoji.err, JSON.stringify(s.openmoji));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // an older app build
  {
    const { ctx, page, errors } = await open(b, { missing: ['emojiSpecimenFont', 'emojiSpecimenCached', 'emojiSpecimenFetch'] });
    const s = await strips(page);
    const any = Object.keys(s).filter(id => s[id].pv || s[id].say || s[id].show);
    check('10. an older build: no strip, no note, no button; the rows as before', Object.keys(s).length === 5 && !any.length && s.noto.first === 'Download', JSON.stringify([Object.keys(s), any]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the licences that travel with the samples (read from the files: the app ships assets/ as it is)
  {
    const read = (...p) => { try { return fs.readFileSync(path.join(REPO, ...p), 'utf8'); } catch (e) { return ''; } };
    const ofl = read('assets', 'emoji', 'OFL.txt'), note = read('assets', 'emoji', 'NOTICE.txt'), root = read('NOTICE');
    const ascii = t => !/[^\x00-\x7f]/.test(t);
    const body = ['SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007', 'PREAMBLE', 'DEFINITIONS', 'PERMISSION & CONDITIONS', '5) The Font Software, modified or unmodified',
      'TERMINATION', 'DISCLAIMER', 'OTHER DEALINGS IN THE FONT SOFTWARE.'].filter(x => ofl.indexOf(x) < 0);
    check('11. assets/emoji/OFL.txt: the full SIL Open Font License 1.1, in ASCII', ofl.length > 4000 && !body.length && ascii(ofl), JSON.stringify([ofl.length, body]));
    const hdr = ofl.split('-----------------------------------------------------------')[0];
    check('   its head names noto.ttf and blobmoji.ttf as Modified Versions, each with "Copyright 2022 Google Inc."', /Modified Versions/.test(hdr)
      && /noto\.ttf \(made from Noto Color Emoji[^\n]*\):\nCopyright 2022 Google Inc\./.test(hdr) && /blobmoji\.ttf \(made from Blobmoji[^\n]*\):\nCopyright 2022 Google Inc\./.test(hdr), hdr);
    const rows = id => (note.split(/\n(?=\S)/).find(x => x.indexOf(id + '.ttf ') === 0) || '');
    check('   assets/emoji/NOTICE.txt: the Noto and Blobmoji rows point to OFL.txt, the other two do not', /OFL\.txt in this folder/.test(rows('noto')) && /OFL\.txt in this folder/.test(rows('blobmoji'))
      && !/OFL\.txt/.test(rows('twemoji')) && !/OFL\.txt/.test(rows('openmoji')), JSON.stringify(IDS.map(rows)));
    const sec = (root.split(/\n(?=Custom Fonts tab: the emoji samples\n-+\n)/)[1] || '').split(/\n\n(?=\S[^\n]*\n-+\n)/)[0];
    const lic = [['Noto Color Emoji', 'OFL-1.1'], ['Twemoji', 'CC-BY-4.0'], ['Blobmoji', 'OFL-1.1'], ['OpenMoji', 'CC-BY-SA-4.0']].filter(([f, l]) => !new RegExp('\\* ' + f + ' \\([^\\n]*\\) - ' + l + ' -\\s').test(sec));
    check('   the root NOTICE lists the four sample fonts with their licences, OFL.txt named, the iOS emoji never shipped', sec && !lic.length && /assets\/emoji\/OFL\.txt/.test(sec) && /iOS-style emoji are never shipped/.test(sec), JSON.stringify([lic, sec.slice(0, 200)]));
    check('   the root NOTICE is ASCII', ascii(root));
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
