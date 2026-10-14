// Apps tab rows: the app's icon is its selection box (a check badge on the icon's corner, role="checkbox" with aria-checked and a name in the
// chosen language), so the separate checkbox column is gone, and the row's Action Button (the gear by default) and ⋯ are bare icons on 44px
// squares with no gap between them. Measured at 320, 360 and 412 px: the controls after the name take less than 100px of the row (they took
// 127), the name and the package get that room, "Amazon Shopping" stays on one line at 320 px, no horizontal page scroll. A tap on the icon
// selects and a second tap deselects, the selection count on the batch button follows, Select all and Clear mark every row; Space on the
// focused icon toggles it; a hold on the icon saves the icon and does not select. The gear still runs its action on a tap and opens its sheet
// on a hold, the ⋯ still opens the app's menu, and the icon, the gear and the ⋯ each answer a tap over at least 44x44px.
const { chromium, PAGE } = require('./lib/pw');
const appsMock = require('./lib/apps_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined || ok ? '' : ': ' + extra)); }
const esc = p => '#card_' + p.replace(/\./g, '\\.');

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (width, lang, dark) => {
    const page = await b.newPage({ viewport: { width, height: 900 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(width + ' ' + e.message));
    await page.addInitScript(appsMock.initScript, { dark: dark !== false, lang });
    await page.addInitScript(() => {
      window.__calls = [];
      const br = window.AndroidBridge;
      br.executeAppAction = (a, p) => { window.__calls.push(a + ' ' + p); return 'Success'; };
      br.saveAppIcon = (p) => { window.__calls.push('saveAppIcon ' + p); return 'started'; };
    });
    await page.goto(PAGE); await page.waitForTimeout(700);
    if (lang && lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await page.waitForTimeout(800); }
    await page.evaluate(() => switchView('apps')); await page.waitForTimeout(300);
    return page;
  };
  // the row's parts and the 44px hit boxes of its controls
  const geo = (page, pkg) => page.evaluate(sel => {
    const c = document.querySelector(sel);
    c.scrollIntoView({ block: 'center' });
    const r = e => e ? e.getBoundingClientRect() : null;
    const meta = r(c.querySelector('.app-meta')), card = r(c), name = c.querySelector('.app-name');
    const lh = parseFloat(getComputedStyle(name).lineHeight);
    const ctl = { icon: c.querySelector('.app-sel'), gear: c.querySelector('.app-actions .ab-btn'), more: c.querySelector('.app-actions .row-menu') };
    const hits = {};
    for (const k in ctl) {
      const el = ctl[k]; if (!el) { hits[k] = 'missing'; continue; }
      const rc = r(el), cx = rc.left + rc.width / 2, cy = rc.top + rc.height / 2, h = 21;
      const miss = [[0, 0], [-h, 0], [h, 0], [0, -h], [0, h]].filter(([dx, dy]) => { const t = document.elementFromPoint(cx + dx, cy + dy); return !t || !(t === el || el.contains(t)); });
      hits[k] = miss.length ? 'misses at ' + miss.map(p => p.join(',')).join(' ') : 'ok';
    }
    const box = c.querySelector('.app-checkbox');
    return {
      trailing: Math.round(card.right - meta.right), meta: Math.round(meta.width), card: Math.round(card.width),
      nameLines: Math.round(name.getBoundingClientRect().height / lh),
      checkboxOnIcon: !!(box && ctl.icon && ctl.icon.contains(box)),
      buttons: [ctl.gear, ctl.more].map(e => e ? Math.round(r(e).width) + 'x' + Math.round(r(e).height) : '-').join(' '),
      inCard: [ctl.gear, ctl.more].every(e => e && r(e).right <= card.right + 0.5),
      hits, docW: document.documentElement.scrollWidth
    };
  }, esc(pkg));
  const state = (page, pkg) => page.evaluate(sel => {
    const c = document.querySelector(sel), s = c.querySelector('.app-sel'), box = c.querySelector('.app-checkbox');
    const fab = document.getElementById('batchFab');
    return { selected: c.classList.contains('selected'), aria: s ? s.getAttribute('aria-checked') : null, badge: !!box && getComputedStyle(box).visibility === 'visible' && box.innerText.trim() === '✓',
      count: fab.classList.contains('show') ? document.getElementById('batchFabBadge').innerText : '0', n: selectedPkgs.size };
  }, esc(pkg));
  const centre = async (page, sel) => { const bb = await page.locator(sel).boundingBox(); return [bb.x + bb.width / 2, bb.y + bb.height / 2]; };

  // ---- layout at three widths, dark and light
  for (const [width, dark] of [[320, true], [360, false], [412, true]]) {
    const page = await open(width, 'en', dark);
    const t = width + ' px ' + (dark ? 'dark' : 'light');
    const g = await geo(page, 'com.amazon.mShop.android.shopping');
    check(t + ': the gear and ⋯ after the name take less than 100px of the row (now ' + g.trailing + 'px)', g.trailing < 100 && g.inCard, JSON.stringify(g));
    check(t + ': the name and package get the room (' + g.meta + 'px of a ' + g.card + 'px row)', g.meta >= g.card - 160, JSON.stringify(g));
    check(t + ': no checkbox column: the ✓ box sits on the icon', g.checkboxOnIcon, JSON.stringify(g));
    check(t + ': the gear and ⋯ are 44x44 buttons', g.buttons === '44x44 44x44', g.buttons);
    check(t + ': the icon, the gear and the ⋯ each answer a tap over 44x44px', g.hits.icon === 'ok' && g.hits.gear === 'ok' && g.hits.more === 'ok', JSON.stringify(g.hits));
    check(t + ': no horizontal page scroll', g.docW <= width, 'docW=' + g.docW);
    if (width === 320) check(t + ': "Amazon Shopping" stays on one line', g.nameLines === 1, JSON.stringify(g));
    await page.close();
  }

  // ---- selection by the icon, ARIA, the batch count, Select all, keyboard, hold
  {
    const page = await open(360, 'en');
    const A = 'com.amazon.mShop.android.shopping', W = 'com.whatsapp';
    const s0 = await state(page, A);
    const attrs = await page.evaluate(sel => { const s = document.querySelector(sel + ' .app-sel'); return s ? { tag: s.tagName, role: s.getAttribute('role'), label: s.getAttribute('aria-label'), aria: s.getAttribute('aria-checked') } : null; }, esc(A));
    check('the icon is a button with role="checkbox", aria-checked="false" and the name "Select Amazon Shopping"', !!attrs && attrs.tag === 'BUTTON' && attrs.role === 'checkbox' && attrs.aria === 'false' && attrs.label === 'Select Amazon Shopping', JSON.stringify(attrs));
    check('   unselected: no badge, count 0', !s0.selected && !s0.badge && s0.count === '0', JSON.stringify(s0));
    let [x, y] = await centre(page, esc(A) + ' img.app-icon');
    await page.mouse.click(x, y); await page.waitForTimeout(200);
    const s1 = await state(page, A);
    check('a tap on the icon selects the app: tinted row, ✓ badge, aria-checked="true", batch count 1', s1.selected && s1.badge && s1.aria === 'true' && s1.count === '1', JSON.stringify(s1));
    [x, y] = await centre(page, esc(W) + ' img.app-icon');
    await page.mouse.click(x, y); await page.waitForTimeout(200);
    check('   a second app by its icon: count 2', (await state(page, W)).count === '2', JSON.stringify(await state(page, W)));
    [x, y] = await centre(page, esc(A) + ' img.app-icon');
    await page.mouse.click(x, y); await page.waitForTimeout(200);
    const s2 = await state(page, A);
    check('   a tap on a selected icon deselects it: no badge, aria-checked="false", count back to 1', !s2.selected && !s2.badge && s2.aria === 'false' && s2.count === '1', JSON.stringify(s2));
    // the name still selects, as before
    await page.click(esc(A) + ' .app-name'); await page.waitForTimeout(200);
    const s3 = await state(page, A);
    check('   a tap on the name still selects, and the icon says so', s3.selected && s3.aria === 'true' && s3.badge && s3.count === '2', JSON.stringify(s3));
    // Select all and Clear
    const all = await page.evaluate(() => { selectAllVisible(true); const s = [...document.querySelectorAll('#appsListContainer .app-sel')]; return { rows: s.length, on: s.filter(e => e.getAttribute('aria-checked') === 'true').length, badges: [...document.querySelectorAll('#appsListContainer .app-checkbox')].filter(e => getComputedStyle(e).visibility === 'visible').length, count: document.getElementById('batchFabBadge').innerText }; });
    check('Select all checks every icon, shows every badge and counts them', all.rows > 3 && all.on === all.rows && all.badges === all.rows && all.count === String(all.rows), JSON.stringify(all));
    const none = await page.evaluate(() => { clearBatchSelection(); const s = [...document.querySelectorAll('#appsListContainer .app-sel')]; return { on: s.filter(e => e.getAttribute('aria-checked') === 'true').length, badges: [...document.querySelectorAll('#appsListContainer .app-checkbox')].filter(e => getComputedStyle(e).visibility === 'visible').length, fab: document.getElementById('batchFab').classList.contains('show') }; });
    check('   Clear unchecks them all and hides the batch button', none.on === 0 && none.badges === 0 && !none.fab, JSON.stringify(none));
    // keyboard
    await page.evaluate(sel => { const s = document.querySelector(sel + ' .app-sel'); if (s) s.focus(); }, esc(W)); await page.keyboard.press('Space'); await page.waitForTimeout(150);
    const k1 = await state(page, W);
    await page.keyboard.press('Space'); await page.waitForTimeout(150);
    const k2 = await state(page, W);
    check('Space on the focused icon checks and unchecks it', k1.aria === 'true' && k1.selected && k2.aria === 'false' && !k2.selected, JSON.stringify([k1, k2]));
    // a hold on the icon saves the icon, it does not select
    [x, y] = await centre(page, esc(A) + ' img.app-icon');
    await page.mouse.move(x, y); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up(); await page.waitForTimeout(200);
    const h = await state(page, A);
    check('a hold on the icon saves the icon and does not select the row', !h.selected && h.aria === 'false' && (await page.evaluate(() => window.__calls.includes('saveAppIcon com.amazon.mShop.android.shopping'))), JSON.stringify(h));
    // the gear and the ⋯ keep their actions
    await page.evaluate(() => { window.__calls = []; });
    await page.click(esc(A) + ' .ab-btn'); await page.waitForTimeout(200);
    check('the gear still opens the app\'s Android settings', (await page.evaluate(() => window.__calls.join())) === 'app_settings com.amazon.mShop.android.shopping' && !(await state(page, A)).selected, await page.evaluate(() => window.__calls.join()));
    [x, y] = await centre(page, esc(A) + ' .ab-btn');
    await page.mouse.move(x, y); await page.mouse.down(); await page.waitForTimeout(800); await page.mouse.up(); await page.waitForTimeout(250);
    check('   a hold on the gear still opens the sheet of every action', await page.evaluate(() => document.getElementById('abSheet').classList.contains('show') && document.getElementById('abSheetTitle').innerText === 'Amazon Shopping'));
    await page.evaluate(() => closeAbSheet()); await page.waitForTimeout(250);
    await page.click(esc(A) + ' .row-menu'); await page.waitForTimeout(400);
    check('the ⋯ still opens the app\'s menu, and does not select the row', await page.evaluate(() => document.getElementById('inspectorModal').classList.contains('show') && inspectedPkg === 'com.amazon.mShop.android.shopping') && !(await state(page, A)).selected);
    await page.evaluate(() => closeInspector()); await page.waitForTimeout(250);
    await page.close();
  }

  // ---- German at 320 px: the checkbox's name is translated, the app name in it is not, the row still fits
  {
    const page = await open(320, 'de', false);
    const lbl = await page.evaluate(() => { const s = document.querySelector('#card_com\\.whatsapp .app-sel'); return s ? s.getAttribute('aria-label') : null; });
    check('German: the icon checkbox is called "WhatsApp auswählen"', lbl === 'WhatsApp auswählen', JSON.stringify(lbl));
    const g = await geo(page, 'com.samsung.android.bixby.agent');
    check('German 320 px: the gear and ⋯ take less than 100px, 44px hit boxes, no horizontal page scroll', g.trailing < 100 && g.hits.icon === 'ok' && g.hits.gear === 'ok' && g.hits.more === 'ok' && g.docW <= 320, JSON.stringify(g));
    await page.close();
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (bad || errors.length) { console.log((bad || errors.length) + ' FAILED'); process.exit(1); }
})();
