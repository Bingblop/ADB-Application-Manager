#!/usr/bin/env node
// Tools for the language dictionaries in assets/lang/.
//
//   node i18n/dict.js chunks <code> [--dir DIR] [--size 400]
//        the strings of i18n/keys.json that assets/lang/<code>.js does not have yet (all of them when there is no such file), in files DIR/src_01.json, ...
//        (default DIR: out/i18n/<code>). Each entry is { id, en, ctx }: hand a file to a translator together with BRIEF.md.
//   node i18n/dict.js check <src.json> <out.json> <code>
//        a translated file ({ "<id>": "translation" }) against its source: every id once, nothing extra, the changing parts ({0}) and numbered tags (<1>..</1>, <2/>, <br>)
//        kept, no stray markup or white space, line breaks only in tab labels. Prints each problem, or OK.
//   node i18n/dict.js assemble <code> [--dir DIR]
//        reads DIR/out_NN.json (each next to its src_NN.json), merges them with the existing assets/lang/<code>.js and writes it again, one entry per line.
//        A language that is not in the list of assets/i18n.js has to be added there first (that is also what makes the drop-down show it).
//
// Then: node i18n/check.js <code>.
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..', '..');
const LANG_DIR = path.join(ROOT, 'assets', 'lang');
const argv = process.argv.slice(2);
const opt = (name, dflt) => { const i = argv.indexOf('--' + name); return i >= 0 ? argv[i + 1] : dflt; };
const cmd = argv[0];

const keys = () => JSON.parse(fs.readFileSync(path.join(HERE, 'keys.json'), 'utf8'));
const allKeys = () => { const k = keys(); let id = 0; const all = []; for (const kind of ['x', 'p', 'b']) for (const e of k[kind] || []) all.push({ id: id++, en: e.k, kind, ctx: e.ctx || '' }); return all; };

function load(file) {
  const sandbox = { window: { __LANGS: {} } };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox, { filename: file });
  const d = Object.values(sandbox.window.__LANGS)[0];
  const map = new Map();
  Object.entries(d.x || {}).forEach(([k, v]) => map.set(k, v));
  (d.p || []).forEach(e => map.set(e[0], e[1]));
  return map;
}
const langName = code => {
  const m = new RegExp("\\{ code: '" + code.replace(/[-]/g, '\\-') + "', name: '([^']+)'").exec(fs.readFileSync(path.join(ROOT, 'assets', 'i18n.js'), 'utf8'));
  if (!m) { console.error(code + ' is not in the language list of assets/i18n.js'); process.exit(2); }
  return m[1];
};

