// v7.17.0 Custom Fonts tab: the tab is registered (top bar, the bottom bar's More sheet with its "Fonts" label and icon) and opens with its six cards in
// order: the intro, Make a font, Emoji fonts, My fonts, Font changer, System emoji; in the dark and the light theme, at 412 and 360 px wide, with no
// horizontal scroll. With nothing picked the Make card has a Regular row (Choose), Bold = None with the line that Android makes bold from the Regular,
// Emoji = None plus the downloaded mergeable emoji, the name field and a disabled Build. The emoji list has five rows, My fonts two rows with their
// package names, and under Root both privileged cards are unlocked (ROOT tags). A language change draws the cards again (German, then English).
// Mock bridge: lib/fontmaker_mock.js.
const fs = require('fs');
const { chromium, PAGE, fixture } = require('./lib/pw');
const fmk = require('./lib/fontmaker_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok ? '' : ': ' + (extra === undefined ? ok : extra))); }
const b64 = f => fs.readFileSync(fixture(f)).toString('base64');
const FONTS = { vf: b64('Fixture-Boxes-VF.ttf'), regular: b64('Fixture-Boxes-Regular.ttf'), bold: b64('Fixture-Boxes-Bold.ttf') };

async function open(b, opts, width) {
  const ctx = await b.newContext({ viewport: { width, height: 900 } });
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(fmk.initScript, Object.assign({ b64: FONTS }, opts));
  await page.goto(PAGE);
  await page.waitForTimeout(500);
  await page.evaluate(() => switchView('fonts'));
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}
const shape = (page) => page.evaluate(() => {
  const v = document.getElementById('view-fonts');
  const cards = [...v.querySelectorAll(':scope > .color-card')].map(c => c.id);
  const t = id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; };
  return {
    active: v.classList.contains('active'), cards,
    titles: [...v.querySelectorAll(':scope > .color-card .color-card-title')].map(e => e.textContent.trim()),
    regRow: t('fntRegRow'), boldSel: document.getElementById('fntBoldSel').value, boldOpts: [...document.getElementById('fntBoldSel').options].map(o => o.value),
    boldNone: getComputedStyle(document.getElementById('fntBoldNone')).display !== 'none' ? t('fntBoldNone') : '',
    emojiOpts: [...document.getElementById('fntEmojiSel').options].map(o => o.value), build: document.getElementById('fntBuildBtn').disabled,
    buildPrimary: document.getElementById('fntBuildBtn').classList.contains('primary'),
    primaries: v.querySelectorAll('.mode-action-btn.primary').length,
    emoRows: [...document.querySelectorAll('#fntEmojiList .fnt-emo')].map(e => e.id),
    libRows: [...document.querySelectorAll('#fntLibList .fnt-lib')].map(r => r.querySelector('.fnt-lib-meta').textContent),
    changerTag: t('fntChangerTag'), sysTag: t('fntSysTag'), changerNow: t('fntChangerBody'),
    scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth,
    wide: [...v.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.width && r.right > document.documentElement.clientWidth + 0.5; }).map(e => e.id || e.className).slice(0, 5),
    bg: getComputedStyle(document.body).backgroundColor,
  };
});

// a picture of the whole tab: the batch sheet and the toast are fixed to the screen, so a full-page picture would paint them over the cards
async function shot(page, file) {
  const st = await page.addStyleTag({ content: '#floatingBatchBar, #toast { display: none !important; }' });
  await page.screenshot({ path: file, fullPage: true });
  await st.evaluate(e => e.remove());
}

