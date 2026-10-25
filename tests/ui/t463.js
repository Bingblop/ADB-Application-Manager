// SDM-5: AppCleaner's Delete question and "Clear the rest with accessibility" count the accessibility service as usable when the phone can start or rebind it by itself (consent given and a working mode on), not only when it is connected; without consent or without a working mode they still say it is not ready.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 900 }, hasTouch: true });
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];

  async function open(privileged) {
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(priv => {
      window.__calls = [];
      window.__acs = { enabled: false, connected: false, consent: false };
      const T = k => 't463_' + k;
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {}, openUrl() {},
        loadSetting(k) { try { return localStorage.getItem(T(k)) || (k === 'perm_intro_v62' ? '1' : ''); } catch (e) { return ''; } }, saveSetting(k, v) { try { localStorage.setItem(T(k), v); } catch (e) {} },
        getWorkingMode() {
          return JSON.stringify(priv ? { adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }
            : { adbTcp: {}, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false });
        },
        scanFonts() { return 'started'; },
        sdm(tag, op, argsJson) {
          const a = JSON.parse(argsJson || '{}'); window.__calls.push({ op, a });
          let r = { ok: true };
          if (op === 'state') r = { ok: true, tools: { systemcleaner: { tool: 'systemcleaner', state: 'idle', hasData: false }, appcleaner: { tool: 'appcleaner', state: 'done', hasData: true, summary: { itemCount: 3, bytes: 3000000, primary: '3 items', secondary: '3 MB' } }, corpsefinder: { tool: 'corpsefinder', state: 'idle', hasData: false }, deduplicator: { tool: 'deduplicator', state: 'idle', hasData: false } }, running: 0, queued: 0 };
          else if (op === 'areas') r = { ok: true, areas: [], mode: priv ? { name: 'adb_tcp', uid: 2000, privileged: true } : { name: 'standard', uid: -1, privileged: false }, access: {} };
          else if (op === 'acs') r = Object.assign({ ok: true }, window.__acs);
          setTimeout(() => window.onSdmReply({ tag, r }), 0);
        }
      };
    }, privileged);
    await page.goto(PAGE); await page.waitForTimeout(600);
    await page.evaluate(() => switchView('sdm')); await page.waitForTimeout(400);
    return page;
  }

  // what Delete asks, and what "Clear the rest" does, for one state of the service
  async function probe(page, acs) {
    const ev = (fn, arg) => page.evaluate(fn, arg);
    await ev(s => { window.__acs = s; window.__calls.length = 0; }, acs);
    await ev(() => { sdDeleteTool('appcleaner'); }); await page.waitForTimeout(300);
    const del = await ev(() => document.getElementById('mpAskText').innerText);
    await ev(() => mpAskDone(false)); await page.waitForTimeout(100);
    await ev(() => { window.__calls.length = 0; });
    await page.click('#sdClearRest'); await page.waitForTimeout(300);
    const title = await ev(() => document.getElementById('mpAskTitle').innerText);
    await ev(() => mpAskDone(true)); await page.waitForTimeout(250);
    const rest = await ev(() => window.__calls.filter(c => c.op === 'delete').pop() || null);
    await ev(() => mpAskDone(false)); await page.waitForTimeout(100);
    return {
      clears: /cleared with the accessibility service/.test(del),
      stays: /not ready/.test(del) && /stay/.test(del),
      reason: (del.match(/not ready \(([^)]*)\)/) || [])[1] || '',
      title: title,
      onlyInaccessible: !!(rest && rest.a.tool === 'appcleaner' && rest.a.selection.options.onlyInaccessible === true && rest.a.selection.options.useAutomation === true)
    };
  }

  // ---- 1. a working mode is on: the phone starts or rebinds the service itself once the consent is given ----
  const p1 = await open(true);
  const rebind = await probe(p1, { enabled: true, connected: false, consent: true });
  console.log('working mode, switched on but not connected, consent: ' + JSON.stringify(rebind));
  check('1. switched on but not connected, with consent and a working mode: Delete says the caches are cleared with the service', rebind.clears && !rebind.stays);
  check('   "Clear the rest" asks to start and then deletes only the caches the files cannot reach', rebind.title === 'Clear the rest with accessibility?' && rebind.onlyInaccessible, rebind.title);
  const start = await probe(p1, { enabled: false, connected: false, consent: true });
  console.log('working mode, switched off, consent: ' + JSON.stringify(start));
  check('   switched off, with consent and a working mode: the same (the phone switches it on)', start.clears && !start.stays && start.title === 'Clear the rest with accessibility?' && start.onlyInaccessible);
  const noConsent = await probe(p1, { enabled: true, connected: false, consent: false });
  console.log('working mode, switched on but not connected, no consent: ' + JSON.stringify(noConsent));
  check('   without consent it is not ready, whatever the mode: the caches stay and "Clear the rest" deletes nothing', noConsent.stays && !noConsent.clears && noConsent.reason === 'no consent given' && noConsent.title === 'The accessibility service is not ready' && !noConsent.onlyInaccessible);
  const ready = await probe(p1, { enabled: true, connected: true, consent: true });
  check('   connected with consent: ready as before', ready.clears && !ready.stays && ready.title === 'Clear the rest with accessibility?' && ready.onlyInaccessible);
  await p1.close();

  // ---- 2. no working mode: the phone cannot start or rebind it, so a service that is not connected is not ready ----
  const p2 = await open(false);
  const noMode = await probe(p2, { enabled: true, connected: false, consent: true });
  console.log('no working mode, switched on but not connected, consent: ' + JSON.stringify(noMode));
  check('2. without a working mode, switched on but not connected: the caches stay, "Clear the rest" offers the settings', noMode.stays && !noMode.clears && noMode.reason === 'it is not connected' && noMode.title === 'The accessibility service is not ready' && !noMode.onlyInaccessible);
  const offNoMode = await probe(p2, { enabled: false, connected: false, consent: true });
  check('   switched off: not ready (it is off)', offNoMode.stays && offNoMode.reason === 'it is off' && !offNoMode.onlyInaccessible);
  const readyNoMode = await probe(p2, { enabled: true, connected: true, consent: true });
  check('   connected with consent: ready without a working mode too', readyNoMode.clears && readyNoMode.onlyInaccessible);
  await p2.close();

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
