// The native side of the file manager (v7.2) as a small virtual file system in the page, for the UI scripts:
//   await page.addInitScript(fm.initScript, { files: { '/storage/emulated/0/a.txt': 'text', '/storage/emulated/0/Pics': null /* a folder */ , ... }, ... });
// Names, the three rules for a taken name (replace / skip / keep both, "name (1).ext") and the merge of folders follow src/.../FileOps.java.
// window.__fm: { fs, calls[], reads, writes, opened[] } for the test to look at.
// opts: files (path -> text, or null for a folder, or { size, bin: true } for something that is not text), access (default true), hold (the batch ends when
//   __fm.release() is called), changedOnDisk (the next fmWriteText with an mtime says the file changed), pdfPages, imageOk, fontB64, openWithError.
exports.initScript = function (opts) {
  opts = opts || {};
  const fm = window.__fm = { fs: {}, calls: [], writes: [], opened: [], cancels: 0, thumbReqs: [], mtime: 1000 };
  const kvStore = (opts.kv && Object.assign({}, opts.kv)) || {};
  const parentOf = p => { const i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); };
  const baseOf = p => p.slice(p.lastIndexOf('/') + 1);
  const norm = p => { p = String(p || '/').replace(/\/{2,}/g, '/'); return p.length > 1 ? p.replace(/\/+$/, '') : p; };
  const put = (p, v) => {
    p = norm(p);
    if (v === null || v === undefined) fm.fs[p] = { dir: true, mtime: fm.mtime++ };
    else if (typeof v === 'string') fm.fs[p] = { dir: false, text: v, size: v.length, mtime: fm.mtime++ };
    else fm.fs[p] = { dir: false, bin: true, size: v.size || 1000, mtime: fm.mtime++ };
    let q = parentOf(p);
    while (q !== '/' && !fm.fs[q]) { fm.fs[q] = { dir: true, mtime: fm.mtime++ }; q = parentOf(q); }
  };
  Object.keys(opts.files || {}).forEach(p => put(p, opts.files[p]));
  fm.put = put;
  const kids = d => Object.keys(fm.fs).filter(p => parentOf(p) === d && p !== d);
  const exists = p => !!fm.fs[p];
  const extStart = n => { const m = /\.tar\.(gz|bz2|xz|zst|lz4|z|lz|lzma)$/i.exec(n); if (m && n.length > m[0].length) return n.length - m[0].length; const i = n.lastIndexOf('.'); return i > 0 ? i : n.length; };
  const unique = (dir, n) => { if (!exists(dir + '/' + n)) return n; const e = extStart(n), stem = n.slice(0, e), ext = n.slice(e); for (let i = 1; i < 99999; i++) { const c = stem + ' (' + i + ')' + ext; if (!exists(dir + '/' + c)) return c; } return n; };
  const subtree = p => Object.keys(fm.fs).filter(q => q === p || q.startsWith(p + '/'));
  const copyTree = (src, dst) => { subtree(src).forEach(q => { const t = dst + q.slice(src.length); fm.fs[t] = Object.assign({}, fm.fs[q], { mtime: fm.mtime++ }); }); };
  const removeTree = p => subtree(p).forEach(q => delete fm.fs[q]);
  const policyOf = s => s === 'skip' ? 'skip' : (s === 'keep' || s === 'both') ? 'keep' : 'replace';

  // one item of a copy or move; returns 'done' | 'skip' | an error message
  function transfer(src, destDir, policy, move, st) {
    if (!exists(src)) return 'No such file or folder';
    if (fm.fs[src].dir && (destDir === src || destDir.startsWith(src + '/'))) return 'A folder cannot go inside itself';
    let target = destDir + '/' + baseOf(src), there = exists(target);
    if (there && target === src) { if (policy !== 'keep' || move) return 'skip'; }
    if (there) {
      const both = fm.fs[src].dir && fm.fs[target].dir;
      if (policy === 'skip' && !both) return 'skip';
      if (policy === 'keep') { target = destDir + '/' + unique(destDir, baseOf(src)); there = false; }
    }
    if (there && fm.fs[src].dir !== fm.fs[target].dir) return fm.fs[src].dir ? 'A file with that name is in the way' : 'A folder with that name is in the way';
    if (move && !there) { subtree(src).forEach(q => { fm.fs[target + q.slice(src.length)] = fm.fs[q]; delete fm.fs[q]; }); return 'done'; }
    if (!fm.fs[src].dir) { fm.fs[target] = Object.assign({}, fm.fs[src], { mtime: fm.mtime++ }); if (move) delete fm.fs[src]; return 'done'; }
    // a folder onto a folder: item by item
    if (!fm.fs[target]) fm.fs[target] = { dir: true, mtime: fm.mtime++ };
    let left = 0;
    kids(src).forEach(k => { const r = transfer(k, target, policy, move, st); if (r !== 'done') left++; if (r !== 'done' && r !== 'skip') st.err = r; });
    if (move && !left) delete fm.fs[src];
    return 'done';
  }

  const b = window.AndroidBridge = window.AndroidBridge || {};
  Object.assign(b, {
    vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    getWorkingMode() { return JSON.stringify({ activeMode: opts.priv ? 'shizuku' : 'standard', modeAvailable: true, isPrivileged: !!opts.priv, configuredMode: opts.priv ? 'shizuku' : 'standard', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: !!opts.priv, authorized: !!opts.priv }, rootAvailable: false }); },
    hasAllFilesAccess() { return opts.access !== false; },
    loadSetting(k) { return kvStore[k] === undefined ? '' : kvStore[k]; },
    saveSetting(k, v) { kvStore[k] = v; fm.calls.push('saveSetting:' + k); },
    fmList(path) {
      path = norm(path);
      fm.calls.push('fmList:' + path);
      if (!fm.fs[path] && path !== '/') return JSON.stringify({ path, error: 'No such folder' });
      if (opts.denied && opts.denied.indexOf(path) >= 0) return JSON.stringify({ path, error: 'Permission denied' });
      const entries = kids(path).map(p => { const n = fm.fs[p]; return { name: baseOf(p), isDir: !!n.dir, isLink: false, perms: (n.dir ? 'd' : '-') + 'rwx', size: n.dir ? 0 : (n.size || 0) }; });
      return JSON.stringify({ path, entries, source: 'file' });
    },
    fmOp(op, a, bb) {
      a = norm(a); bb = bb ? norm(bb) : bb;
      fm.calls.push('fmOp:' + op + ':' + a + (bb ? '->' + bb : ''));
      if (op === 'mkdir') { if (opts.opFail) return JSON.stringify({ ok: false, output: opts.opFail }); put(a, null); return JSON.stringify({ ok: true, output: 'OK' }); }
      if (op === 'touch') { if (opts.opFail) return JSON.stringify({ ok: false, output: opts.opFail }); if (!exists(a)) put(a, ''); return JSON.stringify({ ok: true, output: 'OK' }); }
      if (op === 'rm') { removeTree(a); return JSON.stringify({ ok: true, output: 'OK' }); }
      if (op === 'mv') { if (exists(bb) && !fm.fs[bb].dir) return JSON.stringify({ ok: false, output: 'A file or folder with that name is already there' }); const st = {}; const r = fm.fs[bb] && fm.fs[bb].dir ? transfer(a, bb, 'replace', true, st) : (subtree(a).forEach(q => { fm.fs[bb + q.slice(a.length)] = fm.fs[q]; delete fm.fs[q]; }), 'done'); return JSON.stringify({ ok: r === 'done', output: r === 'done' ? 'OK' : r }); }
      if (op === 'cp') { const st = {}; const r = transfer(a, bb, 'replace', false, st); return JSON.stringify({ ok: r === 'done', output: r === 'done' ? 'OK' : r }); }
      return JSON.stringify({ ok: false, output: 'unknown op' });
    },
    fmBatch(op, paths, dest) { return this.fmBatch2(op, paths, dest, 'replace'); },
    fmBatch2(op, pathsJson, dest, policy) {
      const paths = JSON.parse(pathsJson).map(norm);
      fm.calls.push('fmBatch2:' + op);
      fm.batch = { op, paths, dest: norm(dest || ''), policy };
      if (fm.busy) return 'busy';
      fm.busy = true;
      const finish = () => {
        const res = { op, ok: true, total: paths.length, done: 0, skipped: 0, cancelled: false, failed: [] };
        paths.forEach(p => {
          if (fm.cancelled) { res.cancelled = true; return; }
          if (op === 'rm') { if (exists(p)) { removeTree(p); res.done++; } else res.failed.push({ p, error: 'No such file or folder' }); return; }
          const st = {}; const r = transfer(p, norm(dest), policyOf(policy), op === 'mv', st);
          if (r === 'done' && !st.err) res.done++; else if (r === 'skip') res.skipped++; else res.failed.push({ p, error: st.err || r });
        });
        res.ok = !res.failed.length && !res.cancelled;
        fm.busy = false; fm.cancelled = false;
        window.onFmBatchDone(res);
      };
      setTimeout(() => window.onFmBatchProgress && window.onFmBatchProgress('Copying ' + baseOf(paths[0] || '') + '…'), 10);
      if (opts.hold) fm.release = finish; else setTimeout(finish, 40);
      return 'started';
    },
    fmBatchCancel() { fm.cancels++; fm.cancelled = true; if (fm.release) { const r = fm.release; fm.release = null; setTimeout(r, 10); } },
    fmReadText(path) {
      path = norm(path); fm.calls.push('fmReadText:' + path);
      const n = fm.fs[path];
      if (!n || n.dir) return JSON.stringify({ ok: false, error: 'No such file' });
      if (n.size > 2 * 1024 * 1024) return JSON.stringify({ ok: false, tooBig: true, error: 'This file is over 2 MB: too big to edit here.' });
      if (n.bin) return JSON.stringify({ ok: true, binary: true, size: n.size });
      return JSON.stringify({ ok: true, text: n.text, size: n.text.length, mtime: n.mtime, writable: !(opts.readonly || n.readonly) });
    },
    fmWriteText(path, text, expect) {
      path = norm(path);
      fm.writes.push({ path, text, expect });
      const n = fm.fs[path];
      if (opts.writeError) return JSON.stringify({ ok: false, error: opts.writeError });
      if (n && expect > 0 && (fm.changeOnDisk || Math.abs(n.mtime - expect) > 1)) { fm.changeOnDisk = false; return JSON.stringify({ ok: false, changed: true, error: 'The file changed since it was opened.' }); }
      put(path, text);
      return JSON.stringify({ ok: true, size: text.length, mtime: fm.fs[path].mtime });
    },
    fmThumbs(json, px) {
      const list = JSON.parse(json);
      fm.thumbReqs.push({ paths: list, px });
      list.forEach((p, i) => setTimeout(() => window.onFmThumb && window.onFmThumb(p, 'data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ'), 10 + i));
    },
    fmThumbCache(action) { fm.calls.push('fmThumbCache:' + action); const f = fm.cacheBytes || 3 * 1048576; if (action === 'clear') { fm.cacheBytes = 0; return JSON.stringify({ freed: f, bytes: 0 }); } return JSON.stringify({ bytes: f }); },
    fmImage(path, px) { fm.calls.push('fmImage:' + path + ':' + px); setTimeout(() => window.onFmImage(path, opts.imageOk === false ? '' : 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 640, 480), 15); },
    fmPdf(path, page, w) {
      fm.calls.push('fmPdf:' + path + ':' + page);
      const pages = opts.pdfPages || 3;
      setTimeout(() => window.onFmPdf(JSON.stringify(opts.pdfError ? { ok: false, error: opts.pdfError } : { ok: true, pages, page: Math.max(0, Math.min(pages - 1, page)), data: 'data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ' })), 15);
    },
    fmOpenWith(path, mime, chooser) {
      fm.opened.push({ path, mime, chooser });
      if (opts.openWithError) setTimeout(() => window.onFmOpenWith(JSON.stringify({ ok: false, error: opts.openWithError })), 15);
    },
    fmReadB64(path, maxKb) { fm.calls.push('fmReadB64:' + path); return opts.fontB64 || ''; },
    shareStoredFile(path, mime, name) { fm.calls.push('share:' + path); return ''; },
    fmInstall(path) { return JSON.stringify({ ok: true, ref: path }); },
  });
};
