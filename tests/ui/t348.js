// v7.13.4: the batch menu's Backup button (it took the place of Share List) backs up the selected apps one after another; the app menu has no
// Backup button. One choice about data for all of them, a bar for the whole run, one summary with the apps that could not be saved.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const run = async (root) => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    const apps = ['alpha', 'bravo', 'charlie', 'delta', 'echo'].map(n => ({ pkg: 'com.example.' + n, name: n[0].toUpperCase() + n.slice(1), isSystem: false, version: '1.0', isFrozen: false, isSuspended: false, isUninstalled: false }));
    await page.addInitScript(([a, root]) => {
      window.__calls = []; window.__backups = [];
      window.AndroidBridge = {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
        saveStore() {}, loadStore() { return ''; }, loadPackages() { return JSON.stringify(a); }, getAppDetails() { return '{}'; }, getAppSizes() { return '{}'; },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        hasRoot() { return root; }, getBackups() { return JSON.stringify(window.__backups); },
        // each backup is finished by the script (window.__finish), the way the phone finishes one after a while
        backupApp(p, d) { window.__calls.push('backup:' + p + ':' + d); window.__cur = { p, d }; },
        // the phone's progress report is sent by the script, so the text read below does not depend on timing
        __progress() { window.onBackupProgress(JSON.stringify({ op: 'backup', msg: 'Saving the backup...', pct: 50 })); },
        executeAppAction() { return ''; }, executeShell() { return ''; },
      };
      window.__finish = fail => { const cur = window.__cur; window.__cur = null; window.__cur2 = cur; window.__done(fail, cur); };
      window.__done = (fail, cur) => window.onBackupDone(JSON.stringify(fail
        ? { op: 'backup', ok: false, pkg: cur.p, error: 'No space left' }
        : { op: 'backup', ok: true, pkg: cur.p, label: cur.p.split('.').pop(), path: 'Download/ADB App Manager/Backups/' + cur.p + '.adbbackup', bytes: 1e6, hasData: cur.d, warnings: [] }));
    }, [apps, root]);
    await page.goto(PAGE); await page.waitForTimeout(400);
    return { page, errors };
  };
  const calls = p => p.evaluate(() => window.__calls.slice());

  let { page: p, errors } = await run(true);
  // the app menu has no Backup button, the batch menu has one and no Share List
  await p.evaluate(() => openInspector('com.example.alpha')); await sleep(250);
  console.log('app menu: no Backup button:', await p.locator('#sheetBtnBackup').count() === 0, '| buttons:', (await p.locator('.sheet-action-grid .sheet-btn').allInnerTexts()).map(t => t.trim()).join(','));
  await p.evaluate(() => closeInspector()); await sleep(200);
  await p.evaluate(() => { ['com.example.alpha', 'com.example.bravo', 'com.example.charlie', 'com.example.delta', 'com.example.echo'].forEach(x => toggleSelectPkg(x)); expandBatchPanel(); }); await sleep(400);
  const grid = await p.evaluate(() => Array.from(document.querySelectorAll('.batch-actions-grid .batch-grid-btn')).map(x => x.innerText.trim()));
  console.log('batch menu:', grid.join(','), '| Backup in place of Share List:', grid.includes('Backup') && !grid.includes('Share List'));
  // nothing selected: a toast, no box
  await p.evaluate(() => { clearBatchSelection(); }); await sleep(100);
  await p.evaluate(() => batchBackup()); await sleep(200);
  console.log('nothing selected: box closed:', await p.locator('#backupsModal.show').count() === 0);

  // five apps selected
  await p.evaluate(() => { ['com.example.alpha', 'com.example.bravo', 'com.example.charlie', 'com.example.delta', 'com.example.echo'].forEach(x => toggleSelectPkg(x)); expandBatchPanel(); }); await sleep(300);
  await p.locator('.batch-actions-grid .batch-grid-btn', { hasText: /^Backup$/ }).click(); await sleep(300);
  console.log('box open:', await p.locator('#backupsModal.show').count() === 1);
  console.log('card:', (await p.innerText('#backupTarget')).replace(/\s+/g, ' ').slice(0, 140));
  console.log('data checkbox enabled with root:', await p.locator('#backupIncludeData').isEnabled());
  await p.check('#backupIncludeData'); await p.click('#backupTarget button:has-text("Create backup")');
  await p.waitForFunction(() => window.__calls.length === 1);
  console.log('first app started:', JSON.stringify(await calls(p)), '|', (await p.innerText('#backupProgressText')).trim());
  await p.evaluate(() => window.AndroidBridge.__progress());
  console.log('the phone reports progress: the line follows it, the bar shows the first of five apps:', (await p.innerText('#backupProgressText')).trim(), '|', await p.evaluate(() => document.getElementById('backupProgressBar').style.width));
  await p.evaluate(() => window.__finish(false)); await p.waitForFunction(() => window.__calls.length === 2);
  console.log('second app started (the first one is done):', (await calls(p))[1], '| still running:', await p.locator('#backupProgress').isVisible());
  await p.evaluate(() => window.__finish(true)); await p.waitForFunction(() => window.__calls.length === 3);   // bravo could not be saved
  await p.evaluate(() => window.__finish(false)); await p.waitForFunction(() => window.__calls.length === 4);
  const bar = await p.evaluate(() => parseFloat(document.getElementById('backupProgressBar').style.width));
  console.log('bar is part way through five apps:', bar > 40 && bar < 100, '| the button stays off while it runs:', await p.locator('#backupTarget button').isDisabled());
  await p.evaluate(() => window.__finish(false)); await p.waitForFunction(() => window.__calls.length === 5);
  await p.evaluate(() => window.__finish(false)); await p.waitForFunction(() => document.getElementById('backupResult').style.display === 'block');
  await sleep(60);
  console.log('calls:', JSON.stringify(await calls(p)));
  console.log('summary:', (await p.innerText('#backupResult')).replace(/\s+/g, ' '), '| marked as a problem:', await p.locator('#backupResult.err').count() === 1);
  console.log('progress gone:', !(await p.locator('#backupProgress').isVisible()), '| running again possible:', await p.evaluate(() => backupRunning === false && backupBatch === null));
  await p.screenshot({ path: 'batch_backup.png' });

  // one app selected: the old single card
  await p.evaluate(() => { document.querySelectorAll('.modal-overlay.show').forEach(m => m.classList.remove('show')); clearBatchSelection(); toggleSelectPkg('com.example.echo'); expandBatchPanel(); }); await sleep(300);
  await p.evaluate(() => batchBackup()); await sleep(200);
  console.log('one app: card:', (await p.innerText('#backupTarget')).replace(/\s+/g, ' ').slice(0, 60));
  await p.close();

  // no root: the data switch is off
  ({ page: p, errors } = await run(false));
  await p.evaluate(() => { toggleSelectPkg('com.example.alpha'); toggleSelectPkg('com.example.bravo'); batchBackup(); }); await sleep(300);
  console.log('no root: data switch disabled:', await p.locator('#backupIncludeData').isDisabled(), '|', (await p.innerText('#backupTarget')).replace(/\s+/g, ' ').slice(0, 60));
  await p.click('#backupTarget button:has-text("Create backup")'); await p.waitForFunction(() => window.__calls.length === 1);
  console.log('without data:', JSON.stringify(await calls(p)));
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
