#!/usr/bin/env node
// Builds assets/guide.js (the in-app Help Guide) from the fragments docs/guide/*.html. See docs/guide/README.md.
//
//   node docs/guide/build.js            check the fragments and write assets/guide.js
//   node docs/guide/build.js --check    only check
//   node docs/guide/build.js --strict   also fail when a section the guide must have is missing (the release check)
'use strict';
const fs = require('fs');
const path = require('path');

const DIR = __dirname;
const ROOT = path.resolve(DIR, '..', '..');
const OUT = path.join(ROOT, 'assets', 'guide.js');
const args = process.argv.slice(2);
const CHECK_ONLY = args.includes('--check');
const STRICT = args.includes('--strict');

// The table of contents: groups in order, and the sections of each in order. A section that is in a fragment but not listed here goes to the end of its group.
const GROUPS = [
  ['Start here', ['welcome', 'quick-start', 'install-and-update', 'basics', 'screen-tour']],
  ['Connect the app to your phone', ['modes-overview', 'mode-wireless', 'mode-tcp', 'mode-shizuku', 'mode-root', 'mode-readonly', 'app-permissions']],
  ['The tabs', ['tab-apps', 'apps-menu', 'apps-batch', 'apps-profiles', 'apps-backup', 'quick-tiles', 'tab-saved-lists', 'tab-debloater', 'debloater-safety', 'tab-installer', 'installer-splits',
    'tab-files', 'files-search', 'files-archives', 'files-storage', 'tab-terminal', 'terminal-agents', 'tab-devices', 'devices-add', 'devices-apps', 'devices-send', 'devices-console', 'devices-logcat',
    'devices-files', 'devices-settings', 'devices-display', 'devices-wear', 'tab-settings', 'settings-journal', 'tab-overlays', 'tab-updates', 'tab-store', 'tab-logcat', 'tab-taskmgr', 'tab-about']],
  ['Settings and looks', ['prefs', 'feature-list', 'themes']],
  ['How do I...?', []],
  ['Safety and help', ['safety', 'troubleshooting', 'faq', 'glossary', 'privacy', 'credits']],
];
const REQUIRED = [].concat(...GROUPS.map(g => g[1]));

const ALLOWED_TAGS = new Set(['p', 'ul', 'ol', 'li', 'b', 'i', 'em', 'strong', 'code', 'br', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'div', 'span', 'h4', 'h5', 'kbd', 'sup', 'sub']);
const VOID = new Set(['br']);
const ALLOWED_CLASSES = new Set(['hg-lead', 'hg-steps', 'hg-note', 'hg-warn', 'hg-danger', 'hg-table']);

const errors = [], warns = [];
const err = (where, msg) => errors.push(where + ': ' + msg);

function parseFragments() {
  const files = fs.readdirSync(DIR).filter(f => /^\d\d-.*\.html$/.test(f)).sort();
  const sections = [];
  for (const f of files) {
    const text = fs.readFileSync(path.join(DIR, f), 'utf8');
    const rx = /<section\b([^>]*)>([\s\S]*?)<\/section>/g;
    let m, last = 0, count = 0;
    while ((m = rx.exec(text))) {
      count++;
      const gap = text.slice(last, m.index).replace(/<!--[\s\S]*?-->/g, '').trim();
      if (gap) err(f, 'text outside a <section>: "' + gap.slice(0, 60).replace(/\s+/g, ' ') + '"');
      last = rx.lastIndex;
      const attr = n => { const a = new RegExp('\\b' + n + '="([^"]*)"').exec(m[1]); return a ? a[1] : ''; };
      sections.push({ id: attr('id'), title: attr('data-title'), group: attr('data-group'), html: m[2].trim(), file: f });
    }
    const tail = text.slice(last).replace(/<!--[\s\S]*?-->/g, '').trim();
    if (tail) err(f, 'text after the last </section>: "' + tail.slice(0, 60).replace(/\s+/g, ' ') + '"');
    if (!count) err(f, 'no <section> in it');
  }
  return sections;
}

