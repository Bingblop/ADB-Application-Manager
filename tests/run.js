#!/usr/bin/env node
// Runs the UI scripts in tests/ui against the real assets/index.html (headless Chromium, mock Android bridge) and judges each one.
//
//   node run.js                    every script
//   node run.js t4 t60             only these
//   node run.js --jobs 2           how many run side by side (default 3)
//   node run.js --update t4        record this run's output as the expected output (after a change you meant to make)
//   node run.js --no-compare       only look for failures, not for changes in what a script prints
//   node run.js --list             name and what each script covers
//
// A script passes when it exits with 0, prints no "FAIL ..." line or "N FAILED" summary, reports no page errors, and (unless
// --no-compare) prints what ui/expected/<name>.txt holds once the parts that change from run to run (clock readings, timings) are masked.
// Screenshots and the full output of every script are kept in tests/out/<name>/ (not committed).
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

const TESTS = __dirname;
const UI = path.join(TESTS, 'ui');
const EXPECTED = path.join(UI, 'expected');
const OUT = path.join(TESTS, 'out');

// Parts of the output that legitimately differ from run to run.
const MASKS = [
  [/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, '<iso time>'],       // 2026-10-03T08:30:05.123Z
  [/\b\d{1,2}:\d{2}:\d{2}(?: ?[AP]M)?\b/g, '<clock>'],                     // 8:30:05 AM, 08:30:05
  [/(?<![0-9])\d{14}(?![0-9])/g, '<stamp>'],                                // file names such as logcat_x_20261003083005.txt
  [/\b1[5-9]\d{11}\b/g, '<epoch ms>'],                                     // 1791019805123
];

function normalize(text) {
  let t = text.replace(/\r\n/g, '\n').replace(/[ \t]+$/gm, '').replace(/\n+$/, '') + '\n';
  for (const [re, to] of MASKS) t = t.replace(re, to);
  return t;
}

function scripts() {
  const num = f => { const m = /^t(\d+)\.js$/.exec(f); return m ? +m[1] : 1e6; };
  return fs.readdirSync(UI).filter(f => /^(t\d+|test)\.js$/.test(f)).sort((a, b) => num(a) - num(b) || a.localeCompare(b)).map(f => f.replace(/\.js$/, ''));
}

function describe(name) {
  const out = [];
  for (const l of fs.readFileSync(path.join(UI, name + '.js'), 'utf8').split('\n')) {
    if (l.startsWith('// ')) out.push(l.slice(3).trim()); else if (l.startsWith('//')) out.push(''); else break;
  }
  return out.join(' ').replace(/\s+/g, ' ').trim();
}

function runScript(name, timeoutS) {
  return new Promise(resolve => {
    const dir = path.join(OUT, name);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    const started = Date.now();
    const child = spawn(process.execPath, [path.join(UI, name + '.js')], { cwd: dir, env: Object.assign({}, process.env, { TEST_OUT: dir }) });
    let out = '';
    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { out += d; });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeoutS * 1000);
    child.on('close', code => {
      clearTimeout(timer);
      fs.writeFileSync(path.join(dir, 'output.txt'), out);
      resolve({ name, code, out, timedOut, seconds: (Date.now() - started) / 1000 });
    });
  });
}

// Why a run is a failure whatever it printed before: exit code, FAIL lines, a failure summary, page errors.
function failures(r) {
  const why = [];
  if (r.timedOut) why.push('timed out');
  else if (r.code !== 0) why.push('exit code ' + r.code);
  const lines = r.out.split('\n');
  const failLines = lines.filter(l => /^FAIL\b/.test(l) || /^\d+ FAILED$/.test(l));
  if (failLines.length) why.push(failLines.length + ' failing check' + (failLines.length > 1 ? 's' : '') + ': ' + failLines.slice(0, 3).map(l => l.slice(0, 500)).join(' | '));
  const pageErrors = lines.filter(l => /^errors: \[.+\]/.test(l) && !/^errors: \[\]/.test(l));
  if (pageErrors.length) why.push('page errors: ' + pageErrors[0].slice(0, 160));
  return why;
}

