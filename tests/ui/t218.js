// v7.12.12 Connection Check card in Working Modes: the rows for a phone that is fine, one without Wi-Fi, one with Developer options or Wireless Debugging
// off, a saved Wireless Debugging port that no longer answers, Shizuku in each of its states and the secure-settings permission; each fix button calls the
// page function it names; the card opens with the window and "Check again" probes for real; it hides itself when the phone side has no getConnectionFacts;
// and at 320 px nothing sticks out sideways.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };

  const open = async (w, h, mode, facts, withFacts) => {
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(({ mode, facts, withFacts }) => {
      window.__calls = [];
      window.__mode = mode; window.__facts = facts;
      const br = { vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        loadPackages() { return '[]'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
        getWorkingMode() { window.__calls.push('getWorkingMode'); return JSON.stringify(window.__mode); },
        getWorkingModeFresh() { window.__calls.push('getWorkingModeFresh'); return JSON.stringify(window.__mode); },
        openDeveloperOptions() { window.__calls.push('openDeveloperOptions'); },
        openShizukuApp() { window.__calls.push('openShizukuApp'); },
        requestShizukuPermission() { window.__calls.push('requestShizukuPermission'); return 'requested'; },
        discoverWirelessDebugging() { window.__calls.push('discoverWirelessDebugging'); return JSON.stringify({ connect: [], pairing: [] }); },
        requestWriteSecureSettings() { window.__calls.push('requestWriteSecureSettings'); window.__facts.secureSettings = true; return 'granted'; },
        getAdbKeyInfo() { return JSON.stringify({ fingerprint: 'AA:BB', noticePending: false }); } };
      if (withFacts) br.getConnectionFacts = function () { window.__calls.push('getConnectionFacts'); return JSON.stringify(window.__facts); };
      window.AndroidBridge = br;
    }, { mode, facts, withFacts });
    await page.goto(PAGE); await page.waitForTimeout(450);
    await page.evaluate(() => openWorkingModesModal()); await page.waitForTimeout(250);
    return { page, errors };
  };
  const rows = page => page.evaluate(() => Array.from(document.querySelectorAll('#connCheckRows .cc-row')).map(r => ({
    id: r.dataset.cc, tag: r.querySelector('.mode-status-tag').textContent, cls: r.querySelector('.mode-status-tag').className.replace('mode-status-tag ', ''),
    text: r.querySelector('.cc-detail').textContent.replace(/\s+/g, ' ').trim(), btn: Array.from(r.querySelectorAll('button')).map(x => x.textContent) })));
  const head = page => page.evaluate(() => document.getElementById('connCheckTag').textContent);
  const by = (rs, id) => rs.find(r => r.id === id) || {};
  const good = { sdk: 34, wifiIp: '192.168.1.20', developerOptions: 1, usbDebugging: 1, wirelessDebugging: 1, secureSettings: true };
  const goodMode = { configuredMode: 'auto', activeMode: 'adb_wireless', modeAvailable: true, isPrivileged: true, adbTcp: { host: '127.0.0.1', port: 5555 }, adbWireless: { host: '192.168.1.20', port: 37099, connected: true }, shizuku: { installed: true, running: true, authorized: true, version: 13 } };

  // 1. a phone where everything is fine
  {
    const { page, errors } = await open(400, 860, goodMode, good, true);
    const rs = await rows(page);
    check('1. five rows, in the order Wi-Fi, Wireless Debugging switch, ADB connection, Shizuku, Secure settings access', rs.map(r => r.id).join() === 'wifi,wd,adb,shizuku,secure', rs.map(r => r.id).join());
    check('   all of them OK and the card says ALL GOOD', rs.every(r => r.tag === 'OK' && r.cls === 'active') && (await head(page)) === 'ALL GOOD', JSON.stringify(rs.map(r => r.tag)));
    check('   the Wi-Fi row shows the address and the ADB row the connected target', /192\.168\.1\.20$/.test(by(rs, 'wifi').text) && /Connected\. 192\.168\.1\.20:37099$/.test(by(rs, 'adb').text), by(rs, 'wifi').text + ' | ' + by(rs, 'adb').text);
    check('   no row offers a button', rs.every(r => r.btn.length === 0));
    check('   the card opened with the window (one quiet getWorkingMode, no fresh probe)', await page.evaluate(() => window.__calls.includes('getConnectionFacts') && !window.__calls.includes('getWorkingModeFresh')));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 2. no Wi-Fi, Wireless Debugging switched off, a saved port that does not answer, Shizuku installed but not running, nothing to grant with
  {
    const mode = { configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false, adbTcp: { host: '127.0.0.1', port: 5555 }, adbWireless: { host: '192.168.1.20', port: 41234, connected: false }, shizuku: { installed: true, running: false } };
    const { page, errors } = await open(400, 860, mode, { sdk: 34, wifiIp: '', developerOptions: 1, usbDebugging: 1, wirelessDebugging: 0, secureSettings: false }, true);
    const rs = await rows(page);
    check('2. no Wi-Fi: CHECK, in words', by(rs, 'wifi').tag === 'CHECK' && /Not on Wi-Fi/.test(by(rs, 'wifi').text), by(rs, 'wifi').text);
    check('   the switch is off: CHECK with an Open Developer Options button', by(rs, 'wd').tag === 'CHECK' && by(rs, 'wd').btn.join() === 'Open Developer Options', JSON.stringify(by(rs, 'wd')));
    check('   the saved port 41234 is named as not answering, with Auto-Detect Ports', by(rs, 'adb').tag === 'CHECK' && /41234$/.test(by(rs, 'adb').text) && /not answering/.test(by(rs, 'adb').text) && by(rs, 'adb').btn.join() === 'Auto-Detect Ports', JSON.stringify(by(rs, 'adb')));
    check('   Shizuku installed but not running: CHECK with Open Shizuku', by(rs, 'shizuku').tag === 'CHECK' && by(rs, 'shizuku').btn.join() === 'Open Shizuku', JSON.stringify(by(rs, 'shizuku')));
    check('   the permission without a working mode is INFO (nothing to grant with), no button', by(rs, 'secure').tag === 'INFO' && by(rs, 'secure').btn.length === 0, JSON.stringify(by(rs, 'secure')));
    check('   the card says NEEDS ATTENTION', (await head(page)) === 'NEEDS ATTENTION');
    // each fix button calls what it names
    const press = async (id) => { await page.evaluate((id) => document.querySelector('#connCheckRows [data-cc="' + id + '"] button').click(), id); await page.waitForTimeout(150); };
    await press('wd'); await press('adb'); await press('shizuku');
    const calls = await page.evaluate(() => window.__calls.filter(c => !/^get/.test(c)));
    check('   the three buttons reach Developer Options, the mDNS scan and the Shizuku app', calls.join() === 'openDeveloperOptions,discoverWirelessDebugging,openShizukuApp', calls.join());
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 3. Developer options off, an old Android, Wireless Debugging unreadable
  {
    let r = await open(400, 860, goodMode, { ...good, developerOptions: 0, wirelessDebugging: 0 }, true);
    let rs = await rows(r.page);
    check('3. Developer options off: the switch row says to turn them on first, with no button', by(rs, 'wd').tag === 'CHECK' && /Build number/.test(by(rs, 'wd').text) && by(rs, 'wd').btn.length === 0, by(rs, 'wd').text);
    await r.page.close();
    r = await open(400, 860, goodMode, { ...good, sdk: 29, wirelessDebugging: -1 }, true);
    rs = await rows(r.page);
    check('   Android 10: INFO "Needs Android 11 or newer."', by(rs, 'wd').tag === 'INFO' && /Android 11/.test(by(rs, 'wd').text), by(rs, 'wd').text);
    await r.page.close();
    r = await open(400, 860, goodMode, { ...good, wirelessDebugging: -1 }, true);
    rs = await rows(r.page);
    check('   a phone that does not say: INFO, not a false CHECK', by(rs, 'wd').tag === 'INFO', by(rs, 'wd').text);
    await r.page.close();
  }

  // 4. Shizuku in each state; the ADB port that is open but not connected
  {
    const cases = [
      ['running and authorized', { installed: true, running: true, authorized: true }, 'OK', []],
      ['running, not yet allowed', { installed: true, running: true, authorized: false }, 'CHECK', ['Authorize & Use Shizuku']],
      ['too old', { installed: true, running: true, preV11: true }, 'CHECK', ['Open Shizuku']],
      ['installed, not running', { installed: true, running: false }, 'CHECK', ['Open Shizuku']],
      ['not installed', {}, 'INFO', []],
    ];
    for (const [name, sz, tag, btn] of cases) {
      const r = await open(400, 860, { ...goodMode, shizuku: sz }, good, true);
      const x = by(await rows(r.page), 'shizuku');
      check(`4. Shizuku ${name}: ${tag}${btn.length ? ' with ' + btn[0] : ''}`, x.tag === tag && x.btn.join() === btn.join(), JSON.stringify(x));
      await r.page.close();
    }
    const r = await open(400, 860, { ...goodMode, adbWireless: {}, adbTcp: { host: '127.0.0.1', port: 5555, portOpen: true, connected: false } }, good, true);
    const x = by(await rows(r.page), 'adb');
    check('   port 5555 open but not connected: CHECK that names the port and the prompt on the phone', x.tag === 'CHECK' && /5555$/.test(x.text) && /Allow debugging/.test(x.text), x.text);
    await r.page.close();
    const r2 = await open(400, 860, { ...goodMode, adbWireless: {}, adbTcp: { port: 5555 } }, good, true);
    const y = by(await rows(r2.page), 'adb');
    check('   nothing saved and nothing open: INFO that says how to start', y.tag === 'INFO' && /Pair and connect/.test(y.text), y.text);
    await r2.page.close();
  }

  // 5. Grant now (a working mode is connected, the permission is not) and Check again
  {
    const { page, errors } = await open(400, 860, goodMode, { ...good, secureSettings: false }, true);
    let rs = await rows(page);
    check('5. with a working mode and no permission: CHECK with Grant now', by(rs, 'secure').tag === 'CHECK' && by(rs, 'secure').btn.join() === 'Grant now', JSON.stringify(by(rs, 'secure')));
    await page.evaluate(() => document.querySelector('#connCheckRows [data-cc="secure"] button').click()); await page.waitForTimeout(250);
    rs = await rows(page);
    const toast = await page.evaluate(() => document.getElementById('toastMsg').textContent);
    check('   Grant now asks the phone once, the row turns OK and a toast says so', by(rs, 'secure').tag === 'OK' && toast === 'Secure settings access granted.' && (await page.evaluate(() => window.__calls.filter(c => c === 'requestWriteSecureSettings').length)) === 1, toast);
    await page.evaluate(() => { window.__calls.length = 0; });
    await page.click('#connCheckCard .mode-action-btn:last-child'); await page.waitForTimeout(200);
    check('   Check again probes for real (getWorkingModeFresh) and reads the facts again', await page.evaluate(() => window.__calls.includes('getWorkingModeFresh') && window.__calls.includes('getConnectionFacts')));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 6. A phone side without getConnectionFacts (an older bridge) shows no card; no errors
  {
    const { page, errors } = await open(400, 860, goodMode, good, false);
    const shown = await page.evaluate(() => getComputedStyle(document.getElementById('connCheckCard')).display);
    check('6. without getConnectionFacts the card stays hidden', shown === 'none', shown);
    check('   and the rest of Working Modes still draws (the Automatic card is there)', await page.evaluate(() => !!document.getElementById('modeCardAuto') && document.getElementById('modeCardAuto').offsetHeight > 0));
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  // 7. a small screen: nothing sticks out sideways, every button and row fits inside the sheet
  {
    const mode = { configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false, adbTcp: { port: 5555 }, adbWireless: { host: '192.168.1.20', port: 41234, connected: false }, shizuku: { installed: true, running: true } };
    const { page } = await open(320, 640, mode, { sdk: 34, wifiIp: '', developerOptions: 1, usbDebugging: 1, wirelessDebugging: 0, secureSettings: false }, true);
    const g = await page.evaluate(() => {
      const sh = document.querySelector('#modesModal .modal-sheet').getBoundingClientRect();
      const over = Array.from(document.querySelectorAll('#connCheckCard *')).filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (r.right > sh.right + 1 || r.left < sh.left - 1); }).length;
      return { over, overflowX: document.documentElement.scrollWidth > window.innerWidth + 1 };
    });
    check('7. at 320 px no part of the card sticks out of the sheet and the page does not scroll sideways', g.over === 0 && !g.overflowX, JSON.stringify(g));
    await page.close();
  }

  // 8. in another language: the card, its rows, tags and buttons are translated at once (the real dictionaries), the address and the port are not
  {
    const mode = { configuredMode: 'auto', activeMode: 'unprivileged', modeAvailable: false, isPrivileged: false, adbTcp: { port: 5555 }, adbWireless: { host: '192.168.1.20', port: 41234, connected: false }, shizuku: { installed: true, running: false } };
    const { page, errors } = await open(400, 860, mode, { sdk: 34, wifiIp: '192.168.1.20', developerOptions: 1, usbDebugging: 1, wirelessDebugging: 0, secureSettings: false }, true);
    for (const [code, title, tag, fix, head] of [['de', 'ADB-Verbindung', 'PRÜFEN', 'Entwickleroptionen öffnen', 'HANDLUNG NÖTIG'], ['ja', 'ADB接続', '確認', '開発者向けオプションを開く', '要確認']]) {
      await page.evaluate(c => LANG_ENGINE.set(c), code); await page.waitForTimeout(900);
      const g = await page.evaluate(() => ({ card: document.querySelector('#connCheckCard .mode-card-title').textContent.trim(), head: document.getElementById('connCheckTag').textContent,
        titles: Array.from(document.querySelectorAll('#connCheckRows .cc-title')).map(e => e.textContent), tags: Array.from(document.querySelectorAll('#connCheckRows .mode-status-tag')).map(e => e.textContent),
        btns: Array.from(document.querySelectorAll('#connCheckRows button')).map(e => e.textContent), data: Array.from(document.querySelectorAll('#connCheckRows .cc-data')).map(e => e.textContent.trim()) }));
      check(`8. [${code}] the rows' titles, tags, header tag and buttons are in ${code}`, g.titles.includes(title) && g.tags.includes(tag) && g.head === head && g.btns.includes(fix), JSON.stringify(g));
      check(`   [${code}] the address and the port stay as they are`, g.data.join() === '192.168.1.20,41234', g.data.join());
    }
    check('   no page errors', errors.length === 0, errors.join(' | '));
    await page.close();
  }

  await b.close();
  console.log(failed ? failed + ' FAILED' : 'all passed');
  process.exit(failed ? 1 : 0);
})();
