// Preload for the UI scripts (NODE_OPTIONS=--require this file). Two jobs, by environment:
//  DATA_OUT=file   writes every string the page gets from the (mock) app as data: the leaves of everything the page parses as JSON and the text the bridge returns
//  LEAK_OUT=file DATA_SET=file FORCE_LANG=es   with the pseudo dictionary in place (every key and every data string "translated" to ⟦text⟧): writes where a data string was translated
const fs = require('fs');
const path = require('path');
const DATA_OUT = process.env.DATA_OUT, LEAK_OUT = process.env.LEAK_OUT, FORCE = process.env.FORCE_LANG;
if (DATA_OUT || LEAK_OUT) {
  const pw = require('playwright');
  const dataSet = LEAK_OUT && process.env.DATA_SET ? JSON.parse(fs.readFileSync(process.env.DATA_SET, 'utf8')) : [];
  const script = path.basename(process.argv[1] || '?');
  const instrument = async (page) => {
    if (DATA_OUT) await page.exposeFunction('__dataCapture', s => { try { fs.appendFileSync(DATA_OUT, s + '\n'); } catch (e) {} });
    if (LEAK_OUT) await page.exposeFunction('__leak', s => { try { fs.appendFileSync(LEAK_OUT, JSON.stringify([script].concat(JSON.parse(s))) + '\n'); } catch (e) {} });
    await page.addInitScript((cfg) => {
      if (cfg.collect) {
        const seen = new Set(), queue = [];
        const flush = () => { if (queue.length && window.__dataCapture) window.__dataCapture(JSON.stringify(queue.splice(0))); };
        const add = (s) => { if (typeof s !== 'string') return; for (let l of s.split('\n')) { l = l.replace(/\s+/g, ' ').trim(); if (l.length < 2 || l.length > 160 || !/\p{L}/u.test(l) || seen.has(l)) continue; seen.add(l); queue.push(l); } };
        const leaves = (v, depth) => { if (depth > 8 || v === null) return; if (typeof v === 'string') add(v); else if (Array.isArray(v)) v.forEach(x => leaves(x, depth + 1)); else if (typeof v === 'object') Object.values(v).forEach(x => leaves(x, depth + 1)); };
        const parse = JSON.parse;
        JSON.parse = function () { const r = parse.apply(this, arguments); try { leaves(r, 0); } catch (e) {} return r; };
        document.addEventListener('input', e => { if (e.target && typeof e.target.value === 'string') add(e.target.value); }, true);
        window.__collectData = add;
        setInterval(flush, 300); window.addEventListener('pagehide', flush);
      }
      let real;
      Object.defineProperty(window, 'AndroidBridge', { configurable: true, get() {
        return real && new Proxy(real, { get(t, k) {
          const v = t[k];
          if (typeof v !== 'function') return v;
          return function () {
            if (k === 'loadSetting' && cfg.force && arguments[0] === 'lang') return JSON.stringify(cfg.force);
            const r = v.apply(t, arguments);
            if (cfg.collect && typeof r === 'string' && r.length < 20000 && window.__collectData) window.__collectData(r);
            return r;
          };
        } });
      }, set(v) { real = v; } });
      if (cfg.data.length) {
        const D = new Set(cfg.data);
        const where = (el) => { const out = []; for (let e = el; e && e.nodeType === 1 && out.length < 5; e = e.parentElement) out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.classList.length ? '.' + Array.from(e.classList).slice(0, 2).join('.') : '')); return out.join(' < '); };
        const sent = new Set();
        const look = (v, el, kind) => {
          if (!v || v.indexOf('⟦') < 0) return;
          const re = /⟦([^⟦⟧]+)⟧/g; let m;
          while ((m = re.exec(v))) {
            const inner = m[1].replace(/\s+/g, ' ').trim();
            if (!D.has(inner)) continue;
            const w = where(el), key = inner + '|' + w;
            if (sent.has(key)) continue; sent.add(key);
            window.__leak && window.__leak(JSON.stringify([kind, inner, w]));
          }
        };
        const scan = (root) => {
          if (root.nodeType === 3) { if (root.parentElement) look(root.nodeValue, root.parentElement, 'text'); return; }
          if (root.nodeType !== 1) return;
          ['title', 'placeholder', 'aria-label', 'alt'].forEach(a => { const v = root.getAttribute(a); if (v) look(v, root, a); });
          const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT); let n;
          while ((n = w.nextNode())) { if (n.nodeType === 3) { if (n.parentElement) look(n.nodeValue, n.parentElement, 'text'); } else ['title', 'placeholder', 'aria-label', 'alt'].forEach(a => { const v = n.getAttribute(a); if (v) look(v, n, a); }); }
        };
        const start = () => new MutationObserver(ms => { for (const m of ms) { if (m.type === 'childList') m.addedNodes.forEach(scan); else if (m.type === 'characterData') scan(m.target); else if (m.type === 'attributes') look(m.target.getAttribute(m.attributeName), m.target, m.attributeName); } }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['title', 'placeholder', 'aria-label', 'alt'] });
        if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
      }
    }, { force: FORCE, collect: !!DATA_OUT, data: dataSet });
  };
  const origLaunch = pw.chromium.launch.bind(pw.chromium);
  pw.chromium.launch = async (...a) => {
    const browser = await origLaunch(...a);
    const np = browser.newPage.bind(browser);
    browser.newPage = async (...b) => { const page = await np(...b); await instrument(page); return page; };
    const nc = browser.newContext.bind(browser);
    browser.newContext = async (...b) => { const ctx = await nc(...b); const cnp = ctx.newPage.bind(ctx); ctx.newPage = async (...c) => { const p = await cnp(...c); await instrument(p); return p; }; return ctx; };
    return browser;
  };
}
