// v7.17.0 Custom Fonts, the privileged cards when the working mode changes or a status or a change fails. A switch from Root to the system user
// while the changer's status is being asked: the tag follows the mode at once (SYSTEM USER, never root), the answer asked under Root is dropped and
// the status asked again; System emoji locks. A status that does not answer (no_answer): the phone font is Unknown, the reason shows with Try again,
// and Reset to Default stays on (the way back from a font that went wrong); Try again asks once more and the error goes. A Samsung without
// /data/app_fonts: the changer says so (SAMSUNG ONLY), My fonts says its packages are not used here, Install and Apply are off, Export stays. A change
// that fails still shows what the phone read back: Reset (sans.loc as it is), the system emoji swap (one file changed, one not: "Some files did not
// change", ✓ and ✕) and its Undo; the error of a change stays when the status asked after it answers, and the tab shown again or another working
// mode takes the read-back box away with its error, at once even while that status is still being asked. What a status found about /data/app_fonts
// stays while the next one is asked, and without a mode.
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
  const rows = [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => [...r.querySelectorAll('.mode-action-btn')].map(x => x.textContent.trim() + (x.disabled ? ':off' : ':on')).join('|'));
  const note = document.getElementById('fntDeviceNote');
  return { cTag: t('fntChangerTag'), sTag: t('fntSysTag'), cBody: t('fntChangerBody'), sBody: t('fntSysBody'), reset: on('fntResetBtn'), again: on('fntChangerAgain'), sysAgain: on('fntSysAgain'),
    undo: on('fntSysUndo'), rows, note: getComputedStyle(note).display !== 'none' ? note.textContent : '' };
});
const statusCalls = (page) => page.evaluate(() => window.__fmk.calls.filter(x => x === 'fontMakerChangerStatus').length);
const switchMode = (page, m) => page.evaluate(m => { window.__fmk.setMode(m); checkAllWorkingModes(false, true); }, m);

