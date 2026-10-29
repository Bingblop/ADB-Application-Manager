// v7.17.0 Custom Fonts, the privileged cards when the working mode changes or a status or a change fails (v7.19.1: the Phone font card). A switch
// from Root to the system user while the changer's status is being asked: the tag follows the mode at once (SYSTEM USER, never root), the answer
// asked under Root is dropped and the status asked again (the drop-down says "Checking…" until it answers); System emoji locks. A status that does not
// answer (no_answer): the drop-down says "Choose a font" and stays on, no font in the list is marked In use, the reason shows with Try again; Try again
// asks once more and the error goes; with the font unknown again, Default in the list still resets (the way back from a font that went wrong). A
// Samsung without /data/app_fonts: the card says so (SAMSUNG ONLY, the note on the font folder, no drop-down), the font just built has Use it and
// Install off and Export on. A change that fails still shows what the phone read back: Reset (Default chosen in the list; sans.loc as it is), the
// system emoji swap (one file changed, one not: "Some files did not change", ✓ and ✕) and its Undo; the error of a change stays when the status asked
// after it answers, and the tab shown again or another working mode takes the read-back box away with its error, at once even while that status is
// still being asked. What a status found about /data/app_fonts stays while the next one is asked, and without a mode.
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
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const cards = (page) => page.evaluate(() => {
  const t = id => { const e = document.getElementById(id); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; };
  const on = id => { const e = document.getElementById(id); return e ? !e.disabled : null; };
  const acts = [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].map(x => x.textContent.trim() + (x.disabled ? ':off' : ':on')).join('|');
  const note = document.getElementById('fntDeviceNote'), pick = document.getElementById('fntPhonePick');
  return { cTag: t('fntChangerTag'), sTag: t('fntSysTag'), cBody: t('fntChangerBody'), sBody: t('fntSysBody'), pick: pick ? pick.textContent.replace(/[▾\s]+$/, '').trim() : null, pickOff: pick ? pick.disabled : null,
    errs: [...document.querySelectorAll('#fntChangerBody .fnt-err')].map(e => e.textContent.replace(/\s+/g, ' ').trim()).join(' | '), rb: !!document.querySelector('#fntChangerBody .fnt-rb'),
    again: on('fntChangerAgain'), sysAgain: on('fntSysAgain'), undo: on('fntSysUndo'), acts, note: getComputedStyle(note).display !== 'none' ? note.textContent : '' };
});
// the Phone font list (#fntPhoneSheet): opened by the drop-down; a tap on a row uses that font (data-k: 'default' or the package)
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const listRows = (page) => page.evaluate(() => [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => ({ k: r.dataset.k, cur: r.classList.contains('current'), lock: !!r.querySelector('.fnt-pr-lock') })));
const choose = async (page, k) => { await openList(page); await page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]').click(); await page.waitForTimeout(50); };
const NO_FOLDER = "This phone does not have Samsung's font folder (/data/app_fonts), so its font packages are not used here.";
const statusCalls = (page) => page.evaluate(() => window.__fmk.calls.filter(x => x === 'fontMakerChangerStatus').length);
const switchMode = (page, m) => page.evaluate(m => { window.__fmk.setMode(m); checkAllWorkingModes(false, true); }, m);

