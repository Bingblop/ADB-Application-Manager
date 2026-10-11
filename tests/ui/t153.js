// v7.12.9 security: a value placed in an inline handler (onclick="f('...')") is read by the HTML parser before the script, so escapeHtml alone lets a quote through (&#39; turns back into '). jsArg() makes it a JSON
// string first. This checks jsArg with hostile values (a package name, repo or URL from a third-party patch bundle), that the old way does run injected script (so the check can fail), and that no handler in the page
// builds a string argument with escapeHtml again.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 800 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {}, executeShell() { window.__shell = (window.__shell || 0) + 1; return ''; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(300);

  const hostile = [
    "x');AndroidBridge.executeShell('id');//",
    'x");AndroidBridge.executeShell("id");//',
    "o/r');window.__pwn=1;//",
    '\\',
    "a\\'b",
    '"><img src=x onerror=window.__pwn=1>',
    '&#39;);window.__pwn=1;//',
    'line\nbreak sep',
    '</script><script>window.__pwn=1</script>',
    "plain.package.name",
    '',
  ];
  await page.evaluate(() => { window.__fn = x => { window.__got = [x]; }; });
  const errBefore = errors.length;
  const res = await page.evaluate((vals) => {
    const out = [];
    for (const v of vals) {
      window.__got = undefined; window.__pwn = 0; window.__shell = 0;
      const host = document.createElement('div');
      host.innerHTML = '<button id="b1" onclick="window.__fn(' + jsArg(v) + ')">x</button><button id="b2" onclick="window.__fn(\'' + escapeHtml(v) + '\')">y</button>';
      document.body.appendChild(host);
      document.getElementById('b1').click();
      const safe = { got: window.__got && window.__got[0], pwn: window.__pwn, shell: window.__shell };
      host.remove();
      out.push({ v, safe });
    }
    return out;
  }, hostile);
  const errSafe = errors.length;
  await page.waitForTimeout(50);
  const olds = await page.evaluate((vals) => vals.map(v => {
    window.__got = undefined; window.__pwn = 0; window.__shell = 0;
    const host = document.createElement('div');
    host.innerHTML = '<button id="b2" onclick="window.__fn(\'' + escapeHtml(v) + '\')">y</button>';
    document.body.appendChild(host);
    try { document.getElementById('b2').click(); } catch (e) { /* a syntax error in the handler */ }
    const old = { got: window.__got && window.__got[0], pwn: window.__pwn, shell: window.__shell };
    host.remove();
    return old;
  }), hostile);
  res.forEach((r, i) => { r.old = olds[i]; });
  res.forEach((r, i) => {
    check('jsArg ' + (i + 1) + '. ' + JSON.stringify(r.v).slice(0, 50) + ': the handler receives exactly the value and nothing runs', r.safe.got === r.v && !r.safe.pwn && !r.safe.shell, JSON.stringify(r.safe).slice(0, 120));
  });
  check('the safe handlers raised no page errors', errSafe === errBefore, errors.slice(errBefore, errSafe).join(' | '));
  const oldRuns = res.filter(r => r.old.pwn || r.old.shell || r.old.got !== r.v).length;
  check('control: the old way (escapeHtml inside the quotes) fails for the quote and backslash values, so the check above can fail (' + oldRuns + ' of ' + res.length + ')', oldRuns >= 4);
  check('control: the old way really ran the injected shell call for the first value', res[0].old.shell === 1, JSON.stringify(res[0].old));

  // the page source: no handler argument is built with escapeHtml inside quotes
  const src = fs.readFileSync(path.join(REPO, 'assets', 'index.html'), 'utf8').split('\n');
  const found = [];
  src.forEach((line, i) => {
    if (/\\'' \+ escapeHtml\(|\('\$\{escapeHtml\(|\('\$\{[^}]*\.replace\(/.test(line) && !/String\(r\.browse\)\.replace\(\/\\\\\/g/.test(line)) found.push((i + 1) + ': ' + line.trim().slice(0, 100));
  });
  check('no inline handler builds a string argument with escapeHtml (use jsArg); only the already JS-escaped usBrowse one is allowed', found.length === 0, found.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all ok');
  process.exit(bad ? 1 : 0);
})();
