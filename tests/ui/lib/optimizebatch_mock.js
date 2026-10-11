// A minimal optimizeAppBatch/optimizeBatchCancel mock matching MainActivity's real native contract: loops over
// the given packages, calling the bridge's own optimizeApp once per step with a next-tick yield between steps,
// then reports window.onOptimizeBatchProgress before each step and window.onOptimizeBatchDone at the end. Call
// this AFTER the test's own addInitScript has set window.AndroidBridge with its own optimizeApp - this only adds
// the two batch-runner methods on top (the same split appbatch_mock.js uses for appActionBatch).
exports.installOptimizeBatchMock = function () {
    if (!window.AndroidBridge) return;
    window.AndroidBridge.optimizeAppBatch = function (pkgsJson, mode, force) {
        if (window.__optimizeBatchBusy) return 'busy';
        window.__optimizeBatchBusy = true;
        window.__optimizeBatchCancel = false;
        const pkgs = JSON.parse(pkgsJson);
        const total = pkgs.length;
        const rows = [];
        let done = 0, i = 0;
        const step = () => {
            if (window.__optimizeBatchCancel || i >= total) {
                window.__optimizeBatchBusy = false;
                window.onOptimizeBatchDone && window.onOptimizeBatchDone(JSON.stringify({
                    total: total, done: done, cancelled: window.__optimizeBatchCancel && i < total, rows: rows, ok: true
                }));
                return;
            }
            const pkg = pkgs[i];
            window.onOptimizeBatchProgress && window.onOptimizeBatchProgress(i, total, pkg);
            let ok, output;
            try { const r = JSON.parse(window.AndroidBridge.optimizeApp(pkg, mode, force)); ok = !!r.ok; output = r.output; }
            catch (e) { ok = false; output = String(e && e.message || e); }
            if (ok) done++;
            rows.push({ pkg: pkg, output: output, success: ok });
            i++;
            setTimeout(step, 0);
        };
        step();
        return 'started';
    };
    window.AndroidBridge.optimizeBatchCancel = function () { window.__optimizeBatchCancel = true; };
};
