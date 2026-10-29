// v7.17.0 Custom Fonts: the question sheets and the actions on fonts (v7.19.1: My fonts is gone; the Phone font list and the build result hold them).
// The fonts made here are rows of the Phone font list, and the page asks for no font data (fontMakerFontData: the rows' previews are gone). A press
// and hold on a font made here and not installed asks first on the shared question sheet ("Delete Lato?", what happens, a red Delete and Cancel):
// Cancel and a tap beside the sheet keep the font; Delete removes it (fontMakerDelete) with a toast. A hold on the font in use is refused with a toast
// and nothing is sent; where the page cannot read the font in use (no working mode), the app's own note of the font it applied refuses the Delete
// before it is asked ("Lato is in use. Choose another font first, then delete it."). Under the build result, Install goes through the app (Installing…, then Installed, a toast); a
// hold in the list asks "Uninstall {name}?" and uninstalls it (fontMakerUninstallPackage: "Uninstalling…" in the card while it runs, a toast that
// names it, Install offered again), and Install puts it back; Export (the toast names the saved .apk) goes through the app. Soft reboot asks first
// (only Android restarts and every app closes; after the Undo the apps come back with the phone's own emoji, no word of new ones): Cancel sends
// nothing, Soft reboot calls softRestart. The system emoji swap (Use as the phone's emoji, the read-back, Undo). An app build without the font
// maker: each card says "Needs the app build" and a tap says so too.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const LATO = 'com.monotype.android.font.Lato_8b21d0', INTER = 'com.monotype.android.font.InterDisplay_3fa9c1';

async function open(b, opts) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ mode: 'root', b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const sheet = (page) => page.evaluate(() => {
  const m = document.getElementById('mpAskModal'), ok = document.getElementById('mpAskOk');
  return { open: m.classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, text: document.getElementById('mpAskText').textContent,
    ok: ok.textContent, danger: !!ok.style.background, cancel: document.getElementById('mpAskNo').textContent };
});
const toast = (page) => page.evaluate(() => document.getElementById('toastMsg').textContent);
const calls = (page, re) => page.evaluate((s) => window.__fmk.calls.filter(c => new RegExp(s).test(c)), re.source);
// the Phone font list: opened from its drop-down; each row by its key (data-k: 'default' or the package), with its name and what it says under it
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const closeList = async (page) => { await page.evaluate(() => fntPhoneClose()); await page.waitForTimeout(100); };
const list = (page) => page.evaluate(() => ({ open: document.getElementById('fntPhoneSheet').classList.contains('show'),
  rows: [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => ({ k: r.dataset.k, name: r.querySelector('.ab-sheet-txt > span').textContent, sub: r.querySelector('.ab-sheet-sub').textContent })) }));
const listed = async (page) => { await openList(page); const l = await list(page); await closeList(page); return l.rows; };
const row = (page, k) => page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]');
// a press and hold with the mouse (the list takes the touch the same way)
async function hold(page, k) {
  const box = await row(page, k).boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.waitForTimeout(750); await page.mouse.up();
  await page.waitForTimeout(250);
}
// the Phone font card: the hint under the drop-down, whether the drop-down is off, its messages
const card = (page) => page.evaluate(() => ({ hint: (document.querySelector('#fntChangerBody > .fnt-note:not(.is-warn)') || {}).textContent || '', pickOff: document.getElementById('fntPhonePick').disabled,
  err: [...document.querySelectorAll('#fntChangerBody .fnt-err')].map(e => e.textContent).join(' | ') }));
