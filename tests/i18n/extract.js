#!/usr/bin/env node
// Lists every string the interface can show, as the keys of a language dictionary, and writes tests/i18n/keys.json.
//
//   node i18n/extract.js                         from assets/index.html (it is also loaded in Chromium: for the sentences with bold text or links and for where each text is shown)
//   node i18n/extract.js --seen out/seen.jsonl   also takes in the texts a run of the UI scripts really showed (see capture_hook.js); they are kept in i18n/seen.json
//
// x: a string as it appears (white space collapsed). p: a string with changing parts {0} {1} ... (a conditional in a template is expanded into every sentence it can
// make, so "1 package" and "5 packages" are two entries). b: a sentence with an element inside, the elements numbered <1>..</1>, <2/>, <br> (as assets/i18n.js writes them).
// "ctx" tells the translators where a text is shown. Needs acorn and acorn-walk (npm install in tests/, or NODE_PATH) and Playwright.
'use strict';
const fs = require('fs');
const path = require('path');
const acorn = require('acorn');
const walk = require('acorn-walk');
const HERE = __dirname;
const REPO = path.resolve(HERE, '..', '..');
const PAGE_FILE = path.join(REPO, 'assets', 'index.html');
const argv = process.argv.slice(2);
const seenArg = argv.indexOf('--seen') >= 0 ? argv[argv.indexOf('--seen') + 1] : null;
const html = fs.readFileSync(PAGE_FILE, 'utf8');
const a = html.indexOf('<script>') + '<script>'.length, b = html.lastIndexOf('</script>');
const js = html.slice(a, b);
const staticHtml = (html.slice(0, a - '<script>'.length) + html.slice(b + '</script>'.length)).replace(/<style[\s\S]*?<\/style>/g, '');

const collapse = (s) => s.replace(/\s+/g, ' ').trim();
const hasLetters = (s) => /[^\s\d\W]|[^\x00-\x7f]/.test(s);
const keys = { x: new Map(), p: new Map(), b: new Map() };          // key -> { ctx: Set, seen }
const add = (kind, key, ctx) => {
  key = collapse(key.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&hellip;/g, '…').replace(/&middot;/g, '·').replace(/&rarr;/g, '→'));
  if (!key || !hasLetters(key.replace(/\{\d+\}/g, '').replace(/<\/?\d+\/?>/g, ''))) return null;
  { let n = 0; key = key.replace(/\{\d+\}/g, () => '{' + (n++) + '}'); }                      // holes are numbered by order of appearance in this string
  const m = keys[kind];
  if (!m.has(key)) m.set(key, { ctx: new Set() });
  if (ctx) m.get(key).ctx.add(ctx);
  return key;
};
// what the page itself hands to the translator (txT) or writes as a line of the Terminal (txNote) is text, whatever it looks like ("[shell closed]")
const forced = { x: new Set(), p: new Set() };

// ---------- markup: text between tags and the attributes that are shown ----------
const ATTR_RE = /\b(title|placeholder|aria-label|alt|label)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
function fromMarkup(s, ctx) {
  s.replace(ATTR_RE, (m, at, v1, v2) => { add('x', v1 !== undefined ? v1 : v2, ctx); return m; });
  s.replace(/<[^>]*>/g, '\u0001').split('\u0001').forEach(t => { if (!/\{\d+\}/.test(t)) add('x', t, ctx); else add('p', t, ctx); });
}
fromMarkup(staticHtml, 'html');

