// Appearance schedule (incl. past midnight), pure-black dark mode, ADB key card, prefs restored on relaunch
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    const st = { prefs: '{}', dark: true, notice: true, fp: '78:EF:B9:D4:94:44:F7:CC:80:9F:4E:D7:7F:29:E9:A5', calls: [] }; window.__st = st;
    window.confirm = () => true;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return st.prefs; }, savePreferences(p) { st.prefs = p; }, loadCustomLists() { return '[]'; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return st.dark; }, setSystemBarColor(c) { st.bar = c; }, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false }); },
      getAdbKeyInfo() { return JSON.stringify({ fingerprint: st.fp, noticePending: st.notice }); },
      dismissAdbKeyNotice() { st.notice = false; st.calls.push('dismiss'); },
      regenerateAdbKey() { st.calls.push('regen'); st.fp = 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99'; return JSON.stringify({ fingerprint: st.fp, noticePending: st.notice }); },
      copyToClipboard(t) { st.calls.push('copy:' + t); }, openDeveloperOptions() { st.calls.push('devopts'); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(1200);
  console.log('startup toast:', await page.locator('#toastMsg').innerText());
  const v = () => page.evaluate(() => { const c = getComputedStyle(document.documentElement); return ['--bg-base','--bg-card','--bg-sheet'].map(x => c.getPropertyValue(x).trim()).join(' ') + ' mode=' + document.documentElement.dataset.appearance + ' bar=' + window.__st.bar; });
  // Schedule
  await page.evaluate(() => switchView('colors')); await page.waitForTimeout(200);
  await page.click('.appearance-btn[data-appearance="schedule"]');
  console.log('schedule row visible:', await page.isVisible('#scheduleRow'), '|', await page.locator('#appearanceNote').innerText());
  const sm = await page.evaluate(() => [[6,59],[7,0],[12,0],[18,59],[19,0],[23,30]].map(([h,m]) => `${h}:${String(m).padStart(2,'0')}=${scheduledMode(new Date(2026,9,1,h,m))}`).join(' '));
  console.log('07:00-19:00 →', sm);
  await page.evaluate(() => { setScheduleTime('lightAt', '20:00'); setScheduleTime('darkAt', '06:00'); });
  const wrap = await page.evaluate(() => [[5,0],[6,0],[13,0],[21,0]].map(([h,m]) => `${h}:00=${scheduledMode(new Date(2026,9,1,h,m))}`).join(' '));
  console.log('20:00-06:00 (crosses midnight) →', wrap);
  await page.evaluate(() => { setScheduleTime('lightAt', '07:00'); setScheduleTime('darkAt', '19:00'); });
  // Pure black
  await page.click('.appearance-btn[data-appearance="dark"]');
  console.log('dark:', await v());
  await page.locator('.switch-row:has(#pureBlackToggle)').click();
  console.log('pure black:', await v(), '| toggle checked:', await page.isChecked('#pureBlackToggle'));
  await page.screenshot({ path: 'pureblack_colors.png' });
  await page.click('.appearance-btn[data-appearance="light"]');
  console.log('light (pure black ignored):', await v());
  console.log('saved:', await page.evaluate(() => { const s = JSON.parse(window.__st.prefs); return JSON.stringify({ appearance: s.appearance, schedule: s.schedule, pureBlack: s.pureBlack }); }));
  // Persist across reload
  await page.click('.appearance-btn[data-appearance="schedule"]');
  const prefs = await page.evaluate(() => window.__st.prefs);
  await page.evaluate(p => { localStorage.setItem('x', '1'); }, prefs);
  // ADB key card
  await page.click('.appearance-btn[data-appearance="dark"]');
  await page.evaluate(() => openWorkingModesModal()); await page.waitForTimeout(200);
  console.log('key fp:', await page.locator('#adbKeyFingerprint').innerText(), '| notice visible:', await page.isVisible('#adbKeyNotice'));
  await page.screenshot({ path: 'keycard.png' });
  await page.click('text=✓ Got it'); console.log('notice after dismiss:', await page.isVisible('#adbKeyNotice'));
  await page.click('text=📋 Copy Fingerprint'); await page.click('text=♻️ Regenerate Key'); await page.waitForTimeout(200);
  console.log('after regen:', await page.locator('#adbKeyFingerprint').innerText());
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  // Reload with saved prefs: schedule+pureBlack restored
  const p2 = await b.newPage();
  await p2.addInitScript(p => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return p; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return false; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; } }; }, await page.evaluate(() => window.__st.prefs));
  await p2.goto(PAGE); await p2.waitForTimeout(300);
  console.log('restored:', await p2.evaluate(() => JSON.stringify({ appearance: themeState.appearance, pureBlack: themeState.pureBlack, schedule: themeState.schedule, bg: getComputedStyle(document.documentElement).getPropertyValue('--bg-base') })));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
