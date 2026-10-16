// v7.14 Permissions, the Read Logs row (V2-3 of the #234 review): the app holds READ_LOGS but no part of it reads the log by itself (Live Logcat and its
// recordings go through the working mode), so the sheet and the Help Guide must not promise that it reads every app's log "itself, without asking the shell".
// The row says what the permission is and that Live Logcat does not use it, in English and translated (German); the guide's row says nothing changes without it.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const NONE = { files: false, usage: false, overlay: false, install_unknown: false, restricted_settings: false, bluetooth: false, notifications: false,
  storage_legacy: false, secure_settings: false, dump: false, read_logs: false };
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.dismiss());
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v62: '1', perm_adb_offer_v714: '1' }, perm: NONE, mode: { priv: true } });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.openAppPermissions === 'function');
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const why = async () => { await ev(() => openAppPermissions()); await page.waitForTimeout(200); const r = await ev(() => { const row = document.querySelector('#permModal .pm-row[data-perm="read_logs"]'); return row ? row.querySelector('.pm-why').innerText.replace(/\s+/g, ' ').trim() : null; }); await ev(() => document.getElementById('permModal').classList.remove('show')); return r; };

  const en = await why();
  check('1. the Read Logs row says what the permission is', /^Android's permission to read the system log \(logcat\) of every app\./.test(en || ''), en);
  check('   and that Live Logcat does not use it but reads the log through the working mode', /Live Logcat does not use it: it reads the log through the working mode\./.test(en || ''), en);
  check('   it no longer promises that the app reads the log itself, without the shell', !/itself|without asking the shell/.test(en || ''), en);

  await ev(() => I18N.set('de')); await page.waitForTimeout(800);
  const de = await why();
  check('2. German: the row is translated and names Live-Logcat', !!de && de !== en && /Live-Logcat nutzt sie nicht/.test(de) && !/selbst zu lesen/.test(de), de);
  await ev(() => I18N.set('en')); await page.waitForTimeout(300);

  const guide = fs.readFileSync(path.join(REPO, 'assets', 'guide.js'), 'utf8');
  const row = (guide.match(/<tr><td>Read Logs<\/td>.*?<\/tr>/) || [''])[0];
  check('3. the Help Guide row says nothing in the app depends on Read Logs', /Nothing in the app depends on it/.test(row) && /Live Logcat needs a working mode either way/.test(row), row.slice(0, 200));
  check('   and no longer says the log viewer sees this app\'s own lines without a working mode', !/sees only this app's own lines/.test(row) && !/itself, without asking the shell/.test(row));

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
