// Connection Check: "Turn on Wireless Debugging" for a phone where the switch is off and this app can write it (a working mode is connected or the
// secure-settings permission is held). The button is the only way the app ever calls setWirelessDebugging; each answer of the phone side gets its own plain
// sentence; the row follows the phone's read-back; a phone with no way to write keeps the old "Open Developer Options" button.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };

  const open = async (mode, facts, answer) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 }, hasTouch: true });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(({ mode, facts, answer }) => {
      window.__calls = []; window.__facts = facts; window.__mode = mode;
      window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        getWorkingMode() { return JSON.stringify(window.__mode); }, getWorkingModeFresh() { return JSON.stringify(window.__mode); },
        getConnectionFacts() { return JSON.stringify(window.__facts); },
        openDeveloperOptions() { window.__calls.push('openDeveloperOptions'); },
        setWirelessDebugging(on) { window.__calls.push('setWirelessDebugging:' + on); if (answer === 'on') window.__facts.wirelessDebugging = 1; return answer; },
        getAdbKeyInfo() { return JSON.stringify({ fingerprint: 'AA:BB', noticePending: false }); } };
    }, { mode, facts, answer });
    await page.goto(PAGE); await page.waitForTimeout(450);
    await page.evaluate(() => openWorkingModesModal()); await page.waitForTimeout(250);
    return { page, errors };
  };
  const row = page => page.evaluate(() => { const r = document.querySelector('#connCheckRows [data-cc="wd"]'); return { tag: r.querySelector('.mode-status-tag').textContent, text: r.querySelector('.cc-detail').textContent.replace(/\s+/g, ' ').trim(), btn: Array.from(r.querySelectorAll('button')).map(x => x.textContent) }; });
  const toast = page => page.evaluate(() => document.getElementById('toastMsg').textContent);
  const press = async page => { await page.evaluate(() => document.querySelector('#connCheckRows [data-cc="wd"] button').click()); await page.waitForTimeout(200); };
  const priv = { configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true, adbTcp: { host: '127.0.0.1', port: 5555, connected: true }, adbWireless: {}, shizuku: {} };
  const plain = { ...priv, activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false, adbTcp: { host: '127.0.0.1', port: 5555 } };
  const off = { sdk: 34, wifiIp: '192.168.1.20', developerOptions: 1, usbDebugging: 1, wirelessDebugging: 0, secureSettings: false };

  // 1. a working mode is connected: the button is offered, says what it does, and turns the row OK
  {
    const { page, errors } = await open(priv, { ...off }, 'on');
    let r = await row(page);
    check('1. switch off with a working mode: CHECK with a Turn on button and the Wi-Fi/port warning in words', r.tag === 'CHECK' && r.btn.join() === 'Turn on Wireless Debugging' && /new port/.test(r.text) && /leave this Wi-Fi/.test(r.text), JSON.stringify(r));
    check('   nothing called the phone side before the press', await page.evaluate(() => window.__calls.length === 0));
    await press(page);
    r = await row(page);
    check('   one press, one setWirelessDebugging(true), the row reads OK from the read-back and no button is left', (await page.evaluate(() => window.__calls.join())) === 'setWirelessDebugging:true' && r.tag === 'OK' && r.btn.length === 0, JSON.stringify(r));
    check('   the toast names Auto-Detect Ports as the next step', /Auto-Detect Ports/.test(await toast(page)), await toast(page));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 2. the permission alone is enough, without a working mode
  {
    const { page } = await open(plain, { ...off, secureSettings: true }, 'on');
    const r = await row(page);
    check('2. the permission held, no mode: the Turn on button is offered', r.btn.join() === 'Turn on Wireless Debugging', JSON.stringify(r));
    await page.close();
  }

  // 3. every refusal has its own sentence and leaves the row on CHECK
  {
    const cases = [['no_wifi', /Connect to Wi-Fi first/], ['no_access', /working mode/], ['unsupported', /does not allow/], ['failed', /did not switch it on/], ['', /did not switch it on/]];
    for (const [answer, re] of cases) {
      const { page } = await open(priv, { ...off }, answer);
      await press(page);
      const t = await toast(page), r = await row(page);
      check(`3. answer "${answer || '(nothing)'}": ${re}`, re.test(t) && r.tag === 'CHECK' && r.btn.length === 1, t + ' | ' + r.tag);
      await page.close();
    }
  }

  // 4. no way to write: the old button, and the phone side is never called
  {
    const { page } = await open(plain, { ...off }, 'on');
    const r = await row(page);
    check('4. no mode and no permission: Open Developer Options, as before', r.btn.join() === 'Open Developer Options' && /Developer options/.test(r.text), JSON.stringify(r));
    await press(page);
    check('   the press opens Developer Options and never calls setWirelessDebugging', (await page.evaluate(() => window.__calls.join())) === 'openDeveloperOptions');
    await page.close();
  }

  await b.close();
  console.log(failed ? failed + ' FAILED' : 'all ok');
  process.exit(failed ? 1 : 0);
})();
