// v7.17.0 Custom Fonts: making a font of My fonts again, and one chooser or save screen at a time. "Make again" on a row opens the Make card on that
// font ("Making Inter Display again", its name filled in, New font to go back); the build sends rebuildId, and while it runs the row waits ("Making…",
// its other buttons off). The font made again keeps its id and package and takes the next version, so the copy on the phone (version 2, in use) is
// older and the row offers Update. New font drops the link; deleting the font being made again drops it too. The file chooser: Choose is off while it is open
// and a second pick sends nothing; Build is off until the file chosen is read (a Change with a Regular chosen), so it never builds the one before.
// The save screen: Export is off on every row until it answers.
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
const row = (page, id) => page.evaluate(id => {
  const r = document.getElementById('fntLib_' + id);
  if (!r) return null;
  const btns = {}; r.querySelectorAll('.mode-action-btn').forEach(x => { btns[x.textContent.trim()] = !x.disabled; });
  return { text: r.innerText.replace(/\s+/g, ' ').trim(), btns };
}, id);
const remake = (page) => page.evaluate(() => { const e = document.getElementById('fntRemake'); return e.innerText.replace(/\s+/g, ' ').trim(); });
const clickBtn = (page, id, label) => page.evaluate(([id, l]) => [...document.querySelectorAll('#fntLib_' + id + ' .mode-action-btn')].find(x => x.textContent.trim() === l).click(), [id, label]);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true, picks: { regular: 'static' } });
    const r0 = await row(page, 'f1a2b3');
    check('1. every row has Make again', r0.btns['Make again'] === true && (await row(page, 'c4d5e6')).btns['Make again'] === true, JSON.stringify(r0));
    await clickBtn(page, 'f1a2b3', 'Make again');
    await page.waitForTimeout(150);
    check('   the Make card is on that font: "Making Inter Display again", New font', /^Making Inter Display again Choose its files again\. It keeps its package, so it installs as an update\. New font$/.test(await remake(page)), await remake(page));
    check('   its name is filled in, its Make again is off', await page.inputValue('#fntName') === 'Inter Display' && (await row(page, 'f1a2b3')).btns['Make again'] === false && (await row(page, 'c4d5e6')).btns['Make again'] === true);
    await page.evaluate(() => document.getElementById('fntMakeCard').scrollIntoView());
    await page.screenshot({ path: 'remake.png' });
    await page.click('#fntPick_regular');
    await page.waitForTimeout(150);
    check('   a Regular chosen keeps the name', await page.inputValue('#fntName') === 'Inter Display', await page.inputValue('#fntName'));
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    const req = await page.evaluate(() => window.__fmk.requests[window.__fmk.requests.length - 1]);
    check('2. Build sends rebuildId', req && req.rebuildId === 'f1a2b3' && req.name === 'Inter Display', JSON.stringify(req));
    const busy = await row(page, 'f1a2b3');
    check('   the row waits: "Making…", its other buttons off', busy.btns['Making…'] === false && !busy.btns.Uninstall && !busy.btns.Export && !busy.btns.Delete && !busy.btns.Apply, JSON.stringify(busy.btns));
    check('   the other row: Make again off while a font is being made, the rest on', (await row(page, 'c4d5e6')).btns['Make again'] === false && (await row(page, 'c4d5e6')).btns.Export === true, JSON.stringify((await row(page, 'c4d5e6')).btns));
    check('   New font is off while it is being made', await page.evaluate(() => document.getElementById('fntRemakeOff').disabled));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(150);
    check('3. made again: "Inter Display is made again in My fonts."', /^Inter Display is made again in My fonts\./.test(await page.evaluate(() => document.getElementById('fntBuildResult').textContent)), await page.evaluate(() => document.getElementById('fntBuildResult').textContent));
    const after = await row(page, 'f1a2b3');
    check('   the same row, the same package, version 3; the phone has version 2 (in use): Update', /com\.monotype\.android\.font\.InterDisplay_3fa9c1/.test(after.text) && /Version 3/.test(after.text) && /In use/.test(after.text) && after.btns.Update === true,
      JSON.stringify(after));
    check('   still two fonts in My fonts; the Make card is a new font again', await page.evaluate(() => document.querySelectorAll('#fntLibList .fnt-lib').length) === 2 && await remake(page) === '' && after.btns['Make again'] === true);
    // ---- 4. New font, and deleting the font being made again ----
    await clickBtn(page, 'c4d5e6', 'Make again');
    await page.waitForTimeout(100);
    check('4. Make again on Lato', /^Making Lato again/.test(await remake(page)) && await page.inputValue('#fntName') === 'Lato');
    await page.click('#fntRemakeOff');
    await page.waitForTimeout(100);
    check('   New font: the link goes, Lato\'s Make again is on again', await remake(page) === '' && (await row(page, 'c4d5e6')).btns['Make again'] === true);
    await clickBtn(page, 'c4d5e6', 'Make again');
    await page.waitForTimeout(100);
    await clickBtn(page, 'c4d5e6', 'Delete');
    await page.waitForTimeout(250);
    await page.click('#mpAskOk');
    await page.waitForTimeout(250);
    check('   Lato deleted: the Make card is a new font again', await row(page, 'c4d5e6') === null && await remake(page) === '' && await page.evaluate(() => fnt.rebuildId) === '', await remake(page));
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(50);
    const req2 = await page.evaluate(() => window.__fmk.requests[window.__fmk.requests.length - 1]);
    check('   the next build is a new font (rebuildId null)', req2 && req2.rebuildId === null, JSON.stringify(req2));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(150);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 5. one file chooser at a time ----
    const { ctx, page, errors } = await open(b, { mode: 'root', delay: 1500, picks: { regular: 'static' } });
    await page.click('#fntPick_regular');
    await page.waitForTimeout(100);
    check('5. while the chooser is open: Choose is off', await page.evaluate(() => document.getElementById('fntPick_regular').disabled));
    await page.evaluate(() => fntPick('regular'));
    check('   a second pick sends nothing', (await page.evaluate(() => window.__fmk.calls.filter(c => /^fontMakerPick/.test(c)).length)) === 1);
    await page.waitForTimeout(1700);
    check('   the file is read: Change is on', await page.evaluate(() => { const x = document.getElementById('fntPick_regular'); return !x.disabled && x.textContent === 'Change'; }));
    // Change with a Regular chosen: Build waits until the new file is read, so a build never takes the file chosen before
    const buildOn = () => page.evaluate(() => !document.getElementById('fntBuildBtn').disabled);
    check('   a Regular is chosen: Build is on', await buildOn());
    await page.evaluate(() => { window.__fmk.picks.regular = 'vf'; });
    await page.click('#fntPick_regular');
    await page.waitForTimeout(100);
    check('   Change, the new file not read yet: Build is off', !(await buildOn()));
    await page.evaluate(() => fntBuild());
    check('   a build asked for now sends nothing', !(await page.evaluate(() => window.__fmk.calls.includes('fontMakerBuild'))), await page.evaluate(() => window.__fmk.calls.join()));
    await page.waitForTimeout(1700);
    check('   the new file is read: Build is on, for the new file', await buildOn() && await page.evaluate(() => fnt.pick.regular.fileName === 'InterVariable.ttf' && fnt.name === 'Inter Variable'));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 6. one save screen at a time ----
    const { ctx, page, errors } = await open(b, { mode: 'root', holdSave: true });
    await clickBtn(page, 'c4d5e6', 'Export');
    await page.waitForTimeout(100);
    check('6. while the save screen is open: Export is off on every row', (await row(page, 'f1a2b3')).btns.Export === false && (await row(page, 'c4d5e6')).btns.Export === false);
    await page.evaluate(() => window.__fmk.save());
    await page.waitForTimeout(100);
    check('   saved: a toast, Export on again', await page.evaluate(() => document.getElementById('toastMsg').textContent) === 'Saved Lato.apk' && (await row(page, 'f1a2b3')).btns.Export === true && (await row(page, 'c4d5e6')).btns.Export === true);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
