// File Manager: a round Refresh button in the title row, before "⋯ More" - the same button as the Apps tab's search bar (40px circle, accent, spins
// while it works), for the folder that is open (or the search results): it reads the folder again, once at a time, for at least a moment so a quick
// answer still shows that something happened, and the "Refresh" row of the ⋯ More sheet is still there. It takes no room: the row keeps its height, so
// the ".." row does not move; at 320, 360 and 412px in English, German, Spanish and Portuguese (Brazil) the title is not cut, nothing overlaps and the
// page has no sideways scroll; a failing read does not leave the button off.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = '/storage/emulated/0';
const FILES = {
  [ROOT + '/Download/report.pdf']: { size: 5000, bin: true }, [ROOT + '/Download/notes.txt']: 'notes', [ROOT + '/DCIM']: null, [ROOT + '/Music']: null,
  [ROOT + '/a.txt']: 'A',
};

(async () => {
  const b = await chromium.launch();
  const open = async (width, height, opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width, height }, colorScheme: 'dark' });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.on('dialog', d => d.accept().catch(() => {}));
    await page.addInitScript(fm.initScript, { files: FILES, priv: true, kv: { perm_intro_v61: '1' } });
    await page.addInitScript(() => { window.AndroidBridge.vibrate = () => {}; });
    await page.goto(PAGE);
    await sleep(400);
    if (opts.lang) { await page.evaluate(l => I18N.set(l), opts.lang); await sleep(400); }
    await page.evaluate(() => switchView('files'));
    await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 2);
    await page.evaluate(() => { const t = document.getElementById('toast'); if (t) t.classList.remove('show'); window.scrollTo(0, 0); });
    await sleep(300);
    return page;
  };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const listed = (page, path) => ev(page, p => window.__fm.calls.filter(c => c === 'fmList:' + p).length, path);
  const names = page => ev(page, () => [...document.querySelectorAll('#fmList .perm-name')].map(n => n.innerText));
  const state = page => ev(page, () => {
    const e = document.getElementById('fmRefreshIconBtn'), svg = e.querySelector('svg');
    return { busy: e.classList.contains('busy'), off: e.disabled, aria: e.getAttribute('aria-busy'), spin: getComputedStyle(svg).animationName };
  });

  // ---------------------------------------------------------------- 1) the button: where it is, what it is called, how it looks
  let page = await open(360, 800);
  const bt = await ev(page, () => {
    const e = document.getElementById('fmRefreshIconBtn'), head = e.closest('.fm-head'), more = document.getElementById('fmMoreBtn'), title = head && head.querySelector('.color-card-title');
    const probe = document.createElement('span'); probe.style.color = 'var(--accent)'; document.body.appendChild(probe); const accent = getComputedStyle(probe).color; probe.remove();
    const r = e.getBoundingClientRect(), t = title.getBoundingClientRect(), m = more.getBoundingClientRect();
    return {
      tag: e.tagName, type: e.getAttribute('type'), cls: e.className, label: e.getAttribute('aria-label'), title: e.title, svgHidden: e.querySelector('svg').getAttribute('aria-hidden'),
      inHead: !!head, order: [...head.children].map(c => c.id || c.className.split(' ')[0]).join('|'), w: Math.round(r.width), h: Math.round(r.height), round: getComputedStyle(e).borderRadius,
      color: getComputedStyle(e).color, accent, ordered: t.right <= r.left + 1 && r.right <= m.left + 1, inFiles: !!e.closest('#view-files'), others: document.querySelectorAll('#fmRefreshIconBtn').length,
    };
  });
  check('1. a labelled button in the title row, between the title and "⋯ More", named Refresh for a screen reader and on long press', bt.tag === 'BUTTON' && bt.type === 'button' && bt.inHead && bt.label === 'Refresh' && bt.title === 'Refresh' && bt.svgHidden === 'true' && bt.ordered && bt.others === 1 && bt.inFiles, JSON.stringify(bt));
  check('   it is the Apps tab\'s round Refresh button (same class), a 40px circle in the accent colour', /\bsearch-refresh-btn\b/.test(bt.cls) && bt.w === 40 && bt.h === 40 && bt.round === '50%' && bt.color === bt.accent, JSON.stringify(bt));
  check('   the title row is title, Refresh, ⋯ More', bt.order === 'color-card-title|fmRefreshIconBtn|fmMoreBtn', bt.order);
  check('   at rest it is on, not spinning', JSON.stringify(await state(page)) === JSON.stringify({ busy: false, off: false, aria: null, spin: 'none' }), JSON.stringify(await state(page)));
  check('   the Apps tab\'s own Refresh button is still there', await ev(page, () => !!document.getElementById('appsRefreshBtn') && !document.getElementById('appsRefreshBtn').closest('#view-files')));

  // ---------------------------------------------------------------- 2) a tap reads the folder again, spins for a moment and is ready again
  const c0 = await listed(page, ROOT);
  await ev(page, p => window.__fm.put(p + '/new-from-elsewhere.txt', 'x'), ROOT);
  check('2. a file made elsewhere is not in the list yet', !(await names(page)).includes('new-from-elsewhere.txt'));
  await page.click('#fmRefreshIconBtn'); await sleep(150);
  const mid = await state(page);
  check('   one tap: the folder is read again once, the new file is listed at once', (await listed(page, ROOT)) === c0 + 1 && (await names(page)).includes('new-from-elsewhere.txt'), (await listed(page, ROOT)) + ' vs ' + c0);
  check('   meanwhile the button is off, busy and spinning (aria-busy tells a screen reader)', mid.busy && mid.off && mid.aria === 'true' && mid.spin === 'appsRefreshSpin', JSON.stringify(mid));
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 }); await sleep(50);
  check('   a moment later it is on again and still', JSON.stringify(await state(page)) === JSON.stringify({ busy: false, off: false, aria: null, spin: 'none' }), JSON.stringify(await state(page)));
  check('   the same folder stays open (no jump to another path, no selection mode)', (await ev(page, () => fmPath)) === ROOT && !(await ev(page, () => fmSelMode)));

  // ---------------------------------------------------------------- 3) once at a time
  const c1 = await listed(page, ROOT);
  await ev(page, () => { fmRefreshClick(); fmRefreshClick(); fmRefreshClick(); });
  await sleep(150);
  check('3. three taps in a row read the folder once', (await listed(page, ROOT)) === c1 + 1, (await listed(page, ROOT)) + ' vs ' + c1);
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 });
  await page.click('#fmRefreshIconBtn'); await sleep(150);
  check('   and a tap after it is done reads again', (await listed(page, ROOT)) === c1 + 2);
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 });

  // a keyboard user does not lose the place: the focus comes back to the button when it is on again
  await ev(page, () => document.getElementById('fmRefreshIconBtn').focus());
  await page.keyboard.press('Enter'); await sleep(150);
  const kb = await ev(page, () => ({ off: document.getElementById('fmRefreshIconBtn').disabled, n: window.__fm.calls.length }));
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 }); await sleep(50);
  check('   Enter on the focused button refreshes too and the focus is on the button again afterwards', kb.off && (await ev(page, () => document.activeElement && document.activeElement.id)) === 'fmRefreshIconBtn', JSON.stringify(kb));

  // ---------------------------------------------------------------- 4) the folder of the moment: another folder, and a search
  await ev(page, p => fmGo(p + '/Download'), ROOT); await sleep(100);
  const cd = await listed(page, ROOT + '/Download');
  await page.click('#fmRefreshIconBtn'); await sleep(150);
  check('4. in another folder it reads that folder', (await listed(page, ROOT + '/Download')) === cd + 1 && (await ev(page, () => fmPath)) === ROOT + '/Download');
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 });
  await page.fill('#fmSearchInput', 'notes'); await page.click('#fmSearchBtn');
  await page.waitForFunction(() => fmSearchMode && !fmSearchRunning, null, { timeout: 5000 }); await sleep(150);
  const s0 = await ev(page, () => (window.__fm.searches || []).length), cs = await listed(page, ROOT + '/Download');
  await page.click('#fmRefreshIconBtn'); await sleep(250);
  const after = await ev(page, () => ({ searches: (window.__fm.searches || []).length, last: (window.__fm.searches || []).slice(-1)[0].q, mode: fmSearchMode, input: document.getElementById('fmSearchInput').value }));
  check('   with search results shown it reads the folder and runs the same search again', (await listed(page, ROOT + '/Download')) === cs + 1 && after.searches === s0 + 1 && after.last === 'notes' && after.mode && after.input === 'notes', JSON.stringify(after));
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 });
  await ev(page, () => fmSearchExit()); await sleep(100);

  // ---------------------------------------------------------------- 5) the sheet's Refresh row is still there and still works
  await ev(page, p => fmGo(p), ROOT); await sleep(100);
  await page.click('#fmMoreBtn'); await sleep(400);
  const c2 = await listed(page, ROOT);
  check('5. the "Refresh" row of the ⋯ More sheet is still there (one Refresh row, text unchanged)', (await ev(page, () => [...document.querySelectorAll('#fmMoreModal button')].filter(x => x.innerText.trim() === 'Refresh').length)) === 1);
  await page.click('#fmRefreshBtn'); await sleep(250);
  check('   and reads the folder and closes the sheet', (await listed(page, ROOT)) === c2 + 1 && !(await ev(page, () => document.getElementById('fmMoreModal').classList.contains('show'))));

  // ---------------------------------------------------------------- 6) a read that fails does not leave the button off
  await ev(page, () => { window.__origRefresh = window.fmRefresh; window.fmRefresh = () => { throw new Error('boom'); }; });
  await page.click('#fmRefreshIconBtn'); await sleep(150);
  check('6. while a read is under way the button is busy', (await state(page)).busy);
  await page.waitForFunction(() => !document.getElementById('fmRefreshIconBtn').disabled, null, { timeout: 3000 }); await sleep(50);
  check('   if the read throws, the button is on again, still, and the page has no error', JSON.stringify(await state(page)) === JSON.stringify({ busy: false, off: false, aria: null, spin: 'none' }), JSON.stringify(await state(page)));
  await ev(page, () => { window.fmRefresh = window.__origRefresh; });
  await page.close();

  // ---------------------------------------------------------------- 7) German: the name is translated
  page = await open(360, 800, { lang: 'de' });
  const de = await ev(page, () => { const e = document.getElementById('fmRefreshIconBtn'); return { label: e.getAttribute('aria-label'), title: e.title }; });
  check('7. in German the button is called Aktualisieren (name and long press)', de.label === 'Aktualisieren' && de.title === 'Aktualisieren', JSON.stringify(de));
  await page.close();

  // ---------------------------------------------------------------- 8) phone widths and languages: the row keeps its height, nothing overlaps, the title is whole, a 44px tap lands on the button
  for (const [w, h, lang] of [[320, 640, 'en'], [360, 800, 'en'], [412, 915, 'en'], [320, 640, 'de'], [360, 800, 'de'], [320, 640, 'es'], [320, 640, 'pt-BR'], [360, 800, 'pt-BR'], [320, 640, 'ar']]) {
    page = await open(w, h, { lang: lang === 'en' ? undefined : lang });
    const m = await ev(page, () => {
      const q = id => document.querySelector(id).getBoundingClientRect();
      const btn = q('#fmRefreshIconBtn'), more = q('#fmMoreBtn'), help = q('#fmTopCard .help-q'), head = q('#fmTopCard .fm-head'), title = document.querySelector('#fmTopCard .fm-head .color-card-title'), tr = title.getBoundingClientRect();
      const apart = (a, c) => a.right <= c.left + 0.5 || c.right <= a.left + 0.5;
      const el = document.getElementById('fmRefreshIconBtn'), cx = btn.left + btn.width / 2, cy = btn.top + btn.height / 2;
      const hit = [[cx, cy], [cx - 19, cy], [cx + 19, cy], [cx, cy - 19], [cx, cy + 19]].map(([x, y]) => { const e = document.elementFromPoint(x, y); return !!e && (e === el || el.contains(e)); });
      const moreHit = document.elementFromPoint(more.left + more.width / 2, more.top + more.height / 2), helpHit = document.elementFromPoint(help.left + help.width / 2, help.top + help.height / 2);
      return {
        headH: Math.round(head.height), btn: [Math.round(btn.width), Math.round(btn.height)], titleCut: title.scrollWidth > title.clientWidth + 1,
        apartMore: apart(btn, more), apartTitle: apart(btn, tr), apartHelp: apart(btn, help), hit, moreOk: moreHit && moreHit.id === 'fmMoreBtn', helpOk: !!helpHit && !!helpHit.closest('.help-q'),
        inside: btn.left >= 0 && btn.right <= innerWidth && btn.top >= head.top - 3 && btn.bottom <= head.bottom + 3,
        docW: document.documentElement.scrollWidth, W: innerWidth, 
      };
    });
    const tag = w + 'x' + h + ' ' + lang;
    check(tag + ': a 40px circle that does not make the title row taller (36px)', m.btn[0] === 40 && m.btn[1] === 40 && m.headH <= 37 && m.inside, JSON.stringify(m));
    check(tag + ': it overlaps neither the title, ⋯ More nor the ?, and the title is whole', m.apartMore && m.apartTitle && m.apartHelp && !m.titleCut, JSON.stringify(m));
    check(tag + ': a tap on its middle and four edges lands on it, ⋯ More and ? still take their own taps', m.hit.every(Boolean) && m.moreOk && m.helpOk, JSON.stringify(m));
    check(tag + ': no sideways scroll', m.docW <= m.W, m.docW + ' > ' + m.W);
    await page.close();
  }

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e && e.message); process.exit(1); });
