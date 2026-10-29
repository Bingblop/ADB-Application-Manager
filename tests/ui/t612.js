// v7.19.5: Smart Tabs as the phone runs it (the owner, 2026-10-29: "Bug check the smart tabs. Doesn't seem to be working."). v7.19.4 only reordered the
// tabs inside the bottom bar's pins, so a tab opened most that was not pinned went first under More and the bar (the only bar in Bottom bar only, the
// default) looked the same; t611 ran in Top bar only. Here, in Bottom bar only on a phone-sized screen with a real key-value store behind the bridge
// (lib/bnav_mock.js, kept across a reload): tabs opened from More and left with Back are counted in the store, turning Smart Tabs on puts the tabs opened
// most on the bar (pinned or not, a pinned tab first on a tie) with the rest under More and About last, the Feature List's pins rest and show the bar,
// the order does not change while tabs are opened but does when the app comes back (onAppResume from the app's onResume, with no visibilitychange: a web
// view does not always send one), a reload (a cold start) keeps it all, turning it off gives the pins back, and turned on with nothing counted the bar
// stays as it was. Both bars show the same order.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/bnav_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async kv => {
    const page = await b.newPage({ viewport: { width: 412, height: 900 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(mock.initScript, { kv: kv || {} });
    await page.goto(PAGE); await page.waitForTimeout(700);
    return page;
  };
  let page = await open({ nav_mode: '"bottom"' });
  const ev = (f, a) => page.evaluate(f, a);
  const sleep = ms => page.waitForTimeout(ms);
  const bar = () => ev(() => [...document.querySelectorAll('#bnav .bn-item[data-tab]')].map(x => x.dataset.tab).join(','));
  const more = () => ev(() => bnavMoreKeys().join(','));
  const fromMore = async (key, times) => {                     // More, the tile, then Back to Application Manager, as a thumb does it
    for (let i = 0; i < times; i++) {
      await ev(() => bnavMoreOpen()); await sleep(120);
      await ev(k => document.querySelector('#bnavMoreGrid .bn-tile[data-tab="' + k + '"]').click(), key); await sleep(120);
      await ev(() => backNavigate()); await sleep(80);
    }
  };
  const store = () => ev(() => window.__kv.tab_opens || '');

  const pins = await bar();
  check('1. Bottom bar only: Application Manager and the three pinned tabs', await ev(() => navMode) === 'bottom' && pins === 'apps,installer,console,extrasettings', pins);
  await fromMore('fonts', 5);
  await fromMore('devices', 2);
  check('   tabs opened from More and left with Back are counted in the app\'s settings store', await store() === '{"fonts":5,"devices":2}', await store());
  check('   with Smart Tabs off the bar keeps its pins', await bar() === pins, await bar());

  await ev(() => switchView('prefs')); await sleep(150);
  await ev(() => document.getElementById('smartTabsToggle').click()); await sleep(200);
  check('2. turned on: the tabs opened most are on the bottom bar right after Application Manager, a pinned tab fills the last slot', await bar() === 'apps,fonts,devices,installer', await bar());
  const m = (await more()).split(',');
  check('   the other pinned tabs wait first under More, About last', m[0] === 'console' && m[1] === 'extrasettings' && m[m.length - 1] === 'about' && !m.includes('fonts'), m.join(','));
  check('   the choice is saved in the store', await ev(() => window.__kv.smart_tabs) === 'true', await ev(() => window.__kv.smart_tabs));
  const fl = await ev(() => [...document.querySelectorAll('#featureList .fl-row')].map(r => r.dataset.tab + (r.querySelector('.fl-pin').getAttribute('aria-pressed') === 'true' ? '*' : '') + (r.querySelector('.fl-pin').disabled ? '' : '!')).slice(0, 5).join(','));
  check('   the Feature List lists that order, its pins rest and light the tabs on the bar', fl === 'fonts*,devices*,installer*,console,extrasettings', fl);
  await ev(() => featurePin('console')); await sleep(80);
  check('   a pin cannot change the bar while it is on', await bar() === 'apps,fonts,devices,installer' && await ev(() => tabConfig.pinned.join()) === 'apps,installer,extrasettings,console', await bar());
  await ev(() => switchView('fonts')); await sleep(150);
  check('   the tab opened is lit on the bar itself, not under More', await ev(() => { const a = document.querySelector('#bnav .bn-item.active'); return a && a.dataset.tab; }) === 'fonts');
  await ev(() => switchView('apps')); await sleep(100);

  await fromMore('logcat', 8);
  check('3. no tab moves while tabs are being opened', await bar() === 'apps,fonts,devices,installer', await bar());
  await ev(() => onAppResume()); await sleep(150);
  check('   back in the app (onResume, no visibilitychange): Live Logcat is first on the bar', await bar() === 'apps,logcat,fonts,devices', await bar());

  await page.reload(); await sleep(700);
  check('4. after a restart Smart Tabs is still on, with the same bar', await ev(() => smartTabsOn && document.getElementById('smartTabsToggle').checked) && await bar() === 'apps,logcat,fonts,devices', await bar());
  await ev(() => { navMode = 'both'; navApply(); }); await sleep(150);
  const top = await ev(() => [...document.querySelectorAll('#tabBar .tab-btn')].map(t => t.dataset.tab));
  check('   the top bar has the same order: Application Manager first, About last', top.slice(0, 5).join(',') === 'apps,logcat,fonts,devices,installer' && top[top.length - 1] === 'about', top.join(','));
  await ev(() => { navMode = 'bottom'; navApply(); }); await sleep(100);

  await ev(() => switchView('prefs')); await sleep(150);
  await ev(() => document.getElementById('smartTabsToggle').click()); await sleep(200);
  check('5. turned off: the pinned tabs are the bar again and the pins work', await bar() === pins && await ev(() => !document.querySelector('#featureList .fl-row[data-tab="console"] .fl-pin').disabled), await bar());
  await page.close();

  page = await open({ nav_mode: '"bottom"' });
  await ev(() => switchView('prefs')); await sleep(150);
  await ev(() => document.getElementById('smartTabsToggle').click()); await sleep(200);
  check('6. turned on with nothing counted yet: the bottom bar stays as it was', await bar() === pins, await bar());

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  if (bad) { console.log(bad + ' failed'); process.exit(1); }
})();
