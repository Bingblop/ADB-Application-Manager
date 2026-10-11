#!/usr/bin/env node
// Pulls every fenced shell block (```sh, ```bash, ```shell) out of the Markdown docs and runs `bash -n` on it, so a line that bash cannot even
// parse is found before review. The classic case is a placeholder such as `APP=<package>`: bash reads `<package>` as input redirection.
//
//   node tests/docs/check-shell-blocks.js            check every block
//   node tests/docs/check-shell-blocks.js --list     list the blocks found (file:line, tag, state) and exit
//   node tests/docs/check-shell-blocks.js --self-test  test the checker itself against a tiny built-in fixture
//   node tests/docs/check-shell-blocks.js FILE ...   check only these Markdown files
//
// Scope: *.md in the repository root and under docs/ (never node_modules, tests/out, bin). Fences tagged sh, bash or shell are checked.
// Rules:
//   * Lines starting with "$ " are shell prompts: when a block has any, only those lines are checked (prompt stripped); the rest is output.
//   * An unquoted <name> argument is reported even where bash happens to accept it. Placeholders inside quotes, after a # comment or in a
//     here-document body are fine. Fix: write a concrete example value (APP=com.example.app), or quote it ("<package>").
//   * To skip a block that is illustrative by design, put <!-- no-shell-check --> on the line directly above its opening fence.
// Needs only Node and bash. Exit code 0 = all good, 1 = a block failed (or the self-test failed), 2 = usage error.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const SHELL_TAGS = new Set(['sh', 'bash', 'shell']);
const OPT_OUT = '<!-- no-shell-check -->';
const SKIP_DIRS = new Set(['node_modules', 'out', 'bin', '.git']);

// Every fenced block of a Markdown text: { tag, startLine (the opening fence, 1-based), lines, optOut }.
// A fence may sit inside a block quote ("> ```sh") or a list item (indented any number of columns): the quote markers and the fence's own indentation
// are taken off its lines, and the closing fence is looked for after the same prefix is taken off.
function extractBlocks(text) {
  const src = text.replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  const unquote = (line, quoted) => quoted ? line.replace(/^(?:\s*>)+ ?/, '') : line;
  for (let i = 0; i < src.length; i++) {
    const open = /^((?:\s*>)*)(\s*)(`{3,}|~{3,})\s*([^\s`]*)/.exec(src[i]);
    if (!open) continue;
    const quoted = open[1] !== '';
    const indent = open[2].length;
    const fence = open[3];
    const prev = i > 0 ? unquote(src[i - 1], quoted).trim() : '';
    const block = { tag: open[4].toLowerCase(), startLine: i + 1, lines: [], optOut: prev === OPT_OUT };
    let j = i + 1;
    const closeRe = new RegExp('^ {0,' + (indent + 3) + '}' + (fence[0] === '`' ? '`' : '~') + '{' + fence.length + ',}\\s*$');
    while (j < src.length && !closeRe.test(unquote(src[j], quoted))) {
      let line = unquote(src[j++], quoted);
      let k = 0;
      while (k < indent && line[k] === ' ') k++;
      block.lines.push(line.slice(k));
    }
    blocks.push(block);
    i = j; // resume after the closing fence
  }
  return blocks;
}

// The lines bash should see, each with the 1-based line in the Markdown file it came from.

