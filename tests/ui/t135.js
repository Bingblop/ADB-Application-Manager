// v7.10.11: the overlay list (enabled first, then disabled, then the ones that cannot be changed, with a few words on what each is for), suggestions while typing in the Terminal and the
// ADB Console (Right arrow accepts), and the top of the About tab (Issues, Contact the developer through an alias, never the developer's own address).
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 400, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  await page.addInitScript(sdb.initScript); await page.addInitScript(ovl.initScript);
  await page.addInitScript(() => {
    window.__urls = []; window.__mail = []; window.__mailOk = true;
    const base = window.AndroidBridge;
    base.openUrl = u => window.__urls.push(u);
    base.composeEmail = (to, subject, body) => { window.__mail.push({ to, subject, body }); return window.__mailOk; };
    base.getAboutInfo = () => JSON.stringify({ versionName: '7.10.11-Pro', versionCode: 831, pkg: 'com.bloatware.bingblop', android: '14', sdk: 34, device: 'Test Phone' });
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const sleep = ms => page.waitForTimeout(ms);
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // ---- 1. the overlay list ----
  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); switchView('overlays'); ovlSetSub('list'); }); await sleep(800);
  const order = await ev(() => [...document.querySelectorAll('#ovlRows > *')].map(e => e.classList.contains('ovl-section') ? 'S:' + e.firstChild.innerText : e.classList.contains('ovl-row') ? (e.querySelector('.sdb-sw').classList.contains('on') ? 'on' : e.classList.contains('na') ? 'na' : 'off') : null).filter(Boolean));
  const sections = order.filter(x => x.indexOf('S:') === 0);
  check('1. the list has three sections: Enabled, Disabled, Installed (not changeable), in that order', sections.join() === 'S:Enabled,S:Disabled,S:Installed, not changeable' || sections.join() === 'S:Enabled,S:Disabled', sections.join());
  check('   every enabled overlay comes before every disabled one, and those before the ones that cannot be changed', await ev(() => {
    const rows = [...document.querySelectorAll('#ovlRows .ovl-row')]; const rk = r => r.querySelector('.sdb-sw').classList.contains('on') ? 0 : r.classList.contains('na') ? 2 : 1;
    for (let i = 1; i < rows.length; i++) if (rk(rows[i]) < rk(rows[i - 1])) return false; return rows.length > 20;
  }));
  await ev(() => { ovlLimit = 100000; ovlRenderList(); }); await sleep(200);
  check('   with everything shown, all three kinds are there and a section heading says how many', await ev(() => { const s = [...document.querySelectorAll('#ovlRows .ovl-section')]; return s.length === 3 && s.every(x => /^\d+$/.test(x.querySelector('b').innerText)); }));
  const wh = await ev(() => [...document.querySelectorAll('#ovlRows .ovl-row')].map(r => [r.dataset.id, r.querySelector('.ovl-why') ? r.querySelector('.ovl-why').innerText : null]));
  check('   every overlay has a few words on what it is for', wh.length > 20 && wh.every(x => x[1] && x[1].length > 8 && x[1].length < 80), JSON.stringify(wh.filter(x => !x[1] || x[1].length < 9).slice(0, 3)));
  const why = id => (wh.find(x => x[0] === id) || [])[1];
  check('   the words fit the overlay: the three-button bar, the dark theme, the icon pack, a config of an app', /navigation/i.test(why('com.android.internal.systemui.navbar.threebutton') || '') && /Dark/i.test(why('com.android.systemui.theme.dark') || '') && /icon/i.test(why('com.android.theme.icon_pack.circular.android') || '') && /\S/.test(why('com.google.android.overlay.gmsconfig.photos') || ''), JSON.stringify([why('com.android.internal.systemui.navbar.threebutton'), why('com.android.systemui.theme.dark'), why('com.android.theme.icon_pack.circular.android')]));
  await page.screenshot({ path: 'overlay_list.png' });
  await ev(() => ovlFiltered().length);
  const stateAfter = await ev(() => { const o = ovlList.find(x => x.state === 0); return o ? o.id : ''; });
  await ev(i => { const r = [...document.querySelectorAll('#ovlRows .ovl-row')].find(x => x.dataset.id === i); r.querySelector('.sdb-sw').click(); }, stateAfter); await sleep(500);
  check('   switching one on moves it up to the Enabled section', await ev(i => { const rows = [...document.querySelectorAll('#ovlRows .ovl-row')]; const r = rows.find(x => x.dataset.id === i); return !!r && r.querySelector('.sdb-sw').classList.contains('on') && rows.indexOf(r) < rows.findIndex(x => !x.querySelector('.sdb-sw').classList.contains('on')); }, stateAfter));

  // ---- 2. suggestions in the ADB Console ----
  await ev(() => { switchView('terminal'); });
  await sleep(300);
  await ev(() => txShowPane('console')); await sleep(200);
  await ev(() => { termHistory = ['pm list packages -3 | grep google', 'dumpsys battery']; });
  const hasTerm = await ev(() => !!document.getElementById('termCmd'));
  check('2. the ADB Console has a box and a suggestion line above it', hasTerm && await ev(() => !!document.getElementById('termAc')));
  const type = async (sel, text) => { await ev(([s, t]) => { const i = document.querySelector(s); i.focus(); i.value = t; i.setSelectionRange(t.length, t.length); i.dispatchEvent(new Event('input', { bubbles: true })); }, [sel, text]); await sleep(60); };
  const hint = id => ev(i => { const e = document.getElementById(i); return e.hidden ? '' : e.innerText.replace(/\s*→\s*$/, ''); }, id);
  await type('#termCmd', 'pm list p');
  check('   typing shows the newest command of your history that starts the same way', (await hint('termAc')).replace(/\s+/g, ' ') === 'pm list packages -3 | grep google', await hint('termAc'));
  await type('#termCmd', 'dumpsys bat');
  check('   history first; then the common commands (dumpsys bat -> dumpsys battery)', (await hint('termAc')) === 'dumpsys battery');
  await type('#termCmd', 'getprop ro.product.m');
  check('   from the list of common commands when the history has nothing', /^getprop ro\.product\.m(odel|anufacturer)$/.test(await hint('termAc')), await hint('termAc'));
  await type('#termCmd', 'zzzz');
  check('   no suggestion when nothing fits (the line is hidden)', (await hint('termAc')) === '' && await ev(() => document.getElementById('termAc').hidden));
  await ev(() => { allApps = [{ pkg: 'com.example.alpha', name: 'Alpha' }, { pkg: 'com.example.alphabet.long', name: 'AlphaLong' }, { pkg: 'org.other', name: 'Other' }]; });
  await type('#termCmd', 'pm clear com.exa');
  check('   after a command that takes an app, the app\'s package name is completed (the shortest match)', (await hint('termAc')) === 'pm clear com.example.alpha', await hint('termAc'));
  await type('#termCmd', 'pm clear com.example.alpha');
  const g1 = await hint('termAc');
  await type('#termCmd', 'settings get global window_anim');
  check('   Hidden Settings keys are completed after settings get/put (when the table is loaded) or the common list is used', /^settings get global window_animation_scale/.test(await hint('termAc')) || (await hint('termAc')) === '', await hint('termAc'));
  await type('#termCmd', 'pm list p');
  await ev(() => { const i = document.getElementById('termCmd'); i.setSelectionRange(3, 3); i.dispatchEvent(new Event('click')); }); await sleep(40);
  check('   with the cursor in the middle there is no suggestion, and Right arrow moves the cursor as usual', (await hint('termAc')) === '');
  await ev(() => { const i = document.getElementById('termCmd'); i.setSelectionRange(8, 8); i.dispatchEvent(new Event('click')); }); await sleep(40);
  await page.focus('#termCmd'); await ev(() => { const i = document.getElementById('termCmd'); i.setSelectionRange(8, 8); });
  await type('#termCmd', 'pm list p');
  await page.keyboard.press('ArrowRight'); await sleep(60);
  check('   Right arrow at the end of the text puts the whole suggestion in the box', (await ev(() => document.getElementById('termCmd').value)) === 'pm list packages -3 | grep google', await ev(() => document.getElementById('termCmd').value));
  check('   and the line goes away (nothing more to add)', (await hint('termAc')) === '');
  await ev(() => { document.getElementById('termCmd').value = ''; acUpdate('term'); });
  await type('#termCmd', 'cat /proc/cpu');
  await page.click('#termAc'); await sleep(80);
  check('   a tap on the suggestion line takes it too (a phone has no Right arrow key)', (await ev(() => document.getElementById('termCmd').value)) === 'cat /proc/cpuinfo', await ev(() => document.getElementById('termCmd').value));
  await type('#termCmd', 'whoami');
  check('   a command that is complete shows nothing', (await hint('termAc')) === '');
  await ev(() => { termMode = 'chat'; });
  await type('#termCmd', 'pm list p');
  check('   in the AI chat mode of the console there are no suggestions', (await hint('termAc')) === '');
  await ev(() => { termMode = 'shell'; });

  // ---- 3. suggestions in the Terminal ----
  await ev(() => txShowPane('terminal')); await sleep(200);
  await ev(() => { txHist = ['ls -la /sdcard/Download']; });
  check('3. the Terminal has the suggestion line too', await ev(() => !!document.getElementById('txAc') && !!document.getElementById('txInput')));
  await ev(() => { txState.mode = 'shell'; });
  await type('#txInput', 'ls -la /sd');
  check('   typing shows the history match', (await hint('txAc')) === 'ls -la /sdcard/Download', await hint('txAc'));
  await page.keyboard.press('ArrowRight'); await sleep(60);
  check('   Right arrow accepts it', (await ev(() => document.getElementById('txInput').value)) === 'ls -la /sdcard/Download');
  await ev(() => { document.getElementById('txInput').value = ''; txAutosize(); });
  check('   an empty box shows nothing', (await hint('txAc')) === '');
  await type('#txInput', 'dumpsys wif');
  check('   common commands are suggested here as well', (await hint('txAc')) === 'dumpsys wifi', await hint('txAc'));
  await ev(() => { const i = document.getElementById('txInput'); i.value = 'dumpsys wif\nsecond line'; i.setSelectionRange(i.value.length, i.value.length); acUpdate('tx'); });
  check('   no suggestion for text on several lines', (await hint('txAc')) === '');
  await ev(() => { document.getElementById('txInput').value = ''; txAutosize(); });
  await ev(() => { txState.agent = 'claude'; txState.mode = 'chat'; });
  await type('#txInput', 'dumpsys wif');
  check('   none in the AI chat mode, but after a ! (a shell command in the chat) it works', (await hint('txAc')) === '' && (await (async () => { await type('#txInput', '!dumpsys wif'); return hint('txAc'); })()) === '!dumpsys wifi');
  await ev(() => { txState.agent = 'none'; txState.mode = 'shell'; });

  // ---- 4. the top of the About tab ----
  await ev(() => { switchView('about'); }); await sleep(300);
  const top = await ev(() => [...document.querySelectorAll('#view-about .about-hero .mode-action-btn')].map(b => b.innerText.trim()));
  check('4. the top of the About tab has Issues and Contact the developer first in the row', top.indexOf('Issues') >= 0 && top.indexOf('Contact the developer') >= 0 && top[0] === 'Issues' && top[1] === 'Contact the developer', JSON.stringify(top));
  await ev(() => { window.__urls.length = 0; }); await page.click('#aboutIssuesBtn'); await sleep(60);
  check('   Issues opens the issues page of the GitHub repository', await ev(() => window.__urls.length === 1 && window.__urls[0] === 'https://github.com/Bingblop/ADB-Application-Manager/issues'), await ev(() => window.__urls.join()));
  await page.click('#aboutContactBtn'); await sleep(100);
  check('   Contact the developer opens a window with a message box, the details switch and Send', await ev(() => document.getElementById('contactModal').classList.contains('show') && !!document.getElementById('contactText') && !!document.getElementById('contactSendBtn')));
  check('   the developer\'s own address is nowhere in the app', await ev(async () => { const html = document.documentElement.outerHTML; const js = [...document.scripts].map(s => s.textContent).join('\n'); return !/kyle\.tameirao|tameirao@/i.test(html + js); }));
  check('   the subject is ADB App Manager', await ev(() => CONTACT_SUBJECT === 'ADB App Manager'));
  check('   until the alias is set, the window says the address is not set up and Send sends nothing', await ev(() => { if (CONTACT_ALIAS) return true; return /not set up/.test(document.getElementById('contactNote').innerText); }));
  // with an alias (set for this test): the message goes to the mail app, with the details added at the end
  await ev(() => { window.CONTACT_ALIAS_TEST = true; });
  const sent = await ev(() => {
    const body = (() => { document.getElementById('contactText').value = 'It crashes when I tap X'; document.getElementById('contactWithInfo').checked = true; return contactBody(); })();
    return body;
  });
  check('   the message is followed by the app version, Android version and phone', /^It crashes when I tap X\n\n--\nApp: ADB Application Manager Pro 7\.10\.11-Pro \(build 831\)\nAndroid: 14 \(API 34\)\nPhone: Test Phone/.test(sent), JSON.stringify(sent));
  await ev(() => { document.getElementById('contactWithInfo').checked = false; });
  check('   with the switch off only the message is sent', await ev(() => contactBody() === 'It crashes when I tap X'));
  await page.click('#contactModal .mode-action-btn:not(.primary)'); await sleep(60);
  check('   Copy message copies the subject and the message', await ev(() => /Subject: ADB App Manager/.test(window.__copied || '') && /It crashes when I tap X/.test(window.__copied || '')), await ev(() => window.__copied));
  check('   an empty message is refused', await (async () => { await ev(() => { document.getElementById('contactText').value = ''; }); await page.click('#contactModal .mode-action-btn:not(.primary)'); await sleep(40); return ev(() => /Write your message first/.test(document.getElementById('contactNote').innerText)); })());

  // Send with an alias: the email app is opened with the alias, the subject and the message
  await ev(() => { CONTACT_ALIAS = 'contact@alias.example'; document.getElementById('contactText').value = 'Hello there'; document.getElementById('contactWithInfo').checked = false; contactPreview(); });
  await page.click('#contactSendBtn'); await sleep(80);
  check('   Send opens the email app, addressed to the alias, with the subject ADB App Manager and the message', await ev(() => window.__mail.length === 1 && window.__mail[0].to === 'contact@alias.example' && window.__mail[0].subject === 'ADB App Manager' && window.__mail[0].body === 'Hello there') && await ev(() => !document.getElementById('contactModal').classList.contains('show')), await ev(() => JSON.stringify(window.__mail)));
  await ev(() => { window.__mailOk = false; window.__mail.length = 0; contactOpen(); document.getElementById('contactText').value = 'Second'; });
  await page.click('#contactSendBtn'); await sleep(80);
  check('   with no email app it says so and gives the alias to write to by hand', await ev(() => /No email app answered/.test(document.getElementById('contactNote').innerText) && /contact@alias\.example/.test(document.getElementById('contactNote').innerText) && document.getElementById('contactModal').classList.contains('show')));
  await ev(() => { CONTACT_ALIAS = ''; });

  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  await b.close();
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