(async () => {
  const b = await chromium.launch();
  {
    // ---- 1. Root -> the system user while the status is being asked ----
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true });
    const c0 = await cards(page);
    check('1. Root: ROOT, the status being asked ("Checking…")', c0.cTag === 'ROOT' && /Phone font Checking…/.test(c0.cBody) && await statusCalls(page) === 1, JSON.stringify([c0.cTag, c0.cBody]));
    await switchMode(page, 'system');
    await page.waitForTimeout(50);
    const c1 = await cards(page);
    check('   switched to the system user: the tag follows at once (SYSTEM USER), System emoji locks', c1.cTag === 'SYSTEM USER' && c1.sTag === 'LOCKED' && !/\broot\b/i.test(c1.cBody), JSON.stringify([c1.cTag, c1.sTag, c1.cBody]));
    await page.evaluate(() => window.__fmk.step(2));                               // the two answers asked under Root
    await page.waitForTimeout(50);
    check('   the answer asked under Root is dropped and the status asked again', await statusCalls(page) === 2 && /Phone font Checking…/.test((await cards(page)).cBody), await page.evaluate(() => window.__fmk.calls.join()));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    const c2 = await cards(page);
    check('   the new answer: the font in use, still SYSTEM USER', c2.cTag === 'SYSTEM USER' && /Phone font Inter Display/.test(c2.cBody), JSON.stringify([c2.cTag, c2.cBody]));
    await switchMode(page, 'system');                                             // the same mode again: nothing is asked again
    await page.waitForTimeout(50);
    check('   the same mode checked again: the status is kept, not asked again', await statusCalls(page) === 2 && /Phone font Inter Display/.test((await cards(page)).cBody));
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
    check('2. no answer: Unknown, the reason, Try again, Reset to Default on', /Phone font Unknown/.test(c.cBody) && /The phone did not answer\. Try again\./.test(c.cBody) && c.again === true && c.reset === true, JSON.stringify(c));
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'changer-no-answer.png' });
    await page.click('#fntChangerAgain');
    await page.waitForTimeout(200);
    const c2 = await cards(page);
    check('   Try again: asked once more, the error and the button go', /Phone font Inter Display/.test(c2.cBody) && !/did not answer/.test(c2.cBody) && c2.again === null && await statusCalls(page) === 2, JSON.stringify(c2));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 3. a Samsung without /data/app_fonts ----
    const { ctx, page, errors } = await open(b, { mode: 'root', noAppFonts: true, hold: true });
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    const c = await cards(page);
    check('3. no /data/app_fonts: SAMSUNG ONLY, the card says why', c.cTag === 'SAMSUNG ONLY' && c.cBody === "The font changer needs Samsung's font folder (/data/app_fonts), which this phone does not have.", JSON.stringify([c.cTag, c.cBody]));
    check('   My fonts says its packages are not used here', c.note === "This phone does not have Samsung's font folder (/data/app_fonts), so its font packages are not used here. You can still export them.", c.note);
    check('   Install and Apply off, Export on', c.rows.every(r => /(Install|Uninstall|Update):off/.test(r) || /Uninstall:on/.test(r)) && c.rows.every(r => /Apply:off/.test(r) && /Export:on/.test(r)), JSON.stringify(c.rows));
    // the mode changes: the status is dropped and asked again, and what the last one found is kept meanwhile (no Install that comes back on in between)
    await switchMode(page, 'system');
    await page.waitForTimeout(50);
    const m = await cards(page);
    check('   the system user, the status asked again and not answered yet: still SAMSUNG ONLY, Install still off', await statusCalls(page) === 2 && m.cTag === 'SAMSUNG ONLY'
      && m.rows[1] === 'Install:off|Apply:off|Export:on|Delete:on|Make again:on' && /does not have Samsung's font folder/.test(m.note), JSON.stringify([m.cTag, m.rows, m.note]));
    await page.evaluate(() => window.__fmk.stepAll());
    await switchMode(page, 'none');
    await page.waitForTimeout(50);
    const n = await cards(page);
    check('   no working mode (nothing can look): what Root found stays, Install off, no advice to use Apply', n.rows[1] === 'Install:off|Apply:off|Export:on|Delete:on|Make again:on'
      && /does not have Samsung's font folder/.test(n.note) && !/Use Apply instead/.test(n.note), JSON.stringify([n.rows, n.note]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 4. a Reset that fails: the error and what sans.loc reads ----
    const { ctx, page, errors } = await open(b, { mode: 'root', resetError: { code: 'failed', error: 'cannot reset sans.loc.', readBack: { sansLoc: '/data/app_fonts/0/InterDisplay_3fa9c1#InterDisplay_3fa9c1', files: [] } } });
    await page.click('#fntResetBtn');
    await page.waitForTimeout(300);
    const c = await cards(page);
    check('4. Reset fails: the page\'s sentence, sans.loc as the phone reads it', /The phone could not reset sans\.loc\./.test(c.cBody) && /Read back from the phone: sans\.loc \/data\/app_fonts\/0\/InterDisplay_3fa9c1#InterDisplay_3fa9c1/.test(c.cBody), c.cBody);
    check('   the error stays after the status asked again answers', /Phone font Inter Display/.test(c.cBody) && await statusCalls(page) === 2, c.cBody);
    // the tab shown again: the card asks the phone afresh, and the read-back box goes with the error that explained it
    await page.evaluate(() => switchView('about'));
    await page.evaluate(() => switchView('fonts'));
    await page.waitForTimeout(300);
    const r = await cards(page);
    check('   shown again: a fresh status, no read-back box and no error left', /Phone font Inter Display/.test(r.cBody) && !/Read back from the phone/.test(r.cBody) && !/could not reset/.test(r.cBody)
      && await statusCalls(page) === 3, r.cBody);
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
    await page.click('#fntResetBtn');
    await page.click('#fntSysUse');
    await page.evaluate(() => window.__fmk.step(2));                               // the two changes end; the status asked after each waits
    await page.waitForTimeout(50);
    const c = await cards(page);
    check('7. both fail: each card has its error and its read-back box, the status asked after them not answered yet', /could not reset sans\.loc/.test(c.cBody) && /Read back from the phone/.test(c.cBody)
      && /Some emoji files did not change/.test(c.sBody) && /✕/.test(c.sBody) && await page.evaluate(() => fnt.changerBusy === 'status' && fnt.sysBusy === 'status'), JSON.stringify([c.cBody, c.sBody]));
    await switchMode(page, 'uid0');
    await page.waitForTimeout(50);
    const m = await cards(page);
    check('   another working mode meanwhile: the boxes go with their errors at once', !/could not reset|Read back from the phone/.test(m.cBody) && !/Some emoji files|Some files did not change|✓|✕/.test(m.sBody), JSON.stringify([m.cBody, m.sBody]));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(50);
    const r = await cards(page);
    check('   the status asked again answers: the cards show it, still no error or box', /Phone font Inter Display/.test(r.cBody) && !/could not reset|Read back from the phone/.test(r.cBody)
      && /Phone emoji/.test(r.sBody) && !/Some emoji files|Some files did not change|✓|✕/.test(r.sBody), JSON.stringify([r.cBody, r.sBody]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
