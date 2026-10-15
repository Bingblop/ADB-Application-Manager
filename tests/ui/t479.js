// v7.14.0: Connected Devices' Logcat follows Live Logcat's changes (the owner's wish): it reads 1000 lines unless told otherwise (2000 can be chosen), keeps the
// level, the number of lines and the filter text between launches (kv 'cd_logcat_prefs'; a damaged value is ignored), and has Live Logcat's colour key, whose
// chips carry the same dots (Verbose green, Debug yellow, Info the accent, Warn orange) and hide or show a level.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
const LOG = ['V', 'D', 'I', 'I', 'W', 'E', 'F'].map((l, i) => '10-12 10:00:0' + i + '.123  1  1 ' + l + ' Tag' + l + ': message ' + l + i).join('\n') + '\n';
(async () => {
  const b = await chromium.launch();
  const errors = [];
  let page = null, saved = {};
  const launch = async kv => {
    if (page) { saved = await page.evaluate(() => Object.assign({}, window.__kv)); await page.close(); }
    page = await b.newPage({ viewport: { width: 390, height: 860 } });
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: Object.assign({ perm_intro_v61: '1' }, kv || saved), log: '' });
    await page.goto(PAGE); await page.waitForTimeout(400);
    // a device that answers: the log comes from the test, not adb
    await page.evaluate(log => { window.__asked = []; window.cdReady = () => true; window.cdSh = async c => { window.__asked.push(c); return log; }; switchView('devices'); cd.sub = 'logcat'; document.getElementById('cdWork').style.display = '';
      document.querySelectorAll('.cd-pane').forEach(p => p.classList.toggle('active', p.id === 'cdPane-logcat')); }, LOG);
  };
  const ev = (fn, a) => page.evaluate(fn, a);
  const state = () => ev(() => ({ level: document.getElementById('cdLcLevel').value, lines: document.getElementById('cdLcLines').value, filter: document.getElementById('cdLcFilter').value,
    opts: Array.from(document.getElementById('cdLcLines').options).map(o => o.value).join() }));

  await launch({});
  let s = await state();
  check('1. with nothing saved: 1000 lines (200, 500, 1000 or 2000), Info, no filter', s.lines === '1000' && s.opts === '200,500,1000,2000' && s.level === 'I' && s.filter === '', JSON.stringify(s));
  await ev(() => cdLcRefresh()); await page.waitForTimeout(200);
  check('   and a read asks the device for 1000 lines', await ev(() => / -t 1000 '\*:I'$/.test(window.__asked.slice(-1)[0] || '')), await ev(() => window.__asked.slice(-1)[0]));

  // the colour key: Live Logcat's chips, the same dots, counts, a tap hides a level
  const key = await ev(() => {
    const rgb = s => (s.match(/[\d.]+/g) || []).slice(0, 3).join();
    const dot = (sel, l) => { const c = document.querySelector(sel + ' .lc-chip[data-lvl="' + l + '"]'); return c ? rgb(getComputedStyle(c.querySelector('.lc-dot')).backgroundColor) : null; };
    const out = { same: ['V', 'D', 'I', 'W', 'E', 'F'].every(l => dot('#cdLcKey', l) && dot('#cdLcKey', l) === dot('#logcatKey', l)),
      chips: Array.from(document.querySelectorAll('#cdLcKey .lc-chip')).map(c => c.querySelector('.lc-name').textContent + '=' + c.querySelector('.lc-n').textContent).join('|'),
      rows: document.querySelectorAll('#cdLcOut .lc-row').length };
    return out;
  });
  check('2. the colour key has Live Logcat\'s chips with the same dot colours and each level\'s count', key.same && key.chips === 'V · Verbose=1|D · Debug=1|I · Info=2|W · Warn=1|E · Error=1|F · Fatal=1' && key.rows === 7, JSON.stringify(key));
  await ev(() => document.querySelector('#cdLcKey .lc-chip[data-lvl="I"]').click());
  const hid = await ev(() => ({ rows: document.querySelectorAll('#cdLcOut .lc-row').length, info: document.querySelectorAll('#cdLcOut .lc-row.lvl-I').length, off: document.querySelector('#cdLcKey .lc-chip[data-lvl="I"]').classList.contains('off'), pressed: document.querySelector('#cdLcKey .lc-chip[data-lvl="I"]').getAttribute('aria-pressed') }));
  check('   a tap on Info hides its lines and greys its chip', hid.rows === 5 && hid.info === 0 && hid.off && hid.pressed === 'false', JSON.stringify(hid));
  await ev(() => document.querySelector('#cdLcKey .lc-chip[data-lvl="I"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })));
  check('   Enter on it shows them again', await ev(() => document.querySelectorAll('#cdLcOut .lc-row').length) === 7);
  await ev(() => { document.getElementById('cdLcFilter').value = 'TagW'; cdLcDraw(false); });
  check('   the counts follow the filter box', await ev(() => Array.from(document.querySelectorAll('#cdLcKey .lc-n')).map(e => e.textContent).join()) === '0,0,0,1,0,0');

  // change everything through the controls themselves, then launch again
  await page.selectOption('#cdLcLevel', 'W');
  await page.selectOption('#cdLcLines', '2000');
  await page.fill('#cdLcFilter', 'ActivityManager'); await page.press('#cdLcFilter', 'Tab');
  await launch();
  s = await state();
  check('3. after a new launch the level (Warn), the lines (2000) and the filter text are as they were left', s.level === 'W' && s.lines === '2000' && s.filter === 'ActivityManager', JSON.stringify(s));
  check('   and Live Logcat\'s own choices were not touched', await ev(() => document.getElementById('logcatLines').value === '1000' && document.getElementById('logcatLevel').value === 'I'));
  await launch({ cd_logcat_prefs: JSON.stringify({ level: 'Z', lines: '99999', filter: 42 }) });
  s = await state();
  check('4. an unknown saved value is ignored (Info, 1000 lines, no filter)', s.level === 'I' && s.lines === '1000' && s.filter === '', JSON.stringify(s));
  await launch({ cd_logcat_prefs: '{not json' });
  s = await state();
  check('   and so is a damaged one', s.level === 'I' && s.lines === '1000', JSON.stringify(s));
  await ev(() => cdLcRefresh()); await page.waitForTimeout(300);
  await ev(() => document.getElementById('cdLcKey').scrollIntoView()); await page.waitForTimeout(100);
  await page.screenshot({ path: 'cd_logcat_key.png' });
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
