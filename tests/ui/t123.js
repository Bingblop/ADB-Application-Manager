// Hidden Settings: every setting says what it does and which values it takes. assets/hsinfo.js (made by tools/hsinfo/build.py) holds the text, the rows show
// it (what it does, the values with the current one in bold), the editor has an About box with what the value stands for now, a setting nobody describes gets a
// guess from its name and value, a setting documented as more than on / off is never a switch, and "How to read this list" explains the three tables.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const root = path.join(__dirname, '..', '..');
  // the file is complete and is what the curated texts say
  const code = fs.readFileSync(path.join(root, 'assets', 'hsinfo.js'), 'utf8');
  const sandbox = { window: {} };
  new Function('window', code)(sandbox.window);
  const info = sandbox.window.HS_INFO;
  const tables = ['global', 'secure', 'system'];
  const parsed = {};
  let problems = [];
  tables.forEach(ns => {
    parsed[ns] = new Map();
    String(info[ns]).split('\n').forEach((line, i) => {
      const f = line.split('|');
      if (f.length < 2 || f.length > 4 || !/^[a-z0-9_.:\-]+$/.test(f[0]) || !f[1].trim()) problems.push(ns + ':' + (i + 1) + ' ' + line.slice(0, 60));
      if (f[3] && !['conn', 'lock', 'break'].includes(f[3])) problems.push(ns + ':' + f[0] + ' risk ' + f[3]);
      if (parsed[ns].has(f[0])) problems.push(ns + ':' + f[0] + ' twice');
      parsed[ns].set(f[0], { t: f[1], v: f[2] || '', r: f[3] || '' });
    });
  });
  check('1. assets/hsinfo.js: every line is "name|what|values|risk", no name twice', problems.length === 0, problems.slice(0, 3).join(' ; '));
  check('   it describes Android\'s own settings: global 450+, secure 450+, system 100+', parsed.global.size >= 450 && parsed.secure.size >= 450 && parsed.system.size >= 100, tables.map(n => parsed[n].size).join('/'));
  let stale = [];
  tables.forEach(ns => fs.readFileSync(path.join(root, 'tools', 'hsinfo', 'curated', ns + '.txt'), 'utf8').split('\n').forEach(line => {
    if (!line.trim() || line.startsWith('#')) return;
    const f = line.split(' | ').map(x => x.trim());
    const got = parsed[ns].get(f[0]);
    if (!got || got.t !== f[1] || got.v !== f[2]) stale.push(ns + ':' + f[0]);
  }));
  check('   it holds every curated text (run tools/hsinfo/build.py after editing one)', stale.length === 0, stale.slice(0, 3).join(' '));
  const curatedHaveValues = ['adb_enabled', 'zen_mode', 'wifi_on', 'screen_off_timeout', 'ui_night_mode', 'navigation_mode', 'font_scale', 'private_dns_mode'].every(k => ['global', 'secure', 'system'].some(ns => parsed[ns].has(k) && parsed[ns].get(k).v));
  check('   the common settings all have values text', curatedHaveValues);

  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('dialog', d => d.accept());
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => {
    // settings no list describes
    window.__db.global.my_timeout_ms = '3000';
    window.__db.global['com.vendor.app.some_key'] = 'x';
    window.__db.global.vendor_feature_enabled = '1';
    window.__db.secure.sec_nothing_known = 'true';
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const find = async key => { await ev(() => sdbResetView()); await ev(k => { const i = document.getElementById('sdbSearch'); i.value = k; sdbSearchInput(); }, key); await sleep(280); };
  const rowHtml = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? r.innerHTML : null; }, key);
  const rowText = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? r.innerText : null; }, key);
  const hasSwitch = key => ev(k => { const r = Array.from(document.querySelectorAll('.sdb-row')).find(x => x.dataset.k === k); return r ? !!r.querySelector('.sdb-sw') : null; }, key);
  await ev(() => switchView('settings')); await sleep(900);

  check('2. the page loaded the descriptions when the tab opened', await ev(() => !!hsInfo && hsInfo.global.size > 400));
  await find('zen_mode');
  const zen = await rowText('zen_mode');
  check('   a row says what the setting does and its values', /Do Not Disturb/.test(zen) && /0 = off; 1 = priority interruptions only; 2 = total silence; 3 = alarms only/.test(zen) && (await ev(() => getComputedStyle(document.querySelector('.sdb-row[data-k="zen_mode"] .sdb-vals'), '::before').content)).indexOf('Values') >= 0, JSON.stringify(zen));
  check('   the value it holds now is in bold in the values line', await ev(() => { const r = document.querySelector('.sdb-row[data-k="zen_mode"] .sdb-cur'); return !!r && /^0 = off/.test(r.innerText); }));
  await page.screenshot({ path: 'hs_row.png' });
  check('   a setting with several values is not a switch (zen_mode, wifi_on), an on / off one is (airplane_mode_on, adb_enabled)',
    (await hasSwitch('zen_mode')) === false && (await (async () => { await find('wifi_on'); return hasSwitch('wifi_on'); })()) === false
      && (await (async () => { await find('airplane_mode_on'); return hasSwitch('airplane_mode_on'); })()) === true && (await (async () => { await find('adb_enabled'); return hasSwitch('adb_enabled'); })()) === true);
  await find('priority interruptions');
  check('   the search finds a setting by the words of its values', await ev(() => Array.from(document.querySelectorAll('.sdb-row')).some(r => r.dataset.k === 'zen_mode')));
  await find('screen_off_timeout');
  await ev(() => sdbSetNs('system')); await sleep(250); await find('screen_off_timeout');
  const sot = await rowText('screen_off_timeout');
  check('   system settings too: a time says milliseconds', /milliseconds/.test(sot), JSON.stringify(sot));

  // the editor
  await ev(() => sdbSetNs('global')); await sleep(250); await find('zen_mode');
  await page.locator('.sdb-row[data-k="zen_mode"]').click(); await sleep(250);
  const ed = await ev(() => ({ about: document.getElementById('sdbEditAbout').innerText, shown: document.getElementById('sdbEditAbout').style.display, chips: Array.from(document.querySelectorAll('#sdbEditQuick button')).map(x => x.innerText) }));
  check('3. the editor has an About box: what it does, the values, and what the value is now', ed.shown !== 'none' && /WHAT IT DOES/i.test(ed.about) && /Do Not Disturb/.test(ed.about) && /VALUES/i.test(ed.about) && /2 = total silence/.test(ed.about) && /Now: 0 = off/.test(ed.about), JSON.stringify(ed.about));
  check('   the documented values are buttons, with their meaning', ed.chips.some(c => /^0 · off/.test(c)) && ed.chips.some(c => /^3 · alarms only/.test(c)) && ed.chips.includes('(empty)'), JSON.stringify(ed.chips));
  await page.screenshot({ path: 'hs_editor.png' });
  await ev(() => { const t = document.getElementById('sdbEditText'); t.value = '2'; sdbEditInput(); });
  check('   typing a value updates what it stands for', await ev(() => /Now: 2 = total silence/.test(document.querySelector('#sdbEditAbout [data-now]').innerText)));
  await page.locator('#sdbEditQuick button', { hasText: /^3 · / }).click();
  check('   a value button fills the box', await ev(() => document.getElementById('sdbEditText').value === '3' && /Now: 3 = alarms only/.test(document.querySelector('#sdbEditAbout [data-now]').innerText)));
  await ev(() => sdbEditClose());

  // a setting nobody describes
  await find('my_timeout_ms'); await page.locator('.sdb-row[data-k="my_timeout_ms"]').click(); await sleep(200);
  const g1 = await ev(() => document.getElementById('sdbEditAbout').innerText);
  check('4. an undescribed setting says so and guesses from its name: a time in milliseconds', /WHAT IT PROBABLY IS/i.test(g1) && /does not describe this setting/.test(g1) && /milliseconds/.test(g1), JSON.stringify(g1));
  await ev(() => sdbEditClose());
  await find('vendor_feature_enabled'); await page.locator('.sdb-row[data-k="vendor_feature_enabled"]').click(); await sleep(200);
  check('   an _enabled name with 1 or 0 looks like a switch', /on\/off switch/.test(await ev(() => document.getElementById('sdbEditAbout').innerText)));
  await ev(() => sdbEditClose());
  await find('com.vendor.app'); await page.locator('.sdb-row[data-k="com.vendor.app.some_key"]').click(); await sleep(200);
  check('   a name that looks like a package says which app wrote it', /com\.vendor\.app/.test(await ev(() => document.getElementById('sdbEditAbout').innerText)));
  await ev(() => sdbEditClose());
  await ev(() => sdbSetNs('secure')); await sleep(250); await find('sec_nothing_known'); await page.locator('.sdb-row[data-k="sec_nothing_known"]').click(); await sleep(200);
  check('   a sec_ name says Samsung may have added it', /Samsung/.test(await ev(() => document.getElementById('sdbEditAbout').innerText)));
  await ev(() => sdbEditClose());
  await ev(() => sdbSetNs('global')); await sleep(200);

  // the risk marks of the short hints are still there
  await find('device_provisioned'); await page.locator('.sdb-row[data-k="device_provisioned"]').click(); await sleep(200);
  check('5. a careful setting still shows its warning, with the new text', (await ev(() => document.getElementById('sdbEditRisk').style.display)) !== 'none' && /leave this at 1/.test(await ev(() => document.getElementById('sdbEditSub').innerText)));
  await ev(() => sdbEditClose());

  // the explanation
  await ev(() => sdbResetView());
  await page.locator('#view-settings button', { hasText: 'How to read this list' }).click(); await sleep(200);
  const help = await ev(() => ({ shown: document.getElementById('sdbHelpModal').classList.contains('show'), text: document.getElementById('sdbHelpModal').innerText }));
  check('6. "How to read this list" opens an explanation of the three tables, a row and the values', help.shown && /The three tables/.test(help.text) && /Global/.test(help.text) && /Secure/.test(help.text) && /System/.test(help.text) && /milliseconds/.test(help.text) && /without a description/i.test(help.text), JSON.stringify(help.text.slice(0, 120)));
  await page.screenshot({ path: 'hs_help.png' });
  await ev(() => { window.__guide = null; window.openHelpGuide = id => { window.__guide = id; }; });
  await page.locator('#sdbHelpModal button', { hasText: 'Open the Help Guide topic' }).click(); await sleep(150);
  check('   its button opens the Help Guide at the Hidden Settings topic and closes the sheet', (await ev(() => window.__guide)) === 'tab-settings' && !(await ev(() => document.getElementById('sdbHelpModal').classList.contains('show'))));

  // the page still works when the file cannot be loaded: the short hints stay
  const page2 = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors2 = []; page2.on('pageerror', e => errors2.push(e.message));
  await page2.route('**/hsinfo.js', r => r.abort());
  await page2.addInitScript(mock.initScript);
  await page2.goto(PAGE); await page2.waitForTimeout(500);
  await page2.evaluate(() => switchView('settings')); await page2.waitForTimeout(800);
  check('7. without assets/hsinfo.js the tab still works with the short hints', await page2.evaluate(() => hsInfo === null && !!sdbHint('global', 'adb_enabled') && document.querySelectorAll('.sdb-row').length > 0) && errors2.length === 0, errors2.join(';'));

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
  await b.close();
})();
