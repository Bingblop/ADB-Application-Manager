// v5.8 terminal: command history (arrow keys + sheet), saved commands / scripts, pinned chips, persistence, Rish routing.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let confirmAnswer = true; const dialogs = [];
  page.on('dialog', async d => { dialogs.push(d.message()); await (confirmAnswer ? d.accept() : d.dismiss()); });
  const store = {};                                           // survives page reloads (lives in Node)
  await page.exposeFunction('__kvLoad', k => store[k] || '');
  await page.exposeFunction('__kvSave', (k, v) => { store[k] = v; });
  await page.addInitScript(() => {
    window.__calls = [];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      checkShizukuStatus() { return JSON.stringify({ authorized: true }); },
      loadSetting(k) { const v = window.__kv ? window.__kv[k] : ''; return v || ''; },
      saveSetting(k, v) { window.__kv = window.__kv || {}; window.__kv[k] = v; window.__kvSave(k, v); },
      executeShell(cmd) { window.__calls.push('exec:' + cmd); return 'out[' + cmd.split('\n')[0] + ']\n'; },
      selectWorkingMode(m) { return JSON.stringify({ ok: true, mode: m, message: 'ok' }); },
      rishStart() { setTimeout(() => window.onRishStarted({ ok: true, uid: 2000, host: 'husky', cwd: '/', prompt: 'husky:/ $' }), 20); return 'starting'; },
      rishRun(cmd, id) { window.__calls.push('rish:' + cmd); setTimeout(() => { window.onRishOutput(id, 'rish-out\n'); window.onRishDone(id, { exit: 0, cwd: '/', prompt: 'husky:/ $' }); }, 20); return 'ok'; },
      rishClose() {},
    };
  });
  // seed the store from Node before the page script reads it
  const boot = async () => {
    await page.addInitScript(kv => { window.__kv = kv; }, { ...store });
  };
  await boot();
  await page.goto(PAGE); await page.waitForTimeout(350);
  await page.evaluate(() => switchView('terminal')); await page.waitForTimeout(150);
  const sleep = ms => page.waitForTimeout(ms);
  const type = async c => { await page.fill('#termCmd', c); await page.press('#termCmd', 'Enter'); await sleep(60); await page.evaluate(() => { try { closeCommandResultsModal(); } catch (e) {} }); await sleep(40); };
  const calls = () => page.evaluate(() => window.__calls.slice());

  // 1) History is recorded (newest first, no duplicates) and persisted.
  await type('pm list packages -d'); await type('id'); await type('getprop ro.product.model'); await type('id');
  const h1 = await page.evaluate(() => termHistory.slice());
  console.log('1. history newest-first with the repeated command moved up:', JSON.stringify(h1) === JSON.stringify(['id', 'getprop ro.product.model', 'pm list packages -d']), JSON.stringify(h1));
  console.log('   persisted through the settings store:', JSON.parse(store.term_hist || '[]').join('|') === h1.join('|'));
  // 2) Arrow keys step through history.
  await page.focus('#termCmd');
  await page.press('#termCmd', 'ArrowUp'); const a1 = await page.inputValue('#termCmd');
  await page.press('#termCmd', 'ArrowUp'); const a2 = await page.inputValue('#termCmd');
  await page.press('#termCmd', 'ArrowDown'); const a3 = await page.inputValue('#termCmd');
  await page.press('#termCmd', 'ArrowDown'); const a4 = await page.inputValue('#termCmd');
  console.log('2. ArrowUp/ArrowDown walk the history and back to empty:', [a1, a2, a3, a4].join('|') === 'id|getprop ro.product.model|id|', [a1, a2, a3, a4].join('|'));

  // 3) The history sheet: pick, run, delete, filter, clear.
  await page.click('.term-icon-btn[title="Command history"]'); await sleep(150);
  const rows = await page.locator('#termModalBody .term-row').allInnerTexts();
  console.log('3. history sheet lists the commands:', rows.length === 3 && /^id/.test(rows[0]), JSON.stringify(rows.map(r => r.split('\n')[0])));
  await page.locator('#termModalBody .term-row').nth(1).locator('.term-row-main').click(); await sleep(100);
  console.log('   tapping a row puts it in the input and closes the sheet:', (await page.inputValue('#termCmd')) === 'getprop ro.product.model' && !(await page.evaluate(() => document.getElementById('termModal').classList.contains('show'))));
  await page.fill('#termCmd', '');
  await page.click('.term-icon-btn[title="Command history"]'); await sleep(120);
  const before = (await calls()).length;
  await page.locator('#termModalBody .term-row').nth(2).locator('.term-mini.run').click(); await sleep(150);
  await page.evaluate(() => { try { closeCommandResultsModal(); } catch (e) {} });
  const c3 = await calls();
  console.log('   ▶ runs it right away (and moves it to the top):', c3.length === before + 1 && c3[c3.length - 1] === 'exec:pm list packages -d' && (await page.evaluate(() => termHistory[0])) === 'pm list packages -d', c3[c3.length - 1]);
  await page.click('.term-icon-btn[title="Command history"]'); await sleep(120);
  await page.locator('#termModalBody .term-row').nth(1).locator('.term-mini[title="Remove"]').click(); await sleep(60);
  console.log('   ✕ removes one entry:', (await page.evaluate(() => termHistory.length)) === 2 && JSON.parse(store.term_hist).length === 2);
  await page.evaluate(() => { for (let i = 0; i < 10; i++) termRemember('cmd number ' + i); termRenderModal(); });
  const filterShown = await page.locator('#termHistFilter').count();
  await page.fill('#termHistFilter', 'number 7'); await sleep(80);
  const filtered = await page.locator('#termModalBody .term-row').count();
  console.log('   long histories get a filter box that narrows the list:', filterShown === 1 && filtered === 1);
  await page.fill('#termHistFilter', '');
  confirmAnswer = false;
  await page.locator('#termModalBody button', { hasText: 'Clear history' }).click(); await sleep(80);
  const kept = await page.evaluate(() => termHistory.length);
  confirmAnswer = true;
  await page.locator('#termModalBody button', { hasText: 'Clear history' }).click(); await sleep(80);
  console.log('   Clear history asks first, then empties it:', kept > 0 && (await page.evaluate(() => termHistory.length)) === 0 && /Commands you run show up here/.test(await page.locator('#termModalBody').innerText()), dialogs.slice(-2).join(' / '));
  await page.evaluate(() => termCloseModal());
  // history cap
  await page.evaluate(() => { for (let i = 0; i < 120; i++) termRemember('c' + i); });
  console.log('   history is capped at 80 entries, newest kept:', await page.evaluate(() => termHistory.length === 80 && termHistory[0] === 'c119' && termHistory[79] === 'c40'));
  await page.evaluate(() => { termHistory = []; kvSet('term_hist', []); });

  // 4) Saved commands: create from scratch, from history, edit, pin, run, delete.
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(120);
  console.log('4. saved sheet starts empty with a hint:', /Nothing saved yet/.test(await page.locator('#termModalBody').innerText()));
  await page.locator('#termModalBody button', { hasText: 'New saved command' }).click(); await sleep(80);
  await page.fill('#termEditName', 'Battery'); await page.fill('#termEditCmd', 'dumpsys battery | head -5');
  await page.locator('#termModalBody button', { hasText: 'Save' }).first().click(); await sleep(100);
  const saved1 = await page.evaluate(() => termSaved.slice());
  console.log('   a new saved command is stored and listed:', saved1.length === 1 && saved1[0].name === 'Battery' && saved1[0].cmd === 'dumpsys battery | head -5' && /Battery/.test(await page.locator('#termModalBody').innerText()) && JSON.parse(store.term_saved).length === 1);
  await page.locator('#termModalBody button', { hasText: 'New saved command' }).click(); await sleep(60);
  await page.fill('#termEditName', 'Two step'); await page.fill('#termEditCmd', 'cd /sdcard\nls | head -3\n');
  await page.locator('#termModalBody button', { hasText: 'Save' }).first().click(); await sleep(80);
  const multi = await page.evaluate(() => termSaved[1].cmd);
  console.log('   multi-line scripts keep their lines (trailing blanks trimmed):', multi === 'cd /sdcard\nls | head -3', JSON.stringify(multi));
  // blank command refused
  await page.locator('#termModalBody button', { hasText: 'New saved command' }).click(); await sleep(60);
  await page.locator('#termModalBody button', { hasText: 'Save' }).first().click(); await sleep(60);
  console.log('   an empty command is refused:', /Enter a command/.test(await page.locator('#toastMsg').innerText()) && (await page.evaluate(() => termSaved.length)) === 2);
  await page.locator('#termModalBody button', { hasText: 'Cancel' }).click(); await sleep(60);
  // edit
  await page.locator('#termModalBody .term-row').nth(0).locator('.term-mini[title="Edit"]').click(); await sleep(60);
  await page.fill('#termEditName', 'Battery (short)');
  await page.locator('#termModalBody button', { hasText: 'Save' }).first().click(); await sleep(60);
  console.log('   editing renames it in place:', (await page.evaluate(() => termSaved[0].name)) === 'Battery (short)' && (await page.evaluate(() => termSaved.length)) === 2);
  // pin -> chip in the terminal card
  await page.locator('#termModalBody .term-row').nth(0).locator('.term-mini[title="Pin as a chip"]').click(); await sleep(60);
  await page.evaluate(() => termCloseModal());
  const chip = await page.locator('#view-terminal .term-pin').allInnerTexts();
  console.log('   pinning adds a one-tap chip to the terminal card:', JSON.stringify(chip) === JSON.stringify(['📌 Battery (short)']), JSON.stringify(chip));
  const n0 = (await calls()).length;
  await page.locator('#view-terminal .term-pin').click(); await sleep(150);
  await page.evaluate(() => { try { closeCommandResultsModal(); } catch (e) {} });
  const c4 = await calls();
  console.log('   the chip runs the command:', c4.length === n0 + 1 && c4[c4.length - 1] === 'exec:dumpsys battery | head -5');
  // run a saved multi-line script
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(100);
  await page.locator('#termModalBody .term-row').nth(1).locator('.term-mini.run').click(); await sleep(150);
  await page.evaluate(() => { try { closeCommandResultsModal(); } catch (e) {} });
  const c5 = await calls();
  const echo = await page.locator('#termOutput').innerText();
  console.log('   a saved script runs as one multi-line command, echoed with > continuation:', c5[c5.length - 1] === 'exec:cd /sdcard\nls | head -3' && /\$ cd \/sdcard\n> ls \| head -3/.test(echo), JSON.stringify(echo.split('\n').slice(-4)));
  console.log('   multi-line scripts are not added to the history:', !(await page.evaluate(() => termHistory.some(c => c.includes('\n')))));
  // saving from the history
  await type('settings get global airplane_mode_on');
  await page.click('.term-icon-btn[title="Command history"]'); await sleep(100);
  await page.locator('#termModalBody .term-row').first().locator('.term-mini[title="Save"]').click(); await sleep(80);
  const prefill = await page.evaluate(() => ({ name: document.getElementById('termEditName').value, cmd: document.getElementById('termEditCmd').value, input: document.getElementById('termCmd').value }));
  console.log('   ⭐ on a history row opens the editor prefilled (input untouched):', prefill.cmd === 'settings get global airplane_mode_on' && prefill.name.startsWith('settings get') && prefill.input === '', JSON.stringify(prefill));
  await page.evaluate(() => termCloseModal());
  // delete
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(80);
  await page.locator('#termModalBody .term-row').nth(1).locator('.term-mini[title="Edit"]').click(); await sleep(60);
  await page.locator('#termModalBody button', { hasText: '🗑️' }).click(); await sleep(80);
  console.log('   delete asks first, then removes it (and its chip if pinned):', (await page.evaluate(() => termSaved.length)) === 1 && dialogs.slice(-1)[0].includes('Delete'));
  await page.evaluate(() => termCloseModal());

  // 5) Pin limit
  await page.evaluate(() => { termSaved = []; for (let i = 0; i < 7; i++) termSaved.push({ name: 's' + i, cmd: 'echo ' + i, pinned: false }); kvSet('term_saved', termSaved); });
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(80);
  for (let i = 0; i < 7; i++) { await page.locator('#termModalBody .term-row').nth(i).locator('.term-mini[title="Pin as a chip"]').click(); await sleep(30); }
  console.log('5. at most 6 pinned chips:', (await page.locator('#view-terminal .term-pin').count()) === 6 && /Up to 6 pinned/.test(await page.locator('#toastMsg').innerText()));
  await page.evaluate(() => termCloseModal());

  // 6) Everything survives a reload.
  await page.evaluate(() => { termSaved = [{ name: 'Keep me', cmd: 'echo kept', pinned: true }]; kvSet('term_saved', termSaved); termRemember('echo from history'); });
  await boot();
  await page.reload(); await sleep(400);
  await page.evaluate(() => switchView('terminal')); await sleep(150);
  const back = await page.evaluate(() => ({ hist: termHistory.slice(0, 2), saved: termSaved.map(x => x.name), chips: [...document.querySelectorAll('#view-terminal .term-pin')].map(c => c.textContent) }));
  console.log('6. history, saved commands and chips come back after a restart:', back.hist[0] === 'echo from history' && back.saved.join() === 'Keep me' && back.chips.join() === '📌 Keep me', JSON.stringify(back));

  // 7) HTML in names / commands is text.
  await page.evaluate(() => { termSaved = [{ name: '<img src=x onerror="window.__p=1">', cmd: '<b>echo</b> "q"', pinned: true }]; kvSet('term_saved', termSaved); termRenderPins(); });
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(100);
  const inj = await page.evaluate(() => ({ imgs: document.querySelectorAll('#termModalBody img, #view-terminal .term-pin img').length, p: window.__p || 0, text: document.getElementById('termModalBody').innerText }));
  console.log('7. markup in saved names/commands stays text:', inj.imgs === 0 && inj.p === 0 && /<img src=x/.test(inj.text) && /<b>echo<\/b>/.test(inj.text));
  await page.evaluate(() => termCloseModal());

  // 8) In Rish mode, history/saved commands go through the Rish shell.
  await page.locator('#rishBtn').click(); await sleep(400);
  await page.evaluate(() => { termSaved = [{ name: 'Who', cmd: 'id -u', pinned: true }]; termRenderPins(); });
  await page.locator('#view-terminal .term-pin').click(); await sleep(200);
  const rc = await calls();
  console.log('8. in Rish mode a pinned chip runs in the Rish shell:', rc[rc.length - 1] === 'rish:id -u');
  await page.click('.term-icon-btn[title="Saved commands"]'); await sleep(100);
  console.log('   the saved sheet says where commands run:', /Rish shell/.test(await page.locator('#termModalSub').innerText()));
  // 9) Back closes the sheet
  const handled = await page.evaluate(() => handleAndroidBack()); await sleep(100);
  console.log('9. Back closes the sheet:', handled === true && !(await page.evaluate(() => document.getElementById('termModal').classList.contains('show'))));
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
