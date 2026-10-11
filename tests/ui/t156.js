// v7.12.10: a patched APK whose file cannot be seen (kept in Downloads, the storage is out of reach) stays in the Patched APKs list with a "File not found" label and a
// short explanation; Install, Share and Save to Downloads are off for it, while Log and Delete stay available. A normal entry has none of this.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sheet_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (extra === undefined ? '' : ': ' + extra)); }
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [w, h] of [[360, 800], [412, 915]]) {
    const tag = w + 'x' + h;
    const page = await b.newPage({ viewport: { width: w, height: h }, hasTouch: true });
    page.on('pageerror', e => errors.push(tag + ' ' + e.message));
    await page.addInitScript(mock.initScript, { dark: true });
    await page.goto(PAGE); await page.waitForTimeout(450);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    await ev(() => {
      const now = Date.now();
      mp.patchedOpen = true;
      mp.patched = [
        { id: 'a-1', pkg: 'com.a.one', appName: 'App One', versionName: '1.0', patchedAt: now, size: 1000, patches: ['X'], installed: false },
        { id: 'b-2', pkg: 'com.b.two', appName: 'App Two', versionName: '2.0', patchedAt: now - 1000, size: 2000, patches: ['Y'], installed: false, missing: true },
      ];
      mpSheetOpen({ title: 'Patched APKs', sub: '', body: '' });
      mpPatchedRender();
    });
    await page.waitForTimeout(300);
    const cards = await ev(() => [...document.querySelectorAll('#mpSheetBody .color-card')].map(c => ({
      name: c.querySelector('.mp-name').textContent.trim(),
      chips: [...c.querySelectorAll('.mp-chip')].map(x => x.textContent.trim()),
      note: [...c.querySelectorAll('.mp-note')].map(x => x.textContent.trim()).filter(t => /not where it was saved/.test(t)),
      disabled: [...c.querySelectorAll('.mp-act')].filter(x => x.disabled).map(x => x.textContent.trim()),
      enabled: [...c.querySelectorAll('.mp-act')].filter(x => !x.disabled).map(x => x.textContent.trim()),
    })));
    const one = cards.find(c => c.name.startsWith('App One')), two = cards.find(c => c.name.startsWith('App Two'));
    check(tag + ' 1. a normal entry has no "File not found" label, no note, and all its buttons', one && !one.chips.includes('File not found') && one.note.length === 0 && one.disabled.length === 0 && one.enabled.length === 5, JSON.stringify(one));
    check(tag + ' 2. a missing entry shows the "File not found" label and the explanation', two && two.chips.includes('File not found') && two.note.length === 1, JSON.stringify(two));
    check(tag + ' 3. Install, Share and Save to Downloads are off for it; Log and Delete stay on', two && two.disabled.join() === 'Install,Share,Save to Downloads' && two.enabled.join() === 'Log,Delete', JSON.stringify(two));
    const ov = await ev(() => document.documentElement.scrollWidth > window.innerWidth);
    check(tag + ' 4. no sideways scroll', !ov);
    await page.close();
  }
  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
