// v7.12.12: SD Maid tab, Accessibility service card: what the last attempt ran into is said in a sentence for each reason (stuck on App info, Storage or Clear cache not found, Settings not
// in front, an app name that is not on the screen, App info refused, a locked system app, the screen off, the service on but not running), and a "Copy screen report" button next to it copies
// the screen report of the Settings page where the attempt stopped (what the phone sends back for the op "report"), with a help text that says what is in it; no failure, no button.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__calls = []; window.__toasts = [];
    const idle = t => ({ tool: t, state: 'idle', hasData: false, running: false });
    window.__st = { systemcleaner: idle('systemcleaner'), appcleaner: idle('appcleaner'), corpsefinder: idle('corpsefinder'), deduplicator: idle('deduplicator') };
    window.__acs = { enabled: true, connected: true, consent: true };
    window.__report = 'SD Maid accessibility automation: screen report\nPhone: Samsung SM-948U1, Android 17 (API 37)\nPlan: One UI   Step: Storage entry\nScreen: window com.android.settings [0,0][1440,2971], 12 nodes\n';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return window.__kv && window.__kv[k] ? window.__kv[k] : (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      copyToClipboard(t) { window.__copied = t; }, openUrl() {},
      sdm(tag, op, argsJson) {
        const a = JSON.parse(argsJson || '{}');
        window.__calls.push({ op, a });
        let r = { ok: true };
        if (op === 'state') r = { ok: true, tools: window.__st, running: 0, queued: 0 };
        else if (op === 'areas') r = { ok: true, areas: [{ area: 'SDCARD', label: 'Public storage', via: 'java', available: true }], mode: { name: 'adb_tcp', uid: 2000, privileged: true }, access: { storage: true, usage: true }, acs: window.__acs };
        else if (op === 'acs') { r = a.op === 'report' ? { ok: true, report: window.__noReport ? '' : window.__report } : Object.assign({ ok: true }, window.__acs); }
        setTimeout(() => window.onSdmReply({ tag, r }), 0);
      }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const text = sel => page.locator(sel).first().innerText();
  const setAcs = async o => { await ev(x => { window.__acs = x; return sdAcsRefresh(); }, o); await wait(150); };
  await ev(() => { switchView('sdm'); }); await wait(500);

  check('with nothing wrong there is no failure sentence and no report button', (await text('#sdAcsFail')) === '' && (await ev(() => !document.getElementById('sdAcsCopyBtn'))));

  const L = 'Samsung on-device Resource English (United States)';
  const cases = [
    ['NO_PROGRESS', { label: L }, /^Stuck on the App info page of Samsung on-device Resource English \(United States\): the Storage entry was found, but nothing that was tried \(a tap, a gesture, the keys\) opened it\.$/],
    ['STORAGE_NOT_FOUND', { label: L }, /^The Storage entry was not found on the App info page of Samsung/],
    ['CLEAR_NOT_FOUND', { label: L }, /^The Clear cache button was not found on the Storage page of Samsung/],
    ['SETTINGS_NOT_OPENED', { label: 'App A', window: 'com.avast.android.applocker' }, /^The Settings app did not come to the front for App A\. The window in front was com\.avast\.android\.applocker\.$/],
    ['SETTINGS_NOT_OPENED', { label: 'App A', window: '' }, /^The Settings app did not come to the front for App A, and no window could be read\.$/],
    ['APP_NOT_ON_SCREEN', { label: 'App A' }, /^The Settings app is open, but it does not show the name of App A, so nothing was tapped\.$/],
    ['LAUNCH_FAILED', { label: 'App A' }, /^Android refused to open the App info page of App A\.$/],
    ['LOCKED', { label: 'App A' }, /^App A cannot be cleared this way: Android locks its Clear cache button\.$/],
    ['NO_SETTINGS', { label: 'App A' }, /^App A has no settings page to tap through\.$/],
    ['DISABLED_APP', { label: 'App A' }, /^App A is turned off, so it has no App info page to tap through\.$/],
    ['SCREEN_UNAVAILABLE', { label: '' }, /^The screen turned off or was locked, so the automation stopped\.$/],
    ['GAVE_UP', { label: '' }, /^The automation gave up after several apps in a row failed\.$/],
    ['OTHER', { label: 'App A' }, /^The automation could not finish for App A\.$/]
  ];
  for (const [code, extra, re] of cases) {
    await setAcs({ enabled: true, connected: true, consent: true, lastFailure: Object.assign({ code, pkg: 'com.a', plan: 'One UI', step: 'Storage entry', hasReport: true }, extra) });
    const t = await text('#sdAcsFailMsg');
    check('failure ' + code + (extra.window === '' ? ' (no window)' : '') + ' is said in a sentence', re.test(t), t);
  }

  await setAcs({ enabled: true, connected: true, consent: true, lastFailure: { code: 'NO_PROGRESS', pkg: 'com.a', label: 'App A', hasReport: true } });
  check('the sentence is in the error colour (a note that is red)', await ev(() => document.getElementById('sdAcsFailMsg').classList.contains('is-err')));
  const help = await text('#sdAcsFail .sd-note:nth-of-type(2)');
  check('the help text says what is in the report and what is not', /names and places of the controls/.test(help) && /phone model and Android version/.test(help) && /no files, messages or contacts/.test(help), help);
  check('a "Copy screen report" button is next to it', (await text('#sdAcsCopyBtn')) === 'Copy screen report');
  await ev(() => { window.__calls.length = 0; document.getElementById('sdAcsCopyBtn').click(); }); await wait(250);
  check('it asks the phone for the report', (await ev(() => window.__calls.filter(c => c.op === 'acs' && c.a.op === 'report').length)) === 1);
  check('and copies it to the clipboard', (await ev(() => window.__copied)) === (await ev(() => window.__report)));
  await page.screenshot({ path: 'sdm_acs_fail.png' });
  check('with a toast', await ev(() => /Screen report copied/.test(document.body.innerText)));

  await ev(() => { window.__copied = null; window.__noReport = true; document.getElementById('sdAcsCopyBtn').click(); }); await wait(250);
  check('when the phone has no report there is a toast and nothing is copied', (await ev(() => window.__copied)) === null && await ev(() => /There is no screen report yet/.test(document.body.innerText)));

  await setAcs({ enabled: true, connected: true, consent: true, lastFailure: { code: 'GAVE_UP', pkg: '', label: '', hasReport: false } });
  check('a failure without a report has its sentence but no button', /gave up/.test(await text('#sdAcsFailMsg')) && await ev(() => !document.getElementById('sdAcsCopyBtn')));

  await setAcs({ enabled: true, connected: false, consent: true });
  check('service on but not running: the card says so and what to do', /switched on but Android is not running it/.test(await text('#sdAcsNote')) && /Turn it off and on again/.test(await text('#sdAcsNote')));
  await setAcs({ enabled: true, connected: true, consent: true });
  check('running: ready again, and the failure is gone', /^Ready/.test(await text('#sdAcsNote')) && (await text('#sdAcsFail')) === '');

  console.log('errors: ' + JSON.stringify(errors));
  await b.close();
  if (failed || errors.length) { console.log(failed + ' FAILED'); process.exit(1); }
})();
