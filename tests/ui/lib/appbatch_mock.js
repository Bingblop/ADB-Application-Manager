// A minimal appActionBatch/appBatchCancel mock matching MainActivity's real native contract: loops over the
// given packages, calling the bridge's own executeAppAction once per step with a next-tick yield between steps
// (so a test can observe progress between apps and interrupt with appBatchCancel the same way the real batch
// loop can be stopped after whichever app it is already on), then reports window.onAppBatchProgress before each
// step and window.onAppBatchDone at the end. Call this AFTER the test's own addInitScript has set
// window.AndroidBridge with its own executeAppAction - this only adds the two batch-runner methods on top.
exports.installAppBatchMock = function () {
    if (!window.AndroidBridge) return;
    window.AndroidBridge.appActionBatch = function (action, pkgsJson) {
        if (window.__appBatchBusy) return 'busy';
        window.__appBatchBusy = true;
        window.__appBatchCancel = false;
        const pkgs = JSON.parse(pkgsJson);
        const total = pkgs.length;
        const rows = [];
        let done = 0, i = 0;
        const step = () => {
            if (window.__appBatchCancel || i >= total) {
                window.__appBatchBusy = false;
                window.onAppBatchDone && window.onAppBatchDone(JSON.stringify({
                    action: action, total: total, done: done,
                    cancelled: window.__appBatchCancel && i < total, rows: rows, ok: true
                }));
                return;
            }
            const pkg = pkgs[i];
            window.onAppBatchProgress && window.onAppBatchProgress(i, total, pkg);
            const flagged = window.AndroidBridge.executeAppAction(action, pkg);
            const s = String(flagged == null ? '' : flagged);
            let ok, output;
            if (s.charCodeAt(0) === 1) {
                ok = s.charAt(1) === '1'; output = s.slice(2);
            } else {
                const low = s.toLowerCase();
                ok = !(low.includes('error') || low.includes('failed') || low.includes('failure') || low.includes('exception'));
                output = s;
            }
            if (ok) done++;
            rows.push({ pkg: pkg, output: output, success: ok });
            i++;
            setTimeout(step, 0);
        };
        step();
        return 'started';
    };
    window.AndroidBridge.appBatchCancel = function () { window.__appBatchCancel = true; };
};
