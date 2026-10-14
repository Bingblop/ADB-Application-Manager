// Task Manager > CPU: each per-core reading is labelled with its real core number (review SYS-1). With CPU hotplug /proc/stat leaves
// offline cores out, so the bridge sends coreIds next to perCore; an answer without them (or with a list that does not match) is
// labelled by position as before.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__kv = {};
    window.AndroidBridge = {
      executeShell() { return ''; },
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      tmStart() {}, tmStop() {}, tmRefreshNow() {}, tmKill() {},
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const cores = () => page.evaluate(() => [...document.querySelectorAll('#tmCpuCores .tm-core')].map(e => e.querySelector('b').innerText + ' ' + e.querySelector('span').innerText));
  const send = cpu => page.evaluate(c => window.onTaskMgrData({ ts: 0, ok: true, privileged: true, cpu: c }), cpu);

  await page.evaluate(() => switchView('taskmgr')); await sleep(100);
  await page.evaluate(() => tmSetSub('cpu')); await sleep(30);

  await send({ overall: 40, perCore: [50, 25, 75], coreIds: [0, 1, 3], cores: 4 }); await sleep(50);
  const off = await cores();
  console.log('1. cpu2 offline: the three readings are labelled Core 0, Core 1, Core 3 (cpu3 is not shown as Core 2):', JSON.stringify(off) === JSON.stringify(['50% Core 0', '25% Core 1', '75% Core 3']), JSON.stringify(off));
  console.log('   the core count still says 4 cores:', (await page.locator('#tmCpuSub').innerText()) === '4 cores');

  await send({ overall: 40, perCore: [10, 20, 30, 40], coreIds: [0, 1, 2, 3], cores: 4 }); await sleep(50);
  console.log('2. all four back online: Core 0 to Core 3 in order:', JSON.stringify(await cores()) === JSON.stringify(['10% Core 0', '20% Core 1', '30% Core 2', '40% Core 3']));

  await send({ overall: 40, perCore: [10, 20], cores: 2 }); await sleep(50);
  console.log('3. an answer without core numbers is labelled by position, as before:', JSON.stringify(await cores()) === JSON.stringify(['10% Core 0', '20% Core 1']));

  await send({ overall: 40, perCore: [10, 20], coreIds: [5], cores: 2 }); await sleep(50);
  console.log('4. a list of core numbers that does not match the readings is not used:', JSON.stringify(await cores()) === JSON.stringify(['10% Core 0', '20% Core 1']));

  console.log('no page errors:', errors.length === 0, errors.join(' | '));
  await b.close();
})();
