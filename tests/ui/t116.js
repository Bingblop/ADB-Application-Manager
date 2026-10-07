// Settings > Granular Color Pickers: tapping a color opens a popup (a grid of colors, or Custom with hue / saturation / brightness) with a hex box that has the # written in already and takes capitals
// only (00DFFF); the same box is in both views, they follow each other and the sliders, Apply needs six characters, Cancel changes nothing.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  await page.addInitScript(() => {
    window.__saved = [];
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences(j) { window.__saved.push(j); }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (fn, a) => page.evaluate(fn, a);
  const wait = ms => page.waitForTimeout(ms);
  await ev(() => switchView('prefs')); await wait(300);

  const rows = await ev(() => Array.from(document.querySelectorAll('.picker-bubble-btn')).map(b => b.tagName + ':' + b.querySelector('.bubble-hex').innerText));
  check('seven pickers, buttons (no native color input any more)', rows.length === 7 && rows.every(r => r.startsWith('BUTTON:#')) && (await ev(() => document.querySelectorAll('input[type="color"]').length)) === 0, JSON.stringify(rows));

  await page.click('#hexAccent'); await wait(250);
  check('tapping one opens the popup for that color, naming it', await ev(() => document.getElementById('colorPickModal').classList.contains('show') && document.getElementById('cpTitle').innerText === 'Primary System Accent'));
  const orig = await ev(() => document.getElementById('hexAccent').innerText);
  check('the hex box shows the current color without the #, and the # is written before it', await ev(o => document.getElementById('cpHex1').value === o.slice(1) && document.querySelector('#cpHexRow1 .cp-hash').innerText === '#', orig), orig);
  await page.screenshot({ path: 'picker_colors.png' });

  // typing: capitals only, hex digits only, six at most
  await page.fill('#cpHex1', ''); await page.type('#cpHex1', '00dfff'); await wait(100);
  check('typing 00dfff gives 00DFFF (capitals)', await ev(() => document.getElementById('cpHex1').value === '00DFFF'));
  check('the box itself is styled capital-only too', await ev(() => getComputedStyle(document.getElementById('cpHex1')).textTransform === 'uppercase'));
  check('the preview follows', await ev(() => document.getElementById('cpPreviewHex').innerText === '#00DFFF' && getComputedStyle(document.getElementById('cpPreview')).backgroundColor === 'rgb(0, 223, 255)'));
  await page.fill('#cpHex1', ''); await page.type('#cpHex1', 'zz1g2h3k4m5n6p7q'); await wait(100);
  check('letters that are not hex digits are dropped, six characters at most', await ev(() => document.getElementById('cpHex1').value === '123456'), await ev(() => document.getElementById('cpHex1').value));
  await page.fill('#cpHex1', '#ff0080'); await wait(100);
  check('a pasted #ff0080 becomes FF0080', await ev(() => document.getElementById('cpHex1').value === 'FF0080'));
  await page.fill('#cpHex1', 'ABC'); await wait(100);
  check('three characters: "3 of 6" and Apply is off', await ev(() => document.getElementById('cpHexNote1').innerText === '3 of 6' && document.getElementById('cpApply').disabled && document.getElementById('cpHexRow1').classList.contains('bad')));
  await page.screenshot({ path: 'picker_bad.png' });
  await page.fill('#cpHex1', ''); await wait(100);
  check('an empty box is not an error, Apply is still off', await ev(() => !document.getElementById('cpHexRow1').classList.contains('bad') && document.getElementById('cpApply').disabled));

  // a color of the grid
  await page.click('#cpGrid .cp-sw:nth-child(6)'); await wait(100);
  check('a color of the grid fills the box', await ev(() => document.getElementById('cpHex1').value === '2196F3' && document.querySelector('#cpGrid .cp-sw.on') !== null));

  // Custom: sliders and the same box
  await page.click('#cpTabCustom'); await wait(100);
  check('Custom has the hex box too, showing the same color', await ev(() => document.getElementById('cpPaneCustom').style.display !== 'none' && document.getElementById('cpHex2').value === '2196F3' && document.querySelector('#cpHexRow2 .cp-hash').innerText === '#'));
  check('and the sliders sit where that color is (hue 207, saturation 86, brightness 95)', await ev(() => [cpHue.value, cpSat.value, cpVal.value].join()) === '207,86,95', await ev(() => [cpHue.value, cpSat.value, cpVal.value].join()));
  await page.fill('#cpHex2', ''); await page.type('#cpHex2', 'ff0000'); await wait(100);
  check('typing in the Custom box moves the sliders (red: 0, 100, 100)', await ev(() => [cpHue.value, cpSat.value, cpVal.value].join()) === '0,100,100');
  await ev(() => { const h = document.getElementById('cpHue'); h.value = 120; h.dispatchEvent(new Event('input')); });
  check('moving the hue slider rewrites the box (green: 00FF00) and the other box', await ev(() => document.getElementById('cpHex2').value === '00FF00' && document.getElementById('cpHex1').value === '00FF00'), await ev(() => document.getElementById('cpHex2').value));
  await ev(() => { const v = document.getElementById('cpVal'); v.value = 50; v.dispatchEvent(new Event('input')); });
  check('brightness 50 darkens it (007F00)', await ev(() => document.getElementById('cpHex2').value === '007F00' || document.getElementById('cpHex2').value === '008000'), await ev(() => document.getElementById('cpHex2').value));
  await page.screenshot({ path: 'picker_custom.png' });
  await page.click('#cpTabSwatches'); await wait(100);
  check('back on Colors the first box has the same color', await ev(() => document.getElementById('cpHex1').value === document.getElementById('cpHex2').value));

  // Cancel changes nothing
  await ev(() => { window.__saved.length = 0; });
  await page.click('#colorPickModal .mode-btn-row .mode-action-btn:not(.primary)'); await wait(150);
  check('Cancel closes it and leaves the color as it was', (await ev(o => !document.getElementById('colorPickModal').classList.contains('show') && document.getElementById('hexAccent').innerText === o, orig)) && (await ev(() => window.__saved.length)) === 0);

  // Apply
  await page.click('#hexAccent'); await wait(200);
  await page.fill('#cpHex1', ''); await page.type('#cpHex1', '00dfff'); await wait(100);
  await page.click('#cpApply'); await wait(300);
  check('Apply sets the color: the bubble, the circle and the page accent', await ev(() => document.getElementById('hexAccent').innerText === '#00DFFF' && getComputedStyle(document.getElementById('circAccent')).backgroundColor === 'rgb(0, 223, 255)' && getComputedStyle(document.documentElement).getPropertyValue('--accent').trim().toLowerCase() === '#00dfff'), await ev(() => document.getElementById('hexAccent').innerText + ' ' + getComputedStyle(document.documentElement).getPropertyValue('--accent')));
  check('it is saved with the preferences and Reset tweaks appears', (await ev(() => window.__saved.length)) >= 1 && await ev(() => document.getElementById('resetTweaksBtn').style.display !== 'none'));
  // each of the seven opens with its own name
  const names = [];
  for (const id of ['Accent', 'Bg', 'Card', 'Running', 'Frozen', 'System', 'Bloat']) {
    await page.click('#hex' + id); await wait(120);
    names.push(await ev(() => document.getElementById('cpTitle').innerText));
    await ev(() => closeColorPicker());
  }
  check('all seven name themselves', names.join('|') === 'Primary System Accent|App Background|Card Surface Color|Running App Badge|Frozen App Badge|System App Badge|Bloatware Badge', names.join('|'));
  await ev(() => resetColorTweaks());
  check('Reset tweaks puts the accent back', await ev(o => document.getElementById('hexAccent').innerText === o, orig));

  console.log('errors:', JSON.stringify(errors));
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
})();
