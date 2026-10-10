// A stand-in AndroidBridge for the Terminal (v7.8) UI scripts.
//
//   const tx = require('./lib/tx_mock');
//   const env = await tx.install(page, { real: true });      // before page.goto
//
// Shells: with real: true every command runs in a persistent /bin/sh of this machine, one per shell (app / priv / termux), each
// in its own temporary folder (env.dirs.app ...), framed like the app's RishShell (exit status and folder after each command), so
// quoting, cd / export, base64 file writes and the agents' file steps are exercised for real. Without it, window.__tx.fake(b, cmd)
// answers (scripts can replace it).
// AI: window.__ai.queue holds the answers to aiRequest, first match wins: { match(spec) -> bool, status, sse: [chunks], body,
// headers, delay, hold }. sse chunks are delivered one by one (a chunk may end in the middle of an event: that is the point).
// tx.claudeSse(text), tx.openaiSse(text), tx.geminiSse(text) build realistic streams. window.__ai.reqs records every request
// (spec + parsed body), __ai.tests every key test; __ai.keyAnswer(provider, key, base) decides key tests.
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const RS = '\u001e';

function startShell(cwd) {
  const p = spawn('/bin/sh', [], { cwd, env: Object.assign({}, process.env, { HOME: cwd, PS1: '', TERM: 'dumb' }) });
  let buf = '';
  let waiting = null;
  const marker = RS + '@@TXM:';
  p.stdout.setEncoding('utf8');
  p.stdout.on('data', d => {
    buf += d;
    if (!waiting) return;
    const i = buf.indexOf(marker);
    if (i < 0) return;
    const j = buf.indexOf(RS, i + marker.length);
    if (j < 0) return;
    const out = buf.slice(0, i);
    const [code, dir] = buf.slice(i + marker.length, j).split('|');
    buf = buf.slice(j + 1);
    const w = waiting;
    waiting = null;
    w({ out, exit: parseInt(code, 10), cwd: dir });
  });
  p.stderr.on('data', () => {});
  let alive = true;
  p.on('exit', () => { alive = false; if (waiting) { const w = waiting; waiting = null; w({ out: buf, exit: -1, cwd, exited: true }); } });
  const q = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
  return {
    alive: () => alive,
    run(cmd) {
      return new Promise(resolve => {
        if (!alive) { resolve({ out: '', exit: -1, cwd, exited: true }); return; }
        waiting = resolve;
        p.stdin.write('{ sh -n -c ' + q(cmd) + ' && eval ' + q(cmd) + '; } </dev/null 2>&1; printf \'\\036@@TXM:%s|%s\\036\' "$?" "$PWD"\n');
      });
    },
    kill() { try { p.kill('SIGKILL'); } catch (e) {} }
  };
}

