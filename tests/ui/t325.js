// Touch targets: the shared buttons and chips (.mode-action-btn, .filter-pill, .filter-chip, .copy-chip, .agent-btn, .batch-tool-link, the ? of a card,
// the File Manager and Morphe option checkboxes) are drawn at least 36px tall, and every one of them, the app-row checkbox (its icon) too, answers a tap over at
// least 44x44px: a clear ::after centred on the control reaches past the edges of a smaller one. Measured with elementFromPoint at the middle of each
// edge of that 44px box, so a neighbour that steals the tap or a scrolling row that clips it fails. The rows that scroll sideways do not start to
// scroll up and down because of it, the Apps tab's sort row stays one aligned row, the ? does not cover the line under a card's title, the two
// switches (Saved lists backup, Pure black) are a whole row tall, and a tap just beside an app row's icon (the row's checkbox) selects the app while the ⋯
// button keeps its own taps. At 400 and 320px, without horizontal page scroll.
const { chromium, PAGE } = require('./lib/pw');
const sdb = require('./lib/sdb_mock'), fm = require('./lib/fm_mock');
const SEL = '.mode-action-btn, .filter-pill, .filter-chip, .copy-chip, .agent-btn, .batch-tool-link, .help-q, .fm-opt, .mp-check, .app-checkbox, .app-sel';
const VIEWS = ['apps', 'saved-lists', 'debloater', 'files', 'console', 'settings', 'morphe', 'about', 'prefs'];
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const errors = [];
  for (const width of [400, 320]) {
    const page = await b.newPage({ viewport: { width, height: 860 } });
    page.bottomNav = true;
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(sdb.initScript);
    await page.addInitScript(fm.initScript, { files: { '/storage/emulated/0/Download/a.txt': 'x', '/storage/emulated/0/DCIM': null }, priv: true });
    await page.addInitScript(() => {
      const apps = ['com.whatsapp', 'com.spotify.music', 'org.mozilla.firefox', 'com.android.chrome'].map((pkg, i) => ({ pkg, name: pkg.split('.').pop(), isSystem: i === 3, isRunning: i === 0, isFrozen: false, isUninstalled: false, isSuspended: false, version: '1.' + i }));
      Object.assign(window.AndroidBridge, {
        vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, saveCustomLists() {}, getSystemInfo() { return '{}'; },
        isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return JSON.stringify(apps); }, loadAppIcons() { return ''; },
        loadSetting() { return ''; }, saveSetting() { return true; }, saveStore() {}, loadStore() { return ''; },
        getWorkingMode() { return JSON.stringify({ adbTcp: { connected: true, port: 5555 }, adbWireless: {}, shizuku: {}, configuredMode: 'auto', activeMode: 'adb_tcp', modeAvailable: true, isPrivileged: true }); },
        executeShell() { return ''; }, copyToClipboard() {}, getAppVersion() { return '1.0'; },
      });
    });
    await page.goto(PAGE); await page.waitForTimeout(700);
    const ev = (f, a) => page.evaluate(f, a);
    for (const v of VIEWS) {
      await ev(v => switchView(v), v); await page.waitForTimeout(450);
      const r = await ev(SEL => {
        const view = document.querySelector('.view-content.active');
        const small = [], missed = [];
        let n = 0;
        for (const el of view.querySelectorAll(SEL)) {
          if (!el.checkVisibility({ checkVisibilityCSS: true })) continue;          // not in a closed <details> or a hidden panel
          el.scrollIntoView({ block: 'center', inline: 'center' });
          const rc = el.getBoundingClientRect();
          if (!rc.width || !rc.height) continue;
          n++;
          const name = (el.id ? '#' + el.id : el.tagName.toLowerCase() + '.' + el.classList[0]) + ' "' + (el.innerText || '').trim().slice(0, 16) + '"';
          // drawn size: buttons and chips at least 36px tall (a checkbox stays a checkbox; the Console's compact 32px toolbar keeps its size)
          if (!el.classList.contains('app-checkbox') && !el.closest('.cn-tools') && rc.height < 35.5) small.push(name + ' ' + Math.round(rc.height));
          // hit box: 44x44 at least, centred
          const cx = rc.left + rc.width / 2, cy = rc.top + rc.height / 2, w = Math.max(rc.width, 44), h = Math.max(rc.height, 44);
          const pts = [[cx, cy], [cx, cy - h / 2 + 1], [cx, cy + h / 2 - 1], [cx - w / 2 + 1, cy], [cx + w / 2 - 1, cy]];
          const bad = pts.filter(([x, y]) => { const t = document.elementFromPoint(x, y); return !t || !(t === el || el.contains(t)); });
          if (bad.length) missed.push(name + ' ' + bad.map(([x, y]) => Math.round(x - cx) + ',' + Math.round(y - cy) + '>' + ((t => t ? (t.id ? '#' + t.id : t.tagName.toLowerCase() + '.' + (t.classList[0] || '')) : '-')(document.elementFromPoint(x, y)))).join(' '));
        }
        window.scrollTo(0, 0);
        // rows that scroll sideways must not scroll up and down
        const vscroll = [...view.querySelectorAll('.apps-sort-row, .filter-scroll, .copy-row, .cn-tools.cn-scroll, .uad-brand-row')]
          .filter(e => e.getClientRects().length && e.scrollHeight > e.clientHeight).map(e => (e.id || e.className) + ' ' + e.clientHeight + '/' + e.scrollHeight);
        return { n, small, missed, vscroll, docW: document.documentElement.scrollWidth };
      }, SEL);
      check(v + ' ' + width + ': ' + r.n + ' controls, the buttons and chips drawn at least 36px tall', r.n > 0 && !r.small.length, r.small.slice(0, 6).join(' | '));
      check(v + ' ' + width + ': each answers a tap over at least 44x44px, no neighbour takes it', !r.missed.length, r.missed.slice(0, 4).join(' | '));
      check(v + ' ' + width + ': no sideways row scrolls up and down, no horizontal page scroll', !r.vscroll.length && r.docW <= width, r.vscroll.join(' | ') + ' docW=' + r.docW);
      if (v !== 'apps' && v !== 'about') {
        // the ? of the card: 36px, and no text of its card under it
        const q = await ev(() => {
          const q = document.querySelector('.view-content.active .help-q');
          if (!q || !q.getClientRects().length) return null;
          const qr = q.getBoundingClientRect(), hits = [];
          const w = document.createTreeWalker(q.parentElement, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            if (q.contains(n) || !n.textContent.trim()) continue;
            const rg = document.createRange(); rg.selectNodeContents(n);
            for (const t of rg.getClientRects()) if (t.right > qr.left && t.left < qr.right && t.bottom > qr.top + 0.5 && t.top < qr.bottom - 0.5) hits.push(n.textContent.trim().slice(0, 16));
          }
          return { w: Math.round(qr.width), h: Math.round(qr.height), hits };
        });
        check(v + ' ' + width + ': the ? is 36x36 and no text of its card lies under it', q && q.w >= 36 && q.h >= 36 && !q.hits.length, JSON.stringify(q));
      }
    }
    // the Apps tab's sort row: one row, every control the same height and centred on one line
    await ev(() => switchView('apps')); await page.waitForTimeout(400);
    const row = await ev(() => [...document.querySelectorAll('#view-apps .apps-sort-row > *')].filter(e => e.getClientRects().length && getComputedStyle(e).position !== 'absolute')
      .map(e => { const r = e.getBoundingClientRect(); return [Math.round(r.height), Math.round(r.top + r.height / 2)]; }));
    check('apps ' + width + ': the sort row is one line of 36px controls', row.length >= 3 && row.every(([h, c]) => h === 36 && Math.abs(c - row[0][1]) <= 1), JSON.stringify(row));
    // a tap just beside an app row's icon (the row's checkbox: 40px, answering over 44px) selects the app; the ⋯ button keeps its taps
    const pick = await ev(() => {
      const card = document.querySelector('#view-apps .app-card');
      const cb = card.querySelector('.app-sel'), more = card.querySelector('.app-actions .btn-mini:last-child');
      card.scrollIntoView({ block: 'center' });
      const r = cb.getBoundingClientRect(), m = more.getBoundingClientRect();
      const beside = document.elementFromPoint(r.left - 1.5, r.top + r.height / 2), under = document.elementFromPoint(r.left + r.width / 2, r.bottom + 1.5);
      const moreHit = document.elementFromPoint(m.left + m.width / 2, m.top + m.height / 2), moreEdge = document.elementFromPoint(m.right - 2, m.top + m.height / 2);
      return { beside: beside === cb, under: under === cb, more: moreHit === more || more.contains(moreHit), moreEdge: moreEdge === more || more.contains(moreEdge), cb: Math.round(r.width) + 'x' + Math.round(r.height), x: r.left - 1.5, y: Math.round(r.top + r.height / 2), pkg: card.dataset.pkg };
    });
    await page.mouse.click(pick.x, pick.y); await page.waitForTimeout(150);
    const sel = await ev(pkg => document.getElementById('card_' + pkg).classList.contains('selected'), pick.pkg);
    check('apps ' + width + ': the 40px icon checkbox of a row answers 1.5px to its left and below it, and selects the app', pick.cb === '40x40' && pick.beside && pick.under && sel, JSON.stringify(pick) + ' selected=' + sel);
    check('apps ' + width + ': the ⋯ button beside it keeps its middle and its edge', pick.more && pick.moreEdge, JSON.stringify(pick));
    await ev(pkg => toggleSelectPkg(pkg), pick.pkg);
    // what is inside a control keeps its own taps: the hit area lies under it (the File Manager's tick boxes, a preset chip's name and its ×)
    await ev(() => switchView('files')); await page.waitForTimeout(400);
    const inner = await ev(() => {
      const at = el => { el.scrollIntoView({ block: 'center' }); const r = el.getBoundingClientRect(); return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === el; };
      const box = at(document.getElementById('fmHiddenChk'));
      const chip = document.createElement('span'); chip.className = 'filter-chip preset-chip'; chip.innerHTML = '<span class="pc-name">Preset</span><span class="preset-x">×</span>';
      document.querySelector('#view-files .fm-opts').appendChild(chip);
      const name = at(chip.querySelector('.pc-name')), x = at(chip.querySelector('.preset-x'));
      chip.remove();
      return { box, name, x };
    });
    check('files ' + width + ': a tick box inside its option label, and the name and × inside a chip, keep their own taps', inner.box && inner.name && inner.x, JSON.stringify(inner));
    // the two switches are whole rows: the label row is the target, at least 44px tall, and the track hands its tap to the row (Pure black flips)
    for (const [view, id] of [['saved-lists', 'lbAuto'], ['prefs', 'pureBlackToggle']]) {
      await ev(v => switchView(v), view); await page.waitForTimeout(400);
      const s = await ev(id => {
        const i = document.getElementById(id), row = i.closest('.switch-row');
        row.scrollIntoView({ block: 'center' });
        const t = i.nextElementSibling.getBoundingClientRect(), x = t.left + t.width / 2, y = t.top + t.height / 2;
        const hit = document.elementFromPoint(x, y);
        return { h: Math.round(row.getBoundingClientRect().height), x, y, was: i.checked, label: row.tagName === 'LABEL' && row.contains(i) && !!hit && hit.closest('.switch-row') === row };
      }, id);
      let flips = true;
      if (id === 'pureBlackToggle') {
        await page.mouse.click(s.x, s.y); await page.waitForTimeout(150);
        flips = (await ev(id => document.getElementById(id).checked, id)) !== s.was;
        await page.mouse.click(s.x, s.y); await page.waitForTimeout(150);
      }
      check(view + ' ' + width + ': the ' + id + ' switch is a whole label row ' + (s.h >= 44 ? 'at least 44px' : s.h + 'px') + ' tall that takes the taps on its track' + (id === 'pureBlackToggle' ? ' (and flips)' : ''), s.h >= 44 && s.label && flips, JSON.stringify(s));
    }
    await page.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
  if (failed || errors.length) { console.log((failed || errors.length) + ' FAILED'); process.exit(1); }
})();
