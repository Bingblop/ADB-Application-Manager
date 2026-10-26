// v7.17.0 Custom Fonts, System emoji on a phone the swap cannot run on, and what Soft reboot says. The status names a block before anything is tried:
// emoji updated by Google Play services (emoji_updated), or no list of mounts to read (mounts_unreadable): the reason shows in the page's words as a
// warning, "Use as the phone's emoji" is off and nothing is sent. With emoji_updated, Undo stays on for a swap that is on; without the list of mounts
// the app refuses Undo too (it changes nothing it cannot tell apart), so Undo is greyed out and sends nothing, and with no note of a swap the card
// says Unknown, not Default. Soft reboot's question says what the apps come back with: the new emoji while a swap is on, nothing about the emoji
// while the status is not known (a status that did not answer, with Try again on the card) or cannot tell. The Help Guide says the same.
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
    // ---- 1b. emoji updated, a swap on (laid before the update): Undo stays on, the app can still take its own binds off ----
    const { ctx, page, errors } = await open(b, { mode: 'root', sysActive: 'twemoji', sysBlocked: { code: 'emoji_updated', error: UPDATED } });
    const s = await sys(page);
    check('1b. emoji updated with a swap on: Use off, Undo on', s.blocked === UPDATED && s.use === false && s.undo === true && /Phone emoji Twemoji/.test(s.body), JSON.stringify(s));
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 2. no list of mounts, the app's note of a swap of this boot: the swap is named, and Undo is greyed out, since the app refuses it for the same
    // reason (it changes nothing it cannot tell apart); the reason on the card says a restart puts the emoji back ----
    const { ctx, page, errors } = await open(b, { mode: 'uid0', sysActive: 'twemoji', sysBlocked: { code: 'mounts_unreadable', error: NO_MOUNTS }, sysUndoError: { code: 'mounts_unreadable', error: NO_MOUNTS } });
    const s = await sys(page);
    check('2. no list of mounts: the reason, Use off, the swap named, Undo there but off', s.blocked === NO_MOUNTS && s.use === false && s.undo === false && s.soft === true && /Phone emoji Twemoji/.test(s.body), JSON.stringify(s));
    await page.evaluate(() => fntSysUndo());
    await page.waitForTimeout(150);
    check('   Undo sends nothing', !(await page.evaluate(() => window.__fmk.calls.some(c => /^sysEmojiUndo/.test(c)))), await page.evaluate(() => window.__fmk.calls.join()));
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
    // ---- 4. no list of mounts and no note of a swap (the app's storage was cleared): the app cannot tell, so the card does not say Default ----
    const { ctx, page, errors } = await open(b, { mode: 'root', sysBlocked: { code: 'mounts_unreadable', error: NO_MOUNTS } });
    const s = await sys(page);
    check('4. no list of mounts, no note: Phone emoji Unknown (not Default), no Undo, Use off', /Phone emoji Unknown/.test(s.body) && !/Default/.test(s.body) && s.undo === null && s.use === false && s.blocked === NO_MOUNTS, JSON.stringify(s));
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(250);
    const q = await sheet(page);
    check('   Soft reboot says nothing about the emoji it cannot tell', q.open && /every app closes/.test(q.text) && !/emoji/.test(q.text), JSON.stringify(q));
    await page.click('#mpAskNo');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 5. Soft reboot while the status is not known ----
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    await page.evaluate(() => { window.AndroidBridge.sysEmojiStatus = () => JSON.stringify({ ok: false, code: 'no_answer', error: 'The phone did not answer. Try again.' }); fntStatusAgain('sys'); });
    await page.waitForTimeout(100);
    const s = await sys(page);
    check('5. a status that did not start: Unknown, the reason, Try again', /Phone emoji Unknown/.test(s.body) && /The phone did not answer\. Try again\./.test(s.body) && s.again === true, JSON.stringify(s));
    await page.click('#fntSoftBtn');
    await page.waitForTimeout(250);
    const q = await sheet(page);
    check('   Soft reboot says nothing about the emoji it does not know', q.open && /every app closes/.test(q.text) && !/emoji/.test(q.text), JSON.stringify(q));
    await page.click('#mpAskNo');
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  {
    // ---- 6. the Help Guide says what the card and the app do without the list of mounts ----
    const { ctx, page, errors } = await open(b, { mode: 'root' });
    await page.evaluate(() => openHelpGuide('fonts-system-emoji'));
    await page.waitForFunction(() => hg.cur === 'fonts-system-emoji', null, { timeout: 5000 });
    const li = await page.evaluate(() => { const t = [...document.querySelectorAll('#hgTopic li')].find(x => /list of mounts/.test(x.textContent)); return t ? t.textContent.replace(/\s+/g, ' ').trim() : ''; });
    check('6. the guide: Use and Undo greyed out without the list of mounts, a soft reboot keeps the swap, a normal restart takes it off', /Use as the phone's emoji/.test(li) && /Undo/.test(li) && /greyed out/.test(li)
      && /soft reboot keeps it/i.test(li) && /normal restart/.test(li) && !/can always be taken off/.test(li), li);
    check('   it names the swap from the app\'s note of this boot, and Unknown without one', /Unknown/.test(li), li);
    check('   no page errors', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
