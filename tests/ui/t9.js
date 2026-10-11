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
  const timingFor = cls => page.evaluate(c => { const d = document.createElement('div'); d.className = c; document.body.appendChild(d); const t = getComputedStyle(d).transitionTimingFunction; d.remove(); return t; }, cls);
  const timingForBarFill = () => page.evaluate(() => { const bar = document.createElement('div'); bar.className = 'backup-bar'; const fill = document.createElement('div'); bar.appendChild(fill); document.body.appendChild(bar); const t = getComputedStyle(fill).transitionTimingFunction; bar.remove(); return t; });
  // Fresh install, nothing saved yet: Expressive Animations defaults on
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  console.log('expressive animations, fresh install: on by default:', await page.evaluate(() => themeState.expressiveAnimations), '| html class:', await page.evaluate(() => document.documentElement.classList.contains('expressive-anim')), '| toggle checked:', await page.isChecked('#expressiveAnimToggle'));
  // Schedule
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
  // Expressive Animations
  console.log('expressive, on: a themed transition gets the bounce curve, a progress fill stays plain ease:', await timingFor('batch-bottom-sheet'), '|', await timingForBarFill());
  await page.emulateMedia({ reducedMotion: 'reduce' });
  console.log('the OS\'s own reduced-motion setting wins over it:', await timingFor('batch-bottom-sheet'));
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.locator('.switch-row:has(#expressiveAnimToggle)').click();
  console.log('turned off: the themed transition is back to its own curve, html class gone, toggle unchecked:', await timingFor('batch-bottom-sheet'), '|', await page.evaluate(() => document.documentElement.classList.contains('expressive-anim')), '|', await page.isChecked('#expressiveAnimToggle'));
  console.log('saved:', await page.evaluate(() => { const s = JSON.parse(window.__st.prefs); return JSON.stringify({ appearance: s.appearance, schedule: s.schedule, pureBlack: s.pureBlack, expressiveAnimations: s.expressiveAnimations }); }));
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
  await page.click('#modesModal >> text=Copy Fingerprint'); await page.click('#modesModal >> text=Regenerate Key'); await page.waitForTimeout(200);
  console.log('after regen:', await page.locator('#adbKeyFingerprint').innerText());
  console.log('calls:', JSON.stringify(await page.evaluate(() => window.__st.calls)));
  // Reload with saved prefs: schedule+pureBlack restored
  const p2 = await b.newPage();
  await p2.addInitScript(p => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return p; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return false; }, setSystemBarColor() {}, loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; } }; }, await page.evaluate(() => window.__st.prefs));
  await p2.goto(PAGE); await p2.waitForTimeout(300);
  console.log('restored:', await p2.evaluate(() => JSON.stringify({ appearance: themeState.appearance, pureBlack: themeState.pureBlack, expressiveAnimations: themeState.expressiveAnimations, schedule: themeState.schedule, bg: getComputedStyle(document.documentElement).getPropertyValue('--bg-base') })));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
