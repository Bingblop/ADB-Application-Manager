// v7.4 File manager: code and markup are shown in colour by default (editor, viewer, archive preview) and long lines wrap by default.
const { chromium, PAGE } = require('./lib/pw');
const fm = require('./lib/fm_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = '/storage/emulated/0';
const LONG = 'x'.repeat(400);
const FILES = {
  [ROOT + '/Code']: null,
  [ROOT + '/Code/AndroidManifest.xml']: '<?xml version="1.0"?>\n<manifest package="a.b"><!-- note --><uses-sdk android:minSdkVersion="26"/></manifest>\n',
  [ROOT + '/Code/data.json']: '{"name": "x", "n": 12, "ok": true, "none": null}\n',
  [ROOT + '/Code/Main.java']: '// hi\npublic class Main { int n = 0x1F; String s = "a<b"; }\n',
  [ROOT + '/Code/run.sh']: '#!/bin/sh\nif [ -n "$A" ]; then echo ${B}; fi\n',
  [ROOT + '/Code/notes.txt']: 'just words <b>not code</b>\n',
  [ROOT + '/Code/long.txt']: LONG + '\n',
  [ROOT + '/Code/evil.html']: '<script>alert(1)</script><img src=x onerror=alert(2)>\n',
};
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await page.addInitScript(fm.initScript, { files: FILES });
  await page.goto(PAGE);
  await page.waitForTimeout(300);
  await page.evaluate(() => switchView('files'));
  await page.waitForFunction(() => document.querySelectorAll('#fmList .perm-row').length > 0);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const edit = async name => { await ev(n => { fmActions('/storage/emulated/0/Code/' + n, false, n); fmEditOpen(); }, name); await sleep(80); };
  const spans = cls => ev(c => Array.from(document.querySelectorAll('#fmEditHl .syn-' + c)).map(e => e.textContent), cls);

  await edit('AndroidManifest.xml');
  check('1. an XML file opens in colour: tags, attributes, values and comments are told apart', await ev(() => document.getElementById('fmEditBox').classList.contains('hl')) && (await spans('t')).includes('<manifest') && (await spans('a')).includes('package') && (await spans('s')).includes('"a.b"') && (await spans('c')).some(t => t.includes('note')));
  check('   the text in the box is still the plain file (what is saved is the text, not the colours)', await ev(() => document.getElementById('fmEditArea').value.startsWith('<?xml version="1.0"?>')));
  check('   word wrap is on by default, for the box and for the colour layer under it', await ev(() => document.getElementById('fmEditArea').classList.contains('wrap') && document.getElementById('fmEditHl').classList.contains('wrap') && document.getElementById('fmEditWrap').checked));
  await ev(() => { const t = document.getElementById('fmEditArea'); t.value += '<new a="1"/>\n'; t.dispatchEvent(new Event('input')); });
  await sleep(80);
  check('   typing updates the colours', (await spans('t')).includes('<new'));
  await ev(() => document.getElementById('fmEditColors').click());
  await sleep(60);
  check('   the "Colors" switch turns them off (plain box again) and the choice is remembered', await ev(() => !document.getElementById('fmEditBox').classList.contains('hl') && kvGet('fm_colors', true) === false));
  await ev(() => document.getElementById('fmEditColors').click());
  await sleep(60);
  check('   and on again', await ev(() => document.getElementById('fmEditBox').classList.contains('hl')));
  await ev(() => { fmEd.orig = document.getElementById('fmEditArea').value; fmEditClose(); });

  await edit('data.json');
  check('2. JSON: keys, strings, numbers and true/false/null', (await spans('a')).includes('"name"') && (await spans('s')).includes('"x"') && (await spans('n')).includes('12') && (await spans('k')).join() === 'true,null');
  await ev(() => fmEditClose());
  await edit('Main.java');
  check('3. Java: comment, keywords, numbers, strings (a "<" in a string is text, not a tag)', (await spans('c')).includes('// hi') && (await spans('k')).includes('class') && (await spans('n')).includes('0x1F') && (await spans('s')).includes('"a<b"'));
  await ev(() => fmEditClose());
  await edit('run.sh');
  check('4. shell: comment line, keywords, variables', (await spans('c'))[0] === '#!/bin/sh' && (await spans('k')).includes('then') && (await spans('v')).includes('${B}'));
  await ev(() => fmEditClose());
  await edit('notes.txt');
  check('5. plain text is left plain (no colour layer), and its "<b>" is just text', await ev(() => !document.getElementById('fmEditBox').classList.contains('hl') && document.getElementById('fmEditArea').value.includes('<b>not code</b>')));
  await ev(() => fmEditClose());
  await edit('evil.html');
  check('6. the colour layer is built from escaped text: a script or an image tag in the file never runs or becomes a real element', await ev(() => !document.querySelector('#fmEditHl script, #fmEditHl img') && document.getElementById('fmEditHl').textContent.includes('onerror=alert(2)') && !window.__xss));
  await ev(() => fmEditClose());

  // wrap off is remembered; a long line then does not wrap
  await edit('long.txt');
  await ev(() => document.getElementById('fmEditWrap').click());
  const noWrap = await ev(() => { const t = document.getElementById('fmEditArea'); return { wrap: t.classList.contains('wrap'), sw: t.scrollWidth > t.clientWidth }; });
  check('7. with "Wrap lines" off a long line runs off to the side (scrolls), and the choice is remembered for the next file', !noWrap.wrap && noWrap.sw && (await ev(() => kvGet('fm_wrap', true))) === false);
  await ev(() => fmEditClose());
  await edit('notes.txt');
  check('   the next file opens with wrapping off, as chosen', await ev(() => !document.getElementById('fmEditArea').classList.contains('wrap') && !document.getElementById('fmEditWrap').checked));
  await ev(() => { document.getElementById('fmEditWrap').click(); fmEditClose(); });

  // the plain viewer
  await ev(() => { fmActions('/storage/emulated/0/Code/AndroidManifest.xml', false, 'AndroidManifest.xml'); fmViewText(); });
  await sleep(60);
  check('8. the viewer (View on a file) shows code in colour and wraps long lines', await ev(() => { const v = document.getElementById('fmViewer'); return !!v.querySelector('.syn-t') && getComputedStyle(v).whiteSpace === 'pre-wrap'; }));
  await ev(() => { fmActions('/storage/emulated/0/Code/notes.txt', false, 'notes.txt'); fmViewText(); });
  check('   a plain text file is shown as it is', await ev(() => { const v = document.getElementById('fmViewer'); return !v.querySelector('.syn-t') && v.innerText.includes('<b>not code</b>'); }));

  // size cap and the tokenizer on odd input
  const big = await ev(() => synFor('big.json', '{"a":1}'.repeat(60000)));
  check('9. a text over 250,000 characters is not coloured (plain, so a big file stays fast)', big === null);
  const odd = await ev(() => ['<', '<a', '<a b="', '<!--', '"', '/*', '`', "'''", '\u0000', '<script>', '<style>a{'].every(t => ['xml', 'js', 'py', 'css', 'java', 'json', 'yaml', 'sql'].every(l => typeof synHighlight(t, l) === 'string')));
  check('   unfinished or odd input never throws (an open tag, an open comment or string)', odd);

  const light = await ev(() => { document.documentElement.setAttribute('data-appearance', 'light'); const c = getComputedStyle(document.documentElement).getPropertyValue('--syn-c'); document.documentElement.setAttribute('data-appearance', 'dark'); return c.trim(); });
  check('10. the colours follow the light and dark theme (a darker green for comments on light)', light === '#008000');
  await page.close();
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'ALL PASSED');
  process.exit(bad ? 1 : 0);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
