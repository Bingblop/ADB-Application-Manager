// Extra Settings tab, the three sections after Private DNS: Animation speed (three Global scales with slider + number + chips + a live preview dot, any number of 0 or more, a warning
// above 10, one Apply / Reset to 1x, every write through settingsOp then READ BACK, a read-back that differs, a refused write, a lost answer), Brightness bias
// (screen_auto_brightness_adj in System, -1..1 strictly, chips, the big value and phrase, the adaptive-off notice with its Display settings button, a Samsung-style read-back that
// differs, Reset to 0) and Device telemetry (three rings from getTelemetry, amber / red / charging battery, six spec chips, Not available for what cannot be read, Copy report,
// the 5 s auto-refresh that runs only while the tab is on screen); the header chips Animations and Battery; Read-Only (values still read with settingsPeek, controls off with the
// reason) and no bridge; the layout at 360 / 412 / 320 / 768 px and in German and Russian (no sideways scroll, no pill wraps, 44 px targets); contrast in four colour schemes;
// reduced motion runs no animation.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const dnsMock = require('./lib/dns_mock.js');
const xsMock = require('./lib/xs_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 }, reducedMotion: opts.reduce ? 'reduce' : 'no-preference' });
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    if (opts.kv) await page.addInitScript(kv => { window.__kvInit = kv; }, opts.kv);
    if (opts.bridge !== false) { await page.addInitScript(mock.initScript); await page.addInitScript(dnsMock.initScript); await page.addInitScript(xsMock.initScript); }
    else await page.addInitScript(() => { window.AndroidBridge = undefined; });
    if (opts.priv === false) await page.addInitScript(() => { window.__mode.priv = false; });
    if (opts.init) await page.addInitScript(opts.init);
    await page.goto(PAGE); await page.waitForTimeout(500);
    return page;
  };
  const page = await mkPage({});
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = sel => ev(s => { const e = document.querySelector(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; }, sel);
  const open = async () => { await ev(() => switchView('extrasettings')); await sleep(350); };
  const ops = () => ev(() => window.__calls.op.map(o => o.op + ' ' + o.ns + '/' + o.key + (o.op === 'put' ? '=' + o.value : '')));
  const clearOps = () => ev(() => { window.__calls.op.length = 0; });
  const settle = sel => page.waitForFunction(s => { const c = document.querySelector(s); return c && c.getAttribute('aria-busy') === 'false'; }, sel, { timeout: 5000 });
  const idle = async () => { await settle('#animSpeedCard'); await settle('#brightnessCard'); };
  const row = id => ev(i => {
    const r = document.getElementById('animRow_' + i);
    return { range: r.querySelector('.anim-range').value, num: r.querySelector('.anim-num').value, pressed: [...r.querySelectorAll('.anim-chip')].filter(c => c.getAttribute('aria-pressed') === 'true').map(c => c.innerText).join('|'),
      tag: r.querySelector('.anim-tag').innerText, tagCls: r.querySelector('.anim-tag').className, problem: r.querySelector('.anim-problem').innerText, phone: r.querySelector('.anim-phone').innerText,
      dot: r.querySelector('.anim-dot').style.getPropertyValue('--anim-d'), zero: r.classList.contains('is-zero'), live: r.classList.contains('is-live'), disabled: r.querySelector('.anim-range').disabled && r.querySelector('.anim-num').disabled && [...r.querySelectorAll('.anim-chip')].every(c => c.disabled) };
  }, id);
  const setRange = (sel, v) => ev(a => { const e = document.querySelector(a[0]); e.value = a[1]; e.dispatchEvent(new Event('input', { bubbles: true })); }, [sel, v]);
  const typeNum = (sel, v) => ev(a => { const e = document.querySelector(a[0]); e.value = a[1]; e.dispatchEvent(new Event('input', { bubbles: true })); }, [sel, v]);
  const click = sel => ev(s => document.querySelector(s).click(), sel);
  const pill = id => ev(i => { const p = document.getElementById(i); return p.innerText.replace(/\s+/g, ' ').trim() + ' [' + p.className.replace('xs-pill', '').trim() + ']'; }, id);
  const br = () => ev(() => ({ big: document.getElementById('brBig').innerText, phrase: document.getElementById('brPhrase').innerText, phone: document.getElementById('brPhone').innerText, now: document.getElementById('brNow').innerText,
    range: document.getElementById('brRange').value, num: document.getElementById('brNum').value, problem: document.getElementById('brProblem').innerText, msg: document.getElementById('brMsg').innerText, msgErr: document.getElementById('brMsg').classList.contains('is-err'),
    pressed: [...document.querySelectorAll('#brightnessCard .br-chip')].filter(c => c.getAttribute('aria-pressed') === 'true').map(c => c.innerText).join('|'), notice: getComputedStyle(document.getElementById('brNotice')).display !== 'none',
    off: document.getElementById('brRange').disabled && document.getElementById('brNum').disabled && document.getElementById('brApply').disabled && document.getElementById('brReset').disabled && [...document.querySelectorAll('#brightnessCard .br-chip')].every(c => c.disabled), setup: getComputedStyle(document.getElementById('brSetup')).display !== 'none' }));
  const tel = () => ev(() => {
    const ring = id => { const r = document.getElementById(id); return { pct: r.querySelector('.tel-pct').innerText, small: r.querySelector('.tel-pct').classList.contains('is-small'), sub: r.querySelector('.tel-sub').innerText, cls: r.className.replace('tel-ring', '').trim(), off: r.querySelector('.tel-fill').style.strokeDashoffset, stroke: getComputedStyle(r.querySelector('.tel-fill')).stroke }; };
    const spec = id => { const e = document.getElementById(id); return e.innerText + (e.classList.contains('is-none') ? ' [none]' : ''); };
    return { ram: ring('telRam'), store: ring('telStore'), batt: ring('telBatt'), bolt: getComputedStyle(document.getElementById('telBolt')).display, model: spec('telModel'), android: spec('telAndroid'), kernel: spec('telKernel'), uptime: spec('telUptime'), temp: spec('telBattTemp'), swap: spec('telSwap'),
      loading: document.getElementById('telemetryCard').classList.contains('is-loading'), copyOff: document.getElementById('telCopy').disabled, calls: window.__tel.calls, chip: document.getElementById('xsChipBatt').innerText.replace(/\s+/g, ' ').trim() };
  });

  // ---- 1. the shell: three new sections after Private DNS, two new header chips ----
  await open(); await idle();
  const shell = await ev(() => {
    const root = document.getElementById('extraSettingsRoot');
    const secs = ['extraAnimSection', 'extraBrightnessSection', 'extraTelemetrySection'].map(id => { const s = document.getElementById(id); return s && s.tagName === 'SECTION' && s.classList.contains('xs-sec') && root.contains(s) && s.querySelectorAll('.xs-sec-head .xs-ico svg').length === 1 && s.querySelectorAll('.xs-sec-head .xs-pill').length === 1 && !!s.querySelector('.xs-more summary') && !!s.querySelector('.color-card-subtitle'); });
    const chip = id => { const c = document.getElementById(id); return c.innerText.replace(/\s+/g, ' ').trim() + ' -> ' + c.dataset.target; };
    return { kids: [...root.children].map(e => e.id).join(), secs, anim: chip('xsChipAnim'), batt: chip('xsChipBatt'), titles: [...root.querySelectorAll('.xs-sec .xs-sec-line .color-card-title')].map(e => e.innerText).join('|'), trust: root.querySelectorAll('#animSpeedCard .xs-trust, #brightnessCard .xs-trust').length };
  });
  check('1. the tab is header, Private DNS, Animation speed, Brightness bias, Device telemetry: each a <section class="xs-sec"> with icon tile, title, ONE pill, a sentence and a "What does this do?" note', shell.kids === 'extraSettingsHeader,extraDnsSection,extraAnimSection,extraBrightnessSection,extraTelemetrySection' && shell.secs.every(Boolean) && shell.titles === 'Private DNS|Animation speed|Brightness bias|Device telemetry' && shell.trust === 2, JSON.stringify(shell));
  check('   the header has the live chips "Animations 1×" (to the animation section) and "Battery 49%" (to the telemetry section)', shell.anim === 'Animations 1× -> extraAnimSection' && shell.batt === 'Battery 49% -> extraTelemetrySection', shell.anim + ' / ' + shell.batt);
  const gone = await ev(() => { let n = 0; const o = Element.prototype.scrollIntoView; Element.prototype.scrollIntoView = function () { n++; }; document.getElementById('xsChipBatt').click(); Element.prototype.scrollIntoView = o; return n; });
  check('   a chip scrolls the page to its section', gone === 1);

  // ---- 2. Animation speed: read from the phone ----
  check('2. the three scales were read from the Global table through settingsOp (the working mode), nothing written', (await ops()).filter(o => /animation|animator/.test(o)).join() === 'get global/window_animation_scale,get global/transition_animation_scale,get global/animator_duration_scale' && !(await ops()).some(o => /^put/.test(o)), (await ops()).join());
  const a1 = await ev(() => ({ now: document.getElementById('animNow').innerText, rows: [...document.querySelectorAll('#animRows .anim-row')].map(r => r.dataset.key).join(), names: [...document.querySelectorAll('#animRows .anim-name')].map(e => e.innerText).join('|'), chips: [...document.querySelectorAll('#animRow_window .anim-chip')].map(c => c.innerText).join('|') }));
  check('   the card says "The phone holds: 1× · 1× · 1×"; three rows Window animations / Transition animations / Animator duration; chips Off · 0.5× · 1× · 1.5× · 2×', a1.now === 'The phone holds: 1× · 1× · 1×' && a1.rows === 'window_animation_scale,transition_animation_scale,animator_duration_scale' && a1.names === 'Window animations|Transition animations|Animator duration' && a1.chips === 'Off|0.5×|1×|1.5×|2×', JSON.stringify(a1));
  let w = await row('window');
  check('   a row: slider 1, field 1, chip 1× pressed, "On the phone: 1×", the preview dot runs at 0.7 s per crossing and is live', w.range === '1' && w.num === '1' && w.pressed === '1×' && w.phone === 'On the phone: 1×' && w.dot === '0.700s' && w.live && !w.disabled && w.tag === '', JSON.stringify(w));
  check('   pill "1×" (ok) and chip "Animations 1×"', (await pill('animPill')) === '● 1× [is-ok]' && (await txt('#xsChipAnim')) === 'Animations 1×', await pill('animPill'));
  const anim = await ev(() => getComputedStyle(document.getElementById('animDot_window')).animationName + ' ' + getComputedStyle(document.getElementById('animDot_window')).animationDuration);
  check('   with motion allowed the dot really animates (animDotMove, 0.7 s)', anim === 'animDotMove 0.7s', anim);

  // ---- 3. slider, field and chips stay in step; any number of 0 or more ----
  await setRange('#animRange_window', '2.5'); w = await row('window');
  check('3. slider to 2.5: the field says 2.5, no chip pressed, the dot slows to 1.75 s', w.num === '2.5' && w.pressed === '' && w.dot === '1.750s', JSON.stringify(w));
  await typeNum('#animNum_window', '0.5'); w = await row('window');
  check('   field 0.5: the slider follows, chip 0.5× pressed, dot 0.35 s', w.range === '0.5' && w.pressed === '0.5×' && w.dot === '0.350s', JSON.stringify(w));
  await typeNum('#animNum_window', '15'); w = await row('window');
  check('   field 15 (above the slider): accepted, slider pinned at 10, warning pill "Very slow: Reset to 1× brings it back", no error', w.range === '10' && w.num === '15' && w.tag === 'Very slow: Reset to 1× brings it back' && /is-warn/.test(w.tagCls) && w.problem === '' && w.dot === '10.500s', JSON.stringify(w));
  const tagOne = await ev(() => { const t = document.getElementById('animTag_window'); return t.offsetHeight <= 26 && t.scrollWidth <= t.clientWidth + 1 && t.getBoundingClientRect().top > document.getElementById('animName_window').getBoundingClientRect().bottom - 1; });
  check('   the warning pill sits on its own line under the name, whole, on one line', tagOne);
  for (const [v, why] of [['abc', 'text'], ['-1', 'a negative number'], ['Infinity', 'Infinity'], ['NaN', 'NaN'], ['1e3', 'an exponent'], ['', 'an empty field'], ['1.2.3', 'two dots']]) {
    await typeNum('#animNum_transition', v); const t = await row('transition');
    check('   ' + why + ' is refused in words: "Type a number of 0 or more, like 0.5 or 1."', t.problem === 'Type a number of 0 or more, like 0.5 or 1.' && t.pressed === '', JSON.stringify(t));
  }
  await typeNum('#animNum_transition', '0,75'); let t2 = await row('transition');
  check('   a comma as the decimal sign is read (0,75 = 0.75)', t2.problem === '' && t2.range === '0.75', JSON.stringify(t2));
  await typeNum('#animNum_transition', 'x');
  await clearOps(); await click('#animApply'); await sleep(100);
  const bad1 = await ev(() => ({ msg: document.getElementById('animMsg').innerText, err: document.getElementById('animMsg').classList.contains('is-err'), focus: document.activeElement && document.activeElement.id, problem: document.getElementById('animProblem_transition').innerText }));
  check('   Apply with a field that is not a number: nothing sent, the message says so, the field is focused', bad1.msg === 'Nothing was sent: one of the numbers is not a speed.' && bad1.err && bad1.focus === 'animNum_transition' && (await ops()).length === 0 && bad1.problem !== '', JSON.stringify(bad1));
  await ev(() => document.querySelector('#animRow_animator .anim-chip[data-v="0"]').click()); const z = await row('animator');
  check('   chip Off: slider 0, field 0, the row is marked zero (the dot jumps instead of gliding)', z.range === '0' && z.num === '0' && z.pressed === 'Off' && z.zero && z.dot === '0.035s', JSON.stringify(z));
  const stepped = await ev(() => getComputedStyle(document.getElementById('animDot_animator')).animationTimingFunction);
  check('   at Off the dot uses a stepped timing function', /steps\(1/.test(stepped), stepped);

  // ---- 4. Apply: the exact commands, then the read-back ----
  await typeNum('#animNum_transition', '15'); await clearOps();
  await click('#animApply');
  const busy = await ev(() => ({ msg: document.getElementById('animMsg').innerText, off: document.getElementById('animApply').disabled && document.getElementById('animRange_window').disabled, aria: document.getElementById('animSpeedCard').getAttribute('aria-busy') }));
  check('4. while the change is out: "Applying the animation scales…", the controls are off, aria-busy', busy.msg === 'Applying the animation scales…' && busy.off && busy.aria === 'true', JSON.stringify(busy));
  await idle();
  const applied = await ev(() => ({ msg: document.getElementById('animMsg').innerText, err: document.getElementById('animMsg').classList.contains('is-err'), db: [window.__db.global.window_animation_scale, window.__db.global.transition_animation_scale, window.__db.global.animator_duration_scale].join(), now: document.getElementById('animNow').innerText }));
  check('   the exact commands: put global window_animation_scale 15.0, transition 15.0, animator 0.0, then the three are read back', (await ops()).join() === 'put global/window_animation_scale=15.0,put global/transition_animation_scale=15.0,put global/animator_duration_scale=0.0,get global/window_animation_scale,get global/transition_animation_scale,get global/animator_duration_scale', (await ops()).join());
  check('   "Applied, and read back from the phone."; the phone holds 15.0 / 15.0 / 0.0; the card says "The phone holds: 15× · 15× · Off"', applied.msg === 'Applied, and read back from the phone.' && !applied.err && applied.db === '15.0,15.0,0.0' && applied.now === 'The phone holds: 15× · 15× · Off', JSON.stringify(applied));
  w = await row('window');
  check('   the rows show what the phone holds ("On the phone: 15×", the warning pill stays)', w.phone === 'On the phone: 15×' && w.num === '15' && w.tag !== '' && (await row('animator')).phone === 'On the phone: Off', JSON.stringify(w));
  check('   pill "Mixed" and chip "Animations Mixed" (the three differ)', (await pill('animPill')) === '● Mixed []' && (await txt('#xsChipAnim')) === 'Animations Mixed', await pill('animPill'));
  await clearOps(); await click('#animReset'); await idle();
  const reset = await ev(() => ({ db: [window.__db.global.window_animation_scale, window.__db.global.transition_animation_scale, window.__db.global.animator_duration_scale].join(), pill: document.getElementById('animPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipAnim').innerText.replace(/\s+/g, ' ').trim(), tag: document.getElementById('animTag_window').innerText, num: document.getElementById('animNum_window').value }));
  check('   Reset to 1×: three puts of 1.0, read back, pill "1×", chip "Animations 1×", the warning pill is gone, the fields say 1', (await ops()).filter(o => /^put/.test(o)).join() === 'put global/window_animation_scale=1.0,put global/transition_animation_scale=1.0,put global/animator_duration_scale=1.0' && reset.db === '1.0,1.0,1.0' && reset.pill === '● 1×' && reset.chip === 'Animations 1×' && reset.tag === '' && reset.num === '1', JSON.stringify(reset));
  // a phone that quietly keeps another value (every put answers ok, the read-back says 1.0)
  await ev(() => { const o = window.__origOp = window.AndroidBridge.settingsOp; window.AndroidBridge.settingsOp = function (req, op, ns, key, value) { const r = o.call(this, req, op, ns, key, value); if (op === 'put') setTimeout(() => { window.__db[ns][key] = '1.0'; }, window.__delay + 5); return r; }; });
  await ev(() => document.querySelector('#animRow_window .anim-chip[data-v="2"]').click()); await clearOps(); await click('#animApply'); await idle();
  const kept = await ev(() => ({ msg: document.getElementById('animMsg').innerText, err: document.getElementById('animMsg').classList.contains('is-err'), num: document.getElementById('animNum_window').value, phone: document.getElementById('animPhone_window').innerText }));
  check('   read-back differs (the phone kept 1.0 after an ok put of 2.0): "Not applied as sent: the phone reports other values. The rows show what it holds." and the row shows 1×', kept.msg === 'Not applied as sent: the phone reports other values. The rows show what it holds.' && kept.err && kept.num === '1' && kept.phone === 'On the phone: 1×', JSON.stringify(kept));
  await ev(() => { window.AndroidBridge.settingsOp = window.__origOp; });     // back to the mock's own
  // a refused write
  await ev(() => { window.__deny['global/transition_animation_scale'] = 'java.lang.SecurityException: Permission denial'; });
  await ev(() => document.querySelector('#animRow_transition .anim-chip[data-v="1.5"]').click()); await clearOps(); await click('#animApply'); await idle();
  const denied = await ev(() => ({ msg: document.getElementById('animMsg').innerText, err: document.getElementById('animMsg').classList.contains('is-err'), db: window.__db.global.transition_animation_scale }));
  check('   a refused write: "Could not change the animation scales: …" with Android\'s words and the advice, the later puts are not sent, the phone\'s value stays', /^Could not change the animation scales: java\.lang\.SecurityException: Permission denial Android refused the change/.test(denied.msg) && denied.err && denied.db === '1.0' && (await ops()).filter(o => /^put/.test(o)).length === 2, JSON.stringify(denied) + ' ' + (await ops()).join());
  await ev(() => { delete window.__deny['global/transition_animation_scale']; });
  // a lost answer
  await ev(() => { window.__unknown = { 'global/window_animation_scale': { apply: true } }; });
  await ev(() => document.querySelector('#animRow_window .anim-chip[data-v="0.5"]').click()); await click('#animApply'); await idle();
  const lost = await ev(() => document.getElementById('animMsg').innerText);
  check('   an answer that never came: the message says it may still have been applied and that the rows show what the phone reports now', /^Could not change the animation scales: .*It may still have been applied: the state above is what the phone reports now\.$/.test(lost) && (await row('window')).phone === 'On the phone: 0.5×', lost);
  await clearOps(); await click('#animReset'); await idle();

  // ---- 5. the preview dots run only while the tab is on screen ----
  await ev(() => switchView('settings')); await sleep(200);
  const away = await ev(() => [...document.querySelectorAll('#animRows .anim-row')].every(r => !r.classList.contains('is-live')));
  check('5. leaving the tab stops the preview dots', away);
  await open(); await idle();
  check('   coming back starts them again', await ev(() => [...document.querySelectorAll('#animRows .anim-row')].every(r => r.classList.contains('is-live'))));
  await ev(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  check('   the page hidden (screen off, another app): the dots stop', await ev(() => [...document.querySelectorAll('#animRows .anim-row')].every(r => !r.classList.contains('is-live'))));
  await ev(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
  check('   visible again: they run', await ev(() => [...document.querySelectorAll('#animRows .anim-row')].every(r => r.classList.contains('is-live'))));

  // ---- 6. Brightness bias ----
  let s = await br();
  check('6. no value on the phone: "The phone has no value set, which counts as 0 (neutral).", big 0.00, "Neutral: Android’s own choice", "On the phone: not set (counts as 0)", pill "Not set", no notice', s.now === 'The phone has no value set, which counts as 0 (neutral).' && s.big === '0.00' && s.phrase === 'Neutral: Android’s own choice' && s.phone === 'On the phone: not set (counts as 0)' && (await pill('brPill')) === '● Not set []' && !s.notice && s.pressed === 'Neutral 0' && !s.off, JSON.stringify(s));
  check('   it read screen_auto_brightness_adj and screen_brightness_mode from the System table', (await ev(() => window.__calls.op.map(o => o.op + ' ' + o.ns + '/' + o.key).filter(o => /brightness/.test(o)).join())) === 'get system/screen_auto_brightness_adj,get system/screen_brightness_mode');
  await ev(() => { window.__db.system.screen_auto_brightness_adj = '0.3'; }); await click('#brRefresh'); await idle(); s = await br();
  check('   the phone holds 0.3: "The phone holds: +0.30", big +0.30, "Brighter than normal", slider 0.3, field 0.3, pill "+0.30" (ok)', s.now === 'The phone holds: +0.30' && s.big === '+0.30' && s.phrase === 'Brighter than normal' && s.range === '0.3' && s.num === '0.3' && s.pressed === '' && (await pill('brPill')) === '● +0.30 [is-ok]', JSON.stringify(s));
  await setRange('#brRange', '-0.5'); s = await br();
  check('   slider to −0.5: field −0.5, chip "Darker −0.5" pressed, big −0.50, "Darker than normal"', s.num === '-0.5' && s.pressed === 'Darker −0.5' && s.big === '−0.50' && s.phrase === 'Darker than normal', JSON.stringify(s));
  await typeNum('#brNum', '1.5'); s = await br();
  check('   field 1.5: refused in words, never clamped ("Only values from −1 to 1 work here. Nothing was sent."), the slider is left where it was', s.problem === 'Only values from −1 to 1 work here. Nothing was sent.' && s.range === '-0.5' && s.big === '?' && s.phrase === 'Not a number yet', JSON.stringify(s));
  await clearOps(); await click('#brApply'); await sleep(100); s = await br();
  check('   Apply with 1.5: nothing is sent, "Nothing was sent: the number is not a bias between −1 and 1."', (await ops()).length === 0 && s.msg === 'Nothing was sent: the number is not a bias between −1 and 1.' && s.msgErr, JSON.stringify(s));
  for (const [v, want] of [['abc', 'Type a number between −1 and 1, like −0.3 or 0.5.'], ['-1.01', 'Only values from −1 to 1 work here. Nothing was sent.'], ['', 'Type a number between −1 and 1, like −0.3 or 0.5.'], ['1e0', 'Type a number between −1 and 1, like −0.3 or 0.5.']]) {
    await typeNum('#brNum', v); s = await br();
    check('   field ' + JSON.stringify(v) + ': "' + want + '"', s.problem === want, s.problem);
  }
  await typeNum('#brNum', '−1'); s = await br();
  check('   field −1 (the real minus sign): accepted, "Much darker than normal", slider −1', s.problem === '' && s.range === '-1' && s.phrase === 'Much darker than normal' && s.big === '−1.00', JSON.stringify(s));
  await typeNum('#brNum', '0.8'); s = await br();
  check('   field 0.8: "Much brighter than normal"', s.phrase === 'Much brighter than normal' && s.big === '+0.80', JSON.stringify(s));
  await ev(() => document.querySelector('#brightnessCard .br-chip[data-v="0.5"]').click()); await clearOps(); await click('#brApply'); await idle(); s = await br();
  check('   chip "Brighter +0.5" then Apply: put system screen_auto_brightness_adj 0.5, then the value and the switch are read back; "Applied, and read back from the phone."; pill +0.50', (await ops()).join() === 'put system/screen_auto_brightness_adj=0.5,get system/screen_auto_brightness_adj,get system/screen_brightness_mode' && s.msg === 'Applied, and read back from the phone.' && !s.msgErr && (await ev(() => window.__db.system.screen_auto_brightness_adj)) === '0.5' && (await pill('brPill')) === '● +0.50 [is-ok]' && s.phone === 'On the phone: +0.50', JSON.stringify(s) + ' ' + (await ops()).join());
  // Samsung: the put answers ok, the phone holds 0.0 right after
  await ev(() => { const o = window.__origOp = window.AndroidBridge.settingsOp; window.AndroidBridge.settingsOp = function (req, op, ns, key, value) { const r = o.call(this, req, op, ns, key, value); if (op === 'put') setTimeout(() => { window.__db[ns][key] = '0.0'; }, window.__delay + 5); return r; }; });
  await ev(() => document.querySelector('#brightnessCard .br-chip[data-v="-0.5"]').click()); await click('#brApply'); await idle(); s = await br();
  check('   the phone reports 0.00 after an ok put of −0.5 (One UI): "Applied, but the phone reports 0.00 instead of −0.50. One UI and some other phones reset or ignore this value.", the big number shows the phone\'s 0.00', s.msg === 'Applied, but the phone reports 0.00 instead of −0.50. One UI and some other phones reset or ignore this value.' && s.msgErr && s.big === '0.00' && s.phone === 'On the phone: 0.00', JSON.stringify(s));
  await ev(() => { window.AndroidBridge.settingsOp = window.__origOp; });
  await ev(() => { window.__db.system.screen_auto_brightness_adj = '0.7'; }); await click('#brRefresh'); await idle();
  await clearOps(); await click('#brReset'); await idle(); s = await br();
  check('   Reset to 0 writes 0.0 and reads it back', (await ops())[0] === 'put system/screen_auto_brightness_adj=0.0' && s.big === '0.00' && s.msg === 'Applied, and read back from the phone.' && (await pill('brPill')) === '● 0.00 []', JSON.stringify(s));
  // a refused write
  await ev(() => { window.__deny['system/screen_auto_brightness_adj'] = 'java.lang.SecurityException: not allowed'; });
  await ev(() => document.querySelector('#brightnessCard .br-chip[data-v="0.5"]').click()); await click('#brApply'); await idle(); s = await br();
  check('   a refused write: "Could not change the brightness bias: …" with Android\'s words; the phone\'s 0.00 stays', /^Could not change the brightness bias: java\.lang\.SecurityException: not allowed/.test(s.msg) && s.msgErr && s.big === '0.00', JSON.stringify(s));
  await ev(() => { delete window.__deny['system/screen_auto_brightness_adj']; });
  // adaptive brightness off
  await ev(() => { window.__db.system.screen_brightness_mode = '0'; }); await click('#brRefresh'); await idle(); s = await br();
  check('   adaptive brightness off (screen_brightness_mode 0): the notice "Adaptive brightness is off, so this has no effect until you turn it on." with a button, pill "Adaptive off" (warn); the controls stay usable', s.notice && (await txt('#brNotice')) === 'Adaptive brightness is off, so this has no effect until you turn it on. Open display settings' && (await pill('brPill')) === '● Adaptive off [is-warn]' && !s.off, JSON.stringify(s));
  await click('#brOpenDisplay');
  check('   "Open display settings" opens Android\'s Display settings through the bridge (AndroidBridge.openDisplaySettings) and never writes screen_brightness_mode', (await ev(() => window.__display)) === 1 && !(await ev(() => window.__calls.op.some(o => o.op === 'put' && o.key === 'screen_brightness_mode'))));
  await ev(() => { window.__db.system.screen_brightness_mode = '1'; }); await click('#brRefresh'); await idle(); s = await br();
  check('   adaptive brightness on again: the notice goes', !s.notice && (await pill('brPill')) === '● 0.00 []');
  check('   the hint says why there is no live preview', (await txt('#brightnessCard .br-hint')) === 'No live preview here: the phone sets the brightness from the light around it, so you see the effect after Apply, as the light changes.');
  const brLine = await ev(() => { const e = document.querySelector('#brightnessCard .br-scale'); return e.innerText.replace(/\s+/g, ' ').trim(); });
  check('   the track is labelled "−1 darker  0  +1 brighter"', brLine === '−1 darker 0 +1 brighter', brLine);

  // ---- 7. Device telemetry ----
  let d = await tel();
  check('7. the three rings: RAM 54% "8.5 of 15.8 GB", Storage 47% "112 of 237 GB", Battery 49% "On battery"; the fill is drawn (dash offset), the battery ring has no warning and no bolt', d.ram.pct === '54%' && d.ram.sub === '8.5 of 15.8 GB' && d.store.pct === '47%' && d.store.sub === '112 of 237 GB' && d.batt.pct === '49%' && d.batt.sub === 'On battery' && d.batt.cls === '' && d.bolt === 'none' && d.ram.off === '115.6' && !d.loading, JSON.stringify(d));
  check('   the spec chips: Samsung SM-S918B, 14 (API 34) · One UI 6.1, kernel 5.15.94-android13-8 (the short form), uptime 2d 3h, 88°F · Good, swap 1.3 of 8.0 GB', d.model === 'Samsung SM-S918B' && d.android === '14 (API 34) · One UI 6.1' && d.kernel === '5.15.94-android13-8' && d.uptime === '2d 3h' && d.temp === '88°F · Good' && d.swap === '1.3 of 8.0 GB', JSON.stringify(d));
  check('   pill "Live" (ok), chip "Battery 49%", Copy report enabled, the whole thing needed no settingsOp', (await pill('telPill')) === '● Live [is-ok]' && d.chip === 'Battery 49%' && !d.copyOff && !(await ev(() => window.__calls.op.some(o => /tele|batt/i.test(o.key)))), await pill('telPill'));
  const okStroke = d.ram.stroke;
  await ev(() => { window.__tel.data.battery = { percent: 15, tempTenthsC: 250, plugged: 1, health: 2, status: 2 }; }); await click('#telRefresh'); d = await tel();
  const warnCol = await ev(() => getComputedStyle(document.getElementById('view-extrasettings')).getPropertyValue('--xs-warn').trim());
  check('   battery 15% charging on AC: the ring turns amber (--xs-warn), the bolt shows, "Charging", chip "Battery 15%"', /is-warn/.test(d.batt.cls) && /is-charging/.test(d.batt.cls) && d.bolt !== 'none' && d.batt.sub === 'Charging' && d.chip === 'Battery 15%' && d.batt.stroke !== okStroke && d.temp === '77°F · Good', JSON.stringify(d) + ' warn ' + warnCol);
  const spin = await ev(() => document.getElementById('telRefresh').classList.contains('is-busy'));
  await page.waitForFunction(() => !document.getElementById('telRefresh').classList.contains('is-busy'), null, { timeout: 3000 });
  check('   the refresh button spins for a moment after a tap, then stops', spin === true);
  await ev(() => { window.__tel.data.battery = { percent: 8, tempTenthsC: 250, plugged: 0, health: 3, status: 3 }; }); await click('#telRefresh'); d = await tel();
  check('   battery 8%: red (--xs-bad), no bolt, "Overheat" in the chip', /is-bad/.test(d.batt.cls) && !/is-charging/.test(d.batt.cls) && d.bolt === 'none' && d.temp === '77°F · Overheat' && d.batt.pct === '8%', JSON.stringify(d));
  await ev(() => { window.__tel.data.battery = { percent: 100, plugged: 2, health: 2, status: 5 }; }); await click('#telRefresh'); d = await tel();
  check('   full on USB: 100%, "Full", bolt shown (plugged), no temperature: the chip shows only the health', d.batt.pct === '100%' && d.batt.sub === 'Full' && /is-charging/.test(d.batt.cls) && d.temp === 'Good', JSON.stringify(d));
  await ev(() => { delete window.__tel.data.battery; delete window.__tel.data.storage; window.__tel.data.mem.swapTotalKb = 0; delete window.__tel.data.kernel; delete window.__tel.data.oneUi; }); await click('#telRefresh'); d = await tel();
  check('   what cannot be read says "Not available" (battery ring, storage ring, kernel, temperature), never blank or NaN; no swap says "No swap"; Android without One UI', d.batt.pct === 'Not available' && d.batt.small && d.batt.sub === 'Not available' && d.store.pct === 'Not available' && d.kernel === 'Not available [none]' && d.temp === 'Not available [none]' && d.swap === 'No swap' && d.android === '14 (API 34)' && d.chip === 'Battery Not available' && d.ram.pct === '54%', JSON.stringify(d));
  check('   a ring without a value is grey and empty', /is-none/.test(d.batt.cls) && d.batt.off === '251.3');
  await ev(() => { window.__tel.raw = '{"ok":false,"error":"boom"}'; }); await click('#telRefresh'); d = await tel();
  check('   the app answers ok:false: every ring and chip says "Not available", pill "Not read", Copy report off', d.ram.pct === 'Not available' && d.model === 'Not available [none]' && d.uptime === 'Not available [none]' && (await pill('telPill')) === '● Not read [is-warn]' && d.copyOff, JSON.stringify(d));
  await ev(() => { window.__tel.raw = 'not json'; }); await click('#telRefresh'); d = await tel();
  check('   broken JSON: the same, and no page error', d.ram.pct === 'Not available' && d.copyOff);
  await ev(() => { window.__tel.raw = null; window.__tel.data = JSON.parse(JSON.stringify({ ok: true, mem: { totalKb: 16534728, usedKb: 8928753, availableKb: 7605975, swapTotalKb: 8388604, swapUsedKb: 1310720 }, storage: { totalBytes: 254722064384, freeBytes: 134212849664, usedBytes: 120509214720 }, battery: { percent: 49, tempTenthsC: 312, plugged: 0, health: 2, status: 3 }, uptimeMs: 186120000, model: 'SM-S918B', manufacturer: 'samsung', android: '14', sdk: 34, oneUi: '6.1', kernel: '5.15.94-android13-8-27940483-abS918BXXS4CXF2' })); }); await click('#telRefresh');
  await ev(() => { window.__copied = null; }); await click('#telCopy'); await sleep(50);
  const copied = await ev(() => window.__copied);
  const lines = String(copied || '').split('\n');
  check('   Copy report puts a clean plain-text report on the clipboard (model, Android, kernel in full, uptime, RAM, storage, battery, swap) and says "Copied the report"', lines.length === 9 && /^Device telemetry — \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(lines[0]) && lines.slice(1).join('|') === 'Model: Samsung SM-S918B|Android: 14 (API 34) · One UI 6.1|Kernel: 5.15.94-android13-8-27940483-abS918BXXS4CXF2|Uptime: 2d 3h|RAM used: 8.5 of 15.8 GB (54%)|Storage used: 112 of 237 GB (47%)|Battery: 49%, On battery, 88°F, Good|Swap used: 1.3 of 8.0 GB' && (await txt('#toastMsg')) === 'Copied the report', JSON.stringify(copied));
  // auto-refresh: every 5 s while the tab is on screen
  const c0 = await ev(() => window.__tel.calls);
  await page.waitForFunction(c => window.__tel.calls > c, c0, { timeout: 7000 }).catch(() => {});
  const c1 = await ev(() => window.__tel.calls);
  check('   the numbers refresh by themselves while the tab is on screen (a poll within 7 s)', c1 > c0, c0 + ' -> ' + c1);
  await ev(() => switchView('settings')); await sleep(200);
  const c2 = await ev(() => window.__tel.calls);
  await sleep(5600);
  const c3 = await ev(() => window.__tel.calls);
  check('   leaving the tab stops the poll (no call in 5.6 s)', c3 === c2, c2 + ' -> ' + c3);
  await open(); const c4 = await ev(() => window.__tel.calls);
  check('   coming back reads once right away and the pill says "Live"', c4 === c3 + 1 && (await pill('telPill')) === '● Live [is-ok]', c3 + ' -> ' + c4);
  await ev(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
  const c5 = await ev(() => window.__tel.calls); await sleep(5600); const c6 = await ev(() => window.__tel.calls);
  check('   the page hidden: the poll stops ("Paused")', c6 === c5 && (await pill('telPill')) === '● Paused []', c5 + ' -> ' + c6 + ' ' + (await pill('telPill')));
  await ev(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
  check('   visible again: "Live"', (await pill('telPill')) === '● Live [is-ok]');

  // ---- 8. Read-Only: the values are still read, the controls are off with the reason ----
  const pn = await mkPage({ priv: false });
  await pn.evaluate(() => { window.__db.global.transition_animation_scale = '0.5'; window.__db.system.screen_auto_brightness_adj = '-0.3'; switchView('extrasettings'); }); await pn.waitForTimeout(400);
  const ro = await pn.evaluate(() => ({
    now: document.getElementById('animNow').innerText, peeks: window.__calls.peek.join(), ops: window.__calls.op.length, pill: document.getElementById('animPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipAnim').innerText.replace(/\s+/g, ' ').trim(),
    off: [...document.querySelectorAll('#animRows .anim-range, #animRows .anim-num, #animRows .anim-chip')].every(e => e.disabled) && document.getElementById('animApply').disabled && document.getElementById('animReset').disabled, refresh: document.getElementById('animRefresh').disabled, setup: getComputedStyle(document.getElementById('animSetup')).display !== 'none',
    phone: document.getElementById('animPhone_transition').innerText, num: document.getElementById('animNum_transition').value, live: [...document.querySelectorAll('#animRows .anim-row')].every(r => r.classList.contains('is-live')),
    brNow: document.getElementById('brNow').innerText, brBig: document.getElementById('brBig').innerText, brOff: document.getElementById('brRange').disabled && document.getElementById('brApply').disabled, brPill: document.getElementById('brPill').innerText.replace(/\s+/g, ' ').trim(), brSetup: getComputedStyle(document.getElementById('brSetup')).display !== 'none',
    tel: document.getElementById('telRamPct').innerText + ' ' + document.getElementById('telPillT').innerText,
  }));
  check('8. Read-Only: the scales are read with the app\'s own rights (settingsPeek), nothing goes through settingsOp; the card says "Viewing only: changing this needs a working mode (…)" and still shows what the phone holds; the controls are off, Check again works, Set Up is offered, the dots still run', /^Viewing only: changing this needs a working mode \(ADB, Wireless Debugging, Shizuku or Root\)\. The phone holds: 1× · 0\.5× · 1×$/.test(ro.now) && ro.peeks.indexOf('global/window_animation_scale,global/transition_animation_scale,global/animator_duration_scale') >= 0 && ro.ops === 0 && ro.off && !ro.refresh && ro.setup && ro.phone === 'On the phone: 0.5×' && ro.num === '0.5' && ro.live, JSON.stringify(ro));
  check('   pill "🔒 Viewing only" and chip "Animations Viewing only"', ro.pill === '🔒 Viewing only' && ro.chip === 'Animations Viewing only', ro.pill);
  check('   the brightness bias too: read (−0.30 shown big), "Viewing only…", controls off, Set Up; telemetry needs no mode and is live', /^Viewing only: changing this needs a working mode/.test(ro.brNow) && ro.brBig === '−0.30' && ro.brOff && ro.brPill === '🔒 Viewing only' && ro.brSetup && ro.tel === '54% Live', JSON.stringify(ro));
  await pn.evaluate(() => { animResetAll(); }); await pn.waitForTimeout(100);
  check('   the buttons are disabled; Reset to 1× reached some other way (a stale handler) opens the "requires working mode" sheet and sends nothing', await pn.evaluate(() => document.getElementById('privilegeModal').classList.contains('show') && window.__calls.op.length === 0 && document.getElementById('animReset').disabled));
  await pn.evaluate(() => { document.getElementById('privilegeModal').classList.remove('show'); brReset(); }); await pn.waitForTimeout(100);
  check('   the same for the brightness Reset and Apply', await pn.evaluate(() => document.getElementById('privilegeModal').classList.contains('show') && window.__calls.op.length === 0 && document.getElementById('brReset').disabled && document.getElementById('brApply').disabled));
  // the working mode comes: the sections read through it and the controls open
  await pn.evaluate(() => { window.__mode.priv = true; checkAllWorkingModes(false); }); await pn.waitForTimeout(800);
  const up = await pn.evaluate(() => ({ now: document.getElementById('animNow').innerText, off: document.getElementById('animApply').disabled, ops: window.__calls.op.filter(o => o.op === 'get').length, brNow: document.getElementById('brNow').innerText }));
  check('   when a working mode connects, both sections read again through it and the controls open', up.now === 'The phone holds: 1× · 0.5× · 1×' && !up.off && up.ops >= 5 && up.brNow === 'The phone holds: −0.30', JSON.stringify(up));
  await pn.close();
  // an older app without settingsPeek: Read-Only says it could not read, and nothing breaks
  const po = await mkPage({ priv: false, init: () => { window.addEventListener('DOMContentLoaded', () => { delete window.AndroidBridge.settingsPeek; }); } });
  await po.evaluate(() => switchView('extrasettings')); await po.waitForTimeout(400);
  const old = await po.evaluate(() => ({ now: document.getElementById('animNow').innerText, pill: document.getElementById('animPill').innerText.replace(/\s+/g, ' ').trim() }));
  check('   an app build without settingsPeek: Read-Only says it needs a working mode and could not read, the pill says "Viewing only"', /^Viewing only: changing this needs a working mode .*Could not read the animation scales: Needs a working mode/.test(old.now) && /Viewing only/.test(old.pill), JSON.stringify(old));
  await po.close();
  // no bridge (a browser)
  const pb = await mkPage({ bridge: false });
  await pb.evaluate(() => switchView('extrasettings')); await pb.waitForTimeout(300);
  const nb = await pb.evaluate(() => ({ now: document.getElementById('animNow').innerText, brNow: document.getElementById('brNow').innerText, pills: ['animPill', 'brPill', 'telPill'].map(i => document.getElementById(i).innerText.replace(/\s+/g, ' ').trim()).join('|'), tel: document.getElementById('telRamPct').innerText + ' ' + document.getElementById('telModel').innerText, chips: document.getElementById('xsChipAnim').innerText.replace(/\s+/g, ' ').trim() + '|' + document.getElementById('xsChipBatt').innerText.replace(/\s+/g, ' ').trim(), loading: document.getElementById('telemetryCard').classList.contains('is-loading') }));
  check('   in a browser: "This works in the app on a phone, not in a browser.", pills "Needs the app", telemetry "Not available" (no endless skeleton)', nb.now === 'This works in the app on a phone, not in a browser.' && nb.brNow === nb.now && nb.pills === '● Needs the app|● Needs the app|● Needs the app' && nb.tel === 'Not available Not available' && nb.chips === 'Animations Needs the app|Battery Needs the app' && !nb.loading, JSON.stringify(nb));
  await pb.evaluate(() => document.getElementById('brOpenDisplay').click());
  check('   the display-settings button in a browser only says so in a toast', (await pb.evaluate(() => document.getElementById('toastMsg').innerText)) === 'This works in the app on a phone, not in a browser.');
  await pb.close();

  // ---- 9. layout, in every width and in long languages ----
  const probe = async (w, h, label, lang) => {
    const p = await mkPage({ w, h });
    const e = (fn, a) => p.evaluate(fn, a);
    await e(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important;animation:none!important}'; document.head.appendChild(st); });
    if (lang) { await e(c => LANG_ENGINE.set(c), lang); await p.waitForTimeout(400); }
    await e(() => { window.__db.system.screen_brightness_mode = '0'; window.__db.system.screen_auto_brightness_adj = '0.3'; window.__tel.data.battery = { percent: 15, tempTenthsC: 312, plugged: 1, health: 2, status: 2 }; switchView('extrasettings'); }); await p.waitForTimeout(500);
    await e(() => { ['animMore', 'brMore', 'telMore'].forEach(i => { document.getElementById(i).open = true; }); const n = document.getElementById('animNum_window'); n.value = '15'; n.dispatchEvent(new Event('input')); const m = document.getElementById('brNum'); m.value = '2'; m.dispatchEvent(new Event('input')); document.getElementById('animMsg').textContent = 'Applied, and read back from the phone.'; });
    await p.waitForTimeout(150);
    const r = await e(() => {
      const de = document.documentElement, root = document.getElementById('extraSettingsRoot'), rr = root.getBoundingClientRect();
      const S = '#extraAnimSection, #extraBrightnessSection, #extraTelemetrySection';
      const lines = x => { const r = document.createRange(); r.selectNodeContents(x); const tops = new Set(); [...r.getClientRects()].filter(q => q.width > 0 && q.height > 0).forEach(q => tops.add(Math.round(q.top / 4))); return tops.size; };    // line boxes of the text: 1 when nothing wrapped
      const one = [...document.querySelectorAll('#extraSettingsRoot .xs-pill, #extraSettingsRoot .xs-chip, #animSpeedCard .anim-tag, #animSpeedCard .anim-chip, #brightnessCard .br-chip, #telemetryCard .tel-pct:not(.is-small), #brightnessCard .br-big, .anim-actions .xs-btn, .br-actions .xs-btn, .tel-actions .xs-btn')].filter(x => x.offsetParent !== null).filter(x => lines(x) > 1 || x.scrollWidth > x.clientWidth + 1).map(x => (x.id || x.className) + ':' + lines(x));
      const out = [...document.querySelectorAll(S + ' *')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0 && (x.getBoundingClientRect().right > rr.right + 0.5 || x.getBoundingClientRect().left < rr.left - 0.5) && x.tagName !== 'INPUT').map(x => x.id || x.className || x.tagName).slice(0, 5);
      const small = [...document.querySelectorAll('#extraSettingsRoot .xs-chip, .anim-actions .xs-btn, .br-actions .xs-btn, .tel-actions .xs-btn, #animSpeedCard .anim-range, #animSpeedCard .anim-num, #brightnessCard .br-range, #brightnessCard .br-num, #brOpenDisplay, #animMore summary, #brMore summary, #telMore summary')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().height < 43.5).map(x => (x.id || x.className) + ':' + Math.round(x.getBoundingClientRect().height));
      const clip = [...document.querySelectorAll('#telemetryCard .tel-spec, #telemetryCard .tel-ring, #brightnessCard .br-hero')].filter(x => x.scrollWidth > x.clientWidth + 1).map(x => x.id || x.className);
      const cols = getComputedStyle(document.getElementById('telSpecs')).gridTemplateColumns.split(' ').length;
      const rings = [...document.querySelectorAll('#telemetryCard .tel-gauge')].map(g => Math.round(g.getBoundingClientRect().width));
      const titleCut = [...document.querySelectorAll(S + ' .xs-sec-line .color-card-title')].filter(t => t.scrollWidth > t.clientWidth + 1).map(t => t.innerText);
      return { page: de.scrollWidth <= window.innerWidth, one, out, small, clip, cols, rings, titleCut, w: Math.round(rr.width) };
    });
    await p.close();
    return Object.assign({ label }, r);
  };
  for (const [w, h] of [[360, 740], [412, 860], [320, 640], [768, 900]]) {
    const r = await probe(w, h, w + ' px');
    check('9. [' + w + ' px] the three sections: no sideways scroll, nothing outside the column, no pill / chip / tag wraps, every control at least 44 px tall, no ring or spec chip cut, three rings side by side, ' + r.cols + ' spec columns' + (w >= 360 ? ', no section title cut' : ''), r.page && r.out.length === 0 && r.one.length === 0 && r.small.length === 0 && r.clip.length === 0 && r.rings.length === 3 && r.rings.every(x => x >= 60) && (w < 640 ? r.cols === 2 : r.cols === 3) && (w < 360 || r.titleCut.length === 0), JSON.stringify(r));
  }
  for (const [code, label] of [['de', 'German'], ['ru', 'Russian']]) {
    const r = await probe(360, 740, label, code);
    check('   [360 px, ' + label + '] long translations: no sideways scroll, nothing outside the column, no pill / chip wraps, targets 44 px, nothing cut', r.page && r.out.length === 0 && r.one.length === 0 && r.small.length === 0 && r.clip.length === 0, JSON.stringify(r));
  }

  // ---- 10. colours: every text reads at 4.5 or more in four schemes ----
  const p4 = await mkPage({ w: 360, h: 800, reduce: true });
  const e4 = (fn, a) => p4.evaluate(fn, a);
  await e4(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); window.__db.system.screen_brightness_mode = '0'; window.__db.system.screen_auto_brightness_adj = '0.3'; window.__tel.data.battery = { percent: 15, tempTenthsC: 312, plugged: 1, health: 2, status: 2 }; });
  await e4(() => switchView('extrasettings')); await p4.waitForTimeout(500);
  await e4(() => { const n = document.getElementById('animNum_window'); n.value = '15'; n.dispatchEvent(new Event('input')); const m = document.getElementById('brNum'); m.value = '2'; m.dispatchEvent(new Event('input')); const g = document.getElementById('animMsg'); g.textContent = 'x'; g.classList.add('is-err'); const t = document.getElementById('animProblem_transition'); t.textContent = 'y'; });
  const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
  const cr = (a, b2) => { const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  for (const [mode, preset] of [['dark', 'amoled'], ['light', 'amoled'], ['dark', 'sunset'], ['light', 'sunset']]) {
    await e4(a => { applyPalettePreset(a[1]); setAppearance(a[0]); }, [mode, preset]); await p4.waitForTimeout(150);
    const sample = () => e4(() => {
      const parse = s => { const m = s.match(/[\d.]+/g) || []; const k = /^color\(/.test(s) ? 255 : 1; return { n: m.slice(0, 3).map(x => Math.round(+x * k)), a: m.length >= 4 ? +m[3] : 1 }; };
      const rgb = e => parse(getComputedStyle(e).color).n;
      const bg = e => { for (let n = e; n; n = n.parentElement) { const c = parse(getComputedStyle(n).backgroundColor); if (c.a >= 0.5 && getComputedStyle(n).backgroundColor !== 'rgba(0, 0, 0, 0)') return c.n; } return [0, 0, 0]; };
      const q = s => document.querySelector(s);
      const pair = (sel, bgSel) => [rgb(q(sel)), bg(q(bgSel || sel))];
      const items = {
        'anim name': pair('#animName_window'), 'anim what': pair('#animRow_window .anim-what'), 'anim phone': pair('#animPhone_window'), 'anim chip': pair('#animRow_window .anim-chip'), 'anim chip pressed': pair('#animRow_transition .anim-chip[aria-pressed="true"]'),
        'anim warn tag': pair('#animTag_window'), 'anim problem': pair('#animProblem_transition', '#animSpeedCard'), 'anim message (error)': pair('#animMsg', '#animSpeedCard'), 'anim status': pair('#animNow', '#animSpeedCard'), 'anim field': pair('#animNum_window'),
        'br big': pair('#brBig'), 'br phrase': pair('#brPhrase'), 'br phone': pair('#brPhone'), 'br notice': pair('#brNotice span', '#brNotice'), 'br notice button': pair('#brOpenDisplay'), 'br scale': pair('#brightnessCard .br-scale', '#brightnessCard'), 'br chip': pair('#brightnessCard .br-chip'), 'br problem': pair('#brProblem', '#brightnessCard'), 'br hint': pair('#brightnessCard .br-hint', '#brightnessCard'),
        'tel pct': pair('#telRamPct', '#telemetryCard'), 'tel label': pair('#telRam .tel-label', '#telemetryCard'), 'tel sub': pair('#telRamSub', '#telemetryCard'), 'tel spec key': pair('#telSpecs .tel-spec-k', '#telSpecs .tel-spec'), 'tel spec value': pair('#telModel', '#telSpecs .tel-spec'), 'tel none': pair('#telKernel', '#telSpecs .tel-spec'),
        'copy button': pair('#telCopy'), 'reset button': pair('#animReset'), 'notes': pair('#animSpeedCard .xs-notes p', '#animSpeedCard'), 'more': pair('#animMore summary', '#animMore'),
      };
      const cs0 = getComputedStyle(document.documentElement), hex = s => { s = s.trim().replace('#', ''); if (s.length === 3) s = s.split('').map(c => c + c).join(''); return [0, 2, 4].map(i => parseInt(s.slice(i, i + 2), 16)); };
      items['apply button on accent'] = [rgb(q('#animApply')), hex(cs0.getPropertyValue('--accent'))];     // the shared gradient button, checked like the DNS one (t217): against the accent end
      ['animPill', 'brPill', 'telPill'].forEach(id => { const pl = document.getElementById(id); items[id] = [rgb(pl), bg(pl)]; });
      const bb = q('#telemetryCard .tel-bolt'); items['bolt on accent'] = [rgb(bb), hex(cs0.getPropertyValue('--accent'))];
      return items;
    });
    let colours = await sample(), same = 0;
    for (let i = 0; i < 40 && same < 2; i++) { await p4.waitForTimeout(100); const nx = await sample(); same = JSON.stringify(nx) === JSON.stringify(colours) ? same + 1 : 0; colours = nx; }
    const bad2 = [];
    Object.entries(colours).forEach(([k, v]) => { const c = cr(v[0], v[1]); if (c < 4.5) bad2.push(k + ' ' + c.toFixed(2)); });
    check('10. ' + mode + ' / ' + preset + ': every text of the three sections (names, hints, chips, tags, problems, the big number, notice, scale, rings, spec chips, buttons, pills, the bolt) reads at contrast 4.5 or more', same >= 2 && bad2.length === 0, same < 2 ? 'colours did not settle' : bad2.join(', '));
  }
  // reduced motion: no animation anywhere, the dot sits still in the middle
  await e4(() => { delete window.__tel.data.battery; });
  const mo = await e4(() => [...document.querySelectorAll('#extraSettingsRoot *')].filter(e => { const cs = getComputedStyle(e); return cs.animationName !== 'none' || (e.matches('.tel-fill, .xs-chip, .xs-btn, .anim-chip, .br-chip') && parseFloat(cs.transitionDuration) > 0); }).map(e => (e.id || e.className) + ' ' + getComputedStyle(e).animationName).slice(0, 6));
  const dotStill = await e4(() => { const d = document.getElementById('animDot_window'), p = d.parentElement; const r = d.getBoundingClientRect(), pr = p.getBoundingClientRect(); return Math.abs((r.left + r.width / 2) - (pr.left + pr.width / 2)) < 2; });
  check('   reduced motion: no animation anywhere in the tab (dots, rings, spinner, shimmer) and no transition of its own; the preview dot sits in the middle of its track', mo.length === 0 && dotStill, JSON.stringify(mo) + ' still ' + dotStill);
  await e4(() => { document.getElementById('telRefresh').click(); });
  check('   reduced motion: the refresh button does not spin', (await e4(() => getComputedStyle(document.querySelector('#telRefresh svg')).animationName)) === 'none');
  await p4.close();
  // the skeleton: before the first read the rings and chips shimmer; after it, never again
  const p7 = await mkPage({ w: 360, h: 800 });
  const sk = await p7.evaluate(() => ({ before: document.getElementById('telemetryCard').classList.contains('is-loading'), shimmer: getComputedStyle(document.getElementById('telRamSub')).animationName }));
  await p7.evaluate(() => switchView('extrasettings')); await p7.waitForTimeout(300);
  const sk2 = await p7.evaluate(() => ({ after: document.getElementById('telemetryCard').classList.contains('is-loading'), spin: getComputedStyle(document.querySelector('#telRefresh svg')).animationName, fill: getComputedStyle(document.querySelector('#telRam .tel-fill')).transitionDuration }));
  check('   with motion allowed: the telemetry card is a shimmering skeleton until the first read (telShimmer), then the rings fill with a transition and the skeleton is gone', sk.before && sk.shimmer === 'telShimmer' && !sk2.after && sk2.fill === '0.6s', JSON.stringify(sk) + ' ' + JSON.stringify(sk2));
  await p7.evaluate(() => document.getElementById('telRefresh').click());
  check('   a tap on refresh spins the icon (telSpin)', (await p7.evaluate(() => getComputedStyle(document.querySelector('#telRefresh svg')).animationName)) === 'telSpin');
  await p7.close();

  await page.close();
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all ok');
  process.exit(bad ? 1 : 0);
})();
