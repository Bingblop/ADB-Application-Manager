// v7.15, the code reviews of the UI pass (parts 3 and 4): where the new layout was wrong for a language, a narrow screen or a finger.
// 1. Files: the path crumbs read from the root in Arabic too, so a folder named in Arabic no longer turns the line around and scrolls the open folder
//    out of view, and the line's fade follows it (the hidden start, on the left); every crumb is at least 44 px wide and 40 px tall (the whole line,
//    which reaches over the list card's padding so the list does not move down), and the "/" after a folder is part of its crumb, so no strip
//    between two crumbs does nothing.
// 2. Task Manager: Graph speed keeps a slider of at least 120 px (it was squeezed to 2 px in German at 320 px): one line where that fits, else the
//    slider and its value take the next line together (the value never wraps alone).
// 3. Live Logcat: the two tool rows meet without overlapping, so a tap just under All apps is All apps, not Recordings.
// 4. Palette Style: three a row while every tile keeps room for the longest name, else two, so no style name ("Нейтральный", "Monochrome")
//    breaks between letters at any width.
// 5. The Files shortcut to the Download folder is labelled "Downloads", the folder (the key "Download" is the verb in every language).
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock');
const sdb = require('./lib/sdb_mock');
const con = require('./lib/console_mock');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, ok || extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = '/storage/emulated/0';
const DEEP = ROOT + '/Android/data/com.example.a.rather.long.package.name/ملفات';

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const newPage = async (width, init, arg) => {
    const page = await b.newPage({ viewport: { width, height: 800 }, colorScheme: 'dark' });
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept().catch(() => {}));
    await page.addInitScript(init, arg);
    await page.goto(PAGE); await sleep(400);
    return page;
  };

  // 1. Files: the crumbs
  for (const lang of ['en', 'ar']) {
    const page = await newPage(360, fm.initScript, { files: { [ROOT + '/Download/a.txt']: 'A', [DEEP + '/b.txt']: 'B' }, priv: true, kv: { perm_intro_v61: '1' } });
    if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await sleep(400); }
    await page.evaluate(() => switchView('files')); await sleep(400);
    await page.evaluate(p => fmGo(p), lang === 'ar' ? DEEP : ROOT + '/Download'); await sleep(500);
    const g = await page.evaluate(() => {
      const el = document.getElementById('fmCurrentPath'), c = el.getBoundingClientRect(), cs = getComputedStyle(el);
      const crumbs = [...el.querySelectorAll('.fm-crumb')].map(x => { const r = x.getBoundingClientRect(); return { t: x.textContent, l: r.left, r: r.right, w: r.width, h: r.height, p: x.dataset.p }; });
      const here = el.querySelector('.fm-crumb-here').getBoundingClientRect();
      const card = document.getElementById('fmListCard').getBoundingClientRect();
      return { dir: cs.direction, mask: cs.webkitMaskImage || cs.maskImage || '', crumbs, inCard: c.top >= card.top - 0.5, hereIn: here.left >= c.left - 1 && here.right <= c.right + 1, looseSeps: [...el.querySelectorAll('.fm-crumb-sep')].filter(s => !s.closest('.fm-crumb')).length, text: el.textContent, path: fmPath };
    });
    const touching = g.crumbs.every((x, i) => i === 0 || Math.abs(x.l - g.crumbs[i - 1].r) <= 1);
    check('1. [' + lang + '] the path line reads left to right from the root, the open folder in view, its fade running left to right', g.dir === 'ltr' && g.crumbs[0].t === '/' && g.hereIn && g.text === g.path && g.mask.startsWith('linear-gradient(to right'), JSON.stringify({ dir: g.dir, first: g.crumbs[0], hereIn: g.hereIn, text: g.text, mask: g.mask.slice(0, 40) }));
    check('   [' + lang + '] every crumb is at least 44 x 40 px, inside the list card, each touches the next, no "/" outside a crumb', g.crumbs.every(x => x.w >= 44 - 0.5 && x.h >= 40 - 0.5) && g.inCard && touching && g.looseSeps === 0, JSON.stringify(g.crumbs.map(x => [x.t, Math.round(x.w), Math.round(x.h)])));
    if (lang === 'en') {
      // a tap on the "/" right after "0" opens /storage/emulated/0 (it used to be a strip that did nothing)
      const sep = await page.evaluate(() => { const c = [...document.querySelectorAll('#fmCurrentPath .fm-crumb')].find(x => x.dataset.p === '/storage/emulated/0'), s = (c.querySelector('.fm-crumb-sep') || c.nextElementSibling).getBoundingClientRect(); return { x: s.left + s.width / 2, y: s.top + s.height / 2 }; });
      await page.mouse.click(sep.x, sep.y); await sleep(500);
      check('   a tap on the "/" after "0" opens the storage root', (await page.evaluate(() => fmPath)) === ROOT, await page.evaluate(() => fmPath));
    }
    await page.close();
  }

  // 2. Task Manager: Graph speed
  for (const lang of ['en', 'de']) {
    for (const width of [320, 360, 412]) {
      const page = await newPage(width, sdb.initScript);
      if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await sleep(300); }
      await page.evaluate(() => switchView('taskmgr')); await sleep(400);
      const g = await page.evaluate(() => {
        const s = document.getElementById('tmGraphDelay'), row = s.closest('.tm-settings-row'), v = document.getElementById('tmGraphDelayVal'), lab = row.firstElementChild;
        const r = x => x.getBoundingClientRect(), rr = r(row), rs = r(s), rv = r(v), rl = r(lab);
        const cy = x => (x.top + x.bottom) / 2;
        return { slider: Math.round(rs.width), valueIn: rv.right <= rr.right + 0.5, valueBeside: Math.abs(cy(rv) - cy(rs)) < 2 && rv.left >= rs.right - 0.5, oneLine: Math.abs((rs.top + rs.bottom) / 2 - (rl.top + rl.bottom) / 2) < 6, docW: document.documentElement.scrollWidth, W: innerWidth };
      });
      const oneLineOk = !(lang === 'en' && width === 412) || g.oneLine;
      check('2. [' + lang + ' ' + width + ' px] Graph speed has a slider of 120 px or more, its value beside it inside the row' + (lang === 'en' && width === 412 ? ', all on one line' : ''), g.slider >= 120 && g.valueIn && g.valueBeside && oneLineOk && g.docW <= g.W, JSON.stringify(g));
      await page.close();
    }
  }

  // 3. Live Logcat: the two tool rows
  {
    const page = await newPage(400, con.initScript, { kv: { perm_intro_v61: '1' }, log: '10-12 10:00:01.123  1  1 I Tag: hello\n' });
    await page.evaluate(() => switchView('logcat')); await sleep(600);
    const g = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('#view-logcat .cn-tools.cn-scroll')].map(x => x.getBoundingClientRect());
      const a = document.getElementById('logcatAppBtn').getBoundingClientRect();
      const probe = y => { const h = document.elementFromPoint(a.left + a.width / 2, y); return h ? (h.closest('button') || h).id || h.className : null; };
      return { gap: rows.length >= 2 ? +(rows[1].top - rows[0].bottom).toFixed(1) : null, below2: probe(a.bottom + 2), below5: probe(a.bottom + 5) };
    });
    check('3. Live Logcat: the second tool row starts where the first ends, and a tap just under All apps is All apps', g.gap !== null && g.gap >= -0.5 && g.below2 === 'logcatAppBtn' && g.below5 === 'logcatAppBtn', JSON.stringify(g));
    await page.close();
  }

  // 4. Palette Style: the grid
  const bridge = () => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; }, copyToClipboard() {}, getMaterialYouColors() { return JSON.stringify({ supported: false }); }
    };
  };
  for (const lang of ['ru', 'fr', 'en']) {
    for (const width of [320, 360, 384, 392, 412]) {
      const page = await newPage(width, bridge);
      if (lang !== 'en') { await page.evaluate(l => I18N.set(l), lang); await sleep(300); }
      await page.evaluate(() => { switchView('prefs'); const g = document.getElementById('psStyleGrid'), c = g && g.closest('.cc'); if (c && c.classList.contains('cc-closed')) ccApply(c, true, false); }); await sleep(500);
      const g = await page.evaluate(() => {
        const grid = document.getElementById('psStyleGrid');
        const cols = getComputedStyle(grid).gridTemplateColumns.split(' ').length;
        const broken = [...grid.querySelectorAll('.preset-name')].filter(n => {
          const cs = getComputedStyle(n), probe = document.createElement('span');
          probe.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font:' + cs.font + ';letter-spacing:' + cs.letterSpacing;
          document.body.appendChild(probe);
          const tooWide = n.innerText.split(/\s+/).some(w => { probe.textContent = w; return probe.getBoundingClientRect().width > n.clientWidth + 0.5; });
          probe.remove();
          return tooWide;
        }).map(n => n.innerText);
        return { cols, broken };
      });
      const want = width === 320 ? ' two a row,' : width === 412 ? ' three a row,' : '';
      const colsOk = g.cols >= 2 && g.cols <= 3 && (width !== 320 || g.cols === 2) && (width !== 412 || g.cols === 3);
      check('4. [' + lang + ' ' + width + ' px] Palette Style:' + want + ' every name fits its tile without breaking a word', colsOk && g.broken.length === 0, JSON.stringify(g));
      await page.close();
    }
  }

  // 5. The Files shortcut to the Download folder
  {
    const page = await newPage(400, fm.initScript, { files: { [ROOT + '/a.txt']: 'A' }, priv: true, kv: { perm_intro_v61: '1' } });
    const s = await page.evaluate(() => { const it = TAB_SHORTCUTS.files.find(i => i.id === 'download'); return { label: it.label, run: String(it.run) }; });
    check('5. the Files shortcut to /storage/emulated/0/Download is labelled "Downloads" (the folder), not "Download" (the verb)', s.label === 'Downloads' && s.run.includes('/storage/emulated/0/Download'), JSON.stringify(s));
    await page.close();
  }

  check('no page errors', errors.length === 0, JSON.stringify(errors.slice(0, 3)));
  await b.close();
  console.log(bad ? 'FAILED ' + bad : 'ALL OK');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e.stack || e); process.exit(1); });
