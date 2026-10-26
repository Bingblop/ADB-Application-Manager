// v7.17.0 Custom Fonts, the gates. The font changer unlocks like the tracker switches (Root with su, or Shizuku as the system user UID 1000 or root
// UID 0); System emoji only with root (Root with su, or Shizuku as UID 0). Per working mode: Root (both open, ROOT), the system user (changer open as
// SYSTEM USER, System emoji the standard locked row "Needs Super User"), Shizuku as UID 0 (both open), the ADB shell UID 2000 (both locked: the changer
// row says "Needs Root or the system user"; each Apply in My fonts keeps the lock and a tap goes the Root way, nothing is sent), and a phone that is
// not a Samsung (the changer says it needs a Samsung phone; Install and Apply are off; the font packages can still be exported). One UI 8.5 says once,
// above My fonts, that Samsung's Font style setting will not apply these fonts and Apply is the way; an older One UI does not. Under the system user
// Apply writes the font (fontMakerApply), the card shows the read-back (sans.loc, the files with size, hash and label) and the new font in use, and
// Reset to Default goes back (sans.loc default#default). The words "root" never name the system user.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };

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
  const applies = [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => { const a = [...r.querySelectorAll('.mode-action-btn')].find(x => /Apply/.test(x.textContent)); return a ? (a.classList.contains('locked') ? 'locked' : a.disabled ? 'off' : 'on') + (a.querySelector('svg') ? '+lock' : '') : 'none'; });
  const installs = [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => { const a = r.querySelector('.mode-btn-row .mode-action-btn'); return a.textContent + (a.disabled ? ':off' : ':on'); });
  const exports = [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => { const a = [...r.querySelectorAll('.mode-action-btn')].find(x => x.textContent === 'Export'); return a && !a.disabled; });
  const note = document.getElementById('fntDeviceNote');
  return { cTag: t('fntChangerTag'), sTag: t('fntSysTag'), cBody: t('fntChangerBody'), sBody: t('fntSysBody'), cLock: lockRow('fntChangerBody'), sLock: lockRow('fntSysBody'), applies, installs, exports,
    note: getComputedStyle(note).display !== 'none' ? note.textContent : '', soft: !!document.getElementById('fntSoftBtn'), reset: document.getElementById('fntResetBtn') ? !document.getElementById('fntResetBtn').disabled : null };
});

