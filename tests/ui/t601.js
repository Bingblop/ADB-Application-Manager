// v7.18.0 Google Fonts, getting a family: a family not on the phone shows Download and, once its details came, "N font files". Download
// (gfontsDownload) shows the page's progress bar, open-ended with "Starting…", then "File 1 of 2 · 150 KB of 300 KB" with the bar at its share of
// all files, and Cancel. Done: a toast, "Downloaded · 2 files · 621 KB", Use in Make a font (the one primary), Save to phone and Delete, and the card
// gets its Downloaded badge. Cancel (gfontsCancel) says "Cancelling…", ignores later progress and goes back to Download without an error. A failed
// download says why in the page's words (gf_not_a_font) beside Download, one that failed on the phone's own storage its own sentence or a full
// phone (never "check the connection"); one that fails after its page was left or the sheet closed says so in a toast ("Inter was not
// downloaded. ..."; a cancel says nothing), and its page says why when opened again; one the app refuses (busy: already downloading,
// downloading, being saved) says the app's sentence. Delete (gfontsDelete, no question first) goes back to Download with a toast. Save to phone (gfontsSave) waits for
// Android's save screen ("Saving…"; Delete, another save and My fonts' Export wait too), then a toast with the file's name; closing the screen
// says nothing, a failure says why (the app's "The font family could not be saved: ..." as the page's sentence), in a toast ("Lato was not saved.
// ...") when it comes after the page was left or the sheet closed. The list follows a Delete or a
// download made on a family's page, or one that finishes while the list shows (the Downloaded filter, the count, the badges). A downloaded family the
// refreshed list no longer has (renamed or removed on Google Fonts) keeps a card ("No longer on Google Fonts") and a page with Use, Save and Delete;
// the search button counts it as the sheet does, and its Delete asks first (it cannot be downloaded again; after it the focus is on the list, not
// on the closed question). A refreshed list that drops the family
// whose page is open goes back to the list, unless the family is on the phone: then its page stays and says it left Google Fonts. With a keyboard
// (switch access, TalkBack) the focus is not lost when the section is drawn again: it stays on the same control (the details came, the first
// progress), else goes to the next one (Download to Cancel, Cancel once cancelled and Delete to Download, Save to phone to Use in Make a font);
// a card keeps it when a download that ended while the list shows draws the list again. In About it stays on Open on fonts.google.com when the
// details come, on About itself ("Getting the details…") after Retry until the reason (Retry again) or the details (More) come, on the
// description that More opened, and on it or Licence text (which stays open) when a newer list draws About again (the older list's details stay
// shown until the newer ones come, and stay when those cannot be had or are refused at once). A long Licence text takes the focus itself (Tab),
// and keeps it and the place it was read to through such a redraw; a slider keeps it when a newer list draws the sliders again.
// The Help Guide says the progress sizes are a file's as it comes over the network (packed), not the space the family takes on the phone, and its
// undo table has a row for a downloaded family (Delete on its page). Without
// a family's details, About's Licence is its download's: it comes with a download made on the family's page and goes with a Delete there. A
// family that left Google Fonts shows no details in About (no description, Licence text, reason or Retry), even ones that came before it left.
// Mock bridge: lib/fontmaker_mock.js (opts.gf.hold: the jobs move when the script says; opts.holdSave); the list: fixtures/gfonts-catalog.json.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));

async function open(b, opts, stay) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  if (stay) return { ctx, page, errors };                           // the tab only: the sheet stays closed
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
// the family's About: its rows, and which of the details' parts (description, Licence text) or their wait, reason and Retry are there
const about = (page) => page.evaluate(() => ({ rows: [...document.querySelectorAll('#gfAbout .fnt-now')].map(r => [...r.children].map(c => c.textContent.trim()).join(' ')).join(' | '),
  extra: ['gfDesc', 'gfDescMore', 'gfLic', 'gfInfoWait', 'gfInfoErr', 'gfInfoRetry'].filter(id => !!document.getElementById(id)) }));
