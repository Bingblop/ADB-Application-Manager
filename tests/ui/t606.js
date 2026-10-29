// v7.18.3 (the owner's request): with a VirusTotal API key saved, Settings' key field is not blank: it holds the key as dots (a password
// field), so it is plain that a key is entered. With no key it is empty with its placeholder; typing a key and clearing it still work, and
// the field shows the saved key again each time Settings opens (also after the key was changed elsewhere).
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async kv => {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv), log: '' });
    await page.goto(PAGE); await page.waitForTimeout(400);
    return page;
  };
  const KEY = 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90';
  let page = await open({ vt_key: JSON.stringify(KEY) });          // the app keeps every setting as JSON
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(() => switchView('prefs')); await page.waitForTimeout(200);
  const f1 = await ev(() => { const i = document.getElementById('vtKeyInput'); return { type: i.type, len: i.value.length, same: i.value === vtKey() }; });
  check('1. with a saved key, Settings\' key field holds it as dots (a password field, not blank)', f1.type === 'password' && f1.len === 64 && f1.same, JSON.stringify(f1));
  check('   the status says a key is entered', /A key is entered|entered and approved/.test(await ev(() => document.getElementById('vtKeyStatus').innerText)));
  await ev(() => { vtClearKey(); switchView('apps'); }); await page.waitForTimeout(100);
  await ev(() => switchView('prefs')); await page.waitForTimeout(100);
  check('2. after Remove the field is empty again, with its placeholder', await ev(() => document.getElementById('vtKeyInput').value === '' && !!document.getElementById('vtKeyInput').placeholder));
  await ev(() => { vtKeyCache = 'zzz999'; switchView('apps'); }); await page.waitForTimeout(80);
  await ev(() => switchView('prefs')); await page.waitForTimeout(100);
  check('   opening Settings again shows the key it has now', await ev(() => document.getElementById('vtKeyInput').value === 'zzz999'));
  await page.close();
  page = await open({});
  await page.evaluate(() => switchView('prefs')); await page.waitForTimeout(200);
  check('3. with no key the field is empty', await page.evaluate(() => document.getElementById('vtKeyInput').value === ''));
  await page.close();
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