// ---------- script ----------
const ast = acorn.parse(js, { ecmaVersion: 'latest', locations: true, allowReturnOutsideFunction: true });
const MAXALT = 16;
// every way an expression can read as text: a list of alternatives, each a list of parts (a string, or null for a changing part)
function alts(node) {
  switch (node.type) {
    case 'Literal':
      if (typeof node.value === 'string') return [[node.value]];
      if (typeof node.value === 'number') return [[String(node.value)]];
      return [[null]];
    case 'TemplateLiteral': {
      let cur = [[]];
      node.quasis.forEach((q, i) => {
        cur = cur.map(x => x.concat([q.value.cooked]));
        if (i < node.expressions.length) cur = product(cur, alts(node.expressions[i]));
      });
      return cur.length > MAXALT ? [[null]] : cur;
    }
    case 'BinaryExpression':
      if (node.operator === '+' && ((isNum(node.right) && !hasStr(node.left)) || (isNum(node.left) && !hasStr(node.right)))) return [[null]];   // i + 1: a number, not text
      if (node.operator === '+') { const r = product(alts(node.left), alts(node.right)); return r.length > MAXALT ? [[null]] : r; }
      return [[null]];
    case 'ConditionalExpression': { const r = alts(node.consequent).concat(alts(node.alternate)); return r.length > MAXALT ? [[null]] : r; }
    case 'LogicalExpression':
      if (node.operator === '||' || node.operator === '??') { const l = alts(node.left), r = alts(node.right); return l.concat(r).length > MAXALT ? [[null]] : l.concat(r); }
      return [[null]];
    default: return [[null]];
  }
}
function isNum(n) { return n.type === 'Literal' && typeof n.value === 'number'; }
function hasStr(n) {
  return (n.type === 'Literal' && typeof n.value === 'string') || n.type === 'TemplateLiteral'
    || (n.type === 'BinaryExpression' && n.operator === '+' && (hasStr(n.left) || hasStr(n.right)))
    || (n.type === 'ConditionalExpression' && (hasStr(n.consequent) || hasStr(n.alternate)));
}
function product(A, B) { const out = []; for (const x of A) for (const y of B) out.push(x.concat(y)); return out; }
// two holes next to each other are one hole
const toKey = (parts) => { const merged = []; for (const p of parts) { if (p === null && merged.length && merged[merged.length - 1] === null) continue; merged.push(p); } let k = 0; return merged.map(p => p === null ? '{' + (k++) + '}' : p).join(''); };

const fnName = (anc) => {
  for (let i = anc.length - 1; i >= 0; i--) {
    const n = anc[i];
    if (n.type === 'FunctionDeclaration' && n.id) return n.id.name;
    if ((n.type === 'FunctionExpression' || n.type === 'ArrowFunctionExpression') && anc[i - 1]) {
      const p = anc[i - 1];
      if (p.type === 'VariableDeclarator' && p.id.name) return p.id.name;
      if (p.type === 'Property' && p.key && (p.key.name || p.key.value)) return String(p.key.name || p.key.value);
      if (p.type === 'AssignmentExpression' && p.left.property) return p.left.property.name;
    }
  }
  return 'top level';
};
const isCmp = (p, node) => p && ((p.type === 'BinaryExpression' && ['===', '!==', '==', '!=', 'in', 'instanceof'].includes(p.operator)) || (p.type === 'SwitchCase' && p.test === node) || (p.type === 'MemberExpression' && p.computed && p.property === node) || (p.type === 'Property' && p.key === node && !p.computed) || (p.type === 'ImportDeclaration'));
const top = (node, anc) => { const p = anc[anc.length - 2]; return !(p && ((p.type === 'BinaryExpression' && p.operator === '+') || (p.type === 'ConditionalExpression' && p.test !== node) || (p.type === 'LogicalExpression' && (p.operator === '||' || p.operator === '??')) || (p.type === 'TemplateLiteral'))); };

// what the coding agents are told, and shell commands the Terminal builds: never shown to a person
const neverShown = (anc) => anc.some(a => a.type === 'FunctionDeclaration' && a.id && /^(txSystemPrompt|txCliWrap|txCliLogin|txComplete|txReadFile|txWriteBytes|txApplyEdits|txCursorPrompt|txDebianCmd|agentSystem|askAgentAbout|openAppAsk|wlQueryFromText)$/.test(a.id.name))
  || anc.some(a => a.type === 'CallExpression' && a.callee.type === 'Identifier' && a.callee.name === 'agentBtnHtml')     // the lines an Ask agent button hands to the agent (shown only in a box that is not translated)
  || anc.some(a => a.type === 'VariableDeclarator' && a.id && a.id.name === 'WL_UA')        // the browser name the web lookup introduces itself with
  || (anc.some(a => a.type === 'VariableDeclarator' && a.id && a.id.name === 'TX_CLI') && anc.some(a => a.type === 'ArrowFunctionExpression' || a.type === 'FunctionExpression'));   // the command lines of the agents' own tools
