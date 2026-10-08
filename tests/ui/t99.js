// About tab: Device Specs, under Handy to Know - hardware, software, system, battery, network, camera and
// sensors, refreshed when the tab opens and by its own Refresh button.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__specsCalls = 0;
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      getAboutInfo() { return JSON.stringify({ versionName: '7.9.6', versionCode: 796, pkg: 'com.bloatware.bingblop', android: '14', sdk: 34, device: 'Pixel 8', abi: 'arm64-v8a' }); },
      getDeviceSpecs() {
        window.__specsCalls++;
        return JSON.stringify({
          ok: true,
          hardware: {
            model: 'Pixel 8', manufacturer: 'Google', brand: 'google',
            arch: 'aarch64', abi: 'arm64-v8a', soc: 'Google Tensor G3',
            clusters: [{ cores: 4, minHz: 300000000, curHz: 1800000000, maxHz: 2200000000 }, { cores: 4, minHz: 300000000, curHz: 2400000000, maxHz: 2910000000 }],
            ramTotalKb: 8000000,
            vulkanSupported: true, vulkanApi: '1.3.0', glesVersion: '3.2',
            screenWidthPx: 1080, screenHeightPx: 2400, densityDpi: 420, refreshRateHz: 120,
            storageTotalBytes: 128000000000, storageFreeBytes: 64000000000,
          },
          software: { androidRelease: '14', sdk: 34, securityPatch: '2026-09-05', buildId: 'UQ1A.240205.004', bootloader: 'g14-boot-1.0', kernel: 'Linux version 6.1.0-android14-g1234 (build@host) #1 SMP PREEMPT' },
          system: { uptimeMs: 5445000, locale: 'en-US', timezone: 'America/New_York' },
          battery: { percent: 83, status: 2, plugged: 2, technology: 'Li-ion', health: 2, cycleCount: 47 },
          network: { type: 'WIFI', connected: true, interfaces: ['wlan0', 'rmnet0'] },
          cameras: [{ facing: 'back', megapixels: 50.3, flash: true }, { facing: 'front', megapixels: 10.5, flash: false }],
          sensors: [{ name: 'Accelerometer', vendor: 'Google' }, { name: 'Gyroscope', vendor: 'Google' }, { name: 'Proximity Sensor', vendor: 'Google' }],
        });
      },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const body = () => page.locator('#deviceSpecsBody');

  console.log('1. Handy to know sits under the Device Specs section:', await page.evaluate(() => {
    const cards = [...document.querySelectorAll('#view-about .color-card-title')].map(e => e.innerText);
    const handy = cards.indexOf('Handy to know'), specs = cards.indexOf('Device Specs');
    return specs >= 0 && handy === specs + 1;
  }));

  await page.evaluate(() => switchView('about')); await sleep(150);
  console.log('2. opening the tab fetches it on its own (no Refresh press needed):', await page.evaluate(() => window.__specsCalls) === 1);
  const txt = await body().innerText();
  console.log('   Hardware shows model, SoC, cores, RAM, screen and storage:', /Google Pixel 8/.test(txt) && /Google Tensor G3/.test(txt) && /8 \(2 clusters\)/.test(txt) && /7\.6 GB/.test(txt) && /1080 × 2400/.test(txt) && /120 Hz/.test(txt) && /59\.6 GB free of 119 GB/.test(txt));
  console.log('   Software shows Android version, patch and build:', /14 \(API 34\)/.test(txt) && /2026-09-05/.test(txt) && /UQ1A\.240205\.004/.test(txt));
  console.log('   Battery shows level, status and cycles:', /83%/.test(txt) && /Charging/.test(txt) && /47/.test(txt));
  console.log('   Network shows the connection and interfaces:', /WIFI/.test(txt) && /wlan0, rmnet0/.test(txt));
  console.log('   Camera lists each one with megapixels and flash:', /Camera 1 \(back\)/.test(txt) && /50\.3 MP/.test(txt) && /Flash/.test(txt) && /Camera 2 \(front\)/.test(txt));
  console.log('3. Sensors are listed by name and vendor, with a count:', /SENSORS \(3\)/.test(txt) && (await page.locator('#deviceSpecsBody .tm-net-row').count()) === 3 && /Accelerometer/.test(txt) && /Gyroscope/.test(txt));

  await page.evaluate(() => switchView('apps')); await page.evaluate(() => switchView('about')); await sleep(150);
  console.log('4. leaving and coming back refreshes it again:', await page.evaluate(() => window.__specsCalls) === 2);

  await page.click('#deviceSpecsBody').catch(() => {});   // no-op guard; the real target is the Refresh button below
  await page.locator('.color-card', { has: page.locator('.color-card-title', { hasText: 'Device Specs' }) }).locator('button', { hasText: 'Refresh' }).click();
  await sleep(100);
  console.log('5. the Refresh button fetches it again by hand:', await page.evaluate(() => window.__specsCalls) === 3);

  // ---------------------------------------------------------------- no AndroidBridge method: fails quietly
  await page.evaluate(() => { delete window.AndroidBridge.getDeviceSpecs; renderDeviceSpecs(); });
  console.log('6. without the bridge method, a plain message instead of breaking:', (await body().innerText()).includes('Needs the app build'));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
