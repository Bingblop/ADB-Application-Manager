// v7.17.0 Custom Fonts: making a font again, and one chooser or save screen at a time (v7.19.1: My fonts is gone; Make again is under the build
// result, for the font just made). "Make again" opens the Make card on that font ("Making Inter Variable again", its name filled in, New font to go
// back) and is off for it; a Regular chosen then keeps the name. The build sends rebuildId, and while it runs the font waits: the build result and its
// buttons are gone, a hold on it in the Phone font list says to wait and a tap sends nothing, New font is off. The font made again keeps its id and
// package and takes the next version ("... is made again"), so the copy on the phone (version 1) is older and the build result offers Update. New font
// drops the link; uninstalling the font being made again (a hold in the list) keeps the link, deleting it from the app drops it and its build result
// (the next build is a new font). The file chooser: Choose is off while it is open and a second pick sends nothing; Build is off until the file
// chosen is read (a Change with a Regular chosen), so it never builds the one before. The save screen: the build result's Export is off until it
// answers, and another font's save asked meanwhile sends nothing.
// While a job of the font being made again runs (its Install, Use it through the font changer, its Export), Build is off, as the bridge would refuse
// that build as busy; a job of another font (the font changer writing Lato, chosen in the Phone font list) leaves it on.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const LATO = 'com.monotype.android.font.Lato_8b21d0';

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
const remake = (page) => page.evaluate(() => { const e = document.getElementById('fntRemake'); return e.innerText.replace(/\s+/g, ' ').trim(); });
// the build result: what it says, and its buttons for the font just made ({ label: on })
const result = (page) => page.evaluate(() => document.getElementById('fntBuildResult').textContent);
const acts = (page) => page.evaluate(() => { const o = {}; document.querySelectorAll('#fntBuildActs .mode-action-btn').forEach(x => { o[x.textContent.trim()] = !x.disabled; }); return o; });
const act = (page, label) => page.click('#fntBuildActs .mode-action-btn:text-is("' + label + '")');
const entry = (page, id) => page.evaluate(id => { const e = window.__fmk.lib.find(x => x.id === id); return e ? { id: e.id, pkg: e.pkg, versionCode: e.versionCode, installedVersion: e.installedVersion } : null; }, id);
const calls = (page, re) => page.evaluate((s) => window.__fmk.calls.filter(c => new RegExp(s).test(c)), re.source);
const toast = (page) => page.evaluate(() => document.getElementById('toastMsg').textContent);
const stepAll = async (page) => { await page.evaluate(() => window.__fmk.stepAll()); await page.waitForTimeout(150); };
// make a font from the Regular the chooser hands back (and step its jobs when they wait for the script)
async function make(page, held) {
  await page.click('#fntPick_regular');
  await page.waitForTimeout(150);
  await page.click('#fntBuildBtn');
  if (held) { await page.waitForTimeout(50); await stepAll(page); } else await page.waitForTimeout(400);
  return page.evaluate(() => fnt.resultId);
}
// the Phone font list: a tap uses the font, a press and hold (here with the mouse) uninstalls or deletes it
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const listed = async (page) => { await openList(page); const k = await page.evaluate(() => [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => r.dataset.k)); await page.evaluate(() => fntPhoneClose()); return k; };
const row = (page, k) => page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]');
async function hold(page, k) {
  const box = await row(page, k).boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.waitForTimeout(750); await page.mouse.up();
  await page.waitForTimeout(250);
}
const tap = async (page, k) => { await row(page, k).click(); await page.waitForTimeout(300); };

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true });
    await stepAll(page);                                                   // the cards' statuses
    const id = await make(page, true);
    const pkg = (await entry(page, id) || {}).pkg;
    check('1. the font just made has Make again under its build result', !!id && (await acts(page))['Make again'] === true, JSON.stringify(await acts(page)));
    await act(page, 'Install');
    await stepAll(page);
    check('   installed: version 1 on the phone', (await acts(page)).Installed === false && (await entry(page, id)).installedVersion === 1, JSON.stringify([await acts(page), await entry(page, id)]));
    await act(page, 'Make again');
    await page.waitForTimeout(150);
    check('   the Make card is on that font: "Making Inter Variable again", New font', /^Making Inter Variable again Choose its files again\. It keeps its package, so it installs as an update\. New font$/.test(await remake(page)), await remake(page));
    check('   its name is filled in, its Make again is off', await page.inputValue('#fntName') === 'Inter Variable' && (await acts(page))['Make again'] === false, JSON.stringify(await acts(page)));
    await page.evaluate(() => document.getElementById('fntMakeCard').scrollIntoView());
    await page.screenshot({ path: 'remake.png' });
    await page.evaluate(() => { window.__fmk.picks.regular = 'static'; });
    await page.click('#fntPick_regular');
    await page.waitForTimeout(150);
    check('   a Regular chosen (Lato-Regular.ttf) keeps the name', await page.inputValue('#fntName') === 'Inter Variable' && await page.evaluate(() => fnt.pick.regular.fileName) === 'Lato-Regular.ttf', await page.inputValue('#fntName'));
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    const req = await page.evaluate(() => window.__fmk.requests[window.__fmk.requests.length - 1]);
    check('2. Build sends rebuildId', req && req.rebuildId === id && req.name === 'Inter Variable', JSON.stringify(req));
    check('   while it is made again, the build result and its buttons are gone (nothing to install, use or save of that font)', await result(page) === '' && JSON.stringify(await acts(page)) === '{}', JSON.stringify([await result(page), await acts(page)]));
    await openList(page);
    await hold(page, pkg);
    check('   a hold on it in the Phone font list waits: "Wait for the change that is running to finish."', await toast(page) === 'Wait for the change that is running to finish.' && !(await page.evaluate(() => document.getElementById('mpAskModal').classList.contains('show'))), await toast(page));
    await tap(page, pkg);
    check('   a tap on it there sends nothing', (await calls(page, /^fontMaker(Apply|Install|UninstallPackage|OpenFontStyle)/)).join() === 'fontMakerInstall:' + id, JSON.stringify(await calls(page, /^fontMaker(Apply|Install|UninstallPackage|OpenFontStyle)/)));
    check('   New font is off while it is being made', await page.evaluate(() => document.getElementById('fntRemakeOff').disabled));
    await stepAll(page);
    check('3. made again: "Inter Variable is made again. Use it now, or choose it later under Phone font."', await result(page) === 'Inter Variable is made again. Use it now, or choose it later under Phone font.', await result(page));
    const after = await entry(page, id), a3 = await acts(page);
    check('   the same font, the same package, version 2; the phone has version 1: the build result offers Update', await page.evaluate(() => fnt.resultId) === id && after.pkg === pkg && after.versionCode === 2 && after.installedVersion === 1 && a3.Update === true,
      JSON.stringify([after, a3]));
    check('   still three fonts made here, one row of it in the list; the Make card is a new font again, Make again on', await page.evaluate(() => window.__fmk.lib.length) === 3 && (await listed(page)).filter(k => k === pkg).length === 1
      && await remake(page) === '' && a3['Make again'] === true);
    // ---- 4. New font, and the font being made again uninstalled, then deleted ----
    await act(page, 'Make again');
    await page.waitForTimeout(100);
    check('4. Make again once more: "Making Inter Variable again", its name', /^Making Inter Variable again/.test(await remake(page)) && await page.inputValue('#fntName') === 'Inter Variable');
    await page.click('#fntRemakeOff');
    await page.waitForTimeout(100);
    check('   New font: the link goes, Make again is on again', await remake(page) === '' && (await acts(page))['Make again'] === true && await page.evaluate(() => fnt.rebuildId) === '');
    await act(page, 'Make again');
    await page.waitForTimeout(100);
    await openList(page);
    await hold(page, pkg);
    await page.click('#mpAskOk');
    await page.waitForTimeout(100);
    await stepAll(page);
    check('   uninstalled (a hold in the list, then Uninstall): it is still being made again', await toast(page) === 'Uninstalled Inter Variable' && /^Making Inter Variable again/.test(await remake(page)) && await page.evaluate(() => fnt.rebuildId) === id,
      JSON.stringify([await toast(page), await remake(page)]));
    await openList(page);
    await hold(page, pkg);
    const asked = await page.evaluate(() => document.getElementById('mpAskTitle').textContent);
    await page.click('#mpAskOk');
    await page.waitForTimeout(250);
    check('   deleted (a hold again: "Delete Inter Variable?", Delete): the Make card is a new font again, its build result gone', asked === 'Delete Inter Variable?' && (await calls(page, /^fontMakerDelete/)).join() === 'fontMakerDelete:' + id
      && await remake(page) === '' && await page.evaluate(() => fnt.rebuildId) === '' && await result(page) === '' && JSON.stringify(await acts(page)) === '{}' && !(await listed(page)).includes(pkg), JSON.stringify([asked, await remake(page), await result(page), await acts(page)]));
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(50);
    const req2 = await page.evaluate(() => window.__fmk.requests[window.__fmk.requests.length - 1]);
    check('   the next build is a new font (rebuildId null)', req2 && req2.rebuildId === null,JSON.stringify(req2));
    await stepAll(page);
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
    await make(page, false);
    await act(page, 'Export');
    await page.waitForTimeout(100);
    check('6. while the save screen is open: the build result\'s Export is off', (await acts(page)).Export === false, JSON.stringify(await acts(page)));
    await page.evaluate(() => fntExport('f1a2b3'));                       // no other Export is offered: another font's save, asked through the page
    check('   another font\'s save asked meanwhile sends nothing', (await calls(page, /^fontMakerExport/)).length === 1, JSON.stringify(await calls(page, /^fontMakerExport/)));
    await page.evaluate(() => window.__fmk.save());
    await page.waitForTimeout(100);
    check('   saved: a toast, Export on again', await toast(page) === 'Saved Inter Variable.apk' && (await acts(page)).Export === true, JSON.stringify([await toast(page), await acts(page)]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 7. Build waits while a job of the font being made again runs (the bridge would refuse that build as busy) ----
    const { ctx, page, errors } = await open(b, { mode: 'root', hold: true, holdSave: true });
    await stepAll(page);                                                   // the cards' statuses
    const id = await make(page, true);
    await act(page, 'Make again');
    await page.click('#fntPick_regular');
    await page.waitForTimeout(150);
    const buildOn = () => page.evaluate(() => !document.getElementById('fntBuildBtn').disabled);
    const builds = () => page.evaluate(() => window.__fmk.calls.filter(c => c === 'fontMakerBuild').length);
    check('7. making Inter Variable again, a Regular chosen: Build is on', await buildOn());
    await act(page, 'Install');
    await page.waitForTimeout(100);
    check('   while it installs: Build is off', (await acts(page))['Installing…'] === false && !(await buildOn()), JSON.stringify(await acts(page)));
    await page.evaluate(() => fntBuild());
    check('   a build asked for now sends nothing', await builds() === 1, await page.evaluate(() => window.__fmk.calls.join()));
    await stepAll(page);
    check('   installed: Build is on again', (await acts(page)).Installed === false && await buildOn(), JSON.stringify(await acts(page)));
    await act(page, 'Use it');
    await page.waitForTimeout(100);
    check('   while the font changer applies it (Use it): Build is off', await page.evaluate(() => fnt.changerBusy === 'apply') && (await acts(page))['Applying…'] === false && !(await buildOn()), JSON.stringify(await acts(page)));
    await page.evaluate(() => fntBuild());
    check('   a build asked for now sends nothing', await builds() === 1);
    await stepAll(page);
    check('   applied: Build is on again', (await acts(page))['In use'] === false && await buildOn(), JSON.stringify(await acts(page)));
    await act(page, 'Export');
    await page.waitForTimeout(100);
    check('   while it is saved: Build is off', !(await buildOn()));
    await page.evaluate(() => window.__fmk.save());
    await page.waitForTimeout(100);
    check('   saved: Build is on again', await buildOn());
    await openList(page);
    await tap(page, LATO);
    check('   a job of another font (the font changer writes Lato, chosen in the Phone font list) leaves Build on', await page.evaluate(() => fnt.changerBusy === 'apply' && fnt.applyId === 'c4d5e6') && await buildOn());
    await stepAll(page);
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    const req = await page.evaluate(() => window.__fmk.requests[window.__fmk.requests.length - 1]);
    check('   Build sends rebuildId for Inter Variable', req && req.rebuildId === id, JSON.stringify(req));
    await stepAll(page);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
