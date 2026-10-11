// v5.7 logcat: one row per entry, level colors, grouped stack traces, tappable color key, copy fidelity.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__copied = [];
    window.__logText = '';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku',
        adbTcp: { connected: false }, adbWireless: { connected: false }, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      getLogcat() { return window.__logText; },
      clearLogcat() { return 'cleared'; },
      copyToClipboard(t) { window.__copied.push(t); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  const SAMPLE = [
    '--------- beginning of main',
    '10-02 21:06:13.123  1234  5678 I ActivityManager: Start proc 1234:com.foo/u0a123 for service',
    '10-02 21:06:13.200   999   999 D WifiService: scan result ready',
    '10-02 21:06:13.250   999   999 V Chatty   : verbose noise',
    '10-02 21:06:13.300  2001  2001 W Choreographer: Skipped 42 frames!',
    '10-02 21:06:13.400  3333  3333 E AndroidRuntime: FATAL EXCEPTION: main',
    '10-02 21:06:13.400  3333  3333 E AndroidRuntime: java.lang.NullPointerException: boom',
    '10-02 21:06:13.400  3333  3333 E AndroidRuntime: \tat com.foo.Bar.baz(Bar.java:42)',
    '10-02 21:06:13.500  4444  4444 F libc    : Fatal signal 11 (SIGSEGV)',
    '--------- beginning of system',
    '10-02 21:06:13.600  1234  1300 I ActivityManager: Displayed com.foo/.Main: +350ms',
  ].join('\n');

  await page.evaluate(t => { window.__logText = t; }, SAMPLE);
  await page.evaluate(() => switchView('logcat')); await page.waitForTimeout(150);

  // 1) Entries are separated: grouped stack trace = 1 row, so 7 entry rows (not 9 lines).
  const rows = await page.locator('#logcatOutput .lc-row').count();
  const metas = await page.locator('#logcatOutput .lc-meta').count();
  console.log('1. 7 entry rows + 2 buffer separators:', rows === 7 && metas === 2, '| rows', rows, 'metas', metas);

  // 2) The 3-line exception is a single block.
  const trace = page.locator('#logcatOutput .lc-row.lvl-E').first();
  const traceLines = (await trace.locator('.lc-msg').innerText()).split('\n').length;
  const tag = await trace.locator('.lc-tag').innerText();
  console.log('2. stack trace grouped into one block (3 lines, tag AndroidRuntime):', traceLines === 3 && tag === 'AndroidRuntime');

  // 3) Level badges.
  const badges = await page.locator('#logcatOutput .lc-badge').allInnerTexts();
  console.log('3. badges in order:', JSON.stringify(badges) === JSON.stringify(['I', 'D', 'V', 'W', 'E', 'F', 'I']), JSON.stringify(badges));

  // 4) Colors per level (message color is mixed toward the theme text, so test dominant channel).
  // color-mix() computes to "color(srgb r g b)" with 0..1 floats; plain colors compute to "rgb(r, g, b)" 0..255.
  const col = async lvl => page.evaluate(l => {
    const el = document.querySelector('.lc-row.lvl-' + l + ' .lc-msg');
    const s = getComputedStyle(el).color;
    const m = s.match(/[\d.]+/g).map(Number).slice(0, 3);
    return s.startsWith('color(') ? m.map(v => Math.round(v * 255)) : m;
  }, lvl);
  const E = await col('E'), I = await col('I'), W = await col('W'), F = await col('F'), D = await col('D');
  console.log('4. E reddish:', E[0] > E[1] + 20 && E[0] > E[2] + 20, JSON.stringify(E));
  console.log('   I greenish:', I[1] > I[0] + 20 && I[1] > I[2], JSON.stringify(I));
  console.log('   W amber:', W[0] > W[2] + 40 && W[1] > W[2], JSON.stringify(W));
  console.log('   F magenta:', F[0] > F[1] + 20 && F[2] > F[1] + 20, JSON.stringify(F));
  console.log('   D blueish:', D[2] > D[0] + 20, JSON.stringify(D));

  // 5) Color key: six chips with counts (E counts the 3 stack-trace lines).
  const chips = await page.locator('#logcatKey .lc-chip').count();
  const eChip = await page.locator('#logcatKey .lc-chip[data-lvl="E"]').innerText();
  const iChip = await page.locator('#logcatKey .lc-chip[data-lvl="I"]').innerText();
  console.log('5. key has 6 chips; E=3, I=2:', chips === 6 && /Error/.test(eChip) && /3/.test(eChip) && /2/.test(iChip), '|', JSON.stringify(eChip), JSON.stringify(iChip));

  // 6) Tapping a chip hides that level; tapping again shows it.
  await page.locator('#logcatKey .lc-chip[data-lvl="E"]').click(); await page.waitForTimeout(60);
  const afterHide = await page.locator('#logcatOutput .lc-row.lvl-E').count();
  const off = await page.locator('#logcatKey .lc-chip[data-lvl="E"].off').count();
  console.log('6. tapping E hides error rows + chip dims:', afterHide === 0 && off === 1);

  // 7) Copy returns the ORIGINAL lines for visible levels only (E hidden -> no AndroidRuntime lines).
  await page.evaluate(() => logcatCopy()); await page.waitForTimeout(40);
  const copied = await page.evaluate(() => window.__copied[window.__copied.length - 1]);
  console.log('7. copy = raw threadtime lines w/o hidden level:',
    /^10-02 21:06:13\.123  1234  5678 I ActivityManager: Start proc/m.test(copied) && !/AndroidRuntime/.test(copied) && /beginning of main/.test(copied));
  await page.locator('#logcatKey .lc-chip[data-lvl="E"]').click(); await page.waitForTimeout(60);

  // 8) Filter text is highlighted.
  await page.fill('#logcatFilter', 'frames');
  await page.evaluate(() => logcatDraw(false)); await page.waitForTimeout(40);
  const hits = await page.locator('#logcatOutput mark.lc-hit').allInnerTexts();
  console.log('8. filter highlighted:', hits.length === 1 && hits[0].toLowerCase() === 'frames', JSON.stringify(hits));
  await page.fill('#logcatFilter', '');

  // 9) Bridge errors show as a plain notice, no rows, counts reset.
  await page.evaluate(() => { logcatClear(); window.__logText = 'Error: reading logcat needs ADB, Shizuku or Root.'; logcatFetch(false); });      // (since v7.12.0 an error does not wipe lines already shown: it is the notice only when there are none)
  await page.waitForTimeout(40);
  const errRows = await page.locator('#logcatOutput .lc-row').count();
  const notice = await page.locator('#logcatOutput .lc-meta').innerText();
  const eZero = await page.locator('#logcatKey .lc-chip[data-lvl="E"] .lc-n').innerText();
  console.log('9. error text -> notice only:', errRows === 0 && /needs ADB/.test(notice) && eZero === '0');

  // 10) Live updates don't yank the reader when scrolled up; refresh does jump to the newest line.
  const many = []; for (let i = 0; i < 300; i++) many.push(`10-02 21:07:${String(i % 60).padStart(2, '0')}.${String(100 + i).slice(-3)}  100  100 I Spam: line ${i}`);
  await page.evaluate(t => { window.__logText = t; logcatFetch(false); }, many.join('\n'));
  await page.waitForTimeout(60);
  const bottomAfterRefresh = await page.evaluate(() => { const el = document.getElementById('logcatOutput'); return el.scrollHeight - el.scrollTop - el.clientHeight < 5; });
  await page.evaluate(() => { document.getElementById('logcatOutput').scrollTop = 0; });
  await page.evaluate(() => logcatFetch(true)); await page.waitForTimeout(40);
  const topKept = await page.evaluate(() => document.getElementById('logcatOutput').scrollTop < 10);
  console.log('10. refresh jumps to newest:', bottomAfterRefresh, '| live update keeps reader position:', topKept);

  // 11) Clear leaves a notice.
  await page.evaluate(() => logcatClear()); await page.waitForTimeout(40);
  console.log('11. clear shows (cleared):', /\(cleared\)/.test(await page.locator('#logcatOutput').innerText()));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