(async () => {
  const b = await chromium.launch();
  {
    // ---- 1. Root -> the system user while the status is being asked ----
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true });
    const c0 = await cards(page);
    check('1. Root: ROOT, the status being asked ("Checking…")', c0.cTag === 'ROOT' && c0.pick === 'Checking…' && await statusCalls(page) === 1, JSON.stringify([c0.cTag, c0.cBody]));
    await switchMode(page, 'system');
    await page.waitForTimeout(50);
    const c1 = await cards(page);
    check('   switched to the system user: the tag follows at once (SYSTEM USER), System emoji locks', c1.cTag === 'SYSTEM USER' && c1.sTag === 'LOCKED' && !/\broot\b/i.test(c1.cBody), JSON.stringify([c1.cTag, c1.sTag, c1.cBody]));
    await page.evaluate(() => window.__fmk.step(2));                               // the two answers asked under Root
    await page.waitForTimeout(50);
    check('   the answer asked under Root is dropped and the status asked again', await statusCalls(page) === 2 && (await cards(page)).pick === 'Checking…', await page.evaluate(() => window.__fmk.calls.join()));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    const c2 = await cards(page);
    check('   the new answer: the font in use, still SYSTEM USER', c2.cTag === 'SYSTEM USER' && c2.pick === 'Inter Display', JSON.stringify([c2.cTag, c2.cBody]));
    await switchMode(page, 'system');                                             // the same mode again: nothing is asked again
    await page.waitForTimeout(50);
    check('   the same mode checked again: the status is kept, not asked again', await statusCalls(page) === 2 && (await cards(page)).pick === 'Inter Display');
    await switchMode(page, 'root');
    await page.waitForTimeout(50);
    check('   back to Root: ROOT, the status asked again', (await cards(page)).cTag === 'ROOT' && await statusCalls(page) === 3);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 2. a status that does not answer ----
    const { ctx, page, errors } = await open(b, { mode: 'root', statusFails: 1 });
    const c = await cards(page);
    check('2. no answer: the drop-down says "Choose a font" and stays on, the reason, Try again', c.pick === 'Choose a font' && c.pickOff === false && c.errs === 'The phone did not answer. Try again.' && c.again === true, JSON.stringify(c));
    await openList(page);
    const rows = await listRows(page);
    check('   the list: no font marked In use (the font is unknown), nothing locked', rows.length > 1 && !rows.some(r => r.cur) && !rows.some(r => r.lock), JSON.stringify(rows));
    await page.evaluate(() => fntPhoneClose());
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'changer-no-answer.png' });
    await page.click('#fntChangerAgain');
    await page.waitForTimeout(200);
    const c2 = await cards(page);
    check('   Try again: asked once more, the error and the button go', c2.pick === 'Inter Display' && c2.errs === '' && c2.again === null && await statusCalls(page) === 2, JSON.stringify(c2));
    // the font unknown again (the tab shown again, no answer): Default in the list is the way back from a font that went wrong
    await page.evaluate(() => { window.__fmk.statusFails = 1; switchView('about'); switchView('fonts'); });
    await page.waitForTimeout(300);
    const u = await cards(page);
    await choose(page, 'default');
    await page.waitForTimeout(300);
    const d = await cards(page);
    check('   unknown again: Default in the list still resets (fontMakerReset), the toast', u.pick === 'Choose a font' && /did not answer/.test(u.errs) && await page.evaluate(() => window.__fmk.calls.filter(x => x === 'fontMakerReset').length) === 1
      && await page.evaluate(() => document.getElementById('toastMsg').textContent) === 'The default font is back.' && d.pick === 'Default', JSON.stringify([u, d.pick]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 3. a Samsung without /data/app_fonts ----
    const { ctx, page, errors } = await open(b, { mode: 'root', noAppFonts: true, hold: true });
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    await page.click('#fntPick_regular');                                          // a font made here: its buttons under the build result
    await page.waitForTimeout(300);
    await page.click('#fntBuildBtn');
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    const c = await cards(page);
    check('3. no /data/app_fonts: SAMSUNG ONLY, the note says why, no drop-down', c.cTag === 'SAMSUNG ONLY' && c.note === NO_FOLDER && c.pick === null && c.cBody === '', JSON.stringify([c.cTag, c.note, c.cBody]));
    check('   the font just built: Use it and Install off, Export on', c.acts === 'Use it:off|Install:off|Export:on|Make again:on', c.acts);
    // the mode changes: the status is dropped and asked again, and what the last one found is kept meanwhile (no Install that comes back on in between)
    await switchMode(page, 'system');
    await page.waitForTimeout(50);
    const m = await cards(page);
    check('   the system user, the status asked again and not answered yet: still SAMSUNG ONLY, Install still off', await statusCalls(page) === 2 && m.cTag === 'SAMSUNG ONLY'
      && m.acts === 'Use it:off|Install:off|Export:on|Make again:on' && m.note === NO_FOLDER && m.pick === null, JSON.stringify([m.cTag, m.acts, m.note]));
    await page.evaluate(() => window.__fmk.stepAll());
    await switchMode(page, 'none');
    await page.waitForTimeout(50);
    const n = await cards(page);
    check('   no working mode (nothing can look): what Root found stays, Install off, no One UI advice to use Root', n.acts === 'Use it:off|Install:off|Export:on|Make again:on'
      && n.note === NO_FOLDER && n.pick === null, JSON.stringify([n.acts, n.note]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 4. a Reset that fails: the error and what sans.loc reads ----
    const { ctx, page, errors } = await open(b, { mode: 'root', resetError: { code: 'failed', error: 'cannot reset sans.loc.', readBack: { sansLoc: '/data/app_fonts/0/InterDisplay_3fa9c1#InterDisplay_3fa9c1', files: [] } } });
    await choose(page, 'default');                                                // Default chosen in the list: fontMakerReset
    await page.waitForTimeout(250);
    const c = await cards(page);
    check('4. Default chosen, the Reset fails: the page\'s sentence, sans.loc as the phone reads it', c.errs === 'The phone could not reset sans.loc.' && c.rb && /Read back from the phone: sans\.loc \/data\/app_fonts\/0\/InterDisplay_3fa9c1#InterDisplay_3fa9c1/.test(c.cBody), c.cBody);
    check('   the error stays after the status asked again answers', c.pick === 'Inter Display' && await statusCalls(page) === 2, c.cBody);
    // the tab shown again: the card asks the phone afresh, and the read-back box goes with the error that explained it
    await page.evaluate(() => switchView('about'));
    await page.evaluate(() => switchView('fonts'));
    await page.waitForTimeout(300);
    const r = await cards(page);
    check('   shown again: a fresh status, no read-back box and no error left', r.pick === 'Inter Display' && !r.rb && r.errs === '' && await statusCalls(page) === 3, r.cBody);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 5. the system emoji swap: one file changed, one did not ----
    const rb = [{ target: '/system/fonts/NotoColorEmoji.ttf', sha256: 'd2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e', ok: true },
      { target: '/system/fonts/NotoColorEmojiFlags.ttf', sha256: '7a0c4be1e9f3d2a6b8c5e7f9a1b3d5c7e9f2a4b6c8d0e1f3a5b7c9d2e4f6a8b0', ok: false }];
    const { ctx, page, errors } = await open(b, { mode: 'root', sysApplyError: { code: 'failed', error: 'Some emoji files did not change. Restart the phone to undo the swap.', readBack: rb } });
    await page.click('#fntSysUse');
    await page.waitForTimeout(300);
    const c = await cards(page);
    check('5. a swap that half worked: the error, "Some files did not change.", ✓ and ✕ per file', /Some emoji files did not change\. Restart the phone to undo the swap\./.test(c.sBody) && /Some files did not change\./.test(c.sBody)
      && /NotoColorEmoji\.ttf d2d721abc0ab ✓/.test(c.sBody) && /NotoColorEmojiFlags\.ttf 7a0c4be1e9f3 ✕/.test(c.sBody), c.sBody);
    check('   the status asked after it: the swap is on, Undo is there', /Phone emoji Twemoji/.test(c.sBody) && c.undo === true, JSON.stringify([c.sBody, c.undo]));
    await page.evaluate(() => document.getElementById('fntSysCard').scrollIntoView());
    await page.screenshot({ path: 'sys-half.png' });
    await switchMode(page, 'uid0');                                               // another working mode: both cards ask again
    await page.waitForTimeout(300);
    const r = await cards(page);
    check('   another working mode: the read-back box goes with its error', /Phone emoji Twemoji/.test(r.sBody) && !/Some files did not change/.test(r.sBody) && !/Some emoji files/.test(r.sBody) && !/✓|✕/.test(r.sBody), r.sBody);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 6. an Undo that fails ----
    const { ctx, page, errors } = await open(b, { mode: 'uid0', sysActive: 'twemoji', sysUndoError: { code: 'failed', error: 'The swap could not be undone: umount: /system/fonts/NotoColorEmoji.ttf: Device or resource busy',
      readBack: [{ target: '/system/fonts/NotoColorEmoji.ttf', sha256: 'd2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e', ok: false }] } });
    await page.click('#fntSysUndo');
    await page.waitForTimeout(300);
    const c = await cards(page);
    check('6. Undo fails: the lead in the page\'s words, the shell\'s detail kept, the file read back', /The swap could not be undone: umount: \/system\/fonts\/NotoColorEmoji\.ttf: Device or resource busy/.test(c.sBody)
      && /NotoColorEmoji\.ttf d2d721abc0ab ✕/.test(c.sBody) && /Phone emoji Twemoji/.test(c.sBody) && c.undo === true, c.sBody);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 7. a Reset and a swap that fail, and another working mode before the status asked after them answers ----
    const rb = [{ target: '/system/fonts/NotoColorEmoji.ttf', sha256: 'd2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e', ok: false }];
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true, resetError: { code: 'failed', error: 'cannot reset sans.loc.', readBack: { sansLoc: '/data/app_fonts/0/InterDisplay_3fa9c1#InterDisplay_3fa9c1', files: [] } },
      sysApplyError: { code: 'failed', error: 'Some emoji files did not change. Restart the phone to undo the swap.', readBack: rb } });
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    await choose(page, 'default');                                                // Default chosen in the list: fontMakerReset
    await page.click('#fntSysUse');
    await page.evaluate(() => window.__fmk.step(2));                               // the two changes end; the status asked after each waits
    await page.waitForTimeout(50);
    const c = await cards(page);
    check('7. both fail: each card has its error and its read-back box, the status asked after them not answered yet', c.errs === 'The phone could not reset sans.loc.' && c.rb && /Read back from the phone/.test(c.cBody)
      && /Some emoji files did not change/.test(c.sBody) && /✕/.test(c.sBody) && await page.evaluate(() => fnt.changerBusy === 'status' && fnt.sysBusy === 'status'), JSON.stringify([c.cBody, c.sBody]));
    await switchMode(page, 'uid0');
    await page.waitForTimeout(50);
    const m = await cards(page);
    check('   another working mode meanwhile: the boxes go with their errors at once', !m.rb && m.errs === '' && !/could not reset|Read back from the phone/.test(m.cBody) && !/Some emoji files|Some files did not change|✓|✕/.test(m.sBody), JSON.stringify([m.cBody, m.sBody]));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    const r = await cards(page);
    check('   the status asked again answers: the cards show it, still no error or box', r.pick === 'Inter Display' && !r.rb && r.errs === ''
      && /Phone emoji/.test(r.sBody) && !/Some emoji files|Some files did not change|✓|✕/.test(r.sBody), JSON.stringify([r.cBody, r.sBody]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
