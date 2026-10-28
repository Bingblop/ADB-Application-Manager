// v7.17.0 Custom Fonts: what goes wrong outside a build, in the page's words with Java's details kept. Emoji downloads: a file no longer at its
// address (HTTP 404, "Update the app") told apart from a server that answered 503 (try later), a network failure with its detail, and a zip without
// the font (its name kept). The fonts made here (v7.19.1: My fonts is gone): pm's Failure line after "The phone did not install the font:", pm's
// answer over several lines (an exception and its trace, for an install and an uninstall) kept whole on one line, and the bridge's busy refusals (an
// Export while the font is made again, a second save screen); the font just built has these under the build result's buttons (Install and Export
// there; Export stays on after a refusal) and not in the Phone font card, a font of the list (fntInstall, fntUninstall, fntExport) has them in the
// card. The font changer, a font chosen in the Phone font list: the lock that names the system user (UID 1000) and root (UID 0), a SamsungFonts
// script's ERROR line ("cannot put DroidSans.ttf in place.") as a sentence of the page with the read-back box, and a sans.loc read back over two
// lines. System emoji: the root-only lock. In German the HTTP status stays in the sentence; in Arabic the build's warnings run right to left, each
// line in its own direction and indented at its own start.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const DL_ERRORS = {
  noto: { code: 'http', status: 404, error: 'The file is no longer at its address (HTTP 404). Update the app.' },
  openmoji: { code: 'http', status: 503, error: 'The server answered HTTP 503. Try again later.' },
  blobmoji: { code: 'network', error: 'The download failed: timeout' },
  apple: { code: 'zip_entry', error: 'The download did not contain "AppleColorEmoji.ttf".' },
};

