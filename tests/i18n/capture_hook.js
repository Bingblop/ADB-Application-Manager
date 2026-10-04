// Preload for the UI scripts (NODE_OPTIONS=--require this file, STR_OUT=file): records every text, title, placeholder, aria-label and dialog message
// the page shows while a script runs, one JSON array per line. Used to see which strings the interface really contains.
const fs = require('fs');
const OUT = process.env.STR_OUT;
if (OUT) {
  const pw = require('playwright');
  const instrument = async (page) => {
    await page.exposeFunction('__strCapture', s => { try { fs.appendFileSync(OUT, s + '\n'); } catch (e) {} });
    await page.addInitScript(() => {
      const seen = new Set(), queue = [];
      const flush = () => { if (queue.length && window.__strCapture) { window.__strCapture(JSON.stringify(queue.splice(0))); } };
      const add = (s, kind) => { if (!s) return; s = s.replace(/[ \t\r\f\v]+/g, ' ').replace(/ ?\n ?/g, '\n').trim(); if (!s || !/\p{L}/u.test(s) || s.length > 400) return; const k = kind + '|' + s; if (seen.has(k)) return; seen.add(k); queue.push([kind, s]); };
      const scan = (root) => {
        if (!root) return;
        if (root.nodeType === 3) { const p = root.parentElement; if (p && !/^(SCRIPT|STYLE)$/.test(p.tagName)) add(root.nodeValue, 't'); return; }
        if (root.nodeType !== 1) return;
        if (/^(SCRIPT|STYLE)$/.test(root.tagName)) return;
        const attrs = (e) => { ['title', 'placeholder', 'aria-label', 'alt'].forEach(a => { const v = e.getAttribute && e.getAttribute(a); if (v) add(v, a); }); };
        attrs(root);
        root.querySelectorAll && root.querySelectorAll('[title],[placeholder],[aria-label],[alt]').forEach(attrs);
        const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let n; while ((n = w.nextNode())) { const p = n.parentElement; if (p && !/^(SCRIPT|STYLE)$/.test(p.tagName)) add(n.nodeValue, 't'); }
      };
      const start = () => {
        scan(document.body);
        new MutationObserver(ms => { for (const m of ms) { if (m.type === 'childList') m.addedNodes.forEach(scan); else if (m.type === 'characterData') scan(m.target); else if (m.type === 'attributes') { const v = m.target.getAttribute(m.attributeName); if (v) add(v, m.attributeName); } } })
          .observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['title', 'placeholder', 'aria-label', 'alt'] });
      };
      if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
      setInterval(flush, 300);
      window.addEventListener('pagehide', flush);
      ['confirm', 'alert', 'prompt'].forEach(f => { const o = window[f]; window[f] = function (m) { add(String(m), 'dlg'); flush(); return o.apply(this, arguments); }; });
    });
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
