// The test runners keep text whole however much a script or a Java suite prints (finding BLD-3). Both added each piece of a child's
// output to a string as it came (`out += chunk`), which decodes every pipe read (up to 64 KiB on Linux) on its own: a character cut in
// two by a read became two U+FFFD marks, so a long translated output could be recorded garbled and then differ from run to run. Here a
// child prints about 500 KB of two-, three- and four-byte characters (one long line, then many short ones) on stdout, and again on
// stderr, through tests/run.js (UI scripts) and tests/java/run.js (Java suites); each must collect exactly what was printed. No browser.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { runScript } = require('../run.js');
const { exec } = require('../java/run.js');

let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

// The text, made the same way here and in the child. Three-byte characters do not divide 65,536, so 64 KiB reads cut one in two.
function makeText() {
  const piece = 'Übersetzung: 日本語のテキスト, ελληνικά, 中文 😀 ';
  let long = '';
  while (long.length < 100000) long += piece;
  const lines = [];
  for (let i = 0; i < 4000; i++) lines.push(i + ' ' + piece.slice(i % 7) + '\n');
  return long + '\n' + lines.join('');
}
const expected = makeText();

const dir = process.env.TEST_OUT || fs.mkdtempSync(path.join(os.tmpdir(), 't505-'));
const probe = stream => {
  const file = path.join(dir, 'print-' + stream + '.js');
  fs.writeFileSync(file, makeText.toString() + '\nprocess.' + stream + '.write(makeText());\n');
  return file;
};

function judge(label, r) {
  const marks = (r.out.match(/\uFFFD/g) || []).length;
  let at = 0;
  while (at < r.out.length && r.out[at] === expected[at]) at++;
  const ok = r.code === 0 && !r.timedOut;
  check(label + ': exits with 0', ok, ok ? undefined : 'exit ' + r.code + (r.timedOut ? ', timed out' : ''));
  check(label + ': has no U+FFFD', marks === 0, marks ? marks + ' of them' : undefined);
  check(label + ': is exactly what was printed', r.out === expected,
    r.out === expected ? undefined : Buffer.byteLength(r.out) + ' of ' + Buffer.byteLength(expected) + ' bytes, first difference at character ' + at);
}

(async () => {
  console.log('printed: ' + Buffer.byteLength(expected) + ' bytes, ' + expected.split('\n').length + ' lines, the longest ' + Buffer.byteLength(expected.split('\n')[0]) + ' bytes');
  for (const stream of ['stdout', 'stderr']) {
    const file = probe(stream);
    judge('tests/run.js, a UI script printing on ' + stream, await runScript('t505-' + stream, 120, file));
    judge('tests/java/run.js, a Java suite printing on ' + stream, await exec(process.execPath, [file], { cwd: dir }, 120));
  }
  console.log(bad ? bad + ' FAILED' : 'ALL OK');
  process.exit(bad ? 1 : 0);
})();