async function open(b, opts) {
  const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS, downloadError: DL_ERRORS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const txt = (page, sel) => page.evaluate(s => { const e = document.querySelector(s); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; }, sel);
// a bridge method that answers at once with a refusal (as the bridge does for busy, locked and the like)
const refuse = (page, m, answer) => page.evaluate(([m, a]) => { window.AndroidBridge[m] = function () { window.__fmk.calls.push(m + ':refused'); return JSON.stringify(Object.assign({ ok: false }, a)); }; }, [m, answer]);
const download = async (page, id) => { await page.click('#fntEmo_' + id + ' .op-mode-btn'); await page.waitForTimeout(250); return txt(page, '#fntEmo_' + id + ' .fnt-err'); };
// the Phone font card's error lines, one string; the buttons under the build result (Use it, Install, Export, Make again)
const cardErr = (page) => page.evaluate(() => [...document.querySelectorAll('#fntChangerBody .fnt-err')].map(e => e.textContent.replace(/\s+/g, ' ').trim()).join(' | '));
const actClick = (page, l) => page.evaluate(l => [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].find(x => x.textContent === l).click(), l);
const actOn = (page, l) => page.evaluate(l => { const x = [...document.querySelectorAll('#fntBuildActs .mode-action-btn')].find(x => x.textContent === l); return x ? !x.disabled : null; }, l);
// a font chosen in the Phone font list (Lato: the font changer writes it with Root)
const choose = async (page, k) => { await page.click('#fntPhonePick'); await page.waitForTimeout(250); await page.locator('#fntPhoneList .fnt-pr[data-k="' + k + '"]').click(); await page.waitForTimeout(300); };
const LATO = 'com.monotype.android.font.Lato_8b21d0';

(async () => {
  const b = await chromium.launch();
  {
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    // ---- 1. the emoji downloads ----
    const gone = await download(page, 'noto');
    check('1. HTTP 404: the file moved away, the status kept, update the app', gone === 'The file is no longer at its address (HTTP 404). Update the app.', gone);
    const later = await download(page, 'openmoji');
    check('   HTTP 503: the server answered, try again later', later === 'The server answered HTTP 503. Try again later.', later);
    const net = await download(page, 'blobmoji');
    check('   a network failure keeps its detail', net === 'The download failed (timeout). Check the connection and try again.', net);
    const zip = await download(page, 'apple');
    check('   a zip without the font keeps the file name', zip === 'The download did not contain "AppleColorEmoji.ttf".', zip);
    check('   each line is in the error colour, and the rows offer Download again', await page.evaluate(() => ['noto', 'openmoji', 'blobmoji', 'apple'].every(id => document.querySelector('#fntEmo_' + id + ' .fnt-err .is-err')
      && document.querySelector('#fntEmo_' + id + ' .op-mode-btn').textContent === 'Download')));

    // ---- 2. the fonts made here: Install, Uninstall and Export. The font just built has its errors under the build result's buttons; a font of the
    // list (fntInstall, fntUninstall, fntExport: their row buttons are gone) has them in the Phone font card ----
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(500);
    await page.evaluate(() => {
      window.AndroidBridge.fontMakerInstall = function (id) {
        window.__fmk.calls.push('fontMakerInstall:' + id);
        setTimeout(() => window.onFontMakerInstalled(id, JSON.stringify({ ok: false, code: 'install_failed', error: 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.monotype.android.font.Lato_8b21d0 signatures do not match newer version; ignoring!]' })), 20);
        return JSON.stringify({ ok: true, job: 'jx' });
      };
    });
    await page.evaluate(() => fntInstall('c4d5e6'));                              // Install, Lato
    await page.waitForTimeout(200);
    const pm = await cardErr(page);
    check('2. pm\'s Failure line after "The phone did not install the font:", in the Phone font card', pm === 'The phone did not install the font: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.monotype.android.font.Lato_8b21d0 signatures do not match newer version; ignoring!]'
      && await txt(page, '#fntBuildActs .fnt-err') === null, pm);
    // pm's answer with no Failure line (an exception and its trace): Java sends it whole, over several lines; the details stay, on one line
    const multi = (m, cb, code, error) => page.evaluate(([m, cb, code, error]) => {
      window.AndroidBridge[m] = function (id) { window.__fmk.calls.push(m + ':' + id); setTimeout(() => window[cb](id, JSON.stringify({ ok: false, code, error })), 20); return JSON.stringify({ ok: true, job: 'jm' }); };
    }, [m, cb, code, error]);
    const EXC = "Exception occurred while executing 'install-write':\njava.lang.SecurityException: Permission Denial: writing com.android.server.pm.PackageInstallerService\n\tat android.os.Parcel.createExceptionOrNull(Parcel.java:3011)";
    const EXC1 = "Exception occurred while executing 'install-write': java.lang.SecurityException: Permission Denial: writing com.android.server.pm.PackageInstallerService at android.os.Parcel.createExceptionOrNull(Parcel.java:3011)";
    await multi('fontMakerInstall', 'onFontMakerInstalled', 'install_failed', EXC);
    await actClick(page, 'Install');                                               // Install under the font just built
    await page.waitForTimeout(200);
    const pmx = await txt(page, '#fntBuildActs .fnt-err');
    check('   pm\'s exception over several lines: "The phone did not install the font:" and every detail, on one line, under the build result\'s buttons (not in the card)', pmx === 'The phone did not install the font: ' + EXC1
      && await cardErr(page) === '', JSON.stringify([pmx, await cardErr(page)]));
    await multi('fontMakerUninstall', 'onFontMakerUninstalled', 'uninstall_failed', EXC.replace('install-write', 'uninstall'));
    await page.evaluate(() => fntUninstall('f1a2b3'));                            // Uninstall, Inter Display
    await page.waitForTimeout(200);
    const unx = await cardErr(page);
    check('   the same for an uninstall: "The phone did not uninstall the font:" and the details', unx === 'The phone did not uninstall the font: ' + EXC1.replace('install-write', 'uninstall'), unx);
    await multi('fontMakerInstall', 'onFontMakerInstalled', 'failed', 'The font could not be installed: line one\nline two');
    await page.evaluate(() => fntInstall('c4d5e6'));
    await page.waitForTimeout(200);
    const fx = await cardErr(page);
    check('   a failure whose message runs over two lines keeps the page\'s lead and both lines', fx === 'The font could not be installed: line one line two', fx);
    await refuse(page, 'fontMakerExport', { code: 'busy', error: 'This font is being made again. Wait for it to finish, then save it.' });
    await actClick(page, 'Export');                                                // Export under the font just built
    await page.waitForTimeout(100);
    const busy = await txt(page, '#fntBuildActs .fnt-err');
    check('   a busy Export: the bridge\'s reason under the buttons; Export stays on (no save screen opened)', busy === 'This font is being made again. Wait for it to finish, then save it.' && await actOn(page, 'Export') === true, busy);
    await refuse(page, 'fontMakerExport', { code: 'busy', error: 'The save screen is already open.' });
    await page.evaluate(() => fntExport('c4d5e6'));                               // Export, Lato
    await page.waitForTimeout(100);
    check('   a second save screen: "The save screen is already open."', await cardErr(page) === 'The save screen is already open.', await cardErr(page));

    // ---- 3. the font changer ----
    await refuse(page, 'fontMakerApply', { code: 'locked', error: 'Needs Root, or Shizuku running as the system user (UID 1000) or root (UID 0).' });
    await choose(page, LATO);
    const lock = await cardErr(page);
    check('3. Lato chosen in the Phone font list: the changer\'s lock names the system user (UID 1000) and root (UID 0)', lock === 'Needs Root, or Shizuku running as the system user (UID 1000) or root (UID 0).', lock);
    await refuse(page, 'sysEmojiApply', { code: 'locked', error: 'Needs Root, or Shizuku running as root (UID 0).' });
    await page.click('#fntSysUse');
    await page.waitForTimeout(100);
    check('   System emoji\'s lock: root only', await txt(page, '#fntSysBody .fnt-err') === 'Needs Root, or Shizuku running as root (UID 0).', await txt(page, '#fntSysBody .fnt-err'));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // a SamsungFonts script's ERROR line, with the read-back of what the phone holds
    const { ctx, page, errors } = await open(b, { mode: 'root', applyError: { code: 'failed', error: 'cannot put DroidSans.ttf in place.', readBack: { sansLoc: 'default#default', files: [] } } });
    await choose(page, LATO);
    const body = await page.evaluate(() => document.getElementById('fntChangerBody').innerText.replace(/\s+/g, ' ').trim());
    check('4. a script\'s ERROR line is a sentence of the page', /DroidSans\.ttf could not be put in place\./.test(body) && !/cannot put/.test(body), body);
    check('   what the phone read back is shown with it (sans.loc default#default)', /Read back from the phone: sans\.loc default#default/.test(body), body);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // a sans.loc that reads back over two lines: the page's sentence, with what it reads on one line
    const { ctx, page, errors } = await open(b, { mode: 'root', applyError: { code: 'failed', error: 'The phone did not take the font (sans.loc reads "/data/app_fonts/0/Lato_8b21d0\n#Lato_8b21d0").', readBack: { sansLoc: 'x', files: [] } } });
    await choose(page, LATO);
    const err = await cardErr(page);
    check('   sans.loc read back over two lines: "The phone did not take the font (sans.loc reads "…")."', err === 'The phone did not take the font (sans.loc reads "/data/app_fonts/0/Lato_8b21d0 #Lato_8b21d0").', err);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 5. German: the status stays in the sentence ----
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    await page.evaluate(() => LANG_ENGINE.set('de'));
    await page.waitForTimeout(600);
    const de = await download(page, 'noto');
    check('5. German: the HTTP status stays, the sentence is German', /HTTP 404/.test(de) && !/no longer at its address/.test(de) && de !== null, de);
    const deNet = await download(page, 'blobmoji');
    check('   the network detail stays as Java wrote it', /\(timeout\)/.test(deNet) && !/Check the connection/.test(deNet), deNet);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 6. Arabic: the warnings run right to left, each line in its own direction ----
    const { ctx, page, errors } = await open(b, { mode: 'root', picks: { regular: 'static' } });
    await page.evaluate(() => LANG_ENGINE.set('ar'));
    await page.waitForTimeout(600);
    await page.click('#fntPick_regular');
    await page.waitForTimeout(300);
    await page.selectOption('#fntEmojiSel', 'cat:twemoji');
    await page.evaluate(() => { window.__fmk.buildWarnings = ["The emoji font's positioning table (GPOS) was left out; its emoji do not need it to show.", 'A warning the page has no words for.']; });
    await page.click('#fntBuildBtn');
    await page.waitForTimeout(400);
    const w = await page.evaluate(() => {
      const ul = document.querySelector('#fntBuildResult .fnt-warns');
      const u = ul && ul.getBoundingClientRect();
      return ul ? { dir: getComputedStyle(ul).direction, items: [...ul.querySelectorAll('li')].map(li => { const c = getComputedStyle(li), r = li.getBoundingClientRect();
        return { t: li.textContent, dir: li.getAttribute('dir'), cs: c.direction, mL: parseFloat(c.marginLeft), mR: parseFloat(c.marginRight), inside: r.left >= u.left && r.right <= u.right }; }) } : null;
    });
    check('6. Arabic: the list runs right to left; the Arabic line is indented on the right', w && w.dir === 'rtl' && w.items[0] && w.items[0].cs === 'rtl' && w.items[0].mR > 0 && w.items[0].mL === 0, JSON.stringify(w));
    check('   the English line runs left to right and is indented on its left, its bullet inside the list', w && w.items[1] && w.items[1].cs === 'ltr' && w.items[1].mL > 0 && w.items[1].mR === 0 && w.items.every(i => i.inside), JSON.stringify(w && w.items));
    check('   the known warning is in Arabic (right to left), the unknown one stays English (left to right)', w && w.items.length === 2 && /[؀-ۿ]/.test(w.items[0].t) && /GPOS/.test(w.items[0].t)
      && w.items[1].t === 'A warning the page has no words for.' && w.items.every(i => i.dir === 'auto'), JSON.stringify(w && w.items));
    await page.screenshot({ path: 'warnings-ar.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
