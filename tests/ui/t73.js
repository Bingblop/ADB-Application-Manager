// v6.1 Installer: when an install worked, the result dialog has "Launch Application" and, under it, "Application Settings", above "Done". The dialog stands taller for them
// and the output box keeps the height it has without them.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

(async () => {
  const b = await chromium.launch();
  const open = async (viewport) => {
    const page = await b.newPage({ viewport });
    page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
    await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' } });
    await page.addInitScript(() => {
      window.__pkgs['content://pick/1'] = { type: 'apk', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 9e6,
        splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 9e6, isBase: true, split: '' }], signed: true, installed: false };
    });
    await page.goto(PAGE);
    await page.waitForFunction(() => typeof window.onInstallResult === 'function');
    await page.evaluate(() => switchView('installer'));
    await page.evaluate(() => pickInstallFile());
    await page.waitForFunction(() => /^Loaded/.test(document.getElementById('installPickHint').innerText));
    return page;
  };
  const geo = page => page.evaluate(() => { const r = e => { const x = e.getBoundingClientRect(); return { top: Math.round(x.top), bottom: Math.round(x.bottom), h: Math.round(x.height) }; };
    const btns = Array.from(document.querySelectorAll('#commandResultsActions button')), done = document.getElementById('commandResultsDone'), sheet = document.querySelector('#commandResultsModal .modal-sheet');
    return { list: r(document.getElementById('commandResultsList')), sheet: r(sheet), sheetScrolls: sheet.scrollHeight > sheet.clientHeight + 1, done: r(done), btns: btns.map(x => ({ t: x.innerText.trim(), ...r(x) })), actionsShown: getComputedStyle(document.getElementById('commandResultsActions')).display !== 'none', has: document.getElementById('commandResultsModal').classList.contains('has-actions'), vh: innerHeight }; });
  const longOut = 'Success\n'.repeat(60);
  // the sheet slides up for a third of a second and overshoots a little before it settles: measure it once its transition is over
  const shown = async page => { await page.waitForSelector('#commandResultsModal.show'); await page.waitForFunction(() => { const sh = document.querySelector('#commandResultsModal .modal-sheet'); return sh.getBoundingClientRect().bottom <= innerHeight + 0.5 && sh.getAnimations().length === 0; }); };
  const result = (page, ok, out) => page.evaluate(([o, t]) => window.onInstallResult(JSON.stringify({ ok: o, method: 'adb', output: t, dexopt: 'Success' })), [ok, out]);
  const close = async page => { await page.evaluate(() => closeCommandResultsModal()); await page.waitForFunction(() => !document.getElementById('commandResultsModal').classList.contains('show')); };

  for (const vp of [{ width: 360, height: 800 }, { width: 360, height: 640 }, { width: 412, height: 915 }]) {
    const tag = '[' + vp.width + 'x' + vp.height + '] ';
    const page = await open(vp);
    // a failed install: just Done, nothing else
    await result(page, false, longOut);
    await shown(page);
    const fail = await geo(page);
    check(tag + '1. a failed install shows no extra buttons', !fail.actionsShown && !fail.has && fail.btns.length === 0, JSON.stringify({ actionsShown: fail.actionsShown, has: fail.has }));
    await close(page);
    // a successful one
    await result(page, true, longOut);
    await shown(page);
    const ok = await geo(page);
    check(tag + '2. a successful install has two buttons, "Launch Application" then "Application Settings"', ok.actionsShown && ok.has && ok.btns.length === 2 && /Launch Application$/.test(ok.btns[0].t) && /Application Settings$/.test(ok.btns[1].t), JSON.stringify(ok.btns.map(x => x.t)));
    check(tag + '   they are above Done, Launch above Settings, in that order', ok.btns[0].bottom <= ok.btns[1].top && ok.btns[1].bottom <= ok.done.top && ok.list.bottom <= ok.btns[0].top, JSON.stringify({ list: ok.list.bottom, launch: ok.btns[0], settings: ok.btns[1], done: ok.done.top }));
    check(tag + '   each is a full-size button (at least 44 px tall), Done too', ok.btns.every(x => x.h >= 44) && ok.done.h >= 44, JSON.stringify(ok.btns.map(x => x.h)) + ' done ' + ok.done.h);
    check(tag + '3. the output box has the same height as in the failed install (it was not shrunk to make room)', ok.list.h === fail.list.h, ok.list.h + ' vs ' + fail.list.h);
    check(tag + '   the dialog is taller instead, by about the height of the buttons', ok.sheet.h - fail.sheet.h >= 100 || (ok.sheetScrolls && ok.sheet.h >= fail.sheet.h), 'sheet ' + fail.sheet.h + ' -> ' + ok.sheet.h + (ok.sheetScrolls ? ' (and scrolls)' : ''));
    check(tag + '   it is never taller than the screen (the sheet scrolls when the phone is short)', ok.sheet.bottom <= vp.height && ok.sheet.h <= vp.height * 0.94 + 1, 'sheet ' + ok.sheet.h + ' of ' + vp.height);
    if (vp.height >= 800) check(tag + '   on a tall enough screen everything is in view at once, Done included', !ok.sheetScrolls && ok.done.bottom <= vp.height, JSON.stringify({ doneBottom: ok.done.bottom, scrolls: ok.sheetScrolls }));
    if (vp.height === 800) await page.screenshot({ path: 'install_result_actions.png' });
    // a short output: the box is as small as its text, with and without the buttons
    await close(page);
    await result(page, false, 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]');
    await shown(page);
    const shortFail = await geo(page);
    await close(page);
    await result(page, true, 'Success');
    await shown(page);
    const shortOk = await geo(page);
    check(tag + '4. a short output keeps its height too', shortOk.list.h === shortFail.list.h && shortOk.list.h < 200, shortOk.list.h + ' vs ' + shortFail.list.h);
    check(tag + '   and the dialog grows by the buttons', shortOk.sheet.h - shortFail.sheet.h >= 100);          // (no numbers: the first dialog of a page is a pixel or two off while the emoji font loads)
    await close(page);
    // another kind of result after it carries no buttons
    await page.evaluate(() => showCommandResults('Shell Command Result', 'ls', [{ name: 'ls', pkg: 'shell', output: 'a\nb', success: true }]));
    await shown(page);
    const other = await geo(page);
    check(tag + '5. the next dialog of another kind has none of them (they are not left over)', !other.actionsShown && !other.has && other.btns.length === 0);
    await page.close();
  }

  // 6) what the buttons do
  const page = await open({ width: 360, height: 800 });
  await result(page, true, 'Success');
  await page.waitForSelector('#commandResultsModal.show');
  const toastText = () => page.locator('#toastMsg').innerText();
  await page.click('#commandResultsActions button >> nth=0');
  check('6. Launch Application opens the app that was installed through the bridge', JSON.stringify(await page.evaluate(() => window.__calls.actions)) === '["launch:com.example.app"]');
  check('   it says so in a toast and the dialog stays (no second dialog for something that worked)', /Opening Example App/.test(await toastText()) && (await page.isVisible('#commandResultsModal.show')) && (await page.locator('.modal-overlay.show').count()) === 1);
  await page.click('#commandResultsActions button >> nth=1');
  check('   Application Settings opens the app\'s Android settings page', JSON.stringify(await page.evaluate(() => window.__calls.actions)) === '["launch:com.example.app","app_settings:com.example.app"]');
  check('   the dialog is still there, so Done (or another button) can follow', await page.isVisible('#commandResultsModal.show'));
  await page.evaluate(() => { window.__actionAnswer = () => 'Error: No activities found to run, monkey aborted.'; });
  await page.click('#commandResultsActions button >> nth=0');
  check('   an app with no screen to open says so in a toast (the dialog still stays)', /can't be opened/.test(await toastText()) && /Example App/.test(await toastText()) && (await page.isVisible('#commandResultsModal.show')), await toastText());
  await page.evaluate(() => { window.__actionAnswer = () => 'Events injected: 1'; });
  await page.click('#commandResultsActions button >> nth=0');
  check('   what the phone answers when it did launch (monkey: "Events injected: 1") counts as opened', /Opening Example App/.test(await toastText()), await toastText());
  await page.click('#commandResultsDone');
  await page.waitForFunction(() => !document.getElementById('commandResultsModal').classList.contains('show'));
  check('   Done closes the dialog', true);
  // the tab keeps working after the dialog: the next install result shows it again
  await result(page, true, 'Success');
  await page.waitForSelector('#commandResultsModal.show');
  check('   and a second successful install shows the buttons again', (await page.locator('#commandResultsActions button').count()) === 2);
  await page.close();

  // 7) the answer to an install comes after another package was opened: the dialog and its buttons are about the one that was installed
  const p3 = await open({ width: 360, height: 800 });
  await p3.evaluate(() => { window.__pkgs['content://pick/2'] = { type: 'apk', pkg: 'com.other.app', label: 'Other App', versionName: '1', versionCode: 1, minSdk: 26, targetSdk: 34, totalSize: 4e6,
    splits: [{ path: '/cache/other.apk', name: 'other.apk', size: 4e6, isBase: true, split: '' }], signed: true, installed: false }; });
  await p3.evaluate(() => runInstall());
  check('7. an install is started for the open package (Example App)', (await p3.evaluate(() => window.__calls.install.length)) === 1 && (await p3.evaluate(() => window.__calls.install[0].pkg)) === 'com.example.app');
  await p3.evaluate(() => onInstallFilePicked('content://pick/2'));
  await p3.waitForFunction(() => document.getElementById('installPkgLabel').innerText === 'Other App');
  await result(p3, true, 'Success');
  await p3.waitForSelector('#commandResultsModal.show');
  check('   the package that is open now is another one (Other App), yet the dialog is about the one installed', (await p3.locator('#commandResultsTitle').innerText()) === 'Install com.example.app' && /Example App/.test(await p3.locator('#commandResultsList').innerText()), await p3.locator('#commandResultsTitle').innerText());
  await p3.click('#commandResultsActions button >> nth=0');
  await p3.click('#commandResultsActions button >> nth=1');
  check('   Launch Application and Application Settings act on the installed one, not on the one that was opened since', JSON.stringify(await p3.evaluate(() => window.__calls.actions)) === '["launch:com.example.app","app_settings:com.example.app"]', JSON.stringify(await p3.evaluate(() => window.__calls.actions)));
  await p3.close();

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
