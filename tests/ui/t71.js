// v6.1 Installer, splits: the base and the splits that fit this phone (CPU, screen density, language) are ticked and every other one is left off;
// "Select all splits by default" is off by default; "Match this phone", "Select all" and "Only base" work.
const { chromium, PAGE } = require('./lib/pw');
const inst = require('./lib/inst_mock.js');
let bad = 0;
function check(label, ok, extra) { if (!ok) bad++; console.log((ok ? '' : 'FAIL ') + label + ':', ok, extra === undefined ? '' : extra); }

(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 360, height: 800 } });
  page.on('pageerror', e => { bad++; console.log('FAIL page error:', e.message); });
  await page.addInitScript(inst.initScript, { kv: { perm_intro_v61: '1' }, device: { abis: ['arm64-v8a', 'armeabi-v7a', 'armeabi'], dpi: 420, locales: ['en-US', 'es-ES'] } });
  await page.addInitScript(() => {
    const mk = (n, split, size, extra) => Object.assign({ path: '/cache/' + n, name: n, size, isBase: false, split: split || '', configFor: '', feature: false }, extra || {});
    window.__splits = [mk('base.apk', '', 60e6, { isBase: true }), mk('0__split_config.arm64_v8a.apk', 'config.arm64_v8a', 12e6), mk('1__split_config.armeabi_v7a.apk', 'config.armeabi_v7a', 9e6), mk('2__split_config.x86_64.apk', 'config.x86_64', 13e6),
      mk('3__split_config.xxhdpi.apk', 'config.xxhdpi', 2e6), mk('4__split_config.xhdpi.apk', 'config.xhdpi', 1.5e6), mk('5__split_config.ldpi.apk', 'config.ldpi', 0.5e6),
      mk('6__split_config.en.apk', 'config.en', 0.3e6), mk('7__split_config.fr.apk', 'config.fr', 0.3e6), mk('8__split_config.es.apk', 'config.es', 0.3e6),
      mk('9__split_dyn.apk', 'dyn', 4e6, { feature: true }), mk('10__split_dyn.config.arm64_v8a.apk', 'dyn.config.arm64_v8a', 1e6, { configFor: 'dyn' })];
    window.__pkgs['content://pick/1'] = { type: 'apks', pkg: 'com.example.app', label: 'Example App', versionName: '2.0', versionCode: 200, minSdk: 26, targetSdk: 34, totalSize: 105e6, splits: window.__splits, signed: true, installed: false };
  });
  await page.goto(PAGE);
  await page.waitForFunction(() => typeof window.splitsFitDevice === 'function');
  const ev = (fn, arg) => page.evaluate(fn, arg);

  // 1) the rule itself, for several phones: '+' fits, '-' does not, in the order of the splits
  const fit = (splits, profile) => ev(([sp, p]) => splitsFitDevice(sp, p).map(o => o.fits ? '+' : '-').join(''), [splits, profile]);
  const named = names => [{ isBase: true, name: 'base.apk' }].concat(names.map(n => ({ split: 'config.' + n })));
  const none = { abis: [], dpi: 0, locales: [] };
  //   splits:                                        base arm64 v7a x86_64 xxhdpi xhdpi ldpi en fr es dyn dyn.arm64
  const all = (profile) => ev(([p]) => splitsFitDevice(window.__splits, p).map(o => o.fits ? '+' : '-').join(''), [profile]);
  check('1. a 64-bit phone at 420 dpi in English and Spanish (twelve splits: base, three CPUs, three densities, three languages, a feature module and its config split)', (await all({ abis: ['arm64-v8a', 'armeabi-v7a'], dpi: 420, locales: ['en-US', 'es-ES'] })) === '++--+--+-+--', await all({ abis: ['arm64-v8a', 'armeabi-v7a'], dpi: 420, locales: ['en-US', 'es-ES'] }));
  check('   a 32-bit phone at 320 dpi in French (Canada)', (await all({ abis: ['armeabi-v7a', 'armeabi'], dpi: 320, locales: ['fr-CA'] })) === '+-+--+--+---', await all({ abis: ['armeabi-v7a', 'armeabi'], dpi: 320, locales: ['fr-CA'] }));
  check('   a phone of 640 dpi, bigger than any split: the biggest there is', (await fit(named(['hdpi', 'xxhdpi', 'xhdpi']), { abis: [], dpi: 640, locales: [] })) === '+-+-');
  check('   a phone of 100 dpi, smaller than any split: the smallest there is', (await fit(named(['mdpi', 'ldpi', 'hdpi']), { abis: [], dpi: 100, locales: [] })) === '+-+-');
  // between two densities Android takes the lower one when (2 * lower - phone) * higher > phone * phone (ResTable_config::isBetterThan), else the higher
  check('   a density between two: the one Android itself would take (220 dpi: tvdpi 213 beats hdpi 240)', (await fit(named(['tvdpi', 'hdpi', 'mdpi']), { abis: [], dpi: 220, locales: [] })) === '++--');
  const dens = (dpi, keys) => fit(named(keys), { abis: [], dpi, locales: [] });
  check('   340 dpi, with xhdpi (320) and xxhdpi (480): xhdpi, the nearer (the old "at or above" rule took the bigger one)', (await dens(340, ['xxhdpi', 'xhdpi'])) === '+-+');
  check('   360 dpi: still xhdpi (its pull towards the bigger one is not enough yet)', (await dens(360, ['xxhdpi', 'xhdpi'])) === '+-+');
  check('   380 dpi: xxhdpi, from here on the bigger one', (await dens(380, ['xxhdpi', 'xhdpi'])) === '++-');
  check('   420 dpi and 450 dpi: xxhdpi', (await dens(420, ['xhdpi', 'xxhdpi'])) === '+-+' && (await dens(450, ['xhdpi', 'xxhdpi'])) === '+-+');
  check('   500 dpi, with xxhdpi (480) and xxxhdpi (640): xxhdpi; 560 dpi: xxxhdpi', (await dens(500, ['xxxhdpi', 'xxhdpi'])) === '+-+' && (await dens(560, ['xxxhdpi', 'xxhdpi'])) === '++-');
  check('   a density the phone has exactly wins, whatever else is there', (await dens(480, ['xhdpi', 'xxhdpi', 'xxxhdpi'])) === '+-+-' && (await dens(320, ['xxhdpi', 'xhdpi', 'hdpi'])) === '+-+-');
  check('   a "420dpi" style split counts by its number', (await dens(420, ['280dpi', '420dpi', '560dpi'])) === '+-+-');
  check('   all seven densities at 420 dpi: xxhdpi', (await dens(420, ['ldpi', 'mdpi', 'tvdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'])) === '+-----+-');
  check('   only the first CPU of the phone that has a split is ticked (x86_64 before x86)', (await fit(named(['x86', 'x86_64', 'arm64_v8a']), { abis: ['x86_64', 'x86'], dpi: 0, locales: [] })) === '+-+-');
  check('   the second CPU is used when the first has no split', (await fit(named(['armeabi_v7a', 'x86']), { abis: ['arm64-v8a', 'armeabi-v7a'], dpi: 0, locales: [] })) === '++-');
  check('   a phone whose CPU, density and languages are unknown ticks only the base', (await fit(named(['arm64_v8a', 'xxhdpi', 'en']), none)) === '+---');
  // languages
  const langs = (locales, keys) => fit(named(keys), { abis: [], dpi: 0, locales });
  check('   Hebrew: the phone says "he-IL", a split says "iw" (the old code of the same language)', (await langs(['he-IL'], ['iw', 'he', 'en'])) === '+++-');
  check('   Indonesian: "in-ID" (old) matches "id" and "in"', (await langs(['in-ID'], ['id', 'in', 'ms'])) === '+++-');
  check('   a region has to agree when both name one: pt-PT takes pt_rPT and pt, not pt_rBR', (await langs(['pt-PT'], ['pt_rBR', 'pt_rPT', 'pt'])) === '+-++');
  check('   …and pt-BR takes pt_rBR and pt, not pt_rPT', (await langs(['pt-BR'], ['pt_rBR', 'pt_rPT', 'pt'])) === '++-+');
  check('   a phone with only "pt" takes every region of it', (await langs(['pt'], ['pt_rBR', 'pt_rPT'])) === '+++');
  check('   a script in the phone\'s tag (zh-Hans-CN) does not get in the way: zh and zh_rCN, not zh_rTW', (await langs(['zh-Hans-CN'], ['zh', 'zh_rCN', 'zh_rTW'])) === '+++-');
  check('   every language of the phone\'s list is ticked, wherever it is in the list', (await langs(['en-US', 'de-DE', 'ja-JP'], ['ja', 'de', 'en', 'it'])) === '++++-');
  check('   a Unicode extension in the phone\'s tag is not a region ("ar-u-nu-latn", "en-US-x-twain")', JSON.stringify(await ev(() => [parseLocaleTag('ar-u-nu-latn'), parseLocaleTag('pt-BR-u-ca-gregory'), parseLocaleTag('en-US-x-twain'), parseLocaleTag('zh-Hant-TW')])) === '[{"lang":"ar","region":""},{"lang":"pt","region":"BR"},{"lang":"en","region":"US"},{"lang":"zh","region":"TW"}]');
  check('   a split called "constructor" or "__proto__" is just another split: no code of Object shows in its note', await ev(() => { const o = splitsFitDevice([{ isBase: true, name: 'base.apk' }, { split: 'config.constructor' }, { split: 'config.__proto__' }, { split: 'config.toString' }, { split: 'config.hasOwnProperty' }], { abis: ['arm64-v8a'], dpi: 420, locales: ['en'] }); return o.every(x => !/function|native code|\[object/.test(x.note)) && o.slice(1).every(x => x.kind === 'other' && x.fits === false && x.note === 'optional extra'); }));
  // names: from the file name when the manifest gave none, and what is not a CPU / density / language
  check('   a split with no manifest name is judged by its file name ("3__split_config.arm64_v8a.apk")', (await fit([{ isBase: true, name: 'base.apk' }, { name: '3__split_config.arm64_v8a.apk' }, { name: '4__split_config.xxhdpi.apk' }, { name: '5__split_config.en.apk' }, { name: '6__split_config.fr.apk' }], { abis: ['arm64-v8a'], dpi: 480, locales: ['en'] })) === '++++-');
  check('   texture, night-mode, SDK and unknown splits are not ticked', (await fit([{ isBase: true, name: 'base.apk' }, { split: 'config.tcf_astc' }, { split: 'config.night' }, { split: 'config.sdk_28' }, { split: 'something_else' }], { abis: ['arm64-v8a'], dpi: 480, locales: ['en'] })) === '+----');
  check('   a feature module and its config split are never ticked, even when their CPU would fit', (await all({ abis: ['arm64-v8a'], dpi: 420, locales: ['en'] })).slice(-2) === '--');
  check('   the base is always in', (await fit([{ isBase: true, name: 'base.apk' }], none)) === '+');
  const notes = await ev(() => splitsFitDevice(window.__splits, { abis: ['arm64-v8a'], dpi: 420, locales: ['en'] }).map(o => o.note));
  check('   every split says why: it fits (✓ …) or what it is for', notes[1].startsWith('✓ fits this phone') && /CPU arm64-v8a/.test(notes[1]) && /another CPU/.test(notes[2]) && /screen xxhdpi/.test(notes[4]) && /another screen density/.test(notes[5]) && /language en/.test(notes[7]) && /another language/.test(notes[8]) && /feature module/.test(notes[10]) && /feature module/.test(notes[11]), JSON.stringify(notes));

  // 2) on screen
  const repick = async () => { await ev(() => pickInstallFile()); await page.waitForFunction(() => /^Loaded/.test(document.getElementById('installPickHint').innerText)); };
  const state = () => ev(() => Array.from(document.querySelectorAll('#installSplitsList .install-split-cb')).map(cb => (cb.checked ? '+' : '-') + (cb.disabled ? 'D' : '')).join(' '));
  await ev(() => switchView('installer'));
  await repick();
  check('2. "Select all splits by default" is off to start with', (await ev(() => document.getElementById('optSelectAllSplits').checked)) === false);
  check('   so only the base and the splits that fit are ticked (arm64, xxhdpi, en, es); the base cannot be unticked', (await state()) === '+D + - - + - - + - + - -', await state());
  check('   the install would send exactly those five files', (await ev(() => buildInstallOptions().opts.splits.length)) === 5);
  const rows = await ev(() => Array.from(document.querySelectorAll('#installSplitsList .switch-row')).map(r => r.innerText.replace(/\s+/g, ' ').trim()));
  check('   each row shows its file name without the numbering of the unpacking, its size and why', /^config.arm64_v8a split_config.arm64_v8a.apk • 11.4 MB • ✓ fits this phone \(CPU arm64-v8a\)$/.test(rows[1]) && /another CPU \(armeabi-v7a\)/.test(rows[2]) && !/\d+__/.test(rows.join(' ')), rows[1]);
  check('   the card says the splits that fit are ticked and the others left off', await ev(() => /fit this phone are ticked/.test(document.getElementById('installSplitsSub').innerText)));
  check('   …and that sentence is not shown when "Select all" is the default (then every split starts ticked)', await (async () => {
    await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = true; c.dispatchEvent(new Event('change')); });
    const on = await ev(() => document.getElementById('installSplitsSub').innerText);
    await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = false; c.dispatchEvent(new Event('change')); });
    return /every other split starts ticked/.test(on) && !/left off/.test(on) && /left off for you to add/.test(await ev(() => document.getElementById('installSplitsSub').innerText));
  })());
  await page.click('#installSplitsCard >> text=Select all');
  check('   "Select all" ticks every split', (await state()) === '+D + + + + + + + + + + +', await state());
  await page.click('#installSplitsCard >> text=Only base');
  check('   "Only base" leaves the base', (await state()) === '+D - - - - - - - - - - -', await state());
  await page.click('#installSplitsCard >> text=Match this phone');
  check('   "Match this phone" goes back to the fitting ones', (await state()) === '+D + - - + - - + - + - -', await state());
  await ev(() => { document.querySelector('#installSplitsList .install-split-cb[data-idx="8"]').click(); });        // French: the user adds one
  check('   the user can add a split that does not fit (French)', (await ev(() => buildInstallOptions().opts.splits.length)) === 6);
  await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = true; c.dispatchEvent(new Event('change')); });
  check('   turning "Select all splits by default" on ticks every split of the package that is open', (await state()) === '+D + + + + + + + + + + +', await state());
  await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = false; c.dispatchEvent(new Event('change')); });
  check('   and off again goes back to the fitting ones', (await state()) === '+D + - - + - - + - + - -', await state());
  await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = true; c.dispatchEvent(new Event('change')); });
  await repick();
  check('   with it on, the next package that is opened has every split ticked', (await ev(() => document.querySelectorAll('#installSplitsList .install-split-cb:checked').length)) === 12);
  await ev(() => { const c = document.getElementById('optSelectAllSplits'); c.checked = false; c.dispatchEvent(new Event('change')); });

  // 3) another phone gets other splits (the page asks the phone each time a package is opened)
  await ev(() => { window.__device.abis = ['armeabi-v7a']; window.__device.dpi = 320; window.__device.locales = ['fr-FR']; });
  await repick();
  check('3. another phone (32-bit, 320 dpi, French): the base, armeabi_v7a, xhdpi and fr', (await state()) === '+D - + - - + - - + - - -', await state());

  // 4) a single APK has no splits card at all
  await ev(() => { window.__pkgs['content://pick/2'] = { type: 'apk', pkg: 'com.single', label: 'Single', versionName: '1', versionCode: 1, minSdk: 26, targetSdk: 34, totalSize: 4e6, splits: [{ path: '/cache/base.apk', name: 'base.apk', size: 4e6, isBase: true, split: '' }], signed: true, installed: false }; onInstallFilePicked('content://pick/2'); });
  await page.waitForFunction(() => document.getElementById('installPkgLabel').innerText === 'Single');
  check('4. a single APK has no splits card and installs its one file', !(await page.isVisible('#installSplitsCard')) && (await ev(() => buildInstallOptions().opts.splits.length)) === 1);

  console.log(bad ? bad + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(bad ? 1 : 0);
})();