function diff(expected, actual, limit = 14) {
  const a = expected.split('\n'), b = actual.split('\n'), out = [];
  let i = 0, j = 0;
  while ((i < a.length || j < b.length) && out.length < limit) {
    if (a[i] === b[j]) { i++; j++; continue; }
    // resynchronise on the next common line within a short window
    let k = 1, found = false;
    for (; k < 40 && !found; k++) {
      if (j + k < b.length && a[i] === b[j + k]) { for (let x = 0; x < k; x++) out.push('+ ' + b[j + x].slice(0, 200)); j += k; found = true; }
      else if (i + k < a.length && a[i + k] === b[j]) { for (let x = 0; x < k; x++) out.push('- ' + a[i + x].slice(0, 200)); i += k; found = true; }
    }
    if (!found) { if (i < a.length) out.push('- ' + (a[i] || '').slice(0, 200)); if (j < b.length) out.push('+ ' + (b[j] || '').slice(0, 200)); i++; j++; }
  }
  return out;
}

async function main() {
  const args = process.argv.slice(2);
  let jobs = Math.min(3, os.cpus().length), timeoutS = 400, update = false, compare = true, list = false;
  const names = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--jobs') jobs = Math.max(1, +args[++i] || 1);
    else if (a === '--timeout') timeoutS = +args[++i] || 400;
    else if (a === '--update') update = true;
    else if (a === '--no-compare') compare = false;
    else if (a === '--list') list = true;
    else if (a === '-h' || a === '--help') { console.log(fs.readFileSync(__filename, 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n')); return 0; }
    else names.push(a.replace(/\.js$/, '').replace(/^.*\//, ''));
  }
  const all = scripts();
  const unknown = names.filter(n => !all.includes(n));
  if (unknown.length) { console.error('unknown script: ' + unknown.join(', ')); return 2; }
  const selected = names.length ? all.filter(n => names.includes(n)) : all;
  if (list) { for (const n of selected) console.log(n.padEnd(6), describe(n)); return 0; }

  fs.mkdirSync(EXPECTED, { recursive: true });
  const started = Date.now();
  const results = [];
  let next = 0;
  const worker = async () => {
    while (next < selected.length) {
      const name = selected[next++];
      const r = await runScript(name, timeoutS);
      r.why = failures(r);
      const norm = normalize(r.out);
      const file = path.join(EXPECTED, name + '.txt');
      if (update && r.why.length === 0) { fs.writeFileSync(file, norm); r.status = 'UPDATED'; }
      else if (r.why.length) r.status = 'FAIL';
      else if (!compare) r.status = 'PASS';
      else if (!fs.existsSync(file)) { r.status = 'NEW'; r.why = ['no expected output yet (run with --update ' + name + ' once it looks right)']; }
      else {
        const exp = fs.readFileSync(file, 'utf8');
        if (exp === norm) r.status = 'PASS';
        else { r.status = 'CHANGED'; r.why = ['output differs from ui/expected/' + name + '.txt']; r.diff = diff(exp, norm); }
      }
      results.push(r);
      const tag = r.status === 'PASS' || r.status === 'UPDATED' ? r.status : r.status + '  <<<<';
      console.log(`${String(results.length).padStart(2)}/${selected.length} ${name.padEnd(5)} ${tag.padEnd(9)} ${r.seconds.toFixed(0).padStart(3)}s  ${describe(name).slice(0, 90)}`);
      if (r.status !== 'PASS' && r.status !== 'UPDATED') {
        for (const w of r.why) console.log('        ' + w);
        for (const d of r.diff || []) console.log('        ' + d);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(jobs, selected.length) }, worker));

  const bad = results.filter(r => r.status !== 'PASS' && r.status !== 'UPDATED');
  const secs = ((Date.now() - started) / 1000).toFixed(0);
  console.log('');
  console.log(bad.length ? `${bad.length} of ${results.length} scripts need a look: ${bad.map(r => r.name + ' (' + r.status + ')').join(', ')}` : `All ${results.length} scripts ${update ? 'updated' : 'passed'} (${secs}s).`);
  return bad.length ? 1 : 0;
}

main().then(code => process.exit(code), e => { console.error(e); process.exit(2); });
