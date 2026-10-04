#!/usr/bin/env node
// Runs the Java checks of the parts of the app that need no Android classes (settings and overlay rules, file rules, install hints, the
// archive engine, the Rish shell, the APK signer, the manifest decoder, the store parsers ...). The classes under test are compiled straight
// from src/ together with the test in tests/java/src, with the JDK on this machine.
//
//   node java/run.js                 every suite
//   node java/run.js ziptool rish    only these
//   node java/run.js --list          name, what it covers and what it needs
//   node java/run.js --jobs 2        suites side by side (default 1: the Rish suites count processes, so keep them apart)
//
// A suite passes when it exits with 0 and prints no line starting with "FAIL". A suite whose prerequisite is missing is SKIPPED (and
// says what to install); that is not a failure, unless you pass --strict.
//
// Prerequisites by suite (see --list): a JDK (javac + java), Node for the parity suite, org.json (ORG_JSON_JAR, see below) for the store
// parsers, python3 + zip for the archive suites, mksh + toybox for Rish, an APK (TEST_APK, default bin/ADB_Application_Manager_Pro.apk
// from ./build.sh) + apksigner (APKSIGNER, or on PATH, or in ANDROID_HOME/build-tools) + keytool for the signer.
// org.json: Android's copy is only stubs, so pass a real one: ORG_JSON_JAR=/path/json-20240303.jar
//   (curl -L -o tests/.cache/org-json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar), or ANDROID_ALL_JAR=/path/android-all-*.jar
//   (Robolectric's all-in-one Android jar, which has org.json and the real android.util.JsonReader the F-Droid parser uses).
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, spawnSync } = require('child_process');

const HERE = __dirname;
const TESTS = path.resolve(HERE, '..');
const REPO = path.resolve(TESTS, '..');
const JSRC = path.join(REPO, 'src');                       // source path root: com/bloatware/bingblop/*.java
const TSRC = path.join(HERE, 'src');
const OUT = path.join(TESTS, 'out', 'java');
const CACHE = path.join(TESTS, '.cache');

const SUITES = [
  { name: 'settingsdb', title: 'Android settings (Global / Secure / System): names, values, quoting, read-back verdicts, run through a real sh with a fake settings command',
    tests: ['SettingsDbTest'], main: 'com.bloatware.bingblop.SettingsDbTest', utf8: true },
  { name: 'overlayrules', title: 'Overlays and Material You: cmd overlay output, names, the theme value and its merge, every verdict and the Samsung switch, against a fake phone',
    tests: ['OverlayRulesTest'], main: 'com.bloatware.bingblop.OverlayRulesTest', utf8: true },
  { name: 'parity', title: 'The page and the Java rules give the same answer to the same names, values and colors (compares ~1,300 inputs)',
    kind: 'parity', needs: ['node'] },
  { name: 'filerules', title: 'File manager path rules: canonical form, protected folders', tests: ['FileRulesTest'], main: 'com.bloatware.bingblop.FileRulesTest' },
  { name: 'apktrash', title: 'Deleting a found package file with Undo (which paths, where it waits) and the storage search\'s progress, against a real folder tree', tests: ['ApkTrashTest'], main: 'com.bloatware.bingblop.ApkTrashTest', needs: ['json'] },
  { name: 'fontscan', title: 'Font search for the app font: which files are fonts, what a font calls itself (the name table in every encoding and shape), the bounded walk and its progress', tests: ['FontScanTest'], main: 'FontScanTest', needs: ['json'] },
  { name: 'splitinfo', title: 'What a split APK\'s manifest says about it (split name, the feature module it configures, feature flag)', tests: ['SplitInfoTest'], main: 'com.bloatware.bingblop.SplitInfoTest' },
  { name: 'installhints', title: 'What an install failure means (adb / pm install answers)', tests: ['InstallHintsTest'], main: 'com.bloatware.bingblop.InstallHintsTest' },
  { name: 'pure', title: 'Package-file scan output and XAPK data paths', tests: ['PureTest'], main: 'PureTest', needs: ['json'] },
  { name: 'fdroid', title: 'F-Droid index v1 / v2 parsing (real Seeker and WG Tunnel indexes)', tests: ['FdroidTest'], main: 'com.bloatware.bingblop.FdroidTest', needs: ['androidall', 'fixtures'] },
  { name: 'komi', title: 'GitHub catalog (Komi) feeds: mapping, de-duplication, dates', tests: ['KomiTest'], main: 'KomiTest', needs: ['json', 'fixtures'] },
  { name: 'ziptool', title: 'Archive engine: read, edit and rewrite zips (bad, truncated, encrypted, huge, zip64, streamed), alignment, diff', tests: ['ZipToolTest'], main: 'ZipToolTest', needs: ['zipfix1', 'apk?'] },
  { name: 'zipreview', title: 'Archive engine, review findings: odd names, duplicates, prepended data, extra fields, AES, symlinks', tests: ['ZipReviewTest'], main: 'ZipReviewTest', needs: ['zipfix2'] },
  { name: 'rish', title: 'Rish shell: persistent shell, cd / export, output, STOP, restart (against this machine\'s sh)', tests: ['RishTest'], main: 'RishTest' },
  { name: 'rishreview', title: 'Rish shell, review findings, against a real mksh with toybox applets (Android\'s shell)', tests: ['RishReviewTest'], main: 'RishReviewTest', needs: ['rish'] },
  { name: 'signer', title: 'In-app APK signer: v2 signatures checked with apksigner, RSA and EC keys, re-sign, edited and big APKs, tampering', tests: ['SignerTest'], main: 'SignerTest', needs: ['apk', 'apksigner', 'keys'] },
  { name: 'signmismatch', title: 'APK signer refuses a key that does not match its certificate', tests: ['SignMismatchTest'], main: 'SignMismatchTest', needs: ['apk', 'keys'] },
  { name: 'axml', title: 'Manifest decoder reads every compiled XML file of a real APK', tests: ['AxmlTest'], main: 'AxmlTest', needs: ['apk', 'stubs'] },
];