(async () => {
  const b = await chromium.launch();
  // the registration: a tab of the top bar, a short label and an icon for the bottom bar
  {
    const { ctx, page, errors } = await open(b, { mode: 'root' }, 412);
    const reg = await page.evaluate(() => ({ def: TAB_DEFS.find(t => t.key === 'fonts'), shortL: BNAV_SHORT.fonts, icon: !!BNAV_ICONS.fonts,
      top: [...document.querySelectorAll('#tabBar .tab-btn')].map(x => x.dataset.tab), before: TAB_DEFS.map(t => t.key).slice(-3) }));
    check('1. TAB_DEFS has fonts, label "Custom\\nFonts", name "Custom Fonts"', reg.def && reg.def.label === 'Custom\nFonts' && reg.def.name === 'Custom Fonts', JSON.stringify(reg.def));
    check('   it sits after Root Features, before About', reg.before.join(',') === 'rootfeatures,fonts,about', reg.before.join(','));
    check('   the bottom bar names it "Fonts" and has an icon for it', reg.shortL === 'Fonts' && reg.icon, JSON.stringify(reg));
    check('   the top bar shows it', reg.top.includes('fonts'), reg.top.join(','));
    const s = await shape(page);
    check('2. the tab opens (switchView)', s.active);
    check('   six cards in order', s.cards.join(',') === 'fntTop,fntMakeCard,fntEmojiCard,fntLibCard,fntChangerCard,fntSysCard', s.cards.join(','));
    check('   their titles', s.titles.join(' | ') === 'Custom Fonts | Make a font | Emoji fonts | My fonts | Font changer | System emoji', s.titles.join(' | '));
    check('3. Regular: a row with Choose', /^Regular/.test(s.regRow) && /Choose$/.test(s.regRow), s.regRow);
    check('   Bold: None, with the line that Android makes bold from the Regular', s.boldSel === 'none' && /Android makes bold text from the Regular/.test(s.boldNone), JSON.stringify([s.boldSel, s.boldNone]));
    check('   no variable font picked: no "same variable font" choice', s.boldOpts.join(',') === 'none,file', s.boldOpts.join(','));
    check('   Emoji: None, the downloaded mergeable Twemoji, own file', s.emojiOpts.join(',') === 'none,cat:twemoji,file', s.emojiOpts.join(','));
    check('   Build is the one primary button and starts disabled', s.build && s.buildPrimary && s.primaries === 1, JSON.stringify([s.build, s.primaries]));
    check('4. five emoji rows', s.emoRows.join(',') === 'fntEmo_noto,fntEmo_twemoji,fntEmo_blobmoji,fntEmo_openmoji,fntEmo_apple', s.emoRows.join(','));
    check('   two fonts in My fonts, with their packages', s.libRows.join(',') === 'com.monotype.android.font.InterDisplay_3fa9c1,com.monotype.android.font.Lato_8b21d0', s.libRows.join(','));
    check('5. Root: the font changer is unlocked (ROOT) and shows the font in use', s.changerTag === 'ROOT' && /Phone font\s*Inter Display/.test(s.changerNow), JSON.stringify([s.changerTag, s.changerNow]));
    check('   the system emoji card is unlocked (ROOT)', s.sysTag === 'ROOT', s.sysTag);
    check('6. dark, 412 px: no horizontal scroll', s.scrollW <= s.clientW && !s.wide.length, JSON.stringify([s.scrollW, s.clientW, s.wide]));
    await shot(page, 'dark-412.png');
    // the bottom bar: the More sheet lists the tab with its short label
    page.bottomNav = true;
    await page.evaluate(() => { navMode = 'bottom'; navApply(); });
    await page.waitForTimeout(200);
    const more = await page.evaluate(() => { bnavMoreOpen(); const t = document.querySelector('#bnavMoreGrid .bn-tile[data-tab="fonts"]'); const r = t ? { label: t.innerText.replace(/\s+/g, ' ').trim(), icon: !!t.querySelector('svg') } : null; bnavMoreClose(); return r; });
    check('   with the bottom bar, More has a Custom Fonts tile with its icon', more && more.label === 'Custom Fonts' && more.icon, JSON.stringify(more));
    // another language: the cards are drawn again, so the texts inside the data rows (translate="no") change too
    const lang = async code => { await page.evaluate(c => I18N.set(c), code); await page.waitForTimeout(900);
      return page.evaluate(() => ({ title: document.querySelector('#fntMakeCard .color-card-title').textContent.trim(), meta: document.querySelectorAll('#fntLibList .fnt-lib-meta')[1].textContent })); };
    const de = await lang('de'), en = await lang('en');
    check('8. German: the card titles and the rows of My fonts are in German ("In Verwendung", "Normal und Fett"); English again after', de.title === 'Schriftart erstellen' && /^In Verwendung · .*Normal und Fett/.test(de.meta)
      && en.title === 'Make a font' && /^In use · .*Regular and Bold/.test(en.meta), JSON.stringify([de, en]));
    check('   no page errors (dark)', !errors.length, errors.join(' | '));
    await ctx.close();
  }
  // the light theme, the narrowest phone width
  for (const [dark, width] of [[false, 412], [false, 360], [true, 360]]) {
    const { ctx, page, errors } = await open(b, { mode: 'root', dark }, width);
    const s = await shape(page);
    const name = (dark ? 'dark' : 'light') + '-' + width;
    check('7. ' + name + ': six cards, no horizontal scroll', s.cards.length === 6 && s.scrollW <= s.clientW && !s.wide.length, JSON.stringify([s.cards.length, s.scrollW, s.clientW, s.wide]));
    if (!dark) check('   the light theme is in use (a light page)', (() => { const m = /(\d+), (\d+), (\d+)/.exec(s.bg); return m && (+m[1] + +m[2] + +m[3]) / 3 > 160; })(), s.bg);
    check('   no page errors', !errors.length, errors.join(' | '));
    await shot(page, name + '.png');
    await ctx.close();
  }
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
