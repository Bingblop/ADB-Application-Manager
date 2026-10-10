#!/usr/bin/env node
// Checks the dictionaries in assets/lang/ (and the language list of assets/i18n.js).
//
//   node i18n/check.js                 every dictionary, against the list of strings in i18n/keys.json
//   node i18n/check.js es ja           only these
//   node i18n/check.js --strict        a missing translation is an error (without it only a count is shown)
//   node i18n/check.js --lang-dir D    another folder of dictionaries (the default is assets/lang)
//
// An error: a dictionary that does not load, an entry that is not text, a changing part ({0}) or a numbered tag (<1>) that a translation lost, added or
// renamed, markup that is not one of the tags, a key that is not in the page any more. A warning: a long string left as it is, a language whose
// script does not show in most of its translations, strings with no translation yet.
'use strict';
const ENTITY = /&(gt|lt|amp|quot|apos|nbsp|#\d+|#x[0-9a-f]+);/i;
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');
const args = process.argv.slice(2);
const strict = args.includes('--strict');
const di = args.indexOf('--lang-dir');
const LANG_DIR = di >= 0 ? path.resolve(args[di + 1]) : path.join(ROOT, 'assets', 'lang');
const only = args.filter((a, i) => !a.startsWith('--') && (di < 0 || i !== di + 1));
const keysFile = path.join(__dirname, 'keys.json');
const KEYS = fs.existsSync(keysFile) ? JSON.parse(fs.readFileSync(keysFile, 'utf8')) : null;
const keyList = KEYS ? [].concat(KEYS.x, KEYS.p, KEYS.b || []).map(e => e.k) : [];
const keySet = new Set(keyList);

// the languages the page offers (assets/i18n.js)
const i18nSrc = fs.readFileSync(path.join(ROOT, 'assets', 'i18n.js'), 'utf8');
const offered = [...i18nSrc.matchAll(/\{ code: '([A-Za-z-]+)'/g)].map(m => m[1]).filter(c => c !== 'en');

const SCRIPT = { ru: /[Ѐ-ӿ]/, 'zh-CN': /[一-鿿]/, ja: /[぀-ヿ一-鿿]/, ko: /[가-힯]/, ar: /[؀-ۿ]/, hi: /[ऀ-ॿ]/ };
let errors = 0, warnings = 0;
const err = (code, msg) => { errors++; console.log('  ERROR ' + code + ': ' + msg); };
const warn = (code, msg) => { warnings++; console.log('  warn  ' + code + ': ' + msg); };

const holes = s => (s.match(/\{\d+\}/g) || []).sort().join(',');
const tags = s => (s.match(/<\/?\d+\/?>|<br>/g) || []).sort().join(',');

function load(code) {
  const file = path.join(LANG_DIR, code + '.js');
  const sandbox = { window: {} };
  sandbox.window.__LANGS = {};
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox, { filename: file, timeout: 5000 });
  return sandbox.window.__LANGS[code];
}

const files = fs.existsSync(LANG_DIR) ? fs.readdirSync(LANG_DIR).filter(f => /^[A-Za-z-]+\.js$/.test(f)).map(f => f.replace(/\.js$/, '')) : [];
for (const code of offered) if (!files.includes(code)) { errors++; console.log('ERROR ' + code + ': offered by i18n.js, but assets/lang/' + code + '.js is missing'); }
for (const code of files) if (!offered.includes(code)) { errors++; console.log('ERROR ' + code + ': assets/lang/' + code + '.js is not in the language list of i18n.js'); }

for (const code of files.filter(c => !only.length || only.includes(c))) {
  console.log(code);
  let d;
  try { d = load(code); } catch (e) { err(code, 'does not load: ' + e.message); continue; }
  if (!d || typeof d !== 'object' || typeof d.x !== 'object' || d.x === null || (d.p !== undefined && !Array.isArray(d.p))) { err(code, 'is not { x: {...}, p: [...] }'); continue; }
  const entries = Object.entries(d.x).concat((d.p || []).map(e => Array.isArray(e) && e.length === 2 ? e : [null, null]));
  let n = 0, same = 0, scripted = 0, scriptable = 0;
  for (const [k, v] of entries) {
    n++;
    if (typeof k !== 'string' || typeof v !== 'string') { err(code, 'an entry that is not text: ' + JSON.stringify([k, v]).slice(0, 80)); continue; }
    if (!k || k !== k.trim() || /\s{2}|[\n\t]/.test(k)) err(code, 'key with stray white space: ' + JSON.stringify(k).slice(0, 80));
    if (!v.trim()) { err(code, 'empty translation of ' + JSON.stringify(k).slice(0, 60)); continue; }
    if (v !== v.trim()) err(code, 'translation with white space at an end: ' + JSON.stringify(v).slice(0, 80));
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(v)) err(code, 'control character in the translation of ' + JSON.stringify(k).slice(0, 60));
    if (holes(k) !== holes(v)) err(code, 'changing parts differ: ' + JSON.stringify(k).slice(0, 70) + ' -> ' + JSON.stringify(v).slice(0, 70));
    if (tags(k) !== tags(v)) err(code, 'tags differ: ' + JSON.stringify(k).slice(0, 70) + ' -> ' + JSON.stringify(v).slice(0, 70));
    const own = (k.match(/<[A-Za-z][^<>]*>/g) || []).filter(x => !/^<br>$/.test(x));        // text that merely looks like a tag in the English ("<placeholders>") stays as it is
    const cut = s => own.reduce((acc, x) => acc.split(x).join(''), s), vv = cut(v), kk = cut(k);
    const loose = s => s.replace(/<\/?\d+\/?>|<br>/g, ''), nOf = (s, ch) => loose(s).split(ch).length - 1;
    // only the numbered tags of the English; a < or > that is plain text in the English ("size:>10mb") stays as it is
    if (/<[A-Za-z\/!?]/.test(loose(vv)) || nOf(vv, '<') > nOf(kk, '<') || nOf(vv, '>') > nOf(kk, '>')) err(code, 'markup that is not a numbered tag in ' + JSON.stringify(v).slice(0, 80));
    if (ENTITY.test(v) && !ENTITY.test(k)) err(code, 'an HTML entity (the page shows it as it is) in ' + JSON.stringify(v).slice(0, 80));
    for (const x of own) if (v.indexOf(x) < 0) err(code, 'the text ' + x + ' of the English is missing in ' + JSON.stringify(v).slice(0, 80));
    if (KEYS && !keySet.has(k)) err(code, 'a key the page does not have: ' + JSON.stringify(k).slice(0, 80));
    const words = k.replace(/\{\d+\}|<\/?\d+\/?>|<br>/g, ' ').split(/\s+/).filter(Boolean);
    if (words.length >= 3) {
      if (v === k) same++;
      if (SCRIPT[code]) { scriptable++; if (SCRIPT[code].test(v)) scripted++; }
    }
  }
  const longSame = same;
  if (longSame > 0 && !SCRIPT[code] && longSame / Math.max(1, n) > 0.06) warn(code, longSame + ' strings of three words or more are the same as the English (' + Math.round(100 * longSame / n) + '%)');
  if (SCRIPT[code] && scriptable && scripted / scriptable < 0.85) warn(code, 'only ' + Math.round(100 * scripted / scriptable) + '% of the longer strings contain ' + code + ' script');
  if (KEYS) {
    const have = new Set(Object.keys(d.x).concat((d.p || []).map(e => e[0])));
    const missing = keyList.filter(k => !have.has(k));
    if (missing.length) {
      const msg = missing.length + ' of ' + keyList.length + ' strings have no translation yet (e.g. ' + missing.slice(0, 3).map(k => JSON.stringify(k.slice(0, 40))).join(', ') + ')';
      if (strict) err(code, msg); else warn(code, msg);
    }
  }
  console.log('  ' + n + ' entries');
}
console.log(errors ? errors + ' error(s), ' + warnings + ' warning(s)' : 'no errors' + (warnings ? ', ' + warnings + ' warning(s)' : ''));
process.exit(errors ? 1 : 0);
