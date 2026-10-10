// v6.0.1: the Settings tab is now "Hidden Settings". The name, the card and the menu say so (v7.0: the tab bar shows it on two lines, after Command-Line Interface),
// and what an earlier version saved (the last table, filter and sort; the log of changes) is still used.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock.js');
const ovl = require('./lib/ovl_mock.js');
const URL = PAGE;
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const journal = [1, 2].map(i => ({ n: i, t: Date.now() - i * 1000, ns: 'secure', k: 'saved_key_' + i, f: '0', v: '1' }));
  await page.addInitScript(kv => { window.__kvInit = JSON.parse(kv); }, JSON.stringify({ sdb_ui: JSON.stringify({ ns: 'secure', filter: 'switch', sort: 'za' }), sdb_journal: JSON.stringify(journal) }));
  await page.addInitScript(sdb.initScript); await page.addInitScript(ovl.initScript);
  await page.goto(URL); await page.waitForTimeout(450);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const tabs = await ev(() => Array.from(document.querySelectorAll('.tab-btn')).map(b => b.innerText.replace(/\s+/g, ' ').trim()));
  check('1. the tab bar names it "Hidden Settings"', tabs.includes('Hidden Settings') && !tabs.some(t => /^Settings$/.test(t)), JSON.stringify(tabs));
  const i = tabs.indexOf('Hidden Settings');
  check('   it sits between Command-Line Interface and RRO/Monet Customization', /Command-Line Interface/.test(tabs[i - 1]) && /RRO\/Monet/.test(tabs[i + 1]) && /Third Party/.test(tabs[i + 2]), JSON.stringify(tabs.slice(i - 1, i + 3)));
  await ev(() => { window.__mode.priv = true; checkAllWorkingModes(false); document.querySelectorAll('.tab-btn')[Array.from(document.querySelectorAll('.tab-btn')).findIndex(b => /Hidden\s+Settings/.test(b.innerText))].click(); }); await page.waitForTimeout(800);
  check('2. the button opens it and becomes the active one', (await ev(() => document.querySelector('.tab-btn.active').innerText.replace(/\s+/g, ' ').trim())) === 'Hidden Settings' && (await ev(() => currentViewName())) === 'settings');
  check('3. its card says what it is', (await ev(() => document.querySelector('#sdbTop .color-card-title').innerText.trim())) === 'Android’s hidden settings');
  await ev(() => sdbOpenMenu()); await page.waitForTimeout(200);
  check('4. the ⋯ menu is "Hidden settings tools"', (await ev(() => document.querySelector('#sdbMenuModal .sheet-title h3').innerText.trim())) === 'Hidden settings tools');
  await ev(() => sdbMenuClose());
  check('5. what an earlier version saved is still used (table, filter, sort)', await ev(() => sdbNs === 'secure' && sdbFilter === 'switch' && sdbSort === 'za'), await ev(() => [sdbNs, sdbFilter, sdbSort].join()));
  check('   and the log of changes (2 entries)', (await ev(() => sdbJournal.length)) === 2 && (await ev(() => sdbJournal[0].k)) === 'saved_key_1');
  // no label anywhere on the page still calls it plain "Settings"
  const stray = await ev(() => Array.from(document.querySelectorAll('#view-settings, #sdbMenuModal, #sdbEditModal, #sdbNewModal, #sdbJournalModal')).map(n => n.innerText).join('\n').split('\n').filter(l => /(^|\s)Settings(\s|$)/.test(l) && !/Hidden/i.test(l)));
  check('6. no heading or label of the tab calls it just "Settings"', stray.length === 0, JSON.stringify(stray));
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? 'FAILURES: ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
