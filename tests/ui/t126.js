// The real terminal (full screen): xterm.js on a pseudo-terminal. The page runs against the real pty helper of this computer (native/pty/x86_64/ptyexec, started by this script with
// bash) through the same bridge calls the app makes (ptyStart / ptyWrite / ptyResize / ptyAck / ptyClose and onPtyData / onPtyExit), so typing, the terminal size, resizing,
// Ctrl-C, the extra keys, a full-screen program (vim), exit and restart, hiding and coming back are exercised for real.
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { chromium, PAGE } = require('./lib/pw');
const HELPER = path.resolve(__dirname, '..', '..', 'native', 'pty', 'x86_64', 'ptyexec');
(async () => {
  if (!fs.existsSync(HELPER)) { console.log('skip: no native/pty/x86_64/ptyexec (run native/pty/build.sh)'); return; }
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  // the "phone": one helper process per session id
  const procs = {}; const log = { starts: [], closes: [], acks: 0, resizes: [] };
  const frame = (type, buf) => { const f = Buffer.alloc(3 + buf.length); f[0] = type.charCodeAt(0); f.writeUInt16BE(buf.length, 1); buf.copy(f, 3); return f; };
  await page.exposeFunction('__ptyStart', (id, backend, rows, cols) => {
    log.starts.push([backend, rows, cols]);
    const p = spawn(HELPER, [String(rows), String(cols), '--', '/bin/bash', '--norc', '-i'], { env: Object.assign({}, process.env, { TERM: 'xterm-256color', PS1: 'phone$ ' }) });
    procs[id] = p;
    p.stdout.on('data', d => page.evaluate(([i, b64]) => window.onPtyData(i, b64), [id, d.toString('base64')]).catch(() => {}));
    p.on('exit', code => { page.evaluate(([i, c]) => window.onPtyExit(i, c), [id, code == null ? -1 : code]).catch(() => {}); });
    setTimeout(() => page.evaluate(i => window.onPtyStarted(i, { ok: true }), id).catch(() => {}), 20);
  });
  await page.exposeFunction('__ptyWrite', (id, b64) => { const p = procs[id]; if (p) p.stdin.write(frame('D', Buffer.from(b64, 'base64'))); });
  await page.exposeFunction('__ptyResize', (id, rows, cols) => { log.resizes.push([rows, cols]); const p = procs[id]; if (p) { const f = Buffer.alloc(5); f[0] = 'R'.charCodeAt(0); f.writeUInt16BE(rows, 1); f.writeUInt16BE(cols, 3); p.stdin.write(f); } });
  await page.exposeFunction('__ptyClose', id => { log.closes.push(id); const p = procs[id]; if (p) { try { p.stdin.end(); } catch (e) {} delete procs[id]; } });
  await page.exposeFunction('__ptyAck', () => { log.acks++; });
  await page.addInitScript(() => {
    window.__clip = 'pasted-text';
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return (window.__kv && window.__kv[k]) || (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; }, getWorkingMode() { return '{}'; },
      copyToClipboard(t) { window.__copied = t; }, getClipboardText() { return window.__clip; },
      ptyStart(id, backend, rows, cols) { window.__ptyStart(id, backend, rows, cols); return 'started'; }, ptyWrite(id, b64) { window.__ptyWrite(id, b64); }, ptyResize(id, r, c) { window.__ptyResize(id, r, c); },
      ptyAck(id, n) { window.__ptyAck(id, n); }, ptyClose(id) { window.__ptyClose(id); }, ptyInfo() { return '{"helper":true}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const screen = () => ev(() => { const t = rt.term.buffer.active; const out = []; for (let i = 0; i < t.length; i++) { const l = t.getLine(i); if (l) out.push(l.translateToString(true)); } return out.join('\n'); });
  const until = async (re, ms) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (re.test(await screen())) return true; await wait(40); } return false; };

  await ev(() => switchView('terminal')); await wait(500);
  check('the Terminal tab has a Real terminal button', await ev(() => !!document.getElementById('txRealBtn') && /Real terminal/.test(document.getElementById('txRealBtn').innerText)));
  await page.click('#txRealBtn'); await wait(600);
  check('it opens full screen and loads xterm.js on the first use', await ev(() => !!window.Terminal && !!window.FitAddon && document.getElementById('rtOverlay').classList.contains('show') && document.getElementById('rtOverlay').getBoundingClientRect().height === window.innerHeight));
  check('the session starts with the terminal size of the screen', log.starts.length === 1 && log.starts[0][1] > 10 && log.starts[0][2] > 20, JSON.stringify(log.starts));
  check('the shell prompt appears', await until(/phone\$/));
  await ev(() => rt.term.focus());
  await page.keyboard.type('echo $((6*7))'); await page.keyboard.press('Enter');
  check('typing on the keyboard reaches the shell and its answer is drawn', await until(/\n42\n|^42$/m));
  await page.keyboard.type('stty size'); await page.keyboard.press('Enter'); await wait(400);
  const size = await ev(() => rt.term.rows + ' ' + rt.term.cols);
  check('the program sees a terminal of exactly the screen size', (await screen()).includes(size), size + ' in ' + (await screen()).slice(-120));
  await page.setViewportSize({ width: 400, height: 560 }); await wait(500);
  check('a shorter window (the keyboard) resizes the terminal and tells the program', log.resizes.length > 0 && await ev(() => document.getElementById('rtOverlay').getBoundingClientRect().height === 560), JSON.stringify(log.resizes));
  await page.keyboard.type('stty size'); await page.keyboard.press('Enter'); await wait(400);
  const size2 = await ev(() => rt.term.rows + ' ' + rt.term.cols);
  check('and the program now sees the new size', size2 !== size && (await screen()).trimEnd().split('\n').some(l => l.trim() === size2), size2);
  await page.setViewportSize({ width: 400, height: 860 }); await wait(400);

  // extra keys
  await page.keyboard.type('sleep 30'); await page.keyboard.press('Enter'); await wait(300);
  await page.click('#rtCtrl'); await page.keyboard.type('c'); await wait(400);
  check('CTRL then c (extra key + keyboard) stops the running command', await until(/\^C/) && await ev(() => rt.ctrl === 0));
  await page.keyboard.type('echo recalled-1'); await page.keyboard.press('Enter'); await wait(200);
  await page.click('#rtKeys button[data-rk=up]'); await page.click('#rtKeys button[data-rk=enter]').catch(() => {}); await page.keyboard.press('Enter'); await wait(300);
  check('the up arrow key recalls the previous command', ((await screen()).match(/recalled-1/g) || []).length >= 3, (await screen()).slice(-200));
  await page.keyboard.type('echo ab'); await page.click('#rtKeys button[data-rk=left]'); await page.keyboard.type('X'); await page.keyboard.press('Enter'); await wait(300);
  check('the left arrow key moves the cursor in the line ', /\naXb\n/.test(await screen()), (await screen()).slice(-120));
  await page.keyboard.press('Control+u'); await wait(100);
  await page.keyboard.type('ech'); await page.click('#rtKeys button[data-rk=tab]'); await wait(300);
  check('the TAB key completes (ech is completed to a command name)', /echo/.test((await screen()).split('\n').filter(l => l.trim()).slice(-1)[0] || ''));
  await page.keyboard.press('Control+u'); await wait(100);
  check('the symbol row types its characters', await (async () => { await page.click('#rtSyms button[data-rk="|"]'); await page.click('#rtSyms button[data-rk="~"]'); await wait(200); const tail = (await screen()).split('\n').filter(l => l.trim()).slice(-2).join('\n'); if (!/\|~/.test(tail)) console.log('TAIL:' + JSON.stringify(tail)); return /\|~/.test(tail); })());
  await page.keyboard.press('Control+u'); await wait(100);
  await page.click('#rtCtrl'); await page.click('#rtCtrl');
  check('tapping CTRL twice locks it (marked), a third tap frees it', await ev(() => rt.ctrl === 2 && document.getElementById('rtCtrl').classList.contains('lock')) && (await page.click('#rtCtrl'), await ev(() => rt.ctrl === 0)));

  // a full-screen program
  await page.keyboard.type('vim -u NONE -N'); await page.keyboard.press('Enter');
  check('vim opens on the alternate screen', await (async () => { const t0 = Date.now(); while (Date.now() - t0 < 5000) { if (await ev(() => rt.term.buffer.active.type === 'alternate')) return true; await wait(50); } return false; })());
  await page.keyboard.type('ihello from vim'); await wait(200);
  check('what is typed in vim is drawn by it', /hello from vim/.test(await screen()));
  await page.click('#rtKeys button[data-rk=esc]'); await page.keyboard.type(':q!'); await page.keyboard.press('Enter'); await wait(500);
  check('ESC then :q! leaves vim and the shell screen is back', await ev(() => rt.term.buffer.active.type === 'normal') && /phone\$/.test(await screen()));

  // paste, copy
  await page.click('.rt-bar button:has-text("Paste")'); await wait(200);
  check('Paste types the clipboard into the terminal', /pasted-text/.test(await screen()));
  await page.keyboard.press('Control+u');
  await page.click('.rt-bar button:has-text("Copy")'); await wait(100);
  check('Copy puts the screen text on the clipboard', await ev(() => /echo \$\(\(6\*7\)\)/.test(window.__copied || '') && /42/.test(window.__copied)));
  const f0 = await ev(() => rt.term.options.fontSize);
  await page.click('.rt-bar button:has-text("A+")'); await wait(150);
  check('A+ makes the text bigger and remembers it', await ev(() => rt.term.options.fontSize === 15 && window.__kv.rt_font === '15') && f0 === 14);

  // hide and come back
  await page.click('.rt-x'); await wait(200);
  check('the X hides the screen and the session goes on', !(await ev(() => document.getElementById('rtOverlay').classList.contains('show'))) && Object.keys(procs).length === 1);
  await page.click('#txRealBtn'); await wait(400);
  check('opening it again shows the same session (the earlier output is still there)', /hello from vim|42/.test(await screen()) && log.starts.length === 1);
  await ev(() => { backNavigate(); }); await wait(200);
  check('Back closes the terminal screen first', !(await ev(() => document.getElementById('rtOverlay').classList.contains('show'))) && await ev(() => currentViewName() === 'terminal'));
  await page.click('#txRealBtn'); await wait(300);

  // exit and start again
  await ev(() => rt.term.focus());
  await page.keyboard.type('exit'); await page.keyboard.press('Enter'); await wait(600);
  check('exit ends the program and says how to start a new one', await until(/process ended \(0\).*press Enter/));
  await page.keyboard.press('Enter'); await wait(700);
  check('Enter starts a new shell', log.starts.length === 2 && await until(/phone\$/));
  await page.click('.rt-bar button:has-text("Restart")'); await wait(700);
  check('Restart ends the old session and starts another', log.closes.length >= 1 && log.starts.length === 3 && await until(/phone\$/));
  await ev(() => { document.getElementById('rtShell').value = 'termux'; rtPickShell('termux'); }); await wait(500);
  check('choosing another shell restarts with that backend', log.starts[log.starts.length - 1][0] === 'termux');
  // output is acknowledged (flow control)
  await ev(() => rt.term.focus());
  await page.keyboard.type('seq 1 3000'); await page.keyboard.press('Enter'); await wait(800);
  check('the screen tells the app what it has shown (flow control)', log.acks > 3, String(log.acks));
  check('a long output is all there in the scrollback', /\n3000\n/.test(await screen()) || /^3000$/m.test(await screen()));
  await page.screenshot({ path: 'terminal_real.png' });
  Object.values(procs).forEach(p => { try { p.kill(); } catch (e) {} });
  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
