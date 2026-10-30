// v7.20.0 Extra Settings: the Doze timing rows, the CPU governor and the memory settings rows (the owner, 2026-10-29: "The Doze setting doesn't feel like it does
// anything... how soon after phone screen goes off does Doze go into effect and how often it wakes up... The power profile never changes its state from balanced.
// Zram and Memory settings shows like there's setting options but it never changes."). On the mock phone (lib/xq_mock.js: the writes change what the reads answer):
// five timing rows each show what the phone runs ("Phone: 4 min") next to the chips, a chip marks the change (the live tag turns amber, the phone's chip dashed, the
// button says "Apply 1 change"), the Balanced preset opens the sheet with ten device_config lines and reads every row back, Phone's own deletes them, a refused write
// says which step failed, the saved timing comes back with Re-apply when a sync put the phone's own back, Android 11 writes the one device_idle_constants string; the
// why line (screen, charger, next step); the governor in Root with three clusters (the facts, the chips, the phone's own marked, Set performance read back on every
// cluster, Performance marked from the governor), a cluster that refuses, ADB mode locked with the reason; Battery saver refused while charging with the honest text;
// the memory rows (zram size, swappiness, low-memory killer) with the phone's values, Apply 3 changes through swapoff / reset / disksize / mkswap / swapon, swappiness
// and lmkd DeviceConfig, read back and saved, lmkd that cannot restart (the soft note), Shizuku locks the Root rows only, a refused swappiness, the saved line after
// a restart; xqRowCmd refuses values its table does not allow; the parsers; layout at 320 / 360 / 412 / 768 px; Viewing only disables everything.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, TESTS } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const xqMock = require('./lib/xq_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
const FX = path.join(TESTS, 'java', 'fixtures', 'powertweaks');
const rd = f => fs.readFileSync(path.join(FX, f), 'utf8');
const fixtures = { mm_stat: rd('mm_stat.txt'), disksize: rd('disksize.txt'), meminfo: rd('meminfo.txt'), help11: rd('cmd-power-help-11.txt'), help10: rd('cmd-power-help-10.txt'), helpNone: rd('cmd-power-none.txt'),
  dump_active: rd('deviceidle-active.txt'), dump_forced: rd('deviceidle-forced-idle.txt'), dump_old: rd('deviceidle-old-pending.txt'), dump_tuned: rd('deviceidle-s-tuned.txt'),
  battery_unplugged: rd('dumpsys-battery-unplugged.txt'), battery_usb: rd('dumpsys-battery-usb.txt'), power_saver: rd('dumpsys-power-saver.txt'), cpu: rd('cpufreq-policies.txt'), cpu_denied: rd('cpufreq-denied.txt'), pressure: rd('pressure-memory.txt'), swaps: rd('proc-swaps.txt') };

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 } });
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    if (opts.bridge !== false) { await page.addInitScript(mock.initScript); await page.addInitScript(xqMock.initScript, fixtures); }
    else await page.addInitScript(() => { window.AndroidBridge = undefined; });
    await page.addInitScript(m => { if (window.__mode) Object.assign(window.__mode, m); }, { priv: opts.priv !== false, mode: opts.mode || 'adb_tcp' });
    await page.addInitScript(() => { if (window.__xqDozeDefaults) window.__xqDozeDefaults(); });           // the Settings block of the report prints Android's own values
    if (opts.init) await page.addInitScript(opts.init);
    await page.goto(PAGE); await page.waitForTimeout(400);
    await page.evaluate(() => { XZR_REFRESH_MS = 3600000; });
    return page;
  };
  let page = await mkPage({ mode: 'adb_tcp' });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = sel => ev(s => { const e = document.querySelector(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; }, sel);
  const open = async () => { await ev(() => switchView('extrasettings')); await sleep(450); };
  const calls = () => ev(() => window.__xq.calls.map(c => c.bare));
  const clearCalls = () => ev(() => { window.__xq.calls.length = 0; window.__calls.op.length = 0; });
  const writes = async () => (await calls()).filter(c => !/^dumpsys deviceidle get|^cat \/sys\/block\/zram0\/mm_stat|^dumpsys power \| grep|^cmd power( help| -h)?$/.test(c));
  const sheet = () => ev(() => { const s = document.getElementById('xqSheet'); if (!s || !s.classList.contains('show')) return null; const q = i => document.getElementById(i); return { title: q('xqSheetTitle').innerText, sub: q('xqSheetSub').innerText, text: q('xqSheetText').innerText, cmd: q('xqSheetCmd').innerText, expect: q('xqSheetExpect').innerText.trim(), result: q('xqSheetResult').innerText.replace(/\s+/g, ' ').trim(), resultCls: q('xqSheetResult').className, okLabel: q('xqSheetOkT').innerText, okCls: q('xqSheetOk').className, okDisabled: q('xqSheetOk').disabled, hold: getComputedStyle(q('xqSheetHold')).display !== 'none' }; });
  const confirmTap = async (ms) => { await ev(() => document.getElementById('xqSheetOk').click()); await sleep(ms || 250); };
  const closeSheet = async () => { await ev(() => xqSheetClose()); await sleep(40); };
  const kv = key => ev(k => { const raw = window.AndroidBridge.__kv[k]; return raw ? JSON.parse(raw) : null; }, key);
  // one timing or memory row: the live tag, its class, the chip pressed, the chip that shows the phone's value, the disabled chips
  const row = (prefix, id) => ev(([p, i]) => {
    const live = document.getElementById(p + 'Live_' + i), opts = [...document.querySelectorAll('#' + p + 'Set_' + i + ' .xq-opt')];
    return { live: live.innerText.trim(), warn: live.classList.contains('is-warn'), pressed: opts.filter(o => o.getAttribute('aria-pressed') === 'true').map(o => o.dataset.v).join(','), dashed: opts.filter(o => o.classList.contains('is-live')).map(o => o.dataset.v).join(','), chips: opts.map(o => o.innerText.trim()), disabled: opts.every(o => o.disabled), count: opts.length };
  }, [prefix, id]);
  const chip = async (prefix, id, v) => { await ev(([p, i, x]) => document.querySelector('#' + p + 'Set_' + i + ' .xq-opt[data-v="' + x + '"]').click(), [prefix, id, v]); await sleep(80); };
  const applyBtn = id => ev(i => { const a = document.getElementById(i); return { label: a.innerText.trim(), disabled: a.disabled }; }, id);
  const presets = () => ev(() => [...document.querySelectorAll('#extraDozeSection .xq-preset')].map(p => p.dataset.preset + (p.getAttribute('aria-pressed') === 'true' ? '*' : '') + (p.disabled ? '!' : '')).join(','));
  const facts = () => ev(() => { const o = {}; ['saver', 'charge', 'gov', 'fixed'].forEach(k => { const e = document.getElementById('xdzFact_' + k); o[k] = e.querySelector('b').innerText + (e.querySelector('em').innerText ? ' / ' + e.querySelector('em').innerText : ''); }); return o; });

  // ---- 1. the five timing rows read the Doze constants the phone prints and name what it runs ----
  await open();
  const r1 = {}; for (const id of ['light', 'lightWake', 'deep', 'deepWake', 'window']) r1[id] = await row('xdz', id);
  check('1. five rows, each with the phone\'s value from the Settings block of the Doze report (Android\'s own: 4 min, 5–15 min, 1 h, 1–6 h, 5–10 min) and Phone\'s own pressed',
    r1.light.live === 'Phone: 4 min' && r1.lightWake.live === 'Phone: 5–15 min' && r1.deep.live === 'Phone: 1 h' && r1.deepWake.live === 'Phone: 1–6 h' && r1.window.live === 'Phone: 5–10 min' && Object.values(r1).every(r => r.pressed === 'own' && !r.warn && r.dashed === '' && !r.disabled), JSON.stringify(r1));
  check('   the chips: Phone\'s own then the choices (minutes; the deep wake-ups in hours)', r1.light.chips.join('|') === "Phone's own|1 min|2 min|4 min|8 min|15 min" && r1.deepWake.chips.join('|') === "Phone's own|30–120 min|1–6 h|2–12 h|4–24 h" && r1.deep.chips.join('|') === "Phone's own|5 min|10 min|15 min|30 min|1 h" && r1.window.chips.join('|') === "Phone's own|2–5 min|5–10 min|10–20 min", JSON.stringify([r1.light.chips, r1.deepWake.chips, r1.deep.chips]));
  const a0 = await applyBtn('xdzApply');
  check('   nothing chosen: "Apply changes" rests, the Phone\'s own preset is marked, the saved line is empty', a0.label === 'Apply changes' && a0.disabled && (await presets()) === 'own*,balanced,aggressive' && (await txt('#xdzKept')) === '' , JSON.stringify([a0, await presets()]));
  const why0 = await txt('#xdzWhy');
  check('   the why line reads the state lines: screen on, so Doze starts counting when it goes off', why0 === 'Screen on: Doze starts counting when it goes off', why0);

  // ---- 2. a chip marks the change before anything is written ----
  await chip('xdz', 'light', '2');
  const r2 = await row('xdz', 'light');
  check('2. tapping 2 min: the chip is pressed, the phone\'s chip is dashed, the live tag turns amber, the button says "Apply 1 change"; nothing was written', r2.pressed === '2' && r2.dashed === 'own' && r2.warn && (await applyBtn('xdzApply')).label === 'Apply 1 change' && !(await applyBtn('xdzApply')).disabled && (await writes()).length === 0, JSON.stringify([r2, await applyBtn('xdzApply'), await writes()]));
  await chip('xdz', 'light', 'own');
  check('   tapping the phone\'s chip again takes the change back', (await row('xdz', 'light')).pressed === 'own' && !(await row('xdz', 'light')).warn && (await applyBtn('xdzApply')).disabled);

  // ---- 3. the Balanced preset: the sheet, ten device_config lines, the read-back row by row, saved ----
  await clearCalls();
  await ev(() => document.getElementById('xdzPresetBalanced').click()); await sleep(150);
  const s3 = await sheet();
  const want3 = ['device_config put device_idle light_after_inactive_to 120000', 'device_config put device_idle light_idle_to 600000', 'device_config put device_idle light_max_idle_to 1800000', 'device_config put device_idle inactive_to 450000', 'device_config put device_idle idle_after_inactive_to 450000', 'device_config put device_idle motion_inactive_to 450000', 'device_config put device_idle idle_to 7200000', 'device_config put device_idle max_idle_to 43200000', 'device_config put device_idle idle_pending_to 300000', 'device_config put device_idle max_idle_pending_to 600000'];
  check('3. Balanced opens the sheet: "Apply Doze timing", the ten device_config put lines (Android 12+), what changes, "Apply 5 changes" with a tap (no hold)', !!s3 && s3.title === 'Apply Doze timing' && s3.sub === 'Doze' && s3.cmd === want3.join('\n') && /Changes: Light Doze after the screen goes off: 2 min · Light Doze wake-ups: 10–30 min · Deep Doze after the phone lies still: 15 min · Deep Doze wake-ups: 2–12 h · Maintenance window: 5–10 min\./.test(s3.text) && /device_idle namespace/.test(s3.text) && s3.okLabel === 'Apply 5 changes' && !s3.hold && !/warn|danger/.test(s3.okCls), JSON.stringify(s3));
  check('   the rows show the preset before the apply: every live tag amber, Balanced pressed', (await presets()) === 'own,balanced*,aggressive' && (await row('xdz', 'deep')).pressed === '15' && (await row('xdz', 'deep')).warn, await presets());
  await confirmTap(400);
  const s3b = await sheet();
  check('   Apply: the ten puts went to the shell in order, then the Doze report was read back', (await writes()).join('\n') === want3.join('\n') && (await calls()).slice(-1)[0].startsWith('dumpsys deviceidle get deep;'), JSON.stringify(await calls()));
  check('   the result is the read-back, row by row', s3b.result === 'Applied and read back from the phone: Light Doze after the screen goes off: 2 min · Light Doze wake-ups: 10–30 min · Deep Doze after the phone lies still: 15 min · Deep Doze wake-ups: 2–12 h · Maintenance window: 5–10 min.' && /ok/.test(s3b.resultCls), JSON.stringify(s3b));
  const r3 = {}; for (const id of ['light', 'lightWake', 'deep', 'deepWake', 'window']) r3[id] = await row('xdz', id);
  check('   every row now reads the phone\'s new value, the chip is pressed and nothing is amber, Balanced is marked', r3.light.live === 'Phone: 2 min' && r3.light.pressed === '2' && r3.lightWake.live === 'Phone: 10–30 min' && r3.lightWake.pressed === '10-30' && r3.deep.live === 'Phone: 15 min' && r3.deep.pressed === '15' && r3.deepWake.live === 'Phone: 2–12 h' && r3.deepWake.pressed === '120-720' && r3.window.live === 'Phone: 5–10 min' && r3.window.pressed === '5-10' && Object.values(r3).every(r => !r.warn && r.dashed === '') && (await presets()) === 'own,balanced*,aggressive' && (await applyBtn('xdzApply')).disabled, JSON.stringify(r3));
  const kv3 = await kv('xdz_tune');
  check('   the choice is saved (kv xdz_tune) with the rows and the time', !!kv3 && kv3.at > 0 && JSON.stringify(kv3.rows) === JSON.stringify({ light: '2', lightWake: '10-30', deep: '15', deepWake: '120-720', window: '5-10' }), JSON.stringify(kv3));
  await closeSheet();
  check('   the status line under the rows says the same', (await txt('#xdzStatus')).startsWith('Applied and read back from the phone: Light Doze after the screen goes off: 2 min'), await txt('#xdzStatus'));

  // ---- 4. a write the phone refuses: which step failed, the rows before it applied ----
  await clearCalls();
  await ev(() => { window.__xq.reply = bare => /^device_config put device_idle idle_to /.test(bare) ? { out: 'Failed to set property\n', rc: 1 } : null; });
  await chip('xdz', 'deepWake', '240-1440'); await chip('xdz', 'light', '1');
  check('4. two rows changed: "Apply 2 changes"', (await applyBtn('xdzApply')).label === 'Apply 2 changes');
  await ev(() => document.getElementById('xdzApply').click()); await sleep(150);
  check('   the sheet lists the four lines of the two rows', (await sheet()).cmd === 'device_config put device_idle light_after_inactive_to 60000\ndevice_config put device_idle idle_to 14400000\ndevice_config put device_idle max_idle_to 86400000' && (await sheet()).okLabel === 'Apply 2 changes', (await sheet()).cmd);
  await confirmTap(400);
  const s4 = await sheet();
  check('   the phone refuses idle_to: the sheet names the step and what the phone printed, the row applied before it, and stops there (max_idle_to was not sent)', s4.result === 'Could not apply it: device_config put device_idle idle_to 14400000 failed: Failed to set property Applied before that: Light Doze after the screen goes off: 1 min.' && /bad/.test(s4.resultCls) && !(await writes()).some(c => /max_idle_to/.test(c)), JSON.stringify([s4.result, await writes()]));
  check('   the rows show what the phone really runs: 1 min took, the wake-ups stayed 2–12 h with nothing pending', (await row('xdz', 'light')).live === 'Phone: 1 min' && (await row('xdz', 'deepWake')).live === 'Phone: 2–12 h' && (await row('xdz', 'deepWake')).pressed === '120-720' && !(await row('xdz', 'deepWake')).warn, JSON.stringify([await row('xdz', 'light'), await row('xdz', 'deepWake')]));
  check('   a failed apply does not overwrite the saved choice', JSON.stringify((await kv('xdz_tune')).rows) === JSON.stringify(kv3.rows));
  await closeSheet(); await ev(() => { window.__xq.reply = null; });

  // ---- 5. a sync puts the phone's own back: the saved line and Re-apply; Phone's own deletes the overrides and forgets the saved choice ----
  await ev(() => { window.__phone.dc = {}; window.__xqDozeDefaults(); }); await clearCalls();
  await ev(() => xdzRefresh(true)); await sleep(400);
  const kept5 = await txt('#xdzKept');
  check('5. after a refresh the phone runs its own again: every row says so and the saved line offers Re-apply', (await row('xdz', 'light')).live === 'Phone: 4 min' && (await row('xdz', 'light')).pressed === 'own' && /^Saved \S+: the phone no longer runs the Doze timing you applied \(a sync or a reset put its own back\)\. Re-apply$/.test(kept5) && (await presets()) === 'own*,balanced,aggressive', kept5);
  await ev(() => document.getElementById('xdzReapply').click()); await sleep(150);
  const s5 = await sheet();
  check('   Re-apply opens the sheet with the saved rows (the five of Balanced)', !!s5 && s5.title === 'Apply Doze timing' && s5.cmd === want3.join('\n') && s5.okLabel === 'Apply 5 changes', s5 && s5.cmd);
  await confirmTap(400);
  check('   applied and read back; the saved line is gone', (await sheet()).result.startsWith('Applied and read back from the phone:') && (await txt('#xdzKept')) === '' && (await row('xdz', 'deep')).live === 'Phone: 15 min', await txt('#xdzKept'));
  await closeSheet(); await clearCalls();
  await ev(() => document.getElementById('xdzPresetOwn').click()); await sleep(150);
  const s5b = await sheet();
  check('   Phone\'s own: ten device_config delete lines, "Apply 5 changes"', !!s5b && s5b.cmd === want3.map(c => c.replace(/^device_config put device_idle (\w+) \d+$/, 'device_config delete device_idle $1')).join('\n') && s5b.okLabel === 'Apply 5 changes', s5b && s5b.cmd);
  await confirmTap(400);
  check('   the phone runs its own again, read back; the saved choice is forgotten (kv xdz_tune null)', (await sheet()).result === 'Applied and read back from the phone: Light Doze after the screen goes off: 4 min · Light Doze wake-ups: 5–15 min · Deep Doze after the phone lies still: 1 h · Deep Doze wake-ups: 1–6 h · Maintenance window: 5–10 min.' && (await kv('xdz_tune')) === null && (await presets()) === 'own*,balanced,aggressive', JSON.stringify([(await sheet()).result, await kv('xdz_tune')]));
  await closeSheet();
  await ev(() => document.getElementById('xdzPresetOwn').click()); await sleep(100);
  check('   a preset the phone already runs opens no sheet (a toast says so)', (await sheet()) === null && /already runs these values/.test(await ev(() => document.body.innerText)));

  // ---- 6. an override the chips do not offer, and the why line on a phone that is dozing ----
  await ev(() => { window.__phone.dc = { light_after_inactive_to: '180000' }; window.__phone.settings.light_after_inactive_to = 180000; window.__phone.dump = 'tuned'; });
  await ev(() => xdzRefresh(true)); await sleep(400);
  const r6 = await row('xdz', 'light');
  check('6. a value no chip offers (3 min, set elsewhere): the live tag says "(custom)", no chip pressed, the button rests', r6.live === 'Phone: 3 min (custom)' && r6.pressed === '' && !r6.warn && (await applyBtn('xdzApply')).disabled, JSON.stringify(r6));
  const why6 = await txt('#xdzWhy');
  check('   the why line of a phone lying dark: screen off, the next deep and light Doze steps', why6 === 'Screen off · Next deep Doze step in 6 min · Next light Doze step in 3 min', why6);
  await ev(() => { window.__phone.dc = {}; window.__phone.dump = 'active'; window.__xqDozeDefaults(); });

  // ---- 7. Android 11: one device_idle_constants string instead of device_config ----
  await page.close();
  page = await mkPage({ mode: 'adb_tcp', init: () => { window.__phone.sdk = '30'; } });
  await open(); await clearCalls();
  check('7. Android 11 (sdk 30): the rows read the same Settings block', (await row('xdz', 'light')).live === 'Phone: 4 min' && (await row('xdz', 'deepWake')).live === 'Phone: 1–6 h');
  await ev(() => document.getElementById('xdzPresetAggressive').click()); await sleep(150);
  const s7 = await sheet();
  const want7 = 'settings put global device_idle_constants light_after_inactive_to=60000,light_idle_to=900000,light_max_idle_to=3600000,inactive_to=150000,idle_after_inactive_to=150000,motion_inactive_to=150000,idle_to=14400000,max_idle_to=86400000,idle_pending_to=120000,max_idle_pending_to=300000';
  check('   Aggressive writes the whole constants string in one settings put; the text names Settings.Global', !!s7 && s7.cmd === want7 && /device_idle_constants/.test(s7.text) && s7.okLabel === 'Apply 5 changes', s7 && s7.cmd);
  await confirmTap(400);
  check('   read back: 1 min, 15–60 min, 5 min, 4–24 h, 2–5 min; Aggressive marked', (await sheet()).result === 'Applied and read back from the phone: Light Doze after the screen goes off: 1 min · Light Doze wake-ups: 15–60 min · Deep Doze after the phone lies still: 5 min · Deep Doze wake-ups: 4–24 h · Maintenance window: 2–5 min.' && (await presets()) === 'own,balanced,aggressive*', (await sheet()).result);
  await closeSheet(); await clearCalls();
  await chip('xdz', 'light', 'own');
  await ev(() => document.getElementById('xdzApply').click()); await sleep(150);
  check('   one row back to the phone\'s own: the string is written again without that row', (await sheet()).cmd === want7.replace('light_after_inactive_to=60000,', ''), (await sheet()).cmd);
  await confirmTap(400); await closeSheet();
  await ev(() => document.getElementById('xdzPresetOwn').click()); await sleep(150);
  check('   Phone\'s own deletes the setting', (await sheet()).cmd === 'settings delete global device_idle_constants', (await sheet()).cmd);
  await confirmTap(400);
  check('   read back as Android\'s own', (await row('xdz', 'light')).live === 'Phone: 4 min' && (await presets()) === 'own*,balanced,aggressive');
  await closeSheet();

  // ---- 8. the power governor in Root: three clusters, the facts, the chips; Set performance read back; a refusing cluster ----
  await page.close();
  page = await mkPage({ mode: 'root', init: () => { window.__phone.cpu = window.__xq.fixtures.cpu; } });
  await open(); await clearCalls();
  const f8 = await facts();
  check('8. Root reads the clusters: the governor fact says mixed (one cluster on performance), 3 clusters with the clock range and the clock now; charger on battery with the level; saver off; fixed not reported', f8.gov === 'mixed / 3 clusters · 0.4–3.2 GHz, now 3.2 GHz' && f8.charge === 'On battery · 54 % / adaptive battery on' && f8.saver === 'off' && f8.fixed === 'not reported', JSON.stringify(f8));
  const g8 = await ev(() => ({ live: document.getElementById('xdzLive_gov').innerText, warn: document.getElementById('xdzLive_gov').classList.contains('is-warn'), chips: [...document.querySelectorAll('#xdzGovOpts .xq-opt')].map(o => o.innerText.trim() + (o.getAttribute('aria-pressed') === 'true' ? '*' : '') + (o.disabled ? '!' : '')), none: document.getElementById('xdzGovNone').innerText, own: window.AndroidBridge.__kv.xdz_gov_own === undefined ? null : JSON.parse(window.AndroidBridge.__kv.xdz_gov_own) }));
  check('   the governor row: "Phone: mixed", one chip per governor the clusters offer, no chip pressed and no phone\'s own remembered while the clusters disagree (a single own governor would move the odd cluster)', g8.live === 'Phone: mixed' && !g8.warn && g8.chips.join('|') === 'conservative|powersave|performance|schedutil' && g8.none === '' && g8.own === null, JSON.stringify(g8));
  const prof8 = await ev(() => ({ perf: document.getElementById('xdzProfPerf').disabled, why: document.getElementById('xdzProfPerfWhy').innerText, marked: [...document.querySelectorAll('.xdz-prof')].filter(p => p.getAttribute('aria-pressed') === 'true').map(p => p.dataset.prof).join() }));
  check('   Performance is not locked in Root (the performance governor can be set) though this Android has no fixed-performance command; Balanced is marked', !prof8.perf && prof8.why === '' && prof8.marked === 'balanced', JSON.stringify(prof8));
  await ev(() => document.querySelector('#xdzGovOpts .xq-opt[data-v="performance"]').click()); await sleep(150);
  const s8 = await sheet();
  check('   the sheet: "CPU governor: performance", the loop over the cpufreq policies, no phone\'s own to name, amber Set performance (warm, hungry)', !!s8 && s8.title === 'CPU governor: performance' && s8.sub === 'Power governor' && s8.cmd === 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo performance > $p/scaling_governor; done' && !/The phone's own is/.test(s8.text) && s8.okLabel === 'Set performance' && /warn/.test(s8.okCls), JSON.stringify(s8));
  await confirmTap(400);
  const s8b = await sheet();
  check('   applied: the loop, then the power read; the result says every cluster runs performance', (await writes())[0] === s8.cmd && (await calls()).slice(-1)[0].startsWith('dumpsys power | grep') && s8b.result === 'Applied and read back from the phone: every cluster runs performance.' && /ok/.test(s8b.resultCls), JSON.stringify([await writes(), s8b.result]));
  const g8b = await ev(() => ({ live: document.getElementById('xdzLive_gov').innerText, pressed: [...document.querySelectorAll('#xdzGovOpts .xq-opt[aria-pressed="true"]')].map(o => o.dataset.v).join(), marked: [...document.querySelectorAll('.xdz-prof')].filter(p => p.getAttribute('aria-pressed') === 'true').map(p => p.dataset.prof).join(), fact: document.querySelector('#xdzFact_gov b').innerText, now: document.getElementById('xdzProfNow').innerText }));
  check('   the row reads "Phone: performance", the chip is pressed, the governor fact follows, and the Performance card is marked from the governor (the profile line names it)', g8b.live === 'Phone: performance' && g8b.pressed === 'performance' && g8b.fact === 'performance' && g8b.marked === 'performance' && /CPU governor: performance/.test(g8b.now), JSON.stringify(g8b));
  await closeSheet(); await clearCalls();
  await ev(() => { window.__phone.cpu = window.__phone.cpu.split('\n').map(l => l.startsWith('policy7') ? l.replace('conservative powersave performance schedutil', 'performance schedutil') : l).join('\n'); });
  await ev(() => document.querySelector('#xdzGovOpts .xq-opt[data-v="powersave"]').click()); await sleep(150);
  await confirmTap(400);
  const s8c = await sheet();
  check('   a cluster without the governor: the shell prints Invalid argument, the result says it did not work with the mixed read-back, the row says mixed', /^It did not work: .*Invalid argument.* CPU governor: mixed \(powersave, performance\)$/.test(s8c.result) && /bad/.test(s8c.resultCls) && (await ev(() => document.getElementById('xdzLive_gov').innerText)) === 'Phone: mixed', s8c.result);
  await closeSheet(); await clearCalls();
  await ev(() => { window.__phone.cpuWritable = false; });
  await ev(() => document.querySelector('#xdzGovOpts .xq-opt[data-v="schedutil"]').click()); await sleep(150);
  await confirmTap(400);
  check('   sysfs that refuses the write (Permission denied): the result says so', /^It did not work: .*Permission denied/.test((await sheet()).result), (await sheet()).result);
  await closeSheet();
  await ev(() => { window.__phone.cpuWritable = true; });
  await ev(() => document.querySelector('#xdzGovOpts .xq-opt[data-v="schedutil"]').click()); await sleep(150);
  await confirmTap(400);
  check('   back to schedutil: applied and read back; the Performance card is no longer marked', (await sheet()).result === 'Applied and read back from the phone: every cluster runs schedutil.' && (await ev(() => [...document.querySelectorAll('.xdz-prof')].filter(p => p.getAttribute('aria-pressed') === 'true').map(p => p.dataset.prof).join())) === 'balanced');
  await closeSheet();
  await ev(() => xdzRefresh(true)); await sleep(400);
  const g8d = await ev(() => ({ own: window.AndroidBridge.__kv.xdz_gov_own === undefined ? null : JSON.parse(window.AndroidBridge.__kv.xdz_gov_own), chips: [...document.querySelectorAll('#xdzGovOpts .xq-opt')].map(o => o.innerText.trim()).join('|') }));
  check('   once every cluster agrees the governor is remembered as the phone\'s own and named on its chip', g8d.own === 'schedutil' && g8d.chips === "conservative|powersave|performance|schedutil (phone's own)", JSON.stringify(g8d));

  // ---- 9. ADB mode: the governor is Root only; Performance locked when nothing can set it ----
  await page.close();
  page = await mkPage({ mode: 'adb_tcp', init: () => { window.__phone.cpu = window.__xq.fixtures.cpu_denied; window.__phone.help = window.__xq.fixtures.help10; } });
  await open();
  const g9 = await ev(() => ({ live: document.getElementById('xdzLive_gov').innerText, warn: document.getElementById('xdzLive_gov').classList.contains('is-warn'), chips: document.querySelectorAll('#xdzGovOpts .xq-opt').length, none: document.getElementById('xdzGovNone').innerText, fact: document.querySelector('#xdzFact_gov b').innerText + ' / ' + document.querySelector('#xdzFact_gov em').innerText, perf: document.getElementById('xdzProfPerf').disabled, why: document.getElementById('xdzProfPerfWhy').innerText, fixed: document.querySelector('#xdzFact_fixed b').innerText + ' / ' + document.querySelector('#xdzFact_fixed em').innerText }));
  check('9. ADB mode cannot read the clusters: the fact says so and that Root reads and sets it, the row says "Needs Root mode" in amber with no chips, the note under it; Performance is locked ("Not on this Android version": no fixed command, no governor)', g9.live === 'Needs Root mode' && g9.warn && g9.chips === 0 && g9.none === 'This mode may not read the CPU clusters; Root can.' && g9.fact === 'not readable in this mode / Root reads and sets it' && g9.perf && g9.why === 'Not on this Android version' && g9.fixed === 'not reported / no such command on this Android', JSON.stringify(g9));
  await ev(() => xdzGovTap('performance')); await sleep(100);
  check('   xdzGovTap in ADB mode opens no sheet, a toast says Needs Root mode', (await sheet()) === null && /Needs Root mode/.test(await ev(() => document.body.innerText)));

  // ---- 10. Battery saver while charging: Android keeps it off; the fact, the card and the result say why ----
  await page.close();
  page = await mkPage({ mode: 'adb_tcp', init: () => { window.__phone.battery = window.__xq.fixtures.battery_usb; window.__phone.power = window.__xq.fixtures.power_saver; } });
  await open(); await clearCalls();
  const f10 = await facts();
  check('10. charging over USB at 87 %: the saver fact says off and that it cannot turn on while charging; the Battery saver card says "Not while charging" but stays tappable', f10.charge === 'Charging over USB · 87 % / adaptive battery on' && f10.saver === 'off / cannot turn on while charging' && (await ev(() => document.getElementById('xdzProfSaveWhy').innerText)) === 'Not while charging' && !(await ev(() => document.getElementById('xdzProfSave').disabled)), JSON.stringify(f10));
  await ev(() => { window.__revert['global/low_power'] = '0'; });
  await ev(() => document.getElementById('xdzProfSave').click()); await sleep(150);
  check('   the sheet is the Battery saver sheet', (await sheet()).title === 'Battery saver');
  await confirmTap(400);
  const s10 = await sheet();
  check('   Android puts low_power 0 straight back: the result says it is not applied because the phone is charging (USB), to unplug and apply again; Balanced stays marked', s10.result === 'Not applied: Android keeps battery saver off while the phone is charging (USB). Unplug the cable and apply it again.' && /bad/.test(s10.resultCls) && (await ev(() => [...document.querySelectorAll('.xdz-prof')].filter(p => p.getAttribute('aria-pressed') === 'true').map(p => p.dataset.prof).join())) === 'balanced', s10.result);
  check('   a refused profile is not remembered as the wanted one', (await kv('xdz_prof_want')) === null, JSON.stringify(await kv('xdz_prof_want')));
  await closeSheet();
  await ev(() => { delete window.__revert['global/low_power']; window.__db.global.low_power = '0'; window.__phone.battery = window.__xq.fixtures.battery_unplugged; });
  await ev(() => xdzRefresh(true)); await sleep(400);
  check('   unplugged: the card\'s note is gone and the fact says on battery', (await ev(() => document.getElementById('xdzProfSaveWhy').innerText)) === '' && (await facts()).charge.startsWith('On battery · 54 %'), JSON.stringify(await facts()));

  // ---- 11. the memory settings rows in Root: the phone's values, Apply 3 changes, read back, saved ----
  await page.close();
  page = await mkPage({ mode: 'root', init: () => { window.__phone.swaps = window.__xq.fixtures.swaps; } });
  await open(); await clearCalls();
  const m11 = {}; for (const id of ['zram', 'swappiness', 'lmk']) m11[id] = await row('xzr', id);
  check('11. Root: zram size 6.0 GB, swappiness 100, the low-memory killer at Android\'s own (swap low at 10 %, thrashing limit 100); Phone\'s own pressed on each; the first values seen remembered as the phone\'s own (kv xzr_own)', m11.zram.live === 'Phone: 6.0 GB' && m11.swappiness.live === 'Phone: 100' && m11.lmk.live === 'Phone: swap low at 10 %, thrashing limit 100' && Object.values(m11).every(r => r.pressed === 'own' && !r.warn && !r.disabled) && JSON.stringify(await kv('xzr_own')) === JSON.stringify({ disksize: 6442450944, swappiness: 100 }), JSON.stringify([m11, await kv('xzr_own')]));
  check('   the chips: sizes up to 1.5× the 7.5 GB of RAM without the phone\'s own 6 GB; swappiness without 100; the two killer choices', m11.zram.chips.join('|') === "Phone's own (6.0 GB)|1.0 GB|2.0 GB|3.0 GB|4.0 GB|8.0 GB" && m11.swappiness.chips.join('|') === "Phone's own (100)|20|60|150" && m11.lmk.chips.join('|') === "Phone's own|Keep more apps open|Free memory sooner", JSON.stringify([m11.zram.chips, m11.swappiness.chips, m11.lmk.chips]));
  const ram11 = await ev(() => ({ t: document.getElementById('xzrRamT').innerText, leg: ['U', 'C', 'F'].map(k => document.getElementById('xzrRamLeg' + k).innerText).join('|'), psi: document.getElementById('xzrPsi').innerText, avail: document.getElementById('xzrAvail').innerText, cache: document.getElementById('xzrCache').innerText }));
  check('   the RAM picture: available of total, used / cache / free, the pressure line from /proc/pressure/memory, Available and cache', ram11.t === '2.8 GB available of 7.5 GB' && ram11.leg === '4.7 GB|2.5 GB|267 MB' && ram11.psi === 'Memory pressure: low (0.0 % of the last 10 s stalled on memory)' && ram11.avail === '2.8 GB' && ram11.cache === '2.5 GB', JSON.stringify(ram11));
  await chip('xzr', 'zram', '8'); await chip('xzr', 'swappiness', '150'); await chip('xzr', 'lmk', 'keep');
  check('   three chips: the live tags amber, the phone\'s chips dashed, "Apply 3 changes"', (await row('xzr', 'zram')).warn && (await row('xzr', 'zram')).dashed === 'own' && (await row('xzr', 'lmk')).pressed === 'keep' && (await applyBtn('xzrApply')).label === 'Apply 3 changes' && (await writes()).length === 0, JSON.stringify([await row('xzr', 'zram'), await applyBtn('xzrApply')]));
  await ev(() => document.getElementById('xzrApply').click()); await sleep(150);
  const s11 = await sheet();
  const want11 = ['swapoff /dev/block/zram0 && echo 1 > /sys/block/zram0/reset && echo 8589934592 > /sys/block/zram0/disksize && mkswap /dev/block/zram0 && swapon /dev/block/zram0', 'echo 150 > /proc/sys/vm/swappiness', 'device_config put lmkd_native swap_free_low_percentage 5', 'device_config put lmkd_native thrashing_limit 150', 'setprop lmkd.reinit 1'];
  check('   the sheet: "Apply memory settings", the five lines, the warning about resizing (swap off, apps may close), amber "Apply 3 changes"', !!s11 && s11.title === 'Apply memory settings' && s11.cmd === want11.join('\n') && /Changes: Zram size: 8\.0 GB · Swappiness: 150 · Low-memory killer: Keep more apps open\./.test(s11.text) && /apps parked in the background may close/.test(s11.text) && s11.okLabel === 'Apply 3 changes' && /warn/.test(s11.okCls), JSON.stringify(s11));
  await confirmTap(600);
  const s11b = await sheet();
  check('   applied in order, then the memory read; the result is the read-back row by row', (await writes()).join('\n') === want11.join('\n') && s11b.result === 'Applied and read back from the phone: Zram size: 8.0 GB · Swappiness: 150 · Low-memory killer: swap low at 5 %, thrashing limit 150.' && /ok/.test(s11b.resultCls), JSON.stringify([await writes(), s11b.result]));
  const m11b = {}; for (const id of ['zram', 'swappiness', 'lmk']) m11b[id] = await row('xzr', id);
  check('   the rows read the new values, the chips are pressed, nothing amber, the button rests; saved (kv xzr_set)', m11b.zram.live === 'Phone: 8.0 GB' && m11b.zram.pressed === '8' && m11b.swappiness.live === 'Phone: 150' && m11b.swappiness.pressed === '150' && m11b.lmk.live === 'Phone: swap low at 5 %, thrashing limit 150' && m11b.lmk.pressed === 'keep' && Object.values(m11b).every(r => !r.warn) && (await applyBtn('xzrApply')).disabled && JSON.stringify((await kv('xzr_set')).rows) === JSON.stringify({ zram: '8', swappiness: '150', lmk: 'keep' }), JSON.stringify([m11b, await kv('xzr_set')]));
  check('   the phone\'s own values are still the first ones seen', JSON.stringify(await kv('xzr_own')) === JSON.stringify({ disksize: 6442450944, swappiness: 100 }));
  await closeSheet(); await clearCalls();
  // lmkd that cannot be restarted: the soft note
  await ev(() => { window.__phone.lmkReinitOk = false; });
  await chip('xzr', 'lmk', 'sooner');
  await ev(() => document.getElementById('xzrApply').click()); await sleep(150);
  check('   Free memory sooner alone: the two puts and the reinit, "Apply 1 change"', (await sheet()).cmd === 'device_config put lmkd_native swap_free_low_percentage 20\ndevice_config put lmkd_native thrashing_limit 50\nsetprop lmkd.reinit 1' && (await sheet()).okLabel === 'Apply 1 change', (await sheet()).cmd);
  await confirmTap(400);
  check('   setprop refused: applied and read back with the note that lmkd reads the values at the next restart', (await sheet()).result === 'Applied and read back from the phone: Low-memory killer: swap low at 20 %, thrashing limit 50. lmkd could not be restarted in this mode: the low-memory killer reads the new values at the next restart.' && /ok/.test((await sheet()).resultCls), (await sheet()).result);
  await closeSheet(); await clearCalls(); await ev(() => { window.__phone.lmkReinitOk = true; });
  // a refused swappiness
  await ev(() => { window.__xq.reply = bare => /^echo \d+ > \/proc\/sys\/vm\/swappiness$/.test(bare) ? { out: '/system/bin/sh: can\'t create /proc/sys/vm/swappiness: Read-only file system\n', rc: 1 } : null; });
  await chip('xzr', 'swappiness', '60'); await chip('xzr', 'lmk', 'own');
  await ev(() => document.getElementById('xzrApply').click()); await sleep(150);
  check('   swappiness 60 and the killer back to the phone\'s own: the echo, two deletes and the reinit', (await sheet()).cmd === 'echo 60 > /proc/sys/vm/swappiness\ndevice_config delete lmkd_native swap_free_low_percentage\ndevice_config delete lmkd_native thrashing_limit\nsetprop lmkd.reinit 1', (await sheet()).cmd);
  await confirmTap(400);
  const s11c = await sheet();
  check('   the kernel refuses: the result names the step and the shell\'s words; the deletes were not sent; swappiness still reads 150 and the killer keeps its values', s11c.result === "Could not apply it: echo 60 > /proc/sys/vm/swappiness failed: /system/bin/sh: can't create /proc/sys/vm/swappiness: Read-only file system" && /bad/.test(s11c.resultCls) && !(await writes()).some(c => /delete/.test(c)) && (await row('xzr', 'swappiness')).live === 'Phone: 150' && (await row('xzr', 'lmk')).live === 'Phone: swap low at 20 %, thrashing limit 50', JSON.stringify([s11c.result, await writes()]));
  await closeSheet(); await ev(() => { window.__xq.reply = null; });
  // a restart put the phone's own back: the saved line
  await ev(() => { window.__phone.disksize = '6442450944\n'; window.__phone.swappiness = '100\n'; window.__phone.lmk = {}; });
  await ev(() => xzrRefresh(true)); await sleep(400);
  const kept11 = await txt('#xzrKept');
  check('   after a restart the phone runs its own again: the saved line (zram and swappiness, from the last apply) offers Re-apply', /^Saved \S+: the phone no longer runs the memory settings you applied \(a restart puts its own back\)\. Re-apply$/.test(kept11) && (await row('xzr', 'zram')).live === 'Phone: 6.0 GB' && (await row('xzr', 'zram')).pressed === 'own', kept11);
  await ev(() => document.getElementById('xzrReapply').click()); await sleep(150);
  const s11d = await sheet();
  check('   Re-apply: the zram resize, swappiness 150 and the killer\'s Free memory sooner (the last applied), "Apply 3 changes"', !!s11d && s11d.cmd === want11[0] + '\n' + want11[1] + '\ndevice_config put lmkd_native swap_free_low_percentage 20\ndevice_config put lmkd_native thrashing_limit 50\nsetprop lmkd.reinit 1' && s11d.okLabel === 'Apply 3 changes', s11d && s11d.cmd);
  await closeSheet();
  await ev(() => { xzrPend = {}; xzrRender(); });

  // ---- 12. Shizuku: the zram and swappiness rows are Root only, the killer row works; the cache buttons unchanged ----
  await page.close();
  page = await mkPage({ mode: 'shizuku', init: () => { window.__phone.swaps = window.__xq.fixtures.swaps; } });
  await open();
  const m12 = {}; for (const id of ['zram', 'swappiness', 'lmk']) m12[id] = await row('xzr', id);
  check('12. Shizuku: the zram and swappiness rows say "Needs Root mode" in amber with their chips off; the low-memory killer row works (DeviceConfig)', m12.zram.live === 'Needs Root mode' && m12.zram.warn && m12.zram.disabled && m12.swappiness.live === 'Needs Root mode' && m12.swappiness.disabled && m12.lmk.live === 'Phone: swap low at 10 %, thrashing limit 100' && !m12.lmk.disabled, JSON.stringify(m12));
  await chip('xzr', 'lmk', 'keep');
  check('   the killer alone: "Apply 1 change"', (await applyBtn('xzrApply')).label === 'Apply 1 change' && !(await applyBtn('xzrApply')).disabled);
  await ev(() => document.getElementById('xzrApply').click()); await sleep(150);
  check('   the sheet has no Root line', (await sheet()).cmd === 'device_config put lmkd_native swap_free_low_percentage 5\ndevice_config put lmkd_native thrashing_limit 150\nsetprop lmkd.reinit 1' && !/warn/.test((await sheet()).okCls));
  await confirmTap(400);
  check('   applied and read back', (await sheet()).result === 'Applied and read back from the phone: Low-memory killer: swap low at 5 %, thrashing limit 150.');
  await closeSheet();

  // ---- 13. xqRowCmd and the parsers ----
  const u13 = await ev(() => ({
    put: xqRowCmd(XDZ_ACTS.dcPut, { key: 'idle_to', ms: '7200000' }), badKey: xqRowCmd(XDZ_ACTS.dcPut, { key: 'idle_to; rm -rf /', ms: '7200000' }), badMs: xqRowCmd(XDZ_ACTS.dcPut, { key: 'idle_to', ms: '72' }), missing: xqRowCmd(XDZ_ACTS.dcPut, { key: 'idle_to' }), fixed: xqRowCmd(XDZ_ACTS.wake, {}),
    gov: xqRowCmd(XDZ_ACTS.gov, { gov: 'schedutil' }), badGov: xqRowCmd(XDZ_ACTS.gov, { gov: 'perf; reboot' }), zram: xqRowCmd(XZR_ACTS.zramSize, { bytes: '8589934592' }), badZram: xqRowCmd(XZR_ACTS.zramSize, { bytes: '8589934592; reboot' }), sw: xqRowCmd(XZR_ACTS.swappiness, { n: '250' }), lmk: xqRowCmd(XZR_ACTS.lmkPut, { key: 'swap_free_low_percentage', val: '5' }), badLmk: xqRowCmd(XZR_ACTS.lmkPut, { key: 'other_key', val: '5' }),
    dur: [xdzParseDuration('+4m0s0ms'), xdzParseDuration('+1h30m0s0ms'), xdzParseDuration('+2d0h0m0s0ms'), xdzParseDuration('450000'), xdzParseDuration('abc')],
    split: [xdzDeepSplit(900000), xdzDeepSplit(3600000), xdzDeepSplit(60000)], range: [xdzFmtRange(7200000, 43200000), xdzFmtRange(300000, 900000), xdzFmtRange(1800000, 7200000), xdzFmtRange(900000, 3600000)], ms: [xdzFmtMs(5400000), xdzFmtMs(60000), xdzFmtMs(30000), xdzFmtMs(3600000)],
    sizes: [xzrSizeChoices(7818100), xzrSizeChoices(4000000), xzrSizeChoices(0)], sw2: [xzrParseSwappiness('100\n'), xzrParseSwappiness('250'), xzrParseSwappiness('')],
    cpu: xdzParseCpu(window.__xq.fixtures.cpu).map(p => [p.name, p.governor, p.curKhz, p.minKhz, p.maxKhz, p.available.length]), cpuDenied: xdzParseCpu(window.__xq.fixtures.cpu_denied).length,
    batt: xdzParseBattery(window.__xq.fixtures.battery_usb), saver: xdzParseSaver(window.__xq.fixtures.power_saver), psi: xzrParsePressure(window.__xq.fixtures.pressure), swapOn: [xzrSwapOn(window.__xq.fixtures.swaps), xzrSwapOn('Filename\tType\n')] }));
  check('13. xqRowCmd fills a row\'s holes only with values its table allows: a key with a shell in it, a short ms, a missing value, a governor or size with a second command, a swappiness over 200, an unknown lmkd key all give null; a row without holes gives its command', u13.put === 'device_config put device_idle idle_to 7200000' && u13.badKey === null && u13.badMs === null && u13.missing === null && u13.fixed === 'dumpsys deviceidle unforce' && u13.gov === 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo schedutil > $p/scaling_governor; done' && u13.badGov === null && u13.zram.startsWith('swapoff /dev/block/zram0 && echo 1 > /sys/block/zram0/reset && echo 8589934592 > /sys/block/zram0/disksize &&') && u13.badZram === null && u13.sw === null && u13.lmk === 'device_config put lmkd_native swap_free_low_percentage 5' && u13.badLmk === null, JSON.stringify(u13));
  check('   the duration parser reads TimeUtils (+4m0s0ms, hours, days) and plain milliseconds; nonsense is -1', JSON.stringify(u13.dur) === '[240000,5400000,172800000,450000,-1]', JSON.stringify(u13.dur));
  check('   the deep split halves the total with the motion timeout capped at 10 min and a 2 min floor; the formatters', JSON.stringify(u13.split) === '[[450000,450000,450000],[1800000,1800000,600000],[60000,60000,60000]]' && JSON.stringify(u13.range) === '["2–12 h","5–15 min","30–120 min","15–60 min"]' && JSON.stringify(u13.ms) === '["1 h 30 min","1 min","30 s","1 h"]', JSON.stringify([u13.split, u13.range, u13.ms]));
  check('   zram sizes up to 1.5× RAM (7.5 GB → 1…8 GB; 3.8 GB → 1…4 GB; unknown RAM → 1 GB only... every size offered once); swappiness 0..200 only', JSON.stringify(u13.sizes) === '[[1,2,3,4,6,8],[1,2,3,4],[1]]' && JSON.stringify(u13.sw2) === '[100,-1,-1]', JSON.stringify([u13.sizes, u13.sw2]));
  check('   the cpufreq, battery, saver, pressure and swaps parsers', JSON.stringify(u13.cpu) === '[["policy0","schedutil",1804800,400000,1804800,4],["policy4","schedutil",2515200,672000,2515200,4],["policy7","performance",3187200,787200,3187200,4]]' && u13.cpuDenied === 0 && u13.batt.plugged && u13.batt.usb && u13.batt.level === 87 && u13.saver.enabled === false && u13.saver.powered === true && u13.psi[0] === 0 && u13.swapOn[0] === true && u13.swapOn[1] === false, JSON.stringify([u13.cpu, u13.cpuDenied, u13.batt, u13.saver, u13.psi, u13.swapOn]));

  // ---- 14. layout: the controls at least 44 px tall, nothing outside the column, at 320 / 360 / 412 / 768 ----
  await page.close();
  for (const w of [320, 360, 412, 768]) {
    page = await mkPage({ mode: 'root', w, init: () => { window.__phone.cpu = window.__xq.fixtures.cpu; window.__phone.swaps = window.__xq.fixtures.swaps; } });
    await open();
    const lay = await ev(() => {
      const root = document.getElementById('extraSettingsRoot').getBoundingClientRect();
      const out = [], short = [];
      ['#extraDozeSection', '#extraZramSection'].forEach(sec => {
        document.querySelectorAll(sec + ' *').forEach(e => { const r = e.getBoundingClientRect(); if (r.width && (r.right > root.right + 1 || r.left < root.left - 1)) out.push((e.id || e.className.toString().split(' ')[0]) + ':' + Math.round(r.left) + '-' + Math.round(r.right)); });
        document.querySelectorAll(sec + ' .xq-opt, ' + sec + ' .xq-preset, ' + sec + ' .xs-btn').forEach(e => { const r = e.getBoundingClientRect(); if (r.height && r.height < 44) short.push((e.innerText || e.id).trim() + ':' + Math.round(r.height)); });
      });
      const live = document.getElementById('xdzLive_light').getBoundingClientRect(), title = document.getElementById('xdzSetT_light').getBoundingClientRect();
      return { out: out.slice(0, 6), short: short.slice(0, 6), rootRight: Math.round(root.right), tagBelowOrBeside: live.top >= title.top - 1, rows: document.querySelectorAll('#xdzTune .xq-set').length + document.querySelectorAll('#xzrSets .xq-set').length, scroll: document.documentElement.scrollWidth <= window.innerWidth };
    });
    check('14. ' + w + ' px: eight rows, every chip, preset and button at least 44 px tall, nothing outside the column, no sideways scroll', lay.rows === 8 && !lay.out.length && !lay.short.length && lay.tagBelowOrBeside && lay.scroll, JSON.stringify(lay));
    await page.close();
  }

  // ---- 15. Viewing only: every chip, preset and Apply disabled with the reason; no bridge: the same ----
  page = await mkPage({ priv: false });
  await open();
  const v15 = await ev(() => ({ chips: [...document.querySelectorAll('#extraDozeSection .xq-opt, #extraZramSection .xq-opt, #extraDozeSection .xq-preset')].every(b => b.disabled), apply: document.getElementById('xdzApply').disabled && document.getElementById('xzrApply').disabled, live: document.getElementById('xdzLive_light').innerText, mlive: document.getElementById('xzrLive_zram').innerText, lock: document.getElementById('xdzLock').innerText.replace(/\s+/g, ' ').trim(), kept: document.getElementById('xdzKept').innerText + document.getElementById('xzrKept').innerText, why: document.getElementById('xdzWhy').innerText, presets: document.querySelectorAll('#extraDozeSection .xq-preset').length }));
  check('15. Viewing only (no working mode): the three presets and every chip disabled, both Apply buttons disabled, the live tags wait, the lock box says why, no saved line, no why line', v15.chips && v15.apply && v15.live === '…' && v15.mlive === '…' && /working mode/.test(v15.lock) && v15.kept === '' && v15.why === '' && v15.presets === 3, JSON.stringify(v15));
  await page.close();
  page = await mkPage({ bridge: false });
  await open();
  const v15b = await ev(() => ({ chips: [...document.querySelectorAll('#extraDozeSection .xq-opt, #extraZramSection .xq-opt, #extraDozeSection .xq-preset')].every(b => b.disabled), apply: document.getElementById('xdzApply').disabled && document.getElementById('xzrApply').disabled, now: document.getElementById('xdzNow').innerText }));
  check('   no bridge (a browser): the same, and the state line says it works in the app', v15b.chips && v15b.apply && v15b.now === 'This works in the app on a phone, not in a browser.', JSON.stringify(v15b));
  await page.close();

  check('16. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAIL' : 'all ok');
  process.exit(bad ? 1 : 0);
})();