async function install(page, opts) {
  opts = opts || {};
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'txmock-'));
  const dirs = { app: path.join(base, 'app'), priv: path.join(base, 'priv'), termux: path.join(base, 'termux') };
  Object.values(dirs).forEach(d => fs.mkdirSync(d, { recursive: true }));
  const shells = {};
  if (opts.real) {
    await page.exposeFunction('__shellRun', async (b, cmd) => {
      if (!shells[b] || !shells[b].alive()) shells[b] = startShell(dirs[b] || base);
      return shells[b].run(cmd);
    });
  }
  await page.addInitScript(({ dirs, info, real }) => {
    window.__kv = window.__kv || {};
    const tx = window.__tx = {
      dirs, real,
      starts: [], runs: [], stops: [], closes: [], opens: [], appActions: [], perms: 0, probes: [], clip: '',
      startFail: {}, busy: {}, hold: {}, held: {}, realFor: {},
      info: Object.assign({ termux: { installed: true, permission: true, version: '0.118.3', installer: 'org.fdroid.fdroid' }, sessions: {}, home: dirs.app, sdk: 36, release: '17', model: 'SM-S948U1', manufacturer: 'samsung', abi: 'arm64-v8a' }, info || {}),
      kinds: { app: 'app', priv: 'shizuku', termux: 'termux' },
      uids: { app: 10234, priv: 2000, termux: 10345 },
      probeAnswer: { ok: true, message: '' },
      permAnswer: [true, false],
      fake(b, cmd) { return { out: '', exit: 0, cwd: dirs[b] }; },
    };
    const ai = window.__ai = { queue: [], reqs: [], cancels: [], tests: [], vault: {}, live: {}, keyAnswer: null };
    const deliver = (id, r) => {
      const st = ai.live[id] = { cancelled: false };
      const chunks = r.sse || [];
      let i = 0;
      const step = () => {
        if (st.cancelled) return;
        if (i < chunks.length) { window.onAiChunk(id, chunks[i++]); setTimeout(step, r.delay || 5); return; }
        delete ai.live[id];
        window.onAiDone(id, { status: r.status || 200, body: r.sse ? '' : (r.body || ''), error: r.error || '', cancelled: false, headers: r.headers || {} });
      };
      if (r.hold) { st.release = () => setTimeout(step, 1); return; }
      setTimeout(step, r.delay || 5);
    };
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; },
      isSystemDarkMode() { return true; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; }, hasAllFilesAccess() { return true; },
      loadSetting(k) { return window.__kv[k] !== undefined ? window.__kv[k] : ''; },
      saveSetting(k, v) { window.__kv[k] = v; },
      loadPackages() { return '[]'; },
      getWorkingMode() { return JSON.stringify({ activeMode: 'shizuku', modeAvailable: true, isPrivileged: true, configuredMode: 'shizuku', adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true }, rootAvailable: false }); },
      executeShell() { return ''; },
      copyToClipboard(t) { tx.clip = t; },
      openUrl(u) { tx.opens.push('url:' + u); },
      executeAppAction(a, pkg) { tx.appActions.push([a, pkg]); return 'Launched'; },
      termInfo() { return JSON.stringify(tx.info); },
      termStart(b, optsJson) {
        tx.starts.push([b, JSON.parse(optsJson || '{}')]);
        setTimeout(() => {
          const fail = tx.startFail[b];
          if (fail) { window.onTermStarted(b, { ok: false, message: fail }); return; }
          window.onTermStarted(b, { ok: true, kind: tx.kinds[b], uid: tx.uids[b], cwd: dirs[b], host: 'test', prompt: 'test:' + dirs[b] + ' $', reused: false });
        }, 10);
        return 'starting';
      },
      termRun(b, id, cmd, quiet, env) {
        tx.runs.push({ b, id, cmd, quiet, env: env ? JSON.parse(env) : null });
        if (tx.busy[b]) return 'busy';
        tx.busy[b] = true;
        const finish = r => {
          if (!quiet && r.out) window.onTermOutput(b, id, r.out);
          tx.busy[b] = false;
          window.onTermDone(b, id, { exit: r.exit, cwd: r.cwd || dirs[b], prompt: '', exited: !!r.exited, stopped: !!r.stopped, timedOut: false, restarted: false, revived: false, output: quiet ? (r.out || '') : undefined, error: r.error });
        };
        const go = () => (real && tx.realFor[b] !== false && window.__shellRun ? window.__shellRun(b, cmd) : Promise.resolve(tx.fake(b, cmd))).then(finish);
        if (tx.hold[b]) { tx.hold[b] = false; tx.held[b] = { finish, go, cmd }; return 'ok'; }
        setTimeout(go, 1);
        return 'ok';
      },
      termStop(b) { tx.stops.push(b); },
      termClose(b) { tx.closes.push(b); },
      termuxRequestPermission() {
        tx.perms++;
        if (!tx.info.termux.installed) return 'not_installed';
        if (tx.info.termux.permission) return 'granted';
        setTimeout(() => { tx.info.termux.permission = !!tx.permAnswer[0]; window.onTermuxPermission(tx.permAnswer[0], tx.permAnswer[1]); }, 10);
        return 'asked';
      },
      termuxProbe(id) { tx.probes.push(id); setTimeout(() => window.onTermuxProbe(id, tx.probeAnswer), 10); return 'started'; },
      termuxOpen(cmd) { tx.opens.push(cmd); return 'ok'; },
      aiRequest(id, specJson) {
        const spec = JSON.parse(specJson);
        let body = null;
        try { body = spec.body ? JSON.parse(spec.body) : null; } catch (e) {}
        ai.reqs.push(Object.assign({ id, json: body }, spec));
        const k = ai.queue.findIndex(q => !q.match || q.match(spec, body));
        if (k < 0) { setTimeout(() => window.onAiDone(id, { status: 0, body: '', error: 'Could not connect: nothing answers at that address', headers: {} }), 5); return 'started'; }
        const r = ai.queue[k];
        if (!r.keep) ai.queue.splice(k, 1);
        deliver(id, r);
        return 'started';
      },
      aiCancel(id) {
        ai.cancels.push(id);
        const st = ai.live[id];
        if (st) { st.cancelled = true; delete ai.live[id]; setTimeout(() => window.onAiDone(id, { status: 200, body: '', error: 'cancelled', cancelled: true, headers: {} }), 5); }
      },
      aiTestKey(id, provider, key, base) {
        ai.tests.push({ id, provider, key, base });
        const ans = ai.keyAnswer ? ai.keyAnswer(provider, key, base) : { ok: true, status: 200, body: '{"data":[]}' };
        setTimeout(() => {
          if (ans.ok) {
            ai.vault[provider] = { key: !!key, hint: key ? key.slice(0, 7) + '…' + key.slice(-4) : '', base: base || '', savedAt: 1790000000000, readable: true };
            if (!key) delete ai.vault[provider].hint;
          }
          window.onAiKeyTest(id, Object.assign({ base: base || '', hint: key ? key.slice(0, 7) + '…' + key.slice(-4) : '', headers: {} }, ans, { saved: !!ans.ok }));
        }, 10);
        return 'started';
      },
      aiSaveKey(provider, key, base) { ai.vault[provider] = { key: !!key, hint: key ? key.slice(0, 7) + '…' + key.slice(-4) : '', base: base || '', savedAt: 1790000000000, readable: true }; return 'ok'; },
      aiForgetKey(provider) { delete ai.vault[provider]; },
      aiVaultStatus() { return JSON.stringify(ai.vault); },
    };
  }, { dirs, info: opts.info || null, real: !!opts.real });
  return {
    dirs, base,
    read: (b, rel) => fs.readFileSync(path.join(dirs[b], rel), 'utf8'),
    write: (b, rel, text) => { fs.mkdirSync(path.dirname(path.join(dirs[b], rel)), { recursive: true }); fs.writeFileSync(path.join(dirs[b], rel), text); },
    exists: (b, rel) => fs.existsSync(path.join(dirs[b], rel)),
    close: () => { Object.values(shells).forEach(s => s.kill()); try { fs.rmSync(base, { recursive: true, force: true }); } catch (e) {} },
  };
}

