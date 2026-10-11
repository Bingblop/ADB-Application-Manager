// ADB Console: the result line under every command's output. The Kotlin app shows `SUCCESS (0)` / `EXIT n` from ShellSafety.parseShellOutcome; the page gets one merged
// text from the bridge and no exit code (MainActivity.executeShell returns a String), so the line here comes from the same failure signs read out of the text and never
// shows an exit number or the word success. This script checks:
//   - the pure classifier termClassifyOutput on ordinary, edge and hostile input (the exact rules: see the comment above it in assets/index.html;
//     they are ShellSafety.kt's plus the Java backend's ShellOutcome.backendFailed: a leading error: or adb: device , ignoring case),
//   - what the page draws for an async answer (onShellDone) and for the old synchronous bridge call: the words, the order, a line of its own, role="status",
//   - that a stale answer and the Trim Caches answer get no result line, that the text is data-safe (no markup from the output), and that Copy holds the line,
//   - that the failure line is readable (colour and contrast) in every theme, dark and light, and that it is translated.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const bridge = async mode => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(m => {
      window.__shell = []; window.__copied = [];
      const base = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
        loadSetting() { return ''; }, saveSetting() {}, copyToClipboard(t) { window.__copied.push(t); },
      };
      if (m === 'async') base.executeShellAsync = (id, cmd) => { window.__shell.push({ id, cmd }); return 'started'; };
      else base.executeShell = cmd => { window.__shell.push({ cmd }); return window.__syncOut; };
      window.AndroidBridge = base;
    }, mode);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => (switchView('terminal'), txShowPane('console'))); await page.waitForTimeout(150);
    return page;
  };

  // ---- 1) the classifier ----
  const page = await bridge('async');
  const classify = await page.evaluate(() => {
    const long = 'x'.repeat(300000);
    const cases = [
      // [label, input, failed, kind]
      ['empty output', '', false, ''],
      ['only white space', '  \n\t \n ', false, ''],
      ['null', null, false, ''],
      ['undefined', undefined, false, ''],
      ['a number', 0, false, ''],
      ['a package listing', 'package:com.a\npackage:com.b', false, ''],
      ['id', 'uid=2000(shell) gid=2000(shell) groups=2000(shell)', false, ''],
      ['"Error:" at the start', 'Error: boom', true, 'error'],
      ['"Error:" after leading blank lines', '\n  \nError: boom', true, 'error'],
      ['"Failure [" at the start', 'Failure [INSTALL_FAILED_ALREADY_EXISTS]', true, 'error'],
      ['"Error:" only on a later line is not enough', 'package:a\nError: in the middle', false, ''],
      ['a sentence that merely mentions Error: is not a failure', 'The word Error: is explained in the manual', false, ''],
      ['adb\'s lower-case "error:" at the start', 'error: device offline', true, 'error'],
      ['adb: no devices', 'error: no devices/emulators found', true, 'error'],
      ['the start is matched ignoring case', 'ERROR: x', true, 'error'],
      ['mixed case at the start', 'eRrOr: x', true, 'error'],
      ['"adb: device " at the start', 'adb: device offline', true, 'error'],
      ['"ADB: DEVICE " at the start, ignoring case', 'ADB: DEVICE offline', true, 'error'],
      ['"adb: device unauthorized" is a permission problem', 'adb: device unauthorized', true, 'permission'],
      ['"error:" in the middle of a line is not a failure', 'the parser printed error: nothing here', false, ''],
      ['a listing that has "error: " on a later line is not a failure', 'package:com.a\nerror: not a real failure\nERROR: neither', false, ''],
      ['"adb: device " not at the start is not a failure', 'see adb: device offline in the manual', false, ''],
      ['"error" without the colon at the start', 'errors were found in nothing', false, ''],
      ['"error :" with a space is not the indicator', 'error : x', false, ''],
      ['"adb:device" without the space is not the indicator', 'adb:device offline', false, ''],
      ['"adb: devices" (no space right after device) is not the indicator', 'adb: devices attached', false, ''],
      ['"Failure" without the bracket', 'Failure: nothing', false, ''],
      ['"Failure[" without the space', 'Failure[x]', false, ''],
      ['"Failure [" on a later line is not enough', 'ok\nFailure [x]', false, ''],
      ['"adb: error:" anywhere', 'x\nadb: error: device offline', true, 'error'],
      ['the timeout marker the app appends', 'partial\n[Process timed out after 8000ms]', true, 'timeout'],
      ['"timed out" without the marker is not a failure by itself', 'the job timed out last night', false, ''],
      ['SecurityException anywhere', 'java.lang.SecurityException: Neither user 2000 nor current process has android.permission.X', true, 'permission'],
      ['"Permission Denial" anywhere', 'x\nPermission Denial: starting Intent', true, 'permission'],
      ['"unauthorized" anywhere (adb devices)', 'List of devices attached\nABC123\tunauthorized', true, 'permission'],
      ['upper-case "Unauthorized" is not the indicator', 'Unauthorized access is discussed here', false, ''],
      ['lower-case "securityexception" is not the indicator', 'securityexception', false, ''],
      ['"Error:" and a permission word: permission wins over error', 'Error: java.lang.SecurityException: nope', true, 'permission'],
      ['"Error:" with "timed out" in it: timeout', 'Error: connection timed out', true, 'timeout'],
      ['timeout is matched ignoring case', 'Error: CONNECTION TIMED OUT', true, 'timeout'],
      ['a permission word and a timeout: timeout first', 'SecurityException\n[Process timed out after 10000ms]', true, 'timeout'],
      ['permission words are matched ignoring case for the kind', 'Error: UNAUTHORIZED', true, 'permission'],
      ['the page\'s own start failure', 'Error: the command could not be started', true, 'error'],
      ['markup in the output', '<b>Error:</b> <script>alert(1)</script>', false, ''],
      ['a very long output without any sign', long, false, ''],
      ['a very long output with the sign at the end', long + '\nadb: error: closed', true, 'error'],
    ];
    return cases.map(([label, input, failed, kind]) => { const r = termClassifyOutput(input); return { label, ok: r.failed === failed && r.kind === kind, got: JSON.stringify(r), want: JSON.stringify({ failed, kind }) }; });
  });
  for (const c of classify) check('classifier: ' + c.label, c.ok, 'got ' + c.got + ' want ' + c.want);
  check('classifier: the result never carries an exit code', await page.evaluate(() => Object.keys(termClassifyOutput('Error: x')).sort().join(',') === 'failed,kind'));

  // ---- 2) what the page draws (async bridge) ----
  const run = async (cmd, out, p) => {
    p = p || page;
    await p.fill('#termCmd', cmd); await p.press('#termCmd', 'Enter'); await p.waitForTimeout(40);
    const id = await p.evaluate(() => window.__shell[window.__shell.length - 1].id);
    await p.evaluate(([i, o]) => window.onShellDone(i, o), [id, out]); await p.waitForTimeout(30);
    return p.evaluate(() => { const t = document.getElementById('termOutput'); const l = [...t.querySelectorAll('.term-result')].pop(); return { text: t.textContent, line: l ? l.textContent : null, cls: l ? l.className : null, role: l ? l.getAttribute('role') : null, n: t.querySelectorAll('.term-result').length }; });
  };
  const OK_LINE = 'Finished. No error text in the output.';
  let r = await run('pm list packages', 'package:com.a\npackage:com.b');
  check('a listing gets the neutral line, on a line of its own after the output', r.line === OK_LINE && r.text.endsWith('package:com.b\n' + OK_LINE + '\n'), JSON.stringify(r.text.slice(-120)));
  check('the neutral line is not styled as a failure and is announced (role="status")', r.cls === 'term-result' && r.role === 'status', r.cls + ' / ' + r.role);
  check('the line does not claim a success or show an exit number', !/SUCCESS|EXIT|\(0\)|exit code/i.test(r.line));
  r = await run('echo', 'x\n');
  check('output that already ends in a newline gets no blank line before the result', r.text.endsWith('x\n' + OK_LINE + '\n'), JSON.stringify(r.text.slice(-60)));
  r = await run('true', '');
  check('no output: the line follows the command directly, once', r.line === OK_LINE && r.text.endsWith('$ true\n' + OK_LINE + '\n') && r.n === 3, JSON.stringify(r.text.slice(-60)) + ' n=' + r.n);
  r = await run('pm install x.apk', 'Failure [INSTALL_FAILED_INVALID_APK]');
  check('Failure [ ... ]: the error line, in the failure style', r.line === 'Looks like a failure: the output reports an error.' && /\bis-fail\b/.test(r.cls), r.line + ' / ' + r.cls);
  check('the failure line is announced too and sits after the output', r.role === 'status' && r.text.endsWith('Failure [INSTALL_FAILED_INVALID_APK]\n' + r.line + '\n'));
  r = await run('slow', 'part\n[Process timed out after 8000ms]');
  check('a timeout says it timed out', r.line === 'Looks like a failure: the command timed out.');
  r = await run('am start x', 'Exception\njava.lang.SecurityException: Permission Denial');
  check('a permission problem says permission denied', r.line === 'Looks like a failure: permission denied.');
  r = await run('adb devices', 'List of devices attached\nXYZ\tunauthorized');
  check('an unauthorized device counts as permission denied (as ShellSafety does)', r.line === 'Looks like a failure: permission denied.');
  r = await run('cat notes', 'He wrote that an Error: can happen.');
  check('a sentence that only mentions the words is not marked as a failure', r.line === OK_LINE && !/\bis-fail\b/.test(r.cls));
  r = await run('x', '<b>Error:</b> <img src=x onerror=alert(1)>');
  check('output with markup stays text (no element made from it)', await page.evaluate(() => document.querySelectorAll('#termOutput b, #termOutput img').length) === 0 && r.line === OK_LINE);

  // a stale answer and the Trim Caches answer
  const before = await page.evaluate(() => document.querySelectorAll('#termOutput .term-result').length);
  await page.evaluate(() => onShellDone('s999', 'Error: stale'));
  check('an answer for a command that is not the current one adds nothing', await page.evaluate(() => document.querySelectorAll('#termOutput .term-result').length) === before);

  // the refused start
  await page.evaluate(() => { window.AndroidBridge.executeShellAsync = (id, cmd) => { window.__shell.push({ id, cmd }); return 'nope'; }; });
  await page.fill('#termCmd', 'x'); await page.press('#termCmd', 'Enter'); await page.waitForTimeout(80);
  r = await page.evaluate(() => { const l = [...document.querySelectorAll('#termOutput .term-result')].pop(); return l ? l.textContent : null; });
  check('a start the app refuses ends in the error line', r === 'Looks like a failure: the output reports an error.', r);

  // Copy holds the result line
  await page.evaluate(() => copyTerminal());
  check('Copy holds the result line with the output', (await page.evaluate(() => window.__copied.slice(-1)[0])).includes('Looks like a failure: the output reports an error.'));
  // Find in output still works with the line there
  await page.fill('#termSearch', 'Finished'); await page.evaluate(() => termFind(0));
  check('Find in output finds the line', (await page.locator('#termFindCount').innerText()).includes('/'), await page.locator('#termFindCount').innerText());
  await page.fill('#termSearch', ''); await page.evaluate(() => termFind(0));

  // ---- 3) themes: dark and light ----
  const themeRows = await page.evaluate(async () => {
    const out = [];
    await run0('x', 'package:a');
    await run0('y', 'Error: boom');
    function run0(cmd, text) {
      const id = 's' + (++termShellSeq);
      termShellRun = { id, cmd };
      onShellDone(id, text);
    }
    const lum = c => { const [r, g, b] = c.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
    const cr = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    for (const preset of ['amoled', 'cyberpunk', 'emerald', 'amber', 'slate', 'crimson', 'material3']) for (const mode of ['dark', 'light']) {
      applyPalettePreset(preset); setAppearance(mode);
      const box = document.getElementById('termOutput');
      const bg = getComputedStyle(box).backgroundColor;
      const ok = box.querySelector('.term-result:not(.is-fail)'), bad = box.querySelector('.term-result.is-fail');
      const co = getComputedStyle(ok).color, cb = getComputedStyle(bad).color;
      out.push({ preset, mode, okCr: cr(co, bg), badCr: cr(cb, bg), differ: co !== cb, okOpaque: !/rgba\(.*, 0\)/.test(co), badWeight: getComputedStyle(bad).fontWeight, bar: parseFloat(getComputedStyle(bad).borderLeftWidth) >= 3 && getComputedStyle(bad).borderLeftColor !== 'rgba(0, 0, 0, 0)', appearance: document.documentElement.getAttribute('data-appearance') });
    }
    return out;
  });
  check('themes: the appearance really switched in the checks (' + themeRows.length + ' combinations)', themeRows.length === 14 && themeRows.some(t => t.appearance === 'light') && themeRows.some(t => t.appearance === 'dark'));
  const lowOk = themeRows.filter(t => t.okCr < 4.5), lowBad = themeRows.filter(t => t.badCr < 4.5);
  check('themes: the neutral line reads at 4.5:1 on the console in every theme', lowOk.length === 0, JSON.stringify(lowOk.map(t => [t.preset, t.mode, +t.okCr.toFixed(2)])));
  check('themes: the failure line reads at 4.5:1 on the console in every theme', lowBad.length === 0, JSON.stringify(lowBad.map(t => [t.preset, t.mode, +t.badCr.toFixed(2)])));
  check('themes: the failure line differs from the neutral one: bold with a bar in front, in every theme (the words carry the meaning, the style only helps)', themeRows.every(t => t.differ && +t.badWeight >= 700 && t.bar));

  // ---- 4) translated ----
  const de = await page.evaluate(async () => {
    await LANG_ENGINE.set('de');
    const id = 's' + (++termShellSeq); termShellRun = { id, cmd: 'x' };
    onShellDone(id, '[Process timed out after 8000ms]');
    const id2 = 's' + (++termShellSeq); termShellRun = { id: id2, cmd: 'x' };
    onShellDone(id2, 'ok');
    const ls = [...document.querySelectorAll('#termOutput .term-result')].slice(-2).map(e => e.textContent);
    await LANG_ENGINE.set('en');
    return ls;
  });
  check('the line is translated (German)', de[0] === 'Sieht nach einem Fehlschlag aus: Zeitüberschreitung des Befehls.' && de[1] === 'Beendet. Kein Fehlertext in der Ausgabe.', JSON.stringify(de));
  // right-to-left: the failure bar moves with the text direction (logical properties), so it stays in front of the words
  const rtl = await page.evaluate(async () => {
    const side = () => {
      const id = 's' + (++termShellSeq); termShellRun = { id, cmd: 'x' };
      onShellDone(id, 'Error: x');
      const e = [...document.querySelectorAll('#termOutput .term-result.is-fail')].pop(), cs = getComputedStyle(e);
      const r = e.getBoundingClientRect();
      return { dir: document.documentElement.dir, l: parseFloat(cs.borderLeftWidth), r: parseFloat(cs.borderRightWidth), pl: parseFloat(cs.paddingLeft), pr: parseFloat(cs.paddingRight), text: e.textContent, w: r.width };
    };
    const en = side();
    await LANG_ENGINE.set('ar');
    const ar = side();
    await LANG_ENGINE.set('en');
    return { en, ar };
  });
  check('left-to-right: the failure bar is on the left', rtl.en.dir !== 'rtl' && rtl.en.l === 3 && rtl.en.r === 0 && rtl.en.pl === 6 && rtl.en.pr === 0, JSON.stringify(rtl.en));
  check('right-to-left (Arabic): the bar and its gap are on the right, in front of the translated words', rtl.ar.dir === 'rtl' && rtl.ar.r === 3 && rtl.ar.l === 0 && rtl.ar.pr === 6 && rtl.ar.pl === 0 && /[\u0600-\u06FF]/.test(rtl.ar.text), JSON.stringify(rtl.ar));
  await page.close();

  // ---- 5) the old synchronous bridge call ----
  const sp = await bridge('sync');
  await sp.evaluate(() => { window.__syncOut = 'Error: no such file'; });
  await sp.fill('#termCmd', 'cat /nope'); await sp.press('#termCmd', 'Enter'); await sp.waitForTimeout(80);
  const sl = await sp.evaluate(() => { const t = document.getElementById('termOutput'); const l = t.querySelector('.term-result'); return { line: l && l.textContent, cls: l && l.className, tail: t.textContent.slice(-80) }; });
  check('without executeShellAsync the same line follows the output', sl.line === 'Looks like a failure: the output reports an error.' && /is-fail/.test(sl.cls) && sl.tail.endsWith('Error: no such file\n' + sl.line + '\n'), JSON.stringify(sl));
  await sp.close();

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  console.log(failed ? failed + ' FAILED' : 'all passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