// what the page tells a coding agent about its steps (<result ...>), and web addresses with changing parts: not text either
const forModel = (v) => /<\/?result\b/.test(v);
const isUrl = (key) => /^https?:\/\/\S*\{\d+\}\S*$/.test(key);

walk.ancestor(ast, {
  CallExpression(node, st, anc) {
    if (node.callee.type !== 'Identifier' || !/^(txT|txF|txNote)$/.test(node.callee.name) || !node.arguments.length) return;
    if (neverShown(anc)) return;
    const ctx = 'js ' + fnName(anc);
    alts(node.arguments[0]).forEach(parts => {
      parts = parts.slice();
      const L = parts.length - 1;                                                   // a Termux-style [note]: the words inside the brackets
      if (typeof parts[0] === 'string' && typeof parts[L] === 'string' && parts[0].charAt(0) === '[' && parts[L].slice(-1) === ']') {
        if (L === 0) parts[0] = parts[0].slice(1, -1); else { parts[0] = parts[0].slice(1); parts[L] = parts[L].slice(0, -1); }
      }
      const lit = parts.filter(p => typeof p === 'string').join('');
      if ((lit.match(/\p{L}/gu) || []).length < 2) return;                          // ^C
      const key = toKey(parts);
      if (forModel(key) || isUrl(key) || /[<>]/.test(key) || key.indexOf('\n') >= 0) return;
      const kind = /\{\d+\}/.test(key) ? 'p' : 'x', k = add(kind, key, ctx);
      if (k) forced[kind].add(k);
    });
  },
  Literal(node, st, anc) {
    if (typeof node.value !== 'string') return;
    const p = anc[anc.length - 2];
    if (isCmp(p, node)) return;
    if (anc.some(a => a.type === 'VariableDeclarator' && a.id && a.id.name === 'OVL_PRESETS')) return;       // the list of 700 color names: they stay as they are
    if (anc.some(a => a.type === 'VariableDeclarator' && a.id && a.id.name === 'SYN_WORDS')) return;          // per-language keyword lists for the code-colour tokenizer: not sentences
    if (neverShown(anc)) return;
    // a piece of a longer text ('Could not load: ' + error) is part of the template made from the whole expression
    if (p && p.type === 'BinaryExpression' && p.operator === '+' && /\{|\}/.test('') === false && !(p.left.type === 'Literal' && p.right.type === 'Literal')) return;
    const ctx = 'js ' + fnName(anc);
    // a string with line breaks: line by line (a break in a message is on purpose); markup in it: text between tags
    const v = node.value;
    if (forModel(v)) return;
    if (/[<>]/.test(v)) { fromMarkup(v, ctx); return; }
    if (v.indexOf('\n') >= 0) v.split('\n').forEach(l => add('x', l, ctx)); else add('x', v, ctx);
  },
  TemplateLiteral(node, st, anc) {
    if (!top(node, anc)) return;
    if (neverShown(anc)) return;
    const ctx = 'js ' + fnName(anc);
    alts(node).forEach(parts => emit(parts, ctx));
  },
  BinaryExpression(node, st, anc) {
    if (node.operator !== '+' || !top(node, anc)) return;
    if (neverShown(anc)) return;
    const ctx = 'js ' + fnName(anc);
    alts(node).forEach(parts => emit(parts, ctx));
  },
  ConditionalExpression(node, st, anc) {
    if (!top(node, anc)) return;
    if (neverShown(anc)) return;
    const ctx = 'js ' + fnName(anc);
    alts(node).forEach(parts => { if (parts.length === 1 && parts[0] === null) return; emit(parts, ctx); });
  },
});
function emit(parts, ctx) {
  const lit = parts.filter(p => typeof p === 'string').join('');
  if (!hasLetters(lit)) return;
  const key = toKey(parts);
  if (forModel(key) || isUrl(key)) return;
  if (/[<>]/.test(key)) { fromMarkup(key, ctx); return; }
  const lines = key.indexOf('\n') >= 0 ? key.split('\n') : [key];
  lines.forEach(l => { if (/\{\d+\}/.test(l)) add('p', l, ctx); else add('x', l, ctx); });
}

