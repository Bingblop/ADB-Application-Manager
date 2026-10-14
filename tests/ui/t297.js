// Connected Devices > Add > Wi-Fi: one `adb pair` at a time. Pair disables its button while adb runs (up to 40 s), but Enter in the pairing-code field
// called cdPair() again and started a second `adb pair` with the same code; the first answer then turned the button back on while the second still ran,
// and the log showed whichever answer came last. Now Enter (or anything else) while a pairing runs does nothing, and the next pairing works again.
// The mock adb below holds the pair answer until the script sends it.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    const st = window.__st = { pairs: [], held: [] };
    window.__answer = (out) => { const tag = st.held.shift(); if (tag) window.onCdResult({ tag, out, ms: 4 }); return !!tag; };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
      cdSelfSerials() { return '[]'; },
      cdAdb(tag, serial, argsJson) {
        const a = JSON.parse(argsJson);
        if (a[0] === 'pair') { st.pairs.push(a.join(' ')); st.held.push(tag); return; }
        setTimeout(() => window.onCdResult({ tag, out: a[0] === 'devices' ? 'List of devices attached\n' : '', ms: 4 }), 8);
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const pairs = () => ev(() => window.__st.pairs.slice());
  const log = () => ev(() => document.getElementById('cdAddLog').innerText);
  const btnOff = () => ev(() => document.getElementById('cdPairBtn').disabled);

  await ev(() => { switchView('devices'); cdAddOpen('wifi'); document.getElementById('cdPairAddr').value = '192.168.1.20:37215'; }); await wait(300);
  await page.fill('#cdPairCode', '123456');
  await page.click('#cdPairBtn'); await wait(100);
  check('Pair starts one adb pair and turns its button off', (await pairs()).length === 1 && await btnOff(), JSON.stringify(await pairs()));
  await page.focus('#cdPairCode'); await page.keyboard.press('Enter'); await wait(100);
  await page.keyboard.press('Enter'); await wait(100);
  check('Enter in the code field while it runs starts no second adb pair', (await pairs()).length === 1, JSON.stringify(await pairs()));
  await ev(() => { cdPair(); }); await wait(100);
  check('nor does another call of cdPair', (await pairs()).length === 1, JSON.stringify(await pairs()));

  await ev(() => window.__answer('Successfully paired to 192.168.1.20:37215 [guid=adb-AAA-bbb]')); await wait(150);
  check('the answer turns the button back on', !(await btnOff()));
  check('and the log shows that one answer', /Successfully paired/.test(await log()) && !(await ev(() => window.__st.held.length)), await log());

  // a later pairing works again
  await page.fill('#cdPairCode', '654321');
  await page.focus('#cdPairCode'); await page.keyboard.press('Enter'); await wait(100);
  check('after the answer, Enter in the code field pairs again', (await pairs()).length === 2 && (await pairs())[1] === 'pair 192.168.1.20:37215 654321' && await btnOff(), JSON.stringify(await pairs()));
  await ev(() => window.__answer('Failed: Wrong password or connection dropped')); await wait(150);
  check('and its answer frees it again', !(await btnOff()) && /Wrong password/.test(await log()), await log());

  console.log('errors:', JSON.stringify(errors));
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
  await b.close();
})();
