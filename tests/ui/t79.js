// v7.0, what the review of the emoji removal found: a line that says something failed says so in words and in color (the warning sign was the only cue),
// buttons that were told apart by a picture are told apart again (Uninstall and Delete, the main action, a pinned command, locked permissions,
// "launch through the shell"), the terminal's find box keeps room for its hint at 320 px, the sub-tabs and chips are a little larger, the names of the tabs
// in sentences are the new ones, and nothing writes a symbol as an escape.
const fs = require('fs');
const path = require('path');
const { fileURLToPath } = require('url');
const { chromium, PAGE, REPO } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ratio = (a, b2) => { const lum = c => { const v = c.match(/[\d.]+/g).slice(0, 3).map(Number).map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; }; const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };

(async () => {
  const b = await chromium.launch();
  const open = async (size) => {
    const page = await b.newPage({ viewport: size || { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' } });
    await page.goto(PAGE);
    await page.waitForFunction(() => document.querySelectorAll('.tab-btn').length > 0);
    return page;
  };
  const page = await open();
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // 1) error lines: words first, the failed color, and the next thing written there takes the color away
  const probe = (id) => ev(i => { const el = document.getElementById(i); const bad = el.querySelector('.is-err'); const ref = document.createElement('span'); ref.className = 'upd-msg error'; document.body.appendChild(ref); const want = getComputedStyle(ref).color; ref.remove(); return { text: el.innerText.trim(), marked: !!bad, color: bad ? getComputedStyle(bad).color : '', want }; }, id);
  const isErr = (r, words) => r.marked && r.text.startsWith(words) && r.color === r.want;
  await ev(() => { storeCurrent = { slug: 'x' }; onStoreApp(JSON.stringify({ slug: 'x', status: 'error', error: 'SocketTimeoutException' })); });
  let r = await probe('storeDetailBody');
  check('1. a store app that cannot be loaded: "Could not load this app: SocketTimeoutException", in the failed color', isErr(r, 'Could not load this app: SocketTimeoutException'), JSON.stringify(r));
  await ev(() => { storeSrc.github.items = []; storeSrc.github.loaded = true; storeSrc.github.loading = false; storeSrc.github.error = 'java.net.UnknownHostException'; renderSourceStatus('github', 0); });
  r = await probe('githubStatus');
  check('   a store list that cannot be loaded: "Could not load: ..."', isErr(r, 'Could not load: java.net.UnknownHostException'), JSON.stringify(r));
  await ev(() => { storeSrc.github.error = ''; renderSourceStatus('github', 0); });
  r = await probe('githubStatus');
  check('   and when the error is gone the color goes with it', !r.marked, JSON.stringify(r));
  await ev(() => { document.getElementById('githubStatus').insertAdjacentHTML('afterend', '<button id="tstBtn">x</button>'); storeInline['pk'] = { statusId: 'githubStatus', btnId: 'tstBtn' }; onStoreInstallProgress(JSON.stringify({ pkg: 'pk', stage: 'error', message: 'EACCES (Permission denied)' })); });
  r = await probe('githubStatus');
  check('   an install that fails: "Install failed: EACCES (Permission denied)"', isErr(r, 'Install failed: EACCES (Permission denied)'), JSON.stringify(r));
  await ev(() => { storeInline['pk2'] = { statusId: 'githubStatus', btnId: 'tstBtn' }; onStoreInstallProgress(JSON.stringify({ pkg: 'pk2', stage: 'downloading', message: 'Downloading', percent: 40 })); });
  r = await probe('githubStatus');
  check('   progress after it is plain text again ("Downloading 40%")', !r.marked && r.text === 'Downloading 40%', JSON.stringify(r));
  await ev(() => { onInstallInspected(JSON.stringify({ error: 'EACCES (Permission denied)' })); });
  r = await probe('installPickHint');
  check('   a package that cannot be read: "Could not read that package: ..."', isErr(r, 'Could not read that package: EACCES (Permission denied)'), JSON.stringify(r));
  await ev(() => { window.AndroidBridge.inspectInstallSource = () => { throw new Error('boom'); }; onInstallFilePicked('/x/y.apk'); });
  r = await probe('installPickHint');
  check('   a file the app cannot open: "Could not open that file: boom"', isErr(r, 'Could not open that file: boom'), JSON.stringify(r));
  await ev(() => { onApkScan(JSON.stringify({ status: 'error', error: 'EACCES' })); });
  r = await probe('apkScanStatus');
  check('   the search for packages that fails: "The search failed: EACCES"', isErr(r, 'The search failed: EACCES'), JSON.stringify(r));
  await ev(() => { onApkScan(JSON.stringify({ status: 'error' })); });
  r = await probe('apkScanStatus');
  check('   and without a reason: "The search failed."', isErr(r, 'The search failed.'), JSON.stringify(r));
  await ev(() => { window.AndroidBridge.scanApkFiles = () => { throw new Error('boom'); }; scanApkFiles(); });
  r = await probe('apkScanStatus');
  check('   a search that cannot start: "The search failed: boom"', isErr(r, 'The search failed: boom'), JSON.stringify(r));
  await ev(() => { onApkScan(JSON.stringify({ status: 'ok', files: [] })); });
  r = await probe('apkScanStatus');
  check('   a search that works shows no failed color', !r.marked, JSON.stringify(r));
  await ev(() => { document.getElementById('vtResultBox').style.display = ''; onVtResult(JSON.stringify({ stage: 'error', error: 'bad key' })); });
  r = await probe('vtResultBox');
  check('   a VirusTotal scan that fails: "The scan failed: bad key"', isErr(r, 'The scan failed: bad key'), JSON.stringify(r));

  // 2) buttons that were told apart by a picture
  const bt = await ev(() => {
    const css = (sel) => { const e = document.querySelector(sel); return e ? getComputedStyle(e) : null; };
    const plain = css('.batch-grid-btn:not(.danger):not(.accent)'), danger = css('.batch-grid-btn.danger'), accent = css('.batch-grid-btn.accent');
    const ref = document.createElement('span'); ref.className = 'upd-msg error'; document.body.appendChild(ref); const bloat = getComputedStyle(ref).color; ref.remove();
    return { plain: plain.color, danger: danger.color, dangerBorder: danger.borderColor, bloat, accentBg: accent.backgroundColor, accentColor: accent.color, plainBg: plain.backgroundColor, accentVar: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() };
  });
  check('2. Uninstall and Delete (the danger buttons) are in the failed color, the others are not', bt.danger === bt.bloat && bt.plain !== bt.danger && bt.dangerBorder === bt.bloat, JSON.stringify(bt));
  check('   the main action (the accent buttons: Install, Restore, Reinstall) is tinted and in the accent color', bt.accentBg !== bt.plainBg && bt.accentColor !== bt.plain, JSON.stringify(bt));
  await ev(() => { termSaved = [{ name: 'Connect', cmd: 'adb connect 192.168.1.5', pinned: true }]; termRenderPins(); });
  const pin = await ev(() => { const p = document.querySelector('#view-terminal .term-pin'), o = document.querySelector('#view-terminal .apps-tool-btn:not(.term-pin)'); return { pinned: p && getComputedStyle(p).borderColor, other: o && getComputedStyle(o).borderColor, accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(), pinColor: p && getComputedStyle(p).color, text: p && p.textContent }; });
  check('   a pinned command next to the built-in buttons has the accent color (it is not "Connect 5555")', pin.text === 'Connect' && pin.pinned !== pin.other && pin.pinColor !== '', JSON.stringify(pin));
  await ev(() => { termHistory = ['pm list packages']; termOpenModal('history'); });
  const hist = await ev(() => { const x = document.querySelector('#termModal .term-mini[title="Remove"]') || document.querySelector('.term-mini[title="Remove"]'); return x ? x.getAttribute('aria-label') : null; });
  check('   the remove button of a history row has a name for a screen reader', hist === 'Remove from history', String(hist));
  await ev(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); });
  check('   the F-Droid copy button says Copy (it was a symbol)', (await ev(() => document.getElementById('fdroidCopyBtn').innerText)) === 'Copy address & fingerprint');
  const av = await ev(() => { const a = document.getElementById('arcModalIcon'); a.innerText = ''; const empty = getComputedStyle(a).display; a.innerText = '+'; return { empty, filled: getComputedStyle(a).display }; });
  check('   a sheet with no symbol to show has no empty tile (the archive sheet), and one with a symbol still has it', av.empty === 'none' && av.filled !== 'none', JSON.stringify(av));

  // 3) the find box of the terminal at three widths, and the sizes of the sub-tabs and chips
  for (const w of [320, 360, 412]) {
    const p = await open({ width: w, height: 760 });
    await p.evaluate(() => switchView('terminal'));
    await sleep(100);
    const m = await p.evaluate(() => {
      const inp = document.getElementById('termSearch'), nav = Array.from(document.querySelectorAll('.find-bar .find-nav')), ib = inp.getBoundingClientRect();
      const probe = document.createElement('canvas').getContext('2d'); probe.font = getComputedStyle(inp).fontSize + ' ' + getComputedStyle(inp).fontFamily;
      const hint = probe.measureText(inp.placeholder).width;
      return { input: Math.round(ib.width), hint: Math.round(hint), navInside: nav.every(n => n.getBoundingClientRect().right <= innerWidth), wrapped: nav[0].getBoundingClientRect().top > ib.bottom - 2, side: document.documentElement.scrollWidth <= innerWidth };
    });
    check('3. [' + w + ' px] the terminal find box is wide enough for its hint (' + m.input + ' px for ' + m.hint + ' px of hint), the buttons are on screen, nothing scrolls sideways', m.input - 20 >= m.hint && m.navInside && m.side, JSON.stringify(m));
    await p.close();
  }
  const sz = await ev(() => ({ sdbTab: getComputedStyle(document.querySelector('.sdb-tab')).fontSize, store: getComputedStyle(document.querySelector('.store-subtab')).fontSize, lc: getComputedStyle(document.querySelector('.lc-chip, .lc-key') || document.body).fontSize }));
  check('   the sub-tabs of Hidden Settings and App Stores are 14 px (they were 13), the log level chips 12 px (11)', sz.sdbTab === '14px' && sz.store === '14px', JSON.stringify(sz));

  // 4) readable buttons that only have words: the filled accent ones
  for (const mode of ['dark', 'light']) {
    await ev(m => setAppearance(m), mode);
    await sleep(80);
    const c = await ev(() => {
      const prof = document.createElement('button'); prof.className = 'profile-btn accent'; prof.textContent = 'Apply'; document.body.appendChild(prof);
      const st = document.querySelector('.store-subtab'); st.classList.add('active');
      const out = { prof: [getComputedStyle(prof).color, getComputedStyle(prof).backgroundColor], sub: [getComputedStyle(st).color] };
      prof.remove(); st.classList.remove('active');
      return out;
    });
    const rt = ratio(c.prof[0], c.prof[1]);
    check('4. [' + mode + '] the filled accent button ("Apply", "Watching") is readable (contrast ' + rt.toFixed(1) + ':1, at least 4.5)', rt >= 4.5, JSON.stringify(c));
  }

  // 5) names in sentences, and no symbols written as escapes
  const src = fs.readFileSync(fileURLToPath(PAGE), 'utf8');
  const stale = [/Go to Applications/, /<b>Files<\/b>/, /Installing from Files/, /Installs from Files/, /From Files:/, /Open Saved Lists/, /Applications tab/].filter(re => re.test(src)).map(String);
  check('5. no sentence of the page names a tab that has another name now (Files, Applications, Saved Lists)', stale.length === 0, JSON.stringify(stale));
  const java = fs.readFileSync(path.join(REPO, 'src', 'com', 'bloatware', 'bingblop', 'MainActivity.java'), 'utf8');
  check('   the tile that stops the quick list tells where it is set with the names of v7.0', /Open Saved App Lists in the app and tap Quick list\./.test(java) && !/\\u26a1/i.test(java));
  check('   the About tab names File Manager', /Installs started from File Manager/.test(await ev(() => document.getElementById('view-about').innerText)));

  // 6) the permissions of an app: the ones that cannot be changed carry a "locked" badge (the padlock said so before)
  const mock = require('./lib/sheet_mock.js');
  const sp = await b.newPage({ viewport: { width: 360, height: 800 } });
  sp.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await sp.addInitScript(mock.initScript, { dark: true });
  await sp.goto(PAGE); await sleep(450);
  await sp.evaluate(() => { closeInspector(); openInspector('com.sec.android.app.sbrowser'); });
  await sleep(600);
  const lk = await sp.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#permsContainer .perm-row'));
    const locked = rows.filter(r => r.querySelector('.perm-toggle-btn.locked'));
    return { rows: rows.length, locked: locked.length, badged: locked.filter(r => Array.from(r.querySelectorAll('.perm-kind')).some(k => k.innerText.trim().toLowerCase() === 'locked')).length, wrong: rows.filter(r => !r.querySelector('.perm-toggle-btn.locked') && Array.from(r.querySelectorAll('.perm-kind')).some(k => k.innerText.trim().toLowerCase() === 'locked')).length };
  });
  check('6. every permission that cannot be changed has the "locked" badge, and no other has it', lk.rows > 5 && lk.locked > 0 && lk.badged === lk.locked && lk.wrong === 0, JSON.stringify(lk));
  await sp.close();

  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  process.exit(bad ? 1 : 0);
})();
