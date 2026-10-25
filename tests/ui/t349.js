// About tab: Self-test. A card between "This build" and the Authorization Manager runs the app's read-only checks (bridge runSelfTest),
// shows progress and one line per check with a PASS / FAIL / INFO / SKIP word, and offers the English report: Copy report, and Report on
// GitHub (copies the report, opens the pre-filled phone bug report form). Rows come from the app and are shown as text, never as HTML.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.__calls = { run: [], copy: [], open: [] };
    window.__runAnswer = 'started';
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true }); },
      getAboutInfo() { return JSON.stringify({ versionName: '7.13.3', versionCode: 857, pkg: 'com.bloatware.bingblop', android: '14', sdk: 34, device: 'Pixel 8', abi: 'arm64-v8a' }); },
      getDeviceSpecs() { return '{}'; },
      copyToClipboard(t) { window.__calls.copy.push(t); },
      openUrl(u) { window.__calls.open.push(u); },
      runSelfTest(id) { window.__calls.run.push(id); return window.__runAnswer; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);
  await ev(() => switchView('about')); await sleep(200);

  const REPORT = '### Self-test report\n| Check | Result | Detail |\n|---|---|---|\n| List all packages | PASS | 212 packages |\n';
  const URL = 'https://github.com/Bingblop/ADB-Application-Manager/issues/new?template=phone-bug-report.yml&title=x&mode=Shizuku';
  const payload = (over) => JSON.stringify(Object.assign({
    rows: [
      { id: 'identity', group: 'Access', title: 'Who the commands run as', status: 'pass', detail: 'shell (uid 2000)' },
      { id: 'pm-list', group: 'Packages', title: 'List all packages', status: 'pass', detail: '212 packages' },
      { id: 'dumpsys-package', group: 'Packages', title: "Read one app's package record", status: 'fail', detail: 'Permission Denial: <img src=x onerror=window.__pwned=1>' },
      { id: 'adb-wifi', group: 'Settings', title: 'Wireless debugging switch', status: 'info', detail: 'on' },
      { id: 'appops', group: 'Packages', title: "Read one app's App Ops", status: 'skip', detail: 'needs ADB, Wireless Debugging, Shizuku or Root' },
    ], passed: 2, failed: 1, info: 1, skipped: 1, report: REPORT, issueUrl: URL }, over || {}));
  const disabled = id => ev(i => document.getElementById(i).disabled, id);
  const txt = id => ev(i => document.getElementById(i).innerText.trim(), id);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);

  // ---- where it sits and how it starts
  check('the card sits between "This build" and the Authorization Manager', await ev(() => {
    const t = [...document.querySelectorAll('#view-about .color-card-title')].map(e => e.innerText);
    const i = t.indexOf('Self-test');
    return i > 0 && t[i - 1] === 'This build' && t[i + 1] === 'Authorization Manager';
  }));
  check('it says nothing is changed and what the report leaves out', /Nothing on the phone is changed/.test(await txt('selfTestCard')) && /no serial number, IP address, account, key or setting value/.test(await txt('selfTestCard')));
  check('before a run: Run is on, Copy report and Report on GitHub are off', !(await disabled('selfTestRun')) && (await disabled('selfTestCopy')) && (await disabled('selfTestIssue')));

  // ---- a run: progress, then rows
  await page.click('#selfTestRun'); await sleep(100);
  const id = await ev(() => window.__calls.run[0]);
  check('Run asks the app once, with a run id', (await ev(() => window.__calls.run.length)) === 1 && /^st\d+$/.test(id), id);
  check('while it runs: Run is off and the line says so', (await disabled('selfTestRun')) && (await txt('selfTestSum')) === 'Running…');
  await ev(i => window.onSelfTestStep(i, 3, 28, 'x'), id);
  check('progress: "Running check 3 of 28…"', (await txt('selfTestSum')) === 'Running check 3 of 28…', await txt('selfTestSum'));
  await ev(() => window.onSelfTestStep('other-run', 9, 28, 'x'));
  check('progress from another run is ignored', (await txt('selfTestSum')) === 'Running check 3 of 28…');
  await ev(() => window.onSelfTestDone('other-run', '{"rows":[]}'));
  check('an answer from another run is ignored', (await disabled('selfTestRun')) && (await ev(() => document.querySelectorAll('#selfTestRows .st-row').length)) === 0);

  await ev(([i, p]) => window.onSelfTestDone(i, p), [id, payload()]); await sleep(100);
  check('the summary counts the verdicts', (await txt('selfTestSum')) === '2 passed, 1 failed, 1 info, 1 skipped', await txt('selfTestSum'));
  const rows = await ev(() => [...document.querySelectorAll('#selfTestRows .st-row')].map(r => ({ title: r.querySelector('.st-t').firstChild.textContent, tag: r.querySelector('.st-tag').innerText, cls: r.querySelector('.st-tag').className })));
  check('one row per check, in order, each with its word', rows.length === 5 && rows.map(r => r.tag).join(',') === 'PASS,PASS,FAIL,INFO,SKIP', JSON.stringify(rows.map(r => r.tag)));
  check('each word has its own colour class', rows.map(r => r.cls.replace('st-tag ', '')).join(',') === 'st-pass,st-pass,st-fail,st-info,st-skip');
  const groups = await ev(() => [...document.querySelectorAll('#selfTestRows .st-group')].map(g => g.textContent));
  check('a heading starts each run of checks of one group', groups.join(',') === 'Access,Packages,Settings,Packages', groups.join(','));
  check('a detail is shown under its title', await ev(() => document.querySelectorAll('#selfTestRows .st-row')[0].querySelector('.st-d').innerText) === 'shell (uid 2000)');
  check('text from the phone is text, not HTML', (await ev(() => document.querySelectorAll('#selfTestRows img').length)) === 0 && !(await ev(() => window.__pwned)));
  check('the rows are not translated (they are the English report)', await ev(() => document.getElementById('selfTestRows').getAttribute('translate')) === 'no');
  check('after a run: all three buttons are on', !(await disabled('selfTestRun')) && !(await disabled('selfTestCopy')) && !(await disabled('selfTestIssue')));
  check('the card fits the screen (no sideways scroll)', await ev(() => document.documentElement.scrollWidth <= window.innerWidth + 1));

  // ---- copy, and report on GitHub
  await page.click('#selfTestCopy'); await sleep(50);
  check('Copy report copies the whole report', await ev(() => window.__calls.copy[window.__calls.copy.length - 1]) === REPORT);
  await ev(() => { window.__calls.copy.length = 0; });
  await page.click('#selfTestIssue'); await sleep(100);
  check('Report on GitHub copies the report first', await ev(() => window.__calls.copy.length === 1 && window.__calls.copy[0]) === REPORT);
  check('and opens the pre-filled form address the app gave', await ev(() => window.__calls.open[0]) === URL);
  check('and says where to paste', /Paste it into the report box/.test(await toast()), await toast());

  // ---- a second run replaces the first
  await page.click('#selfTestRun'); await sleep(80);
  const id2 = await ev(() => window.__calls.run[1]);
  check('a second run has a new id and clears the old rows and buttons', id2 !== id && (await ev(() => document.querySelectorAll('#selfTestRows .st-row').length)) === 0 && (await disabled('selfTestCopy')) && (await disabled('selfTestIssue')));
  await ev(([i, p]) => window.onSelfTestDone(i, p), [id, payload({ passed: 9 })]);
  check('the first run\'s late answer does not come back', (await disabled('selfTestRun')) && (await ev(() => document.querySelectorAll('#selfTestRows .st-row').length)) === 0);

  // ---- an error from the app
  await ev(([i]) => window.onSelfTestDone(i, JSON.stringify({ error: 'boom' })), [id2]); await sleep(80);
  check('an error answer: a message, nothing half-drawn, Run is on again', /could not run: boom/.test(await toast()) && !(await disabled('selfTestRun')) && (await disabled('selfTestCopy')) && (await txt('selfTestSum')) === '', await toast());
  await ev(() => { window.__runAnswer = 'busy'; });
  await page.click('#selfTestRun'); await sleep(80);
  check('"busy" from the app: a message, and Run stays on', /already running/.test(await toast()) && !(await disabled('selfTestRun')), await toast());
  await ev(() => { window.__runAnswer = 'error: the app is closing'; });
  await page.click('#selfTestRun'); await sleep(80);
  check('"error: ..." from the app: the reason is shown', /could not run: the app is closing/.test(await toast()) && !(await disabled('selfTestRun')), await toast());
  await ev(() => { window.__runAnswer = 'started'; });
  await ev(() => window.onSelfTestDone(selfTestId, 'not json')); await sleep(60);
  check('an unreadable answer does not break the page', /The self-test could not run\./.test(await toast()) && !(await disabled('selfTestRun')));

  // ---- a build without the bridge method
  await ev(() => { delete window.AndroidBridge.runSelfTest; });
  await page.click('#selfTestRun'); await sleep(80);
  check('without the bridge method: a plain message', /needs the app build/.test(await toast()) && !(await disabled('selfTestRun')));

  // ---- narrow screen
  await page.setViewportSize({ width: 320, height: 700 });
  await ev(() => { window.AndroidBridge.runSelfTest = id => { window.__calls.run.push(id); return 'started'; }; });
  await page.click('#selfTestRun'); await sleep(60);
  await ev(p => window.onSelfTestDone(selfTestId, p), payload()); await sleep(80);
  check('320px: no sideways scroll with the rows shown', await ev(() => document.documentElement.scrollWidth <= window.innerWidth + 1));

  // ---- every language: the card's words come from the dictionary (rows and the report stay English on purpose)
  const langs = await ev(() => I18N.langs.map(l => l.code));
  check('the page knows 14 languages (English and 13 translations)', langs.length === 14 && langs.includes('en'), JSON.stringify(langs));
  await page.setViewportSize({ width: 360, height: 800 });
  const want = (key, args) => ev(([key, args]) => {
    const d = (window.__LANGS || {})[I18N.code];
    let t = key;
    if (I18N.code !== 'en') {
      const x = d && d.x && d.x[key];
      const pe = d && (d.p || []).find(e => e[0] === key);
      t = x !== undefined ? x : (pe ? pe[1] : null);
    }
    return t === null ? null : t.replace(/\{(\d+)\}/g, (m, n) => args[+n]);
  }, [key, args || []]);
  for (const code of langs) {
    await ev(c => I18N.set(c), code); await sleep(150);
    await ev(() => { selfTestData = null; selfTestButtons(false); document.getElementById('selfTestRows').textContent = ''; document.getElementById('selfTestSum').textContent = ''; });
    await ev(() => { window.AndroidBridge.runSelfTest = id => { window.__calls.run.push(id); return 'started'; }; });
    await ev(() => selfTestRun()); await sleep(60);
    await ev(() => window.onSelfTestStep(selfTestId, 4, 28, 'x')); await sleep(80);
    const stepLine = await txt('selfTestSum');
    check(code + ': progress line is the dictionary\'s sentence', stepLine === await want('Running check {0} of {1}\u2026', ['4', '28']), stepLine);
    await ev(p => window.onSelfTestDone(selfTestId, p), payload()); await sleep(120);
    check(code + ': title, buttons and subtitle are the dictionary\'s words', await ev(() => document.querySelector('#selfTestCard .color-card-title').innerText) === await want('Self-test')
      && await txt('selfTestRun') === await want('Run self-test') && await txt('selfTestIssue') === await want('Report on GitHub') && await txt('selfTestCopy') === await want('Copy report')
      && (await txt('selfTestCard')).includes((await want('Runs about 30 read-only checks on this phone in the current working mode, so a bug report or a phone test can say what really works. Nothing on the phone is changed. The report is written in English and holds no serial number, IP address, account, key or setting value.')).slice(0, 40)));
    check(code + ': summary is the dictionary\'s sentence with the counts', await txt('selfTestSum') === await want('{0} passed, {1} failed, {2} info, {3} skipped', ['2', '1', '1', '1']), await txt('selfTestSum'));
    if (code !== 'en') check(code + ':   no English word is left on the title, buttons and summary', !/\b(Self-test|Run|Report|passed|failed|skipped|Running)\b/i.test(await txt('selfTestSum') + ' ' + await txt('selfTestRun') + ' ' + await txt('selfTestIssue')));
    check(code + ': the rows stay English (they are the report)', (await ev(() => document.querySelectorAll('#selfTestRows .st-t')[1].firstChild.textContent)) === 'List all packages');
    check(code + ': fits 360px', await ev(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  }
  await ev(() => I18N.set('en'));

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
