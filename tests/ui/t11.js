// Debloater (UAD-NG): list download, filters, needed-by warning, uninstall review, restore, save to list
const { chromium, PAGE, fixture } = require('./lib/pw');
const fs = require('fs');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock => {
    const data = JSON.parse(mock);
    const st = { calls: [], cached: false, count: 0, updatedAt: 0, downloading: false }; window.__st = st;
    window.prompt = (m, d) => 'Samsung debloat';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists(j) { st.lists = j; },
      getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getUadStatus() { return JSON.stringify({ cached: st.cached, count: st.count, updatedAt: st.updatedAt, stale: !st.cached, downloading: st.downloading }); },
      updateUadList() { st.calls.push('download'); st.downloading = true;
        setTimeout(() => { st.downloading = false; st.cached = true; st.count = 5381; st.updatedAt = Date.now();
          window.onUadListUpdated(JSON.stringify({ cached: true, count: 5381, updatedAt: st.updatedAt, error: '' })); }, 400); },
      getUadMatches() { return st.cached ? JSON.stringify(data) : '{"packages":[]}'; },
      executeAppAction(action, pkg) { st.calls.push(action + ':' + pkg);
        const p = data.packages.find(x => x.pkg === pkg);
        if (action === 'uninstall') p.state = 'uninstalled'; if (action === 'freeze') p.state = 'disabled';
        if (action === 'reinstall' || action === 'unfreeze') p.state = 'enabled';
        return action === 'uninstall' ? 'Success' : 'ok'; },
      openUrl(u) { st.calls.push('url:' + u); },
    };
  }, MOCK);
  await page.goto(PAGE); await page.waitForTimeout(300);
  console.log('tabs:', (await page.locator('.tab-btn').allInnerTexts()).map(t => t.replace(/\s+/g, ' ')).join(' | '));
  await page.click('.tab-btn:has-text("Debloater")'); await page.waitForTimeout(100);
  console.log('first open status:', await page.locator('#uadStatus').innerText());
  await page.waitForTimeout(700);
  console.log('after download:', await page.locator('#uadStatus').innerText());
  console.log('default view:', await page.locator('#uadCount').innerText(), '| levels shown:', [...new Set(await page.locator('.uad-row .uad-badge[class*="uad-r-"]').allInnerTexts())].join(','));
  await page.screenshot({ path: 'debloater.png' });
  // filters
  await page.click('#uadRemovalRow [data-removal="Unsafe"]');
  console.log('+Unsafe:', await page.locator('#uadCount').innerText());
  await page.click('#uadListRow [data-list="Google"]');
  console.log('Google only:', await page.locator('#uadCount').innerText(), [...new Set(await page.locator('.uad-row .uad-tag').allInnerTexts())]);
  await page.click('#uadListRow [data-list="all"]'); await page.click('#uadRemovalRow [data-removal="Unsafe"]');
  await page.fill('#uadSearch', 'bixby');
  console.log('search bixby:', await page.locator('.uad-name').allInnerTexts());
  await page.fill('#uadSearch', '');
  // neededBy warning
  await page.evaluate(() => { uadFilters.removal = new Set(['Recommended','Advanced','Expert','Unsafe']); renderUadList(); });
  console.log('neededBy warnings shown:', await page.locator('.uad-warn').count(), '|', (await page.locator('.uad-warn').first().innerText().catch(() => '')).slice(0, 80));
  // expand
  const first = page.locator('.uad-row').first();
  await first.locator('.uad-main').click();
  console.log('expanded:', await first.evaluate(e => e.classList.contains('expanded')));
  // select 2 Recommended + 1 Unsafe and uninstall → review shows warning
  await page.evaluate(() => { uadFilters.removal = new Set(['Recommended']); renderUadList(); });
  const recs = await page.locator('.uad-row').evaluateAll(r => r.slice(0, 2).map(x => x.dataset.pkg));
  const unsafe = await page.evaluate(() => uadPackages.find(p => p.removal === 'Unsafe' && p.state === 'enabled').pkg);
  await page.evaluate(([a, u]) => { a.forEach(p => toggleUadSelect(p)); toggleUadSelect(u); }, [recs, unsafe]);
  console.log('count:', await page.locator('#uadCount').innerText());
  await page.click('.uad-action-grid >> text=Uninstall'); await page.waitForTimeout(150);
  console.log('review:', await page.locator('#batchConfirmTitle').innerText(), '|', await page.locator('#batchConfirmSubtitle').innerText(), '|', await page.locator('#batchConfirmExecuteBtn').innerText());
  await page.screenshot({ path: 'debloater_review.png' });
  await page.click('#batchConfirmExecuteBtn'); await page.waitForTimeout(400);
  console.log('results:', (await page.locator('#commandResultsTitle').innerText()), '|', await page.locator('#commandResultsSubtitle').innerText());
  await page.evaluate(() => closeCommandResultsModal());
  // restore the uninstalled ones
  await page.click('#uadStateRow [data-state="uninstalled"]');
  await page.evaluate(() => { uadFilters.removal = new Set(['Recommended','Advanced','Expert','Unsafe']); renderUadList(); });
  console.log('uninstalled now:', await page.locator('#uadCount').innerText());
  await page.click('text=Select shown'); await page.click('.uad-action-grid >> text=Restore'); await page.waitForTimeout(150);
  console.log('restore review:', await page.locator('#batchConfirmSubtitle').innerText());
  await page.click('#batchConfirmExecuteBtn'); await page.waitForTimeout(400); await page.evaluate(() => closeCommandResultsModal());
  console.log('uninstalled after restore:', await page.locator('#uadCount').innerText());
  // save to list + wiki
  await page.click('#uadStateRow [data-state="present"]'); await page.click('text=Select shown');
  await page.click('.uad-action-grid >> text=Save to List');
  console.log('saved list:', JSON.parse(await page.evaluate(() => window.__st.lists || '[]')).map(l => l.name + ' (' + l.packages.length + ')'));
  await page.click('text=UAD-NG Wiki'); await page.click('text=Removal Levels');
  console.log('levels visible:', await page.isVisible('#uadLevels'));
  const calls = await page.evaluate(() => window.__st.calls);
  console.log('calls:', calls.filter(c => !c.startsWith('uninstall') && !c.startsWith('reinstall') && !c.startsWith('unfreeze')).join(', '), '| actions:', calls.filter(c => /^(uninstall|reinstall|unfreeze):/.test(c)).length);
  console.log('errors:', JSON.stringify(errors));
  await b.close(); })();