// ---------- what the page really showed ----------
const seen = new Set();
const seenFile = path.join(HERE, 'seen.json');
if (fs.existsSync(seenFile)) JSON.parse(fs.readFileSync(seenFile, 'utf8')).forEach(s => seen.add(s));
if (seenArg) {
  fs.readFileSync(seenArg, 'utf8').split('\n').filter(Boolean).forEach(l => JSON.parse(l).forEach(([kind, s]) => { seen.add(collapse(s)); s.split('\n').forEach(x => seen.add(collapse(x))); }));
}

const uiLike = (s) => /^[A-Z\p{Lu}]/u.test(s) && /[a-z]/.test(s) && !/[{}=<>\\|]|^[a-z]+\.[a-z.]+/.test(s) && (/\s/.test(s) || /[.!?:…]$/.test(s));
const label = (s) => /^(?:\d+(?:st|nd|rd|th) )?[A-Z][A-Za-z0-9 ’'&\-–—,.:;!?()/+·…]*$/.test(s) && s.length <= 48 && !/[a-z][A-Z]/.test(s) && !/_/.test(s) && !/^[A-Z0-9 \-]+$/.test(s) && /[a-z]/.test(s);
// what is plainly not text for a person: a color code, a path, a package or a file name, a code with digits or underscores
const isData = (k) => /^#?[0-9a-fA-F]{3,8}$/.test(k) || /^[\/~]/.test(k) || /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/.test(k) || /^[A-Z0-9_]+$/.test(k) && /[_0-9]/.test(k) || /^[\d\s.,:;%+\-–/×x*()]+$/.test(k) || /^[a-z]+[A-Z]\w*$/.test(k);

// only what the heuristics above would miss is worth keeping of what was seen: a text of the page that does not read like a label or a sentence (a lower case word, say)
if (seenArg) fs.writeFileSync(seenFile, JSON.stringify([...seen].filter(k => keys.x.has(k) && !isData(k) && !(uiLike(k) || label(k))).sort()));

const found = { x: [], p: [] };
for (const [k, v] of keys.x) {
  if (forced.x.has(k)) { found.x.push({ k, fn: [...v.ctx] }); continue; }
  if (isData(k)) continue;
  const bare = k.replace(/^[✕✦●■▸◂»‹]+\s+/, '');                                    // a button label may start with a symbol
  if (seen.has(k) || uiLike(bare) || label(bare)) found.x.push({ k, fn: [...v.ctx] });
}
for (const [k, v] of keys.p) {
  if (forced.p.has(k)) { found.p.push({ k, fn: [...v.ctx] }); continue; }
  const letters = k.replace(/\{\d+\}/g, '').replace(/[^\p{L}]/gu, '').length;
  if (letters < 4 || /^\{\d+\}\s*[\p{L}]{1,3}$/u.test(k)) continue;                 // "{0} s": too little text to tell from a name
  if (/[\[\]=;<>]|base64|\.[a-z]{2,4}$|^#|^\./.test(k) || !/\s|[:.!?…]/.test(k.replace(/\{\d+\}/g, ''))) continue;   // a selector, a data URL, a file name: not a sentence
  if (/^'?(AppFont|PreviewFont)'?, /.test(k)) continue;                                  // a font-family list
  found.p.push({ k, fn: [...v.ctx] });
}

(async () => {
  const { chromium } = require(path.join(REPO, 'tests', 'ui', 'lib', 'pw'));
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  await page.goto('file://' + PAGE_FILE);
  await page.waitForTimeout(800);
  const blocks = [...new Set(await page.evaluate(() => I18N.blocks(document.body)))];
  const tabs = await page.evaluate(() => TAB_DEFS.map(t => t.label.replace(/\n/g, ' ')));
  const places = await page.evaluate(() => {
    const names = { apps: 'Application Manager tab', 'saved-lists': 'Saved Applications tab', debloater: 'UAD-NG Debloater tab', installer: 'APK Installer tab', files: 'File Manager tab', terminal: 'Command-Line Interface tab', settings: 'Hidden Settings tab', overlays: 'RRO/Monet Customization tab', updates: 'App Updater tab', store: 'App Stores tab', logcat: 'Logcat Viewer tab', about: 'About tab', prefs: 'Settings (the gear in the header)' };
    const where = (el) => {
      const v = el.closest('.view-content'); if (v) { const k = v.id.replace(/^view-/, ''); return names[k] || v.id; }
      const m = el.closest('.modal-overlay'); if (m) return 'a sheet (' + m.id.replace(/Modal$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase() + ')';
      if (el.closest('header')) return 'the header';
      if (el.closest('#tabBar')) return 'the tab bar';
      return 'the page';
    };
    const out = {};
    const norm = s => s.replace(/\s+/g, ' ').trim();
    const addp = (t, el) => { t = norm(t); if (!t || !/[A-Za-z]/.test(t)) return; (out[t] = out[t] || new Set()).add(where(el)); };
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n; while ((n = w.nextNode())) { const p = n.parentElement; if (p && !/^(SCRIPT|STYLE)$/.test(p.tagName)) addp(n.nodeValue, p); }
    document.querySelectorAll('[title],[placeholder],[aria-label],[alt],optgroup[label]').forEach(e => ['title', 'placeholder', 'aria-label', 'alt', 'label'].forEach(a => { const v = e.getAttribute(a); if (v) addp(v, e); }));
    return Object.fromEntries(Object.entries(out).map(([k, v]) => [k, [...v]]));
  });
  // the text of a drop-down choice is shown on its own, even when a sentence elsewhere has the same words between its tags
  const alone = new Set(await page.evaluate(() => [...document.querySelectorAll('option')].map(o => o.textContent.replace(/\s+/g, ' ').trim())));
  await b.close();

  // the pieces of a sentence that has its own entry (the text between its tags) are not asked for separately when they are long: they are never shown alone
  const frag = new Set();
  blocks.forEach(s => s.split(/<\/?\d+\/?>|<br>/).forEach(t => { t = collapse(t); if (t.split(' ').length >= 3 && !alone.has(t)) frag.add(t); }));
  const TABS = new Set(tabs);
  const hint = (k, fn) => {
    let h;
    if (places[k]) h = ('in the ' + places[k].slice(0, 3).join(' / ')).replace(/^in the a sheet/, 'in a sheet').replace(/ \/ a sheet/g, ' / sheet');
    else if (!fn[0]) h = '';
    else if (fn[0] === 'html') h = 'static text of the page';
    else h = 'built by the code of: ' + fn[0].replace(/^js /, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
    return TABS.has(k) ? 'tab label (' + (h || 'tab bar') + ')' : h;
  };
  const out = {
    x: found.x.filter(e => !frag.has(e.k)).map(e => ({ k: e.k, ctx: hint(e.k, e.fn) })).sort((a, c) => a.k < c.k ? -1 : 1),
    p: found.p.map(e => ({ k: e.k, ctx: hint(e.k, e.fn) })).sort((a, c) => a.k < c.k ? -1 : 1),
    b: blocks.map(k => ({ k, ctx: hint(k, ['html']) })).sort((a, c) => a.k < c.k ? -1 : 1)
  };
  fs.writeFileSync(path.join(HERE, 'keys.json'), JSON.stringify(out, null, 0).replace(/\},\{/g, '},\n{').replace(/\],"p":\[/, '],\n"p":[').replace(/\],"b":\[/, '],\n"b":['));
  console.log('i18n/keys.json: x ' + out.x.length + ', p ' + out.p.length + ', b ' + out.b.length + ' (' + (out.x.length + out.p.length + out.b.length) + ' strings)');
})();
