// The app sheet's date line ("Installed Aug 29, 2025 • Updated Sep 30, 2026") is one sentence of the dictionary in every language. The page used to
// join "Installed …" and "Updated …" itself, so the dictionary matched only "Installed {0}" and the rest stayed in English ("Installiert: 29. Aug.
// 2025 • Updated 30. Sept. 2026"). Checked in all 13 translations and in English: the line is the dictionary's sentence with both dates filled in and
// no English word is left; an app that was never updated shows "Installed …" alone, and one with only an update time shows "Last updated …".
const { chromium, PAGE } = require('./lib/pw');
const sheet = require('./lib/sheet_mock.js');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra !== undefined ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(sheet.initScript, {});
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  const PKG = 'com.sec.android.app.sbrowser';
  const langs = await ev(() => I18N.langs.map(l => l.code));
  check('the page knows 14 languages (English and 13 translations)', langs.length === 14 && langs.includes('en'), JSON.stringify(langs));

  // what the line must read in the language that is on: the dictionary's sentence with the dates as formatDate writes them in that language
  const expected = (key, dates) => ev(([key, dates]) => {
    const d = (window.__LANGS || {})[I18N.code];
    let tpl = key;
    if (I18N.code !== 'en') { const e = d && (d.p || []).find(x => x[0] === key); tpl = e ? e[1] : null; }
    if (tpl === null) return null;
    return tpl.replace(/\{(\d+)\}/g, (m, n) => formatDate(dates[+n]));
  }, [key, dates]);
  const line = () => ev(() => { const el = document.getElementById('sheetDates'); return { text: el.innerText.trim(), shown: getComputedStyle(el).display !== 'none' }; });
  const day = 864e5, now = await ev(() => Date.now());
  const inst = now - 400 * day, upd = now - 3 * day;

  for (const code of langs) {
    await ev(c => I18N.set(c), code); await page.waitForTimeout(150);
    await ev(p => { closeInspector && closeInspector(); }, PKG).catch(() => {});
    await ev(p => openInspector(p), PKG); await page.waitForTimeout(500);
    let l = await line();
    const want = await expected('Installed {0} • Updated {1}', [inst, upd]);
    check(code + ': installed and updated: the line is the dictionary\'s sentence with both dates', want !== null && l.shown && l.text === want, JSON.stringify([l.text, want]));
    if (code !== 'en') check(code + ':   no English word is left on it', !/\b(Installed|Updated)\b/.test(l.text), l.text);
    // never updated (the update time is the install time): the install date alone
    await ev(([a]) => renderInspectorDates({ firstInstallTime: a, lastUpdateTime: a }), [inst]); await page.waitForTimeout(120);
    l = await line();
    const want2 = await expected('Installed {0}', [inst]);
    check(code + ': never updated: only the install date', l.shown && l.text === want2, JSON.stringify([l.text, want2]));
    // an update time without an install time: its own sentence (not "Updated {0}", which other places use for "Updated <app name>")
    await ev(([u]) => renderInspectorDates({ firstInstallTime: 0, lastUpdateTime: u }), [upd]); await page.waitForTimeout(120);
    l = await line();
    const want3 = await expected('Last updated {0}', [upd]);
    check(code + ': only an update time: "Last updated" with the date', want3 !== null && l.shown && l.text === want3, JSON.stringify([l.text, want3]));
    await ev(() => renderInspectorDates({})); await page.waitForTimeout(60);
    check(code + ': no dates: the line is hidden', !(await line()).shown);
    await ev(() => closeInspector()); await page.waitForTimeout(300);
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
})();