(async () => {
  const b = await chromium.launch();
  // ---- Root ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    const c = await cards(page);
    check('1. Root: the changer is open (ROOT), the font in use, Reset on', c.cTag === 'ROOT' && /Phone font Inter Display/.test(c.cBody) && c.reset === true && !c.cLock, JSON.stringify(c));
    check('   System emoji open (ROOT): Default, the downloaded font, Use, Soft reboot', c.sTag === 'ROOT' && /Phone emoji Default/.test(c.sBody) && c.soft && !c.sLock, JSON.stringify([c.sTag, c.sBody]));
    check('   Apply: off on the font in use, on for the other', c.applies.join() === 'off,on', c.applies.join());
    check('   One UI 8.5: the note above My fonts, once', /^On One UI 8\.5, Samsung's Font style setting does not apply fonts made on this phone\. Use Apply instead: it needs Root, or the system user through Shizuku\.$/.test(c.note)
      && await page.evaluate(() => document.querySelectorAll('#fntLibCard .fnt-note').length) === 1, c.note);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- the system user (Shizuku as UID 1000): Apply and Reset ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'system', hold: true });
    const c0 = await cards(page);
    check('2. the system user: the changer asks the app ("Checking…") and is open as SYSTEM USER', c0.cTag === 'SYSTEM USER' && /Phone font Checking…/.test(c0.cBody), JSON.stringify([c0.cTag, c0.cBody]));
    check('   System emoji: the standard locked row, "Needs Super User", disabled', c0.sTag === 'LOCKED' && c0.sLock && c0.sLock.btn && c0.sLock.why === 'Needs Super User' && /^Use as the phone's emoji/.test(c0.sLock.name), JSON.stringify(c0.sLock));
    check('   the system user is never called root', !/\broot\b/i.test(c0.cTag) && !/root/i.test(c0.cBody), c0.cBody);
    check('   only the changer status was asked (no system emoji status off its gate)', (await page.evaluate(() => window.__fmk.calls.filter(x => /Status$/.test(x)))).join() === 'fontMakerChangerStatus', await page.evaluate(() => window.__fmk.calls.join()));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await page.click('#fntLib_c4d5e6 .mode-action-btn:nth-child(2)');               // Apply on Lato
    await page.waitForTimeout(50);
    const busy = await cards(page);
    check('3. Apply: fontMakerApply(id), "Applying…" on the row, Reset off while it runs', await page.evaluate(() => window.__fmk.calls.includes('fontMakerApply:c4d5e6')) && /Applying…/.test(await page.evaluate(() => document.getElementById('fntLib_c4d5e6').textContent)) && busy.reset === false, JSON.stringify(busy));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await page.evaluate(() => window.__fmk.stepAll());                                // the status asked again after the apply
    await page.waitForTimeout(100);
    const after = await cards(page);
    check('   the read-back: sans.loc and the file with its size, hash and label', /Read back from the phone:/.test(after.cBody) && /sans\.loc \/data\/app_fonts\/0\/Lato_8b21d0#Lato_8b21d0/.test(after.cBody) && /DroidSans\.ttf · 656 KB · 3f1c9a7e5b2d · u:object_r:app_fonts_data_file:s0/.test(after.cBody), after.cBody);
    check('   Lato is the phone font now, In use in My fonts', /Phone font Lato/.test(after.cBody) && /In use/.test(await page.evaluate(() => document.getElementById('fntLib_c4d5e6').textContent)) && after.applies.join() === 'on,off', JSON.stringify([after.cBody, after.applies]));
    check('   a toast says it is applied', await page.evaluate(() => document.getElementById('toastMsg').textContent) === 'The font is applied.');
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'system-user-applied.png' });
    await page.click('#fntResetBtn');
    await page.waitForTimeout(50);
    check('4. Reset to Default: fontMakerReset, "Resetting…"', await page.evaluate(() => window.__fmk.calls.includes('fontMakerReset')) && /Resetting…/.test((await cards(page)).cBody));
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    await page.evaluate(() => window.__fmk.stepAll());
    await page.waitForTimeout(100);
    const r = await cards(page);
    check('   back to Default: sans.loc default#default, Reset off, the toast', /Phone font Default/.test(r.cBody) && /sans\.loc default#default/.test(r.cBody) && r.reset === false && await page.evaluate(() => document.getElementById('toastMsg').textContent) === 'The default font is back.', r.cBody);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- Shizuku as root (UID 0) ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'uid0' });
    const c = await cards(page);
    check('5. Shizuku as UID 0: both cards open, both ROOT', c.cTag === 'ROOT' && c.sTag === 'ROOT' && !c.cLock && !c.sLock && c.soft, JSON.stringify([c.cTag, c.sTag]));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- the ADB shell (Shizuku as UID 2000): locked ----
  {
    const dialogs = [];
    const { ctx, page, errors } = await open(b, { mode: 'shell' }, dialogs);
    const c = await cards(page);
    check('6. the ADB shell: the changer is the standard locked row, "Needs Root or the system user"', c.cTag === 'LOCKED' && c.cLock && c.cLock.btn && c.cLock.why === 'Needs Root or the system user' && /^Apply a font/.test(c.cLock.name), JSON.stringify(c.cLock));
    check('   System emoji locked too, "Needs Super User"', c.sTag === 'LOCKED' && c.sLock && c.sLock.why === 'Needs Super User', JSON.stringify(c.sLock));
    check('   every Apply in My fonts carries the lock', c.applies.join() === 'locked+lock,locked+lock', c.applies.join());
    check('   nothing privileged was asked', !(await page.evaluate(() => window.__fmk.calls.some(x => /Status$|Apply|Reset|softRestart/.test(x)))), await page.evaluate(() => window.__fmk.calls.join()));
    await page.click('#fntLib_c4d5e6 .mode-action-btn.locked');
    await page.waitForTimeout(300);
    check('   a tap on a locked Apply goes the Root way (asks), sends nothing', dialogs.length === 1 && /The font changer needs Super User/.test(dialogs[0]) && !(await page.evaluate(() => window.__fmk.calls.some(x => /^fontMakerApply/.test(x)))), JSON.stringify(dialogs));
    await page.evaluate(() => document.getElementById('fntChangerCard').scrollIntoView());
    await page.screenshot({ path: 'shell-locked.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- not a Samsung (Root) ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', samsung: false });
    const c = await cards(page);
    check('7. not a Samsung: the changer says it needs a Samsung phone (SAMSUNG ONLY)', c.cTag === 'SAMSUNG ONLY' && c.cBody === 'The font changer needs a Samsung phone.', JSON.stringify([c.cTag, c.cBody]));
    check('   My fonts says font packages work only on Samsung phones, export still works', c.note === 'Font packages work only on Samsung phones. You can still export them.' && c.exports.every(Boolean), JSON.stringify([c.note, c.exports]));
    check('   Install and Apply are off', c.installs.join() === 'Uninstall:on,Install:off' && c.applies.join() === 'off,off', JSON.stringify([c.installs, c.applies]));
    check('   System emoji works on any phone with root', c.sTag === 'ROOT' && !c.sLock, c.sTag);
    check('   the changer status was not asked', !(await page.evaluate(() => window.__fmk.calls.includes('fontMakerChangerStatus'))));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // ---- an older One UI that applies self-signed fonts: no note ----
  {
    const { ctx, page, errors } = await open(b, { mode: 'root', oneUi: '', appliesSelfSigned: true });
    const c = await cards(page);
    check('8. a One UI that applies these fonts: no note above My fonts', c.note === '', c.note);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
