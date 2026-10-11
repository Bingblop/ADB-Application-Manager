// App Stores: the screenshots in an app's details can be tapped to look at them full screen. The viewer shows the picture to fit the screen, counts them (2 / 4), has arrows,
// swipes to the next, zooms with the buttons, the wheel, a double tap and a pinch, drags a zoomed picture without letting it leave the screen, closes with Back, a tap beside the
// picture, the X or Escape (the details stay open under it), says when a picture cannot be loaded, and shows only web addresses.
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const svg = (w, h, label, color) => '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '"><rect width="100%" height="100%" fill="' + color + '"/><text x="50%" y="50%" font-size="60" text-anchor="middle" fill="#fff">' + label + '</text></svg>';
  await page.route('https://img.test/**', route => {
    const u = route.request().url();
    if (/broken/.test(u)) return route.fulfill({ status: 404, body: 'no' });
    const m = /\/(\d+)x(\d+)-(\w+)\.svg/.exec(u);
    return route.fulfill({ contentType: 'image/svg+xml', body: svg(+m[1], +m[2], m[3], '#' + ({ one: '3366aa', two: 'aa6633', three: '33aa66', four: '8833aa' }[m[3]] || '444444')) });
  });
  await page.addInitScript(() => {
    window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; },
      saveStore() { return true; }, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, loadPackages() { return '[]'; },
      loadSetting(k) { return k === 'perm_intro_v62' ? '1' : ''; }, saveSetting() {}, getWorkingMode() { return '{}'; } };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);
  const ev = (f, a) => page.evaluate(f, a);
  const wait = ms => page.waitForTimeout(ms);
  const toast = () => ev(() => document.getElementById('toastMsg').innerText);
  const st = () => ev(() => ({ open: shotIsOpen(), src: (document.getElementById('shotImg').getAttribute('src') || ''), idx: shotIdx, s: shotS, x: shotX, y: shotY, count: document.getElementById('shotCount').innerText, prev: !document.getElementById('shotPrev').disabled, next: !document.getElementById('shotNext').disabled, msg: document.getElementById('shotMsg').innerText, store: document.getElementById('storeModal').classList.contains('show') }));
  const U = ['https://img.test/800x400-one.svg', 'https://img.test/300x900-two.svg', 'https://img.test/600x600-three.svg', 'https://img.test/broken-four.svg'];

  await ev(u => { storeCurrent = storeCurrent || {}; document.getElementById('storeModal').classList.add('show'); renderStoreDetail({ slug: 'x', app: { name: 'Pic App', packageName: 'pic.app', fullDescription: 'Text', screenshots: u.concat(['javascript:alert(1)', 'data:image/svg+xml,<svg/>', 'file:///etc/passwd', '//img.test/x.png', 5, null]) }, download: null }); }, U);
  await wait(300);

  // ---- the thumbnails ----
  const th = await ev(() => [...document.querySelectorAll('#storeDetailBody .shot-thumb')].map(i => ({ src: i.getAttribute('src'), role: i.getAttribute('role'), tab: i.tabIndex, alt: i.alt })));
  check('the details show the screenshots as tappable pictures, only web addresses (javascript:, data:, file: and relative ones are not shown)', th.length === 4 && th.every(t => /^https:\/\/img\.test\//.test(t.src) && t.role === 'button' && t.tab === 0 && /tap to enlarge/.test(t.alt)), JSON.stringify(th));
  check('a line says to tap a picture to enlarge it', await ev(() => /Tap a picture to enlarge it/.test(document.getElementById('storeDetailBody').innerText)));
  check('the viewer is closed to begin with', !(await st()).open);

  // ---- open ----
  await page.click('#storeDetailBody .shot-thumb:nth-child(2)'); await wait(300);
  let s = await st();
  check('tapping the second picture opens the viewer on it, counted 2 / 4, with both arrows, over the details', s.open && s.idx === 1 && /300x900-two/.test(s.src) && s.count === '2 / 4' && s.prev && s.next && s.store, JSON.stringify(s));
  check('the picture is shown to fit the screen (a tall one: its height is the screen\'s)', await ev(() => { const i = document.getElementById('shotImg'), r = i.getBoundingClientRect(); return i.complete && i.naturalWidth === 300 && Math.abs(r.height - document.getElementById('shotStage').clientHeight) < 2 && r.width < 400; }));
  check('the viewer covers the whole screen, above the details', await ev(() => { const v = document.getElementById('shotViewer').getBoundingClientRect(); const top = document.elementFromPoint(200, 430); return v.width === window.innerWidth && v.height === window.innerHeight && !!top.closest('#shotViewer'); }));

  // ---- the arrows ----
  await page.click('#shotNext'); await wait(250); s = await st();
  check('the next arrow shows the third picture, 3 / 4', s.idx === 2 && /600x600-three/.test(s.src) && s.count === '3 / 4');
  await page.click('#shotNext'); await wait(300); s = await st();
  check('the last picture: 4 / 4, no next arrow; one that cannot load says so in words', s.idx === 3 && !s.next && s.prev && /could not be loaded/.test(s.msg), JSON.stringify(s));
  await page.click('#shotPrev'); await page.click('#shotPrev'); await page.click('#shotPrev'); await wait(300); s = await st();
  check('back to the first: 1 / 4, no previous arrow, and the message is gone', s.idx === 0 && !s.prev && s.next && s.msg === '' && s.count === '1 / 4', JSON.stringify(s));

  // ---- zoom with the buttons ----
  const zoom = label => page.click('#shotViewer .shot-btn[aria-label="' + label + '"]');
  await zoom('Zoom in'); s = await st();
  check('Zoom in: 1.5 times', Math.abs(s.s - 1.5) < 0.01, JSON.stringify(s));
  await zoom('Zoom in'); await zoom('Zoom in'); await zoom('Zoom in'); await zoom('Zoom in'); await zoom('Zoom in'); await zoom('Zoom in'); s = await st();
  check('the zoom stops at 6 times', Math.abs(s.s - 6) < 0.01, String(s.s));
  await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); await zoom('Zoom out'); s = await st();
  check('Zoom out goes back to fit and never below it, with the picture centred again', s.s === 1 && s.x === 0 && s.y === 0, JSON.stringify(s));
  await zoom('Zoom in'); await page.click('#shotFit'); s = await st();
  check('Fit goes straight back to the whole picture', s.s === 1 && s.x === 0);

  // ---- drag a zoomed picture, never past the edge ----
  const stage = await ev(() => { const r = document.getElementById('shotStage').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height }; });
  const ptr = (type, id, x, y) => ev(a => { document.getElementById('shotStage').dispatchEvent(new PointerEvent(a.type, { pointerId: a.id, clientX: a.x, clientY: a.y, bubbles: true, cancelable: true, pointerType: 'touch', isPrimary: a.id === 1 })); }, { type, id, x, y });
  await zoom('Zoom in'); await zoom('Zoom in');             // 2.25 times
  await ptr('pointerdown', 1, 200, 400); await ptr('pointermove', 1, 260, 380); await ptr('pointerup', 1, 260, 380); s = await st();
  check('dragging a zoomed picture moves it with the finger', s.s > 2 && s.x > 40 && s.x < 70 && s.y === 0, JSON.stringify(s));
  check('(this wide picture is shorter than the screen even zoomed, so it cannot move up or down)', true);
  await ptr('pointerdown', 1, 100, 100); await ptr('pointermove', 1, 4000, 4000); await ptr('pointerup', 1, 4000, 4000); s = await st();
  const lim = await ev(() => shotLimits());
  check('but never past the edge: the picture still covers the screen', s.x <= lim.x + 0.5 && s.x >= -lim.x - 0.5 && s.y <= lim.y + 0.5 && s.y >= -lim.y - 0.5 && s.s > 2, JSON.stringify([s, lim]));
  check('a zoomed picture is not swiped away to the next one', (await st()).idx === 0);
  await page.click('#shotFit');

  // ---- swipe at fit ----
  await ptr('pointerdown', 1, 320, 430); await ptr('pointermove', 1, 200, 432); await ptr('pointermove', 1, 100, 434); await ptr('pointerup', 1, 100, 434); await wait(200); s = await st();
  check('a swipe to the left shows the next picture', s.idx === 1 && s.count === '2 / 4', JSON.stringify(s));
  await ptr('pointerdown', 1, 80, 430); await ptr('pointermove', 1, 200, 432); await ptr('pointermove', 1, 300, 434); await ptr('pointerup', 1, 300, 434); await wait(200); s = await st();
  check('a swipe to the right shows the previous one', s.idx === 0, JSON.stringify(s));
  await ptr('pointerdown', 1, 80, 430); await ptr('pointermove', 1, 300, 434); await ptr('pointerup', 1, 300, 434); await wait(150);
  check('a swipe at the first picture goes nowhere', (await st()).idx === 0);
  await ptr('pointerdown', 1, 200, 430); await ptr('pointermove', 1, 215, 600); await ptr('pointerup', 1, 215, 600); await wait(150);
  check('a mostly vertical drag, or a short one, does not change picture', (await st()).idx === 0);

  // ---- double tap, wheel, pinch ----
  await ptr('pointerdown', 1, 200, 430); await ptr('pointerup', 1, 200, 430); await wait(80);
  await ptr('pointerdown', 1, 200, 430); await ptr('pointerup', 1, 200, 430); await wait(60); s = await st();
  check('a double tap zooms in to 2.5 times', Math.abs(s.s - 2.5) < 0.01 && (await st()).open, JSON.stringify(s));
  await wait(400);
  await ptr('pointerdown', 1, 200, 430); await ptr('pointerup', 1, 200, 430); await wait(80);
  await ptr('pointerdown', 1, 200, 430); await ptr('pointerup', 1, 200, 430); await wait(60); s = await st();
  check('and a double tap on a zoomed picture goes back to fit', s.s === 1 && s.open, JSON.stringify(s));
  await page.mouse.move(200, 430); await page.mouse.wheel(0, -300); await wait(100); s = await st();
  check('the mouse wheel zooms in (on a computer)', s.s > 1.15, String(s.s));
  await page.click('#shotFit');
  await ptr('pointerdown', 1, 150, 430); await ptr('pointerdown', 2, 250, 430);
  await ptr('pointermove', 1, 100, 430); await ptr('pointermove', 2, 300, 430); s = await st();
  check('a pinch (two fingers moving apart, 100 to 200 px) zooms about twice', s.s > 1.8 && s.s < 2.2, String(s.s));
  await ptr('pointermove', 1, 190, 430); await ptr('pointermove', 2, 210, 430); await ptr('pointerup', 1, 190, 430); await ptr('pointerup', 2, 210, 430); s = await st();
  check('and pinching together again goes back to fit', s.s === 1, String(s.s));
  check('lifting the fingers of a pinch does not swipe or close anything', s.open && s.idx === 0);

  // ---- close ----
  await ev(() => handleAndroidBack()); await wait(150); s = await st();
  check('Back closes the viewer first, and the details stay open under it', !s.open && s.store && s.src === '', JSON.stringify(s));
  await page.click('#storeDetailBody .shot-thumb:nth-child(3)'); await wait(250);
  await page.keyboard.press('Escape'); await wait(100);
  check('Escape closes it', !(await st()).open && (await st()).store);
  await page.click('#storeDetailBody .shot-thumb:nth-child(1)'); await wait(250);
  await page.click('#shotViewer .shot-btn[aria-label="Close"]'); await wait(100);
  check('the X closes it', !(await st()).open);
  await page.click('#storeDetailBody .shot-thumb:nth-child(1)'); await wait(250);
  await page.mouse.click(200, 140); await wait(500);
  check('a tap beside the picture closes it', !(await st()).open);
  await page.click('#storeDetailBody .shot-thumb:nth-child(1)'); await wait(250);
  await page.mouse.click(200, 430); await wait(500);
  check('but a tap on the picture does not', (await st()).open);
  await page.keyboard.press('ArrowRight'); await wait(150);
  check('the arrow keys step through the pictures too (on a computer)', (await st()).idx === 1);
  await ev(() => handleAndroidBack()); await wait(100);

  // ---- keyboard on a thumbnail, a single picture, no pictures ----
  await ev(() => { document.querySelector('#storeDetailBody .shot-thumb:nth-child(2)').focus(); }); await page.keyboard.press('Enter'); await wait(250);
  check('Enter on a focused picture opens it', (await st()).open && (await st()).idx === 1);
  await ev(() => handleAndroidBack());
  await ev(u => { renderStoreDetail({ slug: 'y', app: { name: 'One Pic', packageName: 'one.pic', fullDescription: 'x', screenshots: [u] }, download: null }); }, U[0]);
  await page.click('#storeDetailBody .shot-thumb'); await wait(250); s = await st();
  check('with one picture there is no count and no arrows', s.open && s.count === 'Picture' && !s.prev && !s.next, JSON.stringify(s));
  await ev(() => handleAndroidBack());
  await ev(() => { renderStoreDetail({ slug: 'z', app: { name: 'No Pics', packageName: 'no.pics', fullDescription: 'x', screenshots: [] }, download: null }); });
  check('without pictures there is nothing to tap and no hint', await ev(() => !document.querySelector('#storeDetailBody .shot-thumb') && !/enlarge/.test(document.getElementById('storeDetailBody').innerText)));
  await ev(u => { renderStoreDetail({ slug: 'w', app: { name: 'Again', packageName: 'again', fullDescription: 'x', screenshots: u.slice(0, 2) }, download: null }); }, U);
  await page.click('#storeDetailBody .shot-thumb:nth-child(1)'); await wait(250);
  check('a new app brings its own pictures (2 of them)', (await st()).count === '1 / 2');
  await page.screenshot({ path: 'shot_viewer.png' });

  console.log('errors:', JSON.stringify(errors));
  if (errors.length) failed++;
  if (failed) { console.log(failed + ' FAILED'); process.exit(1); }
  await b.close();
  console.log('all ok');
  process.exit(0);
})();
