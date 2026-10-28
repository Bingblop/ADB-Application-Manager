// v7.19.1 (the owner's three asks). 1) App Stores: the "Update stores on mobile data" switch sits below the tab's "?" (it was under it, so a tap opened the
// Help Guide): at 360 and 412 px the ? and the switch do not overlap, a tap on the switch turns it on and the guide stays shut, and the ? still opens
// the guide. 2) Custom Fonts: My fonts is gone; a Phone font card has a drop-down of every font on the phone (Default first, then by name, each with where
// it came from) that uses the font chosen. Without Root or the system user, a font Samsung or Monotype signed opens Samsung's Font style and one they
// did not sign shows a warning and nothing changes; with Root the font changer writes any font (a package of the phone through fontMakerApplyPackage,
// a font made here through fontMakerApply, Default through fontMakerReset). 3) A press and hold uninstalls: Default, a font that came with the phone and
// the font in use are refused with a toast; another font asks first (Cancel keeps it) and goes (pm with a working mode, App info without); a font made
// here and not installed is deleted from the app; Delete on a row does the same for the keyboard. On an older One UI a font made here is installed first,
// then Font style opens. Mock bridges: lib/fontmaker_mock.js (Custom Fonts), none for App Stores (the page alone).
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };

async function fonts(b, opts) {
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
const card = (page) => page.evaluate(() => {
  const t = id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; };
  const note = document.getElementById('fntDeviceNote'), pick = document.getElementById('fntPhonePick'), warn = document.querySelector('#fntChangerBody .fnt-note.is-warn');
  return { title: document.querySelector('#fntChangerCard .color-card-title').textContent, tag: t('fntChangerTag'), note: note.style.display === 'none' ? '' : note.textContent,
    pick: pick ? pick.textContent.replace(/[▾\s]+$/, '').trim() : null, pickOff: pick ? pick.disabled : null, warn: warn ? warn.textContent : '',
    msg: [...document.querySelectorAll('#fntChangerBody .fnt-err')].map(e => e.textContent).join(' | '), libCard: !!document.getElementById('fntLibCard') };
});
const sheet = (page) => page.evaluate(() => ({ open: document.getElementById('fntPhoneSheet').classList.contains('show'),
  rows: [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => ({ k: r.dataset.k, name: r.querySelector('.ab-sheet-txt > span').textContent, sub: r.querySelector('.ab-sheet-sub').textContent,
    lock: !!r.querySelector('.fnt-pr-lock'), cur: r.classList.contains('current') })) }));
const toast = (page) => page.evaluate(() => document.getElementById('toastMsg').textContent);
const calls = (page, re) => page.evaluate((s) => window.__fmk.calls.filter(c => new RegExp(s).test(c)), re.source);
const ask = (page) => page.evaluate(() => ({ open: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent,
  text: document.getElementById('mpAskText').textContent, ok: document.getElementById('mpAskOk').textContent, danger: !!document.getElementById('mpAskOk').style.background }));
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const row = (page, k) => page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]');
// a press and hold with the mouse (the list takes the touch the same way)
async function hold(page, k) {
  const box = await row(page, k).boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.waitForTimeout(750); await page.mouse.up();
  await page.waitForTimeout(250);
}
const tap = async (page, k) => { await row(page, k).click(); await page.waitForTimeout(300); };

