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
function extractBlocks(text) {
  const src = text.replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  for (let i = 0; i < src.length; i++) {
    const open = /^\s{0,3}(`{3,}|~{3,})\s*([^\s`]*)/.exec(src[i]);
    if (!open) continue;
    const fence = open[1];
    const block = { tag: open[2].toLowerCase(), startLine: i + 1, lines: [], optOut: i > 0 && src[i - 1].trim() === OPT_OUT };
    let j = i + 1;
    const closeRe = new RegExp('^\\s{0,3}' + (fence[0] === '`' ? '`' : '~') + '{' + fence.length + ',}\\s*$');
    while (j < src.length && !closeRe.test(src[j])) block.lines.push(src[j++]);
    blocks.push(block);
    i = j; // resume after the closing fence
  }
  return blocks;
}

// The lines bash should see, each with the 1-based line in the Markdown file it came from.
function shellLines(block) {
  const all = block.lines.map((text, k) => ({ text, line: block.startLine + 1 + k }));
  if (!all.some(l => /^\s*\$(?: |$)/.test(l.text))) return all;   // a bare "$" is a prompt too
  const out = [];
  let continued = false;
  for (const l of all) {
    const m = /^\s*\$(?: (.*))?$/.exec(l.text);      // the prompt is "$ " (or a bare "$"); "$name" is output
    if (m) { const cmd = m[1] || ''; out.push({ text: cmd, line: l.line }); continued = /\\$/.test(cmd); }
    else if (continued) { out.push(l); continued = /\\$/.test(l.text); }
  }
  return out;
}

// Unquoted <name> placeholders (outside quotes, comments and here-document bodies). Returns [{ line, text }].
// A quote that is still open at the end of a line stays open on the next one; a here-document marker counts only where it is code (not inside
// quotes or a comment), and its delimiter may be quoted.
function findPlaceholders(lines) {
  const found = [];
  let heredoc = null;
  let quote = null;
  for (const { text, line } of lines) {
    if (heredoc) { if (text.trim() === heredoc) heredoc = null; continue; }
    let code = '';
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) { if (c === quote) quote = null; else if (c === '\\' && quote === '"') i++; continue; }
      if (c === '\\') { i++; continue; }
      if (c === '"' || c === "'") { quote = c; continue; }
      if (c === '#' && (i === 0 || /\s/.test(text[i - 1]))) break;
      if (c === '<' && text[i + 1] === '<' && text[i + 2] !== '<') {      // <<< is a here-string, not a here-document
        const hd = /^<<-?\s*(?:'([^']+)'|"([^"]+)"|\\?([A-Za-z_]\w*))/.exec(text.slice(i));
        if (hd) { heredoc = hd[1] || hd[2] || hd[3]; i += hd[0].length - 1; continue; }
      }
      code += c;
    }
    const re = /<([A-Za-z][\w .:/-]*)>/g;
    let m;
    while ((m = re.exec(code))) {
      found.push({ line, text: m[0] });
    }
  }
  return found;
}

// Check one block. Returns a list of { line, message }.
function checkBlock(block, bash) {
  const lines = shellLines(block);
  const problems = [];
  const r = spawnSync(bash || 'bash', ['-n'], { input: lines.map(l => l.text).join('\n') + '\n', encoding: 'utf8' });
  if (r.error) throw r.error;
  if (r.status !== 0) {
    const e = (r.stderr || '').split('\n').filter(Boolean)[0] || 'syntax error'; // the first message; the rest only echo the line
    const m = /line (\d+): (.*)$/.exec(e);
    const at = m && lines[+m[1] - 1];
    problems.push({ line: at ? at.line : block.startLine, message: 'bash -n: ' + (m ? m[2] : e) });
  }
  for (const p of findPlaceholders(lines)) {
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
  ].join('\n');
  const got = checkText(fx).map(r => r.state + '@' + r.block.startLine + (r.problems.length ? ':' + [...new Set(r.problems.map(p => p.line))].join(',') : ''));
  const want = ['ok@3', 'ok@9', 'FAIL@16:17', 'skipped@22', 'FAIL@26:27', 'FAIL@34:34', 'ok@42', 'FAIL@47:49', 'ok@52', 'FAIL@57:59', 'ok@62'];
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(ok ? 'self-test: ok (' + got.length + ' blocks judged as expected)' : 'self-test: FAIL\n  got:  ' + got.join(' ') + '\n  want: ' + want.join(' '));
  return ok;
}

function main() {
  const args = process.argv.slice(2);
  const list = args.includes('--list');
  if (args.includes('--self-test')) process.exit(selfTest() ? 0 : 1);
  const unknown = args.filter(a => a.startsWith('--') && a !== '--list');
  if (unknown.length) { console.error('Unknown option ' + unknown[0] + '. Use --list, --self-test, or Markdown file names.'); process.exit(2); }
  const named = args.filter(a => !a.startsWith('--')).map(a => path.resolve(a));
  const files = named.length ? named : markdownFiles();
  let total = 0, failed = 0, skipped = 0;
  for (const file of files) {
    const rel = path.relative(ROOT, file) || file;
    for (const r of checkText(fs.readFileSync(file, 'utf8'))) {
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
