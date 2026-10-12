// The speed test's side of the mock bridge: AndroidBridge.dnsProbeStart / dnsProbeCancel, as the native side is specified.
//   JS to Java: dnsProbeStart(requestJson) -> '{"ok":true,"id":"<id>"}' or '{"ok":false,"error":"..."}'; requestJson = {id, hosts[], samples, timeoutMs}
//               dnsProbeCancel(id)
//   Java to JS: window.onDnsProbe(json) once per host: {id, host, status: ok|dns|connect|tls|query|timeout|cancelled, ip, connectMs, tlsMs, queryMs, samples, okSamples, error},
//               then {id, done: true, count}
// Add it to a page after the other mock: page.addInitScript(dnsMock.initScript). The test drives the answers:
//   window.__dns.starts   every request that was started (parsed), window.__dns.cancels every id that was cancelled
//   __dnsEmit(obj)        calls window.onDnsProbe(JSON.stringify(obj)) now
//   __dnsOk(host, c, t, q, extra)   an ok answer for the current scan (times in ms) ; __dnsFail(host, status)  a failed one ; __dnsDone()  the final event
//   window.__dns.startReply   what dnsProbeStart answers (a JSON string) ; window.__dns.throwOnStart   make it throw
module.exports.initScript = function () {
  window.__dns = { starts: [], cancels: [], startReply: null, throwOnStart: false, noStart: false };
  const B = window.AndroidBridge || (window.AndroidBridge = {});
  if (!window.__dns.noStart) {
    B.dnsProbeStart = function (json) {
      if (window.__dns.throwOnStart) throw new Error('boom');
      const req = JSON.parse(json);
      window.__dns.starts.push(req);
      return window.__dns.startReply || JSON.stringify({ ok: true, id: req.id });
    };
    B.dnsProbeCancel = function (id) { window.__dns.cancels.push(id); };
  }
  const cur = () => window.__dns.starts[window.__dns.starts.length - 1];
  window.__dnsEmit = obj => window.onDnsProbe(JSON.stringify(obj));
  window.__dnsOk = (host, c, t, q, extra) => window.__dnsEmit(Object.assign({ id: cur().id, host, status: 'ok', ip: '203.0.113.9', connectMs: c, tlsMs: t, queryMs: q, samples: 3, okSamples: 3, error: '' }, extra || {}));
  window.__dnsFail = (host, status, extra) => window.__dnsEmit(Object.assign({ id: cur().id, host, status, ip: '', connectMs: null, tlsMs: null, queryMs: null, samples: 3, okSamples: 0, error: status }, extra || {}));
  window.__dnsDone = () => window.__dnsEmit({ id: cur().id, done: true, count: cur().hosts.length });
};
module.exports.noStart = function () { window.__dns && (window.__dns.noStart = true); delete (window.AndroidBridge || {}).dnsProbeStart; delete (window.AndroidBridge || {}).dnsProbeCancel; };