// ---------------------------------------------------------------- realistic streams, cut into uneven pieces
function cut(text, sizes) {
  const out = [];
  let i = 0, k = 0;
  while (i < text.length) { const n = sizes[k++ % sizes.length]; out.push(text.slice(i, i + n)); i += n; }
  return out;
}
function claudeSse(text, opts) {
  opts = opts || {};
  const ev = (type, data) => 'event: ' + type + '\ndata: ' + JSON.stringify(data) + '\n\n';
  let s = ev('message_start', { type: 'message_start', message: { id: 'msg_1', type: 'message', role: 'assistant', content: [], usage: { input_tokens: opts.inTok || 120, output_tokens: 1 } } });
  s += ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
  s += ': ping\n\n' + ev('ping', { type: 'ping' });
  cut(text, [7, 13, 3]).forEach(t => { s += ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: t } }); });
  s += ev('content_block_stop', { type: 'content_block_stop', index: 0 });
  if (opts.error) s += ev('error', { type: 'error', error: { type: opts.error, message: 'Overloaded' } });
  s += ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: opts.outTok || 42 } });
  s += ev('message_stop', { type: 'message_stop' });
  return cut(s, [53, 17, 101, 9]);
}
function openaiSse(text, opts) {
  opts = opts || {};
  let s = '';
  cut(text, [5, 11]).forEach(t => { s += 'data: ' + JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content: t }, finish_reason: null }] }) + '\n\n'; });
  s += 'data: ' + JSON.stringify({ id: 'c1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }) + '\n\n';
  if (opts.usage !== false) s += 'data: ' + JSON.stringify({ id: 'c1', choices: [], usage: { prompt_tokens: 200, completion_tokens: 30 } }) + '\n\n';
  s += 'data: [DONE]\n\n';
  return cut(s, [41, 7, 77]);
}
function geminiSse(text) {
  let s = '';
  const parts = cut(text, [9, 21]);
  parts.forEach((t, i) => { s += 'data: ' + JSON.stringify({ candidates: [{ content: { role: 'model', parts: i === 0 ? [{ text: 'thinking...', thought: true }, { text: t }] : [{ text: t }] } }], usageMetadata: { promptTokenCount: 300, candidatesTokenCount: 10 + i } }) + '\r\n\r\n'; });
  return cut(s, [33, 64]);
}

module.exports = { install, claudeSse, openaiSse, geminiSse, cut };
