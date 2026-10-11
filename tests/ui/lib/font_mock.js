// The native side of the app font setting (Settings > Font), added to the bridge of another mock (inst_mock.js) with a second init script:
//   await page.addInitScript(inst.initScript, {...}); await page.addInitScript(fonts.initScript, { fonts: [...], fontFiles: { '/storage/emulated/0/Download/A.ttf': { meta: {...}, b64 } } });
// opts.fonts: what the search finds; opts.fontFiles: ref (a path or a content:// address) -> { meta: { family, style, name, kind, variable, size }, b64 } or { error };
// opts.current: the font in use at the start; opts.noAccess: the search says it cannot read storage; opts.holdScan: the search never ends; opts.delay: ms between answers;
// opts.noCancel: an older build without cancelFontScan (the search then runs to its end). cancelFontScan() stops a search that is running and answers {status:'cancelled'}.
exports.initScript = function (opts) {
  const files = opts.fontFiles || {};
  const st = window.__font = { current: opts.current || null, preview: null, calls: [] };
  const delay = opts.delay === undefined ? 15 : opts.delay;
  const b = window.AndroidBridge;
  let timers = [];
  b.scanFonts = function () {
    st.calls.push('scanFonts');
    if (opts.noAccess) { setTimeout(() => window.onFontScan(JSON.stringify({ status: 'noaccess' })), delay); return; }
    st.scanning = true; timers = [];
    [20, 60].forEach((p, i) => timers.push(setTimeout(() => window.onFontScanProgress && window.onFontScanProgress(JSON.stringify({ pct: p, msg: 'Searched Download (' + (i + 1) + ' of 3 folders)', found: i })), delay * (i + 1))));
    const finish = () => { st.scanning = false; window.onFontScan(JSON.stringify({ status: opts.scanError ? 'error' : 'ok', error: opts.scanError, fonts: opts.fonts || [], truncated: !!opts.truncated })); };
    if (opts.holdScan) st.finishScan = finish; else timers.push(setTimeout(finish, delay * 4));
  };
  if (!opts.noCancel) b.cancelFontScan = function () {
    st.calls.push('cancelFontScan');
    if (!st.scanning) return;
    st.scanning = false; timers.forEach(clearTimeout); st.finishScan = null;
    setTimeout(() => window.onFontScan(JSON.stringify({ status: 'cancelled' })), delay);
  };
  b.pickFontFile = function () { st.calls.push('pickFontFile'); setTimeout(() => window.onFontPicked(opts.pickRef || 'content://font/1'), delay); };
  b.fontPreview = function (ref) {
    st.calls.push('fontPreview:' + ref);
    setTimeout(() => {
      const e = files[ref];
      if (!e) { window.onFontPreview(JSON.stringify({ ok: false, error: 'The app cannot read that file.' })); return; }
      if (e.error) { window.onFontPreview(JSON.stringify({ ok: false, error: e.error })); return; }
      st.preview = e;
      window.onFontPreview(JSON.stringify(Object.assign({ ok: true }, e.meta)));
    }, delay);
  };
  b.fontData = function (which) { const e = which === 'current' ? st.current : st.preview; return e ? e.b64 : ''; };
  b.fontApply = function () {
    st.calls.push('fontApply');
    if (!st.preview) return JSON.stringify({ ok: false, error: 'There is no font to use.' });
    st.current = st.preview; st.preview = null;
    return JSON.stringify(Object.assign({ ok: true }, st.current.meta));
  };
  b.fontClear = function () { st.calls.push('fontClear'); st.current = null; st.preview = null; return JSON.stringify({ ok: true }); };
};
