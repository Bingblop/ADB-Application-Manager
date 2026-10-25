// v7.15.1 bug fix: the full-screen "real" terminal (xterm.js) must render in a monospace font. The page's global `* { font-family: var(--ui-font, var(--font-sans)) }`
// reset (kept so form controls inherit the UI font) otherwise lands directly on xterm's cell spans and beats the monospace they inherit from .xterm, so the
// fixed-width character grid drew a proportional font and the glyphs overlapped ("blended"/unreadable). `.rt-term .xterm, .rt-term .xterm *` is pinned back to the
// monospace stack; this script opens the real terminal, writes output, and asserts the container, the rows area and a rendered cell are all monospaced (not the UI font).
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + ((ok || extra === undefined) ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {},
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, rootAvailable: true }); },
      getPermissionStatus() { return JSON.stringify({ files: true, usage: true, overlay: true, sdk: 34 }); },
      executeAppAction() { return 'Success'; },
      // the real terminal is gated on the app's pty bridge; mock just enough to open xterm (no real pty — we write to the term directly)
      ptyStart() { return 'started:1'; }, ptyResize() {}, ptyWrite() {}, ptyClose() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);

  const opened = await page.evaluate(() => { try { switchView('terminal'); } catch (e) {} try { rtOpen('app'); return true; } catch (e) { return String(e && e.message || e); } });
  check('1. the real terminal opens', opened === true, opened);

  const ready = await page.waitForFunction(() => window.rt && window.rt.term && document.querySelector('.rt-overlay.show .xterm-rows'), null, { timeout: 8000 }).then(() => true).catch(() => false);
  check('2. xterm.js loads and renders its rows', ready);

  await page.evaluate(() => { try { window.rt.term.write(['echo hi', 'root@device:/ # ls -la', 'total 0 drwxr-xr-x WmiIl 0123456789'].join('\r\n')); if (window.rt.fit) window.rt.fit.fit(); } catch (e) {} });
  await page.waitForTimeout(250);

  const f = await page.evaluate(() => {
    const root = document.querySelector('.rt-overlay.show .xterm');
    const rows = root && root.querySelector('.xterm-rows');
    const cell = rows && [...rows.querySelectorAll('span')].find(s => s.textContent && s.textContent.trim());
    const ff = el => el ? getComputedStyle(el).fontFamily : '';
    return { root: ff(root), rows: ff(rows), cell: ff(cell), hasCell: !!cell };
  });
  const mono = s => /\bmono/i.test(s) || /monospace/i.test(s);
  const proportional = s => /apple-system|blinkmacsystemfont|segoe ui|helvetica|sans-serif/i.test(s) && !mono(s);
  check('3. the .xterm container is monospaced, not the proportional UI font', mono(f.root) && !proportional(f.root), f.root);
  check('4. the .xterm-rows render area is monospaced', mono(f.rows) && !proportional(f.rows), f.rows);
  check('5. a rendered cell span is monospaced, so the grid glyphs line up (no overlap)', f.hasCell && mono(f.cell) && !proportional(f.cell), f.cell + ' hasCell=' + f.hasCell);

  check('6. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
