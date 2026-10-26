// v7.17.0 Custom Fonts, System emoji on a phone the swap cannot run on, and what Soft reboot says. The status names a block before anything is tried:
// emoji updated by Google Play services (emoji_updated), or no list of mounts to read (mounts_unreadable): the reason shows in the page's words as a
// warning, "Use as the phone's emoji" is off and nothing is sent, while Undo stays on for a swap that is on. An Undo the app refuses for the same
// reason shows it in the page's words. Soft reboot's question says what the apps come back with: the new emoji while a swap is on, nothing about the
// emoji while the status is not known (a status that did not answer), with Try again on the card.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };
const UPDATED = "This phone's emoji were updated by Google Play services, and the swap cannot replace updated emoji safely. It does not run on this phone.";
const NO_MOUNTS = "The app cannot read the phone's list of mounts, so it cannot tell its own emoji swap from another tool's and changes nothing. A restart of the phone always puts its own emoji back.";

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
const sys = (page) => page.evaluate(() => {
  const on = id => { const e = document.getElementById(id); return e ? !e.disabled : null; };
  const bl = document.getElementById('fntSysBlocked');
  return { body: document.getElementById('fntSysBody').innerText.replace(/\s+/g, ' ').trim(), tag: document.getElementById('fntSysTag').textContent, use: on('fntSysUse'), undo: on('fntSysUndo'), soft: on('fntSoftBtn'), again: on('fntSysAgain'),
    blocked: bl ? bl.textContent : null, warn: bl ? bl.classList.contains('is-warn') : null };
});
const sheet = (page) => page.evaluate(() => ({ open: document.getElementById('mpAskModal').classList.contains('show'), title: document.getElementById('mpAskTitle').textContent, text: document.getElementById('mpAskText').textContent }));

(async () => {
  const b = await chromium.launch();
  {
    // ---- 1. emoji updated by Google Play services, no swap on ----
    const { ctx, page, errors } = await open(b, { mode: 'root', sysBlocked: { code: 'emoji_updated', error: UPDATED } });
    const s = await sys(page);
    check('1. emoji updated: the reason as a warning before anything is tried', s.blocked === UPDATED && s.warn === true, JSON.stringify(s));
    check('   Use is off, no Undo (no swap on), Soft reboot on', s.use === false && s.undo === null && s.soft === true, JSON.stringify(s));
    await page.evaluate(() => fntSysApply());
    await page.waitForTimeout(100);
    check('   nothing is sent', !(await page.evaluate(() => window.__fmk.calls.some(c => /^sysEmojiApply/.test(c)))));
    await page.evaluate(() => document.getElementById('fntSysCard').scrollIntoView());
    await page.screenshot({ path: 'sys-blocked.png' });
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 2. no list of mounts, a swap on: Undo stays; the app refuses it for the same reason ----
    const { ctx, page, errors } = await open(b, { mode: 'uid0', sysActive: 'twemoji', sysBlocked: { code: 'mounts_unreadable', error: NO_MOUNTS }, sysUndoError: { code: 'mounts_unreadable', error: NO_MOUNTS } });
    const s = await sys(page);
    check('2. no list of mounts: the reason, Use off, Undo on for the swap that is on', s.blocked === NO_MOUNTS && s.use === false && s.undo === true && /Phone emoji Twemoji/.test(s.body), JSON.stringify(s));
    await page.click('#fntSysUndo');
    await page.waitForTimeout(300);
    const s2 = await sys(page);
    check('   Undo refused: the reason in the page\'s words, under the buttons', /A restart of the phone always puts its own emoji back\.\s*$/.test(s2.body) && (await page.evaluate(() => document.querySelectorAll('#fntSysBody .fnt-err .is-err').length)) === 1, s2.body);
    // ---- 3. Soft reboot with a swap on ----
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(250);
    const q = await sheet(page);
    check('3. Soft reboot while a swap is on: apps start again with the new emoji', q.open && /Apps start again with the new emoji\. A normal restart puts the phone's own emoji back\.$/.test(q.text), JSON.stringify(q));
    await page.click('#mpAskNo');
    await page.waitForTimeout(200);
    check('   Cancel sends nothing', !(await page.evaluate(() => window.__fmk.calls.includes('softRestart'))));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 4. Soft reboot while the status is not known ----
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    await page.evaluate(() => { window.AndroidBridge.sysEmojiStatus = () => JSON.stringify({ ok: false, code: 'no_answer', error: 'The phone did not answer. Try again.' }); fntStatusAgain('sys'); });
    await page.waitForTimeout(100);
    const s = await sys(page);
    check('4. a status that did not start: Unknown, the reason, Try again', /Phone emoji Unknown/.test(s.body) && /The phone did not answer\. Try again\./.test(s.body) && s.again === true, JSON.stringify(s));
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(250);
    const q = await sheet(page);
    check('   Soft reboot says nothing about the emoji it does not know', q.open && /every app closes/.test(q.text) && !/emoji/.test(q.text), JSON.stringify(q));
    await page.click('#mpAskNo');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
