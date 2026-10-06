// Backup and restore: create with progress, restore preview, share, delete, file picker; app data needs Root
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const run = async (root) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(root => {
      window.__calls = []; window.__backups = [{ ref: "content://media/9", name: "Mom's App_1.0_20261001.adbbackup", pkg: 'com.mom.app', label: "Mom's App", versionName: '1.0', createdAt: Date.now() - 3600e3, bytes: 52428800, hasData: true },
        { ref: '/sdcard/x.adbbackup', name: 'Notes_2.adbbackup', pkg: 'com.notes', label: 'Notes', versionName: '2.0', createdAt: Date.now() - 86400e3, bytes: 3e6, hasData: false }];
      const apps = [{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '26.0' }];
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore() {}, loadStore() { return ''; }, loadPackages() { return JSON.stringify(apps); }, getAppDetails() { return '{}'; }, getAppSizes() { return '{}'; },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        hasRoot() { return root; }, getBackups() { return JSON.stringify(window.__backups); },
        backupApp(p, d) { window.__calls.push('backup:' + p + ':' + d); setTimeout(() => window.onBackupProgress(JSON.stringify({ op: 'backup', msg: 'Saving the backup...', pct: 55 })), 20);
          window.__finishBackup = () => window.onBackupDone(JSON.stringify({ op: 'backup', ok: true, label: 'Samsung Internet', path: 'Download/ADB App Manager/Backups/x.adbbackup', bytes: 181e6, hasData: d, warnings: d ? ['Some data changed while it was being read; the backup may be incomplete'] : [] })); },
        restoreBackup(r, d) { window.__calls.push('restore:' + r + ':' + d); setTimeout(() => window.onBackupDone(JSON.stringify({ op: 'restore', ok: true, label: "Mom's App", install: 'installed 1.0', permissionsGranted: 3, permissionsTotal: 4, appopsSet: 1, data: d ? 'restored' : undefined, warnings: [] })), 60); },
        shareStoredFile(r, m, n) { window.__calls.push('share:' + r + '|' + n); return ''; },
        deleteBackup(r) { window.__calls.push('delete:' + r); window.__backups = window.__backups.filter(x => x.ref !== r); return ''; },
        pickBackupFile() { window.__calls.push('pick'); setTimeout(() => window.onBackupPicked(JSON.stringify({ ref: 'content://picked/1', pkg: 'com.picked', label: 'Picked App', versionName: '9', hasData: true, createdAt: Date.now(), bytes: 1 })), 20); },
        executeAppAction() { return ''; }, executeShell() { return ''; },
      };
    }, root);
    await page.goto(PAGE); await page.waitForTimeout(400);
    return { page, errors };
  };
  const calls = p => p.evaluate(() => window.__calls.slice());

  // ---- with Root ----
  let { page: p, errors } = await run(true);
  await p.evaluate(() => openInspector('com.sec.android.app.sbrowser')); await p.waitForTimeout(250);
  console.log('menu has Backup button:', await p.locator('#sheetBtnBackup').isVisible());
  await p.click('#sheetBtnBackup'); await p.waitForTimeout(300);
  console.log('modal open:', await p.locator('#backupsModal.show').count() === 1, '| inspector closed:', await p.locator('#inspectorModal.show').count() === 0);
  console.log('target:', (await p.innerText('#backupTarget')).replace(/\s+/g, ' ').slice(0, 120));
  console.log('data checkbox enabled with root:', await p.locator('#backupIncludeData').isEnabled());
  console.log('list rows:', await p.locator('#backupsList .backup-item').count(), '|', (await p.locator('#backupsList .backup-item h4').first().innerText()));
  await p.screenshot({ path: 'backups_root.png' });
  await p.check('#backupIncludeData'); await p.click('#backupTarget button:has-text("Create backup")');
  await p.waitForFunction(() => document.getElementById('backupProgressText').innerText.trim() === 'Saving the backup...');          // the first progress report from the phone is in (not the page's own "Starting..." before it); the backup is held until released
  console.log('progress shown:', await p.locator('#backupProgress').isVisible(), '|', await p.innerText('#backupProgressText'), '| button disabled while running:', await p.locator('#backupTarget button').isDisabled());
  await p.evaluate(() => window.__finishBackup());
  await p.waitForFunction(() => /Saved/.test(document.getElementById('backupResult').innerText));
  console.log('result:', (await p.innerText('#backupResult')).replace(/\s+/g, ' '));
  console.log('calls:', JSON.stringify(await calls(p)));
  // restore with an apostrophe in the label (data attributes keep the handler intact)
  await p.locator('#backupsList .backup-item').first().locator('button:has-text("Restore")').click();
  console.log('restore preview:', (await p.innerText('#restorePreview')).replace(/\s+/g, ' ').slice(0, 130));
  console.log('restore-data checkbox enabled (has data + root):', await p.locator('#restoreIncludeData').isEnabled());
  await p.check('#restoreIncludeData'); await p.click('#restorePreview button:has-text("Confirm restore")');
  await p.waitForFunction(() => /installed 1\.0/.test(document.getElementById('backupResult').innerText));
  console.log('restore result:', (await p.innerText('#backupResult')).replace(/\s+/g, ' '));
  console.log('restore call:', (await calls(p)).filter(c => c.startsWith('restore')).join());
  await p.screenshot({ path: 'backups_done.png' });
  // share + delete
  await p.locator('#backupsList .backup-item').first().locator('button:has-text("Share")').click();
  console.log('share call:', (await calls(p)).filter(c => c.startsWith('share')).join());
  await p.locator('#backupsList .backup-item').nth(1).locator('button:has-text("Delete")').click();
  console.log('after delete rows:', await p.locator('#backupsList .backup-item').count(), '|', (await calls(p)).filter(c => c.startsWith('delete')).join());
  // picker
  await p.click('button:has-text("Choose backup file")');
  await p.waitForFunction(() => /Restore Picked App/.test(document.getElementById('restorePreview').innerText));
  console.log('picked → preview:', (await p.innerText('#restorePreview')).replace(/\s+/g, ' ').slice(0, 80));
  await p.click('#restorePreview button:has-text("Cancel")');
  console.log('errors:', JSON.stringify(errors));
  await p.close();

  // ---- without Root ----
  ({ page: p, errors } = await run(false));
  await p.evaluate(() => openBackups('com.sec.android.app.sbrowser')); await p.waitForTimeout(250);
  console.log('no root → data checkbox disabled:', await p.locator('#backupIncludeData').isDisabled(), '|', (await p.innerText('#backupTarget')).includes('Needs Root'));
  await p.locator('#backupsList .backup-item').first().locator('button:has-text("Restore")').click();
  console.log('no root → restore data checkbox disabled:', await p.locator('#restoreIncludeData').isDisabled());
  console.log('errors:', JSON.stringify(errors));
  await p.close();
  await b.close(); })();
