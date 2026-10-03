// v5.7: debloater checkmark fill (same as Apps tab), no Force Stop button in app rows, press-and-hold the checkmark FAB to clear every selection.
const { chromium, PAGE, OUT, fixture } = require('./lib/pw');
const fs = require('fs');
const MOCK = fs.readFileSync(fixture('uad_mock.json'), 'utf8');
(async () => {
  const b = await chromium.launch();
  for (const dark of [true, false]) {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(([d, mock]) => {
      const data = JSON.parse(mock);
      const apps = { 'com.facebook.katana': { name: 'Facebook' }, 'com.netflix.mediaclient': { name: 'Netflix' }, 'com.spotify.music': { name: 'Spotify' }, 'com.samsung.android.bixby.agent': { name: 'Bixby', isSystem: true }, 'com.example.fifth': { name: 'Fifth' } };
      const st = { calls: [] }; window.__st = st;
      window.AndroidBridge = {
        vibrate() { st.calls.push('vibrate'); }, loadPreferences() { return JSON.stringify({ version: 2, preset: 'material3', appearance: d ? 'dark' : 'light', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
        loadCustomLists() { return '[]'; }, saveCustomLists() {}, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return d; }, setSystemBarColor() {},
        loadPackages() { return JSON.stringify(Object.entries(apps).map(([pkg, a]) => Object.assign({ pkg, isSystem: false, isRunning: true, isFrozen: false, isUninstalled: false, isSuspended: false }, a))); },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        getUadStatus() { return JSON.stringify({ cached: true, count: 5381, updatedAt: Date.now(), stale: false, downloading: false }); },
        getUadMatches() { return JSON.stringify(data); },
        getAppDetails() { return JSON.stringify({ permissions: [], appopsRaw: '', activityInfo: [], services: [] }); },
        executeAppAction(action, pkg) { st.calls.push(action + ':' + pkg); return 'ok'; },
      };
    }, [dark, MOCK]);
    await page.goto(PAGE); await page.waitForTimeout(350);
    const tag = dark ? '[dark] ' : '[light] ';
    const sleep = ms => page.waitForTimeout(ms);
    const rgb = s => { const m = s.match(/[\d.]+/g).map(Number); return m.slice(0, 3).map(x => Math.round(x)).join(','); };
    const toast = () => page.locator('#toastMsg').innerText();

    // 1) Debloater checkmark: whole box filled with the accent color, same as the Apps tab.
    await page.evaluate(() => toggleSelectPkg('com.netflix.mediaclient')); await sleep(60);
    const appsCb = await page.locator('#card_com\\.netflix\\.mediaclient .app-checkbox').evaluate(e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, border: c.borderTopColor, text: e.innerText }; });
    const appsOff = await page.locator('#card_com\\.facebook\\.katana .app-checkbox').evaluate(e => { const c = getComputedStyle(e); return { bg: c.backgroundColor }; });
    await page.evaluate(() => clearBatchSelection());
    await page.click('.tab-btn:has-text("Debloater")'); await sleep(300);
    const firstPkg = await page.locator('.uad-row').first().getAttribute('data-pkg');
    await page.evaluate(p => toggleUadSelect(p), firstPkg); await sleep(60);
    const uadOn = await page.locator('.uad-row').first().locator('.app-checkbox').evaluate(e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, border: c.borderTopColor, text: e.innerText }; });
    const uadOff = await page.locator('.uad-row').nth(1).locator('.app-checkbox').evaluate(e => { const c = getComputedStyle(e); return { bg: c.backgroundColor, text: e.innerText }; });
    const accent = await page.evaluate(() => { const t = document.createElement('i'); t.style.color = getComputedStyle(document.documentElement).getPropertyValue('--accent'); document.body.appendChild(t); const c = getComputedStyle(t).color; t.remove(); return c; });
    console.log(tag + '1. Debloater selected box is filled with the accent color:', rgb(uadOn.bg) === rgb(accent) && rgb(uadOn.border) === rgb(accent), uadOn.bg, 'accent', accent);
    console.log(tag + '   identical to the Apps tab selected box (fill, border):', rgb(uadOn.bg) === rgb(appsCb.bg) && rgb(uadOn.border) === rgb(appsCb.border), 'apps', appsCb.bg);
    console.log(tag + '   shows the ✓ mark; an unselected box is not filled:', uadOn.text === '✓' && rgb(uadOff.bg) === rgb(appsOff.bg) && rgb(uadOff.bg) !== rgb(accent), JSON.stringify({ on: uadOn.text, offBg: uadOff.bg }));
    await page.screenshot({ path: `${OUT}/uad_check_${dark ? 'dark' : 'light'}.png` });
    await page.evaluate(p => toggleUadSelect(p), firstPkg);
    await page.click('.tab-btn[data-tab="apps"]').catch(() => {});
    await page.evaluate(() => switchView('apps')); await sleep(150);

    // 2) No Force Stop button in the app rows (only settings + menu are left).
    const rowBtns = await page.locator('#card_com\\.netflix\\.mediaclient .app-actions button').evaluateAll(bs => bs.map(x => (x.getAttribute('title') || '') + '|' + x.innerText));
    const html = await page.locator('#card_com\\.netflix\\.mediaclient').innerHTML();
    console.log(tag + '2. app rows have only ⚙️ App Settings and ⋯ Menu:', rowBtns.length === 2 && /App Settings/.test(rowBtns[0]) && /Menu/.test(rowBtns[1]), JSON.stringify(rowBtns));
    console.log(tag + '   no force-stop control or handler left in the row:', !/force/i.test(html) && !/forceStop|force-stop|forcestop/i.test(html) && !/⏹|⛔|🛑/.test(html));
    const w = await page.locator('#card_com\\.netflix\\.mediaclient .app-meta').evaluate(e => e.getBoundingClientRect().width);
    const wLeft = await page.locator('#card_com\\.netflix\\.mediaclient .app-left').evaluate(e => e.getBoundingClientRect().width);
    console.log(tag + '   the freed room goes to the text (meta width ' + Math.round(w) + 'px of ' + Math.round(wLeft) + 'px):', w > 150);

    // 3) Selecting apps shows the checkmark FAB; a normal tap opens the panel and keeps the selection.
    await page.evaluate(() => { toggleSelectPkg('com.facebook.katana'); toggleSelectPkg('com.spotify.music'); toggleSelectPkg('com.example.fifth'); }); await sleep(350);
    const fab0 = await page.evaluate(() => ({ shown: document.getElementById('batchFab').classList.contains('show'), badge: document.getElementById('batchFabBadge').innerText, sel: selectedPkgs.size, panel: document.getElementById('floatingBatchBar').classList.contains('show') }));
    console.log(tag + '3. FAB shows the selection count:', fab0.shown && fab0.badge === '3' && fab0.sel === 3 && !fab0.panel, JSON.stringify(fab0));
    const fabLabel = await page.locator('#batchFab').getAttribute('aria-label');
    const fabTitle = await page.locator('#batchFab').getAttribute('title');
    console.log(tag + '   tooltip/aria mention the hold gesture:', /hold/i.test(fabLabel) && /Hold/.test(fabTitle) && /clear/i.test(fabTitle), JSON.stringify(fabTitle));
    await page.locator('#batchFab').click(); await sleep(250);
    const tap = await page.evaluate(() => ({ panel: document.getElementById('floatingBatchBar').classList.contains('show'), sel: selectedPkgs.size }));
    console.log(tag + '   a quick tap opens the batch panel, selection kept:', tap.panel && tap.sel === 3, JSON.stringify(tap));
    await page.evaluate(() => collapseBatchPanel()); await sleep(250);

    // 4) Press and hold (mouse): clears all selections, toast, FAB goes away, panel does NOT open.
    const box = await page.locator('#batchFab').boundingBox();
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy); await page.mouse.down();
    await sleep(250);
    const mid = await page.evaluate(() => selectedPkgs.size);
    await sleep(500);
    await page.mouse.up(); await sleep(250);
    const held = await page.evaluate(() => ({ sel: selectedPkgs.size, fab: document.getElementById('batchFab').classList.contains('show'), panel: document.getElementById('floatingBatchBar').classList.contains('show'), checked: document.querySelectorAll('.app-card.selected').length, marks: [...document.querySelectorAll('.app-checkbox')].filter(c => c.innerText.trim()).length }));
    console.log(tag + '4. before the hold completes nothing is cleared:', mid === 3);
    console.log(tag + '   hold clears all selections (rows unmarked), FAB hidden, panel stays closed:', held.sel === 0 && !held.fab && !held.panel && held.checked === 0 && held.marks === 0, JSON.stringify(held));
    console.log(tag + '   toast says how many were cleared:', /Cleared 3 selections/.test(await toast()), JSON.stringify(await toast()));

    // 5) Touch long-press (synthetic touch events), and cancelling by dragging off.
    await page.evaluate(() => { toggleSelectPkg('com.facebook.katana'); toggleSelectPkg('com.spotify.music'); }); await sleep(350);
    const touchSeq = (type, x, y) => page.evaluate(([t, x, y]) => {
      const el = document.getElementById('batchFab');
      const tc = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      el.dispatchEvent(new TouchEvent(t, { touches: t === 'touchend' ? [] : [tc], changedTouches: [tc], bubbles: true, cancelable: true }));
    }, [type, x, y]);
    await touchSeq('touchstart', cx, cy); await sleep(300);
    await touchSeq('touchmove', cx + 40, cy); await sleep(400);     // moved 40px: cancels the hold
    await touchSeq('touchend', cx + 40, cy); await sleep(150);
    console.log(tag + '5. dragging off the button cancels the hold (selection kept):', (await page.evaluate(() => selectedPkgs.size)) === 2);
    await touchSeq('touchstart', cx, cy); await sleep(700);
    await touchSeq('touchend', cx, cy); await sleep(200);
    const tl = await page.evaluate(() => ({ sel: selectedPkgs.size, panel: document.getElementById('floatingBatchBar').classList.contains('show') }));
    console.log(tag + '   touch press-and-hold clears the selection:', tl.sel === 0 && !tl.panel && /Cleared 2 selections/.test(await toast()), JSON.stringify(tl));

    // 6) Releasing early (a tap), and the click after a hold, behave: tap opens, hold-click does not.
    await page.evaluate(() => { toggleSelectPkg('com.facebook.katana'); }); await sleep(350);
    await page.mouse.move(cx, cy); await page.mouse.down(); await sleep(120); await page.mouse.up(); await sleep(250);
    console.log(tag + '6. a 120 ms press is a tap (opens the panel, nothing cleared):', await page.evaluate(() => document.getElementById('floatingBatchBar').classList.contains('show') && selectedPkgs.size === 1));
    await page.evaluate(() => collapseBatchPanel()); await sleep(250);
    // 7) A long press with one app selected uses the singular wording; the context menu is suppressed.
    const ctx = await page.evaluate(() => { const e = new MouseEvent('contextmenu', { bubbles: true, cancelable: true }); document.getElementById('batchFab').dispatchEvent(e); return e.defaultPrevented; });
    await page.mouse.move(cx, cy); await page.mouse.down(); await sleep(700); await page.mouse.up(); await sleep(200);
    console.log(tag + '7. singular toast + context menu suppressed:', /Cleared 1 selection$/.test(await toast()) && ctx === true, JSON.stringify(await toast()));
    // 8) Holding with nothing selected does nothing (FAB is hidden anyway) and leaves no stale flag.
    await page.evaluate(() => { fabHoldStart(0, 0); }); await sleep(700);
    const stale = await page.evaluate(() => fabHoldFired);
    await page.evaluate(() => { toggleSelectPkg('com.facebook.katana'); }); await sleep(300);
    await page.locator('#batchFab').click(); await sleep(200);
    console.log(tag + '8. a hold with no selection leaves no flag behind; the next tap still opens the panel:', stale === false && (await page.evaluate(() => document.getElementById('floatingBatchBar').classList.contains('show'))));
    console.log(tag + 'errors:', JSON.stringify(errors));
    await page.close();
  }
  await b.close();
})();
