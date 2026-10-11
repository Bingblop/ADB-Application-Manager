#!/usr/bin/env node
// Finds data that a word of a dictionary would translate: names of apps, files and settings, paths, output. Names like these are not the app's text and
// must stay as they are in every language (assets/index.html lists them in I18N.skip(...)).
//
//   node i18n/audit.js              runs every UI script twice (about 10 minutes)
//   node i18n/audit.js t60 t61 ...  only these scripts
//
// First run: the UI scripts run in English with i18n/hook.js, which writes every string the mock app hands to the page. Then a pseudo-language is made: every
// string of keys.json and every one of those data strings "translated" to itself between ⟦ ⟧, and the scripts run again in it. Each place where ⟦data⟧ shows up is
// printed with the element it is in (a place whose texts are all words of the page is a false alarm). A place that is data goes into the list in
// assets/index.html (I18N.skip). What is printed is only about the places the scripts reach with their mock data.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const HERE = __dirname;
const TESTS = path.resolve(HERE, '..');
const REPO = path.resolve(TESTS, '..');
const OUT = path.join(TESTS, 'out', 'i18n-audit');
const only = process.argv.slice(2).filter(a => !a.startsWith('--'));
fs.mkdirSync(OUT, { recursive: true });
const run = (env) => spawnSync('node', ['run.js', '--no-compare', '--jobs', '4'].concat(only), { cwd: TESTS, env: Object.assign({}, process.env, env), stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8', maxBuffer: 1 << 28 });

const dataFile = path.join(OUT, 'data.jsonl'), leakFile = path.join(OUT, 'leaks.jsonl');
for (const f of [dataFile, leakFile]) if (fs.existsSync(f)) fs.unlinkSync(f);
console.log('1/2 collecting the data strings of the mock app ...');
run({ NODE_OPTIONS: '--require ' + path.join(HERE, 'hook.js'), DATA_OUT: dataFile });
const data = new Set();
fs.readFileSync(dataFile, 'utf8').split('\n').filter(Boolean).forEach(l => { try { JSON.parse(l).forEach(s => data.add(s)); } catch (e) { /* a torn line */ } });
fs.writeFileSync(path.join(OUT, 'data.json'), JSON.stringify([...data]));
console.log('    ' + data.size + ' data strings');

// the page, with a pseudo dictionary under an existing language code
const page = path.join(OUT, 'pg');
fs.mkdirSync(path.join(page, 'lang'), { recursive: true });
for (const f of ['index.html', 'i18n.js', 'changelog.md']) if (fs.existsSync(path.join(REPO, 'assets', f))) fs.copyFileSync(path.join(REPO, 'assets', f), path.join(page, f));
const keys = JSON.parse(fs.readFileSync(path.join(HERE, 'keys.json'), 'utf8'));
const mark = s => '⟦' + s + '⟧';
const x = {};
keys.x.concat(keys.b).forEach(e => { x[e.k] = mark(e.k); });
data.forEach(d => { if (!(d in x)) x[d] = mark(d); });
fs.writeFileSync(path.join(page, 'lang', 'es.js'), "(window.__LANGS = window.__LANGS || {})['es'] = " + JSON.stringify({ x, p: keys.p.map(e => [e.k, mark(e.k)]) }) + ';\n');

console.log('2/2 running again in the pseudo-language ...');
run({ NODE_OPTIONS: '--require ' + path.join(HERE, 'hook.js'), LEAK_OUT: leakFile, DATA_SET: path.join(OUT, 'data.json'), FORCE_LANG: 'es', PAGE_URL: 'file://' + path.join(page, 'index.html') });
const ui = new Set(keys.x.concat(keys.b).map(e => e.k));
const groups = new Map();
(fs.existsSync(leakFile) ? fs.readFileSync(leakFile, 'utf8').split('\n').filter(Boolean) : []).forEach(l => {
  const [script, kind, s, where] = JSON.parse(l);
  const sig = kind + ' ' + where.split(' < ').slice(0, 3).join(' < ');
  const g = groups.get(sig) || { n: 0, ui: 0, samples: new Set(), scripts: new Set() };
  groups.set(sig, g);
  g.n++; if (ui.has(s)) g.ui++; if (g.samples.size < 3) g.samples.add(s); g.scripts.add(script);
});
const list = [...groups.entries()].filter(([, g]) => g.ui < g.n).sort((a, b) => b[1].n - a[1].n);
console.log(list.length ? list.length + ' place(s) where data was translated (count, the texts, the element, a few samples):' : 'no data was translated');
for (const [sig, g] of list) console.log('  ' + g.n + '\t' + sig + '\t' + JSON.stringify([...g.samples]).slice(0, 100));
console.log('(whole messages made of a name or an error and toasts with a name in them show up here too; they are not elements of their own)');
