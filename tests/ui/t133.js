// v7.10.9: the boxes at the top of the Apps tab wear their own colours, Saved devices (every device that was connected, with Delete), the Logcat entry window
// (Copy, More info from the Coding Agent, Web search), Web search and Explain (AI) for a hidden setting, the CPU temperature unit lives in t87, the Uninstalled box in t104.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const webMock = require('./lib/web_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 860 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const dialogs = [];
  let acceptNext = true;
  page.on('dialog', d => { dialogs.push(d.message()); if (acceptNext) d.accept(); else d.dismiss(); });
  await page.addInitScript(mock.initScript);
  await page.addInitScript(webMock.install);
  await page.addInitScript(() => {
    const base = window.AndroidBridge;
    window.__opened = [];
    const st = window.__st = { up: { '127.0.0.1:5555': 'device' }, models: {} };
    const reply = (tag, out) => setTimeout(() => window.onCdResult({ tag, out, ms: 4 }), 5);
    base.openUrl = u => window.__opened.push(u);
    base.cdSelfSerials = () => JSON.stringify(['127.0.0.1:5555', 'localhost:5555']);
    base.cdCancel = () => {};
    base.cdAdb = (tag, serial, argsJson) => {
      const a = JSON.parse(argsJson);
      if (a[0] === 'devices') return reply(tag, 'List of devices attached\n' + Object.keys(st.up).map(s => s + '\t' + st.up[s] + (st.models[s] ? ' product:p model:' + st.models[s] + ' device:d transport_id:1' : ' transport_id:1') + '\n').join(''));
      if (a[0] === 'connect') return reply(tag, "failed to connect to '" + a[1] + "': Connection refused");
      if (a[0] === 'disconnect') { delete st.up[a[1]]; return reply(tag, 'disconnected ' + a[1]); }
      if (a[0] === 'shell') return reply(tag, /^for p in/.test(a[1]) ? 'ro.product.manufacturer=samsung\nro.product.model=' + (st.models[serial] || 'SM-R930') + '\nro.build.version.release=14\nro.build.version.sdk=34\nbatt=  level: 83   status: 3' : '');
      return reply(tag, '');
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // ---- 1. the boxes ----
  const colors = await ev(() => {
    const out = {};
    ['enabled', 'frozen', 'user', 'system', 'running', 'uninstalled'].forEach(f => {
      const c = document.querySelector('.stat-card[data-filter="' + f + '"]');
      const off = getComputedStyle(c).backgroundColor;
      c.classList.add('active');
      const cs = getComputedStyle(c);
      const want = { enabled: 'apps-enabled', running: 'apps-running', frozen: 'apps-frozen', user: 'status-user', system: 'apps-system', uninstalled: 'apps-uninstalled' }[f];
      const probe = document.createElement('i'); probe.style.color = 'var(--' + want + ')'; document.body.appendChild(probe); const wantRgb = getComputedStyle(probe).color; probe.remove();
      const tone = document.createElement('i'); tone.style.color = 'var(--tone)'; c.appendChild(tone); const toneRgb = getComputedStyle(tone).color; tone.remove();
      out[f] = { off, on: cs.backgroundColor, border: cs.borderTopColor, tone: toneRgb === wantRgb ? 'ok' : toneRgb + ' != ' + wantRgb };
      c.classList.remove('active');
    });
    return out;
  });
  check('1. every box has its own colour (the one its number has): Enabled vivid green, Frozen blue, 3rd Party orange, System purple', Object.keys(colors).every(k => colors[k].tone === 'ok'), JSON.stringify(Object.keys(colors).map(k => k + ':' + colors[k].tone)));
  check('   the one in use is a clearly stronger shade than when it is off, with a ring in its colour', ['enabled', 'frozen', 'user', 'system'].every(k => colors[k].on !== colors[k].off && colors[k].border !== 'rgba(0, 0, 0, 0)'), JSON.stringify(colors.user));
  check('   the four colours differ from each other', new Set(['enabled', 'frozen', 'user', 'system'].map(k => colors[k].on)).size === 4);
  check('   a pair keeps its frame in the colour of the side that is on', await ev(() => {
    const box = document.querySelector('.stat-split'); const c = box.querySelector('.stat-card[data-filter="frozen"]');
    c.classList.add('active'); const bc = getComputedStyle(box).borderTopColor; c.classList.remove('active');
    const probe = document.createElement('i'); probe.style.color = 'var(--apps-frozen)'; document.body.appendChild(probe); const want = getComputedStyle(probe).color; probe.remove();
    return bc === want;
  }));
  check('   a frozen app\'s whole row has a blue tint (a selected one keeps the selection colours), an enabled one has none', await ev(() => {
    const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st);       // read the colours as they are, not half-way through a fade
    allApps.length = 0; allApps.push({ name: 'Alpha', pkg: 'com.t.alpha', isFrozen: true, isSystem: false }, { name: 'Bravo', pkg: 'com.t.bravo', isFrozen: false, isSystem: false }); renderApps();
    const a = document.getElementById('card_com.t.alpha'), b = document.getElementById('card_com.t.bravo');
    const ba = getComputedStyle(a).backgroundColor, bb = getComputedStyle(b).backgroundColor;
    const [r, g, bl] = ba.match(/\d+(\.\d+)?/g).map(Number);
    a.classList.add('selected'); const sel = getComputedStyle(a).backgroundColor; a.classList.remove('selected');
    return a.classList.contains('frozen') && !b.classList.contains('frozen') && ba !== bb && bl > r && bl > g && sel !== ba;
  }));
  check('   a running app\'s row is tinted green and an uninstalled app\'s row red (a frozen one stays blue, a plain one has no tint)', await ev(() => {
    allApps.length = 0; allApps.push({ name: 'Run', pkg: 'com.t.run', isRunning: true }, { name: 'Gone', pkg: 'com.t.gone', isUninstalled: true }, { name: 'Cold', pkg: 'com.t.cold', isFrozen: true }, { name: 'Plain', pkg: 'com.t.plain' }); renderApps();
    const rgb = k => { const c = getComputedStyle(document.getElementById('card_com.t.' + k)).backgroundColor; const n = c.match(/\d+(\.\d+)?/g).slice(0, 3).map(Number); return /^color\(/.test(c) ? n.map(v => v * 255) : n; };       // a mixed colour comes back as color(srgb 0..1)
    const run = rgb('run'), gone = rgb('gone'), cold = rgb('cold'), plain = rgb('plain');
    const lean = (c, i) => [0, 1, 2].every(j => j === i || (c[i] - plain[i]) > (c[j] - plain[j]));        // the tint is very subtle: compare with the plain row, towards green / red / blue
    const big = c => Math.max(Math.abs(c[0] - plain[0]), Math.abs(c[1] - plain[1]), Math.abs(c[2] - plain[2]));
    return lean(run, 1) && lean(gone, 0) && lean(cold, 2) && big(run) <= 14 && big(gone) <= 14 && big(cold) <= 14
      && document.getElementById('card_com.t.plain').className.indexOf('running') < 0 && String(plain) !== String(run) && String(plain) !== String(gone);
  }));
  await ev(() => switchView('apps')); await sleep(200);
  await ev(() => setFilter('user')); await sleep(150);
  await page.screenshot({ path: 'stat_boxes.png' });
  await ev(() => setFilter('all'));

  // ---- 2. Saved devices ----
  await ev(() => { kvSet('cd_autore', false); window.__st.up['192.168.1.20:5555'] = 'device'; window.__st.models['192.168.1.20:5555'] = 'SM_R930'; window.__st.up['192.168.1.30:5555'] = 'device'; window.__st.models['192.168.1.30:5555'] = 'Pixel_Tablet'; switchView('devices'); }); await sleep(700);
  check('2. there is a "Saved devices" line once a device has been connected, closed at first', await ev(() => { const t = document.getElementById('cdSavedToggle'); return !!t && /Saved devices \(\d\)/.test(t.innerText) && t.getAttribute('aria-expanded') === 'false' && !document.querySelector('#cdSaved .cd-saved'); }));
  await page.click('#cdSavedToggle'); await sleep(100);
  const rows = await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].map(r => r.innerText.replace(/\s+/g, ' ').trim()));
  check('   opened, it lists the devices that are up now and are saved, each with Delete', rows.length === 2 && rows.every(r => /Delete/.test(r)) && rows.some(r => /SM[ _-]R930/.test(r) && /connected now/.test(r)), JSON.stringify(rows));
  check('   a device that is connected has no Connect button', await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].every(r => r.classList.contains('up') ? !/Connect/.test(r.innerText.replace(/Connected now/i, '')) : true)));
  // one drops: stays saved, offers Connect
  await ev(() => { delete window.__st.up['192.168.1.30:5555']; }); await ev(() => cdRefreshDevices(false)); await sleep(300);
  const tab = await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].find(r => /Pixel/.test(r.innerText)));
  check('   a device that is gone stays saved and offers Connect and Delete', await ev(() => { const r = [...document.querySelectorAll('#cdSaved .cd-saved')].find(x => /Pixel/.test(x.innerText)); return !!r && /last connected|ago|just now/.test(r.innerText) && [...r.querySelectorAll('button')].map(x => x.innerText).join() === 'Connect,Delete'; }));
  // delete it: asked first
  acceptNext = false; dialogs.length = 0;
  await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].find(x => /Pixel/.test(x.innerText)).querySelectorAll('button')[1].click()); await sleep(100);
  check('   Delete asks first, and "no" keeps it', dialogs.length === 1 && /Delete .*Pixel.* from the saved devices/i.test(dialogs[0]) && await ev(() => !!cdKnown()['192.168.1.30:5555']), dialogs[0]);
  acceptNext = true; dialogs.length = 0;
  await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].find(x => /Pixel/.test(x.innerText)).querySelectorAll('button')[1].click()); await sleep(150);
  check('   confirmed, it is gone from the saved devices and from the "Not connected" list', await ev(() => !cdKnown()['192.168.1.30:5555'] && ![...document.querySelectorAll('#cdSaved .cd-saved')].some(x => /Pixel/.test(x.innerText)) && !/Pixel/.test(document.getElementById('cdLost').innerText)));
  // delete a device that is connected now: it stays connected and is not saved again by the next refresh
  await ev(() => [...document.querySelectorAll('#cdSaved .cd-saved')].find(x => /SM[ _-]R930/.test(x.innerText)).querySelectorAll('button')[0].click()); await sleep(150);
  await ev(() => cdRefreshDevices(false)); await sleep(300);
  check('   deleting a device that is connected keeps it connected, and the refresh does not save it again', await ev(() => cdIsUp('192.168.1.20:5555') && !cdKnown()['192.168.1.20:5555']));
  await ev(() => { delete window.__st.up['192.168.1.20:5555']; }); await ev(() => cdRefreshDevices(false)); await sleep(300);
  check('   when it is later connected again, it is saved again', await ev(() => { window.__st.up['192.168.1.20:5555'] = 'device'; return true; }) && (await ev(() => cdRefreshDevices(false)).then(() => sleep(300)), await ev(() => !!cdKnown()['192.168.1.20:5555'])));
  // Remember switch
  await ev(() => document.getElementById('cdSaveBtn').click()); await sleep(80);
  await ev(() => { window.__st.up['192.168.1.40:5555'] = 'device'; window.__st.models['192.168.1.40:5555'] = 'Phone_Four'; }); await ev(() => cdRefreshDevices(false)); await sleep(300);
  check('   "Remember the devices I connect" off: a new device is used but not saved', await ev(() => !cdSaveOn() && cdIsUp('192.168.1.40:5555') && !cdKnown()['192.168.1.40:5555']));
  await ev(() => document.getElementById('cdSaveBtn').click()); await sleep(80);
  // Delete all
  await ev(() => document.getElementById('cdDeleteAllBtn').click()); await sleep(150);
  check('   Delete all asks, then empties the list (devices that are connected stay connected)', dialogs.some(d => /Delete all \d+ saved devices?/.test(d)) && await ev(() => Object.keys(cdKnown()).length === 0 && cdIsUp('192.168.1.20:5555')), dialogs.join(' | '));
  check('   with nothing saved the line is gone', await ev(() => document.getElementById('cdSaved').innerHTML.trim() === '' || !document.querySelector('#cdSaved .cd-saved')));
  await ev(() => switchView('apps'));

  // ---- 3. the Logcat entry window ----
  const LOG = [
    '10-08 12:00:01.123  1234  1250 E AndroidRuntime: FATAL EXCEPTION: main',
    '10-08 12:00:01.123  1234  1250 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method on a null object',
    '10-08 12:00:02.456  900   910 I ActivityManager: Start proc 4321:com.example/u0a123 for service',
  ].join('\n');
  await ev(l => { switchView('logcat'); logcatRender(l, true); }, LOG); await sleep(250);
  check('3. every entry is a row that can be tapped', await ev(() => document.querySelectorAll('#logcatOutput .lc-row[data-i]').length === 2));
  await page.locator('#logcatOutput .lc-row').first().click(); await sleep(150);
  const w = await ev(() => ({ shown: document.getElementById('lcEntryModal').classList.contains('show'), level: document.getElementById('lcEntLevel').innerText, tag: document.getElementById('lcEntTag').innerText, meta: document.getElementById('lcEntMeta').innerText, msg: document.getElementById('lcEntMsg').innerText, btns: [...document.querySelectorAll('#lcEntryModal .lc-ent-btns button')].map(x => x.innerText) }));
  check('   tapping a row opens it in its own window: level, tag, time and ids, the whole message, and three buttons', w.shown && w.level === 'Error' && w.tag === 'AndroidRuntime' && /10-08 12:00:01\.123/.test(w.meta) && /pid 1234 \/ tid 1250/.test(w.meta) && /FATAL EXCEPTION[\s\S]*NullPointerException/.test(w.msg) && w.btns.slice(0, 3).join() === 'Copy,Ask agent,Web search', JSON.stringify(w));
  await ev(() => { window.__copied = ''; }); await page.click('#lcEntCopy'); await sleep(60);
  check('   Copy puts the entry\'s lines on the clipboard', await ev(() => /AndroidRuntime: FATAL EXCEPTION/.test(window.__copied) && /NullPointerException/.test(window.__copied)), await ev(() => window.__copied));
  await page.click('#lcEntWeb'); await sleep(60);
  const opened = await ev(() => window.__opened.slice());
  check('   Web search opens a search made of the tag and the first line', opened.length === 1 && /^https:\/\/www\.google\.com\/search\?q=/.test(opened[0]) && /AndroidRuntime/.test(decodeURIComponent(opened[0])) && /FATAL\+EXCEPTION|FATAL%20EXCEPTION/.test(opened[0]), opened[0]);
  await page.click('#lcEntMore'); await sleep(250);
  await sleep(600);
  check('   Ask agent with no default agent chosen answers with the built-in web lookup in the entry window (nothing goes to an agent)', await ev(() => document.getElementById('lcEntryModal').classList.contains('show') && /Searched the web for/.test(document.getElementById('lcEntAi').innerText) && (window.__sent || []).length === 0 && /bing\.com\/search/.test((window.__reqs || [])[0] || '')), await ev(() => document.getElementById('lcEntAi').innerText + ' | ' + JSON.stringify(window.__reqs)));
  await ev(l => { switchView('logcat'); logcatRender(l, true); lcEntryOpen(0); }, LOG); await sleep(250);
  // with an agent: the answer streams in
  await ev(() => {
    window.__sent = [];
    askAgentId = 'fake';
    window.txAgentDef = () => ({ id: 'fake', name: 'Fake AI (test)', api: 'x', provider: 'fake' });
    window.txAgentReady = () => true;
    window.txModelFor = () => 'fake-model';
    window.txEffortFor = () => 'balanced';
    window.txTurnApi = async (ag, model, effort, system, msgs, onText) => { window.__sent.push({ system, msgs }); ['This is a ', 'crash: ', 'a null object was used.'].forEach(t => onText(t)); return { text: 'This is a crash: a null object was used.', usage: { in: 1, out: 1 } }; };
  });
  await page.click('#lcEntMore'); await sleep(200);
  const ai = await ev(() => ({ text: document.getElementById('lcEntAi').innerText, sent: window.__sent[0], dis: document.getElementById('lcEntMore').disabled }));
  check('   with a Coding Agent connected, the explanation is shown in the window, and the entry was what was sent', /Asking Fake AI/.test(ai.text) && /This is a crash: a null object was used\./.test(ai.text) && ai.sent && /logcat entry/.test(ai.sent.system) && /Tag: AndroidRuntime/.test(ai.sent.msgs[0].text) && /NullPointerException/.test(ai.sent.msgs[0].text) && ai.dis === false, JSON.stringify(ai));
  await ev(() => { window.__copied = ''; }); await page.click('#lcEntAiCopy'); await sleep(60);
  check('   the answer can be copied too', await ev(() => window.__copied === 'This is a crash: a null object was used.'));
  await page.screenshot({ path: 'logcat_entry.png' });
  await ev(() => lcEntryClose());
  check('   closing the window shuts it', await ev(() => !document.getElementById('lcEntryModal').classList.contains('show')));
  await ev(l => { logcatRender(l + '\n10-08 12:00:03.000  1  1 I Another: later line', false); }, LOG); await sleep(100);
  await ev(() => { const s = window.getSelection(); const r = document.createRange(); r.selectNodeContents(document.querySelector('#logcatOutput .lc-msg')); s.removeAllRanges(); s.addRange(r); lcEntryOpen(0); });
  check('   selecting text in a row to copy it does not open the window', await ev(() => !document.getElementById('lcEntryModal').classList.contains('show')));
  await ev(() => window.getSelection().removeAllRanges());

  // ---- 4. a hidden setting: Web search and Ask agent ----
  await ev(() => switchView('settings')); await sleep(900);
  await ev(() => { sdbResetView(); const i = document.getElementById('sdbSearch'); i.value = 'zen_mode'; sdbSearchInput(); }); await sleep(300);
  await ev(() => { sdbOpenEditor(sdbNs, 'zen_mode'); }); await sleep(200);
  check('4. the editor of a setting has Web search and Ask agent next to Copy', await ev(() => { const t = [...document.querySelectorAll('#sdbEditModal .batch-sheet-tools button')].map(b => b.innerText); return t.slice(0, 3).join() === 'Web search,Ask agent,Copy name'; }));
  await ev(() => { window.__opened.length = 0; }); await page.click('#sdbEditWebBtn'); await sleep(60);
  check('   Web search looks the setting up by table and name', await ev(() => window.__opened.length === 1 && /android\+settings\+\w+\+zen_mode|android%20settings%20\w+%20zen_mode/.test(window.__opened[0])), await ev(() => window.__opened[0]));
  await ev(() => { window.__sent.length = 0; }); await page.click('#sdbEditAiBtn'); await sleep(250);
  const hs = await ev(() => ({ shown: getComputedStyle(document.getElementById('sdbEditAi')).display !== 'none', text: document.getElementById('sdbEditAi').innerText, sent: window.__sent[0] }));
  check('   Ask agent shows the answer under the buttons and says what was sent', hs.shown && /This is a crash/.test(hs.text) && /name, table and current value are sent/.test(hs.text) && hs.sent && /Setting: zen_mode/.test(hs.sent.msgs[0].text) && /Table: global/.test(hs.sent.msgs[0].text) && /Value now: 0/.test(hs.sent.msgs[0].text) && /Do Not Disturb/.test(hs.sent.msgs[0].text), JSON.stringify(hs));
  await ev(() => sdbEditClose()); await ev(() => sdbOpenEditor(sdbNs, 'zen_mode')); await sleep(100);
  check('   reopening a setting starts without the old answer', await ev(() => getComputedStyle(document.getElementById('sdbEditAi')).display === 'none' && document.getElementById('sdbEditAi').innerText === ''));
  await ev(() => sdbEditClose());

  // ---- 5. the descriptions ----
  const sample = ['power_button_short_press', 'wifi_frequency_band', 'tzinfo_content_url', 'angle_gl_driver_selection_values', 'private_dns_default_mode'];
  const have = await ev(s => s.map(k => ['global', 'secure', 'system'].some(ns => hsInfo[ns] && hsInfo[ns].has(k) && !!hsInfo[ns].get(k).t)), sample);
  check('5. the settings researched for this version are described', have.every(Boolean), JSON.stringify(have));
  check('   about 150 more settings are written by hand than before', await ev(() => (hsInfo.global.size + hsInfo.secure.size + hsInfo.system.size) > 1100));

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
