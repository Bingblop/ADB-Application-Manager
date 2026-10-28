// v7.16.2, Live Logcat (the owner's report: the rows did not show the key's colours, and a busy log stuttered). One colour per level: the key's dot,
// the level letter, the tag, the bar on the leading edge, the message (Verbose a little quieter) and the row tint of a row all take the colour of its
// level (Verbose green, Debug yellow, Info the accent, Warn orange, Error red, Fatal purple), in the dark, light and pure black looks and with a Monet
// accent, and every text keeps 4.5:1 on the untinted row. The live view draws only what a poll adds: the rows already there are the same nodes
// afterwards, a poll's new lines are appended (and the oldest rows go when the window of 800 is full, the "earlier entries" line counting them), the
// rest of a stack trace grows the newest row instead of starting a second one, the key's counts follow, a tap on a chip still hides a level, and the
// box follows the newest line only while the reader is at the bottom. Rows far above the screen are not laid out (content-visibility). At 360 px the
// box does not scroll sideways and a long tag wraps.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const line = (i, lvl, tag, msg) => '10-12 10:' + String(Math.floor(i / 60) % 60).padStart(2, '0') + ':' + String(i % 60).padStart(2, '0') + '.' + String(i % 1000).padStart(3, '0') + '  1234  ' + (1000 + (i % 7)) + ' ' + lvl + ' ' + tag + ': ' + msg;
const SIX = ['V', 'D', 'I', 'W', 'E', 'F'].map((l, i) => line(i, l, 'Tag' + l, 'message ' + l)).join('\n') + '\n';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1' }, log: SIX });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });

  // ---- 1. one colour per level, in every look ----
  const colours = () => ev(() => {
    const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
    const ratio = (a, c) => { const x = lum(a), y = lum(c); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const rgb = s => { const n = (s.match(/[\d.]+/g) || []).slice(0, 4).map(Number); return /^color\(srgb/.test(s) ? n.slice(0, 3).map(x => x * 255) : n.slice(0, 3); };
    const probe = v => { const e = document.createElement('i'); e.style.color = v; document.body.appendChild(e); const c = getComputedStyle(e).color; e.remove(); return rgb(c); };
    const want = { V: probe('#2EBD6B'), D: probe('#FFD600'), I: probe('var(--accent)'), W: probe('#FF6D00'), E: probe('#FF5252'), F: probe('#E040FB') };
    // how far a colour sits from the level's colour along the line to the text colour: 0 = the level colour itself, 1 = the text colour
    const text = rgb(getComputedStyle(document.body).color);
    const toward = (c, lvl) => { const w = want[lvl]; let num = 0, den = 0; for (let i = 0; i < 3; i++) { num += (c[i] - w[i]) * (text[i] - w[i]); den += (text[i] - w[i]) * (text[i] - w[i]); } return den ? num / den : 0; };
    const out = {};
    for (const l of Object.keys(want)) {
      const row = document.querySelector('#logcatOutput .lc-row.lvl-' + l); if (!row) { out[l] = 'no row'; continue; }
      const s = getComputedStyle(row), be = getComputedStyle(row, '::before');
      const dot = rgb(getComputedStyle(document.querySelector('#logcatKey .lvl-' + l + ' .lc-dot')).backgroundColor);
      const bar = rgb(document.dir === 'rtl' ? s.borderRightColor : s.borderLeftColor);
      // the tint: one even colour (Row tints > Gradient off, the default since v7.18.1), or the first colour of the gradient with Gradient on
      const tintFirst = rgb(be.backgroundImage === 'none' ? be.backgroundColor : (be.backgroundImage.match(/(rgb|color)\([^)]*\)/) || [''])[0]);
      const bg = rgb(s.backgroundColor);
      const ink = { badge: rgb(getComputedStyle(row.querySelector('.lc-badge')).color), tag: rgb(getComputedStyle(row.querySelector('.lc-tag')).color), msg: rgb(getComputedStyle(row.querySelector('.lc-msg')).color) };
      const near = c => c.length === 3 && c.every((x, i) => Math.abs(x - want[l][i]) < 2);
      out[l] = {
        dot: near(dot), bar: near(bar), tint: near(tintFirst), barW: parseFloat(document.dir === 'rtl' ? s.borderRightWidth : s.borderLeftWidth),
        badgeToward: +toward(ink.badge, l).toFixed(2), tagToward: +toward(ink.tag, l).toFixed(2), msgToward: +toward(ink.msg, l).toFixed(2),
        minContrast: +Math.min(ratio(ink.badge, bg), ratio(ink.tag, bg), ratio(ink.msg, bg)).toFixed(2),
        timeMuted: getComputedStyle(row.querySelector('.lc-time')).color === getComputedStyle(row.querySelector('.lc-pid')).color
      };
    }
    return out;
  });
  const looks = [
    ['dark', () => { themeState.appearance = 'dark'; themeState.pureBlack = false; applyTheme(false); }],
    ['pure black', () => { themeState.appearance = 'dark'; themeState.pureBlack = true; applyTheme(false); }],
    ['light', () => { themeState.appearance = 'light'; themeState.pureBlack = false; applyTheme(false); }],
    ['dark, a green Monet accent', () => { themeState.appearance = 'dark'; themeState.pureBlack = false; applyTheme(false); document.documentElement.style.setProperty('--accent', '#7BD88F'); }],
  ];
  await ev(() => { switchView('logcat'); setRowTint(0); }); await page.waitForTimeout(600);
  for (const [name, setup] of looks) {
    await ev(setup); await page.waitForTimeout(150);
    const c = await colours();
    const rows = Object.values(c);
    check(name + ': every level has a row, and the key\'s dot, the bar and the tint of each row are the level\'s colour', rows.length === 6 && rows.every(r => r.dot && r.bar && r.tint && r.barW >= 2), JSON.stringify(c));
    check(name + ': the level letter, the tag and the message lean toward the level\'s colour (nearer to it than to the text colour; a light page inks them darker), the time and pid stay muted',
      rows.every(r => r.badgeToward < 0.6 && r.tagToward < 0.6 && r.msgToward < 0.72 && r.timeMuted), JSON.stringify(Object.fromEntries(Object.entries(c).map(([k, r]) => [k, [r.badgeToward, r.tagToward, r.msgToward]]))));
    check(name + ': Verbose\'s message is quieter than the other levels\' (further from its colour)', c.V.msgToward > Math.max(c.D.msgToward, c.I.msgToward, c.W.msgToward, c.E.msgToward, c.F.msgToward), JSON.stringify(Object.fromEntries(Object.entries(c).map(([k, r]) => [k, r.msgToward]))));
    check(name + ': every coloured text reads at 4.5:1 or more on its row', rows.every(r => r.minContrast >= 4.5), JSON.stringify(Object.fromEntries(Object.entries(c).map(([k, r]) => [k, r.minContrast]))));
  }
  await ev(() => { document.documentElement.style.removeProperty('--accent'); themeState.pureBlack = false; applyTheme(false); setRowTint(2); });
  check('the Live Logcat rows: the Kotlin look is kept (no border but the bar, the card colour, a 4 px radius)', await ev(() => { const s = getComputedStyle(document.querySelector('#logcatOutput .lc-row')); return s.borderTopWidth === '0px' && s.borderBottomWidth === '0px' && s.borderRadius === '4px'; }));

  // ---- 2. the live view adds, it does not redraw ----
  const rowsN = () => ev(() => document.querySelectorAll('#logcatOutput .lc-row').length);
  const poll = text => ev(t => logcatRender(t, false, true, lcSigNow()), text);
  await ev(() => { logcatStop(); document.getElementById('logcatLevel').value = 'V'; logcatClear(); });
  const big = []; for (let i = 0; i < 1000; i++) big.push(line(i, 'IWE'[i % 3], 'Tag' + i, 'line number ' + i));
  await poll(big.join('\n')); await page.waitForTimeout(100);
  check('2. 1000 lines: the newest 800 are drawn, the line above them counts the 200 earlier ones', await rowsN() === 800 && await ev(() => /^200 earlier entries not shown/.test((document.getElementById('lcEarlier') || {}).textContent || '')), String(await rowsN()));
  await ev(() => { window.__first = document.querySelector('#logcatOutput .lc-row'); window.__last = document.querySelector('#logcatOutput .lc-row:last-child'); window.__firstTag = window.__first.querySelector('.lc-tag').textContent; });
  const more = []; for (let i = 1000; i < 1003; i++) more.push(line(i, 'W', 'New' + i, 'fresh line ' + i));
  await poll(big.slice(-20).concat(more).join('\n')); await page.waitForTimeout(100);
  const after = await ev(() => {
    const rows = [...document.querySelectorAll('#logcatOutput .lc-row')];
    return { n: rows.length, lastKept: rows.includes(window.__last), firstGone: !rows.includes(window.__first), firstTag: rows[0].querySelector('.lc-tag').textContent, oldFirstTag: window.__firstTag,
      tail: rows.slice(-3).map(r => r.querySelector('.lc-tag').textContent).join(), earlier: (document.getElementById('lcEarlier') || {}).textContent || '', entries: logcatEntries.length, counts: JSON.stringify(logcatCounts),
      keyW: document.querySelector('#logcatKey .lvl-W .lc-n').textContent, order: rows.every((r, i) => i === 0 || +rows[i - 1].dataset.i < +r.dataset.i) };
  });
  check('   a poll with 3 new lines (and 20 already seen) appends 3 rows: the rows that were there are the same nodes, the 3 oldest left the window', after.n === 800 && after.lastKept && after.firstGone && after.tail === 'New1000,New1001,New1002' && after.order, JSON.stringify(after));
  check('   the line above counts 203 earlier entries, the Warn chip counts the 3 new lines', /^203 earlier/.test(after.earlier) && after.entries === 1003 && after.keyW === String(333 + 3), after.earlier + ' | ' + after.keyW);
  // the rest of a stack trace: the same header as the newest row continues it
  await poll([line(1002, 'W', 'New1002', 'fresh line 1002'), line(1002, 'W', 'New1002', '\tat com.x.Y.f(Y.java:1)'), line(1002, 'W', 'New1002', '\tat com.x.Y.g(Y.java:2)')].join('\n')); await page.waitForTimeout(100);
  const grown = await ev(() => { const rows = [...document.querySelectorAll('#logcatOutput .lc-row')]; const last = rows[rows.length - 1]; return { n: rows.length, lines: last.querySelector('.lc-msg').textContent.split('\n').length, tag: last.querySelector('.lc-tag').textContent, keyW: document.querySelector('#logcatKey .lvl-W .lc-n').textContent, entries: logcatEntries.length }; });
  check('   two more lines of the newest entry grow its row (3 lines, still one row), the count follows', grown.n === 800 && grown.lines === 3 && grown.tag === 'New1002' && grown.keyW === String(338) && grown.entries === 1003, JSON.stringify(grown));
  // hiding a level still works on the appended view, and the next poll respects it
  await ev(() => logcatToggleLevel('W')); await page.waitForTimeout(100);
  await poll([line(1003, 'W', 'HiddenW', 'not shown'), line(1004, 'E', 'ShownE', 'shown')].join('\n')); await page.waitForTimeout(100);
  const hid = await ev(() => { const rows = [...document.querySelectorAll('#logcatOutput .lc-row')]; return { anyW: rows.some(r => r.classList.contains('lvl-W')), lastTag: rows[rows.length - 1].querySelector('.lc-tag').textContent, hasHidden: !!rows.find(r => r.querySelector('.lc-tag').textContent === 'HiddenW') }; });
  check('   Warn hidden through the key: no Warn row is drawn, a new Warn line stays hidden, a new Error line is appended', !hid.anyW && hid.lastTag === 'ShownE' && !hid.hasHidden, JSON.stringify(hid));
  await ev(() => logcatToggleLevel('W')); await page.waitForTimeout(100);
  check('   showing Warn again brings the rows back (800 drawn, Warn among them)', await rowsN() === 800 && await ev(() => document.querySelectorAll('#logcatOutput .lc-row.lvl-W').length > 0));
  // the box follows the newest line while at the bottom, and stays put when the reader has scrolled up
  await ev(() => { const el = document.getElementById('logcatOutput'); el.scrollTop = el.scrollHeight; }); await page.waitForTimeout(150);
  await poll(line(1005, 'I', 'Follow', 'at the bottom')); await page.waitForTimeout(250);
  const followed = await ev(() => { const el = document.getElementById('logcatOutput'), last = el.lastElementChild; return { atBottom: last.getBoundingClientRect().bottom - el.getBoundingClientRect().bottom < 48, visible: /at the bottom/.test(el.innerText) }; });
  check('   at the bottom, a new line is scrolled into view', followed.atBottom && followed.visible, JSON.stringify(followed));
  await ev(() => { document.getElementById('logcatOutput').scrollTop = 0; }); await page.waitForTimeout(150);
  await poll(line(1006, 'I', 'Stay', 'while reading above')); await page.waitForTimeout(250);
  const stayed = await ev(() => { const el = document.getElementById('logcatOutput'); return { top: el.scrollTop, there: /while reading above/.test(el.textContent) }; });
  check('   scrolled to the top, a new line is added without moving the reader', stayed.top < 48 && stayed.there, JSON.stringify(stayed));
  check('   rows far below the screen are not laid out (content-visibility: auto), the ones on screen are', await ev(() => { const rows = [...document.querySelectorAll('#logcatOutput .lc-row')]; return getComputedStyle(rows[0]).contentVisibility === 'auto' && rows[0].innerText.length > 0 && rows[rows.length - 1].innerText === ''; }));
  // the cap: the oldest entries go, the counts go with them
  const flood = []; for (let i = 2000; i < 4600; i++) flood.push(line(i, 'D', 'Flood', 'd ' + i));
  await poll(flood.join('\n')); await page.waitForTimeout(150);
  const capped = await ev(() => ({ entries: logcatEntries.length, counted: Object.values(logcatCounts).reduce((a, b) => a + b, 0), rows: document.querySelectorAll('#logcatOutput .lc-row').length, earlier: (document.getElementById('lcEarlier') || {}).textContent || '', firstId: +document.querySelector('#logcatOutput .lc-row').dataset.i, firstKept: logcatEntries[0].id }));
  check('3. 2600 more lines: 2500 entries are kept (the oldest went, with their counts), 800 drawn, 1700 counted above', capped.entries === 2500 && capped.counted === 2500 && capped.rows === 800 && /^1700 earlier/.test(capped.earlier) && capped.firstId >= capped.firstKept, JSON.stringify(capped));
  await ev(() => document.getElementById('lcEarlier').click()); await page.waitForTimeout(400);
  check('   the tap on the line draws them all', await rowsN() === 2500);
  await poll(line(5000, 'E', 'AfterAll', 'one more')); await page.waitForTimeout(150);
  check('   and a poll then keeps all of them on the screen (2500 rows, the newest at the end)', await rowsN() === 2500 && await ev(() => document.querySelector('#logcatOutput .lc-row:last-child .lc-tag').textContent === 'AfterAll'));

  // ---- 4. a phone's width ----
  await ev(() => { logcatClear(); });
  await poll([line(1, 'E', 'A'.repeat(70), 'a tag too long for the width'), line(2, 'I', 'Short', 'x'.repeat(120))].join('\n')); await page.waitForTimeout(150);
  const fit = await ev(() => { const el = document.getElementById('logcatOutput'); const rows = [...el.querySelectorAll('.lc-row')]; return { boxWide: el.scrollWidth > el.clientWidth + 1, rowsWide: rows.some(r => r.scrollWidth > r.clientWidth + 1), tagLines: Math.round(rows[0].querySelector('.lc-tag').getBoundingClientRect().height / 15), msgLines: Math.round(rows[1].querySelector('.lc-msg').getBoundingClientRect().height / 16), pageWide: document.documentElement.scrollWidth > window.innerWidth }; });
  check('4. at 360 px nothing scrolls sideways; a 70-letter tag and a 120-letter message wrap', !fit.boxWide && !fit.rowsWide && !fit.pageWide && fit.tagLines >= 2 && fit.msgLines >= 2, JSON.stringify(fit));

  await ev(() => logcatStop());
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
