// Records the frames of the two launch GIFs from the UI test harness (bottom bar + shortcut menu; the Private DNS speed test). See docs/LAUNCH.md.
const path = require('path'), fs = require('fs');
const LIB = process.env.LIB;
const { chromium, PAGE } = require(path.join(LIB, 'pw'));
const bnav = require(path.join(LIB, 'bnav_mock.js'));
const sdb = require(path.join(LIB, 'sdb_mock.js'));
const dns = require(path.join(LIB, 'dns_mock.js'));
const xs = require(path.join(LIB, 'xs_mock.js'));
const OUT = process.env.FRAMES;
const VP = { width: 360, height: 740 };
const noTip = (page) => page.evaluate(() => { for (const el of document.querySelectorAll('body *')) { if (el.children.length === 0 && /^Long-press a tab for shortcuts$/.test((el.textContent || '').trim())) { let n = el; while (n.parentElement && getComputedStyle(n.parentElement).position !== 'static' && n.parentElement !== document.body) n = n.parentElement; n.remove(); } } });
(async () => {
  const b = await chromium.launch();
  const rec = (name) => { const frames = []; let n = 0; return {
    frame: async (page, delayCs) => { const f = path.join(OUT, name + '-' + String(n++).padStart(3, '0') + '.png'); await page.screenshot({ path: f }); frames.push({ f, d: delayCs }); },  // developer tool; name is one of a few fixed labels. nosemgrep: javascript.lang.security.audit.path-traversal.path-join-resolve-traversal.path-join-resolve-traversal
    done: () => fs.writeFileSync(path.join(OUT, name + '.list'), frames.map(x => '-delay ' + x.d + ' ' + x.f).join('\n')) }; };  // developer tool; name is one of a few fixed labels. nosemgrep: javascript.lang.security.audit.path-traversal.path-join-resolve-traversal.path-join-resolve-traversal
  // ---- 1. bottom bar: tap through the tabs, hold a slot for the shortcut menu, open More ----
  let page = await b.newPage({ viewport: VP }); page.bottomNav = true;
  const updates = [1, 2].map(i => ({ pkg: 'org.example.app' + i, name: 'App ' + i, installedVersion: '1.0', availableVersion: '1.1', source: 'github', downloadUrl: 'https://github.com/x/y/a' + i + '.apk' }));
  await page.addInitScript(bnav.initScript, { updates });
  await page.goto(PAGE); await page.waitForTimeout(1200); await noTip(page); await page.waitForTimeout(100);
  const r1 = rec('bottom-bar');
  await r1.frame(page, 120);
  const slot = async (tab) => { const h = await page.$('#bnav .bn-item[data-tab="' + tab + '"]'); const bb = await h.boundingBox(); return { x: bb.x + bb.width / 2, y: bb.y + bb.height / 2 }; };
  for (const t of ['installer', 'console', 'extrasettings']) {
    const s = await slot(t); await page.mouse.click(s.x, s.y); await page.waitForTimeout(120); await r1.frame(page, 8); await page.waitForTimeout(500); await r1.frame(page, 90);
  }
  const s0 = await slot('apps'); await page.mouse.click(s0.x, s0.y); await page.waitForTimeout(500); await r1.frame(page, 70);
  const si = await slot('installer'); await page.mouse.move(si.x, si.y); await page.mouse.down(); await page.waitForTimeout(250); await r1.frame(page, 25); await page.waitForTimeout(450); await r1.frame(page, 120);
  await page.mouse.up(); await page.waitForTimeout(150); await r1.frame(page, 60);
  await page.keyboard.press('Escape'); await page.waitForTimeout(400); await r1.frame(page, 40);
  await page.click('#bnav .bn-item:not([data-tab])'); await page.waitForTimeout(150); await r1.frame(page, 8); await page.waitForTimeout(500); await r1.frame(page, 160);
  r1.done(); await page.close();
  // ---- 2. the Private DNS speed test ----
  page = await b.newPage({ viewport: { width: 360, height: 800 } }); page.bottomNav = true;
  await page.addInitScript(sdb.initScript); await page.addInitScript(dns.initScript); await page.addInitScript(xs.initScript);
  await page.goto(PAGE); await page.waitForTimeout(800);
  await page.evaluate(() => switchView('extrasettings')); await page.waitForTimeout(900);
  await noTip(page); await page.evaluate(() => { const b = document.querySelector('#privateDnsCard .pdns-services-label, #pdnsTest'); document.getElementById('pdnsTest').scrollIntoView({ block: 'start' }); window.scrollBy(0, -8); }); await page.waitForTimeout(300);
  const r2 = rec('dns-speed-test');
  await r2.frame(page, 110);
  await page.evaluate(() => document.getElementById('pdnsTest').click()); await page.waitForTimeout(150); await r2.frame(page, 60);
  const HOSTS = await page.evaluate(() => PDNS_PROVIDERS.map(s => s.host));
  const times = { 'dns.google': [12, 30, 18], 'one.one.one.one': [8, 20, 11], 'dns.quad9.net': [30, 40, 20], 'dns.adguard-dns.com': [9, 20, 11], 'dot.sb': [110, 240, 150] };
  const order = ['one.one.one.one', 'dns.google', 'dns.adguard-dns.com', 'dns.quad9.net', ...HOSTS.filter(h => !(h in times))];
  let i = 0;
  for (const h of order) {
    if (h === 'dns.opendns.com') await page.evaluate(h => __dnsFail(h, 'timeout'), h);
    else { const t = times[h] || [20 + (i * 7) % 40, 30 + (i * 11) % 60, 15 + (i * 5) % 30]; await page.evaluate(a => __dnsOk(a[0], a[1], a[2], a[3]), [h, t[0], t[1], t[2]]); }
    i++; await page.waitForTimeout(60); await r2.frame(page, i < 4 ? 45 : 25);
  }
  await page.evaluate(h => __dnsOk(h, 110, 240, 150), 'dot.sb'); await page.waitForTimeout(200); await r2.frame(page, 150);
  // sort by speed, if the switch exists
  const sortId = await page.evaluate(() => { const el = document.querySelector('[id^="pdnsSort"]'); return el ? el.id : null; });
  if (sortId) { await page.evaluate(id => document.getElementById(id).click(), sortId); await page.waitForTimeout(500); await r2.frame(page, 200); }
  r2.done(); await page.close();
  await b.close();
  console.log('frames written', fs.readdirSync(OUT).filter(f => f.endsWith('.png')).length);
})().catch(e => { console.error(e); process.exit(1); });
