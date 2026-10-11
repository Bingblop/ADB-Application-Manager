// Runs the page's validators and the Java ones on the same inputs and compares the answers.
const fs = require('fs'), vm = require('vm'), cp = require('child_process');
// inputs from run.js: the page, the compiled ParityDump2 classes, and the extra hostile cases
const HTML = process.env.PARITY_HTML || process.argv[2];
const CLASSES = process.env.PARITY_CLASSES;
const CASES = process.env.PARITY_CASES;
if (!HTML || !CLASSES || !CASES) { console.error('run it through tests/java/run.js parity'); process.exit(2); }
const src = fs.readFileSync(HTML, 'utf8');
function grab(name) {
  const i = src.indexOf('function ' + name + '(');
  if (i < 0) throw new Error('missing ' + name);
  let j = src.indexOf('{', i), depth = 0, k = j;
  for (; k < src.length; k++) { if (src[k] === '{') depth++; else if (src[k] === '}') { depth--; if (depth === 0) break; } }
  return src.slice(i, k + 1);
}
const code = 'const SDB_MAX_KEY = 256, SDB_MAX_VALUE = 20000, SDB_MAX_QUOTED_BYTES = 62000;\n' + ['sdbKeyProblem', 'sdbQuotedBytes', 'sdbValueProblem'].map(grab).join('\n') + (src.includes('function ovlNormalizeHex(') ? '\n' + grab('ovlNormalizeHex') : '');
const ctx = {}; vm.createContext(ctx); vm.runInContext(code + '\nthis.sdbKeyProblem = sdbKeyProblem; this.sdbValueProblem = sdbValueProblem; this.sdbQuotedBytes = sdbQuotedBytes; if (typeof ovlNormalizeHex !== "undefined") this.ovlNormalizeHex = ovlNormalizeHex;', ctx);
const hex = s => Buffer.from(s, 'utf8').toString('hex');
const keys = ['adb_enabled', 'a', '', '-x', '--user', 'a b', 'a\tb', 'a\nb', 'a=b', '\u0000', 'x'.repeat(256), 'x'.repeat(257), 'ünï', 'with\'quote', 'a/b', 'A-B', 'x@y+z', 'a b', 'a b', 'a​b', '﻿a', 'a\u0085b', 'a\u007fb', 'a\u009fb', '日本語', '😀emoji', 'tab\u000bvt', 'a　b', '=', '-', '.', '  '];
const vals = ['', '0', 'a b', 'it\'s', '$HOME', '`date`', '"q"', 'a;b', 'line1\nline2', 'tab\there', 'héllo ☕', 'x'.repeat(20000), 'x'.repeat(20001), 'a\u0000b', 'bell\u0007', 'esc\u001b[0m', 'a\rb', '\r', 'a\r\nb', 'ends\r', '\u000bvt', '\u000cff', '\u0008bs', '\u001funit', '\u007f', '\u0085', ' ', 'x y',
  "'".repeat(15499), "'".repeat(15500), "'".repeat(20000), '日'.repeat(20000), '日'.repeat(20001), '😀'.repeat(10000), '😀'.repeat(9000) + "'".repeat(2000), 'é'.repeat(20000), "a'".repeat(7750), "a'".repeat(7751), '\n'.repeat(20000), '\t'.repeat(20000)];
const hexes = ['#6750A4', '6750a4', ' #6750a4 ', '#FF6750A4', '00ABCDEF', '#abc', 'ABC', '000000', '#fff', '', ' ', '#', '##123456', '12345', '1234567', '#abcd', 'GGGGGG', '12 34 56', '#12345g', '0x123456', 'rgb(1,2,3)', '١٢٣٤٥٦', '６７５０Ａ４', '123456\n', '\n123456', '#123456;ls', ' 123456', '123456 ', '﻿123456', '\u0001123456\u0002', '12345\u0000', 'ffffffff', 'FFFFFFFF', '+123456', '-123456', '1e5555', 'ab cd', '#ABC\t'];
const ids = ['android.theme.customization.accent_color', 'com.android.systemui:overlay_name', 'a', 'x'.repeat(300), 'x'.repeat(301), 'com.foo$bar', 'weird\'quote', 'a;b', 'a$(x)', 'a>b', 'ünï.pkg', 'a/b', 'a@b+c', '', '-x', '--user', 'a b', 'a\tb', 'a\nb', '\u0000', 'a b', 'a b', '﻿a', 'a​b'];
// the earlier, larger set of hostile names and values
try { const old = JSON.parse(fs.readFileSync(CASES, 'utf8')); (old.keys || []).forEach(k => keys.push(k)); (old.vals || []).forEach(v => vals.push(v)); } catch (e) { console.log('(no earlier cases: ' + e.message + ')'); }
// and some more: every code point below 0x100 as a name and as a value (alone and inside text)
for (let c = 0; c < 0x100; c++) { const ch = String.fromCharCode(c); keys.push('a' + ch + 'b'); vals.push('a' + ch + 'b'); }
for (const c of [0x2028, 0x2029, 0x200b, 0x200c, 0x200d, 0x2060, 0xfeff, 0x00a0, 0x1680, 0x2000, 0x200a, 0x202f, 0x205f, 0x3000, 0x180e, 0x0085, 0xfffd, 0xe000]) { const ch = String.fromCharCode(c); keys.push('a' + ch + 'b'); vals.push('a' + ch + 'b'); }
const lines = [];
keys.forEach(k => lines.push('K ' + hex(k))); vals.forEach(v => lines.push('V ' + hex(v)) || lines.push('B ' + hex(v)));
hexes.forEach(h => lines.push('H ' + hex(h))); ids.forEach(i => lines.push('I ' + hex(i)));
fs.writeFileSync('parity_in.txt', lines.join('\n') + '\n');
const jv = cp.execSync('java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp ' + JSON.stringify(CLASSES) + ' com.bloatware.bingblop.ParityDump2 parity_in.txt 2>/dev/null', { encoding: 'utf8', env: Object.assign({}, process.env, { LC_ALL: 'C.UTF-8' }) }).trim().split('\n');
let diffs = 0, n = 0;
lines.forEach((l, i) => {
  const t = l[0], v = Buffer.from(l.slice(2), 'hex').toString('utf8');
  let js;
  if (t === 'K') js = ctx.sdbKeyProblem(v) ? '1' : '0';
  else if (t === 'V') js = ctx.sdbValueProblem(v) ? '1' : '0';
  else if (t === 'B') js = String(ctx.sdbQuotedBytes(v));
  else if (t === 'H') { if (!ctx.ovlNormalizeHex) return; const r = ctx.ovlNormalizeHex(v); js = r === null ? 'null' : r; }
  else if (t === 'I') return;                      // the page does not check overlay names itself: the app does
  n++;
  if (js !== jv[i]) { diffs++; console.log('DIFF', t, JSON.stringify(v.length > 40 ? v.slice(0, 40) + '…(' + v.length + ')' : v), 'js=' + js, 'java=' + jv[i]); }
});
console.log(n + ' cases compared, ' + diffs + ' differences');
process.exit(diffs ? 1 : 0);
