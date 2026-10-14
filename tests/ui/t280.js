// Backups: Delete on a backup asks first (Cancel keeps it and does not call the phone), and deletes it only after OK.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__calls = [];
    window.__backups = [{ ref: 'content://media/9', name: 'Mom_1.0.adbbackup', pkg: 'com.mom.app', label: 'Mom App', versionName: '1.0', createdAt: Date.now() - 3600e3, bytes: 52428800, hasData: true },
      { ref: '/sdcard/x.adbbackup', name: 'Notes_2.adbbackup', pkg: 'com.notes', label: 'Notes', versionName: '2.0', createdAt: Date.now() - 86400e3, bytes: 3e6, hasData: false }];
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() {}, loadStore() { return ''; }, loadPackages() { return '[]'; }, getAppDetails() { return '{}'; }, getAppSizes() { return '{}'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      hasRoot() { return true; }, getBackups() { return JSON.stringify(window.__backups); },
      backupApp() {}, deleteBackup(r) { window.__calls.push('delete:' + r); window.__backups = window.__backups.filter(x => x.ref !== r); return ''; },
      executeAppAction() { return ''; }, executeShell() { return ''; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const calls = () => page.evaluate(() => window.__calls.slice());
  const rows = () => page.locator('#backupsList .backup-item').count();
  await page.evaluate(() => openBackups()); await page.waitForTimeout(250);
  check('two backups are listed', await rows() === 2, String(await rows()));

  const asked = [];
  page.once('dialog', d => { asked.push(d.message()); d.dismiss(); });
  await page.locator('#backupsList .backup-item').first().locator('button:has-text("Delete")').click(); await page.waitForTimeout(150);
  check('Delete asks first, naming the backup', asked.length === 1 && /Mom App/.test(asked[0]), JSON.stringify(asked));
  check('Cancel: the phone is not asked to delete', (await calls()).length === 0, JSON.stringify(await calls()));
  check('Cancel: both backups are still listed', await rows() === 2, String(await rows()));

  page.once('dialog', d => { asked.push(d.message()); d.accept(); });
  await page.locator('#backupsList .backup-item').first().locator('button:has-text("Delete")').click(); await page.waitForTimeout(150);
  check('OK: that backup is deleted', JSON.stringify(await calls()) === JSON.stringify(['delete:content://media/9']), JSON.stringify(await calls()));
  check('OK: one backup is left', await rows() === 1 && /Notes/.test(await page.innerText('#backupsList')), String(await rows()));
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
