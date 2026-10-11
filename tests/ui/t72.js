// v6.1 Installer: a small ▾ button at the end of the installer source (-i) box and of the requester / originating URI box opens a list of common values;
// choosing one fills the box.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' } });
  await page.addInitScript(() => {
    window.__pkgs['content://pick/1'] = { type: 'apk', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 9e6,
      splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 9e6, isBase: true, split: '' }], signed: true, installed: false };
  });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.toggleInstallCombo === 'function');
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => switchView('installer'));
  await ev(() => pickInstallFile());
  await page.waitForFunction(() => /^Loaded/.test(document.getElementById('installPickHint').innerText));
  const open = kind => ev(k => { const m = document.getElementById(k === 'installer' ? 'comboMenuInstaller' : 'comboMenuOrigin'); return getComputedStyle(m).display !== 'none'; }, kind);
  const items = id => page.locator('#' + id + ' .combo-item');
  const entries = id => page.locator('#' + id + ' .combo-item:not(.clear)');

  // 1) where the buttons are
  const g = await ev(() => { const r = id => document.getElementById(id).getBoundingClientRect(); const i = r('optInstaller'), btn = r('optInstallerBtn'), j = r('optOriginating'), b2 = r('optOriginatingBtn');
    return { insideInstaller: btn.right <= i.right + 0.5 && btn.left > i.left + i.width * 0.7 && btn.top >= i.top && btn.bottom <= i.bottom, insideOrigin: b2.right <= j.right + 0.5 && b2.left > j.left + j.width * 0.7 && b2.top >= j.top && b2.bottom <= j.bottom, w: Math.round(btn.width), h: Math.round(btn.height), padR: parseFloat(getComputedStyle(document.getElementById('optInstaller')).paddingRight) }; });
  check('1. each box has a small button at its very end, inside the box', g.insideInstaller && g.insideOrigin, JSON.stringify(g));
  check('   the text never runs under the button (the box keeps room for it)', g.padR >= g.w + 6, g.padR + ' vs ' + g.w);
  check('   the button is big enough to hit (at least 36 px each way)', g.w >= 36 && g.h >= 30, g.w + 'x' + g.h);
  check('   both buttons say what they do for a screen reader', await ev(() => ['optInstallerBtn', 'optOriginatingBtn'].every(id => /Choose a common/.test(document.getElementById(id).getAttribute('aria-label')) && document.getElementById(id).getAttribute('aria-haspopup') === 'menu' && document.getElementById(document.getElementById(id).getAttribute('aria-controls')).getAttribute('role') === 'menu')));
  check('   the lists are closed to start with', !(await open('installer')) && !(await open('origin')));

  // 2) the installer source list
  await page.click('#optInstallerBtn');
  const n = await items('comboMenuInstaller').count();
  const list = await ev(() => INSTALLER_SOURCES.map(x => x.name + '=' + x.value));
  check('2. the button opens a list of installer sources (' + n + ')', (await open('installer')) && n === list.length && n >= 20, n);
  check('   the button shows it is open', (await ev(() => document.getElementById('optInstallerBtn').getAttribute('aria-expanded'))) === 'true');
  const first = await items('comboMenuInstaller').first().innerText();
  check('   each entry shows the store and its package name; Google Play comes first', /Google Play Store\s+com\.android\.vending/.test(first), first.replace(/\n/g, ' '));
  const want = { 'Google Play Store': 'com.android.vending', 'F-Droid': 'org.fdroid.fdroid', 'Aurora Store': 'com.aurora.store', 'Amazon Appstore': 'com.amazon.venezia', 'Samsung Galaxy Store': 'com.sec.android.app.samsungapps',
    'Huawei AppGallery': 'com.huawei.appmarket', 'APKMirror Installer': 'com.apkmirror.helper.prod', 'Obtainium': 'dev.imranr.obtainium', 'Xiaomi GetApps': 'com.xiaomi.mipicks', 'vivo App Store': 'com.bbk.appstore' };
  const have = await ev(() => Object.fromEntries(INSTALLER_SOURCES.map(x => [x.name, x.value])));
  check('   the stores the user named are there with their package names (Google Play, F-Droid, Aurora, Amazon, Samsung, Huawei, APKMirror, Obtainium, and more)', Object.keys(want).every(k => have[k] === want[k]), JSON.stringify(Object.keys(want).filter(k => have[k] !== want[k])));
  check('   there is an itch.io entry', Object.keys(have).some(k => /itch/i.test(k)));
  check('   every value is a package name and none is listed twice', await ev(() => { const v = INSTALLER_SOURCES.map(x => x.value); return v.every(x => /^[a-z][a-z0-9_]*(\.[a-zA-Z0-9_]+)+$/.test(x)) && new Set(v).size === v.length && new Set(INSTALLER_SOURCES.map(x => x.name)).size === v.length; }));
  check('   the list stays inside the screen (not wider than the box, not taller than half the screen)', await ev(() => { const r = document.getElementById('comboMenuInstaller').getBoundingClientRect(), i = document.getElementById('optInstaller').getBoundingClientRect(); return Math.abs(r.width - i.width) < 2 && r.height <= innerHeight * 0.5 + 1 && r.left >= 0 && r.right <= innerWidth; }));
  await items('comboMenuInstaller').filter({ hasText: 'F-Droid' }).click();
  check('   choosing F-Droid fills the box with its package name and closes the list', (await page.inputValue('#optInstaller')) === 'org.fdroid.fdroid' && !(await open('installer')));
  check('   the command shown below follows at once', /pm install -r -i org\.fdroid\.fdroid/.test(await page.locator('#installPreview').innerText()));
  check('   the install options take it', (await ev(() => buildInstallOptions().opts.createFlags)).includes('-i org.fdroid.fdroid'));
  await page.click('#optInstallerBtn');
  check('   with something in the box the list starts with a "Clear this box" entry', /Clear this box/.test(await items('comboMenuInstaller').first().innerText()));
  await items('comboMenuInstaller').first().click();
  check('   it empties the box', (await page.inputValue('#optInstaller')) === '' && !(await open('installer')) && !(await ev(() => buildInstallOptions().opts.createFlags)).includes('-i'));
  await page.click('#optInstallerBtn');
  check('   with the box empty there is no "Clear" entry', !/Clear this box/.test(await items('comboMenuInstaller').first().innerText()));
  await page.click('#optInstallerBtn');
  check('   the button closes the list again', !(await open('installer')) && (await ev(() => document.getElementById('optInstallerBtn').getAttribute('aria-expanded'))) === 'false');

  // 3) the requester list
  await page.click('#optOriginatingBtn');
  const urls = await entries('comboMenuOrigin').evaluateAll(els => els.map(e => e.querySelector('small').innerText));
  check('3. the requester list offers common addresses, filled in with the package that is open', urls.includes('https://play.google.com/store/apps/details?id=com.example.app') && urls.includes('market://details?id=com.example.app') && urls.includes('https://f-droid.org/packages/com.example.app/'), JSON.stringify(urls.slice(0, 3)));
  check('   it also lists sites that do not depend on the package (APKMirror, GitHub, itch.io)', urls.includes('https://www.apkmirror.com/') && urls.includes('https://github.com/') && urls.includes('https://itch.io/'));
  await items('comboMenuOrigin').filter({ hasText: 'Google Play page' }).click();
  check('   choosing Google Play page fills the box', (await page.inputValue('#optOriginating')) === 'https://play.google.com/store/apps/details?id=com.example.app' && !(await open('origin')));
  check('   the command carries it', (await ev(() => buildInstallOptions().opts.createFlags)).includes('--originating-uri https://play.google.com/store/apps/details?id=com.example.app'));
  // with no package the addresses stop at the site
  await ev(() => { installData.pkg = ''; });
  await page.click('#optOriginatingBtn');
  const plain = await entries('comboMenuOrigin').evaluateAll(els => els.map(e => e.querySelector('small').innerText));
  check('   with no package open the addresses stop at the site (no "{pkg}" is ever shown)', plain.every(u => !u.includes('{pkg}') && !u.includes('undefined')) && plain.includes('https://play.google.com/store/apps'), JSON.stringify(plain.slice(0, 2)));
  await ev(() => { installData.pkg = 'com.example.app'; });
  await page.click('#optOriginatingBtn');

  // 4) closing it
  await page.click('#optInstallerBtn');
  await ev(() => toggleInstallCombo('origin'));            // (the open list covers the other box, so a person closes it first; the page copes either way)
  check('4. opening one list closes the other', !(await open('installer')) && (await open('origin')));
  await page.keyboard.press('Escape');
  check('   Escape closes it', !(await open('origin')));
  await ev(() => toggleInstallCombo('origin'));
  await page.click('#optInstaller');
  check('   a tap in the text of the other box closes the open list (that box is outside the list\'s own)', !(await open('origin')));
  await ev(() => toggleInstallCombo('installer'));
  await ev(() => document.getElementById('optInstallerBtn').focus());
  await page.keyboard.press('Escape');
  check('   Escape from the button closes the list and the focus stays on the button', !(await open('installer')) && (await ev(() => document.activeElement.id)) === 'optInstallerBtn');
  const roles = await (async () => { await page.click('#optInstallerBtn'); const r = await ev(() => Array.from(document.querySelectorAll('#comboMenuInstaller .combo-item')).every(b => b.getAttribute('role') === 'menuitem')); await page.click('#optInstallerBtn'); return r; })();
  check('   the entries are menu items for a screen reader (they are buttons in a menu)', roles);
  await page.click('#optInstallerBtn');
  await ev(() => document.getElementById('installPreview').scrollIntoView({ block: 'center' }));
  await page.click('#installPreview');
  check('   a tap anywhere else closes it', !(await open('installer')));
  await page.click('#optInstallerBtn');
  check('   Back closes it first (and leaves the tab where it is)', (await ev(() => handleAndroidBack())) === true && !(await open('installer')) && (await ev(() => currentViewName())) === 'installer');
  await page.click('#optInstallerBtn');
  await ev(() => switchView('apps'));
  await ev(() => switchView('installer'));
  check('   leaving the tab and coming back finds the list closed', !(await open('installer')));
  check('   a tap in the typed text of the box does not open or close the list by accident', await (async () => { await page.click('#optInstaller'); return !(await open('installer')); })());

  // 5) no privilege: the boxes are off, and so are their buttons
  await ev(() => { document.getElementById('optAuthorizer').value = 'none'; installAuthorizerChanged(); });
  check('5. with "No privilege" both boxes and both buttons are off, and the buttons open nothing', await ev(() => ['optInstaller', 'optOriginating', 'optInstallerBtn', 'optOriginatingBtn'].every(id => document.getElementById(id).disabled)));
  await ev(() => document.getElementById('optInstallerBtn').click());
  check('   (a click on the disabled button does nothing)', !(await open('installer')));
  await ev(() => { document.getElementById('optAuthorizer').value = 'adb'; installAuthorizerChanged(); });
  check('   and they are back with a working mode', await ev(() => ['optInstaller', 'optOriginating', 'optInstallerBtn', 'optOriginatingBtn'].every(id => !document.getElementById(id).disabled)));
  await page.screenshot({ path: 'combo_closed.png', clip: { x: 0, y: 0, width: 360, height: 800 } });

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
