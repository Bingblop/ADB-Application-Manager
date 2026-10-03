// v5.8 About tab: last tab, developer + repo link, "Buy me a coffee" ($1) and a custom amount through PayPal, build / signature info, debug info.
const { chromium, PAGE, OUT } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__urls = []; window.__copied = []; window.__shared = [];
    window.__about = { versionName: '5.8-Pro', versionCode: 580, pkg: 'com.bloatware.bingblop', minSdk: 26, targetSdk: 34, debuggable: false,
      firstInstall: Date.UTC(2026, 9, 1), lastUpdate: Date.UTC(2026, 9, 3), signerCount: 1,
      signerSha256: '9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17', installer: '',
      device: 'Google Pixel <img src=x onerror=window.__pwn=1> 8', android: '15', sdk: 35, abi: 'arm64-v8a', webview: 'com.google.android.webview 130.0.6723.58' };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      loadSetting() { return ''; }, saveSetting() {},
      copyToClipboard(t) { window.__copied.push(t); },
      shareText(s, t) { window.__shared.push([s, t]); },
      openUrl(u) { window.__urls.push(u); },
      getAboutInfo() { return JSON.stringify(window.__about); },
      getAppVersion() { return JSON.stringify({ versionName: '5.8-Pro', versionCode: 580, firstInstallTime: 1, lastUpdateTime: 1 }); },
      getChangelog() { return '## v5.8-Pro (versionCode 580)\n\n- **Something new.** Details.\n'; },
      checkSelfUpdate() { window.__selfUpd = (window.__selfUpd || 0) + 1; },
      hasAllFilesAccess() { return true; }, fmList(path) { return JSON.stringify({ path, entries: [] }); },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(450);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const toast = () => page.locator('#toastMsg').innerText();
  const lastUrl = () => ev(() => window.__urls[window.__urls.length - 1] || '');
  const q = url => Object.fromEntries(new URL(url).searchParams.entries());

  // 1) The tab: last in the bar, named About
  const tabs = await page.locator('.tab-btn').allInnerTexts();
  console.log('1. About is the last tab in the top bar:', /About/.test(tabs[tabs.length - 1]), JSON.stringify(tabs));
  await page.locator('.tab-btn', { hasText: 'About' }).click(); await sleep(250);
  const s1 = await ev(() => ({ view: currentViewName(), active: document.querySelectorAll('.tab-btn.active').length, last: [...document.querySelectorAll('.tab-btn')].pop().classList.contains('active'), shown: getComputedStyle(document.getElementById('view-about')).display !== 'none' }));
  console.log('   tapping it shows the About page and highlights that tab only:', s1.view === 'about' && s1.shown && s1.active === 1 && s1.last, JSON.stringify(s1));
  console.log('   the old About card in Colors is gone (one About, not two):', (await ev(() => document.querySelectorAll('#aboutCard').length)) === 0 && (await ev(() => document.querySelectorAll('#aboutVersion').length)) === 1);

  // 2) Content: developer, repo, version
  const text = await page.locator('#view-about').innerText();
  console.log('2. it names the developer Bingblop and shows the repo:', /Bingblop/.test(text) && /github\.com\/Bingblop\/ADB-Application-Manager/.test(text));
  console.log('   it shows the version and build:', /Version 5\.8-Pro · build 580/.test(await page.locator('#aboutVersion').innerText()));
  await page.locator('#view-about .about-hero button', { hasText: 'GitHub' }).click();
  console.log('   the GitHub button opens the repo:', (await lastUrl()) === 'https://github.com/Bingblop/ADB-Application-Manager');
  await page.locator('#view-about a', { hasText: 'github.com/Bingblop/ADB-Application-Manager' }).click();
  console.log('   so does the link text:', (await lastUrl()) === 'https://github.com/Bingblop/ADB-Application-Manager');
  await page.locator('#view-about button', { hasText: 'Report a bug' }).click();
  console.log('   report a bug opens the issue form:', (await lastUrl()) === 'https://github.com/Bingblop/ADB-Application-Manager/issues/new');
  await page.locator('#view-about button', { hasText: 'Releases' }).click();
  console.log('   releases opens the latest release:', (await lastUrl()) === 'https://github.com/Bingblop/ADB-Application-Manager/releases/latest');
  await page.locator('#view-about button', { hasText: 'Bingblop on GitHub' }).click();
  console.log('   the developer profile link:', (await lastUrl()) === 'https://github.com/Bingblop');

  // 3) Buy me a coffee: one dollar to the developer's PayPal
  await ev(() => { window.__urls.length = 0; });
  await page.locator('#aboutCoffeeBtn').click(); await sleep(60);
  const u1 = await lastUrl(); const p1 = q(u1);
  console.log('3. "Buy me a coffee" opens PayPal\'s donate page for the developer:', u1.startsWith('https://www.paypal.com/donate?') && p1.business === 'bingblop@yahoo.com' && p1.no_recurring === '1', u1);
  console.log('   for exactly 1.00 US dollar:', p1.amount === '1.00' && p1.currency_code === 'USD');
  console.log('   the address is encoded (no raw @ in the link) and the item is named:', u1.includes('business=bingblop%40yahoo.com') && /coffee/i.test(p1.item_name));
  console.log('   a thank-you toast:', /PayPal/.test(await toast()));
  console.log('   the page says where the money goes:', /bingblop@yahoo\.com/.test(await page.locator('#aboutCoffeeCard').innerText()));

  // 4) Custom amount
  const give = async v => { await ev(() => { window.__urls.length = 0; }); await page.fill('#aboutAmount', v); await page.locator('#aboutDonateBtn').click(); await sleep(40); return ev(() => window.__urls.slice()); };
  for (const [typed, amount] of [['5', '5.00'], ['12.5', '12.50'], ['7,25', '7.25'], ['$ 3', '3.00'], ['0.5', '0.50'], ['0.01', '0.01'], ['100000', '100000.00'], [' 20 ', '20.00']]) {
    const urls = await give(typed);
    console.log('4. typing ' + JSON.stringify(typed) + ' donates ' + amount + ':', urls.length === 1 && q(urls[0]).amount === amount && q(urls[0]).business === 'bingblop@yahoo.com' && q(urls[0]).currency_code === 'USD', JSON.stringify(urls.map(u => q(u).amount)));
  }
  // Enter does the same
  await ev(() => { window.__urls.length = 0; });
  await page.fill('#aboutAmount', '9'); await page.press('#aboutAmount', 'Enter'); await sleep(40);
  console.log('   Enter in the box donates too:', JSON.stringify(await ev(() => window.__urls.map(u => new URL(u).searchParams.get('amount')))) === '["9.00"]');
  // rubbish
  let badOk = true; const badNotes = [];
  for (const v of ['', 'abc', '0', '0.00', '-5', '1,000', '1e3', '5.555', '.5', '5.', '100001', '1 000', '$', '9999999', '5$']) {
    const urls = await give(v);
    const marked = await ev(() => document.getElementById('aboutAmtBox').classList.contains('bad'));
    if (urls.length !== 0 || !marked) { badOk = false; badNotes.push(v); }
  }
  console.log('   anything that is not an amount opens nothing and marks the box:', badOk, JSON.stringify(badNotes));
  await page.fill('#aboutAmount', '4');
  console.log('   typing again clears the red mark:', !(await ev(() => document.getElementById('aboutAmtBox').classList.contains('bad'))));
  console.log('   the message tells what to type:', await (async () => { await give('x'); return /like 5 or 2\.50/.test(await toast()); })());
  console.log('   parseDonationAmount rejects a script-ish string:', (await ev(() => parseDonationAmount('5&business=evil@x.com'))) === null);
  console.log('   the link can not be bent through the amount: only one business= and it is the developer:', await (async () => { const urls = await give('5'); const u = new URL(urls[0]); return u.searchParams.getAll('business').length === 1 && u.searchParams.get('business') === 'bingblop@yahoo.com'; })());
  await ev(() => { document.getElementById('aboutAmount').value = ''; });

  // 5) This build
  const build = await page.locator('#aboutBuild').innerText();
  console.log('5. the build card lists package, Android, device, CPU, WebView, mode:', /com\.bloatware\.bingblop/.test(build) && /15 \(API 35\)/.test(build) && /arm64-v8a/.test(build) && /130\.0\.6723\.58/.test(build) && /Shizuku/.test(build) && /sideloaded/.test(build) && /release/.test(build), JSON.stringify(build.replace(/\s+/g, ' ').slice(0, 260)));
  console.log('   values are escaped (the device name did not become an element or run):', (await ev(() => !!window.__pwn)) === false && (await ev(() => document.querySelectorAll('#aboutBuild img').length)) === 0 && /<img/.test(build));
  const sigTxt = await page.locator('#aboutSig').innerText();
  console.log('   the official key is recognised, with the colon-separated SHA-256:', /Signed with the official release key/.test(sigTxt) && /94:61:87:0F:7C:D2/.test(sigTxt), JSON.stringify(sigTxt));
  await ev(() => { window.__about.signerSha256 = 'aa'.repeat(32); renderAbout(); });
  console.log('   another key is flagged:', /Not signed with the official release key/.test(await page.locator('#aboutSig').innerText()) && (await ev(() => !!document.querySelector('#aboutSig .about-warn'))));
  await ev(() => { window.__about.signerSha256 = '9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17'; renderAbout(); });

  // 6) Debug info
  await page.locator('#view-about button', { hasText: 'Copy debug info' }).click(); await sleep(40);
  const dbg = await ev(() => window.__copied[window.__copied.length - 1] || '');
  console.log('6. Copy debug info copies version, package, Android, device, WebView, mode and the key verdict:',
    /ADB Application Manager Pro 5\.8-Pro \(build 580\)/.test(dbg) && /Package: com\.bloatware\.bingblop/.test(dbg) && /Android: 15 \(API 35\)/.test(dbg) && /arm64-v8a/.test(dbg) && /WebView: com\.google\.android\.webview/.test(dbg) && /Working mode: .*Shizuku/.test(dbg) && /official release key/.test(dbg) && !/not the official/.test(dbg), JSON.stringify(dbg));
  await page.locator('#view-about button', { hasText: 'Share it' }).click(); await sleep(40);
  console.log('   Share it hands the same text to the share sheet:', (await ev(() => window.__shared.length)) === 1 && (await ev(() => window.__shared[0][1])) === dbg);
  await page.locator('#view-about button', { hasText: 'Copy fingerprint' }).click(); await sleep(40);
  console.log('   Copy fingerprint copies the colon form:', (await ev(() => window.__copied[window.__copied.length - 1])) === '94:61:87:0F:7C:D2:40:64:06:A1:19:F4:D3:75:33:C2:3B:B7:D1:ED:85:05:06:BA:D7:EA:3E:6B:4B:D2:6D:17');
  await page.locator('#view-about button', { hasText: 'Share the app' }).click(); await sleep(40);
  console.log('   Share the app shares the releases link:', /github\.com\/Bingblop\/ADB-Application-Manager\/releases\/latest/.test(await ev(() => window.__shared[window.__shared.length - 1][1])));
  await page.locator('#aboutCoffeeCard a', { hasText: 'Copy the PayPal address' }).click(); await sleep(40);
  console.log('   the PayPal address can be copied:', (await ev(() => window.__copied[window.__copied.length - 1])) === 'bingblop@yahoo.com');

  // 7) What's new + update
  await page.locator('#view-about button', { hasText: "What's new" }).click(); await sleep(100);
  console.log('7. What\'s new opens the release notes:', await ev(() => document.getElementById('whatsNewModal').classList.contains('show')));
  await ev(() => closeWhatsNew());
  await page.locator('#view-about button', { hasText: 'Check for update' }).click(); await sleep(150);
  console.log('   Check for update goes to the app-update card and checks:', (await ev(() => currentViewName())) === 'updates' && (await ev(() => window.__selfUpd || 0)) >= 1);

  // 8) Back from About returns to where it came from
  await ev(() => switchView('files')); await sleep(60);
  await ev(() => switchView('about')); await sleep(60);
  const back = await ev(() => { const r = handleAndroidBack(); return { r, view: currentViewName() }; });
  console.log('8. Back from About goes to the tab before it:', back.r === true && back.view === 'files', JSON.stringify(back));

  // 9) Layout at phone width, dark and light: nothing sideways, buttons reachable
  for (const scheme of ['dark', 'light']) {
    await page.emulateMedia({ colorScheme: scheme });
    await ev(() => { setAppearance(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'); switchView('about'); }); await sleep(300);
    const w = await ev(() => ({ s: document.documentElement.scrollWidth, i: window.innerWidth }));
    const cut = await ev(() => [...document.querySelectorAll('#view-about button, #view-about input')].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (r.right > window.innerWidth + 1 || r.left < -1); }).length);
    console.log('9. ' + scheme + ': no sideways scroll and no cut-off buttons at 360 px:', w.s <= w.i && cut === 0, JSON.stringify({ w, cut }));
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: OUT + '/about_' + scheme + '_1.png' });
    await page.evaluate(() => window.scrollTo(0, 700));
    await sleep(100);
    await page.screenshot({ path: OUT + '/about_' + scheme + '_2.png' });
    await page.evaluate(() => window.scrollTo(0, 1500));
    await sleep(100);
    await page.screenshot({ path: OUT + '/about_' + scheme + '_3.png' });
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