if (cmd === 'chunks') {
  const code = argv[1];
  if (!code) { console.error('usage: node i18n/dict.js chunks <code> [--dir DIR] [--size 400]'); process.exit(2); }
  const dir = opt('dir', path.join('out', 'i18n', code));
  const file = path.join(LANG_DIR, code + '.js');
  const have = fs.existsSync(file) ? load(file) : new Map();
  const todo = allKeys().filter(e => !have.has(e.en));
  const size = +opt('size', 400);
  fs.mkdirSync(dir, { recursive: true });
  let n = 0;
  for (let i = 0; i < todo.length; i += size) {
    n++;
    const name = 'src_' + String(n).padStart(2, '0') + '.json';
    fs.writeFileSync(path.join(dir, name), JSON.stringify(todo.slice(i, i + size).map(e => ({ id: e.id, en: e.en, ctx: e.ctx })), null, 0).replace(/\},\{/g, '},\n{'));
  }
  console.log(code + ': ' + todo.length + ' of ' + allKeys().length + ' strings to translate, in ' + n + ' file(s) in ' + dir);
} else if (cmd === 'check') {
  const [src, out, code] = argv.slice(1);
  if (!src || !out) { console.error('usage: node i18n/dict.js check <src.json> <out.json> <code>'); process.exit(2); }
  const S = JSON.parse(fs.readFileSync(src, 'utf8'));
  let O;
  try { O = JSON.parse(fs.readFileSync(out, 'utf8')); } catch (e) { console.log('PROBLEM: ' + out + ' is not valid JSON: ' + e.message); process.exit(1); }
  const holes = s => (s.match(/\{\d+\}/g) || []).sort().join(',');
  const tags = s => (s.match(/<\/?\d+\/?>|<br>/g) || []).sort().join(',');
  const SCRIPT = { ru: /[Ѐ-ӿ]/, 'zh-CN': /[一-鿿]/, ja: /[぀-ヿ一-鿿]/, ko: /[가-힯]/, ar: /[؀-ۿ]/, hi: /[ऀ-ॿ]/ };
  const problems = [], warns = [];
  const ids = new Set(S.map(e => String(e.id)));
  for (const k of Object.keys(O)) if (!ids.has(k)) problems.push('extra id ' + k + ' (not in the source)');
  let scripted = 0, scriptable = 0, same = 0, long = 0;
  for (const e of S) {
    const v = O[String(e.id)];
    const tag = '#' + e.id + ' ' + JSON.stringify(e.en.slice(0, 50));
    if (v === undefined) { problems.push('missing ' + tag); continue; }
    if (typeof v !== 'string') { problems.push('not text: ' + tag); continue; }
    if (!v.trim()) { problems.push('empty ' + tag); continue; }
    if (v !== v.trim()) problems.push('white space at an end of ' + tag + ' -> ' + JSON.stringify(v.slice(0, 60)));
    if (holes(e.en) !== holes(v)) problems.push('changing parts differ ' + tag + ' -> ' + JSON.stringify(v.slice(0, 80)) + ' (needs ' + (holes(e.en) || 'none') + ')');
    if (tags(e.en) !== tags(v)) problems.push('tags differ ' + tag + ' -> ' + JSON.stringify(v.slice(0, 80)) + ' (needs ' + (tags(e.en) || 'none') + ')');
    const own = (e.en.match(/<[A-Za-z][^<>]*>/g) || []).filter(x => !/^<br>$/.test(x));         // text that merely looks like a tag in the English ("<placeholders>") stays as it is
    const vv = own.reduce((acc, x) => acc.split(x).join(''), v);
    if (/<(?!\/?\d+\/?>|br>)/.test(vv) || />/.test(vv.replace(/<\/?\d+\/?>|<br>/g, ''))) problems.push('markup that is not a numbered tag in ' + tag + ' -> ' + JSON.stringify(v.slice(0, 80)));
    for (const x of own) if (v.indexOf(x) < 0) problems.push('the text ' + x + ' of the English is missing in ' + tag);
    if (/\n/.test(v)) {
      if (!/tab label/.test(e.ctx || '')) problems.push('a line break in ' + tag + ' (only tab labels may have one)');
      else if ((v.match(/\n/g) || []).length > 1 || v.split('\n').some(l => l.length > 12)) warns.push('tab label ' + tag + ' -> ' + JSON.stringify(v) + ': one break, lines of at most 12 characters');
    }
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(v)) problems.push('control character in ' + tag);
    const words = e.en.replace(/\{\d+\}|<\/?\d+\/?>|<br>/g, ' ').split(/\s+/).filter(Boolean);
    if (words.length >= 3) {
      long++;
      if (v === e.en) same++;
      if (SCRIPT[code]) { scriptable++; if (SCRIPT[code].test(v)) scripted++; }
    }
  }
  if (SCRIPT[code] && scriptable && scripted / scriptable < 0.85) warns.push('only ' + Math.round(100 * scripted / scriptable) + '% of the longer texts contain ' + code + ' script: did some stay in English?');
  if (!SCRIPT[code] && long && same / long > 0.1) warns.push(same + ' of ' + long + ' longer texts are identical to the English: translate them');
  problems.slice(0, 60).forEach(p => console.log('PROBLEM: ' + p));
  if (problems.length > 60) console.log('... and ' + (problems.length - 60) + ' more problems');
  warns.slice(0, 20).forEach(w => console.log('warning: ' + w));
  if (!problems.length) { console.log('OK (' + S.length + ' texts' + (warns.length ? ', ' + warns.length + ' warning(s)' : '') + ')'); process.exit(0); }
  process.exit(1);
} else if (cmd === 'assemble') {
  const code = argv[1];
  if (!code) { console.error('usage: node i18n/dict.js assemble <code> [--dir DIR]'); process.exit(2); }
  const name = langName(code);
  const dir = opt('dir', path.join('out', 'i18n', code));
  const dest = path.join(LANG_DIR, code + '.js');
  const map = fs.existsSync(dest) ? load(dest) : new Map();
  let got = 0;
  for (const f of fs.readdirSync(dir).filter(f => /^out_\d+\.json$/.test(f)).sort()) {
    const srcFile = path.join(dir, f.replace('out_', 'src_'));
    if (!fs.existsSync(srcFile)) { console.error(f + ' has no ' + path.basename(srcFile) + ' next to it'); process.exit(1); }
    const en = new Map(JSON.parse(fs.readFileSync(srcFile, 'utf8')).map(e => [String(e.id), e.en]));
    for (const [i, v] of Object.entries(JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')))) if (en.has(i) && typeof v === 'string') { map.set(en.get(i), v); got++; }
  }
  const all = allKeys().filter(e => map.has(e.en));
  const x = all.filter(e => e.kind !== 'p'), p = all.filter(e => e.kind === 'p');
  const lines = [];
  lines.push('// ' + name + ' (' + code + '): the interface of the app. A key is the English text of the page (see tests/i18n/keys.json); {0} marks a changing part, <1>..</1> an element inside a sentence.');
  lines.push("(window.__LANGS = window.__LANGS || {})['" + code + "'] = {");
  lines.push('x: {');
  x.forEach((e, i) => lines.push(JSON.stringify(e.en) + ': ' + JSON.stringify(map.get(e.en)) + (i < x.length - 1 ? ',' : '')));
  lines.push('},');
  lines.push('p: [');
  p.forEach((e, i) => lines.push('[' + JSON.stringify(e.en) + ', ' + JSON.stringify(map.get(e.en)) + ']' + (i < p.length - 1 ? ',' : '')));
  lines.push(']');
  lines.push('};');
  fs.mkdirSync(LANG_DIR, { recursive: true });
  fs.writeFileSync(dest, lines.join('\n') + '\n');
  console.log(code + ': ' + got + ' new, ' + x.length + ' x + ' + p.length + ' p written to assets/lang/' + code + '.js');
} else {
  console.error('usage: node i18n/dict.js chunks|check|assemble ...');
  process.exit(2);
}
