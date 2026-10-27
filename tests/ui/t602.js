// v7.18.0 Google Fonts, Use in Make a font: a downloaded variable family (Roboto) hands its upright variable file to the Regular slot (gfontsUse) and
// Make a font offers its own Bold instance; a static family (Lato) offers a Regular and a Bold choice (by default its 400 and 700 upright files; a
// family with one weight, 700 only, has no Bold by default; an italic-only one uses its italic) and hands both. The sheet closes, Make a font scrolls
// into view and a toast says "Roboto is in Make a font"; until the files are read the file chooser gate is shut (Choose waits, no Google Fonts link).
// The Name is filled in from the family unless it holds one of its Reserved Font Names (Lato): then it stays empty, and a Name that holds one (any
// case) shows the licence note under the field while Build stays usable; a Regular chosen from a file clears the Regular's names, but a Bold from
// the family keeps its own while Bold is that file (the note shows with Bold "From a file", not with None). The family's page says
// its Reserved Font Names. Use waits, with the reason, while a font is being made or the file chooser is open; a file the app refuses says why in
// the Regular slot. The family's files take the slots: a refused one leaves its slot empty (Build waits), never with a file chosen before from
// another font, and a refused Regular takes the Reserved Font Names of that earlier font with it. A family under the Ubuntu Font Licence (Ubuntu)
// says so on its page; its own name is not filled in and warns in the Name like a Reserved Font Name, but "Ubuntu derivative <a name>" is allowed
// (UFL 1.0, condition 2), and only for such a family. In Arabic both licence notes read right to left, even when they start with the family's name.
// Mock bridge: lib/fontmaker_mock.js (opts.gf; opts.hold holds the build); the list: fixtures/gfonts-catalog.json.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const CAT = JSON.parse(fs.readFileSync(fixture('gfonts-catalog.json'), 'utf8'));
const NOTE = 'Lato\'s licence keeps the name "Lato" for the original font. A font made from it is a changed copy, so give it another name.';

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
const showFam = async (page, f) => {
  await page.evaluate((f) => { if (!gf.open) gfOpen(); if (gf.view === 'detail') gfBackToList(); const s = document.getElementById('gfSearch'); s.value = f; s.dispatchEvent(new Event('input', { bubbles: true })); }, f);
  await page.waitForTimeout(250);
  await page.click('#gfList .gf-card[data-f="' + f + '"]');
  await page.waitForTimeout(200);
};
const make = (page) => page.evaluate(() => {
  const msg = document.getElementById('fntNameMsg');
  return {
    reg: fnt.pick.regular ? fnt.pick.regular.fileName : '', bold: fnt.pick.bold ? fnt.pick.bold.fileName : '', boldMode: fnt.boldMode, boldSel: document.getElementById('fntBoldSel').value,
    name: document.getElementById('fntName').value, msg: msg.style.display !== 'none' ? msg.textContent : '', build: !document.getElementById('fntBuildBtn').disabled,
    picking: fnt.picking, regRow: document.getElementById('fntRegRow').textContent.replace(/\s+/g, ' ').trim(), link: !!document.getElementById('gfFindLink'),
    regErr: fnt.pickErr.regular, regMsg: document.getElementById('fntRegMsg').textContent.replace(/\s+/g, ' ').trim(), sheet: document.getElementById('gfSheet').classList.contains('show'), toast: (document.getElementById('toast').textContent || '').trim(),
  };
});
const useCalls = (page) => page.evaluate(() => __fmk.calls.filter(c => /^gfontsUse:/.test(c)));
const useState = (page) => page.evaluate(() => { const b = document.getElementById('gfUseBtn'), w = document.getElementById('gfUseWhy'); return { disabled: b ? b.disabled : null, why: w && w.style.display !== 'none' ? w.textContent : '' }; });

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Roboto', 'Lato', 'UnifrakturCook', 'Molle'] }, hold: true });
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await showFam(page, 'Roboto');
    let u = await useState(page);
    check('1. Roboto (variable, on the phone): Use in Make a font, nothing to choose', u.disabled === false && !u.why && !(await page.evaluate(() => !!document.getElementById('gfUseReg'))), JSON.stringify(u));
    const now = await page.evaluate(() => { gfUse(); return { picking: fnt.picking, choose: document.getElementById('fntPick_regular').disabled, link: !!document.getElementById('gfFindLink'), sheet: document.getElementById('gfSheet').classList.contains('show') }; });
    check('   Use: the sheet closes; until the file is read the chooser gate is shut (Choose waits, no link)', now.picking === 'gf' && now.choose && !now.link && !now.sheet, JSON.stringify(now));
    check('   gfontsUse: only the Regular, the upright variable file', (await useCalls(page)).join(',') === 'gfontsUse:Roboto:Roboto-VariableFont_wdth,wght.ttf:regular', (await useCalls(page)).join(','));
    await page.waitForTimeout(900);
    let m = await make(page);
    const top = await page.evaluate(() => document.getElementById('fntMakeCard').getBoundingClientRect().top);
    check('   a toast: "Roboto is in Make a font"; Make a font scrolled into view', m.toast === 'Roboto is in Make a font' && top > -2 && top < 300, JSON.stringify([m.toast, top]));
    check('2. the Regular is Roboto\'s variable file; Bold: its own Bold instance', m.reg === 'Roboto-VariableFont_wdth,wght.ttf' && m.boldMode === 'instance' && m.boldSel === 'instance' && /Roboto-VariableFont_wdth,wght\.ttf/.test(m.regRow), JSON.stringify(m));
    check('   the Name: "Roboto" (no reserved names), no note; the gate open again; Build usable', m.name === 'Roboto' && !m.msg && m.picking === '' && m.build && !m.link, JSON.stringify(m));
    // a font being made: Use waits, with the reason
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(100);
    await showFam(page, 'Lato');
    u = await useState(page);
    check('3. while a font is being made: Use waits, saying why', u.disabled === true && u.why === 'A font is being made. Wait for it to finish, or cancel it.', JSON.stringify(u));
    await page.evaluate(() => gfUse());
    check('   pressing it anyway asks nothing', (await useCalls(page)).length === 1);
    await page.evaluate(() => __fmk.stepAll()); await page.waitForTimeout(300);
    u = await useState(page);
    check('   the font made: Use is usable again, the reason gone', u.disabled === false && !u.why, JSON.stringify(u));
    // the family's page names its Reserved Font Names; the static choices
    const lato = await page.evaluate(() => ({ rfn: [...document.querySelectorAll('#gfGet .gf-rfn')].map(x => x.textContent), reg: document.getElementById('gfUseReg').value, bold: document.getElementById('gfUseBold').value,
      opts: [...document.getElementById('gfUseBold').options].map(o => o.value).slice(0, 3) }));
    check('4. Lato\'s page: "\\"Lato\\" is a Reserved Font Name: …"', lato.rfn.join('|') === '"Lato" is a Reserved Font Name: a font made from this family must have another name.', lato.rfn.join('|'));
    check('   Regular: Lato-Regular.ttf, Bold: Lato-Bold.ttf (None first among the choices)', lato.reg === 'Lato-Regular.ttf' && lato.bold === 'Lato-Bold.ttf' && lato.opts[0] === '', JSON.stringify(lato));
    // the Name the user typed before stays; it holds "lato": the note
    await page.evaluate(() => { const n = document.getElementById('fntName'); n.value = 'Roboto Neo'; fntNameInput('Roboto Neo'); });
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    m = await make(page);
    check('5. static Lato: gfontsUse for the Regular and the Bold', (await useCalls(page)).slice(1).join(',') === 'gfontsUse:Lato:Lato-Regular.ttf:regular,gfontsUse:Lato:Lato-Bold.ttf:bold', (await useCalls(page)).join(','));
    check('   Regular Lato-Regular.ttf, Bold the file Lato-Bold.ttf', m.reg === 'Lato-Regular.ttf' && m.bold === 'Lato-Bold.ttf' && m.boldMode === 'file' && m.boldSel === 'file' && m.picking === '', JSON.stringify(m));
    check('   the Name typed before is kept ("Roboto Neo"), no note', m.name === 'Roboto Neo' && !m.msg, JSON.stringify(m));
    await page.fill('#fntName', 'My LATO');
    m = await make(page);
    check('6. a Name holding "lato" (any case): the licence note; Build stays usable', m.msg === NOTE && m.build, JSON.stringify(m));
    await page.fill('#fntName', 'Summer Sans');
    m = await make(page);
    check('   another name: no note', !m.msg && m.build, JSON.stringify(m));
    // the Name not touched: a reserved name is not filled in
    await page.evaluate(() => { fnt.nameTouched = false; fnt.name = ''; document.getElementById('fntName').value = ''; });
    await showFam(page, 'Lato');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    m = await make(page);
    check('7. the Name left to the page: "Lato" is not filled in (it is a Reserved Font Name)', m.name === '' && !m.msg && !m.build && m.reg === 'Lato-Regular.ttf', JSON.stringify(m));
    await page.fill('#fntName', 'lato');
    check('   typed anyway: the note', (await make(page)).msg === NOTE);
    // Bold None, another Regular
    await showFam(page, 'Lato');
    await page.selectOption('#gfUseReg', 'Lato-Light.ttf');
    await page.selectOption('#gfUseBold', '');
    const n0 = (await useCalls(page)).length;
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    m = await make(page);
    check('8. Regular Lato-Light.ttf, Bold None: only the Regular asked; Bold None', (await useCalls(page)).slice(n0).join(',') === 'gfontsUse:Lato:Lato-Light.ttf:regular' && m.reg === 'Lato-Light.ttf' && m.boldMode === 'none' && m.boldSel === 'none', JSON.stringify([await useCalls(page), m]));
    await showFam(page, 'Lato');
    const kept = await page.evaluate(() => [document.getElementById('gfUseReg').value, document.getElementById('gfUseBold').value]);
    check('   the family\'s page keeps that choice', kept.join(',') === 'Lato-Light.ttf,', kept.join(','));
    // one weight only (700): it is the Regular, Bold None; italic only: the italic file
    await showFam(page, 'UnifrakturCook');
    const uc = await page.evaluate(() => [document.getElementById('gfUseReg').value, document.getElementById('gfUseBold').value]);
    check('9. UnifrakturCook (700 only): Regular its one file, Bold None (never the Regular\'s own file)', uc.join(',') === 'UnifrakturCook-Bold.ttf,', uc.join(','));
    await showFam(page, 'Molle');
    const mo = await page.evaluate(() => [document.getElementById('gfUseReg').value, document.getElementById('gfUseBold').value]);
    check('   Molle (italic only): Regular Molle-Italic.ttf, Bold None', mo.join(',') === 'Molle-Italic.ttf,', mo.join(','));
    // the file chooser open: Use waits
    await page.evaluate(() => gfClose());
    const pick = await page.evaluate(() => { fntPick('bold'); gfOpen(); gfShow('Lato'); const b = document.getElementById('gfUseBtn'), w = document.getElementById('gfUseWhy'); return { d: b.disabled, why: w.style.display !== 'none' ? w.textContent : '' }; });
    check('10. the file chooser open: Use waits, saying why', pick.d && pick.why === 'The file chooser is already open.', JSON.stringify(pick));
    await page.waitForTimeout(300);
    u = await useState(page);
    check('   the chooser answered: Use is usable again', u.disabled === false && !u.why, JSON.stringify(u));
    // a Regular chosen from a file: the reserved names go
    await page.evaluate(() => gfClose());
    await page.fill('#fntName', 'My Lato');
    check('11. with Lato in the Regular slot, "My Lato" has the note', (await make(page)).msg === NOTE);
    await page.click('#fntPick_regular');
    await page.waitForTimeout(400);
    m = await make(page);
    check('   a Regular chosen from a file: the note goes (the family\'s names no longer apply)', m.reg === 'InterVariable.ttf' && !m.msg && m.name === 'My Lato', JSON.stringify(m));
    // Lato's Regular and Bold, then another Regular from a file: the Bold is still Lato's, so its names still count while Bold is that file
    await showFam(page, 'Lato');
    await page.selectOption('#gfUseReg', 'Lato-Regular.ttf');
    await page.selectOption('#gfUseBold', 'Lato-Bold.ttf');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    await page.click('#fntPick_regular');
    await page.waitForTimeout(400);
    await page.fill('#fntName', 'Lato Rounded');
    m = await make(page);
    check('11b. Lato\'s Bold kept, a Regular from a file: "Lato Rounded" still has the note (the Bold is Lato\'s); Build usable', m.reg === 'InterVariable.ttf' && m.bold === 'Lato-Bold.ttf' && m.boldMode === 'file' && m.msg === NOTE && m.build, JSON.stringify(m));
    await page.evaluate(() => fntBoldChange('none'));
    check('   Bold None (Android makes it from the Regular): no note', !(await make(page)).msg);
    await page.evaluate(() => fntBoldChange('file'));
    check('   Bold "From a file" again: the note', (await make(page)).msg === NOTE);
    await page.evaluate(() => { __fmk.picks.bold = 'staticBold'; fntPick('bold'); });
    await page.waitForTimeout(400);
    m = await make(page);
    check('   a Bold chosen from a file (the same Lato-Bold.ttf, not from Google Fonts): no note', m.bold === 'Lato-Bold.ttf' && m.boldMode === 'file' && !m.msg, JSON.stringify(m));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // a file the app refuses
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Roboto'], useError: { code: 'gf_changed', error: "This family's files changed on the phone. Delete it and download it again." } } });
    await showFam(page, 'Roboto');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    const m = await make(page);
    check('12. the file changed on the phone: the Regular slot says why; the gate open again; nothing chosen', m.regErr === "This family's files changed on the phone. Delete it and download it again." && m.regMsg === m.regErr && m.picking === '' && !m.reg, JSON.stringify(m));
    check('   the link is back', m.link, JSON.stringify(m));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the family's files take the slots: one the app refuses leaves its slot empty, never with the file chosen before
  {
    const CHANGED = "This family's files changed on the phone. Delete it and download it again.";
    const slots = (page) => page.evaluate(() => ({ boldErr: fnt.pickErr.bold, reserved: fnt.reserved.join(','), family: fnt.reservedFamily, boldReserved: fnt.reservedBold.names.join(','), boldFamily: fnt.reservedBold.family }));
    let { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], useError: { slot: 'bold', code: 'gf_changed', error: CHANGED } }, picks: { bold: 'vf' } });
    await page.evaluate(() => fntPick('regular')); await page.waitForTimeout(400);
    await page.evaluate(() => { fntBoldChange('file'); fntPick('bold'); }); await page.waitForTimeout(400);
    await page.fill('#fntName', 'Summer Sans');
    let m = await make(page);
    check('13. a Regular and a Bold chosen from files first (InterVariable.ttf both): Build usable', m.reg === 'InterVariable.ttf' && m.bold === 'InterVariable.ttf' && m.boldMode === 'file' && m.build, JSON.stringify(m));
    await showFam(page, 'Lato');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    m = await make(page);
    let s2 = await slots(page);
    check('   Lato\'s Bold refused: the Bold slot says why and holds nothing (not the file chosen before); Build waits', m.reg === 'Lato-Regular.ttf' && m.bold === '' && m.boldMode === 'file' && s2.boldErr === CHANGED && !m.build, JSON.stringify([m, s2]));
    check('   the Regular\'s names are Lato\'s, the empty Bold slot has none', s2.reserved === 'Lato' && s2.family === 'Lato' && s2.boldReserved === '' && s2.boldFamily === '', JSON.stringify(s2));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
    ({ ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Lato'], useError: { slot: 'regular', code: 'gf_changed', error: CHANGED } } }));
    await page.evaluate(() => fntPick('regular')); await page.waitForTimeout(400);
    await page.fill('#fntName', 'Summer Sans');
    m = await make(page);
    check('14. a Regular chosen from a file first (InterVariable.ttf): Build usable', m.reg === 'InterVariable.ttf' && m.build, JSON.stringify(m));
    await showFam(page, 'Lato');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    m = await make(page);
    s2 = await slots(page);
    check('   Lato\'s Regular refused: the Regular slot says why and holds nothing; Build waits', m.reg === '' && m.regErr === CHANGED && m.regMsg === CHANGED && !m.build && m.picking === '', JSON.stringify(m));
    check('   its Bold still comes (Lato-Bold.ttf); the empty Regular slot has no Reserved Font Names, the Bold has Lato\'s', m.bold === 'Lato-Bold.ttf' && m.boldMode === 'file' && s2.reserved === '' && s2.family === ''
      && s2.boldReserved === 'Lato' && s2.boldFamily === 'Lato', JSON.stringify([m, s2]));
    await page.fill('#fntName', 'My Lato');
    check('   "My Lato": the note, for the Bold', (await make(page)).msg === NOTE);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the Ubuntu Font Licence: the family's own name, or "<family> derivative <a name>"
  {
    const { ctx, page, errors } = await open(b, { gf: { catalog: CAT, downloads: ['Ubuntu', 'Lato', 'Roboto'] } });
    const UNOTE = 'Ubuntu\'s licence keeps the name "Ubuntu" for the original font. A font made from it is a changed copy, so give it another name.';
    await showFam(page, 'Ubuntu');
    const notes = await page.evaluate(() => ({ ufl: (document.getElementById('gfUflNote') || {}).textContent || '', rfn: [...document.querySelectorAll('#gfGet .gf-rfn')].length }));
    check('15. Ubuntu\'s page: it is under the Ubuntu Font Licence, another name or "Ubuntu derivative" and a name of one\'s own', notes.ufl === 'Ubuntu is under the Ubuntu Font Licence: a font made from this family must have another name, or "Ubuntu derivative" and a name of your own.' && notes.rfn === 1, JSON.stringify(notes));
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    let m = await make(page);
    check('   Use: the Regular is Ubuntu-Regular.ttf; the Name is not filled in with "Ubuntu"', m.reg === 'Ubuntu-Regular.ttf' && m.name === '' && !m.msg && !m.build, JSON.stringify(m));
    await page.fill('#fntName', 'Ubuntu Foo');
    m = await make(page);
    check('   "Ubuntu Foo": the licence note; Build stays usable', m.msg === UNOTE && m.build, JSON.stringify(m));
    await page.fill('#fntName', 'Ubuntu derivative');
    check('   "Ubuntu derivative" with no name of one\'s own: the note', (await make(page)).msg === UNOTE);
    for (const n of ['Ubuntu derivative Mine', 'ubuntu Derivative Mine']) {
      await page.fill('#fntName', n);
      m = await make(page);
      check('   "' + n + '": no note', !m.msg && m.build, JSON.stringify(m));
    }
    await page.evaluate(() => { fnt.nameTouched = false; fnt.name = ''; document.getElementById('fntName').value = ''; });
    await showFam(page, 'Lato');
    check('16. Lato (OFL) has no Ubuntu Font Licence note', !(await page.evaluate(() => !!document.getElementById('gfUflNote'))));
    await page.click('#gfUseBtn');
    await page.waitForTimeout(400);
    await page.fill('#fntName', 'Lato derivative Mine');
    check('   "Lato derivative Mine": the Reserved Font Name note (the derivative form is the Ubuntu Font Licence\'s alone)', (await make(page)).msg === NOTE);
    await page.evaluate(() => { fnt.nameTouched = false; fnt.name = ''; document.getElementById('fntName').value = ''; });
    await showFam(page, 'Roboto');
    await page.click('#gfUseBtn');
    await page.waitForTimeout(900);
    m = await make(page);
    check('   Roboto (Apache): the Name "Roboto", no note', m.name === 'Roboto' && !m.msg && m.build, JSON.stringify(m));
    // Arabic: a licence note is a sentence of the page (txF) inside translate="no", and must run right to left even when it starts with the name
    const rfns = () => page.evaluate(() => [...document.querySelectorAll('#gfGet .gf-rfn')].map(el => {
      const cs = getComputedStyle(el), r = el.getBoundingClientRect(), rg = document.createRange(), tn = el.firstChild;
      rg.setStart(tn, 0); rg.setEnd(tn, 1);
      const c = rg.getBoundingClientRect();
      return { id: el.id, text: el.textContent, dir: cs.direction, bidi: cs.unicodeBidi, ar: /[\u0600-\u06ff]/.test(el.textContent), right: Math.round(r.right - parseFloat(cs.paddingRight) - c.right) };
    }));
    await page.evaluate(async () => {
      await LANG_ENGINE.set('ar');                                     // the dictionary, with the two notes as ar.js writes such a sentence: the name first
      const P = window.__LANGS.ar.p, put = (k, v) => { for (let i = P.length - 1; i >= 0; i--) if (P[i][0] === k) P.splice(i, 1); P.push([k, v]); };
      put('"{0}" is a Reserved Font Name: a font made from this family must have another name.', '«{0}» اسم خط محجوز: يجب أن يحمل الخط المصنوع من هذه العائلة اسمًا آخر.');
      put('{0} is under the Ubuntu Font Licence: a font made from this family must have another name, or "{1} derivative" and a name of your own.', '«{0}» تحت رخصة خطوط أوبونتو: يجب أن يحمل الخط المصنوع من هذه العائلة اسمًا آخر، أو «{1} derivative» واسمًا من اختيارك.');
      await LANG_ENGINE.set('ar');
    });
    await page.waitForTimeout(400);
    for (const f of ['Ubuntu', 'Lato']) {
      await showFam(page, f);
      const n = await rfns();
      check((f === 'Ubuntu' ? '17. Arabic, ' : '   ') + f + ': its note in Arabic, starting with the name, runs right to left from the right edge', n.length === 1 && n.every(x => x.ar && x.dir === 'rtl' && x.bidi !== 'plaintext' && Math.abs(x.right) <= 2), JSON.stringify(n));
    }
    await page.screenshot({ path: 'notes-ar.png' });
    await page.evaluate(() => LANG_ENGINE.set('en')); await page.waitForTimeout(300);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
