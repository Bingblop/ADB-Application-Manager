// Settings, "Private DNS": a card that sets the phone's private_dns_mode / private_dns_specifier through the Hidden Settings tab's own path (AndroidBridge.settingsOp, nothing new).
// Presets AdGuard DNS, Cloudflare, Quad9, Automatic, Off and a host name of your own. The host name check is pure and is run on hostile text (shell characters, IP addresses, look-alike
// letters, over-long names). What the card reports comes from reading the settings back (the host is written before the mode; a refused write stops the change; a write that the phone
// does not keep is reported as such); buttons are off while a change is out; an answer to an old question is dropped; no working mode and no bridge are explained, not hidden.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
const mock = require('./lib/sdb_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? 'ok   ' : 'FAIL ') + label + (ok && extra === undefined ? '' : ': ' + (extra === undefined ? ok : extra))); }

// ---- the host name rules: [input, expected host or the reason it is refused] ----
const GOOD = [
  ['dns.adguard-dns.com', 'dns.adguard-dns.com'], ['one.one.one.one', 'one.one.one.one'], ['dns.quad9.net', 'dns.quad9.net'], ['dns.nextdns.io', 'dns.nextdns.io'],
  ['DNS.AdGuard-DNS.COM', 'dns.adguard-dns.com'], ['  dns.adguard-dns.com  ', 'dns.adguard-dns.com'], ['\tdns.adguard-dns.com\t', 'dns.adguard-dns.com'], ['dns.adguard-dns.com.', 'dns.adguard-dns.com'], ['dns.adguard-dns.com. ', 'dns.adguard-dns.com'],
  ['a.b', 'a.b'], ['a-b.c-d.example', 'a-b.c-d.example'], ['xn--bcher-kva.example', 'xn--bcher-kva.example'], ['1dns.example.com', '1dns.example.com'],
  ['abc123.def456.ghi', 'abc123.def456.ghi'], ['x'.repeat(63) + '.example', 'x'.repeat(63) + '.example'], ['a.' + 'b'.repeat(63) + '.com', 'a.' + 'b'.repeat(63) + '.com'],
  ['dns.example.0x1', 'dns.example.0x1'], ['12abc.34def.example', '12abc.34def.example'],
  [(('a'.repeat(61) + '.').repeat(3) + 'a'.repeat(61)), (('a'.repeat(61) + '.').repeat(3) + 'a'.repeat(61))],            // 4 x 61 + 3 dots = 247
  [(('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(61)), (('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(61))],            // exactly 253
];
const BAD = [
  ['', 'empty'], ['   ', 'empty'], ['\t', 'empty'], ['.', 'empty'], [null, 'empty'], [undefined, 'empty'], [42, 'empty'],
  ['dns', 'onedot'], ['localhost', 'onedot'], ['dns-only-label', 'onedot'],
  ['1.1.1.1', 'ip'], ['8.8.8.8', 'ip'], ['255.255.255.255', 'ip'], ['999.999.999.999', 'ip'], ['1.1.1.1.', 'ip'], ['1.2.3', 'ip'], ['1.2.3.4.5', 'ip'], ['0x7f.0x0.0x0.0x1', 'ip'], ['0x7f.1', 'ip'], ['01.02.03.04', 'ip'], ['127.1', 'ip'],
  ['2606:4700:4700::1111', 'chars'], ['[2606:4700:4700::1111]', 'chars'], ['::1', 'chars'], ['1.1.1.1:853', 'chars'],
  ['dns.example.1', 'tld'], ['dns.example.123', 'tld'],
  ['dns adguard.com', 'chars'], ['dns.adguard-dns.com extra', 'chars'], ['dns.adguard-dns.com\n', 'chars'], ['dns.adguard-dns.com\r\n', 'chars'], ['dns.adguard-dns.com\nb.example', 'chars'], ['\ndns.adguard-dns.com', 'chars'],
  ['dns.adguard-dns.com ', 'chars'], ['dns.adguard-dns.com​', 'chars'], [' dns.adguard-dns.com', 'chars'], ['dns.adguard-dns.com\u0000', 'chars'],
  ['dns.adguard-dns.com;reboot', 'chars'], ['dns.adguard-dns.com; reboot', 'chars'], ["dns.adguard-dns.com'", 'chars'], ["'dns.adguard-dns.com'", 'chars'], ['"dns.adguard-dns.com"', 'chars'], ['dns.adguard-dns.com`id`', 'chars'],
  ['$(id).example.com', 'chars'], ['dns.$HOME.com', 'chars'], ['${IFS}.example.com', 'chars'], ['a.com|b.com', 'chars'], ['a.com&b.com', 'chars'], ['a.com>b', 'chars'], ['a.com\\b', 'chars'], ['a.com/b', 'chars'],
  ['https://dns.adguard-dns.com', 'chars'], ['dns.adguard-dns.com/dns-query', 'chars'], ['user@dns.adguard-dns.com', 'chars'], ['dns.adguard-dns.com:853', 'chars'], ['*.adguard.com', 'chars'], ['dns_adguard.com', 'chars'], ['dns.adguard-dns.com?x=1', 'chars'],
  ['dns.adguard-dns.com%00', 'chars'], ['dns.adguard-dns.com#x', 'chars'], ['(dns).adguard.com', 'chars'], ['dns,adguard.com', 'chars'], ['dns.adguard-dns.com=1', 'chars'], ['-rf.example.com', 'hyphen'],
  ['dns.аdguard.com', 'chars'],              // Cyrillic a
  ['dns.adguard.cοm', 'chars'],              // Greek omicron
  ['ｄns.adguard.com', 'chars'],              // fullwidth d
  ['dns。adguard。com', 'chars'],            // ideographic full stop
  ['dns．adguard．com', 'chars'],            // fullwidth full stop
  ['dns․adguard․com', 'chars'],    // one dot leader
  ['bücher.example', 'chars'], ['naïve.example.com', 'chars'], ['straße.example', 'chars'], ['日本語.jp', 'chars'], ['dns.adguard-dns.com‮', 'chars'], ['﻿dns.adguard-dns.com', 'chars'], ['\u212a.example', 'chars'], ['\u0131.example', 'chars'],
  ['a..com', 'dots'], ['.com', 'dots'], ['.dns.adguard-dns.com', 'dots'], ['dns.adguard-dns.com..', 'dots'], ['dns..adguard.com', 'dots'], 
  ['-dns.adguard-dns.com', 'hyphen'], ['dns-.adguard.com', 'hyphen'], ['dns.-adguard.com', 'hyphen'], ['dns.adguard-.com', 'hyphen'], ['dns.adguard.-com', 'hyphen'], ['dns.adguard-dns.com-', 'hyphen'], ['-', 'hyphen'], ['a', 'onedot'],
  ['x'.repeat(64) + '.example', 'label'], ['a.' + 'b'.repeat(64) + '.com', 'label'], ['a.b.' + 'c'.repeat(100), 'label'],
  [('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(62), 'long'], [('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(63), 'long'], [('abcd.').repeat(60) + 'com', 'long'],
];

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const mkPage = async (opts) => {
    opts = opts || {};
    const page = await b.newPage({ viewport: { width: opts.w || 400, height: opts.h || 860 }, reducedMotion: opts.reduce ? 'reduce' : 'no-preference' });
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });   // (the page's template images the browser cannot load from a file are not ours)
    if (opts.bridge !== false) await page.addInitScript(mock.initScript);
    else await page.addInitScript(() => { window.AndroidBridge = undefined; });
    if (opts.init) await page.addInitScript(opts.init);
    await page.goto(PAGE); await page.waitForTimeout(500);
    return page;
  };
  const page = await mkPage({});
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const sleep = ms => page.waitForTimeout(ms);
  const txt = id => ev(i => document.getElementById(i).innerText.replace(/\s+/g, ' ').trim(), id);
  const now = () => txt('pdnsNow');
  const msg = () => ev(() => { const e = document.getElementById('pdnsMsg'); return e.style.display === 'none' ? '' : e.innerText.replace(/\s+/g, ' ').trim(); });
  const pressed = () => ev(() => [...document.querySelectorAll('#privateDnsCard .pdns-btn')].filter(x => x.getAttribute('aria-pressed') === 'true').map(x => x.id).join(','));
  const ops = () => ev(() => window.__calls.op.map(o => o.op + ' ' + o.key + (o.op === 'put' ? '=' + o.value : '')));
  const clear = () => ev(() => { window.__calls.op.length = 0; window.__deny = {}; window.__revert = {}; window.__unknown = {}; window.__delay = 20; });
  const phone = () => ev(() => ({ mode: window.__db.global.private_dns_mode, spec: window.__db.global.private_dns_specifier }));
  const setPhone = (mode, spec) => ev(a => { window.__db.global.private_dns_mode = a[0]; if (a[1] === null) delete window.__db.global.private_dns_specifier; else window.__db.global.private_dns_specifier = a[1]; }, [mode, spec]);
  const idle = () => page.waitForFunction(() => !pdnsBusy, null, { timeout: 8000 });
  const press = async id => { await ev(i => document.getElementById(i).click(), id); await sleep(30); await idle(); await sleep(30); };
  const enabled = () => ev(() => [...document.querySelectorAll('#privateDnsCard button, #privateDnsCard input')].filter(e => e.offsetParent !== null && !e.disabled).length);
  const total = () => ev(() => [...document.querySelectorAll('#privateDnsCard button, #privateDnsCard input')].filter(e => e.offsetParent !== null).length);
  const toSettings = async () => { await ev(() => switchView('prefs')); await sleep(250); await idle(); await sleep(60); };

  // ---- 1. the host name check, on its own ----
  const res = await ev(rows => rows.map(r => { const x = pdnsCheckHost(r[0]); return x.ok ? 'ok:' + x.host : 'no:' + x.why; }), GOOD.concat(BAD).map(r => [r[0]]));
  let wrong = [];
  GOOD.forEach((r, i) => { if (res[i] !== 'ok:' + r[1]) wrong.push(JSON.stringify(r[0]).slice(0, 40) + ' -> ' + res[i]); });
  BAD.forEach((r, i) => { const got = res[GOOD.length + i]; if (got !== 'no:' + r[1]) wrong.push(JSON.stringify(r[0]).slice(0, 40) + ' -> ' + got + ' (wanted ' + r[1] + ')'); });
  check('1. the host name check: ' + GOOD.length + ' good names are accepted (lower case, spaces around and one trailing dot dropped), ' + BAD.length + ' bad ones are refused, each for its reason', wrong.length === 0, wrong.join(' | '));
  const lens = await ev(() => ({ ok253: pdnsCheckHost(('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(61)).ok, len: (('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(61)).length, ok254: pdnsCheckHost(('a'.repeat(63) + '.').repeat(3) + 'a'.repeat(62)).ok, ok63: pdnsCheckHost('a'.repeat(63) + '.example').ok, ok64: pdnsCheckHost('a'.repeat(64) + '.example').ok }));
  check('   the limits are exact: 253 characters pass, 254 do not; 63 in a part pass, 64 do not', lens.len === 253 && lens.ok253 && !lens.ok254 && lens.ok63 && !lens.ok64, JSON.stringify(lens));
  const every = await ev(() => { const bad = []; for (let c = 0; c < 0x250; c++) { const ch = String.fromCharCode(c); const x = pdnsCheckHost('a' + ch + 'b.example'); const allowed = /[A-Za-z0-9-]/.test(ch); if (allowed ? !x.ok && !(ch === '-' ) : x.ok) bad.push(c.toString(16)); } return bad; });
  check('   of the first 592 characters, exactly letters, digits and the hyphen can stand inside a part (a dot splits it)', every.filter(c => c !== '2e').length === 0, JSON.stringify(every.slice(0, 20)));
  const messages = await ev(() => ['empty', 'chars', 'long', 'dots', 'label', 'hyphen', 'onedot', 'ip', 'tld', 'zzz'].map(w => pdnsProblemText(w)));
  check('   every reason has its own plain sentence', new Set(messages).size === messages.length && messages.every(m => m.length > 10), JSON.stringify(messages));
  const cls = await ev(() => [['off', null], ['opportunistic', ''], ['hostname', 'Dns.Example.com'], ['hostname', ''], ['hostname', 'null'], ['null', null], ['', null], ['strict', 'x'], [' off\n', null]].map(a => JSON.stringify(pdnsClassify(a[0], a[1]))));
  check('   what the phone reports is classified: off, auto, host (kept as the phone has it), host mode with no host, not set, unknown', JSON.stringify(cls) === JSON.stringify(['{"kind":"off"}', '{"kind":"auto"}', '{"kind":"host","host":"Dns.Example.com"}', '{"kind":"nohost"}', '{"kind":"nohost"}', '{"kind":"default"}', '{"kind":"default"}', '{"kind":"other","raw":"strict"}', '{"kind":"off"}']), JSON.stringify(cls));

  // ---- 2. the card ----
  await toSettings();
  const card = await ev(() => {
    const c = document.getElementById('privateDnsCard'), prev = c.previousElementSibling;
    const q = s => c.querySelector(s);
    const live = el => [el.getAttribute('role'), el.getAttribute('aria-live')].join('/');
    return {
      inPrefs: document.getElementById('view-prefs').contains(c), after: prev && prev.tagName === 'STYLE' ? prev.previousElementSibling.id : prev && prev.id,
      title: q('.color-card-title').innerText, sub: q('.color-card-subtitle').innerText,
      presets: [...c.querySelectorAll('.pdns-btn[data-host]')].map(x => x.querySelector('b').innerText + '=' + x.dataset.host + '/' + x.querySelector('small').innerText + '/' + x.getAttribute('translate')).join(' | '),
      modes: [...c.querySelectorAll('.pdns-btn[data-mode]')].map(x => x.dataset.mode + '=' + x.innerText.trim()).join(' | '),
      live: live(q('#pdnsStatus')) + ' ' + q('#pdnsStatus').getAttribute('aria-atomic') + ' problem:' + live(q('#pdnsProblem')),
      input: { id: q('#pdnsHost').id, ph: q('#pdnsHost').placeholder, tr: q('#pdnsHost').getAttribute('translate'), label: c.querySelector('label[for=pdnsHost]').innerText, type: q('#pdnsHost').type, desc: q('#pdnsHost').getAttribute('aria-describedby') },
      notes: [...c.querySelectorAll('.pdns-notes p')].map(p => p.innerText).join(' '),
      pressedAttr: [...c.querySelectorAll('.pdns-btn')].every(x => x.hasAttribute('aria-pressed')),
    };
  });
  check('2. Settings has the card "Private DNS", straight after "Reconnecting devices" (the connection settings), with a plain explanation', card.inPrefs && card.after === 'cdReconnectCard' && card.title === 'Private DNS' && /DNS-over-TLS/.test(card.sub) && /Android 9/.test(card.sub), JSON.stringify({ inPrefs: card.inPrefs, after: card.after, title: card.title }));
  check('   the three presets, not translated: AdGuard DNS (the host name AdGuard uses now), Cloudflare, Quad9', card.presets === 'AdGuard DNS=dns.adguard-dns.com/dns.adguard-dns.com/no | Cloudflare=one.one.one.one/one.one.one.one/no | Quad9=dns.quad9.net/dns.quad9.net/no', card.presets);
  check('   Automatic and Off, and a field for another host name (hint dns.nextdns.io, not translated) with its Apply button and a label', card.modes === 'auto=Automatic | off=Off' && card.input.ph === 'dns.nextdns.io' && card.input.tr === 'no' && card.input.label === 'Another host name' && card.input.type === 'text' && card.input.desc === 'pdnsProblem', JSON.stringify(card.input) + card.modes);
  check('   the notes say: a host name not an IP, some networks block it, a wrong host can cost the connection, and Automatic or Off is the way back', /host name, never an IP address/.test(card.notes) && /networks and hotspots block it/.test(card.notes) && /lose its internet/.test(card.notes) && /Automatic or Off/.test(card.notes), card.notes);
  check('   the status is a polite live region read as a whole; the problem line too; every button has a pressed state', card.live === 'status/polite true problem:status/polite' && card.pressedAttr, card.live);

  // ---- 3. reading the phone ----
  check('3. Settings opens: the card reads the phone once (the mode only, the host is read when the mode is hostname) and says what it is', JSON.stringify(await ops()) === '["get private_dns_mode"]' && (await now()) === 'Private DNS is now: Automatic', JSON.stringify(await ops()) + ' / ' + await now());
  check('   Automatic is marked (aria-pressed), the others are not', (await pressed()) === 'pdnsAuto', await pressed());
  await clear(); await setPhone('hostname', 'DNS.Quad9.net'); await press('pdnsRefresh');
  check('   "Check again" reads the mode and the host: hostname dns.quad9.net (the case does not matter for the mark); Quad9 is marked', JSON.stringify(await ops()) === '["get private_dns_mode","get private_dns_specifier"]' && (await now()) === 'Private DNS is now: hostname DNS.Quad9.net' && (await pressed()) === 'pdnsQuad9', (await now()) + ' / ' + await pressed());
  const hostSpan = await ev(() => { const s = document.querySelector('#pdnsNow .pdns-host'); return s && [s.getAttribute('translate'), s.innerText, getComputedStyle(s).fontFamily.slice(0, 9)].join('|'); });
  check('   the host is its own span with translate="no" and a monospace face', /^no\|DNS.Quad9.net\|/.test(hostSpan || ''), hostSpan);
  await clear(); await setPhone('off', null); await press('pdnsRefresh');
  check('   off: "Private DNS is now: Off", Off marked', (await now()) === 'Private DNS is now: Off' && (await pressed()) === 'pdnsOff', (await now()) + ' / ' + await pressed());
  await clear(); await setPhone('hostname', null); await press('pdnsRefresh');
  check('   hostname mode with no host: said so, nothing marked', (await now()) === 'Private DNS is now: hostname mode, but no host name is set' && (await pressed()) === '', (await now()) + ' / ' + await pressed());
  await clear(); await setPhone('hostname', '<img src=x onerror=window.__xss=1>'); await press('pdnsRefresh');
  check('   a host the phone reports is shown as text, never as markup', (await ev(() => !window.__xss && !document.querySelector('#pdnsNow img') && document.querySelector('#pdnsNow .pdns-host').innerText)) === '<img src=x onerror=window.__xss=1>');
  await clear(); await ev(() => { delete window.__db.global.private_dns_mode; }); await press('pdnsRefresh');
  check('   not set at all: said so (the phone\'s own default applies), nothing marked', (await now()) === 'Private DNS is now: not set, so the phone’s own default applies' && (await pressed()) === '', await now());
  await clear(); await setPhone('strict', null); await press('pdnsRefresh');
  check('   a mode this card does not know is shown as it is', (await now()) === 'Private DNS is now: an unknown mode “strict”', await now());
  await clear(); await ev(() => { window.__deny = {}; const o = window.AndroidBridge.settingsOp; window.__o = o; window.AndroidBridge.settingsOp = (req, op, ns, key, v) => { setTimeout(() => window.onSettingsOp(JSON.stringify({ req, op, ns, key, ok: false, error: 'The settings service did not answer', advice: 'Try again.' })), 10); return 'started'; }; });
  await press('pdnsRefresh');
  check('   when the phone cannot say, the card says it could not read (no guess)', (await now()) === 'Could not read Private DNS: The settings service did not answer Try again.' && (await pressed()) === '', await now());
  await ev(() => { window.AndroidBridge.settingsOp = window.__o; }); await setPhone('opportunistic', ''); await press('pdnsRefresh');

  // ---- 3b. every time Settings opens the phone is read again ----
  await clear(); await setPhone('opportunistic', ''); await press('pdnsRefresh'); await clear();
  await setPhone('off', null);                                                 // changed in Android's own Settings meanwhile (the last look is a moment old)
  await ev(() => switchView('apps')); await sleep(100); await toSettings();
  check('3b. leaving Settings and coming back reads the phone again, even a moment after the last look: the change made in Android\'s own settings shows (Off, Off marked), after exactly one read', (await now()) === 'Private DNS is now: Off' && (await pressed()) === 'pdnsOff' && JSON.stringify(await ops()) === '["get private_dns_mode"]', (await now()) + JSON.stringify(await ops()));
  await clear(); await ev(() => switchView('apps')); await setPhone('opportunistic', ''); await toSettings();
  check('   and again the next time', (await now()) === 'Private DNS is now: Automatic' && JSON.stringify(await ops()) === '["get private_dns_mode"]', (await now()) + JSON.stringify(await ops()));
  await clear(); await ev(() => { window.__delay = 250; });
  await ev(() => document.getElementById('pdnsCloudflare').click()); await sleep(60);
  await ev(() => { switchView('apps'); switchView('prefs'); }); await sleep(30);
  await idle(); await sleep(60);
  check('   a change that is out is not disturbed by opening Settings again: no extra read, one change, read back once', JSON.stringify(await ops()) === '["put private_dns_specifier=one.one.one.one","put private_dns_mode=hostname","get private_dns_mode","get private_dns_specifier"]' && (await now()) === 'Private DNS is now: hostname one.one.one.one' && (await msg()) === 'Changed, and read back from the phone.', JSON.stringify(await ops()) + await now());
  await clear(); await setPhone('opportunistic', ''); await press('pdnsRefresh'); await clear();

  // ---- 4. a preset ----
  await clear(); await ev(() => { window.__delay = 250; sdbLoadedAt.global = Date.now(); });
  await ev(() => document.getElementById('pdnsAdguard').click()); await sleep(60);
  const mid = await ev(() => ({ busy: document.getElementById('privateDnsCard').getAttribute('aria-busy'), dis: [...document.querySelectorAll('#privateDnsCard button, #privateDnsCard input')].filter(e => e.offsetParent !== null).every(e => e.disabled), msg: document.getElementById('pdnsMsg').innerText }));
  check('4. AdGuard DNS: while the change is out every control is off, the card is busy, and a line says "Changing Private DNS…"', mid.busy === 'true' && mid.dis && mid.msg === 'Changing Private DNS…', JSON.stringify(mid));
  await ev(() => { pdnsPick(document.getElementById('pdnsOff')); pdnsApplyCustom(); document.getElementById('pdnsCloudflare').click(); pdnsChange({ want: { kind: 'auto' }, steps: [['put', PDNS_MODE, 'opportunistic']] }); pdnsRefresh(true); }); await sleep(30);
  await idle(); await sleep(60);
  check('   presses while it runs do nothing', JSON.stringify(await ops()) === '["put private_dns_specifier=dns.adguard-dns.com","put private_dns_mode=hostname","get private_dns_mode","get private_dns_specifier"]', JSON.stringify(await ops()));
  check('   the phone holds it, and the card says what the phone reports: "hostname dns.adguard-dns.com", with "Changed, and read back from the phone."', JSON.stringify(await phone()) === '{"mode":"hostname","spec":"dns.adguard-dns.com"}' && (await now()) === 'Private DNS is now: hostname dns.adguard-dns.com' && (await msg()) === 'Changed, and read back from the phone.', (await now()) + ' / ' + await msg());
  check('   the Hidden Settings tab is told its Global table is out of date (it reads it again when shown)', (await ev(() => sdbLoadedAt.global)) === 0);
  check('   AdGuard DNS is marked and nothing else; the controls are back', (await pressed()) === 'pdnsAdguard' && (await enabled()) === (await total()), (await pressed()) + ' ' + await enabled() + '/' + await total());
  await clear();
  await press('pdnsCloudflare');
  check('   Cloudflare: one.one.one.one', JSON.stringify(await phone()) === '{"mode":"hostname","spec":"one.one.one.one"}' && (await now()) === 'Private DNS is now: hostname one.one.one.one' && (await pressed()) === 'pdnsCloudflare', await now());
  await clear();
  await press('pdnsQuad9');
  check('   Quad9: dns.quad9.net', JSON.stringify(await phone()) === '{"mode":"hostname","spec":"dns.quad9.net"}' && (await now()) === 'Private DNS is now: hostname dns.quad9.net' && (await pressed()) === 'pdnsQuad9', await now());

  // ---- 5. another host name ----
  await clear();
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = '  Dns.NextDNS.io. '; i.dispatchEvent(new Event('input')); });
  check('5. a good name in the field shows no problem', (await txt('pdnsProblem')) === '');
  await ev(() => document.getElementById('pdnsApply').click()); await sleep(30); await idle(); await sleep(40);
  check('   Apply writes the cleaned-up name (lower case, no spaces, no final dot), reads back, and shows it', JSON.stringify(await ops()) === '["put private_dns_specifier=dns.nextdns.io","put private_dns_mode=hostname","get private_dns_mode","get private_dns_specifier"]' && (await now()) === 'Private DNS is now: hostname dns.nextdns.io' && (await ev(() => document.getElementById('pdnsHost').value)) === 'dns.nextdns.io', JSON.stringify(await ops()) + await now());
  check('   no preset is marked for a name of your own', (await pressed()) === '', await pressed());
  await clear();
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'dns.nextdns.io'; i.focus(); i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); }); await sleep(30); await idle(); await sleep(40);
  check('   Enter in the field applies too', (await ops()).length === 4, JSON.stringify(await ops()));
  // refused names: nothing reaches the phone, the reason is shown beside the field
  await clear();
  const refused = [];
  for (const [t, why] of [['1.1.1.1', 'ip'], ['dns.example.com; reboot', 'chars'], ['$(id).example.com', 'chars'], ["a'b.example.com", 'chars'], ['dns', 'onedot'], ['x'.repeat(64) + '.example', 'label'], ['', 'empty'], ['bücher.example', 'chars'], ['-a.example.com', 'hyphen']]) {
    await ev(a => { const i = document.getElementById('pdnsHost'); i.value = a; i.dispatchEvent(new Event('input')); document.getElementById('pdnsApply').click(); }, t);
    await sleep(30);
    refused.push((await txt('pdnsProblem')) === (await ev(w => pdnsProblemText(w), why)) && (await txt('pdnsProblem')).length > 5);
  }
  check('   bad names are refused with a sentence next to the field, and nothing is sent to the phone (no write, no read)', refused.every(Boolean) && (await ops()).length === 0, JSON.stringify(refused) + JSON.stringify(await ops()));
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = ''; i.dispatchEvent(new Event('input')); });
  check('   an empty field shows no complaint', (await txt('pdnsProblem')) === '');
  check('   the field has the focus after a refusal, so it can be fixed', (await ev(() => document.activeElement && document.activeElement.id)) === 'pdnsHost');

  // ---- 6. Automatic and Off ----
  await clear(); await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  await press('pdnsAuto');
  check('6. Automatic writes private_dns_mode=opportunistic (the host stays), reads back, and says Automatic', JSON.stringify(await ops()) === '["put private_dns_mode=opportunistic","get private_dns_mode"]' && JSON.stringify(await phone()) === '{"mode":"opportunistic","spec":"dns.adguard-dns.com"}' && (await now()) === 'Private DNS is now: Automatic' && (await pressed()) === 'pdnsAuto', JSON.stringify(await ops()));
  await clear();
  await press('pdnsOff');
  check('   Off writes private_dns_mode=off and resets the host (as the Kotlin app does), reads back, and says Off', JSON.stringify(await ops()) === '["put private_dns_mode=off","delete private_dns_specifier","get private_dns_mode"]' && JSON.stringify(await phone()) === '{"mode":"off"}' && (await now()) === 'Private DNS is now: Off' && (await pressed()) === 'pdnsOff' && (await msg()) === 'Changed, and read back from the phone.', JSON.stringify(await ops()) + await now());

  // ---- 7. never success from the write alone ----
  await clear(); await setPhone('opportunistic', '');
  await ev(() => { window.__o = window.AndroidBridge.settingsOp; window.AndroidBridge.settingsOp = function (req, op, ns, key, v) { if (op === 'put' && key === 'private_dns_mode') { setTimeout(() => window.onSettingsOp(JSON.stringify({ req, op, ns, key, ok: true, value: v, requested: v })), 10); return 'started'; } return window.__o.apply(this, arguments); }; });   // says yes, keeps nothing
  await press('pdnsOff');
  check('7. a write the phone claims but does not keep: the read-back says Automatic, and the card says "Not applied", not success', (await now()) === 'Private DNS is now: Automatic' && /^Not applied: the phone does not report the new setting/.test(await msg()) && (await ev(() => document.getElementById('pdnsMsg').classList.contains('is-err'))) && (await pressed()) === 'pdnsAuto', (await now()) + ' / ' + await msg());
  await ev(() => { window.AndroidBridge.settingsOp = window.__o; });
  await clear(); await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__revert['global/private_dns_specifier'] = 'dns.adguard-dns.com'; });
  await press('pdnsQuad9');
  check('   a write Android puts back: the error is shown, the mode is not touched, and the card shows what the phone really has', (await ops()).join() === 'put private_dns_specifier=dns.quad9.net,get private_dns_mode,get private_dns_specifier' && /^Could not change Private DNS: Android did not keep the new value/.test(await msg()) && (await now()) === 'Private DNS is now: hostname dns.adguard-dns.com' && (await pressed()) === 'pdnsAdguard', (await ops()).join() + ' / ' + await msg());
  await clear();
  await ev(() => { window.__deny['global/private_dns_specifier'] = 'java.lang.SecurityException: Permission denial: writing to settings requires android.permission.WRITE_SECURE_SETTINGS'; });
  await press('pdnsCloudflare');
  const denied = await msg();
  check('   permission missing: the phone\'s refusal is shown (with the advice), the mode is not written, the old state stays', (await ops()).join() === 'put private_dns_specifier=one.one.one.one,get private_dns_mode,get private_dns_specifier' && /^Could not change Private DNS: java.lang.SecurityException/.test(denied) && /Android refused the request|Xiaomi/.test(denied) && (await now()) === 'Private DNS is now: hostname dns.adguard-dns.com', denied);
  await clear();
  await ev(() => { window.__unknown['global/private_dns_specifier'] = { apply: false }; });
  await press('pdnsQuad9');
  check('   no answer from the phone: said so, and the state read afterwards is the one shown (the change may have been made)', /^Could not change Private DNS: The phone did not answer\./.test(await msg()) && /It may still have been applied/.test(await msg()) && (await now()) === 'Private DNS is now: hostname dns.adguard-dns.com', (await msg()) + ' / ' + await now());
  await clear(); await ev(() => { window.__noAnswer = false; });
  await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__deny['global/private_dns_specifier'] = 'java.lang.SecurityException: not allowed'; });
  await press('pdnsOff');
  check('   Off where the host cannot be cleared: the mode is Off (read back) and the card says the saved host name stayed', (await now()) === 'Private DNS is now: Off' && /^Changed, and read back from the phone\. The saved host name could not be cleared\.$/.test(await msg()), (await now()) + ' / ' + await msg());


  // ---- 7b. the confirmed state decides: a refused redundant write after the one that applied it ----
  await clear(); await setPhone('hostname', 'dns.quad9.net'); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__deny['global/private_dns_mode'] = 'java.lang.SecurityException: not allowed'; });
  await press('pdnsQuad9');
  const warn = await msg(), warnErr = await ev(() => document.getElementById('pdnsMsg').classList.contains('is-err'));
  check('   the phone already in hostname mode: the host write applies at once and the redundant mode write is refused; the read-back has the host, so the card does not say "Could not change" but shows the state and the step\'s problem as a warning', (await ops()).join() === 'put private_dns_specifier=dns.quad9.net,put private_dns_mode=hostname,get private_dns_mode,get private_dns_specifier'
    && /^The phone has this setting, but one step reported a problem: java.lang.SecurityException: not allowed/.test(warn) && !/Could not change/.test(warn) && !warnErr && (await now()) === 'Private DNS is now: hostname dns.quad9.net' && (await pressed()) === 'pdnsQuad9', warn + ' / ' + (await now()));
  await clear();
  await ev(() => { window.__unknown['global/private_dns_mode'] = { apply: false }; });
  await press('pdnsQuad9');
  check('   the same when the second write gets no answer', /^The phone has this setting, but one step reported a problem: The phone did not answer\./.test(await msg()) && (await now()) === 'Private DNS is now: hostname dns.quad9.net', await msg());
  await clear(); await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__deny['global/private_dns_mode'] = 'java.lang.SecurityException: not allowed'; window.__deny['global/private_dns_specifier'] = 'java.lang.SecurityException: not allowed'; });
  await press('pdnsQuad9');
  check('   but a refused change that left the old state is still a failure', /^Could not change Private DNS: /.test(await msg()) && (await now()) === 'Private DNS is now: hostname dns.adguard-dns.com' && (await ev(() => document.getElementById('pdnsMsg').classList.contains('is-err'))), await msg());
  await clear();

  // ---- 8. stale answers ----
  await clear(); await setPhone('opportunistic', ''); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__delay = 300; });
  await ev(() => document.getElementById('pdnsAdguard').click()); await sleep(80);
  await ev(() => { isPrivilegedActive = false; pdnsOnModeChange(); });                     // the working mode went away while the change was out
  const st = await ev(() => ({ busy: pdnsBusy, now: document.getElementById('pdnsNow').innerText, setup: getComputedStyle(document.getElementById('pdnsSetup')).display }));
  await sleep(1200);
  const after = await ev(() => ({ busy: pdnsBusy, now: document.getElementById('pdnsNow').innerText, msg: document.getElementById('pdnsMsg').style.display, state: pdnsState }));
  check('8. the working mode goes while a change is out: the card explains it at once, and the late answers change nothing (no message, no state, not busy)', st.now === 'Needs a working mode (ADB, Wireless Debugging, Shizuku or Root)' && st.setup !== 'none' && !st.busy && !after.busy && after.now === st.now && after.msg === 'none' && after.state === null, JSON.stringify([st, after]));
  await ev(() => { isPrivilegedActive = true; pdnsOnModeChange(); }); await sleep(500); await idle();
  check('   when the mode is back, the card reads the phone again: the dropped change stopped after its first step (the host was written, the mode never was), and that is what it shows', (await now()) === 'Private DNS is now: Automatic' && JSON.stringify(await phone()) === '{"mode":"opportunistic","spec":"dns.adguard-dns.com"}', (await now()) + JSON.stringify(await phone()));
  await clear(); await ev(() => { window.__delay = 300; });
  await ev(() => { pdnsRefresh(false); }); await sleep(50);
  const seqA = await ev(() => pdnsSeq);
  await ev(() => { pdnsSeq++; pdnsBusy = false; pdnsReading = false; pdnsState = { mode: 'off', spec: null }; pdnsRender(); });         // a newer question took over
  await sleep(900);
  check('   an answer to an older question never overwrites a newer state (sequence number)', (await ev(() => pdnsState.mode)) === 'off' && (await now()) === 'Private DNS is now: Off' && seqA > 0, await now());
  await clear();


  // ---- 8b. the backend changes (not only on and off) while a change is out ----
  await clear(); await setPhone('opportunistic', ''); await press('pdnsRefresh'); await clear();
  await ev(() => { window.__delay = 300; });
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = 'dns.nextdns.io'; pdnsApplyCustom(); }); await sleep(80);
  await ev(() => { currentWorkingMode = 'root'; pdnsOnModeChange(); });                  // Wireless ADB / TCP to Root: still privileged, another backend
  await sleep(1500); await idle(); await sleep(100);
  const be = await ev(() => ({ now: document.getElementById('pdnsNow').innerText, msg: document.getElementById('pdnsMsg').style.display, busy: pdnsBusy }));
  check('8b. the backend changes (adb_tcp to root, still privileged) while a change is out: the old answers are dropped, the second step never runs on the other backend (only the host was written), and the card reads the phone again and shows what it has', (await ops()).join() === 'put private_dns_specifier=dns.nextdns.io,get private_dns_mode'
    && be.now === 'Private DNS is now: Automatic' && be.msg === 'none' && !be.busy && JSON.stringify(await phone()) === '{"mode":"opportunistic","spec":"dns.nextdns.io"}', JSON.stringify([await ops(), be]));
  await ev(() => { currentWorkingMode = 'adb_tcp'; pdnsOnModeChange(); }); await sleep(400); await idle();
  check('   and the same again when it goes back; the same backend reported twice changes nothing', (await ops()).filter(o => /^get/.test(o)).length === 2 && (await ev(() => { const n = window.__calls.op.length; pdnsOnModeChange(); return window.__calls.op.length === n; })), JSON.stringify(await ops()));
  await clear();

  // ---- 9. no working mode, no bridge ----
  const p2 = await mkPage({ init: () => { window.__mode.priv = false; } });
  const e2 = (fn, a) => p2.evaluate(fn, a);
  await e2(() => switchView('prefs')); await p2.waitForTimeout(300);
  const np = await e2(() => ({ now: document.getElementById('pdnsNow').innerText, setup: getComputedStyle(document.getElementById('pdnsSetup')).display, locked: document.getElementById('pdnsAdguard').classList.contains('locked'), dis: document.getElementById('pdnsAdguard').disabled, ops: window.__calls.op.length }));
  check('9. no working mode: the card says "Needs a working mode…", offers Set Up, and the buttons are marked locked (not hidden)', /^Needs a working mode/.test(np.now) && np.setup !== 'none' && np.locked && !np.dis && np.ops === 0, JSON.stringify(np));
  await e2(() => document.getElementById('pdnsAdguard').click()); await p2.waitForTimeout(150);
  const modal = await e2(() => ({ open: document.getElementById('privilegeModal').classList.contains('show'), ops: window.__calls.op.length, text: document.getElementById('privilegeModalActionText').innerText }));
  check('   pressing a preset opens the "requires working mode" sheet and sends nothing', modal.open && modal.ops === 0 && /Private DNS/.test(modal.text), JSON.stringify(modal));
  await e2(() => { document.getElementById('pdnsHost').value = 'dns.nextdns.io'; pdnsApplyCustom(); }); await p2.waitForTimeout(100);
  check('   Apply with no working mode sends nothing either', (await e2(() => window.__calls.op.length)) === 0);
  await p2.close();

  const p3 = await mkPage({ bridge: false });
  await p3.evaluate(() => switchView('prefs')); await p3.waitForTimeout(300);
  const nb = await p3.evaluate(() => ({ now: document.getElementById('pdnsNow').innerText, allOff: [...document.querySelectorAll('#privateDnsCard button, #privateDnsCard input')].filter(e => e.offsetParent !== null).every(e => e.disabled) }));
  const nb2 = await p3.evaluate(() => { document.getElementById('pdnsHost').value = 'dns.nextdns.io'; pdnsApplyCustom(); pdnsPick(document.getElementById('pdnsOff')); return 'no error'; });
  check('   in a browser (no bridge): "This works in the app on a phone, not in a browser.", every control off, no error when forced', nb.now === 'This works in the app on a phone, not in a browser.' && nb.allOff && nb2 === 'no error', JSON.stringify(nb));
  await p3.close();

  // ---- 10. narrow, themes, motion ----
  const p4 = await mkPage({ w: 320, h: 700, reduce: true });
  const e4 = (fn, a) => p4.evaluate(fn, a);
  await e4(() => { const st = document.createElement('style'); st.textContent = '*{transition:none!important}'; document.head.appendChild(st); });         // colours are measured, not caught half way through a fade
  await e4(() => switchView('prefs')); await p4.waitForTimeout(400);
  const fit = async label => {
    const r = await e4(() => {
      const c = document.getElementById('privateDnsCard'), cr = c.getBoundingClientRect(), de = document.documentElement;
      const kids = [...c.querySelectorAll('button, input, .pdns-status, .pdns-notes p')].filter(e => e.offsetParent !== null).map(e => ({ id: e.id || e.tagName, r: e.getBoundingClientRect(), sw: e.tagName === 'INPUT' ? 0 : e.scrollWidth, cw: e.clientWidth }));
      const out = kids.filter(k => k.r.right > cr.right + 0.5 || k.r.left < cr.left - 0.5 || k.sw > k.cw + 1).map(k => k.id);
      const small = [...c.querySelectorAll('button')].filter(b => b.offsetParent !== null && b.getBoundingClientRect().height < 32).map(b => b.id);
      return { page: de.scrollWidth <= window.innerWidth, out, small, w: Math.round(cr.width) };
    });
    const fits = r.page && r.out.length === 0 && r.small.length === 0;
    check('10. ' + label + ': nothing sticks out of the card or the page, buttons are at least 32px tall', fits, fits ? undefined : JSON.stringify(r));
  };
  await fit('320px wide, dark');
  await e4(() => { document.getElementById('pdnsHost').value = 'a-very-long-name.' + 'b'.repeat(60) + '.example.com'; pdnsHostInput(); });
  await fit('   with a long name in the field');
  await e4(() => { document.getElementById('pdnsHost').value = ''; pdnsHostInput(); });
  const themes = [['dark', 'amoled'], ['light', 'amoled'], ['dark', 'ocean'], ['light', 'sunset']];
  const lum = c => { const v = c.map(x => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
  for (const [mode, preset] of themes) {
    await e4(a => { applyPalettePreset(a[1]); setAppearance(a[0]); }, [mode, preset]); await p4.waitForTimeout(150);
    const sample = () => e4(() => {
      const rgb = e => (getComputedStyle(e).color.match(/[\d.]+/g) || []).slice(0, 3).map(Number);
      const bg = e => { for (let n = e; n; n = n.parentElement) { const m = getComputedStyle(n).backgroundColor.match(/[\d.]+/g) || []; if (m.length && !(m.length === 4 && +m[3] === 0)) return m.slice(0, 3).map(Number); } return [0, 0, 0]; };
      const now = document.getElementById('pdnsNow'), btn = document.getElementById('pdnsAuto');
      btn.setAttribute('aria-pressed', 'true');
      const out = { now: [rgb(now), bg(now)], btn: [rgb(btn), bg(btn)] };
      btn.setAttribute('aria-pressed', 'false');
      return out;
    });
    let colours = await sample(), same = 0;                                          // wait until two reads in a row agree (the theme has been applied for good)
    for (let i = 0; i < 40 && same < 2; i++) { await p4.waitForTimeout(100); const nx = await sample(); same = JSON.stringify(nx) === JSON.stringify(colours) ? same + 1 : 0; colours = nx; }
    const cr = (a, b2) => { const l1 = lum(a), l2 = lum(b2); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
    const c1 = cr(colours.now[0], colours.now[1]), c2 = cr(colours.btn[0], colours.btn[1]);
    check('    ' + mode + ' / ' + preset + ': the colours settled; the state text reads on the card (contrast >= 4.5) and the marked button reads (>= 3)', same >= 2 && c1 >= 4.5 && c2 >= 3, same >= 2 && c1 >= 4.5 && c2 >= 3 ? undefined : same + ' ' + c1.toFixed(2) + ' / ' + c2.toFixed(2));
    await fit('    ' + mode + ' / ' + preset);
  }
  const motion = await e4(() => { const out = []; for (const e of document.querySelectorAll('#privateDnsCard *')) { const cs = getComputedStyle(e); if (cs.animationName !== 'none') out.push(e.id || e.tagName); } return out; });
  check('    with reduced motion the card runs no animation (it has none of its own)', motion.length === 0, JSON.stringify(motion));
  const check4 = await e4(() => { const s = [...document.styleSheets].flatMap(x => { try { return [...x.cssRules]; } catch (e) { return []; } }); return document.querySelector('#privateDnsCard').innerHTML.indexOf('@keyframes') < 0; });
  check('    the card adds no keyframes and no hard-coded colours of its own (it uses the page tokens)', check4 && !(await e4(() => /#[0-9a-fA-F]{3,6}\b|rgb\(/.test([...document.querySelectorAll('style')].filter(s => s.textContent.indexOf('#privateDnsCard') >= 0).map(s => s.textContent.split('\n').filter(l => l.indexOf('#privateDnsCard') >= 0).join('\n')).join('\n').replace(/#privateDnsCard/g, '')))));
  await p4.close();

  // ---- 11. translation ----
  await clear(); await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  await ev(() => LANG_ENGINE.set('de')); await sleep(500);
  await ev(() => pdnsRender());
  const de = await ev(() => ({
    title: document.querySelector('#privateDnsCard .color-card-title').innerText, now: document.getElementById('pdnsNow').innerText, host: document.querySelector('#pdnsNow .pdns-host').innerText,
    btns: [...document.querySelectorAll('#privateDnsCard .pdns-btn')].map(x => x.innerText.replace(/\s+/g, ' ').trim()).join(' | '), label: document.querySelector('label[for=pdnsHost]').innerText, ph: document.getElementById('pdnsHost').placeholder,
    group: [...document.querySelectorAll('#privateDnsCard [role=group]')].map(g => g.getAttribute('aria-label')).join(' | '), refresh: document.getElementById('pdnsRefresh').innerText, apply: document.getElementById('pdnsApply').innerText }));
  check('11. in German: title, state line, buttons, label and group names are translated; the host names, the presets and the hint are left alone', de.title === 'Privates DNS' && de.now === 'Privates DNS ist jetzt: Hostname dns.adguard-dns.com' && de.host === 'dns.adguard-dns.com'
    && de.btns === 'AdGuard DNS dns.adguard-dns.com | Cloudflare one.one.one.one | Quad9 dns.quad9.net | Automatisch | Aus' && de.label === 'Anderer Hostname' && de.ph === 'dns.nextdns.io' && de.group === 'Dienste für privates DNS | Automatisch oder Aus' && de.refresh === 'Erneut prüfen' && de.apply === 'Anwenden', JSON.stringify(de));
  await ev(() => { const i = document.getElementById('pdnsHost'); i.value = '8.8.8.8'; pdnsApplyCustom(); });
  const deProb = await txt('pdnsProblem');
  await press('pdnsAuto');
  const deMsg = await msg(), deNow = await now();
  check('    the refusal, the result line and the new state are German too', deProb === 'Das sieht nach einer IP-Adresse aus. Privates DNS braucht einen Hostnamen.' && deMsg === 'Geändert und vom Telefon zurückgelesen.' && deNow === 'Privates DNS ist jetzt: Automatisch', JSON.stringify([deProb, deMsg, deNow]));
  await ev(() => { window.__deny['global/private_dns_specifier'] = 'x'; document.getElementById('pdnsHost').value = 'dns.nextdns.io'; pdnsApplyCustom(); }); await sleep(30); await idle(); await sleep(40);
  const deFail = await msg();
  check('    a failure line with the phone\'s own text (not translated) in a German sentence', /^Privates DNS konnte nicht geändert werden: x/.test(deFail), deFail);
  const src = fs.readFileSync(path.join(REPO, 'src', 'com', 'bloatware', 'bingblop', 'SettingsDb.java'), 'utf8');
  const adviceBody = src.slice(src.indexOf('static String advice('), src.indexOf('\n    }\n', src.indexOf('static String advice(')));
  const advList = [...adviceBody.matchAll(/return "((?:[^"\\]|\\.)*)";/g)].map(m => JSON.parse('"' + m[1] + '"')).filter(t => t !== '');
  const advDe = await ev(l => l.map(a => pdnsAppText(a)), advList);
  check('    every advice sentence the app can send (read from SettingsDb.java: ' + advList.length + ') is translated by the card, none is left in English', advList.length === 5 && advDe.every((t, i) => t && t !== advList[i]) && (await ev(l => l.every(a => (LANG_ENGINE.set('en'), pdnsAppText(a) === a)), advList)) === true, JSON.stringify(advDe).slice(0, 300));
  await ev(() => LANG_ENGINE.set('de')); await sleep(300);
  await ev(a => { window.__o2 = window.AndroidBridge.settingsOp; window.AndroidBridge.settingsOp = (req, op, ns, key, v) => { setTimeout(() => window.onSettingsOp(JSON.stringify({ req, op, ns, key, ok: false, error: 'Android did not keep the new value', advice: a })), 10); return 'started'; }; }, advList[1]);
  await ev(() => { document.getElementById('pdnsHost').value = 'dns.nextdns.io'; pdnsApplyCustom(); }); await sleep(30); await idle(); await sleep(40);
  const deAdv = await msg();
  await ev(() => { window.AndroidBridge.settingsOp = window.__o2; });
  check('    a refusal with the app\'s advice, in German: the verdict and the advice are German sentences (the sentence of the app is not shown in English)', /^Privates DNS konnte nicht geändert werden: Android hat den neuen Wert nicht übernommen Android hat die Anfrage abgelehnt\. Manche Telefone/.test(deAdv) && !/Android refused|did not keep/.test(deAdv), deAdv);
  await clear(); await setPhone('hostname', 'dns.adguard-dns.com'); await press('pdnsRefresh'); await clear();
  const langs = {};
  for (const code of ['ar', 'es', 'fr', 'hi', 'id', 'it', 'ja', 'ko', 'pt-BR', 'ru', 'tr', 'zh-CN']) {
    await ev(c => LANG_ENGINE.set(c), code); await sleep(250);
    await ev(() => pdnsRender());
    langs[code] = await ev(adv => ({ t: document.querySelector('#privateDnsCard .color-card-title').innerText, n: document.getElementById('pdnsNow').innerText, h: document.querySelector('#pdnsNow .pdns-host') ? document.querySelector('#pdnsNow .pdns-host').innerText : null, ph: document.getElementById('pdnsHost').placeholder,
      b: document.getElementById('pdnsAuto').innerText.trim(), a: pdnsAppText(adv[1]), k: pdnsAppText('Android did not keep the new value'), p: pdnsProblemText('ip'), f: txF('Could not change Private DNS: {0}', 'zz') }), advList);
  }
  const L = Object.entries(langs);
  check('    in the 12 other languages the title, the state line, the Automatic button, a refusal, a failure line and the advice of the app are all translated (none is the English text), the host name and the hint are not', L.every(([c, v]) => v.t && v.t !== 'Private DNS' && v.n && !/^Private DNS is now/.test(v.n) && v.b !== 'Automatic' && v.p !== 'That looks like an IP address. Private DNS needs a host name.' && v.f.indexOf('zz') >= 0 && v.f !== 'Could not change Private DNS: zz' && v.a && v.a !== advList[1] && v.k && v.k !== 'Android did not keep the new value' && v.ph === 'dns.nextdns.io'), JSON.stringify(langs).slice(0, 600));
  check('    and the state line keeps the host name as it is, whatever the language', L.every(([c, v]) => v.n.indexOf('dns.adguard-dns.com') >= 0 && v.h === 'dns.adguard-dns.com'), JSON.stringify(L.filter(([c, v]) => v.n.indexOf('dns.adguard-dns.com') < 0).map(x => x[0])));
  await ev(() => LANG_ENGINE.set('en')); await sleep(300);

  check('no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all ok');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