// the build result's buttons (for the font just made): { label: on }
const acts = (page) => page.evaluate(() => { const o = {}; document.querySelectorAll('#fntBuildActs .mode-action-btn').forEach(x => { o[x.textContent.trim()] = !x.disabled; }); return o; });
const act = (page, label) => page.click('#fntBuildActs .mode-action-btn:text-is("' + label + '")');

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, {});
    await page.waitForTimeout(300);
    // ---- the fonts made here, in the Phone font list ----
    const l0 = await listed(page);
    const by = n => l0.find(r => r.name === n) || {};
    check('1. My fonts is gone: the fonts made here are rows of the Phone font list, and no font data is asked for (no row previews)', !(await page.$('#fntLibCard')) && by('Inter Display').k === INTER && by('Inter Display').sub === 'Made on this phone · Not signed by Samsung or Monotype · In use'
      && by('Lato').k === LATO && by('Lato').sub === 'Made on this phone, not installed · Not signed by Samsung or Monotype' && (await calls(page, /^fontMakerFontData/)).length === 0, JSON.stringify([l0, await calls(page, /^fontMakerFontData/)]));
    // ---- Delete: the question ----
    await openList(page);
    await hold(page, LATO);
    const s1 = await sheet(page);
    check('2. a hold on Lato (made here, not installed): the list closes and Delete asks first: "Delete Lato?", what happens, a red Delete and Cancel', !(await list(page)).open && s1.open && s1.title === 'Delete Lato?'
      && /removed from this app\. If it is installed, it stays installed until you uninstall it\./.test(s1.text) && s1.ok === 'Delete' && s1.danger && s1.cancel === 'Cancel', JSON.stringify(s1));
    await page.screenshot({ path: 'delete-sheet.png' });
    await page.click('#mpAskNo');
    await page.waitForTimeout(150);
    check('   Cancel keeps it, nothing sent', !(await sheet(page)).open && (await listed(page)).some(r => r.k === LATO) && (await calls(page, /^fontMakerDelete/)).length === 0);
    await openList(page);
    await hold(page, LATO);
    await page.mouse.click(200, 60);                                           // beside the sheet
    await page.waitForTimeout(150);
    check('   a tap beside the sheet keeps it too', !(await sheet(page)).open && (await listed(page)).some(r => r.k === LATO) && (await calls(page, /^fontMakerDelete/)).length === 0);
    await openList(page);
    await hold(page, LATO);
    await page.click('#mpAskOk');
    await page.waitForTimeout(200);
    const l1 = await listed(page);
    check('   Delete removes it: fontMakerDelete, gone from the list, a toast', !l1.some(r => r.k === LATO) && l1.some(r => r.k === INTER) && (await calls(page, /^fontMakerDelete/)).join() === 'fontMakerDelete:c4d5e6' && await toast(page) === 'Deleted Lato',
      JSON.stringify([l1.map(r => r.name), await calls(page, /^fontMakerDelete/), await toast(page)]));
    // the font in use: refused
    await openList(page);
    await hold(page, INTER);
    check('3. a hold on the font in use is refused with a toast: no question, nothing sent', await toast(page) === 'Inter Display is in use. Choose another font first, then uninstall it.' && !(await sheet(page)).open
      && (await calls(page, /^fontMaker(Delete|UninstallPackage|Uninstall)/)).join() === 'fontMakerDelete:c4d5e6', JSON.stringify([await toast(page), await calls(page, /^fontMaker(Delete|UninstallPackage|Uninstall)/)]));
    await closeList(page);
    // ---- the font just made: Install, Uninstall (the list's hold), Install again, Export ----
    await page.click('#fntPick_regular');
    await page.waitForTimeout(150);
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(400);
    const made = await page.evaluate(() => { const e = fntEntry(fnt.resultId) || {}; return { id: e.id, pkg: e.pkg, name: e.name, said: document.getElementById('fntBuildResult').textContent }; });
    check('4. a font made: "Inter Variable is ready. ...", and under it Use it, Install, Export, Make again', made.name === 'Inter Variable' && made.said === 'Inter Variable is ready. Use it now, or choose it later under Phone font.'
      && JSON.stringify(await acts(page)) === JSON.stringify({ 'Use it': true, Install: true, Export: true, 'Make again': true }), JSON.stringify([made, await acts(page)]));
    const midI = await page.evaluate(() => { [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].find(x => x.textContent.trim() === 'Install').click(); return document.getElementById('fntBuildActs').textContent; });
    await page.waitForTimeout(200);
    check('   Install: "Installing…" while it runs, then Installed (the app\'s pm install), the toast', /Installing…/.test(midI) && (await acts(page)).Installed === false && (await calls(page, /^fontMakerInstall/)).join() === 'fontMakerInstall:' + made.id
      && await toast(page) === 'Installed', JSON.stringify([midI, await acts(page), await toast(page)]));
    check('   the Phone font list has it: "Made on this phone"', /^Made on this phone · /.test(((await listed(page)).find(r => r.k === made.pkg) || {}).sub), JSON.stringify(await listed(page)));
    await openList(page);
    await hold(page, made.pkg);
    const s2 = await sheet(page);
    check('   a hold on it in the list: "Uninstall Inter Variable?", it stays in this app, a red Uninstall', s2.open && s2.title === 'Uninstall Inter Variable?' && s2.text === 'The font package is removed from the phone. The font stays in this app, so you can install it again.'
      && s2.ok === 'Uninstall' && s2.danger && s2.cancel === 'Cancel', JSON.stringify(s2));
    const midU = await page.evaluate(() => new Promise(res => {
      document.getElementById('mpAskOk').click();
      setTimeout(() => res({ hint: (document.querySelector('#fntChangerBody > .fnt-note:not(.is-warn)') || {}).textContent, off: document.getElementById('fntPhonePick').disabled }), 0);
    }));
    await page.waitForTimeout(200);
    check('   Uninstall: "Uninstalling…" in the card while it runs (the drop-down off), then the toast names it', midU.hint === 'Uninstalling…' && midU.off && (await calls(page, /^fontMakerUninstallPackage/)).join() === 'fontMakerUninstallPackage:' + made.pkg
      && await toast(page) === 'Uninstalled Inter Variable' && !(await card(page)).pickOff, JSON.stringify([midU, await calls(page, /^fontMakerUninstallPackage/), await toast(page)]));
    check('   the build result offers Install again; the list: "Made on this phone, not installed"', (await acts(page)).Install === true && /^Made on this phone, not installed · /.test(((await listed(page)).find(r => r.k === made.pkg) || {}).sub), JSON.stringify([await acts(page), await listed(page)]));
    await act(page, 'Install');
    await page.waitForTimeout(200);
    check('   Install: Installed again, the toast', (await acts(page)).Installed === false && (await calls(page, /^fontMakerInstall/)).length === 2 && await toast(page) === 'Installed' && /^Made on this phone · /.test(((await listed(page)).find(r => r.k === made.pkg) || {}).sub),
      JSON.stringify([await acts(page), await toast(page)]));
    await act(page, 'Export');
    await page.waitForTimeout(200);
    check('5. Export (under the build result): the save-as of the app, the toast names the .apk', (await calls(page, /^fontMakerExport/)).join() === 'fontMakerExport:' + made.id && await toast(page) === 'Saved Inter Variable.apk', await toast(page));
    // ---- the system emoji swap ----
    await page.click('#fntSysUse');
    await page.waitForTimeout(200);
    const sw = await page.evaluate(() => document.getElementById('fntSysBody').innerText.replace(/\s+/g, ' '));
    check('6. Use as the phone\'s emoji: sysEmojiApply, the read-back of each file, Undo appears', await page.evaluate(() => window.__fmk.calls.includes('sysEmojiApply:twemoji')) && /Read back from the phone: \/system\/fonts\/NotoColorEmoji\.ttf d2d721abc0ab ✓ \/system\/fonts\/NotoColorEmojiFlags\.ttf d2d721abc0ab ✓/.test(sw)
      && /Phone emoji Twemoji/.test(sw) && !!(await page.$('#fntSysUndo')), sw);
    check('   the toast: until the next restart', await toast(page) === 'The phone uses the new emoji until the next restart.');
    await page.click('#fntEmo_twemoji .op-mode-btn');                            // Delete the emoji in use
    await page.waitForTimeout(150);
    check('   the emoji in use cannot be deleted: "Undo the system emoji first."', await page.evaluate(() => (document.querySelector('#fntEmo_twemoji .is-err') || {}).textContent) === 'Undo the system emoji first.');
    await page.click('#fntSysUndo');
    await page.waitForTimeout(200);
    check('   Undo: sysEmojiUndo, Default again, the toast', await page.evaluate(() => window.__fmk.calls.includes('sysEmojiUndo')) && /Phone emoji Default/.test(await page.evaluate(() => document.getElementById('fntSysBody').innerText.replace(/\s+/g, ' '))) && await toast(page) === "The phone's own emoji are back.");
    // ---- Soft reboot ----
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(200);
    const s3 = await sheet(page);
    check('7. Soft reboot asks first: every app closes; after the Undo, apps start again with the phone\'s own emoji', s3.open && s3.title === 'Soft reboot?' && /every app closes/.test(s3.text) && /Apps start again with the phone's own emoji\.$/.test(s3.text) && !/new emoji/.test(s3.text) && s3.ok === 'Soft reboot' && s3.danger, JSON.stringify(s3));
    await page.evaluate(() => document.getElementById('fntSysCard').scrollIntoView());
    await page.screenshot({ path: 'soft-reboot-sheet.png' });
    await page.click('#mpAskNo');
    await page.waitForTimeout(150);
    check('   Cancel: softRestart is not called', !(await page.evaluate(() => window.__fmk.calls.includes('softRestart'))));
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(200);
    await page.click('#mpAskOk');
    await page.waitForTimeout(150);
    check('   Soft reboot: softRestart is called once', await page.evaluate(() => window.__fmk.calls.filter(c => c === 'softRestart').length) === 1);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- no working mode, Lato in use (written by the font changer before, never installed): the page cannot tell, the app refuses ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'none', current: 'c4d5e6' });
    await openList(page);
    await hold(page, LATO);
    const s = await sheet(page);
    check('3b. no working mode, so the page cannot read the font in use, but the app\'s own note says Lato is: a hold on Lato asks nothing and sends no delete',
      !s.open && !(await calls(page, /^fontMakerDelete/)).length, JSON.stringify(s));
    check('   it says so: "Lato is in use. Choose another font first, then delete it.", and Lato is kept', await toast(page) === 'Lato is in use. Choose another font first, then delete it.'
      && (await listed(page)).some(r => r.k === LATO), JSON.stringify(await toast(page)));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- an app build without the font maker ----
  {
    const all = ['fontMakerPick', 'fontMakerPreview', 'fontMakerBuild', 'fontMakerCancel', 'fontMakerLibrary', 'fontMakerFontData', 'fontMakerDelete', 'fontMakerExport', 'fontMakerInstall', 'fontMakerUninstall',
      'fontMakerChangerStatus', 'fontMakerApply', 'fontMakerReset', 'fontMakerPhoneFonts', 'fontMakerApplyPackage', 'fontMakerUninstallPackage', 'fontMakerOpenFontStyle',
      'emojiCatalog', 'emojiDownload', 'emojiCancel', 'emojiDelete', 'sysEmojiStatus', 'sysEmojiApply', 'sysEmojiUndo', 'softRestart'];
    const { ctx, page, errors } = await open(b, { missing: all });
    const t = await page.evaluate(() => ['fntEmojiList', 'fntChangerBody', 'fntSysBody'].map(id => document.getElementById(id).innerText.trim()));
    check('8. without the font maker each card says "Needs the app build"', t.every(x => x === 'Needs the app build'), JSON.stringify(t));
    await page.click('#fntPick_regular');
    await page.waitForTimeout(150);
    check('   a tap on Choose says so too', await toast(page) === 'Needs the app build');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
