// v7.18.0 Google Fonts, getting a family: a family not on the phone shows Download and, once its details came, "N font files". Download
// (gfontsDownload) shows the page's progress bar, open-ended with "Starting…", then "File 1 of 2 · 150 KB of 300 KB" with the bar at its share of
// all files, and Cancel. Done: a toast, "Downloaded · 2 files · 621 KB", Use in Make a font (the one primary), Save to phone and Delete, and the card
// gets its Downloaded badge. Cancel (gfontsCancel) says "Cancelling…", ignores later progress and goes back to Download without an error. A failed
// download says why in the page's words (gf_not_a_font) beside Download; one the app refuses (busy: already downloading, downloading, being saved)
// says the app's sentence. Delete (gfontsDelete, no question first) goes back to Download with a toast. Save to phone (gfontsSave) waits for
// Android's save screen ("Saving…"; Delete, another save and My fonts' Export wait too), then a toast with the file's name; closing the screen
// says nothing, a failure says why.
// Mock bridge: lib/fontmaker_mock.js (opts.gf.hold: the jobs move when the script says; opts.holdSave); the list: fixtures/gfonts-catalog.json.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));

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
const showFam = async (page, f) => {
  await page.evaluate((f) => { if (gf.view === 'detail') gfBackToList(); const s = document.getElementById('gfSearch'); s.value = f; s.dispatchEvent(new Event('input', { bubbles: true })); }, f);
  await page.waitForTimeout(250);
  await page.click('#gfList .gf-card[data-f="' + f + '"]');
  await page.waitForTimeout(200);
};
const step = async (page, n) => { await page.evaluate((n) => __fmk.gfStep(n), n); await page.waitForTimeout(80); };
const get = (page) => page.evaluate(() => {
  const g = document.getElementById('gfGet'), q = s => g.querySelector(s), on = el => !!el && getComputedStyle(el).display !== 'none';
  const btn = id => { const el = document.getElementById(id); return el ? (el.disabled ? '-' : '') + el.textContent.trim() + (el.classList.contains('primary') ? '*' : '') : null; };
  const prog = document.getElementById('gfDlProg');
  return {
    prog: prog ? { on: prog.classList.contains('on'), role: prog.getAttribute('role'), now: prog.getAttribute('aria-valuenow'), indet: document.getElementById('gfDlBar').classList.contains('indet'),
      w: (document.querySelector('#gfDlBar > i') || {}).style ? document.querySelector('#gfDlBar > i').style.width : '', txt: document.getElementById('gfDlTxt').textContent } : null,
    dl: btn('gfDlBtn'), cancel: btn('gfCancelBtn'), use: btn('gfUseBtn'), save: btn('gfSaveBtn'), del: btn('gfDelBtn'),
    have: (document.getElementById('gfHave') || {}).textContent || '', n: (document.getElementById('gfFileCount') || {}).textContent || '',
    err: (document.querySelector('#gfDlErr .is-err') || {}).textContent || '', msg: (document.getElementById('gfMsg') || {}).textContent || '',
    selects: !!document.getElementById('gfUseReg'), primaries: g.querySelectorAll('.mode-action-btn.primary').length,
    toast: ((document.getElementById('toast') || {}).textContent || '').trim(),
  };
});
const calls = (page, re) => page.evaluate((src) => __fmk.calls.filter(c => new RegExp(src).test(c)), re);
const kb = n => (n * 1024);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], hold: true }, holdSave: true });
    await showFam(page, 'Roboto');
    let g = await get(page);
    check('1. Roboto, not on the phone: Download (primary), the number of files once the details came', g.dl === 'Download*' && g.n === '' && !g.prog, JSON.stringify(g));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(100);
    g = await get(page);
    check('   the details: "2 font files"', g.n === '2 font files', g.n);
    await page.click('#gfDlBtn');
    g = await get(page);
    check('2. Download: gfontsDownload, an open-ended bar, "Starting…", Cancel; no Download button', (await calls(page, '^gfontsDownload:')).join(',') === 'gfontsDownload:Roboto' && g.prog && g.prog.on && g.prog.indet && g.prog.role === 'progressbar' && g.prog.now === null
      && g.prog.txt === 'Starting…' && g.cancel === 'Cancel' && g.dl === null, JSON.stringify(g));
    const fmt = await page.evaluate(() => [formatBytes(150 * 1024), formatBytes(300 * 1024), formatBytes(321 * 1024), formatBytes(621 * 1024)]);
    await step(page, 1);
    g = await get(page);
    check('3. progress: "File 1 of 2 · ' + fmt[0] + ' of ' + fmt[1] + '", the bar at 25%', g.prog && !g.prog.indet && g.prog.txt === 'File 1 of 2 · ' + fmt[0] + ' of ' + fmt[1] && g.prog.now === '25' && g.prog.w === '25%', JSON.stringify(g.prog));
    await step(page, 1);
    g = await get(page);
    check('   the first file done: 50%', g.prog.now === '50' && g.prog.w === '50%' && g.prog.txt === 'File 1 of 2 · ' + fmt[1] + ' of ' + fmt[1], JSON.stringify(g.prog));
    await step(page, 1);
    g = await get(page);
    check('   the second file: "File 2 of 2", past 50%', /^File 2 of 2 · /.test(g.prog.txt) && +g.prog.now > 50 && +g.prog.now < 100, JSON.stringify(g.prog));
    // Back to the list while it runs, and in again: the bar is still there
    await page.evaluate(() => gfBackToList());
    await page.click('#gfList .gf-card[data-f="Roboto"]'); await page.waitForTimeout(150);
    g = await get(page);
    check('   left and opened again while it runs: the bar where it was', g.prog && /^File 2 of 2 · /.test(g.prog.txt) && g.cancel === 'Cancel', JSON.stringify(g.prog));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('4. done: a toast, "Downloaded · 2 files · ' + fmt[3] + '"', g.toast === 'Roboto downloaded' && g.have === 'Downloaded · 2 files · ' + fmt[3] && !g.prog, JSON.stringify(g));
    check('   a variable family: nothing to choose; Use in Make a font (the one primary), Save to phone, Delete', !g.selects && g.use === 'Use in Make a font*' && g.save === 'Save to phone' && g.del === 'Delete' && g.primaries === 1, JSON.stringify(g));
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(100);
    const badge = await page.evaluate(() => [...document.querySelectorAll('#gfList .gf-card[data-f="Roboto"] .uad-badge')].map(x => x.textContent).join(','));
    check('   its card: Downloaded', badge === 'Variable,Downloaded', badge);
    // Cancel
    await showFam(page, 'Inter');
    await page.click('#gfDlBtn');
    await step(page, 1);
    await page.click('#gfCancelBtn');
    g = await get(page);
    check('5. Cancel: gfontsCancel, "Cancelling…", Cancel waits', (await calls(page, '^gfontsCancel:')).join(',') === 'gfontsCancel:Inter' && g.prog && g.prog.txt === 'Cancelling…' && g.cancel === '-Cancel', JSON.stringify(g));
    await page.evaluate(() => onGfProgress('Inter', 2, 2, 1000, 2000));
    check('   later progress does not change it', (await get(page)).prog.txt === 'Cancelling…');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('   cancelled: Download again, no error, not on the phone', g.dl === 'Download*' && !g.err && !g.prog && !(await page.evaluate(() => !!gf.dls.Inter)), JSON.stringify(g));
    // a download that fails
    await page.evaluate(() => { __fmk.gfDownloadError = { Inter: { code: 'gf_not_a_font', error: 'Google Fonts sent a file that is not a font, so the family was not kept.' } }; });
    await page.click('#gfDlBtn');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('6. a failed download: the page\'s sentence, Download again', g.err === 'Google Fonts sent a file that is not a font, so the family was not kept.' && g.dl === 'Download*' && !g.prog, JSON.stringify(g));
    await page.evaluate(() => { __fmk.gfDownloadError = { Inter: { code: 'network', error: 'The download failed: Connection reset' } }; });
    await page.click('#gfDlBtn');
    g = await get(page);
    check('   Download again: the error goes', !g.err && g.prog, JSON.stringify(g));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    check('   a network failure: "The download failed (Connection reset). Check the connection and try again."', (await get(page)).err === 'The download failed (Connection reset). Check the connection and try again.');
    // Delete
    await showFam(page, 'Lato');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(100);
    g = await get(page);
    check('7. Lato on the phone: "Downloaded · 10 files", the Regular and Bold choices', /^Downloaded · 10 files · /.test(g.have) && g.selects, JSON.stringify(g));
    // Save to phone: the save screen
    await page.click('#gfSaveBtn');
    g = await get(page);
    const exp = () => page.evaluate(() => [...document.querySelectorAll('#fntLibList button')].filter(x => x.textContent.trim() === 'Export').map(x => x.disabled));
    check('8. Save to phone: gfontsSave, "Saving…" waits, Delete waits', (await calls(page, '^gfontsSave:')).join(',') === 'gfontsSave:Lato' && g.save === '-Saving…' && g.del === '-Delete', JSON.stringify(g));
    check('   My fonts\' Export waits too', (await exp()).length === 2 && (await exp()).every(Boolean), JSON.stringify(await exp()));
    await page.evaluate(() => gfDelete());
    check('   Delete is not asked while it saves', !(await calls(page, '^gfontsDelete:')).length);
    await page.evaluate(() => __fmk.save()); await page.waitForTimeout(100);
    g = await get(page);
    check('   saved: a toast with the file\'s name; the buttons back', g.toast === 'Saved Lato.zip' && g.save === 'Save to phone' && g.del === 'Delete' && (await exp()).every(x => !x), JSON.stringify([g, await exp()]));
    await page.click('#gfDelBtn');
    g = await get(page);
    check('9. Delete: gfontsDelete, at once (no question), a toast, Download again', (await calls(page, '^gfontsDelete:')).join(',') === 'gfontsDelete:Lato' && g.toast === 'Deleted Lato' && g.dl === 'Download*' && !g.have, JSON.stringify(g));
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(100);
    const badge2 = await page.evaluate(() => [...document.querySelectorAll('#gfList .gf-card[data-f="Lato"] .uad-badge')].map(x => x.textContent).join(','));
    check('   its card has no Downloaded badge any more', badge2 === '', badge2);
    // My fonts' Export open: Save to phone waits for it
    await showFam(page, 'Roboto');
    await page.evaluate(() => { fnt.exporting = 'x'; gfRenderGet(); });
    check('   while My fonts saves a font: Save to phone waits', (await get(page)).save === '-Save to phone');
    await page.evaluate(() => { fnt.exporting = ''; gfRenderGet(); });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the save screen closed, or the save failed
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], saveError: { code: 'cancelled', error: 'Cancelled.' } } });
    await showFam(page, 'Lato');
    await page.click('#gfSaveBtn'); await page.waitForTimeout(150);
    let g = await get(page);
    check('10. the save screen closed: nothing said, Save to phone again', !g.msg && g.save === 'Save to phone', JSON.stringify(g));
    await ctx.close();
    check('   no page errors', !errors.length, errors.join(' | '));
  }
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], saveError: { code: 'no_space', error: 'There is not enough free space.' } } });
    await showFam(page, 'Lato');
    await page.click('#gfSaveBtn'); await page.waitForTimeout(150);
    const g = await get(page);
    check('   a failed save: the page\'s sentence', g.msg === 'There is not enough free space on this phone.' && g.save === 'Save to phone', JSON.stringify(g));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the app says no: busy
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'] }, refuse: {
      gfontsDownload: { code: 'busy', error: 'This font family is already downloading.' },
      gfontsDelete: { code: 'busy', error: 'This font family is being saved. Wait for it to finish.' },
      gfontsSave: { code: 'busy', error: 'The save screen is already open.' } } });
    await showFam(page, 'Roboto');
    await page.click('#gfDlBtn');
    let g = await get(page);
    check('11. busy: "This font family is already downloading."', g.err === 'This font family is already downloading.' && g.dl === 'Download*' && !g.prog, JSON.stringify(g));
    await showFam(page, 'Lato');
    await page.click('#gfDelBtn');
    g = await get(page);
    check('   "This font family is being saved. Wait for it to finish."', g.msg === 'This font family is being saved. Wait for it to finish.' && /^Downloaded/.test(g.have), JSON.stringify(g));
    await page.click('#gfSaveBtn');
    g = await get(page);
    check('   "The save screen is already open."', g.msg === 'The save screen is already open.' && g.save === 'Save to phone', JSON.stringify(g));
    const said = await page.evaluate(() => fntErrText({ code: 'busy', error: 'This font family is downloading. Cancel it first.' }));
    check('   "This font family is downloading. Cancel it first." is said as itself', said === 'This font family is downloading. Cancel it first.', said);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
