// v7.14: the order of Settings after the owner's list. Palette Style and My Themes are one card ("Palette Style & My Themes"), the folded Curated Theme Palettes
// follow, then the Default Ask Agent card, which now also holds the VirusTotal API key right below the agent choice. Private DNS (only in Extra Settings) and
// Reconnecting devices (now in Connected Devices, shown even when no device dropped, with its switch, the timings and their note) are no longer in Settings.
// The key still goes through the same calls (vtKeyTyped -> kv 'vt_key', which the app seals in its Keystore vault): only its controls moved.
const { chromium, PAGE } = require('./lib/pw');
let bad = 0;
// the details (sizes, positions: they differ a little from one machine's fonts to another's) are printed only when a check fails
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok || extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  const page = await b.newPage({ viewport: { width: 390, height: 860 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(PAGE); await page.waitForTimeout(400);
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev(() => { localStorage.clear(); switchView('prefs'); }); await page.waitForTimeout(300);

  const order = await ev(() => [...document.querySelectorAll('#view-prefs > .color-card')].map(c => c.querySelector(':scope > .color-card-title').textContent.trim()));
  check('1. Settings, top to bottom', JSON.stringify(order) === JSON.stringify(['Language', 'Appearance', 'Palette Style & My Themes', 'Curated Theme Palettes', 'Default Ask Agent', 'Lists', 'Motion',
    'Row tints', 'Haptic feedback', 'Progress messages', 'Font', 'Icon pack', 'Action Button', 'Navigation', 'Feature List', 'Press and hold guide']), JSON.stringify(order));

  const ps = await ev(() => { const c = document.getElementById('paletteStyleCard'), m = document.getElementById('myThemesCard'); return { inside: c.contains(m), last: c.lastElementChild === m, head: m.querySelector('.cc-section-title').textContent, grid: c.contains(document.getElementById('psStyleGrid')) && m.contains(document.getElementById('mtGrid')) && m.contains(document.getElementById('mtSaveBtn')), seedFirst: !!(document.getElementById('psStyleGrid').compareDocumentPosition(m) & Node.DOCUMENT_POSITION_FOLLOWING) }; });
  check('2. one card holds Palette Style (the source color and the nine styles) and, under it, My Themes with its own heading, Save and the saved themes', ps.inside && ps.last && ps.head === 'My Themes' && ps.grid && ps.seedFirst, JSON.stringify(ps));

  const ag = await ev(() => {
    const c = document.getElementById('agentCard'), v = document.getElementById('vtCard'), sel = document.getElementById('askAgentSelect');
    const kids = [...c.children];
    return { inside: c.contains(v), afterChoice: kids.indexOf(v) > kids.indexOf(sel), last: c.lastElementChild === v, head: v.querySelector('.cc-section-title').textContent,
      parts: ['vtKeyInput', 'vtTestBtn', 'vtKeyStatus', 'vtQuotaLine'].every(id => v.contains(document.getElementById(id))), pw: document.getElementById('vtKeyInput').type,
      prev: c.previousElementSibling.id, foldable: c.classList.contains('cc') };
  });
  check('3. the Default Ask Agent card comes right after Curated Theme Palettes and holds the VirusTotal API key below the agent choice (its own heading, the field, Test key, the state and quota lines)',
    ag.inside && ag.afterChoice && ag.last && ag.head === 'VirusTotal API key' && ag.parts && ag.pw === 'password' && ag.prev === 'curatedCard' && !ag.foldable, JSON.stringify(ag));

  // the key goes where it went before
  await page.fill('#vtKeyInput', 'abc123'); await page.waitForTimeout(150);
  check('   typing a key still goes through vtKeyTyped to the same store (kv vt_key; vtKey reads it back)', await ev(() => vtKey() === 'abc123' && kvGet('vt_key', '') === 'abc123'), await ev(() => String(vtKey())));
  await ev(() => vtClearKey()); await page.waitForTimeout(100);

  const gone = await ev(() => ({ dns: [...document.querySelectorAll('#view-prefs > .color-card > .color-card-title, #view-prefs > .color-card > .color-card-subtitle, #view-prefs > .color-card button')].some(e => /Private DNS/.test(e.textContent || '')), pointer: !!document.getElementById('extraSettingsPointerCard'),
    rc: !!document.querySelector('#view-prefs #cdReconnectCard, #view-prefs #cdAutoSwitch, #view-prefs #cdRetryInput') }));
  check('4. Settings has no Private DNS card or button (the Feature List may still name what the Extra Settings tab holds) and no Reconnecting devices card any more', !gone.dns && !gone.pointer && !gone.rc, JSON.stringify(gone));
  check('   Private DNS is still in Extra Settings', await ev(() => document.getElementById('view-extrasettings').contains(document.getElementById('privateDnsCard'))));

  // Connected Devices, no device ever connected and none dropped
  await ev(() => { kvSet('cd_autore', false); kvSet('cd_retry', [0, 5, 15]); switchView('devices'); const c = document.getElementById('cdReconnectCard'); if (c.classList.contains('cc-closed')) ccToggle(c); }); await page.waitForTimeout(500);
  const cdc = await ev(() => {
    const c = document.getElementById('cdReconnectCard');
    return { inTab: document.getElementById('view-devices').contains(c), afterTop: document.getElementById('cdTop').nextElementSibling === c, shown: c.getBoundingClientRect().height > 0, lost: document.getElementById('cdLost').children.length,
      title: c.querySelector('.color-card-title').textContent, sw: document.getElementById('cdAutoSwitch').checked, box: document.getElementById('cdRetryInput').value, dis: document.getElementById('cdRetryInput').disabled,
      note: document.getElementById('cdRetryNote').textContent, reset: getComputedStyle(document.getElementById('cdRetryReset')).display !== 'none', sub: c.querySelector('.color-card-subtitle').textContent };
  });
  check('5. Connected Devices has the Reconnecting devices card right under its top card, shown with no device dropped, with the saved choices (off, 0, 5, 15) and its note',
    cdc.inTab && cdc.afterTop && cdc.shown && cdc.lost === 0 && cdc.title === 'Reconnecting devices' && !cdc.sw && cdc.box === '0, 5, 15' && cdc.dis && /Turned off/.test(cdc.note) && cdc.reset && /^What this tab does/.test(cdc.sub), JSON.stringify(cdc));
  await page.click('#cdAutoSwitch + .switch-track'); await page.waitForTimeout(150);
  check('   switching it on there is the same setting (kv cd_autore) and the note shows the timings', await ev(() => kvGet('cd_autore', false) === true && /5 s, 15 s/.test(document.getElementById('cdRetryNote').textContent) && !document.getElementById('cdRetryInput').disabled));
  check('   the dropped-devices list no longer carries its own on/off link (the card is the one place)', await ev(() => !document.getElementById('cdAutoBtn')));
  await page.screenshot({ path: 'devices.png', fullPage: true });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) bad++;
  await b.close();
  if (bad) { console.log(bad + ' FAILED'); process.exit(1); }
})();
