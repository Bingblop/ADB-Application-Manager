// Translations stay complete (PG4-3, PG3-2): tests/i18n/keys.json lists exactly the strings of the page (extract.js is run again into a temporary file and the two
// lists are compared, kind and text; the "ctx" notes are not compared), and every dictionary in assets/lang/ has every string and no string the page lost
// (i18n/check.js --strict). A string added to the page without running extract.js and translating it, or a key the page no longer has, fails here.
// The extractor used to skip every txT/txF text with a '<' or '>' in it, so "… Settings > Battery …" was never listed and never translated.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { TESTS } = require('./lib/pw');
let failed = 0;
const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
const node = (args) => spawnSync(process.execPath, args, { cwd: TESTS, encoding: 'utf8', env: process.env, timeout: 240000 });

const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'keys-')), 'keys.json');
const ex = node([path.join(TESTS, 'i18n', 'extract.js'), '--out', tmp]);
check('extract.js runs', ex.status === 0 && fs.existsSync(tmp), (ex.stderr || ex.stdout || '').slice(-400));
if (fs.existsSync(tmp)) {
  const list = f => { const j = JSON.parse(fs.readFileSync(f, 'utf8')); return new Set(['x', 'p', 'b'].flatMap(kind => (j[kind] || []).map(e => kind + ' ' + e.k))); };
  const now = list(tmp), saved = list(path.join(TESTS, 'i18n', 'keys.json'));
  const missing = [...now].filter(k => !saved.has(k)), gone = [...saved].filter(k => !now.has(k));
  check('tests/i18n/keys.json has every string of the page (run node i18n/extract.js after changing a text)', !missing.length, JSON.stringify(missing.slice(0, 5)) + (missing.length > 5 ? ' and ' + (missing.length - 5) + ' more' : ''));
  check('tests/i18n/keys.json has no string the page lost', !gone.length, JSON.stringify(gone.slice(0, 5)) + (gone.length > 5 ? ' and ' + (gone.length - 5) + ' more' : ''));
  const gt = [...now].filter(k => /^[xp] .*>/.test(k) && !/<\/?[A-Za-z0-9][^<>]*>/.test(k)).length;
  check('texts of txT/txF with a ">" in them ("Settings > Battery") are listed', gt >= 2, String(gt));
  fs.rmSync(path.dirname(tmp), { recursive: true, force: true });
}
const ck = node([path.join(TESTS, 'i18n', 'check.js'), '--strict']);
check('every dictionary in assets/lang/ has every string of keys.json and none the page does not have (i18n/check.js --strict)', ck.status === 0, (ck.stdout || '').split('\n').filter(l => /ERROR|warn/.test(l)).slice(0, 6).join(' | '));
// German addresses the reader as Sie (T-010, T-075): the More styles strings of PR 3 and the theme preview line must not say du next to the
// Sie-form "Aus Ihrem Hintergrundbild" of Colors in use
{
  const win = {};
  new Function('window', fs.readFileSync(path.join(TESTS, '..', 'assets', 'lang', 'de.js'), 'utf8'))(win);
  const de = win.__LANGS && win.__LANGS.de, px = new Map(((de && de.p) || []).map(e => [e[0], e[1]]));
  const vals = ['Like Fidelity, with a third accent chosen to sit beside your color.', 'Accents keep your exact color; backgrounds take a faint tint of it.', 'From your wallpaper'].map(k => de && de.x[k]).concat([px.get('From your wallpaper (#{0})')]);
  const du = vals.filter(v => typeof v !== 'string' || /\b(du|dich|dir|dein|deine|deiner|deinem|deinen|deines)\b/i.test(v));
  check('de.js: the More styles strings and the wallpaper preview line use the Sie form, as Colors in use does', !du.length && /Ihrem Hintergrundbild/.test(vals[3] || ''), JSON.stringify(du));
}
console.log(failed ? failed + ' FAILED' : 'all passed');
process.exit(failed ? 1 : 0);
