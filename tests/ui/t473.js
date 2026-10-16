// v7.14.0: Live Logcat's Play / Stop button (the owner's choice). Play is filled with the green gradient, Stop (it was Pause) with the red one, each with black text
// and a black outline, in the dark and the light theme; aria-pressed follows the state.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(mock.initScript, { kv: { perm_intro_v61: '1' }, log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);
  const look = () => ev(() => {
    const e = document.getElementById('logcatPlayBtn'), s = getComputedStyle(e), nums = x => (x.match(/\d+/g) || []).map(Number);
    const stops = (s.backgroundImage.match(/rgb\(\d+, \d+, \d+\)/g) || []).map(nums);
    return { text: e.innerText, pressed: e.getAttribute('aria-pressed'), color: s.color, border: s.borderTopColor + ' ' + s.borderTopWidth + ' ' + s.borderTopStyle, stops, inline: e.getAttribute('style') || '' };
  });
  const green = x => x.stops.length >= 2 && x.stops.every(f => f[1] > f[0] + 60 && f[1] > f[2]);
  const red = x => x.stops.length >= 2 && x.stops.every(f => f[0] > f[1] + 60 && f[0] > f[2] + 60);
  const black = x => x.color === 'rgb(0, 0, 0)' && x.border === 'rgb(0, 0, 0) 1px solid' && !/color/.test(x.inline);
  for (const theme of ['dark', 'light']) {
    await ev(t => { themeState.appearance = t; applyTheme(false); logcatStop(); switchView('apps'); switchView('logcat'); }, theme); await page.waitForTimeout(300);
    const on = await look();
    check(theme + ': while the log runs the button says Stop, filled with the red gradient, black text and outline, aria-pressed true', on.text === 'Stop' && on.pressed === 'true' && red(on) && black(on), JSON.stringify(on));
    await ev(() => logcatTogglePlay()); await page.waitForTimeout(150);
    const off = await look();
    check(theme + ': stopped it says Play, filled with the green gradient, black text and outline, aria-pressed false', off.text === 'Play' && off.pressed === 'false' && green(off) && black(off), JSON.stringify(off));
  }
  await ev(() => logcatStop());
  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