// ---------- prerequisites ----------
function which(cmd) {
  const dirs = (process.env.PATH || '').split(path.delimiter);
  for (const d of dirs) { const p = path.join(d, cmd); try { fs.accessSync(p, fs.constants.X_OK); if (fs.statSync(p).isFile()) return p; } catch (e) { /* next */ } }
  return null;
}
function run(cmd, args, opts) { return spawnSync(cmd, args, Object.assign({ encoding: 'utf8' }, opts)); }

function newestFile(dir, re) {
  let best = null;
  const walk = d => { let es; try { es = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; } for (const e of es) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (re.test(e.name) && (!best || p > best)) best = p; } };
  walk(dir);
  return best;
}

const NEEDS = {
  node: () => ({}),
  json: () => {
    const m2 = path.join(os.homedir(), '.m2', 'repository', 'org');
    const cands = [process.env.ORG_JSON_JAR, path.join(CACHE, 'org-json.jar'), newestFile(path.join(m2, 'json'), /^json-.*\.jar$/), process.env.ANDROID_ALL_JAR, newestFile(path.join(m2, 'robolectric', 'android-all'), /^android-all-.*\.jar$/)].filter(Boolean);
    const jar = cands.find(p => fs.existsSync(p));
    return jar ? { cp: [jar] } : { skip: 'needs org.json: set ORG_JSON_JAR, or curl -L -o tests/.cache/org-json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar' };
  },
  androidall: () => {
    const cands = [process.env.ANDROID_ALL_JAR, newestFile(path.join(os.homedir(), '.m2', 'repository', 'org', 'robolectric', 'android-all'), /^android-all-.*\.jar$/)].filter(Boolean);
    const jar = cands.find(p => fs.existsSync(p));
    return jar ? { cp: [jar] } : { skip: 'needs Android\'s real framework classes (android.util.JsonReader): set ANDROID_ALL_JAR to a Robolectric android-all jar' };
  },
  stubs: () => ({ sp: [path.join(HERE, 'stubs')] }),
  fixtures: () => ({ props: { fixtures: path.join(HERE, 'fixtures') } }),
  zipfix1: () => zipFixtures('zipfix1', 'mkzip1.py', 'notzip.bin'),
  zipfix2: () => zipFixtures('zipfix2', 'mkzip2.py', 'big_uncompressed.zip'),
  apk: optional => {
    const cands = [process.env.TEST_APK, path.join(REPO, 'bin', 'ADB_Application_Manager_Pro.apk')].filter(Boolean);
    const apk = cands.find(p => fs.existsSync(p));
    if (apk) return { props: { testapk: apk } };
    return optional ? {} : { skip: 'needs an APK: run ./build.sh, or set TEST_APK=/path/app.apk' };
  },
  apksigner: () => {
    let p = process.env.APKSIGNER && fs.existsSync(process.env.APKSIGNER) ? process.env.APKSIGNER : which('apksigner');
    if (!p) for (const home of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean)) p = p || newestFile(path.join(home, 'build-tools'), /^apksigner$/);
    return p ? { props: { apksigner: p } } : { skip: 'needs apksigner (Android build-tools): set APKSIGNER or ANDROID_HOME' };
  },
  keys: () => {
    if (!which('keytool')) return { skip: 'needs keytool (part of the JDK)' };
    const dir = path.join(OUT, 'keys');
    fs.mkdirSync(dir, { recursive: true });
    const mk = (file, args) => {
      if (fs.existsSync(path.join(dir, file))) return null;
      const r = run('keytool', ['-genkeypair', '-storetype', 'PKCS12', '-keystore', path.join(dir, file), '-storepass', 'password', '-keypass', 'password', '-validity', '3650'].concat(args));
      return r.status === 0 ? null : 'keytool failed: ' + (r.stderr || r.stdout).slice(0, 200);
    };
    const err = mk('rsa.p12', ['-alias', 'rsa', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=test rsa']) || mk('ec.p12', ['-alias', 'ec', '-keyalg', 'EC', '-groupname', 'secp256r1', '-dname', 'CN=test ec']);
    return err ? { skip: err } : { props: { keys: dir } };
  },
  rish: () => {
    const mksh = which('mksh'), toybox = which('toybox');
    if (!mksh || !toybox) return { skip: 'needs mksh and toybox (apt install mksh toybox)' };
    const env = path.join(OUT, 'rishenv');
    fs.rmSync(env, { recursive: true, force: true });
    fs.mkdirSync(path.join(env, 'bin'), { recursive: true });
    fs.mkdirSync(path.join(env, 'tb'), { recursive: true });
    fs.symlinkSync(mksh, path.join(env, 'bin', 'mksh'));
    fs.symlinkSync(toybox, path.join(env, 'bin', 'toybox'));
    for (const a of ['cat', 'env', 'getprop', 'grep', 'head', 'id', 'kill', 'nohup', 'pgrep', 'pkill', 'printf', 'ps', 'seq', 'setsid', 'sleep', 'tail', 'tee', 'timeout', 'yes']) fs.symlinkSync(path.join(env, 'bin', 'toybox'), path.join(env, 'tb', a));
    return { props: { rishenv: env } };
  },
};

function zipFixtures(dirName, script, sentinel) {
  if (!which('python3')) return { skip: 'needs python3' };
  if (script === 'mkzip1.py' && !which('zip')) return { skip: 'needs the zip command (an encrypted test archive is made with it)' };
  const dir = path.join(OUT, dirName);
  if (!fs.existsSync(path.join(dir, sentinel))) {
    fs.mkdirSync(dir, { recursive: true });
    const r = run('python3', [path.join(HERE, 'zip', script), dir]);
    if (r.status !== 0) return { skip: 'could not make the test archives: ' + (r.stderr || r.stdout).slice(0, 300) };
  }
  const props = {}; props[dirName] = dir;
  if (dirName === 'zipfix2') props.zipdump = path.join(HERE, 'zip', 'zipdump.py');
  return { props };
}

function resolveNeeds(suite) {
  const out = { props: {}, cp: [], sp: [] };
  for (const n of suite.needs || []) {
    const optional = n.endsWith('?'), id = n.replace('?', '');
    const r = NEEDS[id](optional);
    if (r.skip) return { skip: r.skip };
    Object.assign(out.props, r.props || {});
    out.cp.push(...(r.cp || []));
    out.sp.push(...(r.sp || []));
  }
  return out;
}

// ---------- running ----------
function exec(cmd, args, opts, timeoutS) {
  return new Promise(resolve => {
    const child = spawn(cmd, args, opts);
    let out = '', timedOut = false;
    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { out += d; });
    const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeoutS * 1000);
    child.on('error', e => { clearTimeout(timer); resolve({ code: 127, out: out + String(e), timedOut }); });
    child.on('close', code => { clearTimeout(timer); resolve({ code, out, timedOut }); });
  });
}
const clean = text => text.split('\n').filter(l => !l.startsWith('Picked up ')).join('\n');

