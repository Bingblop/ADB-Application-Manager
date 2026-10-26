// v7.17.0 Custom Fonts: the question sheets and the actions of My fonts. Delete asks first on the shared question sheet ("Delete Lato?", what happens,
// a red Delete and Cancel): Cancel and a tap beside the sheet keep the font; Delete removes it (fontMakerDelete) with a toast. The font in use is
// refused by the app, said in the page's words ("Reset the font changer first."). Soft reboot asks first (only Android restarts and every app closes; after
// the Undo the apps come back with the phone's own emoji, no word of new ones): Cancel sends nothing, Soft reboot calls softRestart. Install (Installing…, then Installed),
// Uninstall and Export (the toast names the saved .apk) go through the app; each row previews its own font (fontMakerFontData). The system emoji swap
// (Use as the phone's emoji, the read-back, Undo). An app build without the font maker: each card says "Needs the app build" and a tap says so too.
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
const rows = (page) => page.evaluate(() => [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => r.id));
const btn = (page, row, label) => page.evaluate(([row, label]) => { const x = [...document.querySelectorAll('#' + row + ' .mode-action-btn')].find(b => b.textContent.trim() === label); if (x) x.click(); return !!x; }, [row, label]);

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, {});
    // ---- the row previews ----
    await page.waitForTimeout(300);
    const pv = await page.evaluate(() => [...document.querySelectorAll('#fntLibList .fnt-lib-pv')].map(e => e.style.fontFamily));
    check('1. each row previews its own font (a FontFace from fontMakerFontData)', pv.length === 2 && pv.every(f => /FntLib\d+/.test(f)) && await page.evaluate(() => window.__fmk.calls.filter(c => /^fontMakerFontData:/.test(c)).join()) === 'fontMakerFontData:f1a2b3:regular,fontMakerFontData:c4d5e6:regular', JSON.stringify(pv));
    // ---- Delete: the question ----
    await btn(page, 'fntLib_c4d5e6', 'Delete');
    await page.waitForTimeout(200);
    const s1 = await sheet(page);
    check('2. Delete asks first: "Delete Lato?", what happens, a red Delete and Cancel', s1.open && s1.title === 'Delete Lato?' && /removed from this app\. If it is installed, it stays installed until you uninstall it\./.test(s1.text) && s1.ok === 'Delete' && s1.danger && s1.cancel === 'Cancel', JSON.stringify(s1));
    await page.screenshot({ path: 'delete-sheet.png' });
    await page.click('#mpAskNo');
    await page.waitForTimeout(150);
    check('   Cancel keeps it, nothing sent', !(await sheet(page)).open && (await rows(page)).length === 2 && !(await page.evaluate(() => window.__fmk.calls.some(c => /^fontMakerDelete/.test(c)))));
    await btn(page, 'fntLib_c4d5e6', 'Delete');
    await page.waitForTimeout(200);
    await page.mouse.click(200, 60);                                           // beside the sheet
    await page.waitForTimeout(150);
    check('   a tap beside the sheet keeps it too', !(await sheet(page)).open && (await rows(page)).length === 2);
    await btn(page, 'fntLib_c4d5e6', 'Delete');
    await page.waitForTimeout(200);
    await page.click('#mpAskOk');
    await page.waitForTimeout(200);
    check('   Delete removes it: fontMakerDelete, the row gone, a toast', (await rows(page)).join() === 'fntLib_f1a2b3' && await page.evaluate(() => window.__fmk.calls.includes('fontMakerDelete:c4d5e6')) && await toast(page) === 'Deleted Lato', JSON.stringify([await rows(page), await toast(page)]));
    // the font in use: the app refuses
    await btn(page, 'fntLib_f1a2b3', 'Delete');
    await page.waitForTimeout(200);
    await page.click('#mpAskOk');
    await page.waitForTimeout(200);
    const refused = await page.evaluate(() => (document.querySelector('#fntLib_f1a2b3 .is-err') || {}).textContent);
    check('3. the font in use is refused: "Reset the font changer first." under its row', refused === 'Reset the font changer first.' && (await rows(page)).length === 1, refused);
    // ---- Uninstall, Install, Export ----
    await btn(page, 'fntLib_f1a2b3', 'Uninstall');
    await page.waitForTimeout(5);
    const mid = await page.evaluate(() => document.querySelector('#fntLib_f1a2b3 .mode-btn-row').textContent);
    await page.waitForTimeout(200);
    check('4. Uninstall: "Uninstalling…" while it runs, then Install and the toast', /Uninstalling…/.test(mid) && await btn(page, 'fntLib_f1a2b3', 'Install') && await toast(page) === 'Uninstalled', mid);
    await page.waitForTimeout(200);
    const meta = await page.evaluate(() => document.querySelectorAll('#fntLib_f1a2b3 .fnt-lib-meta')[1].textContent);
    check('   Install: Installed again (the app\'s pm install), the toast', /In use/.test(meta) && await page.evaluate(() => window.__fmk.calls.includes('fontMakerInstall:f1a2b3')) && await toast(page) === 'Installed' && await page.evaluate(() => !!document.evaluate('.//button[text()="Uninstall"]', document.getElementById('fntLib_f1a2b3'), null, 9, null).singleNodeValue), meta);
    await btn(page, 'fntLib_f1a2b3', 'Export');
    await page.waitForTimeout(200);
    check('5. Export: the save-as of the app, the toast names the .apk', await page.evaluate(() => window.__fmk.calls.includes('fontMakerExport:f1a2b3')) && await toast(page) === 'Saved Inter Display.apk', await toast(page));
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
    const s2 = await sheet(page);
    check('7. Soft reboot asks first: every app closes; after the Undo, apps start again with the phone\'s own emoji', s2.open && s2.title === 'Soft reboot?' && /every app closes/.test(s2.text) && /Apps start again with the phone's own emoji\.$/.test(s2.text) && !/new emoji/.test(s2.text) && s2.ok === 'Soft reboot' && s2.danger, JSON.stringify(s2));
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
  // ---- an app build without the font maker ----
  {
    const all = ['fontMakerPick', 'fontMakerPreview', 'fontMakerBuild', 'fontMakerCancel', 'fontMakerLibrary', 'fontMakerFontData', 'fontMakerDelete', 'fontMakerExport', 'fontMakerInstall', 'fontMakerUninstall',
      'fontMakerChangerStatus', 'fontMakerApply', 'fontMakerReset', 'emojiCatalog', 'emojiDownload', 'emojiCancel', 'emojiDelete', 'sysEmojiStatus', 'sysEmojiApply', 'sysEmojiUndo', 'softRestart'];
    const { ctx, page, errors } = await open(b, { missing: all });
    const t = await page.evaluate(() => ['fntEmojiList', 'fntLibList', 'fntChangerBody', 'fntSysBody'].map(id => document.getElementById(id).innerText.trim()));
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
