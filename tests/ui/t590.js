// v7.17.0 Custom Fonts, the gates (v7.19.1: the Phone font card). The font changer opens like the tracker switches (Root with su, or Shizuku as the
// system user UID 1000 or root UID 0); System emoji only with root (Root with su, or Shizuku as UID 0). Per working mode: Root (both open, ROOT: the
// drop-down shows the font in use, the list has no lock and marks that font In use, and a tap on it sends nothing), the system user (the card SYSTEM
// USER, "Checking…" while its status is asked; System emoji the standard locked row "Needs Super User"), Shizuku as UID 0 (both open), the ADB shell
// UID 2000 (the card LIMITED with no locked row, its drop-down on; System emoji locked; a lock in the list on each font Samsung or Monotype did not
// sign, and a tap on one shows the warning and sends nothing; a font they signed opens Samsung's Font style; fntApply called anyway goes the Root way
// and sends nothing), and a phone that is not a Samsung (SAMSUNG ONLY, no drop-down; the note says fonts can still be made and exported; the build
// result's Use it and Install are off, Export works). One UI 8.5 says once, above the drop-down, which fonts Samsung's Font style takes and that the
// others need Root or the system user, only while the font changer is locked; a One UI that takes these fonts says nothing and locks nothing. Under the
// system user a font chosen in the list is written (fontMakerApply; "Applying…", the drop-down off while it runs), the card shows the read-back
// (sans.loc, the files with size, hash and label) and the new font in the drop-down and the list, and Default goes back (fontMakerReset, "Resetting…",
// sans.loc default#default). The words "root" never name the system user.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const LATO = 'com.monotype.android.font.Lato_8b21d0', INTER = 'com.monotype.android.font.InterDisplay_3fa9c1', CHOCO = 'com.monotype.android.font.chococooky';

async function open(b, opts, dialogs) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => { if (dialogs) dialogs.push(d.message()); d.dismiss().catch(() => {}); });
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const cards = (page) => page.evaluate(() => {
  const t = id => { const e = document.getElementById(id); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; };          // innerText: a space between the label and the value
  const lockRow = id => { const r = document.querySelector('#' + id + ' .rf-row.is-locked'); return r ? { name: r.querySelector('.rf-name').textContent.replace(/\s+/g, ' ').trim(), btn: r.querySelector('.op-mode-btn').disabled, why: r.querySelector('.pm-lock').textContent.trim() } : null; };
  const note = document.getElementById('fntDeviceNote'), pick = document.getElementById('fntPhonePick'), warn = document.querySelector('#fntChangerBody .fnt-note.is-warn');
  return { cTag: t('fntChangerTag'), sTag: t('fntSysTag'), cBody: t('fntChangerBody'), sBody: t('fntSysBody'), cLock: lockRow('fntChangerBody'), sLock: lockRow('fntSysBody'),
    pick: pick ? pick.textContent.replace(/[▾\s]+$/, '').trim() : null, pickOff: pick ? pick.disabled : null, warn: warn ? warn.textContent : '',
    note: getComputedStyle(note).display !== 'none' ? note.textContent : '', soft: !!document.getElementById('fntSoftBtn') };
});
// the Phone font list (#fntPhoneSheet): opened by the drop-down, a row per font (data-k: 'default' or the package)
const openList = async (page) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); };
const closeList = (page) => page.evaluate(() => fntPhoneClose());
const sheet = (page) => page.evaluate(() => ({ open: document.getElementById('fntPhoneSheet').classList.contains('show'),
  rows: [...document.querySelectorAll('#fntPhoneList .fnt-pr')].map(r => ({ k: r.dataset.k, name: r.querySelector('.ab-sheet-txt > span').textContent, sub: r.querySelector('.ab-sheet-sub').textContent,
    lock: !!r.querySelector('.fnt-pr-lock'), cur: r.classList.contains('current') })) }));
const tap = async (page, k) => { await page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]').click(); await page.waitForTimeout(300); };
const calls = (page, re) => page.evaluate((s) => window.__fmk.calls.filter(c => new RegExp(s).test(c)), re.source);
const toast = (page) => page.evaluate(() => document.getElementById('toastMsg').textContent);
const NOTE85 = "On One UI 8.5, Samsung's Font style takes only fonts signed by Samsung or Monotype. The others need Root, or Shizuku running as the system user (UID 1000) or root (UID 0).";

