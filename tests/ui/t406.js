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
  // v7.18.0: words the page says only through a literal txT call, which the extractor would otherwise take for data ("Added" for a hex colour, "ExtraLight"),
  // and the sentences of the Google Fonts fixes (a family no longer on Google Fonts and its Delete, a failed family save, the Ubuntu Font Licence, a download
  // or a save that failed while its page was not shown), and the words with a sense of their own (txC)
  const gfWords = ['x Added', 'x ExtraLight', 'x SemiBold', 'x ExtraBold', 'x No longer on Google Fonts', 'x This family is no longer on Google Fonts. Its downloaded files still work here.',
    'p The font family could not be saved: {0}', 'p {0} is under the Ubuntu Font Licence: a font made from this family must have another name, or "{1} derivative" and a name of your own.',
    'p {0} was not downloaded. {1}', 'p {0} was not saved. {1}', 'x This family is no longer on Google Fonts, so it cannot be downloaded again. Save to phone first to keep a copy.',
    // the words other screens translate in another sense, asked for with txC: their keys carry the sense in brackets
    'x Light [font weight]', 'x Display [font category]', 'x About this family'].filter(k => !now.has(k));
  check('the Google Fonts weight names, the About label "Added" and the new Google Fonts sentences are listed', !gfWords.length, JSON.stringify(gfWords));
  fs.rmSync(path.dirname(tmp), { recursive: true, force: true });
}
const ck = node([path.join(TESTS, 'i18n', 'check.js'), '--strict']);
check('every dictionary in assets/lang/ has every string of keys.json and none the page does not have (i18n/check.js --strict)', ck.status === 0, (ck.stdout || '').split('\n').filter(l => /ERROR|warn/.test(l)).slice(0, 6).join(' | '));
// German addresses the reader as Sie (T-010, T-075): the style strings of the Monet tab, the other lines of the one theme screen (the accents
// row, the three switches, the preview's note and the Style card's line; v7.22.0) and the preview line's "your wallpaper" must not say du next
// to the Sie-form "Aus Ihrem Hintergrundbild" of Colors in use
{
  const win = {};
  new Function('window', fs.readFileSync(path.join(TESTS, '..', 'assets', 'lang', 'de.js'), 'utf8'))(win);
  const de = win.__LANGS && win.__LANGS.de, px = new Map(((de && de.p) || []).map(e => [e[0], e[1]]));
  const vals = ['Like Fidelity, with a third accent chosen to sit beside your color.', 'Accents keep your exact color; backgrounds take a faint tint of it.', 'From your wallpaper',
    'One color family: accents and backgrounds are your color at different strengths.',
    'Almost grey, with only a trace of your color.',
    'One hue, five strengths: your color in full for the accent, at half and three quarters for the other accents, a fifth for the surfaces. From the 2026 Material color spec.',
    'Playful: the accents are turned far from your color.', 'Stays as close to your source color as it can, in its own strength.',
    // the accents row's line, the three switches and the preview's note (v7.22.0, the one screen)
    'Your own colors for the second and third accent. The main color stays as it is.', 'Accurate shades',
    'On: every shade keeps its computed tone. Off: the accent colors come out more vivid, with their pale tones pulled down a little.',
    'True black dark backgrounds, with the surfaces in a few dark greys. Light mode keeps its colors.',
    'On: text on surfaces takes a tint of your color. Off: plain white text in dark mode and black in light mode.',
    'Tap a color to copy it. The same palettes as ColorBlendr; the phone shows them once Apply theme has written them.',
    'How the palette is worked out from that color. Four more styles need Super User: this app computes their colors and writes them itself.',
    // the source the preview line names (a hole of the Preview of what Apply theme writes sentence)
    'your wallpaper'].map(k => de && de.x[k]).concat([px.get('The style gives #{0} now • Apply theme writes your color to the phone'), px.get('Preview of what Apply theme writes: #{0} from {1}, {2}, dark')]);
  const du = vals.filter(v => typeof v !== 'string' || /\b(du|dich|dir|dein|deine|deiner|deinem|deinen|deines)\b/i.test(v));
  check('de.js: the Monet tab\'s style strings, the switches and the preview line use the Sie form, as Colors in use does', !du.length && /Ihrem Hintergrundbild/.test(vals[vals.length - 3] || ''), JSON.stringify(du));
}
console.log(failed ? failed + ' FAILED' : 'all passed');
process.exit(failed ? 1 : 0);
