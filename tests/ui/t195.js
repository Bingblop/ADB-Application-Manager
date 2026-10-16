// Console tab (v7.12.12): the ADB Console pane and the Logcat Viewer tab in one tab, in the look of the Kotlin console; since v7.14.0 the Terminal is
// Console's second segment and Live Logcat is its own tab again. This script checks:
//   - the two-tab row (ADB Shell | Terminal): the segment in use is marked and kept, the underline moves, the page opens on the last one used,
//   - Live Logcat reads only while its tab is open (starts when it is shown, stops on leaving the tab, starts again on coming back),
//   - the ADB Shell history: one card per command, newest first, `$ command`, Running... then Finished / Looks like a failure, never an invented exit number,
//     the result line, Copy, Run again, fold of long output, the cap on the number of cards, Clear, the Run button states (idle, spinner, STOP),
//   - Rish mode cards: the exit number only where the shell gave one, Stopped,
//   - the look: theme tokens only, no sideways scroll and no wrapped outcome text at 360 and 412 px, dark and light, reduced motion.
const { chromium, PAGE, REPO } = require('./lib/pw');
const fs = require('fs'), path = require('path');
const mock = require('./lib/console_mock');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const open = async (opts, size, extraCtx) => {
    const page = await b.newPage(Object.assign({ viewport: size || { width: 400, height: 860 } }, extraCtx || {}));
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, opts || {});
    await page.goto(PAGE); await page.waitForTimeout(350);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const run = async (page, cmd) => { await page.fill('#termCmd', cmd); await page.press('#termCmd', 'Enter'); await sleep(40); };
  const cards = page => ev(page, () => [...document.querySelectorAll('#termOutput > .cn-card')].map(c => ({ cmd: c.querySelector('.cn-cmd').textContent, badge: c.querySelector('.cn-badge').textContent, cls: c.className, out: c.querySelector('.cn-out').textContent, line: (c.querySelector('.term-result') || {}).textContent || '', acts: [...c.querySelectorAll('.cn-acts button')].map(x => x.textContent.trim()) })));

  const src = fs.readFileSync(path.join(REPO, 'assets', 'index.html'), 'utf8');
  check('the history as text (Copy, Share, Find) has one declaration: termText', (src.match(/function termText\(/g) || []).length === 1 && (src.match(/function copyTerminal\(/g) || []).length === 1);
  // ---- 1) the tab and its two segments (v7.14.0: ADB Shell | Terminal; Live Logcat is its own tab again, right after Console) ----
  let page = await open();
  const bar = await ev(page, () => [...document.querySelectorAll('.tab-btn')].map(x => x.dataset.tab + ':' + x.innerText.replace('\n', ' ')));
  check('the tab bar has Console and Live Logcat side by side and no Terminal tab', bar.indexOf('console:Console') >= 0 && bar.indexOf('logcat:Live Logcat') === bar.indexOf('console:Console') + 1 && !bar.some(x => /^terminal:/.test(x)), JSON.stringify(bar));
  await ev(page, () => switchView('console')); await sleep(300);
  let s = await ev(page, () => ({
    shell: !document.getElementById('cnSegShell').hidden, term: !document.getElementById('cnSegTerm').hidden, seg: cnSeg,
    tabs: [...document.querySelectorAll('#cnTabs .cn-tab')].map(t => t.textContent + ':' + t.getAttribute('aria-selected')), role: document.getElementById('cnTabs').getAttribute('role'),
    timer: !!logcatTimer, ph: document.getElementById('termCmd').placeholder }));
  check('Console opens on ADB Shell: the shell part shows, the Terminal part is hidden, the tab is marked selected', s.shell && !s.term && s.seg === 'shell' && s.tabs.join() === 'ADB Shell:true,Terminal:false' && s.role === 'tablist', JSON.stringify(s));
  check('the log is not read in Console (no poll, no bridge call)', !s.timer && (await ev(page, () => window.__calls.logcat.length)) === 0);
  check('the command box says "Enter shell command..."', s.ph === 'Enter shell command...');
  const ind0 = await ev(page, () => { const i = document.querySelector('.cn-ind').getBoundingClientRect(), t = document.getElementById('cnTabs').getBoundingClientRect(), f = document.getElementById('cnTabShell').getBoundingClientRect(); return { left: Math.round(i.left - t.left), w: Math.round(i.width), fw: Math.round(f.width) }; });
  check('the underline is under the first tab (as wide as the tab; the "?" keeps its own room at the end of the row)', ind0.left <= 1 && Math.abs(ind0.w - ind0.fw) <= 2, JSON.stringify(ind0));

  await page.click('#cnTabTerm'); await sleep(900);
  s = await ev(page, () => ({ shell: !document.getElementById('cnSegShell').hidden, term: !document.getElementById('cnSegTerm').hidden, kv: window.__kv.console_seg, timer: !!logcatTimer,
    tabs: [...document.querySelectorAll('#cnTabs .cn-tab')].map(t => t.getAttribute('aria-selected')).join(),
    ind: Math.round(document.querySelector('.cn-ind').getBoundingClientRect().left - document.getElementById('cnTabs').getBoundingClientRect().left), half: Math.round(document.getElementById('cnTabTerm').getBoundingClientRect().left - document.getElementById('cnTabs').getBoundingClientRect().left) }));
  check('Terminal shows and is remembered; the log is still not read', s.term && !s.shell && s.kv === '"term"' && !s.timer && s.tabs === 'false,true', JSON.stringify(s));
  check('the underline moved under the second tab', Math.abs(s.ind - s.half) <= 3, JSON.stringify({ ind: s.ind, half: s.half }));
  await page.click('#cnTabShell'); await sleep(200);
  check('ADB Shell again: remembered', await ev(page, () => window.__kv.console_seg === '"shell"' && !document.getElementById('cnSegShell').hidden));

  // Live Logcat, its own tab: reads only while it is open
  await ev(page, () => switchView('logcat')); await sleep(900);
  s = await ev(page, () => ({ timer: !!logcatTimer, play: document.getElementById('logcatPlayBtn').innerText, n: window.__calls.logcat.length, ph: document.getElementById('logcatFilter').placeholder, rows: document.querySelectorAll('#logcatOutput .lc-row').length }));
  check('Live Logcat starts reading at once (Stop shown, polling, the lines drawn)', s.timer && s.play === 'Stop' && s.n >= 1 && s.rows === 2, JSON.stringify(s));
  check('the filter box has the Kotlin console\'s hint, with "text" (the filter is plain text, not a regex)', s.ph === 'Filter tag / text...');
  const kinds = await ev(page, () => ['logcatLevel', 'logcatLines', 'logcatAppBtn', 'logcatPlayBtn', 'logcatRecBtn', 'logcatRecListBtn', 'logcatKey', 'logcatOutput', 'logcatScope'].map(i => !!document.getElementById(i) && document.querySelector('#view-logcat #cnSegLog').contains(document.getElementById(i))).every(Boolean)
    && ['Copy', 'Save', 'Share'].every(t => [...document.querySelectorAll('#cnSegLog button')].some(x => x.textContent.trim() === t)));
  check('everything of the old Logcat Viewer is in the tab (level, lines, app picker, Play, Record to file, Recordings, key, Copy, Save, Share)', kinds);
  // stops on leaving the tab, starts again on coming back
  await ev(page, () => switchView('console')); await sleep(200);
  const n1 = await ev(page, () => window.__calls.logcat.length);
  await sleep(1500);
  s = await ev(page, () => ({ timer: !!logcatTimer, play: document.getElementById('logcatPlayBtn').innerText, n: window.__calls.logcat.length }));
  check('leaving it (here for Console) stops the log: no timer, Play is back, and no more reads for a second and a half', !s.timer && s.play === 'Play' && s.n === n1, JSON.stringify(s));
  await ev(page, () => switchView('logcat')); await sleep(500);
  check('coming back reads again', await ev(page, n => !!logcatTimer && window.__calls.logcat.length > n, n1));
  await ev(page, () => switchView('logcat')); await sleep(100);
  check('opening the tab that is already open does not stop the log', await ev(page, () => !!logcatTimer));
  await page.close();

  // the saved part is used at the next start, and an unknown value (or the old "log") means ADB Shell
  page = await open({ kv: { console_seg: '"term"' } });
  await ev(page, () => switchView('console')); await sleep(500);
  check('after a restart Console opens on the part saved last (Terminal)', await ev(page, () => !document.getElementById('cnSegTerm').hidden && cnSeg === 'term' && !logcatTimer));
  await page.close();
  for (const v of ['"bogus"', '"log"']) {
    page = await open({ kv: { console_seg: v } });
    await ev(page, () => switchView('console')); await sleep(300);
    check('a saved value that is not one of the two (' + v + ') means ADB Shell', await ev(page, () => cnSeg === 'shell' && !document.getElementById('cnSegShell').hidden && !logcatTimer));
    await page.close();
  }

  // ---- 2) the ADB Shell history ----
  page = await open();
  await ev(page, () => switchView('console')); await sleep(250);
  check('before any command the page says it is ready, and no card is drawn', await ev(page, () => document.querySelectorAll('#termOutput > .cn-card').length === 0 && getComputedStyle(document.getElementById('termEmpty')).display !== 'none'));
  let rb = await ev(page, () => { const r = document.getElementById('termRunBtn'); return { idle: r.classList.contains('is-idle'), busy: r.classList.contains('busy'), text: r.innerText, stop: r.classList.contains('stop') }; });
  check('Run is dimmed while the box is empty, says RUN, and is not busy', rb.idle && !rb.busy && rb.text === 'RUN' && !rb.stop, JSON.stringify(rb));
  await page.fill('#termCmd', 'pm list packages -3');
  check('typing in the box lights Run up', !(await ev(page, () => document.getElementById('termRunBtn').classList.contains('is-idle'))));
  await page.press('#termCmd', 'Enter'); await sleep(50);
  let c = await cards(page);
  check('a command makes a card at once: $ command, Running... on the right, no output yet', c.length === 1 && c[0].cmd === '$ pm list packages -3' && c[0].badge === 'Running…' && /\bis-run\b/.test(c[0].cls) && c[0].out === '' && c[0].acts.length === 0, JSON.stringify(c[0]));
  rb = await ev(page, () => { const r = document.getElementById('termRunBtn'); return { busy: r.classList.contains('busy'), spin: getComputedStyle(r.querySelector('.cn-spin')).display, icon: getComputedStyle(r.querySelector('svg')).display, text: r.innerText }; });
  check('while it runs Run shows a spinner instead of the play symbol (and still reads RUN for a screen reader)', rb.busy && rb.spin !== 'none' && rb.icon === 'none' && rb.text === 'RUN', JSON.stringify(rb));
  await ev(page, () => window.__shellDone('pm list packages -3', 'package:com.a\npackage:com.b'));
  await sleep(40);
  c = await cards(page);
  check('the answer fills the card: Finished, the output, the plain result line, and Copy / Run again', c[0].badge === 'Finished' && /\bis-ok\b/.test(c[0].cls) && c[0].out === 'package:com.a\npackage:com.b' && c[0].line === 'Finished. No error text in the output.' && c[0].acts.join() === 'Copy,Run again', JSON.stringify(c[0]));
  check('Run is idle again after the answer', await ev(page, () => { const r = document.getElementById('termRunBtn'); return !r.classList.contains('busy') && r.classList.contains('is-idle') && !termShellRun; }));
  await run(page, 'cat /nope'); await ev(page, () => window.__shellDone('cat /nope', 'Error: cat: /nope: No such file or directory'));
  await run(page, 'id'); await ev(page, () => window.__shellDone('id', 'uid=2000(shell)'));
  c = await cards(page);
  check('newest first: the latest command is the top card', c.map(x => x.cmd).join('|') === '$ id|$ cat /nope|$ pm list packages -3', c.map(x => x.cmd).join('|'));
  check('a failure says Looks like a failure, in the failure style, with the reason line', c[1].badge === 'Looks like a failure' && /\bis-fail\b/.test(c[1].cls) && c[1].line === 'Looks like a failure: the output reports an error.', JSON.stringify(c[1]));
  check('no card of a normal command shows an exit number or says success (the page gets no exit code from the shell)', c.every(x => !/\bexit\b|\(\d+\)|success/i.test(x.badge + ' ' + x.line)), JSON.stringify(c.map(x => x.badge)));
  const colours = await ev(page, () => { const col = el => getComputedStyle(el).color; const cs = document.querySelectorAll('#termOutput > .cn-card'); return { head: col(cs[0].querySelector('.cn-cmd')), accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(), okBadge: col(cs[0].querySelector('.cn-badge')), badBadge: col(cs[1].querySelector('.cn-badge')), out: col(cs[0].querySelector('.cn-out')), muted: getComputedStyle(document.documentElement).getPropertyValue('--text-muted').trim(), fw: getComputedStyle(cs[0].querySelector('.cn-cmd')).fontWeight, fs: getComputedStyle(cs[0].querySelector('.cn-cmd')).fontSize, bfs: getComputedStyle(cs[0].querySelector('.cn-badge')).fontSize, ofs: getComputedStyle(cs[0].querySelector('.cn-out')).fontSize, ff: getComputedStyle(cs[0].querySelector('.cn-out')).fontFamily, radius: getComputedStyle(cs[0]).borderRadius, bg: getComputedStyle(cs[0]).backgroundColor, card: getComputedStyle(document.documentElement).getPropertyValue('--bg-card').trim() }; });
  check('the Kotlin look: `$ command` bold 12 px in the accent colour, outcome 12 px (--fs-pkg, it was 10), output 11 px monospace, cards 16 px round', colours.fw === '700' && colours.fs === '12px' && colours.bfs === '12px' && colours.ofs === '11px' && /mono/i.test(colours.ff) && colours.radius === '16px' && colours.okBadge !== colours.badBadge, JSON.stringify(colours));
  // Copy and Run again
  await ev(page, () => document.querySelector('#termOutput > .cn-card:nth-child(3) .cn-acts button').click());
  const copied = await ev(page, () => window.__calls.copied.slice(-1)[0]);
  check('Copy on a card puts its command and output on the clipboard', copied === '$ pm list packages -3\npackage:com.a\npackage:com.b\n', JSON.stringify(copied));
  const before = await ev(page, () => window.__calls.shell.length);
  await ev(page, () => [...document.querySelectorAll('#termOutput > .cn-card:nth-child(3) .cn-acts button')].find(x => /Run again/.test(x.textContent)).click()); await sleep(40);
  c = await cards(page);
  check('Run again sends the same command again and makes a new card at the top', (await ev(page, () => window.__calls.shell.length)) === before + 1 && c.length === 4 && c[0].cmd === '$ pm list packages -3' && c[0].badge === 'Running…', JSON.stringify(c.map(x => x.cmd + ':' + x.badge)));
  await ev(page, () => window.__shellDone('pm list packages -3', 'package:com.a'));
  // a second command while one runs
  await run(page, 'sleep 20'); await run(page, 'ls');
  check('a second command while one runs is refused, and what was typed stays', (await ev(page, () => window.__calls.shell.filter(x => x.cmd === 'ls').length)) === 0 && (await page.inputValue('#termCmd')) === 'ls' && /still running/.test(await page.locator('#toastMsg').innerText()));
  await page.fill('#termCmd', ''); await ev(page, () => window.__shellDone('sleep 20', ''));
  // fold
  const long = Array.from({ length: 40 }, (_, i) => 'line ' + i).join('\n');
  await run(page, 'dumpsys battery'); await ev(page, o => window.__shellDone('dumpsys battery', o), long);
  let f = await ev(page, () => { const k = document.querySelector('#termOutput > .cn-card'); return { cls: k.className, h: Math.round(k.querySelector('.cn-out').getBoundingClientRect().height), acts: [...k.querySelectorAll('.cn-acts button')].map(x => x.textContent.trim()) }; });
  check('a long output is folded to a few lines, with Show all first', /\bis-fold\b/.test(f.cls) && f.acts[0] === 'Show all' && f.h < 220, JSON.stringify(f));
  await ev(page, () => document.querySelector('#termOutput > .cn-card .cn-acts button').click());
  f = await ev(page, () => { const k = document.querySelector('#termOutput > .cn-card'); return { cls: k.className, h: Math.round(k.querySelector('.cn-out').getBoundingClientRect().height), label: k.querySelector('.cn-acts button').textContent.trim() }; });
  check('Show all opens it (taller, now Show less) and Show less folds it again', /\bis-open\b/.test(f.cls) && f.h > 220 && f.label === 'Show less', JSON.stringify(f));
  await ev(page, () => document.querySelector('#termOutput > .cn-card .cn-acts button').click());
  check('   folded again', await ev(page, () => /\bis-fold\b/.test(document.querySelector('#termOutput > .cn-card').className)));
  // text of the whole history
  const all = await ev(page, () => termText());
  check('Copy / Share of the history is the text of every card, oldest first, with the result lines', /^\$ pm list packages -3\npackage:com.a\npackage:com.b\nFinished\. No error text in the output\.\n\$ cat \/nope\n/.test(all) && all.trimEnd().endsWith('line 39\nFinished. No error text in the output.'), JSON.stringify(all.slice(0, 120)));
  // find
  await page.fill('#termSearch', 'uid=2000'); await ev(page, () => termFind(0));
  check('Find in output marks the matches inside the cards', (await page.locator('#termFindCount').innerText()).includes('1 / 1') && (await page.locator('#termOutput mark.find-hit').count()) === 1);
  await page.fill('#termSearch', ''); await ev(page, () => termFind(0));
  // the cap
  await ev(page, () => { for (let i = 0; i < 70; i++) { const k = cnCardNew('sh', '$ c' + i, 'c' + i); cnShFinish(k, 'x'); } });
  check('the history keeps 60 cards, the newest', await ev(page, () => document.querySelectorAll('#termOutput > .cn-card').length === 60 && document.querySelector('#termOutput > .cn-card .cn-cmd').textContent === '$ c69'));
  await page.click('#cnSegShell .find-nav.danger');
  check('the clear button empties the history and brings back the ready text', await ev(page, () => document.querySelectorAll('#termOutput > .cn-card').length === 0 && getComputedStyle(document.getElementById('termEmpty')).display !== 'none') && (await ev(page, () => termText())) === '');
  await page.close();

  // ---- 3) Rish mode: the exit number only where the shell gave one ----
  page = await open();
  await ev(page, () => switchView('console')); await sleep(250);
  await page.locator('#rishBtn').click(); await sleep(400);
  await run(page, 'true'); await sleep(100);
  c = await cards(page);
  check('Rish: a command with exit 0 given shows "exit 0" (the shell said so)', c[0].cmd.endsWith('true') && c[0].badge === 'exit 0' && /\bis-ok\b/.test(c[0].cls) && c[0].out === 'ran:true\n' && c[0].line === '', JSON.stringify(c[0]));
  await run(page, 'false'); await sleep(100);
  c = await cards(page);
  check('Rish: a failing command shows "exit 1" in the failure style', c[0].badge === 'exit 1' && /\bis-fail\b/.test(c[0].cls) && /\[exit 1\]/.test(c[0].out), JSON.stringify(c[0]));
  await ev(page, () => { window.__rishExit = undefined; });
  await run(page, 'echo none'); await sleep(100);
  c = await cards(page);
  check('Rish: with no exit code given the card says only Finished (no number is made up)', c[0].badge === 'Finished' && !/exit/i.test(c[0].badge), JSON.stringify(c[0].badge));
  await run(page, 'forever'); await sleep(60);
  rb = await ev(page, () => { const r = document.getElementById('termRunBtn'); return { stop: r.classList.contains('stop'), text: r.innerText, href: r.querySelector('svg use').getAttribute('href'), busy: r.classList.contains('busy') }; });
  check('Rish: while a command runs, Run turns into a red STOP with the stop symbol', rb.stop && rb.text === 'STOP' && rb.href === '#cn-i-stop' && !rb.busy, JSON.stringify(rb));
  const nm = await ev(page, () => { const r = document.getElementById('termRunBtn'); return [r.getAttribute('aria-label'), r.title]; });
  check('Rish: the name of the button (for a screen reader, and its tool tip) says Stop with the symbol', nm.join() === 'Stop,Stop', JSON.stringify(nm));
  // output of an earlier command arriving while this one runs is not this card's output; between commands anything is accepted
  const ids = await ev(page, () => { const cur = rishCurrentRun; const c0 = document.querySelector('#termOutput > .cn-card'); const before = c0.querySelector('.cn-out').textContent; onRishOutput('rEARLIER', 'LATE-FROM-AN-EARLIER-COMMAND\n'); const mid = c0.querySelector('.cn-out').textContent; onRishOutput(cur, 'RIGHT-RUN\n'); return { cur, before, ignored: mid === before, taken: c0.querySelector('.cn-out').textContent === before + 'RIGHT-RUN\n', anywhere: document.body.innerText.includes('LATE-FROM') }; });
  check('Rish: while a command runs, output with another run id is dropped and the running command\'s own output is taken', /^r\d+$/.test(ids.cur) && ids.ignored && ids.taken && !ids.anywhere, JSON.stringify(ids));
  await page.locator('#termRunBtn').click(); await sleep(150);
  c = await cards(page);
  const nm2 = await ev(page, () => { const r = document.getElementById('termRunBtn'); return [r.getAttribute('aria-label'), r.title]; });
  check('Rish: after STOP the name of the button is Run again', nm2.join() === 'Run,Run', JSON.stringify(nm2));
  await ev(page, () => { onRishOutput('rBACKGROUND', 'background output between commands\n'); });
  check('Rish: between commands output is accepted whatever its run id (it goes to a card, none is lost)', (await ev(page, () => termText())).includes('background output between commands'));
  const de = await ev(page, async () => { await LANG_ENGINE.set('de'); termSyncRunBtn(); const r = document.getElementById('termRunBtn'); const out = [r.getAttribute('aria-label'), txT('Stop')]; await LANG_ENGINE.set('en'); termSyncRunBtn(); return out; });
  check('the name of the button is translated (German Run label differs from the English one)', de[0] !== 'Run' && de[0].length > 0, JSON.stringify(de));
  check('Rish: STOP ends it and the card says Stopped', c[0].badge === 'Stopped' && /\bis-stop\b/.test(c[0].cls) && (await ev(page, () => window.__calls.rishStop)) === 1, JSON.stringify(c[0]));
  check('Rish: the card has Copy and Run again, Run is back to RUN', c[0].acts.join() === 'Copy,Run again' && (await ev(page, () => document.getElementById('termRunBtn').innerText)) === 'RUN');
  const nr = await ev(page, () => window.__calls.rish.length);
  await ev(page, () => document.querySelector('#termOutput > .cn-card .cn-acts button:last-child').click()); await sleep(60);
  check('Rish: Run again sends the command to the Rish shell again', (await ev(page, () => window.__calls.rish.length)) === nr + 1 && (await ev(page, () => window.__calls.rish.slice(-1)[0])) === 'forever');
  await page.close();

  // ---- 4) the look: tokens, no sideways scroll, no wrapped outcome, in dark and light and with reduced motion ----
  for (const [w, scheme] of [[360, 'dark'], [360, 'light'], [412, 'dark'], [412, 'light']]) {
    const p = await open({}, { width: w, height: 800 }, { colorScheme: scheme });
    await ev(p, m => { try { setAppearance(m); } catch (e) {} }, scheme);
    await ev(p, () => switchView('console')); await sleep(250);
    await run(p, 'pm list packages -3 | grep google'); await ev(p, () => window.__shellDone('pm list packages -3 | grep google', 'package:com.google.android.apps.maps\npackage:com.google.android.youtube'));
    await run(p, 'pm install /sdcard/Download/some-very-long-file-name-for-an-application-package.apk'); await ev(p, () => window.__shellDone('pm install /sdcard/Download/some-very-long-file-name-for-an-application-package.apk', 'Failure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install com.example.app without first uninstalling.]'));
    await run(p, 'sleep 30');
    const g = await ev(p, () => {
      const vw = innerWidth, cs = [...document.querySelectorAll('#termOutput > .cn-card')];
      const lum = c => { const k = c.startsWith('color(') ? 255 : 1; const [r, g, b] = c.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => { v = v * k / 255;          // color-mix() computes to color(srgb 0..1 0..1 0..1)
         return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
      const cr = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
      const bg = getComputedStyle(cs[0]).backgroundColor;
      return {
        side: document.documentElement.scrollWidth <= vw,
        inside: [...document.querySelectorAll('#cnSegShell .cn-row, #cnSegShell .cn-card, #cnSegShell .cn-mcard, #cnSegShell .cn-find, #cnTabs')].every(e => e.getBoundingClientRect().right <= vw + 0.5 && e.getBoundingClientRect().left >= -0.5),
        badgeOneLine: cs.every(c => { const b = c.querySelector('.cn-badge'); return b.getBoundingClientRect().height <= parseFloat(getComputedStyle(b).lineHeight) + 2 && getComputedStyle(b).whiteSpace === 'nowrap'; }),
        badgeIn: cs.every(c => c.querySelector('.cn-badge').getBoundingClientRect().right <= c.getBoundingClientRect().right),
        contrast: cs.map(c => +cr(getComputedStyle(c.querySelector('.cn-badge')).color, bg).toFixed(1)),
        cmdContrast: +cr(getComputedStyle(cs[0].querySelector('.cn-cmd')).color, bg).toFixed(1),
        outContrast: +cr(getComputedStyle(cs[1].querySelector('.cn-out')).color, bg).toFixed(1),
        fieldH: Math.round(document.getElementById('termCmd').getBoundingClientRect().height), runW: Math.round(document.getElementById('termRunBtn').getBoundingClientRect().width),
        sticky: getComputedStyle(document.querySelector('#cnSegShell .cn-sticky')).position,
        appearance: document.documentElement.getAttribute('data-appearance'),
      };
    });
    check('[' + w + ' px ' + scheme + '] nothing scrolls sideways, every block is inside the screen, the outcome text is one line inside its card', g.side && g.inside && g.badgeOneLine && g.badgeIn, JSON.stringify(g));
    check('[' + w + ' px ' + scheme + '] the command box is 56 px tall, Run 58 px wide, the box row sticks while scrolling', g.fieldH === 56 && g.runW === 58 && g.sticky === 'sticky', JSON.stringify({ f: g.fieldH, r: g.runW, s: g.sticky }));
    check('[' + w + ' px ' + scheme + '] the outcome text, the command and the output read at 3:1 or better on the card (' + g.contrast.join('/') + ', ' + g.cmdContrast + ', ' + g.outContrast + ')', g.contrast.every(x => x >= 3) && g.cmdContrast >= 3 && g.outContrast >= 3);
    await p.close();
  }
  for (const preset of ['amoled', 'cyberpunk', 'emerald', 'amber', 'slate', 'crimson', 'material3']) {
    const p = await open({}, { width: 360, height: 800 });
    await ev(p, pr => { applyPalettePreset(pr); }, preset);
    await ev(p, () => switchView('console')); await sleep(150);
    await run(p, 'x'); await ev(p, () => window.__shellDone('x', 'Error: boom')); await run(p, 'y'); await ev(p, () => window.__shellDone('y', 'ok'));
    const t = await ev(p, () => { const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim(); const cs = [...document.querySelectorAll('#termOutput > .cn-card')]; return { accent: css('--accent'), cmd: getComputedStyle(cs[0].querySelector('.cn-cmd')).color, side: document.documentElement.scrollWidth <= innerWidth, ok: getComputedStyle(cs[0].querySelector('.cn-badge')).color, bad: getComputedStyle(cs[1].querySelector('.cn-badge')).color }; });
    check('palette ' + preset + ': the card text follows the theme (no sideways scroll, outcome colours differ)', t.side && t.ok !== t.bad, JSON.stringify(t));
    await p.close();
  }
  // reduced motion: the underline and the spinner do not animate; the moving underline uses a transition otherwise
  let p = await open({}, { width: 400, height: 800 }, { reducedMotion: 'reduce' });
  await ev(p, () => switchView('console')); await sleep(150);
  const rm = await ev(p, () => ({ ind: getComputedStyle(document.querySelector('.cn-ind')).transitionDuration, spin: getComputedStyle(document.querySelector('.cn-spin')).animationDuration }));
  check('reduced motion: the underline has no transition and the spinner is slowed right down', rm.ind === '0s' && parseFloat(rm.spin) >= 3, JSON.stringify(rm));
  await p.close();
  p = await open({}, { width: 400, height: 800 }, { reducedMotion: 'no-preference' });
  await ev(p, () => { document.documentElement.classList.add('expressive-anim'); switchView('console'); }); await sleep(150);
  const em = await ev(p, () => ({ ind: getComputedStyle(document.querySelector('.cn-ind')).transitionDuration, ease: getComputedStyle(document.querySelector('.cn-ind')).transitionTimingFunction }));
  check('motion allowed: the underline slides (0.25 s) and, with Expressive Animations on, with the springy curve', em.ind === '0.25s' && /cubic-bezier\(0\.175, 0\.885, 0\.32, 1\.275\)/.test(em.ease), JSON.stringify(em));
  await p.close();

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