function checkHtml(s) {
  const where = s.file + '#' + s.id;
  if (/`/.test(s.html)) err(where, 'a backtick (use <code>)');
  if (/\p{Extended_Pictographic}/u.test(s.html)) err(where, 'an emoji');
  if (/&(?![a-zA-Z]+;|#\d+;|#x[0-9a-fA-F]+;)/.test(s.html)) err(where, 'a bare & (write &amp;)');
  const stack = [];
  const rx = /<(\/?)([a-zA-Z][a-zA-Z0-9]*)\b([^>]*)>/g;
  let m;
  while ((m = rx.exec(s.html))) {
    const close = !!m[1], tag = m[2].toLowerCase(), attrs = m[3];
    if (!ALLOWED_TAGS.has(tag)) { err(where, 'the tag <' + tag + '> is not allowed'); continue; }
    if (close) {
      if (VOID.has(tag)) continue;
      const top = stack.pop();
      if (top !== tag) { err(where, 'closing </' + tag + '> where <' + (top || 'nothing') + '> is open, near "' + s.html.slice(Math.max(0, m.index - 40), m.index + 30).replace(/\s+/g, ' ') + '"'); return; }
      continue;
    }
    if (/\son\w+\s*=/i.test(attrs)) err(where, 'an event handler attribute in <' + tag + '>');
    const cls = /\bclass="([^"]*)"/.exec(attrs);
    if (cls) cls[1].split(/\s+/).filter(Boolean).forEach(c => { if (!ALLOWED_CLASSES.has(c)) err(where, 'the class "' + c + '" is not one of ' + Array.from(ALLOWED_CLASSES).join(', ')); });
    const left = attrs.replace(/\bclass="[^"]*"/, '').replace(/\bhref="[^"]*"/, '').trim();
    if (left && left !== '/') err(where, 'an attribute that is not allowed in <' + tag + '>: ' + left);
    if (tag === 'a') {
      const h = /\bhref="([^"]*)"/.exec(attrs);
      if (!h) err(where, '<a> without href');
      else if (!/^#[a-z0-9-]+$/.test(h[1]) && !/^https:\/\//.test(h[1])) err(where, 'a link that is neither #id nor https://: ' + h[1]);
    }
    if (!VOID.has(tag)) stack.push(tag);
  }
  if (stack.length) err(where, 'not closed: <' + stack.join('>, <') + '>');
}

function words(html) { return html.replace(/<[^>]+>/g, ' ').replace(/&[a-z#0-9]+;/gi, ' ').split(/\s+/).filter(Boolean).length; }

const sections = parseFragments();
const ids = new Set();
const groupNames = GROUPS.map(g => g[0]);
for (const s of sections) {
  const where = s.file + '#' + (s.id || '?');
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(s.id)) err(where, 'the id must be lower-case letters, digits and hyphens');
  if (ids.has(s.id)) err(where, 'the id is used twice');
  ids.add(s.id);
  if (!s.title) err(where, 'no data-title');
  if (!groupNames.includes(s.group)) err(where, 'data-group "' + s.group + '" is not one of: ' + groupNames.join(' | '));
  if (/^\s*<h3/i.test(s.html)) err(where, 'starts with an <h3>: the title is drawn from data-title');
  if (!s.html) err(where, 'empty');
  else checkHtml(s);
  const w = words(s.html);
  s.words = w;
  if (w < 120) warns.push(where + ': only ' + w + ' words');
}
for (const s of sections) {
  for (const m of s.html.matchAll(/href="#([a-z0-9-]+)"/g)) if (!ids.has(m[1])) err(s.file + '#' + s.id, 'a link to #' + m[1] + ', which is not a section');
}
const missing = REQUIRED.filter(id => !ids.has(id));
if (missing.length) (STRICT ? errors : warns).push('missing sections: ' + missing.join(', '));

// order: the groups, then the table above, then what is not listed
const ordered = [];
for (const [name, list] of GROUPS) {
  const inGroup = sections.filter(s => s.group === name);
  const rank = s => { const i = list.indexOf(s.id); return i < 0 ? list.length : i; };
  inGroup.map((s, i) => [s, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).forEach(x => ordered.push(x[0]));
}

if (errors.length) { console.error(errors.join('\n')); console.error('\n' + errors.length + ' problem' + (errors.length === 1 ? '' : 's') + ' in the guide fragments.'); process.exit(1); }
warns.forEach(w => console.log('note: ' + w));

const manifest = fs.readFileSync(path.join(ROOT, 'AndroidManifest.xml'), 'utf8');
const ver = (/android:versionName="([^"]*)"/.exec(manifest) || [])[1] || '';
const data = {
  version: ver.replace(/-Pro$/, ''),
  groups: groupNames.filter(n => ordered.some(s => s.group === n)),
  sections: ordered.map(s => ({ id: s.id, title: s.title, group: s.group, html: s.html })),
};
const total = ordered.reduce((n, s) => n + s.words, 0);
console.log(ordered.length + ' sections, ' + total + ' words, ' + data.groups.length + ' groups');
if (CHECK_ONLY) process.exit(0);
const LS = String.fromCharCode(0x2028), PS = String.fromCharCode(0x2029);          // line and paragraph separators end a line in JavaScript source
const json = JSON.stringify(data).replace(/<\/(script)/gi, '<\\/$1').split(LS).join('\\u2028').split(PS).join('\\u2029');
const js = '/* The in-app Help Guide. Generated by docs/guide/build.js from docs/guide/*.html: edit those, not this file. */\n' +
  'window.HELP_GUIDE = ' + json + ';\n' +
  'if (window.onHelpGuideLoaded) window.onHelpGuideLoaded();\n';
fs.writeFileSync(OUT, js);
console.log('wrote ' + path.relative(ROOT, OUT) + ' (' + Math.round(js.length / 1024) + ' KB)');