// The delimiter of a here-document that starts at text[at] ("<<" or "<<-"): the shell word after it with its quotes removed, as bash reads it (E"O"F and 'EO'F
// and EO\F are all EOF). Returns { word, dash, end } or null when there is no word.
function heredocWord(text, at) {
  let i = at + 2;
  const dash = text[i] === '-';
  if (dash) i++;
  while (i < text.length && /[ \t]/.test(text[i])) i++;
  let word = '';
  let joined = false;      // the line ends in a backslash inside the word: bash joins the next line to it (EO\ + F is EOF)
  const start = i;
  while (i < text.length) {
    const c = text[i];
    if (/[\s;&|<>()]/.test(c)) break;
    if (c === "'") { const e = text.indexOf("'", i + 1); if (e < 0) { word += text.slice(i + 1); i = text.length; break; } word += text.slice(i + 1, e); i = e + 1; continue; }
    if (c === '"') {
      i++;
      while (i < text.length && text[i] !== '"') { if (text[i] === '\\' && /["\\$`]/.test(text[i + 1] || '')) i++; word += text[i]; i++; }
      i++;
      continue;
    }
    if (c === '\\') { if (i === text.length - 1) { joined = true; i++; break; } i++; word += text[i]; i++; continue; }
    word += c; i++;
  }
  return i > start ? { word, dash, joined, end: Math.min(i, text.length) } : null;      // the word may be empty after quote removal (<<'' ends at an empty line)
}

// One line of shell, read with the quotes, comments and arithmetic of the line (and of the lines before it, in st = { quote, arith }) in mind.
// Returns { code, heredocs, cont }: the text outside quotes and comments, the here-document delimiters declared outside them (a quoted delimiter counts;
// << inside arithmetic is a shift and <<< a here-string, neither is a here-document), and whether the line ends in a backslash that joins it to the next
// line (not one inside a comment or single quotes, and not the second of a pair).
function scanLine(text, st) {
  let code = '';
  let cont = false;
  const heredocs = [];
  let splitWord = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (st.quote) { if (c === st.quote) st.quote = null; else if (c === '\\' && st.quote === '"') { if (i === text.length - 1) cont = true; i++; } continue; }
    if (c === '\\') { if (i === text.length - 1) cont = true; i++; continue; }
    if (c === '"' || c === "'") { st.quote = c; continue; }
    // a word that starts with # is a comment; a word starts after white space or a shell operator (echo ok;# note)
    if (c === '#' && (i === 0 || /[\s;&|()<>]/.test(text[i - 1]))) break;
    if (c === '(' && text[i + 1] === '(' && (i === 0 || /[\s;&|$]/.test(text[i - 1]))) { st.arith++; i++; continue; }
    if (c === ')' && text[i + 1] === ')' && st.arith > 0) { st.arith--; i++; continue; }
    if (st.arith > 0) continue;      // inside $(( )) < and > compare numbers (echo $((x<y>z))): nothing there is a redirection, a here-document or a placeholder
    if (c === '<' && text[i + 1] === '<' && text[i + 2] === '<') { code += '<<<'; i += 2; continue; }      // a here-string: the word after it is not a delimiter
    if (c === '<' && text[i + 1] === '<' && st.arith === 0) {
      const hd = heredocWord(text, i);
      if (hd) { heredocs.push({ word: hd.word, dash: hd.dash }); if (hd.joined) splitWord = true; i = hd.end - 1; continue; }
    }
    code += c;
  }
  return { code, heredocs, cont, splitWord };
}

// Whether the block is a transcript with "$ " prompts: a prompt counts only where a command could start, not inside a here-document body or a quote that is
// still open (a script may contain a line like "$ literal" in its text). A bare "$" is a prompt too.
function hasPrompt(all) {
  const st = { quote: null, arith: 0 };
  let bodies = [];
  let pending = [];
  for (const l of all) {
    if (bodies.length) { const h = bodies[0]; if ((h.dash ? l.text.replace(/^\t+/, '') : l.text) === h.word) bodies.shift(); continue; }
    if (!st.quote && /^\s*\$(?: |$)/.test(l.text)) return true;
    const r0 = scanLine(l.text, st);
    pending = pending.concat(r0.heredocs);
    if (pending.length && !st.quote && !r0.cont) { bodies = pending; pending = []; }
  }
  return false;
}

function shellLines(block) {
  const all = block.lines.map((text, k) => ({ text, line: block.startLine + 1 + k }));
  if (!hasPrompt(all)) return all;
  const out = [];
  let continued = false;
  let st = { quote: null, arith: 0 };
  let waiting = [];   // here-documents declared by the command being read
  let active = [];    // here-documents whose body (and terminator) is being read: those lines are input, not output, and are kept
  const read = text => {
    const r = scanLine(text, st);
    const joined = r.cont || st.quote !== null;      // the line goes on (backslash, open quote): the here-document starts after the joined line
    continued = joined || /(?:\|\||&&|\|&|\|)\s*$/.test(r.code);      // the command goes on in the next line
    waiting = waiting.concat(r.heredocs);
    // bash reads a here-document right after the line that declares it, also when that line ends in | || && |& (the rest of the command comes after the terminator)
    if (!joined && waiting.length) { active = waiting; waiting = []; }
  };
  for (const l of all) {
    if (active.length) {
      out.push(l);
      const h = active[0];
      if ((h.dash ? l.text.replace(/^\t+/, '') : l.text) === h.word) active.shift();
      continue;
    }
    const m = /^\s*\$(?: (.*))?$/.exec(l.text);      // the prompt is "$ " (or a bare "$"); "$name" is output
    if (m) {
      const cmd = m[1] || '';
      out.push({ text: cmd, line: l.line });
      st = { quote: null, arith: 0 };
      read(cmd);
    } else if (continued) {
      out.push(l);
      read(l.text);
    }
  }
  return out;
}

// Unquoted <name> placeholders (outside quotes, comments and here-document bodies). Returns [{ line, text }].
// A quote that is still open at the end of a line stays open on the next one; a here-document marker counts only where it is code (not inside
// quotes or a comment), and its delimiter may be quoted.
function findPlaceholders(lines) {
  const found = [];
  let bodies = [];     // here-documents whose body is being read, in the order declared
  let pending = [];    // declared on the current command; their bodies start on the line after the command ends
  const st = { quote: null, arith: 0 };
  for (const { text, line } of lines) {
    // the terminator is the delimiter alone on the line: exact, except that <<- ignores leading tabs
    if (bodies.length) { const h = bodies[0]; if ((h.dash ? text.replace(/^\t+/, '') : text) === h.word) bodies.shift(); continue; }
    const { code, heredocs, cont, splitWord } = scanLine(text, st);
    pending = pending.concat(heredocs);
    if (splitWord) found.push({ line, text: '', split: true });
    const re = /<([A-Za-z][\w .:/-]*)>/g;
    let m;
    while ((m = re.exec(code))) {
      found.push({ line, text: m[0] });
    }
    // the bodies start after the line that declares them, joined with the next ones while it goes on (odd trailing backslash, or a quote still open);
    // a trailing | || && |& does not delay them: the rest of that command comes after the terminator
    if (pending.length && !st.quote && !cont) { bodies = pending; pending = []; }
  }
  return found;
}

// Check one block. Returns a list of { line, message }.
function checkBlock(block, bash) {
  const lines = shellLines(block);
  const problems = [];
  // a fixed language, so that the diagnostic can be read whatever the caller's locale is
  const r = spawnSync(bash || 'bash', ['-n'], { input: lines.map(l => l.text).join('\n') + '\n', encoding: 'utf8', env: Object.assign({}, process.env, { LC_ALL: 'C', LANG: 'C', LANGUAGE: 'C' }) });
  if (r.error) throw r.error;
  if (r.status !== 0) {
    const e = (r.stderr || '').split('\n').filter(Boolean)[0] || 'syntax error'; // the first message; the rest only echo the line
    const m = /line (\d+): (.*)$/.exec(e);
    const at = m && lines[+m[1] - 1];
    problems.push({ line: at ? at.line : block.startLine, message: 'bash -n: ' + (m ? m[2] : e) });
  }
  else {
    // bash -n only warns (exit 0) about a here-document that is never closed, so a missing terminator is read from the warning
    const w = /here-document at line (\d+) delimited by end-of-file \(wanted `([^']*)'\)/.exec(r.stderr || '');
    if (w) { const at = lines[+w[1] - 1]; problems.push({ line: at ? at.line : block.startLine, message: 'here-document is never closed (no line with only ' + w[2] + ')' }); }
  }
  for (const p of findPlaceholders(lines)) {
    if (p.split) { problems.push({ line: p.line, message: 'the here-document delimiter is split over two lines with a backslash; write it on one line (this checker cannot follow it)' }); continue; }
    problems.push({ line: p.line, message: 'unquoted placeholder ' + p.text + ' (bash reads <...> as redirection); use a concrete example value, e.g. com.example.app, or quote it' });
  }
  return problems;
}

// All blocks of a Markdown text with their verdict: state is 'ok', 'skipped' (opt-out marker) or 'FAIL'.
function checkText(text) {
  return extractBlocks(text).filter(b => SHELL_TAGS.has(b.tag)).map(b => {
    if (b.optOut) return { block: b, state: 'skipped', problems: [] };
    const problems = checkBlock(b);
    return { block: b, state: problems.length ? 'FAIL' : 'ok', problems };
  });
}

function markdownFiles() {
  const files = [];
  for (const f of fs.readdirSync(ROOT).sort()) if (/\.md$/i.test(f)) files.push(path.join(ROOT, f));
  const walk = dir => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (e.isDirectory()) { if (!SKIP_DIRS.has(e.name)) walk(path.join(dir, e.name)); }
      else if (/\.md$/i.test(e.name)) files.push(path.join(dir, e.name));
    }
  };
  if (fs.existsSync(path.join(ROOT, 'docs'))) walk(path.join(ROOT, 'docs'));
  return files;
}

function selfTest() {
  const fx = [
    '# Fixture', '',
    '```bash', 'APP=com.example.app', 'adb shell pm path "$APP"   # <package> in a comment is fine', 'echo "<package> in quotes is fine"', '```', '',
    '```sh', '$ adb devices', 'List of devices attached', '$ APP=com.example.app \\', '    && echo ok', '```', '',
    '```bash', 'APP=<package>', 'adb shell pm path "$APP"', '```', '',
    '<!-- no-shell-check -->', '```bash', 'APP=<package>', '```', '',
    '```bash', 'adb shell pm clear <package> out.txt', '```', '',
    '```json', '{"not": "shell", "x": <broken>}', '```', '',
    '```bash', 'cat <<\'EOF\'', '<html>', 'EOF', 'if true; then', '  echo broken', '```', '',
    '```sh', '$ adb shell pm list packages', '$<output-not-a-command>', '```', '',
    '```bash', 'echo "<<EOF"', 'adb shell pm path <package>', '```', '',
    '```bash', 'echo "first line', '<tag> second line"', '```', '',
    '```bash', '# see <<EOF later', 'adb shell pm path <package>', '```', '',
    '```sh', '$', '<output-after-a-bare-prompt>', '```', '',
    '```bash', 'echo ok;# <package> in a comment after an operator', 'echo done', '```', '',
    '```bash', 'cat <<END-DATA', '<html>', 'END-DATA', 'adb shell pm path <package>', '```', '',
    '```bash', 'mask=$((1 << FLAG))', 'adb shell pm path <package>', '```', '',
    '> ```sh', '> APP=<package>', '> ```', '',
    '- a list item', '', '      ```bash', '      APP=<package>', '      ```', '',
    '```sh', '$ echo a\\\\', 'if this output were shell it would not parse', '```', '',
    '```sh', '$ echo a \\', '    && echo ok', '```', '',
    '```bash', 'cat <<EOF', '  EOF', '<html>', 'EOF', 'echo done', '```', '',
    '```bash', 'cat <<-EOF', '\t<html>', '\tEOF', 'echo done', '```', '',
    '```bash', 'cat <<EOF', '<html>', 'EOF ', 'adb shell pm path <package>', 'EOF', '```', '',
    '```bash', 'cat <<FIRST <<SECOND', '<a>', 'SECOND', '<b>', 'FIRST', '<c>', 'SECOND', 'echo done', '```', '',
    '```bash', 'cat <<EOF \\', '  > out.txt; adb shell pm path <package> out.txt', '<html>', 'EOF', 'echo done', '```', '',
    '```bash', 'cat <<EOF; adb shell pm path <package> out.txt', '<html>', 'EOF', '```', '',
    '```bash', 'cat <<EOF |', '<html>', 'EOF', '  grep <pattern> out.txt', '```', '',
    '```bash', 'cat <<EOF |', '  grep x', '<html>', 'EOF', '```', '',
    '```bash', 'cat <<EOF && echo ok', '<html>', 'EOF', '```', '',
    '```bash', 'cat <<EOF || \\', '  true', '<html>', 'EOF', '```', '',
    '```sh', '$ cat <<EOF', '<html>', 'EOF', '$ adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ cat <<EOF', 'text', 'EOF', '$ if true; then', '```', '',
    '```sh', '$ cat <<EOF', '<html>', 'EOF', '$ echo done', 'done', '```', '',
    '```sh', '$ echo "<<EOF"', '<<EOF', '$ adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ echo ok # <<EOF', 'ok', '$ adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ echo $((1 << 2))', '4', '$ adb shell pm path <package> out.txt', '```', '',
    '```bash', 'cat <<<word', 'adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ cat <<<word', 'word', '$ adb shell pm path <package> out.txt', '```', '',
    '```bash', 'cat <<EOF', '<html>', 'echo done', '```', '',
    '```bash', 'cat <<E"O"F', '<a>', 'EOF', 'adb shell pm path <package> out.txt', '```', '',
    "```bash", "cat <<'EO'F", '<a>', 'EOF', 'adb shell pm path <package> out.txt', '```', '',
    '```bash', 'cat <<EO\\F', '<a>', 'EOF', 'adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ cat <<EOF |&', '<a>', 'EOF', '  cat', '$ adb shell pm path <package> out.txt', '```', '',
    '```bash', 'cat <<EOF |&', '<a>', 'EOF', '  grep <pattern> out.txt', '```', '',
    "```bash", "cat <<''", '<html>', '', 'adb shell pm path <package> out.txt', '```', '',
    "```bash", 'cat <<""', '<html>', '', 'echo done', '```', '',
    '```bash', 'cat <<EOF # note \\', '<html>', 'EOF', 'adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ echo hi # note \\', '<html>', '$ adb shell pm path <package> out.txt', '```', '',
    '```bash', 'cat <<EOF \\', '  > out.txt', '<html>', 'EOF', 'echo done', '```', '',
    '```bash', "echo 'a \\'", 'adb shell pm path <package> out.txt', '```', '',
    '```sh', '$ cat <<EOF |&', '<a>', 'EOF', '  grep <pattern> out.txt', '```', '',
    '```sh', '$ echo hi |&', '  grep <pattern> out.txt', '```', '',
    "```bash", "cat <<'EOF'", '$ literal', 'EOF', 'APP=<package>', '```', '',
    '```bash', 'echo "a', '$ b"', 'APP=<package>', '```', '',
    '```bash', 'cat <<EOF', '$ literal', 'EOF', 'echo done', '```', '',
    '```sh', '$ cat <<\'EOF\'', '<html>', 'EOF', '$ echo done', 'done', '```', '',
    '```bash', 'echo a', '    ```', 'APP=<package>', '```', '',
    '```bash', 'cat <<EO\\', 'F', '<a>', 'EOF', 'APP=<package>', '```', '',
    '```bash', 'echo $((x<y>z))', 'echo $(( 1 << 2 ))', '```', '',
    '```bash', 'echo $((1<2)) <package> out.txt', '```', '',
    '```sh', '$ echo $((x<y>z))', '0', '$ echo done', 'done', '```', '',
  ].join('\n');
  const got = checkText(fx).map(r => r.state + '@' + r.block.startLine + (r.problems.length ? ':' + [...new Set(r.problems.map(p => p.line))].join(',') : ''));
  const want = ['ok@3', 'ok@9', 'FAIL@16:17', 'skipped@22', 'FAIL@26:27', 'FAIL@34:34', 'ok@42', 'FAIL@47:49', 'ok@52', 'FAIL@57:59', 'ok@62', 'ok@67', 'FAIL@72:76', 'FAIL@79:81', 'FAIL@84:85', 'FAIL@90:91', 'ok@94', 'ok@99', 'ok@104', 'ok@112', 'ok@119', 'ok@127', 'FAIL@138:140', 'FAIL@146:147', 'FAIL@152:156', 'FAIL@159:159', 'ok@166', 'ok@172', 'FAIL@179:183', 'FAIL@186:186', 'ok@193', 'FAIL@201:204', 'FAIL@207:210', 'FAIL@213:216', 'FAIL@219:221', 'FAIL@224:227', 'FAIL@230:231', 'FAIL@236:240', 'FAIL@243:247', 'FAIL@250:254', 'FAIL@257:262', 'FAIL@265:269', 'FAIL@272:276', 'ok@279', 'FAIL@286:290', 'FAIL@293:296', 'ok@299', 'FAIL@307:309', 'FAIL@312:316', 'FAIL@319:321', 'FAIL@324:328', 'FAIL@331:334', 'ok@337', 'ok@344', 'FAIL@352:354,355', 'FAIL@358:363,359', 'ok@366', 'FAIL@371:372', 'ok@375'];
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(ok ? 'self-test: ok (' + got.length + ' blocks judged as expected)' : 'self-test: FAIL\n  got:  ' + got.join(' ') + '\n  want: ' + want.join(' '));
  return ok;
}

function main() {
  const args = process.argv.slice(2);
  const list = args.includes('--list');
  const unknown = args.filter(a => a.startsWith('--') && a !== '--list' && a !== '--self-test');
  if (unknown.length) { console.error('Unknown option ' + unknown[0] + '. Use --list, --self-test, or Markdown file names.'); process.exit(2); }
  const named = args.filter(a => !a.startsWith('--')).map(a => path.resolve(a));
  if (args.includes('--self-test')) {
    if (list || named.length) { console.error('--self-test takes no other option or file name.'); process.exit(2); }
    process.exit(selfTest() ? 0 : 1);
  }
  const files = named.length ? named : markdownFiles();
  let total = 0, failed = 0, skipped = 0;
  for (const file of files) {
    const rel = path.relative(ROOT, file) || file;
    let text;
    try { text = fs.readFileSync(file, 'utf8'); } catch (e) { console.error('Cannot read ' + rel + ': ' + e.message); process.exit(2); }
    for (const r of checkText(text)) {
      total++;
      if (r.state === 'skipped') skipped++;
      if (list) { console.log(rel + ':' + r.block.startLine + '  ' + r.block.tag + '  ' + r.state); continue; }
      if (r.state !== 'FAIL') continue;
      failed++;
      console.log('FAIL ' + rel + ':' + r.block.startLine + ' (```' + r.block.tag + ')');
      for (const p of r.problems) console.log('  ' + rel + ':' + p.line + ': ' + p.message);
    }
  }
  if (list) { console.log(total + ' shell blocks (' + skipped + ' opted out)'); return; }
  if (failed) {
    console.log('\n' + failed + ' of ' + total + ' shell blocks failed. Fix the block, or if it is illustrative by design put ' + OPT_OUT + ' on the line above its fence.');
    process.exit(1);
  }
  console.log('docs shell blocks: ' + total + ' checked, ' + skipped + ' opted out, none failed');
}

if (require.main === module) main();
module.exports = { extractBlocks, checkText, findPlaceholders };
