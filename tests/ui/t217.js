// v7.12.12 Extra Settings tab and the Private DNS speed test: the tab is on the bar right after Hidden Settings, is a stack of independent sections (header card with chips, then Private DNS),
// the gear Settings page and the Hidden Settings tab point to it; the services are tiles driven by ONE array (15, no Mullvad); "Test speed" starts a native scan through
// AndroidBridge.dnsProbeStart (request: id, hosts, samples 3, timeoutMs 3000) and fills the tiles from window.onDnsProbe (answers in any order, a failed one, timeouts, Stop, a second scan while one
// runs, events of an old scan or an unknown host or broken JSON ignored, a native side that goes silent); the quickest working service is marked only when every answer is in; "Sort by speed"
// (off by default) orders the tiles; a tile that failed is not applied by one tap (a second tap does it); with no bridge method, a failing start, no working mode the test says so or still works;
// the layout at 360 / 412 / 320 px has no sideways scroll, no pill wraps, touch targets are 44 px, contrast reads in four colour schemes, German and Russian fit, reduced motion runs no animation.
const { chromium, PAGE } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
const dnsMock = require('./lib/dns_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 }, reducedMotion: opts.reduce ? 'reduce' : 'no-preference' });
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    if (opts.kv) await page.addInitScript(kv => { window.__kvInit = kv; }, opts.kv);
    if (opts.bridge !== false) { await page.addInitScript(mock.initScript); await page.addInitScript(dnsMock.initScript); }
    else await page.addInitScript(() => { window.AndroidBridge = undefined; });
    if (opts.init) await page.addInitScript(opts.init);
    await page.goto(PAGE); await page.waitForTimeout(500);
    return page;
  };
  const page = await mkPage({});
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = sel => ev(s => { const e = document.querySelector(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : null; }, sel);
  const open = async () => { await ev(() => switchView('extrasettings')); await sleep(250); };
  const row = id => ev(i => { const r = document.getElementById(i); return { cls: r.className.replace(/mode-action-btn|pdns-btn|pdns-tile|pdns-row/g, '').replace(/\s+/g, ' ').trim(), ms: r.querySelector('.pdns-ms').innerText, det: r.querySelector('.pdns-det').innerText, tag: r.querySelector('.pdns-tag').innerText.replace(/\s+/g, ' ').trim(), warn: r.querySelector('.pdns-warn').innerText.replace(/\s+/g, ' ').trim() }; }, id);
  const order = () => ev(() => [...document.querySelectorAll('#pdnsList .pdns-row')].map(r => r.dataset.host));
  const ops = () => ev(() => window.__calls.op.map(o => o.op + ' ' + o.key + (o.op === 'put' ? '=' + o.value : '')));
  const clearOps = () => ev(() => { window.__calls.op.length = 0; });
  const prog = () => txt('#pdnsProg');
  const start = async () => { await ev(() => document.getElementById('pdnsTest').click()); await sleep(40); };
  const reqs = () => ev(() => window.__dns.starts);
  const ok = (h, c, t, q, x) => ev(a => __dnsOk(a[0], a[1], a[2], a[3], a[4]), [h, c, t, q, x]);
  const fail = (h, s) => ev(a => __dnsFail(a[0], a[1]), [h, s]);
  const HOSTS = await ev(() => PDNS_PROVIDERS.map(s => s.host));

  // ---- 1. the tab ----
  const tabs = await ev(() => [...document.querySelectorAll('.tab-btn')].map(b => b.dataset.tab + '=' + b.innerText.replace(/\n/g, '|')));
  const i1 = tabs.findIndex(t => /^settings=/.test(t));
  check('1. the tab bar has "Extra Settings" (two lines) right after "Hidden Settings"', /^extrasettings=Extra\|Settings$/.test(tabs[i1 + 1] || '') && /^overlays=/.test(tabs[i1 + 2] || ''), JSON.stringify(tabs.slice(i1, i1 + 3)));
  await open();
  const shell = await ev(() => {
    const v = document.getElementById('view-extrasettings'), root = document.getElementById('extraSettingsRoot'), sec = document.getElementById('extraDnsSection');
    return {
      active: v.classList.contains('active'), cur: currentViewName(), rootIn: v.contains(root), secIn: root.contains(sec) && sec.tagName === 'SECTION' && sec.classList.contains('xs-sec'),
      kids: [...root.children].map(e => e.id || e.tagName).join(), header: document.querySelector('#extraSettingsHeader .color-card-title').innerText, sub: document.querySelector('#extraSettingsHeader .color-card-subtitle').innerText,
      helpQ: document.querySelector('#extraSettingsHeader .help-q') && document.querySelector('#extraSettingsHeader .help-q').dataset.help, cardIn: sec.contains(document.getElementById('privateDnsCard')),
      chip: document.getElementById('xsChipDns').innerText.replace(/\s+/g, ' ').trim(), chipTarget: document.getElementById('xsChipDns').dataset.target, fastChip: getComputedStyle(document.getElementById('xsChipFast')).display,
      guideTopic: typeof HELP_LINKS === 'object' && HELP_LINKS.extrasettings };
  });
  check('   the view is a vertical stack inside #extraSettingsRoot: the header card (title "Extra Settings", sentence, "?" to the Help Guide topic) and then the section #extraDnsSection with the Private DNS card inside it', shell.active && shell.cur === 'extrasettings' && shell.rootIn && shell.secIn && shell.kids.startsWith('extraSettingsHeader,extraDnsSection') && shell.header === 'Extra Settings' && shell.sub === 'Tweaks and controls that Android hides' && shell.helpQ === 'tab-extrasettings' && shell.cardIn && shell.guideTopic === 'tab-extrasettings', JSON.stringify(shell));
  check('   the header has the chip "Private DNS Automatic" (what the phone has, read back) that points at the DNS section; the "Fastest" chip waits for a test', shell.chip === 'Private DNS Automatic' && shell.chipTarget === 'extraDnsSection' && shell.fastChip === 'none', shell.chip);
  check('   the section head: icon, title, and ONE status pill ("Automatic") on one line', await ev(() => { const p = document.getElementById('pdnsPill'), h = document.querySelector('#privateDnsCard .xs-sec-head'); return p.innerText.replace(/\s+/g, ' ').trim() === '● Automatic' && p.offsetHeight <= 28 && !!h.querySelector('.xs-ico svg') && h.querySelectorAll('.xs-pill').length === 1; }));
  const guide = require('fs').readFileSync(require('path').join(__dirname, '..', '..', 'assets', 'guide.js'), 'utf8');
  check('   the Help Guide has the topic of the tab and the section, and the Private DNS section starts with where it is: the Extra Settings tab, not Hidden Settings', /id:\s*"tab-extrasettings"|"id":"tab-extrasettings"/.test(guide) && /Where it is:<\/b> the <b>Extra Settings<\/b> tab[^"]*?<b>not<\/b> in the <a href=\\?"#tab-settings\\?">Hidden Settings<\/a> tab/.test(guide), 'topic found: ' + /tab-extrasettings/.test(guide));
  // the pointers
  await ev(() => switchView('prefs')); await sleep(250);
  const gear = await ev(() => ({ pointer: !!document.getElementById('extraSettingsPointerCard') || !!document.getElementById('extraSettingsPointerBtn'), titles: [...document.querySelectorAll('#view-prefs .color-card-title')].map(t => t.textContent.trim()), noCard: !document.querySelector('#view-prefs #privateDnsCard') }));
  check('2. the gear Settings page has nothing about Private DNS any more (7.14: the pointer card is gone; Private DNS is only in Extra Settings)', !gear.pointer && gear.noCard && !gear.titles.some(t => /Private DNS/.test(t)), JSON.stringify(gear));
  await ev(() => switchView('settings')); await sleep(300);
  const hs = await ev(() => { const n = document.getElementById('sdbPrivateDnsPointer'); return { t: n.innerText.replace(/\s+/g, ' ').trim(), top: n.getBoundingClientRect().top, vis: n.offsetParent !== null }; });
  check('   the Hidden Settings tab says "Looking for Private DNS? It is not in this tab." with a button that opens the Extra Settings tab, near the top of the page', /^Looking for Private DNS\? It is not in this tab\. Open the Extra Settings tab$/.test(hs.t) && hs.vis && hs.top < 400, JSON.stringify(hs));
  await ev(() => document.querySelector('#sdbPrivateDnsPointer button').click()); await sleep(250);
  check('   and that button opens it', (await ev(() => currentViewName())) === 'extrasettings');
  const off = await mkPage({ kv: { tab_config: JSON.stringify({ order: ['saved-lists', 'debloater', 'installer', 'files', 'terminal', 'settings', 'extrasettings', 'overlays', 'store', 'logcat', 'taskmgr', 'devices', 'morphe', 'sdm'], off: ['extrasettings'] }) } });
  await off.evaluate(() => switchView('settings')); await off.waitForTimeout(300);
  await off.evaluate(() => document.querySelector('#sdbPrivateDnsPointer button').click()); await off.waitForTimeout(200);
  check('   with the tab switched off (Feature List) the Hidden Settings pointer does not open it and says where to turn it on', await off.evaluate(() => currentViewName() === 'settings' && /Extra Settings is turned off\. Turn it on in Settings, Feature List\./.test(document.getElementById('toastMsg').innerText)));
  await off.close();

  // ---- 2. the services: one array ----
  const svc = await ev(() => ({
    n: PDNS_PROVIDERS.length, ids: PDNS_PROVIDERS.map(s => s.id), tiles: [...document.querySelectorAll('#pdnsList .pdns-row')].map(r => r.dataset.host), names: [...document.querySelectorAll('#pdnsList .pdns-row b')].map(x => x.innerText),
    arr: PDNS_PROVIDERS.map(s => s.name), descs: PDNS_PROVIDERS.every(s => s.desc && s.desc.length < 80), mono: PDNS_PROVIDERS.every(s => s.mono && typeof s.hue === 'number'), uniq: new Set(PDNS_PROVIDERS.map(s => s.id)).size, ok: PDNS_PROVIDERS.every(s => pdnsCheckHost(s.host).ok) }));
  check('3. fifteen services in ONE array (id, name, host, one line, monogram, hue); the tiles are exactly the array, in its order; every host name passes the host check; none is Mullvad', svc.n === 15 && svc.uniq === 15 && svc.tiles.join() === HOSTS.join() && svc.names.join() === svc.arr.join() && svc.descs && svc.mono && svc.ok && !svc.ids.concat(HOSTS).some(x => /mullvad/i.test(x)), JSON.stringify(svc).slice(0, 300));
  check('   the list: Google dns.google, Cloudflare (3), Quad9 (2), AdGuard (3), OpenDNS, CleanBrowsing (3), NextDNS, DNS.SB', HOSTS.join() === 'dns.google,one.one.one.one,security.cloudflare-dns.com,family.cloudflare-dns.com,dns.quad9.net,dns10.quad9.net,dns.adguard-dns.com,family.adguard-dns.com,unfiltered.adguard-dns.com,dns.opendns.com,family-filter-dns.cleanbrowsing.org,adult-filter-dns.cleanbrowsing.org,security-filter-dns.cleanbrowsing.org,dns.nextdns.io,dot.sb', HOSTS.join());
  check('   OpenDNS says to check it with the speed test; NextDNS says a filter needs your own id under Another host name', await ev(() => /answers on some networks only/.test(document.querySelector('#pdnsOpendns .pdns-desc').innerText) && /own NextDNS id under Another host name/.test(document.querySelector('#pdnsNextdns .pdns-desc').innerText)));
  check('   the tiles: Automatic and Off keep their own two buttons', await ev(() => !!document.getElementById('pdnsAuto') && !!document.getElementById('pdnsOff') && document.querySelectorAll('#pdnsList .pdns-row').length === 15));

  // ---- 3. the speed test ----
  check('4. before a test: "Test speed" button, no Stop, no progress, the sentence about example.com and ICMP is there', (await txt('#pdnsTestLbl')) === 'Test speed' && (await ev(() => getComputedStyle(document.getElementById('pdnsTestStop')).display)) === 'none' && (await prog()) === '' && /^Your phone first looks up each service’s name with the DNS it uses now \(once\)\. Then each service may receive up to three short encrypted connections, each asking for example\.com\. ICMP ping is not available to apps, so the times are for the encrypted connection Private DNS actually uses\.$/.test(await txt('#pdnsTestNote')));
  await start();
  const r1 = (await reqs())[0];
  const startOk = (await reqs()).length === 1 && typeof r1.id === 'string' && r1.id.length > 3 && JSON.stringify(r1.hosts) === JSON.stringify(HOSTS) && r1.samples === 3 && r1.timeoutMs === 3000 && Object.keys(r1).sort().join() === 'hosts,id,samples,timeoutMs';
  check('   Start: one dnsProbeStart with { id, hosts (the 15, each once), samples 3, timeoutMs 3000 } and nothing else', startOk, startOk ? undefined : JSON.stringify(r1).slice(0, 200));
  check('   while it runs: "Testing… 0 of 15", a Stop button, the button says Testing…, every tile waits', (await prog()) === 'Testing… 0 of 15' && (await ev(() => getComputedStyle(document.getElementById('pdnsTestStop')).display)) !== 'none' && (await txt('#pdnsTestLbl')) === 'Testing…' && (await ev(() => document.querySelectorAll('#pdnsList .is-wait').length)) === 15);
  // results out of order, one failed, one slow
  await ok('dot.sb', 110, 240, 150);
  await ok('dns.google', 12, 30, 18);
  await fail('dns.opendns.com', 'timeout');
  await ok('one.one.one.one', 8, 20, 11);
  await sleep(50);
  const g = await row('pdnsGoogle'), cf = await row('pdnsCloudflare'), od = await row('pdnsOpendns'), sb = await row('pdnsDnssb');
  check('5. answers one by one, in any order: each tile shows the total in ms and connect / TLS / answer; "Testing… 4 of 15"', g.ms === '60 ms' && g.det === 'connect 12 · TLS 30 · answer 18 ms' && g.tag === '✓ Answers' && cf.ms === '39 ms' && sb.ms === '500 ms' && sb.det === 'connect 110 · TLS 240 · answer 150 ms' && (await prog()) === 'Testing… 4 of 15', JSON.stringify([g, cf, sb]));
  check('   a failed one: "✕ Timed out" and the sentence "Did not answer on your network. If you apply it, this phone may lose its internet; Automatic or Off is the way back."', od.tag === '✕ Timed out' && od.ms === '' && od.warn === 'Did not answer on your network. If you apply it, this phone may lose its internet; Automatic or Off is the way back.' && /is-fail/.test(od.cls), JSON.stringify(od));
  check('   no "fastest" yet (not every answer is in)', (await ev(() => document.querySelectorAll('#pdnsList .is-fastest').length)) === 0 && (await ev(() => getComputedStyle(document.getElementById('xsChipFast')).display)) === 'none');
  // junk that must be ignored
  await ev(() => { const id = window.__dns.starts[0].id; window.onDnsProbe('not json'); window.onDnsProbe('{"id":"' + id + '","host":"evil.example","status":"ok","connectMs":1,"tlsMs":1,"queryMs":1}'); window.onDnsProbe(JSON.stringify({ id: 'other', host: 'dns.google', status: 'timeout' })); window.onDnsProbe(null); window.onDnsProbe('{"id":"' + id + '","host":42}'); });
  const g2 = await row('pdnsGoogle');
  check('   broken JSON, a host that is not in the scan, another scan\'s id, null and a host that is not text: ignored (still 4 of 15, Google unchanged)', (await prog()) === 'Testing… 4 of 15' && g2.ms === '60 ms' && (await ev(() => Object.keys(pdnsScan.res).length)) === 4);
  // the same host twice: the later answer replaces the first and counts once
  await ok('dns.google', 14, 30, 18);
  check('   the same host answering twice counts once, the later answer wins', (await prog()) === 'Testing… 4 of 15' && (await row('pdnsGoogle')).ms === '62 ms');
  // all the others, then the final event
  const others = HOSTS.filter(h => !['dot.sb', 'dns.google', 'dns.opendns.com', 'one.one.one.one'].includes(h));
  for (const h of others.slice(0, -1)) await ok(h, 50, 70, 40);
  await fail(others[others.length - 1], 'connect');
  await sleep(60);
  check('6. when every answer is in the test ends by itself (no "done" event needed): the Stop button goes, "Done: 13 of 15 services answer. Fastest on this network: Cloudflare, 39 ms."', (await prog()) === 'Done: 13 of 15 services answer. Fastest on this network: Cloudflare, 39 ms.' && (await ev(() => getComputedStyle(document.getElementById('pdnsTestStop')).display)) === 'none' && (await txt('#pdnsTestLbl')) === 'Test speed', await prog());
  const fastest = await ev(() => [...document.querySelectorAll('#pdnsList .is-fastest')].map(r => r.id));
  const cf2 = await row('pdnsCloudflare');
  check('   exactly one "Fastest" pill, on the quickest working service (Cloudflare, 39 ms), with the full words as its title; the chip at the top of the tab says it too', fastest.join() === 'pdnsCloudflare' && cf2.tag === '» Fastest' && (await ev(() => document.querySelector('#pdnsCloudflare .pdns-tag').title)) === 'Fastest on this network' && (await txt('#xsChipFast')) === 'Fastest Cloudflare · 39 ms', JSON.stringify([fastest, cf2.tag, await txt('#xsChipFast')]));
  const conn = await row(await ev(h => document.querySelector('#pdnsList .pdns-row[data-host="' + h + '"]').id, others[others.length - 1]));
  check('   a failed row says why in a few words (✕ No answer on 853)', conn.tag === '✕ No answer on 853', conn.tag);
  await ev(() => document.getElementById('pdnsTest').click()); await sleep(40);
  await ev(() => { const id = window.__dns.starts[1].id; __dnsEmit({ id, host: 'dot.sb', status: 'ok', ip: '1.1.1.1', connectMs: 10, tlsMs: 20, queryMs: 15, samples: 3, okSamples: 2, error: '' }); });
  check('   a row that answered in 2 of 3 tries says "Only 2 of 3 tries answered."', (await row('pdnsDnssb')).warn === 'Only 2 of 3 tries answered.' && (await row('pdnsDnssb')).ms === '45 ms');
  // the new scan replaced the old one: old tiles cleared, old id ignored
  check('7. starting a new test while the old one is finished clears every old answer', (await ev(() => document.querySelectorAll('#pdnsList .is-ok').length)) === 1 && (await ev(() => document.querySelectorAll('#pdnsList .is-fail').length)) === 0 && (await ev(() => document.querySelectorAll('#pdnsList .is-fastest').length)) === 0 && (await prog()) === 'Testing… 1 of 15');
  // a late answer of the first scan
  await ev(() => { const old = window.__dns.starts[0].id; __dnsEmit({ id: old, host: 'dns.google', status: 'ok', connectMs: 1, tlsMs: 1, queryMs: 1, samples: 3, okSamples: 3 }); __dnsEmit({ id: old, done: true, count: 15 }); });
  check('   a late answer and a late "done" of the old test are ignored (the new one is still running, Google is not filled in)', (await prog()) === 'Testing… 1 of 15' && (await row('pdnsGoogle')).ms === '…' && (await ev(() => pdnsScan.running)) === true, JSON.stringify([await prog(), await row('pdnsGoogle'), await ev(() => pdnsScan.running)]));
  // a second start while this one runs
  await start();
  check('   pressing "Test speed" while a test runs starts over: a third request, nothing of the second is left', (await reqs()).length === 3 && (await prog()) === 'Testing… 0 of 15' && (await row('pdnsDnssb')).ms === '…', JSON.stringify([(await reqs()).length, await prog(), await row('pdnsDnssb')]));
  check('   the new request cancels the old running one natively too (dnsProbeCancel with the second id), after the new start went through', JSON.stringify(await ev(() => window.__dns.cancels)) === JSON.stringify([(await reqs())[1].id]));
  await ev(() => { const id = window.__dns.starts[1].id; __dnsEmit({ id, host: 'dns.google', status: 'ok', connectMs: 1, tlsMs: 1, queryMs: 1, samples: 3, okSamples: 3 }); });
  check('   an answer of the second request is ignored now', (await prog()) === 'Testing… 0 of 15');
  // stop
  await ok('dns.google', 12, 30, 18); await ok('one.one.one.one', 8, 20, 11);
  await ev(() => document.getElementById('pdnsTestStop').click()); await sleep(40);
  const cancels = await ev(() => window.__dns.cancels.slice());
  check('8. Stop: dnsProbeCancel is called with the current id (after the one of the replaced scan), the line says "Stopped after 2 of 15.", the Stop button goes, the tiles that were not reached say "Not tested"', cancels.length === 2 && cancels[1] === (await reqs())[2].id && (await prog()) === 'Stopped after 2 of 15.' && (await ev(() => getComputedStyle(document.getElementById('pdnsTestStop')).display)) === 'none' && (await row('pdnsAdguard')).tag === '– Not tested' && (await row('pdnsAdguard')).ms === '', JSON.stringify([cancels.length, await prog(), (await row('pdnsAdguard')).tag]));
  await ok('dns.quad9.net', 1, 1, 1); await ev(() => { __dnsEmit({ id: window.__dns.starts[2].id, done: true, count: 15 }); });
  check('   what arrives after Stop is ignored, and nothing is marked fastest after a stop', (await prog()) === 'Stopped after 2 of 15.' && (await row('pdnsQuad9')).ms === '' && (await ev(() => document.querySelectorAll('#pdnsList .is-fastest').length)) === 0);
  // your own host name
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'My-Own.Example.Net.'; i.dispatchEvent(new Event('input')); });
  await start();
  const r4 = (await reqs())[3];
  check('9. a host name typed in the field is tested too (cleaned up, last in the list, once)', JSON.stringify(r4.hosts) === JSON.stringify(HOSTS.concat(['my-own.example.net'])), JSON.stringify(r4.hosts.slice(-2)));
  const extraTile = await ev(() => { const r = document.querySelector('#pdnsList .pdns-row[data-svc="custom"]'); return r && { id: r.id, name: r.querySelector('b').innerText, host: r.querySelector('.pdns-hostline').innerText, last: r === document.querySelector('#pdnsList .pdns-row:last-child') }; });
  check('   it shows as the last tile "Your host name" with the host under it', !!extraTile && extraTile.name === 'Your host name' && extraTile.host === 'my-own.example.net' && extraTile.last, JSON.stringify(extraTile));
  await ev(() => { __dnsOk('my-own.example.net', 5, 9, 4); });
  check('   and it answers like the others (18 ms here)', (await row('pdnsCustom')).ms === '18 ms');
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'second-own.example.net'; i.dispatchEvent(new Event('input')); });
  await start();
  await ev(() => { __dnsOk('second-own.example.net', 7, 7, 7); });
  const c2 = await ev(() => { const r = document.querySelector('#pdnsList .pdns-row[data-svc="custom"]'); return r && { host: r.dataset.host, line: r.querySelector('.pdns-hostline').innerText, ms: r.querySelector('.pdns-ms').innerText, n: document.querySelectorAll('#pdnsList .pdns-row[data-svc="custom"]').length }; });
  check('   a second own name tested right after the first: the "Your host name" tile is for the new name (host, text, answer), still one tile', !!c2 && c2.host === 'second-own.example.net' && c2.line === 'second-own.example.net' && c2.ms === '21 ms' && c2.n === 1, JSON.stringify(c2));
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = '1.1.1.1'; i.dispatchEvent(new Event('input')); });
  await start();
  check('   a name the check refuses (an IP address) is not sent to the test; a name that is in the list is not sent twice', JSON.stringify((await reqs())[5].hosts) === JSON.stringify(HOSTS) && (await ev(() => !document.querySelector('#pdnsList .pdns-row[data-svc="custom"]'))));
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'dns.google'; i.dispatchEvent(new Event('input')); });
  await start();
  check('   (dns.google typed: tested once)', JSON.stringify((await reqs())[6].hosts) === JSON.stringify(HOSTS));
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = ''; i.dispatchEvent(new Event('input')); });

  // ---- sort by speed ----
  await start();
  const speeds = { 'dns.google': [12, 30, 18], 'one.one.one.one': [8, 20, 11], 'dns.quad9.net': [30, 40, 20], 'dns.adguard-dns.com': [9, 20, 11] };
  for (const [h, t] of Object.entries(speeds)) await ok(h, t[0], t[1], t[2]);
  await fail('dns.opendns.com', 'dns');
  await fail('dot.sb', 'tls');
  const swDefault = await ev(() => document.getElementById('pdnsSort').checked);
  check('10. "Sort by speed" is off by default, and with it off the tiles keep the array order whatever the answers are', swDefault === false && (await order()).join() === HOSTS.join());
  await ev(() => document.getElementById('pdnsSort').click()); await sleep(40);
  const o1 = await order();
  check('   on: the ones that answered, quickest first (Cloudflare 39, AdGuard 40, Google 60, Quad9 90), then the ones not tested yet in array order, then the ones that failed (OpenDNS, DNS.SB)', o1.slice(0, 4).join() === 'one.one.one.one,dns.adguard-dns.com,dns.google,dns.quad9.net' && o1.slice(-2).join() === 'dns.opendns.com,dot.sb' && o1.slice(4, -2).join() === HOSTS.filter(h => !(h in speeds) && h !== 'dns.opendns.com' && h !== 'dot.sb').join(), o1.join());
  await ok('dns.nextdns.io', 1, 1, 1);
  check('   an answer that comes in later moves into place at once (NextDNS, 3 ms, first)', (await order())[0] === 'dns.nextdns.io');
  await ev(() => document.getElementById('pdnsSort').click()); await sleep(40);
  check('   off again: the array order', (await order()).join() === HOSTS.join());
  await ev(() => document.getElementById('pdnsSort').click());
  const focusKept = await ev(() => { document.getElementById('pdnsGoogle').focus(); pdnsSortChange(true); return document.activeElement.id; });
  check('   sorting moves the tiles, it does not rebuild them: a focused tile stays focused', focusKept === 'pdnsGoogle');
  await ev(() => document.getElementById('pdnsSort').click());
  await ev(() => document.getElementById('pdnsTestStop').click());

  // ---- a failed tile is not applied by one tap ----
  await ev(() => { window.__db.global.private_dns_mode = 'opportunistic'; delete window.__db.global.private_dns_specifier; });
  await start();
  await ok('dns.google', 12, 30, 18); await fail('dns.opendns.com', 'timeout'); await fail('dns.quad9.net', 'tls');
  await ev(() => __dnsEmit({ id: window.__dns.starts[window.__dns.starts.length - 1].id, host: 'dns.nextdns.io', status: 'dns', ip: '', connectMs: null, tlsMs: null, queryMs: null, samples: 3, okSamples: 0, error: 'resolver busy' }));
  await clearOps();
  await ev(() => document.getElementById('pdnsOpendns').click()); await sleep(80);
  const armed = await ev(() => ({ ops: window.__calls.op.length, cls: document.getElementById('pdnsOpendns').className, warn: document.querySelector('#pdnsOpendns .pdns-warn').innerText.replace(/\s+/g, ' ').trim(), msg: document.getElementById('pdnsMsg').innerText.replace(/\s+/g, ' ').trim(), live: document.getElementById('pdnsStatus').getAttribute('aria-live') }));
  check('11. tapping a tile that failed does NOT apply it: nothing is sent to the phone; the tile is marked and says "Tap again to apply it anyway."; the live status line says the same with the warning', armed.ops === 0 && /is-armed/.test(armed.cls) && /Tap again to apply it anyway\.$/.test(armed.warn) && armed.msg === 'Did not answer on your network. If you apply it, this phone may lose its internet; Automatic or Off is the way back. Tap again to apply it anyway.' && armed.live === 'polite', JSON.stringify(armed));
  await ev(() => document.getElementById('pdnsQuad9').click()); await sleep(80);
  check('   tapping another failed tile first arms that one instead (still nothing sent, the first is not armed any more)', (await ops()).length === 0 && (await ev(() => /is-armed/.test(document.getElementById('pdnsQuad9').className) && !/is-armed/.test(document.getElementById('pdnsOpendns').className))));
  await ev(() => document.getElementById('pdnsOpendns').click()); await sleep(60);
  await ev(() => document.getElementById('pdnsOpendns').click()); await sleep(80);
  await page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  check('   the second tap on the same tile applies it (host first, then mode, read back), the arm is gone', (await ops()).slice(0, 2).join() === 'put private_dns_specifier=dns.opendns.com,put private_dns_mode=hostname' && (await ev(() => !pdnsArmed)) && (await txt('#pdnsNow')) === 'Private DNS is now: hostname dns.opendns.com', JSON.stringify(await ops()));
  await clearOps();
  await ev(() => document.getElementById('pdnsGoogle').click()); await sleep(60); await page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  check('   a tile that answered (or was never tested) is applied by ONE tap, and the tile that matches the phone is marked', (await ops()).slice(0, 2).join() === 'put private_dns_specifier=dns.google,put private_dns_mode=hostname' && (await ev(() => document.getElementById('pdnsGoogle').getAttribute('aria-pressed'))) === 'true');
  await ev(() => __dnsEmit({ id: window.__dns.starts[window.__dns.starts.length - 1].id, host: 'dot.sb', status: 'dns', ip: '', connectMs: null, tlsMs: null, queryMs: null, samples: 3, okSamples: 0, error: 'name lookup timed out' }));
  check('   a name lookup that timed out on the phone (status dns, error "name lookup timed out") reads the same way: "… Couldn’t test now", not "Name not found", no second tap', (await row('pdnsDnssb')).tag === '… Couldn’t test now' && !/is-fail/.test((await row('pdnsDnssb')).cls));
  const busy = await row('pdnsNextdns');
  check('   a name the app could not look up because its resolver was busy (status dns, error "resolver busy") is not a failure of the service: "… Couldn’t test now" and "Couldn’t test right now, try again.", not marked failed, no warning about losing the internet', busy.tag === '… Couldn’t test now' && busy.warn === 'Couldn’t test right now, try again.' && !/is-fail/.test(busy.cls), JSON.stringify(busy));
  await clearOps();
  await ev(() => document.getElementById('pdnsNextdns').click()); await sleep(60); await page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  check('   and tapping it applies at once (nothing was learned against it)', (await ops()).slice(0, 2).join() === 'put private_dns_specifier=dns.nextdns.io,put private_dns_mode=hostname');
  await clearOps();
  await ev(() => document.getElementById('pdnsOpendns').click()); await sleep(60);
  await ev(() => document.getElementById('pdnsAuto').click()); await sleep(60); await page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  check('   Automatic / Off are never asked twice, and they clear a waiting second tap', (await ops())[0] === 'put private_dns_mode=opportunistic' && (await ev(() => !pdnsArmed && !document.querySelector('#pdnsList .is-armed'))));
  // the typed host: press Apply twice
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'dns.opendns.com'; i.dispatchEvent(new Event('input')); }); await clearOps();
  await ev(() => document.getElementById('pdnsApply').click()); await sleep(60);
  const ap1 = await txt('#pdnsProblem');
  check('   Apply with a typed name that failed the test: the first press only warns beside the field, the second applies it', (await ops()).length === 0 && /Tap again to apply it anyway\.$/.test(ap1));
  await ev(() => document.getElementById('pdnsApply').click()); await sleep(60); await page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  check('   (the second press: applied, the warning gone)', (await ops()).slice(0, 2).join() === 'put private_dns_specifier=dns.opendns.com,put private_dns_mode=hostname' && (await txt('#pdnsProblem')) === '');
  // typing in the field cancels the waiting second press
  await ev(() => { window.__db.global.private_dns_mode = 'opportunistic'; });
  await clearOps();
  await ev(() => document.getElementById('pdnsApply').click()); await sleep(40);
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'dns.opendns.co'; i.dispatchEvent(new Event('input')); });
  check('   typing in the field takes the waiting second press back', await ev(() => !pdnsArmed) && (await txt('#pdnsProblem')) === '');
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = ''; i.dispatchEvent(new Event('input')); });
  // leaving the tab stops a test
  await start();
  await ev(() => switchView('apps')); await sleep(60);
  const leave = await ev(() => ({ running: pdnsScan.running, stopped: pdnsScan.stopped, cancels: window.__dns.cancels.length }));
  check('12. leaving the tab during a test stops it (dnsProbeCancel is called)', !leave.running && leave.stopped && leave.cancels >= 2, JSON.stringify(leave));
  await open();
  check('   coming back shows what was found, and the line says it was stopped', /^Stopped after \d+ of 15\.$/.test(await prog()));

  // ---- a native side that goes silent ----
  await ev(() => { PDNS_SILENCE_MS = 300; });
  const cBefore = await ev(() => window.__dns.cancels.length);
  await start();
  await ok('dns.google', 1, 1, 1);
  for (let k = 0; k < 6; k++) { await ev(() => { const id = window.__dns.starts[window.__dns.starts.length - 1].id; window.onDnsProbe(JSON.stringify({ id, host: 'unknown.example', status: 'ok', connectMs: 1, tlsMs: 1, queryMs: 1 })); window.onDnsProbe(JSON.stringify({ id, host: 7, status: 'ok' })); }); await sleep(100); }
  await sleep(300);
  check('13. a test that hears nothing useful for a while is given up (events for an unknown host or a host that is not text do not keep it alive): dnsProbeCancel is called, "The speed test stopped answering. Try it again.", the Stop button goes', (await prog()) === 'The speed test stopped answering. Try it again.' && (await ev(() => window.__dns.cancels.length)) === cBefore + 1 && (await ev(() => getComputedStyle(document.getElementById('pdnsTestStop')).display)) === 'none', await prog());
  await ev(() => { PDNS_SILENCE_MS = 60000; });
  // start fails
  await ev(() => { window.__dns.startReply = JSON.stringify({ ok: false, error: 'too many hosts' }); });
  await start();
  check('14. dnsProbeStart answering { ok: false, error } is said: "The speed test could not start: too many hosts", nothing runs', (await prog()) === 'The speed test could not start: too many hosts' && (await ev(() => !pdnsScan || !pdnsScan.running)));
  await ev(() => { window.__dns.startReply = null; window.__dns.throwOnStart = true; });
  await start();
  check('   dnsProbeStart throwing is said too, no page error', /^The speed test could not start: boom$/.test(await prog()));
  await ev(() => { window.__dns.throwOnStart = false; window.__dns.startReply = 'garbage'; });
  await start();
  check('   an answer that is not JSON counts as a failure to start', /^The speed test could not start: /.test(await prog()));
  await ev(() => { window.__dns.startReply = null; });
  // an old build
  await ev(() => { delete window.AndroidBridge.dnsProbeStart; });
  await start();
  check('15. an old build (no dnsProbeStart): "This version of the app cannot run the speed test. Update the app."', (await prog()) === 'This version of the app cannot run the speed test. Update the app.');
  await page.close();

  // ---- no working mode: the test still works, applying does not ----
  const pn = await mkPage({ init: () => { window.__mode.priv = false; } });
  const en = (fn, a) => pn.evaluate(fn, a);
  await en(() => switchView('extrasettings')); await pn.waitForTimeout(300);
  await en(() => document.getElementById('pdnsTest').click()); await pn.waitForTimeout(40);
  await en(() => { __dnsOk('dns.google', 10, 20, 10); });
  const lock = await en(() => ({ pill: document.getElementById('pdnsPill').innerText.replace(/\s+/g, ' ').trim(), chip: document.getElementById('xsChipDns').innerText.replace(/\s+/g, ' ').trim(), ms: document.querySelector('#pdnsGoogle .pdns-ms').innerText, aria: document.getElementById('pdnsGoogle').getAttribute('aria-disabled'), setup: getComputedStyle(document.getElementById('pdnsSetup')).display, now: document.getElementById('pdnsNow').innerText }));
  check('16. no working mode: the speed test runs and fills the tiles; the pill says "Viewing only" (lock), the tiles are aria-disabled, the card offers Set Up and says it needs a working mode', lock.ms === '40 ms' && /Viewing only/.test(lock.pill) && lock.chip === 'Private DNS Viewing only' && lock.aria === 'true' && lock.setup !== 'none' && /^Needs a working mode/.test(lock.now), JSON.stringify(lock));
  await en(() => document.getElementById('pdnsGoogle').click()); await pn.waitForTimeout(100);
  check('   tapping a tile still opens the "requires working mode" sheet and sends nothing', await en(() => document.getElementById('privilegeModal').classList.contains('show') && window.__calls.op.length === 0));
  await pn.close();
  // no bridge at all (a browser)
  const pb = await mkPage({ bridge: false });
  await pb.evaluate(() => switchView('extrasettings')); await pb.waitForTimeout(250);
  await pb.evaluate(() => document.getElementById('pdnsTest').click()); await pb.waitForTimeout(40);
  check('   in a browser (no bridge): the test button says "This works in the app on a phone, not in a browser." and nothing breaks', (await pb.evaluate(() => document.getElementById('pdnsProg').innerText)) === 'This works in the app on a phone, not in a browser.');
  await pb.close();

  // ---- layout, in every width ----
  const probe = async (w, h, setup, label, lang) => {
    const p = await mkPage({ w, h });
    const e = (fn, a) => p.evaluate(fn, a);
    await e(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important;animation:none!important}'; document.head.appendChild(st); });
    if (lang) { await e(c => LANG_ENGINE.set(c), lang); await p.waitForTimeout(400); }
    await e(() => switchView('extrasettings')); await p.waitForTimeout(300);
    await e(() => { document.getElementById('pdnsMore').open = true; document.getElementById('pdnsTest').click(); });
    await e(h => { PDNS_PROVIDERS.forEach((s, i) => { if (i === 3) __dnsFail(s.host, 'timeout'); else if (i === 7) __dnsFail(s.host, 'connect'); else if (i === 12) __dnsOk(s.host, 321, 654, 987, { okSamples: 1 }); else __dnsOk(s.host, 5 + i, 10 + i, 7 + i); }); }, h);
    await p.waitForTimeout(150);
    const r = await e(() => {
      const de = document.documentElement, root = document.getElementById('extraSettingsRoot'), rr = root.getBoundingClientRect();
      const wrap = [...document.querySelectorAll('#extraSettingsRoot .xs-pill, #extraSettingsRoot .xs-chip, #privateDnsCard .pdns-tag, #privateDnsCard .pdns-ms, #privateDnsCard .xs-btn, #privateDnsCard .xs-btn')].filter(x => x.offsetParent !== null).filter(x => {
        const cs = getComputedStyle(x); const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.3; return x.getBoundingClientRect().height > lh * 2.3 + parseFloat(cs.paddingTop) * 2; }).map(x => x.id || x.className);
      const tall = [...document.querySelectorAll('#extraSettingsRoot .xs-pill, #privateDnsCard .pdns-tag')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().height > 30).map(x => x.id || x.innerText);
      const out = [...document.querySelectorAll('#extraSettingsRoot *')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0 && (x.getBoundingClientRect().right > rr.right + 0.5 || x.getBoundingClientRect().left < rr.left - 0.5) && !x.closest('.switch-row') && x.tagName !== 'INPUT').map(x => x.id || x.className || x.tagName).slice(0, 5);
      const small = [...document.querySelectorAll('#extraSettingsRoot .xs-chip, #extraSettingsRoot .xs-btn, #privateDnsCard .pdns-btn, #privateDnsCard .xs-btn, #pdnsApply, #pdnsRefresh, #pdnsHost, #pdnsMore summary')].filter(x => x.offsetParent !== null && x.getBoundingClientRect().height < 43.5).map(x => (x.id || x.className) + ':' + Math.round(x.getBoundingClientRect().height));
      const clip = [...document.querySelectorAll('#privateDnsCard .pdns-tile')].filter(x => x.scrollWidth > x.clientWidth + 1).map(x => x.id);
      const cols = getComputedStyle(document.getElementById('pdnsList')).gridTemplateColumns.split(' ').length;
      return { page: de.scrollWidth <= window.innerWidth, wrap, tall, out, small, clip, cols, w: Math.round(rr.width), tiles: document.querySelectorAll('#pdnsList .pdns-tile').length };
    });
    await p.close();
    return Object.assign({ label }, r);
  };
  for (const [w, h] of [[360, 740], [412, 860], [320, 640], [768, 900]]) {
    const r = await probe(w, h, null, w + ' px');
    check('17. [' + w + ' px] the whole tab: no sideways scroll, nothing outside the column, no pill / chip / tag wraps, every control at least 44 px tall, no tile content cut, ' + r.cols + ' tile column(s) (2 on a phone, more on a tablet)', r.page && r.out.length === 0 && r.wrap.length === 0 && r.tall.length === 0 && r.small.length === 0 && r.clip.length === 0 && (w < 640 ? r.cols === 2 : r.cols >= 3) && r.tiles === 15, JSON.stringify(r));
  }
  for (const [code, label] of [['de', 'German'], ['ru', 'Russian']]) {
    const r = await probe(360, 740, 'window.__lang', label, code);
    check('18. [360 px, ' + label + '] long translations: no sideways scroll, nothing outside the column, no pill wraps, targets 44 px, no tile cut', r.page && r.out.length === 0 && r.wrap.length === 0 && r.tall.length === 0 && r.small.length === 0 && r.clip.length === 0, JSON.stringify(r));
  }

  // ---- colours and motion ----
  const p4 = await mkPage({ w: 360, h: 800, reduce: true });
  const e4 = (fn, a) => p4.evaluate(fn, a);
  await e4(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });
  await e4(() => switchView('extrasettings')); await p4.waitForTimeout(300);
  await e4(() => { document.getElementById('pdnsTest').click(); PDNS_PROVIDERS.forEach((s, i) => { if (i === 3) __dnsFail(s.host, 'timeout'); else __dnsOk(s.host, 5 + i, 10 + i, 7 + i); }); });
  const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
  const cr = (a, b2) => { const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  for (const [mode, preset] of [['dark', 'amoled'], ['light', 'amoled'], ['dark', 'sunset'], ['light', 'sunset']]) {
    await e4(a => { applyPalettePreset(a[1]); setAppearance(a[0]); }, [mode, preset]); await p4.waitForTimeout(150);
    const sample = () => e4(() => {
      const parse = s => { const m = s.match(/[\d.]+/g) || []; const k = /^color\(/.test(s) ? 255 : 1; return { n: m.slice(0, 3).map(x => Math.round(+x * k)), a: m.length >= 4 ? +m[3] : 1 }; };         // rgb(...) or color(srgb 0.9 0.9 0.9 / a)
      const rgb = e => parse(getComputedStyle(e).color).n;
      const bg = e => { for (let n = e; n; n = n.parentElement) { const c = parse(getComputedStyle(n).backgroundColor); if (c.a >= 0.5 && getComputedStyle(n).backgroundColor !== 'rgba(0, 0, 0, 0)') return c.n; } return [0, 0, 0]; };
      const q = s => document.querySelector(s);
      const ok = q('#pdnsGoogle'), fail = q('#pdnsCloudflareFamily'), fast = q('#pdnsGoogle');
      const items = {
        name: [rgb(q('#pdnsGoogle b')), bg(q('#pdnsGoogle'))], host: [rgb(q('#pdnsGoogle .pdns-hostline')), bg(q('#pdnsGoogle'))], desc: [rgb(q('#pdnsGoogle .pdns-desc')), bg(q('#pdnsGoogle'))], ms: [rgb(q('#pdnsGoogle .pdns-ms')), bg(q('#pdnsGoogle'))],
        okTag: [rgb(q('#pdnsCloudflare .pdns-tag')), bg(q('#pdnsCloudflare .pdns-tag'))], failTag: [rgb(fail.querySelector('.pdns-tag')), bg(fail.querySelector('.pdns-tag'))], warn: [rgb(fail.querySelector('.pdns-warn')), bg(fail)],
        pill: [rgb(q('#pdnsPill')), bg(q('#pdnsPill'))], chipK: [rgb(q('.xs-chip-k')), bg(q('.xs-chip'))], chipV: [rgb(q('.xs-chip-v')), bg(q('.xs-chip'))], sub: [rgb(q('#extraSettingsHeader .color-card-subtitle')), bg(q('#extraSettingsHeader'))],
        mono: [rgb(q('#pdnsGoogle .pdns-mono')), bg(q('#pdnsGoogle .pdns-mono'))], trust: [rgb(q('.xs-trust span')), bg(q('#privateDnsCard'))], more: [rgb(q('#pdnsMore summary')), bg(q('#pdnsMore'))], note: [rgb(q('#pdnsTestNote')), bg(q('#privateDnsCard'))],
        };
      const pl = q('#pdnsPill'), keep = pl.className;
      ['is-ok', 'is-warn', 'is-bad'].forEach(c => { pl.className = 'xs-pill ' + c; items['pill ' + c] = [rgb(pl), bg(pl)]; });
      pl.className = keep;
      const hex = s => { s = s.trim().replace('#', ''); if (s.length === 3) s = s.split('').map(c => c + c).join(''); return [0, 2, 4].map(i => parseInt(s.slice(i, i + 2), 16)); };
      const cs0 = getComputedStyle(document.documentElement), ft = q('#pdnsGoogle .pdns-tag'), tb = q('#pdnsGoogle .pdns-tag').closest('.pdns-tile');
      if (tb.classList.contains('is-fastest')) { items['fastest pill on accent'] = [rgb(ft), hex(cs0.getPropertyValue('--accent'))]; items['test button on accent'] = [rgb(q('#pdnsTest')), hex(cs0.getPropertyValue('--accent'))]; }
      else items['fastest pill missing'] = [[0, 0, 0], [0, 0, 0]];
      return items;
    });
    let colours = await sample(), same = 0;
    for (let i = 0; i < 40 && same < 2; i++) { await p4.waitForTimeout(100); const nx = await sample(); same = JSON.stringify(nx) === JSON.stringify(colours) ? same + 1 : 0; colours = nx; }
    const bad2 = [];
    Object.entries(colours).forEach(([k, v]) => { const c = cr(v[0], v[1]); const need = 4.5; if (c < need) bad2.push(k + ' ' + c.toFixed(2)); });
    check('19. ' + mode + ' / ' + preset + ': every text of the new tab (name, host, line, ms, tags, warning, pill, chips, header sentence, trust line, note, monogram) reads at contrast 4.5 or more', same >= 2 && bad2.length === 0, same < 2 ? 'colours did not settle' : bad2.join(', '));
  }
  await p4.close();
  const p5 = await mkPage({ w: 360, h: 800, reduce: true });
  await p5.evaluate(() => switchView('extrasettings')); await p5.waitForTimeout(300);
  await p5.evaluate(() => { document.getElementById('pdnsTest').click(); });
  const mo = await p5.evaluate(() => [...document.querySelectorAll('#extraSettingsRoot *')].filter(e => { const cs = getComputedStyle(e); return cs.animationName !== 'none' || (e.matches('.xs-chip, .xs-btn, .pdns-sweep, .xs-more summary') && parseFloat(cs.transitionDuration) > 0); }).map(e => (e.id || e.className) + ' ' + getComputedStyle(e).animationName + ' ' + getComputedStyle(e).transitionDuration).slice(0, 6));
  check('   (checked on a page without the forced rule) reduced motion: no animation anywhere in the tab and none of the own transitions of the tab (chips, buttons, progress bar, note arrow), also while a test runs', mo.length === 0, JSON.stringify(mo));
  await p5.close();
  const p6 = await mkPage({ w: 360, h: 800 });
  await p6.evaluate(() => switchView('extrasettings')); await p6.waitForTimeout(300);
  await p6.evaluate(() => { document.getElementById('pdnsTest').click(); });
  const mo2 = await p6.evaluate(() => [...document.querySelectorAll('#pdnsList .pdns-tile.is-wait .pdns-mono')].some(e => getComputedStyle(e).animationName === 'pdnsPulse') && getComputedStyle(document.getElementById('pdnsSweep')).transitionDuration !== '0s');
  check('   with motion allowed, a waiting tile pulses softly and the button\'s progress bar slides', mo2 === true);
  await p6.evaluate(() => { window.__dnsOk('dns.google', 1, 1, 1); });
  const sweep = await p6.evaluate(() => document.getElementById('pdnsSweep').style.width);
  check('   the progress bar of the button follows the count (1 of 15 = 7%)', sweep === '7%', sweep);
  await p6.close();

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all ok');
  process.exit(bad ? 1 : 0);
})();
