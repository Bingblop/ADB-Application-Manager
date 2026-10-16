// v7.14: Live Logcat is its own tab, so no text sends people to "Console, Live Logcat" any more (V4-2 of the #234 review): the empty Recordings list
// and the About tip name the Live Logcat tab, in English and translated (German), and the Help Guide says the same.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript);
  await page.addInitScript(() => {
    const base = window.AndroidBridge;
    base.logRecStart = () => JSON.stringify({ ok: false });
    base.logRecStatus = () => JSON.stringify({ ok: true, running: false, name: '', lines: 0, bytes: 0, startedAt: 0, stopReason: '', lastError: '' });
    base.logRecList = () => JSON.stringify({ ok: true, items: [] });
  });
  await page.goto(PAGE); await page.waitForTimeout(700);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  const listText = async () => { await ev(() => { switchView('logcat'); lcRecListOpen(); }); await sleep(150); const t = await ev(() => document.getElementById('lcRecList').innerText.replace(/\s+/g, ' ').trim()); await ev(() => lcRecListClose()); return t; };
  const tips = () => ev(() => [...document.querySelectorAll('.about-list li')].map(li => li.innerText.replace(/\s+/g, ' ').trim()));

  const en = await listText();
  check('1. the empty Recordings list points to the Live Logcat tab', /Tap Record to file in Live Logcat to make one\./.test(en), en);
  check('   and not to a Live Logcat inside Console', !/Console/.test(en), en);
  const tipsEn = await tips();
  check('2. the About tip names Live Logcat on its own', tipsEn.some(t => /^Live Logcat can show one app only\. Tap Logs in an app's ⋯ menu\.$/.test(t)), JSON.stringify(tipsEn.filter(t => /Logcat/.test(t))));
  check('   no About tip says Console → Live Logcat', !tipsEn.some(t => /Console\s*→\s*Live Logcat/.test(t)));

  await ev(() => I18N.set('de')); await sleep(800);
  const de = await listText();
  check('3. German: the hint is translated and names Live-Logcat, not the Konsole', /Live-Logcat/.test(de) && !/Konsole/.test(de) && !/Tap Record/.test(de), de);
  const tipsDe = await tips();
  check('   German: the About tip is translated and names Live-Logcat alone', tipsDe.some(t => /^Live-Logcat kann nur eine App anzeigen\./.test(t)) && !tipsDe.some(t => /Konsole\s*→\s*Live-Logcat/.test(t)), JSON.stringify(tipsDe.filter(t => /Logcat/.test(t))));
  await ev(() => I18N.set('en')); await sleep(300);

  const guide = fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8');
  check('4. the Help Guide names the Live Logcat tab for Logs in an app\'s menu', /<b>Logs<\/b> in an app's menu \(the Live Logcat tab\)/.test(guide));
  check('   and nowhere puts Live Logcat inside Console', !/\(Console, Live Logcat\)|in Console, Live Logcat|Console → Live Logcat/.test(guide));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