async function runSuite(suite, timeoutS) {
  const started = Date.now();
  const dir = path.join(OUT, suite.name), classes = path.join(dir, 'classes'), work = path.join(dir, 'run');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(classes, { recursive: true });
  fs.mkdirSync(work, { recursive: true });
  const need = resolveNeeds(suite);
  if (need.skip) return { name: suite.name, status: 'SKIP', why: [need.skip], seconds: 0 };

  const files = suite.kind === 'parity' ? [path.join(TSRC, 'ParityDump2.java')] : suite.tests.map(t => path.join(TSRC, t + '.java'));
  const cpSep = path.delimiter;
  const libs = fs.readdirSync(path.join(REPO, 'libs')).filter(f => f.endsWith('.jar')).map(f => path.join(REPO, 'libs', f));
  need.cp = Array.from(new Set(need.cp.concat(libs)));
  const cp = need.cp.join(cpSep);
  const javac = await exec('javac', ['-encoding', 'UTF-8', '-nowarn', '-sourcepath', [JSRC].concat(need.sp).join(cpSep), '-d', classes].concat(cp ? ['-cp', cp] : [], files), { cwd: work }, timeoutS);
  fs.writeFileSync(path.join(dir, 'compile.txt'), javac.out);
  if (javac.code !== 0) return { name: suite.name, status: 'FAIL', why: ['does not compile', clean(javac.out).split('\n').filter(Boolean).slice(0, 6).join('\n          ')], seconds: (Date.now() - started) / 1000 };

  const props = Object.entries(need.props).map(([k, v]) => '-D' + k + '=' + v);
  const env = Object.assign({}, process.env, suite.utf8 ? { LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' } : {});
  let res;
  if (suite.kind === 'parity') {
    const penv = Object.assign({}, env, { LC_ALL: 'C.UTF-8', PARITY_HTML: path.join(REPO, 'assets', 'index.html'), PARITY_CLASSES: classes, PARITY_CASES: path.join(HERE, 'parity', 'parity_cases.json') });
    res = await exec(process.execPath, [path.join(HERE, 'parity', 'parity_all.js')], { cwd: work, env: penv }, timeoutS);
  } else {
    const jvm = suite.utf8 ? ['-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8'] : [];
    res = await exec('java', jvm.concat(props, ['-cp', [classes].concat(need.cp).join(cpSep), suite.main]), { cwd: work, env }, timeoutS);
  }
  const text = clean(res.out);
  fs.writeFileSync(path.join(dir, 'output.txt'), text);
  const why = [];
  if (res.timedOut) why.push('timed out');
  else if (res.code !== 0) why.push('exit code ' + res.code);
  const fails = text.split('\n').filter(l => /^FAIL\b/.test(l) || /^DIFF\b/.test(l));
  if (fails.length) why.push(fails.length + ' failing check' + (fails.length > 1 ? 's' : '') + ': ' + fails.slice(0, 3).map(l => l.slice(0, 140)).join(' | '));
  const last = text.trim().split('\n').pop() || '';
  return { name: suite.name, status: why.length ? 'FAIL' : 'PASS', why, summary: last.slice(0, 90), seconds: (Date.now() - started) / 1000 };
}

async function main() {
  const args = process.argv.slice(2);
  let jobs = 1, timeoutS = 900, strict = false, list = false;
  const names = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--jobs') jobs = Math.max(1, +args[++i] || 1);
    else if (a === '--timeout') timeoutS = +args[++i] || 900;
    else if (a === '--strict') strict = true;
    else if (a === '--list') list = true;
    else if (a === '-h' || a === '--help') { console.log(fs.readFileSync(__filename, 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n')); return 0; }
    else names.push(a);
  }
  const unknown = names.filter(n => !SUITES.some(s => s.name === n));
  if (unknown.length) { console.error('unknown suite: ' + unknown.join(', ')); return 2; }
  const selected = names.length ? SUITES.filter(s => names.includes(s.name)) : SUITES;
  if (list) { for (const s of selected) console.log(s.name.padEnd(13), s.title + ((s.needs || []).length ? '   [needs: ' + s.needs.join(', ') + ']' : '')); return 0; }
  if (!which('javac') || !which('java')) { console.error('A JDK (javac and java) is needed.'); return 2; }

  fs.mkdirSync(OUT, { recursive: true });
  const started = Date.now();
  const results = [];
  let next = 0;
  const worker = async () => {
    while (next < selected.length) {
      const s = selected[next++];
      const r = await runSuite(s, timeoutS);
      results.push(r);
      console.log(`${String(results.length).padStart(2)}/${selected.length} ${r.name.padEnd(13)} ${(r.status === 'PASS' ? 'PASS' : r.status + '  <<<<').padEnd(10)} ${r.seconds.toFixed(0).padStart(3)}s  ${r.status === 'PASS' ? (r.summary || '') : s.title.slice(0, 80)}`);
      if (r.status !== 'PASS') for (const w of r.why) console.log('          ' + w);
    }
  };
  await Promise.all(Array.from({ length: Math.min(jobs, selected.length) }, worker));

  const bad = results.filter(r => r.status === 'FAIL' || (strict && r.status === 'SKIP'));
  const skipped = results.filter(r => r.status === 'SKIP').length;
  console.log('');
  console.log(bad.length ? `${bad.length} of ${results.length} suites need a look: ${bad.map(r => r.name).join(', ')}`
    : `All ${results.length - skipped} suites passed${skipped ? ', ' + skipped + ' skipped (a prerequisite is missing)' : ''} (${((Date.now() - started) / 1000).toFixed(0)}s).`);
  return bad.length ? 1 : 0;
}

main().then(code => process.exit(code), e => { console.error(e); process.exit(2); });
