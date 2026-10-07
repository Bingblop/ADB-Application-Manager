// System UI Tuner tab: in the tab bar after Morphe Patcher; locked (but for the tiles) without a working mode; Demo Mode (the one allow flag, Enabled, the form of status icons / network / misc, changes sent on their own,
// Exit and the banner); the three Quick Settings tiles; status bar and shade (buttons, hide-for-now flags with a risk question and a 15 second Keep it, the icon slots); the notification lab; system actions and
// Restart System UI; the battery simulator (apply, unplug, read, reset, reset when the tab is left); navigation mode; display and windows with Keep it; the information reports; the credits.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__sh = []; window.__tile = []; window.__fail = null; window.__allowed = '0'; window.__tiles = { battery: false, clock: false, demo: false };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return window.__kv && window.__kv[k] ? window.__kv[k] : (k === 'perm_intro_v62' ? '1' : ''); }, saveSetting(k, v) { (window.__kv = window.__kv || {})[k] = v; },
      getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
      copyToClipboard(t) { window.__copied = t; }, shareTextFile(n, t) { window.__shared = n; return ''; }, openUrl(u) { (window.__urls = window.__urls || []).push(u); },
      // the commands are built in Java: here a command is just "op:args", and the shell answers by op
      sysui(op, argsJson) {
        const a = JSON.parse(argsJson || '{}');
        if (op.startsWith('parse.')) {
          const t = a.text || '';
          if (op === 'parse.demoAllowed') return JSON.stringify({ ok: true, data: { allowed: t.trim() === '1' } });
          if (op === 'parse.disableState') return JSON.stringify({ ok: true, data: { flags: /mDisabled1=0x4/.test(t) ? ['clock'] : [] } });
          if (op === 'parse.slots') return JSON.stringify({ ok: true, data: { slots: t.split('\n').filter(Boolean) } });
          if (op === 'parse.notificationKeys') return JSON.stringify({ ok: true, data: { keys: t.split('\n').filter(Boolean) } });
          if (op === 'parse.battery') return JSON.stringify({ ok: true, data: { frozen: /UPDATES STOPPED/.test(t) } });
          if (op === 'parse.navModes') return JSON.stringify({ ok: true, data: { available: ['threebutton', 'twobutton', 'gestural'], enabled: /\[x\].*gestural/.test(t) ? 'gestural' : 'threebutton' } });
          if (op === 'parse.wm') { const ps = /Physical size: (\S+)/.exec(t), os = /Override size: (\S+)/.exec(t), pd = /Physical density: (\S+)/.exec(t), od = /Override density: (\S+)/.exec(t); return JSON.stringify({ ok: true, data: { size: ps ? ps[1] : '', overrideSize: os ? os[1] : '', density: pd ? pd[1] : '', overrideDensity: od ? od[1] : '' } }); }
        }
        if (op === 'disableRisky') return JSON.stringify({ ok: true, data: { risky: a.flags.filter(f => ['home', 'recents', 'statusbar-expansion'].includes(f)) } });
        if (op === 'battery.set' && a.key === 'level' && a.value > 100) return JSON.stringify({ ok: false, error: 'The battery level is 0 to 100.' });
        return JSON.stringify({ ok: true, cmd: op + ':' + JSON.stringify(a) });
      },
      sysuiTile(op, name, arg) {
        window.__tile.push(op + ':' + name + ':' + arg);
        if (op === 'state') return JSON.stringify({ ok: true, canAdd: true, tiles: { battery: { enabled: window.__tiles.battery }, clock: { enabled: window.__tiles.clock }, demo: { enabled: window.__tiles.demo } } });
        if (op === 'set') { window.__tiles[name] = arg === '1'; return JSON.stringify({ ok: true }); }
        if (op === 'add') return JSON.stringify({ ok: true });
        return JSON.stringify({ ok: true });
      },
      executeShell(cmd) {
        const m = /; echo "(__RC[^"]*?):\$\?"$/.exec(cmd);
        const real = m ? cmd.slice(0, m.index) : cmd;
        window.__sh.push(real);
        let out = '', rc = 0;
        if (window.__fail && real.startsWith(window.__fail)) { out = 'Error: refused'; rc = 1; }
        else if (real.startsWith('demo.allowedRead')) out = window.__allowed;
        else if (real.startsWith('demo.allow:')) window.__allowed = JSON.parse(real.slice(11)).allow ? '1' : '0';
        else if (real.startsWith('info:{"which":"statusbar"')) out = 'mDisabled1=0x4 mDisabled2=0x0';
        else if (real.startsWith('icons')) out = 'alarm_clock\nrotate\nheadset\nwifi';
        else if (real.startsWith('notify.list')) out = 'key|1\nkey|2';
        else if (real.startsWith('battery.read')) out = window.__frozen ? 'Current Battery Service state:\n  (UPDATES STOPPED -- use \'reset\' to restart)\n  level: 15' : 'Current Battery Service state:\n  level: 80';
        else if (real.startsWith('battery.set') || real.startsWith('battery.unplug')) window.__frozen = true;
        else if (real.startsWith('battery.reset')) window.__frozen = false;
        else if (real.startsWith('nav.list')) out = 'android:\n[x] com.android.internal.systemui.navbar.threebutton\n[ ] com.android.internal.systemui.navbar.gestural';
        else if (real.startsWith('wm.read')) out = 'Physical size: 1080x2400\nPhysical density: 420' + (window.__dens ? '\nOverride density: ' + window.__dens : '');
        else if (real.startsWith('wm.density:')) window.__dens = String(JSON.parse(real.slice(11)).value);
        else if (real.startsWith('wm.density.reset')) window.__dens = '';
        else if (real.startsWith('info:')) out = 'REPORT of ' + real;
        return m ? out + '\n' + m[1] + ':' + rc : out;
      }
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(600);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const wait = ms => page.waitForTimeout(ms);
  const sh = () => ev(() => window.__sh.slice());
  const last = async () => { const s = await sh(); return s[s.length - 1] || ''; };
  const text = sel => page.locator(sel).first().innerText();
  const open = id => ev(i => { document.getElementById(i).classList.add('open'); }, id);

  // ---- the tab ----
  const tabs = await ev(() => tabsShown());
  check('System UI Tuner is in the tab bar after SD Maid SE', tabs.indexOf('sysui') === tabs.indexOf('sdm') + 1 && tabs.indexOf('sysui') > 0, tabs.join());
  check('its label is on two lines', (await ev(() => tabDef('sysui').label)) === 'System UI\nTuner');
  await ev(() => { isPrivilegedActive = false; switchView('sysui'); }); await wait(400);
  check('without a working mode a note says what needs one and that the tiles do not', /Needs a working mode/.test(await text('#suLock')) && /tiles work without one/.test(await text('#suLock')));
  check('the card explains why most of Tweaker is not here and links to Hidden Settings', /Global, Secure and System settings/.test(await text('#suTop')) && /Open Hidden Settings/.test(await text('#suTop')));
  check('credits: Tweaker by Zachary Wander (MIT) for Demo Mode and the tiles, and what the additions are', await ev(() => { const t = document.getElementById('suTop').innerText; return /Tweaker/.test(t) && /Zachary Wander/.test(t) && /MIT/.test(t) && /additions/.test(t); }));
  check('it has a help "?"', await ev(() => !!document.querySelector('#view-sysui .help-q') && document.querySelector('#view-sysui .help-q').dataset.help === 'tab-sysui'));
  check('the tiles are loaded without a working mode', (await ev(() => window.__tile)).includes('state::') && (await ev(() => document.querySelectorAll('#suTiles .su-tile').length)) === 3);
  check('the Demo Mode form was drawn with its three groups', await ev(() => Array.from(document.querySelectorAll('#suDemoForm .su-sub-h')).map(h => h.innerText).join('|').toLowerCase()) === 'status bar icons|network states|miscellaneous');
  await ev(() => { suDemoOnSet(true); }); await wait(100);
  check('Enabled with no working mode is refused with the question about the mode (nothing ran)', (await sh()).length === 0 && await ev(() => document.getElementById('privilegeModal').classList.contains('show')));
  await ev(() => { closePrivilegeModal(); isPrivilegedActive = true; onSysuiShown(); }); await wait(300);
  check('with a mode the allow flag is read', (await sh()).some(c => c.startsWith('demo.allowedRead')) && await ev(() => document.getElementById('suDemoAllow').checked === false));
  await page.screenshot({ path: 'sysui_top.png' });

  // ---- Demo Mode ----
  await open('suCardDemo'); await wait(100);
  await page.screenshot({ path: 'sysui_demo.png' });
  await ev(() => { const c = document.getElementById('suDemoOn'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(200);
  const cmds = await sh();
  check('turning it on writes the allow flag first, then sends the demo state', cmds.some(c => c.startsWith('demo.allow:{"allow":true}')) && cmds.findIndex(c => c.startsWith('demo.allow:')) < cmds.findIndex(c => c.startsWith('demo.apply:')), JSON.stringify(cmds));
  check('the state sent has the clock as HHMM, the battery, the bar style and the network defaults', await ev(() => { const c = window.__sh.find(x => x.startsWith('demo.apply:')); const a = JSON.parse(c.slice(11)); return a.clock === '1200' && a.battery.level === 100 && a.bars === 'opaque' && a.wifi.level === 4 && a.mobile.datatype === 'lte' && a.icons.volume === 'hide' && a.sims === 1; }));
  check('the banner and the flag are shown', await ev(() => document.getElementById('suDemoBanner').style.display !== 'none' && document.getElementById('suDemoAllow').checked));
  await ev(() => { window.__sh.length = 0; });
  await ev(() => { const s = document.getElementById('suD_battery'); s.value = 42; s.dispatchEvent(new Event('change')); const v = document.getElementById('suD_icons_volume'); v.value = 'vibrate'; v.dispatchEvent(new Event('change')); }); await wait(500);
  check('a change is sent on its own, after a short pause, once', (await sh()).filter(c => c.startsWith('demo.apply:')).length === 1 && await ev(() => { const a = JSON.parse(window.__sh[0].slice(11)); return a.battery.level === 42 && a.icons.volume === 'vibrate'; }));
  check('the choices are remembered', await ev(() => JSON.parse(window.__kv.sysui_demo).battery === 42));
  await ev(() => { window.__sh.length = 0; });
  await page.click('#suDemoBanner button'); await wait(200);
  check('Exit sends the exit command and hides the banner', (await sh()).some(c => c.startsWith('demo.exit')) && await ev(() => document.getElementById('suDemoBanner').style.display === 'none' && !document.getElementById('suDemoOn').checked));
  check('the Demo tile is told the state', (await ev(() => window.__tile)).includes('demoFlag::0') && (await ev(() => window.__tile)).includes('demoFlag::1'));
  await ev(() => { window.__fail = 'demo.apply'; });
  await ev(() => { const c = document.getElementById('suDemoOn'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(200);
  check('a command that fails leaves it off and says why', await ev(() => !document.getElementById('suDemoOn').checked && /refused/.test(document.getElementById('suDemoMsg').innerText) && document.getElementById('suDemoMsg').classList.contains('is-err')));
  await ev(() => { window.__fail = null; });

  // ---- tiles ----
  await open('suCardTiles');
  await ev(() => { const c = document.querySelector('#suTiles .su-tile input'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(100);
  check('turning a tile on enables its component and offers Add', (await ev(() => window.__tile)).includes('set:battery:1') && await ev(() => /Add/.test(document.querySelector('#suTiles .su-tile').innerText)));
  await page.click('#suTiles .su-tile .su-act'); await wait(100);
  check('Add asks the system to add the tile', (await ev(() => window.__tile)).includes('add:battery:'));
  await page.screenshot({ path: 'sysui_tiles.png' });

  // ---- status bar and shade ----
  await open('suCardBar');
  await page.click('#suCardBar .su-grid button:nth-child(1)'); await wait(100);
  check('Open notifications runs the shade command', (await last()).startsWith('shade:{"which":"expand-notifications"'));
  await ev(() => { window.__sh.length = 0; document.querySelector('#suFlags input[data-f="clock"]').checked = true; });
  await page.click('#suCardBar .su-btns .primary'); await wait(200);
  check('Apply hides what is ticked (a clock needs no question)', (await last()).startsWith('disable:{"flags":["clock"]') && /Hidden: clock/.test(await text('#suFlagsMsg')));
  check('and the Keep it countdown starts', await ev(() => document.getElementById('suKeep').style.display !== 'none' && /Putting it back in 1[45] seconds/.test(document.getElementById('suKeep').innerText)));
  await ev(() => { window.__sh.length = 0; }); await ev(() => suKeepEnd(false)); await wait(100);
  check('Put it back restores what was set before', (await last()).startsWith('disable:{"flags":[]') && await ev(() => su.flags.length === 0));
  await ev(() => { document.querySelector('#suFlags input[data-f="home"]').checked = true; });
  await page.click('#suCardBar .su-btns .primary'); await wait(200);
  check('hiding the Home button asks first, in words', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /navigate/.test(document.getElementById('mpAskText').innerText)));
  await ev(() => { window.__sh.length = 0; }); await page.click('#mpAskNo'); await wait(100);
  check('Cancel sends nothing', (await sh()).length === 0);
  await ev(() => { suKeepEnd(true); document.querySelectorAll('#suFlags input').forEach(i => { i.checked = false; }); });
  await page.click('#suCardBar .su-btns button:nth-child(3)'); await wait(150);
  check('"What is hidden now" reads the report', /Hidden now: clock/.test(await text('#suFlagsMsg')));
  await ev(() => { window.__sh.length = 0; });
  await page.click('#suCardBar .su-btns:nth-of-type(2) button:nth-child(1)').catch(() => {});
  await ev(() => suSlotsLoad()); await wait(150);
  check('the slots are listed and can be copied', (await ev(() => document.querySelectorAll('#suSlots .su-slot').length)) === 4 && await ev(() => { suSlotsCopy(); return window.__copied === 'alarm_clock,rotate,headset,wifi'; }));

  // ---- notification lab ----
  await open('suCardNotif');
  await ev(() => { window.__sh.length = 0; });
  await page.click('#suCardNotif .su-btns .primary'); await wait(100);
  check('Send builds the notification command with the title, text, tag and style', await ev(() => { const a = JSON.parse(window.__sh[0].slice(12)); return a.title === 'Test notification' && a.tag === 'sysui_tuner' && a.style === 'basic'; }), await last());
  await ev(() => { document.getElementById('suNStyle').value = 'messaging'; document.getElementById('suNText').value = 'Hello|Hi there'; });
  await page.click('#suCardNotif .su-btns .primary'); await wait(100);
  check('Messaging turns the text into messages', await ev(() => { const a = JSON.parse(window.__sh[window.__sh.length - 1].slice(12)); return a.messages.join() === 'Ann:Hello,Me:Hi there'; }));
  await page.click('#suCardNotif .su-btns button:nth-child(2)'); await wait(150);
  check('List shows the keys with Snooze and Unsnooze', (await ev(() => document.querySelectorAll('#suNKeys .su-tile').length)) === 2);
  await page.click('#suNKeys .su-tile .su-act'); await wait(100);
  check('Snooze sends a minute', (await last()).startsWith('notify.snooze:{"key":"key|1","ms":60000}'));

  // ---- actions ----
  await open('suCardActions');
  check('ten system actions and Restart System UI', (await ev(() => document.querySelectorAll('#suActions button').length)) === 10 && /Restart System UI/.test(await text('#suCardActions')));
  await page.click('#suActions button:nth-child(6)'); await wait(100);
  check('the Power menu is action 6', (await last()).startsWith('action:{"id":6}'));
  await ev(() => { window.__sh.length = 0; document.getElementById('suDemoOn').checked = true; su.demoOn = true; });
  await ev(() => { suRestartUi(); }); await wait(200);
  check('Restart System UI asks first', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /Demo mode ends/.test(document.getElementById('mpAskText').innerText)));
  await page.click('#mpAskOk'); await wait(200);
  check('then restarts it and demo mode is off', (await last()).startsWith('restartUi') && await ev(() => !su.demoOn));

  // ---- battery ----
  await open('suCardBatt');
  await ev(() => { window.__sh.length = 0; document.getElementById('suBLevel').value = 15; document.getElementById('suBStatus').value = '2'; document.getElementById('suBPlug').value = 'usb'; });
  await page.click('#suCardBatt .su-btns .primary'); await wait(250);
  const bc = await sh();
  check('Apply sets level, state, the three plug sources and the temperature, then reads', bc.filter(c => c.startsWith('battery.set')).length === 6 && bc.some(c => c.includes('"key":"level","value":15')) && bc.some(c => c.includes('"key":"usb","value":1')) && bc.some(c => c.includes('"key":"ac","value":0')) && bc[bc.length - 1].startsWith('battery.read'), JSON.stringify(bc));
  check('the red banner says the battery is being simulated and the report shows it', await ev(() => document.getElementById('suBattBanner').style.display !== 'none' && /UPDATES STOPPED/.test(document.getElementById('suBattOut').innerText)));
  await page.screenshot({ path: 'sysui_battery.png' });
  await ev(() => { window.__sh.length = 0; });
  await page.click('#suBattBanner button'); await wait(200);
  check('Reset now puts the real state back', (await sh()).some(c => c.startsWith('battery.reset')) && await ev(() => document.getElementById('suBattBanner').style.display === 'none'));
  await page.click('#suCardBatt .su-btns button:nth-child(2)'); await wait(200);
  check('Unplug freezes it as well', (await sh()).some(c => c.startsWith('battery.unplug')) && await ev(() => su.battSim));
  await ev(() => { window.__sh.length = 0; switchView('apps'); }); await wait(200);
  check('leaving the tab resets the simulation by itself', (await sh()).some(c => c.startsWith('battery.reset')) && await ev(() => !su.battSim));
  await ev(() => { switchView('sysui'); }); await wait(300);

  // ---- navigation ----
  await open('suCardNav');
  check('navigation mode lists the three modes and marks the one in use', await ev(() => { const t = document.getElementById('suNav').innerText; return /3-button/.test(t) && /2-button/.test(t) && /Gesture/.test(t) && /In use now/.test(t); }));
  await ev(() => { window.__sh.length = 0; suNavSet('gestural'); }); await wait(150);
  check('changing it asks first', await ev(() => document.getElementById('mpAskModal').classList.contains('show') && /navigation mode/.test(document.getElementById('mpAskTitle').innerText)));
  await page.click('#mpAskOk'); await wait(200);
  check('and enables the overlay exclusively', (await sh()).some(c => c.startsWith('nav.enable:{"mode":"gestural"')));

  // ---- display and windows ----
  await open('suCardWin');
  check('the current density and size are shown with the default', /Now 420 \(default 420\)/.test(await text('#suDensCur')) && /1080x2400/.test(await text('#suSizeCur')));
  await ev(() => { window.__sh.length = 0; document.getElementById('suDens').value = '480'; });
  await page.click('#suCardWin .su-btns .primary'); await wait(300);
  check('Apply sets the density and starts the Keep it countdown', (await sh()).some(c => c.startsWith('wm.density:{"value":480}')) && await ev(() => document.getElementById('suWinKeep').style.display !== 'none'));
  await ev(() => { window.__sh.length = 0; suKeepEnd(false); }); await wait(200);
  check('Put it back returns to the density before', (await sh()).some(c => c.startsWith('wm.density.reset')));
  await ev(() => { window.__sh.length = 0; });
  await page.click('#suCardWin .su-btns .danger'); await wait(200);
  check('Reset to default resets the density and the size', (await sh()).some(c => c.startsWith('wm.density.reset')) && (await sh()).some(c => c.startsWith('wm.size.reset')));
  await ev(() => { window.__sh.length = 0; const c = document.getElementById('suIgnoreOri'); c.checked = true; c.dispatchEvent(new Event('change')); }); await wait(100);
  check('Ignore orientation requests runs the window command', (await last()).startsWith('wm.ignoreOrientation:{"on":true}'));

  // ---- information ----
  await open('suCardInfo');
  await page.click('#suCardInfo .su-grid button:nth-child(1)'); await wait(150);
  check('an information report fills the box with Copy and Share', /mDisabled1=0x4/.test(await text('#suInfoOut')) && await ev(() => document.getElementById('suInfoBtns').style.display !== 'none'));
  check('five reports are offered', (await ev(() => document.querySelectorAll('#suCardInfo .su-grid button').length)) === 5);
  await page.screenshot({ path: 'sysui_info.png' });

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
