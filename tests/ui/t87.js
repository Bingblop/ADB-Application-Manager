// v7.5 Task Manager tab: Processes (sort, bold X kill with no confirmation), CPU, RAM, GPU, Battery, Network sub-tabs; the gate on
// Processes/GPU only (CPU/RAM/Battery/Network work unprivileged); settings persistence; the two independent timers (graph redraw vs
// native poll) both stop on leaving the tab.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__kv = {};
    window.__calls = { tmStart: [], tmStop: 0, tmRefreshNow: 0, tmKill: [], executeShell: [] };
    window.__rendererProp = '';
    window.AndroidBridge = {
      executeShell(cmd) {
        window.__calls.executeShell.push(cmd);
        const base = cmd.split(';')[0];
        const m = /^setprop debug\.hwui\.renderer (\S+)$/.exec(base);
        if (m) { window.__rendererProp = m[1]; return ''; }
        if (base === 'getprop debug.hwui.renderer') return window.__rendererProp || '';
        return '';
      },
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return JSON.stringify([{ pkg: 'com.sec.android.app.sbrowser', name: 'Samsung Internet', isSystem: true, version: '1' }]); },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      tmStart(ms) { window.__calls.tmStart.push(ms); }, tmStop() { window.__calls.tmStop++; },
      tmRefreshNow() { window.__calls.tmRefreshNow++; }, tmKill(pkg) { window.__calls.tmKill.push(pkg); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const text = sel => page.locator(sel).innerText();
  const disp = sel => page.evaluate(s => getComputedStyle(document.querySelector(s)).display, sel);

  const sample = {
    ts: 0, ok: true, privileged: true,
    cpu: { overall: 42.5, perCore: [10, 20, 80, 90], cores: 4 },
    mem: { totalKb: 4000000, usedKb: 2500000, availableKb: 1500000, swapTotalKb: 1000000, swapUsedKb: 100000 },
    net: { totalRxBytesPerSec: 123456, totalTxBytesPerSec: 7890, ifaces: [{ name: 'wlan0', rxBytesPerSec: 123456, txBytesPerSec: 7890 }] },
    procs: [
      { pid: 1234, rssKb: 51200, cpuPercent: 12.3, name: 'com.sec.android.app.sbrowser', pkg: 'com.sec.android.app.sbrowser' },
      { pid: 5555, rssKb: 204800, cpuPercent: 3.1, name: 'com.unknown.app', pkg: 'com.unknown.app' },
      { pid: 2, ppid: 0, rssKb: 100, cpuPercent: 0.1, name: 'kworker/0:1', pkg: '' },
    ],
    procsFullFormat: true,
    gpu: { available: true, percent: 33.3, approx: false, label: 'Adreno gpubusy', vulkanSupported: true, vulkanApi: '1.3.0', glesVersion: '3.2' },
    battery: { percent: 78, tempTenthsC: 320, voltageMv: 4100, plugged: 2, health: 2, status: 2, technology: 'Li-ion', present: true, currentMicroA: 850000 },
  };

  // ---------------------------------------------------------------- opening the tab
  await page.evaluate(() => switchView('taskmgr')); await sleep(100);
  console.log('1. the tab is in the registry and opens to the Processes sub-tab:', (await page.evaluate(() => document.getElementById('view-taskmgr').classList.contains('active'))) && (await page.evaluate(() => document.querySelector('#tmTabs .sdb-tab.active').dataset.sub)) === 'processes');
  console.log('   opening it starts the native poll at the default interval (3s) with no gate (privileged):', (await page.evaluate(() => window.__calls.tmStart.slice(-1)[0])) === 3000 && (await disp('#tmGate')) === 'none');

  await page.evaluate((s) => window.onTaskMgrData(s), sample); await sleep(50);

  // ---------------------------------------------------------------- Processes: sort, app-name resolution, kill with no confirmation
  console.log('2. processes render sorted by CPU by default, with the resolved app name for an installed package:', /Samsung Internet/.test(await text('#tmProcList')) && (await text('#tmProcStatus')).startsWith('3 processes'));
  const order1 = await page.evaluate(() => [...document.querySelectorAll('.tm-row-name')].map(e => e.innerText));
  console.log('   highest CPU% first:', order1[0] === 'Samsung Internet' && order1[1] === 'com.unknown.app', JSON.stringify(order1));
  await page.click('#tmSortMem'); await sleep(30);
  const order2 = await page.evaluate(() => [...document.querySelectorAll('.tm-row-name')].map(e => e.innerText));
  console.log('   "Sort by memory" re-sorts by RSS instead (and is remembered as the active sort):', order2[0] === 'com.unknown.app' && (await page.evaluate(() => document.getElementById('tmSortMem').classList.contains('on'))));
  console.log('   a kernel thread with no resolvable package has no kill button:', (await page.locator('.tm-row', { hasText: 'kworker' }).locator('.tm-kill-btn').count()) === 0);
  await page.locator('.tm-row', { hasText: 'Samsung Internet' }).locator('.tm-kill-btn').click(); await sleep(30);
  console.log('3. the kill button force-stops immediately through tmKill, with no confirmation dialog anywhere:', (await page.evaluate(() => window.__calls.tmKill.slice(-1)[0])) === 'com.sec.android.app.sbrowser' && (await page.evaluate(() => document.querySelectorAll('.modal-overlay.show').length)) === 0);
  console.log('   it also asks for a fresh sample right away, instead of waiting for the next tick:', (await page.evaluate(() => window.__calls.tmRefreshNow)) >= 1);

  // ---------------------------------------------------------------- CPU / RAM / Network: no gate even without a working mode
  await page.evaluate(() => { isPrivilegedActive = false; });
  await page.evaluate(() => tmSetSub('cpu')); await sleep(30);
  console.log('4. CPU has no gate when unprivileged (it reads /proc/stat itself, no shell needed):', (await disp('#tmGate')) === 'none' && (await text('#tmCpuNow')) === '43%' && (await text('#tmCpuSub')) === '4 cores');
  const cores = await page.evaluate(() => [...document.querySelectorAll('#tmCpuCores b')].map(e => e.innerText));
  console.log('   all 4 per-core readings show:', JSON.stringify(cores) === JSON.stringify(['10%', '20%', '80%', '90%']));

  await page.evaluate(() => tmSetSub('ram')); await sleep(30);
  console.log('5. RAM has no gate either, and shows used/available/swap:', (await disp('#tmGate')) === 'none' && (await text('#tmRamNow')) === '63%' && /2\.4 GB.*Used/s.test(await text('#tmRamStats')));

  await page.evaluate(() => tmSetSub('network')); await sleep(30);
  console.log('6. Network has no gate, shows total throughput and each interface:', (await disp('#tmGate')) === 'none' && /128 KB\/s/.test(await text('#tmNetNow')) && /wlan0/.test(await text('#tmNetIfaces')));

  // ---------------------------------------------------------------- Processes / GPU: the gate when there is no working mode
  await page.evaluate(() => tmSetSub('processes')); await sleep(30);
  console.log('7. Processes shows the gate once there is no working mode:', (await disp('#tmGate')) !== 'none');
  await page.evaluate((s) => window.onTaskMgrData(Object.assign({}, s, { privileged: false, procs: [], gpu: { available: false } })), sample); await sleep(30);
  console.log('   and a plain explanation instead of a (misleadingly empty) process list:', /Needs ADB/.test(await text('#tmProcList')));
  await page.evaluate(() => tmSetSub('gpu')); await sleep(30);
  console.log('8. GPU shows the gate too, and explains the reading needs a working mode (not just "unsupported hardware"):', (await disp('#tmGate')) !== 'none' && /Needs a working mode/.test(await text('#tmGpuSub')));

  // restore privilege and a working GPU reading for the rest of the checks
  await page.evaluate(() => { isPrivilegedActive = true; });
  await page.evaluate((s) => window.onTaskMgrData(s), sample); await sleep(30);
  console.log('   with a real (non-approximate) reading, the "may not be available" note is hidden:', (await disp('#tmGpuNote')) === 'none' && (await text('#tmGpuNow')) === '33%');
  await page.evaluate(() => tmOnModeChange()); await sleep(30);   // the real trigger for a privilege change while already sitting on this sub-tab

  // ---------------------------------------------------------------- GPU renderer switch (debug.hwui.renderer)
  const optionValues = () => page.evaluate(() => [...document.querySelectorAll('#tmRendererSelect option')].map(o => o.value));
  console.log('12. switching to GPU loads the renderer dropdown: Default plus both backends, since this sample supports Vulkan:', JSON.stringify(await optionValues()) === JSON.stringify(['', 'skiagl', 'skiavk']) && !(await page.evaluate(() => document.getElementById('tmRendererSelect').disabled)));
  let before = await page.evaluate(() => window.__calls.executeShell.length);
  await page.selectOption('#tmRendererSelect', 'skiavk'); await sleep(30);
  console.log('    picking Vulkan runs setprop skiavk then crashes System UI, in that order (then re-reads the property, a 3rd call):', JSON.stringify(await page.evaluate(n => window.__calls.executeShell.slice(n, n + 2).map(c => c.split(';')[0]), before)) === JSON.stringify(['setprop debug.hwui.renderer skiavk', 'am crash com.android.systemui']));
  console.log('    the dropdown re-reads the property right after and reflects it (no "Default" option once it is actually set):', (await page.evaluate(() => document.getElementById('tmRendererSelect').value)) === 'skiavk' && JSON.stringify(await optionValues()) === JSON.stringify(['skiagl', 'skiavk']));
  before = await page.evaluate(() => window.__calls.executeShell.length);
  await page.selectOption('#tmRendererSelect', 'skiagl'); await sleep(30);
  console.log('    picking OpenGL does the same with skiagl:', JSON.stringify(await page.evaluate(n => window.__calls.executeShell.slice(n, n + 2).map(c => c.split(';')[0]), before)) === JSON.stringify(['setprop debug.hwui.renderer skiagl', 'am crash com.android.systemui']) && (await page.evaluate(() => document.getElementById('tmRendererSelect').value)) === 'skiagl');

  const beforeTick = await page.evaluate(() => document.getElementById('tmRendererSelect').outerHTML);
  const shellCallsBeforeTick = await page.evaluate(() => window.__calls.executeShell.length);
  await page.evaluate((s) => window.onTaskMgrData(s), sample); await sleep(30);
  console.log('    an ordinary auto-refresh tick while sitting on GPU does not rebuild the dropdown (would interrupt an open selection) or re-run getprop:', beforeTick === (await page.evaluate(() => document.getElementById('tmRendererSelect').outerHTML)) && (await page.evaluate(() => window.__calls.executeShell.length)) === shellCallsBeforeTick);

  await page.evaluate(() => { isPrivilegedActive = false; });
  await page.evaluate(() => tmLoadRenderer()); await sleep(30);
  console.log('    without a working mode the dropdown disables itself and explains why instead of offering a broken control:', (await page.evaluate(() => document.getElementById('tmRendererSelect').disabled)) && /Needs a working mode/.test(await text('#tmRendererNote')));
  await page.evaluate(() => { isPrivilegedActive = true; });

  await page.evaluate((s) => window.onTaskMgrData(Object.assign({}, s, { gpu: Object.assign({}, s.gpu, { vulkanSupported: false }) })), sample); await sleep(20);
  await page.evaluate(() => tmLoadRenderer()); await sleep(30);
  console.log('    a device without Vulkan hardware support is never offered the Vulkan option:', JSON.stringify(await optionValues()) === JSON.stringify(['skiagl']));
  await page.evaluate((s) => window.onTaskMgrData(s), sample); await sleep(20);

  // ---------------------------------------------------------------- Battery: units, and the units row only shows on this sub-tab
  await page.evaluate(() => tmSetSub('battery')); await sleep(30);
  console.log('9. Battery has no gate (BatteryManager needs no shell), shows percent, and its units row only appears here:', (await disp('#tmGate')) === 'none' && (await text('#tmBattNow')) === '78%' && (await disp('#tmBattUnitsRow')) !== 'none');
  const unitsF = await text('#tmBattStats');
  await page.selectOption('#tmTempUnit', 'c'); await sleep(30);
  const unitsC = await text('#tmBattStats');
  console.log('   switching the temperature unit re-renders the same reading in Celsius:', /90°F/.test(unitsF) && /32°C/.test(unitsC));
  await page.evaluate(() => tmSetSub('cpu')); await sleep(20);
  console.log('   the units row is hidden again on another sub-tab:', (await disp('#tmBattUnitsRow')) === 'none');

  // ---------------------------------------------------------------- settings persist across leaving and reopening the tab
  await page.selectOption('#tmAutoRefresh', '10');
  await page.fill('#tmGraphDelay', '800'); await page.evaluate(() => document.getElementById('tmGraphDelay').dispatchEvent(new Event('input')));
  await page.evaluate(() => tmSetSub('ram'));
  await sleep(30);
  await page.evaluate(() => switchView('about')); await sleep(30);
  console.log('10. leaving the tab stops the native poll (both timers: the gone redraw interval and the native side):', (await page.evaluate(() => window.__calls.tmStop)) >= 1);
  await page.evaluate(() => switchView('taskmgr')); await sleep(60);
  const restored = await page.evaluate(() => ({ sub: document.querySelector('#tmTabs .sdb-tab.active').dataset.sub, auto: document.getElementById('tmAutoRefresh').value, delay: document.getElementById('tmGraphDelay').value }));
  console.log('    reopening it restores the sub-tab, auto-refresh and graph speed exactly as left:', restored.sub === 'ram' && restored.auto === '10' && restored.delay === '800', JSON.stringify(restored));
  console.log('    and restarts the native poll at the restored interval:', (await page.evaluate(() => window.__calls.tmStart.slice(-1)[0])) === 10000);

  // ---------------------------------------------------------------- a graph with only one reading still draws (not a blank canvas)
  await page.evaluate(() => tmSetSub('network'));
  await page.evaluate(() => { tmBuf.net = []; });     // a fresh buffer, as right after opening the tab
  await page.evaluate((s) => window.onTaskMgrData(s), sample);
  await page.evaluate(() => tmRedrawNow());           // the redraw timer is independent of data arrival; drive it explicitly
  console.log('11. a single reading draws a visible point rather than nothing:', await page.evaluate(() => {
    const c = document.getElementById('tmNetCanvas'); const ctx = c.getContext('2d');
    const d = ctx.getImageData(0, 0, c.width, c.height).data;
    for (let i = 3; i < d.length; i += 4) if (d[i] !== 0) return true;
    return false;
  }));

  console.log(errors.length ? 'FAIL page errors: ' + errors.join(' | ') : 'no page errors');
  await page.close(); await b.close();
})();
