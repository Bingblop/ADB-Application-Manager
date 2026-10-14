// The real terminal key bar: CTRL and ALT with a key that is more than one byte. Tapping CTRL and then an arrow, Home, End, Page up/down or an F key used to send the plain key
// and leave CTRL armed, so the next letter typed became a control character (CTRL then Left, then l, cleared the screen). Now the key is sent with the modifier in the xterm form
// (CTRL+Left is ESC [ 1 ; 5 D, ALT+Left ESC [ 1 ; 3 D, CTRL+Page up ESC [ 5 ; 5 ~, CTRL+F1 ESC [ 1 ; 5 P) and a one-shot modifier is used up by any key; text that arrives in one piece
// (a paste, composed text) is not turned into a control character. One-letter keys behave as before (CTRL+c is 0x03, ALT+x is ESC x), and a locked modifier stays on.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__ph = { starts: 0, got: [], cur: null };
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; }, getWorkingMode() { return '{}'; },
      copyToClipboard() {}, getClipboardText() { return 'clip'; },
      ptyStart() { const p = window.__ph; p.starts++; return 'started:' + p.starts; },
      ptyWrite(id, b64) { window.__ph.got.push(atob(b64)); }, ptyResize() {}, ptyAck() {}, ptyClose() {}, ptyInfo() { return '{"helper":true}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const esc = s => s.replace(/[\x00-\x1f]/g, c => '\\x' + c.charCodeAt(0).toString(16).padStart(2, '0'));
  await ev(() => switchView('terminal')); await wait(500);
  await page.click('#txRealBtn'); await wait(700);
  await ev(() => onPtyStarted('rt', { ok: true }, 1)); await wait(100);
  // returns what the phone received for the actions, and the modifier state afterwards
  const run = async (name, f, want, mods) => {
    await ev(() => { window.__ph.got = []; });
    await ev(f); await wait(60);
    const r = await ev(() => ({ got: window.__ph.got.slice(), ctrl: rt.ctrl, alt: rt.alt }));
    check(name, r.got.map(esc).join('|') === want.map(esc).join('|') && r.ctrl === mods[0] && r.alt === mods[1], esc(r.got.join('|')) + ' ctrl=' + r.ctrl + ' alt=' + r.alt);
    await ev(() => { rtMod('ctrl', 0); rtMod('alt', 0); });
  };
  await run('plain Left is unchanged', () => rtPress('left'), ['\x1b[D'], [0, 0]);
  await run('CTRL then Left sends CTRL+Left and CTRL is used up', () => { rtPress('ctrl'); rtPress('left'); }, ['\x1b[1;5D'], [0, 0]);
  await run('CTRL then Left, then a letter: the letter stays a letter', () => { rtPress('ctrl'); rtPress('left'); rtOnData('l'); }, ['\x1b[1;5D', 'l'], [0, 0]);
  await run('ALT then Left sends ALT+Left', () => { rtPress('alt'); rtPress('left'); }, ['\x1b[1;3D'], [0, 0]);
  await run('CTRL and ALT then Right', () => { rtPress('ctrl'); rtPress('alt'); rtPress('right'); }, ['\x1b[1;7C'], [0, 0]);
  await run('CTRL then Home and End', () => { rtPress('ctrl'); rtPress('home'); rtPress('ctrl'); rtPress('end'); }, ['\x1b[1;5H', '\x1b[1;5F'], [0, 0]);
  await run('CTRL then Page up', () => { rtPress('ctrl'); rtPress('pgup'); }, ['\x1b[5;5~'], [0, 0]);
  await run('ALT then Page down', () => { rtPress('alt'); rtPress('pgdn'); }, ['\x1b[6;3~'], [0, 0]);
  await run('CTRL then F1 and F5', () => { rtPress('ctrl'); rtPress('f1'); rtPress('ctrl'); rtPress('f5'); }, ['\x1b[1;5P', '\x1b[15;5~'], [0, 0]);
  await run('in application cursor mode CTRL+Left is the same', () => { rt.term.write('\x1b[?1h'); }, [], [0, 0]);
  await run('...CTRL then Left with application cursor keys on', () => { rtPress('ctrl'); rtPress('left'); }, ['\x1b[1;5D'], [0, 0]);
  await run('...and plain Left is then ESC O D', () => { rtPress('left'); rt.term.write('\x1b[?1l'); }, ['\x1bOD'], [0, 0]);
  await run('an arrow from a hardware keyboard with CTRL armed', () => { rtPress('ctrl'); rtOnData('\x1b[A'); }, ['\x1b[1;5A'], [0, 0]);
  await run('pasted or composed text is not turned into a control character, and CTRL is used up', () => { rtPress('ctrl'); rtOnData('hello'); rtOnData('l'); }, ['hello', 'l'], [0, 0]);
  await run('a locked CTRL stays on after an arrow', () => { rtPress('ctrl'); rtPress('ctrl'); rtPress('left'); }, ['\x1b[1;5D'], [2, 0]);
  await run('one-letter keys: CTRL+c is 0x03', () => { rtPress('ctrl'); rtOnData('c'); }, ['\x03'], [0, 0]);
  await run('one-letter keys: ALT+x is ESC x', () => { rtPress('alt'); rtOnData('x'); }, ['\x1bx'], [0, 0]);
  await run('Esc and Tab with ALT are as before', () => { rtPress('alt'); rtPress('esc'); rtPress('alt'); rtPress('tab'); }, ['\x1b\x1b', '\x1b\t'], [0, 0]);
  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
