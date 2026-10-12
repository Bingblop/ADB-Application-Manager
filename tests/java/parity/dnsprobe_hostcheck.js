// Runs the page's pdnsCheckHost() (assets/index.html) on the inputs of DnsProbeTest and prints one answer per line, in the same order:
//   ok:<host>   or   no:<why>
// usage: node dnsprobe_hostcheck.js <index.html> < hex-encoded UTF-8 inputs, one per line (an empty line is the empty string)
'use strict';
const fs = require('fs'), vm = require('vm');
const src = fs.readFileSync(process.argv[2], 'utf8');
const i = src.indexOf('function pdnsCheckHost(');
if (i < 0) { console.error('pdnsCheckHost not found'); process.exit(2); }
let k = src.indexOf('{', i), depth = 0;
for (; k < src.length; k++) { if (src[k] === '{') depth++; else if (src[k] === '}') { depth--; if (depth === 0) break; } }
const ctx = {};
vm.createContext(ctx);
vm.runInContext(src.slice(i, k + 1) + '\nthis.f = pdnsCheckHost;', ctx);
const lines = fs.readFileSync(0, 'utf8').split('\n');
if (lines.length && lines[lines.length - 1] === '') lines.pop();
const out = lines.map(h => { const v = Buffer.from(h, 'hex').toString('utf8'); const r = ctx.f(v); return r.ok ? 'ok:' + r.host : 'no:' + r.why; });
process.stdout.write(out.join('\n') + '\n');