(async () => {
  const b = await chromium.launch();
  // ---- Root ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    const c = await cards(page);
    check('1. Root: the Phone font card is open (ROOT), the drop-down shows the font in use, no locked row, no One UI note', c.cTag === 'ROOT' && c.pick === 'Inter Display' && c.pickOff === false && !c.cLock && c.note === '', JSON.stringify(c));
    check('   System emoji open (ROOT): Default, the downloaded font, Use, Soft reboot', c.sTag === 'ROOT' && /Phone emoji Default/.test(c.sBody) && c.soft && !c.sLock, JSON.stringify([c.sTag, c.sBody]));
    await openList(page);
    const s = await sheet(page), cur = s.rows.filter(r => r.cur);
    check('   the list: nothing locked, the font in use marked In use', s.open && !s.rows.some(r => r.lock) && cur.length === 1 && cur[0].k === INTER && / · In use$/.test(cur[0].sub), JSON.stringify(s.rows));
    await tap(page, INTER);
    check('   a tap on the font in use closes the list and sends nothing', !(await sheet(page)).open && (await calls(page, /^fontMaker(Apply|Reset)/)).length === 0, JSON.stringify(await calls(page, /^fontMaker(Apply|Reset)/)));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- the system user (Shizuku as UID 1000): a font from the list, then Default ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'system', hold: true });
    const c0 = await cards(page);
    check('2. the system user: the card asks the app ("Checking…") and is open as SYSTEM USER', c0.cTag === 'SYSTEM USER' && c0.pick === 'Checking…' && !c0.cLock, JSON.stringify([c0.cTag, c0.cBody]));
    check('   System emoji: the standard locked row, "Needs Super User", disabled', c0.sTag === 'LOCKED' && c0.sLock && c0.sLock.btn && c0.sLock.why === 'Needs Super User' && /^Use as the phone's emoji/.test(c0.sLock.name), JSON.stringify(c0.sLock));
    check('   the system user is never called root', !/\broot\b/i.test(c0.cTag) && !/root/i.test(c0.cBody), c0.cBody);
    check('   only the changer status was asked (no system emoji status off its gate)', (await page.evaluate(() => window.__fmk.calls.filter(x => /Status$/.test(x)))).join() === 'fontMakerChangerStatus', await page.evaluate(() => window.__fmk.calls.join()));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await openList(page);
    await tap(page, LATO);                                                         // Lato, made here, not installed
    const busy = await cards(page);
    check('3. Lato chosen: fontMakerApply(id), the drop-down says "Applying…" and is off while it runs', (await calls(page, /^fontMakerApply/)).join() === 'fontMakerApply:c4d5e6' && busy.pick === 'Applying…' && busy.pickOff === true, JSON.stringify([await calls(page, /^fontMakerApply/), busy.pick, busy.pickOff]));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await page.evaluate(() => window.__fmk.stepAll());                                // the status asked again after the apply
    await page.waitForTimeout(100);
    const after = await cards(page);
    check('   the read-back: sans.loc and the file with its size, hash and label', /Read back from the phone:/.test(after.cBody) && /sans\.loc \/data\/app_fonts\/0\/Lato_8b21d0#Lato_8b21d0/.test(after.cBody) && /DroidSans\.ttf · 656 KB · 3f1c9a7e5b2d · u:object_r:app_fonts_data_file:s0/.test(after.cBody), after.cBody);
    await openList(page);
    const s = await sheet(page), cur = s.rows.filter(r => r.cur);
    await closeList(page);
    check('   Lato is the phone font now: the drop-down shows it, the list marks it In use', after.pick === 'Lato' && after.pickOff === false && cur.length === 1 && cur[0].k === LATO && / · In use$/.test(cur[0].sub), JSON.stringify([after.pick, cur]));
    check('   a toast says it is applied', await toast(page) === 'The font is applied.');
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'system-user-applied.png' });
    await openList(page);
    await tap(page, 'default');
    const rs = await cards(page);
    check('4. Default chosen: fontMakerReset, "Resetting…", the drop-down off while it runs', (await calls(page, /^fontMakerReset/)).length === 1 && rs.pick === 'Resetting…' && rs.pickOff === true, JSON.stringify([rs.pick, rs.pickOff]));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    const r = await cards(page);
    check('   back to Default: the drop-down shows Default, sans.loc default#default, the toast', r.pick === 'Default' && /sans\.loc default#default/.test(r.cBody) && await toast(page) === 'The default font is back.', JSON.stringify([r.pick, r.cBody]));
    await openList(page);
    const d = (await sheet(page)).rows.find(x => x.k === 'default') || {};
    await tap(page, 'default');
    check('   Default is the one in use now: a tap on it sends nothing', d.cur === true && (await calls(page, /^fontMakerReset/)).length === 1, JSON.stringify(d));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- Shizuku as root (UID 0) ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'uid0' });
    const c = await cards(page);
    check('5. Shizuku as UID 0: both cards open, both ROOT, the drop-down shows the font in use', c.cTag === 'ROOT' && c.sTag === 'ROOT' && !c.cLock && !c.sLock && c.soft && c.pick === 'Inter Display', JSON.stringify([c.cTag, c.sTag, c.pick]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- the ADB shell (Shizuku as UID 2000): the font changer locked ----
  {
    const dialogs = [];
    const { ctx, page, errors } = await open(b, { mode: 'shell' }, dialogs);
    const c = await cards(page);
    check('6. the ADB shell: the card is LIMITED, no locked row; the drop-down asks for a font and stays on', c.cTag === 'LIMITED' && !c.cLock && c.pick === 'Choose a font' && c.pickOff === false, JSON.stringify(c));
    check('   One UI 8.5: the note above the drop-down, once: Font style takes only signed fonts, the others need Root or the system user', c.note === NOTE85
      && await page.evaluate(() => [...document.querySelectorAll('#fntChangerCard .fnt-note')].filter(n => /One UI/.test(n.textContent) && getComputedStyle(n).display !== 'none').length) === 1, c.note);
    check('   System emoji locked too, "Needs Super User"', c.sTag === 'LOCKED' && c.sLock && c.sLock.why === 'Needs Super User', JSON.stringify(c.sLock));
    await openList(page);
    const s = await sheet(page);
    check('   the list: a lock on each font Samsung or Monotype did not sign (Comic Neue and the two made here), none on the others', s.rows.filter(r => r.lock).map(r => r.name).join() === 'Comic Neue,Inter Display,Lato', JSON.stringify(s.rows.filter(r => r.lock).map(r => r.name)));
    check('   nothing privileged was asked', !(await page.evaluate(() => window.__fmk.calls.some(x => /Status$|Apply|Reset|softRestart/.test(x)))), await page.evaluate(() => window.__fmk.calls.join()));
    await tap(page, LATO);
    const w = await cards(page);
    check('   a tap on a locked font: the warning, nothing sent, nothing asked', w.warn === 'Lato is not signed by Samsung or Monotype, so the phone takes it only with Root, or Shizuku running as the system user (UID 1000) or root (UID 0). The font was not changed.'
      && !dialogs.length && (await calls(page, /^fontMaker(Apply|Reset|OpenFontStyle|Install)/)).length === 0, JSON.stringify([w.warn, dialogs, await calls(page, /^fontMaker(Apply|Reset|OpenFontStyle|Install)/)]));
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'shell-locked.png' });
    await openList(page);
    await tap(page, CHOCO);
    check('   a tap on a font Samsung or Monotype signed: Samsung\'s Font style opens on it, the font changer is not used', (await calls(page, /^fontMakerOpenFontStyle/)).length === 1 && await toast(page) === 'Choose Choco cooky in Font style.'
      && (await calls(page, /^fontMaker(Apply|Reset)/)).length === 0 && (await cards(page)).warn === '', JSON.stringify([await calls(page, /^fontMaker/), await toast(page)]));
    await page.evaluate(() => fntApply('c4d5e6'));
    await page.waitForTimeout(300);
    check('   fntApply called anyway goes the Root way (asks), sends nothing', dialogs.length === 1 && /The font changer needs Super User/.test(dialogs[0]) && (await calls(page, /^fontMakerApply/)).length === 0, JSON.stringify(dialogs));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- not a Samsung (Root) ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', samsung: false });
    const c = await cards(page);
    check('7. not a Samsung: SAMSUNG ONLY, no drop-down', c.cTag === 'SAMSUNG ONLY' && c.pick === null && c.cBody === '', JSON.stringify([c.cTag, c.cBody]));
    check('   the note: font packages work only on Samsung phones, fonts can still be made and exported', c.note === 'Font packages work only on Samsung phones. You can still make fonts and export them.', c.note);
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(500);
    const acts = await page.evaluate(() => [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].map(x => x.textContent + (x.disabled ? ':off' : ':on')).join('|'));
    check('   a font made here: Use it and Install off, Export and Make again on', acts === 'Use it:off|Install:off|Export:on|Make again:on', acts);
    await page.evaluate(() => [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].find(x => x.textContent === 'Export').click());
    await page.waitForTimeout(200);
    check('   Export works: fontMakerExport, the toast', (await calls(page, /^fontMakerExport/)).length === 1 && /^Saved .+\.apk$/.test(await toast(page)), JSON.stringify([await calls(page, /^fontMakerExport/), await toast(page)]));
    check('   System emoji works on any phone with root', c.sTag === 'ROOT' && !c.sLock, c.sTag);
    check('   the changer status was not asked', !(await page.evaluate(() => window.__fmk.calls.includes('fontMakerChangerStatus'))));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- an older One UI that applies self-signed fonts, the font changer locked (the ADB shell): no note, no lock ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'shell', oneUi: '', appliesSelfSigned: true });
    const c = await cards(page);
    await openList(page);
    const s = await sheet(page);
    check('8. a One UI that applies these fonts, the ADB shell: no note above the drop-down, no lock in the list', c.note === '' && s.rows.length > 1 && !s.rows.some(r => r.lock), JSON.stringify([c.note, s.rows.filter(r => r.lock).map(r => r.name)]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
