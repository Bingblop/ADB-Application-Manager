// v7.19.2 (the owner's three asks). 1) Custom Fonts: the Phone font card comes before Emoji fonts. 2) App Stores: the tab's "?" sits on a line of its own
// above the "Update stores on mobile data" card, outside it (in v7.19.1 it was inside that card), and the card is thinner: a button's height (36 px), all of
// it the switch, at 320, 360 and 412 px; a tap where the ? used to be lands on the switch, the ? opens the Help Guide. App Updater's switch card is as thin.
// 3) Hidden Settings: each answer of Ask agent about a setting (the row's button and the setting sheet's) leaves one short line the AI wrote with that
// setting for good (kv 'sdb_notes' by table and name): the question asks for an "In short:" line, else the answer's first sentence is kept, without
// Markdown or source numbers and at most 160 characters. The row shows it marked AI (in place of "No description yet"), the sheet under "From Ask agent";
// the search finds it, Described counts it, a later answer replaces it, a new start of the app still has it. The free web lookup (no agent) and a failed
// answer keep nothing. Mock bridges: lib/fontmaker_mock.js (Custom Fonts), lib/sdb_mock.js and lib/web_mock.js (Hidden Settings), none for App Stores.
const { chromium, PAGE } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
const sdb = require('./lib/sdb_mock.js');
const webMock = require('./lib/web_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();

  // ---- 1) Custom Fonts: Phone font above Emoji fonts ----
  {
    const ctx = await b.newContext({ viewport: { width: 412, height: 900 } });
    const page = await ctx.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(fmk.initScript, { mode: 'none' });
    await page.goto(PAGE); await page.waitForTimeout(500);
    await page.evaluate(() => switchView('fonts')); await page.waitForTimeout(400);
    const f = await page.evaluate(() => {
      const top = id => document.getElementById(id).getBoundingClientRect().top;
      return { order: [...document.querySelectorAll('#view-fonts > .color-card')].map(c => c.id).join(','), phone: top('fntChangerCard'), emoji: top('fntEmojiCard'), make: top('fntMakeCard') };
    });
    check('1. Custom Fonts: Phone font comes right after Make a font and before Emoji fonts', f.order === 'fntTop,fntMakeCard,fntChangerCard,fntEmojiCard,fntSysCard' && f.make < f.phone && f.phone < f.emoji, JSON.stringify(f));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await ctx.close();
  }

  // ---- 2) App Stores: the ? above the mobile-data card, outside it; the card thinner ----
  for (const w of [320, 360, 412]) {
    const page = await b.newPage({ viewport: { width: w, height: 800 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto(PAGE); await page.waitForTimeout(500);
    await page.evaluate(() => switchView('store')); await page.waitForTimeout(300);
    const g = await page.evaluate(() => {
      const c = document.getElementById('storeMobileCard'), row = document.getElementById('storeHelpRow'), qs = document.querySelectorAll('#view-store .help-q'), q = qs[0];
      const cr = c.getBoundingClientRect(), qr = q.getBoundingClientRect(), tr = c.querySelector('.switch-track').getBoundingClientRect();
      const where = document.elementFromPoint(cr.right - 24, cr.top + cr.height / 2);
      return { count: qs.length, inRow: !!q && row.contains(q), inCard: c.contains(q), above: qr.bottom <= cr.top, right: Math.round(cr.right - qr.right), h: Math.round(cr.height),
        track: tr.top >= cr.top && tr.bottom <= cr.bottom, corner: !!where && !!where.closest('#storeMobileCard .switch-row'), sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth };
    });
    check('2. App Stores at ' + w + ' px: one ?, on its own line above the mobile-data card and outside it, at the right', g.count === 1 && g.inRow && !g.inCard && g.above && g.right >= 0 && g.right <= 12, JSON.stringify(g));
    // (at 320 px a phone font a little wider than the test's may take the name to two lines: the card is then a line taller, never more)
    check('   the card is a button\'s height (36 px) with the whole switch inside it, and its right end is the switch', (w === 320 ? g.h <= 56 : g.h <= 36) && g.h >= 34 && g.track && g.corner, JSON.stringify(g));
    check('   no horizontal scroll', g.sw <= g.cw, JSON.stringify(g));
    await page.click('#storeMobileCard .switch-row', { position: { x: 30, y: 6 } }); await page.waitForTimeout(250);
    const after = await page.evaluate(() => ({ on: document.getElementById('storeMobileToggle').checked, kv: kvGet('store_mobile_data', false), guide: document.getElementById('helpGuideModal').classList.contains('show') }));
    check('   a tap near the card\'s top edge turns the switch on; the Help Guide stays shut', after.on && after.kv === true && !after.guide, JSON.stringify(after));
    if (w === 360) {
      await page.click('#storeHelpRow .help-q'); await page.waitForTimeout(400);
      check('   the ? opens the Help Guide at App Stores', await page.evaluate(() => document.getElementById('helpGuideModal').classList.contains('show') && document.getElementById('storeMobileToggle').checked));
      await page.evaluate(() => document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')));
      await page.evaluate(() => { switchView('installer'); }); await page.waitForTimeout(300);
      await page.evaluate(() => { const t = [...document.querySelectorAll('#instSwitch button')].find(x => /Updater/.test(x.textContent)); if (t) t.click(); }); await page.waitForTimeout(300);
      const u = await page.evaluate(() => { const c = document.getElementById('updMobileCard'); return { shown: c.offsetParent !== null, h: Math.round(c.getBoundingClientRect().height), help: !!c.querySelector('.help-q') }; });
      check('   App Updater\'s mobile-data card is as thin, with no ? inside it', u.shown && u.h <= 36 && u.h >= 34 && !u.help, JSON.stringify(u));
    }
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // ---- 3) Hidden Settings: what Ask agent found is kept with the setting ----
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(sdb.initScript);
  await page.addInitScript(webMock.install);
  await page.goto(PAGE); await page.waitForTimeout(700);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  // a fake agent that gives the next answer of window.__answers (or throws when it is an Error's message starting with "!")
  const agent = () => ev(() => {
    askAgentId = 'fake'; window.__sent = []; window.__answers = window.__answers || [];
    window.txAgentDef = () => ({ id: 'fake', name: 'Fake AI (test)', api: 'x', provider: 'fake' });
    window.txAgentReady = () => true; window.txModelFor = () => 'fake-model'; window.txEffortFor = () => 'balanced';
    window.txTurnApi = async (ag, model, effort, system, msgs, onText) => {
      window.__sent.push({ system, msgs }); const a = window.__answers.shift() || 'Nothing.';
      if (a[0] === '!') throw new Error(a.slice(1));
      onText(a); return { text: a, usage: { in: 1, out: 1 } };
    };
  });
  const find = async (q) => { await ev(s => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = s; sdbSearchInput(); }, q); await sleep(300); };
  const rowOf = (k) => ev(key => { const r = document.querySelector('#sdbList .sdb-row[data-k="' + key + '"]'); if (!r) return null;
    return { hints: [...r.querySelectorAll('.sdb-hint')].map(h => h.textContent.replace(/\s+/g, ' ').trim()), tag: !!r.querySelector('.sdb-note .sdb-note-tag'), note: (r.querySelector('.sdb-note-t') || {}).textContent || '' }; }, k);
  const notes = () => ev(() => { const raw = window.AndroidBridge.__kv.sdb_notes; return raw ? JSON.parse(raw) : null; });
  const askRow = async (k) => { await find(k); await page.locator('#sdbList .sdb-row[data-k="' + k + '"] .agent-btn').click(); await sleep(450); };
  const askBox = () => ev(() => document.getElementById('agentAskAi').innerText);

  await ev(() => switchView('settings')); await sleep(900);
  await agent();
  // a described setting, through the row's Ask agent: the "In short:" line is kept
  await ev(() => { window.__answers = ['Zen mode is **Do Not Disturb** [1]. 0 is off, 1 lets priority through, 2 is total silence, 3 alarms only.\n\nIt is safe to change.\n\n**In short:** Turns Do Not Disturb off, or picks how strict it is [2].']; });
  await askRow('zen_mode');
  let n = await notes();
  const sent = await ev(() => window.__sent[0]);
  check('3. Ask agent on a setting\'s row asks for the "In short:" line', !!sent && /End with one more line, on its own, that starts with "In short:"/.test(sent.system) && /hidden setting/.test(sent.system), JSON.stringify(sent && sent.system));
  check('   and keeps that line with the setting by table and name: no Markdown, no source numbers, the agent and the time', !!n && n['global/zen_mode'] && n['global/zen_mode'].t === 'Turns Do Not Disturb off, or picks how strict it is.'
    && n['global/zen_mode'].by === 'Fake AI' && n['global/zen_mode'].at > 0, JSON.stringify(n));
  check('   the answer says what was kept', /Kept with this setting: Turns Do Not Disturb off, or picks how strict it is\./.test(await askBox()), await askBox());
  await ev(() => agentAskClose()); await sleep(150);
  let r = await rowOf('zen_mode');
  check('   the row shows it under the description, marked AI', !!r && r.hints.length === 2 && /Do Not Disturb/.test(r.hints[0]) && r.tag && r.note === 'Turns Do Not Disturb off, or picks how strict it is.' && r.hints[1] === 'AI' + r.note, JSON.stringify(r));

  // a setting without a description: no "In short:" line, so the first sentence is kept, in place of "No description yet"
  const und = await ev(() => { for (const k of sdbData.global.keys()) if (!sdbHint('global', k) && /^sample_global_0\d\d$/.test(k)) return k; return ''; });
  await ev(u => { window.__answers = ['`' + u + '` is not a standard Android setting [3][4]. It probably belongs to an app on this phone.\n\nLeave it alone.']; }, und);
  await askRow(und);
  n = await notes();
  check('4. an answer without "In short:": its first sentence is kept', !!und && !!n['global/' + und] && n['global/' + und].t === und + ' is not a standard Android setting.', JSON.stringify([und, n['global/' + und]]));
  await ev(() => agentAskClose()); await sleep(150);
  r = await rowOf(und);
  check('   the row shows it in place of "No description yet"', !!r && r.hints.length === 1 && r.tag && r.note === und + ' is not a standard Android setting.' && !r.hints.some(h => /No description yet/.test(h)), JSON.stringify(r));
  const hits = await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'standard'; sdbSearchInput(); return [...document.querySelectorAll('#sdbList .sdb-row')].map(x => x.dataset.k); });
  check('   the search finds the setting by the kept line', hits.includes(und), JSON.stringify(hits.slice(0, 8)));
  const described = await ev(u => { sdbResetView(); sdbSetFilter('known'); sdbLimit = 100000; sdbRender(); const ks = [...document.querySelectorAll('#sdbList .sdb-row')].map(x => x.dataset.k); sdbSetFilter('all'); return ks.includes(u); }, und);
  check('   Described counts it', described);

  // the sheet's Ask agent: kept as well, and shown under "From Ask agent" (first, since the app has no description of its own)
  const sec = await ev(() => { for (const k of sdbData.secure.keys()) if (!sdbHint('secure', k) && /^sec_sample_/.test(k)) return k; return ''; });
  await ev(() => { window.__answers = ['This looks like a test switch.\nIn short: A switch an app keeps for itself; changing it does nothing useful.']; });
  await ev(s => sdbOpenEditor('secure', s), sec); await sleep(200);
  await page.click('#sdbEditAiBtn'); await sleep(450);
  n = await notes();
  const sheet = await ev(() => ({ about: document.getElementById('sdbEditAbout').innerText, heads: [...document.querySelectorAll('#sdbEditAbout .sdb-about-h')].map(h => h.textContent), ai: document.getElementById('sdbEditAi').innerText, sys: window.__sent[window.__sent.length - 1].system }));
  check('5. the setting sheet\'s Ask agent keeps the line too', !!n['secure/' + sec] && n['secure/' + sec].t === 'A switch an app keeps for itself; changing it does nothing useful.' && /In short:/.test(sheet.sys), JSON.stringify([n['secure/' + sec], sheet.sys.slice(-140)]));
  check('   its sheet shows it at once under "From Ask agent", before the guess, with the agent', sheet.heads[0] === 'From Ask agent' && sheet.heads[1] === 'What it probably is' && /A switch an app keeps for itself; changing it does nothing useful\. · Fake AI · /.test(sheet.about)
    && /Kept with this setting: A switch an app keeps/.test(sheet.ai), JSON.stringify(sheet));
  await ev(() => sdbEditClose());
  await ev(() => sdbOpenEditor('global', 'zen_mode')); await sleep(200);
  const zs = await ev(() => [...document.querySelectorAll('#sdbEditAbout .sdb-about-h')].map(h => h.textContent));
  check('   a described setting\'s sheet: What it does first, then From Ask agent', zs[0] === 'What it does' && zs.includes('From Ask agent'), JSON.stringify(zs));
  await ev(() => sdbEditClose());

  // a later answer replaces it; a long line is cut at a word, at most 160 characters
  await ev(() => { window.__answers = ['Answer.\nIn short: ' + 'Silences calls and notifications for a while, with exceptions for the people and apps you choose, and alarms that still ring whatever level is set here, until you turn it off again']; });
  await askRow('zen_mode');
  n = await notes();
  const t = n['global/zen_mode'].t;
  check('6. a later answer replaces the line; a long one is cut at a word, 160 characters at most, with …', t.length <= 160 && /…$/.test(t) && /^Silences calls and notifications/.test(t) && !/ …$/.test(t), JSON.stringify(t));
  await ev(() => agentAskClose());

  // no agent: the web lookup keeps nothing; a failed answer keeps nothing
  await ev(() => { askAgentId = ''; });
  await askRow('adb_enabled'); await sleep(400);
  n = await notes();
  check('7. without an agent the web lookup answers and nothing is kept', /What the pages say|Looking it up/.test(await askBox()) && !n['global/adb_enabled'] && !/Kept with this setting/.test(await askBox()), JSON.stringify(Object.keys(n)));
  await ev(() => agentAskClose());
  await agent();
  await ev(() => { window.__answers = ['!The service did not answer']; });
  await askRow('wifi_on');
  n = await notes();
  check('   a failed answer keeps nothing', !n['global/wifi_on'] && !/Kept with this setting/.test(await askBox()), JSON.stringify(Object.keys(n)));
  await ev(() => agentAskClose());

  // the bug check's findings: a long number in brackets never makes the citation cleanup slow; History opened in the sheet while an answer is on its way
  // keeps that saved answer on screen without a "Kept" line under it; the row's Ask agent button keeps the focus when the list is drawn again; a long name wraps
  const speed = await ev(() => { const a = 'Its value now is [1698765432100,1698765432200… and more. Leave it.\n\nSources: [1698765432100, 1698765432200…';
    const t0 = performance.now(); const t = sdbNoteFrom(a); return { ms: performance.now() - t0, t, c: sdbNoteFrom('Turns it off [1][2, 3].\nIn short: Turns it off [1][2, 3].') }; });
  check('8. an unclosed bracket of long numbers is quick to clean (no catastrophic backtracking); [1][2, 3] still go', speed.ms < 100 && speed.t === 'Its value now is [1698765432100,1698765432200… and more.' && speed.c === 'Turns it off.', JSON.stringify(speed));
  await ev(() => { window.__hold = new Promise(res => { window.__release = res; }); const turn = window.txTurnApi;
    window.txTurnApi = async (...a) => { await window.__hold; window.txTurnApi = turn; return turn(...a); };
    window.__answers = ['Lets a computer use ADB.\nIn short: Turns USB debugging on or off.']; });
  await ev(() => sdbOpenEditor('global', 'adb_enabled')); await sleep(200);
  await page.click('#sdbEditAiBtn'); await sleep(200);
  await ev(s => askHistOpen('sdbEdit', askHistSorted('sdbEdit').findIndex(e => e.name === s)), sec); await sleep(100);
  await ev(() => window.__release()); await sleep(400);
  n = await notes();
  const hist = await ev(() => ({ ai: document.getElementById('sdbEditAi').innerText, about: document.getElementById('sdbEditAbout').innerText, h: askHistGet('sdbEdit').filter(e => e.id === 'global/adb_enabled').map(e => e.answer) }));
  check('9. History opened in the sheet during an answer: the saved answer stays, with no "Kept" line under it', /Saved answer about /.test(hist.ai) && /This looks like a test switch/.test(hist.ai) && !/Kept with this setting/.test(hist.ai), JSON.stringify(hist));
  check('   the line is still kept with the setting asked about, and its sheet shows it; History does not file the other answer under it', !!n['global/adb_enabled'] && n['global/adb_enabled'].t === 'Turns USB debugging on or off.'
    && /From Ask agent/i.test(hist.about) && hist.h.length === 0, JSON.stringify([n['global/adb_enabled'], hist.h]));
  await ev(() => sdbEditClose());
  await ev(() => { window.__answers = ['Zen mode.\nIn short: Picks how strict Do Not Disturb is.']; });
  await find('zen_mode');
  await page.locator('#sdbList .sdb-row[data-k="zen_mode"] .agent-btn').focus(); await page.keyboard.press('Enter'); await sleep(450);
  const foc = await ev(() => { const a = document.activeElement; return { cls: a && a.className, k: a && a.closest && a.closest('.sdb-row') ? a.closest('.sdb-row').dataset.k : '', shown: document.getElementById('agentAskModal').classList.contains('show'), kept: /Kept with this setting/.test(document.getElementById('agentAskAi').innerText) }; });
  check('10. the list drawn again under Ask agent\'s window leaves the focus on the same row\'s Ask agent button, not on the row', foc.shown && foc.kept && /agent-btn/.test(foc.cls) && foc.k === 'zen_mode', JSON.stringify(foc));
  await ev(() => agentAskClose());
  await ev(() => { sdbNoteSave('global', 'zen_mode', '`accessibility_display_magnification_navbar_enabled_by_the_owner` is a switch.', 'Fake AI'); });
  await page.setViewportSize({ width: 320, height: 860 }); await sleep(200);
  await ev(() => sdbOpenEditor('global', 'zen_mode')); await sleep(300);
  const wide = await ev(() => { const a = document.getElementById('sdbEditAbout'), m = a.closest('.modal-sheet'); return { a: [a.scrollWidth, a.clientWidth], m: [m.scrollWidth, m.clientWidth] }; });
  check('11. at 320 px a kept line that starts with a long setting name wraps inside the sheet', wide.a[0] <= wide.a[1] + 1 && wide.m[0] <= wide.m[1] + 1, JSON.stringify(wide));
  await ev(() => sdbEditClose());
  await page.setViewportSize({ width: 400, height: 860 }); await sleep(200);

  // German: the sheet's heading is translated, the kept line (the AI's words) is not
  await ev(() => I18N.set('de')); await sleep(900);
  await ev(s => sdbOpenEditor('secure', s), sec); await sleep(400);
  const de = await ev(() => ({ head: document.querySelector('#sdbEditAbout .sdb-about-h').textContent, note: document.querySelector('#sdbEditAbout .sdb-note-t').textContent }));
  check('12. German: "Aus „Agent fragen“" over the line, which stays as the AI wrote it', de.head === 'Aus „Agent fragen“' && de.note === 'A switch an app keeps for itself; changing it does nothing useful.', JSON.stringify(de));
  await ev(() => sdbEditClose());
  await ev(() => I18N.set('en')); await sleep(600);
  check('   no page errors', errors.length === 0, errors.join(' | '));
  const kept = await ev(() => window.AndroidBridge.__kv.sdb_notes);
  await ctx.close();

  // a new start of the app: the lines are still there
  {
    const ctx2 = await b.newContext({ viewport: { width: 400, height: 860 } });
    const p2 = await ctx2.newPage();
    const errs = []; p2.on('pageerror', e => errs.push(e.message));
    await p2.addInitScript(k => { window.__kvInit = { sdb_notes: k }; }, kept);
    await p2.addInitScript(sdb.initScript);
    await p2.goto(PAGE); await p2.waitForTimeout(700);
    await p2.evaluate(() => switchView('settings')); await p2.waitForTimeout(900);
    const back = await p2.evaluate(u => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = u; sdbSearchInput(); return new Promise(res => setTimeout(() => {
      const r = document.querySelector('#sdbList .sdb-row[data-k="' + u + '"]'); res(r ? (r.querySelector('.sdb-note-t') || {}).textContent : null); }, 300)); }, und);
    check('13. after a new start the row still shows the kept line', back === und + ' is not a standard Android setting.', JSON.stringify(back));
    check('   no page errors', errs.length === 0, errs.join(' | '));
    await ctx2.close();
  }

  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
