// v7.21.0 Android Firewall tab (the owner, 2026-10-30: "a new tab called Android Firewall ... block data and wifi access to certain applications ...
// the same features available in AFWall+ ... include the ability to toggle on/off from saved application lists ... a row in the root features tab").
// Against lib/fw_mock.js (a pretend phone that remembers what was applied): 1) the tab is registered after Custom Fonts, before About, with its label,
// short name, icon and guide link; 2) without a privileged mode the tab is LOCKED (gate card, switch and Apply off) but the rules can still be edited;
// under Root the gate goes and a status read says OFF; 3) the app list: one row per uid (two packages sharing a uid are one row with "+1"), the specials
// after the apps, the filter chips and the search, the two default columns (Wi-Fi, mobile data) and the extra columns the options add; 4) a tap on a cell
// ticks the rule for every package of the row, saved under fw_cfg, the allow / deny colour follows the mode; a column button ticks every row shown;
// 5) Apply sends the engine the rule set with packages turned into uids, the app itself and the DNS resolver added for an allow list, roamingNow and
// selfUid; the status line and tag read the phone's answer (ACTIVE), the master switch follows it; a change after that is NOT APPLIED / Apply changes;
// 6) the mode sheet (block list), 7) profiles (new, switch, rename, delete, twelve at most) and a switch while on re-applies at once; 8) Apply on boot
// hands the script to the app with the delay and is written again after every apply; 9) the Saved Applications card has a Firewall button: Block for a
// list removes the ticks of its apps in allow mode and re-applies while on; the picker inside the tab does the same; 10) the log (grouped, names resolved,
// the kernel's own packets), the active rules and Copy; 11) export to Downloads and import (next to yours, or replacing; a file that is not one is
// refused); 12) a roaming change re-applies after a moment when asked; 13) the master switch off removes the rules, the danger card's hold-to-confirm
// removes them and turns Apply on boot off; 14) the Root Features row opens the tab, locked without root; 15) Shizuku as the system user works, as the
// shell user is locked with its own reason; a phone without iptables says so; 16) a reload keeps the rules and profiles; 17) at 360 px nothing is wider
// than the screen and every cell is a finger's size. Screenshots: fw_root.png, fw_locked.png, fw_360.png.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/fw_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const dialogs = [];
  let dialogAnswer = () => ({ accept: true });
  const open = async (o, vp) => {
    const page = await b.newPage({ viewport: vp || { width: 412, height: 900 } });
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', async d => { dialogs.push({ type: d.type(), msg: d.message() }); const a = dialogAnswer(d) || { accept: true }; if (a.accept) await d.accept(a.text); else await d.dismiss(); });
    await page.addInitScript(mock.initScript, Object.assign({ mode: 'root' }, o || {}));
    await page.goto(PAGE); await page.waitForTimeout(600);
    return page;
  };
  let page = await open({ mode: 'none' });
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = id => ev(i => (document.getElementById(i) || { innerText: '' }).innerText.replace(/\s+/g, ' ').trim(), id);
  const tc = id => ev(i => (document.getElementById(i) || { textContent: '' }).textContent.replace(/\s+/g, ' ').trim(), id);          // folded cards: innerText of a hidden element is empty
  const vis = id => ev(i => { const e = document.getElementById(i); return !!e && e.getClientRects().length > 0 && getComputedStyle(e).display !== 'none'; }, id);
  const cfg = () => ev(() => kvGet('fw_cfg', null));
  const rules = () => ev(() => (kvGet('fw_cfg', { profiles: [{ rules: {} }], profile: 0 }).profiles[kvGet('fw_cfg', { profile: 0 }).profile] || { rules: {} }).rules);
  const cell = (uid, net) => ev(a => { const c = document.querySelector('#fwList .fw-cell[data-uid="' + a[0] + '"][data-net="' + a[1] + '"]'); return c ? { on: c.getAttribute('aria-pressed') === 'true', cls: c.className } : null; }, [uid, net]);
  const tapCell = async (uid, net) => { await ev(a => document.querySelector('#fwList .fw-cell[data-uid="' + a[0] + '"][data-net="' + a[1] + '"]').click(), [uid, net]); await sleep(60); };
  const rows = () => ev(() => [...document.querySelectorAll('#fwList .fw-row')].map(r => r.dataset.uid + ':' + r.querySelector('.fw-name').innerText));
  const calls = () => ev(() => window.__fw.calls.map(c => c.kind));
  const lastApply = () => ev(() => { const a = window.__fw.calls.filter(c => c.kind === 'apply'); return a.length ? a[a.length - 1].cfg : null; });
  const tag = () => txt('fwStatusTag');
  const sheetPick = async v => { await ev(x => document.querySelector('#choiceSheet .ab-sheet-row[data-v="' + x + '"]').click(), v); await sleep(120); };
  const choice = () => ev(() => { const s = document.getElementById('choiceSheet'); return { open: s.classList.contains('show'), title: document.getElementById('choiceTitle').textContent, opts: [...document.querySelectorAll('#choiceList .ab-sheet-row')].map(r => r.dataset.v) }; });
  const settle = async () => { await page.waitForFunction(() => !window.fwBusy && document.getElementById('fwApplyBtn').textContent !== 'Working…', null, { timeout: 4000 }).catch(() => {}); await sleep(80); };

  // 1. the registry
  const reg = await ev(() => ({ idx: TAB_DEFS.findIndex(t => t.key === 'firewall'), n: TAB_DEFS.length, prev: TAB_DEFS[TAB_DEFS.findIndex(t => t.key === 'firewall') - 1].key, next: TAB_DEFS[TAB_DEFS.findIndex(t => t.key === 'firewall') + 1].key,
    label: tabDef('firewall').label, name: tabDef('firewall').name, short: bnavShort('firewall'), icon: !!BNAV_ICONS.firewall && bnavIcon('firewall').indexOf(BNAV_ICON_FALLBACK) < 0, help: HELP_LINKS.firewall, on: tabIsOn('firewall'), inBar: !!document.querySelector('.tab-btn[data-tab="firewall"]'), pinned: tabDefaultPinned().includes('firewall') }));
  check('1. the tab is in the registry after Custom Fonts and right before About, on, in the top bar, not pinned, with its two-line label, name, short name, icon and guide section', reg.idx === reg.n - 2 && reg.prev === 'fonts' && reg.next === 'about' && reg.label === 'Android\nFirewall' && reg.name === 'Android Firewall' && reg.short === 'Firewall' && reg.icon && reg.help === 'tab-firewall' && reg.on && reg.inBar && !reg.pinned, JSON.stringify(reg));

  // 2. locked without a privileged mode
  await ev(() => switchView('firewall')); await sleep(300);
  check('2. no working mode: the gate card shows, the tag reads LOCKED, the switch and Apply are off', await vis('fwGate') && await tag() === 'LOCKED' && await ev(() => document.getElementById('fwMasterSwitch').disabled && document.getElementById('fwApplyBtn').disabled), (await tag()) + ' ' + (await txt('fwMasterSub')));
  check('   the gate says what unlocks it (su, or Shizuku as root or the system user; the shell user may not)', /No su was found.*Shizuku started as root or as the system user \(UID 1000\).*ADB shell user \(UID 2000\)/.test(await txt('fwGateDesc')), await txt('fwGateDesc'));
  check('   the app list is still read (apps that use the internet first) and the rules can be prepared', (await rows()).length === 5 && (await cell(10101, 'wifi')) && !(await cell(10101, 'wifi')).on, JSON.stringify(await rows()));
  await tapCell(10101, 'wifi');
  check('   a tick while locked is kept (nothing is sent)', (await cell(10101, 'wifi')).on && JSON.stringify(await rules()) === '{"com.example.alpha":["wifi"]}' && (await calls()).length === 0, JSON.stringify(await rules()));
  await page.screenshot({ path: 'fw_locked.png', fullPage: false });
  check('   Apply while locked only asks for a privileged mode', (await ev(() => { document.getElementById('fwApplyBtn').disabled = false; fwApply(); return window.__fw.calls.length; })) === 0);
  await ev(() => window.__fw.setMode('root')); await ev(() => checkAllWorkingModes()); await sleep(400);
  check('   Root granted: the gate goes, a status read says OFF, the switch is live and off', !(await vis('fwGate')) && await tag() === 'OFF' && await ev(() => !document.getElementById('fwMasterSwitch').disabled && !document.getElementById('fwMasterSwitch').checked) && (await calls()).includes('status'), (await tag()) + ' ' + JSON.stringify(await calls()));
  check('   the master row says the rules are kept but not in force', /Off\. The rules below are kept but not in force\./.test(await txt('fwMasterSub')), await txt('fwMasterSub'));

  // 3. the list
  check('3. out of the box the list shows the apps that use the internet, five rows', (await rows()).map(x => x.split(':')[0]).join() === '10200,10101,10102,1001,10104' && await txt('fwCount') === '5 apps', JSON.stringify(await rows()));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="all"]').click()); await sleep(80);
  const r = await rows();
  check('   All: one row per uid, apps by name then the specials: the two packages of UID 10104 are one row', r.join('|') === '10200:ADB/Root Toolbox|10101:Alpha Browser|10102:Beta Chat|10103:Gamma Notes|1001:Phone|10104:Shared One|-11:Linux kernel|0:Root (UID 0)|1000:Android system (UID 1000)|1051:DNS resolver (UID 1051)|2000:ADB shell (UID 2000)', r.join('|'));
  check('   the shared row names its other package and its uid', /com\.vendor\.shared\.a \+1 · UID 10104/.test(await ev(() => document.querySelector('#fwList .fw-row[data-uid="10104"] .fw-sub').innerText)));
  check('   two columns out of the box: Wi-Fi and mobile data', await ev(() => [...document.querySelectorAll('#fwCols .fw-col-btn')].map(b => b.dataset.net).join()) === 'wifi,mobile' && await ev(() => document.querySelectorAll('#fwList .fw-row[data-uid="10101"] .fw-cell').length) === 2);
  check('   the count line', await txt('fwCount') === '11 apps', await txt('fwCount'));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="user"]').click()); await sleep(80);
  check('   User apps: the four of them', (await rows()).map(x => x.split(':')[0]).join() === '10200,10101,10102,10103', JSON.stringify(await rows()));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="special"]').click()); await sleep(80);
  check('   Special: the five pseudo-apps, each with its reason', (await rows()).length === 5 && /name lookups/.test(await ev(() => document.querySelector('#fwList .fw-row[data-uid="1051"] .fw-sub').innerText)));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="net"]').click()); await sleep(80);
  check('   With internet: apps that hold the INTERNET permission (Gamma Notes has not), no specials', (await rows()).map(x => x.split(':')[0]).join() === '10200,10101,10102,1001,10104', JSON.stringify(await rows()));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="ticked"]').click()); await sleep(80);
  check('   Ticked: only Alpha', (await rows()).join() === '10101:Alpha Browser', JSON.stringify(await rows()));
  await ev(() => document.querySelector('#fwFilterRow .filter-chip[data-f="all"]').click()); await sleep(80);
  await ev(() => { const s = document.getElementById('fwSearch'); s.value = 'shared'; s.dispatchEvent(new Event('input', { bubbles: true })); }); await sleep(120);
  check('   the search finds a row by any of its packages', (await rows()).join() === '10104:Shared One', JSON.stringify(await rows()));
  await ev(() => { const s = document.getElementById('fwSearch'); s.value = ''; s.dispatchEvent(new Event('input', { bubbles: true })); }); await sleep(120);

  // 4. ticks
  check('4. a ticked cell is pressed and coloured as allowed (allow list)', (await cell(10101, 'wifi')).on && /\bon\b.*\ballow\b/.test((await cell(10101, 'wifi')).cls), (await cell(10101, 'wifi')).cls);
  await tapCell(10104, 'mobile');
  check('   a tick on the shared row rules both its packages', JSON.stringify(await rules()) === '{"com.example.alpha":["wifi"],"com.vendor.shared.a":["mobile"],"com.vendor.shared.b":["mobile"]}', JSON.stringify(await rules()));
  await tapCell(10104, 'mobile');
  check('   a second tap unticks them (an empty rule is dropped)', JSON.stringify(await rules()) === '{"com.example.alpha":["wifi"]}', JSON.stringify(await rules()));
  await tapCell(10104, 'mobile');
  check('   the Untick all button is live when rules exist', await ev(() => !document.getElementById('fwClearBtn').disabled));

  // 5. apply
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  const sent = await lastApply();
  check('5. Apply sends the engine the rule set: allow list, packages turned into uids, the app itself and the resolver added on every network, roamingNow and selfUid',
    sent && sent.mode === 'allow' && Object.keys(sent.uids).sort().join() === '10101,10104,10200,1051' && sent.uids['10101'].join() === 'wifi' && sent.uids['10104'].join() === 'mobile' && sent.uids['10200'].join() === 'wifi,mobile,roam,vpn,lan' && sent.uids['1051'].join() === 'wifi,mobile,roam,vpn,lan' && sent.roamingNow === false && sent.selfUid === 10200 && sent.lan === false && sent.log === false && !('reapply' in sent), JSON.stringify(sent));
  check('   the phone\'s answer: ACTIVE, the switch on, the rule count in the status line and the master row', await tag() === 'ACTIVE' && await ev(() => document.getElementById('fwMasterSwitch').checked) && /In force: \d+ rules in iptables\./.test(await txt('fwStatus')) && /On: \d+ rules in force, profile "Default"\./.test(await txt('fwMasterSub')), (await tag()) + ' | ' + (await txt('fwStatus')) + ' | ' + (await txt('fwMasterSub')));
  check('   the on state is saved', (await cfg()).on === true && (await cfg()).appliedKey.length > 10);
  await tapCell(10102, 'wifi');
  check('   a change after that: NOT APPLIED, the button reads Apply changes', await tag() === 'NOT APPLIED' && await txt('fwApplyBtn') === 'Apply changes' && await ev(() => document.getElementById('fwApplyBtn').classList.contains('is-dirty')) && /changes not applied yet/.test(await txt('fwMasterSub')), (await tag()) + ' ' + (await txt('fwApplyBtn')));
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   applied again: ACTIVE, Beta in the set', await tag() === 'ACTIVE' && (await lastApply()).uids['10102'].join() === 'wifi');
  await ev(() => document.querySelector('#fwCols .fw-col-btn[data-net="mobile"]').click()); await sleep(120);
  check('   the column button ticks every row shown for mobile data (the specials too)', Object.keys(await rules()).length === 12 && (await rules())['@kernel'].join() === 'mobile' && (await rules())['com.example.gamma'].join() === 'mobile', JSON.stringify(Object.keys(await rules())));
  await ev(() => document.querySelector('#fwCols .fw-col-btn[data-net="mobile"]').click()); await sleep(120);
  check('   and unticks them when every row has it', !Object.values(await rules()).some(v => v.includes('mobile')) && (await rules())['com.example.alpha'].join() === 'wifi', JSON.stringify(await rules()));

  // 6. the mode
  await ev(() => document.getElementById('fwModePick').click()); await sleep(120);
  check('6. the mode sheet offers the allow list (current) and the block list', (await choice()).open && (await choice()).opts.join() === 'allow,block' && await ev(() => document.querySelector('#choiceList .ab-sheet-row.current').dataset.v) === 'allow', JSON.stringify(await choice()));
  await sheetPick('block');
  check('   block list: the picker says so, a tick is coloured as blocked, NOT APPLIED', await txt('fwModeTxt') === 'Block list' && /\bdeny\b/.test((await cell(10101, 'wifi')).cls) && await tag() === 'NOT APPLIED');
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  const blk = await lastApply();
  check('   a block list sends no automatic entries for the app and the resolver', blk.mode === 'block' && !('10200' in blk.uids) && !('1051' in blk.uids) && blk.uids['10101'].join() === 'wifi', JSON.stringify(blk.uids));
  await ev(() => document.getElementById('fwModePick').click()); await sleep(100); await sheetPick('allow');

  // 7. profiles
  dialogAnswer = d => d.type() === 'prompt' ? { accept: true, text: 'Travel' } : { accept: false };
  await ev(() => document.getElementById('fwProfPick').click()); await sleep(100);
  check('7. the profile sheet: Default, New, Rename (no Delete with one profile)', (await choice()).opts.join() === '0,+new,+rename', JSON.stringify(await choice()));
  await sheetPick('+new'); await settle();
  check('   a new empty profile (the copy question answered no): named in the picker, no rules, the firewall re-applied with the empty set at once', await txt('fwProfTxt') === 'Travel' && JSON.stringify(await rules()) === '{}' && dialogs.filter(d => d.type === 'confirm').length === 1 && (await cfg()).profiles.length === 2, JSON.stringify(dialogs));
  check('   an empty allow list warns once before Apply', (await calls()).filter(k => k === 'apply').length === 3 && await tag() === 'NOT APPLIED');
  dialogAnswer = () => ({ accept: true });
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   the warning asked, Apply went on with the app itself and the resolver only', dialogs[dialogs.length - 1].msg.includes('almost nothing ticked') && Object.keys((await lastApply()).uids).sort().join() === '10200,1051' && await tag() === 'ACTIVE', JSON.stringify(dialogs[dialogs.length - 1]));
  await ev(() => document.getElementById('fwProfPick').click()); await sleep(100);
  check('   two profiles now, with their rule counts, and Delete', (await choice()).opts.join() === '0,1,+new,+rename,+delete' && await ev(() => [...document.querySelectorAll('#choiceList .ab-sheet-sub')].slice(0, 2).map(x => x.textContent).join('|')) === '2 rules|0 rules', await ev(() => [...document.querySelectorAll('#choiceList .ab-sheet-sub')].slice(0, 2).map(x => x.textContent).join('|')));
  await sheetPick('0'); await settle();
  check('   back to Default while on: re-applied at once with its rules', await txt('fwProfTxt') === 'Default' && (await lastApply()).uids['10101'].join() === 'wifi' && await tag() === 'ACTIVE');
  dialogAnswer = d => d.type() === 'prompt' ? { accept: true, text: 'Home' } : { accept: true };
  await ev(() => document.getElementById('fwProfPick').click()); await sleep(100); await sheetPick('+rename'); await sleep(100);
  check('   renamed', await txt('fwProfTxt') === 'Home' && (await cfg()).profiles[0].name === 'Home');
  await ev(() => document.getElementById('fwProfPick').click()); await sleep(100); await sheetPick('1'); await settle();
  await ev(() => document.getElementById('fwProfPick').click()); await sleep(100); await sheetPick('+delete'); await settle();
  check('   Travel deleted (asked first): Home is the profile again', dialogs[dialogs.length - 1].msg.includes('Delete the profile "Travel"') && (await cfg()).profiles.length === 1 && await txt('fwProfTxt') === 'Home');
  await ev(() => { for (let i = 0; i < 11; i++) fw.profiles.push({ name: 'P' + i, rules: {} }); fwSave(); });
  dialogAnswer = d => d.type() === 'prompt' ? { accept: true, text: 'One too many' } : { accept: false };
  await ev(() => fwProfileNew()); await sleep(100);
  check('   twelve profiles are the most', (await cfg()).profiles.length === 12);
  await ev(() => { fw.profiles.length = 1; fwSave(); fwRenderAll(); });
  dialogAnswer = () => ({ accept: true });

  // 8. the options and Apply on boot
  await ev(() => { const o = document.getElementById('fwOptLan'); o.checked = true; o.dispatchEvent(new Event('change', { bubbles: true })); }); await sleep(100);
  check('8. LAN as a column: a third column appears, a change to apply', await ev(() => [...document.querySelectorAll('#fwCols .fw-col-btn')].map(b => b.dataset.net).join()) === 'wifi,mobile,lan' && await tag() === 'NOT APPLIED');
  await ev(() => { ['fwOptRoam', 'fwOptLog'].forEach(i => { const o = document.getElementById(i); o.checked = true; o.dispatchEvent(new Event('change', { bubbles: true })); }); }); await sleep(100);
  check('   Roaming adds its column', await ev(() => [...document.querySelectorAll('#fwCols .fw-col-btn')].map(b => b.dataset.net).join()) === 'wifi,mobile,roam,lan');
  await ev(() => { const o = document.getElementById('fwOptBoot'); o.checked = true; o.dispatchEvent(new Event('change', { bubbles: true })); }); await sleep(100);
  const boot = await ev(() => window.__fw.boot);
  check('   Apply on boot hands the app the current rule set (lan, roam, log on) with the default delay, and is saved', boot && boot.on && JSON.parse(boot.cfg).lan === true && JSON.parse(boot.cfg).log === true && boot.delay === 15 && (await cfg()).boot === true, JSON.stringify(boot));
  await ev(() => { const s = document.getElementById('fwBootDelay'); s.value = '60'; s.dispatchEvent(new Event('change', { bubbles: true })); }); await sleep(100);
  check('   the delay is handed over when changed', (await ev(() => window.__fw.boot)).delay === 60 && (await cfg()).bootDelay === 60);
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   after an apply the boot script is written again with what was applied', (await lastApply()).lan === true && (await lastApply()).log === true && JSON.parse((await ev(() => window.__fw.boot)).cfg).log === true && await tag() === 'ACTIVE');
  const beforePre = (await calls()).length;
  await ev(() => { document.getElementById('fwPre').value = 'iptables -A atbx -d 10.0.0.9 -j RETURN'; fwCustomChange(); }); await sleep(60);
  check('   your own lines are kept and go with the next apply', (await cfg()).pre === 'iptables -A atbx -d 10.0.0.9 -j RETURN' && await tag() === 'NOT APPLIED' && (await calls()).length === beforePre);
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   sent as pre', (await lastApply()).pre === 'iptables -A atbx -d 10.0.0.9 -j RETURN');

  // 9. saved lists
  await ev(() => switchView('saved-lists')); await sleep(300);
  const slBtns = await ev(() => [...document.querySelectorAll('.saved-list-card .sl-firewall')].map(b => b.getAttribute('data-list-id')));
  check('9. every Saved Applications card has a Firewall button', slBtns.join() === 'l1,l2', JSON.stringify(slBtns));
  const nApply = (await calls()).filter(k => k === 'apply').length;
  await ev(() => document.querySelector('.saved-list-card .sl-firewall[data-list-id="l1"]').click()); await sleep(150);
  check('   it opens a sheet named after the list with Block, Allow and Forget, and says the mode', (await choice()).title === 'Social' && (await choice()).opts.join() === 'block,allow,clear' && /2 apps\. The firewall is in allow list mode\./.test(await ev(() => document.getElementById('choiceDesc').textContent)), JSON.stringify(await choice()));
  await sheetPick('block'); await settle();
  check('   Block in allow mode removes the ticks of Alpha and Beta, opens the tab and re-applies (the firewall was on)', await ev(() => currentViewName()) === 'firewall' && !('com.example.alpha' in await rules()) && !('com.example.beta' in await rules()) && (await calls()).filter(k => k === 'apply').length === nApply + 1 && !('10101' in (await lastApply()).uids), JSON.stringify(await rules()));
  await ev(() => document.getElementById('fwSavedListBtn').click()); await sleep(120);
  check('   the picker inside the tab lists the saved lists', (await choice()).opts.join() === 'l1,l2');
  await sheetPick('l1'); await sleep(150); await sheetPick('allow'); await settle();
  check('   Allow ticks them on every column shown (Wi-Fi, mobile, roaming, LAN)', (await rules())['com.example.alpha'].join() === 'wifi,mobile,roam,lan' && (await rules())['com.example.beta'].join() === 'wifi,mobile,roam,lan' && (await lastApply()).uids['10102'].join() === 'wifi,mobile,roam,lan', JSON.stringify(await rules()));
  await ev(() => document.getElementById('fwSavedListBtn').click()); await sleep(100); await sheetPick('l2'); await sleep(120); await sheetPick('clear'); await settle();
  check('   Forget removes a list\'s ticks', !('com.example.gamma' in await rules()));
  await ev(() => { tabConfig.off.push('firewall'); tabsApplyConfig(); switchView('saved-lists'); renderSavedLists(); }); await sleep(250);
  check('   with the tab switched off the cards have no Firewall button', (await ev(() => document.querySelectorAll('.saved-list-card .sl-firewall').length)) === 0);
  await ev(() => { tabConfig.off = tabConfig.off.filter(k => k !== 'firewall'); tabsApplyConfig(); switchView('firewall'); }); await sleep(250);

  // 10. the log and the active rules
  await ev(() => document.getElementById('fwLogBtn').click()); await settle(); await sleep(150);
  const log = await ev(() => [...document.querySelectorAll('#fwLogList .fw-log-row')].map(r => r.querySelector('.fw-name').textContent + '=' + r.querySelector('.fw-cnt').textContent + ' (' + r.querySelector('.fw-sub').textContent + ')'));
  check('10. the log: one row per app, the name resolved, the count, the last destination; the kernel\'s own packets named', log.join('|') === 'Beta Chat=7 (TCP 157.240.1.35:443 wlan0)|Linux kernel=2 (ICMP 224.0.0.251 wlan0)', JSON.stringify(log));
  await ev(() => document.getElementById('fwRulesBtn').click()); await settle(); await sleep(150);
  check('   Active rules shows iptables\' own listing', /-A atbx -o lo -j RETURN/.test(await tc('fwRulesOut')) && (await calls()).includes('list'));
  await ev(() => document.getElementById('fwRulesCopyBtn').click()); await sleep(60);
  check('   Copy copies it', (await ev(() => window.__calls.filter(c => c.startsWith('copy:')).length)) === 1);

  // 11. export and import
  await ev(() => fwExport(false)); await sleep(80);
  const saved = await ev(() => window.__fw.saved);
  check('11. Save to Downloads writes firewall-<stamp>.json with the mode, options and profiles', saved.length === 1 && /^firewall-\d{8}-\d{4,6}\.json$/.test(saved[0].name) && JSON.parse(saved[0].text).kind === 'firewall' && JSON.parse(saved[0].text).cfg.profiles[0].name === 'Home' && /Saved to Download\/firewall-/.test(await tc('fwBackupStatus')), saved.length ? saved[0].name : 'none');
  await ev(() => fwImportPick()); await sleep(50);
  await ev(() => window.__fw.pick('{"not":"ours"}', 'notes.json')); await sleep(100);
  check('   a file that is not a firewall file is refused by name', await tc('fwBackupStatus') === 'notes.json is not a firewall file from this app.', await tc('fwBackupStatus'));
  dialogAnswer = () => ({ accept: false });
  await ev(() => fwImportPick()); await sleep(50);
  await ev(() => window.__fw.pick(JSON.stringify({ kind: 'firewall', cfg: { mode: 'block', profiles: [{ name: 'Home', rules: { 'com.example.gamma': ['wifi', 'bogus'] } }, { name: 'Work', rules: { '@dns': ['mobile'], 'bad key!': ['wifi'] } }] } }), 'fw-work.json')); await sleep(150);
  check('   imported next to yours (the replace question answered no): the names made unique, unknown networks and bad keys dropped, the mode kept', (await cfg()).profiles.map(p => p.name).join() === 'Home,Home (imported),Work (imported)' && JSON.stringify((await cfg()).profiles[1].rules) === '{"com.example.gamma":["wifi"]}' && JSON.stringify((await cfg()).profiles[2].rules) === '{"@dns":["mobile"]}' && (await cfg()).mode === 'allow' && /Imported 2 profiles from fw-work\.json/.test(await tc('fwBackupStatus')), JSON.stringify((await cfg()).profiles.map(p => p.name)) + ' ' + (await tc('fwBackupStatus')));
  dialogAnswer = () => ({ accept: true });
  await ev(() => fwImportPick()); await sleep(50);
  await ev(() => window.__fw.pick(JSON.stringify({ kind: 'firewall', cfg: { mode: 'block', opts: { lan: true, tor: true }, profile: 0, profiles: [{ name: 'Only', rules: { 'com.example.alpha': ['tor'] } }] } }), 'fw-only.json')); await sleep(150);
  check('   replacing: the file\'s profiles, mode and options take over', (await cfg()).profiles.length === 1 && (await cfg()).mode === 'block' && (await cfg()).opts.tor === true && (await cfg()).opts.lan === true && (await cfg()).opts.roam === false && await txt('fwModeTxt') === 'Block list' && await ev(() => [...document.querySelectorAll('#fwCols .fw-col-btn')].map(b => b.dataset.net).join()) === 'wifi,mobile,lan,tor');
  await ev(() => fwImportPick()); await sleep(50);
  await ev(s => window.__fw.pick(s.text, s.name), saved[0]); await sleep(150);
  check('   the earlier export restores what it held', (await cfg()).profiles[0].name === 'Home' && (await cfg()).mode === 'allow' && (await cfg()).opts.roam === true);
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();

  // 12. roaming
  check('12. the apply sent roamingNow false while on Wi-Fi', (await lastApply()).roamingNow === false && (await cfg()).opts.reapply === true);
  const nBefore = (await calls()).filter(k => k === 'apply').length;
  await ev(() => { window.__fw.roaming = true; window.onFwNet(); }); await sleep(3000);
  check('   roaming started: re-applied after a moment with roamingNow true', (await calls()).filter(k => k === 'apply').length === nBefore + 1 && (await lastApply()).roamingNow === true, JSON.stringify(await calls()));
  await ev(() => { const o = document.getElementById('fwOptReapply'); o.checked = false; o.dispatchEvent(new Event('change', { bubbles: true })); window.__fw.roaming = false; window.onFwNet(); }); await sleep(3000);
  check('   not when the re-apply option is off', (await calls()).filter(k => k === 'apply').length === nBefore + 1);
  await ev(() => { const o = document.getElementById('fwOptReapply'); o.checked = true; o.dispatchEvent(new Event('change', { bubbles: true })); }); await settle();

  // 13. off, and the way out
  await ev(() => { const s = document.getElementById('fwMasterSwitch'); s.checked = false; s.dispatchEvent(new Event('change', { bubbles: true })); }); await settle();
  check('13. the master switch off: the disable job ran, the phone says OFF, the status line says Android\'s rules were not touched, saved off', (await calls()).slice(-1)[0] === 'disable' && await tag() === 'OFF' && /every rule of this app was removed\. Android's own rules were not touched\./.test(await txt('fwStatus')) && (await cfg()).on === false && (await ev(() => window.__fw.applied)) === null, (await tag()) + ' ' + (await txt('fwStatus')));
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   on again for the danger card', await tag() === 'ACTIVE' && (await cfg()).boot === true);
  await ev(() => document.getElementById('fwFlushBtn').click()); await sleep(150);
  const xs = await ev(() => { const s = document.getElementById('xqSheet'); const q = i => document.getElementById(i); return { open: !!s && s.classList.contains('show'), title: q('xqSheetTitle').innerText, hold: getComputedStyle(q('xqSheetHold')).display !== 'none', ok: getComputedStyle(q('xqSheetOk')).display !== 'none', text: q('xqSheetText').innerText }; });
  check('   Remove all firewall rules opens a hold-to-confirm sheet (no plain button) that names what it deletes', xs.open && xs.title === 'Remove all firewall rules' && xs.hold && !xs.ok && /iptables and ip6tables, for every profile/.test(xs.text), JSON.stringify(xs));
  await ev(() => document.getElementById('xqSheetHold').focus()); await page.keyboard.down('Enter'); await sleep(1500); await page.keyboard.up('Enter');          // the keyboard path of the hold (a finger does the same with pointerdown)
  await page.waitForFunction(() => /back to Android/.test(document.getElementById('xqSheetResult').innerText), null, { timeout: 4000 }).catch(() => {});
  check('   held: the rules are gone, Apply on boot is off, the tag reads OFF', /Removed\. The phone is back to Android's own rules\./.test(await txt('xqSheetResult')) && (await ev(() => window.__fw.applied)) === null && (await ev(() => window.__fw.boot)).on === false && (await cfg()).boot === false && await tag() === 'OFF', await txt('xqSheetResult'));
  await ev(() => xqClose && xqClose()).catch(() => {}); await sleep(100);

  // 14. Root Features row
  await ev(() => switchView('rootfeatures')); await sleep(300);
  const rf = await ev(() => { const b = document.getElementById('rfFirewallBtn'); const row = b && b.closest('.op-mode-row, .rf-row, .mode-row, div'); return b ? { text: b.textContent.trim(), locked: b.classList.contains('locked') || b.disabled, title: (row && row.innerText || '').replace(/\s+/g, ' ') } : null; });
  check('14. Root Features has an Android Firewall row with Open tab, live under root', rf && rf.text === 'Open tab' && !rf.locked && /Android Firewall/.test(rf.title) && /Block Wi-Fi, mobile data, roaming, VPN, LAN or Tor for any app/.test(rf.title), JSON.stringify(rf));
  await ev(() => document.getElementById('rfFirewallBtn').click()); await sleep(250);
  check('   it opens the tab', await ev(() => currentViewName()) === 'firewall');
  await page.screenshot({ path: 'fw_root.png', fullPage: false });

  // 15. the other modes and a phone without iptables
  await ev(() => window.__fw.setMode('none')); await ev(() => checkAllWorkingModes()); await sleep(300);
  await ev(() => switchView('rootfeatures')); await sleep(250);
  const lockedRow = await ev(() => { const v = document.getElementById('view-rootfeatures'); const row = [...v.querySelectorAll('.rf-row')].find(r => /Android Firewall/.test(r.textContent)); return { btn: !!document.getElementById('rfFirewallBtn'), row: !!row, locked: !!row && /Locked|Needs Super User/.test(row.textContent) }; });
  check('   without root the row is locked (shown, never hidden)', !lockedRow.btn && lockedRow.row && lockedRow.locked, JSON.stringify(lockedRow));
  await page.close();
  page = await open({ mode: 'system' });
  await ev(() => switchView('firewall')); await sleep(400);
  check('15. Shizuku as the system user (UID 1000): unlocked, a status read answers, the apply goes through the system backend', !(await vis('fwGate')) && await tag() === 'OFF' && (await calls()).includes('status'));
  await tapCell(10101, 'wifi'); await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   applied as the system user', await tag() === 'ACTIVE' && (await lastApply()).uids['10101'].join() === 'wifi');
  await page.close();
  page = await open({ mode: 'shell' });
  await ev(() => switchView('firewall')); await sleep(400);
  check('   Shizuku as the shell user (UID 2000): locked, with the shell-user reason', await vis('fwGate') && await tag() === 'LOCKED' && /ADB shell user \(UID 2000\) may not write kernel rules/.test(await txt('fwGateDesc')) && (await calls()).length === 0, await txt('fwGateDesc'));
  await page.close();
  page = await open({ mode: 'root', noBin: true });
  await ev(() => switchView('firewall')); await sleep(400);
  check('   a phone without iptables: the tag says NO IPTABLES after the status read', await tag() === 'NO IPTABLES', await tag());
  await tapCell(10101, 'wifi'); await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   Apply there fails in words and the firewall is not marked on', /Not applied: iptables is not on this phone/.test(await txt('fwStatus')) && (await cfg()).on === false && !(await ev(() => document.getElementById('fwMasterSwitch').checked)), await txt('fwStatus'));
  await page.close();
  page = await open({ mode: 'root', failApply: { error: 'iptables v1.8.9: Permission denied' } });
  await ev(() => switchView('firewall')); await sleep(400);
  await tapCell(10101, 'wifi'); await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  check('   an apply the phone refused: the words of iptables in the status line, OFF, nothing marked on', /Not applied: iptables v1\.8\.9: Permission denied/.test(await txt('fwStatus')) && await tag() === 'OFF' && (await cfg()).on === false, await txt('fwStatus'));
  await page.close();

  // 16. a reload keeps it all
  page = await open({ mode: 'root' });
  await ev(() => switchView('firewall')); await sleep(400);
  await tapCell(10101, 'wifi'); await tapCell(10102, 'mobile');
  dialogAnswer = d => d.type() === 'prompt' ? { accept: true, text: 'Travel' } : { accept: true };
  await ev(() => fwProfileNew()); await sleep(100);
  await ev(() => document.getElementById('fwApplyBtn').click()); await settle();
  await page.reload(); await sleep(600);
  await ev(() => switchView('firewall')); await sleep(400);
  check('16. after a reload: the profiles and their rules, the current profile and the on state (read back from the phone) are there', (await cfg()).profiles.map(p => p.name).join() === 'Default,Travel' && await txt('fwProfTxt') === 'Travel' && JSON.stringify(await rules()) === '{"com.example.alpha":["wifi"],"com.example.beta":["mobile"]}' && await tag() === 'ACTIVE' && (await cell(10101, 'wifi')).on, JSON.stringify(await cfg()));
  await page.close();

  // 17. narrow screen
  page = await open({ mode: 'root' }, { width: 360, height: 780 });
  await ev(() => switchView('firewall')); await sleep(400);
  await ev(() => { ['fwOptLan', 'fwOptRoam', 'fwOptVpn', 'fwOptTor'].forEach(i => { const o = document.getElementById(i); o.checked = true; o.dispatchEvent(new Event('change', { bubbles: true })); }); }); await sleep(150);
  const geo = await ev(() => { const v = document.getElementById('view-firewall'); const cells = [...v.querySelectorAll('#fwList .fw-row[data-uid="10101"] .fw-cell, #fwCols .fw-col-btn')].map(c => c.getBoundingClientRect()); const row = v.querySelector('#fwList .fw-row[data-uid="10101"]').getBoundingClientRect();
    return { wide: document.documentElement.scrollWidth - innerWidth, cells: cells.length, minH: Math.min(...cells.map(c => Math.round(c.height))), minW: Math.min(...cells.map(c => Math.round(c.width))), rowRight: Math.round(row.right), inner: innerWidth, name: v.querySelector('#fwList .fw-row[data-uid="10101"] .fw-name').getBoundingClientRect().width > 40 }; });
  check('17. at 360 px with all six columns nothing is wider than the screen, every cell and column button is a finger\'s size and the name still has room', geo.wide <= 0 && geo.cells === 12 && geo.minH >= 36 && geo.minW >= 36 && geo.rowRight <= geo.inner && geo.name, JSON.stringify(geo));
  await page.screenshot({ path: 'fw_360.png', fullPage: false });
  const emoji = await ev(() => /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(document.getElementById('view-firewall').innerText));
  check('   no emoji in the tab', !emoji);
  await page.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error('FAIL script error: ' + (e && e.stack || e)); process.exit(1); });