(async () => {
  const b = await chromium.launch();

  // ---- 1) App Stores: the mobile-data switch below the ? ----
  for (const w of [360, 412]) {
    const page = await b.newPage({ viewport: { width: w, height: 800 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto(PAGE); await page.waitForTimeout(500);
    await page.evaluate(() => switchView('store')); await page.waitForTimeout(300);
    const g = await page.evaluate(() => {
      const c = document.getElementById('storeMobileCard'), q = c.querySelector('.help-q'), t = c.querySelector('.switch-track');
      const qr = q.getBoundingClientRect(), tr = t.getBoundingClientRect(), hit = document.elementFromPoint(tr.left + tr.width / 2, tr.top + tr.height / 2);
      return { q: !!q, apart: qr.bottom <= tr.top || qr.top >= tr.bottom || qr.right <= tr.left || qr.left >= tr.right, below: tr.top >= qr.bottom, hit: hit === t || (hit && hit.closest('.switch-row') === t.closest('.switch-row')) };
    });
    check('1. App Stores at ' + w + ' px: the ? is in the mobile-data card and the switch sits below it, apart', g.q && g.apart && g.below && g.hit, JSON.stringify(g));
    await page.click('#storeMobileCard .switch-track'); await page.waitForTimeout(250);
    const after = await page.evaluate(() => ({ on: document.getElementById('storeMobileToggle').checked, kv: kvGet('store_mobile_data', false), guide: document.getElementById('helpGuideModal').classList.contains('show') }));
    check('   a tap on the switch turns it on; the Help Guide stays shut', after.on && after.kv === true && !after.guide, JSON.stringify(after));
    if (w === 412) {
      await page.click('#storeMobileCard .help-q'); await page.waitForTimeout(400);
      check('   the ? still opens the Help Guide', await page.evaluate(() => document.getElementById('helpGuideModal').classList.contains('show')));
    }
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // ---- 2) and 3) without Root or the system user, One UI 8.5 ----
  {
    const { ctx, page, errors } = await fonts(b, { mode: 'none' });
    const c = await card(page);
    check('2. Custom Fonts: My fonts is gone; the Phone font card, LIMITED without Root, says what One UI 8.5 takes, the drop-down asks for a font',
      !c.libCard && c.title === 'Phone font' && c.tag === 'LIMITED' && /^On One UI 8\.5, Samsung's Font style takes only fonts signed by Samsung or Monotype\./.test(c.note) && c.pick === 'Choose a font' && !c.pickOff, JSON.stringify(c));
    await openList(page);
    const s = await sheet(page);
    check('   the list: Default first, then every font on the phone by name, and the fonts made here', s.open && s.rows.map(r => r.name).join() === 'Default,Choco cooky,Comic Neue,Gothic Bold,Inter Display,Lato,Rosemary,SamsungOne', JSON.stringify(s.rows.map(r => r.name)));
    const by = n => s.rows.find(r => r.name === n) || {};
    check('   each says where it came from', by('Default').sub === "The phone's own font" && by('SamsungOne').sub === 'Came with the phone' && by('Choco cooky').sub === 'Installed'
      && by('Comic Neue').sub === 'Not signed by Samsung or Monotype' && by('Inter Display').sub === 'Made on this phone · Not signed by Samsung or Monotype'
      && by('Lato').sub === 'Made on this phone, not installed · Not signed by Samsung or Monotype', JSON.stringify(s.rows.map(r => r.sub)));
    check('   a lock on the fonts this phone takes only with Root or the system user', s.rows.filter(r => r.lock).map(r => r.name).join() === 'Comic Neue,Inter Display,Lato', JSON.stringify(s.rows.filter(r => r.lock).map(r => r.name)));
    await tap(page, 'com.monotype.android.font.ComicNeue_9a1b2c');
    const w1 = await card(page);
    check('   a font Samsung did not sign: the list closes, a warning, nothing sent', !(await sheet(page)).open
      && w1.warn === 'Comic Neue is not signed by Samsung or Monotype, so the phone takes it only with Root, or Shizuku running as the system user (UID 1000) or root (UID 0). The font was not changed.'
      && (await calls(page, /^fontMaker(Apply|Reset|OpenFontStyle|Install)/)).length === 0, JSON.stringify([w1.warn, await calls(page, /^fontMaker(Apply|Reset|OpenFontStyle|Install)/)]));
    await openList(page);
    await tap(page, 'com.monotype.android.font.chococooky');
    check('   a font Samsung or Monotype signed: Samsung\'s Font style opens, the toast names the font, the warning goes',
      (await calls(page, /^fontMakerOpenFontStyle/)).length === 1 && await toast(page) === 'Choose Choco cooky in Font style.' && (await card(page)).warn === '', JSON.stringify([await calls(page, /^fontMakerOpenFontStyle/), await toast(page)]));
    await openList(page);
    await tap(page, 'default');
    check('   Default: Font style too', (await calls(page, /^fontMakerOpenFontStyle/)).length === 2 && await toast(page) === 'Choose Default in Font style.', await toast(page));

    await openList(page);
    await hold(page, 'default');
    check('3. hold Default: refused with a toast, the list stays', await toast(page) === "Default is the phone's own font, so it cannot be uninstalled." && (await sheet(page)).open && !(await ask(page)).open, await toast(page));
    await hold(page, 'com.monotype.android.font.samsungone');
    check('   hold a font that came with the phone: refused', await toast(page) === 'SamsungOne came with the phone, so it cannot be uninstalled.' && !(await ask(page)).open, await toast(page));
    await hold(page, 'com.monotype.android.font.chococooky');
    const a1 = await ask(page);
    check('   hold another font: the list closes and it asks first, in red', !(await sheet(page)).open && a1.open && a1.title === 'Uninstall Choco cooky?' && a1.text === 'The font package is removed from the phone.' && a1.ok === 'Uninstall' && a1.danger, JSON.stringify(a1));
    await page.click('#mpAskNo'); await page.waitForTimeout(200);
    check('   Cancel keeps it: nothing sent', (await calls(page, /^fontMakerUninstallPackage/)).length === 0);
    await openList(page);
    await hold(page, 'com.monotype.android.font.chococooky');
    await page.click('#mpAskOk'); await page.waitForTimeout(400);
    check('   Uninstall without a working mode: fontMakerUninstallPackage, then the font\'s App info', (await calls(page, /^fontMakerUninstallPackage/)).join() === 'fontMakerUninstallPackage:com.monotype.android.font.chococooky'
      && (await card(page)).msg === "Tap Uninstall on the font's App info.", JSON.stringify([await calls(page, /^fontMakerUninstallPackage/), (await card(page)).msg]));
    await openList(page);
    await hold(page, 'com.monotype.android.font.Lato_8b21d0');
    const a2 = await ask(page);
    check('   hold a font made here and not installed: it asks to delete it from the app', a2.open && a2.title === 'Delete Lato?' && a2.ok === 'Delete', JSON.stringify(a2));
    await page.click('#mpAskOk'); await page.waitForTimeout(300);
    await openList(page);
    check('   Delete: fontMakerDelete, Lato gone from the list, the toast', (await calls(page, /^fontMakerDelete/)).join() === 'fontMakerDelete:c4d5e6' && !(await sheet(page)).rows.some(r => r.name === 'Lato') && await toast(page) === 'Deleted Lato',
      JSON.stringify([await calls(page, /^fontMakerDelete/), await toast(page)]));
    await row(page, 'com.monotype.android.font.rosemary').focus();
    await page.keyboard.press('Delete'); await page.waitForTimeout(200);
    check('   the keyboard: Delete on a row asks the same (Rosemary came with the phone: refused)', await toast(page) === 'Rosemary came with the phone, so it cannot be uninstalled.', await toast(page));
    await row(page, 'com.monotype.android.font.chococooky').focus();
    await page.keyboard.press('Enter'); await page.waitForTimeout(300);
    check('   …and Enter chooses it', (await calls(page, /^fontMakerOpenFontStyle/)).length === 3 && !(await sheet(page)).open);
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }

  // ---- with Root: the font changer writes any font ----
  {
    const { ctx, page, errors } = await fonts(b, { mode: 'root' });
    await page.waitForTimeout(300);
    const c = await card(page);
    check('4. Root: ROOT, no One UI note, the drop-down shows the font in use', c.tag === 'ROOT' && c.note === '' && c.pick === 'Inter Display', JSON.stringify(c));
    await openList(page);
    const s = await sheet(page);
    const cur = s.rows.filter(r => r.cur);
    check('   the font in use is marked, and nothing is locked', cur.length === 1 && cur[0].name === 'Inter Display' && / · In use$/.test(cur[0].sub) && !s.rows.some(r => r.lock), JSON.stringify(s.rows));
    await hold(page, 'com.monotype.android.font.InterDisplay_3fa9c1');
    check('   hold the font in use: refused', await toast(page) === 'Inter Display is in use. Choose another font first, then uninstall it.' && !(await ask(page)).open, await toast(page));
    await tap(page, 'com.monotype.android.font.ComicNeue_9a1b2c');
    await page.waitForTimeout(400);
    const c2 = await card(page);
    check('   a package of the phone: fontMakerApplyPackage, the read-back, the toast, and the drop-down shows it', (await calls(page, /^fontMakerApplyPackage/)).join() === 'fontMakerApplyPackage:com.monotype.android.font.ComicNeue_9a1b2c'
      && await toast(page) === 'The font is applied.' && c2.pick === 'Comic Neue' && await page.evaluate(() => /ComicNeue_9a1b2c#ComicNeue_9a1b2c/.test(document.getElementById('fntChangerBody').textContent)), JSON.stringify([c2, await toast(page)]));
    await openList(page);
    await tap(page, 'com.monotype.android.font.Lato_8b21d0');
    await page.waitForTimeout(400);
    check('   a font made here: fontMakerApply with its id (not installed, it is written all the same)', (await calls(page, /^fontMakerApply:/)).join() === 'fontMakerApply:c4d5e6' && (await card(page)).pick === 'Lato', JSON.stringify([await calls(page, /^fontMakerApply:/), (await card(page)).pick]));
    await openList(page);
    await tap(page, 'default');
    await page.waitForTimeout(400);
    check('   Default: fontMakerReset, the toast, Default shown', (await calls(page, /^fontMakerReset/)).length === 1 && await toast(page) === 'The default font is back.' && (await card(page)).pick === 'Default', JSON.stringify([await toast(page), (await card(page)).pick]));
    await openList(page);
    await hold(page, 'com.monotype.android.font.chococooky');
    await page.click('#mpAskOk'); await page.waitForTimeout(400);
    await openList(page);
    check('   Uninstall with Root: pm, the font gone from the list, the toast names it', !(await sheet(page)).rows.some(r => r.name === 'Choco cooky') && await toast(page) === 'Uninstalled Choco cooky', await toast(page));
    await page.keyboard.press('Escape'); await page.evaluate(() => fntPhoneClose()); await page.waitForTimeout(150);
    // the bug check: while the list uninstalls a package, the made font's Install of the same package waits for it
    const before = (await calls(page, /^fontMakerInstall/)).length;
    const held = await page.evaluate(() => {
      const e = fntEntry('c4d5e6'); fnt.phoneBusy = e.pkg; fnt.resultId = e.id; fntRenderActs(); fntInstall(e.id);
      const btns = [...document.querySelectorAll('#fntBuildActs button')].map(x => x.textContent + (x.disabled ? ' (off)' : ''));
      fnt.phoneBusy = ''; fnt.resultId = ''; fntRenderActs();
      return btns;
    });
    check('   an Install of a package the list is uninstalling waits: the button says Uninstalling…, nothing sent', held.includes('Uninstalling… (off)') && (await calls(page, /^fontMakerInstall/)).length === before, JSON.stringify(held));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }

  // ---- an older One UI (every font package is taken): a font made here is installed first ----
  for (const mode of ['none', 'shell']) {
    const { ctx, page, errors } = await fonts(b, { mode, appliesSelfSigned: true, oneUi: '8.0' });
    check('5. older One UI, ' + mode + ': no note, and no lock in the list', await (async () => { const c = await card(page); await openList(page); const s = await sheet(page); return c.note === '' && !s.rows.some(r => r.lock); })());
    await tap(page, 'com.monotype.android.font.Lato_8b21d0');
    await page.waitForTimeout(400);
    if (mode === 'none') {
      check('   a font made here, not installed: installed first (Android\'s installer), then the way to Font style', (await calls(page, /^fontMakerInstall/)).join() === 'fontMakerInstall:c4d5e6'
        && (await card(page)).msg === "Sent to Android's installer. Once it is installed, choose Lato in Font style." && (await calls(page, /^fontMakerOpenFontStyle/)).length === 0, JSON.stringify([await calls(page, /^fontMaker(Install|OpenFontStyle)/), (await card(page)).msg]));
    } else {
      check('   installed by the app (pm), then Font style opens on it', (await calls(page, /^fontMakerInstall/)).join() === 'fontMakerInstall:c4d5e6' && (await calls(page, /^fontMakerOpenFontStyle/)).length === 1
        && await toast(page) === 'Choose Lato in Font style.', JSON.stringify([await calls(page, /^fontMaker(Install|OpenFontStyle)/), await toast(page)]));
    }
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }

  // ---- a phone without a Font style screen by that name, and a phone that is not a Samsung ----
  {
    const { ctx, page, errors } = await fonts(b, { mode: 'none', fontStyleExact: false });
    await openList(page);
    await tap(page, 'com.monotype.android.font.chococooky');
    check('6. no Font style screen by that name: Display settings, and the toast says where to go', await toast(page) === 'Choose Choco cooky in Font size and style, then Font style.', await toast(page));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }
  {
    const { ctx, page, errors } = await fonts(b, { mode: 'root', samsung: false });
    const c = await card(page);
    check('7. not a Samsung: SAMSUNG ONLY, the note, no drop-down', c.tag === 'SAMSUNG ONLY' && c.note === 'Font packages work only on Samsung phones. You can still make fonts and export them.' && c.pick === null, JSON.stringify(c));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
