// v7.12.12 the Updates check: a real progress bar with "N of M apps" and the updates found so far, rows that appear as they are found, Cancel while it runs, a failure list ("Couldn't check: App — timed out",
// a host that is down named once) with "Retry failed (N)", never "Everything is up to date" when apps could not be checked, the last result there again after a tab switch or a restart (marked with its time),
// "Check again, ignore saved results", no internet, and the Check button never left off (an exception in the app, a broken message, Cancel)
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

const ROW = (pkg, name, extra) => Object.assign({ pkg, name, installedVersion: '1.0', installedCode: 10, availableVersion: '2.0', source: 'github', origin: 'obtainium-catalog', downloadUrl: 'https://github.com/x/' + pkg + '.apk', page: 'https://github.com/x/' + pkg }, extra || {});

function bridgeInit(opts) {
  const m = { calls: [], state: { running: false, checkedAt: 0, updates: [] }, hold: true, noExtras: !!opts.noExtras, throwOn: null };
  window.__m = m;
  window.confirm = () => true;
  const B = {
    vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return JSON.stringify({ manufacturer: 'samsung' }); }, isSystemDarkMode() { return false; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
    getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
    saveStore() {}, loadStore() { return ''; }, openUrl() {}, openObtainiumLink() {},
    getUpdateState() { return JSON.stringify(m.state); },
    checkForUpdates() { m.calls.push('check'); if (m.throwOn === 'check') throw new Error('bridge is gone'); },
    cancelUpdateCheck() { m.calls.push('cancel'); },
    recheckUpdates() { m.calls.push('recheck'); },
    retryFailedUpdates() { m.calls.push('retry'); },
    hasGithubToken() { return false; }, setGithubToken() { return true; }, getUpdateSources() { return '{}'; },
  };
  if (m.noExtras) { delete B.cancelUpdateCheck; delete B.recheckUpdates; delete B.retryFailedUpdates; }
  window.AndroidBridge = B;
}

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 900 } });
  const errors = []; page.on('pageerror', e => { bad++; errors.push(e.message); console.log('FAIL page error:', e.message); });
  await page.addInitScript(bridgeInit, { noExtras: false });
  await page.goto(PAGE); await page.waitForTimeout(300);
  await page.click('.tab-btn[data-tab="installer"]'); await page.click('#instSwitchUpdater'); await page.waitForTimeout(150);
  const vis = sel => page.evaluate(s => { const e = document.querySelector(s); return !!e && e.offsetParent !== null && getComputedStyle(e).display !== 'none'; }, sel);
  const dis = sel => page.evaluate(s => document.querySelector(s).disabled, sel);
  const text = sel => page.locator(sel).innerText();
  const status = () => text('#updStatus');
  const send = (fn, obj) => page.evaluate(([f, o]) => window[f](JSON.stringify(o)), [fn, obj]);
  const statusMatches = re => page.waitForFunction(r => new RegExp(r).test(document.getElementById('updStatus').innerText), re, { timeout: 8000 });
  const rows = () => page.locator('#updContainer .upd-row').evaluateAll(r => r.map(x => x.querySelector('.uad-name').innerText));

  // ---- 1. A check is running: progress bar, "N of M apps", updates found so far, Cancel
  check('the first open starts the check', (await page.evaluate(() => window.__m.calls)).join() === 'check');
  check('while it starts: Cancel and the bar show, the Check buttons are off', await vis('#updCancelBtn') && await vis('#updCheckBar') && await dis('#updCheckBtn') && await dis('#updRecheckBtn'), await status());
  const attrs = await page.evaluate(() => { const s = document.getElementById('updStatus'), b = document.getElementById('updCheckBar'); return [s.getAttribute('role'), s.getAttribute('aria-live'), s.getAttribute('aria-atomic'), b.getAttribute('aria-hidden')]; });
  check('the status line is a polite, atomic live region and the bar is decorative (aria-hidden)', attrs.join() === 'status,polite,true,true', attrs.join());
  await send('onUpdateCheckProgress', { done: 0, total: 120, phase: 'start', updates: 0, fromCache: 0 });
  await statusMatches('^0 of 120 apps');
  console.log('status at 0:', await status());
  await send('onUpdateCheckProgress', { done: 40, total: 120, phase: 'checking', updates: 2, fromCache: 10 });
  const w40 = await page.evaluate(() => document.querySelector('#updCheckBar > div').style.width);
  check('the bar moves at once (33%) though the announced text may wait', w40 === '33%', w40);
  await statusMatches('^40 of 120 apps');
  console.log('status at 40:', await status());
  // many reports in a row: the text changes at most every 2 seconds, the newest one wins
  const changes = await page.evaluate(() => new Promise(res => {
    const el = document.getElementById('updStatus'), seen = [];
    const mo = new MutationObserver(() => seen.push(el.innerText));
    mo.observe(el, { childList: true, characterData: true, subtree: true });
    let n = 40;
    const t = setInterval(() => { n++; window.onUpdateCheckProgress(JSON.stringify({ done: n, total: 120, phase: 'checking', updates: 2 })); if (n >= 70) { clearInterval(t); setTimeout(() => { mo.disconnect(); res(seen); }, 2300); } }, 50);
  }));
  check('30 progress reports in 1.5 s change the status text at most 2 times, and the last report is the one shown', changes.length <= 2 && /^70 of 120 apps/.test(changes[changes.length - 1] || ''), JSON.stringify(changes));
  const w = await page.evaluate(() => document.querySelector('#updCheckBar > div').style.width);
  check('the bar follows done / total (70 of 120 is 58%)', w === '58%', w);
  await send('onUpdateFound', ROW('com.a.one', 'Alpha'));
  await send('onUpdateFound', ROW('com.b.two', 'Beta', { source: 'galaxy', isSystem: true }));
  await page.waitForFunction(() => document.querySelectorAll('#updContainer .upd-row').length === 2);
  console.log('rows while it runs:', (await rows()).join(', '), '| still running:', await vis('#updCancelBtn'));
  await send('onUpdateFound', ROW('com.a.one', 'Alpha', { availableVersion: '2.1' }));
  await send('onUpdateFound', ROW('com.me', 'Me', { source: 'self' }));
  await page.waitForTimeout(250);
  check('an update found twice is one row, and this app\'s own update does not join the list', (await rows()).length === 2 && (await page.locator('#updContainer').innerText()).includes('2.1'));
  await send('onUpdateCheckProgress', { done: 71, total: 120, phase: 'checking', updates: 1, fromCache: 10 });
  await statusMatches('^71 of 120 apps');
  console.log('one found so far:', await status());
  await page.screenshot({ path: 'running.png' });

  // ---- 2. Cancel
  await page.click('#updCancelBtn');
  check('Cancel goes to the app', (await page.evaluate(() => window.__m.calls)).join() === 'check,cancel', await status());
  check('...and the button is off while the app stops', await dis('#updCancelBtn'));
  await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha'), ROW('com.b.two', 'Beta', { source: 'galaxy', isSystem: true })], untracked: [], external: [], failed: [], hostsDown: [], cancelled: true, total: 120, notChecked: 70, checked: 10, openSourceChecked: 40, fromCache: 10, checkedAt: Date.now() });
  console.log('after Cancel:', await status());
  check('after Cancel the Check button is back and Cancel is gone', !(await dis('#updCheckBtn')) && !(await dis('#updRecheckBtn')) && !(await vis('#updCancelBtn')) && !(await vis('#updCheckBar')));
  check('what was found stays, and it is not called up to date', (await rows()).length === 2 && !(await page.locator('#updContainer').innerText()).includes('Everything is up to date'));
  check('no failure list after Cancel (nothing failed), no Retry', !(await vis('#updRetryBtn')) && (await page.locator('#updExtraContainer').innerText()).indexOf('Couldn’t check') < 0);
  console.log('a cancelled check with nothing found:');
  await page.click('#updCheckBtn'); await send('onUpdatesChecked', { updates: [], failed: [], hostsDown: [], cancelled: true, total: 120, notChecked: 120, checkedAt: Date.now() });
  console.log('  ', await status(), '|', await text('#updContainer'));

  // ---- 3. Finished with failures: a host down (named once), apps that timed out; Retry failed
  await page.click('#updCheckBtn');
  const failed = [
    { pkg: 'p.one', name: 'Zed', reason: 'apt.izzysoft.de unreachable', code: 'host', host: 'apt.izzysoft.de', lane: 'os' },
    { pkg: 'p.two', name: 'Yak', reason: 'apt.izzysoft.de unreachable', code: 'host', host: 'apt.izzysoft.de', lane: 'os' },
    { pkg: 'p.three', name: 'Slow App', reason: 'timed out', code: 'deadline', lane: 'os' },
    { pkg: 'p.four', name: 'Odd App', reason: 'HTTP 500', code: 'http', lane: 'os' },
    { pkg: 'p.five', name: 'Samsung Thing', reason: 'connection failed', code: 'io', lane: 'gx' },
  ];
  await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha')], untracked: [{ pkg: 'u.one', name: 'Mystery', installedVersion: '1' }], external: [], failed, hostsDown: ['apt.izzysoft.de'], cancelled: false, timedOut: 1, total: 120, notChecked: 0, fromCache: 30, checkedAt: Date.now() });
  console.log('finished with failures:', await status());
  console.log('empty-state text and list:', (await rows()).join(','), '| container:', (await text('#updContainer')).replace(/\s+/g, ' ').slice(0, 60));
  const extras = (await text('#updExtraContainer')).split('\n').map(x => x.trim()).filter(Boolean);
  console.log('failure section:\n   ' + extras.slice(0, 14).join('\n   '));
  check('the host that is down is named once, not once per app', extras.filter(x => /apt\.izzysoft\.de unreachable/.test(x)).length === 1);
  check('each other app has its own line: "Couldn’t check: App — reason"', extras.includes('Couldn’t check: Slow App — timed out') && extras.includes('Couldn’t check: Odd App — HTTP 500') && extras.includes('Couldn’t check: Samsung Thing — connection failed'));
  check('"Retry failed (5)" is there and on', (await text('#updRetryBtn')) === 'Retry failed (5)' && await vis('#updRetryBtn') && !(await dis('#updRetryBtn')));
  check('the page does not say everything is up to date', !(await page.locator('body').innerText()).includes('Everything is up to date'));
  await page.screenshot({ path: 'failures.png', fullPage: true });
  await page.click('#updRetryBtn');
  check('Retry failed goes to the app (and only that)', (await page.evaluate(() => window.__m.calls)).slice(-1)[0] === 'retry', (await page.evaluate(() => window.__m.calls)).join());
  check('while it retries: the list of updates stays, Cancel shows', (await rows()).length === 1 && await vis('#updCancelBtn') && !(await vis('#updRetryBtn')) && (await status()).startsWith('Starting'), await status());
  await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha'), ROW('com.c.three', 'Gamma')], untracked: [{ pkg: 'u.one', name: 'Mystery', installedVersion: '1' }], external: [], failed: [{ pkg: 'p.three', name: 'Slow App', reason: 'timed out', code: 'deadline', lane: 'os' }], hostsDown: [], total: 120, notChecked: 0, checkedAt: Date.now() });
  console.log('after the retry:', await status());
  check('one still failed: "Retry failed (1)", and still no "up to date"', (await text('#updRetryBtn')) === 'Retry failed (1)' && !(await page.locator('body').innerText()).includes('Everything is up to date'));
  await page.click('#updRetryBtn');
  await send('onUpdatesChecked', { updates: [], untracked: [], external: [], failed: [], hostsDown: [], total: 1, notChecked: 0, checkedAt: Date.now() });
  console.log('second retry, nothing left to say:', await status(), '|', await text('#updContainer'));

  // ---- 4. A clean check says so
  await page.click('#updCheckBtn');
  await send('onUpdatesChecked', { updates: [], untracked: [], external: [], failed: [], hostsDown: [], cancelled: false, offline: false, total: 120, notChecked: 0, fromCache: 118, checkedAt: Date.now() });
  console.log('clean:', await status(), '|', await text('#updContainer'));
  check('a clean check: "Everything is up to date." and no Retry, no failure list', (await text('#updContainer')) === 'Everything is up to date.' && !(await vis('#updRetryBtn')) && (await text('#updExtraContainer')).trim() === '');

  // ---- 5. No internet
  await page.click('#updCheckBtn');
  await send('onUpdatesChecked', { updates: [], untracked: [], external: [], failed: [{ pkg: 'p.x', name: 'X', reason: 'no internet', code: 'offline', lane: 'os' }], hostsDown: [], offline: true, total: 1, notChecked: 0, checkedAt: Date.now() });
  console.log('no internet:', await status(), '|', await text('#updContainer'));
  console.log('no internet, extras:', (await text('#updExtraContainer')).split('\n').map(x => x.trim()).filter(Boolean).join(' | '));
  check('no internet is said in words and nothing is called up to date', !(await page.locator('body').innerText()).includes('Everything is up to date') && (await status()).includes('No internet connection'));

  // ---- 6. The Check button is never left off
  await page.evaluate(() => { window.__m.throwOn = 'check'; });
  await page.click('#updCheckBtn');
  check('the bridge throws when the check starts: the buttons come back', !(await dis('#updCheckBtn')) && !(await vis('#updCancelBtn')), await status());
  await page.evaluate(() => { window.__m.throwOn = null; });
  await page.click('#updCheckBtn');
  check('running again', await dis('#updCheckBtn'));
  await page.evaluate(() => window.onUpdatesChecked('this is not json'));
  check('a broken final message: the buttons come back', !(await dis('#updCheckBtn')) && !(await vis('#updCancelBtn')), await status());
  await page.click('#updCheckBtn');
  await page.evaluate(() => window.onUpdatesChecked(JSON.stringify({ error: 'boom', updates: [], failed: [], hostsDown: [] })));
  check('an error summary from the app: the buttons come back and it says so', !(await dis('#updCheckBtn')) && (await status()).includes('boom'), await status());
  await page.click('#updCheckBtn');
  await page.evaluate(() => { window.renderUpdateExtras = () => { throw new Error('render broke'); }; });
  let threw = false;
  try { await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha')], failed: [], hostsDown: [], total: 5 }); } catch (e) { threw = true; }
  check('even if drawing the list throws, the buttons are already back', !(await dis('#updCheckBtn')) && !(await vis('#updCancelBtn')), threw);
  await page.reload(); await page.waitForTimeout(300);
  errors.length = 0;

  // ---- 7. Check again, ignore saved results; the normal Check button is the normal check
  await page.click('.tab-btn[data-tab="installer"]'); await page.click('#instSwitchUpdater'); await page.waitForTimeout(100);
  await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha')], failed: [], hostsDown: [], total: 20, notChecked: 0, fromCache: 20, checkedAt: Date.now() });
  console.log('all from saved results:', await status());
  const before = await page.evaluate(() => window.__m.calls.length);
  await page.click('#updRecheckBtn');
  check('"Check again, ignore saved results" asks the app to ignore them', (await page.evaluate(() => window.__m.calls)).slice(before).join() === 'recheck', await text('#updRecheckBtn'));
  check('...while it runs the three Check buttons are off', await dis('#updCheckBtn') && await dis('#updRecheckBtn'));
  await send('onUpdatesChecked', { updates: [ROW('com.a.one', 'Alpha')], failed: [], hostsDown: [], total: 20, notChecked: 0, fromCache: 0, checkedAt: Date.now() });
  await page.click('#updCheckBtn');
  check('the normal button is the normal check', (await page.evaluate(() => window.__m.calls)).slice(-1)[0] === 'check');
  await send('onUpdatesChecked', { updates: [], failed: [], hostsDown: [], total: 2, checkedAt: Date.now() });

  // ---- 8. The last result is there again after a restart: shown at once, marked with its time, and not checked again by itself
  const savedAt = Date.now() - 3 * 3600 * 1000;
  const saved = { running: false, checkedAt: savedAt, updates: [ROW('com.a.one', 'Alpha'), ROW('com.b.two', 'Beta', { source: 'galaxy', isSystem: true })], untracked: [{ pkg: 'u.one', name: 'Mystery', installedVersion: '1' }], external: [],
    failed: [{ pkg: 'p.three', name: 'Slow App', reason: 'timed out', code: 'deadline', lane: 'os' }], hostsDown: [], total: 120, notChecked: 0, fromCache: 0, checked: 20, openSourceChecked: 99, errors: 0, openSourceErrors: 1 };
  const p2 = await b.newPage({ viewport: { width: 360, height: 900 } });
  p2.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await p2.addInitScript(bridgeInit, { noExtras: false });
  await p2.addInitScript(s => { window.__m.state = s; }, saved);
  await p2.goto(PAGE); await p2.waitForTimeout(300);
  await p2.click('.tab-btn[data-tab="installer"]'); await p2.click('#instSwitchUpdater'); await p2.waitForTimeout(200);
  check('a saved result: no check starts by itself', (await p2.evaluate(() => window.__m.calls)).length === 0);
  console.log('saved result:', await p2.locator('#updStatus').innerText());
  console.log('saved rows:', (await p2.locator('#updContainer .upd-row .uad-name').allInnerTexts()).join(', '), '| not tracked:', (await p2.locator('#updExtraContainer').innerText()).includes('Not tracked (1)'), '| failures:', (await p2.locator('#updExtraContainer').innerText()).includes('Couldn’t check: Slow App — timed out'));
  check('the saved view offers the Check button, Check again and Retry failed (1)', !(await p2.evaluate(() => document.getElementById('updCheckBtn').disabled)) && (await p2.locator('#updRetryBtn').innerText()) === 'Retry failed (1)');
  await p2.screenshot({ path: 'saved.png', fullPage: true });
  // a check that is still running when the tab is opened again
  const p3 = await b.newPage({ viewport: { width: 360, height: 900 } });
  await p3.addInitScript(bridgeInit, { noExtras: false });
  await p3.addInitScript(() => { window.__m.state = { running: true, checkedAt: 0, updates: [{ pkg: 'com.a.one', name: 'Alpha', installedVersion: '1.0', availableVersion: '2.0', source: 'github', downloadUrl: 'https://github.com/x/a.apk', page: 'https://github.com/x/a' }], progress: { done: 60, total: 120, phase: 'checking', updates: 1 } }; });
  await p3.goto(PAGE); await p3.waitForTimeout(300);
  await p3.click('.tab-btn[data-tab="installer"]'); await p3.click('#instSwitchUpdater'); await p3.waitForTimeout(200);
  console.log('tab opened during a check:', await p3.locator('#updStatus').innerText(), '| cancel:', await p3.evaluate(() => getComputedStyle(document.getElementById('updCancelBtn')).display), '| rows:', await p3.locator('#updContainer .upd-row').count(), '| calls:', JSON.stringify(await p3.evaluate(() => window.__m.calls)));

  // ---- 9. An older app without the new bridge methods
  const p4 = await b.newPage({ viewport: { width: 360, height: 900 } });
  await p4.addInitScript(bridgeInit, { noExtras: true });
  await p4.addInitScript(() => { window.__m.state = { running: false, checkedAt: Date.now() - 1000, updates: [] }; });
  await p4.goto(PAGE); await p4.waitForTimeout(300);
  await p4.click('.tab-btn[data-tab="installer"]'); await p4.click('#instSwitchUpdater'); await p4.waitForTimeout(100);
  await p4.click('#updRecheckBtn');
  console.log('without the new methods, Check again is a check:', JSON.stringify(await p4.evaluate(() => window.__m.calls)));

  console.log('errors:', JSON.stringify(errors));
  console.log(bad ? bad + ' FAILED' : 'all ok');
  await b.close();
  if (bad) process.exit(1);
})();