// where the focus is (a keyboard, switch access, TalkBack): the focused element's id, or its tag when it has none ('BODY': lost)
const focused = (page) => page.evaluate(() => { const a = document.activeElement; return a ? a.id || a.tagName : ''; });
const kb = n => (n * 1024);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], hold: true }, holdSave: true });
    await showFam(page, 'Roboto');
    let g = await get(page);
    check('1. Roboto, not on the phone: Download (primary), the number of files once the details came', g.dl === 'Download*' && g.n === '' && !g.prog, JSON.stringify(g));
    await page.focus('#gfDlBtn');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(100);
    g = await get(page);
    check('   the details: "2 font files"', g.n === '2 font files', g.n);
    check('   the section drawn again with them: the focus stays on Download', (await focused(page)) === 'gfDlBtn', await focused(page));
    await page.click('#gfDlBtn');
    g = await get(page);
    check('2. Download: gfontsDownload, an open-ended bar, "Starting…", Cancel; no Download button', (await calls(page, '^gfontsDownload:')).join(',') === 'gfontsDownload:Roboto' && g.prog && g.prog.on && g.prog.indet && g.prog.role === 'progressbar' && g.prog.now === null
      && g.prog.txt === 'Starting…' && g.cancel === 'Cancel' && g.dl === null, JSON.stringify(g));
    check('   the focus goes from Download (gone) to Cancel, not to the body', (await focused(page)) === 'gfCancelBtn', await focused(page));
    const fmt = await page.evaluate(() => [formatBytes(150 * 1024), formatBytes(300 * 1024), formatBytes(321 * 1024), formatBytes(621 * 1024)]);
    await step(page, 1);
    g = await get(page);
    check('3. progress: "File 1 of 2 · ' + fmt[0] + ' of ' + fmt[1] + '", the bar at 25%', g.prog && !g.prog.indet && g.prog.txt === 'File 1 of 2 · ' + fmt[0] + ' of ' + fmt[1] && g.prog.now === '25' && g.prog.w === '25%', JSON.stringify(g.prog));
    check('   the first progress (the bar drawn again, no longer open-ended): the focus stays on Cancel', (await focused(page)) === 'gfCancelBtn', await focused(page));
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
    await page.evaluate(() => { document.getElementById('gfLic').open = true; });
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('4. done: a toast, "Downloaded · 2 files · ' + fmt[3] + '"', g.toast === 'Roboto downloaded' && g.have === 'Downloaded · 2 files · ' + fmt[3] && !g.prog, JSON.stringify(g));
    check('   About is not drawn again (its details came): the Licence text opened meanwhile stays open', await page.evaluate(() => !!document.getElementById('gfLic') && document.getElementById('gfLic').open));
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
    check('   the focus, which Cancel (waiting) could not keep, is on Download', (await focused(page)) === 'gfDlBtn', await focused(page));
    // a download that fails
    await page.evaluate(() => { __fmk.gfDownloadError = { Inter: { code: 'gf_not_a_font', error: 'Google Fonts sent a file that is not a font, so the family was not kept.' } }; });
    await page.click('#gfDlBtn');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('6. a failed download: the page\'s sentence, Download again', g.err === 'Google Fonts sent a file that is not a font, so the family was not kept.' && g.dl === 'Download*' && !g.prog, JSON.stringify(g));
    check('   its page shows it: no toast says it again', !/was not downloaded/.test(g.toast), g.toast);
    await page.evaluate(() => { __fmk.gfDownloadError = { Inter: { code: 'network', error: 'The download failed: Connection reset' } }; });
    await page.click('#gfDlBtn');
    g = await get(page);
    check('   Download again: the error goes', !g.err && g.prog, JSON.stringify(g));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    check('   a network failure: "The download failed (Connection reset). Check the connection and try again."', g.err === 'The download failed (Connection reset). Check the connection and try again.' && !/was not downloaded/.test(g.toast), JSON.stringify(g));
    // a download that fails once its page was left (Back to the list) or the sheet closed: a toast says so, as one that finishes does; the page says why
    const NET = 'The download failed (Connection reset). Check the connection and try again.';
    const noToast = () => page.evaluate(() => { document.getElementById('toastMsg').innerText = ''; });
    await noToast();
    await page.click('#gfDlBtn');
    await step(page, 1);
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(100);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    g = await get(page);
    const interBadge = () => page.evaluate(() => [...document.querySelectorAll('#gfList .gf-card[data-f="Inter"] .uad-badge')].map(x => x.textContent).join(','));
    check('6b. failed after Back to the list: a toast "Inter was not downloaded. ..." with the reason; its card is not Downloaded', g.toast === 'Inter was not downloaded. ' + NET && (await interBadge()) === 'Variable', JSON.stringify([g.toast, await interBadge()]));
    await page.click('#gfList .gf-card[data-f="Inter"]'); await page.waitForTimeout(150);
    g = await get(page);
    check('   its page opened again says why, with Download', g.err === NET && g.dl === 'Download*' && !g.prog, JSON.stringify(g));
    await noToast();
    await page.click('#gfDlBtn');
    await step(page, 1);
    await page.evaluate(() => gfClose()); await page.waitForTimeout(100);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    const closed = await page.evaluate(() => ({ toast: document.getElementById('toastMsg').textContent, err: gf.dlErr.Inter || '', open: gf.open }));
    check('   failed after the sheet closed: the same toast, and the reason kept for its page', !closed.open && closed.toast === 'Inter was not downloaded. ' + NET && closed.err === NET, JSON.stringify(closed));
    await page.click('#gfOpenBtn'); await page.waitForTimeout(200);
    await showFam(page, 'Inter');
    g = await get(page);
    check('   the sheet and its page opened again: the reason, Download', g.err === NET && g.dl === 'Download*', JSON.stringify(g));
    await noToast();
    await page.click('#gfDlBtn');
    await step(page, 1);
    await page.click('#gfCancelBtn');
    await page.evaluate(() => gfClose()); await page.waitForTimeout(100);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    const cxl = await page.evaluate(() => ({ toast: document.getElementById('toastMsg').textContent, err: gf.dlErr.Inter || '' }));
    check('   cancelled, then the sheet closed: no toast, no error', cxl.toast === '' && cxl.err === '', JSON.stringify(cxl));
    await page.evaluate(() => { __fmk.gfDownloadError = {}; });
    await page.click('#gfOpenBtn'); await page.waitForTimeout(200);
    await showFam(page, 'Inter');
    // a failure on the phone's own storage (GfLocal, FontLibrary.localError): its own sentence, or a full phone; never "check the connection" (R2-03)
    for (const said of ["The app could not make the family's folder.", "The family's old folder could not be moved aside.", 'The download could not be put in place.']) {
      await page.evaluate((said) => { __fmk.gfDownloadError = { Inter: { code: 'failed', error: said } }; }, said);
      await page.click('#gfDlBtn');
      await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
      const e = (await get(page)).err;
      check('   a failure on the phone: "' + said + '" as itself', e === said && !/connection/i.test(e), e);
    }
    await page.evaluate(() => { __fmk.gfDownloadError = { Inter: { code: 'no_space', error: 'There is not enough free space on this phone.' } }; });
    await page.click('#gfDlBtn');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    check('   a full phone: "There is not enough free space on this phone."', (await get(page)).err === 'There is not enough free space on this phone.', (await get(page)).err);
    await page.evaluate(() => { __fmk.gfDownloadError = {}; });
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
    check('   the focus goes from Save to phone (waiting) to Use in Make a font', (await focused(page)) === 'gfUseBtn', await focused(page));
    check('   My fonts\' Export waits too', (await exp()).length === 2 && (await exp()).every(Boolean), JSON.stringify(await exp()));
    await page.evaluate(() => gfDelete());
    check('   Delete is not asked while it saves', !(await calls(page, '^gfontsDelete:')).length);
    await page.evaluate(() => __fmk.save()); await page.waitForTimeout(100);
    g = await get(page);
    check('   saved: a toast with the file\'s name; the buttons back', g.toast === 'Saved Lato.zip' && g.save === 'Save to phone' && g.del === 'Delete' && (await exp()).every(x => !x), JSON.stringify([g, await exp()]));
    check('   the focus stays on Use in Make a font', (await focused(page)) === 'gfUseBtn', await focused(page));
    await page.click('#gfDelBtn');
    g = await get(page);
    const asked = await page.evaluate(() => document.getElementById('mpAskModal').classList.contains('show'));
    check('9. Delete: gfontsDelete, at once (no question), a toast, Download again', (await calls(page, '^gfontsDelete:')).join(',') === 'gfontsDelete:Lato' && g.toast === 'Deleted Lato' && g.dl === 'Download*' && !g.have && !asked, JSON.stringify([g, asked]));
    check('   the focus goes from Delete (gone) to Download', (await focused(page)) === 'gfDlBtn', await focused(page));
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
    check('   its page shows it: no toast says it again', !/was not saved/.test(g.toast), g.toast);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a save that fails after its page was left (Back, another family's page) or the sheet closed: a toast says so, as a finished save does, and the
  // family's page says why when opened again; a closed save screen still says nothing
  {
    const NS = 'There is not enough free space on this phone.';
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato', 'Roboto'], saveError: { code: 'no_space', error: 'There is not enough free space.' } }, holdSave: true });
    const said = () => page.evaluate(() => ({ toast: document.getElementById('toastMsg').textContent, msg: (gf.msg.Lato || {}).text || '', saving: gf.saving }));
    const saveThen = async (leave) => {
      await showFam(page, 'Lato');
      await page.click('#gfSaveBtn'); await page.waitForTimeout(100);
      await page.evaluate(() => { document.getElementById('toastMsg').innerText = ''; });
      await leave();
      await page.evaluate(() => __fmk.save()); await page.waitForTimeout(150);
      return said();
    };
    let s = await saveThen(() => page.evaluate(() => gfBackToList()));
    check('10b. failed after Back to the list: a toast "Lato was not saved. ..." with the reason', s.toast === 'Lato was not saved. ' + NS && s.msg === NS && !s.saving, JSON.stringify(s));
    await showFam(page, 'Lato');
    let g = await get(page);
    check('   Lato\'s page opened again says why, Save to phone on', g.msg === NS && g.save === 'Save to phone', JSON.stringify(g));
    s = await saveThen(() => page.evaluate(() => gfClose()));
    check('   failed after the sheet closed: the same toast, the reason kept', s.toast === 'Lato was not saved. ' + NS && s.msg === NS, JSON.stringify(s));
    await page.click('#gfOpenBtn'); await page.waitForTimeout(200);
    s = await saveThen(() => showFam(page, 'Roboto'));
    g = await get(page);
    check('   failed while Roboto\'s page shows: the same toast; Roboto\'s Save to phone on again, no word of Lato\'s on its page', s.toast === 'Lato was not saved. ' + NS && g.save === 'Save to phone' && !g.msg
      && (await page.evaluate(() => gf.fam)) === 'Roboto', JSON.stringify([s, g]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], saveError: { code: 'cancelled', error: 'Cancelled.' } }, holdSave: true });
    await showFam(page, 'Lato');
    await page.click('#gfSaveBtn'); await page.waitForTimeout(100);
    await page.evaluate(() => { document.getElementById('toastMsg').innerText = ''; gfBackToList(); });
    await page.evaluate(() => __fmk.save()); await page.waitForTimeout(150);
    const c = await page.evaluate(() => ({ toast: document.getElementById('toastMsg').textContent, msg: gf.msg.Lato || null }));
    check('   the save screen closed after Back: nothing said', !c.toast && !c.msg, JSON.stringify(c));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], saveError: { code: 'failed', error: 'The font family could not be saved: ENOSPC (No space left on device)' } } });
    await showFam(page, 'Lato');
    await page.click('#gfSaveBtn'); await page.waitForTimeout(150);
    const g = await get(page);
    check('   the app\'s "The font family could not be saved: ..." is the page\'s sentence (not "Something went wrong")', g.msg === 'The font family could not be saved: ENOSPC (No space left on device)' && g.save === 'Save to phone', JSON.stringify(g));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the list follows the downloads: a Delete on a family's page, a download started there and finished while the list shows
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Roboto', 'Lato'], hold: true } });
    const list = () => page.evaluate(() => ({ cards: [...document.querySelectorAll('#gfList .gf-card')].map(c => c.dataset.f).join(','), count: document.getElementById('gfCount').textContent,
      badges: Object.fromEntries([...document.querySelectorAll('#gfList .gf-card')].map(c => [c.dataset.f, [...c.querySelectorAll('.uad-badge')].map(x => x.textContent).join(',')])) }));
    await page.click('#gfChipDl'); await page.waitForTimeout(100);
    let l = await list();
    check('12. Downloaded: Roboto and Lato, "2 of 64"', l.cards === 'Roboto,Lato' && l.count === '2 of 64', JSON.stringify(l));
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(150);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(100);
    await page.click('#gfDelBtn');
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(150);
    l = await list();
    check('   Lato deleted on its page, then Back: Roboto alone, "1 of 64"', l.cards === 'Roboto' && l.count === '1 of 64', JSON.stringify(l));
    await page.evaluate(() => gfShow('Lato')); await page.waitForTimeout(150);
    await page.click('#gfDlBtn');
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(150);
    l = await list();
    check('   its download started, Back while it runs: still Roboto alone', l.cards === 'Roboto' && l.count === '1 of 64', JSON.stringify(l));
    // a keyboard's (TalkBack's) focus on a card when the list is drawn again for a download that ended: on that card still, the list where it was
    const kf = () => page.evaluate(() => { const a = document.activeElement; return { f: a && a.classList.contains('gf-card') && document.getElementById('gfList').contains(a) ? a.dataset.f : (a ? a.id || a.tagName : ''), top: document.getElementById('gfScroll').scrollTop }; });
    await page.focus('#gfList .gf-card[data-f="Roboto"]');
    const k0 = await kf();
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    l = await list();
    check('   it finishes while the list shows: Lato is back, Downloaded, "2 of 64"', l.cards === 'Roboto,Lato' && l.count === '2 of 64' && l.badges.Lato === 'Downloaded', JSON.stringify(l));
    const k1 = await kf();
    check('   a keyboard\'s focus on Roboto\'s card stays there when Lato\'s download finishes (not lost to the page)', k0.f === 'Roboto' && k1.f === 'Roboto' && k1.top === k0.top, JSON.stringify([k0, k1]));
    await page.click('#gfChipDl'); await page.waitForTimeout(100);
    await page.evaluate(() => { gfShow('Inter'); }); await page.waitForTimeout(150);
    await page.click('#gfDlBtn');
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(150);
    await page.evaluate(() => { document.getElementById('gfScroll').scrollTop = 600; }); await page.waitForTimeout(100);
    await page.focus('#gfList .gf-card[data-f="Lato"]');
    const k2 = await kf();
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    l = await list();
    check('   without the filter: Inter\'s card gets Downloaded when its download finishes', l.badges.Inter === 'Variable,Downloaded' && l.count === '64 families', JSON.stringify([l.badges.Inter, l.count]));
    const k3 = await kf();
    check('   the focus on Lato\'s card stays there, the list scrolled where it was', k2.f === 'Lato' && k3.f === 'Lato' && k3.top === k2.top && k2.top > 0, JSON.stringify([k2, k3]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a downloaded family the refreshed list no longer has: its card and page stay, so it can still be used, saved and deleted
  {
    const REMOTE = Object.assign({}, CAT, { families: CAT.families.filter(e => e.f !== 'Lato') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, stale: true, remote: REMOTE, downloads: ['Lato'], hold: true } });
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(200);       // the stale list's refresh comes: Lato is not in it
    const n0 = await page.evaluate(() => __fmk.calls.length);
    const inList = await page.evaluate(() => gf.data.families.some(e => e.f === 'Lato'));
    const counts = () => page.evaluate(() => ({ btn: document.getElementById('gfOpenCount').textContent, sheet: document.getElementById('gfCount').textContent }));
    const c0 = await counts();
    check('13. the new list has 63 families and Lato is on the phone: the search button counts 64, as the sheet does', c0.btn === '64 families' && c0.sheet === '64 families', JSON.stringify(c0));
    await page.click('#gfChipDl'); await page.waitForTimeout(100);
    const l = await page.evaluate(() => ({ cards: [...document.querySelectorAll('#gfList .gf-card')].map(c => c.dataset.f + ':' + [...c.querySelectorAll('.uad-badge')].map(x => x.textContent).join(',')), count: document.getElementById('gfCount').textContent }));
    check('   Lato left the list but is on the phone: under Downloaded, its card says so', !inList && l.cards.join('|') === 'Lato:Downloaded,No longer on Google Fonts' && l.count === '1 of 64', JSON.stringify([inList, l]));
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(200);
    let g = await get(page);
    const pg = await page.evaluate((n0) => ({ title: document.getElementById('gfTitle').textContent, detail: getComputedStyle(document.getElementById('gfDetail')).display !== 'none', note: (document.getElementById('gfGoneNote') || {}).textContent || '',
      infoErr: !!document.getElementById('gfInfoErr'), site: !!document.getElementById('gfOpenSite'), about: [...document.querySelectorAll('#gfAbout .fnt-now')].map(r => [...r.children].map(c => c.textContent.trim()).join(' ')).join(' | '),
      head: document.getElementById('gfDHead').textContent, styles: [...document.querySelectorAll('#gfStyles .gf-style-pv')].map(x => x.classList.contains('gf-failed') && !x.classList.contains('gf-wait')),
      asked: __fmk.calls.slice(n0).filter(c => /^gfonts(Info|Preview):Lato/.test(c)) }), n0);
    check('   its page: Downloaded with Use in Make a font, Save to phone and Delete, and a note that it left Google Fonts', pg.title === 'Lato' && pg.detail && /^Downloaded · 10 files · /.test(g.have) && g.use === 'Use in Make a font*' && g.save === 'Save to phone' && g.del === 'Delete'
      && pg.note === 'This family is no longer on Google Fonts. Its downloaded files still work here.', JSON.stringify([pg, g]));
    check('   its details and previews are not asked for (Google Fonts no longer knows it): "No preview", no error', !pg.asked.length && !pg.infoErr && pg.head === 'LatoNo preview' && pg.styles.length === 10 && pg.styles.every(Boolean), JSON.stringify(pg));
    check('   About: its licence as the download has it, no link to fonts.google.com', pg.about === 'Licence SIL Open Font License 1.1' && !pg.site, JSON.stringify(pg));
    await page.click('#gfSaveBtn'); await page.waitForTimeout(150);
    g = await get(page);
    check('   Save to phone: gfontsSave, saved', (await calls(page, '^gfontsSave:')).join(',') === 'gfontsSave:Lato' && g.toast === 'Saved Lato.zip', JSON.stringify(g));
    await page.click('#gfUseBtn'); await page.waitForTimeout(250);
    const use = await page.evaluate(() => ({ calls: __fmk.calls.filter(c => /^gfontsUse:/.test(c)), reg: fnt.pick.regular && fnt.pick.regular.fileName, bold: fnt.pick.bold && fnt.pick.bold.fileName, toast: document.getElementById('toast').textContent.trim(), open: document.getElementById('gfSheet').classList.contains('show') }));
    check('   Use in Make a font: its Regular and Bold go into Make a font', use.calls.join(',') === 'gfontsUse:Lato:Lato-Regular.ttf:regular,gfontsUse:Lato:Lato-Bold.ttf:bold' && use.reg === 'Lato-Regular.ttf' && use.bold === 'Lato-Bold.ttf'
      && use.toast === 'Lato is in Make a font' && !use.open, JSON.stringify(use));
    await page.click('#gfOpenBtn'); await page.waitForTimeout(200);
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(150);
    // it cannot be downloaded again: Delete asks first (My fonts' question, over the sheet)
    const ask = () => page.evaluate(() => {
      const m = document.getElementById('mpAskModal'), hit = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
      return { show: m.classList.contains('show'), top: !!hit && !!hit.closest('#mpAskModal'), title: document.getElementById('mpAskTitle').textContent, text: document.getElementById('mpAskText').textContent,
        ok: document.getElementById('mpAskOk').textContent, calls: __fmk.calls.filter(c => /^gfontsDelete:/.test(c)), view: gf.view, fam: gf.fam, dl: !!gf.dls.Lato };
    });
    await page.click('#gfDelBtn'); await page.waitForTimeout(150);
    let q = await ask();
    check('   Delete asks first, over the sheet: "Delete Lato?", it cannot be downloaded again; nothing deleted yet', q.show && q.top && q.title === 'Delete Lato?' && q.ok === 'Delete' && !q.calls.length
      && q.text === 'This family is no longer on Google Fonts, so it cannot be downloaded again. Save to phone first to keep a copy.', JSON.stringify(q));
    await page.click('#mpAskNo'); await page.waitForTimeout(150);
    q = await ask();
    check('   Cancel: nothing deleted, its page stays', !q.show && !q.calls.length && q.view === 'detail' && q.fam === 'Lato' && q.dl, JSON.stringify(q));
    await page.click('#gfDelBtn'); await page.waitForTimeout(150);
    await page.click('#mpAskOk'); await page.waitForTimeout(150);
    const del = await page.evaluate(() => ({ calls: __fmk.calls.filter(c => /^gfontsDelete:/.test(c)), view: gf.view, list: getComputedStyle(document.getElementById('gfListView')).display !== 'none', title: document.getElementById('gfTitle').textContent,
      cards: document.querySelectorAll('#gfList .gf-card').length, empty: document.getElementById('gfList').textContent.replace(/\s+/g, ' ').trim(), count: document.getElementById('gfCount').textContent, toast: document.getElementById('toast').textContent.trim(), lato: gf.by.has('Lato') }));
    check('   Delete: gfontsDelete, back to the list, its card gone ("No fonts match.", "0 of 63")', del.calls.join(',') === 'gfontsDelete:Lato' && del.view === 'list' && del.list && del.title === 'Google Fonts' && !del.cards && /^No fonts match\./.test(del.empty)
      && del.count === '0 of 63' && del.toast === 'Deleted Lato' && !del.lato, JSON.stringify(del));
    const fo = await page.evaluate(() => { const a = document.activeElement; return { text: a.textContent.trim(), inList: !!a.closest('#gfList') }; });
    check('   the focus on the empty list\'s Clear filters (its card is gone), not left on the closed question', fo.text === 'Clear filters' && fo.inList, JSON.stringify(fo));
    await page.evaluate(() => gfClose()); await page.waitForTimeout(100);
    const c1 = await counts();
    check('   the search button counts 63 again', c1.btn === '63 families', JSON.stringify(c1));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the tab shown with a list on the phone that no longer has a downloaded family: the search button counts it from the start
  {
    const REMOTE = Object.assign({}, CAT, { families: CAT.families.filter(e => e.f !== 'Lato') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: REMOTE, remote: CAT, downloads: ['Lato'] } }, true);
    const btn = await page.evaluate(() => document.getElementById('gfOpenCount').textContent);
    await page.click('#gfOpenBtn'); await page.waitForTimeout(300);
    const sheet = await page.evaluate(() => document.getElementById('gfCount').textContent);
    check('13b. the tab shown, 63 families listed and Lato on the phone: the button says "64 families", as the sheet', btn === '64 families' && sheet === '64 families', JSON.stringify([btn, sheet]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a refreshed list that drops the family whose page is open: back to the list when it is not on the phone (its page has nothing left to show)
  {
    const REMOTE = Object.assign({}, CAT, { families: CAT.families.filter(e => e.f !== 'Arimo') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, stale: true, remote: REMOTE, hold: true } });
    await page.click('#gfList .gf-card[data-f="Arimo"]'); await page.waitForTimeout(200);
    const view = () => page.evaluate(() => ({ view: gf.view, title: document.getElementById('gfTitle').textContent, list: getComputedStyle(document.getElementById('gfListView')).display !== 'none',
      detail: getComputedStyle(document.getElementById('gfDetail')).display !== 'none', arimo: !!document.querySelector('#gfList .gf-card[data-f="Arimo"]'), count: document.getElementById('gfCount').textContent,
      info: __fmk.calls.filter(c => /^gfontsInfo:/.test(c)) }));
    let v = await view();
    check('14. Arimo\'s page open while the stale list updates (its details asked)', v.view === 'detail' && v.title === 'Arimo' && v.detail && v.info.join(',') === 'gfontsInfo:Arimo', JSON.stringify(v));
    await step(page, 1);
    v = await view();
    check('   the new list has no Arimo: back to the list, its card gone, "63 families"', v.view === 'list' && v.title === 'Google Fonts' && v.list && !v.detail && !v.arimo && v.count === '63 families', JSON.stringify(v));
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    v = await view();
    check('   its details answer late: the list stays', v.view === 'list' && v.list && !v.detail, JSON.stringify(v));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the same with a family on the phone: its page stays, and says at once that it left Google Fonts (its note; no link to fonts.google.com)
  {
    const REMOTE = Object.assign({}, CAT, { families: CAT.families.filter(e => e.f !== 'Lato') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, remote: REMOTE, downloads: ['Lato'], hold: true } });
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(200);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);          // its details first: the link to fonts.google.com
    const pg = () => page.evaluate(() => ({ view: gf.view, title: document.getElementById('gfTitle').textContent, note: (document.getElementById('gfGoneNote') || {}).textContent || '', site: !!document.getElementById('gfOpenSite'),
      btns: ['gfUseBtn', 'gfSaveBtn', 'gfDelBtn'].filter(id => !!document.getElementById(id)).length }));
    let p = await pg();
    const a0 = await about(page);
    check('15. Lato\'s page (on the phone), its details came: the link, no note; About with its description and Licence text', p.view === 'detail' && p.site && !p.note && a0.extra.join(',') === 'gfDesc,gfDescMore,gfLic', JSON.stringify([p, a0]));
    await page.evaluate(() => gfRefresh(true)); await page.waitForTimeout(50);
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    p = await pg();
    check('   Refresh brings a list without Lato: the page stays, with the note, without the link; Use, Save and Delete', p.view === 'detail' && p.title === 'Lato' && p.note === 'This family is no longer on Google Fonts. Its downloaded files still work here.' && !p.site && p.btns === 3, JSON.stringify(p));
    const a = await about(page);
    check('   About: only the licence its download recorded; the details from before it left (description, Licence text, designer) are not shown', a.rows === 'Licence SIL Open Font License 1.1' && !a.extra.length, JSON.stringify([a, a0]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a family's details asked while it was listed, answered (an error) once a refreshed list no longer has it: its page, and its page opened again, show
  // only the licence its download recorded (no reason, no Retry: Google Fonts no longer knows it)
  {
    const REMOTE = Object.assign({}, CAT, { families: CAT.families.filter(e => e.f !== 'Lato') });
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, stale: true, remote: REMOTE, downloads: ['Lato'], hold: true,
      infoError: { code: 'gf_unknown', error: 'This family is not in the Google Fonts list. Update the list and try again.' } } });
    await page.click('#gfList .gf-card[data-f="Lato"]'); await page.waitForTimeout(200);
    const asked = await calls(page, '^gfontsInfo:');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(200);           // the refresh (no Lato) and the details' error
    const note = () => page.evaluate(() => (document.getElementById('gfGoneNote') || {}).textContent || '');
    let a = await about(page), n = await note();
    check('15b. Lato\'s details asked, then the list without it and their error: the note; About only the licence, no reason or Retry', asked.join(',') === 'gfontsInfo:Lato' && !!n && a.rows === 'Licence SIL Open Font License 1.1' && !a.extra.length, JSON.stringify([asked, n, a]));
    await page.evaluate(() => gfBackToList()); await page.waitForTimeout(150);
    await showFam(page, 'Lato');
    a = await about(page); n = await note();
    check('   its page opened again: the same (the error from before it left is not shown)', !!n && a.rows === 'Licence SIL Open Font License 1.1' && !a.extra.length, JSON.stringify([n, a]));
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
  // the Help Guide's example: the progress counts a file's bytes as they come over the network (packed by Google Fonts, against their Content-Length),
  // so its sizes are not the file's on the phone, and "Downloaded · N files · X" is not their sum
  {
    const g = fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8'), H = JSON.parse(g.slice(g.indexOf('{'), g.indexOf('};\n') + 1));
    const html = (H.sections.find(x => x.id === 'fonts-google') || {}).html || '', said = (/File 1 of 2 · (\d+) KB of (\d+) KB"?: ([^)]*)\)/.exec(html) || []), have = /Downloaded · 2 files · (\d+) KB/.exec(html);
    check('16. the Help Guide: the progress sizes are a file\'s as it comes over the network, the "Downloaded" size the space on the phone (not their sum)', said.length && /over the network/.test(said[3]) && !/sizes are those of that file, and they start/.test(html)
      && !!have && +have[1] !== 2 * +said[2], JSON.stringify([said[3], have && have[0]]));
    // its undo table ("Every change this tab makes can be undone") has a row for a family downloaded here: Delete on its page, the question a family
    // no longer on Google Fonts asks first
    const undo = (H.sections.find(x => x.id === 'fonts-undo') || {}).html || '', urow = (/<tr><td>[^<]*(?:<a [^>]*>)?Google Fonts[\s\S]*?<\/tr>/.exec(undo) || [''])[0];
    check('   the undo table has a row for a downloaded Google Fonts family: Delete on its page; a family no longer on Google Fonts asks first', /<b>Delete<\/b> on the family's page \(<b>Search Google Fonts<\/b>/.test(urow)
      && /<b>No longer on Google Fonts<\/b> asks first/.test(urow) && /<b>Save to phone<\/b>/.test(urow), urow || undo.slice(0, 300));
  }
  // a family whose details cannot be had (no connection): About's Licence is its download's, so it comes with a download made on its page and goes
  // with a Delete there, without opening the page again
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true, infoError: { code: 'network', error: 'The download failed: timeout' } } });
    const aboutLic = () => page.evaluate(() => ({ rows: [...document.querySelectorAll('#gfAbout .fnt-now')].map(r => [...r.children].map(c => c.textContent.trim()).join(' ')).filter(t => /^Licence /.test(t)),
      err: !!document.getElementById('gfInfoErr'), retry: !!document.getElementById('gfInfoRetry'), lic: !!document.getElementById('gfLic'), dl: !!document.getElementById('gfDlBtn') }));
    await showFam(page, 'Lato');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    let a = await aboutLic();
    check('17. Lato, not on the phone, its details not to be had: the reason and Retry, no Licence row', a.err && a.retry && !a.rows.length && a.dl, JSON.stringify(a));
    await page.click('#gfDlBtn');
    await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150);
    a = await aboutLic();
    check('   downloaded on its page: "Licence SIL Open Font License 1.1" from the download (no Licence text); the reason and Retry stay', a.rows.join('|') === 'Licence SIL Open Font License 1.1' && !a.lic && a.err && a.retry, JSON.stringify(a));
    await page.click('#gfDelBtn'); await page.waitForTimeout(150);
    a = await aboutLic();
    check('   deleted there: Download, and the Licence row goes', a.dl && !a.rows.length, JSON.stringify(a));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // About with a keyboard (switch access, TalkBack): a focus in it is not lost to the body behind the sheet when it is drawn again. The details that
  // come unasked leave it on Open on fonts.google.com; Retry turns into "Getting the details…", which About holds (and reads) until the reason
  // comes (Retry again) or the details (the first control: More); More goes once pressed, the description it opened has it; a newer list keeps
  // it on the description, or on Licence text, which stays open
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true, infoError: { code: 'network', error: 'The download failed: timeout' } } });
    const fo = () => page.evaluate(() => { const a = document.activeElement; return { id: a ? a.id || a.tagName : '', text: a ? a.textContent.replace(/\s+/g, ' ').trim().slice(0, 80) : '' }; });
    const enter = async (id) => { await page.focus('#' + id); await page.keyboard.press('Enter'); await page.waitForTimeout(100); return fo(); };
    const answer = async () => { await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150); return fo(); };
    await showFam(page, 'Lato');
    await page.focus('#gfOpenSite');
    let f = await answer();
    check('18. a keyboard on Open on fonts.google.com while the details come (here their reason): About drawn again, the focus stays on the link', f.id === 'gfOpenSite'
      && (await about(page)).extra.join(',') === 'gfInfoErr,gfInfoRetry', JSON.stringify([f, await about(page)]));
    f = await enter('gfInfoRetry');
    check('   Retry: About holds the focus (read: "Getting the details from Google Fonts…"), not the body', f.id === 'gfAbout' && /Getting the details from Google Fonts…/.test(f.text), JSON.stringify(f));
    f = await answer();
    check('   they fail again: the focus on the new Retry', f.id === 'gfInfoRetry', JSON.stringify(f));
    await page.evaluate(() => { __fmk.gfInfoError = null; });
    const f1 = await enter('gfInfoRetry'), f2 = await answer();
    check('   Retry, and the details come: About, then its first control (More, Lato\'s description is long)', f1.id === 'gfAbout' && f2.id === 'gfDescMore'
      && (await about(page)).extra.join(',') === 'gfDesc,gfDescMore,gfLic', JSON.stringify([f1, f2, await about(page)]));
    f = await enter('gfDescMore');
    const desc = await page.evaluate(() => ({ open: !document.getElementById('gfDesc').classList.contains('gf-clamp'), more: getComputedStyle(document.getElementById('gfDescMore')).display }));
    check('   More: the whole description, More gone, the focus on the description it opened', f.id === 'gfDesc' && desc.open && desc.more === 'none', JSON.stringify([f, desc]));
    // a newer list while the page shows: gfListCame draws About again, and asks for its details again. The older list's stay shown until the
    // newer ones come (no "Getting the details…"), so the description and Licence text are there for the focus all along
    const newer = async () => { await page.evaluate(() => gfRefresh(true)); return answer(); };
    const asked = () => page.evaluate(() => __fmk.calls.filter(c => c === 'gfontsInfo:Lato').length);
    let n0 = await asked();
    await page.evaluate(() => gfRefresh(true)); await step(page, 1);
    let mid = [await fo(), (await about(page)).extra.join(','), await asked() - n0];
    check('   a newer list, its details still on their way: About keeps the older ones (no "Getting the details…"), the focus on the description', mid[0].id === 'gfDesc'
      && mid[1] === 'gfDesc,gfDescMore,gfLic' && mid[2] === 1, JSON.stringify(mid));
    f = await answer();
    check('   a newer list draws About again: the focus stays on the description (still open)', f.id === 'gfDesc' && await page.evaluate(() => !document.getElementById('gfDesc').classList.contains('gf-clamp')), JSON.stringify(f));
    // Licence text opened (Enter on it): the newer list, its details asked again and their answer draw About three times. It stays open, as the description
    // does, and keeps the focus
    const licOpen = () => page.evaluate(() => !!document.getElementById('gfLic') && document.getElementById('gfLic').open);
    f = await enter('gfLicSum');
    const opened = await licOpen();
    f = await newer();
    check('   and on Licence text, opened: it stays open', opened && f.id === 'gfLicSum' && await licOpen(), JSON.stringify([opened, f]));
    // the newer list's details cannot be had (no connection right after it): the older ones stay, with no reason or Retry, and the focus with
    // them. They are still the older list's, so the page opened again asks once more
    await page.evaluate(() => { __fmk.gfInfoError = { code: 'network', error: 'The download failed: timeout' }; });
    f = await newer();
    mid = [f, (await about(page)).extra.join(','), await page.evaluate(() => !!(gf.info.Lato || {}).stale)];
    check('   a newer list whose details cannot be had: About keeps the older ones (no reason, no Retry), the focus on Licence text (still open)', f.id === 'gfLicSum'
      && mid[1] === 'gfDesc,gfDescMore,gfLic' && mid[2] && await licOpen(), JSON.stringify(mid));
    await page.evaluate(() => { __fmk.gfInfoError = null; });
    // refused at once (another window of the app is getting them: busy): the same, the older ones stay (still stale), no reason or Retry. Set once
    // the refresh has started, which another window's would refuse too
    await page.evaluate(() => gfRefresh(true));
    await page.evaluate(() => { __fmk.gfOtherWindow = true; });
    f = await answer();
    mid = [f, (await about(page)).extra.join(','), await page.evaluate(() => { const I = gf.info.Lato || {}; return [!!I.ok, !!I.stale, !!I.err]; })];
    check('   a newer list whose details are refused at once (busy): About keeps the older ones (no reason, no Retry), the focus on Licence text', f.id === 'gfLicSum'
      && mid[1] === 'gfDesc,gfDescMore,gfLic' && mid[2].join(',') === 'true,true,false' && await licOpen(), JSON.stringify(mid));
    await page.evaluate(() => { __fmk.gfOtherWindow = false; });
    n0 = await asked();
    await showFam(page, 'Lato');
    mid = [(await about(page)).extra.join(','), await asked() - n0, await licOpen()];
    f = await answer();
    check('   Lato opened again: About shows them at once and asks once more; the newer ones come', mid[0] === 'gfDesc,gfDescMore,gfLic' && mid[1] === 1
      && await page.evaluate(() => { const I = gf.info.Lato || {}; return I.ok && !I.stale && !I.refreshing; }), JSON.stringify([mid, f]));
    check('   a family\'s page opened again starts with its Licence text closed, as its description', !mid[2] && !await licOpen(), JSON.stringify(mid));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // 19. A long Licence text scrolls on its own: a keyboard reaches it (Tab after Licence text), and a newer list drawing About again keeps the focus
  // on it and where it was read to. A slider of the page (a variable family's axis) keeps the focus when the newer list changes the family's
  // styles (the sliders drawn again from the axes' defaults); one that no longer has axes gives it to the size slider
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, hold: true, licenceLines: 90 } });
    const fo = () => page.evaluate(() => { const a = document.activeElement; return a ? a.id || a.tagName : ''; });
    const answer = async () => { await page.evaluate(() => __fmk.gfStepAll()); await page.waitForTimeout(150); return fo(); };
    const newer = async () => { await page.evaluate(() => gfRefresh(true)); return answer(); };
    await showFam(page, 'Lato');
    await answer();
    await page.focus('#gfLicSum'); await page.keyboard.press('Enter'); await page.waitForTimeout(100);
    await page.keyboard.press('Tab'); await page.waitForTimeout(100);
    let f = await fo();
    // no name of its own (aria-labelledby would make TalkBack read "Licence text" in its place on Android): the licence itself is read
    const lic = await page.evaluate(() => { const p = document.getElementById('gfLicText'); p.scrollTop = 150; return { room: p.scrollHeight - p.clientHeight, top: p.scrollTop, named: p.hasAttribute('aria-labelledby') || p.hasAttribute('aria-label') }; });
    check('19. Licence text opened, Tab: the focus on the text itself (it scrolls: a keyboard can read all of it), with no name in place of the text', f === 'gfLicText'
      && lic.room > 150 && lic.top === 150 && !lic.named, JSON.stringify([f, lic]));
    f = await newer();
    const top = await page.evaluate(() => { const p = document.getElementById('gfLicText'); return p ? p.scrollTop : -1; });
    check('   a newer list draws About again: the focus stays on the text, read to where it was', f === 'gfLicText' && Math.abs(top - 150) <= 1, JSON.stringify([f, top]));
    // Back, another family whose details came before (About drawn at once), and Lato again: each page's Licence text starts closed and at its top
    const licTop = async () => { await page.click('#gfLicSum'); await page.waitForTimeout(100); return page.evaluate(() => document.getElementById('gfLicText').scrollTop); };
    await showFam(page, 'Inter'); await answer(); await page.evaluate(() => gfBackToList());
    await showFam(page, 'Lato');
    const shut = await page.evaluate(() => !document.getElementById('gfLic').open);
    const t1 = await licTop();
    await page.evaluate(() => { document.getElementById('gfLicText').scrollTop = 300; });
    await showFam(page, 'Inter');
    const t2 = await licTop();
    check('   Back, Lato again, then Inter again (their details came before): each page\'s Licence text closed, and opened at its top, not where the last page left it', shut && t1 === 0 && t2 === 0, JSON.stringify([shut, t1, t2]));
    // Cairo: wght 200-1000 from 400, and slnt
    await showFam(page, 'Cairo');
    await answer();
    await page.focus('#gfAx_wght'); await page.keyboard.press('ArrowRight'); await page.waitForTimeout(100);
    const moved = await page.evaluate(() => document.getElementById('gfAx_wght').value);
    const cat2 = JSON.parse(JSON.stringify(CAT)); cat2.families.find(e => e.f === 'Cairo').w.push('200i');
    await page.evaluate((c) => { __fmk.gfRemote = c; }, cat2);
    f = await newer();
    const ax = await page.evaluate(() => ({ v: (document.getElementById('gfAx_wght') || {}).value, n: document.querySelectorAll('#gfDAxes input').length }));
    check('   a slider moved (wght), and a newer list changes Cairo\'s styles: the sliders from the defaults, the focus still on wght', moved !== '400' && f === 'gfAx_wght'
      && ax.v === '400' && ax.n === 2, JSON.stringify([moved, f, ax]));
    const cat3 = JSON.parse(JSON.stringify(cat2)); cat3.families.find(e => e.f === 'Cairo').x = [];
    await page.evaluate((c) => { __fmk.gfRemote = c; }, cat3);
    f = await newer();
    check('   one more that leaves Cairo with no axes: no sliders, the focus on the size slider', f === 'gfDSize' && !(await page.evaluate(() => document.querySelectorAll('#gfDAxes input').length)), f);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
