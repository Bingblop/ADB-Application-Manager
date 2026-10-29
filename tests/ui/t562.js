// v7.16 "Allow all" restarts the app: with a working mode the bridge grants by command and answers restarting:true once something changed (it left a watcher
// behind through the working mode that starts the app again once this process is gone). The page then shows "Restarting the app", closes the sheet, calls
// restartApp and does not reload the page or walk on to Android screens; when rows are left open (no command for them, or the phone refused), the first-launch
// sheet is not marked done, so it comes back after the restart with only those rows open. The one-time question does the same. Without the flag (an older build,
// or no watcher because the shell could not see the process) the page reloads itself, as before.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const DONE = { perm_intro_v62: '1', perm_adb_offer_v714: '1' };          // the first-launch sheet and the one-time question are both dealt with

(async () => {
  const b = await chromium.launch();
  // a working mode is on and "Allow all" grants everything by command, with the restart flag, unless the scenario says otherwise
  const open = async (opts, accept) => {
    const page = await b.newPage({ viewport: { width: 360, height: 800 } });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    page.on('dialog', d => (accept ? d.accept() : d.dismiss()).catch(() => {}));
    await page.addInitScript(inst.initScript, Object.assign({ kv: DONE, mode: { priv: true }, grantAll: true, restart: true }, opts || {}));
    await page.addInitScript(() => {
      // the first-launch check runs a little after opening: a scenario waits for it
      const st = window.setTimeout;
      window.__introRan = 0;
      window.setTimeout = function (fn, ms, ...rest) {
        if (typeof fn === 'function' && fn.name === 'maybeShowPermIntro') { const f = fn; fn = function () { try { return f.apply(this, arguments); } finally { window.__introRan++; } }; }
        return st.call(this, fn, ms, ...rest);
      };
    });
    await page.addInitScript(() => {
      // every toast from the very start: on a busy machine the one-time question (scenario 4) can run its course before the page is first looked at, so
      // wrapping showToast after the load missed it. The text showToast puts in #toastMsg is read instead (not the "Ready" the parser puts there).
      window.__toasts = [];
      new MutationObserver(recs => {
        const seen = new Set();
        for (const r of recs) {
          const el = r.target.nodeType === 3 ? r.target.parentNode : r.target;
          if (!el || el.id !== 'toastMsg' || seen.has(el) || document.readyState === 'loading') continue;
          seen.add(el);
          window.__toasts.push(el.textContent);
        }
      }).observe(document, { childList: true, subtree: true, characterData: true });
    });
    await page.goto(PAGE);
    await page.waitForFunction(() => typeof window.maybeShowPermIntro === 'function' && typeof window.permAllowAll === 'function');
    return page;
  };
  const scenario = async (opts, fn, accept) => { const page = await open(opts, accept); try { await fn(page); } finally { await page.close(); } };
  const ev = (page, fn, arg) => page.evaluate(fn, arg);
  const shown = page => ev(page, () => document.getElementById('permModal').classList.contains('show'));
  const calls = page => ev(page, () => ({ grantAll: window.__calls.grantAll || 0, restart: window.__calls.restart || 0, reload: window.__calls.reload || 0, perms: window.__calls.perms.join(','),
    saved: window.__calls.kv.filter(k => /^perm_intro_v62=/.test(k)).length, offer: window.__calls.kv.filter(k => /^perm_adb_offer_v714=/.test(k)).length }));
  const toasts = page => ev(page, () => window.__toasts.slice());
  const rows = page => ev(page, () => Array.from(document.querySelectorAll('#permRows .pm-row')).map(r => r.dataset.perm + (r.querySelector('.pm-ok') ? '=ok' : r.classList.contains('is-locked') ? '=locked' : '=open')).join(','));
  const settle = page => page.waitForTimeout(1000);          // longer than the 600 ms before restartApp and the 350 ms before reloadApp
  const intro = page => page.waitForFunction(() => window.__introRan >= 1);

  // 1) first launch, everything has a command: one grant call, the toast, the sheet closes, the app restarts (no page reload, no Android screen), nothing is left
  await scenario({ kv: {}, perm: { files: false, usage: false, overlay: false } }, async page => {
    await intro(page);
    check('1. first launch with a working mode: the sheet is up with its rows open', (await shown(page)) && /files=open/.test(await rows(page)), await rows(page));
    await page.click('#permAllBtn');
    await settle(page);
    const c = await calls(page), t = await toasts(page);
    check('   Allow all: one grant call, the toast says "Restarting the app", and the sheet closes', c.grantAll === 1 && t.includes('Restarting the app') && !(await shown(page)), JSON.stringify([c, t]));
    check('   the app is asked to restart (restartApp), the page is not reloaded, and no Android screen opens', c.restart === 1 && c.reload === 0 && c.perms === '', JSON.stringify(c));
    check('   no "Reloading the app" on top of it', !t.includes('Reloading the app'), JSON.stringify(t));
    check('   nothing is left: the first-launch sheet is marked done, and so is the one-time question', c.saved === 1 && c.offer === 1, JSON.stringify(c));
  });

  // 2) the phone refused one (or it has no command): the app still restarts, the walk does not start, and the first-launch sheet is not marked done
  await scenario({ kv: {}, perm: { files: false, usage: false, overlay: false }, grantAll: ['files', 'usage', 'restricted_settings', 'storage_legacy', 'secure_settings'] }, async page => {
    await intro(page);
    await page.click('#permAllBtn');
    await settle(page);
    const c = await calls(page);
    check('2. one row left open: the app restarts all the same (something was granted) and does not walk on to its Android screen', c.restart === 1 && c.reload === 0 && c.perms === '' && !(await shown(page)) && (await toasts(page)).includes('Restarting the app'), JSON.stringify(c));
    check('   the first-launch sheet is not marked done, so it comes back after the restart with what is left', c.saved === 0 && c.offer === 1, JSON.stringify(c));
  });
  // the fresh page after that restart: the sheet comes back, with only that row open
  await scenario({ kv: { perm_adb_offer_v714: '1' }, perm: { files: true, usage: true, overlay: false } }, async page => {
    await intro(page);
    const r = await rows(page);
    check('   after the restart the sheet is back, and only that row is open', (await shown(page)) && /overlay=open/.test(r) && !/files=open|usage=open/.test(r), r);
    check('   with one row left "Allow all" is away (its own Allow button is enough), as on any sheet', await ev(page, () => getComputedStyle(document.getElementById('permAllBtn')).display === 'none'));
  });

  // 3) the same sheet from About (manual), two rows open ("Allow all" is only offered with more than one): the toast, the sheet closes, a restart, no reload;
  // the first-launch flag is not touched
  await scenario({ perm: { files: true, usage: false, overlay: false } }, async page => {
    await ev(page, () => openPermSheet('manual'));
    check('3. from About, with two rows open', (await shown(page)) && /usage=open,overlay=open/.test(await rows(page)), await rows(page));
    await page.click('#permAllBtn');
    await settle(page);
    const c = await calls(page);
    check('   Allow all restarts the app the same way', c.grantAll === 1 && c.restart === 1 && c.reload === 0 && !(await shown(page)) && (await toasts(page)).includes('Restarting the app') && c.saved === 0, JSON.stringify(c));
  });

  // 4) the one-time question ("Grant all remaining permissions with it now? The app restarts afterwards."), answered yes: granted by command, then the same restart
  await scenario({ kv: { perm_intro_v62: '1' }, perm: { files: true, usage: true, overlay: false } }, async page => {
    await page.waitForFunction(() => (window.__calls.grantAll || 0) >= 1, null, { timeout: 15000 });
    await settle(page);
    const c = await calls(page);
    check('4. the one-time question, answered yes: one grant call, the toast, a restart, no page reload', c.grantAll === 1 && c.restart === 1 && c.reload === 0 && (await toasts(page)).includes('Restarting the app') && c.offer === 1, JSON.stringify(c));
  }, true);

  // 5) without the flag (no watcher could be left behind: the shell cannot see the process, or an older build): the page reloads itself, as before
  await scenario({ restart: false, perm: { files: true, usage: false, overlay: false } }, async page => {
    await ev(page, () => openPermSheet('manual'));
    await page.click('#permAllBtn');
    await settle(page);
    const c = await calls(page);
    check('5. without the flag: the sheet closes and the page reloads itself ("Reloading the app"), no restart', !(await shown(page)) && c.reload === 1 && c.restart === 0 && (await toasts(page)).includes('Reloading the app') && !(await toasts(page)).includes('Restarting the app'), JSON.stringify([c, await toasts(page)]));
  });
  // and the question, without the flag: a reload, as before
  await scenario({ kv: { perm_intro_v62: '1' }, restart: false, perm: { files: true, usage: true, overlay: false } }, async page => {
    await page.waitForFunction(() => (window.__calls.grantAll || 0) >= 1, null, { timeout: 15000 });
    await settle(page);
    const c = await calls(page);
    check('   the question without the flag: a page reload, no restart', c.grantAll === 1 && c.reload === 1 && c.restart === 0, JSON.stringify(c));
  }, true);

  // 6) nothing could be granted (the phone refused everything): no restart, no reload, the walk goes on as before
  await scenario({ grantAll: false, perm: { files: true, usage: false, overlay: false } }, async page => {
    await ev(page, () => openPermSheet('manual'));
    await page.click('#permAllBtn');
    await page.waitForFunction(() => window.__calls.perms.length >= 1);
    await settle(page);
    const c = await calls(page);
    check('6. nothing granted by command: no restart and no reload; the walk opens the Android screen for the first row left', c.grantAll === 1 && c.restart === 0 && c.reload === 0 && c.perms === 'usage' && (await shown(page)), JSON.stringify(c));
  });

  // 7) (T-041) the phone refused a row that only a working mode can grant (READ_LOGS): after the restart the working mode is not back yet when the
  // first-launch check runs (the adb server starts anew, Wireless debugging connects again), so the row shows a lock. That lock must not count as
  // "nothing left": the sheet comes back, and the row unlocks once the mode is seen
  let kvAfter = {};
  await scenario({ kv: {}, perm: { files: false, usage: false, read_logs: false }, grantAll: ['files', 'usage'] }, async page => {
    await intro(page);
    await page.click('#permAllBtn');
    await settle(page);
    const c = await calls(page);
    kvAfter = await ev(page, () => Object.assign({}, window.__kv));
    check('7. Allow all, the phone refuses Read Logs: a restart, the first-launch sheet is not marked done, and the app notes that rows were left', c.restart === 1 && c.saved === 0 && kvAfter.perm_intro_left === '1', JSON.stringify(c));
  });
  // the fresh page after that restart, with what the last one saved, and the working mode not back yet
  await scenario({ kv: kvAfter, mode: { priv: false }, perm: { read_logs: false } }, async page => {
    await intro(page);
    const r = await rows(page);
    check('   back after the restart before the working mode is: the sheet is up, Read Logs locked for now, the sheet not marked done', (await shown(page)) && /read_logs=locked/.test(r) && (await calls(page)).saved === 0, r);
    await ev(page, () => { window.__mode.priv = true; checkAllWorkingModes(false); });
    const r2 = await rows(page);
    check('   the working mode is back: Read Logs has its Allow button', /read_logs=open/.test(r2), r2);
    const up = await shown(page);
    if (up) await page.click('#permCloseBtn');
    check('   Not now: now the sheet is marked done, and the note is cleared', up && !(await shown(page)) && (await calls(page)).saved === 1 && !(await ev(page, () => window.__kv.perm_intro_left)), JSON.stringify(await calls(page)));
  });
  // a first launch (no restart before it) without a working mode, where only rows that need one are open: no sheet, and it is marked done, as before
  await scenario({ kv: { perm_adb_offer_v714: '1' }, mode: { priv: false }, perm: { read_logs: false } }, async page => {
    await intro(page);
    check('   a first launch without a working mode and only locked rows open: no sheet (nothing to allow), marked done, as before', !(await shown(page)) && (await calls(page)).saved === 1 && !(await ev(page, () => window.__calls.kv.some(k => /^perm_intro_left=/.test(k)))), JSON.stringify(await calls(page)));
  });

  await b.close();
  console.log(bad ? 'FAIL ' + bad + ' checks' : 'OK');
  process.exit(bad ? 1 : 0);
})();
