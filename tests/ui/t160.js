// Audit M-13: the Morphe signing key is exported through the Android share sheet, not written to Downloads. Both Export buttons (the KeyStore card of the Application
// Updater box and "Export the key" in the patcher settings) first ask, in words, what the file allows (whoever gets it can sign apps as you); a No does nothing; a Yes asks
// the app for the 'keyExport' operation once (the app opens the share sheet, so the page does not say "Saved to ..."); an error from the app is shown. Importing a key
// and making a new key are unchanged: Import asks for a file and a password, New key asks first.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = []; window.__keyErr = '';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ mode: 'adb_tcp', isPrivileged: true, status: 'connected', activeMode: 'adb_tcp', modeAvailable: true }); },
      getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, getAppDetails() { return '{}'; },
      morphe(tag, op, args) {
        const x = JSON.parse(args); window.__calls.push([op, x]);
        let r = { tag, ok: true, data: {} };
        if (op === 'keyExport') { if (window.__keyErr) r = { tag, ok: false, error: window.__keyErr }; else r.data = { shared: true }; }
        else if (op === 'pick') r.data = { files: [] };
        setTimeout(() => window.onMorphe && window.onMorphe(r), 5);
      }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(900);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });
  const ops = () => ev(() => window.__calls.map(c => c[0]));
  const asking = () => ev(() => document.getElementById('mpAskModal').classList.contains('show'));
  const ask = () => ev(() => ({ title: document.getElementById('mpAskTitle').textContent, text: document.getElementById('mpAskText').textContent, ok: document.getElementById('mpAskOk').textContent }));
  const toast = () => ev(() => { const t = document.getElementById('toast'); return t.classList.contains('show') ? document.getElementById('toastMsg').innerText : ''; });

  // ---- 1. the KeyStore card: the question comes first ----
  await page.click('.tab-btn[data-tab="installer"]'); await sleep(300); await page.click('#instSwitchUpdater'); await sleep(300);
  await ev(() => { window.__calls.length = 0; });
  await page.click('#usKeyExportBtn'); await sleep(150);
  const q = await ask();
  check('1. Export KeyStore opens a question before anything is exported, and no call has been made yet', await asking() && (await ops()).length === 0, JSON.stringify(await ops()));
  check('   the question says in plain words that whoever gets the file can sign apps as you and that Android will take them as updates', /^Share the signing key\?$/.test(q.title) && /Whoever gets this file can sign apps as you/.test(q.text) && /updates of the apps you patched/.test(q.text) && /only you can open/.test(q.text), JSON.stringify(q));
  check('   the button says Share (the share sheet comes next), not Save', q.ok === 'Share', q.ok);

  // ---- 2. No does nothing ----
  await page.click('#mpAskNo'); await sleep(150);
  check('2. Cancel closes the question and the app is not asked for anything', !(await asking()) && (await ops()).length === 0 && (await toast()) === '', JSON.stringify(await ops()));

  // ---- 3. Yes asks the app once; the page does not claim a save ----
  await page.click('#usKeyExportBtn'); await sleep(100);
  await page.click('#mpAskOk'); await sleep(300);
  const o3 = await ev(() => window.__calls.slice());
  check('3. Share asks the app for keyExport once, with no path and no file name from the page', o3.length === 1 && o3[0][0] === 'keyExport' && JSON.stringify(o3[0][1]) === '{}', JSON.stringify(o3));
  check('   no "Saved to" message (nothing was saved to Downloads) and no other file operation was asked for', (await toast()) === '' && !o3.some(c => /shareFile|copyToTree|patchedExport|patchedShare/.test(c[0])), await toast());

  // ---- 4. the app says there is no key yet ----
  await ev(() => { window.__calls.length = 0; window.__keyErr = 'There is no signing key yet: it is made the first time you patch.'; });
  await page.click('#usKeyExportBtn'); await sleep(100);
  await page.click('#mpAskOk'); await sleep(300);
  check('4. an error from the app is shown as it is', (await toast()) === 'There is no signing key yet: it is made the first time you patch.', await toast());
  await ev(() => { window.__keyErr = ''; document.getElementById('toast').classList.remove('show'); });

  // ---- 5. the same in the patcher settings ----
  await ev(() => { window.__calls.length = 0; mpSettingsOpen(); }); await sleep(250);
  const btns = await ev(() => [...document.querySelectorAll('#mpSheetBody .mp-row button')].map(x => x.innerText));
  check('5. the patcher settings still have Export the key, Import a key and New key', btns.join() === 'Export the key,Import a key,New key', btns.join());
  await page.click('#mpSheetBody .mp-row button:has-text("Export the key")'); await sleep(150);
  const q2 = await ask();
  check('   its Export asks the same question first (no call yet)', await asking() && /Whoever gets this file can sign apps as you/.test(q2.text) && (await ops()).length === 0, JSON.stringify(q2));
  await page.click('#mpAskNo'); await sleep(100);
  check('   No: still nothing asked of the app', (await ops()).length === 0);
  await page.click('#mpSheetBody .mp-row button:has-text("Export the key")'); await sleep(100);
  await page.click('#mpAskOk'); await sleep(300);
  check('   Yes: keyExport once', JSON.stringify(await ops()) === '["keyExport"]', JSON.stringify(await ops()));

  // ---- 6. import and a new key are unchanged ----
  await ev(() => { window.__calls.length = 0; });
  await page.click('#mpSheetBody .mp-row button:has-text("Import a key")'); await sleep(300);
  check('6. Import a key still asks the app for a file (the password question comes after a file is chosen)', JSON.stringify(await ops()) === '["pick"]' && !(await asking()), JSON.stringify(await ops()));
  await page.click('#mpSheetBody .mp-row button:has-text("New key")'); await sleep(150);
  const q3 = await ask();
  check('   New key still asks first, in its own words', await asking() && q3.title === 'Make a new signing key?' && q3.ok === 'New key' && !(await ops()).includes('keyReset'), JSON.stringify(q3));
  await page.click('#mpAskNo'); await sleep(100);
  check('   and a No makes no new key', !(await ops()).includes('keyReset'));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
