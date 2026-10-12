// The Extra Settings tab's own side of the mock bridge, added after sdb_mock (it reads the same fake settings database):
//   settingsPeek(ns, key)   -> '{"ok":true,"value":"..."}' from window.__db with the app's own rights ("null" for a key that is not there); every call is in window.__calls.peek
//   getTelemetry()          -> JSON.stringify(window.__tel.data), or window.__tel.raw when it is set (a broken or failing answer); window.__tel.calls counts the calls
//   openDisplaySettings()   -> window.__display counts the calls
// window.__tel.data starts as a plausible phone (15.8 GB of RAM, 256 GB of /data, a battery at 49% on battery); a test changes it as it likes.
module.exports.initScript = function () {
  const B = window.AndroidBridge || (window.AndroidBridge = {});
  window.__calls = window.__calls || { list: [], op: [] };
  window.__calls.peek = [];
  window.__display = 0;
  window.__tel = {
    calls: 0, raw: null,
    data: {
      ok: true,
      mem: { totalKb: 16534728, usedKb: 8928753, availableKb: 7605975, swapTotalKb: 8388604, swapUsedKb: 1310720 },
      storage: { totalBytes: 254722064384, freeBytes: 134212849664, usedBytes: 120509214720 },
      battery: { percent: 49, tempTenthsC: 312, voltageMv: 3912, plugged: 0, health: 2, status: 3, technology: 'Li-ion', present: true },
      uptimeMs: 186120000, model: 'SM-S918B', manufacturer: 'samsung', android: '14', sdk: 34, oneUi: '6.1', kernel: '5.15.94-android13-8-27940483-abS918BXXS4CXF2',
    },
  };
  B.settingsPeek = function (ns, key) {
    window.__calls.peek.push(ns + '/' + key);
    const t = window.__db && window.__db[ns];
    if (!t) return JSON.stringify({ ok: false, error: 'Unknown settings table' });
    return JSON.stringify({ ok: true, value: key in t ? t[key] : 'null' });
  };
  B.getTelemetry = function () {
    window.__tel.calls++;
    return window.__tel.raw != null ? window.__tel.raw : JSON.stringify(window.__tel.data);
  };
  B.openDisplaySettings = function () { window.__display++; };
};
