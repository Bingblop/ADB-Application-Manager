// v7.14.0: the page's prompt / confirm / alert are drawn by the app (PageDialogs.java) with a real title instead of 'The page at "file://" says:'.
// nextDialogTitle(title) hands the title of the very next dialog to the bridge (setNextDialogTitle) right before it; applyTheme sends the colours and the
// words of the dialogs (setDialogTheme). The mock bridge plays the app's part: a title is used by the next dialog only. In a browser without the bridge
// method nothing changes.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sheet_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, colorScheme: 'dark' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { dark: true });
  // The app's side: a named title waits for the next dialog and is used once. The dialogs themselves answer from a queue (before i18n.js wraps them).
  await page.addInitScript(() => {
    window.__dlg = []; window.__titles = []; window.__themes = []; window.__answers = []; window.__pending = null;
    const B = window.AndroidBridge;
    B.setNextDialogTitle = t => { window.__titles.push(t); window.__pending = t; };
    B.setDialogTheme = j => { window.__themes.push(JSON.parse(j)); };
    B.checkForUpdates = () => {}; B.setGithubToken = () => {}; B.hasGithubToken = () => false; B.setUpdateSource = () => '{}';
    const take = (kind, m) => { window.__dlg.push({ kind, m: String(m), title: window.__pending }); window.__pending = null; };
    window.prompt = (m, d) => { take('prompt', m); return window.__answers.length ? window.__answers.shift() : d; };
    window.confirm = m => { take('confirm', m); return true; };
    window.alert = m => { take('alert', m); };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // the colours
  let th = await ev(() => window.__themes[window.__themes.length - 1]);
  check('1. applyTheme sends the colours and the words of the dialogs', !!th && /^#/.test(th.accent) && /^#/.test(th.card) && /^#/.test(th.text) && !!th.surface && !!th.muted && !!th.onAccent && !!th.border && th.radius === 24 && th.light === false
    && th.ok === 'OK' && th.cancel === 'Cancel' && th.confirmTitle === 'Confirm' && th.promptTitle === 'Enter a value' && th.alertTitle === 'ADB/Root Full System Toolbox', Object.keys(th || {}).join());
  check('   they are the colours on the page', await ev(t => { const cs = getComputedStyle(document.documentElement); return cs.getPropertyValue('--bg-card').trim() === t.card && cs.getPropertyValue('--accent').trim() === t.accent; }, th));
  await ev(() => setAppearance('light')); await page.waitForTimeout(200);
  const lt = await ev(() => window.__themes[window.__themes.length - 1]);
  check('   a light appearance sends light colours', lt.light === true && lt.card !== th.card && lt.text !== th.text);
  await ev(() => setAppearance('dark')); await page.waitForTimeout(200);

  // each prompt gets its title, set just before it
  const run = async (fn, answers) => {
    await ev(a => { window.__answers = a.slice(); window.__dlg = []; window.__titles = []; }, answers || []);
    await ev(fn); await page.waitForTimeout(60);
    return ev(() => ({ dlg: window.__dlg.slice(), titles: window.__titles.slice(), pending: window.__pending }));
  };
  const expect = (label, r, kind, title) => check(label, r.dlg.length >= 1 && r.dlg[0].kind === kind && r.dlg[0].title === title && r.titles[0] === title && r.pending === null, JSON.stringify(r.dlg.map(d => [d.kind, d.title])));

  let r = await run(() => mtSave(), ['Mine']);
  expect('2. Save current theme: the prompt is titled "Save theme"', r, 'prompt', 'Save theme');
  r = await run(() => mtRename(0), ['Mine 2']);
  expect('   Rename: "Rename theme"', r, 'prompt', 'Rename theme');
  r = await run(() => mtUpdate(0));
  expect('   Update: the confirm is titled "Update theme"', r, 'confirm', 'Update theme');
  r = await run(() => { document.getElementById('bcInput').value = 'pm list packages -d'; bcSave(); }, ['Disabled']);
  expect('3. Batch command: "Save command"', r, 'prompt', 'Save command');
  r = await run(() => bcRename(0), ['Off']);
  expect('   "Rename command"', r, 'prompt', 'Rename command');
  r = await run(() => bcDeleteSaved(0));
  expect('   "Delete command"', r, 'confirm', 'Delete command');
  r = await run(() => { bopsPick.set('CAMERA', 'ignore'); bopsSavePreset(); }, ['No camera']);
  expect('4. Batch Ops: "Save preset"', r, 'prompt', 'Save preset');
  r = await run(() => bopsRename(0), ['Quiet']);
  expect('   "Rename preset"', r, 'prompt', 'Rename preset');
  r = await run(() => bopsDeletePreset(0));
  expect('   "Delete preset"', r, 'confirm', 'Delete preset');
  r = await run(() => { uadSelected.add('com.example.one'); saveUadSelectionToList(); }, ['']);
  expect('5. Debloater: "Name this list"', r, 'prompt', 'Name this list');
  r = await run(() => setGithubTokenUI(), ['']);
  expect('6. Updater: "GitHub token"', r, 'prompt', 'GitHub token');
  r = await run(() => setUpdateSourceUI('com.example.one', 'One'), ['']);
  expect('   "Update source"', r, 'prompt', 'Update source');
  r = await run(() => mtDelete(0));
  expect('7. Delete a theme: "Delete theme"', r, 'confirm', 'Delete theme');

  // a dialog without a named title gets none from the page (the app then takes it from the message), and a title does not linger
  r = await run(() => { nextDialogTitle('Delete theme'); confirm('Something else?'); confirm('And this?'); });
  check('8. a title is used by the next dialog only', r.dlg.length === 2 && r.dlg[0].title === 'Delete theme' && r.dlg[1].title === null, JSON.stringify(r.dlg));
  r = await run(() => { nextDialogTitle(''); nextDialogTitle(null); confirm('x'); });
  check('   an empty title is not sent', r.titles.length === 0 && r.dlg[0].title === null);

  // without the bridge method (a plain browser): nothing breaks and the dialogs come as before
  r = await run(() => { delete window.AndroidBridge.setNextDialogTitle; window.AndroidBridge.setDialogTheme = undefined; applyTheme(false); mtSave(); }, ['Plain']);
  check('9. without the bridge method the helper does nothing and the prompt still comes', r.dlg.length === 1 && r.dlg[0].kind === 'prompt' && r.titles.length === 0);
  check('   and the theme was saved under the typed name', await ev(() => mtLoad().some(t => t.name === 'Plain')));

  check('10. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
