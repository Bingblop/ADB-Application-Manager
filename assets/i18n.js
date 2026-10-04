/* Interface language. English is the page as it is written. For any other language a dictionary (lang/<code>.js) holds the translation of every
   string the page can show, and the strings are translated where they appear: text, sentences that mix text with bold or links, and the title,
   placeholder, aria-label and alt of an element. That happens when the language is chosen and again whenever the page adds or changes something.
   While the language is English none of this runs.

   A dictionary is   window.__LANGS['es'] = { x: { "English": "Translation" }, p: [ ["Freeze {0} apps", "Congelar {0} apps"] ] }
   x: a string as the page shows it (white space collapsed). p: a string with a changing part ({0}, {1} ... in order of appearance).
   A sentence with bold text or a link inside is written with numbered tags in place of the elements: "Tap <1>Install</1> to continue".
   An element or a part of the page with translate="no", or one that I18N.skip(selectors) names, is left alone (names of apps and files, command output, logs ...). */
(function () {
  'use strict';

  var LANGS = [
    { code: 'en', name: 'English', dir: 'ltr' },
    { code: 'es', name: 'Español', dir: 'ltr', locale: 'es' },
    { code: 'fr', name: 'Français', dir: 'ltr', locale: 'fr' },
    { code: 'de', name: 'Deutsch', dir: 'ltr', locale: 'de' },
    { code: 'pt-BR', name: 'Português (Brasil)', dir: 'ltr', locale: 'pt-BR' },
    { code: 'it', name: 'Italiano', dir: 'ltr', locale: 'it' },
    { code: 'ru', name: 'Русский', dir: 'ltr', locale: 'ru' },
    { code: 'zh-CN', name: '简体中文', dir: 'ltr', locale: 'zh-CN' },
    { code: 'ja', name: '日本語', dir: 'ltr', locale: 'ja' },
    { code: 'ko', name: '한국어', dir: 'ltr', locale: 'ko' },
    { code: 'ar', name: 'العربية', dir: 'rtl', locale: 'ar' },
    { code: 'hi', name: 'हिन्दी', dir: 'ltr', locale: 'hi' },
    { code: 'tr', name: 'Türkçe', dir: 'ltr', locale: 'tr' },
    { code: 'id', name: 'Bahasa Indonesia', dir: 'ltr', locale: 'id' }
  ];
  var ATTRS = ['title', 'placeholder', 'aria-label', 'alt'];
  var SKIP_TAGS = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, NOSCRIPT: 1, SVG: 1, CANVAS: 1 };
  var OBSERVE = { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS };
  var BLOCK_MAX = 60;                                        // an element with more children than this is a list, not a sentence

  var I = window.I18N = { code: 'en', name: 'English', dir: 'ltr', locale: undefined, langs: LANGS, active: false, t: t, set: set, skip: skip };
  window.__LANGS = window.__LANGS || {};

  var exact = null, tpls = null, observer = null, started = false, titleSrc = null;
  var textSrc = new WeakMap();   // text node -> { src, out }
  var attrSrc = new WeakMap();   // element -> { attribute: { src, out } }
  var blockSrc = new WeakMap();  // element -> { src: English sentence, out: what the page holds now, els: its elements in the English order }
  var misses = Object.create(null), missCount = 0;

  function find(code) { for (var i = 0; i < LANGS.length; i++) if (LANGS[i].code === code) return LANGS[i]; return null; }
  function collapse(s) { return s.replace(/\s+/g, ' ').trim(); }
  function esc(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  function hasLetters(s) { return /[^\s\d\W]|[^\x00-\x7f]/.test(s); }
  function tagName(el) { return el.tagName ? el.tagName.toUpperCase() : ''; }

  // ---------- dictionary ----------
  function build(d) {
    exact = Object.assign(Object.create(null), d.x || {});
    var list = [];
    (d.p || []).forEach(function (e) {
      var key = e[0], parts = key.split(/\{\d+\}/);
      if (parts.length < 2) { exact[key] = e[1]; return; }
      var lit = 0; parts.forEach(function (p) { lit += p.length; });
      list.push({ re: new RegExp('^' + parts.map(esc).join('([\\s\\S]*?)') + '$'), to: e[1], w: lit, first: parts[0].charAt(0) });
    });
    list.sort(function (a, b) { return b.w - a.w; });         // the template with the most fixed text wins
    tpls = list;
    misses = Object.create(null); missCount = 0;
  }

  // the translation of one collapsed string, or null. plain: no templates (sentences with elements, a changing part that is a known word)
  function lookup(key, plain) {
    var hit = exact[key];
    if (hit !== undefined) return hit;
    if (plain || !tpls.length || key.length > 300 || misses[key]) return null;
    for (var i = 0; i < tpls.length; i++) {
      var T = tpls[i];
      if (T.first && key.charAt(0) !== T.first) continue;
      var m = T.re.exec(key);
      if (!m) continue;
      return T.to.replace(/\{(\d+)\}/g, function (all, n) {
        var g = m[+n + 1];
        if (g === undefined) return all;
        var inner = exact[g];                                 // a changing part that is itself a known word ("System", "Enabled") is translated too
        return inner !== undefined ? inner : g;
      });
    }
    if (missCount > 4000) { misses = Object.create(null); missCount = 0; }
    misses[key] = 1; missCount++;
    return null;
  }

  // a text with its own line breaks: the whole text first, else line by line. White space at the ends stays.
  function translate(v) {
    var lead = /^\s*/.exec(v)[0];
    if (lead.length === v.length) return null;
    var trail = /\s*$/.exec(v)[0], core = v.slice(lead.length, v.length - trail.length);
    if (!hasLetters(core)) return null;
    var out = lookup(collapse(core));
    if (out === null && core.indexOf('\n') >= 0) {
      var any = false;
      out = core.split('\n').map(function (line) { var l = collapse(line); if (!l) return line; var r = lookup(l); if (r === null) return line; any = true; return r; }).join('\n');
      if (!any) out = null;
    }
    return out === null ? null : lead + out + trail;
  }

  function t(s) {
    if (!I.active || typeof s !== 'string' || !s) return s;
    var r = translate(s);
    return r === null ? s : r;
  }

  // ---------- where not to translate ----------
  var noSel = '';                                            // what the page says is data (names, paths, output), see skip()
  // skip('.app-name, #log'): the elements that match are data of the app, not its text: they are left as they are in every language
  function skip(sel) {
    noSel = noSel ? noSel + ',' + sel : sel;
    try {                                                    // data keeps the direction of its own text in a right-to-left language, as translate="no" does
      var st = document.createElement('style');
      st.textContent = sel.split(',').map(function (x) { return 'html[dir="rtl"] ' + x.trim(); }).join(',') + '{unicode-bidi:plaintext}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  function isNo(el) {
    if (!el.getAttribute) return false;
    if (el.getAttribute('translate') === 'no' || (el.classList && el.classList.contains('notranslate'))) return true;
    return noSel !== '' && el.matches(noSel);
  }
  function skipped(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentNode) { if (SKIP_TAGS[tagName(e)] || isNo(e)) return true; }
    return false;
  }

  // ---------- text nodes and attributes ----------
  function doText(n) {
    var rec = textSrc.get(n), v = n.nodeValue;
    if (rec && v === rec.out) return;                         // our own write
    var out = translate(v);
    if (out === null || out === v) { if (rec) textSrc.delete(n); return; }
    textSrc.set(n, { src: v, out: out });
    n.nodeValue = out;
  }
  function doAttrs(el) {
    for (var i = 0; i < ATTRS.length; i++) {
      var a = ATTRS[i], v = el.getAttribute(a);
      if (!v) continue;
      var m = attrSrc.get(el), rec = m && m[a];
      if (rec && v === rec.out) continue;
      var out = translate(v);
      if (out === null || out === v) { if (rec) delete m[a]; continue; }
      if (!m) { m = {}; attrSrc.set(el, m); }
      m[a] = { src: v, out: out };
      el.setAttribute(a, out);
    }
  }

  // ---------- sentences that mix text and inline markup ----------
  // "Tap <b>Install</b> to continue"  ->  "Tap <1>Install</1> to continue": the numbers follow the elements in order of appearance, an element with
  // no text of its own (an icon) or one that is not to be translated is "<2/>", a line break is "<br>".
  function mixed(el) {
    if (el.childNodes.length > BLOCK_MAX) return false;
    var text = false, kid = false;
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) { if (!text && hasLetters(c.nodeValue)) text = true; }
      else if (c.nodeType === 1 && !kid && (tagName(c) === 'BR' || hasLetters(c.textContent))) kid = true;
      if (text && kid) return true;
    }
    return false;
  }
  function serialize(el, els) {
    var s = '';
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) s += c.nodeValue;
      else if (c.nodeType === 1) {
        if (tagName(c) === 'BR') { s += '<br>'; continue; }
        els.push(c);
        var i = els.length;
        if (skipped(c) || !hasLetters(c.textContent)) s += '<' + i + '/>';
        else s += '<' + i + '>' + serialize(c, els) + '</' + i + '>';
      }
    }
    return s;
  }
  // puts the sentence s (with its numbered tags) into el, using the elements of els; they keep their attributes and their handlers
  function rebuild(el, els, s) {
    while (el.firstChild) el.removeChild(el.firstChild);
    var stack = [el], re = /<(\d+)\/>|<(\d+)>|<\/(\d+)>|<br>/g, last = 0, m;
    var put = function (node) { stack[stack.length - 1].appendChild(node); };
    while ((m = re.exec(s))) {
      if (m.index > last) put(document.createTextNode(s.slice(last, m.index)));
      last = re.lastIndex;
      if (m[0] === '<br>') put(document.createElement('br'));
      else if (m[1]) { var e1 = els[+m[1] - 1]; if (e1) put(e1); }
      else if (m[2]) {
        var e2 = els[+m[2] - 1] || document.createElement('span');
        if (els[+m[2] - 1]) { while (e2.firstChild) e2.removeChild(e2.firstChild); }
        put(e2); stack.push(e2);
      } else if (stack.length > 1) stack.pop();
    }
    if (last < s.length) put(document.createTextNode(s.slice(last)));
  }
  function doBlock(el) {
    var rec = blockSrc.get(el), els = [], raw = serialize(el, els), cur = collapse(raw);
    if (rec && cur === rec.out) return true;                  // what is in the page is what we put there
    var out = lookup(cur, true);
    if (out === null || out === cur) { if (rec) blockSrc.delete(el); return false; }
    var lead = /^\s*/.exec(raw)[0], trail = /\s*$/.exec(raw)[0];
    rebuild(el, els, lead + out + trail);
    els.forEach(doAttrs);
    blockSrc.set(el, { src: cur, out: collapse(serialize(el, [])), els: els, lead: lead, trail: trail });
    return true;
  }

  // ---------- walking ----------
  function doElement(el) {
    if (SKIP_TAGS[tagName(el)] || isNo(el)) {
      if (tagName(el) === 'TEXTAREA' || (el.getAttribute && el.getAttribute('translate') === 'no')) doAttrs(el);   // the text inside stays, the hint and label are the app's own words
      return;
    }
    doAttrs(el);
    if (mixed(el) && doBlock(el)) return;
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) doText(c);
      else if (c.nodeType === 1) doElement(c);
    }
  }
  function doNode(n) {
    if (n.nodeType === 3) {
      var p = n.parentNode;
      if (!p || p.nodeType !== 1 || !hasLetters(n.nodeValue) || skipped(p)) return;
      var rec = textSrc.get(n);
      if (rec && n.nodeValue === rec.out) return;
      if ((blockSrc.has(p) || mixed(p)) && doBlock(p)) return;
      doText(n);
    } else if (n.nodeType === 1) {
      var up = n.parentNode && n.parentNode.nodeType === 1 ? n.parentNode : null;
      if (up && skipped(up)) return;
      doElement(n);
      if (up && up !== document.body && up !== document.documentElement && (blockSrc.has(up) || mixed(up))) doBlock(up);   // a new element can complete a sentence
    }
  }
  function onMutations(recs) {
    observer.disconnect();
    try {
      for (var i = 0; i < recs.length; i++) {
        var r = recs[i], tg = r.target;
        if (r.type === 'childList') {
          for (var k = 0; k < r.addedNodes.length; k++) doNode(r.addedNodes[k]);
          if (r.removedNodes.length && tg.nodeType === 1 && !skipped(tg) && (blockSrc.has(tg) || mixed(tg))) doBlock(tg);
        } else if (r.type === 'characterData') doNode(tg);
        else if (r.type === 'attributes' && tg.nodeType === 1 && (!skipped(tg) || tagName(tg) === 'TEXTAREA')) doAttrs(tg);
      }
    } finally { observer.observe(document.documentElement, OBSERVE); }
  }

  // ---------- back to English ----------
  function restore(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, null), n, blocks = [];
    while ((n = w.nextNode())) {
      if (n.nodeType === 3) { var r = textSrc.get(n); if (r && n.nodeValue === r.out) n.nodeValue = r.src; }
      else {
        var m = attrSrc.get(n);
        if (m) for (var a in m) if (n.getAttribute(a) === m[a].out) n.setAttribute(a, m[a].src);
        if (blockSrc.has(n)) blocks.push(n);
      }
    }
    blocks.forEach(function (el) {
      var rec = blockSrc.get(el);
      if (rec && collapse(serialize(el, [])) === rec.out) rebuild(el, rec.els, rec.lead + rec.src + rec.trail);
      blockSrc.delete(el);
      if (rec) rec.els.forEach(function (e) { var am = attrSrc.get(e); if (am) for (var a in am) if (e.getAttribute(a) === am[a].out) e.setAttribute(a, am[a].src); });
    });
  }

  // ---------- switching ----------
  function apply(code, dict) {
    var L = find(code) || LANGS[0];
    if (observer) observer.disconnect();
    if (I.active && document.body) restore(document.body);
    if (titleSrc !== null) { document.title = titleSrc; titleSrc = null; }
    if (code === 'en' || !dict) {
      exact = tpls = null; I.active = false; I.code = 'en'; I.name = 'English'; I.dir = 'ltr'; I.locale = undefined;
    } else {
      build(dict); I.active = true; I.code = L.code; I.name = L.name; I.dir = L.dir || 'ltr'; I.locale = L.locale;
    }
    var root = document.documentElement;
    root.setAttribute('lang', I.code);
    root.setAttribute('dir', I.dir);
    if (I.active) {
      var tt = t(document.title); if (tt !== document.title) { titleSrc = document.title; document.title = tt; }
      if (document.body) doElement(document.body);
      if (!observer) observer = new MutationObserver(onMutations);
      observer.observe(root, OBSERVE);
    }
    try { document.dispatchEvent(new CustomEvent('i18nchange', { detail: { code: I.code } })); } catch (e) {}
  }

  // the sentences with an element inside (as the dictionary spells them), for the tools that make the dictionaries
  I.blocks = function (root) {
    var out = [];
    (function w(el) {
      if (SKIP_TAGS[tagName(el)] || isNo(el)) return;
      if (mixed(el)) { var k = collapse(serialize(el, [])); if (k) out.push(k); }
      for (var c = el.firstChild; c; c = c.nextSibling) if (c.nodeType === 1) w(c);
    })(root || document.body);
    return out;
  };

  function load(code) {
    return new Promise(function (resolve, reject) {
      if (window.__LANGS[code]) { resolve(window.__LANGS[code]); return; }
      var s = document.createElement('script');
      s.src = 'lang/' + code + '.js';
      s.onload = function () { window.__LANGS[code] ? resolve(window.__LANGS[code]) : reject(new Error('empty')); };
      s.onerror = function () { reject(new Error('missing')); };
      document.head.appendChild(s);
    });
  }
  var setSeq = 0;
  // set('es') loads the dictionary, then switches, and answers whether it worked. set('en') needs no file.
  function set(code) {
    if (!find(code)) return Promise.resolve(false);
    var my = ++setSeq;                                       // two quick picks: the last one wins, whatever order the files arrive in
    if (code === 'en') { apply('en'); return Promise.resolve(true); }
    return load(code).then(function (d) { if (my === setSeq) apply(code, d); return true; }, function () { return false; });
  }

  // ---------- start: the language chosen last time ----------
  var startCode = null;
  try {
    var raw = window.AndroidBridge && window.AndroidBridge.loadSetting ? window.AndroidBridge.loadSetting('lang') : localStorage.getItem('lang');
    var saved = raw ? JSON.parse(raw) : null;
    if (typeof saved === 'string' && saved !== 'en' && find(saved)) startCode = saved;
  } catch (e) {}
  if (startCode && !window.__LANGS[startCode]) {
    if (document.readyState === 'loading') { document.write('<script src="lang/' + startCode + '.js"><\/script>'); }   // synchronous: the dictionary is there before the page is drawn
    else load(startCode).then(function (d) { apply(startCode, d); }, function () {});
  }
  function begin() {
    if (started) return;
    started = true;
    if (startCode && window.__LANGS[startCode]) apply(startCode, window.__LANGS[startCode]);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', begin); else begin();

  // the dialogs of the browser take plain text
  ['alert', 'confirm', 'prompt'].forEach(function (f) {
    var o = window[f]; if (typeof o !== 'function') return;
    window[f] = function (m, d) { return arguments.length > 1 ? o.call(window, t(String(m)), d) : o.call(window, t(String(m))); };
  });
})();
