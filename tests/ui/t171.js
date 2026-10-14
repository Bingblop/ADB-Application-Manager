// Design tokens: every var(--x) the page uses is declared (or has a fallback), the <style> blocks take radius, type size, weight and
// spacing from the token scales instead of hand-written numbers, and the status tints follow the theme (a pink accent gets a pink active pill).
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

const html = fs.readFileSync(PAGE.startsWith('file:') ? decodeURIComponent(PAGE.slice(7)) : path.join(REPO, 'assets', 'index.html'), 'utf8');
const css = [...html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map(m => m[1].replace(/\/\*[\s\S]*?\*\//g, '')).join('\n');

// 1. names
const declared = new Set([...html.matchAll(/(?<![\w-])(--[\w-]+)\s*:/g)].map(m => m[1]));
for (const m of html.matchAll(/setProperty\(\s*['"](--[\w-]+)/g)) declared.add(m[1]);
const undeclared = new Map();
for (const m of html.matchAll(/var\(\s*(--[\w-]+)\s*(,)?/g)) {
  if (declared.has(m[1]) || m[2]) continue;           // a fallback makes a name optional (component-local knobs such as --tx-vh)
  undeclared.set(m[1], (undeclared.get(m[1]) || 0) + 1);
}
check('1. every var(--name) without a fallback is declared somewhere', undeclared.size === 0, [...undeclared].map(([k, v]) => k + 'x' + v).join(' '));

// 2. scales, declaration by declaration
const decls = [];
for (const r of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) for (const d of r[2].matchAll(/(?<![\w-])([a-z-]+)\s*:\s*([^;]+)/g)) decls.push([d[1], d[2].trim(), r[1].trim()]);
const offenders = (test) => decls.filter(([p, v, sel]) => test(p, v, sel)).map(([p, v]) => p + ': ' + v);
const px = v => [...v.matchAll(/(?<![\w.-])(-?\d+(?:\.\d+)?)px/g)].map(m => +m[1]);

const rad = offenders((p, v) => /^border(-(top|bottom)-(left|right))?-radius$/.test(p) && px(v).some(n => n >= 5 && n <= 24 || n === 999));
check('2a. border-radius uses --radius-* (0-4px, 50% and 2px tabs aside)', rad.length === 0, rad.slice(0, 5).join(' | '));
const fsz = offenders((p, v) => p === 'font-size' && px(v).some(n => n < 18 && n > 8));
check('2b. font-size uses --fs-* below 18px (icons and the big glyphs stay literal)', fsz.length === 0, fsz.slice(0, 5).join(' | '));
const fw = offenders((p, v) => p === 'font-weight' && /^\d{3}\b/.test(v));
check('2c. font-weight uses --fw-*', fw.length === 0, fw.slice(0, 5).join(' | '));
const sp = offenders((p, v) => /^(padding|margin)(-(top|right|bottom|left|inline|block)(-(start|end))?)?$|^(row-|column-)?gap$/.test(p) && !/calc|env|var\(--(?!space)|-\d/.test(v) && px(v).some(n => n >= 3 && n < 18));
check('2d. padding, margin and gap use --space-* between 3px and 17px', sp.length === 0, sp.slice(0, 5).join(' | '));
const statusHex = offenders((p, v, sel) => !p.startsWith('--') && !/\.lc-|lvl-|mark|\.hg-hit/.test(sel) && /rgba\(\s*(255,\s*82,\s*82|0,\s*230,\s*118|255,\s*145,\s*0|255,\s*196,\s*0|0,\s*229,\s*255)\s*,/.test(v));
check('2e. no hand-written rgba() of a status colour (use --tint-* / --line-*)', statusHex.length === 0, statusHex.slice(0, 5).join(' | '));

// 3. the tints follow the theme
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => { window.AndroidBridge = { vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {}, loadPackages() { return '[]'; } }; });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const probe = (accent, mode) => page.evaluate(([accent, mode]) => {
    const r = document.documentElement.style;
    r.setProperty('--accent', accent); r.setProperty('--status-user', accent === '#FF0055' ? '#FF0055' : '#FF9100'); r.setProperty('--ov', mode === 'light' ? '0, 0, 0' : '255, 255, 255');
    const mk = (cls, extra) => { const e = document.createElement('div'); e.className = cls; e.style.cssText = extra || ''; document.body.appendChild(e); return e; };
    const pill = mk('filter-pill active');
    const ref = mk('', 'background: color-mix(in srgb, ' + accent + ' 12%, transparent)');
    const out = { pill: getComputedStyle(pill).backgroundColor, ref: getComputedStyle(ref).backgroundColor };
    const veil = mk('', 'background: var(--fill-2)');
    out.fill = getComputedStyle(veil).backgroundColor;
    pill.remove(); ref.remove(); veil.remove();
    return out;
  }, [accent, mode]);
  const pink = await probe('#FF0055', 'dark');
  check('3a. a pink accent gives the active filter pill a pink wash, not the old fixed cyan', pink.pill === pink.ref, pink.pill);
  const cyan = await probe('#00E5FF', 'dark');
  check('3b. and a cyan accent still gives cyan', cyan.pill === cyan.ref && cyan.pill !== pink.pill);
  const dark = await probe('#00E5FF', 'dark'), light = await probe('#00E5FF', 'light');
  check('3c. --fill-2 is a white veil in dark and a black veil in light', /255, 255, 255|srgb 1 1 1/.test(dark.fill) && /rgba\(0, 0, 0|srgb 0 0 0/.test(light.fill), dark.fill + ' / ' + light.fill);
  check('4. no page errors', errors.length === 0, errors.join(' | '));
  await b.close();
  console.log(bad ? bad + ' FAILED' : 'all passed');
  process.exit(bad ? 1 : 0);
})();
